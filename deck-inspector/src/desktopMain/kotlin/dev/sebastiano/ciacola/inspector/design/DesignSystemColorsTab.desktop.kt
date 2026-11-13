package dev.sebastiano.ciacola.inspector.design

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.sebastiano.ciacola.theme.CodePaletteOverrides
import dev.sebastiano.ciacola.theme.ColorTokenOverrides
import dev.sebastiano.ciacola.theme.DeckTheme
import dev.sebastiano.ciacola.theme.DeckThemePreset
import dev.sebastiano.ciacola.theme.DesignSystemOverrides
import dev.sebastiano.ciacola.ui.rememberAccessibleFocusRingColor
import java.util.Locale
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.GroupHeader
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.Tooltip
import org.jetbrains.jewel.ui.typography

private data class ColorTokenSpec(
    val id: String,
    val label: String,
    val getColor: (DeckTheme) -> Color,
    val isOverridden: (ColorTokenOverrides) -> Boolean,
    val pairing: (DeckTheme) -> ContrastPairing?,
    val applyColor: (ColorTokenOverrides, Long?) -> ColorTokenOverrides,
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun DesignSystemColorsTab(
    store: DesignSystemStore,
    preset: DeckThemePreset,
    overrides: DesignSystemOverrides,
    resolvedTheme: DeckTheme,
    onTextInputFocusChanged: (Boolean) -> Unit,
) {
    val focusRingColor = rememberAccessibleFocusRingColor()
    var expandedTokenId by remember(preset) { mutableStateOf<String?>(null) }

    val presetSwatches = remember(resolvedTheme.colors) {
        listOf(
            resolvedTheme.colors.accent,
            resolvedTheme.colors.secondary,
            resolvedTheme.colors.positive,
            resolvedTheme.colors.warning,
            resolvedTheme.colors.danger,
            resolvedTheme.colors.foreground,
            resolvedTheme.colors.muted,
            resolvedTheme.colors.surface,
            resolvedTheme.colors.background,
            Color.White,
        )
    }

    val canvasAndSurfaceSpecs = remember {
        listOf(
            ColorTokenSpec(
                id = "background",
                label = "Background",
                getColor = { it.colors.background },
                isOverridden = { it.backgroundArgb != null },
                pairing = { ContrastPairing("Foreground", it.colors.foreground, enforceAaWarning = false) },
                applyColor = { c, argb -> c.copy(backgroundArgb = argb) },
            ),
            ColorTokenSpec(
                id = "surface",
                label = "Surface",
                getColor = { it.colors.surface },
                isOverridden = { it.surfaceArgb != null },
                pairing = { ContrastPairing("Foreground", it.colors.foreground, enforceAaWarning = false) },
                applyColor = { c, argb -> c.copy(surfaceArgb = argb) },
            ),
            ColorTokenSpec(
                id = "elevatedSurface",
                label = "Elevated Surface",
                getColor = { it.colors.elevatedSurface },
                isOverridden = { it.elevatedSurfaceArgb != null },
                pairing = { ContrastPairing("Foreground", it.colors.foreground, enforceAaWarning = false) },
                applyColor = { c, argb -> c.copy(elevatedSurfaceArgb = argb) },
            ),
            ColorTokenSpec(
                id = "border",
                label = "Border",
                getColor = { it.colors.border },
                isOverridden = { it.borderArgb != null },
                pairing = { null },
                applyColor = { c, argb -> c.copy(borderArgb = argb) },
            ),
            ColorTokenSpec(
                id = "chipBackground",
                label = "Chip Background",
                getColor = { it.colors.chipBackground },
                isOverridden = { it.chipBackgroundArgb != null },
                pairing = { null },
                applyColor = { c, argb -> c.copy(chipBackgroundArgb = argb) },
            ),
        )
    }

    val textAndAccentSpecs = remember {
        fun stagePairing(t: DeckTheme) = ContrastPairing("Background & Surface", t.colors.background, t.colors.surface)
        listOf(
            ColorTokenSpec(
                id = "foreground",
                label = "Foreground",
                getColor = { it.colors.foreground },
                isOverridden = { it.foregroundArgb != null },
                pairing = ::stagePairing,
                applyColor = { c, argb -> c.copy(foregroundArgb = argb) },
            ),
            ColorTokenSpec(
                id = "muted",
                label = "Muted",
                getColor = { it.colors.muted },
                isOverridden = { it.mutedArgb != null },
                pairing = ::stagePairing,
                applyColor = { c, argb -> c.copy(mutedArgb = argb) },
            ),
            ColorTokenSpec(
                id = "subtle",
                label = "Subtle",
                getColor = { it.colors.subtle },
                isOverridden = { it.subtleArgb != null },
                pairing = ::stagePairing,
                applyColor = { c, argb -> c.copy(subtleArgb = argb) },
            ),
            ColorTokenSpec(
                id = "accent",
                label = "Accent",
                getColor = { it.colors.accent },
                isOverridden = { it.accentArgb != null },
                pairing = ::stagePairing,
                applyColor = { c, argb -> c.copy(accentArgb = argb) },
            ),
            ColorTokenSpec(
                id = "onAccent",
                label = "On Accent",
                getColor = { it.colors.onAccent },
                isOverridden = { it.onAccentArgb != null },
                pairing = { ContrastPairing("Accent", it.colors.accent) },
                applyColor = { c, argb -> c.copy(onAccentArgb = argb) },
            ),
            ColorTokenSpec(
                id = "secondary",
                label = "Secondary",
                getColor = { it.colors.secondary },
                isOverridden = { it.secondaryArgb != null },
                pairing = ::stagePairing,
                applyColor = { c, argb -> c.copy(secondaryArgb = argb) },
            ),
            ColorTokenSpec(
                id = "positive",
                label = "Positive",
                getColor = { it.colors.positive },
                isOverridden = { it.positiveArgb != null },
                pairing = ::stagePairing,
                applyColor = { c, argb -> c.copy(positiveArgb = argb) },
            ),
            ColorTokenSpec(
                id = "warning",
                label = "Warning",
                getColor = { it.colors.warning },
                isOverridden = { it.warningArgb != null },
                pairing = ::stagePairing,
                applyColor = { c, argb -> c.copy(warningArgb = argb) },
            ),
            ColorTokenSpec(
                id = "danger",
                label = "Danger",
                getColor = { it.colors.danger },
                isOverridden = { it.dangerArgb != null },
                pairing = ::stagePairing,
                applyColor = { c, argb -> c.copy(dangerArgb = argb) },
            ),
            ColorTokenSpec(
                id = "motionPathPreview",
                label = "Motion Path Preview",
                getColor = { it.colors.motionPathPreview },
                isOverridden = { it.motionPathPreviewArgb != null },
                pairing = ::stagePairing,
                applyColor = { c, argb -> c.copy(motionPathPreviewArgb = argb) },
            ),
        )
    }

    val codeSpecs = remember {
        fun codePairing(t: DeckTheme) = ContrastPairing("Code Background", t.colors.code.background)
        fun codeSpec(
            id: String,
            label: String,
            getColor: (DeckTheme) -> Color,
            isOverridden: (CodePaletteOverrides) -> Boolean,
            pairing: (DeckTheme) -> ContrastPairing? = ::codePairing,
            applyCode: (CodePaletteOverrides, Long?) -> CodePaletteOverrides,
        ) = ColorTokenSpec(
            id = "code-$id",
            label = "Code · $label",
            getColor = getColor,
            isOverridden = { c -> c.code?.let(isOverridden) == true },
            pairing = pairing,
            applyColor = { c, argb ->
                val nextCode = applyCode(c.code ?: CodePaletteOverrides(), argb)
                c.copy(code = nextCode.takeIf { it != CodePaletteOverrides() })
            },
        )
        listOf(
            codeSpec("background", "Background", { it.colors.code.background }, { it.backgroundArgb != null }, { null }) { p, v -> p.copy(backgroundArgb = v) },
            codeSpec("foreground", "Foreground", { it.colors.code.foreground }, { it.foregroundArgb != null }) { p, v -> p.copy(foregroundArgb = v) },
            codeSpec("keyword", "Keyword", { it.colors.code.keyword }, { it.keywordArgb != null }) { p, v -> p.copy(keywordArgb = v) },
            codeSpec("string", "String", { it.colors.code.string }, { it.stringArgb != null }) { p, v -> p.copy(stringArgb = v) },
            codeSpec("comment", "Comment", { it.colors.code.comment }, { it.commentArgb != null }) { p, v -> p.copy(commentArgb = v) },
            codeSpec("number", "Number", { it.colors.code.number }, { it.numberArgb != null }) { p, v -> p.copy(numberArgb = v) },
            codeSpec("type", "Type", { it.colors.code.type }, { it.typeArgb != null }) { p, v -> p.copy(typeArgb = v) },
            codeSpec("functionDeclaration", "Function Decl", { it.colors.code.functionDeclaration }, { it.functionDeclarationArgb != null }) { p, v -> p.copy(functionDeclarationArgb = v) },
            codeSpec("functionCall", "Function Call", { it.colors.code.functionCall }, { it.functionCallArgb != null }) { p, v -> p.copy(functionCallArgb = v) },
            codeSpec("property", "Property", { it.colors.code.property }, { it.propertyArgb != null }) { p, v -> p.copy(propertyArgb = v) },
            codeSpec("annotation", "Annotation", { it.colors.code.annotation }, { it.annotationArgb != null }) { p, v -> p.copy(annotationArgb = v) },
            codeSpec("punctuation", "Punctuation", { it.colors.code.punctuation }, { it.punctuationArgb != null }) { p, v -> p.copy(punctuationArgb = v) },
            codeSpec("invalid", "Invalid", { it.colors.code.invalid }, { it.invalidArgb != null }) { p, v -> p.copy(invalidArgb = v) },
        )
    }

    val colorsModified = overrides.colors != ColorTokenOverrides()
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GroupHeader("Canvas & Surfaces:")
            Tooltip(tooltip = { Text("Reset all color overrides for ${preset.name}") }) {
                OutlinedButton(
                    onClick = { store.resetColors(preset) },
                    enabled = colorsModified,
                    modifier = Modifier
                        .designActivatableFocus(focusRingColor)
                        .testTag("design-colors-reset-section"),
                ) {
                    Text("Reset Colors", style = JewelTheme.typography.small)
                }
            }
        }

        ColorSpecGroup(
            specs = canvasAndSurfaceSpecs,
            store = store,
            preset = preset,
            overrides = overrides.colors,
            resolvedTheme = resolvedTheme,
            presetSwatches = presetSwatches,
            expandedTokenId = expandedTokenId,
            onToggleExpand = { id -> expandedTokenId = if (expandedTokenId == id) null else id },
            onTextInputFocusChanged = onTextInputFocusChanged,
        )

        Divider(Orientation.Horizontal, Modifier.fillMaxWidth())
        GroupHeader("Text & Accents (WCAG 2.1 AA ≥ 4.5:1):")
        ColorSpecGroup(
            specs = textAndAccentSpecs,
            store = store,
            preset = preset,
            overrides = overrides.colors,
            resolvedTheme = resolvedTheme,
            presetSwatches = presetSwatches,
            expandedTokenId = expandedTokenId,
            onToggleExpand = { id -> expandedTokenId = if (expandedTokenId == id) null else id },
            onTextInputFocusChanged = onTextInputFocusChanged,
        )

        Divider(Orientation.Horizontal, Modifier.fillMaxWidth())
        GroupHeader("Spotlight:")
        val dimAlpha = resolvedTheme.colors.spotlightDimAlpha
        DesignSliderRow(
            label = "Spotlight Dim Alpha",
            value = dimAlpha,
            valueRange = 0.05f..1.00f,
            valueText = "%.2f".format(Locale.US, dimAlpha),
            testTagPrefix = "design-color-spotlight-dim",
            isOverridden = overrides.colors.spotlightDimAlpha != null,
            onValueChange = { next ->
                store.updateColors(preset, coalesceKey = "color:spotlightDimAlpha", coalesce = true) {
                    it.copy(spotlightDimAlpha = next.coerceIn(0.05f, 1.0f))
                }
            },
            onReset = {
                store.updateColors(preset) { it.copy(spotlightDimAlpha = null) }
            },
        )

        Divider(Orientation.Horizontal, Modifier.fillMaxWidth())
        GroupHeader("Syntax Code Palette:")
        ColorSpecGroup(
            specs = codeSpecs,
            store = store,
            preset = preset,
            overrides = overrides.colors,
            resolvedTheme = resolvedTheme,
            presetSwatches = presetSwatches,
            expandedTokenId = expandedTokenId,
            onToggleExpand = { id -> expandedTokenId = if (expandedTokenId == id) null else id },
            onTextInputFocusChanged = onTextInputFocusChanged,
        )
    }
}

