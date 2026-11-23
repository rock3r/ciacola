package dev.sebastiano.ciacola.syntax.antlr.python

import org.antlr.v4.kotlinruntime.CharStream
import org.antlr.v4.kotlinruntime.Lexer

/**
 * Pure Kotlin Multiplatform base lexer for [PythonLexer] tracking nested f-string
 * interpolations, conversion/format-spec scopes, lambda expressions, and brace/paren nesting in O(N).
 */
internal abstract class PythonLexerBase(input: CharStream) : Lexer(input) {

    private enum class FStringKind(val isSingleLine: Boolean, val isDoubleQuote: Boolean) {
        SINGLE(isSingleLine = true, isDoubleQuote = false),
        DOUBLE(isSingleLine = true, isDoubleQuote = true),
        TRIPLE_SINGLE(isSingleLine = false, isDoubleQuote = false),
        TRIPLE_DOUBLE(isSingleLine = false, isDoubleQuote = true),
    }

    private class InterpFrame(
        val preFStringModeStackSize: Int,
        var braceDepth: Int = 0,
        var parenDepth: Int = 0,
        var inFormatSpec: Boolean = false,
        var lambdaDepth: Int = -1,
        var atInterpLineStart: Boolean = false,
        var inStatementHeader: Boolean = false,
    )

    private val fstringKindStack = ArrayList<FStringKind>()
    private val fstringBaseStackSize = ArrayList<Int>()
    private val interpStack = ArrayList<InterpFrame>()

    protected fun enterFString(singleLine: Boolean, isDoubleQuote: Boolean, targetMode: Int) {
        val kind = when {
            singleLine && isDoubleQuote -> FStringKind.DOUBLE
            singleLine -> FStringKind.SINGLE
            isDoubleQuote -> FStringKind.TRIPLE_DOUBLE
            else -> FStringKind.TRIPLE_SINGLE
        }
        fstringKindStack.add(kind)
        fstringBaseStackSize.add(_modeStack.size())
        pushMode(targetMode)
    }

    protected fun exitFString() {
        if (fstringKindStack.isNotEmpty()) {
            fstringKindStack.removeAt(fstringKindStack.lastIndex)
            fstringBaseStackSize.removeAt(fstringBaseStackSize.lastIndex)
        }
        if (!_modeStack.isEmpty) {
            popMode()
        }
    }

    protected fun enterInterp(defaultMode: Int) {
        val baseSize = fstringBaseStackSize.lastOrNull() ?: 0
        interpStack.add(InterpFrame(preFStringModeStackSize = baseSize))
        pushMode(defaultMode)
    }

    protected fun onOpenParenOrBracket() {
        val top = interpStack.lastOrNull() ?: return
        if (!top.inFormatSpec) {
            top.parenDepth++
            top.atInterpLineStart = false
        }
    }

    protected fun onCloseParenOrBracket() {
        val top = interpStack.lastOrNull() ?: return
        if (!top.inFormatSpec) {
            if (top.parenDepth > 0) {
                top.parenDepth--
            }
            top.atInterpLineStart = false
        }
    }

    protected fun onLBrace() {
        val top = interpStack.lastOrNull() ?: return
        if (!top.inFormatSpec) {
            top.braceDepth++
            top.atInterpLineStart = false
        }
    }

    protected fun onRBrace(interpCloseTokenType: Int) {
        val top = interpStack.lastOrNull() ?: return
        if (top.braceDepth > 0) {
            top.braceDepth--
            top.atInterpLineStart = false
            return
        }
        interpStack.removeAt(interpStack.lastIndex)
        type = interpCloseTokenType
        if (!_modeStack.isEmpty) {
            popMode()
        }
    }

    protected fun onLambda() {
        val top = interpStack.lastOrNull() ?: return
        if (!top.inFormatSpec && top.lambdaDepth < 0) {
            top.lambdaDepth = top.parenDepth + top.braceDepth
        }
        top.atInterpLineStart = false
    }

    protected fun onStatementHeaderKeyword() {
        val top = interpStack.lastOrNull() ?: return
        if (!top.inFormatSpec && top.atInterpLineStart && top.parenDepth == 0 && top.braceDepth == 0) {
            top.inStatementHeader = true
        }
        top.atInterpLineStart = false
    }

    protected fun onName(lexeme: String) {
        val top = interpStack.lastOrNull() ?: return
        if (
            !top.inFormatSpec &&
            (lexeme == "match" || lexeme == "case") &&
            top.atInterpLineStart &&
            top.parenDepth == 0 &&
            top.braceDepth == 0
        ) {
            top.inStatementHeader = true
        }
        top.atInterpLineStart = false
    }

