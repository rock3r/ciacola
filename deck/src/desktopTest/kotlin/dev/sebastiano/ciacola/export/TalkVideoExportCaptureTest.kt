package dev.sebastiano.ciacola.export

import dev.bnorm.storyboard.Storyboard
import dev.sebastiano.ciacola.DeckCatalog
import dev.sebastiano.ciacola.decks.loadClasspathDeckDocument
import dev.sebastiano.ciacola.document.TransitionType
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue

class TalkVideoExportCaptureTest {

    @Test
    fun highlightSlicersMatchExpectedSlideCountsAndPreserveContiguousTransitions() {
        val shadersDoc = loadClasspathDeckDocument(DeckCatalog.shadersQuickie2025)
        val shadersSlice = TalkVideoExportHarness.prepareDocument(
            shadersDoc,
            TalkVideoExportCatalog.shadersHighlights.slideRanges1Based,
        )
        assertEquals(30, shadersSlice.slides.size)
        assertEquals(58, shadersSlice.slides.sumOf { it.slide.beats.size.coerceAtLeast(1) })
        // S01->S03 (pos 0), S09->S22 (pos 7), S27->S33 (pos 13) are non-contiguous chapter cuts.
        assertEquals(setOf(0, 7, 13), shadersSlice.chapterCutIndices)
        assertEquals(TransitionType.FadeThroughColor, shadersSlice.document.slides[0].transition?.type)
        // S03->S04 (pos 1) stays MagicMove!
        assertEquals(TransitionType.MagicMove, shadersSlice.document.slides[1].transition?.type)

        val jewelDoc = loadClasspathDeckDocument(DeckCatalog.jewelTalk2024)
        val jewelSlice = TalkVideoExportHarness.prepareDocument(
            jewelDoc,
            TalkVideoExportCatalog.jewelHighlights.slideRanges1Based,
        )
        assertEquals(41, jewelSlice.slides.size)
        assertEquals(71, jewelSlice.slides.sumOf { it.slide.beats.size.coerceAtLeast(1) })
        // S51->S52 is contiguous so S41..S64 has zero internal chapter cuts.
        assertEquals(setOf(2, 3, 7, 14, 15, 39), jewelSlice.chapterCutIndices)

        val njptDoc = loadClasspathDeckDocument(DeckCatalog.notJustPlainText2024)
        val njptSlice = TalkVideoExportHarness.prepareDocument(
            njptDoc,
            TalkVideoExportCatalog.njptHighlights.slideRanges1Based,
        )
        assertEquals(57, njptSlice.slides.size)
        assertEquals(105, njptSlice.slides.sumOf { it.slide.beats.size.coerceAtLeast(1) })
        assertEquals(setOf(9, 54, 55), njptSlice.chapterCutIndices)
    }

    @Test
    fun keynoteHoldDurationHonorsAutoAdvanceIntraBuildAndSlideChangePacing() {
        val shadersDoc = loadClasspathDeckDocument(DeckCatalog.shadersQuickie2025)
        val nonSkipped = shadersDoc.slides.filterNot { it.skipped }

        // S01 has 2 beats with no autoAdvanceMs: beat 0 -> 1s, beat 1 (slide end) -> 2s.
        assertEquals(
            1_000.milliseconds,
            TalkVideoExportHarness.keynoteHoldDuration(shadersDoc, Storyboard.Index(0, 0)),
        )
        assertEquals(
            2_000.milliseconds,
            TalkVideoExportHarness.keynoteHoldDuration(shadersDoc, Storyboard.Index(0, 1)),
        )

        // S22 beat 1 has authored autoAdvanceMs = 100ms -> honored over 2s slide hold.
        val s22Idx = nonSkipped.indexOfFirst { it.id == "s22" }
        assertTrue(s22Idx >= 0)
        assertEquals(
            100.milliseconds,
            TalkVideoExportHarness.keynoteHoldDuration(shadersDoc, Storyboard.Index(s22Idx, 1)),
        )

        // Live showcase override applies to single-beat S05 when provided.
        val s05Idx = nonSkipped.indexOfFirst { it.id == "s05" }
        assertEquals(
            2_500.milliseconds,
            TalkVideoExportHarness.keynoteHoldDuration(
                document = shadersDoc,
                index = Storyboard.Index(s05Idx, 0),
                liveShowcaseHoldOverridesMs = mapOf("s05" to 2_500L),
            ),
        )
    }

    @Test
    fun runRequestedHeadlessTalkExportJob() {
        val jobId = System.getProperty("ciacola.videoExportJob")?.trim()?.takeIf { it.isNotEmpty() }
        assumeTrue("Set -Dciacola.videoExportJob=<id> to run headless MP4 export", jobId != null)
        val outDir = Path.of(
            System.getProperty("ciacola.videoExportOutDir")?.takeIf { it.isNotBlank() }
                ?: "build/exported-videos",
        )
        val spansDir = System.getProperty("ciacola.videoExportSpansDir")
            ?.takeIf { it.isNotBlank() }
            ?.let(Path::of)
            ?: outDir
        val job = TalkVideoExportCatalog.byId(jobId!!)
        val result = runBlocking {
            TalkVideoExportHarness.exportJob(
                job = job,
                outputDir = outDir,
                spansDir = spansDir,
                logProgress = true,
            )
        }
        assertTrue(result.totalFrames > 0, "Expected non-empty exported video")
    }
}
