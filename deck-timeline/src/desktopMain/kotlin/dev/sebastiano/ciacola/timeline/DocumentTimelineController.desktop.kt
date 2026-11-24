package dev.sebastiano.ciacola.timeline

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import dev.sebastiano.ciacola.document.BuildEffectRegistry
import dev.sebastiano.ciacola.document.Curve
import dev.sebastiano.ciacola.document.DeckDocument
import dev.sebastiano.ciacola.document.DeckDocumentStore
import dev.sebastiano.ciacola.document.DeckSpecimenRegistry
import dev.sebastiano.ciacola.document.DeckStructureEdits
import dev.sebastiano.ciacola.document.EditTransaction
import dev.sebastiano.ciacola.document.Slot
import dev.sebastiano.ciacola.document.SlotPhase
import dev.sebastiano.ciacola.document.Stagger
import dev.sebastiano.ciacola.document.StaggerOrder
import dev.sebastiano.ciacola.document.TextMorphSpec
import dev.sebastiano.ciacola.document.TransitionSpec
import dev.sebastiano.ciacola.document.TransitionType
import dev.sebastiano.ciacola.timeline.DocumentTimelineMutationSupport.pruneIfDefault
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

/**
 * Document-backed controller for the analytical Timeline Studio and motion stagger editor.
 *
 * Binds directly to [DeckDocumentStore] + [DocumentTimelinePlannerCache] + [DeckSpecimenRegistry]
 * with zero offscreen `ImageComposeScene` stepping. Every interactive drag, slider scrub, bar move,
 * edge resize, curve drag, phase-bar drag, fragment-tick drag, and loop period edit uses explicit
 * [EditTransaction] (`store.begin` -> `update` -> `commit` / `cancel`).
 */
