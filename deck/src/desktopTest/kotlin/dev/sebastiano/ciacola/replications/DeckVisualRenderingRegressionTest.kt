package dev.sebastiano.ciacola.replications

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.bnorm.storyboard.AdvanceDirection
import dev.bnorm.storyboard.Storyboard
import dev.bnorm.storyboard.easel.Easel
import dev.bnorm.storyboard.easel.SceneMode
import dev.bnorm.storyboard.easel.rememberAnimatic
import dev.sebastiano.ciacola.DeckCatalog
import dev.sebastiano.ciacola.DeckTextRendererProvider
import dev.sebastiano.ciacola.decks.loadClasspathDeckDocument
import dev.sebastiano.ciacola.document.RenderSession
import dev.sebastiano.ciacola.document.TransitionType
import dev.sebastiano.ciacola.document.render.DefaultMagicTextV2Renderer
import dev.sebastiano.ciacola.document.render.DocumentColorResolver
import dev.sebastiano.ciacola.document.render.DocumentRenderConfig
import dev.sebastiano.ciacola.document.render.buildDocumentStoryboard
import dev.sebastiano.ciacola.document.render.resolveActiveThemeSpec
import dev.sebastiano.ciacola.document.render.resolveDeckTheme
import dev.sebastiano.ciacola.document.render.toSlideTransitionV2
import dev.sebastiano.ciacola.effects.v2.composeSlideTransition
import dev.sebastiano.ciacola.replications.njpt.NjptJewelMarkdownSplitDemoSpecimen
import dev.sebastiano.ciacola.ui.CiacolaJewelTheme
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image as SkiaImage
import org.jetbrains.skia.ImageInfo

@OptIn(ExperimentalComposeUiApi::class, ExperimentalTestApi::class)
class DeckVisualRenderingRegressionTest {

    private fun spotRenderDir(): File? =
        System.getProperty("ciacola.spotRenderDir")?.takeIf { it.isNotBlank() }?.let(::File)?.apply { mkdirs() }

