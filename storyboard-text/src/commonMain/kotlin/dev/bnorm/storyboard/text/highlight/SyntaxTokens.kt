package dev.bnorm.storyboard.text.highlight

import dev.bnorm.storyboard.text.highlight.antlr.kotlin.KotlinLexer
import dev.bnorm.storyboard.text.highlight.antlr.kotlin.KotlinLexer.Tokens as KotlinTokens
import dev.bnorm.storyboard.text.highlight.antlr.xml.XMLLexer
import dev.bnorm.storyboard.text.highlight.antlr.xml.XMLLexer.Tokens as XmlTokens
import org.antlr.v4.kotlinruntime.CharStreams
import org.antlr.v4.kotlinruntime.Token as AntlrToken

/**
 * Lexical span into an input source string with a semantic [SyntaxTokenKind].
 *
 * Character offsets [startIndex] (inclusive) and [endIndex] (exclusive) index directly into the
 * original input string passed to [lexKotlinSyntax], [lexXmlSyntax], or [lexJsonSyntax].
 */
data class SyntaxToken(
    val kind: SyntaxTokenKind,
    val startIndex: Int,
    val endIndex: Int,
)

enum class SyntaxTokenKind {
    Plain,
    Whitespace,
    Keyword,
    String,
    Comment,
    Number,
    Constant,
    Function,
    Type,
    Property,
    Annotation,
    Attribute,
    Tag,
    Punctuation,
    Invalid,
}

/**
 * Tokenizes Kotlin source code into lossless, non-overlapping [SyntaxToken] spans using the
 * ANTLR4 [KotlinLexer] DFA (zero regular expressions) plus a single linear contextual pass.
 */
fun lexKotlinSyntax(code: String): List<SyntaxToken> {
    if (code.isEmpty()) return emptyList()

    val lexer = KotlinLexer(CharStreams.fromString(code))
    lexer.removeErrorListeners()

    val raw = ArrayList<RawKotlinToken>(code.length / 3 + 8)
    var cursor = 0

    fun emitGap(upTo: Int) {
        if (upTo <= cursor) return
        emitHiddenOrGap(code, cursor, upTo, raw)
        cursor = upTo
    }

    while (true) {
        val t = lexer.nextToken()
        if (t.type == AntlrToken.EOF) break
        val start = t.startIndex
        val end = t.stopIndex + 1
        if (start < cursor || end <= start || end > code.length) continue
        emitGap(start)
        emitKotlinRawToken(code, t.type, start, end, raw)
        cursor = end
    }
    emitGap(code.length)

    classifyKotlinRawTokens(code, raw)
    return raw.map { SyntaxToken(kind = it.kind, startIndex = it.start, endIndex = it.end) }
}

/**
 * Tokenizes XML source code into lossless, non-overlapping [SyntaxToken] spans using the
 * ANTLR4 [XMLLexer] mode-switched DFA (zero regular expressions).
 */
fun lexXmlSyntax(code: String): List<SyntaxToken> {
    if (code.isEmpty()) return emptyList()

    val lexer = XMLLexer(CharStreams.fromString(code))
    lexer.removeErrorListeners()

    val out = ArrayList<SyntaxToken>(code.length / 4 + 8)
    var cursor = 0
    var expectingTagName = false

    fun emitGap(upTo: Int) {
        if (upTo <= cursor) return
        emitXmlGap(code, cursor, upTo, out)
        cursor = upTo
    }

    while (true) {
        val t = lexer.nextToken()
        if (t.type == AntlrToken.EOF) break
        val start = t.startIndex
        val end = t.stopIndex + 1
        if (start < cursor || end <= start || end > code.length) continue
        emitGap(start)

        when (t.type) {
            XmlTokens.OPEN -> {
                out += SyntaxToken(SyntaxTokenKind.Punctuation, start, end)
                expectingTagName = true
            }
            XmlTokens.SLASH -> {
                out += SyntaxToken(SyntaxTokenKind.Punctuation, start, end)
            }
            XmlTokens.XMLDeclOpen -> {
                // `<?xml` followed by mandatory whitespace `S`
                val declEnd = (start + 5).coerceAtMost(end)
                out += SyntaxToken(SyntaxTokenKind.Keyword, start, declEnd)
                if (declEnd < end) {
                    out += SyntaxToken(SyntaxTokenKind.Whitespace, declEnd, end)
                }
                expectingTagName = false
            }
            XmlTokens.CLOSE,
            XmlTokens.SLASH_CLOSE,
            XmlTokens.SPECIAL_CLOSE,
            -> {
                out += SyntaxToken(SyntaxTokenKind.Punctuation, start, end)
                expectingTagName = false
            }
            XmlTokens.EQUALS -> {
                out += SyntaxToken(SyntaxTokenKind.Punctuation, start, end)
            }
            XmlTokens.Name -> {
                val kind = if (expectingTagName) SyntaxTokenKind.Tag else SyntaxTokenKind.Attribute
                out += SyntaxToken(kind, start, end)
                expectingTagName = false
            }
            XmlTokens.STRING -> out += SyntaxToken(SyntaxTokenKind.String, start, end)
            XmlTokens.COMMENT -> out += SyntaxToken(SyntaxTokenKind.Comment, start, end)
            XmlTokens.CDATA -> out += SyntaxToken(SyntaxTokenKind.String, start, end)
            XmlTokens.PI -> out += SyntaxToken(SyntaxTokenKind.Keyword, start, end)
            XmlTokens.EntityRef,
            XmlTokens.CharRef,
            -> out += SyntaxToken(SyntaxTokenKind.Constant, start, end)
            XmlTokens.SEA_WS,
            XmlTokens.S,
            -> out += SyntaxToken(SyntaxTokenKind.Whitespace, start, end)
            XmlTokens.TEXT -> out += SyntaxToken(SyntaxTokenKind.Plain, start, end)
            XmlTokens.DTD -> out += SyntaxToken(SyntaxTokenKind.Keyword, start, end)
            else -> out += SyntaxToken(SyntaxTokenKind.Invalid, start, end)
        }
        cursor = end
    }
    emitGap(code.length)
    return out
}

