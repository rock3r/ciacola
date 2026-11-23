package dev.sebastiano.ciacola.syntax.antlr.csharp

import org.antlr.v4.kotlinruntime.CharStream
import org.antlr.v4.kotlinruntime.IntStream
import org.antlr.v4.kotlinruntime.Lexer

/**
 * Pure Kotlin Multiplatform base lexer for [CSharpLexer], handling line-start preprocessor
 * detection, symmetric C# 11+ raw string literals (`"""..."""`), and arbitrarily nested
 * interpolated strings (`$"..."`, `$@"..."`, `@$"..."`, `$$"""..."""`) including format
 * specifiers vs. ternary `? :` expressions.
 */
internal abstract class CSharpLexerBase(input: CharStream) : Lexer(input) {

    private enum class InterpKind {
        REGULAR,
        VERBATIM,
        RAW,
    }

    private class InterpFrame(
        val kind: InterpKind,
        val dollarCount: Int,
        val quoteCount: Int,
        val preStringModeStackSize: Int,
        var inExpression: Boolean = false,
        var braceDepth: Int = 0,
        var parenDepth: Int = 0,
        var bracketDepth: Int = 0,
        var ternaryDepth: Int = 0,
        var inFormatSpec: Boolean = false,
    ) {
        fun resetExprState() {
            inExpression = true
            braceDepth = 0
            parenDepth = 0
            bracketDepth = 0
            ternaryDepth = 0
            inFormatSpec = false
        }
    }

    private val interpStack = ArrayList<InterpFrame>()

    /**
     * Returns `true` when the `#` just consumed is preceded on its line only by horizontal
     * whitespace (C# specification §6.5.1).
     */
    protected fun isPreprocessorStart(): Boolean {
        var offset = 2
        while (true) {
            val ch = _input.LA(-offset)
            if (ch == IntStream.EOF || ch == '\n'.code || ch == '\r'.code) {
                return true
            }
            if (!isHorizontalWs(ch)) {
                return false
            }
            offset++
        }
    }

    /**
     * Consumes the body and matching closing delimiter of a non-interpolated C# raw string
     * literal (`"""..."""` with 3 or more quotes) plus an optional `u8`/`U8` UTF-8 suffix.
     */
    protected fun consumeCSharpRawString() {
        val quoteCount = (_input.index() - _tokenStartCharIndex).coerceAtLeast(3)
        while (_input.LA(1) != IntStream.EOF) {
            if (_input.LA(1) == '"'.code) {
                var run = 0
                while (run < quoteCount && _input.LA(1) == '"'.code) {
                    _input.consume()
                    run++
                }
                if (run == quoteCount) {
                    consumeOptionalU8Suffix()
                    return
                }
            } else {
                _input.consume()
            }
        }
    }

    private fun consumeOptionalU8Suffix() {
        val c1 = _input.LA(1)
        if ((c1 == 'u'.code || c1 == 'U'.code) && _input.LA(2) == '8'.code) {
            _input.consume()
            _input.consume()
        }
    }

    protected fun enterRegularInterp(targetMode: Int) {
        interpStack.add(
            InterpFrame(
                kind = InterpKind.REGULAR,
                dollarCount = 1,
                quoteCount = 1,
                preStringModeStackSize = _modeStack.size(),
            ),
        )
        pushMode(targetMode)
    }

    protected fun enterVerbatimInterp(targetMode: Int) {
        interpStack.add(
            InterpFrame(
                kind = InterpKind.VERBATIM,
                dollarCount = 1,
                quoteCount = 1,
                preStringModeStackSize = _modeStack.size(),
            ),
        )
        pushMode(targetMode)
    }

    protected fun enterRawInterp(targetMode: Int) {
        val lexeme = text
        var dollars = 0
        while (dollars < lexeme.length && lexeme[dollars] == '$') {
            dollars++
        }
        val quotes = (lexeme.length - dollars).coerceAtLeast(3)
        interpStack.add(
            InterpFrame(
                kind = InterpKind.RAW,
                dollarCount = dollars.coerceAtLeast(1),
                quoteCount = quotes,
                preStringModeStackSize = _modeStack.size(),
            ),
        )
        pushMode(targetMode)
    }

    protected fun exitInterpString() {
        if (interpStack.isNotEmpty()) {
            interpStack.removeAt(interpStack.lastIndex)
        }
        if (!_modeStack.isEmpty) {
            popMode()
        }
    }

    protected fun enterExpr(defaultMode: Int) {
        val top = interpStack.lastOrNull() ?: return
        top.resetExprState()
        pushMode(defaultMode)
    }

