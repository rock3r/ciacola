package dev.sebastiano.ciacola.syntax.antlr.ruby

import org.antlr.v4.kotlinruntime.CharStream
import org.antlr.v4.kotlinruntime.IntStream
import org.antlr.v4.kotlinruntime.Lexer

/**
 * Pure Kotlin Multiplatform base lexer for [RubyLexer], handling:
 * - Column-0 `=begin ... =end` embedded documentation and `__END__` data sections.
 * - Ruby expression-state tracking (`BEG`, `MID`, `END`, `CMDARG`, `CLASS`, `DEF`, `DOT`) with
 *   local-variable scoping and space-sensitivity for disambiguating `/regex/` vs `/`, `%w[]` vs `%`,
 *   `<<EOF` vs `<<`, `?a` vs `?`, `:sym` vs `:`, and compact ternary `? a:b` vs `HASH_KEY`.
 * - Paired and arbitrary-delimiter `%` literals with nested delimiter counting.
 * - Multi-line `/regex/[imxouesn]*` literals with `\/` escapes and `#{...}` interpolation.
 * - FIFO-queued `<<`, `<<-`, and `<<~` heredocs scoped by interpolation depth (bare, double-quoted,
 *   single-quoted, backtick) with backslash newline continuations and `#{...}` interpolation.
 * - Iterative brace-depth tracking for nested `#{...}` interpolations in O(N) time.
 */
internal abstract class RubyLexerBase(input: CharStream) : Lexer(input) {

    protected enum class ExprState {
        BEG,
        MID,
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
        isolatedOwner: LocalScope? = null,
    ) {
        val isolatedOwner: LocalScope = isolatedOwner ?: this
        val vars: HashSet<String> = HashSet()
        val visibleVarCounts: HashMap<String, Int>? = if (isIsolated) HashMap() else null
        var prev: LocalScope? = null
        var next: LocalScope? = null
        var attached: Boolean = true
    }

    private class EndScopeEntry(
        val kind: EndScopeKind,
        val scope: LocalScope?,
    )

    private class DelimitedFrame(
        val openCp: Int,
        val closeCp: Int,
        val isPaired: Boolean,
        val isRegex: Boolean,
        val isSymbol: Boolean,
        var depth: Int = 1,
    )

    private class LambdaHeaderState(
        val scope: LocalScope,
        var expectingParams: Boolean = true,
        var collectingParams: Boolean = false,
        var hasParens: Boolean = false,
        var inParamDefault: Boolean = false,
        val baseDelimDepth: Int,
        val baseEndScopeDepth: Int,
    )

    private class PipeParamState(
        val ownerScope: LocalScope,
        val baseDelimDepth: Int,
        val baseEndScopeDepth: Int,
        var inParamDefault: Boolean = false,
    )

    private class EndlessDefFrame(
        val baseDelimDepth: Int,
        val baseEndScopeDepth: Int,
    )

