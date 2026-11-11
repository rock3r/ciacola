package dev.bnorm.storyboard.easel

import androidx.compose.animation.core.AnimationVector
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.AnimationVector2D
import androidx.compose.animation.core.AnimationVector3D
import androidx.compose.animation.core.AnimationVector4D
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.TargetBasedAnimation
import androidx.compose.animation.core.Transition
import androidx.compose.animation.core.rememberTransition
import androidx.compose.runtime.*
import dev.bnorm.storyboard.AdvanceDirection
import dev.bnorm.storyboard.Frame
import dev.bnorm.storyboard.Scene
import dev.bnorm.storyboard.Storyboard
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Frames a freshly targeted segment rests at play time 0 before it is seeked forward, so content
 * nested a few composition levels deep is composed (and its animations created) at play time 0.
 * Settling continues past the minimum until the transition's total duration stops changing (new
 * children register their animations), up to [SEGMENT_MAX_SETTLE_FRAMES].
 */
private const val SEGMENT_SETTLE_FRAMES = 3
private const val SEGMENT_MAX_SETTLE_FRAMES = 12

/** Frames [Animatic.measurePlaybackDurationsNanos] rests on each beat, like a presenter would. */
private const val MEASURE_REST_FRAMES = 12

/** Counts settle frames; settled after the minimum once the total duration held for a frame. */
private class SettleTracker {
    private var frames = 0
    private var lastTotal = -1L
    private var unchanged = 0

