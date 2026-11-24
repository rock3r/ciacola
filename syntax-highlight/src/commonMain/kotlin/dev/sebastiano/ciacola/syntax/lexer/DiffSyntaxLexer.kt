package dev.sebastiano.ciacola.syntax.lexer

import dev.sebastiano.ciacola.syntax.SyntaxHighlighter
import dev.sebastiano.ciacola.syntax.SyntaxLanguage
import dev.sebastiano.ciacola.syntax.SyntaxLexer
import dev.sebastiano.ciacola.syntax.SyntaxToken
import dev.sebastiano.ciacola.syntax.SyntaxTokenKind
import dev.sebastiano.ciacola.syntax.antlr.diff.DiffLexer
import dev.sebastiano.ciacola.syntax.antlr.diff.DiffLexer.Tokens as DiffTokens
import dev.sebastiano.ciacola.syntax.internal.antlrCharStream
import dev.sebastiano.ciacola.syntax.internal.driveAntlrLexer
import dev.sebastiano.ciacola.syntax.internal.emitDefaultGap

/**
 * Lossless ANTLR4-backed lexical tokenizer for Diff / Patch formats, supporting Git extended,
 * unified, combined (`@@@`), context, normal, binary, and email/SVN patch headers with
 * one-level-deep non-recursive sub-highlighting of embedded file hunks.
 */
internal object DiffSyntaxLexer : SyntaxLexer, SubHighlightableSyntaxLexer {
    override val language: SyntaxLanguage = SyntaxLanguage.Diff

    override fun lex(source: String): List<SyntaxToken> =
        lex(source, allowNestedSubHighlighting = true)

