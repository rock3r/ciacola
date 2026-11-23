package dev.sebastiano.ciacola.syntax.lexer

import dev.sebastiano.ciacola.syntax.SyntaxToken
import dev.sebastiano.ciacola.syntax.SyntaxTokenKind
import dev.sebastiano.ciacola.syntax.antlr.javascript.JavaScriptLexer
import dev.sebastiano.ciacola.syntax.antlr.javascript.JavaScriptLexer.Tokens as JsTokens
import dev.sebastiano.ciacola.syntax.internal.antlrCharStream
import dev.sebastiano.ciacola.syntax.internal.driveAntlrLexer
import dev.sebastiano.ciacola.syntax.internal.emitDefaultGap
import dev.sebastiano.ciacola.syntax.internal.looksLikeTypeName

internal fun lexJsOrTs(source: String, isTypeScript: Boolean): List<SyntaxToken> {
    if (source.isEmpty()) return emptyList()

    val lexer = JavaScriptLexer(antlrCharStream(source)).apply {
        this.isTypeScript = isTypeScript
    }
    val raw = ArrayList<RawJsToken>(source.length / 3 + 8)

    driveAntlrLexer(
        source = source,
        lexer = lexer,
        onGap = { start, end ->
            emitDefaultGap(source, start, end) { kind, s, e ->
                raw += RawJsToken(
                    type = if (kind == SyntaxTokenKind.Whitespace) JsTokens.WS else JsTokens.ERROR_CHAR,
                    start = s,
                    end = e,
                    isHidden = kind == SyntaxTokenKind.Whitespace,
                    isNewline = false,
                )
            }
        },
        onToken = { type, start, end ->
            val hidden = isHiddenJsToken(type)
            val hasNewline = hidden && (type == JsTokens.WS || type == JsTokens.JSX_TAG_WS || type == JsTokens.JSX_CHILDREN_WS || type == JsTokens.JSX_CLOSING_WS) &&
                containsNewline(source, start, end)
            raw += RawJsToken(
                type = type,
                start = start,
                end = end,
                isHidden = hidden,
                isNewline = hasNewline,
            )
        },
    )

    return classifyJsTokens(source, raw, isTypeScript)
}

private class RawJsToken(
    val type: Int,
    val start: Int,
    val end: Int,
    val isHidden: Boolean,
    val isNewline: Boolean,
)

private fun containsNewline(source: String, start: Int, end: Int): Boolean {
    for (i in start until end) {
        val c = source[i]
        if (c == '\n' || c == '\r') return true
    }
    return false
}

private fun isHiddenJsToken(type: Int): Boolean = when (type) {
    JsTokens.WS,
    JsTokens.LINE_COMMENT,
    JsTokens.BLOCK_COMMENT,
    JsTokens.UNTERMINATED_BLOCK_COMMENT,
    JsTokens.JSX_TAG_WS,
    JsTokens.JSX_TAG_LINE_COMMENT,
    JsTokens.JSX_TAG_BLOCK_COMMENT,
    JsTokens.JSX_TAG_UNTERMINATED_BLOCK_COMMENT,
    JsTokens.JSX_TYPE_WS,
    JsTokens.JSX_CLOSING_WS,
    JsTokens.JSX_CHILDREN_WS,
    -> true
    else -> false
}

