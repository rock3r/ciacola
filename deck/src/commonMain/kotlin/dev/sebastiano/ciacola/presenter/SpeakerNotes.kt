package dev.sebastiano.ciacola.presenter

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import dev.bnorm.storyboard.Storyboard
import dev.sebastiano.ciacola.timeline.VirtualizedFrame

/**
 * Speaker notes attached to a single Storyboard scene, supporting both a slide-level summary
 * and optional beat-level notes keyed by base beat index.
 */
@Immutable
data class SceneSpeakerNotes(
    val sceneIndex: Int,
    val title: String,
    val slideNotes: String = "",
    val beatNotes: Map<Int, String> = emptyMap(),
) {
    val hasAnyNotes: Boolean
        get() = slideNotes.isNotBlank() || beatNotes.values.any { it.isNotBlank() }
}

/**
 * Resolved speaker notes for a specific `(sceneIndex, frameIndex)` position in a [Storyboard].
 */
@Immutable
data class ResolvedSpeakerNotes(
    val sceneIndex: Int,
    val sceneTitle: String,
    val beatIndex: Int,
    val baseBeatIndex: Int,
    val isVirtualHoldBeat: Boolean,
    val slideNotes: String,
    val beatNote: String?,
) {
    val hasNotes: Boolean
        get() = !beatNote.isNullOrBlank() || slideNotes.isNotBlank()
}

/**
 * Immutable collection of [SceneSpeakerNotes] indexed by `sceneIndex`.
 */
@Immutable
data class DeckSpeakerNotes(
    val scenes: Map<Int, SceneSpeakerNotes> = emptyMap(),
) {
    fun sceneTitle(sceneIndex: Int): String =
        scenes[sceneIndex]?.title?.takeIf { it.isNotBlank() } ?: "Slide ${sceneIndex + 1}"

    fun resolve(storyboard: Storyboard, index: Storyboard.Index): ResolvedSpeakerNotes {
        val scene = storyboard.scenes.getOrNull(index.sceneIndex)
        val rawFrame = scene?.frames?.getOrNull(index.frameIndex)
        val virtualized = rawFrame as? VirtualizedFrame<*>
        val baseBeatIndex = virtualized?.baseBeatIndex ?: index.frameIndex
        val isVirtualHold = virtualized?.isInjected == true

        val sceneNotes = scenes[index.sceneIndex]
        val title = sceneNotes?.title?.takeIf { it.isNotBlank() } ?: "Slide ${index.sceneIndex + 1}"
        val beatNote = sceneNotes?.beatNotes?.get(baseBeatIndex)?.takeIf { it.isNotBlank() }
        val slideNotes = sceneNotes?.slideNotes?.trim().orEmpty()

        return ResolvedSpeakerNotes(
            sceneIndex = index.sceneIndex,
            sceneTitle = title,
            beatIndex = index.frameIndex,
            baseBeatIndex = baseBeatIndex,
            isVirtualHoldBeat = isVirtualHold,
            slideNotes = slideNotes,
            beatNote = beatNote,
        )
    }

    companion object {
        val Empty = DeckSpeakerNotes()
    }
}

/**
 * Builder DSL for per-scene and per-beat speaker notes.
 */
class SceneSpeakerNotesBuilder(
    private val sceneIndex: Int,
    private val title: String,
) {
    var slideNotes: String = ""
    private val beatNotes = linkedMapOf<Int, String>()

    fun overview(text: String) {
        slideNotes = text.trimIndent().trim()
    }

    fun beat(beatIndex: Int, note: String) {
        require(beatIndex >= 0) { "beatIndex must be non-negative (was $beatIndex)" }
        beatNotes[beatIndex] = note.trimIndent().trim()
    }

    fun build(): SceneSpeakerNotes = SceneSpeakerNotes(
        sceneIndex = sceneIndex,
        title = title,
        slideNotes = slideNotes.trim(),
        beatNotes = beatNotes.toMap(),
    )
}

/**
 * Builder DSL for assembling [DeckSpeakerNotes] across all scenes in a deck.
 */
class DeckSpeakerNotesBuilder {
    private val scenes = linkedMapOf<Int, SceneSpeakerNotes>()

    fun scene(
        sceneIndex: Int,
        title: String,
        block: SceneSpeakerNotesBuilder.() -> Unit,
    ) {
        val builder = SceneSpeakerNotesBuilder(sceneIndex, title)
        builder.block()
        scenes[sceneIndex] = builder.build()
    }

    fun record(notes: SceneSpeakerNotes) {
        scenes[notes.sceneIndex] = notes
    }

    fun build(): DeckSpeakerNotes = DeckSpeakerNotes(scenes.toMap())
}

fun buildDeckSpeakerNotes(block: DeckSpeakerNotesBuilder.() -> Unit): DeckSpeakerNotes =
    DeckSpeakerNotesBuilder().apply(block).build()

/**
 * Composition local exposing the active [DeckSpeakerNotes] inside a decorated storyboard.
 */
val LocalDeckSpeakerNotes = staticCompositionLocalOf { DeckSpeakerNotes.Empty }

/**
 * Associates [DeckSpeakerNotes] with built [Storyboard] instances without altering Storyboard's
 * binary public API.
 */
expect object StoryboardSpeakerNotesRegistry {
    fun register(storyboard: Storyboard, notes: DeckSpeakerNotes)
    fun forStoryboard(storyboard: Storyboard?): DeckSpeakerNotes?
}
