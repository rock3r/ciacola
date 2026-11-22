package dev.sebastiano.ciacola.text.v2

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import dev.sebastiano.ciacola.core.fonts.BundledFontCatalog
import dev.sebastiano.ciacola.core.fonts.ClasspathFontResourceLoader
import dev.sebastiano.ciacola.core.fonts.DeckFontDeclaration
import dev.sebastiano.ciacola.core.fonts.DeckFontResolver
import dev.sebastiano.ciacola.core.fonts.SystemFontLookup
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image as SkiaImage
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Surface

internal object MagicTextV2TestSupport {
    @Volatile
    private var composeRegistered = false

    @OptIn(ExperimentalComposeUiApi::class)
    fun ensureComposeRegistered() {
        if (composeRegistered) return
        val scene = ImageComposeScene(width = 1, height = 1, density = Density(1f)) {}
        try {
            scene.render(0L)
            composeRegistered = true
        } finally {
            scene.close()
        }
    }

    fun bundledFontsDir(): File {
        val prop = System.getProperty("ciacola.fonts.dir")
        if (!prop.isNullOrBlank()) {
            val f = File(prop)
            if (f.isDirectory) return f
        }
        return File("deck/src/desktopMain/resources/fonts").also {
            require(it.isDirectory) { "Bundled fonts dir not found at ${it.absolutePath}" }
        }
    }

    fun createBundledFontResolver(): DeckFontResolver {
        val dir = bundledFontsDir()
        val loader = ClasspathFontResourceLoader(fallbackDirectories = listOf(dir))
        val declarations = listOf(
            DeckFontDeclaration(family = "Inter", src = "Inter-Regular.ttf", weight = 400, italic = false),
            DeckFontDeclaration(family = "Inter", src = "Inter-Medium.ttf", weight = 500, italic = false),
            DeckFontDeclaration(family = "Inter", src = "Inter-SemiBold.ttf", weight = 600, italic = false),
            DeckFontDeclaration(family = "Inter", src = "Inter-Bold.ttf", weight = 700, italic = false),
            DeckFontDeclaration(family = "JetBrainsMono", src = "JetBrainsMono-Regular.ttf", weight = 400, italic = false),
            DeckFontDeclaration(family = "JetBrainsMono", src = "JetBrainsMono-Bold.ttf", weight = 700, italic = false),
            DeckFontDeclaration(family = "JetBrainsMono", src = "JetBrainsMono-Italic.ttf", weight = 400, italic = true),
            DeckFontDeclaration(family = "JetBrainsMono", src = "JetBrainsMono-BoldItalic.ttf", weight = 700, italic = true),
            DeckFontDeclaration(family = "HankenGrotesk", src = "HankenGrotesk-VariableFont_wght.ttf", variable = true, italic = false),
            DeckFontDeclaration(family = "HankenGrotesk", src = "HankenGrotesk-Italic-VariableFont_wght.ttf", variable = true, italic = true),
            DeckFontDeclaration(family = "NotoEmoji", src = "NotoEmoji-VariableFont_wght.ttf", variable = true, role = DeckFontDeclaration.ROLE_EMOJI),
        )
        return DeckFontResolver(
            declarations = declarations,
            resourceLoader = loader,
            systemFontLookup = SystemFontLookup { null },
        )
    }

    fun createAllBundledFontResolver(): DeckFontResolver {
        val dir = bundledFontsDir()
        val loader = ClasspathFontResourceLoader(fallbackDirectories = listOf(dir))
        return DeckFontResolver(
            declarations = BundledFontCatalog.AllBundled,
            resourceLoader = loader,
            systemFontLookup = SystemFontLookup { null },
        )
    }

    fun createMeasurer(
        density: Density = Density(2f),
        layoutDirection: LayoutDirection = LayoutDirection.Ltr,
    ): TextMeasurer {
        ensureComposeRegistered()
        return TextMeasurer(
            defaultFontFamilyResolver = createFontFamilyResolver(),
            defaultDensity = density,
            defaultLayoutDirection = layoutDirection,
            cacheSize = 256,
        )
    }

    fun renderCanvasToRgba(
        w: Int,
        h: Int,
        density: Density = Density(2f),
        layoutDirection: LayoutDirection = LayoutDirection.Ltr,
        clearColorArgb: Int = 0,
        block: DrawScope.() -> Unit,
    ): ByteArray {
        val surface = Surface.makeRasterN32Premul(w, h)
        try {
            surface.canvas.clear(clearColorArgb)
            val scope = CanvasDrawScope()
            scope.draw(
                density = density,
                layoutDirection = layoutDirection,
                canvas = surface.canvas.asComposeCanvas(),
                size = androidx.compose.ui.geometry.Size(w.toFloat(), h.toFloat()),
                block = block,
            )
            return readSurfaceRgba(surface, w, h)
        } finally {
            surface.close()
        }
    }

