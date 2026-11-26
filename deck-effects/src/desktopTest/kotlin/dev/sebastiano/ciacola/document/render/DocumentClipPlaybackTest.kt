package dev.sebastiano.ciacola.document.render

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import dev.bnorm.storyboard.AdvanceDirection
import dev.sebastiano.ciacola.document.BeatSpec
import dev.sebastiano.ciacola.document.Build
import dev.sebastiano.ciacola.document.ClipPlayhead
import dev.sebastiano.ciacola.document.DeckClipControls
import dev.sebastiano.ciacola.document.DeckDocument
import dev.sebastiano.ciacola.document.FrameSpec
import dev.sebastiano.ciacola.document.ImagePayload
import dev.sebastiano.ciacola.document.NodeSpec
import dev.sebastiano.ciacola.document.PlaybackAction
import dev.sebastiano.ciacola.document.RenderPurpose
import dev.sebastiano.ciacola.document.SlideSpec
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Drives the 4-frame CC0 fixture (red 0-100 ms, yellow 100-300, red 300-600, blue 600-1000 at
 * image pixel 10,20) through a full-stage `image` node.
 */
@OptIn(ExperimentalComposeUiApi::class)
class DocumentClipPlaybackTest {
    private val assetsDir: File = Files.createTempDirectory("clip-assets").toFile().also { dir ->
        val target = File(dir, "clip-deck.assets/clip.webp")
        target.parentFile.mkdirs()
        target.writeBytes(checkNotNull(javaClass.getResourceAsStream("/animated-images/sample.webp")).use { it.readBytes() })
    }

    @AfterTest
    fun cleanUp() {
        assetsDir.deleteRecursively()
    }

    private fun deck(image: ImagePayload, builds: List<Build>): DeckDocument {
        val stage = compactStage()
        return DeckDocument(
            id = "clip-deck",
            stage = stage,
            slides = listOf(
                SlideSpec(
                    id = "s",
                    beats = listOf(BeatSpec(), BeatSpec(), BeatSpec()),
                    nodes = listOf(
                        NodeSpec(
                            id = "clip",
                            frame = FrameSpec(0.0, 0.0, stage.width.toDouble(), stage.height.toDouble()),
                            image = image,
                            builds = builds,
                        ),
                    ),
                ),
            ),
        )
    }

    private fun harness(doc: DeckDocument, purpose: RenderPurpose = RenderPurpose.Present) = DocumentStageHarness(
        initialDocument = doc,
        initialConfig = DocumentRenderConfig(baseDensity = 1f, fontsDir = bundledFontsDirPath(), assetsRootDir = assetsDir.absolutePath),
        purpose = purpose,
    )

    private fun DocumentStageHarness.probe(): IntArray = renderCurrent().pixelRgba(width * 10 / 64, height * 20 / 48)

    /** Renders until the background decode lands (live mounts show the static first frame until then). */
    private fun DocumentStageHarness.awaitDecode(): ClipPlayhead {
        repeat(200) {
            renderStep(1_000_000L)
            if (session.hasRetained(KEY)) return session.getOrPutRetained<ClipPlayhead>(KEY) { error("present") }
            Thread.sleep(5)
        }
        error("clip never decoded")
    }

    private fun assertColor(expected: IntArray, actual: IntArray, label: String) = assertTrue(
        (0..2).all { kotlin.math.abs(expected[it] - actual[it]) <= 24 },
        "$label expected ${expected.toList()}, got ${actual.toList()}",
    )

    @Test
    fun `beats play pause at a position and step back to the recorded entry`() {
        val builds = listOf(
            Build.Playback(beat = 1, action = PlaybackAction.Play),
            Build.Playback(beat = 2, action = PlaybackAction.Pause, atMs = 700),
        )
        harness(deck(ImagePayload("clip.webp", fit = "fill"), builds)).use { h ->
            val head = h.awaitDecode()
            assertTrue(head.showPoster)
            assertColor(RED, h.probe(), "poster before play")

            h.advanceToRest(AdvanceDirection.Forward)
            assertTrue(head.playing)
            repeat(15) { h.renderStep(10_000_000L) }
            assertTrue(head.positionMs in 100.0..300.0, "playing, at ${head.positionMs}")
            assertColor(YELLOW, h.probe(), "second frame while playing")

            h.advanceToRest(AdvanceDirection.Forward)
            assertEquals(700.0, head.positionMs)
            assertFalse(head.playing)
            repeat(20) { h.renderStep(10_000_000L) }
            assertColor(BLUE, h.probe(), "paused at 700 ms")

            h.advanceToRest(AdvanceDirection.Backward)
            assertTrue(head.playing)
            assertTrue(head.positionMs < 100.0, "beat 1 replays from its entry, at ${head.positionMs}")
        }
    }

