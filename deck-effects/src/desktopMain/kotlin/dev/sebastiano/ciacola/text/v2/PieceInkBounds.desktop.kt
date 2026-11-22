package dev.sebastiano.ciacola.text.v2

import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextPainter
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Paint as SkiaPaint
import org.jetbrains.skia.Rect as SkiaRect
import org.jetbrains.skia.Surface as SkiaSurface

private const val INK_BOUNDS_SAFETY_HALO_PX = 4f
private const val MAX_SCRATCH_SURFACE_PIXELS = 4_000_000L

/**
 * Empirically measures the exact non-zero alpha (`(alpha and 0xFF) != 0`) bounding box of every
 * opacity-normalized admitted piece once at partition admission time (zero per-frame cost).
 *
 * Guarantees:
 * - Any non-zero alpha pixel (`1..255`) counts as ink.
 * - If any non-zero alpha pixel touches the outermost 1 px border of the per-piece scratch surface
 *   (`minX == 0 || minY == 0 || maxX == w - 1 || maxY == h - 1`), the bounds are **refused**
 *   (`null`) so `saveLayer(null, paint)` falls back to unbounded.
 * - Measured bounds are expanded by a 4 px subpixel/AA safety halo (and further expanded by
 *   `3.5 * sigma` on blurred builds).
 * - Fails closed to `null` (unbounded `saveLayer(null, paint)`) if any piece exceeds the scratch
 *   cap or pixel readback fails.
 */
internal data class SubpixelInkBox(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val midX: Float get() = (left + right) * 0.5f
    val midY: Float get() = (top + bottom) * 0.5f
}

internal object PieceInkBounds {

    fun measureNormalizedPieces(
        normPieces: List<AdmittedPiece>,
        pad: Int,
        config: MagicTextV2Config,
    ): List<SkiaRect?> {
        if (normPieces.isEmpty()) return emptyList()
        if (config.condition2ReadbackOverrideForTest != null) {
            // Respect fail-closed readback injection in tests.
            val override = config.condition2ReadbackOverrideForTest.invoke()
            if (override == null) return List(normPieces.size) { null }
        }

        val localPad = pad.coerceIn(16, 512)
        var maxW = 1
        var maxH = 1
        for (p in normPieces) {
            val pw = p.layout.size.width.coerceAtLeast(1) + localPad * 2
            val ph = p.layout.size.height.coerceAtLeast(1) + localPad * 2
            if (pw.toLong() * ph.toLong() <= MAX_SCRATCH_SURFACE_PIXELS) {
                if (pw > maxW) maxW = pw
                if (ph > maxH) maxH = ph
            }
        }
        val scratchW = maxW.coerceAtMost(4096)
        val scratchH = maxH.coerceAtMost(2048)
        if (scratchW.toLong() * scratchH.toLong() > MAX_SCRATCH_SURFACE_PIXELS) {
            return List(normPieces.size) { null }
        }

        val surface = try {
            SkiaSurface.makeRasterN32Premul(scratchW, scratchH)
        } catch (_: Throwable) {
            return List(normPieces.size) { null }
        }
        val bmp = Bitmap()
        val result = ArrayList<SkiaRect?>(normPieces.size)
        try {
            val native = surface.canvas
            val composeCanvas = native.asComposeCanvas()
            for (p in normPieces) {
                val pw = p.layout.size.width.coerceAtLeast(1) + localPad * 2
                val ph = p.layout.size.height.coerceAtLeast(1) + localPad * 2
                if (pw > scratchW || ph > scratchH) {
                    result += null
                    continue
                }
                val clipRect = SkiaRect.makeWH(pw.toFloat(), ph.toFloat())
                native.save()
                native.clipRect(clipRect)
                native.clear(0)
                native.translate(localPad.toFloat(), localPad.toFloat())
                TextPainter.paint(composeCanvas, p.layout)
                native.restore()

                val info = ImageInfo(pw, ph, ColorType.RGBA_8888, ColorAlphaType.PREMUL)
                val rgba = try {
                    if (!bmp.allocPixels(info)) {
                        null
                    } else if (!surface.readPixels(bmp, 0, 0)) {
                        null
                    } else {
                        bmp.readPixels(info, pw * 4, 0, 0)?.takeIf { it.size == pw * ph * 4 }
                    }
                } catch (_: Throwable) {
                    null
                }
                if (rgba == null) {
                    result += null
                    continue
                }
                result += scanInkBounds(rgba, pw, ph, localPad)
            }
            return result
        } finally {
            bmp.close()
            surface.close()
        }
    }

