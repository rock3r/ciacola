package dev.sebastiano.ciacola.presenter

import dev.bnorm.storyboard.Storyboard
import java.util.Collections
import java.util.WeakHashMap

actual object StoryboardSpeakerNotesRegistry {
    private val byStoryboard = Collections.synchronizedMap(WeakHashMap<Storyboard, DeckSpeakerNotes>())

    actual fun register(storyboard: Storyboard, notes: DeckSpeakerNotes) {
        byStoryboard[storyboard] = notes
    }

    actual fun forStoryboard(storyboard: Storyboard?): DeckSpeakerNotes? =
        storyboard?.let { byStoryboard[it] }
}
