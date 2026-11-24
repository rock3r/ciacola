package dev.sebastiano.ciacola.syntax.antlr.ruby

import org.antlr.v4.kotlinruntime.CharStream
import org.antlr.v4.kotlinruntime.IntStream
import org.antlr.v4.kotlinruntime.Lexer

/**
 * Pure Kotlin Multiplatform base lexer for [RubyLexer], handling:
 * - Column-0 `=begin ... =end` embedded documentation and `__END__` data sections.
 * - Ruby expression-state tracking (`BEG`, `END`, `CMDARG`, `CLASS`, `DEF`, `DOT`) with
 *   local-variable scoping and space-sensitivity for disambiguating `/regex/` vs `/`, `%w[]` vs `%`,
 *   `<<EOF` vs `<<`, `?a` vs `?`, `:sym` vs `:`, and compact ternary `? a:b` vs `HASH_KEY`.
 * - Paired and arbitrary-delimiter `%` literals with nested delimiter counting.
 * - Multi-line `/regex/[imxouesn]*` literals with `[...]` character-class tracking, `/x` comment
 *   lines (memoized in O(N) time), and `#{...}` interpolation.
 * - FIFO-queued `<<`, `<<-`, and `<<~` heredocs scoped by interpolation depth (bare, double-quoted,
 *   single-quoted, backtick) with backslash newline continuations and `#{...}` interpolation.
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

    private enum class EndScopeKind {
        PLAIN,
        ISOLATED,
        BLOCK,
    }

    private class LocalScope(
        val isIsolated: Boolean,
        val vars: HashSet<String> = HashSet(),
    )

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
        val openSlashPos: Int,
        var inCharClass: Boolean = false,
        var hasExtendedFlag: Boolean? = null,
    )

    private class HeredocSpec(
        val delimiter: String,
        val delimiterCodePoints: IntArray,
        val allowIndent: Boolean,
        val isQuoted: Boolean,
        val interpDepth: Int,
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
    private val regexExtendedCache = HashMap<Int, Boolean>()
    private val braceCloseMap = HashMap<Int, Int>()
    private val pendingHeredocs = ArrayList<HeredocSpec>()
    private val heredocStack = ArrayList<HeredocFrame>()

    private val localScopeStack = ArrayList<LocalScope>().apply {
        add(LocalScope(isIsolated = true))
    }
    private val endScopeStack = ArrayList<EndScopeKind>()
    private val braceHasBlockScope = ArrayList<Boolean>()
    private val lhsCandidates = ArrayList<String>()
    private var expectingLhsAfterComma = false
    private var expectingForVar = false
    private var inRescueHeader = false
    private var expectingRescueVar = false
    private var inDefHeader = false
    private var inDefNameBrackets = false
    private var collectingDefParams = false
    private var defParamParenDepth = 0
    private var inDefParamDefault = false
    private var awaitingEndlessDefEq = false
    private var endlessDefBaseDelimDepth = -1
    private var expectingLambdaParams = false
    private var collectingLambdaParams = false
    private var lambdaParamParenDepth = 0
    private var pendingBlockParamStart = false
    private var inPipeBlockParams = false

    private var ternaryDepth = 0
    private val savedDelimiterTernaryDepths = ArrayList<Int>()
    private var delimiterDepth = 0

    override fun reset() {
        super.reset()
        exprState = ExprState.BEG
        sawSpaceSinceLastToken = true
        interpBraceDepths.clear()
        percentStack.clear()
        dquoteStack.clear()
        regexStack.clear()
        regexExtendedCache.clear()
        braceCloseMap.clear()
        pendingHeredocs.clear()
        heredocStack.clear()
        localScopeStack.clear()
        localScopeStack.add(LocalScope(isIsolated = true))
        endScopeStack.clear()
        braceHasBlockScope.clear()
        lhsCandidates.clear()
        expectingLhsAfterComma = false
        expectingForVar = false
        inRescueHeader = false
        expectingRescueVar = false
        inDefHeader = false
        inDefNameBrackets = false
        collectingDefParams = false
        defParamParenDepth = 0
        inDefParamDefault = false
        awaitingEndlessDefEq = false
        endlessDefBaseDelimDepth = -1
        expectingLambdaParams = false
        collectingLambdaParams = false
        lambdaParamParenDepth = 0
        pendingBlockParamStart = false
        inPipeBlockParams = false
        ternaryDepth = 0
        savedDelimiterTernaryDepths.clear()
        delimiterDepth = 0
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

    protected fun isCharLiteralStart(): Boolean {
        if (!isArgLiteralStart()) return false
        val c1 = _input.LA(1)
        if (c1 == IntStream.EOF || isRubySpaceOrEol(c1)) return false
        if (c1 != '\\'.code && isRubyIdentCont(c1) && isRubyIdentCont(_input.LA(2))) {
            return false
        }
        return true
    }

    protected fun isSymbolStart(): Boolean = isArgLiteralStart()

    protected fun isHashKeyAllowed(): Boolean = ternaryDepth == 0

    protected fun isDefMethodNameContext(): Boolean =
        exprState == ExprState.DEF || (exprState == ExprState.DOT && inDefHeader)

    // ---------------- State updates in DEFAULT_MODE ----------------

    protected fun onWhitespace() {
        sawSpaceSinceLastToken = true
    }

    protected fun onNewline(heredocLiteralMode: Int, heredocExpandMode: Int) {
        sawSpaceSinceLastToken = true
        lhsCandidates.clear()
        expectingLhsAfterComma = false
        inRescueHeader = false
        expectingRescueVar = false
        expectingLambdaParams = false
        pendingBlockParamStart = false

        if (collectingDefParams && defParamParenDepth == 0) {
            collectingDefParams = false
            inDefParamDefault = false
        }
        if (inDefHeader) {
            // Line break ends header if method name was missing; keep DEF only right after `def`.
            if (exprState != ExprState.DEF && exprState != ExprState.DOT) {
                inDefHeader = false
            }
        }
        if (awaitingEndlessDefEq) {
            awaitingEndlessDefEq = false
        }
        if (endlessDefBaseDelimDepth >= 0 && delimiterDepth <= endlessDefBaseDelimDepth && exprState != ExprState.BEG) {
            popEndlessDefScope()
        }

        if (exprState != ExprState.DOT && exprState != ExprState.DEF && exprState != ExprState.CLASS) {
            exprState = ExprState.BEG
        }

        val nextSpec = dequeuePendingHeredocAtCurrentDepth()
        if (nextSpec != null) {
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
        clearTransientHeaderStatesOnOperator()
        exprState = ExprState.BEG
        sawSpaceSinceLastToken = false
    }

    protected fun onQuestion() {
        clearTransientHeaderStatesOnOperator()
        ternaryDepth++
        exprState = ExprState.BEG
        sawSpaceSinceLastToken = false
    }

    protected fun onColon() {
        clearTransientHeaderStatesOnOperator()
        if (ternaryDepth > 0) {
            ternaryDepth--
        }
        exprState = ExprState.BEG
        sawSpaceSinceLastToken = false
    }

    protected fun onComma() {
        expectingRescueVar = false
        expectingLambdaParams = false
        pendingBlockParamStart = false
        awaitingEndlessDefEq = false
        if (collectingDefParams && defParamParenDepth <= 1) {
            inDefParamDefault = false
        }
        if (expectingLhsAfterComma) {
            // Keep lhsCandidates while parsing `a, b = ...`.
            expectingLhsAfterComma = false
        } else {
            lhsCandidates.clear()
        }
        exprState = ExprState.BEG
        sawSpaceSinceLastToken = false
    }

    protected fun onSemi() {
        lhsCandidates.clear()
        expectingLhsAfterComma = false
        expectingForVar = false
        inRescueHeader = false
        expectingRescueVar = false
        inDefHeader = false
        inDefNameBrackets = false
        collectingDefParams = false
        defParamParenDepth = 0
        inDefParamDefault = false
        awaitingEndlessDefEq = false
        if (endlessDefBaseDelimDepth >= 0 && delimiterDepth <= endlessDefBaseDelimDepth) {
            popEndlessDefScope()
        }
        expectingLambdaParams = false
        collectingLambdaParams = false
        lambdaParamParenDepth = 0
        pendingBlockParamStart = false
        inPipeBlockParams = false
        ternaryDepth = 0
        exprState = ExprState.BEG
        sawSpaceSinceLastToken = false
    }

    protected fun onAssign() {
        lhsCandidates.clear()
        expectingLhsAfterComma = false
        expectingForVar = false
        inRescueHeader = false
        expectingRescueVar = false
        expectingLambdaParams = false
        pendingBlockParamStart = false

        if (inDefNameBrackets) {
            inDefNameBrackets = false
            inDefHeader = false
            collectingDefParams = true
            defParamParenDepth = 0
            inDefParamDefault = false
            awaitingEndlessDefEq = false
            exprState = ExprState.END
            sawSpaceSinceLastToken = false
            return
        }

        if (awaitingEndlessDefEq) {
            awaitingEndlessDefEq = false
            endlessDefBaseDelimDepth = delimiterDepth
            exprState = ExprState.BEG
            sawSpaceSinceLastToken = false
            return
        }

        if (collectingDefParams) {
            inDefParamDefault = true
        }
        exprState = ExprState.BEG
        sawSpaceSinceLastToken = false
    }

    protected fun onHashRocket() {
        lhsCandidates.clear()
        expectingLhsAfterComma = false
        expectingLambdaParams = false
        pendingBlockParamStart = false
        awaitingEndlessDefEq = false
        if (inRescueHeader) {
            expectingRescueVar = true
        }
        exprState = ExprState.BEG
        sawSpaceSinceLastToken = false
    }

    protected fun onStabbyLambda() {
        clearTransientHeaderStatesOnOperator()
        expectingLambdaParams = true
        exprState = ExprState.BEG
        sawSpaceSinceLastToken = false
    }

    protected fun onPipe() {
        clearTransientHeaderStatesOnOperator()
        if (pendingBlockParamStart) {
            pendingBlockParamStart = false
            inPipeBlockParams = true
            pushBlockLocalScope()
        } else if (inPipeBlockParams) {
            inPipeBlockParams = false
        }
        exprState = ExprState.BEG
        sawSpaceSinceLastToken = false
    }

    protected fun onLParen() {
        lhsCandidates.clear()
        expectingLhsAfterComma = false
        expectingRescueVar = false
        pendingBlockParamStart = false
        inDefNameBrackets = false
        savedDelimiterTernaryDepths.add(ternaryDepth)
        ternaryDepth = 0
        delimiterDepth++

        if (expectingLambdaParams) {
            expectingLambdaParams = false
            collectingLambdaParams = true
            lambdaParamParenDepth = 1
        } else if (collectingLambdaParams) {
            lambdaParamParenDepth++
        } else if (collectingDefParams) {
            defParamParenDepth++
            awaitingEndlessDefEq = false
        } else if (inDefHeader) {
            inDefHeader = false
            collectingDefParams = true
            defParamParenDepth = 1
            inDefParamDefault = false
            awaitingEndlessDefEq = false
        }

        exprState = ExprState.BEG
        sawSpaceSinceLastToken = false
    }

    protected fun onRParen() {
        lhsCandidates.clear()
        expectingLhsAfterComma = false
        expectingRescueVar = false
        pendingBlockParamStart = false
        if (delimiterDepth > 0) delimiterDepth--
        ternaryDepth = if (savedDelimiterTernaryDepths.isNotEmpty()) {
            savedDelimiterTernaryDepths.removeAt(savedDelimiterTernaryDepths.lastIndex)
        } else {
            0
        }

        if (collectingLambdaParams) {
            lambdaParamParenDepth--
            if (lambdaParamParenDepth <= 0) {
                collectingLambdaParams = false
                lambdaParamParenDepth = 0
            }
        } else if (collectingDefParams && defParamParenDepth > 0) {
            defParamParenDepth--
            if (defParamParenDepth == 0) {
                collectingDefParams = false
                inDefParamDefault = false
                awaitingEndlessDefEq = true
            }
        }

        exprState = ExprState.END
        sawSpaceSinceLastToken = false
    }

    protected fun onLBrack() {
        lhsCandidates.clear()
        expectingLhsAfterComma = false
        expectingRescueVar = false
        expectingLambdaParams = false
        pendingBlockParamStart = false
        savedDelimiterTernaryDepths.add(ternaryDepth)
        ternaryDepth = 0
        delimiterDepth++

        if (isDefMethodNameContext()) {
            inDefNameBrackets = true
            inDefHeader = false
        }
        exprState = ExprState.BEG
        sawSpaceSinceLastToken = false
    }

    protected fun onRBrack() {
        lhsCandidates.clear()
        expectingLhsAfterComma = false
        expectingRescueVar = false
        expectingLambdaParams = false
        pendingBlockParamStart = false
        if (delimiterDepth > 0) delimiterDepth--
        ternaryDepth = if (savedDelimiterTernaryDepths.isNotEmpty()) {
            savedDelimiterTernaryDepths.removeAt(savedDelimiterTernaryDepths.lastIndex)
        } else {
            0
        }
        if (inDefNameBrackets) {
            // `def []` or `def []=` — wait one token for optional `=`.
            inDefNameBrackets = _input.LA(1) == '='.code
            if (!inDefNameBrackets) {
                collectingDefParams = true
                defParamParenDepth = 0
                inDefParamDefault = false
                awaitingEndlessDefEq = true
            }
        }
        exprState = ExprState.END
        sawSpaceSinceLastToken = false
    }

    protected fun onHashKey(lexeme: String) {
        lhsCandidates.clear()
        expectingLhsAfterComma = false
        expectingRescueVar = false
        expectingLambdaParams = false
        pendingBlockParamStart = false
        awaitingEndlessDefEq = false

        val name = if (lexeme.length > 1) lexeme.substring(0, lexeme.length - 1) else ""
        if (collectingDefParams && defParamParenDepth <= 1 && !inDefParamDefault) {
            bindLocalVar(name)
            inDefParamDefault = true
        } else if (collectingLambdaParams && lambdaParamParenDepth == 1) {
            bindLocalVar(name)
        }

        exprState = ExprState.BEG
        sawSpaceSinceLastToken = false
    }

    protected fun onEndToken() {
        clearTransientHeaderStatesOnValue()
        exprState = ExprState.END
        sawSpaceSinceLastToken = false
    }

    protected fun onDefOperatorToken() {
        if (isDefMethodNameContext()) {
            inDefHeader = false
            collectingDefParams = true
            defParamParenDepth = 0
            inDefParamDefault = false
            awaitingEndlessDefEq = true
            exprState = ExprState.END
        } else {
            clearTransientHeaderStatesOnOperator()
            exprState = ExprState.BEG
        }
        sawSpaceSinceLastToken = false
    }

    protected fun onDotToken() {
        lhsCandidates.clear()
        expectingLhsAfterComma = false
        expectingRescueVar = false
        expectingLambdaParams = false
        pendingBlockParamStart = false
        awaitingEndlessDefEq = false
        exprState = ExprState.DOT
        sawSpaceSinceLastToken = false
    }

    protected fun onIdentifierOrKeyword(lexeme: String) {
        val prevState = exprState
        val wasInDefHeader = inDefHeader
        val wasPendingBlockParamStart = pendingBlockParamStart
        pendingBlockParamStart = false
        expectingLambdaParams = false

        if (prevState == ExprState.DOT) {
            if (wasInDefHeader) {
                inDefHeader = isDotOrScopeAhead()
                if (!inDefHeader) {
                    collectingDefParams = true
                    defParamParenDepth = 0
                    inDefParamDefault = false
                    awaitingEndlessDefEq = true
                }
            }
            exprState = ExprState.END
            sawSpaceSinceLastToken = false
            return
        }

        if (prevState == ExprState.DEF) {
            if (isDotOrScopeAhead()) {
                inDefHeader = true
            } else {
                inDefHeader = false
                collectingDefParams = true
                defParamParenDepth = 0
                inDefParamDefault = false
                awaitingEndlessDefEq = true
            }
            exprState = ExprState.END
            sawSpaceSinceLastToken = false
            return
        }

        inDefHeader = false

        if (prevState == ExprState.CLASS) {
            // `class Foo` / `module Bar` — scope was pushed on `class`/`module`.
            exprState = ExprState.END
            sawSpaceSinceLastToken = false
            return
        }

        when (lexeme) {
            "def" -> {
                lhsCandidates.clear()
                expectingLhsAfterComma = false
                inDefHeader = true
                collectingDefParams = false
                defParamParenDepth = 0
                inDefParamDefault = false
                awaitingEndlessDefEq = false
                ternaryDepth = 0
                pushEndScope(EndScopeKind.ISOLATED)
                exprState = ExprState.DEF
                sawSpaceSinceLastToken = false
                return
            }

            "class", "module" -> {
                lhsCandidates.clear()
                expectingLhsAfterComma = false
                ternaryDepth = 0
                pushEndScope(EndScopeKind.ISOLATED)
                exprState = ExprState.CLASS
                sawSpaceSinceLastToken = false
                return
            }

            "do" -> {
                lhsCandidates.clear()
                expectingLhsAfterComma = false
                pushEndScope(EndScopeKind.PLAIN)
                pendingBlockParamStart = true
                exprState = ExprState.BEG
                sawSpaceSinceLastToken = false
                return
            }

            "begin", "case" -> {
                lhsCandidates.clear()
                expectingLhsAfterComma = false
                pushEndScope(EndScopeKind.PLAIN)
                exprState = ExprState.BEG
                sawSpaceSinceLastToken = false
                return
            }

            "for" -> {
                lhsCandidates.clear()
                expectingLhsAfterComma = false
                expectingForVar = true
                pushEndScope(EndScopeKind.PLAIN)
                exprState = ExprState.BEG
                sawSpaceSinceLastToken = false
                return
            }

            "if", "unless", "while", "until" -> {
                lhsCandidates.clear()
                expectingLhsAfterComma = false
                if (prevState == ExprState.BEG) {
                    pushEndScope(EndScopeKind.PLAIN)
                }
                exprState = ExprState.BEG
                sawSpaceSinceLastToken = false
                return
            }

            "rescue" -> {
                lhsCandidates.clear()
                expectingLhsAfterComma = false
                inRescueHeader = true
                expectingRescueVar = false
                exprState = ExprState.BEG
                sawSpaceSinceLastToken = false
                return
            }

            "in" -> {
                lhsCandidates.clear()
                expectingLhsAfterComma = false
                expectingForVar = false
                exprState = ExprState.BEG
                sawSpaceSinceLastToken = false
                return
            }

            "elsif", "else", "ensure", "when", "then", "return", "yield",
            "break", "next", "and", "or", "not", "alias", "undef", "defined?",
            -> {
                lhsCandidates.clear()
                expectingLhsAfterComma = false
                exprState = ExprState.BEG
                sawSpaceSinceLastToken = false
                return
            }

            "end" -> {
                lhsCandidates.clear()
                expectingLhsAfterComma = false
                inRescueHeader = false
                expectingRescueVar = false
                popEndScope()
                exprState = ExprState.END
                sawSpaceSinceLastToken = false
                return
            }

            "self", "true", "false", "nil", "__FILE__", "__LINE__", "__ENCODING__",
            "redo", "retry", "BEGIN", "END",
            -> {
                lhsCandidates.clear()
                expectingLhsAfterComma = false
                exprState = ExprState.END
                sawSpaceSinceLastToken = false
                return
            }
        }

        // Plain identifier or constant.
        awaitingEndlessDefEq = false
        val isLocalCandidate = isBindableLocalName(lexeme)

        if (expectingForVar && isLocalCandidate) {
            bindLocalVar(lexeme)
        } else if (expectingRescueVar && isLocalCandidate) {
            bindLocalVar(lexeme)
            expectingRescueVar = false
            inRescueHeader = false
        } else if (collectingDefParams && defParamParenDepth <= 1 && !inDefParamDefault && isLocalCandidate) {
            bindLocalVar(lexeme)
        } else if (collectingLambdaParams && lambdaParamParenDepth == 1 && isLocalCandidate) {
            bindLocalVar(lexeme)
        } else if (inPipeBlockParams && isLocalCandidate) {
            bindLocalVar(lexeme)
        } else if (isLocalCandidate) {
            when {
                isSimpleAssignAhead() -> {
                    for (cand in lhsCandidates) {
                        bindLocalVar(cand)
                    }
                    lhsCandidates.clear()
                    expectingLhsAfterComma = false
                    bindLocalVar(lexeme)
                }
                (prevState == ExprState.BEG || expectingLhsAfterComma) && isCommaAhead() -> {
                    lhsCandidates.add(lexeme)
                    expectingLhsAfterComma = true
                }
                else -> {
                    lhsCandidates.clear()
                    expectingLhsAfterComma = false
                }
            }
        } else {
            lhsCandidates.clear()
            expectingLhsAfterComma = false
        }

        if (wasPendingBlockParamStart) {
            pendingBlockParamStart = false
        }

        exprState = if (isLocalCandidate && isLocalVar(lexeme)) {
            ExprState.END
        } else {
            ExprState.CMDARG
        }
        sawSpaceSinceLastToken = false
    }

    private fun clearTransientHeaderStatesOnOperator() {
        lhsCandidates.clear()
        expectingLhsAfterComma = false
        expectingRescueVar = false
        inDefHeader = false
        expectingLambdaParams = false
        pendingBlockParamStart = false
        awaitingEndlessDefEq = false
    }

    private fun clearTransientHeaderStatesOnValue() {
        lhsCandidates.clear()
        expectingLhsAfterComma = false
        expectingRescueVar = false
        inDefHeader = false
        expectingLambdaParams = false
        pendingBlockParamStart = false
        awaitingEndlessDefEq = false
    }

    private fun pushEndScope(kind: EndScopeKind) {
        endScopeStack.add(kind)
        if (kind == EndScopeKind.ISOLATED) {
            localScopeStack.add(LocalScope(isIsolated = true))
        }
    }

    private fun pushBlockLocalScope() {
        if (braceHasBlockScope.isNotEmpty() && !braceHasBlockScope.last()) {
            braceHasBlockScope[braceHasBlockScope.lastIndex] = true
            localScopeStack.add(LocalScope(isIsolated = false))
        } else if (endScopeStack.isNotEmpty() && endScopeStack.last() == EndScopeKind.PLAIN) {
            endScopeStack[endScopeStack.lastIndex] = EndScopeKind.BLOCK
            localScopeStack.add(LocalScope(isIsolated = false))
        }
    }

    private fun popEndScope() {
        if (endScopeStack.isEmpty()) return
        val kind = endScopeStack.removeAt(endScopeStack.lastIndex)
        if ((kind == EndScopeKind.ISOLATED || kind == EndScopeKind.BLOCK) && localScopeStack.size > 1) {
            localScopeStack.removeAt(localScopeStack.lastIndex)
        }
    }

    private fun popEndlessDefScope() {
        endlessDefBaseDelimDepth = -1
        if (endScopeStack.isNotEmpty() && endScopeStack.last() == EndScopeKind.ISOLATED) {
            popEndScope()
        }
    }

    private fun bindLocalVar(name: String) {
        if (name.isEmpty() || !isBindableLocalName(name)) return
        localScopeStack.last().vars.add(name)
    }

    private fun isLocalVar(name: String): Boolean {
        for (i in localScopeStack.lastIndex downTo 0) {
            val scope = localScopeStack[i]
            if (name in scope.vars) return true
            if (scope.isIsolated) break
        }
        return false
    }

    private fun isBindableLocalName(name: String): Boolean {
        if (name.isEmpty()) return false
        val first = name[0]
        if (first in 'A'..'Z') return false
        val last = name[name.length - 1]
        if (last == '?' || last == '!') return false
        return name !in RUBY_RESERVED_WORDS
    }

    private fun isDotOrScopeAhead(): Boolean {
        val c1 = _input.LA(1)
        if (c1 == '.'.code) return true
        return c1 == ':'.code && _input.LA(2) == ':'.code
    }

    private fun isSimpleAssignAhead(): Boolean {
        var offset = 1
        while (true) {
            val ch = _input.LA(offset)
            if (ch == ' '.code || ch == '\t'.code) offset++ else break
        }
        val c1 = _input.LA(offset)
        val c2 = _input.LA(offset + 1)
        val c3 = _input.LA(offset + 2)
        if (c1 == '='.code) {
            return c2 != '='.code && c2 != '~'.code && c2 != '>'.code
        }
        if ((c1 == '+'.code || c1 == '-'.code || c1 == '^'.code) && c2 == '='.code) {
            return true
        }
        if ((c1 == '*'.code || c1 == '&'.code || c1 == '|'.code || c1 == '<'.code || c1 == '>'.code) &&
            c2 == c1 && c3 == '='.code
        ) {
            return true
        }
        if ((c1 == '*'.code || c1 == '/'.code || c1 == '%'.code || c1 == '&'.code || c1 == '|'.code) &&
            c2 == '='.code
        ) {
            return true
        }
        return false
    }

    private fun isCommaAhead(): Boolean {
        var offset = 1
        while (true) {
            val ch = _input.LA(offset)
            if (ch == ' '.code || ch == '\t'.code) offset++ else break
        }
        return _input.LA(offset) == ','.code
    }

    // ---------------- Braces & #{...} interpolation ----------------

    protected fun onLBrace() {
        lhsCandidates.clear()
        expectingLhsAfterComma = false
        expectingRescueVar = false
        expectingLambdaParams = false
        awaitingEndlessDefEq = false

        savedDelimiterTernaryDepths.add(ternaryDepth)
        ternaryDepth = 0
        delimiterDepth++

        if (interpBraceDepths.isNotEmpty()) {
            interpBraceDepths[interpBraceDepths.lastIndex]++
        }
        braceHasBlockScope.add(false)
        pendingBlockParamStart = true
        exprState = ExprState.BEG
        sawSpaceSinceLastToken = false
    }

    protected fun onRBrace(interpCloseToken: Int) {
        lhsCandidates.clear()
        expectingLhsAfterComma = false
        expectingRescueVar = false
        expectingLambdaParams = false
        pendingBlockParamStart = false
        inPipeBlockParams = false
        if (delimiterDepth > 0) delimiterDepth--
        ternaryDepth = if (savedDelimiterTernaryDepths.isNotEmpty()) {
            savedDelimiterTernaryDepths.removeAt(savedDelimiterTernaryDepths.lastIndex)
        } else {
            0
        }

        if (interpBraceDepths.isNotEmpty()) {
            val lastIdx = interpBraceDepths.lastIndex
            if (interpBraceDepths[lastIdx] > 0) {
                interpBraceDepths[lastIdx]--
                popBraceBlockScope()
                exprState = ExprState.END
                sawSpaceSinceLastToken = false
            } else {
                interpBraceDepths.removeAt(lastIdx)
                type = interpCloseToken
                if (!_modeStack.isEmpty) {
                    popMode()
                }
                sawSpaceSinceLastToken = false
            }
        } else {
            popBraceBlockScope()
            exprState = ExprState.END
            sawSpaceSinceLastToken = false
        }
    }

    private fun popBraceBlockScope() {
        if (braceHasBlockScope.isEmpty()) return
        val openedScope = braceHasBlockScope.removeAt(braceHasBlockScope.lastIndex)
        if (openedScope && localScopeStack.size > 1) {
            localScopeStack.removeAt(localScopeStack.lastIndex)
        }
    }

    protected fun enterInterp(defaultMode: Int) {
        savedDelimiterTernaryDepths.add(ternaryDepth)
        ternaryDepth = 0
        delimiterDepth++
        interpBraceDepths.add(0)
        exprState = ExprState.BEG
        sawSpaceSinceLastToken = true
        pushMode(defaultMode)
    }

    // ---------------- Double-quoted strings, symbols & backticks ----------------

    protected fun enterDQuote(dquoteMode: Int, isSymbol: Boolean) {
        clearTransientHeaderStatesOnValue()
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
        clearTransientHeaderStatesOnValue()
        regexStack.add(RegexFrame(openSlashPos = _tokenStartCharIndex))
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

    protected fun onRegexHash(textToken: Int) {
        val frame = regexStack.lastOrNull()
        type = textToken
        if (frame == null || frame.inCharClass) return
        val hasX = frame.hasExtendedFlag ?: scanRegexExtendedFlag(frame.openSlashPos).also {
            frame.hasExtendedFlag = it
        }
        if (hasX) {
            consumeRestOfLine()
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

    private fun scanRegexExtendedFlag(openSlashPos: Int): Boolean {
        regexExtendedCache[openSlashPos]?.let { return it }
        val savedPos = _input.index()
        _input.seek(openSlashPos + 1)

        var inCC = false
        var result = false

        while (_input.LA(1) != IntStream.EOF) {
            val ch = _input.LA(1)
            when (ch) {
                '\\'.code -> {
                    _input.consume()
                    val esc = _input.LA(1)
                    if (esc != IntStream.EOF) {
                        if (esc == '\r'.code && _input.LA(2) == '\n'.code) {
                            _input.consume()
                        }
                        _input.consume()
                    }
                }

                '['.code -> {
                    inCC = true
                    _input.consume()
                }

                ']'.code -> {
                    inCC = false
                    _input.consume()
                }

                '#'.code -> {
                    val afterHash = _input.LA(2)
                    if (afterHash == '{'.code) {
                        val openBracePos = _input.index() + 1
                        _input.consume() // '#'
                        val closed = skipBalancedBracesMemoized(openBracePos)
                        if (!closed) {
                            result = false
                            break
                        }
                    } else if (!inCC && afterHash != '@'.code && afterHash != '$'.code) {
                        _input.consume()
                        consumeRestOfLine()
                    } else {
                        _input.consume()
                    }
                }

                '/'.code -> {
                    _input.consume()
                    if (!inCC) {
                        while (isRegexFlag(_input.LA(1))) {
                            if (_input.LA(1) == 'x'.code) {
                                result = true
                            }
                            _input.consume()
                        }
                        break
                    }
                }

                else -> _input.consume()
            }
        }

        _input.seek(savedPos)
        regexExtendedCache[openSlashPos] = result
        return result
    }

    /**
     * Skips a balanced `{ ... }` interpolation block starting at [openBracePos], memoizing every
     * visited `{` position in [braceCloseMap] so nested regex scans stay strictly O(N).
     */
    private fun skipBalancedBracesMemoized(openBracePos: Int): Boolean {
        braceCloseMap[openBracePos]?.let { cachedClose ->
            if (cachedClose < 0) {
                while (_input.LA(1) != IntStream.EOF) _input.consume()
                return false
            }
            _input.seek(cachedClose + 1)
            return true
        }

        val braceStack = ArrayList<Int>()
        // Consume initial '{'.
        braceStack.add(_input.index())
        _input.consume()

        while (braceStack.isNotEmpty() && _input.LA(1) != IntStream.EOF) {
            when (_input.LA(1)) {
                '{'.code -> {
                    val pos = _input.index()
                    val cached = braceCloseMap[pos]
                    if (cached != null) {
                        if (cached < 0) {
                            while (_input.LA(1) != IntStream.EOF) _input.consume()
                            break
                        }
                        _input.seek(cached + 1)
                    } else {
                        braceStack.add(pos)
                        _input.consume()
                    }
                }

                '}'.code -> {
                    val matchedOpen = braceStack.removeAt(braceStack.lastIndex)
                    braceCloseMap[matchedOpen] = _input.index()
                    _input.consume()
                }

                '\''.code -> {
                    _input.consume()
                    while (_input.LA(1) != IntStream.EOF) {
                        val c = _input.LA(1)
                        _input.consume()
                        if (c == '\\'.code && _input.LA(1) != IntStream.EOF) {
                            _input.consume()
                        } else if (c == '\''.code) {
                            break
                        }
                    }
                }

                '"'.code -> {
                    _input.consume()
                    while (_input.LA(1) != IntStream.EOF) {
                        val c = _input.LA(1)
                        if (c == '\\'.code) {
                            _input.consume()
                            if (_input.LA(1) != IntStream.EOF) _input.consume()
                        } else if (c == '#'.code && _input.LA(2) == '{'.code) {
                            _input.consume() // '#'
                            val innerPos = _input.index()
                            val cached = braceCloseMap[innerPos]
                            if (cached != null) {
                                if (cached < 0) {
                                    while (_input.LA(1) != IntStream.EOF) _input.consume()
                                    break
                                }
                                _input.seek(cached + 1)
                            } else {
                                braceStack.add(innerPos)
                                _input.consume()
                            }
                        } else {
                            _input.consume()
                            if (c == '"'.code) break
                        }
                    }
                }

                '#'.code -> {
                    _input.consume()
                    consumeRestOfLine()
                }

                else -> _input.consume()
            }
        }

        if (braceStack.isNotEmpty()) {
            for (unclosedPos in braceStack) {
                braceCloseMap[unclosedPos] = -1
            }
            return false
        }
        return true
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
        clearTransientHeaderStatesOnValue()
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

    // ---------------- Heredocs (FIFO queue scoped by interp depth) ----------------

    protected fun enqueueHeredoc() {
        clearTransientHeaderStatesOnValue()
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
                interpDepth = interpBraceDepths.size,
            ),
        )
        exprState = ExprState.END
        sawSpaceSinceLastToken = false
    }

    private fun dequeuePendingHeredocAtCurrentDepth(): HeredocSpec? {
        val depth = interpBraceDepths.size
        for (i in pendingHeredocs.indices) {
            if (pendingHeredocs[i].interpDepth == depth) {
                return pendingHeredocs.removeAt(i)
            }
        }
        return null
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
        val nextSpec = dequeuePendingHeredocAtCurrentDepth()
        if (nextSpec != null) {
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
        val RUBY_RESERVED_WORDS = hashSetOf(
            "__ENCODING__", "__FILE__", "__LINE__", "BEGIN", "END", "alias", "and",
            "begin", "break", "case", "class", "def", "defined?", "do", "else",
            "elsif", "end", "ensure", "false", "for", "if", "in", "module",
            "next", "nil", "not", "or", "redo", "rescue", "retry", "return",
            "self", "super", "then", "true", "undef", "unless", "until", "when",
            "while", "yield",
        )

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

        fun isRubyIdentCont(ch: Int): Boolean =
            ch != IntStream.EOF && (
                ch in 'a'.code..'z'.code ||
                    ch in 'A'.code..'Z'.code ||
                    ch in '0'.code..'9'.code ||
                    ch == '_'.code ||
                    (ch >= 0x80 && ch !in 0xD800..0xDFFF && ch != 0xFEFF)
                )

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
