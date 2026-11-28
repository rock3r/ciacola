package dev.sebastiano.ciacola.document

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Playback state of an animated `image` node derived from its `playback` builds at one beat.
 *
 * @property playing whether the clip runs while this beat rests.
 * @property rate playback speed multiplier.
 * @property anchorMs source position the clip shows when the beat is reached without a recorded
 *   entry position (first visit, jump, or slide re-entry).
 * @property pinnedAtBeat a build on exactly this beat sets the position, so even a forward step
 *   jumps to [anchorMs] instead of continuing.
 * @property showPoster no `play`, `seek` or positioned `pause` has happened yet: the poster frame
 *   is shown while the playhead waits at [anchorMs].
 */
@Immutable
data class ClipBeatState(
    val playing: Boolean,
    val rate: Double,
    val anchorMs: Int,
    val pinnedAtBeat: Boolean,
    val showPoster: Boolean,
)

/** Pure beat-timeline rules for animated image playback. */
object ClipPlayback {
    /** Beat index used before a slide's beat-0 entrance (or while a slide is still arriving). */
    const val BEFORE_FIRST_BEAT: Int = -1

    /** Derives the [ClipBeatState] of [image] (with its node [builds]) at [beat]. */
    fun stateAtBeat(image: ImagePayload, builds: List<Build>, beat: Int): ClipBeatState {
        val playbacks = builds.filterIsInstance<Build.Playback>()
        val start = image.startMs ?: 0
        if (playbacks.isEmpty()) {
            // No playback builds: the clip plays as soon as its slide is shown.
            return ClipBeatState(
                playing = beat >= 0,
                rate = image.playbackRate,
                anchorMs = start,
                pinnedAtBeat = false,
                showPoster = beat < 0,
            )
        }
        var playing = false
        var rate = image.playbackRate
        var anchor = start
        var pinned = false
        var poster = true
        for (build in playbacks.sortedBy { it.beat }) {
            if (build.beat > beat) break
            val here = build.beat == beat
            build.rate?.let { rate = it }
            when (build.action) {
                PlaybackAction.Play -> {
                    playing = true
                    poster = false
                    build.atMs?.let { anchor = it; if (here) pinned = true }
                }
                PlaybackAction.Pause -> {
                    playing = false
                    build.atMs?.let { anchor = it; poster = false; if (here) pinned = true }
                }
                PlaybackAction.Seek -> {
                    anchor = build.atMs ?: start
                    poster = false
                    if (here) pinned = true
                }
                PlaybackAction.Rate -> Unit
            }
        }
        return ClipBeatState(playing, rate, anchor, pinned, poster)
    }

    /**
     * Maps a [clock] onto the beat whose playback state [slideId] shows: the target beat while a
     * forward beat segment runs, [BEFORE_FIRST_BEAT] before the entrance and while the slide is
     * still arriving through a cross-slide transition, and `null` when [slideId] is not involved.
     */
    fun effectiveBeat(clock: DeckClock, slideId: String, lastBeat: Int): Int? =
        when (val segment = clock.segment) {
            is DocSegment.Rest -> if (segment.slideId == slideId) segment.beat else null
            is DocSegment.AtStart -> if (segment.slideId == slideId) BEFORE_FIRST_BEAT else null
            is DocSegment.Entrance -> if (segment.slideId == slideId) 0 else null
            is DocSegment.Beat -> if (segment.slideId == slideId) segment.fromBeat + 1 else null
            is DocSegment.Cross -> when (slideId) {
                segment.toSlideId -> BEFORE_FIRST_BEAT
                segment.fromSlideId -> lastBeat
                else -> null
            }
        }
}

/** Trim window and loop flag of one decoded clip, in source milliseconds. */
@Immutable
data class ClipWindow(val startMs: Double, val endMs: Double, val loop: Boolean) {
    val lengthMs: Double get() = endMs - startMs

    fun clamp(ms: Double): Double = ms.coerceIn(startMs, endMs)

    companion object {
        /** Resolves [image]'s trim against a decoded clip of [durationMs]. */
        fun of(image: ImagePayload, durationMs: Int): ClipWindow {
            val duration = durationMs.coerceAtLeast(1).toDouble()
            val end = (image.endMs?.toDouble() ?: duration).coerceIn(0.0, duration)
            val start = (image.startMs?.toDouble() ?: 0.0).coerceIn(0.0, end)
            return ClipWindow(start, end, image.loop)
        }
    }
}

/**
 * Live playhead of one animated image node, retained per render session so stepping back to a
 * beat restores the position it was first entered at, and the next forward step replays the same
 * segment.
 */
@Stable
class ClipPlayhead {
    var positionMs: Double by mutableDoubleStateOf(0.0)
        private set
    var playing: Boolean by mutableStateOf(false)
        private set
    var rate: Double by mutableDoubleStateOf(1.0)
        private set
    var showPoster: Boolean by mutableStateOf(true)
        private set

