package dev.sebastiano.ciacola.timeline

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.renderComposeScene
import dev.bnorm.storyboard.Storyboard
import dev.bnorm.storyboard.easel.ScenePreview
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlinx.serialization.json.Json

private const val DEFAULT_BEAT_FLOOR_MS = 340
private const val DEFAULT_SLIDE_OVERLAP_MS = 280

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
    val loopId: String? = null,
    val loopPeriodMs: Long? = null,
    val sharedKey: String? = null,
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
            val end = span.startMs + span.durationMs
            if (clamped < end || span == stateSpans.last()) {
                val frac = if (span.durationMs <= 0) {
                    0f
                } else {
                    ((clamped - span.startMs).toFloat() / span.durationMs.toFloat()).coerceIn(0f, 1f)
                }
                return span.stateIndex to frac
            }
        }
        return stateSpans.last().stateIndex to 1f
    }

    fun timeMsForStateFraction(stateIndex: Int, fraction: Float): Int {
        if (stateSpans.isEmpty()) return 0
        val span = stateSpans.firstOrNull { it.stateIndex == stateIndex }
            ?: stateSpans.lastOrNull { it.stateIndex <= stateIndex }
            ?: stateSpans.first()
        return span.startMs + (fraction.coerceIn(0f, 1f) * span.durationMs).toInt()
    }
}

