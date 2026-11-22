package dev.sebastiano.ciacola.decks

import dev.sebastiano.ciacola.document.BuildEffectRegistry
import dev.sebastiano.ciacola.document.CanonicalJson
import dev.sebastiano.ciacola.document.DeckDecodeResult
import dev.sebastiano.ciacola.document.DeckDocumentCodec
import dev.sebastiano.ciacola.document.IssueSeverity
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import kotlin.system.exitProcess

/**
 * CLI entry point backing Gradle tasks `:deck:formatDecks`, `:deck:verifyDecksCanonical`,
 * and `:deck:importLegacyOverrides`.
 */
object DeckCanonicalizerCli {
    @JvmStatic
    fun main(args: Array<String>) {
        if ("--import-legacy-overrides" in args) {
            LegacyOverridesImporterCli.run(args, ::resolveDecksDir)
            return
        }
        val verifyOnly = "--verify" in args
        val dirArg = args.firstOrNull { it.startsWith("--dir=") }?.removePrefix("--dir=")
        val decksDir = resolveDecksDir(dirArg)
        if (!decksDir.isDirectory) {
            System.err.println("Decks directory not found: ${decksDir.absolutePath}")
            exitProcess(1)
        }

        val deckFiles = decksDir.listFiles { f ->
            f.isFile && !f.name.startsWith(".") && f.name.endsWith(".deck.json")
        }
            ?.sortedBy { it.name }
            .orEmpty()
        if (deckFiles.isEmpty()) {
            System.err.println("No *.deck.json files found in ${decksDir.absolutePath}")
            exitProcess(1)
        }

        var failed = false
        for (file in deckFiles) {
            val deckId = file.name.removeSuffix(".deck.json")
            val specimens = AllDocumentDecks.resolveSpecimensForDeck(deckId)
            val assetsDir = File(decksDir, "$deckId.assets")
            val raw = Files.readString(file.toPath(), StandardCharsets.UTF_8)
            val decoded = DeckDocumentCodec.decode(
                rawText = raw,
                specimens = specimens,
                effects = BuildEffectRegistry.Standard,
                assetExists = { src ->
                    val norm = src.removePrefix("/")
                    File(assetsDir, norm).isFile ||
                        File(decksDir, norm).isFile ||
                        DeckCanonicalizerCli::class.java.classLoader.getResource("decks/$deckId.assets/$norm") != null ||
                        DeckCanonicalizerCli::class.java.classLoader.getResource(norm) != null
                },
            )
            val success = when (decoded) {
                is DeckDecodeResult.Success -> decoded
                is DeckDecodeResult.ReadOnlyNewerFormat -> {
                    System.err.println("${file.name}: ${decoded.message}")
                    failed = true
                    continue
                }
                is DeckDecodeResult.Failure -> {
                    System.err.println("${file.name}: decode failed with ${decoded.errors.size} error(s):")
                    decoded.errors.forEach { System.err.println("  [${it.severity}] ${it.code} @ ${it.address}: ${it.message}") }
                    failed = true
                    continue
                }
            }

            val lintErrors = success.lintIssues.filter { it.severity == IssueSeverity.LintError }
            if (lintErrors.isNotEmpty()) {
                System.err.println("${file.name}: ${lintErrors.size} lint error(s):")
                lintErrors.forEach { System.err.println("  [${it.severity}] ${it.code} @ ${it.address}: ${it.message}") }
                failed = true
                continue
            }

            val canonical = CanonicalJson.print(success.document)
            if (verifyOnly) {
                if (canonical != raw) {
                    System.err.println("${file.name}: NOT canonical JSON (run `./gradlew :deck:formatDecks` to normalize).")
                    failed = true
                }
            } else if (canonical != raw) {
                Files.writeString(file.toPath(), canonical, StandardCharsets.UTF_8)
                println("Formatted ${file.name}")
            }
        }

        if (failed) {
            exitProcess(1)
        }
    }

    private fun resolveDecksDir(dirArg: String?): File = if (dirArg != null) {
        File(dirArg)
    } else {
        File("deck/src/desktopMain/resources/decks").takeIf { it.isDirectory }
            ?: File("src/desktopMain/resources/decks")
    }
}
