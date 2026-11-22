package dev.sebastiano.ciacola.effects.v2

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.asSkiaPath
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import dev.sebastiano.ciacola.document.HeadStyle
import dev.sebastiano.ciacola.document.LineHeads
import dev.sebastiano.ciacola.document.render.buildDashPathEffect
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.tan
import org.jetbrains.skia.Path as SkiaPath
import org.jetbrains.skia.PathBuilder
import org.jetbrains.skia.PathMeasure as SkiaPathMeasure

/**
 * Position and unit-ish tangent of an active trimmed path tip (for arrowhead placement).
 */
data class PathTipPoseV2(
    val position: Offset,
    val tangent: Offset,
)

/** A single trimmed stroked contour paired with its local arc-length start offset (for per-contour dash phase). */
data class TrimmedContourStroke(
    val path: Path,
    val strokeStartPx: Float,
)

/**
 * Result of trimming a single- or multi-contour [Path] according to [PathTrimState].
 *
 * - `StartToEnd`, `Clockwise`, `CounterClockwise`: 1 active tip at the advancing end.
 * - `EndToStart`: 1 active tip at the advancing start (tangent points along backward growth).
 * - `FromCenter`: up to 2 active tips advancing outward from the midpoint.
 *
 * [startTip] and [endTip] expose both endpoints of the active trimmed path (at `localStart` on the
 * first active contour with `-tan`, and `localEnd` on the last active contour with `+tan`).
 */
data class TrimmedLineDrawResult(
    val path: Path,
    val tips: List<PathTipPoseV2>,
    val startTip: PathTipPoseV2? = null,
    val endTip: PathTipPoseV2? = null,
    val firstStrokeStartPx: Float = 0f,
    val contours: List<TrimmedContourStroke> = emptyList(),
)

/**
 * Trims [source] (supporting multi-contour paths, `StartToEnd`, `EndToStart`, `FromCenter`,
 * `Clockwise`, and `CounterClockwise`) into a new [Path] plus active tip poses.
 */
fun trimLineDrawPath(
    source: Path,
    state: PathTrimState,
): TrimmedLineDrawResult = trimLineDrawPathWithStrokeInset(
    source = source,
    state = state,
    retractStartPx = 0f,
    retractEndPx = 0f,
)

/**
 * Trims [source] according to [state] while optionally retracting the stroked shaft inward from
 * the un-retracted start/end tips by [retractStartPx] and [retractEndPx] (used so thick strokes,
 * round caps, and dots do not poke past sharp `Filled` arrowhead points).
 */
