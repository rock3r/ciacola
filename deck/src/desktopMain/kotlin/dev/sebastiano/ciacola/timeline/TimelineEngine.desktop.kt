package dev.sebastiano.ciacola.timeline

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.renderComposeScene
import dev.bnorm.storyboard.Storyboard
import dev.bnorm.storyboard.easel.Animatic
import dev.bnorm.storyboard.easel.Easel
import dev.bnorm.storyboard.easel.ScenePreview
import dev.bnorm.storyboard.easel.rememberAnimatic
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

private const val DEFAULT_BEAT_FLOOR_MS = 340

/** Visual rest between beats: the deck waits for a click here (not part of real playback time). */
internal const val CLICK_HOLD_MS = 400

/**
 * Measures how long arrow-key playback spends animating each segment `i -> i+1`, by driving a real
 * [Animatic] through every segment inside an offscreen [ImageComposeScene].
 */
@OptIn(ExperimentalComposeUiApi::class)
internal fun measureSegmentDurationsMs(
    storyboard: Storyboard,
    overrides: DeckTimelineOverridesFile,
    stateCount: Int,
): IntArray {
    val result = IntArray((stateCount - 1).coerceAtLeast(0))
    if (result.isEmpty()) return result
    var animatic: Animatic? = null
    var scope: CoroutineScope? = null
    val frozenLoops = DeckLoopClock().apply { masterTimeMillis = 0L }
    val scene = ImageComposeScene(
        width = 64,
        height = 36,
        density = storyboard.format.density,
        coroutineContext = Dispatchers.Unconfined,
    ) {
        CompositionLocalProvider(
            LocalTimelineOffscreen provides true,
            LocalTimelineOverrides provides overrides,
            LocalDeckLoopClock provides frozenLoops,
        ) {
            scope = rememberCoroutineScope()
            val a = rememberAnimatic(storyboard = { storyboard })
            animatic = a
            Easel(a)
        }
    }
    try {
        var timeNanos = 0L
        scene.render(timeNanos)
        val a = animatic ?: return result
        val job = scope!!.launch {
            val measured = a.measurePlaybackDurationsNanos()
            for (i in result.indices) {
                result[i] = (measured.getOrElse(i) { 0L } / 1_000_000L).toInt().coerceAtLeast(0)
            }
        }
        var frames = 0
        while (!job.isCompleted && frames < 50_000) {
            timeNanos += 16_000_000L
            scene.render(timeNanos)
            frames++
        }
        job.cancel()
    } finally {
        scene.close()
    }
    return result
}

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
     * Time of `(stateIndex, fraction)`. A settled Value state (`fraction == 0`) maps to the end of
     * its click hold, i.e. the event-beat line where the next click's animation starts.
     */
    fun timeMsForStateFraction(stateIndex: Int, fraction: Float): Int {
        if (stateSpans.isEmpty()) return 0
        val segment = stateSpans.firstOrNull { it.stateIndex == stateIndex && !it.isHold }
        if (segment != null) {
            return segment.startMs + (fraction.coerceIn(0f, 1f) * segment.durationMs).toInt()
        }
        val hold = stateSpans.lastOrNull { it.stateIndex == stateIndex && it.isHold }
        if (hold != null) return hold.startMs + hold.durationMs
        val fallback = stateSpans.lastOrNull { it.stateIndex <= stateIndex } ?: stateSpans.first()
        return fallback.startMs + fallback.durationMs
    }
}

