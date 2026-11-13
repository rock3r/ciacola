package dev.bnorm.storyboard.easel.export

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.bnorm.storyboard.SceneFormat
import dev.bnorm.storyboard.Storyboard
import java.nio.file.Files
import kotlin.coroutines.cancellation.CancellationException
import kotlin.io.path.createFile
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.pdfbox.text.TextPosition

@OptIn(kotlin.io.path.ExperimentalPathApi::class)
class StoryboardPdfExporterTest {

    private val deck = Storyboard.build(title = "ExportDeck") {
        scene(frames = listOf(0, 1, 2)) { Text("Scene 0") }
        scene(frames = listOf(0, 1)) { Text("Scene 1") }
    }

    @Test
    fun writeFailureSurfacesAsFailedStatusInsteadOfThrowing() = runTest {
        val dir = Files.createTempDirectory("pdf-export-fail")
        try {
            // The parent of the target is a regular file, so writing the PDF must fail with an IOException.
            val notADirectory = dir.resolve("blocker").createFile()
            val exporter = StoryboardPdfExporter()
            exporter.exportAsPdf(deck, notADirectory.resolve("out.pdf"))
            val status = exporter.status
            assertIs<ExportStatus.Failed>(status, "A write error must be reported as a visible failure, got $status")
            assertTrue(status.message.isNotBlank())
            assertFalse(exporter.isExporting)
            exporter.dismissFailure()
            assertNull(exporter.status)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun secondExportWhileOneIsRunningIsIgnored() = runTest {
        val dir = Files.createTempDirectory("pdf-export-reentry")
        try {
            val first = dir.resolve("first.pdf")
            val second = dir.resolve("second.pdf")
            val exporter = StoryboardPdfExporter()
            val running = launch { exporter.exportAsPdf(deck, first) }
            yield() // let the first export start and suspend between pages
            assertTrue(exporter.isExporting, "First export is running")
            exporter.exportAsPdf(deck, second)
            running.join()
            assertTrue(first.exists() && first.fileSize() > 0, "First export wrote its PDF")
            assertFalse(second.exists(), "A second export started mid-export must be ignored")
            assertNull(exporter.status, "Status clears after a successful export")
            assertFalse(exporter.isExporting)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun exportedPdfContainsSearchableUnicodeTextInReadingOrderAndSkipsInvisibleNodes() = runTest {
        val richDeck = Storyboard.build(
            title = "RichTextDeck",
            format = SceneFormat(IntSize(960, 540), Density(1f)),
        ) {
            scene(frames = listOf(0)) {
                Column(Modifier.fillMaxSize().padding(32.dp)) {
                    Text(
                        text = "Headline — Architecture Overview",
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(16.dp))
                    // Tokenized prose row (similar to MagicText word layout).
                    Row {
                        Text("Transitions", fontSize = 18.sp)
                        Text(" ", fontSize = 18.sp)
                        Text("flow", fontSize = 18.sp)
                        Text(" ", fontSize = 18.sp)
                        Text("from", fontSize = 18.sp)
                        Text(" ", fontSize = 18.sp)
                        Text("A → B…", fontSize = 18.sp)
                    }
                    Spacer(Modifier.height(16.dp))
                    // Syntax-highlighted Kotlin code tokens.
                    val highlightedCode = buildAnnotatedString {
                        withStyle(SpanStyle(color = Color.Magenta, fontWeight = FontWeight.SemiBold)) {
                            append("fun")
                        }
                        append(" ")
                        withStyle(SpanStyle(color = Color.Blue)) {
                            append("provisional")
                        }
                        append("(): String = ")
                        withStyle(SpanStyle(color = Color.Green)) {
                            append("\"hello\"")
                        }
                    }
                    Text(
                        text = highlightedCode,
                        fontSize = 16.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
            scene(frames = listOf(0)) {
                Column(Modifier.fillMaxSize().padding(32.dp)) {
                    Text("Visible Slide Two", fontSize = 24.sp)
                    Box(Modifier.alpha(0f)) {
                        Text("Hidden Alpha Zero Text", fontSize = 18.sp)
                    }
                }
            }
        }

        val dir = Files.createTempDirectory("pdf-export-text-layer")
        try {
            val pdfPath = dir.resolve("rich.pdf")
            val exporter = StoryboardPdfExporter()
            exporter.exportAsPdf(richDeck, pdfPath)

            assertNull(exporter.status)
            assertTrue(pdfPath.exists() && pdfPath.fileSize() > 0)

            Loader.loadPDF(pdfPath.toFile()).use { doc ->
                assertEquals(2, doc.numberOfPages)

                val page1Stripper = RecordingTextStripper().apply {
                    startPage = 1
                    endPage = 1
                }
                val page1Text = page1Stripper.getText(doc)
                assertTrue(page1Text.contains("Headline — Architecture Overview"), "Missing headline in:\n$page1Text")
                assertTrue(page1Text.contains("Transitions flow from A → B…"), "Missing tokenized Unicode prose in:\n$page1Text")
                assertTrue(
                    page1Text.contains("""fun provisional(): String = "hello""""),
                    "Missing syntax-highlighted Kotlin code in:\n$page1Text",
                )

                val headlineIdx = page1Text.indexOf("Headline")
                val proseIdx = page1Text.indexOf("Transitions")
                val codeIdx = page1Text.indexOf("fun provisional")
                assertTrue(headlineIdx in 0 until proseIdx, "Headline should precede prose:\n$page1Text")
                assertTrue(proseIdx in 0 until codeIdx, "Prose should precede code:\n$page1Text")

                val headlineY = page1Stripper.firstYFor("Headline")
                val proseY = page1Stripper.firstYFor("Transitions")
                val codeY = page1Stripper.firstYFor("provisional")
                assertTrue(headlineY < proseY && proseY < codeY, "Expected top-down Y coords: $headlineY < $proseY < $codeY")

                val page2Text = PDFTextStripper().apply {
                    startPage = 2
                    endPage = 2
                }.getText(doc)
                assertTrue(page2Text.contains("Visible Slide Two"), "Missing slide 2 text in:\n$page2Text")
                assertFalse(page2Text.contains("Hidden Alpha Zero Text"), "Invisible alpha=0 text leaked into:\n$page2Text")
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun cancellingMidPdfExportResetsStateAndDoesNotWriteOutput() = runTest {
        val dir = Files.createTempDirectory("pdf-export-cancel")
        try {
            val out = dir.resolve("cancelled.pdf")
            val exporter = StoryboardPdfExporter()
            val job = launch {
                assertFailsWith<CancellationException> {
                    exporter.exportAsPdf(deck, out)
                }
            }
            yield()
            assertTrue(exporter.isExporting)
            exporter.cancel()
            job.join()

            assertNull(exporter.status)
            assertFalse(exporter.isExporting)
            assertFalse(out.exists(), "Cancelled PDF export must not leave a partial file")
        } finally {
            dir.deleteRecursively()
        }
    }

    private class RecordingTextStripper : PDFTextStripper() {
        private val positions = mutableListOf<Pair<String, Float>>()

        override fun writeString(text: String, textPositions: MutableList<TextPosition>) {
            textPositions.firstOrNull()?.let { pos ->
                positions += text to pos.yDirAdj
            }
            super.writeString(text, textPositions)
        }

        fun firstYFor(substring: String): Float =
            positions.first { (chunk, _) -> chunk.contains(substring) }.second
    }
}
