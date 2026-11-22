package dev.sebastiano.ciacola.text.v2

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontListFontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.FontLoadResult
import androidx.compose.ui.text.platform.LoadedFont
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.isUnspecified
import dev.sebastiano.ciacola.core.fonts.CharacterSafetyEngine
import dev.sebastiano.ciacola.core.fonts.DeckFontResolver
import dev.sebastiano.ciacola.core.fonts.EffectiveFontFeatureConfig
import dev.sebastiano.ciacola.core.fonts.ParagraphFaceAssignment
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.floor
import org.jetbrains.skia.Data
import org.jetbrains.skia.Font as SkiaFont
import org.jetbrains.skia.FontEdging
import org.jetbrains.skia.FontFeature as SkiaFontFeature
import org.jetbrains.skia.FontHinting
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.FontStyle as SkiaFontStyle
import org.jetbrains.skia.FontVariation as SkiaFontVariation
import org.jetbrains.skia.Path as SkiaPath
import org.jetbrains.skia.PathAddMode
import org.jetbrains.skia.PathBuilder
import org.jetbrains.skia.PathDirection
import org.jetbrains.skia.Rect as SkiaRect
import org.jetbrains.skia.Typeface as SkiaTypeface
import org.jetbrains.skia.shaper.Shaper
import org.jetbrains.skia.shaper.ShapingOptions

/**
 * Applies the single authoritative [EffectiveFontFeatureConfig] to both
 * [StyledTextV2.style] and every conflicting [androidx.compose.ui.text.SpanStyle] on
 * [StyledTextV2.text].
 */
fun StyledTextV2.withEffectiveFeatureConfig(config: EffectiveFontFeatureConfig): StyledTextV2 {
    val (normalizedText, normalizedStyle) = CharacterSafetyEngine.applyFeatureConfig(text, style, config)
    return if (normalizedText === text && normalizedStyle == style) {
        this
    } else {
        copy(text = normalizedText, style = normalizedStyle)
    }
}

/**
 * Prepares a [StyledTextV2] with weight clamping, the single [EffectiveFontFeatureConfig], and
 * the ONE shared face-assignment function ([DeckFontResolver.assignParagraphFaces]), returning
 * both the prepared [StyledTextV2] and the [ParagraphFaceAssignment] handed to Condition 1.
 */
internal fun StyledTextV2.prepareWithSharedFaceAssignment(
    fontResolver: DeckFontResolver?,
    effectiveConfig: EffectiveFontFeatureConfig,
): Pair<StyledTextV2, ParagraphFaceAssignment?> {
    val clampedStyle = fontResolver?.clampStyleWeights(style) ?: style
    val clampedText = if (fontResolver != null && text.spanStyles.isNotEmpty()) {
        val fallbackStyle = clampedStyle.fontStyle ?: FontStyle.Normal
        var changed = false
        val updatedSpans = text.spanStyles.map { span ->
            val clamped = fontResolver.clampStyleWeights(span.item, fallbackStyle)
            if (clamped != span.item) {
                changed = true
                AnnotatedString.Range(clamped, span.start, span.end, span.tag)
            } else {
                span
            }
        }
        if (changed) AnnotatedString(text.text, updatedSpans, text.paragraphStyles) else text
    } else {
        text
    }

    val (featureText, featureStyle) = CharacterSafetyEngine.applyFeatureConfig(
        paragraph = clampedText,
        baseStyle = clampedStyle,
        config = effectiveConfig,
    )
    val assignment = fontResolver?.assignParagraphFaces(featureText, featureStyle)
    val routedText = assignment?.routedAnnotatedString ?: featureText
    return copy(text = routedText, style = featureStyle) to assignment
}

/**
 * Exact painted alphabetic baseline offset inside a 1-line un-trimmed / default-trim SkParagraph
 * layout: `floor(-SkiaFont(typeface, fontSizePx).metrics.ascent + 0.5f)`.
 */
fun truePaintedBaselinePx(typeface: SkiaTypeface, fontSizePx: Float): Float {
    val ascent = SkiaFont(typeface, fontSizePx).use { -it.metrics.ascent }
    return floor(ascent + 0.5f)
}

/**
 * Exact painted alphabetic baseline Y inside a multi-line resting paragraph, matching
 * `TextLine::paint()` to `<= 0.06 px` across wrapped mixed-style paragraphs.
 */