    override fun lex(source: String, allowNestedSubHighlighting: Boolean): List<SyntaxToken> {
        if (source.isEmpty()) return emptyList()

        val lexer = DiffLexer(antlrCharStream(source))
        val rawTypes = ArrayList<Int>(source.length / 8 + 8)
        val rawStarts = ArrayList<Int>(source.length / 8 + 8)
        val rawEnds = ArrayList<Int>(source.length / 8 + 8)

        driveAntlrLexer(
            source = source,
            lexer = lexer,
            onGap = { start, end ->
                emitDefaultGap(source, start, end) { kind, s, e ->
                    rawTypes += if (kind == SyntaxTokenKind.Whitespace) GAP_WS_TYPE else GAP_INVALID_TYPE
                    rawStarts += s
                    rawEnds += e
                }
            },
            onToken = { type, start, end ->
                rawTypes += type
                rawStarts += start
                rawEnds += end
            },
        )

        val out = ArrayList<SyntaxToken>(rawTypes.size * 2)
        val state = DiffSectionState()
        val hunk = HunkAccumulator()
        val count = rawTypes.size
        var i = 0

        while (i < count) {
            val type = rawTypes[i]
            val start = rawStarts[i]
            val end = rawEnds[i]

            when (type) {
                GAP_WS_TYPE -> {
                    hunk.flushIfActive(out)
                    out += SyntaxToken(SyntaxTokenKind.Whitespace, start, end)
                    i++
                }

                GAP_INVALID_TYPE -> {
                    hunk.flushIfActive(out)
                    out += SyntaxToken(SyntaxTokenKind.Invalid, start, end)
                    i++
                }

                DiffTokens.BOM,
                DiffTokens.NEWLINE,
                -> {
                    hunk.flushIfActive(out)
                    out += SyntaxToken(SyntaxTokenKind.Whitespace, start, end)
                    i++
                }

                DiffTokens.UH_NEWLINE,
                DiffTokens.CH_NEWLINE,
                DiffTokens.CX_NEWLINE,
                -> {
                    if (hunk.isActive) {
                        if (hunk.awaitingHeaderNewline) {
                            hunk.awaitingHeaderNewline = false
                        } else if (hunk.subHighlightLanguage != null) {
                            hunk.appendBodyLine(
                                source = source,
                                role = LineRole.CONTEXT,
                                bodyStart = -1,
                                bodyEnd = -1,
                                newlineText = source.substring(start, end),
                                fallbackKind = SyntaxTokenKind.Plain,
                            )
                        }
                        hunk.addFixed(SyntaxTokenKind.Whitespace, start, end)
                    } else {
                        out += SyntaxToken(SyntaxTokenKind.Whitespace, start, end)
                    }
                    i++
                }

                DiffTokens.NH_NEWLINE -> {
                    if (hunk.isActive) {
                        hunk.awaitingHeaderNewline = false
                        hunk.addFixed(SyntaxTokenKind.Whitespace, start, end)
                    } else {
                        out += SyntaxToken(SyntaxTokenKind.Whitespace, start, end)
                    }
                    i++
                }

                DiffTokens.BP_NEWLINE -> {
                    hunk.flushIfActive(out)
                    out += SyntaxToken(SyntaxTokenKind.Whitespace, start, end)
                    i++
                }

                DiffTokens.GIT_DIFF_HEADER,
                DiffTokens.UH_GIT_DIFF,
                DiffTokens.CH_GIT_DIFF,
                DiffTokens.CX_GIT_DIFF,
                DiffTokens.NH_GIT_DIFF,
                DiffTokens.BP_GIT_DIFF,
                -> {
                    hunk.flushIfActive(out)
                    state.onGitDiffHeader(source.substring(start, end))
                    out += SyntaxToken(SyntaxTokenKind.Keyword, start, end)
                    i++
                }

                DiffTokens.GIT_INDEX_HEADER,
                DiffTokens.GIT_MODE_HEADER,
                DiffTokens.GIT_SIMILARITY_HEADER,
                DiffTokens.GIT_BINARY_FILES,
                DiffTokens.GIT_BINARY_PATCH,
                -> {
                    hunk.flushIfActive(out)
                    out += SyntaxToken(SyntaxTokenKind.Keyword, start, end)
                    i++
                }

                DiffTokens.GIT_COPY_RENAME_HEADER -> {
                    hunk.flushIfActive(out)
                    state.onCopyRenameHeader(source.substring(start, end))
                    out += SyntaxToken(SyntaxTokenKind.Keyword, start, end)
                    i++
                }

                DiffTokens.SVN_INDEX_HEADER,
                DiffTokens.UH_SVN_INDEX,
                DiffTokens.CH_SVN_INDEX,
                DiffTokens.CX_SVN_INDEX,
                DiffTokens.NH_SVN_INDEX,
                DiffTokens.BP_SVN_INDEX,
                -> {
                    hunk.flushIfActive(out)
                    state.onIndexHeader(source.substring(start, end))
                    emitIndexHeader(source, start, end, out)
                    i++
                }

                DiffTokens.HEADER_SEPARATOR,
                DiffTokens.UH_HEADER_SEP,
                DiffTokens.CH_HEADER_SEP,
                DiffTokens.CX_HEADER_SEP,
                DiffTokens.NH_HEADER_SEP,
                DiffTokens.BP_HEADER_SEP,
                -> {
                    hunk.flushIfActive(out)
                    emitTrimmedPunctuation(source, start, end, out)
                    i++
                }

                DiffTokens.PATCH_FROM_ENVELOPE -> {
                    hunk.flushIfActive(out)
                    emitPatchFromEnvelope(source, start, end, out)
                    i++
                }

                DiffTokens.EMAIL_HEADER -> {
                    hunk.flushIfActive(out)
                    emitEmailHeader(source, start, end, out)
                    i++
                }

                DiffTokens.CONTEXT_SEPARATOR,
                DiffTokens.CX_SEPARATOR,
                -> {
                    hunk.flushIfActive(out)
                    emitContextSeparator(source, start, end, out)
                    i++
                }

                DiffTokens.CONTEXT_FILE_HEADER,
                DiffTokens.CX_FROM_FILE,
                DiffTokens.BP_CONTEXT_FILE,
                -> {
                    hunk.flushIfActive(out)
                    state.onContextFromFile(parsePrefixedPath(source, start, end, 3))
                    emitPrefixedFileHeader(source, start, end, 3, SyntaxTokenKind.Keyword, out)
                    i++
                }

                DiffTokens.UNIFIED_FROM_FILE,
                DiffTokens.NH_UNIFIED_FROM_FILE,
                DiffTokens.BP_UNIFIED_FROM,
                DiffTokens.CX_TO_FILE,
                -> {
                    hunk.flushIfActive(out)
                    state.onFromFile(parsePrefixedPath(source, start, end, 3))
                    emitPrefixedFileHeader(source, start, end, 3, SyntaxTokenKind.Keyword, out)
                    i++
                }

                DiffTokens.UNIFIED_TO_FILE,
                DiffTokens.NH_UNIFIED_TO_FILE,
                DiffTokens.BP_UNIFIED_TO,
                -> {
                    hunk.flushIfActive(out)
                    state.onToFile(parsePrefixedPath(source, start, end, 3))
                    emitPrefixedFileHeader(source, start, end, 3, SyntaxTokenKind.String, out)
                    i++
                }

                DiffTokens.SEPARATOR_TRIPLE_DASH -> {
                    hunk.flushIfActive(out)
                    emitTrimmedPunctuation(source, start, end, out)
                    i++
                }

                DiffTokens.CONTEXT_HUNK_STAR_HEADER,
                DiffTokens.CX_HUNK_STAR,
                -> {
                    hunk.flushIfActive(out)
                    state.onHunkStarted()
                    hunk.begin(
                        targetLanguage = state.resolveSubHighlightLanguage(allowNestedSubHighlighting),
                        contextSide = ContextSide.OLD,
                        awaitingHeaderNewline = true,
                    )
                    emitContextHunkHeader(source, start, end, out)
                    i++
                }

                DiffTokens.CONTEXT_HUNK_DASH_HEADER,
                DiffTokens.CX_HUNK_DASH,
                -> {
                    hunk.flushIfActive(out)
                    state.onHunkStarted()
                    emitContextHunkHeader(source, start, end, out)
                    hunk.begin(
                        targetLanguage = state.resolveSubHighlightLanguage(allowNestedSubHighlighting),
                        contextSide = ContextSide.NEW,
                        awaitingHeaderNewline = true,
                    )
                    i++
                }

                DiffTokens.HUNK_HEADER_UNIFIED,
                DiffTokens.UH_HUNK_UNIFIED,
                DiffTokens.CH_HUNK_UNIFIED,
                DiffTokens.CX_HUNK_UNIFIED,
                DiffTokens.NH_HUNK_UNIFIED,
                DiffTokens.BP_HUNK_UNIFIED,
                -> {
                    hunk.flushIfActive(out)
                    state.onHunkStarted()
                    hunk.begin(
                        targetLanguage = state.resolveSubHighlightLanguage(allowNestedSubHighlighting),
                        contextSide = ContextSide.NEW,
                        awaitingHeaderNewline = true,
                    )
                    emitAtHunkHeader(source, start, end, out)
                    i++
                }

                DiffTokens.HUNK_HEADER_COMBINED,
                DiffTokens.UH_HUNK_COMBINED,
                DiffTokens.CH_HUNK_COMBINED,
                DiffTokens.CX_HUNK_COMBINED,
                -> {
                    hunk.flushIfActive(out)
                    state.onHunkStarted()
                    hunk.begin(
                        targetLanguage = state.resolveSubHighlightLanguage(allowNestedSubHighlighting),
                        contextSide = ContextSide.NEW,
                        awaitingHeaderNewline = true,
                    )
                    emitAtHunkHeader(source, start, end, out)
                    i++
                }

                DiffTokens.HUNK_HEADER_NORMAL,
                DiffTokens.UH_HUNK_NORMAL,
                DiffTokens.CX_HUNK_NORMAL,
                DiffTokens.NH_HUNK_NORMAL,
                -> {
                    hunk.flushIfActive(out)
                    state.onHunkStarted()
                    hunk.begin(
                        targetLanguage = state.resolveSubHighlightLanguage(allowNestedSubHighlighting),
                        contextSide = ContextSide.NEW,
                        awaitingHeaderNewline = true,
                    )
                    emitNormalHunkHeader(source, start, end, out)
                    i++
                }

                DiffTokens.NH_SEP -> {
                    if (hunk.isActive) {
                        hunk.addAll { target -> emitTrimmedPunctuation(source, start, end, target) }
                    } else {
                        emitTrimmedPunctuation(source, start, end, out)
                    }
                    i++
                }

                DiffTokens.NO_NEWLINE,
                DiffTokens.UH_NO_NEWLINE,
                DiffTokens.CH_NO_NEWLINE,
                DiffTokens.CX_NO_NEWLINE,
                DiffTokens.NH_NO_NEWLINE,
                -> {
                    if (hunk.isActive) {
                        hunk.awaitingHeaderNewline = true
                        hunk.addFixed(SyntaxTokenKind.Comment, start, end)
                    } else {
                        out += SyntaxToken(SyntaxTokenKind.Comment, start, end)
                    }
                    i++
                }

                DiffTokens.DIFF_COMMENT -> {
                    hunk.flushIfActive(out)
                    out += SyntaxToken(SyntaxTokenKind.Comment, start, end)
                    i++
                }

                DiffTokens.BP_HEADER -> {
                    hunk.flushIfActive(out)
                    emitBinaryPatchHeader(source, start, end, out)
                    i++
                }

                DiffTokens.BP_PAYLOAD -> {
                    hunk.flushIfActive(out)
                    out += SyntaxToken(SyntaxTokenKind.Constant, start, end)
                    i++
                }

                DiffTokens.UNIFIED_ADD_PREFIX,
                DiffTokens.NORMAL_ADD_PREFIX,
                DiffTokens.UNIFIED_DEL_PREFIX,
                DiffTokens.NORMAL_DEL_PREFIX,
                DiffTokens.CONTEXT_CHG_PREFIX,
                DiffTokens.DEFAULT_SPACE_PREFIX,
                -> {
                    // Outside an active hunk header: flush any completed hunk and emit pure Diff
                    // tokens so commit messages, diffstats, and `-- ` signatures never sub-highlight.
                    hunk.flushIfActive(out)
                    val lineKind = classifyLinePrefix(type, source, start, end, ContextSide.NEW)
                    val markerKind = if (lineKind == LineRole.CONTEXT) {
                        SyntaxTokenKind.Whitespace
                    } else {
                        SyntaxTokenKind.Punctuation
                    }
                    out += SyntaxToken(markerKind, start, end)
                    if (i + 1 < count && rawTypes[i + 1] == DiffTokens.BODY_TEXT) {
                        val bodyStart = rawStarts[i + 1]
                        val bodyEnd = rawEnds[i + 1]
                        val bodyKind = when (lineKind) {
                            LineRole.ADDED -> SyntaxTokenKind.String
                            LineRole.DELETED -> SyntaxTokenKind.Keyword
                            LineRole.CHANGED_OLD, LineRole.CHANGED_NEW -> SyntaxTokenKind.Type
                            LineRole.CONTEXT -> SyntaxTokenKind.Plain
                        }
                        out += SyntaxToken(bodyKind, bodyStart, bodyEnd)
                        i++
                    }
                    if (i + 1 < count && rawTypes[i + 1] == DiffTokens.BODY_NEWLINE) {
                        out += SyntaxToken(SyntaxTokenKind.Whitespace, rawStarts[i + 1], rawEnds[i + 1])
                        i++
                    }
                    i++
                }

                DiffTokens.UH_ADD_PREFIX,
                DiffTokens.NH_ADD_PREFIX,
                DiffTokens.CX_ADD_PREFIX,
                DiffTokens.UH_DEL_PREFIX,
                DiffTokens.NH_DEL_PREFIX,
                DiffTokens.CX_DEL_PREFIX,
                DiffTokens.CX_CHG_PREFIX,
                DiffTokens.UH_CTX_PREFIX,
                DiffTokens.CX_CTX_PREFIX,
                DiffTokens.CH_PREFIX,
                -> {
                    val lineKind = classifyLinePrefix(type, source, start, end, hunk.contextSide)
                    if (!hunk.isActive) {
                        state.onHunkStarted()
                        hunk.begin(
                            targetLanguage = state.resolveSubHighlightLanguage(allowNestedSubHighlighting),
                            contextSide = hunk.contextSide,
                        )
                    }
                    hunk.awaitingHeaderNewline = false

                    // Emit the leading diff prefix marker
                    val markerKind = if (lineKind == LineRole.CONTEXT) {
                        SyntaxTokenKind.Whitespace
                    } else {
                        SyntaxTokenKind.Punctuation
                    }
                    hunk.addFixed(markerKind, start, end)

                    // Consume optional BODY_TEXT and optional BODY_NEWLINE that immediately follow
                    var bodyStart = -1
                    var bodyEnd = -1
                    if (i + 1 < count && rawTypes[i + 1] == DiffTokens.BODY_TEXT) {
                        bodyStart = rawStarts[i + 1]
                        bodyEnd = rawEnds[i + 1]
                        i++
                    }

                    var nlStart = -1
                    var nlEnd = -1
                    if (i + 1 < count && rawTypes[i + 1] == DiffTokens.BODY_NEWLINE) {
                        nlStart = rawStarts[i + 1]
                        nlEnd = rawEnds[i + 1]
                        i++
                    }

                    val nlText = if (nlStart >= 0) source.substring(nlStart, nlEnd) else ""
                    val fallbackBodyKind = when (lineKind) {
                        LineRole.ADDED -> SyntaxTokenKind.String
                        LineRole.DELETED -> SyntaxTokenKind.Keyword
                        LineRole.CHANGED_OLD, LineRole.CHANGED_NEW -> SyntaxTokenKind.Type
                        LineRole.CONTEXT -> SyntaxTokenKind.Plain
                    }

                    if (hunk.subHighlightLanguage != null) {
                        hunk.appendBodyLine(
                            source = source,
                            role = lineKind,
                            bodyStart = bodyStart,
                            bodyEnd = bodyEnd,
                            newlineText = nlText,
                            fallbackKind = fallbackBodyKind,
                        )
                    } else if (bodyStart >= 0) {
                        hunk.addFixed(fallbackBodyKind, bodyStart, bodyEnd)
                    }

                    if (nlStart >= 0) {
                        hunk.addFixed(SyntaxTokenKind.Whitespace, nlStart, nlEnd)
                    }
                    i++
                }

                DiffTokens.BODY_TEXT -> {
                    hunk.flushIfActive(out)
                    out += SyntaxToken(SyntaxTokenKind.Plain, start, end)
                    i++
                }

                DiffTokens.BODY_NEWLINE -> {
                    hunk.flushIfActive(out)
                    out += SyntaxToken(SyntaxTokenKind.Whitespace, start, end)
                    i++
                }

                DiffTokens.BACKSLASH_FALLBACK,
                DiffTokens.UH_BACKSLASH_LINE,
                DiffTokens.CH_BACKSLASH_LINE,
                DiffTokens.CX_BACKSLASH_LINE,
                DiffTokens.NH_BACKSLASH_LINE,
                DiffTokens.PREAMBLE_LINE,
                DiffTokens.UH_FALLBACK_LINE,
                DiffTokens.CH_FALLBACK_LINE,
                DiffTokens.CX_FALLBACK_LINE,
                DiffTokens.NH_FALLBACK_LINE,
                DiffTokens.BP_FALLBACK_LINE,
                -> {
                    hunk.flushIfActive(out)
                    out += SyntaxToken(SyntaxTokenKind.Plain, start, end)
                    i++
                }

                else -> {
                    hunk.flushIfActive(out)
                    out += SyntaxToken(SyntaxTokenKind.Invalid, start, end)
                    i++
                }
            }
        }

        hunk.flushIfActive(out)
        return out
    }
}

