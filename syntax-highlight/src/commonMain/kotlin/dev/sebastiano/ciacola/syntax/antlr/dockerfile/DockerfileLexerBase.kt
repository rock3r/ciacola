package dev.sebastiano.ciacola.syntax.antlr.dockerfile

import org.antlr.v4.kotlinruntime.CharStream
import org.antlr.v4.kotlinruntime.IntStream
import org.antlr.v4.kotlinruntime.Lexer

/**
 * Pure Kotlin Multiplatform base lexer for [DockerfileLexer].
 *
 * Tracks parser directives (`# syntax=`, `# escape=`, `# check=`) at the top of the file,
 * dynamic escape character switching (`\` vs `` ` ``) for line continuations and double-quoted
 * strings, logical instruction boundaries across continuations and interleaved `#` comments,
 * and FIFO-queued BuildKit heredocs (`<<EOF`, `<<-EOF`, `<<'EOF'`, `<<"EOF"`) in strictly O(N) time.
 */
internal abstract class DockerfileLexerBase(input: CharStream) : Lexer(input) {

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

    private var escapeChar: Char = '\\'
    private var directivesActive: Boolean = true
    private var atLineStart: Boolean = true
    private var atInstructionStart: Boolean = true
    private var atWordBoundary: Boolean = true
    private var canAcceptHeredoc: Boolean = false
    private var canStartJsonArray: Boolean = false
    private var canStartJsonSuppressesExpansion: Boolean = false
    private var inHealthcheckHeader: Boolean = false
    private var inFlagPosition: Boolean = false
    private var inFlagValue: Boolean = false
    private var inJsonArray: Boolean = false
    private var jsonSuppressesExpansion: Boolean = false
    private var inJsonString: Boolean = false
    private var continuationPending: Boolean = false
    private var commentLineInContinuation: Boolean = false
    private var dqAtLineStart: Boolean = false
    private var dqAfterComment: Boolean = false
    private var peAtLineStart: Boolean = false
    private var peAfterComment: Boolean = false
    private var paramExpBraceDepth: Int = 0
    private val paramExpDepthStack = ArrayList<Int>()
    private val pendingHeredocs = ArrayDeque<HeredocSpec>()
    private val heredocStack = ArrayList<HeredocFrame>()

    override fun reset() {
        super.reset()
        escapeChar = '\\'
        directivesActive = true
        atLineStart = true
        atInstructionStart = true
        atWordBoundary = true
        canAcceptHeredoc = false
        canStartJsonArray = false
        canStartJsonSuppressesExpansion = false
        inHealthcheckHeader = false
        inFlagPosition = false
        inFlagValue = false
        inJsonArray = false
        jsonSuppressesExpansion = false
        inJsonString = false
        continuationPending = false
        commentLineInContinuation = false
        dqAtLineStart = false
        dqAfterComment = false
        peAtLineStart = false
        peAfterComment = false
        paramExpBraceDepth = 0
        paramExpDepthStack.clear()
        pendingHeredocs.clear()
        heredocStack.clear()
    }

    protected fun isEscapeBackslash(): Boolean = escapeChar == '\\'

    protected fun isEscapeBacktick(): Boolean = escapeChar == '`'

    protected fun isInJsonString(): Boolean = inJsonString && _modeStack.size() == 1

    protected fun isInJsonNoExpandString(): Boolean = isInJsonString() && jsonSuppressesExpansion

    protected fun isBacktickContAfterJsonBs(): Boolean {
        if (escapeChar != '`' || isInsideHeredocBody()) return false
        return matchLineContinuationEnd(1) > 0
    }

    protected fun isInsideHeredocBody(): Boolean = heredocStack.isNotEmpty()

    protected fun isCommentStart(): Boolean = atLineStart

    protected fun isDqAtLineStart(): Boolean = dqAtLineStart

    protected fun isDqCommentStart(): Boolean = dqAtLineStart

    protected fun isDqAfterComment(): Boolean = dqAfterComment

    protected fun isPeCommentStart(): Boolean = peAtLineStart

    protected fun isPeContinuationLineStart(): Boolean = peAtLineStart || peAfterComment

    protected fun isHeredocAllowed(): Boolean = canAcceptHeredoc && atWordBoundary

    protected fun onWs() {
        // Leading horizontal whitespace on a line does not end line-start comment/directive status.
        atWordBoundary = true
        inFlagValue = false
    }

