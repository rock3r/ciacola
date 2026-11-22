package dev.sebastiano.ciacola.decks

import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.IntSize
import dev.bnorm.storyboard.ContentDecorator
import dev.bnorm.storyboard.Storyboard
import dev.bnorm.storyboard.easel.Animatic
import dev.bnorm.storyboard.easel.Easel
import dev.bnorm.storyboard.easel.SceneMode
import dev.bnorm.storyboard.easel.export.StoryboardVideoExporter
import dev.bnorm.storyboard.easel.rememberAnimatic
import dev.sebastiano.ciacola.DeckCatalog
import dev.sebastiano.ciacola.DeckEntry
import dev.sebastiano.ciacola.DeckTextRendererProvider
import dev.sebastiano.ciacola.discoverEntries
import dev.sebastiano.ciacola.sanitizeDeckId
import dev.sebastiano.ciacola.document.BuildEffectRegistry
import dev.sebastiano.ciacola.document.DeckDecodeResult
import dev.sebastiano.ciacola.document.DeckDocument
import dev.sebastiano.ciacola.document.DeckDocumentCodec
import dev.sebastiano.ciacola.document.DeckMigrations
import dev.sebastiano.ciacola.document.DocumentIssue
import dev.sebastiano.ciacola.document.IssueSeverity
import dev.sebastiano.ciacola.document.RenderSession
import dev.sebastiano.ciacola.document.SlideSpec
import dev.sebastiano.ciacola.document.render.DefaultMagicTextV2Renderer
import dev.sebastiano.ciacola.document.render.DocumentRenderConfig
import dev.sebastiano.ciacola.document.render.buildDocumentStoryboard
import dev.sebastiano.ciacola.document.render.resolveDeckTheme
import dev.sebastiano.ciacola.document.slideDisplayTitle
import dev.sebastiano.ciacola.forExternalFile
import dev.sebastiano.ciacola.inspector.document.DocumentThemeResolver
import dev.sebastiano.ciacola.inspector.lint.DocumentDeckLintEngine
import dev.sebastiano.ciacola.inspector.lint.DocumentLintIssue
import dev.sebastiano.ciacola.inspector.lint.DocumentLintReport
import dev.sebastiano.ciacola.inspector.lint.LintSeverity
import dev.sebastiano.ciacola.ui.CiacolaJewelTheme
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import kotlin.system.exitProcess
import kotlin.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image as SkiaImage
import org.jetbrains.skia.ImageInfo

/**
 * Headless CLI entry point for agentic `.deck.json` workflows backing `:deck:lintDeck`,
 * `:deck:renderSlides`, and `:deck:exportVideo`.
 */
object DeckAgentCli {

    private val Mp4ExtensionRegex = Regex("""(?i)\.mp4$""")

    private val prettyJson = Json {
        prettyPrint = true
        encodeDefaults = true
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val code = runCli(args)
        if (code != 0) {
            exitProcess(code)
        }
    }

    fun runCli(args: Array<String>): Int {
        if ("--help" in args || "-h" in args || args.isEmpty()) {
            printUsage()
            return if (args.isEmpty()) 1 else 0
        }

        val parsed = runCatching { parseArgs(args) }.getOrElse { err ->
            System.err.println("Error: ${err.message}")
            printUsage()
            return 1
        }

        return when (parsed.mode) {
            CliMode.Lint -> runLint(parsed)
            CliMode.RenderSlides -> runRenderSlides(parsed)
            CliMode.ExportVideo -> runExportVideo(parsed)
        }
    }

    private enum class CliMode {
        Lint,
        RenderSlides,
        ExportVideo,
    }

    private data class CliOptions(
        val mode: CliMode,
        val deckArg: String,
        val dirArg: String?,
        val slideRanges1Based: List<IntRange>?,
        val beatFilter: Set<Int>?,
        val outArg: String?,
        val fps: Int,
        val size: IntSize,
        val theme: String?,
        val jsonOutput: Boolean,
        val failOnWarning: Boolean,
    )

