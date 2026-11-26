package dev.sebastiano.ciacola.studio

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import dev.sebastiano.ciacola.DeckCatalog
import dev.sebastiano.ciacola.DeckEntry
import dev.sebastiano.ciacola.decks.DeckSource
import dev.sebastiano.ciacola.document.CloseFlushResult
import dev.sebastiano.ciacola.document.DeckDocument
import dev.sebastiano.ciacola.document.DeckDocumentStore
import dev.sebastiano.ciacola.document.SyncState
import dev.sebastiano.ciacola.document.render.buildDocumentSpeakerNotes
import dev.sebastiano.ciacola.forExternalFile
import dev.sebastiano.ciacola.inspector.StageInspectionRegistry
import dev.sebastiano.ciacola.inspector.element.DocumentElementInspectorController
import dev.sebastiano.ciacola.inspector.sync.DocumentSyncController
import dev.sebastiano.ciacola.presenter.DeckSpeakerNotes
import dev.sebastiano.ciacola.presenter.PresenterController
import dev.sebastiano.ciacola.review.ReviewStageCoordinator
import dev.sebastiano.ciacola.review.SlideReviewWindow
import java.awt.FileDialog
import java.awt.Frame
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay

/** System property listing deck ids to cycle through after launch while logging switch timings. */
const val DECK_SWITCH_PROBE_PROPERTY = "ciacola.deckSwitchProbe"

/** Optional file the deck switch probe appends its timing lines to. */
const val DECK_SWITCH_PROBE_OUT_PROPERTY = "ciacola.deckSwitchProbeOut"

/** Logs every interactive deck switch timing to stderr when `true`. */
const val DECK_SWITCH_LOG_PROPERTY = "ciacola.logDeckSwitch"

private const val DECK_FILE_SUFFIX = ".deck.json"

/** Wall-clock phases of one deck switch, in milliseconds. */
data class DeckSwitchTiming(
    val fromDeckId: String,
    val toDeckId: String,
    /** Decode + validate + file lock off the UI thread ([preOpenDeckSession]). */
    val preOpenMs: Long,
    /** Request → first fully composed, laid out, and drawn frame of the new deck. */
    val totalMs: Long,
) {
    fun formatLine(): String =
        "deck-switch $fromDeckId -> $toDeckId: preOpen=${preOpenMs}ms firstFrame=${totalMs}ms"
}

/** Measures interactive deck switches from request to the first drawn frame of the new deck. */
@Stable
class DeckSwitchTimer(private val nanoClock: () -> Long = System::nanoTime) {
    private var startedAt: Long? = null
    private var preOpenDoneAt: Long? = null
    private var fromId: String = ""
    private var toId: String = ""

    var lastTiming: DeckSwitchTiming? by mutableStateOf(null)
        private set

    val history: MutableList<DeckSwitchTiming> = mutableListOf()

    fun markRequested(fromDeckId: String, toDeckId: String) {
        fromId = fromDeckId
        toId = toDeckId
        startedAt = nanoClock()
        preOpenDoneAt = null
    }

    fun markPreOpened() {
        preOpenDoneAt = nanoClock()
    }

    /** Call after the frame that drew the new deck completed. */
    fun markFirstFrameDrawn(): DeckSwitchTiming? {
        val started = startedAt ?: return null
        val now = nanoClock()
        val timing = DeckSwitchTiming(
            fromDeckId = fromId,
            toDeckId = toId,
            preOpenMs = ((preOpenDoneAt ?: now) - started) / 1_000_000L,
            totalMs = (now - started) / 1_000_000L,
        )
        startedAt = null
        lastTiming = timing
        history += timing
        if (System.getProperty(DECK_SWITCH_LOG_PROPERTY).toBoolean() || System.getProperty(DECK_SWITCH_PROBE_PROPERTY) != null) {
            System.err.println(timing.formatLine())
            System.getProperty(DECK_SWITCH_PROBE_OUT_PROPERTY)?.let { out ->
                runCatching {
                    Files.writeString(
                        Path.of(out),
                        timing.formatLine() + "\n",
                        java.nio.file.StandardOpenOption.CREATE,
                        java.nio.file.StandardOpenOption.APPEND,
                    )
                }
            }
        }
        return timing
    }
}

