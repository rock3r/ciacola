package dev.sebastiano.ciacola.inspector.element

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performKeyPress
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.unit.dp
import dev.sebastiano.ciacola.document.BeatSpec
import dev.sebastiano.ciacola.document.DeckDocument
import dev.sebastiano.ciacola.document.DeckDocumentStore
import dev.sebastiano.ciacola.document.DeckLoadState
import dev.sebastiano.ciacola.document.DeckThemeSpec
import dev.sebastiano.ciacola.document.FrameSpec
import dev.sebastiano.ciacola.document.NodeSpec
import dev.sebastiano.ciacola.document.RichTextValue
import dev.sebastiano.ciacola.document.ShapeGeometry
import dev.sebastiano.ciacola.document.ShapePayload
import dev.sebastiano.ciacola.document.SlideSpec
import dev.sebastiano.ciacola.document.TextPayload
import dev.sebastiano.ciacola.ui.CiacolaJewelTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class, InternalComposeUiApi::class, ExperimentalComposeUiApi::class)
class InlineEditorClampTest {

    private fun newHeldEnterController(): DocumentElementInspectorController {
        val doc = DeckDocument(
            id = "held-enter-test",
            title = "Held Enter Test",
            themes = mapOf("dark" to DeckThemeSpec()),
            defaultTheme = "dark",
            slides = listOf(
                SlideSpec(
                    id = "s1",
                    beats = listOf(BeatSpec()),
                    nodes = listOf(
                        NodeSpec(
                            id = "s1:headline",
                            frame = FrameSpec(x = 120.0, y = 120.0, w = 420.0, h = 80.0),
                            text = TextPayload(value = RichTextValue.Plain("Initial")),
                        ),
                    ),
                ),
            ),
        )
        return DocumentElementInspectorController(DeckDocumentStore(doc, initialLoad = DeckLoadState.Loaded))
    }

    private fun ComposeUiTest.mountHeldEnterStage(controller: DocumentElementInspectorController) {
        val stageFocusRequester = FocusRequester()
        setContent {
            CiacolaJewelTheme(isDark = true) {
                Box(
                    modifier = Modifier
                        .size(960.dp, 540.dp)
                        .focusRequester(stageFocusRequester)
                        .focusable()
                        .onKeyEvent { ev -> DocumentElementInspectorKeys.handleKeyEvent(controller, ev, allowInlineEnter = true) }
                        .testTag("test-stage-root"),
                ) {
                    DocumentStageDirectEditOverlay(
                        controller = controller,
                        stageScale = 0.5f,
                        onRestoreStageFocus = { runCatching { stageFocusRequester.requestFocus() } },
                    )
                }
            }
        }
        waitForIdle()
        runOnIdle { controller.selectNode("s1:headline") }
        waitForIdle()
        onNodeWithTag("test-stage-root").requestFocus()
        waitForIdle()
    }

    private fun ComposeUiTest.elapse(ms: Long) {
        mainClock.advanceTimeBy(ms)
        waitForIdle()
    }

    private fun ComposeUiTest.holdAndCountTransitions(
        controller: DocumentElementInspectorController,
        initialDelayMs: Long,
        repeatIntervalMs: Long,
        repeats: Int,
    ): Pair<Int, Int> {
        onNodeWithTag("test-stage-root").performKeyInput { keyDown(Key.Enter) }
        waitForIdle()
        assertEquals("s1:headline", controller.inlineEditingNodeId, "Enter must open the editor")
        onNodeWithTag("ciacola-doc-inline-text-field").assertIsFocused()

        var closes = 0
        var reopens = 0
        var wasEditing = true
        elapse(initialDelayMs)
        repeat(repeats) { i ->
            autoRepeatEnter()
            val editing = controller.inlineEditingNodeId != null
            if (wasEditing && !editing) closes++
            if (!wasEditing && editing) reopens++
            wasEditing = editing
            if (i < repeats - 1) elapse(repeatIntervalMs)
        }
        onRoot().performKeyPress(KeyEvent(Key.Enter, KeyEventType.KeyUp))
        waitForIdle()
        return closes to reopens
    }

    private fun ComposeUiTest.autoRepeatEnter() {
        onRoot().performKeyPress(KeyEvent(Key.Enter, KeyEventType.KeyDown))
        waitForIdle()
    }

    private fun ComposeUiTest.pressAndHoldEnterOnStage(controller: DocumentElementInspectorController) {
        onNodeWithTag("test-stage-root").performKeyInput { keyDown(Key.Enter) }
        waitForIdle()
        assertEquals("s1:headline", controller.inlineEditingNodeId, "Expected Enter to open the inline editor")
        onNodeWithTag("ciacola-doc-inline-text-field").assertIsFocused()
    }

