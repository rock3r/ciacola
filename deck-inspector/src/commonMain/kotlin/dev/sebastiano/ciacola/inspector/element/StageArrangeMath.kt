package dev.sebastiano.ciacola.inspector.element

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size

/** Alignment targets for the Design inspector's Align toolbar and `alignSelectedNodes`. */
enum class NodeAlignment(val label: String) {
    Left("Align left edges"),
    CenterH("Align horizontal centers"),
    Right("Align right edges"),
    Top("Align top edges"),
    CenterV("Align vertical centers"),
    Bottom("Align bottom edges"),
}

/** Axis for evenly distributing three or more selected nodes. */
enum class DistributeAxis(val label: String) {
    Horizontal("Distribute horizontally"),
    Vertical("Distribute vertically"),
}

/**
 * Pure geometry for multi-node nudge, align and distribute operations in stage units.
 *
 * Every function returns per-node `(dx, dy)` deltas rather than mutating anything, so the
 * controller can apply them in one atomic document edit (one undo step).
 */
object StageArrangeMath {

    /** Plain arrow nudge distance in stage units. */
    const val NUDGE_SMALL: Double = 1.0

    /** `Alt+Arrow` (Option+Arrow) or `Shift+Arrow` nudge distance in stage units. */
    const val NUDGE_BIG: Double = 10.0

    /** `Alt+Shift+Arrow` nudge distance in stage units. */
    const val NUDGE_HUGE: Double = 25.0

    /** Resolves the nudge distance for the held modifiers. */
    fun nudgeStep(isAltPressed: Boolean, isShiftPressed: Boolean): Double = when {
        isAltPressed && isShiftPressed -> NUDGE_HUGE
        isAltPressed || isShiftPressed -> NUDGE_BIG
        else -> NUDGE_SMALL
    }

    /** Union of [rects], or `null` when empty. */
    fun unionBounds(rects: Collection<Rect>): Rect? {
        if (rects.isEmpty()) return null
        return Rect(
            left = rects.minOf { it.left },
            top = rects.minOf { it.top },
            right = rects.maxOf { it.right },
            bottom = rects.maxOf { it.bottom },
        )
    }

    /**
     * Computes deltas aligning every rect in [bounds] to [alignment].
     *
     * With two or more rects the reference is their union bounding box; with exactly one rect
     * the reference is the full stage (`0, 0, stage.width, stage.height`).
     */
    fun alignmentDeltas(
        bounds: Map<String, Rect>,
        alignment: NodeAlignment,
        stage: Size,
    ): Map<String, Offset> {
        if (bounds.isEmpty()) return emptyMap()
        val reference = if (bounds.size >= 2) {
            unionBounds(bounds.values) ?: return emptyMap()
        } else {
            Rect(0f, 0f, stage.width, stage.height)
        }
        return bounds.mapValues { (_, r) ->
            when (alignment) {
                NodeAlignment.Left -> Offset(reference.left - r.left, 0f)
                NodeAlignment.CenterH -> Offset(reference.center.x - r.center.x, 0f)
                NodeAlignment.Right -> Offset(reference.right - r.right, 0f)
                NodeAlignment.Top -> Offset(0f, reference.top - r.top)
                NodeAlignment.CenterV -> Offset(0f, reference.center.y - r.center.y)
                NodeAlignment.Bottom -> Offset(0f, reference.bottom - r.bottom)
            }
        }
    }

    /**
     * Evenly distributes three or more rects along [axis] with equal gaps, pinning the outermost
     * rects (the first and last along the axis ordered by center) in place.
     *
     * Returns an empty map when fewer than three rects are supplied.
     */
    fun distributionDeltas(
        bounds: Map<String, Rect>,
        axis: DistributeAxis,
    ): Map<String, Offset> {
        if (bounds.size < 3) return emptyMap()
        val horizontal = axis == DistributeAxis.Horizontal
        val ordered = bounds.entries.sortedWith(
            compareBy<Map.Entry<String, Rect>>(
                { if (horizontal) it.value.center.x else it.value.center.y },
                { it.key },
            ),
        )
        val first = ordered.first().value
        val last = ordered.last().value
        val start = if (horizontal) first.left else first.top
        val end = if (horizontal) last.right else last.bottom
        val totalExtent = ordered.sumOf { (if (horizontal) it.value.width else it.value.height).toDouble() }
        val gap = ((end - start) - totalExtent) / (ordered.size - 1)

        val result = LinkedHashMap<String, Offset>(ordered.size)
        var cursor = start.toDouble()
        for ((index, entry) in ordered.withIndex()) {
            val r = entry.value
            val extent = if (horizontal) r.width else r.height
            val current = if (horizontal) r.left else r.top
            val target = when (index) {
                0 -> current.toDouble()
                ordered.lastIndex -> current.toDouble()
                else -> cursor
            }
            val delta = (target - current).toFloat()
            result[entry.key] = if (horizontal) Offset(delta, 0f) else Offset(0f, delta)
            cursor = target + extent + gap
        }
        return result
    }
}
