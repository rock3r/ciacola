package dev.sebastiano.ciacola.timeline

import dev.bnorm.storyboard.Storyboard
import java.util.Locale

/** Visual rest between beats: the deck waits for a click here (not part of real playback time). */
internal const val CLICK_HOLD_MS = 400

data class SlideSectionBar(
    val sceneIndex: Int,
    val label: String,
    val startMs: Int,
    val endMs: Int,
    val overlapInMs: Int,
    val overlapOutMs: Int,
    val beatCount: Int,
)

data class EventBeatLine(
    val beatGlobalIndex: Int,
    val stateIndex: Int,
    val sceneIndex: Int,
    val frameIndex: Int,
    val timeMs: Int,
    val segmentDurationMs: Int,
    val isSlideBoundary: Boolean,
    val isInjected: Boolean,
)

data class TimelineBlockBar(
    val id: String,
    val trackId: String,
    val label: String,
    val category: TrackCategory,
    val sceneIndex: Int,
    val endSceneIndex: Int = sceneIndex,
    val triggerBeatInScene: Int,
    val startMs: Int,
    val durationMs: Int,
    val delayMs: Int = 0,
    val staggerIndex: Int? = null,
    val staggerCount: Int? = null,
    val staggerStepMs: Int? = null,
    val morphDurationMs: Int? = null,
    val loopId: String? = null,
    val loopPeriodMs: Long? = null,
    val sharedKey: String? = null,
    val defaultOffsetXDp: Float = 0f,
    val defaultOffsetYDp: Float = 0f,
)

data class TimelineTrackRow(
    val trackId: String,
    val label: String,
    val category: TrackCategory,
    val sceneIndex: Int?,
    val indentLevel: Int = 0,
    val blocks: List<TimelineBlockBar>,
)

data class StateTimeSpan(
    val stateIndex: Int,
    val sceneIndex: Int,
    val frameIndex: Int,
    val startMs: Int,
    val durationMs: Int,
    val isBoundary: Boolean,
    /** A click hold: the deck rests on Value state [stateIndex] (fraction 0) for the whole span. */
    val isHold: Boolean = false,
)

data class DeckTimelineSnapshot(
    val totalDurationMs: Int,
    val slides: List<SlideSectionBar>,
    val eventBeats: List<EventBeatLine>,
    val tracks: List<TimelineTrackRow>,
    val stateSpans: List<StateTimeSpan> = emptyList(),
) {
    fun stateFractionForTimeMs(timeMs: Int): Pair<Int, Float> {
        if (stateSpans.isEmpty()) return 0 to 0f
        val clamped = timeMs.coerceIn(0, totalDurationMs.coerceAtLeast(1))
        for (span in stateSpans) {
            if (span.durationMs <= 0 && span != stateSpans.last()) continue
            val end = span.startMs + span.durationMs
            if (clamped < end || span == stateSpans.last()) {
                if (span.isHold) return span.stateIndex to 0f
                val frac = if (span.durationMs <= 0) {
                    0f
                } else {
                    ((clamped - span.startMs).toFloat() / span.durationMs.toFloat()).coerceIn(0f, 1f)
                }
                return span.stateIndex to frac
            }
        }
        return stateSpans.last().stateIndex to 0f
    }

    /**
     * Time of `(stateIndex, fraction)`. A settled Value state (`fraction == 0`) maps to the start
     * of its click hold, matching its [EventBeatLine.timeMs].
     */
    fun timeMsForStateFraction(stateIndex: Int, fraction: Float): Int {
        if (stateSpans.isEmpty()) return 0
        val clampedFrac = fraction.coerceIn(0f, 1f)
        if (clampedFrac <= 0f) {
            val hold = stateSpans.firstOrNull { it.stateIndex == stateIndex && it.isHold }
            if (hold != null) return hold.startMs
        }
        val segment = stateSpans.firstOrNull { it.stateIndex == stateIndex && !it.isHold }
        if (segment != null) {
            return segment.startMs + (clampedFrac * segment.durationMs).toInt()
        }
        val hold = stateSpans.lastOrNull { it.stateIndex == stateIndex && it.isHold }
        if (hold != null) return hold.startMs
        val fallback = stateSpans.lastOrNull { it.stateIndex <= stateIndex } ?: stateSpans.first()
        return fallback.startMs + fallback.durationMs
    }
}

/**
 * Formats the transport timecode & `S_:B_` location badge from the playhead's active [StateTimeSpan].
 */
fun formatTimelineLocationBadge(
    snapshot: DeckTimelineSnapshot,
    timeMs: Int,
    activeSubLoopId: String?,
    fallbackIndex: Storyboard.Index,
    sceneBeatsCountProvider: (sceneIndex: Int) -> Int,
): String {
    val safeMs = timeMs.coerceAtLeast(0)
    val seconds = safeMs / 1000
    val millis = safeMs % 1000
    val formattedTime = String.format(Locale.US, "%02d.%03ds", seconds, millis)
    if (activeSubLoopId != null) {
        return "$formattedTime · Loop"
    }
    val spans = snapshot.stateSpans
    if (spans.isEmpty()) {
        val beats = sceneBeatsCountProvider(fallbackIndex.sceneIndex).coerceAtLeast(1)
        return "$formattedTime · S${fallbackIndex.sceneIndex + 1}:B${fallbackIndex.frameIndex + 1}/$beats"
    }
    val clampedMs = safeMs.coerceIn(0, snapshot.totalDurationMs.coerceAtLeast(1))
    val span = spans.firstOrNull { s ->
        (s.durationMs > 0 || s == spans.last()) &&
            (clampedMs < s.startMs + s.durationMs || s == spans.last())
    } ?: spans.last()

    val beatCount = sceneBeatsCountProvider(span.sceneIndex).coerceAtLeast(1)
    val locationPart = when {
        span.isHold -> "S${span.sceneIndex + 1}:B${span.frameIndex + 1}/$beatCount"
        span.isBoundary && span.frameIndex < 0 -> {
            val fromScene = (span.sceneIndex - 1).coerceAtLeast(0)
            val fromBeat = sceneBeatsCountProvider(fromScene).coerceAtLeast(1)
            "S${fromScene + 1}:B$fromBeat → S${span.sceneIndex + 1}:B1"
        }
        span.isBoundary || span.frameIndex + 1 >= beatCount -> {
            val toScene = (span.sceneIndex + 1).coerceAtMost((snapshot.slides.size - 1).coerceAtLeast(span.sceneIndex))
            if (toScene != span.sceneIndex) {
                "S${span.sceneIndex + 1}:B$beatCount → S${toScene + 1}:B1"
            } else {
                "S${span.sceneIndex + 1}:B${span.frameIndex + 1}/$beatCount"
            }
        }
        else -> "S${span.sceneIndex + 1}:B${span.frameIndex + 1} → B${span.frameIndex + 2}"
    }
    return "$formattedTime · $locationPart"
}