    internal fun scanInkBounds(rgba: ByteArray, w: Int, h: Int, pad: Int): SkiaRect? {
        val rowBytes = w * 4
        var minY = -1
        outerTop@ for (y in 0 until h) {
            val rowOff = y * rowBytes
            var x = 0
            while (x < w) {
                if ((rgba[rowOff + x * 4 + 3].toInt() and 0xFF) != 0) {
                    minY = y
                    break@outerTop
                }
                x++
            }
        }
        if (minY < 0) return SkiaRect.makeWH(0f, 0f)

        var maxY = minY
        outerBot@ for (y in h - 1 downTo minY) {
            val rowOff = y * rowBytes
            var x = 0
            while (x < w) {
                if ((rgba[rowOff + x * 4 + 3].toInt() and 0xFF) != 0) {
                    maxY = y
                    break@outerBot
                }
                x++
            }
        }

        var minX = w - 1
        var maxX = 0
        for (y in minY..maxY) {
            val rowOff = y * rowBytes
            var x = 0
            while (x < minX) {
                if ((rgba[rowOff + x * 4 + 3].toInt() and 0xFF) != 0) {
                    minX = x
                    break
                }
                x++
            }
            var rx = w - 1
            while (rx > maxX) {
                if ((rgba[rowOff + rx * 4 + 3].toInt() and 0xFF) != 0) {
                    maxX = rx
                    break
                }
                rx--
            }
        }

        // Refuse bounded rect if any non-zero alpha pixel reaches the 1 px outer scratch border.
        if (minX == 0 || minY == 0 || maxX == w - 1 || maxY == h - 1) {
            return null
        }

        return SkiaRect.makeLTRB(
            (minX - pad).toFloat() - INK_BOUNDS_SAFETY_HALO_PX,
            (minY - pad).toFloat() - INK_BOUNDS_SAFETY_HALO_PX,
            (maxX - pad + 1).toFloat() + INK_BOUNDS_SAFETY_HALO_PX,
            (maxY - pad + 1).toFloat() + INK_BOUNDS_SAFETY_HALO_PX,
        )
    }

    /**
     * Measures verified non-zero-alpha ink bounds for a standalone [TextLayoutResult] (e.g. a
     * `max(fromSp, toSp)` vector size-morph layout when `styleChanged == true`), refusing to `null`
     * if ink reaches the scratch border or exceeds the scratch budget.
     */
    fun measureSingleLayoutInkBounds(layout: TextLayoutResult, pad: Int = 64): SkiaRect? {
        val localPad = pad.coerceIn(16, 512)
        val pw = layout.size.width.coerceAtLeast(1) + localPad * 2
        val ph = layout.size.height.coerceAtLeast(1) + localPad * 2
        if (pw.toLong() * ph.toLong() > MAX_SCRATCH_SURFACE_PIXELS) return null
        val surface = try {
            SkiaSurface.makeRasterN32Premul(pw, ph)
        } catch (_: Throwable) {
            return null
        }
        val bmp = Bitmap()
        return try {
            val native = surface.canvas
            native.clear(0)
            native.save()
            native.translate(localPad.toFloat(), localPad.toFloat())
            TextPainter.paint(native.asComposeCanvas(), layout)
            native.restore()
            val info = ImageInfo(pw, ph, ColorType.RGBA_8888, ColorAlphaType.PREMUL)
            if (!bmp.allocPixels(info) || !surface.readPixels(bmp, 0, 0)) return null
            val rgba = bmp.readPixels(info, pw * 4, 0, 0) ?: return null
            if (rgba.size != pw * ph * 4) return null
            scanInkBounds(rgba, pw, ph, localPad)
        } catch (_: Throwable) {
            null
        } finally {
            bmp.close()
            surface.close()
        }
    }