    private fun parseArgs(args: Array<String>): CliOptions {
        var mode: CliMode? = null
        var deckArg: String? = System.getProperty("ciacola.deck")?.trim()?.takeIf { it.isNotEmpty() }
        var dirArg: String? = System.getProperty(DECKS_DIR_SYSTEM_PROPERTY)?.trim()?.takeIf { it.isNotEmpty() }
        var slidesRaw: String? = null
        var beatsRaw: String? = null
        var outArg: String? = null
        var fps = 30
        var size = IntSize(1280, 720)
        var theme: String? = null
        var jsonOutput = false
        var failOnWarning = false

        fun setMode(next: CliMode) {
            if (mode != null && mode != next) {
                error("Conflicting mode flags: $mode and $next")
            }
            mode = next
        }

        for (arg in args) {
            when {
                arg == "--lint" -> setMode(CliMode.Lint)
                arg == "--render-slides" -> setMode(CliMode.RenderSlides)
                arg == "--export-video" -> setMode(CliMode.ExportVideo)
                arg == "--json" -> jsonOutput = true
                arg.startsWith("--deck=") -> deckArg = arg.removePrefix("--deck=").trim()
                arg.startsWith("--dir=") -> dirArg = arg.removePrefix("--dir=").trim()
                arg.startsWith("--slides=") -> slidesRaw = arg.removePrefix("--slides=").trim()
                arg.startsWith("--beats=") -> beatsRaw = arg.removePrefix("--beats=").trim()
                arg.startsWith("--beat=") -> beatsRaw = arg.removePrefix("--beat=").trim()
                arg.startsWith("--out=") -> outArg = arg.removePrefix("--out=").trim()
                arg.startsWith("--fps=") -> {
                    val v = arg.removePrefix("--fps=").trim().toIntOrNull()
                        ?: error("Invalid --fps value: $arg")
                    require(v > 0) { "--fps must be positive, got $v" }
                    fps = v
                }
                arg.startsWith("--size=") -> size = parseSize(arg.removePrefix("--size=").trim())
                arg.startsWith("--theme=") -> theme = arg.removePrefix("--theme=").trim().takeIf { it.isNotEmpty() }
                arg.startsWith("--fail-on=") -> {
                    when (val level = arg.removePrefix("--fail-on=").trim().lowercase()) {
                        "error", "errors" -> failOnWarning = false
                        "warning", "warnings" -> failOnWarning = true
                        else -> error("Invalid --fail-on value '$level' (expected 'error' or 'warning')")
                    }
                }
                else -> error("Unknown CLI flag: $arg")
            }
        }

        val resolvedMode = mode ?: error("Specify one mode: --lint, --render-slides, or --export-video")
        val resolvedDeck = deckArg?.takeIf { it.isNotEmpty() }
            ?: error("Missing --deck=<id|path> (or -Pciacola.deck=<id|path>)")

        return CliOptions(
            mode = resolvedMode,
            deckArg = resolvedDeck,
            dirArg = dirArg?.takeIf { it.isNotEmpty() },
            slideRanges1Based = slidesRaw?.takeIf { it.isNotEmpty() }?.let(::parseSlideRanges),
            beatFilter = beatsRaw?.takeIf { it.isNotEmpty() }?.let(::parseBeatFilter),
            outArg = outArg?.takeIf { it.isNotEmpty() },
            fps = fps,
            size = size,
            theme = theme,
            jsonOutput = jsonOutput,
            failOnWarning = failOnWarning,
        )
    }

    internal fun parseSize(raw: String): IntSize {
        val parts = raw.lowercase().split('x')
        require(parts.size == 2) { "Invalid --size '$raw' (expected WxH, e.g. 1280x720 or 1920x1080)" }
        val w = parts[0].trim().toIntOrNull() ?: error("Invalid width in --size '$raw'")
        val h = parts[1].trim().toIntOrNull() ?: error("Invalid height in --size '$raw'")
        require(w > 0 && h > 0) { "--size dimensions must be positive, got ${w}x${h}" }
        return IntSize(w, h)
    }

    internal fun parseSlideRanges(raw: String): List<IntRange> {
        val ranges = mutableListOf<IntRange>()
        for (token in raw.split(',')) {
            val part = token.trim()
            if (part.isEmpty()) continue
            val dashParts = if (".." in part) part.split("..") else part.split('-')
            when (dashParts.size) {
                1 -> {
                    val n = dashParts[0].trim().toIntOrNull()
                        ?: error("Invalid slide number '$part' in --slides=$raw")
                    require(n >= 1) { "Slide numbers are 1-based, got $n" }
                    ranges += n..n
                }
                2 -> {
                    val a = dashParts[0].trim().toIntOrNull()
                        ?: error("Invalid range start '$part' in --slides=$raw")
                    val b = dashParts[1].trim().toIntOrNull()
                        ?: error("Invalid range end '$part' in --slides=$raw")
                    require(a >= 1 && b >= a) { "Invalid slide range '$part' in --slides=$raw" }
                    ranges += a..b
                }
                else -> error("Invalid slide range '$part' in --slides=$raw")
            }
        }
        require(ranges.isNotEmpty()) { "--slides must specify at least one slide index" }
        return ranges
    }

    private fun parseBeatFilter(raw: String): Set<Int> {
        val out = linkedSetOf<Int>()
        for (token in raw.split(',')) {
            val n = token.trim().toIntOrNull() ?: error("Invalid beat index '$token' in --beats=$raw")
            require(n >= 0) { "Beat indices must be >= 0, got $n" }
            out += n
        }
        require(out.isNotEmpty()) { "--beats must specify at least one beat index" }
        return out
    }

    private data class ResolvedDeckInput(
        val entry: DeckEntry,
        val source: DeckSource,
        val decksDir: Path?,
        val decodeResult: DeckDecodeResult,
    )

