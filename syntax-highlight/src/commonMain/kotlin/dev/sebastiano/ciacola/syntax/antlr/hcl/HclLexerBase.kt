package dev.sebastiano.ciacola.syntax.antlr.hcl

import org.antlr.v4.kotlinruntime.CharStream
import org.antlr.v4.kotlinruntime.IntStream
import org.antlr.v4.kotlinruntime.Lexer

/**
 * Pure Kotlin Multiplatform base lexer for [HclLexer].
 *
 * Tracks nested `${...}` interpolations and `%{...}` / `%{~...~}` template directives inside
 * double-quoted strings and heredocs, plus `<<EOF` / `<<-EOF` heredoc delimiters in strictly O(N) time.
 */
internal abstract class HclLexerBase(input: CharStream) : Lexer(input) {

    private class TemplateFrame(
        val modeStackSize: Int,
        var braceDepth: Int = 0,
    )

    private class HeredocSpec(
        val delimiterCodePoints: IntArray,
        val stripIndent: Boolean,
    )

    private class HeredocFrame(
        val spec: HeredocSpec,
        var atLineStart: Boolean = true,
    )

    private val templateStack = ArrayList<TemplateFrame>()
    private var pendingHeredoc: HeredocSpec? = null
    private val heredocStack = ArrayList<HeredocFrame>()

    protected fun enterTemplateExpr() {
        pushMode(DEFAULT_MODE)
        templateStack.add(TemplateFrame(modeStackSize = _modeStack.size()))
    }

    protected fun onLBrace() {
        val top = templateStack.lastOrNull()
        if (top != null && _modeStack.size() == top.modeStackSize) {
            top.braceDepth++
        }
    }

    protected fun canCloseTemplate(): Boolean {
        val top = templateStack.lastOrNull() ?: return false
        return _modeStack.size() == top.modeStackSize && top.braceDepth == 0
    }

    protected fun onTemplateStripClose(templateCloseType: Int) {
        if (canCloseTemplate()) {
            templateStack.removeAt(templateStack.lastIndex)
            if (!_modeStack.isEmpty) {
                popMode()
            }
            type = templateCloseType
        }
    }

    protected fun onRBrace(templateCloseType: Int) {
        val top = templateStack.lastOrNull()
        if (top != null && _modeStack.size() == top.modeStackSize) {
            if (top.braceDepth > 0) {
                top.braceDepth--
            } else {
                templateStack.removeAt(templateStack.lastIndex)
                if (!_modeStack.isEmpty) {
                    popMode()
                }
                type = templateCloseType
            }
        }
    }

    protected fun onHeredocOpen(lexeme: String) {
        var idx = 2 // skip "<<"
        var stripIndent = false
        if (idx < lexeme.length && lexeme[idx] == '-') {
            stripIndent = true
            idx++
        }
        val delim = lexeme.substring(idx)
        pendingHeredoc = HeredocSpec(
            delimiterCodePoints = toCodePoints(delim),
            stripIndent = stripIndent,
        )
    }

    protected fun onDefaultNewline(heredocMode: Int) {
        val spec = pendingHeredoc ?: return
        pendingHeredoc = null
        heredocStack.add(HeredocFrame(spec = spec, atLineStart = true))
        pushMode(heredocMode)
    }

    protected fun onHeredocNewline() {
        heredocStack.lastOrNull()?.atLineStart = true
    }

    protected fun markHeredocBody() {
        heredocStack.lastOrNull()?.atLineStart = false
    }

    protected fun consumeHeredocText(heredocCloseType: Int) {
        val frame = heredocStack.lastOrNull()
        if (frame != null && frame.atLineStart) {
            val matchLen = matchClosingDelimiterLengthFromTokenStart(frame.spec)
            if (matchLen > 0) {
                if (matchLen > 1) {
                    repeat(matchLen - 1) { _input.consume() }
                }
                heredocStack.removeAt(heredocStack.lastIndex)
                if (!_modeStack.isEmpty) {
                    popMode()
                }
                type = heredocCloseType
                return
            }
            frame.atLineStart = false
        }

        while (true) {
            val ch = _input.LA(1)
            if (ch == IntStream.EOF || ch == '\r'.code || ch == '\n'.code || ch == '$'.code || ch == '%'.code) {
                break
            }
            _input.consume()
        }
    }

    private fun matchClosingDelimiterLengthFromTokenStart(spec: HeredocSpec): Int {
        var offset = 0
        if (spec.stripIndent) {
            while (true) {
                val ch = charAtOffsetFromTokenStart(offset)
                if (ch == ' '.code || ch == '\t'.code) {
                    offset++
                } else {
                    break
                }
            }
        }
        val delimCps = spec.delimiterCodePoints
        if (delimCps.isEmpty()) return 0
        for (i in delimCps.indices) {
            if (charAtOffsetFromTokenStart(offset + i) != delimCps[i]) {
                return 0
            }
        }
        val after = charAtOffsetFromTokenStart(offset + delimCps.size)
        return if (after == IntStream.EOF || after == '\r'.code || after == '\n'.code) {
            offset + delimCps.size
        } else {
            0
        }
    }

    private fun charAtOffsetFromTokenStart(offset: Int): Int {
        val delta = _tokenStartCharIndex + offset - _input.index()
        return _input.LA(if (delta >= 0) delta + 1 else delta)
    }

    private fun toCodePoints(text: String): IntArray {
        val cps = ArrayList<Int>(text.length)
        var i = 0
        while (i < text.length) {
            val hi = text[i]
            if (hi.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate()) {
                val lo = text[i + 1]
                cps.add(((hi.code - 0xD800) shl 10) + (lo.code - 0xDC00) + 0x10000)
                i += 2
            } else {
                cps.add(hi.code)
                i++
            }
        }
        return IntArray(cps.size) { cps[it] }
    }
}
