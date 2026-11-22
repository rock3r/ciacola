package dev.sebastiano.ciacola.export

import androidx.compose.material.MaterialTheme
import androidx.compose.ui.unit.IntSize
import dev.bnorm.storyboard.ContentDecorator
import dev.bnorm.storyboard.Storyboard
import dev.bnorm.storyboard.easel.export.BeatExportSpan
import dev.bnorm.storyboard.easel.export.StoryboardVideoExporter
import dev.sebastiano.ciacola.DeckCatalog
import dev.sebastiano.ciacola.DeckEntry
import dev.sebastiano.ciacola.DeckTextRendererProvider
import dev.sebastiano.ciacola.decks.loadClasspathDeckDocument
import dev.sebastiano.ciacola.document.DeckDocument
import dev.sebastiano.ciacola.document.RenderSession
import dev.sebastiano.ciacola.document.SlideSpec
import dev.sebastiano.ciacola.document.TransitionSpec
import dev.sebastiano.ciacola.document.TransitionType
import dev.sebastiano.ciacola.document.render.DefaultMagicTextV2Renderer
import dev.sebastiano.ciacola.document.render.DocumentRenderConfig
import dev.sebastiano.ciacola.document.render.buildDocumentStoryboard
import dev.sebastiano.ciacola.document.render.resolveDeckTheme
import dev.sebastiano.ciacola.ui.CiacolaJewelTheme
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.runBlocking

/**
 * Specification for a headless deterministic MP4 talk export job using [StoryboardVideoExporter]
 * and [RenderSession.forVideo] (`RenderPurpose.Video`).
 */
data class TalkVideoExportJob(
    val id: String,
    val deckId: String,
    val outputFileName: String,
    val outputSize: IntSize,
    val fps: Int,
    /** 1-based inclusive slide ranges to include, or `null` for the complete non-skipped deck. */
    val slideRanges1Based: List<IntRange>? = null,
    val intraSlideHoldMs: Long = 1_000L,
    val slideChangeHoldMs: Long = 2_000L,
    /** Optional per-slide-ID hold override (ms) for single-beat live shader/UI showcase slides. */
    val liveShowcaseHoldOverridesMs: Map<String, Long> = emptyMap(),
)

/** A single selected slide alongside its 1-based position in the source talk. */
data class IndexedExportSlide(
    val originalSlideNumber1Based: Int,
    val slide: SlideSpec,
)

/** Result of slicing a [DeckDocument] for full or highlights export. */
data class PreparedExportDocument(
    val document: DeckDocument,
    val slides: List<IndexedExportSlide>,
    val chapterCutIndices: Set<Int>,
)

/** Completed export summary plus per-beat frame-accurate timestamps. */
data class TalkVideoExportResult(
    val job: TalkVideoExportJob,
    val mp4Path: Path,
    val spansTsvPath: Path,
    val totalFrames: Int,
    val spans: List<RecordedBeatSpan>,
)

data class RecordedBeatSpan(
    val beatOrdinal: Int,
    val originalSlideNumber1Based: Int,
    val slideId: String,
    val slideTitle: String,
    val beatIndex: Int,
    val span: BeatExportSpan,
)

/**
 * Standard headless talk export catalog covering full 720p30 end-to-end exports and 1080p60
 * highlight reels for all three replicated talks.
 */
object TalkVideoExportCatalog {
    val shadersFull = TalkVideoExportJob(
        id = "shaders-full",
        deckId = "shaders-quickie-2025",
        outputFileName = "shaders-quickie-2025-full.mp4",
        outputSize = IntSize(1280, 720),
        fps = 30,
        slideRanges1Based = null,
    )

