package dev.sebastiano.ciacola.timeline

import androidx.compose.runtime.Immutable
import dev.sebastiano.ciacola.document.BeatSpec
import dev.sebastiano.ciacola.document.Build
import dev.sebastiano.ciacola.document.Curve
import dev.sebastiano.ciacola.document.DeckDocument
import dev.sebastiano.ciacola.document.DeckThemeSpec
import dev.sebastiano.ciacola.document.NodeSpec
import dev.sebastiano.ciacola.document.Phases
import dev.sebastiano.ciacola.document.Slot
import dev.sebastiano.ciacola.document.SlotPhase
import dev.sebastiano.ciacola.document.SpecimenTrackOverride
import dev.sebastiano.ciacola.document.Stagger
import dev.sebastiano.ciacola.document.StaggerOrder
import dev.sebastiano.ciacola.document.TextMorphSpec
import dev.sebastiano.ciacola.document.Timing
import dev.sebastiano.ciacola.document.TrackKind
import dev.sebastiano.ciacola.document.TransitionSpec
import dev.sebastiano.ciacola.document.warpSchedule
import kotlin.math.abs
import dev.sebastiano.ciacola.document.TransitionType

internal const val SEGMENT_PLANNER_DEFAULT_DURATION_MS: Int = 500
internal const val AUTO_CURRENT_SLIDE_TRACK_THRESHOLD: Int = 80

/**
 * Filter scope for tracks & headers shown in the analytical Timeline Studio.
 * Large decks (`tracks > 80`) automatically default to [CurrentSlide] until the user switches.
 */
enum class TimelineTrackFilterMode {
    CurrentSlide,
    AllSlides,
}

/**
 * Effective resolved phase timings matching [dev.sebastiano.ciacola.document.SegmentPlanner]
 * defaults so phase bars display real timing values rather than hardcoded placeholders.
 */
@Immutable
data class ResolvedPhaseTimings(
    val exit: Timing,
    val move: Timing,
    val enter: Timing,
)

/**
 * Active selection inside the document-backed Timeline Studio (tracks, beat headers, transition
 * headers, loops, and intra-text fragments).
 */
@Immutable
sealed interface DocumentTimelineSelection {
    data object None : DocumentTimelineSelection

    @Immutable
    data class Track(
        val trackId: String,
        val blockId: String? = null,
    ) : DocumentTimelineSelection

    @Immutable
    data class BeatHeader(
        val slideId: String,
        val beatIndex: Int,
    ) : DocumentTimelineSelection

    @Immutable
    data class TransitionHeader(
        val fromSlideId: String,
        val toSlideId: String,
    ) : DocumentTimelineSelection

    @Immutable
    data class Loop(
        val loopId: String,
    ) : DocumentTimelineSelection
}

/**
 * Pure document query, phase-resolution, and immutable transform helpers backing
 * [DocumentTimelineController].
 */
internal object DocumentTimelineMutationSupport {
    fun resolveActiveTheme(doc: DeckDocument, themeName: String?): DeckThemeSpec? {
        val name = themeName ?: doc.defaultTheme
        return name?.let { doc.themes[it] } ?: doc.themes.values.firstOrNull()
    }

    fun Stagger.pruneIfDefault(): Stagger? =
        if (ms == 0 && order == StaggerOrder.Document && !reverse && manual.isEmpty()) null else this

    fun fragmentSlotsFor(ref: DocumentTimelineTrackRef?): List<Slot> {
        val pieces = when (ref) {
            is DocumentTimelineTrackRef.SlideBuildTrack -> ref.trackPlan.pieces
            is DocumentTimelineTrackRef.TransitionTrackBar -> ref.trackPlan.pieces
            else -> null
        } ?: return emptyList()
        return pieces.slots.filter { it.address != Slot.SIZE_ADDRESS }
    }

