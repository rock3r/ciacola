package dev.sebastiano.ciacola.inspector.element

import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import dev.sebastiano.ciacola.inspector.StageNodeSnapshot
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

internal const val STAGE_DRAG_SLOP_SCREEN_PX = 3f
internal const val STAGE_SNAP_THRESHOLD_SCREEN_PX = 6f
internal const val STAGE_SAFE_MARGIN_PX = 28f

internal val SnapGuideStageColor = Color(0xFFFF2D95)
internal val SnapGuideSiblingColor = Color(0xFF00D8FF)

@Immutable
enum class SnapGuideOrientation {
    Vertical,
    Horizontal,
}

@Immutable
enum class SnapGuideKind {
    StageCenter,
    SafeMargin,
    Sibling,
}

@Immutable
data class SnapGuideLine(
    val orientation: SnapGuideOrientation,
    val positionStagePx: Float,
    val spanStartStagePx: Float,
    val spanEndStagePx: Float,
    val kind: SnapGuideKind,
)

@Immutable
data class DragSnapResult(
    val deltaStagePx: Offset,
    val snappedBounds: Rect,
    val guides: List<SnapGuideLine>,
)

/**
 * Computes the active visual scale factor mapping 1920x1080 stage pixels into window/screen pixels.
 */
internal fun computeViewportScale(
    stageRoot: LayoutCoordinates?,
    stageWidthPx: Float = 1920f,
): Float {
    if (stageRoot == null || !stageRoot.isAttached || stageWidthPx <= 0f) return 1f
    val rootBounds = runCatching { stageRoot.boundsInRoot() }.getOrNull() ?: return 1f
    if (rootBounds.width <= 0f) return 1f
    return (rootBounds.width / stageWidthPx).coerceIn(0.05f, 4f)
}

/**
 * Converts a screen-pixel threshold (e.g., 3px drag slop or 6px snap radius) into stage pixels.
 */
internal fun screenPxToStagePx(screenPx: Float, viewportScale: Float): Float =
    screenPx / viewportScale.coerceIn(0.05f, 4f)

/**
 * Computes snapped stage-pixel drag delta and active smart-guide lines for a dragged element.
 *
 * - Excludes [draggedElementId] and all of its descendants from sibling snap targets.
 * - Snaps within [snapThresholdScreenPx] converted via [viewportScale] to stage center, safe-area
 *   margins, and sibling edges/centers.
 * - [disableSnap] (`Alt` held) bypasses snapping completely.
 * - [axisLock] (`Shift` held) locks movement to the dominant axis.
 */
