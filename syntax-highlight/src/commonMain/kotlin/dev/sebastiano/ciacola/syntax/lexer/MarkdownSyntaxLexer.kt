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

private fun processMarkdownTokens(
    source: String,
    raw: List<RawMdToken>,
    allowNestedSubHighlighting: Boolean,
): List<SyntaxToken> {
    val out = ArrayList<SyntaxToken>(raw.size + 16)
    var currentFenceLang: SyntaxLanguage? = null
    var i = 0

    // Paragraph buffer holds contiguous inline/block-line tokens so links, Setext headings,
    // GFM tables, and emphasis can be resolved in a single O(N) pass without crossing fences.
    val paraBuffer = ArrayList<RawMdToken>()

    fun flushParagraph(followedBySetext: Boolean = false) {
        if (paraBuffer.isEmpty()) return
        emitParagraphTokens(source, paraBuffer, followedBySetext, out)
        paraBuffer.clear()
    }

    while (i < raw.size) {
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
                flushParagraph()
                currentFenceLang = null
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
                paraBuffer += tok
                // Flush at blank lines (two consecutive newlines with optional WS) or standalone comments
                if (tok.type == MdTokens.NEWLINE && isBlankLineAhead(raw, i + 1)) {
                    flushParagraph()
                }
            }
        }
        i++
    }
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

    // 1. Identify line boundaries and line-level roles (ATX headings, Setext paragraphs, GFM tables, ref defs)
    var lineStartIdx = 0
    while (lineStartIdx < n) {
        var lineEndIdx = lineStartIdx
        while (lineEndIdx < n && raw[lineEndIdx].type != MdTokens.NEWLINE) {
            lineEndIdx++
        }
        val lineEndExclNl = lineEndIdx
        val nextLineStart = if (lineEndIdx < n) lineEndIdx + 1 else n

        // Skip leading WS / BLOCKQUOTE_MARKER / LIST_MARKER to find first content token
        var firstContent = lineStartIdx
        while (firstContent < lineEndExclNl &&
            (raw[firstContent].type == MdTokens.WS || raw[firstContent].type == MdTokens.BLOCKQUOTE_MARKER)
        ) {
            firstContent++
        }

        val isAtx = firstContent < lineEndExclNl && raw[firstContent].type == MdTokens.ATX_HEADING_OPEN
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
        } else if (followedBySetext) {
            for (k in lineStartIdx until lineEndExclNl) {
                inSetextHeadingLine[k] = true
            }
        }

        // Check GFM table delimiter row (contains '|' and '-', only '|', '-', ':', WS)
        if (!isAtx && isGfmTableDelimiterRow(source, raw, firstContent, lineEndExclNl)) {
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

    // 3. O(N) stack precomputation for LPAREN..RPAREN and LBRACK..RBRACK
    val parenMatch = HashMap<Int, Int>()
    val parenStack = ArrayList<Int>()
    val bracketMatch = HashMap<Int, Int>()
    val bracketStack = ArrayList<Int>()

    for (k in 0 until n) {
        if (excludedFromDelim[k]) continue
        when (raw[k].type) {
            MdTokens.LPAREN -> parenStack.add(k)
            MdTokens.RPAREN -> if (parenStack.isNotEmpty()) {
                parenMatch[parenStack.removeAt(parenStack.lastIndex)] = k
            }
            MdTokens.LBRACK, MdTokens.BANG_LBRACK -> bracketStack.add(k)
            MdTokens.RBRACK -> if (bracketStack.isNotEmpty()) {
                bracketMatch[bracketStack.removeAt(bracketStack.lastIndex)] = k
            }
        }
    }

    // 4. Link reference definitions ([ref]: url "title") and footnote definitions ([^note]: ...)
    val customSplitTokens = HashMap<Int, List<SyntaxToken>>()
    var curLineStart = 0
    while (curLineStart < n) {
        var curLineEnd = curLineStart
        while (curLineEnd < n && raw[curLineEnd].type != MdTokens.NEWLINE) curLineEnd++
        var firstContent = curLineStart
        while (firstContent < curLineEnd &&
            (raw[firstContent].type == MdTokens.WS || raw[firstContent].type == MdTokens.BLOCKQUOTE_MARKER)
        ) {
            firstContent++
        }
        if (firstContent < curLineEnd && raw[firstContent].type == MdTokens.LBRACK) {
            val closeIdx = bracketMatch[firstContent]
            if (closeIdx != null && closeIdx < curLineEnd && closeIdx + 1 < curLineEnd &&
                raw[closeIdx + 1].type == MdTokens.COLON &&
                (closeIdx + 2 >= curLineEnd || raw[closeIdx + 2].type == MdTokens.WS)
            ) {
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
                    // Classify URL (and optional quoted title) on the rest of the definition line
                    var p = closeIdx + 2
                    while (p < curLineEnd && raw[p].type == MdTokens.WS) p++
                    if (p < curLineEnd) {
                        // Consume URL tokens up to whitespace
                        var urlEndTok = p
                        while (urlEndTok < curLineEnd && raw[urlEndTok].type != MdTokens.WS) {
                            excludedFromDelim[urlEndTok] = true
                            if (raw[urlEndTok].type == MdTokens.AUTOLINK) {
                                // Handled below as Punctuation + Attribute + Punctuation
                            } else if (resolvedKinds[urlEndTok] == null) {
                                resolvedKinds[urlEndTok] = SyntaxTokenKind.Attribute
                            }
                            urlEndTok++
                        }
                        // Optional title ("..." or '...')
                        var tStart = urlEndTok
                        while (tStart < curLineEnd && raw[tStart].type == MdTokens.WS) tStart++
                        if (tStart < curLineEnd) {
                            val titleFirstChar = source[raw[tStart].start]
                            if (titleFirstChar == '"' || titleFirstChar == '\'') {
                                for (tk in tStart until curLineEnd) {
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
        curLineStart = if (curLineEnd < n) curLineEnd + 1 else n
    }

    // 5. Inline links [text](url "title"), images ![alt](url), reference links [text][ref], footnotes [^note]
    val activeLinkStack = ArrayList<Pair<Int, Boolean>>() // (openIdx, isActive)
    var idx = 0
    while (idx < n) {
        if (excludedFromDelim[idx]) {
            idx++
            continue
        }
        val t = raw[idx].type
        if (t == MdTokens.LBRACK || t == MdTokens.BANG_LBRACK) {
            activeLinkStack.add(idx to true)
            idx++
            continue
        }
        if (t == MdTokens.RBRACK && activeLinkStack.isNotEmpty()) {
            val (openIdx, isActive) = activeLinkStack.removeAt(activeLinkStack.lastIndex)
            if (!isActive) {
                idx++
                continue
            }
            val isImage = raw[openIdx].type == MdTokens.BANG_LBRACK
            val afterIdx = idx + 1

            // Case A: [text](url "title") or ![alt](url)
            if (afterIdx < n && raw[afterIdx].type == MdTokens.LPAREN) {
                val closeParenIdx = parenMatch[afterIdx]
                if (closeParenIdx != null) {
                    resolvedKinds[openIdx] = SyntaxTokenKind.Punctuation
                    resolvedKinds[idx] = SyntaxTokenKind.Punctuation
                    resolvedKinds[afterIdx] = SyntaxTokenKind.Punctuation
                    resolvedKinds[closeParenIdx] = SyntaxTokenKind.Punctuation
                    excludedFromDelim[openIdx] = true
                    excludedFromDelim[idx] = true
                    excludedFromDelim[afterIdx] = true
                    excludedFromDelim[closeParenIdx] = true

                    for (inner in (openIdx + 1) until idx) {
                        if (resolvedKinds[inner] == null &&
                            raw[inner].type != MdTokens.STAR_RUN &&
                            raw[inner].type != MdTokens.UNDERSCORE_RUN &&
                            raw[inner].type != MdTokens.TILDE_TILDE
                        ) {
                            resolvedKinds[inner] = SyntaxTokenKind.Tag
                        }
                    }
                    classifyLinkParensBody(source, raw, afterIdx + 1, closeParenIdx, resolvedKinds, excludedFromDelim, customSplitTokens)

                    if (!isImage) {
                        for (s in activeLinkStack.indices) {
                            if (raw[activeLinkStack[s].first].type == MdTokens.LBRACK) {
                                activeLinkStack[s] = activeLinkStack[s].first to false
                            }
                        }
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

                    for (inner in (openIdx + 1) until idx) {
                        if (resolvedKinds[inner] == null) {
                            resolvedKinds[inner] = SyntaxTokenKind.Tag
                        }
                    }
                    for (refInner in (afterIdx + 1) until refCloseIdx) {
                        excludedFromDelim[refInner] = true
                        if (resolvedKinds[refInner] == null) {
                            resolvedKinds[refInner] = SyntaxTokenKind.Constant
                        }
                    }
                    if (!isImage) {
                        for (s in activeLinkStack.indices) {
                            if (raw[activeLinkStack[s].first].type == MdTokens.LBRACK) {
                                activeLinkStack[s] = activeLinkStack[s].first to false
                            }
                        }
                    }
                    idx = refCloseIdx + 1
                    continue
                }
            }

            // Case C: Footnote reference [^note]
            if (!isImage && openIdx + 1 < idx && source[raw[openIdx + 1].start] == '^') {
                var hasWs = false
                for (k in (openIdx + 1) until idx) {
                    if (raw[k].type == MdTokens.WS || raw[k].type == MdTokens.NEWLINE) {
                        hasWs = true
                        break
                    }
                }
                if (!hasWs) {
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
        }
        idx++
    }

    // 6. Strictly O(N) emphasis & strikethrough delimiter resolution using CommonMark openersBottom
    resolveEmphasisDelimiters(source, raw, resolvedKinds, excludedFromDelim, customSplitTokens)

    // 7. Emit all tokens in exact order
    for (k in 0 until n) {
        val custom = customSplitTokens[k]
        if (custom != null) {
            out.addAll(custom)
            continue
        }
        val tok = raw[k]
        when (tok.type) {
            MdTokens.TASK_CHECKBOX -> {
                // Split '[x]' / '[ ]' / '[X]' into Punctuation + Constant/Whitespace + Punctuation
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
                val defaultTextKind = if (inAtxHeadingLine[k] || inSetextHeadingLine[k]) {
                    SyntaxTokenKind.Keyword
                } else {
                    SyntaxTokenKind.Plain
                }
                val kind = resolvedKinds[k] ?: defaultTextKind
                out += SyntaxToken(kind, tok.start, tok.end)
            }
        }
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

        val ch0 = source[tok.start]
        if (ch0 == '"' || ch0 == '\'') {
            resolvedKinds[k] = SyntaxTokenKind.String
            val slice = source.substring(tok.start, tok.end)
            if (slice.length == 1 || slice.indexOf(ch0, startIndex = 1) < 0) {
                inTitleQuote = ch0
            }
            k++
            continue
        }

        if (tok.type == MdTokens.AUTOLINK) {
            customSplitTokens[k] = listOf(
                SyntaxToken(SyntaxTokenKind.Punctuation, tok.start, tok.start + 1),
                SyntaxToken(SyntaxTokenKind.Attribute, tok.start + 1, tok.end - 1),
                SyntaxToken(SyntaxTokenKind.Punctuation, tok.end - 1, tok.end),
            )
        } else if (tok.type == MdTokens.HTML_TAG_OPEN || tok.type == MdTokens.HTML_TAG_CLOSE) {
            resolvedKinds[k] = SyntaxTokenKind.Punctuation
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
    var active: Boolean = true,
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
    // openersBottom[kindSlot]: 0..5 for '*', 6..11 for '_', 12..13 for '~'
    val openersBottom = IntArray(14) { -1 }

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
            MdTokens.TILDE_TILDE -> '~'
            else -> continue
        }
        val len = tok.end - tok.start
        val (canOpen, canClose) = computeFlanking(source, tok.start, tok.end, delimChar)
        if (!canOpen && !canClose) continue

        if (canClose) {
            var remainingClose = len
            while (remainingClose > 0) {
                val bottomSlot = when (delimChar) {
                    '*' -> (if (canOpen) 3 else 0) + (remainingClose % 3)
                    '_' -> 6 + (if (canOpen) 3 else 0) + (remainingClose % 3)
                    else -> 12 + (if (canOpen) 1 else 0)
                }
                val bottom = openersBottom[bottomSlot]
                var openerStackIdx = stack.size - 1
                var found = false
                while (openerStackIdx > bottom) {
                    val opener = stack[openerStackIdx]
                    if (opener.active && opener.canOpen && opener.char == delimChar && opener.len > 0) {
                        val ruleOfThreeBlocked = delimChar != '~' &&
                            ((opener.canOpen && opener.canClose) || (canOpen && canClose)) &&
                            (opener.origLen + len) % 3 == 0 &&
                            !(opener.origLen % 3 == 0 && len % 3 == 0)
                        if (!ruleOfThreeBlocked) {
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
                    delimChar == '~' -> 2
                    opener.len >= 2 && remainingClose >= 2 -> 2
                    else -> 1
                }
                // Deactivate any inner delimiters between opener and closer
                for (mid in (openerStackIdx + 1) until stack.size) {
                    stack[mid].active = false
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
                if (opener.len == 0) {
                    opener.active = false
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
    return when (cp) {
        0x2013, 0x2014, 0x2018, 0x2019, 0x201C, 0x201D, 0x2026, 0x00AB, 0x00BB -> true
        else -> false
    }
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
