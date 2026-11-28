package dev.sebastiano.ciacola.animation

import androidx.compose.ui.graphics.toComposeImageBitmap
import java.awt.AlphaComposite
import java.awt.image.BufferedImage
import java.io.InputStream
import javax.imageio.ImageIO
import javax.imageio.ImageReader
import javax.imageio.metadata.IIOMetadataNode

/**
 * Composites delta frames on a persistent full-size canvas, including offsets and GIF disposal.
 * The ImageIO stream is closed without closing the caller's InputStream. Every declared frame's
 * dimensions are checked before pixels are allocated, including frames larger than the canvas.
 */
internal fun decodeGifFrames(input: InputStream, budget: AnimationBudget): List<AnimationFrame>? =
    ImageIO.createImageInputStream(input)?.use { imageInput ->
        val readers = ImageIO.getImageReadersBySuffix("gif")
        if (!readers.hasNext()) return null
        val reader = readers.next()
        try {
            reader.input = imageInput
            val count = reader.getNumImages(true)
            if (count <= 1) return null
            val metadata = reader.streamMetadata
            val root = metadata?.getAsTree(metadata.nativeMetadataFormatName) as? IIOMetadataNode
            val screen = root?.getElementsByTagName("LogicalScreenDescriptor")?.item(0) as? IIOMetadataNode
            val width = screen?.getAttribute("logicalScreenWidth")?.toIntOrNull()?.takeIf { it > 0 }
            val height = screen?.getAttribute("logicalScreenHeight")?.toIntOrNull()?.takeIf { it > 0 }
            budget.check(width ?: reader.getWidth(0), height ?: reader.getHeight(0), count)
            for (index in 0 until count) budget.check(reader.getWidth(index), reader.getHeight(index), 1)
            val frames = mutableListOf<AnimationFrame>()
            var canvas: BufferedImage? = null
            for (index in 0 until count) {
                if (Thread.interrupted()) throw InterruptedException("GIF decoding cancelled")
                val composed = composeGifFrame(reader, index, canvas, width, height) ?: continue
                canvas = composed.first
                frames.add(composed.second)
            }
            if (frames.size <= 1) null else frames
        } finally {
            reader.dispose()
        }
    }

internal fun probeGif(input: InputStream): AnimationInfo? =
    ImageIO.createImageInputStream(input)?.use { imageInput ->
        val readers = ImageIO.getImageReadersBySuffix("gif")
        if (!readers.hasNext()) return null
        val reader = readers.next()
        try {
            reader.input = imageInput
            val count = reader.getNumImages(true)
            if (count <= 0) return null
            val metadata = reader.streamMetadata
            val root = metadata?.getAsTree(metadata.nativeMetadataFormatName) as? IIOMetadataNode
            val screen = root?.getElementsByTagName("LogicalScreenDescriptor")?.item(0) as? IIOMetadataNode
            val width = screen?.getAttribute("logicalScreenWidth")?.toIntOrNull()?.takeIf { it > 0 } ?: reader.getWidth(0)
            val height = screen?.getAttribute("logicalScreenHeight")?.toIntOrNull()?.takeIf { it > 0 } ?: reader.getHeight(0)
            AnimationInfo(width, height, count)
        } finally {
            reader.dispose()
        }
    }

private fun composeGifFrame(
    reader: ImageReader,
    index: Int,
    previous: BufferedImage?,
    canvasWidth: Int?,
    canvasHeight: Int?,
): Pair<BufferedImage, AnimationFrame>? {
    val raw = reader.read(index) ?: return null
    if (raw.width <= 0 || raw.height <= 0) return null
    val root = runCatching {
        val metadata = reader.getImageMetadata(index)
        metadata?.getAsTree(metadata.nativeMetadataFormatName) as? IIOMetadataNode
    }.getOrNull()
    val descriptor = root?.getElementsByTagName("ImageDescriptor")?.item(0) as? IIOMetadataNode
    val left = descriptor?.getAttribute("imageLeftPosition")?.toIntOrNull() ?: 0
    val top = descriptor?.getAttribute("imageTopPosition")?.toIntOrNull() ?: 0
    val control = root?.getElementsByTagName("GraphicControlExtension")?.item(0) as? IIOMetadataNode
    val disposal = control?.getAttribute("disposalMethod") ?: "doNotDispose"
    val delay = control?.getAttribute("delayTime")?.toIntOrNull()?.times(CENTISECONDS_TO_MS)
        ?.takeIf { it > 0 } ?: MIN_FRAME_DELAY_MS
    val width = (canvasWidth ?: raw.width).coerceAtLeast(1)
    val height = (canvasHeight ?: raw.height).coerceAtLeast(1)
    var canvas = previous ?: BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    val beforeDrawing = if (disposal == "restoreToPrevious") copyImage(canvas) else null
    canvas.createGraphics().let { graphics ->
        try { graphics.drawImage(raw, left, top, null) } finally { graphics.dispose() }
    }
    val snapshot = copyImage(canvas).toComposeImageBitmap()
    when (disposal) {
        "restoreToBackgroundColor" -> canvas.createGraphics().let { graphics ->
            try {
                graphics.composite = AlphaComposite.Clear
                graphics.fillRect(left, top, raw.width, raw.height)
            } finally {
                graphics.dispose()
            }
        }
        "restoreToPrevious" -> canvas = beforeDrawing ?: BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    }
    return canvas to (snapshot to delay)
}

private fun copyImage(source: BufferedImage): BufferedImage {
    val copy = BufferedImage(source.width, source.height, BufferedImage.TYPE_INT_ARGB)
    copy.createGraphics().let { graphics ->
        try { graphics.drawImage(source, 0, 0, null) } finally { graphics.dispose() }
    }
    return copy
}

private const val CENTISECONDS_TO_MS = 10
