package dev.sebastiano.ciacola.timeline

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import dev.bnorm.storyboard.Storyboard
import dev.bnorm.storyboard.easel.Animatic
import dev.sebastiano.ciacola.review.ReviewCommentHitForBake
import dev.sebastiano.ciacola.review.ReviewCommentStore
import dev.sebastiano.ciacola.review.ReviewTargetHit
import dev.sebastiano.ciacola.review.SlideReviewComment
import dev.sebastiano.ciacola.review.generateReviewCommentId
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Presenter/coordinator for Ciacola's After Effects Timeline Studio.
 *
 * Owns timeline snapshot indexing, playhead scrubbing, transport playback, 0-ms event-beat
 * insertion/deletion, loop sub-composition drill-down, and live track overrides.
 */
@Stable
class TimelineStudioController(
    initialAnimatic: Animatic,
    val overridesStore: TimelineOverridesStore,
    val loopClock: DeckLoopClock,
    val reviewStore: ReviewCommentStore? = null,
    val motionPathPreview: MotionPathPreviewState = MotionPathPreviewState(),
) {
    var animatic: Animatic by mutableStateOf(initialAnimatic)
        internal set

    var dockedInStoryWindow by mutableStateOf(
        System.getenv("CIACOLA_TIMELINE_OPEN")?.equals("true", ignoreCase = true) == true
    )
    var splitFraction by mutableFloatStateOf(0.56f)

    var selectedTrackId by mutableStateOf<String?>(null)
        private set

    var zoomPxPerSecond by mutableFloatStateOf(120f)
    var isPlaying by mutableStateOf(false)
        private set

    var snapshot by mutableStateOf(DeckTimelineSnapshot(340, emptyList(), emptyList(), emptyList()))
        internal set

    private var playbackJob: Job? = null

    val currentPlayheadTimeMs: Int
        get() {
            val activeLoop = loopClock.activeSubCompLoopId
            if (activeLoop != null) {
                return (loopClock.scrubbedMillis ?: 0L).toInt()
            }
            return snapshot.timeMsForStateFraction(
                stateIndex = animatic.activeFromStateIndex,
                fraction = animatic.activeFraction,
            )
        }

    fun refreshSnapshot(storyboard: Storyboard = animatic.storyboard) {
        snapshot = indexDeckTimeline(storyboard, overridesStore.data())
        if (selectedTrackId != null && snapshot.tracks.none { it.trackId == selectedTrackId }) {
            // Keep selection if it's a parent track of a stagger item
            val baseId = selectedTrackId?.substringBefore("#")
            selectedTrackId = baseId?.takeIf { id -> snapshot.tracks.any { it.trackId == id } }
        }
    }

    fun selectTrack(trackId: String?) {
        val canonical = trackId?.substringBefore("#")
        selectedTrackId = canonical
        motionPathPreview.selectedTrackId = canonical
    }

    fun scrubToTimeMs(scope: CoroutineScope, timeMs: Int) {
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
        scope.launch {
            animatic.seekToSegment(stateIdx, frac)
        }
    }

    fun togglePlayback(scope: CoroutineScope) {
        if (isPlaying) {
            stopPlayback()
            return
        }
        isPlaying = true
        playbackJob = scope.launch {
            var lastNanos = -1L
            val subLoopId = loopClock.activeSubCompLoopId
            if (subLoopId != null) {
                val desc = loopClock.registeredLoops[subLoopId]
                val period = loopClock.effectivePeriodMillis(subLoopId, desc?.defaultPeriodMillis ?: 6_000L)
                var currentMs = (loopClock.scrubbedMillis ?: 0L).toFloat()
                while (isPlaying) {
                    withFrameNanos { now ->
                        if (lastNanos >= 0L) {
                            currentMs = (currentMs + (now - lastNanos) / 1_000_000f) % period.toFloat()
                            loopClock.scrubbedMillis = currentMs.toLong()
                        }
                        lastNanos = now
                    }
                }
            } else {
                val totalMs = snapshot.totalDurationMs.coerceAtLeast(1)
                var playMs = currentPlayheadTimeMs.let { if (it >= totalMs - 8) 0 else it }.toFloat()
                while (isPlaying && playMs < totalMs) {
                    var deltaMs = 0f
                    withFrameNanos { now ->
                        if (lastNanos >= 0L) {
                            deltaMs = ((now - lastNanos) / 1_000_000f).coerceIn(0f, 64f)
                        }
                        lastNanos = now
                    }
                    if (deltaMs > 0f) {
                        playMs = (playMs + deltaMs).coerceAtMost(totalMs.toFloat())
                        val (stIdx, frac) = snapshot.stateFractionForTimeMs(playMs.toInt())
                        animatic.seekToSegment(stIdx, frac)
                    }
                }
                isPlaying = false
            }
        }
    }

    fun stopPlayback() {
        isPlaying = false
        playbackJob?.cancel()
        playbackJob = null
    }

    fun stepBeat(scope: CoroutineScope, delta: Int) {
        stopPlayback()
        if (loopClock.activeSubCompLoopId != null) {
            val step = 500L * delta
            val next = ((loopClock.scrubbedMillis ?: 0L) + step).coerceAtLeast(0L)
            loopClock.scrubbedMillis = next
            return
        }
        val beats = snapshot.eventBeats.filter { !it.isSlideBoundary }
        if (beats.isEmpty()) return
        val nowMs = currentPlayheadTimeMs
        val targetBeat = if (delta > 0) {
            beats.firstOrNull { it.timeMs > nowMs + 12 } ?: beats.last()
        } else {
            beats.lastOrNull { it.timeMs < nowMs - 12 } ?: beats.first()
        }
        scope.launch {
            animatic.seekToSegment(targetBeat.stateIndex, 0f)
        }
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
        loopClock.enterSubComp(loopId)
        if (sceneIndex != null) {
            val stateIdx = animatic.stateDescriptors.firstOrNull {
                it.sceneIndex == sceneIndex && it.frameIndex == 0 && !it.isBoundaryStart && !it.isBoundaryEnd
            }?.stateIndex
            if (stateIdx != null) {
                scope.launch {
                    animatic.seekToSegment(stateIdx, 0f)
                }
            }
        }
    }

    fun exitLoopSubComp() {
        stopPlayback()
        loopClock.exitSubComp()
    }

    fun previewSelectedBlock(scope: CoroutineScope) {
        val selId = selectedTrackId ?: return
        val row = snapshot.tracks.firstOrNull { it.trackId == selId } ?: return
        val firstBlock = row.blocks.firstOrNull() ?: return
        stopPlayback()
        isPlaying = true
        val startMs = (firstBlock.startMs - firstBlock.delayMs).coerceAtLeast(0)
        val endMs = (firstBlock.startMs + firstBlock.durationMs + 40).coerceAtMost(snapshot.totalDurationMs)
        playbackJob = scope.launch {
            var playMs = startMs.toFloat()
            val (initIdx, initFrac) = snapshot.stateFractionForTimeMs(startMs)
            animatic.seekToSegment(initIdx, initFrac)
            var lastNanos = -1L
            while (isPlaying && playMs < endMs) {
                var delta = 0f
                withFrameNanos { now ->
                    if (lastNanos >= 0L) delta = ((now - lastNanos) / 1_000_000f).coerceIn(0f, 64f)
                    lastNanos = now
                }
                if (delta > 0f) {
                    playMs = (playMs + delta).coerceAtMost(endMs.toFloat())
                    val (sIdx, frac) = snapshot.stateFractionForTimeMs(playMs.toInt())
                    animatic.seekToSegment(sIdx, frac)
                }
            }
            isPlaying = false
        }
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
): TimelineStudioController {
    val controller = remember(overridesStore, loopClock, reviewStore, motionPathPreview) {
        TimelineStudioController(animatic, overridesStore, loopClock, reviewStore, motionPathPreview)
    }
    controller.animatic = animatic
    LaunchedEffect(
        animatic.storyboard,
        overridesStore.structuralRevision,
        overridesStore.parameterRevision,
    ) {
        controller.refreshSnapshot(animatic.storyboard)
    }
    return controller
}
