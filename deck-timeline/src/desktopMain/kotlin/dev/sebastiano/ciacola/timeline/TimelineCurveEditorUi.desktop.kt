package dev.sebastiano.ciacola.timeline

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.sebastiano.ciacola.document.Curve
import dev.sebastiano.ciacola.document.CurveEvaluator
import dev.sebastiano.ciacola.ui.accessibleFocusRing
import dev.sebastiano.ciacola.ui.rememberAccessibleFocusRingColor
import java.util.Locale
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.Dropdown
import org.jetbrains.jewel.ui.component.GroupHeader
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

internal data class DocumentCurvePreset(
    val id: String,
    val displayName: String,
    val tooltip: String,
    val curve: Curve,
)

internal val DOCUMENT_CURVE_PRESETS: List<DocumentCurvePreset> = listOf(
    DocumentCurvePreset(
        id = "ease",
        displayName = "Ease",
        tooltip = "Standard Ease (0.25, 0.10, 0.25, 1.00)",
        curve = Curve.Preset("ease"),
    ),
    DocumentCurvePreset(
        id = "easeIn",
        displayName = "Ease in",
        tooltip = "Accelerating entrance (0.42, 0.00, 1.00, 1.00)",
        curve = Curve.Preset("easeIn"),
    ),
    DocumentCurvePreset(
        id = "easeOut",
        displayName = "Ease out",
        tooltip = "Decelerating settle (0.00, 0.00, 0.58, 1.00)",
        curve = Curve.Preset("easeOut"),
    ),
    DocumentCurvePreset(
        id = "easeInOut",
        displayName = "Ease in-out",
        tooltip = "Symmetric S-curve (0.42, 0.00, 0.58, 1.00)",
        curve = Curve.Preset("easeInOut"),
    ),
    DocumentCurvePreset(
        id = "fastOutSlowIn",
        displayName = "Standard",
        tooltip = "Material FastOutSlowIn (0.40, 0.00, 0.20, 1.00)",
        curve = Curve.Preset("fastOutSlowIn"),
    ),
    DocumentCurvePreset(
        id = "keynote.magicMove",
        displayName = "Keynote · Magic move",
        tooltip = "Keynote Magic Move (0.40, 0.00, 0.20, 1.00)",
        curve = Curve.Preset("keynote.magicMove"),
    ),
    DocumentCurvePreset(
        id = "keynote.pop",
        displayName = "Keynote · Pop",
        tooltip = "Keynote Pop overshoot (0.17, 1.32, 0.32, 1.00)",
        curve = Curve.Preset("keynote.pop"),
    ),
    DocumentCurvePreset(
        id = "linear",
        displayName = "Linear",
        tooltip = "Linear constant speed (0.00, 0.00, 1.00, 1.00)",
        curve = Curve.Preset("linear"),
    ),
    DocumentCurvePreset(
        id = "spring",
        displayName = "Spring",
        tooltip = "Analytical spring physics interpolation",
        curve = Curve.Spring(damping = 0.72, stiffness = 380.0),
    ),
)

internal fun resolveBezierCoordinates(curve: Curve): Curve.Bezier = when (curve) {
    is Curve.Bezier -> curve
    is Curve.Preset -> CurveEvaluator.PRESET_BEZIERS[curve.name] ?: Curve.Bezier(0.42, 0.0, 0.58, 1.0)
    is Curve.Spring -> Curve.Bezier(0.42, 0.0, 0.58, 1.0)
}

