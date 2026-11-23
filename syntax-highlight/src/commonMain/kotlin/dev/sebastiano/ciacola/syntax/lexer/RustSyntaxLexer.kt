package dev.sebastiano.ciacola.syntax.lexer

import dev.sebastiano.ciacola.syntax.SyntaxLanguage
import dev.sebastiano.ciacola.syntax.SyntaxLexer
import dev.sebastiano.ciacola.syntax.SyntaxToken
import dev.sebastiano.ciacola.syntax.SyntaxTokenKind
import dev.sebastiano.ciacola.syntax.antlr.rust.RustLexer
import dev.sebastiano.ciacola.syntax.antlr.rust.RustLexer.Tokens as RustTokens
import dev.sebastiano.ciacola.syntax.internal.driveAntlrLexer
import dev.sebastiano.ciacola.syntax.internal.emitDefaultGap
import dev.sebastiano.ciacola.syntax.internal.looksLikeTypeName
import org.antlr.v4.kotlinruntime.CharStreams

/**
 * Lossless ANTLR4 DFA-based lexical tokenizer for Rust source code.
 */
object RustSyntaxLexer : SyntaxLexer {
    override val language: SyntaxLanguage = SyntaxLanguage.Rust

    override fun lex(source: String): List<SyntaxToken> {
        if (source.isEmpty()) return emptyList()

        val lexer = RustLexer(CharStreams.fromString(source))
        val raw = ArrayList<RawRustToken>(source.length / 3 + 8)

        driveAntlrLexer(
            source = source,
            lexer = lexer,
            onGap = { start, end ->
                emitDefaultGap(source, start, end) { kind, s, e ->
                    raw += RawRustToken(
                        type = if (kind == SyntaxTokenKind.Whitespace) RustTokens.WS else RustTokens.ERROR_CHAR,
                        start = s,
                        end = e,
                        kind = kind,
                        isHidden = kind == SyntaxTokenKind.Whitespace,
                    )
                }
            },
            onToken = { type, start, end -> emitRustRawToken(type, start, end, raw) },
        )

        val classified = classifyRustRawTokens(source, raw)
        return classified.map { SyntaxToken(kind = it.kind, startIndex = it.start, endIndex = it.end) }
    }
}

private class RawRustToken(
    val type: Int,
    val start: Int,
    var end: Int,
    var kind: SyntaxTokenKind,
    val isHidden: Boolean,
)

private fun emitRustRawToken(
    type: Int,
    start: Int,
    end: Int,
    out: MutableList<RawRustToken>,
) {
    when (type) {
        RustTokens.WS -> out += RawRustToken(type, start, end, SyntaxTokenKind.Whitespace, isHidden = true)
        RustTokens.LINE_COMMENT -> out += RawRustToken(type, start, end, SyntaxTokenKind.Comment, isHidden = true)
        RustTokens.BLOCK_COMMENT -> {
            val prev = out.lastOrNull()
            if (prev != null && prev.type == RustTokens.BLOCK_COMMENT && prev.end == start) {
                prev.end = end
            } else {
                out += RawRustToken(type, start, end, SyntaxTokenKind.Comment, isHidden = true)
            }
        }
        RustTokens.RAW_STRING,
        RustTokens.STRING,
        RustTokens.UNTERMINATED_STRING,
        RustTokens.CHAR_LITERAL,
        -> out += RawRustToken(type, start, end, SyntaxTokenKind.String, isHidden = false)

        RustTokens.LIFETIME -> out += RawRustToken(type, start, end, SyntaxTokenKind.Type, isHidden = false)
        RustTokens.RAW_IDENT -> out += RawRustToken(type, start, end, SyntaxTokenKind.Plain, isHidden = false)

        RustTokens.HEX_LITERAL,
        RustTokens.OCT_LITERAL,
        RustTokens.BIN_LITERAL,
        RustTokens.FLOAT_LITERAL,
        RustTokens.INT_LITERAL,
        -> out += RawRustToken(type, start, end, SyntaxTokenKind.Number, isHidden = false)

        RustTokens.ERROR_CHAR -> out += RawRustToken(type, start, end, SyntaxTokenKind.Invalid, isHidden = false)

        else -> {
            val kind = when {
                isRustOperator(type) -> SyntaxTokenKind.Operator
                isRustPunctuation(type) -> SyntaxTokenKind.Punctuation
                else -> SyntaxTokenKind.Plain
            }
            out += RawRustToken(type, start, end, kind, isHidden = false)
        }
    }
}

