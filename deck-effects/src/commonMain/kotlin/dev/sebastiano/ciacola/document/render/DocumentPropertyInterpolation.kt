package dev.sebastiano.ciacola.document.render

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp as lerpColor
import dev.sebastiano.ciacola.document.Build
import dev.sebastiano.ciacola.document.Fill
import dev.sebastiano.ciacola.document.FrameSpec
import dev.sebastiano.ciacola.document.JsonMergePatch
import dev.sebastiano.ciacola.document.NodeKind
import dev.sebastiano.ciacola.document.NodeSpec
import dev.sebastiano.ciacola.document.PaddingSpec
import dev.sebastiano.ciacola.document.ScaleValue
import dev.sebastiano.ciacola.document.ShapeGeometry
import dev.sebastiano.ciacola.document.StrokeSpec
import dev.sebastiano.ciacola.document.SurfaceSpec
import dev.sebastiano.ciacola.document.Vec2
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Pure property-interpolation helpers used by [DocumentMotionEvaluator] for intra-slide `change`
 * builds and cross-slide `magicMove` travelling nodes and descendants.
 */
internal fun interpolateDocumentNodeProperties(
    from: NodeSpec,
    to: NodeSpec,
    t: Float,
    colorResolver: DocumentColorResolver,
    interpolateFramePosition: Boolean = true,
): NodeSpec {
    if (t <= 0f) return from
    if (t >= 1f) return to
    val td = t.toDouble()
    val clampedT = t.coerceIn(0f, 1f)

    val frame = interpolateFrame(from.frame, to.frame, td, interpolatePosition = interpolateFramePosition)
    val offset = interpolateVec2(from.offset ?: Vec2(0.0, 0.0), to.offset ?: Vec2(0.0, 0.0), td)
        .takeIf { from.offset != null || to.offset != null }
    val opacity = lerpDouble(from.opacity, to.opacity, td)
    val rotation = lerpDouble(from.rotation, to.rotation, td)
    val scale = interpolateScale(from.scale, to.scale, td)
    val origin = interpolateVec2(from.origin ?: Vec2(0.5, 0.5), to.origin ?: Vec2(0.5, 0.5), td)
        .takeIf { from.origin != null || to.origin != null }

    val fromShape = from.shape
    val toShape = to.shape
    val shape = if (fromShape != null && toShape != null) {
        val fg = fromShape.geometry
        val tg = toShape.geometry
        val (interpGeom, interpCorner) = when {
            (fg is ShapeGeometry.Ellipse && tg is ShapeGeometry.Rect &&
                isNearSquareCircleWithExplicitFrame(from) && hasExplicitFrameExtent(to)) -> {
                val rFrom = min(from.frame!!.w!!, from.frame!!.h!!) * 0.5
                val rTo = toShape.cornerRadius ?: 0.0
                ShapeGeometry.Rect to lerpDouble(rFrom, rTo, td)
            }
            (fg is ShapeGeometry.Rect && tg is ShapeGeometry.Ellipse &&
                hasExplicitFrameExtent(from) && isNearSquareCircleWithExplicitFrame(to)) -> {
                val rFrom = fromShape.cornerRadius ?: 0.0
                val rTo = min(to.frame!!.w!!, to.frame!!.h!!) * 0.5
                ShapeGeometry.Rect to lerpDouble(rFrom, rTo, td)
            }
            else -> {
                val geom = interpolateShapeGeometryAndReference(fg, tg, null, null, clampedT).geometry
                val corner = if (fromShape.cornerRadius != null || toShape.cornerRadius != null) {
                    lerpDouble(fromShape.cornerRadius ?: 0.0, toShape.cornerRadius ?: 0.0, td)
                } else {
                    null
                }
                geom to corner
            }
        }
        (if (clampedT >= 0.5f) toShape else fromShape).copy(
            geometry = interpGeom,
            fill = interpolateFill(fromShape.fill, toShape.fill, clampedT, colorResolver),
            stroke = interpolateStroke(fromShape.stroke, toShape.stroke, clampedT, colorResolver),
            cornerRadius = interpCorner,
        )
    } else {
        if (clampedT >= 0.5f) toShape else fromShape
    }

    val fromText = from.text
    val toText = to.text
    val text = if (fromText != null && toText != null) {
        val base = if (clampedT >= 0.5f) toText else fromText
        base.copy(
            size = lerpNullableDouble(fromText.size, toText.size, td),
            color = interpolateColorString(fromText.color, toText.color, clampedT, colorResolver),
            surface = interpolateSurface(fromText.surface, toText.surface, clampedT, colorResolver),
            axes = interpolateAxes(fromText.axes, toText.axes, td),
        )
    } else {
        if (clampedT >= 0.5f) toText else fromText
    }

    val fromStack = from.stack
    val toStack = to.stack
    val stack = if (fromStack != null && toStack != null) {
        val base = if (clampedT >= 0.5f) toStack else fromStack
        base.copy(
            padding = interpolateNullablePadding(fromStack.padding, toStack.padding, td),
            surface = interpolateSurface(fromStack.surface, toStack.surface, clampedT, colorResolver),
        )
    } else {
        if (clampedT >= 0.5f) toStack else fromStack
    }

    val fromRow = from.row
    val toRow = to.row
    val row = if (fromRow != null && toRow != null) {
        val base = if (clampedT >= 0.5f) toRow else fromRow
        base.copy(
            gap = lerpNullableDouble(fromRow.gap, toRow.gap, td),
            padding = interpolateNullablePadding(fromRow.padding, toRow.padding, td),
            surface = interpolateSurface(fromRow.surface, toRow.surface, clampedT, colorResolver),
        )
    } else {
        if (clampedT >= 0.5f) toRow else fromRow
    }

    val fromColumn = from.column
    val toColumn = to.column
    val column = if (fromColumn != null && toColumn != null) {
        val base = if (clampedT >= 0.5f) toColumn else fromColumn
        base.copy(
            gap = lerpNullableDouble(fromColumn.gap, toColumn.gap, td),
            padding = interpolateNullablePadding(fromColumn.padding, toColumn.padding, td),
            surface = interpolateSurface(fromColumn.surface, toColumn.surface, clampedT, colorResolver),
        )
    } else {
        if (clampedT >= 0.5f) toColumn else fromColumn
    }

    val fromCode = from.code
    val toCode = to.code
    val code = if (fromCode != null && toCode != null) {
        val base = if (clampedT >= 0.5f) toCode else fromCode
        base.copy(surface = interpolateSurface(fromCode.surface, toCode.surface, clampedT, colorResolver))
    } else {
        if (clampedT >= 0.5f) toCode else fromCode
    }

    val fromLoupe = from.loupe
    val toLoupe = to.loupe
    val loupe = if (fromLoupe != null && toLoupe != null) {
        val base = if (clampedT >= 0.5f) toLoupe else fromLoupe
        base.copy(
            zoom = lerpDouble(fromLoupe.zoom, toLoupe.zoom, td),
            diameter = lerpNullableDouble(fromLoupe.diameter, toLoupe.diameter, td),
            lensFill = interpolateFill(fromLoupe.lensFill, toLoupe.lensFill, clampedT, colorResolver),
        )
    } else {
        if (clampedT >= 0.5f) toLoupe else fromLoupe
    }

    return (if (clampedT >= 0.5f) to else from).copy(
        frame = frame,
        offset = offset,
        opacity = opacity,
        rotation = rotation,
        scale = scale,
        origin = origin,
        shape = shape,
        text = text,
        stack = stack,
        row = row,
        column = column,
        code = code,
        loupe = loupe,
    )
}

