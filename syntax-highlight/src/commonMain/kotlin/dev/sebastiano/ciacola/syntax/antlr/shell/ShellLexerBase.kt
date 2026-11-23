package dev.sebastiano.ciacola.syntax.antlr.shell

import org.antlr.v4.kotlinruntime.CharStream
import org.antlr.v4.kotlinruntime.IntStream
import org.antlr.v4.kotlinruntime.Lexer

/**
 * Pure Kotlin Multiplatform base lexer for [ShellLexer] tracking command/subshell/backtick
 * scopes, `case ... in ... esac` pattern boundaries, arithmetic paren nesting, parameter-expansion
 * brace nesting, and FIFO heredoc queues in O(N) time.
 */
internal abstract class ShellLexerBase(input: CharStream) : Lexer(input) {

    private enum class ScopeKind {
        TOP_LEVEL,
        CMD_SUB,
        BACKTICK,
        BACKTICK_ESCAPED,
    }

    private class CaseFrame(
        val baseParenDepth: Int,
        var awaitingWord: Boolean = true,
        var awaitingIn: Boolean = false,
        var inPattern: Boolean = false,
    )

    private class ScopeFrame(
        val kind: ScopeKind,
        var parenDepth: Int = 0,
        var atCommandPosition: Boolean = true,
        val caseStack: ArrayList<CaseFrame> = ArrayList(),
    )

    private class HeredocSpec(
        val delimiter: String,
        val delimiterCodePoints: IntArray,
        val stripTabs: Boolean,
        val isQuoted: Boolean,
    )

    private class HeredocFrame(
        val spec: HeredocSpec,
        var atLineStart: Boolean = true,
        var closedPendingNewline: Boolean = false,
    )

    private val scopeStack = ArrayList<ScopeFrame>().apply {
        add(ScopeFrame(ScopeKind.TOP_LEVEL))
    }
    private val paramExpBraceDepthStack = ArrayList<Int>()
    private val arithParenDepthStack = ArrayList<Int>()
    private val pendingHeredocs = ArrayList<HeredocSpec>()
    private val heredocStack = ArrayList<HeredocFrame>()
    private var pendingStripTabs: Boolean = false

    private fun currentScope(): ScopeFrame {
        if (scopeStack.isEmpty()) {
            scopeStack.add(ScopeFrame(ScopeKind.TOP_LEVEL))
        }
        return scopeStack.last()
    }

    protected fun isAtStartOfInput(): Boolean = _tokenStartCharIndex == 0

    protected fun isCommentStart(): Boolean {
        if (_tokenStartCharIndex == 0) return true
        val prev = _input.LA(-2)
        return prev == ' '.code ||
            prev == '\t'.code ||
            prev == '\r'.code ||
            prev == '\n'.code ||
            prev == '\u000C'.code ||
            prev == '\uFEFF'.code ||
            prev == ';'.code ||
            prev == '&'.code ||
            prev == '|'.code ||
            prev == '('.code ||
            prev == ')'.code ||
            prev == '<'.code ||
            prev == '>'.code ||
            prev == '`'.code
    }

    protected fun onCommandSeparator() {
        val scope = currentScope()
        scope.atCommandPosition = true
        val topCase = scope.caseStack.lastOrNull()
        if (topCase != null && topCase.awaitingWord) {
            topCase.awaitingWord = false
        }
    }

    protected fun onPipeOrListOp() {
        val scope = currentScope()
        val topCase = scope.caseStack.lastOrNull()
        if (topCase != null && topCase.inPattern && scope.parenDepth == topCase.baseParenDepth) {
            // Inside `case $x in a|b)`, `|` separates patterns rather than starting a command.
            scope.atCommandPosition = false
        } else {
            scope.atCommandPosition = true
        }
    }

    protected fun onCaseArmTerminator() {
        val scope = currentScope()
        val topCase = scope.caseStack.lastOrNull()
        if (topCase != null && scope.parenDepth == topCase.baseParenDepth) {
            topCase.awaitingWord = false
            topCase.awaitingIn = false
            topCase.inPattern = true
            scope.atCommandPosition = false
        } else {
            scope.atCommandPosition = true
        }
    }

