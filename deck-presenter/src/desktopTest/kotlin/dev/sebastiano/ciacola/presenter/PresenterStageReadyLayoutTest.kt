package dev.sebastiano.ciacola.presenter

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PresenterStageReadyLayoutTest {

    @Test
    fun notesPaneTakes40PercentWithNotesAnd28PercentWhenEmpty() {
        assertEquals(0.40f, presenterNotesPaneFraction(hasNotes = true, editing = false))
        assertEquals(0.28f, presenterNotesPaneFraction(hasNotes = false, editing = false))
        assertEquals(
            0.40f,
            presenterNotesPaneFraction(hasNotes = false, editing = true),
            "Editing an empty slide's notes must widen the pane to the with-notes width",
        )
    }

    @Test
    fun currentAndNextPreviewsShareOneWidthThatFitsBothStackedFrames() {
        // Height-limited column: both 16:9 frames plus two headers and the gap must fit.
        val width = presenterPreviewWidth(availableWidth = 900.dp, availableHeight = 600.dp)
        val frameHeight = width / (16f / 9f)
        val stacked = frameHeight * 2 + PresenterPreviewHeaderHeight * 2 + PresenterPreviewGap
        assertTrue(stacked.value <= 600f + 0.5f, "Stacked previews ($stacked) must fit the column height")
        assertTrue(stacked.value >= 599f, "Previews must use the full height when height-limited ($stacked)")

        // Width-limited column: previews span the full column width.
        val narrow = presenterPreviewWidth(availableWidth = 320.dp, availableHeight = 900.dp)
        assertEquals(320f, narrow.value, 0.01f)

        // Degenerate sizes never collapse below 1dp.
        assertTrue(presenterPreviewWidth(availableWidth = 0.dp, availableHeight = 0.dp).value >= 1f)
    }

    @Test
    fun previewLayoutGoesSideBySideOnlyWhenItEnlargesCurrentAndHugsItsContent() {
        // Tall column: stacking gives Current the widest frame.
        val tall = presenterPreviewLayout(availableWidth = 700.dp, availableHeight = 860.dp)
        assertTrue(!tall.sideBySide)
        assertEquals(tall.currentWidth.value, tall.nextWidth.value, 0.01f)
        assertEquals(tall.currentWidth.value, tall.contentWidth.value, 0.01f)

        // Wide, short column: side by side (Current 60%, Next 40%) beats two stacked frames.
        val wide = presenterPreviewLayout(availableWidth = 1400.dp, availableHeight = 520.dp)
        assertTrue(wide.sideBySide)
        assertTrue(wide.currentWidth > wide.nextWidth)
        assertTrue(wide.currentWidth > presenterPreviewWidth(1400.dp, 520.dp))
        assertTrue(wide.contentWidth.value <= 1400f + 0.5f, "Side-by-side previews must fit the column")
        val frameHeight = wide.currentWidth / (16f / 9f)
        assertTrue((frameHeight + PresenterPreviewHeaderHeight).value <= 520f + 0.5f)
    }

    @Test
    fun timerFormattingUsesTwoDigitMinutesAndSeconds() {
        assertEquals("00:00", PresenterTimerState.formatMmSs(0))
        assertEquals("12:03", PresenterTimerState.formatMmSs(723))
        assertEquals("05:00 left", PresenterTimerState.formatRemaining(elapsedSeconds = 0, targetMinutes = 5))
        assertEquals("+00:30 over", PresenterTimerState.formatRemaining(elapsedSeconds = 330, targetMinutes = 5))
    }

    @Test
    fun slideTitlesAreHumanizedForPresenterHeaders() {
        assertEquals("Slide 3", PresenterController.humanizePresenterSlideTitle("", 2))
        assertEquals("Intro", PresenterController.humanizePresenterSlideTitle("Slide 1 · Intro", 0))
        assertEquals("Trippy Kaleidoscope", PresenterController.humanizePresenterSlideTitle("shaders.trippyKaleidoscope", 4))
    }
}