/**
 * Derives the frame against which [resolvedNode]'s [ShapeGeometry.Path] was authored at
 * [upToBeat] (and at or before [upToBuildIndex] when evaluating mid-beat sequential `change`
 * builds). Walks completed `Build.Change` builds in order and returns the `frame` produced by the
 * last completed `Build.Change` that actually altered `shape.geometry`, or [rawNode]'s base `frame`
 * otherwise. Returns `null` if [resolvedNode] is not a [ShapeGeometry.Path] or lacks explicit `w`/`h`.
 */
internal fun resolveAuthoredShapeReferenceSize(
    rawNode: NodeSpec,
    resolvedNode: NodeSpec,
    upToBeat: Int,
    resolveNodeAfterBuild: (NodeSpec, Int) -> NodeSpec = JsonMergePatch::resolveNodeAfterBuild,
    upToBuildIndex: Int? = null,
): Size? {
    val resolvedGeom = resolvedNode.shape?.geometry as? ShapeGeometry.Path ?: return null
    var authoredFrame: FrameSpec? = rawNode.frame
    var prevGeom = rawNode.shape?.geometry

    for ((idx, build) in rawNode.builds.withIndex()) {
        if (build !is Build.Change) continue
        val included = when {
            build.beat < upToBeat -> true
            build.beat == upToBeat -> upToBuildIndex == null || idx <= upToBuildIndex
            else -> false
        }
        if (!included) continue
        val after = resolveNodeAfterBuild(rawNode, idx)
        val afterGeom = after.shape?.geometry
        if (afterGeom != prevGeom) {
            authoredFrame = after.frame
            prevGeom = afterGeom
        }
    }

    val frame = if (prevGeom == resolvedGeom) authoredFrame else resolvedNode.frame
    val w = frame?.w?.toFloat() ?: return null
    val h = frame.h?.toFloat() ?: return null
    return Size(w, h)
}