internal fun computeDragSnap(
    draggedElementId: String,
    startBounds: Rect,
    rawDeltaStagePx: Offset,
    allNodes: List<StageNodeSnapshot>,
    stageWidthPx: Float = 1920f,
    stageHeightPx: Float = 1080f,
    viewportScale: Float = 1f,
    safeMarginPx: Float = STAGE_SAFE_MARGIN_PX,
    disableSnap: Boolean = false,
    axisLock: Boolean = false,
    snapThresholdScreenPx: Float = STAGE_SNAP_THRESHOLD_SCREEN_PX,
): DragSnapResult {
    val lockHorizontal = axisLock && abs(rawDeltaStagePx.x) >= abs(rawDeltaStagePx.y)
    val lockVertical = axisLock && abs(rawDeltaStagePx.y) > abs(rawDeltaStagePx.x)
    val baseDelta = when {
        lockHorizontal -> Offset(rawDeltaStagePx.x, 0f)
        lockVertical -> Offset(0f, rawDeltaStagePx.y)
        else -> rawDeltaStagePx
    }

    val candidateBounds = startBounds.translate(baseDelta.x, baseDelta.y)
    if (disableSnap) {
        return DragSnapResult(
            deltaStagePx = baseDelta,
            snappedBounds = candidateBounds,
            guides = emptyList(),
        )
    }

    val thresholdStagePx = screenPxToStagePx(snapThresholdScreenPx, viewportScale)
    val excludedIds = StageSelectionModel.descendantIdsOf(draggedElementId, allNodes) + draggedElementId
    val siblings = allNodes.filter { it.elementId !in excludedIds && it.effectiveAlpha > 0.05f }

    var snapCorrectionX = 0f
    var snapCorrectionY = 0f
    val guides = ArrayList<SnapGuideLine>(4)

    if (!lockVertical) {
        val xTargets = buildVerticalTargets(
            candidate = candidateBounds,
            siblings = siblings,
            stageWidthPx = stageWidthPx,
            stageHeightPx = stageHeightPx,
            safeMarginPx = safeMarginPx,
        )
        val bestX = findBestAxisSnap(
            startEdge = candidateBounds.left,
            center = candidateBounds.center.x,
            endEdge = candidateBounds.right,
            targets = xTargets,
            thresholdStagePx = thresholdStagePx,
        )
        if (bestX != null) {
            snapCorrectionX = bestX.correction
        }
    }

    if (!lockHorizontal) {
        val yTargets = buildHorizontalTargets(
            candidate = candidateBounds,
            siblings = siblings,
            stageWidthPx = stageWidthPx,
            stageHeightPx = stageHeightPx,
            safeMarginPx = safeMarginPx,
        )
        val bestY = findBestAxisSnap(
            startEdge = candidateBounds.top,
            center = candidateBounds.center.y,
            endEdge = candidateBounds.bottom,
            targets = yTargets,
            thresholdStagePx = thresholdStagePx,
        )
        if (bestY != null) {
            snapCorrectionY = bestY.correction
        }
    }

    val finalDelta = Offset(
        x = baseDelta.x + snapCorrectionX,
        y = baseDelta.y + snapCorrectionY,
    )
    val snappedBounds = startBounds.translate(finalDelta.x, finalDelta.y)

    if (!lockVertical && snapCorrectionX != 0f) {
        collectAlignedGuides(
            orientation = SnapGuideOrientation.Vertical,
            startEdge = snappedBounds.left,
            center = snappedBounds.center.x,
            endEdge = snappedBounds.right,
            targets = buildVerticalTargets(
                candidate = snappedBounds,
                siblings = siblings,
                stageWidthPx = stageWidthPx,
                stageHeightPx = stageHeightPx,
                safeMarginPx = safeMarginPx,
            ),
            out = guides,
        )
    } else if (!lockVertical) {
        // Still emit guides if the user landed directly on a snap line with 0 correction.
        collectAlignedGuides(
            orientation = SnapGuideOrientation.Vertical,
            startEdge = snappedBounds.left,
            center = snappedBounds.center.x,
            endEdge = snappedBounds.right,
            targets = buildVerticalTargets(
                candidate = snappedBounds,
                siblings = siblings,
                stageWidthPx = stageWidthPx,
                stageHeightPx = stageHeightPx,
                safeMarginPx = safeMarginPx,
            ),
            out = guides,
        )
    }

    if (!lockHorizontal) {
        collectAlignedGuides(
            orientation = SnapGuideOrientation.Horizontal,
            startEdge = snappedBounds.top,
            center = snappedBounds.center.y,
            endEdge = snappedBounds.bottom,
            targets = buildHorizontalTargets(
                candidate = snappedBounds,
                siblings = siblings,
                stageWidthPx = stageWidthPx,
                stageHeightPx = stageHeightPx,
                safeMarginPx = safeMarginPx,
            ),
            out = guides,
        )
    }

    return DragSnapResult(
        deltaStagePx = finalDelta,
        snappedBounds = snappedBounds,
        guides = guides,
    )
}

/**
 * Draws crisp 1.5 screen-pixel smart snap guide lines on the 1920x1080 stage overlay canvas.
 */
