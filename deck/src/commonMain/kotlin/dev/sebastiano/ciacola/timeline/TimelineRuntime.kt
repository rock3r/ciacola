package dev.sebastiano.ciacola.timeline

import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.ContentScale
import dev.bnorm.storyboard.easel.rememberSharedContentState
import dev.bnorm.storyboard.easel.sharedBounds
import kotlin.math.hypot
import kotlinx.serialization.Serializable

enum class TrackCategory {
    SharedElement,
    MagicText,
    WarpCode,
    StaggerGroup,
    StaggerItem,
    Build,
    LineDraw,
    LoopSubComp,
}

@Serializable
data class TimelineCurveSpec(
    val preset: String = "KeynoteEase",
    val x1: Float = 0.25f,
    val y1: Float = 0.1f,
    val x2: Float = 0.25f,
    val y2: Float = 1.0f,
    val isSpring: Boolean = false,
    val dampingRatio: Float = 0.78f,
    val stiffness: Float = 520f,
) {
    fun toEasing(): Easing = when (preset) {
        "Linear" -> LinearEasing
        else -> CubicBezierEasing(
            x1.coerceIn(0f, 1f),
            y1.coerceIn(-0.6f, 1.6f),
            x2.coerceIn(0f, 1f),
            y2.coerceIn(-0.6f, 1.6f),
        )
    }

    fun <T> toAnimationSpec(durationMillis: Int, delayMillis: Int = 0): FiniteAnimationSpec<T> {
        return if (isSpring) {
            spring(
                dampingRatio = dampingRatio.coerceIn(0.2f, 1.5f),
                stiffness = stiffness.coerceIn(50f, 4_000f),
            )
        } else {
            tween(
                durationMillis = durationMillis.coerceAtLeast(16),
                delayMillis = delayMillis.coerceAtLeast(0),
                easing = toEasing(),
            )
        }
    }
}

@Serializable
data class TimelineSpatialSpec(
    val offsetXDp: Float = 0f,
    val offsetYDp: Float = 24f,
    val initialScale: Float = 0.88f,
    val arcBend: Float = 0f,
)

@Serializable
data class TrackOverride(
    val trackId: String,
    val enterBeat: Int? = null,
    val exitBeat: Int? = null,
    val delayMs: Int? = null,
    val durationMs: Int? = null,
    val staggerStepMs: Int? = null,
    val curve: TimelineCurveSpec? = null,
    val spatial: TimelineSpatialSpec? = null,
)

@Serializable
data class SceneBeatOverride(
    val sceneIndex: Int,
    val injectedAfterBeats: List<Int> = emptyList(),
    val deletedBeats: List<Int> = emptyList(),
)

@Serializable
data class DeckTimelineOverridesFile(
    val tracks: Map<String, TrackOverride> = emptyMap(),
    val sceneBeats: Map<Int, SceneBeatOverride> = emptyMap(),
)

data class ActiveSceneTimelineContext(
    val sceneIndex: Int,
    val sceneTitle: String,
    val totalBeats: Int,
    val frameBeatIndexOf: (Any?) -> Int,
)

data class HarvestedTrackRecord(
    val trackId: String,
    val label: String,
    val category: TrackCategory,
    val sceneIndex: Int,
    val triggerBeat: Int,
    val durationMs: Int,
    val delayMs: Int = 0,
    val staggerCount: Int? = null,
    val staggerStepMs: Int? = null,
    val sharedKey: String? = null,
    val loopId: String? = null,
    val loopPeriodMs: Long? = null,
)

class TimelineHarvestCollector {
    val records = LinkedHashMap<String, HarvestedTrackRecord>()
    val sharedSightings = mutableListOf<Triple<String, Int, Int>>() // sharedKey, sceneIndex, beatIndex
    val sharedLabels = mutableMapOf<String, String>()
    val loopRecords = linkedMapOf<String, Pair<LoopSubCompDescriptor, Int>>()

    fun record(entry: HarvestedTrackRecord) {
        val key = "${entry.trackId}@s${entry.sceneIndex}:b${entry.triggerBeat}"
        records[key] = entry
    }

    fun recordSharedSighting(sharedKey: String, label: String, sceneIndex: Int, beatIndex: Int) {
        sharedLabels[sharedKey] = label
        val triple = Triple(sharedKey, sceneIndex, beatIndex)
        if (triple !in sharedSightings) {
            sharedSightings += triple
        }
    }

