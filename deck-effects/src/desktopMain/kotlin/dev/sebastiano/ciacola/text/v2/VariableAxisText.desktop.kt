package dev.sebastiano.ciacola.text.v2

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import dev.sebastiano.ciacola.core.fonts.DeckFontResolver
import dev.sebastiano.ciacola.document.render.LocalSpecimenFontResolverHandle
import java.text.Bidi
import org.jetbrains.skia.Canvas as SkiaCanvas
import org.jetbrains.skia.Font as SkiaFont
import org.jetbrains.skia.Paint as SkiaPaint
import org.jetbrains.skia.Point
import org.jetbrains.skia.TextBlob
import org.jetbrains.skia.Typeface as SkiaTypeface
import org.jetbrains.skia.shaper.Shaper

/**
 * Draws [text] in the deck font [fontFamilyName] at explicit variable font [axes] coordinates, for
 * specimen content that animates axes on its own clock (for example a loop).
 *
 * [perCharacterAxes] overrides [axes] tag by tag for each character, which lays out every
 * character on its own (by glyph and advance, without kerning or contextual forms). Coordinates
 * are clamped and quantized by the shared axis clone cache, so a looping animation reuses a
 * bounded set of native typefaces instead of deriving a font per frame. Text that needs bidi
 * reordering, contains surrogate pairs, or whose font has none of the requested axes falls back
 * to plain text without axes; so does content composed outside a deck with fonts.
 */
@Composable
fun VariableAxisText(
    text: String,
    fontFamilyName: String,
    fontSize: TextUnit,
    axes: Map<String, Double>,
    color: Color,
    modifier: Modifier = Modifier,
    perCharacterAxes: ((index: Int, char: Char) -> Map<String, Double>)? = null,
) {
    val resolver = LocalSpecimenFontResolverHandle.current as? DeckFontResolver
    val density = LocalDensity.current
    val sizePx = with(density) { fontSize.toPx() }
    val rest = remember(resolver, fontFamilyName) { resolver?.let { VariableAxisTextLayout.restTypeface(it, fontFamilyName) } }
    val raster = remember { AxisPiecePainter.rasterization(TextStyle.Default) }
    val layout = rest?.let { VariableAxisTextLayout.measure(text, it, sizePx, axes, raster, perCharacterAxes) }
    if (layout == null) {
        val style = TextStyle(color = color, fontSize = fontSize)
        BasicText(text = text, modifier = modifier, style = resolver?.resolveTextStyle(fontFamilyName, style) ?: style)
        return
    }
    val argb = color.toArgb()
    Canvas(modifier.size(with(density) { layout.width.toDp() }, with(density) { layout.height.toDp() })) {
        drawIntoCanvas { layout.draw(it.nativeCanvas, argb, raster) }
    }
}