    fun readSurfaceRgba(surface: Surface, w: Int, h: Int): ByteArray {
        val info = ImageInfo(w, h, ColorType.RGBA_8888, ColorAlphaType.PREMUL)
        val bmp = Bitmap()
        try {
            bmp.allocPixels(info)
            surface.readPixels(bmp, 0, 0)
            return bmp.readPixels(info, w * 4, 0, 0) ?: ByteArray(w * h * 4)
        } finally {
            bmp.close()
        }
    }

    fun readImageRgba(image: SkiaImage): ByteArray {
        val w = image.width
        val h = image.height
        val info = ImageInfo(w, h, ColorType.RGBA_8888, ColorAlphaType.PREMUL)
        val bmp = Bitmap()
        try {
            bmp.allocPixels(info)
            image.readPixels(bmp, 0, 0)
            return bmp.readPixels(info, w * 4, 0, 0) ?: ByteArray(w * h * 4)
        } finally {
            bmp.close()
        }
    }

    data class DiffStats(
        val maxDelta: Int,
        val differingPixelsOverTol: Int,
    )

    fun compareRgba(a: ByteArray, b: ByteArray, tolerance: Int = 2): DiffStats {
        require(a.size == b.size) { "Size mismatch: ${a.size} vs ${b.size}" }
        var maxD = 0
        var overTol = 0
        var i = 0
        while (i < a.size) {
            val d0 = abs((a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF))
            val d1 = abs((a[i + 1].toInt() and 0xFF) - (b[i + 1].toInt() and 0xFF))
            val d2 = abs((a[i + 2].toInt() and 0xFF) - (b[i + 2].toInt() and 0xFF))
            val d3 = abs((a[i + 3].toInt() and 0xFF) - (b[i + 3].toInt() and 0xFF))
            val pd = max(max(d0, d1), max(d2, d3))
            if (pd > maxD) maxD = pd
            if (pd > tolerance) overTol++
            i += 4
        }
        return DiffStats(maxD, overTol)
    }

    fun countColoredNonGrayscalePixels(rgba: ByteArray, threshold: Int = 8): Int {
        var count = 0
        var i = 0
        while (i < rgba.size) {
            val a = rgba[i + 3].toInt() and 0xFF
            if (a > 12) {
                val r = rgba[i].toInt() and 0xFF
                val g = rgba[i + 1].toInt() and 0xFF
                val b = rgba[i + 2].toInt() and 0xFF
                if (max(max(abs(r - g), abs(g - b)), abs(r - b)) > threshold) {
                    count++
                }
            }
            i += 4
        }
        return count
    }

    fun countInkPixels(rgba: ByteArray, alphaThreshold: Int = 8): Int {
        var count = 0
        var i = 3
        while (i < rgba.size) {
            if ((rgba[i].toInt() and 0xFF) > alphaThreshold) count++
            i += 4
        }
        return count
    }

    /**
     * Subpixel glyph-edge measurement: computes fractional
     * 50%-crossing left, right, top, and bottom ink edges plus peak edge gradient from alpha rows/columns.
     */
    data class SubpixelInkEdges(
        val left: Double,
        val right: Double,
        val top: Double,
        val bottom: Double,
        val peakGradient: Double,
    )

    fun measureSubpixelEdges(rgba: ByteArray, w: Int, h: Int, regionTop: Int = 0, regionBottom: Int = h): SubpixelInkEdges? {
        val y0 = regionTop.coerceIn(0, h)
        val y1 = regionBottom.coerceIn(y0, h)
        val colMax = DoubleArray(w)
        val rowMax = DoubleArray(y1 - y0)
        var maxGrad = 0.0

        for (y in y0 until y1) {
            val rowOff = y * w * 4
            var rMax = 0.0
            for (x in 0 until w) {
                val a = (rgba[rowOff + x * 4 + 3].toInt() and 0xFF) / 255.0
                if (a > rMax) rMax = a
                if (a > colMax[x]) colMax[x] = a
                if (x > 0) {
                    val prevA = (rgba[rowOff + (x - 1) * 4 + 3].toInt() and 0xFF) / 255.0
                    val g = abs(a - prevA)
                    if (g > maxGrad) maxGrad = g
                }
                if (y > y0) {
                    val upA = (rgba[(y - 1) * w * 4 + x * 4 + 3].toInt() and 0xFF) / 255.0
                    val g = abs(a - upA)
                    if (g > maxGrad) maxGrad = g
                }
            }
            rowMax[y - y0] = rMax
        }

        val left = findRisingCrossing(colMax) ?: return null
        val right = findFallingCrossing(colMax) ?: return null
        val topRel = findRisingCrossing(rowMax) ?: return null
        val bottomRel = findFallingCrossing(rowMax) ?: return null
        return SubpixelInkEdges(
            left = left,
            right = right,
            top = y0 + topRel,
            bottom = y0 + bottomRel,
            peakGradient = maxGrad,
        )
    }