    private fun resolveValidatedTheme(document: DeckDocument, requestedTheme: String): String {
        val exact = document.themes.keys.firstOrNull { it == requestedTheme }
        if (exact != null) return exact
        val caseInsensitive = document.themes.keys.firstOrNull { it.equals(requestedTheme, ignoreCase = true) }
        if (caseInsensitive != null) return caseInsensitive
        val available = document.themes.keys.sorted()
        val availableText = if (available.isEmpty()) "<none>" else available.joinToString(", ")
        error("Unknown theme '$requestedTheme' for deck '${document.id}'. Available themes: $availableText")
    }

    private fun resolveAndDecodeDeck(options: CliOptions): ResolvedDeckInput {
        val decksDir = resolveDefaultDecksDirectory(options.dirArg)
        val deckPathCandidate = Path.of(options.deckArg)
        val isDirectPath = options.deckArg.endsWith(".deck.json") ||
            options.deckArg.endsWith(".json") ||
            Files.isRegularFile(deckPathCandidate)

        val entry = if (isDirectPath) {
            require(Files.isRegularFile(deckPathCandidate)) {
                "Deck file not found: ${deckPathCandidate.toAbsolutePath().normalize()}"
            }
            DeckEntry.forExternalFile(deckPathCandidate)
        } else {
            val catalog = DeckCatalog.discoverEntries(decksDir)
            DeckCatalog.byIdOrNull(options.deckArg, catalog)
                ?: error(
                    "Unknown deck '${options.deckArg}'. Available: ${catalog.joinToString { it.id }}",
                )
        }

        val source = resolveDeckSource(entry, decksDir?.toAbsolutePath()?.toString())
        val decodeResult = when (source) {
            is DeckSource.File -> {
                val rawText = Files.readString(source.file, StandardCharsets.UTF_8)
                val deckDir = source.file.parent
                val assetsDir = source.assetsDir
                DeckDocumentCodec.decode(
                    rawText = rawText,
                    specimens = entry.specimens,
                    effects = BuildEffectRegistry.Standard,
                    migrations = DeckMigrations.Default,
                    assetExists = { src ->
                        assetExistsOnDiskOrClasspath(entry.id, src, deckDir, assetsDir)
                    },
                )
            }
            is DeckSource.Resource -> loadClasspathDeckDecodeResult(entry)
        }
        return ResolvedDeckInput(
            entry = entry,
            source = source,
            decksDir = decksDir,
            decodeResult = decodeResult,
        )
    }

    internal fun assetExistsOnDiskOrClasspath(
        deckId: String,
        src: String,
        deckDir: Path?,
        assetsDir: Path?,
    ): Boolean {
        val norm = src.trim().removePrefix("/")
        if (norm.isEmpty()) return false
        fun isRegularFileInside(baseDir: Path?): Boolean {
            if (baseDir == null) return false
            val base = baseDir.toAbsolutePath().normalize()
            val resolved = runCatching { base.resolve(norm).normalize() }.getOrNull() ?: return false
            return resolved.startsWith(base) && Files.isRegularFile(resolved)
        }
        if (isRegularFileInside(assetsDir)) return true
        if (deckDir != null) {
            if (isRegularFileInside(deckDir.resolve("$deckId.assets"))) return true
            if (isRegularFileInside(deckDir)) return true
        }
        if (norm.split('/', '\\').any { it == ".." }) return false
        val cl = DeckAgentCli::class.java.classLoader
        return cl.getResource("decks/$deckId.assets/$norm") != null || cl.getResource(norm) != null
    }

