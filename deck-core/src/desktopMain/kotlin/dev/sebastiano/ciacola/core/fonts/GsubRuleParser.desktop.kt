package dev.sebastiano.ciacola.core.fonts

import java.nio.ByteBuffer
import java.nio.ByteOrder

internal class BigEndianReader(private val bytes: ByteArray) {
    private val buffer: ByteBuffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)

    fun u16(offset: Long): Int = buffer.getShort(offset.toInt()).toInt() and 0xFFFF
    fun i16(offset: Long): Int = buffer.getShort(offset.toInt()).toInt()
    fun u32(offset: Long): Long = buffer.getInt(offset.toInt()).toLong() and 0xFFFFFFFFL
    fun fixed1616(offset: Long): Float = buffer.getInt(offset.toInt()) / 65536f

    fun tag(offset: Long): String {
        val i = offset.toInt()
        val chars = CharArray(4) { k -> (bytes[i + k].toInt() and 0xFF).toChar() }
        return String(chars)
    }
}

internal data class GsubSubtableTarget(
    val effectiveType: Int,
    val subtableStart: Long,
)

internal class GsubParseBudget(private val limits: GsubParserLimits) {
    var rulesParsed: Int = 0
        private set
    var steps: Int = 0
        private set
    var error: String? = null
        private set

    fun fail(message: String): Nothing? {
        if (error == null) error = message
        return null
    }

    fun consumeStep(count: Int = 1): Boolean {
        steps += count
        if (steps > limits.maxParseSteps) {
            fail("GSUB parse step budget exceeded ($steps > ${limits.maxParseSteps}).")
            return false
        }
        return true
    }

    fun consumeRule(count: Int = 1): Boolean {
        rulesParsed += count
        if (rulesParsed > limits.maxParsedRules) {
            fail("GSUB parsed rule budget exceeded ($rulesParsed > ${limits.maxParsedRules}).")
            return false
        }
        return true
    }

    val maxNestingDepth: Int get() = limits.maxNestingDepth
}

/**
 * Parser for OpenType GSUB subtables (LookupTypes 1, 2, 3, 4, 5, 6, 8, after
 * Extension 7 unwrapping). Validates every glyph ID against `G = {0 .. numGlyphs - 1}`,
 * models Class 0 accurately as `G \ {g : ClassDef(g) > 0}`, intersects Format-2 first positions
 * with Coverage, records nested `SequenceLookupRecord` targets, and fails closed on any unknown
 * format, bounds error, out-of-domain glyph ID, cycle, or budget exhaustion.
 */
internal object GsubRuleParser {

    fun parseSubtable(
        reader: BigEndianReader,
        tableEnd: Long,
        numGlyphs: Int,
        lookupCount: Int,
        target: GsubSubtableTarget,
        budget: GsubParseBudget,
    ): List<GsubRule>? {
        val subStart = target.subtableStart
        if (subStart + 2L > tableEnd) {
            return budget.fail("GSUB subtable (type ${target.effectiveType}) truncated before format.")
        }
        if (!budget.consumeStep()) return null
        val format = reader.u16(subStart)
        return when (target.effectiveType) {
            1 -> when (format) {
                1 -> parseSingleFormat1(reader, subStart, tableEnd, numGlyphs, budget)
                2 -> parseSingleFormat2(reader, subStart, tableEnd, numGlyphs, budget)
                else -> budget.fail("Unsupported GSUB SingleSubst format $format.")
            }
            2 -> if (format == 1) {
                parseMultipleFormat1(reader, subStart, tableEnd, numGlyphs, budget)
            } else {
                budget.fail("Unsupported GSUB MultipleSubst format $format.")
            }
            3 -> if (format == 1) {
                parseAlternateFormat1(reader, subStart, tableEnd, numGlyphs, budget)
            } else {
                budget.fail("Unsupported GSUB AlternateSubst format $format.")
            }
            4 -> if (format == 1) {
                parseLigatureFormat1(reader, subStart, tableEnd, numGlyphs, budget)
            } else {
                budget.fail("Unsupported GSUB LigatureSubst format $format.")
            }
            5 -> when (format) {
                1 -> parseContextFormat1(reader, subStart, tableEnd, numGlyphs, lookupCount, budget)
                2 -> parseContextFormat2(reader, subStart, tableEnd, numGlyphs, lookupCount, budget)
                3 -> parseContextFormat3(reader, subStart, tableEnd, numGlyphs, lookupCount, budget)
                else -> budget.fail("Unsupported GSUB SequenceContext format $format.")
            }
            6 -> when (format) {
                1 -> parseChainedContextFormat1(reader, subStart, tableEnd, numGlyphs, lookupCount, budget)
                2 -> parseChainedContextFormat2(reader, subStart, tableEnd, numGlyphs, lookupCount, budget)
                3 -> parseChainedContextFormat3(reader, subStart, tableEnd, numGlyphs, lookupCount, budget)
                else -> budget.fail("Unsupported GSUB ChainedSequenceContext format $format.")
            }
            8 -> if (format == 1) {
                parseReverseChainedFormat1(reader, subStart, tableEnd, numGlyphs, budget)
            } else {
                budget.fail("Unsupported GSUB ReverseChainSingleSubst format $format.")
            }
            else -> budget.fail("Unsupported GSUB lookup type ${target.effectiveType}.")
        }
    }

    private fun parseSingleFormat1(
        reader: BigEndianReader,
        subStart: Long,
        tableEnd: Long,
        numGlyphs: Int,
        budget: GsubParseBudget,
    ): List<GsubRule>? {
        if (subStart + 6L > tableEnd) return budget.fail("SingleSubstFormat1 truncated.")
        val covRel = reader.u16(subStart + 2L).toLong()
        val delta = reader.i16(subStart + 4L)
        val orderedCov = parseOrderedCoverage(reader, subStart + covRel, tableEnd, numGlyphs, budget) ?: return null
        val rules = ArrayList<GsubRule>(orderedCov.size)
        for (inGid in orderedCov) {
            if (!budget.consumeRule()) return null
            val outGid = (inGid + delta) and 0xFFFF
            if (outGid >= numGlyphs) {
                return budget.fail("SingleSubstFormat1 output glyph $outGid out of domain [0..$numGlyphs).")
            }
            rules += GsubRule(
                lookupType = 1,
                backtrack = emptyList(),
                input = listOf(GlyphMatcher.Single(inGid)),
                lookahead = emptyList(),
                outputs = intArrayOf(outGid),
                nestedLookupIndices = EMPTY_INT_ARRAY,
                isMultiGlyph = false,
            )
        }
        return rules
    }

