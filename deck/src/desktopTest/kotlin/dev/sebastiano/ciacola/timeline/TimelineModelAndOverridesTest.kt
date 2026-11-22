package dev.sebastiano.ciacola.timeline

import dev.sebastiano.ciacola.createLoadedTestDeckStore
import dev.sebastiano.ciacola.decks.AllDocumentDecks
import dev.sebastiano.ciacola.document.Curve
import dev.sebastiano.ciacola.indexFoundationDocumentTimeline
import dev.sebastiano.ciacola.loadFoundationTestDocument
import dev.sebastiano.ciacola.review.ReviewCommentStore
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TimelineModelAndOverridesTest {

    @Test
    fun analyticalTimelineProducesContiguousOverlappingSlidesEventBeatLinesAndCategories() {
        val doc = loadFoundationTestDocument()
        val docTimeline = indexFoundationDocumentTimeline(doc)
        val snapshot = docTimeline.snapshot

        assertEquals(5, snapshot.slides.size, "Expected 5 contiguous slide sections")

        // Adjacent slides overlap contiguously across their slide transition!
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

        // One click beat per resting state (14 beats across 5 slides).
        val restingStates = doc.slides.sumOf { it.beats.size }
        assertEquals(restingStates, snapshot.eventBeats.size)

        val midSpan = snapshot.stateSpans.first { !it.isHold && !it.isBoundary && it.durationMs > 0 }
        val midBeat = snapshot.eventBeats.first { it.stateIndex == midSpan.stateIndex }
        val hold = snapshot.stateSpans.first { it.isHold && it.stateIndex == midBeat.stateIndex }
        val midTime = midSpan.startMs + midSpan.durationMs / 2
        val (stateIdx, frac) = snapshot.stateFractionForTimeMs(midTime)
        assertEquals(midBeat.stateIndex, stateIdx)
        assertEquals(0.5f, frac, absoluteTolerance = 0.05f)
        assertEquals(midTime, snapshot.timeMsForStateFraction(stateIdx, frac), "Time round-trip mismatch")

        assertEquals(midBeat.stateIndex to 0f, snapshot.stateFractionForTimeMs(hold.startMs + hold.durationMs / 2))
        assertEquals(midBeat.timeMs, snapshot.timeMsForStateFraction(midBeat.stateIndex, 0f))
        assertEquals(hold.startMs, midBeat.timeMs)

        val categories = snapshot.tracks.map { it.category }.toSet()
        assertTrue(TrackCategory.SharedElement in categories, "Missing SharedElement track")
        assertTrue(TrackCategory.MagicText in categories, "Missing MagicText track")
        assertTrue(TrackCategory.Build in categories || TrackCategory.LineDraw in categories, "Missing Build/LineDraw track")
        assertTrue(TrackCategory.LoopSubComp in categories, "Missing LoopSubComp track")
    }

    @Test
    fun documentTimelineControllerCommitsBeatInsertionsTrackTimingCurvesAndLoopPeriodsWithUndoRedo() {
        val store = createLoadedTestDeckStore()
        val controller = DocumentTimelineController(
            store = store,
            specimens = AllDocumentDecks.foundation.specimens,
        )

        val initialBeatsSlide0 = store.document.slides[0].beats.size
        assertTrue(controller.insertBeat("beats", 1))
        assertEquals(initialBeatsSlide0 + 1, store.document.slides[0].beats.size)

        val buildRef = controller.snapshot.trackRefsByTrackId.values
            .filterIsInstance<DocumentTimelineTrackRef.SlideBuildTrack>()
            .firstOrNull()
        assertNotNull(buildRef)
        controller.selectTrack(buildRef.trackId)
        controller.resizeTrackBarDuration(buildRef, 620)
        assertTrue(controller.updateTrackCurve(buildRef, Curve.Bezier(0.1, 0.9, 0.2, 1.0)))

        val loopRef = controller.snapshot.trackRefsByTrackId.values
            .filterIsInstance<DocumentTimelineTrackRef.LoopTrackBar>()
            .firstOrNull()
        assertNotNull(loopRef)
        val initialLoopPeriod = store.document.loopPeriodsMs[loopRef.loopId]
        controller.selectLoop(loopRef.loopId)
        assertTrue(controller.setLoopPeriodMs(loopRef.loopId, 7_500L))
        assertEquals(7_500L, store.document.loopPeriodsMs[loopRef.loopId])

        // Undo loop period, curve, duration, and beat insertion cleanly!
        store.undo()
        assertEquals(initialLoopPeriod, store.document.loopPeriodsMs[loopRef.loopId])
        store.undo()
        store.undo()
        store.undo()
        assertEquals(initialBeatsSlide0, store.document.slides[0].beats.size)
        assertFalse(store.canUndo)

        store.redo()
        assertEquals(initialBeatsSlide0 + 1, store.document.slides[0].beats.size)
    }

    @Test
    fun reviewCommentStoreBacksUpCorruptJson() {
        val reviewDir = Files.createTempDirectory("ciacola-corrupt-review")
        try {
            val commentsFile = reviewDir.resolve("review-comments.json")
            Files.writeString(commentsFile, "[ broken review json")
            ReviewCommentStore(reviewDir, reviewDir)
            val backupCommentsFile = reviewDir.resolve("review-comments.corrupt.json")
            assertTrue(
                Files.exists(backupCommentsFile),
                "Corrupt review-comments.json must be backed up to review-comments.corrupt.json",
            )
            assertEquals("[ broken review json", Files.readString(backupCommentsFile))
        } finally {
            reviewDir.toFile().deleteRecursively()
        }
    }
}
