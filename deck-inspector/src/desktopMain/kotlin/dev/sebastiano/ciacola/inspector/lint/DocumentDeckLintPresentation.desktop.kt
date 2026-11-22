package dev.sebastiano.ciacola.inspector.lint

import androidx.compose.runtime.Immutable
import dev.sebastiano.ciacola.document.DeckDocument
import dev.sebastiano.ciacola.document.JsonMergePatch
import dev.sebastiano.ciacola.document.NodeKind
import dev.sebastiano.ciacola.document.SlideSpec
import dev.sebastiano.ciacola.inspector.document.DocumentThemeResolver
import dev.sebastiano.ciacola.inspector.document.snapHalfUnit
import dev.sebastiano.ciacola.inspector.element.DocumentElementArrangeOps
import dev.sebastiano.ciacola.inspector.element.InspectorDisplayNames
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** What the Issues panel has checked so far (drives the empty state copy). */
enum class LintAuditScope { None, Slide, Deck }

/** Issues of one slide, shown under a collapsible `Slide N · "Title" · K issues` header. */
@Immutable
data class LintSlideGroup(
    val slideIndex: Int,
    val slideId: String,
    val slideTitle: String,
    val issues: List<DocumentLintIssue>,
) {
    val headerText: String
        get() = buildString {
            append("Slide ${slideIndex + 1}")
            if (slideTitle.isNotBlank()) append(" · \u201C$slideTitle\u201D")
            append(" · ${issues.size} ${if (issues.size == 1) "issue" else "issues"}")
        }
}

/** Readable slide title: explicit title, else the first text excerpt, else empty. */
fun lintSlideTitle(slide: SlideSpec?): String {
    if (slide == null) return ""
    slide.title?.takeIf { it.isNotBlank() }?.let { return InspectorDisplayNames.excerpt(it, 36) }
    val firstText = DocumentThemeResolver.flattenNodes(slide.nodes).firstOrNull { it.text != null }
    return InspectorDisplayNames.excerpt(firstText?.text?.value?.plainText, 36)
}

/** Groups [issues] by slide, ordered by slide index, keeping each slide's issues errors-first. */
fun groupLintIssuesBySlide(issues: List<DocumentLintIssue>, document: DeckDocument?): List<LintSlideGroup> =
    issues
        .groupBy { it.slideIndex to it.slideId }
        .entries
        .sortedBy { it.key.first }
        .map { (key, list) ->
            val slide = document?.slides?.firstOrNull { it.id == key.second }
            LintSlideGroup(
                slideIndex = key.first,
                slideId = key.second,
                slideTitle = lintSlideTitle(slide),
                issues = list.sortedWith(compareBy({ it.severity.ordinal }, { it.beatIndex }, { it.id })),
            )
        }

/** `52 errors · 270 warnings` (never `52E · 270W`). */
fun humanLintSummary(errorCount: Int, warningCount: Int): String =
    "$errorCount ${if (errorCount == 1) "error" else "errors"} · $warningCount ${if (warningCount == 1) "warning" else "warnings"}"

private fun quoted(name: String) = "\u201C$name\u201D"

/** Plain display name for a lint issue's node (text excerpt / readable type), else the humanized ID. */
fun lintNodeName(document: DeckDocument?, slideId: String, nodeId: String?): String {
    if (nodeId == null) return "This element"
    val slide = document?.slides?.firstOrNull { it.id == slideId }
    val node = slide?.let { DocumentThemeResolver.findNode(it.nodes, nodeId) }
    return node?.let { InspectorDisplayNames.nodeDisplayName(it) }
        ?: InspectorDisplayNames.humanizeIdentifier(nodeId.substringAfterLast(':'))
}

private val RATIO_IN_TITLE = Regex("""\(([0-9]+(?:\.[0-9]+)?):1\)""")
private val REQUIRED_RATIO = Regex("""WCAG ([0-9]+(?:\.[0-9]+)?):1""")
private val QUOTED_IDS = Regex("""'([^']+)'""")
private val OVERLAP_PERCENT = Regex("""overlap by ([0-9]+)%""")
private val FONT_SP = Regex("""\(([0-9]+)sp\) is below ([0-9]+)sp""")

private fun trimRatio(raw: String): String = raw.toDoubleOrNull()?.let {
    val one = Math.round(it * 10) / 10.0
    if (one == Math.floor(one)) one.toInt().toString() else one.toString()
} ?: raw

/**
 * One plain-English sentence per issue (no duplicated title + detail, no raw codes), e.g.
 * `“compute” is hard to read: contrast 2.6:1, needs 3:1.`
 */
