package dev.sebastiano.ciacola.animation

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class AnimatedImageTest {
    @Test
    fun `GIF delta frames honor offsets clear and restore disposal`() {
        val frames = assertNotNull(decodeAnimatedFrames(sample("sample.gif")))
        assertEquals(listOf(100, 200, 300, 400), frames.map { it.second })
        assertTrue(frames.all { it.first.width == 64 && it.first.height == 48 })
        val pixels = frames.map { it.first.toPixelMap() }
        assertEquals(Color.Red, pixels[0][10, 20])
        assertEquals(Color.Yellow, pixels[1][10, 20])
        assertEquals(0f, pixels[2][10, 20].alpha)
        assertEquals(Color.Green, pixels[2][42, 18])
        assertEquals(Color.Red, pixels[3][42, 18], "restore previous must remove the green patch")
        assertEquals(Color.Blue, pixels[3][2, 2])
    }

    @Test
    fun `WebP frames are composited copied and timed`() {
        val frames = assertNotNull(decodeAnimatedFrames(sample("sample.webp")))
        assertEquals(listOf(100, 200, 300, 400), frames.map { it.second })
        assertTrue(frames.all { it.first.width == 64 && it.first.height == 48 })
        val pixels = frames.map { it.first.toPixelMap() }
        assertEquals(Color.Red, pixels[0][10, 20])
        assertEquals(Color.Yellow, pixels[1][10, 20])
        assertEquals(Color.Red, pixels[2][10, 20])
        assertEquals(Color.Yellow, pixels[2][44, 20])
        assertEquals(Color.Blue, pixels[3][10, 20])
        assertEquals(0f, pixels[3][50, 20].alpha)
    }

    @Test
    fun `budget admits exact boundary and refuses excess for both formats`() {
        for (name in listOf("sample.gif", "sample.webp")) {
            val bytes = sample(name)
            assertNotNull(decodeAnimatedFrames(bytes, AnimationBudget(64, 64L * 48 * 4)))
            assertFailsWith<AnimationTooLargeException> {
                decodeAnimatedFrames(bytes, AnimationBudget(64, 64L * 48 * 4 - 1))
            }
            assertFailsWith<AnimationTooLargeException> {
                decodeAnimatedFrames(bytes, AnimationBudget(63, 64L * 48 * 4))
            }
        }
    }

    @Test
    fun `interrupted decoding propagates instead of returning a still`() {
        for (name in listOf("sample.gif", "sample.webp")) {
            Thread.currentThread().interrupt()
            try {
                assertFailsWith<InterruptedException> { decodeAnimatedFrames(sample(name)) }
            } finally {
                Thread.interrupted()
            }
        }
    }

    @Test
    fun `stills and unrelated input are not animations`() {
        assertNull(decodeAnimatedFrames(byteArrayOf()))
        assertNull(decodeAnimatedFrames("not an image".toByteArray()))
        assertNull(decodeAnimatedFrames(sample("still.gif")))
        assertNull(decodeAnimatedFrames(sample("still.webp")))
    }

    @Test
    fun `loader animates or falls back to the first frame with a budget note`() = runBlocking {
        for (name in listOf("sample.gif", "sample.webp")) {
            val bytes = sample(name)
            val normal = loadImage(bytes)
            assertEquals(4, assertNotNull(normal.frames).size)
            assertNull(normal.note)
            for (budget in listOf(AnimationBudget(64, 64L * 48), AnimationBudget(63, 64L * 48))) {
                val fallback = loadImage(bytes, budget)
                assertNull(fallback.frames)
                assertNotNull(fallback.note)
                assertEquals(64 to 48, fallback.firstFrame.width to fallback.firstFrame.height)
                assertEquals(Color.Red, fallback.firstFrame.toPixelMap()[10, 20])
            }
        }
    }

    @Test
    fun `GIF raw frame larger than logical canvas is refused before allocation`() {
        val bytes = sample("sample.gif")
        // Header declares an 8x8 canvas, but the first descriptor still declares 64x48.
        bytes[6] = 8; bytes[7] = 0; bytes[8] = 8; bytes[9] = 0
        assertFailsWith<AnimationTooLargeException> {
            decodeAnimatedFrames(bytes, AnimationBudget(8, 8L * 8 * 4))
        }
    }

    @Test
    fun `GIF decoding closes ImageIO disk cache files`() {
        val directory = Files.createTempDirectory("imageio-cache")
        val previousCache = ImageIO.getUseCache()
        val previousDirectory = ImageIO.getCacheDirectory()
        try {
            ImageIO.setUseCache(true)
            ImageIO.setCacheDirectory(directory.toFile())
            assertNotNull(decodeAnimatedFrames(sample("sample.gif")))
            Files.list(directory).use { assertEquals(0L, it.count()) }
        } finally {
            ImageIO.setUseCache(previousCache)
            ImageIO.setCacheDirectory(previousDirectory)
            Files.list(directory).use { files -> files.forEach { Files.deleteIfExists(it) } }
            Files.delete(directory)
        }
    }
}

internal fun sample(name: String): ByteArray =
    checkNotNull(AnimatedImageTest::class.java.getResourceAsStream("/animated-images/$name")).use { it.readBytes() }