    @Test
    fun autoRepeatAfter600msWhileEnterStillHeldDoesNotCommitEditor() = runComposeUiTest {
        val controller = newHeldEnterController()
        mountHeldEnterStage(controller)
        pressAndHoldEnterOnStage(controller)

        elapse(600)
        autoRepeatEnter()

        assertEquals(
            "s1:headline",
            controller.inlineEditingNodeId,
            "Auto-repeat Enter 600 ms into a single held press must not commit or close the editor",
        )
        onNodeWithTag("test-stage-root").performKeyInput { keyUp(Key.Enter) }
    }

    @Test
    fun autoRepeatInsideInitialWindowIsSwallowed() = runComposeUiTest {
        val controller = newHeldEnterController()
        mountHeldEnterStage(controller)
        pressAndHoldEnterOnStage(controller)

        elapse(100)
        autoRepeatEnter()

        assertEquals("s1:headline", controller.inlineEditingNodeId, "Auto-repeat inside initial hold must be swallowed")
        onNodeWithTag("test-stage-root").performKeyInput { keyUp(Key.Enter) }
    }

    @Test
    fun holdingEnterForThreeSecondsNeverCyclesEditor() = runComposeUiTest {
        val controller = newHeldEnterController()
        mountHeldEnterStage(controller)
        pressAndHoldEnterOnStage(controller)

        var closes = 0
        var reopens = 0
        var wasEditing = true
        elapse(375)
        repeat(29) {
            autoRepeatEnter()
            val editing = controller.inlineEditingNodeId != null
            if (wasEditing && !editing) closes++
            if (!wasEditing && editing) reopens++
            wasEditing = editing
            elapse(90)
        }

        assertEquals(
            0,
            closes,
            "One continuous Enter hold closed the editor $closes time(s) and re-opened it $reopens time(s)",
        )
        onNodeWithTag("test-stage-root").performKeyInput { keyUp(Key.Enter) }
    }

    @Test
    fun holdingEnterAcrossCommitPast600msDoesNotReopenEditorUntilKeyUp() = runComposeUiTest {
        val controller = newHeldEnterController()
        mountHeldEnterStage(controller)

        onNodeWithTag("test-stage-root").performKeyInput { pressKey(Key.Enter) }
        waitForIdle()
        assertEquals("s1:headline", controller.inlineEditingNodeId)

        onNodeWithTag("ciacola-doc-inline-text-field").performTextInput("!")
        waitForIdle()

        // Press and hold Enter to commit, then send OS auto-repeats past 600 ms without releasing.
        onRoot().performKeyPress(KeyEvent(Key.Enter, KeyEventType.KeyDown))
        waitForIdle()
        assertNull(controller.inlineEditingNodeId, "Initial Enter press should commit and close inline editor")

        elapse(600)
        autoRepeatEnter()
        assertNull(controller.inlineEditingNodeId, "Held Enter auto-repeat 600 ms after commit must not reopen editor")

        onRoot().performKeyPress(KeyEvent(Key.Enter, KeyEventType.KeyUp))
        waitForIdle()

        onNodeWithTag("test-stage-root").performKeyInput { pressKey(Key.Enter) }
        waitForIdle()
        assertEquals("s1:headline", controller.inlineEditingNodeId, "Fresh Enter after KeyUp should reopen editor")
    }

    @Test
    fun slowRepeatRateHoldNeverCyclesEditor() = runComposeUiTest {
        val controller = newHeldEnterController()
        mountHeldEnterStage(controller)
        val (closes, reopens) = holdAndCountTransitions(
            controller = controller,
            initialDelayMs = 375,
            repeatIntervalMs = 1_350,
            repeats = 4,
        )
        assertEquals(0, closes, "Slow-repeat hold closed the editor $closes time(s), reopened it $reopens time(s)")
    }

    @Test
    fun longInitialDelayHoldDoesNotCommitEditor() = runComposeUiTest {
        val controller = newHeldEnterController()
        mountHeldEnterStage(controller)
        val (closes, reopens) = holdAndCountTransitions(
            controller = controller,
            initialDelayMs = 1_410,
            repeatIntervalMs = 90,
            repeats = 5,
        )
        assertEquals(0, closes, "Long-delay hold closed the editor $closes time(s), reopened it $reopens time(s)")
    }