    protected fun onNonWsToken() {
        directivesActive = false
        atLineStart = false
        atWordBoundary = false
        if (!inFlagValue) {
            canStartJsonArray = false
            canStartJsonSuppressesExpansion = false
            inFlagPosition = false
            inHealthcheckHeader = false
            inJsonArray = false
            jsonSuppressesExpansion = false
        }
    }

    protected fun onDelimiterToken(keepsWordBoundary: Boolean) {
        directivesActive = false
        atLineStart = false
        atInstructionStart = false
        atWordBoundary = keepsWordBoundary
        if (!inFlagValue) {
            canStartJsonArray = false
            canStartJsonSuppressesExpansion = false
            inFlagPosition = false
            inHealthcheckHeader = false
            inJsonArray = false
            jsonSuppressesExpansion = false
        }
    }

    protected fun onFlag() {
        directivesActive = false
        atLineStart = false
        atInstructionStart = false
        atWordBoundary = false
        if (inFlagPosition) {
            inFlagValue = true
        } else {
            canStartJsonArray = false
            canStartJsonSuppressesExpansion = false
            inHealthcheckHeader = false
            inJsonArray = false
            jsonSuppressesExpansion = false
        }
    }

    protected fun onLBrack() {
        directivesActive = false
        atLineStart = false
        atInstructionStart = false
        atWordBoundary = true
        if (!inFlagValue) {
            if (canStartJsonArray && isValidJsonStringArrayAhead()) {
                inJsonArray = true
                jsonSuppressesExpansion = canStartJsonSuppressesExpansion
            } else {
                inJsonArray = false
                jsonSuppressesExpansion = false
            }
            canStartJsonArray = false
            canStartJsonSuppressesExpansion = false
            inFlagPosition = false
            inHealthcheckHeader = false
        }
    }

    protected fun onRBrack() {
        directivesActive = false
        atLineStart = false
        atInstructionStart = false
        atWordBoundary = false
        if (!inFlagValue) {
            inJsonArray = false
            jsonSuppressesExpansion = false
            canStartJsonArray = false
            canStartJsonSuppressesExpansion = false
            inFlagPosition = false
            inHealthcheckHeader = false
        }
    }

    protected fun onComma() {
        directivesActive = false
        atLineStart = false
        atInstructionStart = false
        atWordBoundary = true
        if (!inFlagValue && !inJsonArray) {
            canStartJsonArray = false
            canStartJsonSuppressesExpansion = false
            inFlagPosition = false
            inHealthcheckHeader = false
        }
    }

    protected fun onWord(lexeme: String) {
        directivesActive = false
        atLineStart = false
        atWordBoundary = false
        if (atInstructionStart) {
            val upper = lexeme.uppercase()
            if (upper == "ONBUILD") {
                atInstructionStart = true
                canAcceptHeredoc = false
                canStartJsonArray = false
                canStartJsonSuppressesExpansion = false
                inHealthcheckHeader = false
                inFlagPosition = false
                inFlagValue = false
                inJsonArray = false
                jsonSuppressesExpansion = false
            } else if (upper == "HEALTHCHECK") {
                atInstructionStart = false
                canAcceptHeredoc = false
                canStartJsonArray = false
                canStartJsonSuppressesExpansion = false
                inHealthcheckHeader = true
                inFlagPosition = true
                inFlagValue = false
                inJsonArray = false
                jsonSuppressesExpansion = false
            } else {
                atInstructionStart = false
                canAcceptHeredoc = upper == "RUN" || upper == "COPY" || upper == "ADD"
                canStartJsonArray = upper == "RUN" ||
                    upper == "CMD" ||
                    upper == "ENTRYPOINT" ||
                    upper == "VOLUME" ||
                    upper == "SHELL" ||
                    upper == "COPY" ||
                    upper == "ADD"
                canStartJsonSuppressesExpansion = upper == "RUN" ||
                    upper == "CMD" ||
                    upper == "ENTRYPOINT" ||
                    upper == "SHELL"
                inHealthcheckHeader = false
                inFlagPosition = upper == "RUN" || upper == "COPY" || upper == "ADD" || upper == "FROM"
                inFlagValue = false
                inJsonArray = false
                jsonSuppressesExpansion = false
            }
        } else if (inHealthcheckHeader && !inFlagValue) {
            inHealthcheckHeader = false
            inFlagPosition = false
            val isCmd = lexeme.uppercase() == "CMD"
            canStartJsonArray = isCmd
            canStartJsonSuppressesExpansion = isCmd
            inJsonArray = false
            jsonSuppressesExpansion = false
        } else if (!inFlagValue) {
            canStartJsonArray = false
            canStartJsonSuppressesExpansion = false
            inFlagPosition = false
            inJsonArray = false
            jsonSuppressesExpansion = false
        }
    }

