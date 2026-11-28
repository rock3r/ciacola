package dev.sebastiano.ciacola.decks

import androidx.compose.runtime.Immutable
import dev.sebastiano.ciacola.document.DeckSpecimenRegistry

/**
 * Pairing of a tracked `<deckId>.deck.json` classpath resource with its static
 * [DeckSpecimenRegistry].
 */
@Immutable
data class DocumentDeckRegistration(
    val id: String,
    val title: String,
    val resourcePath: String,
    val specimens: DeckSpecimenRegistry,
) {
    /** Classpath directory holding image/SVG assets referenced by this deck. */
    val assetsResourceDir: String get() = "decks/$id.assets"
}

/**
 * Complete catalog of all four document-driven decks shipped in Ciacola, ready for `DeckCatalog`
 * and `Main.kt` integration.
 */
object AllDocumentDecks {
    val foundation: DocumentDeckRegistration = DocumentDeckRegistration(
        id = "foundation",
        title = "Ciacola Foundation",
        resourcePath = "decks/foundation.deck.json",
        specimens = foundationSpecimens,
    )

    val shadersQuickie2025: DocumentDeckRegistration = DocumentDeckRegistration(
        id = "shaders-quickie-2025",
        title = "Shaders Quickie 2025",
        resourcePath = "decks/shaders-quickie-2025.deck.json",
        specimens = shadersQuickieSpecimens,
    )

    val jewelTalk2024: DocumentDeckRegistration = DocumentDeckRegistration(
        id = "jewel-talk-2024",
        title = "Jewel Talk 2024",
        resourcePath = "decks/jewel-talk-2024.deck.json",
        specimens = jewelTalkSpecimens,
    )

    val notJustPlainText2024: DocumentDeckRegistration = DocumentDeckRegistration(
        id = "not-just-plain-text-2024",
        title = "Not Just Plain Text 2024",
        resourcePath = "decks/not-just-plain-text-2024.deck.json",
        specimens = notJustPlainTextSpecimens,
    )

    val entries: List<DocumentDeckRegistration> = listOf(
        foundation,
        shadersQuickie2025,
        jewelTalk2024,
        notJustPlainText2024,
    )

    val default: DocumentDeckRegistration get() = foundation

    fun byId(id: String): DocumentDeckRegistration? = entries.firstOrNull { it.id == id }

    /** Combined registry of every specimen across all four decks, plus the typography specimens. */
    val allSpecimens: DeckSpecimenRegistry =
        foundationSpecimens + shadersQuickieSpecimens + jewelTalkSpecimens + notJustPlainTextSpecimens + typographySpecimens

    /** Returns the curated specimen registry for a built-in deck, or [allSpecimens] for custom/external decks. */
    fun resolveSpecimensForDeck(deckId: String?): DeckSpecimenRegistry =
        deckId?.let(::byId)?.specimens ?: allSpecimens
}