private fun classifyJsTokens(
    source: String,
    raw: List<RawJsToken>,
    isTypeScript: Boolean,
): List<SyntaxToken> {
    val visible = ArrayList<Int>(raw.size)
    val precededByNewline = ArrayList<Boolean>(raw.size)
    var sawNewline = true
    for (idx in raw.indices) {
        val tok = raw[idx]
        if (tok.isHidden) {
            if (tok.isNewline) sawNewline = true
        } else {
            visible += idx
            precededByNewline += sawNewline
            sawNewline = false
        }
    }

    val kinds = Array(raw.size) { idx -> defaultJsKind(raw[idx].type) }

    var expectingJsxTagName = false
    var inImportOrExportHeader = false
    var angleDepth = 0
    var forParenDepth = 0
    val parenIsForStack = ArrayList<Boolean>()
    val containerStack = ArrayList<Int>()
    var pendingEnumBlock = false
    var enumBraceDepth = -1

    var i = 0
    while (i < visible.size) {
        val rawIdx = visible[i]
        val tok = raw[rawIdx]
        val prev = if (i > 0) raw[visible[i - 1]] else null
        val prevText = if (prev != null && prev.type == JsTokens.IDENT) source.substring(prev.start, prev.end) else null
        val next = if (i + 1 < visible.size) raw[visible[i + 1]] else null
        val nextText = if (next != null && next.type == JsTokens.IDENT) source.substring(next.start, next.end) else null

        if (precededByNewline[i] && prev?.type != JsTokens.COMMA && prev?.type != JsTokens.LBRACE) {
            // Reset single-line import/export state when moving onto a new statement line unless braced/comma-continued.
            if (tok.type == JsTokens.IDENT) {
                val t = source.substring(tok.start, tok.end)
                if (t != "from" && t != "as") {
                    inImportOrExportHeader = false
                }
            }
        }
        if (tok.type == JsTokens.SEMI) {
            inImportOrExportHeader = false
            angleDepth = 0
        }

        when (tok.type) {
            JsTokens.LPAREN -> {
                val isFor = prevText == "for"
                parenIsForStack.add(isFor)
                if (isFor) forParenDepth++
                containerStack.add(JsTokens.LPAREN)
            }
            JsTokens.RPAREN -> {
                if (parenIsForStack.isNotEmpty() && parenIsForStack.removeAt(parenIsForStack.size - 1)) {
                    if (forParenDepth > 0) forParenDepth--
                }
                if (containerStack.isNotEmpty()) containerStack.removeAt(containerStack.size - 1)
            }
            JsTokens.LBRACK -> containerStack.add(JsTokens.LBRACK)
            JsTokens.RBRACK -> if (containerStack.isNotEmpty()) containerStack.removeAt(containerStack.size - 1)
            JsTokens.LBRACE,
            JsTokens.TEMPLATE_EXPR_START,
            JsTokens.JSX_TAG_EXPR_START,
            JsTokens.JSX_CHILDREN_EXPR_START,
            -> {
                angleDepth = 0
                containerStack.add(JsTokens.LBRACE)
                if (pendingEnumBlock) {
                    enumBraceDepth = containerStack.size
                    pendingEnumBlock = false
                }
            }
            JsTokens.RBRACE,
            JsTokens.TEMPLATE_EXPR_END,
            -> {
                angleDepth = 0
                if (containerStack.size == enumBraceDepth) {
                    enumBraceDepth = -1
                }
                if (containerStack.isNotEmpty()) containerStack.removeAt(containerStack.size - 1)
            }
            JsTokens.LT -> if (isTypeScript && isGenericOpenContext(prev, prevText) && hasMatchingClosingAngle(raw, visible, i)) angleDepth++
            JsTokens.GT -> if (angleDepth > 0) angleDepth--
            JsTokens.RSHIFT -> if (angleDepth >= 2) angleDepth -= 2 else angleDepth = 0
            JsTokens.URSHIFT -> if (angleDepth >= 3) angleDepth -= 3 else angleDepth = 0
        }

        // Decorators: @Component or @Foo.Bar
        if (tok.type == JsTokens.AT && next?.type == JsTokens.IDENT) {
            kinds[rawIdx] = SyntaxTokenKind.Annotation
            var j = i + 1
            while (j < visible.size && raw[visible[j]].type == JsTokens.IDENT) {
                kinds[visible[j]] = SyntaxTokenKind.Annotation
                j++
                if (j + 1 < visible.size &&
                    raw[visible[j]].type == JsTokens.DOT &&
                    raw[visible[j + 1]].type == JsTokens.IDENT
                ) {
                    kinds[visible[j]] = SyntaxTokenKind.Annotation
                    j++
                } else {
                    break
                }
            }
            i = j
            continue
        }

        // JSX tag open / name / attributes
        when (tok.type) {
            JsTokens.JSX_TAG_OPEN,
            JsTokens.JSX_CHILD_TAG_OPEN,
            -> {
                expectingJsxTagName = true
                i++
                continue
            }
            JsTokens.JSX_TAG_SELF_CLOSE,
            JsTokens.JSX_TAG_CLOSE,
            JsTokens.JSX_TAG_EXPR_START,
            JsTokens.JSX_TAG_TYPE_OPEN,
            -> {
                expectingJsxTagName = false
                i++
                continue
            }
            JsTokens.JSX_TYPE_IDENT -> {
                val text = source.substring(tok.start, tok.end)
                kinds[rawIdx] = classifyJsxTypeIdent(text, next?.type)
                i++
                continue
            }
            JsTokens.JSX_TAG_NAME -> {
                if (expectingJsxTagName) {
                    kinds[rawIdx] = SyntaxTokenKind.Tag
                    expectingJsxTagName = next?.type == JsTokens.JSX_TAG_DOT || next?.type == JsTokens.JSX_TAG_COLON
                } else {
                    kinds[rawIdx] = SyntaxTokenKind.Attribute
                }
                i++
                continue
            }
            JsTokens.JSX_CLOSING_NAME -> {
                kinds[rawIdx] = SyntaxTokenKind.Tag
                i++
                continue
            }
            JsTokens.PRIVATE_IDENT -> {
                kinds[rawIdx] = SyntaxTokenKind.Property
                i++
                continue
            }
        }

        if (tok.type == JsTokens.IDENT) {
            val text = source.substring(tok.start, tok.end)
            if (text == "import") {
                if (prev?.type != JsTokens.DOT &&
                    prev?.type != JsTokens.OPT_CHAIN &&
                    next?.type != JsTokens.DOT &&
                    next?.type != JsTokens.LPAREN
                ) {
                    inImportOrExportHeader = true
                }
            } else if (text == "export") {
                if (prev?.type != JsTokens.DOT && prev?.type != JsTokens.OPT_CHAIN) {
                    inImportOrExportHeader = next?.type == JsTokens.LBRACE || next?.type == JsTokens.STAR || nextText == "type"
                }
            } else if (text in JS_DECLARATION_RESET_KEYWORDS) {
                inImportOrExportHeader = false
            }
            if (isTypeScript && text == "enum" && prev?.type != JsTokens.DOT && prev?.type != JsTokens.OPT_CHAIN) {
                pendingEnumBlock = true
            }
            val enclosingIsBrace = containerStack.lastOrNull() == JsTokens.LBRACE
            val inEnumBody = enumBraceDepth > 0 && containerStack.size == enumBraceDepth
            kinds[rawIdx] = classifyJsIdentifier(
                text = text,
                prev = prev,
                prevText = prevText,
                next = next,
                nextText = nextText,
                isTypeScript = isTypeScript,
                inImportOrExportHeader = inImportOrExportHeader,
                inForParen = forParenDepth > 0,
                angleDepth = angleDepth,
                enclosingIsBrace = enclosingIsBrace,
                inEnumBody = inEnumBody,
            )
        }

        i++
    }

    return raw.mapIndexed { idx, tok ->
        SyntaxToken(kind = kinds[idx], startIndex = tok.start, endIndex = tok.end)
    }
}

