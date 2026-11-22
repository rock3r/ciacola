package dev.sebastiano.ciacola.document

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * Pure helpers for applying plain-text or range edits to [RichTextValue] without stripping
 * per-run styling on [RichTextValue.Runs] nodes.
 */
object RichTextValueEdits {

    /**
     * Applies an inline range [edit] to [current]: a plain value becomes plain text; a `Runs`
     * value preserves its runs and styling across the edited span.
     */
    fun applyRangeEdit(current: RichTextValue?, edit: TextRangeEdit): RichTextValue = when (current) {
        is RichTextValue.Runs -> RichTextValue.Runs(applyRangeEditToRuns(current.runs, edit))
        else -> RichTextValue.Plain(edit.applyTo(current?.plainText.orEmpty()))
    }

    /**
     * Replaces the text of [current] with [newText]: a plain value becomes plain [newText]; a
     * `Runs` value keeps its runs and styling by applying one minimal common-prefix/suffix edit.
     */
    fun replaceWholeText(current: RichTextValue?, newText: String): RichTextValue = when (current) {
        is RichTextValue.Runs -> {
            val edit = computeMinimalRangeEdit(current.plainText, newText)
            if (edit == null) current else RichTextValue.Runs(applyRangeEditToRuns(current.runs, edit))
        }
        else -> RichTextValue.Plain(newText)
    }

    /**
     * Computes a minimal single [TextRangeEdit] (common prefix + common suffix) turning [oldText]
     * into [newText], or `null` when they are identical.
     */
    fun computeMinimalRangeEdit(oldText: String, newText: String): TextRangeEdit? {
        if (oldText == newText) return null
        val minLen = minOf(oldText.length, newText.length)
        var start = 0
        while (start < minLen && oldText[start] == newText[start]) start++
        var oldEnd = oldText.length
        var newEnd = newText.length
        while (oldEnd > start && newEnd > start && oldText[oldEnd - 1] == newText[newEnd - 1]) {
            oldEnd--
            newEnd--
        }
        return TextRangeEdit(start = start, removedLength = oldEnd - start, insertedText = newText.substring(start, newEnd))
    }

    /**
     * Applies [edit] to styled [runs]: removed characters come out of whichever runs they span, and
     * the inserted text joins the run the edit starts in (continuing the preceding run at a boundary,
     * like typing). Empty runs are dropped, keeping at least one run.
     */
    fun applyRangeEditToRuns(runs: List<TextRun>, edit: TextRangeEdit): List<TextRun> {
        if (runs.isEmpty()) return listOf(TextRun(text = edit.insertedText))
        val out = ArrayList<TextRun>(runs.size)
        var offset = 0
        var inserted = false
        for ((index, run) in runs.withIndex()) {
            val runStart = offset
            val runEnd = offset + run.text.length
            offset = runEnd
            val keepHead = run.text.substring(0, (edit.start - runStart).coerceIn(0, run.text.length))
            val keepTail = run.text.substring((edit.oldEnd - runStart).coerceIn(0, run.text.length))
            val ownsInsertion = !inserted && (
                edit.start in runStart until runEnd ||
                    (edit.start == runEnd && (index == runs.lastIndex || (edit.removedLength == 0 && runStart < runEnd))) ||
                    (edit.start == 0 && index == 0)
                )
            val text = if (ownsInsertion) {
                inserted = true
                keepHead + edit.insertedText + keepTail
            } else {
                keepHead + keepTail
            }
            out += run.copy(text = text)
        }
        val nonEmpty = out.filter { it.text.isNotEmpty() }
        return nonEmpty.ifEmpty { listOf(out.first().copy(text = "")) }
    }

    /** Serializes [value] to its canonical JSON representation (`JsonPrimitive` or runs `JsonArray`). */
    fun encodeJson(value: RichTextValue): JsonElement =
        Json.encodeToJsonElement(RichTextValueSerializer, value)
}