    private fun parseSingleFormat2(
        reader: BigEndianReader,
        subStart: Long,
        tableEnd: Long,
        numGlyphs: Int,
        budget: GsubParseBudget,
    ): List<GsubRule>? {
        if (subStart + 6L > tableEnd) return budget.fail("SingleSubstFormat2 truncated.")
        val covRel = reader.u16(subStart + 2L).toLong()
        val glyphCount = reader.u16(subStart + 4L)
        if (subStart + 6L + glyphCount.toLong() * 2L > tableEnd) {
            return budget.fail("SingleSubstFormat2 substitute array out of bounds.")
        }
        val orderedCov = parseOrderedCoverage(reader, subStart + covRel, tableEnd, numGlyphs, budget) ?: return null
        if (orderedCov.size < glyphCount) {
            return budget.fail("SingleSubstFormat2 coverage size ${orderedCov.size} < glyphCount $glyphCount.")
        }
        val rules = ArrayList<GsubRule>(glyphCount)
        for (i in 0 until glyphCount) {
            if (!budget.consumeRule()) return null
            val outGid = reader.u16(subStart + 6L + i.toLong() * 2L)
            if (outGid >= numGlyphs) {
                return budget.fail("SingleSubstFormat2 output glyph $outGid out of domain [0..$numGlyphs).")
            }
            rules += GsubRule(
                lookupType = 1,
                backtrack = emptyList(),
                input = listOf(GlyphMatcher.Single(orderedCov[i])),
                lookahead = emptyList(),
                outputs = intArrayOf(outGid),
                nestedLookupIndices = EMPTY_INT_ARRAY,
                isMultiGlyph = false,
            )
        }
        return rules
    }

    private fun parseMultipleFormat1(
        reader: BigEndianReader,
        subStart: Long,
        tableEnd: Long,
        numGlyphs: Int,
        budget: GsubParseBudget,
    ): List<GsubRule>? {
        if (subStart + 6L > tableEnd) return budget.fail("MultipleSubstFormat1 truncated.")
        val covRel = reader.u16(subStart + 2L).toLong()
        val seqCount = reader.u16(subStart + 4L)
        if (subStart + 6L + seqCount.toLong() * 2L > tableEnd) {
            return budget.fail("MultipleSubstFormat1 sequence offsets out of bounds.")
        }
        val orderedCov = parseOrderedCoverage(reader, subStart + covRel, tableEnd, numGlyphs, budget) ?: return null
        if (orderedCov.size < seqCount) {
            return budget.fail("MultipleSubstFormat1 coverage size ${orderedCov.size} < sequenceCount $seqCount.")
        }
        val rules = ArrayList<GsubRule>(seqCount)
        for (i in 0 until seqCount) {
            if (!budget.consumeRule()) return null
            val seqStart = subStart + reader.u16(subStart + 6L + i.toLong() * 2L).toLong()
            if (seqStart + 2L > tableEnd) return budget.fail("MultipleSubst SequenceTable $i truncated.")
            val glyphCount = reader.u16(seqStart)
            if (seqStart + 2L + glyphCount.toLong() * 2L > tableEnd) {
                return budget.fail("MultipleSubst SequenceTable $i glyphs out of bounds.")
            }
            if (!budget.consumeStep(glyphCount.coerceAtLeast(1))) return null
            if (glyphCount == 0) {
                // Deleting multiple substitution merges this character into a neighbour's cluster -> multi-glyph!
                rules += GsubRule(
                    lookupType = 2,
                    backtrack = emptyList(),
                    input = listOf(GlyphMatcher.Single(orderedCov[i])),
                    lookahead = emptyList(),
                    outputs = EMPTY_INT_ARRAY,
                    nestedLookupIndices = EMPTY_INT_ARRAY,
                    isMultiGlyph = true,
                )
            } else {
                val outs = IntArray(glyphCount)
                for (k in 0 until glyphCount) {
                    val gid = reader.u16(seqStart + 2L + k.toLong() * 2L)
                    if (gid >= numGlyphs) {
                        return budget.fail("MultipleSubst output glyph $gid out of domain [0..$numGlyphs).")
                    }
                    outs[k] = gid
                }
                rules += GsubRule(
                    lookupType = 2,
                    backtrack = emptyList(),
                    input = listOf(GlyphMatcher.Single(orderedCov[i])),
                    lookahead = emptyList(),
                    outputs = distinctSorted(outs),
                    nestedLookupIndices = EMPTY_INT_ARRAY,
                    isMultiGlyph = false,
                )
            }
        }
        return rules
    }

    private fun parseAlternateFormat1(
        reader: BigEndianReader,
        subStart: Long,
        tableEnd: Long,
        numGlyphs: Int,
        budget: GsubParseBudget,
    ): List<GsubRule>? {
        if (subStart + 6L > tableEnd) return budget.fail("AlternateSubstFormat1 truncated.")
        val covRel = reader.u16(subStart + 2L).toLong()
        val altSetCount = reader.u16(subStart + 4L)
        if (subStart + 6L + altSetCount.toLong() * 2L > tableEnd) {
            return budget.fail("AlternateSubstFormat1 offsets out of bounds.")
        }
        val orderedCov = parseOrderedCoverage(reader, subStart + covRel, tableEnd, numGlyphs, budget) ?: return null
        if (orderedCov.size < altSetCount) {
            return budget.fail("AlternateSubstFormat1 coverage size ${orderedCov.size} < altSetCount $altSetCount.")
        }
        val rules = ArrayList<GsubRule>(altSetCount)
        for (i in 0 until altSetCount) {
            if (!budget.consumeRule()) return null
            val altStart = subStart + reader.u16(subStart + 6L + i.toLong() * 2L).toLong()
            if (altStart + 2L > tableEnd) return budget.fail("AlternateSetTable $i truncated.")
            val glyphCount = reader.u16(altStart)
            if (altStart + 2L + glyphCount.toLong() * 2L > tableEnd) {
                return budget.fail("AlternateSetTable $i glyphs out of bounds.")
            }
            if (!budget.consumeStep(glyphCount.coerceAtLeast(1))) return null
            val outs = IntArray(glyphCount)
            for (k in 0 until glyphCount) {
                val gid = reader.u16(altStart + 2L + k.toLong() * 2L)
                if (gid >= numGlyphs) {
                    return budget.fail("AlternateSubst output glyph $gid out of domain [0..$numGlyphs).")
                }
                outs[k] = gid
            }
            rules += GsubRule(
                lookupType = 3,
                backtrack = emptyList(),
                input = listOf(GlyphMatcher.Single(orderedCov[i])),
                lookahead = emptyList(),
                outputs = distinctSorted(outs),
                nestedLookupIndices = EMPTY_INT_ARRAY,
                isMultiGlyph = false,
            )
        }
        return rules
    }