    val shadersHighlights = TalkVideoExportJob(
        id = "shaders-highlights",
        deckId = "shaders-quickie-2025",
        outputFileName = "shaders-quickie-2025-highlights-60fps.mp4",
        outputSize = IntSize(1920, 1080),
        fps = 60,
        slideRanges1Based = listOf(
            1..1,
            3..4,
            5..9,
            22..27,
            33..44,
            45..48,
        ),
        liveShowcaseHoldOverridesMs = mapOf(
            "s05" to 2_500L,
            "s06" to 2_500L,
            "s07" to 2_500L,
            "s08" to 2_500L,
            "s09" to 2_500L,
            "s46" to 2_500L,
        ),
    )

    val jewelFull = TalkVideoExportJob(
        id = "jewel-full",
        deckId = "jewel-talk-2024",
        outputFileName = "jewel-talk-2024-full.mp4",
        outputSize = IntSize(1280, 720),
        fps = 30,
        slideRanges1Based = null,
    )

    val jewelHighlights = TalkVideoExportJob(
        id = "jewel-highlights",
        deckId = "jewel-talk-2024",
        outputFileName = "jewel-talk-2024-highlights-60fps.mp4",
        outputSize = IntSize(1920, 1080),
        fps = 60,
        slideRanges1Based = listOf(
            1..3,
            14..14,
            16..19,
            25..31,
            36..36,
            41..51,
            52..64,
            75..75,
        ),
        liveShowcaseHoldOverridesMs = mapOf(
            "s36" to 2_500L,
            "s75" to 2_500L,
        ),
    )

    val njptFull = TalkVideoExportJob(
        id = "njpt-full",
        deckId = "not-just-plain-text-2024",
        outputFileName = "not-just-plain-text-2024-full.mp4",
        outputSize = IntSize(1280, 720),
        fps = 30,
        slideRanges1Based = null,
    )

    val njptHighlights = TalkVideoExportJob(
        id = "njpt-highlights",
        deckId = "not-just-plain-text-2024",
        outputFileName = "not-just-plain-text-2024-highlights-60fps.mp4",
        outputSize = IntSize(1920, 1080),
        fps = 60,
        slideRanges1Based = listOf(
            1..10,
            46..90,
            125..125,
            128..128,
        ),
        liveShowcaseHoldOverridesMs = mapOf(
            "s125" to 3_500L,
            "s128" to 3_500L,
        ),
    )

    /** Fast 6-slide visual check of 720p SkSL shaders (S01 + S05..S09). */
    val shaders720pCheck = TalkVideoExportJob(
        id = "shaders-720p-check",
        deckId = "shaders-quickie-2025",
        outputFileName = "shaders-720p-check.mp4",
        outputSize = IntSize(1280, 720),
        fps = 30,
        slideRanges1Based = listOf(1..1, 5..9),
        intraSlideHoldMs = 500L,
        slideChangeHoldMs = 1_000L,
    )

    val productionJobs: List<TalkVideoExportJob> = listOf(
        shadersFull,
        shadersHighlights,
        jewelFull,
        jewelHighlights,
        njptFull,
        njptHighlights,
    )

    val allJobs: List<TalkVideoExportJob> = productionJobs + shaders720pCheck

    fun byId(id: String): TalkVideoExportJob =
        allJobs.firstOrNull { it.id == id }
            ?: error("Unknown talk video export job '$id'. Available: ${allJobs.joinToString { it.id }}")
}

object TalkVideoExportHarness {

