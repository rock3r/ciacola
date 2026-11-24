package dev.sebastiano.ciacola.syntax.lexer

import dev.sebastiano.ciacola.syntax.SyntaxLanguage
import dev.sebastiano.ciacola.syntax.SyntaxLexer
import dev.sebastiano.ciacola.syntax.SyntaxToken
import dev.sebastiano.ciacola.syntax.SyntaxTokenKind
import dev.sebastiano.ciacola.syntax.antlr.ruby.RubyLexer
import dev.sebastiano.ciacola.syntax.antlr.ruby.RubyLexer.Tokens as RubyTokens
import dev.sebastiano.ciacola.syntax.internal.antlrCharStream
import dev.sebastiano.ciacola.syntax.internal.driveAntlrLexer
import dev.sebastiano.ciacola.syntax.internal.emitDefaultGap
import dev.sebastiano.ciacola.syntax.internal.looksLikeTypeName

/**
 * Lossless ANTLR4 DFA-based lexical tokenizer for Ruby 3.3+ source code.
 */
internal object RubySyntaxLexer : SyntaxLexer {
    override val language: SyntaxLanguage = SyntaxLanguage.Ruby

    override fun lex(source: String): List<SyntaxToken> {
        if (source.isEmpty()) return emptyList()

        val lexer = RubyLexer(antlrCharStream(source))
        val raw = ArrayList<RawRubyToken>(source.length / 3 + 8)

        driveAntlrLexer(
            source = source,
            lexer = lexer,
            onGap = { start, end ->
                emitDefaultGap(source, start, end) { kind, s, e ->
                    raw += RawRubyToken(
                        type = if (kind == SyntaxTokenKind.Whitespace) RubyTokens.WS else RubyTokens.ERROR_CHAR,
                        start = s,
                        end = e,
                        kind = kind,
                        isHidden = kind == SyntaxTokenKind.Whitespace,
                    )
                }
            },
            onToken = { type, start, end -> emitRawRubyToken(source, type, start, end, raw) },
        )

        classifyRubyTokens(source, raw)
        return raw.map { SyntaxToken(kind = it.kind, startIndex = it.start, endIndex = it.end) }
    }
}

private class RawRubyToken(
    val type: Int,
    val start: Int,
    val end: Int,
    var kind: SyntaxTokenKind,
    val isHidden: Boolean,
)

