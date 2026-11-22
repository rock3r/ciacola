package dev.sebastiano.ciacola.document.render

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.Button
import androidx.compose.material.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runDesktopComposeUiTest
import dev.bnorm.storyboard.AdvanceDirection
import dev.sebastiano.ciacola.document.BeatSpec
import dev.sebastiano.ciacola.document.DeckDocument
import dev.sebastiano.ciacola.document.FrameSpec
import dev.sebastiano.ciacola.document.NodeSpec
import dev.sebastiano.ciacola.document.RenderSession
import dev.sebastiano.ciacola.document.SlideSpec
import dev.sebastiano.ciacola.document.SpecimenPayload
import dev.sebastiano.ciacola.document.deckSpecimens
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests the interactive keyboard-capture policy with a focused specimen button, text field, and IME / text input.
 *
 * Covers every state transition:
 * - Non-captured (even when a focusable button or text field inside a specimen holds Compose focus):
 *   Right/Down/Space/Enter advance forward, Left/Up/Backspace advance backward, PageUp/PageDown navigate.
 * - Focused `"interactive": "keyboard"` node: Enter enters interactive capture (`isCaptured == true`);
 *   Present-mode single click enters capture; Edit-mode single click selects without capturing.
 * - Captured (`"interactive": "keyboard"`): typing, arrows, Space, Enter, Backspace, and text/IME
 *   input pass through to the specimen without navigating the deck; `Esc` exits capture;
 *   `PageUp` and `PageDown` ALWAYS navigate the deck and auto-exit capture on slide change.
 * - Non-keyboard (`"pointer"` or `null`) nodes never enter keyboard capture on Enter.
 */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalTestApi::class)
class DocumentKeyboardPolicyTest {

    @Test
    fun controllerStateMachineCoversAllCaptureTransitions() {
        val controller = DocumentKeyboardCaptureController()
        val navEvents = mutableListOf<AdvanceDirection>()
        val navigate: (AdvanceDirection) -> Unit = { navEvents += it }

        // Helper dispatching synthetic key events via internal policy helper
        fun press(key: Key): Boolean = dispatchPolicyKey(controller, key, navigate)

        // 1. Default non-captured (no interactive focus): all navigation keys navigate the deck.
        assertFalse(controller.isCaptured)
        assertTrue(press(Key.DirectionRight))
        assertTrue(press(Key.DirectionDown))
        assertTrue(press(Key.Spacebar))
        assertTrue(press(Key.Enter))
        assertTrue(press(Key.DirectionLeft))
        assertTrue(press(Key.DirectionUp))
        assertTrue(press(Key.Backspace))
        assertTrue(press(Key.PageDown))
        assertTrue(press(Key.PageUp))
        assertEquals(
            listOf(
                AdvanceDirection.Forward,
                AdvanceDirection.Forward,
                AdvanceDirection.Forward,
                AdvanceDirection.Forward,
                AdvanceDirection.Backward,
                AdvanceDirection.Backward,
                AdvanceDirection.Backward,
                AdvanceDirection.Forward,
                AdvanceDirection.Backward,
            ),
            navEvents,
        )
        navEvents.clear()

        // 2. Non-keyboard specimen never registers as keyboard-focused: Enter STILL navigates forward.
        assertNull(controller.focusedKeyboardNodeId)
        assertTrue(press(Key.Enter))
        assertFalse(controller.isCaptured)
        assertEquals(listOf(AdvanceDirection.Forward), navEvents)
        navEvents.clear()

        // 3. Focused "keyboard" specimen: Enter enters capture without advancing the deck.
        controller.onKeyboardNodeFocusChanged("s1", "kb-node", hasFocus = true)
        assertEquals("kb-node", controller.focusedKeyboardNodeId)
        assertTrue(press(Key.Enter))
        assertTrue(controller.isCaptured)
        assertEquals("kb-node", controller.capturedNodeId)
        assertTrue(navEvents.isEmpty(), "Entering capture on Enter must NOT advance the deck")

        // 4. While captured: arrows, Space, Enter, Backspace, and character keys pass through (not consumed).
        assertFalse(press(Key.DirectionRight))
        assertFalse(press(Key.DirectionLeft))
        assertFalse(press(Key.DirectionUp))
        assertFalse(press(Key.DirectionDown))
        assertFalse(press(Key.Spacebar))
        assertFalse(press(Key.Enter))
        assertFalse(press(Key.Backspace))
        assertFalse(press(Key.A))
        assertTrue(navEvents.isEmpty(), "Specimen-bound keys must not navigate deck while captured")

        // 5. While captured: PageDown / PageUp ALWAYS navigate the deck!
        assertTrue(press(Key.PageDown))
        assertTrue(press(Key.PageUp))
        assertEquals(listOf(AdvanceDirection.Forward, AdvanceDirection.Backward), navEvents)
        navEvents.clear()

        // 6. Slide change auto-exits capture.
        controller.onActiveSlideChanged("s1")
        assertTrue(controller.isCaptured, "Staying on same slide must keep capture active")
        controller.onActiveSlideChanged("s2")
        assertFalse(controller.isCaptured, "Changing slide must auto-exit capture")

        // 7. Re-enter capture via Present-mode click and exit via Escape.
        controller.enterInteractive("s2", "kb-node")
        assertTrue(controller.isCaptured)
        assertTrue(press(Key.Escape))
        assertFalse(controller.isCaptured)
        assertTrue(navEvents.isEmpty(), "Esc exits capture without navigating")

        // 8. After exiting capture, arrows and Space navigate the deck again.
        assertTrue(press(Key.DirectionRight))
        assertTrue(press(Key.Spacebar))
        assertEquals(listOf(AdvanceDirection.Forward, AdvanceDirection.Forward), navEvents)
    }

