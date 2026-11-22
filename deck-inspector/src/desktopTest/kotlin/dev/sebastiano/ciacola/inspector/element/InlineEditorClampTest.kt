package dev.sebastiano.ciacola.inspector.element

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.sebastiano.ciacola.document.DeckDocument
import dev.sebastiano.ciacola.document.DeckDocumentStore
import dev.sebastiano.ciacola.document.FrameSpec
import dev.sebastiano.ciacola.document.NodeSpec
import dev.sebastiano.ciacola.document.RichTextValue
import dev.sebastiano.ciacola.document.SlideSpec
import dev.sebastiano.ciacola.document.TextPayload
import dev.sebastiano.ciacola.ui.CiacolaJewelTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class InlineEditorClampTest {

    @Test
    fun pureOffsetClampingKeepsPopoverInsideStageForRightAndBottomElements() {
        val stageW = 1920f
        val stageH = 1080f
        val popover = Size(640f, 280f)

        val rightOffset = computeClampedInlineEditorOffset(
            targetBounds = Rect(1620f, 180f, 1890f, 240f),
            popoverSizePx = popover,
            stageWidthPx = stageW,
            stageHeightPx = stageH,
            marginPx = 16f,
        )
        assertTrue(rightOffset.x >= 16f)
        assertTrue(rightOffset.x + popover.width <= stageW - 16f)
        assertTrue(rightOffset.y >= 16f)
        assertTrue(rightOffset.y + popover.height <= stageH - 16f)

        val bottomOffset = computeClampedInlineEditorOffset(
            targetBounds = Rect(1500f, 940f, 1880f, 1030f),
            popoverSizePx = popover,
            stageWidthPx = stageW,
            stageHeightPx = stageH,
            marginPx = 16f,
        )
        assertTrue(bottomOffset.x >= 16f)
        assertTrue(bottomOffset.x + popover.width <= stageW - 16f)
        assertTrue(bottomOffset.y >= 16f)
        assertTrue(bottomOffset.y + popover.height <= stageH - 16f)
    }

    @Test
    fun documentInlinePopoverClampsInsideStageAndBlocksSpacebarLeak() = runComposeUiTest {
        val doc = DeckDocument(
            id = "clamp-doc",
            title = "Clamp Doc",
            slides = listOf(
                SlideSpec(
                    id = "s1",
                    nodes = listOf(
                        NodeSpec(
                            id = "s1:bottom-right",
                            frame = FrameSpec(x = 1720.0, y = 980.0, w = 180.0, h = 60.0),
                            text = TextPayload(value = RichTextValue.Plain("Bottom Right")),
                        ),
                    ),
                ),
            ),
        )
        val store = DeckDocumentStore(doc)
        val controller = DocumentElementInspectorController(store)
        var leakedSpaceEvents = 0

        setContent {
            CiacolaJewelTheme(isDark = true) {
                Box(
                    modifier = Modifier
                        .size(960.dp, 540.dp)
                        .onKeyEvent { ev ->
                            if (ev.type == KeyEventType.KeyDown && ev.key == Key.Spacebar) {
                                leakedSpaceEvents++
                                true
                            } else {
                                false
                            }
                        }
                        .testTag("test-stage-viewport"),
                ) {
                    DocumentStageDirectEditOverlay(
                        controller = controller,
                        stageScale = 0.5f,
                    )
                }
            }
        }
        waitForIdle()

        controller.startInlineTextEdit("s1:bottom-right")
        waitForIdle()

        val viewportBounds = onNodeWithTag("test-stage-viewport").fetchSemanticsNode().boundsInRoot
        val popoverBounds = onNodeWithTag("ciacola-doc-inline-text-popover")
            .assertIsDisplayed()
            .fetchSemanticsNode()
            .boundsInRoot
        assertTrue(
            popoverBounds.left >= viewportBounds.left && popoverBounds.right <= viewportBounds.right + 1f &&
                popoverBounds.top >= viewportBounds.top && popoverBounds.bottom <= viewportBounds.bottom + 1f,
            "Expected clamped popover ($popoverBounds) inside stage viewport ($viewportBounds)",
        )

        onNodeWithTag("ciacola-doc-inline-text-field").performKeyInput {
            pressKey(Key.Spacebar)
        }
        waitForIdle()
        assertEquals(0, leakedSpaceEvents, "Spacebar inside inline text editor must never leak to stage navigation")
    }
}
