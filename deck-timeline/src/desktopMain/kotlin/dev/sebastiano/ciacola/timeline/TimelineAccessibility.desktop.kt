package dev.sebastiano.ciacola.timeline

import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.sebastiano.ciacola.ui.accessibleFocusRing
import dev.sebastiano.ciacola.ui.trackAuthoringTextInputFocus

/**
 * Tracks focus ownership inside Timeline Studio so global shortcuts (`Space`, `Up`/`Down`, `Esc`)
 * yield to focused buttons, pills, steppers, and layer rows instead of hijacking keyboard input.
 */
@Stable
internal class StudioA11yFocusState {
    var timelinePanelHasFocus by mutableStateOf(false)
    var layerListHasFocus by mutableStateOf(false)
    var swallowNextEscapeKeyUp by mutableStateOf(false)

    private val focusedActivatables = mutableStateMapOf<Any, Boolean>()
    private val focusedTextInputs = mutableStateMapOf<Any, Boolean>()

    val activatableControlHasFocus: Boolean
        get() = focusedActivatables.isNotEmpty()

    val textInputHasFocus: Boolean
        get() = focusedTextInputs.isNotEmpty()

    fun setActivatableFocused(key: Any, focused: Boolean) {
        if (focused) {
            focusedActivatables[key] = true
        } else {
            focusedActivatables.remove(key)
        }
    }

    fun setTextInputFocused(key: Any, focused: Boolean) {
        if (focused) {
            focusedTextInputs[key] = true
        } else {
            focusedTextInputs.remove(key)
        }
    }
}

internal val LocalStudioA11yFocusState = compositionLocalOf<StudioA11yFocusState?> { null }

/**
 * Registers this control as a keyboard-activatable studio control when focused (so `Space`/`Enter`
 * activate it rather than triggering Timeline Play/Pause) and draws a high-contrast focus ring.
 */
internal fun Modifier.studioActivatableFocus(
    ringColor: Color,
    cornerRadius: Dp = 4.dp,
    makeFocusable: Boolean = false,
    interactionSource: MutableInteractionSource? = null,
    onFocusedChanged: (Boolean) -> Unit = {},
): Modifier = composed {
    val focusState = LocalStudioA11yFocusState.current
    val key = remember { Any() }
    var isFocused by remember { mutableStateOf(false) }

    DisposableEffect(focusState, key) {
        onDispose {
            focusState?.setActivatableFocused(key, false)
        }
    }

    val tracked = onFocusChanged { state ->
        val nowFocused = state.isFocused
        if (isFocused != nowFocused) {
            isFocused = nowFocused
            onFocusedChanged(nowFocused)
        }
        focusState?.setActivatableFocused(key, nowFocused)
    }
    val withFocusable = if (makeFocusable) {
        tracked.focusable(interactionSource = interactionSource)
    } else {
        tracked
    }
    withFocusable.accessibleFocusRing(
        focused = isFocused,
        ringColor = ringColor,
        cornerRadius = cornerRadius,
    )
}

/**
 * Registers a text-input field (such as numeric Bezier inputs) so global studio shortcuts yield
 * printable keys, minus/plus, brackets, and cursor arrows while the user is typing.
 */
internal fun Modifier.studioTextInputFocus(): Modifier = composed {
    val focusState = LocalStudioA11yFocusState.current
    val key = remember { Any() }

    DisposableEffect(focusState, key) {
        onDispose {
            focusState?.setTextInputFocused(key, false)
        }
    }

    onFocusChanged { state ->
        focusState?.setTextInputFocused(key, state.isFocused)
    }.trackAuthoringTextInputFocus()
}

internal fun TrackCategory.accessibleLabel(): String = when (this) {
    TrackCategory.SharedElement -> "Shared Element"
    TrackCategory.MagicText -> "Magic Text"
    TrackCategory.WarpCode -> "Warp Code"
    TrackCategory.StaggerGroup -> "Stagger Group"
    TrackCategory.StaggerItem -> "Stagger Item"
    TrackCategory.Build -> "Build"
    TrackCategory.LineDraw -> "Line Draw"
    TrackCategory.LoopSubComp -> "Loop Sub-Composition"
}

internal fun formatMillisForAccessibility(ms: Int): String {
    val safeMs = ms.coerceAtLeast(0)
    val seconds = safeMs / 1000
    val remainderMs = safeMs % 1000
    return if (seconds == 0) {
        "$safeMs milliseconds"
    } else {
        "%d.%03d seconds".format(java.util.Locale.US, seconds, remainderMs)
    }
}
