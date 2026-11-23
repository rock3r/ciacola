package dev.sebastiano.ciacola.syntax.lexer

import dev.sebastiano.ciacola.syntax.SyntaxLanguage
import dev.sebastiano.ciacola.syntax.SyntaxLexer
import dev.sebastiano.ciacola.syntax.SyntaxToken
import dev.sebastiano.ciacola.syntax.SyntaxTokenKind
import dev.sebastiano.ciacola.syntax.antlr.python.PythonLexer
import dev.sebastiano.ciacola.syntax.antlr.python.PythonLexer.Tokens as PythonTokens
import dev.sebastiano.ciacola.syntax.internal.driveAntlrLexer
import dev.sebastiano.ciacola.syntax.internal.emitDefaultGap
import dev.sebastiano.ciacola.syntax.internal.looksLikeTypeName
import org.antlr.v4.kotlinruntime.CharStreams

/**
 * Lossless ANTLR4 DFA-based lexical tokenizer for Python 3.12+ source code.
 */
object PythonSyntaxLexer : SyntaxLexer {
    override val language: SyntaxLanguage = SyntaxLanguage.Python

    override fun lex(source: String): List<SyntaxToken> {
        if (source.isEmpty()) return emptyList()

        val lexer = PythonLexer(CharStreams.fromString(source))
        val raw = ArrayList<RawPythonToken>(source.length / 3 + 8)

        driveAntlrLexer(
            source = source,
            lexer = lexer,
            onGap = { start, end ->
                emitDefaultGap(source, start, end) { kind, s, e ->
                    raw += RawPythonToken(
                        type = if (kind == SyntaxTokenKind.Whitespace) PythonTokens.WS else PythonTokens.ERROR_CHAR,
                        start = s,
                        end = e,
                        kind = kind,
                        isHidden = kind == SyntaxTokenKind.Whitespace,
                    )
                }
            },
            onToken = { type, start, end -> emitRawPythonToken(type, start, end, raw) },
        )

        classifyPythonTokens(source, raw)
        return raw.map { SyntaxToken(kind = it.kind, startIndex = it.start, endIndex = it.end) }
    }
}

private class RawPythonToken(
    val type: Int,
    val start: Int,
    val end: Int,
    var kind: SyntaxTokenKind,
    val isHidden: Boolean,
    val isLogicalNewline: Boolean = false,
)

