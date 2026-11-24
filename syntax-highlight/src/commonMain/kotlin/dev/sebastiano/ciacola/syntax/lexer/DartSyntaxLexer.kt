package dev.sebastiano.ciacola.syntax.lexer

import dev.sebastiano.ciacola.syntax.SyntaxLanguage
import dev.sebastiano.ciacola.syntax.SyntaxLexer
import dev.sebastiano.ciacola.syntax.SyntaxToken
import dev.sebastiano.ciacola.syntax.SyntaxTokenKind
import dev.sebastiano.ciacola.syntax.antlr.dart.DartLexer
import dev.sebastiano.ciacola.syntax.antlr.dart.DartLexer.Tokens as DartTokens
import dev.sebastiano.ciacola.syntax.internal.antlrCharStream
import dev.sebastiano.ciacola.syntax.internal.driveAntlrLexer
import dev.sebastiano.ciacola.syntax.internal.emitDefaultGap
import dev.sebastiano.ciacola.syntax.internal.looksLikeTypeName

/**
 * Lossless ANTLR4 DFA-based lexical tokenizer for Dart 3.x source code.
 */
internal object DartSyntaxLexer : SyntaxLexer {
    override val language: SyntaxLanguage = SyntaxLanguage.Dart

    override fun lex(source: String): List<SyntaxToken> {
        if (source.isEmpty()) return emptyList()

        val lexer = DartLexer(antlrCharStream(source))
        val raw = ArrayList<RawDartToken>(source.length / 3 + 8)

        driveAntlrLexer(
            source = source,
            lexer = lexer,
            onGap = { start, end ->
                emitDefaultGap(source, start, end) { kind, s, e ->
                    raw += RawDartToken(
                        type = if (kind == SyntaxTokenKind.Whitespace) DartTokens.WS else DartTokens.ERROR_CHAR,
                        start = s,
                        end = e,
                        kind = kind,
                        isHidden = kind == SyntaxTokenKind.Whitespace,
                        isNewline = false,
                    )
                }
            },
            onToken = { type, start, end -> emitRawDartToken(type, start, end, raw) },
        )

        classifyDartTokens(source, raw)
        return raw.map { SyntaxToken(kind = it.kind, startIndex = it.start, endIndex = it.end) }
    }
}

private class RawDartToken(
    val type: Int,
    val start: Int,
    val end: Int,
    var kind: SyntaxTokenKind,
    val isHidden: Boolean,
    val isNewline: Boolean,
)

