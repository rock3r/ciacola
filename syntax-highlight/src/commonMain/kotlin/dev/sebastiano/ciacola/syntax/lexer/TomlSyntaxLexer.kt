package dev.sebastiano.ciacola.syntax.lexer

import dev.sebastiano.ciacola.syntax.SyntaxLanguage
import dev.sebastiano.ciacola.syntax.SyntaxLexer
import dev.sebastiano.ciacola.syntax.SyntaxToken
import dev.sebastiano.ciacola.syntax.SyntaxTokenKind
import dev.sebastiano.ciacola.syntax.antlr.toml.TomlLexer
import dev.sebastiano.ciacola.syntax.antlr.toml.TomlLexer.Tokens as TomlTokens
import dev.sebastiano.ciacola.syntax.internal.antlrCharStream
import dev.sebastiano.ciacola.syntax.internal.driveAntlrLexer
import dev.sebastiano.ciacola.syntax.internal.emitDefaultGap

/**
 * Lossless ANTLR4 DFA-based lexical tokenizer for TOML 1.0/1.1 source code.
 */
internal object TomlSyntaxLexer : SyntaxLexer {
    override val language: SyntaxLanguage = SyntaxLanguage.Toml

    override fun lex(source: String): List<SyntaxToken> {
        if (source.isEmpty()) return emptyList()

        val lexer = TomlLexer(antlrCharStream(source))
        val raw = ArrayList<RawTomlToken>(source.length / 4 + 8)

        driveAntlrLexer(
            source = source,
            lexer = lexer,
            onGap = { start, end ->
                emitDefaultGap(source, start, end) { kind, s, e ->
                    raw += RawTomlToken(
                        type = if (kind == SyntaxTokenKind.Whitespace) TomlTokens.WS else TomlTokens.ERROR_CHAR,
                        start = s,
                        end = e,
                        kind = kind,
                        isHidden = kind == SyntaxTokenKind.Whitespace,
                    )
                }
            },
            onToken = { type, start, end -> emitRawTomlToken(type, start, end, raw) },
        )

        classifyTomlTokens(raw)
        return raw.map { SyntaxToken(kind = it.kind, startIndex = it.start, endIndex = it.end) }
    }
}

private class RawTomlToken(
    val type: Int,
    val start: Int,
    val end: Int,
    var kind: SyntaxTokenKind,
    val isHidden: Boolean,
    val isNewline: Boolean = false,
)

private sealed interface TomlContainer {
    data object Array : TomlContainer
    data class InlineTable(var expectingValue: Boolean = false) : TomlContainer
}

private fun emitRawTomlToken(
    type: Int,
    start: Int,
    end: Int,
    out: MutableList<RawTomlToken>,
) {
    when (type) {
        TomlTokens.WS ->
            out += RawTomlToken(type, start, end, SyntaxTokenKind.Whitespace, isHidden = true)

        TomlTokens.NEWLINE ->
            out += RawTomlToken(type, start, end, SyntaxTokenKind.Whitespace, isHidden = true, isNewline = true)

        TomlTokens.COMMENT ->
            out += RawTomlToken(type, start, end, SyntaxTokenKind.Comment, isHidden = true)

        TomlTokens.EQUALS ->
            out += RawTomlToken(type, start, end, SyntaxTokenKind.Operator, isHidden = false)

        TomlTokens.LBRACK,
        TomlTokens.RBRACK,
        TomlTokens.LBRACE,
        TomlTokens.RBRACE,
        TomlTokens.COMMA,
        TomlTokens.DOT,
        -> out += RawTomlToken(type, start, end, SyntaxTokenKind.Punctuation, isHidden = false)

        TomlTokens.ML_BASIC_STRING,
        TomlTokens.UNTERMINATED_ML_BASIC_STRING,
        TomlTokens.ML_LITERAL_STRING,
        TomlTokens.UNTERMINATED_ML_LITERAL_STRING,
        TomlTokens.BASIC_STRING,
        TomlTokens.UNTERMINATED_BASIC_STRING,
        TomlTokens.LITERAL_STRING,
        TomlTokens.UNTERMINATED_LITERAL_STRING,
        -> out += RawTomlToken(type, start, end, SyntaxTokenKind.String, isHidden = false)

        TomlTokens.HEX_INT,
        TomlTokens.OCT_INT,
        TomlTokens.BIN_INT,
        TomlTokens.FLOAT,
        TomlTokens.DEC_INT,
        TomlTokens.SPECIAL_FLOAT,
        -> out += RawTomlToken(type, start, end, SyntaxTokenKind.Number, isHidden = false)

        TomlTokens.BOOL,
        TomlTokens.DATE_TIME,
        TomlTokens.TIME,
        -> out += RawTomlToken(type, start, end, SyntaxTokenKind.Constant, isHidden = false)

        else ->
            out += RawTomlToken(type, start, end, SyntaxTokenKind.Invalid, isHidden = false)
    }
}