@Stable
class DocumentTimelineController(
    val store: DeckDocumentStore,
    val specimens: DeckSpecimenRegistry = DeckSpecimenRegistry.Empty,
    val effects: BuildEffectRegistry = BuildEffectRegistry.Standard,
    val viewport: TimelineViewportState = TimelineViewportState(),
    private val themeNameProvider: () -> String? = { store.document.defaultTheme },
    private val reducedMotionProvider: () -> Boolean = { false },
    var onPlayheadChanged: ((Int) -> Unit)? = null,
    var onStepBeat: ((Int) -> Unit)? = null,
    var onTogglePlayback: (() -> Unit)? = null,
    var onUndoRequest: (() -> Unit)? = null,
    var onRedoRequest: (() -> Unit)? = null,
    var onBeforeBeginGesture: (() -> Unit)? = null,
    var clipboardWriter: (String) -> Unit = { text ->
        runCatching {
            Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
        }
    },
) {
    val plannerCache: DocumentTimelinePlannerCache = DocumentTimelinePlannerCache()

    var snapshot: DocumentTimelineSnapshot by mutableStateOf(DocumentTimelineSnapshot.Empty, referentialEqualityPolicy())
        private set

    var selection: DocumentTimelineSelection by mutableStateOf(DocumentTimelineSelection.None)
        private set

    var expandedTextTrackId: String? by mutableStateOf(null)
        private set

    var selectedFragmentAddress: String? by mutableStateOf(null)

    var playheadMs: Int by mutableIntStateOf(0)
        private set

    var isPlaying: Boolean by mutableStateOf(false)

    var trackSearchQuery: String by mutableStateOf("")

    var trackFilterMode: TimelineTrackFilterMode by mutableStateOf(TimelineTrackFilterMode.AllSlides)
        private set

    private var userOverrodeTrackFilter: Boolean = false

    /** Active slide & beat synced from the stage's Animatic (so `- Beat` targets the active slide). */
    var currentPresentedSlideId: String? by mutableStateOf(null)
        private set

    var currentPresentedSceneIndex: Int by mutableIntStateOf(0)
        private set

    var currentPresentedBeatIndex: Int by mutableIntStateOf(0)
        private set

    /** Active loop sub-composition id and scrub time (ms) fed into live `RenderSession`. */
    var activeSubCompLoopId: String? by mutableStateOf(null)
        private set

    var subCompositionScrubMillis: Long? by mutableStateOf(null)
        private set

    var lastRefreshDurationNanos: Long by mutableLongStateOf(0L)
        private set

    private var activeGestureTx: EditTransaction? = null
    private var lastSyncedDocument: DeckDocument? = null
    private var lastSyncedThemeName: String? = null
    private var lastSyncedReducedMotion: Boolean = false

    val hasActiveGesture: Boolean
        get() = activeGestureTx?.isActive == true

    internal val activeThemeName: String? get() = themeNameProvider()

    init {
        refreshSnapshot(force = true)
    }

    /**
     * Synchronizes [snapshot] with [store.document] using incremental per-slide/per-transition
     * caching (re-planning ONLY changed slide `N` and transitions `N-1 -> N`, `N -> N+1`).
     */
    fun refreshSnapshot(force: Boolean = false): DocumentTimelineSnapshot {
        val doc = store.document
        val themeName = themeNameProvider()
        val reducedMotion = reducedMotionProvider()
        if (!force &&
            lastSyncedDocument === doc &&
            lastSyncedThemeName == themeName &&
            lastSyncedReducedMotion == reducedMotion
        ) {
            return snapshot
        }
        val t0 = System.nanoTime()
        val built = plannerCache.buildSnapshot(
            document = doc,
            themeName = themeName,
            reducedMotion = reducedMotion,
            specimens = specimens,
            effects = effects,
        )
        snapshot = built
        lastSyncedDocument = doc
        lastSyncedThemeName = themeName
        lastSyncedReducedMotion = reducedMotion
        lastRefreshDurationNanos = System.nanoTime() - t0
        viewport.contentDurationMs = built.snapshot.totalDurationMs.coerceAtLeast(1)
        playheadMs = playheadMs.coerceIn(0, built.snapshot.totalDurationMs.coerceAtLeast(0))

        // Auto-default large decks (> 80 tracks) to CurrentSlide unless the user chose explicitly.
        if (!userOverrodeTrackFilter) {
            trackFilterMode = if (built.snapshot.tracks.size > AUTO_CURRENT_SLIDE_TRACK_THRESHOLD) {
                TimelineTrackFilterMode.CurrentSlide
            } else {
                TimelineTrackFilterMode.AllSlides
            }
        }

        // Keep presented location valid when slides change or on initial load.
        val activeSlides = doc.slides.filter { !it.skipped }
        if (activeSlides.isNotEmpty()) {
            val existingIdx = currentPresentedSlideId?.let { id -> activeSlides.indexOfFirst { it.id == id } } ?: -1
            if (existingIdx >= 0) {
                currentPresentedSceneIndex = existingIdx
                currentPresentedBeatIndex = currentPresentedBeatIndex.coerceIn(0, activeSlides[existingIdx].beats.lastIndex.coerceAtLeast(0))
            } else {
                val clampedScene = currentPresentedSceneIndex.coerceIn(0, activeSlides.lastIndex)
                currentPresentedSceneIndex = clampedScene
                currentPresentedSlideId = activeSlides[clampedScene].id
                currentPresentedBeatIndex = currentPresentedBeatIndex.coerceIn(0, activeSlides[clampedScene].beats.lastIndex.coerceAtLeast(0))
            }
        } else {
            currentPresentedSlideId = null
            currentPresentedSceneIndex = 0
            currentPresentedBeatIndex = 0
        }

        // Exit sub-composition if its loop disappeared from the deck.
        val openLoop = activeSubCompLoopId
        if (openLoop != null && "doc:loop:$openLoop" !in built.trackRefsByTrackId) {
            exitLoopSubComp()
        }

        return built
    }

    fun setTrackFilter(mode: TimelineTrackFilterMode) {
        userOverrodeTrackFilter = true
        if (mode == trackFilterMode) return
        // Per-scope zoom memory: toggling Slide -> All -> Slide restores each scope's zoom.
        scopeZoomMemory[trackFilterMode] = currentPresentedSceneIndex to viewport.captureZoom()
        trackFilterMode = mode
        val saved = scopeZoomMemory[mode]
        val restorable = saved != null &&
            (mode == TimelineTrackFilterMode.AllSlides || saved.first == currentPresentedSceneIndex)
        scopeZoomRestoredPending = restorable
        if (restorable) viewport.restoreZoom(saved!!.second)
    }

    /** Zoom levels remembered per scope (with the scene they were captured on). */
    private val scopeZoomMemory = HashMap<TimelineTrackFilterMode, Pair<Int, TimelineZoomMemento>>()
    private var scopeZoomRestoredPending: Boolean = false

    /** Returns (and clears) whether the last scope toggle restored a remembered zoom. */
    internal fun consumeScopeZoomRestored(): Boolean = scopeZoomRestoredPending.also { scopeZoomRestoredPending = false }

    /** Fired with the node id when the user selects a node's track or loop in the timeline. */
    var onSelectedNodeChanged: ((String?) -> Unit)? = null

    /** Node whose tracks are highlighted (from stage/Design selection or a track click). */
    var highlightedNodeId: String? by mutableStateOf(null)
        private set

    /** Bumped whenever the current selection should be scrolled into view (rows + time bar). */
    var revealRequestNonce: Int by mutableIntStateOf(0)
        private set

    /**
     * Track row currently under the pointer in the timeline (null at rest). The stage overlay only
     * shows its floating track label pill while this is set, never for a plain stage selection.
     */
    var hoveredTrackId: String? by mutableStateOf(null)

    /**
     * Stage/Design -> Timeline selection sync: highlights every track of [nodeId] on [slideId],
     * selects its primary track (the one on the presented beat, else the earliest) and scrolls it
     * into view. Does NOT fire [onSelectedNodeChanged], so the two selections never ping-pong.
     */
    fun selectTracksForNode(slideId: String, nodeId: String?) {
        if (nodeId == null) {
            highlightedNodeId = null
            if (selection is DocumentTimelineSelection.Track || selection is DocumentTimelineSelection.Loop) {
                selection = DocumentTimelineSelection.None
                expandedTextTrackId = null
                selectedFragmentAddress = null
            }
            return
        }
        highlightedNodeId = nodeId
        val beat = if (slideId == currentPresentedSlideId) currentPresentedBeatIndex else 0
        val primary = DocumentTimelineSelectionSupport.trackIdsForNode(snapshot, slideId, nodeId, beat).firstOrNull()
        if (primary == null) {
            if (selection is DocumentTimelineSelection.Track) selection = DocumentTimelineSelection.None
            return
        }
        val ref = snapshot.trackRefsByTrackId[primary]
        selection = if (ref is DocumentTimelineTrackRef.LoopTrackBar) {
            DocumentTimelineSelection.Loop(ref.loopId)
        } else {
            DocumentTimelineSelection.Track(primary, snapshot.snapshot.tracks.firstOrNull { it.trackId == primary }?.blocks?.firstOrNull()?.id)
        }
        expandedTextTrackId = if (isExpandableTextTrack(ref)) primary else null
        selectedFragmentAddress = null
        revealRequestNonce++
    }

    private fun notifyNodeSelected(ref: DocumentTimelineTrackRef?) {
        val nodeId = ref?.referencedNodeId()
        highlightedNodeId = nodeId
        revealRequestNonce++
        onSelectedNodeChanged?.invoke(nodeId)
    }

    fun updatePresentedLocation(slideId: String?, sceneIndex: Int, beatIndex: Int) {
        val activeSlides = store.document.slides.filter { !it.skipped }
        val resolvedIdx = when {
            slideId != null -> activeSlides.indexOfFirst { it.id == slideId }.takeIf { it >= 0 }
                ?: sceneIndex.coerceIn(0, activeSlides.lastIndex.coerceAtLeast(0))
            else -> sceneIndex.coerceIn(0, activeSlides.lastIndex.coerceAtLeast(0))
        }
        val slide = activeSlides.getOrNull(resolvedIdx)
        val nextSlideId = slide?.id ?: slideId
        val nextBeat = beatIndex.coerceIn(0, (slide?.beats?.lastIndex ?: 0).coerceAtLeast(0))
        currentPresentedSceneIndex = resolvedIdx
        currentPresentedSlideId = nextSlideId
        currentPresentedBeatIndex = nextBeat
        syncSelectionIfStaleForSlide(nextSlideId, nextBeat)
    }

    /** Returns the time span `[startMs, endMs]` covering the active slide and its outgoing transition. */
    fun activeSlideTimeRange(): Pair<Int, Int>? {
        val slideBar = snapshot.snapshot.slides.firstOrNull { it.sceneIndex == currentPresentedSceneIndex }
            ?: return null
        val transEnd = snapshot.transitionHeaders
            .firstOrNull { it.fromSceneIndex == currentPresentedSceneIndex }
            ?.let { it.startMs + it.segmentMs }
            ?: slideBar.endMs
        return slideBar.startMs to maxOf(slideBar.endMs, transEnd)
    }

    /** Fits the active slide in `CurrentSlide` mode, or the entire deck in `AllSlides` mode. */
    fun fitCurrentScope(surface: TimelineSurface? = viewport.activeSurface) {
        if (trackFilterMode == TimelineTrackFilterMode.CurrentSlide) {
            val range = activeSlideTimeRange()
            if (range != null) {
                viewport.zoomToRange(range.first, range.second, surface = surface)
                return
            }
        }
        viewport.zoomToFit(surface)
    }

    private fun syncSelectionIfStaleForSlide(slideId: String?, beatIndex: Int) {
        if (slideId == null || selection == DocumentTimelineSelection.None) return
        val matches = DocumentTimelineSelectionSupport.selectionMatchesSlide(
            selection, snapshot, slideId, currentPresentedSceneIndex,
        )
        if (!matches) {
            selection = DocumentTimelineSelection.BeatHeader(slideId, beatIndex)
            expandedTextTrackId = null
            selectedFragmentAddress = null
            highlightedNodeId = null
        }
    }

    /**
     * Resolves the `(slideId, beatIndex)` targeted by beat insert/delete actions: honors an explicit
     * beat/track selection first, otherwise targets the active presented slide & beat (NEVER
     * falling back to slide 1's last beat when editing another slide).
     */
    fun activeBeatTarget(): Pair<String, Int>? = DocumentTimelineSelectionSupport.activeBeatTarget(
        doc = store.document,
        snapshot = snapshot,
        selection = selection,
        presentedSlideId = currentPresentedSlideId,
        presentedBeatIndex = currentPresentedBeatIndex,
        presentedTarget = ::currentPresentedTarget,
    )

    private fun currentPresentedTarget(): Pair<String, Int>? {
        val activeSlides = store.document.slides.filter { !it.skipped }
        if (activeSlides.isEmpty()) return null
        val slide = currentPresentedSlideId?.let { id -> activeSlides.firstOrNull { it.id == id } }
            ?: activeSlides.getOrNull(currentPresentedSceneIndex)
            ?: activeSlides.first()
        return slide.id to currentPresentedBeatIndex.coerceIn(0, slide.beats.lastIndex.coerceAtLeast(0))
    }

    fun seekToMs(timeMs: Int) {
        val clamped = timeMs.coerceIn(0, snapshot.snapshot.totalDurationMs.coerceAtLeast(0))
        playheadMs = clamped
        syncPresentedLocationFromTime(clamped)
        onPlayheadChanged?.invoke(clamped)
    }

    /** Updates [playheadMs] to reflect external stage navigation without re-invoking [onPlayheadChanged]. */
    fun syncPlayheadFromStage(timeMs: Int) {
        val clamped = timeMs.coerceIn(0, snapshot.snapshot.totalDurationMs.coerceAtLeast(0))
        playheadMs = clamped
        syncPresentedLocationFromTime(clamped)
    }

    private fun syncPresentedLocationFromTime(timeMs: Int) {
        val spans = snapshot.snapshot.stateSpans
        if (spans.isEmpty()) return
        val span = spans.firstOrNull { s ->
            (s.durationMs > 0 || s == spans.last()) &&
                (timeMs < s.startMs + s.durationMs || s == spans.last())
        } ?: spans.last()
        val activeSlides = store.document.slides.filter { !it.skipped }
        val slide = activeSlides.getOrNull(span.sceneIndex) ?: return
        val nextBeat = span.frameIndex.coerceIn(0, slide.beats.lastIndex.coerceAtLeast(0))
        currentPresentedSceneIndex = span.sceneIndex
        currentPresentedSlideId = slide.id
        currentPresentedBeatIndex = nextBeat
        syncSelectionIfStaleForSlide(slide.id, nextBeat)
    }

    fun stepBeat(delta: Int) {
        if (delta == 0) return
        val external = onStepBeat
        if (external != null) {
            external(delta)
            return
        }
        val beats = snapshot.snapshot.eventBeats
        if (beats.isEmpty()) return
        val target = if (delta > 0) {
            beats.firstOrNull { it.timeMs > playheadMs + 2 } ?: beats.last()
        } else {
            beats.lastOrNull { it.timeMs < playheadMs - 2 } ?: beats.first()
        }
        seekToMs(target.timeMs)
    }

    fun togglePlayback() {
        val external = onTogglePlayback
        if (external != null) {
            external()
        } else {
            isPlaying = !isPlaying
        }
    }

    // ── Loop Sub-Composition Scrubbing ───────────────────────────────────────────────────────────

    fun enterLoopSubComp(loopId: String) {
        val ref = snapshot.trackRefsByTrackId["doc:loop:$loopId"] as? DocumentTimelineTrackRef.LoopTrackBar
            ?: return
        selection = DocumentTimelineSelection.Loop(loopId)
        activeSubCompLoopId = loopId
        subCompositionScrubMillis = (subCompositionScrubMillis ?: 0L).coerceIn(0L, ref.effectivePeriodMs.coerceAtLeast(1L))
        expandedTextTrackId = null
        selectedFragmentAddress = null
    }

    fun exitLoopSubComp() {
        activeSubCompLoopId = null
        subCompositionScrubMillis = null
    }

    fun scrubLoopSubComp(millis: Long) {
        val ref = activeSubCompLoopRef()
        val maxMs = ref?.effectivePeriodMs?.coerceAtLeast(1L) ?: Long.MAX_VALUE
        subCompositionScrubMillis = millis.coerceIn(0L, maxMs)
    }

    fun activeSubCompLoopRef(): DocumentTimelineTrackRef.LoopTrackBar? {
        val id = activeSubCompLoopId ?: return null
        return snapshot.trackRefsByTrackId["doc:loop:$id"] as? DocumentTimelineTrackRef.LoopTrackBar
    }

    // ── Selection & Text Track Expansion ─────────────────────────────────────────────────────────

    fun isExpandableTextTrack(ref: DocumentTimelineTrackRef?): Boolean = when (ref) {
        is DocumentTimelineTrackRef.SlideBuildTrack -> (ref.isTextMorph || ref.isTextDelivery) && ref.trackPlan.pieces != null
        is DocumentTimelineTrackRef.TransitionTrackBar -> ref.isTextMorph && ref.trackPlan.pieces != null
        else -> false
    }

    fun selectTrack(trackId: String, blockId: String? = null) {
        val previous = selection
        selection = DocumentTimelineSelection.Track(trackId, blockId)
        val ref = snapshot.trackRefsByTrackId[trackId]
        expandedTextTrackId = if (isExpandableTextTrack(ref)) trackId else null
        selectedFragmentAddress = null
        if (previous != selection) notifyNodeSelected(ref)
    }

    fun toggleExpandTextTrack(trackId: String) {
        val ref = snapshot.trackRefsByTrackId[trackId]
        if (!isExpandableTextTrack(ref)) return
        if (expandedTextTrackId == trackId) {
            expandedTextTrackId = null
        } else {
            selection = DocumentTimelineSelection.Track(trackId)
            expandedTextTrackId = trackId
        }
    }

    fun selectBeatHeader(slideId: String, beatIndex: Int) {
        selection = DocumentTimelineSelection.BeatHeader(slideId, beatIndex)
        expandedTextTrackId = null
        selectedFragmentAddress = null
        highlightedNodeId = null
        snapshot.beatHeader(slideId, beatIndex)?.let { viewport.ensureTimeVisible(it.startMs.toFloat()) }
        // Header / cleared timeline selections clear the stage element selection too (no drift).
        onSelectedNodeChanged?.invoke(null)
    }

    fun selectTransitionHeader(fromSlideId: String, toSlideId: String) {
        selection = DocumentTimelineSelection.TransitionHeader(fromSlideId, toSlideId)
        expandedTextTrackId = null
        selectedFragmentAddress = null
        highlightedNodeId = null
        snapshot.transitionHeader(fromSlideId, toSlideId)?.let { viewport.ensureTimeVisible(it.startMs.toFloat()) }
        // Header / cleared timeline selections clear the stage element selection too (no drift).
        onSelectedNodeChanged?.invoke(null)
    }

    fun selectLoop(loopId: String) {
        val previous = selection
        selection = DocumentTimelineSelection.Loop(loopId)
        expandedTextTrackId = null
        selectedFragmentAddress = null
        if (previous != selection) notifyNodeSelected(snapshot.trackRefsByTrackId["doc:loop:$loopId"])
    }

    fun clearSelection() {
        selection = DocumentTimelineSelection.None
        expandedTextTrackId = null
        selectedFragmentAddress = null
        highlightedNodeId = null
        // Header / cleared timeline selections clear the stage element selection too (no drift).
        onSelectedNodeChanged?.invoke(null)
    }

    fun selectedTrackRef(): DocumentTimelineTrackRef? = when (val sel = selection) {
        is DocumentTimelineSelection.Track -> snapshot.trackRefsByTrackId[sel.trackId]
        is DocumentTimelineSelection.Loop -> snapshot.trackRefsByTrackId["doc:loop:${sel.loopId}"]
        else -> null
    }

    /** Returns draggable fragment slots (excluding `@size`) for [ref], if it is a text morph or delivery track. */
    fun fragmentSlotsFor(ref: DocumentTimelineTrackRef?): List<Slot> =
        DocumentTimelineMutationSupport.fragmentSlotsFor(ref)

    /** Resolves actual effective [TransitionSpec] for [fromSlideId] without mutating the document. */
    fun effectiveTransitionSpec(fromSlideId: String): TransitionSpec =
        DocumentTimelineMutationSupport.effectiveTransitionSpec(store.document, themeNameProvider(), fromSlideId)

    /** Resolves actual [ResolvedPhaseTimings] for a beat header from the active theme & beat phases. */
    fun resolvedBeatPhases(slideId: String, beatIndex: Int): ResolvedPhaseTimings =
        DocumentTimelineMutationSupport.resolvedBeatPhases(store.document, themeNameProvider(), snapshot, slideId, beatIndex)

    /** Resolves actual [ResolvedPhaseTimings] for a cross-slide transition header. */
    fun resolvedTransitionPhases(fromSlideId: String): ResolvedPhaseTimings =
        DocumentTimelineMutationSupport.resolvedTransitionPhases(store.document, themeNameProvider(), fromSlideId)

    /** Resolves actual [ResolvedPhaseTimings] for an expanded text morph track from its real [Slot]s. */
    fun resolvedTextMorphPhases(ref: DocumentTimelineTrackRef): ResolvedPhaseTimings =
        DocumentTimelineMutationSupport.resolvedTextMorphPhases(store.document, themeNameProvider(), ref)

    /** Returns the current effective auto/manual stagger delay for [nodeId] on a beat. */
    fun plannedNodeDelayMs(slideId: String, beatIndex: Int, nodeId: String): Int =
        DocumentTimelineMutationSupport.plannedNodeDelayMs(store.document, snapshot, slideId, beatIndex, nodeId)

    /** Returns the current effective auto/manual stagger delay for [nodeId] on a transition. */
    fun plannedTransitionNodeDelayMs(fromSlideId: String, toSlideId: String, nodeId: String): Int =
        DocumentTimelineMutationSupport.plannedTransitionNodeDelayMs(
            store.document, themeNameProvider(), snapshot, fromSlideId, toSlideId, nodeId,
        )

    // ── Explicit Gesture Transactions ─────────────────────────────────────────────────────────────

    fun beginGesture(label: String): Boolean {
        if (activeGestureTx?.isActive == true) return true
        onBeforeBeginGesture?.invoke()
        val tx = store.begin(label) ?: return false
        activeGestureTx = tx
        return true
    }

    fun performUndo() {
        val external = onUndoRequest
        if (external != null) {
            external()
        } else {
            cancelActiveGesture()
            store.undo()
            refreshSnapshot()
        }
    }

    fun performRedo() {
        val external = onRedoRequest
        if (external != null) {
            external()
        } else {
            cancelActiveGesture()
            store.redo()
            refreshSnapshot()
        }
    }

    fun commitActiveGesture() {
        val tx = activeGestureTx ?: return
        activeGestureTx = null
        tx.commit()
        refreshSnapshot()
    }

    fun cancelActiveGesture(): Boolean {
        val tx = activeGestureTx
        if (tx != null && tx.isActive) {
            activeGestureTx = null
            tx.cancel()
            refreshSnapshot()
            return true
        }
        if (store.hasOpenTransaction) {
            store.cancelActiveTransaction()
            refreshSnapshot()
            return true
        }
        return false
    }

    internal fun applyDocumentMutation(
        label: String,
        useActiveGesture: Boolean,
        transform: (DeckDocument) -> DeckDocument,
    ): Boolean {
        val tx = activeGestureTx
        if (useActiveGesture && tx != null && tx.isActive) {
            tx.update(transform)
            refreshSnapshot()
            return true
        }
        val ok = store.edit(label, transform)
        if (ok) refreshSnapshot()
        return ok
    }

    // ── Beat Header Motion Stagger & Phases ───────────────────────────────────────────────────────

    fun updateBeatStagger(
        slideId: String,
        beatIndex: Int,
        ms: Int? = null,
        order: StaggerOrder? = null,
        reverse: Boolean? = null,
        useActiveGesture: Boolean = false,
    ): Boolean = applyDocumentMutation("Update beat stagger", useActiveGesture) { doc ->
        DocumentTimelineMutationSupport.updateBeatSpec(doc, slideId, beatIndex) { beat ->
            val prev = beat.stagger ?: Stagger()
            beat.copy(
                stagger = prev.copy(
                    ms = (ms ?: prev.ms).coerceAtLeast(0),
                    order = order ?: prev.order,
                    reverse = reverse ?: prev.reverse,
                ),
            )
        }
    }

    fun updateBeatPhaseTiming(
        slideId: String,
        beatIndex: Int,
        phase: SlotPhase,
        delayMs: Int? = null,
        durationMs: Int? = null,
        curve: Curve? = null,
        keepExistingDelay: Boolean = true,
        keepExistingDuration: Boolean = true,
        keepExistingCurve: Boolean = true,
        useActiveGesture: Boolean = false,
    ): Boolean = applyDocumentMutation("Update beat ${phase.name.lowercase()} phase", useActiveGesture) { doc ->
        DocumentTimelineMutationSupport.updateBeatSpec(doc, slideId, beatIndex) { beat ->
            beat.copy(
                phases = DocumentTimelineMutationSupport.updatePhases(
                    phases = beat.phases,
                    phase = phase,
                    delayMs = delayMs,
                    durationMs = durationMs,
                    curve = curve,
                    keepExistingDelay = keepExistingDelay,
                    keepExistingDuration = keepExistingDuration,
                    keepExistingCurve = keepExistingCurve,
                ),
            )
        }
    }

    fun setBeatNodeManualDelay(
        slideId: String,
        beatIndex: Int,
        nodeId: String,
        delayMs: Int,
        useActiveGesture: Boolean = false,
    ): Boolean = applyDocumentMutation("Set manual stagger for $nodeId", useActiveGesture) { doc ->
        DocumentTimelineMutationSupport.updateBeatSpec(doc, slideId, beatIndex) { beat ->
            val prev = beat.stagger ?: Stagger()
            val nextManual = LinkedHashMap(prev.manual)
            nextManual[nodeId] = delayMs.coerceAtLeast(0)
            beat.copy(stagger = prev.copy(manual = nextManual))
        }
    }

    fun resetBeatNodeManualDelay(
        slideId: String,
        beatIndex: Int,
        nodeId: String,
    ): Boolean = applyDocumentMutation("Reset manual stagger for $nodeId", useActiveGesture = false) { doc ->
        DocumentTimelineMutationSupport.updateBeatSpec(doc, slideId, beatIndex) { beat ->
            val prev = beat.stagger ?: return@updateBeatSpec beat
            if (nodeId !in prev.manual) return@updateBeatSpec beat
            val nextManual = LinkedHashMap(prev.manual).apply { remove(nodeId) }
            beat.copy(stagger = prev.copy(manual = nextManual).pruneIfDefault())
        }
    }

    // ── Transition Header Motion Stagger & Phases ─────────────────────────────────────────────────

    fun updateTransitionType(
        fromSlideId: String,
        type: TransitionType,
    ): Boolean = applyDocumentMutation("Change transition type", useActiveGesture = false) { doc ->
        DocumentTimelineMutationSupport.updateSlideTransition(doc, themeNameProvider(), fromSlideId) { tr ->
            tr.copy(type = type)
        }
    }

    fun updateTransitionStagger(
        fromSlideId: String,
        ms: Int? = null,
        order: StaggerOrder? = null,
        reverse: Boolean? = null,
        useActiveGesture: Boolean = false,
    ): Boolean = applyDocumentMutation("Update transition stagger", useActiveGesture) { doc ->
        DocumentTimelineMutationSupport.updateSlideTransition(doc, themeNameProvider(), fromSlideId) { tr ->
            val prev = tr.stagger ?: Stagger()
            tr.copy(
                stagger = prev.copy(
                    ms = (ms ?: prev.ms).coerceAtLeast(0),
                    order = order ?: prev.order,
                    reverse = reverse ?: prev.reverse,
                ),
            )
        }
    }

    fun updateTransitionPhaseTiming(
        fromSlideId: String,
        phase: SlotPhase,
        delayMs: Int? = null,
        durationMs: Int? = null,
        curve: Curve? = null,
        keepExistingDelay: Boolean = true,
        keepExistingDuration: Boolean = true,
        keepExistingCurve: Boolean = true,
        useActiveGesture: Boolean = false,
    ): Boolean = applyDocumentMutation("Update transition ${phase.name.lowercase()} phase", useActiveGesture) { doc ->
        DocumentTimelineMutationSupport.updateSlideTransition(doc, themeNameProvider(), fromSlideId) { tr ->
            tr.copy(
                phases = DocumentTimelineMutationSupport.updatePhases(
                    phases = tr.phases,
                    phase = phase,
                    delayMs = delayMs,
                    durationMs = durationMs,
                    curve = curve,
                    keepExistingDelay = keepExistingDelay,
                    keepExistingDuration = keepExistingDuration,
                    keepExistingCurve = keepExistingCurve,
                ),
            )
        }
    }

    fun setTransitionNodeManualDelay(
        fromSlideId: String,
        nodeId: String,
        delayMs: Int,
        useActiveGesture: Boolean = false,
    ): Boolean = applyDocumentMutation("Set transition manual stagger for $nodeId", useActiveGesture) { doc ->
        DocumentTimelineMutationSupport.updateSlideTransition(doc, themeNameProvider(), fromSlideId) { tr ->
            val prev = tr.stagger ?: Stagger()
            val nextManual = LinkedHashMap(prev.manual)
            nextManual[nodeId] = delayMs.coerceAtLeast(0)
            tr.copy(stagger = prev.copy(manual = nextManual))
        }
    }

    fun resetTransitionNodeManualDelay(
        fromSlideId: String,
        nodeId: String,
    ): Boolean = applyDocumentMutation("Reset transition manual stagger for $nodeId", useActiveGesture = false) { doc ->
        DocumentTimelineMutationSupport.updateSlideTransition(doc, themeNameProvider(), fromSlideId) { tr ->
            val prev = tr.stagger ?: return@updateSlideTransition tr
            if (nodeId !in prev.manual) return@updateSlideTransition tr
            val nextManual = LinkedHashMap(prev.manual).apply { remove(nodeId) }
            tr.copy(stagger = prev.copy(manual = nextManual).pruneIfDefault())
        }
    }

    // ── Track Bar Direct Manipulation (Move Start, Resize Duration, Curve) ───────────────────────

    /** Adjusts a track bar's start offset (see [DocumentTimelineTrackEdits.dragTrackBarStart]). */
    fun dragTrackBarStart(
        ref: DocumentTimelineTrackRef,
        newRelativeStartMs: Int,
        useActiveGesture: Boolean = false,
    ): Boolean = DocumentTimelineTrackEdits.dragTrackBarStart(this, ref, newRelativeStartMs, useActiveGesture)

    /** Resizes a track bar's right edge to [newDurationMs]. */
    fun resizeTrackBarDuration(
        ref: DocumentTimelineTrackRef,
        newDurationMs: Int,
        useActiveGesture: Boolean = false,
    ): Boolean = DocumentTimelineTrackEdits.resizeTrackBarDuration(this, ref, newDurationMs, useActiveGesture)

    fun updateTrackCurve(
        ref: DocumentTimelineTrackRef,
        curve: Curve,
        useActiveGesture: Boolean = false,
    ): Boolean = DocumentTimelineTrackEdits.updateTrackCurve(this, ref, curve, useActiveGesture)

    fun updateBuildTiming(
        slideId: String,
        nodeId: String,
        buildIndex: Int,
        delayMs: Int? = null,
        durationMs: Int? = null,
        curve: Curve? = null,
        useActiveGesture: Boolean = false,
    ): Boolean = applyDocumentMutation("Update build timing", useActiveGesture) { doc ->
        DocumentTimelineMutationSupport.updateBuildTimingDoc(doc, slideId, nodeId, buildIndex, delayMs, durationMs, curve)
    }

    fun updateSpecimenTrack(
        slideId: String,
        nodeId: String,
        trackName: String,
        delayMs: Int? = null,
        durationMs: Int? = null,
        curve: Curve? = null,
        useActiveGesture: Boolean = false,
    ): Boolean = applyDocumentMutation("Update specimen track $trackName", useActiveGesture) { doc ->
        DocumentTimelineMutationSupport.updateSpecimenTrackDoc(doc, slideId, nodeId, trackName, delayMs, durationMs, curve)
    }

    // ── Intra-Text Fragment Stagger & Morph Phases ────────────────────────────────────────────────

    fun currentFragmentManualDelays(ref: DocumentTimelineTrackRef): Map<String, Int> =
        DocumentTimelineMutationSupport.currentFragmentManualDelays(store.document, ref)

    fun currentTextMorphSpec(ref: DocumentTimelineTrackRef): TextMorphSpec? =
        DocumentTimelineMutationSupport.currentTextMorphSpec(store.document, ref)

    fun setFragmentManualDelay(
        ref: DocumentTimelineTrackRef,
        fragmentAddress: String,
        delayMs: Int,
        useActiveGesture: Boolean = false,
    ): Boolean {
        val clamped = delayMs.coerceAtLeast(0)
        return mutateTextTrackStagger(
            ref = ref,
            label = "Set fragment delay $fragmentAddress",
            useActiveGesture = useActiveGesture,
        ) { prev ->
            val nextManual = LinkedHashMap(prev.manual)
            nextManual[fragmentAddress] = clamped
            prev.copy(manual = nextManual)
        }
    }

    fun resetFragmentManualDelay(
        ref: DocumentTimelineTrackRef,
        fragmentAddress: String,
    ): Boolean = mutateTextTrackStagger(
        ref = ref,
        label = "Reset fragment delay $fragmentAddress",
        useActiveGesture = false,
    ) { prev ->
        if (fragmentAddress !in prev.manual) return@mutateTextTrackStagger prev
        val nextManual = LinkedHashMap(prev.manual).apply { remove(fragmentAddress) }
        prev.copy(manual = nextManual).pruneIfDefault()
    }

    fun updateTextMorphStagger(
        ref: DocumentTimelineTrackRef,
        ms: Int? = null,
        order: StaggerOrder? = null,
        reverse: Boolean? = null,
        useActiveGesture: Boolean = false,
    ): Boolean = mutateTextTrackStagger(
        ref = ref,
        label = "Update text fragment stagger",
        useActiveGesture = useActiveGesture,
    ) { prev ->
        prev.copy(
            ms = (ms ?: prev.ms).coerceAtLeast(0),
            order = order ?: prev.order,
            reverse = reverse ?: prev.reverse,
        )
    }

    fun updateTextMorphPhaseTiming(
        ref: DocumentTimelineTrackRef,
        phase: SlotPhase,
        delayMs: Int? = null,
        durationMs: Int? = null,
        curve: Curve? = null,
        useActiveGesture: Boolean = false,
    ): Boolean {
        val isMorph = when (ref) {
            is DocumentTimelineTrackRef.SlideBuildTrack -> ref.isTextMorph
            is DocumentTimelineTrackRef.TransitionTrackBar -> ref.isTextMorph
            else -> false
        }
        if (!isMorph) return false
        val label = if (ref is DocumentTimelineTrackRef.TransitionTrackBar) {
            "Update transition text morph ${phase.name.lowercase()} phase"
        } else {
            "Update text morph ${phase.name.lowercase()} phase"
        }
        return applyDocumentMutation(label, useActiveGesture) { doc ->
            DocumentTimelineMutationSupport.updateTextMorphPhaseTimingDoc(
                doc, themeNameProvider(), ref, phase, delayMs, durationMs, curve,
            )
        }
    }

    private fun mutateTextTrackStagger(
        ref: DocumentTimelineTrackRef,
        label: String,
        useActiveGesture: Boolean,
        transform: (Stagger) -> Stagger?,
    ): Boolean {
        if (ref !is DocumentTimelineTrackRef.SlideBuildTrack &&
            ref !is DocumentTimelineTrackRef.TransitionTrackBar
        ) {
            return false
        }
        return applyDocumentMutation(label, useActiveGesture) { doc ->
            DocumentTimelineMutationSupport.mutateTextTrackStaggerDoc(doc, themeNameProvider(), ref, transform)
        }
    }

    fun setLoopPeriodMs(loopId: String, periodMs: Long, useActiveGesture: Boolean = false): Boolean =
        applyDocumentMutation("Update loop period $loopId", useActiveGesture) { doc ->
            doc.copy(loopPeriodsMs = LinkedHashMap(doc.loopPeriodsMs).apply { put(loopId, periodMs.coerceAtLeast(100L)) })
        }

    fun resetLoopPeriodMs(loopId: String): Boolean =
        applyDocumentMutation("Reset loop period $loopId", useActiveGesture = false) { doc ->
            if (loopId !in doc.loopPeriodsMs) return@applyDocumentMutation doc
            doc.copy(loopPeriodsMs = LinkedHashMap(doc.loopPeriodsMs).apply { remove(loopId) })
        }

    fun insertBeat(slideId: String, atBeat: Int): Boolean {
        val ok = applyDocumentMutation("Insert beat", useActiveGesture = false) { doc ->
            DeckStructureEdits.insertBeat(doc, slideId, atBeat)
        }
        if (ok) {
            store.document.slides.firstOrNull { it.id == slideId }?.let { s ->
                selectBeatHeader(slideId, atBeat.coerceIn(0, s.beats.lastIndex.coerceAtLeast(0)))
            }
        }
        return ok
    }

    fun deleteBeat(slideId: String, beatIndex: Int): Boolean {
        val slide = store.document.slides.firstOrNull { it.id == slideId } ?: return false
        if (beatIndex !in 1..slide.beats.lastIndex) return false
        return applyDocumentMutation("Delete beat", useActiveGesture = false) { doc ->
            DeckStructureEdits.deleteBeat(doc, slideId, beatIndex)
        }
    }

    fun deleteBuild(slideId: String, nodeId: String, buildIndex: Int): Boolean =
        applyDocumentMutation("Delete build", useActiveGesture = false) { doc ->
            DeckStructureEdits.deleteBuild(doc, slideId, nodeId, buildIndex)
        }

    fun handleKeyEvent(ev: KeyEvent, textInputHasFocus: Boolean = false): Boolean =
        ev.type == KeyEventType.KeyDown && !ev.isAltPressed &&
            handleShortcut(ev.key, ev.isMetaPressed || ev.isCtrlPressed, ev.isShiftPressed, textInputHasFocus)

    fun handleShortcut(
        key: Key,
        isCommandDown: Boolean = false,
        isShiftDown: Boolean = false,
        textInputHasFocus: Boolean = false,
    ): Boolean {
        if (!isCommandDown && key == Key.Escape) {
            if (cancelActiveGesture()) return true
            if (activeSubCompLoopId != null) {
                exitLoopSubComp()
                return true
            }
            if (selection != DocumentTimelineSelection.None) {
                clearSelection()
                return true
            }
            return false
        }
        if (!textInputHasFocus && isCommandDown && key == Key.Z) {
            if (isShiftDown) performRedo() else performUndo()
            return true
        }
        return false
    }
}
