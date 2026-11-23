package dev.sebastiano.ciacola.syntax.lexer

import dev.sebastiano.ciacola.syntax.SyntaxLanguage
import dev.sebastiano.ciacola.syntax.SyntaxLexer
import dev.sebastiano.ciacola.syntax.SyntaxToken
import dev.sebastiano.ciacola.syntax.SyntaxTokenKind
import dev.sebastiano.ciacola.syntax.antlr.css.CssLexer
import dev.sebastiano.ciacola.syntax.antlr.css.CssLexer.Tokens as CssTokens
import dev.sebastiano.ciacola.syntax.internal.driveAntlrLexer
import dev.sebastiano.ciacola.syntax.internal.emitDefaultGap
import org.antlr.v4.kotlinruntime.CharStreams

/**
 * Lossless ANTLR4 DFA-based lexical tokenizer for CSS stylesheets.
 */
object CssSyntaxLexer : SyntaxLexer {
    override val language: SyntaxLanguage = SyntaxLanguage.Css

    override fun lex(source: String): List<SyntaxToken> {
        if (source.isEmpty()) return emptyList()

        val lexer = CssLexer(CharStreams.fromString(source))
        val raw = ArrayList<RawCssToken>(source.length / 3 + 8)

        driveAntlrLexer(
            source = source,
            lexer = lexer,
            onGap = { start, end ->
                emitDefaultGap(source, start, end) { kind, s, e ->
                    raw += RawCssToken(
                        type = if (kind == SyntaxTokenKind.Whitespace) CssTokens.WS else CssTokens.ERROR_CHAR,
                        start = s,
                        end = e,
                        isHidden = kind == SyntaxTokenKind.Whitespace,
                    )
                }
            },
            onToken = { type, start, end ->
                raw += RawCssToken(
                    type = type,
                    start = start,
                    end = end,
                    isHidden = isHiddenCssToken(type),
                )
            },
        )

        return classifyCssTokens(source, raw)
    }
}

private class RawCssToken(
    val type: Int,
    val start: Int,
    val end: Int,
    val isHidden: Boolean,
)

private fun isHiddenCssToken(type: Int): Boolean =
    type == CssTokens.WS ||
        type == CssTokens.URL_WS ||
        type == CssTokens.BLOCK_COMMENT ||
        type == CssTokens.UNTERMINATED_BLOCK_COMMENT

private fun classifyCssTokens(source: String, raw: List<RawCssToken>): List<SyntaxToken> {
    val visible = ArrayList<Int>(raw.size)
    for (idx in raw.indices) {
        if (!raw[idx].isHidden) visible += idx
    }

    val kinds = Array(raw.size) { idx -> defaultCssKind(raw[idx].type) }

    // Split visible tokens into segments terminated by '{', '}', or ';' at paren/bracket depth 0.
    // This linear scan cleanly distinguishes rule selectors ('{') from property declarations (';' or '}').
    var segStart = 0
    var braceDepth = 0
    var parenDepth = 0
    var bracketDepth = 0
    var i = 0
    while (i < visible.size) {
        val tok = raw[visible[i]]
        when (tok.type) {
            CssTokens.LPAREN, CssTokens.URL_LPAREN -> parenDepth++
            CssTokens.RPAREN, CssTokens.URL_RPAREN -> if (parenDepth > 0) parenDepth--
            CssTokens.LBRACK -> bracketDepth++
            CssTokens.RBRACK -> if (bracketDepth > 0) bracketDepth--
            CssTokens.LBRACE -> {
                if (parenDepth == 0 && bracketDepth == 0) {
                    classifyCssSegment(source, raw, visible, segStart, i, terminator = CssTokens.LBRACE, braceDepth = braceDepth, kinds = kinds)
                    braceDepth++
                    segStart = i + 1
                }
            }
            CssTokens.RBRACE -> {
                if (parenDepth == 0 && bracketDepth == 0) {
                    if (segStart < i) {
                        classifyCssSegment(source, raw, visible, segStart, i, terminator = CssTokens.RBRACE, braceDepth = braceDepth, kinds = kinds)
                    }
                    if (braceDepth > 0) braceDepth--
                    segStart = i + 1
                }
            }
            CssTokens.SEMICOLON -> {
                if (parenDepth == 0 && bracketDepth == 0) {
                    classifyCssSegment(source, raw, visible, segStart, i, terminator = CssTokens.SEMICOLON, braceDepth = braceDepth, kinds = kinds)
                    segStart = i + 1
                }
            }
        }
        i++
    }
    if (segStart < visible.size) {
        classifyCssSegment(source, raw, visible, segStart, visible.size, terminator = -1, braceDepth = braceDepth, kinds = kinds)
    }

    // Merge unary '-' directly preceding a NUMBER into a single Number token (e.g. -2.5em).
    val out = ArrayList<SyntaxToken>(raw.size)
    var r = 0
    var lastVisibleRawIdx = -1
    while (r < raw.size) {
        val tok = raw[r]
        if (tok.type == CssTokens.MINUS &&
            r + 1 < raw.size &&
            raw[r + 1].type == CssTokens.NUMBER &&
            tok.end == raw[r + 1].start &&
            isUnaryMinusContext(raw, lastVisibleRawIdx)
        ) {
            val num = raw[r + 1]
            out += SyntaxToken(SyntaxTokenKind.Number, tok.start, num.end)
            lastVisibleRawIdx = r + 1
            r += 2
            continue
        }
        out += SyntaxToken(kinds[r], tok.start, tok.end)
        if (!tok.isHidden) lastVisibleRawIdx = r
        r++
    }
    return out
}

