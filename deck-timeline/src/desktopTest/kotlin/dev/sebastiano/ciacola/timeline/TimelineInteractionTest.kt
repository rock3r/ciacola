package dev.sebastiano.ciacola.timeline

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Timeline interaction and formatting invariants: stage overlay at rest, header `+` placement, one ruler unit, group labels. */
class TimelineInteractionTest {

    @Test
    fun stageOverlayShowsPillOnlyWhileHoveringAndSkipsTheOutlineForTheStageSelection() {
        val rest = timelineStageOverlayDecision("s1:title", hoveringTrack = false, stageSelectedNodeIds = setOf("s1:title"))
        assertFalse(rest.drawLabelPill, "No floating pill at rest")
        assertFalse(rest.drawOutline, "One outline per selection: the stage chrome already outlines it")
        assertFalse(rest.drawMotionPath, "No motion-path marker at rest")

        val other = timelineStageOverlayDecision("s1:title", hoveringTrack = false, stageSelectedNodeIds = setOf("s1:body"))
        assertTrue(other.drawOutline)
        assertFalse(other.drawLabelPill)

        val hover = timelineStageOverlayDecision("s1:title", hoveringTrack = true, stageSelectedNodeIds = setOf("s1:title"))
        assertTrue(hover.drawLabelPill)
        assertTrue(hover.drawOutline)
        assertTrue(hover.drawMotionPath)

        val dragging = timelineStageOverlayDecision("s1:title", hoveringTrack = false, stageSelectedNodeIds = emptySet(), gestureActive = true)
        assertTrue(dragging.drawMotionPath, "The motion path shows during an active drag")
        assertFalse(dragging.drawLabelPill)
    }

    @Test
    fun addBuildButtonStaysInsideTheActiveSegmentAndHidesWhenItCannotFit() {
        val btn = 16f
        // Wide segment: right-aligned inside it.
        assertEquals(181f, assertNotNull(addBeatButtonLeft(startX = 100f, endX = 200f, widthPx = 1000f, btnPx = btn, minSlackPx = 8f)))
        // 7px segment in `All` scope: no button, never spilling over earlier slides.
        assertNull(addBeatButtonLeft(startX = 500f, endX = 507f, widthPx = 1000f, btnPx = btn, minSlackPx = 8f))
        // Segment scrolled half off the left edge: clamped inside the visible part.
        val clamped = assertNotNull(addBeatButtonLeft(startX = -300f, endX = 40f, widthPx = 1000f, btnPx = btn, minSlackPx = 8f))
        assertTrue(clamped >= 2f && clamped + btn <= 38f, "left=$clamped")
        // Segment entirely off-screen.
        assertNull(addBeatButtonLeft(startX = 1100f, endX = 1300f, widthPx = 1000f, btnPx = btn, minSlackPx = 8f))
    }

    @Test
    fun rulerUsesOneUnitPickedFromTheStepAndVisibleRange() {
        val ticks = listOf(0, 500, 1_000, 1_500, 2_000, 2_500)
        val labels = ticks.map { formatTimelineRulerLabel(it, 500, maxVisibleMs = 2_500) }
        assertEquals(listOf("0.0s", "0.5s", "1.0s", "1.5s", "2.0s", "2.5s"), labels, "Never mix 1500ms with 2.0s")
        assertEquals(TimelineRulerUnit.Millis, timelineRulerUnitFor(250, 1_500))
        assertEquals(listOf("0ms", "250ms", "1500ms"), listOf(0, 250, 1_500).map { formatTimelineRulerLabel(it, 250, maxVisibleMs = 1_750) })
        assertEquals(TimelineRulerUnit.MinSec, timelineRulerUnitFor(15_000, 75_000))
        assertEquals(listOf("0:45", "1:00", "1:15"), listOf(45_000, 60_000, 75_000).map { formatTimelineRulerLabel(it, 15_000, maxVisibleMs = 75_000) })
        assertEquals("10s", formatTimelineRulerLabel(10_000, 10_000, maxVisibleMs = 50_000))
    }

    @Test
    fun allScopeGroupHeadersAndExcerptsReadAsPlainLanguage() {
        assertEquals("Slide 3", timelineSlideGroupLabel(null, 2))
        assertEquals("Web", TimelineDisplayNames.stripMarkdownPunctuation("**Web**"))
        assertEquals("", TimelineDisplayNames.stripMarkdownPunctuation("**"))
        assertEquals("Heading", TimelineDisplayNames.stripMarkdownPunctuation("## Heading"))
        assertEquals("snake_case stays", TimelineDisplayNames.stripMarkdownPunctuation("snake_case stays"))
    }
}
