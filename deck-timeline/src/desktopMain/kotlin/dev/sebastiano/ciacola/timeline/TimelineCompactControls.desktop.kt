package dev.sebastiano.ciacola.timeline

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.awt.Cursor
import kotlin.math.roundToInt
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.typography

/** Shared Studio control metrics: 24dp controls, 4dp radius, 11/12/13sp type scale. */
internal val STUDIO_CONTROL_HEIGHT: Dp = 24.dp
internal val STUDIO_CONTROL_SHAPE = RoundedCornerShape(4.dp)

@Composable
internal fun studioCaptionStyle(): TextStyle = JewelTheme.typography.regular.copy(fontSize = 11.sp)

@Composable
internal fun studioLabelStyle(): TextStyle = JewelTheme.typography.regular.copy(fontSize = 12.sp)

@Composable
internal fun studioTitleStyle(): TextStyle =
    JewelTheme.typography.regular.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold)

/** Accent shared by selection everywhere in the timeline (Jewel focus outline colour). */
@Composable
internal fun studioAccentColor(): Color = JewelTheme.globalColors.outlines.focused

/**
 * Flat collapsible section matching the `Design` inspector: 1dp rule above, 24dp header with a
 * chevron and 13sp semibold title, optional trailing actions (e.g. an overflow `…` menu).
 */
@Composable
internal fun TimelineFlatSection(
    title: String,
    tag: String,
    initiallyExpanded: Boolean = true,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    var expanded by rememberSaveable(tag) { mutableStateOf(initiallyExpanded) }
    val colors = JewelTheme.globalColors
    Column(modifier = Modifier.fillMaxWidth().testTag("ciacola-animate-section-$tag")) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.borders.normal))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(STUDIO_CONTROL_HEIGHT + 4.dp)
                .clickable { expanded = !expanded }
                .testTag("ciacola-animate-section-header-$tag"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(if (expanded) "▾" else "▸", style = studioCaptionStyle(), color = colors.text.normal)
            Text(
                text = title,
                style = studioTitleStyle(),
                color = colors.text.normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            trailing?.invoke(this)
        }
        if (expanded) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                content()
            }
        }
    }
}

/**
 * Selection chip/segment: tinted fill + 1dp accent border when selected (never a solid primary
 * button fill), 1dp neutral border otherwise. 24dp tall, 4dp radius.
 */
@Composable
internal fun CompactToggleChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    tag: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = JewelTheme.globalColors
    val accent = studioAccentColor()
    Box(
        modifier = modifier
            .height(STUDIO_CONTROL_HEIGHT)
            .clip(STUDIO_CONTROL_SHAPE)
            .background(if (selected) accent.copy(alpha = 0.16f) else Color.Transparent)
            .border(1.dp, if (selected) accent else colors.borders.normal, STUDIO_CONTROL_SHAPE)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp)
            .semantics { contentDescription = if (selected) "$label (selected)" else label }
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = studioLabelStyle(),
            color = if (enabled) colors.text.normal else colors.text.disabled,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Neutral 24dp text button with a 1dp border and 4dp radius. */
@Composable
internal fun CompactTextButton(
    label: String,
    onClick: () -> Unit,
    tag: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) = CompactToggleChip(label = label, selected = false, onClick = onClick, tag = tag, modifier = modifier, enabled = enabled)

/**
 * Compact numeric field (24dp): typed entry (Enter or focus loss commits), drag on the label to
 * scrub (Shift ×10), and Up/Down arrow keys to step (Shift ×10). Tabular, right-aligned digits.
 */
