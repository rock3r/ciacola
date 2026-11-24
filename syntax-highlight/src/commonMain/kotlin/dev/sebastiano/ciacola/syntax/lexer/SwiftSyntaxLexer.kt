package dev.sebastiano.ciacola.syntax.lexer

import dev.sebastiano.ciacola.syntax.SyntaxLanguage
import dev.sebastiano.ciacola.syntax.SyntaxLexer
import dev.sebastiano.ciacola.syntax.SyntaxToken
import dev.sebastiano.ciacola.syntax.SyntaxTokenKind
import dev.sebastiano.ciacola.syntax.antlr.swift.SwiftLexer
import dev.sebastiano.ciacola.syntax.antlr.swift.SwiftLexer.Tokens as SwiftTokens
import dev.sebastiano.ciacola.syntax.internal.antlrCharStream
import dev.sebastiano.ciacola.syntax.internal.driveAntlrLexer
import dev.sebastiano.ciacola.syntax.internal.emitDefaultGap
import dev.sebastiano.ciacola.syntax.internal.looksLikeTypeName

/**
 * Lossless ANTLR4 DFA-based lexical tokenizer for Swift 5.9/6.0 source code.
 */
internal object SwiftSyntaxLexer : SyntaxLexer {
    override val language: SyntaxLanguage = SyntaxLanguage.Swift

    override fun lex(source: String): List<SyntaxToken> {
        if (source.isEmpty()) return emptyList()

        val lexer = SwiftLexer(antlrCharStream(source))
        val raw = ArrayList<RawSwiftToken>(source.length / 3 + 8)

        driveAntlrLexer(
            source = source,
            lexer = lexer,
            onGap = { start, end ->
                emitDefaultGap(source, start, end) { kind, s, e ->
                    raw += RawSwiftToken(
                        type = if (kind == SyntaxTokenKind.Whitespace) SwiftTokens.WS else SwiftTokens.ERROR_CHAR,
                        start = s,
                        end = e,
                        kind = kind,
                        isHidden = kind == SyntaxTokenKind.Whitespace,
                        isNewline = false,
                    )
                }
            },
            onToken = { type, start, end -> emitRawSwiftToken(type, start, end, raw) },
        )

        classifySwiftTokens(source, raw)
        return raw.map { SyntaxToken(kind = it.kind, startIndex = it.start, endIndex = it.end) }
    }
}

private class RawSwiftToken(
    val type: Int,
    val start: Int,
    val end: Int,
    var kind: SyntaxTokenKind,
    val isHidden: Boolean,
    val isNewline: Boolean,
)