class TimelineOverridesStore(
    val storyboardDir: Path = Path.of(".storyboard"),
    private val clockMillis: () -> Long = System::currentTimeMillis,
) {
    companion object {
        private const val MAX_UNDO_HISTORY = 64
        private const val COALESCE_WINDOW_MS = 250L
    }

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    val overridesFile: Path = storyboardDir.resolve("timeline-overrides.json")

    private var currentData: DeckTimelineOverridesFile = DeckTimelineOverridesFile()
    private var lastObservedModifiedMillis: Long = -1L
    private var lastObservedFileSize: Long = -1L

    private val undoStack = ArrayDeque<DeckTimelineOverridesFile>()
    private val redoStack = ArrayDeque<DeckTimelineOverridesFile>()
    private var activeGestureDepth = 0
    private var gestureSnapshotRecorded = false
    private var lastCoalesceKey: String? = null
    private var lastMutationMillis: Long = Long.MIN_VALUE

    var canUndo by mutableStateOf(false)
        private set

    var canRedo by mutableStateOf(false)
        private set

    var structuralRevision by mutableIntStateOf(0)
        private set

    var parameterRevision by mutableIntStateOf(0)
        private set

    init {
        reloadFromDiskIfChanged(notifyRevisions = false)
    }

    private fun syncUndoFlags() {
        canUndo = undoStack.isNotEmpty()
        canRedo = redoStack.isNotEmpty()
    }

    private fun recordUndoSnapshotBeforeMutation(coalesceKey: String?, coalesce: Boolean) {
        val now = clockMillis()
        redoStack.clear()
        val shouldCoalesce = when {
            activeGestureDepth > 0 -> gestureSnapshotRecorded
            coalesce && coalesceKey != null && coalesceKey == lastCoalesceKey &&
                lastMutationMillis != Long.MIN_VALUE && (now - lastMutationMillis) in 0..COALESCE_WINDOW_MS -> true
            else -> false
        }
        if (!shouldCoalesce) {
            if (undoStack.size >= MAX_UNDO_HISTORY) {
                undoStack.removeFirst()
            }
            undoStack.addLast(currentData)
            if (activeGestureDepth > 0) {
                gestureSnapshotRecorded = true
            }
        }
        lastCoalesceKey = if (coalesce) coalesceKey else null
        lastMutationMillis = now
        syncUndoFlags()
    }

    @Synchronized
    fun beginEditGesture() {
        if (activeGestureDepth == 0) {
            gestureSnapshotRecorded = false
        }
        activeGestureDepth++
    }

    @Synchronized
    fun endEditGesture() {
        if (activeGestureDepth > 0) {
            activeGestureDepth--
            if (activeGestureDepth == 0) {
                gestureSnapshotRecorded = false
                lastCoalesceKey = null
            }
        }
    }

    @Synchronized
    fun undo(): Boolean {
        reloadFromDiskIfChanged(notifyRevisions = false)
        if (undoStack.isEmpty()) return false
        activeGestureDepth = 0
        gestureSnapshotRecorded = false
        lastCoalesceKey = null
        if (redoStack.size >= MAX_UNDO_HISTORY) {
            redoStack.removeFirst()
        }
        redoStack.addLast(currentData)
        val restored = undoStack.removeLast()
        val structuralChanged = restored.sceneBeats != currentData.sceneBeats
        val paramsChanged = restored.tracks != currentData.tracks || restored.loopPeriodsMs != currentData.loopPeriodsMs
        currentData = restored
        persist()
        syncUndoFlags()
        if (structuralChanged) structuralRevision++
        if (paramsChanged) parameterRevision++
        return true
    }

    @Synchronized
    fun redo(): Boolean {
        reloadFromDiskIfChanged(notifyRevisions = false)
        if (redoStack.isEmpty()) return false
        activeGestureDepth = 0
        gestureSnapshotRecorded = false
        lastCoalesceKey = null
        if (undoStack.size >= MAX_UNDO_HISTORY) {
            undoStack.removeFirst()
        }
        undoStack.addLast(currentData)
        val restored = redoStack.removeLast()
        val structuralChanged = restored.sceneBeats != currentData.sceneBeats
        val paramsChanged = restored.tracks != currentData.tracks || restored.loopPeriodsMs != currentData.loopPeriodsMs
        currentData = restored
        persist()
        syncUndoFlags()
        if (structuralChanged) structuralRevision++
        if (paramsChanged) parameterRevision++
        return true
    }

    @Synchronized
    fun data(): DeckTimelineOverridesFile {
        reloadFromDiskIfChanged(notifyRevisions = true)
        return currentData
    }

    @Synchronized
    fun reloadFromDiskIfChanged(notifyRevisions: Boolean = true): Boolean {
        if (!overridesFile.exists()) {
            lastObservedModifiedMillis = -1L
            lastObservedFileSize = -1L
            return false
        }
        val modified = runCatching { Files.getLastModifiedTime(overridesFile).toMillis() }.getOrDefault(-1L)
        val size = runCatching { Files.size(overridesFile) }.getOrDefault(-1L)
        if (modified == lastObservedModifiedMillis && size == lastObservedFileSize) {
            return false
        }
        lastObservedModifiedMillis = modified
        lastObservedFileSize = size
        val text = runCatching { overridesFile.readText() }.getOrNull() ?: return false
        if (text.isBlank()) return false
        val parsed = runCatching {
            json.decodeFromString<DeckTimelineOverridesFile>(text)
        }.getOrNull()
        if (parsed == null) {
            if (size > 0L) {
                runCatching {
                    val backup = overridesFile.resolveSibling("timeline-overrides.corrupt.json")
                    Files.copy(overridesFile, backup, StandardCopyOption.REPLACE_EXISTING)
                }
            }
            return false
        }
        if (parsed == currentData) return false
        val structuralChanged = parsed.sceneBeats != currentData.sceneBeats
        val paramsChanged = parsed.tracks != currentData.tracks || parsed.loopPeriodsMs != currentData.loopPeriodsMs
        currentData = parsed
        if (notifyRevisions) {
            if (structuralChanged) structuralRevision++
            if (paramsChanged) parameterRevision++
        }
        return true
    }

    @Synchronized
    fun updateTrack(
        trackId: String,
        coalesce: Boolean = false,
        transform: (TrackOverride) -> TrackOverride,
    ) {
        reloadFromDiskIfChanged(notifyRevisions = false)
        val prev = currentData.tracks[trackId] ?: TrackOverride(trackId = trackId)
        val updated = transform(prev)
        if (currentData.tracks[trackId] == updated) return
        recordUndoSnapshotBeforeMutation(coalesceKey = "track:$trackId", coalesce = coalesce)
        currentData = currentData.copy(tracks = currentData.tracks + (trackId to updated))
        persist()
        parameterRevision++
    }

    @Synchronized
    fun resetTrack(trackId: String) {
        reloadFromDiskIfChanged(notifyRevisions = false)
        if (trackId !in currentData.tracks) return
        recordUndoSnapshotBeforeMutation(coalesceKey = null, coalesce = false)
        currentData = currentData.copy(tracks = currentData.tracks - trackId)
        persist()
        parameterRevision++
    }

    @Synchronized
    fun updateLoopPeriod(loopId: String, periodMs: Long, coalesce: Boolean = false) {
        reloadFromDiskIfChanged(notifyRevisions = false)
        val clamped = periodMs.coerceIn(200L, 60_000L)
        if (currentData.loopPeriodsMs[loopId] == clamped) return
        recordUndoSnapshotBeforeMutation(coalesceKey = "loop:$loopId", coalesce = coalesce)
        currentData = currentData.copy(loopPeriodsMs = currentData.loopPeriodsMs + (loopId to clamped))
        persist()
        parameterRevision++
    }

    @Synchronized
    fun resetLoopPeriod(loopId: String) {
        reloadFromDiskIfChanged(notifyRevisions = false)
        if (loopId !in currentData.loopPeriodsMs) return
        recordUndoSnapshotBeforeMutation(coalesceKey = null, coalesce = false)
        currentData = currentData.copy(loopPeriodsMs = currentData.loopPeriodsMs - loopId)
        persist()
        parameterRevision++
    }

    @Synchronized
    fun insertBeatAfter(sceneIndex: Int, beatIndex: Int) {
        reloadFromDiskIfChanged(notifyRevisions = false)
        recordUndoSnapshotBeforeMutation(coalesceKey = null, coalesce = false)
        val prev = currentData.sceneBeats[sceneIndex] ?: SceneBeatOverride(sceneIndex = sceneIndex)
        val (baseBeatIndex, _) = resolveVirtualBeatToBaseBeat(prev, beatIndex)
        val updated = prev.copy(injectedAfterBeats = prev.injectedAfterBeats + baseBeatIndex.coerceAtLeast(0))
        currentData = currentData.copy(sceneBeats = currentData.sceneBeats + (sceneIndex to updated))
        persist()
        structuralRevision++
    }

    @Synchronized
    fun removeInjectedBeat(sceneIndex: Int, beatIndex: Int) {
        reloadFromDiskIfChanged(notifyRevisions = false)
        recordUndoSnapshotBeforeMutation(coalesceKey = null, coalesce = false)
        val prev = currentData.sceneBeats[sceneIndex] ?: SceneBeatOverride(sceneIndex = sceneIndex)
        val (baseBeatIndex, isInjected) = resolveVirtualBeatToBaseBeat(prev, beatIndex)
        val updated = if (isInjected || baseBeatIndex in prev.injectedAfterBeats) {
            val mutable = prev.injectedAfterBeats.toMutableList()
            val removePos = mutable.lastIndexOf(baseBeatIndex).takeIf { it >= 0 } ?: mutable.lastIndex
            mutable.removeAt(removePos)
            prev.copy(injectedAfterBeats = mutable)
        } else {
            prev.copy(deletedBeats = (prev.deletedBeats + baseBeatIndex).distinct())
        }
        currentData = currentData.copy(sceneBeats = currentData.sceneBeats + (sceneIndex to updated))
        persist()
        structuralRevision++
    }

    @Synchronized
    fun resetAll() {
        reloadFromDiskIfChanged(notifyRevisions = false)
        if (!currentData.hasOverrides) return
        recordUndoSnapshotBeforeMutation(coalesceKey = null, coalesce = false)
        val hadStructural = currentData.sceneBeats.isNotEmpty()
        currentData = DeckTimelineOverridesFile()
        persist()
        parameterRevision++
        if (hadStructural) structuralRevision++
    }

    fun formatOverridesForAgentBake(
        workspaceRoot: Path,
        gradleTaskHint: String = ":deck:reload",
    ): String = buildString {
        val absWorkspace = workspaceRoot.toAbsolutePath()
        val absOverridesFile = overridesFile.toAbsolutePath()
        val isolatedGradleHome = absWorkspace.resolve(".gradle-agent-home")
        val reloadCommand = if (isolatedGradleHome.exists()) {
            "./gradlew --gradle-user-home \"$isolatedGradleHome\" " +
                "\"-Dkotlin.daemon.options=runFilesPath=$isolatedGradleHome/kotlin-daemon\" " +
                gradleTaskHint
        } else {
            "./gradlew $gradleTaskHint"
        }

        appendLine("Live Ciacola Timeline Studio overrides ready to bake into Kotlin source (`$absWorkspace`):")
        appendLine()
        if (currentData.sceneBeats.isNotEmpty()) {
            appendLine("### Scene Beat Count Changes")
            for ((sceneIdx, sb) in currentData.sceneBeats) {
                appendLine("- Scene $sceneIdx: injectedAfterBeats=${sb.injectedAfterBeats}, deletedBeats=${sb.deletedBeats}")
            }
            appendLine()
        }
        if (currentData.tracks.isNotEmpty()) {
            appendLine("### Track Timing / Curve / Spatial Overrides")
            for ((id, tr) in currentData.tracks) {
                appendLine("- Track `$id`: enterBeat=${tr.enterBeat}, exitBeat=${tr.exitBeat}, delayMs=${tr.delayMs}, durationMs=${tr.durationMs}, staggerStepMs=${tr.staggerStepMs}, curve=${tr.curve}, spatial=${tr.spatial}")
            }
            appendLine()
        }
        if (currentData.loopPeriodsMs.isNotEmpty()) {
            appendLine("### Loop Cycle Period Overrides")
            for ((loopId, periodMs) in currentData.loopPeriodsMs) {
                appendLine("- Loop `$loopId`: periodMillis=${periodMs}L")
            }
            appendLine()
        }
        appendLine("Please:")
        appendLine("1. Fold the listed track, loop-period, and structural beat overrides into the Kotlin slide source files in `$absWorkspace`.")
        appendLine("2. Remove the baked entries from `$absOverridesFile` (or reset `.storyboard/timeline-overrides.json` to `{}` when all overrides are baked) so baked beats and timings are never applied twice.")
        appendLine("3. Run `$reloadCommand` to hot-reload the updated Kotlin source.")
    }

    @Synchronized
    fun formatOverridesAsKotlinSnippet(): String = buildString {
        val snapshotData = data()
        appendLine("// --- Ciacola Timeline Studio Overrides (Kotlin Snippets) ---")
        for ((trackId, tr) in snapshotData.tracks) {
            if (trackId.startsWith("shared:")) {
                val sharedKey = trackId.removePrefix("shared:")
                val params = buildList {
                    add("sharedKey = \"$sharedKey\"")
                    tr.durationMs?.let { add("defaultDurationMs = $it") }
                }
                appendLine("Modifier.deckSharedBounds(${params.joinToString(", ")})")
            } else {
                val params = buildList {
                    add("trackId = \"$trackId\"")
                    tr.enterBeat?.let { add("defaultTriggerBeat = $it") }
                    tr.durationMs?.let { add("durationMillis = $it") }
                    tr.staggerStepMs?.let { add("staggerStepMs = $it") }
                    tr.delayMs?.let { add("/* delayMillis = $it */") }
                    tr.curve?.let { c ->
                        if (c.isSpring) {
                            add("/* spring(dampingRatio = ${"%.2f".format(c.dampingRatio)}f, stiffness = ${c.stiffness.toInt()}f) */")
                        } else {
                            add("easing = CubicBezierEasing(${"%.2f".format(c.x1)}f, ${"%.2f".format(c.y1)}f, ${"%.2f".format(c.x2)}f, ${"%.2f".format(c.y2)}f)")
                        }
                    }
                }
                appendLine("transition.animateBuildProgress(${params.joinToString(", ")})")
                tr.spatial?.let { sp ->
                    appendLine("// Spatial ($trackId): offsetXDp = ${sp.offsetXDp}f, offsetYDp = ${sp.offsetYDp}f, arcBend = ${sp.arcBend}f")
                }
            }
        }
        for ((loopId, periodMs) in snapshotData.loopPeriodsMs) {
            appendLine("rememberDeckLoopMillis(loopId = \"$loopId\", periodMillis = ${periodMs}L)")
        }
        for ((sceneIdx, sb) in snapshotData.sceneBeats) {
            appendLine("// Scene $sceneIdx beats: injectedAfterBeats = ${sb.injectedAfterBeats}, deletedBeats = ${sb.deletedBeats}")
        }
        appendLine()
        appendLine("/* .storyboard/timeline-overrides.json:")
        appendLine(json.encodeToString(snapshotData))
        appendLine("*/")
    }

    private fun persist() {
        runCatching {
            Files.createDirectories(storyboardDir)
            val tmp = overridesFile.resolveSibling("${overridesFile.fileName}.tmp")
            tmp.writeText(json.encodeToString(currentData) + "\n")
            Files.move(tmp, overridesFile, StandardCopyOption.REPLACE_EXISTING)
            lastObservedModifiedMillis = Files.getLastModifiedTime(overridesFile).toMillis()
            lastObservedFileSize = Files.size(overridesFile)
        }
    }
}

