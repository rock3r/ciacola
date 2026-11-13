package dev.sebastiano.ciacola.inspector.element

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.sebastiano.ciacola.theme.LocalDeckTheme
import dev.sebastiano.ciacola.theme.contrastRatio
import dev.sebastiano.ciacola.theme.relativeLuminance
import dev.sebastiano.ciacola.ui.accessibleFocusRing
import dev.sebastiano.ciacola.ui.rememberAccessibleFocusRingColor
import dev.sebastiano.ciacola.ui.trackAuthoringTextInputFocus
import kotlin.math.roundToInt
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Outline
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.IconButton
import org.jetbrains.jewel.ui.component.InfoText
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Slider
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField
import org.jetbrains.jewel.ui.component.Tooltip
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.ui.theme.colorPalette
import org.jetbrains.jewel.ui.typography

internal fun colorToArgbLong(color: Color): Long = color.toArgb().toLong() and 0xFFFFFFFFL

internal fun formatHexColor(color: Color, includeAlpha: Boolean = color.alpha < 0.999f): String {
    val argb = color.toArgb()
    return if (includeAlpha) {
        "#%08X".format(argb)
    } else {
        "#%06X".format(argb and 0x00FFFFFF)
    }
}

internal fun parseHexColorOrNull(raw: String): Color? {
    val clean = raw.trim().removePrefix("#")
    return when (clean.length) {
        6 -> clean.toLongOrNull(16)?.let { Color(0xFF000000L or it) }
        8 -> clean.toLongOrNull(16)?.let { Color(it) }
        else -> null
    }
}

internal fun colorToHsv(color: Color): FloatArray {
    val r = (color.red * 255f).roundToInt().coerceIn(0, 255)
    val g = (color.green * 255f).roundToInt().coerceIn(0, 255)
    val b = (color.blue * 255f).roundToInt().coerceIn(0, 255)
    val hsv = FloatArray(3)
    java.awt.Color.RGBtoHSB(r, g, b, hsv)
    hsv[0] *= 360f
    return hsv
}

internal fun hsvToColor(hue: Float, saturation: Float, value: Float, alpha: Float = 1f): Color {
    val rgb = java.awt.Color.HSBtoRGB(
        (hue / 360f).coerceIn(0f, 1f),
        saturation.coerceIn(0f, 1f),
        value.coerceIn(0f, 1f),
    )
    val base = Color(rgb and 0x00FFFFFF or (0xFF shl 24))
    return base.copy(alpha = alpha.coerceIn(0f, 1f))
}

/**
 * Nudges [foreground] luminance toward light or dark while preserving hue/saturation until it
 * satisfies [minRatio] WCAG contrast against [background].
 */
