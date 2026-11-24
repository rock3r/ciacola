package dev.sebastiano.ciacola.syntax.antlr.markdown

import org.antlr.v4.kotlinruntime.CharStream
import org.antlr.v4.kotlinruntime.IntStream
import org.antlr.v4.kotlinruntime.Lexer

/**
 * Pure Kotlin Multiplatform base lexer for `MarkdownLexer`.
 *
 * Provides strictly O(N) scanning and lookahead for CommonMark + GFM + Frontmatter blocks
 * and inline constructs without position-0 ANTLR semantic predicates:
 * - precomputed O(N) backtick run table for inline code spans,
 * - one-shot O(N) frontmatter closing verification at offset 0 (or post-BOM),
 * - fenced code block info/body/closing matching (top-level and blockquote-nested),
 * - indented code block and Setext underline context tracking,
 * - 3-state memoized inline HTML tag validation and O(N) HTML comments/autolinks.
 */
internal abstract class MarkdownLexerBase(input: CharStream) : Lexer(input) {

    // Document & line-state tracking
    private var atDocStart: Boolean = true
    private var startsWithBom: Boolean = false
    private var afterBlankLine: Boolean = true
    private var prevLineWasParagraph: Boolean = false
    private var prevLineBlockquoteDepth: Int = 0
    private var prevLineWasIndentedCode: Boolean = false
    private var prevLineAllowsIndentedCode: Boolean = true

    private var currentLineIsBlank: Boolean = true
    private var currentLineIsParagraph: Boolean = false
    private var currentLineIsIndentedCode: Boolean = false
    private var currentLineAllowsNextIndentedCode: Boolean = true
    private var currentLineHasOnlyBqAndWs: Boolean = false
    private var blockquoteDepth: Int = 0
    private var justSawBlockquoteMarker: Boolean = false
    private var leadingIndent: Int = 0
    private var lineStreamCol: Int = 0
    private var afterListMarker: Boolean = false
    private var canMatchTaskCheckbox: Boolean = false
    private var currentLineHasListMarker: Boolean = false
    private var currentLineIsRefDef: Boolean = false

    // Per-line memoized thematic-break failure positions (-1 = none on current line)
    private var tbFailedDashLinePos: Int = -1
    private var tbFailedStarLinePos: Int = -1
    private var tbFailedUnderscoreLinePos: Int = -1

    // Frontmatter state: 0 = none, 1 = YAML (---), 2 = TOML (+++)
    private var frontmatterType: Int = 0

    // Fenced code block state
    private var fenceChar: Int = 0
    private var fenceLen: Int = 0
    private var fenceBlockquoteDepth: Int = 0
    private var fenceLangEmitted: Boolean = false

    private var bqFenceSpacesAfterLastMarker: Int = 0
    private var bqFenceJustSawMarker: Boolean = false

    // HTML tag attribute-name vs tag-name state
    private var htmlExpectingTagName: Boolean = false

    // HTML block state (CommonMark §4.6 types 1-7)
    private var htmlBlockType: Int = 0
    private var htmlBlockClosed: Boolean = false
    private var htmlRawCloseTag: String = ""
    private var htmlBlockStartPos: Int = -1
    private var htmlBlockBqDepth: Int = 0
    private var hbAtLineStart: Boolean = false
    private var hbBqConsumed: Int = 0
    private var hbrAtLineStart: Boolean = false
    private var hbrBqConsumed: Int = 0

    // Lazy O(N) backtick run index: built only if a backtick is actually encountered.
    private var backtickIndexBuilt: Boolean = false
    private var backtickRunParaIds: HashMap<Int, Int>? = null
    private var backtickRunsByLength: HashMap<Int, ArrayList<Int>>? = null
    private var backtickCursorByLength: HashMap<Int, Int>? = null

    // 3-state HTML tag failure cache: encodes (cpIndex shl 2) or quoteState.
    private var failedHtmlTagStates: HashSet<Long>? = null

    // Monotonic unclosed inline HTML comment scan failure boundary (-1 = none).
    private var htmlCommentFailEnd: Int = -1

    override fun reset() {
        super.reset()
        atDocStart = true
        startsWithBom = false
        afterBlankLine = true
        prevLineWasParagraph = false
        prevLineBlockquoteDepth = 0
        prevLineWasIndentedCode = false
        prevLineAllowsIndentedCode = true

        currentLineIsBlank = true
        currentLineIsParagraph = false
        currentLineIsIndentedCode = false
        currentLineAllowsNextIndentedCode = true
        currentLineHasOnlyBqAndWs = false
        blockquoteDepth = 0
        justSawBlockquoteMarker = false
        leadingIndent = 0
        lineStreamCol = 0
        afterListMarker = false
        canMatchTaskCheckbox = false
        currentLineHasListMarker = false
        currentLineIsRefDef = false

        tbFailedDashLinePos = -1
        tbFailedStarLinePos = -1
        tbFailedUnderscoreLinePos = -1

        frontmatterType = 0
        fenceChar = 0
        fenceLen = 0
        fenceBlockquoteDepth = 0
        fenceLangEmitted = false
        bqFenceSpacesAfterLastMarker = 0
        bqFenceJustSawMarker = false
        htmlExpectingTagName = false
        htmlBlockType = 0
        htmlBlockClosed = false
        htmlRawCloseTag = ""
        htmlBlockStartPos = -1
        htmlBlockBqDepth = 0
        hbAtLineStart = false
        hbBqConsumed = 0
        hbrAtLineStart = false
        hbrBqConsumed = 0

        backtickIndexBuilt = false
        backtickRunParaIds = null
        backtickRunsByLength = null
        backtickCursorByLength = null
        failedHtmlTagStates = null
        htmlCommentFailEnd = -1
    }

    // Token & mode hooks implemented by generated MarkdownLexer
    protected abstract fun textTokenType(): Int
    protected abstract fun wsTokenType(): Int
    protected abstract fun newlineTokenType(): Int
    protected abstract fun atxHeadingOpenTokenType(): Int
    protected abstract fun setextUnderlineTokenType(): Int
    protected abstract fun thematicBreakTokenType(): Int
    protected abstract fun frontmatterDelimTokenType(): Int
    protected abstract fun frontmatterYamlBodyTokenType(): Int
    protected abstract fun frontmatterTomlBodyTokenType(): Int
    protected abstract fun fenceOpenTokenType(): Int
    protected abstract fun fenceBodyTokenType(): Int
    protected abstract fun fenceBodyLineTokenType(): Int
    protected abstract fun fenceCloseTokenType(): Int
    protected abstract fun fenceLangTokenType(): Int
    protected abstract fun fenceInfoRestTokenType(): Int
    protected abstract fun listMarkerTokenType(): Int
    protected abstract fun taskCheckboxTokenType(): Int
    protected abstract fun inlineCodeTokenType(): Int
    protected abstract fun starRunTokenType(): Int
    protected abstract fun underscoreRunTokenType(): Int
    protected abstract fun tildeRunTokenType(): Int
    protected abstract fun lbrackTokenType(): Int
    protected abstract fun htmlCommentTokenType(): Int
    protected abstract fun htmlBlockCommentTokenType(): Int
    protected abstract fun autolinkTokenType(): Int
    protected abstract fun htmlTagOpenTokenType(): Int
    protected abstract fun htmlBlockTagOpenTokenType(): Int
    protected abstract fun htmlNameTokenType(): Int
    protected abstract fun htmlAttrNameTokenType(): Int
    protected abstract fun htmlAttrValueTokenType(): Int
    protected abstract fun htmlBlockTextTokenType(): Int
    protected abstract fun blockquoteMarkerTokenType(): Int

    protected abstract fun defaultModeId(): Int
    protected abstract fun inlineModeId(): Int
    protected abstract fun indentedCodeModeId(): Int
    protected abstract fun frontmatterHeaderEndModeId(): Int
    protected abstract fun frontmatterBodyModeId(): Int
    protected abstract fun frontmatterCloseModeId(): Int
    protected abstract fun fenceInfoModeId(): Int
    protected abstract fun fenceBodyModeId(): Int
    protected abstract fun fenceCloseModeId(): Int
    protected abstract fun bqFenceInfoModeId(): Int
    protected abstract fun bqFenceBodyModeId(): Int
    protected abstract fun htmlTagModeId(): Int
    protected abstract fun htmlBlockModeId(): Int
    protected abstract fun htmlBlockRawModeId(): Int

    // -------------------------------------------------------------------------
    // Line-start handlers (DEFAULT_MODE)
    // -------------------------------------------------------------------------

    protected fun onBom() {
        if (_tokenStartCharIndex == 0) {
            startsWithBom = true
        } else {
            markInlineParagraphStart()
            type = textTokenType()
            mode(inlineModeId())
        }
    }

    protected fun onDefaultNewline() {
        prevLineWasParagraph = currentLineIsParagraph
        prevLineBlockquoteDepth = blockquoteDepth
        prevLineWasIndentedCode = currentLineIsIndentedCode
        prevLineAllowsIndentedCode =
            currentLineIsBlank || currentLineHasOnlyBqAndWs || currentLineAllowsNextIndentedCode
        afterBlankLine = currentLineIsBlank

        resetLineState()
    }

    protected fun onInlineNewline() {
        prevLineWasParagraph = currentLineIsParagraph
        prevLineBlockquoteDepth = blockquoteDepth
        prevLineWasIndentedCode = currentLineIsIndentedCode
        prevLineAllowsIndentedCode = currentLineAllowsNextIndentedCode
        afterBlankLine = false

        resetLineState()
    }

    protected fun onIndentedCodeNewline() {
        prevLineWasParagraph = false
        prevLineBlockquoteDepth = 0
        prevLineWasIndentedCode = true
        prevLineAllowsIndentedCode = true
        afterBlankLine = false

        resetLineState()
    }

    protected fun onFenceCloseNewline() {
        prevLineWasParagraph = false
        prevLineBlockquoteDepth = 0
        prevLineWasIndentedCode = false
        prevLineAllowsIndentedCode = true
        afterBlankLine = false

        resetLineState()
    }

    private fun resetLineState() {
        atDocStart = false
        currentLineIsBlank = true
        currentLineIsParagraph = false
        currentLineIsIndentedCode = false
        currentLineAllowsNextIndentedCode = false
        currentLineHasOnlyBqAndWs = false
        blockquoteDepth = 0
        justSawBlockquoteMarker = false
        leadingIndent = 0
        lineStreamCol = 0
        afterListMarker = false
        canMatchTaskCheckbox = false
        currentLineHasListMarker = false
        currentLineIsRefDef = false
        tbFailedDashLinePos = -1
        tbFailedStarLinePos = -1
        tbFailedUnderscoreLinePos = -1
    }

