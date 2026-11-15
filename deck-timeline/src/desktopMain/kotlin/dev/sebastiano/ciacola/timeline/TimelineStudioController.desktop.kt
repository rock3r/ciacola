package dev.sebastiano.ciacola.timeline

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import dev.bnorm.storyboard.AdvanceDirection
import dev.bnorm.storyboard.Storyboard
import dev.bnorm.storyboard.easel.Animatic
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.nio.file.Path
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

enum class TimelineBakeStatus {
    Idle,
    Sending,
    Sent,
    Error,
}

/** Filter applied to the Timeline Studio multi-track list and canvas. */
enum class TimelineTrackFilter {
    All,
    CurrentSlide,
}

/**
 * Quiet time after an override edit before the deck is re-indexed. Re-indexing runs offscreen
 * scenes on the UI thread (~200 ms), so bursts of stepper clicks or slider moves are coalesced.
 */
private const val INDEX_DEBOUNCE_MS = 300L

/** Threshold above one 60 Hz frame (~16.67 ms) for flagging an in-place duration mismatch. */
private const val DURATION_TRIPWIRE_THRESHOLD_MS = 17L

/**
 * Whether a scrub step to `(stateIndex, fraction)` can seek in place: it stays strictly inside the
 * segment the stage already shows, so no transition is retargeted and no scene rebuild is needed.
 */
fun scrubStepIsInPlace(activeFrom: Int, activeTo: Int, stateIndex: Int, fraction: Float): Boolean =
    fraction > 0f && fraction < 1f && activeFrom == stateIndex && activeTo == stateIndex + 1

/** A requested playhead position, already mapped to an Animatic segment. */
internal data class ScrubRequest(
    val timeMs: Int,
    val stateIndex: Int,
    val fraction: Float,
    val sequence: Long,
    val forceRebuild: Boolean = false,
)

/**
 * Computes the `[startMs, endMs]` playback window for Inspector `Preview ▶`, stopping cleanly at
 * `min(segmentEnd, blockEnd + 120 ms)` so preview never runs through the trailing 400 ms click hold.
 */
fun computeBlockPreviewRangeMs(
    snapshot: DeckTimelineSnapshot,
    block: TimelineBlockBar,
): IntRange {
    val blockStart = block.startMs
    val blockTriggerStart = (blockStart - block.delayMs).coerceAtLeast(0)
    val blockEnd = blockStart + block.durationMs
    val containingAnimated = snapshot.stateSpans.lastOrNull {
        !it.isHold &&
            it.durationMs > 0 &&
            it.startMs <= blockEnd &&
            (it.startMs + it.durationMs) >= blockTriggerStart
    }
    val segmentEnd = containingAnimated?.let { it.startMs + it.durationMs } ?: snapshot.totalDurationMs
    val startMs = (blockTriggerStart - 120).coerceAtLeast(0)
    val maxEnd = snapshot.totalDurationMs.coerceAtLeast(startMs + 1)
    val endMs = minOf(segmentEnd, blockEnd + 120).coerceIn(startMs + 1, maxEnd)
    return startMs..endMs
}

/**
 * Presenter/coordinator for Ciacola's After Effects Timeline Studio.
 *
 * Owns timeline snapshot indexing, playhead scrubbing, transport playback, 0-ms event-beat
 * insertion/deletion, loop sub-composition drill-down, selection, zoom/pan, and live overrides.
 */