/**
 * Tokenizes JSON (and JSONC comments) into lossless, non-overlapping [SyntaxToken] spans using a
 * single-pass character state machine (zero regular expressions).
 */
fun lexJsonSyntax(code: String): List<SyntaxToken> {
    if (code.isEmpty()) return emptyList()

    val out = ArrayList<SyntaxToken>(code.length / 4 + 8)
    val n = code.length
    var i = 0

    while (i < n) {
        val ch = code[i]
        when {
            ch == ' ' || ch == '\t' || ch == '\r' || ch == '\n' -> {
                val start = i
                i++
                while (i < n && (code[i] == ' ' || code[i] == '\t' || code[i] == '\r' || code[i] == '\n')) i++
                out += SyntaxToken(SyntaxTokenKind.Whitespace, start, i)
            }

            ch == '/' && i + 1 < n && code[i + 1] == '/' -> {
                val start = i
                i += 2
                while (i < n && code[i] != '\n' && code[i] != '\r') i++
                out += SyntaxToken(SyntaxTokenKind.Comment, start, i)
            }

            ch == '/' && i + 1 < n && code[i + 1] == '*' -> {
                val start = i
                i += 2
                while (i < n) {
                    if (code[i] == '*' && i + 1 < n && code[i + 1] == '/') {
                        i += 2
                        break
                    }
                    i++
                }
                out += SyntaxToken(SyntaxTokenKind.Comment, start, i)
            }

            ch == '"' -> {
                val start = i
                i++
                while (i < n) {
                    val c = code[i]
                    if (c == '\\' && i + 1 < n) {
                        i += 2
                    } else if (c == '"') {
                        i++
                        break
                    } else if (c == '\n' || c == '\r') {
                        break
                    } else {
                        i++
                    }
                }
                val kind = if (isJsonPropertyKey(code, i)) SyntaxTokenKind.Property else SyntaxTokenKind.String
                out += SyntaxToken(kind, start, i)
            }

            ch == '{' || ch == '}' || ch == '[' || ch == ']' || ch == ':' || ch == ',' -> {
                out += SyntaxToken(SyntaxTokenKind.Punctuation, i, i + 1)
                i++
            }

            ch.isDigit() || (ch == '-' && i + 1 < n && code[i + 1].isDigit()) -> {
                val start = i
                if (ch == '-') i++
                while (i < n && code[i].isDigit()) i++
                if (i < n && code[i] == '.' && i + 1 < n && code[i + 1].isDigit()) {
                    i++
                    while (i < n && code[i].isDigit()) i++
                }
                if (i < n && (code[i] == 'e' || code[i] == 'E')) {
                    var exp = i + 1
                    if (exp < n && (code[exp] == '+' || code[exp] == '-')) exp++
                    if (exp < n && code[exp].isDigit()) {
                        i = exp + 1
                        while (i < n && code[i].isDigit()) i++
                    }
                }
                out += SyntaxToken(SyntaxTokenKind.Number, start, i)
            }

            ch.isLetter() -> {
                val start = i
                i++
                while (i < n && (code[i].isLetterOrDigit() || code[i] == '_')) i++
                val word = code.substring(start, i)
                val kind = if (word == "true" || word == "false" || word == "null") {
                    SyntaxTokenKind.Constant
                } else {
                    SyntaxTokenKind.Invalid
                }
                out += SyntaxToken(kind, start, i)
            }

            else -> {
                out += SyntaxToken(SyntaxTokenKind.Invalid, i, i + 1)
                i++
            }
        }
    }
    return out
}

