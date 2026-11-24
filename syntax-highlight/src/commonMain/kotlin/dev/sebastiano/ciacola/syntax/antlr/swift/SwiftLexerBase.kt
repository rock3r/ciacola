package dev.sebastiano.ciacola.syntax.antlr.swift

import org.antlr.v4.kotlinruntime.CharStream
import org.antlr.v4.kotlinruntime.IntStream
import org.antlr.v4.kotlinruntime.Lexer
import org.antlr.v4.kotlinruntime.Token

/**
 * Pure Kotlin Multiplatform base lexer for [SwiftLexer], providing:
 * - iterative O(N) nested block comment scanning,
 * - arbitrary `#`-count extended string & regex delimiters with exact `\#(...)` interpolation,
 * - balanced single-line interpolation recovery on unclosed newlines, and
 * - O(N)-memoized bare `/.../` regex vs. division `/` disambiguation.
 */
internal abstract class SwiftLexerBase(input: CharStream) : Lexer(input) {

    private class StringFrame(
        val isMultiline: Boolean,
        val hashCount: Int,
        val preStringModeStackSize: Int,
        var inExpression: Boolean = false,
        var parenDepth: Int = 0,
    )

    private val stringStack = ArrayList<StringFrame>()
    private var lastNonHiddenType: Int = Token.INVALID_TYPE
    private var lastNonHiddenText: String? = null
    private var sawNewlineSinceLastNonHidden: Boolean = false

    // O(N) memoization state for failed bare regex scans on pathological lines.
    private val failedBareRegexOutside = HashSet<Int>()
    private var unclosedBracketFrom: Int = Int.MAX_VALUE
    private var unclosedBracketLineEnd: Int = -1

    override fun reset() {
        super.reset()
        stringStack.clear()
        lastNonHiddenType = Token.INVALID_TYPE
        lastNonHiddenText = null
        sawNewlineSinceLastNonHidden = false
        failedBareRegexOutside.clear()
        unclosedBracketFrom = Int.MAX_VALUE
        unclosedBracketLineEnd = -1
    }

    override fun nextToken(): Token {
        val tok = super.nextToken()
        val t = tok.type
        if (t != Token.EOF) {
            when {
                isNewlineTokenType(t) -> sawNewlineSinceLastNonHidden = true
                !isHiddenTokenType(t) -> {
                    lastNonHiddenType = t
                    lastNonHiddenText = if (isIdentTokenType(t)) tok.text else null
                    sawNewlineSinceLastNonHidden = false
                }
            }
        }
        return tok
    }

    protected abstract fun isHiddenTokenType(type: Int): Boolean
    protected abstract fun isNewlineTokenType(type: Int): Boolean
    protected abstract fun isIdentTokenType(type: Int): Boolean
    protected abstract fun allowsBareRegexAfter(type: Int, text: String?): Boolean

    protected abstract fun singleLineStringMode(): Int
    protected abstract fun multiLineStringMode(): Int
    protected abstract fun extSingleLineStringMode(): Int
    protected abstract fun extMultiLineStringMode(): Int

    protected abstract fun stringTextTokenType(): Int
    protected abstract fun stringEscapeTokenType(): Int
    protected abstract fun stringCloseTokenType(): Int
    protected abstract fun interpOpenTokenType(): Int

    /**
     * Iteratively consumes nested block comments in strictly O(N) time and O(1) stack space.
     */
    protected fun consumeNestedBlockComment() {
        var depth = 1
        while (depth > 0 && _input.LA(1) != IntStream.EOF) {
            val c1 = _input.LA(1)
            if (c1 == '/'.code && _input.LA(2) == '*'.code) {
                _input.consume()
                _input.consume()
                depth++
            } else if (c1 == '*'.code && _input.LA(2) == '/'.code) {
                _input.consume()
                _input.consume()
                depth--
            } else {
                _input.consume()
            }
        }
    }