internal fun DrawScope.drawSnapGuides(
    guides: List<SnapGuideLine>,
    viewportScale: Float,
) {
    if (guides.isEmpty()) return
    val strokePx = screenPxToStagePx(1.5f, viewportScale)
    val dashLen = screenPxToStagePx(5f, viewportScale)
    val dashGap = screenPxToStagePx(3f, viewportScale)
    val tickHalf = screenPxToStagePx(4f, viewportScale)
    val safeDash = PathEffect.dashPathEffect(floatArrayOf(dashLen, dashGap))

    for (guide in guides) {
        val color = when (guide.kind) {
            SnapGuideKind.StageCenter, SnapGuideKind.SafeMargin -> SnapGuideStageColor
            SnapGuideKind.Sibling -> SnapGuideSiblingColor
        }
        val pathEffect = if (guide.kind == SnapGuideKind.SafeMargin) safeDash else null
        when (guide.orientation) {
            SnapGuideOrientation.Vertical -> {
                val x = guide.positionStagePx
                drawLine(
                    color = color,
                    start = Offset(x, guide.spanStartStagePx),
                    end = Offset(x, guide.spanEndStagePx),
                    strokeWidth = strokePx,
                    pathEffect = pathEffect,
                )
                drawLine(
                    color = color,
                    start = Offset(x - tickHalf, guide.spanStartStagePx),
                    end = Offset(x + tickHalf, guide.spanStartStagePx),
                    strokeWidth = strokePx,
                )
                drawLine(
                    color = color,
                    start = Offset(x - tickHalf, guide.spanEndStagePx),
                    end = Offset(x + tickHalf, guide.spanEndStagePx),
                    strokeWidth = strokePx,
                )
            }

            SnapGuideOrientation.Horizontal -> {
                val y = guide.positionStagePx
                drawLine(
                    color = color,
                    start = Offset(guide.spanStartStagePx, y),
                    end = Offset(guide.spanEndStagePx, y),
                    strokeWidth = strokePx,
                    pathEffect = pathEffect,
                )
                drawLine(
                    color = color,
                    start = Offset(guide.spanStartStagePx, y - tickHalf),
                    end = Offset(guide.spanStartStagePx, y + tickHalf),
                    strokeWidth = strokePx,
                )
                drawLine(
                    color = color,
                    start = Offset(guide.spanEndStagePx, y - tickHalf),
                    end = Offset(guide.spanEndStagePx, y + tickHalf),
                    strokeWidth = strokePx,
                )
            }
        }
    }
}

private enum class AnchorMask(val allowsStart: Boolean, val allowsCenter: Boolean, val allowsEnd: Boolean) {
    All(true, true, true),
    StartOnly(true, false, false),
    EndOnly(false, false, true),
}

private data class AxisSnapTarget(
    val position: Float,
    val spanStart: Float,
    val spanEnd: Float,
    val kind: SnapGuideKind,
    val anchorMask: AnchorMask,
)

private data class AxisSnapMatch(
    val correction: Float,
    val distance: Float,
    val priority: Int,
)

private fun buildVerticalTargets(
    candidate: Rect,
    siblings: List<StageNodeSnapshot>,
    stageWidthPx: Float,
    stageHeightPx: Float,
    safeMarginPx: Float,
): List<AxisSnapTarget> {
    val targets = ArrayList<AxisSnapTarget>(3 + siblings.size * 3)
    targets.add(
        AxisSnapTarget(
            position = stageWidthPx / 2f,
            spanStart = 0f,
            spanEnd = stageHeightPx,
            kind = SnapGuideKind.StageCenter,
            anchorMask = AnchorMask.All,
        ),
    )
    targets.add(
        AxisSnapTarget(
            position = safeMarginPx,
            spanStart = 0f,
            spanEnd = stageHeightPx,
            kind = SnapGuideKind.SafeMargin,
            anchorMask = AnchorMask.StartOnly,
        ),
    )
    targets.add(
        AxisSnapTarget(
            position = stageWidthPx - safeMarginPx,
            spanStart = 0f,
            spanEnd = stageHeightPx,
            kind = SnapGuideKind.SafeMargin,
            anchorMask = AnchorMask.EndOnly,
        ),
    )
    for (sibling in siblings) {
        val sb = sibling.stageBounds
        val spanStart = min(candidate.top, sb.top)
        val spanEnd = max(candidate.bottom, sb.bottom)
        targets.add(AxisSnapTarget(sb.left, spanStart, spanEnd, SnapGuideKind.Sibling, AnchorMask.All))
        targets.add(AxisSnapTarget(sb.center.x, spanStart, spanEnd, SnapGuideKind.Sibling, AnchorMask.All))
        targets.add(AxisSnapTarget(sb.right, spanStart, spanEnd, SnapGuideKind.Sibling, AnchorMask.All))
    }
    return targets
}

