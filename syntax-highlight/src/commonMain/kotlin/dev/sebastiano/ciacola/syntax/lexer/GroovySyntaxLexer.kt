package dev.sebastiano.ciacola.syntax.lexer

import dev.sebastiano.ciacola.syntax.SyntaxLanguage
import dev.sebastiano.ciacola.syntax.SyntaxLexer
import dev.sebastiano.ciacola.syntax.SyntaxToken
import dev.sebastiano.ciacola.syntax.SyntaxTokenKind
import dev.sebastiano.ciacola.syntax.antlr.groovy.GroovyLexer
import dev.sebastiano.ciacola.syntax.antlr.groovy.GroovyLexer.Tokens as GroovyTokens
import dev.sebastiano.ciacola.syntax.internal.antlrCharStream
import dev.sebastiano.ciacola.syntax.internal.driveAntlrLexer
import dev.sebastiano.ciacola.syntax.internal.emitDefaultGap
import dev.sebastiano.ciacola.syntax.internal.looksLikeTypeName

/**
 * Lossless ANTLR4 DFA-based lexical tokenizer for Groovy 4 and Gradle Groovy DSL source code.
 */
internal object GroovySyntaxLexer : SyntaxLexer {
    override val language: SyntaxLanguage = SyntaxLanguage.Groovy

    override fun lex(source: String): List<SyntaxToken> {
        if (source.isEmpty()) return emptyList()

        val lexer = GroovyLexer(antlrCharStream(source))
        val raw = ArrayList<RawGroovyToken>(source.length / 3 + 8)

        driveAntlrLexer(
            source = source,
            lexer = lexer,
            onGap = { start, end ->
                emitDefaultGap(source, start, end) { kind, s, e ->
                    raw += RawGroovyToken(
                        type = if (kind == SyntaxTokenKind.Whitespace) GroovyTokens.WS else GroovyTokens.ERROR_CHAR,
                        start = s,
                        end = e,
                        kind = kind,
                        isHidden = kind == SyntaxTokenKind.Whitespace,
                    )
                }
            },
            onToken = { type, start, end -> emitRawGroovyToken(source, type, start, end, raw) },
        )

        return classifyAndEmitGroovyTokens(source, raw)
    }
}

private class RawGroovyToken(
    val type: Int,
    val start: Int,
    var end: Int,
    var kind: SyntaxTokenKind,
    val isHidden: Boolean,
)

