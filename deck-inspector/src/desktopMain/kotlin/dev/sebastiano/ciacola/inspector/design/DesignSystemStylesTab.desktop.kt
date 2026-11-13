package dev.sebastiano.ciacola.inspector.design

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.sebastiano.ciacola.theme.DeckTheme
import dev.sebastiano.ciacola.theme.DeckThemePreset
import dev.sebastiano.ciacola.theme.DesignSystemOverrides
import dev.sebastiano.ciacola.theme.StyleTokenOverrides
import dev.sebastiano.ciacola.ui.rememberAccessibleFocusRingColor
import java.util.Locale
import kotlin.math.roundToInt
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.GroupHeader
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.Tooltip
import org.jetbrains.jewel.ui.typography

private data class FloatStyleTokenSpec(
    val id: String,
    val label: String,
    val unitSuffix: String,
    val range: ClosedFloatingPointRange<Float>,
    val step: Float,
    val decimals: Int = 0,
    val getResolved: (DeckTheme) -> Float,
    val isOverridden: (StyleTokenOverrides) -> Boolean,
    val applyOverride: (StyleTokenOverrides, Float?) -> StyleTokenOverrides,
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun DesignSystemStylesTab(
    store: DesignSystemStore,
    preset: DeckThemePreset,
    overrides: DesignSystemOverrides,
    resolvedTheme: DeckTheme,
) {
    val focusRingColor = rememberAccessibleFocusRingColor()
    val groups = remember {
        listOf(
            "Slide Layout" to listOf(
                FloatStyleTokenSpec("slidePadding", "Slide Padding", "dp", 16f..140f, 4f, 0, { it.styles.slidePadding.value }, { it.slidePaddingDp != null }) { s, v -> s.copy(slidePaddingDp = v) },
            ),
            "Code Card" to listOf(
                FloatStyleTokenSpec("codeCardCorner", "Corner Radius", "dp", 0f..48f, 2f, 0, { it.styles.codeCard.cornerRadius.value }, { it.codeCardCornerRadiusDp != null }) { s, v -> s.copy(codeCardCornerRadiusDp = v) },
                FloatStyleTokenSpec("codeCardPadding", "Padding", "dp", 8f..72f, 2f, 0, { it.styles.codeCard.padding.value }, { it.codeCardPaddingDp != null }) { s, v -> s.copy(codeCardPaddingDp = v) },
                FloatStyleTokenSpec("codeCardBorder", "Border Width", "dp", 0f..8f, 0.5f, 1, { it.styles.codeCard.borderWidth.value }, { it.codeCardBorderWidthDp != null }) { s, v -> s.copy(codeCardBorderWidthDp = v) },
            ),
            "Callout Box" to listOf(
                FloatStyleTokenSpec("calloutCorner", "Corner Radius", "dp", 0f..36f, 2f, 0, { it.styles.calloutBox.cornerRadius.value }, { it.calloutCornerRadiusDp != null }) { s, v -> s.copy(calloutCornerRadiusDp = v) },
                FloatStyleTokenSpec("calloutStroke", "Stroke Width", "dp", 1f..10f, 0.5f, 1, { it.styles.calloutBox.strokeWidth.value }, { it.calloutStrokeWidthDp != null }) { s, v -> s.copy(calloutStrokeWidthDp = v) },
                FloatStyleTokenSpec("calloutDashOn", "Dash On", "dp", 2f..24f, 1f, 0, { it.styles.calloutBox.dashOn.value }, { it.calloutDashOnDp != null }) { s, v -> s.copy(calloutDashOnDp = v) },
                FloatStyleTokenSpec("calloutDashOff", "Dash Off", "dp", 2f..24f, 1f, 0, { it.styles.calloutBox.dashOff.value }, { it.calloutDashOffDp != null }) { s, v -> s.copy(calloutDashOffDp = v) },
                FloatStyleTokenSpec("calloutInset", "Inset", "dp", 0f..24f, 1f, 0, { it.styles.calloutBox.inset.value }, { it.calloutInsetDp != null }) { s, v -> s.copy(calloutInsetDp = v) },
            ),
            "Leader Arrow" to listOf(
                FloatStyleTokenSpec("arrowStroke", "Stroke Width", "dp", 1f..10f, 0.5f, 1, { it.styles.leaderArrow.strokeWidth.value }, { it.leaderArrowStrokeWidthDp != null }) { s, v -> s.copy(leaderArrowStrokeWidthDp = v) },
                FloatStyleTokenSpec("arrowHeadLen", "Head Length", "dp", 6f..32f, 1f, 0, { it.styles.leaderArrow.headLength.value }, { it.leaderArrowHeadLengthDp != null }) { s, v -> s.copy(leaderArrowHeadLengthDp = v) },
                FloatStyleTokenSpec("arrowHeadAngle", "Head Angle", "°", 12f..55f, 2f, 0, { it.styles.leaderArrow.headAngleDegrees }, { it.leaderArrowHeadAngleDegrees != null }) { s, v -> s.copy(leaderArrowHeadAngleDegrees = v) },
                FloatStyleTokenSpec("arrowGap", "Gap", "dp", 0f..24f, 1f, 0, { it.styles.leaderArrow.gap.value }, { it.leaderArrowGapDp != null }) { s, v -> s.copy(leaderArrowGapDp = v) },
            ),
            "Strikeout" to listOf(
                FloatStyleTokenSpec("strikeStroke", "Stroke Width", "dp", 1f..12f, 0.5f, 1, { it.styles.strikeout.strokeWidth.value }, { it.strikeoutStrokeWidthDp != null }) { s, v -> s.copy(strikeoutStrokeWidthDp = v) },
                FloatStyleTokenSpec("strikeOverhang", "Overhang", "dp", 0f..24f, 1f, 0, { it.styles.strikeout.horizontalOverhang.value }, { it.strikeoutHorizontalOverhangDp != null }) { s, v -> s.copy(strikeoutHorizontalOverhangDp = v) },
                FloatStyleTokenSpec("strikeTilt", "Tilt Fraction", "", -0.25f..0.25f, 0.02f, 2, { it.styles.strikeout.verticalTiltFraction }, { it.strikeoutVerticalTiltFraction != null }) { s, v -> s.copy(strikeoutVerticalTiltFraction = v) },
            ),
            "Stamp" to listOf(
                FloatStyleTokenSpec("stampCorner", "Corner Radius", "dp", 0f..36f, 2f, 0, { it.styles.stamp.cornerRadius.value }, { it.stampCornerRadiusDp != null }) { s, v -> s.copy(stampCornerRadiusDp = v) },
                FloatStyleTokenSpec("stampPadH", "Horizontal Pad", "dp", 4f..48f, 2f, 0, { it.styles.stamp.horizontalPadding.value }, { it.stampHorizontalPaddingDp != null }) { s, v -> s.copy(stampHorizontalPaddingDp = v) },
                FloatStyleTokenSpec("stampPadV", "Vertical Pad", "dp", 2f..32f, 2f, 0, { it.styles.stamp.verticalPadding.value }, { it.stampVerticalPaddingDp != null }) { s, v -> s.copy(stampVerticalPaddingDp = v) },
                FloatStyleTokenSpec("stampBorder", "Border Width", "dp", 1f..10f, 0.5f, 1, { it.styles.stamp.borderWidth.value }, { it.stampBorderWidthDp != null }) { s, v -> s.copy(stampBorderWidthDp = v) },
            ),
            "Detour Sign" to listOf(
                FloatStyleTokenSpec("detourSize", "Diamond Size", "dp", 36f..140f, 4f, 0, { it.styles.detourBadge.diamondSize.value }, { it.detourDiamondSizeDp != null }) { s, v -> s.copy(detourDiamondSizeDp = v) },
                FloatStyleTokenSpec("detourCorner", "Corner Radius", "dp", 0f..32f, 2f, 0, { it.styles.detourBadge.cornerRadius.value }, { it.detourCornerRadiusDp != null }) { s, v -> s.copy(detourCornerRadiusDp = v) },
                FloatStyleTokenSpec("detourStroke", "Stroke Width", "dp", 1f..10f, 0.5f, 1, { it.styles.detourBadge.strokeWidth.value }, { it.detourStrokeWidthDp != null }) { s, v -> s.copy(detourStrokeWidthDp = v) },
            ),
            "Magnifier Loupe" to listOf(
                FloatStyleTokenSpec("loupeDiameter", "Diameter", "dp", 120f..420f, 10f, 0, { it.styles.loupe.diameter.value }, { it.loupeDiameterDp != null }) { s, v -> s.copy(loupeDiameterDp = v) },
                FloatStyleTokenSpec("loupeRing", "Ring Width", "dp", 1f..10f, 0.5f, 1, { it.styles.loupe.ringWidth.value }, { it.loupeRingWidthDp != null }) { s, v -> s.copy(loupeRingWidthDp = v) },
                FloatStyleTokenSpec("loupeZoom", "Default Zoom", "×", 1.5f..6.0f, 0.2f, 1, { it.styles.loupe.defaultZoom }, { it.loupeDefaultZoom != null }) { s, v -> s.copy(loupeDefaultZoom = v) },
                FloatStyleTokenSpec("loupeDashOn", "Connector Dash On", "dp", 1f..16f, 1f, 0, { it.styles.loupe.connectorDashOn.value }, { it.loupeConnectorDashOnDp != null }) { s, v -> s.copy(loupeConnectorDashOnDp = v) },
                FloatStyleTokenSpec("loupeDashOff", "Connector Dash Off", "dp", 1f..16f, 1f, 0, { it.styles.loupe.connectorDashOff.value }, { it.loupeConnectorDashOffDp != null }) { s, v -> s.copy(loupeConnectorDashOffDp = v) },
            ),
        )
    }

    val stylesModified = overrides.styles != StyleTokenOverrides()

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GroupHeader("Surfaces & Effects:")
            Tooltip(tooltip = { Text("Reset all surface and effect geometry overrides for ${preset.name}") }) {
                OutlinedButton(
                    onClick = { store.resetStyles(preset) },
                    enabled = stylesModified,
                    modifier = Modifier
                        .designActivatableFocus(focusRingColor)
                        .testTag("design-styles-reset-section"),
                ) {
                    Text("Reset Styles", style = JewelTheme.typography.small)
                }
            }
        }

        groups.forEachIndexed { index, (groupTitle, specs) ->
            if (index > 0) Divider(Orientation.Horizontal, Modifier.fillMaxWidth())
            DesignSectionCard {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    GroupHeader("$groupTitle:")
                    specs.forEach { spec ->
                        val current = spec.getResolved(resolvedTheme)
                        val formatted = when (spec.decimals) {
                            0 -> "${current.roundToInt()}${spec.unitSuffix}"
                            1 -> "%.1f%s".format(Locale.US, current, spec.unitSuffix)
                            else -> "%.2f%s".format(Locale.US, current, spec.unitSuffix)
                        }
                        DesignStepperRow(
                            label = spec.label,
                            valueText = formatted,
                            testTagPrefix = "design-style-${spec.id}",
                            isOverridden = spec.isOverridden(overrides.styles),
                            canDecrease = current > spec.range.start + 1e-4f,
                            canIncrease = current < spec.range.endInclusive - 1e-4f,
                            onDecrease = {
                                val next = snapFloat(current - spec.step, spec.decimals).coerceIn(spec.range)
                                store.updateStyles(preset) { s -> spec.applyOverride(s, next) }
                            },
                            onIncrease = {
                                val next = snapFloat(current + spec.step, spec.decimals).coerceIn(spec.range)
                                store.updateStyles(preset) { s -> spec.applyOverride(s, next) }
                            },
                            onReset = {
                                store.updateStyles(preset) { s -> spec.applyOverride(s, null) }
                            },
                        )
                    }
                }
            }
        }
    }
}

private fun snapFloat(value: Float, decimals: Int): Float {
    val scale = when (decimals) {
        0 -> 1f
        1 -> 10f
        else -> 100f
    }
    return (value * scale).roundToInt() / scale
}