private fun emitRawSwiftToken(
    type: Int,
    start: Int,
    end: Int,
    out: MutableList<RawSwiftToken>,
) {
    when (type) {
        SwiftTokens.WS ->
            out += RawSwiftToken(type, start, end, SyntaxTokenKind.Whitespace, isHidden = true, isNewline = false)

        SwiftTokens.NEWLINE ->
            out += RawSwiftToken(type, start, end, SyntaxTokenKind.Whitespace, isHidden = true, isNewline = true)

        SwiftTokens.DOC_LINE_COMMENT,
        SwiftTokens.LINE_COMMENT,
        SwiftTokens.BLOCK_COMMENT,
        -> out += RawSwiftToken(type, start, end, SyntaxTokenKind.Comment, isHidden = true, isNewline = false)

        SwiftTokens.POUND_IDENT ->
            out += RawSwiftToken(type, start, end, SyntaxTokenKind.Preprocessor, isHidden = false, isNewline = false)

        SwiftTokens.AT_IDENT ->
            out += RawSwiftToken(type, start, end, SyntaxTokenKind.Annotation, isHidden = false, isNewline = false)

        SwiftTokens.INTERP_OPEN,
        SwiftTokens.INTERP_CLOSE,
        -> out += RawSwiftToken(type, start, end, SyntaxTokenKind.Punctuation, isHidden = false, isNewline = false)

        SwiftTokens.STRING_ESCAPE ->
            out += RawSwiftToken(type, start, end, SyntaxTokenKind.Constant, isHidden = false, isNewline = false)

        SwiftTokens.EXT_MULTILINE_STRING_START,
        SwiftTokens.EXT_STRING_START,
        SwiftTokens.MULTILINE_STRING_START,
        SwiftTokens.STRING_START,
        SwiftTokens.STRING_TEXT,
        SwiftTokens.STRING_CLOSE,
        SwiftTokens.EXTENDED_REGEX_LITERAL,
        SwiftTokens.REGEX_LITERAL,
        -> out += RawSwiftToken(type, start, end, SyntaxTokenKind.String, isHidden = false, isNewline = false)

        SwiftTokens.HEX_FLOAT_LITERAL,
        SwiftTokens.HEX_LITERAL,
        SwiftTokens.OCT_LITERAL,
        SwiftTokens.BIN_LITERAL,
        SwiftTokens.FLOAT_LITERAL,
        SwiftTokens.INT_LITERAL,
        -> out += RawSwiftToken(type, start, end, SyntaxTokenKind.Number, isHidden = false, isNewline = false)

        SwiftTokens.TRUE,
        SwiftTokens.FALSE,
        SwiftTokens.NIL,
        SwiftTokens.DOLLAR_ARG,
        -> out += RawSwiftToken(type, start, end, SyntaxTokenKind.Constant, isHidden = false, isNewline = false)

        SwiftTokens.ANY_UPPER,
        SwiftTokens.SELF_UPPER,
        -> out += RawSwiftToken(type, start, end, SyntaxTokenKind.Type, isHidden = false, isNewline = false)

        SwiftTokens.ERROR_CHAR ->
            out += RawSwiftToken(type, start, end, SyntaxTokenKind.Invalid, isHidden = false, isNewline = false)

        else -> {
            val initialKind = when {
                isSwiftHardKeyword(type) -> SyntaxTokenKind.Keyword
                isSwiftOperator(type) -> SyntaxTokenKind.Operator
                isSwiftPunctuation(type) -> SyntaxTokenKind.Punctuation
                else -> SyntaxTokenKind.Plain
            }
            out += RawSwiftToken(type, start, end, initialKind, isHidden = false, isNewline = false)
        }
    }
}

