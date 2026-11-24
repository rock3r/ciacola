package dev.sebastiano.ciacola.inspector.document

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import dev.sebastiano.ciacola.document.DeckDocument
import dev.sebastiano.ciacola.document.DeckThemeSpec
import dev.sebastiano.ciacola.document.NodeSpec
import dev.sebastiano.ciacola.document.SlidePaletteSpec
import dev.sebastiano.ciacola.document.SlideSpec
import dev.sebastiano.ciacola.document.TextRangeEdit
import dev.sebastiano.ciacola.document.TextStyleOverride
import dev.sebastiano.ciacola.document.isLightThemePreset
import dev.sebastiano.ciacola.document.resolveDeckCodeScheme
import dev.sebastiano.ciacola.inspector.design.parseHexColorOrNull
import dev.sebastiano.ciacola.syntax.theme.SyntaxColorScheme
import dev.sebastiano.ciacola.theme.DeckCodePalette
import dev.sebastiano.ciacola.theme.DeckColors
import dev.sebastiano.ciacola.theme.DeckThemes
import kotlin.math.roundToInt

/**
 * Standard semantic color keys editable in `DeckThemeSpec.colors`.
 */
val DOCUMENT_THEME_COLOR_KEYS: List<String> = listOf(
    "background",
    "surface",
    "elevatedSurface",
    "foreground",
    "muted",
    "subtle",
    "accent",
    "secondary",
    "positive",
    "warning",
    "danger",
    "border",
    "chipBackground",
    "onAccent",
)

/**
 * Standard typography role names editable in `DeckThemeSpec.typography` and selectable via
 * `TextPayload.style`.
 */
val DOCUMENT_TYPOGRAPHY_ROLES: List<String> = listOf(
    "hero",
    "title",
    "heading",
    "subheading",
    "body",
    "bodyEmphasis",
    "callout",
    "eyebrow",
    "code",
    "codeEmphasis",
    "stamp",
    "cardTitle",
    "cardBody",
    "codeCompact",
    "caption",
)

@Immutable
data class ResolvedTypographyStyle(
    val font: String,
    val size: Double,
    val weight: Int,
    val italic: Boolean,
    val color: String?,
    val lineHeight: Double,
    val tracking: Double,
    val align: String,
)

/**
 * Snaps a stage coordinate or dimension to `0.5` stage units.
 */
fun snapHalfUnit(value: Double): Double = (value * 2.0).roundToInt() / 2.0

/**
 * Pure analytical helpers for resolving theme tokens, palettes, typography roles, node trees, and
 * inline edit ranges across Element Inspector, Theme Inspector, and DeckLint.
 */
object DocumentThemeResolver {

    fun resolveActiveThemeName(document: DeckDocument, requestedTheme: String? = null): String {
        if (requestedTheme != null && requestedTheme in document.themes) return requestedTheme
        val def = document.defaultTheme
        if (def != null && def in document.themes) return def
        return document.themes.keys.firstOrNull() ?: "stageDark"
    }

    fun resolveThemeSpec(document: DeckDocument, themeName: String? = null): DeckThemeSpec {
        val key = resolveActiveThemeName(document, themeName)
        return document.themes[key] ?: DeckThemeSpec(preset = if (key.contains("light", ignoreCase = true)) "keynoteLight" else "stageDark")
    }

    fun baseColorsForPreset(preset: String): DeckColors =
        if (isLightThemePreset(preset)) DeckThemes.KeynoteLightColors else DeckThemes.StageDarkColors

    fun resolveCodeScheme(themeSpec: DeckThemeSpec): SyntaxColorScheme =
        resolveDeckCodeScheme(themeSpec, isLight = isLightThemePreset(themeSpec.preset))

