package dev.sebastiano.ciacola.document

import dev.sebastiano.ciacola.core.fonts.DeckFontResolver
import dev.sebastiano.ciacola.core.fonts.FontAxes

/**
 * Font-aware lint for static `axes` declared on theme typography roles, text nodes, and inline
 * text runs. Malformed tags are reported by [DeckDocumentCodec.validate]; this pass checks each
 * well-formed tag against the resolved face's `fvar` axes.
 *
 * Every finding is a warning because rendering stays safe: unsupported tags are ignored and
 * out-of-range values are clamped to the axis range.
 */
object DeckFontAxisLint {

    const val CODE_AXES_IGNORED: String = "FONT_AXES_IGNORED"
    const val CODE_UNSUPPORTED_AXIS: String = "UNSUPPORTED_FONT_AXIS"
    const val CODE_AXIS_OUT_OF_RANGE: String = "FONT_AXIS_OUT_OF_RANGE"

    /** `true` iff any theme role, text node, or text run in [document] declares `axes`. */
    fun declaresAxes(document: DeckDocument): Boolean {
        if (document.themes.values.any { theme -> theme.typography.values.any { it.axes.isNotEmpty() } }) return true
        var found = false
        for (slide in document.slides) {
            forEachNode(slide.nodes) { node ->
                val text = node.text
                val runs = (text?.value as? RichTextValue.Runs)?.runs.orEmpty()
                if (text != null && (text.axes.isNotEmpty() || runs.any { it.axes.isNotEmpty() })) {
                    found = true
                }
            }
            if (found) return true
        }
        return false
    }

    /**
     * Returns axis warnings for [document]. [resolver] is the document's font resolver, or `null`
     * when the document declares no fonts (in which case any declared axes are ignored).
     */
    fun validate(document: DeckDocument, resolver: DeckFontResolver?): List<DocumentIssue> {
        val issues = LinkedHashMap<String, DocumentIssue>()
        fun add(issue: DocumentIssue) {
            issues.putIfAbsent("${issue.code}|${issue.address}|${issue.message}", issue)
        }

        for ((themeName, theme) in document.themes) {
            for ((role, style) in theme.typography) {
                if (style.axes.isEmpty()) continue
                checkAxes(
                    resolver = resolver,
                    familyName = style.font,
                    axes = style.axes,
                    italic = style.italic == true,
                    address = "<root>/themes/$themeName/$role",
                    subject = "Theme typography role '$role'",
                    sink = ::add,
                )
            }
        }

        val themes: List<DeckThemeSpec?> = document.themes.values.toList().ifEmpty { listOf(null) }
        for (slide in document.slides) {
            forEachNode(slide.nodes) { node ->
                val text = node.text ?: return@forEachNode
                val addr = "${slide.id}/${node.id}"
                for (theme in themes) {
                    val role = text.style?.let { theme?.typography?.get(it) }
                    val nodeFamily = text.font ?: role?.font
                    val nodeItalic = text.italic ?: role?.italic ?: false
                    // Check the coordinates the renderer actually applies: inherited axes count
                    // whenever a level switches to a different font than the one they were set for.
                    val nodeAxes = FontAxes.merge(role?.axes.orEmpty(), text.axes)
                    val nodeSwitchesFont = text.font != null && text.font != role?.font
                    if (text.axes.isNotEmpty() || (nodeSwitchesFont && nodeAxes.isNotEmpty())) {
                        checkAxes(resolver, nodeFamily, nodeAxes, nodeItalic, addr, "Text node '${node.id}'", ::add)
                    }
                    val runs = (text.value as? RichTextValue.Runs)?.runs.orEmpty()
                    for (run in runs) {
                        val runAxes = FontAxes.merge(nodeAxes, run.axes)
                        val runSwitchesFont = run.font != null && run.font != nodeFamily
                        if (run.axes.isEmpty() && !(runSwitchesFont && runAxes.isNotEmpty())) continue
                        checkAxes(
                            resolver = resolver,
                            familyName = run.font ?: nodeFamily,
                            axes = runAxes,
                            italic = run.italic ?: nodeItalic,
                            address = addr,
                            subject = "Text run '${run.text.take(RUN_PREVIEW_CHARS)}' in '${node.id}'",
                            sink = ::add,
                        )
                    }
                }
            }
        }
        return issues.values.toList()
    }

    private fun checkAxes(
        resolver: DeckFontResolver?,
        familyName: String?,
        axes: Map<String, Double>,
        italic: Boolean,
        address: String,
        subject: String,
        sink: (DocumentIssue) -> Unit,
    ) {
        val validAxes = axes.filterKeys { FontAxes.isValidTag(it) }
        if (validAxes.isEmpty()) return
        if (resolver == null || familyName.isNullOrBlank()) {
            sink(
                warning(
                    CODE_AXES_IGNORED,
                    address,
                    "$subject declares axes ${validAxes.keys.sorted()} but does not use a declared deck font; axes are ignored.",
                ),
            )
            return
        }
        // Unknown families are reported as UNKNOWN_FONT by static validation.
        val resolution = resolver.resolveAxes(familyName, validAxes, italic) ?: return
        val family = resolver.resolveFamily(familyName)
        val isVariable = family?.faces?.any { it.inspection?.variationAxes?.isNotEmpty() == true } == true
        if (!isVariable) {
            sink(
                warning(
                    CODE_AXES_IGNORED,
                    address,
                    "$subject declares axes ${validAxes.keys.sorted()} but font '$familyName' has no variation axes; axes are ignored.",
                ),
            )
            return
        }
        for (tag in resolution.unsupported) {
            sink(
                warning(
                    CODE_UNSUPPORTED_AXIS,
                    address,
                    "$subject sets axis '$tag', which font '$familyName' does not support; it is ignored.",
                ),
            )
        }
        for (clamped in resolution.clamped) {
            sink(
                warning(
                    CODE_AXIS_OUT_OF_RANGE,
                    address,
                    "$subject sets axis '${clamped.tag}' to ${formatNumber(clamped.requested)}, outside font " +
                        "'$familyName' range [${formatNumber(clamped.min)}, ${formatNumber(clamped.max)}]; " +
                        "clamped to ${formatNumber(clamped.applied)}.",
                ),
            )
        }
    }

    private fun forEachNode(nodes: List<NodeSpec>, visit: (NodeSpec) -> Unit) {
        for (node in nodes) {
            visit(node)
            val children = node.stack?.children ?: node.row?.children ?: node.column?.children ?: node.loupe?.children
            if (children != null) forEachNode(children, visit)
        }
    }

    private fun formatNumber(value: Double): String {
        val asLong = value.toLong()
        return if (value == asLong.toDouble()) asLong.toString() else value.toString()
    }

    private fun warning(code: String, address: String, message: String) =
        DocumentIssue(IssueSeverity.LintWarning, code, address, message)

    private const val RUN_PREVIEW_CHARS = 24
}