    @Test
    fun windowBlurClearsEnterAndEscapeGuards() = runComposeUiTest {
        val controller = newHeldEnterController()
        var windowFocused by mutableStateOf(true)
        val stageFocusRequester = FocusRequester()
        setContent {
            CompositionLocalProvider(
                LocalWindowInfo provides object : WindowInfo {
                    override val isWindowFocused: Boolean
                        get() = windowFocused
                },
            ) {
                CiacolaJewelTheme(isDark = true) {
                    Box(
                        modifier = Modifier
                            .size(960.dp, 540.dp)
                            .focusRequester(stageFocusRequester)
                            .focusable()
                            .onKeyEvent { ev -> DocumentElementInspectorKeys.handleKeyEvent(controller, ev, allowInlineEnter = true) }
                            .testTag("test-stage-root"),
                    ) {
                        DocumentStageDirectEditOverlay(
                            controller = controller,
                            stageScale = 0.5f,
                            onRestoreStageFocus = { runCatching { stageFocusRequester.requestFocus() } },
                        )
                    }
                }
            }
        }
        waitForIdle()
        runOnIdle { controller.selectNode("s1:headline") }
        waitForIdle()
        onNodeWithTag("test-stage-root").requestFocus()
        waitForIdle()

        // Orphaned KeyDown without KeyUp keeps Enter guard armed while window remains focused.
        onRoot().performKeyPress(KeyEvent(Key.Enter, KeyEventType.KeyDown))
        waitForIdle()
        assertEquals("s1:headline", controller.inlineEditingNodeId)
        assertTrue(controller.swallowNextEnterKeyUp)
        runOnIdle { controller.swallowNextEscapeKeyUp = true }

        // Window losing focus clears both orphaned key-up guards immediately.
        windowFocused = false
        waitForIdle()
        assertTrue(!controller.swallowNextEnterKeyUp, "Expected window blur to clear swallowNextEnterKeyUp")
        assertTrue(!controller.swallowNextEscapeKeyUp, "Expected window blur to clear swallowNextEscapeKeyUp")

        // After refocusing, a single Enter press commits cleanly without needing an extra KeyUp.
        windowFocused = true
        waitForIdle()
        onNodeWithTag("ciacola-doc-inline-text-field").performKeyInput { pressKey(Key.Enter) }
        waitForIdle()
        assertNull(controller.inlineEditingNodeId)
    }

    @Test
    fun overlayDisposeClearsEnterAndEscapeGuards() = runComposeUiTest {
        val controller = newHeldEnterController()
        var showOverlay by mutableStateOf(true)
        setContent {
            CiacolaJewelTheme(isDark = true) {
                Box(Modifier.size(960.dp, 540.dp)) {
                    if (showOverlay) {
                        DocumentStageDirectEditOverlay(controller = controller, stageScale = 0.5f)
                    }
                }
            }
        }
        waitForIdle()
        runOnIdle {
            controller.swallowNextEnterKeyUp = true
            controller.swallowNextEscapeKeyUp = true
        }
        waitForIdle()
        assertTrue(controller.swallowNextEnterKeyUp, "Guard stays armed while the overlay is mounted")

        showOverlay = false
        waitForIdle()
        assertTrue(!controller.swallowNextEnterKeyUp, "Overlay dispose must clear swallowNextEnterKeyUp")
        assertTrue(!controller.swallowNextEscapeKeyUp, "Overlay dispose must clear swallowNextEscapeKeyUp")
    }

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
        val store = DeckDocumentStore(doc, initialLoad = DeckLoadState.Loaded)
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

