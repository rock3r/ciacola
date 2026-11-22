package dev.sebastiano.ciacola.presenter

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.bnorm.storyboard.Storyboard
import dev.bnorm.storyboard.easel.rememberAnimatic
import dev.sebastiano.ciacola.document.BeatSpec
import dev.sebastiano.ciacola.document.DeckDocument
import dev.sebastiano.ciacola.document.NodeSpec
import dev.sebastiano.ciacola.document.RenderSession
import dev.sebastiano.ciacola.document.RichTextValue
import dev.sebastiano.ciacola.document.SlideSpec
import dev.sebastiano.ciacola.document.TextPayload
import dev.sebastiano.ciacola.document.render.DocumentRenderConfig
import dev.sebastiano.ciacola.document.render.buildDocumentStoryboard
import dev.sebastiano.ciacola.ui.CiacolaJewelTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class PresenterUxPolishTest {

    private fun bridgeDeckDocument(): DeckDocument = DeckDocument(
        id = "presenter-bridge",
        title = "Bridge Test Deck",
        slides = listOf(
            SlideSpec(
                id = "njpt.s01",
                title = "njpt.s01",
                beats = listOf(
                    BeatSpec(notes = ""),
                    BeatSpec(notes = "", autoAdvanceMs = 0), // 0ms bridge beat
                ),
                nodes = listOf(
                    NodeSpec(id = "n1", text = TextPayload(value = RichTextValue.Plain("Slide 1"))),
                ),
            ),
            SlideSpec(
                id = "njpt.s02",
                title = "Live Demo",
                beats = listOf(
                    BeatSpec(notes = "Landing beat"),
                    BeatSpec(notes = "Follow-up reveal"),
                ),
                nodes = listOf(
                    NodeSpec(id = "n2", text = TextPayload(value = RichTextValue.Plain("Slide 2"))),
                ),
            ),
            SlideSpec(
                id = "njpt.s03",
                title = "Closing Summary",
                beats = listOf(BeatSpec(notes = "Wrap up")),
                nodes = listOf(
                    NodeSpec(id = "n3", text = TextPayload(value = RichTextValue.Plain("Slide 3"))),
                ),
            ),
        ),
    )

    @Test
    fun presenterSkipsZeroMsBridgeBeatSetsTransitioningFalseWhenSettledAndUsesJsonNotesHint() = runComposeUiTest {
        val doc = bridgeDeckDocument()
        val storyboard = buildDocumentStoryboard(
            session = RenderSession.forStill(doc),
            config = DocumentRenderConfig(),
        )
        lateinit var controller: PresenterController

        setContent {
            val animatic = rememberAnimatic(storyboard = { storyboard })
            controller = remember(animatic) {
                PresenterController(
                    initialAnimatic = animatic,
                    documentProvider = { doc },
                    previewStoryboardProvider = { storyboard },
                    nextPreviewStoryboardProvider = { storyboard },
                )
            }
            CiacolaJewelTheme(isDark = true) {
                Box(Modifier.size(1_100.dp, 720.dp)) {
                    PresenterDashboard(controller = controller)
                }
            }
        }
        waitForIdle()

        // 1. Settled slide must NOT report isTransitioning == true (prevents stuck "Transitioning" badge).
        assertFalse(controller.isTransitioning, "Settled slide must report Ready (isTransitioning == false)")

        // 2. From Slide 1 Beat 0, Next Preview skips Slide 1 Beat 1 (autoAdvanceMs == 0 bridge) -> Slide 2 Beat 0!
        assertEquals(
            Storyboard.Index(sceneIndex = 1, frameIndex = 0),
            controller.nextPreviewIndex,
            "nextPreviewIndex must skip 0ms bridge beat so Next never mirrors Current",
        )
        assertTrue(
            controller.nextStepLabel().contains("Next Slide (#2)"),
            "Next label must include 1-based slide number: ${controller.nextStepLabel()}",
        )

        // 3. Humanized internal slide title strips `njpt.` prefix.
        assertFalse(
            controller.currentSlideTitle().contains("njpt."),
            "Internal prefix 'njpt.' must be stripped from presenter title: ${controller.currentSlideTitle()}",
        )

        // 4. Empty speaker notes read in plain language, never as a file-format instruction.
        onNodeWithText("No notes for this slide", useUnmergedTree = true).assertIsDisplayed()
        assertTrue(onAllNodesWithText("beats[i].notes", substring = true, useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
    }
}
