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
    }

    protected fun onLineStartWs() {
        val start = _tokenStartCharIndex
        val end = _input.index()
        var col = 0
        var first = true
        for (cp in start until end) {
            val ch = codePointAtIndex(cp)
            if (first && justSawBlockquoteMarker && ch == ' '.code) {
                // Consume the single optional space after '>' without counting it toward inner indent.
                first = false
                continue
            }
            first = false
            col += if (ch == '\t'.code) 4 - (col % 4) else 1
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
        markInlineParagraphStart()
        type = lbrackTokenType()
        mode(inlineModeId())
    }

    private fun enterListMarker() {
        atDocStart = false
        currentLineIsBlank = false
        currentLineIsParagraph = false
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
        currentLineIsParagraph = true
        justSawBlockquoteMarker = false
        afterListMarker = false
        canMatchTaskCheckbox = false
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
        var spaces = 0
        var first = true
        for (cp in start until end) {
            val ch = codePointAtIndex(cp)
            if (first && bqFenceJustSawMarker && ch == ' '.code) {
                first = false
                continue
            }
            first = false
            spaces += if (ch == '\t'.code) 4 else 1
        }
        bqFenceJustSawMarker = false
        bqFenceSpacesAfterLastMarker += spaces
        if (blockquoteDepth >= fenceBlockquoteDepth && bqFenceSpacesAfterLastMarker >= 4) {
            // 4+ spaces inside blockquote fence body line cannot be a closing fence.
        }
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
        var atLineStart = true
        var lineHasNonWs = false
        var lineSpaces = 0

        while (i < size) {
            val ch = codePointAtIndex(i)
            when (ch) {
                '\r'.code, '\n'.code -> {
                    if (atLineStart || !lineHasNonWs) {
                        paraId++
                    }
                    i = skipNewlineAt(i, size)
                    atLineStart = true
                    lineHasNonWs = false
                    lineSpaces = 0
                }
                ' '.code, '\t'.code -> {
                    if (!lineHasNonWs) {
                        lineSpaces += if (ch == '\t'.code) 4 else 1
                    }
                    i++
                }
                '`'.code -> {
                    val runStart = i
                    while (i < size && codePointAtIndex(i) == '`'.code) {
                        i++
                    }
                    val len = i - runStart
                    if (!lineHasNonWs && lineSpaces <= 3 && len >= 3 && hasNoBacktickUntilEol(i, size)) {
                        // Fenced code block opener breaks any preceding paragraph.
                        paraId++
                    } else {
                        paraIds[runStart] = paraId
                        byLen.getOrPut(len) { ArrayList() }.add(runStart)
                    }
                    atLineStart = false
                    lineHasNonWs = true
                }
                '~'.code -> {
                    if (!lineHasNonWs && lineSpaces <= 3 && countSameChar(i, size, '~'.code) >= 3) {
                        paraId++
                    }
                    atLineStart = false
                    lineHasNonWs = true
                    i++
                }
                '#'.code -> {
                    if (!lineHasNonWs && lineSpaces <= 3) {
                        val hashes = countSameChar(i, size, '#'.code)
                        val after = if (i + hashes < size) codePointAtIndex(i + hashes) else IntStream.EOF
                        if (hashes in 1..6 && isSpaceTabEolOrEof(after)) {
                            paraId++
                        }
                    }
                    atLineStart = false
                    lineHasNonWs = true
                    i++
                }
                else -> {
                    atLineStart = false
                    lineHasNonWs = true
                    i++
                }
            }
        }

        backtickRunParaIds = paraIds
        backtickRunsByLength = byLen
        backtickCursorByLength = cursors
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