    /**
     * Consumes an extended regex literal (`#/.../#`, `##/.../##`, including multiline `#/\n...\n/#`)
     * in strictly O(N) time regardless of `#` count.
     */
    protected fun consumeExtendedRegex() {
        val hashes = (_input.index() - _tokenStartCharIndex - 1).coerceAtLeast(1)
        val first = _input.LA(1)
        val isMultiline = first == '\r'.code || first == '\n'.code
        while (_input.LA(1) != IntStream.EOF) {
            val ch = _input.LA(1)
            if (!isMultiline && (ch == '\r'.code || ch == '\n'.code)) {
                return
            }
            if (ch == '/'.code) {
                _input.consume()
                var matched = 0
                while (matched < hashes && _input.LA(1) == '#'.code) {
                    _input.consume()
                    matched++
                }
                if (matched == hashes) {
                    return
                }
            } else {
                _input.consume()
            }
        }
    }

    /**
     * Returns `true` when `/` can begin a bare Swift 5.7+ `/.../` regex literal:
     * expression-prefix context, not starting with space/tab/slash/star/newline,
     * and closing cleanly on the same line (memoized in O(N) across pathological lines).
     */
    protected fun isBareRegexAllowed(): Boolean {
        val allowsContext = lastNonHiddenType == Token.INVALID_TYPE ||
            sawNewlineSinceLastNonHidden ||
            allowsBareRegexAfter(lastNonHiddenType, lastNonHiddenText)
        if (!allowsContext) return false

        val next = _input.LA(1)
        if (next == IntStream.EOF ||
            next == ' '.code ||
            next == '\t'.code ||
            next == '\r'.code ||
            next == '\n'.code ||
            next == '/'.code ||
            next == '*'.code
        ) {
            return false
        }
        return hasValidBareRegexCloseOnLine()
    }

    private fun hasValidBareRegexCloseOnLine(): Boolean {
        val baseIndex = _input.index()
        var offset = 1
        val visitedOutside = ArrayList<Int>()
        while (true) {
            val absPos = baseIndex + offset - 1
            if (absPos in failedBareRegexOutside) {
                for (pos in visitedOutside) failedBareRegexOutside.add(pos)
                return false
            }
            val ch = _input.LA(offset)
            when (ch) {
                IntStream.EOF, '\r'.code, '\n'.code -> {
                    for (pos in visitedOutside) failedBareRegexOutside.add(pos)
                    return false
                }
                '/'.code -> return true
                '\\'.code -> {
                    visitedOutside.add(absPos)
                    val esc = _input.LA(offset + 1)
                    if (esc == IntStream.EOF || esc == '\r'.code || esc == '\n'.code) {
                        for (pos in visitedOutside) failedBareRegexOutside.add(pos)
                        return false
                    }
                    offset += 2
                }
                '['.code -> {
                    visitedOutside.add(absPos)
                    if (absPos < unclosedBracketLineEnd && absPos >= unclosedBracketFrom) {
                        for (pos in visitedOutside) failedBareRegexOutside.add(pos)
                        return false
                    }
                    val afterBracket = findCloseBracketOffset(baseIndex, offset + 1)
                    if (afterBracket < 0) {
                        for (pos in visitedOutside) failedBareRegexOutside.add(pos)
                        return false
                    }
                    offset = afterBracket
                }
                else -> {
                    visitedOutside.add(absPos)
                    offset++
                }
            }
        }
    }

    private fun findCloseBracketOffset(baseIndex: Int, startOffset: Int): Int {
        var offset = startOffset
        while (true) {
            val ch = _input.LA(offset)
            when (ch) {
                IntStream.EOF, '\r'.code, '\n'.code -> {
                    val lineEnd = baseIndex + offset - 1
                    val bracketStart = baseIndex + startOffset - 2
                    if (lineEnd > unclosedBracketLineEnd) {
                        unclosedBracketLineEnd = lineEnd
                        unclosedBracketFrom = bracketStart
                    } else if (bracketStart < unclosedBracketFrom) {
                        unclosedBracketFrom = bracketStart
                    }
                    return -1
                }
                '\\'.code -> {
                    val esc = _input.LA(offset + 1)
                    if (esc == IntStream.EOF || esc == '\r'.code || esc == '\n'.code) {
                        return findCloseBracketOffset(baseIndex, offset + 1)
                    }
                    offset += 2
                }
                ']'.code -> return offset + 1
                else -> offset++
            }
        }
    }