internal fun adjustColorForContrast(
    foreground: Color,
    background: Color,
    minRatio: Float = 4.55f,
): Color {
    val opaqueBg = background.copy(alpha = 1f)
    val opaqueFg = if (foreground.alpha < 0.999f) foreground.copy(alpha = 1f) else foreground
    if (contrastRatio(opaqueFg, opaqueBg) >= minRatio) return opaqueFg

    val hsv = colorToHsv(opaqueFg)
    val bgLum = relativeLuminance(opaqueBg)
    val goLighter = bgLum < 0.35f

    var best = if (goLighter) Color.White else Color.Black
    val targetH = hsv[0]
    val baseS = hsv[1]
    val baseV = hsv[2]

    // Binary search saturation/value toward white (if lighter) or black (if darker).
    var lo = 0f
    var hi = 1f
    repeat(20) {
        val mid = (lo + hi) / 2f
        val candidate = if (goLighter) {
            hsvToColor(targetH, baseS * (1f - mid * 0.65f), baseV + (1f - baseV) * mid)
        } else {
            hsvToColor(targetH, baseS, baseV * (1f - mid))
        }
        if (contrastRatio(candidate, opaqueBg) >= minRatio) {
            best = candidate
            hi = mid
        } else {
            lo = mid
        }
    }
    return best
}

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
    onColorChanged: (Color) -> Unit,
    onReset: () -> Unit,
) {
    val globalColors = JewelTheme.globalColors
    val focusRingColor = rememberAccessibleFocusRingColor()
    val palette = JewelTheme.colorPalette
    val isDark = JewelTheme.isDark
    val theme = LocalDeckTheme.current

    var expanded by remember(testTagPrefix) { mutableStateOf(false) }
    var swatchFocused by remember { mutableStateOf(false) }
    var clearFocused by remember { mutableStateOf(false) }
    var resetFocused by remember { mutableStateOf(false) }

    val formattedHex = formatHexColor(color)
    val hexState = rememberTextFieldState(initialText = formattedHex)
    var lastExternalHex by remember(testTagPrefix) { mutableStateOf(formattedHex) }
    var hexValid by remember(testTagPrefix) { mutableStateOf(true) }

    LaunchedEffect(formattedHex) {
        if (formattedHex != lastExternalHex) {
            lastExternalHex = formattedHex
            if (hexState.text.toString().uppercase() != formattedHex.uppercase()) {
                hexState.setTextAndPlaceCursorAtEnd(formattedHex)
            }
            hexValid = true
        }
    }

    LaunchedEffect(hexState.text.toString()) {
        val raw = hexState.text.toString()
        if (raw.equals(lastExternalHex, ignoreCase = true)) {
            hexValid = true
        } else {
            val parsed = parseHexColorOrNull(raw)
            hexValid = parsed != null
            if (parsed != null) {
                lastExternalHex = formatHexColor(parsed)
                onColorChanged(parsed)
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = if (isOverridden) "● $label" else label,
                style = JewelTheme.typography.small,
                color = if (isOverridden) globalColors.text.normal else globalColors.text.info,
                modifier = Modifier.width(86.dp),
            )

            Tooltip(tooltip = { Text("Toggle color picker for $label") }) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(color)
                        .border(1.dp, globalColors.borders.normal, RoundedCornerShape(4.dp))
                        .accessibleFocusRing(swatchFocused, focusRingColor, cornerRadius = 4.dp)
                        .onFocusChanged { swatchFocused = it.isFocused }
                        .focusable()
                        .pointerHoverIcon(PointerIcon.Hand)
                        .clickable { expanded = !expanded }
                        .semantics {
                            role = Role.Button
                            contentDescription = "Pick $label color (${formatHexColor(color)})"
                        }
                        .testTag("$testTagPrefix-swatch"),
                )
            }

            Tooltip(tooltip = { Text("Enter #RRGGBB or #AARRGGBB hex color") }) {
                TextField(
                    state = hexState,
                    outline = if (hexValid) Outline.None else Outline.Error,
                    modifier = Modifier
                        .weight(1f)
                        .trackAuthoringTextInputFocus()
                        .testTag("$testTagPrefix-hex"),
                )
            }

            if (allowClearFill) {
                Tooltip(tooltip = { Text("Clear fill (transparent)") }) {
                    OutlinedButton(
                        onClick = { onColorChanged(Color.Transparent) },
                        modifier = Modifier
                            .accessibleFocusRing(clearFocused, focusRingColor)
                            .onFocusChanged { clearFocused = it.isFocused }
                            .testTag("$testTagPrefix-clear"),
                    ) {
                        Text("Clear", style = JewelTheme.typography.small)
                    }
                }
            }

            Tooltip(tooltip = { Text("Reset $label to default") }) {
                IconButton(
                    onClick = onReset,
                    enabled = isOverridden,
                    modifier = Modifier
                        .size(24.dp)
                        .accessibleFocusRing(resetFocused, focusRingColor)
                        .onFocusChanged { resetFocused = it.isFocused }
                        .testTag("$testTagPrefix-reset"),
                ) {
                    Icon(
                        key = AllIconsKeys.Actions.Rollback,
                        contentDescription = "Reset $label",
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        }

        if (!hexValid) {
            InfoText(
                text = "Expected #RRGGBB or #AARRGGBB",
                color = globalColors.outlines.error,
                modifier = Modifier.testTag("$testTagPrefix-hex-error"),
            )
        }

        if (contrastAgainst != null) {
            val effectiveFg = if (color.alpha < 0.999f) color.compositeOver(contrastAgainst) else color
            val ratio = contrastRatio(effectiveFg, contrastAgainst)
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
                            text = if (passesAa) "${"%.1f".format(ratio)}:1 AA" else "${"%.1f".format(ratio)}:1 Low",
                            style = JewelTheme.typography.small.copy(fontWeight = FontWeight.Medium),
                            color = globalColors.text.normal,
                        )
                    }
                }
                if (!passesAa) {
                    Tooltip(tooltip = { Text("Adjust lightness to meet >= 4.5:1 WCAG AA contrast") }) {
                        OutlinedButton(
                            onClick = { onColorChanged(adjustColorForContrast(color, contrastAgainst)) },
                            modifier = Modifier.testTag("$testTagPrefix-fix-aa"),
                        ) {
                            Text("Fix AA", style = JewelTheme.typography.small)
                        }
                    }
                }
            }
        }

        if (expanded) {
            InlineHsvPicker(
                color = color,
                themeSwatches = listOf(
                    theme.colors.foreground,
                    theme.colors.muted,
                    theme.colors.accent,
                    theme.colors.secondary,
                    theme.colors.positive,
                    theme.colors.warning,
                    theme.colors.danger,
                    theme.colors.surface,
                    theme.colors.background,
                ),
                testTagPrefix = testTagPrefix,
                onGestureStart = onGestureStart,
                onGestureEnd = onGestureEnd,
                onColorChanged = onColorChanged,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun InlineHsvPicker(
    color: Color,
    themeSwatches: List<Color>,
    testTagPrefix: String,
    onGestureStart: () -> Unit,
    onGestureEnd: () -> Unit,
    onColorChanged: (Color) -> Unit,
) {
    val globalColors = JewelTheme.globalColors
    val hsv = colorToHsv(color)
    val hue = hsv[0]
    val sat = hsv[1]
    val value = hsv[2]
    val alpha = color.alpha

    val latestHue by rememberUpdatedState(hue)
    val latestAlpha by rememberUpdatedState(alpha)
    val latestOnStart by rememberUpdatedState(onGestureStart)
    val latestOnEnd by rememberUpdatedState(onGestureEnd)
    val latestOnChange by rememberUpdatedState(onColorChanged)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, globalColors.borders.normal, RoundedCornerShape(6.dp))
            .background(globalColors.panelBackground, RoundedCornerShape(6.dp))
            .padding(8.dp)
            .testTag("$testTagPrefix-picker"),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val pureHueColor = hsvToColor(hue, 1f, 1f, 1f)
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(88.dp)
                .clip(RoundedCornerShape(4.dp))
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        latestOnStart()
                        try {
                            fun emit(pos: Offset) {
                                val s = (pos.x / size.width.toFloat().coerceAtLeast(1f)).coerceIn(0f, 1f)
                                val v = (1f - pos.y / size.height.toFloat().coerceAtLeast(1f)).coerceIn(0f, 1f)
                                latestOnChange(hsvToColor(latestHue, s, v, latestAlpha.coerceAtLeast(0.05f)))
                            }
                            emit(down.position)
                            while (true) {
                                val ev = awaitPointerEvent()
                                val ch = ev.changes.firstOrNull() ?: break
                                if (!ch.pressed) break
                                ch.consume()
                                emit(ch.position)
                            }
                        } finally {
                            latestOnEnd()
                        }
                    }
                },
        ) {
            drawRect(brush = Brush.horizontalGradient(listOf(Color.White, pureHueColor)))
            drawRect(brush = Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
            val cx = sat * size.width
            val cy = (1f - value) * size.height
            drawCircle(
                color = Color.White,
                radius = 5.dp.toPx(),
                center = Offset(cx, cy),
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx()),
            )
            drawCircle(
                color = Color.Black,
                radius = 6.dp.toPx(),
                center = Offset(cx, cy),
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.dp.toPx()),
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("Hue", style = JewelTheme.typography.small, color = globalColors.text.info, modifier = Modifier.width(38.dp))
            var hueDragging by remember { mutableStateOf(false) }
            Slider(
                value = hue,
                onValueChange = { nextHue ->
                    if (!hueDragging) {
                        hueDragging = true
                        onGestureStart()
                    }
                    onColorChanged(hsvToColor(nextHue, sat, value, alpha))
                },
                onValueChangeFinished = {
                    if (hueDragging) {
                        hueDragging = false
                        onGestureEnd()
                    }
                },
                valueRange = 0f..360f,
                modifier = Modifier.weight(1f),
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("Alpha", style = JewelTheme.typography.small, color = globalColors.text.info, modifier = Modifier.width(38.dp))
            var alphaDragging by remember { mutableStateOf(false) }
            Slider(
                value = alpha,
                onValueChange = { nextAlpha ->
                    if (!alphaDragging) {
                        alphaDragging = true
                        onGestureStart()
                    }
                    onColorChanged(color.copy(alpha = nextAlpha.coerceIn(0f, 1f)))
                },
                onValueChangeFinished = {
                    if (alphaDragging) {
                        alphaDragging = false
                        onGestureEnd()
                    }
                },
                valueRange = 0f..1f,
                modifier = Modifier.weight(1f),
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            themeSwatches.distinct().forEachIndexed { idx, swatch ->
                Tooltip(tooltip = { Text("Apply theme color ${formatHexColor(swatch)}") }) {
                    Box(
                        modifier = Modifier
                            .size(18.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(swatch)
                            .border(1.dp, globalColors.borders.normal, RoundedCornerShape(3.dp))
                            .pointerHoverIcon(PointerIcon.Hand)
                            .clickable { onColorChanged(swatch) }
                            .testTag("$testTagPrefix-theme-swatch-$idx"),
                    )
                }
            }
        }
    }
}