private fun emitRawPythonToken(
    type: Int,
    start: Int,
    end: Int,
    out: MutableList<RawPythonToken>,
) {
    when (type) {
        PythonTokens.WS,
        PythonTokens.LINE_CONTINUATION,
        -> out += RawPythonToken(type, start, end, SyntaxTokenKind.Whitespace, isHidden = true)

        PythonTokens.NEWLINE,
        PythonTokens.FD_UNTERM_NL,
        PythonTokens.FS_UNTERM_NL,
        PythonTokens.FRD_UNTERM_NL,
        PythonTokens.FRS_UNTERM_NL,
        PythonTokens.FFS_NEWLINE,
        -> out += RawPythonToken(type, start, end, SyntaxTokenKind.Whitespace, isHidden = true, isLogicalNewline = true)

        PythonTokens.COMMENT ->
            out += RawPythonToken(type, start, end, SyntaxTokenKind.Comment, isHidden = true)

        PythonTokens.TRIPLE_DOUBLE_STRING,
        PythonTokens.TRIPLE_SINGLE_STRING,
        PythonTokens.DOUBLE_STRING,
        PythonTokens.SINGLE_STRING,
        PythonTokens.F_TRIPLE_DOUBLE_RAW_START,
        PythonTokens.F_TRIPLE_SINGLE_RAW_START,
        PythonTokens.F_TRIPLE_DOUBLE_START,
        PythonTokens.F_TRIPLE_SINGLE_START,
        PythonTokens.F_DOUBLE_RAW_START,
        PythonTokens.F_SINGLE_RAW_START,
        PythonTokens.F_DOUBLE_START,
        PythonTokens.F_SINGLE_START,
        PythonTokens.FSTR_INTERP_CLOSE,
        PythonTokens.FSTR_FORMAT_COLON,
        PythonTokens.FD_ESCAPED_BRACE,
        PythonTokens.FD_INTERP_OPEN,
        PythonTokens.FD_NAMED_ESCAPE,
        PythonTokens.FD_ESCAPE,
        PythonTokens.FD_BACKSLASH,
        PythonTokens.FD_CLOSE,
        PythonTokens.FD_TEXT,
        PythonTokens.FS_ESCAPED_BRACE,
        PythonTokens.FS_INTERP_OPEN,
        PythonTokens.FS_NAMED_ESCAPE,
        PythonTokens.FS_ESCAPE,
        PythonTokens.FS_BACKSLASH,
        PythonTokens.FS_CLOSE,
        PythonTokens.FS_TEXT,
        PythonTokens.FRD_ESCAPED_BRACE,
        PythonTokens.FRD_INTERP_OPEN,
        PythonTokens.FRD_ESCAPE,
        PythonTokens.FRD_BACKSLASH,
        PythonTokens.FRD_CLOSE,
        PythonTokens.FRD_TEXT,
        PythonTokens.FRS_ESCAPED_BRACE,
        PythonTokens.FRS_INTERP_OPEN,
        PythonTokens.FRS_ESCAPE,
        PythonTokens.FRS_BACKSLASH,
        PythonTokens.FRS_CLOSE,
        PythonTokens.FRS_TEXT,
        PythonTokens.FTD_ESCAPED_BRACE,
        PythonTokens.FTD_INTERP_OPEN,
        PythonTokens.FTD_NAMED_ESCAPE,
        PythonTokens.FTD_ESCAPE,
        PythonTokens.FTD_BACKSLASH,
        PythonTokens.FTD_CLOSE,
        PythonTokens.FTD_QUOTES,
        PythonTokens.FTD_TEXT,
        PythonTokens.FTS_ESCAPED_BRACE,
        PythonTokens.FTS_INTERP_OPEN,
        PythonTokens.FTS_NAMED_ESCAPE,
        PythonTokens.FTS_ESCAPE,
        PythonTokens.FTS_BACKSLASH,
        PythonTokens.FTS_CLOSE,
        PythonTokens.FTS_QUOTES,
        PythonTokens.FTS_TEXT,
        PythonTokens.FRTD_ESCAPED_BRACE,
        PythonTokens.FRTD_INTERP_OPEN,
        PythonTokens.FRTD_ESCAPE,
        PythonTokens.FRTD_BACKSLASH,
        PythonTokens.FRTD_CLOSE,
        PythonTokens.FRTD_QUOTES,
        PythonTokens.FRTD_TEXT,
        PythonTokens.FRTS_ESCAPED_BRACE,
        PythonTokens.FRTS_INTERP_OPEN,
        PythonTokens.FRTS_ESCAPE,
        PythonTokens.FRTS_BACKSLASH,
        PythonTokens.FRTS_CLOSE,
        PythonTokens.FRTS_QUOTES,
        PythonTokens.FRTS_TEXT,
        PythonTokens.FFS_INTERP_OPEN,
        PythonTokens.FFS_INTERP_CLOSE,
        PythonTokens.FFS_TEXT,
        -> out += RawPythonToken(type, start, end, SyntaxTokenKind.String, isHidden = false)

        PythonTokens.IMAG_NUMBER,
        PythonTokens.FLOAT_NUMBER,
        PythonTokens.HEX_INTEGER,
        PythonTokens.BIN_INTEGER,
        PythonTokens.OCT_INTEGER,
        PythonTokens.DEC_INTEGER,
        -> out += RawPythonToken(type, start, end, SyntaxTokenKind.Number, isHidden = false)

        PythonTokens.TRUE,
        PythonTokens.FALSE,
        PythonTokens.NONE,
        -> out += RawPythonToken(type, start, end, SyntaxTokenKind.Constant, isHidden = false)

        PythonTokens.ERROR_CHAR,
        PythonTokens.BANG,
        -> out += RawPythonToken(type, start, end, SyntaxTokenKind.Invalid, isHidden = false)

        else -> {
            val initialKind = when {
                isPythonHardKeyword(type) -> SyntaxTokenKind.Keyword
                isPythonOperator(type) -> SyntaxTokenKind.Operator
                isPythonPunctuation(type) -> SyntaxTokenKind.Punctuation
                else -> SyntaxTokenKind.Plain
            }
            out += RawPythonToken(type, start, end, initialKind, isHidden = false)
        }
    }
}