    /**
     * Measures the subpixel 50%-coverage ink bounding box `(left, top, right, bottom)` of [layout]
     * in layout-local coordinates at the piece's exact fractional pixel phase `(fracX, fracY)`
     * once at plan time for size-changing `Move` groups.
     */
    fun measureLayoutSubpixelInkBox(
        layout: TextLayoutResult,
        fracX: Float = 0f,
        fracY: Float = 0f,
    ): SubpixelInkBox? {
        val pad = 24
        val ox = pad.toFloat() + fracX
        val oy = pad.toFloat() + fracY
        val w = layout.size.width.coerceAtLeast(1) + pad * 2 + 2
        val h = layout.size.height.coerceAtLeast(1) + pad * 2 + 2
        if (w.toLong() * h.toLong() > MAX_SCRATCH_SURFACE_PIXELS) return null
        val surface = try {
            SkiaSurface.makeRasterN32Premul(w, h)
        } catch (_: Throwable) {
            return null
        }
        val bmp = Bitmap()
        return try {
            val native = surface.canvas
            native.clear(0)
            native.save()
            native.translate(ox, oy)
            TextPainter.paint(native.asComposeCanvas(), layout)
            native.restore()
            val info = ImageInfo(w, h, ColorType.RGBA_8888, ColorAlphaType.PREMUL)
            if (!bmp.allocPixels(info) || !surface.readPixels(bmp, 0, 0)) return null
            val rgba = bmp.readPixels(info, w * 4, 0, 0) ?: return null
            if (rgba.size != w * h * 4) return null
            computeSubpixelInkBox(rgba, w, h, ox, oy)
        } catch (_: Throwable) {
            null
        } finally {
            bmp.close()
            surface.close()
        }
    }

    /**
     * Measures the subpixel 50%-coverage ink bounding box of pure vector [runs] rendered at
     * [scale] and fractional pixel phase `(fracX, fracBaselineY)` in scaled baseline-anchored
     * coordinates (`x = 0` at run origin, `y = 0` on the alphabetic baseline).
     */
    fun measureVectorRunsSubpixelInkBox(
        runs: List<VectorGlyphRun>,
        layoutWidth: Int,
        layoutHeight: Int,
        baselinePx: Float,
        scale: Float = 1f,
        fracX: Float = 0f,
        fracBaselineY: Float = 0f,
    ): SubpixelInkBox? {
        if (runs.isEmpty()) return null
        val s = scale.coerceAtLeast(1e-3f)
        val pad = 24
        val scaledW = ceil(layoutWidth.coerceAtLeast(1) * s).toInt().coerceAtLeast(1)
        val scaledH = ceil(layoutHeight.coerceAtLeast(1) * s).toInt().coerceAtLeast(1)
        val ox = pad.toFloat() + fracX
        val oy = pad.toFloat() + floor(baselinePx * s) + fracBaselineY
        val w = scaledW + pad * 2 + 2
        val h = scaledH + pad * 2 + 2
        if (w.toLong() * h.toLong() > MAX_SCRATCH_SURFACE_PIXELS) return null
        val surface = try {
            SkiaSurface.makeRasterN32Premul(w, h)
        } catch (_: Throwable) {
            return null
        }
        val bmp = Bitmap()
        val paint = SkiaPaint().apply {
            isAntiAlias = true
            color = -1
        }
        return try {
            val native = surface.canvas
            native.clear(0)
            native.save()
            native.translate(ox, oy)
            native.scale(s, s)
            for (run in runs) {
                val glyphs = run.glyphs
                for (gi in 0 until glyphs.size) {
                    val g = glyphs[gi]
                    native.save()
                    native.translate(g.x, g.y)
                    native.drawPath(g.path, paint)
                    native.restore()
                }
                run.decorationPath?.let { native.drawPath(it, paint) }
            }
            native.restore()
            val info = ImageInfo(w, h, ColorType.RGBA_8888, ColorAlphaType.PREMUL)
            if (!bmp.allocPixels(info) || !surface.readPixels(bmp, 0, 0)) return null
            val rgba = bmp.readPixels(info, w * 4, 0, 0) ?: return null
            if (rgba.size != w * h * 4) return null
            computeSubpixelInkBox(rgba, w, h, ox, oy)
        } catch (_: Throwable) {
            null
        } finally {
            paint.close()
            bmp.close()
            surface.close()
        }
    }