private fun isUnaryMinusContext(raw: List<RawCssToken>, prevVisibleRawIdx: Int): Boolean {
    if (prevVisibleRawIdx < 0) return true
    return when (raw[prevVisibleRawIdx].type) {
        CssTokens.COLON,
        CssTokens.LPAREN,
        CssTokens.COMMA,
        CssTokens.SLASH,
        CssTokens.PLUS,
        CssTokens.MINUS,
        CssTokens.STAR,
        CssTokens.LBRACE,
        CssTokens.SEMICOLON,
        -> true
        else -> false
    }
}

private fun classifyCssSegment(
    source: String,
    raw: List<RawCssToken>,
    visible: List<Int>,
    segStart: Int,
    segEnd: Int,
    terminator: Int,
    braceDepth: Int,
    kinds: Array<SyntaxTokenKind>,
) {
    if (segStart >= segEnd) return

    val firstTok = raw[visible[segStart]]
    if (firstTok.type == CssTokens.AT_KEYWORD) {
        classifyAtRuleSegment(source, raw, visible, segStart, segEnd, kinds)
        return
    }

    val isDeclaration = when (terminator) {
        CssTokens.LBRACE -> false
        CssTokens.SEMICOLON, CssTokens.RBRACE -> hasTopLevelColon(raw, visible, segStart, segEnd)
        else -> braceDepth > 0 && hasTopLevelColon(raw, visible, segStart, segEnd)
    }

    if (isDeclaration) {
        classifyDeclarationSegment(source, raw, visible, segStart, segEnd, kinds)
    } else {
        classifySelectorSegment(source, raw, visible, segStart, segEnd, kinds)
    }
}

private fun hasTopLevelColon(
    raw: List<RawCssToken>,
    visible: List<Int>,
    segStart: Int,
    segEnd: Int,
): Boolean {
    var paren = 0
    var bracket = 0
    for (idx in segStart until segEnd) {
        when (raw[visible[idx]].type) {
            CssTokens.LPAREN, CssTokens.URL_LPAREN -> paren++
            CssTokens.RPAREN, CssTokens.URL_RPAREN -> if (paren > 0) paren--
            CssTokens.LBRACK -> bracket++
            CssTokens.RBRACK -> if (bracket > 0) bracket--
            CssTokens.COLON -> if (paren == 0 && bracket == 0) return true
        }
    }
    return false
}

private fun classifyAtRuleSegment(
    source: String,
    raw: List<RawCssToken>,
    visible: List<Int>,
    segStart: Int,
    segEnd: Int,
    kinds: Array<SyntaxTokenKind>,
) {
    kinds[visible[segStart]] = SyntaxTokenKind.Keyword
    var parenDepth = 0
    for (idx in (segStart + 1) until segEnd) {
        val rawIdx = visible[idx]
        val tok = raw[rawIdx]
        val prev = raw[visible[idx - 1]]
        val next = if (idx + 1 < segEnd) raw[visible[idx + 1]] else null
        when (tok.type) {
            CssTokens.LPAREN, CssTokens.URL_LPAREN -> parenDepth++
            CssTokens.RPAREN, CssTokens.URL_RPAREN -> if (parenDepth > 0) parenDepth--
            CssTokens.HASH_TOKEN -> {
                kinds[rawIdx] = SyntaxTokenKind.Attribute
            }
            CssTokens.IDENT -> {
                val identText = source.substring(tok.start, tok.end)
                kinds[rawIdx] = when {
                    identText.startsWith("--") -> SyntaxTokenKind.Property
                    prev.type == CssTokens.DOT -> SyntaxTokenKind.Attribute
                    prev.type == CssTokens.COLON || prev.type == CssTokens.DOUBLE_COLON -> SyntaxTokenKind.Annotation
                    next?.type == CssTokens.LPAREN -> SyntaxTokenKind.Function
                    parenDepth > 0 && next?.type == CssTokens.COLON -> SyntaxTokenKind.Property
                    else -> SyntaxTokenKind.Plain
                }
            }
            CssTokens.SLASH,
            CssTokens.PLUS,
            CssTokens.MINUS,
            CssTokens.STAR,
            CssTokens.LT,
            CssTokens.GT,
            CssTokens.LTE,
            CssTokens.GTE,
            CssTokens.PIPE,
            CssTokens.COLUMN,
            -> {
                kinds[rawIdx] = SyntaxTokenKind.Operator
            }
        }
    }
}