private fun classifyRustRawTokens(code: String, raw: List<RawRustToken>): List<RawRustToken> {
    // Single-pass O(N) normalization:
    // 1. Merge `ident` + `!` (when adjacent and not `!=`) into a single Function macro token (`println!`, `vec!`, `macro_rules!`).
    // 2. Split chained tuple field access like `tuple.0.1` where `.` is followed by a simple decimal `a.b` float token.
    val normalized = ArrayList<RawRustToken>(raw.size)
    var mergeIdx = 0
    while (mergeIdx < raw.size) {
        val cur = raw[mergeIdx]
        val nxt = if (mergeIdx + 1 < raw.size) raw[mergeIdx + 1] else null
        if (nxt != null && cur.type == RustTokens.IDENT && nxt.type == RustTokens.BANG && cur.end == nxt.start) {
            normalized += RawRustToken(MACRO_SYNTHETIC_TYPE, cur.start, nxt.end, SyntaxTokenKind.Function, isHidden = false)
            mergeIdx += 2
            continue
        }
        normalized += cur
        if (nxt != null && cur.type == RustTokens.DOT && nxt.type == RustTokens.FLOAT_LITERAL && cur.end == nxt.start) {
            val floatText = code.substring(nxt.start, nxt.end)
            val dotOffset = floatText.indexOf('.')
            if (
                dotOffset > 0 &&
                dotOffset < floatText.length - 1 &&
                floatText.all { it.isDigit() || it == '_' || it == '.' }
            ) {
                val splitAt = nxt.start + dotOffset
                normalized += RawRustToken(RustTokens.INT_LITERAL, nxt.start, splitAt, SyntaxTokenKind.Number, isHidden = false)
                normalized += RawRustToken(RustTokens.DOT, splitAt, splitAt + 1, SyntaxTokenKind.Punctuation, isHidden = false)
                normalized += RawRustToken(RustTokens.INT_LITERAL, splitAt + 1, nxt.end, SyntaxTokenKind.Number, isHidden = false)
                mergeIdx += 2
                continue
            }
        }
        mergeIdx++
    }

    val visible = ArrayList<Int>(normalized.size)
    for (idx in normalized.indices) {
        if (!normalized[idx].isHidden) visible += idx
    }

    // Track Rust attributes: `#[...]` and `#![...]`
    var attrBracketDepth = 0
    var angleDepth = 0
    var i = 0
    while (i < visible.size) {
        val tok = normalized[visible[i]]
        val prev = if (i > 0) normalized[visible[i - 1]] else null
        val next = if (i + 1 < visible.size) normalized[visible[i + 1]] else null

        if (attrBracketDepth > 0) {
            when (tok.type) {
                RustTokens.LBRACK -> {
                    attrBracketDepth++
                    tok.kind = SyntaxTokenKind.Annotation
                }
                RustTokens.RBRACK -> {
                    attrBracketDepth--
                    tok.kind = SyntaxTokenKind.Annotation
                }
                RustTokens.RAW_STRING,
                RustTokens.STRING,
                RustTokens.UNTERMINATED_STRING,
                RustTokens.CHAR_LITERAL,
                RustTokens.HEX_LITERAL,
                RustTokens.OCT_LITERAL,
                RustTokens.BIN_LITERAL,
                RustTokens.FLOAT_LITERAL,
                RustTokens.INT_LITERAL,
                -> Unit // keep literal kinds inside attributes (e.g. #[cfg(target_pointer_width = "64")])
                else -> tok.kind = SyntaxTokenKind.Annotation
            }
            i++
            continue
        }

        if (tok.type == RustTokens.HASH) {
            if (next != null && next.type == RustTokens.LBRACK) {
                tok.kind = SyntaxTokenKind.Annotation
                next.kind = SyntaxTokenKind.Annotation
                attrBracketDepth = 1
                i += 2
                continue
            }
            val next2 = if (i + 2 < visible.size) normalized[visible[i + 2]] else null
            if (next != null && next.type == RustTokens.BANG && next2 != null && next2.type == RustTokens.LBRACK) {
                tok.kind = SyntaxTokenKind.Annotation
                next.kind = SyntaxTokenKind.Annotation
                next2.kind = SyntaxTokenKind.Annotation
                attrBracketDepth = 1
                i += 3
                continue
            }
        }

        when (tok.type) {
            RustTokens.LT -> if (hasMatchingGenericClose(normalized, visible, i)) angleDepth++
            RustTokens.GT -> if (angleDepth > 0) angleDepth--
            RustTokens.SHR -> angleDepth = (angleDepth - 2).coerceAtLeast(0)
            RustTokens.SEMI,
            RustTokens.LBRACE,
            RustTokens.RBRACE,
            -> angleDepth = 0
        }

        if ((tok.type == RustTokens.IDENT || tok.type == RustTokens.RAW_IDENT) && tok.kind == SyntaxTokenKind.Plain) {
            val text = code.substring(tok.start, tok.end)
            tok.kind = classifyRustIdentifier(
                code = code,
                text = text,
                isRawIdent = tok.type == RustTokens.RAW_IDENT,
                prev = prev,
                next = next,
                angleDepth = angleDepth,
            )
        }

        i++
    }
    return normalized
}

