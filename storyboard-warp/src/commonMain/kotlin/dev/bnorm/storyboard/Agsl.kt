package dev.bnorm.storyboard

import dev.sebastiano.ciacola.syntax.SyntaxHighlighter
import dev.sebastiano.ciacola.syntax.SyntaxLanguage
import dev.sebastiano.ciacola.syntax.SyntaxToken
import dev.sebastiano.ciacola.syntax.SyntaxTokenKind

/**
 * Lexical [Parser] for AGSL / SkSL / GLSL shader snippets used with `storyboard-warp`,
 * backed by `:syntax-highlight`'s ANTLR4 [SyntaxLanguage.Agsl] lexer.
 *
 * Emits atomic lexical tokens with TextMate-compatible scopes (`keyword.control.agsl`,
 * `storage.type.agsl`, `entity.name.function.agsl`, `variable.other.property.agsl`,
 * `constant.numeric.agsl`, `comment.line.double-slash.agsl`, `punctuation.agsl`) and preserves
 * blank lines with an empty placeholder token so row indexing in `DiffLists` always has at least
 * one token per line.
 */
object AgslParser : Parser {
    override fun parse(code: String): List<Token> {
        if (code.isEmpty()) return emptyList()

        val normalized = if ('\r' in code) {
            code.replace("\r\n", "\n").replace('\r', '\n')
        } else {
            code
        }
        val baseScope = Agsl.scopeName()
        val spans = SyntaxHighlighter.lex(normalized, SyntaxLanguage.Agsl)

        val out = ArrayList<Token>(spans.size + 4)
        var lineNumber = 0
        var lineStartOffset = 0
        var lineHasToken = false

        fun emitBlankLinePlaceholder() {
            out += Token(
                content = "",
                scope = baseScope,
                depth = 1,
                language = Agsl,
                allScopes = listOf(baseScope),
                lineNumber = lineNumber,
                startIndex = 0,
                endIndex = 0,
            )
        }

        for (span in spans) {
            val scope = agslScopeForSpan(span, normalized, baseScope)
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
                        language = Agsl,
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

        return out
    }

    private fun agslScopeForSpan(span: SyntaxToken, source: String, baseScope: String): String = when (span.kind) {
        SyntaxTokenKind.Whitespace -> baseScope
        SyntaxTokenKind.Plain -> "variable.other.agsl"
        SyntaxTokenKind.Keyword -> "keyword.control.agsl"
        SyntaxTokenKind.Type -> "storage.type.agsl"
        SyntaxTokenKind.Function -> "entity.name.function.agsl"
        SyntaxTokenKind.Property -> "variable.other.property.agsl"
        SyntaxTokenKind.Number -> "constant.numeric.agsl"
        SyntaxTokenKind.Constant -> "constant.language.agsl"
        SyntaxTokenKind.Comment -> if (source.startsWith("//", span.startIndex)) {
            "comment.line.double-slash.agsl"
        } else {
            "comment.block.agsl"
        }
        SyntaxTokenKind.Preprocessor -> "meta.annotation.preprocessor.agsl"
        SyntaxTokenKind.Annotation -> "meta.annotation.agsl"
        SyntaxTokenKind.Attribute -> "entity.other.attribute-name.agsl"
        SyntaxTokenKind.Tag -> "entity.name.tag.agsl"
        SyntaxTokenKind.String -> "string.quoted.agsl"
        SyntaxTokenKind.Operator,
        SyntaxTokenKind.Punctuation,
        -> "punctuation.agsl"
        SyntaxTokenKind.Invalid -> "invalid.illegal.agsl"
    }
}

/** First-class AGSL shader language for `storyboard-warp`. */
object Agsl : Language {
    override fun scopeName(): String = "source.agsl"
}