    private class DQuoteFrame(
        val isSymbol: Boolean,
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
    private val pendingHeredocsByDepth = HashMap<Int, ArrayDeque<HeredocSpec>>()
    private val heredocStack = ArrayList<HeredocFrame>()

    private var rootLocalScope: LocalScope = LocalScope(isIsolated = true)
    private var topLocalScope: LocalScope = rootLocalScope
    private val endScopeStack = ArrayList<EndScopeEntry>()
    private val braceBlockScopes = ArrayList<LocalScope?>()
    private val lhsCandidates = ArrayList<String>()
    private var expectingLhsAfterComma = false
    private var expectingForVar = false
    private var expectingLoopDo = false
    private var loopHeaderDelimDepth = 0
    private val savedLoopDoState = ArrayList<Pair<Boolean, Int>>()
    private var inRescueHeader = false
    private var expectingRescueVar = false
    private var inDefHeader = false
    private var inDefNameBrackets = false
    private var collectingDefParams = false
    private var defParamsHaveParens = false
    private var defSeenBareParam = false
    private var defParamBaseDelimDepth = 0
    private var defParamBaseEndScopeDepth = 0
    private var defParamOwnerScope: LocalScope? = null
    private var inDefParamDefault = false
    private var defParamCanContinueNewline = false
    private var awaitingEndlessDefEq = false
    private val endlessDefStack = ArrayList<EndlessDefFrame>()
    private val lambdaHeaderStack = ArrayList<LambdaHeaderState>()
    private var pendingBlockParamScope: LocalScope? = null
    private val pipeParamStack = ArrayList<PipeParamState>()

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
        pendingHeredocsByDepth.clear()
        heredocStack.clear()
        rootLocalScope = LocalScope(isIsolated = true)
        topLocalScope = rootLocalScope
        endScopeStack.clear()
        braceBlockScopes.clear()
        lhsCandidates.clear()
        expectingLhsAfterComma = false
        expectingForVar = false
        expectingLoopDo = false
        loopHeaderDelimDepth = 0
        savedLoopDoState.clear()
        inRescueHeader = false
        expectingRescueVar = false
        inDefHeader = false
        inDefNameBrackets = false
        collectingDefParams = false
        defParamsHaveParens = false
        defSeenBareParam = false
        defParamBaseDelimDepth = 0
        defParamBaseEndScopeDepth = 0
        defParamOwnerScope = null
        inDefParamDefault = false
        defParamCanContinueNewline = false
        awaitingEndlessDefEq = false
        endlessDefStack.clear()
        lambdaHeaderStack.clear()
        pendingBlockParamScope = null
        pipeParamStack.clear()
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
        exprState == ExprState.BEG ||
            exprState == ExprState.MID ||
            (exprState == ExprState.CMDARG && sawSpaceSinceLastToken)

    protected fun isRegexStart(): Boolean {
        if (exprState == ExprState.BEG || exprState == ExprState.MID) return true
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
        if (exprState == ExprState.BEG || exprState == ExprState.MID) return true
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
        abortLambdaHeadersOnNewline(delimiterDepth, endScopeStack.size, exprState)
        pendingBlockParamScope = null

        if (collectingDefParams &&
            !defParamsHaveParens &&
            delimiterDepth <= defParamBaseDelimDepth &&
            endScopeStack.size <= defParamBaseEndScopeDepth &&
            !defParamCanContinueNewline
        ) {
            collectingDefParams = false
            defSeenBareParam = false
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
        if (exprState != ExprState.BEG) {
            popCompletedEndlessDefs()
        }
        if (expectingLoopDo && delimiterDepth <= loopHeaderDelimDepth && exprState != ExprState.BEG) {
            expectingLoopDo = false
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
        defParamCanContinueNewline = isAtBareDefParamDefaultContinuation()
        exprState = ExprState.BEG
        sawSpaceSinceLastToken = false
    }

    protected fun onQuestion() {
        clearTransientHeaderStatesOnOperator()
        defParamCanContinueNewline = isAtBareDefParamDefaultContinuation()
        ternaryDepth++
        exprState = ExprState.BEG
        sawSpaceSinceLastToken = false
    }

    protected fun onColon() {
        clearTransientHeaderStatesOnOperator()
        defParamCanContinueNewline = isAtBareDefParamDefaultContinuation()
        if (ternaryDepth > 0) {
            ternaryDepth--
        }
        exprState = ExprState.BEG
        sawSpaceSinceLastToken = false
    }

    protected fun onComma() {
        expectingRescueVar = false
        abortNonCollectingLambdaAtBase()
        pendingBlockParamScope = null
        awaitingEndlessDefEq = false
        if (isAtDefParamCommaLevel()) {
            inDefParamDefault = false
            defParamCanContinueNewline = !defParamsHaveParens
        } else {
            defParamCanContinueNewline = false
        }
        val topLambda = lambdaHeaderStack.lastOrNull()
        if (topLambda != null && isAtLambdaParamCommaLevel(topLambda)) {
            topLambda.inParamDefault = false
        }
        val topPipe = activePipeParam()
        if (topPipe != null && isAtPipeParamCommaLevel(topPipe)) {
            topPipe.inParamDefault = false
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
        expectingLoopDo = false
        inRescueHeader = false
        expectingRescueVar = false
        inDefHeader = false
        inDefNameBrackets = false
        defParamCanContinueNewline = false
        if (collectingDefParams &&
            !defParamsHaveParens &&
            delimiterDepth <= defParamBaseDelimDepth &&
            endScopeStack.size <= defParamBaseEndScopeDepth
        ) {
            collectingDefParams = false
            defParamsHaveParens = false
            defSeenBareParam = false
            inDefParamDefault = false
        }
        awaitingEndlessDefEq = false
        val topLambda = lambdaHeaderStack.lastOrNull()
        val isLambdaBlockLocalSemi = topLambda != null &&
            topLambda.hasParens &&
            topLambda.collectingParams &&
            delimiterDepth == topLambda.baseDelimDepth + 1 &&
            endScopeStack.size == topLambda.baseEndScopeDepth
        if (isLambdaBlockLocalSemi) {
            topLambda.inParamDefault = false
        } else {
            abortLambdaHeadersAtOrAboveBase(delimiterDepth, endScopeStack.size)
        }
        val topPipe = activePipeParam()
        if (topPipe != null && isAtPipeParamCommaLevel(topPipe)) {
            topPipe.inParamDefault = false
        }
        popCompletedEndlessDefs()
        pendingBlockParamScope = null
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
        abortNonCollectingLambdaAtBase()
        pendingBlockParamScope = null

        if (inDefNameBrackets) {
            inDefNameBrackets = false
            startDefParams(allowEndlessEq = false)
            exprState = ExprState.END
            sawSpaceSinceLastToken = false
            return
        }

        if (awaitingEndlessDefEq) {
            awaitingEndlessDefEq = false
            collectingDefParams = false
            defParamsHaveParens = false
            defSeenBareParam = false
            inDefParamDefault = false
            defParamCanContinueNewline = false
            endlessDefStack.add(
                EndlessDefFrame(
                    baseDelimDepth = delimiterDepth,
                    baseEndScopeDepth = endScopeStack.size,
                ),
            )
            exprState = ExprState.BEG
            sawSpaceSinceLastToken = false
            return
        }

        if (isAtDefParamCommaLevel()) {
            inDefParamDefault = true
            defParamCanContinueNewline = !defParamsHaveParens
        } else {
            defParamCanContinueNewline = false
        }
        val topLambda = lambdaHeaderStack.lastOrNull()
        if (topLambda != null && isAtLambdaParamCommaLevel(topLambda)) {
            topLambda.inParamDefault = true
        }
        val topPipe = activePipeParam()
        if (topPipe != null && isAtPipeParamCommaLevel(topPipe)) {
            topPipe.inParamDefault = true
        }
        exprState = ExprState.BEG
        sawSpaceSinceLastToken = false
    }

    protected fun onHashRocket() {
        lhsCandidates.clear()
        expectingLhsAfterComma = false
        abortNonCollectingLambdaAtBase()
        pendingBlockParamScope = null
        awaitingEndlessDefEq = false
        defParamCanContinueNewline = false
        if (inRescueHeader) {
            expectingRescueVar = true
        }
        exprState = ExprState.BEG
        sawSpaceSinceLastToken = false
    }

    protected fun onStabbyLambda() {
        clearTransientHeaderStatesOnOperator()
        val scope = pushLocalScope(isIsolated = false)
        lambdaHeaderStack.add(
            LambdaHeaderState(
                scope = scope,
                baseDelimDepth = delimiterDepth,
                baseEndScopeDepth = endScopeStack.size,
            ),
        )
        exprState = ExprState.BEG
        sawSpaceSinceLastToken = false
    }

    protected fun onPipe() {
        val pendingScope = pendingBlockParamScope
        clearTransientHeaderStatesOnOperator()
        if (pendingScope != null && pendingScope.attached) {
            pipeParamStack.add(
                PipeParamState(
                    ownerScope = pendingScope,
                    baseDelimDepth = delimiterDepth,
                    baseEndScopeDepth = endScopeStack.size,
                ),
            )
        } else {
            val topPipe = activePipeParam()
            if (topPipe != null &&
                delimiterDepth == topPipe.baseDelimDepth &&
                endScopeStack.size == topPipe.baseEndScopeDepth
            ) {
                pipeParamStack.removeAt(pipeParamStack.lastIndex)
            } else {
                defParamCanContinueNewline = isAtBareDefParamDefaultContinuation()
            }
        }
        exprState = ExprState.BEG
        sawSpaceSinceLastToken = false
    }

    protected fun onLParen() {
        lhsCandidates.clear()
        expectingLhsAfterComma = false
        expectingRescueVar = false
        pendingBlockParamScope = null
        inDefNameBrackets = false
        defParamCanContinueNewline = false

        var openedLambdaParams = false
        val topLambda = activeTopLambdaAtBase()
        if (topLambda != null) {
            if (topLambda.expectingParams) {
                topLambda.expectingParams = false
                topLambda.collectingParams = true
                topLambda.hasParens = true
                topLambda.inParamDefault = false
                openedLambdaParams = true
            } else if (!topLambda.collectingParams) {
                abortTopLambdaHeader()
            }
        }

        savedDelimiterTernaryDepths.add(ternaryDepth)
        ternaryDepth = 0
        savedLoopDoState.add(expectingLoopDo to loopHeaderDelimDepth)
        expectingLoopDo = false
        delimiterDepth++

        if (!openedLambdaParams &&
            collectingDefParams &&
            !defParamsHaveParens &&
            !defSeenBareParam &&
            !inDefParamDefault &&
            topLocalScope === defParamOwnerScope &&
            delimiterDepth == defParamBaseDelimDepth + 1 &&
            endScopeStack.size == defParamBaseEndScopeDepth
        ) {
            defParamsHaveParens = true
            defParamBaseDelimDepth = delimiterDepth
            defParamBaseEndScopeDepth = endScopeStack.size
            awaitingEndlessDefEq = false
        }

        exprState = ExprState.BEG
        sawSpaceSinceLastToken = false
    }

    protected fun onRParen() {
        lhsCandidates.clear()
        expectingLhsAfterComma = false
        expectingRescueVar = false
        pendingBlockParamScope = null
        defParamCanContinueNewline = false
        if (delimiterDepth > 0) delimiterDepth--
        abortLambdaHeadersAboveDelimDepth(delimiterDepth)
        ternaryDepth = if (savedDelimiterTernaryDepths.isNotEmpty()) {
            savedDelimiterTernaryDepths.removeAt(savedDelimiterTernaryDepths.lastIndex)
        } else {
            0
        }
        restoreLoopDoState()

        val topLambda = activeTopLambdaAtBase()
        if (topLambda != null && topLambda.hasParens && topLambda.collectingParams) {
            topLambda.collectingParams = false
            topLambda.inParamDefault = false
        } else if (collectingDefParams &&
            defParamsHaveParens &&
            delimiterDepth < defParamBaseDelimDepth &&
            endScopeStack.size <= defParamBaseEndScopeDepth
        ) {
            collectingDefParams = false
            defParamsHaveParens = false
            defSeenBareParam = false
            inDefParamDefault = false
            awaitingEndlessDefEq = true
        }

        exprState = ExprState.END
        sawSpaceSinceLastToken = false
    }

    protected fun onLBrack() {
        lhsCandidates.clear()
        expectingLhsAfterComma = false
        expectingRescueVar = false
        abortNonCollectingLambdaAtBase()
        pendingBlockParamScope = null
        defParamCanContinueNewline = false
        savedDelimiterTernaryDepths.add(ternaryDepth)
        ternaryDepth = 0
        savedLoopDoState.add(expectingLoopDo to loopHeaderDelimDepth)
        expectingLoopDo = false
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
        pendingBlockParamScope = null
        defParamCanContinueNewline = false
        if (delimiterDepth > 0) delimiterDepth--
        abortLambdaHeadersAboveDelimDepth(delimiterDepth)
        ternaryDepth = if (savedDelimiterTernaryDepths.isNotEmpty()) {
            savedDelimiterTernaryDepths.removeAt(savedDelimiterTernaryDepths.lastIndex)
        } else {
            0
        }
        restoreLoopDoState()
        if (inDefNameBrackets) {
            // `def []` or `def []=` — wait one token for optional `=`.
            inDefNameBrackets = _input.LA(1) == '='.code
            if (!inDefNameBrackets) {
                startDefParams(allowEndlessEq = true)
            }
        }
        exprState = ExprState.END
        sawSpaceSinceLastToken = false
    }

    protected fun onHashKey(lexeme: String) {
        lhsCandidates.clear()
        expectingLhsAfterComma = false
        expectingRescueVar = false
        pendingBlockParamScope = null
        awaitingEndlessDefEq = false
        defParamCanContinueNewline = false

        val topLambda = activeTopLambdaAtBase()
        if (topLambda != null) {
            if (topLambda.expectingParams) {
                topLambda.expectingParams = false
                topLambda.collectingParams = true
                topLambda.hasParens = false
                topLambda.inParamDefault = false
            } else if (!topLambda.collectingParams) {
                abortTopLambdaHeader()
            }
        }

        val name = if (lexeme.length > 1) lexeme.substring(0, lexeme.length - 1) else ""
        if (isCollectingDefParamName()) {
            bindLocalVar(name)
            inDefParamDefault = true
            if (!defParamsHaveParens) defSeenBareParam = true
        } else if (isCollectingLambdaParamName()) {
            bindLocalVar(name)
            lambdaHeaderStack.last().inParamDefault = true
        } else if (isCollectingPipeParamName()) {
            bindLocalVar(name)
            activePipeParam()?.inParamDefault = true
        }

        exprState = ExprState.BEG
        sawSpaceSinceLastToken = false
    }

    protected fun onEndToken() {
        clearTransientHeaderStatesOnValue()
        exprState = ExprState.END
        sawSpaceSinceLastToken = false
    }

    protected fun onParamPrefixOperatorToken() {
        if (isDefMethodNameContext()) {
            onDefOperatorToken()
            return
        }
        if (collectingDefParams && !defParamsHaveParens && isAtDefParamCommaLevel()) {
            defSeenBareParam = true
        }
        val topLambda = activeTopLambdaAtBase()
        if (topLambda != null && topLambda.expectingParams) {
            lhsCandidates.clear()
            expectingLhsAfterComma = false
            expectingRescueVar = false
            inDefHeader = false
            pendingBlockParamScope = null
            awaitingEndlessDefEq = false
            defParamCanContinueNewline = false
            topLambda.expectingParams = false
            topLambda.collectingParams = true
            topLambda.hasParens = false
            topLambda.inParamDefault = false
            exprState = ExprState.BEG
            sawSpaceSinceLastToken = false
            return
        }
        clearTransientHeaderStatesOnOperator()
        defParamCanContinueNewline = isAtBareDefParamDefaultContinuation()
        exprState = ExprState.BEG
        sawSpaceSinceLastToken = false
    }

    protected fun onDefOperatorToken() {
        if (isDefMethodNameContext()) {
            startDefParams(allowEndlessEq = true)
            exprState = ExprState.END
        } else {
            clearTransientHeaderStatesOnOperator()
            defParamCanContinueNewline = isAtBareDefParamDefaultContinuation()
            exprState = ExprState.BEG
        }
        sawSpaceSinceLastToken = false
    }

    protected fun onDotToken() {
        lhsCandidates.clear()
        expectingLhsAfterComma = false
        expectingRescueVar = false
        abortNonCollectingLambdaAtBase()
        pendingBlockParamScope = null
        awaitingEndlessDefEq = false
        defParamCanContinueNewline = isAtBareDefParamDefaultContinuation()
        exprState = ExprState.DOT
        sawSpaceSinceLastToken = false
    }

    protected fun onIdentifierOrKeyword(lexeme: String) {
        val prevState = exprState
        val wasInDefHeader = inDefHeader
        pendingBlockParamScope = null
        defParamCanContinueNewline = false

        if (prevState == ExprState.DOT) {
            if (wasInDefHeader) {
                inDefHeader = isDotOrScopeAhead()
                if (!inDefHeader) {
                    if (isBareSetterEqualsAhead()) {
                        inDefNameBrackets = true
                        collectingDefParams = false
                        awaitingEndlessDefEq = false
                    } else {
                        startDefParams(allowEndlessEq = true)
                    }
                }
            }
            exprState = ExprState.END
            sawSpaceSinceLastToken = false
            return
        }

        if (prevState == ExprState.DEF) {
            if (isDotOrScopeAhead()) {
                inDefHeader = true
            } else if (isBareSetterEqualsAhead()) {
                inDefHeader = false
                inDefNameBrackets = true
                collectingDefParams = false
                awaitingEndlessDefEq = false
            } else {
                startDefParams(allowEndlessEq = true)
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
                abortNonCollectingLambdaAtBase()
                inDefHeader = true
                collectingDefParams = false
                defParamsHaveParens = false
                defSeenBareParam = false
                inDefParamDefault = false
                awaitingEndlessDefEq = false
                ternaryDepth = 0
                defParamOwnerScope = pushEndScope(EndScopeKind.ISOLATED)
                exprState = ExprState.DEF
                sawSpaceSinceLastToken = false
                return
            }

            "class", "module" -> {
                lhsCandidates.clear()
                expectingLhsAfterComma = false
                abortNonCollectingLambdaAtBase()
                ternaryDepth = 0
                pushEndScope(EndScopeKind.ISOLATED)
                exprState = ExprState.CLASS
                sawSpaceSinceLastToken = false
                return
            }

            "do" -> {
                lhsCandidates.clear()
                expectingLhsAfterComma = false
                val topLambda = activeTopLambdaAtBase()
                if (topLambda != null) {
                    lambdaHeaderStack.removeAt(lambdaHeaderStack.lastIndex)
                    endScopeStack.add(EndScopeEntry(EndScopeKind.BLOCK, topLambda.scope))
                } else if (expectingLoopDo && delimiterDepth <= loopHeaderDelimDepth) {
                    expectingLoopDo = false
                } else {
                    pendingBlockParamScope = pushEndScope(EndScopeKind.BLOCK)
                }
                exprState = ExprState.BEG
                sawSpaceSinceLastToken = false
                return
            }

            "begin", "case" -> {
                lhsCandidates.clear()
                expectingLhsAfterComma = false
                abortNonCollectingLambdaAtBase()
                pushEndScope(EndScopeKind.PLAIN)
                exprState = ExprState.BEG
                sawSpaceSinceLastToken = false
                return
            }

            "for" -> {
                lhsCandidates.clear()
                expectingLhsAfterComma = false
                abortNonCollectingLambdaAtBase()
                expectingForVar = true
                expectingLoopDo = true
                loopHeaderDelimDepth = delimiterDepth
                pushEndScope(EndScopeKind.PLAIN)
                exprState = ExprState.BEG
                sawSpaceSinceLastToken = false
                return
            }

            "while", "until" -> {
                lhsCandidates.clear()
                expectingLhsAfterComma = false
                abortNonCollectingLambdaAtBase()
                if (prevState == ExprState.BEG) {
                    expectingLoopDo = true
                    loopHeaderDelimDepth = delimiterDepth
                    pushEndScope(EndScopeKind.PLAIN)
                }
                exprState = ExprState.BEG
                sawSpaceSinceLastToken = false
                return
            }

            "if", "unless" -> {
                lhsCandidates.clear()
                expectingLhsAfterComma = false
                abortNonCollectingLambdaAtBase()
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
                abortNonCollectingLambdaAtBase()
                inRescueHeader = true
                expectingRescueVar = false
                exprState = ExprState.MID
                sawSpaceSinceLastToken = false
                return
            }

            "in" -> {
                lhsCandidates.clear()
                expectingLhsAfterComma = false
                abortNonCollectingLambdaAtBase()
                expectingForVar = false
                exprState = ExprState.BEG
                sawSpaceSinceLastToken = false
                return
            }

            "return", "yield", "break", "next" -> {
                lhsCandidates.clear()
                expectingLhsAfterComma = false
                abortNonCollectingLambdaAtBase()
                exprState = ExprState.MID
                sawSpaceSinceLastToken = false
                return
            }

            "elsif", "else", "ensure", "when", "then",
            "alias", "undef", "defined?",
            -> {
                lhsCandidates.clear()
                expectingLhsAfterComma = false
                abortNonCollectingLambdaAtBase()
                exprState = ExprState.BEG
                sawSpaceSinceLastToken = false
                return
            }

            "and", "or", "not" -> {
                lhsCandidates.clear()
                expectingLhsAfterComma = false
                abortNonCollectingLambdaAtBase()
                defParamCanContinueNewline = isAtBareDefParamDefaultContinuation()
                exprState = ExprState.BEG
                sawSpaceSinceLastToken = false
                return
            }

            "end" -> {
                lhsCandidates.clear()
                expectingLhsAfterComma = false
                expectingLoopDo = false
                inRescueHeader = false
                expectingRescueVar = false
                if (collectingDefParams && endScopeStack.size <= defParamBaseEndScopeDepth) {
                    collectingDefParams = false
                    defParamsHaveParens = false
                    defSeenBareParam = false
                    inDefParamDefault = false
                    defParamOwnerScope = null
                }
                while (lambdaHeaderStack.isNotEmpty() &&
                    lambdaHeaderStack.last().baseEndScopeDepth >= endScopeStack.size &&
                    lambdaHeaderStack.last().baseDelimDepth >= delimiterDepth
                ) {
                    abortTopLambdaHeader()
                }
                popCompletedEndlessDefs()
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
                abortNonCollectingLambdaAtBase()
                exprState = ExprState.END
                sawSpaceSinceLastToken = false
                return
            }
        }

        // Plain identifier or constant.
        awaitingEndlessDefEq = false
        val isLocalCandidate = isBindableLocalName(lexeme)
        val topLambda = activeTopLambdaAtBase()
        if (topLambda != null) {
            if (topLambda.expectingParams) {
                if (isLocalCandidate) {
                    topLambda.expectingParams = false
                    topLambda.collectingParams = true
                    topLambda.hasParens = false
                    topLambda.inParamDefault = false
                } else {
                    abortTopLambdaHeader()
                }
            } else if (!topLambda.collectingParams) {
                abortTopLambdaHeader()
            }
        }

        if (expectingForVar && isLocalCandidate) {
            bindLocalVar(lexeme)
        } else if (expectingRescueVar && isLocalCandidate) {
            bindLocalVar(lexeme)
            expectingRescueVar = false
            inRescueHeader = false
        } else if (isCollectingDefParamName() && isLocalCandidate) {
            bindLocalVar(lexeme)
            if (!defParamsHaveParens) defSeenBareParam = true
        } else if (isCollectingLambdaParamName() && isLocalCandidate) {
            bindLocalVar(lexeme)
        } else if (isCollectingPipeParamName() && isLocalCandidate) {
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
        abortNonCollectingLambdaAtBase()
        pendingBlockParamScope = null
        awaitingEndlessDefEq = false
        defParamCanContinueNewline = false
    }

    private fun clearTransientHeaderStatesOnValue() {
        lhsCandidates.clear()
        expectingLhsAfterComma = false
        expectingRescueVar = false
        inDefHeader = false
        abortNonCollectingLambdaAtBase()
        pendingBlockParamScope = null
        awaitingEndlessDefEq = false
        defParamCanContinueNewline = false
    }

    private fun startDefParams(allowEndlessEq: Boolean) {
        inDefHeader = false
        collectingDefParams = true
        defParamsHaveParens = false
        defSeenBareParam = false
        defParamBaseDelimDepth = delimiterDepth
        defParamBaseEndScopeDepth = endScopeStack.size
        inDefParamDefault = false
        defParamCanContinueNewline = false
        awaitingEndlessDefEq = allowEndlessEq
    }

    private fun isBareSetterEqualsAhead(): Boolean {
        if (_input.LA(1) != '='.code) return false
        val c2 = _input.LA(2)
        return c2 != '='.code && c2 != '~'.code && c2 != '>'.code
    }

    private fun isAtDefParamCommaLevel(): Boolean =
        collectingDefParams &&
            topLocalScope === defParamOwnerScope &&
            delimiterDepth == defParamBaseDelimDepth &&
            endScopeStack.size == defParamBaseEndScopeDepth

    private fun isAtBareDefParamDefaultContinuation(): Boolean =
        !defParamsHaveParens && inDefParamDefault && isAtDefParamCommaLevel()

    private fun isCollectingDefParamName(): Boolean =
        collectingDefParams &&
            !inDefParamDefault &&
            topLocalScope === defParamOwnerScope &&
            endScopeStack.size == defParamBaseEndScopeDepth &&
            delimiterDepth >= defParamBaseDelimDepth

    private fun activeTopLambdaAtBase(): LambdaHeaderState? {
        val top = lambdaHeaderStack.lastOrNull() ?: return null
        return if (delimiterDepth == top.baseDelimDepth && endScopeStack.size == top.baseEndScopeDepth) {
            top
        } else {
            null
        }
    }

    private fun isCollectingLambdaParamName(): Boolean {
        val top = lambdaHeaderStack.lastOrNull() ?: return false
        if (!top.collectingParams || top.inParamDefault) return false
        if (endScopeStack.size != top.baseEndScopeDepth) return false
        return if (top.hasParens) {
            delimiterDepth > top.baseDelimDepth
        } else {
            delimiterDepth >= top.baseDelimDepth
        }
    }

    private fun isAtLambdaParamCommaLevel(top: LambdaHeaderState): Boolean {
        if (!top.collectingParams || endScopeStack.size != top.baseEndScopeDepth) return false
        return if (top.hasParens) {
            delimiterDepth == top.baseDelimDepth + 1
        } else {
            delimiterDepth == top.baseDelimDepth
        }
    }

    private fun activePipeParam(): PipeParamState? {
        while (pipeParamStack.isNotEmpty() && !pipeParamStack.last().ownerScope.attached) {
            pipeParamStack.removeAt(pipeParamStack.lastIndex)
        }
        return pipeParamStack.lastOrNull()
    }

    private fun isCollectingPipeParamName(): Boolean {
        val top = activePipeParam() ?: return false
        if (top.inParamDefault) return false
        return topLocalScope === top.ownerScope &&
            endScopeStack.size == top.baseEndScopeDepth &&
            delimiterDepth >= top.baseDelimDepth
    }

    private fun isAtPipeParamCommaLevel(top: PipeParamState): Boolean =
        topLocalScope === top.ownerScope &&
            delimiterDepth == top.baseDelimDepth &&
            endScopeStack.size == top.baseEndScopeDepth

    private fun pushLocalScope(isIsolated: Boolean): LocalScope {
        val owner = if (isIsolated) null else topLocalScope.isolatedOwner
        val scope = LocalScope(isIsolated = isIsolated, isolatedOwner = owner)
        scope.prev = topLocalScope
        topLocalScope.next = scope
        topLocalScope = scope
        return scope
    }

    private fun unlinkLocalScope(scope: LocalScope) {
        if (scope === rootLocalScope || !scope.attached) return
        scope.attached = false
        if (scope.isIsolated) {
            // Detach any unclosed non-isolated child blocks owned by this isolated scope so
            // closed method/class locals cannot remain visible after malformed unclosed braces.
            var child = scope.next
            while (child != null && !child.isIsolated) {
                val nextChild = child.next
                child.attached = false
                child.prev = null
                child.next = null
                child = nextChild
            }
            val prev = scope.prev
            if (prev != null) {
                prev.next = child
            }
            if (child != null) {
                child.prev = prev
            } else {
                topLocalScope = prev ?: rootLocalScope
            }
            scope.prev = null
            scope.next = null
            return
        }
        if (scope.vars.isNotEmpty()) {
            val counts = scope.isolatedOwner.visibleVarCounts
            if (counts != null) {
                for (name in scope.vars) {
                    val remaining = (counts[name] ?: 0) - 1
                    if (remaining <= 0) {
                        counts.remove(name)
                    } else {
                        counts[name] = remaining
                    }
                }
            }
        }
        val prev = scope.prev
        val next = scope.next
        if (prev != null) {
            prev.next = next
        }
        if (next != null) {
            next.prev = prev
        } else {
            topLocalScope = prev ?: rootLocalScope
        }
        scope.prev = null
        scope.next = null
    }

    private fun abortTopLambdaHeader() {
        if (lambdaHeaderStack.isEmpty()) return
        val header = lambdaHeaderStack.removeAt(lambdaHeaderStack.lastIndex)
        unlinkLocalScope(header.scope)
    }

    private fun abortNonCollectingLambdaAtBase() {
        val top = activeTopLambdaAtBase()
        if (top != null && !top.collectingParams) {
            abortTopLambdaHeader()
        }
    }

    private fun abortLambdaHeadersAboveDelimDepth(depth: Int) {
        while (lambdaHeaderStack.isNotEmpty()) {
            val top = lambdaHeaderStack.last()
            if (top.baseDelimDepth > depth && top.baseEndScopeDepth >= endScopeStack.size) {
                abortTopLambdaHeader()
            } else {
                break
            }
        }
    }

    private fun abortLambdaHeadersOnNewline(delimDepth: Int, endDepth: Int, stateBeforeNewline: ExprState) {
        while (lambdaHeaderStack.isNotEmpty()) {
            val top = lambdaHeaderStack.last()
            if (top.baseDelimDepth >= delimDepth && top.baseEndScopeDepth >= endDepth) {
                // Keep unparenthesized lambda params alive across line continuations such as `-> a,\n b { ... }`.
                if (top.collectingParams && stateBeforeNewline == ExprState.BEG) {
                    break
                }
                abortTopLambdaHeader()
            } else {
                break
            }
        }
    }

    private fun abortLambdaHeadersAtOrAboveBase(delimDepth: Int, endDepth: Int) {
        while (lambdaHeaderStack.isNotEmpty()) {
            val top = lambdaHeaderStack.last()
            if (top.baseDelimDepth >= delimDepth && top.baseEndScopeDepth >= endDepth) {
                abortTopLambdaHeader()
            } else {
                break
            }
        }
    }

    private fun pushEndScope(kind: EndScopeKind): LocalScope? {
        val scope = if (kind == EndScopeKind.ISOLATED || kind == EndScopeKind.BLOCK) {
            pushLocalScope(isIsolated = kind == EndScopeKind.ISOLATED)
        } else {
            null
        }
        endScopeStack.add(EndScopeEntry(kind, scope))
        return scope
    }

    private fun popEndScope() {
        if (endScopeStack.isEmpty()) return
        val entry = endScopeStack.removeAt(endScopeStack.lastIndex)
        if (entry.scope != null) {
            unlinkLocalScope(entry.scope)
        }
    }

    private fun popCompletedEndlessDefs() {
        while (endlessDefStack.isNotEmpty()) {
            val top = endlessDefStack.last()
            if (delimiterDepth <= top.baseDelimDepth && endScopeStack.size <= top.baseEndScopeDepth) {
                endlessDefStack.removeAt(endlessDefStack.lastIndex)
                if (endScopeStack.isNotEmpty() && endScopeStack.last().kind == EndScopeKind.ISOLATED) {
                    popEndScope()
                }
            } else {
                break
            }
        }
    }

    private fun bindLocalVar(name: String) {
        if (name.isEmpty() || !isBindableLocalName(name)) return
        if (topLocalScope.vars.add(name)) {
            val counts = topLocalScope.isolatedOwner.visibleVarCounts ?: return
            counts[name] = (counts[name] ?: 0) + 1
        }
    }

    private fun isLocalVar(name: String): Boolean {
        val counts = topLocalScope.isolatedOwner.visibleVarCounts ?: return false
        return name in counts
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
        val prevState = exprState
        lhsCandidates.clear()
        expectingLhsAfterComma = false
        expectingRescueVar = false
        awaitingEndlessDefEq = false
        defParamCanContinueNewline = false

        val adoptedLambda = activeTopLambdaAtBase()
        if (adoptedLambda != null) {
            lambdaHeaderStack.removeAt(lambdaHeaderStack.lastIndex)
        }
        val isBlockBrace = adoptedLambda != null || prevState == ExprState.END || prevState == ExprState.CMDARG

        savedDelimiterTernaryDepths.add(ternaryDepth)
        ternaryDepth = 0
        savedLoopDoState.add(expectingLoopDo to loopHeaderDelimDepth)
        expectingLoopDo = false
        delimiterDepth++

        if (interpBraceDepths.isNotEmpty()) {
            interpBraceDepths[interpBraceDepths.lastIndex]++
        }
        if (adoptedLambda != null) {
            // Adopt the LocalScope pushed on `->` without opening a duplicate scope.
            braceBlockScopes.add(adoptedLambda.scope)
            pendingBlockParamScope = null
        } else if (isBlockBrace) {
            val scope = pushLocalScope(isIsolated = false)
            braceBlockScopes.add(scope)
            pendingBlockParamScope = scope
        } else {
            braceBlockScopes.add(null)
            pendingBlockParamScope = null
        }
        exprState = ExprState.BEG
        sawSpaceSinceLastToken = false
    }

    protected fun onRBrace(interpCloseToken: Int) {
        lhsCandidates.clear()
        expectingLhsAfterComma = false
        expectingRescueVar = false
        pendingBlockParamScope = null
        defParamCanContinueNewline = false
        if (delimiterDepth > 0) delimiterDepth--
        abortLambdaHeadersAboveDelimDepth(delimiterDepth)
        ternaryDepth = if (savedDelimiterTernaryDepths.isNotEmpty()) {
            savedDelimiterTernaryDepths.removeAt(savedDelimiterTernaryDepths.lastIndex)
        } else {
            0
        }
        restoreLoopDoState()

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
        if (braceBlockScopes.isEmpty()) return
        val scope = braceBlockScopes.removeAt(braceBlockScopes.lastIndex)
        if (scope != null) {
            unlinkLocalScope(scope)
        }
    }

    private fun restoreLoopDoState() {
        if (savedLoopDoState.isNotEmpty()) {
            val saved = savedLoopDoState.removeAt(savedLoopDoState.lastIndex)
            expectingLoopDo = saved.first
            loopHeaderDelimDepth = saved.second
        } else {
            expectingLoopDo = false
            loopHeaderDelimDepth = 0
        }
    }

    protected fun enterInterp(defaultMode: Int) {
        savedDelimiterTernaryDepths.add(ternaryDepth)
        ternaryDepth = 0
        savedLoopDoState.add(expectingLoopDo to loopHeaderDelimDepth)
        expectingLoopDo = false
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

    protected fun enterBacktick(backtickMode: Int) {
        clearTransientHeaderStatesOnValue()
        pushMode(backtickMode)
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
        pushMode(regexMode)
    }

    protected fun exitRegexMode() {
        consumeRegexFlags()
        if (!_modeStack.isEmpty) {
            popMode()
        }
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
        val depth = interpBraceDepths.size
        pendingHeredocsByDepth.getOrPut(depth) { ArrayDeque() }.addLast(
            HeredocSpec(
                delimiter = delimiter,
                delimiterCodePoints = toCodePoints(delimiter),
                allowIndent = allowIndent,
                isQuoted = isQuoted,
                interpDepth = depth,
            ),
        )
        exprState = ExprState.END
        sawSpaceSinceLastToken = false
    }

    private fun dequeuePendingHeredocAtCurrentDepth(): HeredocSpec? {
        val depth = interpBraceDepths.size
        val queue = pendingHeredocsByDepth[depth] ?: return null
        val spec = queue.removeFirstOrNull()
        if (queue.isEmpty()) {
            pendingHeredocsByDepth.remove(depth)
        }
        return spec
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