private fun hasMatchingGenericClose(
    raw: List<RawRustToken>,
    visible: List<Int>,
    openVisibleIdx: Int,
): Boolean {
    var depth = 1
    var parenDepth = 0
    var bracketDepth = 0
    val limit = (openVisibleIdx + 1 + 128).coerceAtMost(visible.size)
    for (j in (openVisibleIdx + 1) until limit) {
        when (raw[visible[j]].type) {
            RustTokens.LPAREN -> parenDepth++
            RustTokens.RPAREN -> if (parenDepth > 0) parenDepth-- else return false
            RustTokens.LBRACK -> bracketDepth++
            RustTokens.RBRACK -> if (bracketDepth > 0) bracketDepth-- else return false
            RustTokens.LBRACE,
            RustTokens.RBRACE,
            -> return false
            RustTokens.SEMI -> if (bracketDepth == 0) return false
            RustTokens.ANDAND,
            RustTokens.OROR,
            RustTokens.EQEQ,
            RustTokens.NE,
            RustTokens.LE,
            RustTokens.GE,
            -> if (parenDepth == 0 && bracketDepth == 0) return false
            RustTokens.LT -> depth++
            RustTokens.GT -> {
                depth--
                if (depth == 0) return true
            }
            RustTokens.SHR -> {
                depth -= 2
                if (depth <= 0) return true
            }
        }
    }
    return false
}

private fun classifyRustIdentifier(
    code: String,
    text: String,
    isRawIdent: Boolean,
    prev: RawRustToken?,
    next: RawRustToken?,
    angleDepth: Int,
): SyntaxTokenKind {
    if (!isRawIdent) {
        if (text in RUST_CONSTANTS) return SyntaxTokenKind.Constant
        if (text in RUST_KEYWORDS) return SyntaxTokenKind.Keyword
        if (text in RUST_PRIMITIVE_TYPES) return SyntaxTokenKind.Type
    }

    val rawName = if (isRawIdent) text.removePrefix("r#") else text
    val prevText = if (prev != null && prev.type == RustTokens.IDENT) code.substring(prev.start, prev.end) else null
    if (prev != null && prev.type == MACRO_SYNTHETIC_TYPE && code.substring(prev.start, prev.end) == "macro_rules!") {
        return SyntaxTokenKind.Function
    }

    if (prevText == "fn") return SyntaxTokenKind.Function
    if (prevText in RUST_TYPE_INTRODUCERS) return SyntaxTokenKind.Type

    if (next?.type == RustTokens.LPAREN) {
        return if (looksLikeTypeName(rawName)) SyntaxTokenKind.Type else SyntaxTokenKind.Function
    }

    if (prev?.type == RustTokens.DOT) {
        return SyntaxTokenKind.Property
    }

    if (looksLikeTypeName(rawName)) return SyntaxTokenKind.Type

    if (
        rawName.length == 1 &&
        rawName[0].isUpperCase() &&
        (
            angleDepth > 0 ||
                prev?.type == RustTokens.COLON ||
                prev?.type == RustTokens.RARROW ||
                (prev?.type == RustTokens.LBRACK && next?.type == RustTokens.SEMI) ||
                prevText == "impl" ||
                prevText == "for" ||
                next?.type == RustTokens.COLON ||
                next?.type == RustTokens.COLONCOLON
            )
    ) {
        return SyntaxTokenKind.Type
    }

    if (
        rawName.length == 1 &&
        rawName[0].isUpperCase() &&
        prev?.type == RustTokens.SEMI &&
        next?.type == RustTokens.RBRACK
    ) {
        return SyntaxTokenKind.Constant
    }

    if (rawName.length > 1 && rawName[0].isUpperCase() && rawName.all { it.isUpperCase() || it.isDigit() || it == '_' }) {
        return SyntaxTokenKind.Constant
    }

    return SyntaxTokenKind.Plain
}

