package dev.sebastiano.ciacola.syntax.antlr.groovy

import org.antlr.v4.kotlinruntime.CharStream
import org.antlr.v4.kotlinruntime.IntStream
import org.antlr.v4.kotlinruntime.Lexer
import org.antlr.v4.kotlinruntime.Token

/**
 * Pure Kotlin Multiplatform base lexer for [GroovyLexer].
 *
 * Handles start-of-input shebang detection, iterative nested GString `${...}` brace tracking,
 * and O(1)-per-token slashy-string (`/pattern/`) vs. division (`/`) disambiguation.
 */
internal abstract class GroovyLexerBase(input: CharStream) : Lexer(input) {

    private class InterpFrame(
        val modeStackSize: Int,
        var braceDepth: Int = 0,
    )

    private class InterpSpan(
        val closePos: Int,
        val maxSlashPos: Int,
    )

    private class BraceFrame(
        val openPos: Int,
        var maxSlashPos: Int = -1,
    )

    private val interpStack = ArrayList<InterpFrame>()
    private var lastNonHiddenType: Int = Token.INVALID_TYPE
    private var lastNonHiddenText: String? = null
    private var lastParenWasCondition: Boolean = false
    private var lastBraceAllowedSlashy: Boolean = true
    private var lastColonWasTernary: Boolean = false
    private var lastGtWasGeneric: Boolean = false
    private var ternaryDepth: Int = 0
    private var parenDepth: Int = 0
    private var brackDepth: Int = 0
    private var angleDepth: Int = 0
    private var sawNewlineSinceLastToken: Boolean = false
    private val parenConditionStack = ArrayList<Boolean>()
    private val braceAllowsSlashyStack = ArrayList<Boolean>()
    private val savedBraceTernaryDepthStack = ArrayList<Int>()
    private val savedParenTernaryDepthStack = ArrayList<Int>()
    private val savedBrackTernaryDepthStack = ArrayList<Int>()
    private val savedInterpTernaryDepthStack = ArrayList<Int>()
    private val slashyCloseCache = HashMap<Int, Boolean>()
    private val interpCloseCache = HashMap<Int, InterpSpan>()
    private val outsideSlashyCloseCache = HashMap<Int, Boolean>()
    private var noSlashBeforeEofFrom: Int = Int.MAX_VALUE

    protected fun isAtStartOfInput(): Boolean = _tokenStartCharIndex == 0

    protected fun enterInterpExpr() {
        pushMode(DEFAULT_MODE)
        interpStack.add(InterpFrame(modeStackSize = _modeStack.size()))
    }

    protected fun onDefaultLBrace() {
        val top = interpStack.lastOrNull()
        if (top != null && _modeStack.size() == top.modeStackSize) {
            top.braceDepth++
        }
    }

    protected fun onDefaultRBrace(interpCloseType: Int) {
        val top = interpStack.lastOrNull()
        if (top != null && _modeStack.size() == top.modeStackSize) {
            if (top.braceDepth > 0) {
                top.braceDepth--
            } else {
                interpStack.removeAt(interpStack.lastIndex)
                if (!_modeStack.isEmpty) {
                    popMode()
                }
                type = interpCloseType
            }
        }
    }

    protected fun isExplicitSlashyPrefix(): Boolean {
        val next = _input.LA(1)
        if (next == '/'.code || next == '*'.code) return false
        return allowsSlashyAfterToken(
            type = lastNonHiddenType,
            text = lastNonHiddenText,
            lastParenWasCond = lastParenWasCondition,
            lastBraceAllowedSlashy = lastBraceAllowedSlashy,
            lastGtWasGeneric = lastGtWasGeneric,
        ) && hasClosingSlashyDelimiter()
    }

