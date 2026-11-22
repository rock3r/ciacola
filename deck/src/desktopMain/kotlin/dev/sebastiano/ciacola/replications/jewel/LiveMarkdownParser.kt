package dev.sebastiano.ciacola.replications.jewel

import androidx.compose.runtime.Immutable

// ---------------------------------------------------------------------------
// Shared Pure-Kotlin CommonMark + GFM AST Model & Parser
// ---------------------------------------------------------------------------

enum class MarkdownCalloutKind(val title: String) {
    Tip("Tip"),
    Warning("Warning"),
    Note("Note"),
    Important("Important"),
}

enum class MarkdownTableAlignment {
    Left,
    Center,
    Right,
}

@Immutable
sealed interface MarkdownInline {
    @Immutable data class Plain(val text: String) : MarkdownInline
    @Immutable data class Bold(val children: List<MarkdownInline>) : MarkdownInline
    @Immutable data class Italic(val children: List<MarkdownInline>) : MarkdownInline
    @Immutable data class Strike(val children: List<MarkdownInline>) : MarkdownInline
    @Immutable data class Code(val text: String) : MarkdownInline
    @Immutable data class Link(
        val label: String,
        val url: String,
        val children: List<MarkdownInline> = listOf(Plain(label)),
    ) : MarkdownInline
}

@Immutable
data class MarkdownListItem(
    val inlines: List<MarkdownInline>,
    val children: List<MarkdownBlock> = emptyList(),
)

@Immutable
data class MarkdownTaskItem(
    val checked: Boolean,
    val content: List<MarkdownInline>,
    val children: List<MarkdownBlock> = emptyList(),
)

@Immutable
sealed interface MarkdownBlock {
    @Immutable data class Heading(val level: Int, val inlines: List<MarkdownInline>) : MarkdownBlock
    @Immutable data class Paragraph(val inlines: List<MarkdownInline>) : MarkdownBlock
    @Immutable data class BlockQuote(val blocks: List<MarkdownBlock>) : MarkdownBlock
    @Immutable data class Callout(val kind: MarkdownCalloutKind, val blocks: List<MarkdownBlock>) : MarkdownBlock
    @Immutable data class CodeBlock(val language: String, val code: String) : MarkdownBlock
    @Immutable data class BulletList(val items: List<MarkdownListItem>) : MarkdownBlock
    @Immutable data class OrderedList(val start: Int, val items: List<MarkdownListItem>) : MarkdownBlock
    @Immutable data class TaskList(val items: List<MarkdownTaskItem>) : MarkdownBlock
    @Immutable data class Table(
        val headers: List<List<MarkdownInline>>,
        val rows: List<List<List<MarkdownInline>>>,
        val alignments: List<MarkdownTableAlignment> = emptyList(),
    ) : MarkdownBlock
    @Immutable data object ThematicBreak : MarkdownBlock
}

private data class FenceHeader(val indent: Int, val char: Char, val length: Int, val language: String)

private enum class ListKind { Task, Bullet, Ordered }

private data class ListMarker(
    val kind: ListKind,
    val baseIndent: Int,
    val contentIndent: Int,
    val content: String,
    val orderedStart: Int = 1,
    val taskChecked: Boolean = false,
)

private val linkRefDefRegex = Regex("""^\s{0,3}\[([^\]]+)]:\s*(?:<([^>\n]+)>|(\S+))(?:\s+(?:"[^"]*"|'[^']*'|\([^)]*\)))?\s*$""")
private val setextHeadingRegex = Regex("""^\s{0,3}(=+|-+)\s*$""")
private val calloutHeaderRegex = Regex("""^\[!(TIP|WARNING|NOTE|IMPORTANT)]\s*(.*)$""", RegexOption.IGNORE_CASE)
private val atxHeadingRegex = Regex("""^\s{0,3}(#{1,6})(?:\s+(.*?))?\s*$""")
private val taskListMarkerRegex = Regex("""^([ \t]*)([-*+])[ \t]+\[([ xX])][ \t]+(.*)$""")
private val bulletListMarkerRegex = Regex("""^([ \t]*)([-*+])[ \t]+(.*)$""")
private val orderedListMarkerRegex = Regex("""^([ \t]*)(\d{1,9})([.)])[ \t]+(.*)$""")

private const val ASCII_PUNCTUATION: String = "!\"#\$%&'()*+,-./:;<=>?@[\\]^_`{|}~"