private const val GAP_WS_TYPE = -1001
private const val GAP_INVALID_TYPE = -1002

private enum class ContextSide {
    OLD,
    NEW,
}

private enum class LineRole {
    ADDED,
    DELETED,
    CONTEXT,
    CHANGED_OLD,
    CHANGED_NEW,
}

private enum class BufferSide {
    OLD,
    NEW,
}

private class DiffSectionState {
    private var gitFromPath: String? = null
    private var gitToPath: String? = null
    private var indexPath: String? = null
    private var fromPath: String? = null
    private var toPath: String? = null
    private var sawHunkInSection: Boolean = false
    private var lastHeaderWasContextFrom: Boolean = false

    fun onHunkStarted() {
        sawHunkInSection = true
        lastHeaderWasContextFrom = false
    }

    fun onGitDiffHeader(line: String) {
        gitFromPath = null
        gitToPath = null
        indexPath = null
        fromPath = null
        toPath = null
        sawHunkInSection = false
        lastHeaderWasContextFrom = false

        val trimmed = line.trimEnd()
        val afterDiff = skipWs(trimmed, 4)
        when {
            trimmed.startsWith("--git", startIndex = afterDiff) -> {
                val p1Start = skipWs(trimmed, afterDiff + 5)
                val (p1, p2) = parseGitDiffPair(trimmed, p1Start)
                gitFromPath = p1
                gitToPath = p2
            }

            trimmed.startsWith("--cc", startIndex = afterDiff) -> {
                val pStart = skipWs(trimmed, afterDiff + 4)
                gitToPath = parseSingleRemainderPath(trimmed, pStart)
            }

            trimmed.startsWith("--combined", startIndex = afterDiff) -> {
                val pStart = skipWs(trimmed, afterDiff + 10)
                gitToPath = parseSingleRemainderPath(trimmed, pStart)
            }

            else -> {
                // Standard `diff -u old new` or `diff dir1/f dir2/f` header: skip `-flags` and collect trailing paths.
                var cursor = afterDiff
                var firstPath: String? = null
                var lastPath: String? = null
                while (cursor < trimmed.length) {
                    cursor = skipWs(trimmed, cursor)
                    if (cursor >= trimmed.length) break
                    if (trimmed[cursor] == '-') {
                        while (cursor < trimmed.length && trimmed[cursor] != ' ' && trimmed[cursor] != '\t') {
                            cursor++
                        }
                    } else {
                        val (p, next) = parseCliPathToken(trimmed, cursor)
                        if (p != null) {
                            if (firstPath == null) firstPath = p
                            lastPath = p
                        }
                        cursor = next
                    }
                }
                gitFromPath = firstPath
                gitToPath = lastPath
            }
        }
    }

    fun onIndexHeader(line: String) {
        gitFromPath = null
        gitToPath = null
        fromPath = null
        toPath = null
        sawHunkInSection = false
        lastHeaderWasContextFrom = false
        val colonIdx = line.indexOf(':')
        indexPath = if (colonIdx >= 0) {
            parseHeaderPathValue(line.substring(colonIdx + 1))
        } else {
            null
        }
    }

