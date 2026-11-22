package dev.sebastiano.ciacola

import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import dev.bnorm.storyboard.AdvanceDirection
import dev.bnorm.storyboard.BasicCode
import dev.bnorm.storyboard.Kotlin
import dev.bnorm.storyboard.State
import dev.bnorm.storyboard.Storyboard
import dev.bnorm.storyboard.buildDiffList
import dev.bnorm.storyboard.easel.Animatic
import dev.bnorm.storyboard.easel.Easel
import dev.bnorm.storyboard.easel.rememberAnimatic
import dev.sebastiano.ciacola.effects.DeckWarpCodeCard
import dev.sebastiano.ciacola.effects.arrowHeadLength
import dev.sebastiano.ciacola.effects.keynoteFadeMove
import dev.sebastiano.ciacola.effects.keynoteFromDarkness
import dev.sebastiano.ciacola.effects.keynotePopAlpha
import dev.sebastiano.ciacola.effects.keynotePopScale
import dev.sebastiano.ciacola.effects.loupeSampleClip
import dev.sebastiano.ciacola.effects.trailingHighlightColor
import dev.sebastiano.ciacola.theme.DeckThemes
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Regression tests for motion, Warp code diffing, and WCAG contrast invariants. */
@OptIn(ExperimentalTestApi::class)
class MotionAndContrastFixesTest {

    @Test
    fun slide1MagicTextCompletesInterruptedAdvanceCleanlyWithValidSemanticsLayout() = runComposeUiTest {
        mainClock.autoAdvance = false
        val deck = buildFoundationTestStoryboard()
        lateinit var animatic: Animatic
        lateinit var scope: CoroutineScope
        setContent {
            scope = rememberCoroutineScope()
            Box(Modifier.size(960.dp, 540.dp)) {
                animatic = rememberAnimatic(storyboard = { deck })
                Easel(animatic)
            }
        }
        mainClock.advanceTimeBy(200)

        lateinit var b1ToB2: Job
        runOnIdle { b1ToB2 = scope.launch { animatic.advance(AdvanceDirection.Forward) } }
        repeat(20) { mainClock.advanceTimeByFrame() }
        lateinit var b2ToB3: Job
        runOnIdle { b2ToB3 = scope.launch { animatic.advance(AdvanceDirection.Forward) } }

        var observedEarlyB2ToB3 = false
        var safety = 0
        while (!b2ToB3.isCompleted && safety++ < 250) {
            mainClock.advanceTimeByFrame()
            val (settling, frac) = runOnIdle { animatic.scrubSettling to animatic.activeFraction }
            if (!settling && frac in 0.05f..0.45f) {
                observedEarlyB2ToB3 = true
                val proseNode = onNodeWithTag("doc-node-s1:prose").fetchSemanticsNode()
                assertTrue(proseNode.boundsInRoot.width > 50f && proseNode.boundsInRoot.height > 20f)
            }
        }
        assertTrue(b2ToB3.isCompleted, "Interrupted advance to S1:B3 completed")
        assertTrue(observedEarlyB2ToB3, "Observed early live frames of S1:B2 -> B3")
        assertEquals(Storyboard.Index(0, 2), animatic.currentIndex)

        val proseSemantics = onNodeWithTag("doc-node-s1:prose").fetchSemanticsNode()
        val layouts = mutableListOf<TextLayoutResult>()
        proseSemantics.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        assertEquals(1, layouts.size)
        assertTrue(layouts.single().layoutInput.text.text.contains("second navigation model."))
    }

    private fun ComposeUiTest.entranceBounds(
        effect: @Composable Modifier.() -> Modifier,
    ): Rect {
        var bounds = Rect.Zero
        setContent {
            Box(Modifier.size(400.dp)) {
                Box(
                    Modifier
                        .offset(100.dp, 100.dp)
                        .size(50.dp)
                        .effect()
                        .onGloballyPositioned { bounds = it.boundsInRoot() },
                )
            }
        }
        waitForIdle()
        return bounds
    }

    private val warpSnippets = listOf(
        "fun fade(s: String) {\n  BasicText(s)\n}",
        "fun fade(\n  s: String,\n  a: Float,\n) {\n  BasicText(s, alpha = a)\n}",
    )

