package dev.sebastiano.ciacola.core.fonts

import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import kotlin.math.ceil
import kotlin.math.floor

/**
 * OpenType GSUB features that character delivery disables in both resting and animated text runs.
 * Only these four optional features are forced off under [EffectiveFontFeatureConfig.CharacterForcedOff]
 * (unless a feature record is referenced as a LangSys `requiredFeatureIndex` or via `FeatureVariations`).
 */
val OPTIONAL_LIGATURE_FEATURES: Set<String> = setOf("liga", "clig", "dlig", "calt")

/**
 * Off-by-default OpenType GSUB feature tags per the OpenType specification.
 * A feature record whose tag is in this list (and is neither required nor a `FeatureVariations`
 * alternative) is classified as inactive.
 */
val OPT_IN_GSUB_FEATURES: Set<String> = buildSet {
    addAll(
        listOf(
            "aalt", "afrc", "c2pc", "c2sc", "case", "cpsp", "cswh",
            "dnom", "expt", "falt", "frac", "fwid", "halt", "hist", "hkna", "hlig", "hwid",
            "ital", "jalt", "lnum", "nalt", "numr", "onum", "opbd", "ordn", "ornm",
            "palt", "pcap", "pkna", "pnum", "pwid", "qwid", "ruby", "salt", "sinf", "smcp", "smpl",
            "subs", "sups", "swsh", "titl", "tnum", "trad", "twid", "unic", "zero",
            "vert", "vrt2", "vhal", "vpal", "vkna",
        ),
    )
    for (i in 1..20) {
        add("ss" + i.toString().padStart(2, '0'))
    }
    for (i in 1..99) {
        add("cv" + i.toString().padStart(2, '0'))
    }
}

/** Returns `true` iff [tag] is on the off-by-default OpenType GSUB list. */
fun isOptInGsubFeature(tag: String): Boolean = tag in OPT_IN_GSUB_FEATURES

/**
 * OpenType GSUB lookup types that can form ligatures or contextual rules:
 * - 4: Ligature Substitution
 * - 5: Contextual Substitution
 * - 6: Chaining Contextual Substitution
 * - 8: Reverse Chaining Contextual Single Substitution
 *
 * Extension Substitution (type 7) is unwrapped; empty Multiple Substitution (type 2 with 0 outputs)
 * is also classified as a multi-glyph (cluster-merging deletion) rule.
 */
val DISALLOWED_GSUB_LOOKUP_TYPES: Set<Int> = setOf(4, 5, 6, 8)

/**
 * Non-OpenType shaping tables that can perform ligature or contextual glyph merging outside GSUB.
 * Faces carrying any of these tables fail closed for character-level certification.
 */
val UNSUPPORTED_SHAPING_TABLES: Set<String> = setOf("morx", "mort", "Silf")

/**
 * OpenType `fontFeatureSettings` string that disables the four optional ligature/contextual
 * features for character-delivery text nodes.
 *
 * Must remain unquoted: Skiko's `FontFeature.parseW3` ignores quoted CSS tags.
 */
const val CHARACTER_DELIVERY_DISABLED_FEATURES: String =
    "liga 0, clig 0, dlig 0, calt 0"

/**
 * The only two effective OpenType feature configurations produced for deck text nodes.
 * Character certification exists ONLY under [CharacterForcedOff].
 */
enum class EffectiveFontFeatureConfig(val fontFeatureSettings: String?) {
    /** Shaper defaults (`fontFeatureSettings = null`). Certification always refuses immediately. */
    ShaperDefaults(null),

    /** Optional ligatures and contextual alternates forced off (`liga 0, clig 0, dlig 0, calt 0`). */
    CharacterForcedOff(CHARACTER_DELIVERY_DISABLED_FEATURES),
    ;