    private fun computeSubpixelInkBox(
        rgba: ByteArray,
        w: Int,
        h: Int,
        originXInSurface: Float,
        originYInSurface: Float,
    ): SubpixelInkBox? {
        val rowMax = IntArray(h)
        val colMax = IntArray(w)
        var peak = 0
        for (y in 0 until h) {
            var rm = 0
            val rowOff = y * w * 4
            for (x in 0 until w) {
                val a = rgba[rowOff + x * 4 + 3].toInt() and 0xFF
                if (a > rm) rm = a
                if (a > colMax[x]) colMax[x] = a
            }
            rowMax[y] = rm
            if (rm > peak) peak = rm
        }
        if (peak < 8) return null
        val threshold = peak * 0.5
        val left = findLeadingEdge(colMax, threshold) ?: return null
        val right = findTrailingEdge(colMax, threshold) ?: return null
        val top = findLeadingEdge(rowMax, threshold) ?: return null
        val bottom = findTrailingEdge(rowMax, threshold) ?: return null
        return SubpixelInkBox(
            left = left.toFloat() - originXInSurface,
            top = top.toFloat() - originYInSurface,
            right = right.toFloat() - originXInSurface,
            bottom = bottom.toFloat() - originYInSurface,
        )
    }

    private fun findLeadingEdge(profile: IntArray, threshold: Double): Double? {
        for (i in profile.indices) {
            val cur = profile[i].toDouble()
            if (cur >= threshold) {
                if (i == 0) return 0.0
                val prev = profile[i - 1].toDouble()
                val frac = if (cur > prev) (threshold - prev) / (cur - prev) else 0.0
                return (i - 1) + frac
            }
        }
        return null
    }

    private fun findTrailingEdge(profile: IntArray, threshold: Double): Double? {
        for (i in profile.lastIndex downTo 0) {
            val cur = profile[i].toDouble()
            if (cur >= threshold) {
                if (i == profile.lastIndex) return profile.size.toDouble()
                val next = profile[i + 1].toDouble()
                val frac = if (cur > next) (cur - threshold) / (cur - next) else 0.0
                return i + frac
            }
        }
        return null
    }

    /**
     * Cancels IEEE-754 `float32` cumulative-shaping ULP drift (`~1e-5..1e-4 px`) and float32
     * addition non-associativity (`(pad + originX) + pLeft != pad + wLeft`) when a glyph sits
     * within 1 ULP of Skia's `0.25 px` horizontal subpixel cache bin edge (`k/4 + 0.125`).
     *
     * Strictly no-op when every glyph already lands in the exact same Skia `0.25 px` bin as
     * `wholeLayout` (at both `tx = 0` and `tx = pad`), or when any glyph differs by `> 0.002 px`
     * (so real kerning or shaping discrepancies are never masked).
     */
    fun refineFloat32UlpOriginX(
        baseOriginX: Float,
        wholeLayout: TextLayoutResult,
        pieceLayout: TextLayoutResult,
        candStart: Int,
        candEnd: Int,
        isMixedBidi: Boolean,
        padPx: Int = 0,
    ): Float {
        if (isMixedBidi || candEnd <= candStart) return baseOriginX
        val padF = padPx.toFloat()
        var sum = 0.0
        var count = 0
        var anyPadBinMismatch = false
        var anyZeroBinMismatch = false

        for (i in candStart until candEnd) {
            val wBox = wholeLayout.getBoundingBox(i)
            val pBox = pieceLayout.getBoundingBox(i - candStart)
            if (wBox.width <= 0f || pBox.width <= 0f) continue
            val delta = wBox.left - (baseOriginX + pBox.left)
            if (abs(delta) > 0.02f) return baseOriginX
            sum += delta.toDouble()
            count++

            val wPosPad = padF + wBox.left
            val pPosPad = (padF + baseOriginX) + pBox.left
            if (floor(wPosPad.toDouble() * 8.0) != floor(pPosPad.toDouble() * 8.0)) {
                anyPadBinMismatch = true
            }
            val wPosZero = wBox.left
            val pPosZero = baseOriginX + pBox.left
            if (floor(wPosZero.toDouble() * 8.0) != floor(pPosZero.toDouble() * 8.0)) {
                anyZeroBinMismatch = true
            }
        }
        if (count == 0 || (!anyPadBinMismatch && !anyZeroBinMismatch)) return baseOriginX

        val avg = baseOriginX.toDouble() + (sum / count)
        var minOx = baseOriginX.toDouble() - 0.02
        var maxOx = baseOriginX.toDouble() + 0.02
        val binMargin = 2e-4

        // First constrain against the Condition 2 pad translation across all 1/8-px grid intervals
        // (covers floor k, 1-bin k+0.5, 2-bin k/2+0.25, and 4-bin k/4+0.125 boundaries).
        for (i in candStart until candEnd) {
            val wBox = wholeLayout.getBoundingBox(i)
            val pBox = pieceLayout.getBoundingBox(i - candStart)
            if (wBox.width <= 0f || pBox.width <= 0f) continue
            val wPos = (padF + wBox.left).toDouble()
            val wBin8 = floor(wPos * 8.0)
            val binLo = wBin8 / 8.0
            val binHi = (wBin8 + 1.0) / 8.0
            val shift = padF.toDouble() + pBox.left.toDouble()
            minOx = max(minOx, (binLo + binMargin) - shift)
            maxOx = min(maxOx, (binHi - binMargin) - shift)
        }
        if (minOx > maxOx) {
            // Relax margin to 1e-5 if glyphs on opposite sides of the piece are tightly bounded.
            minOx = baseOriginX.toDouble() - 0.02
            maxOx = baseOriginX.toDouble() + 0.02
            for (i in candStart until candEnd) {
                val wBox = wholeLayout.getBoundingBox(i)
                val pBox = pieceLayout.getBoundingBox(i - candStart)
                if (wBox.width <= 0f || pBox.width <= 0f) continue
                val wPos = (padF + wBox.left).toDouble()
                val wBin8 = floor(wPos * 8.0)
                val binLo = wBin8 / 8.0
                val binHi = (wBin8 + 1.0) / 8.0
                val shift = padF.toDouble() + pBox.left.toDouble()
                minOx = max(minOx, (binLo + 1e-5) - shift)
                maxOx = min(maxOx, (binHi - 1e-5) - shift)
            }
        }
        val refined = if (minOx <= maxOx) avg.coerceIn(minOx, maxOx) else avg
        return refined.toFloat()
    }