private fun classifyDeclarationSegment(
    source: String,
    raw: List<RawCssToken>,
    visible: List<Int>,
    segStart: Int,
    segEnd: Int,
    kinds: Array<SyntaxTokenKind>,
) {
    var colonIdx = -1
    var paren = 0
    var bracket = 0
    for (idx in segStart until segEnd) {
        when (raw[visible[idx]].type) {
            CssTokens.LPAREN, CssTokens.URL_LPAREN -> paren++
            CssTokens.RPAREN, CssTokens.URL_RPAREN -> if (paren > 0) paren--
            CssTokens.LBRACK -> bracket++
            CssTokens.RBRACK -> if (bracket > 0) bracket--
            CssTokens.COLON -> if (paren == 0 && bracket == 0) {
                colonIdx = idx
                break
            }
        }
    }
    if (colonIdx < 0) return

    for (idx in segStart until colonIdx) {
        val rawIdx = visible[idx]
        if (raw[rawIdx].type == CssTokens.IDENT) {
            kinds[rawIdx] = SyntaxTokenKind.Property
        }
    }

    for (idx in (colonIdx + 1) until segEnd) {
        val rawIdx = visible[idx]
        val tok = raw[rawIdx]
        val next = if (idx + 1 < segEnd) raw[visible[idx + 1]] else null
        when (tok.type) {
            CssTokens.IDENT -> {
                kinds[rawIdx] = if (next?.type == CssTokens.LPAREN) {
                    SyntaxTokenKind.Function
                } else {
                    SyntaxTokenKind.Plain
                }
            }
            CssTokens.HASH_TOKEN -> {
                val hexDigits = source.substring(tok.start + 1, tok.end)
                kinds[rawIdx] = if (isHexColorDigits(hexDigits)) SyntaxTokenKind.Number else SyntaxTokenKind.Plain
            }
            CssTokens.SLASH,
            CssTokens.PLUS,
            CssTokens.MINUS,
            CssTokens.STAR,
            CssTokens.LT,
            CssTokens.GT,
            CssTokens.LTE,
            CssTokens.GTE,
            CssTokens.PIPE,
            CssTokens.COLUMN,
            -> {
                kinds[rawIdx] = SyntaxTokenKind.Operator
            }
        }
    }
}