    private fun findRisingCrossing(profile: DoubleArray, level: Double = 0.5): Double? {
        for (i in 1 until profile.size) {
            if (profile[i] >= level) {
                val a = profile[i - 1]
                val b = profile[i]
                val denom = b - a
                return if (denom > 1e-6) (i - 1) + (level - a) / denom else i.toDouble()
            }
        }
        return null
    }

    private fun findFallingCrossing(profile: DoubleArray, level: Double = 0.5): Double? {
        for (i in profile.size - 2 downTo 0) {
            if (profile[i] >= level) {
                val a = profile[i]
                val b = profile[i + 1]
                val denom = a - b
                return if (denom > 1e-6) i + (a - level) / denom else i.toDouble()
            }
        }
        return null
    }
}

/**
 * Skia-loadable SFNT repacker and OpenType GSUB/cmap binary fixture builder for
 * end-to-end renderer safety tests (repacking real bundled `Inter-Regular.ttf` so every synthetic
 * fixture loads and renders through Skia + `DeckFontResolver` + `MagicTextV2Renderer`).
 */
internal object SyntheticTtfBuilder {

    data class SeqLookupRec(val sequenceIndex: Int, val lookupListIndex: Int)

    data class LookupSpec(
        val type: Int,
        val lookupFlag: Int = 0,
        val rawSubtables: List<ByteArray> = emptyList(),
        val extensionInnerType: Int? = null,
        val extensionInnerSubtable: ByteArray? = null,
    )

    data class FeatureSpec(
        val tag: String,
        val lookupIndices: List<Int>,
    )

    data class VariationSub(
        val featureIndex: Int,
        val alternateLookups: List<Int>,
    )

    val baseInterBytes: ByteArray by lazy {
        File(MagicTextV2TestSupport.bundledFontsDir(), "Inter-Regular.ttf").readBytes()
    }

    fun repackBaseInter(
        lookups: List<LookupSpec> = emptyList(),
        features: List<FeatureSpec> = emptyList(),
        requiredFeatureIndex: Int? = null,
        featureVariations: List<List<VariationSub>> = emptyList(),
        extraTables: Map<String, ByteArray> = emptyMap(),
        removeTables: Set<String> = emptySet(),
        rawGsubOverride: ByteArray? = null,
        rawCmapOverride: ByteArray? = null,
    ): ByteArray {
        val replacements = linkedMapOf<String, ByteArray>()
        if (rawGsubOverride != null) {
            replacements["GSUB"] = rawGsubOverride
        } else if (lookups.isNotEmpty() || features.isNotEmpty() || featureVariations.isNotEmpty()) {
            replacements["GSUB"] = buildGsub(lookups, features, requiredFeatureIndex, featureVariations)
        }
        if (rawCmapOverride != null) {
            replacements["cmap"] = rawCmapOverride
        }
        replacements.putAll(extraTables)
        return repackSfnt(baseInterBytes, replacements = replacements, removals = removeTables)
    }

    fun repackSfnt(
        baseBytes: ByteArray,
        replacements: Map<String, ByteArray> = emptyMap(),
        removals: Set<String> = emptySet(),
    ): ByteArray {
        val buf = ByteBuffer.wrap(baseBytes).order(ByteOrder.BIG_ENDIAN)
        val sfntVersion = buf.getInt(0)
        val numTables = buf.getShort(4).toInt() and 0xFFFF
        val tables = linkedMapOf<String, ByteArray>()
        for (i in 0 until numTables) {
            val pos = 12 + i * 16
            val tag = String(baseBytes, pos, 4, Charsets.ISO_8859_1)
            val offset = buf.getInt(pos + 8)
            val length = buf.getInt(pos + 12)
            if (tag !in removals) {
                tables[tag] = baseBytes.copyOfRange(offset, offset + length)
            }
        }
        for ((k, v) in replacements) {
            tables[k] = v
        }
        return assembleSfnt(sfntVersion, tables)
    }

