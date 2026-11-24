package dev.sebastiano.ciacola.syntax.antlr.diff

import org.antlr.v4.kotlinruntime.CharStream
import org.antlr.v4.kotlinruntime.IntStream
import org.antlr.v4.kotlinruntime.Lexer

/**
 * Pure Kotlin Multiplatform base lexer for `DiffLexer`, tracking unified and combined
 * hunk line counts and bounded prefix widths in strictly O(N) time without position-0
 * semantic predicates.
 */
internal abstract class DiffLexerBase(input: CharStream) : Lexer(input) {
    private var unifiedOldRemaining: Int = 0
    private var unifiedNewRemaining: Int = 0
    private var combinedPrefixWidth: Int = 2
    private var combinedOldRemaining: IntArray = IntArray(4)
    private var combinedOldCount: Int = 0
    private var combinedNewRemaining: Int = 0
    private var atHunkLineStart: Boolean = false

    override fun reset() {
        super.reset()
        resetHunkCounts()
    }

    protected fun resetHunkCounts() {
        unifiedOldRemaining = 0
        unifiedNewRemaining = 0
        combinedPrefixWidth = 2
        combinedOldCount = 0
        combinedNewRemaining = 0
        atHunkLineStart = false
    }

    protected fun onUnifiedHunkHeader(defaultMode: Int, unifiedHunkMode: Int) {
        resetHunkCounts()
        val len = _input.index() - _tokenStartCharIndex
        var idx = 0

        fun peek(i: Int): Int = if (i < len) _input.LA(-(len - i)) else IntStream.EOF

        // Skip leading '@@' and whitespace
        while (idx < len && peek(idx) == '@'.code) idx++
        while (idx < len && (peek(idx) == ' '.code || peek(idx) == '\t'.code)) idx++

        var oldCount = 1
        if (idx < len && peek(idx) == '-'.code) {
            idx++
            // skip start line
            while (idx < len && peek(idx) in '0'.code..'9'.code) idx++
            if (idx < len && peek(idx) == ','.code) {
                idx++
                oldCount = 0
                while (idx < len && peek(idx) in '0'.code..'9'.code) {
                    oldCount = saturateAddDigit(oldCount, peek(idx) - '0'.code)
                    idx++
                }
            }
        }

        while (idx < len && (peek(idx) == ' '.code || peek(idx) == '\t'.code)) idx++

        var newCount = 1
        if (idx < len && peek(idx) == '+'.code) {
            idx++
            // skip start line
            while (idx < len && peek(idx) in '0'.code..'9'.code) idx++
            if (idx < len && peek(idx) == ','.code) {
                idx++
                newCount = 0
                while (idx < len && peek(idx) in '0'.code..'9'.code) {
                    newCount = saturateAddDigit(newCount, peek(idx) - '0'.code)
                    idx++
                }
            }
        }

        unifiedOldRemaining = oldCount
        unifiedNewRemaining = newCount
        if (unifiedOldRemaining > 0 || unifiedNewRemaining > 0) {
            mode(unifiedHunkMode)
        } else {
            mode(defaultMode)
        }
    }

    protected fun onCombinedHunkHeader(defaultMode: Int, combinedHunkMode: Int) {
        resetHunkCounts()
        val len = _input.index() - _tokenStartCharIndex
        var idx = 0

        fun peek(i: Int): Int = if (i < len) _input.LA(-(len - i)) else IntStream.EOF

        var atCount = 0
        while (idx < len && peek(idx) == '@'.code) {
            atCount++
            idx++
        }
        val width = (atCount - 1).coerceAtLeast(2)
        combinedPrefixWidth = width
        if (combinedOldRemaining.size < width) {
            combinedOldRemaining = IntArray(width)
        }

        var parentIdx = 0
        while (idx < len) {
            while (idx < len && (peek(idx) == ' '.code || peek(idx) == '\t'.code)) idx++
            val ch = peek(idx)
            if (ch == '-'.code) {
                idx++
                while (idx < len && peek(idx) in '0'.code..'9'.code) idx++
                var count = 1
                if (idx < len && peek(idx) == ','.code) {
                    idx++
                    count = 0
                    while (idx < len && peek(idx) in '0'.code..'9'.code) {
                        count = saturateAddDigit(count, peek(idx) - '0'.code)
                        idx++
                    }
                }
                if (parentIdx < width) {
                    combinedOldRemaining[parentIdx] = count
                }
                parentIdx++
            } else if (ch == '+'.code) {
                idx++
                while (idx < len && peek(idx) in '0'.code..'9'.code) idx++
                var count = 1
                if (idx < len && peek(idx) == ','.code) {
                    idx++
                    count = 0
                    while (idx < len && peek(idx) in '0'.code..'9'.code) {
                        count = saturateAddDigit(count, peek(idx) - '0'.code)
                        idx++
                    }
                }
                combinedNewRemaining = count
                break
            } else {
                break
            }
        }
        combinedOldCount = parentIdx.coerceAtMost(width)
        if (!isCombinedExhausted()) {
            mode(combinedHunkMode)
        } else {
            mode(defaultMode)
        }
    }