    fun effectiveTransitionSpec(
        doc: DeckDocument,
        themeName: String?,
        fromSlideId: String,
    ): TransitionSpec {
        val slide = doc.slides.firstOrNull { it.id == fromSlideId }
        val activeTheme = resolveActiveTheme(doc, themeName)
        return slide?.transition
            ?: activeTheme?.motion?.transition
            ?: TransitionSpec(type = TransitionType.Dissolve)
    }

    fun resolvedBeatPhases(
        doc: DeckDocument,
        themeName: String?,
        snapshot: DocumentTimelineSnapshot,
        slideId: String,
        beatIndex: Int,
    ): ResolvedPhaseTimings {
        val themeMotion = resolveActiveTheme(doc, themeName)?.motion
        val defaultDur = themeMotion?.durationMs ?: SEGMENT_PLANNER_DEFAULT_DURATION_MS
        val defaultCurve = themeMotion?.curve ?: Curve.EaseInOut
        val beat = doc.slides.firstOrNull { it.id == slideId }?.beats?.getOrNull(beatIndex)
        val phases = beat?.phases
        val plan = snapshot.slideBeatPlans[slideId]?.getOrNull(beatIndex)

        fun resolvePhase(explicit: Timing?, kind: TrackKind): Timing {
            val matchingTrack = plan?.tracks?.firstOrNull { it.kind == kind }
            val delay = explicit?.delayMs ?: 0
            val curve = explicit?.curve ?: matchingTrack?.curve ?: defaultCurve
            val dur = explicit?.durationMs ?: matchingTrack?.durationMs ?: defaultDur
            return Timing(delayMs = delay, durationMs = dur, curve = curve)
        }
        return ResolvedPhaseTimings(
            exit = resolvePhase(phases?.exit, TrackKind.Exit),
            move = resolvePhase(phases?.move, TrackKind.Change),
            enter = resolvePhase(phases?.enter, TrackKind.Enter),
        )
    }

    fun resolvedTransitionPhases(
        doc: DeckDocument,
        themeName: String?,
        fromSlideId: String,
    ): ResolvedPhaseTimings {
        val themeMotion = resolveActiveTheme(doc, themeName)?.motion
        val transition = effectiveTransitionSpec(doc, themeName, fromSlideId)
        val defaultDur = transition.durationMs ?: themeMotion?.durationMs ?: SEGMENT_PLANNER_DEFAULT_DURATION_MS
        val defaultCurve = transition.curve ?: themeMotion?.curve ?: Curve.EaseInOut
        val phases = transition.phases

        fun resolvePhase(explicit: Timing?): Timing = Timing(
            delayMs = explicit?.delayMs ?: 0,
            durationMs = explicit?.durationMs ?: defaultDur,
            curve = explicit?.curve ?: defaultCurve,
        )
        return ResolvedPhaseTimings(
            exit = resolvePhase(phases?.exit),
            move = resolvePhase(phases?.move),
            enter = resolvePhase(phases?.enter),
        )
    }

    fun resolvedTextMorphPhases(
        doc: DeckDocument,
        themeName: String?,
        ref: DocumentTimelineTrackRef,
    ): ResolvedPhaseTimings {
        val morph = currentTextMorphSpec(doc, ref)
        val parentPhases = when (ref) {
            is DocumentTimelineTrackRef.SlideBuildTrack ->
                doc.slides.firstOrNull { it.id == ref.slideId }?.beats?.getOrNull(ref.beatIndex)?.phases
            is DocumentTimelineTrackRef.TransitionTrackBar ->
                effectiveTransitionSpec(doc, themeName, ref.fromSlideId).phases
            else -> null
        }
        val themeMotion = resolveActiveTheme(doc, themeName)?.motion
        val defaultDur = themeMotion?.durationMs ?: SEGMENT_PLANNER_DEFAULT_DURATION_MS
        val defaultCurve = themeMotion?.curve ?: Curve.EaseInOut
        val slots = fragmentSlotsFor(ref)

        fun resolvePhase(phase: SlotPhase, morphT: Timing?, parentT: Timing?): Timing {
            val phaseSlots = slots.filter { it.phase == phase }
            val slotDelay = phaseSlots.minOfOrNull { it.startMs }
            val slotDur = phaseSlots.firstOrNull()?.durationMs
            val slotCurve = phaseSlots.firstOrNull()?.curve
            return Timing(
                delayMs = morphT?.delayMs ?: parentT?.delayMs ?: slotDelay ?: 0,
                durationMs = morphT?.durationMs ?: parentT?.durationMs ?: slotDur ?: defaultDur,
                curve = morphT?.curve ?: parentT?.curve ?: slotCurve ?: defaultCurve,
            )
        }
        return ResolvedPhaseTimings(
            exit = resolvePhase(SlotPhase.Exit, morph?.phases?.exit, parentPhases?.exit),
            move = resolvePhase(SlotPhase.Move, morph?.phases?.move, parentPhases?.move),
            enter = resolvePhase(SlotPhase.Enter, morph?.phases?.enter, parentPhases?.enter),
        )
    }

