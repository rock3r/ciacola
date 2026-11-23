package dev.sebastiano.ciacola.syntax.antlr.yaml

import org.antlr.v4.kotlinruntime.CharStream
import org.antlr.v4.kotlinruntime.IntStream
import org.antlr.v4.kotlinruntime.Lexer

/**
 * Pure Kotlin Multiplatform base lexer for [YamlLexer], providing linear-time
 * indentation-aware block scalar scanning, plain scalar boundary detection,
 * flow-collection context tracking, and line-start directive/marker predicates.
 */
internal abstract class YamlLexerBase(input: CharStream) : Lexer(input) {
    private var flowDepth: Int = 0
    private var lastNonHiddenWasQuoted: Boolean = false
    private var blockParentIndent: Int = -1
    private var blockExplicitIndent: Int = 0
    private var pendingBlockScalarEndCp: Int = -1
    private var lineStartIndex: Int = 0
    private var inLineLeadingIndicators: Boolean = true
    private var lineLastIndicatorIndent: Int = -1
    private var lineKeyIndent: Int = -1
    private var lineHasMappingColon: Boolean = false
    private var pendingBlockOwnerIndent: Int = -1
    private var plainScalarMinIndent: Int = -1

    override fun reset() {
        super.reset()
        flowDepth = 0
        lastNonHiddenWasQuoted = false
        blockParentIndent = -1
        blockExplicitIndent = 0
        pendingBlockScalarEndCp = -1
        lineStartIndex = 0
        inLineLeadingIndicators = true
        lineLastIndicatorIndent = -1
        lineKeyIndent = -1
        lineHasMappingColon = false
        pendingBlockOwnerIndent = -1
        plainScalarMinIndent = -1
    }

    protected fun onWs() {
        if (lineStartIndex == 0 && _tokenStartCharIndex == 0 && _input.index() > 0) {
            val firstCp = _input.LA(-(_input.index()))
            if (firstCp == BOM_CODE_POINT) {
                lineStartIndex = 1
            }
        }
    }

    protected fun onNewline() {
        lineStartIndex = _input.index()
        inLineLeadingIndicators = true
        lineLastIndicatorIndent = -1
        lineKeyIndent = -1
        lineHasMappingColon = false
    }

    protected fun onDirectiveStart() {
        pendingBlockOwnerIndent = -1
        plainScalarMinIndent = -1
        markNonIndicatorContent()
    }

    protected fun markNonIndicatorContent() {
        if (inLineLeadingIndicators) {
            inLineLeadingIndicators = false
            lineKeyIndent = currentTokenCol()
        }
    }

    protected fun onLeadingIndicator() {
        if (flowDepth == 0 && inLineLeadingIndicators) {
            val col = currentTokenCol()
            lineLastIndicatorIndent = col
            pendingBlockOwnerIndent = col
        }
    }

    protected fun onMappingColon() {
        if (flowDepth == 0) {
            if (inLineLeadingIndicators) {
                val col = currentTokenCol()
                lineLastIndicatorIndent = col
                pendingBlockOwnerIndent = col
            } else {
                lineHasMappingColon = true
                pendingBlockOwnerIndent = if (lineKeyIndent >= 0) lineKeyIndent else currentTokenCol()
            }
        }
    }

    protected fun onNonPlainScalar(quoted: Boolean) {
        markNonIndicatorContent()
        pendingBlockOwnerIndent = -1
        lastNonHiddenWasQuoted = quoted
    }

    protected fun onQuotedScalarDone() {
        markNonIndicatorContent()
        pendingBlockOwnerIndent = -1
        lastNonHiddenWasQuoted = true
        val tokenLen = _input.index() - _tokenStartCharIndex
        for (back in 1..tokenLen) {
            val ch = _input.LA(-back)
            if (ch == '\n'.code || ch == '\r'.code) {
                lineStartIndex = _input.index() - back + 1
                break
            }
        }
    }