    private fun runLint(options: CliOptions): Int {
        val resolved = runCatching { resolveAndDecodeDeck(options) }.getOrElse { err ->
            return emitCliError(options, code = "DECK_OPEN_ERROR", message = err.message ?: "Failed to open deck")
        }
        val deckFileLabel = when (val src = resolved.source) {
            is DeckSource.File -> src.file.toString()
            is DeckSource.Resource -> "classpath:${src.resourcePath}"
        }

        val success = when (val dec = resolved.decodeResult) {
            is DeckDecodeResult.Success -> dec
            is DeckDecodeResult.ReadOnlyNewerFormat -> {
                return emitLintDecodeFailure(
                    options = options,
                    deckId = resolved.entry.id,
                    deckFile = deckFileLabel,
                    title = resolved.entry.title,
                    issues = listOf(
                        DocumentIssue(
                            severity = IssueSeverity.LoadError,
                            code = "NEWER_FORMAT",
                            address = "<root>",
                            message = dec.message,
                        ),
                    ),
                )
            }
            is DeckDecodeResult.Failure -> {
                return emitLintDecodeFailure(
                    options = options,
                    deckId = resolved.entry.id,
                    deckFile = deckFileLabel,
                    title = resolved.entry.title,
                    issues = dec.errors,
                )
            }
        }

        val doc = success.document
        val themeNames = if (options.theme != null) {
            val validated = runCatching { resolveValidatedTheme(doc, options.theme) }.getOrElse { err ->
                return emitCliError(options, code = "INVALID_THEME", message = err.message ?: "Invalid theme")
            }
            listOf(validated)
        } else {
            doc.themes.keys.toList().ifEmpty { listOf(DocumentThemeResolver.resolveActiveThemeName(doc)) }
        }
        val deckDir = (resolved.source as? DeckSource.File)?.file?.parent ?: resolved.decksDir
        val assetsDir = (resolved.source as? DeckSource.File)?.assetsDir
            ?: resolved.decksDir?.resolve("${resolved.entry.id}.assets")?.takeIf { Files.isDirectory(it) }

        val selectedIndices = options.slideRanges1Based?.let { ranges ->
            runCatching { resolveDocumentSlideIndices(doc, ranges) }.getOrElse { err ->
                return emitCliError(
                    options = options,
                    code = "INVALID_SLIDES",
                    message = err.message ?: "Invalid --slides selection",
                )
            }
        }
        val report: DocumentLintReport = runCatching {
            runBlocking {
                DocumentDeckLintEngine(
                    specimens = resolved.entry.specimens,
                    effects = BuildEffectRegistry.Standard,
                ).use { engine ->
                    if (selectedIndices == null) {
                        engine.lintFullDeck(
                            document = doc,
                            themeNames = themeNames,
                            deckDir = deckDir,
                            assetsDir = assetsDir,
                        )
                    } else {
                        lintSelectedSlides(
                            engine = engine,
                            document = doc,
                            selectedIndices = selectedIndices,
                            themeNames = themeNames,
                            deckDir = deckDir,
                            assetsDir = assetsDir,
                        )
                    }
                }
            }
        }.getOrElse { err ->
            return emitCliError(
                options = options,
                code = "LINT_RUNTIME_ERROR",
                message = err.message ?: "Lint engine error",
            )
        }

        val errors = report.issues.count { it.severity == LintSeverity.Error }
        val warnings = report.issues.count { it.severity == LintSeverity.Warning }
        val failed = errors > 0 || (options.failOnWarning && warnings > 0)
        val nonSkippedSlides = doc.slides.filterNot { it.skipped }
        fun issueSlideNumber1Based(issue: DocumentLintIssue): Int {
            val nonSkippedIdx = nonSkippedSlides.indexOfFirst { it.id == issue.slideId }
            return if (nonSkippedIdx >= 0) nonSkippedIdx + 1 else issue.slideIndex + 1
        }

        if (options.jsonOutput) {
            val jsonReport = LintCliJsonReport(
                deckId = doc.id,
                title = doc.title.ifBlank { resolved.entry.title },
                deckFile = deckFileLabel,
                passed = !failed,
                errorCount = errors,
                warningCount = warnings,
                slidesAudited = report.stats.slidesAudited,
                beatsAudited = report.stats.beatsAudited,
                totalDurationMs = report.stats.totalDurationMs,
                issues = report.issues.map { issue ->
                    val slide = doc.slides.getOrNull(issue.slideIndex)
                    val slideNum = issueSlideNumber1Based(issue)
                    LintCliJsonIssue(
                        id = issue.id,
                        code = issue.code,
                        severity = issue.severity.name.lowercase(),
                        source = issue.source.name,
                        slideNumber = slideNum,
                        slideId = issue.slideId,
                        slideTitle = slide?.let { slideDisplayTitle(it, slideNum) }.orEmpty(),
                        beatIndex = issue.beatIndex,
                        nodeId = issue.nodeId,
                        themeName = issue.themeName,
                        title = issue.title,
                        detail = issue.detail,
                    )
                },
            )
            println(prettyJson.encodeToString(jsonReport))
        } else {
            println(
                "Lint '${doc.id}' ($deckFileLabel): $errors error(s), $warnings warning(s) " +
                    "across ${report.stats.slidesAudited} slide(s) / ${report.stats.beatsAudited} beat(s) " +
                    "in ${"%.1f".format(Locale.US, report.stats.totalDurationMs)} ms",
            )
            for (issue in report.issues) {
                val sev = if (issue.severity == LintSeverity.Error) "ERROR" else "WARN"
                val slide = doc.slides.getOrNull(issue.slideIndex)
                val slideNum = issueSlideNumber1Based(issue)
                val slideLabel = slide?.let { slideDisplayTitle(it, slideNum) } ?: issue.slideId
                val addr = listOfNotNull(issue.slideId.takeIf { it.isNotBlank() }, issue.nodeId).joinToString("/")
                val themeSuffix = issue.themeName?.let { " [$it]" }.orEmpty()
                println(
                    "  [$sev] S${slideNum}:B${issue.beatIndex} \"$slideLabel\" " +
                        "${issue.code}$themeSuffix @ $addr: ${issue.title} — ${issue.detail}",
                )
            }
        }
        return if (failed) 1 else 0
    }