    protected fun isSlashyStart(): Boolean {
        val next = _input.LA(1)
        // '//' and '/*' are always line or block comments in Groovy, never slashy strings.
        if (next == '/'.code || next == '*'.code) return false
        val explicit = allowsSlashyAfterToken(
            type = lastNonHiddenType,
            text = lastNonHiddenText,
            lastParenWasCond = lastParenWasCondition,
            lastBraceAllowedSlashy = lastBraceAllowedSlashy,
            lastGtWasGeneric = lastGtWasGeneric,
        )
        if (!explicit) {
            if (sawNewlineSinceLastToken && parenDepth == 0 && brackDepth == 0 &&
                allowsSlashyAfterNewline(lastNonHiddenType)
            ) {
                return hasClosingSlashyDelimiter()
            }
            return false
        }
        return hasClosingSlashyDelimiter()
    }

    private fun allowsSlashyAfterNewline(prevType: Int): Boolean = when (prevType) {
        // After an operand-ending token, a leading '/' on the next line is division/div-assign
        // unless it followed a statement/block terminator.
        GroovyLexer.Tokens.RBRACE,
        GroovyLexer.Tokens.SEMI,
        Token.INVALID_TYPE,
        -> true
        else -> false
    }

    /**
     * Checks in amortized O(N) time (via [slashyCloseCache], [interpCloseCache],
     * [outsideSlashyCloseCache], and [noSlashBeforeEofFrom]) whether a `/` at [_tokenStartCharIndex]
     * has a matching unescaped closing `/` before EOF.
     */
    private fun hasClosingSlashyDelimiter(): Boolean {
        val entryPos = _tokenStartCharIndex
        if (entryPos >= noSlashBeforeEofFrom) return false
        slashyCloseCache[entryPos]?.let { return it }

        var offset = 1
        val baseIndex = _input.index()
        var maxSlashPos = -1
        // Explicit iterative stack of open `${` ($ position) and `{` ({ position) frames.
        // Empty means currently in slashy body (outside `${...}` interpolation).
        val braceStack = ArrayList<BraceFrame>()
        val visitedOutside = ArrayList<Int>()

        while (true) {
            val ch = _input.LA(offset)
            if (ch == IntStream.EOF) break
            val absPos = baseIndex + offset - 1

            if (braceStack.isEmpty()) {
                val cachedOutside = outsideSlashyCloseCache[absPos]
                if (cachedOutside != null) {
                    return finishSlashyLookahead(entryPos, cachedOutside, braceStack, visitedOutside)
                }
                visitedOutside.add(absPos)
                when (ch) {
                    '/'.code -> {
                        return finishSlashyLookahead(entryPos, true, braceStack, visitedOutside)
                    }
                    '\\'.code -> {
                        offset += if (_input.LA(offset + 1) == '/'.code) 2 else 1
                    }
                    '$'.code -> {
                        if (_input.LA(offset + 1) == '{'.code) {
                            val span = interpCloseCache[absPos]
                            if (span != null) {
                                if (span.closePos < 0) {
                                    return finishSlashyLookahead(entryPos, false, braceStack, visitedOutside)
                                }
                                if (span.maxSlashPos > maxSlashPos) {
                                    maxSlashPos = span.maxSlashPos
                                }
                                offset = span.closePos - baseIndex + 2
                            } else {
                                braceStack.add(BraceFrame(openPos = absPos))
                                offset += 2
                            }
                        } else {
                            offset++
                        }
                    }
                    else -> offset++
                }
            } else {
                when (ch) {
                    '{'.code -> {
                        val span = interpCloseCache[absPos]
                        if (span != null) {
                            if (span.closePos < 0) {
                                return finishSlashyLookahead(entryPos, false, braceStack, visitedOutside)
                            }
                            val top = braceStack.last()
                            if (span.maxSlashPos > top.maxSlashPos) {
                                top.maxSlashPos = span.maxSlashPos
                            }
                            offset = span.closePos - baseIndex + 2
                        } else {
                            braceStack.add(BraceFrame(openPos = absPos))
                            offset++
                        }
                    }
                    '}'.code -> {
                        val frame = braceStack.removeAt(braceStack.lastIndex)
                        interpCloseCache[frame.openPos] = InterpSpan(
                            closePos = absPos,
                            maxSlashPos = frame.maxSlashPos,
                        )
                        if (braceStack.isNotEmpty()) {
                            val parent = braceStack.last()
                            if (frame.maxSlashPos > parent.maxSlashPos) {
                                parent.maxSlashPos = frame.maxSlashPos
                            }
                        } else if (frame.maxSlashPos > maxSlashPos) {
                            maxSlashPos = frame.maxSlashPos
                        }
                        offset++
                    }
                    '"'.code -> {
                        offset = skipQuotedInLookahead(offset, '"'.code)
                    }
                    '\''.code -> {
                        offset = skipQuotedInLookahead(offset, '\''.code)
                    }
                    '/'.code -> {
                        val top = braceStack.last()
                        if (absPos > top.maxSlashPos) {
                            top.maxSlashPos = absPos
                        }
                        val next = _input.LA(offset + 1)
                        if (next == '/'.code) {
                            offset += 2
                            while (true) {
                                val c = _input.LA(offset)
                                if (c == IntStream.EOF || c == '\r'.code || c == '\n'.code) break
                                offset++
                            }
                        } else if (next == '*'.code) {
                            offset += 2
                            while (true) {
                                val c = _input.LA(offset)
                                if (c == IntStream.EOF) break
                                if (c == '*'.code && _input.LA(offset + 1) == '/'.code) {
                                    offset += 2
                                    break
                                }
                                offset++
                            }
                        } else {
                            offset++
                        }
                    }
                    '$'.code -> {
                        if (_input.LA(offset + 1) == '{'.code) {
                            val span = interpCloseCache[absPos]
                            if (span != null) {
                                if (span.closePos < 0) {
                                    return finishSlashyLookahead(entryPos, false, braceStack, visitedOutside)
                                }
                                val top = braceStack.last()
                                if (span.maxSlashPos > top.maxSlashPos) {
                                    top.maxSlashPos = span.maxSlashPos
                                }
                                offset = span.closePos - baseIndex + 2
                            } else {
                                braceStack.add(BraceFrame(openPos = absPos))
                                offset += 2
                            }
                        } else {
                            offset++
                        }
                    }
                    else -> offset++
                }
            }
        }

        for (frame in braceStack) {
            if (frame.maxSlashPos > maxSlashPos) {
                maxSlashPos = frame.maxSlashPos
            }
        }
        if (maxSlashPos < entryPos) {
            noSlashBeforeEofFrom = minOf(noSlashBeforeEofFrom, entryPos)
        } else {
            noSlashBeforeEofFrom = minOf(noSlashBeforeEofFrom, maxSlashPos + 1)
        }
        return finishSlashyLookahead(entryPos, false, braceStack, visitedOutside)
    }