    protected fun onDefaultWord(lexeme: String) {
        val scope = currentScope()
        val topCase = scope.caseStack.lastOrNull()
        if (topCase != null && scope.parenDepth == topCase.baseParenDepth) {
            when {
                topCase.awaitingWord -> {
                    topCase.awaitingWord = false
                    topCase.awaitingIn = true
                    scope.atCommandPosition = false
                    return
                }
                topCase.awaitingIn && lexeme == "in" -> {
                    topCase.awaitingIn = false
                    topCase.inPattern = true
                    scope.atCommandPosition = false
                    return
                }
                lexeme == "esac" && (scope.atCommandPosition || topCase.inPattern) -> {
                    scope.caseStack.removeAt(scope.caseStack.lastIndex)
                    scope.atCommandPosition = false
                    return
                }
            }
        }

        if (scope.atCommandPosition) {
            when (lexeme) {
                "case" -> {
                    scope.caseStack.add(CaseFrame(baseParenDepth = scope.parenDepth))
                    scope.atCommandPosition = false
                }
                "then", "do", "else", "elif", "if", "while", "until",
                "time", "coproc", "command", "builtin", "exec", "eval",
                -> scope.atCommandPosition = true
                else -> scope.atCommandPosition = false
            }
        }
    }

    protected fun onNonCommandArgToken() {
        val scope = currentScope()
        val topCase = scope.caseStack.lastOrNull()
        if (topCase != null && topCase.awaitingWord && scope.parenDepth == topCase.baseParenDepth) {
            topCase.awaitingWord = false
            topCase.awaitingIn = true
        }
        scope.atCommandPosition = false
    }

    protected fun onAssignmentEq() {
        // Plain `VAR=val` keeps `atCommandPosition` true only if we were at command position before
        // `VAR`; the RHS token consumes argument state, after which subsequent tokens can still be
        // handled cleanly by ShellSyntaxLexer.
    }

    protected fun onLParen() {
        val scope = currentScope()
        scope.parenDepth++
        val topCase = scope.caseStack.lastOrNull()
        if (topCase == null || !topCase.inPattern || scope.parenDepth - 1 != topCase.baseParenDepth) {
            scope.atCommandPosition = true
        }
    }

    protected fun onRParen() {
        val scope = currentScope()
        val topCase = scope.caseStack.lastOrNull()
        if (topCase != null && topCase.inPattern) {
            if (scope.parenDepth == topCase.baseParenDepth) {
                topCase.inPattern = false
                scope.atCommandPosition = true
                return
            }
            if (scope.parenDepth == topCase.baseParenDepth + 1) {
                scope.parenDepth--
                topCase.inPattern = false
                scope.atCommandPosition = true
                return
            }
        }
        if (scope.parenDepth > 0) {
            scope.parenDepth--
            scope.atCommandPosition = false
            return
        }
        if (scope.kind == ScopeKind.CMD_SUB && scopeStack.size > 1) {
            scopeStack.removeAt(scopeStack.lastIndex)
            if (!_modeStack.isEmpty) {
                popMode()
            }
            onNonCommandArgToken()
        }
    }

    protected fun pushCmdSub() {
        onNonCommandArgToken()
        scopeStack.add(ScopeFrame(ScopeKind.CMD_SUB))
        pushMode(DEFAULT_MODE)
    }

    protected fun onBacktick() {
        val top = currentScope()
        if (top.kind == ScopeKind.BACKTICK && scopeStack.size > 1) {
            scopeStack.removeAt(scopeStack.lastIndex)
            if (!_modeStack.isEmpty) {
                popMode()
            }
            onNonCommandArgToken()
        } else {
            onNonCommandArgToken()
            scopeStack.add(ScopeFrame(ScopeKind.BACKTICK))
            pushMode(DEFAULT_MODE)
        }
    }

    protected fun pushBacktick() {
        scopeStack.add(ScopeFrame(ScopeKind.BACKTICK))
        pushMode(DEFAULT_MODE)
    }

    protected fun onEscapedBacktick() {
        val top = currentScope()
        if (top.kind == ScopeKind.BACKTICK_ESCAPED && scopeStack.size > 1) {
            scopeStack.removeAt(scopeStack.lastIndex)
            if (!_modeStack.isEmpty) {
                popMode()
            }
            onNonCommandArgToken()
        } else if (top.kind == ScopeKind.BACKTICK) {
            onNonCommandArgToken()
            scopeStack.add(ScopeFrame(ScopeKind.BACKTICK_ESCAPED))
            pushMode(DEFAULT_MODE)
        } else {
            onNonCommandArgToken()
        }
    }

    protected fun pushDQuote(dquoteMode: Int) {
        onNonCommandArgToken()
        pushMode(dquoteMode)
    }

    protected fun pushParamExp(paramExpMode: Int) {
        onNonCommandArgToken()
        paramExpBraceDepthStack.add(0)
        pushMode(paramExpMode)
    }

    protected fun onParamExpLBrace() {
        if (paramExpBraceDepthStack.isNotEmpty()) {
            val idx = paramExpBraceDepthStack.lastIndex
            paramExpBraceDepthStack[idx] = paramExpBraceDepthStack[idx] + 1
        }
    }