    protected fun onRawInterpLBrace(
        defaultMode: Int,
        interpOpenType: Int,
        fallbackTextType: Int,
    ) {
        val top = interpStack.lastOrNull() ?: return
        val count = text.length
        when {
            count < top.dollarCount -> {
                type = fallbackTextType
            }
            count == top.dollarCount -> {
                type = interpOpenType
                top.resetExprState()
                pushMode(defaultMode)
            }
            else -> {
                // Leave the last `dollarCount` braces in the stream to open the interpolation next.
                _input.seek(_tokenStartCharIndex + (count - top.dollarCount))
                type = fallbackTextType
            }
        }
    }

    protected fun onRawInterpQuotes(
        closeType: Int,
        fallbackTextType: Int,
    ) {
        val top = interpStack.lastOrNull() ?: return
        val count = text.length
        when {
            count < top.quoteCount -> {
                type = fallbackTextType
            }
            count == top.quoteCount -> {
                type = closeType
                exitInterpString()
            }
            else -> {
                _input.seek(_tokenStartCharIndex + top.quoteCount)
                type = closeType
                exitInterpString()
            }
        }
    }

    protected fun onOpenParen() {
        val top = activeExprFrame() ?: return
        top.parenDepth++
    }

    protected fun onCloseParen() {
        val top = activeExprFrame() ?: return
        if (top.parenDepth > 0) {
            top.parenDepth--
        }
    }

    protected fun onOpenBracket() {
        val top = activeExprFrame() ?: return
        top.bracketDepth++
    }

    protected fun onCloseBracket() {
        val top = activeExprFrame() ?: return
        if (top.bracketDepth > 0) {
            top.bracketDepth--
        }
    }

    protected fun onQuestionMark() {
        val top = activeExprFrame() ?: return
        if (top.parenDepth == 0 && top.bracketDepth == 0 && top.braceDepth == 0 && isTernaryQuestionAhead()) {
            top.ternaryDepth++
        }
    }

    protected fun onColon(formatSpecMode: Int, formatColonType: Int) {
        val top = activeExprFrame() ?: return
        if (top.parenDepth == 0 && top.bracketDepth == 0 && top.braceDepth == 0) {
            if (top.ternaryDepth > 0) {
                top.ternaryDepth--
            } else {
                top.inFormatSpec = true
                type = formatColonType
                pushMode(formatSpecMode)
            }
        }
    }

    protected fun onLBrace() {
        val top = activeExprFrame() ?: return
        top.braceDepth++
    }

    protected fun onRBrace(interpCloseType: Int) {
        val top = activeExprFrame() ?: return
        if (top.braceDepth > 0) {
            top.braceDepth--
            return
        }
        val neededExtra = top.dollarCount - 1
        if (neededExtra > 0) {
            for (offset in 1..neededExtra) {
                if (_input.LA(offset) != '}'.code) {
                    return
                }
            }
            repeat(neededExtra) { _input.consume() }
        }
        top.inExpression = false
        type = interpCloseType
        if (!_modeStack.isEmpty) {
            popMode()
        }
    }

    protected fun onDefaultNewline() {
        val top = activeExprFrame() ?: return
        if (
            top.kind == InterpKind.REGULAR &&
            top.parenDepth == 0 &&
            top.bracketDepth == 0 &&
            top.braceDepth == 0 &&
            !hasBalancedSingleLineInterpClosing()
        ) {
            unwindSingleLineInterps()
        }
    }

    protected fun onFormatSpecRBrace(interpCloseType: Int, fallbackTextType: Int) {
        val top = interpStack.lastOrNull() ?: return
        val count = text.length
        if (count < top.dollarCount) {
            type = fallbackTextType
            return
        }
        if (count > top.dollarCount) {
            _input.seek(_tokenStartCharIndex + top.dollarCount)
        }
        type = interpCloseType
        top.inFormatSpec = false
        top.inExpression = false
        // Pop INTERP_FORMAT_SPEC and DEFAULT_MODE (the expression mode).
        if (!_modeStack.isEmpty) popMode()
        if (!_modeStack.isEmpty) popMode()
    }

    protected fun onFormatSpecQuoteOrNewline(isNewline: Boolean, fallbackTextType: Int) {
        val top = interpStack.lastOrNull() ?: return
        if (top.kind == InterpKind.RAW) {
            type = fallbackTextType
            return
        }
        if (isNewline) {
            // Only single-line interpolated strings ($"...") unwind on newline.
            if (top.kind == InterpKind.REGULAR) {
                unwindSingleLineInterps()
            } else {
                type = fallbackTextType
            }
            return
        }
        if (top.kind == InterpKind.REGULAR) {
            unwindSingleLineInterps()
        } else {
            // Unclosed format specifier followed by closing quote in verbatim string:
            // exit format spec, expression, and the enclosing interpolated string cleanly.
            val targetStackSize = top.preStringModeStackSize
            interpStack.removeAt(interpStack.lastIndex)
            while (_modeStack.size() > targetStackSize) {
                popMode()
            }
        }
    }