    private fun finishSlashyLookahead(
        entryPos: Int,
        result: Boolean,
        braceStack: List<BraceFrame>,
        visitedOutside: List<Int>,
    ): Boolean {
        slashyCloseCache[entryPos] = result
        for (pos in visitedOutside) {
            outsideSlashyCloseCache[pos] = result
        }
        if (!result) {
            for (frame in braceStack) {
                interpCloseCache[frame.openPos] = InterpSpan(closePos = -1, maxSlashPos = frame.maxSlashPos)
            }
        }
        return result
    }

    private fun skipQuotedInLookahead(startOffset: Int, quoteCode: Int): Int {
        var offset = startOffset + 1
        val isTriple = _input.LA(offset) == quoteCode && _input.LA(offset + 1) == quoteCode
        if (isTriple) {
            offset += 2
            while (true) {
                val c = _input.LA(offset)
                if (c == IntStream.EOF) return offset
                if (c == '\\'.code) {
                    offset += if (_input.LA(offset + 1) == IntStream.EOF) 1 else 2
                } else if (c == quoteCode && _input.LA(offset + 1) == quoteCode && _input.LA(offset + 2) == quoteCode) {
                    return offset + 3
                } else {
                    offset++
                }
            }
        }
        while (true) {
            val c = _input.LA(offset)
            if (c == IntStream.EOF || c == '\r'.code || c == '\n'.code) return offset
            if (c == '\\'.code) {
                offset += if (_input.LA(offset + 1) == IntStream.EOF) 1 else 2
            } else if (c == quoteCode) {
                return offset + 1
            } else {
                offset++
            }
        }
    }