    fun plannedNodeDelayMs(
        doc: DeckDocument,
        snapshot: DocumentTimelineSnapshot,
        slideId: String,
        beatIndex: Int,
        nodeId: String,
    ): Int {
        val slide = doc.slides.firstOrNull { it.id == slideId }
        val manual = slide?.beats?.getOrNull(beatIndex)?.stagger?.manual?.get(nodeId)
        if (manual != null) return manual
        val plan = snapshot.slideBeatPlans[slideId]?.getOrNull(beatIndex)
        val track = plan?.tracks?.firstOrNull { it.nodeId == nodeId && it.kind != TrackKind.SpecimenTrack }
            ?: return 0
        val phaseDelay = when (track.kind) {
            TrackKind.Exit -> slide?.beats?.getOrNull(beatIndex)?.phases?.exit?.delayMs ?: 0
            TrackKind.Enter -> slide?.beats?.getOrNull(beatIndex)?.phases?.enter?.delayMs ?: 0
            else -> slide?.beats?.getOrNull(beatIndex)?.phases?.move?.delayMs ?: 0
        }
        return (track.startMs - phaseDelay).coerceAtLeast(0)
    }

    fun plannedTransitionNodeDelayMs(
        doc: DeckDocument,
        themeName: String?,
        snapshot: DocumentTimelineSnapshot,
        fromSlideId: String,
        toSlideId: String,
        nodeId: String,
    ): Int {
        val tr = effectiveTransitionSpec(doc, themeName, fromSlideId)
        val manual = tr.stagger?.manual?.get(nodeId)
        if (manual != null) return manual
        val plan = snapshot.transitionPlans[fromSlideId to toSlideId]
        val track = plan?.tracks?.firstOrNull { it.nodeId == nodeId && it.kind != TrackKind.TransitionSlide }
            ?: return 0
        val baseDelay = (tr.delayMs ?: 0) + when (track.kind) {
            TrackKind.TransitionExit -> tr.phases?.exit?.delayMs ?: 0
            TrackKind.TransitionEnter -> tr.phases?.enter?.delayMs ?: 0
            else -> tr.phases?.move?.delayMs ?: 0
        }
        return (track.startMs - baseDelay).coerceAtLeast(0)
    }

    fun currentFragmentManualDelays(doc: DeckDocument, ref: DocumentTimelineTrackRef): Map<String, Int> = when (ref) {
        is DocumentTimelineTrackRef.SlideBuildTrack -> {
            val node = findNodeOnSlide(doc, ref.slideId, ref.nodeId)
            val build = node?.builds?.getOrNull(ref.buildIndex)
            when (build) {
                is Build.Change -> (build.morph ?: node.text?.morph)?.stagger?.manual.orEmpty()
                is Build.Enter -> build.stagger?.manual.orEmpty()
                is Build.Exit -> build.stagger?.manual.orEmpty()
                is Build.Emphasis -> build.stagger?.manual.orEmpty()
                null -> emptyMap()
            }
        }
        is DocumentTimelineTrackRef.TransitionTrackBar -> {
            val fromSlide = doc.slides.firstOrNull { it.id == ref.fromSlideId }
            val spec = fromSlide?.transition?.text?.get(ref.nodeId)
                ?: findNodeOnSlide(doc, ref.toSlideId, ref.nodeId)?.text?.morph
                ?: findNodeOnSlide(doc, ref.fromSlideId, ref.nodeId)?.text?.morph
            spec?.stagger?.manual.orEmpty()
        }
        else -> emptyMap()
    }

