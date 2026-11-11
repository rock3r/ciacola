package dev.bnorm.storyboard.easel

import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.Transition
import androidx.compose.animation.core.rememberTransition
import androidx.compose.runtime.*
import dev.bnorm.storyboard.AdvanceDirection
import dev.bnorm.storyboard.Frame
import dev.bnorm.storyboard.Scene
import dev.bnorm.storyboard.Storyboard
import kotlin.math.abs

private class RetainedAnimaticHolder(var current: Animatic? = null)

@Composable
fun rememberAnimatic(
    initialIndex: Storyboard.Index = Storyboard.Index(0, 0),
    storyboard: () -> Storyboard,
): Animatic {
    val holder = remember { RetainedAnimaticHolder() }
    val builtStoryboard = remember(storyboard) { storyboard() }
    val states = remember(builtStoryboard) { Animatic.buildStates(builtStoryboard) }

    val transitionState = remember(builtStoryboard) {
        val desiredIndex = holder.current?.currentIndex ?: initialIndex
        val clampedIndex = clampStoryboardIndex(builtStoryboard.indices, desiredIndex)
        val initialState = states.find { it.index == clampedIndex } ?: states.first()
        SeekableTransitionState(initialState)
    }

    val transition = rememberTransition(transitionState, label = "Animatic")
    // Ensure SeekableTransitionState's composition continuation always observes targetState changes
    // on CMP 1.13+ even when a caller has not yet mounted Easel(animatic).
    transition.targetState
    return remember(builtStoryboard, states, transitionState, transition) {
        Animatic(builtStoryboard, states, transitionState, transition).also {
            holder.current = it
        }
    }
}

internal fun clampStoryboardIndex(
    indices: List<Storyboard.Index>,
    target: Storyboard.Index,
): Storyboard.Index {
    if (indices.isEmpty()) return target
    val exact = indices.binarySearch(target)
    if (exact >= 0) return indices[exact]

    val sameScene = indices.filter { it.sceneIndex == target.sceneIndex }
    if (sameScene.isNotEmpty()) {
        return sameScene.lastOrNull { it.frameIndex <= target.frameIndex } ?: sameScene.first()
    }

    val earlierScene = indices.lastOrNull { it.sceneIndex < target.sceneIndex }
    return earlierScene ?: indices.first()
}