/** Suspends until the frame that composes the current snapshot has been fully drawn. */
suspend fun awaitNextDrawnFrame() {
    withFrameNanos { }
    withFrameNanos { }
}

/**
 * When `-Dciacola.deckSwitchProbe=<id>,<id>,…` is set, cycles through those decks after launch so
 * real-app switch latency can be measured end to end (timings are printed by [DeckSwitchTimer]).
 */
@Composable
fun DeckSwitchProbeEffect(
    timer: DeckSwitchTimer,
    onRequestSwitch: (DeckEntry) -> Unit,
    entriesProvider: () -> List<DeckEntry> = { DeckCatalog.discoverEntries() },
) {
    val probe = System.getProperty(DECK_SWITCH_PROBE_PROPERTY)?.trim().orEmpty()
    if (probe.isEmpty()) return
    val latestSwitch by rememberUpdatedState(onRequestSwitch)
    val latestEntriesProvider by rememberUpdatedState(entriesProvider)
    LaunchedEffect(probe) {
        val entries = latestEntriesProvider()
        val ids = probe.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        delay(PROBE_SETTLE_MS)
        for (id in ids) {
            val entry = entries.firstOrNull { it.id == id } ?: continue
            val before = timer.history.size
            latestSwitch(entry)
            var waited = 0L
            while (timer.history.size == before && waited < PROBE_TIMEOUT_MS) {
                delay(PROBE_POLL_MS)
                waited += PROBE_POLL_MS
            }
            delay(PROBE_SETTLE_MS)
        }
    }
}

private const val PROBE_SETTLE_MS = 2_500L
private const val PROBE_POLL_MS = 50L
private const val PROBE_TIMEOUT_MS = 120_000L

/** Shows the native file picker filtered to `*.deck.json` files. */
fun pickDeckJsonFile(title: String = "Open .deck.json"): Path? {
    val dialog = FileDialog(null as Frame?, title, FileDialog.LOAD)
    dialog.setFilenameFilter { _, name -> name.endsWith(DECK_FILE_SUFFIX) }
    dialog.isVisible = true
    val dir = dialog.directory ?: return null
    val file = dialog.file ?: return null
    return Path.of(dir, file)
}

/**
 * Builds a [DeckEntry] for an external `<id>.deck.json` file via [DeckEntry.Companion.forExternalFile],
 * preserving curated catalog titles when the stem matches a built-in deck while binding
 * [DeckEntry.externalFilePath] to [file] so `resolveDeckSource` opens the chosen file directly.
 */
fun externalDeckEntryFor(file: Path, catalog: List<DeckEntry> = DeckCatalog.entries): DeckEntry? {
    val name = file.fileName?.toString() ?: return null
    if (!name.endsWith(DECK_FILE_SUFFIX)) return null
    if (name.removeSuffix(DECK_FILE_SUFFIX).isBlank()) return null
    val external = DeckEntry.forExternalFile(file)
    val catalogMatch = catalog.firstOrNull { it.id == external.id }
    return if (catalogMatch != null && Files.isRegularFile(file)) {
        external.copy(title = catalogMatch.title, specimens = catalogMatch.specimens)
    } else {
        external
    }
}

/** Directory to treat as the decks dir when opening [file] (so `resolveDeckSource` picks the file). */
fun decksDirFor(file: Path): String? = file.toAbsolutePath().normalize().parent?.toString()

/**
 * Snapshot-observable speaker notes for the Presenter View, rebuilt only when the document instance
 * changes (inline edits through [saveSpeakerNote] update it immediately).
 */
