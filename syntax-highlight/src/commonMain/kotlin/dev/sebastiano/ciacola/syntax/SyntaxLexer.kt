package dev.sebastiano.ciacola.syntax

import dev.sebastiano.ciacola.syntax.lexer.AgslSyntaxLexer
import dev.sebastiano.ciacola.syntax.lexer.CSyntaxLexer
import dev.sebastiano.ciacola.syntax.lexer.CSharpSyntaxLexer
import dev.sebastiano.ciacola.syntax.lexer.CppSyntaxLexer
import dev.sebastiano.ciacola.syntax.lexer.CssSyntaxLexer
import dev.sebastiano.ciacola.syntax.lexer.DartSyntaxLexer
import dev.sebastiano.ciacola.syntax.lexer.DiffSyntaxLexer
import dev.sebastiano.ciacola.syntax.lexer.DockerfileSyntaxLexer
import dev.sebastiano.ciacola.syntax.lexer.GlslSyntaxLexer
import dev.sebastiano.ciacola.syntax.lexer.GoSyntaxLexer
import dev.sebastiano.ciacola.syntax.lexer.GroovySyntaxLexer
import dev.sebastiano.ciacola.syntax.lexer.HclSyntaxLexer
import dev.sebastiano.ciacola.syntax.lexer.HtmlSyntaxLexer
import dev.sebastiano.ciacola.syntax.lexer.JavaScriptSyntaxLexer
import dev.sebastiano.ciacola.syntax.lexer.JavaSyntaxLexer
import dev.sebastiano.ciacola.syntax.lexer.JsonSyntaxLexer
import dev.sebastiano.ciacola.syntax.lexer.KotlinSyntaxLexer
import dev.sebastiano.ciacola.syntax.lexer.MarkdownSyntaxLexer
import dev.sebastiano.ciacola.syntax.lexer.PhpSyntaxLexer
import dev.sebastiano.ciacola.syntax.lexer.PowerShellSyntaxLexer
import dev.sebastiano.ciacola.syntax.lexer.ProtobufSyntaxLexer
import dev.sebastiano.ciacola.syntax.lexer.PythonSyntaxLexer
import dev.sebastiano.ciacola.syntax.lexer.RubySyntaxLexer
import dev.sebastiano.ciacola.syntax.lexer.RustSyntaxLexer
import dev.sebastiano.ciacola.syntax.lexer.ShellSyntaxLexer
import dev.sebastiano.ciacola.syntax.lexer.SkslSyntaxLexer
import dev.sebastiano.ciacola.syntax.lexer.SqlSyntaxLexer
import dev.sebastiano.ciacola.syntax.lexer.SwiftSyntaxLexer
import dev.sebastiano.ciacola.syntax.lexer.TomlSyntaxLexer
import dev.sebastiano.ciacola.syntax.lexer.TypeScriptSyntaxLexer
import dev.sebastiano.ciacola.syntax.lexer.XmlSyntaxLexer
import dev.sebastiano.ciacola.syntax.lexer.YamlSyntaxLexer

/**
 * Lossless lexical tokenizer for a single [SyntaxLanguage].
 */
public interface SyntaxLexer {
    public val language: SyntaxLanguage

    /**
     * Tokenizes [source] into contiguous, non-overlapping [SyntaxToken] spans covering `[0, source.length)`.
     */
    public fun lex(source: String): List<SyntaxToken>
}

/**
 * Registry and entry point for lexing source strings into [SyntaxToken] spans.
 */
public object SyntaxHighlighter {
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
        SyntaxLanguage.Python to PythonSyntaxLexer,
        SyntaxLanguage.Toml to TomlSyntaxLexer,
        SyntaxLanguage.Java to JavaSyntaxLexer,
        SyntaxLanguage.C to CSyntaxLexer,
        SyntaxLanguage.Cpp to CppSyntaxLexer,
        SyntaxLanguage.Glsl to GlslSyntaxLexer,
        SyntaxLanguage.Sksl to SkslSyntaxLexer,
        SyntaxLanguage.Agsl to AgslSyntaxLexer,
        SyntaxLanguage.Yaml to YamlSyntaxLexer,
        SyntaxLanguage.Sql to SqlSyntaxLexer,
        SyntaxLanguage.Shell to ShellSyntaxLexer,
        SyntaxLanguage.CSharp to CSharpSyntaxLexer,
        SyntaxLanguage.Php to PhpSyntaxLexer,
        SyntaxLanguage.Swift to SwiftSyntaxLexer,
        SyntaxLanguage.Ruby to RubySyntaxLexer,
        SyntaxLanguage.Dart to DartSyntaxLexer,
        SyntaxLanguage.Groovy to GroovySyntaxLexer,
        SyntaxLanguage.PowerShell to PowerShellSyntaxLexer,
        SyntaxLanguage.Dockerfile to DockerfileSyntaxLexer,
        SyntaxLanguage.Diff to DiffSyntaxLexer,
        SyntaxLanguage.Markdown to MarkdownSyntaxLexer,
        SyntaxLanguage.Protobuf to ProtobufSyntaxLexer,
        SyntaxLanguage.Hcl to HclSyntaxLexer,
    )

    /** Languages that currently have a registered [SyntaxLexer]. */
    public val supportedLanguages: Set<SyntaxLanguage> get() = lexers.keys

    /** Returns the [SyntaxLexer] for [language], or `null` if none is registered. */
    public fun lexerOrNull(language: SyntaxLanguage): SyntaxLexer? = lexers[language]

    /**
     * Returns the [SyntaxLexer] for [language], throwing [IllegalArgumentException] if none is registered.
     */
    public fun lexer(language: SyntaxLanguage): SyntaxLexer =
        lexers[language] ?: throw IllegalArgumentException("Unsupported syntax language: ${language.id}")

    /**
     * Lexes [source] using the registered lexer for [language].
     */
    public fun lex(source: String, language: SyntaxLanguage): List<SyntaxToken> =
        lexer(language).lex(source)

    /**
     * Resolves [languageName] via [SyntaxLanguage.fromNameOrAlias] and lexes [source],
     * returning `null` if [languageName] is unknown or has no registered lexer.
     */
    public fun lex(source: String, languageName: String): List<SyntaxToken>? {
        val language = SyntaxLanguage.fromNameOrAlias(languageName) ?: return null
        val lexer = lexerOrNull(language) ?: return null
        return lexer.lex(source)
    }
}