    /** Beat currently applied, or `null` when the node is not composed. */
    var currentBeat: Int? = null
        private set

    private var currentState: ClipBeatState? = null
    private val entryPositions = HashMap<Int, Double>()

    /** Set by [hold]: the frame stays put, and the next beat at or after zero counts as a fresh arrival. */
    private var held = false

    /**
     * Applies [state] for [beat]. An adjacent forward step continues from the live position unless
     * the beat pins one; any other arrival restores the position recorded when [beat] was last
     * entered forward, falling back to the derived anchor. Going from a shown beat back to
     * [ClipPlayback.BEFORE_FIRST_BEAT] (stepping backward off the slide) [hold]s the frame instead
     * of jumping to the poster.
     */
    fun enterBeat(beat: Int, state: ClipBeatState, window: ClipWindow) {
        val previous = currentBeat
        if (beat < 0 && (held || (previous != null && previous >= 0))) {
            hold()
            return
        }
        held = false
        if (previous == beat && currentState == state) return
        val forwardStep = previous != null && beat == previous + 1
        val position = when {
            state.pinnedAtBeat -> state.anchorMs.toDouble()
            forwardStep -> positionMs
            else -> entryPositions[beat] ?: state.anchorMs.toDouble()
        }
        if (forwardStep || previous == null) entryPositions[beat] = window.clamp(position)
        apply(beat, state, window.clamp(position))
    }

    /** Replays the current beat from its entry position without changing beat (the `R` key). */
    fun restart(window: ClipWindow) {
        val beat = currentBeat?.takeIf { it >= 0 } ?: return
        val state = currentState ?: return
        val position = if (state.pinnedAtBeat) state.anchorMs.toDouble() else entryPositions[beat] ?: state.anchorMs.toDouble()
        apply(beat, state, window.clamp(position))
    }

    /** `true` while the mouse button is held on the clip: the frame under the pointer stays put. */
    var scrubbing: Boolean by mutableStateOf(false)
        private set

    /** Moves the playhead to [ms] (mouse scrubbing); the playing state is kept. */
    fun seek(ms: Double, window: ClipWindow) {
        positionMs = window.clamp(ms)
        showPoster = false
    }

    /** Freezes playback while the mouse button is held, so a scrubbed frame can be shown. */
    fun beginScrub() {
        scrubbing = true
    }

    /** Releases the mouse button: playback carries on from the scrubbed position. */
    fun endScrub() {
        scrubbing = false
    }

    /**
     * Pauses a playing clip or resumes a paused one (the show-mode `P` key). Resuming a clip held
     * on its last frame plays it again from the trim start.
     */
    fun togglePause(window: ClipWindow) {
        if ((currentBeat ?: return) < 0) return
        val heldAtEnd = !window.loop && positionMs >= window.endMs
        if (playing && !showPoster && !heldAtEnd) {
            playing = false
            return
        }
        if (heldAtEnd) positionMs = window.startMs
        playing = true
        showPoster = false
    }

    /** Advances by [elapsedMs] of wall time; non-looping clips hold their last frame at the end. */
    fun advance(elapsedMs: Double, window: ClipWindow) {
        if (!playing || showPoster || scrubbing || elapsedMs <= 0.0 || rate <= 0.0) return
        val next = positionMs + elapsedMs * rate
        positionMs = when {
            next < window.endMs -> next
            window.loop && window.lengthMs > 0.0 -> window.startMs + (next - window.startMs) % window.lengthMs
            else -> window.endMs
        }
    }

    /**
     * Stops on the frame shown now, for a node that is hidden (before its `enter`, after its
     * `exit`) or a slide being left backward. The next shown beat is entered as a fresh arrival.
     */
    fun hold() {
        playing = false
        held = true
        currentBeat = null
        currentState = null
    }

    /** Marks the node as no longer composed, so its next appearance counts as a fresh arrival. */
    fun detach() {
        held = false
        currentBeat = null
        currentState = null
    }

    private fun apply(beat: Int, state: ClipBeatState, position: Double) {
        currentBeat = beat
        currentState = state
        positionMs = position
        playing = state.playing
        rate = state.rate
        showPoster = state.showPoster
    }
}

/** Process-wide request to restart the clips on the slide being shown (the show-mode `R` key). */
object DeckClipControls {
    var restartEpoch: Int by mutableIntStateOf(0)
        private set

    /** Bumped by the show-mode `P` key: clips on the shown slide pause or resume. */
    var pauseEpoch: Int by mutableIntStateOf(0)
        private set

    fun requestTogglePause() {
        pauseEpoch++
    }

    fun requestRestart() {
        restartEpoch++
    }
}