    fun currentTextMorphSpec(doc: DeckDocument, ref: DocumentTimelineTrackRef): TextMorphSpec? = when (ref) {
        is DocumentTimelineTrackRef.SlideBuildTrack -> {
            if (!ref.isTextMorph) return null
            val node = findNodeOnSlide(doc, ref.slideId, ref.nodeId)
            val build = node?.builds?.getOrNull(ref.buildIndex) as? Build.Change
            build?.morph ?: node?.text?.morph ?: TextMorphSpec()
        }
        is DocumentTimelineTrackRef.TransitionTrackBar -> {
            if (!ref.isTextMorph) return null
            val fromSlide = doc.slides.firstOrNull { it.id == ref.fromSlideId }
            fromSlide?.transition?.text?.get(ref.nodeId)
                ?: findNodeOnSlide(doc, ref.toSlideId, ref.nodeId)?.text?.morph
                ?: findNodeOnSlide(doc, ref.fromSlideId, ref.nodeId)?.text?.morph
                ?: TextMorphSpec()
        }
        else -> null
    }

    fun mutateTextTrackStaggerDoc(
        doc: DeckDocument,
        themeName: String?,
        ref: DocumentTimelineTrackRef,
        transform: (Stagger) -> Stagger?,
    ): DeckDocument = when (ref) {
        is DocumentTimelineTrackRef.SlideBuildTrack -> updateNodeOnSlide(doc, ref.slideId, ref.nodeId) { node ->
            val build = node.builds.getOrNull(ref.buildIndex) ?: return@updateNodeOnSlide node
            val updatedBuilds = node.builds.toMutableList()
            updatedBuilds[ref.buildIndex] = when (build) {
                is Build.Change -> {
                    val baseMorph = build.morph ?: node.text?.morph ?: TextMorphSpec()
                    val nextStagger = transform(baseMorph.stagger ?: Stagger())
                    build.copy(morph = baseMorph.copy(stagger = nextStagger))
                }
                is Build.Enter -> build.copy(stagger = transform(build.stagger ?: Stagger()))
                is Build.Exit -> build.copy(stagger = transform(build.stagger ?: Stagger()))
                is Build.Emphasis -> build.copy(stagger = transform(build.stagger ?: Stagger()))
            }
            node.copy(builds = updatedBuilds)
        }
        is DocumentTimelineTrackRef.TransitionTrackBar -> updateSlideTransition(doc, themeName, ref.fromSlideId) { tr ->
            val baseMorph = tr.text[ref.nodeId]
                ?: findNodeOnSlide(doc, ref.toSlideId, ref.nodeId)?.text?.morph
                ?: findNodeOnSlide(doc, ref.fromSlideId, ref.nodeId)?.text?.morph
                ?: TextMorphSpec()
            val nextStagger = transform(baseMorph.stagger ?: Stagger())
            val nextMap = LinkedHashMap(tr.text)
            nextMap[ref.nodeId] = baseMorph.copy(stagger = nextStagger)
            tr.copy(text = nextMap)
        }
        else -> doc
    }