    private suspend fun lintSelectedSlides(
        engine: DocumentDeckLintEngine,
        document: DeckDocument,
        selectedIndices: List<Int>,
        themeNames: List<String>,
        deckDir: Path?,
        assetsDir: Path?,
    ): DocumentLintReport {
        val mergedIssues = LinkedHashMap<String, DocumentLintIssue>()
        var totalBeats = 0
        var totalMs = 0.0
        for (docSlideIdx in selectedIndices) {
            val r = engine.lintSingleSlide(
                document = document,
                slideIndex = docSlideIdx,
                themeNames = themeNames,
                deckDir = deckDir,
                assetsDir = assetsDir,
            )
            totalBeats += r.stats.beatsAudited
            totalMs += r.stats.totalDurationMs
            for (issue in r.issues) {
                mergedIssues.putIfAbsent(issue.id, issue)
            }
        }
        return DocumentLintReport(
            issues = mergedIssues.values.toList(),
            stats = dev.sebastiano.ciacola.inspector.lint.DocumentDeckLintStats(
                slidesAudited = selectedIndices.size,
                beatsAudited = totalBeats,
                totalDurationMs = totalMs,
            ),
        )
    }

    private fun resolveDocumentSlideIndices(
        document: DeckDocument,
        slideRanges1Based: List<IntRange>,
    ): List<Int> {
        val nonSkippedDocIndices = document.slides.indices.filter { !document.slides[it].skipped }
        val selected = LinkedHashSet<Int>()
        for (range in slideRanges1Based) {
            for (oneBased in range) {
                val zeroBased = oneBased - 1
                require(zeroBased in nonSkippedDocIndices.indices) {
                    "Slide S$oneBased out of range for deck '${document.id}' (${nonSkippedDocIndices.size} non-skipped slides)"
                }
                selected += nonSkippedDocIndices[zeroBased]
            }
        }
        return selected.toList()
    }

    private fun emitLintDecodeFailure(
        options: CliOptions,
        deckId: String,
        deckFile: String,
        title: String,
        issues: List<DocumentIssue>,
    ): Int {
        if (options.jsonOutput) {
            val jsonReport = LintCliJsonReport(
                deckId = deckId,
                title = title,
                deckFile = deckFile,
                passed = false,
                errorCount = issues.size,
                warningCount = 0,
                slidesAudited = 0,
                beatsAudited = 0,
                totalDurationMs = 0.0,
                issues = issues.mapIndexed { idx, issue ->
                    LintCliJsonIssue(
                        id = "decode-$idx-${issue.code}",
                        code = issue.code,
                        severity = "error",
                        source = "Decode",
                        slideNumber = 0,
                        slideId = issue.address,
                        slideTitle = "",
                        beatIndex = 0,
                        nodeId = null,
                        themeName = null,
                        title = issue.code,
                        detail = issue.message,
                    )
                },
            )
            println(prettyJson.encodeToString(jsonReport))
        } else {
            System.err.println("Lint '$deckId' ($deckFile) decode failed with ${issues.size} error(s):")
            for (issue in issues) {
                System.err.println("  [${issue.severity}] ${issue.code} @ ${issue.address}: ${issue.message}")
            }
        }
        return 1
    }

