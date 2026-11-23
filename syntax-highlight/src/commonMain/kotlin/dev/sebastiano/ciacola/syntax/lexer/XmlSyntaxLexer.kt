package dev.sebastiano.ciacola.syntax.lexer

import dev.sebastiano.ciacola.syntax.SyntaxLanguage
import dev.sebastiano.ciacola.syntax.SyntaxLexer
import dev.sebastiano.ciacola.syntax.SyntaxToken
import dev.sebastiano.ciacola.syntax.SyntaxTokenKind
import dev.sebastiano.ciacola.syntax.antlr.xml.XMLLexer
import dev.sebastiano.ciacola.syntax.antlr.xml.XMLLexer.Tokens as XmlTokens
import dev.sebastiano.ciacola.syntax.internal.driveAntlrLexer
import dev.sebastiano.ciacola.syntax.internal.emitDefaultGap
import org.antlr.v4.kotlinruntime.CharStreams

/**
 * Lossless ANTLR4 mode-switched DFA lexical tokenizer for XML source code.
 */
object XmlSyntaxLexer : SyntaxLexer {
    override val language: SyntaxLanguage = SyntaxLanguage.Xml

    override fun lex(source: String): List<SyntaxToken> {
        if (source.isEmpty()) return emptyList()

        val lexer = XMLLexer(CharStreams.fromString(source))
        val out = ArrayList<SyntaxToken>(source.length / 4 + 8)
        var expectingTagName = false

        driveAntlrLexer(
            source = source,
            lexer = lexer,
            onGap = { start, end ->
                emitDefaultGap(source, start, end) { kind, s, e ->
                    out += SyntaxToken(kind, s, e)
                }
            },
            onToken = { type, start, end ->
                when (type) {
                    XmlTokens.OPEN -> {
                        out += SyntaxToken(SyntaxTokenKind.Punctuation, start, end)
                        expectingTagName = true
                    }
                    XmlTokens.SLASH -> {
                        out += SyntaxToken(SyntaxTokenKind.Punctuation, start, end)
                    }
                    XmlTokens.XMLDeclOpen -> {
                        val declEnd = (start + 5).coerceAtMost(end)
                        out += SyntaxToken(SyntaxTokenKind.Keyword, start, declEnd)
                        if (declEnd < end) {
                            out += SyntaxToken(SyntaxTokenKind.Whitespace, declEnd, end)
                        }
                        expectingTagName = false
                    }
                    XmlTokens.CLOSE,
                    XmlTokens.SLASH_CLOSE,
                    XmlTokens.SPECIAL_CLOSE,
                    -> {
                        out += SyntaxToken(SyntaxTokenKind.Punctuation, start, end)
                        expectingTagName = false
                    }
                    XmlTokens.EQUALS -> {
                        out += SyntaxToken(SyntaxTokenKind.Punctuation, start, end)
                    }
                    XmlTokens.Name -> {
                        val kind = if (expectingTagName) SyntaxTokenKind.Tag else SyntaxTokenKind.Attribute
                        out += SyntaxToken(kind, start, end)
                        expectingTagName = false
                    }
                    XmlTokens.STRING,
                    XmlTokens.UNTERMINATED_STRING,
                    -> out += SyntaxToken(SyntaxTokenKind.String, start, end)
                    XmlTokens.COMMENT,
                    XmlTokens.UNTERMINATED_COMMENT,
                    -> out += SyntaxToken(SyntaxTokenKind.Comment, start, end)
                    XmlTokens.CDATA,
                    XmlTokens.UNTERMINATED_CDATA,
                    -> out += SyntaxToken(SyntaxTokenKind.String, start, end)
                    XmlTokens.PI,
                    XmlTokens.UNTERMINATED_PI,
                    XmlTokens.DTD,
                    -> out += SyntaxToken(SyntaxTokenKind.Keyword, start, end)
                    XmlTokens.EntityRef,
                    XmlTokens.CharRef,
                    -> out += SyntaxToken(SyntaxTokenKind.Constant, start, end)
                    XmlTokens.SEA_WS,
                    XmlTokens.S,
                    -> out += SyntaxToken(SyntaxTokenKind.Whitespace, start, end)
                    XmlTokens.TEXT -> out += SyntaxToken(SyntaxTokenKind.Plain, start, end)
                    else -> out += SyntaxToken(SyntaxTokenKind.Invalid, start, end)
                }
            },
        )

        return out
    }
}