    fun updateTextMorphPhaseTimingDoc(
        doc: DeckDocument,
        themeName: String?,
        ref: DocumentTimelineTrackRef,
        phase: SlotPhase,
        delayMs: Int?,
        durationMs: Int?,
        curve: Curve?,
    ): DeckDocument = when (ref) {
        is DocumentTimelineTrackRef.SlideBuildTrack -> updateNodeOnSlide(doc, ref.slideId, ref.nodeId) { node ->
            val build = node.builds.getOrNull(ref.buildIndex) as? Build.Change ?: return@updateNodeOnSlide node
            val baseMorph = build.morph ?: node.text?.morph ?: TextMorphSpec()
            val nextMorph = baseMorph.copy(
                phases = updatePhases(baseMorph.phases, phase, delayMs, durationMs, curve),
            )
            val updatedBuilds = node.builds.toMutableList()
            updatedBuilds[ref.buildIndex] = build.copy(morph = nextMorph)
            node.copy(builds = updatedBuilds)
        }
        is DocumentTimelineTrackRef.TransitionTrackBar -> updateSlideTransition(doc, themeName, ref.fromSlideId) { tr ->
            val baseMorph = tr.text[ref.nodeId]
                ?: findNodeOnSlide(doc, ref.toSlideId, ref.nodeId)?.text?.morph
                ?: findNodeOnSlide(doc, ref.fromSlideId, ref.nodeId)?.text?.morph
                ?: TextMorphSpec()
            val nextMorph = baseMorph.copy(
                phases = updatePhases(baseMorph.phases, phase, delayMs, durationMs, curve),
            )
            val nextMap = LinkedHashMap(tr.text)
            nextMap[ref.nodeId] = nextMorph
            tr.copy(text = nextMap)
        }
        else -> doc
    }

    fun updateBuildTimingDoc(
        doc: DeckDocument,
        slideId: String,
        nodeId: String,
        buildIndex: Int,
        delayMs: Int?,
        durationMs: Int?,
        curve: Curve?,
    ): DeckDocument = updateNodeOnSlide(doc, slideId, nodeId) { node ->
        if (buildIndex !in node.builds.indices) return@updateNodeOnSlide node
        val updated = node.builds.toMutableList()
        updated[buildIndex] = updated[buildIndex].withTiming(
            delayMs = delayMs?.coerceAtLeast(0),
            durationMs = durationMs?.coerceAtLeast(0),
            curve = curve,
        )
        node.copy(builds = updated)
    }

    fun updateSpecimenTrackDoc(
        doc: DeckDocument,
        slideId: String,
        nodeId: String,
        trackName: String,
        delayMs: Int?,
        durationMs: Int?,
        curve: Curve?,
    ): DeckDocument = updateNodeOnSlide(doc, slideId, nodeId) { node ->
        val sp = node.specimen ?: return@updateNodeOnSlide node
        val prev = sp.tracks[trackName] ?: SpecimenTrackOverride()
        val next = prev.copy(
            delayMs = delayMs?.coerceAtLeast(0) ?: prev.delayMs,
            durationMs = durationMs?.coerceAtLeast(0) ?: prev.durationMs,
            curve = curve ?: prev.curve,
        )
        val nextMap = LinkedHashMap(sp.tracks)
        nextMap[trackName] = next
        node.copy(specimen = sp.copy(tracks = nextMap))
    }

    fun updateBeatSpec(
        doc: DeckDocument,
        slideId: String,
        beatIndex: Int,
        transform: (BeatSpec) -> BeatSpec,
    ): DeckDocument {
        val sIdx = doc.slides.indexOfFirst { it.id == slideId }
        if (sIdx < 0) return doc
        val slide = doc.slides[sIdx]
        if (beatIndex !in slide.beats.indices) return doc
        val updatedBeats = slide.beats.toMutableList()
        updatedBeats[beatIndex] = transform(updatedBeats[beatIndex])
        val updatedSlides = doc.slides.toMutableList()
        updatedSlides[sIdx] = slide.copy(beats = updatedBeats)
        return doc.copy(slides = updatedSlides)
    }