    // One-shot O(logical line) lookahead after '[' to distinguish genuine JSON string arrays
    // (e.g. `RUN ["echo", "a\"b"]`) from POSIX `[ "$X" = "y" ]` or malformed shell-fallback lines.
    private fun isValidJsonStringArrayAhead(): Boolean {
        var offset = skipLogicalWsAndContinuations(1)
        if (_input.LA(offset) == ']'.code) {
            offset = skipLogicalWsAndContinuations(offset + 1)
            val tail = _input.LA(offset)
            return tail == IntStream.EOF || tail == '\r'.code || tail == '\n'.code
        }
        while (true) {
            if (_input.LA(offset) != '"'.code) return false
            offset = skipJsonStringAhead(offset)
            if (offset < 0) return false
            offset = skipLogicalWsAndContinuations(offset)
            when (_input.LA(offset)) {
                ','.code -> offset = skipLogicalWsAndContinuations(offset + 1)
                ']'.code -> {
                    offset = skipLogicalWsAndContinuations(offset + 1)
                    val tail = _input.LA(offset)
                    return tail == IntStream.EOF || tail == '\r'.code || tail == '\n'.code
                }
                else -> return false
            }
        }
    }

    private fun skipJsonStringAhead(quoteOffset: Int): Int {
        var offset = quoteOffset + 1
        while (true) {
            val afterCont = skipBuildKitContinuations(offset)
            if (afterCont > offset) {
                offset = afterCont
                continue
            }
            val ch = _input.LA(offset)
            if (ch == IntStream.EOF || ch in 0..0x1F) return -1
            if (ch == '"'.code) return offset + 1
            if (ch == '\\'.code) {
                offset++
                if (escapeChar == '`') {
                    val afterEscCont = skipBuildKitContinuations(offset)
                    if (afterEscCont > offset) {
                        offset = afterEscCont
                    }
                }
                val escaped = _input.LA(offset)
                val validEsc = when (escaped) {
                    '"'.code, '\\'.code, '/'.code, 'b'.code, 'f'.code, 'n'.code, 'r'.code, 't'.code -> true
                    'u'.code -> {
                        var hexPos = offset + 1
                        var ok = true
                        repeat(4) {
                            val afterHexCont = skipBuildKitContinuations(hexPos)
                            if (afterHexCont > hexPos) hexPos = afterHexCont
                            if (!isHexDigit(_input.LA(hexPos))) {
                                ok = false
                            } else {
                                hexPos++
                            }
                        }
                        if (!ok) return -1
                        offset = hexPos
                        continue
                    }
                    else -> false
                }
                if (!validEsc) return -1
                offset++
            } else {
                offset++
            }
        }
    }

    private fun isHexDigit(cp: Int): Boolean =
        (cp in '0'.code..'9'.code) || (cp in 'a'.code..'f'.code) || (cp in 'A'.code..'F'.code)

    private fun skipLogicalWsAndContinuations(startOffset: Int): Int {
        var offset = startOffset
        while (true) {
            val ch = _input.LA(offset)
            if (ch == ' '.code || ch == '\t'.code) {
                offset++
                continue
            }
            val afterCont = skipBuildKitContinuations(offset)
            if (afterCont > offset) {
                offset = afterCont
                continue
            }
            return offset
        }
    }