private fun hasMatchingClosingAngle(
    raw: List<RawJsToken>,
    visible: List<Int>,
    ltVisibleIndex: Int,
): Boolean {
    var subAngle = 1
    var paren = 0
    var brace = 0
    var bracket = 0
    val maxLookahead = (ltVisibleIndex + 128).coerceAtMost(visible.size)
    for (idx in (ltVisibleIndex + 1) until maxLookahead) {
        when (raw[visible[idx]].type) {
            JsTokens.LPAREN -> paren++
            JsTokens.RPAREN -> if (--paren < 0) return false
            JsTokens.LBRACE -> brace++
            JsTokens.RBRACE -> if (--brace < 0) return false
            JsTokens.LBRACK -> bracket++
            JsTokens.RBRACK -> if (--bracket < 0) return false
            JsTokens.SEMI -> return false
            JsTokens.LOGICAL_AND,
            JsTokens.LOGICAL_OR,
            JsTokens.NULLISH,
            JsTokens.EQ,
            JsTokens.NEQ,
            JsTokens.STRICT_EQ,
            JsTokens.STRICT_NEQ,
            -> if (paren == 0 && brace == 0 && bracket == 0) return false
            JsTokens.LT -> if (paren == 0 && brace == 0 && bracket == 0) subAngle++
            JsTokens.GT -> if (paren == 0 && brace == 0 && bracket == 0) {
                if (--subAngle <= 0) return true
            }
            JsTokens.RSHIFT -> if (paren == 0 && brace == 0 && bracket == 0) {
                subAngle -= 2
                if (subAngle <= 0) return true
            }
            JsTokens.URSHIFT -> if (paren == 0 && brace == 0 && bracket == 0) {
                subAngle -= 3
                if (subAngle <= 0) return true
            }
        }
    }
    return false
}

