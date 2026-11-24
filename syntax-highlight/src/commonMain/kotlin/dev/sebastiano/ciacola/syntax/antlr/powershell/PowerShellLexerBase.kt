package dev.sebastiano.ciacola.syntax.antlr.powershell

import org.antlr.v4.kotlinruntime.CharStream
import org.antlr.v4.kotlinruntime.IntStream
import org.antlr.v4.kotlinruntime.Lexer

/**
 * Pure Kotlin Multiplatform base lexer for [PowerShellLexer].
 *
 * Tracks word-boundary state for `#` line comments, column-0 detection for closing
 * `@"..."@` and `@'...'@` here-string delimiters, and iterative `$(...)` subexpression
 * parenthesis nesting across interpolated strings and here-strings.
 */
internal abstract class PowerShellLexerBase(input: CharStream) : Lexer(input) {

    private class SubExprFrame(
        val modeStackSize: Int,
        var parenDepth: Int = 0,
    )

    private val subExprStack = ArrayList<SubExprFrame>()
    private var atWordBoundary: Boolean = true

    protected fun isLineCommentStart(): Boolean = atWordBoundary

    protected fun onWhitespace() {
        atWordBoundary = true
    }

    protected fun onBoundaryToken() {
        atWordBoundary = true
    }

    protected fun onNonBoundaryToken() {
        atWordBoundary = false
    }

    /**
     * Returns `true` when the closing `"@` or `'@` delimiter just matched begins at column 0
     * (immediately preceded by `\n` or `\r`).
     */
    protected fun isAtLineStart(): Boolean {
        val consumed = _input.index() - _tokenStartCharIndex
        val prev = _input.LA(-consumed - 1)
        return prev == IntStream.EOF || prev == '\n'.code || prev == '\r'.code
    }

    protected fun enterSubExpr() {
        onNonBoundaryToken()
        pushMode(DEFAULT_MODE)
        subExprStack.add(SubExprFrame(modeStackSize = _modeStack.size()))
    }

    protected fun onDefaultLParen() {
        onBoundaryToken()
        val top = subExprStack.lastOrNull()
        if (top != null && _modeStack.size() == top.modeStackSize) {
            top.parenDepth++
        }
    }

    protected fun onDefaultRParen(subExprCloseType: Int) {
        val top = subExprStack.lastOrNull()
        if (top != null && _modeStack.size() == top.modeStackSize) {
            if (top.parenDepth > 0) {
                top.parenDepth--
                onNonBoundaryToken()
            } else {
                subExprStack.removeAt(subExprStack.lastIndex)
                if (!_modeStack.isEmpty) {
                    popMode()
                }
                type = subExprCloseType
                onNonBoundaryToken()
            }
        } else {
            onNonBoundaryToken()
        }
    }

    override fun reset() {
        super.reset()
        subExprStack.clear()
        atWordBoundary = true
    }
}
