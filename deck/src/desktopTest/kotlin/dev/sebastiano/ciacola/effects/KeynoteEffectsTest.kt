package dev.sebastiano.ciacola.effects

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.bnorm.storyboard.AdvanceDirection
import dev.bnorm.storyboard.Storyboard
import dev.bnorm.storyboard.easel.Easel
import dev.bnorm.storyboard.easel.rememberAnimatic
import dev.bnorm.storyboard.layout.decorated.DecorationLayout
import dev.sebastiano.ciacola.createFoundationDeck
import dev.sebastiano.ciacola.theme.DeckThemes
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@OptIn(ExperimentalTestApi::class)
class KeynoteEffectsTest {
    @Test
    fun staggerProgressCascadesForwardInOrderAndReversesOrderOnBackwardStep() {
        // 4 items, 300ms each, 50ms stagger -> total span = 300 + 3*50 = 450ms
        val f0 = computeStaggerItemProgress(0.5f, index = 0, count = 4, itemDurationMs = 300, staggerStepMs = 50, reverseOrder = false)
        val f1 = computeStaggerItemProgress(0.5f, index = 1, count = 4, itemDurationMs = 300, staggerStepMs = 50, reverseOrder = false)
        val f2 = computeStaggerItemProgress(0.5f, index = 2, count = 4, itemDurationMs = 300, staggerStepMs = 50, reverseOrder = false)
        val f3 = computeStaggerItemProgress(0.5f, index = 3, count = 4, itemDurationMs = 300, staggerStepMs = 50, reverseOrder = false)

        assertTrue(f0 > f1 && f1 > f2 && f2 > f3, "Forward stagger must cascade 0 -> 3, got $f0, $f1, $f2, $f3")
        assertEquals(0f, computeStaggerItemProgress(0f, index = 0, count = 4, itemDurationMs = 300, staggerStepMs = 50))
        assertEquals(1f, computeStaggerItemProgress(1f, index = 3, count = 4, itemDurationMs = 300, staggerStepMs = 50))

        // Stepping backward (overallProgress dropping toward 0 with reverseOrder = true):
        // the last item (index 3) must lead the exit (lowest remaining progress) while index 0 lingers longest!
        val b0 = computeStaggerItemProgress(0.5f, index = 0, count = 4, itemDurationMs = 300, staggerStepMs = 50, reverseOrder = true)
        val b3 = computeStaggerItemProgress(0.5f, index = 3, count = 4, itemDurationMs = 300, staggerStepMs = 50, reverseOrder = true)
        assertTrue(b0 > b3, "Backward reverse-order stagger must fade trailing item 3 before leading item 0, got b0=$b0, b3=$b3")
    }

    @Test
    fun trimPathSlicesStraightAndMultiContourPathsWithAccurateTipPositionAndTangent() {
        val line = Path().apply {
            moveTo(0f, 0f)
            lineTo(100f, 0f)
        }
        assertNull(trimPath(line, progress = 0f))

        val midOut = Path()
        val midTip = trimPath(line, progress = 0.5f, destination = midOut)
        assertNotNull(midTip)
        assertTrue(!midOut.isEmpty)
        assertTrue(abs(midTip.position.x - 50f) < 0.5f, "Expected midpoint x=50, got ${midTip.position}")
        assertTrue(abs(midTip.position.y) < 0.5f)
        assertTrue(midTip.tangent.x > 0.95f, "Expected unit +X tangent, got ${midTip.tangent}")

        val fullTip = trimPath(line, progress = 1f)
        assertNotNull(fullTip)
        assertTrue(abs(fullTip.position.x - 100f) < 0.5f)

        // Multi-contour path (two 50px segments = 100px total): 75% progress must reach x=25 on contour 2 (y=20)
        val twoContours = Path().apply {
            moveTo(0f, 0f)
            lineTo(50f, 0f)
            moveTo(0f, 20f)
            lineTo(50f, 20f)
        }
        val secondTip = trimPath(twoContours, progress = 0.75f)
        assertNotNull(secondTip)
        assertTrue(abs(secondTip.position.x - 25f) < 0.5f && abs(secondTip.position.y - 20f) < 0.5f)

        // Closed RoundRect callout outline trims smoothly without error
        val roundRectPath = Path().apply {
            addRoundRect(RoundRect(Rect(0f, 0f, 120f, 60f), 12f, 12f))
        }
        val boxOut = Path()
        assertNotNull(trimPath(roundRectPath, progress = 0.4f, destination = boxOut))
        assertTrue(!boxOut.isEmpty)
    }

