package dev.sebastiano.ciacola.inspector.element

import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import dev.sebastiano.ciacola.document.DeckDocument
import dev.sebastiano.ciacola.document.DeckStructureEdits
import dev.sebastiano.ciacola.document.ManualDelayRemapNotice
import dev.sebastiano.ciacola.document.TextRangeEdit

internal const val INLINE_EDITOR_MARGIN_PX = 16f
internal const val INLINE_EDITOR_GAP_PX = 12f

internal fun rectOverlapArea(a: Rect, b: Rect): Float {
    val w = minOf(a.right, b.right) - maxOf(a.left, b.left)
    val h = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)
    return if (w > 0f && h > 0f) w * h else 0f
}

/**
 * Smart non-occluding placement helper for the inline stage text editor.
 *
 * Places the popover `gapPx` outside [targetBounds] — preferring Above or Below (and Right/Left or
 * cleared below siblings if needed) so neither the edited text nor nearby sibling text nodes are
 * covered — while staying strictly clamped inside `[marginPx, stageSize - popoverSize - marginPx]`.
 */
internal fun computeClampedInlineEditorOffset(
    targetBounds: Rect,
    popoverSizePx: Size,
    stageWidthPx: Float,
    stageHeightPx: Float,
    marginPx: Float = INLINE_EDITOR_MARGIN_PX,
    gapPx: Float = INLINE_EDITOR_GAP_PX,
    siblingTextBounds: List<Rect> = emptyList(),
): Offset {
    val popW = popoverSizePx.width
    val popH = popoverSizePx.height
    val maxX = (stageWidthPx - popW - marginPx).coerceAtLeast(marginPx)
    val maxY = (stageHeightPx - popH - marginPx).coerceAtLeast(marginPx)
    val alignedX = targetBounds.left.coerceIn(marginPx, maxX)
    val alignedY = targetBounds.top.coerceIn(marginPx, maxY)

    fun fitsInStage(r: Rect): Boolean =
        r.left >= marginPx - 0.5f &&
            r.right <= stageWidthPx - marginPx + 0.5f &&
            r.top >= marginPx - 0.5f &&
            r.bottom <= stageHeightPx - marginPx + 0.5f

    // Stable placement: below first; above only when below cannot fit inside the stage.
    val belowFits = targetBounds.bottom + gapPx + popH <= stageHeightPx - marginPx + 0.5f
    val preferAbove = !belowFits
    val belowRect = Rect(alignedX, targetBounds.bottom + gapPx, alignedX + popW, targetBounds.bottom + gapPx + popH)
    val aboveRect = Rect(alignedX, targetBounds.top - popH - gapPx, alignedX + popW, targetBounds.top - gapPx)
    val rightRect = Rect(targetBounds.right + gapPx, alignedY, targetBounds.right + gapPx + popW, alignedY + popH)
    val leftRect = Rect(targetBounds.left - popW - gapPx, alignedY, targetBounds.left - gapPx, alignedY + popH)

    val orderedCandidates = buildList<Pair<Int, Rect>> {
        if (preferAbove) {
            add(0 to aboveRect)
            add(1 to belowRect)
        } else {
            add(0 to belowRect)
            add(1 to aboveRect)
        }
        add(2 to rightRect)
        add(3 to leftRect)

        // If a sibling text node sits directly below the target (e.g., headline below eyebrow),
        // also consider placing cleanly below that sibling or docked at the bottom/top edge.
        val overlappingBelowBottom = siblingTextBounds
            .filter { rectOverlapArea(belowRect, it) > 0.5f }
            .maxOfOrNull { it.bottom }
        if (overlappingBelowBottom != null) {
            val clearedY = (overlappingBelowBottom + gapPx).coerceIn(marginPx, maxY)
            add(4 to Rect(alignedX, clearedY, alignedX + popW, clearedY + popH))
        }
        add(5 to Rect(alignedX, maxY, alignedX + popW, maxY + popH))
        add(6 to Rect(alignedX, marginPx, alignedX + popW, marginPx + popH))
    }

    val nonOccluding = orderedCandidates.filter { (_, r) ->
        fitsInStage(r) && rectOverlapArea(r, targetBounds) <= 0.5f
    }

    val best = nonOccluding.minWithOrNull(
        compareBy<Pair<Int, Rect>> { (_, r) ->
            siblingTextBounds.count { rectOverlapArea(r, it) > 0.5f }
        }.thenBy { (_, r) ->
            siblingTextBounds.sumOf { rectOverlapArea(r, it).toDouble() }
        }.thenBy { (priority, _) ->
            priority
        },
    )

    if (best != null) {
        return Offset(
            x = best.second.left.coerceIn(marginPx, maxX),
            y = best.second.top.coerceIn(marginPx, maxY),
        )
    }

    // Fallback only when target node covers virtually the entire viewport.
    return Offset(x = alignedX, y = alignedY)
}