private fun classifyPythonTokens(code: String, raw: List<RawPythonToken>) {
    val visible = ArrayList<Int>(raw.size)
    val precededByNewline = ArrayList<Boolean>(raw.size)
    var sawNewline = true
    for (idx in raw.indices) {
        val tok = raw[idx]
        if (tok.isHidden) {
            if (tok.isLogicalNewline) sawNewline = true
        } else {
            visible += idx
            precededByNewline += sawNewline
            sawNewline = false
        }
    }

    var bracketDepth = 0
    var inImportStatement = false
    var fstringInterpDepth = 0
    var expectingTypeAliasName = false
    var i = 0

    while (i < visible.size) {
        val tok = raw[visible[i]]
        val prev = if (i > 0) raw[visible[i - 1]] else null
        val next = if (i + 1 < visible.size) raw[visible[i + 1]] else null
        val lineStart = precededByNewline[i]
        val atStatementStart = bracketDepth == 0 &&
            (lineStart || prev == null || prev.type == PythonTokens.SEMI)

        if (atStatementStart) {
            inImportStatement = false
            expectingTypeAliasName = false
        }

        if (isFStringInterpOpen(tok.type)) {
            fstringInterpDepth++
        } else if (tok.type == PythonTokens.FSTR_INTERP_CLOSE || tok.type == PythonTokens.FFS_INTERP_CLOSE) {
            if (fstringInterpDepth > 0) fstringInterpDepth--
        }

        when (tok.type) {
            PythonTokens.LPAREN,
            PythonTokens.LBRACK,
            PythonTokens.LBRACE,
            -> bracketDepth++

            PythonTokens.RPAREN,
            PythonTokens.RBRACK,
            PythonTokens.RBRACE,
            -> if (bracketDepth > 0) bracketDepth--

            PythonTokens.IMPORT,
            PythonTokens.FROM,
            -> if (bracketDepth == 0) inImportStatement = true
        }

        // f-string conversion flags `!r`, `!s`, `!a` inside interpolation
        if (
            fstringInterpDepth > 0 &&
            tok.type == PythonTokens.BANG &&
            next != null &&
            next.type == PythonTokens.NAME &&
            i + 2 < visible.size
        ) {
            val convText = code.substring(next.start, next.end)
            val afterConv = raw[visible[i + 2]].type
            if (
                (convText == "r" || convText == "s" || convText == "a") &&
                (afterConv == PythonTokens.FSTR_FORMAT_COLON || afterConv == PythonTokens.FSTR_INTERP_CLOSE)
            ) {
                tok.kind = SyntaxTokenKind.String
                next.kind = SyntaxTokenKind.String
                i += 2
                continue
            }
        }

        // Decorators (`@dataclass`, `@functools.lru_cache(...)`)
        if (tok.type == PythonTokens.AT && atStatementStart && next?.type == PythonTokens.NAME) {
            tok.kind = SyntaxTokenKind.Annotation
            var j = i + 1
            while (j < visible.size && raw[visible[j]].type == PythonTokens.NAME) {
                raw[visible[j]].kind = SyntaxTokenKind.Annotation
                j++
                if (
                    j + 1 < visible.size &&
                    raw[visible[j]].type == PythonTokens.DOT &&
                    raw[visible[j + 1]].type == PythonTokens.NAME
                ) {
                    raw[visible[j]].kind = SyntaxTokenKind.Annotation
                    j++
                } else {
                    break
                }
            }
            i = j
            continue
        }

        if (tok.type == PythonTokens.NAME) {
            val text = code.substring(tok.start, tok.end)
            if (atStatementStart && isSoftKeywordInStatement(text, raw, visible, precededByNewline, i, code)) {
                tok.kind = SyntaxTokenKind.Keyword
                if (text == "type") {
                    expectingTypeAliasName = true
                }
                i++
                continue
            }

            if (expectingTypeAliasName) {
                tok.kind = SyntaxTokenKind.Type
                expectingTypeAliasName = false
                i++
                continue
            }

            tok.kind = classifyPythonName(
                text = text,
                prev = prev,
                next = next,
                inImportStatement = inImportStatement,
                bracketDepth = bracketDepth,
            )
        }

        i++
    }
}

