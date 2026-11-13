package dev.sebastiano.ciacola.inspector.element

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.sebastiano.ciacola.inspector.ElementPatch
import dev.sebastiano.ciacola.inspector.StageElementMetadata
import dev.sebastiano.ciacola.theme.LocalDeckTheme
import dev.sebastiano.ciacola.ui.accessibleFocusRing
import dev.sebastiano.ciacola.ui.rememberAccessibleFocusRingColor
import kotlin.math.roundToInt
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.GroupHeader
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.IconButton
import org.jetbrains.jewel.ui.component.Slider
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.Tooltip
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.ui.typography

@Composable
internal fun FillAndBorderSection(
    elementId: String,
    patch: ElementPatch,
    meta: StageElementMetadata?,
    store: ElementOverridesStore,
) {
    val theme = LocalDeckTheme.current
    val defaultFill = meta?.defaultFillColor ?: Color.Transparent
    val currentFill = patch.fillColorArgb?.let(::Color) ?: defaultFill
    val defaultBorderColor = meta?.defaultBorderColor ?: theme.colors.border
    val currentBorderColor = patch.borderColorArgb?.let(::Color) ?: defaultBorderColor
    val currentBorderWidth = (patch.borderWidthDp ?: meta?.defaultBorderWidthDp ?: 0f).coerceIn(0f, 12f)
    val currentCornerRadius = (patch.cornerRadiusDp ?: meta?.defaultCornerRadiusDp ?: 0f).coerceIn(0f, 48f)

    GroupHeader("Fill & Border:")

    ElementColorControlRow(
        label = "Fill",
        color = currentFill,
        isOverridden = patch.fillColorArgb != null,
        testTagPrefix = "ciacola-element-fill",
        allowClearFill = true,
        onGestureStart = { store.beginEditGesture() },
        onGestureEnd = { store.endEditGesture() },
        onColorChanged = { c ->
            store.updateElement(elementId, coalesce = true, coalesceKey = "fill:$elementId") {
                it.copy(fillColorArgb = colorToArgbLong(c))
            }
        },
        onReset = { store.updateElement(elementId) { it.copy(fillColorArgb = null) } },
    )

    ElementStepperSliderRow(
        label = "Border Width",
        value = currentBorderWidth,
        valueText = if (currentBorderWidth % 1f == 0f) "${currentBorderWidth.toInt()} dp" else "${"%.1f".format(currentBorderWidth)} dp",
        valueRange = 0f..12f,
        step = 0.5f,
        isOverridden = patch.borderWidthDp != null,
        testTagPrefix = "ciacola-element-borderwidth",
        onGestureStart = { store.beginEditGesture() },
        onGestureEnd = { store.endEditGesture() },
        onValueChanged = { next, coalesce ->
            store.updateElement(elementId, coalesce = coalesce, coalesceKey = "borderwidth:$elementId") {
                it.copy(borderWidthDp = next.coerceIn(0f, 12f))
            }
        },
        onReset = { store.updateElement(elementId) { it.copy(borderWidthDp = null) } },
    )

    ElementColorControlRow(
        label = "Border Color",
        color = currentBorderColor,
        isOverridden = patch.borderColorArgb != null,
        testTagPrefix = "ciacola-element-bordercolor",
        onGestureStart = { store.beginEditGesture() },
        onGestureEnd = { store.endEditGesture() },
        onColorChanged = { c ->
            store.updateElement(elementId, coalesce = true, coalesceKey = "bordercolor:$elementId") {
                it.copy(borderColorArgb = colorToArgbLong(c))
            }
        },
        onReset = { store.updateElement(elementId) { it.copy(borderColorArgb = null) } },
    )

    ElementStepperSliderRow(
        label = "Corner Radius",
        value = currentCornerRadius,
        valueText = "${currentCornerRadius.roundToInt()} dp",
        valueRange = 0f..48f,
        step = 2f,
        isOverridden = patch.cornerRadiusDp != null,
        testTagPrefix = "ciacola-element-cornerradius",
        onGestureStart = { store.beginEditGesture() },
        onGestureEnd = { store.endEditGesture() },
        onValueChanged = { next, coalesce ->
            store.updateElement(elementId, coalesce = coalesce, coalesceKey = "cornerradius:$elementId") {
                it.copy(cornerRadiusDp = next.coerceIn(0f, 48f))
            }
        },
        onReset = { store.updateElement(elementId) { it.copy(cornerRadiusDp = null) } },
    )
}