    protected fun onLineStartWs() {
        val start = _tokenStartCharIndex
        val end = _input.index()
        var streamCol = if (justSawBlockquoteMarker) 1 else lineStreamCol
        var col = 0
        var first = justSawBlockquoteMarker
        for (cp in start until end) {
            val ch = codePointAtIndex(cp)
            if (ch == '\t'.code) {
                val step = 4 - (streamCol % 4)
                streamCol += step
                col += if (first) step - 1 else step
                first = false
            } else {
                streamCol++
                if (first) {
                    first = false
                } else {
                    col++
                }
            }
        }
        lineStreamCol = streamCol
        justSawBlockquoteMarker = false

        if (afterListMarker) {
            canMatchTaskCheckbox = true
            leadingIndent += col
            // Stay in DEFAULT_MODE so list items can nest headings, blockquotes, lists, or checkboxes.
            return
        }

        val next = _input.LA(1)
        if (next == '\r'.code || next == '\n'.code || next == IntStream.EOF) {
            // Blank line: stay in DEFAULT_MODE and leave currentLineIsBlank = true.
            return
        }

        leadingIndent += col
        val canIndentCode = atDocStart || prevLineAllowsIndentedCode
        if (canIndentCode && leadingIndent >= 4) {
            currentLineIsBlank = false
            currentLineIsParagraph = false
            currentLineIsIndentedCode = true
            currentLineAllowsNextIndentedCode = true
            currentLineHasOnlyBqAndWs = false
            atDocStart = false
            mode(indentedCodeModeId())
        } else if (leadingIndent >= 4) {
            // 4+ spaces on a paragraph continuation cannot start a block marker.
            markInlineParagraphStart()
            mode(inlineModeId())
        }
        // Else 0..3 spaces: remain in DEFAULT_MODE for block markers.
    }

    protected fun onBlockquoteMarker() {
        atDocStart = false
        currentLineIsBlank = false
        currentLineHasOnlyBqAndWs = true
        currentLineAllowsNextIndentedCode = false
        blockquoteDepth++
        justSawBlockquoteMarker = true
        leadingIndent = 0
        lineStreamCol++
        afterListMarker = false
        canMatchTaskCheckbox = false
        currentLineHasListMarker = false
        currentLineIsRefDef = false
    }

    protected fun handleLineStartHash() {
        val count = _input.index() - _tokenStartCharIndex
        val next = _input.LA(1)
        if (count in 1..6 && isSpaceTabEolOrEof(next)) {
            atDocStart = false
            currentLineIsBlank = false
            currentLineIsParagraph = false
            currentLineAllowsNextIndentedCode = true
            currentLineHasOnlyBqAndWs = false
            afterListMarker = false
            canMatchTaskCheckbox = false
            type = atxHeadingOpenTokenType()
            mode(inlineModeId())
        } else {
            markInlineParagraphStart()
            type = textTokenType()
            mode(inlineModeId())
        }
    }

    protected fun handleLineStartBackticks() {
        val start = _tokenStartCharIndex
        val count = _input.index() - start
        if (count >= 3 && hasNoBacktickOnRestOfLine()) {
            enterFence(fenceCh = '`'.code, count = count)
        } else {
            markInlineParagraphStart()
            handleInlineBackticks(start, count)
            mode(inlineModeId())
        }
    }

    protected fun handleLineStartTildes() {
        val count = _input.index() - _tokenStartCharIndex
        if (count >= 3) {
            enterFence(fenceCh = '~'.code, count = count)
        } else {
            markInlineParagraphStart()
            type = tildeRunTokenType()
            mode(inlineModeId())
        }
    }

    private fun enterFence(fenceCh: Int, count: Int) {
        atDocStart = false
        currentLineIsBlank = false
        currentLineIsParagraph = false
        currentLineAllowsNextIndentedCode = true
        currentLineHasOnlyBqAndWs = false
        afterListMarker = false
        canMatchTaskCheckbox = false
        fenceChar = fenceCh
        fenceLen = count
        fenceBlockquoteDepth = blockquoteDepth
        fenceLangEmitted = false
        type = fenceOpenTokenType()
        mode(if (blockquoteDepth > 0) bqFenceInfoModeId() else fenceInfoModeId())
    }

    protected fun handleLineStartDash() {
        val start = _tokenStartCharIndex
        val count = _input.index() - start

        // 1. YAML frontmatter at offset 0 (or directly after leading BOM)
        if (isAtDocumentOffsetZero() && count == 3 && isOnlyWsBeforeNewline() && hasFrontmatterCloseAhead(1)) {
            atDocStart = false
            currentLineIsBlank = false
            currentLineIsParagraph = false
            currentLineHasOnlyBqAndWs = false
            frontmatterType = 1
            type = frontmatterDelimTokenType()
            mode(frontmatterHeaderEndModeId())
            return
        }

        // 2. Setext H2 underline directly under a non-empty paragraph line
        if (prevLineWasParagraph && !afterBlankLine && blockquoteDepth == prevLineBlockquoteDepth && isOnlyWsBeforeEolOrEof()) {
            atDocStart = false
            currentLineIsBlank = false
            currentLineIsParagraph = false
            currentLineAllowsNextIndentedCode = true
            currentLineHasOnlyBqAndWs = false
            type = setextUnderlineTokenType()
            mode(inlineModeId())
            return
        }

        // 3. Thematic break (--- or - - -)
        val tbEnd = findThematicBreakEnd('-'.code, count)
        if (tbEnd > start) {
            _input.seek(tbEnd)
            atDocStart = false
            currentLineIsBlank = false
            currentLineIsParagraph = false
            currentLineAllowsNextIndentedCode = true
            currentLineHasOnlyBqAndWs = false
            type = thematicBreakTokenType()
            mode(inlineModeId())
            return
        }

        // 4. Unordered list marker '-'
        if (count == 1 && isSpaceTabEolOrEof(_input.LA(1))) {
            enterListMarker()
            return
        }

        markInlineParagraphStart()
        type = textTokenType()
        mode(inlineModeId())
    }

    protected fun handleLineStartStar() {
        val start = _tokenStartCharIndex
        val count = _input.index() - start

        val tbEnd = findThematicBreakEnd('*'.code, count)
        if (tbEnd > start) {
            _input.seek(tbEnd)
            atDocStart = false
            currentLineIsBlank = false
            currentLineIsParagraph = false
            currentLineAllowsNextIndentedCode = true
            currentLineHasOnlyBqAndWs = false
            type = thematicBreakTokenType()
            mode(inlineModeId())
            return
        }

        if (count == 1 && isSpaceTabEolOrEof(_input.LA(1))) {
            enterListMarker()
            return
        }

        markInlineParagraphStart()
        type = starRunTokenType()
        mode(inlineModeId())
    }

    protected fun handleLineStartUnderscore() {
        val start = _tokenStartCharIndex
        val count = _input.index() - start

        val tbEnd = findThematicBreakEnd('_'.code, count)
        if (tbEnd > start) {
            _input.seek(tbEnd)
            atDocStart = false
            currentLineIsBlank = false
            currentLineIsParagraph = false
            currentLineAllowsNextIndentedCode = true
            currentLineHasOnlyBqAndWs = false
            type = thematicBreakTokenType()
            mode(inlineModeId())
            return
        }

        markInlineParagraphStart()
        type = underscoreRunTokenType()
        mode(inlineModeId())
    }

    protected fun handleLineStartPlus() {
        val count = _input.index() - _tokenStartCharIndex

        // 1. TOML frontmatter at offset 0 (or directly after leading BOM)
        if (isAtDocumentOffsetZero() && count == 3 && isOnlyWsBeforeNewline() && hasFrontmatterCloseAhead(2)) {
            atDocStart = false
            currentLineIsBlank = false
            currentLineIsParagraph = false
            currentLineHasOnlyBqAndWs = false
            frontmatterType = 2
            type = frontmatterDelimTokenType()
            mode(frontmatterHeaderEndModeId())
            return
        }

        // 2. Unordered list marker '+'
        if (count == 1 && isSpaceTabEolOrEof(_input.LA(1))) {
            enterListMarker()
            return
        }

        markInlineParagraphStart()
        type = textTokenType()
        mode(inlineModeId())
    }

    protected fun handleLineStartEquals() {
        if (prevLineWasParagraph && !afterBlankLine && blockquoteDepth == prevLineBlockquoteDepth && isOnlyWsBeforeEolOrEof()) {
            atDocStart = false
            currentLineIsBlank = false
            currentLineIsParagraph = false
            currentLineAllowsNextIndentedCode = true
            currentLineHasOnlyBqAndWs = false
            type = setextUnderlineTokenType()
            mode(inlineModeId())
            return
        }
        markInlineParagraphStart()
        type = textTokenType()
        mode(inlineModeId())
    }

    protected fun handleLineStartDigits() {
        val count = _input.index() - _tokenStartCharIndex
        val la1 = _input.LA(1)
        if (count in 1..9 && (la1 == '.'.code || la1 == ')'.code) && isSpaceTabEolOrEof(_input.LA(2))) {
            _input.consume() // consume '.' or ')'
            enterListMarker()
            return
        }
        markInlineParagraphStart()
        type = textTokenType()
        mode(inlineModeId())
    }

    protected fun handleLineStartLbrack() {
        if (canMatchTaskCheckbox) {
            val ch = _input.LA(1)
            if ((ch == ' '.code || ch == 'x'.code || ch == 'X'.code) &&
                _input.LA(2) == ']'.code &&
                isSpaceTabEolOrEof(_input.LA(3))
            ) {
                _input.consume()
                _input.consume()
                markInlineParagraphStart()
                type = taskCheckboxTokenType()
                mode(inlineModeId())
                return
            }
        }
        if (!currentLineHasListMarker && isLinkRefDefStart(_tokenStartCharIndex)) {
            currentLineIsRefDef = true
        }
        markInlineParagraphStart()
        type = lbrackTokenType()
        mode(inlineModeId())
    }

    private fun enterListMarker() {
        atDocStart = false
        currentLineIsBlank = false
        currentLineIsParagraph = false
        currentLineAllowsNextIndentedCode = false
        currentLineHasOnlyBqAndWs = false
        currentLineHasListMarker = true
        lineStreamCol += (_input.index() - _tokenStartCharIndex)
        afterListMarker = true
        canMatchTaskCheckbox = false
        type = listMarkerTokenType()
    }

