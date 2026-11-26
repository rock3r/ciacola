package dev.sebastiano.ciacola.animation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runInterruptible
import org.jetbrains.skia.Image

/** A full-canvas snapshot, followed by its display duration in milliseconds. */
typealias AnimationFrame = Pair<ImageBitmap, Int>

/** Limits on canvas sides and the sum of all full-canvas frame pixels (about 96 MiB RGBA). */
data class AnimationBudget(
    val maxSidePx: Int = 8192,
    val maxPixels: Long = 24L * 1024 * 1024,
) {
    init {
        require(maxSidePx > 0 && maxPixels > 0) { "Decode limits must be positive" }
    }

    internal fun check(width: Int, height: Int, frameCount: Int) {
        if (width > maxSidePx || height > maxSidePx || width.toLong() * height * frameCount > maxPixels) {
            throw AnimationTooLargeException("$width×$height, $frameCount frames, exceeds the decode limit")
        }
    }
}

class AnimationTooLargeException(message: String) : RuntimeException(message)

/**
 * Content-sniffed GIF/WebP animation decode. Returns null for stills and unrecognized formats.
 * Runs synchronously: use an interruptible background thread. Cancellation is checked between
 * frames; malformed recognized data throws IOException or RuntimeException. Input bytes are owned
 * by the caller and must not be mutated during decoding. Container loop counts are not interpreted.
 */
fun decodeAnimatedFrames(
    bytes: ByteArray,
    budget: AnimationBudget = AnimationBudget(),
): List<AnimationFrame>? {
    val header = String(bytes, 0, minOf(bytes.size, WEBP_HEADER_BYTES), Charsets.ISO_8859_1)
    return when {
        header.startsWith("GIF87a") || header.startsWith("GIF89a") ->
            decodeGifFrames(bytes.inputStream(), budget)
        WEBP_HEADER.matches(header) -> decodeWebpFrames(bytes, budget)
        else -> null
    }
}

/** A still, or full-canvas animation frames. [note] explains a budget-related still fallback. */
data class DecodedImage(
    val firstFrame: ImageBitmap,
    val frames: List<AnimationFrame>? = null,
    val note: String? = null,
)

/**
 * Background loader with a first-frame fallback when the animation exceeds its budget.
 * The animation budget does not limit the still fallback. No cache, file access, or network access.
 */
suspend fun loadImage(bytes: ByteArray, budget: AnimationBudget = AnimationBudget()): DecodedImage =
    runInterruptible(Dispatchers.IO) {
        var note: String? = null
        val frames = try {
            decodeAnimatedFrames(bytes, budget)
        } catch (e: AnimationTooLargeException) {
            note = "Too large to animate (${e.message}); showing its first frame."
            null
        } catch (e: CancellationException) {
            throw e
        } catch (_: IOException) {
            null // A damaged animation may still have a readable first frame.
        } catch (_: RuntimeException) {
            null
        }
        if (frames != null) {
            DecodedImage(frames.first().first, frames)
        } else {
            if (Thread.interrupted()) throw InterruptedException("Image decoding cancelled")
            val first = Image.makeFromEncoded(bytes).use { it.toComposeImageBitmap() }
            DecodedImage(first, note = note)
        }
    }

/**
 * Composition-scoped decode. Null means loading; failures are returned, cancellation propagates.
 * Leaving composition cancels the load. Reuse the byte-array instance across recompositions;
 * a new instance or budget starts a new decode, never drawing the old source while it loads.
 */
@Composable
fun rememberDecodedImage(
    bytes: ByteArray,
    budget: AnimationBudget = AnimationBudget(),
): Result<DecodedImage>? {
    // Key the producer group too: produceState alone retains its old value until its effect runs.
    return androidx.compose.runtime.key(bytes, budget) {
        val result by produceState<Result<DecodedImage>?>(null, bytes, budget) {
            value = try {
                Result.success(loadImage(bytes, budget))
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                Result.failure(e)
            } catch (e: RuntimeException) {
                Result.failure(e)
            }
        }
        result
    }
}

/**
 * Plays while composed and [playing], indefinitely when [loop] is true, otherwise once, holding
 * the last frame. Leaving composition cancels the timer.
 * Re-entering composition, changing frames, or switching playing back on starts from frame zero.
 * A caller that retains off-screen compositions must explicitly set playing=false or remove them.
 */
@Composable
fun rememberAnimatedFrame(
    frames: List<AnimationFrame>,
    playing: Boolean = true,
    loop: Boolean = true,
): ImageBitmap {
    if (frames.isEmpty()) return remember { ImageBitmap(1, 1) }
    var current by remember(frames, playing, loop) { mutableStateOf(frames.first().first) }
    LaunchedEffect(frames, playing, loop) {
        if (!playing) return@LaunchedEffect
        var index = 0
        while (true) {
            current = frames[index].first
            if (!loop && index == frames.lastIndex) return@LaunchedEffect
            delay(frames[index].second.toLong())
            index = (index + 1) % frames.size
        }
    }
    return current
}

/** Total display time of [frames] in milliseconds. */
fun animationDurationMs(frames: List<AnimationFrame>): Int = frames.sumOf { it.second }

/**
 * Index of the frame on screen [positionMs] into [frames], clamped to the first and last frames
 * (so the clip's end shows its last frame).
 */
fun frameIndexAt(frames: List<AnimationFrame>, positionMs: Double): Int {
    if (frames.isEmpty()) return 0
    var elapsed = 0.0
    for ((index, frame) in frames.withIndex()) {
        elapsed += frame.second
        if (positionMs < elapsed) return index
    }
    return frames.lastIndex
}

/** Canvas size and frame count of a GIF/WebP animation, read without decoding its pixels. */
data class AnimationInfo(val width: Int, val height: Int, val frameCount: Int) {
    val totalPixels: Long get() = width.toLong() * height * frameCount
}

/**
 * Content-sniffed GIF/WebP header probe. Returns null for stills, unrecognized formats and
 * unreadable data; never decodes frame pixels.
 */
fun probeAnimation(bytes: ByteArray): AnimationInfo? {
    val header = String(bytes, 0, minOf(bytes.size, WEBP_HEADER_BYTES), Charsets.ISO_8859_1)
    val info = runCatching {
        when {
            header.startsWith("GIF87a") || header.startsWith("GIF89a") -> probeGif(bytes.inputStream())
            WEBP_HEADER.matches(header) -> probeWebp(bytes)
            else -> null
        }
    }.getOrNull()
    return info?.takeIf { it.frameCount > 1 }
}

internal const val MIN_FRAME_DELAY_MS = 100
private const val WEBP_HEADER_BYTES = 12
private val WEBP_HEADER = Regex("RIFF.{4}WEBP", RegexOption.DOT_MATCHES_ALL)