    @Test
    fun enterOnSelectedTextNodeOpensInlineEditorAndEnterCmdEnterEscRestoreStageFocusWithoutLeaks() = runComposeUiTest {
        val doc = DeckDocument(
            id = "enter-edit-doc",
            title = "Enter Edit Doc",
            themes = mapOf("dark" to DeckThemeSpec()),
            defaultTheme = "dark",
            slides = listOf(
                SlideSpec(
                    id = "s1",
                    beats = listOf(BeatSpec()),
                    nodes = listOf(
                        NodeSpec(
                            id = "s1:headline",
                            frame = FrameSpec(x = 120.0, y = 120.0, w = 420.0, h = 80.0),
                            text = TextPayload(value = RichTextValue.Plain("Initial")),
                        ),
                        NodeSpec(
                            id = "s1:shape",
                            frame = FrameSpec(x = 120.0, y = 260.0, w = 200.0, h = 80.0),
                            shape = ShapePayload(geometry = ShapeGeometry.Rect),
                        ),
                    ),
                ),
            ),
        )
        val store = DeckDocumentStore(doc, initialLoad = DeckLoadState.Loaded)
        val controller = DocumentElementInspectorController(store)
        val stageFocusRequester = FocusRequester()
        var leakedStageKeys = 0
        var restoreCalls = 0

        setContent {
            CiacolaJewelTheme(isDark = true) {
                Box(
                    modifier = Modifier
                        .size(960.dp, 540.dp)
                        .focusRequester(stageFocusRequester)
                        .focusable()
                        .onKeyEvent { ev ->
                            if (DocumentElementInspectorKeys.handleKeyEvent(controller, ev, allowInlineEnter = true)) {
                                return@onKeyEvent true
                            }
                            if (ev.key == Key.Enter || ev.key == Key.NumPadEnter || ev.key == Key.Escape) {
                                leakedStageKeys++
                                true
                            } else {
                                false
                            }
                        }
                        .testTag("test-stage-root"),
                ) {
                    DocumentStageDirectEditOverlay(
                        controller = controller,
                        stageScale = 0.5f,
                        onRestoreStageFocus = {
                            restoreCalls++
                            runCatching { stageFocusRequester.requestFocus() }
                        },
                    )
                }
            }
        }
        waitForIdle()

        // Selecting a non-text node and pressing Enter must not open the inline editor.
        runOnIdle { controller.selectNode("s1:shape") }
        waitForIdle()
        onNodeWithTag("test-stage-root").requestFocus()
        waitForIdle()
        onNodeWithTag("test-stage-root").performKeyInput {
            pressKey(Key.Enter)
        }
        waitForIdle()
        assertNull(controller.inlineEditingNodeId)

        // Selecting a single editable text node and pressing Enter opens the inline editor & focuses field.
        leakedStageKeys = 0
        runOnIdle { controller.selectNode("s1:headline") }
        waitForIdle()
        onNodeWithTag("test-stage-root").requestFocus()
        waitForIdle()
        onNodeWithTag("test-stage-root").performKeyInput {
            pressKey(Key.Enter)
        }
        waitForIdle()
        assertEquals("s1:headline", controller.inlineEditingNodeId)
        onNodeWithTag("ciacola-doc-inline-text-popover").assertIsDisplayed()
        onNodeWithTag("ciacola-doc-inline-text-field").assertIsFocused()

        // Plain Enter commits cleanly without leaking and deterministically restores stage focus.
        onNodeWithTag("ciacola-doc-inline-text-field").performTextInput(" A")
        waitForIdle()
        onNodeWithTag("ciacola-doc-inline-text-field").performKeyInput {
            pressKey(Key.Enter)
        }
        waitForIdle()
        assertTrue(store.lastRejectedEditErrors.isEmpty(), "Unexpected rejected edit errors: ${store.lastRejectedEditErrors}")
        assertNull(controller.inlineEditingNodeId)
        assertEquals("Initial A", store.document.slides.first().nodes.first { it.id == "s1:headline" }.text?.value?.plainText)
        assertTrue(restoreCalls >= 1, "Expected onRestoreStageFocus on Enter commit")
        onNodeWithTag("test-stage-root").assertIsFocused()
        assertEquals(0, leakedStageKeys, "Enter open/commit must not leak Enter keys to stage")

        // Re-open with Enter, cancel with Esc -> text unchanged, no Esc leak, stage focus restored.
        val restoresBeforeEsc = restoreCalls
        onNodeWithTag("test-stage-root").performKeyInput {
            pressKey(Key.Enter)
        }
        waitForIdle()
        assertEquals("s1:headline", controller.inlineEditingNodeId)
        onNodeWithTag("ciacola-doc-inline-text-field").performTextInput(" UNSAVED")
        waitForIdle()
        onNodeWithTag("ciacola-doc-inline-text-field").performKeyInput {
            pressKey(Key.Escape)
        }
        waitForIdle()
        assertNull(controller.inlineEditingNodeId)
        assertEquals("Initial A", store.document.slides.first().nodes.first { it.id == "s1:headline" }.text?.value?.plainText)
        assertTrue(restoreCalls > restoresBeforeEsc, "Expected onRestoreStageFocus on Escape cancel")
        onNodeWithTag("test-stage-root").assertIsFocused()
        assertEquals(0, leakedStageKeys, "Escape cancel must not leak Escape to stage")

        // Re-open with Enter, commit with Cmd+Enter -> committed and stage focus restored.
        val restoresBeforeCmdEnter = restoreCalls
        onNodeWithTag("test-stage-root").performKeyInput {
            pressKey(Key.Enter)
        }
        waitForIdle()
        onNodeWithTag("ciacola-doc-inline-text-field").performTextInput(" B")
        waitForIdle()
        onNodeWithTag("ciacola-doc-inline-text-field").performKeyInput {
            withKeyDown(Key.MetaLeft) {
                pressKey(Key.Enter)
            }
        }
        waitForIdle()
        assertNull(controller.inlineEditingNodeId)
        assertEquals("Initial A B", store.document.slides.first().nodes.first { it.id == "s1:headline" }.text?.value?.plainText)
        assertTrue(restoreCalls > restoresBeforeCmdEnter, "Expected onRestoreStageFocus on Cmd+Enter commit")
        onNodeWithTag("test-stage-root").assertIsFocused()
        assertEquals(0, leakedStageKeys, "Cmd+Enter commit must not leak Enter to stage")

        // Holding Enter across opening keeps swallowNextEnterKeyUp armed until KeyUp, then fresh Enter commits cleanly.
        onNodeWithTag("test-stage-root").performKeyInput {
            keyDown(Key.Enter)
        }
        waitForIdle()
        assertEquals("s1:headline", controller.inlineEditingNodeId)
        assertTrue(controller.swallowNextEnterKeyUp, "Expected swallowNextEnterKeyUp armed while Enter stays held")
        onNodeWithTag("ciacola-doc-inline-text-field").performKeyInput {
            keyUp(Key.Enter)
        }
        waitForIdle()
        assertEquals("s1:headline", controller.inlineEditingNodeId)
        onNodeWithTag("ciacola-doc-inline-text-field").performKeyInput {
            pressKey(Key.Enter)
        }
        waitForIdle()
        assertNull(controller.inlineEditingNodeId)
    }