private fun emitRawGroovyToken(
    source: String,
    type: Int,
    start: Int,
    end: Int,
    out: MutableList<RawGroovyToken>,
) {
    when (type) {
        GroovyTokens.WS,
        GroovyTokens.GDQ_UNTERM_NL,
        -> out += RawGroovyToken(type, start, end, SyntaxTokenKind.Whitespace, isHidden = true)

        GroovyTokens.SHEBANG,
        GroovyTokens.LINE_COMMENT,
        GroovyTokens.BLOCK_COMMENT,
        GroovyTokens.UNTERMINATED_BLOCK_COMMENT,
        -> out += RawGroovyToken(type, start, end, SyntaxTokenKind.Comment, isHidden = true)

        GroovyTokens.TRIPLE_SINGLE_STRING,
        GroovyTokens.UNTERMINATED_TRIPLE_SINGLE_STRING,
        GroovyTokens.SINGLE_STRING,
        GroovyTokens.UNTERMINATED_SINGLE_STRING,
        GroovyTokens.TDQUOTE_START,
        GroovyTokens.DQUOTE_START,
        GroovyTokens.DOLLAR_SLASHY_START,
        GroovyTokens.SLASHY_START,
        GroovyTokens.GDQ_CLOSE,
        GroovyTokens.GDQ_LINE_CONT,
        GroovyTokens.GDQ_ESCAPE,
        GroovyTokens.GDQ_BACKSLASH,
        GroovyTokens.GDQ_DOLLAR,
        GroovyTokens.GDQ_TEXT,
        GroovyTokens.GTDQ_CLOSE,
        GroovyTokens.GTDQ_QUOTE,
        GroovyTokens.GTDQ_ESCAPE,
        GroovyTokens.GTDQ_BACKSLASH,
        GroovyTokens.GTDQ_DOLLAR,
        GroovyTokens.GTDQ_TEXT,
        GroovyTokens.SL_CLOSE,
        GroovyTokens.SL_ESCAPE,
        GroovyTokens.SL_BACKSLASH,
        GroovyTokens.SL_DOLLAR,
        GroovyTokens.SL_TEXT,
        GroovyTokens.DSL_CLOSE,
        GroovyTokens.DSL_ESCAPE,
        GroovyTokens.DSL_DOLLAR,
        GroovyTokens.DSL_SLASH,
        GroovyTokens.DSL_TEXT,
        -> out += RawGroovyToken(type, start, end, SyntaxTokenKind.String, isHidden = false)

        GroovyTokens.GDQ_INTERP_START,
        GroovyTokens.GTDQ_INTERP_START,
        GroovyTokens.SL_INTERP_START,
        GroovyTokens.DSL_INTERP_START,
        GroovyTokens.GSTRING_INTERP_CLOSE,
        -> out += RawGroovyToken(type, start, end, SyntaxTokenKind.Punctuation, isHidden = false)

        GroovyTokens.GDQ_INTERP_VAR,
        GroovyTokens.GTDQ_INTERP_VAR,
        GroovyTokens.SL_INTERP_VAR,
        GroovyTokens.DSL_INTERP_VAR,
        -> emitGStringInterpolatedVar(source, start, end, out)

        GroovyTokens.HEX_FLOAT_LITERAL,
        GroovyTokens.FLOAT_LITERAL,
        GroovyTokens.HEX_LITERAL,
        GroovyTokens.BIN_LITERAL,
        GroovyTokens.OCT_LITERAL,
        GroovyTokens.DECIMAL_LITERAL,
        -> out += RawGroovyToken(type, start, end, SyntaxTokenKind.Number, isHidden = false)

        GroovyTokens.TRUE,
        GroovyTokens.FALSE,
        GroovyTokens.NULL,
        -> out += RawGroovyToken(type, start, end, SyntaxTokenKind.Constant, isHidden = false)

        GroovyTokens.BOOLEAN,
        GroovyTokens.BYTE,
        GroovyTokens.CHAR,
        GroovyTokens.SHORT,
        GroovyTokens.INT,
        GroovyTokens.LONG,
        GroovyTokens.FLOAT,
        GroovyTokens.DOUBLE,
        GroovyTokens.VOID,
        -> out += RawGroovyToken(type, start, end, SyntaxTokenKind.Type, isHidden = false)

        GroovyTokens.ERROR_CHAR ->
            out += RawGroovyToken(type, start, end, SyntaxTokenKind.Invalid, isHidden = false)

        else -> {
            val initialKind = when {
                isGroovyHardKeyword(type) -> SyntaxTokenKind.Keyword
                isGroovyOperator(type) -> SyntaxTokenKind.Operator
                isGroovyPunctuation(type) -> SyntaxTokenKind.Punctuation
                else -> SyntaxTokenKind.Plain
            }
            out += RawGroovyToken(type, start, end, initialKind, isHidden = false)
        }
    }
}

private fun emitGStringInterpolatedVar(
    source: String,
    start: Int,
    end: Int,
    out: MutableList<RawGroovyToken>,
) {
    // Split `$foo.bar.baz` into `$` (Punctuation), `foo` (Property/Plain), `.` (Punctuation), `bar` (Property), ...
    if (start < end && source[start] == '$') {
        out += RawGroovyToken(SYNTHETIC_INTERP_DOLLAR, start, start + 1, SyntaxTokenKind.Punctuation, isHidden = false)
        var segStart = start + 1
        var first = true
        while (segStart < end) {
            val dotIdx = source.indexOf('.', segStart)
            val segEnd = if (dotIdx in segStart until end) dotIdx else end
            if (segEnd > segStart) {
                val segText = source.substring(segStart, segEnd)
                val segKind = when {
                    !first -> SyntaxTokenKind.Property
                    looksLikeTypeName(segText) -> SyntaxTokenKind.Type
                    else -> SyntaxTokenKind.Property
                }
                out += RawGroovyToken(SYNTHETIC_INTERP_SEG, segStart, segEnd, segKind, isHidden = false)
                first = false
            }
            if (dotIdx in segStart until end) {
                out += RawGroovyToken(GroovyTokens.DOT, dotIdx, dotIdx + 1, SyntaxTokenKind.Punctuation, isHidden = false)
                segStart = dotIdx + 1
            } else {
                break
            }
        }
    } else {
        out += RawGroovyToken(GroovyTokens.GDQ_INTERP_VAR, start, end, SyntaxTokenKind.Property, isHidden = false)
    }
}