    protected fun onUnifiedAdd() {
        if (unifiedNewRemaining > 0) unifiedNewRemaining--
    }

    protected fun onUnifiedDel() {
        if (unifiedOldRemaining > 0) unifiedOldRemaining--
    }

    protected fun onUnifiedCtx() {
        if (unifiedOldRemaining > 0) unifiedOldRemaining--
        if (unifiedNewRemaining > 0) unifiedNewRemaining--
    }

    protected fun onHunkNoNewline() {
        atHunkLineStart = false
    }

    protected fun onUnifiedNewline(defaultMode: Int) {
        if (!atHunkLineStart) {
            atHunkLineStart = true
        } else if (unifiedOldRemaining > 0 && unifiedNewRemaining > 0) {
            unifiedOldRemaining--
            unifiedNewRemaining--
        }
        if (unifiedOldRemaining <= 0 && unifiedNewRemaining <= 0) {
            atHunkLineStart = false
            mode(defaultMode)
        }
    }

    protected fun onCombinedNewline(defaultMode: Int) {
        if (!atHunkLineStart) {
            atHunkLineStart = true
        } else if (combinedNewRemaining > 0 && allCombinedOldRemainingPositive()) {
            combinedNewRemaining--
            for (i in 0 until combinedOldCount) {
                combinedOldRemaining[i]--
            }
        }
        if (isCombinedExhausted()) {
            atHunkLineStart = false
            mode(defaultMode)
        }
    }

    protected fun consumeCombinedPrefix() {
        val targetWidth = combinedPrefixWidth
        var consumed = 1 // first char already matched by grammar rule
        while (consumed < targetWidth) {
            val next = _input.LA(1)
            if (next == '+'.code || next == '-'.code || next == ' '.code) {
                _input.consume()
                consumed++
            } else {
                break
            }
        }
        // Update combined hunk counters based on the prefix characters just consumed.
        var hasMinus = false
        var allSpaces = true
        for (i in 0 until consumed) {
            val ch = _input.LA(-(consumed - i))
            if (ch != ' '.code) allSpaces = false
            if (ch == '-'.code) {
                hasMinus = true
                if (i < combinedOldCount && combinedOldRemaining[i] > 0) {
                    combinedOldRemaining[i]--
                }
            } else if (ch == ' '.code) {
                if (i < combinedOldCount && combinedOldRemaining[i] > 0) {
                    combinedOldRemaining[i]--
                }
            }
        }
        if (allSpaces && consumed < targetWidth) {
            // Stripped trailing space(s) on a blank combined context line
            for (i in consumed until targetWidth) {
                if (i < combinedOldCount && combinedOldRemaining[i] > 0) {
                    combinedOldRemaining[i]--
                }
            }
        }
        if (!hasMinus && combinedNewRemaining > 0) {
            combinedNewRemaining--
        }
    }

    protected fun onBodyNewline(defaultMode: Int, unifiedHunkMode: Int, combinedHunkMode: Int) {
        if (!_modeStack.isEmpty) {
            popMode()
        }
        atHunkLineStart = true
        if (_mode == unifiedHunkMode && unifiedOldRemaining <= 0 && unifiedNewRemaining <= 0) {
            atHunkLineStart = false
            mode(defaultMode)
        } else if (_mode == combinedHunkMode && isCombinedExhausted()) {
            atHunkLineStart = false
            mode(defaultMode)
        }
    }

    private fun allCombinedOldRemainingPositive(): Boolean {
        for (i in 0 until combinedOldCount) {
            if (combinedOldRemaining[i] <= 0) return false
        }
        return true
    }

    private fun isCombinedExhausted(): Boolean {
        if (combinedNewRemaining > 0) return false
        for (i in 0 until combinedOldCount) {
            if (combinedOldRemaining[i] > 0) return false
        }
        return true
    }

    private fun saturateAddDigit(current: Int, digit: Int): Int {
        if (current >= MAX_COUNT_SATURATION) return MAX_COUNT_SATURATION
        val next = current * 10 + digit
        return if (next < 0 || next > MAX_COUNT_SATURATION) MAX_COUNT_SATURATION else next
    }

    private companion object {
        private const val MAX_COUNT_SATURATION = 1_000_000_000
    }
}
