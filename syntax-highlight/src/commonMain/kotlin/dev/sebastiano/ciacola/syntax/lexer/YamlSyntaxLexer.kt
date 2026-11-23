package dev.sebastiano.ciacola.syntax.lexer

import dev.sebastiano.ciacola.syntax.SyntaxLanguage
import dev.sebastiano.ciacola.syntax.SyntaxLexer
import dev.sebastiano.ciacola.syntax.SyntaxToken
import dev.sebastiano.ciacola.syntax.SyntaxTokenKind
import dev.sebastiano.ciacola.syntax.antlr.yaml.YamlLexer
import dev.sebastiano.ciacola.syntax.antlr.yaml.YamlLexer.Tokens as YamlTokens
import dev.sebastiano.ciacola.syntax.internal.antlrCharStream
import dev.sebastiano.ciacola.syntax.internal.driveAntlrLexer
import dev.sebastiano.ciacola.syntax.internal.emitDefaultGap

/**
 * Lossless ANTLR4 DFA-based lexical tokenizer for YAML 1.2 source code.
 */
internal object YamlSyntaxLexer : SyntaxLexer {
    override val language: SyntaxLanguage = SyntaxLanguage.Yaml

    override fun lex(source: String): List<SyntaxToken> {
        if (source.isEmpty()) return emptyList()

        val lexer = YamlLexer(antlrCharStream(source))
        val rawTypes = ArrayList<Int>(source.length / 4 + 8)
        val rawStarts = ArrayList<Int>(source.length / 4 + 8)
        val rawEnds = ArrayList<Int>(source.length / 4 + 8)

        driveAntlrLexer(
            source = source,
            lexer = lexer,
            onGap = { start, end ->
                emitDefaultGap(source, start, end) { kind, s, e ->
                    rawTypes += if (kind == SyntaxTokenKind.Whitespace) YamlTokens.WS else YamlTokens.ERROR_CHAR
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

        val count = rawTypes.size
        val out = ArrayList<SyntaxToken>(count)
        for (i in 0 until count) {
            val type = rawTypes[i]
            val start = rawStarts[i]
            val end = rawEnds[i]
            val kind = when (type) {
                YamlTokens.WS,
                YamlTokens.NEWLINE,
                YamlTokens.DIR_WS,
                YamlTokens.DIR_NEWLINE,
                YamlTokens.BS_WS,
                YamlTokens.BS_NEWLINE,
                -> SyntaxTokenKind.Whitespace

                YamlTokens.COMMENT,
                YamlTokens.DIR_COMMENT,
                YamlTokens.BS_COMMENT,
                -> SyntaxTokenKind.Comment

                YamlTokens.DIRECTIVE_START,
                YamlTokens.LBRACE,
                YamlTokens.RBRACE,
                YamlTokens.LBRACK,
                YamlTokens.RBRACK,
                YamlTokens.COMMA,
                YamlTokens.COLON,
                YamlTokens.SEQUENCE_ENTRY,
                YamlTokens.COMPLEX_KEY,
                -> SyntaxTokenKind.Punctuation

                YamlTokens.DOC_START,
                YamlTokens.DOC_END,
                YamlTokens.DIR_NAME,
                YamlTokens.ANCHOR_SIGIL,
                YamlTokens.ALIAS_SIGIL,
                YamlTokens.BLOCK_SCALAR_START,
                YamlTokens.BS_CHOMP,
                -> SyntaxTokenKind.Keyword

                YamlTokens.DIR_TAG_HANDLE,
                YamlTokens.VERBATIM_TAG,
                YamlTokens.TAG,
                YamlTokens.ANCHOR_NAME,
                -> SyntaxTokenKind.Type

                YamlTokens.ALIAS_NAME,
                YamlTokens.DIR_ARG,
                -> SyntaxTokenKind.Plain

                YamlTokens.MERGE_KEY -> SyntaxTokenKind.Constant

                YamlTokens.BOOL,
                YamlTokens.NULL,
                YamlTokens.TIMESTAMP,
                -> if (isFollowedByColonOnSameLine(rawTypes, i + 1)) {
                    SyntaxTokenKind.Property
                } else {
                    SyntaxTokenKind.Constant
                }

                YamlTokens.DIR_VERSION,
                YamlTokens.BS_INDENT,
                -> SyntaxTokenKind.Number

                YamlTokens.HEX_INT,
                YamlTokens.OCT_INT,
                YamlTokens.FLOAT,
                YamlTokens.INT,
                YamlTokens.SPECIAL_FLOAT,
                -> if (isFollowedByColonOnSameLine(rawTypes, i + 1)) {
                    SyntaxTokenKind.Property
                } else {
                    SyntaxTokenKind.Number
                }

                YamlTokens.DOUBLE_QUOTED_STRING,
                YamlTokens.SINGLE_QUOTED_STRING,
                -> if (isSingleLine(source, start, end) && isFollowedByColonOnSameLine(rawTypes, i + 1)) {
                    SyntaxTokenKind.Property
                } else {
                    SyntaxTokenKind.String
                }

                YamlTokens.PLAIN_SCALAR -> if (isFollowedByColonOnSameLine(rawTypes, i + 1)) {
                    SyntaxTokenKind.Property
                } else {
                    SyntaxTokenKind.String
                }

                YamlTokens.UNTERMINATED_DOUBLE_QUOTED_STRING,
                YamlTokens.UNTERMINATED_SINGLE_QUOTED_STRING,
                YamlTokens.BLOCK_SCALAR_BODY,
                -> SyntaxTokenKind.String

                else -> SyntaxTokenKind.Invalid
            }
            out += SyntaxToken(kind = kind, startIndex = start, endIndex = end)
        }
        return out
    }
}

private fun isSingleLine(source: String, start: Int, end: Int): Boolean {
    for (i in start until end) {
        val ch = source[i]
        if (ch == '\n' || ch == '\r') return false
    }
    return true
}

private fun isFollowedByColonOnSameLine(rawTypes: List<Int>, fromIndex: Int): Boolean {
    var j = fromIndex
    while (j < rawTypes.size) {
        when (rawTypes[j]) {
            YamlTokens.WS -> j++
            YamlTokens.COLON -> return true
            else -> return false
        }
    }
    return false
}
