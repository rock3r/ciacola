package dev.sebastiano.ciacola.inspector.element

import dev.sebastiano.ciacola.document.Build
import dev.sebastiano.ciacola.document.DeckDisplayNames
import dev.sebastiano.ciacola.document.NodeSpec

/**
 * Plain display names for nodes shown in the Design inspector (Layers tree, headers, issues).
 *
 * Raw document IDs never appear in primary labels; they live in tooltips and the copyable
 * `slideId/nodeId` row instead.
 */
object InspectorDisplayNames {

    private const val TEXT_EXCERPT_MAX = 28

    /** Short readable type label, e.g. `Text`, `Rectangle`, `Group`, `Specimen` (see [DeckDisplayNames.nodeTypeLabel]). */
    fun nodeTypeLabel(node: NodeSpec): String = DeckDisplayNames.nodeTypeLabel(node)

    /**
     * Readable primary label, delegated to [DeckDisplayNames.nodeDisplayName] so the Layers tree,
     * inspector header, timeline and CLI never diverge (text excerpt, `Code (kotlin)`,
     * `Image (Sparkles)`, specimen label, `Group · 3 items`, …).
     */
    fun nodeDisplayName(node: NodeSpec, specimenLabel: String? = null): String =
        DeckDisplayNames.nodeDisplayName(node, specimenLabel)

    /** `slideId/nodeId` reference copied for agents. */
    fun rawReference(slideId: String, nodeId: String): String = "$slideId/$nodeId"

    /**
     * First beat on which [node] is present at rest (its earliest `enter` build), or `0` when it
     * has no `enter` build.
     */
    fun firstPresentBeat(node: NodeSpec): Int =
        node.builds.filterIsInstance<Build.Enter>().minOfOrNull { it.beat } ?: 0

    /** Collapses whitespace and truncates to a short excerpt (shared with [DeckDisplayNames.excerpt]). */
    fun excerpt(raw: String?, max: Int = TEXT_EXCERPT_MAX): String = DeckDisplayNames.excerpt(raw, max)

    /** `elevatedSurface` / `on-accent` / `chip_background` → `Elevated Surface` / `On Accent` / `Chip Background`. */
    fun humanizeIdentifier(raw: String): String = DeckDisplayNames.tokenDisplayName(raw)
}