/**
 * Parses a CommonMark + GFM Markdown document into immutable [MarkdownBlock] AST nodes.
 *
 * Supports:
 * - Reference link definitions (`[label]: url "title"`) resolved case-insensitively across blocks;
 * - Fenced code blocks (`` ``` `` / `~~~`, length-matched closing fences) and 4-space indented code blocks;
 * - ATX (`#`..`######`) and Setext (`===` / `---`) headings and thematic breaks;
 * - Recursive nested blockquotes and GFM callouts (`> [!TIP|WARNING|NOTE|IMPORTANT]`);
 * - GFM tables with column alignments (`:---`, `:---:`, `---:`), escaped pipes (`\|`), and code-span pipes;
 * - Nested bullet, ordered, and GFM task lists with recursive child blocks;
 * - CommonMark inline parsing: ASCII backslash escapes, multi-backtick code spans, CommonMark
 *   flanking emphasis (`*`/`**`/`***`, `_`/`__`/`___`), GFM `~~strike~~`, inline links with balanced
 *   parentheses & formatted labels, full/collapsed/shortcut reference links, and autolinks.
 */
fun parseMarkdownDocument(source: String): List<MarkdownBlock> {
    val rawLines = source.replace("\r\n", "\n").replace('\r', '\n').split('\n')
    val linkRefs = linkedMapOf<String, String>()
    val bodyLines = extractReferenceDefinitions(rawLines, linkRefs)
    return parseMarkdownBlocks(bodyLines, linkRefs)
}

private fun normalizeRefLabel(label: String): String =
    label.trim().replace(Regex("""\s+"""), " ").lowercase()

private fun extractReferenceDefinitions(
    lines: List<String>,
    outRefs: MutableMap<String, String>,
): List<String> {
    val kept = ArrayList<String>(lines.size)
    var activeFence: FenceHeader? = null
    for (line in lines) {
        if (activeFence != null) {
            kept += line
            if (isClosingFence(line, activeFence)) {
                activeFence = null
            }
            continue
        }
        val opened = parseOpeningFence(line)
        if (opened != null) {
            activeFence = opened
            kept += line
            continue
        }
        val m = linkRefDefRegex.matchEntire(line)
        if (m != null) {
            val key = normalizeRefLabel(m.groupValues[1])
            val dest = m.groupValues[2].ifEmpty { m.groupValues[3] }
            if (key.isNotEmpty() && key !in outRefs) {
                outRefs[key] = unescapeAsciiPunctuation(dest)
            }
        } else {
            kept += line
        }
    }
    return kept
}

private fun leadingColumnIndent(line: String): Int {
    var cols = 0
    for (ch in line) {
        when (ch) {
            ' ' -> cols++
            '\t' -> cols += 4 - (cols % 4)
            else -> break
        }
    }
    return cols
}

private fun stripLeadingColumns(line: String, columnsToStrip: Int): String {
    var stripped = 0
    var idx = 0
    while (idx < line.length && stripped < columnsToStrip) {
        when (line[idx]) {
            ' ' -> {
                stripped++
                idx++
            }
            '\t' -> {
                val advance = 4 - (stripped % 4)
                stripped += advance
                idx++
            }
            else -> break
        }
    }
    return line.substring(idx)
}

private fun parseOpeningFence(line: String): FenceHeader? {
    val indent = leadingColumnIndent(line)
    if (indent > 3) return null
    val trimmed = line.trimStart(' ', '\t')
    if (trimmed.length < 3) return null
    val ch = trimmed[0]
    if (ch != '`' && ch != '~') return null
    val len = trimmed.takeWhile { it == ch }.length
    if (len < 3) return null
    val info = trimmed.substring(len).trim()
    // CommonMark fenced code blocks: backtick info strings cannot contain backtick characters
    if (ch == '`' && info.contains('`')) return null
    val lang = info.takeWhile { !it.isWhitespace() }
    return FenceHeader(indent = indent, char = ch, length = len, language = lang)
}

private fun isClosingFence(line: String, opening: FenceHeader): Boolean {
    if (leadingColumnIndent(line) > 3) return false
    val trimmed = line.trim()
    return trimmed.length >= opening.length && trimmed.all { it == opening.char }
}

private fun isThematicBreak(line: String): Boolean {
    if (leadingColumnIndent(line) > 3) return false
    val stripped = line.filterNot { it == ' ' || it == '\t' }
    if (stripped.length < 3) return false
    val ch = stripped[0]
    return (ch == '-' || ch == '*' || ch == '_') && stripped.all { it == ch }
}

private fun parseAtxHeading(line: String): Pair<Int, String>? {
    if (leadingColumnIndent(line) > 3) return null
    val m = atxHeadingRegex.matchEntire(line) ?: return null
    val level = m.groupValues[1].length
    var content = m.groupValues[2].trim()
    // CommonMark ATX headings: optional closing sequence of #s preceded by whitespace
    val withoutTrailingHashes = content.trimEnd('#')
    if (withoutTrailingHashes.isEmpty()) {
        content = ""
    } else if (withoutTrailingHashes.length < content.length && withoutTrailingHashes.last().isWhitespace()) {
        content = withoutTrailingHashes.trimEnd()
    }
    return level to content
}