    private fun parseLigatureFormat1(
        reader: BigEndianReader,
        subStart: Long,
        tableEnd: Long,
        numGlyphs: Int,
        budget: GsubParseBudget,
    ): List<GsubRule>? {
        if (subStart + 6L > tableEnd) return budget.fail("LigatureSubstFormat1 truncated.")
        val covRel = reader.u16(subStart + 2L).toLong()
        val ligSetCount = reader.u16(subStart + 4L)
        if (subStart + 6L + ligSetCount.toLong() * 2L > tableEnd) {
            return budget.fail("LigatureSubstFormat1 offsets out of bounds.")
        }
        val orderedCov = parseOrderedCoverage(reader, subStart + covRel, tableEnd, numGlyphs, budget) ?: return null
        if (orderedCov.size < ligSetCount) {
            return budget.fail("LigatureSubstFormat1 coverage size ${orderedCov.size} < ligSetCount $ligSetCount.")
        }

        val rules = ArrayList<GsubRule>(ligSetCount * 2)
        for (i in 0 until ligSetCount) {
            val firstGlyph = orderedCov[i]
            val ligSetStart = subStart + reader.u16(subStart + 6L + i.toLong() * 2L).toLong()
            if (ligSetStart + 2L > tableEnd) return budget.fail("LigatureSet $i truncated.")
            val ligCount = reader.u16(ligSetStart)
            if (ligSetStart + 2L + ligCount.toLong() * 2L > tableEnd) {
                return budget.fail("LigatureSet $i offsets out of bounds.")
            }
            for (k in 0 until ligCount) {
                if (!budget.consumeRule()) return null
                val ligStart = ligSetStart + reader.u16(ligSetStart + 2L + k.toLong() * 2L).toLong()
                if (ligStart + 4L > tableEnd) return budget.fail("LigatureTable truncated.")
                val ligGlyph = reader.u16(ligStart)
                if (ligGlyph >= numGlyphs) {
                    return budget.fail("Ligature output glyph $ligGlyph out of domain [0..$numGlyphs).")
                }
                val compCount = reader.u16(ligStart + 2L)
                if (compCount < 1 || ligStart + 4L + (compCount - 1).toLong() * 2L > tableEnd) {
                    return budget.fail("LigatureTable componentCount $compCount invalid or out of bounds.")
                }
                if (!budget.consumeStep(compCount)) return null
                val inputMatchers = ArrayList<GlyphMatcher>(compCount)
                inputMatchers += GlyphMatcher.Single(firstGlyph)
                for (m in 1 until compCount) {
                    val compGid = reader.u16(ligStart + 4L + (m - 1).toLong() * 2L)
                    if (compGid >= numGlyphs) {
                        return budget.fail("Ligature component glyph $compGid out of domain [0..$numGlyphs).")
                    }
                    inputMatchers += GlyphMatcher.Single(compGid)
                }
                rules += GsubRule(
                    lookupType = 4,
                    backtrack = emptyList(),
                    input = inputMatchers,
                    lookahead = emptyList(),
                    outputs = intArrayOf(ligGlyph),
                    nestedLookupIndices = EMPTY_INT_ARRAY,
                    isMultiGlyph = true,
                )
            }
        }
        return rules
    }

    private fun parseContextFormat1(
        reader: BigEndianReader,
        subStart: Long,
        tableEnd: Long,
        numGlyphs: Int,
        lookupCount: Int,
        budget: GsubParseBudget,
    ): List<GsubRule>? {
        if (subStart + 6L > tableEnd) return budget.fail("SequenceContextFormat1 truncated.")
        val covRel = reader.u16(subStart + 2L).toLong()
        val ruleSetCount = reader.u16(subStart + 4L)
        if (subStart + 6L + ruleSetCount.toLong() * 2L > tableEnd) {
            return budget.fail("SequenceContextFormat1 ruleSet offsets out of bounds.")
        }
        val orderedCov = parseOrderedCoverage(reader, subStart + covRel, tableEnd, numGlyphs, budget) ?: return null
        if (orderedCov.size < ruleSetCount) {
            return budget.fail("SequenceContextFormat1 coverage size ${orderedCov.size} < ruleSetCount $ruleSetCount.")
        }

        val rules = ArrayList<GsubRule>()
        for (i in 0 until ruleSetCount) {
            val rsRel = reader.u16(subStart + 6L + i.toLong() * 2L).toLong()
            if (rsRel == 0L) continue
            val rsStart = subStart + rsRel
            if (rsStart + 2L > tableEnd) return budget.fail("SequenceRuleSet $i truncated.")
            val ruleCount = reader.u16(rsStart)
            if (rsStart + 2L + ruleCount.toLong() * 2L > tableEnd) {
                return budget.fail("SequenceRuleSet $i offsets out of bounds.")
            }
            val firstGlyph = orderedCov[i]
            for (k in 0 until ruleCount) {
                if (!budget.consumeRule()) return null
                val rStart = rsStart + reader.u16(rsStart + 2L + k.toLong() * 2L).toLong()
                if (rStart + 4L > tableEnd) return budget.fail("SequenceRule truncated.")
                val glyphCount = reader.u16(rStart)
                val seqLookupCount = reader.u16(rStart + 2L)
                val seqRecsStart = rStart + 4L + (glyphCount - 1).toLong() * 2L
                if (glyphCount < 1 || seqRecsStart + seqLookupCount.toLong() * 4L > tableEnd) {
                    return budget.fail("SequenceRule out of bounds.")
                }
                if (!budget.consumeStep(glyphCount + seqLookupCount)) return null
                val inputMatchers = ArrayList<GlyphMatcher>(glyphCount)
                inputMatchers += GlyphMatcher.Single(firstGlyph)
                for (m in 1 until glyphCount) {
                    val gid = reader.u16(rStart + 4L + (m - 1).toLong() * 2L)
                    if (gid >= numGlyphs) {
                        return budget.fail("SequenceContextFormat1 glyph $gid out of domain [0..$numGlyphs).")
                    }
                    inputMatchers += GlyphMatcher.Single(gid)
                }
                val nested = parseSeqLookupRecords(reader, seqRecsStart, seqLookupCount, glyphCount, lookupCount, budget)
                    ?: return null
                rules += GsubRule(
                    lookupType = 5,
                    backtrack = emptyList(),
                    input = inputMatchers,
                    lookahead = emptyList(),
                    outputs = EMPTY_INT_ARRAY,
                    nestedLookupIndices = nested,
                    isMultiGlyph = glyphCount > 1,
                )
            }
        }
        return rules
    }