    internal class SubpixelOverrideResolution(
        val overrides: FloatArray?,
        val unresolvedSample: String? = null,
    )

    /**
     * When Condition 2 finds a small number of failing pieces whose glyphs already match
     * `wholeLayout` within `0.03 px` (zero real shaping/kerning discrepancy), SkParagraph's
     * internal `float32` accumulation or FreeType fixed-point rounding can flip a glyph across
     * a Skia subpixel cache bin boundary even when `getBoundingBox().left` agrees to ~1 ULP.
     *
     * Tests tiny subpixel nudges (`<= 0.04 px`) locally against [wholePixels] and returns an
     * override array (using `Float.NaN` for untouched pieces) ONLY for pieces whose local patch
     * drops strictly to `<= tolerance` (`2/255`). The caller still verifies the full paragraph
     * bitmap under the exact `<= 2/255` Condition 2 gate.
     */
    fun resolveSubpixelBinEdgeOverrides(
        normPieces: List<AdmittedPiece>,
        wholeLayout: TextLayoutResult,
        wholePixels: ByteArray,
        bmpW: Int,
        bmpH: Int,
        pad: Int,
        diffPoints: List<Pair<Float, Float>>,
        tolerance: Int,
        density: Density,
        layoutDirection: LayoutDirection,
    ): SubpixelOverrideResolution {
        if (normPieces.isEmpty() || diffPoints.isEmpty()) return SubpixelOverrideResolution(null)
        val failingIndices = LinkedHashSet<Int>()
        for ((dx, dy) in diffPoints) {
            for (i in normPieces.indices) {
                val b = normPieces[i].boundingBox
                if (dx >= b.left - 4f && dx <= b.right + 4f && dy >= b.top - 4f && dy <= b.bottom + 4f) {
                    failingIndices += i
                }
            }
        }
        if (failingIndices.isEmpty() || failingIndices.size > 64) return SubpixelOverrideResolution(null)

        val overrides = FloatArray(normPieces.size) { Float.NaN }
        var anyResolved = false
        var unresolvedSample: String? = null
        val deltas = floatArrayOf(
            0.00015f, -0.00015f,
            0.0003f, -0.0003f,
            0.0006f, -0.0006f,
            0.0012f, -0.0012f,
            0.0025f, -0.0025f,
            0.005f, -0.005f,
            0.01f, -0.01f,
            0.02f, -0.02f,
            0.04f, -0.04f,
        )
        val bmp = Bitmap()
        try {
            for (idx in failingIndices) {
                val p = normPieces[idx]
                if (!hasMatchingGlyphPositions(p, wholeLayout)) {
                    if (unresolvedSample == null) {
                        unresolvedSample = describePiecePositions(idx, p, wholeLayout, "posMismatch")
                    }
                    continue
                }

                val b = p.boundingBox
                val x0 = (floor(b.left.toDouble()).toInt() + pad - 4).coerceIn(0, bmpW - 1)
                val y0 = (floor(b.top.toDouble()).toInt() + pad - 4).coerceIn(0, bmpH - 1)
                val x1 = (ceil(b.right.toDouble()).toInt() + pad + 4).coerceIn(x0 + 1, bmpW)
                val y1 = (ceil(b.bottom.toDouble()).toInt() + pad + 4).coerceIn(y0 + 1, bmpH)
                val cw = x1 - x0
                val ch = y1 - y0
                if (cw <= 0 || ch <= 0 || cw.toLong() * ch.toLong() > MAX_SCRATCH_SURFACE_PIXELS) continue

                // Scan strictly over piece idx's own pixel box (+1 px AA halo) so an adjacent
                // unresolved neighbor cannot mask a valid subpixel override for this piece.
                val scanX0 = ((floor(b.left.toDouble()).toInt() + pad - 1) - x0).coerceIn(0, cw - 1)
                val scanY0 = ((floor(b.top.toDouble()).toInt() + pad - 1) - y0).coerceIn(0, ch - 1)
                val scanX1 = ((ceil(b.right.toDouble()).toInt() + pad + 1) - x0).coerceIn(scanX0 + 1, cw)
                val scanY1 = ((ceil(b.bottom.toDouble()).toInt() + pad + 1) - y0).coerceIn(scanY0 + 1, ch)

                val neighbors = buildList {
                    for (otherIdx in normPieces.indices) {
                        if (otherIdx == idx) continue
                        val other = normPieces[otherIdx]
                        if (other.boundingBox.right + 8f >= b.left - 4f &&
                            other.boundingBox.left - 8f <= b.right + 4f &&
                            other.boundingBox.bottom + 8f >= b.top - 4f &&
                            other.boundingBox.top - 8f <= b.bottom + 4f
                        ) {
                            add(otherIdx to other)
                        }
                    }
                }

                val surface = try {
                    SkiaSurface.makeRasterN32Premul(cw, ch)
                } catch (_: Throwable) {
                    continue
                }
                try {
                    val info = ImageInfo(cw, ch, ColorType.RGBA_8888, ColorAlphaType.PREMUL)
                    val drawScope = CanvasDrawScope()
                    val composeCanvas = surface.canvas.asComposeCanvas()
                    val cropSize = androidx.compose.ui.geometry.Size(cw.toFloat(), ch.toFloat())

                    fun evalCrop(candidateOx: Float): Triple<Int, Int, Int> {
                        surface.canvas.clear(0)
                        drawScope.draw(density, layoutDirection, composeCanvas, cropSize) {
                            for ((nbIdx, nb) in neighbors) {
                                val nbOx = overrides[nbIdx].takeIf { !it.isNaN() } ?: nb.originX
                                withTransform({
                                    translate(pad - x0 + nbOx, pad - y0 + nb.originY)
                                }) {
                                    drawText(nb.layout)
                                }
                            }
                            withTransform({
                                translate(pad - x0 + candidateOx, pad - y0 + p.originY)
                            }) {
                                drawText(p.layout)
                            }
                        }
                        if (!bmp.allocPixels(info) || !surface.readPixels(bmp, 0, 0)) return Triple(255, -1, -1)
                        val cropRgba = bmp.readPixels(info, cw * 4, 0, 0) ?: return Triple(255, -1, -1)
                        if (cropRgba.size != cw * ch * 4) return Triple(255, -1, -1)
                        return maxCropChannelDeltaWithPos(
                            whole = wholePixels,
                            wholeW = bmpW,
                            x0 = x0,
                            y0 = y0,
                            crop = cropRgba,
                            cw = cw,
                            scanX0 = scanX0,
                            scanY0 = scanY0,
                            scanX1 = scanX1,
                            scanY1 = scanY1,
                        )
                    }

                    val (zeroDelta, zeroPx, zeroPy) = evalCrop(p.originX)
                    var minLocalDelta = zeroDelta
                    var bestDelta = 0f
                    var resolvedThis = false

                    if (zeroDelta <= tolerance) {
                        // Crop translation matched even at delta=0, which means the full-surface
                        // translation (pad + originX) crossed a bin edge due to larger float32 ULP.
                        // Nudge in the direction of the double-precision glyph drift so the full
                        // surface clears the bin edge safely too.
                        var driftSum = 0.0
                        var driftCount = 0
                        for (ci in p.start until p.end) {
                            val wb = wholeLayout.getBoundingBox(ci)
                            val pb = p.layout.getBoundingBox(ci - p.start)
                            if (wb.width <= 0f || pb.width <= 0f) continue
                            driftSum += wb.left.toDouble() - (p.originX.toDouble() + pb.left.toDouble())
                            driftCount++
                        }
                        val dir = if (driftCount > 0 && driftSum < 0.0) -1f else 1f
                        val directed = floatArrayOf(
                            dir * 0.0005f, dir * 0.0015f, -dir * 0.0005f, -dir * 0.0015f,
                        )
                        for (d in directed) {
                            val candOx = p.originX + d
                            if (evalCrop(candOx).first <= tolerance) {
                                overrides[idx] = candOx
                                anyResolved = true
                                resolvedThis = true
                                break
                            }
                        }
                    }

                    if (!resolvedThis) {
                        for (delta in deltas) {
                            val candidateOx = p.originX + delta
                            val (localDelta, _, _) = evalCrop(candidateOx)
                            if (localDelta < minLocalDelta) {
                                minLocalDelta = localDelta
                                bestDelta = delta
                            }
                            if (localDelta <= tolerance) {
                                overrides[idx] = candidateOx
                                anyResolved = true
                                resolvedThis = true
                                break
                            }
                        }
                    }

                    if (!resolvedThis && unresolvedSample == null) {
                        val maxPixX = if (zeroPx >= 0) x0 + zeroPx - pad else -1
                        val maxPixY = if (zeroPy >= 0) y0 + zeroPy - pad else -1
                        unresolvedSample = describePiecePositions(
                            idx = idx,
                            piece = p,
                            wholeLayout = wholeLayout,
                            tag = "d0=$zeroDelta,minD=$minLocalDelta@$bestDelta,pix=($maxPixX,$maxPixY)",
                        )
                    }
                } finally {
                    surface.close()
                }
            }
        } finally {
            bmp.close()
        }
        return SubpixelOverrideResolution(
            overrides = if (anyResolved) overrides else null,
            unresolvedSample = unresolvedSample,
        )
    }

