package dev.sebastiano.ciacola.timeline

import androidx.compose.runtime.Immutable
import dev.sebastiano.ciacola.core.fonts.DeckFontDeclaration
import dev.sebastiano.ciacola.document.BeatSpec
import dev.sebastiano.ciacola.document.Build
import dev.sebastiano.ciacola.document.BuildEffectRegistry
import dev.sebastiano.ciacola.document.DeckDocument
import dev.sebastiano.ciacola.document.DeckSpecimenRegistry
import dev.sebastiano.ciacola.document.DeckThemeSpec
import dev.sebastiano.ciacola.document.NodeKind
import dev.sebastiano.ciacola.document.NodeSpec
import dev.sebastiano.ciacola.document.SegmentPlan
import dev.sebastiano.ciacola.document.SegmentPlanner
import dev.sebastiano.ciacola.document.SlidePaletteSpec
import dev.sebastiano.ciacola.document.SlideSpec
import dev.sebastiano.ciacola.document.StageSize
import dev.sebastiano.ciacola.document.TrackKind
import dev.sebastiano.ciacola.document.TrackPlan
import dev.sebastiano.ciacola.document.TransitionSpec
import dev.sebastiano.ciacola.document.TransitionType

/**
 * Diagnostics reported by [DocumentTimelinePlannerCache.buildSnapshot] for incremental planning
 * verification and scale telemetry.
 */
@Immutable
data class DocumentTimelinePlanDiagnostics(
    val replannedSlideIds: Set<String>,
    val replannedTransitions: Set<Pair<String, String>>,
    val cachedSlideCount: Int,
    val cachedTransitionCount: Int,
    val planDurationNanos: Long,
    val estimatedCacheMemoryBytes: Long,
)

/**
 * Metadata linking a visual Timeline track/block back to the exact entity in [DeckDocument].
 */
@Immutable
sealed interface DocumentTimelineTrackRef {
    val trackId: String

    @Immutable
    data class SlideBuildTrack(
        override val trackId: String,
        val slideId: String,
        val sceneIndex: Int,
        val beatIndex: Int,
        val nodeId: String,
        val nodeKind: NodeKind?,
        val buildIndex: Int,
        val buildId: String?,
        val segmentStartMs: Int,
        val trackPlan: TrackPlan,
        val isTextMorph: Boolean,
        val isTextDelivery: Boolean,
        val isUnsequenced: Boolean,
    ) : DocumentTimelineTrackRef

    @Immutable
    data class SpecimenTrackBar(
        override val trackId: String,
        val slideId: String,
        val sceneIndex: Int,
        val beatIndex: Int,
        val nodeId: String,
        val specimenRef: String,
        val trackName: String,
        val segmentStartMs: Int,
        val trackPlan: TrackPlan,
    ) : DocumentTimelineTrackRef

    @Immutable
    data class TransitionTrackBar(
        override val trackId: String,
        val fromSlideId: String,
        val toSlideId: String,
        val fromSceneIndex: Int,
        val toSceneIndex: Int,
        val nodeId: String,
        val nodeKind: NodeKind?,
        val segmentStartMs: Int,
        val trackPlan: TrackPlan,
        val isTextMorph: Boolean,
    ) : DocumentTimelineTrackRef

    @Immutable
    data class LoopTrackBar(
        override val trackId: String,
        val loopId: String,
        val specimenRef: String,
        val slideId: String,
        val sceneIndex: Int,
        val nodeId: String,
        val loopName: String,
        val label: String,
        val defaultPeriodMs: Long,
        val effectivePeriodMs: Long,
        val perNode: Boolean,
        /** Every active slide (scene index) whose nodes declare or reference this loop's clock key. */
        val sceneIndices: Set<Int> = setOf(sceneIndex),
    ) : DocumentTimelineTrackRef
}

/**
 * Selectable beat header (`beats[beatIndex]`) on the master timeline.
 */
@Immutable
data class DocumentBeatHeaderInfo(
    val slideId: String,
    val sceneIndex: Int,
    val beatIndex: Int,
    val startMs: Int,
    val segmentMs: Int,
    val beatSpec: BeatSpec,
    val participatingNodeIds: List<String>,
) {
    val headerKey: String get() = "$slideId:b$beatIndex"
}

/**
 * Selectable cross-slide transition header (`fromSlide.transition`) on the master timeline.
 */