class TimelineOverridesStore(
    val storyboardDir: Path = Path.of(".storyboard"),
) {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    val overridesFile: Path = storyboardDir.resolve("timeline-overrides.json")

    private var currentData: DeckTimelineOverridesFile = DeckTimelineOverridesFile()

    var structuralRevision by mutableIntStateOf(0)
        private set

    var parameterRevision by mutableIntStateOf(0)
        private set

    init {
        currentData = readFromDisk()
    }

    fun data(): DeckTimelineOverridesFile = currentData

    @Synchronized
    fun updateTrack(trackId: String, transform: (TrackOverride) -> TrackOverride) {
        val prev = currentData.tracks[trackId] ?: TrackOverride(trackId = trackId)
        val updated = transform(prev)
        currentData = currentData.copy(tracks = currentData.tracks + (trackId to updated))
        persist()
        parameterRevision++
    }

    @Synchronized
    fun resetTrack(trackId: String) {
        if (trackId !in currentData.tracks) return
        currentData = currentData.copy(tracks = currentData.tracks - trackId)
        persist()
        parameterRevision++
    }

    @Synchronized
    fun insertBeatAfter(sceneIndex: Int, beatIndex: Int) {
        val prev = currentData.sceneBeats[sceneIndex] ?: SceneBeatOverride(sceneIndex = sceneIndex)
        val updated = prev.copy(injectedAfterBeats = prev.injectedAfterBeats + beatIndex.coerceAtLeast(0))
        currentData = currentData.copy(sceneBeats = currentData.sceneBeats + (sceneIndex to updated))
        persist()
        structuralRevision++
    }

    @Synchronized
    fun removeInjectedBeat(sceneIndex: Int, beatIndex: Int) {
        val prev = currentData.sceneBeats[sceneIndex] ?: SceneBeatOverride(sceneIndex = sceneIndex)
        val updated = if (prev.injectedAfterBeats.isNotEmpty()) {
            val mutable = prev.injectedAfterBeats.toMutableList()
            val removePos = mutable.indexOfLast { it <= beatIndex }.takeIf { it >= 0 } ?: mutable.lastIndex
            mutable.removeAt(removePos)
            prev.copy(injectedAfterBeats = mutable)
        } else {
            prev.copy(deletedBeats = (prev.deletedBeats + beatIndex).distinct())
        }
        currentData = currentData.copy(sceneBeats = currentData.sceneBeats + (sceneIndex to updated))
        persist()
        structuralRevision++
    }

    @Synchronized
    fun resetAll() {
        val hadStructural = currentData.sceneBeats.isNotEmpty()
        currentData = DeckTimelineOverridesFile()
        persist()
        parameterRevision++
        if (hadStructural) structuralRevision++
    }

    fun formatOverridesForAgentBake(workspaceRoot: Path): String = buildString {
        appendLine("Please fold the following live Ciacola After Effects timeline overrides into the Kotlin scene definitions in `$workspaceRoot`, then run `:deck:reload`:")
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
        }
    }

    private fun readFromDisk(): DeckTimelineOverridesFile {
        if (!overridesFile.exists()) return DeckTimelineOverridesFile()
        return runCatching {
            json.decodeFromString<DeckTimelineOverridesFile>(overridesFile.readText())
        }.getOrDefault(DeckTimelineOverridesFile())
    }

    private fun persist() {
        Files.createDirectories(storyboardDir)
        val tmp = overridesFile.resolveSibling("${overridesFile.fileName}.tmp")
        tmp.writeText(json.encodeToString(currentData) + "\n")
        Files.move(tmp, overridesFile, StandardCopyOption.REPLACE_EXISTING)
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
    // register all tracks, sharedBounds sightings, and loops across the whole deck!
    for (index in storyboard.indices) {
        runCatching {
            renderComposeScene(width = 64, height = 36, density = storyboard.format.density) {
                CompositionLocalProvider(
                    LocalTimelineHarvestCollector provides collector,
                    LocalTimelineOverrides provides overrides,
                    LocalDeckLoopClock provides harvestLoopClock,
                ) {
                    ScenePreview(storyboard, index)
                }
            }
        }
    }

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

    // Compute per-(scene, beat) animation duration ceiling so every beat segment fits its longest stagger/build!
    fun maxBeatContentDurationMs(sceneIndex: Int, beatIndex: Int): Int {
        val matching = harvestedRecords.filter {
            it.sceneIndex == sceneIndex && it.triggerBeat == beatIndex
        }
        val maxTrackEnd = matching.maxOfOrNull { it.delayMs + it.durationMs } ?: 0
        return maxOf(DEFAULT_BEAT_FLOOR_MS, maxTrackEnd + 60)
    }

    val stateSpans = mutableListOf<StateTimeSpan>()
    val eventBeats = mutableListOf<EventBeatLine>()
    val beatStartTimeMap = mutableMapOf<Pair<Int, Int>, Int>()
    val sceneStartTime = IntArray(storyboard.scenes.size)
    val sceneEndTime = IntArray(storyboard.scenes.size)

    var cursorMs = 0
    var beatCounter = 0

    for (i in rawStates.indices) {
        val st = rawStates[i]
        val isLastOverall = i == rawStates.lastIndex
        val nextSt = rawStates.getOrNull(i + 1)

        when {
            // Value frame transitioning to next Value frame in the SAME scene
            !st.isStart && !st.isEnd && nextSt != null && !nextSt.isStart && !nextSt.isEnd && nextSt.sceneIndex == st.sceneIndex -> {
                if (st.frameIndex == 0 && st.sceneIndex == 0) {
                    sceneStartTime[0] = cursorMs
                }
                beatStartTimeMap[st.sceneIndex to st.frameIndex] = cursorMs
                // Transition from Frame(f) -> Frame(f+1) triggers animations keyed on Beat(f+1)!
                val dur = maxBeatContentDurationMs(st.sceneIndex, st.frameIndex + 1)
                val injectedSet = overrides.sceneBeats[st.sceneIndex]?.injectedAfterBeats.orEmpty()
                val isInjected = injectedSet.isNotEmpty() && st.frameIndex > 0
                eventBeats += EventBeatLine(
                    beatGlobalIndex = beatCounter++,
                    stateIndex = st.stateIndex,
                    sceneIndex = st.sceneIndex,
                    frameIndex = st.frameIndex,
                    timeMs = cursorMs,
                    segmentDurationMs = dur,
                    isSlideBoundary = false,
                    isInjected = isInjected,
                )
                stateSpans += StateTimeSpan(st.stateIndex, st.sceneIndex, st.frameIndex, cursorMs, dur, isBoundary = false)
                cursorMs += dur
            }

            // Last Value frame of Scene s -> Scene s Frame.End (first half of cross-slide overlap!)
            !st.isStart && !st.isEnd && nextSt != null && nextSt.isEnd -> {
                if (st.frameIndex == 0 && st.sceneIndex == 0) {
                    sceneStartTime[0] = cursorMs
                }
                beatStartTimeMap[st.sceneIndex to st.frameIndex] = cursorMs
                val holdBeforeExitMs = maxBeatContentDurationMs(st.sceneIndex, st.frameIndex).coerceAtLeast(DEFAULT_BEAT_FLOOR_MS)
                eventBeats += EventBeatLine(
                    beatGlobalIndex = beatCounter++,
                    stateIndex = st.stateIndex,
                    sceneIndex = st.sceneIndex,
                    frameIndex = st.frameIndex,
                    timeMs = cursorMs,
                    segmentDurationMs = holdBeforeExitMs,
                    isSlideBoundary = false,
                    isInjected = false,
                )
                // Notice: Scene s+1 visually begins overlapping right as Scene s starts exiting!
                stateSpans += StateTimeSpan(st.stateIndex, st.sceneIndex, st.frameIndex, cursorMs, holdBeforeExitMs, isBoundary = false)
                cursorMs += holdBeforeExitMs
            }

            // Scene s Frame.End -> Scene s+1 Frame.Start (the overlapping slide transition zone!)
            st.isEnd -> {
                val nextSceneIdx = st.sceneIndex + 1
                val overlapStartMs = cursorMs
                if (nextSceneIdx in sceneStartTime.indices) {
                    sceneStartTime[nextSceneIdx] = overlapStartMs
                }
                eventBeats += EventBeatLine(
                    beatGlobalIndex = beatCounter++,
                    stateIndex = st.stateIndex,
                    sceneIndex = st.sceneIndex,
                    frameIndex = st.frameIndex,
                    timeMs = overlapStartMs,
                    segmentDurationMs = DEFAULT_SLIDE_OVERLAP_MS,
                    isSlideBoundary = true,
                    isInjected = false,
                )
                stateSpans += StateTimeSpan(st.stateIndex, st.sceneIndex, st.frameIndex, overlapStartMs, DEFAULT_SLIDE_OVERLAP_MS, isBoundary = true)
                cursorMs += DEFAULT_SLIDE_OVERLAP_MS
                sceneEndTime[st.sceneIndex] = cursorMs
            }

            // Scene s+1 Frame.Start -> Scene s+1 Frame.Value(0) (settle into first frame of new slide)
            st.isStart -> {
                val settleMs = 60
                stateSpans += StateTimeSpan(st.stateIndex, st.sceneIndex, st.frameIndex, cursorMs, settleMs, isBoundary = true)
                cursorMs += settleMs
            }

            // Final Value frame of the final scene in the deck
            isLastOverall -> {
                if (st.frameIndex == 0 && st.sceneIndex == 0) {
                    sceneStartTime[0] = cursorMs
                }
                beatStartTimeMap[st.sceneIndex to st.frameIndex] = cursorMs
                val finalHoldMs = DEFAULT_BEAT_FLOOR_MS
                eventBeats += EventBeatLine(
                    beatGlobalIndex = beatCounter++,
                    stateIndex = st.stateIndex,
                    sceneIndex = st.sceneIndex,
                    frameIndex = st.frameIndex,
                    timeMs = cursorMs,
                    segmentDurationMs = finalHoldMs,
                    isSlideBoundary = false,
                    isInjected = false,
                )
                stateSpans += StateTimeSpan(st.stateIndex, st.sceneIndex, st.frameIndex, cursorMs, finalHoldMs, isBoundary = false)
                cursorMs += finalHoldMs
                sceneEndTime[st.sceneIndex] = cursorMs
            }
        }
    }

    val slideBars = storyboard.scenes.mapIndexed { sIdx, scene ->
        val start = sceneStartTime[sIdx]
        val end = sceneEndTime[sIdx].coerceAtLeast(start + DEFAULT_BEAT_FLOOR_MS)
        SlideSectionBar(
            sceneIndex = sIdx,
            label = sceneDisplayTitle(sIdx, scene.frames.size),
            startMs = start,
            endMs = end,
            overlapInMs = if (sIdx > 0) DEFAULT_SLIDE_OVERLAP_MS else 0,
            overlapOutMs = if (sIdx < storyboard.scenes.lastIndex) DEFAULT_SLIDE_OVERLAP_MS else 0,
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
        val startMs = beatStartTimeMap[firstSighting]
            ?: slideBars.getOrNull(firstSighting.first)?.startMs
            ?: 0
        val lastBeatStart = beatStartTimeMap[lastSighting]
            ?: slideBars.getOrNull(lastSighting.first)?.startMs
            ?: startMs
        val trackId = "shared:$sharedKey"
        val customDur = overrides.tracks[trackId]?.durationMs ?: 420
        val spanEnd = maxOf(lastBeatStart + customDur, startMs + customDur)
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
                    durationMs = (spanEnd - startMs).coerceAtLeast(240),
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
            // Note: Beat `b > 0` starts during transition from Frame `b - 1` -> Frame `b`!
            val segmentStartMs = if (rec.triggerBeat > 0) {
                beatStartTimeMap[rec.sceneIndex to (rec.triggerBeat - 1)]
                    ?: beatStartTimeMap[rec.sceneIndex to rec.triggerBeat]
                    ?: slideBars.getOrNull(rec.sceneIndex)?.startMs
                    ?: 0
            } else {
                slideBars.getOrNull(rec.sceneIndex)?.startMs ?: 0
            }
            TimelineBlockBar(
                id = "${rec.trackId}@b${rec.triggerBeat}",
                trackId = rec.trackId,
                label = rec.label,
                category = rec.category,
                sceneIndex = rec.sceneIndex,
                triggerBeatInScene = rec.triggerBeat,
                startMs = segmentStartMs + rec.delayMs,
                durationMs = rec.durationMs,
                delayMs = rec.delayMs,
                staggerCount = rec.staggerCount,
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
        trackRows += TimelineTrackRow(
            trackId = trackId,
            label = "⟳ S${loopSceneIdx + 1} · ${loopDesc.label}",
            category = TrackCategory.LoopSubComp,
            sceneIndex = loopSceneIdx,
            blocks = listOf(
                TimelineBlockBar(
                    id = "$trackId-subcomp",
                    trackId = trackId,
                    label = "⟳ ${loopDesc.label} · ${loopDesc.defaultPeriodMillis}ms ▸",
                    category = TrackCategory.LoopSubComp,
                    sceneIndex = loopSceneIdx,
                    triggerBeatInScene = 0,
                    startMs = startMs,
                    durationMs = spanMs,
                    loopId = loopId,
                    loopPeriodMs = loopDesc.defaultPeriodMillis,
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

private fun sceneDisplayTitle(sceneIndex: Int, beatCount: Int): String = when (sceneIndex) {
    0 -> "S1 · Beats ($beatCount)"
    1 -> "S2 · Shared Move ($beatCount)"
    2 -> "S3 · Ambient Loop ($beatCount)"
    3 -> "S4 · Highlighted Code ($beatCount)"
    4 -> "S5 · Keynote Lab ($beatCount)"
    else -> "S${sceneIndex + 1} · Scene ($beatCount)"
}