    protected fun onBlockScalarBodyDone() {
        pendingBlockOwnerIndent = -1
        plainScalarMinIndent = -1
        inLineLeadingIndicators = false
    }

    protected fun isAtLineStart(): Boolean {
        val startCp = _tokenStartCharIndex
        if (startCp == 0) return true
        val prev = codePointBeforeTokenStart()
        if (prev == '\n'.code || prev == '\r'.code) return true
        return startCp == 1 && prev == BOM_CODE_POINT
    }

    protected fun isCommentAllowed(): Boolean {
        val startCp = _tokenStartCharIndex
        if (startCp == 0) return true
        val prev = codePointBeforeTokenStart()
        return prev == ' '.code ||
            prev == '\t'.code ||
            prev == '\r'.code ||
            prev == '\n'.code ||
            prev == BOM_CODE_POINT
    }

    protected fun isDocMarker(): Boolean {
        if (!isAtLineStart()) return false
        val next = _input.LA(1)
        return isWhitespaceOrEof(next)
    }

    protected fun onDocMarker() {
        flowDepth = 0
        lastNonHiddenWasQuoted = false
        pendingBlockOwnerIndent = -1
        plainScalarMinIndent = -1
        markNonIndicatorContent()
    }

    protected fun isIndicatorBoundary(): Boolean = isWhitespaceOrEof(_input.LA(1))

    protected fun isMappingColon(): Boolean = isColonEndAt(1)

    protected fun isFollowedByMappingColon(): Boolean {
        if (_input.LA(1) != ':'.code) return false
        return isColonEndAt(2)
    }

    protected fun isScalarBoundary(): Boolean {
        val next = _input.LA(1)
        if (next == IntStream.EOF) return true
        if (next == '\r'.code || next == '\n'.code) {
            val minIndent = computePlainContinuationMinIndent()
            return !hasPlainContinuationAfterOffset(1, minIndent)
        }
        if (next == ':'.code) return isColonEndAt(2)
        if (flowDepth > 0 && isFlowClosingOrComma(next)) return true
        if (next == ' '.code || next == '\t'.code) {
            var offset = 2
            while (true) {
                val ch = _input.LA(offset)
                if (ch == ' '.code || ch == '\t'.code) {
                    offset++
                } else {
                    if (ch == IntStream.EOF || ch == '#'.code) {
                        return true
                    }
                    if (ch == '\r'.code || ch == '\n'.code) {
                        val minIndent = computePlainContinuationMinIndent()
                        return !hasPlainContinuationAfterOffset(offset, minIndent)
                    }
                    if (ch == ':'.code) {
                        return isColonEndAt(offset + 1)
                    }
                    return flowDepth > 0 && isFlowClosingOrComma(ch)
                }
            }
        }
        return false
    }

    protected fun onPlainScalarDone(plainContMode: Int) {
        val minIndent = computePlainContinuationMinIndent()
        markNonIndicatorContent()
        pendingBlockOwnerIndent = -1
        lastNonHiddenWasQuoted = false
        val eolOffset = findLineEndOffsetAfterTrailingWs()
        if (eolOffset > 0 && hasPlainContinuationAfterOffset(eolOffset, minIndent)) {
            plainScalarMinIndent = minIndent
            pushMode(plainContMode)
        } else {
            plainScalarMinIndent = -1
        }
    }

    protected fun consumePlainScalarContinuation() {
        consumePlainScalar()
        markNonIndicatorContent()
        lastNonHiddenWasQuoted = false
        val eolOffset = findLineEndOffsetAfterTrailingWs()
        if (eolOffset <= 0 || !hasPlainContinuationAfterOffset(eolOffset, plainScalarMinIndent)) {
            plainScalarMinIndent = -1
            if (!_modeStack.isEmpty) {
                popMode()
            }
        }
    }

