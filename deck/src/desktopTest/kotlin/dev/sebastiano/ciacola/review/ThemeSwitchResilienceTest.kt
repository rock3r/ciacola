package dev.sebastiano.ciacola.review

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.bnorm.storyboard.easel.Easel
import dev.bnorm.storyboard.easel.EaselWindow
import dev.bnorm.storyboard.easel.overview.EaselOverview
import dev.bnorm.storyboard.easel.rememberAnimatic
import dev.sebastiano.ciacola.buildFoundationTestStoryboard
import dev.sebastiano.ciacola.createLoadedTestDeckStore
import dev.sebastiano.ciacola.inspector.design.DocumentThemeInspectorController
import dev.sebastiano.ciacola.inspector.design.rememberDocumentThemeInspectorWindow
import dev.sebastiano.ciacola.theme.DeckThemePreset
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class ThemeSwitchResilienceTest {
    @Test
    fun switchingThemePreservesOpenReviewComposerAndTypedDraft() = runComposeUiTest {
        val tempRoot = Files.createTempDirectory("ciacola-theme-composer-draft")
        val storyboardDir = Files.createDirectories(tempRoot.resolve(".storyboard"))
        val store = ReviewCommentStore(storyboardDir = storyboardDir, workspaceRoot = tempRoot)

        var preset by mutableStateOf(DeckThemePreset.StageDark)
        lateinit var coordinator: ReviewStageCoordinator

        setContent {
            coordinator = rememberReviewStageCoordinator()
            val deck = remember(preset, coordinator) {
                buildFoundationTestStoryboard(
                    themeName = if (preset == DeckThemePreset.KeynoteLight) "light" else "dark",
                    stageDecorator = coordinator.stageDecorator,
                )
            }
            val animatic = rememberAnimatic(storyboard = { deck })
            val reviewWindow = rememberSlideReviewWindow(
                animatic = animatic,
                store = store,
                stageCoordinator = coordinator,
                currentPresetProvider = { preset },
            )
            Box(Modifier.size(960.dp, 540.dp)) {
                reviewWindow.decorator.decorate {
                    EaselOverview(animatic) {
                        Easel(animatic)
                    }
                }
            }
        }

        waitForIdle()
        runOnIdle { coordinator.inspectModeEnabled = true }
        waitForIdle()

        onNodeWithTag("ciacola-review-stage", useUnmergedTree = true).performMouseInput {
            click(position = Offset(300f, 240f))
        }
        waitForIdle()
        mainClock.advanceTimeBy(250)
        waitForIdle()

        assertNotNull(coordinator.pendingSelection, "Precondition: composer opened")
        onNodeWithTag("ciacola-review-note-input").performTextInput("Keep warm coral contrast in light mode")
        waitForIdle()

        runOnIdle { preset = DeckThemePreset.KeynoteLight }
        waitForIdle()
        mainClock.advanceTimeBy(250)
        waitForIdle()

        assertNotNull(
            coordinator.pendingSelection,
            "Switching theme must not dismiss an open review comment composer",
        )
        onNodeWithTag("ciacola-review-composer-popup").assertIsDisplayed()
        onNode(hasText("Keep warm coral contrast in light mode", substring = true)).assertIsDisplayed()
    }

    @Test
    fun documentThemeInspectorWindowSurvivesActiveThemeSwitches() = runComposeUiTest {
        val docStore = createLoadedTestDeckStore()
        lateinit var controller: DocumentThemeInspectorController
        lateinit var themeWin: EaselWindow
        setContent {
            controller = remember { DocumentThemeInspectorController(store = docStore) }
            themeWin = rememberDocumentThemeInspectorWindow(
                controller = controller,
                isDarkProvider = { controller.activeThemeName != "light" },
            )
            if (themeWin.visible) {
                Box(Modifier.size(440.dp, 560.dp)) {
                    themeWin.Content()
                }
            }
        }
        waitForIdle()
        val initialWin = themeWin
        runOnIdle { initialWin.visible = true }
        waitForIdle()

        runOnIdle { controller.selectTheme("light") }
        waitForIdle()

        assertSame(initialWin, themeWin, "DocumentThemeInspectorWindow instance must be retained across theme switches")
        assertTrue(themeWin.visible, "DocumentThemeInspectorWindow must stay open when switching themes")
    }
}