private fun classifySwiftTokens(code: String, raw: List<RawSwiftToken>) {
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

    var inImport = false
    var inPrecedenceGroup = false
    var precedenceBraceDepth = 0
    var expectingDeclaredTypeName = false
    var angleDepth = 0
    var braceDepth = 0

    var i = 0
    while (i < visible.size) {
        val tok = raw[visible[i]]
        val prev = if (i > 0) raw[visible[i - 1]] else null
        val next = if (i + 1 < visible.size) raw[visible[i + 1]] else null
        val lineStart = precededByNewline[i]

        if (lineStart || tok.type == SwiftTokens.SEMI) {
            inImport = false
            expectingDeclaredTypeName = false
        }

        // Member access after `.` turns most keywords (except `self`) into Property/Type/Function.
        if (prev?.type == SwiftTokens.DOT && tok.type != SwiftTokens.SELF_LOWER) {
            if (tok.type == SwiftTokens.IDENT || tok.type == SwiftTokens.BACKTICK_IDENT) {
                val text = code.substring(tok.start, tok.end)
                tok.kind = when {
                    text == "Type" || text == "Protocol" -> SyntaxTokenKind.Keyword
                    next?.type == SwiftTokens.LPAREN -> SyntaxTokenKind.Function
                    looksLikeTypeName(text) && next?.type == SwiftTokens.DOT -> SyntaxTokenKind.Type
                    else -> SyntaxTokenKind.Property
                }
                i++
                continue
            } else if (isSwiftHardKeyword(tok.type) || tok.type == SwiftTokens.TRUE || tok.type == SwiftTokens.FALSE || tok.type == SwiftTokens.NIL) {
                tok.kind = if (next?.type == SwiftTokens.LPAREN) SyntaxTokenKind.Function else SyntaxTokenKind.Property
                i++
                continue
            }
        }

        when (tok.type) {
            SwiftTokens.IMPORT -> inImport = true

            SwiftTokens.CLASS,
            SwiftTokens.STRUCT,
            SwiftTokens.ENUM,
            SwiftTokens.PROTOCOL,
            SwiftTokens.TYPEALIAS,
            SwiftTokens.ASSOCIATEDTYPE,
            SwiftTokens.EXTENSION,
            -> expectingDeclaredTypeName = true

            SwiftTokens.PRECEDENCEGROUP -> {
                expectingDeclaredTypeName = true
                inPrecedenceGroup = true
                precedenceBraceDepth = braceDepth + 1
            }

            SwiftTokens.LBRACE -> {
                braceDepth++
                angleDepth = 0
            }

            SwiftTokens.RBRACE -> {
                if (inPrecedenceGroup && braceDepth == precedenceBraceDepth) {
                    inPrecedenceGroup = false
                }
                if (braceDepth > 0) braceDepth--
                angleDepth = 0
            }

            SwiftTokens.SEMI -> angleDepth = 0
            SwiftTokens.LT -> if (hasMatchingSwiftAngleClose(raw, visible, i)) angleDepth++
            SwiftTokens.GT -> if (angleDepth > 0) angleDepth--
            SwiftTokens.RSHIFT -> angleDepth = (angleDepth - 2).coerceAtLeast(0)
        }

        if (tok.type == SwiftTokens.BACKTICK_IDENT) {
            val inner = code.substring(tok.start + 1, tok.end - 1)
            tok.kind = when {
                expectingDeclaredTypeName -> {
                    expectingDeclaredTypeName = false
                    SyntaxTokenKind.Type
                }
                prev?.type == SwiftTokens.FUNC -> SyntaxTokenKind.Function
                next?.type == SwiftTokens.LPAREN -> SyntaxTokenKind.Function
                looksLikeTypeName(inner) -> SyntaxTokenKind.Type
                else -> SyntaxTokenKind.Plain
            }
            i++
            continue
        }

        if (tok.type == SwiftTokens.DOLLAR_IDENT) {
            tok.kind = if (prev?.type == SwiftTokens.DOT) SyntaxTokenKind.Property else SyntaxTokenKind.Plain
            i++
            continue
        }

        if (tok.type == SwiftTokens.IDENT) {
            val text = code.substring(tok.start, tok.end)
            val nextOnNewline = i + 1 < visible.size && precededByNewline[i + 1]

            if (expectingDeclaredTypeName) {
                tok.kind = SyntaxTokenKind.Type
                expectingDeclaredTypeName = false
                i++
                continue
            }

            val contextualKind = classifySwiftContextualKeyword(
                text = text,
                code = code,
                raw = raw,
                visible = visible,
                index = i,
                prev = prev,
                next = next,
                lineStart = lineStart,
                nextOnNewline = nextOnNewline,
                inPrecedenceGroup = inPrecedenceGroup && braceDepth == precedenceBraceDepth,
                angleDepth = angleDepth,
            )
            if (contextualKind != null) {
                tok.kind = contextualKind
                if (text == "actor") {
                    expectingDeclaredTypeName = true
                }
                i++
                continue
            }

            tok.kind = classifySwiftIdentifier(
                text = text,
                code = code,
                raw = raw,
                visible = visible,
                index = i,
                prev = prev,
                next = next,
                inImport = inImport,
                angleDepth = angleDepth,
            )
        }

        i++
    }
}

