package dev.sebastiano.ciacola.effects

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.modifier.modifierLocalConsumer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.bnorm.storyboard.layout.decorated.Decoration
import dev.bnorm.storyboard.layout.decorated.DecorationLayout
import dev.bnorm.storyboard.layout.decorated.DecorationScope
import dev.sebastiano.ciacola.inspector.LocalEffectiveBackgroundColor
import dev.sebastiano.ciacola.inspector.LocalStageDragPreview
import dev.sebastiano.ciacola.inspector.LocalStageParentElementId
import dev.sebastiano.ciacola.inspector.ModifierLocalStageAlpha
import dev.sebastiano.ciacola.review.reviewTarget
import dev.sebastiano.ciacola.theme.LocalDeckTheme
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

@Composable
fun DottedLeaderRow(
    leading: String,
    trailing: String,
    modifier: Modifier = Modifier,
    highlightTrailing: Boolean = false,
    trailingModifier: Modifier = Modifier,
    trailingStyle: TextStyle = LocalDeckTheme.current.typography.code,
    // 0..1 blend of the trailing text towards the accent, read at draw time; overrides
    // [highlightTrailing] when set.
    highlightProgress: (() -> Float)? = null,
    elementId: String? = null,
    leadingStyle: TextStyle = LocalDeckTheme.current.typography.bodyEmphasis,
) {
    val theme = LocalDeckTheme.current
    val effectiveBg = effectiveBackgroundColor()
    val dragPreview = LocalStageDragPreview.current
    val parentElementId = LocalStageParentElementId.current
    val childParentId = elementId ?: parentElementId
    val trailingId = elementId?.let { "$it:trailing" }
    val resolvedTrailingFontSizeSp = if (trailingStyle.fontSize.isSp) trailingStyle.fontSize.value.coerceAtLeast(6f) else 16f
    var trailingCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var trailingLayout by remember { mutableStateOf<TextLayoutResult?>(null) }
    var trailingAlphaProvider by remember { mutableStateOf<() -> Float>({ 1f }) }

    val rowBoxModifier = if (elementId != null) {
        modifier.editableBox(
            elementId = elementId,
            label = "$leading → $trailing",
            kind = "DottedLeaderRow",
        )
    } else {
        modifier.reviewTarget(
            kind = "DottedLeaderRow",
            label = "$leading → $trailing",
            textSnippet = "$leading … $trailing",
        )
    }

    val trailingOffsetMod = if (trailingId != null) {
        Modifier.offset {
            val drag = dragPreview?.offsetFor(trailingId) ?: DpOffset.Zero
            IntOffset(
                x = drag.x.roundToPx(),
                y = drag.y.roundToPx(),
            )
        }
    } else {
        Modifier
    }
    val effectiveTrailingColor = trailingHighlightColor(
        base = theme.colors.foreground,
        accent = theme.colors.accent,
        progress = highlightProgress?.invoke() ?: if (highlightTrailing) 1f else 0f,
    )
    CompositionLocalProvider(LocalStageParentElementId provides childParentId) {
        RecordStageTextInspection(
            elementId = trailingId,
            kind = "LeaderCode",
            label = trailing,
            defaultText = trailing,
            resolvedText = trailing,
            defaultTextColor = theme.colors.foreground,
            effectiveForeground = effectiveTrailingColor,
            effectiveBackground = effectiveBg,
            defaultFontSizeSp = if (trailingStyle.fontSize.isSp) trailingStyle.fontSize.value else 16f,
            effectiveFontSizeSp = resolvedTrailingFontSizeSp,
            defaultFontWeight = trailingStyle.fontWeight?.weight ?: 400,
            coords = trailingCoords,
            layoutResult = trailingLayout,
            alphaProvider = { trailingAlphaProvider() },
        )

        Layout(
            modifier = rowBoxModifier
                .modifierLocalConsumer { trailingAlphaProvider = ModifierLocalStageAlpha.current }
                .fillMaxWidth(),
            content = {
                if (elementId != null) {
                    DeckEditableText(
                        elementId = "$elementId:leading",
                        text = leading,
                        label = leading,
                        kind = "LeaderLabel",
                        style = leadingStyle,
                        color = theme.colors.foreground,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else {
                    Text(
                        text = leading,
                        style = leadingStyle,
                        color = theme.colors.foreground,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Canvas(Modifier.widthIn(min = 36.dp).height(4.dp)) {
                    val dotEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 8.dp.toPx()), 0f)
                    drawLine(
                        color = theme.colors.muted.copy(alpha = 0.65f),
                        start = Offset(0f, size.height / 2f),
                        end = Offset(size.width, size.height / 2f),
                        strokeWidth = 2.5.dp.toPx(),
                        cap = StrokeCap.Round,
                        pathEffect = dotEffect,
                    )
                }
                BasicText(
                    text = trailing,
                    style = trailingStyle,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    color = {
                        trailingHighlightColor(
                            base = theme.colors.foreground,
                            accent = theme.colors.accent,
                            progress = highlightProgress?.invoke() ?: if (highlightTrailing) 1f else 0f,
                        )
                    },
                    onTextLayout = { trailingLayout = it },
                    modifier = trailingModifier
                        .then(trailingOffsetMod)
                        .onPlaced { trailingCoords = it },
                )
            },
        ) { measurables, constraints ->
            val preferredGapPx = 20.dp.roundToPx()
            val minGapPx = 10.dp.roundToPx()
            val minDotsPx = 36.dp.roundToPx()
            val tightDotsPx = 20.dp.roundToPx()
            val preferredSeparatorPx = minDotsPx + preferredGapPx * 2
            val tightSeparatorPx = tightDotsPx + minGapPx * 2
            val maxW = constraints.maxWidth
            val loose = constraints.copy(minWidth = 0, minHeight = 0)
            val leadingIntrinsicW = measurables[0].maxIntrinsicWidth(loose.maxHeight)
            val trailingIntrinsicW = measurables[2].maxIntrinsicWidth(loose.maxHeight)
            val bothFitUntruncated = leadingIntrinsicW + trailingIntrinsicW + tightSeparatorPx <= maxW

            val leadingPlaceable: androidx.compose.ui.layout.Placeable
            val trailingPlaceable: androidx.compose.ui.layout.Placeable
            val gapPx: Int
            val dotsW: Int
            if (bothFitUntruncated) {
                leadingPlaceable = measurables[0].measure(loose.copy(maxWidth = maxW))
                trailingPlaceable = measurables[2].measure(loose.copy(maxWidth = maxW))
                val remaining = (maxW - leadingPlaceable.width - trailingPlaceable.width).coerceAtLeast(0)
                gapPx = ((remaining - minDotsPx) / 2).coerceIn(minGapPx.coerceAtMost(remaining / 2), preferredGapPx)
                dotsW = (remaining - gapPx * 2).coerceAtLeast(0)
            } else {
                // Reserve up to 35% of row width for leading before measuring trailing so a long
                // trailing code token can never crush the leading label into vertical columns,
                // while preserving the full >=36dp dots + 20dp gap leader separator.
                val minLeadingReserve = (maxW * 0.35f).roundToInt()
                val reservedLeadingW = minOf(leadingIntrinsicW, minLeadingReserve)
                val maxTrailingW = (maxW - reservedLeadingW - preferredSeparatorPx).coerceAtLeast(0)
                trailingPlaceable = measurables[2].measure(loose.copy(maxWidth = maxTrailingW))
                val maxLeadingW = (maxW - trailingPlaceable.width - preferredSeparatorPx).coerceAtLeast(0)
                leadingPlaceable = measurables[0].measure(loose.copy(maxWidth = maxLeadingW))
                gapPx = preferredGapPx
                dotsW = (maxW - leadingPlaceable.width - trailingPlaceable.width - gapPx * 2)
                    .coerceAtLeast(minDotsPx.coerceAtMost((maxW - leadingPlaceable.width - trailingPlaceable.width).coerceAtLeast(0)))
            }
            val dotsPlaceable = measurables[1].measure(
                Constraints(minWidth = dotsW, maxWidth = dotsW, minHeight = 0, maxHeight = loose.maxHeight),
            )
            val rowHeight = maxOf(leadingPlaceable.height, dotsPlaceable.height, trailingPlaceable.height)
                .coerceIn(constraints.minHeight, constraints.maxHeight)
            val rowWidth = maxW.coerceIn(constraints.minWidth, constraints.maxWidth)
            layout(rowWidth, rowHeight) {
                leadingPlaceable.placeRelative(0, (rowHeight - leadingPlaceable.height) / 2)
                dotsPlaceable.placeRelative(leadingPlaceable.width + gapPx, (rowHeight - dotsPlaceable.height) / 2)
                trailingPlaceable.placeRelative(
                    (rowWidth - trailingPlaceable.width).coerceAtLeast(0),
                    (rowHeight - trailingPlaceable.height) / 2,
                )
            }
        }
    }
}

fun trailingHighlightColor(base: Color, accent: Color, progress: Float): Color =
    lerp(base, accent, progress.coerceIn(0f, 1f))

/**
 * Region of the lens (in lens-local px) that may show magnified specimen, given the target [src]
 * rect in specimen coordinates, the lens centre, and the zoom: the target inflated by [padPx], so
 * neighbouring content (e.g. the stamp border right under the target row) never bleeds into the rim.
 */
fun loupeSampleClip(src: Rect, lensCenter: Offset, zoom: Float, padPx: Float): Rect {
    val padded = src.inflate(padPx)
    return Rect(
        left = lensCenter.x + (padded.left - src.center.x) * zoom,
        top = lensCenter.y + (padded.top - src.center.y) * zoom,
        right = lensCenter.x + (padded.right - src.center.x) * zoom,
        bottom = lensCenter.y + (padded.bottom - src.center.y) * zoom,
    )
}

/** Endpoint pair for one outer tangent ray connecting the source pill to the circular lens. */
data class LoupeTangentSegment(
    val pillPoint: Offset,
    val lensPoint: Offset,
)

/**
 * Computes the two outer supporting bitangents between a rounded rectangle ([pillRect] with
 * [pillRadius]) and the circular loupe lens ([lensCenter], [lensRadius]).
 *
 * Returns `null` when the lens encloses or intersects the pill so closely that no outer tangent
 * pair exists.
 */
fun computeLoupeOuterTangents(
    pillRect: Rect,
    pillRadius: Float,
    lensCenter: Offset,
    lensRadius: Float,
): Pair<LoupeTangentSegment, LoupeTangentSegment>? {
    if (pillRect.width <= 0.5f || pillRect.height <= 0.5f || lensRadius <= 0.5f) return null
    val r = pillRadius.coerceIn(0f, minOf(pillRect.width, pillRect.height) / 2f)
    val corners = arrayOf(
        Offset(pillRect.left + r, pillRect.top + r),
        Offset(pillRect.right - r, pillRect.top + r),
        Offset(pillRect.right - r, pillRect.bottom - r),
        Offset(pillRect.left + r, pillRect.bottom - r),
    )
    val axis = lensCenter - pillRect.center
    var positiveSide: LoupeTangentSegment? = null
    var negativeSide: LoupeTangentSegment? = null

    for (corner in corners) {
        val dx = lensCenter.x - corner.x
        val dy = lensCenter.y - corner.y
        val dist = hypot(dx, dy)
        if (dist <= abs(lensRadius - r) + 1e-3f) continue
        val baseAngle = atan2(dy, dx)
        val cosAlpha = ((r - lensRadius) / dist).coerceIn(-1f, 1f)
        val delta = acos(cosAlpha)
        for (sign in intArrayOf(-1, 1)) {
            val theta = baseAngle + sign * delta
            val nx = cos(theta)
            val ny = sin(theta)
            val support = corner.x * nx + corner.y * ny
            var isOuterSupport = true
            for (other in corners) {
                if (other.x * nx + other.y * ny > support + 0.5f) {
                    isOuterSupport = false
                    break
                }
            }
            if (!isOuterSupport) continue
            val segment = LoupeTangentSegment(
                pillPoint = Offset(corner.x + nx * r, corner.y + ny * r),
                lensPoint = Offset(lensCenter.x + nx * lensRadius, lensCenter.y + ny * lensRadius),
            )
            val sideCross = axis.x * ny - axis.y * nx
            if (sideCross >= 0f) {
                if (positiveSide == null) positiveSide = segment
            } else {
                if (negativeSide == null) negativeSide = segment
            }
        }
    }
    val first = negativeSide ?: return null
    val second = positiveSide ?: return null
    return first to second
}

/**
 * Clamps [requestedZoom] so that the padded [targetSize] fits cleanly inside a circular lens of
 * [usableLensRadiusPx] without slicing leading or trailing characters off the target.
 */
fun computeFittedLoupeZoom(
    targetSize: Size,
    requestedZoom: Float,
    usableLensRadiusPx: Float,
    padXPx: Float,
    padYPx: Float,
): Float {
    if (usableLensRadiusPx <= 1f || targetSize.width <= 0.5f || targetSize.height <= 0.5f) {
        return requestedZoom
    }
    val halfW = targetSize.width / 2f + padXPx
    val halfH = targetSize.height / 2f + padYPx
    val halfDiag = hypot(halfW, halfH)
    if (halfDiag <= 0.5f) return requestedZoom
    val maxFitZoom = usableLensRadiusPx / halfDiag
    return minOf(requestedZoom, maxFitZoom).coerceAtLeast(0.5f)
}

private val LoupeIrisEasing = CubicBezierEasing(0.32f, 0f, 0.24f, 1f)

/**
 * Snapshot of [MagnifierLoupeStage]'s active geometry in stage-root coordinates, exposed to
 * geometric layout tests via [LocalLoupeGeometryObserver].
 */
data class LoupeStageGeometrySnapshot(
    val targetKey: Any,
    val progress: Float,
    val stageOrigin: Offset,
    val pillRectInStage: Rect,
    val pillRadius: Float,
    val lensCenterInStage: Offset,
    val lensRadius: Float,
    val tangentsInStage: Pair<LoupeTangentSegment, LoupeTangentSegment>?,
    val resolvedLensBackground: Color,
)

/** Optional test observer notified whenever [MagnifierLoupeStage] draws active loupe geometry. */
val LocalLoupeGeometryObserver = compositionLocalOf<((LoupeStageGeometrySnapshot) -> Unit)?> { null }

@Composable
fun MagnifierLoupeStage(
    targetKey: Any,
    progress: () -> Float,
    modifier: Modifier = Modifier,
    loupeAlignment: Alignment = Alignment.CenterEnd,
    loupeDiameter: Dp = LocalDeckTheme.current.styles.loupe.diameter,
    loupePadding: PaddingValues = PaddingValues(end = 24.dp),
    decorations: List<Decoration> = emptyList(),
    zoom: Float = LocalDeckTheme.current.styles.loupe.defaultZoom,
    lensBackgroundColor: Color = Color.Unspecified,
    content: @Composable DecorationScope.() -> Unit,
) {
    val theme = LocalDeckTheme.current
    val effectiveBg = effectiveBackgroundColor()
    val resolvedLensBg = lensBackgroundColor.takeOrElse { effectiveBg }
    val rootHolder = dev.sebastiano.ciacola.inspector.LocalStageRootCoordinates.current
    val geometryObserver = LocalLoupeGeometryObserver.current
    val loupeStyle = theme.styles.loupe
    val specimenLayer = rememberGraphicsLayer()
    var stageCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var targetRect by remember { mutableStateOf<Rect?>(null) }
    var loupeRect by remember { mutableStateOf<Rect?>(null) }

    val targetCaptureDecoration = remember(targetKey) {
        Decoration { container ->
            targetRect = container.getBoundingBox(targetKey)
        }
    }

    Box(
        modifier = modifier.onPlaced { stageCoords = it },
    ) {
        // Record only the slide content (inside DecorationLayout) into specimenLayer so overlays
        // like leader arrowheads, callout outlines, and strikeouts are never magnified inside the lens.
        DecorationLayout(
            modifier = Modifier.fillMaxSize(),
            decorations = decorations + targetCaptureDecoration,
        ) {
            val decorationScope = this
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .drawWithContent {
                        specimenLayer.record {
                            drawRect(color = effectiveBg)
                            this@drawWithContent.drawContent()
                        }
                        drawLayer(specimenLayer)
                    },
            ) {
                with(decorationScope) {
                    content()
                }
            }
        }

        // Dotted connector tangents + tight pill source frame + iris-clipped live magnified loupe
        Canvas(Modifier.fillMaxSize()) {
            val p = progress().coerceIn(0f, 1f)
            val src = targetRect ?: return@Canvas
            val dst = loupeRect ?: return@Canvas
            if (p <= 0f) return@Canvas

            val edgeAlpha = (p / 0.18f).coerceIn(0f, 1f)
            val iris = LoupeIrisEasing.transform(p)
            val padXPx = 8.dp.toPx()
            val padYPx = 6.dp.toPx()
            val halfW = (src.width / 2f + padXPx) * iris
            val halfH = (src.height / 2f + padYPx) * iris
            val pillRect = Rect(
                left = src.center.x - halfW,
                top = src.center.y - halfH,
                right = src.center.x + halfW,
                bottom = src.center.y + halfH,
            )
            val pillRadius = (pillRect.height / 2f).coerceAtMost(14.dp.toPx())
            val dstRadius = (dst.minDimension / 2f) * iris

            val dash = PathEffect.dashPathEffect(
                floatArrayOf(loupeStyle.connectorDashOn.toPx(), loupeStyle.connectorDashOff.toPx()),
                0f,
            )
            val strokeColor = theme.colors.muted.copy(alpha = theme.colors.muted.alpha * edgeAlpha)
            val connectorStroke = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, pathEffect = dash)
            val tangents = computeLoupeOuterTangents(
                pillRect = pillRect,
                pillRadius = pillRadius,
                lensCenter = dst.center,
                lensRadius = dstRadius,
            )
            if (geometryObserver != null) {
                val stageOrigin = resolveStageBounds(stageCoords, rootHolder)?.topLeft ?: Offset.Zero
                val stageTangents = tangents?.let { (a, b) ->
                    LoupeTangentSegment(
                        pillPoint = a.pillPoint + stageOrigin,
                        lensPoint = a.lensPoint + stageOrigin,
                    ) to LoupeTangentSegment(
                        pillPoint = b.pillPoint + stageOrigin,
                        lensPoint = b.lensPoint + stageOrigin,
                    )
                }
                geometryObserver(
                    LoupeStageGeometrySnapshot(
                        targetKey = targetKey,
                        progress = p,
                        stageOrigin = stageOrigin,
                        pillRectInStage = pillRect.translate(stageOrigin),
                        pillRadius = pillRadius,
                        lensCenterInStage = dst.center + stageOrigin,
                        lensRadius = dstRadius,
                        tangentsInStage = stageTangents,
                        resolvedLensBackground = resolvedLensBg,
                    )
                )
            }
            if (tangents != null) {
                val (rayA, rayB) = tangents
                val pathA = Path().apply {
                    moveTo(rayA.pillPoint.x, rayA.pillPoint.y)
                    lineTo(rayA.lensPoint.x, rayA.lensPoint.y)
                }
                val pathB = Path().apply {
                    moveTo(rayB.pillPoint.x, rayB.pillPoint.y)
                    lineTo(rayB.lensPoint.x, rayB.lensPoint.y)
                }
                val trimmedA = Path()
                val trimmedB = Path()
                trimPath(pathA, iris, trimmedA)
                trimPath(pathB, iris, trimmedB)
                drawPath(trimmedA, color = strokeColor, style = connectorStroke)
                drawPath(trimmedB, color = strokeColor, style = connectorStroke)
            }
            drawRoundRect(
                color = strokeColor,
                topLeft = pillRect.topLeft,
                size = Size(pillRect.width, pillRect.height),
                cornerRadius = CornerRadius(pillRadius, pillRadius),
                style = Stroke(width = 2.dp.toPx()),
            )
        }

        Box(
            modifier = Modifier
                .align(loupeAlignment)
                .padding(loupePadding)
                .size(loupeDiameter)
                .testTag("ciacola-magnifier-loupe")
                .onPlaced { coords ->
                    stageCoords?.let { root ->
                        loupeRect = root.localBoundingBoxOf(coords)
                    }
                }
                .graphicsLayer {
                    val p = progress().coerceIn(0f, 1f)
                    val a = (p / 0.18f).coerceIn(0f, 1f)
                    alpha = a
                    compositingStrategy = if (a < 1f) CompositingStrategy.Offscreen else CompositingStrategy.Auto
                }
                .drawWithContent {
                    val p = progress().coerceIn(0f, 1f)
                    val src = targetRect ?: return@drawWithContent
                    if (p <= 0f) return@drawWithContent

                    val edgeAlpha = (p / 0.18f).coerceIn(0f, 1f)
                    val iris = LoupeIrisEasing.transform(p)
                    val radius = (size.minDimension / 2f) * iris
                    if (radius <= 0.5f) return@drawWithContent
                    val center = Offset(size.width / 2f, size.height / 2f)
                    val circle = Path().apply {
                        addOval(Rect(center = center, radius = radius))
                    }
                    val padXPx = 5.dp.toPx()
                    val padYPx = 4.dp.toPx()
                    val usableRadius = (radius - loupeStyle.ringWidth.toPx() - 3.dp.toPx()).coerceAtLeast(1f)
                    val effectiveZoom = computeFittedLoupeZoom(
                        targetSize = src.size,
                        requestedZoom = zoom,
                        usableLensRadiusPx = usableRadius,
                        padXPx = padXPx,
                        padYPx = padYPx,
                    )
                    clipPath(circle) {
                        drawRect(color = effectiveBg)
                        if (lensBackgroundColor != Color.Unspecified) {
                            drawRect(color = lensBackgroundColor)
                        }
                        val sample = Rect(
                            left = center.x + (src.left - padXPx - src.center.x) * effectiveZoom,
                            top = center.y + (src.top - padYPx - src.center.y) * effectiveZoom,
                            right = center.x + (src.right + padXPx - src.center.x) * effectiveZoom,
                            bottom = center.y + (src.bottom + padYPx - src.center.y) * effectiveZoom,
                        )
                        clipRect(sample.left, sample.top, sample.right, sample.bottom) {
                            withTransform({
                                // Translate so src.center inside specimenLayer lands at loupe center, scaled by effectiveZoom
                                translate(
                                    left = center.x - src.center.x * effectiveZoom,
                                    top = center.y - src.center.y * effectiveZoom,
                                )
                                scale(scaleX = effectiveZoom, scaleY = effectiveZoom, pivot = Offset.Zero)
                            }) {
                                drawLayer(specimenLayer)
                            }
                        }
                    }
                    drawCircle(
                        color = theme.colors.accent.copy(alpha = theme.colors.accent.alpha * edgeAlpha),
                        radius = radius,
                        center = center,
                        style = Stroke(width = loupeStyle.ringWidth.toPx()),
                    )
                },
        )
    }
}