private fun emitRawRubyToken(
    source: String,
    type: Int,
    start: Int,
    end: Int,
    out: MutableList<RawRubyToken>,
) {
    when (type) {
        RubyTokens.WS,
        RubyTokens.LINE_CONTINUATION,
        RubyTokens.NEWLINE,
        RubyTokens.HL_NEWLINE,
        RubyTokens.HE_NEWLINE,
        -> out += RawRubyToken(type, start, end, SyntaxTokenKind.Whitespace, isHidden = true)

        RubyTokens.SHEBANG,
        RubyTokens.COMMENT,
        RubyTokens.EMBEDDED_DOC,
        -> out += RawRubyToken(type, start, end, SyntaxTokenKind.Comment, isHidden = true)

        RubyTokens.END_DATA ->
            out += RawRubyToken(type, start, end, SyntaxTokenKind.Keyword, isHidden = false)

        RubyTokens.DATA_BODY ->
            out += RawRubyToken(type, start, end, SyntaxTokenKind.Plain, isHidden = false)

        RubyTokens.HASH_KEY -> {
            // Split `key:` into Constant (`key`) + Punctuation (`:`).
            val colonPos = end - 1
            if (colonPos > start) {
                out += RawRubyToken(type, start, colonPos, SyntaxTokenKind.Constant, isHidden = false)
                out += RawRubyToken(RubyTokens.COLON, colonPos, end, SyntaxTokenKind.Punctuation, isHidden = false)
            } else {
                out += RawRubyToken(type, start, end, SyntaxTokenKind.Constant, isHidden = false)
            }
        }

        RubyTokens.SHORTHAND_VAR -> {
            // Split `#@ivar`, `#@@cvar`, `#$gvar` into Punctuation (`#`) + Plain variable name.
            if (end - start > 1) {
                out += RawRubyToken(RubyTokens.INTERP_CLOSE, start, start + 1, SyntaxTokenKind.Punctuation, isHidden = false)
                out += RawRubyToken(RubyTokens.IVAR, start + 1, end, SyntaxTokenKind.Plain, isHidden = false)
            } else {
                out += RawRubyToken(type, start, end, SyntaxTokenKind.String, isHidden = false)
            }
        }

        RubyTokens.HEREDOC_END -> {
            var indentEnd = start
            while (indentEnd < end && (source[indentEnd] == ' ' || source[indentEnd] == '\t')) {
                indentEnd++
            }
            if (indentEnd > start) {
                out += RawRubyToken(RubyTokens.WS, start, indentEnd, SyntaxTokenKind.Whitespace, isHidden = true)
            }
            if (indentEnd < end) {
                out += RawRubyToken(type, indentEnd, end, SyntaxTokenKind.String, isHidden = false)
            }
        }

        RubyTokens.SYM_DQUOTE_OPEN,
        RubyTokens.SYM_SINGLE,
        RubyTokens.SYM_OP,
        RubyTokens.SYM_VAR,
        RubyTokens.SYM_IDENT,
        RubyTokens.PERCENT_SYM_OPEN,
        RubyTokens.PERCENT_SYM_TEXT,
        RubyTokens.PERCENT_SYM_CLOSE,
        RubyTokens.DQUOTE_SYM_CLOSE,
        RubyTokens.DQUOTE_SYM_TEXT,
        RubyTokens.DQUOTE_SYM_ESCAPE,
        -> out += RawRubyToken(type, start, end, SyntaxTokenKind.Constant, isHidden = false)

        RubyTokens.SINGLE_STRING,
        RubyTokens.DQUOTE_OPEN,
        RubyTokens.DQ_CLOSE,
        RubyTokens.DQ_ESCAPE,
        RubyTokens.DQ_BACKSLASH,
        RubyTokens.DQ_TEXT,
        RubyTokens.BACKTICK_OPEN,
        RubyTokens.BT_CLOSE,
        RubyTokens.BT_ESCAPE,
        RubyTokens.BT_BACKSLASH,
        RubyTokens.BT_TEXT,
        RubyTokens.HEREDOC_OPEN,
        RubyTokens.HL_TEXT,
        RubyTokens.HE_ESCAPE,
        RubyTokens.HE_BACKSLASH,
        RubyTokens.HE_TEXT,
        RubyTokens.PERCENT_LITERAL_OPEN,
        RubyTokens.PL_ESCAPE,
        RubyTokens.PL_BACKSLASH,
        RubyTokens.PL_TEXT,
        RubyTokens.PI_ESCAPE,
        RubyTokens.PI_BACKSLASH,
        RubyTokens.PI_TEXT,
        RubyTokens.PERCENT_CLOSE,
        RubyTokens.REGEX_OPEN,
        RubyTokens.RE_ESCAPE,
        RubyTokens.RE_BACKSLASH,
        RubyTokens.RE_SLASH,
        RubyTokens.RE_TEXT,
        RubyTokens.CHAR_LITERAL,
        -> out += RawRubyToken(type, start, end, SyntaxTokenKind.String, isHidden = false)

        RubyTokens.DQ_INTERP_OPEN,
        RubyTokens.BT_INTERP_OPEN,
        RubyTokens.RE_INTERP_OPEN,
        RubyTokens.PI_INTERP_OPEN,
        RubyTokens.HE_INTERP_OPEN,
        RubyTokens.INTERP_CLOSE,
        -> out += RawRubyToken(type, start, end, SyntaxTokenKind.Punctuation, isHidden = false)

        RubyTokens.HEX_LITERAL,
        RubyTokens.BIN_LITERAL,
        RubyTokens.OCT_LITERAL,
        RubyTokens.DEC_PREFIX_LITERAL,
        RubyTokens.FLOAT_LITERAL,
        RubyTokens.INT_LITERAL,
        -> out += RawRubyToken(type, start, end, SyntaxTokenKind.Number, isHidden = false)

        RubyTokens.CVAR,
        RubyTokens.IVAR,
        RubyTokens.GVAR,
        -> out += RawRubyToken(type, start, end, SyntaxTokenKind.Plain, isHidden = false)

        RubyTokens.ERROR_CHAR,
        RubyTokens.AT,
        RubyTokens.DOLLAR,
        RubyTokens.BACKSLASH,
        -> out += RawRubyToken(type, start, end, SyntaxTokenKind.Invalid, isHidden = false)

        else -> {
            val initialKind = when {
                isRubyOperator(type) -> SyntaxTokenKind.Operator
                isRubyPunctuation(type) -> SyntaxTokenKind.Punctuation
                else -> SyntaxTokenKind.Plain
            }
            out += RawRubyToken(type, start, end, initialKind, isHidden = false)
        }
    }
}