private fun classifySwiftContextualKeyword(
    text: String,
    code: String,
    raw: List<RawSwiftToken>,
    visible: List<Int>,
    index: Int,
    prev: RawSwiftToken?,
    next: RawSwiftToken?,
    lineStart: Boolean,
    nextOnNewline: Boolean,
    inPrecedenceGroup: Boolean,
    angleDepth: Int,
): SyntaxTokenKind? {
    val prevType = prev?.type
    val nextType = next?.type
    val prevText = if (prevType == SwiftTokens.IDENT) code.substring(prev.start, prev.end) else null
    val nextText = if (nextType == SwiftTokens.IDENT) code.substring(next.start, next.end) else null

    return when (text) {
        "actor" -> {
            if (isSwiftDeclStart(prev, prevText, lineStart) && nextType == SwiftTokens.IDENT && !nextOnNewline) {
                SyntaxTokenKind.Keyword
            } else {
                null
            }
        }

        "macro" -> {
            if (isSwiftDeclStart(prev, prevText, lineStart) && nextType == SwiftTokens.IDENT && !nextOnNewline) {
                SyntaxTokenKind.Keyword
            } else {
                null
            }
        }

        "package" -> {
            if (isSwiftDeclStart(prev, prevText, lineStart) && next != null && !nextOnNewline &&
                (isSwiftDeclOrModifierStart(next, nextText) || nextType == SwiftTokens.LPAREN)
            ) {
                SyntaxTokenKind.Keyword
            } else {
                null
            }
        }

        "async" -> {
            val validAfter = prevType == SwiftTokens.RPAREN ||
                prevType == SwiftTokens.ARROW ||
                prevType == SwiftTokens.COLON ||
                prevType == SwiftTokens.LET ||
                prevType == SwiftTokens.VAR ||
                prevType == SwiftTokens.TRY ||
                prevType == SwiftTokens.ASSIGN ||
                isSwiftDeclStart(prev, prevText, lineStart)
            val validBefore = nextType == SwiftTokens.THROWS ||
                nextType == SwiftTokens.RETHROWS ||
                nextType == SwiftTokens.ARROW ||
                nextType == SwiftTokens.LBRACE ||
                nextType == SwiftTokens.LET ||
                nextType == SwiftTokens.VAR ||
                nextType == SwiftTokens.FUNC ||
                nextType == SwiftTokens.INIT ||
                nextType == SwiftTokens.RPAREN ||
                nextType == SwiftTokens.COMMA ||
                (nextType == SwiftTokens.IDENT && (nextText == "get" || nextText == "throws"))
            if ((validAfter || validBefore) && nextType != SwiftTokens.ASSIGN && nextType != SwiftTokens.COLON) {
                SyntaxTokenKind.Keyword
            } else {
                null
            }
        }

        "await" -> {
            if (prevType != SwiftTokens.FUNC && prevType != SwiftTokens.LET && prevType != SwiftTokens.VAR &&
                next != null && !isSwiftValueBoundary(nextType)
            ) {
                SyntaxTokenKind.Keyword
            } else {
                null
            }
        }

        "some",
        "any",
        -> {
            if (prevType != SwiftTokens.LET && prevType != SwiftTokens.VAR && prevType != SwiftTokens.FUNC &&
                next != null && !nextOnNewline &&
                (nextType == SwiftTokens.IDENT || nextType == SwiftTokens.ANY_UPPER || nextType == SwiftTokens.SELF_UPPER || nextType == SwiftTokens.LPAREN)
            ) {
                SyntaxTokenKind.Keyword
            } else {
                null
            }
        }

        "nonisolated",
        "isolated",
        "sending",
        "borrowing",
        "consuming",
        -> {
            val validNext = next != null && !nextOnNewline && !isSwiftValueBoundary(nextType) &&
                (text == "nonisolated" || nextType != SwiftTokens.LPAREN || nextText != null)
            val validSetMod = text == "nonisolated" && nextType == SwiftTokens.LPAREN && nextText == "unsafe"
            if ((validNext || validSetMod) && prevType != SwiftTokens.LET && prevType != SwiftTokens.VAR) {
                SyntaxTokenKind.Keyword
            } else {
                null
            }
        }

        "consume",
        "copy",
        -> {
            if (next != null && !nextOnNewline && !isSwiftValueBoundary(nextType) &&
                nextType != SwiftTokens.LPAREN && prevType != SwiftTokens.LET && prevType != SwiftTokens.VAR
            ) {
                SyntaxTokenKind.Keyword
            } else {
                null
            }
        }

        "each" -> {
            if ((prevType == SwiftTokens.REPEAT || prevType == SwiftTokens.COLON || prevType == SwiftTokens.COMMA ||
                    prevType == SwiftTokens.LT || prevType == SwiftTokens.LPAREN || prevType == SwiftTokens.IN || prevType == SwiftTokens.FOR) &&
                nextType == SwiftTokens.IDENT && !nextOnNewline
            ) {
                SyntaxTokenKind.Keyword
            } else {
                null
            }
        }

        "convenience",
        "dynamic",
        "final",
        "indirect",
        "lazy",
        "mutating",
        "nonmutating",
        "optional",
        "override",
        "required",
        "unowned",
        "weak",
        "distributed",
        -> {
            val isUnownedQual = text == "unowned" && nextType == SwiftTokens.LPAREN
            if (isSwiftDeclStart(prev, prevText, lineStart) && next != null && !nextOnNewline &&
                (isSwiftDeclOrModifierStart(next, nextText) || isUnownedQual ||
                    (text == "unowned" || text == "weak") && nextType == SwiftTokens.VAR)
            ) {
                SyntaxTokenKind.Keyword
            } else {
                null
            }
        }

        "get",
        "set",
        "willSet",
        "didSet",
        -> {
            if (text == "set" && prevType == SwiftTokens.LPAREN && nextType == SwiftTokens.RPAREN) {
                val beforeParen = if (index >= 2) raw[visible[index - 2]].type else null
                if (beforeParen in SWIFT_ACCESS_CONTROL_TOKENS || (beforeParen == SwiftTokens.IDENT &&
                        code.substring(raw[visible[index - 2]].start, raw[visible[index - 2]].end) == "package")
                ) {
                    return SyntaxTokenKind.Keyword
                }
            }
            val accessorPrev = lineStart || prev == null || prevType == SwiftTokens.LBRACE ||
                prevType == SwiftTokens.RBRACE || prevType == SwiftTokens.SEMI ||
                prevText in SWIFT_ACCESSOR_MODIFIERS
            val accessorNext = nextType == SwiftTokens.LBRACE ||
                (nextType == SwiftTokens.LPAREN && (text == "willSet" || text == "didSet" || text == "set")) ||
                (nextType == SwiftTokens.IDENT && (nextText == "async" || nextText == "throws")) ||
                nextType == SwiftTokens.THROWS
            if (accessorPrev && accessorNext) SyntaxTokenKind.Keyword else null
        }

        "infix",
        "prefix",
        "postfix",
        -> {
            if (nextType == SwiftTokens.OPERATOR || nextType == SwiftTokens.FUNC ||
                (nextType == SwiftTokens.IDENT && nextText in SWIFT_DECL_MODIFIERS)
            ) {
                SyntaxTokenKind.Keyword
            } else {
                null
            }
        }

        "associativity",
        "higherThan",
        "lowerThan",
        "assignment",
        -> if (inPrecedenceGroup && nextType == SwiftTokens.COLON) SyntaxTokenKind.Keyword else null

        "left",
        "right",
        "none",
        -> if (inPrecedenceGroup && prevType == SwiftTokens.COLON) SyntaxTokenKind.Keyword else null

        "yield" -> if (next != null && !nextOnNewline && !isSwiftValueBoundary(nextType)) SyntaxTokenKind.Keyword else null

        else -> {
            if (angleDepth > 0 && text.length == 1 && text[0].isUpperCase()) {
                SyntaxTokenKind.Type
            } else {
                null
            }
        }
    }
}