    private fun assembleSfnt(sfntVersion: Int, tables: Map<String, ByteArray>): ByteArray {
        val sorted = tables.entries.sortedBy { it.key }
        val numTables = sorted.size
        val headerSize = 12 + numTables * 16
        val body = ByteArrayOutputStream()
        val offsets = IntArray(numTables)
        val lengths = IntArray(numTables)
        val checksums = IntArray(numTables)
        var offset = headerSize
        for ((idx, entry) in sorted.withIndex()) {
            val data = if (entry.key == "head" && entry.value.size >= 12) {
                entry.value.copyOf().also {
                    it[8] = 0; it[9] = 0; it[10] = 0; it[11] = 0
                }
            } else {
                entry.value
            }
            offsets[idx] = offset
            lengths[idx] = data.size
            checksums[idx] = tableChecksum(data)
            body.write(data)
            val pad = (4 - (data.size % 4)) % 4
            repeat(pad) { body.write(0) }
            offset += data.size + pad
        }
        var entrySelector = 0
        var searchRange = 16
        while (searchRange * 2 <= numTables * 16) {
            searchRange *= 2
            entrySelector++
        }
        val rangeShift = (numTables * 16 - searchRange).coerceAtLeast(0)
        val out = ByteBuffer.allocate(headerSize + body.size()).order(ByteOrder.BIG_ENDIAN)
        out.putInt(sfntVersion)
        out.putShort(numTables.toShort())
        out.putShort(searchRange.toShort())
        out.putShort(entrySelector.toShort())
        out.putShort(rangeShift.toShort())
        var headOffset = -1
        for ((idx, entry) in sorted.withIndex()) {
            if (entry.key == "head") headOffset = offsets[idx]
            out.put(entry.key.toByteArray(Charsets.ISO_8859_1))
            out.putInt(checksums[idx])
            out.putInt(offsets[idx])
            out.putInt(lengths[idx])
        }
        out.put(body.toByteArray())
        val result = out.array()
        if (headOffset >= 0 && headOffset + 12 <= result.size) {
            val wholeChecksum = tableChecksum(result)
            val adjustment = (0xB1B0AFBAL - (wholeChecksum.toLong() and 0xFFFFFFFFL)).toInt()
            ByteBuffer.wrap(result).order(ByteOrder.BIG_ENDIAN).putInt(headOffset + 8, adjustment)
        }
        return result
    }

    private fun tableChecksum(bytes: ByteArray): Int {
        var sum = 0L
        var i = 0
        while (i < bytes.size) {
            val b0 = (bytes[i].toInt() and 0xFF).toLong() shl 24
            val b1 = if (i + 1 < bytes.size) (bytes[i + 1].toInt() and 0xFF).toLong() shl 16 else 0L
            val b2 = if (i + 2 < bytes.size) (bytes[i + 2].toInt() and 0xFF).toLong() shl 8 else 0L
            val b3 = if (i + 3 < bytes.size) (bytes[i + 3].toInt() and 0xFF).toLong() else 0L
            sum = (sum + (b0 or b1 or b2 or b3)) and 0xFFFFFFFFL
            i += 4
        }
        return sum.toInt()
    }

    fun buildCoverageFormat1(glyphs: List<Int>): ByteArray =
        ByteBuffer.allocate(4 + glyphs.size * 2).order(ByteOrder.BIG_ENDIAN).apply {
            putShort(1); putShort(glyphs.size.toShort())
            for (g in glyphs) putShort(g.toShort())
        }.array()

    fun buildClassDefFormat1(startGlyph: Int, classValues: List<Int>): ByteArray =
        ByteBuffer.allocate(6 + classValues.size * 2).order(ByteOrder.BIG_ENDIAN).apply {
            putShort(1); putShort(startGlyph.toShort()); putShort(classValues.size.toShort())
            for (c in classValues) putShort(c.toShort())
        }.array()

    fun buildSingleFormat2Subtable(pairs: List<Pair<Int, Int>>): ByteArray {
        val sorted = pairs.sortedBy { it.first }
        val cov = buildCoverageFormat1(sorted.map { it.first })
        val header = 6 + sorted.size * 2
        return ByteBuffer.allocate(header + cov.size).order(ByteOrder.BIG_ENDIAN).apply {
            putShort(2); putShort(header.toShort()); putShort(sorted.size.toShort())
            for ((_, out) in sorted) putShort(out.toShort())
            put(cov)
        }.array()
    }

    fun buildMultipleFormat1Subtable(entries: List<Pair<Int, List<Int>>>): ByteArray {
        val sorted = entries.sortedBy { it.first }
        val cov = buildCoverageFormat1(sorted.map { it.first })
        val header = 6 + sorted.size * 2
        val body = ByteArrayOutputStream()
        body.write(cov)
        var cur = header + cov.size
        val seqOffsets = IntArray(sorted.size)
        for ((i, pair) in sorted.withIndex()) {
            seqOffsets[i] = cur
            val seqBuf = ByteBuffer.allocate(2 + pair.second.size * 2).order(ByteOrder.BIG_ENDIAN).apply {
                putShort(pair.second.size.toShort())
                for (g in pair.second) putShort(g.toShort())
            }.array()
            body.write(seqBuf)
            cur += seqBuf.size
        }
        return ByteBuffer.allocate(header + body.size()).order(ByteOrder.BIG_ENDIAN).apply {
            putShort(1); putShort(header.toShort()); putShort(sorted.size.toShort())
            for (o in seqOffsets) putShort(o.toShort())
            put(body.toByteArray())
        }.array()
    }

