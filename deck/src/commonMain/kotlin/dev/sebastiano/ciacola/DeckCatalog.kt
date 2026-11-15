package dev.sebastiano.ciacola

import dev.bnorm.storyboard.Storyboard
import dev.sebastiano.ciacola.authoring.DeckHostParams

/**
 * Catalog entry describing a selectable deck in Studio and Show Mode.
 */
data class DeckEntry(
    val id: String,
    val title: String,
    val create: (DeckHostParams) -> Storyboard,
)

/**
 * Built-in catalog of Ciacola decks available to the Studio toolbar and `Deck` menu switcher.
 */
object DeckCatalog {
    const val FOUNDATION_ID: String = "foundation"

    val foundation: DeckEntry = DeckEntry(
        id = FOUNDATION_ID,
        title = "Foundation Deck",
        create = { host -> createFoundationDeck(host = host, includeKeynoteEffectsLab = true) },
    )

    val entries: List<DeckEntry> = listOf(
        foundation,
    )

    val defaultEntry: DeckEntry
        get() = foundation

    fun byId(id: String?, catalog: List<DeckEntry> = entries): DeckEntry =
        catalog.firstOrNull { it.id == id } ?: catalog.firstOrNull() ?: defaultEntry

    fun byIdOrNull(id: String?, catalog: List<DeckEntry> = entries): DeckEntry? =
        catalog.firstOrNull { it.id == id }
}