    fun resolveThemeColorMap(themeSpec: DeckThemeSpec): Map<String, Color> {
        val base = baseColorsForPreset(themeSpec.preset)
        val codePalette = DeckCodePalette.fromScheme(resolveCodeScheme(themeSpec))
        val out = linkedMapOf(
            "background" to base.background,
            "surface" to base.surface,
            "elevatedSurface" to base.elevatedSurface,
            "foreground" to base.foreground,
            "muted" to base.muted,
            "subtle" to base.subtle,
            "accent" to base.accent,
            "secondary" to base.secondary,
            "positive" to base.positive,
            "warning" to base.warning,
            "danger" to base.danger,
            "border" to base.border,
            "chipBackground" to base.chipBackground,
            "onAccent" to base.onAccent,
            "motionPathPreview" to base.motionPathPreview,
            "codeBackground" to codePalette.background,
            "codeForeground" to codePalette.foreground,
            "code.background" to codePalette.background,
            "code.foreground" to codePalette.foreground,
            "code.keyword" to codePalette.keyword,
            "code.string" to codePalette.string,
            "code.comment" to codePalette.comment,
            "code.number" to codePalette.number,
            "code.type" to codePalette.type,
            "code.functionDeclaration" to codePalette.functionDeclaration,
            "code.functionCall" to codePalette.functionCall,
            "code.function" to codePalette.functionCall,
            "code.property" to codePalette.property,
            "code.annotation" to codePalette.annotation,
            "code.punctuation" to codePalette.punctuation,
            "code.invalid" to codePalette.invalid,
            "code.constant" to codePalette.constant,
            "code.attribute" to codePalette.attribute,
            "code.tag" to codePalette.tag,
            "code.operator" to codePalette.operator,
            "code.preprocessor" to codePalette.preprocessor,
        )
        for ((token, hexOrRef) in themeSpec.colors) {
            val parsed = parseHexColorOrNull(hexOrRef) ?: out[hexOrRef]
            if (parsed != null) {
                out[token] = parsed
                when (token) {
                    "codeBackground" -> out["code.background"] = parsed
                    "code.background" -> out["codeBackground"] = parsed
                    "codeForeground" -> out["code.foreground"] = parsed
                    "code.foreground" -> out["codeForeground"] = parsed
                    "code.function" -> out["code.functionCall"] = parsed
                    "code.functionCall" -> out["code.function"] = parsed
                }
            }
        }
        // Deterministic primary-key precedence independent of map iteration order:
        // codeBackground > code.background, codeForeground > code.foreground, code.functionCall > code.function.
        for ((primary, alias) in listOf(
            "codeBackground" to "code.background",
            "codeForeground" to "code.foreground",
            "code.functionCall" to "code.function",
        )) {
            val primaryRaw = themeSpec.colors[primary] ?: continue
            val parsed = parseHexColorOrNull(primaryRaw) ?: out[primaryRaw] ?: continue
            out[primary] = parsed
            out[alias] = parsed
        }
        return out
    }

    /**
     * Resolves a color string (either `#RRGGBB` / `#AARRGGBB`, a slide palette token such as
     * `canvas` / `ink` / `mutedInk` / `accent` / `surface` / `border`, or a theme color token).
     */
    fun resolveColor(
        raw: String?,
        slidePalette: SlidePaletteSpec?,
        themeSpec: DeckThemeSpec,
    ): Color? {
        if (raw.isNullOrBlank()) return null
        val trimmed = raw.trim()
        parseHexColorOrNull(trimmed)?.let { return it }

        val paletteRaw = when (trimmed) {
            "canvas", "palette.canvas" -> slidePalette?.canvas
            "ink", "palette.ink" -> slidePalette?.ink
            "mutedInk", "palette.mutedInk" -> slidePalette?.mutedInk
            "accent", "palette.accent" -> slidePalette?.accent
            "surface", "palette.surface" -> slidePalette?.surface
            "border", "palette.border" -> slidePalette?.border
            else -> null
        }
        if (paletteRaw != null) {
            parseHexColorOrNull(paletteRaw)?.let { return it }
            resolveThemeColorMap(themeSpec)[paletteRaw]?.let { return it }
        }

        val themeColors = resolveThemeColorMap(themeSpec)
        themeColors[trimmed]?.let { return it }
        return when (trimmed) {
            "canvas" -> themeColors["background"]
            "ink" -> themeColors["foreground"]
            "mutedInk" -> themeColors["muted"]
            "codeBackground" -> themeColors["code.background"]
            "codeForeground" -> themeColors["code.foreground"]
            else -> null
        }
    }

    fun resolveSlideCanvasColor(slide: SlideSpec, document: DeckDocument, themeSpec: DeckThemeSpec): Color {
        val palette = slide.palette?.let { document.palettes[it] }
        val fromPalette = palette?.canvas?.let { resolveColor(it, palette, themeSpec) }
        if (fromPalette != null) return fromPalette
        return resolveThemeColorMap(themeSpec)["background"] ?: Color(0xFF0B0D12)
    }

    fun resolveDefaultInkColor(slide: SlideSpec, document: DeckDocument, themeSpec: DeckThemeSpec): Color {
        val palette = slide.palette?.let { document.palettes[it] }
        val fromPalette = palette?.ink?.let { resolveColor(it, palette, themeSpec) }
        if (fromPalette != null) return fromPalette
        return resolveThemeColorMap(themeSpec)["foreground"] ?: Color(0xFFF4F1EA)
    }