    // Skips one or more active BuildKit line continuations plus any following blank or '#'-comment lines.
    private fun skipBuildKitContinuations(startOffset: Int): Int {
        var offset = startOffset
        while (true) {
            val afterCont = matchLineContinuationEnd(offset)
            if (afterCont <= 0) return offset
            offset = afterCont
            while (true) {
                var linePos = offset
                while (_input.LA(linePos) == ' '.code || _input.LA(linePos) == '\t'.code) {
                    linePos++
                }
                val ch = _input.LA(linePos)
                if (ch == '#'.code) {
                    linePos++
                    while (true) {
                        val c = _input.LA(linePos)
                        if (c == IntStream.EOF || c == '\r'.code || c == '\n'.code) break
                        linePos++
                    }
                    val eol = _input.LA(linePos)
                    if (eol == '\n'.code) {
                        offset = linePos + 1
                    } else if (eol == '\r'.code) {
                        offset = linePos + if (_input.LA(linePos + 1) == '\n'.code) 2 else 1
                    } else {
                        return linePos
                    }
                } else if (ch == '\n'.code) {
                    offset = linePos + 1
                } else if (ch == '\r'.code) {
                    offset = linePos + if (_input.LA(linePos + 1) == '\n'.code) 2 else 1
                } else {
                    break
                }
            }
        }
    }

    // Returns the offset immediately after `<escapeChar>[ \t]*(\r?\n|\r)`, or -1 if none at startOffset.
    private fun matchLineContinuationEnd(startOffset: Int): Int {
        if (_input.LA(startOffset) != escapeChar.code) return -1
        var offset = startOffset + 1
        while (_input.LA(offset) == ' '.code || _input.LA(offset) == '\t'.code) {
            offset++
        }
        val eol = _input.LA(offset)
        return when {
            eol == '\n'.code -> offset + 1
            eol == '\r'.code -> offset + if (_input.LA(offset + 1) == '\n'.code) 2 else 1
            else -> -1
        }
    }

    protected fun onComment(lexeme: String) {
        if (directivesActive && atLineStart) {
            if (!tryParseDirective(lexeme)) {
                directivesActive = false
            }
        }
        atLineStart = false
        if (continuationPending) {
            commentLineInContinuation = true
        }
    }

    private fun tryParseDirective(commentText: String): Boolean {
        if (!commentText.startsWith("#")) return false
        var i = 1
        while (i < commentText.length && (commentText[i] == ' ' || commentText[i] == '\t')) {
            i++
        }
        val eqIdx = commentText.indexOf('=', i)
        if (eqIdx <= i) return false
        val key = commentText.substring(i, eqIdx).trim().lowercase()
        if (key != "syntax" && key != "escape" && key != "check") return false
        val value = commentText.substring(eqIdx + 1).trim()
        if (key == "escape") {
            if (value == "\\") {
                escapeChar = '\\'
            } else if (value == "`") {
                escapeChar = '`'
            }
        }
        return true
    }

    protected fun onLineContinuation() {
        directivesActive = false
        atLineStart = true
        atWordBoundary = true
        inFlagValue = false
        continuationPending = true
        commentLineInContinuation = false
    }

    protected fun onNewline(heredocLiteralMode: Int, heredocExpandMode: Int) {
        if (atLineStart && !continuationPending) {
            directivesActive = false
        }
        val blankLineInContinuation = continuationPending && atLineStart
        atLineStart = true
        atWordBoundary = true
        inFlagValue = false
        if (commentLineInContinuation || blankLineInContinuation) {
            commentLineInContinuation = false
            return
        }
        continuationPending = false
        atInstructionStart = true
        canAcceptHeredoc = false
        canStartJsonArray = false
        canStartJsonSuppressesExpansion = false
        inHealthcheckHeader = false
        inFlagPosition = false
        inJsonArray = false
        jsonSuppressesExpansion = false
        inJsonString = false
        if (pendingHeredocs.isNotEmpty()) {
            val nextSpec = pendingHeredocs.removeFirst()
            heredocStack.add(HeredocFrame(spec = nextSpec))
            val targetMode = if (nextSpec.isQuoted || isClosingDelimiterAhead(nextSpec)) {
                heredocLiteralMode
            } else {
                heredocExpandMode
            }
            pushMode(targetMode)
        }
    }

    protected fun pushDQuote(dquoteMode: Int) {
        if (_mode == DEFAULT_MODE) {
            directivesActive = false
            atLineStart = false
            atInstructionStart = false
            atWordBoundary = false
            if (!inFlagValue) {
                canStartJsonArray = false
                canStartJsonSuppressesExpansion = false
                inFlagPosition = false
                inHealthcheckHeader = false
            }
            inJsonString = inJsonArray && !inFlagValue
        }
        dqAtLineStart = false
        dqAfterComment = false
        pushMode(dquoteMode)
    }

    protected fun onDQuoteLineCont() {
        dqAtLineStart = true
        dqAfterComment = false
    }

