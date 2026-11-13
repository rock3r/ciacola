package dev.sebastiano.ciacola.inspector.design

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.sebastiano.ciacola.theme.CodePaletteOverrides
import dev.sebastiano.ciacola.theme.ColorTokenOverrides
import dev.sebastiano.ciacola.theme.DeckTheme
import dev.sebastiano.ciacola.theme.DeckThemePreset
import dev.sebastiano.ciacola.theme.DeckThemes
import dev.sebastiano.ciacola.theme.DesignSystemOverrides
import dev.sebastiano.ciacola.theme.MotionTokenOverrides
import dev.sebastiano.ciacola.theme.StyleTokenOverrides
import dev.sebastiano.ciacola.theme.TypographyTokenOverrides
import dev.sebastiano.ciacola.theme.withOverrides
import java.awt.EventQueue
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.FileTime
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Root `.storyboard/design-system.json` payload storing independent overrides for
 * [DeckThemePreset.StageDark] and [DeckThemePreset.KeynoteLight].
 */
@Serializable
data class DesignSystemOverridesFile(
    val schemaVersion: Int = 1,
    val stageDark: DesignSystemOverrides = DesignSystemOverrides(),
    val keynoteLight: DesignSystemOverrides = DesignSystemOverrides(),
) {
    fun forPreset(preset: DeckThemePreset): DesignSystemOverrides = when (preset) {
        DeckThemePreset.StageDark -> stageDark
        DeckThemePreset.KeynoteLight -> keynoteLight
    }

    fun withPreset(
        preset: DeckThemePreset,
        overrides: DesignSystemOverrides,
    ): DesignSystemOverridesFile = when (preset) {
        DeckThemePreset.StageDark -> copy(stageDark = overrides)
        DeckThemePreset.KeynoteLight -> copy(keynoteLight = overrides)
    }

    val hasOverrides: Boolean
        get() = stageDark.hasOverrides || keynoteLight.hasOverrides
}

/**
 * Manages live design system token overrides persisted in `.storyboard/design-system.json` with
 * independent per-preset storage, off-EDT atomic writes, corrupt-file backup, `Dispatchers.IO`
 * external mtime+size polling, gesture-coalesced 64-step Undo/Redo, granular resets, and Kotlin
 * snippet export.
 */