    protected fun consumePlainScalar() {
        while (_input.LA(1) != IntStream.EOF) {
            val ch = _input.LA(1)
            if (ch == '\r'.code || ch == '\n'.code || ch == BOM_CODE_POINT) break
            if (flowDepth > 0 && isAnyFlowIndicator(ch)) break
            if (ch == ':'.code) {
                if (isColonEndAt(2)) break
                _input.consume()
                continue
            }
            if (ch == ' '.code || ch == '\t'.code) {
                var k = 2
                while (true) {
                    val ahead = _input.LA(k)
                    if (ahead == ' '.code || ahead == '\t'.code) {
                        k++
                    } else {
                        break
                    }
                }
                val afterWs = _input.LA(k)
                if (afterWs == IntStream.EOF ||
                    afterWs == '\r'.code ||
                    afterWs == '\n'.code ||
                    afterWs == '#'.code ||
                    afterWs == BOM_CODE_POINT
                ) {
                    break
                }
                if (flowDepth > 0 && isAnyFlowIndicator(afterWs)) {
                    break
                }
                if (afterWs == ':'.code && isColonEndAt(k + 1)) {
                    break
                }
                repeat(k - 1) { _input.consume() }
                continue
            }
            _input.consume()
        }
    }

    protected fun isFollowedByAnchorChar(): Boolean {
        val next = _input.LA(1)
        return next != IntStream.EOF &&
            next != ' '.code &&
            next != '\t'.code &&
            next != '\r'.code &&
            next != '\n'.code &&
            next != BOM_CODE_POINT &&
            !isAnyFlowIndicator(next)
    }

    protected fun isBlockScalarHeaderAhead(): Boolean {
        var k = 1
        val c1 = _input.LA(k)
        if (c1 in '1'.code..'9'.code) {
            k++
            val c2 = _input.LA(k)
            if (c2 == '+'.code || c2 == '-'.code) k++
        } else if (c1 == '+'.code || c1 == '-'.code) {
            k++
            val c2 = _input.LA(k)
            if (c2 in '1'.code..'9'.code) k++
        }
        while (true) {
            val ws = _input.LA(k)
            if (ws == ' '.code || ws == '\t'.code) {
                k++
            } else {
                return ws == IntStream.EOF || ws == '\r'.code || ws == '\n'.code || ws == '#'.code
            }
        }
    }

    protected fun onBlockScalarStart() {
        blockExplicitIndent = 0
        blockParentIndent = computeParentIndentForCurrentLine()
        markNonIndicatorContent()
        pendingBlockOwnerIndent = -1
    }

    protected fun onBlockScalarIndentDigit(digitCodePoint: Int) {
        blockExplicitIndent = digitCodePoint - '0'.code
    }

    protected fun onBlockScalarHeaderNewline(bodyMode: Int) {
        val endOffset = scanBlockScalarEndOffset()
        if (endOffset > 0) {
            pendingBlockScalarEndCp = _input.index() + endOffset
            mode(bodyMode)
        } else {
            pendingBlockScalarEndCp = -1
            popMode()
        }
    }

    protected fun consumeBlockScalarBody() {
        val target = pendingBlockScalarEndCp
        pendingBlockScalarEndCp = -1
        while (_input.index() < target && _input.LA(1) != IntStream.EOF) {
            _input.consume()
        }
    }

    protected fun onOpenFlow() {
        markNonIndicatorContent()
        flowDepth++
        pendingBlockOwnerIndent = -1
        lastNonHiddenWasQuoted = false
    }

    protected fun onCloseFlow() {
        if (flowDepth > 0) flowDepth--
        lastNonHiddenWasQuoted = false
    }

    protected fun markQuoted(quoted: Boolean) {
        lastNonHiddenWasQuoted = quoted
    }

    private fun currentTokenCol(): Int {
        val col = _tokenStartCharIndex - lineStartIndex
        return if (col < 0) 0 else col
    }