private fun isSoftKeywordInStatement(
    text: String,
    raw: List<RawPythonToken>,
    visible: List<Int>,
    precededByNewline: List<Boolean>,
    index: Int,
    code: String,
): Boolean {
    val next = if (index + 1 < visible.size) raw[visible[index + 1]] else null
    if (next == null) return false
    if (next.type == PythonTokens.DOT || next.type == PythonTokens.COLON || isAssignmentToken(next.type)) return false

    return when (text) {
        "type" -> {
            if (next.type != PythonTokens.NAME) return false
            val afterName = if (index + 2 < visible.size) raw[visible[index + 2]] else null
            val nextText = code.substring(next.start, next.end)
            nextText != "import" && (afterName?.type == PythonTokens.ASSIGN || afterName?.type == PythonTokens.LBRACK)
        }

        "match",
        "case",
        -> hasStatementColonBeforeLineEnd(raw, visible, precededByNewline, index + 1)

        else -> false
    }
}

private fun hasStatementColonBeforeLineEnd(
    raw: List<RawPythonToken>,
    visible: List<Int>,
    precededByNewline: List<Boolean>,
    startIndex: Int,
): Boolean {
    var depth = 0
    var j = startIndex
    while (j < visible.size) {
        if (depth == 0 && precededByNewline[j]) return false
        val t = raw[visible[j]].type
        if (isFStringInterpOpen(t)) {
            depth++
            j++
            continue
        }
        when (t) {
            PythonTokens.LPAREN,
            PythonTokens.LBRACK,
            PythonTokens.LBRACE,
            -> depth++

            PythonTokens.RPAREN,
            PythonTokens.RBRACK,
            PythonTokens.RBRACE,
            PythonTokens.FSTR_INTERP_CLOSE,
            PythonTokens.FFS_INTERP_CLOSE,
            -> if (depth > 0) depth--

            PythonTokens.COLON -> if (depth == 0) return true
            PythonTokens.SEMI -> if (depth == 0) return false
        }
        j++
    }
    return false
}