    @Test
    fun shadersS02SeparatesWhatAreShadersAndQuestionMarkWithoutOverlap() =
        runDesktopComposeUiTest(width = 1920, height = 1080) {
        val entry = DeckCatalog.shadersQuickie2025
        val fullDoc = loadClasspathDeckDocument(entry)
        val s01 = fullDoc.slides.first { it.id == "s01" }
        val s02 = fullDoc.slides.first { it.id == "s02" }
        val sliceDoc = fullDoc.copy(slides = listOf(s01, s02))
        val session = RenderSession.forVideo(sliceDoc)
        val storyboard = buildDocumentStoryboard(
            session = session,
            config = DocumentRenderConfig(
                baseDensity = 1f,
                specimens = entry.specimens,
            ),
        )

        setContent {
            CiacolaJewelTheme(isDark = false) {
                DeckTextRendererProvider(DefaultMagicTextV2Renderer) {
                    val animatic = rememberAnimatic(
                        initialIndex = Storyboard.Index(1, 0),
                        storyboard = { storyboard },
                    )
                    Box(Modifier.size(1920.dp, 1080.dp)) {
                        Easel(animatic = animatic, mode = SceneMode.Preview)
                    }
                }
            }
        }
        waitForIdle()

        val whatAreBounds = onNodeWithTag("doc-node-what-are", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val shadersBounds = onNodeWithTag("doc-node-shaders", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val qMarkBounds = onNodeWithTag("doc-node-question-mark", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot

        // Measure actual glyph advance of "what are" and "shaders" to confirm "?" sits strictly right of "shaders".
        fun actualGlyphRight(tag: String): Float {
            val node = onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode()
            val results = mutableListOf<TextLayoutResult>()
            node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
            val tlr = assertNotNull(results.firstOrNull(), "Expected TextLayoutResult on $tag")
            assertEquals(1, tlr.lineCount, "$tag must render on 1 line")
            return node.boundsInRoot.left + tlr.getLineRight(0)
        }

        val whatAreRight = actualGlyphRight("doc-node-what-are")
        val shadersRight = actualGlyphRight("doc-node-shaders")
        assertTrue(
            whatAreRight < shadersBounds.left - 8f,
            "Expected 'what are' right ($whatAreRight) to stay cleanly left of 'shaders' (${shadersBounds.left})",
        )
        assertTrue(
            qMarkBounds.left >= shadersRight - 2f,
            "Expected '?' left (${qMarkBounds.left}) to sit cleanly after 'shaders' right ($shadersRight)",
        )

        spotRenderDir()?.let { dir ->
            // Render S02 entrance start (s01 -> s02 transition=none -> entrance t=20ms) & S02 rest (1920x1080)
            val restRgba = renderDeckSliceAtRest(sliceDoc, entry.specimens, Storyboard.Index(1, 0), darkJewel = false)
            savePng(restRgba, 1920, 1080, File(dir, "shaders_s02_rest.png"))

            val entranceRgba = renderDeckSliceDuringAdvance(
                doc = sliceDoc,
                specimens = entry.specimens,
                fromIndex = Storyboard.Index(0, s01.beats.lastIndex.coerceAtLeast(0)),
                elapsedMs = 40L,
                darkJewel = false,
            )
            savePng(entranceRgba, 1920, 1080, File(dir, "shaders_s02_entrance_start.png"))
        }
        assertTrue(whatAreBounds.width > 0f)
    }

    @Test
    fun jewelS56ToS57MidPushRendersBothSlidesAtAlphaOneWithZeroBleed() {
        val entry = DeckCatalog.jewelTalk2024
        val fullDoc = loadClasspathDeckDocument(entry)
        val s56 = fullDoc.slides.first { it.id == "s56" }
        val s57 = fullDoc.slides.first { it.id == "s57" }

        val tSpec = assertNotNull(s56.transition, "Jewel s56 must declare an outgoing push transition")
        assertEquals(TransitionType.Push, tSpec.type, "Jewel s56 -> s57 must use TransitionType.Push")
        assertEquals("left", tSpec.direction)

        val activeThemeSpec = resolveActiveThemeSpec(fullDoc, fullDoc.defaultTheme)
        val baseTheme = resolveDeckTheme(fullDoc, fullDoc.defaultTheme)
        val colorResolver = DocumentColorResolver(activeThemeSpec, baseTheme, null)
        val tV2 = tSpec.toSlideTransitionV2(colorResolver, activeThemeSpec?.motion)

        for (p in listOf(0.25f, 0.5f, 0.75f)) {
            val comp = composeSlideTransition(
                transition = tV2,
                canonicalProgress = p,
                stageSize = Size(1920f, 1080f),
                reducedMotion = false,
            )
            assertEquals(1f, comp.slideA.layer.alpha, 0.0001f, "Outgoing s56 alpha must be 1.0 at p=$p")
            assertEquals(1f, comp.slideB.layer.alpha, 0.0001f, "Incoming s57 alpha must be 1.0 at p=$p")
        }

        // Render s56 -> s57 mid-push (350ms / 700ms => p = 0.5) at 1920x1080 and verify s57's incoming
        // Gemini card on the right half is crisp opaque light surface (zero dark/lavender bleed from s56).
        val sliceDoc = fullDoc.copy(slides = listOf(s56, s57))
        val midRgba = renderDeckSliceDuringAdvance(
            doc = sliceDoc,
            specimens = entry.specimens,
            fromIndex = Storyboard.Index(0, 0),
            elapsedMs = 350L,
            darkJewel = true,
        )

        spotRenderDir()?.let { dir ->
            savePng(midRgba, 1920, 1080, File(dir, "jewel_s56_to_s57_push_p050.png"))
        }

        // At p = 0.5 (push left, e ≈ 0.5), s57 is shifted by +960px, so its Gemini card (frame x=237..785, y=65..1015)
        // occupies x ≈ 1197..1745! Sample card background at (x = 1230, y = 160): must be crisp light #F5..#FF!
        val cardIdx = (160 * 1920 + 1230) * 4
        val r = midRgba[cardIdx].toInt() and 0xFF
        val g = midRgba[cardIdx + 1].toInt() and 0xFF
        val b = midRgba[cardIdx + 2].toInt() and 0xFF
        assertTrue(
            r >= 235 && g >= 235 && b >= 238,
            "Incoming s57 Gemini card mid-push at (1230, 160) must be 100% opaque light card (got RGB=($r, $g, $b))",
        )
    }

    @Test
    fun njptS019PreservesCleanWordSpaceBetweenIfYouGuessedAndHacks() =
        runDesktopComposeUiTest(width = 1920, height = 1080) {
        val entry = DeckCatalog.notJustPlainText2024
        val fullDoc = loadClasspathDeckDocument(entry)
        val s019 = fullDoc.slides.first { it.id == "s019" }
        val sliceDoc = fullDoc.copy(slides = listOf(s019))
        val session = RenderSession.forStill(sliceDoc)
        val storyboard = buildDocumentStoryboard(
            session = session,
            config = DocumentRenderConfig(
                baseDensity = 1f,
                specimens = entry.specimens,
            ),
        )

        setContent {
            CiacolaJewelTheme(isDark = false) {
                DeckTextRendererProvider(DefaultMagicTextV2Renderer) {
                    val animatic = rememberAnimatic(
                        initialIndex = Storyboard.Index(0, 1),
                        storyboard = { storyboard },
                    )
                    Box(Modifier.size(1920.dp, 1080.dp)) {
                        Easel(animatic = animatic, mode = SceneMode.Preview)
                    }
                }
            }
        }
        waitForIdle()

        val guessedNode = onNodeWithTag("doc-node-if-you-guessed", useUnmergedTree = true).fetchSemanticsNode()
        val guessedLayouts = mutableListOf<TextLayoutResult>()
        guessedNode.config[SemanticsActions.GetTextLayoutResult].action?.invoke(guessedLayouts)
        val guessedRight = guessedNode.boundsInRoot.left + assertNotNull(guessedLayouts.firstOrNull()).getLineRight(0)

        val hacksBounds = onNodeWithTag("doc-node-hacks", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val gapPx = hacksBounds.left - guessedRight
        assertTrue(
            gapPx >= 14f,
            "Expected clean word gap >= 14px between 'IF YOU GUESSED' ($guessedRight) and 'HACKS' (${hacksBounds.left}), got $gapPx",
        )

        spotRenderDir()?.let { dir ->
            val b0 = renderDeckSliceAtRest(sliceDoc, entry.specimens, Storyboard.Index(0, 0), darkJewel = false)
            val b1 = renderDeckSliceAtRest(sliceDoc, entry.specimens, Storyboard.Index(0, 1), darkJewel = false)
            savePng(b0, 1920, 1080, File(dir, "njpt_s019_beat0.png"))
            savePng(b1, 1920, 1080, File(dir, "njpt_s019_beat1.png"))
        }
    }

    @Test
    fun njptS125MarkdownTableKeepsCodeIdentifiersSingleLineWithoutMidWordWrap() =
        runDesktopComposeUiTest(width = 1920, height = 1080) {
        setContent {
            CiacolaJewelTheme(isDark = false) {
                Box(Modifier.size(1495.dp, 860.dp)) {
                    NjptJewelMarkdownSplitDemoSpecimen(
                        loopMillis = 0L,
                        isInteractive = false,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
        waitForIdle()

        for (token in listOf("MarkdownProcessor", "ExtensionSpec", "SyntaxHighlighter")) {
            val matches = onAllNodesWithText(token, substring = true, useUnmergedTree = true).fetchSemanticsNodes()
            // Raw Markdown editor sits in the left pane; rendered AST table cell sits in the right pane.
            val tableCellNode = matches.filter { it.boundsInRoot.left > 650f }.maxByOrNull { it.boundsInRoot.left }
            val node = assertNotNull(tableCellNode, "Expected rendered table cell containing '$token' (found ${matches.map { it.boundsInRoot }})")
            val results = mutableListOf<TextLayoutResult>()
            node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
            val tlr = assertNotNull(results.firstOrNull(), "Expected TextLayoutResult for '$token'")
            assertEquals(1, tlr.lineCount, "Table cell '$token' must render on a single line without mid-word wrapping")
            assertTrue(!tlr.didOverflowWidth, "Table cell '$token' must not overflow its allocated column width")
        }

        spotRenderDir()?.let { dir ->
            val entry = DeckCatalog.notJustPlainText2024
            val fullDoc = loadClasspathDeckDocument(entry)
            val s125 = fullDoc.slides.first { it.id == "s125" }
            val sliceDoc = fullDoc.copy(slides = listOf(s125))
            val rgba = renderDeckSliceAtRest(sliceDoc, entry.specimens, Storyboard.Index(0, 0), darkJewel = false)
            savePng(rgba, 1920, 1080, File(dir, "njpt_s125_table.png"))
        }
    }

    private fun renderDeckSliceAtRest(
        doc: dev.sebastiano.ciacola.document.DeckDocument,
        specimens: dev.sebastiano.ciacola.document.DeckSpecimenRegistry,
        index: Storyboard.Index,
        darkJewel: Boolean,
    ): ByteArray {
        val session = RenderSession.forVideo(doc)
        val storyboard = buildDocumentStoryboard(
            session = session,
            config = DocumentRenderConfig(
                baseDensity = 1f,
                specimens = specimens,
            ),
        )
        lateinit var animaticRef: dev.bnorm.storyboard.easel.Animatic
        val scene = ImageComposeScene(width = 1920, height = 1080, density = Density(1f)) {
            CiacolaJewelTheme(isDark = darkJewel) {
                DeckTextRendererProvider(DefaultMagicTextV2Renderer) {
                    val animatic = rememberAnimatic(initialIndex = index, storyboard = { storyboard })
                    animaticRef = animatic
                    Box(Modifier.fillMaxSize()) {
                        Easel(animatic = animatic, mode = SceneMode.Preview)
                    }
                }
            }
        }
        try {
            scene.render(0L)
            runBlocking { animaticRef.jumpTo(index) }
            scene.render(16_000_000L)
            scene.render(32_000_000L)
            val img = scene.render(48_000_000L)
            return imageToRgba(img, 1920, 1080)
        } finally {
            scene.close()
        }
    }

    private fun renderDeckSliceDuringAdvance(
        doc: dev.sebastiano.ciacola.document.DeckDocument,
        specimens: dev.sebastiano.ciacola.document.DeckSpecimenRegistry,
        fromIndex: Storyboard.Index,
        elapsedMs: Long,
        darkJewel: Boolean,
    ): ByteArray {
        val session = RenderSession.forVideo(doc)
        val storyboard = buildDocumentStoryboard(
            session = session,
            config = DocumentRenderConfig(
                baseDensity = 1f,
                specimens = specimens,
            ),
        )
        lateinit var animaticRef: dev.bnorm.storyboard.easel.Animatic
        lateinit var composeScope: CoroutineScope
        val scene = ImageComposeScene(
            width = 1920,
            height = 1080,
            density = Density(1f),
            coroutineContext = Dispatchers.Unconfined,
        ) {
            composeScope = rememberCoroutineScope()
            CiacolaJewelTheme(isDark = darkJewel) {
                DeckTextRendererProvider(DefaultMagicTextV2Renderer) {
                    val animatic = rememberAnimatic(initialIndex = fromIndex, storyboard = { storyboard })
                    animaticRef = animatic
                    Box(Modifier.fillMaxSize()) {
                        Easel(animatic = animatic, mode = SceneMode.Preview)
                    }
                }
            }
        }
        try {
            var nowNs = 0L
            scene.render(nowNs)
            nowNs += 16_000_000L
            scene.render(nowNs)
            nowNs += 16_000_000L
            scene.render(nowNs)

            val job = composeScope.launch {
                animaticRef.advance(AdvanceDirection.Forward)
            }
            nowNs += 16_000_000L
            scene.render(nowNs)
            val startNs = nowNs
            while ((nowNs - startNs) < elapsedMs * 1_000_000L) {
                nowNs += 10_000_000L
                scene.render(nowNs)
            }
            val img = scene.render(nowNs)
            job.cancel()
            return imageToRgba(img, 1920, 1080)
        } finally {
            scene.close()
        }
    }

    private fun imageToRgba(skiaImg: SkiaImage, width: Int, height: Int): ByteArray {
        val bmp = Bitmap()
        bmp.allocPixels(ImageInfo(width, height, ColorType.RGBA_8888, ColorAlphaType.UNPREMUL))
        assertTrue(skiaImg.readPixels(bmp, 0, 0))
        return bmp.readPixels(bmp.imageInfo, width * 4, 0, 0)!!
    }

    private fun savePng(rgba: ByteArray, width: Int, height: Int, outFile: File) {
        val info = ImageInfo(width, height, ColorType.RGBA_8888, ColorAlphaType.UNPREMUL)
        val img = SkiaImage.makeRaster(info, rgba, width * 4)
        val png = img.encodeToData(EncodedImageFormat.PNG) ?: return
        outFile.writeBytes(png.bytes)
    }
}