    override fun reset() {
        super.reset()
        interpStack.clear()
        lastNonHiddenType = Token.INVALID_TYPE
        lastNonHiddenText = null
        lastParenWasCondition = false
        lastBraceAllowedSlashy = true
        lastColonWasTernary = false
        lastGtWasGeneric = false
        ternaryDepth = 0
        parenDepth = 0
        brackDepth = 0
        angleDepth = 0
        sawNewlineSinceLastToken = false
        parenConditionStack.clear()
        braceAllowsSlashyStack.clear()
        savedBraceTernaryDepthStack.clear()
        savedParenTernaryDepthStack.clear()
        savedBrackTernaryDepthStack.clear()
        savedInterpTernaryDepthStack.clear()
        slashyCloseCache.clear()
        interpCloseCache.clear()
        outsideSlashyCloseCache.clear()
        noSlashBeforeEofFrom = Int.MAX_VALUE
    }

    override fun nextToken(): Token {
        val tok = super.nextToken()
        val tokType = tok.type
        if (tokType == Token.EOF) return tok

        if (isHiddenTokenType(tokType)) {
            if (tokType == GroovyLexer.Tokens.GDQ_UNTERM_NL) {
                sawNewlineSinceLastToken = true
            } else if (tokType == GroovyLexer.Tokens.WS) {
                val txt = tok.text
                if (txt != null && (txt.indexOf('\n') >= 0 || txt.indexOf('\r') >= 0)) {
                    sawNewlineSinceLastToken = true
                }
            }
            return tok
        }

        sawNewlineSinceLastToken = false
        val identText = if (tokType == GroovyLexer.Tokens.IDENTIFIER) tok.text else null

        when (tokType) {
            GroovyLexer.Tokens.LPAREN -> {
                parenDepth++
                parenConditionStack.add(isConditionKeyword(lastNonHiddenType))
                savedParenTernaryDepthStack.add(ternaryDepth)
                ternaryDepth = 0
                lastParenWasCondition = false
                lastBraceAllowedSlashy = false
                lastColonWasTernary = false
            }

            GroovyLexer.Tokens.RPAREN -> {
                if (parenDepth > 0) parenDepth--
                lastParenWasCondition = if (parenConditionStack.isNotEmpty()) {
                    parenConditionStack.removeAt(parenConditionStack.lastIndex)
                } else {
                    false
                }
                ternaryDepth = if (savedParenTernaryDepthStack.isNotEmpty()) {
                    savedParenTernaryDepthStack.removeAt(savedParenTernaryDepthStack.lastIndex)
                } else {
                    0
                }
                lastBraceAllowedSlashy = false
                lastColonWasTernary = false
            }

            GroovyLexer.Tokens.LBRACK -> {
                brackDepth++
                savedBrackTernaryDepthStack.add(ternaryDepth)
                ternaryDepth = 0
                lastParenWasCondition = false
                lastBraceAllowedSlashy = false
                lastColonWasTernary = false
            }

            GroovyLexer.Tokens.RBRACK -> {
                if (brackDepth > 0) brackDepth--
                ternaryDepth = if (savedBrackTernaryDepthStack.isNotEmpty()) {
                    savedBrackTernaryDepthStack.removeAt(savedBrackTernaryDepthStack.lastIndex)
                } else {
                    0
                }
                lastParenWasCondition = false
                lastBraceAllowedSlashy = false
                lastColonWasTernary = false
            }

            GroovyLexer.Tokens.LBRACE -> {
                val enclosingAllows = braceAllowsSlashyStack.lastOrNull() ?: true
                val opensBlock = opensBlockBraceAfterToken(
                    type = lastNonHiddenType,
                    text = lastNonHiddenText,
                    enclosingAllows = enclosingAllows,
                    lastColonWasTernary = lastColonWasTernary,
                )
                braceAllowsSlashyStack.add(opensBlock)
                savedBraceTernaryDepthStack.add(ternaryDepth)
                ternaryDepth = 0
                lastParenWasCondition = false
                lastBraceAllowedSlashy = false
                lastColonWasTernary = false
            }

            GroovyLexer.Tokens.RBRACE -> {
                lastBraceAllowedSlashy = if (braceAllowsSlashyStack.isNotEmpty()) {
                    braceAllowsSlashyStack.removeAt(braceAllowsSlashyStack.lastIndex)
                } else {
                    true
                }
                ternaryDepth = if (savedBraceTernaryDepthStack.isNotEmpty()) {
                    savedBraceTernaryDepthStack.removeAt(savedBraceTernaryDepthStack.lastIndex)
                } else {
                    0
                }
                lastParenWasCondition = false
                lastColonWasTernary = false
            }

            GroovyLexer.Tokens.GDQ_INTERP_START,
            GroovyLexer.Tokens.GTDQ_INTERP_START,
            GroovyLexer.Tokens.SL_INTERP_START,
            GroovyLexer.Tokens.DSL_INTERP_START,
            -> {
                savedInterpTernaryDepthStack.add(ternaryDepth)
                ternaryDepth = 0
                lastParenWasCondition = false
                lastBraceAllowedSlashy = false
                lastColonWasTernary = false
            }

            GroovyLexer.Tokens.GSTRING_INTERP_CLOSE -> {
                ternaryDepth = if (savedInterpTernaryDepthStack.isNotEmpty()) {
                    savedInterpTernaryDepthStack.removeAt(savedInterpTernaryDepthStack.lastIndex)
                } else {
                    0
                }
                lastParenWasCondition = false
                lastBraceAllowedSlashy = false
                lastColonWasTernary = false
            }

            GroovyLexer.Tokens.QUESTION -> {
                ternaryDepth++
                lastParenWasCondition = false
                lastBraceAllowedSlashy = false
                lastColonWasTernary = false
            }

            GroovyLexer.Tokens.COLON -> {
                if (ternaryDepth > 0) {
                    ternaryDepth--
                    lastColonWasTernary = true
                } else {
                    lastColonWasTernary = false
                }
                lastParenWasCondition = false
                lastBraceAllowedSlashy = false
            }

            GroovyLexer.Tokens.SEMI -> {
                ternaryDepth = 0
                angleDepth = 0
                lastParenWasCondition = false
                lastBraceAllowedSlashy = false
                lastColonWasTernary = false
                lastGtWasGeneric = false
            }

            GroovyLexer.Tokens.LT -> {
                if (looksLikeGenericOpen(lastNonHiddenType)) {
                    angleDepth++
                }
                lastParenWasCondition = false
                lastBraceAllowedSlashy = false
                lastColonWasTernary = false
                lastGtWasGeneric = false
            }

            GroovyLexer.Tokens.GT -> {
                if (angleDepth > 0) {
                    angleDepth--
                    lastGtWasGeneric = true
                } else {
                    lastGtWasGeneric = false
                }
                lastParenWasCondition = false
                lastBraceAllowedSlashy = false
                lastColonWasTernary = false
            }

            GroovyLexer.Tokens.RSHIFT -> {
                if (angleDepth > 0) {
                    angleDepth = (angleDepth - 2).coerceAtLeast(0)
                    lastGtWasGeneric = true
                } else {
                    lastGtWasGeneric = false
                }
                lastParenWasCondition = false
                lastBraceAllowedSlashy = false
                lastColonWasTernary = false
            }

            GroovyLexer.Tokens.URSHIFT -> {
                if (angleDepth > 0) {
                    angleDepth = (angleDepth - 3).coerceAtLeast(0)
                    lastGtWasGeneric = true
                } else {
                    lastGtWasGeneric = false
                }
                lastParenWasCondition = false
                lastBraceAllowedSlashy = false
                lastColonWasTernary = false
            }

            else -> {
                lastParenWasCondition = false
                lastBraceAllowedSlashy = false
                lastColonWasTernary = false
                lastGtWasGeneric = false
            }
        }

        lastNonHiddenType = tokType
        lastNonHiddenText = identText
        return tok
    }