    /**
     * Prepares a [DeckDocument] for full or sliced highlight export:
     * - Filters out skipped slides.
     * - Preserves exact authored transitions on all contiguous slide pairs.
     * - Injects a crisp 300ms `fadeThroughColor("#000000")` chapter cut strictly at non-contiguous
     *   boundaries (never attempting Magic Move across skipped sections) and ensures a non-contiguous
     *   departing slide doesn't have an authored `autoAdvanceMs == 0` flash on its final beat.
     */
    fun prepareDocument(
        source: DeckDocument,
        slideRanges1Based: List<IntRange>?,
    ): PreparedExportDocument {
        val nonSkipped = source.slides.filterNot { it.skipped }
        if (slideRanges1Based == null) {
            val indexed = nonSkipped.mapIndexed { idx, slide ->
                IndexedExportSlide(originalSlideNumber1Based = idx + 1, slide = slide)
            }
            return PreparedExportDocument(
                document = source.copy(slides = nonSkipped),
                slides = indexed,
                chapterCutIndices = emptySet(),
            )
        }

        val selectedIndices0 = LinkedHashSet<Int>()
        for (range in slideRanges1Based) {
            for (oneBased in range) {
                val zeroBased = oneBased - 1
                require(zeroBased in nonSkipped.indices) {
                    "Slide S$oneBased out of range for deck '${source.id}' (${nonSkipped.size} non-skipped slides)"
                }
                selectedIndices0 += zeroBased
            }
        }
        val ordered = selectedIndices0.toList()
        val chapterCuts = LinkedHashSet<Int>()
        val patchedSlides = ArrayList<SlideSpec>(ordered.size)
        val indexedSlides = ArrayList<IndexedExportSlide>(ordered.size)

        for (pos in ordered.indices) {
            val origIdx = ordered[pos]
            val slide = nonSkipped[origIdx]
            val nextOrigIdx = ordered.getOrNull(pos + 1)
            val isNonContiguousCut = nextOrigIdx != null && nextOrigIdx != origIdx + 1
            val adjusted = if (isNonContiguousCut) {
                chapterCuts += pos
                val adjustedBeats = if (slide.beats.isNotEmpty() && slide.beats.last().autoAdvanceMs == 0) {
                    slide.beats.dropLast(1) + slide.beats.last().copy(autoAdvanceMs = null)
                } else {
                    slide.beats
                }
                slide.copy(
                    beats = adjustedBeats,
                    transition = TransitionSpec(
                        type = TransitionType.FadeThroughColor,
                        color = "#000000",
                        durationMs = 300,
                    ),
                )
            } else {
                slide
            }
            patchedSlides += adjusted
            indexedSlides += IndexedExportSlide(
                originalSlideNumber1Based = origIdx + 1,
                slide = adjusted,
            )
        }

        return PreparedExportDocument(
            document = source.copy(slides = patchedSlides),
            slides = indexedSlides,
            chapterCutIndices = chapterCuts,
        )
    }

    /**
     * Keynote self-playing `.mov` hold-duration policy:
     * 1. Honor explicit `beat.autoAdvanceMs` wherever authored in the KPF/document.
     * 2. Honor any per-slide live-showcase override on the slide's final beat.
     * 3. Otherwise hold [slideChangeHoldMs] (`2s`) on slide changes (final beat of a slide) and
     *    [intraSlideHoldMs] (`1s`) after intra-slide builds.
     */
    fun keynoteHoldDuration(
        document: DeckDocument,
        index: Storyboard.Index,
        intraSlideHoldMs: Long = 1_000L,
        slideChangeHoldMs: Long = 2_000L,
        liveShowcaseHoldOverridesMs: Map<String, Long> = emptyMap(),
    ): Duration {
        val nonSkipped = document.slides.filterNot { it.skipped }
        val slide = nonSkipped.getOrNull(index.sceneIndex) ?: return slideChangeHoldMs.milliseconds
        val beat = slide.beats.getOrNull(index.frameIndex)
        val authoredAutoMs = beat?.autoAdvanceMs?.toLong()
        if (authoredAutoMs != null) {
            return authoredAutoMs.coerceAtLeast(0L).milliseconds
        }
        val lastBeatIdx = slide.beats.lastIndex.coerceAtLeast(0)
        val isSlideEnd = index.frameIndex >= lastBeatIdx
        if (isSlideEnd) {
            val overrideMs = liveShowcaseHoldOverridesMs[slide.id]
            if (overrideMs != null) return overrideMs.coerceAtLeast(0L).milliseconds
            return slideChangeHoldMs.milliseconds
        }
        return intraSlideHoldMs.milliseconds
    }