class DesignSystemStore(
    val storyboardDir: Path = Path.of(".storyboard"),
    private val clockMillis: () -> Long = System::currentTimeMillis,
) {
    companion object {
        const val MAX_UNDO_HISTORY = 64
        private const val COALESCE_WINDOW_MS = 250L
        private const val DEBOUNCE_PERSIST_MS = 200L
    }

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val overridesFile: Path = storyboardDir.resolve("design-system.json")
    val corruptBackupFile: Path = storyboardDir.resolve("design-system.corrupt.json")

    private var currentData: DesignSystemOverridesFile = DesignSystemOverridesFile()
    private var lastObservedModifiedTime: FileTime? = null
    private var lastObservedFileSize: Long = -1L

    private val undoStack = ArrayDeque<DesignSystemOverridesFile>()
    private val redoStack = ArrayDeque<DesignSystemOverridesFile>()
    private var activeGestureDepth = 0
    private var gestureSnapshotRecorded = false
    private var pendingPersist = false
    private var persistEpoch = 0L
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

    @Synchronized
    fun endEditGesture() {
        if (activeGestureDepth > 0) {
            activeGestureDepth--
            if (activeGestureDepth == 0) {
                gestureSnapshotRecorded = false
                lastCoalesceKey = null
                if (pendingPersist) {
                    scheduleOrFlushPersist(debounce = false)
                }
            }
        }
    }

    @Synchronized
    fun flushPendingPersistence() {
        debouncePersistJob?.cancel()
        debouncePersistJob = null
        if (!pendingPersist) return
        val snapshot = currentData
        val targetEpoch = ++persistEpoch
        writeSnapshotToDisk(snapshot, targetEpoch)
    }

    @Synchronized
    fun data(): DesignSystemOverridesFile {
        revision
        return currentData
    }

    @Synchronized
    fun overridesFor(preset: DeckThemePreset): DesignSystemOverrides {
        revision
        return currentData.forPreset(preset)
    }

    @Synchronized
    fun resolveTheme(
        preset: DeckThemePreset,
        baseTheme: DeckTheme = DeckThemes.forPreset(preset),
    ): DeckTheme {
        revision
        return baseTheme.withOverrides(currentData.forPreset(preset))
    }

    private sealed interface PollOutcome {
        data object Unchanged : PollOutcome
        data object Deleted : PollOutcome
        data class Loaded(
            val parsed: DesignSystemOverridesFile,
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
            return PollOutcome.Loaded(DesignSystemOverridesFile(), mtime, size)
        }
        val parsed = runCatching {
            json.decodeFromString<DesignSystemOverridesFile>(text)
        }.getOrNull()
        if (parsed == null) {
            if (text.isNotEmpty()) {
                runCatching {
                    Files.createDirectories(storyboardDir)
                    Files.copy(overridesFile, corruptBackupFile, StandardCopyOption.REPLACE_EXISTING)
                }
            }
            return PollOutcome.Loaded(currentData, mtime, size)
        }
        return PollOutcome.Loaded(parsed, mtime, size)
    }

    private fun applyPollOutcomeLocked(outcome: PollOutcome, notifyRevision: Boolean): Boolean = when (outcome) {
        PollOutcome.Unchanged -> false
        PollOutcome.Deleted -> {
            lastObservedModifiedTime = null
            lastObservedFileSize = -1L
            if (currentData != DesignSystemOverridesFile()) {
                currentData = DesignSystemOverridesFile()
                if (notifyRevision) revision++
                true
            } else {
                false
            }
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

    @Synchronized
    fun updatePreset(
        preset: DeckThemePreset,
        coalesceKey: String? = null,
        coalesce: Boolean = false,
        transform: (DesignSystemOverrides) -> DesignSystemOverrides,
    ) {
        val prev = currentData.forPreset(preset)
        val updated = transform(prev)
        if (updated == prev) return
        recordUndoSnapshotBeforeMutation(coalesceKey = coalesceKey, coalesce = coalesce)
        currentData = currentData.withPreset(preset, updated)
        requestPersistLocked(coalesce = coalesce)
        revision++
    }

    fun updateColors(
        preset: DeckThemePreset,
        coalesceKey: String? = null,
        coalesce: Boolean = false,
        transform: (ColorTokenOverrides) -> ColorTokenOverrides,
    ) {
        updatePreset(preset, coalesceKey = coalesceKey, coalesce = coalesce) { o ->
            o.copy(colors = transform(o.colors))
        }
    }

    fun updateCodePalette(
        preset: DeckThemePreset,
        coalesceKey: String? = null,
        coalesce: Boolean = false,
        transform: (CodePaletteOverrides) -> CodePaletteOverrides,
    ) {
        updateColors(preset, coalesceKey = coalesceKey, coalesce = coalesce) { c ->
            val updatedCode = transform(c.code ?: CodePaletteOverrides())
            c.copy(code = updatedCode.takeIf { it != CodePaletteOverrides() })
        }
    }

    fun updateTypography(
        preset: DeckThemePreset,
        coalesceKey: String? = null,
        coalesce: Boolean = false,
        transform: (TypographyTokenOverrides) -> TypographyTokenOverrides,
    ) {
        updatePreset(preset, coalesceKey = coalesceKey, coalesce = coalesce) { o ->
            o.copy(typography = transform(o.typography))
        }
    }

    fun updateStyles(
        preset: DeckThemePreset,
        coalesceKey: String? = null,
        coalesce: Boolean = false,
        transform: (StyleTokenOverrides) -> StyleTokenOverrides,
    ) {
        updatePreset(preset, coalesceKey = coalesceKey, coalesce = coalesce) { o ->
            o.copy(styles = transform(o.styles))
        }
    }

    fun updateMotion(
        preset: DeckThemePreset,
        coalesceKey: String? = null,
        coalesce: Boolean = false,
        transform: (MotionTokenOverrides) -> MotionTokenOverrides,
    ) {
        updatePreset(preset, coalesceKey = coalesceKey, coalesce = coalesce) { o ->
            o.copy(motion = transform(o.motion))
        }
    }

    fun resetColors(preset: DeckThemePreset) {
        updatePreset(preset) { it.copy(colors = ColorTokenOverrides()) }
    }

    fun resetTypography(preset: DeckThemePreset) {
        updatePreset(preset) { it.copy(typography = TypographyTokenOverrides()) }
    }

    fun resetStyles(preset: DeckThemePreset) {
        updatePreset(preset) { it.copy(styles = StyleTokenOverrides()) }
    }

    fun resetMotion(preset: DeckThemePreset) {
        updatePreset(preset) { it.copy(motion = MotionTokenOverrides()) }
    }

    fun resetAllForPreset(preset: DeckThemePreset) {
        updatePreset(preset) { DesignSystemOverrides() }
    }

    @Synchronized
    fun resetAll() {
        if (!currentData.hasOverrides) return
        recordUndoSnapshotBeforeMutation(coalesceKey = null, coalesce = false)
        currentData = DesignSystemOverridesFile()
        requestPersistLocked(coalesce = false)
        revision++
    }

    @Synchronized
    fun undo(): Boolean {
        if (undoStack.isEmpty()) return false
        activeGestureDepth = 0
        gestureSnapshotRecorded = false
        lastCoalesceKey = null
        if (redoStack.size >= MAX_UNDO_HISTORY) {
            redoStack.removeFirst()
        }
        redoStack.addLast(currentData)
        currentData = undoStack.removeLast()
        requestPersistLocked(coalesce = false)
        syncUndoFlags()
        revision++
        return true
    }

    @Synchronized
    fun redo(): Boolean {
        if (redoStack.isEmpty()) return false
        activeGestureDepth = 0
        gestureSnapshotRecorded = false
        lastCoalesceKey = null
        if (undoStack.size >= MAX_UNDO_HISTORY) {
            undoStack.removeFirst()
        }
        undoStack.addLast(currentData)
        currentData = redoStack.removeLast()
        requestPersistLocked(coalesce = false)
        syncUndoFlags()
        revision++
        return true
    }

    @Synchronized
    fun exportKotlinSnippet(
        preset: DeckThemePreset,
        baseTheme: DeckTheme = DeckThemes.forPreset(preset),
    ): String = exportDesignSystemKotlinSnippet(
        preset = preset,
        overrides = currentData.forPreset(preset),
        baseTheme = baseTheme,
    )

    private fun requestPersistLocked(coalesce: Boolean) {
        pendingPersist = true
        if (activeGestureDepth > 0) {
            debouncePersistJob?.cancel()
            debouncePersistJob = null
            return
        }
        scheduleOrFlushPersist(debounce = coalesce)
    }

    private fun scheduleOrFlushPersist(debounce: Boolean) {
        debouncePersistJob?.cancel()
        debouncePersistJob = null
        val targetEpoch = ++persistEpoch
        val snapshot = currentData
        if (debounce) {
            debouncePersistJob = ioScope.launch {
                delay(DEBOUNCE_PERSIST_MS)
                val latest = synchronized(this@DesignSystemStore) {
                    if (persistEpoch != targetEpoch || !pendingPersist || activeGestureDepth > 0) {
                        null
                    } else {
                        currentData
                    }
                } ?: return@launch
                writeSnapshotToDisk(latest, targetEpoch)
            }
        } else if (EventQueue.isDispatchThread()) {
            debouncePersistJob = ioScope.launch {
                writeSnapshotToDisk(snapshot, targetEpoch)
            }
        } else {
            writeSnapshotToDisk(snapshot, targetEpoch)
        }
    }

    private fun writeSnapshotToDisk(snapshot: DesignSystemOverridesFile, targetEpoch: Long) {
        runCatching {
            Files.createDirectories(storyboardDir)
            val encoded = json.encodeToString(snapshot) + "\n"
            val tmp = overridesFile.resolveSibling("${overridesFile.fileName}.tmp")
            tmp.writeText(encoded)
            try {
                Files.move(
                    tmp,
                    overridesFile,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tmp, overridesFile, StandardCopyOption.REPLACE_EXISTING)
            }
            val mtime = Files.getLastModifiedTime(overridesFile)
            val size = Files.size(overridesFile)
            synchronized(this) {
                if (persistEpoch == targetEpoch) {
                    pendingPersist = false
                }
                lastObservedModifiedTime = mtime
                lastObservedFileSize = size
            }
        }
    }
}
