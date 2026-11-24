package dev.sebastiano.ciacola.syntax.lexer

import dev.sebastiano.ciacola.syntax.SyntaxHighlighter
import dev.sebastiano.ciacola.syntax.SyntaxLanguage
import dev.sebastiano.ciacola.syntax.SyntaxLexer
import dev.sebastiano.ciacola.syntax.SyntaxToken
import dev.sebastiano.ciacola.syntax.SyntaxTokenKind
import dev.sebastiano.ciacola.syntax.antlr.markdown.MarkdownLexer
import dev.sebastiano.ciacola.syntax.antlr.markdown.MarkdownLexer.Tokens as MdTokens
import dev.sebastiano.ciacola.syntax.internal.antlrCharStream
import dev.sebastiano.ciacola.syntax.internal.driveAntlrLexer
import dev.sebastiano.ciacola.syntax.internal.emitDefaultGap
import dev.sebastiano.ciacola.syntax.internal.isAsciiWhitespace

/**
 * Lossless ANTLR4-backed lexical tokenizer for Markdown (CommonMark + GFM + YAML/TOML frontmatter),
 * supporting 1-level-deep non-recursive sub-highlighting for fenced code blocks and frontmatter.
 */
internal object MarkdownSyntaxLexer : SyntaxLexer, SubHighlightableSyntaxLexer {
    override val language: SyntaxLanguage = SyntaxLanguage.Markdown

    override fun lex(source: String): List<SyntaxToken> =
        lex(source, allowNestedSubHighlighting = true)

    override fun lex(source: String, allowNestedSubHighlighting: Boolean): List<SyntaxToken> {
        if (source.isEmpty()) return emptyList()

        val lexer = MarkdownLexer(antlrCharStream(source))
        val raw = ArrayList<RawMdToken>(source.length / 4 + 8)

        driveAntlrLexer(
            source = source,
            lexer = lexer,
            onGap = { start, end ->
                emitDefaultGap(source, start, end) { kind, s, e ->
                    raw += RawMdToken(
                        type = if (kind == SyntaxTokenKind.Whitespace) MdTokens.WS else MdTokens.TEXT,
                        start = s,
                        end = e,
                    )
                }
            },
            onToken = { type, start, end ->
                raw += RawMdToken(type = type, start = start, end = end)
            },
        )

        return processMarkdownTokens(source, raw, allowNestedSubHighlighting)
    }
}

private class RawMdToken(
    val type: Int,
    val start: Int,
    val end: Int,
)

private class BqFenceLine(
    val prefixTokens: List<SyntaxToken>,
    val bodyStart: Int,
    val bodyEnd: Int,
    val nlStart: Int,
    val nlEnd: Int,
) {
    val bodyLen: Int get() = bodyEnd - bodyStart
}

private class BqFenceAccumulator {
    var active: Boolean = false
        private set
    var fenceBqDepth: Int = 0
        private set
    private var targetLanguage: SyntaxLanguage? = null
    private var allowNested: Boolean = false
    private val lines = ArrayList<BqFenceLine>()
    private val buffer = StringBuilder()

    fun begin(language: SyntaxLanguage?, allowNestedSubHighlighting: Boolean, bqDepth: Int) {
        active = true
        fenceBqDepth = bqDepth
        targetLanguage = language
        allowNested = allowNestedSubHighlighting
        lines.clear()
        buffer.clear()
    }

    fun appendLine(
        source: String,
        lineTokens: List<RawMdToken>,
        outHasNl: Boolean,
    ) {
        var lastBqIdx = -1
        for (idx in lineTokens.indices) {
            if (lineTokens[idx].type == MdTokens.BLOCKQUOTE_MARKER) {
                lastBqIdx = idx
            } else if (lineTokens[idx].type == MdTokens.FENCE_BODY_LINE) {
                break
            }
        }
        val prefixTokens = ArrayList<SyntaxToken>(lastBqIdx + 2)
        var bodyStartIdx = lastBqIdx + 1
        for (idx in 0..lastBqIdx) {
            val t = lineTokens[idx]
            val kind = if (t.type == MdTokens.BLOCKQUOTE_MARKER) SyntaxTokenKind.Punctuation else SyntaxTokenKind.Whitespace
            prefixTokens += SyntaxToken(kind, t.start, t.end)
        }
        // Optional single space right after the final '>' belongs to the blockquote prefix.
        if (bodyStartIdx < lineTokens.size && lineTokens[bodyStartIdx].type == MdTokens.WS) {
            val wsTok = lineTokens[bodyStartIdx]
            if (source[wsTok.start] == ' ') {
                prefixTokens += SyntaxToken(SyntaxTokenKind.Whitespace, wsTok.start, wsTok.start + 1)
                if (wsTok.start + 1 < wsTok.end) {
                    // Remaining spaces belong to the code body indentation.
                    val bodyStart = wsTok.start + 1
                    val nlTok = if (outHasNl) lineTokens.last() else null
                    val bodyEnd = nlTok?.start ?: lineTokens.last().end
                    val nlStart = nlTok?.start ?: bodyEnd
                    val nlEnd = nlTok?.end ?: bodyEnd
                    lines += BqFenceLine(prefixTokens, bodyStart, bodyEnd, nlStart, nlEnd)
                    if (bodyStart < bodyEnd) buffer.append(source, bodyStart, bodyEnd)
                    if (nlStart < nlEnd) buffer.append(source, nlStart, nlEnd)
                    return
                }
                bodyStartIdx++
            }
        }

        val nlTok = if (outHasNl) lineTokens.last() else null
        val contentEndIdx = if (outHasNl) lineTokens.size - 1 else lineTokens.size
        val bodyStart = if (bodyStartIdx < contentEndIdx) lineTokens[bodyStartIdx].start else (nlTok?.start ?: lineTokens.last().end)
        val bodyEnd = if (bodyStartIdx < contentEndIdx) lineTokens[contentEndIdx - 1].end else bodyStart
        val nlStart = nlTok?.start ?: bodyEnd
        val nlEnd = nlTok?.end ?: bodyEnd
        lines += BqFenceLine(prefixTokens, bodyStart, bodyEnd, nlStart, nlEnd)
        if (bodyStart < bodyEnd) buffer.append(source, bodyStart, bodyEnd)
        if (nlStart < nlEnd) buffer.append(source, nlStart, nlEnd)
    }

    fun flush(source: String, out: MutableList<SyntaxToken>) {
        if (!active) return
        active = false
        val lang = targetLanguage
        val canSubHighlight = allowNested &&
            lang != null &&
            lang != SyntaxLanguage.Markdown &&
            SyntaxHighlighter.lexerOrNull(lang) != null &&
            buffer.isNotEmpty()

        if (canSubHighlight) {
            val virtualText = buffer.toString()
            val subTokens = lexEmbeddedNoRecursion(virtualText, lang)
            if (subTokens.isNotEmpty()) {
                var subIdx = 0
                var bufOffset = 0
                for (line in lines) {
                    out.addAll(line.prefixTokens)
                    val lineBufEnd = bufOffset + line.bodyLen
                    while (subIdx < subTokens.size && subTokens[subIdx].startIndex < lineBufEnd) {
                        val st = subTokens[subIdx]
                        val ovStart = maxOf(st.startIndex, bufOffset)
                        val ovEnd = minOf(st.endIndex, lineBufEnd)
                        if (ovEnd > ovStart) {
                            out += SyntaxToken(
                                kind = st.kind,
                                startIndex = line.bodyStart + (ovStart - bufOffset),
                                endIndex = line.bodyStart + (ovEnd - bufOffset),
                            )
                        }
                        if (st.endIndex <= lineBufEnd) {
                            subIdx++
                        } else {
                            break
                        }
                    }
                    if (line.nlStart < line.nlEnd) {
                        out += SyntaxToken(SyntaxTokenKind.Whitespace, line.nlStart, line.nlEnd)
                    }
                    bufOffset = lineBufEnd + (line.nlEnd - line.nlStart)
                }
                lines.clear()
                buffer.clear()
                targetLanguage = null
                return
            }
        }

        for (line in lines) {
            out.addAll(line.prefixTokens)
            if (line.bodyStart < line.bodyEnd) {
                out += SyntaxToken(SyntaxTokenKind.String, line.bodyStart, line.bodyEnd)
            }
            if (line.nlStart < line.nlEnd) {
                out += SyntaxToken(SyntaxTokenKind.Whitespace, line.nlStart, line.nlEnd)
            }
        }
        lines.clear()
        buffer.clear()
        targetLanguage = null
        fenceBqDepth = 0
    }
}