    fun onCopyRenameHeader(line: String) {
        lastHeaderWasContextFrom = false
        when {
            line.startsWith("rename from") -> fromPath = parseHeaderPathValue(line.substring(11))
            line.startsWith("copy from") -> fromPath = parseHeaderPathValue(line.substring(9))
            line.startsWith("rename to") -> toPath = parseHeaderPathValue(line.substring(9))
            line.startsWith("copy to") -> toPath = parseHeaderPathValue(line.substring(7))
        }
    }

    fun onContextFromFile(path: String?) {
        if (sawHunkInSection) {
            gitFromPath = null
            gitToPath = null
            indexPath = null
            sawHunkInSection = false
        }
        fromPath = path
        toPath = null
        lastHeaderWasContextFrom = true
    }

    fun onFromFile(path: String?) {
        if (lastHeaderWasContextFrom && !sawHunkInSection) {
            toPath = path
            lastHeaderWasContextFrom = false
            return
        }
        if (sawHunkInSection) {
            gitFromPath = null
            gitToPath = null
            indexPath = null
            sawHunkInSection = false
        }
        fromPath = path
        toPath = null
        lastHeaderWasContextFrom = false
    }

    fun onToFile(path: String?) {
        toPath = path
        lastHeaderWasContextFrom = false
    }

    fun resolveSubHighlightLanguage(allowNestedSubHighlighting: Boolean): SyntaxLanguage? {
        if (!allowNestedSubHighlighting) return null
        val target = resolveTargetLanguage() ?: return null
        if (target == SyntaxLanguage.Diff) return null
        if (SyntaxHighlighter.lexerOrNull(target) == null) return null
        return target
    }

    fun resolveTargetLanguage(): SyntaxLanguage? {
        val candidates = arrayOf(toPath, fromPath, gitToPath, gitFromPath, indexPath)
        for (raw in candidates) {
            if (raw == null) continue
            val normalized = normalizeDiffPath(raw) ?: continue
            resolveLanguageForNormalizedPath(normalized)?.let { return it }
        }
        return null
    }
}

private class LineProjectionMapping(
    val side: BufferSide,
    val diffBodyStart: Int,
    val diffBodyEnd: Int,
    val bufBodyStart: Int,
    val bufBodyEnd: Int,
    val fallbackKind: SyntaxTokenKind,
)

private sealed interface HunkEntry {
    class Fixed(val kind: SyntaxTokenKind, val start: Int, val end: Int) : HunkEntry
    class Body(val mapping: LineProjectionMapping) : HunkEntry
}

private class HunkAccumulator {
    var isActive: Boolean = false
        private set
    var subHighlightLanguage: SyntaxLanguage? = null
        private set
    var contextSide: ContextSide = ContextSide.NEW
    var awaitingHeaderNewline: Boolean = false

    private val entries = ArrayList<HunkEntry>()
    private val oldBuffer = StringBuilder()
    private val newBuffer = StringBuilder()
    private var hasOldMappedLines = false
    private var hasNewMappedLines = false

    fun begin(
        targetLanguage: SyntaxLanguage?,
        contextSide: ContextSide,
        awaitingHeaderNewline: Boolean = false,
    ) {
        reset()
        isActive = true
        subHighlightLanguage = targetLanguage
        this.contextSide = contextSide
        this.awaitingHeaderNewline = awaitingHeaderNewline
    }

    fun addFixed(kind: SyntaxTokenKind, start: Int, end: Int) {
        if (end > start) {
            entries += HunkEntry.Fixed(kind, start, end)
        }
    }

    fun addAll(emit: (MutableList<SyntaxToken>) -> Unit) {
        val temp = ArrayList<SyntaxToken>(6)
        emit(temp)
        for (tok in temp) {
            entries += HunkEntry.Fixed(tok.kind, tok.startIndex, tok.endIndex)
        }
    }

    fun appendBodyLine(
        source: String,
        role: LineRole,
        bodyStart: Int,
        bodyEnd: Int,
        newlineText: String,
        fallbackKind: SyntaxTokenKind,
    ) {
        val hasBody = bodyStart >= 0 && bodyEnd > bodyStart
        when (role) {
            LineRole.ADDED,
            LineRole.CHANGED_NEW,
            -> {
                val bufStart = newBuffer.length
                if (hasBody) newBuffer.append(source, bodyStart, bodyEnd)
                val bufEnd = newBuffer.length
                newBuffer.append(newlineText)
                if (hasBody) {
                    val mapping = LineProjectionMapping(
                        side = BufferSide.NEW,
                        diffBodyStart = bodyStart,
                        diffBodyEnd = bodyEnd,
                        bufBodyStart = bufStart,
                        bufBodyEnd = bufEnd,
                        fallbackKind = fallbackKind,
                    )
                    entries += HunkEntry.Body(mapping)
                    hasNewMappedLines = true
                }
            }

            LineRole.DELETED,
            LineRole.CHANGED_OLD,
            -> {
                val bufStart = oldBuffer.length
                if (hasBody) oldBuffer.append(source, bodyStart, bodyEnd)
                val bufEnd = oldBuffer.length
                oldBuffer.append(newlineText)
                if (hasBody) {
                    val mapping = LineProjectionMapping(
                        side = BufferSide.OLD,
                        diffBodyStart = bodyStart,
                        diffBodyEnd = bodyEnd,
                        bufBodyStart = bufStart,
                        bufBodyEnd = bufEnd,
                        fallbackKind = fallbackKind,
                    )
                    entries += HunkEntry.Body(mapping)
                    hasOldMappedLines = true
                }
            }

            LineRole.CONTEXT -> {
                // Context lines feed both virtual sides so multi-line constructs stay intact,
                // while projection depends on the enclosing section (OLD side of context diff vs NEW post-image).
                val oldBufStart = oldBuffer.length
                if (hasBody) oldBuffer.append(source, bodyStart, bodyEnd)
                val oldBufEnd = oldBuffer.length
                oldBuffer.append(newlineText)

                val newBufStart = newBuffer.length
                if (hasBody) newBuffer.append(source, bodyStart, bodyEnd)
                val newBufEnd = newBuffer.length
                newBuffer.append(newlineText)

                if (hasBody) {
                    if (contextSide == ContextSide.OLD) {
                        val mapping = LineProjectionMapping(
                            side = BufferSide.OLD,
                            diffBodyStart = bodyStart,
                            diffBodyEnd = bodyEnd,
                            bufBodyStart = oldBufStart,
                            bufBodyEnd = oldBufEnd,
                            fallbackKind = fallbackKind,
                        )
                        entries += HunkEntry.Body(mapping)
                        hasOldMappedLines = true
                    } else {
                        val mapping = LineProjectionMapping(
                            side = BufferSide.NEW,
                            diffBodyStart = bodyStart,
                            diffBodyEnd = bodyEnd,
                            bufBodyStart = newBufStart,
                            bufBodyEnd = newBufEnd,
                            fallbackKind = fallbackKind,
                        )
                        entries += HunkEntry.Body(mapping)
                        hasNewMappedLines = true
                    }
                }
            }
        }
    }