    fun isSettled(totalDurationNanos: Long): Boolean {
        frames++
        if (totalDurationNanos == lastTotal) unchanged++ else unchanged = 0
        lastTotal = totalDurationNanos
        return frames >= SEGMENT_MAX_SETTLE_FRAMES || (frames >= SEGMENT_SETTLE_FRAMES && unchanged >= 1)
    }
}

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

    /**
     * Progress through the active segment as a fraction of its [effective duration][effectiveDurationNanos]
     * (the span in which something on stage actually changes), which is what the timeline shows.
     */
    val activeFraction: Float
        get() {
            val total = transition.totalDurationNanos
            val effective = effectiveDurationNanos()
            if (total <= 0L || effective <= 0L) return 0f
            return (transitionState.fraction * total.toDouble() / effective).toFloat().coerceIn(0f, 1f)
        }

    /**
     * How long the active segment visibly animates: the longest running animation whose start and
     * end values differ. A `Transition` otherwise also counts the full `tween` duration of every
     * animation that does not change in this segment, which would play as dead time after a click.
     */
    fun effectiveDurationNanos(): Long = transition.effectiveDurationNanos()

    private fun Transition<*>.effectiveDurationNanos(): Long {
        if (currentState == targetState) return 0L
        var longest = 0L
        for (state in animations) {
            if (state.animation.changesValue()) longest = maxOf(longest, state.animation.durationNanos)
        }
        for (child in transitions) longest = maxOf(longest, child.effectiveDurationNanos())
        return longest
    }

    private fun <T, V : AnimationVector> TargetBasedAnimation<T, V>.changesValue(): Boolean {
        val from = typeConverter.convertToVector(initialValue).components()
        val to = typeConverter.convertToVector(targetValue).components()
        if (from == null || to == null) return initialValue != targetValue
        return from.indices.any { abs(from[it] - to[it]) > 1e-3f }
    }

    private fun AnimationVector.components(): FloatArray? = when (this) {
        is AnimationVector1D -> floatArrayOf(value)
        is AnimationVector2D -> floatArrayOf(v1, v2)
        is AnimationVector3D -> floatArrayOf(v1, v2, v3)
        is AnimationVector4D -> floatArrayOf(v1, v2, v3, v4)
        else -> null
    }

    /** Converts a fraction of the effective duration into a `SeekableTransitionState` fraction. */
    private fun transitionFractionFor(effectiveFraction: Float): Float {
        val total = transition.totalDurationNanos
        val effective = effectiveDurationNanos()
        if (total <= 0L || effective <= 0L) return 0f
        return (effectiveFraction.toDouble() * effective / total).toFloat().coerceIn(0f, 1f)
    }

    /**
     * Incremented on every random-access scrub step. [Easel] keys its scene subtree on this value,
     * so each scrubbed frame is composed from scratch at `(segment, fraction)`: child transitions,
     * content queues, shared-bounds anchors, and cached plans can never leak from the scrub path that
     * led there. Regular navigation ([advance], [jumpTo]) never bumps it.
     */
    var scrubEpoch: Int by mutableIntStateOf(0)
        private set

    /**
     * `true` while a rebuilt scene subtree settles on its segment's `from` state. [Easel] keeps
     * presenting the previous frame during this window so the settle frames are never visible.
     */
    var scrubSettling: Boolean by mutableStateOf(false)
        private set

    private val seekMutex = Mutex()

    /**
     * Frame clock of the composition that renders this animatic ([Easel] registers it). All
     * navigation runs on it so "wait a frame" means "the stage composed a frame".
     */
    internal var renderFrameClock: MonotonicFrameClock? = null

    /** Snapshots the currently presented frame ([Easel] registers it); see [freezePresentedFrame]. */
    internal var freezeCapture: (suspend () -> Unit)? = null

    /**
     * Starts presenting a snapshot of the current frame instead of live content ([scrubSettling]).
     * No-op if already frozen, so nested freezes keep the outermost snapshot.
     */
    private suspend fun freezePresentedFrame() {
        if (scrubSettling) return
        freezeCapture?.invoke()
        scrubSettling = true
    }

    /** The navigation currently animating, if any; a newer navigation supersedes (cancels) it. */
    private var inFlight: Job? = null

    /**
     * Runs [block] exclusively: an in-flight navigation is cancelled first (so rapid arrow presses
     * skip ahead instead of queueing), then callers are serialized. Returns `null` if [block] was
     * itself superseded by a newer call.
     */
    private suspend fun <T> exclusive(block: suspend () -> T): T? {
        inFlight?.cancel()
        return seekMutex.withLock {
            coroutineScope {
                // Count frames on the clock of the window that renders this animatic: callers may
                // live in another composition (e.g. an application-level controller) whose frame
                // clock ticks independently, so settle frames would elapse before anything composed.
                val work = async(renderFrameClock ?: EmptyCoroutineContext) { block() }
                inFlight = work
                try {
                    work.await()
                } catch (e: CancellationException) {
                    if (work.isCancelled && currentCoroutineContext().isActive) null else throw e
                } finally {
                    if (inFlight === work) inFlight = null
                }
            }
        }
    }

    /**
     * Seeks to [fraction] of the segment starting at [fromStateIndex]. A newer call supersedes an
     * older one that has not finished yet, so the last request always wins.
     *
     * @param rebuildScene when `true` (default) the scene subtree is recomposed from scratch for this
     * frame (see [scrubEpoch]). Passing `false` performs a plain in-place seek.
     */
    suspend fun seekToSegment(fromStateIndex: Int, fraction: Float, rebuildScene: Boolean = true) {
        exclusive { seekToSegmentLocked(fromStateIndex, fraction, rebuildScene) }
    }

    private suspend fun seekToSegmentLocked(fromStateIndex: Int, fraction: Float, rebuildScene: Boolean) {
        if (states.isEmpty()) return
        var segment = fromStateIndex.coerceIn(0, states.lastIndex)
        var clampedFraction = fraction.coerceIn(0f, 1f)
        // Normalize the end of a segment to the start of the next one.
        if (clampedFraction >= 1f && segment < states.lastIndex) {
            segment += 1
            clampedFraction = 0f
        }
        if (segment == states.lastIndex) clampedFraction = 0f

        if (!rebuildScene) {
            seekInPlace(segment, clampedFraction)
            return
        }

        freezePresentedFrame()
        try {
            // 1. Fresh subtree, settled on `from` (presented frame stays frozen meanwhile).
            scrubEpoch++
            val fromState = states[segment]
            if (transitionState.currentState != fromState || transitionState.targetState != fromState) {
                transitionState.snapTo(fromState)
            }
            withFrameNanos { } // recompose the fresh subtree at `from`
            withFrameNanos { } // let its SideEffects observe the settled state
            // 2. Seek the freshly composed subtree straight to the requested position.
            seekInPlace(segment, clampedFraction)
            withFrameNanos { } // make sure the seeked frame is composed before unfreezing
        } finally {
            scrubSettling = false
        }
    }

    /**
     * Plays the whole storyboard forward from its first state, exactly like arrow-key presenting,
     * and returns how long each segment (state `i` to `i + 1`) animated, in nanoseconds. Intended for
     * offscreen timeline indexing driven by a virtual frame clock.
     */
    suspend fun measurePlaybackDurationsNanos(): LongArray = exclusive {
        val result = LongArray((states.size - 1).coerceAtLeast(0))
        if (result.isEmpty()) return@exclusive result
        transitionState.snapTo(states.first())
        currentState = states.first()
        targetState = states.first()
        withFrameNanos { }
        withFrameNanos { }
        for (i in result.indices) {
            result[i] = runSegmentBySeeking(states[i + 1], measureOnly = true)
            // A presenter always rests on a beat before the next click; let the resting state settle
            // the same way (state-dependent recompositions can change the next segment's length).
            if (states[i + 1].frame is Frame.Value) repeat(MEASURE_REST_FRAMES) { withFrameNanos { } }
        }
        result
    } ?: LongArray(0)

    /**
     * Animates the transition from where it is now to the adjacent state [to] by seeking it frame by
     * frame (rather than `animateTo`), so presenting renders exactly the frames a scrub renders.
     * Handles a fresh start, resuming an interrupted segment towards [to], and reversing an
     * interrupted segment back to [to]. Returns the segment's total duration in nanoseconds.
     */
    private suspend fun runSegmentBySeeking(to: LinkedState<*>, measureOnly: Boolean = false): Long {
        val ts = transitionState
        if (ts.currentState == to && ts.targetState == to) return 0L
        val reverse = ts.currentState == to
        val resume = !reverse && ts.targetState == to
        var settling = false
        val wasSettling = scrubSettling
        val startFraction = if (reverse || resume) {
            activeFraction
        } else {
            if (ts.currentState != ts.targetState) ts.snapTo(ts.targetState)
            // Compose the new segment at play time 0 and give nested content a few frames to
            // compose before seeking forward (late children are delayed by the play time at which
            // they are first composed; see seekInPlace). Those frames are not presentable (shared
            // bounds have not started yet): keep showing the resting frame until the segment moves.
            freezePresentedFrame()
            try {
                ts.seekTo(0f, to)
            } catch (e: Throwable) {
                scrubSettling = wasSettling
                throw e
            }
            settling = true
            0f
        }
        val segmentTarget = ts.targetState
        val settle = SettleTracker()
        var played = -1.0
        var lastFrame = -1L
        var effective = 0L
        try {
            while (true) {
                val now = withFrameNanos { it }
                val total = transition.totalDurationNanos
                effective = effectiveDurationNanos()
                if (lastFrame < 0L) {
                    played = startFraction.toDouble() * effective
                } else {
                    played += if (reverse) -(now - lastFrame).toDouble() else (now - lastFrame).toDouble()
                }
                lastFrame = now
                if (settling) {
                    if (!settle.isSettled(total)) continue
                    settling = false
                }
                // Measuring only needs the settled segment's duration, not real-time playback.
                if (measureOnly) break
                if (effective <= 0L || total <= 0L) break
                if (!reverse && played >= effective) break
                if (reverse && played <= 0.0) break
                ts.seekTo((played / total).toFloat().coerceIn(0f, 1f), segmentTarget)
                scrubSettling = wasSettling
            }
            ts.snapTo(to)
        } finally {
            scrubSettling = wasSettling
        }
        return effective
    }

    private suspend fun seekInPlace(segment: Int, fraction: Float) {
        val fromState = states[segment]
        if (segment == states.lastIndex) {
            if (transitionState.currentState != fromState || transitionState.targetState != fromState) {
                transitionState.snapTo(fromState)
            }
            val settled = resolveBackwardValueState(segment)
            currentState = settled
            targetState = settled
            return
        }

        val toState = states[segment + 1]
        if (fraction <= 0f) {
            // Resting at the start of a segment is the settled `from` state (what the audience sees
            // before the click). Targeting the next state at play time 0 is not equivalent: shared
            // bounds have not started yet, so moved elements would already sit at their targets.
            if (transitionState.currentState != fromState || transitionState.targetState != fromState) {
                transitionState.snapTo(fromState)
            }
            currentState = resolveBackwardValueState(segment)
            targetState = if (fromState.frame is Frame.Value) fromState else resolveForwardValueState(segment + 1)
            return
        }
        if (transitionState.currentState != fromState) {
            transitionState.snapTo(fromState)
        }
        currentState = resolveBackwardValueState(segment)
        targetState = resolveForwardValueState(segment + 1)
        if (transitionState.targetState != toState) {
            // Compose the new segment at play time 0 first, exactly like animateTo does. Transition
            // children that get (re)targeted while the play time is non-zero are delayed by that play
            // time (see Transition.TransitionAnimationState.updateAnimation), so nested content that
            // composes a frame or two after a direct seekTo(fraction) would be stuck at its start
            // value and every late child would lag behind live playback. Those play-time-0 frames
            // are not presentable (shared bounds have not started), so the last frame stays up.
            withPresentedFrameFrozen {
                transitionState.seekTo(0f, toState)
                val settle = SettleTracker()
                while (true) {
                    withFrameNanos { }
                    if (settle.isSettled(transition.totalDurationNanos)) break
                }
                transitionState.seekTo(transitionFractionFor(fraction), toState)
            }
            return
        }
        transitionState.seekTo(transitionFractionFor(fraction), toState)
    }

    /** Keeps [Easel] presenting the last frame while [block] runs (see [scrubSettling]). */
    private suspend fun <T> withPresentedFrameFrozen(block: suspend () -> T): T {
        val wasSettling = scrubSettling
        freezePresentedFrame()
        try {
            return block()
        } finally {
            scrubSettling = wasSettling
        }
    }

    /**
     * Plays the segment starting at [fromStateIndex] from [startFraction] to its end in real time,
     * exactly like an arrow-key advance animates it. If the animatic already rests on (or is paused
     * inside) that segment, playback continues from there without rebuilding the scene.
     */
    suspend fun playSegmentToEnd(fromStateIndex: Int, startFraction: Float = 0f) {
        if (states.size < 2) return
        val clampedFrom = fromStateIndex.coerceIn(0, states.lastIndex - 1)
        val fraction = startFraction.coerceIn(0f, 0.999f)
        exclusive {
            val fromState = states[clampedFrom]
            val toState = states[clampedFrom + 1]
            val restingOnFrom = transitionState.currentState == fromState &&
                transitionState.targetState == fromState && fraction == 0f
            val pausedInside = transitionState.currentState == fromState &&
                transitionState.targetState == toState && abs(activeFraction - fraction) < 0.002f
            if (!restingOnFrom && !pausedInside) {
                seekToSegmentLocked(clampedFrom, fraction, rebuildScene = true)
            }
            currentState = resolveBackwardValueState(clampedFrom)
            targetState = resolveForwardValueState(clampedFrom + 1)
            runSegmentBySeeking(toState)
            if (toState.frame is Frame.Value) {
                currentState = toState
                targetState = toState
            }
        }
    }

    private fun resolveBackwardValueState(idx: Int): LinkedState<*> {
        var cursor = idx.coerceIn(0, states.lastIndex)
        while (cursor > 0 && states[cursor].frame !is Frame.Value) cursor--
        return states[cursor]
    }

    private fun resolveForwardValueState(idx: Int): LinkedState<*> {
        var cursor = idx.coerceIn(0, states.lastIndex)
        while (cursor < states.lastIndex && states[cursor].frame !is Frame.Value) cursor++
        return states[cursor]
    }

    // TODO what should the initial values be?
    private var currentState by mutableStateOf(transitionState.currentState)
    private var targetState by mutableStateOf(transitionState.targetState)

    val currentIndex: Storyboard.Index get() = currentState.index
    val targetIndex: Storyboard.Index get() = targetState.index

    /**
     * Advances to the next resting state in [direction], animating every intermediate segment.
     * Pressing again while animating snaps the running advance to its target and continues (or
     * reverses it), exactly like the original navigation model. Segments are animated by seeking
     * (see [runSegmentBySeeking]), so presenting and timeline scrubbing render identical frames.
     */
    suspend fun advance(direction: AdvanceDirection): Boolean = exclusive {
        advanceLocked(direction)
    } ?: true

    private suspend fun advanceLocked(direction: AdvanceDirection): Boolean {
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

            runSegmentBySeeking(transitionState.currentState)
        }

        // Animate to the target frame.
        var iterState = transitionState.currentState
        while (iterState != targetState) {
            iterState = iterState.advance(direction) ?: error("internal error")
            runSegmentBySeeking(iterState)
        }

        currentState = targetState
        return true
    }

    suspend fun jumpTo(index: Storyboard.Index): Boolean = exclusive {
        // TODO a little inefficient?
        var jumpIndex = states.binarySearchBy(index) { it.index }
        if (jumpIndex < 0) return@exclusive false

        // Back up from the search index until a state frame is found.
        // Since the first frame of a storyboard is always a state frame,
        // this loop will never run out of bounds.
        while (states[jumpIndex].frame !is Frame.Value) {
            jumpIndex--
        }

        targetState = states[jumpIndex]
        transitionState.snapTo(targetState)
        currentState = targetState
        true
    } ?: true

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
