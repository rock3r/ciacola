package dev.sebastiano.ciacola.syntax.antlr.php

import org.antlr.v4.kotlinruntime.CharStream
import org.antlr.v4.kotlinruntime.IntStream
import org.antlr.v4.kotlinruntime.Lexer

/**
 * Pure Kotlin Multiplatform base lexer for [PhpLexer], managing PHP/HTML tag boundaries,
 * flexible PHP 7.3+ heredoc/nowdoc delimiters, string interpolations (`$var`, `{$expr}`,
 * `${expr}`), and recovery on `?>` or EOF.
 */
internal abstract class PhpLexerBase(input: CharStream) : Lexer(input) {

    private val heredocMarkerStack = ArrayList<String>()
    private val interpBraceDepths = ArrayList<Int>()
    protected var afterSimpleVar: Boolean = false

    protected fun isPhpOpenBoundaryAhead(): Boolean {
        val ch = _input.LA(1)
        return ch == IntStream.EOF || ch == ' '.code || ch == '\t'.code || ch == '\r'.code || ch == '\n'.code
    }

    protected fun isShortOpenTagAhead(): Boolean {
        val c1 = _input.LA(1)
        if (c1 == '='.code) return false
        val c2 = _input.LA(2)
        val c3 = _input.LA(3)
        val c4 = _input.LA(4)
        if (equalsAsciiIgnoreCase(c1, 'p') && equalsAsciiIgnoreCase(c2, 'h') && equalsAsciiIgnoreCase(c3, 'p')) {
            if (c4 == IntStream.EOF || c4 == ' '.code || c4 == '\t'.code || c4 == '\r'.code || c4 == '\n'.code) {
                return false
            }
        }
        if (equalsAsciiIgnoreCase(c1, 'x') && equalsAsciiIgnoreCase(c2, 'm') && equalsAsciiIgnoreCase(c3, 'l')) {
            if (c4 == IntStream.EOF || c4 == ' '.code || c4 == '\t'.code || c4 == '\r'.code || c4 == '\n'.code || c4 == '?'.code) {
                return false
            }
        }
        return true
    }

    protected fun onPhpClose() {
        heredocMarkerStack.clear()
        interpBraceDepths.clear()
        afterSimpleVar = false
        while (!_modeStack.isEmpty) {
            popMode()
        }
    }

    protected fun onCurlyDollarOpen(phpMode: Int, interpOpenType: Int) {
        // Rewind to leave `$` for the variable token inside PHP_MODE.
        _input.seek(_tokenStartCharIndex + 1)
        afterSimpleVar = false
        type = interpOpenType
        interpBraceDepths.add(0)
        pushMode(phpMode)
    }

    protected fun enterBraceInterp(phpMode: Int) {
        afterSimpleVar = false
        interpBraceDepths.add(0)
        pushMode(phpMode)
    }

    protected fun onLBraceInPhp() {
        if (interpBraceDepths.isNotEmpty()) {
            interpBraceDepths[interpBraceDepths.lastIndex]++
        }
    }

    protected fun onRBraceInPhp(interpCloseType: Int) {
        if (interpBraceDepths.isEmpty()) return
        val lastIdx = interpBraceDepths.lastIndex
        if (interpBraceDepths[lastIdx] > 0) {
            interpBraceDepths[lastIdx]--
        } else {
            interpBraceDepths.removeAt(lastIdx)
            type = interpCloseType
            if (!_modeStack.isEmpty) {
                popMode()
            }
        }
    }

    protected fun onSimpleVarMember(memberType: Int, fallbackTextType: Int) {
        if (afterSimpleVar) {
            afterSimpleVar = false
            type = memberType
        } else {
            type = fallbackTextType
        }
    }

    protected fun onSimpleVarIndex(indexMode: Int, indexOpenType: Int, fallbackTextType: Int) {
        if (afterSimpleVar) {
            afterSimpleVar = false
            type = indexOpenType
            pushMode(indexMode)
        } else {
            type = fallbackTextType
        }
    }

    protected fun consumeNowdoc() {
        val marker = extractMarker(text)
        if (marker.isEmpty()) return

        while (_input.LA(1) != IntStream.EOF) {
            if (consumeClosingMarkerIfAtLineStart(marker)) {
                return
            }
            while (true) {
                val ch = _input.LA(1)
                if (ch == IntStream.EOF) return
                _input.consume()
                if (ch == '\n'.code) break
                if (ch == '\r'.code) {
                    if (_input.LA(1) == '\n'.code) {
                        _input.consume()
                    }
                    break
                }
            }
        }
    }

    protected fun enterHeredoc(heredocMode: Int) {
        afterSimpleVar = false
        val marker = extractMarker(text)
        if (marker.isEmpty()) return
        if (consumeClosingMarkerIfAtLineStart(marker)) {
            return
        }
        heredocMarkerStack.add(marker)
        pushMode(heredocMode)
    }

    protected fun onHeredocNewline() {
        afterSimpleVar = false
        val marker = heredocMarkerStack.lastOrNull() ?: return
        if (consumeClosingMarkerIfAtLineStart(marker)) {
            heredocMarkerStack.removeAt(heredocMarkerStack.lastIndex)
            if (!_modeStack.isEmpty) {
                popMode()
            }
        }
    }

    private fun consumeClosingMarkerIfAtLineStart(marker: String): Boolean {
        var offset = 1
        while (true) {
            val ch = _input.LA(offset)
            if (ch == ' '.code || ch == '\t'.code) {
                offset++
            } else {
                break
            }
        }
        var markerCpCount = 0
        var i = 0
        while (i < marker.length) {
            val hi = marker[i]
            val expectedCp = if (i + 1 < marker.length && hi.isHighSurrogate() && marker[i + 1].isLowSurrogate()) {
                val lo = marker[i + 1]
                i += 2
                0x10000 + ((hi.code - 0xD800) shl 10) + (lo.code - 0xDC00)
            } else {
                i++
                hi.code
            }
            if (_input.LA(offset + markerCpCount) != expectedCp) {
                return false
            }
            markerCpCount++
        }
        val after = _input.LA(offset + markerCpCount)
        if (isPhpIdentContinue(after)) {
            return false
        }
        repeat(offset - 1 + markerCpCount) {
            _input.consume()
        }
        return true
    }

    private fun extractMarker(header: String): String {
        var start = 3 // skip "<<<"
        while (start < header.length && (header[start] == ' ' || header[start] == '\t')) {
            start++
        }
        var end = header.length
        while (end > start && (header[end - 1] == '\n' || header[end - 1] == '\r')) {
            end--
        }
        if (end - start >= 2 &&
            ((header[start] == '\'' && header[end - 1] == '\'') || (header[start] == '"' && header[end - 1] == '"'))
        ) {
            start++
            end--
        }
        return if (end > start) header.substring(start, end) else ""
    }

    private fun isPhpIdentContinue(ch: Int): Boolean =
        ch != IntStream.EOF && (
            ch in 'a'.code..'z'.code ||
                ch in 'A'.code..'Z'.code ||
                ch in '0'.code..'9'.code ||
                ch == '_'.code ||
                ch >= 0x80
            )

    private fun equalsAsciiIgnoreCase(cp: Int, lowerAscii: Char): Boolean =
        cp == lowerAscii.code || cp == (lowerAscii.code - 32)

    override fun reset() {
        super.reset()
        heredocMarkerStack.clear()
        interpBraceDepths.clear()
        afterSimpleVar = false
    }
}
