package dev.sebastiano.ciacola.timeline

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.input.key.key
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
import androidx.compose.ui.unit.dp
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
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Slider
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField
import org.jetbrains.jewel.ui.component.Tooltip
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.ui.theme.colorPalette
import org.jetbrains.jewel.ui.typography

internal data class CurvePresetDefinition(
    val id: String,
    val displayName: String,
    val tooltip: String,
    val spec: TimelineCurveSpec,
)

internal val TIMELINE_CURVE_PRESETS: List<CurvePresetDefinition> = listOf(
    CurvePresetDefinition(
        id = "Ease",
        displayName = "Ease",
        tooltip = "Keynote Ease (0.25, 0.10, 0.25, 1.00)",
        spec = TimelineCurveSpec("KeynoteEase", 0.25f, 0.10f, 0.25f, 1.00f, isSpring = false),
    ),
    CurvePresetDefinition(
        id = "EaseIn",
        displayName = "EaseIn",
        tooltip = "Accelerating entrance (0.42, 0.00, 1.00, 1.00)",
        spec = TimelineCurveSpec("EaseIn", 0.42f, 0.00f, 1.00f, 1.00f, isSpring = false),
    ),
    CurvePresetDefinition(
        id = "EaseOut",
        displayName = "EaseOut",
        tooltip = "Decelerating settle (0.00, 0.00, 0.58, 1.00)",
        spec = TimelineCurveSpec("EaseOut", 0.00f, 0.00f, 0.58f, 1.00f, isSpring = false),
    ),
    CurvePresetDefinition(
        id = "EaseInOut",
        displayName = "EaseInOut",
        tooltip = "Symmetric S-curve (0.42, 0.00, 0.58, 1.00)",
        spec = TimelineCurveSpec("EaseInOut", 0.42f, 0.00f, 0.58f, 1.00f, isSpring = false),
    ),
    CurvePresetDefinition(
        id = "FastOutSlowIn",
        displayName = "FastOutSlowIn",
        tooltip = "Material FastOutSlowIn (0.40, 0.00, 0.20, 1.00)",
        spec = TimelineCurveSpec("FastOutSlowIn", 0.40f, 0.00f, 0.20f, 1.00f, isSpring = false),
    ),
    CurvePresetDefinition(
        id = "Anticipate",
        displayName = "Anticipate",
        tooltip = "Pullback anticipation (0.36, -0.35, 0.66, 1.00)",
        spec = TimelineCurveSpec("Anticipate", 0.36f, -0.35f, 0.66f, 1.00f, isSpring = false),
    ),
    CurvePresetDefinition(
        id = "Pop",
        displayName = "Pop",
        tooltip = "Pop overshoot curve (0.20, 1.25, 0.35, 1.00)",
        spec = TimelineCurveSpec("Overshoot", 0.20f, 1.25f, 0.35f, 1.00f, isSpring = false),
    ),
    CurvePresetDefinition(
        id = "Linear",
        displayName = "Linear",
        tooltip = "Linear constant speed (0.00, 0.00, 1.00, 1.00)",
        spec = TimelineCurveSpec("Linear", 0.00f, 0.00f, 1.00f, 1.00f, isSpring = false),
    ),
    CurvePresetDefinition(
        id = "Spring",
        displayName = "Spring",
        tooltip = "Spring physics interpolation",
        spec = TimelineCurveSpec("Spring", isSpring = true),
    ),
)

internal fun formatCurveAsKotlin(curve: TimelineCurveSpec): String =
    if (curve.isSpring) {
        val damping = "%.2f".format(Locale.US, curve.dampingRatio.coerceIn(0.20f, 1.50f))
        val stiffness = curve.stiffness.coerceIn(50f, 4_000f).roundToInt()
        "spring(dampingRatio = ${damping}f, stiffness = ${stiffness}f)"
    } else {
        val x1 = "%.2f".format(Locale.US, curve.x1.coerceIn(0f, 1f))
        val y1 = "%.2f".format(Locale.US, curve.y1.coerceIn(-0.6f, 1.6f))
        val x2 = "%.2f".format(Locale.US, curve.x2.coerceIn(0f, 1f))
        val y2 = "%.2f".format(Locale.US, curve.y2.coerceIn(-0.6f, 1.6f))
        "CubicBezierEasing(${x1}f, ${y1}f, ${x2}f, ${y2}f)"
    }

