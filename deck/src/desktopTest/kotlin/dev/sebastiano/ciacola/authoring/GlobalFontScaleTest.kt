package dev.sebastiano.ciacola.authoring

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import dev.bnorm.storyboard.easel.Easel
import dev.bnorm.storyboard.easel.rememberAnimatic
import dev.sebastiano.ciacola.createLoadedTestDeckStore
import dev.sebastiano.ciacola.document.BeatSpec
import dev.sebastiano.ciacola.document.CodePayload
import dev.sebastiano.ciacola.document.ContainerPayload
import dev.sebastiano.ciacola.document.FrameSpec
import dev.sebastiano.ciacola.document.NodeSpec
import dev.sebastiano.ciacola.document.PaddingSpec
import dev.sebastiano.ciacola.document.RenderSession
import dev.sebastiano.ciacola.document.RichTextValue
import dev.sebastiano.ciacola.document.SlideSpec
import dev.sebastiano.ciacola.document.TextPayload
import dev.sebastiano.ciacola.document.render.DocumentRenderConfig
import dev.sebastiano.ciacola.document.render.rememberDocumentStoryboard
import dev.sebastiano.ciacola.loadFoundationTestDocument
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class GlobalFontScaleTest {

    @Test
    fun globalFontScaleViaDocumentRenderConfigScalesTextAndWarpCodePrimitivesUniformly() = runComposeUiTest {
        var scale by mutableFloatStateOf(1.0f)
        val baseDoc = loadFoundationTestDocument()
        val doc = baseDoc.copy(
            id = "gfs-probe",
            title = "Global Font Scale Probe",
            slides = listOf(
                SlideSpec(
                    id = "s1",
                    beats = listOf(BeatSpec()),
                    nodes = listOf(
                        NodeSpec(
                            id = "col",
                            frame = FrameSpec(x = 0.0, y = 0.0, w = 1920.0, h = 1080.0),
                            column = ContainerPayload(
                                gap = 32.0,
                                padding = PaddingSpec(left = 48.0, top = 48.0, right = 48.0, bottom = 48.0),
                                children = listOf(
                                    NodeSpec(
                                        id = "gfs-heading",
                                        text = TextPayload(
                                            value = RichTextValue.Plain("Editable Headline"),
                                            style = "heading",
                                            color = "foreground",
                                        ),
                                    ),
                                    NodeSpec(
                                        id = "gfs-body",
                                        text = TextPayload(
                                            value = RichTextValue.Plain("Inline Override Copy"),
                                            style = "body",
                                            size = 72.0,
                                            lineHeight = 88.0,
                                            color = "foreground",
                                        ),
                                    ),
                                    NodeSpec(
                                        id = "gfs-warp",
                                        code = CodePayload(
                                            value = "val answer = 42\nprintln(answer)",
                                            language = "kotlin",
                                        ),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val store = createLoadedTestDeckStore(doc)

        setContent {
            val session = remember { RenderSession.forPresent(store, freeClockMillis = { 0L }) }
            val storyboard = rememberDocumentStoryboard(
                session = session,
                config = DocumentRenderConfig(
                    themeName = "dark",
                    fontScale = scale,
                ),
            )
            Box(modifier = Modifier.size(960.dp, 540.dp)) {
                Easel(rememberAnimatic(storyboard = { storyboard }))
            }
        }
        waitForIdle()

        val tags = listOf("doc-node-gfs-heading", "doc-node-gfs-body")
        val baselineHeights = tags.associateWith { tag ->
            onNodeWithTag(tag).getUnclippedBoundsInRoot().height.value
        }
        val warpBaseHeight = onNodeWithTag("doc-node-gfs-warp").getUnclippedBoundsInRoot().height.value

        runOnIdle { scale = 1.18f }
        waitForIdle()

        for (tag in tags) {
            val baseH = baselineHeights.getValue(tag)
            val scaledH = onNodeWithTag(tag).getUnclippedBoundsInRoot().height.value
            val expected = baseH * 1.18f
            assertTrue(
                abs(scaledH - expected) <= 2.5f,
                "Expected '$tag' height to scale from ${baseH}dp to ~${expected}dp at 1.18x (actual=${scaledH}dp)",
            )
        }

        // DeckWarpCodeCard includes fixed dp card padding (36dp * 2 = 72dp), while inner text lines scale 1.18x.
        val warpScaledHeight = onNodeWithTag("doc-node-gfs-warp").getUnclippedBoundsInRoot().height.value
        val warpPaddingDp = 72f
        val warpInnerBase = (warpBaseHeight - warpPaddingDp).coerceAtLeast(1f)
        val warpInnerScaled = (warpScaledHeight - warpPaddingDp).coerceAtLeast(1f)
        val warpExpectedInner = warpInnerBase * 1.18f
        assertTrue(
            abs(warpInnerScaled - warpExpectedInner) <= 3.5f,
            "Expected DeckWarpCodeCard inner text height to scale from ${warpInnerBase}dp to ~${warpExpectedInner}dp (actual=${warpInnerScaled}dp)",
        )
    }
}