    protected fun onNonParagraphLineStart() {
        atDocStart = false
        currentLineIsBlank = false
        currentLineIsParagraph = false
        currentLineHasOnlyBqAndWs = false
        justSawBlockquoteMarker = false
        afterListMarker = false
        canMatchTaskCheckbox = false
    }

    protected fun onInlineNonWs() {
        markInlineParagraphStart()
    }

    private fun markInlineParagraphStart() {
        atDocStart = false
        currentLineIsBlank = false
        currentLineIsParagraph = !currentLineHasListMarker && !currentLineIsRefDef
        currentLineAllowsNextIndentedCode = false
        currentLineHasOnlyBqAndWs = false
        justSawBlockquoteMarker = false
        afterListMarker = false
        canMatchTaskCheckbox = false
    }

    private fun isLinkRefDefStart(lbrackIdx: Int): Boolean {
        val size = _input.size()
        var idx = lbrackIdx + 1
        var hasNonWs = false
        while (idx < size) {
            val ch = codePointAtIndex(idx)
            when (ch) {
                '\r'.code, '\n'.code, '['.code -> return false
                '\\'.code -> {
                    hasNonWs = true
                    idx += 2
                    continue
                }
                ']'.code -> {
                    if (!hasNonWs) return false
                    return idx + 1 < size &&
                        codePointAtIndex(idx + 1) == ':'.code &&
                        (idx + 2 >= size || isSpaceTabEolOrEof(codePointAtIndex(idx + 2)))
                }
                ' '.code, '\t'.code -> Unit
                else -> hasNonWs = true
            }
            idx++
        }
        return false
    }

    private fun isAtDocumentOffsetZero(): Boolean =
        _tokenStartCharIndex == 0 || (_tokenStartCharIndex == 1 && startsWithBom)

    private fun isSpaceTabEolOrEof(ch: Int): Boolean =
        ch == ' '.code || ch == '\t'.code || ch == '\r'.code || ch == '\n'.code || ch == IntStream.EOF

    private fun isOnlyWsBeforeNewline(): Boolean {
        var k = 1
        while (true) {
            val ch = _input.LA(k)
            when (ch) {
                ' '.code, '\t'.code -> k++
                '\r'.code, '\n'.code -> return true
                else -> return false
            }
        }
    }

    private fun isOnlyWsBeforeEolOrEof(): Boolean {
        var k = 1
        while (true) {
            val ch = _input.LA(k)
            when (ch) {
                ' '.code, '\t'.code -> k++
                '\r'.code, '\n'.code, IntStream.EOF -> return true
                else -> return false
            }
        }
    }

    private fun hasNoBacktickOnRestOfLine(): Boolean {
        var k = 1
        while (true) {
            val ch = _input.LA(k)
            when (ch) {
                '\r'.code, '\n'.code, IntStream.EOF -> return true
                '`'.code -> return false
                else -> k++
            }
        }
    }

    private fun findThematicBreakEnd(marker: Int, initialCount: Int): Int {
        val cachedFail = when (marker) {
            '-'.code -> tbFailedDashLinePos
            '*'.code -> tbFailedStarLinePos
            else -> tbFailedUnderscoreLinePos
        }
        if (cachedFail >= _tokenStartCharIndex) return -1

        var count = initialCount
        var idx = _input.index()
        val size = _input.size()
        while (idx < size) {
            val ch = codePointAtIndex(idx)
            when {
                ch == marker -> {
                    count++
                    idx++
                }
                ch == ' '.code || ch == '\t'.code -> idx++
                ch == '\r'.code || ch == '\n'.code -> break
                else -> {
                    cacheTbFail(marker, idx)
                    return -1
                }
            }
        }
        if (count >= 3) return idx
        cacheTbFail(marker, idx)
        return -1
    }

    private fun cacheTbFail(marker: Int, pos: Int) {
        when (marker) {
            '-'.code -> tbFailedDashLinePos = pos
            '*'.code -> tbFailedStarLinePos = pos
            else -> tbFailedUnderscoreLinePos = pos
        }
    }

    // -------------------------------------------------------------------------
    // Frontmatter (one-shot O(N) check at offset 0)
    // -------------------------------------------------------------------------

    private fun hasFrontmatterCloseAhead(fmType: Int): Boolean {
        val size = _input.size()
        var idx = _input.index()
        // Skip header trailing whitespace + newline
        while (idx < size) {
            val ch = codePointAtIndex(idx)
            if (ch == ' '.code || ch == '\t'.code) {
                idx++
            } else {
                break
            }
        }
        idx = skipNewlineAt(idx, size)
        while (idx < size) {
            if (isFrontmatterCloseAt(idx, fmType)) return true
            idx = advancePastLine(idx, size)
        }
        return false
    }

    private fun isFrontmatterCloseAt(lineStart: Int, fmType: Int): Boolean {
        val size = _input.size()
        if (lineStart + 3 > size) return false
        val c0 = codePointAtIndex(lineStart)
        val c1 = codePointAtIndex(lineStart + 1)
        val c2 = codePointAtIndex(lineStart + 2)
        val matches = if (fmType == 1) {
            (c0 == '-'.code && c1 == '-'.code && c2 == '-'.code) ||
                (c0 == '.'.code && c1 == '.'.code && c2 == '.'.code)
        } else {
            c0 == '+'.code && c1 == '+'.code && c2 == '+'.code
        }
        if (!matches) return false
        var idx = lineStart + 3
        while (idx < size) {
            val ch = codePointAtIndex(idx)
            if (ch == ' '.code || ch == '\t'.code) {
                idx++
            } else if (ch == '\r'.code || ch == '\n'.code) {
                return true
            } else {
                return false
            }
        }
        return true
    }

    protected fun onFrontmatterHeaderNewline() {
        if (isFrontmatterCloseAt(_input.index(), frontmatterType)) {
            mode(frontmatterCloseModeId())
        } else {
            mode(frontmatterBodyModeId())
        }
    }

    protected fun consumeFrontmatterBody() {
        val size = _input.size()
        var idx = advancePastLine(_tokenStartCharIndex, size)
        while (idx < size) {
            if (isFrontmatterCloseAt(idx, frontmatterType)) break
            idx = advancePastLine(idx, size)
        }
        _input.seek(idx)
        type = if (frontmatterType == 1) frontmatterYamlBodyTokenType() else frontmatterTomlBodyTokenType()
    }

    protected fun onFrontmatterCloseNewline() {
        frontmatterType = 0
        prevLineWasParagraph = false
        prevLineBlockquoteDepth = 0
        prevLineWasIndentedCode = false
        prevLineAllowsIndentedCode = true
        afterBlankLine = false
        resetLineState()
    }

    // -------------------------------------------------------------------------
    // Fenced code blocks
    // -------------------------------------------------------------------------

    protected fun onFenceInfoWord() {
        if (!fenceLangEmitted) {
            fenceLangEmitted = true
            type = fenceLangTokenType()
        } else {
            type = fenceInfoRestTokenType()
        }
    }

    protected fun onFenceInfoNewline() {
        fenceLangEmitted = false
        val nextPos = _input.index()
        if (nextPos >= _input.size()) {
            resetLineState()
            mode(defaultModeId())
        } else if (isClosingFenceAt(nextPos)) {
            mode(fenceCloseModeId())
        } else {
            mode(fenceBodyModeId())
        }
    }

    protected fun consumeFenceBody() {
        val size = _input.size()
        var idx = advancePastLine(_tokenStartCharIndex, size)
        while (idx < size) {
            if (isClosingFenceAt(idx)) break
            idx = advancePastLine(idx, size)
        }
        _input.seek(idx)
        type = fenceBodyTokenType()
    }

    private fun isClosingFenceAt(lineStart: Int): Boolean {
        val size = _input.size()
        var idx = lineStart
        var spaces = 0
        while (idx < size && spaces < 4 && codePointAtIndex(idx) == ' '.code) {
            spaces++
            idx++
        }
        if (spaces > 3) return false
        var count = 0
        while (idx < size && codePointAtIndex(idx) == fenceChar) {
            count++
            idx++
        }
        if (count < fenceLen) return false
        while (idx < size) {
            val ch = codePointAtIndex(idx)
            if (ch == ' '.code || ch == '\t'.code) {
                idx++
            } else if (ch == '\r'.code || ch == '\n'.code) {
                return true
            } else {
                return false
            }
        }
        return true
    }

    protected fun onBqFenceInfoNewline() {
        fenceLangEmitted = false
        val nextPos = _input.index()
        if (nextPos >= _input.size() || !hasBlockquotePrefixAhead(nextPos, fenceBlockquoteDepth)) {
            fenceBlockquoteDepth = 0
            prevLineWasParagraph = false
            prevLineBlockquoteDepth = 0
            prevLineWasIndentedCode = false
            prevLineAllowsIndentedCode = true
            afterBlankLine = false
            resetLineState()
            mode(defaultModeId())
        } else {
            blockquoteDepth = 0
            bqFenceSpacesAfterLastMarker = 0
            bqFenceJustSawMarker = false
            mode(bqFenceBodyModeId())
        }
    }

    protected fun onBqFenceWs() {
        val start = _tokenStartCharIndex
        val end = _input.index()
        var streamCol = if (bqFenceJustSawMarker) 1 else bqFenceSpacesAfterLastMarker
        var spaces = 0
        var first = bqFenceJustSawMarker
        for (cp in start until end) {
            val ch = codePointAtIndex(cp)
            if (ch == '\t'.code) {
                val step = 4 - (streamCol % 4)
                streamCol += step
                spaces += if (first) step - 1 else step
                first = false
            } else {
                streamCol++
                if (first) {
                    first = false
                } else {
                    spaces++
                }
            }
        }
        bqFenceJustSawMarker = false
        bqFenceSpacesAfterLastMarker += spaces
    }

    protected fun handleBqFenceMarker() {
        if (blockquoteDepth >= fenceBlockquoteDepth) {
            consumeBqFenceRestOfLine()
            return
        }
        blockquoteDepth++
        bqFenceSpacesAfterLastMarker = 0
        bqFenceJustSawMarker = true
        type = blockquoteMarkerTokenType()
    }