/**
 * Coalesces sequential contiguous cursor edits recorded by [InputTransformation] into minimal
 * exact [TextRangeEdit] operations.
 *
 * For example, typing `'i'`, `'t'`, `'e'`, `'m'`, `' '` at UTF-16 offset `0` before `"item"`
 * coalesces into a single `TextRangeEdit(start = 0, removedLength = 0, insertedText = "item ")`
 * rather than transiently fusing `"i"` + `"item"` -> `"iitem"`.
 */
internal fun coalesceTextRangeEdits(edits: List<TextRangeEdit>): List<TextRangeEdit> {
    if (edits.isEmpty()) return emptyList()
    val out = ArrayList<TextRangeEdit>(edits.size)
    for (next in edits) {
        if (next.removedLength == 0 && next.insertedText.isEmpty()) continue
        val prev = out.lastOrNull()
        if (prev == null) {
            out += next
            continue
        }
        val prevEndAfter = prev.start + prev.insertedText.length
        when {
            // Sequential typing / insertion immediately at the end of the previous edit.
            next.removedLength == 0 && next.start == prevEndAfter -> {
                out[out.lastIndex] = prev.copy(insertedText = prev.insertedText + next.insertedText)
            }
            // Sequential backspace inside previously inserted text (shrinking insertedText).
            next.insertedText.isEmpty() &&
                next.start >= prev.start &&
                next.start + next.removedLength == prevEndAfter -> {
                val trimLen = next.removedLength
                val remaining = prev.insertedText.dropLast(trimLen)
                if (remaining.isEmpty() && prev.removedLength == 0) {
                    out.removeAt(out.lastIndex)
                } else {
                    out[out.lastIndex] = prev.copy(insertedText = remaining)
                }
            }
            // Sequential backspace extending before prev.start into original text.
            next.insertedText.isEmpty() &&
                prev.insertedText.isEmpty() &&
                next.start + next.removedLength == prev.start -> {
                out[out.lastIndex] = TextRangeEdit(
                    start = next.start,
                    removedLength = prev.removedLength + next.removedLength,
                    insertedText = "",
                )
            }
            // Sequential forward delete right at prevEndAfter.
            next.insertedText.isEmpty() && next.start == prevEndAfter -> {
                out[out.lastIndex] = prev.copy(removedLength = prev.removedLength + next.removedLength)
            }
            else -> out += next
        }
    }
    return out
}

/** Replays a sequence of [TextRangeEdit]s against [initial] to verify exact final string parity. */
internal fun replayTextRangeEdits(initial: String, edits: List<TextRangeEdit>): String? {
    val sb = StringBuilder(initial)
    for (edit in edits) {
        if (edit.start < 0 || edit.removedLength < 0 || edit.start + edit.removedLength > sb.length) {
            return null
        }
        sb.replace(edit.start, edit.start + edit.removedLength, edit.insertedText)
    }
    return sb.toString()
}

/** Applies coalesced UTF-16 range edits to [doc] when [currentText] matches [oldText], else falls back cleanly. */
internal fun applyInlineRangeEditsToDocument(
    doc: DeckDocument,
    slideId: String,
    nodeId: String,
    beat: Int,
    oldText: String,
    finalText: String,
    currentText: String?,
    editsToApply: List<TextRangeEdit>,
): Pair<DeckDocument, List<ManualDelayRemapNotice>> {
    if (currentText == null) return doc to emptyList()
    if (currentText != oldText) {
        return if (currentText == finalText) {
            doc to emptyList()
        } else {
            DeckStructureEdits.applyWholeTextReplacement(doc, slideId, nodeId, beat, finalText)
        }
    }
    val collectedNotices = mutableListOf<ManualDelayRemapNotice>()
    var current = doc
    for (edit in editsToApply) {
        val (next, notices) = DeckStructureEdits.applyTextRangeEdit(current, slideId, nodeId, beat, edit)
        current = next
        collectedNotices += notices
    }
    return current to collectedNotices
}
