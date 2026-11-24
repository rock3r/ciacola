package dev.sebastiano.ciacola.syntax.antlr.ruby

import org.antlr.v4.kotlinruntime.CharStream
import org.antlr.v4.kotlinruntime.IntStream
import org.antlr.v4.kotlinruntime.Lexer
import org.antlr.v4.kotlinruntime.Token

/**
 * Pure Kotlin Multiplatform base lexer for [RubyLexer], handling:
 * - Column-0 `=begin ... =end` embedded documentation and `__END__` data sections.
 * - Ruby expression-state tracking (`BEG`, `END`, `CMDARG`, `CLASS`, `DEF`, `DOT`) with
 *   space-sensitivity for disambiguating `/regex/` vs `/`, `%w[]` vs `%`, `<<EOF` vs `<<`,
 *   `?a` vs `?`, and `:sym` vs `:`.
 * - Paired and arbitrary-delimiter `%` literals with nested delimiter counting.
 * - Multi-line `/regex/[imxouesn]*` literals with `[...]` character-class tracking and `#{...}`
 *   interpolation.
 * - FIFO-queued `<<`, `<<-`, and `<<~` heredocs (bare, double-quoted, single-quoted, backtick)
 *   with nested `#{...}` interpolation.
 * - Iterative brace-depth tracking for nested `#{...}` interpolations in O(N) time.
 */
internal abstract class RubyLexerBase(input: CharStream) : Lexer(input) {

    protected enum class ExprState {
        BEG,
        END,
        CMDARG,
        CLASS,
        DEF,
        DOT,
    }

    private class DelimitedFrame(
        val openCp: Int,
        val closeCp: Int,
        val isPaired: Boolean,
        val isRegex: Boolean,
        val isSymbol: Boolean,
        var depth: Int = 1,
    )

    private class DQuoteFrame(
        val isSymbol: Boolean,
    )

    private class RegexFrame(
        var inCharClass: Boolean = false,
    )

    private class HeredocSpec(
        val delimiter: String,
        val delimiterCodePoints: IntArray,
        val allowIndent: Boolean,
        val isQuoted: Boolean,
    )

    private class HeredocFrame(
        val spec: HeredocSpec,
        var atLineStart: Boolean = true,
        var closedPendingNewline: Boolean = false,
    )

    protected var exprState: ExprState = ExprState.BEG
    protected var sawSpaceSinceLastToken: Boolean = true

    private val interpBraceDepths = ArrayList<Int>()
    private val percentStack = ArrayList<DelimitedFrame>()
    private val dquoteStack = ArrayList<DQuoteFrame>()
    private val regexStack = ArrayList<RegexFrame>()
    private val pendingHeredocs = ArrayList<HeredocSpec>()
    private val heredocStack = ArrayList<HeredocFrame>()

    override fun reset() {
        super.reset()
        exprState = ExprState.BEG
        sawSpaceSinceLastToken = true
        interpBraceDepths.clear()
        percentStack.clear()
        dquoteStack.clear()
        regexStack.clear()
        pendingHeredocs.clear()
        heredocStack.clear()
    }

    // ---------------- Line-start & boundary checks ----------------

    protected fun isAtStartOfInput(): Boolean = _tokenStartCharIndex == 0

    protected fun isAtLineStartAtTokenStart(): Boolean {
        if (_tokenStartCharIndex == 0) return true
        val delta = _tokenStartCharIndex - _input.index()
        val prev = _input.LA(delta - 1)
        return prev == '\n'.code || prev == '\r'.code
    }

    protected fun isEmbeddedDocStart(): Boolean {
        if (!isAtLineStartAtTokenStart()) return false
        return isEmbDocBoundary(_input.LA(1))
    }

    protected fun consumeEmbeddedDoc() {
        consumeRestOfLine()
        while (_input.LA(1) != IntStream.EOF) {
            consumeLineBreak()
            if (_input.LA(1) == IntStream.EOF) break
            if (isEmbeddedDocEndAhead()) {
                repeat(4) { _input.consume() } // "=end"
                consumeRestOfLine()
                break
            }
            consumeRestOfLine()
        }
        sawSpaceSinceLastToken = true
    }