class DocumentSpeakerNotesMemo {
    private var lastDoc: DeckDocument? = null
    private var lastNotes: DeckSpeakerNotes = DeckSpeakerNotes.Empty

    fun notesFor(doc: DeckDocument): DeckSpeakerNotes {
        if (doc !== lastDoc) {
            lastNotes = buildDocumentSpeakerNotes(doc)
            lastDoc = doc
        }
        return lastNotes
    }
}

/**
 * Persists [note] into `beats[beatIndex].notes` of slide [slideId] as one undoable edit (blank
 * notes clear the field). Returns `false` when read-only or the slide/beat does not exist.
 */
fun saveSpeakerNote(store: DeckDocumentStore, slideId: String, beatIndex: Int, note: String?): Boolean {
    val doc = store.document
    val slide = doc.slides.firstOrNull { it.id == slideId } ?: return false
    if (beatIndex !in slide.beats.indices) return false
    val normalized = note?.trimEnd()?.takeIf { it.isNotBlank() }
    if (slide.beats[beatIndex].notes == normalized) return true
    return store.edit(label = "Edit speaker notes") { current ->
        current.copy(
            slides = current.slides.map { s ->
                if (s.id != slideId) {
                    s
                } else {
                    s.copy(
                        beats = s.beats.mapIndexed { i, beat ->
                            if (i == beatIndex) beat.copy(notes = normalized) else beat
                        },
                    )
                }
            },
        )
    }
}

/** Returns the directory containing [source] for resolving relative deck fonts, or `null` for bundled resources. */
internal fun resolveDeckFontsDir(source: DeckSource): String? =
    (source as? DeckSource.File)?.file?.toAbsolutePath()?.normalize()?.parent?.toString()

/**
 * Returns the asset root directory for [source]: for file-backed decks, uses the sibling
 * `<stem>.assets` directory when present and falls back to the deck file's parent directory so
 * images stored beside an external `.deck.json` render identically to the headless CLI.
 */
internal fun resolveDeckAssetsRootDir(source: DeckSource, fallbackResourceDir: String?): String? =
    when (source) {
        is DeckSource.File -> (source.assetsDir ?: source.file.toAbsolutePath().normalize().parent)?.toString()
        is DeckSource.Resource -> fallbackResourceDir
    }

internal fun pickDeckFileForCopyThemes(): Path? {
    val dialog = FileDialog(null as Frame?, "Copy themes from .deck.json", FileDialog.LOAD)
    dialog.isVisible = true
    val dir = dialog.directory
    val file = dialog.file
    return if (dir != null && file != null) Path.of(dir, file) else null
}

internal fun pickCodeSchemeFile(): Path? {
    val dialog = FileDialog(null as Frame?, "Import IntelliJ or VS Code color scheme", FileDialog.LOAD)
    dialog.setFilenameFilter { _, name ->
        val lower = name.lowercase()
        lower.endsWith(".icls") || lower.endsWith(".xml") || lower.endsWith(".json") || lower.endsWith(".jsonc")
    }
    dialog.isVisible = true
    val dir = dialog.directory
    val file = dialog.file
    return if (dir != null && file != null) Path.of(dir, file) else null
}

/**
 * Runs before the outgoing deck is flushed for a deck switch or quit: cancels uncommitted drag and
 * inline-text transactions, then commits an open speaker-notes draft into the outgoing store so the
 * flush that follows persists it (and nothing can land in the incoming deck).
 */
internal fun prepareToLeaveDeck(
    workspace: StudioWorkspaceController,
    elementController: DocumentElementInspectorController,
    presenterController: PresenterController?,
) {
    workspace.cancelAnyActiveGesture()
    elementController.cancelInlineTextEdit()
    presenterController?.commitEditingNotesIfDirty()
}