internal fun trimLineDrawPathWithStrokeInset(
    source: Path,
    state: PathTrimState,
    retractStartPx: Float,
    retractEndPx: Float,
    includeContours: Boolean = false,
): TrimmedLineDrawResult {
    val startF = state.startFraction.coerceIn(0f, 1f)
    val endF = state.endFraction.coerceIn(startF, 1f)
    if (endF - startF <= 1e-5f || state.alpha <= 0f) {
        return TrimmedLineDrawResult(path = Path(), tips = emptyList())
    }

    val orientedContours = extractOrientedContours(source.asSkiaPath(), state.direction)
    if (orientedContours.isEmpty()) {
        return TrimmedLineDrawResult(path = Path(), tips = emptyList())
    }

    val totalLength = orientedContours.sumOf { it.length.toDouble() }.toFloat()
    if (totalLength <= 1e-5f) {
        return TrimmedLineDrawResult(path = Path(), tips = emptyList())
    }

    val globalStart = totalLength * startF
    val globalEnd = totalLength * endF
    data class ActiveSpan(
        val contour: OrientedContour,
        val localStart: Float,
        val localEnd: Float,
    )
    val activeSpans = ArrayList<ActiveSpan>(orientedContours.size)
    var firstTip: PathTipPoseV2? = null
    var lastTip: PathTipPoseV2? = null

    var cursor = 0f
    for (contour in orientedContours) {
        val contourStart = cursor
        val contourEnd = cursor + contour.length
        cursor = contourEnd

        val overlapStart = maxOf(globalStart, contourStart)
        val overlapEnd = minOf(globalEnd, contourEnd)
        if (overlapEnd - overlapStart <= 1e-5f) continue

        val localStart = (overlapStart - contourStart).coerceIn(0f, contour.length)
        val localEnd = (overlapEnd - contourStart).coerceIn(localStart, contour.length)
        activeSpans += ActiveSpan(contour, localStart, localEnd)

        SkiaPathMeasure(contour.path, false).use { measurer ->
            if (firstTip == null) {
                val p0 = measurer.getPosition(localStart)
                val t0 = measurer.getTangent(minOf(contour.length, localStart + 0.25f))
                if (p0 != null && t0 != null) {
                    firstTip = PathTipPoseV2(
                        position = Offset(p0.x, p0.y),
                        // Backward growth tip points opposite to path parameterization.
                        tangent = Offset(-t0.x, -t0.y),
                    )
                }
            }
            val p1 = measurer.getPosition(localEnd)
            val t1 = measurer.getTangent(maxOf(0f, localEnd - 0.25f))
            if (p1 != null && t1 != null) {
                lastTip = PathTipPoseV2(
                    position = Offset(p1.x, p1.y),
                    tangent = Offset(t1.x, t1.y),
                )
            }
        }
    }

    val builder = PathBuilder()
    val contourStrokes = if (includeContours) ArrayList<TrimmedContourStroke>(activeSpans.size) else null
    var firstStrokeStartPx: Float? = null
    for (idx in activeSpans.indices) {
        val span = activeSpans[idx]
        val visibleLen = (span.localEnd - span.localStart).coerceAtLeast(0f)
        val insetStart = if (idx == 0 && retractStartPx > 0f) minOf(visibleLen, retractStartPx) else 0f
        val insetEnd = if (idx == activeSpans.lastIndex && retractEndPx > 0f) minOf(visibleLen, retractEndPx) else 0f
        val strokeStart = span.localStart + insetStart
        val strokeEnd = span.localEnd - insetEnd
        if (strokeEnd - strokeStart > 1e-3f) {
            if (firstStrokeStartPx == null) {
                firstStrokeStartPx = strokeStart
            }
            val contourBuilder = if (contourStrokes != null) PathBuilder() else null
            SkiaPathMeasure(span.contour.path, false).use { measurer ->
                measurer.getSegment(strokeStart, strokeEnd, builder, true)
                if (contourBuilder != null) {
                    measurer.getSegment(strokeStart, strokeEnd, contourBuilder, true)
                }
            }
            if (contourStrokes != null && contourBuilder != null) {
                contourStrokes += TrimmedContourStroke(
                    path = contourBuilder.detach().asComposePath(),
                    strokeStartPx = strokeStart,
                )
            }
        }
    }

    val tips = buildList {
        when (state.direction) {
            LineDrawDirection.EndToStart -> firstTip?.let { add(it) }
            LineDrawDirection.FromCenter -> {
                firstTip?.let { add(it) }
                lastTip?.let { add(it) }
            }
            LineDrawDirection.StartToEnd,
            LineDrawDirection.Clockwise,
            LineDrawDirection.CounterClockwise,
            -> lastTip?.let { add(it) }
        }
    }

    return TrimmedLineDrawResult(
        path = builder.detach().asComposePath(),
        tips = tips,
        startTip = firstTip,
        endTip = lastTip,
        firstStrokeStartPx = firstStrokeStartPx ?: 0f,
        contours = contourStrokes ?: emptyList(),
    )
}

/**
 * Draws a Path-tier `lineDraw` stroke with optional [ArrowHeadStyle] or bidirectional [LineHeads]
 * placed tangent to the active trimmed tip(s).
 */