    private fun isEmbeddedDocEndAhead(): Boolean {
        if (_input.LA(1) != '='.code ||
            _input.LA(2) != 'e'.code ||
            _input.LA(3) != 'n'.code ||
            _input.LA(4) != 'd'.code
        ) {
            return false
        }
        return isEmbDocBoundary(_input.LA(5))
    }

    protected fun isEndDataMarker(): Boolean {
        if (!isAtLineStartAtTokenStart()) return false
        val next = _input.LA(1)
        return next == IntStream.EOF || next == '\n'.code || next == '\r'.code
    }

    protected fun consumeToEof() {
        while (_input.LA(1) != IntStream.EOF) {
            _input.consume()
        }
    }

    private fun consumeRestOfLine() {
        while (true) {
            val ch = _input.LA(1)
            if (ch == IntStream.EOF || ch == '\r'.code || ch == '\n'.code) break
            _input.consume()
        }
    }

    private fun consumeLineBreak() {
        val ch = _input.LA(1)
        if (ch == '\r'.code) {
            _input.consume()
            if (_input.LA(1) == '\n'.code) {
                _input.consume()
            }
        } else if (ch == '\n'.code) {
            _input.consume()
        }
    }

    // ---------------- Expression-state disambiguation ----------------

    protected fun isArgLiteralStart(): Boolean =
        exprState == ExprState.BEG || (exprState == ExprState.CMDARG && sawSpaceSinceLastToken)

    protected fun isRegexStart(): Boolean {
        if (exprState == ExprState.BEG) return true
        if (exprState == ExprState.CMDARG && sawSpaceSinceLastToken) {
            val next = _input.LA(1)
            return next != IntStream.EOF && !isRubySpaceOrEol(next) && next != '='.code
        }
        return false
    }

    protected fun isTypedPercentStart(): Boolean {
        if (!isArgLiteralStart()) return false
        val delim = _input.LA(-1)
        return delim != IntStream.EOF && !isAsciiAlnum(delim) && delim != '\r'.code && delim != '\n'.code
    }

    protected fun isUntypedPercentStart(): Boolean {
        val delim = _input.LA(-1)
        if (delim == IntStream.EOF || isAsciiAlnum(delim) || isRubySpaceOrEol(delim)) return false
        if (exprState == ExprState.BEG) return true
        return exprState == ExprState.CMDARG && sawSpaceSinceLastToken && delim != '='.code
    }

    protected fun isHeredocStart(): Boolean = isArgLiteralStart()

    protected fun isCharLiteralStart(): Boolean = isArgLiteralStart()

    protected fun isSymbolStart(): Boolean = isArgLiteralStart()

    protected fun isDefContext(): Boolean = exprState == ExprState.DEF

    // ---------------- State updates in DEFAULT_MODE ----------------

    protected fun onWhitespace() {
        sawSpaceSinceLastToken = true
    }

    protected fun onNewline(heredocLiteralMode: Int, heredocExpandMode: Int) {
        sawSpaceSinceLastToken = true
        if (exprState != ExprState.DOT && exprState != ExprState.DEF && exprState != ExprState.CLASS) {
            exprState = ExprState.BEG
        }
        if (pendingHeredocs.isNotEmpty()) {
            val nextSpec = pendingHeredocs.removeAt(0)
            heredocStack.add(HeredocFrame(spec = nextSpec))
            val targetMode = if (nextSpec.isQuoted || isHeredocCloserAhead(nextSpec)) {
                heredocLiteralMode
            } else {
                heredocExpandMode
            }
            pushMode(targetMode)
        }
    }

    protected fun onBegToken() {
        exprState = ExprState.BEG
        sawSpaceSinceLastToken = false
    }

    protected fun onEndToken() {
        exprState = ExprState.END
        sawSpaceSinceLastToken = false
    }

