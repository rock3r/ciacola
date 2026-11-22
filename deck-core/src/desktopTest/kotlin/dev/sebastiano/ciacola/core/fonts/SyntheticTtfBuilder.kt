package dev.sebastiano.ciacola.core.fonts

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Deterministic big-endian OpenType/TrueType binary fixture builder producing valid Skia/CoreText-
 * loadable TTFs as well as targeted edge-case and malformed binaries for Condition 1
 * character-safety tests across [FontTableInspector] and [DeckFontResolver].
 */
object SyntheticTtfBuilder {

    data class AxisSpec(
        val tag: String,
        val minValue: Float,
        val defaultValue: Float,
        val maxValue: Float,
    )

    data class SeqLookupRec(
        val sequenceIndex: Int,
        val lookupListIndex: Int,
    )

    data class LigatureEntry(
        val components: List<Int>,
        val ligatureGlyph: Int,
    )

    data class GsubLookupSpec(
        val lookupType: Int,
        val lookupFlag: Int = 0,
        val extensionInnerTypes: List<Int> = emptyList(),
        /** Optional real inner subtable payloads for Type 7 (paired with [extensionInnerTypes]). */
        val extensionInnerSubtables: List<ByteArray> = emptyList(),
        /** Optional raw subtable byte arrays emitted verbatim for this lookup. */
        val rawSubtables: List<ByteArray> = emptyList(),
        /** Convenience for Type 4 Ligature Format 1 component sequences (output defaults to [defaultLigatureGlyph]). */
        val ligatureGlyphSequences: List<List<Int>> = emptyList(),
        val defaultLigatureGlyph: Int = 2,
    )

    data class GsubFeatureSpec(
        val tag: String,
        val lookupIndices: List<Int>,
    )

    data class GsubVariationSubstitution(
        val featureIndex: Int,
        val alternateLookupIndices: List<Int>,
    )

    data class GsubSpec(
        val minorVersion: Int = 0,
        val lookups: List<GsubLookupSpec> = emptyList(),
        val features: List<GsubFeatureSpec> = emptyList(),
        val requiredFeatureIndex: Int? = null,
        val featureVariations: List<List<GsubVariationSubstitution>> = emptyList(),
    )

    fun buildTtf(
        sfntVersion: Int = 0x00010000,
        familyName: String = "SyntheticSans",
        bmpCodePoints: Set<Int> = (' '..'~').map { it.code }.toSet(),
        useIndirectFormat4: Boolean = false,
        fullUnicodeCodePoints: Set<Int>? = null,
        explicitNumGlyphs: Int? = null,
        axes: List<AxisSpec> = emptyList(),
        os2WeightClass: Int = 400,
        os2IsItalic: Boolean = false,
        gsub: GsubSpec? = null,
        extraTables: Map<String, ByteArray> = emptyMap(),
        omitCmap: Boolean = false,
        omitMaxp: Boolean = false,
    ): ByteArray {
        val totalMapped = maxOf(
            bmpCodePoints.size,
            fullUnicodeCodePoints?.size ?: 0,
        )
        val numGlyphs = (explicitNumGlyphs ?: maxOf(totalMapped + 16, 128)).coerceAtLeast(2)

        val tables = linkedMapOf<String, ByteArray>()
        tables["head"] = buildHeadTable()
        tables["hhea"] = buildHheaTable(numGlyphs)
        if (!omitMaxp) {
            tables["maxp"] = buildMaxpV1Table(numGlyphs)
        }
        tables["hmtx"] = buildHmtxTable(numGlyphs)
        val (loca, glyf) = buildLocaAndGlyfTables(numGlyphs)
        tables["loca"] = loca
        tables["glyf"] = glyf
        tables["name"] = buildNameTable(familyName)
        tables["post"] = buildPostTable()
        tables["OS/2"] = buildOs2Table(os2WeightClass, os2IsItalic)
        if (!omitCmap) {
            tables["cmap"] = buildCmapTable(bmpCodePoints, useIndirectFormat4, fullUnicodeCodePoints)
        }
        if (axes.isNotEmpty()) {
            tables["fvar"] = buildFvarTable(axes)
        }
        if (gsub != null) {
            tables["GSUB"] = buildGsubTable(gsub)
        }
        tables.putAll(extraTables)
        return assembleSfnt(sfntVersion, tables)
    }

    /**
     * Extracts all tables from an existing SFNT binary (e.g. `Inter-Regular.ttf`), applies
     * [replacements] and [removals], and re-assembles a valid SFNT binary.
     */
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