/** Shaped glyph runs of one [VariableAxisText] frame, each drawn with its own axis clone. */
internal class VariableAxisTextLayout(
    val runs: List<Run>,
    val sizePx: Float,
    val width: Float,
    val ascent: Float,
    val descent: Float,
) {
    val height: Float get() = descent - ascent

    /** Glyphs drawn with [typeface], with positions relative to the run origin [x] on the baseline. */
    class Run(val typeface: SkiaTypeface, val glyphs: ShortArray, val positions: FloatArray, val x: Float)

    fun draw(canvas: SkiaCanvas, argb: Int, raster: AxisPiecePainter.Rasterization?) {
        SkiaPaint().use { paint ->
            paint.isAntiAlias = true
            paint.color = argb
            for (run in runs) {
                SkiaFont(run.typeface, sizePx).use { font ->
                    raster?.let { AxisPiecePainter.configure(font, it) }
                    val placed = Array(run.glyphs.size) { gi ->
                        Point(run.x + run.positions[gi * 2], -ascent + run.positions[gi * 2 + 1])
                    }
                    val blob = TextBlob.makeFromPos(run.glyphs, placed, font) ?: return@use
                    canvas.drawTextBlob(blob, 0f, 0f, paint)
                    blob.close()
                }
            }
        }
    }

    companion object {
        /** The upright regular face of [familyName] as a Skia typeface, or `null` when unknown. */
        fun restTypeface(resolver: DeckFontResolver, familyName: String): SkiaTypeface? {
            val family = resolver.resolveFamily(familyName) ?: return null
            val face = family.resolveFace(FontWeight.Normal, FontStyle.Normal) ?: return null
            return resolver.renderingTypeface(face)
        }

        /**
         * Shapes [text] at [axes] (with [perCharacterAxes] overrides) under [raster], or returns
         * `null` when it must fall back to plain text.
         */
        fun measure(
            text: String,
            rest: SkiaTypeface,
            sizePx: Float,
            axes: Map<String, Double>,
            raster: AxisPiecePainter.Rasterization? = null,
            perCharacterAxes: ((index: Int, char: Char) -> Map<String, Double>)? = null,
        ): VariableAxisTextLayout? {
            if (text.isEmpty() || text.any { it.isSurrogate() }) return null
            if (Bidi.requiresBidi(text.toCharArray(), 0, text.length)) return null
            val tags = rest.variationAxes?.map { it.tag }?.toSet().orEmpty()
            val requested = axes.keys + perCharacterAxes?.invoke(0, text[0])?.keys.orEmpty()
            if (requested.none { it in tags }) return null

            val options = VectorGlyphOutlineBuilder.parseShapingOptions(null, isRtl = false)
            val shaper = Shaper.makeShapeDontWrapOrReorder()
            try {
                val pieces = if (perCharacterAxes == null) {
                    listOf(text to axes)
                } else {
                    text.mapIndexed { i, c -> c.toString() to axes + perCharacterAxes(i, c) }
                }
                val runs = ArrayList<Run>(pieces.size)
                var pen = 0f
                for ((piece, coordinates) in pieces) {
                    val typeface = typefaceAt(rest, coordinates)
                    SkiaFont(typeface, sizePx).use { font ->
                        raster?.let { AxisPiecePainter.configure(font, it) }
                        if (perCharacterAxes == null) {
                            shaper.shapeLine(piece, font, options).use { line ->
                                runs += Run(typeface, line.glyphs, line.positions, pen)
                                pen += line.width
                            }
                        } else {
                            // A lone character needs no shaping, only its glyph and advance; shaping
                            // costs milliseconds per call and per-character text calls it per glyph.
                            val glyphs = font.getStringGlyphs(piece)
                            val widths = font.getWidths(glyphs)
                            val positions = FloatArray(glyphs.size * 2)
                            var x = 0f
                            for (gi in glyphs.indices) {
                                positions[gi * 2] = x
                                x += widths[gi]
                            }
                            runs += Run(typeface, glyphs, positions, pen)
                            pen += x
                        }
                    }
                }
                val metrics = SkiaFont(rest, sizePx).use { it.metrics }
                return VariableAxisTextLayout(runs, sizePx, pen, metrics.ascent, metrics.descent)
            } finally {
                shaper.close()
            }
        }

        /** [rest] cloned at absolute [coordinates], quantized and bounded by [AxisVariationCache]. */
        fun typefaceAt(rest: SkiaTypeface, coordinates: Map<String, Double>): SkiaTypeface {
            val axes = rest.variationAxes ?: return rest
            val current = rest.variations?.associate { it.tag to it.value }.orEmpty()
            val offsets = HashMap<String, Float>()
            for ((tag, value) in coordinates) {
                val axis = axes.firstOrNull { it.tag == tag } ?: continue
                offsets[tag] = value.toFloat() - (current[tag] ?: axis.defaultValue)
            }
            return AxisVariationCache.withOffsets(rest, offsets) ?: rest
        }
    }
}
