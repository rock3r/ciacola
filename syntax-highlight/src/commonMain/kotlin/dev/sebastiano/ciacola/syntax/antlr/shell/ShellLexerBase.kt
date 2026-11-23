package dev.sebastiano.ciacola.syntax.antlr.shell

import org.antlr.v4.kotlinruntime.CharStream
import org.antlr.v4.kotlinruntime.IntStream
import org.antlr.v4.kotlinruntime.Lexer

/**
 * Pure Kotlin Multiplatform base lexer for [ShellLexer] tracking command/subshell/backtick
 * scopes, `case ... in ... esac` pattern boundaries, arithmetic paren nesting, parameter-expansion
 * brace nesting, O(N) balanced `((` / `$((` lookahead, and FIFO heredoc queues in O(N) time.
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
        var expectingFuncHeaderName: Boolean = false,
        var afterFuncHeader: Boolean = false,
        var sawFuncDeclCommand: Boolean = false,
        val caseStack: ArrayList<CaseFrame> = ArrayList(),
        val pendingHeredocs: ArrayList<HeredocSpec> = ArrayList(),
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

    private class ArithScanEntry(
        val doubleStartPos: Int = -1,
    )

    private class ScanFrame(
        val isDQuote: Boolean,
        val isDQuoteCmdSub: Boolean = false,
        val openParens: ArrayList<ArithScanEntry> = ArrayList(),
    )

    private val scopeStack = ArrayList<ScopeFrame>().apply {
        add(ScopeFrame(ScopeKind.TOP_LEVEL))
    }
    private val paramExpBraceDepthStack = ArrayList<Int>()
    private val arithParenDepthStack = ArrayList<Int>()
    private val arithBalanceCache = HashMap<Int, Boolean>()
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
        return isCommentBoundaryChar(_input.LA(-2))
    }

    protected fun onCommandSeparator() {
        val scope = currentScope()
        scope.atCommandPosition = true
        scope.expectingFuncHeaderName = false
        scope.afterFuncHeader = false
        scope.sawFuncDeclCommand = false
        val topCase = scope.caseStack.lastOrNull()
        if (topCase != null && topCase.awaitingWord) {
            topCase.awaitingWord = false
        }
    }

    protected fun onLBrace() {
        val scope = currentScope()
        val precededByBoundary = _tokenStartCharIndex == 0 || isBraceOpenBoundaryChar(_input.LA(-2))
        val next = _input.LA(1)
        val followedByWsOrEof = next == IntStream.EOF ||
            next == ' '.code ||
            next == '\t'.code ||
            next == '\r'.code ||
            next == '\n'.code ||
            next == '\u000C'.code
        if ((scope.atCommandPosition || scope.afterFuncHeader) && precededByBoundary && followedByWsOrEof) {
            scope.atCommandPosition = true
            scope.expectingFuncHeaderName = false
            scope.afterFuncHeader = false
            scope.sawFuncDeclCommand = false
            val topCase = scope.caseStack.lastOrNull()
            if (topCase != null && topCase.awaitingWord) {
                topCase.awaitingWord = false
            }
        } else {
            scope.afterFuncHeader = false
            scope.sawFuncDeclCommand = false
            onNonCommandArgToken()
        }
    }

    protected fun onPipeOrListOp() {
        val scope = currentScope()
        scope.expectingFuncHeaderName = false
        scope.afterFuncHeader = false
        scope.sawFuncDeclCommand = false
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
        scope.expectingFuncHeaderName = false
        scope.afterFuncHeader = false
        scope.sawFuncDeclCommand = false
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
        if (scope.expectingFuncHeaderName) {
            scope.expectingFuncHeaderName = false
            scope.afterFuncHeader = true
            scope.atCommandPosition = false
            return
        }
        scope.afterFuncHeader = false

        val topCase = scope.caseStack.lastOrNull()
        if (topCase != null && scope.parenDepth == topCase.baseParenDepth) {
            when {
                topCase.awaitingWord -> {
                    topCase.awaitingWord = false
                    topCase.awaitingIn = true
                    scope.atCommandPosition = false
                    scope.sawFuncDeclCommand = false
                    return
                }
                topCase.awaitingIn && lexeme == "in" -> {
                    topCase.awaitingIn = false
                    topCase.inPattern = true
                    scope.atCommandPosition = false
                    scope.sawFuncDeclCommand = false
                    return
                }
                lexeme == "esac" && (scope.atCommandPosition || topCase.inPattern) -> {
                    scope.caseStack.removeAt(scope.caseStack.lastIndex)
                    scope.atCommandPosition = false
                    scope.sawFuncDeclCommand = false
                    return
                }
            }
        }

        if (scope.atCommandPosition) {
            when (lexeme) {
                "case" -> {
                    scope.caseStack.add(CaseFrame(baseParenDepth = scope.parenDepth))
                    scope.atCommandPosition = false
                    scope.sawFuncDeclCommand = false
                }
                "function", "coproc" -> {
                    scope.expectingFuncHeaderName = true
                    scope.atCommandPosition = false
                    scope.sawFuncDeclCommand = false
                }
                "then", "do", "else", "elif", "if", "while", "until",
                "time", "command", "builtin", "exec", "eval",
                -> {
                    scope.atCommandPosition = true
                    scope.sawFuncDeclCommand = false
                }
                else -> {
                    scope.atCommandPosition = false
                    scope.sawFuncDeclCommand = isFollowingEmptyParens()
                }
            }
        } else {
            scope.sawFuncDeclCommand = false
        }
    }

    private fun isFollowingEmptyParens(): Boolean {
        var offset = 1
        while (true) {
            val ch = _input.LA(offset)
            if (ch == ' '.code || ch == '\t'.code) {
                offset++
            } else if (ch == '('.code) {
                offset++
                while (true) {
                    val ch2 = _input.LA(offset)
                    if (ch2 == ' '.code || ch2 == '\t'.code) {
                        offset++
                    } else {
                        return ch2 == ')'.code
                    }
                }
            } else {
                return false
            }
        }
    }

    protected fun onNonCommandArgToken() {
        val scope = currentScope()
        scope.expectingFuncHeaderName = false
        scope.afterFuncHeader = false
        scope.sawFuncDeclCommand = false
        val topCase = scope.caseStack.lastOrNull()
        if (topCase != null && topCase.awaitingWord && scope.parenDepth == topCase.baseParenDepth) {
            topCase.awaitingWord = false
            topCase.awaitingIn = true
        }
        scope.atCommandPosition = false
    }

    protected fun onAssignmentEq() {
        val scope = currentScope()
        scope.expectingFuncHeaderName = false
        scope.afterFuncHeader = false
        scope.sawFuncDeclCommand = false
    }

    protected fun onLParen() {
        val scope = currentScope()
        scope.parenDepth++
        if (scope.sawFuncDeclCommand) {
            // `foo()` keeps `sawFuncDeclCommand` until `)` so `foo() { ... }` knows `{` opens a body.
            scope.atCommandPosition = false
            return
        }
        scope.afterFuncHeader = false
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
                scope.sawFuncDeclCommand = false
                return
            }
            if (scope.parenDepth == topCase.baseParenDepth + 1) {
                scope.parenDepth--
                topCase.inPattern = false
                scope.atCommandPosition = true
                scope.sawFuncDeclCommand = false
                return
            }
        }
        if (scope.parenDepth > 0) {
            scope.parenDepth--
            scope.atCommandPosition = false
            if (scope.sawFuncDeclCommand) {
                scope.sawFuncDeclCommand = false
                scope.afterFuncHeader = true
            }
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

    /**
     * Verifies in amortized O(N) time (memoized via [arithBalanceCache]) that a matched `((` or
     * `$((` closes with `))` before an unmatched single `)` at depth 0, distinguishing arithmetic
     * expressions from nested subshells like `((echo a) && (echo b))`.
     */
    protected fun hasBalancedDoubleParenClose(): Boolean {
        val entryPos = _tokenStartCharIndex
        arithBalanceCache[entryPos]?.let { return it }

        val frames = ArrayList<ScanFrame>()
        val rootOpen = ArrayList<ArithScanEntry>().apply {
            add(ArithScanEntry(doubleStartPos = -1))
            add(ArithScanEntry(doubleStartPos = entryPos))
        }
        frames.add(ScanFrame(isDQuote = false, openParens = rootOpen))

        var offset = 1
        val baseIndex = _input.index()
        var prevChar = '('.code

        while (true) {
            val ch = _input.LA(offset)
            if (ch == IntStream.EOF) break
            val frame = frames.last()

            if (frame.isDQuote) {
                when (ch) {
                    '\\'.code -> {
                        val next = _input.LA(offset + 1)
                        offset += if (next == IntStream.EOF) 1 else 2
                        prevChar = next
                    }
                    '"'.code -> {
                        frames.removeAt(frames.lastIndex)
                        offset++
                        prevChar = ch
                    }
                    '$'.code -> {
                        val n1 = _input.LA(offset + 1)
                        if (n1 == '('.code) {
                            if (_input.LA(offset + 2) == '('.code) {
                                val nestedStart = baseIndex + offset - 1
                                val open = ArrayList<ArithScanEntry>().apply {
                                    add(ArithScanEntry(doubleStartPos = -1))
                                    add(ArithScanEntry(doubleStartPos = nestedStart))
                                }
                                frames.add(ScanFrame(isDQuote = false, isDQuoteCmdSub = true, openParens = open))
                                offset += 3
                                prevChar = '('.code
                            } else {
                                val open = ArrayList<ArithScanEntry>().apply {
                                    add(ArithScanEntry(doubleStartPos = -1))
                                }
                                frames.add(ScanFrame(isDQuote = false, isDQuoteCmdSub = true, openParens = open))
                                offset += 2
                                prevChar = '('.code
                            }
                        } else {
                            offset++
                            prevChar = ch
                        }
                    }
                    else -> {
                        offset++
                        prevChar = ch
                    }
                }
                continue
            }

            when (ch) {
                '\\'.code -> {
                    val next = _input.LA(offset + 1)
                    offset += if (next == IntStream.EOF) 1 else 2
                    prevChar = next
                }
                '\''.code -> {
                    offset++
                    while (true) {
                        val qch = _input.LA(offset)
                        if (qch == IntStream.EOF) break
                        if (prevChar == '$'.code && qch == '\\'.code) {
                            offset += if (_input.LA(offset + 1) == IntStream.EOF) 1 else 2
                            continue
                        }
                        offset++
                        if (qch == '\''.code) break
                    }
                    prevChar = '\''.code
                }
                '"'.code -> {
                    frames.add(ScanFrame(isDQuote = true))
                    offset++
                    prevChar = ch
                }
                '#'.code -> {
                    if (isCommentBoundaryChar(prevChar)) {
                        offset++
                        while (true) {
                            val cch = _input.LA(offset)
                            if (cch == IntStream.EOF || cch == '\r'.code || cch == '\n'.code) break
                            offset++
                        }
                        prevChar = '\n'.code
                    } else {
                        offset++
                        prevChar = ch
                    }
                }
                '$'.code -> {
                    if (_input.LA(offset + 1) == '('.code) {
                        if (_input.LA(offset + 2) == '('.code) {
                            val nestedStart = baseIndex + offset - 1
                            frame.openParens.add(ArithScanEntry(doubleStartPos = -1))
                            frame.openParens.add(ArithScanEntry(doubleStartPos = nestedStart))
                            offset += 3
                            prevChar = '('.code
                        } else {
                            frame.openParens.add(ArithScanEntry(doubleStartPos = -1))
                            offset += 2
                            prevChar = '('.code
                        }
                    } else {
                        offset++
                        prevChar = ch
                    }
                }
                '('.code -> {
                    if (_input.LA(offset + 1) == '('.code) {
                        val nestedStart = baseIndex + offset - 1
                        frame.openParens.add(ArithScanEntry(doubleStartPos = -1))
                        frame.openParens.add(ArithScanEntry(doubleStartPos = nestedStart))
                        offset += 2
                        prevChar = '('.code
                    } else {
                        frame.openParens.add(ArithScanEntry(doubleStartPos = -1))
                        offset++
                        prevChar = ch
                    }
                }
                ')'.code -> {
                    val open = frame.openParens
                    if (open.isNotEmpty()) {
                        val top = open.removeAt(open.lastIndex)
                        if (top.doubleStartPos >= 0) {
                            if (_input.LA(offset + 1) == ')'.code) {
                                arithBalanceCache[top.doubleStartPos] = true
                                if (open.isNotEmpty()) open.removeAt(open.lastIndex)
                                offset += 2
                                prevChar = ')'.code
                                if (open.isEmpty()) {
                                    if (frame.isDQuoteCmdSub && frames.size > 1) {
                                        frames.removeAt(frames.lastIndex)
                                    } else {
                                        return arithBalanceCache[entryPos] ?: true
                                    }
                                }
                                continue
                            } else {
                                arithBalanceCache[top.doubleStartPos] = false
                                offset++
                                prevChar = ch
                                continue
                            }
                        } else {
                            offset++
                            prevChar = ch
                            if (open.isEmpty()) {
                                if (frame.isDQuoteCmdSub && frames.size > 1) {
                                    frames.removeAt(frames.lastIndex)
                                } else {
                                    return arithBalanceCache[entryPos] ?: false
                                }
                            }
                            continue
                        }
                    } else {
                        offset++
                        prevChar = ch
                        if (frames.size == 1) break
                    }
                }
                else -> {
                    offset++
                    prevChar = ch
                }
            }
        }

        for (frame in frames) {
            for (entry in frame.openParens) {
                if (entry.doubleStartPos >= 0 && entry.doubleStartPos !in arithBalanceCache) {
                    arithBalanceCache[entry.doubleStartPos] = true
                }
            }
        }
        return arithBalanceCache[entryPos] ?: true
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
        if (arithParenDepthStack.isEmpty()) {
            if (!_modeStack.isEmpty) popMode()
            return
        }
        val idx = arithParenDepthStack.lastIndex
        val depth = arithParenDepthStack[idx]
        if (depth > 0) {
            arithParenDepthStack[idx] = depth - 1
        } else {
            // Malformed input hit an unmatched ')' inside ARITH: pop safely instead of leaking.
            arithParenDepthStack.removeAt(idx)
            if (!_modeStack.isEmpty) {
                popMode()
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
                '$' -> {
                    val next = if (i + 1 < rawDelimiter.length) rawDelimiter[i + 1] else '\u0000'
                    if (next == '\'') {
                        isQuoted = true
                        i += 2
                        while (i < rawDelimiter.length && rawDelimiter[i] != '\'') {
                            if (rawDelimiter[i] == '\\' && i + 1 < rawDelimiter.length) {
                                i = decodeAnsiCEscape(rawDelimiter, i + 1, unquoted)
                            } else {
                                unquoted.append(rawDelimiter[i])
                                i++
                            }
                        }
                        if (i < rawDelimiter.length && rawDelimiter[i] == '\'') i++
                    } else if (next == '"') {
                        isQuoted = true
                        i += 2
                        i = appendDoubleQuotedSegment(rawDelimiter, i, unquoted)
                    } else {
                        unquoted.append(ch)
                        i++
                    }
                }
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
                    i = appendDoubleQuotedSegment(rawDelimiter, i, unquoted)
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
        currentScope().pendingHeredocs.add(
            HeredocSpec(
                delimiter = delimiter,
                delimiterCodePoints = toCodePoints(delimiter),
                stripTabs = pendingStripTabs,
                isQuoted = isQuoted,
            ),
        )
        pendingStripTabs = false
    }

    private fun appendDoubleQuotedSegment(raw: String, startIndex: Int, out: StringBuilder): Int {
        var i = startIndex
        while (i < raw.length && raw[i] != '"') {
            if (raw[i] == '\\' && i + 1 < raw.length) {
                val next = raw[i + 1]
                if (next == '"' || next == '\\' || next == '$' || next == '`') {
                    out.append(next)
                    i += 2
                    continue
                }
            }
            out.append(raw[i])
            i++
        }
        if (i < raw.length && raw[i] == '"') i++
        return i
    }

    private fun decodeAnsiCEscape(raw: String, index: Int, out: StringBuilder): Int {
        val esc = raw[index]
        return when (esc) {
            'a' -> {
                out.append('\u0007')
                index + 1
            }
            'b' -> {
                out.append('\b')
                index + 1
            }
            'e', 'E' -> {
                out.append('\u001B')
                index + 1
            }
            'f' -> {
                out.append('\u000C')
                index + 1
            }
            'n' -> {
                out.append('\n')
                index + 1
            }
            'r' -> {
                out.append('\r')
                index + 1
            }
            't' -> {
                out.append('\t')
                index + 1
            }
            'v' -> {
                out.append('\u000B')
                index + 1
            }
            '\\', '\'', '"', '?' -> {
                out.append(esc)
                index + 1
            }
            in '0'..'7' -> {
                var value = 0
                var j = index
                val limit = minOf(raw.length, index + 3)
                while (j < limit && raw[j] in '0'..'7') {
                    value = (value shl 3) or (raw[j] - '0')
                    j++
                }
                out.append((value and 0xFF).toChar())
                j
            }
            'x' -> decodeHexAnsiCEscape(raw, index, maxDigits = 2, out = out, allowUnicode = false)
            'u' -> decodeHexAnsiCEscape(raw, index, maxDigits = 4, out = out, allowUnicode = true)
            'U' -> decodeHexAnsiCEscape(raw, index, maxDigits = 8, out = out, allowUnicode = true)
            'c' -> {
                if (index + 1 < raw.length && raw[index + 1] != '\'') {
                    out.append((raw[index + 1].code and 0x1F).toChar())
                    index + 2
                } else {
                    out.append("\\c")
                    index + 1
                }
            }
            else -> {
                out.append('\\').append(esc)
                index + 1
            }
        }
    }

    private fun decodeHexAnsiCEscape(
        raw: String,
        escIndex: Int,
        maxDigits: Int,
        out: StringBuilder,
        allowUnicode: Boolean,
    ): Int {
        var value = 0
        var j = escIndex + 1
        val limit = minOf(raw.length, j + maxDigits)
        var digits = 0
        while (j < limit) {
            val d = hexDigitValue(raw[j])
            if (d < 0) break
            value = (value shl 4) or d
            digits++
            j++
        }
        if (digits == 0) {
            out.append('\\').append(raw[escIndex])
            return escIndex + 1
        }
        if (!allowUnicode) {
            out.append((value and 0xFF).toChar())
        } else if (value in 0..0xFFFF && value !in 0xD800..0xDFFF) {
            out.append(value.toChar())
        } else if (value in 0x10000..0x10FFFF) {
            val cp = value - 0x10000
            out.append(((cp ushr 10) + 0xD800).toChar())
            out.append(((cp and 0x3FF) + 0xDC00).toChar())
        } else {
            out.append('\\').append(raw, escIndex, j)
        }
        return j
    }

    private fun hexDigitValue(ch: Char): Int = when (ch) {
        in '0'..'9' -> ch - '0'
        in 'a'..'f' -> ch - 'a' + 10
        in 'A'..'F' -> ch - 'A' + 10
        else -> -1
    }

    protected fun onDefaultNewline(heredocLiteralMode: Int, heredocExpandMode: Int) {
        val scope = currentScope()
        scope.atCommandPosition = true
        scope.expectingFuncHeaderName = false
        scope.afterFuncHeader = false
        scope.sawFuncDeclCommand = false
        val pending = scope.pendingHeredocs
        if (pending.isNotEmpty()) {
            val nextSpec = pending.removeAt(0)
            heredocStack.add(HeredocFrame(spec = nextSpec))
            val targetMode = if (nextSpec.isQuoted || isClosingDelimiterAhead(nextSpec)) {
                heredocLiteralMode
            } else {
                heredocExpandMode
            }
            pushMode(targetMode)
        }
    }

    protected fun consumeHeredocLiteralLine(
        delimEndTokenType: Int,
        heredocLiteralMode: Int,
        heredocExpandMode: Int,
    ) {
        val frame = heredocStack.lastOrNull()
        if (frame != null && matchesClosingDelimiterFromTokenStart(frame.spec)) {
            val offset = if (frame.spec.stripTabs) countLeadingTabsFromTokenStart() else 0
            val totalCps = offset + frame.spec.delimiterCodePoints.size
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

    protected fun onHeredocNewline(heredocLiteralMode: Int, heredocExpandMode: Int) {
        val frame = heredocStack.lastOrNull() ?: return
        val closedEmptyDelim = frame.atLineStart && frame.spec.delimiter.isEmpty()
        if (frame.closedPendingNewline || closedEmptyDelim) {
            frame.closedPendingNewline = false
            advanceOrExitHeredoc(heredocLiteralMode, heredocExpandMode)
        } else {
            frame.atLineStart = true
            if (!frame.spec.isQuoted && isClosingDelimiterAhead(frame.spec) && _mode != heredocLiteralMode) {
                // Switch to literal mode for the closing delimiter line so delimiters with `$`/`\\`/`` ` `` match in O(1).
                mode(heredocLiteralMode)
            }
        }
    }

    private fun isClosingDelimiterAhead(spec: HeredocSpec): Boolean {
        var offset = 1
        if (spec.stripTabs) {
            while (_input.LA(offset) == '\t'.code) {
                offset++
            }
        }
        val delimCps = spec.delimiterCodePoints
        for (cpIdx in delimCps.indices) {
            if (_input.LA(offset + cpIdx) != delimCps[cpIdx]) {
                return false
            }
        }
        val after = _input.LA(offset + delimCps.size)
        return after == IntStream.EOF || after == '\r'.code || after == '\n'.code
    }

    private fun countLeadingTabsFromTokenStart(): Int {
        var offset = 0
        while (charAtOffsetFromTokenStart(offset) == '\t'.code) {
            offset++
        }
        return offset
    }

    private fun matchesClosingDelimiterFromTokenStart(spec: HeredocSpec): Boolean {
        val offset = if (spec.stripTabs) countLeadingTabsFromTokenStart() else 0
        val delimCps = spec.delimiterCodePoints
        for (cpIdx in delimCps.indices) {
            if (charAtOffsetFromTokenStart(offset + cpIdx) != delimCps[cpIdx]) {
                return false
            }
        }
        val after = charAtOffsetFromTokenStart(offset + delimCps.size)
        return after == IntStream.EOF || after == '\r'.code || after == '\n'.code
    }

    private fun advanceOrExitHeredoc(heredocLiteralMode: Int, heredocExpandMode: Int) {
        if (heredocStack.isNotEmpty()) {
            heredocStack.removeAt(heredocStack.lastIndex)
        }
        val pending = currentScope().pendingHeredocs
        if (pending.isNotEmpty()) {
            val nextSpec = pending.removeAt(0)
            heredocStack.add(HeredocFrame(spec = nextSpec))
            val nextMode = if (nextSpec.isQuoted || isClosingDelimiterAhead(nextSpec)) {
                heredocLiteralMode
            } else {
                heredocExpandMode
            }
            if (_mode != nextMode) {
                mode(nextMode)
            }
        } else if (!_modeStack.isEmpty) {
            popMode()
            val scope = currentScope()
            scope.atCommandPosition = true
            scope.expectingFuncHeaderName = false
            scope.afterFuncHeader = false
            scope.sawFuncDeclCommand = false
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
        arithBalanceCache.clear()
        heredocStack.clear()
        pendingStripTabs = false
    }

    private companion object {
        fun isCommentBoundaryChar(ch: Int): Boolean =
            ch == ' '.code ||
                ch == '\t'.code ||
                ch == '\r'.code ||
                ch == '\n'.code ||
                ch == '\u000C'.code ||
                ch == '\uFEFF'.code ||
                ch == ';'.code ||
                ch == '&'.code ||
                ch == '|'.code ||
                ch == '('.code ||
                ch == ')'.code ||
                ch == '<'.code ||
                ch == '>'.code ||
                ch == '`'.code

        fun isBraceOpenBoundaryChar(ch: Int): Boolean =
            isCommentBoundaryChar(ch) || ch == '{'.code || ch == '}'.code

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