    @Test
    fun liveComposeFocusedButtonAndTextFieldWithKeyboardPolicy() = runDesktopComposeUiTest(
        width = 480,
        height = 300,
    ) {
        val controller = DocumentKeyboardCaptureController()
        val navEvents = mutableListOf<AdvanceDirection>()
        var buttonClicks = 0
        var textFieldValue by mutableStateOf("hi")
        val buttonFocus = FocusRequester()
        val fieldFocus = FocusRequester()

        val registry = deckSpecimens {
            specimen("kb.form") {
                content {
                    Column(Modifier.fillMaxSize()) {
                        Button(
                            onClick = { buttonClicks++ },
                            modifier = Modifier
                                .testTag("spec-btn")
                                .focusRequester(buttonFocus),
                        ) {
                            Text("Go")
                        }
                        BasicTextField(
                            value = textFieldValue,
                            onValueChange = { textFieldValue = it },
                            modifier = Modifier
                                .testTag("spec-field")
                                .focusRequester(fieldFocus),
                        )
                    }
                }
            }
            specimen("ptr.form") {
                content {
                    Button(
                        onClick = { buttonClicks++ },
                        modifier = Modifier.testTag("ptr-btn"),
                    ) {
                        Text("Ptr")
                    }
                }
            }
        }

        val doc = DeckDocument(
            id = "kb-policy-test",
            stage = compactStage(480, 300),
            slides = listOf(
                SlideSpec(
                    id = "s1",
                    beats = listOf(BeatSpec()),
                    nodes = listOf(
                        NodeSpec(
                            id = "kb-spec",
                            frame = FrameSpec(x = 20.0, y = 20.0, w = 220.0, h = 180.0),
                            interactive = "keyboard",
                            specimen = SpecimenPayload(ref = "kb.form"),
                        ),
                        NodeSpec(
                            id = "ptr-spec",
                            frame = FrameSpec(x = 260.0, y = 20.0, w = 180.0, h = 100.0),
                            interactive = "pointer",
                            specimen = SpecimenPayload(ref = "ptr.form"),
                        ),
                    ),
                ),
            ),
        )
        val session = RenderSession.forPresent(documentProvider = { doc })
        var editMode by mutableStateOf(false)
        val selectedNodes = mutableListOf<Pair<String, String>>()

        setContent {
            val sb = rememberDocumentStoryboard(
                session = session,
                config = DocumentRenderConfig(
                    baseDensity = 1f,
                    specimens = registry,
                    isEditMode = editMode,
                    keyboardController = controller,
                    onNodeClick = { s, n -> selectedNodes += s to n },
                    fontsDir = bundledFontsDirPath(),
                ),
            )
            val animatic = dev.bnorm.storyboard.easel.rememberAnimatic(storyboard = { sb })
            androidx.compose.foundation.layout.Box(
                modifier = Modifier
                    .fillMaxSize()
                    .documentStageKeyboardNavigation(
                        controller = controller,
                        onAdvance = { navEvents += it },
                    ),
            ) {
                dev.bnorm.storyboard.easel.Easel(animatic, mode = dev.bnorm.storyboard.easel.SceneMode.Preview)
            }
        }

        waitForIdle()

        // Focus button inside "pointer" specimen: deck navigation owns Right/Space/Enter!
        onNodeWithTag("ptr-btn").requestFocus()
        waitForIdle()
        onNodeWithTag("ptr-btn").performKeyInput {
            pressKey(Key.DirectionRight)
            pressKey(Key.Spacebar)
            pressKey(Key.DirectionLeft)
            pressKey(Key.Backspace)
        }
        waitForIdle()
        assertFalse(controller.isCaptured)
        assertEquals(
            listOf(
                AdvanceDirection.Forward,
                AdvanceDirection.Forward,
                AdvanceDirection.Backward,
                AdvanceDirection.Backward,
            ),
            navEvents,
        )
        assertEquals(0, buttonClicks, "Deck nav must intercept Space/Enter before non-captured specimen button")
        navEvents.clear()

        // Focus TextField inside "keyboard" specimen (not yet captured): Right navigates, Enter captures!
        onNodeWithTag("spec-field").requestFocus()
        waitForIdle()
        assertEquals("kb-spec", controller.focusedKeyboardNodeId)
        assertFalse(controller.isCaptured)
        onNodeWithTag("spec-field").performKeyInput {
            pressKey(Key.DirectionRight)
        }
        waitForIdle()
        assertEquals(listOf(AdvanceDirection.Forward), navEvents)
        navEvents.clear()

        // Press Enter while focused on keyboard specimen -> enters interactive capture!
        onNodeWithTag("spec-field").performKeyInput {
            pressKey(Key.Enter)
        }
        waitForIdle()
        assertTrue(controller.isCaptured, "Enter on focused keyboard specimen must enter capture")
        assertTrue(navEvents.isEmpty(), "Enter that activates capture must not advance deck")

        // While captured: typing & text/IME input goes to BasicTextField, arrows don't navigate deck.
        onNodeWithTag("spec-field").performTextInput("!")
        onNodeWithTag("spec-field").performKeyInput {
            pressKey(Key.DirectionLeft)
            pressKey(Key.Spacebar)
        }
        waitForIdle()
        assertTrue(textFieldValue.contains("!"), "Captured BasicTextField must receive text/IME input (got '$textFieldValue')")
        assertTrue(navEvents.isEmpty(), "Captured arrows/Space must not navigate deck")

        // PageDown while captured ALWAYS navigates the deck!
        onNodeWithTag("spec-field").performKeyInput {
            pressKey(Key.PageDown)
            pressKey(Key.PageUp)
        }
        waitForIdle()
        assertEquals(listOf(AdvanceDirection.Forward, AdvanceDirection.Backward), navEvents)
        navEvents.clear()

        // Esc exits capture -> arrows navigate deck again.
        onNodeWithTag("spec-field").performKeyInput {
            pressKey(Key.Escape)
        }
        waitForIdle()
        assertFalse(controller.isCaptured, "Esc must exit capture")
        onNodeWithTag("spec-field").performKeyInput {
            pressKey(Key.DirectionRight)
        }
        waitForIdle()
        assertEquals(listOf(AdvanceDirection.Forward), navEvents)
        navEvents.clear()

        // Edit mode: single click selects node without capturing!
        editMode = true
        waitForIdle()
        onNodeWithTag("doc-node-kb-spec").performClick()
        waitForIdle()
        assertFalse(controller.isCaptured, "Edit-mode single click must select node, not enter capture")
        assertTrue(selectedNodes.any { it == ("s1" to "kb-spec") }, "Edit-mode click must report node selection")
    }

    private fun dispatchPolicyKey(
        controller: DocumentKeyboardCaptureController,
        key: Key,
        onNavigate: (AdvanceDirection) -> Unit,
    ): Boolean = controller.handlePreviewKey(
        key = key,
        type = KeyEventType.KeyDown,
        onAdvance = onNavigate,
    )
}