@Stable
class TimelineStudioController(
    initialAnimatic: Animatic,
    overridesStore: TimelineOverridesStore,
    loopClock: DeckLoopClock,
    initialBakeDispatcher: AgentBakeDispatcher? = null,
    initialBakeWorkspaceRoot: Path = overridesStore.storyboardDir.toAbsolutePath().parent ?: Path.of("").toAbsolutePath(),
    initialBakeGradleTaskHint: String = ":deck:reload",
    val motionPathPreview: MotionPathPreviewState = MotionPathPreviewState(),
    val stageSelection: TimelineStageSelection = TimelineStageSelection(),
) {
    var overridesStore: TimelineOverridesStore by mutableStateOf(overridesStore)
        internal set
    var loopClock: DeckLoopClock by mutableStateOf(loopClock)
        internal set
    var bakeDispatcher: AgentBakeDispatcher? by mutableStateOf(initialBakeDispatcher)
        internal set
    var bakeWorkspaceRoot: Path = initialBakeWorkspaceRoot
        internal set
    var bakeGradleTaskHint: String = initialBakeGradleTaskHint
        internal set
    var animatic: Animatic by mutableStateOf(initialAnimatic)
        internal set

    val viewport = TimelineViewportState()
    val preview = StagePreviewViewportState()

    var dockedInStoryWindow by mutableStateOf(
        System.getenv("CIACOLA_TIMELINE_OPEN")?.equals("true", ignoreCase = true) == true
    )
    var detachedWindowVisible by mutableStateOf(false)

    /** Authoring mode: the timeline is visible (docked or detached). */
    val authoringActive: Boolean get() = dockedInStoryWindow || detachedWindowVisible

    var splitFraction by mutableFloatStateOf(0.56f)

    var trackFilter by mutableStateOf(TimelineTrackFilter.All)

    /**
     * Visible-for-testing tripwire counter incremented whenever an in-place scrub seek observes an
     * active segment duration diverging from the indexed [StateTimeSpan] duration by more than one
     * frame (17 ms). Never auto-rebuilds.
     */
    var durationTripwireCount by mutableIntStateOf(0)
        internal set

    /**
     * Tracks visible in the layer header list and multi-track canvas under [trackFilter].
     */
    fun visibleTracks(activeSceneIndex: Int = animatic.presentedIndex.sceneIndex): List<TimelineTrackRow> =
        when (trackFilter) {
            TimelineTrackFilter.All -> snapshot.tracks
            TimelineTrackFilter.CurrentSlide -> snapshot.tracks.filter { row ->
                row.sceneIndex == activeSceneIndex ||
                    (row.category == TrackCategory.SharedElement &&
                        row.blocks.any { activeSceneIndex in it.sceneIndex..it.endSceneIndex }) ||
                    (row.sceneIndex == null &&
                        row.blocks.any { activeSceneIndex in it.sceneIndex..it.endSceneIndex })
            }
        }

    internal fun resetForDeckSwitch(
        nextOverridesStore: TimelineOverridesStore,
        nextLoopClock: DeckLoopClock,
    ) {
        stopPlayback()
        exitLoopSubComp()
        selectTrack(null)
        lastRequested = null
        overridesStore = nextOverridesStore
        loopClock = nextLoopClock
        syncLoopPeriodsFromStore()
    }

    /** Canonical selected track (stagger items resolve to their group) for the Inspector. */
    var selectedTrackId by mutableStateOf<String?>(null)
        private set
    var onTrackSelected: ((String?) -> Unit)? = null

    /** Exact selected track id, including a stagger item's `#itemN` suffix. */
    var selectedExactTrackId by mutableStateOf<String?>(null)
        private set

    var isPlaying by mutableStateOf(false)
        private set

    var bakeStatus by mutableStateOf(TimelineBakeStatus.Idle)
        private set

    var bakeStatusMessage by mutableStateOf<String?>(null)
        private set

    var copiedConfirmationVisible by mutableStateOf(false)
        private set

    private var copiedFlashJob: Job? = null

    var conversationIdProvider: (() -> String)? = null

    var clipboardWriter: (String) -> Unit = { text ->
        runCatching {
            Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
        }
    }

    /** Space is held: drags pan (AE hand tool); a tap without dragging toggles playback. */
    var spaceHeld by mutableStateOf(false)
        internal set
    internal var spaceUsedForPan = false

    var snapshot by mutableStateOf(DeckTimelineSnapshot(340, emptyList(), emptyList(), emptyList()))
        internal set

    private var playbackJob: Job? = null
    private var scrubSequence = 0L
    private var appliedScrubSequence by mutableLongStateOf(0L)
    private val scrubRequests = MutableStateFlow<ScrubRequest?>(null)
    private var lastRequested by mutableStateOf<ScrubRequest?>(null)
    var isScrubDragging by mutableStateOf(false)
        private set

    /** True while [scrubToTimeMs] has queued a request not yet finished by [runScrubWorker]. */
    val hasPendingScrubRequest: Boolean
        get() = appliedScrubSequence < scrubSequence

    fun beginScrubDrag() {
        isScrubDragging = true
    }

    fun endScrubDrag() {
        isScrubDragging = false
        val req = lastRequested
        if (req != null && loopClock.activeSubCompLoopId == null) {
            val settleReq = req.copy(sequence = ++scrubSequence, forceRebuild = true)
            lastRequested = settleReq
            scrubRequests.value = settleReq
        }
    }

    init {
        syncLoopPeriodsFromStore()
        stageSelection.onPick = { trackId ->
            selectTrack(trackId)
            if (trackId != null) viewport.pendingRevealTrackId = trackId
        }
    }

    fun syncLoopPeriodsFromStore() {
        val persisted = overridesStore.data().loopPeriodsMs
        val toRemove = loopClock.periodOverridesMillis.keys.filter { it !in persisted }
        toRemove.forEach { loopClock.periodOverridesMillis.remove(it) }
        for ((loopId, periodMs) in persisted) {
            if (loopClock.periodOverridesMillis[loopId] != periodMs) {
                loopClock.periodOverridesMillis[loopId] = periodMs
            }
        }
    }

    fun updateLoopPeriod(loopId: String, periodMs: Long) {
        val clamped = periodMs.coerceIn(500L, 30_000L)
        loopClock.periodOverridesMillis[loopId] = clamped
        overridesStore.updateLoopPeriod(loopId, clamped)
    }

    fun resetLoopPeriod(loopId: String) {
        loopClock.periodOverridesMillis.remove(loopId)
        overridesStore.resetLoopPeriod(loopId)
    }

    fun resetAllOverrides() {
        loopClock.periodOverridesMillis.clear()
        overridesStore.resetAll()
    }

    fun undo() {
        if (overridesStore.undo()) {
            syncLoopPeriodsFromStore()
        }
    }

    fun redo() {
        if (overridesStore.redo()) {
            syncLoopPeriodsFromStore()
        }
    }

    fun copyOverridesAsKotlinSnippet(scope: CoroutineScope): String {
        val snippet = overridesStore.formatOverridesAsKotlinSnippet()
        clipboardWriter(snippet)
        copiedConfirmationVisible = true
        copiedFlashJob?.cancel()
        copiedFlashJob = scope.launch {
            delay(1_800L)
            copiedConfirmationVisible = false
        }
        return snippet
    }

    fun isPlayheadOnInjectedVirtualBeat(): Boolean {
        if (loopClock.activeSubCompLoopId != null) return false
        val idx = animatic.presentedIndex
        val rawFrame = animatic.storyboard.scenes.getOrNull(idx.sceneIndex)?.frames?.getOrNull(idx.frameIndex)
        if (rawFrame is VirtualizedFrame<*>) {
            return rawFrame.isInjected
        }
        val beatOvr = overridesStore.data().sceneBeats[idx.sceneIndex] ?: return false
        return resolveVirtualBeatToBaseBeat(beatOvr, idx.frameIndex).second
    }

    val currentPlayheadTimeMs: Int
        get() {
            if (loopClock.activeSubCompLoopId != null) {
                return (loopClock.scrubbedMillis ?: 0L).toInt()
            }
            val from = animatic.activeFromStateIndex
            val fraction = animatic.activeFraction
            // Keep the playhead locked 1:1 to the requested scrub position both while the scrub
            // worker is applying the seek (so the red line never snaps back to a beat diamond mid-seek)
            // and while resting at the position that request mapped to (e.g. inside a click hold).
            val requested = lastRequested
            if (requested != null) {
                if (isScrubDragging || appliedScrubSequence < requested.sequence) {
                    return requested.timeMs
                }
                val (expectedState, expectedFraction) = normalized(requested.stateIndex, requested.fraction)
                if (from == expectedState && abs(fraction - expectedFraction) < 0.005f) {
                    return requested.timeMs
                }
            }
            return snapshot.timeMsForStateFraction(stateIndex = from, fraction = fraction)
        }

    private fun normalized(stateIndex: Int, fraction: Float): Pair<Int, Float> {
        val last = animatic.stateDescriptors.lastIndex
        return if (fraction >= 1f && stateIndex < last) (stateIndex + 1) to 0f else stateIndex to fraction
    }

    fun refreshSnapshot(storyboard: Storyboard = animatic.storyboard) {
        snapshot = indexDeckTimeline(storyboard, overridesStore.data())
        viewport.contentDurationMs = snapshot.totalDurationMs.coerceAtLeast(1)
        val selected = selectedExactTrackId
        if (selected != null && snapshot.tracks.none { it.trackId == selected || it.trackId == selected.substringBefore("#") }) {
            selectTrack(null)
        }
    }

    /**
     * Whether the row for [trackId] reads as selected: the exact track, or every row of a selected
     * stagger group (matching the stage, which highlights every item of a selected group).
     */
    fun isTrackRowSelected(trackId: String): Boolean {
        val exact = selectedExactTrackId ?: return false
        if (exact == trackId) return true
        return '#' !in exact && trackId.substringBefore("#") == exact
    }

    fun selectTrack(trackId: String?) {
        val canonical = trackId?.substringBefore("#")
        selectedExactTrackId = trackId
        selectedTrackId = canonical
        motionPathPreview.selectedTrackId = canonical
        stageSelection.selectedTrackId = trackId
        stageSelection.selectedLabel = trackId?.let { id ->
            val parent = snapshot.tracks.firstOrNull { it.trackId == canonical }?.label?.substringAfter(" · ")
                ?.removePrefix("⇄ ")?.removePrefix("⟳ ")
            if ('#' in id) {
                val item = id.substringAfter("#item").toIntOrNull()?.plus(1)
                listOfNotNull(parent, item?.let { "Item $it" }).joinToString(" · ")
            } else {
                parent ?: id
            }
        }
        onTrackSelected?.invoke(trackId)
    }

    /**
     * Requests the playhead at [timeMs]. Requests are conflated and applied by a single worker
     * ([runScrubWorker]), so pointer bursts never queue up or interleave.
     */
    fun scrubToTimeMs(timeMs: Int) {
        stopPlayback()
        val subLoopId = loopClock.activeSubCompLoopId
        if (subLoopId != null) {
            val desc = loopClock.registeredLoops[subLoopId]
            val period = loopClock.effectivePeriodMillis(subLoopId, desc?.defaultPeriodMillis ?: 6_000L)
            loopClock.scrubbedMillis = timeMs.toLong().coerceIn(0L, period)
            return
        }
        val clamped = timeMs.coerceIn(0, snapshot.totalDurationMs)
        val (stateIdx, frac) = snapshot.stateFractionForTimeMs(clamped)
        val request = ScrubRequest(clamped, stateIdx, frac, ++scrubSequence)
        lastRequested = request
        scrubRequests.value = request
    }

    /**
     * Applies the latest scrub request; slow seeks simply skip superseded requests.
     *
     * Rebuilding the scene subtree ([Animatic.seekToSegment] with `rebuildScene = true`) makes every
     * frame independent of the scrub path, but costs ~6-15 frames per step. While dragging:
     * - steps inside the active segment or across intra-slide beats seek in place (`rebuildScene = false`)
     *   so animations follow pointer scrubbing proportionally at 60 fps without snapping or freezing,
     * - a step that maps to the exact position already presented (a drag through a click hold) is
     *   skipped.
     */
    suspend fun runScrubWorker() = coroutineScope {
        var lastApplied: ScrubRequest? = null
        var restingNeedsRebuild = false
        scrubRequests.filterNotNull().collect { request ->
            val a = animatic
            val previous = lastApplied
            val shouldForceRebuild = request.forceRebuild && restingNeedsRebuild
            if (!shouldForceRebuild &&
                previous != null &&
                previous.stateIndex == request.stateIndex &&
                previous.fraction == request.fraction &&
                a.isPresenting(request.stateIndex, request.fraction)
            ) {
                appliedScrubSequence = maxOf(appliedScrubSequence, request.sequence)
                return@collect
            }
            val prevEndpoint = ScrubStepEndpoint.fromActiveAnimatic(
                animatic = a,
                lastFraction = previous?.takeIf { it.stateIndex == a.activeFromStateIndex }?.fraction
                    ?: a.activeFraction,
            )
            val nextEndpoint = ScrubStepEndpoint.fromStateIndex(
                animatic = a,
                stateIndex = request.stateIndex,
                fraction = request.fraction,
            )
            val decision = scrubStepPolicy(
                prev = prevEndpoint,
                next = nextEndpoint,
                dragging = isScrubDragging,
                forceRebuild = shouldForceRebuild,
            )
            a.seekToSegment(request.stateIndex, request.fraction, rebuildScene = !decision.inPlace)
            if (decision.inPlace && request.fraction > 0f) {
                val indexedMs = snapshot.stateSpans
                    .firstOrNull { !it.isHold && it.stateIndex == request.stateIndex }
                    ?.durationMs
                val actualMs = a.activeSegmentDurationNanos / 1_000_000L
                if (indexedMs != null &&
                    indexedMs > 0 &&
                    actualMs > 0L &&
                    abs(actualMs - indexedMs.toLong()) > DURATION_TRIPWIRE_THRESHOLD_MS
                ) {
                    durationTripwireCount++
                    System.err.println(
                        "TimelineStudio duration tripwire: state=${request.stateIndex} " +
                            "indexed=${indexedMs}ms active=${actualMs}ms (in-place seek, no auto-rebuild)"
                    )
                }
            }
            restingNeedsRebuild = when {
                !decision.inPlace -> false
                decision.crossedIntraSlideBeat || decision.reversedWithinSegment -> true
                else -> restingNeedsRebuild
            }
            lastApplied = request
            appliedScrubSequence = maxOf(appliedScrubSequence, request.sequence)
        }
    }

    /** Whether the stage rests on (or inside the segment of) the position a scrub put it at. */
    private fun Animatic.isPresenting(stateIndex: Int, fraction: Float): Boolean =
        activeFromStateIndex == stateIndex && activeToStateIndex == if (fraction > 0f) stateIndex + 1 else stateIndex

    fun togglePlayback(scope: CoroutineScope) {
        if (isPlaying) {
            stopPlayback()
            return
        }
        val subLoopId = loopClock.activeSubCompLoopId
        val totalMs = snapshot.totalDurationMs.coerceAtLeast(1)
        val startMs = if (subLoopId != null) 0 else currentPlayheadTimeMs.let { if (it >= totalMs - 8) 0 else it }
        playRange(scope, startMs, if (subLoopId != null) Int.MAX_VALUE else totalMs)
    }

    private fun playRange(scope: CoroutineScope, startMs: Int, endMs: Int) {
        stopPlayback()
        isPlaying = true
        playbackJob = scope.launch {
            try {
                var lastNanos = -1L
                val subLoopId = loopClock.activeSubCompLoopId
                if (subLoopId != null) {
                    val desc = loopClock.registeredLoops[subLoopId]
                    val period = loopClock.effectivePeriodMillis(subLoopId, desc?.defaultPeriodMillis ?: 6_000L)
                    var currentMs = (loopClock.scrubbedMillis ?: 0L).toFloat()
                    while (true) {
                        withFrameNanos { now ->
                            if (lastNanos >= 0L) {
                                currentMs = (currentMs + (now - lastNanos) / 1_000_000f) % period.toFloat()
                                loopClock.scrubbedMillis = currentMs.toLong()
                            }
                            lastNanos = now
                        }
                    }
                }
                val spans = snapshot.stateSpans
                if (spans.isEmpty() || startMs >= endMs) return@launch

                // Drive playback from a single monotonic wall-clock timer across the full span so
                // segment settle frames never add dead time (~10% drift) and lastRequested stays
                // continuous across segment boundaries (zero 1-frame snap-back).
                var playTimeMs = startMs.toDouble()
                val targetEndMs = endMs.toDouble()
                var expectedFrom = -1
                var expectedTo = -1
                var expectedCurrentIndex: Storyboard.Index? = null
                var expectedTargetIndex: Storyboard.Index? = null
                var isFirstFrame = true

                fun isExternallyPreempted(): Boolean {
                    if (expectedFrom < 0) return false
                    if (animatic.activeFromStateIndex != expectedFrom || animatic.activeToStateIndex != expectedTo) {
                        return true
                    }
                    if (expectedCurrentIndex != null && animatic.currentIndex != expectedCurrentIndex) {
                        return true
                    }
                    if (expectedTargetIndex != null && animatic.targetIndex != expectedTargetIndex) {
                        return true
                    }
                    return false
                }

                while (true) {
                    withFrameNanos { now ->
                        if (!isFirstFrame && lastNanos >= 0L) {
                            val deltaMs = ((now - lastNanos) / 1_000_000.0).coerceIn(0.0, 250.0)
                            playTimeMs = (playTimeMs + deltaMs).coerceAtMost(targetEndMs)
                        }
                        lastNanos = now
                        isFirstFrame = false
                    }

                    if (isExternallyPreempted()) {
                        lastRequested = null
                        return@launch
                    }

                    val currentMs = playTimeMs.roundToInt().coerceIn(startMs, endMs)
                    val (stateIdx, frac) = snapshot.stateFractionForTimeMs(currentMs)
                    val activeSpan = spans.firstOrNull { span ->
                        (span.durationMs > 0 || span == spans.last()) &&
                            (currentMs < span.startMs + span.durationMs || span == spans.last())
                    } ?: spans.last()
                    val reqFrac = if (activeSpan.isHold) 0f else frac

                    val reqSeq = ++scrubSequence
                    lastRequested = ScrubRequest(currentMs, stateIdx, reqFrac, reqSeq)

                    val targetFrom = stateIdx
                    val targetTo = if (reqFrac > 0f && stateIdx < animatic.stateDescriptors.lastIndex) {
                        stateIdx + 1
                    } else {
                        stateIdx
                    }
                    val needsSeek = animatic.activeFromStateIndex != targetFrom ||
                        animatic.activeToStateIndex != targetTo ||
                        (reqFrac > 0f && abs(animatic.activeFraction - reqFrac) > 1e-4f)

                    if (needsSeek) {
                        val rebuild = expectedFrom < 0 && animatic.activeFromStateIndex != targetFrom
                        animatic.seekToSegment(targetFrom, reqFrac, rebuildScene = rebuild)
                    }
                    appliedScrubSequence = maxOf(appliedScrubSequence, reqSeq)

                    if (animatic.activeFromStateIndex != targetFrom || animatic.activeToStateIndex != targetTo) {
                        lastRequested = null
                        return@launch
                    }
                    expectedFrom = targetFrom
                    expectedTo = targetTo
                    expectedCurrentIndex = animatic.currentIndex
                    expectedTargetIndex = animatic.targetIndex

                    if (playTimeMs >= targetEndMs) break
                }
            } finally {
                isPlaying = false
            }
        }
    }

    fun stopPlayback() {
        isPlaying = false
        playbackJob?.cancel()
        playbackJob = null
    }

    /**
     * Presents the next/previous beat exactly like the stage's arrow keys, so presenting keeps
     * working while keyboard focus sits on studio chrome (transport, layer rows, sliders).
     */
    fun advance(scope: CoroutineScope, direction: AdvanceDirection) {
        stopPlayback()
        lastRequested = null
        scope.launch { animatic.advance(direction) }
    }

    fun stepBeat(delta: Int) {
        stopPlayback()
        val subLoopId = loopClock.activeSubCompLoopId
        if (subLoopId != null) {
            val desc = loopClock.registeredLoops[subLoopId]
            val period = loopClock.effectivePeriodMillis(subLoopId, desc?.defaultPeriodMillis ?: 6_000L)
            val step = 500L * delta
            loopClock.scrubbedMillis = ((loopClock.scrubbedMillis ?: 0L) + step).coerceIn(0L, period)
            return
        }
        val beats = snapshot.eventBeats
        if (beats.isEmpty()) return
        val nowMs = currentPlayheadTimeMs
        val targetBeat = if (delta > 0) {
            beats.firstOrNull { it.timeMs > nowMs + 2 } ?: beats.last()
        } else {
            beats.lastOrNull { it.timeMs < nowMs - 2 } ?: beats.first()
        }
        scrubToTimeMs(targetBeat.timeMs)
        viewport.ensureTimeVisible(targetBeat.timeMs.toFloat())
    }

    fun insertEventBeatAtPlayhead() {
        val targetIdx = animatic.presentedIndex
        overridesStore.insertBeatAfter(
            sceneIndex = targetIdx.sceneIndex,
            beatIndex = targetIdx.frameIndex,
        )
    }

    fun removeEventBeatAtPlayhead() {
        val targetIdx = animatic.presentedIndex
        val sceneBeatsCount = animatic.storyboard.scenes.getOrNull(targetIdx.sceneIndex)?.frames?.size ?: 1
        if (sceneBeatsCount <= 1) return
        overridesStore.removeInjectedBeat(
            sceneIndex = targetIdx.sceneIndex,
            beatIndex = targetIdx.frameIndex,
        )
    }

    fun enterLoopSubComp(scope: CoroutineScope, loopId: String, sceneIndex: Int? = null) {
        stopPlayback()
        if (sceneIndex != null) {
            val alreadyOnScene = animatic.presentedIndex.sceneIndex == sceneIndex
            if (!alreadyOnScene) {
                // Jumping from another slide: park on the beat where the loop block is active
                // (or the slide's last beat so beat-gated loops animate when scrubbed).
                val slideBeats = snapshot.eventBeats.filter { it.sceneIndex == sceneIndex }
                val loopBlock = snapshot.tracks
                    .firstOrNull { it.trackId == "loop:$loopId" }
                    ?.blocks
                    ?.firstOrNull { it.sceneIndex == sceneIndex }
                val targetBeat = loopBlock
                    ?.takeIf { it.triggerBeatInScene > 0 }
                    ?.let { blk -> slideBeats.firstOrNull { it.frameIndex == blk.triggerBeatInScene } }
                    ?: slideBeats.lastOrNull()
                if (targetBeat != null) {
                    scrubToTimeMs(targetBeat.timeMs)
                } else {
                    val slide = snapshot.slides.getOrNull(sceneIndex)
                    if (slide != null) {
                        scrubToTimeMs(slide.startMs + slide.overlapInMs + 1)
                    }
                }
            }
        }
        loopClock.enterSubComp(loopId)
    }

    fun exitLoopSubComp() {
        stopPlayback()
        loopClock.exitSubComp()
    }

    fun previewSelectedBlock(scope: CoroutineScope) {
        val selId = selectedExactTrackId ?: selectedTrackId ?: return
        val row = snapshot.tracks.firstOrNull { it.trackId == selId }
            ?: snapshot.tracks.firstOrNull { it.trackId == selectedTrackId }
            ?: return
        val firstBlock = row.blocks.firstOrNull() ?: return
        val range = computeBlockPreviewRangeMs(snapshot, firstBlock)
        playRange(scope, range.first, range.last)
    }

    fun clearBakeStatus() {
        bakeStatus = TimelineBakeStatus.Idle
        bakeStatusMessage = null
    }

    fun bakeOverridesViaAgent(scope: CoroutineScope) {
        val dispatcher = bakeDispatcher ?: return
        val data = overridesStore.data()
        if (!data.hasOverrides) return
        if (bakeStatus == TimelineBakeStatus.Sending) return

        val overrideConvId = conversationIdProvider?.invoke()
        if (overrideConvId != null && overrideConvId.isBlank()) {
            bakeStatus = TimelineBakeStatus.Error
            bakeStatusMessage = "No Conversation ID — set in Review (F4)"
            return
        }

        val prompt = overridesStore.formatOverridesForAgentBake(
            workspaceRoot = bakeWorkspaceRoot,
            gradleTaskHint = bakeGradleTaskHint,
        )
        bakeStatus = TimelineBakeStatus.Sending
        bakeStatusMessage = "Baking…"
        scope.launch {
            val result = dispatcher.dispatchBake(prompt)
            if (result.isSuccess) {
                bakeStatus = TimelineBakeStatus.Sent
                bakeStatusMessage = "Bake sent to agent"
            } else {
                bakeStatus = TimelineBakeStatus.Error
                bakeStatusMessage = (result.exceptionOrNull()?.message ?: "Bake failed").take(120)
            }
        }
    }
}

