package dev.sebastiano.ciacola.document

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Transition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.key
import dev.bnorm.storyboard.Frame
import dev.bnorm.storyboard.Storyboard
import dev.bnorm.storyboard.easel.Animatic
import dev.bnorm.storyboard.easel.effectiveSegmentDurationNanos
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

/**
 * Canonical document segment derived from the root [Animatic] transition or a still preview frame.
 */
@Immutable
sealed interface DocSegment {
    /** Resting on a beat (`beat` in `0 until beatsCount`). */
    @Immutable
    data class Rest(val slideId: String, val beat: Int) : DocSegment

    /** Resting at `Frame.Start` before the slide's beat-0 entrance (`enter` builds still pending). */
    @Immutable
    data class AtStart(val slideId: String) : DocSegment

    /** Active beat segment `fromBeat -> fromBeat + 1` on [slideId]. */
    @Immutable
    data class Beat(val slideId: String, val fromBeat: Int) : DocSegment

    /** Active beat-0 entrance segment `Start -> Value(0)` on [slideId]. */
    @Immutable
    data class Entrance(val slideId: String) : DocSegment

    /** Active cross-slide transition `fromSlideId.End <-> toSlideId.Start`. */
    @Immutable
    data class Cross(val fromSlideId: String, val toSlideId: String) : DocSegment
}

/**
 * Immutable per-frame canonical clock read by every document renderer, transition compositor, and
 * specimen on a mount.
 *
 * - [t] is always in canonical forward orientation (`0f..effMs`) regardless of whether the user
 *   advanced forward, stepped backward, reversed mid-flight, or scrubbed.
 * - [effMs] is the root transition's effective duration in milliseconds (never the plan's raw
 *   `segmentMs`, so mirroring matches what `Animatic` actually ran).
 */
@Immutable
data class DeckClock(
    val segment: DocSegment,
    val t: Float,
    val effMs: Float,
) {
    val isResting: Boolean
        get() = segment is DocSegment.Rest || segment is DocSegment.AtStart

    val isActive: Boolean
        get() = !isResting

    /** Canonical progress `[0f..1f]`, returning `1f` when resting or on a 0-ms segment. */
    fun progress(): Float = if (isResting || effMs <= 0f) 1f else (t / effMs).coerceIn(0f, 1f)

    companion object {
        /**
         * Total mapping from a root [RootSegment] and the ordered list of non-skipped slides onto
         * [DeckClock].
         */
        fun fromRootSegment(
            root: RootSegment,
            nonSkippedSlides: List<SlideSpec>,
        ): DeckClock {
            if (nonSkippedSlides.isEmpty()) {
                return DeckClock(DocSegment.Rest("", 0), 0f, 0f)
            }

            fun restAt(index: Storyboard.Index): DocSegment {
                val slide = nonSkippedSlides[index.sceneIndex.coerceIn(0, nonSkippedSlides.lastIndex)]
                val beatsCount = slide.beats.size.coerceAtLeast(1)
                val lastBeat = beatsCount - 1
                return when {
                    index.frameIndex < 0 -> DocSegment.AtStart(slide.id)
                    index.frameIndex >= beatsCount -> DocSegment.Rest(slide.id, lastBeat)
                    else -> DocSegment.Rest(slide.id, index.frameIndex)
                }
            }

            if (!root.active || root.from == root.to) {
                return DeckClock(segment = restAt(root.to), t = 0f, effMs = 0f)
            }

            val forward = root.to > root.from
            val lo = if (forward) root.from else root.to
            val hi = if (forward) root.to else root.from
            val effMs = root.effMs.coerceAtLeast(0f)
            val rawT = if (forward) root.playMs else effMs - root.playMs
            val canonicalT = if (effMs > 0f) rawT.coerceIn(0f, effMs) else 0f

            if (lo.sceneIndex !in nonSkippedSlides.indices || hi.sceneIndex !in nonSkippedSlides.indices) {
                return DeckClock(segment = restAt(root.to), t = 0f, effMs = 0f)
            }

            if (lo.sceneIndex == hi.sceneIndex) {
                val slide = nonSkippedSlides[lo.sceneIndex]
                val beatsCount = slide.beats.size.coerceAtLeast(1)
                val lastBeat = beatsCount - 1
                return when {
                    lo.frameIndex == -1 && hi.frameIndex == 0 ->
                        DeckClock(DocSegment.Entrance(slide.id), canonicalT, effMs)

                    lo.frameIndex in 0 until lastBeat && hi.frameIndex == lo.frameIndex + 1 ->
                        DeckClock(DocSegment.Beat(slide.id, lo.frameIndex), canonicalT, effMs)

                    // Value(last) <-> End bridge has no content; t is ignored.
                    lo.frameIndex == lastBeat && hi.frameIndex == beatsCount ->
                        DeckClock(DocSegment.Rest(slide.id, lastBeat), 0f, 0f)

                    else -> DeckClock(restAt(root.to), 0f, 0f)
                }
            }

            if (hi.sceneIndex == lo.sceneIndex + 1) {
                val slideA = nonSkippedSlides[lo.sceneIndex]
                val slideB = nonSkippedSlides[hi.sceneIndex]
                val beatsA = slideA.beats.size.coerceAtLeast(1)
                if (lo.frameIndex == beatsA && hi.frameIndex == -1) {
                    return DeckClock(DocSegment.Cross(slideA.id, slideB.id), canonicalT, effMs)
                }
            }

            return DeckClock(segment = restAt(root.to), t = 0f, effMs = 0f)
        }

        /**
         * Still-mount clock constructor for `ScenePreview` / Overview / PDF / lint frames where no
         * `Animatic` root transition exists. A still clock is never active.
         */
        fun fromStillFrame(
            slide: SlideSpec,
            frame: Frame<Int>,
        ): DeckClock {
            val lastBeat = (slide.beats.size - 1).coerceAtLeast(0)
            val segment = when (frame) {
                Frame.Start -> DocSegment.AtStart(slide.id)
                Frame.End -> DocSegment.Rest(slide.id, lastBeat)
                is Frame.Value -> DocSegment.Rest(slide.id, frame.value.coerceIn(0, lastBeat))
            }
            return DeckClock(segment = segment, t = 0f, effMs = 0f)
        }
    }
}

