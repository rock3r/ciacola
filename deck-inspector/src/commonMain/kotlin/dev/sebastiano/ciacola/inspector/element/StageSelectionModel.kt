package dev.sebastiano.ciacola.inspector.element

import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import dev.sebastiano.ciacola.inspector.StageInteractiveEntry
import dev.sebastiano.ciacola.inspector.StageNodeSnapshot

/**
 * Current hierarchical selection context on the slide stage.
 *
 * [contextParentId] identifies the container currently entered (or `null` at top-level stage
 * scope). Single-click selects direct children of [contextParentId], double-click enters a
 * container or begins inline editing on a text leaf, and `Esc` pops [contextParentId] up one level.
 *
 * [selectedElementIds] carries the full multi-selection (`Shift+Click`, marquee, `Cmd+A`) while
 * [selectedElementId] stays the primary selection shown in the inspector. The primary is always a
 * member of the set (enforced in `init`); change the selection through [withSingleSelection] or by
 * passing BOTH fields, never `copy(selectedElementId = …)` alone.
 */
@Immutable
data class StageSelectionContext(
    val contextParentId: String? = null,
    val selectedElementId: String? = null,
    val selectedElementIds: Set<String> = selectedElementId?.let(::setOf) ?: emptySet(),
) {
    init {
        require(selectedElementId == null || selectedElementId in selectedElementIds) {
            "Primary selection '$selectedElementId' must be part of selectedElementIds $selectedElementIds"
        }
    }

    /** Replaces the whole selection with [elementId] (or clears it), keeping both fields in sync. */
    fun withSingleSelection(elementId: String?): StageSelectionContext =
        copy(selectedElementId = elementId, selectedElementIds = setOfNotNull(elementId))
}

/**
 * Outcome of double-clicking a stage coordinate under [StageSelectionModel].
 */
sealed interface StageDoubleClickOutcome {
    data class EnteredContainer(val next: StageSelectionContext) : StageDoubleClickOutcome
    data class StartInlineTextEdit(val elementId: String, val next: StageSelectionContext) : StageDoubleClickOutcome
    data class Selected(val next: StageSelectionContext) : StageDoubleClickOutcome
    data object NoOp : StageDoubleClickOutcome
}

/**
 * Flattened tree row for hierarchical rendering in the Element Inspector picker.
 */
@Immutable
data class StagePickerEntry(
    val node: StageNodeSnapshot,
    val depth: Int,
)

/**
 * Pure Figma-style container-first selection resolver backed by explicit [StageNodeSnapshot.parentElementId]
 * relationships rather than brittle area heuristics.
 */
object StageSelectionModel {

    fun effectiveParentId(
        elementId: String,
        byId: Map<String, StageNodeSnapshot>,
    ): String? {
        val raw = byId[elementId]?.parentElementId ?: return null
        if (raw == elementId || raw !in byId) return null
        // Guard against accidental cycles.
        val seen = HashSet<String>(4).apply { add(elementId) }
        var cursor: String? = raw
        while (cursor != null) {
            if (!seen.add(cursor)) return null
            val next = byId[cursor]?.parentElementId
            cursor = next?.takeIf { it != cursor && it in byId }
        }
        return raw
    }

    /**
     * Returns `[root, ..., parent, elementId]` from outermost ancestor down to [elementId].
     */
    fun ancestorChain(
        elementId: String,
        byId: Map<String, StageNodeSnapshot>,
    ): List<String> {
        if (elementId !in byId) return emptyList()
        val chain = ArrayList<String>(4)
        val seen = HashSet<String>(4)
        var cursor: String? = elementId
        while (cursor != null && seen.add(cursor)) {
            chain.add(cursor)
            cursor = effectiveParentId(cursor, byId)
        }
        chain.reverse()
        return chain
    }

    fun hasChildren(
        elementId: String,
        nodes: List<StageNodeSnapshot>,
        byId: Map<String, StageNodeSnapshot> = nodes.associateBy { it.elementId },
    ): Boolean = nodes.any { effectiveParentId(it.elementId, byId) == elementId }

