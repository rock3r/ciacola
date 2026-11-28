package dev.sebastiano.ciacola.animation

import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Codec
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorSpace
import org.jetbrains.skia.Data
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo

/** Skia composites blend/disposal/offsets; copy every full-canvas snapshot before reusing the bitmap. */
internal fun decodeWebpFrames(bytes: ByteArray, budget: AnimationBudget): List<AnimationFrame>? =
    Data.makeFromBytes(bytes).use { data ->
        Codec.makeFromData(data).use { codec ->
            val count = codec.frameCount
            if (count <= 1) return null
            budget.check(codec.width, codec.height, count)
            val durations = codec.framesInfo.map { it.duration }
            Bitmap().use { canvas ->
                canvas.allocPixels(
                    ImageInfo.makeN32(codec.width, codec.height, ColorAlphaType.PREMUL, ColorSpace.sRGB)
                )
                List(count) { index ->
                    if (Thread.interrupted()) throw InterruptedException("WebP decoding cancelled")
                    if (index == 0) codec.readPixels(canvas, 0)
                    else codec.readPixels(canvas, index, index - 1)
                    val snapshot = Image.makeFromBitmap(canvas).use { it.toComposeImageBitmap() }
                    snapshot to (durations[index].takeIf { it > 0 } ?: MIN_FRAME_DELAY_MS)
                }
            }
        }
    }

internal fun probeWebp(bytes: ByteArray): AnimationInfo =
    Data.makeFromBytes(bytes).use { data ->
        Codec.makeFromData(data).use { codec -> AnimationInfo(codec.width, codec.height, codec.frameCount) }
    }