private fun isBlockQuoteLine(line: String): Boolean =
    leadingColumnIndent(line) <= 3 && line.trimStart(' ', '\t').startsWith(">")

private fun stripBlockQuoteMarker(line: String): String {
    val afterLead = line.trimStart(' ', '\t').removePrefix(">")
    return when {
        afterLead.startsWith(" ") -> afterLead.substring(1)
        afterLead.startsWith("\t") -> "   " + afterLead.substring(1)
        else -> afterLead
    }
}

private fun parseListMarker(line: String): ListMarker? {
    val task = taskListMarkerRegex.matchEntire(line)
    if (task != null) {
        val baseIndent = leadingColumnIndent(task.groupValues[1])
        val markerText = task.groupValues[2] + " [" + task.groupValues[3] + "] "
        return ListMarker(
            kind = ListKind.Task,
            baseIndent = baseIndent,
            contentIndent = baseIndent + markerText.length,
            content = task.groupValues[4],
            taskChecked = task.groupValues[3].equals("x", ignoreCase = true),
        )
    }
    val bullet = bulletListMarkerRegex.matchEntire(line)
    if (bullet != null) {
        val baseIndent = leadingColumnIndent(bullet.groupValues[1])
        val markerWidth = bullet.groupValues[2].length + 1
        return ListMarker(
            kind = ListKind.Bullet,
            baseIndent = baseIndent,
            contentIndent = baseIndent + markerWidth,
            content = bullet.groupValues[3],
        )
    }
    val ordered = orderedListMarkerRegex.matchEntire(line)
    if (ordered != null) {
        val baseIndent = leadingColumnIndent(ordered.groupValues[1])
        val digits = ordered.groupValues[2]
        val markerWidth = digits.length + ordered.groupValues[3].length + 1
        return ListMarker(
            kind = ListKind.Ordered,
            baseIndent = baseIndent,
            contentIndent = baseIndent + markerWidth,
            content = ordered.groupValues[4],
            orderedStart = digits.toIntOrNull() ?: 1,
        )
    }
    return null
}

private fun parseTableDelimiterAlignments(line: String): List<MarkdownTableAlignment>? {
    val trimmed = line.trim()
    if (!trimmed.contains('-') || !trimmed.contains('|')) return null
    val cells = splitTableRow(trimmed)
    if (cells.isEmpty()) return null
    val alignments = ArrayList<MarkdownTableAlignment>(cells.size)
    for (rawCell in cells) {
        val cell = rawCell.trim()
        if (cell.isEmpty()) return null
        val leftColon = cell.startsWith(":")
        val rightColon = cell.endsWith(":")
        val middle = cell.removePrefix(":").removeSuffix(":")
        if (middle.isEmpty() || !middle.all { it == '-' }) return null
        alignments += when {
            leftColon && rightColon -> MarkdownTableAlignment.Center
            rightColon -> MarkdownTableAlignment.Right
            else -> MarkdownTableAlignment.Left
        }
    }
    return alignments
}

/**
 * Splits a GFM table row into cells while respecting escaped pipes (`\|`) and backtick code spans
 * containing literal pipes (`` `a | b` ``).
 */
private fun splitTableRow(line: String): List<String> {
    var s = line.trim()
    if (s.startsWith("|")) s = s.substring(1)
    if (s.endsWith("|") && !isEscapedAt(s, s.lastIndex)) s = s.dropLast(1)
    val cells = mutableListOf<String>()
    val buf = StringBuilder()
    var i = 0
    var inCodeTicks = 0
    while (i < s.length) {
        val ch = s[i]
        // GFM tables: escaped pipe `\|` becomes a literal `|` even inside code spans
        if (ch == '\\' && i + 1 < s.length && s[i + 1] == '|') {
            buf.append('|')
            i += 2
            continue
        }
        if (ch == '`') {
            var run = 0
            while (i + run < s.length && s[i + run] == '`') run++
            if (inCodeTicks == 0) {
                inCodeTicks = run
            } else if (inCodeTicks == run) {
                inCodeTicks = 0
            }
            buf.append(s, i, i + run)
            i += run
            continue
        }
        if (inCodeTicks == 0 && ch == '|') {
            cells += buf.toString().trim()
            buf.clear()
            i++
            continue
        }
        buf.append(ch)
        i++
    }
    cells += buf.toString().trim()
    return cells
}

private fun isEscapedAt(text: String, index: Int): Boolean {
    var backslashes = 0
    var k = index - 1
    while (k >= 0 && text[k] == '\\') {
        backslashes++
        k--
    }
    return (backslashes % 2) == 1
}