    protected fun onDQuoteComment() {
        dqAtLineStart = false
        dqAfterComment = true
    }

    protected fun onDQuoteAfterCommentNewline() {
        dqAfterComment = false
        dqAtLineStart = true
    }

    protected fun onDQuoteContinuationBlankLine() {
        dqAfterComment = false
        dqAtLineStart = true
    }

    protected fun onDQuoteNonComment() {
        dqAtLineStart = false
        dqAfterComment = false
    }

    protected fun onDQuoteClose() {
        if (_modeStack.size() == 1) {
            inJsonString = false
        }
        dqAtLineStart = false
        dqAfterComment = false
        if (!_modeStack.isEmpty) {
            popMode()
        }
    }

    protected fun onDQuoteNewline(heredocLiteralMode: Int, heredocExpandMode: Int) {
        unwindUnclosedInlineExpansion(heredocLiteralMode, heredocExpandMode)
    }

    protected fun consumeSingleString() {
        val allowLineContinuations = !isInsideHeredocBody()
        var atContLineStart = false
        while (true) {
            val ch = _input.LA(1)
            if (ch == IntStream.EOF) break
            if (ch == '\r'.code || ch == '\n'.code) {
                if (!atContLineStart) break
                if (ch == '\r'.code && _input.LA(2) == '\n'.code) {
                    _input.consume()
                }
                _input.consume()
                atContLineStart = true
                continue
            }
            if (atContLineStart && (ch == ' '.code || ch == '\t'.code)) {
                _input.consume()
                continue
            }
            if (ch == '\''.code) {
                _input.consume()
                break
            }
            if (atContLineStart && ch == '#'.code) {
                while (true) {
                    val c = _input.LA(1)
                    if (c == IntStream.EOF || c == '\r'.code || c == '\n'.code) break
                    _input.consume()
                }
                atContLineStart = true
                continue
            }
            atContLineStart = false
            if (allowLineContinuations && ch == escapeChar.code) {
                var offset = 2
                while (_input.LA(offset) == ' '.code || _input.LA(offset) == '\t'.code) {
                    offset++
                }
                val eol = _input.LA(offset)
                if (eol == '\n'.code) {
                    repeat(offset) { _input.consume() }
                    atContLineStart = true
                    continue
                }
                if (eol == '\r'.code) {
                    val hasLf = _input.LA(offset + 1) == '\n'.code
                    repeat(offset + if (hasLf) 1 else 0) { _input.consume() }
                    atContLineStart = true
                    continue
                }
            }
            _input.consume()
        }
    }

    protected fun onPeLineCont() {
        peAtLineStart = true
        peAfterComment = false
    }

    protected fun onPeComment() {
        peAtLineStart = false
        peAfterComment = true
    }

    protected fun onPeContNewline() {
        peAtLineStart = true
        peAfterComment = false
    }

    protected fun onPeNonComment() {
        peAtLineStart = false
        peAfterComment = false
    }

    protected fun enqueueHeredoc(lexeme: String) {
        onNonWsToken()
        var idx = 2 // skip "<<"
        var stripTabs = false
        if (idx < lexeme.length && lexeme[idx] == '-') {
            stripTabs = true
            idx++
        }
        val rawDelim = lexeme.substring(idx)
        val isQuoted = rawDelim.length >= 2 &&
            ((rawDelim.startsWith("'") && rawDelim.endsWith("'")) ||
                (rawDelim.startsWith("\"") && rawDelim.endsWith("\"")))
        val delimiter = if (isQuoted) rawDelim.substring(1, rawDelim.length - 1) else rawDelim
        pendingHeredocs.addLast(
            HeredocSpec(
                delimiter = delimiter,
                delimiterCodePoints = toCodePoints(delimiter),
                stripTabs = stripTabs,
                isQuoted = isQuoted,
            ),
        )
    }

    protected fun pushParamExp(paramExpMode: Int) {
        if (_mode == DEFAULT_MODE) {
            onNonWsToken()
        }
        peAtLineStart = false
        peAfterComment = false
        paramExpDepthStack.add(paramExpBraceDepth)
        paramExpBraceDepth = 0
        pushMode(paramExpMode)
    }

    protected fun onParamExpLBrace() {
        paramExpBraceDepth++
    }

