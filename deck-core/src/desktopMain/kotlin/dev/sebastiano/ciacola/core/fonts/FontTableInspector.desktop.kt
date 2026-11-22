package dev.sebastiano.ciacola.core.fonts

import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Pure-Kotlin binary OpenType/TrueType `.ttf`/`.otf` table parser using big-endian [ByteBuffer].
 *
 * Inspects:
 * - SFNT offset table and table directory (fail-closed on corruption or unsupported `ttcf`)
 * - `maxp`: validated glyph count (`numGlyphs > 0`) defining domain `G = {0 .. numGlyphs - 1}`
 * - `fvar`: variation axes (`wght`, `ital`, `slnt`, etc.)
 * - `OS/2`: weight class and italic bit (for static faces)
 * - `cmap`: Unicode Format 4 / Format 12 (informational only; Condition 1 uses rendering Skia Typeface)
 * - `GSUB`: v1.0 & v1.1 `ScriptList`, `FeatureList`, `LookupList` (all lookup types 1..8 with
 *   Extension 7 unwrapping, nested lookup cycle detection, and record-based precedence),
 *   `LangSys.requiredFeatureIndex`, and `FeatureVariations`
 *
 * File-level inspection metadata is cached by `(SHA-256, limits)`.
 */
object FontTableInspector {
    private const val SFNT_TRUETYPE: Long = 0x00010000L
    private const val SFNT_CFF: Long = 0x4F54544FL // "OTTO"
    private const val SFNT_APPLE_TRUETYPE: Long = 0x74727565L // "true"
    private const val SFNT_TTC: Long = 0x74746366L // "ttcf"

    private const val MAXP_VERSION_0_5: Long = 0x00005000L
    private const val MAXP_VERSION_1_0: Long = 0x00010000L

    private const val REQUIRED_FEATURE_NONE: Int = 0xFFFF
    private const val LOOKUP_TYPE_EXTENSION: Int = 7

    private val cache: ConcurrentHashMap<Pair<String, GsubParserLimits>, FontTableInspection> =
        ConcurrentHashMap()

    fun inspect(
        bytes: ByteArray,
        useCache: Boolean = true,
        limits: GsubParserLimits = GsubParserLimits.Default,
    ): FontTableInspection {
        val sha256 = sha256Hex(bytes)
        val cacheKey = sha256 to limits
        if (useCache) {
            cache[cacheKey]?.let { return it }
        }
        val result = parseInternal(bytes, sha256, limits)
        if (useCache) {
            cache.putIfAbsent(cacheKey, result)
        }
        return result
    }

    fun clearCache() {
        cache.clear()
    }

