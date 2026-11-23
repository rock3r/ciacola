package dev.sebastiano.ciacola.syntax.lexer

import dev.sebastiano.ciacola.syntax.SyntaxLanguage
import dev.sebastiano.ciacola.syntax.SyntaxLexer
import dev.sebastiano.ciacola.syntax.SyntaxToken
import dev.sebastiano.ciacola.syntax.SyntaxTokenKind
import dev.sebastiano.ciacola.syntax.antlr.go.GoLexer
import dev.sebastiano.ciacola.syntax.antlr.go.GoLexer.Tokens as GoTokens
import dev.sebastiano.ciacola.syntax.internal.driveAntlrLexer
import dev.sebastiano.ciacola.syntax.internal.emitDefaultGap
import dev.sebastiano.ciacola.syntax.internal.looksLikeTypeName
import org.antlr.v4.kotlinruntime.CharStreams

/**
 * Lossless ANTLR4 DFA-based lexical tokenizer for Go source code.
 */
object GoSyntaxLexer : SyntaxLexer {
    override val language: SyntaxLanguage = SyntaxLanguage.Go

    override fun lex(source: String): List<SyntaxToken> {
        if (source.isEmpty()) return emptyList()

        val lexer = GoLexer(CharStreams.fromString(source))
        val raw = ArrayList<RawGoToken>(source.length / 3 + 8)

        driveAntlrLexer(
            source = source,
            lexer = lexer,
            onGap = { start, end ->
                emitDefaultGap(source, start, end) { kind, s, e ->
                    raw += RawGoToken(
                        type = if (kind == SyntaxTokenKind.Whitespace) GoTokens.WS else GoTokens.ERROR_CHAR,
                        start = s,
                        end = e,
                        kind = kind,
                        isHidden = kind == SyntaxTokenKind.Whitespace,
                        hasNewline = (s until e).any { source[it] == '\n' || source[it] == '\r' },
                    )
                }
            },
            onToken = { type, start, end -> emitGoRawToken(source, type, start, end, raw) },
        )

        classifyGoRawTokens(source, raw)
        return raw.map { SyntaxToken(kind = it.kind, startIndex = it.start, endIndex = it.end) }
    }
}

private class RawGoToken(
    val type: Int,
    val start: Int,
    val end: Int,
    var kind: SyntaxTokenKind,
    val isHidden: Boolean,
    val hasNewline: Boolean = false,
)

private fun emitGoRawToken(
    code: String,
    type: Int,
    start: Int,
    end: Int,
    out: MutableList<RawGoToken>,
) {
    when (type) {
        GoTokens.WS -> {
            val hasNl = (start until end).any { code[it] == '\n' || code[it] == '\r' }
            out += RawGoToken(type, start, end, SyntaxTokenKind.Whitespace, isHidden = true, hasNewline = hasNl)
        }
        GoTokens.LINE_COMMENT -> out += RawGoToken(type, start, end, SyntaxTokenKind.Comment, isHidden = true)
        GoTokens.BLOCK_COMMENT,
        GoTokens.UNTERMINATED_BLOCK_COMMENT,
        -> {
            val hasNl = (start until end).any { code[it] == '\n' || code[it] == '\r' }
            out += RawGoToken(type, start, end, SyntaxTokenKind.Comment, isHidden = true, hasNewline = hasNl)
        }

        GoTokens.RAW_STRING,
        GoTokens.UNTERMINATED_RAW_STRING,
        GoTokens.STRING,
        GoTokens.UNTERMINATED_STRING,
        GoTokens.RUNE,
        GoTokens.UNTERMINATED_RUNE,
        -> out += RawGoToken(type, start, end, SyntaxTokenKind.String, isHidden = false)

        GoTokens.HEX_FLOAT,
        GoTokens.HEX_INT,
        GoTokens.BIN_INT,
        GoTokens.OCT_INT,
        GoTokens.FLOAT,
        GoTokens.DEC_INT,
        -> out += RawGoToken(type, start, end, SyntaxTokenKind.Number, isHidden = false)

        GoTokens.ERROR_CHAR -> out += RawGoToken(type, start, end, SyntaxTokenKind.Invalid, isHidden = false)

        else -> {
            val kind = when {
                isGoOperator(type) -> SyntaxTokenKind.Operator
                isGoPunctuation(type) -> SyntaxTokenKind.Punctuation
                else -> SyntaxTokenKind.Plain
            }
            out += RawGoToken(type, start, end, kind, isHidden = false)
        }
    }
}