private fun classifyPythonName(
    text: String,
    prev: RawPythonToken?,
    next: RawPythonToken?,
    inImportStatement: Boolean,
    bracketDepth: Int,
): SyntaxTokenKind {
    if (inImportStatement) {
        return if (looksLikeTypeName(text)) SyntaxTokenKind.Type else SyntaxTokenKind.Plain
    }

    val prevType = prev?.type
    val nextType = next?.type

    if (prevType == PythonTokens.CLASS) {
        return SyntaxTokenKind.Type
    }
    if (prevType == PythonTokens.DEF && nextType != PythonTokens.DOT) {
        return SyntaxTokenKind.Function
    }

    if (prevType != PythonTokens.DOT) {
        if (text in PYTHON_CONSTANTS) return SyntaxTokenKind.Constant
        if (text == "type" && nextType != null && isAssignmentToken(nextType)) {
            return SyntaxTokenKind.Plain
        }
        if (text in PYTHON_BUILTIN_TYPES) return SyntaxTokenKind.Type
        if (text in PYTHON_BUILTIN_FUNCTIONS) return SyntaxTokenKind.Function
    }

    if (nextType == PythonTokens.LPAREN) {
        return SyntaxTokenKind.Function
    }
    if (prevType == PythonTokens.DOT) {
        return SyntaxTokenKind.Property
    }
    if (
        text.length == 1 &&
        text[0].isUpperCase() &&
        nextType != PythonTokens.ASSIGN &&
        (
            prevType == PythonTokens.COLON ||
                prevType == PythonTokens.ARROW ||
                prevType == PythonTokens.LBRACK ||
                prevType == PythonTokens.PIPE ||
                (bracketDepth > 0 && prevType == PythonTokens.COMMA)
            )
    ) {
        return SyntaxTokenKind.Type
    }
    if (looksLikeTypeName(text)) {
        return SyntaxTokenKind.Type
    }
    return SyntaxTokenKind.Plain
}

private fun isFStringInterpOpen(type: Int): Boolean = when (type) {
    PythonTokens.FD_INTERP_OPEN,
    PythonTokens.FS_INTERP_OPEN,
    PythonTokens.FRD_INTERP_OPEN,
    PythonTokens.FRS_INTERP_OPEN,
    PythonTokens.FTD_INTERP_OPEN,
    PythonTokens.FTS_INTERP_OPEN,
    PythonTokens.FRTD_INTERP_OPEN,
    PythonTokens.FRTS_INTERP_OPEN,
    PythonTokens.FFS_INTERP_OPEN,
    -> true
    else -> false
}

private fun isAssignmentToken(type: Int): Boolean = when (type) {
    PythonTokens.ASSIGN,
    PythonTokens.ADD_ASSIGN,
    PythonTokens.SUB_ASSIGN,
    PythonTokens.MUL_ASSIGN,
    PythonTokens.DIV_ASSIGN,
    PythonTokens.MOD_ASSIGN,
    PythonTokens.POWER_ASSIGN,
    PythonTokens.FLOORDIV_ASSIGN,
    PythonTokens.LSHIFT_ASSIGN,
    PythonTokens.RSHIFT_ASSIGN,
    PythonTokens.AT_ASSIGN,
    PythonTokens.AND_ASSIGN,
    PythonTokens.OR_ASSIGN,
    PythonTokens.XOR_ASSIGN,
    -> true
    else -> false
}

private fun isPythonHardKeyword(type: Int): Boolean = when (type) {
    PythonTokens.AND,
    PythonTokens.AS,
    PythonTokens.ASSERT,
    PythonTokens.ASYNC,
    PythonTokens.AWAIT,
    PythonTokens.BREAK,
    PythonTokens.CLASS,
    PythonTokens.CONTINUE,
    PythonTokens.DEF,
    PythonTokens.DEL,
    PythonTokens.ELIF,
    PythonTokens.ELSE,
    PythonTokens.EXCEPT,
    PythonTokens.FINALLY,
    PythonTokens.FOR,
    PythonTokens.FROM,
    PythonTokens.GLOBAL,
    PythonTokens.IF,
    PythonTokens.IMPORT,
    PythonTokens.IN,
    PythonTokens.IS,
    PythonTokens.LAMBDA,
    PythonTokens.NONLOCAL,
    PythonTokens.NOT,
    PythonTokens.OR,
    PythonTokens.PASS,
    PythonTokens.RAISE,
    PythonTokens.RETURN,
    PythonTokens.TRY,
    PythonTokens.WHILE,
    PythonTokens.WITH,
    PythonTokens.YIELD,
    -> true
    else -> false
}

