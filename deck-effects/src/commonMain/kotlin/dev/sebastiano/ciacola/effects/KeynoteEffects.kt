package dev.sebastiano.ciacola.effects

import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
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
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toIntSize
import androidx.compose.ui.unit.toOffset
import dev.bnorm.storyboard.layout.decorated.Decoration
import dev.sebastiano.ciacola.inspector.provideStageVisibilityAlpha
import dev.sebastiano.ciacola.theme.LocalDeckTheme
import dev.sebastiano.ciacola.theme.LocalReducedMotion
import dev.sebastiano.ciacola.theme.REDUCED_MOTION_FADE_DURATION_MS
import dev.sebastiano.ciacola.timeline.TrackCategory
import dev.sebastiano.ciacola.timeline.timelineTarget
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

/**
 * Eased 0→1 progress of a build that fires on [defaultTriggerBeat] (or the track's `enterBeat`
 * override). Inside a choreographed scene the beat is the single source of truth: visibility is
 * derived from the scene's beat index, so the timeline block and the stage always agree. [visible]
 * is only consulted outside a scene (previews, plain transitions without a timeline context).
 */
@Composable
fun <S> Transition<S>.animateBuildProgress(
    label: String = "keynote-build",
    trackId: String? = null,
    category: TrackCategory = TrackCategory.Build,
    defaultTriggerBeat: Int = 1,
    durationMillis: Int = LocalDeckTheme.current.motion.fadeMoveDurationMs,
    easing: Easing = LocalDeckTheme.current.motion.standardEasing,
    visible: ((S) -> Boolean)? = null,
): State<Float> {
    val reducedMotion = LocalReducedMotion.current
    return animateFloat(
        transitionSpec = {
            if (reducedMotion) {
                tween(
                    durationMillis = durationMillis.coerceAtMost(REDUCED_MOTION_FADE_DURATION_MS),
                    delayMillis = 0,
                    easing = LinearOutSlowInEasing,
                )
            } else {
                tween(
                    durationMillis = durationMillis,
                    delayMillis = 0,
                    easing = easing,
                )
            }
        },
        label = label,
    ) { state ->
        val isVisible = visible?.invoke(state)
            ?: fallbackBeatIndexOf(state)?.let { it >= defaultTriggerBeat }
            ?: false
        if (isVisible) 1f else 0f
    }
}

/** Beat index of a plain `Int` state, for builds used outside a choreographed scene. */
private fun fallbackBeatIndexOf(state: Any?): Int? = state as? Int

