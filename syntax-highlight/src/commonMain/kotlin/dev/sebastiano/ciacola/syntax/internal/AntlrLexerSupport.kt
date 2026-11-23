package dev.sebastiano.ciacola.syntax.internal

import dev.sebastiano.ciacola.syntax.SyntaxTokenKind
import org.antlr.v4.kotlinruntime.Lexer
import org.antlr.v4.kotlinruntime.Token

/**
 * Drives an ANTLR4 [lexer] across [source], suppressing error listeners and guaranteeing
 * strictly monotonic, contiguous coverage of `[0, source.length)` via [onGap] and [onToken].
 */
internal fun driveAntlrLexer(
    source: String,
    lexer: Lexer,
    onGap: (start: Int, end: Int) -> Unit,
    onToken: (type: Int, start: Int, end: Int) -> Unit,
) {
    if (source.isEmpty()) return
    lexer.removeErrorListeners()

    var cursor = 0
    while (true) {
        val token = lexer.nextToken()
        if (token.type == Token.EOF) break
        val start = token.startIndex
        val end = token.stopIndex + 1
        if (start < cursor || end <= start || end > source.length) continue
        if (start > cursor) {
            onGap(cursor, start)
        }
        onToken(token.type, start, end)
        cursor = end
    }
    if (cursor < source.length) {
        onGap(cursor, source.length)
    }
}

/**
 * Splits an unmatched or skipped gap `[start, end)` in [source] into contiguous
 * [SyntaxTokenKind.Whitespace] and [SyntaxTokenKind.Invalid] spans.
 */
internal fun emitDefaultGap(
    source: String,
    start: Int,
    end: Int,
    emit: (SyntaxTokenKind, Int, Int) -> Unit,
) {
    var i = start
    while (i < end) {
        val isWs = isAsciiWhitespace(source[i]) || source[i].isWhitespace()
        val runStart = i
        i++
        while (i < end && (isAsciiWhitespace(source[i]) || source[i].isWhitespace()) == isWs) {
            i++
        }
        emit(if (isWs) SyntaxTokenKind.Whitespace else SyntaxTokenKind.Invalid, runStart, i)
    }
}

internal fun looksLikeTypeName(text: String): Boolean {
    if (text.length < 2 || !text[0].isUpperCase()) return false
    return text.any { it.isLowerCase() }
}

internal fun isAsciiWhitespace(ch: Char): Boolean =
    ch == ' ' || ch == '\t' || ch == '\r' || ch == '\n'
