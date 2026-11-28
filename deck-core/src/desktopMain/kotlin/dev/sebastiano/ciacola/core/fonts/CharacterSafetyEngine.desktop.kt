package dev.sebastiano.ciacola.core.fonts

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.LoadedFont
import java.text.Normalizer
import java.util.BitSet
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import org.jetbrains.skia.Data
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.FontVariation
import org.jetbrains.skia.Typeface

/**
 * Single Unicode scalar inside a paragraph paired with its authoritative face assignment and
 * nominal glyph ID queried from the rendering Skia [Typeface].
 */
data class AssignedScalar(
    val utf16Start: Int,
    val utf16End: Int,
    val codePoint: Int,
    val face: ResolvedFontFace?,
    val nominalGlyphId: Int,
    val usedFallbackFamily: ResolvedFontFamily?,
    val fallbackWeight: FontWeight?,
    val isLineFeed: Boolean,
)

/**
 * Output of the single shared face-assignment function.
 *
 * [routedAnnotatedString] is handed directly to the Compose / SkParagraph drawing pipeline while
 * [scalars] and the diagnostic flags feed Condition 1 certification — there is no second assignment.
 */
class ParagraphFaceAssignment(
    val text: String,
    val scalars: List<AssignedScalar>,
    val routedAnnotatedString: AnnotatedString,
    val hasUnpairedSurrogate: Boolean,
    val hasSplitSurrogateSpan: Boolean,
    val hasUnavailableTypeface: Boolean,
    val hasOutOfDomainGlyph: Boolean,
    val hasNonBundledFace: Boolean,
    val hasUnresolvedScalar: Boolean,
)

/**
 * Per-paragraph Condition 1 character-safety certification handle produced by
 * [CharacterSafetyEngine.certifyParagraph].
 *
 * Holds Tier A and per-face Tier B paragraph-universe `U` state for one paragraph and answers
 * per-word character-safety queries for ranges inside that paragraph.
 */