private fun classifyGoRawTokens(code: String, raw: List<RawGoToken>) {
    val visible = ArrayList<Int>(raw.size)
    val precededByNewline = ArrayList<Boolean>(raw.size)
    var sawNewline = true
    for (idx in raw.indices) {
        val tok = raw[idx]
        if (tok.isHidden) {
            if (tok.hasNewline) sawNewline = true
        } else {
            visible += idx
            precededByNewline += sawNewline
            sawNewline = false
        }
    }

    var afterFuncKeyword = false
    var afterFuncCanBeReceiver = false
    var receiverParenDepth = 0
    var expectingMethodNameAfterReceiver = false
    var pendingStructOrInterface = false
    var structBraceDepth = 0
    val structDepthStack = ArrayList<Int>()
    var braceDepth = 0
    var parenDepth = 0
    var expectingTypeParamBracket = false
    var typeParamBracketDepth = 0
    val declaredTypeParams = HashSet<String>()

    for (i in visible.indices) {
        val tok = raw[visible[i]]
        val prev = if (i > 0) raw[visible[i - 1]] else null
        val next = if (i + 1 < visible.size) raw[visible[i + 1]] else null
        val lineStart = precededByNewline[i]

        val parenDepthBefore = parenDepth
        when (tok.type) {
            GoTokens.LPAREN -> parenDepth++
            GoTokens.RPAREN -> if (parenDepth > 0) parenDepth--
            GoTokens.LBRACE -> {
                braceDepth++
                if (pendingStructOrInterface) {
                    structDepthStack += braceDepth
                    pendingStructOrInterface = false
                }
            }
            GoTokens.RBRACE -> {
                if (structDepthStack.lastOrNull() == braceDepth) {
                    structDepthStack.removeAt(structDepthStack.lastIndex)
                }
                if (braceDepth > 0) braceDepth--
                if (braceDepth == 0) declaredTypeParams.clear()
            }
            GoTokens.LBRACK -> {
                if (expectingTypeParamBracket) {
                    typeParamBracketDepth = 1
                    expectingTypeParamBracket = false
                } else if (typeParamBracketDepth > 0) {
                    typeParamBracketDepth++
                }
            }
            GoTokens.RBRACK -> {
                if (typeParamBracketDepth > 0) typeParamBracketDepth--
            }
        }
        structBraceDepth = structDepthStack.lastOrNull() ?: 0

        if (receiverParenDepth > 0) {
            if (tok.type == GoTokens.LPAREN) receiverParenDepth++
            else if (tok.type == GoTokens.RPAREN) {
                receiverParenDepth--
                if (receiverParenDepth == 0) {
                    expectingMethodNameAfterReceiver = true
                }
            }
        } else if (afterFuncKeyword) {
            val canBeReceiver = afterFuncCanBeReceiver
            afterFuncKeyword = false
            afterFuncCanBeReceiver = false
            if (tok.type == GoTokens.LPAREN && canBeReceiver) {
                receiverParenDepth = 1
            } else if (tok.type == GoTokens.IDENT) {
                tok.kind = SyntaxTokenKind.Function
                if (next?.type == GoTokens.LBRACK) {
                    expectingTypeParamBracket = true
                    declaredTypeParams.clear()
                }
                continue
            }
        } else if (expectingMethodNameAfterReceiver) {
            expectingMethodNameAfterReceiver = false
            if (tok.type == GoTokens.IDENT) {
                val candidate = code.substring(tok.start, tok.end)
                if (
                    candidate !in GO_KEYWORDS &&
                    candidate !in GO_CONSTANTS &&
                    candidate !in GO_PREDECLARED_TYPES &&
                    (next?.type == GoTokens.LPAREN || next?.type == GoTokens.LBRACK)
                ) {
                    tok.kind = SyntaxTokenKind.Function
                    if (next.type == GoTokens.LBRACK) {
                        expectingTypeParamBracket = true
                    }
                    continue
                }
            }
        }

        if (tok.type != GoTokens.IDENT) {
            if (tok.type != GoTokens.LBRACK && tok.type != GoTokens.IDENT) {
                expectingTypeParamBracket = false
            }
            continue
        }
        val text = code.substring(tok.start, tok.end)

        if (text in GO_CONSTANTS) {
            tok.kind = SyntaxTokenKind.Constant
            continue
        }
        if (text in GO_KEYWORDS) {
            tok.kind = SyntaxTokenKind.Keyword
            if (text == "func") {
                afterFuncKeyword = true
                afterFuncCanBeReceiver = braceDepth == 0 && parenDepthBefore == 0 && (
                    prev == null ||
                        prev.type == GoTokens.SEMI ||
                        prev.type == GoTokens.RBRACE ||
                        (lineStart && !isGoNonReceiverPredecessor(prev.type))
                    )
            }
            if (text == "struct" || text == "interface") pendingStructOrInterface = true
            continue
        }
        if (text in GO_PREDECLARED_TYPES && prev?.type != GoTokens.DOT) {
            tok.kind = SyntaxTokenKind.Type
            continue
        }
        if (text in GO_BUILTINS && prev?.type != GoTokens.DOT && next?.type == GoTokens.LPAREN) {
            tok.kind = SyntaxTokenKind.Function
            continue
        }

        val prevText = if (prev != null && prev.type == GoTokens.IDENT) code.substring(prev.start, prev.end) else null
        if (prevText == "type") {
            tok.kind = SyntaxTokenKind.Type
            if (next?.type == GoTokens.LBRACK) {
                expectingTypeParamBracket = true
                declaredTypeParams.clear()
            }
            continue
        }

        if (typeParamBracketDepth > 0 && text.isNotEmpty() && text[0].isUpperCase()) {
            declaredTypeParams += text
            tok.kind = SyntaxTokenKind.Type
            continue
        }

        if (text in declaredTypeParams && prev?.type != GoTokens.DOT) {
            tok.kind = SyntaxTokenKind.Type
            continue
        }

        if (next?.type == GoTokens.LPAREN) {
            tok.kind = SyntaxTokenKind.Function
            continue
        }

        if (prev?.type == GoTokens.DOT) {
            tok.kind = SyntaxTokenKind.Property
            continue
        }

        // Struct field declarations (e.g. `Port int`, `Name string`)
        if (
            structBraceDepth > 0 &&
            braceDepth == structBraceDepth &&
            (lineStart || prev?.type == GoTokens.LBRACE || prev?.type == GoTokens.SEMI) &&
            next != null &&
            next.type != GoTokens.DOT &&
            next.type != GoTokens.RBRACE &&
            next.type != GoTokens.LPAREN
        ) {
            tok.kind = SyntaxTokenKind.Property
            continue
        }

        if (looksLikeTypeName(text)) {
            tok.kind = SyntaxTokenKind.Type
            continue
        }
    }
}

