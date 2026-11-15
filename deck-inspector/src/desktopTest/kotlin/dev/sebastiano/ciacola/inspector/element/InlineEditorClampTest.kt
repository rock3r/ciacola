package dev.sebastiano.ciacola.inspector.element

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.sebastiano.ciacola.effects.DeckEditableText
import dev.sebastiano.ciacola.inspector.LocalElementOverrides
import dev.sebastiano.ciacola.theme.DeckThemeProvider
import dev.sebastiano.ciacola.theme.DeckThemes
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class InlineEditorClampTest {

    @Test
    fun pureOffsetClampingKeepsPopoverInsideStageForRightAndBottomElements() {
        val stageW = 1920f
        val stageH = 1080f
        val popover = Size(640f, 280f)

        // Right-column element near right edge of 1920px stage.
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

        // Bottom-third element near bottom-right corner of 1920x1080 stage -> must clamp inside.
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
    fun rightColumnAndBottomThirdPopoversStayStrictlyInsideStageAndBlockSpacebarLeak() = runComposeUiTest {
        val dir = Files.createTempDirectory("ciacola-inline-clamp")
        val store = ElementOverridesStore(dir)
        val controller = ElementEditorController(store).apply { editorActive = true }
        var leakedSpaceEvents = 0

        setContent {
            DeckThemeProvider(DeckThemes.stageDark()) {
                CompositionLocalProvider(LocalElementOverrides provides store.data().also { store.revision }) {
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
                        TestScaledStage(size = IntSize(1920, 1080), density = Density(2f)) {
                            controller.stageDecorator.decorate {
                                Box(Modifier.fillMaxSize()) {
                                    // Right-column element near right edge of 1920x1080 (960dp x 540dp at 2x)
                                    DeckEditableText(
                                        elementId = "s2:right-fact",
                                        text = "Right Column Fact",
                                        color = Color.White,
                                        modifier = Modifier
                                            .offset(x = 760.dp, y = 120.dp)
                                            .width(180.dp),
                                    )
                                    // Bottom-third element near bottom-right of 1920x1080
                                    DeckEditableText(
                                        elementId = "s2:bottom-caption",
                                        text = "Bottom Third Caption",
                                        color = Color.White,
                                        modifier = Modifier
                                            .offset(x = 620.dp, y = 470.dp)
                                            .width(300.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        waitForIdle()

        val stageRect = onNodeWithTag("test-stage-viewport").fetchSemanticsNode().boundsInRoot

        // 1. Right-column element: popover + Cancel + Apply buttons stay inside stage and Apply clicks by tag.
        runOnIdle { controller.startInlineEdit("s2:right-fact") }
        waitForIdle()

        val rightPopover = onNodeWithTag("ciacola-inline-stage-editor").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val rightCancel = onNodeWithTag("ciacola-inline-stage-editor-cancel").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val rightSave = onNodeWithTag("ciacola-inline-stage-editor-save").assertIsDisplayed().fetchSemanticsNode().boundsInRoot

        assertBoundsInside(rightPopover, stageRect, "right-column popover")
        assertBoundsInside(rightCancel, stageRect, "right-column Cancel button")
        assertBoundsInside(rightSave, stageRect, "right-column Apply button")

        // Spacebar inside inline editor must NEVER leak to outer StoryNavigation onKeyEvent.
        onNodeWithTag("ciacola-inline-stage-editor-input").performKeyInput {
            pressKey(Key.Spacebar)
            pressKey(Key.Spacebar)
        }
        waitForIdle()
        assertEquals(0, leakedSpaceEvents, "Spacebar must not leak from inline editor to outer navigation handler")

        // Apply button is directly clickable by testTag (no Tooltip wrapper obstructing it).
        onNodeWithTag("ciacola-inline-stage-editor-input").performTextReplacement("Clamped Right Edit")
        onNodeWithTag("ciacola-inline-stage-editor-save").performClick()
        waitForIdle()
        assertNull(controller.inlineEditingElementId)
        assertEquals("Clamped Right Edit", store.patchFor("s2:right-fact")?.text)

        // 2. Bottom-third element: popover + buttons must clamp strictly inside stage.
        runOnIdle { controller.startInlineEdit("s2:bottom-caption") }
        waitForIdle()

        val bottomPopover = onNodeWithTag("ciacola-inline-stage-editor").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val bottomCancel = onNodeWithTag("ciacola-inline-stage-editor-cancel").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val bottomSave = onNodeWithTag("ciacola-inline-stage-editor-save").assertIsDisplayed().fetchSemanticsNode().boundsInRoot

        assertBoundsInside(bottomPopover, stageRect, "bottom-third popover")
        assertBoundsInside(bottomCancel, stageRect, "bottom-third Cancel button")
        assertBoundsInside(bottomSave, stageRect, "bottom-third Apply button")

        onNodeWithTag("ciacola-inline-stage-editor-cancel").performClick()
        waitForIdle()
        assertNull(controller.inlineEditingElementId)
    }

    private fun assertBoundsInside(inner: Rect, outer: Rect, name: String) {
        val eps = 1f
        assertTrue(
            inner.left >= outer.left - eps &&
                inner.top >= outer.top - eps &&
                inner.right <= outer.right + eps &&
                inner.bottom <= outer.bottom + eps,
            "Expected $name ($inner) to stay strictly inside stage bounds ($outer)",
        )
    }
}

@Composable
private fun TestScaledStage(
    size: IntSize,
    density: Density,
    content: @Composable () -> Unit,
) {
    Layout(
        content = {
            CompositionLocalProvider(LocalDensity provides density) {
                Box { content() }
            }
        },
    ) { measurables, constraints ->
        val scale = minOf(
            constraints.maxWidth.toFloat() / size.width,
            constraints.maxHeight.toFloat() / size.height,
        )
        val layoutW = (size.width * scale).toInt()
        val layoutH = (size.height * scale).toInt()
        val placeable = measurables[0].measure(Constraints.fixed(size.width, size.height))
        layout(layoutW, layoutH) {
            placeable.placeWithLayer((layoutW - size.width) / 2, (layoutH - size.height) / 2) {
                scaleX = scale
                scaleY = scale
            }
        }
    }
}