    @Test
    fun `scrubbing seeks and the next beat continues from there`() {
        val builds = listOf(Build.Playback(beat = 1, action = PlaybackAction.Play))
        harness(deck(ImagePayload("clip.webp", fit = "fill"), builds)).use { h ->
            val head = h.awaitDecode()
            h.onUiThread {
                h.scene.sendPointerEvent(PointerEventType.Enter, Offset(h.width * 0.65f, h.height * 0.5f))
                h.scene.sendPointerEvent(PointerEventType.Press, Offset(h.width * 0.65f, h.height * 0.5f))
                h.scene.sendPointerEvent(PointerEventType.Release, Offset(h.width * 0.65f, h.height * 0.5f))
            }
            h.renderStep(1_000_000L)
            assertEquals(650.0, head.positionMs, 1.0)
            assertColor(BLUE, h.probe(), "scrubbed frame")

            h.advanceToRest(AdvanceDirection.Forward)
            assertTrue(head.positionMs >= 650.0, "continues from the scrub, at ${head.positionMs}")
        }
    }

    @Test
    fun `restart request replays the current beat`() {
        val builds = listOf(Build.Playback(beat = 1, action = PlaybackAction.Play))
        harness(deck(ImagePayload("clip.webp", fit = "fill"), builds)).use { h ->
            val head = h.awaitDecode()
            h.advanceToRest(AdvanceDirection.Forward)
            repeat(40) { h.renderStep(10_000_000L) }
            assertTrue(head.positionMs > 300.0)
            h.onUiThread { DeckClipControls.requestRestart() }
            h.renderStep(1_000_000L)
            h.renderStep(1_000_000L)
            assertTrue(head.positionMs < 100.0, "restarted, at ${head.positionMs}")
            assertTrue(head.playing)
        }
    }

    @Test
    fun `non-looping clip holds its last frame and looping wraps`() {
        harness(deck(ImagePayload("clip.webp", fit = "fill"), emptyList())).use { h ->
            val head = h.awaitDecode()
            repeat(150) { h.renderStep(10_000_000L) }
            assertEquals(1000.0, head.positionMs)
            assertColor(BLUE, h.probe(), "held last frame")
        }
        harness(deck(ImagePayload("clip.webp", fit = "fill", loop = true), emptyList())).use { h ->
            val head = h.awaitDecode()
            repeat(120) { h.renderStep(10_000_000L) }
            assertTrue(head.positionMs < 1000.0, "wrapped, at ${head.positionMs}")
        }
    }

    @Test
    fun `stills show the poster frame without playing`() {
        val builds = listOf(Build.Playback(beat = 1, action = PlaybackAction.Play))
        harness(deck(ImagePayload("clip.webp", fit = "fill", posterMs = 700), builds), RenderPurpose.Still).use { h ->
            h.jumpAndSettle(dev.bnorm.storyboard.Storyboard.Index(0, 1))
            repeat(30) { h.renderStep(10_000_000L) }
            assertColor(BLUE, h.probe(), "poster at 700 ms")
            assertFalse(h.session.hasRetained(KEY), "stills never create a playhead")
        }
        harness(deck(ImagePayload("clip.webp", fit = "fill"), emptyList()), RenderPurpose.Video).use { h ->
            repeat(30) { h.renderStep(10_000_000L) }
            assertColor(RED, h.probe(), "default poster is the first frame")
        }
    }

    private companion object {
        const val KEY = "clip:s/clip"
        val RED = intArrayOf(255, 0, 0, 255)
        val YELLOW = intArrayOf(255, 255, 0, 255)
        val BLUE = intArrayOf(0, 0, 255, 255)
    }
}