    fun recordLoop(descriptor: LoopSubCompDescriptor, sceneIndex: Int) {
        if (descriptor.loopId !in loopRecords) {
            loopRecords[descriptor.loopId] = descriptor to sceneIndex
        }
    }
}

data class ActiveMotionArcPreview(
    val sharedKey: String,
    val startBounds: Rect,
    val endBounds: Rect,
    val arcBend: Float,
    val sceneIndex: Int? = null,
)

@Stable
class MotionPathPreviewState {
    var selectedTrackId by mutableStateOf<String?>(null)
    var latestArc by mutableStateOf<ActiveMotionArcPreview?>(null)
    var activeStageSceneIndex by mutableStateOf<Int?>(null)
}

val LocalTimelineOverrides = staticCompositionLocalOf { DeckTimelineOverridesFile() }
val LocalActiveSceneTimeline = staticCompositionLocalOf<ActiveSceneTimelineContext?> { null }
val LocalTimelineHarvestCollector = staticCompositionLocalOf<TimelineHarvestCollector?> { null }
val LocalMotionPathPreviewState = staticCompositionLocalOf<MotionPathPreviewState?> { null }

/**
 * Computes a quadratic-Bezier arc-interpolated [Rect] between [start] and [end].
 * When [arcBend] is `0f`, reduces to exact linear interpolation.
 */
fun computeArcInterpolatedRect(
    start: Rect,
    end: Rect,
    fraction: Float,
    arcBend: Float,
): Rect {
    val t = fraction.coerceIn(0f, 1f)
    val width = start.width + (end.width - start.width) * t
    val height = start.height + (end.height - start.height) * t

    val sx = start.center.x
    val sy = start.center.y
    val ex = end.center.x
    val ey = end.center.y

    if (kotlin.math.abs(arcBend) < 0.001f) {
        val cx = sx + (ex - sx) * t
        val cy = sy + (ey - sy) * t
        return Rect(cx - width / 2f, cy - height / 2f, cx + width / 2f, cy + height / 2f)
    }

    val dx = ex - sx
    val dy = ey - sy
    val dist = hypot(dx, dy).coerceAtLeast(64f)
    // Normal vector perpendicular to chord (upward bend for positive arcBend on horizontal moves)
    val nx = -dy / dist
    val ny = dx / dist
    val peakOffset = -arcBend * dist * 0.42f
    val ctrlX = (sx + ex) * 0.5f + nx * peakOffset
    val ctrlY = (sy + ey) * 0.5f + ny * peakOffset

    val oneMinusT = 1f - t
    val cx = oneMinusT * oneMinusT * sx + 2f * oneMinusT * t * ctrlX + t * t * ex
    val cy = oneMinusT * oneMinusT * sy + 2f * oneMinusT * t * ctrlY + t * t * ey

    return Rect(cx - width / 2f, cy - height / 2f, cx + width / 2f, cy + height / 2f)
}

fun createArcBoundsTransform(
    durationMillis: Int,
    delayMillis: Int = 0,
    curve: TimelineCurveSpec = TimelineCurveSpec(),
    arcBend: Float = 0f,
    onBoundsObserved: ((Rect, Rect) -> Unit)? = null,
): BoundsTransform = BoundsTransform { initialBounds, targetBounds ->
    onBoundsObserved?.invoke(initialBounds, targetBounds)
    val safeDuration = durationMillis.coerceAtLeast(32)
    if (kotlin.math.abs(arcBend) < 0.001f && delayMillis == 0 && !curve.isSpring) {
        tween(durationMillis = safeDuration, easing = curve.toEasing())
    } else {
        val easing = curve.toEasing()
        val steps = 16
        keyframes {
            this.durationMillis = safeDuration + delayMillis.coerceAtLeast(0)
            initialBounds at 0
            if (delayMillis > 0) {
                initialBounds at delayMillis
            }
            for (step in 1 until steps) {
                val rawFrac = step.toFloat() / steps.toFloat()
                val easedFrac = easing.transform(rawFrac)
                val timeAt = delayMillis + (rawFrac * safeDuration).toInt()
                computeArcInterpolatedRect(initialBounds, targetBounds, easedFrac, arcBend) at timeAt
            }
            targetBounds at (delayMillis + safeDuration)
        }
    }
}