    protected fun onParamExpRBrace() {
        if (paramExpBraceDepthStack.isNotEmpty()) {
            val idx = paramExpBraceDepthStack.lastIndex
            val depth = paramExpBraceDepthStack[idx]
            if (depth > 0) {
                paramExpBraceDepthStack[idx] = depth - 1
                return
            }
            paramExpBraceDepthStack.removeAt(idx)
        }
        if (!_modeStack.isEmpty) {
            popMode()
        }
    }

    protected fun pushArith(arithMode: Int) {
        onNonCommandArgToken()
        arithParenDepthStack.add(0)
        pushMode(arithMode)
    }

    protected fun onArithLParen() {
        if (arithParenDepthStack.isNotEmpty()) {
            val idx = arithParenDepthStack.lastIndex
            arithParenDepthStack[idx] = arithParenDepthStack[idx] + 1
        }
    }

    protected fun onArithRParen() {
        if (arithParenDepthStack.isNotEmpty()) {
            val idx = arithParenDepthStack.lastIndex
            val depth = arithParenDepthStack[idx]
            if (depth > 0) {
                arithParenDepthStack[idx] = depth - 1
            }
        }
    }

    protected fun canCloseArith(): Boolean =
        arithParenDepthStack.isEmpty() || arithParenDepthStack.last() == 0

    protected fun popArith() {
        if (arithParenDepthStack.isNotEmpty()) {
            arithParenDepthStack.removeAt(arithParenDepthStack.lastIndex)
        }
        if (!_modeStack.isEmpty) {
            popMode()
        }
    }

    protected fun onHeredocOp(stripTabs: Boolean, delimMode: Int) {
        if (hasHeredocDelimiterAhead()) {
            pendingStripTabs = stripTabs
            pushMode(delimMode)
        }
    }

    private fun hasHeredocDelimiterAhead(): Boolean {
        var offset = 1
        while (true) {
            val ch = _input.LA(offset)
            if (ch == ' '.code || ch == '\t'.code) {
                offset++
                continue
            }
            return ch != IntStream.EOF &&
                ch != '\r'.code &&
                ch != '\n'.code &&
                ch != ';'.code &&
                ch != '&'.code &&
                ch != '|'.code &&
                ch != '('.code &&
                ch != ')'.code &&
                ch != '<'.code &&
                ch != '>'.code &&
                ch != '#'.code
        }
    }

    protected fun enqueueHeredoc(rawDelimiter: String) {
        var isQuoted = false
        val unquoted = StringBuilder(rawDelimiter.length)
        var i = 0
        while (i < rawDelimiter.length) {
            when (val ch = rawDelimiter[i]) {
                '\'' -> {
                    isQuoted = true
                    i++
                    while (i < rawDelimiter.length && rawDelimiter[i] != '\'') {
                        unquoted.append(rawDelimiter[i])
                        i++
                    }
                    if (i < rawDelimiter.length && rawDelimiter[i] == '\'') i++
                }
                '"' -> {
                    isQuoted = true
                    i++
                    while (i < rawDelimiter.length && rawDelimiter[i] != '"') {
                        if (rawDelimiter[i] == '\\' && i + 1 < rawDelimiter.length) {
                            val next = rawDelimiter[i + 1]
                            if (next == '"' || next == '\\' || next == '$' || next == '`') {
                                unquoted.append(next)
                                i += 2
                                continue
                            }
                        }
                        unquoted.append(rawDelimiter[i])
                        i++
                    }
                    if (i < rawDelimiter.length && rawDelimiter[i] == '"') i++
                }
                '\\' -> {
                    isQuoted = true
                    if (i + 1 < rawDelimiter.length) {
                        unquoted.append(rawDelimiter[i + 1])
                        i += 2
                    } else {
                        i++
                    }
                }
                else -> {
                    unquoted.append(ch)
                    i++
                }
            }
        }
        val delimiter = unquoted.toString()
        pendingHeredocs.add(
            HeredocSpec(
                delimiter = delimiter,
                delimiterCodePoints = toCodePoints(delimiter),
                stripTabs = pendingStripTabs,
                isQuoted = isQuoted,
            ),
        )
        pendingStripTabs = false
    }

    protected fun onDefaultNewline(heredocLiteralMode: Int, heredocExpandMode: Int) {
        val scope = currentScope()
        scope.atCommandPosition = true
        if (pendingHeredocs.isNotEmpty()) {
            val nextSpec = pendingHeredocs.removeAt(0)
            heredocStack.add(HeredocFrame(spec = nextSpec))
            pushMode(if (nextSpec.isQuoted) heredocLiteralMode else heredocExpandMode)
        }
    }