@Composable
internal fun SpacingAndPositionSection(
    elementId: String,
    patch: ElementPatch,
    meta: StageElementMetadata?,
    store: ElementOverridesStore,
) {
    val currentPadding = (patch.paddingDp ?: meta?.defaultPaddingDp ?: 0f).coerceIn(0f, 64f)
    val currentOffsetX = (patch.offsetXDp ?: 0f).coerceIn(-240f, 240f)
    val currentOffsetY = (patch.offsetYDp ?: 0f).coerceIn(-240f, 240f)

    GroupHeader("Spacing & Position:")

    ElementStepperSliderRow(
        label = "Padding",
        value = currentPadding,
        valueText = "${currentPadding.roundToInt()} dp",
        valueRange = 0f..64f,
        step = 2f,
        isOverridden = patch.paddingDp != null,
        testTagPrefix = "ciacola-element-padding",
        onGestureStart = { store.beginEditGesture() },
        onGestureEnd = { store.endEditGesture() },
        onValueChanged = { next, coalesce ->
            store.updateElement(elementId, coalesce = coalesce, coalesceKey = "padding:$elementId") {
                it.copy(paddingDp = next.coerceIn(0f, 64f))
            }
        },
        onReset = { store.updateElement(elementId) { it.copy(paddingDp = null) } },
    )

    ElementStepperSliderRow(
        label = "Offset X",
        value = currentOffsetX,
        valueText = "${currentOffsetX.roundToInt()} dp",
        valueRange = -240f..240f,
        step = 2f,
        isOverridden = patch.offsetXDp != null,
        resetTooltip = "Reset Offset X to 0 dp",
        testTagPrefix = "ciacola-element-offsetx",
        onGestureStart = { store.beginEditGesture() },
        onGestureEnd = { store.endEditGesture() },
        onValueChanged = { next, coalesce ->
            store.updateElement(elementId, coalesce = coalesce, coalesceKey = "offsetx:$elementId") {
                it.copy(offsetXDp = next.coerceIn(-240f, 240f))
            }
        },
        onReset = { store.updateElement(elementId) { it.copy(offsetXDp = null) } },
    )

    ElementStepperSliderRow(
        label = "Offset Y",
        value = currentOffsetY,
        valueText = "${currentOffsetY.roundToInt()} dp",
        valueRange = -240f..240f,
        step = 2f,
        isOverridden = patch.offsetYDp != null,
        resetTooltip = "Reset Offset Y to 0 dp",
        testTagPrefix = "ciacola-element-offsety",
        onGestureStart = { store.beginEditGesture() },
        onGestureEnd = { store.endEditGesture() },
        onValueChanged = { next, coalesce ->
            store.updateElement(elementId, coalesce = coalesce, coalesceKey = "offsety:$elementId") {
                it.copy(offsetYDp = next.coerceIn(-240f, 240f))
            }
        },
        onReset = { store.updateElement(elementId) { it.copy(offsetYDp = null) } },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ElementStepperSliderRow(
    label: String,
    value: Float,
    valueText: String,
    valueRange: ClosedFloatingPointRange<Float>,
    step: Float,
    isOverridden: Boolean,
    testTagPrefix: String,
    resetTooltip: String = "Reset $label to default",
    onGestureStart: () -> Unit,
    onGestureEnd: () -> Unit,
    onValueChanged: (Float, Boolean) -> Unit,
    onReset: () -> Unit,
) {
    val globalColors = JewelTheme.globalColors
    val focusRingColor = rememberAccessibleFocusRingColor()
    var sliderDragging by remember(testTagPrefix) { mutableStateOf(false) }

    DisposableEffect(testTagPrefix) {
        onDispose {
            if (sliderDragging) {
                sliderDragging = false
                onGestureEnd()
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().height(26.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (isOverridden) "● $label" else label,
                style = JewelTheme.typography.small,
                color = if (isOverridden) globalColors.text.normal else globalColors.text.info,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                var decFocused by remember { mutableStateOf(false) }
                Tooltip(tooltip = { Text("Decrease $label") }) {
                    IconButton(
                        onClick = {
                            onValueChanged((value - step).coerceIn(valueRange.start, valueRange.endInclusive), false)
                        },
                        modifier = Modifier
                            .size(22.dp)
                            .accessibleFocusRing(decFocused, focusRingColor)
                            .onFocusChanged { decFocused = it.isFocused }
                            .testTag("$testTagPrefix-dec"),
                    ) {
                        Icon(AllIconsKeys.General.Remove, contentDescription = "Decrease $label", modifier = Modifier.size(12.dp))
                    }
                }
                Text(
                    text = valueText,
                    style = JewelTheme.typography.small.copy(fontWeight = FontWeight.SemiBold),
                    color = globalColors.text.normal,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.width(54.dp).testTag("$testTagPrefix-value"),
                )
                var incFocused by remember { mutableStateOf(false) }
                Tooltip(tooltip = { Text("Increase $label") }) {
                    IconButton(
                        onClick = {
                            onValueChanged((value + step).coerceIn(valueRange.start, valueRange.endInclusive), false)
                        },
                        modifier = Modifier
                            .size(22.dp)
                            .accessibleFocusRing(incFocused, focusRingColor)
                            .onFocusChanged { incFocused = it.isFocused }
                            .testTag("$testTagPrefix-inc"),
                    ) {
                        Icon(AllIconsKeys.General.Add, contentDescription = "Increase $label", modifier = Modifier.size(12.dp))
                    }
                }
                var resetFocused by remember { mutableStateOf(false) }
                Tooltip(tooltip = { Text(resetTooltip) }) {
                    IconButton(
                        onClick = onReset,
                        enabled = isOverridden,
                        modifier = Modifier
                            .size(22.dp)
                            .accessibleFocusRing(resetFocused, focusRingColor)
                            .onFocusChanged { resetFocused = it.isFocused }
                            .testTag("$testTagPrefix-reset"),
                    ) {
                        Icon(AllIconsKeys.Actions.Rollback, contentDescription = resetTooltip, modifier = Modifier.size(12.dp))
                    }
                }
            }
        }
        Slider(
            value = value.coerceIn(valueRange.start, valueRange.endInclusive),
            onValueChange = { next ->
                if (!sliderDragging) {
                    sliderDragging = true
                    onGestureStart()
                }
                onValueChanged(next, true)
            },
            onValueChangeFinished = {
                if (sliderDragging) {
                    sliderDragging = false
                    onGestureEnd()
                }
            },
            valueRange = valueRange,
            modifier = Modifier.fillMaxWidth().testTag("$testTagPrefix-slider"),
        )
    }
}
