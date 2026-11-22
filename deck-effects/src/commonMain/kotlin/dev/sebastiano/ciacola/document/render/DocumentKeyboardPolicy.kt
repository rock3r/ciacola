package dev.sebastiano.ciacola.document.render

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import dev.bnorm.storyboard.AdvanceDirection
import dev.bnorm.storyboard.easel.Animatic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Controller enforcing the keyboard-capture policy for interactive specimen nodes.
 *
 * Policy summary:
 * - Non-captured (default, including `"interactive": "pointer"` and unfocused/unentered
 *   `"interactive": "keyboard"`): deck navigation owns arrows, Space, Enter, Backspace, PageUp,
 *   and PageDown even when a focusable button or text field inside a specimen has Compose focus.
 * - Focused `"interactive": "keyboard"` node: pressing `Enter` (or clicking in Present mode /
 *   double-clicking in Edit mode) enters interactive mode (`isCaptured == true`).
 * - Captured (`"interactive": "keyboard"`): typing, arrows, Space, Enter, Backspace, and IME go to
 *   the specimen; `Esc` exits interactive mode; `PageUp` and `PageDown` ALWAYS advance the deck
 *   and auto-exit interactive mode when the slide changes.
 */
@Stable
class DocumentKeyboardCaptureController(
    isEditMode: Boolean = false,
) {
    var isEditMode: Boolean by mutableStateOf(isEditMode)

    /** Slide ID owning the currently captured `"interactive": "keyboard"` node, or `null`. */
    var capturedSlideId: String? by mutableStateOf(null)
        private set

    /** Node ID of the currently captured `"interactive": "keyboard"` node, or `null`. */
    var capturedNodeId: String? by mutableStateOf(null)
        private set

    /** Currently focused `"interactive": "keyboard"` node (eligible for `Enter` to enter capture). */
    var focusedKeyboardSlideId: String? by mutableStateOf(null)
        private set

    var focusedKeyboardNodeId: String? by mutableStateOf(null)
        private set

    /** Whether a `"interactive": "keyboard"` specimen currently owns keyboard input. */
    val isCaptured: Boolean
        get() = capturedNodeId != null

    fun isNodeCaptured(slideId: String, nodeId: String): Boolean =
        capturedSlideId == slideId && capturedNodeId == nodeId

    /** Enters interactive capture mode for `(slideId, nodeId)`. */
    fun enterInteractive(slideId: String, nodeId: String) {
        capturedSlideId = slideId
        capturedNodeId = nodeId
    }

    /** Exits interactive capture mode back to deck navigation. */
    fun exitInteractive() {
        capturedSlideId = null
        capturedNodeId = null
    }

    /** Records focus state for a node declared with `"interactive": "keyboard"`. */
    fun onKeyboardNodeFocusChanged(slideId: String, nodeId: String, hasFocus: Boolean) {
        if (hasFocus) {
            focusedKeyboardSlideId = slideId
            focusedKeyboardNodeId = nodeId
        } else if (focusedKeyboardSlideId == slideId && focusedKeyboardNodeId == nodeId) {
            focusedKeyboardSlideId = null
            focusedKeyboardNodeId = null
        }
    }

    /** Auto-exits interactive capture whenever the active slide changes. */
    fun onActiveSlideChanged(activeSlideId: String?) {
        if (capturedSlideId != null && capturedSlideId != activeSlideId) {
            exitInteractive()
        }
        if (focusedKeyboardSlideId != null && focusedKeyboardSlideId != activeSlideId) {
            focusedKeyboardSlideId = null
            focusedKeyboardNodeId = null
        }
    }

    /**
     * Evaluates a preview [KeyEvent] against the interactive keyboard-capture policy.
     *
     * Returns `true` when the deck/policy consumed the event (either navigating, entering capture,
     * or exiting capture), or `false` to let the focused interactive specimen handle it.
     */
    fun handlePreviewKeyEvent(
        event: KeyEvent,
        onAdvance: (AdvanceDirection) -> Unit,
        isExternalTextInputFocused: Boolean = false,
    ): Boolean = handlePreviewKey(
        key = event.key,
        type = event.type,
        onAdvance = onAdvance,
        isExternalTextInputFocused = isExternalTextInputFocused,
    )

    /**
     * Pure key-code policy evaluator backing [handlePreviewKeyEvent].
     */
    fun handlePreviewKey(
        key: Key,
        type: KeyEventType = KeyEventType.KeyDown,
        onAdvance: (AdvanceDirection) -> Unit,
        isExternalTextInputFocused: Boolean = false,
    ): Boolean {
        if (type != KeyEventType.KeyDown) return false
        if (isExternalTextInputFocused) return false

        // PageUp / PageDown ALWAYS navigate the deck, even while captured.
        when (key) {
            Key.PageDown -> {
                onAdvance(AdvanceDirection.Forward)
                return true
            }
            Key.PageUp -> {
                onAdvance(AdvanceDirection.Backward)
                return true
            }
            else -> Unit
        }

        // Captured mode: Esc exits capture; all other keys flow to the specimen.
        if (isCaptured) {
            if (key == Key.Escape) {
                exitInteractive()
                return true
            }
            return false
        }

        // Non-captured mode: Enter on a focused "keyboard" specimen enters interactive mode.
        if ((key == Key.Enter || key == Key.NumPadEnter) &&
            focusedKeyboardSlideId != null &&
            focusedKeyboardNodeId != null
        ) {
            enterInteractive(focusedKeyboardSlideId!!, focusedKeyboardNodeId!!)
            return true
        }

        // Non-captured mode: deck owns navigation keys even if a specimen child has focus.
        return when (key) {
            Key.DirectionRight,
            Key.DirectionDown,
            Key.Spacebar,
            Key.Enter,
            Key.NumPadEnter,
            -> {
                onAdvance(AdvanceDirection.Forward)
                true
            }
            Key.DirectionLeft,
            Key.DirectionUp,
            Key.Backspace,
            -> {
                onAdvance(AdvanceDirection.Backward)
                true
            }
            else -> false
        }
    }
}

