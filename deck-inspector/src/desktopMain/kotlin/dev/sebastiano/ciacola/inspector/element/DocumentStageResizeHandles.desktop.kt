package dev.sebastiano.ciacola.inspector.element

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sebastiano.ciacola.document.FrameSpec
import dev.sebastiano.ciacola.inspector.design.inspectorAccent
import dev.sebastiano.ciacola.inspector.document.snapHalfUnit
import java.awt.Cursor
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.typography

/** The 8 interactive resize handles surrounding a selected stage node. */
enum class StageResizeHandle(
    val normX: Float,
    val normY: Float,
    val awtCursor: Int,
) {
    NW(0f, 0f, Cursor.NW_RESIZE_CURSOR),
    N(0.5f, 0f, Cursor.N_RESIZE_CURSOR),
    NE(1f, 0f, Cursor.NE_RESIZE_CURSOR),
    E(1f, 0.5f, Cursor.E_RESIZE_CURSOR),
    SE(1f, 1f, Cursor.SE_RESIZE_CURSOR),
    S(0.5f, 1f, Cursor.S_RESIZE_CURSOR),
    SW(0f, 1f, Cursor.SW_RESIZE_CURSOR),
    W(0f, 0.5f, Cursor.W_RESIZE_CURSOR),
}

/**
 * Pure half-unit snapped frame calculation for dragging one of the 8 stage resize handles.
 * Opposite edges remain anchored when clamping at [minWidth] / [minHeight].
 */
internal fun computeResizedFrame(
    handle: StageResizeHandle,
    startFrame: FrameSpec,
    deltaStageX: Double,
    deltaStageY: Double,
    minWidth: Double = 24.0,
    minHeight: Double = 24.0,
): FrameSpec {
    val origX = startFrame.x ?: 0.0
    val origY = startFrame.y ?: 0.0
    val origW = (startFrame.w ?: 240.0).coerceAtLeast(minWidth)
    val origH = (startFrame.h ?: 64.0).coerceAtLeast(minHeight)
    val origRight = origX + origW
    val origBottom = origY + origH

    var nextX = origX
    var nextY = origY
    var nextW = origW
    var nextH = origH

    when (handle) {
        StageResizeHandle.E, StageResizeHandle.NE, StageResizeHandle.SE -> {
            nextW = snapHalfUnit((origW + deltaStageX).coerceAtLeast(minWidth))
        }
        StageResizeHandle.W, StageResizeHandle.NW, StageResizeHandle.SW -> {
            val proposedX = snapHalfUnit(origX + deltaStageX)
            val clampedX = proposedX.coerceAtMost(origRight - minWidth)
            nextX = clampedX
            nextW = snapHalfUnit((origRight - clampedX).coerceAtLeast(minWidth))
        }
        StageResizeHandle.N, StageResizeHandle.S -> Unit
    }

    when (handle) {
        StageResizeHandle.S, StageResizeHandle.SE, StageResizeHandle.SW -> {
            nextH = snapHalfUnit((origH + deltaStageY).coerceAtLeast(minHeight))
        }
        StageResizeHandle.N, StageResizeHandle.NE, StageResizeHandle.NW -> {
            val proposedY = snapHalfUnit(origY + deltaStageY)
            val clampedY = proposedY.coerceAtMost(origBottom - minHeight)
            nextY = clampedY
            nextH = snapHalfUnit((origBottom - clampedY).coerceAtLeast(minHeight))
        }
        StageResizeHandle.E, StageResizeHandle.W -> Unit
    }

    return FrameSpec(
        x = snapHalfUnit(nextX),
        y = snapHalfUnit(nextY),
        w = snapHalfUnit(nextW).coerceAtLeast(minWidth),
        h = snapHalfUnit(nextH).coerceAtLeast(minHeight),
    )
}

internal fun formatHalfUnit(value: Double): String {
    val snapped = snapHalfUnit(value)
    val rounded = snapped.roundToInt().toDouble()
    return if (abs(snapped - rounded) < 0.01) {
        String.format(Locale.US, "%.0f", rounded)
    } else {
        String.format(Locale.US, "%.1f", snapped)
    }
}

/** Visual handle size; the hit target is twice as large. */
internal val STAGE_HANDLE_SIZE = 8.dp

/** Gap between the selection frame and the inner edge of each handle, so handles never cover glyphs. */
internal val STAGE_HANDLE_OUTSET = 4.dp

