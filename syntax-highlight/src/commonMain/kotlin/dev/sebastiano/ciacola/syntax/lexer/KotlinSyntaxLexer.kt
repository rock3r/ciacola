package dev.sebastiano.ciacola.syntax.lexer

import dev.sebastiano.ciacola.syntax.SyntaxLanguage
import dev.sebastiano.ciacola.syntax.SyntaxLexer
import dev.sebastiano.ciacola.syntax.SyntaxToken
import dev.sebastiano.ciacola.syntax.SyntaxTokenKind
import dev.sebastiano.ciacola.syntax.antlr.kotlin.KotlinLexer
import dev.sebastiano.ciacola.syntax.antlr.kotlin.KotlinLexer.Tokens as KotlinTokens
import dev.sebastiano.ciacola.syntax.internal.driveAntlrLexer
import dev.sebastiano.ciacola.syntax.internal.looksLikeTypeName
import org.antlr.v4.kotlinruntime.CharStreams

/**
 * Lossless ANTLR4 DFA-based lexical tokenizer for Kotlin source code.
 */
object KotlinSyntaxLexer : SyntaxLexer {
    override val language: SyntaxLanguage = SyntaxLanguage.Kotlin

    override fun lex(source: String): List<SyntaxToken> {
        if (source.isEmpty()) return emptyList()

        val lexer = KotlinLexer(CharStreams.fromString(source))
        val raw = ArrayList<RawKotlinToken>(source.length / 3 + 8)

        driveAntlrLexer(
            source = source,
            lexer = lexer,
            onGap = { start, end -> emitHiddenOrGap(source, start, end, raw) },
            onToken = { type, start, end -> emitKotlinRawToken(source, type, start, end, raw) },
        )

        classifyKotlinRawTokens(source, raw)
        return raw.map { SyntaxToken(kind = it.kind, startIndex = it.start, endIndex = it.end) }
    }
}

private class RawKotlinToken(
    val type: Int,
    val start: Int,
    var end: Int,
    var kind: SyntaxTokenKind,
    val isHidden: Boolean,
    val isNewline: Boolean = false,
)

private fun emitHiddenOrGap(code: String, start: Int, end: Int, out: MutableList<RawKotlinToken>) {
    if (start >= end) return
    val isComment = (code[start] == '/' && start + 1 < end && (code[start + 1] == '/' || code[start + 1] == '*'))
    val hasNewline = !isComment && (start until end).any { code[it] == '\n' || code[it] == '\r' }
    val allWs = !isComment && (start until end).all { code[it].isWhitespace() }
    val kind = when {
        isComment -> SyntaxTokenKind.Comment
        allWs -> SyntaxTokenKind.Whitespace
        else -> SyntaxTokenKind.Invalid
    }
    out += RawKotlinToken(
        type = if (isComment) KotlinTokens.LineComment else if (hasNewline) KotlinTokens.NL else KotlinTokens.WS,
        start = start,
        end = end,
        kind = kind,
        isHidden = isComment || allWs,
        isNewline = hasNewline,
    )
}