    fun buildLigatureFormat1Subtable(entries: List<Pair<List<Int>, Int>>): ByteArray {
        val grouped = entries.groupBy { it.first.first() }.toSortedMap()
        val firstGlyphs = grouped.keys.toList()
        val covBytes = buildCoverageFormat1(firstGlyphs)
        val ligSetBytes = grouped.values.map { ligs ->
            val ligTables = ligs.map { (comps, outGlyph) ->
                ByteBuffer.allocate(4 + (comps.size - 1) * 2).order(ByteOrder.BIG_ENDIAN).apply {
                    putShort(outGlyph.toShort()); putShort(comps.size.toShort())
                    for (i in 1 until comps.size) putShort(comps[i].toShort())
                }.array()
            }
            val lsHeader = 2 + ligTables.size * 2
            var off = lsHeader
            val lsOffsets = IntArray(ligTables.size)
            val lsBody = ByteArrayOutputStream()
            for ((i, bytes) in ligTables.withIndex()) {
                lsOffsets[i] = off; lsBody.write(bytes); off += bytes.size
            }
            ByteBuffer.allocate(lsHeader + lsBody.size()).order(ByteOrder.BIG_ENDIAN).apply {
                putShort(ligTables.size.toShort())
                for (o in lsOffsets) putShort(o.toShort())
                put(lsBody.toByteArray())
            }.array()
        }
        val header = 6 + firstGlyphs.size * 2
        var cur = header + covBytes.size
        val lsOffsets = IntArray(ligSetBytes.size)
        val body = ByteArrayOutputStream()
        body.write(covBytes)
        for ((i, bytes) in ligSetBytes.withIndex()) {
            lsOffsets[i] = cur; body.write(bytes); cur += bytes.size
        }
        return ByteBuffer.allocate(header + body.size()).order(ByteOrder.BIG_ENDIAN).apply {
            putShort(1); putShort(header.toShort()); putShort(firstGlyphs.size.toShort())
            for (o in lsOffsets) putShort(o.toShort())
            put(body.toByteArray())
        }.array()
    }

    fun buildContextFormat1Subtable(
        firstGlyph: Int,
        remainingGlyphs: List<Int>,
        seqLookups: List<SeqLookupRec> = emptyList(),
    ): ByteArray {
        val cov = buildCoverageFormat1(listOf(firstGlyph))
        val rule = ByteBuffer.allocate(4 + remainingGlyphs.size * 2 + seqLookups.size * 4).order(ByteOrder.BIG_ENDIAN).apply {
            putShort((1 + remainingGlyphs.size).toShort())
            putShort(seqLookups.size.toShort())
            for (g in remainingGlyphs) putShort(g.toShort())
            for (rec in seqLookups) { putShort(rec.sequenceIndex.toShort()); putShort(rec.lookupListIndex.toShort()) }
        }.array()
        val ruleSet = ByteBuffer.allocate(4 + rule.size).order(ByteOrder.BIG_ENDIAN).apply {
            putShort(1); putShort(4); put(rule)
        }.array()
        val header = 8
        return ByteBuffer.allocate(header + cov.size + ruleSet.size).order(ByteOrder.BIG_ENDIAN).apply {
            putShort(1); putShort(header.toShort()); putShort(1); putShort((header + cov.size).toShort())
            put(cov); put(ruleSet)
        }.array()
    }

    fun buildContextFormat2Subtable(
        coverageBytes: ByteArray,
        classDefBytes: ByteArray,
        firstClass: Int,
        remainingClasses: List<Int>,
        seqLookups: List<SeqLookupRec> = emptyList(),
    ): ByteArray {
        val classSetCount = firstClass + 1
        val rule = ByteBuffer.allocate(4 + remainingClasses.size * 2 + seqLookups.size * 4).order(ByteOrder.BIG_ENDIAN).apply {
            putShort((1 + remainingClasses.size).toShort())
            putShort(seqLookups.size.toShort())
            for (c in remainingClasses) putShort(c.toShort())
            for (rec in seqLookups) { putShort(rec.sequenceIndex.toShort()); putShort(rec.lookupListIndex.toShort()) }
        }.array()
        val classSet = ByteBuffer.allocate(4 + rule.size).order(ByteOrder.BIG_ENDIAN).apply {
            putShort(1); putShort(4); put(rule)
        }.array()
        val header = 8 + classSetCount * 2
        val covOff = header
        val clsOff = covOff + coverageBytes.size
        val csOff = clsOff + classDefBytes.size
        return ByteBuffer.allocate(csOff + classSet.size).order(ByteOrder.BIG_ENDIAN).apply {
            putShort(2); putShort(covOff.toShort()); putShort(clsOff.toShort()); putShort(classSetCount.toShort())
            for (i in 0 until classSetCount) putShort((if (i == firstClass) csOff else 0).toShort())
            put(coverageBytes); put(classDefBytes); put(classSet)
        }.array()
    }

