package dev.bnorm.storyboard.text.magic

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import kotlin.math.abs

/**
 * Granularity at which [MagicTextDiff.plan] tokenizes and matches source and target text.
 */
enum class MorphBy {
    Word,
    Character,
    Line,
}

/**
 * A single correspondence entry produced by [MagicTextDiff.plan].
 *
 * Ranges are half-open UTF-16 offsets into the original `from` and `to` [AnnotatedString]s:
 * - Kept match: `before != null && after != null`
 * - Exiting token: `before != null && after == null`
 * - Entering token: `before == null && after != null`
 */
data class TokenMatch(
    val before: TextRange?,
    val after: TextRange?,
    val styleChanged: Boolean,
)

/**
 * Pure logical correspondence planner producing UTF-16 [TextRange] matches for MagicText v2 and
 * layout-free motion planning.
 */
object MagicTextDiff {

    /**
     * Plans token correspondence from [from] to [to] at [by] granularity.
     *
     * Pure whitespace and line-break separators participate in context scoring during matching but
     * are excluded from the returned [TokenMatch] list so trailing whitespace on layout pieces
     * never creates false cross-token bridges.
     */
    fun plan(
        from: AnnotatedString,
        to: AnnotatedString,
        by: MorphBy,
        pruneStopwords: Boolean = true,
    ): List<TokenMatch> {
        val beforeStream = tokenizeStream(from, by)
        val afterStream = tokenizeStream(to, by)

        if (beforeStream.none { !it.isSeparator } && afterStream.none { !it.isSeparator }) {
            return emptyList()
        }

        val beforeNodes = beforeStream.mapIndexed { idx, tok -> DiffNode(idx, tok) }
        val afterNodes = afterStream.mapIndexed { idx, tok -> DiffNode(idx, tok) }
        linkNodes(beforeNodes)
        linkNodes(afterNodes)

        runContextMatching(beforeNodes, afterNodes)

        if (pruneStopwords && by == MorphBy.Word) {
            pruneProseMatches(beforeNodes, afterNodes)
        }

        val exits = mutableListOf<TokenMatch>()
        val matched = mutableListOf<TokenMatch>()
        val enters = mutableListOf<TokenMatch>()

        for (b in beforeNodes) {
            if (b.token.isSeparator) continue
            if (b.partner == null) {
                exits += TokenMatch(before = b.token.range, after = null, styleChanged = false)
            }
        }
        for (a in afterNodes) {
            if (a.token.isSeparator) continue
            val b = a.partner
            if (b != null) {
                matched += TokenMatch(
                    before = b.token.range,
                    after = a.token.range,
                    styleChanged = hasStyleDifference(from, b.token.range, to, a.token.range),
                )
            } else {
                enters += TokenMatch(before = null, after = a.token.range, styleChanged = false)
            }
        }

        val result = ArrayList<TokenMatch>(exits.size + matched.size + enters.size)
        result.addAll(exits)
        result.addAll(matched)
        result.addAll(enters)
        return result
    }

    /**
     * Tokenizes [text] into non-separator semantic UTF-16 [TextRange]s at [by] granularity.
     */
    fun tokenize(text: AnnotatedString, by: MorphBy): List<TextRange> =
        tokenizeStream(text, by).filterNot { it.isSeparator }.map { it.range }

    /**
     * Computes stable positional fragment addresses (`"token#occ"` for target tokens,
     * `"-token#occ"` for exiting source tokens) aligned 1-to-1 with [matches].
     *
     * Both target and exiting source occurrences are numbered across ALL tokens of their
     * respective string in text order so exit addresses never depend on match tie-breaking
     * and always align with tokenizer occurrence indices.
     */
    fun fragmentAddresses(
        from: AnnotatedString,
        to: AnnotatedString,
        matches: List<TokenMatch>,
    ): List<String> {
        // Source occurrences are numbered in source text order across ALL source tokens (kept + exiting).
        val sourceRangesSorted = matches.mapNotNull { it.before }.sortedBy { it.start }
        val beforeCounts = HashMap<String, Int>()
        val beforeIndexByRange = HashMap<TextRange, String>()
        for (br in sourceRangesSorted) {
            val slice = from.text.substring(br.start, br.end)
            val occ = beforeCounts[slice] ?: 0
            beforeCounts[slice] = occ + 1
            beforeIndexByRange[br] = "-$slice#$occ"
        }

        // Target occurrences are numbered in target text order across all target tokens.
        val targetRangesSorted = matches.mapNotNull { it.after }.sortedBy { it.start }
        val targetCounts = HashMap<String, Int>()
        val targetAddressByRange = HashMap<TextRange, String>()
        for (ar in targetRangesSorted) {
            val slice = to.text.substring(ar.start, ar.end)
            val occ = targetCounts[slice] ?: 0
            targetCounts[slice] = occ + 1
            targetAddressByRange[ar] = "$slice#$occ"
        }

        return matches.map { m ->
            val ar = m.after
            if (ar != null) {
                targetAddressByRange.getValue(ar)
            } else {
                beforeIndexByRange.getValue(checkNotNull(m.before))
            }
        }
    }