private fun emitRawDartToken(
    type: Int,
    start: Int,
    end: Int,
    out: MutableList<RawDartToken>,
) {
    when (type) {
        DartTokens.WS ->
            out += RawDartToken(type, start, end, SyntaxTokenKind.Whitespace, isHidden = true, isNewline = false)

        DartTokens.NEWLINE ->
            out += RawDartToken(type, start, end, SyntaxTokenKind.Whitespace, isHidden = true, isNewline = true)

        DartTokens.DOC_LINE_COMMENT,
        DartTokens.LINE_COMMENT,
        DartTokens.BLOCK_COMMENT,
        -> out += RawDartToken(type, start, end, SyntaxTokenKind.Comment, isHidden = true, isNewline = false)

        DartTokens.AT_IDENT ->
            out += RawDartToken(type, start, end, SyntaxTokenKind.Annotation, isHidden = false, isNewline = false)

        DartTokens.INTERP_OPEN,
        DartTokens.INTERP_CLOSE,
        -> out += RawDartToken(type, start, end, SyntaxTokenKind.Punctuation, isHidden = false, isNewline = false)

        DartTokens.INTERP_IDENT -> {
            out += RawDartToken(type, start, start + 1, SyntaxTokenKind.Punctuation, isHidden = false, isNewline = false)
            if (start + 1 < end) {
                out += RawDartToken(DartTokens.IDENT, start + 1, end, SyntaxTokenKind.Plain, isHidden = false, isNewline = false)
            }
        }

        DartTokens.STRING_ESCAPE ->
            out += RawDartToken(type, start, end, SyntaxTokenKind.Constant, isHidden = false, isNewline = false)

        DartTokens.RAW_TRIPLE_SQ_STRING,
        DartTokens.RAW_TRIPLE_DQ_STRING,
        DartTokens.RAW_SQ_STRING,
        DartTokens.RAW_DQ_STRING,
        DartTokens.TRIPLE_SQ_STRING_START,
        DartTokens.TRIPLE_DQ_STRING_START,
        DartTokens.SQ_STRING_START,
        DartTokens.DQ_STRING_START,
        DartTokens.STRING_TEXT,
        DartTokens.STRING_CLOSE,
        -> out += RawDartToken(type, start, end, SyntaxTokenKind.String, isHidden = false, isNewline = false)

        DartTokens.HEX_LITERAL,
        DartTokens.FLOAT_LITERAL,
        DartTokens.INT_LITERAL,
        -> out += RawDartToken(type, start, end, SyntaxTokenKind.Number, isHidden = false, isNewline = false)

        DartTokens.TRUE,
        DartTokens.FALSE,
        DartTokens.NULL,
        -> out += RawDartToken(type, start, end, SyntaxTokenKind.Constant, isHidden = false, isNewline = false)

        DartTokens.VOID ->
            out += RawDartToken(type, start, end, SyntaxTokenKind.Type, isHidden = false, isNewline = false)

        DartTokens.ERROR_CHAR ->
            out += RawDartToken(type, start, end, SyntaxTokenKind.Invalid, isHidden = false, isNewline = false)

        else -> {
            val initialKind = when {
                isDartHardKeyword(type) -> SyntaxTokenKind.Keyword
                isDartOperator(type) -> SyntaxTokenKind.Operator
                isDartPunctuation(type) -> SyntaxTokenKind.Punctuation
                else -> SyntaxTokenKind.Plain
            }
            out += RawDartToken(type, start, end, initialKind, isHidden = false, isNewline = false)
        }
    }
}