private fun parseMarkdownBlocks(
    lines: List<String>,
    linkRefs: Map<String, String>,
): List<MarkdownBlock> {
    val blocks = mutableListOf<MarkdownBlock>()
    var i = 0

    while (i < lines.size) {
        val raw = lines[i]
        val trimmed = raw.trim()

        if (trimmed.isEmpty()) {
            i++
            continue
        }

        // 1. Fenced code block (``` or ~~~)
        val fence = parseOpeningFence(raw)
        if (fence != null) {
            val codeLines = mutableListOf<String>()
            i++
            while (i < lines.size && !isClosingFence(lines[i], fence)) {
                codeLines += stripLeadingColumns(lines[i], fence.indent)
                i++
            }
            if (i < lines.size) i++
            blocks += MarkdownBlock.CodeBlock(language = fence.language, code = codeLines.joinToString("\n"))
            continue
        }

        // 2. Indented code block (4+ leading columns)
        if (leadingColumnIndent(raw) >= 4) {
            val codeLines = mutableListOf<String>()
            while (i < lines.size) {
                val cur = lines[i]
                if (cur.isBlank()) {
                    codeLines += ""
                    i++
                } else if (leadingColumnIndent(cur) >= 4) {
                    codeLines += stripLeadingColumns(cur, 4)
                    i++
                } else {
                    break
                }
            }
            while (codeLines.isNotEmpty() && codeLines.last().isEmpty()) {
                codeLines.removeAt(codeLines.lastIndex)
            }
            blocks += MarkdownBlock.CodeBlock(language = "", code = codeLines.joinToString("\n"))
            continue
        }

        // 3. Thematic break (--- / *** / ___ / - - -)
        if (isThematicBreak(raw)) {
            blocks += MarkdownBlock.ThematicBreak
            i++
            continue
        }

        // 4. ATX Heading (# .. ######)
        val atx = parseAtxHeading(raw)
        if (atx != null) {
            blocks += MarkdownBlock.Heading(level = atx.first, inlines = parseMarkdownInlines(atx.second, linkRefs))
            i++
            continue
        }

        // 5. Blockquote / GFM Callout (supports nested quotes, lists, code blocks)
        if (isBlockQuoteLine(raw)) {
            val quoteLines = mutableListOf<String>()
            while (i < lines.size && isBlockQuoteLine(lines[i])) {
                quoteLines += stripBlockQuoteMarker(lines[i])
                i++
            }
            val firstNonBlank = quoteLines.firstOrNull { it.isNotBlank() }?.trim().orEmpty()
            val calloutMatch = calloutHeaderRegex.matchEntire(firstNonBlank)
            if (calloutMatch != null) {
                val kind = when (calloutMatch.groupValues[1].uppercase()) {
                    "TIP" -> MarkdownCalloutKind.Tip
                    "WARNING" -> MarkdownCalloutKind.Warning
                    "IMPORTANT" -> MarkdownCalloutKind.Important
                    else -> MarkdownCalloutKind.Note
                }
                val firstIdx = quoteLines.indexOfFirst { it.isNotBlank() }
                val remainder = buildList {
                    val trailing = calloutMatch.groupValues[2].trim()
                    if (trailing.isNotEmpty()) add(trailing)
                    if (firstIdx >= 0 && firstIdx + 1 < quoteLines.size) {
                        addAll(quoteLines.subList(firstIdx + 1, quoteLines.size))
                    }
                }
                blocks += MarkdownBlock.Callout(
                    kind = kind,
                    blocks = parseMarkdownBlocks(remainder, linkRefs),
                )
            } else {
                blocks += MarkdownBlock.BlockQuote(parseMarkdownBlocks(quoteLines, linkRefs))
            }
            continue
        }

        // 6. GFM Table (header + delimiter row with alignments)
        if (trimmed.contains('|') && i + 1 < lines.size) {
            val alignments = parseTableDelimiterAlignments(lines[i + 1])
            val headerCells = splitTableRow(raw)
            if (alignments != null && headerCells.size == alignments.size) {
                val headers = headerCells.map { parseMarkdownInlines(it, linkRefs) }
                i += 2
                val rows = mutableListOf<List<List<MarkdownInline>>>()
                while (i < lines.size) {
                    val rowLine = lines[i]
                    val rowTrim = rowLine.trim()
                    if (rowTrim.isEmpty() || !rowTrim.contains('|') ||
                        parseOpeningFence(rowLine) != null ||
                        parseAtxHeading(rowLine) != null ||
                        isBlockQuoteLine(rowLine) ||
                        isThematicBreak(rowLine)
                    ) {
                        break
                    }
                    val rawCells = splitTableRow(rowLine)
                    val normalizedCells = List(headers.size) { col ->
                        parseMarkdownInlines(rawCells.getOrElse(col) { "" }, linkRefs)
                    }
                    rows += normalizedCells
                    i++
                }
                blocks += MarkdownBlock.Table(headers = headers, rows = rows, alignments = alignments)
                continue
            }
        }

        // 7. Nested Bullet / Ordered / GFM Task Lists
        val firstMarker = parseListMarker(raw)
        if (firstMarker != null) {
            val listKind = firstMarker.kind
            val baseIndent = firstMarker.baseIndent
            val startNum = firstMarker.orderedStart
            val bulletOrOrderedItems = mutableListOf<MarkdownListItem>()
            val taskItems = mutableListOf<MarkdownTaskItem>()

            while (i < lines.size) {
                val marker = parseListMarker(lines[i]) ?: break
                if (marker.kind != listKind || marker.baseIndent != baseIndent) break

                val primaryLines = mutableListOf(marker.content)
                val childLines = mutableListOf<String>()
                val childStripCols = marker.contentIndent.coerceAtLeast(baseIndent + 2)
                i++

                while (i < lines.size) {
                    val next = lines[i]
                    if (next.isBlank()) {
                        // Keep blank line only if followed by a more-indented continuation of this item
                        var lookahead = i + 1
                        while (lookahead < lines.size && lines[lookahead].isBlank()) lookahead++
                        if (lookahead < lines.size && leadingColumnIndent(lines[lookahead]) > baseIndent) {
                            childLines += ""
                            i++
                            continue
                        } else {
                            break
                        }
                    }
                    val nextIndent = leadingColumnIndent(next)
                    if (nextIndent > baseIndent) {
                        val strip = minOf(nextIndent, childStripCols)
                        childLines += stripLeadingColumns(next, strip)
                        i++
                        continue
                    }
                    // Sibling list item or new block starts here
                    if (parseListMarker(next) != null ||
                        parseOpeningFence(next) != null ||
                        parseAtxHeading(next) != null ||
                        isBlockQuoteLine(next) ||
                        isThematicBreak(next)
                    ) {
                        break
                    }
                    // Lazy paragraph continuation if no child blocks have started yet
                    if (childLines.isEmpty()) {
                        primaryLines += next.trim()
                        i++
                    } else {
                        break
                    }
                }

                val parsedInlines = parseMarkdownInlines(primaryLines.joinToString(" "), linkRefs)
                val parsedChildren = if (childLines.any { it.isNotBlank() }) {
                    parseMarkdownBlocks(childLines, linkRefs)
                } else {
                    emptyList()
                }
                if (listKind == ListKind.Task) {
                    taskItems += MarkdownTaskItem(
                        checked = marker.taskChecked,
                        content = parsedInlines,
                        children = parsedChildren,
                    )
                } else {
                    bulletOrOrderedItems += MarkdownListItem(
                        inlines = parsedInlines,
                        children = parsedChildren,
                    )
                }
            }

            blocks += when (listKind) {
                ListKind.Task -> MarkdownBlock.TaskList(taskItems)
                ListKind.Bullet -> MarkdownBlock.BulletList(bulletOrOrderedItems)
                ListKind.Ordered -> MarkdownBlock.OrderedList(start = startNum, items = bulletOrOrderedItems)
            }
            continue
        }

        // 8. Paragraph (or Setext heading when underlined by === / ---)
        val paraLines = mutableListOf<String>()
        var setextLevel: Int? = null
        while (i < lines.size) {
            val candidate = lines[i]
            val ct = candidate.trim()
            if (ct.isEmpty()) break
            if (paraLines.isNotEmpty()) {
                val setext = setextHeadingRegex.matchEntire(candidate)
                if (setext != null) {
                    setextLevel = if (setext.groupValues[1][0] == '=') 1 else 2
                    i++
                    break
                }
            }
            if (parseOpeningFence(candidate) != null ||
                isThematicBreak(candidate) ||
                parseAtxHeading(candidate) != null ||
                isBlockQuoteLine(candidate) ||
                parseListMarker(candidate) != null ||
                (ct.contains('|') && i + 1 < lines.size && parseTableDelimiterAlignments(lines[i + 1]) != null)
            ) {
                break
            }
            paraLines += ct
            i++
        }
        if (paraLines.isNotEmpty()) {
            val inlines = parseMarkdownInlines(paraLines.joinToString(" "), linkRefs)
            if (setextLevel != null) {
                blocks += MarkdownBlock.Heading(level = setextLevel, inlines = inlines)
            } else {
                blocks += MarkdownBlock.Paragraph(inlines)
            }
        } else {
            i++
        }
    }

    return blocks
}