@Immutable
data class DocumentTransitionHeaderInfo(
    val fromSlideId: String,
    val toSlideId: String,
    val fromSceneIndex: Int,
    val toSceneIndex: Int,
    val startMs: Int,
    val segmentMs: Int,
    val transitionSpec: TransitionSpec,
    val participatingNodeIds: List<String>,
) {
    val headerKey: String get() = "$fromSlideId->$toSlideId"
}

/**
 * Complete analytical Timeline snapshot built directly from [DeckDocument] + [SegmentPlanner]
 * with zero offscreen Compose rendering or frame stepping.
 */
@Immutable
data class DocumentTimelineSnapshot(
    val snapshot: DeckTimelineSnapshot,
    val beatHeaders: List<DocumentBeatHeaderInfo>,
    val transitionHeaders: List<DocumentTransitionHeaderInfo>,
    val trackRefsByTrackId: Map<String, DocumentTimelineTrackRef>,
    val blockRefsByBlockId: Map<String, DocumentTimelineTrackRef>,
    val slideBeatPlans: Map<String, List<SegmentPlan>>,
    val transitionPlans: Map<Pair<String, String>, SegmentPlan>,
    val diagnostics: DocumentTimelinePlanDiagnostics,
) {
    fun beatHeader(slideId: String, beatIndex: Int): DocumentBeatHeaderInfo? =
        beatHeaders.firstOrNull { it.slideId == slideId && it.beatIndex == beatIndex }

    fun transitionHeader(fromSlideId: String, toSlideId: String): DocumentTransitionHeaderInfo? =
        transitionHeaders.firstOrNull { it.fromSlideId == fromSlideId && it.toSlideId == toSlideId }

    companion object {
        val Empty: DocumentTimelineSnapshot = DocumentTimelineSnapshot(
            snapshot = DeckTimelineSnapshot(
                totalDurationMs = 0,
                slides = emptyList(),
                eventBeats = emptyList(),
                tracks = emptyList(),
                stateSpans = emptyList(),
            ),
            beatHeaders = emptyList(),
            transitionHeaders = emptyList(),
            trackRefsByTrackId = emptyMap(),
            blockRefsByBlockId = emptyMap(),
            slideBeatPlans = emptyMap(),
            transitionPlans = emptyMap(),
            diagnostics = DocumentTimelinePlanDiagnostics(
                replannedSlideIds = emptySet(),
                replannedTransitions = emptySet(),
                cachedSlideCount = 0,
                cachedTransitionCount = 0,
                planDurationNanos = 0L,
                estimatedCacheMemoryBytes = 0L,
            ),
        )
    }
}

/**
 * Incremental analytical planner cache for Timeline Studio.
 *
 * Caches beat [SegmentPlan]s per slide and cross-slide transition [SegmentPlan]s per adjacent
 * non-skipped slide pair. Editing slide `N` re-plans ONLY slide `N` and its transitions
 * `N-1 -> N` and `N -> N+1`, then recomputes master-timeline offsets as an O(S) prefix sum.
 */
class DocumentTimelinePlannerCache {

    private data class CachedLoopDecl(
        val loopId: String,
        val specimenRef: String,
        val nodeId: String,
        val loopName: String,
        val label: String,
        val defaultPeriodMs: Long,
        val perNode: Boolean,
    )

    private data class CachedSlideEntry(
        val slide: SlideSpec,
        val theme: DeckThemeSpec?,
        val palette: SlidePaletteSpec?,
        val stage: StageSize,
        val fonts: List<DeckFontDeclaration>,
        val reducedMotion: Boolean,
        val specimens: DeckSpecimenRegistry,
        val effects: BuildEffectRegistry,
        val beatPlans: List<SegmentPlan>,
        val nodesById: Map<String, NodeSpec>,
        val loopDecls: List<CachedLoopDecl>,
    )

    private data class CachedTransitionEntry(
        val fromSlide: SlideSpec,
        val toSlide: SlideSpec,
        val theme: DeckThemeSpec?,
        val stage: StageSize,
        val fonts: List<DeckFontDeclaration>,
        val reducedMotion: Boolean,
        val specimens: DeckSpecimenRegistry,
        val effects: BuildEffectRegistry,
        val plan: SegmentPlan,
    )

