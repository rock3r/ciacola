package dev.sebastiano.ciacola.timeline

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import dev.bnorm.storyboard.AdvanceDirection
import dev.bnorm.storyboard.Storyboard
import dev.bnorm.storyboard.easel.Animatic
import dev.sebastiano.ciacola.review.ReviewCommentStore
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class TimelineBakeStatus {
    Idle,
    Sending,
    Sent,
    Error,
}

/**
 * Quiet time after an override edit before the deck is re-indexed. Re-indexing runs offscreen
 * scenes on the UI thread (~200 ms), so bursts of stepper clicks or slider moves are coalesced.
 */
private const val INDEX_DEBOUNCE_MS = 300L

/**
 * Whether a scrub step to `(stateIndex, fraction)` can seek in place: it stays strictly inside the
 * segment the stage already shows, so no transition is retargeted and no scene rebuild is needed.
 */
internal fun scrubStepIsInPlace(activeFrom: Int, activeTo: Int, stateIndex: Int, fraction: Float): Boolean =
    fraction > 0f && fraction < 1f && activeFrom == stateIndex && activeTo == stateIndex + 1

/** A requested playhead position, already mapped to an Animatic segment. */
internal data class ScrubRequest(
    val timeMs: Int,
    val stateIndex: Int,
    val fraction: Float,
    val sequence: Long,
    val forceRebuild: Boolean = false,
)

/**
 * Presenter/coordinator for Ciacola's After Effects Timeline Studio.
 *
 * Owns timeline snapshot indexing, playhead scrubbing, transport playback, 0-ms event-beat
 * insertion/deletion, loop sub-composition drill-down, selection, zoom/pan, and live overrides.
 */