// ── JSON helpers ──────────────────────────────────────────────────────────────────────────────

private fun isJsonPropertyKey(code: String, afterQuote: Int): Boolean {
    val n = code.length
    var j = afterQuote
    while (j < n) {
        val c = code[j]
        when {
            c == ' ' || c == '\t' || c == '\r' || c == '\n' -> j++
            c == '/' && j + 1 < n && code[j + 1] == '/' -> {
                j += 2
                while (j < n && code[j] != '\n' && code[j] != '\r') j++
            }
            c == '/' && j + 1 < n && code[j + 1] == '*' -> {
                j += 2
                while (j < n) {
                    if (code[j] == '*' && j + 1 < n && code[j + 1] == '/') {
                        j += 2
                        break
                    }
                    j++
                }
            }
            else -> return c == ':'
        }
    }
    return false
}

// ── XML helpers ───────────────────────────────────────────────────────────────────────────────

private fun emitXmlGap(code: String, start: Int, end: Int, out: MutableList<SyntaxToken>) {
    var i = start
    while (i < end) {
        val ch = code[i]
        if (ch == ' ' || ch == '\t' || ch == '\r' || ch == '\n') {
            val wsStart = i
            i++
            while (i < end && (code[i] == ' ' || code[i] == '\t' || code[i] == '\r' || code[i] == '\n')) i++
            out += SyntaxToken(SyntaxTokenKind.Whitespace, wsStart, i)
        } else if (ch == '<' && i + 1 < end && code[i + 1] == '!') {
            val dtdStart = i
            var j = i + 2
            while (j < end && code[j] != '>') j++
            i = if (j < end) j + 1 else end
            out += SyntaxToken(SyntaxTokenKind.Keyword, dtdStart, i)
        } else {
            val badStart = i
            i++
            while (
                i < end &&
                code[i] != ' ' && code[i] != '\t' && code[i] != '\r' && code[i] != '\n' &&
                !(code[i] == '<' && i + 1 < end && code[i + 1] == '!')
            ) {
                i++
            }
            out += SyntaxToken(SyntaxTokenKind.Invalid, badStart, i)
        }
    }
}

// ── Kotlin helpers ────────────────────────────────────────────────────────────────────────────

private class RawKotlinToken(
    val type: Int,
    val start: Int,
    val end: Int,
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

        KotlinTokens.LineComment,
        KotlinTokens.DelimitedComment,
        KotlinTokens.Inside_Comment,
        KotlinTokens.ShebangLine,
        -> out += RawKotlinToken(type, start, end, SyntaxTokenKind.Comment, isHidden = true)

        // Composite tokens carrying an operator/keyword plus trailing or surrounding hidden/newline spans
        KotlinTokens.EXCL_WS -> {
            out += RawKotlinToken(KotlinTokens.EXCL_NO_WS, start, start + 1, SyntaxTokenKind.Punctuation, isHidden = false)
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

        KotlinTokens.AT_POST_WS -> {
            out += RawKotlinToken(KotlinTokens.AT_NO_WS, start, start + 1, SyntaxTokenKind.Annotation, isHidden = false)
            emitHiddenOrGap(code, start + 1, end, out)
        }

        KotlinTokens.AT_PRE_WS -> {
            val atPos = end - 1
            emitHiddenOrGap(code, start, atPos, out)
            out += RawKotlinToken(KotlinTokens.AT_NO_WS, atPos, end, SyntaxTokenKind.Annotation, isHidden = false)
        }

        KotlinTokens.AT_BOTH_WS -> {
            val atPos = findAtPositionInBothWs(code, start, end)
            emitHiddenOrGap(code, start, atPos, out)
            out += RawKotlinToken(KotlinTokens.AT_NO_WS, atPos, atPos + 1, SyntaxTokenKind.Annotation, isHidden = false)
            emitHiddenOrGap(code, atPos + 1, end, out)
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
            out += RawKotlinToken(type, start, start + 1, SyntaxTokenKind.Keyword, isHidden = false)
            if (start + 1 < end) {
                out += RawKotlinToken(KotlinTokens.Identifier, start + 1, end, SyntaxTokenKind.Plain, isHidden = false)
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
                isKotlinPunctuation(type) -> SyntaxTokenKind.Punctuation
                type == KotlinTokens.AT_NO_WS -> SyntaxTokenKind.Annotation
                else -> SyntaxTokenKind.Plain
            }
            out += RawKotlinToken(type, start, end, initialKind, isHidden = false)
        }
    }
}