private fun classifyAndEmitGroovyTokens(code: String, raw: MutableList<RawGroovyToken>): List<SyntaxToken> {
    val visible = ArrayList<Int>(raw.size)
    for (idx in raw.indices) {
        if (!raw[idx].isHidden) visible += idx
    }

    var inPackageOrImport = false
    var inTypeHeader = false
    var expectingDeclaredTypeName = false
    var braceDepth = 0
    var angleDepth = 0
    var parenDepth = 0
    var brackDepth = 0
    var ternaryDepth = 0
    var inSwitchCaseLabel = false
    var caseLabelDepth = 0
    var annotationArgDepth = 0
    var justEndedAnnotationArgs = false
    val savedParenTernaryDepths = ArrayList<Int>()
    val savedBrackTernaryDepths = ArrayList<Int>()
    val savedBraceTernaryDepths = ArrayList<Int>()

    val dropIndices = HashSet<Int>()
    var i = 0

    while (i < visible.size) {
        val rawIdx = visible[i]
        val tok = raw[rawIdx]
        val prev = if (i > 0) raw[visible[i - 1]] else null
        val next = if (i + 1 < visible.size) raw[visible[i + 1]] else null
        val wasAfterAnnotation = justEndedAnnotationArgs || prev?.kind == SyntaxTokenKind.Annotation
        justEndedAnnotationArgs = false

        val crossedNewline = prev != null && hasNewlineBetween(code, prev.end, tok.start)

        if (tok.type == GroovyTokens.SEMI || crossedNewline) {
            if (tok.type == GroovyTokens.SEMI || inPackageOrImport) {
                inPackageOrImport = false
            }
            if (tok.type == GroovyTokens.SEMI || (parenDepth == 0 && brackDepth == 0 && prev?.type != GroovyTokens.QUESTION)) {
                ternaryDepth = 0
            }
            if (tok.type == GroovyTokens.SEMI) {
                inTypeHeader = false
                expectingDeclaredTypeName = false
                inSwitchCaseLabel = false
                angleDepth = 0
            }
        }

        when (tok.type) {
            GroovyTokens.PACKAGE,
            GroovyTokens.IMPORT,
            -> inPackageOrImport = true

            GroovyTokens.CLASS,
            GroovyTokens.INTERFACE,
            GroovyTokens.ENUM,
            -> {
                if (!isGroovyMemberPredecessor(prev?.type)) {
                    inTypeHeader = true
                    expectingDeclaredTypeName = true
                }
            }

            GroovyTokens.LBRACE -> {
                braceDepth++
                savedBraceTernaryDepths.add(ternaryDepth)
                ternaryDepth = 0
                angleDepth = 0
                inTypeHeader = false
                inSwitchCaseLabel = false
            }

            GroovyTokens.RBRACE -> {
                if (braceDepth > 0) braceDepth--
                ternaryDepth = if (savedBraceTernaryDepths.isNotEmpty()) {
                    savedBraceTernaryDepths.removeAt(savedBraceTernaryDepths.lastIndex)
                } else {
                    0
                }
                angleDepth = 0
                inSwitchCaseLabel = false
            }

            GroovyTokens.LPAREN -> {
                parenDepth++
                savedParenTernaryDepths.add(ternaryDepth)
                ternaryDepth = 0
                if (inSwitchCaseLabel) caseLabelDepth++
            }

            GroovyTokens.RPAREN -> {
                if (parenDepth > 0) parenDepth--
                ternaryDepth = if (savedParenTernaryDepths.isNotEmpty()) {
                    savedParenTernaryDepths.removeAt(savedParenTernaryDepths.lastIndex)
                } else {
                    0
                }
                if (inSwitchCaseLabel && caseLabelDepth > 0) caseLabelDepth--
                if (annotationArgDepth > 0 && parenDepth < annotationArgDepth) {
                    annotationArgDepth = 0
                    justEndedAnnotationArgs = true
                }
            }

            GroovyTokens.LBRACK -> {
                brackDepth++
                savedBrackTernaryDepths.add(ternaryDepth)
                ternaryDepth = 0
            }

            GroovyTokens.RBRACK -> {
                if (brackDepth > 0) brackDepth--
                ternaryDepth = if (savedBrackTernaryDepths.isNotEmpty()) {
                    savedBrackTernaryDepths.removeAt(savedBrackTernaryDepths.lastIndex)
                } else {
                    0
                }
            }

            GroovyTokens.QUESTION -> {
                // Safe index `list?[0]` is not a ternary expression
                if (!(next?.type == GroovyTokens.LBRACK && tok.end == next.start)) {
                    ternaryDepth++
                }
            }

            GroovyTokens.LT -> if (hasMatchingGroovyAngleClose(raw, visible, i)) angleDepth++
            GroovyTokens.GT -> if (angleDepth > 0) angleDepth--
            GroovyTokens.RSHIFT -> angleDepth = (angleDepth - 2).coerceAtLeast(0)
            GroovyTokens.URSHIFT -> angleDepth = (angleDepth - 3).coerceAtLeast(0)

            GroovyTokens.CASE -> {
                inSwitchCaseLabel = true
                caseLabelDepth = 0
            }

            GroovyTokens.COLON -> {
                if (ternaryDepth > 0) ternaryDepth--
                if (inSwitchCaseLabel && caseLabelDepth == 0) {
                    inSwitchCaseLabel = false
                }
            }

            GroovyTokens.ARROW -> {
                if (inSwitchCaseLabel && caseLabelDepth == 0) {
                    inSwitchCaseLabel = false
                }
            }
        }

        // Annotations (`@CompileStatic`, `@Grab(...)`, `@groovy.transform.ToString`, `@interface`)
        if (tok.type == GroovyTokens.AT && next != null) {
            if (next.type == GroovyTokens.INTERFACE) {
                tok.kind = SyntaxTokenKind.Keyword
                i++
                continue
            }
            if (next.type == GroovyTokens.IDENTIFIER) {
                tok.kind = SyntaxTokenKind.Annotation
                var j = i + 1
                while (j < visible.size && raw[visible[j]].type == GroovyTokens.IDENTIFIER) {
                    raw[visible[j]].kind = SyntaxTokenKind.Annotation
                    j++
                    if (
                        j + 1 < visible.size &&
                        raw[visible[j]].type == GroovyTokens.DOT &&
                        raw[visible[j + 1]].type == GroovyTokens.IDENTIFIER
                    ) {
                        raw[visible[j]].kind = SyntaxTokenKind.Annotation
                        j++
                    } else {
                        break
                    }
                }
                if (j < visible.size && raw[visible[j]].type == GroovyTokens.LPAREN) {
                    annotationArgDepth = parenDepth + 1
                }
                i = j
                continue
            }
        }

        // Contextual `non-sealed` in declaration modifier position
        if (
            tok.type == GroovyTokens.IDENTIFIER &&
            code.substring(tok.start, tok.end) == "non" &&
            !isGroovyMemberPredecessor(prev?.type) &&
            i + 3 < visible.size
        ) {
            val dash = raw[visible[i + 1]]
            val sealedTok = raw[visible[i + 2]]
            val after = raw[visible[i + 3]]
            if (
                dash.type == GroovyTokens.MINUS &&
                tok.end == dash.start &&
                dash.end == sealedTok.start &&
                sealedTok.type == GroovyTokens.IDENTIFIER &&
                code.substring(sealedTok.start, sealedTok.end) == "sealed" &&
                isGroovyTypeOrModifier(after.type, code.substring(after.start, after.end))
            ) {
                tok.end = sealedTok.end
                tok.kind = SyntaxTokenKind.Keyword
                dropIndices += visible[i + 1]
                dropIndices += visible[i + 2]
                i += 3
                continue
            }
        }

        // Quoted map key (`'key': value` or `"key": value`) stays String, unquoted `key: value` becomes Property below.
        if (tok.type == GroovyTokens.IDENTIFIER) {
            val text = code.substring(tok.start, tok.end)

            if (isGroovyContextualKeyword(
                    text = text,
                    raw = raw,
                    visible = visible,
                    index = i,
                    code = code,
                    prev = prev,
                    crossedNewline = crossedNewline,
                    prevAfterAnnotation = wasAfterAnnotation,
                    inTypeHeader = inTypeHeader,
                    inSwitchCaseLabel = inSwitchCaseLabel,
                    caseLabelDepth = caseLabelDepth,
                )
            ) {
                tok.kind = SyntaxTokenKind.Keyword
                if (text == "trait" || text == "record") {
                    inTypeHeader = true
                    expectingDeclaredTypeName = true
                }
                i++
                continue
            }

            if (expectingDeclaredTypeName) {
                tok.kind = SyntaxTokenKind.Type
                expectingDeclaredTypeName = false
                i++
                continue
            }

            tok.kind = classifyGroovyIdentifier(
                text = text,
                prev = prev,
                next = next,
                inPackageOrImport = inPackageOrImport,
                angleDepth = angleDepth,
                ternaryDepth = ternaryDepth,
            )
        }

        i++
    }

    val out = ArrayList<SyntaxToken>(raw.size)
    for (idx in raw.indices) {
        if (idx in dropIndices) continue
        val t = raw[idx]
        out += SyntaxToken(kind = t.kind, startIndex = t.start, endIndex = t.end)
    }
    return out
}