/**
 * Shared-element modifier that automatically registers cross-slide spans in the After Effects
 * timeline and honors live duration, curve, and curved spatial-arc overrides.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
context(animatedVisibilityScope: AnimatedVisibilityScope, sharedTransitionScope: SharedTransitionScope)
fun Modifier.deckSharedBounds(
    sharedKey: String,
    label: String = sharedKey,
    defaultDurationMs: Int = 420,
    currentBeatInScene: Int = 0,
): Modifier {
    val trackId = "shared:$sharedKey"
    val overrides = LocalTimelineOverrides.current.tracks[trackId]
    val sceneCtx = LocalActiveSceneTimeline.current
    val collector = LocalTimelineHarvestCollector.current
    val previewState = LocalMotionPathPreviewState.current

    val effectiveDuration = overrides?.durationMs ?: defaultDurationMs
    val effectiveDelay = overrides?.delayMs ?: 0
    val effectiveCurve = overrides?.curve ?: TimelineCurveSpec()
    val effectiveArcBend = overrides?.spatial?.arcBend ?: 0f

    SideEffect {
        if (sceneCtx != null) {
            collector?.recordSharedSighting(
                sharedKey = sharedKey,
                label = label,
                sceneIndex = sceneCtx.sceneIndex,
                beatIndex = currentBeatInScene,
            )
        }
    }

    val boundsTransform = createArcBoundsTransform(
        durationMillis = effectiveDuration,
        delayMillis = effectiveDelay,
        curve = effectiveCurve,
        arcBend = effectiveArcBend,
        onBoundsObserved = { startRect, endRect ->
            if (previewState != null && previewState.selectedTrackId == trackId) {
                previewState.latestArc = ActiveMotionArcPreview(
                    sharedKey = sharedKey,
                    startBounds = startRect,
                    endBounds = endRect,
                    arcBend = effectiveArcBend,
                    sceneIndex = sceneCtx?.sceneIndex,
                )
            }
        },
    )

    return this.sharedBounds(
        sharedContentState = rememberSharedContentState(sharedKey),
        enter = fadeIn(tween(effectiveDuration, delayMillis = effectiveDelay)),
        exit = fadeOut(tween(effectiveDuration, delayMillis = effectiveDelay)),
        boundsTransform = boundsTransform,
        resizeMode = SharedTransitionScope.ResizeMode.scaleToBounds(ContentScale.FillWidth, Alignment.Center),
    )
}

/**
 * Applies zero-latency event beat insertions and deletions to a scene's base frame list.
 * Returns the expanded/contracted list of `(virtualBeatIndex, baseFrameValue, isInjected)` tuples.
 */
data class VirtualizedFrame<T>(
    val virtualBeatIndex: Int,
    val value: T,
    val baseBeatIndex: Int,
    val isInjected: Boolean,
)

fun <T> buildVirtualizedFrames(
    baseFrames: List<T>,
    beatOverride: SceneBeatOverride?,
): List<VirtualizedFrame<T>> {
    require(baseFrames.isNotEmpty()) { "Scene baseFrames must not be empty" }
    if (beatOverride == null ||
        (beatOverride.injectedAfterBeats.isEmpty() && beatOverride.deletedBeats.isEmpty())
    ) {
        return baseFrames.mapIndexed { idx, v ->
            VirtualizedFrame(virtualBeatIndex = idx, value = v, baseBeatIndex = idx, isInjected = false)
        }
    }

    val deleted = beatOverride.deletedBeats.toSet()
    val injectionCounts = beatOverride.injectedAfterBeats.groupingBy { it }.eachCount()

    val raw = mutableListOf<Triple<T, Int, Boolean>>()
    for ((baseIdx, value) in baseFrames.withIndex()) {
        if (baseIdx !in deleted || (raw.isEmpty() && baseIdx == baseFrames.lastIndex)) {
            raw += Triple(value, baseIdx, false)
        }
        val extraAfter = injectionCounts[baseIdx] ?: 0
        repeat(extraAfter) {
            // For Int step scenes, increment step value if safe; for typed data states, hold current value.
            @Suppress("UNCHECKED_CAST")
            val heldValue: T = if (value is Int) {
                value as T
            } else {
                value
            }
            raw += Triple(heldValue, baseIdx, true)
        }
    }
    if (raw.isEmpty()) {
        raw += Triple(baseFrames.first(), 0, false)
    }
    return raw.mapIndexed { vIdx, (value, baseIdx, injected) ->
        VirtualizedFrame(
            virtualBeatIndex = vIdx,
            value = value,
            baseBeatIndex = baseIdx,
            isInjected = injected,
        )
    }
}
