package dev.sebastiano.ciacola.syntax.lexer

import dev.sebastiano.ciacola.syntax.SyntaxLanguage
import dev.sebastiano.ciacola.syntax.SyntaxLexer
import dev.sebastiano.ciacola.syntax.SyntaxToken
import dev.sebastiano.ciacola.syntax.SyntaxTokenKind
import dev.sebastiano.ciacola.syntax.antlr.xml.XMLLexer
import dev.sebastiano.ciacola.syntax.antlr.xml.XMLLexer.Tokens as XmlTokens
import dev.sebastiano.ciacola.syntax.internal.antlrCharStream
import dev.sebastiano.ciacola.syntax.internal.driveAntlrLexer
import dev.sebastiano.ciacola.syntax.internal.emitDefaultGap

/**
 * Lossless ANTLR4 mode-switched DFA lexical tokenizer for XML source code.
 */
internal object XmlSyntaxLexer : SyntaxLexer {
    override val language: SyntaxLanguage = SyntaxLanguage.Xml

    override fun lex(source: String): List<SyntaxToken> {
        if (source.isEmpty()) return emptyList()

        val lexer = XMLLexer(antlrCharStream(source))
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
                    XmlTokens.DTD_SUBSET_COMMENT,
                    XmlTokens.DTD_SUBSET_UNTERM_COMMENT,
                    -> out += SyntaxToken(SyntaxTokenKind.Comment, start, end)
                    XmlTokens.CDATA,
                    XmlTokens.UNTERMINATED_CDATA,
                    -> out += SyntaxToken(SyntaxTokenKind.String, start, end)
                    XmlTokens.PI,
                    XmlTokens.UNTERMINATED_PI,
                    XmlTokens.DTD_OPEN,
                    XmlTokens.DTD_SUBSET_OPEN,
                    XmlTokens.DTD_CLOSE,
                    XmlTokens.DTD_STRING,
                    XmlTokens.DTD_UNTERM_STRING,
                    XmlTokens.DTD_CONTENT,
                    XmlTokens.DTD_FALLBACK,
                    XmlTokens.DTD_SUBSET_PI,
                    XmlTokens.DTD_SUBSET_UNTERM_PI,
                    XmlTokens.DTD_SUBSET_DECL_OPEN,
                    XmlTokens.DTD_SUBSET_CLOSE,
                    XmlTokens.DTD_SUBSET_TEXT,
                    XmlTokens.DTD_SUBSET_FALLBACK,
                    XmlTokens.DTD_DECL_CLOSE,
                    XmlTokens.DTD_DECL_STRING,
                    XmlTokens.DTD_DECL_UNTERM_STRING,
                    XmlTokens.DTD_DECL_CONTENT,
                    -> out += SyntaxToken(SyntaxTokenKind.Keyword, start, end)
                    XmlTokens.EntityRef,
                    XmlTokens.CharRef,
                    -> out += SyntaxToken(SyntaxTokenKind.Constant, start, end)
                    XmlTokens.SEA_WS,
                    XmlTokens.S,
                    XmlTokens.DTD_WS,
                    XmlTokens.DTD_SUBSET_WS,
                    XmlTokens.DTD_DECL_WS,
                    -> out += SyntaxToken(SyntaxTokenKind.Whitespace, start, end)
                    XmlTokens.TEXT -> out += SyntaxToken(SyntaxTokenKind.Plain, start, end)
                    else -> out += SyntaxToken(SyntaxTokenKind.Invalid, start, end)
                }
            },
        )

        return out
    }
}
