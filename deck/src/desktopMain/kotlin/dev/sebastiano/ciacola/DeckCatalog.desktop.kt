package dev.sebastiano.ciacola

import dev.sebastiano.ciacola.decks.AllDocumentDecks
import dev.sebastiano.ciacola.decks.resolveDefaultDecksDirectory
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

private val DiscoveryJson = Json { ignoreUnknownKeys = true }
private val SafeDeckIdRegex = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")

private data class CachedDeckMeta(
    val lastModified: FileTime,
    val size: Long,
    val id: String?,
    val title: String?,
)

private data class CachedDirEntries(
    val dirModified: FileTime,
    val cachedAtNanos: Long,
    val entries: List<DeckEntry>,
)

private const val DIR_CATALOG_CACHE_TTL_NANOS = 2_000_000_000L

private val deckMetaCache = ConcurrentHashMap<Path, CachedDeckMeta>()
private val dirCatalogCache = ConcurrentHashMap<Path, CachedDirEntries>()

internal fun isValidDeckId(id: String): Boolean =
    SafeDeckIdRegex.matches(id) && ".." !in id

internal fun sanitizeDeckId(raw: String, fallback: String = "external-deck"): String {
    val trimmed = raw.trim()
    if (isValidDeckId(trimmed)) return trimmed
    val cleaned = trimmed
        .replace(Regex("[^A-Za-z0-9._-]+"), "-")
        .replace(Regex("\\.{2,}"), ".")
        .trimStart('.', '-', '_')
        .trimEnd('-', '_')
        .take(128)
    return if (isValidDeckId(cleaned)) cleaned else fallback
}

internal actual val platformDeckEntries: List<DeckEntry>
    get() = discoverDocumentDeckEntries(resolveDefaultDecksDirectory(), forceRefresh = false)

internal actual fun platformDiscoverDeckEntries(decksDirPath: String?, forceRefresh: Boolean): List<DeckEntry> {
    val dir = decksDirPath?.trim()?.takeIf { it.isNotEmpty() }?.let(Path::of)
        ?: resolveDefaultDecksDirectory()
    return discoverDocumentDeckEntries(dir, forceRefresh = forceRefresh)
}

/**
 * Discovers all `*.deck.json` files inside [decksDir] and merges them with the built-in
 * registrations in [AllDocumentDecks], preserving curated titles and specimen registries for
 * built-in decks while exposing newly dropped or custom decks automatically.
 */
fun DeckCatalog.discoverEntries(decksDir: Path?, forceRefresh: Boolean = false): List<DeckEntry> =
    discoverDocumentDeckEntries(decksDir, forceRefresh = forceRefresh)

/**
 * Constructs a [DeckEntry] backed by an arbitrary external `.deck.json` file outside `decks/`.
 */
fun DeckEntry.Companion.forExternalFile(path: Path): DeckEntry {
    val abs = path.toAbsolutePath().normalize()
    val fileName = abs.fileName?.toString().orEmpty()
    val stem = sanitizeDeckId(deckStemFromFileName(fileName), fallback = "external-deck")
    val (parsedId, parsedTitle) = readDeckIdAndTitle(abs)
    val id = parsedId ?: stem
    val title = parsedTitle ?: humanizeDeckTitle(stem)
    return DeckEntry(
        id = id,
        title = title,
        resourcePath = "decks/$id.deck.json",
        specimens = AllDocumentDecks.resolveSpecimensForDeck(id),
        externalFilePath = abs.toString(),
    )
}