@Composable
private fun ColorSpecGroup(
    specs: List<ColorTokenSpec>,
    store: DesignSystemStore,
    preset: DeckThemePreset,
    overrides: ColorTokenOverrides,
    resolvedTheme: DeckTheme,
    presetSwatches: List<Color>,
    expandedTokenId: String?,
    onToggleExpand: (String) -> Unit,
    onTextInputFocusChanged: (Boolean) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        specs.forEach { spec ->
            ColorTokenRow(
                tokenId = spec.id,
                label = spec.label,
                color = spec.getColor(resolvedTheme),
                isOverridden = spec.isOverridden(overrides),
                presetSwatches = presetSwatches,
                expanded = expandedTokenId == spec.id,
                contrastPairing = spec.pairing(resolvedTheme),
                onToggleExpand = { onToggleExpand(spec.id) },
                onGestureStart = { store.beginEditGesture() },
                onGestureEnd = { store.endEditGesture() },
                onColorChanged = { nextColor, coalesce ->
                    store.updateColors(preset, coalesceKey = "color:${spec.id}", coalesce = coalesce) { c ->
                        spec.applyColor(c, nextColor.toArgbLong())
                    }
                },
                onReset = {
                    store.updateColors(preset) { c -> spec.applyColor(c, null) }
                },
                onTextInputFocusChanged = onTextInputFocusChanged,
            )
        }
    }
}