/**
 * Repeats the guarded close flush right before swapping sessions if the outgoing deck received new
 * edits while the incoming deck was pre-opening asynchronously. Returns `true` when it is safe to
 * dispose the outgoing session and swap, or `false` when the sync close guard is holding the swap.
 */
internal suspend fun flushOutgoingBeforeSwap(
    syncController: DocumentSyncController?,
    revisionAtSwitchStart: Long,
    dirtyAtSwitchStart: Boolean,
): Boolean {
    val sync = syncController ?: return true
    var baselineRevision = revisionAtSwitchStart
    var baselineDirty = dirtyAtSwitchStart
    repeat(3) {
        val store = sync.store
        if (store.sync is SyncState.ReadOnly && !store.isDirty) return true
        val needsFlush = store.revision != baselineRevision || (!baselineDirty && store.isDirty)
        if (!needsFlush) return true
        val revBeforeFlush = store.revision
        val result = sync.requestClose(onAllowClose = {})
        if (result !is CloseFlushResult.Clean) return false
        baselineRevision = revBeforeFlush
        baselineDirty = false
    }
    return !sync.store.isDirty
}

/** Wires stage hit-testing fallbacks and keyboard comment pinning between Review and Studio. */
internal fun wireReviewAndWorkspaceCallbacks(
    reviewStageCoordinator: ReviewStageCoordinator,
    stageInspectionRegistry: StageInspectionRegistry,
    studioWorkspace: StudioWorkspaceController,
    reviewWindowHolderProvider: () -> SlideReviewWindow?,
    appScope: CoroutineScope,
    isAnyTextInputActive: () -> Boolean,
    isAudiencePresentingProvider: () -> Boolean = { false },
    closeAuthoringChrome: () -> Unit,
    restoreStageFocus: () -> Unit,
) {
    reviewStageCoordinator.isExternalTextInputActiveProvider = isAnyTextInputActive
    reviewStageCoordinator.isAudiencePresentingProvider = isAudiencePresentingProvider
    reviewStageCoordinator.fallbackNodeIdResolver = { stagePoint, dragRect ->
        stageInspectionRegistry.nodes.values
            .filter { snap ->
                snap.effectiveAlpha > 0.01f &&
                    snap.stageBounds.width > 0f &&
                    snap.stageBounds.height > 0f &&
                    if (dragRect != null) snap.stageBounds.overlaps(dragRect) else snap.stageBounds.contains(stagePoint)
            }
            .minByOrNull { it.stageBounds.width * it.stageBounds.height }
            ?.elementId
    }
    reviewStageCoordinator.fallbackNodeBoundsResolver = { nodeId ->
        stageInspectionRegistry.nodes[nodeId]?.takeIf {
            it.effectiveAlpha > 0.01f && it.stageBounds.width > 0f && it.stageBounds.height > 0f
        }?.stageBounds
    }
    studioWorkspace.onCaptureReviewTargetNodeId = { nodeId -> reviewStageCoordinator.preferredTargetNodeId = nodeId }
    studioWorkspace.onActiveSlideChanged = { reviewStageCoordinator.preferredTargetNodeId = null }
    studioWorkspace.onPinReviewComment = { reviewStageCoordinator.pinCommentAtCurrentTarget(appScope) }
    studioWorkspace.setReviewInspectMode = { enabled ->
        reviewStageCoordinator.inspectModeEnabled = enabled
        if (enabled) reviewStageCoordinator.showStageBadges = true else reviewStageCoordinator.dismissActivePopups()
    }
    studioWorkspace.onToggleReviewInspect = {
        val next = !reviewStageCoordinator.inspectModeEnabled
        reviewStageCoordinator.inspectModeEnabled = next
        if (next) reviewStageCoordinator.showStageBadges = true
    }
    studioWorkspace.onOpenReviewPanel = { reviewWindowHolderProvider()?.visible = true }
    studioWorkspace.onEnterCleanShowMode = closeAuthoringChrome
    studioWorkspace.onRestoreStageFocus = restoreStageFocus
}