class ParagraphCharacterSafetyCertifier internal constructor(
    val effectiveConfig: EffectiveFontFeatureConfig,
    val assignment: ParagraphFaceAssignment?,
    val paragraphRefusedReason: String?,
    private val textLength: Int,
    private val faceStates: Map<ResolvedFontFace, FaceTierBState>,
    private val scalars: List<AssignedScalar>,
    private val maxFixedPointRounds: Int,
) {
    /** `true` if either Tier B fixed point (`U` or any word's `W`) exhausted the round limit. */
    var budgetExhausted: Boolean = (paragraphRefusedReason == REASON_FIXED_POINT_EXHAUSTED)
        private set

    /** `true` iff the configuration guard and all 5 paragraph preconditions passed without exhaustion. */
    val isParagraphEligible: Boolean
        get() = paragraphRefusedReason == null && !budgetExhausted

    /**
     * Evaluates whether the word covering UTF-16 `[start, end)` inside this paragraph is
     * character-safe (Tier A + Tier B closure across every face in the word).
     */
    fun isWordRangeSafe(start: Int, end: Int): Boolean {
        if (!isParagraphEligible || start < 0 || end <= start || end > textLength) return false
        val wordScalars = collectWordScalars(start, end) ?: return false
        if (wordScalars.isEmpty()) return false

        val byFace = LinkedHashMap<ResolvedFontFace, MutableList<AssignedScalar>>(2)
        for (scalar in wordScalars) {
            val face = scalar.face ?: return false
            byFace.getOrPut(face) { ArrayList() }.add(scalar)
        }

        for ((face, faceWordScalars) in byFace) {
            val state = faceStates[face] ?: return false
            when (state) {
                is FaceTierBState.TierARejected -> return false
                is FaceTierBState.SafeAll -> continue
                is FaceTierBState.Computed -> {
                    if (!evaluateWordForFace(state, faceWordScalars)) return false
                }
            }
        }
        return true
    }

    /**
     * Evaluates a batch of word ranges `[IntRange]` (inclusive Kotlin `start..endInclusive` converted
     * to half-open `[first, last + 1)` — use [certifyHalfOpenWordRanges] for half-open UTF-16 ranges).
     * If ANY word exhausts the `W` fixed-point budget, refuses EVERY word of the paragraph
     * (no partial result on budget exhaustion).
     */
    fun certifyHalfOpenWordRanges(ranges: List<Pair<Int, Int>>): BooleanArray {
        val results = BooleanArray(ranges.size)
        if (!isParagraphEligible) return results
        for (i in ranges.indices) {
            val (start, end) = ranges[i]
            results[i] = isWordRangeSafe(start, end)
            if (budgetExhausted) {
                results.fill(false)
                return results
            }
        }
        return results
    }

    private fun collectWordScalars(start: Int, end: Int): List<AssignedScalar>? {
        val matched = ArrayList<AssignedScalar>(end - start)
        for (s in scalars) {
            if (s.utf16End <= start) continue
            if (s.utf16Start >= end) break
            // Reject if range boundary splits a surrogate pair.
            if (s.utf16Start < start || s.utf16End > end) return null
            matched.add(s)
        }
        if (matched.isEmpty()) return null
        if (matched.first().utf16Start != start || matched.last().utf16End != end) return null
        return matched
    }

    private fun evaluateWordForFace(
        state: FaceTierBState.Computed,
        wordScalars: List<AssignedScalar>,
    ): Boolean {
        val numGlyphs = state.numGlyphs
        val wordBits = BitSet(numGlyphs)
        for (s in wordScalars) {
            if (s.nominalGlyphId in 0 until numGlyphs) {
                wordBits.set(s.nominalGlyphId)
            }
            if (s.isLineFeed) {
                wordBits.set(0)
                if (state.spaceGlyphId in 0 until numGlyphs) {
                    wordBits.set(state.spaceGlyphId)
                }
            }
        }

        // Step 3: Word universe W fixed-point closure over possible output-producing rules whose
        // input intersects W. Always run when output rules exist so W exhaustion is never hidden.
        if (state.possibleOutputRules.isNotEmpty()) {
            var converged = false
            for (round in 0 until maxFixedPointRounds) {
                var changed = false
                for (rule in state.possibleOutputRules) {
                    if (anyInputIntersects(rule, wordBits, numGlyphs)) {
                        for (outGid in rule.outputs) {
                            if (!wordBits.get(outGid)) {
                                wordBits.set(outGid)
                                changed = true
                            }
                        }
                    }
                }
                if (!changed) {
                    converged = true
                    break
                }
            }
            if (!converged) {
                budgetExhausted = true
                return false
            }
        }

        // Step 4: Safe iff no possible multi-glyph rule has an input position intersecting W.
        for (rule in state.possibleMultiGlyphRules) {
            if (anyInputIntersects(rule, wordBits, numGlyphs)) {
                return false
            }
        }
        return true
    }

    companion object {
        const val REASON_SHAPER_DEFAULTS: String = "shaper-defaults"
        const val REASON_FIXED_POINT_EXHAUSTED: String = "fixed-point-budget-exhausted"

        internal fun refused(
            config: EffectiveFontFeatureConfig,
            reason: String,
            assignment: ParagraphFaceAssignment? = null,
        ): ParagraphCharacterSafetyCertifier = ParagraphCharacterSafetyCertifier(
            effectiveConfig = config,
            assignment = assignment,
            paragraphRefusedReason = reason,
            textLength = assignment?.text?.length ?: 0,
            faceStates = emptyMap(),
            scalars = emptyList(),
            maxFixedPointRounds = GsubParserLimits.Default.maxFixedPointRounds,
        )
    }
}

internal sealed interface FaceTierBState {
    data object TierARejected : FaceTierBState
    data class SafeAll(val numGlyphs: Int) : FaceTierBState
    class Computed(
        val numGlyphs: Int,
        val possibleOutputRules: List<GsubRule>,
        val possibleMultiGlyphRules: List<GsubRule>,
        val spaceGlyphId: Int,
    ) : FaceTierBState
}

/**
 * Authoritative Skia [Typeface] cache for bundled faces. Provides the exact rendering typeface
 * built from bundled bytes so nominal glyph lookup matches Skia/HarfBuzz with zero independent
 * `cmap` subtable guessing.
 */
internal object BundledTypefaceRegistry {
    private data class Key(val sha256: String, val weight: Int, val italic: Boolean, val axes: String)

    private val baseTypefaces = ConcurrentHashMap<String, Any>() // Typeface or UNAVAILABLE
    private val faceTypefaces = ConcurrentHashMap<Key, Any>() // Typeface or UNAVAILABLE
    private val UNAVAILABLE = Any()