private fun hasNewlineBetween(code: String, start: Int, end: Int): Boolean {
    for (idx in start until end) {
        val ch = code[idx]
        if (ch == '\n' || ch == '\r') return true
    }
    return false
}

private fun isGroovyMemberPredecessor(type: Int?): Boolean = when (type) {
    GroovyTokens.DOT,
    GroovyTokens.SAFE_DOT,
    GroovyTokens.SPREAD_DOT,
    GroovyTokens.METHOD_PTR,
    GroovyTokens.FIELD_ACC,
    GroovyTokens.COLONCOLON,
    -> true
    else -> false
}

private fun hasMatchingGroovyAngleClose(
    raw: List<RawGroovyToken>,
    visible: List<Int>,
    openIndex: Int,
): Boolean {
    var depth = 1
    var nestedParens = 0
    val limit = minOf(visible.size, openIndex + 65)
    for (j in openIndex + 1 until limit) {
        when (raw[visible[j]].type) {
            GroovyTokens.LPAREN,
            GroovyTokens.LBRACK,
            -> nestedParens++

            GroovyTokens.RPAREN,
            GroovyTokens.RBRACK,
            -> {
                if (nestedParens == 0) return false
                nestedParens--
            }

            GroovyTokens.SEMI,
            GroovyTokens.LBRACE,
            GroovyTokens.RBRACE,
            -> return false

            GroovyTokens.ASSIGN,
            GroovyTokens.EQ,
            GroovyTokens.NE,
            GroovyTokens.SPACESHIP,
            GroovyTokens.AND,
            GroovyTokens.OR,
            GroovyTokens.RETURN,
            GroovyTokens.IF,
            GroovyTokens.FOR,
            GroovyTokens.WHILE,
            GroovyTokens.SWITCH,
            -> if (nestedParens == 0) return false

            GroovyTokens.LT -> if (nestedParens == 0) depth++
            GroovyTokens.GT -> if (nestedParens == 0) {
                depth--
                if (depth <= 0) return true
            }
            GroovyTokens.RSHIFT -> if (nestedParens == 0) {
                depth -= 2
                if (depth <= 0) return true
            }
            GroovyTokens.URSHIFT -> if (nestedParens == 0) {
                depth -= 3
                if (depth <= 0) return true
            }
        }
    }
    return false
}