    @Test
    fun spaceReservedBuildsAndLineDrawDecorationsKeepCenteredSiblingsStationary() = runComposeUiTest {
        var buildProgress by mutableFloatStateOf(0f)
        var firstItemY = Float.NaN

        setContent {
            Box(Modifier.size(480.dp, 320.dp).testTag("stage"), contentAlignment = Alignment.Center) {
                DecorationLayout(
                    modifier = Modifier.fillMaxSize(),
                    decorations = listOf(
                        AnimatedLine(
                            startKey = "note",
                            startAlignment = Alignment.TopCenter,
                            endKey = "headline",
                            endAlignment = Alignment.BottomCenter,
                            progress = { buildProgress },
                            color = Color.Red,
                        ),
                        AnimatedCalloutOutline(
                            targetKey = "headline",
                            progress = { buildProgress },
                            color = Color.Cyan,
                        ),
                        AnimatedStrikeout(
                            targetKey = "headline",
                            progress = { buildProgress },
                            color = Color.Green,
                        ),
                    ),
                ) {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        verticalArrangement = Arrangement.spacedBy(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            "Web .......... <span style=\"opacity\">",
                            modifier = Modifier
                                .testTag("first-row")
                                .decorate("headline")
                                .onGloballyPositioned { coords ->
                                    firstItemY = coords.positionInRoot().y
                                },
                        )
                        Text(
                            "immutable",
                            modifier = Modifier
                                .decorate("note")
                                .keynoteFadeMove(progress = { buildProgress })
                                .keynotePop(progress = { buildProgress }),
                        )
                    }
                }
            }
        }

        waitForIdle()
        val initialY = firstItemY
        assertTrue(!initialY.isNaN())

        val beforePixels = onNodeWithTag("stage").captureToImage().toPixelMap()
        var redAtZero = 0
        for (y in 0 until beforePixels.height) {
            for (x in 0 until beforePixels.width) {
                val c = beforePixels[x, y]
                if (c.red > 0.8f && c.green < 0.2f && c.blue < 0.2f) redAtZero++
            }
        }
        assertEquals(0, redAtZero, "LineDraw must draw 0 red pixels when progress == 0f")

        // Advance buildProgress 0f -> 0.5f -> 1f: first row must not jump vertically by even 0.05px!
        runOnIdle { buildProgress = 0.5f }
        waitForIdle()
        assertEquals(initialY, firstItemY, absoluteTolerance = 0.05f)

        runOnIdle { buildProgress = 1.0f }
        waitForIdle()
        assertEquals(initialY, firstItemY, absoluteTolerance = 0.05f)

