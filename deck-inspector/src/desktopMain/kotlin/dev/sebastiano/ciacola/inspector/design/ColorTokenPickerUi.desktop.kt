package dev.sebastiano.ciacola.inspector.design

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.sebastiano.ciacola.ui.rememberAccessibleFocusRingColor
import java.util.Locale
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.Outline
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.InfoText
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField
import org.jetbrains.jewel.ui.component.Tooltip
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.ui.theme.colorPalette
import org.jetbrains.jewel.ui.typography

/**
 * Defines the semantic background pairing used to compute live WCAG 2.1 contrast and 1-click Fix AA.
 */
@Immutable
data class ContrastPairing(
    val targetLabel: String,
    val primaryBackground: Color,
    val secondaryBackground: Color? = null,
    val enforceAaWarning: Boolean = true,
)

/**
 * Reusable color token row displaying a clickable swatch, hex value, live WCAG 2.1 contrast ratio
 * badge, 1-click "Fix AA" button when contrast `< 4.5:1`, reset control, and an expandable inline
 * HSV/Alpha/Hex/Preset-swatch color editor.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ColorTokenRow(
    tokenId: String,
    label: String,
    color: Color,
    isOverridden: Boolean,
    presetSwatches: List<Color>,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onColorChanged: (Color, coalesce: Boolean) -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
    supportsAlpha: Boolean = false,
    contrastPairing: ContrastPairing? = null,
    onGestureStart: () -> Unit = {},
    onGestureEnd: () -> Unit = {},
    onTextInputFocusChanged: (Boolean) -> Unit = {},
) {
    val globalColors = JewelTheme.globalColors
    val focusRingColor = rememberAccessibleFocusRingColor()
    val hexText = color.toHexString(includeAlpha = supportsAlpha)
    val rowShape = RoundedCornerShape(5.dp)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(
                color = if (expanded) globalColors.toolwindowBackground else Color.Transparent,
                shape = rowShape,
            )
            .then(
                if (expanded) {
                    Modifier.border(1.dp, globalColors.borders.normal, rowShape).padding(6.dp)
                } else {
                    Modifier.padding(vertical = 2.dp, horizontal = 2.dp)
                },
            )
            .testTag("design-color-$tokenId-row"),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Tooltip(
                    tooltip = {
                        Text(if (expanded) "Collapse $label color picker" else "Edit $label color ($hexText)")
                    },
                ) {
                    Box(
                        modifier = Modifier
                            .size(22.dp)
                            .designActivatableFocus(focusRingColor, cornerRadius = 4.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .border(1.dp, globalColors.borders.normal, RoundedCornerShape(4.dp))
                            .clickable(onClick = onToggleExpand)
                            .testTag("design-color-$tokenId-swatch")
                            .semantics {
                                role = Role.Button
                                contentDescription = "Edit $label color"
                                stateDescription = if (expanded) "Expanded, $hexText" else "Collapsed, $hexText"
                            },
                    ) {
                        if (supportsAlpha && color.alpha < 0.999f) {
                            CheckerboardBackground(Modifier.fillMaxSize())
                        }
                        Box(Modifier.fillMaxSize().background(color))
                    }
                }

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(1.dp),
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (isOverridden) {
                            ModifiedDotBadge(tooltip = "$label is overridden")
                        }
                        Text(
                            text = label,
                            style = JewelTheme.typography.small.copy(fontWeight = FontWeight.Medium),
                            color = globalColors.text.normal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        text = hexText,
                        style = JewelTheme.typography.small.copy(fontFamily = FontFamily.Monospace),
                        color = globalColors.text.info,
                        maxLines = 1,
                        modifier = Modifier.testTag("design-color-$tokenId-hex-label"),
                    )
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (contrastPairing != null) {
                    ContrastRatioControls(
                        tokenId = tokenId,
                        color = color,
                        pairing = contrastPairing,
                        onFixAa = { fixed -> onColorChanged(fixed, false) },
                    )
                }
                DesignTooltipIconButton(
                    icon = AllIconsKeys.Actions.Refresh,
                    contentDescription = "Reset $label",
                    tooltip = "Reset $label to preset default",
                    testTag = "design-color-$tokenId-reset",
                    enabled = isOverridden,
                    size = 22.dp,
                    iconSize = 12.dp,
                    onClick = onReset,
                )
            }
        }

        if (expanded) {
            Divider(Orientation.Horizontal, Modifier.fillMaxWidth())
            InlineHsvColorEditor(
                tokenId = tokenId,
                label = label,
                color = color,
                supportsAlpha = supportsAlpha,
                presetSwatches = presetSwatches,
                onGestureStart = onGestureStart,
                onGestureEnd = onGestureEnd,
                onColorChanged = onColorChanged,
                onTextInputFocusChanged = onTextInputFocusChanged,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ContrastRatioControls(
    tokenId: String,
    color: Color,
    pairing: ContrastPairing,
    onFixAa: (Color) -> Unit,
) {
    val globalColors = JewelTheme.globalColors
    val palette = JewelTheme.colorPalette
    val isDark = JewelTheme.isDark
    val focusRingColor = rememberAccessibleFocusRingColor()

    val ratio = worstWcagContrastRatio(
        foreground = color,
        primaryBackground = pairing.primaryBackground,
        secondaryBackground = pairing.secondaryBackground,
    )
    val passesAa = ratio >= 4.5f
    val passesAaa = ratio >= 7.0f
    val formattedRatio = "%.1f".format(Locale.US, ratio)
    val grade = when {
        passesAaa -> "AAA"
        passesAa -> "AA"
        else -> "Low"
    }
    val warnColor = palette.yellowOrNull(if (isDark) 6 else 5) ?: Color(0xFFD99B16)
    val passColor = palette.greenOrNull(if (isDark) 6 else 5) ?: Color(0xFF267D4D)
    val pillAccent = if (passesAa || !pairing.enforceAaWarning) passColor else warnColor
    val pillShape = RoundedCornerShape(4.dp)

    Tooltip(
        tooltip = {
            val statusText = if (passesAa) "passes WCAG AA (≥ 4.5:1)" else "below WCAG AA (< 4.5:1)"
            Text("Contrast vs ${pairing.targetLabel}: $formattedRatio:1 — $statusText")
        },
    ) {
        Box(
            modifier = Modifier
                .background(pillAccent.copy(alpha = 0.16f), pillShape)
                .border(1.dp, pillAccent.copy(alpha = 0.65f), pillShape)
                .padding(horizontal = 5.dp, vertical = 2.dp)
                .testTag("design-color-$tokenId-contrast-badge")
                .semantics {
                    contentDescription = "Contrast $formattedRatio to 1, $grade against ${pairing.targetLabel}"
                },
        ) {
            Text(
                text = "$formattedRatio:1 $grade",
                style = JewelTheme.typography.small.copy(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                ),
                color = globalColors.text.normal,
                maxLines = 1,
            )
        }
    }

    if (!passesAa && pairing.enforceAaWarning) {
        Tooltip(
            tooltip = {
                Text("Fix AA: nudge lightness until contrast ≥ 4.55:1 against ${pairing.targetLabel}")
            },
        ) {
            OutlinedButton(
                onClick = {
                    val fixed = fixColorForWcagAa(
                        foreground = color,
                        primaryBackground = pairing.primaryBackground,
                        secondaryBackground = pairing.secondaryBackground,
                        targetRatio = 4.55f,
                    )
                    onFixAa(fixed)
                },
                modifier = Modifier
                    .height(22.dp)
                    .designActivatableFocus(focusRingColor)
                    .testTag("design-color-$tokenId-fix-aa"),
            ) {
                Text("Fix AA", style = JewelTheme.typography.small, maxLines = 1)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
private fun InlineHsvColorEditor(
    tokenId: String,
    label: String,
    color: Color,
    supportsAlpha: Boolean,
    presetSwatches: List<Color>,
    onGestureStart: () -> Unit,
    onGestureEnd: () -> Unit,
    onColorChanged: (Color, coalesce: Boolean) -> Unit,
    onTextInputFocusChanged: (Boolean) -> Unit,
) {
    val globalColors = JewelTheme.globalColors
    val focusRingColor = rememberAccessibleFocusRingColor()
    var localHsv by remember(tokenId) { mutableStateOf(color.toHsv()) }

    LaunchedEffect(color) {
        val currentFromLocal = hsvToColor(localHsv.hue, localHsv.saturation, localHsv.value, localHsv.alpha)
        if (currentFromLocal.toArgbLong() != color.toArgbLong()) {
            localHsv = color.toHsv()
        }
    }

    val hexState = rememberTextFieldState(initialText = color.toHexString(includeAlpha = supportsAlpha))
    var hexError by remember(tokenId) { mutableStateOf<String?>(null) }
    var hexHasFocus by remember(tokenId) { mutableStateOf(false) }
    var lastProgrammaticHex by remember(tokenId) { mutableStateOf(color.toHexString(includeAlpha = supportsAlpha)) }

    LaunchedEffect(color, supportsAlpha) {
        val formatted = color.toHexString(includeAlpha = supportsAlpha)
        val parsedCurrent = parseHexColorOrNull(hexState.text.toString(), defaultAlpha = localHsv.alpha)
        if (!hexHasFocus || parsedCurrent?.toArgbLong() != color.toArgbLong()) {
            lastProgrammaticHex = formatted
            if (hexState.text.toString() != formatted) {
                hexState.setTextAndPlaceCursorAtEnd(formatted)
            }
            hexError = null
        }
    }

    val liveText = hexState.text.toString()
    LaunchedEffect(liveText) {
        if (!hexHasFocus || liveText == lastProgrammaticHex) return@LaunchedEffect
        val parsed = parseHexColorOrNull(liveText, defaultAlpha = localHsv.alpha)
        if (parsed == null) {
            hexError = if (supportsAlpha) "Use #RRGGBB or #AARRGGBB" else "Use #RRGGBB"
        } else {
            hexError = null
            if (parsed.toArgbLong() != color.toArgbLong()) {
                localHsv = parsed.toHsv()
                onColorChanged(parsed, false)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 2.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SaturationValueCanvas(
            tokenId = tokenId,
            label = label,
            hsv = localHsv,
            onGestureStart = onGestureStart,
            onGestureEnd = onGestureEnd,
            onSatValChanged = { sat, value, coalesce ->
                val updated = localHsv.copy(saturation = sat, value = value)
                localHsv = updated
                onColorChanged(hsvToColor(updated.hue, updated.saturation, updated.value, updated.alpha), coalesce)
            },
        )

        HueSliderBar(
            tokenId = tokenId,
            label = label,
            hue = localHsv.hue,
            onGestureStart = onGestureStart,
            onGestureEnd = onGestureEnd,
            onHueChanged = { hue, coalesce ->
                val updated = localHsv.copy(hue = hue)
                localHsv = updated
                onColorChanged(hsvToColor(updated.hue, updated.saturation, updated.value, updated.alpha), coalesce)
            },
        )

        if (supportsAlpha) {
            AlphaSliderBar(
                tokenId = tokenId,
                label = label,
                color = hsvToColor(localHsv.hue, localHsv.saturation, localHsv.value, localHsv.alpha),
                alpha = localHsv.alpha,
                onGestureStart = onGestureStart,
                onGestureEnd = onGestureEnd,
                onAlphaChanged = { alpha, coalesce ->
                    val updated = localHsv.copy(alpha = alpha)
                    localHsv = updated
                    onColorChanged(hsvToColor(updated.hue, updated.saturation, updated.value, updated.alpha), coalesce)
                },
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Hex",
                    style = JewelTheme.typography.small,
                    color = globalColors.text.info,
                )
                Tooltip(tooltip = { Text("Enter #RRGGBB" + if (supportsAlpha) " or #AARRGGBB" else "") }) {
                    TextField(
                        state = hexState,
                        outline = if (hexError != null) Outline.Error else Outline.None,
                        placeholder = { Text(if (supportsAlpha) "#AARRGGBB" else "#RRGGBB") },
                        modifier = Modifier
                            .width(124.dp)
                            .designActivatableFocus(focusRingColor) { focused ->
                                hexHasFocus = focused
                                onTextInputFocusChanged(focused)
                            }
                            .testTag("design-color-$tokenId-hex"),
                    )
                }
                Text(
                    text = "H:${localHsv.hue.toInt()}° S:${(localHsv.saturation * 100).toInt()}% V:${(localHsv.value * 100).toInt()}%",
                    style = JewelTheme.typography.small.copy(fontFamily = FontFamily.Monospace),
                    color = globalColors.text.info,
                    maxLines = 1,
                )
            }
            if (hexError != null) {
                InfoText(
                    text = hexError!!,
                    color = globalColors.outlines.error,
                    modifier = Modifier.testTag("design-color-$tokenId-hex-error"),
                )
            }
        }

        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            presetSwatches.forEachIndexed { index, swatch ->
                val swatchHex = swatch.toHexString(includeAlpha = supportsAlpha)
                Tooltip(tooltip = { Text("Apply $swatchHex") }) {
                    Box(
                        modifier = Modifier
                            .size(18.dp)
                            .designActivatableFocus(focusRingColor, cornerRadius = 4.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(swatch)
                            .border(
                                width = if (swatch.toArgbLong() == color.toArgbLong()) 2.dp else 1.dp,
                                color = if (swatch.toArgbLong() == color.toArgbLong()) {
                                    globalColors.outlines.focused
                                } else {
                                    globalColors.borders.normal
                                },
                                shape = RoundedCornerShape(4.dp),
                            )
                            .clickable {
                                val applied = if (supportsAlpha) swatch else swatch.copy(alpha = 1f)
                                localHsv = applied.toHsv()
                                onColorChanged(applied, false)
                            }
                            .testTag("design-color-$tokenId-preset-$index")
                            .semantics {
                                role = Role.Button
                                contentDescription = "Preset swatch $swatchHex"
                            },
                    )
                }
            }
        }
    }
}
