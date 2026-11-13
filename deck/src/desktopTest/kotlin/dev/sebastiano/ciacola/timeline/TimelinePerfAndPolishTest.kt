package dev.sebastiano.ciacola.timeline

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.bnorm.storyboard.Storyboard
import dev.bnorm.storyboard.easel.Easel
import dev.bnorm.storyboard.easel.LocalOverviewOpen
import dev.bnorm.storyboard.easel.overview.EaselOverview
import dev.bnorm.storyboard.easel.rememberAnimatic
import dev.sebastiano.ciacola.createFoundationDeck
import dev.sebastiano.ciacola.effects.DottedLeaderRow
import dev.sebastiano.ciacola.theme.DeckThemePreset
import dev.sebastiano.ciacola.theme.DeckThemes
import dev.sebastiano.ciacola.theme.LocalAmbientLoopsPaused
import dev.sebastiano.ciacola.theme.LocalDeckTheme
import dev.sebastiano.ciacola.theme.contrastRatio
import java.nio.file.Files
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@OptIn(ExperimentalTestApi::class)
class TimelinePerfAndPolishTest {

    @Test
    fun seekingSettledOrSameSegmentNeverBumpsScrubEpoch() = runComposeUiTest {
        val tempDir = Files.createTempDirectory("ciacola-scrub-epoch")
        val store = TimelineOverridesStore(tempDir)
        val deck = createFoundationDeck(includeKeynoteEffectsLab = false)
        lateinit var controller: TimelineStudioController

        setContent {
            val loopClock = remember { DeckLoopClock() }
            val animatic = rememberAnimatic(storyboard = { deck })
            controller = rememberTimelineStudioController(
                animatic = animatic,
                overridesStore = store,
                loopClock = loopClock,
            )
            Box(Modifier.size(480.dp, 270.dp)) {
                Easel(animatic)
            }
        }
        waitForIdle()
        runOnIdle { controller.refreshSnapshot(deck) }
        waitForIdle()

        val initialEpoch = controller.animatic.scrubEpoch
        val firstAnimated = controller.snapshot.stateSpans.first { !it.isHold && it.durationMs > 100 }

        // Seeking to already-settled state 0 at fraction 0 must not bump scrubEpoch.
        runOnIdle {
            controller.beginScrubDrag()
            controller.scrubToTimeMs(100)
            controller.endScrubDrag()
        }
        waitForIdle()
        assertEquals(initialEpoch, controller.animatic.scrubEpoch, "Settled seek must not bump scrubEpoch")

        // Dragging inside the same active (from, to) segment + releasing must not force scrubEpoch++.
        runOnIdle { controller.beginScrubDrag() }
        for (frac in listOf(0.15f, 0.35f, 0.55f, 0.72f)) {
            val t = firstAnimated.startMs + (firstAnimated.durationMs * frac).toInt()
            runOnIdle { controller.scrubToTimeMs(t) }
            waitForIdle()
        }
        runOnIdle { controller.endScrubDrag() }
        waitForIdle()
        assertEquals(
            initialEpoch,
            controller.animatic.scrubEpoch,
            "In-segment scrub + endScrubDrag must stay at scrubEpoch=$initialEpoch without rebuilding scene",
        )
    }