    protected fun onNonHeaderToken() {
        val top = interpStack.lastOrNull() ?: return
        top.atInterpLineStart = false
    }

    protected fun onSemicolon() {
        val top = interpStack.lastOrNull() ?: return
        if (top.parenDepth == 0 && top.braceDepth == 0) {
            top.atInterpLineStart = true
            top.inStatementHeader = false
            top.lambdaDepth = -1
        }
    }

    protected fun onDefaultNewline() {
        val top = interpStack.lastOrNull() ?: return
        top.atInterpLineStart = true
        if (top.parenDepth == 0 && top.braceDepth == 0) {
            top.inStatementHeader = false
            top.lambdaDepth = -1
            val currentKind = fstringKindStack.lastOrNull()
            if (currentKind != null && currentKind.isSingleLine && !hasBalancedSingleLineFStringClosing(currentKind)) {
                unwindSingleLineFStrings()
            }
        }
    }

    private fun hasBalancedSingleLineFStringClosing(kind: FStringKind): Boolean {
        val quoteChar = if (kind.isDoubleQuote) '"'.code else '\''.code
        var offset = 1
        var inInterpolation = true
        var braceDepth = 1
        var atLineStart = true

        while (offset <= SINGLE_LINE_FSTRING_LOOKAHEAD_LIMIT) {
            if (inInterpolation) {
                if (atLineStart) {
                    offset = skipHorizontalWs(offset)
                    if (offset > SINGLE_LINE_FSTRING_LOOKAHEAD_LIMIT) return false
                    val lineFirst = _input.LA(offset)
                    if (lineFirst == -1) return false
                    if (lineFirst == '\n'.code) {
                        offset++
                        continue
                    }
                    if (lineFirst == '\r'.code) {
                        offset += if (_input.LA(offset + 1) == '\n'.code) 2 else 1
                        continue
                    }
                    if (lineFirst == '#'.code) {
                        offset = skipToLineEnd(offset + 1)
                        continue
                    }
                    if (braceDepth == 1 && startsStatementKeyword(offset)) {
                        return false
                    }
                    atLineStart = false
                }

                val ch = _input.LA(offset)
                when {
                    ch == -1 -> return false
                    ch == '\n'.code -> {
                        offset++
                        atLineStart = true
                    }
                    ch == '\r'.code -> {
                        offset += if (_input.LA(offset + 1) == '\n'.code) 2 else 1
                        atLineStart = true
                    }
                    ch == '#'.code -> {
                        offset = skipToLineEnd(offset + 1)
                    }
                    ch == '{'.code -> {
                        braceDepth++
                        offset++
                    }
                    ch == '}'.code -> {
                        braceDepth--
                        offset++
                        if (braceDepth == 0) {
                            inInterpolation = false
                        }
                    }
                    ch == '\''.code || ch == '"'.code -> {
                        offset = skipQuotedLiteralInLookahead(offset, ch)
                        if (offset < 0) return false
                    }
                    else -> offset++
                }
            } else {
                val ch = _input.LA(offset)
                when {
                    ch == -1 || ch == '\n'.code || ch == '\r'.code -> return false
                    ch == quoteChar -> return true
                    ch == '\\'.code -> {
                        val next = _input.LA(offset + 1)
                        offset += when {
                            next == -1 -> 1
                            next == '\r'.code && _input.LA(offset + 2) == '\n'.code -> 3
                            else -> 2
                        }
                    }
                    ch == '{'.code -> {
                        if (_input.LA(offset + 1) == '{'.code) {
                            offset += 2
                        } else {
                            inInterpolation = true
                            braceDepth = 1
                            atLineStart = false
                            offset++
                        }
                    }
                    else -> offset++
                }
            }
        }
        return false
    }

    private fun skipHorizontalWs(startOffset: Int): Int {
        var offset = startOffset
        while (offset <= SINGLE_LINE_FSTRING_LOOKAHEAD_LIMIT) {
            val ch = _input.LA(offset)
            if (ch == ' '.code || ch == '\t'.code || ch == '\u000C'.code || ch == '\uFEFF'.code) {
                offset++
            } else {
                break
            }
        }
        return offset
    }

    private fun skipToLineEnd(startOffset: Int): Int {
        var offset = startOffset
        while (offset <= SINGLE_LINE_FSTRING_LOOKAHEAD_LIMIT) {
            val ch = _input.LA(offset)
            if (ch == -1 || ch == '\n'.code || ch == '\r'.code) break
            offset++
        }
        return offset
    }

