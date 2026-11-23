package dev.sebastiano.ciacola.syntax

import dev.sebastiano.ciacola.syntax.lexer.AgslSyntaxLexer
import dev.sebastiano.ciacola.syntax.lexer.CSyntaxLexer
import dev.sebastiano.ciacola.syntax.lexer.CppSyntaxLexer
import dev.sebastiano.ciacola.syntax.lexer.CssSyntaxLexer
import dev.sebastiano.ciacola.syntax.lexer.GlslSyntaxLexer
import dev.sebastiano.ciacola.syntax.lexer.GoSyntaxLexer
import dev.sebastiano.ciacola.syntax.lexer.HtmlSyntaxLexer
import dev.sebastiano.ciacola.syntax.lexer.JavaScriptSyntaxLexer
import dev.sebastiano.ciacola.syntax.lexer.JsonSyntaxLexer
import dev.sebastiano.ciacola.syntax.lexer.KotlinSyntaxLexer
import dev.sebastiano.ciacola.syntax.lexer.RustSyntaxLexer
import dev.sebastiano.ciacola.syntax.lexer.SkslSyntaxLexer
import dev.sebastiano.ciacola.syntax.lexer.TypeScriptSyntaxLexer
import dev.sebastiano.ciacola.syntax.lexer.XmlSyntaxLexer

/**
 * Lossless lexical tokenizer for a single [SyntaxLanguage].
 */
interface SyntaxLexer {
    val language: SyntaxLanguage

    /**
     * Tokenizes [source] into contiguous, non-overlapping [SyntaxToken] spans covering `[0, source.length)`.
     */
    fun lex(source: String): List<SyntaxToken>
}

/**
 * Registry and entry point for lexing source strings into [SyntaxToken] spans.
 */
object SyntaxHighlighter {
    private val lexers: Map<SyntaxLanguage, SyntaxLexer> = mapOf(
        SyntaxLanguage.Kotlin to KotlinSyntaxLexer,
        SyntaxLanguage.Xml to XmlSyntaxLexer,
        SyntaxLanguage.Json to JsonSyntaxLexer,
        SyntaxLanguage.Html to HtmlSyntaxLexer,
        SyntaxLanguage.Css to CssSyntaxLexer,
        SyntaxLanguage.JavaScript to JavaScriptSyntaxLexer,
        SyntaxLanguage.TypeScript to TypeScriptSyntaxLexer,
        SyntaxLanguage.Rust to RustSyntaxLexer,
        SyntaxLanguage.Go to GoSyntaxLexer,
        SyntaxLanguage.C to CSyntaxLexer,
        SyntaxLanguage.Cpp to CppSyntaxLexer,
        SyntaxLanguage.Glsl to GlslSyntaxLexer,
        SyntaxLanguage.Sksl to SkslSyntaxLexer,
        SyntaxLanguage.Agsl to AgslSyntaxLexer,
    )

    /** Languages that currently have a registered [SyntaxLexer]. */
    val supportedLanguages: Set<SyntaxLanguage> get() = lexers.keys

    /** Returns the [SyntaxLexer] for [language], or `null` if none is registered. */
    fun lexerOrNull(language: SyntaxLanguage): SyntaxLexer? = lexers[language]

    /**
     * Returns the [SyntaxLexer] for [language], throwing [IllegalArgumentException] if none is registered.
     */
    fun lexer(language: SyntaxLanguage): SyntaxLexer =
        lexers[language] ?: throw IllegalArgumentException("Unsupported syntax language: ${language.id}")

    /**
     * Lexes [source] using the registered lexer for [language].
     */
    fun lex(source: String, language: SyntaxLanguage): List<SyntaxToken> =
        lexer(language).lex(source)

    /**
     * Resolves [languageName] via [SyntaxLanguage.fromNameOrAlias] and lexes [source],
     * returning `null` if [languageName] is unknown or has no registered lexer.
     */
    fun lex(source: String, languageName: String): List<SyntaxToken>? {
        val language = SyntaxLanguage.fromNameOrAlias(languageName) ?: return null
        val lexer = lexerOrNull(language) ?: return null
        return lexer.lex(source)
    }
}