    private fun activeExprFrame(): InterpFrame? =
        interpStack.lastOrNull()?.takeIf { it.inExpression && !it.inFormatSpec }

    private fun isTernaryQuestionAhead(): Boolean {
        var offset = 1
        while (offset <= LOOKAHEAD_LIMIT) {
            val ch = _input.LA(offset)
            if (ch == IntStream.EOF) return false
            if (isHorizontalWs(ch) || ch == '\n'.code || ch == '\r'.code) {
                offset++
                continue
            }
            return ch != ':'.code &&
                ch != '}'.code &&
                ch != ')'.code &&
                ch != ']'.code &&
                ch != ','.code &&
                ch != ';'.code &&
                ch != '?'.code &&
                ch != '.'.code &&
                ch != '['.code
        }
        return false
    }

    private fun hasBalancedSingleLineInterpClosing(): Boolean {
        var offset = 1
        var inExpr = true
        var braceDepth = 1
        while (offset <= LOOKAHEAD_LIMIT) {
            val ch = _input.LA(offset)
            if (ch == IntStream.EOF) return false
            if (inExpr) {
                when (ch) {
                    '{'.code -> {
                        braceDepth++
                        offset++
                    }
                    '}'.code -> {
                        braceDepth--
                        offset++
                        if (braceDepth == 0) {
                            inExpr = false
                        }
                    }
                    '"'.code -> {
                        offset = skipSimpleQuotedInLookahead(offset, '"'.code)
                        if (offset < 0) return false
                    }
                    '\''.code -> {
                        offset = skipSimpleQuotedInLookahead(offset, '\''.code)
                        if (offset < 0) return false
                    }
                    '/'.code -> {
                        val next = _input.LA(offset + 1)
                        if (next == '/'.code) {
                            offset = skipToLineEnd(offset + 2)
                        } else if (next == '*'.code) {
                            offset = skipBlockComment(offset + 2)
                            if (offset < 0) return false
                        } else {
                            offset++
                        }
                    }
                    else -> offset++
                }
            } else {
                when (ch) {
                    '\n'.code, '\r'.code -> return false
                    '"'.code -> return true
                    '\\'.code -> offset += 2
                    '{'.code -> {
                        if (_input.LA(offset + 1) == '{'.code) {
                            offset += 2
                        } else {
                            inExpr = true
                            braceDepth = 1
                            offset++
                        }
                    }
                    else -> offset++
                }
            }
        }
        return false
    }

    private fun skipSimpleQuotedInLookahead(startOffset: Int, quote: Int): Int {
        var offset = startOffset + 1
        while (offset <= LOOKAHEAD_LIMIT) {
            val ch = _input.LA(offset)
            when {
                ch == IntStream.EOF || ch == '\n'.code || ch == '\r'.code -> return -1
                ch == '\\'.code -> offset += 2
                ch == quote -> return offset + 1
                else -> offset++
            }
        }
        return -1
    }

    private fun skipToLineEnd(startOffset: Int): Int {
        var offset = startOffset
        while (offset <= LOOKAHEAD_LIMIT) {
            val ch = _input.LA(offset)
            if (ch == IntStream.EOF || ch == '\n'.code || ch == '\r'.code) return offset
            offset++
        }
        return offset
    }

    private fun skipBlockComment(startOffset: Int): Int {
        var offset = startOffset
        while (offset <= LOOKAHEAD_LIMIT) {
            val ch = _input.LA(offset)
            if (ch == IntStream.EOF) return -1
            if (ch == '*'.code && _input.LA(offset + 1) == '/'.code) return offset + 2
            offset++
        }
        return -1
    }

    private fun unwindSingleLineInterps() {
        while (interpStack.isNotEmpty() && interpStack.last().kind == InterpKind.REGULAR) {
            val frame = interpStack.removeAt(interpStack.lastIndex)
            while (_modeStack.size() > frame.preStringModeStackSize) {
                popMode()
            }
        }
    }

    private fun isHorizontalWs(ch: Int): Boolean =
        ch == ' '.code || ch == '\t'.code || ch == '\u000B'.code || ch == '\u000C'.code || ch == '\uFEFF'.code

    override fun reset() {
        super.reset()
        interpStack.clear()
    }

    private companion object {
        const val LOOKAHEAD_LIMIT = 512
    }
}