private fun classifySwiftIdentifier(
    text: String,
    code: String,
    raw: List<RawSwiftToken>,
    visible: List<Int>,
    index: Int,
    prev: RawSwiftToken?,
    next: RawSwiftToken?,
    inImport: Boolean,
    angleDepth: Int,
): SyntaxTokenKind {
    if (inImport) {
        return if (looksLikeTypeName(text)) SyntaxTokenKind.Type else SyntaxTokenKind.Plain
    }

    val prevType = prev?.type
    val nextType = next?.type

    if (prevType == SwiftTokens.FUNC && nextType != SwiftTokens.DOT) {
        return SyntaxTokenKind.Function
    }

    if (prevType == SwiftTokens.IDENT && code.substring(prev.start, prev.end) == "macro") {
        return SyntaxTokenKind.Function
    }

    if (text in SWIFT_BUILTIN_TYPES) {
        return SyntaxTokenKind.Type
    }

    if (nextType == SwiftTokens.LPAREN &&
        prevType != SwiftTokens.COLON &&
        prevType != SwiftTokens.ARROW &&
        prevType != SwiftTokens.IS &&
        prevType != SwiftTokens.AS
    ) {
        return SyntaxTokenKind.Function
    }

    if (nextType == SwiftTokens.LT && hasMatchingSwiftAngleClose(raw, visible, index + 1)) {
        val afterClose = findAfterMatchingSwiftAngle(raw, visible, index + 1)
        if (afterClose?.type == SwiftTokens.LPAREN && prevType != SwiftTokens.COLON && prevType != SwiftTokens.ARROW) {
            return SyntaxTokenKind.Function
        }
        if (looksLikeTypeName(text)) {
            return SyntaxTokenKind.Type
        }
    }

    if (text.length == 1 && text[0].isUpperCase() &&
        (angleDepth > 0 || prevType == SwiftTokens.LT || prevType == SwiftTokens.COLON || prevType == SwiftTokens.ARROW || prevType == SwiftTokens.COMMA)
    ) {
        return SyntaxTokenKind.Type
    }

    if (looksLikeTypeName(text)) {
        return SyntaxTokenKind.Type
    }

    return SyntaxTokenKind.Plain
}

