package dev.sebastiano.ciacola.syntax.lexer

import dev.sebastiano.ciacola.syntax.SyntaxLanguage
import dev.sebastiano.ciacola.syntax.SyntaxLexer
import dev.sebastiano.ciacola.syntax.SyntaxToken
import dev.sebastiano.ciacola.syntax.SyntaxTokenKind
import dev.sebastiano.ciacola.syntax.antlr.csharp.CSharpLexer
import dev.sebastiano.ciacola.syntax.antlr.csharp.CSharpLexer.Tokens as CSharpTokens
import dev.sebastiano.ciacola.syntax.internal.antlrCharStream
import dev.sebastiano.ciacola.syntax.internal.driveAntlrLexer
import dev.sebastiano.ciacola.syntax.internal.emitDefaultGap
import dev.sebastiano.ciacola.syntax.internal.looksLikeTypeName

/**
 * Lossless ANTLR4 DFA-based lexical tokenizer for C# 12/13 source code.
 */
internal object CSharpSyntaxLexer : SyntaxLexer {
    override val language: SyntaxLanguage = SyntaxLanguage.CSharp

    override fun lex(source: String): List<SyntaxToken> {
        if (source.isEmpty()) return emptyList()

        val lexer = CSharpLexer(antlrCharStream(source))
        val raw = ArrayList<RawCSharpToken>(source.length / 3 + 8)

        driveAntlrLexer(
            source = source,
            lexer = lexer,
            onGap = { start, end ->
                emitDefaultGap(source, start, end) { kind, s, e ->
                    raw += RawCSharpToken(
                        type = if (kind == SyntaxTokenKind.Whitespace) CSharpTokens.WS else CSharpTokens.ERROR_CHAR,
                        start = s,
                        end = e,
                        kind = kind,
                        isHidden = kind == SyntaxTokenKind.Whitespace,
                    )
                }
            },
            onToken = { type, start, end -> emitRawCSharpToken(type, start, end, raw) },
        )

        classifyCSharpTokens(source, raw)
        return raw.map { SyntaxToken(kind = it.kind, startIndex = it.start, endIndex = it.end) }
    }
}

private class RawCSharpToken(
    val type: Int,
    val start: Int,
    val end: Int,
    var kind: SyntaxTokenKind,
    val isHidden: Boolean,
)

private fun emitRawCSharpToken(
    type: Int,
    start: Int,
    end: Int,
    out: MutableList<RawCSharpToken>,
) {
    when (type) {
        CSharpTokens.WS,
        CSharpTokens.NEWLINE,
        CSharpTokens.PP_WS,
        CSharpTokens.PP_NL,
        CSharpTokens.IR_UNTERM_NL,
        CSharpTokens.FMT_NEWLINE,
        -> out += RawCSharpToken(type, start, end, SyntaxTokenKind.Whitespace, isHidden = true)

        CSharpTokens.DOC_COMMENT,
        CSharpTokens.LINE_COMMENT,
        CSharpTokens.BLOCK_COMMENT,
        CSharpTokens.UNTERMINATED_BLOCK_COMMENT,
        CSharpTokens.PP_LINE_COMMENT,
        CSharpTokens.PP_BLOCK_COMMENT,
        -> out += RawCSharpToken(type, start, end, SyntaxTokenKind.Comment, isHidden = true)

        CSharpTokens.PP_HASH,
        CSharpTokens.PP_DIRECTIVE,
        CSharpTokens.PP_TEXT,
        -> out += RawCSharpToken(type, start, end, SyntaxTokenKind.Preprocessor, isHidden = false)

        CSharpTokens.INTERP_OPEN,
        CSharpTokens.INTERP_CLOSE,
        -> out += RawCSharpToken(type, start, end, SyntaxTokenKind.Punctuation, isHidden = false)

        CSharpTokens.PP_STRING,
        CSharpTokens.RAW_INTERP_START,
        CSharpTokens.VERBATIM_INTERP_START,
        CSharpTokens.REGULAR_INTERP_START,
        CSharpTokens.RAW_STRING_LITERAL,
        CSharpTokens.VERBATIM_STRING_LITERAL,
        CSharpTokens.UNTERMINATED_VERBATIM_STRING_LITERAL,
        CSharpTokens.STRING_LITERAL,
        CSharpTokens.UNTERMINATED_STRING_LITERAL,
        CSharpTokens.CHAR_LITERAL,
        CSharpTokens.UNTERMINATED_CHAR_LITERAL,
        CSharpTokens.IR_ESCAPED_BRACE,
        CSharpTokens.IR_ESCAPE,
        CSharpTokens.IR_BACKSLASH,
        CSharpTokens.IR_CLOSE,
        CSharpTokens.IR_TEXT,
        CSharpTokens.IV_ESCAPED_BRACE,
        CSharpTokens.IV_ESCAPED_QUOTE,
        CSharpTokens.IV_CLOSE,
        CSharpTokens.IV_TEXT,
        CSharpTokens.IRAW_LBRACE,
        CSharpTokens.IRAW_RBRACE,
        CSharpTokens.IRAW_QUOTES,
        CSharpTokens.IRAW_CLOSE,
        CSharpTokens.IRAW_TEXT,
        CSharpTokens.INTERP_FORMAT_COLON,
        CSharpTokens.FMT_RBRACE,
        CSharpTokens.FMT_ESCAPE,
        CSharpTokens.FMT_VERBATIM_QUOTE,
        CSharpTokens.FMT_QUOTE,
        CSharpTokens.FMT_TEXT,
        -> out += RawCSharpToken(type, start, end, SyntaxTokenKind.String, isHidden = false)

        CSharpTokens.HEX_LITERAL,
        CSharpTokens.BIN_LITERAL,
        CSharpTokens.FLOAT_LITERAL,
        CSharpTokens.DECIMAL_LITERAL,
        -> out += RawCSharpToken(type, start, end, SyntaxTokenKind.Number, isHidden = false)

        CSharpTokens.TRUE,
        CSharpTokens.FALSE,
        CSharpTokens.NULL,
        -> out += RawCSharpToken(type, start, end, SyntaxTokenKind.Constant, isHidden = false)

        CSharpTokens.BOOL,
        CSharpTokens.BYTE,
        CSharpTokens.SBYTE,
        CSharpTokens.CHAR,
        CSharpTokens.DECIMAL,
        CSharpTokens.DOUBLE,
        CSharpTokens.FLOAT,
        CSharpTokens.INT,
        CSharpTokens.UINT,
        CSharpTokens.LONG,
        CSharpTokens.ULONG,
        CSharpTokens.OBJECT,
        CSharpTokens.SHORT,
        CSharpTokens.USHORT,
        CSharpTokens.STRING,
        CSharpTokens.VOID,
        -> out += RawCSharpToken(type, start, end, SyntaxTokenKind.Type, isHidden = false)

        CSharpTokens.ERROR_CHAR ->
            out += RawCSharpToken(type, start, end, SyntaxTokenKind.Invalid, isHidden = false)

        else -> {
            val initialKind = when {
                isCSharpHardKeyword(type) -> SyntaxTokenKind.Keyword
                isCSharpOperator(type) -> SyntaxTokenKind.Operator
                isCSharpPunctuation(type) -> SyntaxTokenKind.Punctuation
                else -> SyntaxTokenKind.Plain
            }
            out += RawCSharpToken(type, start, end, initialKind, isHidden = false)
        }
    }
}