internal data class InterpolatedShapeGeometryAndRef(
    val geometry: ShapeGeometry,
    val referenceSize: Size?,
)

/**
 * Single source of truth pairing interpolated [ShapeGeometry] with its authoring reference [Size].
 * When both endpoints share the exact same [ShapeGeometry.Path] and both have non-null authored
 * sizes, the reference size is interpolated continuously across [t] so mid-flight `t = 0.5` never
 * snaps; otherwise geometry and reference size switch together at `t = 0.5f`.
 */
internal fun interpolateShapeGeometryAndReference(
    fromGeometry: ShapeGeometry,
    toGeometry: ShapeGeometry,
    fromAuthoredSize: Size?,
    toAuthoredSize: Size?,
    t: Float,
): InterpolatedShapeGeometryAndRef {
    val p = t.coerceIn(0f, 1f)
    val geom = if (p >= 0.5f) toGeometry else fromGeometry
    val ref = if (fromGeometry is ShapeGeometry.Path &&
        fromGeometry == toGeometry &&
        fromAuthoredSize != null &&
        toAuthoredSize != null
    ) {
        Size(
            width = fromAuthoredSize.width + (toAuthoredSize.width - fromAuthoredSize.width) * p,
            height = fromAuthoredSize.height + (toAuthoredSize.height - fromAuthoredSize.height) * p,
        )
    } else if (p < 0.5f) {
        fromAuthoredSize
    } else {
        toAuthoredSize
    }
    return InterpolatedShapeGeometryAndRef(geom, ref)
}

internal fun shapeReferenceSizeForInterpolation(
    fromGeometry: ShapeGeometry?,
    toGeometry: ShapeGeometry?,
    fromAuthoredSize: Size?,
    toAuthoredSize: Size?,
    t: Float,
): Size? {
    val p = t.coerceIn(0f, 1f)
    if (fromGeometry != null && toGeometry != null) {
        return interpolateShapeGeometryAndReference(
            fromGeometry = fromGeometry,
            toGeometry = toGeometry,
            fromAuthoredSize = fromAuthoredSize,
            toAuthoredSize = toAuthoredSize,
            t = p,
        ).referenceSize
    }
    return if (p < 0.5f) fromAuthoredSize else toAuthoredSize
}

internal fun resolveChangeShapeReferenceSize(
    rawNode: NodeSpec,
    fromNode: NodeSpec,
    toNode: NodeSpec,
    fromUpToBeat: Int,
    fromUpToBuildIndex: Int?,
    toUpToBeat: Int,
    toUpToBuildIndex: Int?,
    t: Float,
    resolveNodeAfterBuild: (NodeSpec, Int) -> NodeSpec,
): Size? {
    if (rawNode.kind != NodeKind.Shape) return null
    val fromAuthored = resolveAuthoredShapeReferenceSize(
        rawNode = rawNode,
        resolvedNode = fromNode,
        upToBeat = fromUpToBeat,
        resolveNodeAfterBuild = resolveNodeAfterBuild,
        upToBuildIndex = fromUpToBuildIndex,
    )
    val toAuthored = resolveAuthoredShapeReferenceSize(
        rawNode = rawNode,
        resolvedNode = toNode,
        upToBeat = toUpToBeat,
        resolveNodeAfterBuild = resolveNodeAfterBuild,
        upToBuildIndex = toUpToBuildIndex,
    )
    return shapeReferenceSizeForInterpolation(
        fromGeometry = fromNode.shape?.geometry,
        toGeometry = toNode.shape?.geometry,
        fromAuthoredSize = fromAuthored,
        toAuthoredSize = toAuthored,
        t = t,
    )
}

