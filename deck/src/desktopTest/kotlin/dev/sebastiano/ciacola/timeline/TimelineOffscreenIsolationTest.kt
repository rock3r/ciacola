package dev.sebastiano.ciacola.timeline

import androidx.compose.ui.unit.IntSize
import dev.bnorm.storyboard.plus
import dev.sebastiano.ciacola.createFoundationDeck
import dev.sebastiano.ciacola.review.PendingReviewSelection
import dev.sebastiano.ciacola.review.ReviewStageCoordinator
import dev.sebastiano.ciacola.review.ReviewTargetHit
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The timeline indexes and measures the deck in offscreen scenes whenever overrides change (every
 * Inspector tweak). Those scenes render the deck's stage decorators too, and must never touch the
 * live studio: an open review composer stays open and no offscreen element registers as a target.
 */
class TimelineOffscreenIsolationTest {

    @Test
    fun indexingTheDeckLeavesLiveReviewAndSelectionStateAlone() {
        val review = ReviewStageCoordinator()
        val selection = TimelineStageSelection()
        val loopClock = DeckLoopClock()
        val stageDecorator = createTimelineStageDecorator(
            loopClock = loopClock,
            motionPathPreview = MotionPathPreviewState(),
            overridesStore = TimelineOverridesStore(Files.createTempDirectory("ciacola-offscreen")),
            stageSelection = selection,
        )
        val deck = createFoundationDeck(
            includeKeynoteEffectsLab = true,
            stageDecorator = stageDecorator + review.stageDecorator,
        )
        val pending = PendingReviewSelection(
            stageX = 10f,
            stageY = 10f,
            selectionBounds = null,
            target = ReviewTargetHit(kind = "Stamp", label = "IMMUTABLE"),
            sceneHeader = "KEYNOTE EFFECTS LAB / PROVISIONAL",
            stageFormatSize = IntSize(1920, 1080),
            capturedBitmap = null,
        )
        review.pendingSelection = pending

        val snapshot = indexDeckTimeline(deck)

        assertTrue(snapshot.totalDurationMs > 0)
        assertNotNull(review.pendingSelection, "An open review composer survives timeline re-indexing")
        assertEquals(pending, review.pendingSelection)
        assertTrue(selection.targets.isEmpty(), "Offscreen elements never register as stage targets")
        assertTrue(loopClock.registeredLoops.isEmpty(), "Offscreen loops never register on the live clock")
    }
}
