package dev.sebastiano.ciacola.core.fonts

import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight

/**
 * How a font family or face was resolved at load time.
 */
enum class FontOrigin {
    /** Loaded directly from bundled deck/classpath bytes declared with `src`. */
    Bundled,

    /** Matched from host OS system fonts (`system = true`). Always non-character-safe. */
    System,

    /** A `system = true` family absent on the host machine that fell back to a bundled family. */
    FallbackToBundled,

    /** Emergency default fallback when a declaration was missing, broken, or cyclic. */
    DefaultFallback,
}

/**
 * Lookup key for a single weight/italic face inside a resolved family.
 */
@Immutable
data class FontFaceKey(
    val weight: Int,
    val italic: Boolean,
)

/**
 * A single loaded font face (specific weight and upright/italic style) belonging to a
 * [ResolvedFontFamily].
 */
@Immutable
data class ResolvedFontFace(
    val familyName: String,
    val resolvedFamilyName: String,
    val weight: Int,
    val italic: Boolean,
    val origin: FontOrigin,
    val src: String?,
    val inspection: FontTableInspection?,
    val composeFont: Font? = null,
) {
    /**
     * `true` iff this face was loaded directly from bundled bytes declared on its own family
     * (not a system font and not a fallback face) AND passed file-level binary table certification
     * with zero active multi-glyph GSUB lookups anywhere in the face.
     */
    val isCertifiedBundled: Boolean
        get() = origin == FontOrigin.Bundled && inspection?.isCharacterSafe == true

    /**
     * `true` iff this face was loaded directly from bundled bytes declared on its own family and
     * its OpenType tables (`cmap`, `fvar`, `GSUB`) parsed cleanly with no unsupported shaping
     * tables, making it eligible for per-word glyph & GSUB rule certification.
     */
    val isInspectableBundled: Boolean
        get() = origin == FontOrigin.Bundled && inspection?.isStructurallyInspectable == true
}

/**
 * A resolved font family ready to be applied to Compose [TextStyle] / [SpanStyle] with mandatory
 * weight clamping to prevent Skia synthetic bolding.
 */
@Immutable
data class ResolvedFontFamily(
    val familyName: String,
    val resolvedFamilyName: String,
    val fontFamily: FontFamily,
    val minWeight: Int,
    val maxWeight: Int,
    val origin: FontOrigin,
    val role: String?,
    val faces: List<ResolvedFontFace>,
    private val normalWeightRange: IntRange?,
    private val italicWeightRange: IntRange?,
) {
    private val facesByKey: Map<FontFaceKey, ResolvedFontFace> =
        faces.associateBy { FontFaceKey(it.weight, it.italic) }

    val availableWeights: Set<Int> = faces.mapTo(linkedSetOf()) { it.weight }

    val hasNormal: Boolean
        get() = normalWeightRange != null

    val hasItalic: Boolean
        get() = italicWeightRange != null

    /**
     * `true` iff this family was loaded directly from bundled bytes and every loaded face passed
     * file-level structural character-safety certification.
     */
    val isCharacterSafeFamily: Boolean
        get() = origin == FontOrigin.Bundled && faces.isNotEmpty() && faces.all { it.isCertifiedBundled }

    /**
     * Clamps [requested] weight to this family's supported `[minWeight, maxWeight]` (scoped to the
     * requested [style] when that style is present) so Skia never synthesizes faux bold.
     */
    fun clampWeight(requested: Int, style: FontStyle = FontStyle.Normal): Int {
        val range = rangeForStyle(style)
        return requested.coerceIn(range.first, range.last)
    }

    /**
     * Clamps [requested] [FontWeight] to this family's supported weight range for [style].
     */
    fun clampWeight(requested: FontWeight, style: FontStyle = FontStyle.Normal): FontWeight =
        FontWeight(clampWeight(requested.weight, style))

    /**
     * Resolves the exact loaded face for ([weight], [style]) after clamping [weight] to the
     * family's supported range. Returns `null` when that `(clampedWeight, style)` has no loaded
     * face (for example, requesting `FontStyle.Italic` on a family with no italic font file).
     */
    fun resolveFace(weight: FontWeight = FontWeight.Normal, style: FontStyle = FontStyle.Normal): ResolvedFontFace? {
        val italic = style == FontStyle.Italic
        val clamped = clampWeight(weight.weight, style)
        return facesByKey[FontFaceKey(clamped, italic)]
    }

    /**
     * Returns a copy of [style] bound to [fontFamily] with `fontWeight` clamped into the family's
     * supported range so Skia never applies synthetic bold.
     */
    fun applyTo(
        style: TextStyle,
        disableOptionalLigatures: Boolean = false,
    ): TextStyle {
        val effectiveStyle = style.fontStyle ?: FontStyle.Normal
        val effectiveWeight = style.fontWeight ?: FontWeight.Normal
        val clampedWeight = clampWeight(effectiveWeight, effectiveStyle)
        val features = if (disableOptionalLigatures) {
            mergeDisabledLigatureFeatures(style.fontFeatureSettings)
        } else {
            style.fontFeatureSettings
        }
        return style.copy(
            fontFamily = fontFamily,
            fontWeight = clampedWeight,
            fontFeatureSettings = features,
        )
    }

    /**
     * Returns a copy of [style] bound to [fontFamily] with `fontWeight` clamped into the family's
     * supported range.
     */
    fun applyTo(
        style: SpanStyle,
        fallbackFontStyle: FontStyle = FontStyle.Normal,
        disableOptionalLigatures: Boolean = false,
    ): SpanStyle {
        val effectiveStyle = style.fontStyle ?: fallbackFontStyle
        val effectiveWeight = style.fontWeight ?: FontWeight.Normal
        val clampedWeight = clampWeight(effectiveWeight, effectiveStyle)
        val features = if (disableOptionalLigatures) {
            mergeDisabledLigatureFeatures(style.fontFeatureSettings)
        } else {
            style.fontFeatureSettings
        }
        return style.copy(
            fontFamily = fontFamily,
            fontWeight = clampedWeight,
            fontFeatureSettings = features,
        )
    }

    private fun rangeForStyle(style: FontStyle): IntRange = when (style) {
        FontStyle.Italic -> italicWeightRange ?: normalWeightRange ?: (minWeight..maxWeight)
        else -> normalWeightRange ?: italicWeightRange ?: (minWeight..maxWeight)
    }
}

internal fun mergeDisabledLigatureFeatures(existing: String?): String {
    if (existing.isNullOrBlank()) return CHARACTER_DELIVERY_DISABLED_FEATURES
    val tokens = existing.split(',')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .filterNot { token ->
            OPTIONAL_LIGATURE_FEATURES.any { tag ->
                token.startsWith("\"$tag\"") || token.startsWith("'$tag'") || token.startsWith(tag)
            }
        }
    return (tokens + CHARACTER_DELIVERY_DISABLED_FEATURES).joinToString(", ")
}