    protected fun consumeBqFenceRestOfLine() {
        val size = _input.size()
        var idx = _tokenStartCharIndex
        while (idx < size) {
            val ch = codePointAtIndex(idx)
            if (ch == '\r'.code || ch == '\n'.code) break
            idx++
        }
        _input.seek(idx)
        type = fenceBodyLineTokenType()
    }

    protected fun handleBqFencePotentialClose() {
        val leadCh = codePointAtIndex(_tokenStartCharIndex)
        if (blockquoteDepth == fenceBlockquoteDepth &&
            bqFenceSpacesAfterLastMarker <= 3 &&
            leadCh == fenceChar &&
            isOnlyClosingFenceRest(_tokenStartCharIndex)
        ) {
            type = fenceCloseTokenType()
            fenceBlockquoteDepth = 0
        } else {
            consumeBqFenceRestOfLine()
        }
    }

    private fun isOnlyClosingFenceRest(start: Int): Boolean {
        val size = _input.size()
        var idx = start
        var count = 0
        while (idx < size && codePointAtIndex(idx) == fenceChar) {
            count++
            idx++
        }
        if (count < fenceLen) return false
        while (idx < size) {
            val ch = codePointAtIndex(idx)
            if (ch == ' '.code || ch == '\t'.code) {
                idx++
            } else if (ch == '\r'.code || ch == '\n'.code) {
                return true
            } else {
                return false
            }
        }
        return true
    }

    protected fun onBqFenceBodyNewline() {
        if (fenceBlockquoteDepth == 0) {
            prevLineWasParagraph = false
            prevLineBlockquoteDepth = 0
            prevLineWasIndentedCode = false
            prevLineAllowsIndentedCode = true
            afterBlankLine = false
            resetLineState()
            mode(defaultModeId())
            return
        }
        val nextPos = _input.index()
        if (nextPos >= _input.size() || !hasBlockquotePrefixAhead(nextPos, fenceBlockquoteDepth)) {
            fenceBlockquoteDepth = 0
            prevLineWasParagraph = false
            prevLineBlockquoteDepth = 0
            prevLineWasIndentedCode = false
            prevLineAllowsIndentedCode = true
            afterBlankLine = false
            resetLineState()
            mode(defaultModeId())
        } else {
            blockquoteDepth = 0
            bqFenceSpacesAfterLastMarker = 0
            bqFenceJustSawMarker = false
        }
    }

    private fun hasBlockquotePrefixAhead(lineStart: Int, requiredDepth: Int): Boolean {
        val size = _input.size()
        var idx = lineStart
        for (d in 0 until requiredDepth) {
            var spaces = 0
            while (idx < size && spaces < 4 && codePointAtIndex(idx) == ' '.code) {
                spaces++
                idx++
            }
            if (spaces > 3 || idx >= size || codePointAtIndex(idx) != '>'.code) return false
            idx++
            if (idx < size && codePointAtIndex(idx) == ' '.code) idx++
        }
        return true
    }

    private fun skipBqMarkerOptionalSpace(idx: Int): Int {
        val size = _input.size()
        return if (idx < size && codePointAtIndex(idx) == ' '.code) idx + 1 else idx
    }

    // -------------------------------------------------------------------------
    // Strictly O(N) inline code spans (precomputed backtick run index)
    // -------------------------------------------------------------------------

    protected fun handleInlineBackticksToken() {
        val start = _tokenStartCharIndex
        val count = _input.index() - start
        handleInlineBackticks(start, count)
    }

    private fun handleInlineBackticks(start: Int, count: Int) {
        ensureBacktickIndex()
        val runs = backtickRunsByLength?.get(count)
        if (runs == null || runs.size < 2) {
            type = textTokenType()
            return
        }
        val cursors = backtickCursorByLength!!
        var cursor = cursors[count] ?: 0
        while (cursor < runs.size && runs[cursor] <= start) {
            cursor++
        }
        cursors[count] = cursor
        if (cursor >= runs.size) {
            type = textTokenType()
            return
        }
        val closeStart = runs[cursor]
        val paraIds = backtickRunParaIds!!
        if (paraIds[start] != paraIds[closeStart]) {
            type = textTokenType()
            return
        }
        _input.seek(closeStart + count)
        type = inlineCodeTokenType()
    }

    private fun ensureBacktickIndex() {
        if (backtickIndexBuilt) return
        backtickIndexBuilt = true

        val size = _input.size()
        val paraIds = HashMap<Int, Int>()
        val byLen = HashMap<Int, ArrayList<Int>>()
        val cursors = HashMap<Int, Int>()

        var paraId = 0
        var i = 0
        if (i < size && codePointAtIndex(0) == 0xFEFF) {
            i++
        }

        // Skip YAML/TOML frontmatter at offset 0 (or directly after BOM) without indexing backticks inside it.
        if (i + 3 <= size) {
            val c0 = codePointAtIndex(i)
            val c1 = codePointAtIndex(i + 1)
            val c2 = codePointAtIndex(i + 2)
            val fmType = when {
                c0 == '-'.code && c1 == '-'.code && c2 == '-'.code -> 1
                c0 == '+'.code && c1 == '+'.code && c2 == '+'.code -> 2
                else -> 0
            }
            if (fmType != 0 && isOnlyWsAfterUntilNewline(i + 3, size)) {
                val closeEnd = findFrontmatterEndOffset(i + 3, size, fmType)
                if (closeEnd > 0) {
                    i = closeEnd
                    paraId++
                }
            }
        }

        var prevBqDepth = 0
        var prevLineWasBlank = true
        var prevLineWasPara = false
        var prevLineAllowsIndent = true

        while (i < size) {
            // 1. Parse blockquote prefix ('>' markers with <= 3 spaces before each and optional 1 space)
            var bqDepth = 0
            var consumedSpaceAfterLastBq = false
            var pos = i
            while (pos < size) {
                var spaces = 0
                var p = pos
                while (p < size && spaces < 4 && codePointAtIndex(p) == ' '.code) {
                    spaces++
                    p++
                }
                if (spaces <= 3 && p < size && codePointAtIndex(p) == '>'.code) {
                    bqDepth++
                    p++
                    if (p < size && codePointAtIndex(p) == ' '.code) {
                        p++
                        consumedSpaceAfterLastBq = true
                    } else {
                        consumedSpaceAfterLastBq = false
                    }
                    pos = p
                } else {
                    break
                }
            }

            // 2. Count leading indentation after blockquote prefix
            var col = 0
            var streamCol = if (consumedSpaceAfterLastBq) 2 else if (bqDepth > 0) 1 else 0
            var firstAfterBq = bqDepth > 0 && !consumedSpaceAfterLastBq
            while (pos < size) {
                val ch = codePointAtIndex(pos)
                if (ch == ' '.code) {
                    streamCol++
                    if (firstAfterBq) firstAfterBq = false else col++
                    pos++
                } else if (ch == '\t'.code) {
                    val step = 4 - (streamCol % 4)
                    streamCol += step
                    col += if (firstAfterBq) step - 1 else step
                    firstAfterBq = false
                    pos++
                } else {
                    break
                }
            }

            // 3. Blank line (including blank blockquote line '>')
            if (pos >= size || codePointAtIndex(pos) == '\r'.code || codePointAtIndex(pos) == '\n'.code) {
                paraId++
                i = skipNewlineAt(pos, size)
                prevBqDepth = bqDepth
                prevLineWasBlank = true
                prevLineWasPara = false
                prevLineAllowsIndent = true
                continue
            }

            // Blockquote depth transition breaks inline code spans across lines
            if (bqDepth != prevBqDepth) {
                paraId++
            }

            // 4. Indented code block (>= 4 columns when allowed)
            if (prevLineAllowsIndent && col >= 4) {
                paraId++
                i = advancePastLine(pos, size)
                paraId++
                prevBqDepth = bqDepth
                prevLineWasBlank = false
                prevLineWasPara = false
                prevLineAllowsIndent = true
                continue
            }

            // 5. Block-level starters at indent <= 3 (or after a list marker, matching DEFAULT_MODE)
            var tbFailDash = -1
            var tbFailStar = -1
            var tbFailUnder = -1
            var sawListMarker = false
            val canSetextBeforeList = prevLineWasPara && !prevLineWasBlank && bqDepth == prevBqDepth
            while ((col <= 3 || sawListMarker) && pos < size) {
                val fch = codePointAtIndex(pos)
                if (!sawListMarker && canSetextBeforeList &&
                    (fch == '-'.code || fch == '='.code) &&
                    isContiguousSameCharThenWsToEol(pos, size, fch)
                ) {
                    break
                }
                if (fch == '-'.code || fch == '*'.code || fch == '_'.code) {
                    val failAt = when (fch) {
                        '-'.code -> tbFailDash
                        '*'.code -> tbFailStar
                        else -> tbFailUnder
                    }
                    if (failAt < pos) {
                        val failOut = isThematicOrSetextLineMemo(pos, size, fch)
                        if (failOut < 0) {
                            break
                        } else {
                            when (fch) {
                                '-'.code -> tbFailDash = failOut
                                '*'.code -> tbFailStar = failOut
                                else -> tbFailUnder = failOut
                            }
                        }
                    }
                }
                var markerEnd = -1
                if ((fch == '-'.code || fch == '*'.code || fch == '+'.code) &&
                    isSpaceTabEolOrEof(if (pos + 1 < size) codePointAtIndex(pos + 1) else IntStream.EOF)
                ) {
                    markerEnd = pos + 1
                } else if (fch in '0'.code..'9'.code) {
                    var digits = 0
                    var dp = pos
                    while (dp < size && digits < 10 && codePointAtIndex(dp) in '0'.code..'9'.code) {
                        digits++
                        dp++
                    }
                    if (digits in 1..9 && dp < size) {
                        val sep = codePointAtIndex(dp)
                        val afterSep = if (dp + 1 < size) codePointAtIndex(dp + 1) else IntStream.EOF
                        if ((sep == '.'.code || sep == ')'.code) && isSpaceTabEolOrEof(afterSep)) {
                            markerEnd = dp + 1
                        }
                    }
                }
                if (markerEnd < 0) break
                sawListMarker = true
                paraId++
                streamCol += (markerEnd - pos)
                pos = markerEnd
                var postCol = 0
                while (pos < size) {
                    val wch = codePointAtIndex(pos)
                    if (wch == ' '.code) {
                        postCol++
                        streamCol++
                        pos++
                    } else if (wch == '\t'.code) {
                        val step = 4 - (streamCol % 4)
                        postCol += step
                        streamCol += step
                        pos++
                    } else {
                        break
                    }
                }
                col = postCol
                if (pos >= size || codePointAtIndex(pos) == '\r'.code || codePointAtIndex(pos) == '\n'.code) {
                    break
                }
            }

            if (pos >= size || codePointAtIndex(pos) == '\r'.code || codePointAtIndex(pos) == '\n'.code) {
                paraId++
                i = skipNewlineAt(pos, size)
                prevBqDepth = bqDepth
                prevLineWasBlank = false
                prevLineWasPara = false
                prevLineAllowsIndent = false
                continue
            }

            val firstCh = codePointAtIndex(pos)
            if (col <= 3 || sawListMarker) {
                // Fenced code block opener (``` or ~~~)
                if (firstCh == '`'.code || firstCh == '~'.code) {
                    val fenceCount = countSameChar(pos, size, firstCh)
                    if (fenceCount >= 3 && (firstCh == '~'.code || hasNoBacktickUntilEol(pos + fenceCount, size))) {
                        paraId++
                        i = skipFencedBlockForBacktickIndex(pos + fenceCount, size, firstCh, fenceCount, bqDepth)
                        paraId++
                        prevBqDepth = bqDepth
                        prevLineWasBlank = false
                        prevLineWasPara = false
                        prevLineAllowsIndent = true
                        continue
                    }
                }

                // ATX heading (#..###### followed by space/tab/EOL/EOF)
                if (firstCh == '#'.code) {
                    val hashes = countSameChar(pos, size, '#'.code)
                    val after = if (pos + hashes < size) codePointAtIndex(pos + hashes) else IntStream.EOF
                    if (hashes in 1..6 && isSpaceTabEolOrEof(after)) {
                        paraId++
                        i = indexBackticksOnLine(pos + hashes, size, paraId, paraIds, byLen)
                        paraId++
                        prevBqDepth = bqDepth
                        prevLineWasBlank = false
                        prevLineWasPara = false
                        prevLineAllowsIndent = true
                        continue
                    }
                }

                // Setext underline (=+ or -+ with optional trailing ws only, directly under a paragraph in same container)
                val canSetext = !sawListMarker && canSetextBeforeList
                if (canSetext &&
                    (firstCh == '-'.code || firstCh == '='.code) &&
                    isContiguousSameCharThenWsToEol(pos, size, firstCh)
                ) {
                    paraId++
                    i = advancePastLine(pos, size)
                    paraId++
                    prevBqDepth = bqDepth
                    prevLineWasBlank = false
                    prevLineWasPara = false
                    prevLineAllowsIndent = true
                    continue
                }

                // Thematic break (---, ***, ___)
                if ((firstCh == '-'.code || firstCh == '*'.code || firstCh == '_'.code) &&
                    isThematicOrSetextLine(pos, size, firstCh)
                ) {
                    paraId++
                    i = advancePastLine(pos, size)
                    paraId++
                    prevBqDepth = bqDepth
                    prevLineWasBlank = false
                    prevLineWasPara = false
                    prevLineAllowsIndent = true
                    continue
                }

                // CommonMark HTML blocks (types 1-7)
                if (firstCh == '<'.code) {
                    if (matchesLiteralCaseSensitive(pos, "<!--", size)) {
                        paraId++
                        val (closedAt, stopPos) = findType2HtmlCommentEnd(pos + 4, size, bqDepth)
                        if (closedAt > 0) {
                            i = indexBackticksOnLine(closedAt, size, paraId, paraIds, byLen)
                            paraId++
                            prevBqDepth = bqDepth
                            prevLineWasBlank = false
                            prevLineWasPara = false
                            prevLineAllowsIndent = isOnlyWsBeforeEolOrEofAt(closedAt, size)
                        } else {
                            i = stopPos
                            paraId++
                            prevBqDepth = bqDepth
                            prevLineWasBlank = false
                            prevLineWasPara = false
                            prevLineAllowsIndent = true
                        }
                        continue
                    }
                    val allowType7 = !canSetextBeforeList
                    val htmlEnd = skipHtmlBlockForBacktickIndex(pos, size, bqDepth, allowType7)
                    if (htmlEnd > pos) {
                        paraId++
                        i = htmlEnd
                        paraId++
                        prevBqDepth = bqDepth
                        prevLineWasBlank = false
                        prevLineWasPara = false
                        prevLineAllowsIndent = true
                        continue
                    }
                }
            }

            // 6. Index backtick runs across the rest of this line
            val isRefDef = !sawListMarker && firstCh == '['.code && isLinkRefDefStart(pos)
            i = indexBackticksOnLine(pos, size, paraId, paraIds, byLen)
            prevBqDepth = bqDepth
            prevLineWasBlank = false
            prevLineWasPara = !sawListMarker && !isRefDef
            prevLineAllowsIndent = false
        }

        backtickRunParaIds = paraIds
        backtickRunsByLength = byLen
        backtickCursorByLength = cursors
    }