private fun classifyTomlTokens(raw: List<RawTomlToken>) {
    val containers = ArrayList<TomlContainer>()
    var inHeader = false
    var headerBracketDepth = 0
    var headerSawKey = false
    var topLevelExpectingValue = false

    for (tok in raw) {
        if (tok.isHidden) {
            if (tok.isNewline && containers.isEmpty()) {
                inHeader = false
                headerBracketDepth = 0
                headerSawKey = false
                topLevelExpectingValue = false
            }
            continue
        }

        val currentContainer = containers.lastOrNull()
        if (currentContainer == null) {
            when {
                inHeader -> {
                    when (tok.type) {
                        TomlTokens.LBRACK -> {
                            tok.kind = SyntaxTokenKind.Punctuation
                            if (!headerSawKey) headerBracketDepth++
                        }

                        TomlTokens.RBRACK -> {
                            tok.kind = SyntaxTokenKind.Punctuation
                            if (headerBracketDepth > 0) headerBracketDepth--
                            if (headerBracketDepth == 0) {
                                inHeader = false
                            }
                        }

                        TomlTokens.DOT -> tok.kind = SyntaxTokenKind.Punctuation
                        else -> {
                            if (isTomlKeyToken(tok.type)) {
                                tok.kind = SyntaxTokenKind.Tag
                                headerSawKey = true
                            } else {
                                tok.kind = SyntaxTokenKind.Invalid
                            }
                        }
                    }
                }

                !topLevelExpectingValue -> {
                    when (tok.type) {
                        TomlTokens.LBRACK -> {
                            tok.kind = SyntaxTokenKind.Punctuation
                            inHeader = true
                            headerBracketDepth = 1
                            headerSawKey = false
                        }

                        TomlTokens.EQUALS -> {
                            tok.kind = SyntaxTokenKind.Operator
                            topLevelExpectingValue = true
                        }

                        TomlTokens.DOT -> tok.kind = SyntaxTokenKind.Punctuation
                        else -> {
                            tok.kind = if (isTomlKeyToken(tok.type)) SyntaxTokenKind.Property else SyntaxTokenKind.Invalid
                        }
                    }
                }

                else -> {
                    when (tok.type) {
                        TomlTokens.LBRACK -> {
                            tok.kind = SyntaxTokenKind.Punctuation
                            containers += TomlContainer.Array
                        }

                        TomlTokens.LBRACE -> {
                            tok.kind = SyntaxTokenKind.Punctuation
                            containers += TomlContainer.InlineTable(expectingValue = false)
                        }

                        else -> tok.kind = classifyTomlValueKind(tok.type)
                    }
                }
            }
        } else {
            when (currentContainer) {
                is TomlContainer.Array -> {
                    when (tok.type) {
                        TomlTokens.LBRACK -> {
                            tok.kind = SyntaxTokenKind.Punctuation
                            containers += TomlContainer.Array
                        }

                        TomlTokens.RBRACK -> {
                            tok.kind = SyntaxTokenKind.Punctuation
                            containers.removeAt(containers.lastIndex)
                        }

                        TomlTokens.LBRACE -> {
                            tok.kind = SyntaxTokenKind.Punctuation
                            containers += TomlContainer.InlineTable(expectingValue = false)
                        }

                        TomlTokens.COMMA -> tok.kind = SyntaxTokenKind.Punctuation
                        else -> tok.kind = classifyTomlValueKind(tok.type)
                    }
                }

                is TomlContainer.InlineTable -> {
                    if (!currentContainer.expectingValue) {
                        when (tok.type) {
                            TomlTokens.RBRACE -> {
                                tok.kind = SyntaxTokenKind.Punctuation
                                containers.removeAt(containers.lastIndex)
                            }

                            TomlTokens.EQUALS -> {
                                tok.kind = SyntaxTokenKind.Operator
                                currentContainer.expectingValue = true
                            }

                            TomlTokens.DOT -> tok.kind = SyntaxTokenKind.Punctuation
                            else -> {
                                tok.kind = if (isTomlKeyToken(tok.type)) SyntaxTokenKind.Property else SyntaxTokenKind.Invalid
                            }
                        }
                    } else {
                        when (tok.type) {
                            TomlTokens.COMMA -> {
                                tok.kind = SyntaxTokenKind.Punctuation
                                currentContainer.expectingValue = false
                            }

                            TomlTokens.RBRACE -> {
                                tok.kind = SyntaxTokenKind.Punctuation
                                containers.removeAt(containers.lastIndex)
                            }

                            TomlTokens.LBRACK -> {
                                tok.kind = SyntaxTokenKind.Punctuation
                                containers += TomlContainer.Array
                            }

                            TomlTokens.LBRACE -> {
                                tok.kind = SyntaxTokenKind.Punctuation
                                containers += TomlContainer.InlineTable(expectingValue = false)
                            }

                            else -> tok.kind = classifyTomlValueKind(tok.type)
                        }
                    }
                }
            }
        }
    }
}

