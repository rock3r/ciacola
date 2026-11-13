package dev.sebastiano.ciacola.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
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
import dev.sebastiano.ciacola.review.ReviewCommentStatus
import dev.sebastiano.ciacola.theme.contrastRatio
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.intui.standalone.styling.default
import org.jetbrains.jewel.intui.standalone.theme.IntUiTheme
import org.jetbrains.jewel.intui.standalone.theme.darkThemeDefinition
import org.jetbrains.jewel.intui.standalone.theme.default
import org.jetbrains.jewel.intui.standalone.theme.lightThemeDefinition
import org.jetbrains.jewel.ui.ComponentStyling
import org.jetbrains.jewel.ui.icon.IconKey
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.ui.theme.colorPalette

/**
 * Applies standalone JetBrains Jewel Int UI theming for Ciacola's desktop chrome, popups,
 * and tool windows.
 *
 * @param isDark Whether to use the dark Int UI theme definition (matching StageDark vs KeynoteLight).
 * @param content Composable content styled with Jewel theme locals.
 */
@Composable
fun CiacolaJewelTheme(
    isDark: Boolean,
    content: @Composable () -> Unit,
) {
    val themeDefinition = if (isDark) {
        JewelTheme.darkThemeDefinition()
    } else {
        JewelTheme.lightThemeDefinition()
    }
    IntUiTheme(
        theme = themeDefinition,
        styling = ComponentStyling.default(),
        swingCompatMode = false,
        content = content,
    )
}

@Immutable
data class ReviewStatusPalette(
    val queued: Color,
    val applying: Color,
    val applied: Color,
    val failed: Color,
    val inspectAccent: Color,
    val marqueeAccent: Color,
    val pinBadgeForeground: Color,
) {
    fun colorFor(status: ReviewCommentStatus): Color = when (status) {
        ReviewCommentStatus.Queued -> queued
        ReviewCommentStatus.Applying -> applying
        ReviewCommentStatus.Applied -> applied
        ReviewCommentStatus.Failed -> failed
    }
}

/**
 * Resolves light/dark-aware status colors from Jewel's semantic global colors and Int UI
 * color palette with safe fallbacks, keyed on [JewelTheme.instanceUuid].
 */
@Composable
fun rememberReviewStatusPalette(): ReviewStatusPalette {
    val globalColors = JewelTheme.globalColors
    val palette = JewelTheme.colorPalette
    val isDark = JewelTheme.isDark
    val instanceUuid = JewelTheme.instanceUuid

    return remember(instanceUuid, isDark) {
        val fallbackFocus = globalColors.outlines.focused
        val fallbackWarning = globalColors.outlines.warning
        val fallbackError = globalColors.outlines.error
        ReviewStatusPalette(
            queued = palette.yellowOrNull(if (isDark) 6 else 5) ?: fallbackWarning,
            applying = palette.blueOrNull(if (isDark) 7 else 5) ?: fallbackFocus,
            applied = palette.greenOrNull(if (isDark) 6 else 5) ?: fallbackFocus,
            failed = palette.redOrNull(if (isDark) 6 else 5) ?: fallbackError,
            inspectAccent = palette.tealOrNull(if (isDark) 6 else 5) ?: fallbackFocus,
            marqueeAccent = palette.orangeOrNull(if (isDark) 6 else 5) ?: fallbackWarning,
            pinBadgeForeground = if (isDark) Color(0xFF0E1117) else Color(0xFFFFFFFF),
        )
    }
}

fun ReviewCommentStatus.iconKey(): IconKey = when (this) {
    ReviewCommentStatus.Queued -> AllIconsKeys.General.Warning
    ReviewCommentStatus.Applying -> AllIconsKeys.Actions.Refresh
    ReviewCommentStatus.Applied -> AllIconsKeys.General.GreenCheckmark
    ReviewCommentStatus.Failed -> AllIconsKeys.General.Error
}

fun ReviewCommentStatus.labelText(): String = when (this) {
    ReviewCommentStatus.Queued -> "Queued"
    ReviewCommentStatus.Applying -> "Applying…"
    ReviewCommentStatus.Applied -> "Applied"
    ReviewCommentStatus.Failed -> "Delivery failed"
}

/**
 * Resolves a focus ring color derived from Jewel's focused outline token that guarantees at least
 * 3:1 WCAG graphical contrast against [surface] in both dark and light themes.
 */
fun accessibleFocusRingColor(
    focused: Color,
    isDark: Boolean,
    surface: Color,
): Color {
    if (focused != Color.Unspecified && contrastRatio(focused, surface) >= 3.0f) {
        return focused
    }
    val fallback = if (isDark) Color(0xFF6BA8FF) else Color(0xFF1D4ED8)
    if (contrastRatio(fallback, surface) >= 3.0f) {
        return fallback
    }
    return if (isDark) Color.White else Color(0xFF0F2B6B)
}

@Composable
fun rememberAccessibleFocusRingColor(): Color {
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
fun Modifier.accessibleFocusRing(
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
 * Tracks whether any authoring `TextField` or `TextArea` across Studio, Design System, Element
 * Inspector, Inline Stage Editor, or Curve Editor currently holds focus so global single-key and
 * Studio shortcuts (`B`, `W`, `Space`, `Arrows`, `Cmd+Z`) yield cleanly to text entry.
 */
@Stable
class AuthoringTextInputFocusTracker {
    private var focusedCount by mutableIntStateOf(0)

    val hasFocusedTextInput: Boolean
        get() = focusedCount > 0

    fun onFocusAcquired() {
        focusedCount++
    }

    fun onFocusReleased() {
        focusedCount = (focusedCount - 1).coerceAtLeast(0)
    }
}

val LocalAuthoringTextInputTracker = staticCompositionLocalOf<AuthoringTextInputFocusTracker?> { null }

/**
 * Registers this modifier's focus subtree as an authoring text input in [LocalAuthoringTextInputTracker],
 * automatically releasing on unmount if the field disappears while focused.
 */
fun Modifier.trackAuthoringTextInputFocus(): Modifier = composed {
    val tracker = LocalAuthoringTextInputTracker.current ?: return@composed this
    var held by remember { mutableStateOf(false) }
    DisposableEffect(tracker) {
        onDispose {
            if (held) {
                held = false
                tracker.onFocusReleased()
            }
        }
    }
    this.onFocusChanged { state ->
        val nowFocused = state.hasFocus
        if (nowFocused != held) {
            held = nowFocused
            if (nowFocused) tracker.onFocusAcquired() else tracker.onFocusReleased()
        }
    }
}