@Composable
internal fun ScrubbableMsField(
    label: String,
    value: Int,
    onValueChange: (value: Int, inGesture: Boolean) -> Unit,
    tag: String,
    modifier: Modifier = Modifier,
    step: Int = 10,
    range: IntRange = 0..20_000,
    suffix: String = "ms",
    labelWidth: Dp = 72.dp,
    fieldWidth: Dp = 64.dp,
    enabled: Boolean = true,
    onBeginGesture: () -> Unit = {},
    onCommitGesture: () -> Unit = {},
) {
    val colors = JewelTheme.globalColors
    val latestValue by rememberUpdatedState(value)
    val latestOnChange by rememberUpdatedState(onValueChange)
    var text by remember(tag) { mutableStateOf(value.toString()) }
    var focused by remember(tag) { mutableStateOf(false) }
    LaunchedEffect(value, focused) { if (!focused) text = value.toString() }

    fun commitTyped() {
        val parsed = text.filter { it.isDigit() || it == '-' }.toIntOrNull()
        if (parsed == null) {
            text = latestValue.toString()
            return
        }
        val clamped = parsed.coerceIn(range)
        text = clamped.toString()
        if (clamped != latestValue) latestOnChange(clamped, false)
    }

    Row(
        modifier = modifier.height(STUDIO_CONTROL_HEIGHT).testTag("ciacola-scrub-field-$tag"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (label.isNotEmpty()) Text(
            text = label,
            style = studioLabelStyle(),
            color = colors.text.normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .width(labelWidth)
                .pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(Cursor.E_RESIZE_CURSOR)))
                .pointerInput(tag, enabled) {
                    if (!enabled) return@pointerInput
                    var base = 0
                    var accum = 0f
                    var open = false
                    try {
                        detectHorizontalDragGestures(
                            onDragStart = {
                                base = latestValue
                                accum = 0f
                                open = true
                                onBeginGesture()
                            },
                            onDragEnd = {
                                open = false
                                onCommitGesture()
                            },
                            onDragCancel = {
                                open = false
                                onCommitGesture()
                            },
                            onHorizontalDrag = { change, dx ->
                                change.consume()
                                // Accumulate in dp so scrubbing feels identical on 1x and 2x displays.
                                accum += dx / density
                                val perPx = (step / 4f).coerceAtLeast(1f)
                                val next = (base + (accum * perPx).roundToInt()).coerceIn(range)
                                if (next != latestValue) latestOnChange(next, true)
                            },
                        )
                    } finally {
                        if (open) onCommitGesture()
                    }
                }
                .testTag("ciacola-scrub-label-$tag"),
        )
        Row(
            modifier = Modifier
                .width(fieldWidth)
                .height(STUDIO_CONTROL_HEIGHT)
                .clip(STUDIO_CONTROL_SHAPE)
                .border(1.dp, if (focused) studioAccentColor() else colors.borders.normal, STUDIO_CONTROL_SHAPE)
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicTextField(
                value = text,
                onValueChange = { next -> text = next.filter { it.isDigit() }.take(6) },
                enabled = enabled,
                singleLine = true,
                textStyle = studioLabelStyle().copy(
                    color = colors.text.normal,
                    textAlign = TextAlign.End,
                    fontFeatureSettings = "tnum",
                ),
                cursorBrush = SolidColor(colors.text.normal),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { commitTyped() }),
                modifier = Modifier
                    .weight(1f)
                    .studioTextInputFocus()
                    .onFocusChanged { state ->
                        if (focused && !state.isFocused) commitTyped()
                        focused = state.isFocused
                    }
                    .onPreviewKeyEvent { ev ->
                        if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        val mult = if (ev.isShiftPressed) 10 else 1
                        when (ev.key) {
                            Key.DirectionUp, Key.DirectionDown -> {
                                val delta = if (ev.key == Key.DirectionUp) step * mult else -step * mult
                                val next = (latestValue + delta).coerceIn(range)
                                text = next.toString()
                                if (next != latestValue) latestOnChange(next, false)
                                true
                            }
                            Key.Enter, Key.NumPadEnter -> {
                                commitTyped()
                                true
                            }
                            else -> false
                        }
                    }
                    .testTag("ciacola-scrub-input-$tag"),
            )
            if (suffix.isNotEmpty()) {
                Text(suffix, style = studioCaptionStyle(), color = colors.text.info, modifier = Modifier.padding(start = 2.dp))
            }
        }
    }
}