private fun classifyDartTokens(code: String, raw: List<RawDartToken>) {
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

    var inDirective = false
    var inImportOrExport = false
    var expectingDeclaredTypeName = false
    var inSwitchOrCaseExpr = false
    var switchCaseDepth = 0
    var angleDepth = 0
    var braceDepth = 0
    var parenDepth = 0
    var bracketDepth = 0

    var i = 0
    while (i < visible.size) {
        val tok = raw[visible[i]]
        val prev = if (i > 0) raw[visible[i - 1]] else null
        val next = if (i + 1 < visible.size) raw[visible[i + 1]] else null
        val lineStart = precededByNewline[i]
        val totalDepth = braceDepth + parenDepth + bracketDepth

        if (tok.type == DartTokens.SEMI) {
            inDirective = false
            inImportOrExport = false
            expectingDeclaredTypeName = false
            inSwitchOrCaseExpr = false
            angleDepth = 0
        }

        if (inSwitchOrCaseExpr && totalDepth < switchCaseDepth) {
            inSwitchOrCaseExpr = false
        }

        // Qualified annotation `@prefix.Annotation`
        if (prev?.type == DartTokens.DOT &&
            i >= 2 &&
            raw[visible[i - 2]].type == DartTokens.AT_IDENT &&
            tok.type == DartTokens.IDENT
        ) {
            tok.kind = SyntaxTokenKind.Annotation
            i++
            continue
        }

        // Member access after `.`, `..`, `?.`, `?..`
        if (isDartMemberDot(prev?.type) && tok.type == DartTokens.IDENT) {
            val text = code.substring(tok.start, tok.end)
            tok.kind = when {
                next?.type == DartTokens.LPAREN ||
                    (next?.type == DartTokens.LT && hasMatchingDartAngleClose(raw, visible, i + 1)) -> SyntaxTokenKind.Function
                looksLikeTypeName(text) && isDartMemberDot(next?.type) -> SyntaxTokenKind.Type
                else -> SyntaxTokenKind.Property
            }
            i++
            continue
        }

        when (tok.type) {
            DartTokens.CLASS,
            DartTokens.ENUM,
            -> expectingDeclaredTypeName = true

            DartTokens.CASE -> {
                inSwitchOrCaseExpr = true
                switchCaseDepth = totalDepth
            }

            DartTokens.COLON,
            DartTokens.FAT_ARROW,
            -> {
                if (inSwitchOrCaseExpr && totalDepth == switchCaseDepth) {
                    inSwitchOrCaseExpr = false
                }
            }

            DartTokens.LPAREN -> parenDepth++
            DartTokens.RPAREN -> if (parenDepth > 0) parenDepth--
            DartTokens.LBRACK -> bracketDepth++
            DartTokens.RBRACK -> if (bracketDepth > 0) bracketDepth--
            DartTokens.LBRACE -> {
                braceDepth++
                angleDepth = 0
                if (inSwitchOrCaseExpr && totalDepth == switchCaseDepth) {
                    inSwitchOrCaseExpr = false
                }
            }
            DartTokens.RBRACE -> {
                if (braceDepth > 0) braceDepth--
                angleDepth = 0
            }
            DartTokens.LT -> if (hasMatchingDartAngleClose(raw, visible, i)) angleDepth++
            DartTokens.GT -> if (angleDepth > 0) angleDepth--
            DartTokens.RSHIFT -> angleDepth = (angleDepth - 2).coerceAtLeast(0)
            DartTokens.URSHIFT -> angleDepth = (angleDepth - 3).coerceAtLeast(0)
        }

        if (tok.type == DartTokens.IDENT) {
            val text = code.substring(tok.start, tok.end)
            val nextOnNewline = i + 1 < visible.size && precededByNewline[i + 1]

            if (expectingDeclaredTypeName) {
                val isExtensionModifier = text == "on" && prev?.type == DartTokens.IDENT &&
                    code.substring(prev.start, prev.end) == "extension"
                if (!isExtensionModifier) {
                    tok.kind = SyntaxTokenKind.Type
                    expectingDeclaredTypeName = false
                    i++
                    continue
                }
                expectingDeclaredTypeName = false
            }

            if ((text == "import" || text == "export" || text == "library" || text == "part") &&
                isDartStatementStart(prev, lineStart)
            ) {
                inDirective = true
                if (text == "import" || text == "export") {
                    inImportOrExport = true
                }
                tok.kind = SyntaxTokenKind.Keyword
                i++
                continue
            }

            val contextualKind = classifyDartContextualKeyword(
                text = text,
                code = code,
                raw = raw,
                visible = visible,
                index = i,
                prev = prev,
                next = next,
                lineStart = lineStart,
                nextOnNewline = nextOnNewline,
                inDirective = inDirective,
                inImportOrExport = inImportOrExport,
                inSwitchOrCaseExpr = inSwitchOrCaseExpr || isIfCaseContext(raw, visible, i),
            )
            if (contextualKind != null) {
                tok.kind = contextualKind
                if (text == "mixin" || text == "extension" || text == "typedef") {
                    val nextText = if (next?.type == DartTokens.IDENT) code.substring(next.start, next.end) else null
                    if (next?.type == DartTokens.IDENT && nextText != "type" && nextText != "on") {
                        expectingDeclaredTypeName = true
                    }
                } else if (text == "type" && prev?.type == DartTokens.IDENT &&
                    code.substring(prev.start, prev.end) == "extension"
                ) {
                    expectingDeclaredTypeName = true
                }
                i++
                continue
            }

            tok.kind = classifyDartIdentifier(
                text = text,
                code = code,
                raw = raw,
                visible = visible,
                index = i,
                prev = prev,
                next = next,
                inDirective = inDirective,
                angleDepth = angleDepth,
            )
        }

        i++
    }
}

