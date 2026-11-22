package dev.sebastiano.ciacola

import androidx.compose.runtime.Immutable
import dev.sebastiano.ciacola.document.DeckSpecimenRegistry

/**
 * Catalog entry pairing a `<deckId>.deck.json` document with its static [DeckSpecimenRegistry].
 */
@Immutable
data class DeckEntry(
    val id: String,
    val title: String,
    val resourcePath: String = "decks/$id.deck.json",
    val specimens: DeckSpecimenRegistry = DeckSpecimenRegistry.Empty,
    val externalFilePath: String? = null,
) {
    /** Classpath directory holding image/SVG assets referenced by this deck. */
    val assetsResourceDir: String
        get() = "decks/$id.assets"

    val isExternalFile: Boolean
        get() = !externalFilePath.isNullOrBlank()

    companion object
}

internal expect val platformDeckEntries: List<DeckEntry>

internal expect fun platformDiscoverDeckEntries(decksDirPath: String?, forceRefresh: Boolean = false): List<DeckEntry>

/**
 * Dynamic catalog of Ciacola document decks available to Studio, Show Mode, and `⌘⌥1..9`.
 */
object DeckCatalog {
    const val FOUNDATION_ID: String = "foundation"
    const val SHADERS_QUICKIE_ID: String = "shaders-quickie-2025"
    const val JEWEL_TALK_ID: String = "jewel-talk-2024"
    const val NOT_JUST_PLAIN_TEXT_ID: String = "not-just-plain-text-2024"

    val entries: List<DeckEntry>
        get() = platformDeckEntries

    fun discoverEntries(decksDirPath: String? = null, forceRefresh: Boolean = false): List<DeckEntry> =
        platformDiscoverDeckEntries(decksDirPath, forceRefresh = forceRefresh)

    val foundation: DeckEntry
        get() = byId(FOUNDATION_ID)

    val shadersQuickie2025: DeckEntry
        get() = byId(SHADERS_QUICKIE_ID)

    val jewelTalk2024: DeckEntry
        get() = byId(JEWEL_TALK_ID)

    val notJustPlainText2024: DeckEntry
        get() = byId(NOT_JUST_PLAIN_TEXT_ID)

    val defaultEntry: DeckEntry
        get() = entries.first()

    fun byId(id: String?, catalog: List<DeckEntry> = entries): DeckEntry =
        byIdOrNull(id, catalog) ?: catalog.firstOrNull() ?: DeckEntry(
            id = FOUNDATION_ID,
            title = "Ciacola Foundation",
        )

    fun byIdOrNull(id: String?, catalog: List<DeckEntry> = entries): DeckEntry? {
        if (id == null) return null
        return catalog.firstOrNull { it.id == id }
            ?: catalog.firstOrNull { matchesFileStem(it, id) }
    }

    private fun matchesFileStem(entry: DeckEntry, query: String): Boolean {
        val extPath = entry.externalFilePath?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        val fileName = extPath.substringAfterLast('/').substringAfterLast('\\')
        val stem = if (fileName.endsWith(".deck.json")) {
            fileName.removeSuffix(".deck.json")
        } else {
            fileName.substringBeforeLast('.', fileName)
        }
        return stem == query
    }
}
