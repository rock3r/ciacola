package dev.sebastiano.ciacola.decks

import dev.bnorm.storyboard.easel.resolveStoryboardWorkspaceRoot
import dev.sebastiano.ciacola.DeckCatalog
import dev.sebastiano.ciacola.document.CanonicalJson
import dev.sebastiano.ciacola.document.DeckLoadState
import dev.sebastiano.ciacola.document.SyncState
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Verifies that all legacy override stores, bake/snippet machinery, MagicText v1 bindings, and
 * Kotlin slide-DSL symbols have been deleted from production source across the repository,
 * `.storyboard/` is referenced exclusively for session state and slide review comments, and
 * `DeckSource` resolves writable file vs read-only classpath resources.
 */
class LegacyAuthoringRemovalTest {

    private val deletedProductionFiles = listOf(
        "deck/src/commonMain/kotlin/dev/sebastiano/ciacola/FoundationDeck.kt",
        "deck/src/desktopMain/kotlin/dev/sebastiano/ciacola/HotReloadStoryboard.kt",
        "deck-effects/src/commonMain/kotlin/dev/sebastiano/ciacola/authoring/DeckSlideBuilder.kt",
        "deck-effects/src/commonMain/kotlin/dev/sebastiano/ciacola/authoring/DeckHost.kt",
        "deck-effects/src/desktopMain/kotlin/dev/sebastiano/ciacola/MagicTextV1Renderer.desktop.kt",
        "deck-inspector/src/desktopMain/kotlin/dev/sebastiano/ciacola/inspector/element/ElementOverridesStore.desktop.kt",
        "deck-inspector/src/desktopMain/kotlin/dev/sebastiano/ciacola/inspector/design/DesignSystemStore.desktop.kt",
        "deck-inspector/src/desktopMain/kotlin/dev/sebastiano/ciacola/inspector/design/DesignSystemSnippetExporter.desktop.kt",
        "deck-timeline/src/desktopMain/kotlin/dev/sebastiano/ciacola/timeline/TimelineOverridesStore.desktop.kt",
        "deck-timeline/src/desktopMain/kotlin/dev/sebastiano/ciacola/timeline/AgentBakeDispatcher.kt",
    )

    private val forbiddenSymbols = listOf(
        "ElementOverridesStore",
        "ElementPatch",
        "DeckElementOverridesFile",
        "LocalElementOverrides",
        "ElementEditorController",
        "DesignSystemStore",
        "DesignSystemOverrides",
        "DesignSystemSnippetExporter",
        "TimelineOverridesStore",
        "TrackOverride",
        "SceneBeatOverride",
        "DeckTimelineOverridesFile",
        "VirtualizedFrame",
        "TimelineHarvestCollector",
        "measureSegmentDurationsMs",
        "TimelineStudioController",
        "DeckLoopClock",
        "rememberDeckLoopMillis",
        "AgentBakeDispatcher",
        "hasUnbakedOverrides",
        "MagicTextV1Renderer",
        "DeckMagicText",
        "DeckSlideBuilder",
        "ciacolaDeck",
        "DeckHostParams",
        "createFoundationDeck",
    )

    private fun collectMainKotlinSources(repoRoot: Path): List<Path> {
        val ciacolaModules = listOf(
            "deck-core",
            "deck-effects",
            "deck-inspector",
            "deck-presenter",
            "deck-review",
            "deck-timeline",
            "deck",
        )
        val out = mutableListOf<Path>()
        for (mod in ciacolaModules) {
            val srcDir = repoRoot.resolve(mod).resolve("src")
            if (!Files.isDirectory(srcDir)) continue
            Files.walk(srcDir).use { stream ->
                stream.filter { p ->
                    p.isRegularFile() &&
                        p.extension == "kt" &&
                        (p.toString().contains("/commonMain/") ||
                            p.toString().contains("/desktopMain/") ||
                            p.toString().contains("/jvmMain/"))
                }.forEach { p -> out.add(p) }
            }
        }
        return out.sorted()
    }

