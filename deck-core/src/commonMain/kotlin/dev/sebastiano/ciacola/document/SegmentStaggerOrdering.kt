package dev.sebastiano.ciacola.document

/**
 * Pure flow-aware geometric stagger ranking helpers for [SegmentPlanner].
 */
internal object SegmentStaggerOrdering {

    internal const val TIE_GROUP_THRESHOLD_UNITS: Double = 8.0

    internal data class FlatNodeEntry(
        val node: NodeSpec,
        val ancestors: List<NodeSpec>,
        val childIndexPath: List<Int>,
        val documentOrder: Int,
    )

    internal fun flattenSlideNodes(roots: List<NodeSpec>): List<FlatNodeEntry> {
        val out = mutableListOf<FlatNodeEntry>()
        var order = 0
        fun visit(nodes: List<NodeSpec>, ancestors: List<NodeSpec>, path: List<Int>) {
            for ((idx, node) in nodes.withIndex()) {
                val childPath = path + idx
                out += FlatNodeEntry(node, ancestors, childPath, order++)
                if (node.children.isNotEmpty()) {
                    visit(node.children, ancestors + node, childPath)
                }
            }
        }
        visit(roots, emptyList(), emptyList())
        return out
    }

    /**
     * Groups and ranks nodes by document geometry with deterministic 8-unit tie groups:
     * - Sort by primary geometric key.
     * - Start a group at the first item; open a new group when an item's key exceeds the
     *   **group's first** key by more than 8.0 units (`{0, 7, 14}` -> `{0, 7}` rank 0, `{14}` rank 1).
     */
    internal fun computeGeometryRanks(
        items: List<Pair<String, DocumentSortKey>>,
        stagger: Stagger?,
    ): Map<String, Int> {
        if (items.isEmpty()) return emptyMap()
        val order = stagger?.order ?: StaggerOrder.Document
        val reverse = stagger?.reverse == true

        if (order == StaggerOrder.Document) {
            val ordered = items.sortedBy { it.second.documentOrder }.let { if (reverse) it.reversed() else it }
            return ordered.withIndex().associate { (idx, pair) -> pair.first to idx }
        }

        val sorted = when (order) {
            StaggerOrder.TopToBottom -> items.sortedWith(
                compareBy<Pair<String, DocumentSortKey>> { it.second.y }
                    .thenComparator { a, b -> compareChildPaths(a.second.childPath, b.second.childPath) }
                    .thenBy { it.second.documentOrder },
            )
            StaggerOrder.LeftToRight -> items.sortedWith(
                compareBy<Pair<String, DocumentSortKey>> { it.second.x }
                    .thenComparator { a, b -> compareChildPaths(a.second.childPath, b.second.childPath) }
                    .thenBy { it.second.documentOrder },
            )
            StaggerOrder.ReadingOrder -> items.sortedWith(
                compareBy<Pair<String, DocumentSortKey>> { it.second.y }
                    .thenBy { it.second.x }
                    .thenComparator { a, b -> compareChildPaths(a.second.childPath, b.second.childPath) }
                    .thenBy { it.second.documentOrder },
            )
            StaggerOrder.Document -> items
        }

        val groups = mutableListOf<MutableList<String>>()
        when (order) {
            StaggerOrder.TopToBottom -> {
                var groupFirstY = Double.NaN
                for ((id, key) in sorted) {
                    if (groups.isEmpty() || key.y > groupFirstY + TIE_GROUP_THRESHOLD_UNITS) {
                        groups += mutableListOf(id)
                        groupFirstY = key.y
                    } else {
                        groups.last() += id
                    }
                }
            }
            StaggerOrder.LeftToRight -> {
                var groupFirstX = Double.NaN
                for ((id, key) in sorted) {
                    if (groups.isEmpty() || key.x > groupFirstX + TIE_GROUP_THRESHOLD_UNITS) {
                        groups += mutableListOf(id)
                        groupFirstX = key.x
                    } else {
                        groups.last() += id
                    }
                }
            }
            StaggerOrder.ReadingOrder -> {
                // Group rows by y within 8 units, then sort each row by x and group x ties within 8 units.
                val rows = mutableListOf<MutableList<Pair<String, DocumentSortKey>>>()
                var rowFirstY = Double.NaN
                for (item in sorted) {
                    if (rows.isEmpty() || item.second.y > rowFirstY + TIE_GROUP_THRESHOLD_UNITS) {
                        rows += mutableListOf(item)
                        rowFirstY = item.second.y
                    } else {
                        rows.last() += item
                    }
                }
                for (row in rows) {
                    val byX = row.sortedWith(
                        compareBy<Pair<String, DocumentSortKey>> { it.second.x }
                            .thenComparator { a, b -> compareChildPaths(a.second.childPath, b.second.childPath) }
                            .thenBy { it.second.documentOrder },
                    )
                    var groupFirstX = Double.NaN
                    for ((id, key) in byX) {
                        if (groups.isEmpty() || groupFirstX.isNaN() || key.x > groupFirstX + TIE_GROUP_THRESHOLD_UNITS) {
                            groups += mutableListOf(id)
                            groupFirstX = key.x
                        } else {
                            groups.last() += id
                        }
                    }
                }
            }
            StaggerOrder.Document -> Unit
        }

        val finalGroups = if (reverse) groups.reversed() else groups
        val ranks = LinkedHashMap<String, Int>(items.size)
        for ((rank, group) in finalGroups.withIndex()) {
            for (id in group) {
                ranks[id] = rank
            }
        }
        return ranks
    }

