package dev.sebastiano.ciacola.syntax.lexer

import dev.sebastiano.ciacola.syntax.SyntaxHighlighter
import dev.sebastiano.ciacola.syntax.SyntaxLanguage
import dev.sebastiano.ciacola.syntax.SyntaxToken

/**
 * Internal seam for lexers (such as Diff and Markdown) that can sub-highlight embedded
 * language slices at most one level deep without recursing into nested sub-highlighting.
 */
internal interface SubHighlightableSyntaxLexer {
    fun lex(source: String, allowNestedSubHighlighting: Boolean): List<SyntaxToken>
}

/**
 * Lexes [source] as [language] with nested sub-highlighting disabled, enforcing the
 * one-level-deep non-recursive sub-highlighting boundary.
 */
internal fun lexEmbeddedNoRecursion(source: String, language: SyntaxLanguage): List<SyntaxToken> {
    val lexer = SyntaxHighlighter.lexerOrNull(language) ?: return emptyList()
    return if (lexer is SubHighlightableSyntaxLexer) {
        lexer.lex(source, allowNestedSubHighlighting = false)
    } else {
        lexer.lex(source)
    }
}
