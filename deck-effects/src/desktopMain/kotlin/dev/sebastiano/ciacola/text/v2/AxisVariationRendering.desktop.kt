package dev.sebastiano.ciacola.text.v2

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.FontHinting as ComposeFontHinting
import androidx.compose.ui.text.FontRasterizationSettings
import androidx.compose.ui.text.FontSmoothing
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.isUnspecified
import dev.sebastiano.ciacola.core.fonts.DeckFontResolver
import java.text.Bidi
import kotlin.math.roundToInt
import org.jetbrains.skia.Canvas as SkiaCanvas
import org.jetbrains.skia.Font as SkiaFont
import org.jetbrains.skia.FontEdging
import org.jetbrains.skia.FontHinting
import org.jetbrains.skia.FontVariation as SkiaFontVariation
import org.jetbrains.skia.Paint as SkiaPaint
import org.jetbrains.skia.Point
import org.jetbrains.skia.TextBlob
import org.jetbrains.skia.Typeface as SkiaTypeface
import org.jetbrains.skia.shaper.Shaper

/**
 * Bounded cache of Skia typeface clones at animated variable-axis coordinates.
 *
 * Axis animation never goes through Compose's `FontFamily` cache: each frame asks for a clone of the
 * piece's resting typeface at quantized coordinates (1/512 of each axis's range, well below what
 * the eye can tell apart). At most [CAPACITY] clones are kept in LRU order. Evicted clones are
 * closed only after [RETIRED_LIMIT] further evictions, so a clone fetched by a concurrent renderer
 * (for example a video export) is never freed while it is being drawn. Native handles therefore
 * stay bounded at `CAPACITY + RETIRED_LIMIT` however long a looping animation runs.
 */
internal object AxisVariationCache {
    const val CAPACITY: Int = 256
    const val RETIRED_LIMIT: Int = 64
    private const val STEPS_PER_RANGE: Float = 512f

    private data class Key(val restId: Int, val coordinates: List<Pair<String, Float>>)

    private val lock = Any()
    private val clones = LinkedHashMap<Key, SkiaTypeface>(64, 0.75f, true)
    private val retired = ArrayDeque<SkiaTypeface>()

    /** Live native clones held by the cache, including retired ones not yet closed. */
    val liveCount: Int
        get() = synchronized(lock) { clones.size + retired.size }

    /**
     * Returns [rest] with [offsets] (axis units, keyed by tag) added to its coordinates and clamped
     * to each axis's range, or `null` when none of [offsets] hits an axis the face has or every
     * offset rounds to the resting coordinate.
     */
    fun withOffsets(rest: SkiaTypeface, offsets: Map<String, Float>): SkiaTypeface? {
        if (offsets.isEmpty()) return null
        val axes = rest.variationAxes ?: return null
        if (axes.isEmpty()) return null
        val current = HashMap<String, Float>()
        rest.variations?.forEach { current[it.tag] = it.value }

        val coordinates = ArrayList<Pair<String, Float>>(axes.size)
        var moved = false
        for (axis in axes) {
            val restValue = current[axis.tag] ?: axis.defaultValue
            val offset = offsets[axis.tag]
            val target = if (offset == null) restValue else (restValue + offset).coerceIn(axis.minValue, axis.maxValue)
            val step = ((axis.maxValue - axis.minValue) / STEPS_PER_RANGE).coerceAtLeast(1e-3f)
            val quantized = (target / step).roundToInt() * step
            if (offset != null && (quantized - restValue).let { it > step / 2f || it < -step / 2f }) moved = true
            coordinates += axis.tag to quantized
        }
        if (!moved) return null

        val key = Key(rest.uniqueId, coordinates)
        synchronized(lock) {
            clones[key]?.let { return it }
        }
        val clone = try {
            rest.makeClone(coordinates.map { (tag, value) -> SkiaFontVariation(tag, value) }.toTypedArray(), 0)
        } catch (_: Throwable) {
            return null
        }
        synchronized(lock) {
            clones[key]?.let {
                clone.close()
                return it
            }
            clones[key] = clone
            while (clones.size > CAPACITY) {
                val eldest = clones.entries.iterator()
                val entry = eldest.next()
                eldest.remove()
                retired.addLast(entry.value)
            }
            while (retired.size > RETIRED_LIMIT) {
                retired.removeFirst().close()
            }
        }
        return clone
    }

    fun clearForTests() {
        synchronized(lock) {
            clones.values.forEach { it.close() }
            clones.clear()
            retired.forEach { it.close() }
            retired.clear()
        }
    }
}

/**
 * Draws one admitted piece with variable-axis offsets applied, glyph for glyph, instead of painting
 * its prebuilt paragraph layout.
 *
 * The piece keeps its resting horizontal centre while the axis changes its advance, and stays on
 * its painted baseline. Only plain single-style, left-to-right pieces qualify (no shadow, brush,
 * decoration or background); anything else keeps its normal paint and simply does not animate its
 * axes.
 */
internal object AxisPiecePainter {