    private fun indexBackticksOnLine(
        startPos: Int,
        size: Int,
        paraId: Int,
        paraIds: HashMap<Int, Int>,
        byLen: HashMap<Int, ArrayList<Int>>,
    ): Int {
        var i = startPos
        while (i < size) {
            val ch = codePointAtIndex(i)
            when (ch) {
                '\r'.code, '\n'.code -> return skipNewlineAt(i, size)
                '`'.code -> {
                    val runStart = i
                    while (i < size && codePointAtIndex(i) == '`'.code) {
                        i++
                    }
                    paraIds[runStart] = paraId
                    byLen.getOrPut(i - runStart) { ArrayList() }.add(runStart)
                }
                else -> i++
            }
        }
        return size
    }

    private fun isOnlyWsAfterUntilNewline(start: Int, size: Int): Boolean {
        var idx = start
        while (idx < size) {
            val ch = codePointAtIndex(idx)
            when (ch) {
                ' '.code, '\t'.code -> idx++
                '\r'.code, '\n'.code -> return true
                else -> return false
            }
        }
        return false
    }

    private fun findFrontmatterEndOffset(afterOpenDelim: Int, size: Int, fmType: Int): Int {
        var idx = advancePastLine(afterOpenDelim, size)
        while (idx < size) {
            if (isFrontmatterCloseAt(idx, fmType)) {
                return advancePastLine(idx, size)
            }
            idx = advancePastLine(idx, size)
        }
        return -1
    }

    private fun skipFencedBlockForBacktickIndex(
        afterOpenerChars: Int,
        size: Int,
        fChar: Int,
        fLen: Int,
        bqDepth: Int,
    ): Int {
        var idx = advancePastLine(afterOpenerChars, size)
        while (idx < size) {
            var pos = idx
            var consumedMarkerSpace = false
            if (bqDepth > 0) {
                if (!hasBlockquotePrefixAhead(pos, bqDepth)) {
                    return idx
                }
                for (d in 0 until bqDepth) {
                    while (pos < size && codePointAtIndex(pos) == ' '.code) pos++
                    pos++ // '>'
                    if (pos < size && codePointAtIndex(pos) == ' '.code) {
                        consumedMarkerSpace = true
                        pos++
                    } else {
                        consumedMarkerSpace = false
                    }
                }
            }
            var spaces = 0
            var streamCol = if (consumedMarkerSpace) 2 else if (bqDepth > 0) 1 else 0
            var firstAfterMarker = bqDepth > 0 && !consumedMarkerSpace
            while (pos < size && spaces < 4) {
                val ch = codePointAtIndex(pos)
                if (ch == ' '.code) {
                    spaces++
                    streamCol++
                    firstAfterMarker = false
                    pos++
                } else if (ch == '\t'.code) {
                    val step = 4 - (streamCol % 4)
                    spaces += if (firstAfterMarker) step - 1 else step
                    streamCol += step
                    firstAfterMarker = false
                    pos++
                } else {
                    break
                }
            }
            if (spaces <= 3 && pos < size && codePointAtIndex(pos) == fChar) {
                val closeCount = countSameChar(pos, size, fChar)
                if (closeCount >= fLen && isOnlyWsBeforeEolOrEofAt(pos + closeCount, size)) {
                    return advancePastLine(pos + closeCount, size)
                }
            }
            idx = advancePastLine(idx, size)
        }
        return size
    }

    private fun isOnlyWsBeforeEolOrEofAt(start: Int, size: Int): Boolean {
        var idx = start
        while (idx < size) {
            val ch = codePointAtIndex(idx)
            when (ch) {
                ' '.code, '\t'.code -> idx++
                '\r'.code, '\n'.code -> return true
                else -> return false
            }
        }
        return true
    }

    private fun isThematicOrSetextLine(start: Int, size: Int, marker: Int): Boolean =
        isThematicOrSetextLineMemo(start, size, marker) < 0

    // Returns -1 if [start..EOL) is a valid thematic/Setext line, or the offending/EOL index on failure.
    private fun isThematicOrSetextLineMemo(start: Int, size: Int, marker: Int): Int {
        var count = 0
        var idx = start
        while (idx < size) {
            val ch = codePointAtIndex(idx)
            when (ch) {
                marker -> {
                    count++
                    idx++
                }
                ' '.code, '\t'.code -> idx++
                '\r'.code, '\n'.code -> return if (count >= 3) -1 else idx
                else -> return idx
            }
        }
        return if (count >= 3) -1 else size
    }

    private fun isContiguousSameCharThenWsToEol(start: Int, size: Int, ch: Int): Boolean {
        var idx = start
        if (idx >= size || codePointAtIndex(idx) != ch) return false
        while (idx < size && codePointAtIndex(idx) == ch) {
            idx++
        }
        return isOnlyWsBeforeEolOrEofAt(idx, size)
    }

    // -------------------------------------------------------------------------
    // HTML comments, HTML blocks (CommonMark §4.6 types 1-7), autolinks, and inline tags
    // -------------------------------------------------------------------------

    private fun findType2HtmlCommentEnd(scanStart: Int, size: Int, bqDepth: Int): Pair<Int, Int> {
        var idx = scanStart
        var firstLine = true
        while (idx < size) {
            if (!firstLine && bqDepth > 0) {
                if (!hasBlockquotePrefixAhead(idx, bqDepth)) {
                    return -1 to idx
                }
                idx = skipBlockquotePrefix(idx, bqDepth, size)
            }
            firstLine = false
            while (idx < size) {
                val ch = codePointAtIndex(idx)
                if (ch == '\r'.code || ch == '\n'.code) {
                    idx = skipNewlineAt(idx, size)
                    break
                }
                if (ch == '-'.code &&
                    idx + 2 < size &&
                    codePointAtIndex(idx + 1) == '-'.code &&
                    codePointAtIndex(idx + 2) == '>'.code
                ) {
                    return (idx + 3) to -1
                }
                idx++
            }
        }
        return -1 to size
    }

