package dev.bnorm.storyboard.text.highlight

import dev.sebastiano.ciacola.syntax.SyntaxHighlighter
import dev.sebastiano.ciacola.syntax.SyntaxLanguage

typealias SyntaxToken = dev.sebastiano.ciacola.syntax.SyntaxToken
typealias SyntaxTokenKind = dev.sebastiano.ciacola.syntax.SyntaxTokenKind

/**
 * Tokenizes Kotlin source code into lossless, non-overlapping [SyntaxToken] spans.
 */
fun lexKotlinSyntax(code: String): List<SyntaxToken> =
    SyntaxHighlighter.lex(code, SyntaxLanguage.Kotlin)

/**
 * Tokenizes XML source code into lossless, non-overlapping [SyntaxToken] spans.
 */
fun lexXmlSyntax(code: String): List<SyntaxToken> =
    SyntaxHighlighter.lex(code, SyntaxLanguage.Xml)

/**
 * Tokenizes JSON (and JSONC comments) into lossless, non-overlapping [SyntaxToken] spans.
 */
fun lexJsonSyntax(code: String): List<SyntaxToken> =
    SyntaxHighlighter.lex(code, SyntaxLanguage.Json)