private fun hasMatchingSwiftAngleClose(
    raw: List<RawSwiftToken>,
    visible: List<Int>,
    openIndex: Int,
): Boolean {
    var depth = 1
    var nestedParens = 0
    val limit = minOf(visible.size, openIndex + 48)
    for (j in openIndex + 1 until limit) {
        when (raw[visible[j]].type) {
            SwiftTokens.LPAREN, SwiftTokens.LBRACK -> nestedParens++
            SwiftTokens.RPAREN, SwiftTokens.RBRACK -> {
                if (nestedParens == 0) return false
                nestedParens--
            }
            SwiftTokens.SEMI, SwiftTokens.LBRACE, SwiftTokens.RBRACE -> return false
            SwiftTokens.LT -> if (nestedParens == 0) depth++
            SwiftTokens.GT -> if (nestedParens == 0) {
                depth--
                if (depth == 0) return true
            }
            SwiftTokens.RSHIFT -> if (nestedParens == 0) {
                depth -= 2
                if (depth <= 0) return true
            }
        }
    }
    return false
}

private fun findAfterMatchingSwiftAngle(
    raw: List<RawSwiftToken>,
    visible: List<Int>,
    openIndex: Int,
): RawSwiftToken? {
    var depth = 1
    var nestedParens = 0
    val limit = minOf(visible.size, openIndex + 48)
    for (j in openIndex + 1 until limit) {
        when (raw[visible[j]].type) {
            SwiftTokens.LPAREN, SwiftTokens.LBRACK -> nestedParens++
            SwiftTokens.RPAREN, SwiftTokens.RBRACK -> {
                if (nestedParens == 0) return null
                nestedParens--
            }
            SwiftTokens.SEMI, SwiftTokens.LBRACE, SwiftTokens.RBRACE -> return null
            SwiftTokens.LT -> if (nestedParens == 0) depth++
            SwiftTokens.GT -> if (nestedParens == 0) {
                depth--
                if (depth == 0) {
                    return if (j + 1 < visible.size) raw[visible[j + 1]] else null
                }
            }
            SwiftTokens.RSHIFT -> if (nestedParens == 0) {
                depth -= 2
                if (depth <= 0) {
                    return if (j + 1 < visible.size) raw[visible[j + 1]] else null
                }
            }
        }
    }
    return null
}

private fun isSwiftDeclStart(prev: RawSwiftToken?, prevText: String?, lineStart: Boolean): Boolean =
    lineStart ||
        prev == null ||
        prev.type == SwiftTokens.LBRACE ||
        prev.type == SwiftTokens.RBRACE ||
        prev.type == SwiftTokens.SEMI ||
        prev.type == SwiftTokens.AT_IDENT ||
        prev.type in SWIFT_DECL_PREFIX_TOKENS ||
        prevText in SWIFT_DECL_MODIFIERS

private fun isSwiftDeclOrModifierStart(tok: RawSwiftToken, text: String?): Boolean =
    tok.type in SWIFT_DECL_TOKENS || (tok.type == SwiftTokens.IDENT && text in SWIFT_DECL_MODIFIERS)

private fun isSwiftValueBoundary(type: Int?): Boolean = when (type) {
    SwiftTokens.ASSIGN,
    SwiftTokens.COMMA,
    SwiftTokens.COLON,
    SwiftTokens.SEMI,
    SwiftTokens.RPAREN,
    SwiftTokens.RBRACK,
    SwiftTokens.RBRACE,
    SwiftTokens.DOT,
    SwiftTokens.ARROW,
    -> true
    else -> false
}