internal fun formatDocumentCurveAsKotlin(curve: Curve): String = when (curve) {
    is Curve.Spring -> {
        val damping = "%.2f".format(Locale.US, curve.damping.coerceIn(CurveEvaluator.SPRING_MIN_DAMPING, CurveEvaluator.SPRING_MAX_DAMPING))
        val stiffness = curve.stiffness.coerceIn(CurveEvaluator.SPRING_MIN_STIFFNESS, CurveEvaluator.SPRING_MAX_STIFFNESS).roundToInt()
        "spring(dampingRatio = ${damping}f, stiffness = ${stiffness}f)"
    }
    else -> {
        val bez = resolveBezierCoordinates(curve)
        val x1 = "%.2f".format(Locale.US, bez.x1.coerceIn(0.0, 1.0))
        val y1 = "%.2f".format(Locale.US, bez.y1.coerceIn(-0.6, 1.6))
        val x2 = "%.2f".format(Locale.US, bez.x2.coerceIn(0.0, 1.0))
        val y2 = "%.2f".format(Locale.US, bez.y2.coerceIn(-0.6, 1.6))
        "CubicBezierEasing(${x1}f, ${y1}f, ${x2}f, ${y2}f)"
    }
}

private fun displayNameForDocumentCurve(curve: Curve): String = TimelineDisplayNames.curveName(curve)