    protected fun onDotToken() {
        exprState = ExprState.DOT
        sawSpaceSinceLastToken = false
    }

    protected fun onDefToken() {
        exprState = ExprState.DEF
        sawSpaceSinceLastToken = false
    }

    protected fun onClassOrModuleToken() {
        exprState = ExprState.CLASS
        sawSpaceSinceLastToken = false
    }

    protected fun onCmdArgToken() {
        exprState = when (exprState) {
            ExprState.DEF, ExprState.CLASS -> ExprState.END
            else -> ExprState.CMDARG
        }
        sawSpaceSinceLastToken = false
    }

    protected fun onIdentifierOrKeyword(lexeme: String) {
        if (exprState == ExprState.DOT || exprState == ExprState.DEF) {
            exprState = ExprState.END
            sawSpaceSinceLastToken = false
            return
        }
        if (exprState == ExprState.CLASS) {
            exprState = ExprState.END
            sawSpaceSinceLastToken = false
            return
        }
        exprState = when (lexeme) {
            "def" -> ExprState.DEF
            "class", "module" -> ExprState.CLASS
            "if", "elsif", "else", "unless", "while", "until", "for", "in", "do",
            "case", "when", "begin", "rescue", "ensure", "then", "return", "yield",
            "break", "next", "and", "or", "not", "alias", "undef", "defined?",
            -> ExprState.BEG
            "self", "true", "false", "nil", "__FILE__", "__LINE__", "__ENCODING__",
            "end", "redo", "retry", "BEGIN", "END",
            -> ExprState.END
            else -> ExprState.CMDARG
        }
        sawSpaceSinceLastToken = false
    }

    // ---------------- Braces & #{...} interpolation ----------------

    protected fun onLBrace() {
        if (interpBraceDepths.isNotEmpty()) {
            interpBraceDepths[interpBraceDepths.lastIndex]++
        }
        exprState = ExprState.BEG
        sawSpaceSinceLastToken = false
    }

    protected fun onRBrace(interpCloseToken: Int) {
        if (interpBraceDepths.isNotEmpty()) {
            val lastIdx = interpBraceDepths.lastIndex
            if (interpBraceDepths[lastIdx] > 0) {
                interpBraceDepths[lastIdx]--
                exprState = ExprState.END
                sawSpaceSinceLastToken = false
            } else {
                interpBraceDepths.removeAt(lastIdx)
                type = interpCloseToken
                if (!_modeStack.isEmpty) {
                    popMode()
                }
                // After interpolation closes, the outer container continues until its closer.
                sawSpaceSinceLastToken = false
            }
        } else {
            exprState = ExprState.END
            sawSpaceSinceLastToken = false
        }
    }

    protected fun enterInterp(defaultMode: Int) {
        interpBraceDepths.add(0)
        exprState = ExprState.BEG
        sawSpaceSinceLastToken = true
        pushMode(defaultMode)
    }

    // ---------------- Double-quoted strings, symbols & backticks ----------------

    protected fun enterDQuote(dquoteMode: Int, isSymbol: Boolean) {
        dquoteStack.add(DQuoteFrame(isSymbol = isSymbol))
        pushMode(dquoteMode)
    }

    protected fun onDQuoteText(symTextToken: Int) {
        if (dquoteStack.lastOrNull()?.isSymbol == true) {
            type = symTextToken
        }
    }

    protected fun onDQuoteEscape(symEscapeToken: Int) {
        if (dquoteStack.lastOrNull()?.isSymbol == true) {
            type = symEscapeToken
        }
    }

    protected fun exitDQuote(symCloseToken: Int) {
        val frame = if (dquoteStack.isNotEmpty()) dquoteStack.removeAt(dquoteStack.lastIndex) else null
        if (frame?.isSymbol == true) {
            type = symCloseToken
        }
        if (!_modeStack.isEmpty) {
            popMode()
        }
        exprState = ExprState.END
        sawSpaceSinceLastToken = false
    }