private fun isTomlKeyToken(type: Int): Boolean = when (type) {
    TomlTokens.BARE_KEY,
    TomlTokens.BASIC_STRING,
    TomlTokens.LITERAL_STRING,
    TomlTokens.BOOL,
    TomlTokens.SPECIAL_FLOAT,
    TomlTokens.DEC_INT,
    TomlTokens.HEX_INT,
    TomlTokens.OCT_INT,
    TomlTokens.BIN_INT,
    TomlTokens.FLOAT,
    TomlTokens.DATE_TIME,
    TomlTokens.TIME,
    -> true
    else -> false
}

private fun classifyTomlValueKind(type: Int): SyntaxTokenKind = when (type) {
    TomlTokens.ML_BASIC_STRING,
    TomlTokens.UNTERMINATED_ML_BASIC_STRING,
    TomlTokens.ML_LITERAL_STRING,
    TomlTokens.UNTERMINATED_ML_LITERAL_STRING,
    TomlTokens.BASIC_STRING,
    TomlTokens.UNTERMINATED_BASIC_STRING,
    TomlTokens.LITERAL_STRING,
    TomlTokens.UNTERMINATED_LITERAL_STRING,
    -> SyntaxTokenKind.String

    TomlTokens.HEX_INT,
    TomlTokens.OCT_INT,
    TomlTokens.BIN_INT,
    TomlTokens.FLOAT,
    TomlTokens.DEC_INT,
    TomlTokens.SPECIAL_FLOAT,
    -> SyntaxTokenKind.Number

    TomlTokens.BOOL,
    TomlTokens.DATE_TIME,
    TomlTokens.TIME,
    -> SyntaxTokenKind.Constant

    TomlTokens.COMMA,
    TomlTokens.LBRACK,
    TomlTokens.RBRACK,
    TomlTokens.LBRACE,
    TomlTokens.RBRACE,
    -> SyntaxTokenKind.Punctuation

    else -> SyntaxTokenKind.Invalid
}