    private fun looksLikeGenericOpen(prevType: Int): Boolean {
        if (prevType != GroovyLexer.Tokens.IDENTIFIER &&
            prevType != GroovyLexer.Tokens.DOT &&
            prevType != GroovyLexer.Tokens.LT
        ) {
            return false
        }
        var offset = 1
        var depth = 1
        var nestedParens = 0
        while (offset <= 64) {
            val c = _input.LA(offset)
            if (c == IntStream.EOF) return false
            when (c) {
                '('.code, '['.code -> nestedParens++
                ')'.code, ']'.code -> {
                    if (nestedParens == 0) return false
                    nestedParens--
                }
                ';'.code, '{'.code, '}'.code, '='.code, '&'.code, '|'.code -> {
                    if (nestedParens == 0) return false
                }
                '<'.code -> if (nestedParens == 0) depth++
                '>'.code -> if (nestedParens == 0) {
                    depth--
                    if (depth == 0) return true
                }
            }
            offset++
        }
        return false
    }

    private fun isHiddenTokenType(type: Int): Boolean = when (type) {
        GroovyLexer.Tokens.WS,
        GroovyLexer.Tokens.GDQ_UNTERM_NL,
        GroovyLexer.Tokens.LINE_COMMENT,
        GroovyLexer.Tokens.BLOCK_COMMENT,
        GroovyLexer.Tokens.UNTERMINATED_BLOCK_COMMENT,
        -> true
        else -> false
    }

