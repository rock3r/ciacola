package dev.sebastiano.ciacola

import androidx.compose.animation.core.createChildTransition
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.ProvideTextStyle
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.renderComposeScene
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.bnorm.storyboard.AdvanceDirection
import dev.bnorm.storyboard.Storyboard
import dev.bnorm.storyboard.easel.Animatic
import dev.bnorm.storyboard.easel.Easel
import dev.bnorm.storyboard.easel.rememberAnimatic
import dev.bnorm.storyboard.text.magic.MagicText
import dev.bnorm.storyboard.toValue
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@OptIn(ExperimentalComposeUiApi::class)
class MagicTextWrapTest {

    @Test
    fun magicTextWrapsLongSingleLineWithinBoundedContainerWidth() {
        var measuredBounds = Rect.Zero
        val maxContainerWidthPx = 600 // 300.dp at density 2f

        renderComposeScene(width = maxContainerWidthPx, height = 400, density = Density(2f)) {
            Box(Modifier.width(300.dp), contentAlignment = Alignment.TopStart) {
                ProvideTextStyle(TextStyle(fontSize = 28.sp, lineHeight = 36.sp)) {
                    MagicText(
                        text = AnnotatedString(
                            "The same path is reversible without inventing a second navigation model.",
                        ),
                        modifier = Modifier.onGloballyPositioned { coords ->
                            measuredBounds = coords.boundsInRoot()
                        },
                    )
                }
            }
        }

        assertTrue(
            measuredBounds.width <= maxContainerWidthPx + 1f,
            "Expected MagicText width (${measuredBounds.width}) <= container width ($maxContainerWidthPx)",
        )
        assertTrue(
            measuredBounds.height > 100f,
            "Expected wrapped MagicText height (${measuredBounds.height}) to span multiple lines (> 100px)",
        )
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun magicTextInterpolatesContainerHeightSmoothlyInsteadOfSnappingSiblingLayoutOnFirstFrame() = runComposeUiTest {
        mainClock.autoAdvance = false
        val deck = createFoundationDeck()
        lateinit var animatic: Animatic
        lateinit var scope: CoroutineScope
        val siblingTops = mutableListOf<Float>()

        val probeDeck = Storyboard.build(
            title = "HeightProbe",
            format = DeckDesign.format,
            decorator = deck.decorator,
        ) {
            scene(
                frames = listOf(
                    AnnotatedString("Syntax highlighting is data carried by AnnotatedString."),
                    AnnotatedString("Retained Kotlin tokens move smoothly across lines while their styles remain attached."),
                    AnnotatedString("The sample remains intentionally small and replaceable."),
                ),
            ) {
                val desc = transition.createChildTransition(label = "desc") { it.toValue() }
                Column(
                    Modifier.fillMaxSize().padding(64.dp),
                    verticalArrangement = Arrangement.spacedBy(44.dp),
                ) {
                    DeckMagicText(desc)
                    Box(Modifier.width(200.dp).height(100.dp).testTag("below-card"))
                }
            }
        }

        setContent {
            scope = rememberCoroutineScope()
            Box(Modifier.width(960.dp).height(540.dp)) {
                animatic = rememberAnimatic(storyboard = { probeDeck })
                Easel(animatic)
            }
        }
        mainClock.advanceTimeBy(200)

        fun belowTop(): Float = onNodeWithTag("below-card").fetchSemanticsNode().boundsInRoot.top

        // 1. Expanding 1 line -> 2 lines (Beat 0 -> Beat 1)
        siblingTops.clear()
        siblingTops += belowTop()
        lateinit var expandJob: Job
        runOnIdle { expandJob = scope.launch { animatic.advance(AdvanceDirection.Forward) } }
        var guard = 0
        while (!expandJob.isCompleted && guard++ < 200) {
            mainClock.advanceTimeByFrame()
            val settling = runOnIdle { animatic.scrubSettling }
            if (!settling) siblingTops += belowTop()
        }
        mainClock.advanceTimeByFrame()
        siblingTops += belowTop()
        val startTop = siblingTops.first()
        val endTop = siblingTops.last()
        val totalShift = abs(endTop - startTop)
        assertTrue(totalShift > 20f, "Expected 1 -> 2 line description to shift card down (>20px), got $startTop -> $endTop")
        assertTrue(
            siblingTops.take(10).all { abs(it - startTop) < 1.5f },
            "Expected container bounds to hold steady during initial deletion phase before gliding, got ${siblingTops.take(12)}",
        )
        val maxExpandStep = siblingTops.zipWithNext { a, b -> abs(b - a) }.max()
        assertTrue(
            maxExpandStep <= totalShift * 0.20f,
            "Card below MagicText snapped by $maxExpandStep px (of $totalShift px total) on 1->2 line transition: $siblingTops",
        )

        // 2. Shrinking 2 lines -> 1 line (Beat 1 -> Beat 2)
        val shrinkTops = mutableListOf(belowTop())
        lateinit var shrinkJob: Job
        runOnIdle { shrinkJob = scope.launch { animatic.advance(AdvanceDirection.Forward) } }
        guard = 0
        while (!shrinkJob.isCompleted && guard++ < 200) {
            mainClock.advanceTimeByFrame()
            val settling = runOnIdle { animatic.scrubSettling }
            if (!settling) shrinkTops += belowTop()
        }
        mainClock.advanceTimeByFrame()
        shrinkTops += belowTop()
        val shrinkStart = shrinkTops.first()
        val shrinkEnd = shrinkTops.last()
        val shrinkShift = abs(shrinkEnd - shrinkStart)
        assertTrue(shrinkShift > 20f, "Expected 2 -> 1 line description to shift card up (>20px), got $shrinkStart -> $shrinkEnd")
        val maxShrinkStep = shrinkTops.zipWithNext { a, b -> abs(b - a) }.max()
        assertTrue(
            maxShrinkStep <= shrinkShift * 0.25f,
            "Card below MagicText snapped by $maxShrinkStep px (of $shrinkShift px total) on 2->1 line transition: $shrinkTops",
        )
    }
}