internal fun resolveTravellingShapeReferenceSize(
    rawFromNode: NodeSpec,
    fromNode: NodeSpec,
    rawToNode: NodeSpec,
    toNode: NodeSpec,
    fromSlideLastBeat: Int,
    moveProgress: Float,
    resolveFromNodeAfterBuild: (NodeSpec, Int) -> NodeSpec,
    resolveToNodeAfterBuild: (NodeSpec, Int) -> NodeSpec,
): Size? {
    if (toNode.kind != NodeKind.Shape && fromNode.kind != NodeKind.Shape) return null
    val fromAuthored = resolveAuthoredShapeReferenceSize(
        rawNode = rawFromNode,
        resolvedNode = fromNode,
        upToBeat = fromSlideLastBeat,
        resolveNodeAfterBuild = resolveFromNodeAfterBuild,
    )
    val toAuthored = resolveAuthoredShapeReferenceSize(
        rawNode = rawToNode,
        resolvedNode = toNode,
        upToBeat = 0,
        resolveNodeAfterBuild = resolveToNodeAfterBuild,
    )
    return shapeReferenceSizeForInterpolation(
        fromGeometry = fromNode.shape?.geometry,
        toGeometry = toNode.shape?.geometry,
        fromAuthoredSize = fromAuthored,
        toAuthoredSize = toAuthored,
        t = moveProgress,
    )
}

private fun isNearSquareCircleWithExplicitFrame(node: NodeSpec): Boolean {
    val w = node.frame?.w ?: return false
    val h = node.frame?.h ?: return false
    if (w <= 0.0 || h <= 0.0) return false
    return abs(w - h) <= max(1.0, min(2.0, max(w, h) * 0.02))
}

private fun hasExplicitFrameExtent(node: NodeSpec): Boolean =
    node.frame?.w != null && node.frame?.h != null

internal fun interpolateDocumentPadding(a: PaddingSpec?, b: PaddingSpec?, t: Double): PaddingSpec = PaddingSpec(
    left = lerpDouble(a?.left ?: 0.0, b?.left ?: 0.0, t),
    top = lerpDouble(a?.top ?: 0.0, b?.top ?: 0.0, t),
    right = lerpDouble(a?.right ?: 0.0, b?.right ?: 0.0, t),
    bottom = lerpDouble(a?.bottom ?: 0.0, b?.bottom ?: 0.0, t),
)

private fun interpolateFrame(
    a: FrameSpec?,
    b: FrameSpec?,
    t: Double,
    interpolatePosition: Boolean = true,
): FrameSpec? {
    if (a == null && b == null) return null
    return FrameSpec(
        x = if (interpolatePosition) lerpNullableDouble(a?.x, b?.x, t) else (a?.x ?: b?.x),
        y = if (interpolatePosition) lerpNullableDouble(a?.y, b?.y, t) else (a?.y ?: b?.y),
        w = lerpNullableDouble(a?.w, b?.w, t),
        h = lerpNullableDouble(a?.h, b?.h, t),
    )
}

private fun interpolateNullablePadding(a: PaddingSpec?, b: PaddingSpec?, t: Double): PaddingSpec? {
    if (a == null && b == null) return null
    return interpolateDocumentPadding(a, b, t)
}

private fun interpolateVec2(a: Vec2, b: Vec2, t: Double): Vec2 =
    Vec2(x = lerpDouble(a.x, b.x, t), y = lerpDouble(a.y, b.y, t))

private fun interpolateScale(a: ScaleValue?, b: ScaleValue?, t: Double): ScaleValue? {
    if (a == null && b == null) return null
    val sa = a ?: ScaleValue.Identity
    val sb = b ?: ScaleValue.Identity
    val sx = lerpDouble(sa.scaleX, sb.scaleX, t)
    val sy = lerpDouble(sa.scaleY, sb.scaleY, t)
    return if (sx == sy) ScaleValue.Uniform(sx) else ScaleValue.NonUniform(sx, sy)
}