fun wholeLinePaintedBaselinePx(
    wholeLineBaseline: Float,
    pieceFirstBaseline: Float,
    line: Int,
    typeface: SkiaTypeface,
    fontSizePx: Float,
    lineHeightPx: Float?,
    trimsFirstTop: Boolean,
): Float {
    val lineY = wholeLineBaseline - pieceFirstBaseline
    val metrics = SkiaFont(typeface, fontSizePx).use { it.metrics }
    val unscaledAscent = -metrics.ascent
    val naturalHeight = unscaledAscent + metrics.descent
    val localAscent = if (line == 0 && trimsFirstTop) {
        unscaledAscent
    } else if (lineHeightPx != null && lineHeightPx > 0f && naturalHeight > 0f) {
        unscaledAscent * (lineHeightPx / naturalHeight)
    } else {
        unscaledAscent
    }
    return lineY + floor(localAscent + 0.5f)
}

/**
 * Thread-safe cache resolving Compose [FontFamily] / [LoadedFont] into Skia [SkiaTypeface]
 * instances (reusing [DeckFontResolver.renderingTypeface] first so bundled TTF bytes are decoded
 * once) for exact baseline metric queries.
 */
internal object SkiaTypefaceCache {
    private class CachedTypeface(val typeface: SkiaTypeface?)

    private val byIdentity = ConcurrentHashMap<String, CachedTypeface>()

    fun resolve(
        fontFamily: FontFamily?,
        weight: FontWeight,
        style: FontStyle,
        fontResolver: DeckFontResolver?,
        fontFamilyResolver: FontFamily.Resolver? = null,
    ): SkiaTypeface? {
        if (fontResolver != null && fontFamily != null) {
            val resolvedFamily = fontResolver.findResolvedFamily(fontFamily)
            val face = resolvedFamily?.resolveFace(weight, style)
                ?: resolvedFamily?.resolveFace(weight, FontStyle.Normal)
            if (face != null) {
                fontResolver.renderingTypeface(face)?.let { return it }
            }
        }
        val loaded = findLoadedFont(fontFamily, weight, style)
        if (loaded != null) {
            return byIdentity.computeIfAbsent(loaded.identity) {
                val data = Data.makeFromBytes(loaded.data)
                val base = try {
                    FontMgr.default.makeFromData(data)
                } catch (_: Throwable) {
                    null
                } finally {
                    data.close()
                }
                if (base == null) {
                    CachedTypeface(null)
                } else {
                    val skiaVars = loaded.variationSettings.settings.map { setting ->
                        SkiaFontVariation(setting.axisName, setting.toVariationValue(null))
                    }.toTypedArray()
                    val resolved = if (skiaVars.isNotEmpty()) {
                        val cloned = base.makeClone(skiaVars)
                        base.close()
                        cloned
                    } else {
                        base
                    }
                    CachedTypeface(resolved)
                }
            }.typeface
        }
        if (fontFamilyResolver != null) {
            val resolved = runCatching {
                fontFamilyResolver.resolve(
                    fontFamily = fontFamily,
                    fontWeight = weight,
                    fontStyle = style,
                ).value
            }.getOrNull()
            (resolved as? FontLoadResult)?.typeface?.let { return it }
        }
        val isBold = weight.weight >= 600
        val isItalic = style == FontStyle.Italic
        val skiaStyle = when {
            isBold && isItalic -> SkiaFontStyle.BOLD_ITALIC
            isBold -> SkiaFontStyle.BOLD
            isItalic -> SkiaFontStyle.ITALIC
            else -> SkiaFontStyle.NORMAL
        }
        return FontMgr.default.matchFamilyStyle(null, skiaStyle)
    }

    private fun findLoadedFont(
        fontFamily: FontFamily?,
        weight: FontWeight,
        style: FontStyle,
    ): LoadedFont? {
        val listFamily = fontFamily as? FontListFontFamily ?: return null
        val loadedFonts = listFamily.fonts.filterIsInstance<LoadedFont>()
        if (loadedFonts.isEmpty()) return null
        return loadedFonts.minByOrNull { f ->
            val stylePenalty = if (f.style == style) 0 else 10_000
            stylePenalty + abs(f.weight.weight - weight.weight)
        }
    }
}

/**
 * Pure vector glyph outline run extracted once at plan time from [SkiaFont.getPath] at the larger
 * morph endpoint (`max(fromSp, toSp)`), in baseline-anchored coordinates (`y = 0` on the
 * alphabetic baseline).
 *
 * Keeping per-glyph [SkiaPath] outlines individual (instead of merging whole lines into one giant
 * `> 256 px` path) ensures Skia Ganesh on non-MSAA Metal window surfaces (`sampleCount = 0`) uses
 * its smooth 8-bit analytical AA path renderer rather than non-AA hardware tessellation, while
 * bypassing `SkTextBlob` integer-Y baseline snapping (`SkAxisAlignment::kX`) and keeping glyphs
 * 100% vector-sharp at arbitrary stage or Loupe zoom (zero intermediate bitmaps).
 */