    private fun isConditionKeyword(type: Int): Boolean = when (type) {
        GroovyLexer.Tokens.IF,
        GroovyLexer.Tokens.WHILE,
        GroovyLexer.Tokens.FOR,
        GroovyLexer.Tokens.SWITCH,
        GroovyLexer.Tokens.SYNCHRONIZED,
        GroovyLexer.Tokens.CATCH,
        -> true
        else -> false
    }

    private fun opensBlockBraceAfterToken(
        type: Int,
        text: String?,
        enclosingAllows: Boolean,
        lastColonWasTernary: Boolean,
    ): Boolean = when (type) {
        GroovyLexer.Tokens.ASSIGN,
        GroovyLexer.Tokens.ELVIS_ASSIGN,
        GroovyLexer.Tokens.ADD_ASSIGN,
        GroovyLexer.Tokens.SUB_ASSIGN,
        GroovyLexer.Tokens.MUL_ASSIGN,
        GroovyLexer.Tokens.DIV_ASSIGN,
        GroovyLexer.Tokens.MOD_ASSIGN,
        GroovyLexer.Tokens.STAR_STAR_ASSIGN,
        GroovyLexer.Tokens.AND_ASSIGN,
        GroovyLexer.Tokens.OR_ASSIGN,
        GroovyLexer.Tokens.XOR_ASSIGN,
        GroovyLexer.Tokens.LSHIFT_ASSIGN,
        GroovyLexer.Tokens.RSHIFT_ASSIGN,
        GroovyLexer.Tokens.URSHIFT_ASSIGN,
        GroovyLexer.Tokens.LPAREN,
        GroovyLexer.Tokens.LBRACK,
        GroovyLexer.Tokens.COMMA,
        GroovyLexer.Tokens.QUESTION,
        GroovyLexer.Tokens.ELVIS,
        GroovyLexer.Tokens.RETURN,
        GroovyLexer.Tokens.THROW,
        GroovyLexer.Tokens.ASSERT,
        -> false

        GroovyLexer.Tokens.LBRACE -> enclosingAllows
        GroovyLexer.Tokens.COLON -> enclosingAllows && !lastColonWasTernary
        GroovyLexer.Tokens.IDENTIFIER -> text != "yield"
        else -> true
    }

