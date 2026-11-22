package dev.sebastiano.ciacola.decks

import dev.bnorm.storyboard.Storyboard
import dev.bnorm.storyboard.easel.export.BeatExportSpan
import dev.sebastiano.ciacola.document.DeckDocument
import dev.sebastiano.ciacola.document.SlideSpec
import dev.sebastiano.ciacola.document.TransitionSpec
import dev.sebastiano.ciacola.document.TransitionType
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.serialization.Serializable

internal data class ExportedBeatSpan(
    val beatOrdinal: Int,
    val originalSlideNumber1Based: Int,
    val slideId: String,
    val slideTitle: String,
    val beatIndex: Int,
    val span: BeatExportSpan,
)

internal fun prepareExportDocumentInternal(
    source: DeckDocument,
    slideRanges1Based: List<IntRange>?,
): DeckAgentCli.PreparedExport {
    val nonSkipped = source.slides.filterNot { it.skipped }
    if (slideRanges1Based == null) {
        return DeckAgentCli.PreparedExport(
            document = source.copy(slides = nonSkipped),
            slides = nonSkipped.mapIndexed { idx, slide -> DeckAgentCli.IndexedSlide(idx + 1, slide) },
        )
    }
    val selectedIndices = LinkedHashSet<Int>()
    for (range in slideRanges1Based) {
        for (oneBased in range) {
            val zeroBased = oneBased - 1
            require(zeroBased in nonSkipped.indices) {
                "Slide S$oneBased out of range for deck '${source.id}' (${nonSkipped.size} non-skipped slides)"
            }
            selectedIndices += zeroBased
        }
    }
    val ordered = selectedIndices.toList()
    val patchedSlides = ArrayList<SlideSpec>(ordered.size)
    val indexedSlides = ArrayList<DeckAgentCli.IndexedSlide>(ordered.size)
    for (pos in ordered.indices) {
        val origIdx = ordered[pos]
        val slide = nonSkipped[origIdx]
        val nextOrigIdx = ordered.getOrNull(pos + 1)
        val isNonContiguousCut = nextOrigIdx != null && nextOrigIdx != origIdx + 1
        val adjusted = if (isNonContiguousCut) {
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
        indexedSlides += DeckAgentCli.IndexedSlide(origIdx + 1, adjusted)
    }
    return DeckAgentCli.PreparedExport(
        document = source.copy(slides = patchedSlides),
        slides = indexedSlides,
    )
}

internal fun exportHoldDurationInternal(
    document: DeckDocument,
    index: Storyboard.Index,
    intraSlideHoldMs: Long = 1_000L,
    slideChangeHoldMs: Long = 2_000L,
): Duration {
    val slide = document.slides.getOrNull(index.sceneIndex) ?: return slideChangeHoldMs.milliseconds
    val beat = slide.beats.getOrNull(index.frameIndex)
    beat?.autoAdvanceMs?.let { return it.toLong().coerceAtLeast(0L).milliseconds }
    val lastBeatIdx = slide.beats.lastIndex.coerceAtLeast(0)
    return if (index.frameIndex >= lastBeatIdx) {
        slideChangeHoldMs.milliseconds
    } else {
        intraSlideHoldMs.milliseconds
    }
}

internal fun writeExportSpansTsvInternal(path: Path, spans: List<ExportedBeatSpan>) {
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

@Serializable
internal data class LintCliJsonIssue(
    val id: String,
    val code: String,
    val severity: String,
    val source: String,
    val slideNumber: Int,
    val slideId: String,
    val slideTitle: String,
    val beatIndex: Int,
    val nodeId: String?,
    val themeName: String?,
    val title: String,
    val detail: String,
)

@Serializable
internal data class LintCliJsonReport(
    val deckId: String,
    val title: String,
    val deckFile: String,
    val passed: Boolean,
    val errorCount: Int,
    val warningCount: Int,
    val slidesAudited: Int,
    val beatsAudited: Int,
    val totalDurationMs: Double,
    val issues: List<LintCliJsonIssue>,
)

@Serializable
internal data class RenderManifestBeat(
    val beat: Int,
    val png: String,
    val path: String,
)

@Serializable
internal data class RenderManifestSlide(
    val slideNumber: Int,
    val slideId: String,
    val title: String,
    val beats: List<RenderManifestBeat>,
)

@Serializable
internal data class RenderManifest(
    val deckId: String,
    val title: String,
    val theme: String,
    val width: Int,
    val height: Int,
    val slides: List<RenderManifestSlide>,
    val byKey: Map<String, String>,
)
