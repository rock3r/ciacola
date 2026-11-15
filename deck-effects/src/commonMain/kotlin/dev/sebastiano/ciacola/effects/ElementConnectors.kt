package dev.sebastiano.ciacola.effects

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.sebastiano.ciacola.inspector.LocalStageDragPreview
import dev.sebastiano.ciacola.inspector.LocalStageInspectionRegistry
import dev.sebastiano.ciacola.inspector.LocalStageRootCoordinates
import dev.sebastiano.ciacola.theme.LocalDeckTheme
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** Anchor edge on a stage element's bounding box for [ElementConnector]. */
enum class ConnectorAnchor { Auto, Top, Bottom, Start, End, Center }

/** Visual routing and arrowhead style for [ElementConnector]. */
enum class ConnectorStyle { Line, Arrow, CurvedArrow, Orthogonal, TreeEdge }

/** Resolved connector endpoints and outward anchor normals in local coordinate space. */
@Immutable
data class ConnectorEndpoints(
    val start: Offset,
    val end: Offset,
    val startNormal: Offset,
    val endNormal: Offset,
)

/** Computes endpoints on [fromRect] and [toRect], resolving [ConnectorAnchor.Auto] to facing edges. */
fun resolveConnectorEndpoints(
    fromRect: Rect,
    toRect: Rect,
    fromAnchor: ConnectorAnchor = ConnectorAnchor.Auto,
    toAnchor: ConnectorAnchor = ConnectorAnchor.Auto,
    gapPx: Float = 0f,
    layoutDirection: LayoutDirection = LayoutDirection.Ltr,
): ConnectorEndpoints {
    val dx = toRect.center.x - fromRect.center.x
    val dy = toRect.center.y - fromRect.center.y
    val horiz = abs(dx) >= abs(dy)
    val resolvedFrom = when {
        fromAnchor != ConnectorAnchor.Auto -> fromAnchor
        horiz -> if (dx >= 0f) ConnectorAnchor.End else ConnectorAnchor.Start
        else -> if (dy >= 0f) ConnectorAnchor.Bottom else ConnectorAnchor.Top
    }
    val resolvedTo = when {
        toAnchor != ConnectorAnchor.Auto -> toAnchor
        horiz -> if (dx >= 0f) ConnectorAnchor.Start else ConnectorAnchor.End
        else -> if (dy >= 0f) ConnectorAnchor.Top else ConnectorAnchor.Bottom
    }
    val (rawStart, sNorm) = anchorPointAndNormal(fromRect, resolvedFrom, layoutDirection)
    val (rawEnd, eNorm) = anchorPointAndNormal(toRect, resolvedTo, layoutDirection)
    if (gapPx <= 0f) return ConnectorEndpoints(rawStart, rawEnd, sNorm, eNorm)

    val delta = rawEnd - rawStart
    val dist = hypot(delta.x, delta.y)
    val unit = if (dist > 1e-3f) Offset(delta.x / dist, delta.y / dist) else Offset.Zero
    val safeGap = gapPx.coerceAtMost(dist * 0.45f)
    return ConnectorEndpoints(
        start = rawStart + (if (sNorm != Offset.Zero) sNorm else unit) * safeGap,
        end = rawEnd + (if (eNorm != Offset.Zero) eNorm else -unit) * safeGap,
        startNormal = sNorm,
        endNormal = eNorm,
    )
}

/** Builds the vector [Path] connecting [endpoints] according to [style]. */
fun buildConnectorPath(
    endpoints: ConnectorEndpoints,
    style: ConnectorStyle,
    destination: Path = Path(),
): Path {
    destination.reset()
    val (start, end, sNorm, eNorm) = endpoints
    val dx = end.x - start.x
    val dy = end.y - start.y
    val dist = hypot(dx, dy)
    if (dist < 1e-3f) return destination

    destination.moveTo(start.x, start.y)
    when (style) {
        ConnectorStyle.Line, ConnectorStyle.Arrow -> destination.lineTo(end.x, end.y)
        ConnectorStyle.CurvedArrow -> {
            val pull = (dist * 0.42f).coerceIn(28f, 160f)
            val cp1 = if (sNorm != Offset.Zero) start + sNorm * pull
            else Offset(start.x + dx * 0.35f - dy * 0.14f, start.y + dy * 0.35f + dx * 0.14f)
            val cp2 = if (eNorm != Offset.Zero) end + eNorm * pull
            else Offset(start.x + dx * 0.65f - dy * 0.14f, start.y + dy * 0.65f + dx * 0.14f)
            destination.cubicTo(cp1.x, cp1.y, cp2.x, cp2.y, end.x, end.y)
        }
        ConnectorStyle.Orthogonal -> when {
            abs(sNorm.x) > 0.5f && abs(eNorm.x) > 0.5f -> {
                val midX = (start.x + end.x) * 0.5f
                destination.lineTo(midX, start.y); destination.lineTo(midX, end.y); destination.lineTo(end.x, end.y)
            }
            abs(sNorm.y) > 0.5f && abs(eNorm.y) > 0.5f -> {
                val midY = (start.y + end.y) * 0.5f
                destination.lineTo(start.x, midY); destination.lineTo(end.x, midY); destination.lineTo(end.x, end.y)
            }
            abs(sNorm.x) > 0.5f -> { destination.lineTo(end.x, start.y); destination.lineTo(end.x, end.y) }
            else -> { destination.lineTo(start.x, end.y); destination.lineTo(end.x, end.y) }
        }
        ConnectorStyle.TreeEdge -> {
            val horizTree = abs(sNorm.x) > 0.5f || (sNorm == Offset.Zero && abs(dx) >= abs(dy))
            if (horizTree) {
                val midX = (start.x + end.x) * 0.5f
                destination.cubicTo(midX, start.y, midX, end.y, end.x, end.y)
            } else {
                val midY = (start.y + end.y) * 0.5f
                destination.cubicTo(start.x, midY, end.x, midY, end.x, end.y)
            }
        }
    }
    return destination
}

