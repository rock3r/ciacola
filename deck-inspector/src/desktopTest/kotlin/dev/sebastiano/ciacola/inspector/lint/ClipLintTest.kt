package dev.sebastiano.ciacola.inspector.lint

import dev.sebastiano.ciacola.document.BeatSpec
import dev.sebastiano.ciacola.document.Build
import dev.sebastiano.ciacola.document.DeckDocument
import dev.sebastiano.ciacola.document.FrameSpec
import dev.sebastiano.ciacola.document.ImagePayload
import dev.sebastiano.ciacola.document.NodeSpec
import dev.sebastiano.ciacola.document.PlaybackAction
import dev.sebastiano.ciacola.document.SlideSpec
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking

class ClipLintTest {
    @Test
    fun `clip lint flags missing, still and over-budget sources`() {
        val assets = Files.createTempDirectory("clip-lint")
        try {
            Files.write(assets.resolve("clip.webp"), Base64.getDecoder().decode(SAMPLE_WEBP))
            ImageIO.write(BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB), "png", assets.resolve("still.png").toFile())
            val play = listOf(Build.Playback(beat = 1, action = PlaybackAction.Play))
            fun node(id: String, image: ImagePayload, builds: List<Build> = emptyList()) =
                NodeSpec(id = id, frame = FrameSpec(0.0, 0.0, 100.0, 100.0), image = image, builds = builds)
            val doc = DeckDocument(
                id = "d",
                slides = listOf(
                    SlideSpec(
                        id = "s",
                        beats = listOf(BeatSpec(), BeatSpec()),
                        nodes = listOf(
                            node("missing", ImagePayload("gone.webp"), play),
                            node("still", ImagePayload("still.png", loop = true)),
                            node("tight", ImagePayload("clip.webp", maxPixels = 100), play),
                            node("ok", ImagePayload("clip.webp"), play),
                            node("plain", ImagePayload("still.png")),
                        ),
                    ),
                ),
            )
            val report = DocumentDeckLintEngine().use { engine ->
                runBlocking { engine.lintSingleSlide(document = doc, slideIndex = 0, assetsDir = assets) }
            }
            val clipCodes = report.issues
                .filter { it.code.startsWith("CLIP_") }
                .map { it.nodeId to it.code }
                .toSet()
            assertEquals(
                setOf(
                    "missing" to "CLIP_SOURCE_MISSING",
                    "still" to "CLIP_SOURCE_NOT_ANIMATED",
                    "tight" to "CLIP_OVER_BUDGET",
                ),
                clipCodes,
            )
        } finally {
            assets.toFile().deleteRecursively()
        }
    }

    private fun node(id: String, image: ImagePayload, builds: List<Build> = emptyList()) =
        NodeSpec(id = id, frame = FrameSpec(0.0, 0.0, 100.0, 100.0), image = image, builds = builds)

    private fun lint(doc: DeckDocument, assets: java.nio.file.Path?) = DocumentDeckLintEngine().use { engine ->
        runBlocking { engine.lintSingleSlide(document = doc, slideIndex = 0, assetsDir = assets) }
    }.issues.filter { it.code.startsWith("CLIP_") }

    private fun deck(id: String, vararg nodes: NodeSpec) =
        DeckDocument(id = id, slides = listOf(SlideSpec(id = "s", beats = listOf(BeatSpec(), BeatSpec()), nodes = nodes.toList())))

    @Test
    fun `clip lint reads classpath assets when no deck folder is known`() {
        val issues = lint(deck("clip-lint-cp", node("tight", ImagePayload("clip.webp", maxPixels = 100))), assets = null)
        assertEquals(listOf("CLIP_OVER_BUDGET"), issues.map { it.code })
    }

    @Test
    fun `only gif and webp sources animate, whatever their bytes`() {
        val assets = Files.createTempDirectory("clip-lint-ext")
        try {
            Files.write(assets.resolve("anim.png"), Base64.getDecoder().decode(SAMPLE_WEBP))
            val issues = lint(deck("d", node("misnamed", ImagePayload("anim.png", loop = true))), assets)
            assertEquals(listOf("CLIP_SOURCE_NOT_ANIMATED"), issues.map { it.code })
        } finally {
            assets.toFile().deleteRecursively()
        }
    }

    @Test
    fun `a clip over the side limit is not told to raise maxPixels`() {
        val assets = Files.createTempDirectory("clip-lint-side")
        try {
            Files.write(assets.resolve("wide.gif"), wideGif(width = 8193))
            val issue = lint(deck("d", node("wide", ImagePayload("wide.gif"))), assets).single()
            assertEquals("CLIP_OVER_BUDGET", issue.code)
            kotlin.test.assertTrue("8192" in issue.detail && "maxPixels" !in issue.detail, issue.detail)
        } finally {
            assets.toFile().deleteRecursively()
        }
    }

    /** A 2-frame GIF whose logical screen is [width] x 1, each frame a single pixel. */
    private fun wideGif(width: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write("GIF89a".toByteArray())
        out.write(byteArrayOf(width.toByte(), (width shr 8).toByte(), 1, 0, 0x80.toByte(), 0, 0, -1, -1, -1, 0, 0, 0))
        repeat(2) {
            out.write(byteArrayOf(0x21, 0xF9.toByte(), 4, 0, 10, 0, 0, 0))
            out.write(byteArrayOf(0x2C, 0, 0, 0, 0, 1, 0, 1, 0, 0, 2, 2, 0x44, 1, 0))
        }
        out.write(0x3B)
        return out.toByteArray()
    }

    private companion object {
        /** The CC0 4-frame 64x48 animated WebP fixture from `deck-effects` test resources. */
        const val SAMPLE_WEBP =
            "UklGRvoAAABXRUJQVlA4WAoAAAASAAAAPwAALwAAQU5JTQYAAAAAAAAAAABBTk1GKAAAAAAAAAAAAD8AAC8AAGQAAAJWUDhMDwAAAC8/wAsABxD9j/4HIqL/AQBBTk1GKAAAAAQAAAgAAA8AAA8AAMgAAABWUDhMEAAAAC8PwAMAB9D//kf/AxHR/wBBTk1GNAAAAAQAAAgAAC8AAA8AACwBAABWUDhMHAAAAC8vwAMQFzD/8x/zH3AgJCCcdNJYOaL/oQpQtRxBTk1GMgAAAAAAAAAAAD8AAC8AAJABAAJWUDhMGQAAAC8/wAsQDxAx//MfDLFgMn/pFDoj+h9VsB0A"
    }
}