private fun classifyJsxTypeIdent(text: String, nextType: Int?): SyntaxTokenKind = when {
    text in JS_CONSTANTS -> SyntaxTokenKind.Constant
    text in TS_PRIMITIVE_TYPES || text == "void" -> SyntaxTokenKind.Type
    text in TS_BUILTIN_TYPES -> SyntaxTokenKind.Type
    text == "readonly" || text == "keyof" || text == "typeof" || text == "extends" || text == "infer" || text == "is" || text == "as" -> SyntaxTokenKind.Keyword
    nextType == JsTokens.JSX_TYPE_OP && text != "true" && text != "false" -> SyntaxTokenKind.Plain
    text[0].isUpperCase() -> SyntaxTokenKind.Type
    else -> SyntaxTokenKind.Plain
}

private fun isGenericOpenContext(prev: RawJsToken?, prevText: String?): Boolean {
    if (prev == null) return false
    return when (prev.type) {
        JsTokens.IDENT -> prevText !in JS_EXPRESSION_PREFIX_KEYWORDS
        JsTokens.RPAREN, JsTokens.RBRACK -> true
        JsTokens.COMMA, JsTokens.COLON, JsTokens.ASSIGN, JsTokens.LT -> true
        else -> false
    }
}

private fun classifyJsIdentifier(
    text: String,
    prev: RawJsToken?,
    prevText: String?,
    next: RawJsToken?,
    nextText: String?,
    isTypeScript: Boolean,
    inImportOrExportHeader: Boolean,
    inForParen: Boolean,
    angleDepth: Int,
    enclosingIsBrace: Boolean,
    inEnumBody: Boolean,
): SyntaxTokenKind {
    val prevType = prev?.type
    val nextType = next?.type

    // Member access after `.` or `?.`: property name unless invoked as a method `foo.bar()`.
    if (prevType == JsTokens.DOT || prevType == JsTokens.OPT_CHAIN) {
        return if (nextType == JsTokens.LPAREN) SyntaxTokenKind.Function else SyntaxTokenKind.Property
    }

    val isObjectKey = enclosingIsBrace && nextType == JsTokens.COLON && (prevType == JsTokens.LBRACE || prevType == JsTokens.COMMA)

    if (text in JS_CONSTANTS) {
        if (isObjectKey) return SyntaxTokenKind.Property
        return SyntaxTokenKind.Constant
    }

    if (text in JS_HARD_KEYWORDS) {
        if (isObjectKey && text != "default") return SyntaxTokenKind.Property
        if (isTypeScript && text == "void" && isTypeContext(prevType, prevText, angleDepth)) {
            return SyntaxTokenKind.Type
        }
        return SyntaxTokenKind.Keyword
    }

    // Contextual JS keywords
    when (text) {
        "from" -> if (inImportOrExportHeader && (nextType == JsTokens.STRING || nextType == JsTokens.UNTERMINATED_STRING)) {
            return SyntaxTokenKind.Keyword
        }
        "of" -> if (inForParen && prevType != null && prevType != JsTokens.LPAREN && prevType != JsTokens.SEMI) {
            return SyntaxTokenKind.Keyword
        }
        "as" -> if (isTypeScript || inImportOrExportHeader) {
            if (!isObjectKey) return SyntaxTokenKind.Keyword
        }
        "get", "set" -> if (nextType == JsTokens.IDENT || nextType == JsTokens.LBRACK || nextType == JsTokens.PRIVATE_IDENT || nextType == JsTokens.STAR) {
            return SyntaxTokenKind.Keyword
        }
        "using" -> if (nextType == JsTokens.IDENT && !isObjectKey) {
            return SyntaxTokenKind.Keyword
        }
    }

    if (isTypeScript) {
        when (text) {
            in TS_UNCONDITIONAL_KEYWORDS -> {
                if (isObjectKey) return SyntaxTokenKind.Property
                return SyntaxTokenKind.Keyword
            }
            "type" -> {
                val isTypeDecl = inImportOrExportHeader ||
                    (nextType == JsTokens.IDENT && nextText != "from") ||
                    nextType == JsTokens.LBRACE
                if (isTypeDecl && !isObjectKey) {
                    return SyntaxTokenKind.Keyword
                }
            }
            "is" -> {
                if (prevType == JsTokens.IDENT && nextType == JsTokens.IDENT) {
                    return SyntaxTokenKind.Keyword
                }
            }
            "module" -> {
                if (nextType == JsTokens.IDENT || nextType == JsTokens.STRING) {
                    return SyntaxTokenKind.Keyword
                }
            }
            "out" -> {
                if (angleDepth > 0 && nextType == JsTokens.IDENT) {
                    return SyntaxTokenKind.Keyword
                }
            }
            "accessor" -> {
                if (nextType == JsTokens.IDENT || nextType == JsTokens.PRIVATE_IDENT || nextType == JsTokens.LBRACK) {
                    return SyntaxTokenKind.Keyword
                }
            }
            "unique" -> {
                if (nextText == "symbol") {
                    return SyntaxTokenKind.Keyword
                }
            }
            in TS_PRIMITIVE_TYPES -> {
                if (!isObjectKey) {
                    return SyntaxTokenKind.Type
                }
            }
        }
    }

    // Declaration names
    if (prevText == "function") {
        return SyntaxTokenKind.Function
    }
    if ((prevText == "const" || prevText == "let" || prevText == "var") && angleDepth == 0) {
        return SyntaxTokenKind.Plain
    }
    if (prevText == "class" || prevText == "extends" ||
        (isTypeScript && (prevText == "interface" || prevText == "type" || prevText == "enum" || prevText == "implements"))
    ) {
        return SyntaxTokenKind.Type
    }

    // Function call / method definition
    if (nextType == JsTokens.LPAREN) {
        return SyntaxTokenKind.Function
    }

    // Object literal property key `{ key: val, other: val2 }`
    if (isObjectKey) {
        return SyntaxTokenKind.Property
    }

    if (inImportOrExportHeader || inEnumBody) {
        return SyntaxTokenKind.Plain
    }

    if (isTypeScript) {
        if (text in TS_BUILTIN_TYPES) {
            return SyntaxTokenKind.Type
        }
        if (text[0].isUpperCase() &&
            (isTypeContext(prevType, prevText, angleDepth) ||
                (text.length == 1 && (angleDepth > 0 || nextType == JsTokens.GT || nextText == "extends" || nextText == "in")))
        ) {
            return SyntaxTokenKind.Type
        }
    }

    if (looksLikeTypeName(text)) {
        return SyntaxTokenKind.Type
    }

    return SyntaxTokenKind.Plain
}

