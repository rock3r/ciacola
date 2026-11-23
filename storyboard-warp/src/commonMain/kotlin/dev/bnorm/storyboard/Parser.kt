package dev.bnorm.storyboard

import dev.sebastiano.ciacola.syntax.SyntaxHighlighter
import dev.sebastiano.ciacola.syntax.SyntaxLanguage
import dev.sebastiano.ciacola.syntax.SyntaxTokenKind

/**
 * A custom parser if you wanted full control on how to parse tokens.
 */
interface Parser {
    /**
     * Parses a multi-line code snippet into a sequence of [Token]s.
     */
    fun parse(code: String): List<Token>
}

/**
 * Parses [code] into line-indexed, syntax-scoped [Token]s for [language].
 *
 * Built-in languages (`Kotlin`, `Xml`, `Json`) use ANTLR4 DFA lexers from `:syntax-highlight` in
 * common code across all targets (JVM, JS, and WasmJs) with no regular-expression grammar engines.
 */
fun parse(code: String, language: Language): List<Token> = when (language) {
    is Custom -> language.parser.parse(code)
    Kotlin -> parseWithSyntaxLexer(code, language, SyntaxLanguage.Kotlin)
    Xml -> parseWithSyntaxLexer(code, language, SyntaxLanguage.Xml)
    Json -> parseWithSyntaxLexer(code, language, SyntaxLanguage.Json)
}

private fun parseWithSyntaxLexer(
    code: String,
    language: Language,
    syntaxLanguage: SyntaxLanguage,
): List<Token> {
    if (code.isEmpty()) return emptyList()

    val normalized = if ('\r' in code) {
        code.replace("\r\n", "\n").replace('\r', '\n')
    } else {
        code
    }
    val baseScope = language.scopeName()
    val suffix = syntaxLanguage.id
    val spans = SyntaxHighlighter.lex(normalized, syntaxLanguage)

    val out = ArrayList<Token>(spans.size * 2)
    var lineNumber = 0
    var lineStartOffset = 0
    var lineHasToken = false

    fun emitBlankLinePlaceholder() {
        out += Token(
            content = "",
            scope = baseScope,
            depth = 1,
            language = language,
            allScopes = listOf(baseScope),
            lineNumber = lineNumber,
            startIndex = 0,
            endIndex = 0,
        )
    }

    for (span in spans) {
        val scope = scopeForKind(span.kind, baseScope, suffix)
        val depth = if (scope == baseScope) 1 else 2
        val allScopes = if (scope == baseScope) listOf(baseScope) else listOf(baseScope, scope)

        var pos = span.startIndex
        val end = span.endIndex
        while (pos < end) {
            var segEnd = pos
            while (segEnd < end && normalized[segEnd] != '\n') segEnd++
            val hitNewline = segEnd < end
            if (segEnd > pos) {
                out += Token(
                    content = normalized.substring(pos, segEnd),
                    scope = scope,
                    depth = depth,
                    language = language,
                    allScopes = allScopes,
                    lineNumber = lineNumber,
                    startIndex = pos - lineStartOffset,
                    endIndex = segEnd - lineStartOffset,
                )
                lineHasToken = true
            }
            if (hitNewline) {
                if (!lineHasToken) {
                    emitBlankLinePlaceholder()
                }
                lineNumber++
                lineStartOffset = segEnd + 1
                lineHasToken = false
                pos = segEnd + 1
            } else {
                pos = end
            }
        }
    }

    if (!lineHasToken) {
        emitBlankLinePlaceholder()
    }

    return out.splitIntoLexicalTokens()
}

private fun scopeForKind(kind: SyntaxTokenKind, baseScope: String, suffix: String): String = when (kind) {
    SyntaxTokenKind.Plain,
    SyntaxTokenKind.Whitespace,
    -> baseScope
    SyntaxTokenKind.Keyword -> "keyword.control.$suffix"
    SyntaxTokenKind.String -> "string.quoted.$suffix"
    SyntaxTokenKind.Comment -> "comment.$suffix"
    SyntaxTokenKind.Number -> "constant.numeric.$suffix"
    SyntaxTokenKind.Constant -> "constant.language.$suffix"
    SyntaxTokenKind.Function -> "entity.name.function.$suffix"
    SyntaxTokenKind.Type -> "entity.name.type.$suffix"
    SyntaxTokenKind.Property -> "variable.other.property.$suffix"
    SyntaxTokenKind.Annotation -> "meta.annotation.$suffix"
    SyntaxTokenKind.Attribute -> "entity.other.attribute-name.$suffix"
    SyntaxTokenKind.Tag -> "entity.name.tag.$suffix"
    SyntaxTokenKind.Operator -> "punctuation.operator.$suffix"
    SyntaxTokenKind.Preprocessor -> "meta.annotation.preprocessor.$suffix"
    SyntaxTokenKind.Punctuation -> "punctuation.$suffix"
    SyntaxTokenKind.Invalid -> "invalid.illegal.$suffix"
}