private fun classifyDartContextualKeyword(
    text: String,
    code: String,
    raw: List<RawDartToken>,
    visible: List<Int>,
    index: Int,
    prev: RawDartToken?,
    next: RawDartToken?,
    lineStart: Boolean,
    nextOnNewline: Boolean,
    inDirective: Boolean,
    inImportOrExport: Boolean,
    inSwitchOrCaseExpr: Boolean,
): SyntaxTokenKind? {
    val prevType = prev?.type
    val nextType = next?.type
    val prevText = if (prevType == DartTokens.IDENT) code.substring(prev.start, prev.end) else null
    val nextText = if (nextType == DartTokens.IDENT) code.substring(next.start, next.end) else null

    return when (text) {
        "dynamic",
        "Function",
        -> {
            if (nextType == DartTokens.ASSIGN || nextType == DartTokens.SEMI ||
                (nextType == DartTokens.COMMA && prevType != DartTokens.LT)
            ) {
                null
            } else {
                SyntaxTokenKind.Type
            }
        }

        "abstract",
        "base",
        "interface",
        "sealed",
        -> {
            val validPrev = isDartStatementStart(prev, lineStart) || prevText in DART_CLASS_MODIFIERS || prevType == DartTokens.FINAL
            val validNext = nextType == DartTokens.CLASS || nextType == DartTokens.FINAL ||
                (nextType == DartTokens.IDENT && nextText in DART_CLASS_MODIFIERS)
            if (validPrev && validNext) SyntaxTokenKind.Keyword else null
        }

        "mixin" -> {
            val validPrev = isDartStatementStart(prev, lineStart) || prevText in DART_CLASS_MODIFIERS
            val validNext = nextType == DartTokens.CLASS || (nextType == DartTokens.IDENT && !nextOnNewline)
            if (validPrev && validNext) SyntaxTokenKind.Keyword else null
        }

        "extension" -> {
            if (isDartStatementStart(prev, lineStart) && nextType == DartTokens.IDENT && !nextOnNewline) {
                SyntaxTokenKind.Keyword
            } else {
                null
            }
        }

        "type" -> {
            if (prevText == "extension" && nextType == DartTokens.IDENT && !nextOnNewline) {
                SyntaxTokenKind.Keyword
            } else {
                null
            }
        }

        "typedef",
        "implements",
        -> if (nextType == DartTokens.IDENT && !nextOnNewline) SyntaxTokenKind.Keyword else null

        "factory",
        "external",
        "covariant",
        "late",
        "required",
        "static",
        -> {
            if (next != null && !nextOnNewline && !isDartValueBoundary(nextType) && nextType != DartTokens.LPAREN) {
                SyntaxTokenKind.Keyword
            } else {
                null
            }
        }

        "operator" -> {
            if (next != null && !nextOnNewline && isDartOverloadableOperator(nextType, next)) {
                SyntaxTokenKind.Keyword
            } else {
                null
            }
        }

        "get" -> {
            if (nextType == DartTokens.IDENT && index + 2 < visible.size &&
                (raw[visible[index + 2]].type == DartTokens.FAT_ARROW ||
                    raw[visible[index + 2]].type == DartTokens.LBRACE ||
                    raw[visible[index + 2]].type == DartTokens.SEMI ||
                    raw[visible[index + 2]].type == DartTokens.IDENT)
            ) {
                SyntaxTokenKind.Keyword
            } else {
                null
            }
        }

        "set" -> {
            if (nextType == DartTokens.IDENT && index + 2 < visible.size &&
                raw[visible[index + 2]].type == DartTokens.LPAREN
            ) {
                SyntaxTokenKind.Keyword
            } else {
                null
            }
        }

        "async",
        "sync",
        -> {
            val validPrev = prevType == DartTokens.RPAREN || prevType == DartTokens.RBRACK
            val validNext = nextType == DartTokens.STAR || nextType == DartTokens.LBRACE || nextType == DartTokens.FAT_ARROW
            if (validPrev && validNext) SyntaxTokenKind.Keyword else null
        }

        "await" -> {
            if (nextType == DartTokens.FOR || (next != null && !nextOnNewline && !isDartValueBoundary(nextType) &&
                    prevType != DartTokens.VOID && prevType != DartTokens.VAR && prevType != DartTokens.FINAL && prevType != DartTokens.CONST)
            ) {
                SyntaxTokenKind.Keyword
            } else {
                null
            }
        }

        "yield" -> {
            if (isDartStatementStart(prev, lineStart) && next != null && !isDartValueBoundary(nextType)) {
                SyntaxTokenKind.Keyword
            } else {
                null
            }
        }

        "as" -> {
            if (inImportOrExport && nextType == DartTokens.IDENT) {
                SyntaxTokenKind.Keyword
            } else if (prev != null && next != null && !nextOnNewline &&
                (nextType == DartTokens.IDENT || nextType == DartTokens.VOID || nextType == DartTokens.LPAREN) &&
                !isDartValueBoundary(nextType)
            ) {
                SyntaxTokenKind.Keyword
            } else {
                null
            }
        }

        "show",
        "hide",
        "deferred",
        -> if (inImportOrExport) SyntaxTokenKind.Keyword else null

        "of" -> if (inDirective && prevText == "part") SyntaxTokenKind.Keyword else null

        "on" -> {
            if (next != null && !nextOnNewline &&
                (prevType == DartTokens.RBRACE || prevText == "extension" || prevText == "on" || isDartOnCatchOrClause(raw, visible, index))
            ) {
                SyntaxTokenKind.Keyword
            } else {
                null
            }
        }

        "when" -> if (inSwitchOrCaseExpr) SyntaxTokenKind.Keyword else null

        else -> null
    }
}