fun parseMarkdownInlines(
    text: String,
    linkRefs: Map<String, String> = emptyMap(),
): List<MarkdownInline> {
    val result = mutableListOf<MarkdownInline>()
    var i = 0
    val plain = StringBuilder()

    fun flushPlain() {
        if (plain.isNotEmpty()) {
            result += MarkdownInline.Plain(plain.toString())
            plain.clear()
        }
    }

    while (i < text.length) {
        val ch = text[i]

        // 1. CommonMark backslash escape of ASCII punctuation
        if (ch == '\\' && i + 1 < text.length && text[i + 1] in ASCII_PUNCTUATION) {
            plain.append(text[i + 1])
            i += 2
            continue
        }

        // 2. CommonMark multi-backtick code spans
        if (ch == '`') {
            var ticks = 0
            while (i + ticks < text.length && text[i + ticks] == '`') ticks++
            val closeStart = findMatchingBacktickRun(text, i + ticks, ticks)
            if (closeStart >= 0) {
                flushPlain()
                var rawCode = text.substring(i + ticks, closeStart).replace('\n', ' ')
                if (rawCode.length >= 2 &&
                    rawCode.first() == ' ' &&
                    rawCode.last() == ' ' &&
                    rawCode.any { it != ' ' }
                ) {
                    rawCode = rawCode.substring(1, rawCode.length - 1)
                }
                result += MarkdownInline.Code(rawCode)
                i = closeStart + ticks
            } else {
                // Unmatched backtick run: consume entire run literally
                plain.append(text, i, i + ticks)
                i += ticks
            }
            continue
        }

        // 3. CommonMark angle-bracket autolink (<https://...>, <mailto:...>)
        if (ch == '<') {
            val close = text.indexOf('>', i + 1)
            if (close > i + 1) {
                val candidate = text.substring(i + 1, close)
                if ((candidate.startsWith("http://") || candidate.startsWith("https://") || candidate.startsWith("mailto:")) &&
                    candidate.none { it.isWhitespace() || it == '<' }
                ) {
                    flushPlain()
                    val display = candidate.removePrefix("mailto:")
                    result += MarkdownInline.Link(
                        label = display,
                        url = candidate,
                        children = listOf(MarkdownInline.Plain(display)),
                    )
                    i = close + 1
                    continue
                }
            }
        }

        // 4. Inline & reference links: [label](url "title"), [label][ref], [label][], [label]
        if (ch == '[') {
            val closeBracket = findMatchingBracket(text, i)
            if (closeBracket > i) {
                val rawLabel = text.substring(i + 1, closeBracket)
                val nextIdx = closeBracket + 1
                // 4a. Inline link [label](dest)
                if (nextIdx < text.length && text[nextIdx] == '(') {
                    val parsedDest = parseInlineLinkDestination(text, nextIdx)
                    if (parsedDest != null) {
                        val (url, afterParen) = parsedDest
                        flushPlain()
                        val labelChildren = parseMarkdownInlines(rawLabel, linkRefs)
                        result += MarkdownInline.Link(
                            label = plainTextOf(labelChildren),
                            url = url,
                            children = labelChildren,
                        )
                        i = afterParen
                        continue
                    }
                }
                // 4b. Full or collapsed reference link [label][ref] / [label][]
                if (nextIdx < text.length && text[nextIdx] == '[') {
                    val closeRef = findMatchingBracket(text, nextIdx)
                    if (closeRef > nextIdx) {
                        val refKeyRaw = text.substring(nextIdx + 1, closeRef).ifEmpty { rawLabel }
                        val resolvedUrl = linkRefs[normalizeRefLabel(refKeyRaw)]
                        if (resolvedUrl != null) {
                            flushPlain()
                            val labelChildren = parseMarkdownInlines(rawLabel, linkRefs)
                            result += MarkdownInline.Link(
                                label = plainTextOf(labelChildren),
                                url = resolvedUrl,
                                children = labelChildren,
                            )
                            i = closeRef + 1
                            continue
                        }
                    }
                }
                // 4c. Shortcut reference link [label]
                val shortcutUrl = linkRefs[normalizeRefLabel(rawLabel)]
                if (shortcutUrl != null) {
                    flushPlain()
                    val labelChildren = parseMarkdownInlines(rawLabel, linkRefs)
                    result += MarkdownInline.Link(
                        label = plainTextOf(labelChildren),
                        url = shortcutUrl,
                        children = labelChildren,
                    )
                    i = closeBracket + 1
                    continue
                }
            }
        }

        // 5. GFM strikethrough ~~...~~
        if (ch == '~' && text.startsWith("~~", i)) {
            val close = findClosingDelimiter(text, i + 2, '~', 2, enforceFlanking = false)
            if (close > i + 2) {
                flushPlain()
                result += MarkdownInline.Strike(parseMarkdownInlines(text.substring(i + 2, close), linkRefs))
                i = close + 2
                continue
            }
        }

        // 6. CommonMark emphasis (* / ** / *** and _ / __ / ___) with flanking delimiter rules
        if (ch == '*' || ch == '_') {
            var runLen = 0
            while (i + runLen < text.length && text[i + runLen] == ch) runLen++
            if (canOpenEmphasis(text, i, i + runLen, ch)) {
                var matched = false
                if (runLen >= 3) {
                    val close3 = findClosingDelimiter(text, i + 3, ch, 3, enforceFlanking = true)
                    if (close3 > i + 3) {
                        flushPlain()
                        val inner = parseMarkdownInlines(text.substring(i + 3, close3), linkRefs)
                        result += MarkdownInline.Bold(listOf(MarkdownInline.Italic(inner)))
                        i = close3 + 3
                        matched = true
                    }
                }
                if (!matched && runLen >= 2) {
                    val close2 = findClosingDelimiter(text, i + 2, ch, 2, enforceFlanking = true)
                    if (close2 > i + 2) {
                        flushPlain()
                        val inner = parseMarkdownInlines(text.substring(i + 2, close2), linkRefs)
                        result += MarkdownInline.Bold(inner)
                        i = close2 + 2
                        matched = true
                    }
                }
                if (!matched) {
                    val close1 = findClosingDelimiter(text, i + 1, ch, 1, enforceFlanking = true)
                    if (close1 > i + 1) {
                        flushPlain()
                        val inner = parseMarkdownInlines(text.substring(i + 1, close1), linkRefs)
                        result += MarkdownInline.Italic(inner)
                        i = close1 + 1
                        matched = true
                    }
                }
                if (matched) continue
            }
            // Did not open a valid emphasis span — emit the entire delimiter run literally
            plain.append(text, i, i + runLen)
            i += runLen
            continue
        }

        // 7. GFM bare URL autolink (https://... or http://...)
        if ((text.startsWith("https://", i) || text.startsWith("http://", i)) &&
            (i == 0 || text[i - 1].isWhitespace() || text[i - 1] in "([<")
        ) {
            var end = i
            while (end < text.length && !text[end].isWhitespace() && text[end] !in "<>\"'") end++
            while (end > i && text[end - 1] in ".,;:!?") end--
            // Trim unbalanced trailing ')'
            var candidate = text.substring(i, end)
            while (candidate.endsWith(")") && candidate.count { it == ')' } > candidate.count { it == '(' }) {
                candidate = candidate.dropLast(1)
                end--
            }
            if (candidate.length > "https://".length) {
                flushPlain()
                result += MarkdownInline.Link(
                    label = candidate,
                    url = candidate,
                    children = listOf(MarkdownInline.Plain(candidate)),
                )
                i = end
                continue
            }
        }

        plain.append(ch)
        i++
    }

    flushPlain()
    return result
}