    fun resolveTypographyRole(role: String?, themeSpec: DeckThemeSpec): ResolvedTypographyStyle {
        val baseTypo = DeckThemes.buildTypography()
        val key = role ?: "body"
        val composeStyle = when (key) {
            "hero" -> baseTypo.hero
            "title" -> baseTypo.title
            "heading" -> baseTypo.heading
            "subheading" -> baseTypo.subheading
            "body" -> baseTypo.body
            "bodyEmphasis" -> baseTypo.bodyEmphasis
            "callout" -> baseTypo.callout
            "eyebrow" -> baseTypo.eyebrow
            "code" -> baseTypo.code
            "codeEmphasis" -> baseTypo.codeEmphasis
            "stamp" -> baseTypo.stamp
            "cardTitle" -> baseTypo.cardTitle
            "cardBody" -> baseTypo.cardBody
            "codeCompact" -> baseTypo.codeCompact
            "caption" -> baseTypo.caption
            else -> baseTypo.body
        }
        val override: TextStyleOverride? = themeSpec.typography[key]
        val defaultSize = composeStyle.fontSize.value.toDouble().takeIf { it > 0.0 } ?: 30.0
        val defaultLineHeight = composeStyle.lineHeight.value.toDouble().takeIf { it > 0.0 } ?: (defaultSize * 1.3)
        val defaultWeight = composeStyle.fontWeight?.weight ?: 400
        val defaultTracking = if (composeStyle.letterSpacing.isSp) composeStyle.letterSpacing.value.toDouble() else 0.0
        val defaultFont = if (key.startsWith("code")) "JetBrains Mono" else "Inter"

        return ResolvedTypographyStyle(
            font = override?.font ?: defaultFont,
            size = override?.size ?: defaultSize,
            weight = override?.weight ?: defaultWeight,
            italic = override?.italic ?: false,
            color = override?.color,
            lineHeight = override?.lineHeight ?: defaultLineHeight,
            tracking = override?.tracking ?: defaultTracking,
            align = override?.align ?: "start",
        )
    }

    fun findNode(nodes: List<NodeSpec>, nodeId: String): NodeSpec? {
        for (n in nodes) {
            if (n.id == nodeId) return n
            findNode(n.children, nodeId)?.let { return it }
        }
        return null
    }

    fun flattenNodes(nodes: List<NodeSpec>): List<NodeSpec> {
        val out = mutableListOf<NodeSpec>()
        fun visit(list: List<NodeSpec>) {
            for (n in list) {
                out += n
                if (n.children.isNotEmpty()) visit(n.children)
            }
        }
        visit(nodes)
        return out
    }

    fun updateSlide(
        document: DeckDocument,
        slideId: String,
        transform: (SlideSpec) -> SlideSpec,
    ): DeckDocument {
        val idx = document.slides.indexOfFirst { it.id == slideId }
        if (idx < 0) return document
        val next = document.slides.toMutableList()
        next[idx] = transform(next[idx])
        return document.copy(slides = next)
    }

    fun updateNodeInSlide(
        document: DeckDocument,
        slideId: String,
        nodeId: String,
        transform: (NodeSpec) -> NodeSpec,
    ): DeckDocument = updateSlide(document, slideId) { slide ->
        slide.copy(nodes = slide.nodes.map { rewriteNode(it, nodeId, transform) })
    }

    private fun rewriteNode(node: NodeSpec, targetId: String, transform: (NodeSpec) -> NodeSpec): NodeSpec {
        val updatedChildren = when {
            node.stack != null -> node.copy(stack = node.stack!!.copy(children = node.stack!!.children.map { rewriteNode(it, targetId, transform) }))
            node.row != null -> node.copy(row = node.row!!.copy(children = node.row!!.children.map { rewriteNode(it, targetId, transform) }))
            node.column != null -> node.copy(column = node.column!!.copy(children = node.column!!.children.map { rewriteNode(it, targetId, transform) }))
            node.loupe != null -> node.copy(loupe = node.loupe!!.copy(children = node.loupe!!.children.map { rewriteNode(it, targetId, transform) }))
            else -> node
        }
        return if (updatedChildren.id == targetId) transform(updatedChildren) else updatedChildren
    }

    /**
     * Computes a minimal single-range UTF-16 [TextRangeEdit] transforming [oldText] into [newText],
     * or `null` if the two strings are identical.
     */
    fun computeTextRangeEdit(oldText: String, newText: String): TextRangeEdit? {
        if (oldText == newText) return null
        val minLen = minOf(oldText.length, newText.length)
        var prefix = 0
        while (prefix < minLen && oldText[prefix] == newText[prefix]) {
            prefix++
        }
        var suffix = 0
        val maxSuffix = minLen - prefix
        while (suffix < maxSuffix && oldText[oldText.length - 1 - suffix] == newText[newText.length - 1 - suffix]) {
            suffix++
        }
        val removedLength = oldText.length - prefix - suffix
        val insertedText = newText.substring(prefix, newText.length - suffix)
        return TextRangeEdit(
            start = prefix,
            removedLength = removedLength,
            insertedText = insertedText,
        )
    }
}
