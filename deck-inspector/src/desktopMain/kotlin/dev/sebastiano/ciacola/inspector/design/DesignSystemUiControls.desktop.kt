package dev.sebastiano.ciacola.inspector.design

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.sebastiano.ciacola.ui.accessibleFocusRing
import dev.sebastiano.ciacola.ui.rememberAccessibleFocusRingColor
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.IconButton
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Slider
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.Tooltip
import org.jetbrains.jewel.ui.icon.IconKey
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.ui.theme.colorPalette
import org.jetbrains.jewel.ui.typography

/**
 * Adds a high-contrast Jewel focus ring around interactive controls in the Design System Editor.
 */
internal fun Modifier.designActivatableFocus(
    ringColor: Color,
    cornerRadius: Dp = 4.dp,
    makeFocusable: Boolean = false,
    onFocusedChanged: (Boolean) -> Unit = {},
): Modifier = composed {
    var isFocused by remember { mutableStateOf(false) }
    val tracked = onFocusChanged { state ->
        val nowFocused = state.isFocused
        if (isFocused != nowFocused) {
            isFocused = nowFocused
            onFocusedChanged(nowFocused)
        }
    }
    val withFocusable = if (makeFocusable) tracked.focusable() else tracked
    withFocusable.accessibleFocusRing(
        focused = isFocused,
        ringColor = ringColor,
        cornerRadius = cornerRadius,
    )
}

/**
 * Compact Jewel [IconButton] wrapped in a [Tooltip] with accessible focus ring styling.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun DesignTooltipIconButton(
    icon: IconKey,
    contentDescription: String,
    tooltip: String,
    testTag: String,
    enabled: Boolean = true,
    size: Dp = 24.dp,
    iconSize: Dp = 14.dp,
    onClick: () -> Unit,
) {
    val focusRingColor = rememberAccessibleFocusRingColor()
    Tooltip(tooltip = { Text(tooltip) }) {
        IconButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier
                .size(size)
                .designActivatableFocus(focusRingColor)
                .testTag(testTag),
        ) {
            Icon(
                key = icon,
                contentDescription = contentDescription,
                modifier = Modifier.size(iconSize),
            )
        }
    }
}

/**
 * Compact toggle pill backed by [DefaultButton] / [OutlinedButton] with radio semantics and [Tooltip].
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun DesignTogglePill(
    label: String,
    tooltip: String,
    selected: Boolean,
    testTag: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val focusRingColor = rememberAccessibleFocusRingColor()
    val pillModifier = modifier
        .designActivatableFocus(focusRingColor)
        .testTag(testTag)
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

/**
 * Labeled numeric stepper row with `-` / `+` buttons, optional reset button, and Jewel [Tooltip]s.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun DesignStepperRow(
    label: String,
    valueText: String,
    testTagPrefix: String,
    isOverridden: Boolean,
    canDecrease: Boolean = true,
    canIncrease: Boolean = true,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
    onReset: () -> Unit,
) {
    val globalColors = JewelTheme.globalColors
    val focusRingColor = rememberAccessibleFocusRingColor()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(26.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (isOverridden) {
                ModifiedDotBadge(tooltip = "$label is overridden from preset default")
            }
            Text(
                text = label,
                style = JewelTheme.typography.small,
                color = globalColors.text.info,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Tooltip(tooltip = { Text("Decrease $label") }) {
                IconButton(
                    onClick = onDecrease,
                    enabled = canDecrease,
                    modifier = Modifier
                        .size(22.dp)
                        .designActivatableFocus(focusRingColor)
                        .testTag("$testTagPrefix-dec"),
                ) {
                    Icon(
                        key = AllIconsKeys.General.Remove,
                        contentDescription = "Decrease $label",
                        modifier = Modifier.size(12.dp),
                    )
                }
            }
            Text(
                text = valueText,
                style = JewelTheme.typography.small.copy(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                ),
                color = globalColors.text.normal,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .width(62.dp)
                    .testTag("$testTagPrefix-value"),
            )
            Tooltip(tooltip = { Text("Increase $label") }) {
                IconButton(
                    onClick = onIncrease,
                    enabled = canIncrease,
                    modifier = Modifier
                        .size(22.dp)
                        .designActivatableFocus(focusRingColor)
                        .testTag("$testTagPrefix-inc"),
                ) {
                    Icon(
                        key = AllIconsKeys.General.Add,
                        contentDescription = "Increase $label",
                        modifier = Modifier.size(12.dp),
                    )
                }
            }
            DesignTooltipIconButton(
                icon = AllIconsKeys.Actions.Rollback,
                contentDescription = "Reset $label",
                tooltip = "Reset $label to preset default",
                testTag = "$testTagPrefix-reset",
                enabled = isOverridden,
                size = 22.dp,
                iconSize = 12.dp,
                onClick = onReset,
            )
        }
    }
}

/**
 * Labeled continuous Jewel [Slider] row with live formatted readout and per-token reset action.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun DesignSliderRow(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    valueText: String,
    testTagPrefix: String,
    isOverridden: Boolean,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit = {},
    onReset: () -> Unit,
) {
    val globalColors = JewelTheme.globalColors
    val focusRingColor = rememberAccessibleFocusRingColor()
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (isOverridden) {
                    ModifiedDotBadge(tooltip = "$label is overridden from preset default")
                }
                Text(
                    text = label,
                    style = JewelTheme.typography.small,
                    color = globalColors.text.info,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = valueText,
                    style = JewelTheme.typography.small.copy(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = globalColors.text.normal,
                    modifier = Modifier.testTag("$testTagPrefix-value"),
                )
                DesignTooltipIconButton(
                    icon = AllIconsKeys.Actions.Rollback,
                    contentDescription = "Reset $label",
                    tooltip = "Reset $label to preset default",
                    testTag = "$testTagPrefix-reset",
                    enabled = isOverridden,
                    size = 20.dp,
                    iconSize = 11.dp,
                    onClick = onReset,
                )
            }
        }
        Slider(
            value = value.coerceIn(valueRange.start, valueRange.endInclusive),
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished,
            valueRange = valueRange,
            modifier = Modifier
                .fillMaxWidth()
                .designActivatableFocus(focusRingColor)
                .testTag("$testTagPrefix-slider"),
        )
    }
}

/**
 * Small modified-token indicator badge with [Tooltip] and non-color semantics.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ModifiedDotBadge(
    tooltip: String,
    modifier: Modifier = Modifier,
) {
    val palette = JewelTheme.colorPalette
    val isDark = JewelTheme.isDark
    val dotColor = palette.yellowOrNull(if (isDark) 6 else 5) ?: Color(0xFFE0A82E)
    Tooltip(tooltip = { Text(tooltip) }) {
        Box(
            modifier = modifier
                .size(7.dp)
                .background(dotColor, RoundedCornerShape(50))
                .semantics { contentDescription = "Modified" },
        )
    }
}

/**
 * Subtle section card container following flat JetBrains Int UI conventions (border + fill delta, no shadow).
 */
@Composable
internal fun DesignSectionCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val globalColors = JewelTheme.globalColors
    val shape = RoundedCornerShape(6.dp)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(globalColors.toolwindowBackground, shape)
            .border(1.dp, globalColors.borders.normal, shape)
            .padding(10.dp),
    ) {
        content()
    }
}
