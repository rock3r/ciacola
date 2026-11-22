package dev.sebastiano.ciacola.decks

import dev.sebastiano.ciacola.document.BuildEffectRegistry
import dev.sebastiano.ciacola.document.CanonicalJson
import dev.sebastiano.ciacola.document.DeckDecodeResult
import dev.sebastiano.ciacola.document.DeckDocumentCodec
import dev.sebastiano.ciacola.document.IssueSeverity
import dev.sebastiano.ciacola.document.LegacyOverridesImporter
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

/**
 * CLI runner backing `:deck:importLegacyOverrides`. Isolated from runtime deck loading
 * so authored deck content never reads `.storyboard/`.
 */
internal object LegacyOverridesImporterCli {
    fun run(args: Array<String>, resolveDecksDir: (String?) -> File) {
        val dirArg = args.firstOrNull { it.startsWith("--dir=") }?.removePrefix("--dir=")
        val storyboardDirArg = args.firstOrNull { it.startsWith("--storyboard-dir=") }?.removePrefix("--storyboard-dir=")
        val deckFilter = args.firstOrNull { it.startsWith("--deck=") }?.removePrefix("--deck=")
        val decksDir = resolveDecksDir(dirArg)
        if (!decksDir.isDirectory) {
            System.err.println("Decks directory not found: ${decksDir.absolutePath}")
            exitProcess(1)
        }
        val storyboardDir = if (storyboardDirArg != null) File(storyboardDirArg) else File(".storyboard")
        if (!storyboardDir.isDirectory) {
            println("No legacy .storyboard directory at ${storyboardDir.absolutePath}; nothing to import.")
            return
        }

        val deckFiles = decksDir.listFiles { f -> f.isFile && f.name.endsWith(".deck.json") }
            ?.filter { deckFilter == null || it.name.removeSuffix(".deck.json") == deckFilter }
            ?.sortedBy { it.name }
            .orEmpty()
        if (deckFiles.isEmpty()) {
            println("No matching *.deck.json files found in ${decksDir.absolutePath}.")
            return
        }

        var failed = false
        for (file in deckFiles) {
            val deckId = file.name.removeSuffix(".deck.json")
            val reg = AllDocumentDecks.byId(deckId)
            val specimens = reg?.specimens ?: AllDocumentDecks.allSpecimens
            val raw = Files.readString(file.toPath(), StandardCharsets.UTF_8)
            val decoded = DeckDocumentCodec.decode(raw, specimens = specimens, effects = BuildEffectRegistry.Standard)
            val success = when (decoded) {
                is DeckDecodeResult.Success -> decoded
                is DeckDecodeResult.ReadOnlyNewerFormat -> {
                    System.err.println("${file.name}: skipping newer format (${decoded.message})")
                    continue
                }
                is DeckDecodeResult.Failure -> {
                    System.err.println("${file.name}: cannot import into invalid deck (${decoded.errors.size} error(s))")
                    failed = true
                    continue
                }
            }

            val candidateDir = File(storyboardDir, deckId).takeIf { it.isDirectory }
                ?: storyboardDir.takeIf { deckId == "foundation" }
                ?: continue

            val elemFile = firstExisting(candidateDir, "element-overrides.json", "elements.json")
            val timelineFile = firstExisting(candidateDir, "timeline-overrides.json", "timeline.json")
            val designByTheme = LinkedHashMap<String, Path>()
            val sharedDesign = firstExisting(candidateDir, "design-system-overrides.json", "design-system.json")
            for (themeName in success.document.themes.keys.sorted()) {
                val perTheme = firstExisting(
                    candidateDir,
                    "design-system-$themeName.json",
                    "design-$themeName.json",
                )
                val resolved = perTheme ?: sharedDesign
                if (resolved != null) {
                    designByTheme[themeName] = resolved
                }
            }
            if (elemFile == null && timelineFile == null && designByTheme.isEmpty()) {
                continue
            }

            val archiveDir = storyboardDir.toPath().resolve("archived-legacy").resolve(deckId)
            val report = LegacyOverridesImporter.importFromFiles(
                document = success.document,
                elementOverridesFile = elemFile,
                timelineOverridesFile = timelineFile,
                designSystemOverridesByTheme = designByTheme,
                archiveDir = archiveDir,
            )

            val postLoadErrors = DeckDocumentCodec.validate(
                document = report.document,
                specimens = specimens,
                effects = BuildEffectRegistry.Standard,
            ).filter { it.severity == IssueSeverity.LoadError }
            if (postLoadErrors.isNotEmpty()) {
                System.err.println("${file.name}: imported document failed validation (${postLoadErrors.size} load error(s)); not writing:")
                postLoadErrors.forEach { System.err.println("  ${it.code} @ ${it.address}: ${it.message}") }
                failed = true
                continue
            }

            if (report.document != success.document) {
                Files.writeString(file.toPath(), CanonicalJson.print(report.document), StandardCharsets.UTF_8)
            }
            println("Imported legacy overrides for $deckId: ${report.appliedItems.size} applied, ${report.unplacedItems.size} unplaced, ${report.archivedFiles.size} archived.")
            for (item in report.unplacedItems) {
                println("  [unplaced] $item")
            }
            for (archived in report.archivedFiles) {
                println("  [archived] $archived")
            }
        }

        if (failed) {
            exitProcess(1)
        }
    }

    private fun firstExisting(dir: File, vararg names: String): Path? =
        names.map { File(dir, it) }.firstOrNull { it.isFile }?.toPath()
}