    private fun parseContextFormat2(
        reader: BigEndianReader,
        subStart: Long,
        tableEnd: Long,
        numGlyphs: Int,
        lookupCount: Int,
        budget: GsubParseBudget,
    ): List<GsubRule>? {
        if (subStart + 8L > tableEnd) return budget.fail("SequenceContextFormat2 truncated.")
        val covRel = reader.u16(subStart + 2L).toLong()
        val clsRel = reader.u16(subStart + 4L).toLong()
        val classSetCount = reader.u16(subStart + 6L)
        if (subStart + 8L + classSetCount.toLong() * 2L > tableEnd) {
            return budget.fail("SequenceContextFormat2 classSet offsets out of bounds.")
        }
        val cov = parseSortedCoverage(reader, subStart + covRel, tableEnd, numGlyphs, budget) ?: return null
        val classDef = parseClassDef(reader, subStart + clsRel, tableEnd, numGlyphs, budget) ?: return null

        val rules = ArrayList<GsubRule>()
        for (clsIdx in 0 until classSetCount) {
            val csRel = reader.u16(subStart + 8L + clsIdx.toLong() * 2L).toLong()
            if (csRel == 0L) continue
            val csStart = subStart + csRel
            if (csStart + 2L > tableEnd) return budget.fail("ClassSequenceRuleSet $clsIdx truncated.")
            val ruleCount = reader.u16(csStart)
            if (csStart + 2L + ruleCount.toLong() * 2L > tableEnd) {
                return budget.fail("ClassSequenceRuleSet $clsIdx offsets out of bounds.")
            }
            val firstSet = intersectCoverageWithClass(cov, classDef, clsIdx)
            val firstMatcher = GlyphMatcher.ExplicitSet(firstSet)

            for (k in 0 until ruleCount) {
                if (!budget.consumeRule()) return null
                val rStart = csStart + reader.u16(csStart + 2L + k.toLong() * 2L).toLong()
                if (rStart + 4L > tableEnd) return budget.fail("ClassSequenceRule truncated.")
                val glyphCount = reader.u16(rStart)
                val seqLookupCount = reader.u16(rStart + 2L)
                val seqRecsStart = rStart + 4L + (glyphCount - 1).toLong() * 2L
                if (glyphCount < 1 || seqRecsStart + seqLookupCount.toLong() * 4L > tableEnd) {
                    return budget.fail("ClassSequenceRule out of bounds.")
                }
                if (!budget.consumeStep(glyphCount + seqLookupCount)) return null
                val inputMatchers = ArrayList<GlyphMatcher>(glyphCount)
                inputMatchers += firstMatcher
                var impossible = firstMatcher.isEmpty
                for (m in 1 until glyphCount) {
                    val c = reader.u16(rStart + 4L + (m - 1).toLong() * 2L)
                    val matcher = classDef.matcherForClass(c, numGlyphs)
                    if (matcher.isEmpty) impossible = true
                    inputMatchers += matcher
                }
                val nested = parseSeqLookupRecords(reader, seqRecsStart, seqLookupCount, glyphCount, lookupCount, budget)
                    ?: return null
                if (!impossible) {
                    rules += GsubRule(
                        lookupType = 5,
                        backtrack = emptyList(),
                        input = inputMatchers,
                        lookahead = emptyList(),
                        outputs = EMPTY_INT_ARRAY,
                        nestedLookupIndices = nested,
                        isMultiGlyph = glyphCount > 1,
                    )
                }
            }
        }
        return rules
    }

    private fun parseContextFormat3(
        reader: BigEndianReader,
        subStart: Long,
        tableEnd: Long,
        numGlyphs: Int,
        lookupCount: Int,
        budget: GsubParseBudget,
    ): List<GsubRule>? {
        if (subStart + 6L > tableEnd) return budget.fail("SequenceContextFormat3 truncated.")
        val glyphCount = reader.u16(subStart + 2L)
        val seqLookupCount = reader.u16(subStart + 4L)
        val seqRecsStart = subStart + 6L + glyphCount.toLong() * 2L
        if (glyphCount < 1 || seqRecsStart + seqLookupCount.toLong() * 4L > tableEnd) {
            return budget.fail("SequenceContextFormat3 out of bounds.")
        }
        if (!budget.consumeRule() || !budget.consumeStep(glyphCount + seqLookupCount)) return null
        val inputMatchers = ArrayList<GlyphMatcher>(glyphCount)
        var impossible = false
        for (i in 0 until glyphCount) {
            val covRel = reader.u16(subStart + 6L + i.toLong() * 2L).toLong()
            val cov = parseSortedCoverage(reader, subStart + covRel, tableEnd, numGlyphs, budget) ?: return null
            if (cov.isEmpty()) impossible = true
            inputMatchers += GlyphMatcher.ExplicitSet(cov)
        }
        val nested = parseSeqLookupRecords(reader, seqRecsStart, seqLookupCount, glyphCount, lookupCount, budget)
            ?: return null
        if (impossible) return emptyList()
        return listOf(
            GsubRule(
                lookupType = 5,
                backtrack = emptyList(),
                input = inputMatchers,
                lookahead = emptyList(),
                outputs = EMPTY_INT_ARRAY,
                nestedLookupIndices = nested,
                isMultiGlyph = glyphCount > 1,
            ),
        )
    }

