package dev.sebastiano.ciacola.inspector.design

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sebastiano.ciacola.document.Curve
import dev.sebastiano.ciacola.ui.CiacolaPrimaryButton
import dev.sebastiano.ciacola.ui.accessibleFocusRing
import dev.sebastiano.ciacola.ui.rememberAccessibleFocusRingColor
import dev.sebastiano.ciacola.ui.rememberCiacolaPrimaryButtonStyle
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.foundation.theme.LocalContentColor as JewelLocalContentColor
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.IconButton
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Slider
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.Tooltip
import org.jetbrains.jewel.ui.component.styling.ButtonMetrics
import org.jetbrains.jewel.ui.component.styling.ButtonStyle
import org.jetbrains.jewel.ui.icon.IconKey
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.ui.theme.colorPalette
import org.jetbrains.jewel.ui.theme.outlinedButtonStyle
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
    stateDescription: String? = null,
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
                .semantics {
                    if (stateDescription != null) {
                        this.stateDescription = stateDescription
                    }
                }
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
 * Compact toggle pill backed by [CiacolaPrimaryButton] / [OutlinedButton] with radio semantics and [Tooltip].
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun DesignTogglePill(
    label: String,
    tooltip: String,
    selected: Boolean,
    testTag: String,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
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
    val smallTextStyle = JewelTheme.typography.small
    Tooltip(tooltip = { Text(tooltip) }) {
        if (compact) {
            if (selected) {
                val primaryResolved = rememberCiacolaPrimaryButtonStyle()
                val compactPrimaryStyle = remember(primaryResolved) {
                    val m = primaryResolved.style.metrics
                    ButtonStyle(
                        colors = primaryResolved.style.colors,
                        metrics = ButtonMetrics(
                            cornerSize = m.cornerSize,
                            padding = PaddingValues(horizontal = 7.dp, vertical = 2.dp),
                            minSize = DpSize(0.dp, 24.dp),
                            borderWidth = m.borderWidth,
                            focusOutlineExpand = m.focusOutlineExpand,
                        ),
                        focusOutlineAlignment = primaryResolved.style.focusOutlineAlignment,
                    )
                }
                DefaultButton(
                    onClick = onClick,
                    modifier = pillModifier,
                    style = compactPrimaryStyle,
                    textStyle = smallTextStyle.copy(color = Color.Unspecified),
                ) {
                    val activeColor = JewelLocalContentColor.current
                    CompositionLocalProvider(JewelLocalContentColor provides activeColor) {
                        Text(
                            text = label,
                            style = smallTextStyle.copy(color = Color.Unspecified),
                            color = activeColor,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            } else {
                val baseOutlined = JewelTheme.outlinedButtonStyle
                val compactOutlinedStyle = remember(baseOutlined) {
                    val m = baseOutlined.metrics
                    ButtonStyle(
                        colors = baseOutlined.colors,
                        metrics = ButtonMetrics(
                            cornerSize = m.cornerSize,
                            padding = PaddingValues(horizontal = 7.dp, vertical = 2.dp),
                            minSize = DpSize(0.dp, 24.dp),
                            borderWidth = m.borderWidth,
                            focusOutlineExpand = m.focusOutlineExpand,
                        ),
                        focusOutlineAlignment = baseOutlined.focusOutlineAlignment,
                    )
                }
                OutlinedButton(
                    onClick = onClick,
                    modifier = pillModifier,
                    style = compactOutlinedStyle,
                ) {
                    Text(
                        text = label,
                        style = smallTextStyle,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        } else if (selected) {
            CiacolaPrimaryButton(
                text = label,
                onClick = onClick,
                textStyle = smallTextStyle,
                modifier = pillModifier,
            )
        } else {
            OutlinedButton(onClick = onClick, modifier = pillModifier) {
                Text(label, style = smallTextStyle, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
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
                color = inspectorSecondaryTextColor(),
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
    gestureKey: String = testTagPrefix,
    onGestureStart: () -> Unit = {},
    onGestureEnd: () -> Unit = {},
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit = {},
    onReset: () -> Unit,
) {
    val globalColors = JewelTheme.globalColors
    val focusRingColor = rememberAccessibleFocusRingColor()
    val clampedExternal = value.coerceIn(valueRange.start, valueRange.endInclusive)
    var sliderDragging by remember(gestureKey) { mutableStateOf(false) }
    var sliderHadFocus by remember(gestureKey) { mutableStateOf(false) }
    var localDragValue by remember(gestureKey) { mutableFloatStateOf(clampedExternal) }
    val latestOnGestureEnd by rememberUpdatedState(onGestureEnd)
    val latestOnValueChangeFinished by rememberUpdatedState(onValueChangeFinished)

    LaunchedEffect(clampedExternal, sliderDragging) {
        if (!sliderDragging) {
            localDragValue = clampedExternal
        }
    }

    DisposableEffect(gestureKey) {
        onDispose {
            if (sliderDragging) {
                sliderDragging = false
                latestOnGestureEnd()
                latestOnValueChangeFinished()
            }
        }
    }

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
                    color = inspectorSecondaryTextColor(),
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
            value = if (sliderDragging) localDragValue else clampedExternal,
            onValueChange = { next ->
                if (!sliderDragging) {
                    sliderDragging = true
                    onGestureStart()
                }
                val clamped = next.coerceIn(valueRange.start, valueRange.endInclusive)
                localDragValue = clamped
                onValueChange(clamped)
            },
            onValueChangeFinished = {
                if (sliderDragging) {
                    sliderDragging = false
                    onGestureEnd()
                }
                onValueChangeFinished()
            },
            valueRange = valueRange,
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { focusState ->
                    if (focusState.hasFocus) {
                        sliderHadFocus = true
                    } else if (sliderHadFocus) {
                        sliderHadFocus = false
                        if (sliderDragging) {
                            sliderDragging = false
                            latestOnGestureEnd()
                            latestOnValueChangeFinished()
                        }
                    }
                }
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

/** Shared metrics for the flat, compact inspector chrome (Design, Theme, Issues). */
internal object InspectorTokens {
    val RowHeight: Dp = 24.dp
    val RowGap: Dp = 4.dp
    val Corner: Dp = 4.dp
    val SectionHorizontalPadding: Dp = 12.dp
    val SectionHeaderHeight: Dp = 30.dp
    val Caption = 11.sp
    val Body = 12.sp
    val Title = 13.sp
}

/** Unified Jewel focus/accent colour used for every selection and active state. */
@Composable
internal fun inspectorAccent(): Color = JewelTheme.globalColors.outlines.focused

/** WCAG AA secondary chrome label colour (≥ 4.5:1 on both dark and light inspector panels). */
internal fun inspectorSecondaryTextColor(isDark: Boolean): Color =
    if (isDark) Color(0xFFB4BCC8) else Color(0xFF475061)

@Composable
internal fun inspectorSecondaryTextColor(): Color =
    inspectorSecondaryTextColor(JewelTheme.isDark)

/** Caption text style (11sp, secondary). */
@Composable
internal fun inspectorCaptionStyle(): TextStyle =
    JewelTheme.typography.small.copy(fontSize = InspectorTokens.Caption, fontFeatureSettings = "tnum")

/** Label / value text style (12sp, tabular figures). */
@Composable
internal fun inspectorBodyStyle(): TextStyle =
    JewelTheme.typography.regular.copy(fontSize = InspectorTokens.Body, fontFeatureSettings = "tnum")

/**
 * Flat collapsible inspector section: 12sp semibold header with chevron, optional trailing header
 * content, 12dp horizontal padding, and a 1dp divider rule underneath. No card fill or border.
 */
@Composable
internal fun FlatCollapsibleSection(
    title: String,
    testTag: String,
    modifier: Modifier = Modifier,
    initiallyExpanded: Boolean = true,
    stateKey: Any = title,
    headerTrailing: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    var expanded by remember(stateKey) { mutableStateOf(initiallyExpanded) }
    val colors = JewelTheme.globalColors
    Column(modifier = modifier.fillMaxWidth().testTag(testTag)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(InspectorTokens.SectionHeaderHeight)
                .padding(start = 6.dp, end = InspectorTokens.SectionHorizontalPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(InspectorTokens.Corner))
                    .clickable { expanded = !expanded }
                    .semantics {
                        role = Role.Button
                        stateDescription = if (expanded) "Expanded" else "Collapsed"
                    }
                    .padding(vertical = 4.dp)
                    .testTag("$testTag-header"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(
                    key = if (expanded) AllIconsKeys.General.ChevronDown else AllIconsKeys.General.ChevronRight,
                    contentDescription = null,
                    modifier = Modifier.size(12.dp),
                )
                Text(
                    text = title,
                    style = inspectorBodyStyle(),
                    fontWeight = FontWeight.SemiBold,
                    color = colors.text.normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            headerTrailing?.invoke(this)
        }
        if (expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = InspectorTokens.SectionHorizontalPadding,
                        end = InspectorTokens.SectionHorizontalPadding,
                        bottom = 10.dp,
                    ),
                verticalArrangement = Arrangement.spacedBy(InspectorTokens.RowGap),
                content = content,
            )
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.borders.normal.copy(alpha = 0.6f)))
    }
}

/**
 * Compact segmented control. The selected segment uses a tinted accent fill with a 1dp accent
 * border (never the solid primary-button fill).
 */
@Composable
internal fun <T> InspectorSegmented(
    options: List<T>,
    selected: T?,
    label: (T) -> String,
    testTag: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    tooltip: ((T) -> String)? = null,
    equalWidths: Boolean = true,
    content: (@Composable (T, Boolean) -> Unit)? = null,
) {
    val colors = JewelTheme.globalColors
    val accent = inspectorAccent()
    val shape = RoundedCornerShape(InspectorTokens.Corner)
    Row(
        modifier = modifier
            .height(InspectorTokens.RowHeight)
            .clip(shape)
            .border(1.dp, colors.borders.normal, shape),
    ) {
        options.forEachIndexed { index, option ->
            val isSelected = option == selected
            val cell: @Composable () -> Unit = {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .then(if (equalWidths) Modifier.fillMaxWidth() else Modifier)
                        .then(if (isSelected) Modifier.background(accent.copy(alpha = 0.18f)).border(1.dp, accent, shape) else Modifier)
                        .clickable { onSelect(option) }
                        .semantics {
                            this.selected = isSelected
                            role = Role.RadioButton
                            contentDescription = tooltip?.invoke(option) ?: label(option)
                        }
                        .padding(horizontal = 8.dp)
                        .testTag(testTag(option)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (content != null) {
                        content(option, isSelected)
                    } else {
                        Text(
                            text = label(option),
                            style = inspectorBodyStyle(),
                            color = if (isSelected) colors.text.normal else colors.text.info,
                            fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                }
            }
            val weighted = if (equalWidths) Modifier.weight(1f) else Modifier
            Box(weighted.fillMaxHeight()) {
                val tip = tooltip?.invoke(option)
                if (tip != null) {
                    InspectorTooltip(tip) { cell() }
                } else {
                    cell()
                }
            }
            if (index < options.lastIndex) {
                Box(Modifier.fillMaxHeight().width(1.dp).background(colors.borders.normal))
            }
        }
    }
}

/** Jewel [Tooltip] wrapper. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun InspectorTooltip(text: String, content: @Composable () -> Unit) {
    Tooltip(tooltip = { Text(text) }, content = content)
}

/** `elevatedSurface` → `Elevated Surface`, `onAccent` → `On Accent`. */
fun humanizeTokenKey(key: String): String =
    dev.sebastiano.ciacola.document.DeckDisplayNames.tokenDisplayName(key)

/** Readable curve names instead of serialised `Bezier(...)` tuples. */
fun curveDisplayName(curve: Curve?): String =
    dev.sebastiano.ciacola.document.DeckDisplayNames.curveDisplayName(curve)
