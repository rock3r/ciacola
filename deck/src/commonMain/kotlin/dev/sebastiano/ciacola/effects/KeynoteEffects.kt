package dev.sebastiano.ciacola.effects

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.Transition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toIntSize
import androidx.compose.ui.unit.toOffset
import dev.bnorm.storyboard.layout.decorated.Decoration
import dev.sebastiano.ciacola.theme.LocalDeckTheme
import dev.sebastiano.ciacola.timeline.HarvestedTrackRecord
import dev.sebastiano.ciacola.timeline.LocalActiveSceneTimeline
import dev.sebastiano.ciacola.timeline.LocalTimelineHarvestCollector
import dev.sebastiano.ciacola.timeline.LocalTimelineOverrides
import dev.sebastiano.ciacola.timeline.TrackCategory
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

enum class SlideDirection {
    Up,
    Down,
    Left,
    Right,
}

enum class WipeDirection {
    StartToEnd,
    EndToStart,
    TopToBottom,
    BottomToTop,
}

enum class ArrowHead {
    None,
    Open,
    Filled,
}

data class PathTipPose(
    val position: Offset,
    val tangent: Offset,
)

internal expect fun trimMultiContourPath(
    source: Path,
    progress: Float,
    destination: Path,
): PathTipPose?

fun computeStaggerItemProgress(
    overallProgress: Float,
    index: Int,
    count: Int,
    itemDurationMs: Int,
    staggerStepMs: Int,
    reverseOrder: Boolean = false,
): Float {
    val clamped = overallProgress.coerceIn(0f, 1f)
    if (count <= 1 || staggerStepMs <= 0 || itemDurationMs <= 0) return clamped
    val safeIndex = index.coerceIn(0, count - 1)
    val totalMs = (itemDurationMs + (count - 1) * staggerStepMs).toFloat()

    return if (!reverseOrder) {
        val startMs = safeIndex * staggerStepMs.toFloat()
        ((clamped * totalMs - startMs) / itemDurationMs.toFloat()).coerceIn(0f, 1f)
    } else {
        val exitOrder = (count - 1) - safeIndex
        val exitFraction = 1f - clamped
        val exitElapsedMs = exitFraction * totalMs
        val itemExit = ((exitElapsedMs - exitOrder * staggerStepMs.toFloat()) / itemDurationMs.toFloat())
            .coerceIn(0f, 1f)
        (1f - itemExit).coerceIn(0f, 1f)
    }
}

@Stable
class StaggerGroupState internal constructor(
    private val overallProgress: State<Float>,
    val count: Int,
    private val itemDurationMs: Int,
    private val staggerStepMs: Int,
    private val isReversing: () -> Boolean,
) {
    fun progressFor(index: Int): Float = computeStaggerItemProgress(
        overallProgress = overallProgress.value,
        index = index,
        count = count,
        itemDurationMs = itemDurationMs,
        staggerStepMs = staggerStepMs,
        reverseOrder = isReversing(),
    )
}

@Composable
fun <S> Transition<S>.animateBuildProgress(
    label: String = "keynote-build",
    trackId: String? = null,
    category: TrackCategory = TrackCategory.Build,
    defaultTriggerBeat: Int = 1,
    durationMillis: Int = LocalDeckTheme.current.motion.fadeMoveDurationMs,
    easing: Easing = LocalDeckTheme.current.motion.standardEasing,
    visible: (S) -> Boolean,
): State<Float> {
    val sceneCtx = LocalActiveSceneTimeline.current
    val resolvedTrackId = trackId ?: if (sceneCtx != null) "s${sceneCtx.sceneIndex}:$label" else label
    val override = LocalTimelineOverrides.current.tracks[resolvedTrackId]
    val collector = LocalTimelineHarvestCollector.current

    val effectiveDuration = override?.durationMs ?: durationMillis
    val effectiveDelay = override?.delayMs ?: 0
    val effectiveTriggerBeat = override?.enterBeat ?: defaultTriggerBeat
    val effectiveExitBeat = override?.exitBeat

    SideEffect {
        if (sceneCtx != null) {
            collector?.record(
                HarvestedTrackRecord(
                    trackId = resolvedTrackId,
                    label = label,
                    category = category,
                    sceneIndex = sceneCtx.sceneIndex,
                    triggerBeat = effectiveTriggerBeat.coerceIn(0, (sceneCtx.totalBeats - 1).coerceAtLeast(0)),
                    durationMs = effectiveDuration,
                    delayMs = effectiveDelay,
                )
            )
        }
    }

    return animateFloat(
        transitionSpec = {
            if (override?.curve != null) {
                override.curve.toAnimationSpec(effectiveDuration, effectiveDelay)
            } else {
                tween(
                    durationMillis = effectiveDuration,
                    delayMillis = effectiveDelay,
                    easing = easing,
                )
            }
        },
        label = label,
    ) { state ->
        val isVisible = if (sceneCtx != null && (override?.enterBeat != null || effectiveExitBeat != null)) {
            val beatIdx = sceneCtx.frameBeatIndexOf(state)
            val afterEnter = beatIdx >= effectiveTriggerBeat
            val beforeExit = effectiveExitBeat == null || beatIdx < effectiveExitBeat
            afterEnter && beforeExit
        } else {
            visible(state)
        }
        if (isVisible) 1f else 0f
    }
}

