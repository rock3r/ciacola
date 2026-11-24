package dev.sebastiano.ciacola.inspector.element

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isAltPressed as isPointerAltPressed
import androidx.compose.ui.input.pointer.isShiftPressed as isPointerShiftPressed
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.sebastiano.ciacola.inspector.design.InspectorTokens
import dev.sebastiano.ciacola.inspector.design.InspectorTooltip
import dev.sebastiano.ciacola.inspector.design.inspectorAccent
import dev.sebastiano.ciacola.inspector.design.inspectorBodyStyle
import dev.sebastiano.ciacola.ui.trackAuthoringTextInputFocus
import java.awt.Cursor
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Slider
import org.jetbrains.jewel.ui.component.Text

/** Placeholder shown in numeric fields when a multi-selection has differing values. */
const val MIXED_VALUE_PLACEHOLDER: String = "Mixed"

/** Multiplier applied to scrub / arrow steps for the held modifiers (`Shift` ×10, `Alt` ×0.1). */
internal fun scrubMultiplier(isShiftPressed: Boolean, isAltPressed: Boolean): Double = when {
    isShiftPressed -> 10.0
    isAltPressed -> 0.1
    else -> 1.0
}

/** Formats a number with up to [decimals] decimals and no trailing zeros, using `Locale.US`. */
internal fun formatCompactNumber(value: Double, decimals: Int = 1): String {
    val factor = Math.pow(10.0, decimals.toDouble())
    val rounded = (value * factor).roundToLong() / factor
    return if (abs(rounded - rounded.roundToLong()) < 1e-9) {
        rounded.roundToLong().toString()
    } else {
        String.format(Locale.US, "%.${decimals}f", rounded).trimEnd('0').trimEnd('.')
    }
}

/** Parses typed numeric input, tolerating unit suffixes (`°`, `%`, `px`, `x`) and comma decimals. */
internal fun parseNumericInput(raw: String): Double? =
    raw.trim()
        .removeSuffix("°").removeSuffix("%").removeSuffix("px").removeSuffix("x")
        .trim()
        .replace(',', '.')
        .toDoubleOrNull()

/**
 * Returns the shared value of [values], or `null` ("Mixed") when they differ by more than
 * [tolerance]. An empty list yields `null`.
 */
internal fun sharedValueOrMixed(values: List<Double>, tolerance: Double = 1e-6): Double? {
    val first = values.firstOrNull() ?: return null
    return if (values.all { abs(it - first) <= tolerance }) first else null
}

/**
 * 6dp modified indicator drawn before a property label. Click it to reset. When unmodified it
 * reserves the same width so value columns never shift.
 */
@Composable
internal fun ModifiedDot(
    modified: Boolean,
    label: String,
    tag: String,
    resolvedTestTag: String = "$tag-modified-dot",
    onReset: (() -> Unit)?,
) {
    if (!modified) {
        Spacer(Modifier.width(6.dp))
        return
    }
    InspectorTooltip("$label differs from its default. Click to reset.") {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(inspectorAccent())
                .then(if (onReset != null) Modifier.clickable { onReset() } else Modifier)
                .semantics {
                    role = Role.Button
                    contentDescription = "Reset $label"
                }
                .testTag(resolvedTestTag),
        )
    }
}