    private fun hasMatchingGlyphPositions(piece: AdmittedPiece, wholeLayout: TextLayoutResult): Boolean {
        if (piece.end <= piece.start) return false
        var anyNonEmpty = false
        for (i in piece.start until piece.end) {
            val wBox = wholeLayout.getBoundingBox(i)
            val pBox = piece.layout.getBoundingBox(i - piece.start)
            if (wBox.width <= 0f || pBox.width <= 0f) continue
            if (abs(wBox.left - (piece.originX + pBox.left)) > 0.03f) return false
            anyNonEmpty = true
        }
        return anyNonEmpty
    }

    private fun describePiecePositions(
        idx: Int,
        piece: AdmittedPiece,
        wholeLayout: TextLayoutResult,
        tag: String,
    ): String {
        val chars = buildString {
            val limit = min(piece.end, piece.start + 8)
            for (ci in piece.start until limit) {
                if (ci > piece.start) append(',')
                val wb = wholeLayout.getBoundingBox(ci)
                val pb = piece.layout.getBoundingBox(ci - piece.start)
                val d = wb.left - (piece.originX + pb.left)
                append("${wb.left}/${d}")
            }
        }
        return "p$idx(l=${piece.line},ox=${piece.originX},oy=${piece.originY},$tag,g=[$chars])"
    }