private fun processMarkdownTokens(
    source: String,
    raw: List<RawMdToken>,
    allowNestedSubHighlighting: Boolean,
): List<SyntaxToken> {
    val out = ArrayList<SyntaxToken>(raw.size + 16)
    var currentFenceLang: SyntaxLanguage? = null
    var pendingBqFenceDepth = 0
    val bqFenceAcc = BqFenceAccumulator()
    var i = 0

    // Paragraph buffer holds contiguous inline/block-line tokens so links, Setext headings,
    // GFM tables, and emphasis can be resolved in a single O(N) pass without crossing fences.
    val paraBuffer = ArrayList<RawMdToken>()

    fun flushParagraph(followedBySetext: Boolean = false) {
        if (paraBuffer.isEmpty()) return
        emitParagraphTokens(source, paraBuffer, followedBySetext, out)
        paraBuffer.clear()
    }

    fun currentParaLineBlockquoteDepth(): Int {
        var depth = 0
        for (k in paraBuffer.lastIndex downTo 0) {
            val t = paraBuffer[k].type
            if (t == MdTokens.NEWLINE) break
            if (t == MdTokens.BLOCKQUOTE_MARKER) depth++
        }
        return depth
    }

    while (i < raw.size) {
        if (bqFenceAcc.active) {
            // Collect one line from raw starting at i.
            val lineStart = i
            var lineEnd = i
            while (lineEnd < raw.size && raw[lineEnd].type != MdTokens.NEWLINE) {
                lineEnd++
            }
            val hasNl = lineEnd < raw.size
            val endExcl = if (hasNl) lineEnd + 1 else lineEnd
            var hasBodyLine = false
            var hasFenceClose = false
            var bqCount = 0
            var onlyBqWsNl = true
            for (k in lineStart until endExcl) {
                when (raw[k].type) {
                    MdTokens.FENCE_BODY_LINE -> {
                        hasBodyLine = true
                        onlyBqWsNl = false
                    }
                    MdTokens.FENCE_CLOSE -> {
                        hasFenceClose = true
                        onlyBqWsNl = false
                    }
                    MdTokens.BLOCKQUOTE_MARKER -> bqCount++
                    MdTokens.WS, MdTokens.NEWLINE -> Unit
                    else -> onlyBqWsNl = false
                }
            }
            val isBlankBqContinuation = onlyBqWsNl && bqCount == bqFenceAcc.fenceBqDepth
            if (!hasFenceClose && (hasBodyLine || isBlankBqContinuation)) {
                bqFenceAcc.appendLine(source, raw.subList(lineStart, endExcl), hasNl)
                i = endExcl
                continue
            } else {
                bqFenceAcc.flush(source, out)
                if (hasFenceClose) {
                    for (k in lineStart until endExcl) {
                        val lt = raw[k]
                        val kind = when (lt.type) {
                            MdTokens.BLOCKQUOTE_MARKER, MdTokens.FENCE_CLOSE -> SyntaxTokenKind.Punctuation
                            MdTokens.WS, MdTokens.NEWLINE -> SyntaxTokenKind.Whitespace
                            else -> SyntaxTokenKind.Plain
                        }
                        out += SyntaxToken(kind, lt.start, lt.end)
                    }
                    currentFenceLang = null
                    i = endExcl
                    continue
                }
                // Otherwise fall through to process this non-fence line normally.
            }
        }

        val tok = raw[i]
        when (tok.type) {
            MdTokens.BOM -> {
                flushParagraph()
                out += SyntaxToken(SyntaxTokenKind.Whitespace, tok.start, tok.end)
            }

            MdTokens.FRONTMATTER_DELIM -> {
                flushParagraph()
                out += SyntaxToken(SyntaxTokenKind.Punctuation, tok.start, tok.end)
            }

            MdTokens.FRONTMATTER_YAML_BODY -> {
                flushParagraph()
                emitSubHighlightedOrPlain(
                    source = source,
                    start = tok.start,
                    end = tok.end,
                    targetLanguage = SyntaxLanguage.Yaml,
                    allowNestedSubHighlighting = allowNestedSubHighlighting,
                    fallbackKind = SyntaxTokenKind.Plain,
                    out = out,
                )
            }

            MdTokens.FRONTMATTER_TOML_BODY -> {
                flushParagraph()
                emitSubHighlightedOrPlain(
                    source = source,
                    start = tok.start,
                    end = tok.end,
                    targetLanguage = SyntaxLanguage.Toml,
                    allowNestedSubHighlighting = allowNestedSubHighlighting,
                    fallbackKind = SyntaxTokenKind.Plain,
                    out = out,
                )
            }

            MdTokens.FENCE_OPEN -> {
                val bqDepth = currentParaLineBlockquoteDepth()
                flushParagraph()
                currentFenceLang = null
                pendingBqFenceDepth = bqDepth
                out += SyntaxToken(SyntaxTokenKind.Punctuation, tok.start, tok.end)
            }

            MdTokens.FENCE_INFO_WS -> {
                out += SyntaxToken(SyntaxTokenKind.Whitespace, tok.start, tok.end)
            }

            MdTokens.FENCE_LANG -> {
                val langText = source.substring(tok.start, tok.end)
                currentFenceLang = SyntaxLanguage.fromNameOrAlias(langText)
                out += SyntaxToken(SyntaxTokenKind.Type, tok.start, tok.end)
            }

            MdTokens.FENCE_INFO_REST -> {
                val text = source.substring(tok.start, tok.end)
                val kind = if (text.startsWith('"') || text.startsWith('\'')) {
                    SyntaxTokenKind.String
                } else {
                    SyntaxTokenKind.Attribute
                }
                out += SyntaxToken(kind, tok.start, tok.end)
            }

            MdTokens.FENCE_BODY -> {
                flushParagraph()
                pendingBqFenceDepth = 0
                emitSubHighlightedOrPlain(
                    source = source,
                    start = tok.start,
                    end = tok.end,
                    targetLanguage = currentFenceLang,
                    allowNestedSubHighlighting = allowNestedSubHighlighting,
                    fallbackKind = SyntaxTokenKind.String,
                    out = out,
                )
            }

            MdTokens.FENCE_BODY_LINE -> {
                flushParagraph()
                out += SyntaxToken(SyntaxTokenKind.String, tok.start, tok.end)
            }

            MdTokens.FENCE_CLOSE -> {
                flushParagraph()
                pendingBqFenceDepth = 0
                currentFenceLang = null
                out += SyntaxToken(SyntaxTokenKind.Punctuation, tok.start, tok.end)
            }

            MdTokens.INDENTED_CODE_LINE -> {
                flushParagraph()
                out += SyntaxToken(SyntaxTokenKind.String, tok.start, tok.end)
            }

            MdTokens.THEMATIC_BREAK -> {
                flushParagraph()
                out += SyntaxToken(SyntaxTokenKind.Punctuation, tok.start, tok.end)
            }

            MdTokens.SETEXT_UNDERLINE -> {
                flushParagraph(followedBySetext = true)
                out += SyntaxToken(SyntaxTokenKind.Punctuation, tok.start, tok.end)
            }

            else -> {
                if (pendingBqFenceDepth > 0 && tok.type == MdTokens.NEWLINE) {
                    val bqDepth = pendingBqFenceDepth
                    pendingBqFenceDepth = 0
                    out += SyntaxToken(SyntaxTokenKind.Whitespace, tok.start, tok.end)
                    bqFenceAcc.begin(currentFenceLang, allowNestedSubHighlighting, bqDepth)
                } else {
                    paraBuffer += tok
                    // Flush at blank lines (two consecutive newlines with optional WS)
                    if (tok.type == MdTokens.NEWLINE && isBlankLineAhead(raw, i + 1)) {
                        flushParagraph()
                    }
                }
            }
        }
        i++
    }
    bqFenceAcc.flush(source, out)
    flushParagraph()
    return coalesceAdjacentTokens(out)
}