    protected fun exitBacktick() {
        if (!_modeStack.isEmpty) {
            popMode()
        }
        exprState = ExprState.END
        sawSpaceSinceLastToken = false
    }

    // ---------------- Regex mode (/.../[imxouesn]*) ----------------

    protected fun enterRegexMode(regexMode: Int) {
        regexStack.add(RegexFrame())
        pushMode(regexMode)
    }

    protected fun onRegexLBracket() {
        val frame = regexStack.lastOrNull() ?: return
        if (!frame.inCharClass) {
            frame.inCharClass = true
        }
    }

    protected fun onRegexRBracket() {
        val frame = regexStack.lastOrNull() ?: return
        if (frame.inCharClass) {
            frame.inCharClass = false
        }
    }

    protected fun onRegexSlash(textToken: Int, closeToken: Int) {
        val frame = regexStack.lastOrNull()
        if (frame != null && frame.inCharClass) {
            type = textToken
            return
        }
        consumeRegexFlags()
        if (regexStack.isNotEmpty()) {
            regexStack.removeAt(regexStack.lastIndex)
        }
        if (!_modeStack.isEmpty) {
            popMode()
        }
        type = closeToken
        exprState = ExprState.END
        sawSpaceSinceLastToken = false
    }

    private fun consumeRegexFlags() {
        while (isRegexFlag(_input.LA(1))) {
            _input.consume()
        }
    }

    // ---------------- Percent literals (%q, %Q, %w, %W, %i, %I, %r, %s, %x, %) ----------------

    protected fun isPercentHashCloser(): Boolean =
        percentStack.lastOrNull()?.closeCp == '#'.code

    protected fun enterPercentLiteral(
        typed: Boolean,
        literalMode: Int,
        interpMode: Int,
        symOpenToken: Int,
    ) {
        val openCp = _input.LA(-1)
        val kindChar = if (typed) _input.LA(-2) else '%'.code
        val closeCp = pairedCloseDelimiter(openCp)
        val isPaired = closeCp != openCp
        val isSymbol = typed && (kindChar == 'i'.code || kindChar == 'I'.code || kindChar == 's'.code)
        val isRegex = typed && kindChar == 'r'.code
        val isInterp = !typed ||
            kindChar == 'Q'.code ||
            kindChar == 'W'.code ||
            kindChar == 'I'.code ||
            kindChar == 'r'.code ||
            kindChar == 'x'.code

        percentStack.add(
            DelimitedFrame(
                openCp = openCp,
                closeCp = closeCp,
                isPaired = isPaired,
                isRegex = isRegex,
                isSymbol = isSymbol,
            ),
        )
        if (isSymbol) {
            type = symOpenToken
        }
        pushMode(if (isInterp) interpMode else literalMode)
    }

    protected fun consumePercentBody(
        isInterp: Boolean,
        textToken: Int,
        closeToken: Int,
        symTextToken: Int,
        symCloseToken: Int,
    ) {
        val frame = percentStack.lastOrNull() ?: return
        val firstCp = _input.LA(-1)

        if (frame.isPaired && firstCp == frame.openCp) {
            frame.depth++
        } else if (firstCp == frame.closeCp) {
            if (frame.depth > 1) {
                frame.depth--
            } else {
                if (frame.isRegex) {
                    consumeRegexFlags()
                }
                percentStack.removeAt(percentStack.lastIndex)
                if (!_modeStack.isEmpty) {
                    popMode()
                }
                type = if (frame.isSymbol) symCloseToken else closeToken
                exprState = ExprState.END
                sawSpaceSinceLastToken = false
                return
            }
        }

        while (true) {
            val next = _input.LA(1)
            if (next == IntStream.EOF || next == '\\'.code) break
            if (isInterp && next == '#'.code && frame.closeCp != '#'.code) {
                val afterHash = _input.LA(2)
                if (afterHash == '{'.code || afterHash == '@'.code || afterHash == '$'.code) {
                    break
                }
            }
            if (frame.isPaired && next == frame.openCp) {
                frame.depth++
                _input.consume()
                continue
            }
            if (next == frame.closeCp) {
                if (frame.depth > 1) {
                    frame.depth--
                    _input.consume()
                    continue
                }
                break
            }
            _input.consume()
        }

        type = if (frame.isSymbol) symTextToken else textToken
    }

