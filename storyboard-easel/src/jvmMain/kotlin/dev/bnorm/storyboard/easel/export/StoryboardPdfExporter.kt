package dev.bnorm.storyboard.easel.export

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.TextUnit
import dev.bnorm.storyboard.Storyboard
import dev.bnorm.storyboard.easel.ScenePreview
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.dialogs.openFileSaver
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.io.path.exists
import kotlin.io.path.inputStream
import kotlin.io.path.isRegularFile
import kotlin.io.path.writeBytes
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.yield
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDFont
import org.apache.pdfbox.pdmodel.font.PDType0Font
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject
import org.apache.pdfbox.pdmodel.graphics.state.RenderingMode
import org.apache.pdfbox.util.Matrix
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image

private const val MIN_VISIBLE_ALPHA = 0.01f
private const val MAX_LAYOUT_SETTLE_PASSES = 3

internal data class PdfTextRun(
    val text: String,
    val left: Float,
    val right: Float,
    val top: Float,
    val bottom: Float,
    val baseline: Float,
    val fontSizePx: Float,
)

class StoryboardPdfExporter(
    private val coordinator: ExportCoordinator = ExportCoordinator(),
) {
    val status: ExportStatus? get() = coordinator.status

    /** `true` from the moment an export is requested (file dialog included) until it ends. */
    val isExporting: Boolean get() = coordinator.isExporting

    /** Clears a [ExportStatus.Failed] status; a running export's progress is left alone. */
    fun dismissFailure() = coordinator.dismissFailure()

    /** Cancels an active PDF export, if any. */
    fun cancel() = coordinator.cancel()

    /** Asks for a target file and exports [storyboard] to it. Ignored while an export is running. */
    suspend fun export(storyboard: Storyboard) {
        coordinator.runExclusive(failureMessage = ::failureMessage) { updateStatus ->
            val file = FileKit.openFileSaver(
                suggestedName = storyboard.title,
                defaultExtension = "pdf",
                allowedExtensions = setOf("pdf"),
            )
            if (file != null) {
                exportAsPdfLocked(
                    storyboard = storyboard,
                    path = file.file.toPath(),
                    updateStatus = updateStatus,
                )
            }
        }
    }

    internal suspend fun exportAsPdf(
        storyboard: Storyboard,
        path: Path,
    ) {
        coordinator.runExclusive(failureMessage = ::failureMessage) { updateStatus ->
            exportAsPdfLocked(storyboard, path, updateStatus)
        }
    }

    private fun failureMessage(e: Exception): String =
        "PDF export failed: ${e.message ?: e::class.simpleName}"

    @OptIn(ExperimentalComposeUiApi::class)
    private suspend fun exportAsPdfLocked(
        storyboard: Storyboard,
        path: Path,
        updateStatus: (ExportStatus?) -> Unit,
    ) {
        val doc = PDDocument()
        try {
            doc.documentInformation.title = storyboard.title
            val font = runInterruptible(Dispatchers.IO) { loadUnicodeFont(doc) }
            val frames = storyboard.indices
            val pageWidth = storyboard.format.size.width
            val pageHeight = storyboard.format.size.height

            for ((page, frame) in frames.withIndex()) {
                updateStatus(
                    ExportStatus.Exporting(
                        progress = if (frames.isEmpty()) 1f else page.toFloat() / frames.size,
                        message = "Generating PDF (${page + 1} / ${frames.size})\u2026",
                    )
                )
                // Let the live window draw a frame (and the progress popup animate) between pages.
                yield()

                // Offscreen composition stays on the caller (UI) thread: composing an
                // ImageComposeScene on another thread while a live window composes on the AWT
                // event thread can deadlock on the global snapshot observer locks.
                val scene = ImageComposeScene(
                    width = pageWidth,
                    height = pageHeight,
                    density = storyboard.format.density,
                    coroutineContext = Dispatchers.Unconfined,
                ) {
                    ScenePreview(storyboard, frame)
                }
                val renderedImage: Image
                val textRuns: List<PdfTextRun>
                try {
                    var image = scene.render(0L)
                    var settlePass = 0
                    while (scene.hasInvalidations() && settlePass < MAX_LAYOUT_SETTLE_PASSES) {
                        image.close()
                        image = scene.render(0L)
                        settlePass++
                    }
                    renderedImage = image
                    textRuns = extractPdfTextRuns(scene, pageWidth.toFloat(), pageHeight.toFloat())
                } finally {
                    scene.close()
                }

                try {
                    runInterruptible(Dispatchers.IO) {
                        createPage(renderedImage, textRuns, font, page, doc)
                    }
                } finally {
                    renderedImage.close()
                }
            }

            updateStatus(ExportStatus.Exporting(1.0f, "Saving PDF\u2026"))
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
            updateStatus(null)
        } finally {
            try {
                doc.close()
            } catch (e: IOException) {
                System.err.println("Storyboard: unable to close PDF document: ${e.message}")
            }
        }
    }

    private fun createPage(
        image: Image,
        textRuns: List<PdfTextRun>,
        font: PDFont,
        index: Int,
        doc: PDDocument,
    ) {
        val bytes = image.encodeToData(EncodedImageFormat.PNG)?.bytes
            ?: throw IOException("unable to encode page ${index + 1} as PNG")
        val name = "frame-${index.toString().padStart(3, '0')}"

        val pageWidth = image.width.toFloat()
        val pageHeight = image.height.toFloat()
        val page = PDPage(PDRectangle(pageWidth, pageHeight))
        doc.addPage(page)

        PDPageContentStream(doc, page).use { contentStream ->
            contentStream.drawImage(PDImageXObject.createFromByteArray(doc, bytes, name), 0f, 0f)
            writeInvisibleTextLayer(contentStream, textRuns, font, pageWidth, pageHeight)
        }
    }

    private fun writeInvisibleTextLayer(
        contentStream: PDPageContentStream,
        textRuns: List<PdfTextRun>,
        font: PDFont,
        pageWidth: Float,
        pageHeight: Float,
    ) {
        if (textRuns.isEmpty()) return
        contentStream.setRenderingMode(RenderingMode.NEITHER)
        for (run in textRuns) {
            val sanitized = sanitizeForFont(run.text, font)
            if (sanitized.isBlank()) continue

            val fontSizePt = run.fontSizePx.coerceIn(1f, pageHeight)
            val pdfX = run.left.coerceIn(0f, pageWidth)
            val pdfBaselineY = (pageHeight - run.baseline).coerceIn(0f, pageHeight)
            val measuredWidth = runCatching { font.getStringWidth(sanitized) / 1000f * fontSizePt }
                .getOrDefault(0f)
            val targetWidth = (run.right - run.left).coerceAtLeast(0f)
            val hScale = if (measuredWidth > 0.5f && targetWidth > 0.5f) {
                (targetWidth / measuredWidth * 100f).coerceIn(50f, 200f)
            } else {
                100f
            }

            contentStream.beginText()
            contentStream.setFont(font, fontSizePt)
            contentStream.setHorizontalScaling(hScale)
            contentStream.setTextMatrix(Matrix.getTranslateInstance(pdfX, pdfBaselineY))
            contentStream.showText(sanitized)
            contentStream.endText()
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
internal fun extractPdfTextRuns(
    scene: ImageComposeScene,
    pageWidth: Float,
    pageHeight: Float,
): List<PdfTextRun> {
    val rawRuns = mutableListOf<PdfTextRun>()
    val textLayouts = mutableListOf<TextLayoutResult>()

    for (owner in scene.semanticsOwners) {
        val nodes = owner.getAllSemanticsNodes(mergingEnabled = false)
        for (node in nodes) {
            if (!isVisibleTextCandidate(node, pageWidth, pageHeight)) continue
            val action = node.config.getOrNull(SemanticsActions.GetTextLayoutResult) ?: continue
            textLayouts.clear()
            action.action?.invoke(textLayouts)
            if (textLayouts.isEmpty()) continue

            val origin = node.positionInRoot
            val clipBounds = node.boundsInRoot
            for (layout in textLayouts) {
                val rawSource = layout.layoutInput.text.text
                if (rawSource.isEmpty()) continue
                val fontSizePx = resolveFontSizePx(layout)
                for (lineIdx in 0 until layout.lineCount) {
                    val start = layout.getLineStart(lineIdx).coerceIn(0, rawSource.length)
                    val end = layout.getLineEnd(lineIdx, visibleEnd = false).coerceIn(start, rawSource.length)
                    val lineText = rawSource.substring(start, end)
                        .replace("\r", "")
                        .replace("\n", "")
                    if (lineText.isEmpty()) continue

                    val left = origin.x + layout.getLineLeft(lineIdx)
                    val right = origin.x + layout.getLineRight(lineIdx)
                    val top = origin.y + layout.getLineTop(lineIdx)
                    val bottom = origin.y + layout.getLineBottom(lineIdx)
                    val baseline = origin.y + layout.getLineBaseline(lineIdx)
                    if (right <= 0f || left >= pageWidth || bottom <= 0f || top >= pageHeight) continue
                    if (right <= clipBounds.left || left >= clipBounds.right ||
                        bottom <= clipBounds.top || top >= clipBounds.bottom
                    ) {
                        continue
                    }

                    val lineFontSize = if (fontSizePx > 0f) fontSizePx else ((bottom - top) * 0.78f).coerceAtLeast(1f)
                    rawRuns += PdfTextRun(
                        text = lineText,
                        left = left,
                        right = right,
                        top = top,
                        bottom = bottom,
                        baseline = baseline,
                        fontSizePx = lineFontSize,
                    )
                }
            }
        }
    }

    return orderAndCoalesceRuns(rawRuns)
}

@Suppress("DEPRECATION")
private fun isVisibleTextCandidate(
    node: SemanticsNode,
    pageWidth: Float,
    pageHeight: Float,
): Boolean {
    if (node.config.contains(SemanticsProperties.HideFromAccessibility)) return false
    if (node.config.contains(SemanticsProperties.InvisibleToUser)) return false
    if (node.computeEffectiveAlpha() <= MIN_VISIBLE_ALPHA) return false
    val bounds = node.boundsInRoot
    if (bounds.width <= 0f || bounds.height <= 0f) return false
    if (bounds.right <= 0f || bounds.left >= pageWidth || bounds.bottom <= 0f || bounds.top >= pageHeight) {
        return false
    }
    return true
}

private fun resolveFontSizePx(layout: TextLayoutResult): Float {
    val size: TextUnit = layout.layoutInput.style.fontSize
    if (size == TextUnit.Unspecified || !size.isSp) return 0f
    return with(layout.layoutInput.density) { size.toPx() }
}

internal fun orderAndCoalesceRuns(rawRuns: List<PdfTextRun>): List<PdfTextRun> {
    if (rawRuns.isEmpty()) return emptyList()

    // Deduplicate identical overlapping runs before row grouping.
    val uniqueRaw = ArrayList<PdfTextRun>(rawRuns.size)
    for (candidate in rawRuns) {
        val duplicate = uniqueRaw.any { existing ->
            existing.text == candidate.text &&
                abs(existing.left - candidate.left) < 2f &&
                abs(existing.right - candidate.right) < 4f &&
                abs(existing.baseline - candidate.baseline) < 2f
        }
        if (!duplicate) uniqueRaw += candidate
    }

    // Group into visual rows sorted top-to-bottom, and within each row left-to-right.
    val sortedByBaseline = uniqueRaw.sortedWith(compareBy<PdfTextRun> { it.baseline }.thenBy { it.left })
    val rows = mutableListOf<MutableList<PdfTextRun>>()
    for (run in sortedByBaseline) {
        val currentRow = rows.lastOrNull()
        if (currentRow == null) {
            rows += mutableListOf(run)
        } else {
            val rowBaseline = currentRow.first().baseline
            val rowFontSize = currentRow.first().fontSizePx
            val tolerance = maxOf(2f, minOf(run.fontSizePx, rowFontSize) * 0.25f)
            if (abs(run.baseline - rowBaseline) <= tolerance) {
                currentRow += run
            } else {
                rows += mutableListOf(run)
            }
        }
    }

    val coalesced = mutableListOf<PdfTextRun>()
    for (row in rows) {
        row.sortBy { it.left }
        var acc: PdfTextRun? = null
        for (next in row) {
            val prev = acc
            if (prev == null) {
                acc = next
                continue
            }
            val maxFont = maxOf(prev.fontSizePx, next.fontSizePx)
            val similarSize = abs(prev.fontSizePx - next.fontSizePx) <= maxFont * 0.15f
            val gap = next.left - prev.right
            val sameLineGap = gap in -4f..(maxFont * 0.75f)
            if (similarSize && sameLineGap) {
                val joinedText = when {
                    prev.text.lastOrNull()?.isWhitespace() == true ||
                        next.text.firstOrNull()?.isWhitespace() == true -> prev.text + next.text
                    gap > maxFont * 0.18f -> prev.text + " " + next.text
                    else -> prev.text + next.text
                }
                acc = PdfTextRun(
                    text = joinedText,
                    left = minOf(prev.left, next.left),
                    right = maxOf(prev.right, next.right),
                    top = minOf(prev.top, next.top),
                    bottom = maxOf(prev.bottom, next.bottom),
                    baseline = prev.baseline,
                    fontSizePx = prev.fontSizePx,
                )
            } else {
                appendNormalizedRun(coalesced, prev)
                acc = next
            }
        }
        if (acc != null) {
            appendNormalizedRun(coalesced, acc)
        }
    }

    return coalesced
}

private fun appendNormalizedRun(out: MutableList<PdfTextRun>, run: PdfTextRun) {
    val trimmedEnd = run.text.trimEnd()
    if (trimmedEnd.isBlank()) return
    val normalized = if (trimmedEnd.length == run.text.length) run else run.copy(text = trimmedEnd)
    val duplicate = out.any { existing ->
        existing.text == normalized.text &&
            abs(existing.left - normalized.left) < 2f &&
            abs(existing.baseline - normalized.baseline) < 2f
    }
    if (!duplicate) {
        out += normalized
    }
}

private fun sanitizeForFont(text: String, font: PDFont): String {
    val sb = StringBuilder(text.length)
    var index = 0
    while (index < text.length) {
        val cp = text.codePointAt(index)
        val charCount = Character.charCount(cp)
        index += charCount

        if (cp < 0x20) {
            if (cp == '\t'.code) sb.append(' ')
            continue
        }
        val slice = String(Character.toChars(cp))
        val canEncode = try {
            font.encode(slice)
            true
        } catch (_: IllegalArgumentException) {
            false
        } catch (_: IOException) {
            false
        }
        if (canEncode) {
            sb.append(slice)
        }
    }
    return sb.toString()
}

private fun loadUnicodeFont(doc: PDDocument): PDFont {
    val classpathCandidates = listOf(
        "composeResources/ciacola.deck.generated.resources/font/Inter-Regular.ttf",
        "composeResources/ciacola.deck.generated.resources/font/JetBrainsMono-Regular.ttf",
        "font/Inter-Regular.ttf",
        "font/JetBrainsMono-Regular.ttf",
        "fonts/Inter-Regular.ttf",
        "fonts/JetBrainsMono-Regular.ttf",
    )
    var basicFallback: PDFont? = null
    val classLoader = Thread.currentThread().contextClassLoader ?: StoryboardPdfExporter::class.java.classLoader
    for (resource in classpathCandidates) {
        val stream = classLoader?.getResourceAsStream(resource) ?: continue
        val loaded = stream.use { input ->
            runCatching { PDType0Font.load(doc, input) }.getOrNull()
        } ?: continue
        if (supportsUnicodeSample(loaded)) return loaded
        if (basicFallback == null && supportsBasicAscii(loaded)) {
            basicFallback = loaded
        }
    }

    val javaHome = System.getProperty("java.home")
    val fileCandidates = buildList {
        if (!javaHome.isNullOrBlank()) {
            val jbrFonts = Path.of(javaHome, "lib", "fonts")
            add(jbrFonts.resolve("JetBrainsMono-Regular.ttf"))
            add(jbrFonts.resolve("FiraCode-Regular.ttf"))
            add(jbrFonts.resolve("DroidSans.ttf"))
            add(jbrFonts.resolve("Roboto-Light.ttf"))
            add(jbrFonts.resolve("SourceCodePro-Regular.ttf"))
        }
        // macOS
        add(Path.of("/System/Library/Fonts/Supplemental/Arial Unicode.ttf"))
        add(Path.of("/Library/Fonts/Arial Unicode.ttf"))
        add(Path.of("/System/Library/Fonts/Geneva.ttf"))
        // Linux
        add(Path.of("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"))
        add(Path.of("/usr/share/fonts/TTF/DejaVuSans.ttf"))
        add(Path.of("/usr/share/fonts/noto/NotoSans-Regular.ttf"))
        add(Path.of("/usr/share/fonts/truetype/liberation/LiberationSans-Regular.ttf"))
        // Windows
        val winDir = System.getenv("WINDIR") ?: "C:\\Windows"
        add(Path.of(winDir, "Fonts", "segoeui.ttf"))
        add(Path.of(winDir, "Fonts", "arial.ttf"))
    }

    for (fontPath in fileCandidates) {
        if (!Files.isRegularFile(fontPath) || !fontPath.exists()) continue
        val loaded = runCatching {
            fontPath.inputStream().use { input -> PDType0Font.load(doc, input) }
        }.getOrNull() ?: continue
        if (supportsUnicodeSample(loaded)) return loaded
        if (basicFallback == null && supportsBasicAscii(loaded)) {
            basicFallback = loaded
        }
    }

    return basicFallback ?: PDType1Font(Standard14Fonts.FontName.HELVETICA)
}

private fun supportsBasicAscii(font: PDFont): Boolean = try {
    font.encode("A")
    true
} catch (_: Exception) {
    false
}

private fun supportsUnicodeSample(font: PDFont): Boolean = try {
    font.encode("A\u2192\u2014\u2026")
    true
} catch (_: Exception) {
    false
}