private fun isGroovyContextualKeyword(
    text: String,
    raw: List<RawGroovyToken>,
    visible: List<Int>,
    index: Int,
    code: String,
    prev: RawGroovyToken?,
    crossedNewline: Boolean,
    prevAfterAnnotation: Boolean,
    inTypeHeader: Boolean,
    inSwitchCaseLabel: Boolean,
    caseLabelDepth: Int,
): Boolean {
    if (isGroovyMemberPredecessor(prev?.type)) return false
    val next = if (index + 1 < visible.size) raw[visible[index + 1]] else null
    val next2 = if (index + 2 < visible.size) raw[visible[index + 2]] else null
    val nextText = next?.let { code.substring(it.start, it.end) }

    return when (text) {
        "trait" -> {
            next?.type == GroovyTokens.IDENTIFIER &&
                isGroovyDeclPredecessor(prev, crossedNewline, prevAfterAnnotation)
        }

        "record" -> {
            next?.type == GroovyTokens.IDENTIFIER &&
                (next2?.type == GroovyTokens.LPAREN || next2?.type == GroovyTokens.LT) &&
                isGroovyDeclPredecessor(prev, crossedNewline, prevAfterAnnotation)
        }

        "sealed" -> {
            isGroovyDeclPredecessor(prev, crossedNewline, prevAfterAnnotation) &&
                next != null &&
                isGroovyTypeOrModifier(next.type, nextText.orEmpty())
        }

        "permits" -> {
            inTypeHeader &&
                prev != null &&
                (prev.type == GroovyTokens.IDENTIFIER || prev.type == GroovyTokens.GT) &&
                next?.type == GroovyTokens.IDENTIFIER
        }

        "var" -> {
            next?.type == GroovyTokens.IDENTIFIER &&
                next2 != null &&
                (
                    next2.type == GroovyTokens.ASSIGN ||
                        next2.type == GroovyTokens.COLON ||
                        next2.type == GroovyTokens.COMMA ||
                        next2.type == GroovyTokens.RPAREN ||
                        next2.type == GroovyTokens.SEMI ||
                        next2.type == GroovyTokens.IN
                    ) &&
                isGroovyVarPredecessor(prev, crossedNewline, prevAfterAnnotation)
        }

        "yield" -> {
            isGroovyStmtStartPredecessor(prev, crossedNewline) &&
                next != null &&
                !isGroovyAssignmentOrSeparator(next.type) &&
                !(next.type == GroovyTokens.LPAREN && next2?.type == GroovyTokens.RPAREN)
        }

        "when" -> {
            inSwitchCaseLabel &&
                caseLabelDepth == 0 &&
                next != null &&
                !isGroovyAssignmentOrSeparator(next.type)
        }

        else -> false
    }
}

