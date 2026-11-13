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
import dev.sebastiano.ciacola.StoryboardPresetRegistry
import dev.sebastiano.ciacola.createFoundationDeck
import dev.sebastiano.ciacola.rememberThemeSwitcherWindow
import dev.sebastiano.ciacola.theme.DeckThemePreset
import dev.sebastiano.ciacola.theme.DeckThemes
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
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
            val theme = remember(preset) { DeckThemes.forPreset(preset) }
            val deck = remember(preset, coordinator) {
                createFoundationDeck(
                    theme = theme,
                    bundledPreset = preset,
                    includeKeynoteEffectsLab = true,
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

        // Switch theme while composer is open with unsaved text.
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
    fun storyboardPresetRegistryLocksChromeToRenderedStoryboardAndThemeWindowSurvivesSwitch() = runComposeUiTest {
        val registry = StoryboardPresetRegistry()
        val darkSb = createFoundationDeck(
            theme = DeckThemes.forPreset(DeckThemePreset.StageDark),
            bundledPreset = DeckThemePreset.StageDark,
        ).also { registry.record(it, DeckThemePreset.StageDark) }
        val lightSb = createFoundationDeck(
            theme = DeckThemes.forPreset(DeckThemePreset.KeynoteLight),
            bundledPreset = DeckThemePreset.KeynoteLight,
        ).also { registry.record(it, DeckThemePreset.KeynoteLight) }

        // While the window is still rendering darkSb, even if outer state flipped to KeynoteLight,
        // Jewel chrome stays locked to StageDark until lightSb arrives on the same frame.
        assertEquals(
            DeckThemePreset.StageDark,
            registry.presetFor(darkSb, fallback = DeckThemePreset.KeynoteLight),
        )
        assertEquals(
            DeckThemePreset.KeynoteLight,
            registry.presetFor(lightSb, fallback = DeckThemePreset.KeynoteLight),
        )

        var preset by mutableStateOf(DeckThemePreset.StageDark)
        lateinit var themeWin: EaselWindow
        setContent {
            themeWin = rememberThemeSwitcherWindow(
                currentPreset = preset,
                onSelectPreset = { preset = it },
            )
            if (themeWin.visible) {
                Box(Modifier.size(360.dp, 240.dp)) {
                    themeWin.Content()
                }
            }
        }
        waitForIdle()
        val initialWin = themeWin
        runOnIdle { initialWin.visible = true }
        waitForIdle()

        runOnIdle { preset = DeckThemePreset.KeynoteLight }
        waitForIdle()

        assertSame(initialWin, themeWin, "Theme Inspector window instance must be retained across preset switches")
        assertTrue(themeWin.visible, "Theme Inspector window must stay open when switching presets")
    }
}