    companion object {
        /**
         * Single authoritative mapping from a text node's character-delivery requirement to its
         * effective feature configuration across Rest, Morph, Build, word tracks, and overlays.
         */
        fun forTextNode(characterDeliveryNode: Boolean): EffectiveFontFeatureConfig =
            if (characterDeliveryNode) CharacterForcedOff else ShaperDefaults

        /**
         * Parses an explicit `fontFeatureSettings` string back into one of the two effective
         * configurations. Returns [CharacterForcedOff] iff the string disables all four optional
         * ligature features (`liga`, `clig`, `dlig`, `calt` set to `0`, unquoted) with no other
         * active feature flags; otherwise returns [ShaperDefaults].
         */
        fun fromFeatureSettings(settings: String?): EffectiveFontFeatureConfig {
            if (settings.isNullOrBlank()) return ShaperDefaults
            val disabled = HashSet<String>(4)
            for (raw in settings.split(',')) {
                val token = raw.trim()
                if (token.isEmpty()) continue
                val parts = token.split(' ', '\t').filter { it.isNotEmpty() }
                if (parts.size != 2) return ShaperDefaults
                val tag = parts[0]
                val value = parts[1]
                if (tag in OPTIONAL_LIGATURE_FEATURES && value == "0") {
                    disabled.add(tag)
                } else {
                    return ShaperDefaults
                }
            }
            return if (disabled.size == OPTIONAL_LIGATURE_FEATURES.size) {
                CharacterForcedOff
            } else {
                ShaperDefaults
            }
        }
    }
}

/**
 * Single authoritative function returning the effective OpenType feature configuration for a text node.
 */
fun effectiveFeatureConfigForTextNode(characterDeliveryNode: Boolean): EffectiveFontFeatureConfig =
    EffectiveFontFeatureConfig.forTextNode(characterDeliveryNode)

/**
 * Applies [config] to [style] so the whole paragraph, every piece, every track, and the overlay
 * share the exact same `fontFeatureSettings`.
 */
fun applyEffectiveFeatureConfig(style: TextStyle, config: EffectiveFontFeatureConfig): TextStyle =
    if (style.fontFeatureSettings == config.fontFeatureSettings) {
        style
    } else {
        style.copy(fontFeatureSettings = config.fontFeatureSettings)
    }

/**
 * Applies [config] to [style].
 */
fun applyEffectiveFeatureConfig(style: SpanStyle, config: EffectiveFontFeatureConfig): SpanStyle =
    if (style.fontFeatureSettings == config.fontFeatureSettings) {
        style
    } else {
        style.copy(fontFeatureSettings = config.fontFeatureSettings)
    }

/**
 * Fixed resource limits enforced during GSUB parsing and Tier B fixed-point closure.
 * Exceeding any limit fails closed.
 */
@Immutable
data class GsubParserLimits(
    val maxParsedRules: Int = 200_000,
    val maxParseSteps: Int = 2_000_000,
    val maxNestingDepth: Int = 32,
    val maxFixedPointRounds: Int = 64,
) {
    companion object {
        val Default: GsubParserLimits = GsubParserLimits()
    }
}

/**
 * A single variation axis declared in a variable font's OpenType `fvar` table.
 */
@Immutable
data class VariationAxisRange(
    val tag: String,
    val minValue: Float,
    val defaultValue: Float,
    val maxValue: Float,
) {
    val minWeightInt: Int
        get() = ceil(minValue.toDouble()).toInt().coerceIn(1, 1000)

    val maxWeightInt: Int
        get() = floor(maxValue.toDouble()).toInt().coerceIn(1, 1000)
}

/**
 * Unicode code point coverage parsed from a font's `cmap` table (Format 4 and/or Format 12).
 * Informational only — Condition 1 certification queries nominal glyph IDs from the rendering
 * Skia `Typeface`, never from this parser.
 */
interface CmapCoverage {
    val formatsPresent: Set<Int>
    fun glyphId(codePoint: Int): Int
    fun hasGlyph(codePoint: Int): Boolean = glyphId(codePoint) != 0
}

/**
 * Classification of an OpenType GSUB feature record under [EffectiveFontFeatureConfig.CharacterForcedOff].
 */