private fun unescapeAsciiPunctuation(text: String): String {
    val sb = StringBuilder(text.length)
    var i = 0
    while (i < text.length) {
        if (text[i] == '\\' && i + 1 < text.length && text[i + 1] in ASCII_PUNCTUATION) {
            sb.append(text[i + 1])
            i += 2
        } else {
            sb.append(text[i++])
        }
    }
    return sb.toString()
}

private fun plainTextOf(inlines: List<MarkdownInline>): String = buildString {
    fun visit(nodes: List<MarkdownInline>) {
        for (n in nodes) {
            when (n) {
                is MarkdownInline.Plain -> append(n.text)
                is MarkdownInline.Code -> append(n.text)
                is MarkdownInline.Bold -> visit(n.children)
                is MarkdownInline.Italic -> visit(n.children)
                is MarkdownInline.Strike -> visit(n.children)
                is MarkdownInline.Link -> visit(n.children)
            }
        }
    }
    visit(inlines)
}

private fun findMatchingBacktickRun(text: String, fromIndex: Int, requiredLength: Int): Int {
    var j = fromIndex
    while (j < text.length) {
        if (text[j] == '`') {
            var run = 0
            while (j + run < text.length && text[j + run] == '`') run++
            if (run == requiredLength) return j
            j += run
        } else {
            j++
        }
    }
    return -1
}

