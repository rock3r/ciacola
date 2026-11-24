package dev.sebastiano.ciacola.syntax.antlr.swift

import org.antlr.v4.kotlinruntime.CharStream
import org.antlr.v4.kotlinruntime.IntStream
import org.antlr.v4.kotlinruntime.Lexer
import org.antlr.v4.kotlinruntime.Token

/**
 * Pure Kotlin Multiplatform base lexer for [SwiftLexer], providing:
 * - iterative O(N) nested block comment scanning,
 * - arbitrary `#`-count extended string & regex delimiters with exact `\#(...)` interpolation,
 * - SE-0354 extended regex escape and multiline `#` comment / closing-line rules,
 * - single-line interpolation recovery on unclosed newlines, and
 * - O(N)-memoized SE-0354 bare `/.../` regex vs. division `/` disambiguation.
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
    private var sawHiddenSinceLastNonHidden: Boolean = false
    private var lastBangWasPrefix: Boolean = true

    // O(N) memoization state for bare regex validation on pathological lines.
    private val failedBareRegexOutside = HashMap<Int, Int>()
    private val bracketCloseCache = HashMap<Int, Int>()
    private var unclosedBracketFrom: Int = Int.MAX_VALUE
    private var unclosedBracketLineEnd: Int = -1
    private var validatedBareRegexStart: Int = -1
    private var validatedBareRegexEnd: Int = -1

    override fun reset() {
        super.reset()
        stringStack.clear()
        lastNonHiddenType = Token.INVALID_TYPE
        lastNonHiddenText = null
        sawNewlineSinceLastNonHidden = false
        sawHiddenSinceLastNonHidden = false
        lastBangWasPrefix = true
        failedBareRegexOutside.clear()
        bracketCloseCache.clear()
        unclosedBracketFrom = Int.MAX_VALUE
        unclosedBracketLineEnd = -1
        validatedBareRegexStart = -1
        validatedBareRegexEnd = -1
    }

    override fun nextToken(): Token {
        val tok = super.nextToken()
        val t = tok.type
        if (t != Token.EOF) {
            when {
                isNewlineTokenType(t) -> {
                    sawNewlineSinceLastNonHidden = true
                    sawHiddenSinceLastNonHidden = true
                }
                isHiddenTokenType(t) -> {
                    sawHiddenSinceLastNonHidden = true
                }
                else -> {
                    if (isBangTokenType(t)) {
                        lastBangWasPrefix = lastNonHiddenType == Token.INVALID_TYPE ||
                            sawHiddenSinceLastNonHidden ||
                            (lastNonHiddenType != rbraceTokenType() &&
                                allowsBareRegexAfter(lastNonHiddenType, lastNonHiddenText, lastBangWasPrefix))
                    }
                    lastNonHiddenType = t
                    lastNonHiddenText = if (isIdentTokenType(t)) tok.text else null
                    sawNewlineSinceLastNonHidden = false
                    sawHiddenSinceLastNonHidden = false
                }
            }
        }
        return tok
    }

    protected abstract fun isHiddenTokenType(type: Int): Boolean
    protected abstract fun isNewlineTokenType(type: Int): Boolean
    protected abstract fun isIdentTokenType(type: Int): Boolean
    protected abstract fun isBangTokenType(type: Int): Boolean
    protected abstract fun rbraceTokenType(): Int
    protected abstract fun allowsBareRegexAfter(type: Int, text: String?, lastBangWasPrefix: Boolean): Boolean

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
     * in strictly O(N) time regardless of `#` count, honoring backslash escapes and SE-0354
     * multiline `#` comments and standalone closing-line rules.
     */
    protected fun consumeExtendedRegex() {
        val hashes = (_input.index() - _tokenStartCharIndex - 1).coerceAtLeast(1)
        val first = _input.LA(1)
        val isMultiline = first == '\r'.code || first == '\n'.code
        var onlyWhitespaceOnLine = true
        var inCharClass = false

        while (_input.LA(1) != IntStream.EOF) {
            val ch = _input.LA(1)
            if (!isMultiline && (ch == '\r'.code || ch == '\n'.code)) {
                return
            }
            when (ch) {
                '\r'.code, '\n'.code -> {
                    _input.consume()
                    onlyWhitespaceOnLine = true
                }
                ' '.code, '\t'.code -> _input.consume()
                '\\'.code -> {
                    _input.consume()
                    val esc = _input.LA(1)
                    if (esc != IntStream.EOF && esc != '\r'.code && esc != '\n'.code) {
                        _input.consume()
                    }
                    onlyWhitespaceOnLine = false
                }
                '['.code -> {
                    if (!inCharClass) inCharClass = true
                    _input.consume()
                    onlyWhitespaceOnLine = false
                }
                ']'.code -> {
                    if (inCharClass) inCharClass = false
                    _input.consume()
                    onlyWhitespaceOnLine = false
                }
                '#'.code -> {
                    if (isMultiline && !inCharClass) {
                        // In multiline extended regex mode, unescaped `#` outside `[...]` starts a line comment.
                        while (_input.LA(1) != IntStream.EOF && _input.LA(1) != '\r'.code && _input.LA(1) != '\n'.code) {
                            _input.consume()
                        }
                    } else {
                        _input.consume()
                    }
                    onlyWhitespaceOnLine = false
                }
                '/'.code -> {
                    _input.consume()
                    val canClose = !inCharClass && (!isMultiline || onlyWhitespaceOnLine)
                    if (canClose) {
                        var matched = 0
                        while (matched < hashes && _input.LA(1) == '#'.code) {
                            _input.consume()
                            matched++
                        }
                        if (matched == hashes) {
                            return
                        }
                    }
                    onlyWhitespaceOnLine = false
                }
                else -> {
                    _input.consume()
                    onlyWhitespaceOnLine = false
                }
            }
        }
    }

    /**
     * Returns `true` when `/` can begin a bare Swift 5.7+ `/.../` regex literal:
     * expression-prefix context, valid opening character, balanced parentheses outside `[...]`,
     * no unescaped trailing space/tab before `/`, and closing cleanly on the same line
     * (memoized in strictly O(N) across pathological lines).
     */
    protected fun isBareRegexAllowed(): Boolean {
        val allowsContext = lastNonHiddenType == Token.INVALID_TYPE ||
            sawNewlineSinceLastNonHidden ||
            allowsBareRegexAfter(lastNonHiddenType, lastNonHiddenText, lastBangWasPrefix)
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
        val baseIndex = _input.index()
        val endOffset = findBareRegexEndOffset(baseIndex)
        if (endOffset < 0) return false
        validatedBareRegexStart = baseIndex
        validatedBareRegexEnd = baseIndex + endOffset
        return true
    }

    protected fun consumeValidatedBareRegex() {
        val current = _input.index()
        if (validatedBareRegexStart == current && validatedBareRegexEnd > current) {
            _input.seek(validatedBareRegexEnd)
        }
        validatedBareRegexStart = -1
        validatedBareRegexEnd = -1
    }

    private fun findBareRegexEndOffset(baseIndex: Int): Int {
        var offset = 1
        var parenDepth = 0
        var lastWasUnescapedSpaceOrTab = false
        val visitedOutside = ArrayList<Pair<Int, Int>>()

        fun recordFailure(extraMargin: Int) {
            for ((pos, depthAtPos) in visitedOutside) {
                val limit = if (extraMargin == Int.MAX_VALUE) Int.MAX_VALUE else depthAtPos + extraMargin
                val existing = failedBareRegexOutside[pos]
                if (existing == null || limit > existing) {
                    failedBareRegexOutside[pos] = limit
                }
            }
        }

        while (true) {
            val absPos = baseIndex + offset - 1
            val cachedDepth = failedBareRegexOutside[absPos]
            if (cachedDepth != null && parenDepth <= cachedDepth) {
                recordFailure(if (cachedDepth == Int.MAX_VALUE) Int.MAX_VALUE else cachedDepth - parenDepth)
                return -1
            }
            val ch = _input.LA(offset)
            when (ch) {
                IntStream.EOF, '\r'.code, '\n'.code -> {
                    recordFailure(Int.MAX_VALUE)
                    return -1
                }
                '/'.code -> {
                    if (lastWasUnescapedSpaceOrTab) {
                        recordFailure(Int.MAX_VALUE)
                        return -1
                    }
                    return offset
                }
                '('.code -> {
                    visitedOutside.add(absPos to parenDepth)
                    parenDepth++
                    lastWasUnescapedSpaceOrTab = false
                    offset++
                }
                ')'.code -> {
                    if (parenDepth == 0) {
                        recordFailure(0)
                        return -1
                    }
                    visitedOutside.add(absPos to parenDepth)
                    parenDepth--
                    lastWasUnescapedSpaceOrTab = false
                    offset++
                }
                '\\'.code -> {
                    visitedOutside.add(absPos to parenDepth)
                    val esc = _input.LA(offset + 1)
                    if (esc == IntStream.EOF || esc == '\r'.code || esc == '\n'.code) {
                        recordFailure(Int.MAX_VALUE)
                        return -1
                    }
                    lastWasUnescapedSpaceOrTab = false
                    offset += 2
                }
                '['.code -> {
                    visitedOutside.add(absPos to parenDepth)
                    if (absPos < unclosedBracketLineEnd && absPos >= unclosedBracketFrom) {
                        recordFailure(Int.MAX_VALUE)
                        return -1
                    }
                    val afterBracket = findCloseBracketOffset(baseIndex, offset + 1)
                    if (afterBracket < 0) {
                        recordFailure(Int.MAX_VALUE)
                        return -1
                    }
                    lastWasUnescapedSpaceOrTab = false
                    offset = afterBracket
                }
                ' '.code, '\t'.code -> {
                    visitedOutside.add(absPos to parenDepth)
                    lastWasUnescapedSpaceOrTab = true
                    offset++
                }
                else -> {
                    visitedOutside.add(absPos to parenDepth)
                    lastWasUnescapedSpaceOrTab = false
                    offset++
                }
            }
        }
    }

    private fun findCloseBracketOffset(baseIndex: Int, startOffset: Int): Int {
        val openAbsPos = baseIndex + startOffset - 2
        bracketCloseCache[openAbsPos]?.let { cachedCloseAbs ->
            return if (cachedCloseAbs < 0) -1 else cachedCloseAbs - baseIndex + 2
        }

        val visitedBrackets = ArrayList<Int>()
        visitedBrackets.add(openAbsPos)
        var offset = startOffset
        while (true) {
            val ch = _input.LA(offset)
            when (ch) {
                IntStream.EOF, '\r'.code, '\n'.code -> {
                    val lineEnd = baseIndex + offset - 1
                    if (lineEnd > unclosedBracketLineEnd) {
                        unclosedBracketLineEnd = lineEnd
                        unclosedBracketFrom = openAbsPos
                    } else if (openAbsPos < unclosedBracketFrom) {
                        unclosedBracketFrom = openAbsPos
                    }
                    for (pos in visitedBrackets) bracketCloseCache[pos] = -1
                    return -1
                }
                '\\'.code -> {
                    val esc = _input.LA(offset + 1)
                    if (esc == IntStream.EOF || esc == '\r'.code || esc == '\n'.code) {
                        offset += 1
                    } else {
                        offset += 2
                    }
                }
                '['.code -> {
                    val nestedAbs = baseIndex + offset - 1
                    bracketCloseCache[nestedAbs]?.let { cachedCloseAbs ->
                        for (pos in visitedBrackets) bracketCloseCache[pos] = cachedCloseAbs
                        return if (cachedCloseAbs < 0) -1 else cachedCloseAbs - baseIndex + 2
                    }
                    visitedBrackets.add(nestedAbs)
                    offset++
                }
                ']'.code -> {
                    val closeAbs = baseIndex + offset - 1
                    for (pos in visitedBrackets) bracketCloseCache[pos] = closeAbs
                    return offset + 1
                }
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
        if (!top.isMultiline) {
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

    private fun unwindSingleLineStrings() {
        while (stringStack.isNotEmpty() && !stringStack.last().isMultiline) {
            val frame = stringStack.removeAt(stringStack.lastIndex)
            while (_modeStack.size() > frame.preStringModeStackSize) {
                popMode()
            }
        }
    }
}