        val afterPixels = onNodeWithTag("stage").captureToImage().toPixelMap()
        var redAtOne = 0
        var cyanAtOne = 0
        var greenAtOne = 0
        for (y in 0 until afterPixels.height) {
            for (x in 0 until afterPixels.width) {
                val c = afterPixels[x, y]
                if (c.red > 0.8f && c.green < 0.2f && c.blue < 0.2f) redAtOne++
                if (c.red < 0.2f && c.green > 0.8f && c.blue > 0.8f) cyanAtOne++
                if (c.red < 0.2f && c.green > 0.8f && c.blue < 0.2f) greenAtOne++
            }
        }
        assertTrue(redAtOne > 20, "AnimatedLine + ArrowHead must paint red pixels at progress=1f, got $redAtOne")
        assertTrue(cyanAtOne > 20, "AnimatedCalloutOutline must paint cyan pixels at progress=1f, got $cyanAtOne")
        assertTrue(greenAtOne > 20, "AnimatedStrikeout must paint green pixels at progress=1f, got $greenAtOne")
    }

    @Test
    fun keynoteEffectsLabSceneStepsForwardAndBackwardThroughAllFiveBuildsAndMagnifierLoupe() = runComposeUiTest {
        lateinit var animatic: dev.bnorm.storyboard.easel.Animatic
        lateinit var uiScope: CoroutineScope
        val deck = createFoundationDeck(
            theme = DeckThemes.keynoteLight(),
            includeKeynoteEffectsLab = true,
        )
        assertEquals(listOf(3, 2, 1, 3, 5), deck.scenes.map { it.frames.size })

        setContent {
            uiScope = androidx.compose.runtime.rememberCoroutineScope()
            animatic = rememberAnimatic(
                initialIndex = Storyboard.Index(sceneIndex = 4, frameIndex = 0),
                storyboard = { deck },
            )
            Box(Modifier.size(960.dp, 540.dp).testTag("lab-easel")) {
                Easel(animatic)
            }
        }

        waitForIdle()
        assertEquals(Storyboard.Index(4, 0), animatic.currentIndex)

        // Step forward through B1 (stagger), B2 (LineDraw arrow + Pop stamp), B3 (Spotlight + Warp diff), B4 (Strikeout + Loupe)
        for (expectedFrame in 1..4) {
            runOnIdle { uiScope.launch { animatic.advance(AdvanceDirection.Forward) } }
            mainClock.advanceTimeBy(600)
            waitForIdle()
            assertEquals(Storyboard.Index(4, expectedFrame), animatic.currentIndex)
        }

        // Step backward from B4 -> B3 -> B2 to prove symmetric reverse transitions
        for (expectedFrame in 3 downTo 2) {
            runOnIdle { uiScope.launch { animatic.advance(AdvanceDirection.Backward) } }
            mainClock.advanceTimeBy(600)
            waitForIdle()
            assertEquals(Storyboard.Index(4, expectedFrame), animatic.currentIndex)
        }
    }

    @Test
    fun magnifierLoupeExcludesDecorationsFromLensAndFadesNearZeroProgress() = runComposeUiTest {
        var progress by mutableFloatStateOf(1f)
        val darkTheme = DeckThemes.stageDark()

        setContent {
            dev.sebastiano.ciacola.theme.DeckThemeProvider(darkTheme) {
                Box(Modifier.size(640.dp, 360.dp).testTag("loupe-stage")) {
                    MagnifierLoupeStage(
                        targetKey = "target",
                        progress = { progress },
                        modifier = Modifier.fillMaxSize(),
                        decorations = listOf(
                            AnimatedLine(
                                startKey = "origin",
                                startAlignment = Alignment.TopCenter,
                                endKey = "target",
                                endAlignment = Alignment.BottomCenter,
                                progress = { 1f },
                                color = Color.Green,
                                gap = 0.dp,
                            ),
                        ),
                    ) {
                        Column(
                            modifier = Modifier.align(Alignment.CenterStart),
                            verticalArrangement = Arrangement.spacedBy(28.dp),
                        ) {
                            Text("BasicText(AnnotatedString)", color = Color.White, modifier = Modifier.decorate("target"))
                            Text("IMMUTABLE", color = Color.White, modifier = Modifier.decorate("origin"))
                        }
                    }
                }
            }
        }

        waitForIdle()
        val fullPixels = onNodeWithTag("loupe-stage").captureToImage().toPixelMap()
        // Right side holds the loupe lens; green AnimatedLine is on the left column only.
        var greenInLoupeHalf = 0
        for (y in 0 until fullPixels.height) {
            for (x in (fullPixels.width / 2) until fullPixels.width) {
                val c = fullPixels[x, y]
                if (c.green > 0.75f && c.red < 0.25f && c.blue < 0.25f) greenInLoupeHalf++
            }
        }
        assertEquals(
            0,
            greenInLoupeHalf,
            "MagnifierLoupeStage lens must not magnify stage decorations (found $greenInLoupeHalf green pixels in lens half)",
        )

        // At near-zero progress (p = 0.01f), the collapsing loupe ring must fade out rather than paint a bright pinhole dot.
        runOnIdle { progress = 0.01f }
        waitForIdle()
        val tinyPixels = onNodeWithTag("loupe-stage").captureToImage().toPixelMap()
        var brightAccentPinhole = 0
        for (y in 0 until tinyPixels.height) {
            for (x in (tinyPixels.width / 2) until tinyPixels.width) {
                val c = tinyPixels[x, y]
                if (c.red > 0.65f && c.blue < 0.55f) brightAccentPinhole++
            }
        }
        assertEquals(0, brightAccentPinhole, "Collapsing loupe at p=0.01f must fade cleanly without leaving a bright pinhole")
    }
}