private fun classifyCSharpTokens(code: String, raw: List<RawCSharpToken>) {
    val visible = ArrayList<Int>(raw.size)
    for (idx in raw.indices) {
        if (!raw[idx].isHidden) visible += idx
    }

    var inNamespaceOrUsing = false
    var expectingDeclaredTypeName = false
    var inGenericConstraint = false
    var inPattern = false
    var patternEntryDepth = 0
    var inCaseLabel = false
    var caseLabelEntryDepth = 0
    var inLinqQuery = false
    var linqEntryDepth = 0
    var angleDepth = 0
    var braceDepth = 0
    var parenDepth = 0
    var bracketDepth = 0
    var justEndedAttribute = false
    val switchBodyBraceDepths = ArrayList<Int>()
    var pendingSwitchHeader = false
    var pendingSwitchParenDepth = 0

    var i = 0
    while (i < visible.size) {
        val tok = raw[visible[i]]
        val prev = if (i > 0) raw[visible[i - 1]] else null
        val next = if (i + 1 < visible.size) raw[visible[i + 1]] else null
        val wasAfterAttribute = justEndedAttribute
        justEndedAttribute = false

        val totalDepth = braceDepth + parenDepth + bracketDepth

        if (tok.type == CSharpTokens.SEMI) {
            inNamespaceOrUsing = false
            expectingDeclaredTypeName = false
            inGenericConstraint = false
            inPattern = false
            inCaseLabel = false
            inLinqQuery = false
            angleDepth = 0
        }

        if (inPattern && totalDepth < patternEntryDepth) {
            inPattern = false
        }
        if (inPattern && totalDepth == patternEntryDepth && (tok.type == CSharpTokens.AND || tok.type == CSharpTokens.OR || tok.type == CSharpTokens.LAMBDA_ARROW || tok.type == CSharpTokens.QUESTION)) {
            inPattern = false
        }
        if (inLinqQuery && totalDepth < linqEntryDepth) {
            inLinqQuery = false
        }

        when (tok.type) {
            CSharpTokens.NAMESPACE,
            CSharpTokens.USING,
            -> if (prev?.type != CSharpTokens.DOT && prev?.type != CSharpTokens.COLONCOLON) {
                inNamespaceOrUsing = true
            }

            CSharpTokens.CLASS,
            CSharpTokens.STRUCT,
            CSharpTokens.INTERFACE,
            CSharpTokens.ENUM,
            -> {
                if (prev?.type != CSharpTokens.DOT && prev?.type != CSharpTokens.COLONCOLON) {
                    expectingDeclaredTypeName = true
                }
            }

            CSharpTokens.SWITCH -> {
                pendingSwitchHeader = true
                pendingSwitchParenDepth = parenDepth
            }

            CSharpTokens.LPAREN -> parenDepth++
            CSharpTokens.RPAREN -> {
                if (parenDepth > 0) parenDepth--
            }

            CSharpTokens.LBRACE -> {
                braceDepth++
                angleDepth = 0
                inGenericConstraint = false
                if (pendingSwitchHeader && parenDepth == pendingSwitchParenDepth) {
                    switchBodyBraceDepths.add(braceDepth)
                    pendingSwitchHeader = false
                }
            }

            CSharpTokens.RBRACE -> {
                if (switchBodyBraceDepths.lastOrNull() == braceDepth) {
                    switchBodyBraceDepths.removeAt(switchBodyBraceDepths.lastIndex)
                }
                if (braceDepth > 0) braceDepth--
                angleDepth = 0
                inCaseLabel = false
            }

            CSharpTokens.LT -> if (hasMatchingCSharpAngleClose(raw, visible, i)) angleDepth++
            CSharpTokens.GT -> if (angleDepth > 0) angleDepth--
            CSharpTokens.RSHIFT -> angleDepth = (angleDepth - 2).coerceAtLeast(0)
            CSharpTokens.URSHIFT -> angleDepth = (angleDepth - 3).coerceAtLeast(0)

            CSharpTokens.IS -> {
                inPattern = true
                patternEntryDepth = totalDepth
            }

            CSharpTokens.CASE -> {
                inCaseLabel = true
                caseLabelEntryDepth = totalDepth
                inPattern = true
                patternEntryDepth = totalDepth
            }

            CSharpTokens.COLON -> {
                if (inCaseLabel && totalDepth == caseLabelEntryDepth) {
                    inCaseLabel = false
                    inPattern = false
                }
            }

            CSharpTokens.LAMBDA_ARROW -> {
                if (inCaseLabel && totalDepth == caseLabelEntryDepth) {
                    inCaseLabel = false
                }
                inPattern = false
            }
        }

        // Attribute lists: `[Serializable]`, `[HttpGet("api/v1")]`, `[assembly: AssemblyVersion("1.0")]`
        if (tok.type == CSharpTokens.LBRACK) {
            val attrEnd = findCSharpAttributeListEnd(code, raw, visible, i, prev, wasAfterAttribute)
            if (attrEnd > i) {
                classifyCSharpAttributeList(code, raw, visible, i, attrEnd)
                justEndedAttribute = true
                i = attrEnd + 1
                continue
            }
            bracketDepth++
            i++
            continue
        }

        if (tok.type == CSharpTokens.RBRACK) {
            if (bracketDepth > 0) bracketDepth--
            i++
            continue
        }

        if (tok.type == CSharpTokens.VERBATIM_IDENT) {
            val text = code.substring(tok.start, tok.end)
            if (expectingDeclaredTypeName) {
                tok.kind = SyntaxTokenKind.Type
                expectingDeclaredTypeName = false
            } else {
                tok.kind = classifyCSharpIdentifier(
                    text = text.substring(1),
                    raw = raw,
                    visible = visible,
                    index = i,
                    prev = prev,
                    next = next,
                    inNamespaceOrUsing = inNamespaceOrUsing,
                    angleDepth = angleDepth,
                )
            }
            i++
            continue
        }

        if (tok.type == CSharpTokens.IDENT) {
            val text = code.substring(tok.start, tok.end)
            val inSwitchExprArm = switchBodyBraceDepths.lastOrNull() == braceDepth && isInsideSwitchArmPattern(raw, visible, i)

            if (text == "from" &&
                prev?.type != CSharpTokens.DOT &&
                prev?.type != CSharpTokens.COLONCOLON &&
                isLinqFromQueryStart(raw, visible, i)
            ) {
                inLinqQuery = true
                linqEntryDepth = totalDepth
            }

            val contextualKind = classifyCSharpContextualKeyword(
                text = text,
                code = code,
                raw = raw,
                visible = visible,
                index = i,
                prev = prev,
                next = next,
                wasAfterAttribute = wasAfterAttribute,
                inCaseLabel = inCaseLabel,
                inPattern = inPattern || inSwitchExprArm,
                inGenericConstraint = inGenericConstraint,
                inLinqQuery = inLinqQuery && totalDepth == linqEntryDepth,
            )
            if (contextualKind != null) {
                tok.kind = contextualKind
                when (text) {
                    "record" -> if (next?.type != CSharpTokens.CLASS && next?.type != CSharpTokens.STRUCT) {
                        expectingDeclaredTypeName = true
                    }
                    "where" -> if (isGenericConstraintWhere(raw, visible, i)) {
                        inGenericConstraint = true
                    }
                    "using" -> inNamespaceOrUsing = true
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

            tok.kind = classifyCSharpIdentifier(
                text = text,
                raw = raw,
                visible = visible,
                index = i,
                prev = prev,
                next = next,
                inNamespaceOrUsing = inNamespaceOrUsing,
                angleDepth = angleDepth,
            )
        }

        i++
    }
}

private fun findCSharpAttributeListEnd(
    code: String,
    raw: List<RawCSharpToken>,
    visible: List<Int>,
    openIndex: Int,
    prev: RawCSharpToken?,
    wasAfterAttribute: Boolean,
): Int {
    if (!wasAfterAttribute && prev != null) {
        when (prev.type) {
            CSharpTokens.LBRACE,
            CSharpTokens.RBRACE,
            CSharpTokens.SEMI,
            CSharpTokens.LPAREN,
            CSharpTokens.COMMA,
            CSharpTokens.LT,
            -> Unit
            else -> return -1
        }
    }

    val firstInside = if (openIndex + 1 < visible.size) raw[visible[openIndex + 1]] else return -1
    val firstIsValidStart = firstInside.type == CSharpTokens.IDENT ||
        firstInside.type == CSharpTokens.VERBATIM_IDENT ||
        firstInside.type == CSharpTokens.RETURN ||
        firstInside.type == CSharpTokens.EVENT
    if (!firstIsValidStart) return -1

    var paren = 0
    var bracket = 1
    val limit = minOf(visible.size, openIndex + 80)
    var closeIndex = -1
    for (j in openIndex + 1 until limit) {
        when (raw[visible[j]].type) {
            CSharpTokens.LPAREN -> paren++
            CSharpTokens.RPAREN -> {
                if (paren == 0) return -1
                paren--
            }
            CSharpTokens.LBRACK -> bracket++
            CSharpTokens.RBRACK -> {
                bracket--
                if (bracket == 0) {
                    closeIndex = j
                    break
                }
            }
            CSharpTokens.SEMI,
            CSharpTokens.LBRACE,
            CSharpTokens.RBRACE,
            -> if (paren == 0) return -1
        }
    }
    if (closeIndex <= openIndex + 1) return -1

    // Validate target specifier (`[assembly: ...]`) if `:` appears at depth 0 inside `[...]`.
    if (openIndex + 2 < closeIndex && raw[visible[openIndex + 2]].type == CSharpTokens.COLON) {
        val targetText = code.substring(firstInside.start, firstInside.end)
        if (targetText !in CSHARP_ATTRIBUTE_TARGETS) return -1
    }

    val after = if (closeIndex + 1 < visible.size) raw[visible[closeIndex + 1]] else return -1
    val afterText = if (after.type == CSharpTokens.IDENT) code.substring(after.start, after.end) else ""
    val validFollower = after.type == CSharpTokens.LBRACK ||
        after.type == CSharpTokens.LPAREN ||
        after.type == CSharpTokens.VOID ||
        after.type == CSharpTokens.THIS ||
        isCSharpHardKeyword(after.type) ||
        isCSharpPrimitiveType(after.type) ||
        after.type == CSharpTokens.IDENT ||
        after.type == CSharpTokens.VERBATIM_IDENT ||
        afterText in CSHARP_DECLARATION_CONTEXTUAL_MODIFIERS
    if (!validFollower) return -1
    // Reject obvious expression followers (`[a, b];`, `([a, b])`, `[a, b],`).
    if (after.type == CSharpTokens.SEMI ||
        after.type == CSharpTokens.COMMA ||
        after.type == CSharpTokens.RPAREN ||
        after.type == CSharpTokens.RBRACK ||
        after.type == CSharpTokens.RBRACE
    ) {
        return -1
    }
    return closeIndex
}

private fun classifyCSharpAttributeList(
    code: String,
    raw: List<RawCSharpToken>,
    visible: List<Int>,
    openIndex: Int,
    closeIndex: Int,
) {
    var j = openIndex + 1
    if (j + 1 < closeIndex && raw[visible[j + 1]].type == CSharpTokens.COLON) {
        val targetTok = raw[visible[j]]
        val targetText = code.substring(targetTok.start, targetTok.end)
        if (targetText in CSHARP_ATTRIBUTE_TARGETS) {
            targetTok.kind = SyntaxTokenKind.Keyword
            j += 2
        }
    }

    var paren = 0
    var angle = 0
    while (j < closeIndex) {
        val tok = raw[visible[j]]
        val next = if (j + 1 < closeIndex) raw[visible[j + 1]] else null
        when (tok.type) {
            CSharpTokens.LPAREN -> paren++
            CSharpTokens.RPAREN -> if (paren > 0) paren--
            CSharpTokens.LT -> if (paren == 0) angle++
            CSharpTokens.GT -> if (paren == 0 && angle > 0) angle--
            CSharpTokens.IDENT,
            CSharpTokens.VERBATIM_IDENT,
            -> {
                if (paren == 0 && angle == 0) {
                    tok.kind = SyntaxTokenKind.Annotation
                } else if (next?.type == CSharpTokens.ASSIGN || next?.type == CSharpTokens.COLON) {
                    tok.kind = SyntaxTokenKind.Property
                } else if (tok.type == CSharpTokens.IDENT) {
                    val t = code.substring(tok.start, tok.end)
                    if (t == "nameof" && next?.type == CSharpTokens.LPAREN) {
                        tok.kind = SyntaxTokenKind.Keyword
                    } else if (looksLikeTypeName(t)) {
                        tok.kind = SyntaxTokenKind.Type
                    }
                }
            }
        }
        j++
    }
}

private fun classifyCSharpContextualKeyword(
    text: String,
    code: String,
    raw: List<RawCSharpToken>,
    visible: List<Int>,
    index: Int,
    prev: RawCSharpToken?,
    next: RawCSharpToken?,
    wasAfterAttribute: Boolean,
    inCaseLabel: Boolean,
    inPattern: Boolean,
    inGenericConstraint: Boolean,
    inLinqQuery: Boolean,
): SyntaxTokenKind? {
    if (prev?.type == CSharpTokens.DOT || prev?.type == CSharpTokens.COLONCOLON) return null
    val nextText = if (next?.type == CSharpTokens.IDENT) code.substring(next.start, next.end) else null
    val next2 = if (index + 2 < visible.size) raw[visible[index + 2]] else null

    return when (text) {
        "var" -> {
            val validPrev = prev == null || wasAfterAttribute || prev.type in CSHARP_VAR_PREDECESSORS ||
                (prev.type == CSharpTokens.IDENT && code.substring(prev.start, prev.end) == "await")
            val validNext = next?.type == CSharpTokens.IDENT || next?.type == CSharpTokens.VERBATIM_IDENT || next?.type == CSharpTokens.LPAREN
            if (validPrev && validNext) SyntaxTokenKind.Keyword else null
        }

        "dynamic",
        "nint",
        "nuint",
        -> {
            if (prev?.kind == SyntaxTokenKind.Type) return null
            if (next?.type == CSharpTokens.ASSIGN || next?.type == CSharpTokens.SEMI || next?.type == CSharpTokens.COMMA && prev?.type != CSharpTokens.LT) {
                return null
            }
            SyntaxTokenKind.Type
        }

        "global" -> {
            if (next?.type == CSharpTokens.COLONCOLON || (isDeclStart(prev, wasAfterAttribute) && next?.type == CSharpTokens.USING)) {
                SyntaxTokenKind.Keyword
            } else {
                null
            }
        }

        "async" -> {
            val validPrev = prev == null || wasAfterAttribute || isDeclOrExprStart(prev) || isCSharpDeclModifier(prev, code)
            val validNext = next != null && (
                isCSharpDeclModifier(next, code) ||
                    isCSharpPrimitiveType(next.type) ||
                    next.type == CSharpTokens.DELEGATE ||
                    next.type == CSharpTokens.LPAREN ||
                    next.type == CSharpTokens.IDENT ||
                    next.type == CSharpTokens.VERBATIM_IDENT
                ) && next.type != CSharpTokens.ASSIGN && next.type != CSharpTokens.SEMI
            if (validPrev && validNext && prev?.kind != SyntaxTokenKind.Type) SyntaxTokenKind.Keyword else null
        }

        "await" -> {
            if (prev?.kind == SyntaxTokenKind.Type) return null
            val validNext = next != null && !isCSharpAssignmentOrTerminator(next.type) && next.type != CSharpTokens.LPAREN.takeIf { next2?.type == CSharpTokens.RPAREN }
            if (validNext) SyntaxTokenKind.Keyword else null
        }

        "record" -> {
            if (isDeclStart(prev, wasAfterAttribute) && (
                    next?.type == CSharpTokens.CLASS ||
                        next?.type == CSharpTokens.STRUCT ||
                        next?.type == CSharpTokens.IDENT ||
                        next?.type == CSharpTokens.VERBATIM_IDENT
                    )
            ) {
                SyntaxTokenKind.Keyword
            } else {
                null
            }
        }

        "partial" -> {
            if (isDeclStart(prev, wasAfterAttribute) && (
                    next?.type == CSharpTokens.CLASS ||
                        next?.type == CSharpTokens.STRUCT ||
                        next?.type == CSharpTokens.INTERFACE ||
                        next?.type == CSharpTokens.VOID ||
                        isCSharpPrimitiveType(next?.type ?: -1) ||
                        next?.type == CSharpTokens.IDENT ||
                        next?.type == CSharpTokens.VERBATIM_IDENT ||
                        nextText == "record"
                    )
            ) {
                SyntaxTokenKind.Keyword
            } else {
                null
            }
        }

        "required",
        "file",
        "scoped",
        -> {
            val validPrev = isDeclStart(prev, wasAfterAttribute) || prev?.type == CSharpTokens.LPAREN || prev?.type == CSharpTokens.COMMA
            val validNext = next != null && !isCSharpAssignmentOrTerminator(next.type) && next.type != CSharpTokens.LPAREN
            if (validPrev && validNext && prev?.kind != SyntaxTokenKind.Type) SyntaxTokenKind.Keyword else null
        }

        "get",
        "set",
        "init",
        "add",
        "remove",
        -> {
            val isAccessorPrev = prev == null || wasAfterAttribute ||
                prev.type == CSharpTokens.LBRACE ||
                prev.type == CSharpTokens.RBRACE ||
                prev.type == CSharpTokens.SEMI ||
                prev.type == CSharpTokens.PRIVATE ||
                prev.type == CSharpTokens.PROTECTED ||
                prev.type == CSharpTokens.INTERNAL ||
                prev.type == CSharpTokens.PUBLIC
            val isAccessorNext = next?.type == CSharpTokens.LBRACE || next?.type == CSharpTokens.SEMI || next?.type == CSharpTokens.LAMBDA_ARROW
            if (isAccessorPrev && isAccessorNext) SyntaxTokenKind.Keyword else null
        }

        "yield" -> if (next?.type == CSharpTokens.RETURN || next?.type == CSharpTokens.BREAK) SyntaxTokenKind.Keyword else null

        "nameof" -> if (next?.type == CSharpTokens.LPAREN && prev?.kind != SyntaxTokenKind.Type) SyntaxTokenKind.Keyword else null

        "when" -> if (inCaseLabel || isCatchWhen(raw, visible, index) || isSwitchArmWhen(raw, visible, index)) SyntaxTokenKind.Keyword else null

        "where" -> if (inLinqQuery || isGenericConstraintWhere(raw, visible, index)) SyntaxTokenKind.Keyword else null

        "with" -> if (next?.type == CSharpTokens.LBRACE && prev?.kind != SyntaxTokenKind.Type) SyntaxTokenKind.Keyword else null

        "managed",
        "unmanaged",
        -> if (inGenericConstraint || prev?.type == CSharpTokens.STAR) SyntaxTokenKind.Keyword else null

        "notnull" -> if (inGenericConstraint) SyntaxTokenKind.Keyword else null

        "not" -> if ((inPattern || prev?.type == CSharpTokens.IS || prev?.type == CSharpTokens.CASE) && next != null && !isCSharpAssignmentOrTerminator(next.type)) SyntaxTokenKind.Keyword else null

        "and",
        "or",
        -> if (inPattern && prev != null && next != null && !isCSharpAssignmentOrTerminator(next.type)) SyntaxTokenKind.Keyword else null

        "from",
        "select",
        "orderby",
        "ascending",
        "descending",
        "group",
        "by",
        "into",
        "join",
        "on",
        "equals",
        "let",
        -> if (inLinqQuery && prev?.kind != SyntaxTokenKind.Type) SyntaxTokenKind.Keyword else null

        else -> null
    }
}

private fun classifyCSharpIdentifier(
    text: String,
    raw: List<RawCSharpToken>,
    visible: List<Int>,
    index: Int,
    prev: RawCSharpToken?,
    next: RawCSharpToken?,
    inNamespaceOrUsing: Boolean,
    angleDepth: Int,
): SyntaxTokenKind {
    if (inNamespaceOrUsing) {
        return if (looksLikeTypeName(text)) SyntaxTokenKind.Type else SyntaxTokenKind.Plain
    }

    val prevType = prev?.type
    val nextType = next?.type

    if (prevType == CSharpTokens.DOT || prevType == CSharpTokens.ARROW) {
        if (nextType == CSharpTokens.LPAREN || (nextType == CSharpTokens.LT && hasMatchingCSharpAngleClose(raw, visible, index + 1))) {
            return SyntaxTokenKind.Function
        }
        return if (looksLikeTypeName(text) && nextType == CSharpTokens.DOT) {
            SyntaxTokenKind.Type
        } else {
            SyntaxTokenKind.Property
        }
    }

    if (prevType == CSharpTokens.COLONCOLON) {
        return if (looksLikeTypeName(text)) SyntaxTokenKind.Type else SyntaxTokenKind.Plain
    }

    if (nextType == CSharpTokens.LPAREN) {
        if (prevType == CSharpTokens.NEW && looksLikeTypeName(text)) {
            return SyntaxTokenKind.Type
        }
        return SyntaxTokenKind.Function
    }

    if (nextType == CSharpTokens.LT && hasMatchingCSharpAngleClose(raw, visible, index + 1)) {
        val afterClose = findAfterMatchingCSharpAngle(raw, visible, index + 1)
        if (afterClose?.type == CSharpTokens.LPAREN && prevType != CSharpTokens.NEW) {
            return SyntaxTokenKind.Function
        }
        if (looksLikeTypeName(text)) {
            return SyntaxTokenKind.Type
        }
    }

    if (prev?.kind == SyntaxTokenKind.Type) {
        return SyntaxTokenKind.Plain
    }

    if (text.length == 1 && text[0].isUpperCase() && (angleDepth > 0 || prevType == CSharpTokens.LT || prevType == CSharpTokens.COMMA)) {
        return SyntaxTokenKind.Type
    }

    if (looksLikeTypeName(text)) {
        return SyntaxTokenKind.Type
    }

    return SyntaxTokenKind.Plain
}

private fun isLinqFromQueryStart(raw: List<RawCSharpToken>, visible: List<Int>, fromIndex: Int): Boolean {
    val prev = if (fromIndex > 0) raw[visible[fromIndex - 1]] else null
    if (prev != null && prev.type !in CSHARP_EXPR_PREDECESSORS) return false
    val limit = minOf(visible.size, fromIndex + 10)
    var sawIdent = false
    for (j in fromIndex + 1 until limit) {
        val t = raw[visible[j]].type
        if (t == CSharpTokens.IN) return sawIdent
        if (t == CSharpTokens.SEMI || t == CSharpTokens.LBRACE || t == CSharpTokens.RBRACE || t == CSharpTokens.ASSIGN) return false
        if (t == CSharpTokens.IDENT || t == CSharpTokens.VERBATIM_IDENT || isCSharpPrimitiveType(t)) {
            sawIdent = true
        }
    }
    return false
}

private fun isGenericConstraintWhere(raw: List<RawCSharpToken>, visible: List<Int>, whereIndex: Int): Boolean {
    val next = if (whereIndex + 1 < visible.size) raw[visible[whereIndex + 1]] else return false
    val next2 = if (whereIndex + 2 < visible.size) raw[visible[whereIndex + 2]] else return false
    return (next.type == CSharpTokens.IDENT || next.type == CSharpTokens.VERBATIM_IDENT) && next2.type == CSharpTokens.COLON
}

private fun isCatchWhen(raw: List<RawCSharpToken>, visible: List<Int>, whenIndex: Int): Boolean {
    var depth = 0
    var j = whenIndex - 1
    val floor = (whenIndex - 24).coerceAtLeast(0)
    while (j >= floor) {
        when (raw[visible[j]].type) {
            CSharpTokens.RPAREN -> depth++
            CSharpTokens.LPAREN -> if (depth > 0) depth-- else return false
            CSharpTokens.CATCH -> if (depth == 0) return true
            CSharpTokens.SEMI,
            CSharpTokens.LBRACE,
            CSharpTokens.RBRACE,
            -> return false
        }
        j--
    }
    return false
}

private fun isSwitchArmWhen(raw: List<RawCSharpToken>, visible: List<Int>, whenIndex: Int): Boolean {
    var depth = 0
    val limit = minOf(visible.size, whenIndex + 32)
    for (j in whenIndex + 1 until limit) {
        when (raw[visible[j]].type) {
            CSharpTokens.LPAREN,
            CSharpTokens.LBRACK,
            CSharpTokens.LBRACE,
            -> depth++
            CSharpTokens.RPAREN,
            CSharpTokens.RBRACK,
            CSharpTokens.RBRACE,
            -> if (depth > 0) depth-- else return false
            CSharpTokens.LAMBDA_ARROW -> if (depth == 0) return true
            CSharpTokens.SEMI -> if (depth == 0) return false
        }
    }
    return false
}

private fun isInsideSwitchArmPattern(raw: List<RawCSharpToken>, visible: List<Int>, index: Int): Boolean {
    var depth = 0
    val limit = minOf(visible.size, index + 24)
    for (j in index + 1 until limit) {
        when (raw[visible[j]].type) {
            CSharpTokens.LPAREN,
            CSharpTokens.LBRACK,
            CSharpTokens.LBRACE,
            -> depth++
            CSharpTokens.RPAREN,
            CSharpTokens.RBRACK,
            CSharpTokens.RBRACE,
            -> if (depth > 0) depth-- else return false
            CSharpTokens.LAMBDA_ARROW -> if (depth == 0) return true
            CSharpTokens.SEMI,
            CSharpTokens.COMMA,
            -> if (depth == 0) return false
        }
    }
    return false
}

private fun hasMatchingCSharpAngleClose(
    raw: List<RawCSharpToken>,
    visible: List<Int>,
    openIndex: Int,
): Boolean = findAfterMatchingCSharpAngleIndex(raw, visible, openIndex) >= 0

private fun findAfterMatchingCSharpAngle(
    raw: List<RawCSharpToken>,
    visible: List<Int>,
    openIndex: Int,
): RawCSharpToken? {
    val closeIdx = findAfterMatchingCSharpAngleIndex(raw, visible, openIndex)
    return if (closeIdx >= 0 && closeIdx + 1 < visible.size) raw[visible[closeIdx + 1]] else null
}

private fun findAfterMatchingCSharpAngleIndex(
    raw: List<RawCSharpToken>,
    visible: List<Int>,
    openIndex: Int,
): Int {
    if (openIndex >= visible.size || raw[visible[openIndex]].type != CSharpTokens.LT) return -1
    var depth = 1
    var nested = 0
    val limit = minOf(visible.size, openIndex + 65)
    for (j in openIndex + 1 until limit) {
        when (raw[visible[j]].type) {
            CSharpTokens.LPAREN,
            CSharpTokens.LBRACK,
            -> nested++
            CSharpTokens.RPAREN,
            CSharpTokens.RBRACK,
            -> {
                if (nested == 0) return -1
                nested--
            }
            CSharpTokens.SEMI,
            CSharpTokens.LBRACE,
            CSharpTokens.RBRACE,
            -> return -1
            CSharpTokens.ASSIGN,
            CSharpTokens.EQ,
            CSharpTokens.NE,
            CSharpTokens.AND,
            CSharpTokens.OR,
            CSharpTokens.RETURN,
            CSharpTokens.IF,
            CSharpTokens.FOR,
            CSharpTokens.WHILE,
            CSharpTokens.SWITCH,
            -> if (nested == 0) return -1
            CSharpTokens.LT -> if (nested == 0) depth++
            CSharpTokens.GT -> if (nested == 0) {
                depth--
                if (depth <= 0) return j
            }
            CSharpTokens.RSHIFT -> if (nested == 0) {
                depth -= 2
                if (depth <= 0) return j
            }
            CSharpTokens.URSHIFT -> if (nested == 0) {
                depth -= 3
                if (depth <= 0) return j
            }
        }
    }
    return -1
}

private fun isDeclStart(prev: RawCSharpToken?, wasAfterAttribute: Boolean): Boolean {
    if (prev == null || wasAfterAttribute) return true
    return when (prev.type) {
        CSharpTokens.LBRACE,
        CSharpTokens.RBRACE,
        CSharpTokens.SEMI,
        CSharpTokens.PUBLIC,
        CSharpTokens.PRIVATE,
        CSharpTokens.INTERNAL,
        CSharpTokens.PROTECTED,
        CSharpTokens.STATIC,
        CSharpTokens.SEALED,
        CSharpTokens.ABSTRACT,
        CSharpTokens.VIRTUAL,
        CSharpTokens.OVERRIDE,
        CSharpTokens.READONLY,
        CSharpTokens.UNSAFE,
        CSharpTokens.EXTERN,
        CSharpTokens.NEW,
        CSharpTokens.REF,
        CSharpTokens.CONST,
        CSharpTokens.IDENT,
        -> true
        else -> false
    }
}

private fun isDeclOrExprStart(prev: RawCSharpToken?): Boolean {
    if (prev == null) return true
    return prev.type in CSHARP_EXPR_PREDECESSORS || prev.type == CSharpTokens.RBRACE || isCSharpHardKeyword(prev.type)
}

private fun isCSharpDeclModifier(tok: RawCSharpToken, code: String): Boolean {
    if (tok.type == CSharpTokens.IDENT) {
        return code.substring(tok.start, tok.end) in CSHARP_DECLARATION_CONTEXTUAL_MODIFIERS
    }
    return when (tok.type) {
        CSharpTokens.PUBLIC,
        CSharpTokens.PRIVATE,
        CSharpTokens.INTERNAL,
        CSharpTokens.PROTECTED,
        CSharpTokens.STATIC,
        CSharpTokens.SEALED,
        CSharpTokens.ABSTRACT,
        CSharpTokens.VIRTUAL,
        CSharpTokens.OVERRIDE,
        CSharpTokens.READONLY,
        CSharpTokens.UNSAFE,
        CSharpTokens.EXTERN,
        CSharpTokens.NEW,
        CSharpTokens.VOLATILE,
        CSharpTokens.REF,
        CSharpTokens.CONST,
        -> true
        else -> false
    }
}

private fun isCSharpAssignmentOrTerminator(type: Int): Boolean = when (type) {
    CSharpTokens.ASSIGN,
    CSharpTokens.ADD_ASSIGN,
    CSharpTokens.SUB_ASSIGN,
    CSharpTokens.MUL_ASSIGN,
    CSharpTokens.DIV_ASSIGN,
    CSharpTokens.MOD_ASSIGN,
    CSharpTokens.AND_ASSIGN,
    CSharpTokens.OR_ASSIGN,
    CSharpTokens.XOR_ASSIGN,
    CSharpTokens.LSHIFT_ASSIGN,
    CSharpTokens.RSHIFT_ASSIGN,
    CSharpTokens.URSHIFT_ASSIGN,
    CSharpTokens.NULL_COALESCE_ASSIGN,
    CSharpTokens.SEMI,
    CSharpTokens.COMMA,
    CSharpTokens.RPAREN,
    CSharpTokens.RBRACK,
    CSharpTokens.RBRACE,
    CSharpTokens.DOT,
    -> true
    else -> false
}

private fun isCSharpPrimitiveType(type: Int): Boolean = when (type) {
    CSharpTokens.BOOL,
    CSharpTokens.BYTE,
    CSharpTokens.SBYTE,
    CSharpTokens.CHAR,
    CSharpTokens.DECIMAL,
    CSharpTokens.DOUBLE,
    CSharpTokens.FLOAT,
    CSharpTokens.INT,
    CSharpTokens.UINT,
    CSharpTokens.LONG,
    CSharpTokens.ULONG,
    CSharpTokens.OBJECT,
    CSharpTokens.SHORT,
    CSharpTokens.USHORT,
    CSharpTokens.STRING,
    CSharpTokens.VOID,
    -> true
    else -> false
}

private fun isCSharpHardKeyword(type: Int): Boolean = when (type) {
    CSharpTokens.ABSTRACT,
    CSharpTokens.AS,
    CSharpTokens.BASE,
    CSharpTokens.BREAK,
    CSharpTokens.CASE,
    CSharpTokens.CATCH,
    CSharpTokens.CHECKED,
    CSharpTokens.CLASS,
    CSharpTokens.CONST,
    CSharpTokens.CONTINUE,
    CSharpTokens.DEFAULT,
    CSharpTokens.DELEGATE,
    CSharpTokens.DO,
    CSharpTokens.ELSE,
    CSharpTokens.ENUM,
    CSharpTokens.EVENT,
    CSharpTokens.EXPLICIT,
    CSharpTokens.EXTERN,
    CSharpTokens.FINALLY,
    CSharpTokens.FIXED,
    CSharpTokens.FOR,
    CSharpTokens.FOREACH,
    CSharpTokens.GOTO,
    CSharpTokens.IF,
    CSharpTokens.IMPLICIT,
    CSharpTokens.IN,
    CSharpTokens.INTERFACE,
    CSharpTokens.INTERNAL,
    CSharpTokens.IS,
    CSharpTokens.LOCK,
    CSharpTokens.NAMESPACE,
    CSharpTokens.NEW,
    CSharpTokens.OPERATOR,
    CSharpTokens.OUT,
    CSharpTokens.OVERRIDE,
    CSharpTokens.PARAMS,
    CSharpTokens.PRIVATE,
    CSharpTokens.PROTECTED,
    CSharpTokens.PUBLIC,
    CSharpTokens.READONLY,
    CSharpTokens.REF,
    CSharpTokens.RETURN,
    CSharpTokens.SEALED,
    CSharpTokens.SIZEOF,
    CSharpTokens.STACKALLOC,
    CSharpTokens.STATIC,
    CSharpTokens.STRUCT,
    CSharpTokens.SWITCH,
    CSharpTokens.THIS,
    CSharpTokens.THROW,
    CSharpTokens.TRY,
    CSharpTokens.TYPEOF,
    CSharpTokens.UNCHECKED,
    CSharpTokens.UNSAFE,
    CSharpTokens.USING,
    CSharpTokens.VIRTUAL,
    CSharpTokens.VOLATILE,
    CSharpTokens.WHILE,
    -> true
    else -> false
}

private fun isCSharpOperator(type: Int): Boolean = when (type) {
    CSharpTokens.URSHIFT_ASSIGN,
    CSharpTokens.RSHIFT_ASSIGN,
    CSharpTokens.LSHIFT_ASSIGN,
    CSharpTokens.NULL_COALESCE_ASSIGN,
    CSharpTokens.LAMBDA_ARROW,
    CSharpTokens.NULL_COALESCE,
    CSharpTokens.DOTDOT,
    CSharpTokens.ARROW,
    CSharpTokens.URSHIFT,
    CSharpTokens.RSHIFT,
    CSharpTokens.LSHIFT,
    CSharpTokens.INC,
    CSharpTokens.DEC,
    CSharpTokens.AND,
    CSharpTokens.OR,
    CSharpTokens.EQ,
    CSharpTokens.NE,
    CSharpTokens.LE,
    CSharpTokens.GE,
    CSharpTokens.ADD_ASSIGN,
    CSharpTokens.SUB_ASSIGN,
    CSharpTokens.MUL_ASSIGN,
    CSharpTokens.DIV_ASSIGN,
    CSharpTokens.MOD_ASSIGN,
    CSharpTokens.AND_ASSIGN,
    CSharpTokens.OR_ASSIGN,
    CSharpTokens.XOR_ASSIGN,
    CSharpTokens.ASSIGN,
    CSharpTokens.GT,
    CSharpTokens.LT,
    CSharpTokens.BANG,
    CSharpTokens.TILDE,
    CSharpTokens.QUESTION,
    CSharpTokens.PLUS,
    CSharpTokens.MINUS,
    CSharpTokens.STAR,
    CSharpTokens.SLASH,
    CSharpTokens.BITAND,
    CSharpTokens.BITOR,
    CSharpTokens.CARET,
    CSharpTokens.MOD,
    -> true
    else -> false
}

private fun isCSharpPunctuation(type: Int): Boolean = when (type) {
    CSharpTokens.COLONCOLON,
    CSharpTokens.LPAREN,
    CSharpTokens.RPAREN,
    CSharpTokens.LBRACE,
    CSharpTokens.RBRACE,
    CSharpTokens.LBRACK,
    CSharpTokens.RBRACK,
    CSharpTokens.SEMI,
    CSharpTokens.COMMA,
    CSharpTokens.DOT,
    CSharpTokens.COLON,
    -> true
    else -> false
}

private val CSHARP_ATTRIBUTE_TARGETS = setOf(
    "assembly",
    "module",
    "field",
    "event",
    "method",
    "param",
    "property",
    "return",
    "type",
    "typevar",
)

private val CSHARP_DECLARATION_CONTEXTUAL_MODIFIERS = setOf(
    "async",
    "partial",
    "required",
    "file",
    "scoped",
    "record",
)

private val CSHARP_VAR_PREDECESSORS = setOf(
    CSharpTokens.LBRACE,
    CSharpTokens.RBRACE,
    CSharpTokens.SEMI,
    CSharpTokens.LPAREN,
    CSharpTokens.COMMA,
    CSharpTokens.COLON,
    CSharpTokens.LAMBDA_ARROW,
    CSharpTokens.OUT,
    CSharpTokens.USING,
    CSharpTokens.REF,
)

private val CSHARP_EXPR_PREDECESSORS = setOf(
    CSharpTokens.LBRACE,
    CSharpTokens.SEMI,
    CSharpTokens.LPAREN,
    CSharpTokens.LBRACK,
    CSharpTokens.COMMA,
    CSharpTokens.ASSIGN,
    CSharpTokens.LAMBDA_ARROW,
    CSharpTokens.RETURN,
    CSharpTokens.QUESTION,
    CSharpTokens.COLON,
    CSharpTokens.NULL_COALESCE,
)