private fun isBlankLineAhead(raw: List<RawMdToken>, fromIdx: Int): Boolean {
    var j = fromIdx
    while (j < raw.size) {
        when (raw[j].type) {
            MdTokens.WS -> j++
            MdTokens.NEWLINE -> return true
            else -> return false
        }
    }
    return false
}

private fun emitSubHighlightedOrPlain(
    source: String,
    start: Int,
    end: Int,
    targetLanguage: SyntaxLanguage?,
    allowNestedSubHighlighting: Boolean,
    fallbackKind: SyntaxTokenKind,
    out: MutableList<SyntaxToken>,
) {
    if (start >= end) return
    val canSubHighlight = allowNestedSubHighlighting &&
        targetLanguage != null &&
        targetLanguage != SyntaxLanguage.Markdown &&
        SyntaxHighlighter.lexerOrNull(targetLanguage) != null

    if (canSubHighlight) {
        val slice = source.substring(start, end)
        val subTokens = lexEmbeddedNoRecursion(slice, targetLanguage)
        if (subTokens.isNotEmpty()) {
            for (tok in subTokens) {
                out += SyntaxToken(
                    kind = tok.kind,
                    startIndex = start + tok.startIndex,
                    endIndex = start + tok.endIndex,
                )
            }
            return
        }
    }

    // Fallback: emit line bodies as fallbackKind and line terminators as Whitespace.
    var cursor = start
    while (cursor < end) {
        val ch = source[cursor]
        if (ch == '\r' || ch == '\n') {
            val nlStart = cursor
            cursor++
            if (ch == '\r' && cursor < end && source[cursor] == '\n') {
                cursor++
            }
            out += SyntaxToken(SyntaxTokenKind.Whitespace, nlStart, cursor)
        } else {
            val lineStart = cursor
            while (cursor < end && source[cursor] != '\r' && source[cursor] != '\n') {
                cursor++
            }
            out += SyntaxToken(fallbackKind, lineStart, cursor)
        }
    }
}

private class LineMeta(
    val startIdx: Int,
    val endExclNl: Int,
    val bqDepth: Int,
    val isBlank: Boolean,
    val isAtx: Boolean,
    val isListStart: Boolean,
    var isNonPara: Boolean,
)