    fun updateSlideTransition(
        doc: DeckDocument,
        themeName: String?,
        fromSlideId: String,
        transform: (TransitionSpec) -> TransitionSpec,
    ): DeckDocument {
        val sIdx = doc.slides.indexOfFirst { it.id == fromSlideId }
        if (sIdx < 0) return doc
        val slide = doc.slides[sIdx]
        val base = slide.transition
            ?: resolveActiveTheme(doc, themeName)?.motion?.transition
            ?: TransitionSpec(type = TransitionType.Dissolve)
        val updatedSlides = doc.slides.toMutableList()
        updatedSlides[sIdx] = slide.copy(transition = transform(base))
        return doc.copy(slides = updatedSlides)
    }

    fun updateNodeOnSlide(
        doc: DeckDocument,
        slideId: String,
        nodeId: String,
        transform: (NodeSpec) -> NodeSpec,
    ): DeckDocument {
        val sIdx = doc.slides.indexOfFirst { it.id == slideId }
        if (sIdx < 0) return doc
        val slide = doc.slides[sIdx]
        val updatedNodes = slide.nodes.map { rewriteNode(it, nodeId, transform) }
        val updatedSlides = doc.slides.toMutableList()
        updatedSlides[sIdx] = slide.copy(nodes = updatedNodes)
        return doc.copy(slides = updatedSlides)
    }

    fun rewriteNode(node: NodeSpec, targetId: String, transform: (NodeSpec) -> NodeSpec): NodeSpec {
        val withChildren = when {
            node.stack != null -> node.copy(
                stack = node.stack!!.copy(children = node.stack!!.children.map { rewriteNode(it, targetId, transform) }),
            )
            node.row != null -> node.copy(
                row = node.row!!.copy(children = node.row!!.children.map { rewriteNode(it, targetId, transform) }),
            )
            node.column != null -> node.copy(
                column = node.column!!.copy(children = node.column!!.children.map { rewriteNode(it, targetId, transform) }),
            )
            node.loupe != null -> node.copy(
                loupe = node.loupe!!.copy(children = node.loupe!!.children.map { rewriteNode(it, targetId, transform) }),
            )
            else -> node
        }
        return if (withChildren.id == targetId) transform(withChildren) else withChildren
    }

    fun findNodeOnSlide(doc: DeckDocument, slideId: String, nodeId: String): NodeSpec? {
        val slide = doc.slides.firstOrNull { it.id == slideId } ?: return null
        fun search(nodes: List<NodeSpec>): NodeSpec? {
            for (n in nodes) {
                if (n.id == nodeId) return n
                search(n.children)?.let { return it }
            }
            return null
        }
        return search(slide.nodes)
    }

    fun updatePhases(
        phases: Phases?,
        phase: SlotPhase,
        delayMs: Int?,
        durationMs: Int?,
        curve: Curve?,
        keepExistingDelay: Boolean = true,
        keepExistingDuration: Boolean = true,
        keepExistingCurve: Boolean = true,
    ): Phases {
        val base = phases ?: Phases()
        fun merge(existing: Timing?): Timing = Timing(
            delayMs = delayMs?.coerceAtLeast(0) ?: if (keepExistingDelay) existing?.delayMs else null,
            durationMs = durationMs?.coerceAtLeast(0) ?: if (keepExistingDuration) existing?.durationMs else null,
            curve = curve ?: if (keepExistingCurve) existing?.curve else null,
        )
        return when (phase) {
            SlotPhase.Exit -> base.copy(exit = merge(base.exit))
            SlotPhase.Move -> base.copy(move = merge(base.move))
            SlotPhase.Enter -> base.copy(enter = merge(base.enter))
        }
    }

    fun phaseDelayForBuild(doc: DeckDocument, ref: DocumentTimelineTrackRef.SlideBuildTrack): Int {
        val slide = doc.slides.firstOrNull { it.id == ref.slideId } ?: return 0
        val beat = slide.beats.getOrNull(ref.beatIndex) ?: return 0
        val phases = beat.phases ?: return 0
        val timing = when (ref.trackPlan.kind) {
            TrackKind.Exit -> phases.exit
            TrackKind.Enter -> phases.enter
            else -> phases.move
        }
        return timing?.delayMs ?: 0
    }