    /**
     * Returns all strict descendant element IDs of [elementId].
     */
    fun descendantIdsOf(
        elementId: String,
        nodes: List<StageNodeSnapshot>,
        byId: Map<String, StageNodeSnapshot> = nodes.associateBy { it.elementId },
    ): Set<String> {
        val result = LinkedHashSet<String>()
        for (node in nodes) {
            if (node.elementId == elementId) continue
            val chain = ancestorChain(node.elementId, byId)
            if (elementId in chain) {
                result.add(node.elementId)
            }
        }
        return result
    }

    /**
     * Normalizes [current] against the active [nodes] list so stale parent contexts or deleted
     * selections never linger across slide/beat transitions.
     */
    fun sanitizeContext(
        current: StageSelectionContext,
        nodes: List<StageNodeSnapshot>,
    ): StageSelectionContext {
        val byId = nodes.associateBy { it.elementId }
        val validContext = current.contextParentId?.takeIf { it in byId }
        val validSelected = current.selectedElementId?.takeIf { it in byId }
        val validSet = current.selectedElementIds.filterTo(LinkedHashSet()) { it in byId }
        if (validSelected != null) validSet.add(validSelected)
        return StageSelectionContext(
            contextParentId = validContext,
            selectedElementId = validSelected ?: validSet.lastOrNull(),
            selectedElementIds = validSet,
        )
    }

    /**
     * `Shift+Click` toggle: adds [elementId] to the multi-selection (making it primary) or removes
     * it (promoting the most recently added remaining element to primary).
     */
    fun toggleAdditive(
        current: StageSelectionContext,
        elementId: String,
    ): StageSelectionContext {
        val next = LinkedHashSet(current.selectedElementIds)
        current.selectedElementId?.let(next::add)
        val primary = if (next.remove(elementId)) {
            current.selectedElementId?.takeIf { it != elementId && it in next } ?: next.lastOrNull()
        } else {
            next.add(elementId)
            elementId
        }
        return current.copy(selectedElementId = primary, selectedElementIds = next)
    }

    /** Top-level editable nodes (no editable parent), used by `Cmd+A` and marquee selection. */
    fun topLevelEditable(nodes: List<StageNodeSnapshot>): List<StageNodeSnapshot> {
        val byId = nodes.associateBy { it.elementId }
        return nodes.filter { it.isEditable && effectiveParentId(it.elementId, byId) == null }
    }

    /**
     * Rubber-band marquee: returns IDs of top-level editable nodes whose bounds intersect
     * [marquee] (in stage coordinates) with a positive area, ordered top-to-bottom, left-to-right.
     */
    fun resolveMarquee(
        marquee: Rect,
        nodes: List<StageNodeSnapshot>,
    ): List<String> {
        val normalized = Rect(
            left = minOf(marquee.left, marquee.right),
            top = minOf(marquee.top, marquee.bottom),
            right = maxOf(marquee.left, marquee.right),
            bottom = maxOf(marquee.top, marquee.bottom),
        )
        if (normalized.width <= 0f || normalized.height <= 0f) return emptyList()
        return topLevelEditable(nodes)
            .filter { node ->
                val b = node.stageBounds
                minOf(b.right, normalized.right) > maxOf(b.left, normalized.left) &&
                    minOf(b.bottom, normalized.bottom) > maxOf(b.top, normalized.top)
            }
            .sortedWith(compareBy({ it.stageBounds.top }, { it.stageBounds.left }, { it.elementId }))
            .map { it.elementId }
    }

    /**
     * Resolves a context aligned to an explicitly chosen element (e.g., from the Element Picker
     * sidebar or `Alt+Click` on a live specimen container).
     */
    fun selectExplicit(
        elementId: String?,
        nodes: List<StageNodeSnapshot>,
    ): StageSelectionContext {
        if (elementId == null) return StageSelectionContext()
        val byId = nodes.associateBy { it.elementId }
        if (elementId !in byId) return StageSelectionContext()
        return StageSelectionContext(
            contextParentId = effectiveParentId(elementId, byId),
            selectedElementId = elementId,
        )
    }