    fun flushIfActive(out: MutableList<SyntaxToken>) {
        if (!isActive) return
        val lang = subHighlightLanguage
        if (lang == null || entries.isEmpty()) {
            for (entry in entries) {
                when (entry) {
                    is HunkEntry.Fixed -> out += SyntaxToken(entry.kind, entry.start, entry.end)
                    is HunkEntry.Body -> out += SyntaxToken(entry.mapping.fallbackKind, entry.mapping.diffBodyStart, entry.mapping.diffBodyEnd)
                }
            }
            reset()
            return
        }

        val oldTokens = if (hasOldMappedLines && oldBuffer.isNotEmpty()) {
            lexEmbeddedNoRecursion(oldBuffer.toString(), lang)
        } else {
            emptyList()
        }
        val newTokens = if (hasNewMappedLines && newBuffer.isNotEmpty()) {
            lexEmbeddedNoRecursion(newBuffer.toString(), lang)
        } else {
            emptyList()
        }

        var oldIdx = 0
        var newIdx = 0
        for (entry in entries) {
            when (entry) {
                is HunkEntry.Fixed -> out += SyntaxToken(entry.kind, entry.start, entry.end)
                is HunkEntry.Body -> {
                    val m = entry.mapping
                    if (m.side == BufferSide.OLD) {
                        oldIdx = projectLineTokens(m, oldTokens, oldIdx, out)
                    } else {
                        newIdx = projectLineTokens(m, newTokens, newIdx, out)
                    }
                }
            }
        }
        reset()
    }

    private fun projectLineTokens(
        mapping: LineProjectionMapping,
        subTokens: List<SyntaxToken>,
        startTokenIdx: Int,
        out: MutableList<SyntaxToken>,
    ): Int {
        var idx = startTokenIdx
        val bufStart = mapping.bufBodyStart
        val bufEnd = mapping.bufBodyEnd
        val delta = mapping.diffBodyStart - bufStart

        while (idx < subTokens.size && subTokens[idx].endIndex <= bufStart) {
            idx++
        }

        var coveredUntil = mapping.diffBodyStart
        while (idx < subTokens.size && subTokens[idx].startIndex < bufEnd) {
            val tok = subTokens[idx]
            val ovStart = maxOf(tok.startIndex, bufStart)
            val ovEnd = minOf(tok.endIndex, bufEnd)
            if (ovEnd > ovStart) {
                val mappedStart = ovStart + delta
                val mappedEnd = ovEnd + delta
                if (mappedStart > coveredUntil) {
                    out += SyntaxToken(mapping.fallbackKind, coveredUntil, mappedStart)
                }
                out += SyntaxToken(tok.kind, mappedStart, mappedEnd)
                coveredUntil = mappedEnd
            }
            if (tok.endIndex <= bufEnd) {
                idx++
            } else {
                break
            }
        }

        if (coveredUntil < mapping.diffBodyEnd) {
            out += SyntaxToken(mapping.fallbackKind, coveredUntil, mapping.diffBodyEnd)
        }
        return idx
    }

    private fun reset() {
        isActive = false
        subHighlightLanguage = null
        contextSide = ContextSide.NEW
        awaitingHeaderNewline = false
        entries.clear()
        oldBuffer.setLength(0)
        newBuffer.setLength(0)
        hasOldMappedLines = false
        hasNewMappedLines = false
    }
}

private fun classifyLinePrefix(
    type: Int,
    source: String,
    start: Int,
    end: Int,
    contextSide: ContextSide,
): LineRole = when (type) {
    DiffTokens.UNIFIED_ADD_PREFIX,
    DiffTokens.UH_ADD_PREFIX,
    DiffTokens.NORMAL_ADD_PREFIX,
    DiffTokens.NH_ADD_PREFIX,
    DiffTokens.CX_ADD_PREFIX,
    -> LineRole.ADDED

    DiffTokens.UNIFIED_DEL_PREFIX,
    DiffTokens.UH_DEL_PREFIX,
    DiffTokens.NORMAL_DEL_PREFIX,
    DiffTokens.NH_DEL_PREFIX,
    DiffTokens.CX_DEL_PREFIX,
    -> LineRole.DELETED

    DiffTokens.CONTEXT_CHG_PREFIX,
    DiffTokens.CX_CHG_PREFIX,
    -> if (contextSide == ContextSide.OLD) LineRole.CHANGED_OLD else LineRole.CHANGED_NEW

    DiffTokens.DEFAULT_SPACE_PREFIX,
    DiffTokens.UH_CTX_PREFIX,
    DiffTokens.CX_CTX_PREFIX,
    -> LineRole.CONTEXT

    DiffTokens.CH_PREFIX -> {
        var hasMinus = false
        var hasPlus = false
        for (idx in start until end) {
            when (source[idx]) {
                '-' -> hasMinus = true
                '+' -> hasPlus = true
            }
        }
        when {
            hasMinus -> LineRole.DELETED
            hasPlus -> LineRole.ADDED
            else -> LineRole.CONTEXT
        }
    }

    else -> LineRole.CONTEXT
}

private fun emitTrimmedPunctuation(
    source: String,
    start: Int,
    end: Int,
    out: MutableList<SyntaxToken>,
) {
    var s = start
    while (s < end && isInlineWs(source[s])) s++
    if (s > start) out += SyntaxToken(SyntaxTokenKind.Whitespace, start, s)
    var e = end
    while (e > s && isInlineWs(source[e - 1])) e--
    if (e > s) out += SyntaxToken(SyntaxTokenKind.Punctuation, s, e)
    if (e < end) out += SyntaxToken(SyntaxTokenKind.Whitespace, e, end)
}

private fun emitContextSeparator(
    source: String,
    start: Int,
    end: Int,
    out: MutableList<SyntaxToken>,
) {
    var starsEnd = start
    while (starsEnd < end && source[starsEnd] == '*') starsEnd++
    if (starsEnd > start) {
        out += SyntaxToken(SyntaxTokenKind.Punctuation, start, starsEnd)
    }
    var wsEnd = starsEnd
    while (wsEnd < end && isInlineWs(source[wsEnd])) wsEnd++
    if (wsEnd > starsEnd) {
        out += SyntaxToken(SyntaxTokenKind.Whitespace, starsEnd, wsEnd)
    }
    if (wsEnd < end) {
        var trimmedEnd = end
        while (trimmedEnd > wsEnd && isInlineWs(source[trimmedEnd - 1])) trimmedEnd--
        if (trimmedEnd > wsEnd) {
            out += SyntaxToken(SyntaxTokenKind.Plain, wsEnd, trimmedEnd)
        }
        if (trimmedEnd < end) {
            out += SyntaxToken(SyntaxTokenKind.Whitespace, trimmedEnd, end)
        }
    }
}

private fun emitPrefixedFileHeader(
    source: String,
    start: Int,
    end: Int,
    prefixLen: Int,
    pathKind: SyntaxTokenKind,
    out: MutableList<SyntaxToken>,
) {
    val pEnd = (start + prefixLen).coerceAtMost(end)
    out += SyntaxToken(SyntaxTokenKind.Punctuation, start, pEnd)
    var wsEnd = pEnd
    while (wsEnd < end && isInlineWs(source[wsEnd])) wsEnd++
    if (wsEnd > pEnd) {
        out += SyntaxToken(SyntaxTokenKind.Whitespace, pEnd, wsEnd)
    }
    if (wsEnd < end) {
        out += SyntaxToken(pathKind, wsEnd, end)
    }
}

private fun emitIndexHeader(
    source: String,
    start: Int,
    end: Int,
    out: MutableList<SyntaxToken>,
) {
    val colonIdx = source.indexOf(':', start)
    if (colonIdx < 0 || colonIdx >= end) {
        out += SyntaxToken(SyntaxTokenKind.Keyword, start, end)
        return
    }
    if (colonIdx > start) {
        out += SyntaxToken(SyntaxTokenKind.Keyword, start, colonIdx)
    }
    out += SyntaxToken(SyntaxTokenKind.Punctuation, colonIdx, colonIdx + 1)
    var wsEnd = colonIdx + 1
    while (wsEnd < end && isInlineWs(source[wsEnd])) wsEnd++
    if (wsEnd > colonIdx + 1) {
        out += SyntaxToken(SyntaxTokenKind.Whitespace, colonIdx + 1, wsEnd)
    }
    if (wsEnd < end) {
        out += SyntaxToken(SyntaxTokenKind.Plain, wsEnd, end)
    }
}

