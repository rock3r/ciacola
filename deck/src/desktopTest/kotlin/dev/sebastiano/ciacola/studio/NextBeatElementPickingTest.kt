package dev.sebastiano.ciacola.studio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.bnorm.storyboard.AdvanceDirection
import dev.bnorm.storyboard.Storyboard
import dev.bnorm.storyboard.easel.Animatic
import dev.bnorm.storyboard.easel.Easel
import dev.bnorm.storyboard.easel.LocalSceneMode
import dev.bnorm.storyboard.easel.SceneMode
import dev.bnorm.storyboard.easel.rememberAnimatic
import dev.sebastiano.ciacola.authoring.DeckHostParams
import dev.sebastiano.ciacola.authoring.ciacolaDeck
import dev.sebastiano.ciacola.effects.DeckEditableText
import dev.sebastiano.ciacola.inspector.LocalStageDragPreview
import dev.sebastiano.ciacola.inspector.LocalStageInteractiveRegistry
import dev.sebastiano.ciacola.inspector.StageDragPreview
import dev.sebastiano.ciacola.inspector.StageInspectionRegistry
import dev.sebastiano.ciacola.inspector.StageInteractiveRegistry
import dev.sebastiano.ciacola.inspector.element.ElementEditorController
import dev.sebastiano.ciacola.inspector.element.ElementOverridesStore
import dev.sebastiano.ciacola.inspector.element.rememberElementEditorController
import dev.sebastiano.ciacola.theme.DeckThemePreset
import dev.sebastiano.ciacola.theme.DeckThemes
import dev.sebastiano.ciacola.theme.LocalDeckTheme
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Verifies UX-02: after `Next Beat` onto Slide 1's last beat in a multi-slide `ciacolaDeck`,
 * `animatic.presentedIndex.sceneIndex` stays `0` (not `1`) so clicking `pick:s1:subtitle` on the
 * live stage selects it instead of silently rejecting the click as a scene mismatch.
 */
@OptIn(ExperimentalTestApi::class)
class NextBeatElementPickingTest {

    private fun createPickingFixtureDeck(host: DeckHostParams): Storyboard = ciacolaDeck(
        id = "next-beat-picking-fixture",
        title = "Next Beat Picking Fixture",
        host = host,
    ) {
        slide(key = "slide-1", title = "Opening Slide", beats = 2) {
            val theme = LocalDeckTheme.current
            Column(
                modifier = Modifier.fillMaxSize().padding(96.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                DeckEditableText(
                    elementId = "pick:s1:title",
                    text = "Opening Slide Title",
                    style = theme.typography.hero,
                    color = theme.colors.foreground,
                )
                DeckEditableText(
                    elementId = "pick:s1:subtitle",
                    text = "SUBTITLE ON FINAL BEAT",
                    style = theme.typography.eyebrow,
                    color = theme.colors.accent,
                )
            }
        }
        slide(key = "slide-2", title = "Second Slide", beats = 1) {
            val theme = LocalDeckTheme.current
            Box(Modifier.fillMaxSize().padding(96.dp)) {
                DeckEditableText(
                    elementId = "pick:s2:title",
                    text = "Second Slide Title",
                    style = theme.typography.title,
                    color = theme.colors.foreground,
                )
            }
        }
    }

    @Test
    fun nextBeatOntoSlide1LastBeatPreservesPresentedSceneAndAllowsSelectingSubtitle() = runComposeUiTest {
        val tmpDir = Files.createTempDirectory("ciacola-next-beat-pick-")
        val elementStore = ElementOverridesStore(storyboardDir = tmpDir)
        lateinit var animatic: Animatic
        lateinit var elementController: ElementEditorController
        lateinit var scope: CoroutineScope

        try {
            setContent {
                scope = rememberCoroutineScope()
                val inspectionRegistry = remember { StageInspectionRegistry() }
                val dragPreview = remember { StageDragPreview() }
                val interactiveRegistry = remember { StageInteractiveRegistry() }
                elementController = rememberElementEditorController(
                    overridesStore = elementStore,
                    inspectionRegistry = inspectionRegistry,
                )
                val storyboard = remember {
                    createPickingFixtureDeck(
                        DeckHostParams(
                            theme = DeckThemes.stageDark(),
                            bundledPreset = DeckThemePreset.StageDark,
                            stageDecorator = elementController.stageDecorator,
                            elementOverridesProvider = {
                                elementStore.revision
                                elementStore.data()
                            },
                        ),
                    )
                }
                animatic = rememberAnimatic(storyboard = { storyboard })
                SideEffect {
                    elementController.editorActive = true
                    elementController.elementPickingEnabled = true
                    elementController.currentSceneIndexProvider = { animatic.presentedIndex.sceneIndex }
                    elementController.currentBeatIndexProvider = { animatic.presentedIndex.frameIndex }
                    elementController.isStageSettlingProvider = { animatic.scrubSettling }
                }

                CompositionLocalProvider(
                    LocalSceneMode provides SceneMode.Story,
                    LocalStageDragPreview provides dragPreview,
                    LocalStageInteractiveRegistry provides interactiveRegistry,
                ) {
                    Box(Modifier.size(960.dp, 540.dp)) {
                        Easel(animatic)
                    }
                }
            }
            waitForIdle()

            // Advance Next Beat onto Slide 1's last beat (S1 has 2 beats: B0 -> B1).
            runOnIdle {
                scope.launch { animatic.advance(AdvanceDirection.Forward) }
            }
            mainClock.advanceTimeBy(1_800)
            waitForIdle()

            val lastBeatOfSlide1 = animatic.storyboard.scenes[0].frames.lastIndex
            assertEquals(
                0,
                animatic.presentedIndex.sceneIndex,
                "After Next Beat onto Slide 1's last beat, presentedIndex.sceneIndex must remain 0",
            )
            assertEquals(lastBeatOfSlide1, animatic.presentedIndex.frameIndex)

            // Clicking `pick:s1:subtitle` directly on the live stage via primary mouse button must select it.
            val subtitleBounds = onNodeWithText("SUBTITLE ON FINAL BEAT").fetchSemanticsNode().boundsInRoot
            val overlayBounds = onNodeWithTag("ciacola-direct-edit-overlay").fetchSemanticsNode().boundsInRoot
            val clickLocal = Offset(
                x = (subtitleBounds.left + subtitleBounds.right) / 2f - overlayBounds.left,
                y = (subtitleBounds.top + subtitleBounds.bottom) / 2f - overlayBounds.top,
            )
            onNodeWithTag("ciacola-direct-edit-overlay").performMouseInput {
                click(clickLocal)
            }
            waitForIdle()

            assertEquals(
                "pick:s1:subtitle",
                elementController.selectedElementId,
                "Clicking pick:s1:subtitle on Slide 1's last beat must select pick:s1:subtitle",
            )
        } finally {
            elementStore.close()
            tmpDir.toFile().deleteRecursively()
        }
    }
}