@Stable
class TimelineStudioController(
    initialAnimatic: Animatic,
    val overridesStore: TimelineOverridesStore,
    val loopClock: DeckLoopClock,
    val reviewStore: ReviewCommentStore? = null,
    val motionPathPreview: MotionPathPreviewState = MotionPathPreviewState(),
    val stageSelection: TimelineStageSelection = TimelineStageSelection(),
) {
    var animatic: Animatic by mutableStateOf(initialAnimatic)
        internal set

    val viewport = TimelineViewportState()
    val preview = StagePreviewViewportState()

    var dockedInStoryWindow by mutableStateOf(
        System.getenv("CIACOLA_TIMELINE_OPEN")?.equals("true", ignoreCase = true) == true
    )
    var detachedWindowVisible by mutableStateOf(false)

    /** Authoring mode: the timeline is visible (docked or detached). */
    val authoringActive: Boolean get() = dockedInStoryWindow || detachedWindowVisible

    var splitFraction by mutableFloatStateOf(0.56f)

    /** Canonical selected track (stagger items resolve to their group) for the Inspector. */
    var selectedTrackId by mutableStateOf<String?>(null)
        private set

    /** Exact selected track id, including a stagger item's `#itemN` suffix. */
    var selectedExactTrackId by mutableStateOf<String?>(null)
        private set

    var isPlaying by mutableStateOf(false)
        private set

    var bakeStatus by mutableStateOf(TimelineBakeStatus.Idle)
        private set

    var bakeStatusMessage by mutableStateOf<String?>(null)
        private set

    var copiedConfirmationVisible by mutableStateOf(false)
        private set

    private var copiedFlashJob: Job? = null

    internal var conversationIdProvider: (() -> String)? = null

    internal var clipboardWriter: (String) -> Unit = { text ->
        runCatching {
            Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
        }
    }

    /** Space is held: drags pan (AE hand tool); a tap without dragging toggles playback. */
    var spaceHeld by mutableStateOf(false)
        internal set
    internal var spaceUsedForPan = false

    var snapshot by mutableStateOf(DeckTimelineSnapshot(340, emptyList(), emptyList(), emptyList()))
        internal set

    private var playbackJob: Job? = null
    private var scrubSequence = 0L
    private var appliedScrubSequence by mutableLongStateOf(0L)
    private val scrubRequests = MutableStateFlow<ScrubRequest?>(null)
    private var lastRequested by mutableStateOf<ScrubRequest?>(null)
    var isScrubDragging by mutableStateOf(false)
        private set

    fun beginScrubDrag() {
        isScrubDragging = true
    }

    fun endScrubDrag() {
        isScrubDragging = false
        val req = lastRequested
        if (req != null && req.fraction > 0f && loopClock.activeSubCompLoopId == null) {
            val settleReq = req.copy(sequence = ++scrubSequence, forceRebuild = true)
            lastRequested = settleReq
            scrubRequests.value = settleReq
        }
    }

    init {
        syncLoopPeriodsFromStore()
        stageSelection.onPick = { trackId ->
            selectTrack(trackId)
            if (trackId != null) viewport.pendingRevealTrackId = trackId
        }
    }

    fun syncLoopPeriodsFromStore() {
        val persisted = overridesStore.data().loopPeriodsMs
        val toRemove = loopClock.periodOverridesMillis.keys.filter { it !in persisted }
        toRemove.forEach { loopClock.periodOverridesMillis.remove(it) }
        for ((loopId, periodMs) in persisted) {
            if (loopClock.periodOverridesMillis[loopId] != periodMs) {
                loopClock.periodOverridesMillis[loopId] = periodMs
            }
        }
    }

    fun updateLoopPeriod(loopId: String, periodMs: Long) {
        val clamped = periodMs.coerceIn(500L, 30_000L)
        loopClock.periodOverridesMillis[loopId] = clamped
        overridesStore.updateLoopPeriod(loopId, clamped)
    }

    fun resetLoopPeriod(loopId: String) {
        loopClock.periodOverridesMillis.remove(loopId)
        overridesStore.resetLoopPeriod(loopId)
    }

    fun resetAllOverrides() {
        loopClock.periodOverridesMillis.clear()
        overridesStore.resetAll()
    }

    fun undo() {
        if (overridesStore.undo()) {
            syncLoopPeriodsFromStore()
        }
    }

    fun redo() {
        if (overridesStore.redo()) {
            syncLoopPeriodsFromStore()
        }
    }

    fun copyOverridesAsKotlinSnippet(scope: CoroutineScope): String {
        val snippet = overridesStore.formatOverridesAsKotlinSnippet()
        clipboardWriter(snippet)
        copiedConfirmationVisible = true
        copiedFlashJob?.cancel()
        copiedFlashJob = scope.launch {
            delay(1_800L)
            copiedConfirmationVisible = false
        }
        return snippet
    }

    fun isPlayheadOnInjectedVirtualBeat(): Boolean {
        if (loopClock.activeSubCompLoopId != null) return false
        val idx = animatic.targetIndex
        val rawFrame = animatic.storyboard.scenes.getOrNull(idx.sceneIndex)?.frames?.getOrNull(idx.frameIndex)
        if (rawFrame is VirtualizedFrame<*>) {
            return rawFrame.isInjected
        }
        val beatOvr = overridesStore.data().sceneBeats[idx.sceneIndex] ?: return false
        return resolveVirtualBeatToBaseBeat(beatOvr, idx.frameIndex).second
    }

    val currentPlayheadTimeMs: Int
        get() {
            if (loopClock.activeSubCompLoopId != null) {
                return (loopClock.scrubbedMillis ?: 0L).toInt()
            }
            val from = animatic.activeFromStateIndex
            val fraction = animatic.activeFraction
            // Keep the playhead locked 1:1 to the requested scrub position both while the scrub
            // worker is applying the seek (so the red line never snaps back to a beat diamond mid-seek)
            // and while resting at the position that request mapped to (e.g. inside a click hold).
            val requested = lastRequested
            if (requested != null) {
                if (isScrubDragging || appliedScrubSequence < requested.sequence) {
                    return requested.timeMs
                }
                val (expectedState, expectedFraction) = normalized(requested.stateIndex, requested.fraction)
                if (from == expectedState && abs(fraction - expectedFraction) < 0.005f) {
                    return requested.timeMs
                }
            }
            return snapshot.timeMsForStateFraction(stateIndex = from, fraction = fraction)
        }

    private fun normalized(stateIndex: Int, fraction: Float): Pair<Int, Float> {
        val last = animatic.stateDescriptors.lastIndex
        return if (fraction >= 1f && stateIndex < last) (stateIndex + 1) to 0f else stateIndex to fraction
    }

    fun refreshSnapshot(storyboard: Storyboard = animatic.storyboard) {
        snapshot = indexDeckTimeline(storyboard, overridesStore.data())
        viewport.contentDurationMs = snapshot.totalDurationMs.coerceAtLeast(1)
        val selected = selectedExactTrackId
        if (selected != null && snapshot.tracks.none { it.trackId == selected || it.trackId == selected.substringBefore("#") }) {
            selectTrack(null)
        }
    }

    /**
     * Whether the row for [trackId] reads as selected: the exact track, or every row of a selected
     * stagger group (matching the stage, which highlights every item of a selected group).
     */
    fun isTrackRowSelected(trackId: String): Boolean {
        val exact = selectedExactTrackId ?: return false
        if (exact == trackId) return true
        return '#' !in exact && trackId.substringBefore("#") == exact
    }

    fun selectTrack(trackId: String?) {
        val canonical = trackId?.substringBefore("#")
        selectedExactTrackId = trackId
        selectedTrackId = canonical
        motionPathPreview.selectedTrackId = canonical
        stageSelection.selectedTrackId = trackId
        stageSelection.selectedLabel = trackId?.let { id ->
            val parent = snapshot.tracks.firstOrNull { it.trackId == canonical }?.label?.substringAfter(" · ")
                ?.removePrefix("⇄ ")?.removePrefix("⟳ ")
            if ('#' in id) {
                val item = id.substringAfter("#item").toIntOrNull()?.plus(1)
                listOfNotNull(parent, item?.let { "Item $it" }).joinToString(" · ")
            } else {
                parent ?: id
            }
        }
    }

    /**
     * Requests the playhead at [timeMs]. Requests are conflated and applied by a single worker
     * ([runScrubWorker]), so pointer bursts never queue up or interleave.
     */
    fun scrubToTimeMs(timeMs: Int) {
        stopPlayback()
        val subLoopId = loopClock.activeSubCompLoopId
        if (subLoopId != null) {
            val desc = loopClock.registeredLoops[subLoopId]
            val period = loopClock.effectivePeriodMillis(subLoopId, desc?.defaultPeriodMillis ?: 6_000L)
            loopClock.scrubbedMillis = timeMs.toLong().coerceIn(0L, period)
            return
        }
        val clamped = timeMs.coerceIn(0, snapshot.totalDurationMs)
        val (stateIdx, frac) = snapshot.stateFractionForTimeMs(clamped)
        val request = ScrubRequest(clamped, stateIdx, frac, ++scrubSequence)
        lastRequested = request
        scrubRequests.value = request
    }

    /**
     * Applies the latest scrub request; slow seeks simply skip superseded requests.
     *
     * Rebuilding the scene subtree ([Animatic.seekToSegment] with `rebuildScene = true`) makes every
     * frame independent of the scrub path, but costs ~6-15 frames per step. While dragging:
     * - steps inside the active segment or across intra-slide beats seek in place (`rebuildScene = false`)
     *   so animations follow pointer scrubbing proportionally at 60 fps without snapping or freezing,
     * - a step that maps to the exact position already presented (a drag through a click hold) is
     *   skipped.
     */
    suspend fun runScrubWorker() = coroutineScope {
        var lastApplied: ScrubRequest? = null
        var restingNeedsRebuild = false
        scrubRequests.filterNotNull().collect { request ->
            val a = animatic
            val previous = lastApplied
            val shouldForceRebuild = request.forceRebuild && restingNeedsRebuild
            if (!shouldForceRebuild &&
                previous != null &&
                previous.stateIndex == request.stateIndex &&
                previous.fraction == request.fraction &&
                a.isPresenting(request.stateIndex, request.fraction)
            ) {
                appliedScrubSequence = maxOf(appliedScrubSequence, request.sequence)
                return@collect
            }
            val curDesc = a.stateDescriptors.getOrNull(a.activeFromStateIndex)
            val reqDesc = a.stateDescriptors.getOrNull(request.stateIndex)
            val sameSlideDrag = isScrubDragging &&
                curDesc != null &&
                reqDesc != null &&
                curDesc.sceneIndex == reqDesc.sceneIndex &&
                !curDesc.isBoundaryStart &&
                !curDesc.isBoundaryEnd &&
                !reqDesc.isBoundaryStart &&
                !reqDesc.isBoundaryEnd
            val inPlace = !shouldForceRebuild && (
                scrubStepIsInPlace(a.activeFromStateIndex, a.activeToStateIndex, request.stateIndex, request.fraction) ||
                    sameSlideDrag
                )
            a.seekToSegment(request.stateIndex, request.fraction, rebuildScene = !inPlace)
            restingNeedsRebuild = inPlace && request.fraction > 0f
            lastApplied = request
            appliedScrubSequence = maxOf(appliedScrubSequence, request.sequence)
        }
    }

    /** Whether the stage rests on (or inside the segment of) the position a scrub put it at. */
    private fun Animatic.isPresenting(stateIndex: Int, fraction: Float): Boolean =
        activeFromStateIndex == stateIndex && activeToStateIndex == if (fraction > 0f) stateIndex + 1 else stateIndex

    fun togglePlayback(scope: CoroutineScope) {
        if (isPlaying) {
            stopPlayback()
            return
        }
        val subLoopId = loopClock.activeSubCompLoopId
        val totalMs = snapshot.totalDurationMs.coerceAtLeast(1)
        val startMs = if (subLoopId != null) 0 else currentPlayheadTimeMs.let { if (it >= totalMs - 8) 0 else it }
        playRange(scope, startMs, if (subLoopId != null) Int.MAX_VALUE else totalMs)
    }

    private fun playRange(scope: CoroutineScope, startMs: Int, endMs: Int) {
        stopPlayback()
        isPlaying = true
        playbackJob = scope.launch {
            try {
                var lastNanos = -1L
                val subLoopId = loopClock.activeSubCompLoopId
                if (subLoopId != null) {
                    val desc = loopClock.registeredLoops[subLoopId]
                    val period = loopClock.effectivePeriodMillis(subLoopId, desc?.defaultPeriodMillis ?: 6_000L)
                    var currentMs = (loopClock.scrubbedMillis ?: 0L).toFloat()
                    while (true) {
                        withFrameNanos { now ->
                            if (lastNanos >= 0L) {
                                currentMs = (currentMs + (now - lastNanos) / 1_000_000f) % period.toFloat()
                                loopClock.scrubbedMillis = currentMs.toLong()
                            }
                            lastNanos = now
                        }
                    }
                }
                // Playback is real arrow-key playback: each animated segment runs through the
                // animatic's own animateTo (so it looks exactly like presenting), and click holds
                // just advance the playhead while the slide rests on its beat.
                var playMs = startMs
                val spans = snapshot.stateSpans
                var spanIndex = spans.indexOfFirst { playMs < it.startMs + it.durationMs }
                var isFirstSpan = true
                while (spanIndex in spans.indices && playMs < endMs) {
                    val span = spans[spanIndex++]
                    val spanEnd = span.startMs + span.durationMs
                    if (span.isHold) {
                        if (isFirstSpan) {
                            if (animatic.activeFromStateIndex != span.stateIndex || animatic.activeToStateIndex != span.stateIndex) {
                                animatic.seekToSegment(span.stateIndex, 0f)
                            }
                            if (animatic.activeFromStateIndex != span.stateIndex || animatic.activeToStateIndex != span.stateIndex) {
                                lastRequested = null
                                return@launch
                            }
                        } else if (animatic.activeFromStateIndex != span.stateIndex || animatic.activeToStateIndex != span.stateIndex) {
                            lastRequested = null
                            return@launch
                        }
                        val expectedIndex = Storyboard.Index(span.sceneIndex, span.frameIndex)
                        var heldMs = playMs.toFloat()
                        lastNanos = -1L
                        while (heldMs < spanEnd) {
                            if (
                                animatic.activeFromStateIndex != span.stateIndex ||
                                animatic.activeToStateIndex != span.stateIndex ||
                                animatic.currentIndex != expectedIndex ||
                                animatic.targetIndex != expectedIndex
                            ) {
                                lastRequested = null
                                return@launch
                            }
                            withFrameNanos { now ->
                                if (lastNanos >= 0L) heldMs += ((now - lastNanos) / 1_000_000f).coerceIn(0f, 64f)
                                lastNanos = now
                            }
                            if (
                                animatic.activeFromStateIndex != span.stateIndex ||
                                animatic.activeToStateIndex != span.stateIndex ||
                                animatic.currentIndex != expectedIndex ||
                                animatic.targetIndex != expectedIndex
                            ) {
                                lastRequested = null
                                return@launch
                            }
                            val shown = heldMs.toInt().coerceAtMost(spanEnd)
                            lastRequested = ScrubRequest(shown, span.stateIndex, 0f, ++scrubSequence)
                        }
                    } else {
                        if (!isFirstSpan && (animatic.activeFromStateIndex != span.stateIndex || animatic.activeToStateIndex != span.stateIndex)) {
                            lastRequested = null
                            return@launch
                        }
                        val fraction = if (span.durationMs > 0) (playMs - span.startMs).toFloat() / span.durationMs else 0f
                        lastRequested = null
                        val expectedEndState = (span.stateIndex + 1).coerceAtMost(animatic.stateDescriptors.lastIndex)
                        animatic.playSegmentToEnd(span.stateIndex, fraction.coerceIn(0f, 1f))
                        if (animatic.activeFromStateIndex != expectedEndState || animatic.activeToStateIndex != expectedEndState) {
                            return@launch
                        }
                        val endDesc = animatic.stateDescriptors.getOrNull(expectedEndState)
                        if (endDesc != null && !endDesc.isBoundaryStart && !endDesc.isBoundaryEnd) {
                            val expectedEndIndex = Storyboard.Index(endDesc.sceneIndex, endDesc.frameIndex)
                            if (animatic.currentIndex != expectedEndIndex || animatic.targetIndex != expectedEndIndex) {
                                return@launch
                            }
                        }
                    }
                    isFirstSpan = false
                    playMs = maxOf(playMs, spanEnd)
                }
            } finally {
                isPlaying = false
            }
        }
    }

    fun stopPlayback() {
        isPlaying = false
        playbackJob?.cancel()
        playbackJob = null
    }

    /**
     * Presents the next/previous beat exactly like the stage's arrow keys, so presenting keeps
     * working while keyboard focus sits on studio chrome (transport, layer rows, sliders).
     */
    fun advance(scope: CoroutineScope, direction: AdvanceDirection) {
        stopPlayback()
        lastRequested = null
        scope.launch { animatic.advance(direction) }
    }

    fun stepBeat(delta: Int) {
        stopPlayback()
        if (loopClock.activeSubCompLoopId != null) {
            val step = 500L * delta
            loopClock.scrubbedMillis = ((loopClock.scrubbedMillis ?: 0L) + step).coerceAtLeast(0L)
            return
        }
        val beats = snapshot.eventBeats
        if (beats.isEmpty()) return
        val nowMs = currentPlayheadTimeMs
        val targetBeat = if (delta > 0) {
            beats.firstOrNull { it.timeMs > nowMs + 2 } ?: beats.last()
        } else {
            beats.lastOrNull { it.timeMs < nowMs - 2 } ?: beats.first()
        }
        scrubToTimeMs(targetBeat.timeMs)
        viewport.ensureTimeVisible(targetBeat.timeMs.toFloat())
    }

    fun insertEventBeatAtPlayhead() {
        val targetIdx = animatic.targetIndex
        overridesStore.insertBeatAfter(
            sceneIndex = targetIdx.sceneIndex,
            beatIndex = targetIdx.frameIndex,
        )
    }

    fun removeEventBeatAtPlayhead() {
        val targetIdx = animatic.targetIndex
        val sceneBeatsCount = animatic.storyboard.scenes.getOrNull(targetIdx.sceneIndex)?.frames?.size ?: 1
        if (sceneBeatsCount <= 1) return
        overridesStore.removeInjectedBeat(
            sceneIndex = targetIdx.sceneIndex,
            beatIndex = targetIdx.frameIndex,
        )
    }

    fun enterLoopSubComp(scope: CoroutineScope, loopId: String, sceneIndex: Int? = null) {
        stopPlayback()
        if (sceneIndex != null) {
            val slide = snapshot.slides.getOrNull(sceneIndex)
            if (slide != null) {
                // Park the master playhead on the loop's slide (after its entrance).
                scrubToTimeMs(slide.startMs + slide.overlapInMs + 1)
            }
        }
        loopClock.enterSubComp(loopId)
    }

    fun exitLoopSubComp() {
        stopPlayback()
        loopClock.exitSubComp()
    }

    fun previewSelectedBlock(scope: CoroutineScope) {
        val selId = selectedExactTrackId ?: selectedTrackId ?: return
        val row = snapshot.tracks.firstOrNull { it.trackId == selId }
            ?: snapshot.tracks.firstOrNull { it.trackId == selectedTrackId }
            ?: return
        val firstBlock = row.blocks.firstOrNull() ?: return
        val startMs = (firstBlock.startMs - firstBlock.delayMs - 120).coerceAtLeast(0)
        val endMs = (firstBlock.startMs + firstBlock.durationMs + 120).coerceAtMost(snapshot.totalDurationMs)
        playRange(scope, startMs, endMs)
    }

    fun clearBakeStatus() {
        bakeStatus = TimelineBakeStatus.Idle
        bakeStatusMessage = null
    }

    fun bakeOverridesViaAgent(scope: CoroutineScope) {
        val rStore = reviewStore ?: return
        val data = overridesStore.data()
        if (!data.hasOverrides) return
        if (bakeStatus == TimelineBakeStatus.Sending) return

        val convId = (
            conversationIdProvider?.invoke()
                ?: rStore.loadConfig().conversationId.trim().ifEmpty { rStore.effectiveConversationId() }
            ).trim()
        if (convId.isBlank()) {
            bakeStatus = TimelineBakeStatus.Error
            bakeStatusMessage = "No Conversation ID — set in Review (F4)"
            return
        }

        val bin = rStore.effectiveAgentApiBin()
        val prompt = overridesStore.formatOverridesForAgentBake(
            workspaceRoot = rStore.workspaceRoot,
            gradleTaskHint = rStore.config.gradleTaskHint,
        )
        bakeStatus = TimelineBakeStatus.Sending
        bakeStatusMessage = "Baking…"
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val pb = ProcessBuilder(
                        bin,
                        "send-message",
                        "--title=Ciacola Timeline Bake",
                        convId,
                        prompt,
                    )
                    pb.environment().remove("ANTIGRAVITY_SOURCE_METADATA")
                    pb.directory(rStore.workspaceRoot.toFile())
                    val proc = pb.start()
                    val finished = proc.waitFor(15, TimeUnit.SECONDS)
                    if (!finished) {
                        proc.destroyForcibly()
                        error("Timed out invoking agentapi after 15s")
                    }
                    val stderr = proc.errorStream.bufferedReader().readText().trim()
                    val stdout = proc.inputStream.bufferedReader().readText().trim()
                    if (proc.exitValue() != 0) {
                        error("agentapi exited with ${proc.exitValue()}: ${stderr.ifBlank { stdout }}")
                    }
                }
            }
            if (result.isSuccess) {
                bakeStatus = TimelineBakeStatus.Sent
                bakeStatusMessage = "Bake sent to agent"
            } else {
                bakeStatus = TimelineBakeStatus.Error
                bakeStatusMessage = (result.exceptionOrNull()?.message ?: "Bake failed").take(120)
            }
        }
    }
}