    protected fun enterSwiftString(isMultiline: Boolean, isExtended: Boolean) {
        val lexemeLen = _input.index() - _tokenStartCharIndex
        val quoteLen = if (isMultiline) 3 else 1
        val hashes = if (isExtended) (lexemeLen - quoteLen).coerceAtLeast(1) else 0
        val targetMode = when {
            isExtended && isMultiline -> extMultiLineStringMode()
            isExtended -> extSingleLineStringMode()
            isMultiline -> multiLineStringMode()
            else -> singleLineStringMode()
        }
        stringStack.add(
            StringFrame(
                isMultiline = isMultiline,
                hashCount = hashes,
                preStringModeStackSize = _modeStack.size(),
            ),
        )
        pushMode(targetMode)
    }

    protected fun exitSwiftString() {
        if (stringStack.isNotEmpty()) {
            stringStack.removeAt(stringStack.lastIndex)
        }
        if (!_modeStack.isEmpty) {
            popMode()
        }
    }

    protected fun enterInterpExpr(defaultMode: Int) {
        val top = stringStack.lastOrNull() ?: return
        top.inExpression = true
        top.parenDepth = 0
        pushMode(defaultMode)
    }

    protected fun onOpenParen() {
        val top = activeExprFrame() ?: return
        top.parenDepth++
    }

    protected fun onCloseParen(interpCloseType: Int) {
        val top = activeExprFrame() ?: return
        if (top.parenDepth > 0) {
            top.parenDepth--
            return
        }
        top.inExpression = false
        type = interpCloseType
        if (!_modeStack.isEmpty) {
            popMode()
        }
    }

    protected fun onDefaultNewline() {
        val top = activeExprFrame() ?: return
        if (!top.isMultiline && !hasBalancedSingleLineInterpOnLine()) {
            unwindSingleLineStrings()
        }
    }

    protected fun onExtQuotes(quoteCount: Int) {
        val top = stringStack.lastOrNull() ?: return
        val hashes = text.length - quoteCount
        when {
            hashes < top.hashCount -> type = stringTextTokenType()
            hashes == top.hashCount -> {
                type = stringCloseTokenType()
                exitSwiftString()
            }
            else -> {
                _input.seek(_tokenStartCharIndex + quoteCount + top.hashCount)
                type = stringCloseTokenType()
                exitSwiftString()
            }
        }
    }

    protected fun onExtBackslash(isSingleLine: Boolean) {
        val top = stringStack.lastOrNull() ?: return
        val lexeme = text
        var hashes = 0
        while (1 + hashes < lexeme.length && lexeme[1 + hashes] == '#') {
            hashes++
        }
        when {
            hashes < top.hashCount -> {
                // Fewer `#` than delimiter: leave any trailing char (e.g. closing `"`) in the stream.
                val consumed = 1 + hashes
                if (consumed < lexeme.length) {
                    _input.seek(_tokenStartCharIndex + consumed)
                }
                type = stringTextTokenType()
            }
            hashes == top.hashCount -> {
                val tailIndex = 1 + hashes
                if (tailIndex < lexeme.length && lexeme[tailIndex] == '(') {
                    type = interpOpenTokenType()
                    enterInterpExpr(Lexer.DEFAULT_MODE)
                } else if (tailIndex < lexeme.length) {
                    type = stringEscapeTokenType()
                } else {
                    type = stringTextTokenType()
                }
            }
            else -> {
                // `hashes > top.hashCount`: `\` + `top.hashCount` `#`s followed by the next `#` (escaped char).
                val consumed = 1 + top.hashCount + 1
                if (consumed < lexeme.length) {
                    _input.seek(_tokenStartCharIndex + consumed)
                }
                type = stringEscapeTokenType()
            }
        }
        if (isSingleLine && type == stringEscapeTokenType()) {
            // Single-line escapes never consume newlines.
        }
    }

    private fun activeExprFrame(): StringFrame? =
        stringStack.lastOrNull()?.takeIf { it.inExpression }

