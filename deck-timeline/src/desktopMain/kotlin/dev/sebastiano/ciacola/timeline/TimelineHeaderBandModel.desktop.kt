package dev.sebastiano.ciacola.timeline

import androidx.compose.runtime.Immutable

/**
 * One entry of the time-aligned header band: a slide segment, a beat tick inside it, or the
 * transition join leading to the next slide. All entries share the track time coordinates.
 */
@Immutable
sealed interface HeaderBandEntry {
    val sceneIndex: Int

    /** Tie-breaker inside one slide: segment (0) < beats (1) < outgoing transition (2). */
    val kindOrder: Int
    val startMs: Int
    val endMs: Int
    val key: String

    @Immutable
    data class Slide(
        override val sceneIndex: Int,
        val slideId: String,
        val label: String,
        val title: String,
        override val startMs: Int,
        override val endMs: Int,
        val beatCount: Int,
    ) : HeaderBandEntry {
        override val kindOrder: Int get() = 0
        override val key: String get() = "slide:$slideId"
    }

    @Immutable
    data class Beat(
        val info: DocumentBeatHeaderInfo,
        val label: String,
    ) : HeaderBandEntry {
        override val sceneIndex: Int get() = info.sceneIndex
        override val kindOrder: Int get() = 1
        override val startMs: Int get() = info.startMs
        override val endMs: Int get() = info.startMs + info.segmentMs
        override val key: String get() = "beat:${info.slideId}:${info.beatIndex}"
    }

    @Immutable
    data class Transition(
        val info: DocumentTransitionHeaderInfo,
        val label: String,
    ) : HeaderBandEntry {
        override val sceneIndex: Int get() = info.fromSceneIndex
        override val kindOrder: Int get() = 2
        override val startMs: Int get() = info.startMs
        override val endMs: Int get() = info.startMs + info.segmentMs
        override val key: String get() = "trans:${info.fromSlideId}:${info.toSlideId}"
    }
}

/** Ordering used everywhere headers are listed: `(sceneIndex, kindOrder, startMs)`. */
internal val HEADER_BAND_ORDER: Comparator<HeaderBandEntry> =
    compareBy<HeaderBandEntry>({ it.sceneIndex }, { it.kindOrder }, { it.startMs })

/**
 * Builds the header band entries for [docSnapshot], ordered by construction so a zero-length
 * transition `s1 → s2` always sorts after every beat of `s1` and before `s2`'s first beat,
 * even though it shares a timestamp with `s2`'s base beat.
 */
internal fun buildHeaderBandEntries(
    docSnapshot: DocumentTimelineSnapshot,
    slideTitleProvider: (slideId: String, sceneIndex: Int) -> String? = { _, _ -> null },
): List<HeaderBandEntry> {
    val slides = docSnapshot.snapshot.slides
    val beatsByScene = docSnapshot.beatHeaders.groupBy { it.sceneIndex }
    val transByScene = docSnapshot.transitionHeaders.associateBy { it.fromSceneIndex }
    val out = ArrayList<HeaderBandEntry>(slides.size + docSnapshot.beatHeaders.size + docSnapshot.transitionHeaders.size)
    for (slide in slides) {
        val beats = beatsByScene[slide.sceneIndex].orEmpty()
        val slideId = beats.firstOrNull()?.slideId
            ?: transByScene[slide.sceneIndex]?.fromSlideId
            ?: "scene-${slide.sceneIndex}"
        val segStart = beats.minOfOrNull { it.startMs } ?: slide.startMs
        val segEnd = transByScene[slide.sceneIndex]?.startMs ?: slide.endMs
        val title = slideTitleProvider(slideId, slide.sceneIndex)?.trim()?.takeIf { it.isNotEmpty() }
        out += HeaderBandEntry.Slide(
            sceneIndex = slide.sceneIndex,
            slideId = slideId,
            label = if (title != null) "${slide.sceneIndex + 1} · $title" else "Slide ${slide.sceneIndex + 1}",
            title = title ?: "Slide ${slide.sceneIndex + 1}",
            startMs = segStart,
            endMs = segEnd.coerceAtLeast(segStart),
            beatCount = slide.beatCount,
        )
    }
    for (b in docSnapshot.beatHeaders) {
        out += HeaderBandEntry.Beat(b, TimelineDisplayNames.beatLabel(b.beatIndex))
    }
    for (t in docSnapshot.transitionHeaders) {
        val toTitle = slideTitleProvider(t.toSlideId, t.toSceneIndex)?.takeIf { it.isNotBlank() }
            ?: "Slide ${t.toSceneIndex + 1}"
        out += HeaderBandEntry.Transition(t, "→ $toTitle · ${TimelineDisplayNames.transitionTypeName(t.transitionSpec.type)}")
    }
    out.sortWith(HEADER_BAND_ORDER)
    return out
}

