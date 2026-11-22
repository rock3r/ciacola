package dev.sebastiano.ciacola.timeline

import dev.sebastiano.ciacola.document.BeatSpec
import dev.sebastiano.ciacola.document.SlideSpec
import dev.sebastiano.ciacola.indexFoundationTimelineSnapshot
import dev.sebastiano.ciacola.loadFoundationTestDocument
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StudioUxImprovementsTest {

    @Test
    fun timelineViewportPreservesVisibleTimeSpanAcrossRetinaAnd1xMonitors() {
        val vp = TimelineViewportState().apply {
            contentDurationMs = 10_000
            updateSurfaceMetrics(TimelineSurface.Docked, widthPx = 2_000f, surfaceDensity = 2f)
            zoomToFit(TimelineSurface.Docked)
        }
        val dpPerMsAt2x = vp.dpPerMs
        val spanStartAt2x = vp.timeForX(0f, TimelineSurface.Docked)
        val spanEndAt2x = vp.timeForX(2_000f, TimelineSurface.Docked)

        vp.setZoomDpPerMs(0.5f, anchorX = 800f, surface = TimelineSurface.Docked)
        vp.scrollHorizontallyBy(600f, TimelineSurface.Docked)
        val zoomedStartAt2x = vp.timeForX(0f, TimelineSurface.Docked)
        val zoomedEndAt2x = vp.timeForX(2_000f, TimelineSurface.Docked)

        vp.updateSurfaceMetrics(TimelineSurface.Docked, widthPx = 1_000f, surfaceDensity = 1f)
        assertEquals(0.5f, vp.dpPerMs, absoluteTolerance = 1e-4f, "dpPerMs must stay density-independent")
        assertEquals(
            zoomedStartAt2x,
            vp.timeForX(0f, TimelineSurface.Docked),
            absoluteTolerance = 1f,
            message = "Visible start time must be preserved across 2x -> 1x monitor move",
        )
        assertEquals(
            zoomedEndAt2x,
            vp.timeForX(1_000f, TimelineSurface.Docked),
            absoluteTolerance = 1f,
            message = "Visible end time must be preserved across 2x -> 1x monitor move",
        )

        vp.zoomToFit(TimelineSurface.Docked)
        assertTrue(vp.isFitLocked)
        assertEquals(dpPerMsAt2x, vp.dpPerMs, absoluteTolerance = 1e-4f)
        assertEquals(spanStartAt2x, vp.timeForX(0f, TimelineSurface.Docked), absoluteTolerance = 1f)
        assertEquals(spanEndAt2x, vp.timeForX(1_000f, TimelineSurface.Docked), absoluteTolerance = 1f)

        vp.updateSurfaceMetrics(TimelineSurface.Docked, widthPx = 1_400f, surfaceDensity = 1f)
        vp.applyStickyFitIfNeeded(TimelineSurface.Docked)
        assertEquals(vp.fitDpPerMs(TimelineSurface.Docked), vp.dpPerMs, absoluteTolerance = 1e-4f)
        assertTrue(vp.xForTime(10_000f, TimelineSurface.Docked) <= 1_400f)

        vp.zoomBy(0.01f, anchorX = 200f, surface = TimelineSurface.Docked)
        assertEquals(vp.fitDpPerMs(TimelineSurface.Docked), vp.dpPerMs, absoluteTolerance = 1e-4f)
        assertFalse(vp.isFitLocked, "Manual zoom clears sticky fit lock")
    }

    @Test
    fun dockedAndDetachedSurfacesIsolateViewportWidthsSoDockedScrollingNeverLocks() {
        val vp = TimelineViewportState().apply {
            contentDurationMs = 12_000
            updateSurfaceMetrics(TimelineSurface.Docked, widthPx = 700f, surfaceDensity = 1f)
            updateSurfaceMetrics(TimelineSurface.Detached, widthPx = 1_200f, surfaceDensity = 1f)
        }
        vp.zoomToFit(TimelineSurface.Detached)
        assertEquals(0f, vp.maxScrollX(TimelineSurface.Detached), absoluteTolerance = 0.5f)
        assertTrue(
            vp.maxScrollX(TimelineSurface.Docked) > 400f,
            "Docked timeline must retain positive scroll range when wide Detached window is open",
        )
        val consumed = vp.scrollHorizontallyBy(250f, TimelineSurface.Docked)
        assertEquals(250f, consumed, absoluteTolerance = 0.5f)
        assertEquals(250f, vp.scrollX(TimelineSurface.Docked), absoluteTolerance = 0.5f)
        assertEquals(0f, vp.scrollX(TimelineSurface.Detached), absoluteTolerance = 0.5f)
    }

    @Test
    fun rulerTicksAlwaysUseDotDecimalAndSlideRibbonUsesDocumentTitles() {
        val originalLocale = Locale.getDefault()
        val snapshot = indexFoundationTimelineSnapshot()
        try {
            Locale.setDefault(Locale.ITALY)
            assertEquals("1500ms", formatMasterTimelineTick(1_500, 500))
            assertEquals("1:30", formatMasterTimelineTick(90_000, 30_000))
            val badge = formatTimelineLocationBadge(
                snapshot = snapshot,
                timeMs = 1_500,
                activeSubLoopId = null,
                fallbackIndex = dev.bnorm.storyboard.Storyboard.Index(0, 0),
                sceneBeatsCountProvider = { s -> snapshot.slides[s].beatCount },
            )
            assertTrue(badge.startsWith("01.500s"), "Expected US dot decimal under Locale.ITALY, got $badge")
        } finally {
            Locale.setDefault(originalLocale)
        }
        assertTrue(snapshot.slides[0].label.contains("Beats"))
        assertTrue(snapshot.slides[1].label.contains("Shared Move"))
        assertTrue(snapshot.slides[2].label.contains("Ambient Loop"))
        assertTrue(snapshot.slides[3].label.contains("Highlighted Code"))
        assertTrue(snapshot.slides[4].label.contains("Keynote Effects Lab"))

        val customDoc = loadFoundationTestDocument().copy(
            slides = listOf(SlideSpec(id = "custom-1", title = "Custom Intro", beats = listOf(BeatSpec()))),
        )
        val customSnapshot = DocumentTimelinePlannerCache().buildSnapshot(customDoc).snapshot
        assertTrue(customSnapshot.slides[0].label.contains("Custom Intro"), "Got ${customSnapshot.slides[0].label}")
    }
}