    @Test
    fun playbackRunsAt1xWallClockWithoutMidBoundarySnapBack() = runComposeUiTest {
        mainClock.autoAdvance = false
        val tempDir = Files.createTempDirectory("ciacola-playback-1x")
        val store = TimelineOverridesStore(tempDir)
        val deck = createFoundationDeck(includeKeynoteEffectsLab = true)
        lateinit var controller: TimelineStudioController
        lateinit var uiScope: CoroutineScope

        setContent {
            uiScope = rememberCoroutineScope()
            val loopClock = remember { DeckLoopClock() }
            val animatic = rememberAnimatic(storyboard = { deck })
            controller = rememberTimelineStudioController(
                animatic = animatic,
                overridesStore = store,
                loopClock = loopClock,
            )
            Box(Modifier.size(480.dp, 270.dp)) {
                Easel(animatic)
            }
        }
        mainClock.advanceTimeByFrame()
        mainClock.advanceTimeBy(50)
        runOnIdle { controller.refreshSnapshot(deck) }
        mainClock.advanceTimeByFrame()

        runOnIdle { controller.scrubToTimeMs(0) }
        mainClock.advanceTimeByFrame()
        val startMs = controller.currentPlayheadTimeMs
        val observedTimes = mutableListOf<Int>()
        runOnIdle { controller.togglePlayback(uiScope) }
        mainClock.advanceTimeByFrame()

        val steps = 120 // 120 * 16ms = 1920ms across hold + S1:B1->B2 + hold + S1:B2->B3
        repeat(steps) {
            mainClock.advanceTimeBy(16)
            observedTimes += controller.currentPlayheadTimeMs
        }
        runOnIdle { controller.stopPlayback() }

        // 1. Playhead must be strictly non-decreasing across every frame (zero 1-frame snap-back).
        for (i in 1 until observedTimes.size) {
            assertTrue(
                observedTimes[i] >= observedTimes[i - 1],
                "Playhead snapped backward at frame $i: ${observedTimes[i - 1]} -> ${observedTimes[i]} ($observedTimes)",
            )
        }
        // 2. Total playhead progress must track 1.00x wall-clock time (~1920ms) within tight tolerance.
        val elapsedPlayMs = observedTimes.last() - startMs
        val expectedMs = (steps - 1) * 16
        assertTrue(
            abs(elapsedPlayMs - expectedMs) <= 80,
            "Playback must track 1.00x wall-clock time (~${expectedMs}ms), got ${elapsedPlayMs}ms (start=$startMs, end=${observedTimes.last()})",
        )
    }

    @Test
    fun textLayoutCacheReusesMeasuredLayoutsAndBoundsSize() = runComposeUiTest {
        val cache = TimelineTextLayoutCache(maxEntries = 4)
        lateinit var measurer: androidx.compose.ui.text.TextMeasurer
        setContent { measurer = rememberTextMeasurer() }
        waitForIdle()

        val style = TextStyle(fontSize = 10.sp)
        val first = cache.measure(measurer, "Slide 1", style, density = 2f, maxWidthPx = 120)
        val second = cache.measure(measurer, "Slide 1", style, density = 2f, maxWidthPx = 120)
        assertSame(first, second, "Cache must return identical TextLayoutResult on repeat measurement")

        repeat(6) { idx ->
            cache.measure(measurer, "Item $idx", style, density = 2f, maxWidthPx = 120)
        }
        assertEquals(4, cache.size, "LRU cache must stay bounded at maxEntries=4")
    }

    @Test
    fun formatTimelineLocationBadgeReflectsRestingMidBeatAndCrossSlideTransitions() {
        val deck = createFoundationDeck(includeKeynoteEffectsLab = false)
        val snapshot = indexDeckTimeline(deck)
        val firstHold = snapshot.stateSpans.first { it.isHold }
        val firstAnim = snapshot.stateSpans.first { !it.isHold && !it.isBoundary && it.durationMs > 0 }
        val boundaryAnim = snapshot.stateSpans.first { it.isBoundary && it.durationMs > 0 }

        val restBadge = formatTimelineLocationBadge(
            snapshot = snapshot,
            timeMs = firstHold.startMs + 50,
            activeSubLoopId = null,
            fallbackIndex = Storyboard.Index(0, 0),
            sceneBeatsCountProvider = { s -> snapshot.slides[s].beatCount },
        )
        assertTrue(restBadge.endsWith("S1:B1/${snapshot.slides[0].beatCount}"), "Got $restBadge")

        val midBadge = formatTimelineLocationBadge(
            snapshot = snapshot,
            timeMs = firstAnim.startMs + firstAnim.durationMs / 2,
            activeSubLoopId = null,
            fallbackIndex = Storyboard.Index(0, 0),
            sceneBeatsCountProvider = { s -> snapshot.slides[s].beatCount },
        )
        assertTrue(midBadge.endsWith("S1:B1 → B2"), "Got $midBadge")

        val crossBadge = formatTimelineLocationBadge(
            snapshot = snapshot,
            timeMs = boundaryAnim.startMs + boundaryAnim.durationMs / 2,
            activeSubLoopId = null,
            fallbackIndex = Storyboard.Index(0, 0),
            sceneBeatsCountProvider = { s -> snapshot.slides[s].beatCount },
        )
        assertTrue(crossBadge.contains("→ S2:B1"), "Expected cross-slide arrow badge, got $crossBadge")
    }