private fun isPythonOperator(type: Int): Boolean = when (type) {
    PythonTokens.WALRUS,
    PythonTokens.ARROW,
    PythonTokens.POWER_ASSIGN,
    PythonTokens.FLOORDIV_ASSIGN,
    PythonTokens.LSHIFT_ASSIGN,
    PythonTokens.RSHIFT_ASSIGN,
    PythonTokens.POWER,
    PythonTokens.FLOORDIV,
    PythonTokens.LSHIFT,
    PythonTokens.RSHIFT,
    PythonTokens.LE,
    PythonTokens.GE,
    PythonTokens.EQ,
    PythonTokens.NE,
    PythonTokens.ADD_ASSIGN,
    PythonTokens.SUB_ASSIGN,
    PythonTokens.MUL_ASSIGN,
    PythonTokens.DIV_ASSIGN,
    PythonTokens.MOD_ASSIGN,
    PythonTokens.AT_ASSIGN,
    PythonTokens.AND_ASSIGN,
    PythonTokens.OR_ASSIGN,
    PythonTokens.XOR_ASSIGN,
    PythonTokens.AT,
    PythonTokens.ASSIGN,
    PythonTokens.PLUS,
    PythonTokens.MINUS,
    PythonTokens.STAR,
    PythonTokens.SLASH,
    PythonTokens.PERCENT,
    PythonTokens.AMP,
    PythonTokens.PIPE,
    PythonTokens.CARET,
    PythonTokens.TILDE,
    PythonTokens.LT,
    PythonTokens.GT,
    -> true
    else -> false
}

private fun isPythonPunctuation(type: Int): Boolean = when (type) {
    PythonTokens.ELLIPSIS,
    PythonTokens.LPAREN,
    PythonTokens.RPAREN,
    PythonTokens.LBRACK,
    PythonTokens.RBRACK,
    PythonTokens.LBRACE,
    PythonTokens.RBRACE,
    PythonTokens.COLON,
    PythonTokens.COMMA,
    PythonTokens.SEMI,
    PythonTokens.DOT,
    -> true
    else -> false
}

private val PYTHON_CONSTANTS = setOf(
    "Ellipsis",
    "NotImplemented",
)

private val PYTHON_BUILTIN_TYPES = setOf(
    "int",
    "float",
    "complex",
    "str",
    "bytes",
    "bytearray",
    "memoryview",
    "bool",
    "list",
    "tuple",
    "dict",
    "set",
    "frozenset",
    "object",
    "type",
    "BaseException",
    "Exception",
    "ArithmeticError",
    "AssertionError",
    "AttributeError",
    "BufferError",
    "EOFError",
    "FileExistsError",
    "FileNotFoundError",
    "ImportError",
    "IndexError",
    "KeyError",
    "LookupError",
    "MemoryError",
    "ModuleNotFoundError",
    "NameError",
    "NotImplementedError",
    "OSError",
    "OverflowError",
    "PermissionError",
    "RecursionError",
    "RuntimeError",
    "StopAsyncIteration",
    "StopIteration",
    "SyntaxError",
    "SystemExit",
    "TimeoutError",
    "TypeError",
    "ValueError",
    "ZeroDivisionError",
)

private val PYTHON_BUILTIN_FUNCTIONS = setOf(
    "abs",
    "aiter",
    "all",
    "anext",
    "any",
    "ascii",
    "bin",
    "breakpoint",
    "callable",
    "chr",
    "classmethod",
    "compile",
    "delattr",
    "dir",
    "divmod",
    "enumerate",
    "eval",
    "exec",
    "filter",
    "format",
    "getattr",
    "globals",
    "hasattr",
    "hash",
    "help",
    "hex",
    "id",
    "input",
    "isinstance",
    "issubclass",
    "iter",
    "len",
    "locals",
    "map",
    "max",
    "min",
    "next",
    "oct",
    "open",
    "ord",
    "pow",
    "print",
    "property",
    "range",
    "repr",
    "reversed",
    "round",
    "setattr",
    "slice",
    "sorted",
    "staticmethod",
    "sum",
    "super",
    "vars",
    "zip",
    "__import__",
)
