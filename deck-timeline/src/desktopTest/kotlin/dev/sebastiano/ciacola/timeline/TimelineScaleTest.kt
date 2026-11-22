package dev.sebastiano.ciacola.timeline

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TimelineScaleTest {

    private fun syntheticLargeSnapshot(
        slideCount: Int = 136,
        totalTracks: Int = 700,
        slideDurationMs: Int = 6_200,
    ): DeckTimelineSnapshot {
        val totalDurationMs = slideCount * slideDurationMs
        val slides = List(slideCount) { s ->
            val start = s * slideDurationMs
            SlideSectionBar(
                sceneIndex = s,
                label = "Slide ${s + 1}",
                startMs = start,
                endMs = start + slideDurationMs,
                overlapInMs = 0,
                overlapOutMs = 0,
                beatCount = 2,
            )
        }
        val beats = List(slideCount) { s ->
            EventBeatLine(
                beatGlobalIndex = s,
                stateIndex = s,
                sceneIndex = s,
                frameIndex = 0,
                timeMs = s * slideDurationMs,
                segmentDurationMs = slideDurationMs,
                isSlideBoundary = s > 0,
                isInjected = false,
            )
        }
        val spans = List(slideCount) { s ->
            StateTimeSpan(
                stateIndex = s,
                sceneIndex = s,
                frameIndex = 0,
                startMs = s * slideDurationMs,
                durationMs = slideDurationMs,
                isBoundary = false,
                isHold = true,
            )
        }
        val tracks = List(totalTracks) { t ->
            val sceneIdx = t % slideCount
            val start = sceneIdx * slideDurationMs + ((t / slideCount) * 400)
            TimelineTrackRow(
                trackId = "track-$t",
                label = "Slide ${sceneIdx + 1} · Track $t",
                category = TrackCategory.Build,
                sceneIndex = sceneIdx,
                blocks = listOf(
                    TimelineBlockBar(
                        id = "blk-$t",
                        trackId = "track-$t",
                        label = "Track $t",
                        category = TrackCategory.Build,
                        sceneIndex = sceneIdx,
                        triggerBeatInScene = 0,
                        startMs = start,
                        durationMs = 500,
                    ),
                ),
            )
        }
        return DeckTimelineSnapshot(
            totalDurationMs = totalDurationMs,
            slides = slides,
            eventBeats = beats,
            tracks = tracks,
            stateSpans = spans,
        )
    }

    @Test
    fun zoomToFitFits500sAnd840sDecksOnDockedAndDetachedViewports() {
        assertEquals(0.0005f, MIN_DP_PER_MS, "MIN_DP_PER_MS must be lowered to 0.0005f for 136-slide scale")

        for (durationMs in listOf(430_000, 500_000, 840_000)) {
            for (surface in listOf(TimelineSurface.Docked, TimelineSurface.Detached)) {
                for (density in listOf(1f, 2f)) {
                    val vp = TimelineViewportState()
                    vp.updateSurfaceMetrics(surface, widthPx = 1_000f * density, surfaceDensity = density)
                    vp.markSurfaceActive(surface)
                    vp.contentDurationMs = durationMs
                    vp.zoomToFit(surface)

                    assertTrue(vp.dpPerMs >= MIN_DP_PER_MS, "dpPerMs=${vp.dpPerMs} must be >= $MIN_DP_PER_MS")
                    assertTrue(
                        vp.contentWidthDp <= vp.viewportWidthDp(surface) + 0.5f,
                        "duration=${durationMs}ms surface=$surface density=$density must fit within 1000dp viewport " +
                            "(contentWidthDp=${vp.contentWidthDp})",
                    )
                    assertEquals(0f, vp.maxScrollDp(surface), 0.5f, "Fit zoom must require zero horizontal scroll")
                    assertTrue(
                        vp.xForTime(durationMs.toFloat(), surface) <= vp.viewportWidthPx(surface),
                        "End of ${durationMs}ms deck must be visible in viewport",
                    )
                    assertTrue(vp.isNearFit(surface))
                }
            }
        }
    }

    @Test
    fun masterRulerTickStepsAreNonOverlappingAcrossZoomLevels() {
        val zoomLevelsDpPerMs = listOf(
            MIN_DP_PER_MS,
            0.001f,
            0.00184f,
            0.00214f,
            0.004f,
            0.01f,
            0.03f,
            0.12f,
            FRAME_LEVEL_DP_PER_MS,
            MAX_DP_PER_MS,
        )
        for (dpPerMs in zoomLevelsDpPerMs) {
            val majorStep = chooseMasterRulerTickStepMs(dpPerMs)
            val minorStep = chooseMasterRulerMinorStepMs(majorStep)
            val spacingDp = majorStep * dpPerMs
            assertTrue(
                spacingDp >= 84f,
                "At dpPerMs=$dpPerMs, majorStep=${majorStep}ms gives spacing=${spacingDp}dp (< 84dp)",
            )
            assertTrue(minorStep > 0 && majorStep % minorStep == 0, "minorStep=$minorStep must evenly divide majorStep=$majorStep")

            val label0 = formatMasterTimelineTick(0, majorStep)
            val label1 = formatMasterTimelineTick(majorStep, majorStep)
            val label2 = formatMasterTimelineTick(majorStep * 2, majorStep)
            assertTrue(label0.isNotBlank() && label1.isNotBlank() && label2.isNotBlank())
            assertTrue(label1 != label2, "Consecutive major ticks must have distinct labels ($label1 vs $label2)")
        }

        assertEquals("10s", formatMasterTimelineTick(10_000, 10_000))
        assertEquals("30s", formatMasterTimelineTick(30_000, 30_000))
        assertEquals("1:00", formatMasterTimelineTick(60_000, 60_000))
        assertEquals("1:30", formatMasterTimelineTick(90_000, 30_000))
        assertEquals("2:00", formatMasterTimelineTick(120_000, 120_000))
        assertEquals("5:00", formatMasterTimelineTick(300_000, 300_000))
    }

    @Test
    fun minimapSlideIndexMappingAndVirtualizedTrackRangeScaleCleanly() {
        val snapshot = syntheticLargeSnapshot(slideCount = 136, totalTracks = 136)
        assertEquals(0, minimapSlideIndexForX(0f, 1_000f, snapshot.slides, snapshot.totalDurationMs))
        assertEquals(135, minimapSlideIndexForX(1_000f, 1_000f, snapshot.slides, snapshot.totalDurationMs))

        var prev = -1
        for (i in 0..200) {
            val x = i * 5f
            val idx = minimapSlideIndexForX(x, 1_000f, snapshot.slides, snapshot.totalDurationMs)
            assertTrue(idx in 0..135, "Slide index out of range: $idx")
            assertTrue(idx >= prev, "Minimap mapping must be monotonically non-decreasing ($idx < $prev at x=$x)")
            prev = idx
        }

        val midRange = computeVisibleTrackRowRange(
            totalRows = 700,
            scrollYPx = 350f * 30f,
            viewportHeightPx = 300f,
            rowHeightPx = 30f,
        )
        assertTrue(midRange.count() <= 30, "Mid-scroll visible+overscan window should be compact, got ${midRange.count()}")
        assertTrue(350 in midRange)
    }
}
