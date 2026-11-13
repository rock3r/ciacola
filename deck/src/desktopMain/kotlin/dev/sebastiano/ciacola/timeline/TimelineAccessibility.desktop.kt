package dev.sebastiano.ciacola.timeline

import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.jetbrains.jewel.foundation.theme.JewelTheme

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

    val activatableControlHasFocus: Boolean
        get() = focusedActivatables.isNotEmpty()

    fun setActivatableFocused(key: Any, focused: Boolean) {
        if (focused) {
            focusedActivatables[key] = true
        } else {
            focusedActivatables.remove(key)
        }
    }
}

internal val LocalStudioA11yFocusState = compositionLocalOf<StudioA11yFocusState?> { null }

/**
 * Resolves a focus ring color derived from Jewel's focused outline token that guarantees at least
 * 3:1 WCAG graphical contrast against [surface] in both dark and light themes.
 */
internal fun accessibleFocusRingColor(
    focused: Color,
    isDark: Boolean,
    surface: Color,
): Color {
    if (focused != Color.Unspecified && wcagContrastRatio(focused, surface) >= 3.0f) {
        return focused
    }
    val fallback = if (isDark) Color(0xFF6BA8FF) else Color(0xFF1D4ED8)
    if (wcagContrastRatio(fallback, surface) >= 3.0f) {
        return fallback
    }
    return if (isDark) Color.White else Color(0xFF0F2B6B)
}

@Composable
internal fun rememberAccessibleFocusRingColor(): Color {
    val globalColors = JewelTheme.globalColors
    val isDark = JewelTheme.isDark
    val surface = globalColors.panelBackground
    val focused = globalColors.outlines.focused
    return remember(JewelTheme.instanceUuid, isDark, surface, focused) {
        accessibleFocusRingColor(focused = focused, isDark = isDark, surface = surface)
    }
}

/**
 * Draws a crisp, non-layout-shifting focus ring when [focused] is true.
 */
internal fun Modifier.accessibleFocusRing(
    focused: Boolean,
    ringColor: Color,
    cornerRadius: Dp = 4.dp,
    strokeWidth: Dp = 2.dp,
): Modifier = if (!focused) {
    this
} else {
    drawWithContent {
        drawContent()
        val strokePx = strokeWidth.toPx()
        val inset = strokePx / 2f
        val radiusPx = cornerRadius.toPx()
        drawRoundRect(
            color = ringColor,
            topLeft = Offset(inset, inset),
            size = Size(
                width = (size.width - strokePx).coerceAtLeast(0f),
                height = (size.height - strokePx).coerceAtLeast(0f),
            ),
            cornerRadius = CornerRadius(radiusPx, radiusPx),
            style = Stroke(width = strokePx),
        )
    }
}

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