private fun buildHorizontalTargets(
    candidate: Rect,
    siblings: List<StageNodeSnapshot>,
    stageWidthPx: Float,
    stageHeightPx: Float,
    safeMarginPx: Float,
): List<AxisSnapTarget> {
    val targets = ArrayList<AxisSnapTarget>(3 + siblings.size * 3)
    targets.add(
        AxisSnapTarget(
            position = stageHeightPx / 2f,
            spanStart = 0f,
            spanEnd = stageWidthPx,
            kind = SnapGuideKind.StageCenter,
            anchorMask = AnchorMask.All,
        ),
    )
    targets.add(
        AxisSnapTarget(
            position = safeMarginPx,
            spanStart = 0f,
            spanEnd = stageWidthPx,
            kind = SnapGuideKind.SafeMargin,
            anchorMask = AnchorMask.StartOnly,
        ),
    )
    targets.add(
        AxisSnapTarget(
            position = stageHeightPx - safeMarginPx,
            spanStart = 0f,
            spanEnd = stageWidthPx,
            kind = SnapGuideKind.SafeMargin,
            anchorMask = AnchorMask.EndOnly,
        ),
    )
    for (sibling in siblings) {
        val sb = sibling.stageBounds
        val spanStart = min(candidate.left, sb.left)
        val spanEnd = max(candidate.right, sb.right)
        targets.add(AxisSnapTarget(sb.top, spanStart, spanEnd, SnapGuideKind.Sibling, AnchorMask.All))
        targets.add(AxisSnapTarget(sb.center.y, spanStart, spanEnd, SnapGuideKind.Sibling, AnchorMask.All))
        targets.add(AxisSnapTarget(sb.bottom, spanStart, spanEnd, SnapGuideKind.Sibling, AnchorMask.All))
    }
    return targets
}

private fun findBestAxisSnap(
    startEdge: Float,
    center: Float,
    endEdge: Float,
    targets: List<AxisSnapTarget>,
    thresholdStagePx: Float,
): AxisSnapMatch? {
    var best: AxisSnapMatch? = null
    for (target in targets) {
        val priority = kindPriority(target.kind)
        fun consider(anchor: Float) {
            val diff = target.position - anchor
            val dist = abs(diff)
            if (dist <= thresholdStagePx) {
                val cur = best
                if (cur == null || dist < cur.distance - 0.01f || (abs(dist - cur.distance) <= 0.01f && priority < cur.priority)) {
                    best = AxisSnapMatch(correction = diff, distance = dist, priority = priority)
                }
            }
        }
        if (target.anchorMask.allowsStart) consider(startEdge)
        if (target.anchorMask.allowsCenter) consider(center)
        if (target.anchorMask.allowsEnd) consider(endEdge)
    }
    return best
}

private fun collectAlignedGuides(
    orientation: SnapGuideOrientation,
    startEdge: Float,
    center: Float,
    endEdge: Float,
    targets: List<AxisSnapTarget>,
    out: MutableList<SnapGuideLine>,
) {
    val deduped = LinkedHashMap<Int, SnapGuideLine>()
    for (target in targets) {
        val aligned = (target.anchorMask.allowsStart && abs(target.position - startEdge) <= 0.5f) ||
            (target.anchorMask.allowsCenter && abs(target.position - center) <= 0.5f) ||
            (target.anchorMask.allowsEnd && abs(target.position - endEdge) <= 0.5f)
        if (!aligned) continue
        val key = (target.position * 2f).toInt()
        val existing = deduped[key]
        val line = SnapGuideLine(
            orientation = orientation,
            positionStagePx = target.position,
            spanStartStagePx = target.spanStart,
            spanEndStagePx = target.spanEnd,
            kind = target.kind,
        )
        if (existing == null || kindPriority(line.kind) < kindPriority(existing.kind)) {
            deduped[key] = line
        } else if (existing.kind == line.kind) {
            deduped[key] = existing.copy(
                spanStartStagePx = min(existing.spanStartStagePx, line.spanStartStagePx),
                spanEndStagePx = max(existing.spanEndStagePx, line.spanEndStagePx),
            )
        }
    }
    out.addAll(deduped.values)
}

private fun kindPriority(kind: SnapGuideKind): Int = when (kind) {
    SnapGuideKind.StageCenter -> 0
    SnapGuideKind.SafeMargin -> 1
    SnapGuideKind.Sibling -> 2
}