    internal fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val out = StringBuilder(digest.size * 2)
        for (b in digest) {
            val v = b.toInt() and 0xFF
            out.append(HEX_DIGITS[v ushr 4])
            out.append(HEX_DIGITS[v and 0x0F])
        }
        return out.toString()
    }

    private fun parseInternal(
        bytes: ByteArray,
        sha256: String,
        limits: GsubParserLimits,
    ): FontTableInspection {
        if (bytes.size < 12) {
            return invalidInspection(sha256, "Font data too short (${bytes.size} bytes) for SFNT header.")
        }

        val reader = BigEndianReader(bytes)
        val sfntVersion = reader.u32(0)
        if (sfntVersion == SFNT_TTC) {
            return invalidInspection(sha256, "TrueType Collection (ttcf) is not supported as a single bundled face.")
        }
        if (sfntVersion != SFNT_TRUETYPE && sfntVersion != SFNT_CFF && sfntVersion != SFNT_APPLE_TRUETYPE) {
            return invalidInspection(sha256, "Unsupported SFNT version 0x${sfntVersion.toString(16)}.")
        }

        val numTables = reader.u16(4)
        if (numTables <= 0) {
            return invalidInspection(sha256, "SFNT table directory declares zero tables.")
        }
        val dirEnd = 12L + numTables.toLong() * 16L
        if (dirEnd > bytes.size.toLong()) {
            return invalidInspection(sha256, "SFNT table directory truncated (need $dirEnd bytes, have ${bytes.size}).")
        }

        val tables = linkedMapOf<String, TableRange>()
        for (i in 0 until numTables) {
            val recPos = 12L + i.toLong() * 16L
            val tag = reader.tag(recPos)
            val offset = reader.u32(recPos + 8L)
            val length = reader.u32(recPos + 12L)
            if (offset < 12L || offset + length > bytes.size.toLong()) {
                return invalidInspection(
                    sha256,
                    "Table '$tag' range [$offset..${offset + length}) exceeds file size ${bytes.size}.",
                )
            }
            if (tables.put(tag, TableRange(offset, length)) != null) {
                return invalidInspection(sha256, "Duplicate SFNT table tag '$tag'.")
            }
        }

        val tableTags = tables.keys.toSet()
        val unsupportedShaping = tableTags.intersect(UNSUPPORTED_SHAPING_TABLES)

        var firstError: String? = null
        fun recordError(message: String) {
            if (firstError == null) firstError = message
        }

        // Parse maxp (required: defines glyph domain G = {0 .. numGlyphs - 1}).
        val numGlyphs: Int = tables["maxp"]?.let { range ->
            val parsed = parseMaxp(reader, range)
            if (parsed.error != null) {
                recordError(parsed.error)
                0
            } else {
                parsed.value
            }
        } ?: run {
            recordError("Missing required 'maxp' table.")
            0
        }

        // Parse fvar (optional).
        val variationAxes: List<VariationAxisRange> = tables["fvar"]?.let { range ->
            val parsed = parseFvar(reader, range)
            if (parsed.error != null) {
                recordError(parsed.error)
                emptyList()
            } else {
                parsed.value
            }
        } ?: emptyList()

        // Parse OS/2 (optional).
        var os2WeightClass: Int? = null
        var os2IsItalic: Boolean? = null
        tables["OS/2"]?.let { range ->
            if (range.length < 64L) {
                recordError("OS/2 table truncated (${range.length} bytes < 64).")
            } else {
                os2WeightClass = reader.u16(range.offset + 4L)
                val fsSelection = reader.u16(range.offset + 62L)
                os2IsItalic = (fsSelection and 0x0001) != 0
            }
        }

        // Parse cmap (informational; certification uses Skia Typeface nominal glyphs).
        val cmapCoverage: CmapCoverage? = tables["cmap"]?.let { range ->
            val parsed = parseCmap(reader, range)
            if (parsed.error != null) {
                recordError(parsed.error)
                null
            } else {
                parsed.value
            }
        } ?: run {
            recordError("Missing required 'cmap' table.")
            null
        }

        // Parse GSUB (optional; validated against maxp.numGlyphs domain).
        val gsubInspection: GsubInspection = tables["GSUB"]?.let { range ->
            if (numGlyphs <= 0) {
                GsubInspection(
                    present = true,
                    majorVersion = 0,
                    minorVersion = 0,
                    lookupCount = 0,
                    featureCount = 0,
                    featureVariationCount = 0,
                    featureTags = emptySet(),
                    disallowedReferences = emptyList(),
                    parseError = "Cannot inspect GSUB without valid maxp.numGlyphs.",
                )
            } else {
                parseGsub(reader, range, numGlyphs, limits)
            }
        } ?: GsubInspection.Absent

        if (gsubInspection.parseError != null) {
            recordError(gsubInspection.parseError)
        }

        return FontTableInspection(
            contentSha256 = sha256,
            validSfnt = true,
            tableTags = tableTags,
            numGlyphs = numGlyphs,
            variationAxes = variationAxes,
            os2WeightClass = os2WeightClass,
            os2IsItalic = os2IsItalic,
            cmapCoverage = cmapCoverage,
            gsub = gsubInspection,
            unsupportedShapingTables = unsupportedShaping,
            parseError = firstError,
        )
    }

    private fun invalidInspection(sha256: String, error: String): FontTableInspection =
        FontTableInspection(
            contentSha256 = sha256,
            validSfnt = false,
            tableTags = emptySet(),
            numGlyphs = 0,
            variationAxes = emptyList(),
            os2WeightClass = null,
            os2IsItalic = null,
            cmapCoverage = null,
            gsub = GsubInspection.Absent,
            unsupportedShapingTables = emptySet(),
            parseError = error,
        )

    private fun parseMaxp(reader: BigEndianReader, table: TableRange): ParseResult<Int> {
        if (table.length < 6L) {
            return ParseResult(0, "maxp table truncated (${table.length} bytes < 6).")
        }
        val version = reader.u32(table.offset)
        val requiredLen = when (version) {
            MAXP_VERSION_0_5 -> 6L
            MAXP_VERSION_1_0 -> 32L
            else -> return ParseResult(0, "Unsupported maxp version 0x${version.toString(16)}.")
        }
        if (table.length < requiredLen) {
            return ParseResult(0, "maxp v0x${version.toString(16)} truncated (${table.length} < $requiredLen).")
        }
        val numGlyphs = reader.u16(table.offset + 4L)
        if (numGlyphs <= 0) {
            return ParseResult(0, "maxp declares numGlyphs=$numGlyphs (must be > 0).")
        }
        return ParseResult(numGlyphs, null)
    }

    private fun parseFvar(
        reader: BigEndianReader,
        table: TableRange,
    ): ParseResult<List<VariationAxisRange>> {
        if (table.length < 16L) {
            return ParseResult(emptyList(), "fvar table truncated (${table.length} bytes < 16).")
        }
        val base = table.offset
        val major = reader.u16(base)
        val minor = reader.u16(base + 2L)
        if (major != 1 || minor != 0) {
            return ParseResult(emptyList(), "Unsupported fvar version $major.$minor.")
        }
        val axesArrayOffset = reader.u16(base + 4L).toLong()
        val axisCount = reader.u16(base + 8L)
        val axisSize = reader.u16(base + 10L)
        if (axesArrayOffset < 16L || axesArrayOffset > table.length) {
            return ParseResult(emptyList(), "Invalid fvar axesArrayOffset $axesArrayOffset.")
        }
        if (axisCount > 0 && axisSize < 20) {
            return ParseResult(emptyList(), "Invalid fvar axisSize $axisSize (< 20).")
        }
        val axesEnd = axesArrayOffset + axisCount.toLong() * axisSize.toLong()
        if (axesEnd > table.length) {
            return ParseResult(emptyList(), "fvar axes array exceeds table length ($axesEnd > ${table.length}).")
        }

        val axes = ArrayList<VariationAxisRange>(axisCount)
        for (i in 0 until axisCount) {
            val pos = base + axesArrayOffset + i.toLong() * axisSize.toLong()
            val tag = reader.tag(pos)
            val minVal = reader.fixed1616(pos + 4L)
            val defVal = reader.fixed1616(pos + 8L)
            val maxVal = reader.fixed1616(pos + 12L)
            if (minVal > defVal || defVal > maxVal) {
                return ParseResult(
                    emptyList(),
                    "Corrupted fvar axis '$tag': min=$minVal, default=$defVal, max=$maxVal.",
                )
            }
            axes += VariationAxisRange(tag = tag, minValue = minVal, defaultValue = defVal, maxValue = maxVal)
        }
        return ParseResult(axes, null)
    }

    private fun parseCmap(reader: BigEndianReader, table: TableRange): ParseResult<CmapCoverage?> {
        if (table.length < 4L) {
            return ParseResult(null, "cmap table truncated (${table.length} bytes < 4).")
        }
        val base = table.offset
        val tableEnd = table.end
        val version = reader.u16(base)
        if (version != 0) {
            return ParseResult(null, "Unsupported cmap version $version.")
        }
        val numTables = reader.u16(base + 2L)
        if (4L + numTables.toLong() * 8L > table.length) {
            return ParseResult(null, "cmap encoding records exceed table length.")
        }

        var bestFormat12: CmapFormat12? = null
        var bestFormat4: CmapFormat4? = null
        val parsedFormat12ByOffset = hashMapOf<Long, CmapFormat12>()
        val parsedFormat4ByOffset = hashMapOf<Long, CmapFormat4>()

        for (i in 0 until numTables) {
            val recPos = base + 4L + i.toLong() * 8L
            val platformId = reader.u16(recPos)
            val encodingId = reader.u16(recPos + 2L)
            val subOffset = reader.u32(recPos + 4L)
            if (subOffset < 4L || subOffset + 2L > table.length) {
                return ParseResult(null, "cmap subtable offset $subOffset out of bounds.")
            }

            val isUnicodeFull = (platformId == 3 && encodingId == 10) ||
                (platformId == 0 && (encodingId == 4 || encodingId == 6))
            val isUnicodeBmp = (platformId == 3 && encodingId == 1) ||
                (platformId == 0 && encodingId in 0..3)
            if (!isUnicodeFull && !isUnicodeBmp) continue

            val subPos = base + subOffset
            when (reader.u16(subPos)) {
                12 -> {
                    val parsed = parsedFormat12ByOffset[subOffset] ?: run {
                        val res = parseCmapFormat12(reader, subPos, tableEnd)
                        if (res.error != null) return ParseResult(null, res.error)
                        res.value!!.also { parsedFormat12ByOffset[subOffset] = it }
                    }
                    if (bestFormat12 == null || isUnicodeFull) bestFormat12 = parsed
                }
                4 -> {
                    val parsed = parsedFormat4ByOffset[subOffset] ?: run {
                        val res = parseCmapFormat4(reader, subPos, tableEnd)
                        if (res.error != null) return ParseResult(null, res.error)
                        res.value!!.also { parsedFormat4ByOffset[subOffset] = it }
                    }
                    if (bestFormat4 == null || (platformId == 3 && encodingId == 1)) bestFormat4 = parsed
                }
            }
        }

        if (bestFormat12 == null && bestFormat4 == null) {
            return ParseResult(null, "No Unicode cmap Format 4 or Format 12 subtable found.")
        }
        return ParseResult(CompositeCmapCoverage(bestFormat12, bestFormat4), null)
    }

    private fun parseCmapFormat4(
        reader: BigEndianReader,
        subStart: Long,
        tableEnd: Long,
    ): ParseResult<CmapFormat4?> {
        if (subStart + 14L > tableEnd) return ParseResult(null, "cmap Format 4 header truncated.")
        val length = reader.u16(subStart + 2L).toLong()
        if (length < 16L || subStart + length > tableEnd) {
            return ParseResult(null, "cmap Format 4 length $length out of bounds.")
        }
        val subEnd = subStart + length
        val segCountX2 = reader.u16(subStart + 6L)
        if (segCountX2 < 2 || (segCountX2 and 1) != 0) {
            return ParseResult(null, "Invalid cmap Format 4 segCountX2=$segCountX2.")
        }
        val segCount = segCountX2 / 2
        val endCodePos = subStart + 14L
        val startCodePos = endCodePos + segCountX2.toLong() + 2L
        val idDeltaPos = startCodePos + segCountX2.toLong()
        val idRangeOffsetPos = idDeltaPos + segCountX2.toLong()
        val glyphArrayPos = idRangeOffsetPos + segCountX2.toLong()
        if (glyphArrayPos > subEnd) {
            return ParseResult(null, "cmap Format 4 segment arrays exceed subtable length.")
        }

        val endCodes = IntArray(segCount)
        val startCodes = IntArray(segCount)
        val idDeltas = IntArray(segCount)
        val idRangeOffsets = IntArray(segCount)
        var prevEnd = -1
        for (i in 0 until segCount) {
            val e = reader.u16(endCodePos + i.toLong() * 2L)
            val s = reader.u16(startCodePos + i.toLong() * 2L)
            val d = reader.i16(idDeltaPos + i.toLong() * 2L)
            val r = reader.u16(idRangeOffsetPos + i.toLong() * 2L)
            if (s > e || e < prevEnd) {
                return ParseResult(null, "Corrupted cmap Format 4 segment $i: start=$s, end=$e, prevEnd=$prevEnd.")
            }
            if (r != 0 && s != 0xFFFF) {
                if ((r and 1) != 0) return ParseResult(null, "Odd cmap Format 4 idRangeOffset=$r at segment $i.")
                val firstAddr = idRangeOffsetPos + i.toLong() * 2L + r.toLong()
                val lastAddr = firstAddr + (e - s).toLong() * 2L
                if (firstAddr < glyphArrayPos || lastAddr + 2L > subEnd) {
                    return ParseResult(null, "cmap Format 4 glyphIdArray reference out of bounds at segment $i.")
                }
            }
            endCodes[i] = e
            startCodes[i] = s
            idDeltas[i] = d
            idRangeOffsets[i] = r
            prevEnd = e
        }

        val glyphArrayCount = ((subEnd - glyphArrayPos) / 2L).toInt()
        val glyphIds = IntArray(glyphArrayCount) { idx -> reader.u16(glyphArrayPos + idx.toLong() * 2L) }
        return ParseResult(CmapFormat4(endCodes, startCodes, idDeltas, idRangeOffsets, glyphIds, segCount), null)
    }

    private fun parseCmapFormat12(
        reader: BigEndianReader,
        subStart: Long,
        tableEnd: Long,
    ): ParseResult<CmapFormat12?> {
        if (subStart + 16L > tableEnd) return ParseResult(null, "cmap Format 12 header truncated.")
        val length = reader.u32(subStart + 4L)
        if (length < 16L || subStart + length > tableEnd) {
            return ParseResult(null, "cmap Format 12 length $length out of bounds.")
        }
        val numGroupsLong = reader.u32(subStart + 12L)
        if (numGroupsLong > Int.MAX_VALUE.toLong() || 16L + numGroupsLong * 12L > length) {
            return ParseResult(null, "cmap Format 12 group count $numGroupsLong exceeds subtable length.")
        }
        val numGroups = numGroupsLong.toInt()
        val startChars = IntArray(numGroups)
        val endChars = IntArray(numGroups)
        val startGlyphs = IntArray(numGroups)

        var prevEnd = -1L
        for (i in 0 until numGroups) {
            val gPos = subStart + 16L + i.toLong() * 12L
            val startCode = reader.u32(gPos)
            val endCode = reader.u32(gPos + 4L)
            val startGlyph = reader.u32(gPos + 8L)
            if (startCode > endCode || startCode <= prevEnd || endCode > 0x10FFFFL) {
                return ParseResult(null, "Corrupted cmap Format 12 group $i: [$startCode..$endCode].")
            }
            startChars[i] = startCode.toInt()
            endChars[i] = endCode.toInt()
            startGlyphs[i] = startGlyph.toInt()
            prevEnd = endCode
        }
        return ParseResult(CmapFormat12(startChars, endChars, startGlyphs), null)
    }

    private fun parseGsub(
        reader: BigEndianReader,
        table: TableRange,
        numGlyphs: Int,
        limits: GsubParserLimits,
    ): GsubInspection {
        fun fail(
            message: String,
            major: Int = 0,
            minor: Int = 0,
            lookupCount: Int = 0,
            featureCount: Int = 0,
            fvCount: Int = 0,
            featureTags: Set<String> = emptySet(),
            disallowed: List<GsubDisallowedReference> = emptyList(),
        ): GsubInspection = GsubInspection(
            present = true,
            majorVersion = major,
            minorVersion = minor,
            lookupCount = lookupCount,
            featureCount = featureCount,
            featureVariationCount = fvCount,
            featureTags = featureTags,
            disallowedReferences = disallowed,
            parseError = message,
        )

        val base = table.offset
        val tableEnd = table.end
        if (table.length < 10L) return fail("GSUB table truncated (${table.length} bytes < 10).")

        val major = reader.u16(base)
        val minor = reader.u16(base + 2L)
        if (major != 1 || (minor != 0 && minor != 1)) {
            return fail("Unsupported GSUB version $major.$minor.", major, minor)
        }
        val headerSize = if (minor == 1) 14L else 10L
        if (table.length < headerSize) {
            return fail("GSUB v$major.$minor header truncated (${table.length} < $headerSize).", major, minor)
        }

        val scriptListRel = reader.u16(base + 4L).toLong()
        val featureListRel = reader.u16(base + 6L).toLong()
        val lookupListRel = reader.u16(base + 8L).toLong()
        val featureVariationsRel = if (minor == 1) reader.u32(base + 10L) else 0L

        if (scriptListRel < headerSize || scriptListRel >= table.length) {
            return fail("GSUB scriptListOffset $scriptListRel out of bounds.", major, minor)
        }
        if (featureListRel < headerSize || featureListRel >= table.length) {
            return fail("GSUB featureListOffset $featureListRel out of bounds.", major, minor)
        }
        if (lookupListRel < headerSize || lookupListRel >= table.length) {
            return fail("GSUB lookupListOffset $lookupListRel out of bounds.", major, minor)
        }
        if (featureVariationsRel != 0L && (featureVariationsRel < headerSize || featureVariationsRel >= table.length)) {
            return fail("GSUB featureVariationsOffset $featureVariationsRel out of bounds.", major, minor)
        }

        // 1. Parse ALL LookupList entries, unwrap Extension 7, and validate every subtable against domain G.
        val lookupBase = base + lookupListRel
        if (lookupBase + 2L > tableEnd) return fail("GSUB LookupList header truncated.", major, minor)
        val lookupCount = reader.u16(lookupBase)
        if (lookupBase + 2L + lookupCount.toLong() * 2L > tableEnd) {
            return fail("GSUB LookupList offset array exceeds table bounds.", major, minor, lookupCount)
        }

        val rawLookups = ArrayList<RawLookup>(lookupCount)
        val budget = GsubParseBudget(limits)
        for (i in 0 until lookupCount) {
            val lRel = reader.u16(lookupBase + 2L + i.toLong() * 2L).toLong()
            val lStart = lookupBase + lRel
            if (lStart + 6L > tableEnd) {
                return fail("GSUB Lookup $i header out of bounds.", major, minor, lookupCount)
            }
            val lookupType = reader.u16(lStart)
            val subTableCount = reader.u16(lStart + 4L)
            if (lookupType !in 1..8) {
                return fail("Unknown GSUB lookupType $lookupType at lookup $i.", major, minor, lookupCount)
            }
            if (lStart + 6L + subTableCount.toLong() * 2L > tableEnd) {
                return fail("GSUB Lookup $i subtable offsets exceed table bounds.", major, minor, lookupCount)
            }

            val targets = ArrayList<GsubSubtableTarget>(subTableCount)
            val effectiveTypes = linkedSetOf<Int>()
            val viaExt = lookupType == LOOKUP_TYPE_EXTENSION
            if (viaExt) {
                for (s in 0 until subTableCount) {
                    val subStart = lStart + reader.u16(lStart + 6L + s.toLong() * 2L).toLong()
                    if (subStart + 8L > tableEnd) {
                        return fail("GSUB Extension lookup $i subtable $s truncated.", major, minor, lookupCount)
                    }
                    val substFormat = reader.u16(subStart)
                    if (substFormat != 1) {
                        return fail("Unsupported GSUB Extension substFormat $substFormat at lookup $i.", major, minor, lookupCount)
                    }
                    val extLookupType = reader.u16(subStart + 2L)
                    if (extLookupType !in 1..8 || extLookupType == LOOKUP_TYPE_EXTENSION) {
                        return fail("Invalid GSUB Extension inner lookupType $extLookupType at lookup $i.", major, minor, lookupCount)
                    }
                    val extOffset = reader.u32(subStart + 4L)
                    if (extOffset < 8L || subStart + extOffset >= tableEnd) {
                        return fail("GSUB Extension lookup $i subtable $s offset $extOffset OOB.", major, minor, lookupCount)
                    }
                    effectiveTypes += extLookupType
                    targets += GsubSubtableTarget(extLookupType, subStart + extOffset)
                }
            } else {
                effectiveTypes += lookupType
                for (s in 0 until subTableCount) {
                    val subStart = lStart + reader.u16(lStart + 6L + s.toLong() * 2L).toLong()
                    if (subStart >= tableEnd) {
                        return fail("GSUB Lookup $i subtable $s offset OOB.", major, minor, lookupCount)
                    }
                    targets += GsubSubtableTarget(lookupType, subStart)
                }
            }

            val lookupRules = ArrayList<GsubRule>()
            for (target in targets) {
                val parsed = GsubRuleParser.parseSubtable(reader, tableEnd, numGlyphs, lookupCount, target, budget)
                    ?: return fail(budget.error ?: "Failed to parse GSUB lookup $i.", major, minor, lookupCount)
                lookupRules += parsed
            }
            rawLookups += RawLookup(
                lookupIndex = i,
                effectiveTypes = effectiveTypes,
                viaExtension = viaExt,
                rules = lookupRules,
                directHasMultiGlyph = lookupRules.any { it.isMultiGlyph },
            )
        }

        // 2. Cycle detection and transitive multi-glyph reachability across nested Type 5/6 lookups.
        val visitState = IntArray(lookupCount) // 0=unvisited, 1=visiting, 2=done
        val transitiveMulti = BooleanArray(lookupCount)
        var cycleError: String? = null

        fun dfsLookup(idx: Int, depth: Int): Boolean {
            if (cycleError != null) return false
            if (depth > budget.maxNestingDepth) {
                cycleError = "GSUB nested lookup nesting depth exceeded (${budget.maxNestingDepth})."
                return false
            }
            when (visitState[idx]) {
                1 -> {
                    cycleError = "GSUB nested lookup cycle detected involving lookup $idx."
                    return false
                }
                2 -> return transitiveMulti[idx]
            }
            visitState[idx] = 1
            val raw = rawLookups[idx]
            var hasMulti = raw.directHasMultiGlyph
            for (rule in raw.rules) {
                for (nestedIdx in rule.nestedLookupIndices) {
                    if (dfsLookup(nestedIdx, depth + 1)) {
                        hasMulti = true
                    }
                    if (cycleError != null) return false
                }
            }
            visitState[idx] = 2
            transitiveMulti[idx] = hasMulti
            return hasMulti
        }

        for (i in 0 until lookupCount) {
            dfsLookup(i, 0)
            cycleError?.let { err -> return fail(err, major, minor, lookupCount) }
        }

        val parsedLookups = List(lookupCount) { i ->
            val raw = rawLookups[i]
            GsubParsedLookup(
                lookupIndex = raw.lookupIndex,
                effectiveTypes = raw.effectiveTypes,
                viaExtension = raw.viaExtension,
                rules = raw.rules,
                directHasMultiGlyph = raw.directHasMultiGlyph,
                transitiveHasMultiGlyph = transitiveMulti[i],
            )
        }

        // 3. Parse FeatureList.
        val featureBase = base + featureListRel
        if (featureBase + 2L > tableEnd) return fail("GSUB FeatureList header truncated.", major, minor, lookupCount)
        val featureCount = reader.u16(featureBase)
        if (featureBase + 2L + featureCount.toLong() * 6L > tableEnd) {
            return fail("GSUB FeatureList records exceed bounds.", major, minor, lookupCount, featureCount)
        }

        val features = ArrayList<ParsedFeature>(featureCount)
        val featureTags = linkedSetOf<String>()
        for (i in 0 until featureCount) {
            val recPos = featureBase + 2L + i.toLong() * 6L
            val tag = reader.tag(recPos)
            val fStart = featureBase + reader.u16(recPos + 4L).toLong()
            val indices = parseFeatureTableLookups(reader, fStart, tableEnd, lookupCount)
                ?: return fail("GSUB Feature '$tag' (index $i) invalid FeatureTable.", major, minor, lookupCount, featureCount, 0, featureTags)
            featureTags += tag
            features += ParsedFeature(index = i, tag = tag, lookupIndices = indices)
        }

        // 4. Parse ScriptList and collect all requiredFeatureIndex values across every script & LangSys.
        val scriptBase = base + scriptListRel
        if (scriptBase + 2L > tableEnd) {
            return fail("GSUB ScriptList header truncated.", major, minor, lookupCount, featureCount, 0, featureTags)
        }
        val scriptCount = reader.u16(scriptBase)
        if (scriptBase + 2L + scriptCount.toLong() * 6L > tableEnd) {
            return fail("GSUB ScriptList records exceed bounds.", major, minor, lookupCount, featureCount, 0, featureTags)
        }

        val requiredFeatureIndices = hashSetOf<Int>()
        fun validateLangSys(langSysStart: Long): String? {
            if (langSysStart + 6L > tableEnd) return "GSUB LangSys table truncated."
            val reqIdx = reader.u16(langSysStart + 2L)
            val featIdxCount = reader.u16(langSysStart + 4L)
            if (langSysStart + 6L + featIdxCount.toLong() * 2L > tableEnd) {
                return "GSUB LangSys feature index array exceeds bounds."
            }
            if (reqIdx != REQUIRED_FEATURE_NONE) {
                if (reqIdx >= featureCount) {
                    return "GSUB LangSys requiredFeatureIndex $reqIdx out of bounds (featureCount=$featureCount)."
                }
                requiredFeatureIndices += reqIdx
            }
            for (k in 0 until featIdxCount) {
                val fIdx = reader.u16(langSysStart + 6L + k.toLong() * 2L)
                if (fIdx >= featureCount) {
                    return "GSUB LangSys featureIndex $fIdx out of bounds (featureCount=$featureCount)."
                }
            }
            return null
        }

        for (s in 0 until scriptCount) {
            val sStart = scriptBase + reader.u16(scriptBase + 2L + s.toLong() * 6L + 4L).toLong()
            if (sStart + 4L > tableEnd) {
                return fail("GSUB ScriptTable $s truncated.", major, minor, lookupCount, featureCount, 0, featureTags)
            }
            val defaultLangSysRel = reader.u16(sStart).toLong()
            val langSysCount = reader.u16(sStart + 2L)
            if (sStart + 4L + langSysCount.toLong() * 6L > tableEnd) {
                return fail("GSUB ScriptTable $s LangSysRecords exceed bounds.", major, minor, lookupCount, featureCount, 0, featureTags)
            }
            if (defaultLangSysRel != 0L) {
                validateLangSys(sStart + defaultLangSysRel)?.let { err ->
                    return fail(err, major, minor, lookupCount, featureCount, 0, featureTags)
                }
            }
            for (l in 0 until langSysCount) {
                val lRel = reader.u16(sStart + 4L + l.toLong() * 6L + 4L).toLong()
                validateLangSys(sStart + lRel)?.let { err ->
                    return fail(err, major, minor, lookupCount, featureCount, 0, featureTags)
                }
            }
        }

        // 5. Classify feature records under exact 5-level precedence.
        val disallowed = mutableListOf<GsubDisallowedReference>()
        val alwaysOnLookupSet = linkedSetOf<Int>()
        val perWordLookupSet = linkedSetOf<Int>()
        var alwaysOnHasMultiGlyph = false

        fun inspectFeatureRecord(
            featureIndex: Int,
            tag: String,
            lookupIndices: IntArray,
            viaFeatureVariation: Boolean,
        ) {
            val isRequired = featureIndex in requiredFeatureIndices
            val featureClass = classifyFeatureRecord(
                tag = tag,
                isRequired = isRequired,
                viaFeatureVariation = viaFeatureVariation,
            )
            if (featureClass == GsubFeatureClass.Inactive) return

            for (lIdx in lookupIndices) {
                val lookup = parsedLookups[lIdx]
                when (featureClass) {
                    GsubFeatureClass.AlwaysOn -> {
                        alwaysOnLookupSet += lIdx
                        if (lookup.transitiveHasMultiGlyph) {
                            alwaysOnHasMultiGlyph = true
                            val multiType = lookup.rules.firstOrNull { it.isMultiGlyph }?.lookupType
                                ?: lookup.effectiveTypes.firstOrNull()
                                ?: 0
                            disallowed += GsubDisallowedReference(
                                featureTag = tag,
                                lookupIndex = lIdx,
                                lookupType = multiType,
                                viaExtension = lookup.viaExtension,
                                viaRequiredFeature = isRequired,
                                viaFeatureVariation = viaFeatureVariation,
                                featureClass = GsubFeatureClass.AlwaysOn,
                            )
                        }
                    }
                    GsubFeatureClass.PerWord -> {
                        perWordLookupSet += lIdx
                        if (lookup.transitiveHasMultiGlyph) {
                            val multiType = lookup.rules.firstOrNull { it.isMultiGlyph }?.lookupType
                                ?: lookup.effectiveTypes.firstOrNull()
                                ?: 0
                            disallowed += GsubDisallowedReference(
                                featureTag = tag,
                                lookupIndex = lIdx,
                                lookupType = multiType,
                                viaExtension = lookup.viaExtension,
                                viaRequiredFeature = false,
                                viaFeatureVariation = false,
                                featureClass = GsubFeatureClass.PerWord,
                            )
                        }
                    }
                    GsubFeatureClass.Inactive -> Unit
                }
            }
        }

        for (feature in features) {
            inspectFeatureRecord(
                featureIndex = feature.index,
                tag = feature.tag,
                lookupIndices = feature.lookupIndices,
                viaFeatureVariation = false,
            )
        }

        // 6. Parse and inspect GSUB v1.1 FeatureVariations (Rule 1: ALWAYS Always-On regardless of tag!).
        var fvCount = 0
        if (minor == 1 && featureVariationsRel != 0L) {
            val fvBase = base + featureVariationsRel
            if (fvBase + 8L > tableEnd) {
                return fail("GSUB FeatureVariations header truncated.", major, minor, lookupCount, featureCount, 0, featureTags, disallowed)
            }
            val fvMajor = reader.u16(fvBase)
            val fvMinor = reader.u16(fvBase + 2L)
            if (fvMajor != 1 || fvMinor != 0) {
                return fail("Unsupported GSUB FeatureVariations version $fvMajor.$fvMinor.", major, minor, lookupCount, featureCount, 0, featureTags, disallowed)
            }
            val recCountLong = reader.u32(fvBase + 4L)
            if (recCountLong > Int.MAX_VALUE.toLong() || fvBase + 8L + recCountLong * 8L > tableEnd) {
                return fail("GSUB FeatureVariations record array exceeds bounds.", major, minor, lookupCount, featureCount, 0, featureTags, disallowed)
            }
            fvCount = recCountLong.toInt()
            for (r in 0 until fvCount) {
                val rPos = fvBase + 8L + r.toLong() * 8L
                val condSetRel = reader.u32(rPos)
                val substRel = reader.u32(rPos + 4L)

                if (condSetRel != 0L) {
                    val csStart = fvBase + condSetRel
                    if (csStart + 2L > tableEnd) {
                        return fail("GSUB FeatureVariations ConditionSet $r truncated.", major, minor, lookupCount, featureCount, fvCount, featureTags, disallowed)
                    }
                    val condCount = reader.u16(csStart)
                    if (csStart + 2L + condCount.toLong() * 4L > tableEnd) {
                        return fail("GSUB FeatureVariations ConditionSet $r offsets OOB.", major, minor, lookupCount, featureCount, fvCount, featureTags, disallowed)
                    }
                    for (c in 0 until condCount) {
                        val cStart = csStart + reader.u32(csStart + 2L + c.toLong() * 4L)
                        if (cStart + 2L > tableEnd) {
                            return fail("GSUB FeatureVariations ConditionTable OOB.", major, minor, lookupCount, featureCount, fvCount, featureTags, disallowed)
                        }
                        val condFormat = reader.u16(cStart)
                        if (condFormat != 1 || cStart + 8L > tableEnd) {
                            return fail("Unsupported or truncated ConditionTable format $condFormat.", major, minor, lookupCount, featureCount, fvCount, featureTags, disallowed)
                        }
                    }
                }

                if (substRel != 0L) {
                    val subBase = fvBase + substRel
                    if (subBase + 6L > tableEnd) {
                        return fail("GSUB FeatureTableSubstitution $r truncated.", major, minor, lookupCount, featureCount, fvCount, featureTags, disallowed)
                    }
                    val subMajor = reader.u16(subBase)
                    val subMinor = reader.u16(subBase + 2L)
                    if (subMajor != 1 || subMinor != 0) {
                        return fail("Unsupported FeatureTableSubstitution version $subMajor.$subMinor.", major, minor, lookupCount, featureCount, fvCount, featureTags, disallowed)
                    }
                    val subCount = reader.u16(subBase + 4L)
                    if (subBase + 6L + subCount.toLong() * 6L > tableEnd) {
                        return fail("GSUB FeatureTableSubstitution $r records OOB.", major, minor, lookupCount, featureCount, fvCount, featureTags, disallowed)
                    }
                    for (j in 0 until subCount) {
                        val jPos = subBase + 6L + j.toLong() * 6L
                        val featureIdx = reader.u16(jPos)
                        val altFeatRel = reader.u32(jPos + 2L)
                        if (featureIdx >= featureCount) {
                            return fail("FeatureTableSubstitution OOB featureIndex $featureIdx.", major, minor, lookupCount, featureCount, fvCount, featureTags, disallowed)
                        }
                        val altIndices = parseFeatureTableLookups(reader, subBase + altFeatRel, tableEnd, lookupCount)
                            ?: return fail("FeatureTableSubstitution invalid alternate FeatureTable for featureIndex $featureIdx.", major, minor, lookupCount, featureCount, fvCount, featureTags, disallowed)
                        inspectFeatureRecord(
                            featureIndex = featureIdx,
                            tag = features[featureIdx].tag,
                            lookupIndices = altIndices,
                            viaFeatureVariation = true,
                        )
                    }
                }
            }
        }

        return GsubInspection(
            present = true,
            majorVersion = major,
            minorVersion = minor,
            lookupCount = lookupCount,
            featureCount = featureCount,
            featureVariationCount = fvCount,
            featureTags = featureTags,
            disallowedReferences = disallowed,
            parsedLookups = parsedLookups,
            alwaysOnLookupIndices = alwaysOnLookupSet.toIntArray().also { it.sort() },
            perWordLookupIndices = perWordLookupSet.toIntArray().also { it.sort() },
            alwaysOnHasMultiGlyph = alwaysOnHasMultiGlyph,
            parseError = null,
        )
    }

    internal fun classifyFeatureRecord(
        tag: String,
        isRequired: Boolean,
        viaFeatureVariation: Boolean,
    ): GsubFeatureClass = when {
        isRequired || viaFeatureVariation -> GsubFeatureClass.AlwaysOn
        tag in OPTIONAL_LIGATURE_FEATURES -> GsubFeatureClass.Inactive
        tag == "ccmp" || tag == "locl" -> GsubFeatureClass.PerWord
        isOptInGsubFeature(tag) -> GsubFeatureClass.Inactive
        else -> GsubFeatureClass.AlwaysOn
    }

    private fun parseFeatureTableLookups(
        reader: BigEndianReader,
        featureStart: Long,
        tableEnd: Long,
        lookupCount: Int,
    ): IntArray? {
        if (featureStart + 4L > tableEnd) return null
        val paramsOffset = reader.u16(featureStart).toLong()
        if (paramsOffset != 0L && featureStart + paramsOffset >= tableEnd) return null
        val indexCount = reader.u16(featureStart + 2L)
        if (featureStart + 4L + indexCount.toLong() * 2L > tableEnd) return null
        val indices = IntArray(indexCount)
        for (i in 0 until indexCount) {
            val lIdx = reader.u16(featureStart + 4L + i.toLong() * 2L)
            if (lIdx >= lookupCount) return null
            indices[i] = lIdx
        }
        return indices
    }

    private data class TableRange(val offset: Long, val length: Long) {
        val end: Long get() = offset + length
    }

    private data class ParseResult<T>(val value: T, val error: String?)

    private class RawLookup(
        val lookupIndex: Int,
        val effectiveTypes: Set<Int>,
        val viaExtension: Boolean,
        val rules: List<GsubRule>,
        val directHasMultiGlyph: Boolean,
    )

    private class ParsedFeature(val index: Int, val tag: String, val lookupIndices: IntArray)

    private class CmapFormat4(
        private val endCodes: IntArray,
        private val startCodes: IntArray,
        private val idDeltas: IntArray,
        private val idRangeOffsets: IntArray,
        private val glyphIds: IntArray,
        private val segCount: Int,
    ) : CmapCoverage {
        override val formatsPresent: Set<Int> = setOf(4)

        override fun glyphId(codePoint: Int): Int {
            if (codePoint < 0 || codePoint > 0xFFFF) return 0
            var lo = 0
            var hi = segCount - 1
            var found = -1
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                if (endCodes[mid] >= codePoint) {
                    found = mid
                    hi = mid - 1
                } else {
                    lo = mid + 1
                }
            }
            if (found < 0 || codePoint < startCodes[found]) return 0
            val rangeOffset = idRangeOffsets[found]
            if (rangeOffset == 0) return (codePoint + idDeltas[found]) and 0xFFFF
            val glyphIndex = (rangeOffset / 2) + (codePoint - startCodes[found]) - (segCount - found)
            if (glyphIndex !in glyphIds.indices) return 0
            val rawGlyph = glyphIds[glyphIndex]
            return if (rawGlyph == 0) 0 else (rawGlyph + idDeltas[found]) and 0xFFFF
        }
    }

    private class CmapFormat12(
        private val startChars: IntArray,
        private val endChars: IntArray,
        private val startGlyphs: IntArray,
    ) : CmapCoverage {
        override val formatsPresent: Set<Int> = setOf(12)

        override fun glyphId(codePoint: Int): Int {
            if (codePoint < 0 || codePoint > 0x10FFFF) return 0
            var lo = 0
            var hi = endChars.size - 1
            var found = -1
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                if (endChars[mid] >= codePoint) {
                    found = mid
                    hi = mid - 1
                } else {
                    lo = mid + 1
                }
            }
            if (found < 0 || codePoint < startChars[found]) return 0
            return startGlyphs[found] + (codePoint - startChars[found])
        }
    }

    private class CompositeCmapCoverage(
        private val format12: CmapFormat12?,
        private val format4: CmapFormat4?,
    ) : CmapCoverage {
        override val formatsPresent: Set<Int> = buildSet {
            if (format4 != null) add(4)
            if (format12 != null) add(12)
        }

        override fun glyphId(codePoint: Int): Int {
            if (codePoint < 0 || codePoint > 0x10FFFF) return 0
            if (format12 != null) return format12.glyphId(codePoint)
            return if (codePoint <= 0xFFFF && format4 != null) format4.glyphId(codePoint) else 0
        }
    }

    private val HEX_DIGITS: CharArray = "0123456789abcdef".toCharArray()
}