/**
 * Center of [handle] in overlay pixels: pushed [outsetPx] + half a handle outside [box] along the
 * handle's outward normal (corners move diagonally), so the visual square never overlaps content.
 */
internal fun stageHandleCenter(handle: StageResizeHandle, box: Rect, outsetPx: Float, handlePx: Float): Offset {
    val push = outsetPx + handlePx / 2f
    val dirX = when (handle.normX) { 0f -> -1f; 1f -> 1f; else -> 0f }
    val dirY = when (handle.normY) { 0f -> -1f; 1f -> 1f; else -> 0f }
    return Offset(
        x = box.left + box.width * handle.normX + dirX * push,
        y = box.top + box.height * handle.normY + dirY * push,
    )
}

/**
 * Collision-free dimension pill placement: tries centered below [selection], then centered
 * above, then inside its top-left corner, picking the first slot that fits inside the stage and
 * does not intersect any of [obstacles]. Falls back to the first slot that fits the stage.
 */
internal fun computeDimensionPillPlacement(
    selection: Rect,
    pillSize: Size,
    stageSize: Size,
    obstacles: List<Rect>,
    gapPx: Float,
    marginPx: Float = 4f,
): Offset {
    val centeredX = (selection.center.x - pillSize.width / 2f)
        .coerceIn(marginPx, (stageSize.width - pillSize.width - marginPx).coerceAtLeast(marginPx))
    val candidates = listOf(
        Offset(centeredX, selection.bottom + gapPx),
        Offset(centeredX, selection.top - gapPx - pillSize.height),
        Offset(selection.left + gapPx, selection.top + gapPx),
    )
    fun rectOf(o: Offset) = Rect(o.x, o.y, o.x + pillSize.width, o.y + pillSize.height)
    fun fits(r: Rect) = r.left >= marginPx - 0.5f && r.top >= marginPx - 0.5f &&
        r.right <= stageSize.width - marginPx + 0.5f && r.bottom <= stageSize.height - marginPx + 0.5f
    val fitting = candidates.filter { fits(rectOf(it)) }
    return fitting.firstOrNull { o -> obstacles.none { rectOverlapArea(rectOf(o), it) > 0.5f } }
        ?: fitting.firstOrNull()
        ?: Offset(
            selection.left.coerceIn(marginPx, (stageSize.width - pillSize.width - marginPx).coerceAtLeast(marginPx)),
            selection.top.coerceIn(marginPx, (stageSize.height - pillSize.height - marginPx).coerceAtLeast(marginPx)),
        )
}

/**
 * Interactive 8-point resize handles (`NW, N, NE, E, SE, S, SW, W`): 8dp squares drawn 4dp
 * outside the selection frame with a 16dp hit area, white fill, 1dp accent border and a 1dp
 * contrasting halo. Each drag is ONE undo step. With [groupStartRects] (2+ nodes) the frame is the
 * multi-selection union and dragging a handle resizes every node proportionally.
 */
