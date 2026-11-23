package dev.sebastiano.ciacola.syntax.lexer

import dev.sebastiano.ciacola.syntax.SyntaxLanguage
import dev.sebastiano.ciacola.syntax.SyntaxLexer
import dev.sebastiano.ciacola.syntax.SyntaxToken

/**
 * Lossless ANTLR4 DFA-based lexical tokenizer for JavaScript and JSX source code.
 */
internal object JavaScriptSyntaxLexer : SyntaxLexer {
    override val language: SyntaxLanguage = SyntaxLanguage.JavaScript

    override fun lex(source: String): List<SyntaxToken> =
        lexJsOrTs(source = source, isTypeScript = false)
}
