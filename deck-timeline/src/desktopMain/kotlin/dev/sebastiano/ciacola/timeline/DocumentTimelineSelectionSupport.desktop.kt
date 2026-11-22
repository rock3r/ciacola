package dev.sebastiano.ciacola.timeline

import dev.sebastiano.ciacola.document.DeckDocument

/**
 * Pure selection helpers backing [DocumentTimelineController] (kept out of the controller so it
 * stays well under the module's file-size cap).
 */
internal object DocumentTimelineSelectionSupport {

    /** Whether [selection] still refers to something on [slideId] (scene [sceneIndex]). */
    fun selectionMatchesSlide(
        selection: DocumentTimelineSelection,
        snapshot: DocumentTimelineSnapshot,
        slideId: String,
        sceneIndex: Int,
    ): Boolean = when (selection) {
        DocumentTimelineSelection.None -> true
        is DocumentTimelineSelection.BeatHeader -> selection.slideId == slideId
        is DocumentTimelineSelection.TransitionHeader -> selection.fromSlideId == slideId || selection.toSlideId == slideId
        is DocumentTimelineSelection.Track -> when (val ref = snapshot.trackRefsByTrackId[selection.trackId]) {
            is DocumentTimelineTrackRef.LoopTrackBar -> ref.slideId == slideId || sceneIndex in ref.sceneIndices
            null -> false
            else -> ref.belongsToSlide(slideId)
        }
        is DocumentTimelineSelection.Loop -> {
            val ref = snapshot.trackRefsByTrackId["doc:loop:${selection.loopId}"] as? DocumentTimelineTrackRef.LoopTrackBar
            ref == null || ref.slideId == slideId || sceneIndex in ref.sceneIndices
        }
    }

    /**
     * Resolves the `(slideId, beatIndex)` targeted by beat insert/delete actions: honors an explicit
     * beat/track selection first, otherwise the presented slide & beat.
     */
    fun activeBeatTarget(
        doc: DeckDocument,
        snapshot: DocumentTimelineSnapshot,
        selection: DocumentTimelineSelection,
        presentedSlideId: String?,
        presentedBeatIndex: Int,
        presentedTarget: () -> Pair<String, Int>?,
    ): Pair<String, Int>? {
        fun clampBeat(slideId: String, preferred: Int): Pair<String, Int>? {
            val slide = doc.slides.firstOrNull { it.id == slideId } ?: return null
            return slideId to preferred.coerceIn(0, slide.beats.lastIndex.coerceAtLeast(0))
        }
        fun lastBeatOf(slideId: String): Int = doc.slides.firstOrNull { it.id == slideId }?.beats?.lastIndex ?: 0
        fun beatOn(slideId: String, otherwise: Int): Int = if (slideId == presentedSlideId) presentedBeatIndex else otherwise

        return when (selection) {
            is DocumentTimelineSelection.BeatHeader -> clampBeat(selection.slideId, selection.beatIndex)
            is DocumentTimelineSelection.TransitionHeader ->
                clampBeat(selection.fromSlideId, beatOn(selection.fromSlideId, lastBeatOf(selection.fromSlideId)))
            is DocumentTimelineSelection.Track -> when (val ref = snapshot.trackRefsByTrackId[selection.trackId]) {
                is DocumentTimelineTrackRef.SlideBuildTrack -> clampBeat(ref.slideId, ref.beatIndex)
                is DocumentTimelineTrackRef.SpecimenTrackBar -> clampBeat(ref.slideId, ref.beatIndex)
                is DocumentTimelineTrackRef.TransitionTrackBar ->
                    clampBeat(ref.fromSlideId, beatOn(ref.fromSlideId, lastBeatOf(ref.fromSlideId)))
                is DocumentTimelineTrackRef.LoopTrackBar -> clampBeat(ref.slideId, beatOn(ref.slideId, 0))
                null -> presentedTarget()
            }
            is DocumentTimelineSelection.Loop -> {
                val ref = snapshot.trackRefsByTrackId["doc:loop:${selection.loopId}"] as? DocumentTimelineTrackRef.LoopTrackBar
                if (ref != null) clampBeat(ref.slideId, beatOn(ref.slideId, 0)) else presentedTarget()
            }
            DocumentTimelineSelection.None -> presentedTarget()
        }
    }

    /**
     * Track ids belonging to [nodeId] on [slideId], with the track on [preferredBeat] (or the
     * earliest one) first. Loop tracks are included so specimen nodes reveal their ambient loop.
     */
    fun trackIdsForNode(
        snapshot: DocumentTimelineSnapshot,
        slideId: String,
        nodeId: String,
        preferredBeat: Int,
    ): List<String> {
        val matches = snapshot.snapshot.tracks.mapNotNull { row ->
            val ref = snapshot.trackRefsByTrackId[row.trackId] ?: return@mapNotNull null
            if (ref.referencedNodeId() == nodeId && ref.belongsToSlide(slideId)) row.trackId to ref else null
        }
        if (matches.isEmpty()) return emptyList()
        val preferred = matches.firstOrNull { (_, ref) ->
            (ref as? DocumentTimelineTrackRef.SlideBuildTrack)?.beatIndex == preferredBeat ||
                (ref as? DocumentTimelineTrackRef.SpecimenTrackBar)?.beatIndex == preferredBeat
        }
        return buildList {
            if (preferred != null) add(preferred.first)
            for ((id, _) in matches) if (id != preferred?.first) add(id)
        }
    }
}