    protected fun consumeHeredocLiteralLine(
        delimEndTokenType: Int,
        heredocLiteralMode: Int,
        heredocExpandMode: Int,
    ) {
        val frame = heredocStack.lastOrNull()
        if (frame != null) {
            val spec = frame.spec
            var offset = 0
            if (spec.stripTabs) {
                while (charAtOffsetFromTokenStart(offset) == '\t'.code) {
                    offset++
                }
            }
            var matched = true
            val delimCps = spec.delimiterCodePoints
            for (cpIdx in delimCps.indices) {
                if (charAtOffsetFromTokenStart(offset + cpIdx) != delimCps[cpIdx]) {
                    matched = false
                    break
                }
            }
            if (matched) {
                val after = charAtOffsetFromTokenStart(offset + delimCps.size)
                if (after == IntStream.EOF || after == '\r'.code || after == '\n'.code) {
                    val totalCps = offset + delimCps.size
                    if (totalCps > 1) {
                        repeat(totalCps - 1) { _input.consume() }
                    }
                    type = delimEndTokenType
                    if (_input.LA(1) == IntStream.EOF) {
                        advanceOrExitHeredoc(heredocLiteralMode, heredocExpandMode)
                    } else {
                        frame.closedPendingNewline = true
                    }
                    return
                }
            }
        }

        // Not the closing delimiter: consume the rest of the literal line up to CR/LF/EOF.
        while (true) {
            val ch = _input.LA(1)
            if (ch == IntStream.EOF || ch == '\r'.code || ch == '\n'.code) break
            _input.consume()
        }
        frame?.atLineStart = false
    }

    protected fun markHeredocLineBody() {
        heredocStack.lastOrNull()?.atLineStart = false
    }

    protected fun checkHeredocExpandSegment(
        delimEndTokenType: Int,
        heredocLiteralMode: Int,
        heredocExpandMode: Int,
    ) {
        val frame = heredocStack.lastOrNull() ?: return
        if (!frame.atLineStart) return
        val after = _input.LA(1)
        if (after != IntStream.EOF && after != '\r'.code && after != '\n'.code) {
            frame.atLineStart = false
            return
        }
        val segment = text
        val startOffset = if (frame.spec.stripTabs) {
            var i = 0
            while (i < segment.length && segment[i] == '\t') i++
            i
        } else {
            0
        }
        val delim = frame.spec.delimiter
        if (segment.length - startOffset == delim.length &&
            segment.regionMatches(startOffset, delim, 0, delim.length)
        ) {
            type = delimEndTokenType
            if (after == IntStream.EOF) {
                advanceOrExitHeredoc(heredocLiteralMode, heredocExpandMode)
            } else {
                frame.closedPendingNewline = true
            }
        } else {
            frame.atLineStart = false
        }
    }

    protected fun onHeredocNewline(heredocLiteralMode: Int, heredocExpandMode: Int) {
        val frame = heredocStack.lastOrNull() ?: return
        val closedEmptyDelim = frame.atLineStart && frame.spec.delimiter.isEmpty()
        if (frame.closedPendingNewline || closedEmptyDelim) {
            frame.closedPendingNewline = false
            advanceOrExitHeredoc(heredocLiteralMode, heredocExpandMode)
        } else {
            frame.atLineStart = true
        }
    }

    private fun advanceOrExitHeredoc(heredocLiteralMode: Int, heredocExpandMode: Int) {
        if (heredocStack.isNotEmpty()) {
            heredocStack.removeAt(heredocStack.lastIndex)
        }
        if (pendingHeredocs.isNotEmpty()) {
            val nextSpec = pendingHeredocs.removeAt(0)
            heredocStack.add(HeredocFrame(spec = nextSpec))
            val nextMode = if (nextSpec.isQuoted) heredocLiteralMode else heredocExpandMode
            if (_mode != nextMode) {
                mode(nextMode)
            }
        } else if (!_modeStack.isEmpty) {
            popMode()
        }
    }

    private fun charAtOffsetFromTokenStart(offset: Int): Int =
        if (offset == 0) _input.LA(-1) else _input.LA(offset)

    override fun reset() {
        super.reset()
        scopeStack.clear()
        scopeStack.add(ScopeFrame(ScopeKind.TOP_LEVEL))
        paramExpBraceDepthStack.clear()
        arithParenDepthStack.clear()
        pendingHeredocs.clear()
        heredocStack.clear()
        pendingStripTabs = false
    }

    private companion object {
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
