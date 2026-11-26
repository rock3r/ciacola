package dev.sebastiano.ciacola.studio

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.bnorm.storyboard.Storyboard
import dev.bnorm.storyboard.easel.Easel
import dev.bnorm.storyboard.easel.overview.EaselOverview
import dev.bnorm.storyboard.easel.overview.LocalOverviewSlideTitleProvider
import dev.bnorm.storyboard.easel.overview.LocalOverviewThumbnailFrameProvider
import dev.bnorm.storyboard.easel.overview.rememberOverviewVisibility
import dev.bnorm.storyboard.easel.rememberAnimatic
import dev.sebastiano.ciacola.DeckCatalog
import dev.sebastiano.ciacola.DeckTextRendererProvider
import dev.sebastiano.ciacola.decks.AllDocumentDecks
import dev.sebastiano.ciacola.decks.loadClasspathDeckDocument
import dev.sebastiano.ciacola.document.BeatSpec
import dev.sebastiano.ciacola.document.Build
import dev.sebastiano.ciacola.document.DeckDocument
import dev.sebastiano.ciacola.document.FrameSpec
import dev.sebastiano.ciacola.document.NodeSpec
import dev.sebastiano.ciacola.document.RenderSession
import dev.sebastiano.ciacola.document.RichTextValue
import dev.sebastiano.ciacola.document.SlideSpec
import dev.sebastiano.ciacola.document.TextPayload
import dev.sebastiano.ciacola.document.render.DefaultMagicTextV2Renderer
import dev.sebastiano.ciacola.document.render.DocumentRenderConfig
import dev.sebastiano.ciacola.document.render.buildDocumentStoryboard
import dev.sebastiano.ciacola.ui.CiacolaJewelTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class OverviewDelayedEntranceThumbnailTest {

    @Test
    fun representativeOverviewBeatIndexPicksPopulatedBeatForDelayedEntranceSlides() {
        val shadersDoc = loadClasspathDeckDocument(DeckCatalog.byId(AllDocumentDecks.shadersQuickie2025.id))
        val shadersS01 = shadersDoc.slides.first { it.id == "s01" }
        assertEquals(1, representativeOverviewBeatIndex(shadersS01), "shaders-quickie-2025 s01 hero enters on beat 1")

        val jewelDoc = loadClasspathDeckDocument(DeckCatalog.byId(AllDocumentDecks.jewelTalk2024.id))
        val jewelS18 = jewelDoc.slides.first { it.id == "s18" }
        assertEquals(
            0,
            representativeOverviewBeatIndex(jewelS18),
            "Slides that already have visible Beat-0 content (jewel-talk-2024 s18) preserve Beat 0",
        )

        val beat0Populated = SlideSpec(
            id = "static",
            beats = listOf(BeatSpec(), BeatSpec(), BeatSpec()),
            nodes = listOf(
                NodeSpec(id = "n1", text = TextPayload(value = RichTextValue.Plain("Always visible"))),
                NodeSpec(
                    id = "bullet-2",
                    text = TextPayload(value = RichTextValue.Plain("Later bullet")),
                    builds = listOf(Build.Enter(beat = 1, id = "b1", effect = "dissolve")),
                ),
                NodeSpec(
                    id = "bullet-3",
                    text = TextPayload(value = RichTextValue.Plain("Final bullet")),
                    builds = listOf(Build.Enter(beat = 2, id = "b2", effect = "dissolve")),
                ),
            ),
        )
        assertEquals(
            0,
            representativeOverviewBeatIndex(beat0Populated),
            "Bullet-reveal slides that already have visible Beat-0 content must keep Beat 0 in Overview",
        )
    }

    @Test
    fun productionOverviewSlideProvidersPopulateDelayedEntranceThumbnailsWithRealControl() = runComposeUiTest {
        val delayedSlide = SlideSpec(
            id = "s01",
            title = "Delayed Hero",
            beats = listOf(BeatSpec(), BeatSpec()),
            nodes = listOf(
                NodeSpec(
                    id = "hero-title",
                    frame = FrameSpec(x = 120.0, y = 200.0, w = 800.0, h = 120.0),
                    text = TextPayload(value = RichTextValue.Plain("Shaders are awesome")),
                    builds = listOf(Build.Enter(beat = 1, id = "b1", effect = "dissolve")),
                ),
            ),
        )
        val fillerSlides = List(13) { idx ->
            SlideSpec(
                id = "s${idx + 2}",
                title = "Slide ${idx + 2}",
                beats = listOf(BeatSpec()),
                nodes = listOf(
                    NodeSpec(
                        id = "t-${idx + 2}",
                        frame = FrameSpec(x = 120.0, y = 200.0, w = 600.0, h = 80.0),
                        text = TextPayload(value = RichTextValue.Plain("Filler ${idx + 2}")),
                    ),
                ),
            )
        }
        val doc = DeckDocument(
            id = "overview-delayed-test",
            title = "Overview Delayed Test",
            slides = listOf(delayedSlide) + fillerSlides,
        )
        val config = DocumentRenderConfig()
        val liveSession = RenderSession.forStill(documentSnapshot = doc)
        val liveStoryboard = buildDocumentStoryboard(session = liveSession, config = config)
        val overviewStoryboard = buildPerThumbnailOverviewStoryboard(frozenDoc = doc, config = config)

        var useProductionProvider by mutableStateOf(false)
        lateinit var capturedProviders: OverviewSlideProviders
        lateinit var animatic: dev.bnorm.storyboard.easel.Animatic
        lateinit var visibility: dev.bnorm.storyboard.easel.overview.OverviewVisibility

        setContent {
            val stateAnimatic = rememberAnimatic(
                initialIndex = Storyboard.Index(1, 0),
                storyboard = { liveStoryboard },
            )
            animatic = stateAnimatic
            visibility = rememberOverviewVisibility(initiallyVisible = true)
            capturedProviders = rememberOverviewSlideProviders(doc.slides)
            DeckTextRendererProvider(DefaultMagicTextV2Renderer) {
                CiacolaJewelTheme(isDark = true) {
                    CompositionLocalProvider(
                        LocalOverviewSlideTitleProvider provides (if (useProductionProvider) capturedProviders.titleProvider else null),
                        LocalOverviewThumbnailFrameProvider provides (if (useProductionProvider) capturedProviders.thumbnailFrameProvider else null),
                    ) {
                        Box(Modifier.size(1280.dp, 800.dp)) {
                            EaselOverview(
                                animatic = animatic,
                                visibility = visibility,
                                previewStoryboard = overviewStoryboard,
                            ) {
                                Easel(animatic)
                            }
                        }
                    }
                }
            }
        }
        mainClock.advanceTimeBy(600)
        waitForIdle()

        // Verify production provider behavior including currentFrameIdx > 0 preservation.
        assertEquals("Delayed Hero", capturedProviders.titleProvider(0))
        assertEquals(1, capturedProviders.thumbnailFrameProvider(0, 0))
        assertEquals(1, capturedProviders.thumbnailFrameProvider(0, 1))
        assertEquals(0, capturedProviders.thumbnailFrameProvider(1, 0))

        // Control: without LocalOverviewThumbnailFrameProvider, delayed Beat-1 hero renders at alpha 0 on Beat 0
        // (text pixels in the hero band match uniform stage background, i.e. visually blank thumbnail).
        onNodeWithTag("easel-overview-grid-card-0", useUnmergedTree = true).assertIsDisplayed()
        val controlPixels = onNodeWithTag("easel-overview-grid-card-0", useUnmergedTree = true)
            .captureToImage().toPixelMap()
        val heroSampleBg = controlPixels[(controlPixels.width * 0.1f).toInt(), (controlPixels.height * 0.25f).toInt()]
        var controlHeroInkPixels = 0
        for (y in (controlPixels.height * 0.15f).toInt()..(controlPixels.height * 0.55f).toInt()) {
            for (x in (controlPixels.width * 0.08f).toInt()..(controlPixels.width * 0.75f).toInt()) {
                if (controlPixels[x, y] != heroSampleBg) controlHeroInkPixels++
            }
        }
        assertEquals(0, controlHeroInkPixels, "Control failed: Beat 0 hero band should be visually blank without provider")

        // Treatment: enabling production rememberOverviewSlideProviders populates card 0 visually on Beat 1.
        useProductionProvider = true
        mainClock.advanceTimeBy(600)
        waitForIdle()
        assertTrue(
            onAllNodesWithText("Shaders are awesome", substring = true, useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty(),
            "Expected s01 Overview thumbnail to display Beat-1 hero content with production provider",
        )
        val treatmentPixels = onNodeWithTag("easel-overview-grid-card-0", useUnmergedTree = true)
            .captureToImage().toPixelMap()
        var treatmentHeroInkPixels = 0
        for (y in (treatmentPixels.height * 0.15f).toInt()..(treatmentPixels.height * 0.55f).toInt()) {
            for (x in (treatmentPixels.width * 0.08f).toInt()..(treatmentPixels.width * 0.75f).toInt()) {
                if (treatmentPixels[x, y] != heroSampleBg) treatmentHeroInkPixels++
            }
        }
        assertTrue(
            treatmentHeroInkPixels > 50,
            "Expected visible hero ink pixels on card 0 with production provider; got $treatmentHeroInkPixels",
        )

        // First click on card 0 jumps animatic to (0, 0); second click closes Overview cleanly.
        onNodeWithTag("easel-overview-grid-card-0", useUnmergedTree = true).performClick()
        mainClock.advanceTimeBy(600)
        waitForIdle()
        assertEquals(Storyboard.Index(0, 0), animatic.targetIndex)

        onNodeWithTag("easel-overview-grid-card-0", useUnmergedTree = true).performClick()
        mainClock.advanceTimeBy(1_000)
        waitForIdle()
        assertEquals(false, visibility.isVisible)
    }
}