private fun emitPatchFromEnvelope(
    source: String,
    start: Int,
    end: Int,
    out: MutableList<SyntaxToken>,
) {
    // `From <sha> <rest>`
    val fromEnd = (start + 4).coerceAtMost(end)
    out += SyntaxToken(SyntaxTokenKind.Keyword, start, fromEnd)
    var cursor = fromEnd
    var wsEnd = cursor
    while (wsEnd < end && isInlineWs(source[wsEnd])) wsEnd++
    if (wsEnd > cursor) out += SyntaxToken(SyntaxTokenKind.Whitespace, cursor, wsEnd)
    cursor = wsEnd
    var shaEnd = cursor
    while (shaEnd < end && !isInlineWs(source[shaEnd])) shaEnd++
    if (shaEnd > cursor) out += SyntaxToken(SyntaxTokenKind.Constant, cursor, shaEnd)
    cursor = shaEnd
    var ws2End = cursor
    while (ws2End < end && isInlineWs(source[ws2End])) ws2End++
    if (ws2End > cursor) out += SyntaxToken(SyntaxTokenKind.Whitespace, cursor, ws2End)
    if (ws2End < end) out += SyntaxToken(SyntaxTokenKind.Plain, ws2End, end)
}

private fun emitEmailHeader(
    source: String,
    start: Int,
    end: Int,
    out: MutableList<SyntaxToken>,
) {
    val colonIdx = source.indexOf(':', start)
    if (colonIdx < 0 || colonIdx >= end) {
        out += SyntaxToken(SyntaxTokenKind.Plain, start, end)
        return
    }
    if (colonIdx > start) {
        out += SyntaxToken(SyntaxTokenKind.Property, start, colonIdx)
    }
    out += SyntaxToken(SyntaxTokenKind.Punctuation, colonIdx, colonIdx + 1)
    var wsEnd = colonIdx + 1
    while (wsEnd < end && isInlineWs(source[wsEnd])) wsEnd++
    if (wsEnd > colonIdx + 1) {
        out += SyntaxToken(SyntaxTokenKind.Whitespace, colonIdx + 1, wsEnd)
    }
    if (wsEnd < end) {
        out += SyntaxToken(SyntaxTokenKind.Plain, wsEnd, end)
    }
}

private fun emitAtHunkHeader(
    source: String,
    start: Int,
    end: Int,
    out: MutableList<SyntaxToken>,
) {
    var openEnd = start
    while (openEnd < end && source[openEnd] == '@') openEnd++
    out += SyntaxToken(SyntaxTokenKind.Punctuation, start, openEnd)

    val closeStart = findClosingAtRun(source, openEnd, end)
    if (closeStart < 0) {
        if (openEnd < end) out += SyntaxToken(SyntaxTokenKind.Number, openEnd, end)
        return
    }
    var closeEnd = closeStart
    while (closeEnd < end && source[closeEnd] == '@') closeEnd++

    // Emit range inside `@@ ... @@` with leading/trailing whitespace split cleanly.
    var rangeStart = openEnd
    while (rangeStart < closeStart && isInlineWs(source[rangeStart])) rangeStart++
    if (rangeStart > openEnd) {
        out += SyntaxToken(SyntaxTokenKind.Whitespace, openEnd, rangeStart)
    }
    var rangeEnd = closeStart
    while (rangeEnd > rangeStart && isInlineWs(source[rangeEnd - 1])) rangeEnd--
    if (rangeEnd > rangeStart) {
        out += SyntaxToken(SyntaxTokenKind.Number, rangeStart, rangeEnd)
    }
    if (rangeEnd < closeStart) {
        out += SyntaxToken(SyntaxTokenKind.Whitespace, rangeEnd, closeStart)
    }

    out += SyntaxToken(SyntaxTokenKind.Punctuation, closeStart, closeEnd)

    if (closeEnd < end) {
        var tailWsEnd = closeEnd
        while (tailWsEnd < end && isInlineWs(source[tailWsEnd])) tailWsEnd++
        if (tailWsEnd > closeEnd) {
            out += SyntaxToken(SyntaxTokenKind.Whitespace, closeEnd, tailWsEnd)
        }
        if (tailWsEnd < end) {
            out += SyntaxToken(SyntaxTokenKind.Plain, tailWsEnd, end)
        }
    }
}

private fun findClosingAtRun(source: String, from: Int, end: Int): Int {
    var i = from
    while (i + 1 < end) {
        if (source[i] == '@' && source[i + 1] == '@') {
            return i
        }
        i++
    }
    return -1
}

private fun emitContextHunkHeader(
    source: String,
    start: Int,
    end: Int,
    out: MutableList<SyntaxToken>,
) {
    val openEnd = (start + 3).coerceAtMost(end)
    out += SyntaxToken(SyntaxTokenKind.Punctuation, start, openEnd)

    var trimmedEnd = end
    while (trimmedEnd > openEnd && isInlineWs(source[trimmedEnd - 1])) trimmedEnd--

    var closeStart = trimmedEnd
    val closeChar = source[start]
    while (closeStart > openEnd && source[closeStart - 1] == closeChar) closeStart--

    var rangeStart = openEnd
    while (rangeStart < closeStart && isInlineWs(source[rangeStart])) rangeStart++
    if (rangeStart > openEnd) {
        out += SyntaxToken(SyntaxTokenKind.Whitespace, openEnd, rangeStart)
    }
    var rangeEnd = closeStart
    while (rangeEnd > rangeStart && isInlineWs(source[rangeEnd - 1])) rangeEnd--
    if (rangeEnd > rangeStart) {
        out += SyntaxToken(SyntaxTokenKind.Number, rangeStart, rangeEnd)
    }
    if (rangeEnd < closeStart) {
        out += SyntaxToken(SyntaxTokenKind.Whitespace, rangeEnd, closeStart)
    }
    if (trimmedEnd > closeStart) {
        out += SyntaxToken(SyntaxTokenKind.Punctuation, closeStart, trimmedEnd)
    }
    if (trimmedEnd < end) {
        out += SyntaxToken(SyntaxTokenKind.Whitespace, trimmedEnd, end)
    }
}

private fun emitNormalHunkHeader(
    source: String,
    start: Int,
    end: Int,
    out: MutableList<SyntaxToken>,
) {
    var trimmedEnd = end
    while (trimmedEnd > start && isInlineWs(source[trimmedEnd - 1])) trimmedEnd--
    if (trimmedEnd > start) {
        out += SyntaxToken(SyntaxTokenKind.Number, start, trimmedEnd)
    }
    if (trimmedEnd < end) {
        out += SyntaxToken(SyntaxTokenKind.Whitespace, trimmedEnd, end)
    }
}

private fun emitBinaryPatchHeader(
    source: String,
    start: Int,
    end: Int,
    out: MutableList<SyntaxToken>,
) {
    var kwEnd = start
    while (kwEnd < end && !isInlineWs(source[kwEnd])) kwEnd++
    out += SyntaxToken(SyntaxTokenKind.Keyword, start, kwEnd)
    var wsEnd = kwEnd
    while (wsEnd < end && isInlineWs(source[wsEnd])) wsEnd++
    if (wsEnd > kwEnd) out += SyntaxToken(SyntaxTokenKind.Whitespace, kwEnd, wsEnd)
    var numEnd = wsEnd
    while (numEnd < end && source[numEnd] in '0'..'9') numEnd++
    if (numEnd > wsEnd) out += SyntaxToken(SyntaxTokenKind.Number, wsEnd, numEnd)
    if (numEnd < end) out += SyntaxToken(SyntaxTokenKind.Whitespace, numEnd, end)
}

