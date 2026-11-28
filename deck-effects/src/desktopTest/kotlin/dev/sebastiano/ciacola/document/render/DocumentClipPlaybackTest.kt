package dev.sebastiano.ciacola.document.render

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
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
import dev.sebastiano.ciacola.document.RenderSession
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

    private fun harness(
        doc: DeckDocument,
        purpose: RenderPurpose = RenderPurpose.Present,
        session: RenderSession? = null,
        clipScrubbing: Boolean = true,
    ) = DocumentStageHarness(
        initialDocument = doc,
        initialConfig = DocumentRenderConfig(
            baseDensity = 1f,
            fontsDir = bundledFontsDirPath(),
            assetsRootDir = assetsDir.absolutePath,
            clipScrubbing = clipScrubbing,
        ),
        existingSession = session,
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

    private fun DocumentStageHarness.send(
        type: PointerEventType,
        fraction: Float,
        buttons: PointerButtons? = null,
        modifiers: PointerKeyboardModifiers? = null,
    ) = onUiThread {
        scene.sendPointerEvent(type, Offset(width * fraction, height * 0.5f), buttons = buttons, keyboardModifiers = modifiers)
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
    fun `scrubbing holds the frame until release and the next beat continues from there`() {
        val builds = listOf(Build.Playback(beat = 1, action = PlaybackAction.Play))
        harness(deck(ImagePayload("clip.webp", fit = "fill"), builds)).use { h ->
            val head = h.awaitDecode()
            h.advanceToRest(AdvanceDirection.Forward)
            val beat = h.animatic.currentIndex
            fun send(type: PointerEventType, fraction: Float) = h.send(type, fraction)
            send(PointerEventType.Enter, 0.65f)
            send(PointerEventType.Press, 0.65f)
            repeat(20) { h.renderStep(10_000_000L) }
            assertEquals(650.0, head.positionMs, 1.0, "held while the button is down")
            assertColor(BLUE, h.probe(), "scrubbed frame")
            send(PointerEventType.Move, 0.2f)
            repeat(20) { h.renderStep(10_000_000L) }
            assertEquals(200.0, head.positionMs, 1.0, "dragging moves the held frame")
            send(PointerEventType.Release, 0.2f)
            repeat(10) { h.renderStep(10_000_000L) }
            assertTrue(head.positionMs > 200.0 && head.playing, "plays on after release, at ${head.positionMs}")
            assertEquals(beat, h.animatic.currentIndex, "scrubbing never changes beat")

            h.advanceToRest(AdvanceDirection.Forward)
            assertTrue(head.positionMs > 200.0, "continues from the scrub, at ${head.positionMs}")
        }
    }

    @Test
    fun `dragging past the edge clamps to the end and releasing outside lets go`() {
        harness(deck(ImagePayload("clip.webp", fit = "fill"), emptyList())).use { h ->
            val head = h.awaitDecode()
            h.send(PointerEventType.Enter, 0.5f)
            h.send(PointerEventType.Press, 0.5f)
            h.send(PointerEventType.Exit, 1.2f)
            h.renderStep(1_000_000L)
            assertEquals(1000.0, head.positionMs, 1.0, "the step that crosses the edge still seeks")
            h.send(PointerEventType.Release, 1.2f)
            h.renderStep(1_000_000L)
            assertFalse(head.scrubbing)
        }
    }

    @Test
    fun `only a plain primary click in show mode scrubs`() {
        harness(deck(ImagePayload("clip.webp", fit = "fill"), emptyList())).use { h ->
            val head = h.awaitDecode()
            h.onUiThread { DeckClipControls.requestTogglePause() }
            h.renderStep(1_000_000L)
            val paused = head.positionMs
            h.send(PointerEventType.Press, 0.65f, modifiers = PointerKeyboardModifiers(isCtrlPressed = true))
            h.send(PointerEventType.Release, 0.65f)
            h.send(PointerEventType.Press, 0.65f, buttons = PointerButtons(isSecondaryPressed = true))
            h.send(PointerEventType.Release, 0.65f)
            h.renderStep(1_000_000L)
            assertEquals(paused, head.positionMs, "Ctrl+Click and right-click leave the clip alone")
        }
        harness(deck(ImagePayload("clip.webp", fit = "fill"), emptyList()), clipScrubbing = false).use { h ->
            val head = h.awaitDecode()
            h.onUiThread { DeckClipControls.requestTogglePause() }
            h.renderStep(1_000_000L)
            val paused = head.positionMs
            h.send(PointerEventType.Press, 0.65f)
            h.send(PointerEventType.Release, 0.65f)
            h.renderStep(1_000_000L)
            assertEquals(paused, head.positionMs, "no scrubbing outside show mode")
        }
    }

    @Test
    fun `a clip hidden until its enter beat neither plays nor takes clicks`() {
        val builds = listOf(Build.Enter(beat = 1, effect = "fade"))
        harness(deck(ImagePayload("clip.webp", fit = "fill"), builds)).use { h ->
            val head = h.awaitDecode()
            repeat(30) { h.renderStep(10_000_000L) }
            h.send(PointerEventType.Press, 0.65f)
            h.send(PointerEventType.Release, 0.65f)
            h.renderStep(1_000_000L)
            assertEquals(0.0, head.positionMs, "held while hidden")
            assertFalse(head.playing)
            h.advanceToRest(AdvanceDirection.Forward)
            repeat(10) { h.renderStep(10_000_000L) }
            assertTrue(head.playing && head.positionMs > 0.0, "plays once shown, at ${head.positionMs}")
        }
    }

    @Test
    fun `a clip decoded by another mount shows its poster on the first frame`() {
        val doc = deck(
            ImagePayload("clip.webp", fit = "fill", posterMs = 700),
            listOf(Build.Playback(beat = 1, action = PlaybackAction.Play)),
        )
        harness(doc).use { it.awaitDecode() }
        harness(doc).use { h ->
            h.renderStep(1_000_000L)
            assertColor(BLUE, h.probe(), "poster from the cache, no first-frame flash")
        }
    }

    @Test
    fun `pause request toggles the clip`() {
        harness(deck(ImagePayload("clip.webp", fit = "fill"), emptyList())).use { h ->
            val head = h.awaitDecode()
            repeat(20) { h.renderStep(10_000_000L) }
            h.onUiThread { DeckClipControls.requestTogglePause() }
            h.renderStep(1_000_000L)
            val paused = head.positionMs
            repeat(20) { h.renderStep(10_000_000L) }
            assertEquals(paused, head.positionMs, "paused")
            h.onUiThread { DeckClipControls.requestTogglePause() }
            repeat(20) { h.renderStep(10_000_000L) }
            assertTrue(head.positionMs > paused, "resumed")
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
    fun `a mirroring preview shows the stage clip's frame instead of its poster`() {
        val builds = listOf(
            Build.Playback(beat = 1, action = PlaybackAction.Play),
            Build.Playback(beat = 2, action = PlaybackAction.Pause, atMs = 700),
        )
        val doc = deck(ImagePayload("clip.webp", fit = "fill"), builds)
        harness(doc).use { stage ->
            stage.awaitDecode()
            val previewSession = RenderSession.forStill(doc).also { it.clipMirrorSource = stage.session }
            harness(doc, RenderPurpose.Still, previewSession).use { preview ->
                stage.advanceToRest(AdvanceDirection.Forward)
                stage.advanceToRest(AdvanceDirection.Forward)
                preview.jumpAndSettle(dev.bnorm.storyboard.Storyboard.Index(0, 2))
                repeat(200) {
                    preview.renderStep(10_000_000L)
                    if (preview.probe().contentEquals(BLUE)) return@repeat
                    Thread.sleep(5)
                }
                assertColor(BLUE, preview.probe(), "preview mirrors the stage paused at 700 ms")
                assertFalse(previewSession.hasRetained(KEY), "the mirror never creates its own playhead")
            }
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
        const val KEY = "clip:s/clip/clip.webp"
        val RED = intArrayOf(255, 0, 0, 255)
        val YELLOW = intArrayOf(255, 255, 0, 255)
        val BLUE = intArrayOf(0, 0, 255, 255)
    }
}