/**
 * Compact 24dp numeric property field: right-aligned tabular digits, typed entry (Enter / focus
 * loss commits, Esc reverts), drag-on-label scrubbing (`Shift` ×10, `Alt` ×0.1), Up/Down arrow
 * stepping, a `Mixed` placeholder when [value] is `null`, a modified dot and right-click reset.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun NumericPropertyField(
    label: String,
    value: Double?,
    tag: String,
    format: (Double) -> String,
    onCommit: (Double) -> Unit,
    onStep: (Double) -> Unit,
    onScrubStart: () -> Unit,
    onScrub: (Double) -> Unit,
    onScrubEnd: () -> Unit,
    modifier: Modifier = Modifier,
    labelWidth: Dp = 18.dp,
    step: Double = 1.0,
    modified: Boolean = false,
    onReset: (() -> Unit)? = null,
    onFocusChanged: (Boolean) -> Unit = {},
    tooltip: String? = null,
    enabled: Boolean = true,
) {
    val colors = JewelTheme.globalColors
    val accent = inspectorAccent()
    val display = value?.let(format).orEmpty()
    val state = rememberTextFieldState(display)
    var focused by remember(tag) { mutableStateOf(false) }
    val focusManager: FocusManager = LocalFocusManager.current
    val latestValue by rememberUpdatedState(value)
    val latestOnScrub by rememberUpdatedState(onScrub)
    val latestOnScrubStart by rememberUpdatedState(onScrubStart)
    val latestOnScrubEnd by rememberUpdatedState(onScrubEnd)

    LaunchedEffect(display, focused) {
        if (!focused && state.text.toString() != display) state.setTextAndPlaceCursorAtEnd(display)
    }

    fun commitTyped() {
        // Untouched text (still the rounded display) is never a commit: blurring or tabbing through a
        // field must not round `100.14` to `100.1` and push a spurious undo step.
        if (state.text.toString() == display) return
        val parsed = parseNumericInput(state.text.toString())
        if (parsed != null && (latestValue == null || abs(parsed - latestValue!!) > 1e-9)) {
            onCommit(parsed)
        } else if (parsed == null) {
            state.setTextAndPlaceCursorAtEnd(display)
        }
    }

    Row(
        modifier = modifier.height(InspectorTokens.RowHeight),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        ModifiedDot(modified = modified, label = label, tag = tag, onReset = onReset)
        val labelContent: @Composable () -> Unit = {
            Text(
                text = label,
                style = inspectorBodyStyle(),
                color = colors.text.info,
                maxLines = 1,
                overflow = TextOverflow.Clip,
                modifier = Modifier
                    .width(labelWidth)
                    .pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(Cursor.E_RESIZE_CURSOR)))
                    .pointerInput(tag, enabled) {
                        if (!enabled) return@pointerInput
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            var started = false
                            var total = 0.0
                            try {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                    if (!change.pressed) {
                                        change.consume()
                                        break
                                    }
                                    val dx = change.positionChange().x
                                    if (dx != 0f) {
                                        change.consume()
                                        if (!started) {
                                            started = true
                                            latestOnScrubStart()
                                        }
                                        val mods = event.keyboardModifiers
                                        // Scrub in dp, not device pixels, so 1x and 2x displays feel identical.
                                        total += (dx / density) * step * scrubMultiplier(mods.isPointerShiftPressed, mods.isPointerAltPressed)
                                        latestOnScrub(total)
                                    }
                                }
                            } finally {
                                if (started) latestOnScrubEnd()
                            }
                        }
                    }
                    .semantics { contentDescription = "Drag to adjust $label" }
                    .testTag("$tag-scrub"),
            )
        }
        if (tooltip != null) InspectorTooltip(tooltip) { labelContent() } else labelContent()

        val shape = RoundedCornerShape(InspectorTokens.Corner)
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .clip(shape)
                .background(colors.borders.normal.copy(alpha = 0.10f))
                .border(1.dp, if (focused) accent else colors.borders.normal.copy(alpha = 0.7f), shape)
                .pointerInput(tag, onReset) {
                    awaitEachGesture {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.buttons.isSecondaryPressed && onReset != null) {
                            event.changes.forEach { it.consume() }
                            onReset()
                        }
                    }
                }
                .padding(horizontal = 6.dp),
            contentAlignment = Alignment.CenterEnd,
        ) {
            if (value == null && !focused && state.text.isEmpty()) {
                Text(
                    text = MIXED_VALUE_PLACEHOLDER,
                    style = inspectorBodyStyle(),
                    color = colors.text.info,
                    maxLines = 1,
                    modifier = Modifier.testTag("$tag-mixed"),
                )
            }
            BasicTextField(
                state = state,
                enabled = enabled,
                lineLimits = TextFieldLineLimits.SingleLine,
                textStyle = inspectorBodyStyle().copy(color = colors.text.normal, textAlign = TextAlign.End),
                cursorBrush = SolidColor(accent),
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { st ->
                        val now = st.isFocused
                        if (focused && !now) commitTyped()
                        focused = now
                        onFocusChanged(now)
                    }
                    .trackAuthoringTextInputFocus()
                    .onPreviewKeyEvent { ev ->
                        if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (ev.key) {
                            Key.Enter, Key.NumPadEnter -> {
                                commitTyped()
                                focusManager.clearFocus()
                                true
                            }
                            Key.Escape -> {
                                state.setTextAndPlaceCursorAtEnd(display)
                                focusManager.clearFocus()
                                true
                            }
                            Key.DirectionUp, Key.DirectionDown -> {
                                val sign = if (ev.key == Key.DirectionUp) 1.0 else -1.0
                                onStep(sign * step * scrubMultiplier(ev.isShiftPressed, ev.isAltPressed))
                                true
                            }
                            else -> false
                        }
                    }
                    .semantics { contentDescription = label }
                    .testTag("$tag-input"),
            )
        }
    }
}

/**
 * One 24dp row pairing a small, tactile slider with its numeric readout (opacity, corner radius,
 * shader parameters). Slider drags are coalesced into one gesture by the caller.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun CompactSliderRow(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    valueText: String,
    tagPrefix: String,
    modified: Boolean,
    onReset: (() -> Unit)?,
    onGestureStart: () -> Unit,
    onChange: (Float) -> Unit,
    onGestureEnd: () -> Unit,
    labelWidth: Dp = 72.dp,
) {
    val colors = JewelTheme.globalColors
    val clampedExternal = value.coerceIn(valueRange.start, valueRange.endInclusive)
    var dragging by remember(tagPrefix) { mutableStateOf(false) }
    var pointerPressed by remember(tagPrefix) { mutableStateOf(false) }
    var local by remember(tagPrefix) { mutableFloatStateOf(clampedExternal) }
    if (!dragging && local != clampedExternal) local = clampedExternal
    val latestStart by rememberUpdatedState(onGestureStart)
    val latestEnd by rememberUpdatedState(onGestureEnd)
    DisposableEffect(tagPrefix) {
        onDispose {
            if (dragging) {
                dragging = false
                latestEnd()
            }
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth().height(InspectorTokens.RowHeight),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        ModifiedDot(modified = modified, label = label, tag = "ciacola-$tagPrefix", onReset = onReset)
        Text(
            text = label,
            style = inspectorBodyStyle(),
            color = colors.text.info,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(labelWidth),
        )
        Slider(
            value = if (dragging) local else clampedExternal,
            valueRange = valueRange,
            onValueChange = { raw ->
                if (!dragging && !pointerPressed && abs(raw - local) < 1e-5f) return@Slider
                if (!dragging) {
                    dragging = true
                    latestStart()
                }
                local = raw
                onChange(raw)
            },
            onValueChangeFinished = {
                // Keep a single transaction open for the entire physical pointer press even if
                // Slider internally hands off between its tap and drag modifiers mid-press.
                if (dragging && !pointerPressed) {
                    dragging = false
                    latestEnd()
                }
            },
            modifier = Modifier
                .weight(1f)
                .height(InspectorTokens.RowHeight)
                .pointerInput(tagPrefix) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        pointerPressed = true
                        try {
                            do {
                                val event = awaitPointerEvent(pass = PointerEventPass.Final)
                            } while (event.changes.any { it.pressed })
                        } finally {
                            pointerPressed = false
                            if (dragging) {
                                dragging = false
                                latestEnd()
                            }
                        }
                    }
                }
                .testTag("ciacola-$tagPrefix-slider"),
        )
        Text(
            text = valueText,
            style = inspectorBodyStyle(),
            color = colors.text.normal,
            textAlign = TextAlign.End,
            maxLines = 1,
            modifier = Modifier.width(44.dp).testTag("ciacola-$tagPrefix-value"),
        )
    }
}

/** Compact 3×3 transform-origin anchor grid (`0, 0.5, 1` on each axis). */
@Composable
internal fun AnchorGrid(
    originX: Double?,
    originY: Double?,
    tag: String,
    onPick: (Double, Double) -> Unit,
) {
    val colors = JewelTheme.globalColors
    val accent = inspectorAccent()
    val stops = listOf(0.0, 0.5, 1.0)
    val shape = RoundedCornerShape(InspectorTokens.Corner)
    androidx.compose.foundation.layout.Column(
        modifier = Modifier
            .size(48.dp)
            .clip(shape)
            .border(1.dp, colors.borders.normal, shape)
            .padding(3.dp)
            .testTag(tag),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        for (y in stops) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                for (x in stops) {
                    val selected = originX != null && originY != null && abs(originX - x) < 0.01 && abs(originY - y) < 0.01
                    val name = "${anchorName(y, vertical = true)} ${anchorName(x, vertical = false)}".trim()
                    Box(
                        modifier = Modifier
                            .size(13.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .clickable { onPick(x, y) }
                            .semantics {
                                role = Role.RadioButton
                                contentDescription = "Origin $name"
                                stateDescription = if (selected) "Selected" else "Not selected"
                            }
                            .testTag("$tag-${(x * 2).toInt()}-${(y * 2).toInt()}"),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            Modifier
                                .size(if (selected) 7.dp else 4.dp)
                                .clip(CircleShape)
                                .background(if (selected) accent else colors.text.info.copy(alpha = 0.7f)),
                        )
                    }
                }
            }
        }
    }
}

private fun anchorName(v: Double, vertical: Boolean): String = when {
    v < 0.25 -> if (vertical) "Top" else "Left"
    v > 0.75 -> if (vertical) "Bottom" else "Right"
    else -> "Center"
}