/**
 * Lightweight per-slide anchor coordinate registry populated unconditionally (including inside the
 * Overview thumbnail grid, where Studio inspection is suppressed) so [ElementConnector] always
 * resolves live element bounds.
 */
@Stable
class StageAnchorRegistry {
    private class AnchorRecord(
        var coords: LayoutCoordinates,
        var owner: Any,
        var lastBoundsInRoot: Rect,
    )

    private val records = HashMap<String, AnchorRecord>()
    private val versions = mutableStateMapOf<String, Int>()

    fun record(elementId: String, coords: LayoutCoordinates?, owner: Any) {
        if (coords == null || !coords.isAttached) return
        val currentBounds = runCatching { coords.boundsInRoot() }.getOrDefault(Rect.Zero)
        val existing = records[elementId]
        if (existing != null) {
            val unchanged = existing.coords === coords &&
                existing.owner === owner &&
                existing.lastBoundsInRoot == currentBounds
            existing.coords = coords
            existing.owner = owner
            existing.lastBoundsInRoot = currentBounds
            if (unchanged) return
        } else {
            records[elementId] = AnchorRecord(coords, owner, currentBounds)
        }
        versions[elementId] = (versions[elementId] ?: 0) + 1
    }

    fun remove(elementId: String, owner: Any) {
        val existing = records[elementId] ?: return
        if (existing.owner === owner) {
            records.remove(elementId)
            versions.remove(elementId)
        }
    }

    fun versionOf(elementId: String): Int = versions[elementId] ?: 0

    fun boundsRelativeTo(elementId: String, canvasCoords: LayoutCoordinates?): Rect? {
        versions[elementId]
        val cCoords = canvasCoords?.takeIf { it.isAttached } ?: return null
        val targetCoords = records[elementId]?.coords?.takeIf { it.isAttached } ?: return null
        val rect = runCatching { cCoords.localBoundingBoxOf(targetCoords, clipBounds = false) }.getOrNull() ?: return null
        return rect.takeIf { it.width > 0.5f && it.height > 0.5f }
    }
}

/** Provides the enclosing slide's [StageAnchorRegistry] to [ElementConnector] and stage elements. */
val LocalStageAnchorRegistry = compositionLocalOf<StageAnchorRegistry?> { null }

/**
 * Draws a live stage-space connector between registered slide elements ([fromId] -> [toId]) using
 * [LocalStageAnchorRegistry] (with fallback to [LocalStageInspectionRegistry]), tracking element
 * drags, nudges, Overview thumbnails, and layout changes.
 */
