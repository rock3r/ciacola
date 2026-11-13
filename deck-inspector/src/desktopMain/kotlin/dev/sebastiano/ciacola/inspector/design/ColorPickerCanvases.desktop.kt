package dev.sebastiano.ciacola.inspector.design

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import dev.sebastiano.ciacola.ui.rememberAccessibleFocusRingColor
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.Tooltip

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SaturationValueCanvas(
    tokenId: String,
    label: String,
    hsv: HsvColor,
    onGestureStart: () -> Unit,
    onGestureEnd: () -> Unit,
    onSatValChanged: (saturation: Float, value: Float, coalesce: Boolean) -> Unit,
) {
    val globalColors = JewelTheme.globalColors
    val focusRingColor = rememberAccessibleFocusRingColor()
    val latestHsv by rememberUpdatedState(hsv)
    val latestOnGestureStart by rememberUpdatedState(onGestureStart)
    val latestOnGestureEnd by rememberUpdatedState(onGestureEnd)
    val latestOnSatValChanged by rememberUpdatedState(onSatValChanged)
    val focusRequester = remember { FocusRequester() }
    val hueColor = hsvToColor(hsv.hue, 1f, 1f, 1f)

    Tooltip(tooltip = { Text("Drag or use arrow keys to adjust $label saturation and brightness") }) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(112.dp)
                .clip(RoundedCornerShape(5.dp))
                .border(1.dp, globalColors.borders.normal, RoundedCornerShape(5.dp))
                .focusRequester(focusRequester)
                .designActivatableFocus(focusRingColor, cornerRadius = 5.dp, makeFocusable = true)
                .onPreviewKeyEvent { ev ->
                    if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    val step = 0.05f
                    when (ev.key) {
                        Key.DirectionLeft -> {
                            latestOnSatValChanged((latestHsv.saturation - step).coerceIn(0f, 1f), latestHsv.value, false)
                            true
                        }
                        Key.DirectionRight -> {
                            latestOnSatValChanged((latestHsv.saturation + step).coerceIn(0f, 1f), latestHsv.value, false)
                            true
                        }
                        Key.DirectionUp -> {
                            latestOnSatValChanged(latestHsv.saturation, (latestHsv.value + step).coerceIn(0f, 1f), false)
                            true
                        }
                        Key.DirectionDown -> {
                            latestOnSatValChanged(latestHsv.saturation, (latestHsv.value - step).coerceIn(0f, 1f), false)
                            true
                        }
                        else -> false
                    }
                }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        runCatching { focusRequester.requestFocus() }
                        val w = size.width.toFloat().coerceAtLeast(1f)
                        val h = size.height.toFloat().coerceAtLeast(1f)
                        latestOnGestureStart()
                        try {
                            latestOnSatValChanged(
                                (down.position.x / w).coerceIn(0f, 1f),
                                (1f - down.position.y / h).coerceIn(0f, 1f),
                                true,
                            )
                            while (true) {
                                val ev = awaitPointerEvent()
                                val ch = ev.changes.firstOrNull() ?: break
                                if (!ch.pressed) break
                                ch.consume()
                                latestOnSatValChanged(
                                    (ch.position.x / w).coerceIn(0f, 1f),
                                    (1f - ch.position.y / h).coerceIn(0f, 1f),
                                    true,
                                )
                            }
                        } finally {
                            latestOnGestureEnd()
                        }
                    }
                }
                .testTag("design-color-$tokenId-sv-canvas")
                .semantics {
                    contentDescription = "$label saturation and brightness picker"
                    stateDescription = "Saturation ${(hsv.saturation * 100).toInt()}%, Brightness ${(hsv.value * 100).toInt()}%"
                },
        ) {
            drawRect(Brush.horizontalGradient(listOf(Color.White, hueColor)))
            drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
            val thumb = Offset(hsv.saturation * size.width, (1f - hsv.value) * size.height)
            drawCircle(color = Color.Black.copy(alpha = 0.65f), radius = 6.5f, center = thumb, style = Stroke(width = 2.5f))
            drawCircle(color = Color.White, radius = 5.5f, center = thumb, style = Stroke(width = 2f))
        }
    }
}

@Composable
internal fun HueSliderBar(
    tokenId: String,
    label: String,
    hue: Float,
    onGestureStart: () -> Unit,
    onGestureEnd: () -> Unit,
    onHueChanged: (Float, coalesce: Boolean) -> Unit,
) {
    val spectrum = remember {
        listOf(
            Color(0xFFFF0000),
            Color(0xFFFFFF00),
            Color(0xFF00FF00),
            Color(0xFF00FFFF),
            Color(0xFF0000FF),
            Color(0xFFFF00FF),
            Color(0xFFFF0000),
        )
    }
    HorizontalColorChannelBar(
        tooltip = "Drag or use Left/Right arrows to adjust $label hue",
        testTag = "design-color-$tokenId-hue-bar",
        contentDescriptionText = "$label hue slider",
        stateDescriptionText = "${hue.toInt()} degrees",
        fraction = (hue / 360f).coerceIn(0f, 1f),
        keyStepFraction = 5f / 360f,
        wrapAround = true,
        showCheckerboard = false,
        onGestureStart = onGestureStart,
        onGestureEnd = onGestureEnd,
        onFractionChanged = { f, coalesce -> onHueChanged((f * 360f).coerceIn(0f, 360f), coalesce) },
    ) {
        drawRect(Brush.horizontalGradient(spectrum))
    }
}