private fun classifyDartIdentifier(
    text: String,
    code: String,
    raw: List<RawDartToken>,
    visible: List<Int>,
    index: Int,
    prev: RawDartToken?,
    next: RawDartToken?,
    inDirective: Boolean,
    angleDepth: Int,
): SyntaxTokenKind {
    if (inDirective) {
        return if (looksLikeTypeName(text)) SyntaxTokenKind.Type else SyntaxTokenKind.Plain
    }

    val prevType = prev?.type
    val nextType = next?.type

    if (prevType == DartTokens.IDENT &&
        (code.substring(prev.start, prev.end) == "get" || code.substring(prev.start, prev.end) == "set") &&
        prev.kind == SyntaxTokenKind.Keyword
    ) {
        return SyntaxTokenKind.Function
    }

    if (text in DART_BUILTIN_TYPES) {
        return SyntaxTokenKind.Type
    }

    if (nextType == DartTokens.LPAREN) {
        if (prevType == DartTokens.NEW || prevType == DartTokens.CONST) {
            return if (looksLikeTypeName(text)) SyntaxTokenKind.Type else SyntaxTokenKind.Function
        }
        return SyntaxTokenKind.Function
    }

    if (nextType == DartTokens.LT && hasMatchingDartAngleClose(raw, visible, index + 1)) {
        val afterClose = findAfterMatchingDartAngle(raw, visible, index + 1)
        if (afterClose?.type == DartTokens.LPAREN && prevType != DartTokens.NEW && prevType != DartTokens.CONST) {
            return SyntaxTokenKind.Function
        }
        if (looksLikeTypeName(text)) {
            return SyntaxTokenKind.Type
        }
    }

    if (text.length == 1 && text[0].isUpperCase() &&
        (angleDepth > 0 || prevType == DartTokens.LT || prevType == DartTokens.COMMA || prevType == DartTokens.EXTENDS)
    ) {
        return SyntaxTokenKind.Type
    }

    if (looksLikeTypeName(text)) {
        return SyntaxTokenKind.Type
    }

    return SyntaxTokenKind.Plain
}

private fun isIfCaseContext(raw: List<RawDartToken>, visible: List<Int>, whenIndex: Int): Boolean {
    var j = whenIndex - 1
    var paren = 0
    val floor = (whenIndex - 32).coerceAtLeast(0)
    while (j >= floor) {
        when (raw[visible[j]].type) {
            DartTokens.RPAREN -> paren++
            DartTokens.LPAREN -> if (paren > 0) paren-- else return false
            DartTokens.CASE -> if (paren == 0) return true
            DartTokens.SEMI, DartTokens.LBRACE, DartTokens.RBRACE -> return false
        }
        j--
    }
    return false
}