private fun isGroovyDeclPredecessor(
    prev: RawGroovyToken?,
    crossedNewline: Boolean,
    prevAfterAnnotation: Boolean,
): Boolean {
    if (prev == null || crossedNewline || prevAfterAnnotation) return true
    return when (prev.type) {
        GroovyTokens.LBRACE,
        GroovyTokens.RBRACE,
        GroovyTokens.SEMI,
        GroovyTokens.PUBLIC,
        GroovyTokens.PROTECTED,
        GroovyTokens.PRIVATE,
        GroovyTokens.ABSTRACT,
        GroovyTokens.STATIC,
        GroovyTokens.FINAL,
        GroovyTokens.STRICTFP,
        -> true
        else -> false
    }
}

private fun isGroovyVarPredecessor(
    prev: RawGroovyToken?,
    crossedNewline: Boolean,
    prevAfterAnnotation: Boolean,
): Boolean {
    if (prev == null || crossedNewline || prevAfterAnnotation) return true
    return when (prev.type) {
        GroovyTokens.LBRACE,
        GroovyTokens.RBRACE,
        GroovyTokens.SEMI,
        GroovyTokens.LPAREN,
        GroovyTokens.COMMA,
        GroovyTokens.COLON,
        GroovyTokens.ARROW,
        GroovyTokens.FINAL,
        GroovyTokens.PUBLIC,
        GroovyTokens.PROTECTED,
        GroovyTokens.PRIVATE,
        GroovyTokens.STATIC,
        -> true
        else -> false
    }
}

private fun isGroovyStmtStartPredecessor(prev: RawGroovyToken?, crossedNewline: Boolean): Boolean {
    if (prev == null || crossedNewline) return true
    return when (prev.type) {
        GroovyTokens.LBRACE,
        GroovyTokens.RBRACE,
        GroovyTokens.SEMI,
        GroovyTokens.COLON,
        GroovyTokens.ARROW,
        -> true
        else -> false
    }
}

private fun isGroovyTypeOrModifier(type: Int, text: String): Boolean = when (type) {
    GroovyTokens.CLASS,
    GroovyTokens.INTERFACE,
    GroovyTokens.PUBLIC,
    GroovyTokens.PROTECTED,
    GroovyTokens.PRIVATE,
    GroovyTokens.ABSTRACT,
    GroovyTokens.STATIC,
    GroovyTokens.FINAL,
    GroovyTokens.STRICTFP,
    -> true
    GroovyTokens.IDENTIFIER -> text == "trait" || text == "non" || text == "sealed"
    else -> false
}