/**
 * Installs the stage preview key handler bound to [animatic] and [controller].
 */
fun Modifier.documentStageKeyboardNavigation(
    animatic: Animatic,
    scope: CoroutineScope,
    controller: DocumentKeyboardCaptureController,
    isExternalTextInputFocused: () -> Boolean = { false },
): Modifier = documentStageKeyboardNavigation(
    controller = controller,
    onAdvance = { dir -> scope.launch { animatic.advance(dir) } },
    isExternalTextInputFocused = isExternalTextInputFocused,
)

/**
 * Installs the stage preview key handler invoking [onAdvance] for deck navigation keys.
 */
fun Modifier.documentStageKeyboardNavigation(
    controller: DocumentKeyboardCaptureController,
    onAdvance: (AdvanceDirection) -> Unit,
    isExternalTextInputFocused: () -> Boolean = { false },
): Modifier = this.onPreviewKeyEvent { event ->
    controller.handlePreviewKeyEvent(
        event = event,
        onAdvance = onAdvance,
        isExternalTextInputFocused = isExternalTextInputFocused(),
    )
}

/**
 * Attaches focus/click/double-click keyboard-capture handling to an individual node.
 */
fun Modifier.documentInteractiveKeyboardNode(
    slideId: String,
    nodeId: String,
    interactive: String?,
    controller: DocumentKeyboardCaptureController?,
    focusRequester: FocusRequester? = null,
    onSelectNode: ((String, String) -> Unit)? = null,
    onDoubleClickNode: ((String, String) -> Unit)? = null,
): Modifier {
    if (interactive != "keyboard" || controller == null) return this
    var mod = this
    if (focusRequester != null) {
        mod = mod.focusRequester(focusRequester)
    }
    return mod
        .onFocusChanged { focusState ->
            controller.onKeyboardNodeFocusChanged(slideId, nodeId, focusState.hasFocus)
        }
        .pointerInput(slideId, nodeId, controller, controller.isEditMode) {
            var lastTapUptimeMs = -1L
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                val editMode = controller.isEditMode
                val captured = controller.isNodeCaptured(slideId, nodeId)
                if (editMode && !captured) {
                    // Intercept clicks in Edit mode before specimen children consume them so
                    // single click selects and double click enters interactive capture.
                    down.consume()
                    val up = waitForUpOrCancellation(pass = PointerEventPass.Initial)
                    if (up != null) {
                        up.consume()
                        val now = up.uptimeMillis
                        val isDoubleTap = lastTapUptimeMs >= 0L &&
                            (now - lastTapUptimeMs) <= viewConfiguration.doubleTapTimeoutMillis
                        lastTapUptimeMs = if (isDoubleTap) -1L else now
                        if (isDoubleTap) {
                            controller.enterInteractive(slideId, nodeId)
                            runCatching { focusRequester?.requestFocus() }
                            onDoubleClickNode?.invoke(slideId, nodeId)
                        } else {
                            onSelectNode?.invoke(slideId, nodeId)
                        }
                    }
                } else if (!editMode) {
                    // Present mode: clicking anywhere inside a "keyboard" specimen enters capture
                    // without blocking the child control from receiving the pointer event.
                    controller.enterInteractive(slideId, nodeId)
                }
            }
        }
}
