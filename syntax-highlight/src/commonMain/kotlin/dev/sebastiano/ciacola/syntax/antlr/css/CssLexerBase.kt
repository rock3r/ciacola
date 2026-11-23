package dev.sebastiano.ciacola.syntax.antlr.css

import org.antlr.v4.kotlinruntime.CharStream
import org.antlr.v4.kotlinruntime.Lexer
import org.antlr.v4.kotlinruntime.Token

/**
 * Pure Kotlin Multiplatform base lexer for CSS, entering unquoted `url(...)` argument mode
 * when `(` immediately follows a `url` identifier.
 */
internal abstract class CssLexerBase(input: CharStream) : Lexer(input) {
    private var lastWasUrlIdent: Boolean = false

    protected abstract fun isIdentType(type: Int): Boolean
    protected abstract fun isWhitespaceType(type: Int): Boolean

    override fun reset() {
        super.reset()
        lastWasUrlIdent = false
    }

    override fun nextToken(): Token {
        val tok = super.nextToken()
        if (tok.type != Token.EOF && !isWhitespaceType(tok.type)) {
            lastWasUrlIdent = isIdentType(tok.type) && tok.text.equals("url", ignoreCase = true)
        }
        return tok
    }

    protected fun onLparen(urlBodyMode: Int) {
        if (lastWasUrlIdent) {
            pushMode(urlBodyMode)
        }
    }
}
