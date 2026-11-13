package dev.sebastiano.ciacola.inspector.element

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.sebastiano.ciacola.inspector.DeckElementOverridesFile
import dev.sebastiano.ciacola.inspector.ElementPatch
import java.awt.EventQueue
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.FileTime
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Persistent, undoable store for per-element stage overrides stored at
 * `.storyboard/element-overrides.json`.
 *
 * Supports epoch-ordered off-EDT atomic saves, corrupt-file backup, `Dispatchers.IO` mtime+size
 * external reload, 64-step Undo/Redo with gesture & key-repeat/typing coalescing + debounce,
 * granular element reset, and Kotlin snippet export.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ElementOverridesStore(
    val storyboardDir: Path = Path.of(".storyboard"),
    private val clockMillis: () -> Long = System::currentTimeMillis,
    externalIoScope: CoroutineScope? = null,
) : AutoCloseable {
    companion object {
        const val MAX_UNDO_HISTORY = 64
        private const val COALESCE_WINDOW_MS = 400L
        private const val DEBOUNCE_PERSIST_MS = 200L
    }

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    private val ownsIoScope = externalIoScope == null
    private val ioScope: CoroutineScope = externalIoScope
        ?: CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private val writeLock = Any()

    val overridesFile: Path = storyboardDir.resolve("element-overrides.json")

    private var currentData: DeckElementOverridesFile = DeckElementOverridesFile()
    private var lastObservedModifiedTime: FileTime? = null
    private var lastObservedFileSize: Long = -1L

    private val undoStack = ArrayDeque<DeckElementOverridesFile>()
    private val redoStack = ArrayDeque<DeckElementOverridesFile>()
    private var activeGestureDepth = 0
    private var gestureSnapshotRecorded = false
    private var pendingPersist = false
    private var persistEpoch = 0L
    private var lastWrittenEpoch = 0L
    private var debouncePersistJob: Job? = null
    private var lastCoalesceKey: String? = null
    private var lastMutationMillis: Long = Long.MIN_VALUE

    var canUndo by mutableStateOf(false)
        private set

    var canRedo by mutableStateOf(false)
        private set

    var revision by mutableIntStateOf(0)
        private set

    init {
        reloadFromDiskIfChanged(notifyRevision = false)
    }

    private fun syncUndoFlags() {
        canUndo = undoStack.isNotEmpty()
        canRedo = redoStack.isNotEmpty()
    }

    private fun recordUndoSnapshotBeforeMutation(coalesceKey: String?, coalesce: Boolean) {
        val now = clockMillis()
        redoStack.clear()
        val shouldCoalesce = when {
            activeGestureDepth > 0 -> gestureSnapshotRecorded
            coalesce && coalesceKey != null && coalesceKey == lastCoalesceKey &&
                lastMutationMillis != Long.MIN_VALUE && (now - lastMutationMillis) in 0..COALESCE_WINDOW_MS -> true
            else -> false
        }
        if (!shouldCoalesce) {
            if (undoStack.size >= MAX_UNDO_HISTORY) {
                undoStack.removeFirst()
            }
            undoStack.addLast(currentData)
            if (activeGestureDepth > 0) {
                gestureSnapshotRecorded = true
            }
        }
        lastCoalesceKey = if (coalesce) coalesceKey else null
        lastMutationMillis = now
        syncUndoFlags()
    }

    @Synchronized
    fun beginEditGesture() {
        if (activeGestureDepth == 0) {
            gestureSnapshotRecorded = false
        }
        activeGestureDepth++
    }

    fun endEditGesture() {
        val syncWrite: Pair<DeckElementOverridesFile, Long>? = synchronized(this) {
            if (activeGestureDepth > 0) {
                activeGestureDepth--
                if (activeGestureDepth == 0) {
                    gestureSnapshotRecorded = false
                    lastCoalesceKey = null
                    if (pendingPersist) {
                        return@synchronized preparePersistLocked(debounce = false)
                    }
                }
            }
            null
        }
        if (syncWrite != null) {
            writeSnapshotToDisk(syncWrite.first, syncWrite.second)
        }
    }

    fun flushPendingPersistence() {
        val pending: Pair<DeckElementOverridesFile, Long>? = synchronized(this) {
            debouncePersistJob?.cancel()
            debouncePersistJob = null
            if (!pendingPersist) null else currentData to ++persistEpoch
        }
        if (pending != null) {
            writeSnapshotToDisk(pending.first, pending.second)
        }
    }

    override fun close() {
        flushPendingPersistence()
        if (ownsIoScope) {
            ioScope.cancel()
        }
    }

    @Synchronized
    fun data(): DeckElementOverridesFile = currentData

    @Synchronized
    fun patchFor(elementId: String): ElementPatch? = currentData.patchFor(elementId)

    private sealed interface PollOutcome {
        data object Unchanged : PollOutcome
        data object Deleted : PollOutcome
        data class Corrupt(
            val modifiedTime: FileTime?,
            val fileSize: Long,
        ) : PollOutcome
        data class Loaded(
            val parsed: DeckElementOverridesFile,
            val modifiedTime: FileTime?,
            val fileSize: Long,
        ) : PollOutcome
    }

    /**
     * Performs file stat/read/parse on [Dispatchers.IO] and applies state changes on the caller
     * (UI) thread so polling never touches disk on the EDT.
     */
    suspend fun pollExternalChangesFromDisk(notifyRevision: Boolean = true): Boolean {
        val (prevMtime, prevSize, skip) = synchronized(this) {
            Triple(
                lastObservedModifiedTime,
                lastObservedFileSize,
                activeGestureDepth > 0 || pendingPersist,
            )
        }
        if (skip) return false

        val outcome = withContext(Dispatchers.IO) {
            readDiskIfChanged(prevMtime, prevSize)
        }

        return synchronized(this) {
            if (activeGestureDepth > 0 || pendingPersist) return@synchronized false
            applyPollOutcomeLocked(outcome, notifyRevision)
        }
    }

    @Synchronized
    fun reloadFromDiskIfChanged(notifyRevision: Boolean = true): Boolean {
        if (activeGestureDepth > 0 || pendingPersist) return false
        val outcome = readDiskIfChanged(lastObservedModifiedTime, lastObservedFileSize)
        return applyPollOutcomeLocked(outcome, notifyRevision)
    }

    private fun readDiskIfChanged(
        prevMtime: FileTime?,
        prevSize: Long,
    ): PollOutcome {
        if (!overridesFile.exists()) {
            return if (prevMtime != null || prevSize != -1L) PollOutcome.Deleted else PollOutcome.Unchanged
        }
        val mtime = runCatching { Files.getLastModifiedTime(overridesFile) }.getOrNull()
        val size = runCatching { Files.size(overridesFile) }.getOrDefault(-1L)
        if (mtime != null && mtime == prevMtime && size == prevSize) {
            return PollOutcome.Unchanged
        }
        val text = runCatching { overridesFile.readText() }.getOrNull() ?: return PollOutcome.Unchanged
        if (text.isBlank()) {
            return PollOutcome.Loaded(DeckElementOverridesFile(), mtime, size)
        }
        val parsed = runCatching {
            json.decodeFromString<DeckElementOverridesFile>(text)
        }.getOrNull()
        if (parsed == null) {
            if (size > 0L) {
                runCatching {
                    val backup = overridesFile.resolveSibling("element-overrides.corrupt.json")
                    Files.copy(overridesFile, backup, StandardCopyOption.REPLACE_EXISTING)
                }
            }
            return PollOutcome.Corrupt(mtime, size)
        }
        return PollOutcome.Loaded(normalizeFile(parsed), mtime, size)
    }

    private fun applyPollOutcomeLocked(outcome: PollOutcome, notifyRevision: Boolean): Boolean = when (outcome) {
        PollOutcome.Unchanged -> false
        PollOutcome.Deleted -> {
            lastObservedModifiedTime = null
            lastObservedFileSize = -1L
            if (currentData.hasOverrides) {
                currentData = DeckElementOverridesFile()
                if (notifyRevision) revision++
                true
            } else {
                false
            }
        }
        is PollOutcome.Corrupt -> {
            lastObservedModifiedTime = outcome.modifiedTime
            lastObservedFileSize = outcome.fileSize
            false
        }
        is PollOutcome.Loaded -> {
            lastObservedModifiedTime = outcome.modifiedTime
            lastObservedFileSize = outcome.fileSize
            if (outcome.parsed != currentData) {
                currentData = outcome.parsed
                if (notifyRevision) revision++
                true
            } else {
                false
            }
        }
    }

    fun updateElement(
        elementId: String,
        coalesce: Boolean = false,
        coalesceKey: String = "element:$elementId",
        transform: (ElementPatch) -> ElementPatch,
    ) {
        val syncWrite: Pair<DeckElementOverridesFile, Long>? = synchronized(this) {
            val prev = currentData.elements[elementId] ?: ElementPatch(elementId = elementId)
            val rawUpdated = transform(prev).copy(elementId = elementId)
            val nextMap = if (rawUpdated.isEmpty) {
                currentData.elements - elementId
            } else {
                currentData.elements + (elementId to rawUpdated)
            }
            val nextData = DeckElementOverridesFile(elements = nextMap)
            if (nextData == currentData) return@synchronized null

            recordUndoSnapshotBeforeMutation(coalesceKey = coalesceKey, coalesce = coalesce)
            currentData = nextData
            val write = requestPersistLocked(coalesce = coalesce)
            revision++
            write
        }
        if (syncWrite != null) {
            writeSnapshotToDisk(syncWrite.first, syncWrite.second)
        }
    }

    fun resetElement(elementId: String) {
        val syncWrite: Pair<DeckElementOverridesFile, Long>? = synchronized(this) {
            if (elementId !in currentData.elements) return@synchronized null
            recordUndoSnapshotBeforeMutation(coalesceKey = null, coalesce = false)
            currentData = DeckElementOverridesFile(elements = currentData.elements - elementId)
            val write = requestPersistLocked(coalesce = false)
            revision++
            write
        }
        if (syncWrite != null) {
            writeSnapshotToDisk(syncWrite.first, syncWrite.second)
        }
    }

    fun resetAll() {
        val syncWrite: Pair<DeckElementOverridesFile, Long>? = synchronized(this) {
            if (!currentData.hasOverrides && currentData.elements.isEmpty()) return@synchronized null
            recordUndoSnapshotBeforeMutation(coalesceKey = null, coalesce = false)
            currentData = DeckElementOverridesFile()
            val write = requestPersistLocked(coalesce = false)
            revision++
            write
        }
        if (syncWrite != null) {
            writeSnapshotToDisk(syncWrite.first, syncWrite.second)
        }
    }

    fun undo(): Boolean {
        val syncWrite: Pair<Boolean, Pair<DeckElementOverridesFile, Long>?> = synchronized(this) {
            if (undoStack.isEmpty()) return@synchronized false to null
            activeGestureDepth = 0
            gestureSnapshotRecorded = false
            lastCoalesceKey = null
            if (redoStack.size >= MAX_UNDO_HISTORY) {
                redoStack.removeFirst()
            }
            redoStack.addLast(currentData)
            currentData = undoStack.removeLast()
            val write = requestPersistLocked(coalesce = false)
            syncUndoFlags()
            revision++
            true to write
        }
        syncWrite.second?.let { writeSnapshotToDisk(it.first, it.second) }
        return syncWrite.first
    }

    fun redo(): Boolean {
        val syncWrite: Pair<Boolean, Pair<DeckElementOverridesFile, Long>?> = synchronized(this) {
            if (redoStack.isEmpty()) return@synchronized false to null
            activeGestureDepth = 0
            gestureSnapshotRecorded = false
            lastCoalesceKey = null
            if (undoStack.size >= MAX_UNDO_HISTORY) {
                undoStack.removeFirst()
            }
            undoStack.addLast(currentData)
            currentData = redoStack.removeLast()
            val write = requestPersistLocked(coalesce = false)
            syncUndoFlags()
            revision++
            true to write
        }
        syncWrite.second?.let { writeSnapshotToDisk(it.first, it.second) }
        return syncWrite.first
    }

    /**
     * Generates clean Kotlin override code for [elementId] (or all overridden elements when `null`)
     * and optionally copies it to the system clipboard.
     */
    @Synchronized
    fun exportKotlinSnippet(
        elementId: String? = null,
        copyToClipboard: Boolean = true,
    ): String {
        val snippet = if (elementId != null) {
            val patch = currentData.patchFor(elementId)
            if (patch == null || patch.isEmpty) {
                "// No element overrides for \"$elementId\""
            } else {
                formatPatchKotlin(patch, indent = "")
            }
        } else {
            val entries = currentData.elements.values
                .filter { !it.isEmpty }
                .sortedBy { it.elementId }
            if (entries.isEmpty()) {
                "DeckElementOverridesFile()"
            } else {
                buildString {
                    appendLine("DeckElementOverridesFile(")
                    appendLine("    elements = mapOf(")
                    for (patch in entries) {
                        appendLine("        \"${escapeKotlinString(patch.elementId)}\" to ${formatPatchKotlin(patch, indent = "        ")},")
                    }
                    appendLine("    ),")
                    append(")")
                }
            }
        }

        if (copyToClipboard) {
            runCatching {
                val selection = StringSelection(snippet)
                Toolkit.getDefaultToolkit().systemClipboard.setContents(selection, selection)
            }
        }
        return snippet
    }

    private fun normalizeFile(file: DeckElementOverridesFile): DeckElementOverridesFile =
        DeckElementOverridesFile(elements = file.elements.filterValues { !it.isEmpty })

    private fun requestPersistLocked(coalesce: Boolean): Pair<DeckElementOverridesFile, Long>? {
        pendingPersist = true
        if (activeGestureDepth > 0) {
            debouncePersistJob?.cancel()
            debouncePersistJob = null
            return null
        }
        return preparePersistLocked(debounce = coalesce)
    }

    private fun preparePersistLocked(debounce: Boolean): Pair<DeckElementOverridesFile, Long>? {
        debouncePersistJob?.cancel()
        debouncePersistJob = null
        val targetEpoch = ++persistEpoch
        val snapshot = currentData
        return if (debounce) {
            debouncePersistJob = ioScope.launch {
                delay(DEBOUNCE_PERSIST_MS)
                val latest = synchronized(this@ElementOverridesStore) {
                    if (persistEpoch != targetEpoch || !pendingPersist || activeGestureDepth > 0) {
                        null
                    } else {
                        currentData
                    }
                } ?: return@launch
                writeSnapshotToDisk(latest, targetEpoch)
            }
            null
        } else if (EventQueue.isDispatchThread()) {
            debouncePersistJob = ioScope.launch {
                writeSnapshotToDisk(snapshot, targetEpoch)
            }
            null
        } else {
            snapshot to targetEpoch
        }
    }

    internal fun writeSnapshotToDisk(snapshot: DeckElementOverridesFile, targetEpoch: Long): Boolean {
        synchronized(writeLock) {
            if (targetEpoch <= lastWrittenEpoch) {
                return false
            }
            var tmp: Path? = null
            return try {
                Files.createDirectories(storyboardDir)
                tmp = Files.createTempFile(storyboardDir, "element-overrides-", ".tmp")
                Files.writeString(tmp, json.encodeToString(DeckElementOverridesFile.serializer(), snapshot))
                try {
                    Files.move(tmp, overridesFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(tmp, overridesFile, StandardCopyOption.REPLACE_EXISTING)
                }
                tmp = null
                val mtime = Files.getLastModifiedTime(overridesFile)
                val size = Files.size(overridesFile)
                lastWrittenEpoch = targetEpoch
                synchronized(this) {
                    if (persistEpoch == targetEpoch) {
                        pendingPersist = false
                    }
                    lastObservedModifiedTime = mtime
                    lastObservedFileSize = size
                }
                true
            } catch (_: Throwable) {
                tmp?.let { runCatching { Files.deleteIfExists(it) } }
                false
            }
        }
    }

    private fun formatPatchKotlin(patch: ElementPatch, indent: String): String {
        val args = buildList {
            add("elementId = \"${escapeKotlinString(patch.elementId)}\"")
            patch.text?.let { add("text = \"${escapeKotlinString(it)}\"") }
            patch.textColorArgb?.let { add("textColorArgb = ${formatArgbLiteral(it)}") }
            patch.fontSizeSp?.let { add("fontSizeSp = ${formatFloatLiteral(it)}") }
            patch.fontWeight?.let { add("fontWeight = $it") }
            patch.fillColorArgb?.let { add("fillColorArgb = ${formatArgbLiteral(it)}") }
            patch.borderColorArgb?.let { add("borderColorArgb = ${formatArgbLiteral(it)}") }
            patch.borderWidthDp?.let { add("borderWidthDp = ${formatFloatLiteral(it)}") }
            patch.cornerRadiusDp?.let { add("cornerRadiusDp = ${formatFloatLiteral(it)}") }
            patch.paddingDp?.let { add("paddingDp = ${formatFloatLiteral(it)}") }
            patch.offsetXDp?.let { add("offsetXDp = ${formatFloatLiteral(it)}") }
            patch.offsetYDp?.let { add("offsetYDp = ${formatFloatLiteral(it)}") }
        }
        return buildString {
            appendLine("ElementPatch(")
            for (arg in args) {
                appendLine("$indent    $arg,")
            }
            append("$indent)")
        }
    }

    private fun formatArgbLiteral(argb: Long): String =
        "0x" + (argb and 0xFFFFFFFFL).toString(16).uppercase().padStart(8, '0') + "L"

    private fun formatFloatLiteral(value: Float): String {
        val formatted = if (value % 1f == 0f) "${value.toInt()}.0" else "%.2f".format(value).trimEnd('0').trimEnd('.')
        return "${formatted}f"
    }

    private fun escapeKotlinString(value: String): String = buildString(value.length) {
        for (ch in value) {
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '$' -> append("\\$")
                else -> append(ch)
            }
        }
    }
}