enum class GsubFeatureClass {
    /** Rule 1 (required / FeatureVariations) or Rule 5 (everything else / rlig / rclt / rvrn / unknown). */
    AlwaysOn,

    /** Rule 3 (`ccmp`, `locl` when not promoted by Rule 1). */
    PerWord,

    /** Rule 2 (`liga`, `clig`, `dlig`, `calt` under forced-off) or Rule 4 (off-by-default list). */
    Inactive,
}

/**
 * Records a single active multi-glyph GSUB lookup reference for diagnostics and test assertions.
 */
@Immutable
data class GsubDisallowedReference(
    val featureTag: String,
    val lookupIndex: Int,
    val lookupType: Int,
    val viaExtension: Boolean,
    val viaRequiredFeature: Boolean,
    val viaFeatureVariation: Boolean,
    val featureClass: GsubFeatureClass = GsubFeatureClass.AlwaysOn,
)

/**
 * Set of glyph IDs matched at one rule position inside the validated domain `G = {0 .. numGlyphs - 1}`.
 */
@Immutable
sealed interface GlyphMatcher {
    /** `true` iff this matcher contains no glyph IDs inside `G`. */
    val isEmpty: Boolean

    /** Single exact glyph ID in `G`. */
    @Immutable
    data class Single(val glyphId: Int) : GlyphMatcher {
        override val isEmpty: Boolean get() = false
    }

    /** Sorted distinct glyph IDs in `G`. */
    @Immutable
    class ExplicitSet(val sortedGlyphs: IntArray) : GlyphMatcher {
        override val isEmpty: Boolean get() = sortedGlyphs.isEmpty()
    }

    /**
     * Class 0 complement inside `G = {0 .. domainSize - 1}`:
     * `Class0 = G \ excludedSortedGlyphs`, where `excludedSortedGlyphs` holds every glyph assigned
     * a non-zero class value in the ClassDef. Both unlisted glyphs and glyphs explicitly assigned
     * class 0 (singly or in a range) are absent from [excludedSortedGlyphs] and therefore belong
     * to Class 0.
     */
    @Immutable
    class Complement(
        val excludedSortedGlyphs: IntArray,
        val domainSize: Int,
    ) : GlyphMatcher {
        override val isEmpty: Boolean get() = excludedSortedGlyphs.size >= domainSize
    }
}

/**
 * A single parsed GSUB rule (from LookupTypes 1, 2, 3, 4, 5, 6, 8, with Extension 7 unwrapped).
 *
 * - [isMultiGlyph]: `true` for Ligature (Type 4), contextual/chaining/reverse-chaining (5/6/8)
 *   whose `backtrack.size + input.size + lookahead.size > 1`, or deleting Multiple (Type 2 with
 *   0 output glyphs).
 * - [outputs]: distinct sorted output glyph IDs in `G` that this rule can emit into the glyph run.
 * - [nestedLookupIndices]: lookup indices invoked via `SequenceLookupRecord`s by Type 5/6 rules.
 */
@Immutable
class GsubRule(
    val lookupType: Int,
    val backtrack: List<GlyphMatcher>,
    val input: List<GlyphMatcher>,
    val lookahead: List<GlyphMatcher>,
    val outputs: IntArray,
    val nestedLookupIndices: IntArray,
    val isMultiGlyph: Boolean,
) {
    val isOutputProducing: Boolean get() = outputs.isNotEmpty()
}

/**
 * Parsed lookup inside `GSUB.LookupList` (with Extension LookupType 7 unwrapped and nested
 * multi-glyph reachability precomputed after cycle validation).
 */
@Immutable
class GsubParsedLookup(
    val lookupIndex: Int,
    val effectiveTypes: Set<Int>,
    val viaExtension: Boolean,
    val rules: List<GsubRule>,
    val directHasMultiGlyph: Boolean,
    val transitiveHasMultiGlyph: Boolean,
)

/**
 * Result of inspecting a font file's OpenType `GSUB` table (v1.0 or v1.1 including
 * `FeatureVariations`) for character-safety eligibility.
 */