@Composable
fun <S> Transition<S>.animateStaggerGroup(
    count: Int,
    label: String = "keynote-stagger",
    trackId: String? = null,
    defaultTriggerBeat: Int = 1,
    itemDurationMs: Int = LocalDeckTheme.current.motion.fadeMoveDurationMs,
    staggerStepMs: Int = LocalDeckTheme.current.motion.staggerStepMs,
    easing: Easing = LocalDeckTheme.current.motion.standardEasing,
    visible: (S) -> Boolean,
): StaggerGroupState {
    val safeCount = count.coerceAtLeast(1)
    val sceneCtx = LocalActiveSceneTimeline.current
    val resolvedTrackId = trackId ?: if (sceneCtx != null) "s${sceneCtx.sceneIndex}:$label" else label
    val override = LocalTimelineOverrides.current.tracks[resolvedTrackId]
    val collector = LocalTimelineHarvestCollector.current

    val effectiveItemDuration = override?.durationMs ?: itemDurationMs
    val effectiveStep = override?.staggerStepMs ?: staggerStepMs
    val effectiveDelay = override?.delayMs ?: 0
    val effectiveTriggerBeat = override?.enterBeat ?: defaultTriggerBeat
    val totalDurationMs = effectiveItemDuration + (safeCount - 1) * effectiveStep

    SideEffect {
        if (sceneCtx != null) {
            collector?.record(
                HarvestedTrackRecord(
                    trackId = resolvedTrackId,
                    label = label,
                    category = TrackCategory.StaggerGroup,
                    sceneIndex = sceneCtx.sceneIndex,
                    triggerBeat = effectiveTriggerBeat.coerceIn(0, (sceneCtx.totalBeats - 1).coerceAtLeast(0)),
                    durationMs = totalDurationMs,
                    delayMs = effectiveDelay,
                    staggerCount = safeCount,
                    staggerStepMs = effectiveStep,
                )
            )
        }
    }

    fun isVisibleAt(state: S): Boolean {
        return if (sceneCtx != null && override?.enterBeat != null) {
            sceneCtx.frameBeatIndexOf(state) >= effectiveTriggerBeat
        } else {
            visible(state)
        }
    }

    val progress = animateFloat(
        transitionSpec = {
            if (override?.curve != null) {
                override.curve.toAnimationSpec(totalDurationMs, effectiveDelay)
            } else {
                tween(
                    durationMillis = totalDurationMs,
                    delayMillis = effectiveDelay,
                    easing = easing,
                )
            }
        },
        label = label,
    ) { state ->
        if (isVisibleAt(state)) 1f else 0f
    }
    return remember(this, progress, safeCount, effectiveItemDuration, effectiveStep, override) {
        StaggerGroupState(
            overallProgress = progress,
            count = safeCount,
            itemDurationMs = effectiveItemDuration,
            staggerStepMs = effectiveStep,
            isReversing = { isVisibleAt(currentState) && !isVisibleAt(targetState) },
        )
    }
}

fun trimPath(
    source: Path,
    progress: Float,
    destination: Path = Path(),
): PathTipPose? = trimMultiContourPath(source, progress, destination)

fun Modifier.keynoteDissolve(progress: () -> Float): Modifier = graphicsLayer {
    alpha = progress().coerceIn(0f, 1f)
}