private fun isGroovyAssignmentOrSeparator(type: Int): Boolean = when (type) {
    GroovyTokens.ASSIGN,
    GroovyTokens.ELVIS_ASSIGN,
    GroovyTokens.ADD_ASSIGN,
    GroovyTokens.SUB_ASSIGN,
    GroovyTokens.MUL_ASSIGN,
    GroovyTokens.DIV_ASSIGN,
    GroovyTokens.MOD_ASSIGN,
    GroovyTokens.STAR_STAR_ASSIGN,
    GroovyTokens.AND_ASSIGN,
    GroovyTokens.OR_ASSIGN,
    GroovyTokens.XOR_ASSIGN,
    GroovyTokens.LSHIFT_ASSIGN,
    GroovyTokens.RSHIFT_ASSIGN,
    GroovyTokens.URSHIFT_ASSIGN,
    GroovyTokens.DOT,
    GroovyTokens.SAFE_DOT,
    GroovyTokens.SPREAD_DOT,
    GroovyTokens.METHOD_PTR,
    GroovyTokens.FIELD_ACC,
    GroovyTokens.COLONCOLON,
    GroovyTokens.SEMI,
    GroovyTokens.COMMA,
    GroovyTokens.RPAREN,
    GroovyTokens.RBRACK,
    GroovyTokens.RBRACE,
    -> true
    else -> false
}

private fun classifyGroovyIdentifier(
    text: String,
    prev: RawGroovyToken?,
    next: RawGroovyToken?,
    inPackageOrImport: Boolean,
    angleDepth: Int,
    ternaryDepth: Int,
): SyntaxTokenKind {
    if (inPackageOrImport) {
        return if (looksLikeTypeName(text)) SyntaxTokenKind.Type else SyntaxTokenKind.Plain
    }

    val prevType = prev?.type
    val nextType = next?.type

    if (nextType == GroovyTokens.LPAREN) {
        if (prevType == GroovyTokens.NEW && looksLikeTypeName(text)) {
            return SyntaxTokenKind.Type
        }
        return SyntaxTokenKind.Function
    }

    if (prevType == GroovyTokens.METHOD_PTR || prevType == GroovyTokens.COLONCOLON) {
        return if (looksLikeTypeName(text)) SyntaxTokenKind.Type else SyntaxTokenKind.Function
    }

    if (prevType == GroovyTokens.FIELD_ACC ||
        prevType == GroovyTokens.DOT ||
        prevType == GroovyTokens.SAFE_DOT ||
        prevType == GroovyTokens.SPREAD_DOT
    ) {
        return if (looksLikeTypeName(text) && nextType == GroovyTokens.DOT) {
            SyntaxTokenKind.Type
        } else {
            SyntaxTokenKind.Property
        }
    }

    // Named argument or unquoted map key: `group: 'dev.example', version: '1.0'`
    if (nextType == GroovyTokens.COLON && ternaryDepth == 0) {
        return SyntaxTokenKind.Property
    }

    if (prev?.kind == SyntaxTokenKind.Type) {
        return SyntaxTokenKind.Plain
    }

    if (
        text.length == 1 &&
        text[0].isUpperCase() &&
        (angleDepth > 0 || prevType == GroovyTokens.LT || prevType == GroovyTokens.COMMA || prevType == GroovyTokens.EXTENDS || prevType == GroovyTokens.SUPER)
    ) {
        return SyntaxTokenKind.Type
    }

    if (looksLikeTypeName(text)) {
        return SyntaxTokenKind.Type
    }

    if (text == "println" || text == "print" || text == "printf") {
        return SyntaxTokenKind.Function
    }

    return SyntaxTokenKind.Plain
}