@Composable
fun <S> Transition<S>.animateStaggerGroup(
    count: Int,
    label: String = "keynote-stagger",
    trackId: String? = null,
    defaultTriggerBeat: Int = 1,
    itemDurationMs: Int = LocalDeckTheme.current.motion.fadeMoveDurationMs,
    staggerStepMs: Int = LocalDeckTheme.current.motion.staggerStepMs,
    easing: Easing = LocalDeckTheme.current.motion.standardEasing,
    visible: ((S) -> Boolean)? = null,
): StaggerGroupState {
    val safeCount = count.coerceAtLeast(1)
    val reducedMotion = LocalReducedMotion.current
    val effectiveItemDuration = if (reducedMotion) itemDurationMs.coerceAtMost(REDUCED_MOTION_FADE_DURATION_MS) else itemDurationMs
    val effectiveStep = if (reducedMotion) 0 else staggerStepMs
    val totalDurationMs = effectiveItemDuration + (safeCount - 1) * effectiveStep

    fun isVisibleAt(state: S): Boolean =
        visible?.invoke(state) ?: fallbackBeatIndexOf(state)?.let { it >= defaultTriggerBeat } ?: false

    val progress = animateFloat(
        transitionSpec = {
            if (reducedMotion) {
                tween(
                    durationMillis = totalDurationMs,
                    delayMillis = 0,
                    easing = LinearOutSlowInEasing,
                )
            } else {
                tween(
                    durationMillis = totalDurationMs,
                    delayMillis = 0,
                    easing = easing,
                )
            }
        },
        label = label,
    ) { state ->
        if (isVisibleAt(state)) 1f else 0f
    }
    return remember(this, progress, safeCount, effectiveItemDuration, effectiveStep, reducedMotion) {
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

@Composable
fun Modifier.keynoteFadeMove(
    progress: () -> Float,
    direction: SlideDirection = SlideDirection.Up,
    distance: Dp = LocalDeckTheme.current.motion.fadeMoveDistance,
    // Build progress (animateBuildProgress / animateStaggerGroup) is already eased by its curve, so
    // the default here is linear; pass an easing only when driving the modifier with raw progress.
    easing: Easing = LinearEasing,
): Modifier {
    val reducedMotion = LocalReducedMotion.current
    return provideStageVisibilityAlpha { progress().coerceIn(0f, 1f) }
        .graphicsLayer {
            val raw = progress().coerceIn(0f, 1f)
            alpha = raw
            if (reducedMotion) {
                translationX = 0f
                translationY = 0f
            } else {
                val eased = easing.transform(raw)
                val remainingPx = (1f - eased) * distance.toPx()
                when (direction) {
                    SlideDirection.Up -> translationY = remainingPx
                    SlideDirection.Down -> translationY = -remainingPx
                    SlideDirection.Left -> translationX = remainingPx
                    SlideDirection.Right -> translationX = -remainingPx
                }
            }
        }
}

@Composable
fun Modifier.keynoteFromDarkness(
    progress: () -> Float,
    initialScale: Float = LocalDeckTheme.current.motion.fromDarknessInitialScale,
    transformOrigin: TransformOrigin = TransformOrigin.Center,
    // Build progress (animateBuildProgress / animateStaggerGroup) is already eased by its curve, so
    // the default here is linear; pass an easing only when driving the modifier with raw progress.
    easing: Easing = LinearEasing,
    entranceOffset: DpOffset = DpOffset.Zero,
): Modifier {
    val reducedMotion = LocalReducedMotion.current
    return provideStageVisibilityAlpha { progress().coerceIn(0f, 1f) }
        .graphicsLayer {
            val raw = progress().coerceIn(0f, 1f)
            alpha = raw
            if (reducedMotion) {
                scaleX = 1f
                scaleY = 1f
                translationX = 0f
                translationY = 0f
            } else {
                val eased = easing.transform(raw)
                val s = initialScale + (1f - initialScale) * eased
                scaleX = s
                scaleY = s
                translationX = (1f - eased) * entranceOffset.x.toPx()
                translationY = (1f - eased) * entranceOffset.y.toPx()
            }
            this.transformOrigin = transformOrigin
        }
}

@Composable
fun Modifier.keynotePop(
    progress: () -> Float,
    initialScale: Float = LocalDeckTheme.current.motion.popInitialScale,
    overshootScale: Float = LocalDeckTheme.current.motion.popOvershootScale,
    entranceOffset: DpOffset = DpOffset.Zero,
): Modifier {
    val reducedMotion = LocalReducedMotion.current
    return provideStageVisibilityAlpha {
        if (reducedMotion) progress().coerceIn(0f, 1f) else keynotePopAlpha(progress())
    }.graphicsLayer {
        val p = progress()
        if (reducedMotion) {
            alpha = p.coerceIn(0f, 1f)
            scaleX = 1f
            scaleY = 1f
            translationX = 0f
            translationY = 0f
        } else {
            val scale = keynotePopScale(p, initialScale, overshootScale)
            alpha = keynotePopAlpha(p)
            scaleX = scale
            scaleY = scale
            val remaining = 1f - p.coerceIn(0f, 1f)
            translationX = remaining * entranceOffset.x.toPx()
            translationY = remaining * entranceOffset.y.toPx()
        }
    }
}

fun keynotePopScale(progress: Float, initialScale: Float, overshootScale: Float): Float {
    // C1-continuous "back" ease-out: rises from initialScale, overshoots once to overshootScale, and
    // lands on 1 with zero velocity, so the pop has no velocity kink (and plays back cleanly in reverse).
    val p = progress.coerceIn(0f, 1f)
    val span = 1f - initialScale
    val u = p - 1f
    if (span <= 0f || overshootScale <= 1f) return initialScale + span * (1f + u * u * u)
    val c = backOvershootFor((overshootScale - 1f) / span)
    return initialScale + span * (1f + (c + 1f) * u * u * u + c * u * u)
}

/** Solves the back-ease constant whose peak overshoot (4c³ / 27(c+1)²) equals [peak]. */
private fun backOvershootFor(peak: Float): Float {
    var lo = 0f
    var hi = 50f
    repeat(40) {
        val mid = (lo + hi) / 2f
        val value = 4f * mid * mid * mid / (27f * (mid + 1f) * (mid + 1f))
        if (value < peak) lo = mid else hi = mid
    }
    return (lo + hi) / 2f
}

fun keynotePopAlpha(progress: Float): Float = smoothstep((progress / 0.35f).coerceIn(0f, 1f))

private fun smoothstep(t: Float): Float = t * t * (3f - 2f * t)

/** Arrowhead length for a leader trimmed to [progress]: the head grows in with the shaft. */
fun arrowHeadLength(fullLengthPx: Float, progress: Float): Float =
    fullLengthPx * smoothstep((progress / 0.3f).coerceIn(0f, 1f))

@Composable
fun Modifier.keynoteWipe(
    progress: () -> Float,
    direction: WipeDirection = WipeDirection.StartToEnd,
): Modifier {
    val withAlpha = provideStageVisibilityAlpha { progress().coerceIn(0f, 1f) }
    if (LocalReducedMotion.current) {
        return withAlpha.graphicsLayer {
            alpha = progress().coerceIn(0f, 1f)
        }
    }
    return withAlpha.drawWithContent {
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
    val edgeAlpha = (progress / 0.08f).coerceIn(0f, 1f)
    val effectiveColor = color.copy(alpha = color.alpha * edgeAlpha)
    val pathEffect = dashPattern?.let { (on, off) ->
        PathEffect.dashPathEffect(floatArrayOf(on.toPx(), off.toPx()), 0f)
    }
    drawPath(
        path = trimmed,
        color = effectiveColor,
        style = Stroke(
            width = strokeWidthPx,
            cap = StrokeCap.Round,
            join = StrokeJoin.Round,
            pathEffect = pathEffect,
        ),
    )
    if (arrowHead != ArrowHead.None && headLengthPx > 0f && tip.tangent.getDistance() > 0.001f) {
        drawArrowHead(tip, effectiveColor, strokeWidthPx, arrowHead, arrowHeadLength(headLengthPx, progress), headAngleDegrees)
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