fun plainLintSentence(issue: DocumentLintIssue, document: DeckDocument? = null): String {
    val name = quoted(lintNodeName(document, issue.slideId, issue.nodeId))
    return when (issue.code) {
        "LOW_CONTRAST" -> {
            val ratio = RATIO_IN_TITLE.find(issue.title)?.groupValues?.get(1)?.let(::trimRatio)
            val needed = REQUIRED_RATIO.find(issue.detail)?.groupValues?.get(1)?.let(::trimRatio)
            if (ratio != null && needed != null) "$name is hard to read: contrast $ratio:1, needs $needed:1."
            else "$name is hard to read against its background."
        }
        "CONTRAST_UNKNOWN" -> "Couldn\u2019t measure the contrast of $name."
        "STAGE_OUT_OF_BOUNDS" -> "$name extends past the edge of the slide."
        "SAFE_AREA_BLEED" -> "$name sits inside the safe margin near the slide edge."
        "TEXT_OVERLAP" -> {
            val ids = QUOTED_IDS.findAll(issue.detail).map { it.groupValues[1] }.toList()
            val pct = OVERLAP_PERCENT.find(issue.detail)?.groupValues?.get(1)
            if (ids.size >= 2) {
                val a = quoted(lintNodeName(document, issue.slideId, ids[0]))
                val b = quoted(lintNodeName(document, issue.slideId, ids[1]))
                "$a and $b overlap" + (pct?.let { " by $it%." } ?: ".")
            } else {
                "Two text elements overlap."
            }
        }
        "TINY_STAGE_TEXT" -> {
            val m = FONT_SP.find(issue.detail)
            if (m != null) "$name is too small to read from the room (${m.groupValues[1]}sp, needs ${m.groupValues[2]}sp)."
            else "$name is too small to read from the room."
        }
        "CODE_NATURAL_SIZE_EXCEEDS_BOX" -> "The code in $name doesn\u2019t fit inside its box."
        "TODO_VIDEO" -> "$name is still a video placeholder; replace it with a live specimen."
        else -> issue.detail.substringAfter(": ", issue.detail).trim()
            .replaceFirstChar { it.uppercaseChar() }
            .let { if (it.endsWith('.')) it else "$it." }
    }
}

/** Short tooltip describing what `Fix` will do, or `null` when the issue has no automatic fix. */
fun lintFixDescription(issue: DocumentLintIssue): String? = when {
    issue.nodeId == null -> null
    issue.code == "STAGE_OUT_OF_BOUNDS" -> "Move it back inside the slide"
    issue.code == "SAFE_AREA_BLEED" -> "Move it inside the safe margin"
    issue.code == "LOW_CONTRAST" -> "Use the theme\u2019s foreground text color"
    else -> null
}

/**
 * Computes the beat-aware merge patch that fixes [issue], or `null` when there is no automatic fix
 * (or the node is not found). Pure; applied by [DocumentDeckLintController.fixIssue].
 */
internal fun lintFixPatch(issue: DocumentLintIssue, document: DeckDocument): JsonObject? {
    val nodeId = issue.nodeId ?: return null
    val slide = document.slides.firstOrNull { it.id == issue.slideId } ?: return null
    val base = DocumentThemeResolver.findNode(slide.nodes, nodeId) ?: return null
    val node = JsonMergePatch.resolveNodeAtBeat(base, issue.beatIndex)
    return when (issue.code) {
        "STAGE_OUT_OF_BOUNDS", "SAFE_AREA_BLEED" -> {
            val margin = if (issue.code == "SAFE_AREA_BLEED") SAFE_AREA_MARGIN_PX.toDouble() + 1.0 else 0.0
            val f = node.frame ?: return null
            val w = f.w ?: issue.stageBounds.width.toDouble()
            val h = f.h ?: issue.stageBounds.height.toDouble()
            val parentOrigin = DocumentElementArrangeOps.parentContentStageOrigin(slide, issue.beatIndex, nodeId)
            val hasMeasuredStageBounds = issue.stageBounds.width > 0f || issue.stageBounds.height > 0f
            val stageX = when {
                hasMeasuredStageBounds -> issue.stageBounds.left.toDouble()
                f.x != null -> parentOrigin.x + f.x!!
                else -> issue.stageBounds.left.toDouble()
            }
            val stageY = when {
                hasMeasuredStageBounds -> issue.stageBounds.top.toDouble()
                f.y != null -> parentOrigin.y + f.y!!
                else -> issue.stageBounds.top.toDouble()
            }
            val maxX = (document.stage.width - margin - w).coerceAtLeast(margin)
            val maxY = (document.stage.height - margin - h).coerceAtLeast(margin)
            val targetStageX = stageX.coerceIn(margin, maxX)
            val targetStageY = stageY.coerceIn(margin, maxY)
            val localX = (f.x ?: (stageX - parentOrigin.x)) + (targetStageX - stageX)
            val localY = (f.y ?: (stageY - parentOrigin.y)) + (targetStageY - stageY)
            buildJsonObject {
                put("frame", buildJsonObject {
                    put("x", snapHalfUnit(localX))
                    put("y", snapHalfUnit(localY))
                })
            }
        }
        "LOW_CONTRAST" -> if (node.kind == NodeKind.Text) {
            buildJsonObject { put("text", buildJsonObject { put("color", JsonPrimitive("foreground")) }) }
        } else {
            null
        }
        else -> null
    }
}