class VectorGlyphOutline(
    val path: SkiaPath,
    val x: Float,
    val y: Float,
)

class VectorGlyphRun(
    val glyphs: List<VectorGlyphOutline>,
    val decorationPath: SkiaPath?,
    val argbColor: Int,
    val backgroundRect: SkiaRect? = null,
    val backgroundArgbColor: Int = 0,
)

private val CSS_FONT_FEATURE_REGEX = Regex("""["']?([A-Za-z0-9 ]{4})["']?(?:\s*(?:=|\s)\s*(on|off|\d+))?""")

internal object VectorGlyphOutlineBuilder {
    fun buildRuns(
        text: AnnotatedString,
        baseStyle: TextStyle,
        largeLayout: TextLayoutResult,
        isRtl: Boolean,
        density: Density,
        fontResolver: DeckFontResolver?,
    ): List<VectorGlyphRun>? {
        val raw = text.text
        if (raw.isEmpty()) return emptyList()

        val cuts = sortedSetOf(0, raw.length)
        for (span in text.spanStyles) {
            cuts += span.start.coerceIn(0, raw.length)
            cuts += span.end.coerceIn(0, raw.length)
        }
        val boundaries = cuts.toIntArray()
        if (boundaries.size < 2) return emptyList()

        val baseSpan = baseStyle.toSpanStyle()
        val pxPerSp = density.density * density.fontScale
        val defaultFontSizePx = if (!baseSpan.fontSize.isUnspecified) {
            baseSpan.fontSize.value * pxPerSp
        } else {
            16f * pxPerSp
        }

        val shaper = Shaper.makeShapeDontWrapOrReorder()
        val out = ArrayList<VectorGlyphRun>(boundaries.size - 1)
        try {
            for (bi in 0 until boundaries.size - 1) {
                val s = boundaries[bi]
                val e = boundaries[bi + 1]
                if (e <= s) continue
                var merged: SpanStyle = baseSpan
                for (span in text.spanStyles) {
                    if (span.start < e && span.end > s) {
                        merged = merged.merge(span.item)
                    }
                }
                val activeShadow = merged.shadow ?: baseStyle.shadow
                if (activeShadow != null && activeShadow.color != Color.Unspecified && activeShadow.color.alpha > 0f) {
                    return null
                }
                val family = merged.fontFamily ?: baseStyle.fontFamily
                val weight = merged.fontWeight ?: baseStyle.fontWeight ?: FontWeight.Normal
                val fontStyle = merged.fontStyle ?: baseStyle.fontStyle ?: FontStyle.Normal
                val typeface = SkiaTypefaceCache.resolve(
                    fontFamily = family,
                    weight = weight,
                    style = fontStyle,
                    fontResolver = fontResolver,
                    fontFamilyResolver = largeLayout.layoutInput.fontFamilyResolver,
                ) ?: return null
                val sliceFontSizePx = if (!merged.fontSize.isUnspecified) {
                    merged.fontSize.value * pxPerSp
                } else {
                    defaultFontSizePx
                }
                val letterSpacingPx = when {
                    merged.letterSpacing.isUnspecified -> 0f
                    merged.letterSpacing.type == TextUnitType.Sp -> merged.letterSpacing.value * pxPerSp
                    merged.letterSpacing.type == TextUnitType.Em -> merged.letterSpacing.value * sliceFontSizePx
                    else -> 0f
                }
                val baselineShiftPx = merged.baselineShift?.let { -it.multiplier * sliceFontSizePx } ?: 0f
                val sliceOriginX = if (s == 0 && !isRtl) {
                    0f
                } else {
                    largeLayout.getHorizontalPosition(s, usePrimaryDirection = !isRtl)
                }

                val skiaOpts = parseShapingOptions(merged.fontFeatureSettings ?: baseStyle.fontFeatureSettings, isRtl)
                val sliceStr = raw.substring(s, e)

                SkiaFont(typeface, sliceFontSizePx).use { font ->
                    font.isSubpixel = true
                    font.isLinearMetrics = true
                    font.hinting = FontHinting.NONE
                    font.edging = FontEdging.SUBPIXEL_ANTI_ALIAS

                    shaper.shapeLine(sliceStr, font, skiaOpts).use { line ->
                        val glyphs = line.glyphs
                        val positions = line.positions
                        val spaceGid = typeface.getUTF32Glyph(' '.code)
                        val nbspGid = typeface.getUTF32Glyph(0x00A0)
                        val outlines = ArrayList<VectorGlyphOutline>(glyphs.size)
                        for (gi in glyphs.indices) {
                            val gid = glyphs[gi]
                            val gx = sliceOriginX + positions[gi * 2] + gi * letterSpacingPx
                            val gy = positions[gi * 2 + 1] + baselineShiftPx
                            val gp = font.getPath(gid)
                            if (gp == null) {
                                // Bitmap-only glyph (e.g. sbix color emoji): fall back cleanly to TextPainter.
                                if (gid != spaceGid && gid != nbspGid && gid.toInt() != 0) {
                                    return null
                                }
                                continue
                            }
                            if (!gp.isEmpty) {
                                outlines += VectorGlyphOutline(path = gp, x = gx, y = gy)
                            } else {
                                gp.close()
                            }
                        }

                        val totalWidth = line.width + (glyphs.size - 1).coerceAtLeast(0) * letterSpacingPx
                        val metrics = font.metrics
                        val decoration = merged.textDecoration ?: baseStyle.textDecoration
                        var decorationPath: SkiaPath? = null
                        if (decoration != null && totalWidth > 0f &&
                            (decoration.contains(TextDecoration.Underline) || decoration.contains(TextDecoration.LineThrough))
                        ) {
                            val builder = PathBuilder()
                            try {
                                if (decoration.contains(TextDecoration.Underline)) {
                                    val uy = (metrics.underlinePosition ?: (sliceFontSizePx * 0.1f)) + baselineShiftPx
                                    val ut = (metrics.underlineThickness ?: (sliceFontSizePx * 0.06f)).coerceAtLeast(1f)
                                    builder.addRect(
                                        SkiaRect.makeXYWH(sliceOriginX, uy - ut * 0.5f, totalWidth, ut),
                                        PathDirection.CLOCKWISE,
                                        0,
                                    )
                                }
                                if (decoration.contains(TextDecoration.LineThrough)) {
                                    val sy = (metrics.strikeoutPosition ?: (-sliceFontSizePx * 0.3f)) + baselineShiftPx
                                    val st = (metrics.strikeoutThickness ?: (sliceFontSizePx * 0.06f)).coerceAtLeast(1f)
                                    builder.addRect(
                                        SkiaRect.makeXYWH(sliceOriginX, sy - st * 0.5f, totalWidth, st),
                                        PathDirection.CLOCKWISE,
                                        0,
                                    )
                                }
                                decorationPath = builder.detach()
                            } finally {
                                builder.close()
                            }
                        }

                        val bgColor = merged.background
                        val bgRect = if (bgColor != Color.Unspecified && bgColor.alpha > 0f && totalWidth > 0f) {
                            SkiaRect.makeLTRB(
                                sliceOriginX,
                                metrics.ascent + baselineShiftPx,
                                sliceOriginX + totalWidth,
                                metrics.descent + baselineShiftPx,
                            )
                        } else {
                            null
                        }

                        val fgColor = when {
                            merged.color != Color.Unspecified -> merged.color
                            baseStyle.color != Color.Unspecified -> baseStyle.color
                            else -> Color.White
                        }
                        out += VectorGlyphRun(
                            glyphs = outlines,
                            decorationPath = decorationPath,
                            argbColor = fgColor.toArgb(),
                            backgroundRect = bgRect,
                            backgroundArgbColor = if (bgRect != null) bgColor.toArgb() else 0,
                        )
                    }
                }
            }
            return out
        } finally {
            shaper.close()
        }
    }

    private fun parseShapingOptions(featureSettings: String?, isRtl: Boolean): ShapingOptions {
        val base = ShapingOptions.DEFAULT.withLeftToRight(!isRtl)
        if (featureSettings.isNullOrBlank()) return base
        val parsed = ArrayList<SkiaFontFeature>(4)
        for (part in featureSettings.split(',')) {
            val trimmed = part.trim()
            if (trimmed.isEmpty()) continue
            val m = CSS_FONT_FEATURE_REGEX.matchEntire(trimmed) ?: continue
            val tag = m.groupValues[1]
            val rawVal = m.groupValues[2].lowercase()
            val value = when {
                rawVal.isEmpty() || rawVal == "on" -> 1
                rawVal == "off" -> 0
                else -> rawVal.toIntOrNull() ?: 1
            }
            runCatching { SkiaFontFeature(tag, value) }.getOrNull()?.let { parsed += it }
        }
        return if (parsed.isEmpty()) base else base.withFeatures(parsed.toTypedArray())
    }
}