private fun isInlineWs(ch: Char): Boolean = ch == ' ' || ch == '\t'

private fun skipWs(text: String, from: Int): Int {
    var idx = from
    while (idx < text.length && isInlineWs(text[idx])) idx++
    return idx
}

private fun parseCliPathToken(text: String, from: Int): Pair<String?, Int> {
    if (from >= text.length) return null to from
    if (text[from] == '"') {
        val (unquoted, nextIdx) = unquoteCPath(text, from)
        return unquoted to nextIdx
    }
    var end = from
    while (end < text.length && !isInlineWs(text[end])) end++
    return text.substring(from, end) to end
}

private fun parseSingleRemainderPath(text: String, from: Int): String? {
    if (from >= text.length) return null
    if (text[from] == '"') {
        val (unquoted, _) = unquoteCPath(text, from)
        return unquoted
    }
    return text.substring(from).trimEnd(' ', '\t').ifEmpty { null }
}

private fun parseGitDiffPair(text: String, from: Int): Pair<String?, String?> {
    if (from >= text.length) return null to null
    if (text[from] == '"') {
        val (p1, nextIdx) = unquoteCPath(text, from)
        val p2Start = skipWs(text, nextIdx)
        val p2 = parseSingleRemainderPath(text, p2Start)
        return p1 to p2
    }
    val remainder = text.substring(from)
    val quotedSecondIdx = remainder.indexOf(" \"")
    if (quotedSecondIdx > 0) {
        val p1 = remainder.substring(0, quotedSecondIdx).trimEnd(' ', '\t')
        val p2Start = skipWs(remainder, quotedSecondIdx + 1)
        val (p2, _) = parseCliPathToken(remainder, p2Start)
        return p1.ifEmpty { null } to p2
    }
    splitUnquotedGitPaths(remainder)?.let { return it }
    val (p1, nextIdx) = parseCliPathToken(text, from)
    val p2Start = skipWs(text, nextIdx)
    val (p2, _) = parseCliPathToken(text, p2Start)
    return p1 to p2
}

private fun splitUnquotedGitPaths(remainder: String): Pair<String, String>? {
    val prefixes = arrayOf("a/", "b/", "i/", "w/", "c/", "o/")
    val startsWithPrefix = prefixes.any { remainder.startsWith(it) }
    val len = remainder.length
    val mid = len / 2
    if (len >= 3 && len % 2 == 1 && remainder[mid] == ' ') {
        if (startsWithPrefix &&
            len >= 5 &&
            prefixes.any { remainder.startsWith(it, mid + 1) } &&
            remainder.regionMatches(2, remainder, mid + 3, mid - 2)
        ) {
            return remainder.substring(0, mid) to remainder.substring(mid + 1)
        }
        if (remainder.regionMatches(0, remainder, mid + 1, mid)) {
            return remainder.substring(0, mid) to remainder.substring(mid + 1)
        }
    }
    if (startsWithPrefix && len > 2) {
        // Linear fallback for renames with spaces (`a/old dir/A.kt b/new dir/B.kt`).
        for (p2 in prefixes) {
            val idx = remainder.indexOf(" $p2", startIndex = 2)
            if (idx > 2) {
                return remainder.substring(0, idx) to remainder.substring(idx + 1)
            }
        }
    }
    return null
}

private fun parsePrefixedPath(source: String, start: Int, end: Int, prefixLen: Int): String? {
    val contentStart = (start + prefixLen).coerceAtMost(end)
    if (contentStart >= end) return null
    return parseHeaderPathValue(source.substring(contentStart, end))
}

private fun parseHeaderPathValue(raw: String): String? {
    val start = skipWs(raw, 0)
    if (start >= raw.length) return null
    if (raw[start] == '"') {
        val (unquoted, _) = unquoteCPath(raw, start)
        return unquoted
    }
    val tabIdx = raw.indexOf('\t', start)
    val end = if (tabIdx >= 0) tabIdx else raw.length
    val trimmed = raw.substring(start, end).trimEnd(' ', '\t')
    val stripped = stripSpaceSeparatedHeaderSuffix(trimmed)
    return stripped.ifEmpty { null }
}

private fun stripSpaceSeparatedHeaderSuffix(text: String): String {
    if (text.endsWith(')')) {
        val openIdx = text.lastIndexOf(" (")
        if (openIdx > 0) {
            val tag = text.substring(openIdx + 2, text.length - 1).lowercase()
            if (tag.startsWith("revision ") ||
                tag == "working copy" ||
                tag == "nonexistent" ||
                tag == "left" ||
                tag == "right" ||
                tag == "original" ||
                tag == "mine" ||
                tag == "theirs"
            ) {
                return text.substring(0, openIdx).trimEnd(' ')
            }
        }
    }
    // Strip space-separated ISO dates (` 2026-11-23...`) or ctime timestamps (` Mon Nov 23 ...`).
    for (i in 1 until text.length) {
        if (text[i] == ' ' && (isIsoDateAt(text, i + 1) || isCtimeDateAt(text, i + 1))) {
            return text.substring(0, i).trimEnd(' ')
        }
    }
    return text
}

private fun isIsoDateAt(text: String, pos: Int): Boolean {
    if (pos + 10 > text.length) return false
    if (!(text[pos] in '0'..'9' &&
            text[pos + 1] in '0'..'9' &&
            text[pos + 2] in '0'..'9' &&
            text[pos + 3] in '0'..'9' &&
            text[pos + 4] == '-' &&
            text[pos + 5] in '0'..'9' &&
            text[pos + 6] in '0'..'9' &&
            text[pos + 7] == '-' &&
            text[pos + 8] in '0'..'9' &&
            text[pos + 9] in '0'..'9')
    ) {
        return false
    }
    return pos + 10 == text.length ||
        text[pos + 10] == ' ' ||
        text[pos + 10] == 'T' ||
        text[pos + 10] == 't'
}

private fun isCtimeDateAt(text: String, pos: Int): Boolean {
    if (pos + 10 > text.length) return false
    val dow = text.substring(pos, pos + 3)
    if (dow != "Mon" && dow != "Tue" && dow != "Wed" && dow != "Thu" &&
        dow != "Fri" && dow != "Sat" && dow != "Sun"
    ) {
        return false
    }
    return text[pos + 3] == ' ' &&
        text[pos + 4] in 'A'..'Z' &&
        text[pos + 5] in 'a'..'z' &&
        text[pos + 6] in 'a'..'z' &&
        text[pos + 7] == ' '
}

private fun unquoteCPath(text: String, openQuoteIdx: Int): Pair<String, Int> {
    val sb = StringBuilder()
    var i = openQuoteIdx + 1
    while (i < text.length) {
        val ch = text[i]
        if (ch == '"') {
            return sb.toString() to (i + 1)
        }
        if (ch == '\\' && i + 1 < text.length) {
            val esc = text[i + 1]
            when (esc) {
                '\\' -> {
                    sb.append('\\')
                    i += 2
                }
                '"' -> {
                    sb.append('"')
                    i += 2
                }
                'n' -> {
                    sb.append('\n')
                    i += 2
                }
                'r' -> {
                    sb.append('\r')
                    i += 2
                }
                't' -> {
                    sb.append('\t')
                    i += 2
                }
                'b' -> {
                    sb.append('\b')
                    i += 2
                }
                'a' -> {
                    sb.append('\u0007')
                    i += 2
                }
                'f' -> {
                    sb.append('\u000C')
                    i += 2
                }
                'v' -> {
                    sb.append('\u000B')
                    i += 2
                }
                in '0'..'7' -> {
                    var octalVal = esc - '0'
                    var consumed = 1
                    while (consumed < 3 && i + 1 + consumed < text.length) {
                        val d = text[i + 1 + consumed]
                        if (d in '0'..'7') {
                            octalVal = octalVal * 8 + (d - '0')
                            consumed++
                        } else {
                            break
                        }
                    }
                    sb.append(octalVal.toChar())
                    i += 1 + consumed
                }
                else -> {
                    sb.append(esc)
                    i += 2
                }
            }
        } else {
            sb.append(ch)
            i++
        }
    }
    return sb.toString() to i
}