    private fun maxCropChannelDeltaWithPos(
        whole: ByteArray,
        wholeW: Int,
        x0: Int,
        y0: Int,
        crop: ByteArray,
        cw: Int,
        scanX0: Int,
        scanY0: Int,
        scanX1: Int,
        scanY1: Int,
    ): Triple<Int, Int, Int> {
        var maxDelta = 0
        var maxX = -1
        var maxY = -1
        for (y in scanY0 until scanY1) {
            val wRow = (y0 + y) * wholeW * 4 + x0 * 4
            val cRow = y * cw * 4
            for (x in scanX0 until scanX1) {
                val wb = wRow + x * 4
                val cb = cRow + x * 4
                val d0 = abs((whole[wb].toInt() and 0xFF) - (crop[cb].toInt() and 0xFF))
                val d1 = abs((whole[wb + 1].toInt() and 0xFF) - (crop[cb + 1].toInt() and 0xFF))
                val d2 = abs((whole[wb + 2].toInt() and 0xFF) - (crop[cb + 2].toInt() and 0xFF))
                val d3 = abs((whole[wb + 3].toInt() and 0xFF) - (crop[cb + 3].toInt() and 0xFF))
                val pd = max(max(d0, d1), max(d2, d3))
                if (pd > maxDelta) {
                    maxDelta = pd
                    maxX = x
                    maxY = y
                }
            }
        }
        return Triple(maxDelta, maxX, maxY)
    }

