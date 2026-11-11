package dev.sebastiano.ciacola.timeline

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import dev.bnorm.storyboard.Storyboard
import dev.bnorm.storyboard.easel.Animatic
import dev.sebastiano.ciacola.review.ReviewCommentHitForBake
import dev.sebastiano.ciacola.review.ReviewCommentStore
import dev.sebastiano.ciacola.review.ReviewTargetHit
import dev.sebastiano.ciacola.review.SlideReviewComment
import dev.sebastiano.ciacola.review.generateReviewCommentId
import java.time.Instant
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

/** A requested playhead position, already mapped to an Animatic segment. */
internal data class ScrubRequest(
    val timeMs: Int,
    val stateIndex: Int,
    val fraction: Float,
    val sequence: Long,
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

    /** Space is held: drags pan (AE hand tool); a tap without dragging toggles playback. */
    var spaceHeld by mutableStateOf(false)
        internal set
    internal var spaceUsedForPan = false

    var snapshot by mutableStateOf(DeckTimelineSnapshot(340, emptyList(), emptyList(), emptyList()))
        internal set

    private var playbackJob: Job? = null
    private var scrubSequence = 0L
    private val scrubRequests = MutableStateFlow<ScrubRequest?>(null)
    private var lastRequested by mutableStateOf<ScrubRequest?>(null)

    init {
        stageSelection.onPick = { trackId ->
            selectTrack(trackId)
            if (trackId != null) viewport.pendingRevealTrackId = trackId
        }
    }

    val currentPlayheadTimeMs: Int
        get() {
            if (loopClock.activeSubCompLoopId != null) {
                return (loopClock.scrubbedMillis ?: 0L).toInt()
            }
            val from = animatic.activeFromStateIndex
            val fraction = animatic.activeFraction
            // Show the exact requested time while the animatic sits where that request mapped
            // (a click hold maps a whole time range onto one settled state).
            val requested = lastRequested
            if (requested != null) {
                val (expectedState, expectedFraction) = normalized(requested.stateIndex, requested.fraction)
                if (from == expectedState && abs(fraction - expectedFraction) < 0.002f) {
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

    /** Applies the latest scrub request; slow seeks simply skip superseded requests. */
    suspend fun runScrubWorker() {
        scrubRequests.filterNotNull().collect { request ->
            animatic.seekToSegment(request.stateIndex, request.fraction)
        }
    }

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
                while (spanIndex in spans.indices && playMs < endMs) {
                    val span = spans[spanIndex++]
                    val spanEnd = span.startMs + span.durationMs
                    if (span.isHold) {
                        if (animatic.activeFromStateIndex != span.stateIndex || animatic.activeToStateIndex != span.stateIndex) {
                            animatic.seekToSegment(span.stateIndex, 0f)
                        }
                        var heldMs = playMs.toFloat()
                        lastNanos = -1L
                        while (heldMs < spanEnd) {
                            withFrameNanos { now ->
                                if (lastNanos >= 0L) heldMs += ((now - lastNanos) / 1_000_000f).coerceIn(0f, 64f)
                                lastNanos = now
                            }
                            val shown = heldMs.toInt().coerceAtMost(spanEnd)
                            lastRequested = ScrubRequest(shown, span.stateIndex, 0f, ++scrubSequence)
                        }
                    } else {
                        val fraction = if (span.durationMs > 0) (playMs - span.startMs).toFloat() / span.durationMs else 0f
                        lastRequested = null
                        animatic.playSegmentToEnd(span.stateIndex, fraction.coerceIn(0f, 1f))
                    }
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

    fun bakeOverridesViaAgent(scope: CoroutineScope) {
        val rStore = reviewStore ?: return
        val data = overridesStore.data()
        if (data.tracks.isEmpty() && data.sceneBeats.isEmpty()) return
        val note = overridesStore.formatOverridesForAgentBake(rStore.workspaceRoot)
        val idx = animatic.targetIndex
        scope.launch {
            rStore.submitComment(
                comment = SlideReviewComment(
                    id = generateReviewCommentId(),
                    sceneIndex = idx.sceneIndex,
                    frameIndex = idx.frameIndex,
                    sceneCount = animatic.storyboard.scenes.size,
                    frameCount = animatic.storyboard.scenes.getOrNull(idx.sceneIndex)?.frames?.size ?: 1,
                    sceneHeader = ReviewCommentHitForBake,
                    stageX = 960f,
                    stageY = 540f,
                    target = ReviewTargetHit(kind = "TimelineBake", label = "Timeline Overrides"),
                    note = note,
                    createdAt = Instant.now().toString(),
                ),
                dispatchImmediate = true,
            )
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
    LaunchedEffect(
        animatic.storyboard,
        overridesStore.structuralRevision,
        overridesStore.parameterRevision,
    ) {
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
        snapshotFlow { controller.authoringActive }.collect { stageSelection.pickingEnabled = it }
    }
    return controller
}