private fun interpolateSurface(
    a: SurfaceSpec?,
    b: SurfaceSpec?,
    t: Float,
    colorResolver: DocumentColorResolver,
): SurfaceSpec? {
    if (a == null && b == null) return null
    val base = if (t >= 0.5f) b ?: a!! else a ?: b!!
    val corner = if (a?.cornerRadius != null || b?.cornerRadius != null) {
        lerpDouble(a?.cornerRadius ?: 0.0, b?.cornerRadius ?: 0.0, t.toDouble())
    } else {
        null
    }
    return base.copy(
        fill = interpolateFill(a?.fill, b?.fill, t, colorResolver),
        stroke = interpolateStroke(a?.stroke, b?.stroke, t, colorResolver),
        cornerRadius = corner,
    )
}

private fun interpolateFill(
    a: Fill?,
    b: Fill?,
    t: Float,
    colorResolver: DocumentColorResolver,
): Fill? {
    if (a is Fill.Solid && b is Fill.Solid) {
        val ca = colorResolver.resolveOrNull(a.color)
        val cb = colorResolver.resolveOrNull(b.color)
        if (ca != null && cb != null) {
            return Fill.Solid(colorToHexArgb(lerpColor(ca, cb, t)))
        }
    }
    return if (t >= 0.5f) b else a
}

private fun interpolateStroke(
    a: StrokeSpec?,
    b: StrokeSpec?,
    t: Float,
    colorResolver: DocumentColorResolver,
): StrokeSpec? {
    if (a == null && b == null) return null
    if (a != null && b != null) {
        val col = interpolateColorString(a.color, b.color, t, colorResolver) ?: b.color
        return (if (t >= 0.5f) b else a).copy(
            color = col,
            width = lerpDouble(a.width, b.width, t.toDouble()),
        )
    }
    return if (t >= 0.5f) b else a
}

private fun interpolateColorString(
    a: String?,
    b: String?,
    t: Float,
    colorResolver: DocumentColorResolver,
): String? {
    if (a == null && b == null) return null
    val ca = a?.let { colorResolver.resolveOrNull(it) }
    val cb = b?.let { colorResolver.resolveOrNull(it) }
    if (ca != null && cb != null) {
        return colorToHexArgb(lerpColor(ca, cb, t))
    }
    return if (t >= 0.5f) b else a
}

private fun colorToHexArgb(color: Color): String {
    val a = (color.alpha * 255f + 0.5f).toInt().coerceIn(0, 255)
    val r = (color.red * 255f + 0.5f).toInt().coerceIn(0, 255)
    val g = (color.green * 255f + 0.5f).toInt().coerceIn(0, 255)
    val b = (color.blue * 255f + 0.5f).toInt().coerceIn(0, 255)
    fun hex2(v: Int): String = v.toString(16).uppercase().padStart(2, '0')
    return "#${hex2(a)}${hex2(r)}${hex2(g)}${hex2(b)}"
}

private fun lerpDouble(a: Double, b: Double, t: Double): Double = a + (b - a) * t

/**
 * Interpolates per-tag variable font axis coordinates. A tag set on both sides moves smoothly; a
 * tag set on one side only has no known other end (the face's default lives in the font), so it
 * switches at the midpoint like other unpaired properties. Values are rounded to 1/100 of an axis
 * unit, so the same time always yields the same coordinates and reuses the same axis-derived font.
 */
internal fun interpolateAxes(a: Map<String, Double>, b: Map<String, Double>, t: Double): Map<String, Double> {
    if (a == b) return b
    if (a.isEmpty() && b.isEmpty()) return b
    val out = LinkedHashMap<String, Double>(a.size + b.size)
    for (tag in a.keys + b.keys) {
        val from = a[tag]
        val to = b[tag]
        val value = when {
            from != null && to != null -> kotlin.math.round(lerpDouble(from, to, t) * 100.0) / 100.0
            t >= 0.5 -> to
            else -> from
        } ?: continue
        out[tag] = value
    }
    return out
}

private fun lerpNullableDouble(a: Double?, b: Double?, t: Double): Double? = when {
    a != null && b != null -> lerpDouble(a, b, t)
    t >= 0.5 -> b ?: a
    else -> a ?: b
}