    private fun warpCardHeights(from: Int, to: Int): List<Float> {
        val heights = mutableListOf<Float>()
        runComposeUiTest {
            var step by mutableIntStateOf(from)
            setContent {
                Box(Modifier.size(900.dp, 700.dp).background(Color.Magenta)) {
                    DeckWarpCodeCard(
                        codeStates = warpSnippets,
                        stateTransition = updateTransition(step),
                        modifier = Modifier.testTag("warp"),
                    )
                }
            }
            fun visibleHeight(): Float {
                val pixels = onNodeWithTag("warp").captureToImage().toPixelMap()
                val x = pixels.width / 2
                return (0 until pixels.height).count { y -> pixels[x, y] != Color.Magenta }.toFloat()
            }
            waitForIdle()
            mainClock.autoAdvance = false
            heights += visibleHeight()
            step = to
            repeat(40) {
                mainClock.advanceTimeByFrame()
                heights += visibleHeight()
            }
        }
        return heights
    }

    private fun assertSmoothHeight(heights: List<Float>, label: String) {
        val start = heights.first()
        val end = heights.last()
        val delta = abs(end - start)
        assertTrue(delta > 20f, "$label: expected the card height to change, got $start -> $end")
        val maxJump = heights.zipWithNext { a, b -> abs(b - a) }.max()
        assertTrue(maxJump <= delta * 0.35f, "$label: largest single-frame height jump $maxJump of $delta ($heights)")
        val settledFrame = heights.indexOfFirst { abs(it - end) < 0.5f }
        assertTrue(settledFrame * 16 <= 450, "$label: height settled only after ${settledFrame * 16} ms ($heights)")
    }

    @Test
    fun warpCodeCardInterpolatesItsHeightForwardAndBackward() {
        assertSmoothHeight(warpCardHeights(0, 1), "forward 3 -> 6 lines")
        assertSmoothHeight(warpCardHeights(1, 0), "backward 6 -> 3 lines")
    }

    @Test
    fun warpCodeDiffMatchesParenthesesParametersAndDelimitersAcrossMultilineReflow() {
        val diffs = buildDiffList(
            warpSnippets.map { BasicCode(it) },
            Kotlin,
        )
        val beforeStates = diffs[0].rows.flatten()
        val afterStates = diffs[1].rows.flatten()

        val deletedVisible = beforeStates
            .filterIsInstance<State.Delete>()
            .map { it.token.content }
            .filter { it.isNotBlank() }
        assertTrue(
            deletedVisible.isEmpty(),
            "Expanding fun fade(s: String) to multiline should not delete any visible token (including '(' or 's'), got deletes=$deletedVisible",
        )

        val matchedAfter = afterStates
            .filterIsInstance<State.Match>()
            .map { it.current.content }
            .filter { it.isNotBlank() }
        assertEquals(2, matchedAfter.count { it == "(" }, "Both '(' tokens must match instead of being replaced: $matchedAfter")
        assertEquals(2, matchedAfter.count { it == ")" }, "Both ')' tokens must match: $matchedAfter")
        assertEquals(2, matchedAfter.count { it == "s" }, "Both 's' identifiers must match: $matchedAfter")
        assertTrue(matchedAfter.containsAll(listOf("fun", "fade", ":", "String", "{", "BasicText", "}")))
    }

    @Test
    fun fadeMoveAndFromDarknessApplyTheBuildEasingOnlyOnce() {
        var density = 1f
        var fadeMove = Rect.Zero
        var fromDarkness = Rect.Zero
        runComposeUiTest {
            density = this.density.density
            fadeMove = entranceBounds { keynoteFadeMove(progress = { 0.5f }) }
        }
        runComposeUiTest {
            fromDarkness = entranceBounds { keynoteFromDarkness(progress = { 0.5f }) }
        }
        assertEquals((125f + 12f) * density, fadeMove.center.y, 0.5f, "Half-way build = half the 24 dp travel left")
        assertEquals(50f * (0.88f + 0.12f * 0.5f) * density, fromDarkness.width, 0.5f, "Half-way build = half the scale-up")
    }