    private fun allowsSlashyAfterToken(
        type: Int,
        text: String?,
        lastParenWasCond: Boolean,
        lastBraceAllowedSlashy: Boolean,
        lastGtWasGeneric: Boolean,
    ): Boolean {
        if (type == GroovyLexer.Tokens.RPAREN) return lastParenWasCond
        if (type == GroovyLexer.Tokens.RBRACE) return lastBraceAllowedSlashy
        if (type == GroovyLexer.Tokens.GT ||
            type == GroovyLexer.Tokens.RSHIFT ||
            type == GroovyLexer.Tokens.URSHIFT
        ) {
            return !lastGtWasGeneric
        }
        return when (type) {
            Token.INVALID_TYPE,
            GroovyLexer.Tokens.LPAREN,
            GroovyLexer.Tokens.LBRACK,
            GroovyLexer.Tokens.LBRACE,
            GroovyLexer.Tokens.COMMA,
            GroovyLexer.Tokens.SEMI,
            GroovyLexer.Tokens.COLON,
            GroovyLexer.Tokens.QUESTION,
            GroovyLexer.Tokens.ARROW,
            GroovyLexer.Tokens.ELLIPSIS,
            GroovyLexer.Tokens.ASSIGN,
            GroovyLexer.Tokens.ELVIS_ASSIGN,
            GroovyLexer.Tokens.ELVIS,
            GroovyLexer.Tokens.ADD_ASSIGN,
            GroovyLexer.Tokens.SUB_ASSIGN,
            GroovyLexer.Tokens.MUL_ASSIGN,
            GroovyLexer.Tokens.DIV_ASSIGN,
            GroovyLexer.Tokens.MOD_ASSIGN,
            GroovyLexer.Tokens.STAR_STAR_ASSIGN,
            GroovyLexer.Tokens.AND_ASSIGN,
            GroovyLexer.Tokens.OR_ASSIGN,
            GroovyLexer.Tokens.XOR_ASSIGN,
            GroovyLexer.Tokens.LSHIFT_ASSIGN,
            GroovyLexer.Tokens.RSHIFT_ASSIGN,
            GroovyLexer.Tokens.URSHIFT_ASSIGN,
            GroovyLexer.Tokens.PLUS,
            GroovyLexer.Tokens.MINUS,
            GroovyLexer.Tokens.STAR,
            GroovyLexer.Tokens.SLASH,
            GroovyLexer.Tokens.MOD,
            GroovyLexer.Tokens.STAR_STAR,
            GroovyLexer.Tokens.BANG,
            GroovyLexer.Tokens.TILDE,
            GroovyLexer.Tokens.BITAND,
            GroovyLexer.Tokens.BITOR,
            GroovyLexer.Tokens.CARET,
            GroovyLexer.Tokens.AND,
            GroovyLexer.Tokens.OR,
            GroovyLexer.Tokens.LT,
            GroovyLexer.Tokens.GT,
            GroovyLexer.Tokens.LE,
            GroovyLexer.Tokens.GE,
            GroovyLexer.Tokens.EQ,
            GroovyLexer.Tokens.NE,
            GroovyLexer.Tokens.IDENTICAL,
            GroovyLexer.Tokens.NOT_IDENTICAL,
            GroovyLexer.Tokens.SPACESHIP,
            GroovyLexer.Tokens.REGEX_FIND,
            GroovyLexer.Tokens.REGEX_MATCH,
            GroovyLexer.Tokens.LSHIFT,
            GroovyLexer.Tokens.RSHIFT,
            GroovyLexer.Tokens.URSHIFT,
            GroovyLexer.Tokens.RANGE,
            GroovyLexer.Tokens.HALF_RANGE_RIGHT,
            GroovyLexer.Tokens.HALF_RANGE_LEFT,
            GroovyLexer.Tokens.HALF_RANGE_BOTH,
            GroovyLexer.Tokens.RETURN,
            GroovyLexer.Tokens.THROW,
            GroovyLexer.Tokens.CASE,
            GroovyLexer.Tokens.ASSERT,
            GroovyLexer.Tokens.IN,
            GroovyLexer.Tokens.INSTANCEOF,
            GroovyLexer.Tokens.ELSE,
            GroovyLexer.Tokens.DO,
            GroovyLexer.Tokens.GDQ_INTERP_START,
            GroovyLexer.Tokens.GTDQ_INTERP_START,
            GroovyLexer.Tokens.SL_INTERP_START,
            GroovyLexer.Tokens.DSL_INTERP_START,
            -> true

            GroovyLexer.Tokens.IDENTIFIER -> text == "yield"
            else -> false
        }
    }
}