internal fun normalizeDiffPath(rawPath: String): String? {
    val trimmed = rawPath.trim()
    if (trimmed.isEmpty() || trimmed == "/dev/null") return null
    val slashNormalized = trimmed.replace('\\', '/')
    if (slashNormalized == "/dev/null") return null
    if (slashNormalized.length >= 2 &&
        slashNormalized[1] == '/' &&
        slashNormalized[0] in charArrayOf('a', 'b', 'i', 'w', 'c', 'o')
    ) {
        return slashNormalized.substring(2)
    }
    return slashNormalized
}

internal fun resolveLanguageForNormalizedPath(normalizedPath: String): SyntaxLanguage? {
    val basename = normalizedPath.substringAfterLast('/')
    if (basename.isEmpty()) return null
    val lowerBase = basename.lowercase()

    specialBasenameLanguage(lowerBase)?.let { return it }

    val dotIdx = lowerBase.lastIndexOf('.')
    if (dotIdx < 0 || dotIdx == lowerBase.length - 1) return null
    val ext = lowerBase.substring(dotIdx + 1)

    return EXTENSION_TO_LANGUAGE[ext] ?: SyntaxLanguage.fromNameOrAlias(ext)
}

private fun specialBasenameLanguage(lowerBase: String): SyntaxLanguage? = when {
    lowerBase == "dockerfile" ||
        lowerBase.startsWith("dockerfile.") ||
        lowerBase == "containerfile" ||
        lowerBase.startsWith("containerfile.") -> SyntaxLanguage.Dockerfile

    lowerBase == "rakefile" ||
        lowerBase == "gemfile" ||
        lowerBase == "podfile" ||
        lowerBase == "vagrantfile" ||
        lowerBase == "fastfile" ||
        lowerBase == "brewfile" -> SyntaxLanguage.Ruby

    lowerBase == "jenkinsfile" ||
        lowerBase == "build.gradle" ||
        lowerBase == "settings.gradle" -> SyntaxLanguage.Groovy

    lowerBase == "build.gradle.kts" ||
        lowerBase == "settings.gradle.kts" -> SyntaxLanguage.Kotlin

    lowerBase == ".bashrc" ||
        lowerBase == ".zshrc" ||
        lowerBase == ".bash_profile" ||
        lowerBase == ".profile" ||
        lowerBase == ".zprofile" ||
        lowerBase == ".shrc" -> SyntaxLanguage.Shell

    else -> null
}

private val EXTENSION_TO_LANGUAGE: Map<String, SyntaxLanguage> = mapOf(
    "kt" to SyntaxLanguage.Kotlin,
    "kts" to SyntaxLanguage.Kotlin,
    "xml" to SyntaxLanguage.Xml,
    "svg" to SyntaxLanguage.Xml,
    "xsd" to SyntaxLanguage.Xml,
    "plist" to SyntaxLanguage.Xml,
    "json" to SyntaxLanguage.Json,
    "jsonc" to SyntaxLanguage.Json,
    "html" to SyntaxLanguage.Html,
    "htm" to SyntaxLanguage.Html,
    "xhtml" to SyntaxLanguage.Html,
    "css" to SyntaxLanguage.Css,
    "js" to SyntaxLanguage.JavaScript,
    "jsx" to SyntaxLanguage.JavaScript,
    "mjs" to SyntaxLanguage.JavaScript,
    "cjs" to SyntaxLanguage.JavaScript,
    "ts" to SyntaxLanguage.TypeScript,
    "tsx" to SyntaxLanguage.TypeScript,
    "mts" to SyntaxLanguage.TypeScript,
    "cts" to SyntaxLanguage.TypeScript,
    "rs" to SyntaxLanguage.Rust,
    "go" to SyntaxLanguage.Go,
    "py" to SyntaxLanguage.Python,
    "pyi" to SyntaxLanguage.Python,
    "toml" to SyntaxLanguage.Toml,
    "tml" to SyntaxLanguage.Toml,
    "java" to SyntaxLanguage.Java,
    "c" to SyntaxLanguage.C,
    "h" to SyntaxLanguage.C,
    "cc" to SyntaxLanguage.Cpp,
    "cxx" to SyntaxLanguage.Cpp,
    "cpp" to SyntaxLanguage.Cpp,
    "hpp" to SyntaxLanguage.Cpp,
    "hh" to SyntaxLanguage.Cpp,
    "hxx" to SyntaxLanguage.Cpp,
    "c++" to SyntaxLanguage.Cpp,
    "h++" to SyntaxLanguage.Cpp,
    "glsl" to SyntaxLanguage.Glsl,
    "vert" to SyntaxLanguage.Glsl,
    "frag" to SyntaxLanguage.Glsl,
    "comp" to SyntaxLanguage.Glsl,
    "geom" to SyntaxLanguage.Glsl,
    "tesc" to SyntaxLanguage.Glsl,
    "tese" to SyntaxLanguage.Glsl,
    "sksl" to SyntaxLanguage.Sksl,
    "agsl" to SyntaxLanguage.Agsl,
    "yml" to SyntaxLanguage.Yaml,
    "yaml" to SyntaxLanguage.Yaml,
    "sql" to SyntaxLanguage.Sql,
    "sh" to SyntaxLanguage.Shell,
    "bash" to SyntaxLanguage.Shell,
    "zsh" to SyntaxLanguage.Shell,
    "cs" to SyntaxLanguage.CSharp,
    "php" to SyntaxLanguage.Php,
    "phtml" to SyntaxLanguage.Php,
    "swift" to SyntaxLanguage.Swift,
    "rb" to SyntaxLanguage.Ruby,
    "rake" to SyntaxLanguage.Ruby,
    "gemspec" to SyntaxLanguage.Ruby,
    "dart" to SyntaxLanguage.Dart,
    "groovy" to SyntaxLanguage.Groovy,
    "gradle" to SyntaxLanguage.Groovy,
    "gvy" to SyntaxLanguage.Groovy,
    "ps1" to SyntaxLanguage.PowerShell,
    "psm1" to SyntaxLanguage.PowerShell,
    "psd1" to SyntaxLanguage.PowerShell,
    "dockerfile" to SyntaxLanguage.Dockerfile,
    "containerfile" to SyntaxLanguage.Dockerfile,
    "diff" to SyntaxLanguage.Diff,
    "patch" to SyntaxLanguage.Diff,
    "md" to SyntaxLanguage.Markdown,
    "mkd" to SyntaxLanguage.Markdown,
    "mdx" to SyntaxLanguage.Markdown,
    "markdown" to SyntaxLanguage.Markdown,
    "proto" to SyntaxLanguage.Protobuf,
    "hcl" to SyntaxLanguage.Hcl,
    "tf" to SyntaxLanguage.Hcl,
    "tfvars" to SyntaxLanguage.Hcl,
)