    protected fun handleLineStartHtmlComment() {
        val size = _input.size()
        val (closedAt, stopPos) = findType2HtmlCommentEnd(_input.index(), size, blockquoteDepth)
        if (closedAt > 0) {
            _input.seek(closedAt)
            onNonParagraphLineStart()
            currentLineAllowsNextIndentedCode = true
            type = htmlBlockCommentTokenType()
            mode(inlineModeId())
            return
        }
        // Unclosed Type 2 HTML comment block runs to blockquote exit or EOF per CommonMark §4.6.
        _input.seek(stopPos)
        onNonParagraphLineStart()
        currentLineAllowsNextIndentedCode = true
        type = htmlBlockCommentTokenType()
        if (stopPos < size) {
            prevLineWasParagraph = false
            prevLineBlockquoteDepth = 0
            prevLineWasIndentedCode = false
            prevLineAllowsIndentedCode = true
            afterBlankLine = false
            resetLineState()
            mode(defaultModeId())
        } else {
            mode(inlineModeId())
        }
    }

    protected fun consumeInlineHtmlComment() {
        val start = _tokenStartCharIndex
        val fallbackType = if (_mode == htmlBlockModeId()) {
            onHtmlBlockNonWs()
            htmlBlockTextTokenType()
        } else {
            textTokenType()
        }
        val size = _input.size()
        val firstAfterOpen = _input.index()
        // CommonMark §6.8 allows abrupt HTML comments '<!-->' and '<!--->'
        if (firstAfterOpen < size && codePointAtIndex(firstAfterOpen) == '>'.code) {
            _input.seek(firstAfterOpen + 1)
            type = htmlCommentTokenType()
            return
        }
        if (firstAfterOpen + 1 < size &&
            codePointAtIndex(firstAfterOpen) == '-'.code &&
            codePointAtIndex(firstAfterOpen + 1) == '>'.code
        ) {
            _input.seek(firstAfterOpen + 2)
            type = htmlCommentTokenType()
            return
        }
        if (start < htmlCommentFailEnd) {
            type = fallbackType
            return
        }
        var idx = firstAfterOpen
        while (idx < size) {
            val ch = codePointAtIndex(idx)
            if (ch == '-'.code &&
                idx + 2 < size &&
                codePointAtIndex(idx + 1) == '-'.code &&
                codePointAtIndex(idx + 2) == '>'.code
            ) {
                _input.seek(idx + 3)
                type = htmlCommentTokenType()
                return
            }
            if (ch == '\r'.code || ch == '\n'.code) {
                val afterNl = skipNewlineAt(idx, size)
                if (isBlankLineAt(afterNl, size)) {
                    htmlCommentFailEnd = afterNl
                    type = fallbackType
                    return
                }
                idx = afterNl
                continue
            }
            idx++
        }
        htmlCommentFailEnd = size
        type = fallbackType
    }

    protected fun handleInlineLt() {
        val start = _tokenStartCharIndex
        val autolinkEnd = findAutolinkEnd(start)
        if (autolinkEnd > start) {
            _input.seek(autolinkEnd)
            type = autolinkTokenType()
            return
        }
        if (hasValidHtmlTagClose(start)) {
            htmlExpectingTagName = true
            type = htmlTagOpenTokenType()
            pushMode(htmlTagModeId())
            return
        }
        type = textTokenType()
    }

    protected fun handleLineStartLt() {
        val start = _tokenStartCharIndex
        val size = _input.size()

        // CommonMark §4.6 HTML block Type 1: <pre, <script, <style, <textarea
        val type1Tag = matchHtmlBlockType1TagName(start, size)
        if (type1Tag != null) {
            atDocStart = false
            currentLineIsBlank = false
            currentLineIsParagraph = false
            currentLineAllowsNextIndentedCode = true
            currentLineHasOnlyBqAndWs = false
            afterListMarker = false
            canMatchTaskCheckbox = false
            htmlBlockType = 1
            htmlRawCloseTag = "</$type1Tag"
            htmlBlockClosed = false
            htmlBlockStartPos = start
            htmlBlockBqDepth = blockquoteDepth
            hbrAtLineStart = false
            hbrBqConsumed = blockquoteDepth
            if (hasValidHtmlTagClose(start)) {
                htmlExpectingTagName = true
                type = htmlBlockTagOpenTokenType()
                mode(htmlBlockRawModeId())
                pushMode(htmlTagModeId())
            } else {
                type = htmlBlockTextTokenType()
                mode(htmlBlockRawModeId())
            }
            return
        }

        // CommonMark §4.6 HTML block Type 3: <?
        if (start + 1 < size && codePointAtIndex(start + 1) == '?'.code) {
            _input.seek(start + 2)
            enterRawHtmlBlock(blockType = 3, startPos = start)
            return
        }

        // CommonMark §4.6 HTML block Type 5: <![CDATA[
        if (matchesLiteralCaseSensitive(start, "<![CDATA[", size)) {
            _input.seek(start + 9)
            enterRawHtmlBlock(blockType = 5, startPos = start)
            return
        }

        // CommonMark §4.6 HTML block Type 4: <! + ASCII letter
        if (start + 2 < size &&
            codePointAtIndex(start + 1) == '!'.code &&
            isAsciiLetter(codePointAtIndex(start + 2))
        ) {
            enterRawHtmlBlock(blockType = 4, startPos = start)
            return
        }

        // CommonMark §4.6 HTML block Type 6 (known block tag, can interrupt paragraph)
        if (matchHtmlBlockType6(start, size)) {
            enterStructuredHtmlBlock(blockType = 6, ltStart = start)
            return
        }

        // CommonMark §4.6 HTML block Type 7 (complete open/close tag + ws to EOL, cannot interrupt paragraph)
        val canType7 = !(prevLineWasParagraph && !afterBlankLine && blockquoteDepth == prevLineBlockquoteDepth)
        if (canType7) {
            val tagEnd = findValidHtmlTagCloseEndSameLine(start, size)
            if (tagEnd > start && isOnlyWsBeforeEolOrEofAt(tagEnd, size)) {
                enterStructuredHtmlBlock(blockType = 7, ltStart = start)
                return
            }
        }

        // Otherwise: inline autolink or inline HTML tag or plain '<'
        markInlineParagraphStart()
        val autolinkEnd = findAutolinkEnd(start)
        if (autolinkEnd > start) {
            _input.seek(autolinkEnd)
            type = autolinkTokenType()
            mode(inlineModeId())
            return
        }
        if (hasValidHtmlTagClose(start)) {
            htmlExpectingTagName = true
            type = htmlTagOpenTokenType()
            mode(inlineModeId())
            pushMode(htmlTagModeId())
            return
        }
        type = textTokenType()
        mode(inlineModeId())
    }

    private fun enterRawHtmlBlock(blockType: Int, startPos: Int) {
        atDocStart = false
        currentLineIsBlank = false
        currentLineIsParagraph = false
        currentLineAllowsNextIndentedCode = true
        currentLineHasOnlyBqAndWs = false
        afterListMarker = false
        canMatchTaskCheckbox = false
        htmlBlockType = blockType
        htmlRawCloseTag = ""
        htmlBlockClosed = false
        htmlBlockStartPos = startPos
        htmlBlockBqDepth = blockquoteDepth
        hbrAtLineStart = false
        hbrBqConsumed = blockquoteDepth
        type = htmlBlockTextTokenType()
        mode(htmlBlockRawModeId())
    }

    private fun enterStructuredHtmlBlock(blockType: Int, ltStart: Int) {
        atDocStart = false
        currentLineIsBlank = false
        currentLineIsParagraph = false
        currentLineAllowsNextIndentedCode = true
        currentLineHasOnlyBqAndWs = false
        afterListMarker = false
        canMatchTaskCheckbox = false
        htmlBlockType = blockType
        htmlBlockClosed = false
        htmlBlockBqDepth = blockquoteDepth
        hbAtLineStart = false
        hbBqConsumed = blockquoteDepth
        if (hasValidHtmlTagClose(ltStart)) {
            htmlExpectingTagName = true
            type = htmlBlockTagOpenTokenType()
            mode(htmlBlockModeId())
            pushMode(htmlTagModeId())
        } else {
            type = htmlBlockTextTokenType()
            mode(htmlBlockModeId())
        }
    }

    protected fun onHtmlBlockNonWs() {
        hbAtLineStart = false
    }

    protected fun handleHtmlBlockGt() {
        if (hbAtLineStart && htmlBlockBqDepth > 0 && hbBqConsumed < htmlBlockBqDepth) {
            hbBqConsumed++
            type = blockquoteMarkerTokenType()
        } else {
            hbAtLineStart = false
            type = htmlBlockTextTokenType()
        }
    }

    protected fun handleHtmlBlockLt() {
        hbAtLineStart = false
        val start = _tokenStartCharIndex
        if (hasValidHtmlTagClose(start)) {
            htmlExpectingTagName = true
            type = htmlBlockTagOpenTokenType()
            pushMode(htmlTagModeId())
        } else {
            type = htmlBlockTextTokenType()
        }
    }

    protected fun onHtmlBlockNewline() {
        val size = _input.size()
        val nextPos = _input.index()
        val shouldExit = htmlBlockClosed ||
            nextPos >= size ||
            (htmlBlockBqDepth == 0 && isBlankLineAt(nextPos, size)) ||
            (htmlBlockBqDepth > 0 && (!hasBlockquotePrefixAhead(nextPos, htmlBlockBqDepth) || isBlankBqLineAt(nextPos, htmlBlockBqDepth, size)))
        if (shouldExit) {
            htmlBlockType = 0
            htmlBlockClosed = false
            htmlBlockBqDepth = 0
            hbAtLineStart = false
            hbBqConsumed = 0
            prevLineWasParagraph = false
            prevLineBlockquoteDepth = 0
            prevLineWasIndentedCode = false
            prevLineAllowsIndentedCode = true
            afterBlankLine = false
            resetLineState()
            mode(defaultModeId())
        } else {
            hbAtLineStart = true
            hbBqConsumed = 0
        }
    }