    fun buildContextFormat3Subtable(coverages: List<ByteArray>, seqLookups: List<SeqLookupRec> = emptyList()): ByteArray {
        val header = 6 + coverages.size * 2 + seqLookups.size * 4
        val body = ByteArrayOutputStream()
        val offsets = IntArray(coverages.size)
        var cur = header
        for ((i, c) in coverages.withIndex()) { offsets[i] = cur; body.write(c); cur += c.size }
        return ByteBuffer.allocate(header + body.size()).order(ByteOrder.BIG_ENDIAN).apply {
            putShort(3); putShort(coverages.size.toShort()); putShort(seqLookups.size.toShort())
            for (o in offsets) putShort(o.toShort())
            for (rec in seqLookups) { putShort(rec.sequenceIndex.toShort()); putShort(rec.lookupListIndex.toShort()) }
            put(body.toByteArray())
        }.array()
    }

    fun buildChainedFormat1Subtable(
        backtrackGlyphs: List<Int>,
        firstInputGlyph: Int,
        remainingInputGlyphs: List<Int>,
        lookaheadGlyphs: List<Int>,
        seqLookups: List<SeqLookupRec> = emptyList(),
    ): ByteArray {
        val cov = buildCoverageFormat1(listOf(firstInputGlyph))
        val ruleLen = 2 + backtrackGlyphs.size * 2 + 2 + remainingInputGlyphs.size * 2 + 2 + lookaheadGlyphs.size * 2 + 2 + seqLookups.size * 4
        val rule = ByteBuffer.allocate(ruleLen).order(ByteOrder.BIG_ENDIAN).apply {
            putShort(backtrackGlyphs.size.toShort()); for (g in backtrackGlyphs) putShort(g.toShort())
            putShort((1 + remainingInputGlyphs.size).toShort()); for (g in remainingInputGlyphs) putShort(g.toShort())
            putShort(lookaheadGlyphs.size.toShort()); for (g in lookaheadGlyphs) putShort(g.toShort())
            putShort(seqLookups.size.toShort())
            for (rec in seqLookups) { putShort(rec.sequenceIndex.toShort()); putShort(rec.lookupListIndex.toShort()) }
        }.array()
        val ruleSet = ByteBuffer.allocate(4 + rule.size).order(ByteOrder.BIG_ENDIAN).apply {
            putShort(1); putShort(4); put(rule)
        }.array()
        val header = 8
        return ByteBuffer.allocate(header + cov.size + ruleSet.size).order(ByteOrder.BIG_ENDIAN).apply {
            putShort(1); putShort(header.toShort()); putShort(1); putShort((header + cov.size).toShort())
            put(cov); put(ruleSet)
        }.array()
    }

    fun buildChainedFormat2Subtable(
        coverageBytes: ByteArray,
        backClassDefBytes: ByteArray,
        inputClassDefBytes: ByteArray,
        lookClassDefBytes: ByteArray,
        backtrackClasses: List<Int>,
        firstInputClass: Int,
        remainingInputClasses: List<Int>,
        lookaheadClasses: List<Int>,
        seqLookups: List<SeqLookupRec> = emptyList(),
    ): ByteArray {
        val classSetCount = firstInputClass + 1
        val ruleLen = 2 + backtrackClasses.size * 2 + 2 + remainingInputClasses.size * 2 + 2 + lookaheadClasses.size * 2 + 2 + seqLookups.size * 4
        val rule = ByteBuffer.allocate(ruleLen).order(ByteOrder.BIG_ENDIAN).apply {
            putShort(backtrackClasses.size.toShort()); for (c in backtrackClasses) putShort(c.toShort())
            putShort((1 + remainingInputClasses.size).toShort()); for (c in remainingInputClasses) putShort(c.toShort())
            putShort(lookaheadClasses.size.toShort()); for (c in lookaheadClasses) putShort(c.toShort())
            putShort(seqLookups.size.toShort())
            for (rec in seqLookups) { putShort(rec.sequenceIndex.toShort()); putShort(rec.lookupListIndex.toShort()) }
        }.array()
        val classSet = ByteBuffer.allocate(4 + rule.size).order(ByteOrder.BIG_ENDIAN).apply {
            putShort(1); putShort(4); put(rule)
        }.array()
        val header = 12 + classSetCount * 2
        val covOff = header
        val backOff = covOff + coverageBytes.size
        val inOff = backOff + backClassDefBytes.size
        val lookOff = inOff + inputClassDefBytes.size
        val csOff = lookOff + lookClassDefBytes.size
        return ByteBuffer.allocate(csOff + classSet.size).order(ByteOrder.BIG_ENDIAN).apply {
            putShort(2); putShort(covOff.toShort()); putShort(backOff.toShort()); putShort(inOff.toShort())
            putShort(lookOff.toShort()); putShort(classSetCount.toShort())
            for (i in 0 until classSetCount) putShort((if (i == firstInputClass) csOff else 0).toShort())
            put(coverageBytes); put(backClassDefBytes); put(inputClassDefBytes); put(lookClassDefBytes); put(classSet)
        }.array()
    }