private fun isGoNonReceiverPredecessor(type: Int): Boolean = when (type) {
    GoTokens.IDENT,
    GoTokens.EQ,
    GoTokens.COLONEQ,
    GoTokens.RBRACK,
    GoTokens.COMMA,
    GoTokens.LPAREN,
    GoTokens.LBRACE,
    GoTokens.COLON,
    GoTokens.LARROW,
    -> true
    else -> false
}

private val GO_KEYWORDS = setOf(
    "break", "case", "chan", "const", "continue",
    "default", "defer", "else", "fallthrough", "for",
    "func", "go", "goto", "if", "import",
    "interface", "map", "package", "range", "return",
    "select", "struct", "switch", "type", "var",
)

private val GO_CONSTANTS = setOf("true", "false", "iota", "nil")

private val GO_PREDECLARED_TYPES = setOf(
    "bool", "byte", "complex64", "complex128", "error",
    "float32", "float64", "int", "int8", "int16", "int32", "int64",
    "rune", "string", "uint", "uint8", "uint16", "uint32", "uint64", "uintptr",
    "any", "comparable",
)

private val GO_BUILTINS = setOf(
    "append", "cap", "clear", "close", "complex", "copy", "delete",
    "imag", "len", "make", "max", "min", "new", "panic", "print",
    "println", "real", "recover",
)

private fun isGoOperator(type: Int): Boolean = when (type) {
    GoTokens.ELLIPSIS,
    GoTokens.COLONEQ,
    GoTokens.LARROW,
    GoTokens.AMPCASEQ,
    GoTokens.AMPCARET,
    GoTokens.SHLEQ,
    GoTokens.SHREQ,
    GoTokens.SHL,
    GoTokens.SHR,
    GoTokens.PLUSPLUS,
    GoTokens.MINUSMINUS,
    GoTokens.ANDAND,
    GoTokens.OROR,
    GoTokens.EQEQ,
    GoTokens.NE,
    GoTokens.LE,
    GoTokens.GE,
    GoTokens.PLUSEQ,
    GoTokens.MINUSEQ,
    GoTokens.STAREQ,
    GoTokens.SLASHEQ,
    GoTokens.PERCENTEQ,
    GoTokens.AMPEQ,
    GoTokens.PIPEEQ,
    GoTokens.CARETEQ,
    GoTokens.PLUS,
    GoTokens.MINUS,
    GoTokens.STAR,
    GoTokens.SLASH,
    GoTokens.PERCENT,
    GoTokens.CARET,
    GoTokens.AMP,
    GoTokens.PIPE,
    GoTokens.BANG,
    GoTokens.EQ,
    GoTokens.LT,
    GoTokens.GT,
    GoTokens.TILDE,
    -> true
    else -> false
}

private fun isGoPunctuation(type: Int): Boolean = when (type) {
    GoTokens.LPAREN,
    GoTokens.RPAREN,
    GoTokens.LBRACE,
    GoTokens.RBRACE,
    GoTokens.LBRACK,
    GoTokens.RBRACK,
    GoTokens.SEMI,
    GoTokens.COMMA,
    GoTokens.DOT,
    GoTokens.COLON,
    -> true
    else -> false
}