    @Test
    fun popScaleAndAlphaAreC1SmoothOvershootOnceAndLandAtRest() {
        val n = 400
        val h = 1f / n
        val scale = (0..n).map { keynotePopScale(it * h, initialScale = 0.72f, overshootScale = 1.06f) }
        assertEquals(0.72f, scale.first(), 1e-4f)
        assertEquals(1f, scale.last(), 1e-4f)
        assertEquals(1.06f, scale.max(), 0.005f)
        val slope = scale.zipWithNext { a, b -> (b - a) / h }
        val kink = slope.zipWithNext { a, b -> abs(b - a) }.max()
        assertTrue(kink < 0.1f, "Scale velocity must be continuous, largest step change $kink")
        assertTrue(abs(slope.last()) < 0.05f, "Pop must land with ~zero velocity, got ${slope.last()}")

        val alpha = (0..n).map { keynotePopAlpha(it * h) }
        val alphaSlope = alpha.zipWithNext { a, b -> (b - a) / h }
        val alphaKink = alphaSlope.zipWithNext { a, b -> abs(b - a) }.max()
        assertTrue(alphaKink < 0.2f, "Alpha velocity must be continuous, largest step change $alphaKink")
        assertEquals(1f, alpha.last(), 1e-4f)
    }

    @Test
    fun trailingHighlightBlendsInsteadOfSnapping() {
        val quarter = trailingHighlightColor(base = Color.White, accent = Color.Red, progress = 0.25f)
        val half = trailingHighlightColor(base = Color.White, accent = Color.Red, progress = 0.5f)
        assertTrue(quarter.green in 0.3f..0.95f, "A quarter-way highlight is a partial blend, got $quarter")
        assertTrue(half.green < quarter.green, "The blend progresses monotonically ($quarter -> $half)")
        assertEquals(Color.Red, trailingHighlightColor(Color.White, Color.Red, 1f))
        assertEquals(Color.White, trailingHighlightColor(Color.White, Color.Red, 0f))
    }

    @Test
    fun leaderArrowHeadGrowsWithTheShaft() {
        assertTrue(arrowHeadLength(14f, 0.05f) < 14f * 0.3f, "A barely drawn leader must not carry a full head")
        assertEquals(14f, arrowHeadLength(14f, 1f), 1e-4f)
        assertTrue(arrowHeadLength(14f, 0.1f) < arrowHeadLength(14f, 0.2f))
    }

    @Test
    fun loupeOnlyMagnifiesTheTargetCallout() {
        val target = Rect(100f, 100f, 300f, 124f)
        val lensCenter = Offset(82f, 82f)
        val zoom = 2.4f
        val clip = loupeSampleClip(target, lensCenter, zoom, padPx = 4f)
        fun toLens(p: Offset) = Offset(lensCenter.x + (p.x - target.center.x) * zoom, lensCenter.y + (p.y - target.center.y) * zoom)
        assertTrue(clip.contains(toLens(target.center)))
        assertTrue(clip.contains(toLens(Offset(target.center.x, target.bottom + 3f))))
        assertFalse(clip.contains(toLens(Offset(target.center.x, target.bottom + 10f))))
    }

    private fun contrast(a: Color, b: Color): Float {
        val la = a.luminance()
        val lb = b.luminance()
        return (maxOf(la, lb) + 0.05f) / (minOf(la, lb) + 0.05f)
    }

    private fun over(fg: Color, alpha: Float, bg: Color) = Color(
        red = fg.red * alpha + bg.red * (1f - alpha),
        green = fg.green * alpha + bg.green * (1f - alpha),
        blue = fg.blue * alpha + bg.blue * (1f - alpha),
    )

    @Test
    fun deckThemeTokensMeetWcagContrastInBothThemes() {
        for (theme in listOf(DeckThemes.stageDark(), DeckThemes.keynoteLight())) {
            val c = theme.colors
            val name = theme.name
            assertTrue(contrast(c.muted, c.background) >= 4.5f, "$name: muted text ${contrast(c.muted, c.background)}:1")
            assertTrue(contrast(c.onAccent, c.accent) >= 4.5f, "$name: Shared Card label ${contrast(c.onAccent, c.accent)}:1")
            val stampFill = over(c.accent, 0.14f, c.background)
            assertTrue(contrast(c.accent, stampFill) >= 3.3f, "$name: stamp text ${contrast(c.accent, stampFill)}:1 (large display)")
            val dimmed = over(c.foreground, c.spotlightDimAlpha, c.background)
            assertTrue(contrast(dimmed, c.background) >= 3.0f, "$name: spotlight-dimmed text ${contrast(dimmed, c.background)}:1")
            assertTrue(
                contrast(c.motionPathPreview, c.background) >= 3.0f,
                "$name: motion-path preview ${contrast(c.motionPathPreview, c.background)}:1 (graphical)",
            )
        }
    }
}