/**
 * Track rows visible for [mode]: `CurrentSlide` keeps rows of [activeSceneIndex] plus ambient
 * loops whose clock key is declared or referenced by that slide (never loops of other slides).
 * [query] matches plain labels and raw track ids.
 */
internal fun filterTracksForScope(
    docSnapshot: DocumentTimelineSnapshot,
    mode: TimelineTrackFilterMode,
    activeSceneIndex: Int,
    query: String,
): List<TimelineTrackRow> {
    val tracks = docSnapshot.snapshot.tracks
    val scoped = when (mode) {
        TimelineTrackFilterMode.CurrentSlide -> tracks.filter { row ->
            when {
                row.sceneIndex != null -> row.sceneIndex == activeSceneIndex
                else -> trackBelongsToScene(docSnapshot.trackRefsByTrackId[row.trackId], activeSceneIndex)
            }
        }
        TimelineTrackFilterMode.AllSlides -> tracks
    }
    val q = query.trim().lowercase()
    if (q.isEmpty()) return scoped
    return scoped.filter { row -> row.label.lowercase().contains(q) || row.trackId.lowercase().contains(q) }
}

private fun trackBelongsToScene(ref: DocumentTimelineTrackRef?, sceneIndex: Int): Boolean = when (ref) {
    is DocumentTimelineTrackRef.LoopTrackBar -> sceneIndex in ref.sceneIndices
    is DocumentTimelineTrackRef.SlideBuildTrack -> ref.sceneIndex == sceneIndex
    is DocumentTimelineTrackRef.SpecimenTrackBar -> ref.sceneIndex == sceneIndex
    is DocumentTimelineTrackRef.TransitionTrackBar -> ref.fromSceneIndex == sceneIndex
    null -> false
}

/** Node id referenced by a timeline track (or `null` for synthetic whole-slide tracks). */
internal fun DocumentTimelineTrackRef.referencedNodeId(): String? = when (this) {
    is DocumentTimelineTrackRef.SlideBuildTrack -> nodeId
    is DocumentTimelineTrackRef.SpecimenTrackBar -> nodeId
    is DocumentTimelineTrackRef.TransitionTrackBar -> nodeId.takeUnless { it.startsWith("@transition") }
    is DocumentTimelineTrackRef.LoopTrackBar -> nodeId
}

/** Whether this track belongs to [slideId] (transitions belong to both of their slides). */
internal fun DocumentTimelineTrackRef.belongsToSlide(slideId: String): Boolean = when (this) {
    is DocumentTimelineTrackRef.SlideBuildTrack -> this.slideId == slideId
    is DocumentTimelineTrackRef.SpecimenTrackBar -> this.slideId == slideId
    is DocumentTimelineTrackRef.TransitionTrackBar -> fromSlideId == slideId || toSlideId == slideId
    is DocumentTimelineTrackRef.LoopTrackBar -> this.slideId == slideId
}

/** Absolute `[startMs, endMs]` covered by [row]'s first block, if any. */
internal fun TimelineTrackRow.timeRange(): Pair<Int, Int>? {
    val b = blocks.firstOrNull() ?: return null
    return b.startMs to (b.startMs + b.durationMs)
}