internal fun discoverDocumentDeckEntries(
    decksDir: Path?,
    forceRefresh: Boolean = false,
): List<DeckEntry> {
    val builtInEntries = AllDocumentDecks.entries.map { reg ->
        DeckEntry(
            id = reg.id,
            title = reg.title,
            resourcePath = reg.resourcePath,
            specimens = reg.specimens,
        )
    }
    if (decksDir == null || !Files.isDirectory(decksDir)) {
        return builtInEntries
    }

    val absDir = decksDir.toAbsolutePath().normalize()
    val dirMtime = runCatching { Files.getLastModifiedTime(absDir) }.getOrNull()
    val nowNanos = System.nanoTime()
    if (!forceRefresh && dirMtime != null) {
        dirCatalogCache[absDir]?.let { cached ->
            if (cached.dirModified == dirMtime &&
                (nowNanos - cached.cachedAtNanos) < DIR_CATALOG_CACHE_TTL_NANOS
            ) {
                return cached.entries
            }
        }
    }

    val seenIds = builtInEntries.mapTo(LinkedHashSet()) { it.id }
    val discovered = mutableListOf<DeckEntry>()
    val files = runCatching {
        Files.list(absDir).use { stream ->
            stream.filter { p ->
                p.isRegularFile() &&
                    !p.name.startsWith(".") &&
                    p.name.endsWith(".deck.json")
            }.sorted(Comparator.comparing { it.name }).toList()
        }
    }.getOrDefault(emptyList())

    for (file in files) {
        val rawStem = deckStemFromFileName(file.name)
        if (rawStem.isBlank() || AllDocumentDecks.byId(rawStem) != null) continue
        val stem = sanitizeDeckId(rawStem, fallback = "deck")
        if (AllDocumentDecks.byId(stem) != null) continue
        val (parsedId, parsedTitle) = readDeckIdAndTitle(file)
        val entryId = parsedId ?: stem
        if (!seenIds.add(entryId)) {
            System.err.println(
                "Warning: skipping duplicate deck id '$entryId' at ${file.toAbsolutePath().normalize()}",
            )
            continue
        }
        val entryTitle = parsedTitle ?: humanizeDeckTitle(stem)
        discovered += DeckEntry(
            id = entryId,
            title = entryTitle,
            resourcePath = "decks/${file.name}",
            specimens = AllDocumentDecks.resolveSpecimensForDeck(entryId),
            externalFilePath = file.toAbsolutePath().normalize().toString(),
        )
    }

    val result = builtInEntries + discovered
    if (dirMtime != null) {
        dirCatalogCache[absDir] = CachedDirEntries(dirMtime, System.nanoTime(), result)
    }
    return result
}

private fun deckStemFromFileName(fileName: String): String =
    if (fileName.endsWith(".deck.json")) {
        fileName.removeSuffix(".deck.json")
    } else {
        fileName.substringBeforeLast('.', fileName)
    }

private fun readDeckIdAndTitle(file: Path): Pair<String?, String?> {
    val abs = file.toAbsolutePath().normalize()
    val modified = runCatching { Files.getLastModifiedTime(abs) }.getOrNull()
    val size = runCatching { Files.size(abs) }.getOrNull() ?: -1L
    val previous = deckMetaCache[abs]
    if (modified != null && size >= 0L && previous != null &&
        previous.lastModified == modified && previous.size == size
    ) {
        return previous.id to previous.title
    }
    val raw = runCatching { Files.readString(abs, StandardCharsets.UTF_8) }.getOrNull()
        ?: return if (previous != null) previous.id to previous.title else null to null
    val obj = runCatching { DiscoveryJson.parseToJsonElement(raw) as? JsonObject }.getOrNull()
        ?: return if (previous != null) previous.id to previous.title else null to null
    val rawId = runCatching {
        (obj["id"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
    }.getOrNull()
    val id = rawId?.takeIf(::isValidDeckId)
    val title = runCatching {
        (obj["title"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
    }.getOrNull()
    if (modified != null && size >= 0L) {
        deckMetaCache[abs] = CachedDeckMeta(modified, size, id, title)
    }
    return id to title
}

internal fun humanizeDeckTitle(stem: String): String =
    stem.replace('-', ' ')
        .replace('_', ' ')
        .trim()
        .split(Regex("""\s+"""))
        .filter { it.isNotEmpty() }
        .joinToString(" ") { word ->
            word.replaceFirstChar { ch -> ch.uppercaseChar() }
        }
        .ifEmpty { "Untitled Deck" }