private fun classifyRubyTokens(code: String, raw: List<RawRubyToken>) {
    val visible = ArrayList<Int>(raw.size)
    val precededByNewline = ArrayList<Boolean>(raw.size)
    var sawNewline = false
    for (idx in raw.indices) {
        val tok = raw[idx]
        if (tok.isHidden) {
            if (tok.type == RubyTokens.NEWLINE) sawNewline = true
        } else {
            visible += idx
            precededByNewline += sawNewline
            sawNewline = false
        }
    }

    var expectingDefName = false
    var expectingClassOrModuleName = false
    var expectingSuperclassName = false
    var defExpectingMethodAfterReceiver = false

    var i = 0
    while (i < visible.size) {
        val tok = raw[visible[i]]
        val prev = if (i > 0) raw[visible[i - 1]] else null
        val next = if (i + 1 < visible.size) raw[visible[i + 1]] else null
        val lineBreakBefore = precededByNewline[i]
        val afterDot = prev != null && (prev.type == RubyTokens.DOT || prev.type == RubyTokens.SAFE_NAV)
        val afterScope = prev != null && prev.type == RubyTokens.COLON_COLON

        if (tok.type == RubyTokens.SEMI || lineBreakBefore) {
            expectingSuperclassName = false
            if (tok.type == RubyTokens.SEMI) {
                expectingDefName = false
                expectingClassOrModuleName = false
                defExpectingMethodAfterReceiver = false
            }
        }

        if (tok.type == RubyTokens.UNARY_OP_DEF || tok.type == RubyTokens.BACKTICK_OP_DEF) {
            tok.kind = SyntaxTokenKind.Function
            expectingDefName = false
            defExpectingMethodAfterReceiver = false
            i++
            continue
        }

        if (expectingDefName) {
            if (tok.type == RubyTokens.IDENT) {
                val lexeme = code.substring(tok.start, tok.end)
                if (lexeme == "self" && next != null && (next.type == RubyTokens.DOT || next.type == RubyTokens.COLON_COLON)) {
                    tok.kind = SyntaxTokenKind.Keyword
                    defExpectingMethodAfterReceiver = true
                    expectingDefName = false
                } else if (next != null && (next.type == RubyTokens.DOT || next.type == RubyTokens.COLON_COLON)) {
                    tok.kind = if (looksLikeTypeName(lexeme)) SyntaxTokenKind.Type else SyntaxTokenKind.Plain
                    defExpectingMethodAfterReceiver = true
                    expectingDefName = false
                } else {
                    tok.kind = SyntaxTokenKind.Function
                    // Handle setter method definition `def foo=(v)` where `=` immediately follows `foo`.
                    if (next != null && next.type == RubyTokens.ASSIGN && next.start == tok.end) {
                        next.kind = SyntaxTokenKind.Function
                    }
                    expectingDefName = false
                }
                i++
                continue
            } else if (isRubyDefOperatorName(tok.type)) {
                tok.kind = SyntaxTokenKind.Function
                expectingDefName = false
                i++
                continue
            } else if (tok.type == RubyTokens.LBRACK && next != null && next.type == RubyTokens.RBRACK) {
                tok.kind = SyntaxTokenKind.Function
                next.kind = SyntaxTokenKind.Function
                val third = if (i + 2 < visible.size) raw[visible[i + 2]] else null
                if (third != null && third.type == RubyTokens.ASSIGN && third.start == next.end) {
                    third.kind = SyntaxTokenKind.Function
                    i += 3
                } else {
                    i += 2
                }
                expectingDefName = false
                continue
            } else {
                expectingDefName = false
            }
        }

        if (defExpectingMethodAfterReceiver) {
            if (tok.type == RubyTokens.DOT || tok.type == RubyTokens.COLON_COLON) {
                i++
                continue
            }
            defExpectingMethodAfterReceiver = false
            if (tok.type == RubyTokens.IDENT) {
                tok.kind = SyntaxTokenKind.Function
                if (next != null && next.type == RubyTokens.ASSIGN && next.start == tok.end) {
                    next.kind = SyntaxTokenKind.Function
                }
                i++
                continue
            } else if (isRubyDefOperatorName(tok.type)) {
                tok.kind = SyntaxTokenKind.Function
                i++
                continue
            }
        }

        if (expectingClassOrModuleName) {
            if (tok.type == RubyTokens.LSHIFT) {
                // `class << self`
                expectingClassOrModuleName = false
                i++
                continue
            }
            if (tok.type == RubyTokens.COLON_COLON) {
                i++
                continue
            }
            if (tok.type == RubyTokens.IDENT) {
                tok.kind = SyntaxTokenKind.Type
                if (next == null || next.type != RubyTokens.COLON_COLON) {
                    expectingClassOrModuleName = false
                    if (next != null && next.type == RubyTokens.LT) {
                        expectingSuperclassName = true
                    }
                }
                i++
                continue
            }
            expectingClassOrModuleName = false
        }

        if (expectingSuperclassName) {
            if (tok.type == RubyTokens.LT || tok.type == RubyTokens.COLON_COLON) {
                i++
                continue
            }
            if (tok.type == RubyTokens.IDENT) {
                tok.kind = SyntaxTokenKind.Type
                if (next == null || next.type != RubyTokens.COLON_COLON) {
                    expectingSuperclassName = false
                }
                i++
                continue
            }
            expectingSuperclassName = false
        }

        if (tok.type != RubyTokens.IDENT) {
            i++
            continue
        }

        val lexeme = code.substring(tok.start, tok.end)

        if (afterDot) {
            tok.kind = SyntaxTokenKind.Function
            i++
            continue
        }

        if (!afterScope && lexeme in RUBY_CONSTANTS) {
            tok.kind = SyntaxTokenKind.Constant
        } else if (!afterScope && lexeme in RUBY_KEYWORDS) {
            tok.kind = SyntaxTokenKind.Keyword
            if (lexeme == "def") {
                expectingDefName = true
            } else if (lexeme == "class" || lexeme == "module") {
                expectingClassOrModuleName = true
            }
        } else if (!afterScope && lexeme in RUBY_MODIFIER_KEYWORDS && next?.type != RubyTokens.ASSIGN) {
            tok.kind = SyntaxTokenKind.Keyword
        } else if (!afterScope && lexeme in RUBY_KERNEL_FUNCTIONS && next?.type != RubyTokens.ASSIGN) {
            tok.kind = SyntaxTokenKind.Function
        } else if (isRubyConstantName(lexeme)) {
            tok.kind = if (looksLikeTypeName(lexeme)) SyntaxTokenKind.Type else SyntaxTokenKind.Constant
        } else if (next?.type == RubyTokens.LPAREN) {
            tok.kind = SyntaxTokenKind.Function
        } else {
            tok.kind = SyntaxTokenKind.Plain
        }

        i++
    }
}

