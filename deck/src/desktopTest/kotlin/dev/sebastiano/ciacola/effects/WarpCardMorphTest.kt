package dev.sebastiano.ciacola.effects

import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.rememberTransition
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.bnorm.storyboard.Kotlin
import dev.sebastiano.ciacola.inspector.LocalStageInspectionRegistry
import dev.sebastiano.ciacola.inspector.LocalStageRootCoordinates
import dev.sebastiano.ciacola.inspector.StageInspectionRegistry
import dev.sebastiano.ciacola.inspector.StageRootCoordinatesHolder
import dev.sebastiano.ciacola.theme.DeckThemeProvider
import dev.sebastiano.ciacola.theme.DeckThemes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@OptIn(ExperimentalTestApi::class)
class WarpCardMorphTest {

    private fun ComposeUiTest.runOnUi(scope: CoroutineScope, block: suspend () -> Unit) {
        lateinit var job: Job
        runOnIdle { job = scope.launch { block() } }
        var guard = 0
        while (!job.isCompleted && guard++ < 500) mainClock.advanceTimeByFrame()
        assertTrue(job.isCompleted, "UI coroutine did not complete")
    }

    @Test
    fun midMorphRendersSingleSurfaceAndInsertsOverlapDeletes() = runComposeUiTest {
        mainClock.autoAdvance = false
        val step0 = """
            fun compute(oldValue: Int): Int {
                return oldValue + 1
            }
        """.trimIndent()
        val step1 = """
            fun compute(newValue: Int, scale: Int): Int {
                val scaled = newValue * scale
                return scaled + 1
            }
        """.trimIndent()

        val ts = SeekableTransitionState(0)
        lateinit var scope: CoroutineScope
        setContent {
            scope = rememberCoroutineScope()
            DeckThemeProvider(DeckThemes.stageDark()) {
                Box(Modifier.size(960.dp, 540.dp).testTag("stage")) {
                    val transition = rememberTransition(ts, label = "warp-morph")
                    DeckWarpCodeCard(
                        codeStates = listOf(step0, step1),
                        stateTransition = transition,
                        language = Kotlin,
                        elementId = "test:warp",
                        trackId = "warp:custom-track",
                    )
                }
            }
        }
        mainClock.advanceTimeBy(200)

        // Seek to mid-transition (f = 0.5): exactly one warp-card-surface must exist.
        runOnUi(scope) { ts.seekTo(0f, 1) }
        repeat(4) { mainClock.advanceTimeByFrame() }
        runOnUi(scope) { ts.seekTo(0.5f, 1) }
        repeat(2) { mainClock.advanceTimeByFrame() }

        val surfaces = onAllNodesWithTag("warp-card-surface", useUnmergedTree = true).fetchSemanticsNodes()
        assertEquals(1, surfaces.size, "Expected exactly 1 warp-card-surface mid-morph, got ${surfaces.size}")

        // Verify D-5 insert timing overlap:
        // default tokenDurationMs = 700 -> deleteDurationMs = 336ms (0..336ms),
        // moveDelayMs = 105ms, moveDurationMs = 490ms, insertDelayMs = 105 + 196 = 301ms (<= 336 + 16ms).
        // Therefore, by f = 0.45 (~366ms of ~810ms total), inserted token "scaled" must already be
        // mounted and visibly painted on screen (non-zero brightness above card background).
        runOnUi(scope) { ts.seekTo(0.48f, 1) }
        repeat(2) { mainClock.advanceTimeByFrame() }
        val insertedNodes = onAllNodes(hasText("scaled", substring = true), useUnmergedTree = true).fetchSemanticsNodes()
        assertTrue(insertedNodes.isNotEmpty(), "Inserted token 'scaled' should already be mounted during delete tail overlap")
        val bounds = insertedNodes.first().boundsInRoot
        val pm = onNodeWithTag("stage").captureToImage().toPixelMap()
        val x0 = bounds.left.toInt().coerceIn(0, pm.width - 1)
        val y0 = bounds.top.toInt().coerceIn(0, pm.height - 1)
        val x1 = bounds.right.toInt().coerceIn(x0 + 1, pm.width)
        val y1 = bounds.bottom.toInt().coerceIn(y0 + 1, pm.height)
        var maxLum = 0f
        for (y in y0 until y1) {
            for (x in x0 until x1) {
                val c = pm[x, y]
                val lum = 0.2126f * c.red + 0.7152f * c.green + 0.0722f * c.blue
                if (lum > maxLum) maxLum = lum
            }
        }
        assertTrue(
            maxLum > 0.15f,
            "Inserted token 'scaled' should already be visibly fading in at f=0.48 (maxLum=$maxLum)",
        )
    }