    /**
     * Builds the offscreen [Storyboard] wrapped in `DeckTextRendererProvider`, `CiacolaJewelTheme`,
     * and `MaterialTheme` so headless `ImageComposeScene` renders identically to `Main.kt`.
     */
    fun buildHeadlessVideoStoryboard(
        entry: DeckEntry,
        document: DeckDocument,
    ): Storyboard {
        val themeName = document.defaultTheme
        val hostTheme = resolveDeckTheme(document, themeName)
        val materialColors = hostTheme.toMaterialColors()
        val materialTypography = hostTheme.toMaterialTypography()
        val isDark = !hostTheme.isLight

        val exportStageDecorator = ContentDecorator { inner ->
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

        val session = RenderSession.forVideo(documentSnapshot = document)
        val config = DocumentRenderConfig(
            themeName = themeName,
            specimens = entry.specimens,
            reducedMotion = false,
            assetsRootDir = entry.assetsResourceDir,
            isEditMode = false,
            keyboardController = null,
            stageDecorator = exportStageDecorator,
        )
        return buildDocumentStoryboard(session = session, config = config)
    }

    /** Runs a single [TalkVideoExportJob] headlessly and writes both `.mp4` and `.spans.tsv`. */
    suspend fun exportJob(
        job: TalkVideoExportJob,
        outputDir: Path,
        spansDir: Path = outputDir,
        logProgress: Boolean = true,
    ): TalkVideoExportResult {
        Files.createDirectories(outputDir)
        Files.createDirectories(spansDir)

        val entry = DeckCatalog.byId(job.deckId)
        val rawDoc = loadClasspathDeckDocument(entry)
        val prepared = prepareDocument(rawDoc, job.slideRanges1Based)
        val storyboard = buildHeadlessVideoStoryboard(entry, prepared.document)

        val recordedSpans = ArrayList<RecordedBeatSpan>(storyboard.indices.size)
        val startWallNanos = System.nanoTime()
        val exporter = StoryboardVideoExporter(
            fps = job.fps,
            holdDurationForState = { index ->
                keynoteHoldDuration(
                    document = prepared.document,
                    index = index,
                    intraSlideHoldMs = job.intraSlideHoldMs,
                    slideChangeHoldMs = job.slideChangeHoldMs,
                    liveShowcaseHoldOverridesMs = job.liveShowcaseHoldOverridesMs,
                )
            },
            outputSize = job.outputSize,
            onBeatSpan = { span ->
                val slideInfo = prepared.slides.getOrNull(span.index.sceneIndex)
                val slideId = slideInfo?.slide?.id ?: "s${span.index.sceneIndex + 1}"
                val slideTitle = slideInfo?.slide?.title?.takeIf { it.isNotBlank() } ?: slideId
                val origNum = slideInfo?.originalSlideNumber1Based ?: (span.index.sceneIndex + 1)
                recordedSpans += RecordedBeatSpan(
                    beatOrdinal = span.beatOrdinal,
                    originalSlideNumber1Based = origNum,
                    slideId = slideId,
                    slideTitle = slideTitle,
                    beatIndex = span.index.frameIndex,
                    span = span,
                )
                if (logProgress && (span.beatOrdinal % 10 == 0 || span.beatOrdinal == storyboard.indices.lastIndex)) {
                    val elapsedSec = (System.nanoTime() - startWallNanos) / 1_000_000_000.0
                    println(
                        String.format(
                            Locale.US,
                            "[%s] beat %d/%d (S%03d:%d %s) -> frame %d (%.2fs video, %.1fs wall)",
                            job.id,
                            span.beatOrdinal + 1,
                            storyboard.indices.size,
                            origNum,
                            span.index.frameIndex,
                            slideId,
                            span.endFrameExclusive,
                            span.endSeconds,
                            elapsedSec,
                        ),
                    )
                }
            },
        )

        val mp4Path = outputDir.resolve(job.outputFileName)
        if (logProgress) {
            println(
                "[${job.id}] Starting headless export -> $mp4Path " +
                    "(${job.outputSize.width}x${job.outputSize.height} @ ${job.fps}fps, " +
                    "${prepared.slides.size} slides / ${storyboard.indices.size} beats)",
            )
        }
        exporter.exportAsMp4(storyboard, mp4Path)

        val totalFrames = recordedSpans.lastOrNull()?.span?.endFrameExclusive ?: 0
        val spansTsvPath = spansDir.resolve(job.outputFileName.removeSuffix(".mp4") + ".spans.tsv")
        writeSpansTsv(spansTsvPath, recordedSpans)

        if (logProgress) {
            val elapsedSec = (System.nanoTime() - startWallNanos) / 1_000_000_000.0
            val videoSec = totalFrames.toDouble() / job.fps
            val sizeMb = Files.size(mp4Path) / (1024.0 * 1024.0)
            println(
                String.format(
                    Locale.US,
                    "[%s] DONE: %s (%.2f MB, %d frames = %.2fs @ %dfps, took %.1fs wall)",
                    job.id,
                    mp4Path.fileName,
                    sizeMb,
                    totalFrames,
                    videoSec,
                    job.fps,
                    elapsedSec,
                ),
            )
        }

        return TalkVideoExportResult(
            job = job,
            mp4Path = mp4Path,
            spansTsvPath = spansTsvPath,
            totalFrames = totalFrames,
            spans = recordedSpans,
        )
    }

    private fun writeSpansTsv(path: Path, spans: List<RecordedBeatSpan>) {
        val sb = StringBuilder()
        sb.appendLine(
            "beatOrdinal\toriginalSlideNumber\tslideId\tslideTitle\tbeatIndex\t" +
                "transitionStartSec\tholdStartSec\tendSec\ttransitionStartFrame\tholdStartFrame\tendFrameExclusive",
        )
        for (rec in spans) {
            val cleanTitle = rec.slideTitle.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')
            sb.appendLine(
                String.format(
                    Locale.US,
                    "%d\t%d\t%s\t%s\t%d\t%.3f\t%.3f\t%.3f\t%d\t%d\t%d",
                    rec.beatOrdinal,
                    rec.originalSlideNumber1Based,
                    rec.slideId,
                    cleanTitle,
                    rec.beatIndex,
                    rec.span.transitionStartSeconds,
                    rec.span.holdStartSeconds,
                    rec.span.endSeconds,
                    rec.span.transitionStartFrame,
                    rec.span.holdStartFrame,
                    rec.span.endFrameExclusive,
                ),
            )
        }
        Files.writeString(path, sb.toString(), StandardCharsets.UTF_8)
    }
}

/**
 * Standalone headless CLI entrypoint so multiple talk exports can run in parallel across JVMs with
 * `-Dapple.awt.UIElement=true` (zero windows on screen).
 */
fun main(args: Array<String>) {
    var jobId: String? = null
    var outDir: Path? = null
    var spansDir: Path? = null

    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--job" -> jobId = args.getOrNull(++i)
            "--outDir" -> outDir = args.getOrNull(++i)?.let(Path::of)
            "--spansDir" -> spansDir = args.getOrNull(++i)?.let(Path::of)
            else -> error("Unknown argument: ${args[i]}")
        }
        i++
    }

    val resolvedJobId = requireNotNull(jobId) { "Missing --job <jobId>" }
    val resolvedOutDir = requireNotNull(outDir) { "Missing --outDir <path>" }
    val resolvedSpansDir = spansDir ?: resolvedOutDir

    val job = TalkVideoExportCatalog.byId(resolvedJobId)
    runBlocking {
        TalkVideoExportHarness.exportJob(
            job = job,
            outputDir = resolvedOutDir,
            spansDir = resolvedSpansDir,
            logProgress = true,
        )
    }
}