private fun isRubyConstantName(text: String): Boolean {
    if (text.isEmpty()) return false
    val first = text[0]
    if (first !in 'A'..'Z') return false
    val last = text[text.length - 1]
    // Methods like `Integer?` or `Foo!` are method calls, not constants.
    return last != '?' && last != '!'
}

private fun isRubyDefOperatorName(type: Int): Boolean = when (type) {
    RubyTokens.SPACESHIP,
    RubyTokens.TRIPLE_EQ,
    RubyTokens.POW,
    RubyTokens.LSHIFT,
    RubyTokens.RSHIFT,
    RubyTokens.EQ_EQ,
    RubyTokens.NOT_EQ,
    RubyTokens.MATCH,
    RubyTokens.NOT_MATCH,
    RubyTokens.LE,
    RubyTokens.GE,
    RubyTokens.PLUS,
    RubyTokens.MINUS,
    RubyTokens.STAR,
    RubyTokens.SLASH,
    RubyTokens.PERCENT,
    RubyTokens.LT,
    RubyTokens.GT,
    RubyTokens.BANG,
    RubyTokens.TILDE,
    RubyTokens.AMP,
    RubyTokens.PIPE,
    RubyTokens.CARET,
    -> true
    else -> false
}

private fun isRubyOperator(type: Int): Boolean = when (type) {
    RubyTokens.SPACESHIP,
    RubyTokens.TRIPLE_EQ,
    RubyTokens.POW_ASSIGN,
    RubyTokens.LSHIFT_ASSIGN,
    RubyTokens.RSHIFT_ASSIGN,
    RubyTokens.AND_AND_ASSIGN,
    RubyTokens.OR_OR_ASSIGN,
    RubyTokens.RANGE_INC,
    RubyTokens.ELLIPSIS,
    RubyTokens.HASH_ROCKET,
    RubyTokens.STABBY_LAMBDA,
    RubyTokens.POW,
    RubyTokens.LSHIFT,
    RubyTokens.RSHIFT,
    RubyTokens.EQ_EQ,
    RubyTokens.NOT_EQ,
    RubyTokens.MATCH,
    RubyTokens.NOT_MATCH,
    RubyTokens.LE,
    RubyTokens.GE,
    RubyTokens.AND_AND,
    RubyTokens.OR_OR,
    RubyTokens.PLUS_ASSIGN,
    RubyTokens.MINUS_ASSIGN,
    RubyTokens.STAR_ASSIGN,
    RubyTokens.SLASH_ASSIGN,
    RubyTokens.PERCENT_ASSIGN,
    RubyTokens.AMP_ASSIGN,
    RubyTokens.PIPE_ASSIGN,
    RubyTokens.CARET_ASSIGN,
    RubyTokens.ASSIGN,
    RubyTokens.PLUS,
    RubyTokens.MINUS,
    RubyTokens.STAR,
    RubyTokens.SLASH,
    RubyTokens.PERCENT,
    RubyTokens.LT,
    RubyTokens.GT,
    RubyTokens.BANG,
    RubyTokens.TILDE,
    RubyTokens.AMP,
    RubyTokens.PIPE,
    RubyTokens.CARET,
    RubyTokens.QUESTION,
    RubyTokens.SAFE_NAV,
    -> true
    else -> false
}