    /**
     * Resolves a single-click at [point] in stage coordinates.
     *
     * Prefers direct children of `current.contextParentId`; if no hit lies inside the current
     * context, pops up ancestor levels until a hit matches (falling back to top-level `null`).
     */
    fun resolveSingleClick(
        point: Offset,
        nodes: List<StageNodeSnapshot>,
        current: StageSelectionContext,
    ): StageSelectionContext {
        val byId = nodes.associateBy { it.elementId }
        val hits = nodes.filter { it.isEditable && it.stageBounds.contains(point) }
        if (hits.isEmpty()) {
            return StageSelectionContext(contextParentId = null, selectedElementId = null)
        }

        val contextsToTry = buildContextSearchChain(current.contextParentId, byId)
        for (ctxParent in contextsToTry) {
            val candidate = pickCandidateAtContext(hits = hits, contextParentId = ctxParent, byId = byId)
            if (candidate != null) {
                return StageSelectionContext(
                    contextParentId = ctxParent,
                    selectedElementId = candidate.elementId,
                )
            }
        }

        val fallback = pickSmallestHit(hits)
        return StageSelectionContext(
            contextParentId = effectiveParentId(fallback.elementId, byId),
            selectedElementId = fallback.elementId,
        )
    }

    /**
     * Resolves hover highlight target using the same container-first scoping as [resolveSingleClick].
     */
    fun resolveHover(
        point: Offset,
        nodes: List<StageNodeSnapshot>,
        current: StageSelectionContext,
    ): StageNodeSnapshot? {
        val byId = nodes.associateBy { it.elementId }
        val hits = nodes.filter { it.isEditable && it.stageBounds.contains(point) }
        if (hits.isEmpty()) return null
        val resolved = resolveSingleClick(point = point, nodes = nodes, current = current)
        return resolved.selectedElementId?.let { byId[it] }
    }

    /**
     * Resolves a double-click at [point] in stage coordinates:
     * - If the targeted element is a container with children, enters that container and selects
     *   its direct child under [point] (if any).
     * - Otherwise, if the targeted element supports inline text editing, returns
     *   [StageDoubleClickOutcome.StartInlineTextEdit].
     */
    fun resolveDoubleClick(
        point: Offset,
        nodes: List<StageNodeSnapshot>,
        current: StageSelectionContext,
        supportsInlineTextEdit: (StageNodeSnapshot) -> Boolean,
    ): StageDoubleClickOutcome {
        val byId = nodes.associateBy { it.elementId }
        val hits = nodes.filter { it.isEditable && it.stageBounds.contains(point) }
        if (hits.isEmpty()) return StageDoubleClickOutcome.NoOp

        val single = resolveSingleClick(point = point, nodes = nodes, current = current)
        val targetId = single.selectedElementId ?: return StageDoubleClickOutcome.NoOp
        val targetNode = byId[targetId] ?: return StageDoubleClickOutcome.NoOp

        if (hasChildren(targetId, nodes, byId)) {
            val childUnderPoint = pickCandidateAtContext(
                hits = hits,
                contextParentId = targetId,
                byId = byId,
            )
            val fallbackChild = childUnderPoint ?: nodes
                .filter { it.isEditable && effectiveParentId(it.elementId, byId) == targetId }
                .minByOrNull { it.stageBounds.top * 10_000f + it.stageBounds.left }
            return if (fallbackChild != null) {
                StageDoubleClickOutcome.EnteredContainer(
                    StageSelectionContext(
                        contextParentId = targetId,
                        selectedElementId = fallbackChild.elementId,
                    ),
                )
            } else {
                StageDoubleClickOutcome.Selected(single)
            }
        }

        return if (supportsInlineTextEdit(targetNode)) {
            StageDoubleClickOutcome.StartInlineTextEdit(
                elementId = targetId,
                next = single,
            )
        } else {
            StageDoubleClickOutcome.Selected(single)
        }
    }

    /**
     * Handles `Esc` key navigation in the selection hierarchy.
     *
     * Pops up one container level if inside a container, clears selection if at root with an
     * element selected, or returns `null` if nothing is selected (so `Esc` is not consumed).
     */
    fun resolveEscape(
        current: StageSelectionContext,
        nodes: List<StageNodeSnapshot>,
    ): StageSelectionContext? {
        val byId = nodes.associateBy { it.elementId }
        val ctx = current.contextParentId
        if (ctx != null && ctx in byId) {
            return StageSelectionContext(
                contextParentId = effectiveParentId(ctx, byId),
                selectedElementId = ctx,
            )
        }
        if (current.selectedElementId != null) {
            return StageSelectionContext(contextParentId = null, selectedElementId = null)
        }
        return null
    }

