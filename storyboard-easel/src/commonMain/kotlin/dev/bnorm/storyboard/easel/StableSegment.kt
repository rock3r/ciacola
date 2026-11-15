package dev.bnorm.storyboard.easel

import androidx.compose.animation.core.AnimationVector
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.AnimationVector2D
import androidx.compose.animation.core.AnimationVector3D
import androidx.compose.animation.core.AnimationVector4D
import androidx.compose.animation.core.TargetBasedAnimation
import androidx.compose.animation.core.Transition
import kotlin.math.abs

/**
 * Returns `true` while the root [Transition] is actively transitioning between two distinct states
 * (`root.currentState != root.targetState`).
 *
 * Walking up to the root transition prevents a sub-transition created via `createChildTransition`
 * from reading a stale `currentState != targetState` after the root has already snapped back to
 * rest via `SeekableTransitionState.snapTo`.
 */
@Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
val Transition<*>.isSegmentActive: Boolean
    get() {
        var root: Transition<*> = this
        while (root.parentTransition != null) {
            root = root.parentTransition!!
        }
        return root.currentState != root.targetState
    }

/**
 * Returns a `(from, to)` pair for this transition that collapses to `(targetState, targetState)`
 * whenever the root transition is at rest (`!isSegmentActive`), and returns
 * `(currentState, targetState)` while a segment is actively running or scrubbed.
 */
fun <S> Transition<S>.stableSegment(): Pair<S, S> =
    if (isSegmentActive) currentState to targetState else targetState to targetState

/**
 * Progress `[0f..1f]` through the root [Transition]'s active segment relative to its effective
 * changing-animation duration, or `1f` when resting (`!isSegmentActive`).
 */
@Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
val Transition<*>.segmentProgressFraction: Float
    get() {
        var root: Transition<*> = this
        while (root.parentTransition != null) {
            root = root.parentTransition!!
        }
        if (root.currentState == root.targetState) return 1f
        val effective = root.effectiveSegmentDurationNanos().takeIf { it > 0L } ?: root.totalDurationNanos
        if (effective <= 0L) return 1f
        return (root.playTimeNanos.toDouble() / effective.toDouble()).toFloat().coerceIn(0f, 1f)
    }

internal fun Transition<*>.effectiveSegmentDurationNanos(): Long {
    if (currentState == targetState) return 0L
    var longest = 0L
    for (state in animations) {
        if (state.animation.changesSegmentValue()) longest = maxOf(longest, state.animation.durationNanos)
    }
    for (child in transitions) longest = maxOf(longest, child.effectiveSegmentDurationNanos())
    return longest
}

private fun <T, V : AnimationVector> TargetBasedAnimation<T, V>.changesSegmentValue(): Boolean {
    val from = typeConverter.convertToVector(initialValue).vectorComponents()
    val to = typeConverter.convertToVector(targetValue).vectorComponents()
    return from.indices.any { abs(from[it] - to[it]) > 1e-3f }
}

private fun AnimationVector.vectorComponents(): FloatArray = when (this) {
    is AnimationVector1D -> floatArrayOf(value)
    is AnimationVector2D -> floatArrayOf(v1, v2)
    is AnimationVector3D -> floatArrayOf(v1, v2, v3)
    is AnimationVector4D -> floatArrayOf(v1, v2, v3, v4)
}