private fun classifySelectorSegment(
    source: String,
    raw: List<RawCssToken>,
    visible: List<Int>,
    segStart: Int,
    segEnd: Int,
    kinds: Array<SyntaxTokenKind>,
) {
    var bracketDepth = 0
    val parenKindStack = ArrayList<Int>() // 0 = normal, 1 = selector-list pseudo (:is/:where/:has/:not), 2 = nth pseudo
    var idx = segStart
    while (idx < segEnd) {
        val rawIdx = visible[idx]
        val tok = raw[rawIdx]
        val prev = if (idx > segStart) raw[visible[idx - 1]] else null
        val next = if (idx + 1 < segEnd) raw[visible[idx + 1]] else null
        when (tok.type) {
            CssTokens.LBRACK -> bracketDepth++
            CssTokens.RBRACK -> if (bracketDepth > 0) bracketDepth--
            CssTokens.LPAREN -> {
                val parenMode = if (prev?.type == CssTokens.IDENT && kinds[visible[idx - 1]] == SyntaxTokenKind.Annotation) {
                    val fnName = source.substring(prev.start, prev.end).lowercase()
                    when {
                        fnName == "is" || fnName == "where" || fnName == "has" || fnName == "not" -> 1
                        fnName.startsWith("nth-") -> 2
                        else -> 0
                    }
                } else {
                    0
                }
                parenKindStack.add(parenMode)
            }
            CssTokens.RPAREN -> if (parenKindStack.isNotEmpty()) parenKindStack.removeAt(parenKindStack.size - 1)
            CssTokens.HASH_TOKEN -> {
                kinds[rawIdx] = SyntaxTokenKind.Attribute
            }
            CssTokens.IDENT -> {
                val activeParenMode = parenKindStack.lastOrNull() ?: 0
                kinds[rawIdx] = when {
                    bracketDepth > 0 && (prev?.type == CssTokens.LBRACK || prev?.type == CssTokens.IDENT) -> {
                        SyntaxTokenKind.Attribute
                    }
                    bracketDepth > 0 -> {
                        val flag = source.substring(tok.start, tok.end).lowercase()
                        if ((flag == "i" || flag == "s") && next?.type == CssTokens.RBRACK) {
                            SyntaxTokenKind.Keyword
                        } else {
                            SyntaxTokenKind.Plain
                        }
                    }
                    prev?.type == CssTokens.DOT -> SyntaxTokenKind.Attribute
                    prev?.type == CssTokens.COLON || prev?.type == CssTokens.DOUBLE_COLON -> SyntaxTokenKind.Annotation
                    activeParenMode == 1 -> SyntaxTokenKind.Tag
                    activeParenMode == 2 -> {
                        val word = source.substring(tok.start, tok.end).lowercase()
                        if (word == "of" || word == "even" || word == "odd") SyntaxTokenKind.Keyword else SyntaxTokenKind.Plain
                    }
                    parenKindStack.isNotEmpty() -> SyntaxTokenKind.Plain
                    else -> SyntaxTokenKind.Tag
                }
            }
            CssTokens.AMP -> {
                kinds[rawIdx] = SyntaxTokenKind.Tag
            }
            CssTokens.LT,
            CssTokens.GT,
            CssTokens.LTE,
            CssTokens.GTE,
            CssTokens.PIPE,
            CssTokens.COLUMN,
            CssTokens.PLUS,
            CssTokens.TILDE,
            CssTokens.STAR,
            CssTokens.EQ,
            CssTokens.INCLUDES,
            CssTokens.DASHMATCH,
            CssTokens.PREFIXMATCH,
            CssTokens.SUFFIXMATCH,
            CssTokens.SUBSTRINGMATCH,
            -> kinds[rawIdx] = SyntaxTokenKind.Operator
            CssTokens.MINUS, CssTokens.SLASH -> {
                if (parenKindStack.isNotEmpty()) kinds[rawIdx] = SyntaxTokenKind.Operator
            }
        }
        idx++
    }
}

private fun isHexColorDigits(digits: String): Boolean {
    if (digits.length !in intArrayOf(3, 4, 6, 8)) return false
    return digits.all { ch -> ch in '0'..'9' || ch in 'a'..'f' || ch in 'A'..'F' }
}

private fun defaultCssKind(type: Int): SyntaxTokenKind = when (type) {
    CssTokens.WS,
    CssTokens.URL_WS,
    -> SyntaxTokenKind.Whitespace
    CssTokens.BLOCK_COMMENT,
    CssTokens.UNTERMINATED_BLOCK_COMMENT,
    -> SyntaxTokenKind.Comment
    CssTokens.STRING,
    CssTokens.UNTERMINATED_STRING,
    CssTokens.URL_STRING,
    CssTokens.URL_UNTERMINATED_STRING,
    CssTokens.URL_UNQUOTED,
    -> SyntaxTokenKind.String
    CssTokens.IMPORTANT,
    CssTokens.AT_KEYWORD,
    -> SyntaxTokenKind.Keyword
    CssTokens.NUMBER,
    CssTokens.UNICODE_RANGE,
    -> SyntaxTokenKind.Number
    CssTokens.DOUBLE_COLON,
    CssTokens.COLON,
    CssTokens.SEMICOLON,
    CssTokens.COMMA,
    CssTokens.LBRACE,
    CssTokens.RBRACE,
    CssTokens.LPAREN,
    CssTokens.RPAREN,
    CssTokens.URL_LPAREN,
    CssTokens.URL_RPAREN,
    CssTokens.LBRACK,
    CssTokens.RBRACK,
    CssTokens.DOT,
    CssTokens.AMP,
    -> SyntaxTokenKind.Punctuation
    CssTokens.INCLUDES,
    CssTokens.DASHMATCH,
    CssTokens.PREFIXMATCH,
    CssTokens.SUFFIXMATCH,
    CssTokens.SUBSTRINGMATCH,
    CssTokens.LTE,
    CssTokens.GTE,
    CssTokens.LT,
    CssTokens.GT,
    CssTokens.COLUMN,
    CssTokens.PIPE,
    CssTokens.PLUS,
    CssTokens.MINUS,
    CssTokens.TILDE,
    CssTokens.STAR,
    CssTokens.SLASH,
    CssTokens.EQ,
    -> SyntaxTokenKind.Operator
    CssTokens.BANG,
    CssTokens.HASH_SINGLE,
    CssTokens.AT_SINGLE,
    CssTokens.ERROR_CHAR,
    -> SyntaxTokenKind.Invalid
    else -> SyntaxTokenKind.Plain
}
