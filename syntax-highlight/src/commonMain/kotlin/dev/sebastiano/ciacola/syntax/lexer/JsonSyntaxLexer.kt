package dev.sebastiano.ciacola.syntax.lexer

import dev.sebastiano.ciacola.syntax.SyntaxLanguage
import dev.sebastiano.ciacola.syntax.SyntaxLexer
import dev.sebastiano.ciacola.syntax.SyntaxToken
import dev.sebastiano.ciacola.syntax.SyntaxTokenKind
import dev.sebastiano.ciacola.syntax.antlr.json.JsonLexer
import dev.sebastiano.ciacola.syntax.antlr.json.JsonLexer.Tokens as JsonTokens
import dev.sebastiano.ciacola.syntax.internal.driveAntlrLexer
import dev.sebastiano.ciacola.syntax.internal.emitDefaultGap
import org.antlr.v4.kotlinruntime.CharStreams

/**
 * Lossless ANTLR4 DFA-based lexical tokenizer for JSON and JSONC (`//` and `/* */` comments),
 * with contextual lookahead to distinguish object property keys from string values.
 */
object JsonSyntaxLexer : SyntaxLexer {
    override val language: SyntaxLanguage = SyntaxLanguage.Json

    override fun lex(source: String): List<SyntaxToken> {
        if (source.isEmpty()) return emptyList()

        val lexer = JsonLexer(CharStreams.fromString(source))
        val rawTypes = ArrayList<Int>(source.length / 4 + 8)
        val rawStarts = ArrayList<Int>(source.length / 4 + 8)
        val rawEnds = ArrayList<Int>(source.length / 4 + 8)

        driveAntlrLexer(
            source = source,
            lexer = lexer,
            onGap = { start, end ->
                emitDefaultGap(source, start, end) { kind, s, e ->
                    rawTypes += if (kind == SyntaxTokenKind.Whitespace) JsonTokens.WS else JsonTokens.ERROR_CHAR
                    rawStarts += s
                    rawEnds += e
                }
            },
            onToken = { type, start, end ->
                rawTypes += type
                rawStarts += start
                rawEnds += end
            },
        )

        val out = ArrayList<SyntaxToken>(rawTypes.size)
        val count = rawTypes.size
        for (i in 0 until count) {
            val type = rawTypes[i]
            val kind = when (type) {
                JsonTokens.WS -> SyntaxTokenKind.Whitespace
                JsonTokens.LINE_COMMENT,
                JsonTokens.BLOCK_COMMENT,
                JsonTokens.UNTERMINATED_BLOCK_COMMENT,
                -> SyntaxTokenKind.Comment
                JsonTokens.STRING -> if (isFollowedByColon(rawTypes, i + 1)) {
                    SyntaxTokenKind.Property
                } else {
                    SyntaxTokenKind.String
                }
                JsonTokens.UNTERMINATED_STRING -> SyntaxTokenKind.String
                JsonTokens.LBRACE,
                JsonTokens.RBRACE,
                JsonTokens.LBRACK,
                JsonTokens.RBRACK,
                JsonTokens.COLON,
                JsonTokens.COMMA,
                -> SyntaxTokenKind.Punctuation
                JsonTokens.NUMBER -> SyntaxTokenKind.Number
                JsonTokens.TRUE,
                JsonTokens.FALSE,
                JsonTokens.NULL,
                -> SyntaxTokenKind.Constant
                JsonTokens.IDENTIFIER,
                JsonTokens.ERROR_CHAR,
                -> SyntaxTokenKind.Invalid
                else -> SyntaxTokenKind.Invalid
            }
            out += SyntaxToken(kind = kind, startIndex = rawStarts[i], endIndex = rawEnds[i])
        }
        return out
    }
}

private fun isFollowedByColon(rawTypes: List<Int>, fromIndex: Int): Boolean {
    var j = fromIndex
    while (j < rawTypes.size) {
        when (rawTypes[j]) {
            JsonTokens.WS,
            JsonTokens.LINE_COMMENT,
            JsonTokens.BLOCK_COMMENT,
            JsonTokens.UNTERMINATED_BLOCK_COMMENT,
            -> j++
            JsonTokens.COLON -> return true
            else -> return false
        }
    }
    return false
}