    // ---------------- Heredocs (FIFO queue) ----------------

    protected fun enqueueHeredoc() {
        val lexeme = text
        var idx = 2 // skip "<<"
        var allowIndent = false
        if (idx < lexeme.length && (lexeme[idx] == '-' || lexeme[idx] == '~')) {
            allowIndent = true
            idx++
        }
        val rawDelim = lexeme.substring(idx)
        val isQuoted: Boolean
        val delimiter: String
        if (rawDelim.length >= 2 && rawDelim[0] == '\'' && rawDelim[rawDelim.length - 1] == '\'') {
            isQuoted = true
            delimiter = rawDelim.substring(1, rawDelim.length - 1)
        } else if (rawDelim.length >= 2 &&
            ((rawDelim[0] == '"' && rawDelim[rawDelim.length - 1] == '"') ||
                (rawDelim[0] == '`' && rawDelim[rawDelim.length - 1] == '`'))
        ) {
            isQuoted = false
            delimiter = rawDelim.substring(1, rawDelim.length - 1)
        } else {
            isQuoted = false
            delimiter = rawDelim
        }
        pendingHeredocs.add(
            HeredocSpec(
                delimiter = delimiter,
                delimiterCodePoints = toCodePoints(delimiter),
                allowIndent = allowIndent,
                isQuoted = isQuoted,
            ),
        )
        exprState = ExprState.END
        sawSpaceSinceLastToken = false
    }

    protected fun consumeHeredocLiteralLine(
        heredocEndToken: Int,
        heredocLiteralMode: Int,
        heredocExpandMode: Int,
    ) {
        val frame = heredocStack.lastOrNull()
        if (frame != null && matchesClosingDelimiterFromTokenStart(frame.spec)) {
            val indentCps = if (frame.spec.allowIndent) countLeadingIndentFromTokenStart() else 0
            val totalCps = indentCps + frame.spec.delimiterCodePoints.size
            if (totalCps > 1) {
                repeat(totalCps - 1) { _input.consume() }
            }
            type = heredocEndToken
            if (_input.LA(1) == IntStream.EOF) {
                advanceOrExitHeredoc(heredocLiteralMode, heredocExpandMode)
            } else {
                frame.closedPendingNewline = true
            }
            return
        }

        while (true) {
            val ch = _input.LA(1)
            if (ch == IntStream.EOF || ch == '\r'.code || ch == '\n'.code) break
            _input.consume()
        }
        frame?.atLineStart = false
    }

    protected fun markHeredocBody() {
        heredocStack.lastOrNull()?.atLineStart = false
    }

    protected fun markHeredocText(lexeme: String) {
        val frame = heredocStack.lastOrNull() ?: return
        if (frame.atLineStart && frame.spec.allowIndent && lexeme.all { it == ' ' || it == '\t' }) {
            return
        }
        frame.atLineStart = false
    }

    protected fun onHeredocNewline(heredocLiteralMode: Int, heredocExpandMode: Int) {
        val frame = heredocStack.lastOrNull() ?: return
        val closedEmptyDelim = frame.atLineStart && frame.spec.delimiter.isEmpty()
        if (frame.closedPendingNewline || closedEmptyDelim) {
            frame.closedPendingNewline = false
            advanceOrExitHeredoc(heredocLiteralMode, heredocExpandMode)
        } else {
            frame.atLineStart = true
            if (!frame.spec.isQuoted && isHeredocCloserAhead(frame.spec) && _mode != heredocLiteralMode) {
                mode(heredocLiteralMode)
            }
        }
    }