@Composable
fun Modifier.keynoteFadeMoveTracked(
    trackId: String,
    progress: () -> Float,
    direction: SlideDirection = SlideDirection.Up,
    distance: Dp = 24.dp,
    easing: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f),
): Modifier {
    val spatial = LocalTimelineOverrides.current.tracks[trackId]?.spatial
    return if (spatial == null) {
        keynoteFadeMove(progress, direction, distance, easing)
    } else {
        graphicsLayer {
            val raw = progress().coerceIn(0f, 1f)
            val eased = easing.transform(raw)
            alpha = raw
            translationX = (1f - eased) * spatial.offsetXDp.dp.toPx()
            translationY = (1f - eased) * spatial.offsetYDp.dp.toPx()
        }
    }
}

fun Modifier.keynoteFadeMove(
    progress: () -> Float,
    direction: SlideDirection = SlideDirection.Up,
    distance: Dp = 24.dp,
    easing: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f),
): Modifier = graphicsLayer {
    val raw = progress().coerceIn(0f, 1f)
    val eased = easing.transform(raw)
    val remainingPx = (1f - eased) * distance.toPx()
    alpha = raw
    when (direction) {
        SlideDirection.Up -> translationY = remainingPx
        SlideDirection.Down -> translationY = -remainingPx
        SlideDirection.Left -> translationX = remainingPx
        SlideDirection.Right -> translationX = -remainingPx
    }
}

fun Modifier.keynoteFromDarkness(
    progress: () -> Float,
    initialScale: Float = 0.88f,
    transformOrigin: TransformOrigin = TransformOrigin.Center,
    easing: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f),
): Modifier = graphicsLayer {
    val raw = progress().coerceIn(0f, 1f)
    val eased = easing.transform(raw)
    val s = initialScale + (1f - initialScale) * eased
    alpha = raw
    scaleX = s
    scaleY = s
    this.transformOrigin = transformOrigin
}

fun Modifier.keynotePop(
    progress: () -> Float,
    initialScale: Float = 0.72f,
    overshootScale: Float = 1.06f,
): Modifier = graphicsLayer {
    val p = progress().coerceIn(0f, 1.25f)
    val scale = when {
        p <= 0f -> initialScale
        p < 0.72f -> {
            val t = p / 0.72f
            initialScale + (overshootScale - initialScale) * t
        }
        p <= 1f -> {
            val t = (p - 0.72f) / 0.28f
            overshootScale + (1f - overshootScale) * t
        }
        else -> 1f
    }
    alpha = (p / 0.35f).coerceIn(0f, 1f)
    scaleX = scale
    scaleY = scale
}

fun Modifier.keynoteWipe(
    progress: () -> Float,
    direction: WipeDirection = WipeDirection.StartToEnd,
): Modifier = drawWithContent {
    val p = progress().coerceIn(0f, 1f)
    if (p <= 0f) return@drawWithContent
    val left = when (direction) {
        WipeDirection.EndToStart -> size.width * (1f - p)
        else -> 0f
    }
    val right = when (direction) {
        WipeDirection.StartToEnd -> size.width * p
        else -> size.width
    }
    val top = when (direction) {
        WipeDirection.BottomToTop -> size.height * (1f - p)
        else -> 0f
    }
    val bottom = when (direction) {
        WipeDirection.TopToBottom -> size.height * p
        else -> size.height
    }
    clipRect(left = left, top = top, right = right, bottom = bottom) {
        this@drawWithContent.drawContent()
    }
}

fun Modifier.keynoteSpotlight(
    dimProgress: () -> Float,
    dimmedAlpha: Float = 0.25f,
): Modifier = graphicsLayer {
    val d = dimProgress().coerceIn(0f, 1f)
    alpha = 1f - (1f - dimmedAlpha.coerceIn(0f, 1f)) * d
}

fun Modifier.keynoteSpotlight(
    activeFocusKey: () -> Any?,
    itemKey: Any,
    dimmedAlpha: Float = 0.25f,
): Modifier = graphicsLayer {
    val active = activeFocusKey()
    alpha = if (active == null || active == itemKey) 1f else dimmedAlpha.coerceIn(0f, 1f)
}