    protected fun handleHtmlBlockRawGt() {
        if (hbrAtLineStart && htmlBlockBqDepth > 0 && hbrBqConsumed < htmlBlockBqDepth) {
            hbrBqConsumed++
            type = blockquoteMarkerTokenType()
            return
        }
        hbrAtLineStart = false
        val pos = _tokenStartCharIndex
        when (htmlBlockType) {
            3 -> if (pos - 1 >= htmlBlockStartPos + 2 && codePointAtIndex(pos - 1) == '?'.code) {
                htmlBlockClosed = true
            }
            4 -> htmlBlockClosed = true
            5 -> if (pos - 2 >= htmlBlockStartPos + 9 &&
                codePointAtIndex(pos - 2) == ']'.code &&
                codePointAtIndex(pos - 1) == ']'.code
            ) {
                htmlBlockClosed = true
            }
        }
        type = htmlBlockTextTokenType()
    }

    protected fun handleHtmlBlockRawLt() {
        hbrAtLineStart = false
        val start = _tokenStartCharIndex
        if (htmlBlockType == 1 && matchesRawCloseTagAt(start)) {
            htmlBlockClosed = true
            if (hasValidHtmlTagClose(start)) {
                htmlExpectingTagName = true
                type = htmlBlockTagOpenTokenType()
                pushMode(htmlTagModeId())
                return
            }
        }
        type = htmlBlockTextTokenType()
    }

    protected fun onHtmlBlockRawText() {
        hbrAtLineStart = false
    }

    protected fun onHtmlBlockRawFallback() {
        hbrAtLineStart = false
    }

    protected fun onHtmlBlockRawNewline() {
        val size = _input.size()
        val nextPos = _input.index()
        val shouldExit = htmlBlockClosed ||
            nextPos >= size ||
            (htmlBlockBqDepth > 0 && !hasBlockquotePrefixAhead(nextPos, htmlBlockBqDepth))
        if (shouldExit) {
            htmlBlockType = 0
            htmlBlockClosed = false
            htmlRawCloseTag = ""
            htmlBlockStartPos = -1
            htmlBlockBqDepth = 0
            hbrAtLineStart = false
            hbrBqConsumed = 0
            prevLineWasParagraph = false
            prevLineBlockquoteDepth = 0
            prevLineWasIndentedCode = false
            prevLineAllowsIndentedCode = true
            afterBlankLine = false
            resetLineState()
            mode(defaultModeId())
        } else {
            hbrAtLineStart = true
            hbrBqConsumed = 0
        }
    }

    private fun findAutolinkEnd(ltIndex: Int): Int {
        val size = _input.size()
        var idx = ltIndex + 1
        if (idx >= size) return -1

        // Check URI scheme: [A-Za-z][A-Za-z0-9+.-]{1,31}:
        val first = codePointAtIndex(idx)
        if (!isAsciiLetter(first)) return -1

        var hasColon = false
        var hasAt = false
        var schemeLen = 0
        var scanningScheme = true
        while (idx < size) {
            val ch = codePointAtIndex(idx)
            when {
                ch == '>'.code -> {
                    val bodyLen = idx - (ltIndex + 1)
                    if (bodyLen < 3) return -1
                    return if (hasColon || hasAt) idx + 1 else -1
                }
                ch == '<'.code || ch == ' '.code || ch == '\t'.code || ch == '\r'.code || ch == '\n'.code -> return -1
                ch == ':'.code -> {
                    if (scanningScheme && schemeLen in 2..32) {
                        hasColon = true
                    }
                    scanningScheme = false
                    idx++
                }
                ch == '@'.code -> {
                    scanningScheme = false
                    hasAt = true
                    idx++
                }
                else -> {
                    if (scanningScheme) {
                        if (isAsciiLetter(ch) || ch in '0'.code..'9'.code || ch == '+'.code || ch == '.'.code || ch == '-'.code) {
                            schemeLen++
                            if (schemeLen > 32) scanningScheme = false
                        } else {
                            scanningScheme = false
                        }
                    }
                    idx++
                }
            }
        }
        return -1
    }

    private fun hasValidHtmlTagClose(ltIndex: Int): Boolean {
        val size = _input.size()
        var idx = ltIndex + 1
        if (idx >= size) return false
        var ch = codePointAtIndex(idx)
        if (ch == '/'.code) {
            idx++
            if (idx >= size) return false
            ch = codePointAtIndex(idx)
        }
        if (!isAsciiLetter(ch)) return false
        idx++
        while (idx < size) {
            ch = codePointAtIndex(idx)
            if (isAsciiLetter(ch) || ch in '0'.code..'9'.code || ch == '-'.code) {
                idx++
            } else {
                break
            }
        }
        if (idx >= size) return false
        ch = codePointAtIndex(idx)
        if (ch == '>'.code) return true
        if (ch != ' '.code && ch != '\t'.code && ch != '\r'.code && ch != '\n'.code && ch != '/'.code) {
            return false
        }

        // Deterministic 3-state automaton: 0 = outside quotes, 1 = double-quoted, 2 = single-quoted
        var quoteState = 0
        var steps = 0
        val cache = failedHtmlTagStates
        var visited: ArrayList<Long>? = null

        while (idx < size) {
            val stateKey = (idx.toLong() shl 2) or quoteState.toLong()
            if (cache != null && stateKey in cache) {
                return false
            }
            if (steps > 32) {
                if (visited == null) visited = ArrayList()
                visited.add(stateKey)
            }
            steps++

            ch = codePointAtIndex(idx)
            when (quoteState) {
                0 -> when (ch) {
                    '>'.code -> return true
                    '<'.code -> {
                        recordFailedHtmlStates(visited)
                        return false
                    }
                    '"'.code -> quoteState = 1
                    '\''.code -> quoteState = 2
                    '\r'.code, '\n'.code -> {
                        // Stop at blank line
                        val afterNl = skipNewlineAt(idx, size)
                        if (isBlankLineAt(afterNl, size)) {
                            recordFailedHtmlStates(visited)
                            return false
                        }
                        idx = afterNl
                        continue
                    }
                }
                1 -> when (ch) {
                    '"'.code -> quoteState = 0
                    '\r'.code, '\n'.code -> {
                        val afterNl = skipNewlineAt(idx, size)
                        if (isBlankLineAt(afterNl, size)) {
                            recordFailedHtmlStates(visited)
                            return false
                        }
                        idx = afterNl
                        continue
                    }
                }
                2 -> when (ch) {
                    '\''.code -> quoteState = 0
                    '\r'.code, '\n'.code -> {
                        val afterNl = skipNewlineAt(idx, size)
                        if (isBlankLineAt(afterNl, size)) {
                            recordFailedHtmlStates(visited)
                            return false
                        }
                        idx = afterNl
                        continue
                    }
                }
            }
            idx++
        }
        recordFailedHtmlStates(visited)
        return false
    }

    private fun recordFailedHtmlStates(visited: List<Long>?) {
        if (visited == null) return
        val cache = failedHtmlTagStates ?: HashSet<Long>().also { failedHtmlTagStates = it }
        cache.addAll(visited)
    }

