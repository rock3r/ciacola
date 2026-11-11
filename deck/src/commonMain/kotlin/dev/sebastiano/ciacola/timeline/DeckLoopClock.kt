package dev.sebastiano.ciacola.timeline

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos

data class LoopSubCompDescriptor(
    val loopId: String,
    val label: String,
    val defaultPeriodMillis: Long,
    val harmonicChannels: List<String> = listOf(
        "Primary Phase (0..2π)",
        "Drift sin(ωt)",
        "Counter-Drift cos(ωt)",
    ),
)

@Stable
class DeckLoopClock {
    var activeSubCompLoopId by mutableStateOf<String?>(null)
    var scrubbedMillis by mutableStateOf<Long?>(null)

    /**
     * When non-null (Timeline Studio open), every loop follows the master playhead like an After
     * Effects pre-comp follows its parent comp: the stage shows exactly the frame at the playhead.
     */
    var masterTimeMillis by mutableStateOf<Long?>(null)
    var isPlaying by mutableStateOf(true)
    val registeredLoops = mutableStateMapOf<String, LoopSubCompDescriptor>()
    val periodOverridesMillis = mutableStateMapOf<String, Long>()

    /** Free-running loop time lives here, not in composition, so scene rebuilds never reset it. */
    internal val freeRunMillis = mutableStateMapOf<String, Long>()

    fun effectivePeriodMillis(loopId: String, defaultPeriodMillis: Long): Long =
        (periodOverridesMillis[loopId] ?: defaultPeriodMillis).coerceAtLeast(200L)

    fun enterSubComp(loopId: String) {
        activeSubCompLoopId = loopId
        isPlaying = false
        if (scrubbedMillis == null) {
            scrubbedMillis = 0L
        }
    }

    fun exitSubComp() {
        activeSubCompLoopId = null
        scrubbedMillis = null
        isPlaying = true
    }
}

val LocalDeckLoopClock = staticCompositionLocalOf { DeckLoopClock() }

/**
 * Scrubbable, pauseable replacement for `rememberInfiniteTransition` that automatically
 * registers an After Effects-style Loop Sub-Composition on [LocalDeckLoopClock].
 */
@Composable
fun rememberDeckLoopMillis(
    loopId: String,
    label: String,
    periodMillis: Long,
    harmonicChannels: List<String> = listOf(
        "Primary Phase (0..2π)",
        "Drift sin(ωt)",
        "Counter-Drift cos(ωt)",
    ),
): Long {
    val clock = LocalDeckLoopClock.current
    val collector = LocalTimelineHarvestCollector.current
    val sceneCtx = LocalActiveSceneTimeline.current
    val effectivePeriod = clock.effectivePeriodMillis(loopId, periodMillis)
    val descriptor = remember(loopId, label, periodMillis, harmonicChannels) {
        LoopSubCompDescriptor(
            loopId = loopId,
            label = label,
            defaultPeriodMillis = periodMillis,
            harmonicChannels = harmonicChannels,
        )
    }
    if (clock.registeredLoops[loopId] != descriptor) {
        clock.registeredLoops[loopId] = descriptor
    }
    if (collector != null) {
        collector.recordLoop(descriptor, sceneCtx?.sceneIndex ?: 0)
    }
    SideEffect {
        if (clock.registeredLoops[loopId] != descriptor) {
            clock.registeredLoops[loopId] = descriptor
        }
        if (collector != null) {
            collector.recordLoop(descriptor, sceneCtx?.sceneIndex ?: 0)
        }
    }

    val isThisSubCompScrubbed =
        (clock.activeSubCompLoopId == null || clock.activeSubCompLoopId == loopId) &&
            clock.scrubbedMillis != null

    if (isThisSubCompScrubbed) {
        val raw = clock.scrubbedMillis ?: 0L
        return ((raw % effectivePeriod) + effectivePeriod) % effectivePeriod
    }

    val master = clock.masterTimeMillis
    if (master != null) {
        return ((master % effectivePeriod) + effectivePeriod) % effectivePeriod
    }

    LaunchedEffect(loopId, clock.isPlaying, effectivePeriod) {
        if (!clock.isPlaying) return@LaunchedEffect
        var lastNanos = -1L
        while (true) {
            withFrameNanos { nowNanos ->
                if (lastNanos >= 0L) {
                    val deltaMs = ((nowNanos - lastNanos) / 1_000_000L).coerceAtLeast(0L)
                    val previous = clock.freeRunMillis[loopId] ?: 0L
                    clock.freeRunMillis[loopId] = (previous + deltaMs) % effectivePeriod
                }
                lastNanos = nowNanos
            }
        }
    }

    return (clock.freeRunMillis[loopId] ?: 0L) % effectivePeriod
}
