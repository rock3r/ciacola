package dev.sebastiano.ciacola.syntax.lexer

import dev.sebastiano.ciacola.syntax.SyntaxLanguage
import dev.sebastiano.ciacola.syntax.SyntaxLexer
import dev.sebastiano.ciacola.syntax.SyntaxToken
import dev.sebastiano.ciacola.syntax.SyntaxTokenKind
import dev.sebastiano.ciacola.syntax.antlr.html.HtmlLexer
import dev.sebastiano.ciacola.syntax.antlr.html.HtmlLexer.Tokens as HtmlTokens
import dev.sebastiano.ciacola.syntax.internal.driveAntlrLexer
import dev.sebastiano.ciacola.syntax.internal.emitDefaultGap
import org.antlr.v4.kotlinruntime.CharStreams

/**
 * Lossless ANTLR4 mode-switched lexical tokenizer for HTML5 with embedded `<script>` (JS/TS/JSON)
 * and `<style>` (CSS) delegation.
 */
object HtmlSyntaxLexer : SyntaxLexer {
    override val language: SyntaxLanguage = SyntaxLanguage.Html

    override fun lex(source: String): List<SyntaxToken> {
        if (source.isEmpty()) return emptyList()

        val lexer = HtmlLexer(CharStreams.fromString(source))
        val raw = ArrayList<RawHtmlToken>(source.length / 4 + 8)

        driveAntlrLexer(
            source = source,
            lexer = lexer,
            onGap = { start, end ->
                emitDefaultGap(source, start, end) { kind, s, e ->
                    raw += RawHtmlToken(
                        type = if (kind == SyntaxTokenKind.Whitespace) HtmlTokens.SEA_WS else HtmlTokens.INSIDE_ERROR,
                        start = s,
                        end = e,
                    )
                }
            },
            onToken = { type, start, end ->
                raw += RawHtmlToken(type = type, start = start, end = end)
            },
        )

        return classifyAndDelegateHtml(source, raw)
    }
}

private class RawHtmlToken(
    val type: Int,
    val start: Int,
    val end: Int,
)