    private fun emitCliError(options: CliOptions, code: String, message: String): Int {
        if (options.jsonOutput && options.mode == CliMode.Lint) {
            val jsonReport = LintCliJsonReport(
                deckId = options.deckArg,
                title = "",
                deckFile = options.deckArg,
                passed = false,
                errorCount = 1,
                warningCount = 0,
                slidesAudited = 0,
                beatsAudited = 0,
                totalDurationMs = 0.0,
                issues = listOf(
                    LintCliJsonIssue(
                        id = "cli-error",
                        code = code,
                        severity = "error",
                        source = "CLI",
                        slideNumber = 0,
                        slideId = "<root>",
                        slideTitle = "",
                        beatIndex = 0,
                        nodeId = null,
                        themeName = null,
                        title = code,
                        detail = message,
                    ),
                ),
            )
            println(prettyJson.encodeToString(jsonReport))
        } else {
            System.err.println("Error [$code]: $message")
        }
        return 1
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun runRenderSlides(options: CliOptions): Int {
        val resolved = runCatching { resolveAndDecodeDeck(options) }.getOrElse { err ->
            System.err.println("Error: ${err.message}")
            return 1
        }
        val doc = when (val dec = resolved.decodeResult) {
            is DeckDecodeResult.Success -> dec.document
            is DeckDecodeResult.ReadOnlyNewerFormat -> {
                System.err.println("Error: ${dec.message}")
                return 1
            }
            is DeckDecodeResult.Failure -> {
                dec.errors.forEach { System.err.println("  [${it.severity}] ${it.code} @ ${it.address}: ${it.message}") }
                return 1
            }
        }

        val nonSkipped = doc.slides.filterNot { it.skipped }
        if (nonSkipped.isEmpty()) {
            System.err.println("Deck '${doc.id}' has no non-skipped slides to render.")
            return 1
        }

        val selectedIndices0 = if (options.slideRanges1Based != null) {
            val set = LinkedHashSet<Int>()
            for (range in options.slideRanges1Based) {
                for (oneBased in range) {
                    val zeroBased = oneBased - 1
                    if (zeroBased !in nonSkipped.indices) {
                        System.err.println("Slide S$oneBased out of range for deck '${doc.id}' (${nonSkipped.size} non-skipped slides)")
                        return 1
                    }
                    set += zeroBased
                }
            }
            set.toList()
        } else {
            nonSkipped.indices.toList()
        }

        val outDir = options.outArg?.let { Path.of(it).toAbsolutePath().normalize() }
            ?: defaultRenderOutDir(doc.id)
        val themeName = if (options.theme != null) {
            runCatching { resolveValidatedTheme(doc, options.theme) }.getOrElse { err ->
                System.err.println("Error: ${err.message}")
                return 1
            }
        } else {
            DocumentThemeResolver.resolveActiveThemeName(doc)
        }
        val hasMatchingBeats = selectedIndices0.any { sceneIdx ->
            val beatCount = nonSkipped[sceneIdx].beats.size.coerceAtLeast(1)
            (0 until beatCount).any { options.beatFilter == null || it in options.beatFilter }
        }
        if (!hasMatchingBeats) {
            System.err.println(
                "Error: no beats matched --beats=${options.beatFilter?.sorted()?.joinToString(",") ?: ""} " +
                    "for selected slide(s) in '${doc.id}'.",
            )
            return 1
        }
        Files.createDirectories(outDir)
        val storyboard = buildHeadlessStoryboard(
            entry = resolved.entry,
            source = resolved.source,
            document = doc,
            themeName = themeName,
            forVideo = false,
            decksDir = resolved.decksDir,
        )

        val firstSlideIdx = selectedIndices0.first()
        val firstBeatIdx = (0 until nonSkipped[firstSlideIdx].beats.size.coerceAtLeast(1))
            .firstOrNull { options.beatFilter == null || it in options.beatFilter }
            ?: 0
        val initialIndex = Storyboard.Index(firstSlideIdx, firstBeatIdx)

        lateinit var animaticRef: Animatic
        lateinit var scopeRef: CoroutineScope
        val scene = ImageComposeScene(
            width = options.size.width,
            height = options.size.height,
            density = storyboard.format.density,
            coroutineContext = Dispatchers.Unconfined,
        ) {
            scopeRef = rememberCoroutineScope()
            val animatic = rememberAnimatic(initialIndex = initialIndex, storyboard = { storyboard })
            animaticRef = animatic
            Easel(animatic, mode = SceneMode.Preview)
        }

        val info = ImageInfo(options.size.width, options.size.height, ColorType.RGBA_8888, ColorAlphaType.UNPREMUL)
        val bitmap = Bitmap().apply { allocPixels(info) }
        val manifestSlides = mutableListOf<RenderManifestSlide>()
        val byKey = linkedMapOf<String, String>()
        var tick = 0L
        fun nextNanos(): Long = (tick++) * 16_000_000L

        try {
            scene.render(nextNanos()).close()
            var initialSettle = 0
            while (scene.hasInvalidations() && initialSettle < 3) {
                scene.render(nextNanos()).close()
                initialSettle++
            }

            for (sceneIdx in selectedIndices0) {
                val slide = nonSkipped[sceneIdx]
                val beatCount = slide.beats.size.coerceAtLeast(1)
                val beatEntries = mutableListOf<RenderManifestBeat>()
                for (beatIdx in 0 until beatCount) {
                    if (options.beatFilter != null && beatIdx !in options.beatFilter) continue
                    val target = Storyboard.Index(sceneIdx, beatIdx)
                    if (animaticRef.currentIndex != target) {
                        val jumpJob = scopeRef.launch { animaticRef.jumpTo(target) }
                        var pump = 0
                        while (!jumpJob.isCompleted && pump++ < 200) {
                            scene.render(nextNanos()).close()
                        }
                        check(jumpJob.isCompleted) {
                            "Timed out pumping jumpTo($target) after $pump frames"
                        }
                    }
                    scene.render(nextNanos()).close()
                    var settle = 0
                    while (scene.hasInvalidations() && settle < 3) {
                        scene.render(nextNanos()).close()
                        settle++
                    }
                    scene.render(nextNanos()).close()
                    val skiaImage = scene.render(nextNanos())
                    val pngBytes = try {
                        check(skiaImage.readPixels(bitmap, 0, 0)) {
                            "Failed to read pixels for slide ${slide.id} beat $beatIdx"
                        }
                        val rgba = bitmap.readPixels(info, options.size.width * 4, 0, 0)
                            ?: error("Failed to extract RGBA bytes for slide ${slide.id} beat $beatIdx")
                        SkiaImage.makeRaster(info, rgba, options.size.width * 4).use { raster ->
                            raster.encodeToData(EncodedImageFormat.PNG)?.bytes
                                ?: error("Failed to encode PNG for slide ${slide.id} beat $beatIdx")
                        }
                    } finally {
                        skiaImage.close()
                    }

                    val safeSlideId = slide.id.replace(Regex("""[^A-Za-z0-9._-]"""), "_")
                    val fileName = String.format(Locale.US, "%03d-%s-b%02d.png", sceneIdx + 1, safeSlideId, beatIdx)
                    val pngPath = outDir.resolve(fileName)
                    Files.write(pngPath, pngBytes)
                    val absPngPath = pngPath.toString()
                    beatEntries += RenderManifestBeat(
                        beat = beatIdx,
                        png = fileName,
                        path = absPngPath,
                    )
                    byKey["${slide.id}/$beatIdx"] = absPngPath
                }
                manifestSlides += RenderManifestSlide(
                    slideNumber = sceneIdx + 1,
                    slideId = slide.id,
                    title = slideDisplayTitle(slide, sceneIdx + 1),
                    beats = beatEntries,
                )
            }
        } finally {
            bitmap.close()
            scene.close()
        }

        if (byKey.isEmpty()) {
            System.err.println(
                "Error: no beats matched --beats=${options.beatFilter?.sorted()?.joinToString(",") ?: ""} " +
                    "for selected slide(s) in '${doc.id}'.",
            )
            return 1
        }

        val manifest = RenderManifest(
            deckId = doc.id,
            title = doc.title.ifBlank { resolved.entry.title },
            theme = themeName,
            width = options.size.width,
            height = options.size.height,
            slides = manifestSlides,
            byKey = byKey,
        )
        val manifestPath = outDir.resolve("manifest.json")
        Files.writeString(manifestPath, prettyJson.encodeToString(manifest) + "\n", StandardCharsets.UTF_8)
        println("Rendered ${byKey.size} PNG(s) for '${doc.id}' (${options.size.width}x${options.size.height}) -> $manifestPath")
        return 0
    }

    private fun runExportVideo(options: CliOptions): Int {
        val resolved = runCatching { resolveAndDecodeDeck(options) }.getOrElse { err ->
            System.err.println("Error: ${err.message}")
            return 1
        }
        val rawDoc = when (val dec = resolved.decodeResult) {
            is DeckDecodeResult.Success -> dec.document
            is DeckDecodeResult.ReadOnlyNewerFormat -> {
                System.err.println("Error: ${dec.message}")
                return 1
            }
            is DeckDecodeResult.Failure -> {
                dec.errors.forEach { System.err.println("  [${it.severity}] ${it.code} @ ${it.address}: ${it.message}") }
                return 1
            }
        }

        val prepared = runCatching {
            prepareExportDocument(rawDoc, options.slideRanges1Based)
        }.getOrElse { err ->
            System.err.println("Error: ${err.message}")
            return 1
        }
        if (prepared.slides.isEmpty()) {
            System.err.println("Deck '${rawDoc.id}' has no non-skipped slides to export.")
            return 1
        }

        val (mp4Path, spansTsvPath) = defaultVideoOutPaths(rawDoc.id, options.outArg)
        Files.createDirectories(mp4Path.parent)

        val themeName = if (options.theme != null) {
            runCatching { resolveValidatedTheme(prepared.document, options.theme) }.getOrElse { err ->
                System.err.println("Error: ${err.message}")
                return 1
            }
        } else {
            DocumentThemeResolver.resolveActiveThemeName(prepared.document)
        }
        val storyboard = buildHeadlessStoryboard(
            entry = resolved.entry,
            source = resolved.source,
            document = prepared.document,
            themeName = themeName,
            forVideo = true,
            decksDir = resolved.decksDir,
        )

        val recordedSpans = ArrayList<ExportedBeatSpan>(storyboard.indices.size)
        val startNanos = System.nanoTime()
        val exporter = StoryboardVideoExporter(
            fps = options.fps,
            holdDurationForState = { idx -> exportHoldDuration(prepared.document, idx) },
            outputSize = options.size,
            onBeatSpan = { span ->
                val slideInfo = prepared.slides.getOrNull(span.index.sceneIndex)
                val slideId = slideInfo?.slide?.id ?: "s${span.index.sceneIndex + 1}"
                val origNum = slideInfo?.originalSlideNumber1Based ?: (span.index.sceneIndex + 1)
                val slideTitle = slideInfo?.slide?.let { slideDisplayTitle(it, origNum) } ?: slideId
                recordedSpans += ExportedBeatSpan(
                    beatOrdinal = span.beatOrdinal,
                    originalSlideNumber1Based = origNum,
                    slideId = slideId,
                    slideTitle = slideTitle,
                    beatIndex = span.index.frameIndex,
                    span = span,
                )
            },
        )

        println(
            "Exporting '${rawDoc.id}' (${prepared.slides.size} slide(s), ${storyboard.indices.size} beat(s), " +
                "${options.size.width}x${options.size.height} @ ${options.fps}fps) -> $mp4Path",
        )
        runBlocking {
            exporter.exportAsMp4(storyboard, mp4Path)
        }
        writeExportSpansTsvInternal(spansTsvPath, recordedSpans)

        val totalFrames = recordedSpans.lastOrNull()?.span?.endFrameExclusive ?: 0
        val wallSec = (System.nanoTime() - startNanos) / 1_000_000_000.0
        val videoSec = totalFrames.toDouble() / options.fps
        println(
            String.format(
                Locale.US,
                "Exported %s (%d frames, %.2fs video in %.1fs wall) + %s",
                mp4Path,
                totalFrames,
                videoSec,
                wallSec,
                spansTsvPath,
            ),
        )
        return 0
    }

    private fun buildHeadlessStoryboard(
        entry: DeckEntry,
        source: DeckSource,
        document: DeckDocument,
        themeName: String,
        forVideo: Boolean,
        decksDir: Path?,
    ): Storyboard {
        val hostTheme = resolveDeckTheme(document, themeName)
        val materialColors = hostTheme.toMaterialColors()
        val materialTypography = hostTheme.toMaterialTypography()
        val isDark = !hostTheme.isLight

        val stageDecorator = ContentDecorator { inner ->
            DeckTextRendererProvider(DefaultMagicTextV2Renderer) {
                CiacolaJewelTheme(isDark = isDark) {
                    MaterialTheme(
                        colors = materialColors,
                        typography = materialTypography,
                    ) {
                        inner()
                    }
                }
            }
        }

        val assetsRootDir = when (source) {
            is DeckSource.File -> (source.assetsDir ?: source.file.parent)?.toString()
            is DeckSource.Resource -> entry.assetsResourceDir
        }
        val fontsDir = when (source) {
            is DeckSource.File -> source.file.parent?.toString()
            is DeckSource.Resource -> decksDir?.toString()
        }
        val session = if (forVideo) {
            RenderSession.forVideo(documentSnapshot = document)
        } else {
            RenderSession.forStill(documentSnapshot = document)
        }
        val config = DocumentRenderConfig(
            themeName = themeName,
            specimens = entry.specimens,
            reducedMotion = !forVideo,
            assetsRootDir = assetsRootDir,
            fontsDir = fontsDir,
            isEditMode = false,
            keyboardController = null,
            stageDecorator = stageDecorator,
        )
        return buildDocumentStoryboard(session = session, config = config)
    }

    internal data class IndexedSlide(
        val originalSlideNumber1Based: Int,
        val slide: SlideSpec,
    )

    internal data class PreparedExport(
        val document: DeckDocument,
        val slides: List<IndexedSlide>,
    )

    internal fun defaultRenderOutDir(deckId: String): Path {
        val safeId = sanitizeDeckId(deckId, fallback = "deck")
        return Path.of("build/ciacola-slides/$safeId").toAbsolutePath().normalize()
    }

    internal fun defaultVideoOutPaths(deckId: String, outArg: String?): Pair<Path, Path> {
        val safeId = sanitizeDeckId(deckId, fallback = "deck")
        val outArgPath = outArg?.let { Path.of(it).toAbsolutePath().normalize() }
        return if (outArgPath != null && outArgPath.fileName.toString().endsWith(".mp4", ignoreCase = true)) {
            val parent = outArgPath.parent ?: Path.of(".").toAbsolutePath().normalize()
            val stem = outArgPath.fileName.toString().replace(Mp4ExtensionRegex, "")
            outArgPath to parent.resolve("$stem.spans.tsv")
        } else {
            val dir = outArgPath ?: Path.of("build/ciacola-video/$safeId").toAbsolutePath().normalize()
            dir.resolve("$safeId.mp4") to dir.resolve("$safeId.spans.tsv")
        }
    }

    internal fun prepareExportDocument(
        source: DeckDocument,
        slideRanges1Based: List<IntRange>?,
    ): PreparedExport = prepareExportDocumentInternal(source, slideRanges1Based)

    internal fun exportHoldDuration(
        document: DeckDocument,
        index: Storyboard.Index,
        intraSlideHoldMs: Long = 1_000L,
        slideChangeHoldMs: Long = 2_000L,
    ): Duration = exportHoldDurationInternal(document, index, intraSlideHoldMs, slideChangeHoldMs)

    private fun printUsage() {
        println(
            """
            Usage: DeckAgentCli <--lint | --render-slides | --export-video> --deck=<id|path> [options]

            Options:
              --deck=<id|path>       Deck ID (e.g. foundation) or path to a .deck.json file
              --dir=<decksDir>       Override directory scanned for *.deck.json files
              --slides=<1,3-5>       1-based non-skipped slide numbers / ranges
              --beats=<0,1>          0-based beat indices filter (--render-slides only)
              --out=<dir|file>       Output directory (or .mp4 path for --export-video)
              --fps=<30|60>          Frame rate for --export-video (default: 30)
              --size=<WxH>           Pixel resolution (default: 1280x720)
              --theme=<name>         Theme override (e.g. dark or light)
              --json                 Machine-readable JSON report (--lint only)
              --fail-on=<error|warning>  Exit non-zero threshold for --lint (default: error)
            """.trimIndent(),
        )
    }
}