/**
 * Snapshot of the root `Animatic` transition's active segment and play time.
 */
@Immutable
data class RootSegment(
    val from: Storyboard.Index,
    val to: Storyboard.Index,
    val active: Boolean,
    val playMs: Float,
    val effMs: Float,
)

/**
 * Walks up [Transition.parentTransition] to the root transition and extracts a [RootSegment] if
 * and only if the root transition's states are [Animatic.State] instances.
 *
 * Returns `null` on non-Animatic mounts (such as `ScenePreview`). Never performs an unchecked cast.
 */
@Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
fun Transition<*>.rootSegment(): RootSegment? {
    var root: Transition<*> = this
    while (root.parentTransition != null) {
        root = root.parentTransition!!
    }
    val current = root.currentState as? Animatic.State<*> ?: return null
    val target = root.targetState as? Animatic.State<*> ?: return null
    val active = current.index != target.index
    if (!active) {
        return RootSegment(
            from = current.index,
            to = target.index,
            active = false,
            playMs = 0f,
            effMs = 0f,
        )
    }
    val effectiveNanos = root.effectiveSegmentDurationNanos().takeIf { it > 0L } ?: root.totalDurationNanos
    return RootSegment(
        from = current.index,
        to = target.index,
        active = true,
        playMs = root.playTimeNanos / 1_000_000f,
        effMs = effectiveNanos / 1_000_000f,
    )
}

/**
 * Walks [Transition.parentTransition] to the root `Animatic.State<*>` transition and returns the
 * canonical `(earlierSceneIndex, laterSceneIndex)` pair while the root is transitioning across two
 * distinct scenes, or `null` otherwise (lookup for symmetric cross-slide durations).
 */
@Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
fun Transition<*>.rootCrossSlideIndices(): Pair<Int, Int>? {
    var root: Transition<*> = this
    while (root.parentTransition != null) {
        root = root.parentTransition!!
    }
    val current = root.currentState as? Animatic.State<*> ?: return null
    val target = root.targetState as? Animatic.State<*> ?: return null
    val a = current.index.sceneIndex
    val b = target.index.sceneIndex
    if (a == b) return null
    return minOf(a, b) to maxOf(a, b)
}

/**
 * Composition local carrying the mount's current [DeckClock] when bound by the document host.
 */
val LocalDeckClock = compositionLocalOf<DeckClock?> { null }

/**
 * Intra-slide carrier mounted for a scene's intra-slide segments.
 *
 * When mounted under an [Animatic] root transition, attaches its `animateFloat` directly to the
 * root `Transition<Animatic.State<*>>` keyed on `(root.currentState, root.targetState)`. This
 * completely avoids Compose's inverted child-transition state after an in-place drag-to-zero
 * (`seekToSegment(..., 0f, rebuildScene = false)`) or direct `snapTo` jump, where the scene's
 * child `Transition<Frame<Int>>` can stay stuck at `(Value(1), Value(0))` while the root is at
 * rest on `(Value(0), Value(0))`.
 *
 * Maps `Frame.Start -> -1f`, `Frame.Value(i) -> i.toFloat()`, and `Frame.End -> beatCount.toFloat()`
 * so that the beat-0 entrance segment (`Start <-> Value(0)`) has a non-zero carrier delta while
 * `Value(last) <-> End` and cross-slide edges snap in `0 ms`. The target value lambda never reads
 * `targetState`.
 */
@Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
@Composable
fun Transition<out Frame<Int>>.rememberDocIntraCarrier(
    beatCount: Int,
    entranceDurationMs: () -> Int,
    beatSegmentDurationMs: (fromBeat: Int) -> Int,
): State<Float> {
    var root: Transition<*> = this
    while (root.parentTransition != null) {
        root = root.parentTransition!!
    }
    @Suppress("UNCHECKED_CAST")
    val animaticRoot: Transition<Animatic.State<*>>? =
        if (root.currentState is Animatic.State<*> && root.targetState is Animatic.State<*>) {
            root as Transition<Animatic.State<*>>
        } else {
            null
        }
    if (animaticRoot != null) {
        return key(animaticRoot.currentState, animaticRoot.targetState) {
            animaticRoot.animateFloat(
                transitionSpec = {
                    val ms = if (initialState.index.sceneIndex != targetState.index.sceneIndex) {
                        0
                    } else {
                        intraFrameDurationMs(
                            initialFrame = initialState.frame,
                            targetFrame = targetState.frame,
                            entranceDurationMs = entranceDurationMs,
                            beatSegmentDurationMs = beatSegmentDurationMs,
                        )
                    }
                    if (ms > 0) tween(ms, easing = LinearEasing) else snap()
                },
                label = "doc-intra-carrier",
            ) { state ->
                val frameOffset = when (val f = state.frame) {
                    Frame.Start -> -1f
                    is Frame.Value -> (f.value as? Int)?.toFloat() ?: 0f
                    Frame.End -> beatCount.toFloat()
                }
                state.index.sceneIndex * 10_000f + frameOffset
            }
        }
    }
    return key(currentState, targetState) {
        animateFloat(
            transitionSpec = {
                val ms = intraFrameDurationMs(
                    initialFrame = initialState,
                    targetFrame = targetState,
                    entranceDurationMs = entranceDurationMs,
                    beatSegmentDurationMs = beatSegmentDurationMs,
                )
                if (ms > 0) tween(ms, easing = LinearEasing) else snap()
            },
            label = "doc-intra-carrier",
        ) { frame ->
            when (frame) {
                Frame.Start -> -1f
                is Frame.Value -> frame.value.toFloat()
                Frame.End -> beatCount.toFloat()
            }
        }
    }
}

private fun intraFrameDurationMs(
    initialFrame: Frame<*>,
    targetFrame: Frame<*>,
    entranceDurationMs: () -> Int,
    beatSegmentDurationMs: (fromBeat: Int) -> Int,
): Int = when {
    initialFrame is Frame.End || targetFrame is Frame.End -> 0
    (initialFrame is Frame.Start && targetFrame is Frame.Value) ||
        (initialFrame is Frame.Value && targetFrame is Frame.Start) -> entranceDurationMs()

    initialFrame is Frame.Value && targetFrame is Frame.Value -> {
        val a = initialFrame.value as? Int
        val b = targetFrame.value as? Int
        if (a != null && b != null && abs(a - b) == 1) beatSegmentDurationMs(minOf(a, b)) else 0
    }

    else -> 0
}

/**
 * Cross-slide carrier mounted on each scene's [AnimatedVisibilityScope.transition].
 *
 * Reads the canonical `(earlierSceneIndex, laterSceneIndex)` pair from the root `Animatic`
 * transition via [rootCrossSlideIndices] so that exiting `S1 -> S0` backward uses `S0 -> S1`'s
 * transition duration rather than `S1 -> S2`'s duration.
 *
 * Keyed on `(currentState, targetState)` so a fresh `TransitionAnimationState` (`isFinished = true`)
 * is allocated per transition edge, preventing Compose's `SeekableTransitionState` interruption
 * fallback (`spring()`, ~171 ms) from overriding `snap()` (0-ms cuts) or short `< 171 ms` tweens
 * (e.g. 150-ms reduced-motion transitions) on an already-mounted exiting scene.
 */
@Composable
context(animatedVisibilityScope: AnimatedVisibilityScope)
fun rememberDocCrossCarrier(
    crossSlideDurationMs: (earlierSceneIndex: Int, laterSceneIndex: Int) -> Int,
): State<Float> {
    val t = animatedVisibilityScope.transition
    return key(t.currentState, t.targetState) {
        t.animateFloat(
            transitionSpec = {
                val pair = t.rootCrossSlideIndices()
                val ms = if (pair != null) crossSlideDurationMs(pair.first, pair.second) else 0
                if (ms > 0) tween(ms, easing = LinearEasing) else snap()
            },
            label = "doc-cross-carrier",
        ) { state ->
            when (state) {
                EnterExitState.PreEnter -> 0f
                EnterExitState.Visible -> 1f
                EnterExitState.PostExit -> 2f
            }
        }
    }
}