private const val MACRO_SYNTHETIC_TYPE = -100

private val RUST_KEYWORDS = setOf(
    "as", "async", "await", "break", "const", "continue", "crate", "dyn",
    "else", "enum", "extern", "fn", "for", "if", "impl", "in", "let",
    "loop", "match", "mod", "move", "mut", "pub", "ref", "return",
    "self", "static", "struct", "super", "trait", "type", "unsafe", "use",
    "where", "while", "macro_rules", "try", "abstract", "become", "box",
    "do", "final", "macro", "override", "priv", "typeof", "unsized",
    "virtual", "yield", "gen",
)

private val RUST_CONSTANTS = setOf("true", "false")

private val RUST_PRIMITIVE_TYPES = setOf(
    "i8", "i16", "i32", "i64", "i128", "isize",
    "u8", "u16", "u32", "u64", "u128", "usize",
    "f32", "f64", "bool", "char", "str", "Self",
)

private val RUST_TYPE_INTRODUCERS = setOf("struct", "enum", "trait", "type", "union")

private fun isRustOperator(type: Int): Boolean = when (type) {
    RustTokens.DOTDOTEQ,
    RustTokens.DOTDOT,
    RustTokens.COLONCOLON,
    RustTokens.RARROW,
    RustTokens.FATARROW,
    RustTokens.SHLEQ,
    RustTokens.SHREQ,
    RustTokens.SHL,
    RustTokens.SHR,
    RustTokens.ANDAND,
    RustTokens.OROR,
    RustTokens.EQEQ,
    RustTokens.NE,
    RustTokens.LE,
    RustTokens.GE,
    RustTokens.PLUSEQ,
    RustTokens.MINUSEQ,
    RustTokens.STAREQ,
    RustTokens.SLASHEQ,
    RustTokens.PERCENTEQ,
    RustTokens.AMPEQ,
    RustTokens.PIPEEQ,
    RustTokens.CARETEQ,
    RustTokens.QUESTION,
    RustTokens.AT,
    RustTokens.PLUS,
    RustTokens.MINUS,
    RustTokens.STAR,
    RustTokens.SLASH,
    RustTokens.PERCENT,
    RustTokens.CARET,
    RustTokens.AMP,
    RustTokens.PIPE,
    RustTokens.BANG,
    RustTokens.EQ,
    RustTokens.LT,
    RustTokens.GT,
    -> true
    else -> false
}

private fun isRustPunctuation(type: Int): Boolean = when (type) {
    RustTokens.LPAREN,
    RustTokens.RPAREN,
    RustTokens.LBRACE,
    RustTokens.RBRACE,
    RustTokens.LBRACK,
    RustTokens.RBRACK,
    RustTokens.SEMI,
    RustTokens.COMMA,
    RustTokens.DOT,
    RustTokens.COLON,
    RustTokens.HASH,
    RustTokens.DOLLAR,
    RustTokens.TILDE,
    -> true
    else -> false
}