    fun findBuildDelayMs(doc: DeckDocument, ref: DocumentTimelineTrackRef.SlideBuildTrack): Int {
        val node = findNodeOnSlide(doc, ref.slideId, ref.nodeId) ?: return 0
        return node.builds.getOrNull(ref.buildIndex)?.delayMs ?: 0
    }

    fun dependencyEndMsForSequencedBuild(
        snapshot: DocumentTimelineSnapshot,
        ref: DocumentTimelineTrackRef.SlideBuildTrack,
    ): Int {
        val beatPlan = snapshot.slideBeatPlans[ref.slideId]?.getOrNull(ref.beatIndex)
        val planned = beatPlan?.tracks?.firstOrNull {
            it.nodeId == ref.nodeId && it.buildIndex == ref.buildIndex
        } ?: ref.trackPlan
        return planned.dependencyEndMs
    }

    /**
     * Converts a requested total displayed track span [requestedSpanMs] into the per-fragment
     * or token `build.durationMs` to persist on [ref]:
     * - Code warp: inverts [warpSchedule]'s `totalSpanMs`.
     * - Staggered delivery (`by != Whole`): subtracts the max fragment start offset.
     * - Text morph: subtracts the max start offset of slots inheriting `build.durationMs`.
     * - Whole build: returns [requestedSpanMs] unchanged.
     */
    fun buildDurationForTotalSpan(
        doc: DeckDocument,
        snapshot: DocumentTimelineSnapshot,
        ref: DocumentTimelineTrackRef.SlideBuildTrack,
        requestedSpanMs: Int,
    ): Int {
        val clamped = requestedSpanMs.coerceAtLeast(0)
        val beatPlan = snapshot.slideBeatPlans[ref.slideId]?.getOrNull(ref.beatIndex)
        val planned = beatPlan?.tracks?.firstOrNull {
            it.nodeId == ref.nodeId && it.buildIndex == ref.buildIndex
        } ?: ref.trackPlan

        if (planned.warpSchedule != null) {
            return invertWarpSpanToTokenDurationMs(clamped)
        }
        val pieces = planned.pieces ?: return clamped
        if (pieces.slots.isEmpty()) return clamped

        if (!ref.isTextMorph) {
            val maxSlotStart = pieces.slots.maxOf { it.startMs }
            return (clamped - maxSlotStart).coerceAtLeast(0)
        }

        val slide = doc.slides.firstOrNull { it.id == ref.slideId }
        val node = findNodeOnSlide(doc, ref.slideId, ref.nodeId)
        val build = node?.builds?.getOrNull(ref.buildIndex) as? Build.Change
        val effectivePhases = build?.morph?.phases ?: node?.text?.morph?.phases ?: slide?.beats?.getOrNull(ref.beatIndex)?.phases
        val inheritedSlots = pieces.slots.filter { phaseExplicitDuration(effectivePhases, it.phase) == null }
        val maxSlotStart = (inheritedSlots.ifEmpty { pieces.slots }).maxOf { it.startMs }
        return (clamped - maxSlotStart).coerceAtLeast(0)
    }