    private fun hasBalancedSingleLineInterpOnLine(): Boolean {
        val top = stringStack.lastOrNull() ?: return true
        val afterParen = skipExprOnLine(1, top.parenDepth + 1, depth = 0)
        if (afterParen < 0) return false
        if (afterParen > SINGLE_LINE_LOOKAHEAD_LIMIT) return true
        return scanSingleLineStringTailOnLine(afterParen, top.hashCount, depth = 0) > 0
    }

    private fun skipExprOnLine(startOffset: Int, initialParenDepth: Int, depth: Int): Int {
        if (depth > MAX_LOOKAHEAD_DEPTH) return SINGLE_LINE_LOOKAHEAD_LIMIT + 1
        var offset = startOffset
        var parenDepth = initialParenDepth
        while (offset <= SINGLE_LINE_LOOKAHEAD_LIMIT) {
            val ch = _input.LA(offset)
            if (ch == IntStream.EOF || ch == '\r'.code || ch == '\n'.code) {
                return -1
            }
            when (ch) {
                '('.code -> {
                    parenDepth++
                    offset++
                }
                ')'.code -> {
                    parenDepth--
                    offset++
                    if (parenDepth == 0) return offset
                }
                '#'.code -> {
                    var hashes = 0
                    while (_input.LA(offset + hashes) == '#'.code && offset + hashes <= SINGLE_LINE_LOOKAHEAD_LIMIT) {
                        hashes++
                    }
                    if (_input.LA(offset + hashes) == '"'.code) {
                        offset = scanSingleLineStringTailOnLine(offset + hashes + 1, hashes, depth + 1)
                        if (offset < 0) return -1
                    } else {
                        offset += hashes
                    }
                }
                '"'.code -> {
                    offset = scanSingleLineStringTailOnLine(offset + 1, 0, depth + 1)
                    if (offset < 0) return -1
                }
                '/'.code -> {
                    val next = _input.LA(offset + 1)
                    when (next) {
                        '/'.code -> return -1
                        '*'.code -> {
                            offset += 2
                            while (offset <= SINGLE_LINE_LOOKAHEAD_LIMIT) {
                                val c = _input.LA(offset)
                                if (c == IntStream.EOF || c == '\r'.code || c == '\n'.code) return -1
                                if (c == '*'.code && _input.LA(offset + 1) == '/'.code) {
                                    offset += 2
                                    break
                                }
                                offset++
                            }
                        }
                        else -> offset++
                    }
                }
                else -> offset++
            }
        }
        return SINGLE_LINE_LOOKAHEAD_LIMIT + 1
    }

    private fun scanSingleLineStringTailOnLine(startOffset: Int, hashCount: Int, depth: Int): Int {
        var offset = startOffset
        while (offset <= SINGLE_LINE_LOOKAHEAD_LIMIT) {
            when (_input.LA(offset)) {
                IntStream.EOF, '\r'.code, '\n'.code -> return -1
                '\\'.code -> {
                    var hashes = 0
                    while (_input.LA(offset + 1 + hashes) == '#'.code && offset + 1 + hashes <= SINGLE_LINE_LOOKAHEAD_LIMIT) {
                        hashes++
                    }
                    if (hashes == hashCount && _input.LA(offset + 1 + hashes) == '('.code) {
                        val afterExpr = skipExprOnLine(offset + 2 + hashes, 1, depth + 1)
                        if (afterExpr < 0) return -1
                        offset = afterExpr
                    } else {
                        offset += 2
                    }
                }
                '"'.code -> {
                    if (hashCount == 0) return offset + 1
                    var matched = 0
                    while (matched < hashCount && _input.LA(offset + 1 + matched) == '#'.code) {
                        matched++
                    }
                    if (matched == hashCount) return offset + 1 + matched
                    offset += 1 + matched
                }
                else -> offset++
            }
        }
        return SINGLE_LINE_LOOKAHEAD_LIMIT + 1
    }

    private fun unwindSingleLineStrings() {
        while (stringStack.isNotEmpty() && !stringStack.last().isMultiline) {
            val frame = stringStack.removeAt(stringStack.lastIndex)
            while (_modeStack.size() > frame.preStringModeStackSize) {
                popMode()
            }
        }
    }

    private companion object {
        const val SINGLE_LINE_LOOKAHEAD_LIMIT = 512
        const val MAX_LOOKAHEAD_DEPTH = 6
    }
}