private fun isRubyPunctuation(type: Int): Boolean = when (type) {
    RubyTokens.LPAREN,
    RubyTokens.RPAREN,
    RubyTokens.LBRACK,
    RubyTokens.RBRACK,
    RubyTokens.LBRACE,
    RubyTokens.RBRACE,
    RubyTokens.COMMA,
    RubyTokens.SEMI,
    RubyTokens.DOT,
    RubyTokens.COLON,
    RubyTokens.COLON_COLON,
    -> true
    else -> false
}

private val RUBY_CONSTANTS = hashSetOf(
    "true",
    "false",
    "nil",
)

private val RUBY_KEYWORDS = hashSetOf(
    "__ENCODING__",
    "__FILE__",
    "__LINE__",
    "BEGIN",
    "END",
    "alias",
    "and",
    "begin",
    "break",
    "case",
    "class",
    "def",
    "defined?",
    "do",
    "else",
    "elsif",
    "end",
    "ensure",
    "for",
    "if",
    "in",
    "module",
    "next",
    "not",
    "or",
    "redo",
    "rescue",
    "retry",
    "return",
    "self",
    "super",
    "then",
    "undef",
    "unless",
    "until",
    "when",
    "while",
    "yield",
)

private val RUBY_MODIFIER_KEYWORDS = hashSetOf(
    "private",
    "protected",
    "public",
    "attr_reader",
    "attr_writer",
    "attr_accessor",
    "attr",
    "module_function",
    "include",
    "extend",
    "prepend",
    "require",
    "require_relative",
    "raise",
    "fail",
    "lambda",
    "proc",
)

private val RUBY_KERNEL_FUNCTIONS = hashSetOf(
    "puts",
    "print",
    "p",
    "pp",
    "sprintf",
    "format",
    "warn",
    "gets",
    "readline",
    "readlines",
    "open",
    "loop",
    "trap",
    "exit",
    "abort",
    "sleep",
    "srand",
    "rand",
    "eval",
    "binding",
    "caller",
    "block_given?",
    "iterator?",
    "throw",
    "catch",
    "tap",
    "yield_self",
    "freeze",
    "dup",
    "clone",
)