    @Test
    fun codeFitShrinkToWidthPreventsVisualOverflowAndReportsOverflowUnderCodeFitNone() = runComposeUiTest {
        mainClock.autoAdvance = false
        val long120ColLine =
            "val veryLongConfigurationIdentifierName = computeDeterministicPipelineOutput(firstInputParam, secondInputParam, thirdParam)"
        assertTrue(long120ColLine.length >= 120, "Synthetic line must be >= 120 columns")

        val registry = StageInspectionRegistry()
        setContent {
            val rootHolder = remember { StageRootCoordinatesHolder() }
            val tsNone = remember { SeekableTransitionState(0) }
            val tsShrink = remember { SeekableTransitionState(0) }
            DeckThemeProvider(DeckThemes.stageDark()) {
                CompositionLocalProvider(
                    LocalStageInspectionRegistry provides registry,
                    LocalStageRootCoordinates provides rootHolder,
                ) {
                    Box(
                        modifier = Modifier
                            .size(960.dp, 540.dp)
                            .onPlaced { rootHolder.coordinates = it },
                    ) {
                        val trNone = rememberTransition(tsNone, label = "fit-none")
                        DeckWarpCodeCard(
                            codeStates = listOf(long120ColLine),
                            stateTransition = trNone,
                            language = Kotlin,
                            elementId = "warp:fit-none",
                            fit = CodeFit.None,
                            modifier = Modifier.widthIn(max = 900.dp),
                        )
                        val trShrink = rememberTransition(tsShrink, label = "fit-shrink")
                        DeckWarpCodeCard(
                            codeStates = listOf(long120ColLine),
                            stateTransition = trShrink,
                            language = Kotlin,
                            elementId = "warp:fit-shrink",
                            fit = CodeFit.ShrinkToWidth(min = 0.35f),
                            modifier = Modifier.widthIn(max = 900.dp).testTag("shrink-card"),
                        )
                    }
                }
            }
        }
        repeat(6) { mainClock.advanceTimeByFrame() }

        val noneSnapshot = registry.nodes["warp:fit-none"]
        val shrinkSnapshot = registry.nodes["warp:fit-shrink"]
        assertTrue(noneSnapshot != null, "Expected StageInspectionRegistry snapshot for warp:fit-none")
        assertTrue(shrinkSnapshot != null, "Expected StageInspectionRegistry snapshot for warp:fit-shrink")
        assertTrue(
            noneSnapshot.hasVisualOverflow,
            "CodeFit.None must report hasVisualOverflow=true on a 120-column line inside 900.dp",
        )
        assertFalse(
            shrinkSnapshot.hasVisualOverflow,
            "CodeFit.ShrinkToWidth(0.5f) must fit a 120-column line inside 900.dp with hasVisualOverflow=false",
        )
        assertTrue(
            shrinkSnapshot.stageBounds.width <= 900.5f * density.density,
            "ShrinkToWidth card width (${shrinkSnapshot.stageBounds.width}) must not exceed 900.dp",
        )
    }

    @Test
    fun highlightedLinesDimsNonHighlightedLines() = runComposeUiTest {
        mainClock.autoAdvance = false
        val code = """
            val highlightedLineAlphaCheck = 111
            val dimmedLineAlphaCheck = 222
        """.trimIndent()

        setContent {
            val ts = remember { SeekableTransitionState(0) }
            DeckThemeProvider(DeckThemes.stageDark()) {
                Box(Modifier.size(960.dp, 300.dp).testTag("stage")) {
                    val tr = rememberTransition(ts, label = "highlight-test")
                    DeckWarpCodeCard(
                        codeStates = listOf(code),
                        stateTransition = tr,
                        language = Kotlin,
                        highlightedLines = { setOf(0) },
                        dimmedLineAlpha = 0.30f,
                    )
                }
            }
        }
        repeat(5) { mainClock.advanceTimeByFrame() }

        val highlightedBounds = onAllNodes(hasText("highlightedLineAlphaCheck", substring = true), useUnmergedTree = true)
            .fetchSemanticsNodes().single().boundsInRoot
        val dimmedBounds = onAllNodes(hasText("dimmedLineAlphaCheck", substring = true), useUnmergedTree = true)
            .fetchSemanticsNodes().single().boundsInRoot
        val pm = onNodeWithTag("stage").captureToImage().toPixelMap()

        fun peakLuminance(rect: androidx.compose.ui.geometry.Rect): Float {
            val x0 = rect.left.toInt().coerceIn(0, pm.width - 1)
            val y0 = rect.top.toInt().coerceIn(0, pm.height - 1)
            val x1 = rect.right.toInt().coerceIn(x0 + 1, pm.width)
            val y1 = rect.bottom.toInt().coerceIn(y0 + 1, pm.height)
            var peak = 0f
            for (y in y0 until y1) {
                for (x in x0 until x1) {
                    val c = pm[x, y]
                    val lum = 0.2126f * c.red + 0.7152f * c.green + 0.0722f * c.blue
                    if (lum > peak) peak = lum
                }
            }
            return peak
        }

        val highlightedPeak = peakLuminance(highlightedBounds)
        val dimmedPeak = peakLuminance(dimmedBounds)
        assertTrue(
            highlightedPeak > dimmedPeak * 1.4f,
            "Highlighted line peak luminance ($highlightedPeak) should clearly exceed dimmed line ($dimmedPeak)",
        )
    }
}