@Stable
class Animatic internal constructor(
    val storyboard: Storyboard,
    private val states: List<LinkedState<*>>,
    private val transitionState: SeekableTransitionState<LinkedState<*>>,
    val transition: Transition<out State<*>>,
) {
    @Immutable
    interface State<T> {
        val scene: Scene<T>
        val frame: Frame<T>
        val index: Storyboard.Index
    }

    internal class LinkedState<T>(
        override val scene: Scene<T>,
        override val frame: Frame<T>,
        override val index: Storyboard.Index,
        val stateIndex: Int,
    ) : State<T>, Comparable<LinkedState<*>> {
        var prev: LinkedState<*>? = null
        var next: LinkedState<*>? = null

        override fun compareTo(other: LinkedState<*>): Int {
            return compareValues(stateIndex, other.stateIndex)
        }
    }

    @Immutable
    data class StateDescriptor(
        val stateIndex: Int,
        val sceneIndex: Int,
        val frameIndex: Int,
        val isBoundaryStart: Boolean,
        val isBoundaryEnd: Boolean,
    )

    val stateDescriptors: List<StateDescriptor> = states.map { s ->
        StateDescriptor(
            stateIndex = s.stateIndex,
            sceneIndex = s.index.sceneIndex,
            frameIndex = s.index.frameIndex,
            isBoundaryStart = s.frame is Frame.Start,
            isBoundaryEnd = s.frame is Frame.End,
        )
    }

    val activeFromStateIndex: Int get() = transitionState.currentState.stateIndex
    val activeToStateIndex: Int get() = transitionState.targetState.stateIndex
    val activeFraction: Float get() = transitionState.fraction

    suspend fun seekToSegment(fromStateIndex: Int, fraction: Float) {
        if (states.isEmpty()) return
        val clampedFrom = fromStateIndex.coerceIn(0, states.lastIndex)
        val clampedFraction = fraction.coerceIn(0f, 1f)

        fun resolveBackwardValueState(idx: Int): LinkedState<*> {
            var cursor = idx.coerceIn(0, states.lastIndex)
            while (cursor > 0 && states[cursor].frame !is Frame.Value) cursor--
            return states[cursor]
        }

        fun resolveForwardValueState(idx: Int): LinkedState<*> {
            var cursor = idx.coerceIn(0, states.lastIndex)
            while (cursor < states.lastIndex && states[cursor].frame !is Frame.Value) cursor++
            return states[cursor]
        }

        if (clampedFrom == states.lastIndex || clampedFraction <= 0f) {
            val exact = states[clampedFrom]
            transitionState.snapTo(exact)
            val settled = resolveBackwardValueState(clampedFrom)
            currentState = settled
            targetState = settled
            return
        }

        val nextIdx = (clampedFrom + 1).coerceAtMost(states.lastIndex)
        if (clampedFraction >= 1f) {
            val exact = states[nextIdx]
            transitionState.snapTo(exact)
            val settled = if (exact.frame is Frame.Value) exact else resolveForwardValueState(nextIdx)
            currentState = settled
            targetState = settled
            return
        }

        val fromState = states[clampedFrom]
        val toState = states[nextIdx]
        if (transitionState.currentState != fromState) {
            transitionState.snapTo(fromState)
        }
        currentState = resolveBackwardValueState(clampedFrom)
        targetState = resolveForwardValueState(nextIdx)
        transitionState.seekTo(clampedFraction, toState)
    }

    suspend fun playSegmentToEnd(fromStateIndex: Int, startFraction: Float = 0f) {
        if (states.size < 2) return
        val clampedFrom = fromStateIndex.coerceIn(0, states.lastIndex - 1)
        seekToSegment(clampedFrom, startFraction.coerceIn(0f, 0.999f))
        val toState = states[clampedFrom + 1]
        transitionState.animateTo(toState)
        if (toState.frame is Frame.Value) {
            currentState = toState
            targetState = toState
        }
    }

    // TODO what should the initial values be?
    private var currentState by mutableStateOf(transitionState.currentState)
    private var targetState by mutableStateOf(transitionState.targetState)

    val currentIndex: Storyboard.Index get() = currentState.index
    val targetIndex: Storyboard.Index get() = targetState.index

    suspend fun advance(direction: AdvanceDirection): Boolean {
        val currentDirection = toDirection(transitionState.currentState.index, transitionState.targetState.index)
        if (currentDirection == null || currentDirection == direction) {
            if (currentDirection == direction) {
                // Snap to the target and continue.
                transitionState.snapTo(targetState)
                currentState = targetState
                // Fall-through and continue with the next advancement.
            }

            // Find the next target frame index.
            val targetFrame = findTargetFrame(direction) ?: return false
            targetState = targetFrame
        } else {
            // Reverse directions.
            val tmp = currentState
            currentState = targetState
            targetState = tmp

            transitionState.animateTo(transitionState.currentState)
        }

        // Animate to the target frame.
        var iterState = transitionState.currentState
        while (iterState != targetState) {
            iterState = iterState.advance(direction) ?: error("internal error")
            transitionState.animateTo(iterState)
        }

        currentState = targetState
        return true
    }

    suspend fun jumpTo(index: Storyboard.Index): Boolean {
        // TODO a little inefficient?
        var jumpIndex = states.binarySearchBy(index) { it.index }
        if (jumpIndex < 0) return false

        // Back up from the search index until a state frame is found.
        // Since the first frame of a storyboard is always a state frame,
        // this loop will never run out of bounds.
        while (states[jumpIndex].frame !is Frame.Value) {
            jumpIndex--
        }

        targetState = states[jumpIndex]
        transitionState.snapTo(targetState)
        currentState = targetState
        return true
    }

    private fun findTargetFrame(direction: AdvanceDirection): LinkedState<*>? {
        require(transitionState.currentState == transitionState.targetState) {
            "cannot find target state during transition: " +
                    "currentState=${transitionState.currentState} " +
                    "targetState=${transitionState.targetState}"
        }

        var iterState = transitionState.currentState.advance(direction) ?: return null
        while (iterState.frame !is Frame.Value) {
            iterState = iterState.advance(direction) ?: error("internal error")
        }
        return iterState
    }

    private fun LinkedState<*>.advance(direction: AdvanceDirection): LinkedState<*>? = when (direction) {
        AdvanceDirection.Forward -> next
        AdvanceDirection.Backward -> prev
    }

    private fun <T : Comparable<T>> toDirection(current: T, target: T): AdvanceDirection? {
        val compare = current.compareTo(target)
        return when {
            compare < 0 -> AdvanceDirection.Forward
            compare > 0 -> AdvanceDirection.Backward
            else -> null
        }
    }

    companion object {
        internal fun buildStates(storyboard: Storyboard): List<LinkedState<*>> = buildList {
            require(storyboard.scenes.isNotEmpty()) { "cannot build frames for empty list of scenes" }

            val first = storyboard.scenes.first()
            val last = storyboard.scenes.last()
            require(first.frames.isNotEmpty() && last.frames.isNotEmpty()) { "first and last scene must have states" }

            var stateIndex = 0
            fun <T> MutableList<LinkedState<*>>.addStates(scene: Scene<T>) {
                for ((frameIndex, value) in scene.frames.withIndex()) {
                    val index = LinkedState(
                        scene = scene,
                        frame = Frame.Value(value),
                        index = Storyboard.Index(scene.index, frameIndex),
                        stateIndex = stateIndex++,
                    )
                    add(index)
                }
            }

            for (scene in storyboard.scenes) {
                if (scene != first) {
                    // TODO don't create an invalid Storyboard.Index
                    add(
                        LinkedState(
                            scene = scene,
                            frame = Frame.Start,
                            index = Storyboard.Index(scene.index, -1),
                            stateIndex = stateIndex++,
                        )
                    )
                }
                addStates(scene)
                if (scene != last) {
                    // TODO don't create an invalid Storyboard.Index
                    add(
                        LinkedState(
                            scene = scene,
                            frame = Frame.End,
                            index = Storyboard.Index(scene.index, scene.frames.size),
                            stateIndex = stateIndex++,
                        )
                    )
                }
            }

            var prev: LinkedState<*>? = null
            for (state in this) {
                state.prev = prev
                prev?.next = state
                prev = state
            }
        }
    }

    // TODO internal stuff for slider
    //  - is there a way to simplify all of this?
    //  - is there a way we can make this public without exposing too much?

    internal val storyDistance: Float
        get() = states.lastIndex.toFloat()

    internal val storyProgress: Float
        get() {
            val current = transitionState.currentState.stateIndex
            val target = transitionState.targetState.stateIndex
            val fraction = when {
                current == target -> 0f
                target > current -> transitionState.fraction
                else -> -transitionState.fraction
            }

            return current.toFloat() + fraction
        }

    internal val advancementDistance: Float
        get() {
            if (currentState == targetState) return 1f
            val start = currentState.stateIndex
            val target = targetState.stateIndex
            return abs(target - start).toFloat()
        }

    internal val advancementProgress: Float
        get() {
            if (currentIndex == targetIndex) return 1f
            val start = currentState.stateIndex
            val current = transitionState.currentState.stateIndex
            val fraction = if (current == transitionState.targetState.stateIndex) 0f else transitionState.fraction
            return abs(current - start) + fraction
        }

    internal suspend fun seek(storyFraction: Float) {
        if (states.isEmpty()) return
        val progress = (storyFraction.coerceIn(0f, 1f)) * storyDistance
        val index = progress.toInt().coerceIn(0, states.lastIndex)
        val nextFraction = (progress - index).coerceIn(0f, 1f)
        seekToSegment(index, nextFraction)
    }
}
