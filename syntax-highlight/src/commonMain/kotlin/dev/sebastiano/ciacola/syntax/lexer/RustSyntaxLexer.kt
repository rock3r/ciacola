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

        classifyRustRawTokens(source, raw)
        return raw.map { SyntaxToken(kind = it.kind, startIndex = it.start, endIndex = it.end) }
    }
}

private class RawRustToken(
    var type: Int,
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

private fun classifyRustRawTokens(code: String, raw: MutableList<RawRustToken>) {
    // Merge `ident` + `!` (when adjacent and not `!=`) into a single Function macro token (`println!`, `vec!`, `macro_rules!`).
    // Also split chained tuple field access like `tuple.0.1` where `.` is followed by a simple decimal `a.b` float token.
    var mergeIdx = 0
    while (mergeIdx < raw.size) {
        val cur = raw[mergeIdx]
        if (mergeIdx + 1 < raw.size) {
            val nxt = raw[mergeIdx + 1]
            if (cur.type == RustTokens.IDENT && nxt.type == RustTokens.BANG && cur.end == nxt.start) {
                cur.end = nxt.end
                cur.kind = SyntaxTokenKind.Function
                cur.type = MACRO_SYNTHETIC_TYPE
                raw.removeAt(mergeIdx + 1)
                mergeIdx++
                continue
            }
            if (cur.type == RustTokens.DOT && nxt.type == RustTokens.FLOAT_LITERAL && cur.end == nxt.start) {
                val floatText = code.substring(nxt.start, nxt.end)
                val dotOffset = floatText.indexOf('.')
                if (
                    dotOffset > 0 &&
                    dotOffset < floatText.length - 1 &&
                    floatText.all { it.isDigit() || it == '_' || it == '.' }
                ) {
                    val splitAt = nxt.start + dotOffset
                    raw[mergeIdx + 1] = RawRustToken(RustTokens.INT_LITERAL, nxt.start, splitAt, SyntaxTokenKind.Number, isHidden = false)
                    raw.add(mergeIdx + 2, RawRustToken(RustTokens.DOT, splitAt, splitAt + 1, SyntaxTokenKind.Punctuation, isHidden = false))
                    raw.add(mergeIdx + 3, RawRustToken(RustTokens.INT_LITERAL, splitAt + 1, nxt.end, SyntaxTokenKind.Number, isHidden = false))
                }
            }
        }
        mergeIdx++
    }

    val visible = ArrayList<Int>(raw.size)
    for (idx in raw.indices) {
        if (!raw[idx].isHidden) visible += idx
    }

    // Track Rust attributes: `#[...]` and `#![...]`
    var attrBracketDepth = 0
    var angleDepth = 0
    var i = 0
    while (i < visible.size) {
        val tok = raw[visible[i]]
        val prev = if (i > 0) raw[visible[i - 1]] else null
        val next = if (i + 1 < visible.size) raw[visible[i + 1]] else null

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
            val next2 = if (i + 2 < visible.size) raw[visible[i + 2]] else null
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
            RustTokens.LT -> angleDepth++
            RustTokens.GT -> if (angleDepth > 0) angleDepth--
            RustTokens.SHR -> angleDepth = (angleDepth - 2).coerceAtLeast(0)
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
