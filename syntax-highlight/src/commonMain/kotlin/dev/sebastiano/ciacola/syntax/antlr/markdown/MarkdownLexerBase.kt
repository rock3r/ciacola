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

    private var currentLineIsBlank: Boolean = true
    private var currentLineIsParagraph: Boolean = false
    private var currentLineIsIndentedCode: Boolean = false
    private var blockquoteDepth: Int = 0
    private var justSawBlockquoteMarker: Boolean = false
    private var leadingIndent: Int = 0
    private var afterListMarker: Boolean = false
    private var canMatchTaskCheckbox: Boolean = false
    private var currentLineHasListMarker: Boolean = false
    private var currentLineIsRefDef: Boolean = false

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

    // Lazy O(N) backtick run index: built only if a backtick is actually encountered.
    private var backtickIndexBuilt: Boolean = false
    private var backtickRunParaIds: HashMap<Int, Int>? = null
    private var backtickRunsByLength: HashMap<Int, ArrayList<Int>>? = null
    private var backtickCursorByLength: HashMap<Int, Int>? = null

    // 3-state HTML tag failure cache: encodes (cpIndex shl 2) or quoteState.
    private var failedHtmlTagStates: HashSet<Long>? = null

    override fun reset() {
        super.reset()
        atDocStart = true
        startsWithBom = false
        afterBlankLine = true
        prevLineWasParagraph = false
        prevLineBlockquoteDepth = 0
        prevLineWasIndentedCode = false

        currentLineIsBlank = true
        currentLineIsParagraph = false
        currentLineIsIndentedCode = false
        blockquoteDepth = 0
        justSawBlockquoteMarker = false
        leadingIndent = 0
        afterListMarker = false
        canMatchTaskCheckbox = false
        currentLineHasListMarker = false
        currentLineIsRefDef = false

        frontmatterType = 0
        fenceChar = 0
        fenceLen = 0
        fenceBlockquoteDepth = 0
        fenceLangEmitted = false
        bqFenceSpacesAfterLastMarker = 0
        bqFenceJustSawMarker = false
        htmlExpectingTagName = false

        backtickIndexBuilt = false
        backtickRunParaIds = null
        backtickRunsByLength = null
        backtickCursorByLength = null
        failedHtmlTagStates = null
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
    protected abstract fun tildeTildeTokenType(): Int
    protected abstract fun lbrackTokenType(): Int
    protected abstract fun htmlCommentTokenType(): Int
    protected abstract fun autolinkTokenType(): Int
    protected abstract fun htmlTagOpenTokenType(): Int
    protected abstract fun htmlNameTokenType(): Int
    protected abstract fun htmlAttrNameTokenType(): Int
    protected abstract fun htmlAttrValueTokenType(): Int
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
        afterBlankLine = currentLineIsBlank

        resetLineState()
    }

    protected fun onInlineNewline() {
        prevLineWasParagraph = currentLineIsParagraph
        prevLineBlockquoteDepth = blockquoteDepth
        prevLineWasIndentedCode = currentLineIsIndentedCode
        afterBlankLine = false

        resetLineState()
    }

    protected fun onIndentedCodeNewline() {
        prevLineWasParagraph = false
        prevLineBlockquoteDepth = 0
        prevLineWasIndentedCode = true
        afterBlankLine = false

        resetLineState()
    }

    private fun resetLineState() {
        atDocStart = false
        currentLineIsBlank = true
        currentLineIsParagraph = false
        currentLineIsIndentedCode = false
        blockquoteDepth = 0
        justSawBlockquoteMarker = false
        leadingIndent = 0
        afterListMarker = false
        canMatchTaskCheckbox = false
        currentLineHasListMarker = false
        currentLineIsRefDef = false
    }

    protected fun onLineStartWs() {
        val start = _tokenStartCharIndex
        val end = _input.index()
        var streamCol = if (justSawBlockquoteMarker) 1 else leadingIndent
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
        val canIndentCode = atDocStart || afterBlankLine || prevLineWasIndentedCode
        if (canIndentCode && leadingIndent >= 4) {
            currentLineIsBlank = false
            currentLineIsParagraph = false
            currentLineIsIndentedCode = true
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
        blockquoteDepth++
        justSawBlockquoteMarker = true
        leadingIndent = 0
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
        } else if (count == 2) {
            markInlineParagraphStart()
            type = tildeTildeTokenType()
            mode(inlineModeId())
        } else {
            markInlineParagraphStart()
            type = textTokenType()
            mode(inlineModeId())
        }
    }

    private fun enterFence(fenceCh: Int, count: Int) {
        atDocStart = false
        currentLineIsBlank = false
        currentLineIsParagraph = false
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
        currentLineHasListMarker = true
        afterListMarker = true
        canMatchTaskCheckbox = false
        type = listMarkerTokenType()
    }

    protected fun onNonParagraphLineStart() {
        atDocStart = false
        currentLineIsBlank = false
        currentLineIsParagraph = false
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
                else -> return -1
            }
        }
        return if (count >= 3) idx else -1
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
        var prevWasIndentedCode = false

        while (i < size) {
            // 1. Parse blockquote prefix ('>' markers with <= 3 spaces before each and optional 1 space/tab)
            var bqDepth = 0
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
                    }
                    pos = p
                } else {
                    break
                }
            }

            // 2. Count leading indentation after blockquote prefix
            var col = 0
            var streamCol = if (bqDepth > 0) 1 else 0
            var firstAfterBq = bqDepth > 0
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

            // 3. Blank line
            if (pos >= size || codePointAtIndex(pos) == '\r'.code || codePointAtIndex(pos) == '\n'.code) {
                paraId++
                i = skipNewlineAt(pos, size)
                prevBqDepth = bqDepth
                prevLineWasBlank = true
                prevWasIndentedCode = false
                continue
            }

            // Blockquote depth transition breaks inline code spans across lines
            if (bqDepth != prevBqDepth) {
                paraId++
            }

            // 4. Indented code block (>= 4 columns after blank/doc-start/indented-code)
            if ((prevLineWasBlank || prevWasIndentedCode) && col >= 4) {
                paraId++
                i = advancePastLine(pos, size)
                paraId++
                prevBqDepth = bqDepth
                prevLineWasBlank = false
                prevWasIndentedCode = true
                continue
            }

            // 5. Block-level starters at indent <= 3
            val firstCh = codePointAtIndex(pos)
            if (col <= 3) {
                // Fenced code block opener (``` or ~~~)
                if ((firstCh == '`'.code || firstCh == '~'.code)) {
                    val fenceCount = countSameChar(pos, size, firstCh)
                    if (fenceCount >= 3 && (firstCh == '~'.code || hasNoBacktickUntilEol(pos + fenceCount, size))) {
                        paraId++
                        i = skipFencedBlockForBacktickIndex(pos + fenceCount, size, firstCh, fenceCount, bqDepth)
                        paraId++
                        prevBqDepth = bqDepth
                        prevLineWasBlank = false
                        prevWasIndentedCode = false
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
                        prevWasIndentedCode = false
                        continue
                    }
                }

                // Thematic break (---, ***, ___) or Setext underline (===, ---)
                if ((firstCh == '-'.code || firstCh == '*'.code || firstCh == '_'.code) &&
                    isThematicOrSetextLine(pos, size, firstCh)
                ) {
                    paraId++
                    i = advancePastLine(pos, size)
                    paraId++
                    prevBqDepth = bqDepth
                    prevLineWasBlank = false
                    prevWasIndentedCode = false
                    continue
                }
                if (firstCh == '='.code && isSetextEqualsLine(pos, size)) {
                    paraId++
                    i = advancePastLine(pos, size)
                    paraId++
                    prevBqDepth = bqDepth
                    prevLineWasBlank = false
                    prevWasIndentedCode = false
                    continue
                }

                // Unordered list marker (-, *, + followed by space/tab/EOL/EOF)
                if ((firstCh == '-'.code || firstCh == '*'.code || firstCh == '+'.code) &&
                    isSpaceTabEolOrEof(if (pos + 1 < size) codePointAtIndex(pos + 1) else IntStream.EOF)
                ) {
                    paraId++
                    pos++
                } else if (firstCh in '0'.code..'9'.code) {
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
                            paraId++
                            pos = dp + 1
                        }
                    }
                } else if (firstCh == '<'.code &&
                    pos + 3 < size &&
                    codePointAtIndex(pos + 1) == '!'.code &&
                    codePointAtIndex(pos + 2) == '-'.code &&
                    codePointAtIndex(pos + 3) == '-'.code
                ) {
                    paraId++
                }
            }

            // 6. Index backtick runs across the rest of this line
            i = indexBackticksOnLine(pos, size, paraId, paraIds, byLen)
            prevBqDepth = bqDepth
            prevLineWasBlank = false
            prevWasIndentedCode = false
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

    private fun isThematicOrSetextLine(start: Int, size: Int, marker: Int): Boolean {
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
                '\r'.code, '\n'.code -> return count >= 3
                else -> return false
            }
        }
        return count >= 3
    }

    private fun isSetextEqualsLine(start: Int, size: Int): Boolean {
        var idx = start
        while (idx < size) {
            val ch = codePointAtIndex(idx)
            when (ch) {
                '='.code, ' '.code, '\t'.code -> idx++
                '\r'.code, '\n'.code -> return true
                else -> return false
            }
        }
        return true
    }

    // -------------------------------------------------------------------------
    // HTML comments, autolinks, and 3-state memoized inline HTML tags
    // -------------------------------------------------------------------------

    protected fun consumeHtmlComment() {
        val size = _input.size()
        var idx = _input.index()
        while (idx < size) {
            if (codePointAtIndex(idx) == '-'.code &&
                idx + 2 < size &&
                codePointAtIndex(idx + 1) == '-'.code &&
                codePointAtIndex(idx + 2) == '>'.code
            ) {
                _input.seek(idx + 3)
                type = htmlCommentTokenType()
                return
            }
            idx++
        }
        _input.seek(size)
        type = htmlCommentTokenType()
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
        markInlineParagraphStart()
        val start = _tokenStartCharIndex
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
}