    fun typefaceFor(face: ResolvedFontFace, rawBytes: ByteArray?): Typeface? {
        if (face.origin != FontOrigin.Bundled) return null
        val inspection = face.inspection ?: return null
        val bytes = rawBytes ?: (face.composeFont as? LoadedFont)?.data ?: return null
        val key = Key(inspection.contentSha256, face.weight, face.italic, FontAxes.cacheKey(face.axisCoordinates))
        val cached = faceTypefaces[key]
        if (cached != null) return cached as? Typeface

        val baseObj = baseTypefaces.computeIfAbsent(inspection.contentSha256) {
            val data = Data.makeFromBytes(bytes)
            try {
                FontMgr.default.makeFromData(data, 0) ?: UNAVAILABLE
            } catch (_: Throwable) {
                UNAVAILABLE
            } finally {
                data.close()
            }
        }
        val base = baseObj as? Typeface
        if (base == null) {
            faceTypefaces.putIfAbsent(key, UNAVAILABLE)
            return null
        }

        val coordinates = LinkedHashMap<String, Float>()
        if (inspection.weightAxis != null) coordinates["wght"] = face.weight.toFloat()
        coordinates.putAll(face.axisCoordinates)
        val resolved: Typeface = if (coordinates.isNotEmpty()) {
            try {
                val variations = coordinates.map { (tag, value) -> FontVariation(tag, value) }.toTypedArray()
                base.makeClone(variations, 0) ?: base
            } catch (_: Throwable) {
                base
            }
        } else {
            base
        }
        val existing = faceTypefaces.putIfAbsent(key, resolved)
        if (existing != null) {
            if (resolved !== base) resolved.close()
            return existing as? Typeface
        }
        return resolved
    }

    fun clearForTests() {
        faceTypefaces.clear()
        baseTypefaces.clear()
    }
}

/**
 * Single authoritative Condition 1 character-safety engine shared by [DeckFontResolver] and
 * MagicText v2 (`TwoConditionPartitioner`). No second character-safety predicate exists.
 */
object CharacterSafetyEngine {
    private const val CODEPOINT_LINE_FEED: Int = 0x000A
    private const val CODEPOINT_FRACTION_SLASH: Int = 0x2044
    private const val CODEPOINT_DIVISION_SLASH: Int = 0x2215
    private const val CODEPOINT_VARIATION_SELECTOR_16: Int = 0xFE0F
    private const val CODEPOINT_ZERO_WIDTH_JOINER: Int = 0x200D

    private val tierACache: ConcurrentHashMap<Pair<String, EffectiveFontFeatureConfig>, Boolean> =
        ConcurrentHashMap()

    /**
     * Diagnostic counter incremented whenever Tier A inspection is evaluated (cache miss or hit).
     * Used by tests to prove that [EffectiveFontFeatureConfig.ShaperDefaults] short-circuits
     * BEFORE any font inspection or Tier A lookup.
     */
    val tierAEvaluationCount: AtomicLong = AtomicLong(0L)

    fun clearTierACache() {
        tierACache.clear()
    }

    /**
     * Normalizes [paragraph] and [baseStyle] to carry the exact [config] feature settings across
     * the whole paragraph, every piece, every track, and the overlay.
     */
    fun applyFeatureConfig(
        paragraph: AnnotatedString,
        baseStyle: TextStyle,
        config: EffectiveFontFeatureConfig,
    ): Pair<AnnotatedString, TextStyle> {
        val normalizedStyle = applyEffectiveFeatureConfig(baseStyle, config)
        val hasConflictingSpan = paragraph.spanStyles.any {
            it.item.fontFeatureSettings != null && it.item.fontFeatureSettings != config.fontFeatureSettings
        }
        if (!hasConflictingSpan) return paragraph to normalizedStyle
        val normalizedParagraph = AnnotatedString(
            text = paragraph.text,
            spanStyles = paragraph.spanStyles.map { span ->
                if (span.item.fontFeatureSettings != null &&
                    span.item.fontFeatureSettings != config.fontFeatureSettings
                ) {
                    AnnotatedString.Range(
                        item = span.item.copy(fontFeatureSettings = config.fontFeatureSettings),
                        start = span.start,
                        end = span.end,
                        tag = span.tag,
                    )
                } else {
                    span
                }
            },
            paragraphStyles = paragraph.paragraphStyles,
        )
        return normalizedParagraph to normalizedStyle
    }