fun AnimatedLine(
    startKey: Any,
    startAlignment: Alignment,
    endKey: Any,
    endAlignment: Alignment,
    progress: () -> Float,
    color: Color,
    strokeWidth: Dp = 3.dp,
    dashPattern: Pair<Dp, Dp>? = null,
    gap: Dp = 6.dp,
    arrowHead: ArrowHead = ArrowHead.Open,
    headLength: Dp = 14.dp,
    headAngleDegrees: Float = 28f,
    modifier: Modifier = Modifier,
): Decoration = Decoration(modifier) { layout ->
    val p = progress().coerceIn(0f, 1f)
    if (p <= 0f) return@Decoration
    val startRect = layout.getBoundingBox(startKey) ?: return@Decoration
    val endRect = layout.getBoundingBox(endKey) ?: return@Decoration

    val rawStart = borderPosition(startRect, startAlignment, layoutDirection)
    val rawEnd = borderPosition(endRect, endAlignment, layoutDirection)
    val delta = rawEnd - rawStart
    val dist = delta.getDistance()
    val gapPx = gap.toPx()
    if (dist <= gapPx * 2f) return@Decoration

    val unit = delta / dist
    val startPos = rawStart + unit * gapPx
    val endPos = rawEnd - unit * gapPx

    val fullPath = Path().apply {
        moveTo(startPos.x, startPos.y)
        lineTo(endPos.x, endPos.y)
    }
    drawTrimmedDecorationPath(
        fullPath = fullPath,
        progress = p,
        color = color,
        strokeWidthPx = strokeWidth.toPx(),
        dashPattern = dashPattern,
        arrowHead = arrowHead,
        headLengthPx = headLength.toPx(),
        headAngleDegrees = headAngleDegrees,
    )
}

fun AnimatedCubicLine(
    startKey: Any,
    startAlignment: Alignment,
    endKey: Any,
    endAlignment: Alignment,
    progress: () -> Float,
    color: Color,
    strokeWidth: Dp = 3.dp,
    dashPattern: Pair<Dp, Dp>? = null,
    arrowHead: ArrowHead = ArrowHead.Open,
    headLength: Dp = 14.dp,
    headAngleDegrees: Float = 28f,
    modifier: Modifier = Modifier,
): Decoration = Decoration(modifier) { layout ->
    val p = progress().coerceIn(0f, 1f)
    if (p <= 0f) return@Decoration
    val startRect = layout.getBoundingBox(startKey) ?: return@Decoration
    val endRect = layout.getBoundingBox(endKey) ?: return@Decoration

    val startPos = borderPosition(startRect, startAlignment, layoutDirection)
    val endPos = borderPosition(endRect, endAlignment, layoutDirection)
    val bounds = endPos - startPos
    val distance = when {
        startAlignment is BiasAlignment && endAlignment is BiasAlignment -> when {
            startAlignment.horizontalBias == 0f && endAlignment.horizontalBias == 0f -> abs(bounds.y)
            startAlignment.verticalBias == 0f && endAlignment.verticalBias == 0f -> abs(bounds.x)
            else -> minOf(abs(bounds.x), abs(bounds.y))
        }
        else -> minOf(abs(bounds.x), abs(bounds.y))
    }
    val startProj = startPos.projectOutward(startPos - startRect.center, distance / 2f)
    val endProj = endPos.projectOutward(endPos - endRect.center, distance / 2f)

    val fullPath = Path().apply {
        moveTo(startPos.x, startPos.y)
        cubicTo(startProj.x, startProj.y, endProj.x, endProj.y, endPos.x, endPos.y)
    }
    drawTrimmedDecorationPath(
        fullPath = fullPath,
        progress = p,
        color = color,
        strokeWidthPx = strokeWidth.toPx(),
        dashPattern = dashPattern,
        arrowHead = arrowHead,
        headLengthPx = headLength.toPx(),
        headAngleDegrees = headAngleDegrees,
    )
}

fun AnimatedCalloutOutline(
    targetKey: Any,
    progress: () -> Float,
    color: Color,
    cornerRadius: Dp = 12.dp,
    strokeWidth: Dp = 3.dp,
    dashPattern: Pair<Dp, Dp>? = 10.dp to 7.dp,
    inset: Dp = 8.dp,
    modifier: Modifier = Modifier,
): Decoration = Decoration(modifier) { layout ->
    val p = progress().coerceIn(0f, 1f)
    if (p <= 0f) return@Decoration
    val rawRect = layout.getBoundingBox(targetKey) ?: return@Decoration
    val expanded = rawRect.inflate(inset.toPx())
    val radiusPx = cornerRadius.toPx()

    val fullPath = Path().apply {
        addRoundRect(RoundRect(expanded, radiusPx, radiusPx))
    }
    drawTrimmedDecorationPath(
        fullPath = fullPath,
        progress = p,
        color = color,
        strokeWidthPx = strokeWidth.toPx(),
        dashPattern = dashPattern,
        arrowHead = ArrowHead.None,
        headLengthPx = 0f,
        headAngleDegrees = 0f,
    )
}