private fun emitParagraphTokens(
    source: String,
    raw: List<RawMdToken>,
    followedBySetext: Boolean,
    out: MutableList<SyntaxToken>,
) {
    val n = raw.size
    val resolvedKinds = arrayOfNulls<SyntaxTokenKind>(n)
    val excludedFromDelim = BooleanArray(n)
    val inAtxHeadingLine = BooleanArray(n)
    val inSetextHeadingLine = BooleanArray(n)
    val lines = ArrayList<LineMeta>()

    // 1. Identify line boundaries and line-level roles (ATX headings, Setext candidates, GFM tables)
    var lineStartIdx = 0
    while (lineStartIdx < n) {
        var lineEndIdx = lineStartIdx
        while (lineEndIdx < n && raw[lineEndIdx].type != MdTokens.NEWLINE) {
            lineEndIdx++
        }
        val lineEndExclNl = lineEndIdx
        val nextLineStart = if (lineEndIdx < n) lineEndIdx + 1 else n

        var bqDepth = 0
        var firstContent = lineStartIdx
        while (firstContent < lineEndExclNl &&
            (raw[firstContent].type == MdTokens.WS || raw[firstContent].type == MdTokens.BLOCKQUOTE_MARKER)
        ) {
            if (raw[firstContent].type == MdTokens.BLOCKQUOTE_MARKER) {
                bqDepth++
            }
            firstContent++
        }

        val isBlank = firstContent >= lineEndExclNl
        val isListStart = !isBlank && raw[firstContent].type == MdTokens.LIST_MARKER
        val isAtx = !isBlank && raw[firstContent].type == MdTokens.ATX_HEADING_OPEN
        var isNonPara = isBlank || isAtx || isListStart

        if (isAtx) {
            for (k in lineStartIdx until lineEndExclNl) {
                inAtxHeadingLine[k] = true
            }
            // Check optional closing '#' sequence preceded by WS (or ATX_HEADING_OPEN)
            var lastNonWs = lineEndExclNl - 1
            while (lastNonWs > firstContent && raw[lastNonWs].type == MdTokens.WS) {
                lastNonWs--
            }
            if (lastNonWs > firstContent && raw[lastNonWs].type == MdTokens.TEXT) {
                val tok = raw[lastNonWs]
                var allHashes = true
                for (p in tok.start until tok.end) {
                    if (source[p] != '#') {
                        allHashes = false
                        break
                    }
                }
                if (allHashes && raw[lastNonWs - 1].type == MdTokens.WS) {
                    resolvedKinds[lastNonWs] = SyntaxTokenKind.Punctuation
                    excludedFromDelim[lastNonWs] = true
                }
            }
        }

        // Check GFM table delimiter row (contains '|' and '-', only '|', '-', ':', WS)
        if (!isAtx && isGfmTableDelimiterRow(source, raw, firstContent, lineEndExclNl)) {
            isNonPara = true
            for (k in firstContent until lineEndExclNl) {
                val t = raw[k].type
                if (t == MdTokens.WS) {
                    resolvedKinds[k] = SyntaxTokenKind.Whitespace
                } else {
                    resolvedKinds[k] = SyntaxTokenKind.Punctuation
                    excludedFromDelim[k] = true
                }
            }
        }

        if (!isBlank && (raw[firstContent].type == MdTokens.HTML_COMMENT || raw[firstContent].type == MdTokens.HTML_BLOCK_TEXT)) {
            isNonPara = true
        }

        lines += LineMeta(
            startIdx = lineStartIdx,
            endExclNl = lineEndExclNl,
            bqDepth = bqDepth,
            isBlank = isBlank,
            isAtx = isAtx,
            isListStart = isListStart,
            isNonPara = isNonPara,
        )
        lineStartIdx = nextLineStart
    }

    // 2. Pre-assign non-inline structural & atomic tokens
    for (k in 0 until n) {
        val tok = raw[k]
        when (tok.type) {
            MdTokens.WS, MdTokens.NEWLINE -> {
                resolvedKinds[k] = SyntaxTokenKind.Whitespace
                excludedFromDelim[k] = true
            }
            MdTokens.HTML_BLOCK_TEXT -> {
                resolvedKinds[k] = SyntaxTokenKind.Plain
                excludedFromDelim[k] = true
            }
            MdTokens.ATX_HEADING_OPEN,
            MdTokens.BLOCKQUOTE_MARKER,
            MdTokens.LIST_MARKER,
            MdTokens.PIPE,
            -> {
                resolvedKinds[k] = SyntaxTokenKind.Punctuation
                excludedFromDelim[k] = true
            }
            MdTokens.INLINE_CODE -> {
                resolvedKinds[k] = SyntaxTokenKind.String
                excludedFromDelim[k] = true
            }
            MdTokens.ESCAPE,
            MdTokens.ENTITY_REF,
            -> {
                resolvedKinds[k] = SyntaxTokenKind.Constant
                excludedFromDelim[k] = true
            }
            MdTokens.HTML_COMMENT -> {
                resolvedKinds[k] = SyntaxTokenKind.Comment
                excludedFromDelim[k] = true
            }
            MdTokens.HTML_TAG_OPEN,
            MdTokens.HTML_TAG_SLASH,
            MdTokens.HTML_TAG_CLOSE,
            -> {
                resolvedKinds[k] = SyntaxTokenKind.Punctuation
                excludedFromDelim[k] = true
            }
            MdTokens.HTML_TAG_EQ -> {
                resolvedKinds[k] = SyntaxTokenKind.Operator
                excludedFromDelim[k] = true
            }
            MdTokens.HTML_NAME -> {
                resolvedKinds[k] = SyntaxTokenKind.Tag
                excludedFromDelim[k] = true
            }
            MdTokens.HTML_ATTR_NAME -> {
                resolvedKinds[k] = SyntaxTokenKind.Attribute
                excludedFromDelim[k] = true
            }
            MdTokens.HTML_ATTR_VALUE -> {
                resolvedKinds[k] = SyntaxTokenKind.String
                excludedFromDelim[k] = true
            }
            MdTokens.HTML_TAG_ERROR -> {
                resolvedKinds[k] = SyntaxTokenKind.Invalid
                excludedFromDelim[k] = true
            }
            MdTokens.TASK_CHECKBOX,
            MdTokens.AUTOLINK,
            -> {
                excludedFromDelim[k] = true
            }
        }
    }

    // 3. O(N) stack precomputation for LBRACK..RBRACK and memoized CommonMark link parens scanner
    val bracketMatch = HashMap<Int, Int>()
    val bracketStack = ArrayList<Int>()
    for (k in 0 until n) {
        if (excludedFromDelim[k]) continue
        when (raw[k].type) {
            MdTokens.LBRACK, MdTokens.BANG_LBRACK -> bracketStack.add(k)
            MdTokens.RBRACK -> if (bracketStack.isNotEmpty()) {
                bracketMatch[bracketStack.removeAt(bracketStack.lastIndex)] = k
            }
        }
    }
    val linkParensScanner = LinkParensScanner(source, raw)
    val nextBracketIdx = IntArray(n + 1)
    var nextBr = n
    for (k in n - 1 downTo 0) {
        if (!excludedFromDelim[k]) {
            val t = raw[k].type
            if (t == MdTokens.LBRACK || t == MdTokens.BANG_LBRACK || t == MdTokens.RBRACK) {
                nextBr = k
            }
        }
        nextBracketIdx[k] = nextBr
    }
    nextBracketIdx[n] = n

    // 4. Link reference definitions ([ref]: url "title") and footnote definitions ([^note]: ...)
    val customSplitTokens = HashMap<Int, List<SyntaxToken>>()
    for (line in lines) {
        if (line.isBlank) continue
        var firstContent = line.startIdx
        while (firstContent < line.endExclNl &&
            (raw[firstContent].type == MdTokens.WS || raw[firstContent].type == MdTokens.BLOCKQUOTE_MARKER)
        ) {
            firstContent++
        }
        if (firstContent < line.endExclNl && raw[firstContent].type == MdTokens.LBRACK) {
            val closeIdx = bracketMatch[firstContent]
            if (closeIdx != null && closeIdx < line.endExclNl && closeIdx + 1 < line.endExclNl &&
                raw[closeIdx + 1].type == MdTokens.COLON &&
                (closeIdx + 2 >= line.endExclNl || raw[closeIdx + 2].type == MdTokens.WS)
            ) {
                line.isNonPara = true
                resolvedKinds[firstContent] = SyntaxTokenKind.Punctuation
                resolvedKinds[closeIdx] = SyntaxTokenKind.Punctuation
                resolvedKinds[closeIdx + 1] = SyntaxTokenKind.Punctuation
                excludedFromDelim[firstContent] = true
                excludedFromDelim[closeIdx] = true
                excludedFromDelim[closeIdx + 1] = true

                val isFootnote = firstContent + 1 < closeIdx && source[raw[firstContent + 1].start] == '^'
                for (inner in (firstContent + 1) until closeIdx) {
                    excludedFromDelim[inner] = true
                    if (resolvedKinds[inner] == null) {
                        resolvedKinds[inner] = SyntaxTokenKind.Constant
                    }
                }

                if (!isFootnote) {
                    var p = closeIdx + 2
                    while (p < line.endExclNl && raw[p].type == MdTokens.WS) p++
                    if (p < line.endExclNl) {
                        var urlEndTok = p
                        while (urlEndTok < line.endExclNl && raw[urlEndTok].type != MdTokens.WS) {
                            excludedFromDelim[urlEndTok] = true
                            if (raw[urlEndTok].type != MdTokens.AUTOLINK && resolvedKinds[urlEndTok] == null) {
                                resolvedKinds[urlEndTok] = SyntaxTokenKind.Attribute
                            }
                            urlEndTok++
                        }
                        var tStart = urlEndTok
                        while (tStart < line.endExclNl && raw[tStart].type == MdTokens.WS) tStart++
                        if (tStart < line.endExclNl) {
                            val titleFirstChar = source[raw[tStart].start]
                            if (titleFirstChar == '"' || titleFirstChar == '\'') {
                                for (tk in tStart until line.endExclNl) {
                                    excludedFromDelim[tk] = true
                                    if (resolvedKinds[tk] == null) {
                                        resolvedKinds[tk] = SyntaxTokenKind.String
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Mark only contiguous trailing paragraph lines immediately preceding a Setext underline.
    if (followedBySetext && lines.isNotEmpty()) {
        var lineIdx = lines.lastIndex
        while (lineIdx >= 0 && lines[lineIdx].isBlank) {
            lineIdx--
        }
        if (lineIdx >= 0 && !lines[lineIdx].isNonPara) {
            val targetBqDepth = lines[lineIdx].bqDepth
            while (lineIdx >= 0) {
                val lm = lines[lineIdx]
                if (lm.isNonPara || lm.bqDepth != targetBqDepth) break
                for (k in lm.startIdx until lm.endExclNl) {
                    inSetextHeadingLine[k] = true
                }
                lineIdx--
            }
        }
    }

    // 5. O(N) Inline links [text](url "title"), images ![alt](url), reference links [text][ref], footnotes [^note]
    val activeLinkStack = ArrayList<Int>()
    var minActiveLbrackIdx = -1
    val linkTextMask = IntArray(n + 1)
    var idx = 0
    while (idx < n) {
        if (excludedFromDelim[idx]) {
            idx++
            continue
        }
        val t = raw[idx].type
        if (t == MdTokens.LBRACK || t == MdTokens.BANG_LBRACK) {
            activeLinkStack.add(idx)
            idx++
            continue
        }
        if (t == MdTokens.RBRACK && activeLinkStack.isNotEmpty()) {
            val openIdx = activeLinkStack.removeAt(activeLinkStack.lastIndex)
            val isImage = raw[openIdx].type == MdTokens.BANG_LBRACK
            if (!isImage && openIdx < minActiveLbrackIdx) {
                idx++
                continue
            }
            val afterIdx = idx + 1

            // Case A: [text](url "title") or ![alt](url)
            if (afterIdx < n && raw[afterIdx].type == MdTokens.LPAREN) {
                val closeParenIdx = linkParensScanner.scan(afterIdx)
                if (closeParenIdx >= 0) {
                    resolvedKinds[openIdx] = SyntaxTokenKind.Punctuation
                    resolvedKinds[idx] = SyntaxTokenKind.Punctuation
                    resolvedKinds[afterIdx] = SyntaxTokenKind.Punctuation
                    resolvedKinds[closeParenIdx] = SyntaxTokenKind.Punctuation
                    excludedFromDelim[openIdx] = true
                    excludedFromDelim[idx] = true
                    excludedFromDelim[afterIdx] = true
                    excludedFromDelim[closeParenIdx] = true

                    linkTextMask[openIdx + 1]++
                    linkTextMask[idx]--
                    classifyLinkParensBody(source, raw, afterIdx + 1, closeParenIdx, resolvedKinds, excludedFromDelim, customSplitTokens)

                    if (!isImage && openIdx > minActiveLbrackIdx) {
                        minActiveLbrackIdx = openIdx
                    }
                    idx = closeParenIdx + 1
                    continue
                }
            }

            // Case B: [text][ref] or [ref][]
            if (afterIdx < n && raw[afterIdx].type == MdTokens.LBRACK) {
                val refCloseIdx = bracketMatch[afterIdx]
                if (refCloseIdx != null) {
                    resolvedKinds[openIdx] = SyntaxTokenKind.Punctuation
                    resolvedKinds[idx] = SyntaxTokenKind.Punctuation
                    resolvedKinds[afterIdx] = SyntaxTokenKind.Punctuation
                    resolvedKinds[refCloseIdx] = SyntaxTokenKind.Punctuation
                    excludedFromDelim[openIdx] = true
                    excludedFromDelim[idx] = true
                    excludedFromDelim[afterIdx] = true
                    excludedFromDelim[refCloseIdx] = true

                    linkTextMask[openIdx + 1]++
                    linkTextMask[idx]--
                    for (refInner in (afterIdx + 1) until refCloseIdx) {
                        excludedFromDelim[refInner] = true
                        if (resolvedKinds[refInner] == null) {
                            resolvedKinds[refInner] = SyntaxTokenKind.Constant
                        }
                    }
                    if (!isImage && openIdx > minActiveLbrackIdx) {
                        minActiveLbrackIdx = openIdx
                    }
                    idx = refCloseIdx + 1
                    continue
                }
            }

            // Case C: Footnote reference [^note] (disjoint, no inner brackets or whitespace)
            if (!isImage &&
                openIdx + 1 < idx &&
                source[raw[openIdx + 1].start] == '^' &&
                nextBracketIdx[openIdx + 1] == idx &&
                linkParensScanner.nextWsOrNlAt(openIdx + 1) >= idx
            ) {
                resolvedKinds[openIdx] = SyntaxTokenKind.Punctuation
                resolvedKinds[idx] = SyntaxTokenKind.Punctuation
                excludedFromDelim[openIdx] = true
                excludedFromDelim[idx] = true
                for (k in (openIdx + 1) until idx) {
                    excludedFromDelim[k] = true
                    if (resolvedKinds[k] == null) {
                        resolvedKinds[k] = SyntaxTokenKind.Constant
                    }
                }
            }
        }
        idx++
    }

    // 5.5 Strictly O(N) GFM extended autolinks (www., http://, https://, bare email)
    val autolinkPartialSpans = resolveGfmExtendedAutolinks(
        source = source,
        raw = raw,
        linkTextMask = linkTextMask,
        resolvedKinds = resolvedKinds,
        excludedFromDelim = excludedFromDelim,
    )

    // 6. Strictly O(N) emphasis & strikethrough delimiter resolution using CommonMark openersBottom
    resolveEmphasisDelimiters(source, raw, resolvedKinds, excludedFromDelim, customSplitTokens)

    // 7. Emit all tokens in exact order
    var linkTextDepth = 0
    for (k in 0 until n) {
        linkTextDepth += linkTextMask[k]
        val custom = customSplitTokens[k]
        if (custom != null) {
            out.addAll(custom)
            continue
        }
        val tok = raw[k]
        when (tok.type) {
            MdTokens.TASK_CHECKBOX -> {
                val midChar = source[tok.start + 1]
                out += SyntaxToken(SyntaxTokenKind.Punctuation, tok.start, tok.start + 1)
                out += SyntaxToken(
                    kind = if (midChar == ' ') SyntaxTokenKind.Whitespace else SyntaxTokenKind.Constant,
                    startIndex = tok.start + 1,
                    endIndex = tok.start + 2,
                )
                out += SyntaxToken(SyntaxTokenKind.Punctuation, tok.start + 2, tok.end)
            }

            MdTokens.AUTOLINK -> {
                out += SyntaxToken(SyntaxTokenKind.Punctuation, tok.start, tok.start + 1)
                out += SyntaxToken(SyntaxTokenKind.Attribute, tok.start + 1, tok.end - 1)
                out += SyntaxToken(SyntaxTokenKind.Punctuation, tok.end - 1, tok.end)
            }

            else -> {
                val defaultTextKind = when {
                    linkTextDepth > 0 -> SyntaxTokenKind.Tag
                    inAtxHeadingLine[k] || inSetextHeadingLine[k] -> SyntaxTokenKind.Keyword
                    else -> SyntaxTokenKind.Plain
                }
                val kind = resolvedKinds[k] ?: defaultTextKind
                val partialSpans = autolinkPartialSpans[k]
                if (partialSpans == null) {
                    out += SyntaxToken(kind, tok.start, tok.end)
                } else {
                    var cursor = tok.start
                    for ((aStart, aEnd) in partialSpans) {
                        if (cursor < aStart) {
                            out += SyntaxToken(kind, cursor, aStart)
                        }
                        out += SyntaxToken(SyntaxTokenKind.Attribute, aStart, aEnd)
                        cursor = aEnd
                    }
                    if (cursor < tok.end) {
                        out += SyntaxToken(kind, cursor, tok.end)
                    }
                }
            }
        }
    }
}

private class LinkParensScanner(
    private val source: String,
    private val raw: List<RawMdToken>,
) {
    private val n = raw.size
    private val nextWsOrNl = IntArray(n + 1)
    private val bareParenEnd = HashMap<Int, Int>()

    private var noDQuoteCloseFrom = Int.MAX_VALUE
    private var noSQuoteCloseFrom = Int.MAX_VALUE
    private var noGtCloseFrom = Int.MAX_VALUE
    private var noParenTitleCloseFrom = Int.MAX_VALUE

    init {
        var nextWs = n
        for (k in n - 1 downTo 0) {
            val t = raw[k].type
            if (t == MdTokens.WS || t == MdTokens.NEWLINE) {
                nextWs = k
            }
            nextWsOrNl[k] = nextWs
        }
        nextWsOrNl[n] = n

        val stack = ArrayList<Int>()
        for (k in 0 until n) {
            when (raw[k].type) {
                MdTokens.WS, MdTokens.NEWLINE -> stack.clear()
                MdTokens.LPAREN -> stack.add(k)
                MdTokens.RPAREN -> if (stack.isNotEmpty()) {
                    bareParenEnd[stack.removeAt(stack.lastIndex)] = k
                }
            }
        }
    }

    fun nextWsOrNlAt(idx: Int): Int = nextWsOrNl[idx]

    fun scan(openParenIdx: Int): Int {
        var k = openParenIdx + 1
        while (k < n && (raw[k].type == MdTokens.WS || raw[k].type == MdTokens.NEWLINE)) {
            k++
        }
        if (k >= n) return -1
        if (raw[k].type == MdTokens.RPAREN) return k

        var hasDestination = false
        val firstTok = raw[k]
        val firstChar = source[firstTok.start]
        if (firstTok.type == MdTokens.AUTOLINK) {
            hasDestination = true
            k++
        } else if (firstTok.type == MdTokens.HTML_TAG_OPEN) {
            hasDestination = true
            k++
            while (k < n && raw[k].type != MdTokens.HTML_TAG_CLOSE) {
                if (raw[k].type == MdTokens.NEWLINE) return -1
                k++
            }
            if (k >= n) return -1
            k++
        } else if (firstChar == '<') {
            hasDestination = true
            val gtEnd = scanPointyDestination(k)
            if (gtEnd < 0) return -1
            k = gtEnd
        } else {
            val wsIdx = nextWsOrNl[k]
            var p = k
            var closedDirectly = -1
            while (p < wsIdx) {
                when (raw[p].type) {
                    MdTokens.LPAREN -> {
                        val match = bareParenEnd[p] ?: return -1
                        p = match + 1
                    }
                    MdTokens.RPAREN -> {
                        closedDirectly = p
                        break
                    }
                    else -> p++
                }
            }
            if (closedDirectly >= 0) return closedDirectly
            if (p == k) return -1
            hasDestination = true
            k = wsIdx
        }

        if (k >= n) return -1
        if (raw[k].type == MdTokens.RPAREN) return k

        var hadWs = false
        while (k < n && (raw[k].type == MdTokens.WS || raw[k].type == MdTokens.NEWLINE)) {
            hadWs = true
            k++
        }
        if (k >= n) return -1
        if (raw[k].type == MdTokens.RPAREN) return k
        if (hasDestination && !hadWs) return -1

        // Optional title: "...", '...', or (...)
        val titleTok = raw[k]
        val titleCh = source[titleTok.start]
        val afterTitleIdx = when {
            titleTok.type == MdTokens.LPAREN -> scanParenTitle(k)
            titleCh == '"' -> scanQuotedTitle(k, '"', isDouble = true)
            titleCh == '\'' -> scanQuotedTitle(k, '\'', isDouble = false)
            else -> -1
        }
        if (afterTitleIdx < 0) return -1

        var tail = afterTitleIdx
        while (tail < n && (raw[tail].type == MdTokens.WS || raw[tail].type == MdTokens.NEWLINE)) {
            tail++
        }
        return if (tail < n && raw[tail].type == MdTokens.RPAREN) tail else -1
    }

    private fun scanPointyDestination(startTokIdx: Int): Int {
        val startOffset = raw[startTokIdx].start
        if (startOffset >= noGtCloseFrom) return -1
        var k = startTokIdx
        while (k < n) {
            val tok = raw[k]
            if (tok.type == MdTokens.NEWLINE) {
                return -1
            }
            if (tok.type != MdTokens.ESCAPE && tok.type != MdTokens.WS) {
                val startInTok = if (k == startTokIdx) tok.start + 1 else tok.start
                for (p in startInTok until tok.end) {
                    val ch = source[p]
                    if (ch == '>') {
                        return if (p == tok.end - 1) k + 1 else -1
                    }
                    if (ch == '<') {
                        return -1
                    }
                }
            }
            k++
        }
        noGtCloseFrom = minOf(noGtCloseFrom, startOffset)
        return -1
    }

    private fun scanQuotedTitle(startTokIdx: Int, quote: Char, isDouble: Boolean): Int {
        val startOffset = raw[startTokIdx].start
        val limit = if (isDouble) noDQuoteCloseFrom else noSQuoteCloseFrom
        if (startOffset >= limit) return -1

        var k = startTokIdx
        while (k < n) {
            val tok = raw[k]
            if (tok.type == MdTokens.TEXT) {
                val searchFrom = if (k == startTokIdx) tok.start + 1 else tok.start
                for (p in searchFrom until tok.end) {
                    if (source[p] == quote) {
                        return if (p == tok.end - 1) k + 1 else -1
                    }
                }
            }
            k++
        }
        if (isDouble) {
            noDQuoteCloseFrom = minOf(noDQuoteCloseFrom, startOffset)
        } else {
            noSQuoteCloseFrom = minOf(noSQuoteCloseFrom, startOffset)
        }
        return -1
    }

    private fun scanParenTitle(startTokIdx: Int): Int {
        val startOffset = raw[startTokIdx].start
        if (startOffset >= noParenTitleCloseFrom) return -1
        var k = startTokIdx + 1
        while (k < n) {
            when (raw[k].type) {
                MdTokens.LPAREN -> return -1
                MdTokens.RPAREN -> return k + 1
                else -> k++
            }
        }
        noParenTitleCloseFrom = minOf(noParenTitleCloseFrom, startOffset)
        return -1
    }
}

private fun isGfmTableDelimiterRow(
    source: String,
    raw: List<RawMdToken>,
    start: Int,
    end: Int,
): Boolean {
    if (start >= end) return false
    var hasPipe = false
    var hasDash = false
    for (k in start until end) {
        val tok = raw[k]
        when (tok.type) {
            MdTokens.WS -> Unit
            MdTokens.PIPE -> hasPipe = true
            MdTokens.COLON -> Unit
            MdTokens.TEXT -> {
                for (p in tok.start until tok.end) {
                    val ch = source[p]
                    if (ch == '-') {
                        hasDash = true
                    } else if (ch != ':') {
                        return false
                    }
                }
            }
            else -> return false
        }
    }
    return hasPipe && hasDash
}

private fun classifyLinkParensBody(
    source: String,
    raw: List<RawMdToken>,
    bodyStartIdx: Int,
    bodyEndIdx: Int,
    resolvedKinds: Array<SyntaxTokenKind?>,
    excludedFromDelim: BooleanArray,
    customSplitTokens: MutableMap<Int, List<SyntaxToken>>,
) {
    // Classify URL tokens up to title quote or end; title quotes as String.
    var k = bodyStartIdx
    while (k < bodyEndIdx && raw[k].type == MdTokens.WS) {
        excludedFromDelim[k] = true
        k++
    }

    var inTitleQuote: Char? = null
    var inPointyDest = false
    while (k < bodyEndIdx) {
        val tok = raw[k]
        excludedFromDelim[k] = true
        if (tok.type == MdTokens.WS || tok.type == MdTokens.NEWLINE) {
            resolvedKinds[k] = SyntaxTokenKind.Whitespace
            k++
            continue
        }
        if (inTitleQuote != null) {
            resolvedKinds[k] = SyntaxTokenKind.String
            if (tok.type == MdTokens.TEXT && source.substring(tok.start, tok.end).contains(inTitleQuote)) {
                inTitleQuote = null
            }
            k++
            continue
        }
        if (inPointyDest) {
            if (tok.type == MdTokens.HTML_TAG_CLOSE || (tok.type == MdTokens.TEXT && source[tok.end - 1] == '>')) {
                if (tok.type == MdTokens.HTML_TAG_CLOSE || tok.end - tok.start == 1) {
                    resolvedKinds[k] = SyntaxTokenKind.Punctuation
                } else {
                    customSplitTokens[k] = listOf(
                        SyntaxToken(SyntaxTokenKind.Attribute, tok.start, tok.end - 1),
                        SyntaxToken(SyntaxTokenKind.Punctuation, tok.end - 1, tok.end),
                    )
                }
                inPointyDest = false
            } else {
                resolvedKinds[k] = SyntaxTokenKind.Attribute
            }
            k++
            continue
        }

        val ch0 = source[tok.start]
        if (tok.type == MdTokens.AUTOLINK) {
            customSplitTokens[k] = listOf(
                SyntaxToken(SyntaxTokenKind.Punctuation, tok.start, tok.start + 1),
                SyntaxToken(SyntaxTokenKind.Attribute, tok.start + 1, tok.end - 1),
                SyntaxToken(SyntaxTokenKind.Punctuation, tok.end - 1, tok.end),
            )
            k++
            continue
        }
        if (tok.type == MdTokens.HTML_TAG_OPEN) {
            resolvedKinds[k] = SyntaxTokenKind.Punctuation
            inPointyDest = true
            k++
            continue
        }
        if (ch0 == '<') {
            val endsWithGt = tok.end - tok.start > 1 && source[tok.end - 1] == '>'
            if (tok.end - tok.start == 1) {
                resolvedKinds[k] = SyntaxTokenKind.Punctuation
                inPointyDest = true
            } else if (endsWithGt) {
                val parts = ArrayList<SyntaxToken>(3)
                parts += SyntaxToken(SyntaxTokenKind.Punctuation, tok.start, tok.start + 1)
                if (tok.start + 1 < tok.end - 1) {
                    parts += SyntaxToken(SyntaxTokenKind.Attribute, tok.start + 1, tok.end - 1)
                }
                parts += SyntaxToken(SyntaxTokenKind.Punctuation, tok.end - 1, tok.end)
                customSplitTokens[k] = parts
                inPointyDest = false
            } else {
                customSplitTokens[k] = listOf(
                    SyntaxToken(SyntaxTokenKind.Punctuation, tok.start, tok.start + 1),
                    SyntaxToken(SyntaxTokenKind.Attribute, tok.start + 1, tok.end),
                )
                inPointyDest = true
            }
            k++
            continue
        }

        if (ch0 == '"' || ch0 == '\'') {
            resolvedKinds[k] = SyntaxTokenKind.String
            val slice = source.substring(tok.start, tok.end)
            if (slice.length == 1 || slice.indexOf(ch0, startIndex = 1) < 0) {
                inTitleQuote = ch0
            }
            k++
            continue
        }

        if (tok.type == MdTokens.HTML_TAG_CLOSE) {
            resolvedKinds[k] = SyntaxTokenKind.Punctuation
            inPointyDest = false
        } else if (tok.type == MdTokens.HTML_NAME ||
            tok.type == MdTokens.HTML_ATTR_NAME ||
            tok.type == MdTokens.HTML_ATTR_VALUE ||
            resolvedKinds[k] == null
        ) {
            resolvedKinds[k] = SyntaxTokenKind.Attribute
        }
        k++
    }
}

private class DelimEntry(
    val tokIdx: Int,
    val char: Char,
    val origLen: Int,
    var len: Int,
    val canOpen: Boolean,
    val canClose: Boolean,
)

private fun resolveEmphasisDelimiters(
    source: String,
    raw: List<RawMdToken>,
    resolvedKinds: Array<SyntaxTokenKind?>,
    excludedFromDelim: BooleanArray,
    customSplitTokens: MutableMap<Int, List<SyntaxToken>>,
) {
    val n = raw.size
    val stack = ArrayList<DelimEntry>()
    // openersBottom[kindSlot]: 0..5 for '*', 6..11 for '_', 12..15 for '~' (single vs double)
    val openersBottom = IntArray(16) { -1 }

    // Track per-delimiter-token matched mask (left-consumed / right-consumed)
    val matchedLeft = IntArray(n)
    val matchedRight = IntArray(n)
    val boldMask = IntArray(n)
    val italicMask = IntArray(n)
    val strikeMask = IntArray(n)

    for (k in 0 until n) {
        if (excludedFromDelim[k]) continue
        val tok = raw[k]
        val delimChar = when (tok.type) {
            MdTokens.STAR_RUN -> '*'
            MdTokens.UNDERSCORE_RUN -> '_'
            MdTokens.TILDE_RUN -> '~'
            else -> continue
        }
        val len = tok.end - tok.start
        // Per GFM §6.5, only 1 or 2 tildes can open or close strikethrough.
        if (delimChar == '~' && len > 2) continue
        val (canOpen, canClose) = computeFlanking(source, tok.start, tok.end, delimChar)
        if (!canOpen && !canClose) continue

        if (canClose) {
            var remainingClose = len
            while (remainingClose > 0) {
                val bottomSlot = when (delimChar) {
                    '*' -> (if (canOpen) 3 else 0) + (len % 3)
                    '_' -> 6 + (if (canOpen) 3 else 0) + (len % 3)
                    else -> 12 + (if (len == 2) 2 else 0) + (if (canOpen) 1 else 0)
                }
                val bottom = openersBottom[bottomSlot]
                var openerStackIdx = stack.size - 1
                var found = false
                while (openerStackIdx > bottom) {
                    val opener = stack[openerStackIdx]
                    if (opener.canOpen && opener.char == delimChar && opener.len > 0) {
                        val blocked = if (delimChar == '~') {
                            opener.origLen != len
                        } else {
                            ((opener.canOpen && opener.canClose) || (canOpen && canClose)) &&
                                (opener.origLen + len) % 3 == 0 &&
                                !(opener.origLen % 3 == 0 && len % 3 == 0)
                        }
                        if (!blocked) {
                            found = true
                            break
                        }
                    }
                    openerStackIdx--
                }
                if (!found) {
                    openersBottom[bottomSlot] = stack.size - 1
                    break
                }

                val opener = stack[openerStackIdx]
                val useCount = when {
                    delimChar == '~' -> len
                    opener.len >= 2 && remainingClose >= 2 -> 2
                    else -> 1
                }
                opener.len -= useCount
                remainingClose -= useCount
                matchedRight[opener.tokIdx] += useCount
                matchedLeft[k] += useCount

                when {
                    delimChar == '~' -> {
                        strikeMask[opener.tokIdx + 1]++
                        strikeMask[k]--
                    }
                    useCount == 2 -> {
                        boldMask[opener.tokIdx + 1]++
                        boldMask[k]--
                    }
                    else -> {
                        italicMask[opener.tokIdx + 1]++
                        italicMask[k]--
                    }
                }
                // Shrink stack in place on match and clamp openersBottom in O(1) so dead entries are never re-scanned.
                val newSize = if (opener.len == 0) openerStackIdx else openerStackIdx + 1
                while (stack.size > newSize) {
                    stack.removeAt(stack.lastIndex)
                }
                for (s in openersBottom.indices) {
                    if (openersBottom[s] >= newSize) {
                        openersBottom[s] = newSize - 1
                    }
                }
            }

            if (remainingClose > 0 && canOpen) {
                stack.add(DelimEntry(k, delimChar, len, remainingClose, canOpen = true, canClose = canClose))
            }
        } else if (canOpen) {
            stack.add(DelimEntry(k, delimChar, len, len, canOpen = true, canClose = false))
        }
    }

    // Apply bold/italic/strike masks and split partially matched delimiter runs
    var boldDepth = 0
    var italicDepth = 0
    var strikeDepth = 0
    for (k in 0 until n) {
        boldDepth += boldMask[k]
        italicDepth += italicMask[k]
        strikeDepth += strikeMask[k]

        val tok = raw[k]
        val ml = matchedLeft[k]
        val mr = matchedRight[k]
        if (ml > 0 || mr > 0) {
            val totalLen = tok.end - tok.start
            val parts = ArrayList<SyntaxToken>(3)
            val leftEnd = tok.start + ml
            val rightStart = (tok.end - mr).coerceAtLeast(leftEnd)
            if (ml > 0) {
                parts += SyntaxToken(SyntaxTokenKind.Punctuation, tok.start, leftEnd)
            }
            if (leftEnd < rightStart) {
                val midKind = activeEmphasisKind(boldDepth, italicDepth, strikeDepth) ?: SyntaxTokenKind.Plain
                parts += SyntaxToken(midKind, leftEnd, rightStart)
            }
            if (mr > 0 && rightStart < tok.end) {
                parts += SyntaxToken(SyntaxTokenKind.Punctuation, rightStart, tok.end)
            } else if (ml + mr > totalLen && parts.isEmpty()) {
                parts += SyntaxToken(SyntaxTokenKind.Punctuation, tok.start, tok.end)
            }
            customSplitTokens[k] = parts
        } else if (resolvedKinds[k] == null) {
            val empKind = activeEmphasisKind(boldDepth, italicDepth, strikeDepth)
            if (empKind != null) {
                resolvedKinds[k] = empKind
            }
        }
    }
}

private fun activeEmphasisKind(boldDepth: Int, italicDepth: Int, strikeDepth: Int): SyntaxTokenKind? = when {
    strikeDepth > 0 -> SyntaxTokenKind.Comment
    boldDepth > 0 -> SyntaxTokenKind.Constant
    italicDepth > 0 -> SyntaxTokenKind.Property
    else -> null
}

private fun computeFlanking(source: String, start: Int, end: Int, delimChar: Char): Pair<Boolean, Boolean> {
    val before = codePointBefore(source, start)
    val after = codePointAt(source, end)
    val wsBefore = isMdWhitespaceCp(before)
    val wsAfter = isMdWhitespaceCp(after)
    val punctBefore = !wsBefore && isMdPunctuationCp(before)
    val punctAfter = !wsAfter && isMdPunctuationCp(after)

    val leftFlanking = !wsAfter && (!punctAfter || wsBefore || punctBefore)
    val rightFlanking = !wsBefore && (!punctBefore || wsAfter || punctAfter)
    return when (delimChar) {
        '_' -> (leftFlanking && (!rightFlanking || punctBefore)) to
            (rightFlanking && (!leftFlanking || punctAfter))
        else -> leftFlanking to rightFlanking
    }
}

private fun codePointBefore(source: String, index: Int): Int {
    if (index <= 0) return '\n'.code
    val lo = source[index - 1]
    if (lo.isLowSurrogate() && index - 2 >= 0 && source[index - 2].isHighSurrogate()) {
        val hi = source[index - 2]
        return ((hi.code - 0xD800) shl 10) + (lo.code - 0xDC00) + 0x10000
    }
    return lo.code
}

private fun codePointAt(source: String, index: Int): Int {
    if (index >= source.length) return '\n'.code
    val hi = source[index]
    if (hi.isHighSurrogate() && index + 1 < source.length && source[index + 1].isLowSurrogate()) {
        val lo = source[index + 1]
        return ((hi.code - 0xD800) shl 10) + (lo.code - 0xDC00) + 0x10000
    }
    return hi.code
}

private fun isMdWhitespaceCp(cp: Int): Boolean =
    cp == ' '.code || cp == '\t'.code || cp == '\r'.code || cp == '\n'.code || cp == 0x00A0 ||
        (cp in 0..0xFFFF && isAsciiWhitespace(cp.toChar())) ||
        (cp in 0..0xFFFF && cp.toChar().isWhitespace())

private fun isMdPunctuationCp(cp: Int): Boolean {
    if (cp in 0x21..0x2F || cp in 0x3A..0x40 || cp in 0x5B..0x60 || cp in 0x7B..0x7E) {
        return true
    }
    if (cp in 0x00A1..0x00BF ||
        cp in 0x2000..0x206F ||
        cp in 0x2E00..0x2E7F ||
        cp in 0x3001..0x303F ||
        cp in 0xFE10..0xFE6F ||
        cp in 0xFF01..0xFF65
    ) {
        return true
    }
    if (cp in 0..0xFFFF) {
        return when (cp.toChar().category) {
            CharCategory.CONNECTOR_PUNCTUATION,
            CharCategory.DASH_PUNCTUATION,
            CharCategory.END_PUNCTUATION,
            CharCategory.FINAL_QUOTE_PUNCTUATION,
            CharCategory.INITIAL_QUOTE_PUNCTUATION,
            CharCategory.OTHER_PUNCTUATION,
            CharCategory.START_PUNCTUATION,
            -> true
            else -> false
        }
    }
    return false
}

private fun resolveGfmExtendedAutolinks(
    source: String,
    raw: List<RawMdToken>,
    linkTextMask: IntArray,
    resolvedKinds: Array<SyntaxTokenKind?>,
    excludedFromDelim: BooleanArray,
): Map<Int, List<Pair<Int, Int>>> {
    val n = raw.size
    if (n == 0) return emptyMap()
    val partialSpans = HashMap<Int, ArrayList<Pair<Int, Int>>>()
    var linkDepth = 0
    var t = 0
    while (t < n) {
        linkDepth += linkTextMask[t]
        if (excludedFromDelim[t] || linkDepth > 0 || resolvedKinds[t] != null) {
            t++
            continue
        }
        val runStartTok = t
        var runEndTok = t + 1
        while (runEndTok < n) {
            val nextLinkDepth = linkDepth + linkTextMask[runEndTok]
            if (nextLinkDepth > 0 ||
                excludedFromDelim[runEndTok] ||
                resolvedKinds[runEndTok] != null ||
                raw[runEndTok].start != raw[runEndTok - 1].end
            ) {
                break
            }
            linkDepth = nextLinkDepth
            runEndTok++
        }

        val segStart = raw[runStartTok].start
        val segEnd = raw[runEndTok - 1].end
        var tokCursor = runStartTok
        var pos = segStart
        var emailScanBarrier = -1

        while (pos < segEnd) {
            var matchEnd = -1
            val urlAllowedBefore = pos == segStart || isGfmUrlBoundaryBefore(source[pos - 1])
            if (urlAllowedBefore) {
                matchEnd = matchGfmUrlEnd(source, pos, segEnd)
            }
            if (matchEnd < 0 && pos >= emailScanBarrier && isGfmEmailLocalChar(source[pos])) {
                val validBefore = pos == segStart || !isGfmEmailLocalChar(source[pos - 1])
                if (validBefore) {
                    val (emailEnd, localEnd) = matchGfmEmailEnd(source, pos, segEnd)
                    if (emailEnd > pos) {
                        matchEnd = emailEnd
                    } else {
                        emailScanBarrier = localEnd + 1
                    }
                }
            }
            if (matchEnd > pos) {
                while (tokCursor < runEndTok && raw[tokCursor].end <= pos) {
                    tokCursor++
                }
                var k = tokCursor
                while (k < runEndTok && raw[k].start < matchEnd) {
                    val tok = raw[k]
                    if (tok.start >= pos && tok.end <= matchEnd) {
                        resolvedKinds[k] = SyntaxTokenKind.Attribute
                        excludedFromDelim[k] = true
                    } else {
                        val s = maxOf(tok.start, pos)
                        val e = minOf(tok.end, matchEnd)
                        if (e > s) {
                            partialSpans.getOrPut(k) { ArrayList(2) }.add(s to e)
                        }
                    }
                    k++
                }
                tokCursor = if (k > tokCursor) k - 1 else tokCursor
                pos = matchEnd
            } else {
                pos++
            }
        }
        t = runEndTok
    }
    return partialSpans
}

private fun isGfmUrlBoundaryBefore(ch: Char): Boolean =
    ch == '*' || ch == '_' || ch == '~' || ch == '('

private fun isGfmEmailLocalChar(ch: Char): Boolean =
    ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch == '.' || ch == '+' || ch == '_' || ch == '-'

private fun isGfmDomainChar(ch: Char): Boolean =
    ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch == '_' || ch == '-'

private fun isGfmAsciiAlnum(ch: Char): Boolean =
    ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9'

private fun matchGfmUrlEnd(source: String, start: Int, segEnd: Int): Int {
    val domStart = when {
        startsWithIgnoreCase(source, start, segEnd, "https://") -> start + 8
        startsWithIgnoreCase(source, start, segEnd, "http://") -> start + 7
        source.regionMatches(start, "www.", 0, 4, ignoreCase = false) -> start + 4
        else -> return -1
    }
    if (domStart >= segEnd) return -1

    var p = domStart
    var dotCount = 0
    var prevSegUnder = false
    var curSegUnder = false
    var curSegLen = 0
    var domEnd = -1

    while (p < segEnd) {
        val ch = source[p]
        if (ch == '.') {
            if (curSegLen == 0) break
            // Peek ahead: if next char starts a valid segment, advance dot; else domain ends before '.'
            if (p + 1 >= segEnd || !isGfmDomainChar(source[p + 1])) {
                domEnd = p
                break
            }
            dotCount++
            prevSegUnder = curSegUnder
            curSegUnder = false
            curSegLen = 0
            p++
        } else if (isGfmDomainChar(ch)) {
            if (ch == '_') curSegUnder = true
            curSegLen++
            p++
            domEnd = p
        } else {
            if (curSegLen > 0) {
                domEnd = p
            }
            break
        }
    }

    if (domEnd <= domStart || dotCount < 1 || curSegLen == 0 || prevSegUnder || curSegUnder) {
        return -1
    }

    var end = domEnd
    var openParens = 0
    var closeParens = 0
    while (end < segEnd) {
        val ch = source[end]
        if (ch == '<') break
        if (ch == '(') openParens++ else if (ch == ')') closeParens++
        end++
    }

    while (end > domEnd) {
        val last = source[end - 1]
        if (last == '?' || last == '!' || last == '.' || last == ',' || last == ':' ||
            last == '*' || last == '_' || last == '~' || last == '\'' || last == '"'
        ) {
            end--
        } else if (last == ')' && closeParens > openParens) {
            end--
            closeParens--
        } else {
            break
        }
    }

    return if (end >= domEnd) end else -1
}

private fun matchGfmEmailEnd(source: String, start: Int, segEnd: Int): Pair<Int, Int> {
    var localEnd = start
    while (localEnd < segEnd && isGfmEmailLocalChar(source[localEnd])) {
        localEnd++
    }
    if (localEnd == start || localEnd >= segEnd || source[localEnd] != '@') {
        return -1 to localEnd
    }
    var p = localEnd + 1
    if (p >= segEnd || !isGfmDomainChar(source[p])) {
        return -1 to localEnd
    }
    var dotCount = 0
    var curSegLen = 0
    var curLastChar = '\u0000'
    var domEnd = -1
    while (p < segEnd) {
        val ch = source[p]
        if (ch == '.') {
            if (curSegLen == 0) break
            if (p + 1 >= segEnd || !isGfmDomainChar(source[p + 1])) {
                domEnd = p
                break
            }
            dotCount++
            curSegLen = 0
            p++
        } else if (isGfmDomainChar(ch)) {
            curSegLen++
            curLastChar = ch
            p++
            domEnd = p
        } else {
            if (curSegLen > 0) domEnd = p
            break
        }
    }
    if (domEnd < 0 || dotCount < 1 || curSegLen == 0 || curLastChar == '-' || curLastChar == '_') {
        return -1 to localEnd
    }
    if (domEnd < segEnd) {
        val after = source[domEnd]
        if (isGfmAsciiAlnum(after) || after == '@' || after == '/') {
            return -1 to localEnd
        }
    }
    return domEnd to localEnd
}

private fun startsWithIgnoreCase(source: String, start: Int, segEnd: Int, literal: String): Boolean {
    if (start + literal.length > segEnd) return false
    return source.regionMatches(start, literal, 0, literal.length, ignoreCase = true)
}

private fun coalesceAdjacentTokens(tokens: List<SyntaxToken>): List<SyntaxToken> {
    if (tokens.size <= 1) return tokens
    val out = ArrayList<SyntaxToken>(tokens.size)
    var prev = tokens[0]
    for (i in 1 until tokens.size) {
        val cur = tokens[i]
        val canMerge = cur.kind == prev.kind &&
            cur.startIndex == prev.endIndex &&
            (cur.kind == SyntaxTokenKind.Plain || cur.kind == SyntaxTokenKind.Keyword || cur.kind == SyntaxTokenKind.Attribute)
        if (canMerge) {
            prev = SyntaxToken(prev.kind, prev.startIndex, cur.endIndex)
        } else {
            out += prev
            prev = cur
        }
    }
    out += prev
    return out
}