@Composable
fun rememberTimelineStudioController(
    animatic: Animatic,
    overridesStore: TimelineOverridesStore,
    loopClock: DeckLoopClock,
    bakeDispatcher: AgentBakeDispatcher? = null,
    bakeWorkspaceRoot: Path = overridesStore.storyboardDir.toAbsolutePath().parent ?: Path.of("").toAbsolutePath(),
    bakeGradleTaskHint: String = ":deck:reload",
    motionPathPreview: MotionPathPreviewState = remember { MotionPathPreviewState() },
    stageSelection: TimelineStageSelection = remember { TimelineStageSelection() },
    externalTimingKey: Any? = Unit,
    externalInEditGesture: Boolean = false,
): TimelineStudioController {
    val controller = remember(motionPathPreview, stageSelection) {
        TimelineStudioController(
            initialAnimatic = animatic,
            overridesStore = overridesStore,
            loopClock = loopClock,
            initialBakeDispatcher = bakeDispatcher,
            initialBakeWorkspaceRoot = bakeWorkspaceRoot,
            initialBakeGradleTaskHint = bakeGradleTaskHint,
            motionPathPreview = motionPathPreview,
            stageSelection = stageSelection,
        )
    }
    if (controller.overridesStore !== overridesStore || controller.loopClock !== loopClock) {
        controller.resetForDeckSwitch(overridesStore, loopClock)
    }
    controller.animatic = animatic
    controller.bakeDispatcher = bakeDispatcher
    controller.bakeWorkspaceRoot = bakeWorkspaceRoot
    controller.bakeGradleTaskHint = bakeGradleTaskHint
    val paramRev = overridesStore.parameterRevision
    val structRev = overridesStore.structuralRevision
    SideEffect {
        if (paramRev >= 0 && structRev >= 0) {
            controller.syncLoopPeriodsFromStore()
        }
    }
    var lastIndexedStore by remember(controller) { mutableStateOf<TimelineOverridesStore?>(null) }
    LaunchedEffect(
        animatic.storyboard,
        overridesStore,
        overridesStore.structuralRevision,
        overridesStore.parameterRevision,
        overridesStore.isInEditGesture,
        externalTimingKey,
        externalInEditGesture,
    ) {
        val inGesture = overridesStore.isInEditGesture || externalInEditGesture
        if (inGesture && controller.snapshot.stateSpans.isNotEmpty() && lastIndexedStore === overridesStore) {
            return@LaunchedEffect
        }
        // First index or deck switch runs immediately; in-deck edits are coalesced.
        if (controller.snapshot.stateSpans.isNotEmpty() && lastIndexedStore === overridesStore) {
            delay(INDEX_DEBOUNCE_MS)
        }
        controller.refreshSnapshot(animatic.storyboard)
        lastIndexedStore = overridesStore
    }
    LaunchedEffect(controller) {
        controller.runScrubWorker()
    }
    // While authoring, ambient loops follow the master playhead (AE pre-comp semantics), so the
    // stage always shows exactly the frame under the playhead; otherwise they free-run.
    LaunchedEffect(controller, loopClock) {
        snapshotFlow {
            if (controller.authoringActive) controller.currentPlayheadTimeMs.toLong() else null
        }.collect { controller.loopClock.masterTimeMillis = it }
    }
    LaunchedEffect(controller, loopClock) {
        snapshotFlow { controller.authoringActive }.collect { authoring ->
            stageSelection.pickingEnabled = authoring
            // A sub-comp freezes every ambient loop; it must not outlive the studio.
            if (!authoring && controller.loopClock.activeSubCompLoopId != null) controller.exitLoopSubComp()
        }
    }
    LaunchedEffect(controller, overridesStore, controller.authoringActive, controller.bakeStatus) {
        if (!controller.authoringActive && controller.bakeStatus != TimelineBakeStatus.Sent) return@LaunchedEffect
        while (true) {
            delay(750L)
            controller.overridesStore.reloadFromDiskIfChanged(notifyRevisions = true)
        }
    }
    return controller
}