    private fun advanceOrExitHeredoc(heredocLiteralMode: Int, heredocExpandMode: Int) {
        if (heredocStack.isNotEmpty()) {
            heredocStack.removeAt(heredocStack.lastIndex)
        }
        if (pendingHeredocs.isNotEmpty()) {
            val nextSpec = pendingHeredocs.removeAt(0)
            heredocStack.add(HeredocFrame(spec = nextSpec))
            val nextMode = if (nextSpec.isQuoted || isHeredocCloserAhead(nextSpec)) {
                heredocLiteralMode
            } else {
                heredocExpandMode
            }
            if (_mode != nextMode) {
                mode(nextMode)
            }
        } else if (!_modeStack.isEmpty) {
            popMode()
            exprState = ExprState.BEG
            sawSpaceSinceLastToken = true
        }
    }

    private fun isHeredocCloserAhead(spec: HeredocSpec): Boolean {
        var offset = 1
        if (spec.allowIndent) {
            while (true) {
                val c = _input.LA(offset)
                if (c == ' '.code || c == '\t'.code) offset++ else break
            }
        }
        val delimCps = spec.delimiterCodePoints
        for (i in delimCps.indices) {
            if (_input.LA(offset + i) != delimCps[i]) return false
        }
        val after = _input.LA(offset + delimCps.size)
        return after == IntStream.EOF || after == '\r'.code || after == '\n'.code
    }

    private fun countLeadingIndentFromTokenStart(): Int {
        var offset = 0
        while (true) {
            val c = charAtOffsetFromTokenStart(offset)
            if (c == ' '.code || c == '\t'.code) offset++ else break
        }
        return offset
    }

    private fun matchesClosingDelimiterFromTokenStart(spec: HeredocSpec): Boolean {
        val offset = if (spec.allowIndent) countLeadingIndentFromTokenStart() else 0
        val delimCps = spec.delimiterCodePoints
        for (i in delimCps.indices) {
            if (charAtOffsetFromTokenStart(offset + i) != delimCps[i]) return false
        }
        val after = charAtOffsetFromTokenStart(offset + delimCps.size)
        return after == IntStream.EOF || after == '\r'.code || after == '\n'.code
    }

    private fun charAtOffsetFromTokenStart(offset: Int): Int =
        if (offset == 0) _input.LA(-1) else _input.LA(offset)

    private companion object {
        fun isEmbDocBoundary(ch: Int): Boolean =
            ch == IntStream.EOF ||
                ch == ' '.code ||
                ch == '\t'.code ||
                ch == '\r'.code ||
                ch == '\n'.code ||
                ch == '\u000B'.code ||
                ch == '\u000C'.code

        fun isRubySpaceOrEol(ch: Int): Boolean =
            ch == ' '.code ||
                ch == '\t'.code ||
                ch == '\r'.code ||
                ch == '\n'.code ||
                ch == '\u000B'.code ||
                ch == '\u000C'.code

        fun isAsciiAlnum(ch: Int): Boolean =
            ch in 'a'.code..'z'.code ||
                ch in 'A'.code..'Z'.code ||
                ch in '0'.code..'9'.code

        fun isRegexFlag(ch: Int): Boolean =
            ch == 'i'.code ||
                ch == 'm'.code ||
                ch == 'x'.code ||
                ch == 'o'.code ||
                ch == 'u'.code ||
                ch == 'e'.code ||
                ch == 's'.code ||
                ch == 'n'.code

        fun pairedCloseDelimiter(openCp: Int): Int = when (openCp) {
            '('.code -> ')'.code
            '['.code -> ']'.code
            '{'.code -> '}'.code
            '<'.code -> '>'.code
            else -> openCp
        }

        fun toCodePoints(s: String): IntArray {
            val cps = IntArray(s.length)
            var count = 0
            var i = 0
            while (i < s.length) {
                val ch = s[i]
                if (ch.isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate()) {
                    cps[count++] = ((ch.code - 0xD800) shl 10) + (s[i + 1].code - 0xDC00) + 0x10000
                    i += 2
                } else {
                    cps[count++] = ch.code
                    i++
                }
            }
            return if (count == cps.size) cps else cps.copyOf(count)
        }
    }
}