    /**
     * Splits [text] into extended Unicode grapheme clusters (UAX #29) represented as half-open
     * UTF-16 [TextRange]s covering `[0, text.length)` without gaps or overlaps.
     */
    fun splitGraphemeClusters(text: String): List<TextRange> {
        val len = text.length
        if (len == 0) return emptyList()
        val result = ArrayList<TextRange>(len)
        var offset = 0
        while (offset < len) {
            val end = nextGraphemeClusterEnd(text, offset)
            result += TextRange(offset, end)
            offset = end
        }
        return result
    }

    // -------------------------------------------------------------------------
    // Tokenization
    // -------------------------------------------------------------------------

    private data class StreamToken(
        val range: TextRange,
        val text: String,
        val isSeparator: Boolean,
        val isNewline: Boolean,
    )

    private fun tokenizeStream(source: AnnotatedString, by: MorphBy): List<StreamToken> {
        val s = source.text
        if (s.isEmpty()) return emptyList()
        return when (by) {
            MorphBy.Line -> tokenizeLines(s)
            MorphBy.Character -> tokenizeCharacters(s)
            MorphBy.Word -> tokenizeWords(s)
        }
    }

    private fun tokenizeLines(text: String): List<StreamToken> {
        val out = ArrayList<StreamToken>()
        var lineStart = 0
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            if (ch == '\n' || ch == '\r') {
                if (i > lineStart) {
                    val range = TextRange(lineStart, i)
                    out += StreamToken(range, text.substring(lineStart, i), isSeparator = false, isNewline = false)
                }
                val nlEnd = if (ch == '\r' && i + 1 < text.length && text[i + 1] == '\n') i + 2 else i + 1
                out += StreamToken(TextRange(i, nlEnd), "\n", isSeparator = true, isNewline = true)
                i = nlEnd
                lineStart = nlEnd
            } else {
                i++
            }
        }
        if (lineStart < text.length) {
            out += StreamToken(
                TextRange(lineStart, text.length),
                text.substring(lineStart, text.length),
                isSeparator = false,
                isNewline = false,
            )
        }
        return out
    }

    private fun tokenizeCharacters(text: String): List<StreamToken> {
        val clusters = splitGraphemeClusters(text)
        val out = ArrayList<StreamToken>(clusters.size)
        for (range in clusters) {
            val slice = text.substring(range.start, range.end)
            val isNl = slice == "\n" || slice == "\r" || slice == "\r\n"
            val isWs = isNl || slice.all { it.isWhitespace() }
            out += StreamToken(
                range = range,
                text = if (isNl) "\n" else slice,
                isSeparator = isWs,
                isNewline = isNl,
            )
        }
        return out
    }

    private fun tokenizeWords(text: String): List<StreamToken> {
        val out = ArrayList<StreamToken>()
        var i = 0
        val len = text.length
        while (i < len) {
            val ch = text[i]
            // 1. Line breaks stay isolated for line-scoped context matching.
            if (ch == '\n' || ch == '\r') {
                val nlEnd = if (ch == '\r' && i + 1 < len && text[i + 1] == '\n') i + 2 else i + 1
                out += StreamToken(TextRange(i, nlEnd), "\n", isSeparator = true, isNewline = true)
                i = nlEnd
                continue
            }

            // 2. Horizontal whitespace run.
            if (ch.isWhitespace()) {
                var end = i + 1
                while (end < len && text[end].isWhitespace() && text[end] != '\n' && text[end] != '\r') {
                    end++
                }
                out += StreamToken(TextRange(i, end), text.substring(i, end), isSeparator = true, isNewline = false)
                i = end
                continue
            }

            // 3. Multi-character programming operator symbols.
            val sym = matchSymbolWordAt(text, i)
            if (sym != null) {
                val end = i + sym.length
                out += StreamToken(TextRange(i, end), sym, isSeparator = false, isNewline = false)
                i = end
                continue
            }

            // 4. Word run (letters, digits, underscore, combining marks, supplementary letters/digits).
            val cp = codePointAt(text, i)
            if (isWordCodePoint(cp)) {
                var end = nextGraphemeClusterEnd(text, i)
                while (end < len) {
                    val nextCp = codePointAt(text, end)
                    if (!isWordCodePoint(nextCp)) break
                    end = nextGraphemeClusterEnd(text, end)
                }
                out += StreamToken(TextRange(i, end), text.substring(i, end), isSeparator = false, isNewline = false)
                i = end
                continue
            }

            // 5. Single grapheme cluster (punctuation, emoji ZWJ sequence, regional flag, symbol).
            val end = nextGraphemeClusterEnd(text, i)
            out += StreamToken(TextRange(i, end), text.substring(i, end), isSeparator = false, isNewline = false)
            i = end
        }
        return out
    }

    private val SYMBOL_WORDS_ORDERED: List<String> = listOf(
        "===", "!==", "\"\"\"",
        "&&", "||", "==", "!=", ">=", "<=", "!!", "?:", "::", "->",
    )

    private fun matchSymbolWordAt(text: String, index: Int): String? {
        for (sym in SYMBOL_WORDS_ORDERED) {
            if (text.regionMatches(index, sym, 0, sym.length)) return sym
        }
        return null
    }

    // -------------------------------------------------------------------------
    // Context-aware correspondence matcher
    // -------------------------------------------------------------------------

    private class DiffNode(
        val index: Int,
        val token: StreamToken,
    ) : Comparable<DiffNode> {
        var partner: DiffNode? = null
        var prev: DiffNode? = null
        var next: DiffNode? = null

        fun step(forward: Boolean, stopOnLineBreaks: Boolean): DiffNode? {
            var cur = if (forward) next else prev
            while (cur != null) {
                if (!cur.token.isNewline) return cur
                if (stopOnLineBreaks) return null
                cur = if (forward) cur.next else cur.prev
            }
            return null
        }

        override fun compareTo(other: DiffNode): Int = index.compareTo(other.index)
    }

    private data class NodePair(val before: DiffNode, val after: DiffNode) : Comparable<NodePair> {
        fun step(forward: Boolean, stopOnLineBreaks: Boolean): NodePair? {
            val nb = before.step(forward, stopOnLineBreaks) ?: return null
            val na = after.step(forward, stopOnLineBreaks) ?: return null
            return NodePair(nb, na)
        }

        fun canPair(): Boolean =
            before.partner == null && after.partner == null && before.token.text == after.token.text

        override fun compareTo(other: NodePair): Int = when {
            before < other.before && after < other.after -> -1
            before > other.before && after > other.after -> 1
            before == other.before && after == other.after -> 0
            else -> before.index.compareTo(other.before.index)
        }
    }

    private data class PairRegion(val start: NodePair, val end: NodePair) {
        fun widen(pair: NodePair): PairRegion = when {
            pair.before < start.before && pair.after < start.after -> PairRegion(pair, end)
            pair.before > end.before && pair.after > end.after -> PairRegion(start, pair)
            else -> this
        }
    }

    private fun linkNodes(nodes: List<DiffNode>) {
        var prev: DiffNode? = null
        for (n in nodes) {
            n.prev = prev
            prev?.next = n
            prev = n
        }
    }

    private fun runContextMatching(
        beforeList: List<DiffNode>,
        afterList: List<DiffNode>,
    ) {
        val regions = mutableListOf<PairRegion>()
        val ignoredKeys = hashSetOf<String>()

        fun link(pair: NodePair) {
            pair.before.partner = pair.after
            pair.after.partner = pair.before
        }

        fun extend(pair: NodePair, forward: Boolean, stopOnLineBreaks: Boolean): NodePair {
            var last = pair
            while (true) {
                val next = last.step(forward, stopOnLineBreaks) ?: break
                if (!next.canPair()) break
                link(next)
                last = next
            }
            return last
        }

        fun countExtend(pair: NodePair, forward: Boolean, stopOnLineBreaks: Boolean): Int {
            var count = 0
            var cur = pair
            while (count < 32) {
                val next = cur.step(forward, stopOnLineBreaks) ?: break
                if (!next.canPair()) break
                count++
                cur = next
            }
            return count
        }

        fun associateCenter(pair: NodePair, stopOnLineBreaks: Boolean): PairRegion {
            ignoredKeys.clear()
            link(pair)
            var region = PairRegion(pair, pair)
            region = region.widen(extend(pair, forward = true, stopOnLineBreaks = stopOnLineBreaks))
            region = region.widen(extend(pair, forward = false, stopOnLineBreaks = stopOnLineBreaks))
            return region
        }

        fun findBestMultiMatch(
            sharedKeys: Set<String>,
            groupedBefore: Map<String, List<DiffNode>>,
            groupedAfter: Map<String, List<DiffNode>>,
            stopOnLineBreaks: Boolean,
            skipTies: Boolean,
        ): PairRegion? {
            val totalBefore = beforeList.size.coerceAtLeast(1).toFloat()
            val totalAfter = afterList.size.coerceAtLeast(1).toFloat()

            for (key in sharedKeys) {
                val bItems = groupedBefore.getValue(key)
                val aItems = groupedAfter.getValue(key)
                if (bItems.size * aItems.size > 256) continue

                var bestScore = -1
                var bestDist = Float.MAX_VALUE
                var bestPair: NodePair? = null
                var tiedTop = false

                for (b in bItems) {
                    val bPos = b.index / totalBefore
                    for (a in aItems) {
                        val pair = NodePair(b, a)
                        val strength = countExtend(pair, true, stopOnLineBreaks) +
                            countExtend(pair, false, stopOnLineBreaks)
                        val dist = abs(bPos - (a.index / totalAfter))
                        when {
                            strength > bestScore -> {
                                bestScore = strength
                                bestDist = dist
                                bestPair = pair
                                tiedTop = false
                            }
                            strength == bestScore -> {
                                if (skipTies) {
                                    tiedTop = true
                                } else if (dist < bestDist) {
                                    bestDist = dist
                                    bestPair = pair
                                }
                            }
                        }
                    }
                }

                if (bestPair != null) {
                    if (skipTies && tiedTop) {
                        ignoredKeys += key
                    } else {
                        return associateCenter(bestPair, stopOnLineBreaks)
                    }
                }
            }
            return null
        }

        fun matchPass(stopOnLineBreaks: Boolean, skipTies: Boolean) {
            ignoredKeys.clear()
            while (true) {
                var groupedBefore: Map<String, List<DiffNode>>
                var groupedAfter: Map<String, List<DiffNode>>

                while (true) {
                    groupedBefore = beforeList.filter { it.partner == null && !it.token.isNewline }
                        .groupBy { it.token.text }
                    groupedAfter = afterList.filter { it.partner == null && !it.token.isNewline }
                        .groupBy { it.token.text }

                    val uniqueBefore = groupedBefore.filter { it.value.size == 1 }.keys
                    val uniqueAfter = groupedAfter.filter { it.value.size == 1 }.keys
                    val sharedUnique = uniqueBefore intersect uniqueAfter
                    if (sharedUnique.isEmpty()) break

                    for (key in sharedUnique) {
                        val b = groupedBefore.getValue(key)[0]
                        val a = groupedAfter.getValue(key)[0]
                        if (b.partner == null && a.partner == null) {
                            regions += associateCenter(NodePair(b, a), stopOnLineBreaks)
                        }
                    }
                }

                val multiBefore = groupedBefore.filter { it.value.size <= 16 }.keys
                val multiAfter = groupedAfter.filter { it.value.size <= 16 }.keys
                val sharedMulti = (multiBefore intersect multiAfter) - ignoredKeys
                if (sharedMulti.isEmpty()) break

                val region = findBestMultiMatch(
                    sharedKeys = sharedMulti,
                    groupedBefore = groupedBefore,
                    groupedAfter = groupedAfter,
                    stopOnLineBreaks = stopOnLineBreaks,
                    skipTies = skipTies,
                ) ?: break
                regions += region
            }
        }

        fun widenAll() {
            val snapshot = regions.toList()
            regions.clear()
            for (r in snapshot) {
                var wider = r
                wider = wider.widen(extend(wider.end, forward = true, stopOnLineBreaks = false))
                wider = wider.widen(extend(wider.start, forward = false, stopOnLineBreaks = false))
                regions += wider
            }
        }

        fun matchEdges() {
            if (beforeList.isEmpty() || afterList.isEmpty()) return
            val first = NodePair(beforeList.first(), afterList.first())
            if (first.canPair()) {
                link(first)
                extend(first, forward = true, stopOnLineBreaks = false)
            }
            val last = NodePair(beforeList.last(), afterList.last())
            if (last.canPair()) {
                link(last)
                extend(last, forward = false, stopOnLineBreaks = false)
            }
        }

        fun matchHighFrequencyInOrder() {
            // Remaining unmatched tokens (e.g., >16 occurrences of 'e' in character mode):
            // match greedily in document order per distinct token text so no valid correspondence is dropped.
            val remainingBefore = beforeList.filter { it.partner == null && !it.token.isNewline }
                .groupBy { it.token.text }
            val remainingAfter = afterList.filter { it.partner == null && !it.token.isNewline }
                .groupBy { it.token.text }
            for ((text, bList) in remainingBefore) {
                val aList = remainingAfter[text] ?: continue
                val pairs = minOf(bList.size, aList.size)
                for (i in 0 until pairs) {
                    val p = NodePair(bList[i], aList[i])
                    if (p.canPair()) link(p)
                }
            }
        }

        matchPass(stopOnLineBreaks = true, skipTies = true)
        widenAll()
        matchEdges()
        matchPass(stopOnLineBreaks = false, skipTies = false)
        matchHighFrequencyInOrder()
    }

    // -------------------------------------------------------------------------
    // Prose stopword & detached-punctuation pruning (Word mode only)
    // -------------------------------------------------------------------------

    private const val PROSE_PUNCTUATION = ".,;:!?"

    private val PROSE_STOP_WORDS = setOf(
        "a", "an", "the", "to", "of", "in", "with", "on", "at", "by",
        "for", "is", "are", "was", "be", "or", "and", "as", "it",
    )

    private val LOW_SIGNAL_STOP_WORDS = setOf(
        "a", "an", "the", "to", "in", "of", "with", "for", "on", "at", "by", "is", "and", "or",
    )

    private fun DiffNode.isBlankToken(): Boolean = token.isSeparator && !token.isNewline
    private fun DiffNode.isWordToken(): Boolean = token.text.any { it.isLetterOrDigit() }
    private fun DiffNode.isProsePunctuation(): Boolean =
        token.text.isNotEmpty() && token.text.all { it in PROSE_PUNCTUATION }
    private fun DiffNode.isSingleCharPunctuation(): Boolean =
        token.text.length == 1 && !token.text[0].isLetterOrDigit() && !token.text[0].isWhitespace()
    private fun DiffNode.isProseStopWord(): Boolean = token.text.lowercase() in PROSE_STOP_WORDS
    private fun DiffNode.isLowSignalMatch(): Boolean =
        token.text.lowercase() in LOW_SIGNAL_STOP_WORDS || isSingleCharPunctuation()

    private fun DiffNode.adjacentWordOnLine(forward: Boolean): DiffNode? {
        var cur = step(forward, stopOnLineBreaks = true)
        while (cur != null) {
            if (cur.isWordToken()) return cur
            if (!cur.isBlankToken() && !cur.isProsePunctuation()) return cur
            cur = cur.step(forward, stopOnLineBreaks = true)
        }
        return null
    }

    private fun DiffNode.unpair() {
        val other = partner ?: return
        partner = null
        other.partner = null
    }

    private fun pruneProseMatches(
        beforeNodes: List<DiffNode>,
        afterNodes: List<DiffNode>,
    ) {
        val hasWords = beforeNodes.any { it.isWordToken() } || afterNodes.any { it.isWordToken() }
        if (!hasWords) return

        var commonPrefix = 0
        while (
            commonPrefix < beforeNodes.size &&
            commonPrefix < afterNodes.size &&
            beforeNodes[commonPrefix].partner === afterNodes[commonPrefix]
        ) {
            commonPrefix++
        }

        // 1. Drop detached sentence punctuation outside common prefix.
        for (b in beforeNodes) {
            val a = b.partner ?: continue
            if (b.index < commonPrefix || !b.isProsePunctuation()) continue
            val leftAnchored = b.prev != null && a.prev != null &&
                !b.prev!!.isBlankToken() && b.prev!!.partner === a.prev
            val rightAnchored = b.next != null && a.next != null &&
                !b.next!!.isBlankToken() && b.next!!.partner === a.next
            if (!leftAnchored && !rightAnchored) {
                b.unpair()
            }
        }

        var commonSuffix = 0
        while (
            commonSuffix < beforeNodes.size - commonPrefix &&
            commonSuffix < afterNodes.size - commonPrefix &&
            beforeNodes[beforeNodes.size - 1 - commonSuffix].partner === afterNodes[afterNodes.size - 1 - commonSuffix]
        ) {
            commonSuffix++
        }
        fun isExemptEdge(index: Int): Boolean =
            index < commonPrefix || index >= beforeNodes.size - commonSuffix

        // 2. Drop isolated prose stopwords in multi-word sentences.
        val beforeWordCount = beforeNodes.count { it.isWordToken() }
        val afterWordCount = afterNodes.count { it.isWordToken() }
        if (beforeWordCount >= 3 && afterWordCount >= 3) {
            for (b in beforeNodes) {
                val a = b.partner ?: continue
                if (isExemptEdge(b.index) || !b.isProseStopWord()) continue
                val bPrev = b.adjacentWordOnLine(forward = false)
                val aPrev = a.adjacentWordOnLine(forward = false)
                val prevAnchored = bPrev != null && aPrev != null && bPrev.partner === aPrev
                val bNext = b.adjacentWordOnLine(forward = true)
                val aNext = a.adjacentWordOnLine(forward = true)
                val nextAnchored = bNext != null && aNext != null && bNext.partner === aNext
                if (!prevAnchored && !nextAnchored) {
                    b.unpair()
                }
            }
        }

        // 3. Drop low-signal-only matches covering < 20% of non-whitespace characters (outside edges).
        val beforeNonWs = beforeNodes.sumOf { n -> n.token.text.count { !it.isWhitespace() } }
        val afterNonWs = afterNodes.sumOf { n -> n.token.text.count { !it.isWhitespace() } }
        val totalNonWs = maxOf(beforeNonWs, afterNonWs)
        val matchedVisible = beforeNodes.filter { it.partner != null && !it.token.isSeparator }
        if (totalNonWs > 0 && matchedVisible.isNotEmpty() && matchedVisible.all { it.isLowSignalMatch() }) {
            val matchedChars = matchedVisible.sumOf { n -> n.token.text.count { !it.isWhitespace() } }
            if (matchedChars * 5 < totalNonWs) {
                for (b in matchedVisible) {
                    if (!isExemptEdge(b.index)) b.unpair()
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Effective style difference detection
    // -------------------------------------------------------------------------

    private val EMPTY_SPAN = SpanStyle()
    private val EMPTY_PARAGRAPH = ParagraphStyle()

    private fun hasStyleDifference(
        from: AnnotatedString,
        before: TextRange,
        to: AnnotatedString,
        after: TextRange,
    ): Boolean {
        val len = before.length
        if (len != after.length) return true
        val fromSlice = from.subSequence(before.start, before.end)
        val toSlice = to.subSequence(after.start, after.end)

        val fromPara = fromSlice.paragraphStyles.filter { it.item != EMPTY_PARAGRAPH }
        val toPara = toSlice.paragraphStyles.filter { it.item != EMPTY_PARAGRAPH }
        if (fromPara != toPara) return true

        val fromSpans = fromSlice.spanStyles.filter { it.item != EMPTY_SPAN && it.start < it.end }
        val toSpans = toSlice.spanStyles.filter { it.item != EMPTY_SPAN && it.start < it.end }
        if (fromSpans.isEmpty() && toSpans.isEmpty()) return false
        if (fromSpans == toSpans) return false

        // Compare merged effective SpanStyle on every boundary interval so differently split
        // identical spans compare equal.
        val boundaries = mutableSetOf(0, len)
        for (s in fromSpans) {
            boundaries += s.start.coerceIn(0, len)
            boundaries += s.end.coerceIn(0, len)
        }
        for (s in toSpans) {
            boundaries += s.start.coerceIn(0, len)
            boundaries += s.end.coerceIn(0, len)
        }
        val pts = boundaries.sorted()
        for (idx in 0 until pts.size - 1) {
            val a = pts[idx]
            val b = pts[idx + 1]
            if (a >= b) continue
            val effFrom = effectiveSpanAt(fromSpans, a, b)
            val effTo = effectiveSpanAt(toSpans, a, b)
            if (effFrom != effTo) return true
        }
        return false
    }

    private fun effectiveSpanAt(
        spans: List<AnnotatedString.Range<SpanStyle>>,
        start: Int,
        end: Int,
    ): SpanStyle {
        var acc = EMPTY_SPAN
        for (s in spans) {
            if (s.start <= start && s.end >= end) {
                acc = acc.merge(s.item)
            }
        }
        return acc
    }

    // -------------------------------------------------------------------------
    // Extended Unicode grapheme cluster segmentation (UAX #29)
    // -------------------------------------------------------------------------

    private fun nextGraphemeClusterEnd(text: String, start: Int): Int {
        val len = text.length
        if (start >= len) return len

        val firstCp = codePointAt(text, start)
        var end = advanceCodePoint(text, start)

        // GB3: CR x LF
        if (firstCp == '\r'.code && end < len && text[end] == '\n') {
            return end + 1
        }
        // GB4: (Control | CR | LF) ÷
        if (firstCp == '\n'.code || firstCp == '\r'.code) {
            return end
        }

        // GB12/GB13: Regional Indicator pairs
        if (isRegionalIndicator(firstCp)) {
            if (end < len && isRegionalIndicator(codePointAt(text, end))) {
                end = advanceCodePoint(text, end)
            }
            return consumeTrailingExtends(text, end)
        }

        // GB6/7/8: Hangul syllable Jamo sequences (L* V+ T* | LV V* T* | LVT T*)
        var prevHangul = hangulType(firstCp)
        if (prevHangul != HangulType.None) {
            while (end < len) {
                val nextType = hangulType(codePointAt(text, end))
                val join = when (prevHangul) {
                    HangulType.L -> nextType == HangulType.L || nextType == HangulType.V ||
                        nextType == HangulType.LV || nextType == HangulType.LVT
                    HangulType.LV, HangulType.V -> nextType == HangulType.V || nextType == HangulType.T
                    HangulType.LVT, HangulType.T -> nextType == HangulType.T
                    HangulType.None -> false
                }
                if (!join) break
                end = advanceCodePoint(text, end)
                prevHangul = nextType
            }
            return consumeTrailingExtends(text, end)
        }

        // GB9 / GB9a / GB9b / GB9c / GB11: Extend, SpacingMark, ZWJ sequences, Emoji modifiers, Tag sequences, Keycaps
        var prevCp = firstCp
        while (end < len) {
            val cp = codePointAt(text, end)
            when {
                isExtendOrSpacingMark(cp) || isEmojiModifier(cp) || isTagSpecChar(cp) || cp == 0xE007F -> {
                    end = advanceCodePoint(text, end)
                    prevCp = cp
                }
                cp == 0x200D -> {
                    // ZWJ: attach ZWJ and, if followed by a non-control/non-whitespace code point, keep joining.
                    end = advanceCodePoint(text, end)
                    prevCp = cp
                    if (end < len) {
                        val afterZwj = codePointAt(text, end)
                        if (afterZwj != '\n'.code && afterZwj != '\r'.code && !isAsciiSpace(afterZwj)) {
                            end = advanceCodePoint(text, end)
                            prevCp = afterZwj
                        }
                    }
                }
                isIndicVirama(prevCp) && isIndicConsonant(cp) -> {
                    // Indic conjunct cluster across Virama
                    end = advanceCodePoint(text, end)
                    prevCp = cp
                }
                else -> break
            }
        }
        return end
    }

    private fun consumeTrailingExtends(text: String, start: Int): Int {
        var end = start
        while (end < text.length) {
            val cp = codePointAt(text, end)
            if (isExtendOrSpacingMark(cp) || isEmojiModifier(cp)) {
                end = advanceCodePoint(text, end)
            } else {
                break
            }
        }
        return end
    }

    private enum class HangulType { None, L, V, T, LV, LVT }

    private fun hangulType(cp: Int): HangulType = when {
        cp in 0x1100..0x115F || cp in 0xA960..0xA97C -> HangulType.L
        cp in 0x1160..0x11A7 || cp in 0xD7B0..0xD7C6 -> HangulType.V
        cp in 0x11A8..0x11FF || cp in 0xD7CB..0xD7FB -> HangulType.T
        cp in 0xAC00..0xD7A3 -> if ((cp - 0xAC00) % 28 == 0) HangulType.LV else HangulType.LVT
        else -> HangulType.None
    }

    private fun codePointAt(text: String, index: Int): Int {
        val high = text[index]
        if (high.isHighSurrogate() && index + 1 < text.length) {
            val low = text[index + 1]
            if (low.isLowSurrogate()) {
                return 0x10000 + ((high.code - 0xD800) shl 10) + (low.code - 0xDC00)
            }
        }
        return high.code
    }

    private fun advanceCodePoint(text: String, index: Int): Int =
        if (text[index].isHighSurrogate() && index + 1 < text.length && text[index + 1].isLowSurrogate()) {
            index + 2
        } else {
            index + 1
        }

    private fun isAsciiSpace(cp: Int): Boolean = cp == ' '.code || cp == '\t'.code

    private fun isRegionalIndicator(cp: Int): Boolean = cp in 0x1F1E6..0x1F1FF

    private fun isEmojiModifier(cp: Int): Boolean = cp in 0x1F3FB..0x1F3FF

    private fun isTagSpecChar(cp: Int): Boolean = cp in 0xE0020..0xE007E

    private fun isIndicVirama(cp: Int): Boolean =
        cp == 0x094D || cp == 0x09CD || cp == 0x0A4D || cp == 0x0ACD ||
            cp == 0x0B4D || cp == 0x0BCD || cp == 0x0C4D || cp == 0x0CCD || cp == 0x0D4D

    private fun isIndicConsonant(cp: Int): Boolean =
        cp in 0x0915..0x0939 || cp in 0x0958..0x095F || cp in 0x0995..0x09B9 ||
            cp in 0x0A15..0x0A39 || cp in 0x0A95..0x0AB9 || cp in 0x0B15..0x0B39 ||
            cp in 0x0B95..0x0BB9 || cp in 0x0C15..0x0C39 || cp in 0x0C95..0x0CB9 || cp in 0x0D15..0x0D3A

    private fun isExtendOrSpacingMark(cp: Int): Boolean {
        if (cp == 0x200C) return true // ZWNJ
        if (cp in 0xFE00..0xFE0F || cp in 0xE0100..0xE01EF) return true // Variation Selectors
        if (cp == 0x20E3) return true // Combining Enclosing Keycap
        if (cp <= 0xFFFF) {
            val cat = cp.toChar().category
            if (cat == CharCategory.NON_SPACING_MARK ||
                cat == CharCategory.COMBINING_SPACING_MARK ||
                cat == CharCategory.ENCLOSING_MARK
            ) {
                return true
            }
        }
        return false
    }

    private fun isWordCodePoint(cp: Int): Boolean {
        if (cp == '_'.code) return true
        if (cp <= 0xFFFF) {
            val ch = cp.toChar()
            if (ch.isLetterOrDigit()) return true
            val cat = ch.category
            return cat == CharCategory.NON_SPACING_MARK ||
                cat == CharCategory.COMBINING_SPACING_MARK ||
                cat == CharCategory.ENCLOSING_MARK
        }
        // Supplementary alphanumeric scripts (Mathematical Alphanumeric, CJK Extensions B–H)
        return cp in 0x1D400..0x1D7FF || cp in 0x20000..0x323AF
    }
}