private fun emitKotlinRawToken(
    code: String,
    type: Int,
    start: Int,
    end: Int,
    out: MutableList<RawKotlinToken>,
) {
    when (type) {
        KotlinTokens.WS,
        KotlinTokens.Inside_WS,
        -> out += RawKotlinToken(type, start, end, SyntaxTokenKind.Whitespace, isHidden = true)

        KotlinTokens.NL,
        KotlinTokens.Inside_NL,
        -> out += RawKotlinToken(type, start, end, SyntaxTokenKind.Whitespace, isHidden = true, isNewline = true)

        KotlinTokens.DelimitedComment -> {
            val prev = out.lastOrNull()
            if (prev != null && prev.type == KotlinTokens.DelimitedComment && prev.end == start) {
                prev.end = end
            } else {
                out += RawKotlinToken(type, start, end, SyntaxTokenKind.Comment, isHidden = true)
            }
        }

        KotlinTokens.LineComment,
        KotlinTokens.ShebangLine,
        -> out += RawKotlinToken(type, start, end, SyntaxTokenKind.Comment, isHidden = true)

        KotlinTokens.EXCL_WS -> {
            out += RawKotlinToken(KotlinTokens.EXCL_NO_WS, start, start + 1, SyntaxTokenKind.Operator, isHidden = false)
            emitHiddenOrGap(code, start + 1, end, out)
        }

        KotlinTokens.QUEST_WS -> {
            out += RawKotlinToken(KotlinTokens.QUEST_NO_WS, start, start + 1, SyntaxTokenKind.Punctuation, isHidden = false)
            emitHiddenOrGap(code, start + 1, end, out)
        }

        KotlinTokens.NOT_IS,
        KotlinTokens.NOT_IN,
        -> {
            val kwEnd = (start + 3).coerceAtMost(end)
            out += RawKotlinToken(type, start, kwEnd, SyntaxTokenKind.Keyword, isHidden = false)
            emitHiddenOrGap(code, kwEnd, end, out)
        }

        KotlinTokens.RETURN_AT,
        KotlinTokens.CONTINUE_AT,
        KotlinTokens.BREAK_AT,
        KotlinTokens.THIS_AT,
        KotlinTokens.SUPER_AT,
        -> {
            var atPos = start
            while (atPos < end && code[atPos] != '@') atPos++
            if (atPos > start) {
                out += RawKotlinToken(type, start, atPos, SyntaxTokenKind.Keyword, isHidden = false)
            }
            if (atPos < end) {
                out += RawKotlinToken(KotlinTokens.AT_NO_WS, atPos, end, SyntaxTokenKind.Annotation, isHidden = false)
            }
        }

        KotlinTokens.LineStrRef,
        KotlinTokens.MultiLineStrRef,
        -> {
            var dollarEnd = start
            while (dollarEnd < end && code[dollarEnd] == '$') dollarEnd++
            if (dollarEnd > start) {
                out += RawKotlinToken(type, start, dollarEnd, SyntaxTokenKind.Keyword, isHidden = false)
            }
            if (dollarEnd < end) {
                out += RawKotlinToken(KotlinTokens.Identifier, dollarEnd, end, SyntaxTokenKind.Plain, isHidden = false)
            }
        }

        KotlinTokens.LineStrEscapedChar -> {
            out += RawKotlinToken(type, start, end, SyntaxTokenKind.Constant, isHidden = false)
        }

        KotlinTokens.LineStrExprStart,
        KotlinTokens.MultiLineStrExprStart,
        -> {
            out += RawKotlinToken(type, start, end, SyntaxTokenKind.Punctuation, isHidden = false)
        }

        KotlinTokens.MULTI_DOLLAR_QUOTE_OPEN,
        KotlinTokens.MULTI_DOLLAR_TRIPLE_QUOTE_OPEN,
        KotlinTokens.QUOTE_OPEN,
        KotlinTokens.QUOTE_CLOSE,
        KotlinTokens.LineStrText,
        KotlinTokens.TRIPLE_QUOTE_OPEN,
        KotlinTokens.TRIPLE_QUOTE_CLOSE,
        KotlinTokens.MultiLineStringQuote,
        KotlinTokens.MultiLineStrText,
        KotlinTokens.CharacterLiteral,
        -> {
            out += RawKotlinToken(type, start, end, SyntaxTokenKind.String, isHidden = false)
        }

        KotlinTokens.IntegerLiteral,
        KotlinTokens.HexLiteral,
        KotlinTokens.BinLiteral,
        KotlinTokens.RealLiteral,
        KotlinTokens.FloatLiteral,
        KotlinTokens.DoubleLiteral,
        KotlinTokens.LongLiteral,
        KotlinTokens.UnsignedLiteral,
        -> {
            out += RawKotlinToken(type, start, end, SyntaxTokenKind.Number, isHidden = false)
        }

        KotlinTokens.BooleanLiteral,
        KotlinTokens.NullLiteral,
        -> {
            out += RawKotlinToken(type, start, end, SyntaxTokenKind.Constant, isHidden = false)
        }

        KotlinTokens.ErrorCharacter -> {
            out += RawKotlinToken(type, start, end, SyntaxTokenKind.Invalid, isHidden = false)
        }

        else -> {
            val initialKind = when {
                isKotlinHardKeyword(type) -> SyntaxTokenKind.Keyword
                isKotlinOperator(type) -> SyntaxTokenKind.Operator
                isKotlinPunctuation(type) -> SyntaxTokenKind.Punctuation
                type == KotlinTokens.AT_NO_WS -> SyntaxTokenKind.Annotation
                else -> SyntaxTokenKind.Plain
            }
            out += RawKotlinToken(type, start, end, initialKind, isHidden = false)
        }
    }
}