    /**
     * Returns `true` iff every piece has non-null empirically measured [AdmittedPiece.verifiedInkBounds]
     * and the actual ink boxes (verified bounds minus 3 px of the 4 px safety halo, preserving a
     * >= 1 px empty-alpha guard) are strictly pairwise disjoint in paragraph coordinates.
     *
     * When `true`, coalescing consecutive pieces with the exact same quantized alpha byte inside
     * one `saveLayer(unionBounds, paint)` is mathematically bit-for-bit identical to per-piece
     * layers while collapsing hundreds of GPU render passes into one.
     */
    fun areAllPiecesPairwiseDisjoint(pieces: List<AdmittedPiece>): Boolean {
        val n = pieces.size
        if (n <= 1 || n > 1024) return false
        val trim = INK_BOUNDS_SAFETY_HALO_PX - 1f
        val lefts = FloatArray(n)
        val tops = FloatArray(n)
        val rights = FloatArray(n)
        val bottoms = FloatArray(n)
        for (i in 0 until n) {
            val p = pieces[i]
            val b = p.verifiedInkBounds ?: return false
            if (b.isEmpty) {
                lefts[i] = 0f
                tops[i] = 0f
                rights[i] = 0f
                bottoms[i] = 0f
            } else {
                lefts[i] = p.originX + b.left + trim
                tops[i] = p.originY + b.top + trim
                rights[i] = p.originX + b.right - trim
                bottoms[i] = p.originY + b.bottom - trim
            }
        }
        for (i in 0 until n) {
            if (rights[i] <= lefts[i] || bottoms[i] <= tops[i]) continue
            val l1 = lefts[i]
            val t1 = tops[i]
            val r1 = rights[i]
            val b1 = bottoms[i]
            for (j in i + 1 until n) {
                if (rights[j] <= lefts[j] || bottoms[j] <= tops[j]) continue
                if (l1 < rights[j] && r1 > lefts[j] && t1 < bottoms[j] && b1 > tops[j]) {
                    return false
                }
            }
        }
        return true
    }

    /** Union of non-null rects shifted by per-rect `(dx, dy)`; returns `null` if any rect is `null`. */
    fun unionShifted(rects: List<Triple<SkiaRect?, Float, Float>>): SkiaRect? {
        if (rects.isEmpty()) return null
        var left = Float.POSITIVE_INFINITY
        var top = Float.POSITIVE_INFINITY
        var right = Float.NEGATIVE_INFINITY
        var bottom = Float.NEGATIVE_INFINITY
        var anyNonEmpty = false
        for ((r, dx, dy) in rects) {
            if (r == null) return null
            if (r.isEmpty) continue
            anyNonEmpty = true
            val l = r.left + dx
            val t = r.top + dy
            val ri = r.right + dx
            val b = r.bottom + dy
            if (l < left) left = l
            if (t < top) top = t
            if (ri > right) right = ri
            if (b > bottom) bottom = b
        }
        return if (anyNonEmpty) SkiaRect.makeLTRB(left, top, right, bottom) else SkiaRect.makeWH(0f, 0f)
    }

    /** Expands [bounds] by the 3.5-sigma Gaussian blur support plus 4 px safety margin. */
    fun expandForBlur(bounds: SkiaRect?, blurRadiusPx: Float): SkiaRect? {
        if (bounds == null) return null
        if (blurRadiusPx <= 0.05f) return bounds
        val sigma = blurRadiusPx * 0.57735f
        val extra = ceil(3.5f * sigma) + INK_BOUNDS_SAFETY_HALO_PX
        return SkiaRect.makeLTRB(
            bounds.left - extra,
            bounds.top - extra,
            bounds.right + extra,
            bounds.bottom + extra,
        )
    }
}
