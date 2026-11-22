package dev.sebastiano.ciacola.document

import kotlin.math.abs

private const val NODE_TEXT_EXCERPT_MAX_CHARS = 28
private const val BEZIER_MATCH_EPSILON = 1e-3

/**
 * Pure, UI-independent display-name formatting helpers for `.deck.json` models so Studio panels,
 * Layers trees, Timeline tracks, Overview cards, and CLI output share identical plain labels
 * instead of raw JSON identifiers.
 */
object DeckDisplayNames {
    fun slideDisplayTitle(slide: SlideSpec, oneBasedIndex: Int): String =
        dev.sebastiano.ciacola.document.slideDisplayTitle(slide, oneBasedIndex)

    fun nodeTypeLabel(node: NodeSpec): String =
        dev.sebastiano.ciacola.document.nodeTypeLabel(node)

    fun nodeDisplayName(node: NodeSpec, specimenLabel: String? = null): String =
        dev.sebastiano.ciacola.document.nodeDisplayName(node, specimenLabel)

    fun beatDisplayName(beatIndex: Int): String =
        dev.sebastiano.ciacola.document.beatDisplayName(beatIndex)

    fun transitionDisplayName(transition: TransitionSpec?, toSlideTitle: String? = null): String =
        dev.sebastiano.ciacola.document.transitionDisplayName(transition, toSlideTitle)

    fun trackDisplayName(beatIndex: Int, node: NodeSpec?, specimenLabel: String? = null): String =
        dev.sebastiano.ciacola.document.trackDisplayName(beatIndex, node, specimenLabel)

    fun curveDisplayName(curve: Curve?): String =
        dev.sebastiano.ciacola.document.curveDisplayName(curve)

    fun tokenDisplayName(tokenKey: String): String =
        dev.sebastiano.ciacola.document.tokenDisplayName(tokenKey)

    /** Collapses whitespace and truncates to [max] characters with an ellipsis (`""` when blank). */
    fun excerpt(raw: String?, max: Int = NODE_TEXT_EXCERPT_MAX_CHARS): String =
        excerptText(raw.orEmpty(), max).orEmpty()
}

/**
 * Returns the slide's authored title when non-blank, otherwise `"Slide <oneBasedIndex>"`.
 */
fun slideDisplayTitle(slide: SlideSpec, oneBasedIndex: Int): String =
    slide.title?.trim()?.takeIf { it.isNotEmpty() } ?: "Slide $oneBasedIndex"

/**
 * Formats a concise human-readable type label for [node] without raw JSON identifiers.
 */
fun nodeTypeLabel(node: NodeSpec): String = when {
    node.text != null -> "Text"
    node.code != null -> "Code"
    node.shape != null -> when (node.shape.geometry) {
        ShapeGeometry.Rect -> "Rectangle"
        ShapeGeometry.Ellipse -> "Ellipse"
        ShapeGeometry.Line -> "Line"
        is ShapeGeometry.Path -> "Path"
    }
    node.image != null -> "Image"
    node.connector != null -> "Connector"
    node.specimen != null -> "Specimen"
    node.stack != null -> "Group"
    node.row != null -> "Row"
    node.column != null -> "Column"
    node.loupe != null -> "Loupe"
    else -> "Element"
}

/**
 * Formats a concise human-readable name for [node] without exposing raw JSON `node.id` strings.
 */
fun nodeDisplayName(node: NodeSpec, specimenLabel: String? = null): String {
    node.text?.let { textPayload ->
        val excerpt = excerptText(textPayload.value.plainText)
        return excerpt ?: "Text"
    }
    node.code?.let { codePayload ->
        val lang = codePayload.language?.trim()?.takeIf { it.isNotEmpty() }
        return if (lang != null) "Code ($lang)" else "Code"
    }
    node.shape?.let { shapePayload ->
        return when (shapePayload.geometry) {
            ShapeGeometry.Rect -> "Rectangle"
            ShapeGeometry.Ellipse -> "Ellipse"
            ShapeGeometry.Line -> "Line"
            is ShapeGeometry.Path -> "Shape Path"
        }
    }
    node.image?.let { imagePayload ->
        val rawName = imagePayload.src.substringAfterLast('/').substringAfterLast('\\')
        val stem = rawName.substringBeforeLast('.', rawName).trim()
        return if (stem.isNotEmpty()) {
            "Image (${humanizeIdentifier(stem).take(18)})"
        } else {
            "Image"
        }
    }
    if (node.connector != null) {
        return "Connector"
    }
    node.specimen?.let { specimenPayload ->
        val explicit = specimenLabel?.trim()?.takeIf { it.isNotEmpty() }
        if (explicit != null) return explicit
        val ref = specimenPayload.ref.trim()
        val tail = ref.substringAfterLast('.').trim()
        return if (tail.isNotEmpty()) humanizeIdentifier(tail) else "Specimen"
    }
    if (node.stack != null || node.row != null || node.column != null || node.loupe != null) {
        val baseLabel = nodeTypeLabel(node)
        val count = node.children.size
        return if (count > 0) {
            "$baseLabel · $count ${if (count == 1) "item" else "items"}"
        } else {
            baseLabel
        }
    }
    return "Element"
}

/**
 * Formats beat 0 as `"Base"` and beat `k` (`k > 0`) as `"Build k"`.
 */
fun beatDisplayName(beatIndex: Int): String =
    if (beatIndex <= 0) "Base" else "Build $beatIndex"