private fun classifyKotlinRawTokens(code: String, raw: List<RawKotlinToken>) {
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

    var inPackageOrImport = false
    var angleDepth = 0
    var i = 0
    while (i < visible.size) {
        val tok = raw[visible[i]]
        val prev = if (i > 0) raw[visible[i - 1]] else null
        val next = if (i + 1 < visible.size) raw[visible[i + 1]] else null
        val lineStart = precededByNewline[i]

        if (lineStart || tok.type == KotlinTokens.SEMICOLON) {
            inPackageOrImport = false
        }
        when (tok.type) {
            KotlinTokens.PACKAGE, KotlinTokens.IMPORT -> inPackageOrImport = true
            KotlinTokens.AS -> inPackageOrImport = false
            KotlinTokens.SEMICOLON, KotlinTokens.LCURL, KotlinTokens.RCURL -> angleDepth = 0
            KotlinTokens.LANGLE -> if (hasMatchingKotlinAngleClose(raw, visible, i)) angleDepth++
            KotlinTokens.RANGLE -> if (angleDepth > 0) angleDepth--
        }

        if (tok.type == KotlinTokens.AT_NO_WS && tok.end - tok.start == 1) {
            val isTrailingLabel = prev != null &&
                prev.end == tok.start &&
                isKotlinIdentifierLike(prev.type)
            if (isTrailingLabel) {
                prev.kind = SyntaxTokenKind.Annotation
                tok.kind = SyntaxTokenKind.Annotation
                i++
                continue
            }
            if (next != null && isKotlinIdentifierLike(next.type)) {
                tok.kind = SyntaxTokenKind.Annotation
                var j = i + 1
                if (
                    isKotlinUseSiteTarget(raw[visible[j]].type) &&
                    j + 1 < visible.size &&
                    raw[visible[j + 1]].type == KotlinTokens.COLON
                ) {
                    raw[visible[j]].kind = SyntaxTokenKind.Annotation
                    raw[visible[j + 1]].kind = SyntaxTokenKind.Annotation
                    j += 2
                }
                while (j < visible.size && isKotlinIdentifierLike(raw[visible[j]].type)) {
                    raw[visible[j]].kind = SyntaxTokenKind.Annotation
                    j++
                    if (
                        j < visible.size &&
                        raw[visible[j]].type == KotlinTokens.DOT &&
                        j + 1 < visible.size &&
                        isKotlinIdentifierLike(raw[visible[j + 1]].type)
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
        }

        if (tok.type == KotlinTokens.Identifier) {
            val text = code.substring(tok.start, tok.end)
            if (text == "this" && prev?.type != KotlinTokens.DOT) {
                tok.kind = SyntaxTokenKind.Keyword
                i++
                continue
            }
            if (text == "class" && prev?.type == KotlinTokens.COLONCOLON) {
                tok.kind = SyntaxTokenKind.Keyword
                i++
                continue
            }
        }

        if (isKotlinSoftKeyword(tok.type)) {
            val nextOnNewLine = i + 1 < visible.size && precededByNewline[i + 1]
            if (isSoftKeywordActingAsKeyword(tok.type, prev, next, lineStart, nextOnNewLine, angleDepth)) {
                tok.kind = SyntaxTokenKind.Keyword
                i++
                continue
            }
        }

        if (isKotlinIdentifierLike(tok.type) && tok.kind == SyntaxTokenKind.Plain) {
            val text = code.substring(tok.start, tok.end)
            tok.kind = classifyKotlinIdentifier(
                text = text,
                prev = prev,
                next = next,
                inPackageOrImport = inPackageOrImport,
                angleDepth = angleDepth,
            )
        }

        i++
    }
}

private fun hasMatchingKotlinAngleClose(
    raw: List<RawKotlinToken>,
    visible: List<Int>,
    openIndex: Int,
): Boolean {
    var depth = 1
    var nestedParens = 0
    val limit = minOf(visible.size, openIndex + 65)
    for (j in openIndex + 1 until limit) {
        when (raw[visible[j]].type) {
            KotlinTokens.LPAREN,
            KotlinTokens.LSQUARE,
            -> nestedParens++

            KotlinTokens.RPAREN,
            KotlinTokens.RSQUARE,
            -> {
                if (nestedParens == 0) return false
                nestedParens--
            }

            KotlinTokens.SEMICOLON,
            KotlinTokens.LCURL,
            KotlinTokens.RCURL,
            -> return false

            KotlinTokens.ASSIGNMENT,
            KotlinTokens.EQEQ,
            KotlinTokens.EQEQEQ,
            KotlinTokens.EXCL_EQ,
            KotlinTokens.EXCL_EQEQ,
            KotlinTokens.CONJ,
            KotlinTokens.DISJ,
            KotlinTokens.RETURN,
            KotlinTokens.IF,
            KotlinTokens.FOR,
            KotlinTokens.WHILE,
            KotlinTokens.WHEN,
            KotlinTokens.VAL,
            KotlinTokens.VAR,
            KotlinTokens.FUN,
            KotlinTokens.CLASS,
            KotlinTokens.INTERFACE,
            KotlinTokens.OBJECT,
            -> if (nestedParens == 0) return false

            KotlinTokens.LANGLE -> if (nestedParens == 0) depth++
            KotlinTokens.RANGLE -> if (nestedParens == 0) {
                depth--
                if (depth == 0) return true
            }
        }
    }
    return false
}

private fun isSoftKeywordActingAsKeyword(
    type: Int,
    prev: RawKotlinToken?,
    next: RawKotlinToken?,
    lineStart: Boolean,
    nextOnNewLine: Boolean,
    angleDepth: Int,
): Boolean {
    if (prev != null && isNonModifierPredecessor(prev.type)) {
        return false
    }
    return when (type) {
        KotlinTokens.GET,
        KotlinTokens.SET,
        -> (lineStart || prev == null || prev.kind == SyntaxTokenKind.Keyword || prev.type == KotlinTokens.SEMICOLON || prev.type == KotlinTokens.RCURL) &&
            (next?.type == KotlinTokens.LPAREN || next?.type == KotlinTokens.ASSIGNMENT)

        KotlinTokens.INIT -> next?.type == KotlinTokens.LCURL
        KotlinTokens.CONSTRUCTOR -> next?.type == KotlinTokens.LPAREN
        KotlinTokens.COMPANION -> next?.type == KotlinTokens.OBJECT
        KotlinTokens.CATCH -> next?.type == KotlinTokens.LPAREN
        KotlinTokens.FINALLY -> next?.type == KotlinTokens.LCURL
        KotlinTokens.OUT -> angleDepth > 0 && next != null && (isKotlinIdentifierLike(next.type) || next.type == KotlinTokens.MULT)
        KotlinTokens.CONTEXT -> next?.type == KotlinTokens.LPAREN
        KotlinTokens.BY,
        KotlinTokens.WHERE,
        -> next != null && !isValueBoundary(next.type)

        KotlinTokens.SUSPEND -> next != null && (
            isKotlinDeclarationOrModifierStart(next.type) ||
                next.type == KotlinTokens.LPAREN ||
                next.type == KotlinTokens.LCURL ||
                (prev?.type == KotlinTokens.COLON && !nextOnNewLine && isKotlinIdentifierLike(next.type))
            )

        KotlinTokens.VARARG,
        KotlinTokens.NOINLINE,
        KotlinTokens.CROSSINLINE,
        KotlinTokens.REIFIED,
        -> next != null && !nextOnNewLine && !isValueBoundary(next.type) && next.type != KotlinTokens.LPAREN

        KotlinTokens.FILE,
        KotlinTokens.FIELD,
        KotlinTokens.PROPERTY,
        KotlinTokens.RECEIVER,
        KotlinTokens.PARAM,
        KotlinTokens.SETPARAM,
        KotlinTokens.DELEGATE,
        -> false

        else -> {
            next != null && isKotlinDeclarationOrModifierStart(next.type)
        }
    }
}

private fun isNonModifierPredecessor(type: Int): Boolean = when (type) {
    KotlinTokens.DOT,
    KotlinTokens.COLONCOLON,
    KotlinTokens.VAL,
    KotlinTokens.VAR,
    KotlinTokens.FUN,
    KotlinTokens.ASSIGNMENT,
    KotlinTokens.ADD_ASSIGNMENT,
    KotlinTokens.SUB_ASSIGNMENT,
    KotlinTokens.MULT_ASSIGNMENT,
    KotlinTokens.DIV_ASSIGNMENT,
    KotlinTokens.MOD_ASSIGNMENT,
    KotlinTokens.RETURN,
    KotlinTokens.THROW,
    KotlinTokens.ADD,
    KotlinTokens.SUB,
    KotlinTokens.MULT,
    KotlinTokens.DIV,
    KotlinTokens.MOD,
    KotlinTokens.EQEQ,
    KotlinTokens.EXCL_EQ,
    KotlinTokens.EQEQEQ,
    KotlinTokens.EXCL_EQEQ,
    KotlinTokens.CONJ,
    KotlinTokens.DISJ,
    KotlinTokens.ARROW,
    KotlinTokens.IS,
    KotlinTokens.NOT_IS,
    KotlinTokens.IN,
    KotlinTokens.NOT_IN,
    KotlinTokens.AS,
    KotlinTokens.AS_SAFE,
    KotlinTokens.RANGE,
    KotlinTokens.RANGE_UNTIL,
    -> true
    else -> false
}

private fun isKotlinDeclarationOrModifierStart(type: Int): Boolean = when (type) {
    KotlinTokens.FUN,
    KotlinTokens.VAL,
    KotlinTokens.VAR,
    KotlinTokens.CLASS,
    KotlinTokens.INTERFACE,
    KotlinTokens.OBJECT,
    KotlinTokens.TYPE_ALIAS,
    KotlinTokens.CONSTRUCTOR,
    KotlinTokens.COMPANION,
    KotlinTokens.INIT,
    KotlinTokens.GET,
    KotlinTokens.SET,
    KotlinTokens.AT_NO_WS,
    -> true
    else -> isKotlinSoftKeyword(type) && !isKotlinUseSiteTarget(type)
}

private fun isValueBoundary(type: Int): Boolean = when (type) {
    KotlinTokens.ASSIGNMENT,
    KotlinTokens.ADD_ASSIGNMENT,
    KotlinTokens.SUB_ASSIGNMENT,
    KotlinTokens.MULT_ASSIGNMENT,
    KotlinTokens.DIV_ASSIGNMENT,
    KotlinTokens.MOD_ASSIGNMENT,
    KotlinTokens.DOT,
    KotlinTokens.COMMA,
    KotlinTokens.COLON,
    KotlinTokens.SEMICOLON,
    KotlinTokens.RPAREN,
    KotlinTokens.RSQUARE,
    KotlinTokens.RCURL,
    KotlinTokens.ARROW,
    KotlinTokens.EQEQ,
    KotlinTokens.EXCL_EQ,
    KotlinTokens.EQEQEQ,
    KotlinTokens.EXCL_EQEQ,
    KotlinTokens.ADD,
    KotlinTokens.SUB,
    KotlinTokens.MULT,
    KotlinTokens.DIV,
    KotlinTokens.MOD,
    KotlinTokens.CONJ,
    KotlinTokens.DISJ,
    KotlinTokens.QUEST_NO_WS,
    KotlinTokens.QUEST_WS,
    -> true
    else -> false
}

private fun classifyKotlinIdentifier(
    text: String,
    prev: RawKotlinToken?,
    next: RawKotlinToken?,
    inPackageOrImport: Boolean,
    angleDepth: Int,
): SyntaxTokenKind {
    if (text.isEmpty()) return SyntaxTokenKind.Plain

    if (inPackageOrImport) {
        return if (looksLikeTypeName(text)) SyntaxTokenKind.Type else SyntaxTokenKind.Plain
    }

    val prevType = prev?.type
    val nextType = next?.type

    if (
        prevType == KotlinTokens.CLASS ||
        prevType == KotlinTokens.INTERFACE ||
        prevType == KotlinTokens.OBJECT ||
        prevType == KotlinTokens.TYPE_ALIAS
    ) {
        return SyntaxTokenKind.Type
    }

    if (prevType == KotlinTokens.FUN && nextType != KotlinTokens.DOT) {
        return SyntaxTokenKind.Function
    }

    if (prevType == KotlinTokens.COLON && looksLikeTypeName(text)) {
        return SyntaxTokenKind.Type
    }

    if (nextType == KotlinTokens.LPAREN && prevType != KotlinTokens.CONSTRUCTOR) {
        return SyntaxTokenKind.Function
    }

    if (
        nextType == KotlinTokens.ASSIGNMENT &&
        (prevType == KotlinTokens.LPAREN || prevType == KotlinTokens.COMMA)
    ) {
        return SyntaxTokenKind.Property
    }

    if (prevType == KotlinTokens.DOT || prevType == KotlinTokens.COLONCOLON) {
        return SyntaxTokenKind.Property
    }

    if (
        text.length == 1 &&
        text[0].isUpperCase() &&
        (angleDepth > 0 || prevType == KotlinTokens.COLON || prevType == KotlinTokens.REIFIED || prevType == KotlinTokens.AS || prevType == KotlinTokens.IS)
    ) {
        return SyntaxTokenKind.Type
    }

    if (looksLikeTypeName(text)) {
        return SyntaxTokenKind.Type
    }
    if (text.length > 1 && text[0].isUpperCase() && text.all { it.isUpperCase() || it.isDigit() || it == '_' }) {
        return SyntaxTokenKind.Property
    }
    return SyntaxTokenKind.Plain
}

private fun isKotlinHardKeyword(type: Int): Boolean = when (type) {
    KotlinTokens.PACKAGE,
    KotlinTokens.IMPORT,
    KotlinTokens.CLASS,
    KotlinTokens.INTERFACE,
    KotlinTokens.FUN,
    KotlinTokens.OBJECT,
    KotlinTokens.VAL,
    KotlinTokens.VAR,
    KotlinTokens.TYPE_ALIAS,
    KotlinTokens.THIS,
    KotlinTokens.SUPER,
    KotlinTokens.TYPEOF,
    KotlinTokens.IF,
    KotlinTokens.ELSE,
    KotlinTokens.WHEN,
    KotlinTokens.TRY,
    KotlinTokens.FOR,
    KotlinTokens.DO,
    KotlinTokens.WHILE,
    KotlinTokens.THROW,
    KotlinTokens.RETURN,
    KotlinTokens.CONTINUE,
    KotlinTokens.BREAK,
    KotlinTokens.AS,
    KotlinTokens.AS_SAFE,
    KotlinTokens.IS,
    KotlinTokens.IN,
    -> true
    else -> false
}

private fun isKotlinSoftKeyword(type: Int): Boolean = when (type) {
    KotlinTokens.CONSTRUCTOR,
    KotlinTokens.BY,
    KotlinTokens.COMPANION,
    KotlinTokens.INIT,
    KotlinTokens.WHERE,
    KotlinTokens.CATCH,
    KotlinTokens.FINALLY,
    KotlinTokens.OUT,
    KotlinTokens.DYNAMIC,
    KotlinTokens.PUBLIC,
    KotlinTokens.PRIVATE,
    KotlinTokens.PROTECTED,
    KotlinTokens.INTERNAL,
    KotlinTokens.ENUM,
    KotlinTokens.SEALED,
    KotlinTokens.ANNOTATION,
    KotlinTokens.DATA,
    KotlinTokens.INNER,
    KotlinTokens.VALUE,
    KotlinTokens.TAILREC,
    KotlinTokens.OPERATOR,
    KotlinTokens.INLINE,
    KotlinTokens.INFIX,
    KotlinTokens.EXTERNAL,
    KotlinTokens.SUSPEND,
    KotlinTokens.OVERRIDE,
    KotlinTokens.ABSTRACT,
    KotlinTokens.FINAL,
    KotlinTokens.OPEN,
    KotlinTokens.CONST,
    KotlinTokens.LATEINIT,
    KotlinTokens.VARARG,
    KotlinTokens.NOINLINE,
    KotlinTokens.CROSSINLINE,
    KotlinTokens.REIFIED,
    KotlinTokens.EXPECT,
    KotlinTokens.ACTUAL,
    KotlinTokens.CONTEXT,
    KotlinTokens.GET,
    KotlinTokens.SET,
    KotlinTokens.FILE,
    KotlinTokens.FIELD,
    KotlinTokens.PROPERTY,
    KotlinTokens.RECEIVER,
    KotlinTokens.PARAM,
    KotlinTokens.SETPARAM,
    KotlinTokens.DELEGATE,
    -> true
    else -> false
}

private fun isKotlinUseSiteTarget(type: Int): Boolean = when (type) {
    KotlinTokens.FILE,
    KotlinTokens.FIELD,
    KotlinTokens.PROPERTY,
    KotlinTokens.GET,
    KotlinTokens.SET,
    KotlinTokens.RECEIVER,
    KotlinTokens.PARAM,
    KotlinTokens.SETPARAM,
    KotlinTokens.DELEGATE,
    -> true
    else -> false
}

private fun isKotlinIdentifierLike(type: Int): Boolean =
    type == KotlinTokens.Identifier || type == KotlinTokens.IdentifierOrSoftKey || isKotlinSoftKeyword(type)

private fun isKotlinOperator(type: Int): Boolean = when (type) {
    KotlinTokens.MULT,
    KotlinTokens.MOD,
    KotlinTokens.DIV,
    KotlinTokens.ADD,
    KotlinTokens.SUB,
    KotlinTokens.INCR,
    KotlinTokens.DECR,
    KotlinTokens.CONJ,
    KotlinTokens.DISJ,
    KotlinTokens.EXCL_NO_WS,
    KotlinTokens.ASSIGNMENT,
    KotlinTokens.ADD_ASSIGNMENT,
    KotlinTokens.SUB_ASSIGNMENT,
    KotlinTokens.MULT_ASSIGNMENT,
    KotlinTokens.DIV_ASSIGNMENT,
    KotlinTokens.MOD_ASSIGNMENT,
    KotlinTokens.ARROW,
    KotlinTokens.DOUBLE_ARROW,
    KotlinTokens.RANGE,
    KotlinTokens.RANGE_UNTIL,
    KotlinTokens.LANGLE,
    KotlinTokens.RANGLE,
    KotlinTokens.LE,
    KotlinTokens.GE,
    KotlinTokens.EXCL_EQ,
    KotlinTokens.EXCL_EQEQ,
    KotlinTokens.EQEQ,
    KotlinTokens.EQEQEQ,
    KotlinTokens.AMP,
    -> true
    else -> false
}

private fun isKotlinPunctuation(type: Int): Boolean = when (type) {
    KotlinTokens.RESERVED,
    KotlinTokens.DOT,
    KotlinTokens.COMMA,
    KotlinTokens.LPAREN,
    KotlinTokens.RPAREN,
    KotlinTokens.LSQUARE,
    KotlinTokens.RSQUARE,
    KotlinTokens.LCURL,
    KotlinTokens.RCURL,
    KotlinTokens.COLON,
    KotlinTokens.SEMICOLON,
    KotlinTokens.COLONCOLON,
    KotlinTokens.DOUBLE_SEMICOLON,
    KotlinTokens.HASH,
    KotlinTokens.QUEST_NO_WS,
    KotlinTokens.SINGLE_QUOTE,
    -> true
    else -> false
}
