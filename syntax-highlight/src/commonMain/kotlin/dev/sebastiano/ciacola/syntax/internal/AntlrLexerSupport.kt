package dev.sebastiano.ciacola.syntax.internal

import dev.sebastiano.ciacola.syntax.SyntaxTokenKind
import org.antlr.v4.kotlinruntime.CharStream
import org.antlr.v4.kotlinruntime.IntStream
import org.antlr.v4.kotlinruntime.Lexer
import org.antlr.v4.kotlinruntime.Token
import org.antlr.v4.kotlinruntime.misc.Interval

/**
 * Creates a [CharStream] over [source] with exact UTF-16 supplementary-plane and unpaired-surrogate
 * handling across all Kotlin Multiplatform targets.
 */
internal fun antlrCharStream(source: String): CharStream = SafeCodePointCharStream(source)

/**
 * Pure-Kotlin [CharStream] backed by a UTF-16 [String].
 *
 * Works around `antlr-kotlin-runtime`'s `StringCharStream.codePoint` bug where a supplementary
 * code point at the final position of the input is misread as [IntStream.EOF] (it checks
 * `codePointIndex + 1 < size` instead of `utf16Index + 1 < source.length`), handles unpaired
 * surrogates losslessly, avoids index-table allocation for BMP-only inputs, and exposes
 * [cpToUtf16] directly for [driveAntlrLexer].
 */
internal class SafeCodePointCharStream(
    private val source: String,
    override val sourceName: String = IntStream.UNKNOWN_SOURCE_NAME,
) : CharStream {
    internal val cpToUtf16: IntArray?
    private val cpSize: Int
    private var position: Int = 0

    init {
        val len = source.length
        var hasPair = false
        var i = 0
        while (i + 1 < len) {
            if (source[i].isHighSurrogate() && source[i + 1].isLowSurrogate()) {
                hasPair = true
                break
            }
            i++
        }
        if (!hasPair) {
            cpToUtf16 = null
            cpSize = len
        } else {
            val offsets = IntArray(len + 1)
            var cp = 0
            var idx = 0
            while (idx < len) {
                offsets[cp++] = idx
                idx += if (idx + 1 < len && source[idx].isHighSurrogate() && source[idx + 1].isLowSurrogate()) 2 else 1
            }
            offsets[cp] = len
            cpToUtf16 = offsets.copyOf(cp + 1)
            cpSize = cp
        }
    }

    override fun consume() {
        if (position >= cpSize) {
            throw IllegalStateException("cannot consume EOF")
        }
        position++
    }

    override fun LA(i: Int): Int = when {
        i > 0 -> codePointAt(position + i - 1)
        i < 0 -> codePointAt(position + i)
        else -> 0
    }

    override fun mark(): Int = -1

    override fun release(marker: Int) = Unit

    override fun index(): Int = position

    override fun seek(index: Int) {
        position = index
    }

    override fun size(): Int = cpSize

    override fun getText(interval: Interval): String {
        val startCp = interval.a
        val stopCp = interval.b
        if (startCp >= cpSize || stopCp < 0 || stopCp < startCp) return ""
        val clampedStart = startCp.coerceAtLeast(0)
        val clampedStop = stopCp.coerceAtMost(cpSize - 1)
        val map = cpToUtf16
        return if (map == null) {
            source.substring(clampedStart, clampedStop + 1)
        } else {
            source.substring(map[clampedStart], map[clampedStop + 1])
        }
    }

    override fun toString(): String = source

    private fun codePointAt(cpIndex: Int): Int {
        if (cpIndex < 0 || cpIndex >= cpSize) return IntStream.EOF
        val map = cpToUtf16 ?: return source[cpIndex].code
        val utf16 = map[cpIndex]
        val hi = source[utf16]
        if (hi.isHighSurrogate() && utf16 + 1 < source.length) {
            val lo = source[utf16 + 1]
            if (lo.isLowSurrogate()) {
                return ((hi.code - 0xD800) shl 10) + (lo.code - 0xDC00) + 0x10000
            }
        }
        return hi.code
    }
}

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

    val stream = lexer.inputStream
    val cpToUtf16 = if (stream is SafeCodePointCharStream) {
        stream.cpToUtf16
    } else {
        val streamSize = stream.size()
        if (streamSize in 1 until source.length) {
            buildCodePointToUtf16Map(source, streamSize)
        } else {
            null
        }
    }

    var cursor = 0
    while (true) {
        val token = lexer.nextToken()
        if (token.type == Token.EOF) break
        val rawStart = token.startIndex
        val rawEnd = token.stopIndex + 1
        val start = if (cpToUtf16 != null && rawStart in cpToUtf16.indices) cpToUtf16[rawStart] else rawStart
        val end = if (cpToUtf16 != null && rawEnd in cpToUtf16.indices) cpToUtf16[rawEnd] else rawEnd
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

private fun buildCodePointToUtf16Map(source: String, codePointCount: Int): IntArray {
    val map = IntArray(codePointCount + 1)
    var cp = 0
    var i = 0
    while (i < source.length && cp < codePointCount) {
        map[cp++] = i
        i += if (i + 1 < source.length && source[i].isHighSurrogate() && source[i + 1].isLowSurrogate()) 2 else 1
    }
    while (cp <= codePointCount) {
        map[cp++] = source.length
    }
    return map
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
