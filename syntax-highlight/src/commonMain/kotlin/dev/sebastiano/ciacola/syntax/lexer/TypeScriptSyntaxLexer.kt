package dev.sebastiano.ciacola.syntax.lexer

import dev.sebastiano.ciacola.syntax.SyntaxLanguage
import dev.sebastiano.ciacola.syntax.SyntaxLexer
import dev.sebastiano.ciacola.syntax.SyntaxToken

/**
 * Lossless ANTLR4 DFA-based lexical tokenizer for TypeScript and TSX source code.
 */
internal object TypeScriptSyntaxLexer : SyntaxLexer {
    override val language: SyntaxLanguage = SyntaxLanguage.TypeScript

    override fun lex(source: String): List<SyntaxToken> =
        lexJsOrTs(source = source, isTypeScript = true)
}