    /** Strips `//` line comments and `/* ... */` block comments while preserving line structure. */
    private fun stripComments(source: String): String {
        val sb = StringBuilder(source.length)
        var i = 0
        val n = source.length
        var inString = false
        var inTripleString = false
        while (i < n) {
            if (!inString && i + 2 < n && source.startsWith("\"\"\"", i)) {
                inTripleString = !inTripleString
                sb.append("\"\"\"")
                i += 3
                continue
            }
            if (!inTripleString && source[i] == '"' && (i == 0 || source[i - 1] != '\\')) {
                inString = !inString
                sb.append('"')
                i++
                continue
            }
            if (!inString && !inTripleString) {
                if (i + 1 < n && source[i] == '/' && source[i + 1] == '/') {
                    while (i < n && source[i] != '\n') i++
                    continue
                }
                if (i + 1 < n && source[i] == '/' && source[i + 1] == '*') {
                    i += 2
                    while (i + 1 < n && !(source[i] == '*' && source[i + 1] == '/')) {
                        if (source[i] == '\n') sb.append('\n')
                        i++
                    }
                    i += 2
                    continue
                }
            }
            sb.append(source[i])
            i++
        }
        return sb.toString()
    }

    @Test
    fun `deleted legacy files do not exist and main source has zero references to deleted symbols`() {
        val repoRoot = resolveStoryboardWorkspaceRoot()
        for (rel in deletedProductionFiles) {
            assertFalse(
                Files.exists(repoRoot.resolve(rel)),
                "Legacy authoring file must be deleted: $rel",
            )
        }

        val mainSources = collectMainKotlinSources(repoRoot)
        assertTrue(mainSources.size >= 40, "Expected >= 40 main Kotlin source files, got ${mainSources.size}")

        val symbolRegexes = forbiddenSymbols.associateWith { sym -> Regex("\\b${Regex.escape(sym)}\\b") }
        val violations = mutableListOf<String>()

        for (file in mainSources) {
            val relPath = repoRoot.relativize(file).toString().replace('\\', '/')
            val codeOnly = stripComments(file.readText())
            for ((sym, regex) in symbolRegexes) {
                val match = regex.find(codeOnly)
                if (match != null) {
                    val lineNum = codeOnly.substring(0, match.range.first).count { it == '\n' } + 1
                    violations += "$relPath:$lineNum references deleted legacy symbol '$sym'"
                }
            }
        }

        assertTrue(
            violations.isEmpty(),
            "Legacy symbol scan failed:\n" + violations.joinToString("\n"),
        )
    }

    @Test
    fun `storyboard directory is never used for authored deck content or legacy override files`() {
        val repoRoot = resolveStoryboardWorkspaceRoot()
        val mainSources = collectMainKotlinSources(repoRoot)
        val legacyOverrideFilenames = listOf(
            "element-overrides.json",
            "timeline-overrides.json",
            "design-system.json",
            "studio-layout.properties",
            "active-deck",
        )
        val violations = mutableListOf<String>()

        for (file in mainSources) {
            val relPath = repoRoot.relativize(file).toString().replace('\\', '/')
            if (file.name.startsWith("LegacyOverridesImporter")) continue
            val codeOnly = stripComments(file.readText())

            for (legacyName in legacyOverrideFilenames) {
                if (codeOnly.contains("\"$legacyName\"")) {
                    violations += "$relPath references legacy override file '$legacyName'"
                }
            }

            if (codeOnly.contains("\".storyboard\"")) {
                val allowed = relPath.startsWith("deck-review/") ||
                    relPath.contains("StudioSessionStore") ||
                    relPath.endsWith("DeckSwitcherSupport.desktop.kt") ||
                    relPath.endsWith("Main.kt")
                if (!allowed) {
                    violations += "$relPath references '.storyboard' outside session/review scope"
                }
            }
        }

        assertTrue(
            violations.isEmpty(),
            "Storyboard directory content isolation failed:\n" + violations.joinToString("\n"),
        )
    }

    @Test
    fun `DeckSource resolves writable File when ciacola decks dir exists and ReadOnly Resource otherwise`() {
        val entry = DeckCatalog.foundation

        // 1. Unset property -> classpath DeckSource.Resource (read-only store).
        val resourceSource = resolveDeckSource(entry, decksDirProperty = null)
        assertIs<DeckSource.Resource>(resourceSource)
        assertEquals("decks/foundation.deck.json", resourceSource.resourcePath)

        val readOnlyStore = openReadOnlyResourceDeckStore(entry)
        assertIs<DeckLoadState.Loaded>(readOnlyStore.load)
        assertIs<SyncState.ReadOnly>(readOnlyStore.sync)
        assertEquals(5, readOnlyStore.document.slides.size)

        // 2. Writable directory containing foundation.deck.json -> DeckSource.File.
        val tmpDir = Files.createTempDirectory("ciacola-decks-dir-")
        try {
            val deckFile = tmpDir.resolve("foundation.deck.json")
            Files.writeString(deckFile, CanonicalJson.print(readOnlyStore.document), StandardCharsets.UTF_8)

            val fileSource = resolveDeckSource(entry, decksDirProperty = tmpDir.toString())
            assertIs<DeckSource.File>(fileSource)
            assertEquals(deckFile.toAbsolutePath().normalize(), fileSource.file)

            // Missing file inside directory -> falls back cleanly to DeckSource.Resource.
            val missingOther = resolveDeckSource(DeckCatalog.shadersQuickie2025, decksDirProperty = tmpDir.toString())
            assertIs<DeckSource.Resource>(missingOther)
        } finally {
            tmpDir.toFile().deleteRecursively()
        }
    }
}
