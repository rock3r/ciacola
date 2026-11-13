package dev.sebastiano.ciacola.inspector.design

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sebastiano.ciacola.theme.DeckTheme
import dev.sebastiano.ciacola.theme.DeckThemePreset
import dev.sebastiano.ciacola.theme.DeckTypography
import dev.sebastiano.ciacola.theme.DesignSystemOverrides
import dev.sebastiano.ciacola.theme.TextStyleTokenOverride
import dev.sebastiano.ciacola.theme.TypographyTokenOverrides
import dev.sebastiano.ciacola.ui.rememberAccessibleFocusRingColor
import java.util.Locale
import kotlin.math.roundToInt
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.GroupHeader
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.Tooltip
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.ui.typography

private data class TypographyRoleSpec(
    val id: String,
    val label: String,
    val getStyle: (DeckTypography) -> TextStyle,
    val getOverride: (TypographyTokenOverrides) -> TextStyleTokenOverride?,
    val withOverride: (TypographyTokenOverrides, TextStyleTokenOverride?) -> TypographyTokenOverrides,
)

private val FONT_WEIGHT_OPTIONS = listOf(
    400 to "Regular 400",
    500 to "Medium 500",
    600 to "SemiBold 600",
    700 to "Bold 700",
    800 to "ExtraBold 800",
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun DesignSystemTypographyTab(
    store: DesignSystemStore,
    preset: DeckThemePreset,
    overrides: DesignSystemOverrides,
    baseTheme: DeckTheme,
    resolvedTheme: DeckTheme,
) {
    val focusRingColor = rememberAccessibleFocusRingColor()
    var expandedRoleId by remember(preset) { mutableStateOf<String?>("title") }

    val roleSpecs = remember {
        listOf(
            TypographyRoleSpec("hero", "Hero", { it.hero }, { it.hero }) { t, o -> t.copy(hero = o) },
            TypographyRoleSpec("title", "Title", { it.title }, { it.title }) { t, o -> t.copy(title = o) },
            TypographyRoleSpec("heading", "Heading", { it.heading }, { it.heading }) { t, o -> t.copy(heading = o) },
            TypographyRoleSpec("subheading", "Subheading", { it.subheading }, { it.subheading }) { t, o -> t.copy(subheading = o) },
            TypographyRoleSpec("body", "Body", { it.body }, { it.body }) { t, o -> t.copy(body = o) },
            TypographyRoleSpec("bodyEmphasis", "Body Emphasis", { it.bodyEmphasis }, { it.bodyEmphasis }) { t, o -> t.copy(bodyEmphasis = o) },
            TypographyRoleSpec("callout", "Callout", { it.callout }, { it.callout }) { t, o -> t.copy(callout = o) },
            TypographyRoleSpec("eyebrow", "Eyebrow", { it.eyebrow }, { it.eyebrow }) { t, o -> t.copy(eyebrow = o) },
            TypographyRoleSpec("code", "Code", { it.code }, { it.code }) { t, o -> t.copy(code = o) },
            TypographyRoleSpec("codeEmphasis", "Code Emphasis", { it.codeEmphasis }, { it.codeEmphasis }) { t, o -> t.copy(codeEmphasis = o) },
            TypographyRoleSpec("stamp", "Stamp", { it.stamp }, { it.stamp }) { t, o -> t.copy(stamp = o) },
        )
    }

    val globalScale = overrides.typography.globalFontScale ?: 1.0f
    val typographyModified = overrides.typography != TypographyTokenOverrides()

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GroupHeader("Typography Scale:")
            Tooltip(tooltip = { Text("Reset all typography overrides for ${preset.name}") }) {
                OutlinedButton(
                    onClick = { store.resetTypography(preset) },
                    enabled = typographyModified,
                    modifier = Modifier
                        .designActivatableFocus(focusRingColor)
                        .testTag("design-typography-reset-section"),
                ) {
                    Text("Reset Typography", style = JewelTheme.typography.small)
                }
            }
        }

        DesignSliderRow(
            label = "Global Font Scale",
            value = globalScale,
            valueRange = 0.75f..1.50f,
            valueText = "%.2f×".format(Locale.US, globalScale),
            testTagPrefix = "design-typo-global-scale",
            isOverridden = overrides.typography.globalFontScale != null,
            onValueChange = { next ->
                val snapped = ((next * 100f).roundToInt() / 100f).coerceIn(0.75f, 1.50f)
                store.updateTypography(preset, coalesceKey = "typo:globalScale", coalesce = true) {
                    it.copy(globalFontScale = if (snapped == 1.0f) null else snapped)
                }
            },
            onReset = {
                store.updateTypography(preset) { it.copy(globalFontScale = null) }
            },
        )

        Divider(Orientation.Horizontal, Modifier.fillMaxWidth())
        GroupHeader("Type Roles (${roleSpecs.size}):")

        roleSpecs.forEach { spec ->
            val roleOverride = spec.getOverride(overrides.typography)
            val baseStyle = spec.getStyle(baseTheme.typography)
            val resolvedStyle = spec.getStyle(resolvedTheme.typography)
            TypographyRoleCard(
                spec = spec,
                baseStyle = baseStyle,
                resolvedStyle = resolvedStyle,
                roleOverride = roleOverride,
                expanded = expandedRoleId == spec.id,
                onToggleExpand = {
                    expandedRoleId = if (expandedRoleId == spec.id) null else spec.id
                },
                onUpdate = { next ->
                    val cleaned = next.takeIf { it != TextStyleTokenOverride() }
                    store.updateTypography(preset) { t -> spec.withOverride(t, cleaned) }
                },
                onReset = {
                    store.updateTypography(preset) { t -> spec.withOverride(t, null) }
                },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
private fun TypographyRoleCard(
    spec: TypographyRoleSpec,
    baseStyle: TextStyle,
    resolvedStyle: TextStyle,
    roleOverride: TextStyleTokenOverride?,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onUpdate: (TextStyleTokenOverride) -> Unit,
    onReset: () -> Unit,
) {
    val globalColors = JewelTheme.globalColors
    val focusRingColor = rememberAccessibleFocusRingColor()
    val isOverridden = roleOverride != null && roleOverride != TextStyleTokenOverride()
    val cardShape = RoundedCornerShape(6.dp)

    val unscaledSizeSp = roleOverride?.fontSizeSp ?: baseStyle.fontSize.value
    val unscaledLineSp = roleOverride?.lineHeightSp ?: baseStyle.lineHeight.value
    val weightInt = roleOverride?.fontWeight ?: (baseStyle.fontWeight?.weight ?: 400)
    val baseLetterSp = if (baseStyle.letterSpacing.isSp) baseStyle.letterSpacing.value else 0f
    val letterSp = roleOverride?.letterSpacingSp ?: baseLetterSp

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(globalColors.toolwindowBackground, cardShape)
            .border(1.dp, globalColors.borders.normal, cardShape)
            .padding(8.dp)
            .testTag("design-typo-${spec.id}-card"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Tooltip(tooltip = { Text(if (expanded) "Collapse ${spec.label}" else "Expand ${spec.label} controls") }) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .designActivatableFocus(focusRingColor)
                        .clickable(onClick = onToggleExpand)
                        .padding(vertical = 2.dp)
                        .testTag("design-typo-${spec.id}-toggle")
                        .semantics {
                            role = Role.Button
                            contentDescription = "${spec.label} typography role"
                            stateDescription = if (expanded) "Expanded" else "Collapsed"
                        },
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        key = if (expanded) AllIconsKeys.General.ChevronDown else AllIconsKeys.General.ChevronRight,
                        contentDescription = null,
                        modifier = Modifier.size(12.dp),
                    )
                    if (isOverridden) {
                        ModifiedDotBadge(tooltip = "${spec.label} has typography overrides")
                    }
                    Text(
                        text = spec.label,
                        style = JewelTheme.typography.small.copy(fontWeight = FontWeight.SemiBold),
                        color = globalColors.text.normal,
                        maxLines = 1,
                    )
                    Text(
                        text = "${resolvedStyle.fontSize.value.roundToInt()}/${resolvedStyle.lineHeight.value.roundToInt()}sp · $weightInt",
                        style = JewelTheme.typography.small.copy(fontFamily = FontFamily.Monospace),
                        color = globalColors.text.info,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val previewSize = (resolvedStyle.fontSize.value * 0.45f).coerceIn(11f, 28f).sp
                Text(
                    text = "Aa 0123",
                    style = resolvedStyle.copy(
                        fontSize = previewSize,
                        lineHeight = previewSize * 1.15f,
                        letterSpacing = (resolvedStyle.letterSpacing.takeIf { it.isSp }?.value ?: 0f).times(0.45f).sp,
                    ),
                    color = globalColors.text.normal,
                    maxLines = 1,
                    modifier = Modifier.testTag("design-typo-${spec.id}-preview"),
                )
                DesignTooltipIconButton(
                    icon = AllIconsKeys.Actions.Rollback,
                    contentDescription = "Reset ${spec.label}",
                    tooltip = "Reset ${spec.label} typography to preset default",
                    testTag = "design-typo-${spec.id}-reset",
                    enabled = isOverridden,
                    size = 22.dp,
                    iconSize = 12.dp,
                    onClick = onReset,
                )
            }
        }

        if (expanded) {
            Divider(Orientation.Horizontal, Modifier.fillMaxWidth())
            val current = roleOverride ?: TextStyleTokenOverride()
            DesignStepperRow(
                label = "Font Size",
                valueText = "${unscaledSizeSp.roundToInt()}sp",
                testTagPrefix = "design-typo-${spec.id}-size",
                isOverridden = current.fontSizeSp != null,
                canDecrease = unscaledSizeSp > 8f,
                canIncrease = unscaledSizeSp < 160f,
                onDecrease = {
                    val next = (unscaledSizeSp - 2f).coerceIn(8f, 160f)
                    onUpdate(current.copy(fontSizeSp = next))
                },
                onIncrease = {
                    val next = (unscaledSizeSp + 2f).coerceIn(8f, 160f)
                    onUpdate(current.copy(fontSizeSp = next))
                },
                onReset = { onUpdate(current.copy(fontSizeSp = null)) },
            )
            DesignStepperRow(
                label = "Line Height",
                valueText = "${unscaledLineSp.roundToInt()}sp",
                testTagPrefix = "design-typo-${spec.id}-line-height",
                isOverridden = current.lineHeightSp != null,
                canDecrease = unscaledLineSp > 10f,
                canIncrease = unscaledLineSp < 180f,
                onDecrease = {
                    val next = (unscaledLineSp - 2f).coerceIn(10f, 180f)
                    onUpdate(current.copy(lineHeightSp = next))
                },
                onIncrease = {
                    val next = (unscaledLineSp + 2f).coerceIn(10f, 180f)
                    onUpdate(current.copy(lineHeightSp = next))
                },
                onReset = { onUpdate(current.copy(lineHeightSp = null)) },
            )
            DesignStepperRow(
                label = "Letter Spacing",
                valueText = "%.1fsp".format(Locale.US, letterSp),
                testTagPrefix = "design-typo-${spec.id}-letter-spacing",
                isOverridden = current.letterSpacingSp != null,
                canDecrease = letterSp > -4f,
                canIncrease = letterSp < 12f,
                onDecrease = {
                    val next = (((letterSp - 0.5f) * 10f).roundToInt() / 10f).coerceIn(-4f, 12f)
                    onUpdate(current.copy(letterSpacingSp = next))
                },
                onIncrease = {
                    val next = (((letterSp + 0.5f) * 10f).roundToInt() / 10f).coerceIn(-4f, 12f)
                    onUpdate(current.copy(letterSpacingSp = next))
                },
                onReset = { onUpdate(current.copy(letterSpacingSp = null)) },
            )

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (current.fontWeight != null) {
                            ModifiedDotBadge(tooltip = "Font Weight is overridden from preset default")
                        }
                        Text(
                            text = "Font Weight",
                            style = JewelTheme.typography.small,
                            color = globalColors.text.info,
                        )
                    }
                    DesignTooltipIconButton(
                        icon = AllIconsKeys.Actions.Rollback,
                        contentDescription = "Reset Font Weight",
                        tooltip = "Reset Font Weight to preset default",
                        testTag = "design-typo-${spec.id}-weight-reset",
                        enabled = current.fontWeight != null,
                        size = 22.dp,
                        iconSize = 12.dp,
                        onClick = { onUpdate(current.copy(fontWeight = null)) },
                    )
                }
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    FONT_WEIGHT_OPTIONS.forEach { (weight, weightLabel) ->
                        DesignTogglePill(
                            label = weightLabel,
                            tooltip = "Set ${spec.label} weight to $weightLabel",
                            selected = weightInt == weight,
                            testTag = "design-typo-${spec.id}-weight-$weight",
                            onClick = { onUpdate(current.copy(fontWeight = weight)) },
                        )
                    }
                }
            }
        }
    }
}