private fun isDartOnCatchOrClause(raw: List<RawDartToken>, visible: List<Int>, onIndex: Int): Boolean {
    val prev = if (onIndex > 0) raw[visible[onIndex - 1]] else return false
    if (prev.type == DartTokens.RBRACE || prev.type == DartTokens.SEMI) return true
    var j = onIndex - 1
    val floor = (onIndex - 16).coerceAtLeast(0)
    while (j >= floor) {
        val t = raw[visible[j]].type
        if (t == DartTokens.SEMI || t == DartTokens.LBRACE) return false
        if (t == DartTokens.RBRACE) return true
        if (t == DartTokens.IDENT && raw[visible[j]].kind == SyntaxTokenKind.Keyword) {
            return true
        }
        j--
    }
    return false
}

private fun hasMatchingDartAngleClose(
    raw: List<RawDartToken>,
    visible: List<Int>,
    openIndex: Int,
): Boolean {
    var depth = 1
    var nestedParens = 0
    val limit = minOf(visible.size, openIndex + 48)
    for (j in openIndex + 1 until limit) {
        when (raw[visible[j]].type) {
            DartTokens.LPAREN, DartTokens.LBRACK -> nestedParens++
            DartTokens.RPAREN, DartTokens.RBRACK -> {
                if (nestedParens == 0) return false
                nestedParens--
            }
            DartTokens.SEMI, DartTokens.LBRACE, DartTokens.RBRACE, DartTokens.ASSIGN -> return false
            DartTokens.LT -> if (nestedParens == 0) depth++
            DartTokens.GT -> if (nestedParens == 0) {
                depth--
                if (depth == 0) return true
            }
            DartTokens.RSHIFT -> if (nestedParens == 0) {
                depth -= 2
                if (depth <= 0) return true
            }
            DartTokens.URSHIFT -> if (nestedParens == 0) {
                depth -= 3
                if (depth <= 0) return true
            }
        }
    }
    return false
}

private fun findAfterMatchingDartAngle(
    raw: List<RawDartToken>,
    visible: List<Int>,
    openIndex: Int,
): RawDartToken? {
    var depth = 1
    var nestedParens = 0
    val limit = minOf(visible.size, openIndex + 48)
    for (j in openIndex + 1 until limit) {
        when (raw[visible[j]].type) {
            DartTokens.LPAREN, DartTokens.LBRACK -> nestedParens++
            DartTokens.RPAREN, DartTokens.RBRACK -> {
                if (nestedParens == 0) return null
                nestedParens--
            }
            DartTokens.SEMI, DartTokens.LBRACE, DartTokens.RBRACE, DartTokens.ASSIGN -> return null
            DartTokens.LT -> if (nestedParens == 0) depth++
            DartTokens.GT -> if (nestedParens == 0) {
                depth--
                if (depth == 0) return if (j + 1 < visible.size) raw[visible[j + 1]] else null
            }
            DartTokens.RSHIFT -> if (nestedParens == 0) {
                depth -= 2
                if (depth <= 0) return if (j + 1 < visible.size) raw[visible[j + 1]] else null
            }
            DartTokens.URSHIFT -> if (nestedParens == 0) {
                depth -= 3
                if (depth <= 0) return if (j + 1 < visible.size) raw[visible[j + 1]] else null
            }
        }
    }
    return null
}

private fun isDartMemberDot(type: Int?): Boolean =
    type == DartTokens.DOT ||
        type == DartTokens.CASCADE ||
        type == DartTokens.NULL_DOT ||
        type == DartTokens.NULL_CASCADE

private fun isDartStatementStart(prev: RawDartToken?, lineStart: Boolean): Boolean =
    lineStart ||
        prev == null ||
        prev.type == DartTokens.SEMI ||
        prev.type == DartTokens.LBRACE ||
        prev.type == DartTokens.RBRACE ||
        prev.type == DartTokens.AT_IDENT

private fun isDartValueBoundary(type: Int?): Boolean = when (type) {
    DartTokens.ASSIGN,
    DartTokens.COMMA,
    DartTokens.COLON,
    DartTokens.SEMI,
    DartTokens.RPAREN,
    DartTokens.RBRACK,
    DartTokens.RBRACE,
    DartTokens.DOT,
    DartTokens.CASCADE,
    DartTokens.NULL_DOT,
    DartTokens.NULL_CASCADE,
    DartTokens.FAT_ARROW,
    -> true
    else -> false
}

