package dev.sebastiano.ciacola.ui

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.sebastiano.ciacola.theme.contrastRatio
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.foundation.theme.LocalContentColor as JewelLocalContentColor
import org.jetbrains.jewel.foundation.theme.LocalTextStyle as JewelLocalTextStyle
import org.jetbrains.jewel.foundation.theme.ThemeColorPalette
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.styling.ButtonColors
import org.jetbrains.jewel.ui.component.styling.ButtonStyle
import org.jetbrains.jewel.ui.icon.IconKey
import org.jetbrains.jewel.ui.theme.colorPalette
import org.jetbrains.jewel.ui.theme.defaultButtonStyle
import androidx.compose.material.LocalContentColor as MaterialLocalContentColor

private const val PRIMARY_BUTTON_MIN_CONTRAST = 4.5f
private val PrimaryButtonFallbackBlue = Color(0xFF1D4ED8)

/**
 * Resolved primary button styling with verified foreground/background contrast.
 */
@Immutable
data class ResolvedPrimaryButtonStyle(
    val style: ButtonStyle,
    val contentColor: Color,
    val backgroundColor: Color,
    val contrastRatio: Float,
)

/**
 * Resolves a [ButtonStyle] derived from Jewel's [baseStyle] and [palette] whose enabled primary
 * surface guarantees at least 4.5:1 WCAG AA contrast against white foreground text/icons in both
 * dark and light themes.
 */
fun resolveCiacolaPrimaryButtonStyle(
    baseStyle: ButtonStyle,
    palette: ThemeColorPalette,
    isDark: Boolean,
): ResolvedPrimaryButtonStyle {
    val contentColor = Color.White
    val baseBg = extractSolidColor(baseStyle.colors.background)
        ?: palette.blueOrNull(if (isDark) 6 else 4)
        ?: PrimaryButtonFallbackBlue

    val resolvedBg = ensureContrastAgainstWhite(
        initial = baseBg,
        palette = palette,
        isDark = isDark,
    )
    val hoverBg = ensureContrastAgainstWhite(
        initial = extractSolidColor(baseStyle.colors.backgroundHovered) ?: darkenColor(resolvedBg, 0.92f),
        palette = palette,
        isDark = isDark,
    )
    val pressedBg = ensureContrastAgainstWhite(
        initial = extractSolidColor(baseStyle.colors.backgroundPressed) ?: darkenColor(resolvedBg, 0.84f),
        palette = palette,
        isDark = isDark,
    )
    val focusedBg = ensureContrastAgainstWhite(
        initial = extractSolidColor(baseStyle.colors.backgroundFocused) ?: resolvedBg,
        palette = palette,
        isDark = isDark,
    )

    val baseColors = baseStyle.colors
    val updatedColors = ButtonColors(
        background = SolidColor(resolvedBg),
        backgroundDisabled = baseColors.backgroundDisabled,
        backgroundFocused = SolidColor(focusedBg),
        backgroundPressed = SolidColor(pressedBg),
        backgroundHovered = SolidColor(hoverBg),
        content = contentColor,
        contentDisabled = baseColors.contentDisabled,
        contentFocused = contentColor,
        contentPressed = contentColor,
        contentHovered = contentColor,
        border = SolidColor(resolvedBg),
        borderDisabled = baseColors.borderDisabled,
        borderFocused = baseColors.borderFocused,
        borderPressed = SolidColor(pressedBg),
        borderHovered = SolidColor(hoverBg),
    )

    val updatedStyle = ButtonStyle(
        colors = updatedColors,
        metrics = baseStyle.metrics,
        focusOutlineAlignment = baseStyle.focusOutlineAlignment,
    )

    return ResolvedPrimaryButtonStyle(
        style = updatedStyle,
        contentColor = contentColor,
        backgroundColor = resolvedBg,
        contrastRatio = contrastRatio(contentColor, resolvedBg),
    )
}

@Composable
fun rememberCiacolaPrimaryButtonStyle(): ResolvedPrimaryButtonStyle {
    val baseStyle = JewelTheme.defaultButtonStyle
    val palette = JewelTheme.colorPalette
    val isDark = JewelTheme.isDark
    val instanceUuid = JewelTheme.instanceUuid
    return remember(instanceUuid, isDark, baseStyle) {
        resolveCiacolaPrimaryButtonStyle(
            baseStyle = baseStyle,
            palette = palette,
            isDark = isDark,
        )
    }
}

/**
 * Primary call-to-action button wrapping Jewel [DefaultButton] with WCAG AA (>= 4.5:1) foreground
 * contrast in both dark and light themes and stripped text-style color overrides so labels and
 * leading icons stay crisp white when enabled.
 */
@Composable
fun CiacolaPrimaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    textStyle: TextStyle = JewelTheme.defaultTextStyle,
    content: @Composable () -> Unit,
) {
    val resolved = rememberCiacolaPrimaryButtonStyle()
    val sanitizedTextStyle = remember(textStyle) { textStyle.copy(color = Color.Unspecified) }
    DefaultButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        interactionSource = interactionSource,
        style = resolved.style,
        textStyle = sanitizedTextStyle,
    ) {
        val activeContentColor = JewelLocalContentColor.current
        CompositionLocalProvider(
            JewelLocalContentColor provides activeContentColor,
            MaterialLocalContentColor provides activeContentColor,
            JewelLocalTextStyle provides sanitizedTextStyle,
        ) {
            content()
        }
    }
}

/**
 * Convenience primary button displaying a single-line [text] label and optional tinted [leadingIcon].
 */
@Composable
fun CiacolaPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: IconKey? = null,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    textStyle: TextStyle = JewelTheme.defaultTextStyle,
) {
    CiacolaPrimaryButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        interactionSource = interactionSource,
        textStyle = textStyle,
    ) {
        val contentColor = JewelLocalContentColor.current
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leadingIcon != null) {
                Icon(
                    key = leadingIcon,
                    contentDescription = null,
                    modifier = Modifier.size(13.dp),
                    tint = contentColor,
                )
            }
            Text(
                text = text,
                style = textStyle.copy(color = Color.Unspecified),
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun extractSolidColor(brush: Brush): Color? = (brush as? SolidColor)?.value

private fun ensureContrastAgainstWhite(
    initial: Color,
    palette: ThemeColorPalette,
    isDark: Boolean,
): Color {
    if (initial != Color.Unspecified && contrastRatio(Color.White, initial) >= PRIMARY_BUTTON_MIN_CONTRAST) {
        return initial
    }
    val paletteSteps = if (isDark) listOf(5, 4, 3, 2, 1) else listOf(3, 2, 1)
    for (step in paletteSteps) {
        val candidate = palette.blueOrNull(step) ?: continue
        if (contrastRatio(Color.White, candidate) >= PRIMARY_BUTTON_MIN_CONTRAST) {
            return candidate
        }
    }
    var candidate = if (initial != Color.Unspecified) initial else PrimaryButtonFallbackBlue
    repeat(6) {
        candidate = darkenColor(candidate, 0.88f)
        if (contrastRatio(Color.White, candidate) >= PRIMARY_BUTTON_MIN_CONTRAST) {
            return candidate
        }
    }
    return PrimaryButtonFallbackBlue
}

private fun darkenColor(color: Color, factor: Float): Color = Color(
    red = (color.red * factor).coerceIn(0f, 1f),
    green = (color.green * factor).coerceIn(0f, 1f),
    blue = (color.blue * factor).coerceIn(0f, 1f),
    alpha = color.alpha,
)