private fun classifyAndDelegateHtml(source: String, raw: List<RawHtmlToken>): List<SyntaxToken> {
    val out = ArrayList<SyntaxToken>(raw.size)
    var expectingTagName = false
    var isClosingTag = false
    var currentOpenTag = ""
    var lastAttrName = ""
    var pendingAttrEquals = false
    var scriptTypeAttr = ""
    var scriptLangAttr = ""

    var i = 0
    while (i < raw.size) {
        val tok = raw[i]
        when (tok.type) {
            HtmlTokens.SCRIPT_TEXT,
            HtmlTokens.SCRIPT_LT,
            -> {
                val bodyStart = tok.start
                var bodyEnd = tok.end
                var j = i + 1
                while (j < raw.size && (raw[j].type == HtmlTokens.SCRIPT_TEXT || raw[j].type == HtmlTokens.SCRIPT_LT)) {
                    bodyEnd = raw[j].end
                    j++
                }
                emitEmbeddedBody(
                    source = source,
                    bodyStart = bodyStart,
                    bodyEnd = bodyEnd,
                    lexer = selectScriptLexer(scriptTypeAttr, scriptLangAttr),
                    out = out,
                )
                i = j
                continue
            }

            HtmlTokens.STYLE_TEXT,
            HtmlTokens.STYLE_LT,
            -> {
                val bodyStart = tok.start
                var bodyEnd = tok.end
                var j = i + 1
                while (j < raw.size && (raw[j].type == HtmlTokens.STYLE_TEXT || raw[j].type == HtmlTokens.STYLE_LT)) {
                    bodyEnd = raw[j].end
                    j++
                }
                emitEmbeddedBody(
                    source = source,
                    bodyStart = bodyStart,
                    bodyEnd = bodyEnd,
                    lexer = CssSyntaxLexer,
                    out = out,
                )
                i = j
                continue
            }

            HtmlTokens.OPEN,
            HtmlTokens.SCRIPT_END_OPEN,
            HtmlTokens.STYLE_END_OPEN,
            -> {
                out += SyntaxToken(SyntaxTokenKind.Punctuation, tok.start, tok.end)
                expectingTagName = true
                isClosingTag = false
                currentOpenTag = ""
                lastAttrName = ""
                pendingAttrEquals = false
                scriptTypeAttr = ""
                scriptLangAttr = ""
            }

            HtmlTokens.DOCTYPE_OPEN -> {
                val split = (tok.start + 2).coerceAtMost(tok.end)
                out += SyntaxToken(SyntaxTokenKind.Punctuation, tok.start, split)
                if (split < tok.end) {
                    out += SyntaxToken(SyntaxTokenKind.Tag, split, tok.end)
                }
                expectingTagName = false
                isClosingTag = false
                currentOpenTag = "doctype"
                lastAttrName = ""
                pendingAttrEquals = false
            }

            HtmlTokens.XML_DECL_OPEN -> {
                val split = (tok.start + 5).coerceAtMost(tok.end)
                out += SyntaxToken(SyntaxTokenKind.Keyword, tok.start, split)
                if (split < tok.end) {
                    out += SyntaxToken(SyntaxTokenKind.Whitespace, split, tok.end)
                }
                expectingTagName = false
                isClosingTag = false
                currentOpenTag = ""
                lastAttrName = ""
                pendingAttrEquals = false
            }

            HtmlTokens.SLASH -> {
                out += SyntaxToken(SyntaxTokenKind.Punctuation, tok.start, tok.end)
                if (expectingTagName) {
                    isClosingTag = true
                }
            }

            HtmlTokens.CLOSE,
            HtmlTokens.SLASH_CLOSE,
            HtmlTokens.SPECIAL_CLOSE,
            HtmlTokens.ATTR_CLOSE_TAG,
            -> {
                out += SyntaxToken(SyntaxTokenKind.Punctuation, tok.start, tok.end)
                expectingTagName = false
                isClosingTag = false
                pendingAttrEquals = false
                lastAttrName = ""
            }

            HtmlTokens.EQUALS -> {
                out += SyntaxToken(SyntaxTokenKind.Operator, tok.start, tok.end)
                pendingAttrEquals = true
            }

            HtmlTokens.TAG_NAME -> {
                val name = source.substring(tok.start, tok.end)
                if (expectingTagName) {
                    out += SyntaxToken(SyntaxTokenKind.Tag, tok.start, tok.end)
                    expectingTagName = false
                    currentOpenTag = if (isClosingTag) "" else name.lowercase()
                } else {
                    out += SyntaxToken(SyntaxTokenKind.Attribute, tok.start, tok.end)
                    lastAttrName = name.lowercase()
                    pendingAttrEquals = false
                }
            }

            HtmlTokens.ATTR_SPECIAL_NAME -> {
                out += SyntaxToken(SyntaxTokenKind.Attribute, tok.start, tok.end)
                lastAttrName = ""
                pendingAttrEquals = false
            }

            HtmlTokens.ATTR_STRING,
            HtmlTokens.ATTR_UNTERMINATED_STRING,
            HtmlTokens.ATTR_UNQUOTED,
            HtmlTokens.INSIDE_STRING,
            HtmlTokens.INSIDE_UNTERMINATED_STRING,
            -> {
                out += SyntaxToken(SyntaxTokenKind.String, tok.start, tok.end)
                if (pendingAttrEquals && currentOpenTag == "script") {
                    val value = unquoteAttrValue(source.substring(tok.start, tok.end))
                    when (lastAttrName) {
                        "type" -> scriptTypeAttr = value
                        "lang" -> scriptLangAttr = value
                    }
                }
                pendingAttrEquals = false
                lastAttrName = ""
            }

            HtmlTokens.HTML_COMMENT,
            HtmlTokens.UNTERMINATED_HTML_COMMENT,
            -> out += SyntaxToken(SyntaxTokenKind.Comment, tok.start, tok.end)

            HtmlTokens.CDATA,
            HtmlTokens.UNTERMINATED_CDATA,
            -> out += SyntaxToken(SyntaxTokenKind.String, tok.start, tok.end)

            HtmlTokens.PI,
            HtmlTokens.UNTERMINATED_PI,
            HtmlTokens.DTD,
            -> out += SyntaxToken(SyntaxTokenKind.Keyword, tok.start, tok.end)

            HtmlTokens.ENTITY_REF,
            HtmlTokens.CHAR_REF,
            -> out += SyntaxToken(SyntaxTokenKind.Constant, tok.start, tok.end)

            HtmlTokens.SEA_WS,
            HtmlTokens.TAG_WS,
            HtmlTokens.ATTR_WS,
            -> out += SyntaxToken(SyntaxTokenKind.Whitespace, tok.start, tok.end)

            HtmlTokens.TEXT,
            HtmlTokens.LONE_AMP,
            -> out += SyntaxToken(SyntaxTokenKind.Plain, tok.start, tok.end)

            HtmlTokens.INSIDE_ERROR,
            HtmlTokens.ATTR_ERROR,
            -> out += SyntaxToken(SyntaxTokenKind.Invalid, tok.start, tok.end)

            else -> out += SyntaxToken(SyntaxTokenKind.Invalid, tok.start, tok.end)
        }
        i++
    }
    return out
}

private fun emitEmbeddedBody(
    source: String,
    bodyStart: Int,
    bodyEnd: Int,
    lexer: SyntaxLexer?,
    out: MutableList<SyntaxToken>,
) {
    if (bodyStart >= bodyEnd) return
    if (lexer == null) {
        out += SyntaxToken(SyntaxTokenKind.Plain, bodyStart, bodyEnd)
        return
    }
    val subTokens = lexer.lex(source.substring(bodyStart, bodyEnd))
    for (tok in subTokens) {
        out += SyntaxToken(
            kind = tok.kind,
            startIndex = bodyStart + tok.startIndex,
            endIndex = bodyStart + tok.endIndex,
        )
    }
}

private fun selectScriptLexer(typeAttr: String, langAttr: String): SyntaxLexer? {
    val t = typeAttr.trim().lowercase()
    val l = langAttr.trim().lowercase()
    return when {
        t == "application/json" || t == "application/ld+json" || t == "importmap" || t.endsWith("+json") -> JsonSyntaxLexer
        t.contains("typescript") || l == "ts" || l == "tsx" -> TypeScriptSyntaxLexer
        t.isEmpty() ||
            t == "module" ||
            t.contains("javascript") ||
            t.contains("ecmascript") ||
            t == "text/jsx" ||
            l == "js" ||
            l == "jsx" -> JavaScriptSyntaxLexer
        else -> null
    }
}

private fun unquoteAttrValue(raw: String): String {
    if (raw.length >= 2 && ((raw.startsWith('"') && raw.endsWith('"')) || (raw.startsWith('\'') && raw.endsWith('\'')))) {
        return raw.substring(1, raw.length - 1)
    }
    if (raw.startsWith('"') || raw.startsWith('\'')) {
        return raw.substring(1)
    }
    return raw
}
