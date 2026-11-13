package dev.sebastiano.ciacola.inspector.element

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.sebastiano.ciacola.inspector.design.CheckerboardBackground
import dev.sebastiano.ciacola.inspector.design.ColorPresetSwatchStrip
import dev.sebastiano.ciacola.inspector.design.DesignSectionCard
import dev.sebastiano.ciacola.inspector.design.DesignTooltipIconButton
import dev.sebastiano.ciacola.inspector.design.HsvChannelPickers
import dev.sebastiano.ciacola.inspector.design.ModifiedDotBadge
import dev.sebastiano.ciacola.inspector.design.designActivatableFocus
import dev.sebastiano.ciacola.inspector.design.fixColorForWcagAa
import dev.sebastiano.ciacola.inspector.design.hsvToColor
import dev.sebastiano.ciacola.inspector.design.parseHexColorOrNull
import dev.sebastiano.ciacola.inspector.design.toArgbLong
import dev.sebastiano.ciacola.inspector.design.toHexString
import dev.sebastiano.ciacola.inspector.design.toHsv
import dev.sebastiano.ciacola.inspector.design.wcagContrastRatio
import dev.sebastiano.ciacola.theme.LocalDeckTheme
import dev.sebastiano.ciacola.ui.rememberAccessibleFocusRingColor
import dev.sebastiano.ciacola.ui.trackAuthoringTextInputFocus
import java.util.Locale
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Outline
import org.jetbrains.jewel.ui.component.InfoText
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField
import org.jetbrains.jewel.ui.component.Tooltip
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.ui.theme.colorPalette
import org.jetbrains.jewel.ui.typography

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ElementColorControlRow(
    label: String,
    color: Color,
    isOverridden: Boolean,
    testTagPrefix: String,
    contrastAgainst: Color? = null,
    allowClearFill: Boolean = false,
    onGestureStart: () -> Unit = {},
    onGestureEnd: () -> Unit = {},
    onColorChanged: (Color, Boolean) -> Unit,
    onReset: () -> Unit,
) {
    val globalColors = JewelTheme.globalColors
    val focusRingColor = rememberAccessibleFocusRingColor()
    val palette = JewelTheme.colorPalette
    val isDark = JewelTheme.isDark
    val theme = LocalDeckTheme.current

    var expanded by remember(testTagPrefix) { mutableStateOf(false) }
    var localHsv by remember(testTagPrefix) { mutableStateOf(color.toHsv()) }

    LaunchedEffect(color) {
        val currentFromLocal = hsvToColor(localHsv.hue, localHsv.saturation, localHsv.value, localHsv.alpha)
        if (currentFromLocal.toArgbLong() != color.toArgbLong()) {
            localHsv = color.toHsv()
        }
    }

    val formattedHex = color.toHexString(includeAlpha = color.alpha < 0.999f)
    val hexState = rememberTextFieldState(initialText = formattedHex)
    var hexHasFocus by remember(testTagPrefix) { mutableStateOf(false) }
    var lastProgrammaticHex by remember(testTagPrefix) { mutableStateOf(formattedHex) }
    var hexValid by remember(testTagPrefix) { mutableStateOf(true) }

    LaunchedEffect(color) {
        val formatted = color.toHexString(includeAlpha = color.alpha < 0.999f)
        val parsedCurrent = parseHexColorOrNull(hexState.text.toString(), defaultAlpha = 1f)
        if (!hexHasFocus || parsedCurrent?.toArgbLong() != color.toArgbLong()) {
            lastProgrammaticHex = formatted
            if (hexState.text.toString() != formatted) {
                hexState.setTextAndPlaceCursorAtEnd(formatted)
            }
            hexValid = true
        }
    }

    val liveHex = hexState.text.toString()
    LaunchedEffect(liveHex) {
        val currentText = hexState.text.toString()
        if (!hexHasFocus || currentText.equals(lastProgrammaticHex, ignoreCase = true)) {
            hexValid = true
            return@LaunchedEffect
        }
        val parsed = parseHexColorOrNull(currentText, defaultAlpha = 1f)
        hexValid = parsed != null
        if (parsed != null && parsed.toArgbLong() != color.toArgbLong()) {
            localHsv = parsed.toHsv()
            onColorChanged(parsed, false)
        }
    }

    val themeSwatches = remember(theme.colors) {
        listOf(
            theme.colors.foreground,
            theme.colors.muted,
            theme.colors.accent,
            theme.colors.secondary,
            theme.colors.positive,
            theme.colors.warning,
            theme.colors.danger,
            theme.colors.surface,
            theme.colors.background,
        ).distinctBy { it.toArgbLong() }
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.width(86.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (isOverridden) {
                    ModifiedDotBadge(tooltip = "$label is overridden")
                }
                Text(
                    text = label,
                    style = JewelTheme.typography.small,
                    color = if (isOverridden) globalColors.text.normal else globalColors.text.info,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Tooltip(tooltip = { Text("Toggle color picker for $label ($formattedHex)") }) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .designActivatableFocus(focusRingColor, cornerRadius = 4.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .border(1.dp, globalColors.borders.normal, RoundedCornerShape(4.dp))
                        .pointerHoverIcon(PointerIcon.Hand)
                        .clickable { expanded = !expanded }
                        .semantics {
                            role = Role.Button
                            contentDescription = "Pick $label color ($formattedHex)"
                        }
                        .testTag("$testTagPrefix-swatch"),
                ) {
                    if (color.alpha < 0.999f) {
                        CheckerboardBackground(Modifier.fillMaxSize())
                    }
                    Box(Modifier.fillMaxSize().background(color))
                }
            }

            Tooltip(tooltip = { Text("Enter #RRGGBB or #AARRGGBB hex color") }) {
                TextField(
                    state = hexState,
                    outline = if (hexValid) Outline.None else Outline.Error,
                    modifier = Modifier
                        .weight(1f)
                        .trackAuthoringTextInputFocus()
                        .designActivatableFocus(focusRingColor) { focused ->
                            hexHasFocus = focused
                        }
                        .testTag("$testTagPrefix-hex"),
                )
            }

            if (allowClearFill) {
                DesignTooltipIconButton(
                    icon = AllIconsKeys.General.Close,
                    contentDescription = "Clear $label",
                    tooltip = "Clear $label (transparent)",
                    testTag = "$testTagPrefix-clear",
                    enabled = true,
                    size = 24.dp,
                    iconSize = 13.dp,
                    onClick = { onColorChanged(Color.Transparent, false) },
                )
            }

            DesignTooltipIconButton(
                icon = AllIconsKeys.Actions.Rollback,
                contentDescription = "Reset $label",
                tooltip = "Reset $label to default",
                testTag = "$testTagPrefix-reset",
                enabled = isOverridden,
                size = 24.dp,
                iconSize = 13.dp,
                onClick = onReset,
            )
        }

        if (!hexValid) {
            InfoText(
                text = "Expected #RRGGBB or #AARRGGBB",
                color = globalColors.outlines.error,
                modifier = Modifier.testTag("$testTagPrefix-hex-error"),
            )
        }

        if (contrastAgainst != null) {
            val ratio = wcagContrastRatio(color, contrastAgainst)
            val passesAa = ratio >= 4.5f
            val badgeBg = if (passesAa) {
                (palette.greenOrNull(if (isDark) 3 else 11) ?: Color(0xFF1F5132)).copy(alpha = 0.28f)
            } else {
                (palette.redOrNull(if (isDark) 3 else 11) ?: Color(0xFF6B1D1D)).copy(alpha = 0.28f)
            }
            val badgeBorder = if (passesAa) {
                palette.greenOrNull(if (isDark) 6 else 5) ?: Color(0xFF3FB950)
            } else {
                palette.redOrNull(if (isDark) 6 else 5) ?: Color(0xFFF85149)
            }
            val formattedRatio = "%.1f".format(Locale.US, ratio)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Spacer(Modifier.width(86.dp))
                Tooltip(tooltip = { Text("WCAG 2.1 contrast ratio against element background") }) {
                    Box(
                        modifier = Modifier
                            .background(badgeBg, RoundedCornerShape(4.dp))
                            .border(1.dp, badgeBorder.copy(alpha = 0.7f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                            .testTag("$testTagPrefix-contrast-badge"),
                    ) {
                        Text(
                            text = if (passesAa) "$formattedRatio:1 AA" else "$formattedRatio:1 Low",
                            style = JewelTheme.typography.small.copy(fontWeight = FontWeight.Medium),
                            color = globalColors.text.normal,
                        )
                    }
                }
                if (!passesAa) {
                    Tooltip(tooltip = { Text("Adjust lightness to meet >= 4.5:1 WCAG AA contrast") }) {
                        OutlinedButton(
                            onClick = {
                                val fixed = fixColorForWcagAa(
                                    foreground = color,
                                    primaryBackground = contrastAgainst,
                                    targetRatio = 4.55f,
                                )
                                localHsv = fixed.toHsv()
                                onColorChanged(fixed, false)
                            },
                            modifier = Modifier
                                .designActivatableFocus(focusRingColor)
                                .testTag("$testTagPrefix-fix-aa"),
                        ) {
                            Text("Fix AA", style = JewelTheme.typography.small)
                        }
                    }
                }
            }
        }

        if (expanded) {
            DesignSectionCard(modifier = Modifier.testTag("$testTagPrefix-picker")) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    HsvChannelPickers(
                        tokenId = testTagPrefix,
                        label = label,
                        color = color,
                        hsv = localHsv,
                        supportsAlpha = true,
                        svTestTag = "$testTagPrefix-sv-canvas",
                        hueTestTag = "$testTagPrefix-hue-bar",
                        alphaTestTag = "$testTagPrefix-alpha-bar",
                        onGestureStart = onGestureStart,
                        onGestureEnd = onGestureEnd,
                        onHsvAndColorChanged = { updated, nextColor, coalesce ->
                            localHsv = updated
                            onColorChanged(nextColor, coalesce)
                        },
                    )
                    ColorPresetSwatchStrip(
                        selectedColor = color,
                        presetSwatches = themeSwatches,
                        supportsAlpha = true,
                        tooltipForSwatch = { hex -> "Apply theme color $hex" },
                        contentDescriptionForSwatch = { hex -> "Theme swatch $hex" },
                        swatchTestTag = { idx -> "$testTagPrefix-theme-swatch-$idx" },
                        onSwatchPicked = { applied ->
                            localHsv = applied.toHsv()
                            onColorChanged(applied, false)
                        },
                    )
                }
            }
        }
    }
}