@Immutable
data class GsubInspection(
    val present: Boolean,
    val majorVersion: Int,
    val minorVersion: Int,
    val lookupCount: Int,
    val featureCount: Int,
    val featureVariationCount: Int,
    val featureTags: Set<String>,
    val disallowedReferences: List<GsubDisallowedReference>,
    val parsedLookups: List<GsubParsedLookup> = emptyList(),
    val alwaysOnLookupIndices: IntArray = EMPTY_INT_ARRAY,
    val perWordLookupIndices: IntArray = EMPTY_INT_ARRAY,
    val alwaysOnHasMultiGlyph: Boolean = false,
    val parseError: String? = null,
) {
    /**
     * `true` iff GSUB parsed cleanly and NO Always-On or Per-Word feature reaches a multi-glyph rule.
     */
    val isClean: Boolean
        get() = parseError == null && disallowedReferences.isEmpty()

    /**
     * `true` iff GSUB parsed cleanly and NO Always-On feature record (Rule 1 or Rule 5) reaches
     * a multi-glyph rule — the Tier A GSUB gate under [EffectiveFontFeatureConfig.CharacterForcedOff].
     */
    val passesTierA: Boolean
        get() = parseError == null && !alwaysOnHasMultiGlyph

    companion object {
        private val EMPTY_INT_ARRAY: IntArray = IntArray(0)

        val Absent: GsubInspection = GsubInspection(
            present = false,
            majorVersion = 0,
            minorVersion = 0,
            lookupCount = 0,
            featureCount = 0,
            featureVariationCount = 0,
            featureTags = emptySet(),
            disallowedReferences = emptyList(),
            parsedLookups = emptyList(),
            alwaysOnLookupIndices = EMPTY_INT_ARRAY,
            perWordLookupIndices = EMPTY_INT_ARRAY,
            alwaysOnHasMultiGlyph = false,
            parseError = null,
        )
    }
}

/**
 * Immutable file-level OpenType/TrueType table inspection cached by SHA-256 content hash.
 */
@Immutable
data class FontTableInspection(
    val contentSha256: String,
    val validSfnt: Boolean,
    val tableTags: Set<String>,
    val numGlyphs: Int = 0,
    val variationAxes: List<VariationAxisRange>,
    val os2WeightClass: Int?,
    val os2IsItalic: Boolean?,
    val cmapCoverage: CmapCoverage?,
    val gsub: GsubInspection,
    val unsupportedShapingTables: Set<String>,
    val parseError: String? = null,
) {
    val weightAxis: VariationAxisRange?
        get() = variationAxes.firstOrNull { it.tag == "wght" }

    val italicAxis: VariationAxisRange?
        get() = variationAxes.firstOrNull { it.tag == "ital" }

    val slantAxis: VariationAxisRange?
        get() = variationAxes.firstOrNull { it.tag == "slnt" }

    /**
     * `true` iff the SFNT binary, `maxp` (`numGlyphs > 0`), `fvar` (if present), and `GSUB`
     * (if present) all parsed cleanly within the parser contract and no unsupported
     * shaping tables (`morx`/`mort`/`Silf`) are present.
     */
    val isStructurallyInspectable: Boolean
        get() = validSfnt &&
            parseError == null &&
            numGlyphs > 0 &&
            unsupportedShapingTables.isEmpty() &&
            gsub.parseError == null

    /**
     * Tier A face eligibility under [EffectiveFontFeatureConfig.CharacterForcedOff]:
     * `true` iff [isStructurallyInspectable] AND no Always-On feature record reaches a multi-glyph
     * rule. (Under [EffectiveFontFeatureConfig.ShaperDefaults], Tier A never certifies.)
     */
    val passesTierAForcedOff: Boolean
        get() = isStructurallyInspectable && gsub.passesTierA

    /**
     * Whole-face character safety with zero active multi-glyph rules in either Always-On or
     * Per-Word (`ccmp`/`locl`) features.
     */
    val isCharacterSafe: Boolean
        get() = isStructurallyInspectable && gsub.isClean
}