    @Test
    fun disablingOverlayOrBlockingProviderMidEditCancelsActiveSessionWithoutStealingFocus() = runComposeUiTest {
        val controller = newHeldEnterController()
        var overlayEnabled by mutableStateOf(true)
        var editBlocked by mutableStateOf(false)
        var restoreCalls = 0
        val stageFocusRequester = FocusRequester()

        setContent {
            CiacolaJewelTheme(isDark = true) {
                Box(
                    modifier = Modifier
                        .size(960.dp, 540.dp)
                        .focusRequester(stageFocusRequester)
                        .focusable()
                        .onKeyEvent { ev -> DocumentElementInspectorKeys.handleKeyEvent(controller, ev, allowInlineEnter = true) }
                        .testTag("test-stage-root"),
                ) {
                    DocumentStageDirectEditOverlay(
                        controller = controller,
                        stageScale = 0.5f,
                        enabled = overlayEnabled,
                        inlineEditBlockedProvider = { editBlocked },
                        onRestoreStageFocus = { restoreCalls++ },
                    )
                }
            }
        }
        waitForIdle()

        runOnIdle { controller.selectNode("s1:headline") }
        waitForIdle()
        onNodeWithTag("test-stage-root").requestFocus()
        waitForIdle()

        // Disabling the overlay mid-edit cancels the session and keeps the original text intact.
        onNodeWithTag("test-stage-root").performKeyInput { pressKey(Key.Enter) }
        waitForIdle()
        assertEquals("s1:headline", controller.inlineEditingNodeId)
        onNodeWithTag("ciacola-doc-inline-text-field").performTextInput(" Draft")
        waitForIdle()
        val restoresBeforeDisable = restoreCalls
        runOnIdle { overlayEnabled = false }
        waitForIdle()
        assertNull(controller.inlineEditingNodeId)
        onNodeWithTag("ciacola-doc-inline-text-popover").assertDoesNotExist()
        assertEquals(restoresBeforeDisable, restoreCalls, "Disabling overlay must not yank focus back to stage")
        assertEquals("Initial", controller.store.document.slides.first().nodes.first { it.id == "s1:headline" }.text?.value?.plainText)

        // Blocking provider turning true while enabled also cancels any active inline edit.
        runOnIdle { overlayEnabled = true }
        waitForIdle()
        onNodeWithTag("test-stage-root").requestFocus()
        waitForIdle()
        onNodeWithTag("test-stage-root").performKeyInput { pressKey(Key.Enter) }
        waitForIdle()
        assertEquals("s1:headline", controller.inlineEditingNodeId)
        runOnIdle { editBlocked = true }
        waitForIdle()
        assertNull(controller.inlineEditingNodeId)
        onNodeWithTag("ciacola-doc-inline-text-popover").assertDoesNotExist()
    }
}
