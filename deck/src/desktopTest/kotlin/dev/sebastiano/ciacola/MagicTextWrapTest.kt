package dev.sebastiano.ciacola

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.ProvideTextStyle
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
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
import dev.bnorm.storyboard.easel.Animatic
import dev.bnorm.storyboard.easel.Easel
import dev.bnorm.storyboard.easel.rememberAnimatic
import dev.bnorm.storyboard.text.magic.MagicText
import dev.sebastiano.ciacola.document.BeatSpec
import dev.sebastiano.ciacola.document.Build
import dev.sebastiano.ciacola.document.ContainerPayload
import dev.sebastiano.ciacola.document.DeckDocument
import dev.sebastiano.ciacola.document.FrameSpec
import dev.sebastiano.ciacola.document.NodeSpec
import dev.sebastiano.ciacola.document.PaddingSpec
import dev.sebastiano.ciacola.document.RenderSession
import dev.sebastiano.ciacola.document.RichTextValue
import dev.sebastiano.ciacola.document.ShapeGeometry
import dev.sebastiano.ciacola.document.ShapePayload
import dev.sebastiano.ciacola.document.SlideSpec
import dev.sebastiano.ciacola.document.TextPayload
import dev.sebastiano.ciacola.document.TextMorphSpec
import dev.sebastiano.ciacola.document.render.DocumentRenderConfig
import dev.sebastiano.ciacola.document.render.buildDocumentStoryboard
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.putJsonObject

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
    fun magicTextV2InterpolatesContainerHeightSmoothlyInsteadOfSnappingSiblingLayoutOnFirstFrame() = runComposeUiTest {
        mainClock.autoAdvance = false
        val baseDoc = loadFoundationTestDocument()
        val probeDoc = baseDoc.copy(
            id = "height-probe",
            title = "HeightProbe",
            slides = listOf(
                SlideSpec(
                    id = "s1",
                    beats = listOf(BeatSpec(), BeatSpec(), BeatSpec()),
                    nodes = listOf(
                        NodeSpec(
                            id = "col",
                            frame = FrameSpec(x = 0.0, y = 0.0, w = 1400.0, h = 1080.0),
                            column = ContainerPayload(
                                gap = 88.0,
                                padding = PaddingSpec(left = 128.0, top = 128.0, right = 128.0, bottom = 128.0),
                                children = listOf(
                                    NodeSpec(
                                        id = "desc",
                                        frame = FrameSpec(w = 1100.0),
                                        text = TextPayload(
                                            value = RichTextValue.Plain("Syntax highlighting is data carried by AnnotatedString."),
                                            style = "heading",
                                            color = "foreground",
                                            morph = TextMorphSpec(),
                                        ),
                                        builds = listOf(
                                            Build.Change(
                                                beat = 1,
                                                set = buildJsonObject {
                                                    putJsonObject("text") {
                                                        put(
                                                            "value",
                                                            JsonPrimitive("Retained Kotlin tokens move smoothly across lines while their styles remain attached to every morphing piece."),
                                                        )
                                                    }
                                                },
                                            ),
                                            Build.Change(
                                                beat = 2,
                                                set = buildJsonObject {
                                                    putJsonObject("text") {
                                                        put("value", JsonPrimitive("The sample remains intentionally small and replaceable."))
                                                    }
                                                },
                                            ),
                                        ),
                                    ),
                                    NodeSpec(
                                        id = "below-card",
                                        frame = FrameSpec(w = 400.0, h = 200.0),
                                        shape = ShapePayload(geometry = ShapeGeometry.Rect),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val probeDeck = buildDocumentStoryboard(
            session = RenderSession.forStill(probeDoc),
            config = DocumentRenderConfig(themeName = "dark"),
        )

        lateinit var animatic: Animatic
        lateinit var scope: CoroutineScope
        val siblingTops = mutableListOf<Float>()

        setContent {
            scope = rememberCoroutineScope()
            Box(Modifier.width(960.dp).height(540.dp)) {
                animatic = rememberAnimatic(storyboard = { probeDeck })
                Easel(animatic)
            }
        }
        mainClock.advanceTimeBy(200)

        fun belowTop(): Float = onNodeWithTag("doc-node-below-card").fetchSemanticsNode().boundsInRoot.top

        // 1. Expanding 1 line -> multi-line (Beat 0 -> Beat 1)
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
        assertTrue(totalShift > 20f, "Expected 1 -> multi-line description to shift card down (>20px), got $startTop -> $endTop")
        val maxExpandStep = siblingTops.zipWithNext { a, b -> abs(b - a) }.max()
        assertTrue(
            maxExpandStep <= totalShift * 0.25f,
            "Card below MagicText v2 snapped by $maxExpandStep px (of $totalShift px total) on expand transition: $siblingTops",
        )

        // 2. Shrinking multi-line -> 1 line (Beat 1 -> Beat 2)
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
        assertTrue(shrinkShift > 20f, "Expected multi-line -> 1 line description to shift card up (>20px), got $shrinkStart -> $shrinkEnd")
        val maxShrinkStep = shrinkTops.zipWithNext { a, b -> abs(b - a) }.max()
        assertTrue(
            maxShrinkStep <= shrinkShift * 0.28f,
            "Card below MagicText v2 snapped by $maxShrinkStep px (of $shrinkShift px total) on shrink transition: $shrinkTops",
        )
    }
}