private fun isTypeContext(prevType: Int?, prevText: String?, angleDepth: Int): Boolean {
    if (angleDepth > 0) return true
    return prevType == JsTokens.COLON ||
        prevType == JsTokens.BIT_OR ||
        prevType == JsTokens.BIT_AND ||
        prevType == JsTokens.ARROW ||
        prevText == "as" ||
        prevText == "satisfies" ||
        prevText == "is" ||
        prevText == "extends" ||
        prevText == "implements"
}

private fun defaultJsKind(type: Int): SyntaxTokenKind = when (type) {
    JsTokens.WS,
    JsTokens.JSX_TAG_WS,
    JsTokens.JSX_CHILDREN_WS,
    JsTokens.JSX_CLOSING_WS,
    -> SyntaxTokenKind.Whitespace

    JsTokens.SHEBANG,
    JsTokens.LINE_COMMENT,
    JsTokens.BLOCK_COMMENT,
    JsTokens.UNTERMINATED_BLOCK_COMMENT,
    JsTokens.JSX_TAG_LINE_COMMENT,
    JsTokens.JSX_TAG_BLOCK_COMMENT,
    JsTokens.JSX_TAG_UNTERMINATED_BLOCK_COMMENT,
    -> SyntaxTokenKind.Comment

    JsTokens.STRING,
    JsTokens.UNTERMINATED_STRING,
    JsTokens.REGEX_LITERAL,
    JsTokens.TEMPLATE_START,
    JsTokens.TEMPLATE_CLOSE,
    JsTokens.TEMPLATE_EXPR_START,
    JsTokens.TEMPLATE_EXPR_END,
    JsTokens.TEMPLATE_ESCAPE,
    JsTokens.TEMPLATE_TEXT,
    JsTokens.TEMPLATE_LONE_DOLLAR,
    JsTokens.JSX_TAG_STRING,
    JsTokens.JSX_TAG_UNTERMINATED_STRING,
    JsTokens.JSX_TYPE_STRING,
    -> SyntaxTokenKind.String

    JsTokens.BIGINT_LITERAL,
    JsTokens.HEX_LITERAL,
    JsTokens.BIN_LITERAL,
    JsTokens.OCT_LITERAL,
    JsTokens.DECIMAL_LITERAL,
    JsTokens.JSX_TYPE_NUMBER,
    -> SyntaxTokenKind.Number

    JsTokens.ARROW,
    JsTokens.JSX_TYPE_ARROW,
    -> SyntaxTokenKind.Keyword

    JsTokens.LPAREN,
    JsTokens.RPAREN,
    JsTokens.LBRACK,
    JsTokens.RBRACK,
    JsTokens.LBRACE,
    JsTokens.RBRACE,
    JsTokens.DOT,
    JsTokens.OPT_CHAIN,
    JsTokens.COMMA,
    JsTokens.SEMI,
    JsTokens.JSX_FRAG_OPEN,
    JsTokens.JSX_FRAG_CLOSE,
    JsTokens.JSX_TAG_OPEN,
    JsTokens.JSX_TAG_SELF_CLOSE,
    JsTokens.JSX_TAG_CLOSE,
    JsTokens.JSX_TAG_EXPR_START,
    JsTokens.JSX_TAG_TYPE_OPEN,
    JsTokens.JSX_TAG_DOT,
    JsTokens.JSX_TAG_COLON,
    JsTokens.JSX_TYPE_PUNCT,
    JsTokens.JSX_CLOSING_OPEN,
    JsTokens.JSX_CLOSING_END,
    JsTokens.JSX_CLOSING_DOT,
    JsTokens.JSX_CLOSING_COLON,
    JsTokens.JSX_CHILD_FRAG_OPEN,
    JsTokens.JSX_CHILD_TAG_OPEN,
    JsTokens.JSX_CHILDREN_EXPR_START,
    JsTokens.JSX_CHILDREN_RBRACE,
    -> SyntaxTokenKind.Punctuation

    JsTokens.ELLIPSIS,
    JsTokens.STRICT_EQ,
    JsTokens.STRICT_NEQ,
    JsTokens.EQ,
    JsTokens.NEQ,
    JsTokens.LE,
    JsTokens.GE,
    JsTokens.URSHIFT_ASSIGN,
    JsTokens.RSHIFT_ASSIGN,
    JsTokens.LSHIFT_ASSIGN,
    JsTokens.URSHIFT,
    JsTokens.RSHIFT,
    JsTokens.LSHIFT,
    JsTokens.LT,
    JsTokens.GT,
    JsTokens.STAR_STAR_ASSIGN,
    JsTokens.STAR_STAR,
    JsTokens.LOGICAL_AND_ASSIGN,
    JsTokens.LOGICAL_OR_ASSIGN,
    JsTokens.NULLISH_ASSIGN,
    JsTokens.LOGICAL_AND,
    JsTokens.LOGICAL_OR,
    JsTokens.NULLISH,
    JsTokens.PLUS_ASSIGN,
    JsTokens.MINUS_ASSIGN,
    JsTokens.STAR_ASSIGN,
    JsTokens.SLASH_ASSIGN,
    JsTokens.PERCENT_ASSIGN,
    JsTokens.AND_ASSIGN,
    JsTokens.OR_ASSIGN,
    JsTokens.XOR_ASSIGN,
    JsTokens.INC,
    JsTokens.DEC,
    JsTokens.ASSIGN,
    JsTokens.PLUS,
    JsTokens.MINUS,
    JsTokens.STAR,
    JsTokens.SLASH,
    JsTokens.PERCENT,
    JsTokens.BANG,
    JsTokens.TILDE,
    JsTokens.BIT_AND,
    JsTokens.BIT_OR,
    JsTokens.BIT_XOR,
    JsTokens.QUESTION,
    JsTokens.COLON,
    JsTokens.JSX_TAG_EQ,
    JsTokens.JSX_TYPE_OPEN,
    JsTokens.JSX_TYPE_CLOSE,
    JsTokens.JSX_TYPE_OP,
    -> SyntaxTokenKind.Operator

    JsTokens.AT -> SyntaxTokenKind.Annotation

    JsTokens.JSX_TEXT,
    JsTokens.JSX_CHILDREN_LT,
    -> SyntaxTokenKind.Plain

    JsTokens.ERROR_CHAR,
    JsTokens.JSX_TAG_ERROR,
    JsTokens.JSX_TYPE_ERROR,
    JsTokens.JSX_CLOSING_ERROR,
    -> SyntaxTokenKind.Invalid

    else -> SyntaxTokenKind.Plain
}