/**
 * Computes the document structure key: ordered `(slideId, beatCount)` for non-skipped
 * slides. A `Storyboard` is rebuilt if and only if this structure key changes.
 */
@Immutable
data class DeckStructureKey(
    val slides: List<SlideStructureEntry>,
) {
    @Immutable
    data class SlideStructureEntry(
        val slideId: String,
        val beatCount: Int,
    )

    companion object {
        fun from(document: DeckDocument): DeckStructureKey = DeckStructureKey(
            slides = document.slides
                .filterNot { it.skipped }
                .map { SlideStructureEntry(it.id, it.beats.size.coerceAtLeast(1)) },
        )
    }
}

/**
 * Resolves navigation restoration after a structural edit:
 * - Same `slideId` and beat (clamped) if the slide still exists and is non-skipped.
 * - Else nearest following slide that existed after the old position (or the new slide at the same
 *   index), else the previous one, with beat clamped to `0`.
 */
fun restoreNavigationIndex(
    previousNonSkippedSlides: List<SlideSpec>,
    previousSlideId: String,
    previousBeat: Int,
    nextNonSkippedSlides: List<SlideSpec>,
): Storyboard.Index {
    if (nextNonSkippedSlides.isEmpty()) return Storyboard.Index(0, 0)

    val sameIdx = nextNonSkippedSlides.indexOfFirst { it.id == previousSlideId }
    if (sameIdx >= 0) {
        val maxBeat = (nextNonSkippedSlides[sameIdx].beats.size - 1).coerceAtLeast(0)
        return Storyboard.Index(sameIdx, previousBeat.coerceIn(0, maxBeat))
    }

    val prevIdx = previousNonSkippedSlides.indexOfFirst { it.id == previousSlideId }
    if (prevIdx >= 0) {
        // Look for the nearest following slide from the previous deck that survived.
        for (i in (prevIdx + 1) until previousNonSkippedSlides.size) {
            val candidateId = previousNonSkippedSlides[i].id
            val nextIdx = nextNonSkippedSlides.indexOfFirst { it.id == candidateId }
            if (nextIdx >= 0) return Storyboard.Index(nextIdx, 0)
        }
        // Else look for the nearest preceding slide that survived.
        for (i in (prevIdx - 1) downTo 0) {
            val candidateId = previousNonSkippedSlides[i].id
            val nextIdx = nextNonSkippedSlides.indexOfFirst { it.id == candidateId }
            if (nextIdx >= 0) return Storyboard.Index(nextIdx, 0)
        }
    }

    val clampedScene = prevIdx.coerceIn(0, nextNonSkippedSlides.lastIndex)
    return Storyboard.Index(clampedScene, 0)
}

/**
 * Auto-advance controller:
 * - Fires `autoAdvanceMs` after arriving at rest on a beat **by a forward advance**.
 * - Never fires on arrival by jump, scrub, seek, or backward navigation.
 * - Any navigation cancels a pending auto-advance.
 * - Holds at most one pending job keyed on [forwardArrivalToken].
 * - Inactive while [isScrubbing] is `true`.
 */
@Stable
class AutoAdvanceController(
    private val scope: CoroutineScope,
    private val onAdvanceForward: suspend () -> Unit,
) {
    var forwardArrivalToken: Long = 0L
        private set

    var isScrubbing: Boolean = false
        set(value) {
            field = value
            if (value) cancelPending()
        }

    val hasPendingAdvance: Boolean
        get() = pendingJob?.isActive == true

    private var pendingJob: Job? = null

    /**
     * Records a forward-advance arrival at rest. Increments [forwardArrivalToken] and schedules
     * a single pending auto-advance when [autoAdvanceMs] is non-null (`>= 0`) and not scrubbing.
     */
    fun onForwardAdvanceArrived(autoAdvanceMs: Int?) {
        cancelPending()
        forwardArrivalToken++
        if (isScrubbing || autoAdvanceMs == null || autoAdvanceMs < 0) return
        val expectedToken = forwardArrivalToken
        var scheduledJob: Job? = null
        scheduledJob = scope.launch {
            if (autoAdvanceMs > 0) {
                delay(autoAdvanceMs.toLong())
            } else {
                yield()
            }
            if (!isScrubbing && forwardArrivalToken == expectedToken) {
                if (pendingJob === scheduledJob) {
                    pendingJob = null
                }
                onAdvanceForward()
            }
        }
        pendingJob = scheduledJob
    }

    /**
     * Records any non-forward navigation (jump, scrub, seek, backward step, or start of a new
     * transition). Cancels any pending auto-advance without incrementing [forwardArrivalToken].
     */
    fun onOtherNavigation() {
        cancelPending()
    }

    fun cancelPending() {
        pendingJob?.cancel()
        pendingJob = null
    }
}