fun AnimatedStrikeout(
    targetKey: Any,
    progress: () -> Float,
    color: Color,
    strokeWidth: Dp = 4.dp,
    horizontalOverhang: Dp = 6.dp,
    verticalTiltFraction: Float = -0.08f,
    modifier: Modifier = Modifier,
): Decoration = Decoration(modifier) { layout ->
    val p = progress().coerceIn(0f, 1f)
    if (p <= 0f) return@Decoration
    val rect = layout.getBoundingBox(targetKey) ?: return@Decoration
    val overhangPx = horizontalOverhang.toPx()
    val dy = rect.height * verticalTiltFraction
    val start = Offset(rect.left - overhangPx, rect.center.y - dy)
    val end = Offset(rect.right + overhangPx, rect.center.y + dy)

    val fullPath = Path().apply {
        moveTo(start.x, start.y)
        lineTo(end.x, end.y)
    }
    drawTrimmedDecorationPath(
        fullPath = fullPath,
        progress = p,
        color = color,
        strokeWidthPx = strokeWidth.toPx(),
        dashPattern = null,
        arrowHead = ArrowHead.None,
        headLengthPx = 0f,
        headAngleDegrees = 0f,
    )
}

private fun DrawScope.drawTrimmedDecorationPath(
    fullPath: Path,
    progress: Float,
    color: Color,
    strokeWidthPx: Float,
    dashPattern: Pair<Dp, Dp>?,
    arrowHead: ArrowHead,
    headLengthPx: Float,
    headAngleDegrees: Float,
) {
    val trimmed = Path()
    val tip = trimPath(fullPath, progress, trimmed) ?: return
    val pathEffect = dashPattern?.let { (on, off) ->
        PathEffect.dashPathEffect(floatArrayOf(on.toPx(), off.toPx()), 0f)
    }
    drawPath(
        path = trimmed,
        color = color,
        style = Stroke(
            width = strokeWidthPx,
            cap = StrokeCap.Round,
            join = StrokeJoin.Round,
            pathEffect = pathEffect,
        ),
    )
    if (arrowHead != ArrowHead.None && headLengthPx > 0f && tip.tangent.getDistance() > 0.001f) {
        drawArrowHead(tip, color, strokeWidthPx, arrowHead, headLengthPx, headAngleDegrees)
    }
}

private fun DrawScope.drawArrowHead(
    tip: PathTipPose,
    color: Color,
    strokeWidthPx: Float,
    style: ArrowHead,
    headLengthPx: Float,
    headAngleDegrees: Float,
) {
    val dirLen = tip.tangent.getDistance()
    if (dirLen <= 0.001f) return
    val ux = tip.tangent.x / dirLen
    val uy = tip.tangent.y / dirLen
    val rad = headAngleDegrees * (PI.toFloat() / 180f)
    val cosA = cos(rad)
    val sinA = sin(rad)

    // Back-vector (-ux, -uy) rotated by ±headAngleDegrees
    val bx = -ux
    val by = -uy
    val wing1 = Offset(
        x = tip.position.x + (bx * cosA - by * sinA) * headLengthPx,
        y = tip.position.y + (bx * sinA + by * cosA) * headLengthPx,
    )
    val wing2 = Offset(
        x = tip.position.x + (bx * cosA + by * sinA) * headLengthPx,
        y = tip.position.y + (-bx * sinA + by * cosA) * headLengthPx,
    )

    val headPath = Path().apply {
        moveTo(wing1.x, wing1.y)
        lineTo(tip.position.x, tip.position.y)
        lineTo(wing2.x, wing2.y)
        if (style == ArrowHead.Filled) close()
    }
    if (style == ArrowHead.Filled) {
        drawPath(headPath, color = color)
    } else {
        drawPath(
            path = headPath,
            color = color,
            style = Stroke(width = strokeWidthPx, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}

private fun Offset.projectOutward(vector: Offset, distance: Float): Offset {
    val d = vector.getDistance()
    return if (d <= 0.001f) this else this + vector * (distance / d)
}

private fun borderPosition(rect: Rect, alignment: Alignment, layoutDirection: LayoutDirection): Offset {
    val offset = alignment.align(IntSize.Zero, rect.size.toIntSize(), layoutDirection)
    return rect.topLeft + offset.toOffset()
}