@Composable
internal fun StageSelectionHandlesOverlay(
    nodeId: String,
    frameX: Double,
    frameY: Double,
    frameW: Double,
    frameH: Double,
    stageScale: Float,
    controller: DocumentElementInspectorController,
    groupStartRects: Map<String, Rect>? = null,
) {
    val density = LocalDensity.current
    val handlePx = with(density) { STAGE_HANDLE_SIZE.toPx() }
    val outsetPx = with(density) { STAGE_HANDLE_OUTSET.toPx() }
    val hitSizeDp = STAGE_HANDLE_SIZE * 2
    val halfHitPx = with(density) { hitSizeDp.toPx() } * 0.5f
    val accent = inspectorAccent()
    val box = Rect(
        left = (frameX * stageScale).toFloat(),
        top = (frameY * stageScale).toFloat(),
        right = ((frameX + frameW) * stageScale).toFloat(),
        bottom = ((frameY + frameH) * stageScale).toFloat(),
    )
    val latestFrame by rememberUpdatedState(FrameSpec(x = frameX, y = frameY, w = frameW, h = frameH))
    val latestScale by rememberUpdatedState(stageScale.coerceAtLeast(0.05f))
    val latestGroup by rememberUpdatedState(groupStartRects)

    for (handle in StageResizeHandle.entries) {
        val center = stageHandleCenter(handle, box, outsetPx, handlePx)
        Box(
            modifier = Modifier
                .offset { IntOffset((center.x - halfHitPx).roundToInt(), (center.y - halfHitPx).roundToInt()) }
                .size(hitSizeDp)
                .pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(handle.awtCursor)))
                .pointerInput(nodeId, handle) {
                    var gestureOpen = false
                    try {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Main)
                            if (controller.inlineEditingNodeId != null || controller.store.isReadOnly) {
                                return@awaitEachGesture
                            }
                            down.consume()
                            val startFrame = latestFrame
                            // Multi-selection: resize the union proportionally (one undo step).
                            val group = latestGroup?.takeIf { it.size > 1 }
                            val startUnion = Rect(
                                (startFrame.x ?: 0.0).toFloat(),
                                (startFrame.y ?: 0.0).toFloat(),
                                ((startFrame.x ?: 0.0) + (startFrame.w ?: 0.0)).toFloat(),
                                ((startFrame.y ?: 0.0) + (startFrame.h ?: 0.0)).toFloat(),
                            )
                            var accumStageX = 0.0
                            var accumStageY = 0.0
                            var dragged = false
                            while (true) {
                                val event = awaitPointerEvent(pass = PointerEventPass.Main)
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) {
                                    change.consume()
                                    if (dragged && gestureOpen) {
                                        gestureOpen = false
                                        if (group != null) controller.commitGesture() else controller.commitResizeGesture()
                                    }
                                    break
                                }
                                val delta = change.positionChange()
                                if (delta != Offset.Zero) {
                                    change.consume()
                                    accumStageX += delta.x / latestScale
                                    accumStageY += delta.y / latestScale
                                    if (!dragged) {
                                        dragged = true
                                        gestureOpen = if (group != null) {
                                            controller.beginGroupResizeGesture(group.size)
                                        } else {
                                            controller.beginResizeGesture(nodeId, startFrame)
                                        }
                                    }
                                    if (gestureOpen) {
                                        val next = computeResizedFrame(handle, startFrame, accumStageX, accumStageY)
                                        if (group != null) {
                                            val nx = (next.x ?: 0.0).toFloat()
                                            val ny = (next.y ?: 0.0).toFloat()
                                            val nextUnion = Rect(nx, ny, nx + (next.w ?: 0.0).toFloat(), ny + (next.h ?: 0.0).toFloat())
                                            controller.updateGroupResizeGesture(startUnion, nextUnion, group)
                                        } else {
                                            controller.updateResizeGesture(nodeId, next)
                                        }
                                    }
                                }
                            }
                        }
                    } finally {
                        if (gestureOpen) {
                            gestureOpen = false
                            controller.cancelResizeGesture()
                        }
                    }
                }
                .testTag("ciacola-stage-resize-handle-$nodeId-${handle.name}"),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(STAGE_HANDLE_SIZE + 2.dp)
                    .background(Color(0x99000000), RoundedCornerShape(2.dp))
                    .padding(1.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(STAGE_HANDLE_SIZE)
                        .background(Color.White, RoundedCornerShape(1.dp))
                        .border(1.dp, accent, RoundedCornerShape(1.dp)),
                )
            }
        }
    }
}

/**
 * Dimension / position pill shown ONLY while dragging or resizing, placed collision-free
 * (below, then above, then inside the top-left corner of [selectionPx]).
 */
@Composable
internal fun StageDimensionPill(
    text: String,
    selectionPx: Rect,
    overlaySizePx: IntSize,
    obstaclesPx: List<Rect>,
) {
    val density = LocalDensity.current
    val colors = JewelTheme.globalColors
    val pillSize = with(density) { Size((text.length * 6.5f + 14f).dp.toPx(), 20.dp.toPx()) }
    val placement = computeDimensionPillPlacement(
        selection = selectionPx,
        pillSize = pillSize,
        stageSize = Size(overlaySizePx.width.toFloat(), overlaySizePx.height.toFloat()),
        obstacles = obstaclesPx,
        gapPx = with(density) { (STAGE_HANDLE_OUTSET + STAGE_HANDLE_SIZE + 4.dp).toPx() },
    )
    Box(
        modifier = Modifier
            .offset { IntOffset(placement.x.roundToInt(), placement.y.roundToInt()) }
            .clip(RoundedCornerShape(4.dp))
            .background(colors.panelBackground.copy(alpha = 0.94f))
            .border(1.dp, inspectorAccent(), RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
            .testTag("ciacola-stage-dimension-pill"),
    ) {
        Text(
            text = text,
            style = JewelTheme.typography.small.copy(fontSize = 11.sp, fontWeight = FontWeight.Medium, fontFeatureSettings = "tnum"),
            color = colors.text.normal,
            maxLines = 1,
        )
    }
}