    internal class BeatResolveCache {
        private val resolved = HashMap<Pair<String, Int>, NodeSpec>()
        private val present = HashMap<Pair<String, Int>, Boolean>()

        fun resolveAtBeat(node: NodeSpec, beat: Int): NodeSpec =
            resolved.getOrPut(node.id to beat) { JsonMergePatch.resolveNodeAtBeat(node, beat) }

        fun isPresentAtBeat(node: NodeSpec, beat: Int): Boolean =
            present.getOrPut(node.id to beat) { JsonMergePatch.isNodePresentAtBeat(node, beat) }
    }

    internal fun computeDocumentSortKey(
        entry: FlatNodeEntry,
        beat: Int,
        resolveCache: BeatResolveCache = BeatResolveCache(),
    ): DocumentSortKey {
        // Walk top-down from root through every ancestor to this node, accumulating container
        // positions and preceding present flow-sibling steps at every level.
        val chain = entry.ancestors + entry.node
        val root = resolveCache.resolveAtBeat(chain[0], beat)
        var curX = root.frame?.x ?: 0.0
        var curY = root.frame?.y ?: 0.0
        for (level in 0 until chain.lastIndex) {
            val parent = resolveCache.resolveAtBeat(chain[level], beat)
            val childIdx = entry.childIndexPath.getOrNull(level + 1) ?: continue
            val child = resolveCache.resolveAtBeat(chain[level + 1], beat)
            when (parent.kind) {
                NodeKind.Row -> {
                    val rowGap = parent.row?.gap ?: 0.0
                    for (k in 0 until childIdx) {
                        val sibRaw = parent.children.getOrNull(k) ?: continue
                        if (resolveCache.isPresentAtBeat(sibRaw, beat)) {
                            val sib = resolveCache.resolveAtBeat(sibRaw, beat)
                            val step = ((sib.frame?.w ?: 100.0) + rowGap).coerceAtLeast(TIE_GROUP_THRESHOLD_UNITS + 1.0)
                            curX += step
                        }
                    }
                    if (child.frame?.x != null) curX += child.frame.x
                    if (child.frame?.y != null) curY += child.frame.y
                }
                NodeKind.Column -> {
                    val colGap = parent.column?.gap ?: 0.0
                    for (k in 0 until childIdx) {
                        val sibRaw = parent.children.getOrNull(k) ?: continue
                        if (resolveCache.isPresentAtBeat(sibRaw, beat)) {
                            val sib = resolveCache.resolveAtBeat(sibRaw, beat)
                            val step = ((sib.frame?.h ?: 60.0) + colGap).coerceAtLeast(TIE_GROUP_THRESHOLD_UNITS + 1.0)
                            curY += step
                        }
                    }
                    if (child.frame?.x != null) curX += child.frame.x
                    if (child.frame?.y != null) curY += child.frame.y
                }
                else -> {
                    if (child.frame?.x != null) curX += child.frame.x
                    if (child.frame?.y != null) curY += child.frame.y
                }
            }
        }
        return DocumentSortKey(curX, curY, entry.childIndexPath, entry.documentOrder)
    }

    private fun compareChildPaths(a: List<Int>, b: List<Int>): Int {
        val min = minOf(a.size, b.size)
        for (i in 0 until min) {
            if (a[i] != b[i]) return a[i].compareTo(b[i])
        }
        return a.size.compareTo(b.size)
    }
}