    private fun skipQuotedLiteralInLookahead(startOffset: Int, quote: Int): Int {
        val isTriple = _input.LA(startOffset + 1) == quote && _input.LA(startOffset + 2) == quote
        var offset = startOffset + if (isTriple) 3 else 1
        while (offset <= SINGLE_LINE_FSTRING_LOOKAHEAD_LIMIT) {
            val ch = _input.LA(offset)
            when {
                ch == -1 -> return -1
                !isTriple && (ch == '\n'.code || ch == '\r'.code) -> return offset
                ch == '\\'.code -> {
                    val next = _input.LA(offset + 1)
                    offset += when {
                        next == -1 -> return -1
                        next == '\r'.code && _input.LA(offset + 2) == '\n'.code -> 3
                        else -> 2
                    }
                }
                ch == quote -> {
                    if (!isTriple) return offset + 1
                    if (_input.LA(offset + 1) == quote && _input.LA(offset + 2) == quote) {
                        return offset + 3
                    }
                    offset++
                }
                else -> offset++
            }
        }
        return -1
    }

    private fun startsStatementKeyword(offset: Int): Boolean {
        for (keyword in STATEMENT_STARTER_KEYWORDS) {
            if (matchesKeywordAt(offset, keyword)) {
                val after = _input.LA(offset + keyword.length)
                val validBoundary = when (keyword) {
                    "else", "try", "finally" -> {
                        val afterWs = skipHorizontalWs(offset + keyword.length)
                        _input.LA(afterWs) == ':'.code
                    }
                    "return" -> {
                        after == -1 ||
                            after == ' '.code ||
                            after == '\t'.code ||
                            after == '('.code ||
                            after == ';'.code ||
                            after == '\r'.code ||
                            after == '\n'.code
                    }
                    "if", "elif", "while", "with", "match" -> {
                        after == ' '.code || after == '\t'.code || after == '('.code
                    }
                    else -> after == ' '.code || after == '\t'.code
                }
                if (validBoundary) return true
            }
        }
        return false
    }

    private fun matchesKeywordAt(offset: Int, keyword: String): Boolean {
        for (i in keyword.indices) {
            if (_input.LA(offset + i) != keyword[i].code) return false
        }
        return true
    }

    private companion object {
        const val SINGLE_LINE_FSTRING_LOOKAHEAD_LIMIT = 512
        val STATEMENT_STARTER_KEYWORDS = arrayOf(
            "def",
            "class",
            "if",
            "elif",
            "else",
            "for",
            "while",
            "return",
            "import",
            "from",
            "try",
            "except",
            "finally",
            "with",
            "match",
            "async",
        )
    }

    protected fun onColon(formatSpecMode: Int, formatColonTokenType: Int) {
        val top = interpStack.lastOrNull() ?: return
        if (top.inFormatSpec) return
        top.atInterpLineStart = false
        val currentDepth = top.parenDepth + top.braceDepth
        if (top.lambdaDepth == currentDepth) {
            top.lambdaDepth = -1
            return
        }
        if (top.inStatementHeader && currentDepth == 0) {
            top.inStatementHeader = false
            return
        }
        if (currentDepth == 0) {
            top.inFormatSpec = true
            type = formatColonTokenType
            pushMode(formatSpecMode)
        }
    }

    protected fun onFormatSpecRBrace(interpCloseTokenType: Int) {
        type = interpCloseTokenType
        if (!_modeStack.isEmpty) {
            popMode()
        }
        if (interpStack.isNotEmpty()) {
            interpStack.removeAt(interpStack.lastIndex)
            if (!_modeStack.isEmpty) {
                popMode()
            }
        }
    }

    protected fun onFormatSpecNewline() {
        if (fstringKindStack.lastOrNull()?.isSingleLine == true) {
            unwindSingleLineFStrings()
        }
    }

    private fun unwindSingleLineFStrings() {
        while (fstringKindStack.isNotEmpty() && fstringKindStack.last().isSingleLine) {
            val targetStackSize = fstringBaseStackSize.removeAt(fstringBaseStackSize.lastIndex)
            fstringKindStack.removeAt(fstringKindStack.lastIndex)
            while (interpStack.isNotEmpty() && interpStack.last().preFStringModeStackSize >= targetStackSize) {
                interpStack.removeAt(interpStack.lastIndex)
            }
            while (_modeStack.size() > targetStackSize) {
                popMode()
            }
        }
    }

    override fun reset() {
        super.reset()
        fstringKindStack.clear()
        fstringBaseStackSize.clear()
        interpStack.clear()
    }
}