    private fun parseChainedContextFormat1(
        reader: BigEndianReader,
        subStart: Long,
        tableEnd: Long,
        numGlyphs: Int,
        lookupCount: Int,
        budget: GsubParseBudget,
    ): List<GsubRule>? {
        if (subStart + 6L > tableEnd) return budget.fail("ChainedSequenceContextFormat1 truncated.")
        val covRel = reader.u16(subStart + 2L).toLong()
        val ruleSetCount = reader.u16(subStart + 4L)
        if (subStart + 6L + ruleSetCount.toLong() * 2L > tableEnd) {
            return budget.fail("ChainedSequenceContextFormat1 ruleSet offsets out of bounds.")
        }
        val orderedCov = parseOrderedCoverage(reader, subStart + covRel, tableEnd, numGlyphs, budget) ?: return null
        if (orderedCov.size < ruleSetCount) {
            return budget.fail("ChainedSequenceContextFormat1 coverage size ${orderedCov.size} < ruleSetCount $ruleSetCount.")
        }

        val rules = ArrayList<GsubRule>()
        for (i in 0 until ruleSetCount) {
            val rsRel = reader.u16(subStart + 6L + i.toLong() * 2L).toLong()
            if (rsRel == 0L) continue
            val rsStart = subStart + rsRel
            if (rsStart + 2L > tableEnd) return budget.fail("ChainedSequenceRuleSet $i truncated.")
            val ruleCount = reader.u16(rsStart)
            if (rsStart + 2L + ruleCount.toLong() * 2L > tableEnd) {
                return budget.fail("ChainedSequenceRuleSet $i offsets out of bounds.")
            }
            val firstGlyph = orderedCov[i]

            for (k in 0 until ruleCount) {
                if (!budget.consumeRule()) return null
                var p = rsStart + reader.u16(rsStart + 2L + k.toLong() * 2L).toLong()
                if (p + 2L > tableEnd) return budget.fail("ChainedSequenceRule truncated.")
                val backCount = reader.u16(p); p += 2L
                if (p + backCount.toLong() * 2L + 2L > tableEnd) return budget.fail("ChainedSequenceRule backtrack OOB.")
                val backMatchers = ArrayList<GlyphMatcher>(backCount)
                for (m in 0 until backCount) {
                    val gid = reader.u16(p); p += 2L
                    if (gid >= numGlyphs) {
                        return budget.fail("ChainedSequenceRule backtrack glyph $gid out of domain [0..$numGlyphs).")
                    }
                    backMatchers += GlyphMatcher.Single(gid)
                }
                val inputCount = reader.u16(p); p += 2L
                if (inputCount < 1 || p + (inputCount - 1).toLong() * 2L + 2L > tableEnd) {
                    return budget.fail("ChainedSequenceRule input OOB.")
                }
                val inputMatchers = ArrayList<GlyphMatcher>(inputCount)
                inputMatchers += GlyphMatcher.Single(firstGlyph)
                for (m in 1 until inputCount) {
                    val gid = reader.u16(p); p += 2L
                    if (gid >= numGlyphs) {
                        return budget.fail("ChainedSequenceRule input glyph $gid out of domain [0..$numGlyphs).")
                    }
                    inputMatchers += GlyphMatcher.Single(gid)
                }
                val lookCount = reader.u16(p); p += 2L
                if (p + lookCount.toLong() * 2L + 2L > tableEnd) return budget.fail("ChainedSequenceRule lookahead OOB.")
                val lookMatchers = ArrayList<GlyphMatcher>(lookCount)
                for (m in 0 until lookCount) {
                    val gid = reader.u16(p); p += 2L
                    if (gid >= numGlyphs) {
                        return budget.fail("ChainedSequenceRule lookahead glyph $gid out of domain [0..$numGlyphs).")
                    }
                    lookMatchers += GlyphMatcher.Single(gid)
                }
                val seqLookupCount = reader.u16(p); p += 2L
                if (p + seqLookupCount.toLong() * 4L > tableEnd) return budget.fail("ChainedSequenceRule seqLookups OOB.")
                if (!budget.consumeStep(backCount + inputCount + lookCount + seqLookupCount)) return null
                val nested = parseSeqLookupRecords(reader, p, seqLookupCount, inputCount, lookupCount, budget)
                    ?: return null
                rules += GsubRule(
                    lookupType = 6,
                    backtrack = backMatchers,
                    input = inputMatchers,
                    lookahead = lookMatchers,
                    outputs = EMPTY_INT_ARRAY,
                    nestedLookupIndices = nested,
                    isMultiGlyph = (backCount + inputCount + lookCount) > 1,
                )
            }
        }
        return rules
    }