    /** A paint routine for [piece] at [offsets], or `null` when the piece cannot vary its axes. */
    fun prepare(
        piece: AdmittedPiece,
        offsets: Map<String, Float>,
        density: Density,
        fontResolver: DeckFontResolver?,
    ): ((SkiaCanvas) -> Unit)? {
        if (offsets.isEmpty() || piece.isRtl) return null
        val layout = piece.layout
        val text = layout.layoutInput.text
        val raw = text.text
        if (raw.isEmpty()) return null
        // Mixed-direction pieces are admitted with isRtl = false; shaping them as one LTR run here
        // would bypass the paragraph's bidi reordering.
        if (Bidi.requiresBidi(raw.toCharArray(), 0, raw.length)) return null
        val baseStyle = layout.layoutInput.style
        var style: SpanStyle = baseStyle.toSpanStyle()
        for (span in text.spanStyles) {
            // A span that does not cover the whole piece means mixed styling: keep the normal paint.
            if (span.start > 0 || span.end < raw.length) return null
            style = style.merge(span.item)
        }
        if (style.brush != null || style.shadow != null || style.background.isSpecified) return null
        if (style.textDecoration != null && style.textDecoration != TextDecoration.None) return null
        if (style.baselineShift != null) return null

        val rest = SkiaTypefaceCache.resolve(
            fontFamily = style.fontFamily,
            weight = style.fontWeight ?: FontWeight.Normal,
            style = style.fontStyle ?: FontStyle.Normal,
            fontResolver = fontResolver,
            fontFamilyResolver = layout.layoutInput.fontFamilyResolver,
        ) ?: return null
        if (rest.variationAxes?.any { it.tag in offsets } != true) return null
        // An offset that rounds to rest still draws here, with the rest face: switching to the
        // paragraph paint for a frame mid-animation shows as a twitch, however closely the two match.
        val varied = AxisVariationCache.withOffsets(rest, offsets) ?: rest
        val raster = rasterization(baseStyle)

        val pxPerSp = density.density * density.fontScale
        val sizePx = if (style.fontSize.isUnspecified) piece.dominantFontSizeSp * pxPerSp else style.fontSize.value * pxPerSp
        val letterSpacingPx = when {
            style.letterSpacing.isUnspecified -> 0f
            style.letterSpacing.type == TextUnitType.Sp -> style.letterSpacing.value * pxPerSp
            style.letterSpacing.type == TextUnitType.Em -> style.letterSpacing.value * sizePx
            else -> 0f
        }
        val color = style.color.takeIf { it.isSpecified } ?: Color.Black
        val argb = color.toArgb()
        val baselineLocal = piece.paintedBaselineY - piece.originY
        val options = VectorGlyphOutlineBuilder.parseShapingOptions(style.fontFeatureSettings, isRtl = false)

        return paint@{ canvas ->
            val shaper = Shaper.makeShapeDontWrapOrReorder()
            try {
                val restAdvance = SkiaFont(rest, sizePx).use { font ->
                    configure(font, raster)
                    shaper.shapeLine(raw, font, options).use { it.width }
                }
                SkiaFont(varied, sizePx).use { font ->
                    configure(font, raster)
                    shaper.shapeLine(raw, font, options).use { line ->
                        val glyphs = line.glyphs
                        if (glyphs.isEmpty()) return@paint
                        val positions = line.positions
                        val shift = (restAdvance - line.width) / 2f
                        val placed = Array(glyphs.size) { gi ->
                            Point(
                                shift + positions[gi * 2] + gi * letterSpacingPx,
                                baselineLocal + positions[gi * 2 + 1],
                            )
                        }
                        val blob = TextBlob.makeFromPos(glyphs, placed, font) ?: return@paint
                        SkiaPaint().use { paint ->
                            paint.isAntiAlias = true
                            paint.color = argb
                            canvas.drawTextBlob(blob, 0f, 0f, paint)
                        }
                        blob.close()
                    }
                }
            } finally {
                shaper.close()
            }
        }
    }

    /** Skia font settings matching what Compose applies to the piece's paragraph. */
    internal class Rasterization(
        val edging: FontEdging,
        val hinting: FontHinting,
        val subpixel: Boolean,
        val forceAutoHinting: Boolean,
    )

    @OptIn(ExperimentalTextApi::class)
    internal fun rasterization(style: TextStyle): Rasterization {
        val resolved = style.platformStyle?.paragraphStyle?.fontRasterizationSettings
            ?: FontRasterizationSettings.PlatformDefault
        return Rasterization(
            edging = when (resolved.smoothing) {
                FontSmoothing.None -> FontEdging.ALIAS
                FontSmoothing.AntiAlias -> FontEdging.ANTI_ALIAS
                FontSmoothing.SubpixelAntiAlias -> FontEdging.SUBPIXEL_ANTI_ALIAS
            },
            hinting = when (resolved.hinting) {
                ComposeFontHinting.None -> FontHinting.NONE
                ComposeFontHinting.Slight -> FontHinting.SLIGHT
                ComposeFontHinting.Normal -> FontHinting.NORMAL
                ComposeFontHinting.Full -> FontHinting.FULL
            },
            subpixel = resolved.subpixelPositioning,
            forceAutoHinting = resolved.autoHintingForced,
        )
    }

    internal fun configure(font: SkiaFont, raster: Rasterization) {
        font.isSubpixel = raster.subpixel
        font.hinting = raster.hinting
        font.edging = raster.edging
        font.isAutoHintingForced = raster.forceAutoHinting
    }
}