    /**
     * THE ONE shared face-assignment function.
     *
     * Determines per Unicode scalar which bundled face draws it using the rendering Skia
     * [Typeface.getUTF32Glyph] call, and simultaneously builds [ParagraphFaceAssignment.routedAnnotatedString]
     * with clamped fallback spans (e.g. monochrome `Noto Emoji`) so drawing and certification
     * cannot diverge.
     */
    fun assignParagraphFaces(
        resolver: DeckFontResolver,
        paragraph: AnnotatedString,
        baseStyle: TextStyle,
    ): ParagraphFaceAssignment {
        val text = paragraph.text
        val scalars = ArrayList<AssignedScalar>(text.length)
        var hasUnpairedSurrogate = false
        var hasSplitSurrogateSpan = false
        var hasUnavailableTypeface = false
        var hasOutOfDomainGlyph = false
        var hasNonBundledFace = false
        var hasUnresolvedScalar = false

        val emojiFamily = resolver.emojiFamily
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            val cp: Int
            val nextIdx: Int
            if (ch.isHighSurrogate()) {
                if (i + 1 >= text.length || !text[i + 1].isLowSurrogate()) {
                    hasUnpairedSurrogate = true
                    hasUnresolvedScalar = true
                    scalars += AssignedScalar(i, i + 1, ch.code, null, 0, null, null, false)
                    i += 1
                    continue
                }
                cp = Character.toCodePoint(ch, text[i + 1])
                nextIdx = i + 2
            } else if (ch.isLowSurrogate()) {
                hasUnpairedSurrogate = true
                hasUnresolvedScalar = true
                scalars += AssignedScalar(i, i + 1, ch.code, null, 0, null, null, false)
                i += 1
                continue
            } else {
                cp = ch.code
                nextIdx = i + 1
            }

            // Detect any SpanStyle splitting a surrogate pair.
            if (nextIdx - i == 2) {
                val mid = i + 1
                for (span in paragraph.spanStyles) {
                    if ((span.start == mid && span.end > mid) || (span.end == mid && span.start < mid)) {
                        hasSplitSurrogateSpan = true
                        break
                    }
                }
            }

            var effFamily = baseStyle.fontFamily
            var effWeight = baseStyle.fontWeight ?: FontWeight.Normal
            var effStyle = baseStyle.fontStyle ?: FontStyle.Normal
            for (span in paragraph.spanStyles) {
                if (span.start <= i && span.end >= nextIdx) {
                    span.item.fontFamily?.let { effFamily = it }
                    span.item.fontWeight?.let { effWeight = it }
                    span.item.fontStyle?.let { effStyle = it }
                }
            }

            val primaryFamily = resolver.findResolvedFamily(effFamily)
            val primaryFace = primaryFamily?.resolveFace(effWeight, effStyle)

            if (primaryFace == null) {
                hasNonBundledFace = true
                hasUnresolvedScalar = true
                scalars += AssignedScalar(i, nextIdx, cp, null, 0, null, null, cp == CODEPOINT_LINE_FEED)
                i = nextIdx
                continue
            }
            if (primaryFace.origin != FontOrigin.Bundled) {
                hasNonBundledFace = true
                scalars += AssignedScalar(i, nextIdx, cp, primaryFace, 0, null, null, cp == CODEPOINT_LINE_FEED)
                i = nextIdx
                continue
            }

            val primaryTf = resolver.renderingTypeface(primaryFace)
            if (primaryTf == null) {
                hasUnavailableTypeface = true
                hasUnresolvedScalar = true
                scalars += AssignedScalar(i, nextIdx, cp, primaryFace, 0, null, null, cp == CODEPOINT_LINE_FEED)
                i = nextIdx
                continue
            }

            val primaryGid = primaryTf.getUTF32Glyph(cp).toInt() and 0xFFFF
            val primaryNumGlyphs = primaryFace.inspection?.numGlyphs ?: 0
            if (primaryGid != 0) {
                if (primaryNumGlyphs <= 0 || primaryGid >= primaryNumGlyphs) {
                    hasOutOfDomainGlyph = true
                }
                scalars += AssignedScalar(
                    utf16Start = i,
                    utf16End = nextIdx,
                    codePoint = cp,
                    face = primaryFace,
                    nominalGlyphId = primaryGid,
                    usedFallbackFamily = null,
                    fallbackWeight = null,
                    isLineFeed = cp == CODEPOINT_LINE_FEED,
                )
            } else if (cp == CODEPOINT_LINE_FEED) {
                // LF is never font-fallback-shaped; assign to its run's bundled face with nominal
                // glyph 0 and seed {0, spaceGlyph} into Tier B universes.
                scalars += AssignedScalar(
                    utf16Start = i,
                    utf16End = nextIdx,
                    codePoint = cp,
                    face = primaryFace,
                    nominalGlyphId = 0,
                    usedFallbackFamily = null,
                    fallbackWeight = null,
                    isLineFeed = true,
                )
            } else {
                // Try declared bundled fallback-role family (e.g. role="emoji").
                var assignedFallback: AssignedScalar? = null
                if (emojiFamily != null &&
                    emojiFamily.origin == FontOrigin.Bundled &&
                    emojiFamily.familyName != primaryFamily.familyName
                ) {
                    val clampedFbWeight = emojiFamily.clampWeight(effWeight, FontStyle.Normal)
                    val fbFace = emojiFamily.resolveFace(clampedFbWeight, FontStyle.Normal)
                    if (fbFace != null && fbFace.origin == FontOrigin.Bundled) {
                        val fbTf = resolver.renderingTypeface(fbFace)
                        if (fbTf == null) {
                            hasUnavailableTypeface = true
                        } else {
                            val fbGid = fbTf.getUTF32Glyph(cp).toInt() and 0xFFFF
                            if (fbGid != 0) {
                                val fbNumGlyphs = fbFace.inspection?.numGlyphs ?: 0
                                if (fbNumGlyphs <= 0 || fbGid >= fbNumGlyphs) {
                                    hasOutOfDomainGlyph = true
                                }
                                assignedFallback = AssignedScalar(
                                    utf16Start = i,
                                    utf16End = nextIdx,
                                    codePoint = cp,
                                    face = fbFace,
                                    nominalGlyphId = fbGid,
                                    usedFallbackFamily = emojiFamily,
                                    fallbackWeight = clampedFbWeight,
                                    isLineFeed = false,
                                )
                            }
                        }
                    }
                }
                if (assignedFallback != null) {
                    scalars += assignedFallback
                } else {
                    hasUnresolvedScalar = true
                    scalars += AssignedScalar(i, nextIdx, cp, null, 0, null, null, false)
                }
            }
            i = nextIdx
        }