    private fun parseChainedContextFormat2(
        reader: BigEndianReader,
        subStart: Long,
        tableEnd: Long,
        numGlyphs: Int,
        lookupCount: Int,
        budget: GsubParseBudget,
    ): List<GsubRule>? {
        if (subStart + 12L > tableEnd) return budget.fail("ChainedSequenceContextFormat2 truncated.")
        val covRel = reader.u16(subStart + 2L).toLong()
        val backClsRel = reader.u16(subStart + 4L).toLong()
        val inputClsRel = reader.u16(subStart + 6L).toLong()
        val lookClsRel = reader.u16(subStart + 8L).toLong()
        val classSetCount = reader.u16(subStart + 10L)
        if (subStart + 12L + classSetCount.toLong() * 2L > tableEnd) {
            return budget.fail("ChainedSequenceContextFormat2 classSet offsets OOB.")
        }

        val cov = parseSortedCoverage(reader, subStart + covRel, tableEnd, numGlyphs, budget) ?: return null
        val backCls = parseClassDef(reader, subStart + backClsRel, tableEnd, numGlyphs, budget) ?: return null
        val inputCls = parseClassDef(reader, subStart + inputClsRel, tableEnd, numGlyphs, budget) ?: return null
        val lookCls = parseClassDef(reader, subStart + lookClsRel, tableEnd, numGlyphs, budget) ?: return null

        val rules = ArrayList<GsubRule>()
        for (clsIdx in 0 until classSetCount) {
            val csRel = reader.u16(subStart + 12L + clsIdx.toLong() * 2L).toLong()
            if (csRel == 0L) continue
            val csStart = subStart + csRel
            if (csStart + 2L > tableEnd) return budget.fail("ChainedClassSequenceRuleSet $clsIdx truncated.")
            val ruleCount = reader.u16(csStart)
            if (csStart + 2L + ruleCount.toLong() * 2L > tableEnd) {
                return budget.fail("ChainedClassSequenceRuleSet $clsIdx offsets OOB.")
            }

            val firstMatcher = GlyphMatcher.ExplicitSet(intersectCoverageWithClass(cov, inputCls, clsIdx))

            for (k in 0 until ruleCount) {
                if (!budget.consumeRule()) return null
                var p = csStart + reader.u16(csStart + 2L + k.toLong() * 2L).toLong()
                if (p + 2L > tableEnd) return budget.fail("ChainedClassSequenceRule truncated.")
                val backCount = reader.u16(p); p += 2L
                if (p + backCount.toLong() * 2L + 2L > tableEnd) {
                    return budget.fail("ChainedClassSequenceRule backtrack OOB.")
                }
                var impossible = firstMatcher.isEmpty
                val backMatchers = ArrayList<GlyphMatcher>(backCount)
                for (m in 0 until backCount) {
                    val c = reader.u16(p); p += 2L
                    val matcher = backCls.matcherForClass(c, numGlyphs)
                    if (matcher.isEmpty) impossible = true
                    backMatchers += matcher
                }
                val inputCount = reader.u16(p); p += 2L
                if (inputCount < 1 || p + (inputCount - 1).toLong() * 2L + 2L > tableEnd) {
                    return budget.fail("ChainedClassSequenceRule input OOB.")
                }
                val inputMatchers = ArrayList<GlyphMatcher>(inputCount)
                inputMatchers += firstMatcher
                for (m in 1 until inputCount) {
                    val c = reader.u16(p); p += 2L
                    val matcher = inputCls.matcherForClass(c, numGlyphs)
                    if (matcher.isEmpty) impossible = true
                    inputMatchers += matcher
                }
                val lookCount = reader.u16(p); p += 2L
                if (p + lookCount.toLong() * 2L + 2L > tableEnd) {
                    return budget.fail("ChainedClassSequenceRule lookahead OOB.")
                }
                val lookMatchers = ArrayList<GlyphMatcher>(lookCount)
                for (m in 0 until lookCount) {
                    val c = reader.u16(p); p += 2L
                    val matcher = lookCls.matcherForClass(c, numGlyphs)
                    if (matcher.isEmpty) impossible = true
                    lookMatchers += matcher
                }
                val seqLookupCount = reader.u16(p); p += 2L
                if (p + seqLookupCount.toLong() * 4L > tableEnd) {
                    return budget.fail("ChainedClassSequenceRule seqLookups OOB.")
                }
                if (!budget.consumeStep(backCount + inputCount + lookCount + seqLookupCount)) return null
                val nested = parseSeqLookupRecords(reader, p, seqLookupCount, inputCount, lookupCount, budget)
                    ?: return null
                if (!impossible) {
                    rules += GsubRule(
                        lookupType = 6,
                        backtrack = backMatchers,
                        input = inputMatchers,
                        lookahead = lookMatchers,
                        outputs = EMPTY_INT_ARRAY,
                        nestedLookupIndices = nested,
                        isMultiGlyph = (backCount + inputCount + lookCount) > 1,
                    )
                }
            }
        }
        return rules
    }

    private fun parseChainedContextFormat3(
        reader: BigEndianReader,
        subStart: Long,
        tableEnd: Long,
        numGlyphs: Int,
        lookupCount: Int,
        budget: GsubParseBudget,
    ): List<GsubRule>? {
        var p = subStart + 2L
        if (p + 2L > tableEnd) return budget.fail("ChainedSequenceContextFormat3 truncated.")
        val backCount = reader.u16(p); p += 2L
        if (p + backCount.toLong() * 2L + 2L > tableEnd) {
            return budget.fail("ChainedSequenceContextFormat3 backtrack OOB.")
        }
        var impossible = false
        val backMatchers = ArrayList<GlyphMatcher>(backCount)
        for (i in 0 until backCount) {
            val rel = reader.u16(p).toLong(); p += 2L
            val cov = parseSortedCoverage(reader, subStart + rel, tableEnd, numGlyphs, budget) ?: return null
            if (cov.isEmpty()) impossible = true
            backMatchers += GlyphMatcher.ExplicitSet(cov)
        }
        val inputCount = reader.u16(p); p += 2L
        if (inputCount < 1 || p + inputCount.toLong() * 2L + 2L > tableEnd) {
            return budget.fail("ChainedSequenceContextFormat3 input OOB.")
        }
        val inputMatchers = ArrayList<GlyphMatcher>(inputCount)
        for (i in 0 until inputCount) {
            val rel = reader.u16(p).toLong(); p += 2L
            val cov = parseSortedCoverage(reader, subStart + rel, tableEnd, numGlyphs, budget) ?: return null
            if (cov.isEmpty()) impossible = true
            inputMatchers += GlyphMatcher.ExplicitSet(cov)
        }
        val lookCount = reader.u16(p); p += 2L
        if (p + lookCount.toLong() * 2L + 2L > tableEnd) {
            return budget.fail("ChainedSequenceContextFormat3 lookahead OOB.")
        }
        val lookMatchers = ArrayList<GlyphMatcher>(lookCount)
        for (i in 0 until lookCount) {
            val rel = reader.u16(p).toLong(); p += 2L
            val cov = parseSortedCoverage(reader, subStart + rel, tableEnd, numGlyphs, budget) ?: return null
            if (cov.isEmpty()) impossible = true
            lookMatchers += GlyphMatcher.ExplicitSet(cov)
        }
        val seqLookupCount = reader.u16(p); p += 2L
        if (p + seqLookupCount.toLong() * 4L > tableEnd) {
            return budget.fail("ChainedSequenceContextFormat3 seqLookups OOB.")
        }
        if (!budget.consumeRule() || !budget.consumeStep(backCount + inputCount + lookCount + seqLookupCount)) {
            return null
        }
        val nested = parseSeqLookupRecords(reader, p, seqLookupCount, inputCount, lookupCount, budget) ?: return null
        if (impossible) return emptyList()
        return listOf(
            GsubRule(
                lookupType = 6,
                backtrack = backMatchers,
                input = inputMatchers,
                lookahead = lookMatchers,
                outputs = EMPTY_INT_ARRAY,
                nestedLookupIndices = nested,
                isMultiGlyph = (backCount + inputCount + lookCount) > 1,
            ),
        )
    }