private fun findMatchingBracket(text: String, openIndex: Int): Int {
    var depth = 0
    var j = openIndex
    while (j < text.length) {
        val c = text[j]
        if (c == '\\' && j + 1 < text.length) {
            j += 2
            continue
        }
        if (c == '`') {
            var ticks = 0
            while (j + ticks < text.length && text[j + ticks] == '`') ticks++
            val close = findMatchingBacktickRun(text, j + ticks, ticks)
            j = if (close >= 0) close + ticks else j + ticks
            continue
        }
        if (c == '[') depth++
        else if (c == ']') {
            depth--
            if (depth == 0) return j
        }
        j++
    }
    return -1
}

private fun parseInlineLinkDestination(text: String, openParenIndex: Int): Pair<String, Int>? {
    var j = openParenIndex + 1
    while (j < text.length && text[j].isWhitespace()) j++
    if (j >= text.length) return null

    val url: String
    if (text[j] == '<') {
        val closeAngle = text.indexOf('>', j + 1)
        if (closeAngle < 0) return null
        url = unescapeAsciiPunctuation(text.substring(j + 1, closeAngle))
        j = closeAngle + 1
    } else {
        val start = j
        var parenDepth = 0
        while (j < text.length) {
            val c = text[j]
            if (c == '\\' && j + 1 < text.length && text[j + 1] in ASCII_PUNCTUATION) {
                j += 2
                continue
            }
            if (c.isWhitespace()) break
            if (c == '(') {
                parenDepth++
            } else if (c == ')') {
                if (parenDepth == 0) break
                parenDepth--
            }
            j++
        }
        url = unescapeAsciiPunctuation(text.substring(start, j))
    }

    while (j < text.length && text[j].isWhitespace()) j++
    // Optional link title: "title", 'title', or (title)
    if (j < text.length && (text[j] == '"' || text[j] == '\'' || text[j] == '(')) {
        val closeQuote = if (text[j] == '(') ')' else text[j]
        j++
        while (j < text.length && text[j] != closeQuote) {
            if (text[j] == '\\' && j + 1 < text.length) j += 2 else j++
        }
        if (j >= text.length) return null
        j++ // consume closing quote
        while (j < text.length && text[j].isWhitespace()) j++
    }
    if (j >= text.length || text[j] != ')') return null
    return url to (j + 1)
}