private fun displayNameForCurve(curve: TimelineCurveSpec): String {
    if (curve.isSpring) return "Spring"
    return TIMELINE_CURVE_PRESETS.firstOrNull { !it.spec.isSpring && it.spec.preset == curve.preset }?.displayName
        ?: curve.preset
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun TimeInterpolationInspectorSection(
    controller: TimelineStudioController,
    trackId: String,
    currentOverride: TrackOverride,
) {
    val globalColors = JewelTheme.globalColors
    val focusRingColor = rememberAccessibleFocusRingColor()
    val scope = rememberCoroutineScope()
    val curve = currentOverride.curve ?: TimelineCurveSpec()
    var copiedFlash by remember(trackId) { mutableStateOf(false) }
    var flashJob by remember { mutableStateOf<Job?>(null) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GroupHeader(
            text = if (currentOverride.curve != null) "● Time interpolation:" else "Time interpolation:",
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
                        controller.clipboardWriter(formatCurveAsKotlin(curve))
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

    val primaryIds = remember { listOf("Ease", "Linear", "Pop", "Spring") }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for (id in primaryIds) {
            val preset = TIMELINE_CURVE_PRESETS.first { it.id == id }
            val isSelected = if (preset.spec.isSpring) {
                curve.isSpring
            } else {
                !curve.isSpring && curve.preset == preset.spec.preset
            }
            CurvePresetButton(
                label = preset.displayName,
                tooltip = preset.tooltip,
                selected = isSelected,
                modifier = Modifier.weight(1f),
            ) {
                val next = if (preset.spec.isSpring) {
                    curve.copy(preset = "Spring", isSpring = true)
                } else {
                    preset.spec
                }
                controller.overridesStore.updateTrack(trackId) { it.copy(curve = next) }
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
            style = JewelTheme.typography.small,
            color = globalColors.text.info,
        )
        Dropdown(
            modifier = Modifier
                .width(176.dp)
                .studioActivatableFocus(focusRingColor)
                .testTag("ciacola-inspector-curve-preset-dropdown"),
            menuContent = {
                for (preset in TIMELINE_CURVE_PRESETS) {
                    val isSelected = if (preset.spec.isSpring) {
                        curve.isSpring
                    } else {
                        !curve.isSpring && curve.preset == preset.spec.preset
                    }
                    selectableItem(
                        selected = isSelected,
                        onClick = {
                            val next = if (preset.spec.isSpring) {
                                curve.copy(preset = "Spring", isSpring = true)
                            } else {
                                preset.spec
                            }
                            controller.overridesStore.updateTrack(trackId) { it.copy(curve = next) }
                        },
                    ) {
                        Text(preset.displayName, style = JewelTheme.typography.small)
                    }
                }
            },
        ) {
            Text(
                text = displayNameForCurve(curve),
                style = JewelTheme.typography.small,
                maxLines = 1,
            )
        }
    }

    if (curve.isSpring) {
        SpringCurveControls(
            controller = controller,
            trackId = trackId,
            curve = curve,
        )
    } else {
        BezierCurveEditorBox(
            curve = curve,
            onGestureStart = { controller.overridesStore.beginEditGesture() },
            onGestureEnd = { controller.overridesStore.endEditGesture() },
            onCurveChanged = { updatedCurve ->
                controller.overridesStore.updateTrack(trackId, coalesce = true) {
                    it.copy(curve = updatedCurve)
                }
            },
        )
        BezierNumericInputsRow(
            curve = curve,
            onCurveChanged = { updatedCurve ->
                controller.overridesStore.updateTrack(trackId) {
                    it.copy(curve = updatedCurve)
                }
            },
        )
    }
}

@Composable
private fun SpringCurveControls(
    controller: TimelineStudioController,
    trackId: String,
    curve: TimelineCurveSpec,
) {
    val clampedDamping = curve.dampingRatio.coerceIn(0.20f, 1.50f)
    val clampedStiffness = curve.stiffness.coerceIn(50f, 4_000f)
    NumericStepperRow(
        label = "Damping",
        valueText = "%.2f".format(Locale.US, clampedDamping),
        decTag = "ciacola-inspector-damping-dec",
        incTag = "ciacola-inspector-damping-inc",
        onDec = {
            val next = ((clampedDamping - 0.05f) * 100f).roundToInt() / 100f
            controller.overridesStore.updateTrack(trackId) {
                it.copy(curve = curve.copy(isSpring = true, dampingRatio = next.coerceIn(0.20f, 1.50f)))
            }
        },
        onInc = {
            val next = ((clampedDamping + 0.05f) * 100f).roundToInt() / 100f
            controller.overridesStore.updateTrack(trackId) {
                it.copy(curve = curve.copy(isSpring = true, dampingRatio = next.coerceIn(0.20f, 1.50f)))
            }
        },
    )
    Slider(
        value = clampedDamping,
        onValueChange = { next ->
            controller.overridesStore.updateTrack(trackId, coalesce = true) {
                it.copy(curve = curve.copy(isSpring = true, dampingRatio = next.coerceIn(0.20f, 1.50f)))
            }
        },
        valueRange = 0.20f..1.50f,
        modifier = Modifier.fillMaxWidth().testTag("ciacola-inspector-damping-slider"),
    )
    NumericStepperRow(
        label = "Stiffness",
        valueText = "${clampedStiffness.roundToInt()}",
        decTag = "ciacola-inspector-stiffness-dec",
        incTag = "ciacola-inspector-stiffness-inc",
        onDec = {
            controller.overridesStore.updateTrack(trackId) {
                it.copy(curve = curve.copy(isSpring = true, stiffness = (clampedStiffness - 100f).coerceIn(50f, 4_000f)))
            }
        },
        onInc = {
            controller.overridesStore.updateTrack(trackId) {
                it.copy(curve = curve.copy(isSpring = true, stiffness = (clampedStiffness + 100f).coerceIn(50f, 4_000f)))
            }
        },
    )
    Slider(
        value = clampedStiffness,
        onValueChange = { next ->
            controller.overridesStore.updateTrack(trackId, coalesce = true) {
                it.copy(curve = curve.copy(isSpring = true, stiffness = next.coerceIn(50f, 4_000f)))
            }
        },
        valueRange = 50f..4_000f,
        modifier = Modifier.fillMaxWidth().testTag("ciacola-inspector-stiffness-slider"),
    )
    org.jetbrains.jewel.ui.component.InfoText("Estimated settle: ~${curve.estimatedSpringDurationMs()}ms")
}

@Composable
private fun BezierNumericInputsRow(
    curve: TimelineCurveSpec,
    onCurveChanged: (TimelineCurveSpec) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            BezierCoordinateField(
                label = "x1",
                value = curve.x1,
                min = 0f,
                max = 1f,
                testTag = "ciacola-bezier-input-x1",
                modifier = Modifier.weight(1f),
            ) { next ->
                onCurveChanged(curve.copy(preset = "Custom", x1 = next, isSpring = false))
            }
            BezierCoordinateField(
                label = "y1",
                value = curve.y1,
                min = -0.6f,
                max = 1.6f,
                testTag = "ciacola-bezier-input-y1",
                modifier = Modifier.weight(1f),
            ) { next ->
                onCurveChanged(curve.copy(preset = "Custom", y1 = next, isSpring = false))
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            BezierCoordinateField(
                label = "x2",
                value = curve.x2,
                min = 0f,
                max = 1f,
                testTag = "ciacola-bezier-input-x2",
                modifier = Modifier.weight(1f),
            ) { next ->
                onCurveChanged(curve.copy(preset = "Custom", x2 = next, isSpring = false))
            }
            BezierCoordinateField(
                label = "y2",
                value = curve.y2,
                min = -0.6f,
                max = 1.6f,
                testTag = "ciacola-bezier-input-y2",
                modifier = Modifier.weight(1f),
            ) { next ->
                onCurveChanged(curve.copy(preset = "Custom", y2 = next, isSpring = false))
            }
        }
    }
}

@Composable
private fun BezierCoordinateField(
    label: String,
    value: Float,
    min: Float,
    max: Float,
    testTag: String,
    modifier: Modifier = Modifier,
    onCommit: (Float) -> Unit,
) {
    val formatted = "%.2f".format(Locale.US, value)
    val textState = rememberTextFieldState(initialText = formatted)
    var isFocused by remember { mutableStateOf(false) }

    LaunchedEffect(formatted, isFocused) {
        if (!isFocused && textState.text.toString() != formatted) {
            textState.setTextAndPlaceCursorAtEnd(formatted)
        }
    }

    fun commitText(reformatField: Boolean) {
        val parsed = textState.text.toString().trim().toFloatOrNull()
        if (parsed != null && parsed.isFinite()) {
            val clamped = parsed.coerceIn(min, max)
            onCommit(clamped)
            if (reformatField) {
                textState.setTextAndPlaceCursorAtEnd("%.2f".format(Locale.US, clamped))
            }
        } else if (reformatField) {
            textState.setTextAndPlaceCursorAtEnd(formatted)
        }
    }

    LaunchedEffect(textState.text, isFocused) {
        if (isFocused) {
            val raw = textState.text.toString().trim()
            if (raw.isNotEmpty() && raw != "-" && !raw.endsWith(".")) {
                val parsed = raw.toFloatOrNull()
                if (parsed != null && parsed.isFinite() && parsed in min..max) {
                    onCommit(parsed)
                }
            }
        }
    }

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = JewelTheme.typography.small,
            color = JewelTheme.globalColors.text.info,
        )
        TextField(
            state = textState,
            modifier = Modifier
                .weight(1f)
                .studioTextInputFocus()
                .onFocusChanged { state ->
                    val wasFocused = isFocused
                    isFocused = state.isFocused
                    if (wasFocused && !state.isFocused) {
                        commitText(reformatField = true)
                    }
                }
                .onPreviewKeyEvent { ev ->
                    if (ev.type == KeyEventType.KeyDown &&
                        (ev.key == Key.Enter || ev.key == Key.NumPadEnter)
                    ) {
                        commitText(reformatField = true)
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
private fun CurvePresetButton(
    label: String,
    tooltip: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val focusRingColor = rememberAccessibleFocusRingColor()
    val pillModifier = modifier
        .studioActivatableFocus(focusRingColor)
        .testTag("ciacola-inspector-curve-${label.lowercase()}")
        .semantics {
            this.selected = selected
            this.role = Role.RadioButton
            this.stateDescription = if (selected) "Selected" else "Not selected"
        }
    Tooltip(tooltip = { Text(tooltip) }) {
        if (selected) {
            DefaultButton(onClick = onClick, modifier = pillModifier) {
                Text(label, style = JewelTheme.typography.small, maxLines = 1)
            }
        } else {
            OutlinedButton(onClick = onClick, modifier = pillModifier) {
                Text(label, style = JewelTheme.typography.small, maxLines = 1)
            }
        }
    }
}

@Composable
private fun BezierCurveEditorBox(
    curve: TimelineCurveSpec,
    onGestureStart: () -> Unit = {},
    onGestureEnd: () -> Unit = {},
    onCurveChanged: (TimelineCurveSpec) -> Unit,
) {
    val globalColors = JewelTheme.globalColors
    val palette = JewelTheme.colorPalette
    val isDark = JewelTheme.isDark
    val p1Color = palette.tealOrNull(if (isDark) 6 else 5) ?: Color(0xFF35B5AC)
    val p2Color = palette.orangeOrNull(if (isDark) 6 else 5) ?: Color(0xFFE57C4E)
    val latestCurve by rememberUpdatedState(curve)
    val latestOnGestureStart by rememberUpdatedState(onGestureStart)
    val latestOnGestureEnd by rememberUpdatedState(onGestureEnd)
    val latestOnCurveChanged by rememberUpdatedState(onCurveChanged)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(112.dp)
            .border(1.dp, globalColors.borders.normal, RoundedCornerShape(6.dp))
            .padding(8.dp),
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(96.dp)
                .testTag("ciacola-bezier-editor")
                .semantics {
                    contentDescription = "Cubic Bezier curve editor, preset ${latestCurve.preset}"
                    stateDescription = "Control point 1 (${"%.2f".format(Locale.US, latestCurve.x1)}, ${"%.2f".format(Locale.US, latestCurve.y1)}), " +
                        "Control point 2 (${"%.2f".format(Locale.US, latestCurve.x2)}, ${"%.2f".format(Locale.US, latestCurve.y2)})"
                }
                // Keyed on Unit (reading latestCurve) so dragging a control handle is not cancelled
                // when the first move updates the override and recomposes the Inspector.
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        val startCurve = latestCurve
                        val w = size.width.toFloat().coerceAtLeast(1f)
                        val h = size.height.toFloat().coerceAtLeast(1f)
                        val p1Px = Offset(startCurve.x1 * w, (1f - startCurve.y1.coerceIn(0f, 1f)) * h)
                        val p2Px = Offset(startCurve.x2 * w, (1f - startCurve.y2.coerceIn(0f, 1f)) * h)
                        val dragP1 = hypot(down.position.x - p1Px.x, down.position.y - p1Px.y) <=
                            hypot(down.position.x - p2Px.x, down.position.y - p2Px.y)

                        var latest = startCurve
                        latestOnGestureStart()
                        try {
                            while (true) {
                                val ev = awaitPointerEvent()
                                val ch = ev.changes.firstOrNull() ?: break
                                if (!ch.pressed) break
                                ch.consume()
                                val nx = (ch.position.x / w).coerceIn(0f, 1f)
                                val ny = (1f - ch.position.y / h).coerceIn(-0.4f, 1.4f)
                                latest = if (dragP1) {
                                    latest.copy(preset = "Custom", x1 = nx, y1 = ny, isSpring = false)
                                } else {
                                    latest.copy(preset = "Custom", x2 = nx, y2 = ny, isSpring = false)
                                }
                                latestOnCurveChanged(latest)
                            }
                        } finally {
                            latestOnGestureEnd()
                        }
                    }
                },
        ) {
            val activeCurve = latestCurve
            val w = size.width
            val h = size.height
            val origin = Offset(0f, h)
            val dest = Offset(w, 0f)
            val p1 = Offset(activeCurve.x1.coerceIn(0f, 1f) * w, (1f - activeCurve.y1.coerceIn(-0.2f, 1.2f)) * h)
            val p2 = Offset(activeCurve.x2.coerceIn(0f, 1f) * w, (1f - activeCurve.y2.coerceIn(-0.2f, 1.2f)) * h)

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
    }
}
