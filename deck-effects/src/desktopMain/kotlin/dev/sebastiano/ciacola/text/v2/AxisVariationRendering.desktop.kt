package dev.sebastiano.ciacola.text.v2

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.isUnspecified
import dev.sebastiano.ciacola.core.fonts.DeckFontResolver
import kotlin.math.roundToInt
import org.jetbrains.skia.Canvas as SkiaCanvas
import org.jetbrains.skia.Font as SkiaFont
import org.jetbrains.skia.FontEdging
import org.jetbrains.skia.FontHinting
import org.jetbrains.skia.FontVariation as SkiaFontVariation
import org.jetbrains.skia.Paint as SkiaPaint
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

    /** A paint routine for [piece] at [offsets], or `null` when the piece cannot (or need not) vary. */
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
        val varied = AxisVariationCache.withOffsets(rest, offsets) ?: return null

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
                    configure(font)
                    shaper.shapeLine(raw, font, options).use { it.width }
                }
                SkiaFont(varied, sizePx).use { font ->
                    configure(font)
                    shaper.shapeLine(raw, font, options).use { line ->
                        val glyphs = line.glyphs
                        if (glyphs.isEmpty()) return@paint
                        val positions = line.positions
                        val shift = (restAdvance - line.width) / 2f
                        val placed = FloatArray(glyphs.size * 2)
                        for (gi in glyphs.indices) {
                            placed[gi * 2] = shift + positions[gi * 2] + gi * letterSpacingPx
                            placed[gi * 2 + 1] = baselineLocal + positions[gi * 2 + 1]
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

    private fun configure(font: SkiaFont) {
        font.isSubpixel = true
        font.isLinearMetrics = true
        font.hinting = FontHinting.NONE
        font.edging = FontEdging.SUBPIXEL_ANTI_ALIAS
    }
}