/**
 * Interactive Cubic-Bezier & Spring Curve Editor wired directly to [DeckDocumentStore] via
 * explicit gesture transactions (`beginGesture` / `commitActiveGesture` / `cancelActiveGesture`).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DocumentTimelineCurveEditor(
    targetKey: String,
    curve: Curve,
    onBeginGesture: () -> Unit,
    onCommitGesture: () -> Unit,
    onCancelGesture: () -> Unit,
    onCurveChanged: (curve: Curve, inGesture: Boolean) -> Unit,
    clipboardWriter: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val globalColors = JewelTheme.globalColors
    val focusRingColor = rememberAccessibleFocusRingColor()
    val scope = rememberCoroutineScope()
    var copiedFlash by remember(targetKey) { mutableStateOf(false) }
    var flashJob by remember { mutableStateOf<Job?>(null) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("ciacola-curve-editor"),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Shape",
                style = studioLabelStyle(),
                color = globalColors.text.normal,
                modifier = Modifier.weight(1f),
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (copiedFlash) {
                    Text(
                        text = "Copied!",
                        style = JewelTheme.typography.small,
                        color = globalColors.text.info,
                        modifier = Modifier.testTag("ciacola-inspector-copy-curve-feedback"),
                    )
                }
                Tooltip(tooltip = { Text(if (copiedFlash) "Copied!" else "Copy Curve Kotlin") }) {
                    IconButton(
                        onClick = {
                            clipboardWriter(formatDocumentCurveAsKotlin(curve))
                            copiedFlash = true
                            flashJob?.cancel()
                            flashJob = scope.launch {
                                delay(1_800L)
                                copiedFlash = false
                            }
                        },
                        modifier = Modifier
                            .size(22.dp)
                            .studioActivatableFocus(focusRingColor)
                            .testTag("ciacola-inspector-copy-curve"),
                    ) {
                        Icon(
                            key = AllIconsKeys.Actions.Copy,
                            contentDescription = "Copy Curve Kotlin",
                            modifier = Modifier.size(13.dp),
                        )
                    }
                }
            }
        }

        val quickIds = remember { listOf("easeInOut", "linear", "keynote.pop", "spring") }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            for (id in quickIds) {
                val preset = DOCUMENT_CURVE_PRESETS.first { it.id == id }
                val isSelected = when {
                    preset.curve is Curve.Spring -> curve is Curve.Spring
                    curve is Curve.Preset -> curve.name == id
                    else -> false
                }
                CurveQuickButton(
                    label = when (id) { "easeInOut" -> "Ease"; "keynote.pop" -> "Pop"; else -> preset.displayName },
                    tooltip = preset.tooltip,
                    selected = isSelected,
                    modifier = Modifier.weight(1f),
                ) {
                    onCurveChanged(preset.curve, false)
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Preset",
                style = studioLabelStyle(),
                color = globalColors.text.normal,
            )
            Dropdown(
                modifier = Modifier
                    .width(172.dp)
                    .studioActivatableFocus(focusRingColor)
                    .testTag("ciacola-inspector-curve-preset-dropdown"),
                menuContent = {
                    for (preset in DOCUMENT_CURVE_PRESETS) {
                        val isSelected = when {
                            preset.curve is Curve.Spring -> curve is Curve.Spring
                            curve is Curve.Preset -> curve.name == preset.id
                            else -> false
                        }
                        selectableItem(
                            selected = isSelected,
                            onClick = { onCurveChanged(preset.curve, false) },
                        ) {
                            Text(preset.displayName, style = JewelTheme.typography.small)
                        }
                    }
                },
            ) {
                Text(
                    text = displayNameForDocumentCurve(curve),
                    style = JewelTheme.typography.small,
                    maxLines = 1,
                )
            }
        }

        if (curve is Curve.Spring) {
            SpringCurveCanvasPreview(curve = curve)
            DocumentSpringCurveControls(
                targetKey = targetKey,
                curve = curve,
                onBeginGesture = onBeginGesture,
                onCommitGesture = onCommitGesture,
                onCurveChanged = onCurveChanged,
            )
        } else {
            val bezier = resolveBezierCoordinates(curve)
            DocumentBezierCurveCanvas(
                targetKey = targetKey,
                bezier = bezier,
                label = displayNameForDocumentCurve(curve),
                onBeginGesture = onBeginGesture,
                onCommitGesture = onCommitGesture,
                onCancelGesture = onCancelGesture,
                onBezierChanged = { updated -> onCurveChanged(updated, true) },
            )
            DocumentBezierNumericInputsRow(
                bezier = bezier,
                onBezierCommitted = { updated -> onCurveChanged(updated, false) },
            )
        }
    }
}

@Composable
private fun SpringCurveCanvasPreview(curve: Curve.Spring) {
    val globalColors = JewelTheme.globalColors
    val palette = JewelTheme.colorPalette
    val isDark = JewelTheme.isDark
    val lineColor = palette.tealOrNull(if (isDark) 6 else 5) ?: Color(0xFF35B5AC)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(84.dp)
            .border(1.dp, globalColors.borders.normal, RoundedCornerShape(6.dp))
            .padding(8.dp),
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(68.dp)
                .testTag("ciacola-spring-curve-preview"),
        ) {
            val w = size.width.coerceAtLeast(1f)
            val h = size.height.coerceAtLeast(1f)
            val yZero = h * 0.85f
            val yOne = h * 0.22f
            val spanY = yZero - yOne

            drawLine(globalColors.borders.normal.copy(alpha = 0.45f), Offset(0f, yOne), Offset(w, yOne), strokeWidth = 1f)
            drawLine(globalColors.borders.normal.copy(alpha = 0.35f), Offset(0f, yZero), Offset(w, yZero), strokeWidth = 1f)

            val path = Path()
            val samples = 64
            for (i in 0..samples) {
                val frac = i.toFloat() / samples.toFloat()
                val v = CurveEvaluator.evaluateFraction(curve, frac)
                val px = frac * w
                val py = (yZero - v * spanY).coerceIn(0f, h)
                if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
            drawPath(path, color = lineColor, style = Stroke(width = 2.2f))
        }
    }
}

@Composable
private fun DocumentSpringCurveControls(
    targetKey: String,
    curve: Curve.Spring,
    onBeginGesture: () -> Unit,
    onCommitGesture: () -> Unit,
    onCurveChanged: (Curve, Boolean) -> Unit,
) {
    val clampedDamping = curve.damping.coerceIn(CurveEvaluator.SPRING_MIN_DAMPING, CurveEvaluator.SPRING_MAX_DAMPING)
    val clampedStiffness = curve.stiffness.coerceIn(CurveEvaluator.SPRING_MIN_STIFFNESS, CurveEvaluator.SPRING_MAX_STIFFNESS)
    var draggingDamping by remember(targetKey) { mutableStateOf(false) }
    var draggingStiffness by remember(targetKey) { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Damping", style = studioLabelStyle(), color = JewelTheme.globalColors.text.normal)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(
                    onClick = {
                        val next = ((clampedDamping - 0.05) * 100.0).roundToInt() / 100.0
                        onCurveChanged(
                            curve.copy(damping = next.coerceIn(CurveEvaluator.SPRING_MIN_DAMPING, CurveEvaluator.SPRING_MAX_DAMPING)),
                            false,
                        )
                    },
                    modifier = Modifier.testTag("ciacola-inspector-damping-dec"),
                ) { Text("-", style = JewelTheme.typography.small) }
                Text("%.2f".format(Locale.US, clampedDamping), style = JewelTheme.typography.small)
                OutlinedButton(
                    onClick = {
                        val next = ((clampedDamping + 0.05) * 100.0).roundToInt() / 100.0
                        onCurveChanged(
                            curve.copy(damping = next.coerceIn(CurveEvaluator.SPRING_MIN_DAMPING, CurveEvaluator.SPRING_MAX_DAMPING)),
                            false,
                        )
                    },
                    modifier = Modifier.testTag("ciacola-inspector-damping-inc"),
                ) { Text("+", style = JewelTheme.typography.small) }
            }
        }
        Slider(
            value = clampedDamping.toFloat(),
            valueRange = CurveEvaluator.SPRING_MIN_DAMPING.toFloat()..CurveEvaluator.SPRING_MAX_DAMPING.toFloat(),
            onValueChange = { raw ->
                if (!draggingDamping) {
                    draggingDamping = true
                    onBeginGesture()
                }
                val snapped = (raw * 100f).roundToInt() / 100.0
                onCurveChanged(
                    curve.copy(damping = snapped.coerceIn(CurveEvaluator.SPRING_MIN_DAMPING, CurveEvaluator.SPRING_MAX_DAMPING)),
                    true,
                )
            },
            onValueChangeFinished = {
                if (draggingDamping) {
                    draggingDamping = false
                    onCommitGesture()
                }
            },
            modifier = Modifier.fillMaxWidth().testTag("ciacola-inspector-damping-slider"),
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Stiffness", style = studioLabelStyle(), color = JewelTheme.globalColors.text.normal)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(
                    onClick = {
                        val next = (clampedStiffness - 100.0).coerceIn(CurveEvaluator.SPRING_MIN_STIFFNESS, CurveEvaluator.SPRING_MAX_STIFFNESS)
                        onCurveChanged(curve.copy(stiffness = next), false)
                    },
                    modifier = Modifier.testTag("ciacola-inspector-stiffness-dec"),
                ) { Text("-", style = JewelTheme.typography.small) }
                Text("${clampedStiffness.roundToInt()}", style = JewelTheme.typography.small)
                OutlinedButton(
                    onClick = {
                        val next = (clampedStiffness + 100.0).coerceIn(CurveEvaluator.SPRING_MIN_STIFFNESS, CurveEvaluator.SPRING_MAX_STIFFNESS)
                        onCurveChanged(curve.copy(stiffness = next), false)
                    },
                    modifier = Modifier.testTag("ciacola-inspector-stiffness-inc"),
                ) { Text("+", style = JewelTheme.typography.small) }
            }
        }
        Slider(
            value = clampedStiffness.toFloat(),
            valueRange = CurveEvaluator.SPRING_MIN_STIFFNESS.toFloat()..CurveEvaluator.SPRING_MAX_STIFFNESS.toFloat(),
            onValueChange = { raw ->
                if (!draggingStiffness) {
                    draggingStiffness = true
                    onBeginGesture()
                }
                val snapped = ((raw / 10f).roundToInt() * 10.0)
                    .coerceIn(CurveEvaluator.SPRING_MIN_STIFFNESS, CurveEvaluator.SPRING_MAX_STIFFNESS)
                onCurveChanged(curve.copy(stiffness = snapped), true)
            },
            onValueChangeFinished = {
                if (draggingStiffness) {
                    draggingStiffness = false
                    onCommitGesture()
                }
            },
            modifier = Modifier.fillMaxWidth().testTag("ciacola-inspector-stiffness-slider"),
        )
        val settleMs = CurveEvaluator.springSettleDurationMs(clampedDamping, clampedStiffness)
        InfoText("Estimated settle: ~${settleMs}ms")
    }
}

@Composable
private fun DocumentBezierCurveCanvas(
    targetKey: String,
    bezier: Curve.Bezier,
    label: String,
    onBeginGesture: () -> Unit,
    onCommitGesture: () -> Unit,
    onCancelGesture: () -> Unit,
    onBezierChanged: (Curve.Bezier) -> Unit,
) {
    val globalColors = JewelTheme.globalColors
    val palette = JewelTheme.colorPalette
    val isDark = JewelTheme.isDark
    val p1Color = palette.tealOrNull(if (isDark) 6 else 5) ?: Color(0xFF35B5AC)
    val p2Color = palette.orangeOrNull(if (isDark) 6 else 5) ?: Color(0xFFE57C4E)

    val latestBezier by rememberUpdatedState(bezier)
    val latestOnBegin by rememberUpdatedState(onBeginGesture)
    val latestOnCommit by rememberUpdatedState(onCommitGesture)
    val latestOnCancel by rememberUpdatedState(onCancelGesture)
    val latestOnChanged by rememberUpdatedState(onBezierChanged)

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(112.dp)
            .border(1.dp, globalColors.borders.normal, RoundedCornerShape(6.dp))
            .padding(8.dp)
            .focusGroup(),
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .testTag("ciacola-bezier-editor")
                .semantics {
                    contentDescription = "Cubic Bezier curve editor, $label"
                    stateDescription = "P1(${"%.2f".format(Locale.US, latestBezier.x1)}, ${"%.2f".format(Locale.US, latestBezier.y1)}), " +
                        "P2(${"%.2f".format(Locale.US, latestBezier.x2)}, ${"%.2f".format(Locale.US, latestBezier.y2)})"
                }
                // Keyed ONLY on stable targetKey (never curve coordinates) so dragging handles
                // survives recomposition and cancels cleanly on coroutine interruption.
                .pointerInput(targetKey) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        val start = latestBezier
                        val w = size.width.toFloat().coerceAtLeast(1f)
                        val h = size.height.toFloat().coerceAtLeast(1f)
                        val p1Px = Offset(start.x1.toFloat() * w, (1f - start.y1.toFloat().coerceIn(0f, 1f)) * h)
                        val p2Px = Offset(start.x2.toFloat() * w, (1f - start.y2.toFloat().coerceIn(0f, 1f)) * h)
                        val dragP1 = hypot(down.position.x - p1Px.x, down.position.y - p1Px.y) <=
                            hypot(down.position.x - p2Px.x, down.position.y - p2Px.y)

                        var current = start
                        var completedNormally = false
                        latestOnBegin()
                        try {
                            while (true) {
                                val ev = awaitPointerEvent()
                                val ch = ev.changes.firstOrNull() ?: break
                                if (!ch.pressed) {
                                    completedNormally = true
                                    break
                                }
                                ch.consume()
                                val nx = ((ch.position.x / w).coerceIn(0f, 1f) * 100f).roundToInt() / 100.0
                                val ny = ((1f - ch.position.y / h).coerceIn(-0.4f, 1.4f) * 100f).roundToInt() / 100.0
                                current = if (dragP1) {
                                    current.copy(x1 = nx, y1 = ny)
                                } else {
                                    current.copy(x2 = nx, y2 = ny)
                                }
                                latestOnChanged(current)
                            }
                        } finally {
                            if (completedNormally) {
                                latestOnCommit()
                            } else {
                                latestOnCancel()
                            }
                        }
                    }
                },
        ) {
            val active = latestBezier
            val w = size.width
            val h = size.height
            val origin = Offset(0f, h)
            val dest = Offset(w, 0f)
            val p1 = Offset(active.x1.toFloat().coerceIn(0f, 1f) * w, (1f - active.y1.toFloat().coerceIn(-0.2f, 1.2f)) * h)
            val p2 = Offset(active.x2.toFloat().coerceIn(0f, 1f) * w, (1f - active.y2.toFloat().coerceIn(-0.2f, 1.2f)) * h)

            drawLine(globalColors.borders.normal.copy(alpha = 0.45f), origin, dest, strokeWidth = 1f)
            drawLine(p1Color.copy(alpha = 0.65f), origin, p1, strokeWidth = 1.5f)
            drawLine(p2Color.copy(alpha = 0.65f), dest, p2, strokeWidth = 1.5f)

            val path = Path().apply {
                moveTo(origin.x, origin.y)
                cubicTo(p1.x, p1.y, p2.x, p2.y, dest.x, dest.y)
            }
            drawPath(path, color = globalColors.text.normal, style = Stroke(width = 2.2f))
            drawCircle(color = p1Color, radius = 5.5f, center = p1)
            drawCircle(color = p2Color, radius = 5.5f, center = p2)
        }
        BezierHandleFocusTarget(
            tag = "ciacola-bezier-handle-p1",
            description = "Bezier control point P1, $label",
            x = latestBezier.x1,
            y = latestBezier.y1,
            maxWidth = maxWidth,
            maxHeight = maxHeight,
            accentColor = p1Color,
            onNudge = { dx, dy ->
                val cur = latestBezier
                val nx = ((cur.x1 + dx).coerceIn(0.0, 1.0) * 100.0).roundToInt() / 100.0
                val ny = ((cur.y1 + dy).coerceIn(-0.4, 1.4) * 100.0).roundToInt() / 100.0
                latestOnBegin()
                latestOnChanged(cur.copy(x1 = nx, y1 = ny))
                latestOnCommit()
            },
        )
        BezierHandleFocusTarget(
            tag = "ciacola-bezier-handle-p2",
            description = "Bezier control point P2, $label",
            x = latestBezier.x2,
            y = latestBezier.y2,
            maxWidth = maxWidth,
            maxHeight = maxHeight,
            accentColor = p2Color,
            onNudge = { dx, dy ->
                val cur = latestBezier
                val nx = ((cur.x2 + dx).coerceIn(0.0, 1.0) * 100.0).roundToInt() / 100.0
                val ny = ((cur.y2 + dy).coerceIn(-0.4, 1.4) * 100.0).roundToInt() / 100.0
                latestOnBegin()
                latestOnChanged(cur.copy(x2 = nx, y2 = ny))
                latestOnCommit()
            },
        )
    }
}

@Composable
private fun BezierHandleFocusTarget(
    tag: String,
    description: String,
    x: Double,
    y: Double,
    maxWidth: Dp,
    maxHeight: Dp,
    accentColor: Color,
    onNudge: (dx: Double, dy: Double) -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val half = 10.dp
    val cx = maxWidth * x.toFloat().coerceIn(0f, 1f)
    val cy = maxHeight * (1f - y.toFloat().coerceIn(-0.2f, 1.2f))
    Box(
        modifier = Modifier
            .offset(x = cx - half, y = cy - half)
            .size(20.dp)
            .accessibleFocusRing(focused, accentColor, cornerRadius = 10.dp)
            .focusable(interactionSource = interaction)
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                val step = if (event.isShiftPressed) 0.01 else 0.05
                when (event.key) {
                    Key.DirectionLeft -> { onNudge(-step, 0.0); true }
                    Key.DirectionRight -> { onNudge(+step, 0.0); true }
                    Key.DirectionUp -> { onNudge(0.0, +step); true }
                    Key.DirectionDown -> { onNudge(0.0, -step); true }
                    else -> false
                }
            }
            .semantics {
                contentDescription = description
                stateDescription = "X ${"%.2f".format(Locale.US, x)}, Y ${"%.2f".format(Locale.US, y)}"
            }
            .testTag(tag),
    )
}

@Composable
private fun DocumentBezierNumericInputsRow(
    bezier: Curve.Bezier,
    onBezierCommitted: (Curve.Bezier) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            BezierCoordInput(
                label = "x1",
                value = bezier.x1,
                min = 0.0,
                max = 1.0,
                testTag = "ciacola-bezier-input-x1",
                modifier = Modifier.weight(1f),
            ) { next -> onBezierCommitted(bezier.copy(x1 = next)) }
            BezierCoordInput(
                label = "y1",
                value = bezier.y1,
                min = -0.6,
                max = 1.6,
                testTag = "ciacola-bezier-input-y1",
                modifier = Modifier.weight(1f),
            ) { next -> onBezierCommitted(bezier.copy(y1 = next)) }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            BezierCoordInput(
                label = "x2",
                value = bezier.x2,
                min = 0.0,
                max = 1.0,
                testTag = "ciacola-bezier-input-x2",
                modifier = Modifier.weight(1f),
            ) { next -> onBezierCommitted(bezier.copy(x2 = next)) }
            BezierCoordInput(
                label = "y2",
                value = bezier.y2,
                min = -0.6,
                max = 1.6,
                testTag = "ciacola-bezier-input-y2",
                modifier = Modifier.weight(1f),
            ) { next -> onBezierCommitted(bezier.copy(y2 = next)) }
        }
    }
}

@Composable
private fun BezierCoordInput(
    label: String,
    value: Double,
    min: Double,
    max: Double,
    testTag: String,
    modifier: Modifier = Modifier,
    onCommit: (Double) -> Unit,
) {
    val formatted = "%.2f".format(Locale.US, value)
    val textState = rememberTextFieldState(initialText = formatted)
    var isFocused by remember { mutableStateOf(false) }

    LaunchedEffect(formatted, isFocused) {
        if (!isFocused && textState.text.toString() != formatted) {
            textState.setTextAndPlaceCursorAtEnd(formatted)
        }
    }

    fun commitText() {
        val parsed = textState.text.toString().trim().toDoubleOrNull()
        if (parsed != null && parsed.isFinite()) {
            val clamped = parsed.coerceIn(min, max)
            onCommit(clamped)
            textState.setTextAndPlaceCursorAtEnd("%.2f".format(Locale.US, clamped))
        } else {
            textState.setTextAndPlaceCursorAtEnd(formatted)
        }
    }

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = studioLabelStyle(), color = JewelTheme.globalColors.text.normal)
        TextField(
            state = textState,
            modifier = Modifier
                .weight(1f)
                .studioTextInputFocus()
                .onFocusChanged { state ->
                    val wasFocused = isFocused
                    isFocused = state.isFocused
                    if (wasFocused && !state.isFocused) commitText()
                }
                .onPreviewKeyEvent { ev ->
                    if (ev.type == KeyEventType.KeyDown && (ev.key == Key.Enter || ev.key == Key.NumPadEnter)) {
                        commitText()
                        true
                    } else {
                        false
                    }
                }
                .testTag(testTag),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CurveQuickButton(
    label: String,
    tooltip: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val focusRingColor = rememberAccessibleFocusRingColor()
    // Weight/size modifiers go on the Tooltip (the Row's direct child), selection is a tinted chip.
    Tooltip(tooltip = { Text(tooltip) }, modifier = modifier) {
        CompactToggleChip(
            label = label,
            selected = selected,
            onClick = onClick,
            tag = "ciacola-inspector-curve-${label.lowercase()}",
            modifier = Modifier
                .fillMaxWidth()
                .studioActivatableFocus(focusRingColor)
                .semantics {
                    this.selected = selected
                    this.role = Role.RadioButton
                    this.stateDescription = if (selected) "Selected" else "Not selected"
                },
        )
    }
}