    fun buildChainedFormat3Subtable(
        backCoverages: List<ByteArray>,
        inputCoverages: List<ByteArray>,
        lookCoverages: List<ByteArray>,
        seqLookups: List<SeqLookupRec> = emptyList(),
    ): ByteArray {
        val header = 2 + 2 + backCoverages.size * 2 + 2 + inputCoverages.size * 2 + 2 + lookCoverages.size * 2 + 2 + seqLookups.size * 4
        val body = ByteArrayOutputStream()
        var cur = header
        fun addAll(list: List<ByteArray>): IntArray = IntArray(list.size) { idx ->
            val off = cur; body.write(list[idx]); cur += list[idx].size; off
        }
        val backOffs = addAll(backCoverages)
        val inOffs = addAll(inputCoverages)
        val lookOffs = addAll(lookCoverages)
        return ByteBuffer.allocate(header + body.size()).order(ByteOrder.BIG_ENDIAN).apply {
            putShort(3)
            putShort(backCoverages.size.toShort()); for (o in backOffs) putShort(o.toShort())
            putShort(inputCoverages.size.toShort()); for (o in inOffs) putShort(o.toShort())
            putShort(lookCoverages.size.toShort()); for (o in lookOffs) putShort(o.toShort())
            putShort(seqLookups.size.toShort())
            for (rec in seqLookups) { putShort(rec.sequenceIndex.toShort()); putShort(rec.lookupListIndex.toShort()) }
            put(body.toByteArray())
        }.array()
    }

    fun buildReverseChainedFormat1Subtable(
        inputPairs: List<Pair<Int, Int>>,
        backCoverages: List<ByteArray> = emptyList(),
        lookCoverages: List<ByteArray> = emptyList(),
    ): ByteArray {
        val sorted = inputPairs.sortedBy { it.first }
        val inCov = buildCoverageFormat1(sorted.map { it.first })
        val header = 4 + 2 + backCoverages.size * 2 + 2 + lookCoverages.size * 2 + 2 + sorted.size * 2
        val body = ByteArrayOutputStream()
        var cur = header
        val inCovOff = cur; body.write(inCov); cur += inCov.size
        val backOffs = IntArray(backCoverages.size) { i -> val o = cur; body.write(backCoverages[i]); cur += backCoverages[i].size; o }
        val lookOffs = IntArray(lookCoverages.size) { i -> val o = cur; body.write(lookCoverages[i]); cur += lookCoverages[i].size; o }
        return ByteBuffer.allocate(header + body.size()).order(ByteOrder.BIG_ENDIAN).apply {
            putShort(1); putShort(inCovOff.toShort())
            putShort(backCoverages.size.toShort()); for (o in backOffs) putShort(o.toShort())
            putShort(lookCoverages.size.toShort()); for (o in lookOffs) putShort(o.toShort())
            putShort(sorted.size.toShort()); for ((_, sub) in sorted) putShort(sub.toShort())
            put(body.toByteArray())
        }.array()
    }