    /**
     * Resolves the container element to select when the user `Alt+Click`s a live interactive
     * specimen registered in [StageInteractiveEntry].
     */
    fun resolveContainerForInteractive(
        entry: StageInteractiveEntry,
        nodes: List<StageNodeSnapshot>,
    ): String? {
        val byId = nodes.associateBy { it.elementId }
        entry.parentElementId?.takeIf { it in byId }?.let { return it }
        if (entry.id in byId) return entry.id
        val center = entry.stageBounds.center
        return nodes
            .filter { it.isEditable && it.stageBounds.contains(center) }
            .minByOrNull { it.stageBounds.width * it.stageBounds.height }
            ?.elementId
    }

    /**
     * Builds a deterministic hierarchical list for the Element Inspector picker, indenting
     * children directly underneath their parent container.
     */
    fun buildPickerHierarchy(nodes: List<StageNodeSnapshot>): List<StagePickerEntry> {
        if (nodes.isEmpty()) return emptyList()
        val byId = nodes.associateBy { it.elementId }
        val childrenByParent = HashMap<String?, MutableList<StageNodeSnapshot>>()
        for (node in nodes) {
            val parent = effectiveParentId(node.elementId, byId)
            childrenByParent.getOrPut(parent) { ArrayList() }.add(node)
        }
        val comparator = compareBy<StageNodeSnapshot>(
            { it.stageBounds.top },
            { it.stageBounds.left },
            { it.elementId },
        )
        for (bucket in childrenByParent.values) {
            bucket.sortWith(comparator)
        }

        val ordered = ArrayList<StagePickerEntry>(nodes.size)
        val visited = HashSet<String>(nodes.size)
        fun visit(node: StageNodeSnapshot, depth: Int) {
            if (!visited.add(node.elementId)) return
            ordered.add(StagePickerEntry(node = node, depth = depth))
            val children = childrenByParent[node.elementId].orEmpty()
            for (child in children) {
                visit(child, depth + 1)
            }
        }

        for (root in childrenByParent[null].orEmpty()) {
            visit(root, 0)
        }
        // Append any unvisited nodes defensively.
        for (node in nodes.sortedWith(comparator)) {
            if (node.elementId !in visited) {
                visit(node, 0)
            }
        }
        return ordered
    }

    private fun buildContextSearchChain(
        contextParentId: String?,
        byId: Map<String, StageNodeSnapshot>,
    ): List<String?> {
        val result = ArrayList<String?>(4)
        val seen = HashSet<String>(4)
        var cursor = contextParentId?.takeIf { it in byId }
        while (cursor != null && seen.add(cursor)) {
            result.add(cursor)
            cursor = effectiveParentId(cursor, byId)
        }
        result.add(null)
        return result
    }

    private fun pickCandidateAtContext(
        hits: List<StageNodeSnapshot>,
        contextParentId: String?,
        byId: Map<String, StageNodeSnapshot>,
    ): StageNodeSnapshot? {
        val lifted = LinkedHashMap<String, StageNodeSnapshot>()
        for (hit in hits) {
            val directChildId = liftToDirectChildOf(
                elementId = hit.elementId,
                contextParentId = contextParentId,
                byId = byId,
            ) ?: continue
            val directNode = byId[directChildId] ?: continue
            if (directNode.isEditable) {
                lifted[directChildId] = directNode
            }
        }
        if (lifted.isEmpty()) return null
        return pickSmallestHit(lifted.values.toList())
    }

    private fun liftToDirectChildOf(
        elementId: String,
        contextParentId: String?,
        byId: Map<String, StageNodeSnapshot>,
    ): String? {
        val chain = ancestorChain(elementId, byId)
        if (chain.isEmpty()) return null
        if (contextParentId == null) {
            return chain.first()
        }
        val idx = chain.indexOf(contextParentId)
        if (idx < 0 || idx + 1 >= chain.size) return null
        return chain[idx + 1]
    }

    private fun pickSmallestHit(candidates: List<StageNodeSnapshot>): StageNodeSnapshot =
        candidates.minWith(
            compareBy<StageNodeSnapshot>(
                { it.stageBounds.width * it.stageBounds.height },
                { if (it.resolvedText.isNotEmpty()) 0 else 1 },
                { it.elementId },
            ),
        )
}