private fun isMarkdownPunctuation(ch: Char): Boolean =
    ch in ASCII_PUNCTUATION || !ch.isLetterOrDigit() && !ch.isWhitespace()

private fun isLeftFlanking(text: String, start: Int, end: Int): Boolean {
    val before = if (start == 0) ' ' else text[start - 1]
    val after = if (end >= text.length) ' ' else text[end]
    return !after.isWhitespace() &&
        (!isMarkdownPunctuation(after) || before.isWhitespace() || isMarkdownPunctuation(before))
}

private fun isRightFlanking(text: String, start: Int, end: Int): Boolean {
    val before = if (start == 0) ' ' else text[start - 1]
    val after = if (end >= text.length) ' ' else text[end]
    return !before.isWhitespace() &&
        (!isMarkdownPunctuation(before) || after.isWhitespace() || isMarkdownPunctuation(after))
}

private fun canOpenEmphasis(text: String, start: Int, end: Int, ch: Char): Boolean {
    val left = isLeftFlanking(text, start, end)
    if (!left) return false
    if (ch == '*') return true
    val right = isRightFlanking(text, start, end)
    val before = if (start == 0) ' ' else text[start - 1]
    return !right || isMarkdownPunctuation(before)
}

private fun canCloseEmphasis(text: String, start: Int, end: Int, ch: Char): Boolean {
    val right = isRightFlanking(text, start, end)
    if (!right) return false
    if (ch == '*') return true
    val left = isLeftFlanking(text, start, end)
    val after = if (end >= text.length) ' ' else text[end]
    return !left || isMarkdownPunctuation(after)
}

private fun findClosingDelimiter(
    text: String,
    fromIndex: Int,
    delim: Char,
    requiredCount: Int,
    enforceFlanking: Boolean,
): Int {
    var j = fromIndex
    while (j < text.length) {
        val c = text[j]
        if (c == '\\' && j + 1 < text.length) {
            j += 2
            continue
        }
        if (c == '`') {
            var ticks = 0
            while (j + ticks < text.length && text[j + ticks] == '`') ticks++
            val close = findMatchingBacktickRun(text, j + ticks, ticks)
            j = if (close >= 0) close + ticks else j + ticks
            continue
        }
        if (c == delim) {
            var run = 0
            while (j + run < text.length && text[j + run] == delim) run++
            if (run >= requiredCount) {
                val closeStart = j + run - requiredCount
                val valid = if (!enforceFlanking) {
                    true
                } else {
                    canCloseEmphasis(text, j, j + run, delim)
                }
                if (valid && closeStart > fromIndex) {
                    return closeStart
                }
            }
            j += run
            continue
        }
        j++
    }
    return -1
}