    @Test
    fun inspectorExpandedCurvesNumericBezierCopyKotlinAndResizableLayerColumnWork() = runComposeUiTest {
        val tempDir = Files.createTempDirectory("ciacola-curve-resize")
        val store = TimelineOverridesStore(tempDir)
        val deck = createFoundationDeck(includeKeynoteEffectsLab = true)
        lateinit var controller: TimelineStudioController
        val copied = mutableListOf<String>()

        setContent {
            val loopClock = remember { DeckLoopClock() }
            val animatic = rememberAnimatic(storyboard = { deck })
            controller = rememberTimelineStudioController(
                animatic = animatic,
                overridesStore = store,
                loopClock = loopClock,
            )
            controller.clipboardWriter = { copied += it }
            controller.dockedInStoryWindow = true
            Box(Modifier.size(Dp(1280f), Dp(720f))) {
                TimelineStudioPanel(controller = controller, isDark = true)
            }
        }
        waitForIdle()
        runOnIdle {
            controller.refreshSnapshot(deck)
            controller.selectTrack("lab-immutable-stamp")
        }
        waitForIdle()

        // 1. Expanded preset dropdown selects FastOutSlowIn.
        onNodeWithTag("ciacola-inspector-curve-preset-dropdown", useUnmergedTree = true).performClick()
        waitForIdle()
        onNodeWithText("FastOutSlowIn", useUnmergedTree = true).performClick()
        waitForIdle()
        assertEquals("FastOutSlowIn", store.data().tracks["lab-immutable-stamp"]?.curve?.preset)

        // 2. Numeric Bezier coordinate field edits curve cleanly without stealing '-' or digits.
        onNodeWithTag("ciacola-bezier-input-y1", useUnmergedTree = true).performTextReplacement("-0.25")
        onNodeWithTag("ciacola-bezier-input-y1", useUnmergedTree = true).performKeyInput { pressKey(Key.Enter) }
        waitForIdle()
        val editedCurve = store.data().tracks["lab-immutable-stamp"]?.curve
        assertEquals("Custom", editedCurve?.preset)
        assertEquals(-0.25f, editedCurve?.y1 ?: 0f, absoluteTolerance = 0.01f)

        // 3. Copy Curve Kotlin copies CubicBezierEasing(...) and shows "Copied!" feedback.
        onNodeWithTag("ciacola-inspector-copy-curve", useUnmergedTree = true).performClick()
        waitForIdle()
        assertTrue(copied.lastOrNull()?.startsWith("CubicBezierEasing(") == true, "Copied=${copied.lastOrNull()}")
        onNodeWithTag("ciacola-inspector-copy-curve-feedback", useUnmergedTree = true).assertIsDisplayed()

        // 4. Resizable layer column clamps to [180..420] and double-click resets to 236dp.
        onNodeWithTag("ciacola-layer-column-splitter", useUnmergedTree = true).assertExists()
        assertEquals(DEFAULT_LAYER_COLUMN_WIDTH_DP, controller.viewport.layerColumnWidthDp)
        controller.viewport.resizeLayerColumnBy(500f)
        assertEquals(MAX_LAYER_COLUMN_WIDTH_DP, controller.viewport.layerColumnWidthDp)
        controller.viewport.resizeLayerColumnBy(-500f)
        assertEquals(MIN_LAYER_COLUMN_WIDTH_DP, controller.viewport.layerColumnWidthDp)
        controller.viewport.resetLayerColumnWidth()
        assertEquals(DEFAULT_LAYER_COLUMN_WIDTH_DP, controller.viewport.layerColumnWidthDp)

        // 5. Loop Sub-Comp renders matching scrollbars and playhead chrome.
        onNodeWithTag("ciacola-timeline-open-loop", useUnmergedTree = true).performClick()
        waitForIdle()
        onNodeWithTag("ciacola-subcomp-canvas", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithTag("ciacola-subcomp-vscrollbar", useUnmergedTree = true).assertExists()
        onNodeWithTag("ciacola-subcomp-hscrollbar", useUnmergedTree = true).assertExists()
        onNodeWithTag("ciacola-layer-column-splitter", useUnmergedTree = true).assertExists()
    }

    @Test
    fun stageSelectionChipContrastMeetsAaAndHidesInOverview() = runComposeUiTest {
        mainClock.autoAdvance = false
        // 1. Stage selection chip meets >= 5.5:1 WCAG contrast and flips below near top edge.
        val ratio = contrastRatio(STAGE_SELECTION_CHIP_TEXT, STAGE_SELECTION_CHIP_FILL)
        assertTrue(ratio >= 5.5f, "Selection chip contrast must be >= 5.5:1, got $ratio")

        val topAnchorPos = computeSelectionChipOffset(
            anchor = Rect(20f, 8f, 200f, 60f),
            chipSize = Size(120f, 28f),
            canvasSize = Size(1920f, 1080f),
            gapPx = 6f,
            marginPx = 4f,
        )
        assertEquals(66f, topAnchorPos.y, absoluteTolerance = 0.5f, "Chip near top edge must flip below target")

        // 2. Selection overlay hides when LocalOverviewOpen is true.
        val tempDir = Files.createTempDirectory("ciacola-overview-hide")
        val store = TimelineOverridesStore(tempDir)
        var overviewOpen by mutableStateOf(false)
        lateinit var selection: TimelineStageSelection
        setContent {
            val loopClock = remember { DeckLoopClock() }
            selection = remember { TimelineStageSelection().apply { pickingEnabled = true } }
            val motionPath = remember { MotionPathPreviewState() }
            val decorator = rememberTimelineStageDecorator(loopClock, motionPath, store, selection)
            val deck = remember { createFoundationDeck(includeKeynoteEffectsLab = true, stageDecorator = decorator) }
            val animatic = rememberAnimatic(storyboard = { deck })
            CompositionLocalProvider(
                LocalOverviewOpen provides overviewOpen,
                LocalAmbientLoopsPaused provides true,
            ) {
                Box(Modifier.size(480.dp, 270.dp)) {
                    EaselOverview(animatic) { Easel(animatic) }
                }
            }
        }
        mainClock.advanceTimeByFrame()
        mainClock.advanceTimeBy(100)
        runOnIdle { selection.selectedTrackId = "s0:magic-text" }
        mainClock.advanceTimeByFrame()
        assertEquals(1, onAllNodesWithTag("ciacola-stage-selection-overlay", useUnmergedTree = true).fetchSemanticsNodes().size)

        runOnIdle { overviewOpen = true }
        mainClock.advanceTimeByFrame()
        assertEquals(
            0,
            onAllNodesWithTag("ciacola-stage-selection-overlay", useUnmergedTree = true).fetchSemanticsNodes().size,
            "Selection overlay must hide when LocalOverviewOpen is true",
        )
    }

    @Test
    fun dottedLeaderRowKeepsMinimumDotsWidthInNarrowContainer() = runComposeUiTest {
        var densityVal = 1f
        setContent {
            densityVal = LocalDensity.current.density
            val theme = DeckThemes.forPreset(DeckThemePreset.StageDark)
            CompositionLocalProvider(LocalDeckTheme provides theme) {
                Box(Modifier.size(480.dp, 120.dp)) {
                    DottedLeaderRow(
                        leading = "Compose Declarative UI",
                        trailing = "BasicText(AnnotatedString)",
                        trailingStyle = theme.typography.code.copy(fontSize = 14.sp),
                    )
                }
            }
        }
        waitForIdle()
        val trailingBounds = onNodeWithText("BasicText(AnnotatedString)").fetchSemanticsNode().boundsInRoot
        val leadingBounds = onNodeWithText("Compose Declarative UI").fetchSemanticsNode().boundsInRoot
        val gapBetweenTextsDp = (trailingBounds.left - leadingBounds.right) / densityVal
        assertTrue(
            gapBetweenTextsDp >= 36f + 36f, // 20dp + >=36dp dots + 20dp
            "DottedLeaderRow must preserve >=36dp dots gap (got ${gapBetweenTextsDp}dp)",
        )
    }
}