fun DrawScope.drawLineDrawPath(
    source: Path,
    state: PathTrimState,
    color: Color,
    strokeWidthPx: Float,
    cap: StrokeCap = StrokeCap.Round,
    join: StrokeJoin = StrokeJoin.Round,
    pathEffect: PathEffect? = null,
    arrowHead: ArrowHeadStyle = ArrowHeadStyle.None,
    headLengthPx: Float = 14f,
    headAngleDegrees: Float = 26.565f,
    heads: LineHeads? = null,
    dash: List<Double>? = null,
): TrimmedLineDrawResult {
    val effectiveHeadLength = headLengthPx * state.headScale
    val retractStart = if (heads != null && heads.start == HeadStyle.Filled && effectiveHeadLength > 0.5f) {
        effectiveHeadLength * 0.80f
    } else {
        0f
    }
    val retractEnd = if (heads != null && heads.end == HeadStyle.Filled && effectiveHeadLength > 0.5f) {
        effectiveHeadLength * 0.80f
    } else {
        0f
    }

    val trimmed = trimLineDrawPathWithStrokeInset(
        source = source,
        state = state,
        retractStartPx = retractStart,
        retractEndPx = retractEnd,
        includeContours = dash != null,
    )
    if ((trimmed.startTip == null && trimmed.endTip == null && trimmed.tips.isEmpty()) || state.alpha <= 0f) {
        return trimmed
    }

    val effectiveColor = color.copy(alpha = (color.alpha * state.alpha).coerceIn(0f, 1f))
    val useMultiContourDash = dash != null && trimmed.contours.size > 1
    val strokeDrawCount = if (useMultiContourDash) {
        trimmed.contours.count { !it.path.isEmpty }
    } else if (!trimmed.path.isEmpty) {
        1
    } else {
        0
    }
    val headDrawCount = if (effectiveHeadLength > 0.5f) {
        when {
            heads != null -> {
                (if (heads.start != HeadStyle.None && trimmed.startTip != null) 1 else 0) +
                    (if (heads.end != HeadStyle.None && trimmed.endTip != null) 1 else 0)
            }
            arrowHead != ArrowHeadStyle.None -> trimmed.tips.size
            else -> 0
        }
    } else {
        0
    }
    val totalPrimitives = strokeDrawCount + headDrawCount

    fun drawPrimitives(primitiveColor: Color) {
        if (useMultiContourDash) {
            for (contour in trimmed.contours) {
                if (!contour.path.isEmpty) {
                    drawPath(
                        path = contour.path,
                        color = primitiveColor,
                        style = Stroke(
                            width = strokeWidthPx,
                            cap = cap,
                            join = join,
                            pathEffect = buildDashPathEffect(dash, cap, contour.strokeStartPx),
                        ),
                    )
                }
            }
        } else if (!trimmed.path.isEmpty) {
            val effectivePathEffect = if (dash != null) {
                buildDashPathEffect(dash, cap, trimmed.firstStrokeStartPx)
            } else {
                pathEffect
            }
            drawPath(
                path = trimmed.path,
                color = primitiveColor,
                style = Stroke(
                    width = strokeWidthPx,
                    cap = cap,
                    join = join,
                    pathEffect = effectivePathEffect,
                ),
            )
        }

        if (effectiveHeadLength > 0.5f) {
            if (heads != null) {
                if (heads.start != HeadStyle.None) {
                    trimmed.startTip?.let { tip ->
                        drawArrowHeadTip(
                            tip = tip,
                            color = primitiveColor,
                            strokeWidthPx = strokeWidthPx,
                            style = heads.start.toPathArrowHeadStyle(),
                            headLengthPx = effectiveHeadLength,
                            headAngleDegrees = headAngleDegrees,
                        )
                    }
                }
                if (heads.end != HeadStyle.None) {
                    trimmed.endTip?.let { tip ->
                        drawArrowHeadTip(
                            tip = tip,
                            color = primitiveColor,
                            strokeWidthPx = strokeWidthPx,
                            style = heads.end.toPathArrowHeadStyle(),
                            headLengthPx = effectiveHeadLength,
                            headAngleDegrees = headAngleDegrees,
                        )
                    }
                }
            } else if (arrowHead != ArrowHeadStyle.None) {
                for (tip in trimmed.tips) {
                    drawArrowHeadTip(
                        tip = tip,
                        color = primitiveColor,
                        strokeWidthPx = strokeWidthPx,
                        style = arrowHead,
                        headLengthPx = effectiveHeadLength,
                        headAngleDegrees = headAngleDegrees,
                    )
                }
            }
        }
    }

    if (effectiveColor.alpha < 1f && totalPrimitives > 1) {
        val srcBounds = source.getBounds()
        val inflatePx = maxOf(strokeWidthPx, effectiveHeadLength) * 2f + 8f
        val layerBounds = Rect(
            left = minOf(0f, srcBounds.left) - inflatePx,
            top = minOf(0f, srcBounds.top) - inflatePx,
            right = maxOf(size.width, srcBounds.right) + inflatePx,
            bottom = maxOf(size.height, srcBounds.bottom) + inflatePx,
        )
        val drawColor = color.copy(alpha = 1f)
        drawIntoCanvas { canvas ->
            canvas.saveLayer(layerBounds, Paint().apply { alpha = effectiveColor.alpha })
            try {
                drawPrimitives(drawColor)
            } finally {
                canvas.restore()
            }
        }
    } else {
        drawPrimitives(effectiveColor)
    }
    return trimmed
}