private fun isSwiftHardKeyword(type: Int): Boolean = when (type) {
    SwiftTokens.ASSOCIATEDTYPE,
    SwiftTokens.CLASS,
    SwiftTokens.DEINIT,
    SwiftTokens.ENUM,
    SwiftTokens.EXTENSION,
    SwiftTokens.FILEPRIVATE,
    SwiftTokens.FUNC,
    SwiftTokens.IMPORT,
    SwiftTokens.INIT,
    SwiftTokens.INOUT,
    SwiftTokens.INTERNAL,
    SwiftTokens.LET,
    SwiftTokens.OPEN,
    SwiftTokens.OPERATOR,
    SwiftTokens.PRIVATE,
    SwiftTokens.PRECEDENCEGROUP,
    SwiftTokens.PROTOCOL,
    SwiftTokens.PUBLIC,
    SwiftTokens.RETHROWS,
    SwiftTokens.STATIC,
    SwiftTokens.STRUCT,
    SwiftTokens.SUBSCRIPT,
    SwiftTokens.TYPEALIAS,
    SwiftTokens.VAR,
    SwiftTokens.BREAK,
    SwiftTokens.CASE,
    SwiftTokens.CATCH,
    SwiftTokens.CONTINUE,
    SwiftTokens.DEFAULT,
    SwiftTokens.DEFER,
    SwiftTokens.DO,
    SwiftTokens.ELSE,
    SwiftTokens.FALLTHROUGH,
    SwiftTokens.FOR,
    SwiftTokens.GUARD,
    SwiftTokens.IF,
    SwiftTokens.IN,
    SwiftTokens.REPEAT,
    SwiftTokens.RETURN,
    SwiftTokens.THROW,
    SwiftTokens.SWITCH,
    SwiftTokens.WHERE,
    SwiftTokens.WHILE,
    SwiftTokens.AS,
    SwiftTokens.IS,
    SwiftTokens.SELF_LOWER,
    SwiftTokens.SUPER,
    SwiftTokens.THROWS,
    SwiftTokens.TRY,
    -> true
    else -> false
}

private fun isSwiftOperator(type: Int): Boolean = when (type) {
    SwiftTokens.ELLIPSIS,
    SwiftTokens.HALF_OPEN_RANGE,
    SwiftTokens.ARROW,
    SwiftTokens.OVERFLOW_LSHIFT_ASSIGN,
    SwiftTokens.OVERFLOW_RSHIFT_ASSIGN,
    SwiftTokens.LSHIFT_ASSIGN,
    SwiftTokens.RSHIFT_ASSIGN,
    SwiftTokens.OVERFLOW_LSHIFT,
    SwiftTokens.OVERFLOW_RSHIFT,
    SwiftTokens.TRIPLE_EQ,
    SwiftTokens.TRIPLE_NE,
    SwiftTokens.LSHIFT,
    SwiftTokens.RSHIFT,
    SwiftTokens.NIL_COALESCE,
    SwiftTokens.AND,
    SwiftTokens.OR,
    SwiftTokens.EQ,
    SwiftTokens.NE,
    SwiftTokens.LE,
    SwiftTokens.GE,
    SwiftTokens.PLUS_ASSIGN,
    SwiftTokens.MINUS_ASSIGN,
    SwiftTokens.STAR_ASSIGN,
    SwiftTokens.SLASH_ASSIGN,
    SwiftTokens.PERCENT_ASSIGN,
    SwiftTokens.AMP_ASSIGN,
    SwiftTokens.PIPE_ASSIGN,
    SwiftTokens.CARET_ASSIGN,
    SwiftTokens.TILDE_EQ,
    SwiftTokens.OVERFLOW_ADD,
    SwiftTokens.OVERFLOW_SUB,
    SwiftTokens.OVERFLOW_MUL,
    SwiftTokens.PIPE_FORWARD,
    SwiftTokens.PIPE_BACKWARD,
    SwiftTokens.BIDIR_ARROW,
    SwiftTokens.POWER,
    SwiftTokens.ASSIGN,
    SwiftTokens.LT,
    SwiftTokens.GT,
    SwiftTokens.BANG,
    SwiftTokens.PLUS,
    SwiftTokens.MINUS,
    SwiftTokens.STAR,
    SwiftTokens.SLASH,
    SwiftTokens.PERCENT,
    SwiftTokens.AMP,
    SwiftTokens.PIPE,
    SwiftTokens.CARET,
    SwiftTokens.TILDE,
    SwiftTokens.BACKSLASH,
    -> true
    else -> false
}