private val JS_CONSTANTS = setOf(
    "true",
    "false",
    "null",
    "undefined",
    "NaN",
    "Infinity",
)

private val JS_HARD_KEYWORDS = setOf(
    "async",
    "await",
    "break",
    "case",
    "catch",
    "class",
    "const",
    "continue",
    "debugger",
    "default",
    "delete",
    "do",
    "else",
    "export",
    "extends",
    "finally",
    "for",
    "function",
    "if",
    "import",
    "in",
    "instanceof",
    "let",
    "new",
    "return",
    "static",
    "super",
    "switch",
    "this",
    "throw",
    "try",
    "typeof",
    "var",
    "void",
    "while",
    "with",
    "yield",
)

private val JS_EXPRESSION_PREFIX_KEYWORDS = setOf(
    "return",
    "throw",
    "case",
    "else",
    "do",
    "typeof",
    "void",
    "delete",
    "await",
    "yield",
    "in",
    "of",
    "instanceof",
)

private val JS_DECLARATION_RESET_KEYWORDS = setOf(
    "const",
    "let",
    "var",
    "function",
    "class",
    "interface",
    "enum",
    "abstract",
    "async",
    "declare",
    "default",
)

private val TS_UNCONDITIONAL_KEYWORDS = setOf(
    "interface",
    "enum",
    "namespace",
    "declare",
    "abstract",
    "implements",
    "readonly",
    "override",
    "keyof",
    "infer",
    "satisfies",
    "asserts",
    "private",
    "protected",
    "public",
)

private val TS_PRIMITIVE_TYPES = setOf(
    "string",
    "number",
    "boolean",
    "unknown",
    "never",
    "any",
    "bigint",
    "symbol",
    "object",
)

private val TS_BUILTIN_TYPES = setOf(
    "Promise",
    "Record",
    "Partial",
    "Required",
    "Readonly",
    "Pick",
    "Omit",
    "Exclude",
    "Extract",
    "NonNullable",
    "Parameters",
    "ReturnType",
    "ConstructorParameters",
    "InstanceType",
    "Awaited",
    "Array",
    "ReadonlyArray",
    "Map",
    "Set",
    "WeakMap",
    "WeakSet",
    "Error",
    "RegExp",
    "Date",
    "Function",
    "Uppercase",
    "Lowercase",
    "Capitalize",
    "Uncapitalize",
    "ThisType",
    "Disposable",
    "AsyncDisposable",
)