/**
 * Formats a slide transition's readable label, optionally appending `" → <toSlideTitle>"`.
 */
fun transitionDisplayName(transition: TransitionSpec?, toSlideTitle: String? = null): String {
    val typeLabel = when (transition?.type) {
        null -> "Transition"
        TransitionType.None -> "None"
        TransitionType.Dissolve -> "Dissolve"
        TransitionType.MagicMove -> "Magic Move"
        TransitionType.Push -> "Push"
        TransitionType.Slide -> "Slide"
        TransitionType.Reveal -> "Reveal"
        TransitionType.Wipe -> "Wipe"
        TransitionType.Iris -> "Iris"
        TransitionType.FadeThroughColor -> "Fade through color"
    }
    val dest = toSlideTitle?.trim()?.takeIf { it.isNotEmpty() }
    return if (dest != null) "$typeLabel → $dest" else typeLabel
}

/**
 * Formats a timeline track label combining the beat label and the target node's display name
 * (e.g. `"Build 1 · A scene begins…"`).
 */
fun trackDisplayName(beatIndex: Int, node: NodeSpec?, specimenLabel: String? = null): String {
    val beatLabel = beatDisplayName(beatIndex)
    return if (node != null) {
        "$beatLabel · ${nodeDisplayName(node, specimenLabel)}"
    } else {
        beatLabel
    }
}

/**
 * Maps standard cubic beziers, preset curve names, and springs to concise UI labels
 * (`"Standard"`, `"Ease in-out"`, `"Linear"`, `"Spring"`, `"Custom"`, etc.).
 */
fun curveDisplayName(curve: Curve?): String = when (curve) {
    null -> "Standard"
    is Curve.Spring -> "Spring"
    is Curve.Preset -> presetCurveDisplayName(curve.name)
    is Curve.Bezier -> bezierCurveDisplayName(curve)
}

/**
 * Converts camelCase / snake_case / kebab-case design token keys into readable title-cased labels
 * (e.g. `"elevatedSurface"` -> `"Elevated Surface"`, `"onAccent"` -> `"On Accent"`).
 */
fun tokenDisplayName(tokenKey: String): String = humanizeIdentifier(tokenKey)

private fun excerptText(raw: String, max: Int = NODE_TEXT_EXCERPT_MAX_CHARS): String? {
    if (max <= 0) return ""
    val normalized = raw.replace(WhitespaceRunRegex, " ").trim()
    if (normalized.isEmpty()) return null
    if (normalized.length <= max) return normalized
    if (max == 1) return normalized.take(1)
    val truncated = normalized.take(max - 1).trimEnd()
    return "$truncated…"
}

private fun presetCurveDisplayName(rawName: String): String = when (rawName.trim()) {
    "standard", "fastOutSlowIn", "keynote.magicMove" -> "Standard"
    "linear", "keynote.none" -> "Linear"
    "easeInOut", "keynote.easeInOut", "easeInOutCubic" -> "Ease in-out"
    "easeIn", "keynote.easeIn", "easeInCubic" -> "Ease in"
    "easeOut", "keynote.easeOut", "easeOutCubic" -> "Ease out"
    "ease" -> "Ease"
    "keynote.pop" -> "Pop"
    else -> {
        val stripped = rawName.trim().removePrefix("keynote.")
        humanizeIdentifier(stripped).ifEmpty { "Custom" }
    }
}

private fun bezierCurveDisplayName(curve: Curve.Bezier): String = when {
    curve.matches(0.2, 0.0, 0.0, 1.0) -> "Standard"
    curve.matches(0.4, 0.0, 0.2, 1.0) -> "Standard"
    curve.matches(0.42, 0.0, 0.58, 1.0) -> "Ease in-out"
    curve.matches(0.0, 0.0, 1.0, 1.0) -> "Linear"
    curve.matches(0.42, 0.0, 1.0, 1.0) -> "Ease in"
    curve.matches(0.0, 0.0, 0.58, 1.0) -> "Ease out"
    curve.matches(0.25, 0.1, 0.25, 1.0) -> "Ease"
    else -> "Custom"
}

private fun Curve.Bezier.matches(tx1: Double, ty1: Double, tx2: Double, ty2: Double): Boolean =
    abs(x1 - tx1) <= BEZIER_MATCH_EPSILON &&
        abs(y1 - ty1) <= BEZIER_MATCH_EPSILON &&
        abs(x2 - tx2) <= BEZIER_MATCH_EPSILON &&
        abs(y2 - ty2) <= BEZIER_MATCH_EPSILON

private fun humanizeIdentifier(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return ""
    val withSpaces = trimmed
        .replace('_', ' ')
        .replace('-', ' ')
        .replace('.', ' ')
        .replace(CamelBoundaryRegex, "$1 $2")
        .replace(AcronymBoundaryRegex, "$1 $2")
        .replace(WhitespaceRunRegex, " ")
        .trim()
    if (withSpaces.isEmpty()) return ""
    return withSpaces.split(' ')
        .filter { it.isNotEmpty() }
        .joinToString(" ") { word ->
            word.replaceFirstChar { ch -> ch.uppercaseChar() }
        }
}

private val WhitespaceRunRegex = Regex("""\s+""")
private val CamelBoundaryRegex = Regex("""([a-z0-9])([A-Z])""")
private val AcronymBoundaryRegex = Regex("""([A-Z]+)([A-Z][a-z])""")