    private val slideCache = LinkedHashMap<String, CachedSlideEntry>()
    private val transitionCache = LinkedHashMap<Pair<String, String>, CachedTransitionEntry>()

    val cachedSlideCount: Int get() = slideCache.size
    val cachedTransitionCount: Int get() = transitionCache.size

    fun clear() {
        slideCache.clear()
        transitionCache.clear()
    }

    /**
     * Builds (or incrementally updates) the analytical [DocumentTimelineSnapshot] for [document].
     */
    fun buildSnapshot(
        document: DeckDocument,
        themeName: String? = document.defaultTheme,
        reducedMotion: Boolean = false,
        specimens: DeckSpecimenRegistry = DeckSpecimenRegistry.Empty,
        effects: BuildEffectRegistry = BuildEffectRegistry.Standard,
    ): DocumentTimelineSnapshot {
        val startNanos = System.nanoTime()
        val activeTheme = resolveActiveTheme(document, themeName)
        val activeSlides = document.slides.filter { !it.skipped }

        val replannedSlides = LinkedHashSet<String>()
        val replannedTransitions = LinkedHashSet<Pair<String, String>>()

        // 1. Re-plan ONLY slides whose inputs changed.
        val activeSlideIds = HashSet<String>(activeSlides.size * 2)
        val slideEntries = ArrayList<CachedSlideEntry>(activeSlides.size)
        for (slide in activeSlides) {
            activeSlideIds.add(slide.id)
            val palette = slide.palette?.let { document.palettes[it] }
            val existing = slideCache[slide.id]
            val hit = existing != null &&
                sameRefOrValue(existing.slide, slide) &&
                sameRefOrValue(existing.theme, activeTheme) &&
                sameRefOrValue(existing.palette, palette) &&
                existing.stage == document.stage &&
                sameRefOrValue(existing.fonts, document.fonts) &&
                existing.reducedMotion == reducedMotion &&
                existing.specimens === specimens &&
                existing.effects === effects

            val entry = if (hit) {
                existing!!
            } else {
                replannedSlides += slide.id
                val beatPlans = List(slide.beats.size) { beatIdx ->
                    SegmentPlanner.planSegment(
                        document = document,
                        slideId = slide.id,
                        beat = beatIdx,
                        reducedMotion = reducedMotion,
                        specimens = specimens,
                        effects = effects,
                        themeName = themeName,
                    )
                }
                val nodesById = flattenNodesById(slide.nodes)
                val loops = collectSlideLoops(slide, nodesById, specimens)
                CachedSlideEntry(
                    slide = slide,
                    theme = activeTheme,
                    palette = palette,
                    stage = document.stage,
                    fonts = document.fonts,
                    reducedMotion = reducedMotion,
                    specimens = specimens,
                    effects = effects,
                    beatPlans = beatPlans,
                    nodesById = nodesById,
                    loopDecls = loops,
                ).also { slideCache[slide.id] = it }
            }
            slideEntries += entry
        }
        // Evict removed/skipped slides to keep memory bounded.
        slideCache.keys.retainAll(activeSlideIds)

        // 2. Re-plan ONLY transitions whose adjacent slide inputs changed.
        val activeTransitionKeys = HashSet<Pair<String, String>>(activeSlides.size * 2)
        val transitionEntries = ArrayList<CachedTransitionEntry>((activeSlides.size - 1).coerceAtLeast(0))
        for (i in 0 until activeSlides.lastIndex) {
            val fromSlide = activeSlides[i]
            val toSlide = activeSlides[i + 1]
            val key = fromSlide.id to toSlide.id
            activeTransitionKeys.add(key)
            val existing = transitionCache[key]
            val hit = existing != null &&
                sameRefOrValue(existing.fromSlide, fromSlide) &&
                sameRefOrValue(existing.toSlide, toSlide) &&
                sameRefOrValue(existing.theme, activeTheme) &&
                existing.stage == document.stage &&
                sameRefOrValue(existing.fonts, document.fonts) &&
                existing.reducedMotion == reducedMotion &&
                existing.specimens === specimens &&
                existing.effects === effects

            val entry = if (hit) {
                existing!!
            } else {
                replannedTransitions += key
                val plan = SegmentPlanner.planTransition(
                    document = document,
                    fromSlideId = fromSlide.id,
                    toSlideId = toSlide.id,
                    reducedMotion = reducedMotion,
                    specimens = specimens,
                    effects = effects,
                    themeName = themeName,
                )
                CachedTransitionEntry(
                    fromSlide = fromSlide,
                    toSlide = toSlide,
                    theme = activeTheme,
                    stage = document.stage,
                    fonts = document.fonts,
                    reducedMotion = reducedMotion,
                    specimens = specimens,
                    effects = effects,
                    plan = plan,
                ).also { transitionCache[key] = it }
            }
            transitionEntries += entry
        }
        transitionCache.keys.retainAll(activeTransitionKeys)

        // 3. Recompute master-timeline prefix sums and assemble track rows.
        val slideBars = ArrayList<SlideSectionBar>(activeSlides.size)
        val eventBeats = ArrayList<EventBeatLine>(activeSlides.size * 2)
        val stateSpans = ArrayList<StateTimeSpan>(activeSlides.size * 4)
        val trackRows = ArrayList<TimelineTrackRow>(activeSlides.size * 6)
        val beatHeaders = ArrayList<DocumentBeatHeaderInfo>(activeSlides.size * 2)
        val transitionHeaders = ArrayList<DocumentTransitionHeaderInfo>(transitionEntries.size)
        val trackRefsByTrackId = LinkedHashMap<String, DocumentTimelineTrackRef>()
        val blockRefsByBlockId = LinkedHashMap<String, DocumentTimelineTrackRef>()
        val slideBeatPlansOut = LinkedHashMap<String, List<SegmentPlan>>(activeSlides.size)
        val transitionPlansOut = LinkedHashMap<Pair<String, String>, SegmentPlan>(transitionEntries.size)

        var cursorMs = 0
        var globalStateIndex = 0
        var globalBeatIndex = 0
        val seenSharedLoops = LinkedHashSet<String>()

        // Associate every loop clock key with ALL slides that declare/reference it, so the
        // `Slide` scope shows a loop only on slides that actually use it.
        val loopSceneIndices = LinkedHashMap<String, MutableSet<Int>>()
        for ((sceneIdx, entry) in slideEntries.withIndex()) {
            for (loop in entry.loopDecls) {
                loopSceneIndices.getOrPut(loop.loopId) { LinkedHashSet() } += sceneIdx
            }
        }

        for ((sceneIdx, entry) in slideEntries.withIndex()) {
            val slide = entry.slide
            val overlapInMs = if (sceneIdx > 0) {
                transitionEntries[sceneIdx - 1].plan.segmentMs.coerceAtLeast(0)
            } else {
                0
            }
            val slideStartMs = (cursorMs - overlapInMs).coerceAtLeast(0)
            val trackSlideStartMs = cursorMs
            slideBeatPlansOut[slide.id] = entry.beatPlans

            // Match Animatic.buildStates: scenes after Scene 0 start with a Frame.Start boundary state.
            val sceneStartStateIdx: Int? = if (sceneIdx > 0) globalStateIndex++ else null

            for (beatIdx in slide.beats.indices) {
                val beatPlan = entry.beatPlans.getOrElse(beatIdx) { SegmentPlan.Empty }
                val beatStartMs = cursorMs
                val segmentMs = beatPlan.segmentMs.coerceAtLeast(0)

                val participatingNodeIds = beatPlan.tracks
                    .filter { it.kind != TrackKind.SpecimenTrack }
                    .map { it.nodeId }
                    .distinct()

                beatHeaders += DocumentBeatHeaderInfo(
                    slideId = slide.id,
                    sceneIndex = sceneIdx,
                    beatIndex = beatIdx,
                    startMs = beatStartMs,
                    segmentMs = segmentMs,
                    beatSpec = slide.beats[beatIdx],
                    participatingNodeIds = participatingNodeIds,
                )

                // Emit segment animation span (if any) before resting click hold, aligned 1:1 with Animatic.buildStates:
                // - beatIdx == 0 && sceneIdx > 0: Frame.Start (sceneStartStateIdx) -> Value(0)
                // - beatIdx > 0: Value(beatIdx - 1) (globalStateIndex - 1) -> Value(beatIdx)
                if (segmentMs > 0) {
                    if (beatIdx == 0 && sceneStartStateIdx != null) {
                        stateSpans += StateTimeSpan(
                            stateIndex = sceneStartStateIdx,
                            sceneIndex = sceneIdx,
                            frameIndex = -1,
                            startMs = cursorMs,
                            durationMs = segmentMs,
                            isBoundary = true,
                            isHold = false,
                        )
                        cursorMs += segmentMs
                    } else if (beatIdx > 0) {
                        stateSpans += StateTimeSpan(
                            stateIndex = (globalStateIndex - 1).coerceAtLeast(0),
                            sceneIndex = sceneIdx,
                            frameIndex = beatIdx - 1,
                            startMs = cursorMs,
                            durationMs = segmentMs,
                            isBoundary = false,
                            isHold = false,
                        )
                        cursorMs += segmentMs
                    }
                }

                // Resting beat marker + click hold on Frame.Value(beatIdx).
                val valueStateIndex = globalStateIndex++
                val holdStartMs = cursorMs
                val effectiveBeatSegmentMs = if (beatIdx == 0 && sceneIdx > 0) {
                    overlapInMs + segmentMs
                } else {
                    segmentMs
                }
                eventBeats += EventBeatLine(
                    beatGlobalIndex = globalBeatIndex++,
                    stateIndex = valueStateIndex,
                    sceneIndex = sceneIdx,
                    frameIndex = beatIdx,
                    timeMs = holdStartMs,
                    segmentDurationMs = effectiveBeatSegmentMs,
                    isSlideBoundary = beatIdx == 0 && sceneIdx > 0,
                    isInjected = false,
                )
                stateSpans += StateTimeSpan(
                    stateIndex = valueStateIndex,
                    sceneIndex = sceneIdx,
                    frameIndex = beatIdx,
                    startMs = holdStartMs,
                    durationMs = CLICK_HOLD_MS,
                    isBoundary = false,
                    isHold = true,
                )
                cursorMs += CLICK_HOLD_MS

                // Emit track rows for every planned track in this beat.
                for ((tIdx, trackPlan) in beatPlan.tracks.withIndex()) {
                    val node = entry.nodesById[trackPlan.nodeId]
                    val absStart = beatStartMs + trackPlan.startMs
                    val dur = trackPlan.durationMs
                    if (trackPlan.kind == TrackKind.SpecimenTrack) {
                        val trackName = trackPlan.buildId ?: "track$tIdx"
                        val trackId = "doc:${slide.id}:b$beatIdx:specimen:${trackPlan.nodeId}:$trackName"
                        val blockId = "$trackId#0"
                        val ref = DocumentTimelineTrackRef.SpecimenTrackBar(
                            trackId = trackId,
                            slideId = slide.id,
                            sceneIndex = sceneIdx,
                            beatIndex = beatIdx,
                            nodeId = trackPlan.nodeId,
                            specimenRef = node?.specimen?.ref.orEmpty(),
                            trackName = trackName,
                            segmentStartMs = beatStartMs,
                            trackPlan = trackPlan,
                        )
                        val block = TimelineBlockBar(
                            id = blockId,
                            trackId = trackId,
                            label = TimelineDisplayNames.humanizeId(trackName),
                            category = TrackCategory.Build,
                            sceneIndex = sceneIdx,
                            triggerBeatInScene = beatIdx,
                            startMs = absStart,
                            durationMs = dur,
                            delayMs = trackPlan.startMs,
                        )
                        trackRows += TimelineTrackRow(
                            trackId = trackId,
                            label = TimelineDisplayNames.specimenTrackLabel(beatIdx, node, trackPlan.nodeId, trackName, specimens),
                            category = TrackCategory.Build,
                            sceneIndex = sceneIdx,
                            blocks = listOf(block),
                        )
                        trackRefsByTrackId[trackId] = ref
                        blockRefsByBlockId[blockId] = ref
                    } else {
                        val buildIndex = trackPlan.buildIndex ?: 0
                        val build = node?.builds?.getOrNull(buildIndex)
                        val isTextMorph = node?.text != null && build is Build.Change && trackPlan.pieces != null
                        val isTextDelivery = node?.text != null && build !is Build.Change && trackPlan.pieces != null
                        val isUnsequenced = !trackPlan.isSequenced
                        val category = classifyTrackCategory(trackPlan, node, isTextMorph, isTextDelivery)
                        val trackId = "doc:${slide.id}:b$beatIdx:${trackPlan.nodeId}:$buildIndex"
                        val blockId = "$trackId#0"
                        val ref = DocumentTimelineTrackRef.SlideBuildTrack(
                            trackId = trackId,
                            slideId = slide.id,
                            sceneIndex = sceneIdx,
                            beatIndex = beatIdx,
                            nodeId = trackPlan.nodeId,
                            nodeKind = node?.kind,
                            buildIndex = buildIndex,
                            buildId = trackPlan.buildId,
                            segmentStartMs = beatStartMs,
                            trackPlan = trackPlan,
                            isTextMorph = isTextMorph,
                            isTextDelivery = isTextDelivery,
                            isUnsequenced = isUnsequenced,
                        )
                        val block = TimelineBlockBar(
                            id = blockId,
                            trackId = trackId,
                            label = formatBuildLabel(trackPlan, build),
                            category = category,
                            sceneIndex = sceneIdx,
                            triggerBeatInScene = beatIdx,
                            startMs = absStart,
                            durationMs = dur,
                            delayMs = trackPlan.startMs,
                            staggerCount = trackPlan.pieces?.slots?.count { it.address != dev.sebastiano.ciacola.document.Slot.SIZE_ADDRESS },
                        )
                        trackRows += TimelineTrackRow(
                            trackId = trackId,
                            label = TimelineDisplayNames.buildTrackLabel(beatIdx, node, trackPlan.nodeId, specimens),
                            category = category,
                            sceneIndex = sceneIdx,
                            blocks = listOf(block),
                        )
                        trackRefsByTrackId[trackId] = ref
                        blockRefsByBlockId[blockId] = ref
                    }
                }
            }

            val holdEndMs = cursorMs

            // Emit specimen loop tracks declared on this slide.
            for (loop in entry.loopDecls) {
                if (!loop.perNode && !seenSharedLoops.add(loop.loopId)) continue
                val effectivePeriod = document.loopPeriodsMs[loop.loopId] ?: loop.defaultPeriodMs
                val trackId = "doc:loop:${loop.loopId}"
                val blockId = "$trackId#0"
                val ref = DocumentTimelineTrackRef.LoopTrackBar(
                    trackId = trackId,
                    loopId = loop.loopId,
                    specimenRef = loop.specimenRef,
                    slideId = slide.id,
                    sceneIndex = sceneIdx,
                    nodeId = loop.nodeId,
                    loopName = loop.loopName,
                    label = loop.label,
                    defaultPeriodMs = loop.defaultPeriodMs,
                    effectivePeriodMs = effectivePeriod,
                    perNode = loop.perNode,
                    sceneIndices = loopSceneIndices[loop.loopId]?.toSet() ?: setOf(sceneIdx),
                )
                val spanMs = (holdEndMs - trackSlideStartMs).coerceAtLeast(effectivePeriod.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                val block = TimelineBlockBar(
                    id = blockId,
                    trackId = trackId,
                    label = "${loop.label} (${effectivePeriod}ms)",
                    category = TrackCategory.LoopSubComp,
                    sceneIndex = sceneIdx,
                    triggerBeatInScene = 0,
                    startMs = trackSlideStartMs,
                    durationMs = spanMs,
                    loopId = loop.loopId,
                    loopPeriodMs = effectivePeriod,
                )
                trackRows += TimelineTrackRow(
                    trackId = trackId,
                    label = "Loop · ${loop.label}",
                    category = TrackCategory.LoopSubComp,
                    sceneIndex = if (loop.perNode) sceneIdx else null,
                    blocks = listOf(block),
                )
                trackRefsByTrackId[trackId] = ref
                blockRefsByBlockId[blockId] = ref
            }

            // Cross-slide transition to next active slide (Animatic Frame.End -> next Frame.Start).
            var overlapOutMs = 0
            if (sceneIdx < transitionEntries.size) {
                val endStateIndex = globalStateIndex++
                val transEntry = transitionEntries[sceneIdx]
                val nextSlide = transEntry.toSlide
                val transPlan = transEntry.plan
                val transKey = slide.id to nextSlide.id
                transitionPlansOut[transKey] = transPlan

                val transStartMs = cursorMs
                val transDurationMs = transPlan.segmentMs.coerceAtLeast(0)
                overlapOutMs = transDurationMs
                val effectiveSpec = slide.transition
                    ?: activeTheme?.motion?.transition
                    ?: TransitionSpec(type = TransitionType.Dissolve)

                val transNodes = transPlan.tracks
                    .filter { it.kind != TrackKind.TransitionSlide }
                    .map { it.nodeId }
                    .distinct()

                transitionHeaders += DocumentTransitionHeaderInfo(
                    fromSlideId = slide.id,
                    toSlideId = nextSlide.id,
                    fromSceneIndex = sceneIdx,
                    toSceneIndex = sceneIdx + 1,
                    startMs = transStartMs,
                    segmentMs = transDurationMs,
                    transitionSpec = effectiveSpec,
                    participatingNodeIds = transNodes,
                )

                if (transDurationMs > 0) {
                    stateSpans += StateTimeSpan(
                        stateIndex = endStateIndex,
                        sceneIndex = sceneIdx,
                        frameIndex = slide.beats.size,
                        startMs = transStartMs,
                        durationMs = transDurationMs,
                        isBoundary = true,
                        isHold = false,
                    )
                    cursorMs += transDurationMs
                }

                val nextNodesById = slideEntries[sceneIdx + 1].nodesById
                for ((tIdx, trackPlan) in transPlan.tracks.withIndex()) {
                    val node = nextNodesById[trackPlan.nodeId] ?: entry.nodesById[trackPlan.nodeId]
                    val isTextMorph = trackPlan.kind == TrackKind.TransitionMove && trackPlan.pieces != null
                    val category = when {
                        isTextMorph -> TrackCategory.MagicText
                        trackPlan.kind == TrackKind.TransitionMove -> TrackCategory.SharedElement
                        else -> TrackCategory.Build
                    }
                    val trackId = "doc:trans:${slide.id}->${nextSlide.id}:${trackPlan.nodeId}:$tIdx"
                    val blockId = "$trackId#0"
                    val ref = DocumentTimelineTrackRef.TransitionTrackBar(
                        trackId = trackId,
                        fromSlideId = slide.id,
                        toSlideId = nextSlide.id,
                        fromSceneIndex = sceneIdx,
                        toSceneIndex = sceneIdx + 1,
                        nodeId = trackPlan.nodeId,
                        nodeKind = node?.kind,
                        segmentStartMs = transStartMs,
                        trackPlan = trackPlan,
                        isTextMorph = isTextMorph,
                    )
                    val transLabel = TimelineDisplayNames.transitionTrackLabel(
                        kind = trackPlan.kind,
                        toSlide = nextSlide,
                        toSceneIndex = sceneIdx + 1,
                        node = node,
                        nodeId = trackPlan.nodeId,
                        transitionType = effectiveSpec.type,
                        specimens = specimens,
                    )
                    val block = TimelineBlockBar(
                        id = blockId,
                        trackId = trackId,
                        label = transLabel,
                        category = category,
                        sceneIndex = sceneIdx,
                        endSceneIndex = sceneIdx + 1,
                        triggerBeatInScene = slide.beats.lastIndex.coerceAtLeast(0),
                        startMs = transStartMs + trackPlan.startMs,
                        durationMs = trackPlan.durationMs,
                        delayMs = trackPlan.startMs,
                        sharedKey = if (trackPlan.kind == TrackKind.TransitionMove) trackPlan.nodeId else null,
                        staggerCount = trackPlan.pieces?.slots?.count { it.address != dev.sebastiano.ciacola.document.Slot.SIZE_ADDRESS },
                    )
                    trackRows += TimelineTrackRow(
                        trackId = trackId,
                        label = transLabel,
                        category = category,
                        sceneIndex = sceneIdx,
                        blocks = listOf(block),
                    )
                    trackRefsByTrackId[trackId] = ref
                    blockRefsByBlockId[blockId] = ref
                }
            }

            slideBars += SlideSectionBar(
                sceneIndex = sceneIdx,
                label = TimelineDisplayNames.slideTitle(slide, sceneIdx),
                startMs = slideStartMs,
                endMs = cursorMs,
                overlapInMs = overlapInMs,
                overlapOutMs = overlapOutMs,
                beatCount = slide.beats.size,
            )
        }

        val elapsedNanos = System.nanoTime() - startNanos
        val estimatedBytes = estimateCacheMemoryBytes()

        return DocumentTimelineSnapshot(
            snapshot = DeckTimelineSnapshot(
                totalDurationMs = cursorMs.coerceAtLeast(CLICK_HOLD_MS),
                slides = slideBars,
                eventBeats = eventBeats,
                tracks = trackRows,
                stateSpans = stateSpans,
            ),
            beatHeaders = beatHeaders,
            transitionHeaders = transitionHeaders,
            trackRefsByTrackId = trackRefsByTrackId,
            blockRefsByBlockId = blockRefsByBlockId,
            slideBeatPlans = slideBeatPlansOut,
            transitionPlans = transitionPlansOut,
            diagnostics = DocumentTimelinePlanDiagnostics(
                replannedSlideIds = replannedSlides,
                replannedTransitions = replannedTransitions,
                cachedSlideCount = slideCache.size,
                cachedTransitionCount = transitionCache.size,
                planDurationNanos = elapsedNanos,
                estimatedCacheMemoryBytes = estimatedBytes,
            ),
        )
    }

    /**
     * Conservative upper-bound estimate of retained JVM heap memory held by the planner cache
     * (objects + track plans + piece slots + maps) for cache budget telemetry (`<= 50 MB`).
     */
    fun estimateCacheMemoryBytes(): Long {
        var bytes = 256L
        for (entry in slideCache.values) {
            bytes += 256L + entry.nodesById.size * 64L + entry.loopDecls.size * 128L
            for (plan in entry.beatPlans) {
                bytes += estimateSegmentPlanBytes(plan)
            }
        }
        for (entry in transitionCache.values) {
            bytes += 192L + estimateSegmentPlanBytes(entry.plan)
        }
        return bytes
    }

    private fun estimateSegmentPlanBytes(plan: SegmentPlan): Long {
        var bytes = 64L
        for (track in plan.tracks) {
            bytes += 128L + track.nodeId.length * 2L + (track.buildId?.length ?: 0) * 2L
            val slots = track.pieces?.slots
            if (slots != null) {
                bytes += 48L + slots.size * 96L
            }
            if (track.warpSchedule != null) {
                bytes += 256L
            }
        }
        return bytes
    }

    private fun resolveActiveTheme(document: DeckDocument, themeName: String?): DeckThemeSpec? =
        themeName?.let { document.themes[it] } ?: document.themes.values.firstOrNull()

    private fun <T> sameRefOrValue(a: T, b: T): Boolean = (a === b) || (a == b)

    private fun flattenNodesById(roots: List<NodeSpec>): Map<String, NodeSpec> {
        val out = LinkedHashMap<String, NodeSpec>()
        fun visit(nodes: List<NodeSpec>) {
            for (n in nodes) {
                out[n.id] = n
                if (n.children.isNotEmpty()) visit(n.children)
            }
        }
        visit(roots)
        return out
    }

    private fun collectSlideLoops(
        slide: SlideSpec,
        nodesById: Map<String, NodeSpec>,
        specimens: DeckSpecimenRegistry,
    ): List<CachedLoopDecl> {
        val out = mutableListOf<CachedLoopDecl>()
        for (node in nodesById.values) {
            val sp = node.specimen ?: continue
            val decl = specimens.find(sp.ref) ?: continue
            for (loop in decl.loops) {
                out += CachedLoopDecl(
                    loopId = loop.loopId(sp.ref, slide.id, node.id),
                    specimenRef = sp.ref,
                    nodeId = node.id,
                    loopName = loop.name,
                    label = loop.label,
                    defaultPeriodMs = loop.periodMs,
                    perNode = loop.perNode,
                )
            }
        }
        return out
    }

    private fun classifyTrackCategory(
        plan: TrackPlan,
        node: NodeSpec?,
        isTextMorph: Boolean,
        isTextDelivery: Boolean,
    ): TrackCategory = when {
        plan.warpSchedule != null -> TrackCategory.WarpCode
        isTextMorph || isTextDelivery -> TrackCategory.MagicText
        node?.connector != null -> TrackCategory.LineDraw
        plan.kind == TrackKind.TransitionMove -> TrackCategory.SharedElement
        else -> TrackCategory.Build
    }

    private fun formatBuildLabel(plan: TrackPlan, build: Build?): String = when (build) {
        is Build.Change -> if (plan.pieces != null) "Text morph" else if (plan.warpSchedule != null) "Code warp" else "Change"
        else -> TimelineDisplayNames.buildKindLabel(build, plan.kind)
    }
}
