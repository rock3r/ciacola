package dev.bnorm.storyboard.easel.export

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.renderComposeScene
import dev.bnorm.storyboard.Storyboard
import dev.bnorm.storyboard.easel.ScenePreview
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.dialogs.openFileSaver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.yield
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.coroutines.cancellation.CancellationException
import kotlin.io.path.writeBytes

class StoryboardPdfExporter {
    var status by mutableStateOf<ExportStatus?>(null)
        private set

    private var running by mutableStateOf(false)

    /** `true` from the moment an export is requested (file dialog included) until it ends. */
    val isExporting: Boolean get() = running

    /** Clears a [ExportStatus.Failed] status; a running export's progress is left alone. */
    fun dismissFailure() {
        if (status is ExportStatus.Failed) status = null
    }

    /** Asks for a target file and exports [storyboard] to it. Ignored while an export is running. */
    suspend fun export(
        storyboard: Storyboard,
    ) {
        exclusive {
            val file = FileKit.openFileSaver(
                suggestedName = storyboard.title,
                defaultExtension = "pdf",
                allowedExtensions = setOf("pdf"),
            )
            if (file != null) {
                exportAsPdfLocked(
                    storyboard = storyboard,
                    path = file.file.toPath(),
                )
            }
        }
    }

    internal suspend fun exportAsPdf(
        storyboard: Storyboard,
        path: Path,
    ) {
        exclusive { exportAsPdfLocked(storyboard, path) }
    }

    /**
     * Runs [block] unless an export is already running. Callers live on the UI thread, so the
     * check-and-set cannot race. Errors end up in a visible [ExportStatus.Failed].
     */
    private suspend fun exclusive(block: suspend () -> Unit) {
        if (running) return
        running = true
        status = null
        try {
            block()
        } catch (e: CancellationException) {
            status = null
            throw e
        } catch (e: IOException) {
            status = failure(e)
        } catch (e: RuntimeException) {
            status = failure(e)
        } finally {
            running = false
        }
    }

    private fun failure(e: Exception): ExportStatus.Failed =
        ExportStatus.Failed("PDF export failed: ${e.message ?: e::class.simpleName}")

    private suspend fun exportAsPdfLocked(
        storyboard: Storyboard,
        path: Path,
    ) {
        val doc = PDDocument()
        try {
            val frames = storyboard.indices
            for ((page, frame) in frames.withIndex()) {
                status = ExportStatus.Exporting(page.toFloat() / frames.size, "Generating PDF...")
                // Let the live window draw a frame (and the progress popup animate) between pages.
                yield()
                // Offscreen composition stays on the caller (UI) thread: composing an
                // ImageComposeScene on another thread while a live window composes on the AWT
                // event thread can deadlock on the global snapshot observer locks.
                val image = renderComposeScene(
                    width = storyboard.format.size.width,
                    height = storyboard.format.size.height,
                    // Not needed because preview sets the density, but good for consistency.
                    density = storyboard.format.density,
                ) {
                    ScenePreview(storyboard, frame)
                }
                try {
                    runInterruptible(Dispatchers.IO) { createPage(image, page, doc) }
                } finally {
                    image.close()
                }
            }

            status = ExportStatus.Exporting(1.0f, "Saving PDF...")
            runInterruptible(Dispatchers.IO) {
                val bytes = ByteArrayOutputStream()
                doc.save(bytes)
                path.writeBytes(
                    array = bytes.toByteArray(),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                )
            }
            status = null
        } finally {
            try {
                doc.close()
            } catch (e: IOException) {
                System.err.println("Storyboard: unable to close PDF document: ${e.message}")
            }
        }
    }

    private fun createPage(image: Image, index: Int, doc: PDDocument) {
        val bytes = image.encodeToData(EncodedImageFormat.PNG)?.bytes
            ?: throw IOException("unable to encode page ${index + 1} as PNG")
        val name = "frame-${index.toString().padStart(3, '0')}"

        val page = PDPage(PDRectangle(image.width.toFloat(), image.height.toFloat()))
        doc.addPage(page)

        PDPageContentStream(doc, page).use { contentStream ->
            contentStream.drawImage(PDImageXObject.createFromByteArray(doc, bytes, name), 0f, 0f)
        }
    }
}
