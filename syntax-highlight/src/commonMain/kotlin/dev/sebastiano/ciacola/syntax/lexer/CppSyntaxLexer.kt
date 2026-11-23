package dev.sebastiano.ciacola.syntax.lexer

import dev.sebastiano.ciacola.syntax.SyntaxLanguage
import dev.sebastiano.ciacola.syntax.SyntaxLexer
import dev.sebastiano.ciacola.syntax.SyntaxToken
import dev.sebastiano.ciacola.syntax.SyntaxTokenKind
import dev.sebastiano.ciacola.syntax.antlr.cpp.CppLexer
import dev.sebastiano.ciacola.syntax.antlr.cpp.CppLexer.Tokens as CppTokens
import dev.sebastiano.ciacola.syntax.internal.driveAntlrLexer
import dev.sebastiano.ciacola.syntax.internal.emitDefaultGap
import dev.sebastiano.ciacola.syntax.internal.looksLikeTypeName
import org.antlr.v4.kotlinruntime.CharStreams

/**
 * Lossless ANTLR4 DFA-based lexical tokenizer for C++ (C++20/C++23).
 */
object CppSyntaxLexer : SyntaxLexer {
    override val language: SyntaxLanguage = SyntaxLanguage.Cpp

    override fun lex(source: String): List<SyntaxToken> =
        lexCFamily(source, CPP_DIALECT)
}

internal class CFamilyDialect(
    val isCpp: Boolean,
    val keywords: Set<String>,
    val constants: Set<String>,
    val types: Set<String>,
    val typeIntroducers: Set<String>,
)

internal val CPP_DIALECT = CFamilyDialect(
    isCpp = true,
    keywords = setOf(
        "alignas", "alignof", "and", "and_eq", "asm", "auto",
        "bitand", "bitor", "break", "case", "catch", "class",
        "co_await", "co_return", "co_yield", "compl", "concept",
        "const", "const_cast", "consteval", "constexpr", "constinit",
        "continue", "decltype", "default", "delete", "do",
        "dynamic_cast", "else", "enum", "explicit", "export",
        "extern", "final", "for", "friend", "goto", "if",
        "import", "inline", "module", "mutable", "namespace",
        "new", "noexcept", "not", "not_eq", "operator", "or",
        "or_eq", "override", "private", "protected", "public",
        "register", "reinterpret_cast", "requires", "restrict",
        "return", "sizeof", "static", "static_assert", "static_cast",
        "struct", "switch", "template", "this", "thread_local",
        "throw", "try", "typedef", "typeid", "typename",
        "union", "using", "virtual", "volatile", "while",
        "xor", "xor_eq",
    ),
    constants = setOf("true", "false", "nullptr", "NULL"),
    types = setOf(
        "void", "bool", "char", "char8_t", "char16_t", "char32_t", "wchar_t",
        "short", "int", "long", "signed", "unsigned", "float", "double",
        "size_t", "ssize_t", "ptrdiff_t", "intptr_t", "uintptr_t",
        "int8_t", "int16_t", "int32_t", "int64_t",
        "uint8_t", "uint16_t", "uint32_t", "uint64_t",
        "FILE",
    ),
    typeIntroducers = setOf("struct", "class", "enum", "union", "typename", "concept"),
)

private val CPP_STD_TYPES = setOf(
    "string", "string_view", "wstring", "u8string", "u16string", "u32string",
    "vector", "deque", "list", "forward_list", "array", "span",
    "set", "multiset", "map", "multimap", "unordered_set", "unordered_map",
    "stack", "queue", "priority_queue", "pair", "tuple",
    "optional", "variant", "any", "expected", "function",
    "unique_ptr", "shared_ptr", "weak_ptr", "atomic", "mutex", "thread",
    "ostream", "istream", "iostream", "stringstream", "filesystem",
)

private class RawCppToken(
    val type: Int,
    val start: Int,
    var end: Int,
    var kind: SyntaxTokenKind,
    val isHidden: Boolean,
)

internal fun lexCFamily(source: String, dialect: CFamilyDialect): List<SyntaxToken> {
    if (source.isEmpty()) return emptyList()

    val lexer = CppLexer(CharStreams.fromString(source)).apply {
        enableCppFeatures = dialect.isCpp
    }
    val raw = ArrayList<RawCppToken>(source.length / 3 + 8)

    driveAntlrLexer(
        source = source,
        lexer = lexer,
        onGap = { start, end ->
            emitDefaultGap(source, start, end) { kind, s, e ->
                raw += RawCppToken(
                    type = if (kind == SyntaxTokenKind.Whitespace) CppTokens.WS else CppTokens.ERROR_CHAR,
                    start = s,
                    end = e,
                    kind = kind,
                    isHidden = kind == SyntaxTokenKind.Whitespace,
                )
            }
        },
        onToken = { type, start, end -> emitCppRawToken(type, start, end, raw) },
    )

    classifyCppRawTokens(source, raw, dialect)
    return raw.map { SyntaxToken(kind = it.kind, startIndex = it.start, endIndex = it.end) }
}