    private fun parseReverseChainedFormat1(
        reader: BigEndianReader,
        subStart: Long,
        tableEnd: Long,
        numGlyphs: Int,
        budget: GsubParseBudget,
    ): List<GsubRule>? {
        if (subStart + 6L > tableEnd) return budget.fail("ReverseChainSingleSubstFormat1 truncated.")
        val covRel = reader.u16(subStart + 2L).toLong()
        val orderedCov = parseOrderedCoverage(reader, subStart + covRel, tableEnd, numGlyphs, budget) ?: return null

        var p = subStart + 4L
        val backCount = reader.u16(p); p += 2L
        if (p + backCount.toLong() * 2L + 2L > tableEnd) {
            return budget.fail("ReverseChainSingleSubstFormat1 backtrack OOB.")
        }
        var impossible = orderedCov.isEmpty()
        val backMatchers = ArrayList<GlyphMatcher>(backCount)
        for (i in 0 until backCount) {
            val rel = reader.u16(p).toLong(); p += 2L
            val bCov = parseSortedCoverage(reader, subStart + rel, tableEnd, numGlyphs, budget) ?: return null
            if (bCov.isEmpty()) impossible = true
            backMatchers += GlyphMatcher.ExplicitSet(bCov)
        }
        val lookCount = reader.u16(p); p += 2L
        if (p + lookCount.toLong() * 2L + 2L > tableEnd) {
            return budget.fail("ReverseChainSingleSubstFormat1 lookahead OOB.")
        }
        val lookMatchers = ArrayList<GlyphMatcher>(lookCount)
        for (i in 0 until lookCount) {
            val rel = reader.u16(p).toLong(); p += 2L
            val lCov = parseSortedCoverage(reader, subStart + rel, tableEnd, numGlyphs, budget) ?: return null
            if (lCov.isEmpty()) impossible = true
            lookMatchers += GlyphMatcher.ExplicitSet(lCov)
        }
        val glyphCount = reader.u16(p); p += 2L
        if (p + glyphCount.toLong() * 2L > tableEnd) {
            return budget.fail("ReverseChainSingleSubstFormat1 substitute array OOB.")
        }
        if (orderedCov.size < glyphCount) {
            return budget.fail("ReverseChainSingleSubstFormat1 coverage size ${orderedCov.size} < glyphCount $glyphCount.")
        }
        if (!budget.consumeStep(glyphCount.coerceAtLeast(1))) return null
        val substitutes = IntArray(glyphCount)
        for (i in 0 until glyphCount) {
            val gid = reader.u16(p); p += 2L
            if (gid >= numGlyphs) {
                return budget.fail("ReverseChainSingleSubstFormat1 output glyph $gid out of domain [0..$numGlyphs).")
            }
            substitutes[i] = gid
        }
        if (impossible) return emptyList()

        val hasContext = (backCount + lookCount) > 0
        if (!hasContext) {
            // Context-free single-position reverse-chaining: NOT multi-glyph, exact per-glyph outputs.
            val rules = ArrayList<GsubRule>(glyphCount)
            for (i in 0 until glyphCount) {
                if (!budget.consumeRule()) return null
                rules += GsubRule(
                    lookupType = 8,
                    backtrack = emptyList(),
                    input = listOf(GlyphMatcher.Single(orderedCov[i])),
                    lookahead = emptyList(),
                    outputs = intArrayOf(substitutes[i]),
                    nestedLookupIndices = EMPTY_INT_ARRAY,
                    isMultiGlyph = false,
                )
            }
            return rules
        }

        if (!budget.consumeRule()) return null
        val covSorted = distinctSorted(orderedCov)
        return listOf(
            GsubRule(
                lookupType = 8,
                backtrack = backMatchers,
                input = listOf(GlyphMatcher.ExplicitSet(covSorted)),
                lookahead = lookMatchers,
                outputs = distinctSorted(substitutes),
                nestedLookupIndices = EMPTY_INT_ARRAY,
                isMultiGlyph = true,
            ),
        )
    }

    private fun parseSeqLookupRecords(
        reader: BigEndianReader,
        start: Long,
        count: Int,
        inputGlyphCount: Int,
        lookupCount: Int,
        budget: GsubParseBudget,
    ): IntArray? {
        if (count == 0) return EMPTY_INT_ARRAY
        val indices = IntArray(count)
        for (i in 0 until count) {
            val pos = start + i.toLong() * 4L
            val seqIndex = reader.u16(pos)
            val lookupIdx = reader.u16(pos + 2L)
            if (seqIndex >= inputGlyphCount) {
                return budget.fail("SequenceLookupRecord sequenceIndex $seqIndex >= inputGlyphCount $inputGlyphCount.")
            }
            if (lookupIdx >= lookupCount) {
                return budget.fail("SequenceLookupRecord lookupListIndex $lookupIdx >= lookupCount $lookupCount.")
            }
            indices[i] = lookupIdx
        }
        return distinctSorted(indices)
    }