/**
 * Composes every storyboard index once in a single reused offscreen scene, so tracks, shared
 * sightings and loops register into [collector].
 */
@OptIn(ExperimentalComposeUiApi::class)
internal fun harvestIntoCollector(
    storyboard: Storyboard,
    overrides: DeckTimelineOverridesFile,
    collector: TimelineHarvestCollector,
    loopClock: DeckLoopClock,
) {
    val current = mutableStateOf(storyboard.indices.firstOrNull())
    val scene = ImageComposeScene(width = 64, height = 36, density = storyboard.format.density) {
        CompositionLocalProvider(
            LocalTimelineOffscreen provides true,
            LocalTimelineHarvestCollector provides collector,
            LocalTimelineOverrides provides overrides,
            LocalDeckLoopClock provides loopClock,
        ) {
            val index = current.value
            if (index != null) key(index) { ScenePreview(storyboard, index) }
        }
    }
    try {
        for (index in storyboard.indices) {
            runCatching {
                current.value = index
                Snapshot.sendApplyNotifications()
                scene.render().close()
            }
        }
    } finally {
        scene.close()
    }
}

/**
 * Harvests all animation tracks, staggered items, cross-slide shared elements, and loop
 * sub-compositions across every slide and beat of [storyboard] via an offscreen dry-run pass,
 * producing a contiguous After Effects [DeckTimelineSnapshot].
 */