    fun assembleSfnt(sfntVersion: Int, tables: Map<String, ByteArray>): ByteArray {
        val sorted = tables.entries.sortedBy { it.key }
        val numTables = sorted.size
        val headerSize = 12 + numTables * 16
        val body = ByteArrayOutputStream()
        val offsets = IntArray(numTables)
        val lengths = IntArray(numTables)
        val checksums = IntArray(numTables)
        var currentOffset = headerSize
        for ((idx, entry) in sorted.withIndex()) {
            val data = if (entry.key == "head" && entry.value.size >= 12) {
                entry.value.copyOf().also { headCopy ->
                    headCopy[8] = 0; headCopy[9] = 0; headCopy[10] = 0; headCopy[11] = 0
                }
            } else {
                entry.value
            }
            offsets[idx] = currentOffset
            lengths[idx] = data.size
            checksums[idx] = tableChecksum(data)
            body.write(data)
            val pad = (4 - (data.size % 4)) % 4
            repeat(pad) { body.write(0) }
            currentOffset += data.size + pad
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
            val tagBytes = entry.key.toByteArray(Charsets.ISO_8859_1)
            require(tagBytes.size == 4) { "Table tag must be 4 ASCII chars: ${entry.key}" }
            if (entry.key == "head") headOffset = offsets[idx]
            out.put(tagBytes)
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

    // -------------------------------------------------------------------------
    // Minimal valid SFNT tables for Skia/CoreText loading
    // -------------------------------------------------------------------------

    private fun buildHeadTable(): ByteArray {
        val buf = ByteBuffer.allocate(54).order(ByteOrder.BIG_ENDIAN)
        buf.putInt(0x00010000) // major.minor
        buf.putInt(0x00010000) // fontRevision
        buf.putInt(0) // checksumAdjustment
        buf.putInt(0x5F0F3CF5) // magicNumber
        buf.putShort(0x000B) // flags
        buf.putShort(1000) // unitsPerEm
        buf.putLong(0L) // created
        buf.putLong(0L) // modified
        buf.putShort(0) // xMin
        buf.putShort(-200) // yMin
        buf.putShort(600) // xMax
        buf.putShort(800) // yMax
        buf.putShort(0) // macStyle
        buf.putShort(8) // lowestRecPPEM
        buf.putShort(2) // fontDirectionHint
        buf.putShort(1) // indexToLocFormat = 1 (long 32-bit)
        buf.putShort(0) // glyphDataFormat
        return buf.array()
    }

    private fun buildHheaTable(numGlyphs: Int): ByteArray {
        val buf = ByteBuffer.allocate(36).order(ByteOrder.BIG_ENDIAN)
        buf.putInt(0x00010000) // version
        buf.putShort(800) // ascender
        buf.putShort(-200) // descender
        buf.putShort(0) // lineGap
        buf.putShort(600) // advanceWidthMax
        buf.putShort(0) // minLeftSideBearing
        buf.putShort(0) // minRightSideBearing
        buf.putShort(550) // xMaxExtent
        buf.putShort(1) // caretSlopeRise
        buf.putShort(0) // caretSlopeRun
        buf.putShort(0) // caretOffset
        buf.putShort(0); buf.putShort(0); buf.putShort(0); buf.putShort(0) // reserved
        buf.putShort(0) // metricDataFormat
        buf.putShort(numGlyphs.toShort()) // numberOfHMetrics
        return buf.array()
    }

    fun buildMaxpV1Table(numGlyphs: Int): ByteArray {
        val buf = ByteBuffer.allocate(32).order(ByteOrder.BIG_ENDIAN)
        buf.putInt(0x00010000) // version 1.0
        buf.putShort(numGlyphs.toShort())
        buf.putShort(4) // maxPoints
        buf.putShort(1) // maxContours
        buf.putShort(0) // maxCompositePoints
        buf.putShort(0) // maxCompositeContours
        buf.putShort(1) // maxZones
        buf.putShort(0) // maxTwilightPoints
        buf.putShort(0) // maxStorage
        buf.putShort(0) // maxFunctionDefs
        buf.putShort(0) // maxInstructionDefs
        buf.putShort(0) // maxStackElements
        buf.putShort(0) // maxSizeOfInstructions
        buf.putShort(0) // maxComponentElements
        buf.putShort(0) // maxComponentDepth
        return buf.array()
    }

    private fun buildHmtxTable(numGlyphs: Int): ByteArray {
        val buf = ByteBuffer.allocate(numGlyphs * 4).order(ByteOrder.BIG_ENDIAN)
        for (i in 0 until numGlyphs) {
            buf.putShort(600) // advanceWidth
            buf.putShort(50) // lsb
        }
        return buf.array()
    }

    private fun buildLocaAndGlyfTables(numGlyphs: Int): Pair<ByteArray, ByteArray> {
        // Single simple 4-point rectangle contour (28 bytes, 4-byte aligned).
        val boxGlyph = ByteBuffer.allocate(28).order(ByteOrder.BIG_ENDIAN).apply {
            putShort(1) // numberOfContours = 1
            putShort(50); putShort(0); putShort(550); putShort(700) // bbox
            putShort(3) // endPtsOfContours[0] = 3
            putShort(0) // instructionLength = 0
            // 4 points, flag = 0x01 (on-curve, 2-byte x/y deltas -> wait: use 1-byte positive deltas!).
            // Flag 0x01 (on-curve), 0x02 (x short), 0x10 (x positive), 0x04 (y short), 0x20 (y positive)
            // Use 0x01 (2-byte signed deltas) for clarity: 4 flags + 8 bytes x + 8 bytes y = 20 bytes -> total 34 -> pad to 36
        }
        // Build clean 36-byte glyph with 2-byte signed deltas (flag = 0x01 on-curve).
        val cleanGlyph = ByteBuffer.allocate(36).order(ByteOrder.BIG_ENDIAN).apply {
            putShort(1) // numberOfContours
            putShort(50); putShort(0); putShort(550); putShort(700) // bbox
            putShort(3) // endPtsOfContours[0]
            putShort(0) // instructionLength
            put(1); put(1); put(1); put(1) // flags: on-curve, 16-bit deltas
            // x deltas: 50, +500, 0, -500
            putShort(50); putShort(500); putShort(0); putShort(-500)
            // y deltas: 0, 0, +700, 0
            putShort(0); putShort(0); putShort(700); putShort(0)
            putShort(0) // 2-byte pad to 36
        }.array()
        boxGlyph.clear()

        // Share glyph 0's outline across glyph 0 and all non-empty glyphs via loca offsets!
        // Put 2 glyphs in glyf (glyph 0 = box, glyph 1 = box) and map all others to glyph 0's range.
        val glyf = cleanGlyph
        val loca = ByteBuffer.allocate((numGlyphs + 1) * 4).order(ByteOrder.BIG_ENDIAN)
        // Glyph 0 uses [0..36), remaining glyphs are empty [36..36) — wait: CoreText allows empty outlines
        // (valid space/blank glyphs), OR give glyph 0..min(numGlyphs, 256) real outlines if needed!
        // Giving glyph 0 [0..36) and glyph 1..numGlyphs-1 empty [36..36) keeps file tiny AND 100% spec-valid
        // (loca offsets must be monotonically non-decreasing!).
        loca.putInt(0)
        for (i in 1..numGlyphs) {
            loca.putInt(glyf.size)
        }
        return loca.array() to glyf
    }

    private fun buildNameTable(familyName: String): ByteArray {
        val entries = listOf(
            1 to familyName,
            2 to "Regular",
            4 to "$familyName Regular",
            6 to familyName.replace(" ", ""),
        )
        val encoded = entries.map { (id, text) -> id to text.toByteArray(Charsets.UTF_16BE) }
        val headerSize = 6 + encoded.size * 12
        val body = ByteArrayOutputStream()
        val offsets = IntArray(encoded.size)
        var off = 0
        for ((i, pair) in encoded.withIndex()) {
            offsets[i] = off
            body.write(pair.second)
            off += pair.second.size
        }
        val buf = ByteBuffer.allocate(headerSize + body.size()).order(ByteOrder.BIG_ENDIAN)
        buf.putShort(0) // format
        buf.putShort(encoded.size.toShort())
        buf.putShort(headerSize.toShort())
        for ((i, pair) in encoded.withIndex()) {
            buf.putShort(3) // platformID = Windows
            buf.putShort(1) // encodingID = Unicode BMP
            buf.putShort(0x0409) // languageID = en-US
            buf.putShort(pair.first.toShort())
            buf.putShort(pair.second.size.toShort())
            buf.putShort(offsets[i].toShort())
        }
        buf.put(body.toByteArray())
        return buf.array()
    }

    private fun buildPostTable(): ByteArray {
        val buf = ByteBuffer.allocate(32).order(ByteOrder.BIG_ENDIAN)
        buf.putInt(0x00030000) // format 3.0 (no PostScript glyph names)
        buf.putInt(0) // italicAngle
        buf.putShort(-100) // underlinePosition
        buf.putShort(50) // underlineThickness
        buf.putInt(0) // isFixedPitch
        buf.putInt(0); buf.putInt(0); buf.putInt(0); buf.putInt(0)
        return buf.array()
    }

    private fun buildOs2Table(weightClass: Int, isItalic: Boolean): ByteArray {
        val buf = ByteBuffer.allocate(78).order(ByteOrder.BIG_ENDIAN)
        buf.putShort(1) // version
        buf.putShort(500) // xAvgCharWidth
        buf.putShort(weightClass.toShort()) // usWeightClass at offset 4
        buf.putShort(5) // usWidthClass
        val fsSelection = if (isItalic) 0x0001 else 0x0040
        buf.putShort(62, fsSelection.toShort())
        buf.putShort(64, 0x0020) // usFirstCharIndex
        buf.putShort(66, 0x007E) // usLastCharIndex
        buf.putShort(68, 800) // sTypoAscender
        buf.putShort(70, -200) // sTypoDescender
        buf.putShort(74, 800) // usWinAscent
        buf.putShort(76, 200) // usWinDescent
        return buf.array()
    }

    private fun buildFvarTable(axes: List<AxisSpec>): ByteArray {
        val axesOffset = 16
        val axisSize = 20
        val buf = ByteBuffer.allocate(axesOffset + axes.size * axisSize).order(ByteOrder.BIG_ENDIAN)
        buf.putShort(1); buf.putShort(0)
        buf.putShort(axesOffset.toShort()); buf.putShort(2)
        buf.putShort(axes.size.toShort()); buf.putShort(axisSize.toShort())
        buf.putShort(0); buf.putShort(0)
        for (axis in axes) {
            buf.put(axis.tag.toByteArray(Charsets.ISO_8859_1))
            buf.putInt((axis.minValue * 65536f).toInt())
            buf.putInt((axis.defaultValue * 65536f).toInt())
            buf.putInt((axis.maxValue * 65536f).toInt())
            buf.putShort(0); buf.putShort(256)
        }
        return buf.array()
    }

    // -------------------------------------------------------------------------
    // CMAP builders (including explicit codePoint -> glyphId maps for disagreeing subtables)
    // -------------------------------------------------------------------------

    fun buildCustomCmapTable(subtables: List<Triple<Int, Int, ByteArray>>): ByteArray {
        val headerSize = 4 + subtables.size * 8
        var offset = headerSize
        val body = ByteArrayOutputStream()
        val recOffsets = IntArray(subtables.size)
        for ((idx, sub) in subtables.withIndex()) {
            recOffsets[idx] = offset
            body.write(sub.third)
            offset += sub.third.size
        }
        val out = ByteBuffer.allocate(headerSize + body.size()).order(ByteOrder.BIG_ENDIAN)
        out.putShort(0)
        out.putShort(subtables.size.toShort())
        for ((idx, sub) in subtables.withIndex()) {
            out.putShort(sub.first.toShort())
            out.putShort(sub.second.toShort())
            out.putInt(recOffsets[idx])
        }
        out.put(body.toByteArray())
        return out.array()
    }

    fun buildCmapFormat4FromMap(mapping: Map<Int, Int>): ByteArray {
        val sorted = mapping.entries.filter { it.key in 0..0xFFFE }.sortedBy { it.key }
        val segCount = sorted.size + 1
        val length = 16 + segCount * 8
        val buf = ByteBuffer.allocate(length).order(ByteOrder.BIG_ENDIAN)
        buf.putShort(4)
        buf.putShort(length.toShort())
        buf.putShort(0)
        buf.putShort((segCount * 2).toShort())
        buf.putShort(0); buf.putShort(0); buf.putShort(0)
        for ((cp, _) in sorted) buf.putShort(cp.toShort())
        buf.putShort(0xFFFF.toShort())
        buf.putShort(0)
        for ((cp, _) in sorted) buf.putShort(cp.toShort())
        buf.putShort(0xFFFF.toShort())
        for ((cp, gid) in sorted) buf.putShort(((gid - cp) and 0xFFFF).toShort())
        buf.putShort(1)
        for (i in 0 until segCount) buf.putShort(0)
        return buf.array()
    }

    fun buildCmapFormat12FromMap(mapping: Map<Int, Int>): ByteArray {
        val sorted = mapping.entries.filter { it.key in 0..0x10FFFF }.sortedBy { it.key }
        val length = 16 + sorted.size * 12
        val buf = ByteBuffer.allocate(length).order(ByteOrder.BIG_ENDIAN)
        buf.putShort(12); buf.putShort(0)
        buf.putInt(length); buf.putInt(0); buf.putInt(sorted.size)
        for ((cp, gid) in sorted) {
            buf.putInt(cp); buf.putInt(cp); buf.putInt(gid)
        }
        return buf.array()
    }

    private fun buildCmapTable(
        bmpCodePoints: Set<Int>,
        useIndirectFormat4: Boolean,
        fullUnicodeCodePoints: Set<Int>?,
    ): ByteArray {
        val subtables = mutableListOf<Triple<Int, Int, ByteArray>>()
        if (bmpCodePoints.isNotEmpty()) {
            subtables += Triple(3, 1, buildCmapFormat4(bmpCodePoints, useIndirectFormat4))
        }
        if (fullUnicodeCodePoints != null) {
            subtables += Triple(3, 10, buildCmapFormat12(fullUnicodeCodePoints))
        }
        return buildCustomCmapTable(subtables)
    }

    private fun buildCmapFormat4(codePoints: Set<Int>, useIndirect: Boolean): ByteArray {
        val sorted = codePoints.filter { it in 0..0xFFFE }.sorted()
        val ranges = mutableListOf<IntRange>()
        for (cp in sorted) {
            if (ranges.isEmpty() || cp != ranges.last().last + 1) {
                ranges += cp..cp
            } else {
                ranges[ranges.lastIndex] = ranges.last().first..cp
            }
        }
        val segCount = ranges.size + 1
        val glyphIds = mutableListOf<Int>()
        val idDeltas = IntArray(segCount)
        val idRangeOffsets = IntArray(segCount)
        var nextGlyphId = 1
        for (i in ranges.indices) {
            val r = ranges[i]
            if (useIndirect) {
                idDeltas[i] = 0
                idRangeOffsets[i] = ((segCount - i) + glyphIds.size) * 2
                for (cp in r) glyphIds += nextGlyphId++
            } else {
                idDeltas[i] = (nextGlyphId - r.first) and 0xFFFF
                idRangeOffsets[i] = 0
                nextGlyphId += (r.last - r.first + 1)
            }
        }
        idDeltas[segCount - 1] = 1
        idRangeOffsets[segCount - 1] = 0

        val length = 16 + segCount * 8 + glyphIds.size * 2
        val buf = ByteBuffer.allocate(length).order(ByteOrder.BIG_ENDIAN)
        buf.putShort(4); buf.putShort(length.toShort()); buf.putShort(0)
        buf.putShort((segCount * 2).toShort())
        buf.putShort(0); buf.putShort(0); buf.putShort(0)
        for (r in ranges) buf.putShort(r.last.toShort())
        buf.putShort(0xFFFF.toShort())
        buf.putShort(0)
        for (r in ranges) buf.putShort(r.first.toShort())
        buf.putShort(0xFFFF.toShort())
        for (d in idDeltas) buf.putShort(d.toShort())
        for (ro in idRangeOffsets) buf.putShort(ro.toShort())
        for (g in glyphIds) buf.putShort(g.toShort())
        return buf.array()
    }

    private fun buildCmapFormat12(codePoints: Set<Int>): ByteArray {
        val sorted = codePoints.filter { it in 0..0x10FFFF }.sorted()
        val groups = mutableListOf<Triple<Int, Int, Int>>()
        var nextGlyph = 1
        for (cp in sorted) {
            if (groups.isEmpty() || cp != groups.last().second + 1) {
                groups += Triple(cp, cp, nextGlyph)
            } else {
                val prev = groups.last()
                groups[groups.lastIndex] = Triple(prev.first, cp, prev.third)
            }
            nextGlyph++
        }
        val length = 16 + groups.size * 12
        val buf = ByteBuffer.allocate(length).order(ByteOrder.BIG_ENDIAN)
        buf.putShort(12); buf.putShort(0)
        buf.putInt(length); buf.putInt(0); buf.putInt(groups.size)
        for ((start, end, startGlyph) in groups) {
            buf.putInt(start); buf.putInt(end); buf.putInt(startGlyph)
        }
        return buf.array()
    }

    // -------------------------------------------------------------------------
    // GSUB table and subtable builders (OpenType LookupTypes 1..8)
    // -------------------------------------------------------------------------

    fun buildCoverageFormat1(glyphs: List<Int>): ByteArray =
        ByteBuffer.allocate(4 + glyphs.size * 2).order(ByteOrder.BIG_ENDIAN).apply {
            putShort(1)
            putShort(glyphs.size.toShort())
            for (g in glyphs) putShort(g.toShort())
        }.array()

    fun buildCoverageFormat2(ranges: List<IntRange>): ByteArray {
        val buf = ByteBuffer.allocate(4 + ranges.size * 6).order(ByteOrder.BIG_ENDIAN)
        buf.putShort(2)
        buf.putShort(ranges.size.toShort())
        var covIdx = 0
        for (r in ranges) {
            buf.putShort(r.first.toShort())
            buf.putShort(r.last.toShort())
            buf.putShort(covIdx.toShort())
            covIdx += (r.last - r.first + 1)
        }
        return buf.array()
    }

    fun buildClassDefFormat1(startGlyph: Int, classValues: List<Int>): ByteArray =
        ByteBuffer.allocate(6 + classValues.size * 2).order(ByteOrder.BIG_ENDIAN).apply {
            putShort(1)
            putShort(startGlyph.toShort())
            putShort(classValues.size.toShort())
            for (c in classValues) putShort(c.toShort())
        }.array()

    fun buildClassDefFormat2(ranges: List<Triple<Int, Int, Int>>): ByteArray =
        ByteBuffer.allocate(4 + ranges.size * 6).order(ByteOrder.BIG_ENDIAN).apply {
            putShort(2)
            putShort(ranges.size.toShort())
            for ((s, e, c) in ranges) {
                putShort(s.toShort())
                putShort(e.toShort())
                putShort(c.toShort())
            }
        }.array()

    fun buildSingleFormat1Subtable(coverageBytes: ByteArray, deltaGlyphId: Int): ByteArray =
        ByteBuffer.allocate(6 + coverageBytes.size).order(ByteOrder.BIG_ENDIAN).apply {
            putShort(1)
            putShort(6)
            putShort(deltaGlyphId.toShort())
            put(coverageBytes)
        }.array()

    fun buildSingleFormat2Subtable(pairs: List<Pair<Int, Int>>): ByteArray {
        val sorted = pairs.sortedBy { it.first }
        val cov = buildCoverageFormat1(sorted.map { it.first })
        val header = 6 + sorted.size * 2
        return ByteBuffer.allocate(header + cov.size).order(ByteOrder.BIG_ENDIAN).apply {
            putShort(2)
            putShort(header.toShort())
            putShort(sorted.size.toShort())
            for ((_, out) in sorted) putShort(out.toShort())
            put(cov)
        }.array()
    }

    fun buildMultipleFormat1Subtable(entries: List<Pair<Int, List<Int>>>): ByteArray {
        val sorted = entries.sortedBy { it.first }
        val cov = buildCoverageFormat1(sorted.map { it.first })
        val header = 6 + sorted.size * 2
        val covOffset = header
        val body = ByteArrayOutputStream()
        body.write(cov)
        var cur = covOffset + cov.size
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
            putShort(1)
            putShort(covOffset.toShort())
            putShort(sorted.size.toShort())
            for (o in seqOffsets) putShort(o.toShort())
            put(body.toByteArray())
        }.array()
    }

    fun buildAlternateFormat1Subtable(entries: List<Pair<Int, List<Int>>>): ByteArray =
        buildMultipleFormat1Subtable(entries) // Identical binary layout!

    fun buildLigatureFormat1Subtable(entries: List<LigatureEntry>): ByteArray {
        val grouped = entries.groupBy { it.components.first() }.toSortedMap()
        val firstGlyphs = grouped.keys.toList()
        val covBytes = buildCoverageFormat1(firstGlyphs)
        val ligSetBytes = grouped.values.map { ligs ->
            val ligTables = ligs.map { entry ->
                ByteBuffer.allocate(4 + (entry.components.size - 1) * 2).order(ByteOrder.BIG_ENDIAN).apply {
                    putShort(entry.ligatureGlyph.toShort())
                    putShort(entry.components.size.toShort())
                    for (i in 1 until entry.components.size) putShort(entry.components[i].toShort())
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
        val covOffset = header
        var cur = covOffset + covBytes.size
        val lsOffsets = IntArray(ligSetBytes.size)
        val body = ByteArrayOutputStream()
        body.write(covBytes)
        for ((i, bytes) in ligSetBytes.withIndex()) {
            lsOffsets[i] = cur; body.write(bytes); cur += bytes.size
        }
        return ByteBuffer.allocate(header + body.size()).order(ByteOrder.BIG_ENDIAN).apply {
            putShort(1)
            putShort(covOffset.toShort())
            putShort(firstGlyphs.size.toShort())
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
            for (rec in seqLookups) {
                putShort(rec.sequenceIndex.toShort()); putShort(rec.lookupListIndex.toShort())
            }
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
            for (rec in seqLookups) {
                putShort(rec.sequenceIndex.toShort()); putShort(rec.lookupListIndex.toShort())
            }
        }.array()
        val classSet = ByteBuffer.allocate(4 + rule.size).order(ByteOrder.BIG_ENDIAN).apply {
            putShort(1); putShort(4); put(rule)
        }.array()
        val header = 8 + classSetCount * 2
        val covOff = header
        val clsOff = covOff + coverageBytes.size
        val csOff = clsOff + classDefBytes.size
        return ByteBuffer.allocate(csOff + classSet.size).order(ByteOrder.BIG_ENDIAN).apply {
            putShort(2)
            putShort(covOff.toShort())
            putShort(clsOff.toShort())
            putShort(classSetCount.toShort())
            for (i in 0 until classSetCount) {
                putShort((if (i == firstClass) csOff else 0).toShort())
            }
            put(coverageBytes); put(classDefBytes); put(classSet)
        }.array()
    }

    fun buildContextFormat3Subtable(
        coverages: List<ByteArray>,
        seqLookups: List<SeqLookupRec> = emptyList(),
    ): ByteArray {
        val header = 6 + coverages.size * 2 + seqLookups.size * 4
        val body = ByteArrayOutputStream()
        val offsets = IntArray(coverages.size)
        var cur = header
        for ((i, c) in coverages.withIndex()) {
            offsets[i] = cur; body.write(c); cur += c.size
        }
        return ByteBuffer.allocate(header + body.size()).order(ByteOrder.BIG_ENDIAN).apply {
            putShort(3)
            putShort(coverages.size.toShort())
            putShort(seqLookups.size.toShort())
            for (o in offsets) putShort(o.toShort())
            for (rec in seqLookups) {
                putShort(rec.sequenceIndex.toShort()); putShort(rec.lookupListIndex.toShort())
            }
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
        val ruleLen = 2 + backtrackGlyphs.size * 2 +
            2 + remainingInputGlyphs.size * 2 +
            2 + lookaheadGlyphs.size * 2 +
            2 + seqLookups.size * 4
        val rule = ByteBuffer.allocate(ruleLen).order(ByteOrder.BIG_ENDIAN).apply {
            putShort(backtrackGlyphs.size.toShort())
            for (g in backtrackGlyphs) putShort(g.toShort())
            putShort((1 + remainingInputGlyphs.size).toShort())
            for (g in remainingInputGlyphs) putShort(g.toShort())
            putShort(lookaheadGlyphs.size.toShort())
            for (g in lookaheadGlyphs) putShort(g.toShort())
            putShort(seqLookups.size.toShort())
            for (rec in seqLookups) {
                putShort(rec.sequenceIndex.toShort()); putShort(rec.lookupListIndex.toShort())
            }
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
        val ruleLen = 2 + backtrackClasses.size * 2 +
            2 + remainingInputClasses.size * 2 +
            2 + lookaheadClasses.size * 2 +
            2 + seqLookups.size * 4
        val rule = ByteBuffer.allocate(ruleLen).order(ByteOrder.BIG_ENDIAN).apply {
            putShort(backtrackClasses.size.toShort())
            for (c in backtrackClasses) putShort(c.toShort())
            putShort((1 + remainingInputClasses.size).toShort())
            for (c in remainingInputClasses) putShort(c.toShort())
            putShort(lookaheadClasses.size.toShort())
            for (c in lookaheadClasses) putShort(c.toShort())
            putShort(seqLookups.size.toShort())
            for (rec in seqLookups) {
                putShort(rec.sequenceIndex.toShort()); putShort(rec.lookupListIndex.toShort())
            }
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
            putShort(2)
            putShort(covOff.toShort())
            putShort(backOff.toShort())
            putShort(inOff.toShort())
            putShort(lookOff.toShort())
            putShort(classSetCount.toShort())
            for (i in 0 until classSetCount) {
                putShort((if (i == firstInputClass) csOff else 0).toShort())
            }
            put(coverageBytes); put(backClassDefBytes); put(inputClassDefBytes); put(lookClassDefBytes); put(classSet)
        }.array()
    }

    fun buildChainedFormat3Subtable(
        backCoverages: List<ByteArray>,
        inputCoverages: List<ByteArray>,
        lookCoverages: List<ByteArray>,
        seqLookups: List<SeqLookupRec> = emptyList(),
    ): ByteArray {
        val header = 2 + 2 + backCoverages.size * 2 +
            2 + inputCoverages.size * 2 +
            2 + lookCoverages.size * 2 +
            2 + seqLookups.size * 4
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
            putShort(backCoverages.size.toShort())
            for (o in backOffs) putShort(o.toShort())
            putShort(inputCoverages.size.toShort())
            for (o in inOffs) putShort(o.toShort())
            putShort(lookCoverages.size.toShort())
            for (o in lookOffs) putShort(o.toShort())
            putShort(seqLookups.size.toShort())
            for (rec in seqLookups) {
                putShort(rec.sequenceIndex.toShort()); putShort(rec.lookupListIndex.toShort())
            }
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
        val backOffs = IntArray(backCoverages.size) { i ->
            val o = cur; body.write(backCoverages[i]); cur += backCoverages[i].size; o
        }
        val lookOffs = IntArray(lookCoverages.size) { i ->
            val o = cur; body.write(lookCoverages[i]); cur += lookCoverages[i].size; o
        }
        return ByteBuffer.allocate(header + body.size()).order(ByteOrder.BIG_ENDIAN).apply {
            putShort(1)
            putShort(inCovOff.toShort())
            putShort(backCoverages.size.toShort())
            for (o in backOffs) putShort(o.toShort())
            putShort(lookCoverages.size.toShort())
            for (o in lookOffs) putShort(o.toShort())
            putShort(sorted.size.toShort())
            for ((_, out) in sorted) putShort(out.toShort())
            put(body.toByteArray())
        }.array()
    }

    fun buildGsubTable(spec: GsubSpec): ByteArray {
        val hasFv = spec.minorVersion >= 1 && spec.featureVariations.isNotEmpty()
        val headerSize = if (spec.minorVersion >= 1) 14 else 10
        val scriptListBytes = buildScriptList(spec.features.size, spec.requiredFeatureIndex)
        val featureListBytes = buildFeatureList(spec.features)
        val lookupListBytes = buildLookupList(spec.lookups)
        val fvBytes = if (hasFv) buildFeatureVariations(spec.featureVariations) else ByteArray(0)

        val scriptOff = headerSize
        val featureOff = scriptOff + scriptListBytes.size
        val lookupOff = featureOff + featureListBytes.size
        val fvOff = if (hasFv) lookupOff + lookupListBytes.size else 0
        val totalSize = lookupOff + lookupListBytes.size + fvBytes.size

        return ByteBuffer.allocate(totalSize).order(ByteOrder.BIG_ENDIAN).apply {
            putShort(1)
            putShort(spec.minorVersion.toShort())
            putShort(scriptOff.toShort())
            putShort(featureOff.toShort())
            putShort(lookupOff.toShort())
            if (spec.minorVersion >= 1) putInt(fvOff)
            put(scriptListBytes)
            put(featureListBytes)
            put(lookupListBytes)
            put(fvBytes)
        }.array()
    }

    private fun buildScriptList(featureCount: Int, requiredFeatureIndex: Int?): ByteArray {
        val langSysSize = 6 + featureCount * 2
        val scriptTableSize = 4 + langSysSize
        return ByteBuffer.allocate(8 + scriptTableSize).order(ByteOrder.BIG_ENDIAN).apply {
            putShort(1)
            put("DFLT".toByteArray(Charsets.ISO_8859_1))
            putShort(8)
            putShort(4)
            putShort(0)
            putShort(0)
            putShort((requiredFeatureIndex ?: 0xFFFF).toShort())
            putShort(featureCount.toShort())
            for (i in 0 until featureCount) putShort(i.toShort())
        }.array()
    }

    private fun buildFeatureList(features: List<GsubFeatureSpec>): ByteArray {
        val headerSize = 2 + features.size * 6
        val featureTables = features.map { feat ->
            ByteBuffer.allocate(4 + feat.lookupIndices.size * 2).order(ByteOrder.BIG_ENDIAN).apply {
                putShort(0)
                putShort(feat.lookupIndices.size.toShort())
                for (idx in feat.lookupIndices) putShort(idx.toShort())
            }.array()
        }
        var offset = headerSize
        val offsets = IntArray(features.size)
        val body = ByteArrayOutputStream()
        for ((i, bytes) in featureTables.withIndex()) {
            offsets[i] = offset; body.write(bytes); offset += bytes.size
        }
        return ByteBuffer.allocate(headerSize + body.size()).order(ByteOrder.BIG_ENDIAN).apply {
            putShort(features.size.toShort())
            for ((i, feat) in features.withIndex()) {
                put(feat.tag.toByteArray(Charsets.ISO_8859_1))
                putShort(offsets[i].toShort())
            }
            put(body.toByteArray())
        }.array()
    }

    private fun buildLookupList(lookups: List<GsubLookupSpec>): ByteArray {
        val headerSize = 2 + lookups.size * 2
        val lookupTables = lookups.map { spec -> buildSingleLookup(spec) }
        var offset = headerSize
        val offsets = IntArray(lookups.size)
        val body = ByteArrayOutputStream()
        for ((i, bytes) in lookupTables.withIndex()) {
            offsets[i] = offset; body.write(bytes); offset += bytes.size
        }
        return ByteBuffer.allocate(headerSize + body.size()).order(ByteOrder.BIG_ENDIAN).apply {
            putShort(lookups.size.toShort())
            for (o in offsets) putShort(o.toShort())
            put(body.toByteArray())
        }.array()
    }

    private fun buildSingleLookup(spec: GsubLookupSpec): ByteArray {
        if (spec.lookupType == 7) {
            val subCount = spec.extensionInnerTypes.size
            val innerPayloads = List(subCount) { idx ->
                spec.extensionInnerSubtables.getOrNull(idx) ?: defaultValidSubtableFor(spec.extensionInnerTypes[idx])
            }
            val lHeader = 6 + subCount * 2
            val subOffsets = IntArray(subCount)
            val body = ByteArrayOutputStream()
            var cur = lHeader
            for (s in 0 until subCount) {
                subOffsets[s] = cur
                val payload = innerPayloads[s]
                val extRec = ByteBuffer.allocate(8 + payload.size).order(ByteOrder.BIG_ENDIAN).apply {
                    putShort(1) // substFormat = 1
                    putShort(spec.extensionInnerTypes[s].toShort())
                    putInt(8) // offset from this Extension subtable to target payload
                    put(payload)
                }.array()
                body.write(extRec)
                cur += extRec.size
            }
            return ByteBuffer.allocate(lHeader + body.size()).order(ByteOrder.BIG_ENDIAN).apply {
                putShort(7)
                putShort(spec.lookupFlag.toShort())
                putShort(subCount.toShort())
                for (o in subOffsets) putShort(o.toShort())
                put(body.toByteArray())
            }.array()
        }

        val subtables: List<ByteArray> = when {
            spec.rawSubtables.isNotEmpty() -> spec.rawSubtables
            spec.lookupType == 4 && spec.ligatureGlyphSequences.isNotEmpty() -> listOf(
                buildLigatureFormat1Subtable(
                    spec.ligatureGlyphSequences.map { LigatureEntry(it, spec.defaultLigatureGlyph) },
                ),
            )
            else -> listOf(defaultValidSubtableFor(spec.lookupType))
        }
        val lHeader = 6 + subtables.size * 2
        val subOffsets = IntArray(subtables.size)
        val body = ByteArrayOutputStream()
        var cur = lHeader
        for ((i, s) in subtables.withIndex()) {
            subOffsets[i] = cur; body.write(s); cur += s.size
        }
        return ByteBuffer.allocate(lHeader + body.size()).order(ByteOrder.BIG_ENDIAN).apply {
            putShort(spec.lookupType.toShort())
            putShort(spec.lookupFlag.toShort())
            putShort(subtables.size.toShort())
            for (o in subOffsets) putShort(o.toShort())
            put(body.toByteArray())
        }.array()
    }

    /** Produces a minimal, spec-valid GSUB subtable for [lookupType] using valid glyph IDs 1, 2, 3. */
    private fun defaultValidSubtableFor(lookupType: Int): ByteArray = when (lookupType) {
        1 -> buildSingleFormat2Subtable(listOf(1 to 2))
        2 -> buildMultipleFormat1Subtable(listOf(1 to listOf(2, 3)))
        3 -> buildAlternateFormat1Subtable(listOf(1 to listOf(2, 3)))
        4 -> buildLigatureFormat1Subtable(listOf(LigatureEntry(listOf(1, 2), 3)))
        5 -> buildContextFormat1Subtable(1, listOf(2))
        6 -> buildChainedFormat1Subtable(listOf(1), 2, emptyList(), emptyList())
        8 -> buildReverseChainedFormat1Subtable(
            inputPairs = listOf(1 to 2),
            backCoverages = listOf(buildCoverageFormat1(listOf(3))),
        )
        else -> ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(0).array()
    }

    private fun buildFeatureVariations(records: List<List<GsubVariationSubstitution>>): ByteArray {
        val headerSize = 8 + records.size * 8
        val condSetBytes = ByteBuffer.allocate(2 + 4 + 8).order(ByteOrder.BIG_ENDIAN).apply {
            putShort(1)
            putInt(6)
            putShort(1) // ConditionFormat1
            putShort(0) // axisIndex
            putShort(0x2000)
            putShort(0x4000)
        }.array()

        val substTables = records.map { subs ->
            val subHeader = 6 + subs.size * 6
            val altBodies = ByteArrayOutputStream()
            val altOffsets = IntArray(subs.size)
            var altOff = subHeader
            for ((j, sub) in subs.withIndex()) {
                altOffsets[j] = altOff
                val arr = ByteBuffer.allocate(4 + sub.alternateLookupIndices.size * 2).order(ByteOrder.BIG_ENDIAN).apply {
                    putShort(0)
                    putShort(sub.alternateLookupIndices.size.toShort())
                    for (lIdx in sub.alternateLookupIndices) putShort(lIdx.toShort())
                }.array()
                altBodies.write(arr)
                altOff += arr.size
            }
            ByteBuffer.allocate(subHeader + altBodies.size()).order(ByteOrder.BIG_ENDIAN).apply {
                putShort(1); putShort(0)
                putShort(subs.size.toShort())
                for ((j, sub) in subs.withIndex()) {
                    putShort(sub.featureIndex.toShort())
                    putInt(altOffsets[j])
                }
                put(altBodies.toByteArray())
            }.array()
        }

        val body = ByteArrayOutputStream()
        val condOffset = headerSize
        body.write(condSetBytes)
        var cur = headerSize + condSetBytes.size
        val substOffsets = IntArray(records.size)
        for ((r, bytes) in substTables.withIndex()) {
            substOffsets[r] = cur; body.write(bytes); cur += bytes.size
        }

        return ByteBuffer.allocate(headerSize + body.size()).order(ByteOrder.BIG_ENDIAN).apply {
            putShort(1); putShort(0)
            putInt(records.size)
            for (r in records.indices) {
                putInt(condOffset)
                putInt(substOffsets[r])
            }
            put(body.toByteArray())
        }.array()
    }
}