private fun HeadStyle.toPathArrowHeadStyle(): ArrowHeadStyle = when (this) {
    HeadStyle.Open -> ArrowHeadStyle.Open
    HeadStyle.Filled -> ArrowHeadStyle.Filled
    HeadStyle.None -> ArrowHeadStyle.None
}

private data class OrientedContour(
    val path: SkiaPath,
    val length: Float,
)

private fun extractOrientedContours(
    source: SkiaPath,
    direction: LineDrawDirection,
): List<OrientedContour> {
    val contours = mutableListOf<OrientedContour>()
    val needsOrientation = direction == LineDrawDirection.Clockwise || direction == LineDrawDirection.CounterClockwise
    val wantClockwise = direction == LineDrawDirection.Clockwise

    SkiaPathMeasure(source, false).use { measurer ->
        do {
            val len = measurer.length
            if (len <= 1e-5f) continue

            val shouldReverse = if (needsOrientation) {
                val signedArea = estimateContourSignedArea(measurer, len)
                // In Y-down coordinates, positive shoelace sum corresponds to clockwise winding.
                if (wantClockwise) signedArea < 0.0 else signedArea > 0.0
            } else {
                false
            }

            val contourPath = if (shouldReverse) {
                buildReversedContour(measurer, len, measurer.isClosed)
            } else {
                val builder = PathBuilder()
                measurer.getSegment(0f, len, builder, true)
                builder.detach()
            }
            val measuredLen = SkiaPathMeasure(contourPath, false).use { it.length }
            if (measuredLen > 1e-5f) {
                contours += OrientedContour(contourPath, measuredLen)
            }
        } while (measurer.nextContour())
    }
    return contours
}

private fun estimateContourSignedArea(measurer: SkiaPathMeasure, length: Float): Double {
    val samples = ceil((length / 4f).toDouble()).toInt().coerceIn(16, 256)
    val pts = ArrayList<Offset>(samples)
    for (i in 0 until samples) {
        val d = length * i.toFloat() / samples.toFloat()
        val p = measurer.getPosition(d) ?: continue
        pts += Offset(p.x, p.y)
    }
    if (pts.size < 3) return 0.0
    var sum = 0.0
    for (i in pts.indices) {
        val a = pts[i]
        val b = pts[(i + 1) % pts.size]
        sum += a.x.toDouble() * b.y.toDouble() - b.x.toDouble() * a.y.toDouble()
    }
    return sum * 0.5
}

private fun buildReversedContour(
    measurer: SkiaPathMeasure,
    length: Float,
    isClosed: Boolean,
): SkiaPath {
    val steps = ceil((length / 2f).toDouble()).toInt().coerceIn(32, 1024)
    val builder = PathBuilder()
    var started = false
    for (i in steps downTo 0) {
        val d = length * i.toFloat() / steps.toFloat()
        val p = measurer.getPosition(d) ?: continue
        if (!started) {
            builder.moveTo(p.x, p.y)
            started = true
        } else {
            builder.lineTo(p.x, p.y)
        }
    }
    if (isClosed && started) {
        builder.closePath()
    }
    return builder.detach()
}

internal fun DrawScope.drawArrowHeadTip(
    tip: PathTipPoseV2,
    color: Color,
    strokeWidthPx: Float,
    style: ArrowHeadStyle,
    headLengthPx: Float,
    headAngleDegrees: Float = 26.565f,
) {
    val dirLen = tip.tangent.getDistance()
    if (dirLen <= 1e-4f) return
    val ux = tip.tangent.x / dirLen
    val uy = tip.tangent.y / dirLen
    val tanHalf = tan(headAngleDegrees * (PI.toFloat() / 180f))
    val halfBase = headLengthPx * tanHalf
    val baseX = tip.position.x - ux * headLengthPx
    val baseY = tip.position.y - uy * headLengthPx
    // Perpendicular (-uy, ux) produces exact axial=headLengthPx and baseWidth=2*halfBase.
    val wing1 = Offset(x = baseX - uy * halfBase, y = baseY + ux * halfBase)
    val wing2 = Offset(x = baseX + uy * halfBase, y = baseY - ux * halfBase)

    val headPath = Path().apply {
        moveTo(wing1.x, wing1.y)
        lineTo(tip.position.x, tip.position.y)
        lineTo(wing2.x, wing2.y)
        if (style == ArrowHeadStyle.Filled) close()
    }
    if (style == ArrowHeadStyle.Filled) {
        drawPath(headPath, color = color)
    } else {
        drawPath(
            path = headPath,
            color = color,
            style = Stroke(width = strokeWidthPx, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}
