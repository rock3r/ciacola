package dev.sebastiano.ciacola.inspector.element

import dev.sebastiano.ciacola.document.DeckDocument
import dev.sebastiano.ciacola.document.JsonMergePatch
import dev.sebastiano.ciacola.document.RichTextValue
import dev.sebastiano.ciacola.document.RichTextValueEdits
import dev.sebastiano.ciacola.document.TextRangeEdit
import dev.sebastiano.ciacola.document.TextRun
import dev.sebastiano.ciacola.inspector.document.DocumentThemeResolver

/**
 * Returns [current] carrying [newText]: a plain value becomes plain [newText]; a `Runs` value keeps
 * its runs and styling, with the change applied as one minimal range edit (common prefix/suffix).
 */
internal fun richValueWithText(current: RichTextValue?, newText: String): RichTextValue =
    RichTextValueEdits.replaceWholeText(current, newText)

/**
 * Applies [edit] to styled [runs]: removed characters come out of whichever runs they span, and the
 * inserted text joins the run the edit starts in (continuing the preceding run at a boundary, like
 * typing). Empty runs are dropped, keeping at least one run.
 */
internal fun applyRangeEditToRuns(runs: List<TextRun>, edit: TextRangeEdit): List<TextRun> =
    RichTextValueEdits.applyRangeEditToRuns(runs, edit)

/** Plain text of [nodeId] resolved at [beat], or `null` when the node has no text. */
internal fun resolvedNodeText(doc: DeckDocument, slideId: String, nodeId: String, beat: Int): String? {
    val slide = doc.slides.firstOrNull { it.id == slideId } ?: return null
    val node = DocumentThemeResolver.findNode(slide.nodes, nodeId) ?: return null
    return JsonMergePatch.resolveNodeAtBeat(node, beat).text?.value?.plainText
}