fun indexDeckTimeline(
    storyboard: Storyboard,
    overrides: DeckTimelineOverridesFile = DeckTimelineOverridesFile(),
): DeckTimelineSnapshot {
    val collector = TimelineHarvestCollector()
    val harvestLoopClock = DeckLoopClock()

    // Dry-run every (sceneIndex, frameIndex) in a tiny offscreen Compose scene so lazy scenes
    // register all tracks, sharedBounds sightings, and loops across the whole deck! One scene is
    // reused (re-keyed per index): same records as a fresh scene per index, ~5x cheaper.
    harvestIntoCollector(storyboard, overrides, collector, harvestLoopClock)

    // Build exact Animatic state sequence matching Animatic.buildStates(storyboard):
    // Scene 0: Value(0)..Value(n-1), End
    // Scene s: Start, Value(0)..Value(n-1), End
    // Scene last: Start, Value(0)..Value(n-1)
    data class RawAnimaticState(
        val stateIndex: Int,
        val sceneIndex: Int,
        val frameIndex: Int,
        val isStart: Boolean,
        val isEnd: Boolean,
    )

    val rawStates = mutableListOf<RawAnimaticState>()
    var runningStateIdx = 0
    for ((sIdx, scene) in storyboard.scenes.withIndex()) {
        if (sIdx > 0) {
            rawStates += RawAnimaticState(runningStateIdx++, sIdx, -1, isStart = true, isEnd = false)
        }
        for (fIdx in scene.frames.indices) {
            rawStates += RawAnimaticState(runningStateIdx++, sIdx, fIdx, isStart = false, isEnd = false)
        }
        if (sIdx < storyboard.scenes.lastIndex) {
            rawStates += RawAnimaticState(runningStateIdx++, sIdx, scene.frames.size, isStart = false, isEnd = true)
        }
    }

    val harvestedRecords = collector.records.values.toList()

    // Real playback durations for every segment (state i -> i+1), measured on a live animatic. The
    // timeline therefore shows exactly the time an arrow-key advance spends animating each segment,
    // and scrubbing to time t lands on the frame playback shows at t.
    val segmentDurationMs = measureSegmentDurationsMs(storyboard, overrides, rawStates.size)

    val stateSpans = mutableListOf<StateTimeSpan>()
    val eventBeats = mutableListOf<EventBeatLine>()
    val beatStartTimeMap = mutableMapOf<Pair<Int, Int>, Int>()
    val valueStateIndex = mutableMapOf<Pair<Int, Int>, Int>()
    val startStateIndex = mutableMapOf<Int, Int>()
    val segmentStart = IntArray(rawStates.size)
    val sceneStartTime = IntArray(storyboard.scenes.size)
    val sceneEndTime = IntArray(storyboard.scenes.size)
    val sceneOverlapIn = IntArray(storyboard.scenes.size)
    val sceneOverlapOut = IntArray(storyboard.scenes.size)

    var cursorMs = 0
    var beatCounter = 0
    for (i in rawStates.indices) {
        val st = rawStates[i]
        val isValue = !st.isStart && !st.isEnd
        if (isValue) {
            valueStateIndex[st.sceneIndex to st.frameIndex] = i
            beatStartTimeMap[st.sceneIndex to st.frameIndex] = cursorMs
            // Click hold: the slide rests on this beat until the presenter clicks.
            stateSpans += StateTimeSpan(i, st.sceneIndex, st.frameIndex, cursorMs, CLICK_HOLD_MS, isBoundary = false, isHold = true)
            cursorMs += CLICK_HOLD_MS
        }
        if (st.isStart) startStateIndex[st.sceneIndex] = i
        if (i == rawStates.lastIndex) break

        val next = rawStates[i + 1]
        val duration = segmentDurationMs[i]
        segmentStart[i] = cursorMs
        if (isValue) {
            val sceneFrames = storyboard.scenes.getOrNull(st.sceneIndex)?.frames
            val currentVf = sceneFrames?.getOrNull(st.frameIndex) as? VirtualizedFrame<*>
            val nextVf = if (!next.isEnd) sceneFrames?.getOrNull(next.frameIndex) as? VirtualizedFrame<*> else null
            val isInjectedBeatLine = if (currentVf != null || nextVf != null) {
                (currentVf?.isInjected == true) || (!next.isEnd && nextVf?.isInjected == true)
            } else {
                val injectedSet = overrides.sceneBeats[st.sceneIndex]?.injectedAfterBeats.orEmpty()
                !next.isEnd && (st.frameIndex + 1) in injectedSet.map { it + 1 }
            }
            val beatSegmentDuration = if (next.isEnd) {
                var total = duration
                var boundaryIdx = i + 1
                while (boundaryIdx < rawStates.lastIndex && (rawStates[boundaryIdx].isEnd || rawStates[boundaryIdx].isStart)) {
                    total += segmentDurationMs[boundaryIdx]
                    boundaryIdx++
                }
                total
            } else {
                duration
            }
            eventBeats += EventBeatLine(
                beatGlobalIndex = beatCounter++,
                stateIndex = i,
                sceneIndex = st.sceneIndex,
                frameIndex = st.frameIndex,
                timeMs = cursorMs,
                segmentDurationMs = beatSegmentDuration,
                isSlideBoundary = next.isEnd,
                isInjected = isInjectedBeatLine,
            )
        }
        if (st.isEnd) {
            // Scene s and s+1 are both on stage for the whole End -> Start segment.
            sceneEndTime[st.sceneIndex] = cursorMs + duration
            sceneOverlapOut[st.sceneIndex] = duration
            sceneStartTime[next.sceneIndex] = cursorMs
            sceneOverlapIn[next.sceneIndex] = duration
        }
        stateSpans += StateTimeSpan(i, st.sceneIndex, st.frameIndex, cursorMs, duration, isBoundary = st.isEnd || st.isStart)
        cursorMs += duration
    }
    sceneEndTime[storyboard.scenes.lastIndex] = cursorMs

    /** Start of the segment during which animations keyed on [beat] of [sceneIndex] play. */
    fun beatSegmentStartMs(sceneIndex: Int, beat: Int): Int {
        val segmentState = if (beat > 0) {
            valueStateIndex[sceneIndex to (beat - 1)]
        } else {
            startStateIndex[sceneIndex]
        }
        return segmentState?.let { segmentStart[it] } ?: sceneStartTime.getOrElse(sceneIndex) { 0 }
    }

    fun beatSegmentEndMs(sceneIndex: Int, beat: Int): Int {
        val segmentState = if (beat > 0) valueStateIndex[sceneIndex to (beat - 1)] else startStateIndex[sceneIndex]
        return segmentState?.let { segmentStart[it] + segmentDurationMs[it] } ?: beatSegmentStartMs(sceneIndex, beat)
    }

    val slideBars = storyboard.scenes.mapIndexed { sIdx, scene ->
        val start = sceneStartTime[sIdx]
        val end = sceneEndTime[sIdx].coerceAtLeast(start + 1)
        SlideSectionBar(
            sceneIndex = sIdx,
            label = sceneDisplayTitle(sIdx, scene.frames.size, collector.sceneTitles[sIdx]),
            startMs = start,
            endMs = end,
            overlapInMs = sceneOverlapIn[sIdx],
            overlapOutMs = sceneOverlapOut[sIdx],
            beatCount = scene.frames.size,
        )
    }

    val trackRows = mutableListOf<TimelineTrackRow>()

    // 1. Pinned Cross-Slide & Intra-Slide Shared Element Span Tracks!
    val sightingsByKey = collector.sharedSightings.groupBy({ it.first }, { it.second to it.third })
    for ((sharedKey, pairs) in sightingsByKey) {
        val sorted = pairs.sortedWith(compareBy({ it.first }, { it.second }))
        val firstSighting = sorted.first()
        val lastSighting = sorted.last()
        val startMs = beatSegmentStartMs(firstSighting.first, firstSighting.second)
        val trackId = "shared:$sharedKey"
        val spanEnd = maxOf(beatSegmentEndMs(lastSighting.first, lastSighting.second), startMs + 1)
        val label = collector.sharedLabels[sharedKey] ?: sharedKey

        trackRows += TimelineTrackRow(
            trackId = trackId,
            label = "⇄ $label",
            category = TrackCategory.SharedElement,
            sceneIndex = if (firstSighting.first == lastSighting.first) firstSighting.first else null,
            blocks = listOf(
                TimelineBlockBar(
                    id = "$trackId-span",
                    trackId = trackId,
                    label = if (firstSighting.first != lastSighting.first) {
                        "$label (S${firstSighting.first + 1} → S${lastSighting.first + 1})"
                    } else {
                        label
                    },
                    category = TrackCategory.SharedElement,
                    sceneIndex = firstSighting.first,
                    endSceneIndex = lastSighting.first,
                    triggerBeatInScene = firstSighting.second,
                    startMs = startMs,
                    durationMs = (spanEnd - startMs).coerceAtLeast(1),
                    morphDurationMs = collector.sharedDurations[sharedKey] ?: 550,
                    sharedKey = sharedKey,
                )
            ),
        )
    }

    // 2. Harvested Build, StaggerGroup (+ StaggerItem staircase!), MagicText, WarpCode, and LineDraw tracks!
    val groupedByTrack = harvestedRecords.groupBy { it.trackId }
    for ((trackId, entries) in groupedByTrack) {
        val representative = entries.first()
        val blocks = entries.sortedBy { it.triggerBeat }.map { rec ->
            // Beat `b > 0` plays during the Frame `b - 1` -> Frame `b` segment.
            val segmentStartMs = beatSegmentStartMs(rec.sceneIndex, rec.triggerBeat)
            val springOverrideMs = overrides.tracks[rec.trackId]?.curve?.takeIf { it.isSpring }?.estimatedSpringDurationMs()
            TimelineBlockBar(
                id = "${rec.trackId}@b${rec.triggerBeat}",
                trackId = rec.trackId,
                label = rec.label,
                category = rec.category,
                sceneIndex = rec.sceneIndex,
                triggerBeatInScene = rec.triggerBeat,
                startMs = segmentStartMs + rec.delayMs,
                durationMs = springOverrideMs ?: rec.durationMs,
                delayMs = rec.delayMs,
                staggerCount = rec.staggerCount,
                staggerStepMs = rec.staggerStepMs,
                defaultOffsetXDp = rec.defaultOffsetXDp,
                defaultOffsetYDp = rec.defaultOffsetYDp,
            )
        }

        trackRows += TimelineTrackRow(
            trackId = trackId,
            label = "S${representative.sceneIndex + 1} · ${representative.label}",
            category = representative.category,
            sceneIndex = representative.sceneIndex,
            indentLevel = 0,
            blocks = blocks,
        )

        // Expand StaggerGroup into individual StaggerItem staircase rows so timing offsets are visual!
        val staggerCount = representative.staggerCount ?: 0
        val staggerStep = representative.staggerStepMs ?: 55
        if (representative.category == TrackCategory.StaggerGroup && staggerCount > 1) {
            val itemDur = (representative.durationMs - (staggerCount - 1) * staggerStep).coerceAtLeast(80)
            for (itemIdx in 0 until staggerCount) {
                val itemTrackId = "$trackId#item$itemIdx"
                val itemBlocks = blocks.map { parentBlock ->
                    TimelineBlockBar(
                        id = "${parentBlock.id}#item$itemIdx",
                        trackId = trackId,
                        label = "${representative.label} [${itemIdx + 1}/$staggerCount]",
                        category = TrackCategory.StaggerItem,
                        sceneIndex = parentBlock.sceneIndex,
                        triggerBeatInScene = parentBlock.triggerBeatInScene,
                        startMs = parentBlock.startMs + itemIdx * staggerStep,
                        durationMs = itemDur,
                        delayMs = parentBlock.delayMs + itemIdx * staggerStep,
                        staggerIndex = itemIdx,
                        staggerCount = staggerCount,
                        staggerStepMs = staggerStep,
                        defaultOffsetXDp = representative.defaultOffsetXDp,
                        defaultOffsetYDp = representative.defaultOffsetYDp,
                    )
                }
                trackRows += TimelineTrackRow(
                    trackId = itemTrackId,
                    label = "↳ Item ${itemIdx + 1}",
                    category = TrackCategory.StaggerItem,
                    sceneIndex = representative.sceneIndex,
                    indentLevel = 1,
                    blocks = itemBlocks,
                )
            }
        }
    }

    // 3. Ambient Loop Sub-Compositions!
    val allLoops: Map<String, Pair<LoopSubCompDescriptor, Int>> = buildMap {
        putAll(collector.loopRecords)
        for ((loopId, loopDesc) in harvestLoopClock.registeredLoops) {
            if (loopId !in this) {
                put(loopId, loopDesc to 0)
            }
        }
    }
    for ((loopId, pair) in allLoops) {
        val (loopDesc, loopSceneIdx) = pair
        val slideBar = slideBars.getOrNull(loopSceneIdx)
        val startMs = slideBar?.startMs ?: 0
        val spanMs = ((slideBar?.endMs ?: 600) - startMs).coerceAtLeast(DEFAULT_BEAT_FLOOR_MS)
        val trackId = "loop:$loopId"
        val effectivePeriodMs = (overrides.loopPeriodsMs[loopId] ?: loopDesc.defaultPeriodMillis).coerceAtLeast(200L)
        trackRows += TimelineTrackRow(
            trackId = trackId,
            label = "⟳ S${loopSceneIdx + 1} · ${loopDesc.label} · ${effectivePeriodMs}ms",
            category = TrackCategory.LoopSubComp,
            sceneIndex = loopSceneIdx,
            blocks = listOf(
                TimelineBlockBar(
                    id = "$trackId-subcomp",
                    trackId = trackId,
                    label = "⟳ ${loopDesc.label} · ${effectivePeriodMs}ms ▸",
                    category = TrackCategory.LoopSubComp,
                    sceneIndex = loopSceneIdx,
                    triggerBeatInScene = 0,
                    startMs = startMs,
                    durationMs = spanMs,
                    loopId = loopId,
                    loopPeriodMs = effectivePeriodMs,
                )
            ),
        )
    }

    // Order track groups chronologically across the deck:
    // 1. Cross-slide SharedElement spans at the very top,
    // 2. Remaining tracks (keeping StaggerItem children attached beneath their StaggerGroup parent)
    //    sorted by (sceneIndex, firstBlockStartMs).
    val groupedUnits = mutableListOf<List<TimelineTrackRow>>()
    var cursorIdx = 0
    while (cursorIdx < trackRows.size) {
        val head = trackRows[cursorIdx]
        val unit = mutableListOf(head)
        cursorIdx++
        while (cursorIdx < trackRows.size && trackRows[cursorIdx].indentLevel > 0) {
            unit += trackRows[cursorIdx]
            cursorIdx++
        }
        groupedUnits += unit
    }
    val orderedTracks = groupedUnits
        .sortedWith(
            compareBy<List<TimelineTrackRow>>(
                { unit -> if (unit.first().category == TrackCategory.SharedElement) 0 else 1 },
                { unit -> unit.first().sceneIndex ?: 0 },
                { unit -> unit.first().blocks.minOfOrNull { it.startMs } ?: 0 },
            )
        )
        .flatten()

    return DeckTimelineSnapshot(
        totalDurationMs = cursorMs.coerceAtLeast(DEFAULT_BEAT_FLOOR_MS),
        slides = slideBars,
        eventBeats = eventBeats,
        tracks = orderedTracks,
        stateSpans = stateSpans,
    )
}

private fun sceneDisplayTitle(sceneIndex: Int, beatCount: Int, harvestedTitle: String?): String {
    val cleanTitle = harvestedTitle?.trim()?.takeIf { it.isNotEmpty() } ?: "Scene"
    return "S${sceneIndex + 1} · $cleanTitle ($beatCount)"
}
