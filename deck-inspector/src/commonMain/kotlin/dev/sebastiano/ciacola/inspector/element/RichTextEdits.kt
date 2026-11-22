package dev.sebastiano.ciacola.inspector.element

import dev.sebastiano.ciacola.document.Build
import dev.sebastiano.ciacola.document.DeckDocument
import dev.sebastiano.ciacola.document.JsonMergePatch
import dev.sebastiano.ciacola.document.NodeSpec
import dev.sebastiano.ciacola.document.RichTextValue
import dev.sebastiano.ciacola.document.RichTextValueSerializer
import dev.sebastiano.ciacola.document.TextRangeEdit
import dev.sebastiano.ciacola.document.TextRun
import dev.sebastiano.ciacola.inspector.document.DocumentThemeResolver
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Returns [current] carrying [newText]: a plain value becomes plain [newText]; a `Runs` value keeps
 * its runs and styling, with the change applied as one minimal range edit (common prefix/suffix).
 */
internal fun richValueWithText(current: RichTextValue?, newText: String): RichTextValue = when (current) {
    is RichTextValue.Runs -> {
        val edit = DocumentThemeResolver.computeTextRangeEdit(current.plainText, newText)
        if (edit == null) current else RichTextValue.Runs(applyRangeEditToRuns(current.runs, edit))
    }
    else -> RichTextValue.Plain(newText)
}

/**
 * Applies [edit] to styled [runs]: removed characters come out of whichever runs they span, and the
 * inserted text joins the run the edit starts in (continuing the preceding run at a boundary, like
 * typing). Empty runs are dropped, keeping at least one run.
 */
internal fun applyRangeEditToRuns(runs: List<TextRun>, edit: TextRangeEdit): List<TextRun> {
    if (runs.isEmpty()) return listOf(TextRun(text = edit.insertedText))
    val out = ArrayList<TextRun>(runs.size)
    var offset = 0
    var inserted = false
    for ((index, run) in runs.withIndex()) {
        val runStart = offset
        val runEnd = offset + run.text.length
        offset = runEnd
        // Keep the parts of this run outside the removed range [start, oldEnd).
        val keepHead = run.text.substring(0, (edit.start - runStart).coerceIn(0, run.text.length))
        val keepTail = run.text.substring((edit.oldEnd - runStart).coerceIn(0, run.text.length))
        val ownsInsertion = !inserted && (
            edit.start in runStart until runEnd ||
                (edit.start == runEnd && (index == runs.lastIndex || runStart < runEnd)) ||
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

/** Plain text of [nodeId] resolved at [beat], or `null` when the node has no text. */
internal fun resolvedNodeText(doc: DeckDocument, slideId: String, nodeId: String, beat: Int): String? {
    val slide = doc.slides.firstOrNull { it.id == slideId } ?: return null
    val node = DocumentThemeResolver.findNode(slide.nodes, nodeId) ?: return null
    return JsonMergePatch.resolveNodeAtBeat(node, beat).text?.value?.plainText
}

/**
 * `All builds`: [text] becomes [nodeId]'s text on every build. A rich (`Runs`) base value keeps its
 * runs and styling ([richValueWithText], taken from [styleSource] when given); builds that carried their OWN text override lose their
 * manual fragment delays ([pruneOverriddenTextManualDelays]).
 */
internal fun withTextOnAllBuilds(
    doc: DeckDocument,
    slideId: String,
    nodeId: String,
    text: String,
    styleSource: RichTextValue? = null,
): DeckDocument {
    val slide = doc.slides.firstOrNull { it.id == slideId } ?: return doc
    val node = DocumentThemeResolver.findNode(slide.nodes, nodeId) ?: return doc
    // [styleSource] is the base value BEFORE any plain-text rewrite, so its runs survive.
    val value = richValueWithText(styleSource ?: node.text?.value, text)
    val patch = JsonMergePatch.nestedPatch(listOf("text", "value"), Json.encodeToJsonElement(RichTextValueSerializer, value))
    return DocumentThemeResolver.updateNodeInSlide(doc, slideId, nodeId) { n ->
        JsonMergePatch.applyToAllBeats(pruneOverriddenTextManualDelays(n), patch)
    }
}

/**
 * Clears manual fragment delays on `change` builds that set their own `text.value`: those keys
 * addressed fragments of text that an `All builds` edit replaces, so none may survive stale.
 */
internal fun pruneOverriddenTextManualDelays(node: NodeSpec): NodeSpec = node.copy(
    builds = node.builds.map { build ->
        if (build !is Build.Change) return@map build
        val ownsText = (build.set["text"] as? JsonObject)?.containsKey("value") == true
        val morph = build.morph
        val stagger = morph?.stagger
        if (!ownsText || morph == null || stagger == null || stagger.manual.isEmpty()) {
            build
        } else {
            build.copy(morph = morph.copy(stagger = stagger.copy(manual = emptyMap())))
        }
    },
)
