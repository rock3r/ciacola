package dev.sebastiano.ciacola.studio

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.bnorm.storyboard.AdvanceDirection
import dev.bnorm.storyboard.easel.Animatic
import dev.bnorm.storyboard.easel.Easel
import dev.bnorm.storyboard.easel.LocalSceneMode
import dev.bnorm.storyboard.easel.SceneMode
import dev.bnorm.storyboard.easel.rememberAnimatic
import dev.sebastiano.ciacola.createLoadedTestDeckStore
import dev.sebastiano.ciacola.document.BeatSpec
import dev.sebastiano.ciacola.document.FrameSpec
import dev.sebastiano.ciacola.document.NodeSpec
import dev.sebastiano.ciacola.document.RenderSession
import dev.sebastiano.ciacola.document.RichTextValue
import dev.sebastiano.ciacola.document.SlideSpec
import dev.sebastiano.ciacola.document.TextPayload
import dev.sebastiano.ciacola.document.render.DocumentRenderConfig
import dev.sebastiano.ciacola.document.render.rememberDocumentStoryboard
import dev.sebastiano.ciacola.inspector.element.DocumentElementInspectorController
import dev.sebastiano.ciacola.inspector.element.DocumentStageDirectEditOverlay
import dev.sebastiano.ciacola.loadFoundationTestDocument
import dev.sebastiano.ciacola.ui.CiacolaJewelTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Verifies UX-02 on document decks: after `Next Beat` onto Slide 1's last beat in a multi-slide
 * [dev.sebastiano.ciacola.document.DeckDocument], `animatic.presentedIndex.sceneIndex` stays `0`
 * so clicking `pick:s1:subtitle` via [DocumentStageDirectEditOverlay] selects it cleanly.
 */
@OptIn(ExperimentalTestApi::class)
class NextBeatElementPickingTest {

    @Test
    fun nextBeatOntoSlide1LastBeatPreservesPresentedSceneAndAllowsSelectingSubtitle() = runComposeUiTest {
        val baseDoc = loadFoundationTestDocument()
        val fixtureDoc = baseDoc.copy(
            id = "next-beat-picking-fixture",
            title = "Next Beat Picking Fixture",
            slides = listOf(
                SlideSpec(
                    id = "slide-1",
                    title = "Opening Slide",
                    beats = listOf(BeatSpec(), BeatSpec()),
                    nodes = listOf(
                        NodeSpec(
                            id = "pick:s1:title",
                            frame = FrameSpec(x = 128.0, y = 160.0, w = 1200.0, h = 120.0),
                            text = TextPayload(value = RichTextValue.Plain("Opening Slide Title"), style = "hero", color = "foreground"),
                        ),
                        NodeSpec(
                            id = "pick:s1:subtitle",
                            frame = FrameSpec(x = 128.0, y = 340.0, w = 800.0, h = 64.0),
                            text = TextPayload(value = RichTextValue.Plain("SUBTITLE ON FINAL BEAT"), style = "eyebrow", color = "accent"),
                        ),
                    ),
                ),
                SlideSpec(
                    id = "slide-2",
                    title = "Second Slide",
                    beats = listOf(BeatSpec()),
                    nodes = listOf(
                        NodeSpec(
                            id = "pick:s2:title",
                            frame = FrameSpec(x = 128.0, y = 160.0, w = 1200.0, h = 120.0),
                            text = TextPayload(value = RichTextValue.Plain("Second Slide Title"), style = "title", color = "foreground"),
                        ),
                    ),
                ),
            ),
        )
        val store = createLoadedTestDeckStore(fixtureDoc)
        lateinit var animatic: Animatic
        lateinit var elementController: DocumentElementInspectorController
        lateinit var scope: CoroutineScope

        setContent {
            scope = rememberCoroutineScope()
            elementController = remember { DocumentElementInspectorController(store = store) }
            val session = remember { RenderSession.forPresent(store, freeClockMillis = { 0L }) }
            val storyboard = rememberDocumentStoryboard(
                session = session,
                config = DocumentRenderConfig(themeName = "dark"),
            )
            animatic = rememberAnimatic(storyboard = { storyboard })
            SideEffect {
                val nonSkipped = store.document.slides.filterNot { it.skipped }
                val activeSlideId = nonSkipped.getOrNull(animatic.presentedIndex.sceneIndex)?.id
                if (activeSlideId != null) {
                    elementController.activeSlideId = activeSlideId
                    elementController.activeBeat = animatic.presentedIndex.frameIndex.coerceAtLeast(0)
                }
            }

            CompositionLocalProvider(LocalSceneMode provides SceneMode.Story) {
                CiacolaJewelTheme(isDark = true) {
                    Box(Modifier.size(960.dp, 540.dp)) {
                        DocumentStageDirectEditOverlay(
                            controller = elementController,
                            stageScale = 0.5f,
                            enabled = true,
                        ) {
                            Easel(animatic)
                        }
                    }
                }
            }
        }
        waitForIdle()

        // Advance Next Beat onto Slide 1's last beat (S1 has 2 beats: B0 -> B1).
        runOnIdle {
            scope.launch { animatic.advance(AdvanceDirection.Forward) }
        }
        mainClock.advanceTimeBy(1_200)
        waitForIdle()

        val lastBeatOfSlide1 = animatic.storyboard.scenes[0].frames.lastIndex
        assertEquals(
            0,
            animatic.presentedIndex.sceneIndex,
            "After Next Beat onto Slide 1's last beat, presentedIndex.sceneIndex must remain 0",
        )
        assertEquals(lastBeatOfSlide1, animatic.presentedIndex.frameIndex)

        // Clicking `pick:s1:subtitle` hit region on the live stage selects it cleanly.
        onNodeWithTag("ciacola-doc-stage-node-pick:s1:subtitle").performClick()
        waitForIdle()

        assertEquals(
            "pick:s1:subtitle",
            elementController.selectedNodeId,
            "Clicking pick:s1:subtitle on Slide 1's last beat must select pick:s1:subtitle",
        )
    }
}
