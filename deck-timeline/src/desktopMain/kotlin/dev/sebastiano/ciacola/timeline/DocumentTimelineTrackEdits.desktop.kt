package dev.sebastiano.ciacola.timeline

import dev.sebastiano.ciacola.document.Curve
import dev.sebastiano.ciacola.document.TrackKind

/**
 * Track-bar direct manipulation (move start, resize duration, curve) for
 * [DocumentTimelineController], routed through the controller's transaction-aware mutation path.
 */
internal object DocumentTimelineTrackEdits {

    /**
     * Adjusts a track bar's start offset in the Timeline:
     * - Unsequenced beat builds write `beats[b].stagger.manual[nodeId]` subtracting phase delay and `build.delayMs`.
     * - Sequenced beat builds (`after` / prior same-node build) write `build.delayMs = max(0, target - depEnd)`.
     * - Cross-slide transition node tracks write `slide.transition.stagger.manual[nodeId]`.
     * - Specimen tracks write `specimen.tracks[trackName].delayMs`.
     */
    fun dragTrackBarStart(
        c: DocumentTimelineController,
        ref: DocumentTimelineTrackRef,
        newRelativeStartMs: Int,
        useActiveGesture: Boolean,
    ): Boolean {
        val clamped = newRelativeStartMs.coerceAtLeast(0)
        val doc = c.store.document
        return when (ref) {
            is DocumentTimelineTrackRef.SlideBuildTrack -> if (ref.isUnsequenced) {
                val phaseDelay = DocumentTimelineMutationSupport.phaseDelayForBuild(doc, ref)
                val buildDelay = DocumentTimelineMutationSupport.findBuildDelayMs(doc, ref)
                c.setBeatNodeManualDelay(
                    slideId = ref.slideId,
                    beatIndex = ref.beatIndex,
                    nodeId = ref.nodeId,
                    delayMs = (clamped - phaseDelay - buildDelay).coerceAtLeast(0),
                    useActiveGesture = useActiveGesture,
                )
            } else {
                val depEndMs = DocumentTimelineMutationSupport.dependencyEndMsForSequencedBuild(c.snapshot, ref)
                c.updateBuildTiming(
                    slideId = ref.slideId,
                    nodeId = ref.nodeId,
                    buildIndex = ref.buildIndex,
                    delayMs = (clamped - depEndMs).coerceAtLeast(0),
                    useActiveGesture = useActiveGesture,
                )
            }
            is DocumentTimelineTrackRef.TransitionTrackBar -> if (ref.trackPlan.kind == TrackKind.TransitionSlide) {
                c.applyDocumentMutation("Move transition delay", useActiveGesture) { d ->
                    DocumentTimelineMutationSupport.updateSlideTransition(d, c.activeThemeName, ref.fromSlideId) {
                        it.copy(delayMs = clamped)
                    }
                }
            } else {
                val baseDelay = DocumentTimelineMutationSupport.transitionTrackBaseDelay(doc, c.activeThemeName, ref)
                c.setTransitionNodeManualDelay(
                    fromSlideId = ref.fromSlideId,
                    nodeId = ref.nodeId,
                    delayMs = (clamped - baseDelay).coerceAtLeast(0),
                    useActiveGesture = useActiveGesture,
                )
            }
            is DocumentTimelineTrackRef.SpecimenTrackBar -> c.updateSpecimenTrack(
                slideId = ref.slideId,
                nodeId = ref.nodeId,
                trackName = ref.trackName,
                delayMs = clamped,
                useActiveGesture = useActiveGesture,
            )
            is DocumentTimelineTrackRef.LoopTrackBar -> false
        }
    }

    /** Resizes a track bar's right edge to [newDurationMs]. */
    fun resizeTrackBarDuration(
        c: DocumentTimelineController,
        ref: DocumentTimelineTrackRef,
        newDurationMs: Int,
        useActiveGesture: Boolean,
    ): Boolean {
        val clamped = newDurationMs.coerceAtLeast(0)
        val doc = c.store.document
        return when (ref) {
            is DocumentTimelineTrackRef.SlideBuildTrack -> {
                val buildDurationMs = DocumentTimelineMutationSupport.buildDurationForTotalSpan(
                    doc = doc,
                    snapshot = c.snapshot,
                    ref = ref,
                    requestedSpanMs = clamped,
                )
                c.updateBuildTiming(
                    slideId = ref.slideId,
                    nodeId = ref.nodeId,
                    buildIndex = ref.buildIndex,
                    durationMs = buildDurationMs,
                    useActiveGesture = useActiveGesture,
                )
            }
            is DocumentTimelineTrackRef.SpecimenTrackBar -> c.updateSpecimenTrack(
                slideId = ref.slideId,
                nodeId = ref.nodeId,
                trackName = ref.trackName,
                durationMs = clamped,
                useActiveGesture = useActiveGesture,
            )
            is DocumentTimelineTrackRef.TransitionTrackBar -> {
                val phase = DocumentTimelineMutationSupport.phaseForTransitionKind(ref.trackPlan.kind)
                if (phase != null) {
                    val phaseDurationMs = DocumentTimelineMutationSupport.transitionPhaseDurationForTotalSpan(
                        doc = doc,
                        themeName = c.activeThemeName,
                        snapshot = c.snapshot,
                        ref = ref,
                        requestedSpanMs = clamped,
                    )
                    c.updateTransitionPhaseTiming(
                        fromSlideId = ref.fromSlideId,
                        phase = phase,
                        durationMs = phaseDurationMs,
                        useActiveGesture = useActiveGesture,
                    )
                } else {
                    c.applyDocumentMutation("Resize transition duration", useActiveGesture) { d ->
                        DocumentTimelineMutationSupport.updateSlideTransition(d, c.activeThemeName, ref.fromSlideId) {
                            it.copy(durationMs = clamped)
                        }
                    }
                }
            }
            is DocumentTimelineTrackRef.LoopTrackBar -> c.setLoopPeriodMs(
                loopId = ref.loopId,
                periodMs = clamped.toLong().coerceAtLeast(100L),
                useActiveGesture = useActiveGesture,
            )
        }
    }

    fun updateTrackCurve(
        c: DocumentTimelineController,
        ref: DocumentTimelineTrackRef,
        curve: Curve,
        useActiveGesture: Boolean,
    ): Boolean = when (ref) {
        is DocumentTimelineTrackRef.SlideBuildTrack -> c.updateBuildTiming(
            slideId = ref.slideId,
            nodeId = ref.nodeId,
            buildIndex = ref.buildIndex,
            curve = curve,
            useActiveGesture = useActiveGesture,
        )
        is DocumentTimelineTrackRef.SpecimenTrackBar -> c.updateSpecimenTrack(
            slideId = ref.slideId,
            nodeId = ref.nodeId,
            trackName = ref.trackName,
            curve = curve,
            useActiveGesture = useActiveGesture,
        )
        is DocumentTimelineTrackRef.TransitionTrackBar -> {
            val phase = DocumentTimelineMutationSupport.phaseForTransitionKind(ref.trackPlan.kind)
            if (phase != null) {
                c.updateTransitionPhaseTiming(
                    fromSlideId = ref.fromSlideId,
                    phase = phase,
                    curve = curve,
                    useActiveGesture = useActiveGesture,
                )
            } else {
                c.applyDocumentMutation("Update transition curve", useActiveGesture) { d ->
                    DocumentTimelineMutationSupport.updateSlideTransition(d, c.activeThemeName, ref.fromSlideId) {
                        it.copy(curve = curve)
                    }
                }
            }
        }
        is DocumentTimelineTrackRef.LoopTrackBar -> false
    }
}
