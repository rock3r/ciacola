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
    private val interpCloseCache = HashMap<Int, Int>()
    private val outsideSlashyCloseCache = HashMap<Int, Boolean>()
    private val inBraceCloseCache = HashMap<Int, Int>()
    private val blockCommentBodyEndCache = HashMap<Int, Int>()
    private val lineCommentBodyEndCache = HashMap<Int, Int>()
    private val quoteBodyEndCaches = Array(4) { HashMap<Int, Int>() }

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
     * Checks in strictly O(N) amortized time (via exact finite-state memoization across
     * [slashyCloseCache], [outsideSlashyCloseCache], [interpCloseCache], [inBraceCloseCache],
     * [blockCommentBodyEndCache], [lineCommentBodyEndCache], and [quoteBodyEndCaches]) whether
     * a `/` at [_tokenStartCharIndex] has a matching unescaped closing `/` before EOF.
     */
    private fun hasClosingSlashyDelimiter(): Boolean {
        val entryPos = _tokenStartCharIndex
        slashyCloseCache[entryPos]?.let { return it }

        var offset = 1
        val baseIndex = _input.index()
        // Explicit iterative stack of open `${` ($ position) and `{` ({ position) offsets.
        // Empty means currently in slashy body (outside `${...}` interpolation).
        val braceStack = ArrayList<Int>()
        val braceVisitedStarts = ArrayList<Int>()
        val braceVisited = ArrayList<Int>()
        val visitedOutside = ArrayList<Int>()

        while (true) {
            val ch = _input.LA(offset)
            if (ch == IntStream.EOF) break
            val absPos = baseIndex + offset - 1

            if (braceStack.isEmpty()) {
                val cachedOutside = outsideSlashyCloseCache[absPos]
                if (cachedOutside != null) {
                    return finishSlashyLookahead(
                        entryPos = entryPos,
                        result = cachedOutside,
                        braceStack = braceStack,
                        visitedOutside = visitedOutside,
                        braceVisited = braceVisited,
                    )
                }
                visitedOutside.add(absPos)
                when (ch) {
                    '/'.code -> {
                        return finishSlashyLookahead(
                            entryPos = entryPos,
                            result = true,
                            braceStack = braceStack,
                            visitedOutside = visitedOutside,
                            braceVisited = braceVisited,
                        )
                    }
                    '\\'.code -> {
                        offset += if (_input.LA(offset + 1) == '/'.code) 2 else 1
                    }
                    '$'.code -> {
                        if (_input.LA(offset + 1) == '{'.code) {
                            val cachedClose = interpCloseCache[absPos]
                            if (cachedClose != null) {
                                if (cachedClose < 0) {
                                    return finishSlashyLookahead(
                                        entryPos = entryPos,
                                        result = false,
                                        braceStack = braceStack,
                                        visitedOutside = visitedOutside,
                                        braceVisited = braceVisited,
                                    )
                                }
                                offset = cachedClose - baseIndex + 2
                            } else {
                                braceStack.add(absPos)
                                braceVisitedStarts.add(braceVisited.size)
                                offset += 2
                            }
                        } else {
                            offset++
                        }
                    }
                    else -> offset++
                }
            } else {
                val cachedFrameClose = inBraceCloseCache[absPos]
                if (cachedFrameClose != null) {
                    if (cachedFrameClose < 0) {
                        return finishSlashyLookahead(
                            entryPos = entryPos,
                            result = false,
                            braceStack = braceStack,
                            visitedOutside = visitedOutside,
                            braceVisited = braceVisited,
                        )
                    }
                    popBraceFrame(braceStack, braceVisitedStarts, braceVisited, cachedFrameClose)
                    offset = cachedFrameClose - baseIndex + 2
                    continue
                }
                braceVisited.add(absPos)

                when (ch) {
                    '{'.code -> {
                        val cachedClose = interpCloseCache[absPos]
                        if (cachedClose != null) {
                            if (cachedClose < 0) {
                                return finishSlashyLookahead(
                                    entryPos = entryPos,
                                    result = false,
                                    braceStack = braceStack,
                                    visitedOutside = visitedOutside,
                                    braceVisited = braceVisited,
                                )
                            }
                            offset = cachedClose - baseIndex + 2
                        } else {
                            braceStack.add(absPos)
                            braceVisitedStarts.add(braceVisited.size)
                            offset++
                        }
                    }
                    '}'.code -> {
                        popBraceFrame(braceStack, braceVisitedStarts, braceVisited, absPos)
                        offset++
                    }
                    '"'.code -> {
                        offset = skipQuotedInLookahead(offset, '"'.code, baseIndex)
                    }
                    '\''.code -> {
                        offset = skipQuotedInLookahead(offset, '\''.code, baseIndex)
                    }
                    '/'.code -> {
                        val next = _input.LA(offset + 1)
                        offset = when (next) {
                            '/'.code -> skipLineCommentInLookahead(offset, baseIndex)
                            '*'.code -> skipBlockCommentInLookahead(offset, baseIndex)
                            else -> offset + 1
                        }
                    }
                    '$'.code -> {
                        if (_input.LA(offset + 1) == '{'.code) {
                            val cachedClose = interpCloseCache[absPos]
                            if (cachedClose != null) {
                                if (cachedClose < 0) {
                                    return finishSlashyLookahead(
                                        entryPos = entryPos,
                                        result = false,
                                        braceStack = braceStack,
                                        visitedOutside = visitedOutside,
                                        braceVisited = braceVisited,
                                    )
                                }
                                offset = cachedClose - baseIndex + 2
                            } else {
                                braceStack.add(absPos)
                                braceVisitedStarts.add(braceVisited.size)
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

        return finishSlashyLookahead(
            entryPos = entryPos,
            result = false,
            braceStack = braceStack,
            visitedOutside = visitedOutside,
            braceVisited = braceVisited,
        )
    }

    private fun popBraceFrame(
        braceStack: ArrayList<Int>,
        braceVisitedStarts: ArrayList<Int>,
        braceVisited: ArrayList<Int>,
        closePos: Int,
    ) {
        val openPos = braceStack.removeAt(braceStack.lastIndex)
        val frameStart = braceVisitedStarts.removeAt(braceVisitedStarts.lastIndex)
        interpCloseCache[openPos] = closePos
        while (braceVisited.size > frameStart) {
            inBraceCloseCache[braceVisited.removeAt(braceVisited.lastIndex)] = closePos
        }
    }

    private fun finishSlashyLookahead(
        entryPos: Int,
        result: Boolean,
        braceStack: List<Int>,
        visitedOutside: List<Int>,
        braceVisited: List<Int>,
    ): Boolean {
        slashyCloseCache[entryPos] = result
        for (pos in visitedOutside) {
            outsideSlashyCloseCache[pos] = result
        }
        if (!result) {
            for (openPos in braceStack) {
                interpCloseCache[openPos] = -1
            }
            for (pos in braceVisited) {
                inBraceCloseCache[pos] = -1
            }
        }
        return result
    }

    private fun skipLineCommentInLookahead(startOffset: Int, baseIndex: Int): Int {
        var offset = startOffset + 2
        val visited = ArrayList<Int>()
        while (true) {
            val c = _input.LA(offset)
            val curAbsPos = baseIndex + offset - 1
            if (c == IntStream.EOF || c == '\r'.code || c == '\n'.code) {
                for (pos in visited) {
                    lineCommentBodyEndCache[pos] = curAbsPos
                }
                return offset
            }
            lineCommentBodyEndCache[curAbsPos]?.let { endAbsPos ->
                for (pos in visited) {
                    lineCommentBodyEndCache[pos] = endAbsPos
                }
                return endAbsPos - baseIndex + 1
            }
            visited.add(curAbsPos)
            offset++
        }
    }

    private fun skipBlockCommentInLookahead(startOffset: Int, baseIndex: Int): Int {
        var offset = startOffset + 2
        val visited = ArrayList<Int>()
        while (true) {
            val c = _input.LA(offset)
            val curAbsPos = baseIndex + offset - 1
            if (c == IntStream.EOF) {
                for (pos in visited) {
                    blockCommentBodyEndCache[pos] = curAbsPos
                }
                return offset
            }
            blockCommentBodyEndCache[curAbsPos]?.let { endAbsPos ->
                for (pos in visited) {
                    blockCommentBodyEndCache[pos] = endAbsPos
                }
                return endAbsPos - baseIndex + 1
            }
            visited.add(curAbsPos)
            if (c == '*'.code && _input.LA(offset + 1) == '/'.code) {
                val endAbsPos = curAbsPos + 2
                for (pos in visited) {
                    blockCommentBodyEndCache[pos] = endAbsPos
                }
                return offset + 2
            }
            offset++
        }
    }

    private fun skipQuotedInLookahead(startOffset: Int, quoteCode: Int, baseIndex: Int): Int {
        var offset = startOffset + 1
        val isTriple = _input.LA(offset) == quoteCode && _input.LA(offset + 1) == quoteCode
        val cacheIdx = (if (quoteCode == '"'.code) 1 else 0) + (if (isTriple) 2 else 0)
        val cache = quoteBodyEndCaches[cacheIdx]
        if (isTriple) {
            offset += 2
        }
        val visited = ArrayList<Int>()

        while (true) {
            val c = _input.LA(offset)
            val curAbsPos = baseIndex + offset - 1
            val atEnd = if (isTriple) {
                c == IntStream.EOF
            } else {
                c == IntStream.EOF || c == '\r'.code || c == '\n'.code
            }
            if (atEnd) {
                for (pos in visited) {
                    cache[pos] = curAbsPos
                }
                return offset
            }
            cache[curAbsPos]?.let { endAbsPos ->
                for (pos in visited) {
                    cache[pos] = endAbsPos
                }
                return endAbsPos - baseIndex + 1
            }
            visited.add(curAbsPos)
            if (c == '\\'.code) {
                offset += if (_input.LA(offset + 1) == IntStream.EOF) 1 else 2
            } else if (isTriple) {
                if (c == quoteCode && _input.LA(offset + 1) == quoteCode && _input.LA(offset + 2) == quoteCode) {
                    val endAbsPos = curAbsPos + 3
                    for (pos in visited) {
                        cache[pos] = endAbsPos
                    }
                    return offset + 3
                }
                offset++
            } else if (c == quoteCode) {
                val endAbsPos = curAbsPos + 1
                for (pos in visited) {
                    cache[pos] = endAbsPos
                }
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
        inBraceCloseCache.clear()
        blockCommentBodyEndCache.clear()
        lineCommentBodyEndCache.clear()
        for (cache in quoteBodyEndCaches) {
            cache.clear()
        }
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
