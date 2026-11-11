package dev.sebastiano.ciacola.timeline

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.LayoutCoordinates
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TimelineViewportAndSelectionTest {

    private fun viewport(widthPx: Float = 1_000f, durationMs: Int = 10_000) = TimelineViewportState().apply {
        viewportWidthPx = widthPx
        contentDurationMs = durationMs
    }

    @Test
    fun altWheelZoomKeepsTheTimeUnderTheCursorFixed() {
        val vp = viewport()
        vp.setZoom(0.5f, anchorX = 0f)
        vp.scrollHorizontallyTo(1_200f)
        val anchorX = 420f
        val timeUnderCursor = vp.timeForX(anchorX)

        vp.zoomBy(1.6f, anchorX)
        assertEquals(timeUnderCursor, vp.timeForX(anchorX), absoluteTolerance = 0.5f)
        vp.zoomBy(1f / 2.3f, anchorX)
        assertEquals(timeUnderCursor, vp.timeForX(anchorX), absoluteTolerance = 0.5f)
    }

    @Test
    fun zoomIsClampedAndScrollStaysInsideTheContent() {
        val vp = viewport()
        repeat(40) { vp.zoomBy(2f, 500f) }
        assertEquals(MAX_PX_PER_MS, vp.pxPerMs)
        assertTrue(vp.scrollX in 0f..vp.maxScrollX)
        repeat(40) { vp.zoomBy(0.5f, 500f) }
        assertTrue(vp.pxPerMs >= MIN_PX_PER_MS)
        assertEquals(0f, vp.scrollX)
        assertEquals(0f, vp.scrollHorizontallyBy(-50f), "Cannot scroll before time 0")
    }

    @Test
    fun semicolonTogglesBetweenFitAndFrameLevelAroundThePlayhead() {
        val vp = viewport()
        vp.zoomToFit()
        assertTrue(vp.isNearFit)
        assertTrue(vp.xForTime(10_000f) <= vp.viewportWidthPx, "Fit shows the whole composition")

        vp.toggleFitAndFrames(anchorTimeMs = 6_000f)
        assertEquals(FRAME_LEVEL_PX_PER_MS, vp.pxPerMs)
        val playheadX = vp.xForTime(6_000f)
        assertTrue(playheadX in 0f..vp.viewportWidthPx, "Playhead stays visible at frame level (x=$playheadX)")

        vp.toggleFitAndFrames(anchorTimeMs = 6_000f)
        assertTrue(vp.isNearFit)
        assertEquals(0f, vp.scrollX)
    }

    @Test
    fun ensureTimeVisibleScrollsOnlyWhenTheTimeIsOffscreen() {
        val vp = viewport()
        vp.setZoom(0.5f, 0f)
        vp.ensureTimeVisible(500f)
        assertEquals(0f, vp.scrollX, "Already visible")
        vp.ensureTimeVisible(9_000f)
        val x = vp.xForTime(9_000f)
        assertTrue(x in 0f..vp.viewportWidthPx, "Revealed (x=$x)")
    }

    @Test
    fun previewWheelZoomKeepsTheContentPointUnderTheCursorFixed() {
        val preview = StagePreviewViewportState()
        val anchor = Offset(300f, 200f)
        fun contentPointAt(screen: Offset) = (screen - preview.offset) / preview.scale
        val before = contentPointAt(anchor)

        preview.zoomBy(1.25f, anchor)
        preview.zoomBy(1.25f, anchor)
        val after = contentPointAt(anchor)
        assertEquals(before.x, after.x, absoluteTolerance = 0.01f)
        assertEquals(before.y, after.y, absoluteTolerance = 0.01f)

        preview.panBy(Offset(10f, -5f))
        assertTrue(!preview.isIdentity)
        preview.reset()
        assertTrue(preview.isIdentity)

        repeat(30) { preview.zoomBy(2f, anchor) }
        assertEquals(StagePreviewViewportState.MAX_PREVIEW_SCALE, preview.scale)
    }

    @Test
    fun selectingAGroupHighlightsEveryItemAndPickingPrefersTheItem() {
        val selection = TimelineStageSelection()
        val coords = fakeCoordinates()
        val group = TimelineTargetEntry(setOf("lab-leader-stagger"), coords)
        val item0 = TimelineTargetEntry(setOf("lab-leader-stagger", "lab-leader-stagger#item0"), coords)
        val item1 = TimelineTargetEntry(setOf("lab-leader-stagger", "lab-leader-stagger#item1"), coords)
        selection.targets["g"] = group
        selection.targets["0"] = item0
        selection.targets["1"] = item1

        assertEquals(3, selection.entriesFor("lab-leader-stagger").size)
        assertEquals(listOf(item1), selection.entriesFor("lab-leader-stagger#item1"))
        assertEquals("lab-leader-stagger#item0", selection.mostSpecificTrackId(item0))
        assertEquals("lab-leader-stagger", selection.mostSpecificTrackId(group))
    }

    private fun fakeCoordinates(): LayoutCoordinates =
        java.lang.reflect.Proxy.newProxyInstance(
            LayoutCoordinates::class.java.classLoader,
            arrayOf(LayoutCoordinates::class.java),
        ) { _, _, _ -> null } as LayoutCoordinates
}
