package dev.sebastiano.ciacola.decks

import dev.sebastiano.ciacola.DeckCatalog
import dev.sebastiano.ciacola.DeckEntry
import dev.sebastiano.ciacola.discoverEntries
import dev.sebastiano.ciacola.forExternalFile
import dev.sebastiano.ciacola.studio.externalDeckEntryFor
import dev.sebastiano.ciacola.studio.storyboardDirForDeck
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DeckCatalogDiscoveryTest {

    @Test
    fun `discoverEntries merges built-ins with newly dropped deck json files`() {
        val tmpDir = Files.createTempDirectory("ciacola-catalog-discovery-")
        try {
            Files.writeString(
                tmpDir.resolve("agent-custom-talk.deck.json"),
                """{"format":1,"id":"agent-custom-talk","title":"Agent Custom Talk","slides":[{"id":"s1"}]}""",
                StandardCharsets.UTF_8,
            )
            Files.writeString(
                tmpDir.resolve("untitled_notes-deck.deck.json"),
                """{"format":1,"id":"untitled_notes-deck","slides":[{"id":"s1"}]}""",
                StandardCharsets.UTF_8,
            )
            // Hidden sync/temp files must be ignored during discovery.
            Files.writeString(
                tmpDir.resolve(".hidden-draft.deck.json"),
                """{"format":1,"id":"hidden","slides":[{"id":"s1"}]}""",
                StandardCharsets.UTF_8,
            )

            val entries = DeckCatalog.discoverEntries(tmpDir)
            assertTrue(entries.size >= 6, "Expected 4 built-ins + 2 discovered entries, got ${entries.map { it.id }}")

            val foundation = DeckCatalog.byId(DeckCatalog.FOUNDATION_ID, entries)
            assertEquals("Ciacola Foundation", foundation.title)
            assertEquals(AllDocumentDecks.foundation.specimens.all.size, foundation.specimens.all.size)

            val custom = DeckCatalog.byIdOrNull("agent-custom-talk", entries)
            assertNotNull(custom)
            assertEquals("Agent Custom Talk", custom.title)
            assertEquals(AllDocumentDecks.allSpecimens.all.size, custom.specimens.all.size)
            assertTrue(custom.isExternalFile)

            val untitled = DeckCatalog.byIdOrNull("untitled_notes-deck", entries)
            assertNotNull(untitled)
            assertEquals("Untitled Notes Deck", untitled.title)

            assertEquals(null, DeckCatalog.byIdOrNull("hidden", entries))

            val source = resolveDeckSource(custom)
            assertIs<DeckSource.File>(source)
            assertEquals(tmpDir.resolve("agent-custom-talk.deck.json").toAbsolutePath().normalize(), source.file)
        } finally {
            tmpDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `forExternalFile resolves arbitrary deck json outside decks dir with sibling stem assets`() {
        val tmpDir = Files.createTempDirectory("ciacola-external-deck-")
        try {
            val deckFile = tmpDir.resolve("conference-keynote.deck.json")
            val assetsDir = Files.createDirectories(tmpDir.resolve("conference-keynote.assets"))
            Files.writeString(
                deckFile,
                """{"format":1,"id":"conf-2026","title":"Keynote 2026","slides":[{"id":"s1"}]}""",
                StandardCharsets.UTF_8,
            )

            val entry = DeckEntry.forExternalFile(deckFile)
            assertEquals("conf-2026", entry.id)
            assertEquals("Keynote 2026", entry.title)
            assertTrue(entry.isExternalFile)
            assertEquals(AllDocumentDecks.allSpecimens.all.size, entry.specimens.all.size)

            val source = resolveDeckSource(entry, decksDirProperty = null)
            assertIs<DeckSource.File>(source)
            assertEquals(deckFile.toAbsolutePath().normalize(), source.file)
            assertEquals(assetsDir.toAbsolutePath().normalize(), source.assetsDir)
        } finally {
            tmpDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `DeckCatalog entries dynamically observes ciacola decks dir system property`() {
        val tmpDir = Files.createTempDirectory("ciacola-sysprop-decks-")
        val previous = System.getProperty(DECKS_DIR_SYSTEM_PROPERTY)
        try {
            Files.writeString(
                tmpDir.resolve("live-dropped.deck.json"),
                """{"format":1,"id":"live-dropped","title":"Live Dropped Deck","slides":[{"id":"s1"}]}""",
                StandardCharsets.UTF_8,
            )
            System.setProperty(DECKS_DIR_SYSTEM_PROPERTY, tmpDir.toString())

            val resolved = DeckCatalog.byId("live-dropped")
            assertEquals("live-dropped", resolved.id)
            assertEquals("Live Dropped Deck", resolved.title)
        } finally {
            if (previous != null) {
                System.setProperty(DECKS_DIR_SYSTEM_PROPERTY, previous)
            } else {
                System.clearProperty(DECKS_DIR_SYSTEM_PROPERTY)
            }
            tmpDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `traversal deck ids are sanitized and storyboardDirForDeck never escapes decks root`() {
        val tmpDir = Files.createTempDirectory("ciacola-traversal-id-")
        try {
            val evilDeck = tmpDir.resolve("evil-talk.deck.json")
            Files.writeString(
                evilDeck,
                """{"format":1,"id":"../../../evil","title":"Traversal Attempt","slides":[{"id":"s1"}]}""",
                StandardCharsets.UTF_8,
            )

            val external = DeckEntry.forExternalFile(evilDeck)
            assertEquals("evil-talk", external.id)
            assertFalse("/" in external.id)
            assertFalse(".." in external.id)

            val discovered = DeckCatalog.discoverEntries(tmpDir)
            assertNotNull(DeckCatalog.byIdOrNull("evil-talk", discovered))
            assertEquals(null, DeckCatalog.byIdOrNull("../../../evil", discovered))

            val rootStoryboardDir = tmpDir.resolve(".storyboard").normalize()
            val decksRoot = rootStoryboardDir.resolve("decks").normalize()
            val resolvedDir = storyboardDirForDeck(rootStoryboardDir, "../../../evil")
            assertTrue(
                resolvedDir.normalize().startsWith(decksRoot),
                "Expected $resolvedDir to stay inside $decksRoot",
            )
            assertEquals(decksRoot.resolve("evil"), resolvedDir)
        } finally {
            tmpDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `byIdOrNull falls back to filename stem and discoverEntries warns on duplicate ids`() {
        val tmpDir = Files.createTempDirectory("ciacola-stem-and-dup-")
        val originalErr = System.err
        val errBuf = ByteArrayOutputStream()
        try {
            Files.writeString(
                tmpDir.resolve("my-file-name.deck.json"),
                """{"format":1,"id":"internal-json-id","title":"Stem Fallback","slides":[{"id":"s1"}]}""",
                StandardCharsets.UTF_8,
            )
            Files.writeString(
                tmpDir.resolve("duplicate-foundation.deck.json"),
                """{"format":1,"id":"foundation","title":"Duplicate Foundation","slides":[{"id":"s1"}]}""",
                StandardCharsets.UTF_8,
            )

            System.setErr(PrintStream(errBuf, true, StandardCharsets.UTF_8))
            val entries = DeckCatalog.discoverEntries(tmpDir)
            val stderr = errBuf.toString(StandardCharsets.UTF_8)

            val byId = DeckCatalog.byIdOrNull("internal-json-id", entries)
            val byStem = DeckCatalog.byIdOrNull("my-file-name", entries)
            assertNotNull(byId)
            assertEquals(byId, byStem, "Filename stem lookup must resolve the entry whose internal id differs")
            assertTrue(
                stderr.contains("Warning: skipping duplicate deck id 'foundation'"),
                "Expected duplicate id warning on stderr, got:\n$stderr",
            )
        } finally {
            System.setErr(originalErr)
            tmpDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `malformed non-primitive id or title fields never crash discovery and externalDeckEntryFor preserves built-in metadata`() {
        val tmpDir = Files.createTempDirectory("ciacola-malformed-meta-")
        try {
            Files.writeString(
                tmpDir.resolve("bad-meta.deck.json"),
                """{"format":1,"id":{"nested":1},"title":["arr"],"slides":[{"id":"s1"}]}""",
                StandardCharsets.UTF_8,
            )

            val entries = DeckCatalog.discoverEntries(tmpDir)
            val fallback = DeckCatalog.byIdOrNull("bad-meta", entries)
            assertNotNull(fallback)
            assertEquals("bad-meta", fallback.id)
            assertEquals("Bad Meta", fallback.title)

            val foundationOnDisk = tmpDir.resolve("foundation.deck.json")
            Files.writeString(
                foundationOnDisk,
                """{"format":1,"id":"foundation","slides":[{"id":"s1"}]}""",
                StandardCharsets.UTF_8,
            )
            val externalBuiltIn = externalDeckEntryFor(foundationOnDisk)
            assertNotNull(externalBuiltIn)
            assertEquals("Ciacola Foundation", externalBuiltIn.title)
            assertEquals(AllDocumentDecks.foundation.specimens.all.size, externalBuiltIn.specimens.all.size)
            assertEquals(foundationOnDisk.toString(), externalBuiltIn.externalFilePath)
        } finally {
            tmpDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `readDeckIdAndTitle keeps last known valid id and title when file temporarily becomes unparseable`() {
        val tmpDir = Files.createTempDirectory("ciacola-stale-cache-mid-edit-")
        try {
            val deckFile = tmpDir.resolve("filename-stem.deck.json")
            Files.writeString(
                deckFile,
                """{"format":1,"id":"authored-id","title":"Authored Title","slides":[{"id":"s1"}]}""",
                StandardCharsets.UTF_8,
            )

            val initial = DeckCatalog.discoverEntries(tmpDir, forceRefresh = true)
            val firstEntry = DeckCatalog.byIdOrNull("authored-id", initial)
            assertNotNull(firstEntry)
            assertEquals("Authored Title", firstEntry.title)

            // Simulate a mid-edit syntax error (truncated JSON) with changed mtime/size.
            Files.writeString(
                deckFile,
                """{"format":1,"id":"authored-id", broken_syntax""",
                StandardCharsets.UTF_8,
            )

            val duringBrokenEdit = DeckCatalog.discoverEntries(tmpDir, forceRefresh = true)
            val keptEntry = DeckCatalog.byIdOrNull("authored-id", duringBrokenEdit)
            assertNotNull(keptEntry, "Catalog ID must not revert to filename stem during broken JSON mid-edit")
            assertEquals("Authored Title", keptEntry.title)
        } finally {
            tmpDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `discoverEntries caches directory listing within TTL when forceRefresh is false`() {
        val tmpDir = Files.createTempDirectory("ciacola-dir-cache-ttl-")
        try {
            val deckFile = tmpDir.resolve("cached-talk.deck.json")
            Files.writeString(
                deckFile,
                """{"format":1,"id":"cached-talk","title":"Initial Title","slides":[{"id":"s1"}]}""",
                StandardCharsets.UTF_8,
            )

            val first = DeckCatalog.discoverEntries(tmpDir, forceRefresh = false)
            assertEquals("Initial Title", DeckCatalog.byIdOrNull("cached-talk", first)?.title)

            // In-place file update keeps directory mtime unchanged: cached call avoids re-reading within TTL.
            Files.writeString(
                deckFile,
                """{"format":1,"id":"cached-talk","title":"Updated Title","slides":[{"id":"s1"}]}""",
                StandardCharsets.UTF_8,
            )
            val cachedAgain = DeckCatalog.discoverEntries(tmpDir, forceRefresh = false)
            assertEquals("Initial Title", DeckCatalog.byIdOrNull("cached-talk", cachedAgain)?.title)

            // Explicit forceRefresh picks up the in-place file update immediately.
            val refreshed = DeckCatalog.discoverEntries(tmpDir, forceRefresh = true)
            assertEquals("Updated Title", DeckCatalog.byIdOrNull("cached-talk", refreshed)?.title)
        } finally {
            tmpDir.toFile().deleteRecursively()
        }
    }
}