private fun emitCppRawToken(
    type: Int,
    start: Int,
    end: Int,
    out: MutableList<RawCppToken>,
) {
    when (type) {
        CppTokens.WS,
        CppTokens.PP_HDR_WS,
        CppTokens.PP_INC_WS,
        CppTokens.PP_BODY_WS,
        CppTokens.PP_HDR_NL,
        CppTokens.PP_INC_NL,
        CppTokens.PP_BODY_NL,
        -> out += RawCppToken(type, start, end, SyntaxTokenKind.Whitespace, isHidden = true)

        CppTokens.LINE_COMMENT,
        CppTokens.BLOCK_COMMENT,
        CppTokens.UNTERMINATED_BLOCK_COMMENT,
        CppTokens.PP_HDR_LINE_COMMENT,
        CppTokens.PP_HDR_BLOCK_COMMENT,
        CppTokens.PP_INC_LINE_COMMENT,
        CppTokens.PP_INC_BLOCK_COMMENT,
        CppTokens.PP_BODY_LINE_COMMENT,
        CppTokens.PP_BODY_BLOCK_COMMENT,
        -> out += RawCppToken(type, start, end, SyntaxTokenKind.Comment, isHidden = true)

        CppTokens.PP_HASH,
        CppTokens.PP_HDR_INCLUDE,
        CppTokens.PP_HDR_DIRECTIVE,
        CppTokens.PP_HDR_OTHER,
        CppTokens.PP_HDR_LINE_CONT,
        CppTokens.PP_INC_LINE_CONT,
        CppTokens.PP_INC_OTHER,
        CppTokens.PP_BODY_LINE_CONT,
        CppTokens.PP_BODY_TEXT,
        -> out += RawCppToken(type, start, end, SyntaxTokenKind.Preprocessor, isHidden = false)

        CppTokens.PP_INC_SYS_HEADER,
        CppTokens.PP_INC_STR_HEADER,
        CppTokens.PP_BODY_STRING,
        CppTokens.PP_BODY_CHAR,
        CppTokens.RAW_STRING,
        CppTokens.STRING,
        CppTokens.UNTERMINATED_STRING,
        CppTokens.CHAR_LITERAL,
        CppTokens.UNTERMINATED_CHAR,
        -> out += RawCppToken(type, start, end, SyntaxTokenKind.String, isHidden = false)

        CppTokens.HEX_FLOAT,
        CppTokens.HEX_INT,
        CppTokens.BIN_INT,
        CppTokens.OCT_INT,
        CppTokens.FLOAT,
        CppTokens.DEC_INT,
        -> out += RawCppToken(type, start, end, SyntaxTokenKind.Number, isHidden = false)

        CppTokens.ERROR_CHAR -> out += RawCppToken(type, start, end, SyntaxTokenKind.Invalid, isHidden = false)

        else -> {
            val kind = when {
                isCppOperator(type) -> SyntaxTokenKind.Operator
                isCppPunctuation(type) -> SyntaxTokenKind.Punctuation
                else -> SyntaxTokenKind.Plain
            }
            out += RawCppToken(type, start, end, kind, isHidden = false)
        }
    }
}

private fun classifyCppRawTokens(
    code: String,
    raw: MutableList<RawCppToken>,
    dialect: CFamilyDialect,
) {
    // Merge C++11+ user-defined literal suffixes (`100ms`, `0xFF_u8`, `"hi"sv`, `R"(x)"_s`)
    if (dialect.isCpp) {
        var m = 0
        while (m + 1 < raw.size) {
            val cur = raw[m]
            val nxt = raw[m + 1]
            if (
                cur.end == nxt.start &&
                nxt.type == CppTokens.IDENT &&
                isCppUdlBaseLiteral(cur.type)
            ) {
                cur.end = nxt.end
                raw.removeAt(m + 1)
            } else {
                m++
            }
        }
    }

    val visible = ArrayList<Int>(raw.size)
    for (idx in raw.indices) {
        if (!raw[idx].isHidden) visible += idx
    }

    var inDoubleBracketAttr = false
    val templateTypeParams = HashSet<String>()
    var braceDepth = 0
    var i = 0
    while (i < visible.size) {
        val tok = raw[visible[i]]
        val prev = if (i > 0) raw[visible[i - 1]] else null
        val next = if (i + 1 < visible.size) raw[visible[i + 1]] else null

        when (tok.type) {
            CppTokens.LBRACE -> braceDepth++
            CppTokens.RBRACE -> {
                if (braceDepth > 0) braceDepth--
                if (braceDepth == 0) templateTypeParams.clear()
            }
        }

        if (inDoubleBracketAttr) {
            if (
                tok.type == CppTokens.RBRACK &&
                next != null &&
                next.type == CppTokens.RBRACK &&
                tok.end == next.start
            ) {
                inDoubleBracketAttr = false
                i += 2
                continue
            }
            if (tok.type == CppTokens.IDENT) {
                tok.kind = SyntaxTokenKind.Attribute
            }
            i++
            continue
        }

        if (
            tok.type == CppTokens.LBRACK &&
            next != null &&
            next.type == CppTokens.LBRACK &&
            tok.end == next.start
        ) {
            inDoubleBracketAttr = true
            i += 2
            continue
        }

        if (tok.type == CppTokens.IDENT) {
            val text = code.substring(tok.start, tok.end)
            tok.kind = classifyCppIdentifier(
                code = code,
                text = text,
                prev = prev,
                next = next,
                dialect = dialect,
                templateTypeParams = templateTypeParams,
            )
            val prevText = if (prev != null && prev.type == CppTokens.IDENT) code.substring(prev.start, prev.end) else null
            if (dialect.isCpp && (prevText == "typename" || prevText == "class") && text.isNotEmpty() && text[0].isUpperCase()) {
                templateTypeParams += text
            }
        }

        i++
    }
}

