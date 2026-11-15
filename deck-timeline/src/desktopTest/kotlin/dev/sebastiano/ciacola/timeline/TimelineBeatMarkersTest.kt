package dev.sebastiano.ciacola.timeline

import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import dev.bnorm.storyboard.Frame
import dev.bnorm.storyboard.Storyboard
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TimelineBeatMarkersTest {

    private fun multiSlideTestStoryboard(): Storyboard = Storyboard.build(
        title = "Beat Marker Fixture",
    ) {
        scene(frames = listOf(0, 1)) {
            val p by transition.animateFloat(transitionSpec = { tween(durationMillis = 280) }) { f ->
                when (f) {
                    is Frame.Value -> f.value.toFloat()
                    Frame.Start -> 0f
                    Frame.End -> 1f
                }
            }
            Box(Modifier.fillMaxSize()) { check(p >= 0f) }
        }
        scene(frames = listOf(0, 1, 2)) {
            val p by transition.animateFloat(transitionSpec = { tween(durationMillis = 320) }) { f ->
                when (f) {
                    is Frame.Value -> f.value.toFloat()
                    Frame.Start -> 0f
                    Frame.End -> 2f
                }
            }
            Box(Modifier.fillMaxSize()) { check(p >= 0f) }
        }
        scene(frames = listOf(0, 1)) {
            val p by transition.animateFloat(transitionSpec = { tween(durationMillis = 240) }) { f ->
                when (f) {
                    is Frame.Value -> f.value.toFloat()
                    Frame.Start -> 0f
                    Frame.End -> 1f
                }
            }
            Box(Modifier.fillMaxSize()) { check(p >= 0f) }
        }
    }

    @Test
    fun emitsStartOfHoldBeatMarkersForEveryValueFrameIncludingFinalBeat() {
        val storyboard = multiSlideTestStoryboard()
        val snapshot = indexDeckTimeline(storyboard)

        val expectedTotalBeats = storyboard.scenes.sumOf { it.frames.size }
        assertEquals(7, expectedTotalBeats)
        assertEquals(
            expectedTotalBeats,
            snapshot.eventBeats.size,
            "Every value frame including the final beat of the last slide must emit an EventBeatLine",
        )

        // Verify every EventBeatLine sits at the START of its value state's hold span, not on the outgoing cliff.
        val holdByState = snapshot.stateSpans.filter { it.isHold }.associateBy { it.stateIndex }
        for (beat in snapshot.eventBeats) {
            val hold = holdByState[beat.stateIndex]
            assertTrue(hold != null, "Expected a hold span for beat stateIndex=${beat.stateIndex}")
            assertEquals(
                hold.startMs,
                beat.timeMs,
                "EventBeatLine S${beat.sceneIndex + 1}:B${beat.frameIndex + 1} must be at hold.startMs (${hold.startMs}), not ${beat.timeMs}",
            )
            assertEquals(
                beat.timeMs,
                snapshot.timeMsForStateFraction(beat.stateIndex, 0f),
                "Settled playhead at fraction 0 must align 1:1 with EventBeatLine.timeMs",
            )
            val (resolvedState, resolvedFrac) = snapshot.stateFractionForTimeMs(beat.timeMs)
            assertEquals(
                beat.stateIndex,
                resolvedState,
                "stateFractionForTimeMs(beat.timeMs) must resolve back to the same beat state (not an End boundary)",
            )
            assertEquals(0f, resolvedFrac, 1e-4f)
        }

        // Verify final beat metadata specifically.
        val finalBeat = snapshot.eventBeats.last()
        assertEquals(2, finalBeat.sceneIndex)
        assertEquals(1, finalBeat.frameIndex)
        assertEquals(0, finalBeat.segmentDurationMs)
        assertFalse(finalBeat.isSlideBoundary)
    }

    @Test
    fun scrubStepPolicyMatchesInSegmentDragAndRebuildRules() {
        val midSeg = ScrubStepEndpoint(stateIndex = 2, toStateIndex = 3, fraction = 0.45f, sceneIndex = 0)
        val sameSegLater = ScrubStepEndpoint(stateIndex = 2, toStateIndex = 3, fraction = 0.72f, sceneIndex = 0)
        val sameSegEarlier = ScrubStepEndpoint(stateIndex = 2, toStateIndex = 3, fraction = 0.20f, sceneIndex = 0)
        val backToBeatStart = ScrubStepEndpoint(stateIndex = 2, toStateIndex = 2, fraction = 0f, sceneIndex = 0)
        val nextIntraSlideSeg = ScrubStepEndpoint(stateIndex = 3, toStateIndex = 4, fraction = 0.20f, sceneIndex = 0)
        val nextSlideSeg = ScrubStepEndpoint(stateIndex = 5, toStateIndex = 6, fraction = 0.20f, sceneIndex = 1)

        // Strict same-segment scrub seeks in place with no intra-slide crossing.
        val forwardInSeg = scrubStepPolicy(prev = midSeg, next = sameSegLater, dragging = true)
        assertTrue(forwardInSeg.inPlace)
        assertTrue(forwardInSeg.sameSegmentInPlace)
        assertFalse(forwardInSeg.sameSlideDrag)
        assertFalse(forwardInSeg.crossedIntraSlideBeat)
        assertFalse(forwardInSeg.reversedWithinSegment)

        // Reverse inside same segment seeks in place and flags reversedWithinSegment.
        val reverseInSeg = scrubStepPolicy(prev = midSeg, next = sameSegEarlier, dragging = true)
        assertTrue(reverseInSeg.inPlace)
        assertTrue(reverseInSeg.sameSegmentInPlace)
        assertTrue(reverseInSeg.reversedWithinSegment)

        // Dragging back to beat start (fraction 0) uses sameSlideDrag + flags crossedIntraSlideBeat.
        val dragBackToStart = scrubStepPolicy(prev = midSeg, next = backToBeatStart, dragging = true)
        assertTrue(dragBackToStart.inPlace)
        assertFalse(dragBackToStart.sameSegmentInPlace)
        assertTrue(dragBackToStart.sameSlideDrag)
        assertTrue(dragBackToStart.crossedIntraSlideBeat)

        // Dragging across intra-slide beats seeks in place during drag and flags crossedIntraSlideBeat.
        val dragAcrossBeat = scrubStepPolicy(prev = midSeg, next = nextIntraSlideSeg, dragging = true)
        assertTrue(dragAcrossBeat.inPlace)
        assertTrue(dragAcrossBeat.sameSlideDrag)
        assertTrue(dragAcrossBeat.crossedIntraSlideBeat)

        // Non-drag click across beat rebuilds cleanly.
        val clickAcrossBeat = scrubStepPolicy(prev = midSeg, next = nextIntraSlideSeg, dragging = false)
        assertFalse(clickAcrossBeat.inPlace)

        // Crossing into another slide always rebuilds even while dragging.
        val crossSlide = scrubStepPolicy(prev = midSeg, next = nextSlideSeg, dragging = true)
        assertFalse(crossSlide.inPlace)

        // Explicit forceRebuild overrides in-place seeking.
        val forced = scrubStepPolicy(prev = midSeg, next = sameSegLater, dragging = true, forceRebuild = true)
        assertFalse(forced.inPlace)
    }
}