    private fun computePlainContinuationMinIndent(): Int {
        if (flowDepth > 0) return 0
        val parentIndent = when {
            lineHasMappingColon -> if (lineKeyIndent >= 0) lineKeyIndent else pendingBlockOwnerIndent
            lineLastIndicatorIndent >= 0 -> lineLastIndicatorIndent
            pendingBlockOwnerIndent >= 0 && currentTokenCol() > pendingBlockOwnerIndent -> pendingBlockOwnerIndent
            else -> -1
        }
        return if (parentIndent >= 0) parentIndent + 1 else 0
    }

    private fun findLineEndOffsetAfterTrailingWs(): Int {
        var offset = 1
        while (true) {
            val ch = _input.LA(offset)
            when (ch) {
                ' '.code, '\t'.code -> offset++
                '\r'.code, '\n'.code -> return offset
                else -> return -1
            }
        }
    }

    private fun hasPlainContinuationAfterOffset(eolOffset: Int, minIndent: Int): Boolean {
        val eolChar = _input.LA(eolOffset)
        if (eolChar != '\r'.code && eolChar != '\n'.code) return false
        var pos = advancePastNewline(eolOffset, eolChar)
        while (true) {
            val first = _input.LA(pos)
            if (first == IntStream.EOF) return false
            if (isDocMarkerAt(pos)) return false

            var spaces = 0
            var p = pos
            while (_input.LA(p) == ' '.code) {
                spaces++
                p++
            }
            var afterWsPos = p
            while (_input.LA(afterWsPos) == ' '.code || _input.LA(afterWsPos) == '\t'.code) {
                afterWsPos++
            }
            val ch = _input.LA(afterWsPos)
            if (ch == IntStream.EOF) return false
            if (ch == '\r'.code || ch == '\n'.code) {
                pos = advancePastNewline(afterWsPos, ch)
                continue
            }
            if (ch == '#'.code || ch == BOM_CODE_POINT) return false
            if (flowDepth == 0 && spaces < minIndent) return false
            if (flowDepth > 0 && isAnyFlowIndicator(ch)) return false
            if (ch == ':'.code && isColonEndAt(afterWsPos + 1)) return false
            return true
        }
    }

    private fun codePointBeforeTokenStart(): Int {
        val dist = _input.index() - _tokenStartCharIndex + 1
        return _input.LA(-dist)
    }

    private fun isColonEndAt(offset: Int): Boolean {
        val after = _input.LA(offset)
        if (isWhitespaceOrEof(after)) return true
        if (flowDepth > 0) {
            if (isAnyFlowIndicator(after)) return true
            if (lastNonHiddenWasQuoted) return true
        }
        return false
    }

    private fun computeParentIndentForCurrentLine(): Int {
        val startCp = _tokenStartCharIndex
        val currentIndex = _input.index()
        var back = 1
        while (startCp - back >= 0) {
            val ch = _input.LA(-(currentIndex - startCp + back))
            if (ch == '\n'.code || ch == '\r'.code || ch == IntStream.EOF) break
            back++
        }
        val lineLen = back - 1
        if (lineLen <= 0) return -1

        fun charAtLineCol(col: Int): Int =
            _input.LA(-(currentIndex - startCp + lineLen - col))

        var col = 0
        var visualIndent = 0
        if (startCp - lineLen == 0 && charAtLineCol(0) == BOM_CODE_POINT) {
            col = 1
        }
        while (col < lineLen && (charAtLineCol(col) == ' '.code || charAtLineCol(col) == '\t'.code)) {
            col++
            visualIndent++
        }
        var lastSeqIndent = -1
        while (col < lineLen && charAtLineCol(col) == '-'.code) {
            val nextCol = col + 1
            if (nextCol < lineLen && (charAtLineCol(nextCol) == ' '.code || charAtLineCol(nextCol) == '\t'.code)) {
                lastSeqIndent = visualIndent
                col++
                visualIndent++
                while (col < lineLen && (charAtLineCol(col) == ' '.code || charAtLineCol(col) == '\t'.code)) {
                    col++
                    visualIndent++
                }
            } else {
                break
            }
        }
        return if (col < lineLen && hasMappingColonInRange(col, lineLen, ::charAtLineCol)) {
            visualIndent
        } else {
            lastSeqIndent
        }
    }

