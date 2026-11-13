package dev.sebastiano.ciacola.timeline

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.bnorm.storyboard.Storyboard
import dev.bnorm.storyboard.easel.rememberAnimatic
import dev.sebastiano.ciacola.createFoundationDeck
import java.nio.file.Files
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
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

        // Zoom in to 0.5 dp/ms and scroll horizontally.
        vp.setZoomDpPerMs(0.5f, anchorX = 800f, surface = TimelineSurface.Docked)
        vp.scrollHorizontallyBy(600f, TimelineSurface.Docked)
        val zoomedStartAt2x = vp.timeForX(0f, TimelineSurface.Docked)
        val zoomedEndAt2x = vp.timeForX(2_000f, TimelineSurface.Docked)

        // Move window from 2x Retina (2000px = 1000dp) to 1x external monitor (1000px = 1000dp).
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

        // Also check sticky fit re-computes when zoomed to fit across window resizes.
        vp.zoomToFit(TimelineSurface.Docked)
        assertTrue(vp.isFitLocked)
        assertEquals(dpPerMsAt2x, vp.dpPerMs, absoluteTolerance = 1e-4f)
        assertEquals(spanStartAt2x, vp.timeForX(0f, TimelineSurface.Docked), absoluteTolerance = 1f)
        assertEquals(spanEndAt2x, vp.timeForX(1_000f, TimelineSurface.Docked), absoluteTolerance = 1f)

        // Resizing while fit-locked re-fits automatically until the user manually zooms.
        vp.updateSurfaceMetrics(TimelineSurface.Docked, widthPx = 1_400f, surfaceDensity = 1f)
        vp.applyStickyFitIfNeeded(TimelineSurface.Docked)
        assertEquals(vp.fitDpPerMs(TimelineSurface.Docked), vp.dpPerMs, absoluteTolerance = 1e-4f)
        assertTrue(vp.xForTime(10_000f, TimelineSurface.Docked) <= 1_400f)

        // Zooming out below fit is clamped to fit so the deck never shrinks to an unusable sliver.
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
        // Fit to the wider Detached window -> Detached has maxScroll == 0, Docked must still scroll!
        vp.zoomToFit(TimelineSurface.Detached)
        assertEquals(0f, vp.maxScrollX(TimelineSurface.Detached), absoluteTolerance = 0.5f)
        assertTrue(
            vp.maxScrollX(TimelineSurface.Docked) > 400f,
            "Docked timeline must retain positive scroll range when wide Detached window is open (got ${vp.maxScrollX(TimelineSurface.Docked)})",
        )
        val consumed = vp.scrollHorizontallyBy(250f, TimelineSurface.Docked)
        assertEquals(250f, consumed, absoluteTolerance = 0.5f)
        assertEquals(250f, vp.scrollX(TimelineSurface.Docked), absoluteTolerance = 0.5f)
        assertEquals(0f, vp.scrollX(TimelineSurface.Detached), absoluteTolerance = 0.5f)
    }

    @Test
    fun loopSubCompOverrideScopesStrictlyToEnteredLoopIdAndBeatStepClamps() = runComposeUiTest {
        val loopClock = DeckLoopClock()
        var loopAValue by mutableLongStateOf(-1L)
        var loopBValue by mutableLongStateOf(-1L)

        mainClock.autoAdvance = false
        setContent {
            CompositionLocalProvider(LocalDeckLoopClock provides loopClock) {
                loopAValue = rememberDeckLoopMillis(
                    loopId = "loop-a",
                    label = "Loop A",
                    periodMillis = 4_000L,
                )
                loopBValue = rememberDeckLoopMillis(
                    loopId = "loop-b",
                    label = "Loop B",
                    periodMillis = 4_000L,
                )
            }
        }
        mainClock.advanceTimeByFrame()
        mainClock.advanceTimeBy(400)
        mainClock.advanceTimeByFrame()
        val loopBFreeBefore = loopBValue

        // Enter sub-comp ONLY for loop-a and scrub it to 1,500 ms.
        runOnIdle {
            loopClock.enterSubComp("loop-a")
            loopClock.scrubbedMillis = 1_500L
        }
        mainClock.advanceTimeByFrame()
        assertEquals(1_500L, loopAValue, "Entered sub-comp loop-a must obey scrubbedMillis")

        // Advance frame clock: loop-b must keep advancing freely, unaffected by loop-a's sub-comp scrub!
        repeat(10) {
            mainClock.advanceTimeBy(32)
            mainClock.advanceTimeByFrame()
        }
        assertEquals(1_500L, loopAValue, "loop-a remains paused at 1500ms while in sub-comp")
        assertTrue(
            loopBValue != 1_500L && loopBValue != loopBFreeBefore,
            "Other ambient loop-b must keep free-running unaffected (before=$loopBFreeBefore, now=$loopBValue)",
        )

        // Sub-comp stepBeat(+1 / -1) must clamp strictly within [0L, periodMs].
        val tempDir = Files.createTempDirectory("ciacola-subcomp-clamp")
        val store = TimelineOverridesStore(tempDir)
        val deck = createFoundationDeck(includeKeynoteEffectsLab = false)
        lateinit var controller: TimelineStudioController
        setContent {
            val animatic = rememberAnimatic(storyboard = { deck })
            controller = rememberTimelineStudioController(
                animatic = animatic,
                overridesStore = store,
                loopClock = loopClock,
            )
            Box(Modifier.size(640.dp, 360.dp))
        }
        waitForIdle()
        runOnIdle {
            loopClock.enterSubComp("loop-a")
            loopClock.scrubbedMillis = 3_800L
            controller.stepBeat(+1)
        }
        assertEquals(4_000L, loopClock.scrubbedMillis, "Next beat in sub-comp must clamp to periodMs (4000)")
        runOnIdle {
            loopClock.scrubbedMillis = 200L
            controller.stepBeat(-1)
        }
        assertEquals(0L, loopClock.scrubbedMillis, "Prev beat in sub-comp must clamp to 0L")
    }

    @Test
    fun computeBlockPreviewRangeStopsAtMinOfSegmentEndAndBlockEndPlus120Ms() {
        val snapshot = DeckTimelineSnapshot(
            totalDurationMs = 2_400,
            slides = emptyList(),
            eventBeats = emptyList(),
            tracks = emptyList(),
            stateSpans = listOf(
                StateTimeSpan(stateIndex = 0, sceneIndex = 0, frameIndex = 0, startMs = 0, durationMs = 400, isBoundary = false, isHold = true),
                StateTimeSpan(stateIndex = 0, sceneIndex = 0, frameIndex = 0, startMs = 400, durationMs = 500, isBoundary = false, isHold = false),
                StateTimeSpan(stateIndex = 1, sceneIndex = 0, frameIndex = 1, startMs = 900, durationMs = 400, isBoundary = false, isHold = true),
                StateTimeSpan(stateIndex = 1, sceneIndex = 0, frameIndex = 1, startMs = 1_300, durationMs = 340, isBoundary = false, isHold = false),
            ),
        )
        // Case 1: block ends at segmentEnd (900) -> must stop at 900, NOT spill into the 400ms click hold (900..1300)!
        val fullSegBlock = TimelineBlockBar(
            id = "b1",
            trackId = "t1",
            label = "Build",
            category = TrackCategory.Build,
            sceneIndex = 0,
            triggerBeatInScene = 1,
            startMs = 400,
            durationMs = 500,
        )
        assertEquals(280..900, computeBlockPreviewRangeMs(snapshot, fullSegBlock))

        // Case 2: short block (400..580) inside 400..900 segment -> stops at min(900, 580 + 120) = 700!
        val shortBlock = fullSegBlock.copy(durationMs = 180)
        assertEquals(280..700, computeBlockPreviewRangeMs(snapshot, shortBlock))
    }

    @Test
    fun rulerTicksAlwaysUseDotDecimalAndSlideRibbonUsesDynamicTitlesAndLightPalette() {
        val originalLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.ITALY)
            assertEquals("1.5s", formatTimelineTick(1_500, 500))
            assertEquals("0.25s", formatTimelineTick(250, 50))
        } finally {
            Locale.setDefault(originalLocale)
        }

        // Light-theme slide ribbon contrast >= 4.5:1 across all slide colors.
        val lightPalette = buildStudioCanvasPaletteForTheme(isDark = false)
        for (slideColor in lightPalette.slideColors) {
            val composited = compositeColorOver(slideColor.copy(alpha = 0.88f), lightPalette.headerBg)
            val ink = barLabelInkForFill(composited)
            val ratio = wcagContrastRatio(ink, composited)
            assertTrue(ratio >= 4.5f, "KeynoteLight slide ribbon contrast must be >= 4.5:1, got $ratio")
        }

        // Dynamic scene titles harvested from ActiveSceneTimelineContext instead of hardcoded indices.
        val deck = createFoundationDeck(includeKeynoteEffectsLab = true)
        val snapshot = indexDeckTimeline(deck)
        assertEquals("S1 · Beats (3)", snapshot.slides[0].label)
        assertEquals("S2 · Shared Move (2)", snapshot.slides[1].label)
        assertEquals("S3 · Ambient Loop (1)", snapshot.slides[2].label)
        assertEquals("S4 · Highlighted Code (3)", snapshot.slides[3].label)
        assertEquals("S5 · Keynote Effects Lab (5)", snapshot.slides[4].label)

        // Custom deck with reordered/custom scene titles should never show hardcoded Foundation titles.
        val customDeck = Storyboard.build(title = "Custom") {
            scene(frames = listOf(0)) {
                LocalTimelineHarvestCollector.current?.recordSceneTitle(0, "Custom Intro")
            }
        }
        val customSnapshot = indexDeckTimeline(customDeck)
        assertEquals("S1 · Custom Intro (1)", customSnapshot.slides[0].label)
    }
}
