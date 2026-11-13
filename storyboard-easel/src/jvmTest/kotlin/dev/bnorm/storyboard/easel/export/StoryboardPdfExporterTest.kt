package dev.bnorm.storyboard.easel.export

import androidx.compose.material.Text
import dev.bnorm.storyboard.Storyboard
import java.nio.file.Files
import kotlin.io.path.createFile
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield

@OptIn(kotlin.io.path.ExperimentalPathApi::class)
class StoryboardPdfExporterTest {

    private val deck = Storyboard.build(title = "ExportDeck") {
        scene(frames = listOf(0, 1, 2)) { Text("Scene 0") }
        scene(frames = listOf(0, 1)) { Text("Scene 1") }
    }

    @Test
    fun writeFailureSurfacesAsFailedStatusInsteadOfThrowing() {
        val dir = Files.createTempDirectory("pdf-export-fail")
        try {
            // The parent of the target is a regular file, so writing the PDF must fail with an IOException.
            val notADirectory = dir.resolve("blocker").createFile()
            val exporter = StoryboardPdfExporter()
            runBlocking { exporter.exportAsPdf(deck, notADirectory.resolve("out.pdf")) }
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
    fun secondExportWhileOneIsRunningIsIgnored() {
        val dir = Files.createTempDirectory("pdf-export-reentry")
        try {
            val first = dir.resolve("first.pdf")
            val second = dir.resolve("second.pdf")
            val exporter = StoryboardPdfExporter()
            runBlocking {
                val running = launch { exporter.exportAsPdf(deck, first) }
                yield() // let the first export start and suspend between pages
                assertTrue(exporter.isExporting, "First export is running")
                exporter.exportAsPdf(deck, second)
                running.join()
            }
            assertTrue(first.exists() && first.fileSize() > 0, "First export wrote its PDF")
            assertFalse(second.exists(), "A second export started mid-export must be ignored")
            assertNull(exporter.status, "Status clears after a successful export")
            assertFalse(exporter.isExporting)
        } finally {
            dir.deleteRecursively()
        }
    }
}