    private inline fun hasMappingColonInRange(
        startCol: Int,
        endCol: Int,
        charAt: (Int) -> Int,
    ): Boolean {
        var c = startCol
        var inDouble = false
        var inSingle = false
        while (c < endCol) {
            val ch = charAt(c)
            when {
                inDouble -> {
                    if (ch == '\\'.code) {
                        c += 2
                        continue
                    }
                    if (ch == '"'.code) inDouble = false
                }
                inSingle -> {
                    if (ch == '\''.code) {
                        if (c + 1 < endCol && charAt(c + 1) == '\''.code) {
                            c += 2
                            continue
                        }
                        inSingle = false
                    }
                }
                ch == '"'.code -> inDouble = true
                ch == '\''.code -> inSingle = true
                ch == ':'.code -> {
                    val next = if (c + 1 < endCol) charAt(c + 1) else -1
                    if (next == ' '.code || next == '\t'.code) return true
                }
            }
            c++
        }
        return false
    }

    private fun scanBlockScalarEndOffset(): Int {
        var offset = 1
        var detectedIndent = if (blockExplicitIndent > 0) {
            (if (blockParentIndent < 0) 0 else blockParentIndent) + blockExplicitIndent
        } else {
            -1
        }
        var lastContentEnd = 0

        while (true) {
            val first = _input.LA(offset)
            if (first == IntStream.EOF) break
            if (isDocMarkerAt(offset)) break

            var spaces = 0
            var p = offset
            while (_input.LA(p) == ' '.code) {
                spaces++
                p++
            }
            val afterSpaces = _input.LA(p)
            if (afterSpaces == IntStream.EOF) break
            if (afterSpaces == '\r'.code || afterSpaces == '\n'.code) {
                offset = advancePastNewline(p, afterSpaces)
                continue
            }
            if (blockParentIndent >= 0 && spaces <= blockParentIndent) break
            if (detectedIndent < 0) {
                detectedIndent = spaces
            } else if (spaces < detectedIndent) {
                break
            }
            while (true) {
                val c = _input.LA(p)
                if (c == IntStream.EOF || c == '\r'.code || c == '\n'.code) break
                p++
            }
            lastContentEnd = p - 1
            val eol = _input.LA(p)
            if (eol == IntStream.EOF) break
            offset = advancePastNewline(p, eol)
        }
        return lastContentEnd
    }

    private fun isDocMarkerAt(offset: Int): Boolean {
        val c1 = _input.LA(offset)
        val c2 = _input.LA(offset + 1)
        val c3 = _input.LA(offset + 2)
        val isMarkerChars = (c1 == '-'.code && c2 == '-'.code && c3 == '-'.code) ||
            (c1 == '.'.code && c2 == '.'.code && c3 == '.'.code)
        if (!isMarkerChars) return false
        return isWhitespaceOrEof(_input.LA(offset + 3))
    }

    private fun advancePastNewline(pos: Int, eolChar: Int): Int =
        if (eolChar == '\r'.code && _input.LA(pos + 1) == '\n'.code) pos + 2 else pos + 1

    private companion object {
        const val BOM_CODE_POINT = 0xFEFF

        fun isWhitespaceOrEof(ch: Int): Boolean =
            ch == IntStream.EOF || ch == ' '.code || ch == '\t'.code || ch == '\r'.code || ch == '\n'.code

        fun isFlowClosingOrComma(ch: Int): Boolean =
            ch == ','.code || ch == ']'.code || ch == '}'.code

        fun isAnyFlowIndicator(ch: Int): Boolean =
            ch == ','.code || ch == '['.code || ch == ']'.code || ch == '{'.code || ch == '}'.code
    }
}
