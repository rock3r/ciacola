package dev.sebastiano.ciacola.studio

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import dev.bnorm.storyboard.AdvanceDirection
import dev.bnorm.storyboard.easel.Animatic
import dev.sebastiano.ciacola.timeline.DocumentTimelineController
import dev.sebastiano.ciacola.timeline.scrubStepIsInPlace
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Connects Timeline scrubbing → [Animatic] through a conflated worker (so 60 Hz pointer drags never
 * starve multi-frame cross-slide seeks), Timeline transport (step / play) → [Animatic], and stage
 * navigation → Timeline playhead.
 */
@Composable
internal fun TimelineAnimaticSyncEffects(
    timelineController: DocumentTimelineController,
    animatic: Animatic,
    appScope: CoroutineScope,
    paused: Boolean = false,
) {
    val pendingScrubRequest = remember(animatic) { MutableStateFlow<Pair<Long, Int>?>(null) }
    var scrubSeq by remember(animatic) { mutableStateOf(0L) }
    var isApplyingScrub by remember(animatic) { mutableStateOf(false) }
    var playbackJob by remember(animatic) { mutableStateOf<Job?>(null) }
    val latestPaused by rememberUpdatedState(paused)

    DisposableEffect(timelineController, animatic) {
        timelineController.onPlayheadChanged = { timeMs ->
            if (!latestPaused) {
                scrubSeq += 1L
                pendingScrubRequest.value = scrubSeq to timeMs
            }
        }
        timelineController.onStepBeat = { delta ->
            playbackJob?.cancel()
            playbackJob = null
            timelineController.isPlaying = false
            val direction = if (delta >= 0) AdvanceDirection.Forward else AdvanceDirection.Backward
            appScope.launch {
                animatic.advance(direction)
            }
        }
        timelineController.onTogglePlayback = {
            if (timelineController.isPlaying) {
                playbackJob?.cancel()
                playbackJob = null
                timelineController.isPlaying = false
            } else if (!latestPaused) {
                timelineController.isPlaying = true
                playbackJob?.cancel()
                playbackJob = appScope.launch {
                    try {
                        while (timelineController.isPlaying) {
                            val indices = animatic.storyboard.indices
                            if (indices.isEmpty() || animatic.currentIndex == indices.last()) {
                                break
                            }
                            animatic.advance(AdvanceDirection.Forward)
                            delay(350)
                        }
                    } finally {
                        timelineController.isPlaying = false
                        playbackJob = null
                    }
                }
            }
        }
        onDispose {
            playbackJob?.cancel()
            playbackJob = null
            timelineController.isPlaying = false
            timelineController.onPlayheadChanged = null
            timelineController.onStepBeat = null
            timelineController.onTogglePlayback = null
        }
    }
    LaunchedEffect(timelineController, paused) {
        if (paused) {
            playbackJob?.cancel()
            playbackJob = null
            timelineController.isPlaying = false
            pendingScrubRequest.value = null
        }
    }
    LaunchedEffect(animatic, timelineController, paused) {
        if (paused) return@LaunchedEffect
        pendingScrubRequest.collect { req ->
            if (req == null) return@collect
            pendingScrubRequest.compareAndSet(req, null)
            val snap = timelineController.snapshot.snapshot
            if (snap.stateSpans.isEmpty()) return@collect
            val (stateIdx, frac) = snap.stateFractionForTimeMs(req.second)
            val inPlace = scrubStepIsInPlace(
                activeFrom = animatic.activeFromStateIndex,
                activeTo = animatic.activeToStateIndex,
                stateIndex = stateIdx,
                fraction = frac,
            )
            isApplyingScrub = true
            try {
                animatic.seekToSegment(stateIdx, frac, rebuildScene = !inPlace)
            } finally {
                isApplyingScrub = false
            }
        }
    }
    LaunchedEffect(animatic, timelineController, paused) {
        if (paused) return@LaunchedEffect
        timelineController.refreshSnapshot()
        snapshotFlow { animatic.activeFromStateIndex to animatic.activeFraction }
            .distinctUntilChanged()
            .collect { (stateIdx, frac) ->
                if (!timelineController.hasActiveGesture && !isApplyingScrub && pendingScrubRequest.value == null) {
                    val syncedMs = timelineController.snapshot.snapshot.timeMsForStateFraction(stateIdx, frac)
                    timelineController.syncPlayheadFromStage(syncedMs)
                }
            }
    }
}