@Composable
fun ElementConnector(
    fromId: String,
    toId: String,
    fromAnchor: ConnectorAnchor = ConnectorAnchor.Auto,
    toAnchor: ConnectorAnchor = ConnectorAnchor.Auto,
    style: ConnectorStyle = ConnectorStyle.Arrow,
    color: Color = LocalDeckTheme.current.colors.accent,
    strokeWidth: Dp = 2.5.dp,
    progress: () -> Float = { 1f },
    modifier: Modifier = Modifier,
    gap: Dp = 0.dp,
    dashPattern: Pair<Dp, Dp>? = null,
    headLength: Dp = 12.dp,
    headAngleDegrees: Float = 28f,
) {
    val anchorRegistry = LocalStageAnchorRegistry.current
    val inspectionRegistry = LocalStageInspectionRegistry.current
    if (anchorRegistry == null && inspectionRegistry == null) return
    val rootHolder = LocalStageRootCoordinates.current
    val dragPreview = LocalStageDragPreview.current
    var canvasCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val sourcePath = remember { Path() }
    val trimmedPath = remember { Path() }
    val headPath = remember { Path() }

    Canvas(modifier = modifier.fillMaxSize().onPlaced { canvasCoords = it }) {
        val p = progress().coerceIn(0f, 1f)
        if (p <= 0f) return@Canvas

        // Subscribe DrawScope to anchor placements, snapshot updates, and active drags.
        anchorRegistry?.versionOf(fromId)
        anchorRegistry?.versionOf(toId)
        val fromSnap = inspectionRegistry?.nodes?.get(fromId)
        val toSnap = inspectionRegistry?.nodes?.get(toId)
        dragPreview?.offsetFor(fromId)
        dragPreview?.offsetFor(toId)

        val origin by lazy(LazyThreadSafetyMode.NONE) {
            resolveStageBounds(canvasCoords, rootHolder)?.topLeft ?: Offset.Zero
        }
        val fromLocal = anchorRegistry?.boundsRelativeTo(fromId, canvasCoords)
            ?: (inspectionRegistry?.liveStageBoundsOf(fromId) ?: fromSnap?.stageBounds)
                ?.translate(-origin.x, -origin.y)
            ?: return@Canvas
        val toLocal = anchorRegistry?.boundsRelativeTo(toId, canvasCoords)
            ?: (inspectionRegistry?.liveStageBoundsOf(toId) ?: toSnap?.stageBounds)
                ?.translate(-origin.x, -origin.y)
            ?: return@Canvas
        val endpoints = resolveConnectorEndpoints(
            fromRect = fromLocal,
            toRect = toLocal,
            fromAnchor = fromAnchor,
            toAnchor = toAnchor,
            gapPx = gap.toPx(),
            layoutDirection = layoutDirection,
        )
        buildConnectorPath(endpoints, style, sourcePath)
        val tip = trimPath(sourcePath, p, trimmedPath) ?: return@Canvas

        val strokePx = strokeWidth.toPx()
        val pathEffect = dashPattern?.let { (on, off) ->
            PathEffect.dashPathEffect(floatArrayOf(on.toPx(), off.toPx()), 0f)
        }
        drawPath(
            path = trimmedPath,
            color = color,
            style = Stroke(width = strokePx, cap = StrokeCap.Round, join = StrokeJoin.Round, pathEffect = pathEffect),
        )
        if (style != ConnectorStyle.Line && headLength > 0.dp) {
            val headPx = arrowHeadLength(headLength.toPx(), p)
            if (headPx > 1f) drawConnectorArrowHead(headPath, tip.position, tip.tangent, headPx, headAngleDegrees, color, strokePx)
        }
    }
}

private fun anchorPointAndNormal(rect: Rect, anchor: ConnectorAnchor, dir: LayoutDirection): Pair<Offset, Offset> {
    val rtl = dir == LayoutDirection.Rtl
    return when (anchor) {
        ConnectorAnchor.Top -> Offset(rect.center.x, rect.top) to Offset(0f, -1f)
        ConnectorAnchor.Bottom -> Offset(rect.center.x, rect.bottom) to Offset(0f, 1f)
        ConnectorAnchor.Start -> if (rtl) Offset(rect.right, rect.center.y) to Offset(1f, 0f)
        else Offset(rect.left, rect.center.y) to Offset(-1f, 0f)
        ConnectorAnchor.End -> if (rtl) Offset(rect.left, rect.center.y) to Offset(-1f, 0f)
        else Offset(rect.right, rect.center.y) to Offset(1f, 0f)
        ConnectorAnchor.Center, ConnectorAnchor.Auto -> rect.center to Offset.Zero
    }
}

private fun DrawScope.drawConnectorArrowHead(
    headPath: Path,
    tip: Offset,
    tangent: Offset,
    lengthPx: Float,
    angleDegrees: Float,
    color: Color,
    strokeWidthPx: Float,
) {
    val base = atan2(tangent.y.toDouble(), tangent.x.toDouble())
    val spread = angleDegrees * PI / 180.0
    val lAngle = base + PI - spread
    val rAngle = base + PI + spread
    headPath.reset()
    headPath.moveTo(tip.x + (cos(lAngle) * lengthPx).toFloat(), tip.y + (sin(lAngle) * lengthPx).toFloat())
    headPath.lineTo(tip.x, tip.y)
    headPath.lineTo(tip.x + (cos(rAngle) * lengthPx).toFloat(), tip.y + (sin(rAngle) * lengthPx).toFloat())
    drawPath(headPath, color = color, style = Stroke(width = strokeWidthPx, cap = StrokeCap.Round, join = StrokeJoin.Round))
}