    /**
     * Converts a requested total displayed track span [requestedSpanMs] into the transition phase
     * duration for [ref], subtracting staggered morph slot offsets when [ref] is a text morph.
     */
    fun transitionPhaseDurationForTotalSpan(
        doc: DeckDocument,
        themeName: String?,
        snapshot: DocumentTimelineSnapshot,
        ref: DocumentTimelineTrackRef.TransitionTrackBar,
        requestedSpanMs: Int,
    ): Int {
        val clamped = requestedSpanMs.coerceAtLeast(0)
        if (!ref.isTextMorph) return clamped
        val trPlan = snapshot.transitionPlans[ref.fromSlideId to ref.toSlideId]
        val planned = trPlan?.tracks?.firstOrNull {
            it.nodeId == ref.nodeId && it.kind == ref.trackPlan.kind
        } ?: ref.trackPlan
        val pieces = planned.pieces ?: return clamped
        if (pieces.slots.isEmpty()) return clamped

        val tr = effectiveTransitionSpec(doc, themeName, ref.fromSlideId)
        val toNode = findNodeOnSlide(doc, ref.toSlideId, ref.nodeId)
        val fromNode = findNodeOnSlide(doc, ref.fromSlideId, ref.nodeId)
        val morphPhases = (tr.text[ref.nodeId] ?: toNode?.text?.morph ?: fromNode?.text?.morph)?.phases
        val effectivePhases = morphPhases ?: tr.phases
        val inheritedSlots = pieces.slots.filter {
            it.phase == SlotPhase.Move || phaseExplicitDuration(effectivePhases, it.phase) == null
        }
        val maxSlotStart = (inheritedSlots.ifEmpty { pieces.slots }).maxOf { it.startMs }
        return (clamped - maxSlotStart).coerceAtLeast(0)
    }

    private fun phaseExplicitDuration(phases: Phases?, phase: SlotPhase): Int? = when (phase) {
        SlotPhase.Exit -> phases?.exit?.durationMs
        SlotPhase.Move -> phases?.move?.durationMs
        SlotPhase.Enter -> phases?.enter?.durationMs
    }

    private fun invertWarpSpanToTokenDurationMs(targetSpanMs: Int): Int {
        if (targetSpanMs <= 0) return 0
        var lo = 0
        var hi = targetSpanMs * 2 + 200
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (warpSchedule(mid, reducedMotion = false).totalSpanMs < targetSpanMs) {
                lo = mid + 1
            } else {
                hi = mid
            }
        }
        if (lo == 0) return 0
        val loDiff = abs(warpSchedule(lo, reducedMotion = false).totalSpanMs - targetSpanMs)
        val prevDiff = abs(warpSchedule(lo - 1, reducedMotion = false).totalSpanMs - targetSpanMs)
        return if (prevDiff <= loDiff) lo - 1 else lo
    }

    fun transitionTrackBaseDelay(
        doc: DeckDocument,
        themeName: String?,
        ref: DocumentTimelineTrackRef.TransitionTrackBar,
    ): Int {
        val tr = doc.slides.firstOrNull { it.id == ref.fromSlideId }?.transition
            ?: resolveActiveTheme(doc, themeName)?.motion?.transition
            ?: return 0
        val trDelay = tr.delayMs ?: 0
        val phases = tr.phases
        val phaseDelay = when (ref.trackPlan.kind) {
            TrackKind.TransitionExit -> phases?.exit?.delayMs ?: 0
            TrackKind.TransitionEnter -> phases?.enter?.delayMs ?: 0
            else -> phases?.move?.delayMs ?: 0
        }
        return trDelay + phaseDelay
    }

    fun phaseForTransitionKind(kind: TrackKind): SlotPhase? = when (kind) {
        TrackKind.TransitionExit -> SlotPhase.Exit
        TrackKind.TransitionMove -> SlotPhase.Move
        TrackKind.TransitionEnter -> SlotPhase.Enter
        else -> null
    }

    private fun Build.withTiming(delayMs: Int?, durationMs: Int?, curve: Curve?): Build = when (this) {
        is Build.Enter -> copy(
            delayMs = delayMs ?: this.delayMs,
            durationMs = durationMs ?: this.durationMs,
            curve = curve ?: this.curve,
        )
        is Build.Exit -> copy(
            delayMs = delayMs ?: this.delayMs,
            durationMs = durationMs ?: this.durationMs,
            curve = curve ?: this.curve,
        )
        is Build.Emphasis -> copy(
            delayMs = delayMs ?: this.delayMs,
            durationMs = durationMs ?: this.durationMs,
            curve = curve ?: this.curve,
        )
        is Build.Change -> copy(
            delayMs = delayMs ?: this.delayMs,
            durationMs = durationMs ?: this.durationMs,
            curve = curve ?: this.curve,
        )
    }
}