        val routed = buildRoutedAnnotatedString(paragraph, scalars)
        return ParagraphFaceAssignment(
            text = text,
            scalars = scalars,
            routedAnnotatedString = routed,
            hasUnpairedSurrogate = hasUnpairedSurrogate,
            hasSplitSurrogateSpan = hasSplitSurrogateSpan,
            hasUnavailableTypeface = hasUnavailableTypeface,
            hasOutOfDomainGlyph = hasOutOfDomainGlyph,
            hasNonBundledFace = hasNonBundledFace,
            hasUnresolvedScalar = hasUnresolvedScalar,
        )
    }

    private fun buildRoutedAnnotatedString(
        paragraph: AnnotatedString,
        scalars: List<AssignedScalar>,
    ): AnnotatedString {
        var anyFallback = false
        for (s in scalars) {
            if (s.usedFallbackFamily != null) {
                anyFallback = true
                break
            }
        }
        if (!anyFallback) return paragraph

        return buildAnnotatedString {
            append(paragraph)
            var runStart = -1
            var runEnd = -1
            var runFamily: ResolvedFontFamily? = null
            var runWeight: FontWeight? = null

            fun flush() {
                val fam = runFamily
                val w = runWeight
                if (runStart >= 0 && runEnd > runStart && fam != null && w != null) {
                    addStyle(
                        SpanStyle(
                            fontFamily = fam.fontFamily,
                            fontWeight = w,
                            fontStyle = FontStyle.Normal,
                        ),
                        runStart,
                        runEnd,
                    )
                }
                runStart = -1
                runEnd = -1
                runFamily = null
                runWeight = null
            }

            for (s in scalars) {
                val fbFam = s.usedFallbackFamily
                val fbWeight = s.fallbackWeight
                val extendForEmojiJoiner = fbFam == null &&
                    runStart >= 0 &&
                    (s.codePoint == CODEPOINT_VARIATION_SELECTOR_16 || s.codePoint == CODEPOINT_ZERO_WIDTH_JOINER)
                when {
                    fbFam != null && fbWeight != null -> {
                        if (runStart >= 0 && runFamily == fbFam && runWeight == fbWeight && runEnd == s.utf16Start) {
                            runEnd = s.utf16End
                        } else {
                            flush()
                            runStart = s.utf16Start
                            runEnd = s.utf16End
                            runFamily = fbFam
                            runWeight = fbWeight
                        }
                    }
                    extendForEmojiJoiner -> {
                        runEnd = s.utf16End
                    }
                    else -> flush()
                }
            }
            flush()
        }
    }

    /**
     * THE ONE authoritative Condition 1 character-safety predicate.
     *
     * 1. Configuration guard: under [EffectiveFontFeatureConfig.ShaperDefaults], immediately
     *    returns a refused certifier BEFORE inspecting any font or touching any cache.
     * 2. Paragraph preconditions 1–5 (NFC, layout-resolved LTR, Unicode category/script allow-list
     *    excluding U+2044 / U+2215 / Hangul / marks / Cf / unpaired surrogates, all faces bundled,
     *    nothing unresolved, nominal glyphs from rendering Skia [Typeface] inside domain `G`).
     * 3. Per-face Tier A check (cached by `(contentSha256, effectiveConfig)`): rejects that face
     *    alone if Always-On features reach multi-glyph rules or unsupported tables/parse errors exist.
     * 4. Per-face Tier B paragraph universe `U` closure (bounded by [limits.maxFixedPointRounds];
     *    exhaustion refuses ALL words of the paragraph).
     */
    fun certifyParagraph(
        resolver: DeckFontResolver,
        paragraph: AnnotatedString,
        baseStyle: TextStyle,
        effectiveConfig: EffectiveFontFeatureConfig,
        isLayoutLtrAtOffset: (Int) -> Boolean,
        existingAssignment: ParagraphFaceAssignment? = null,
        limits: GsubParserLimits = GsubParserLimits.Default,
    ): ParagraphCharacterSafetyCertifier {
        // 0. Configuration guard: under ShaperDefaults, refuse immediately before ANY font inspection!
        if (effectiveConfig != EffectiveFontFeatureConfig.CharacterForcedOff) {
            return ParagraphCharacterSafetyCertifier.refused(
                config = effectiveConfig,
                reason = ParagraphCharacterSafetyCertifier.REASON_SHAPER_DEFAULTS,
            )
        }

        val text = paragraph.text
        if (text.isEmpty()) {
            return ParagraphCharacterSafetyCertifier.refused(effectiveConfig, "empty-paragraph")
        }

        // Precondition 5: NFC.
        if (!Normalizer.isNormalized(text, Normalizer.Form.NFC)) {
            return ParagraphCharacterSafetyCertifier.refused(effectiveConfig, "not-nfc")
        }

        // Precondition 3: Left-to-right at every offset as resolved by layout.
        for (offset in 0 until text.length) {
            if (!isLayoutLtrAtOffset(offset)) {
                return ParagraphCharacterSafetyCertifier.refused(effectiveConfig, "non-ltr-bidi")
            }
        }

        // Precondition 4 + malformed UTF-16 check: every scalar on allow-list.
        val allowListError = validateScalarAllowList(text)
        if (allowListError != null) {
            return ParagraphCharacterSafetyCertifier.refused(effectiveConfig, allowListError)
        }

        // Preconditions 1 & 2 via the ONE shared face-assignment function (Skia Typeface nominal glyphs).
        val assignment = existingAssignment ?: assignParagraphFaces(resolver, paragraph, baseStyle)
        if (assignment.hasUnpairedSurrogate || assignment.hasSplitSurrogateSpan) {
            return ParagraphCharacterSafetyCertifier.refused(effectiveConfig, "malformed-utf16", assignment)
        }
        if (assignment.hasUnavailableTypeface) {
            return ParagraphCharacterSafetyCertifier.refused(effectiveConfig, "unavailable-typeface", assignment)
        }
        if (assignment.hasNonBundledFace) {
            return ParagraphCharacterSafetyCertifier.refused(effectiveConfig, "non-bundled-face", assignment)
        }
        if (assignment.hasUnresolvedScalar) {
            return ParagraphCharacterSafetyCertifier.refused(effectiveConfig, "unresolved-character", assignment)
        }
        if (assignment.hasOutOfDomainGlyph) {
            return ParagraphCharacterSafetyCertifier.refused(effectiveConfig, "nominal-glyph-out-of-domain", assignment)
        }

        return buildCertifierFromScalars(
            resolver = resolver,
            effectiveConfig = effectiveConfig,
            assignment = assignment,
            textLength = text.length,
            scalars = assignment.scalars,
            limits = limits,
        )
    }

    private fun buildCertifierFromScalars(
        resolver: DeckFontResolver,
        effectiveConfig: EffectiveFontFeatureConfig,
        assignment: ParagraphFaceAssignment?,
        textLength: Int,
        scalars: List<AssignedScalar>,
        limits: GsubParserLimits,
    ): ParagraphCharacterSafetyCertifier {
        val scalarsByFace = LinkedHashMap<ResolvedFontFace, MutableList<AssignedScalar>>(2)
        for (s in scalars) {
            val face = s.face
                ?: return ParagraphCharacterSafetyCertifier.refused(effectiveConfig, "unresolved-character", assignment)
            scalarsByFace.getOrPut(face) { ArrayList() }.add(s)
        }

        val faceStates = HashMap<ResolvedFontFace, FaceTierBState>(scalarsByFace.size)
        for ((face, faceScalars) in scalarsByFace) {
            val inspection = face.inspection
            if (inspection == null) {
                faceStates[face] = FaceTierBState.TierARejected
                continue
            }
            if (!checkTierACached(inspection, effectiveConfig)) {
                faceStates[face] = FaceTierBState.TierARejected
                continue
            }

            val computed = computeFaceTierB(resolver, face, inspection, faceScalars, limits.maxFixedPointRounds)
            if (computed == null) {
                // Fixed-point budget exhausted -> refuse EVERY word of the paragraph in EVERY face!
                return ParagraphCharacterSafetyCertifier.refused(
                    config = effectiveConfig,
                    reason = ParagraphCharacterSafetyCertifier.REASON_FIXED_POINT_EXHAUSTED,
                    assignment = assignment,
                )
            }
            faceStates[face] = computed
        }

        return ParagraphCharacterSafetyCertifier(
            effectiveConfig = effectiveConfig,
            assignment = assignment,
            paragraphRefusedReason = null,
            textLength = textLength,
            faceStates = faceStates,
            scalars = scalars,
            maxFixedPointRounds = limits.maxFixedPointRounds,
        )
    }

    private fun checkTierACached(
        inspection: FontTableInspection,
        config: EffectiveFontFeatureConfig,
    ): Boolean {
        if (config != EffectiveFontFeatureConfig.CharacterForcedOff) return false
        tierAEvaluationCount.incrementAndGet()
        // Parse errors (including strict parse-budget rejections) fail closed without polluting or
        // consulting the cross-call Tier A cache.
        if (inspection.parseError != null) return false
        val key = inspection.contentSha256 to config
        return tierACache.computeIfAbsent(key) { inspection.passesTierAForcedOff }
    }

    private fun computeFaceTierB(
        resolver: DeckFontResolver,
        face: ResolvedFontFace,
        inspection: FontTableInspection,
        faceScalars: List<AssignedScalar>,
        maxRounds: Int,
    ): FaceTierBState? {
        val numGlyphs = inspection.numGlyphs
        val gsub = inspection.gsub
        if (!gsub.present || (gsub.alwaysOnLookupIndices.isEmpty() && gsub.perWordLookupIndices.isEmpty())) {
            return FaceTierBState.SafeAll(numGlyphs)
        }

        val universeU = BitSet(numGlyphs)
        var hasLf = false
        for (s in faceScalars) {
            if (s.nominalGlyphId in 0 until numGlyphs) {
                universeU.set(s.nominalGlyphId)
            }
            if (s.isLineFeed) hasLf = true
        }
        val spaceGlyphId = if (hasLf) {
            universeU.set(0)
            val spGid = resolver.renderingTypeface(face)?.getUTF32Glyph(' '.code)?.toInt()?.and(0xFFFF) ?: 0
            if (spGid in 0 until numGlyphs) universeU.set(spGid)
            spGid
        } else {
            0
        }

        val activatedLookups = BitSet(gsub.lookupCount)
        for (idx in gsub.alwaysOnLookupIndices) activatedLookups.set(idx)
        for (idx in gsub.perWordLookupIndices) activatedLookups.set(idx)

        // Step 1: Paragraph universe U fixed-point closure over all rules in play (Always-On + Per-Word).
        var converged = false
        for (round in 0 until maxRounds) {
            var changed = false
            var lIdx = activatedLookups.nextSetBit(0)
            while (lIdx >= 0) {
                val lookup = gsub.parsedLookups[lIdx]
                for (rule in lookup.rules) {
                    if (ruleMatchesUniverse(rule, universeU, numGlyphs)) {
                        for (outGid in rule.outputs) {
                            if (!universeU.get(outGid)) {
                                universeU.set(outGid)
                                changed = true
                            }
                        }
                        for (nestedIdx in rule.nestedLookupIndices) {
                            if (!activatedLookups.get(nestedIdx)) {
                                activatedLookups.set(nestedIdx)
                                changed = true
                            }
                        }
                    }
                }
                lIdx = activatedLookups.nextSetBit(lIdx + 1)
            }
            if (!changed) {
                converged = true
                break
            }
        }
        if (!converged) return null

        // Step 2: Possible rules = every rule in activated lookups whose every position intersects U.
        val possibleOutputRules = ArrayList<GsubRule>()
        val possibleMultiGlyphRules = ArrayList<GsubRule>()
        var lIdx = activatedLookups.nextSetBit(0)
        while (lIdx >= 0) {
            val lookup = gsub.parsedLookups[lIdx]
            for (rule in lookup.rules) {
                if (ruleMatchesUniverse(rule, universeU, numGlyphs)) {
                    if (rule.isOutputProducing) possibleOutputRules += rule
                    if (rule.isMultiGlyph) possibleMultiGlyphRules += rule
                }
            }
            lIdx = activatedLookups.nextSetBit(lIdx + 1)
        }

        return FaceTierBState.Computed(
            numGlyphs = numGlyphs,
            possibleOutputRules = possibleOutputRules,
            possibleMultiGlyphRules = possibleMultiGlyphRules,
            spaceGlyphId = spaceGlyphId,
        )
    }

    internal fun validateScalarAllowList(text: String): String? {
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            val cp: Int
            if (ch.isHighSurrogate()) {
                if (i + 1 >= text.length || !text[i + 1].isLowSurrogate()) return "unpaired-surrogate"
                cp = Character.toCodePoint(ch, text[i + 1])
                i += 2
            } else if (ch.isLowSurrogate()) {
                return "unpaired-surrogate"
            } else {
                cp = ch.code
                i += 1
            }

            if (cp == CODEPOINT_FRACTION_SLASH || cp == CODEPOINT_DIVISION_SLASH) {
                return "disallowed-fraction-or-division-slash"
            }
            if (cp != CODEPOINT_LINE_FEED && !isAllowedGeneralCategory(Character.getType(cp))) {
                return "disallowed-unicode-category"
            }
            if (!isAllowedUnicodeScript(Character.UnicodeScript.of(cp))) {
                return "disallowed-unicode-script"
            }
        }
        return null
    }

    private fun isAllowedGeneralCategory(type: Int): Boolean = when (type) {
        Character.UPPERCASE_LETTER.toInt(),
        Character.LOWERCASE_LETTER.toInt(),
        Character.TITLECASE_LETTER.toInt(),
        Character.MODIFIER_LETTER.toInt(),
        Character.OTHER_LETTER.toInt(),
        Character.DECIMAL_DIGIT_NUMBER.toInt(),
        Character.LETTER_NUMBER.toInt(),
        Character.OTHER_NUMBER.toInt(),
        Character.CONNECTOR_PUNCTUATION.toInt(),
        Character.DASH_PUNCTUATION.toInt(),
        Character.START_PUNCTUATION.toInt(),
        Character.END_PUNCTUATION.toInt(),
        Character.INITIAL_QUOTE_PUNCTUATION.toInt(),
        Character.FINAL_QUOTE_PUNCTUATION.toInt(),
        Character.OTHER_PUNCTUATION.toInt(),
        Character.MATH_SYMBOL.toInt(),
        Character.CURRENCY_SYMBOL.toInt(),
        Character.MODIFIER_SYMBOL.toInt(),
        Character.OTHER_SYMBOL.toInt(),
        Character.SPACE_SEPARATOR.toInt(),
        -> true
        else -> false
    }

    private fun isAllowedUnicodeScript(script: Character.UnicodeScript): Boolean = when (script) {
        Character.UnicodeScript.LATIN,
        Character.UnicodeScript.GREEK,
        Character.UnicodeScript.CYRILLIC,
        Character.UnicodeScript.HAN,
        Character.UnicodeScript.HIRAGANA,
        Character.UnicodeScript.KATAKANA,
        Character.UnicodeScript.COMMON,
        -> true
        else -> false
    }
}