private fun isSwiftPunctuation(type: Int): Boolean = when (type) {
    SwiftTokens.LPAREN,
    SwiftTokens.RPAREN,
    SwiftTokens.LBRACE,
    SwiftTokens.RBRACE,
    SwiftTokens.LBRACK,
    SwiftTokens.RBRACK,
    SwiftTokens.SEMI,
    SwiftTokens.COMMA,
    SwiftTokens.DOT,
    SwiftTokens.COLON,
    SwiftTokens.QUESTION,
    SwiftTokens.HASH,
    SwiftTokens.AT,
    -> true
    else -> false
}

private val SWIFT_ACCESS_CONTROL_TOKENS = setOf(
    SwiftTokens.PRIVATE,
    SwiftTokens.FILEPRIVATE,
    SwiftTokens.INTERNAL,
    SwiftTokens.PUBLIC,
    SwiftTokens.OPEN,
)

private val SWIFT_DECL_PREFIX_TOKENS = setOf(
    SwiftTokens.PRIVATE,
    SwiftTokens.FILEPRIVATE,
    SwiftTokens.INTERNAL,
    SwiftTokens.PUBLIC,
    SwiftTokens.OPEN,
    SwiftTokens.STATIC,
    SwiftTokens.CLASS,
    SwiftTokens.RPAREN,
)

private val SWIFT_DECL_TOKENS = setOf(
    SwiftTokens.CLASS,
    SwiftTokens.STRUCT,
    SwiftTokens.ENUM,
    SwiftTokens.PROTOCOL,
    SwiftTokens.EXTENSION,
    SwiftTokens.FUNC,
    SwiftTokens.INIT,
    SwiftTokens.DEINIT,
    SwiftTokens.SUBSCRIPT,
    SwiftTokens.VAR,
    SwiftTokens.LET,
    SwiftTokens.TYPEALIAS,
    SwiftTokens.ASSOCIATEDTYPE,
    SwiftTokens.OPERATOR,
    SwiftTokens.PRECEDENCEGROUP,
    SwiftTokens.IMPORT,
    SwiftTokens.PRIVATE,
    SwiftTokens.FILEPRIVATE,
    SwiftTokens.INTERNAL,
    SwiftTokens.PUBLIC,
    SwiftTokens.OPEN,
    SwiftTokens.STATIC,
)

private val SWIFT_DECL_MODIFIERS = setOf(
    "actor",
    "macro",
    "package",
    "convenience",
    "dynamic",
    "final",
    "indirect",
    "lazy",
    "mutating",
    "nonmutating",
    "optional",
    "override",
    "required",
    "unowned",
    "weak",
    "nonisolated",
    "distributed",
    "infix",
    "prefix",
    "postfix",
)

private val SWIFT_ACCESSOR_MODIFIERS = setOf(
    "mutating",
    "nonmutating",
    "nonisolated",
    "consuming",
)

private val SWIFT_BUILTIN_TYPES = setOf(
    "Int", "Int8", "Int16", "Int32", "Int64",
    "UInt", "UInt8", "UInt16", "UInt32", "UInt64",
    "Float", "Float16", "Float80", "Double",
    "Bool", "String", "Substring", "Character",
    "Void", "Never", "AnyObject", "Optional",
    "Array", "Dictionary", "Set", "Result",
    "Error", "Sendable", "Equatable", "Hashable", "Comparable",
    "Codable", "Encodable", "Decodable", "Identifiable",
    "Sequence", "Collection", "RandomAccessCollection",
    "Duration", "Date", "Data", "URL", "UUID",
    "UnsafePointer", "UnsafeMutablePointer", "UnsafeRawPointer",
    "UnsafeBufferPointer", "ContinuousClock",
)