    private fun parseOrderedCoverage(
        reader: BigEndianReader,
        start: Long,
        tableEnd: Long,
        numGlyphs: Int,
        budget: GsubParseBudget,
    ): IntArray? {
        if (start + 4L > tableEnd) return budget.fail("Coverage table truncated.")
        val format = reader.u16(start)
        return when (format) {
            1 -> {
                val count = reader.u16(start + 2L)
                if (start + 4L + count.toLong() * 2L > tableEnd) {
                    return budget.fail("Coverage Format 1 glyph array out of bounds.")
                }
                if (!budget.consumeStep(count.coerceAtLeast(1))) return null
                IntArray(count) { i ->
                    val gid = reader.u16(start + 4L + i.toLong() * 2L)
                    if (gid >= numGlyphs) {
                        budget.fail("Coverage Format 1 glyph $gid out of domain [0..$numGlyphs).")
                        return null
                    }
                    gid
                }
            }
            2 -> {
                val rangeCount = reader.u16(start + 2L)
                if (start + 4L + rangeCount.toLong() * 6L > tableEnd) {
                    return budget.fail("Coverage Format 2 range array out of bounds.")
                }
                var total = 0
                for (i in 0 until rangeCount) {
                    val pos = start + 4L + i.toLong() * 6L
                    val s = reader.u16(pos)
                    val e = reader.u16(pos + 2L)
                    if (s > e || e >= numGlyphs) {
                        return budget.fail("Coverage Format 2 range [$s..$e] invalid for numGlyphs=$numGlyphs.")
                    }
                    total += (e - s + 1)
                    if (total > numGlyphs) {
                        return budget.fail("Coverage Format 2 total glyph count $total exceeds numGlyphs $numGlyphs.")
                    }
                }
                if (!budget.consumeStep(total.coerceAtLeast(1))) return null
                val out = IntArray(total) { -1 }
                for (i in 0 until rangeCount) {
                    val pos = start + 4L + i.toLong() * 6L
                    val s = reader.u16(pos)
                    val e = reader.u16(pos + 2L)
                    val startCovIdx = reader.u16(pos + 4L)
                    if (startCovIdx + (e - s) >= total) {
                        return budget.fail("Coverage Format 2 startCoverageIndex $startCovIdx out of bounds.")
                    }
                    for (g in s..e) {
                        val idx = startCovIdx + (g - s)
                        if (out[idx] != -1) {
                            return budget.fail("Coverage Format 2 overlapping coverage index $idx.")
                        }
                        out[idx] = g
                    }
                }
                out
            }
            else -> budget.fail("Unsupported Coverage format $format.")
        }
    }

    private fun parseSortedCoverage(
        reader: BigEndianReader,
        start: Long,
        tableEnd: Long,
        numGlyphs: Int,
        budget: GsubParseBudget,
    ): IntArray? {
        val raw = parseOrderedCoverage(reader, start, tableEnd, numGlyphs, budget) ?: return null
        return distinctSorted(raw)
    }

    private class ParsedClassDef(
        private val byClass: Map<Int, IntArray>,
        val allNonZeroGlyphs: IntArray,
    ) {
        fun glyphsForClass(cls: Int): IntArray = byClass[cls] ?: EMPTY_INT_ARRAY

        fun isAssignedNonZero(gid: Int): Boolean = allNonZeroGlyphs.binarySearch(gid) >= 0

        fun matcherForClass(cls: Int, numGlyphs: Int): GlyphMatcher =
            if (cls == 0) {
                GlyphMatcher.Complement(allNonZeroGlyphs, numGlyphs)
            } else {
                GlyphMatcher.ExplicitSet(glyphsForClass(cls))
            }
    }

    private fun parseClassDef(
        reader: BigEndianReader,
        start: Long,
        tableEnd: Long,
        numGlyphs: Int,
        budget: GsubParseBudget,
    ): ParsedClassDef? {
        if (start + 4L > tableEnd) return budget.fail("ClassDef table truncated.")
        val format = reader.u16(start)
        val map = hashMapOf<Int, MutableList<Int>>()
        val nonZero = ArrayList<Int>()
        when (format) {
            1 -> {
                if (start + 6L > tableEnd) return budget.fail("ClassDef Format 1 header truncated.")
                val startGlyph = reader.u16(start + 2L)
                val count = reader.u16(start + 4L)
                if (start + 6L + count.toLong() * 2L > tableEnd) {
                    return budget.fail("ClassDef Format 1 array out of bounds.")
                }
                if (count > 0 && startGlyph + count - 1 >= numGlyphs) {
                    return budget.fail(
                        "ClassDef Format 1 glyph ${startGlyph + count - 1} out of domain [0..$numGlyphs).",
                    )
                }
                if (!budget.consumeStep(count.coerceAtLeast(1))) return null
                for (i in 0 until count) {
                    val cls = reader.u16(start + 6L + i.toLong() * 2L)
                    if (cls != 0) {
                        val gid = startGlyph + i
                        map.getOrPut(cls) { ArrayList() }.add(gid)
                        nonZero += gid
                    }
                }
            }
            2 -> {
                val rangeCount = reader.u16(start + 2L)
                if (start + 4L + rangeCount.toLong() * 6L > tableEnd) {
                    return budget.fail("ClassDef Format 2 range array out of bounds.")
                }
                for (i in 0 until rangeCount) {
                    val pos = start + 4L + i.toLong() * 6L
                    val s = reader.u16(pos)
                    val e = reader.u16(pos + 2L)
                    val cls = reader.u16(pos + 4L)
                    if (s > e || e >= numGlyphs) {
                        return budget.fail("ClassDef Format 2 range [$s..$e] invalid for numGlyphs=$numGlyphs.")
                    }
                    val len = e - s + 1
                    if (!budget.consumeStep(if (cls != 0) len else 1)) return null
                    if (cls != 0) {
                        val list = map.getOrPut(cls) { ArrayList() }
                        for (g in s..e) {
                            list += g
                            nonZero += g
                        }
                    }
                }
            }
            else -> return budget.fail("Unsupported ClassDef format $format.")
        }
        val frozen = HashMap<Int, IntArray>(map.size)
        for ((k, v) in map) {
            frozen[k] = distinctSorted(v.toIntArray())
        }
        return ParsedClassDef(frozen, distinctSorted(nonZero.toIntArray()))
    }

    private fun intersectCoverageWithClass(
        sortedCoverage: IntArray,
        classDef: ParsedClassDef,
        classValue: Int,
    ): IntArray {
        val out = ArrayList<Int>(sortedCoverage.size)
        if (classValue == 0) {
            for (g in sortedCoverage) {
                if (!classDef.isAssignedNonZero(g)) out += g
            }
        } else {
            val clsSet = classDef.glyphsForClass(classValue)
            if (clsSet.isEmpty()) return EMPTY_INT_ARRAY
            for (g in sortedCoverage) {
                if (clsSet.binarySearch(g) >= 0) out += g
            }
        }
        return out.toIntArray()
    }

    internal fun distinctSorted(values: IntArray): IntArray {
        if (values.size <= 1) return values
        val copy = values.copyOf()
        copy.sort()
        var write = 1
        for (i in 1 until copy.size) {
            if (copy[i] != copy[write - 1]) {
                copy[write++] = copy[i]
            }
        }
        return if (write == copy.size) copy else copy.copyOf(write)
    }

    private val EMPTY_INT_ARRAY: IntArray = IntArray(0)
}