    protected fun onHtmlName() {
        if (htmlExpectingTagName) {
            htmlExpectingTagName = false
            type = htmlNameTokenType()
        } else {
            type = htmlAttrNameTokenType()
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun codePointAtIndex(cpIndex: Int): Int {
        val pos = _input.index()
        return if (cpIndex >= pos) {
            _input.LA(cpIndex - pos + 1)
        } else {
            _input.LA(cpIndex - pos)
        }
    }

    private fun skipNewlineAt(idx: Int, size: Int): Int {
        if (idx >= size) return idx
        val ch = codePointAtIndex(idx)
        if (ch == '\r'.code) {
            return if (idx + 1 < size && codePointAtIndex(idx + 1) == '\n'.code) idx + 2 else idx + 1
        }
        if (ch == '\n'.code) return idx + 1
        return idx
    }

    private fun advancePastLine(idx: Int, size: Int): Int {
        var i = idx
        while (i < size) {
            val ch = codePointAtIndex(i)
            if (ch == '\r'.code || ch == '\n'.code) {
                return skipNewlineAt(i, size)
            }
            i++
        }
        return size
    }

    private fun isBlankLineAt(idx: Int, size: Int): Boolean {
        var i = idx
        while (i < size) {
            val ch = codePointAtIndex(i)
            if (ch == ' '.code || ch == '\t'.code) {
                i++
            } else if (ch == '\r'.code || ch == '\n'.code) {
                return true
            } else {
                return false
            }
        }
        return true
    }

    private fun countSameChar(start: Int, size: Int, ch: Int): Int {
        var idx = start
        while (idx < size && codePointAtIndex(idx) == ch) {
            idx++
        }
        return idx - start
    }

    private fun hasNoBacktickUntilEol(start: Int, size: Int): Boolean {
        var idx = start
        while (idx < size) {
            val ch = codePointAtIndex(idx)
            when (ch) {
                '\r'.code, '\n'.code -> return true
                '`'.code -> return false
                else -> idx++
            }
        }
        return true
    }

    private fun isAsciiLetter(ch: Int): Boolean =
        ch in 'A'.code..'Z'.code || ch in 'a'.code..'z'.code

    private fun asciiLower(ch: Int): Int =
        if (ch in 'A'.code..'Z'.code) ch + ('a'.code - 'A'.code) else ch

    private fun matchesLiteralCaseSensitive(start: Int, literal: String, size: Int): Boolean {
        if (start + literal.length > size) return false
        for (k in literal.indices) {
            if (codePointAtIndex(start + k) != literal[k].code) return false
        }
        return true
    }

    private fun matchesLiteralCaseInsensitive(start: Int, lowerLiteral: String, size: Int): Boolean {
        if (start + lowerLiteral.length > size) return false
        for (k in lowerLiteral.indices) {
            if (asciiLower(codePointAtIndex(start + k)) != lowerLiteral[k].code) return false
        }
        return true
    }

    private fun matchesRawCloseTagAt(ltIndex: Int): Boolean {
        val tag = htmlRawCloseTag
        if (tag.isEmpty()) return false
        val size = _input.size()
        if (!matchesLiteralCaseInsensitive(ltIndex, tag, size)) return false
        val after = ltIndex + tag.length
        val ch = if (after < size) codePointAtIndex(after) else IntStream.EOF
        return ch == '>'.code || isSpaceTabEolOrEof(ch)
    }

    private fun matchHtmlBlockType1TagName(ltIndex: Int, size: Int): String? {
        for (tag in TYPE1_HTML_TAGS) {
            val len = 1 + tag.length
            if (ltIndex + len <= size &&
                codePointAtIndex(ltIndex) == '<'.code &&
                matchesLiteralCaseInsensitive(ltIndex + 1, tag, size)
            ) {
                val after = if (ltIndex + len < size) codePointAtIndex(ltIndex + len) else IntStream.EOF
                if (after == '>'.code || isSpaceTabEolOrEof(after)) {
                    return tag
                }
            }
        }
        return null
    }

    private fun matchHtmlBlockType6(ltIndex: Int, size: Int): Boolean {
        if (ltIndex + 2 >= size || codePointAtIndex(ltIndex) != '<'.code) return false
        var nameStart = ltIndex + 1
        if (codePointAtIndex(nameStart) == '/'.code) {
            nameStart++
            if (nameStart >= size) return false
        }
        var nameEnd = nameStart
        while (nameEnd < size && nameEnd - nameStart < 16) {
            val ch = codePointAtIndex(nameEnd)
            if (isAsciiLetter(ch) || ch in '0'.code..'9'.code) {
                nameEnd++
            } else {
                break
            }
        }
        if (nameEnd == nameStart) return false
        val after = if (nameEnd < size) codePointAtIndex(nameEnd) else IntStream.EOF
        val validBoundary = isSpaceTabEolOrEof(after) ||
            after == '>'.code ||
            (after == '/'.code && nameEnd + 1 < size && codePointAtIndex(nameEnd + 1) == '>'.code)
        if (!validBoundary) return false
        val sb = StringBuilder(nameEnd - nameStart)
        for (p in nameStart until nameEnd) {
            sb.append(asciiLower(codePointAtIndex(p)).toChar())
        }
        return sb.toString() in HTML_BLOCK_TYPE6_TAGS
    }

    private fun findValidHtmlTagCloseEndSameLine(ltIndex: Int, size: Int): Int {
        var idx = ltIndex + 1
        if (idx >= size) return -1
        val isClosing = codePointAtIndex(idx) == '/'.code
        if (isClosing) {
            idx++
            if (idx >= size) return -1
        }
        if (!isAsciiLetter(codePointAtIndex(idx))) return -1
        idx++
        while (idx < size) {
            val ch = codePointAtIndex(idx)
            if (isAsciiLetter(ch) || ch in '0'.code..'9'.code || ch == '-'.code) {
                idx++
            } else {
                break
            }
        }
        if (isClosing) {
            while (idx < size && (codePointAtIndex(idx) == ' '.code || codePointAtIndex(idx) == '\t'.code)) {
                idx++
            }
            return if (idx < size && codePointAtIndex(idx) == '>'.code) idx + 1 else -1
        }
        while (idx < size) {
            var sawWs = false
            while (idx < size && (codePointAtIndex(idx) == ' '.code || codePointAtIndex(idx) == '\t'.code)) {
                sawWs = true
                idx++
            }
            if (idx >= size) return -1
            val ch = codePointAtIndex(idx)
            if (ch == '>'.code) return idx + 1
            if (ch == '/'.code) {
                return if (idx + 1 < size && codePointAtIndex(idx + 1) == '>'.code) idx + 2 else -1
            }
            if (!sawWs) return -1
            if (!isAsciiLetter(ch) && ch != '_'.code && ch != ':'.code) return -1
            idx++
            while (idx < size) {
                val ac = codePointAtIndex(idx)
                if (isAsciiLetter(ac) || ac in '0'.code..'9'.code || ac == '_'.code || ac == '.'.code || ac == ':'.code || ac == '-'.code) {
                    idx++
                } else {
                    break
                }
            }
            var eqScan = idx
            while (eqScan < size && (codePointAtIndex(eqScan) == ' '.code || codePointAtIndex(eqScan) == '\t'.code)) {
                eqScan++
            }
            if (eqScan < size && codePointAtIndex(eqScan) == '='.code) {
                idx = eqScan + 1
                while (idx < size && (codePointAtIndex(idx) == ' '.code || codePointAtIndex(idx) == '\t'.code)) {
                    idx++
                }
                if (idx >= size) return -1
                val vc = codePointAtIndex(idx)
                if (vc == '"'.code || vc == '\''.code) {
                    idx++
                    while (idx < size) {
                        val q = codePointAtIndex(idx)
                        if (q == vc) {
                            idx++
                            break
                        }
                        if (q == '\r'.code || q == '\n'.code) return -1
                        idx++
                    }
                    if (idx > size || codePointAtIndex(idx - 1) != vc) return -1
                } else {
                    val vStart = idx
                    while (idx < size) {
                        val u = codePointAtIndex(idx)
                        if (u == ' '.code || u == '\t'.code || u == '\r'.code || u == '\n'.code ||
                            u == '"'.code || u == '\''.code || u == '='.code || u == '<'.code || u == '>'.code || u == '`'.code
                        ) {
                            break
                        }
                        idx++
                    }
                    if (idx == vStart) return -1
                }
            }
        }
        return -1
    }

    private fun isBlankBqLineAt(idx: Int, requiredDepth: Int, size: Int): Boolean {
        var pos = idx
        for (d in 0 until requiredDepth) {
            var spaces = 0
            while (pos < size && spaces < 4 && codePointAtIndex(pos) == ' '.code) {
                spaces++
                pos++
            }
            if (spaces > 3 || pos >= size || codePointAtIndex(pos) != '>'.code) return false
            pos++
            if (pos < size && codePointAtIndex(pos) == ' '.code) pos++
        }
        return isBlankLineAt(pos, size)
    }

    private fun skipHtmlBlockForBacktickIndex(
        ltStart: Int,
        size: Int,
        bqDepth: Int,
        allowType7: Boolean,
    ): Int {
        // Type 1: <pre, <script, <style, <textarea
        val type1Tag = matchHtmlBlockType1TagName(ltStart, size)
        if (type1Tag != null) {
            return skipRawHtmlLinesForBacktickIndex(ltStart, size, bqDepth, blockType = 1, closeTag = "</$type1Tag")
        }

        // Type 3: <? ... ?>
        if (ltStart + 1 < size && codePointAtIndex(ltStart + 1) == '?'.code) {
            return skipRawHtmlLinesForBacktickIndex(ltStart + 2, size, bqDepth, blockType = 3, closeTag = "")
        }

        // Type 5: <![CDATA[ ... ]]>
        if (matchesLiteralCaseSensitive(ltStart, "<![CDATA[", size)) {
            return skipRawHtmlLinesForBacktickIndex(ltStart + 9, size, bqDepth, blockType = 5, closeTag = "")
        }

        // Type 4: <! + ASCII letter ... >
        if (ltStart + 2 < size && codePointAtIndex(ltStart + 1) == '!'.code && isAsciiLetter(codePointAtIndex(ltStart + 2))) {
            return skipRawHtmlLinesForBacktickIndex(ltStart + 3, size, bqDepth, blockType = 4, closeTag = "")
        }

        // Type 6 or Type 7
        val isType6 = matchHtmlBlockType6(ltStart, size)
        val isType7 = !isType6 && allowType7 &&
            findValidHtmlTagCloseEndSameLine(ltStart, size).let { it > ltStart && isOnlyWsBeforeEolOrEofAt(it, size) }
        if (isType6 || isType7) {
            var idx = advancePastLine(ltStart, size)
            while (idx < size) {
                if (bqDepth == 0) {
                    if (isBlankLineAt(idx, size)) return idx
                } else {
                    if (!hasBlockquotePrefixAhead(idx, bqDepth) || isBlankBqLineAt(idx, bqDepth, size)) return idx
                }
                idx = advancePastLine(idx, size)
            }
            return size
        }
        return -1
    }

    private fun skipBlockquotePrefix(lineStart: Int, requiredDepth: Int, size: Int): Int {
        var idx = lineStart
        for (d in 0 until requiredDepth) {
            var spaces = 0
            while (idx < size && spaces < 4 && codePointAtIndex(idx) == ' '.code) {
                spaces++
                idx++
            }
            if (spaces > 3 || idx >= size || codePointAtIndex(idx) != '>'.code) return idx
            idx++
            if (idx < size && codePointAtIndex(idx) == ' '.code) idx++
        }
        return idx
    }

    private fun skipRawHtmlLinesForBacktickIndex(
        scanStart: Int,
        size: Int,
        bqDepth: Int,
        blockType: Int,
        closeTag: String,
    ): Int {
        var idx = scanStart
        var firstLine = true
        while (idx < size) {
            if (!firstLine && bqDepth > 0) {
                if (!hasBlockquotePrefixAhead(idx, bqDepth)) {
                    return idx
                }
                idx = skipBlockquotePrefix(idx, bqDepth, size)
            }
            firstLine = false
            while (idx < size) {
                val ch = codePointAtIndex(idx)
                if (ch == '\r'.code || ch == '\n'.code) {
                    idx = skipNewlineAt(idx, size)
                    break
                }
                val matchedClose = when (blockType) {
                    1 -> ch == '<'.code &&
                        matchesLiteralCaseInsensitive(idx, closeTag, size) &&
                        run {
                            val after = idx + closeTag.length
                            val ac = if (after < size) codePointAtIndex(after) else IntStream.EOF
                            ac == '>'.code || isSpaceTabEolOrEof(ac)
                        }
                    3 -> ch == '?'.code && idx + 1 < size && codePointAtIndex(idx + 1) == '>'.code
                    4 -> ch == '>'.code
                    5 -> ch == ']'.code && idx + 2 < size && codePointAtIndex(idx + 1) == ']'.code && codePointAtIndex(idx + 2) == '>'.code
                    else -> false
                }
                if (matchedClose) {
                    return advancePastLine(idx, size)
                }
                idx++
            }
        }
        return size
    }

    private companion object {
        val TYPE1_HTML_TAGS = arrayOf("pre", "script", "style", "textarea")

        val HTML_BLOCK_TYPE6_TAGS = hashSetOf(
            "address", "article", "aside", "base", "basefont", "blockquote", "body",
            "caption", "center", "col", "colgroup", "dd", "details", "dialog", "dir",
            "div", "dl", "dt", "fieldset", "figcaption", "figure", "footer", "form",
            "frame", "frameset", "h1", "h2", "h3", "h4", "h5", "h6", "head", "header",
            "hr", "html", "iframe", "legend", "li", "link", "main", "menu", "menuitem",
            "nav", "noframes", "ol", "optgroup", "option", "p", "param", "search",
            "section", "summary", "table", "tbody", "td", "tfoot", "th", "thead",
            "title", "tr", "track", "ul",
        )
    }
}