@Composable
internal fun AlphaSliderBar(
    tokenId: String,
    label: String,
    color: Color,
    alpha: Float,
    onGestureStart: () -> Unit,
    onGestureEnd: () -> Unit,
    onAlphaChanged: (Float, coalesce: Boolean) -> Unit,
) {
    val opaque = color.copy(alpha = 1f)
    HorizontalColorChannelBar(
        tooltip = "Drag or use Left/Right arrows to adjust $label opacity",
        testTag = "design-color-$tokenId-alpha-bar",
        contentDescriptionText = "$label alpha slider",
        stateDescriptionText = "${(alpha * 100).toInt()} percent",
        fraction = alpha.coerceIn(0f, 1f),
        keyStepFraction = 0.05f,
        wrapAround = false,
        showCheckerboard = true,
        onGestureStart = onGestureStart,
        onGestureEnd = onGestureEnd,
        onFractionChanged = onAlphaChanged,
    ) {
        drawRect(Brush.horizontalGradient(listOf(opaque.copy(alpha = 0f), opaque)))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HorizontalColorChannelBar(
    tooltip: String,
    testTag: String,
    contentDescriptionText: String,
    stateDescriptionText: String,
    fraction: Float,
    keyStepFraction: Float,
    wrapAround: Boolean,
    showCheckerboard: Boolean,
    onGestureStart: () -> Unit,
    onGestureEnd: () -> Unit,
    onFractionChanged: (Float, coalesce: Boolean) -> Unit,
    drawGradient: DrawScope.() -> Unit,
) {
    val globalColors = JewelTheme.globalColors
    val focusRingColor = rememberAccessibleFocusRingColor()
    val latestFraction by rememberUpdatedState(fraction)
    val latestOnGestureStart by rememberUpdatedState(onGestureStart)
    val latestOnGestureEnd by rememberUpdatedState(onGestureEnd)
    val latestOnFractionChanged by rememberUpdatedState(onFractionChanged)
    val focusRequester = remember { FocusRequester() }

    Tooltip(tooltip = { Text(tooltip) }) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(16.dp)
                .clip(RoundedCornerShape(4.dp))
                .border(1.dp, globalColors.borders.normal, RoundedCornerShape(4.dp))
                .focusRequester(focusRequester)
                .designActivatableFocus(focusRingColor, cornerRadius = 4.dp, makeFocusable = true)
                .onPreviewKeyEvent { ev ->
                    if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    val delta = when (ev.key) {
                        Key.DirectionLeft -> -keyStepFraction
                        Key.DirectionRight -> keyStepFraction
                        else -> return@onPreviewKeyEvent false
                    }
                    val next = if (wrapAround) {
                        ((latestFraction + delta) + 1f) % 1f
                    } else {
                        (latestFraction + delta).coerceIn(0f, 1f)
                    }
                    latestOnFractionChanged(next, false)
                    true
                }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        runCatching { focusRequester.requestFocus() }
                        val w = size.width.toFloat().coerceAtLeast(1f)
                        latestOnGestureStart()
                        try {
                            latestOnFractionChanged((down.position.x / w).coerceIn(0f, 1f), true)
                            while (true) {
                                val ev = awaitPointerEvent()
                                val ch = ev.changes.firstOrNull() ?: break
                                if (!ch.pressed) break
                                ch.consume()
                                latestOnFractionChanged((ch.position.x / w).coerceIn(0f, 1f), true)
                            }
                        } finally {
                            latestOnGestureEnd()
                        }
                    }
                }
                .testTag(testTag)
                .semantics {
                    contentDescription = contentDescriptionText
                    stateDescription = stateDescriptionText
                },
        ) {
            if (showCheckerboard) {
                CheckerboardBackground(Modifier.fillMaxSize())
            }
            Canvas(Modifier.fillMaxSize()) {
                drawGradient()
                val x = fraction.coerceIn(0f, 1f) * size.width
                drawLine(Color.Black.copy(alpha = 0.7f), Offset(x, 0f), Offset(x, size.height), strokeWidth = 4f)
                drawLine(Color.White, Offset(x, 0f), Offset(x, size.height), strokeWidth = 2f)
            }
        }
    }
}

@Composable
internal fun CheckerboardBackground(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val cell = 6.dp.toPx()
        val cols = (size.width / cell).toInt() + 1
        val rows = (size.height / cell).toInt() + 1
        val light = Color(0xFFD8D8D8)
        val dark = Color(0xFFA8A8A8)
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                drawRect(
                    color = if ((r + c) % 2 == 0) light else dark,
                    topLeft = Offset(c * cell, r * cell),
                    size = Size(cell, cell),
                )
            }
        }
    }
}