    protected fun onParamExpRBrace(closeTokenType: Int) {
        if (paramExpBraceDepth > 0) {
            paramExpBraceDepth--
        } else {
            paramExpBraceDepth = if (paramExpDepthStack.isNotEmpty()) {
                paramExpDepthStack.removeAt(paramExpDepthStack.lastIndex)
            } else {
                0
            }
            if (!_modeStack.isEmpty) {
                popMode()
            }
            type = closeTokenType
        }
    }

    protected fun onParamExpUnclosedNewline(heredocLiteralMode: Int, heredocExpandMode: Int) {
        unwindUnclosedInlineExpansion(heredocLiteralMode, heredocExpandMode)
    }

    private fun unwindUnclosedInlineExpansion(heredocLiteralMode: Int, heredocExpandMode: Int) {
        // Unwind nested PARAM_EXP / DQUOTE modes back to HEREDOC_EXPAND or DEFAULT_MODE.
        while (!_modeStack.isEmpty && _mode != heredocExpandMode && _mode != DEFAULT_MODE) {
            popMode()
        }
        paramExpBraceDepth = 0
        paramExpDepthStack.clear()
        inJsonString = false
        dqAtLineStart = false
        dqAfterComment = false
        peAtLineStart = false
        peAfterComment = false
        if (_mode == heredocExpandMode) {
            onHeredocNewline(heredocLiteralMode, heredocExpandMode)
        } else {
            directivesActive = false
            atLineStart = true
            atWordBoundary = true
            atInstructionStart = true
            canAcceptHeredoc = false
            canStartJsonArray = false
            canStartJsonSuppressesExpansion = false
            inHealthcheckHeader = false
            inFlagPosition = false
            inFlagValue = false
            inJsonArray = false
            jsonSuppressesExpansion = false
            continuationPending = false
            commentLineInContinuation = false
            if (pendingHeredocs.isNotEmpty()) {
                val nextSpec = pendingHeredocs.removeFirst()
                heredocStack.add(HeredocFrame(spec = nextSpec))
                val targetMode = if (nextSpec.isQuoted || isClosingDelimiterAhead(nextSpec)) {
                    heredocLiteralMode
                } else {
                    heredocExpandMode
                }
                pushMode(targetMode)
            }
        }
    }

    protected fun consumeHeredocLiteralLine(
        delimEndTokenType: Int,
        heredocLiteralMode: Int,
        heredocExpandMode: Int,
    ) {
        val frame = heredocStack.lastOrNull()
        if (frame != null && frame.atLineStart && matchesClosingDelimiterFromTokenStart(frame.spec)) {
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

    protected fun markHeredocTextBody(lexeme: String) {
        val frame = heredocStack.lastOrNull() ?: return
        if (frame.atLineStart && frame.spec.stripTabs && lexeme.all { it == '\t' }) {
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
            if (!frame.spec.isQuoted && isClosingDelimiterAhead(frame.spec) && _mode != heredocLiteralMode) {
                mode(heredocLiteralMode)
            }
        }
    }

    private fun advanceOrExitHeredoc(heredocLiteralMode: Int, heredocExpandMode: Int) {
        if (heredocStack.isNotEmpty()) {
            heredocStack.removeAt(heredocStack.lastIndex)
        }
        if (pendingHeredocs.isNotEmpty()) {
            val nextSpec = pendingHeredocs.removeFirst()
            heredocStack.add(HeredocFrame(spec = nextSpec))
            val targetMode = if (nextSpec.isQuoted || isClosingDelimiterAhead(nextSpec)) {
                heredocLiteralMode
            } else {
                heredocExpandMode
            }
            mode(targetMode)
        } else if (!_modeStack.isEmpty) {
            popMode()
            if (_mode == DEFAULT_MODE) {
                atLineStart = true
                atWordBoundary = true
                atInstructionStart = true
                canAcceptHeredoc = false
                continuationPending = false
                commentLineInContinuation = false
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
        if (delimCps.isEmpty()) {
            val ch = charAtOffsetFromTokenStart(offset)
            return ch == IntStream.EOF || ch == '\r'.code || ch == '\n'.code
        }
        for (cpIdx in delimCps.indices) {
            if (charAtOffsetFromTokenStart(offset + cpIdx) != delimCps[cpIdx]) {
                return false
            }
        }
        val after = charAtOffsetFromTokenStart(offset + delimCps.size)
        return after == IntStream.EOF || after == '\r'.code || after == '\n'.code
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