private fun findAtPositionInBothWs(code: String, start: Int, end: Int): Int {
    var i = start
    while (i < end) {
        if (code[i] == '/' && i + 1 < end && code[i + 1] == '*') {
            i += 2
            var depth = 1
            while (i < end && depth > 0) {
                if (code[i] == '/' && i + 1 < end && code[i + 1] == '*') {
                    depth++
                    i += 2
                } else if (code[i] == '*' && i + 1 < end && code[i + 1] == '/') {
                    depth--
                    i += 2
                } else {
                    i++
                }
            }
        } else if (code[i] == '@') {
            return i
        } else {
            i++
        }
    }
    return end - 1
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
            KotlinTokens.LANGLE -> angleDepth++
            KotlinTokens.RANGLE -> if (angleDepth > 0) angleDepth--
        }

        // Annotation chain: `@Foo`, `@pkg.Foo`, `@file:JvmName`, `@get:Composable`
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
                // Optional use-site target (`@file:`, `@get:`, `@param:`, ...)
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

        // `this` / `::class` lexed as Identifier inside `Inside` mode (`(...)` / `[...]`)
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

        // Soft & modifier keywords
        if (isKotlinSoftKeyword(tok.type)) {
            val nextOnNewLine = i + 1 < visible.size && precededByNewline[i + 1]
            if (isSoftKeywordActingAsKeyword(tok.type, prev, next, lineStart, nextOnNewLine, angleDepth)) {
                tok.kind = SyntaxTokenKind.Keyword
                i++
                continue
            }
        }

        // Identifiers (and soft keywords acting as identifiers)
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
            // Declaration modifiers (private, data, sealed, inline, override, value, open, ...)
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

    // Declaration after class / interface / object / typealias
    if (
        prevType == KotlinTokens.CLASS ||
        prevType == KotlinTokens.INTERFACE ||
        prevType == KotlinTokens.OBJECT ||
        prevType == KotlinTokens.TYPE_ALIAS
    ) {
        return SyntaxTokenKind.Type
    }

    // Function declaration: `fun foo(...)` (but `fun String.foo()` keeps `String` as Type)
    if (prevType == KotlinTokens.FUN && nextType != KotlinTokens.DOT) {
        return SyntaxTokenKind.Function
    }

    // Supertype constructor call `class Foo : Bar()` stays Type
    if (prevType == KotlinTokens.COLON && looksLikeTypeName(text)) {
        return SyntaxTokenKind.Type
    }

    // Function / composable / constructor call `foo(...)` or `foo<T>(...)`
    if (nextType == KotlinTokens.LPAREN && prevType != KotlinTokens.CONSTRUCTOR) {
        return SyntaxTokenKind.Function
    }

    // Named argument `param = value`
    if (
        nextType == KotlinTokens.ASSIGNMENT &&
        (prevType == KotlinTokens.LPAREN || prevType == KotlinTokens.COMMA)
    ) {
        return SyntaxTokenKind.Property
    }

    // Member access `obj.prop` or `Color.White` or `Foo::bar`
    if (prevType == KotlinTokens.DOT || prevType == KotlinTokens.COLONCOLON) {
        return SyntaxTokenKind.Property
    }

    // Single-letter type parameter (`<T>`, `reified T`, `: T`)
    if (
        text.length == 1 &&
        text[0].isUpperCase() &&
        (angleDepth > 0 || prevType == KotlinTokens.COLON || prevType == KotlinTokens.REIFIED || prevType == KotlinTokens.AS || prevType == KotlinTokens.IS)
    ) {
        return SyntaxTokenKind.Type
    }

    // Type names (`String`, `Modifier`, `Box`, `Int`) vs ALL_CAPS constants (`MAX_VALUE`)
    if (looksLikeTypeName(text)) {
        return SyntaxTokenKind.Type
    }
    if (text.length > 1 && text[0].isUpperCase() && text.all { it.isUpperCase() || it.isDigit() || it == '_' }) {
        return SyntaxTokenKind.Property
    }
    return SyntaxTokenKind.Plain
}

private fun looksLikeTypeName(text: String): Boolean {
    if (text.length < 2 || !text[0].isUpperCase()) return false
    return text.any { it.isLowerCase() }
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
    KotlinTokens.COLON,
    KotlinTokens.SEMICOLON,
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
    KotlinTokens.COLONCOLON,
    KotlinTokens.DOUBLE_SEMICOLON,
    KotlinTokens.HASH,
    KotlinTokens.QUEST_NO_WS,
    KotlinTokens.LANGLE,
    KotlinTokens.RANGLE,
    KotlinTokens.LE,
    KotlinTokens.GE,
    KotlinTokens.EXCL_EQ,
    KotlinTokens.EXCL_EQEQ,
    KotlinTokens.EQEQ,
    KotlinTokens.EQEQEQ,
    KotlinTokens.SINGLE_QUOTE,
    KotlinTokens.AMP,
    -> true
    else -> false
}