    fun buildGsub(
        lookups: List<LookupSpec>,
        features: List<FeatureSpec>,
        requiredFeatureIndex: Int?,
        featureVariations: List<List<VariationSub>>,
    ): ByteArray {
        val isV11 = featureVariations.isNotEmpty()
        val headerSize = if (isV11) 14 else 10

        val langSysSize = 6 + features.size * 2
        val scriptTableSize = 4 + langSysSize
        val scriptListSize = 2 + 6 + scriptTableSize
        val scriptList = ByteBuffer.allocate(scriptListSize).order(ByteOrder.BIG_ENDIAN).apply {
            putShort(1)
            put("DFLT".toByteArray(Charsets.ISO_8859_1))
            putShort(8); putShort(4); putShort(0); putShort(0)
            putShort((requiredFeatureIndex ?: 0xFFFF).toShort())
            putShort(features.size.toShort())
            for (i in features.indices) putShort(i.toShort())
        }.array()

        val featHeaderSize = 2 + features.size * 6
        val featTables = features.map { f ->
            ByteBuffer.allocate(4 + f.lookupIndices.size * 2).order(ByteOrder.BIG_ENDIAN).apply {
                putShort(0); putShort(f.lookupIndices.size.toShort())
                for (li in f.lookupIndices) putShort(li.toShort())
            }.array()
        }
        val featListOut = ByteArrayOutputStream()
        val featHeader = ByteBuffer.allocate(featHeaderSize).order(ByteOrder.BIG_ENDIAN)
        featHeader.putShort(features.size.toShort())
        var fOff = featHeaderSize
        for ((idx, f) in features.withIndex()) {
            featHeader.put(f.tag.toByteArray(Charsets.ISO_8859_1))
            featHeader.putShort(fOff.toShort())
            fOff += featTables[idx].size
        }
        featListOut.write(featHeader.array())
        for (ft in featTables) featListOut.write(ft)
        val featureList = featListOut.toByteArray()

        val lookupHeaderSize = 2 + lookups.size * 2
        val lookupTables = lookups.map { lk ->
            if (lk.type == 7 && lk.extensionInnerType != null) {
                val inner = lk.extensionInnerSubtable
                    ?: buildLigatureFormat1Subtable(listOf(listOf(10, 11) to 12))
                ByteBuffer.allocate(16 + inner.size).order(ByteOrder.BIG_ENDIAN).apply {
                    putShort(7); putShort(lk.lookupFlag.toShort()); putShort(1); putShort(8)
                    putShort(1); putShort(lk.extensionInnerType.toShort()); putInt(8)
                    put(inner)
                }.array()
            } else {
                val subs = lk.rawSubtables
                val hSize = 6 + subs.size * 2
                val body = ByteArrayOutputStream()
                val offs = IntArray(subs.size)
                var cur = hSize
                for ((i, s) in subs.withIndex()) { offs[i] = cur; body.write(s); cur += s.size }
                ByteBuffer.allocate(hSize + body.size()).order(ByteOrder.BIG_ENDIAN).apply {
                    putShort(lk.type.toShort()); putShort(lk.lookupFlag.toShort()); putShort(subs.size.toShort())
                    for (o in offs) putShort(o.toShort())
                    put(body.toByteArray())
                }.array()
            }
        }
        val lookupListOut = ByteArrayOutputStream()
        val lookupHeader = ByteBuffer.allocate(lookupHeaderSize).order(ByteOrder.BIG_ENDIAN)
        lookupHeader.putShort(lookups.size.toShort())
        var lOff = lookupHeaderSize
        for (lt in lookupTables) {
            lookupHeader.putShort(lOff.toShort())
            lOff += lt.size
        }
        lookupListOut.write(lookupHeader.array())
        for (lt in lookupTables) lookupListOut.write(lt)
        val lookupList = lookupListOut.toByteArray()

        val featVarBytes = if (!isV11) {
            ByteArray(0)
        } else {
            val recCount = featureVariations.size
            val fvHeaderSize = 8 + recCount * 8
            val subTables = featureVariations.map { subs ->
                val stHeader = 6 + subs.size * 6
                val altTables = subs.map { s ->
                    ByteBuffer.allocate(4 + s.alternateLookups.size * 2).order(ByteOrder.BIG_ENDIAN).apply {
                        putShort(0); putShort(s.alternateLookups.size.toShort())
                        for (li in s.alternateLookups) putShort(li.toShort())
                    }.array()
                }
                val stOut = ByteArrayOutputStream()
                val h = ByteBuffer.allocate(stHeader).order(ByteOrder.BIG_ENDIAN)
                h.putShort(1); h.putShort(0); h.putShort(subs.size.toShort())
                var aOff = stHeader
                for ((idx, s) in subs.withIndex()) {
                    h.putShort(s.featureIndex.toShort()); h.putInt(aOff); aOff += altTables[idx].size
                }
                stOut.write(h.array())
                for (at in altTables) stOut.write(at)
                stOut.toByteArray()
            }
            val fvOut = ByteArrayOutputStream()
            val fvH = ByteBuffer.allocate(fvHeaderSize).order(ByteOrder.BIG_ENDIAN)
            fvH.putShort(1); fvH.putShort(0); fvH.putInt(recCount)
            var stOff = fvHeaderSize
            for (st in subTables) {
                fvH.putInt(0); fvH.putInt(stOff); stOff += st.size
            }
            fvOut.write(fvH.array())
            for (st in subTables) fvOut.write(st)
            fvOut.toByteArray()
        }

        val scriptOff = headerSize
        val featOff = scriptOff + scriptList.size
        val lookupOff = featOff + featureList.size
        val featVarOff = lookupOff + lookupList.size

        val gsub = ByteBuffer.allocate(featVarOff + featVarBytes.size).order(ByteOrder.BIG_ENDIAN)
        gsub.putShort(1); gsub.putShort(if (isV11) 1 else 0)
        gsub.putShort(scriptOff.toShort()); gsub.putShort(featOff.toShort()); gsub.putShort(lookupOff.toShort())
        if (isV11) gsub.putInt(featVarOff)
        gsub.put(scriptList); gsub.put(featureList); gsub.put(lookupList); gsub.put(featVarBytes)
        return gsub.array()
    }
}
