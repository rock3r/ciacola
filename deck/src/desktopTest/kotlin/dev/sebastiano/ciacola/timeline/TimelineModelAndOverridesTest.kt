package dev.sebastiano.ciacola.timeline

import androidx.compose.ui.geometry.Rect
import dev.sebastiano.ciacola.createFoundationDeck
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class TimelineModelAndOverridesTest {

    @Test
    fun indexDeckTimelineProducesContiguousOverlappingSlidesEventBeatLinesStaggersSharedSpansAndLoops() {
        val tempDir = Files.createTempDirectory("ciacola-timeline-idx")
        val store = TimelineOverridesStore(tempDir)
        val deck = createFoundationDeck(includeKeynoteEffectsLab = true)

        val snapshot = indexDeckTimeline(deck, store.data())
        assertEquals(5, snapshot.slides.size, "Expected 5 contiguous slide sections")

        // Adjacent slides must overlap contiguously across their slide transition!
        for (i in 0 until snapshot.slides.lastIndex) {
            val current = snapshot.slides[i]
            val next = snapshot.slides[i + 1]
            assertTrue(current.overlapOutMs > 0, "Expected positive overlap leaving slide $i")
            assertEquals(
                current.endMs - current.overlapOutMs,
                next.startMs,
                "Slide ${i + 1} must start where Slide $i's exit transition begins",
            )
        }

        // Event beat lines must exist for every frame and map round-trip to (stateIndex, fraction)
        assertTrue(snapshot.eventBeats.size >= 14)
        val midBeat = snapshot.eventBeats[4]
        val midTime = midBeat.timeMs + midBeat.segmentDurationMs / 2
        val (stateIdx, frac) = snapshot.stateFractionForTimeMs(midTime)
        assertEquals(midBeat.stateIndex, stateIdx)
        assertEquals(0.5f, frac, absoluteTolerance = 0.05f)
        assertEquals(midTime, snapshot.timeMsForStateFraction(stateIdx, frac), "Time round-trip mismatch")

        // Categories harvested across the whole deck (even scenes not currently mounted!)
        val categories = snapshot.tracks.map { it.category }.toSet()
        assertTrue(TrackCategory.SharedElement in categories, "Missing SharedElement track")
        assertTrue(TrackCategory.MagicText in categories, "Missing MagicText track")
        assertTrue(TrackCategory.WarpCode in categories, "Missing WarpCode track")
        assertTrue(TrackCategory.StaggerGroup in categories, "Missing StaggerGroup track")
        assertTrue(TrackCategory.StaggerItem in categories, "Missing StaggerItem staircase tracks")
        assertTrue(TrackCategory.LineDraw in categories, "Missing LineDraw tracks")
        assertTrue(TrackCategory.LoopSubComp in categories, "Missing LoopSubComp track")
    }

    @Test
    fun timelineOverridesStorePersistsZeroLatencyBeatInsertionsTrackDragsCurvesAndArcMotionPaths() {
        val tempDir = Files.createTempDirectory("ciacola-timeline-ovr")
        val store = TimelineOverridesStore(tempDir)

        store.insertBeatAfter(sceneIndex = 4, beatIndex = 1)
        assertEquals(1, store.structuralRevision)

        store.updateTrack("lab-immutable-stamp") { prev ->
            prev.copy(
                enterBeat = 3,
                delayMs = 80,
                durationMs = 520,
                curve = TimelineCurveSpec(preset = "Custom", x1 = 0.1f, y1 = 0.9f, x2 = 0.2f, y2 = 1.0f),
                spatial = TimelineSpatialSpec(offsetXDp = -16f, offsetYDp = 40f, arcBend = 0.65f),
            )
        }
        assertEquals(1, store.parameterRevision)

        // Reload in a fresh store instance to prove atomic JSON persistence
        val reloaded = TimelineOverridesStore(tempDir)
        assertEquals(listOf(1), reloaded.data().sceneBeats[4]?.injectedAfterBeats)
        assertEquals(520, reloaded.data().tracks["lab-immutable-stamp"]?.durationMs)
        assertEquals(0.65f, reloaded.data().tracks["lab-immutable-stamp"]?.spatial?.arcBend)

        // Verify curved spatial arc interpolation bends away from straight linear midpoint
        val start = Rect(100f, 100f, 200f, 200f)
        val end = Rect(700f, 100f, 800f, 200f)
        val linearMid = computeArcInterpolatedRect(start, end, fraction = 0.5f, arcBend = 0f)
        val curvedMid = computeArcInterpolatedRect(start, end, fraction = 0.5f, arcBend = 0.6f)
        assertEquals(450f, linearMid.center.x, absoluteTolerance = 1f)
        assertEquals(150f, linearMid.center.y, absoluteTolerance = 1f)
        assertNotEquals(linearMid.center.y, curvedMid.center.y, "Arc bend must curve Y midpoint off the line")
    }
}