@Composable
fun rememberTimelineStudioController(
    animatic: Animatic,
    overridesStore: TimelineOverridesStore,
    loopClock: DeckLoopClock,
    reviewStore: ReviewCommentStore? = null,
    motionPathPreview: MotionPathPreviewState = remember { MotionPathPreviewState() },
    stageSelection: TimelineStageSelection = remember { TimelineStageSelection() },
): TimelineStudioController {
    val controller = remember(overridesStore, loopClock, reviewStore, motionPathPreview, stageSelection) {
        TimelineStudioController(animatic, overridesStore, loopClock, reviewStore, motionPathPreview, stageSelection)
    }
    controller.animatic = animatic
    val paramRev = overridesStore.parameterRevision
    val structRev = overridesStore.structuralRevision
    SideEffect {
        if (paramRev >= 0 && structRev >= 0) {
            controller.syncLoopPeriodsFromStore()
        }
    }
    LaunchedEffect(
        animatic.storyboard,
        overridesStore.structuralRevision,
        overridesStore.parameterRevision,
    ) {
        // The first index (empty snapshot) runs immediately; edits are coalesced (a new key cancels
        // this effect while it waits).
        if (controller.snapshot.stateSpans.isNotEmpty()) delay(INDEX_DEBOUNCE_MS)
        controller.refreshSnapshot(animatic.storyboard)
    }
    LaunchedEffect(controller) {
        controller.runScrubWorker()
    }
    // While authoring, ambient loops follow the master playhead (AE pre-comp semantics), so the
    // stage always shows exactly the frame under the playhead; otherwise they free-run.
    LaunchedEffect(controller) {
        snapshotFlow {
            if (controller.authoringActive) controller.currentPlayheadTimeMs.toLong() else null
        }.collect { loopClock.masterTimeMillis = it }
    }
    LaunchedEffect(controller) {
        snapshotFlow { controller.authoringActive }.collect { authoring ->
            stageSelection.pickingEnabled = authoring
            // A sub-comp freezes every ambient loop; it must not outlive the studio.
            if (!authoring && loopClock.activeSubCompLoopId != null) controller.exitLoopSubComp()
        }
    }
    LaunchedEffect(controller, controller.authoringActive, controller.bakeStatus) {
        if (!controller.authoringActive && controller.bakeStatus != TimelineBakeStatus.Sent) return@LaunchedEffect
        while (true) {
            delay(750L)
            overridesStore.reloadFromDiskIfChanged(notifyRevisions = true)
        }
    }
    return controller
}