internal fun ruleMatchesUniverse(rule: GsubRule, universe: BitSet, numGlyphs: Int): Boolean {
    for (m in rule.backtrack) {
        if (!m.intersects(universe, numGlyphs)) return false
    }
    for (m in rule.input) {
        if (!m.intersects(universe, numGlyphs)) return false
    }
    for (m in rule.lookahead) {
        if (!m.intersects(universe, numGlyphs)) return false
    }
    return true
}

internal fun anyInputIntersects(rule: GsubRule, wordBits: BitSet, numGlyphs: Int): Boolean {
    for (m in rule.input) {
        if (m.intersects(wordBits, numGlyphs)) return true
    }
    return false
}

internal fun GlyphMatcher.intersects(bits: BitSet, numGlyphs: Int): Boolean = when (this) {
    is GlyphMatcher.Single -> glyphId in 0 until numGlyphs && bits.get(glyphId)
    is GlyphMatcher.ExplicitSet -> {
        var hit = false
        for (g in sortedGlyphs) {
            if (g in 0 until numGlyphs && bits.get(g)) {
                hit = true
                break
            }
        }
        hit
    }
    is GlyphMatcher.Complement -> {
        if (isEmpty) {
            false
        } else {
            val limit = minOf(domainSize, numGlyphs)
            var hit = false
            var g = bits.nextSetBit(0)
            while (g >= 0 && g < limit) {
                if (excludedSortedGlyphs.binarySearch(g) < 0) {
                    hit = true
                    break
                }
                g = bits.nextSetBit(g + 1)
            }
            hit
        }
    }
}