private fun isDartOverloadableOperator(type: Int?, tok: RawDartToken): Boolean =
    (type != null && isDartOperator(type)) || tok.type == DartTokens.LBRACK

private fun isDartHardKeyword(type: Int): Boolean = when (type) {
    DartTokens.ASSERT,
    DartTokens.BREAK,
    DartTokens.CASE,
    DartTokens.CATCH,
    DartTokens.CLASS,
    DartTokens.CONST,
    DartTokens.CONTINUE,
    DartTokens.DEFAULT,
    DartTokens.DO,
    DartTokens.ELSE,
    DartTokens.ENUM,
    DartTokens.EXTENDS,
    DartTokens.FINAL,
    DartTokens.FINALLY,
    DartTokens.FOR,
    DartTokens.IF,
    DartTokens.IN,
    DartTokens.IS,
    DartTokens.NEW,
    DartTokens.RETHROW,
    DartTokens.RETURN,
    DartTokens.SUPER,
    DartTokens.SWITCH,
    DartTokens.THIS,
    DartTokens.THROW,
    DartTokens.TRY,
    DartTokens.VAR,
    DartTokens.WHILE,
    DartTokens.WITH,
    -> true
    else -> false
}

private fun isDartOperator(type: Int): Boolean = when (type) {
    DartTokens.ELLIPSIS_QUESTION,
    DartTokens.ELLIPSIS,
    DartTokens.NULL_CASCADE,
    DartTokens.CASCADE,
    DartTokens.NULL_DOT,
    DartTokens.NULL_COALESCE_ASSIGN,
    DartTokens.NULL_COALESCE,
    DartTokens.INT_DIV_ASSIGN,
    DartTokens.INT_DIV,
    DartTokens.URSHIFT_ASSIGN,
    DartTokens.RSHIFT_ASSIGN,
    DartTokens.LSHIFT_ASSIGN,
    DartTokens.URSHIFT,
    DartTokens.RSHIFT,
    DartTokens.LSHIFT,
    DartTokens.FAT_ARROW,
    DartTokens.INC,
    DartTokens.DEC,
    DartTokens.AND,
    DartTokens.OR,
    DartTokens.EQ,
    DartTokens.NE,
    DartTokens.LE,
    DartTokens.GE,
    DartTokens.PLUS_ASSIGN,
    DartTokens.MINUS_ASSIGN,
    DartTokens.STAR_ASSIGN,
    DartTokens.SLASH_ASSIGN,
    DartTokens.PERCENT_ASSIGN,
    DartTokens.AMP_ASSIGN,
    DartTokens.PIPE_ASSIGN,
    DartTokens.CARET_ASSIGN,
    DartTokens.ASSIGN,
    DartTokens.LT,
    DartTokens.GT,
    DartTokens.BANG,
    DartTokens.TILDE,
    DartTokens.QUESTION,
    DartTokens.PLUS,
    DartTokens.MINUS,
    DartTokens.STAR,
    DartTokens.SLASH,
    DartTokens.PERCENT,
    DartTokens.AMP,
    DartTokens.PIPE,
    DartTokens.CARET,
    -> true
    else -> false
}

private fun isDartPunctuation(type: Int): Boolean = when (type) {
    DartTokens.LPAREN,
    DartTokens.RPAREN,
    DartTokens.LBRACE,
    DartTokens.RBRACE,
    DartTokens.LBRACK,
    DartTokens.RBRACK,
    DartTokens.SEMI,
    DartTokens.COMMA,
    DartTokens.DOT,
    DartTokens.COLON,
    DartTokens.HASH,
    DartTokens.AT,
    -> true
    else -> false
}

private val DART_CLASS_MODIFIERS = setOf(
    "abstract",
    "base",
    "interface",
    "mixin",
    "sealed",
)

private val DART_BUILTIN_TYPES = setOf(
    "int", "double", "num", "bool", "String", "Object",
    "Null", "Never", "Future", "Stream", "Iterable",
    "List", "Map", "Set", "Record", "Symbol", "Type",
    "Enum", "Pattern", "Match", "RegExp", "Duration",
    "DateTime", "Uri", "Error", "Exception", "StackTrace",
    "Comparable", "FutureOr",
)