private fun isGroovyHardKeyword(type: Int): Boolean = when (type) {
    GroovyTokens.ABSTRACT,
    GroovyTokens.AS,
    GroovyTokens.ASSERT,
    GroovyTokens.BREAK,
    GroovyTokens.CASE,
    GroovyTokens.CATCH,
    GroovyTokens.CLASS,
    GroovyTokens.CONST,
    GroovyTokens.CONTINUE,
    GroovyTokens.DEF,
    GroovyTokens.DEFAULT,
    GroovyTokens.DO,
    GroovyTokens.ELSE,
    GroovyTokens.ENUM,
    GroovyTokens.EXTENDS,
    GroovyTokens.FINAL,
    GroovyTokens.FINALLY,
    GroovyTokens.FOR,
    GroovyTokens.GOTO,
    GroovyTokens.IF,
    GroovyTokens.IMPLEMENTS,
    GroovyTokens.IMPORT,
    GroovyTokens.IN,
    GroovyTokens.INSTANCEOF,
    GroovyTokens.INTERFACE,
    GroovyTokens.NATIVE,
    GroovyTokens.NEW,
    GroovyTokens.PACKAGE,
    GroovyTokens.PRIVATE,
    GroovyTokens.PROTECTED,
    GroovyTokens.PUBLIC,
    GroovyTokens.RETURN,
    GroovyTokens.STATIC,
    GroovyTokens.STRICTFP,
    GroovyTokens.SUPER,
    GroovyTokens.SWITCH,
    GroovyTokens.SYNCHRONIZED,
    GroovyTokens.THIS,
    GroovyTokens.THROW,
    GroovyTokens.THROWS,
    GroovyTokens.TRANSIENT,
    GroovyTokens.TRY,
    GroovyTokens.VOLATILE,
    GroovyTokens.WHILE,
    -> true
    else -> false
}

private fun isGroovyOperator(type: Int): Boolean = when (type) {
    GroovyTokens.ARROW,
    GroovyTokens.ELVIS_ASSIGN,
    GroovyTokens.ELVIS,
    GroovyTokens.SAFE_DOT,
    GroovyTokens.SPREAD_DOT,
    GroovyTokens.METHOD_PTR,
    GroovyTokens.FIELD_ACC,
    GroovyTokens.STAR_STAR_ASSIGN,
    GroovyTokens.STAR_STAR,
    GroovyTokens.SPACESHIP,
    GroovyTokens.REGEX_MATCH,
    GroovyTokens.REGEX_FIND,
    GroovyTokens.IDENTICAL,
    GroovyTokens.NOT_IDENTICAL,
    GroovyTokens.HALF_RANGE_BOTH,
    GroovyTokens.HALF_RANGE_LEFT,
    GroovyTokens.HALF_RANGE_RIGHT,
    GroovyTokens.RANGE,
    GroovyTokens.URSHIFT_ASSIGN,
    GroovyTokens.RSHIFT_ASSIGN,
    GroovyTokens.LSHIFT_ASSIGN,
    GroovyTokens.ADD_ASSIGN,
    GroovyTokens.SUB_ASSIGN,
    GroovyTokens.MUL_ASSIGN,
    GroovyTokens.DIV_ASSIGN,
    GroovyTokens.MOD_ASSIGN,
    GroovyTokens.AND_ASSIGN,
    GroovyTokens.OR_ASSIGN,
    GroovyTokens.XOR_ASSIGN,
    GroovyTokens.URSHIFT,
    GroovyTokens.RSHIFT,
    GroovyTokens.LSHIFT,
    GroovyTokens.INC,
    GroovyTokens.DEC,
    GroovyTokens.AND,
    GroovyTokens.OR,
    GroovyTokens.LE,
    GroovyTokens.GE,
    GroovyTokens.EQ,
    GroovyTokens.NE,
    GroovyTokens.ASSIGN,
    GroovyTokens.GT,
    GroovyTokens.LT,
    GroovyTokens.BANG,
    GroovyTokens.TILDE,
    GroovyTokens.QUESTION,
    GroovyTokens.PLUS,
    GroovyTokens.MINUS,
    GroovyTokens.STAR,
    GroovyTokens.SLASH,
    GroovyTokens.BITAND,
    GroovyTokens.BITOR,
    GroovyTokens.CARET,
    GroovyTokens.MOD,
    -> true
    else -> false
}

private fun isGroovyPunctuation(type: Int): Boolean = when (type) {
    GroovyTokens.ELLIPSIS,
    GroovyTokens.LPAREN,
    GroovyTokens.RPAREN,
    GroovyTokens.LBRACE,
    GroovyTokens.RBRACE,
    GroovyTokens.LBRACK,
    GroovyTokens.RBRACK,
    GroovyTokens.SEMI,
    GroovyTokens.COMMA,
    GroovyTokens.DOT,
    GroovyTokens.COLON,
    GroovyTokens.COLONCOLON,
    GroovyTokens.AT,
    -> true
    else -> false
}

private const val SYNTHETIC_INTERP_DOLLAR: Int = -1
private const val SYNTHETIC_INTERP_SEG: Int = -2