private fun isCppUdlBaseLiteral(type: Int): Boolean = when (type) {
    CppTokens.RAW_STRING,
    CppTokens.STRING,
    CppTokens.CHAR_LITERAL,
    CppTokens.HEX_FLOAT,
    CppTokens.HEX_INT,
    CppTokens.BIN_INT,
    CppTokens.OCT_INT,
    CppTokens.FLOAT,
    CppTokens.DEC_INT,
    -> true
    else -> false
}

private fun classifyCppIdentifier(
    code: String,
    text: String,
    prev: RawCppToken?,
    next: RawCppToken?,
    dialect: CFamilyDialect,
    templateTypeParams: Set<String>,
): SyntaxTokenKind {
    if (text in dialect.constants) return SyntaxTokenKind.Constant
    if (text in dialect.keywords) return SyntaxTokenKind.Keyword
    if (text in dialect.types) return SyntaxTokenKind.Type

    val prevText = if (prev != null && prev.type == CppTokens.IDENT) code.substring(prev.start, prev.end) else null
    if (prevText in dialect.typeIntroducers) return SyntaxTokenKind.Type

    if (next?.type == CppTokens.LPAREN) {
        return SyntaxTokenKind.Function
    }

    if (prev?.type == CppTokens.DOT || prev?.type == CppTokens.ARROW) {
        return SyntaxTokenKind.Property
    }

    if (dialect.isCpp && prev?.type == CppTokens.COLONCOLON && text in CPP_STD_TYPES) {
        return SyntaxTokenKind.Type
    }

    if (dialect.isCpp && text in templateTypeParams) {
        return SyntaxTokenKind.Type
    }

    if (looksLikeTypeName(text)) return SyntaxTokenKind.Type

    if (text.length > 1 && text[0].isUpperCase() && text.all { it.isUpperCase() || it.isDigit() || it == '_' }) {
        return SyntaxTokenKind.Constant
    }

    return SyntaxTokenKind.Plain
}

private fun isCppOperator(type: Int): Boolean = when (type) {
    CppTokens.SPACESHIP,
    CppTokens.ELLIPSIS,
    CppTokens.ARROWSTAR,
    CppTokens.DOTSTAR,
    CppTokens.COLONCOLON,
    CppTokens.ARROW,
    CppTokens.SHLEQ,
    CppTokens.SHREQ,
    CppTokens.SHL,
    CppTokens.SHR,
    CppTokens.PLUSPLUS,
    CppTokens.MINUSMINUS,
    CppTokens.ANDAND,
    CppTokens.OROR,
    CppTokens.EQEQ,
    CppTokens.NE,
    CppTokens.LE,
    CppTokens.GE,
    CppTokens.PLUSEQ,
    CppTokens.MINUSEQ,
    CppTokens.STAREQ,
    CppTokens.SLASHEQ,
    CppTokens.PERCENTEQ,
    CppTokens.AMPEQ,
    CppTokens.PIPEEQ,
    CppTokens.CARETEQ,
    CppTokens.QUESTION,
    CppTokens.PLUS,
    CppTokens.MINUS,
    CppTokens.STAR,
    CppTokens.SLASH,
    CppTokens.PERCENT,
    CppTokens.CARET,
    CppTokens.AMP,
    CppTokens.PIPE,
    CppTokens.TILDE,
    CppTokens.BANG,
    CppTokens.EQ,
    CppTokens.LT,
    CppTokens.GT,
    -> true
    else -> false
}

private fun isCppPunctuation(type: Int): Boolean = when (type) {
    CppTokens.LPAREN,
    CppTokens.RPAREN,
    CppTokens.LBRACE,
    CppTokens.RBRACE,
    CppTokens.LBRACK,
    CppTokens.RBRACK,
    CppTokens.SEMI,
    CppTokens.COMMA,
    CppTokens.DOT,
    CppTokens.COLON,
    -> true
    else -> false
}
