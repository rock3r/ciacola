package dev.sebastiano.ciacola.syntax.antlr.dart

import org.antlr.v4.kotlinruntime.CharStream
import org.antlr.v4.kotlinruntime.IntStream
import org.antlr.v4.kotlinruntime.Lexer

/**
 * Pure Kotlin Multiplatform base lexer for [DartLexer], providing:
 * - iterative O(N) nested block comment scanning (`/* /* ... */ */`),
 * - O(N) raw multiline string scanning (`r'''...'''`, `r"""..."""`),
 * - arbitrarily nested `${expr}` interpolation mode-stack management, and
 * - bounded lookahead recovery on unclosed single-line interpolations.
 */
internal abstract class DartLexerBase(input: CharStream) : Lexer(input) {

    private class StringFrame(
        val quoteChar: Char,
        val isMultiline: Boolean,
        val preStringModeStackSize: Int,
        var inExpression: Boolean = false,
        var braceDepth: Int = 0,
    )

    private val stringStack = ArrayList<StringFrame>()

    override fun reset() {
        super.reset()
        stringStack.clear()
    }

    protected abstract fun singleLineSqMode(): Int
    protected abstract fun singleLineDqMode(): Int
    protected abstract fun multiLineSqMode(): Int
    protected abstract fun multiLineDqMode(): Int

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
     * Consumes the body and closing delimiter of a raw multiline Dart string (`r'''...'''` or `r"""..."""`).
     */
    protected fun consumeDartRawString(quoteChar: Char, quoteCount: Int) {
        val target = quoteChar.code
        var run = 0
        while (_input.LA(1) != IntStream.EOF) {
            val ch = _input.LA(1)
            _input.consume()
            if (ch == target) {
                run++
                if (run == quoteCount) {
                    return
                }
            } else {
                run = 0
            }
        }
    }

    protected fun enterDartString(quoteChar: Char, isMultiline: Boolean) {
        val targetMode = when {
            quoteChar == '\'' && isMultiline -> multiLineSqMode()
            quoteChar == '"' && isMultiline -> multiLineDqMode()
            quoteChar == '\'' -> singleLineSqMode()
            else -> singleLineDqMode()
        }
        stringStack.add(
            StringFrame(
                quoteChar = quoteChar,
                isMultiline = isMultiline,
                preStringModeStackSize = _modeStack.size(),
            ),
        )
        pushMode(targetMode)
    }

    protected fun exitDartString() {
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
        top.braceDepth = 0
        pushMode(defaultMode)
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
        top.inExpression = false
        type = interpCloseType
        if (!_modeStack.isEmpty) {
            popMode()
        }
    }

    protected fun onDefaultNewline() {
        val top = activeExprFrame() ?: return
        if (!top.isMultiline && !hasBalancedSingleLineInterpClosing()) {
            unwindSingleLineStrings()
        }
    }

    private fun activeExprFrame(): StringFrame? =
        stringStack.lastOrNull()?.takeIf { it.inExpression }

    private fun hasBalancedSingleLineInterpClosing(): Boolean {
        val top = stringStack.lastOrNull() ?: return true
        val afterExpr = skipExprHoleInLookahead(1, top.braceDepth + 1, depth = 0)
        if (afterExpr < 0) return false
        if (afterExpr > LOOKAHEAD_LIMIT) return true
        return scanSingleLineTail(afterExpr, top.quoteChar.code, depth = 0) > 0
    }

    private fun scanSingleLineTail(startOffset: Int, quoteCode: Int, depth: Int): Int {
        var offset = startOffset
        while (offset <= LOOKAHEAD_LIMIT) {
            when (_input.LA(offset)) {
                IntStream.EOF, '\r'.code, '\n'.code -> return -1
                quoteCode -> return offset + 1
                '\\'.code -> offset += 2
                '$'.code -> {
                    if (_input.LA(offset + 1) == '{'.code) {
                        val afterHole = skipExprHoleInLookahead(offset + 2, 1, depth + 1)
                        if (afterHole < 0) return -1
                        if (afterHole > LOOKAHEAD_LIMIT) return LOOKAHEAD_LIMIT + 1
                        offset = afterHole
                    } else {
                        offset++
                    }
                }
                else -> offset++
            }
        }
        return LOOKAHEAD_LIMIT + 1
    }

    private fun skipExprHoleInLookahead(startOffset: Int, initialBraceDepth: Int, depth: Int): Int {
        if (depth > MAX_LOOKAHEAD_DEPTH) return LOOKAHEAD_LIMIT + 1
        var offset = startOffset
        var braceDepth = initialBraceDepth
        while (offset <= LOOKAHEAD_LIMIT) {
            when (_input.LA(offset)) {
                IntStream.EOF -> return -1
                '{'.code -> {
                    braceDepth++
                    offset++
                }
                '}'.code -> {
                    braceDepth--
                    offset++
                    if (braceDepth == 0) return offset
                }
                'r'.code -> {
                    val q = _input.LA(offset + 1)
                    if (q == '\''.code || q == '"'.code) {
                        if (_input.LA(offset + 2) == q && _input.LA(offset + 3) == q) {
                            offset = skipRawMultilineInLookahead(offset + 4, q)
                        } else {
                            offset = skipRawSingleInLookahead(offset + 2, q)
                        }
                        if (offset < 0) return -1
                    } else {
                        offset++
                    }
                }
                '\''.code, '"'.code -> {
                    val q = _input.LA(offset)
                    if (_input.LA(offset + 1) == q && _input.LA(offset + 2) == q) {
                        offset = skipMultilineStringInLookahead(offset + 3, q, depth + 1)
                    } else {
                        offset = scanSingleLineTail(offset + 1, q, depth + 1)
                    }
                    if (offset < 0) return -1
                }
                '/'.code -> {
                    offset = when (_input.LA(offset + 1)) {
                        '/'.code -> skipToLineEnd(offset + 2)
                        '*'.code -> skipNestedBlockCommentInLookahead(offset + 2)
                        else -> offset + 1
                    }
                    if (offset < 0) return -1
                }
                else -> offset++
            }
        }
        return LOOKAHEAD_LIMIT + 1
    }

    private fun skipRawSingleInLookahead(startOffset: Int, quoteCode: Int): Int {
        var offset = startOffset
        while (offset <= LOOKAHEAD_LIMIT) {
            val ch = _input.LA(offset)
            if (ch == IntStream.EOF || ch == '\r'.code || ch == '\n'.code) return -1
            if (ch == quoteCode) return offset + 1
            offset++
        }
        return LOOKAHEAD_LIMIT + 1
    }

    private fun skipRawMultilineInLookahead(startOffset: Int, quoteCode: Int): Int {
        var offset = startOffset
        var run = 0
        while (offset <= LOOKAHEAD_LIMIT) {
            val ch = _input.LA(offset)
            if (ch == IntStream.EOF) return -1
            offset++
            if (ch == quoteCode) {
                run++
                if (run == 3) return offset
            } else {
                run = 0
            }
        }
        return LOOKAHEAD_LIMIT + 1
    }

    private fun skipMultilineStringInLookahead(startOffset: Int, quoteCode: Int, depth: Int): Int {
        var offset = startOffset
        while (offset <= LOOKAHEAD_LIMIT) {
            when (_input.LA(offset)) {
                IntStream.EOF -> return -1
                '\\'.code -> offset += 2
                '$'.code -> {
                    if (_input.LA(offset + 1) == '{'.code) {
                        offset = skipExprHoleInLookahead(offset + 2, 1, depth + 1)
                        if (offset < 0) return -1
                    } else {
                        offset++
                    }
                }
                quoteCode -> {
                    if (_input.LA(offset + 1) == quoteCode && _input.LA(offset + 2) == quoteCode) {
                        return offset + 3
                    }
                    offset++
                }
                else -> offset++
            }
        }
        return LOOKAHEAD_LIMIT + 1
    }

    private fun skipToLineEnd(startOffset: Int): Int {
        var offset = startOffset
        while (offset <= LOOKAHEAD_LIMIT) {
            val ch = _input.LA(offset)
            if (ch == IntStream.EOF || ch == '\r'.code || ch == '\n'.code) return offset
            offset++
        }
        return LOOKAHEAD_LIMIT + 1
    }

    private fun skipNestedBlockCommentInLookahead(startOffset: Int): Int {
        var offset = startOffset
        var depth = 1
        while (offset <= LOOKAHEAD_LIMIT && depth > 0) {
            val c1 = _input.LA(offset)
            if (c1 == IntStream.EOF) return -1
            if (c1 == '/'.code && _input.LA(offset + 1) == '*'.code) {
                depth++
                offset += 2
            } else if (c1 == '*'.code && _input.LA(offset + 1) == '/'.code) {
                depth--
                offset += 2
            } else {
                offset++
            }
        }
        return if (depth == 0) offset else LOOKAHEAD_LIMIT + 1
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
        const val LOOKAHEAD_LIMIT = 512
        const val MAX_LOOKAHEAD_DEPTH = 6
    }
}
