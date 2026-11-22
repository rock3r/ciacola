package dev.sebastiano.ciacola.timeline

import dev.sebastiano.ciacola.document.BeatSpec
import dev.sebastiano.ciacola.document.Build
import dev.sebastiano.ciacola.document.DeckDocument
import dev.sebastiano.ciacola.document.FrameSpec
import dev.sebastiano.ciacola.document.NodeSpec
import dev.sebastiano.ciacola.document.RichTextValue
import dev.sebastiano.ciacola.document.SlideSpec
import dev.sebastiano.ciacola.document.TextPayload
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TimelineBeatMarkersTest {

    private fun multiSlideTestDocument(): DeckDocument = DeckDocument(
        id = "beat-marker-fixture",
        title = "Beat Marker Fixture",
        slides = listOf(
            SlideSpec(
                id = "s0",
                beats = listOf(BeatSpec(), BeatSpec()),
                nodes = listOf(
                    NodeSpec(
                        id = "s0-t",
                        frame = FrameSpec(100.0, 100.0, 400.0, 80.0),
                        text = TextPayload(value = RichTextValue.Plain("Slide 0")),
                        builds = listOf(Build.Enter(beat = 1, effect = "dissolve", durationMs = 280)),
                    ),
                ),
            ),
            SlideSpec(
                id = "s1",
                beats = listOf(BeatSpec(), BeatSpec(), BeatSpec()),
                nodes = listOf(
                    NodeSpec(
                        id = "s1-t",
                        frame = FrameSpec(100.0, 100.0, 400.0, 80.0),
                        text = TextPayload(value = RichTextValue.Plain("Slide 1")),
                        builds = listOf(Build.Enter(beat = 1, effect = "dissolve", durationMs = 320)),
                    ),
                ),
            ),
            SlideSpec(
                id = "s2",
                beats = listOf(BeatSpec(), BeatSpec()),
                nodes = listOf(
                    NodeSpec(
                        id = "s2-t",
                        frame = FrameSpec(100.0, 100.0, 400.0, 80.0),
                        text = TextPayload(value = RichTextValue.Plain("Slide 2")),
                        builds = listOf(Build.Enter(beat = 1, effect = "dissolve", durationMs = 240)),
                    ),
                ),
            ),
        ),
    )

    @Test
    fun emitsStartOfHoldBeatMarkersForEveryValueFrameIncludingFinalBeat() {
        val doc = multiSlideTestDocument()
        val snapshot = DocumentTimelinePlannerCache().buildSnapshot(doc).snapshot

        val expectedTotalBeats = doc.slides.sumOf { it.beats.size }
        assertEquals(7, expectedTotalBeats)
        assertEquals(
            expectedTotalBeats,
            snapshot.eventBeats.size,
            "Every value frame including the final beat of the last slide must emit an EventBeatLine",
        )

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
                "stateFractionForTimeMs(beat.timeMs) must resolve back to the same beat state",
            )
            assertEquals(0f, resolvedFrac, 1e-4f)
        }

        assertEquals(0, snapshot.eventBeats.first().segmentDurationMs)
        val finalBeat = snapshot.eventBeats.last()
        assertEquals(2, finalBeat.sceneIndex)
        assertEquals(1, finalBeat.frameIndex)
        assertEquals(240, finalBeat.segmentDurationMs)
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

        val forwardInSeg = scrubStepPolicy(prev = midSeg, next = sameSegLater, dragging = true)
        assertTrue(forwardInSeg.inPlace)
        assertTrue(forwardInSeg.sameSegmentInPlace)
        assertFalse(forwardInSeg.sameSlideDrag)
        assertFalse(forwardInSeg.crossedIntraSlideBeat)
        assertFalse(forwardInSeg.reversedWithinSegment)

        val reverseInSeg = scrubStepPolicy(prev = midSeg, next = sameSegEarlier, dragging = true)
        assertTrue(reverseInSeg.inPlace)
        assertTrue(reverseInSeg.sameSegmentInPlace)
        assertTrue(reverseInSeg.reversedWithinSegment)

        val dragBackToStart = scrubStepPolicy(prev = midSeg, next = backToBeatStart, dragging = true)
        assertTrue(dragBackToStart.inPlace)
        assertFalse(dragBackToStart.sameSegmentInPlace)
        assertTrue(dragBackToStart.sameSlideDrag)
        assertTrue(dragBackToStart.crossedIntraSlideBeat)

        val dragAcrossBeat = scrubStepPolicy(prev = midSeg, next = nextIntraSlideSeg, dragging = true)
        assertTrue(dragAcrossBeat.inPlace)
        assertTrue(dragAcrossBeat.sameSlideDrag)
        assertTrue(dragAcrossBeat.crossedIntraSlideBeat)

        val clickAcrossBeat = scrubStepPolicy(prev = midSeg, next = nextIntraSlideSeg, dragging = false)
        assertFalse(clickAcrossBeat.inPlace)

        val crossSlide = scrubStepPolicy(prev = midSeg, next = nextSlideSeg, dragging = true)
        assertFalse(crossSlide.inPlace)

        val forced = scrubStepPolicy(prev = midSeg, next = sameSegLater, dragging = true, forceRebuild = true)
        assertFalse(forced.inPlace)
    }
}
