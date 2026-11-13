package dev.sebastiano.ciacola.presenter

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.bnorm.storyboard.Storyboard
import dev.bnorm.storyboard.easel.Animatic
import dev.bnorm.storyboard.easel.Easel
import dev.bnorm.storyboard.easel.LocalOverviewOpen
import dev.bnorm.storyboard.easel.overview.EaselOverview
import dev.bnorm.storyboard.easel.overview.rememberOverviewVisibility
import dev.bnorm.storyboard.easel.rememberAnimatic
import dev.sebastiano.ciacola.createFoundationDeck
import dev.sebastiano.ciacola.theme.DeckThemePreset
import dev.sebastiano.ciacola.theme.DeckThemes
import dev.sebastiano.ciacola.ui.CiacolaJewelTheme
import java.awt.Rectangle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class PresenterWindowAndStageAidsTest {

    @Test
    fun presenterWindowSurvivesThemeAndStoryboardRebuildsAndNeverStealsStoryHooks() = runComposeUiTest {
        var preset by mutableStateOf(DeckThemePreset.StageDark)
        var showPresenter by mutableStateOf(true)
        lateinit var presenterWindow: PresenterWindow
        lateinit var animatic: Animatic

        setContent {
            val theme = remember(preset) { DeckThemes.forPreset(preset) }
            val deck = remember(preset) {
                createFoundationDeck(
                    theme = theme,
                    bundledPreset = preset,
                    includeKeynoteEffectsLab = true,
                )
            }
            animatic = rememberAnimatic(storyboard = { deck })
            presenterWindow = rememberPresenterWindow(
                animatic = animatic,
                currentPresetProvider = { preset },
            )
            Box(Modifier.size(1200.dp, 720.dp)) {
                // Mount Story Easel alongside PresenterDashboard (using SceneMode.Preview).
                Easel(animatic)
                if (showPresenter && presenterWindow.visible) {
                    presenterWindow.Content()
                }
            }
        }

        fun readAnimaticField(name: String): Any? =
            Animatic::class.java.getDeclaredField(name).apply { isAccessible = true }.get(animatic)

        waitForIdle()
        val initialWindow = presenterWindow
        val storyClock = readAnimaticField("renderFrameClock")
        val storyCapture = readAnimaticField("freezeCapture")
        assertNotNull(storyClock, "Story Easel must register renderFrameClock")
        assertNotNull(storyCapture, "Story Easel must register freezeCapture")

        runOnIdle {
            initialWindow.visible = true
            initialWindow.controller.increaseNotesFontSize()
        }
        waitForIdle()

        assertSame(storyClock, readAnimaticField("renderFrameClock"), "Presenter preview must not steal Story renderFrameClock")
        assertSame(storyCapture, readAnimaticField("freezeCapture"), "Presenter preview must not steal Story freezeCapture")

        // Switch theme preset (rebuilds Storyboard + Animatic).
        runOnIdle { preset = DeckThemePreset.KeynoteLight }
        waitForIdle()

        assertSame(initialWindow, presenterWindow, "PresenterWindow instance must survive Storyboard rebuild")
        assertTrue(presenterWindow.visible, "PresenterWindow must remain open across Storyboard rebuild")
        assertEquals(22f, presenterWindow.controller.notesFontSizeSp, "Presenter font size must survive rebuild")

        // Hide PresenterDashboard and ensure Story hooks were never cleared on dispose!
        runOnIdle { showPresenter = false }
        waitForIdle()
        assertNotNull(readAnimaticField("renderFrameClock"), "Disposing Presenter preview must not clear Story renderFrameClock")
        assertNotNull(readAnimaticField("freezeCapture"), "Disposing Presenter preview must not clear Story freezeCapture")
    }

    @Test
    fun presenterDashboardRendersMinimumSizeReadinessBannerDisplayRoutingAndNavigation() = runComposeUiTest {
        var studioOpen by mutableStateOf(true)
        var inspectOpen by mutableStateOf(true)
        var unbaked by mutableStateOf(true)
        val fakeScreens = listOf(
            DisplayScreenInfo("d1", "Display 1 (1920×1080 · Primary)", Rectangle(0, 0, 1920, 1080), true),
            DisplayScreenInfo("d2", "Display 2 (2560×1440)", Rectangle(1920, 0, 2560, 1440), false),
        )
        var presentedScreenId: String? = null
        var swapped = false
        val router = object : DisplayRoutingAdapter {
            override fun screens(): List<DisplayScreenInfo> = fakeScreens
            override fun presentStoryOn(screen: DisplayScreenInfo): Boolean {
                presentedScreenId = screen.id
                return true
            }

            override fun swapAudienceAndPresenter(): Boolean {
                swapped = true
                return true
            }
        }

        val deck = createFoundationDeck(includeKeynoteEffectsLab = true)
        lateinit var animatic: Animatic
        lateinit var controller: PresenterController

        setContent {
            animatic = rememberAnimatic(storyboard = { deck })
            controller = remember(animatic) {
                PresenterController(
                    initialAnimatic = animatic,
                    isStudioOpenProvider = { studioOpen },
                    isInspectOpenProvider = { inspectOpen },
                    hasUnbakedOverridesProvider = { unbaked },
                    onEnterCleanShowModeAction = {
                        studioOpen = false
                        inspectOpen = false
                    },
                    displayRouter = router,
                    wallClockProvider = { "14:30" },
                )
            }
            // Exercise responsive layout right at minimumSize (760x500).
            Box(Modifier.size(760.dp, 500.dp)) {
                CiacolaJewelTheme(isDark = false) {
                    PresenterDashboard(controller = controller)
                }
            }
        }

        waitForIdle()
        onNodeWithTag("ciacola-presenter-slide-beat").assertTextContains("Slide 1 / 5 · Beat 1 / 3")
        onNodeWithTag("ciacola-presenter-scene-title").assertTextContains("Beats", substring = true)
        onNodeWithTag("ciacola-presenter-stage-status").assertTextContains("Ready")
        onNodeWithTag("ciacola-presenter-wall-clock").assertTextContains("14:30")
        onNodeWithTag("ciacola-presenter-target-duration").assertTextContains("35:00")
        onNodeWithTag("ciacola-presenter-pace-pill").assertTextContains("On pace", substring = true)
        onNodeWithTag("ciacola-presenter-current-preview").assertIsDisplayed()
        onNodeWithTag("ciacola-presenter-next-preview").assertIsDisplayed()
        onNodeWithTag("ciacola-presenter-notes-content").assertIsDisplayed()

        // Readiness banner is visible with Enter Clean Show Mode.
        onNodeWithTag("ciacola-presenter-readiness-banner").assertIsDisplayed()
        onNodeWithTag("ciacola-presenter-clean-show-button").performClick()
        waitForIdle()
        assertFalse(studioOpen, "Enter Clean Show Mode must close Studio")
        assertFalse(inspectOpen, "Enter Clean Show Mode must disable Inspect")

        // Unbaked overrides warning remains dismissible once Studio/Inspect are closed.
        onNodeWithTag("ciacola-presenter-dismiss-readiness").performClick()
        waitForIdle()
        onNodeWithTag("ciacola-presenter-readiness-banner").assertDoesNotExist()

        // Font size shortcuts & buttons.
        onNodeWithTag("ciacola-presenter-notes-font-inc").performClick()
        waitForIdle()
        assertEquals(22f, controller.notesFontSizeSp)
        onNodeWithTag("ciacola-presenter-notes-font-dec").performClick()
        waitForIdle()
        assertEquals(20f, controller.notesFontSizeSp)

        // Keyboard shortcuts inside Presenter Dashboard: T (timer), R (reset), Cmd+= / Cmd+- (font size).
        onNodeWithTag("ciacola-presenter-dashboard").performKeyInput { pressKey(Key.T) }
        waitForIdle()
        assertEquals(TimerRunState.Running, controller.timer.runState)
        onNodeWithTag("ciacola-presenter-dashboard").performKeyInput { pressKey(Key.R) }
        waitForIdle()
        onNodeWithTag("ciacola-presenter-dashboard").performKeyInput { pressKey(Key.T) }
        waitForIdle()
        assertEquals(TimerRunState.Paused, controller.timer.runState)
        onNodeWithTag("ciacola-presenter-dashboard").performKeyInput { pressKey(Key.R) }
        waitForIdle()
        assertEquals(TimerRunState.Stopped, controller.timer.runState)
        onNodeWithTag("ciacola-presenter-dashboard").performKeyInput {
            keyDown(Key.MetaLeft)
            pressKey(Key.Equals)
            keyUp(Key.MetaLeft)
        }
        waitForIdle()
        assertEquals(22f, controller.notesFontSizeSp)
        onNodeWithTag("ciacola-presenter-dashboard").performKeyInput {
            keyDown(Key.MetaLeft)
            pressKey(Key.Minus)
            keyUp(Key.MetaLeft)
        }
        waitForIdle()
        assertEquals(20f, controller.notesFontSizeSp)

        // Display routing popup actions.
        onNodeWithTag("ciacola-presenter-displays-button").performClick()
        waitForIdle()
        onNodeWithText("Display 2 (2560×1440)").performClick()
        waitForIdle()
        assertEquals("d2", presentedScreenId)

        onNodeWithTag("ciacola-presenter-displays-button").performClick()
        waitForIdle()
        onNodeWithTag("ciacola-presenter-swap-displays").performClick()
        waitForIdle()
        assertTrue(swapped)

        // Advance smoothly via Next Beat button.
        onNodeWithTag("ciacola-presenter-next-beat").performClick()
        waitForIdle()
        mainClock.advanceTimeBy(1_000)
        waitForIdle()
        assertEquals(Storyboard.Index(0, 1), animatic.currentIndex)
        onNodeWithTag("ciacola-presenter-slide-beat").assertTextContains("Slide 1 / 5 · Beat 2 / 3")
    }

    @Test
    fun emptySpeakerNotesStateIsShownWhenSlideHasNoNotes() = runComposeUiTest {
        val storyboard = Storyboard.build(title = "No Notes") {
            scene(frameCount = 1) {}
        }
        setContent {
            val animatic = rememberAnimatic(storyboard = { storyboard })
            val controller = remember(animatic) {
                PresenterController(
                    initialAnimatic = animatic,
                    speakerNotesProvider = { DeckSpeakerNotes.Empty },
                )
            }
            Box(Modifier.size(900.dp, 600.dp)) {
                CiacolaJewelTheme(isDark = true) {
                    PresenterDashboard(controller = controller)
                }
            }
        }
        waitForIdle()
        onNodeWithTag("ciacola-presenter-notes-empty").assertIsDisplayed()
        onNodeWithTag("ciacola-presenter-end-of-deck").assertIsDisplayed()
    }

    @Test
    fun blackoutWhiteoutEscClearingWithoutOpeningOverviewAndQuickSlideJump() = runComposeUiTest {
        var studioOpen by mutableStateOf(false)
        var inspectOpen by mutableStateOf(false)
        val deck = createFoundationDeck(includeKeynoteEffectsLab = true)
        lateinit var animatic: Animatic
        lateinit var presenterWindow: PresenterWindow
        lateinit var overviewVisibility: dev.bnorm.storyboard.easel.overview.OverviewVisibility

        setContent {
            animatic = rememberAnimatic(storyboard = { deck })
            overviewVisibility = rememberOverviewVisibility(initiallyVisible = false)
            presenterWindow = rememberPresenterWindow(
                animatic = animatic,
                isStudioOpenProvider = { studioOpen },
                isInspectOpenProvider = { inspectOpen },
            )
            Box(Modifier.size(960.dp, 540.dp)) {
                CompositionLocalProvider(LocalOverviewOpen provides overviewVisibility.isVisible) {
                    presenterWindow.decorator.decorate {
                        EaselOverview(animatic, visibility = overviewVisibility) {
                            Easel(animatic)
                        }
                    }
                }
            }
        }

        waitForIdle()
        // Focus the stage (EaselOverview inner box) and verify B toggles Blackout.
        onNodeWithTag("ciacola-stage-curtain").assertDoesNotExist()
        onRoot().performKeyInput { pressKey(Key.B) }
        waitForIdle()
        assertEquals(StageCurtain.Blackout, presenterWindow.controller.curtain)
        onNodeWithTag("ciacola-stage-curtain").assertIsDisplayed()

        // Pressing Esc clears Blackout AND must NOT open Overview!
        onRoot().performKeyInput { pressKey(Key.Escape) }
        waitForIdle()
        assertEquals(StageCurtain.None, presenterWindow.controller.curtain)
        onNodeWithTag("ciacola-stage-curtain").assertDoesNotExist()
        assertFalse(overviewVisibility.isVisible, "Esc clearing blackout must NOT open Overview")

        // Pressing W toggles Whiteout; advancing clears it cleanly.
        onRoot().performKeyInput { pressKey(Key.W) }
        waitForIdle()
        assertEquals(StageCurtain.Whiteout, presenterWindow.controller.curtain)
        onNodeWithTag("ciacola-stage-curtain").assertIsDisplayed()

        onRoot().performKeyInput { pressKey(Key.DirectionRight) }
        waitForIdle()
        mainClock.advanceTimeBy(1_000)
        waitForIdle()
        assertEquals(StageCurtain.None, presenterWindow.controller.curtain)
        assertEquals(Storyboard.Index(0, 1), animatic.currentIndex)

        // Pressing B when Studio or Inspect is open must do nothing.
        runOnIdle { studioOpen = true }
        waitForIdle()
        onRoot().performKeyInput { pressKey(Key.B) }
        waitForIdle()
        assertEquals(StageCurtain.None, presenterWindow.controller.curtain)

        runOnIdle {
            studioOpen = false
            inspectOpen = true
        }
        waitForIdle()
        onRoot().performKeyInput { pressKey(Key.W) }
        waitForIdle()
        assertEquals(StageCurtain.None, presenterWindow.controller.curtain)
        runOnIdle { inspectOpen = false }
        waitForIdle()

        // Cmd+G opens Quick Slide Jump dialog; invalid number shows inline error; valid number jumps.
        onRoot().performKeyInput {
            keyDown(Key.MetaLeft)
            pressKey(Key.G)
            keyUp(Key.MetaLeft)
        }
        waitForIdle()
        onNodeWithTag("ciacola-presenter-jump-dialog").assertIsDisplayed()

        // Esc cancels jump dialog WITHOUT opening Overview, and restores stage keyboard navigation!
        onNodeWithTag("ciacola-presenter-jump-input").performKeyInput { pressKey(Key.Escape) }
        waitForIdle()
        onNodeWithTag("ciacola-presenter-jump-dialog").assertDoesNotExist()
        assertFalse(overviewVisibility.isVisible, "Esc closing jump dialog must NOT open Overview")
        onRoot().performKeyInput { pressKey(Key.DirectionRight) }
        waitForIdle()
        mainClock.advanceTimeBy(1_000)
        waitForIdle()
        assertEquals(Storyboard.Index(0, 2), animatic.currentIndex, "Stage navigation must still work after Esc closes jump dialog")

        // Re-open with Cmd+G, try invalid slide 99, then valid slide 4 + Enter.
        onRoot().performKeyInput {
            keyDown(Key.MetaLeft)
            pressKey(Key.G)
            keyUp(Key.MetaLeft)
        }
        waitForIdle()
        onNodeWithTag("ciacola-presenter-jump-input").performTextReplacement("99")
        onNodeWithTag("ciacola-presenter-jump-input").performKeyInput { pressKey(Key.Enter) }
        waitForIdle()
        onNodeWithTag("ciacola-presenter-jump-error").assertIsDisplayed()

        onNodeWithTag("ciacola-presenter-jump-input").performTextReplacement("4")
        onNodeWithTag("ciacola-presenter-jump-input").performKeyInput { pressKey(Key.Enter) }
        waitForIdle()
        mainClock.advanceTimeBy(200)
        waitForIdle()
        onNodeWithTag("ciacola-presenter-jump-dialog").assertDoesNotExist()
        assertEquals(Storyboard.Index(3, 0), animatic.currentIndex)

        // Clicking an active curtain resumes the stage without leaking clicks underneath.
        onRoot().performKeyInput { pressKey(Key.B) }
        waitForIdle()
        assertEquals(StageCurtain.Blackout, presenterWindow.controller.curtain)
        onNodeWithTag("ciacola-stage-curtain").performClick()
        waitForIdle()
        assertEquals(StageCurtain.None, presenterWindow.controller.curtain)
    }

    @Test
    fun spaceOnFocusedPresenterControlActivatesControlWithoutAdvancingDeck() = runComposeUiTest {
        val deck = createFoundationDeck(includeKeynoteEffectsLab = true)
        lateinit var animatic: Animatic
        lateinit var controller: PresenterController

        setContent {
            animatic = rememberAnimatic(storyboard = { deck })
            controller = remember(animatic) {
                PresenterController(initialAnimatic = animatic, wallClockProvider = { "14:30" })
            }
            Box(Modifier.size(900.dp, 600.dp)) {
                CiacolaJewelTheme(isDark = true) {
                    PresenterDashboard(controller = controller)
                }
            }
        }

        waitForIdle()
        assertEquals(Storyboard.Index(0, 0), animatic.currentIndex)
        assertEquals(TimerRunState.Stopped, controller.timer.runState)

        // Focus the timer play/pause button and press Space: timer starts, slide stays at (0, 0).
        onNodeWithTag("ciacola-presenter-timer-play-pause").requestFocus()
        waitForIdle()
        onNodeWithTag("ciacola-presenter-timer-play-pause").performKeyInput { pressKey(Key.Spacebar) }
        waitForIdle()
        mainClock.advanceTimeBy(500)
        waitForIdle()
        assertEquals(TimerRunState.Running, controller.timer.runState, "Space on focused timer button must start timer")
        assertEquals(Storyboard.Index(0, 0), animatic.currentIndex, "Space on focused button must not advance deck")
    }

    @Test
    fun keyboardRemainsLiveWhenFocusedPresenterButtonDisablesAndDisplayListRefreshesLive() = runComposeUiTest {
        var connectedScreens = listOf(
            DisplayScreenInfo("d1", "Display 1 (1920×1080 · Primary)", Rectangle(0, 0, 1920, 1080), true),
        )
        var swapped = false
        var presentedScreenId: String? = null
        val router = object : DisplayRoutingAdapter {
            override fun screens(): List<DisplayScreenInfo> = connectedScreens
            override fun presentStoryOn(screen: DisplayScreenInfo): Boolean {
                presentedScreenId = screen.id
                return true
            }
            override fun swapAudienceAndPresenter(): Boolean {
                swapped = true
                return true
            }
        }

        val twoBeatDeck = Storyboard.build(title = "BoundaryFocus") {
            scene(frameCount = 2) {}
        }
        lateinit var animatic: Animatic
        lateinit var controller: PresenterController

        setContent {
            animatic = rememberAnimatic(storyboard = { twoBeatDeck })
            controller = remember(animatic) {
                PresenterController(
                    initialAnimatic = animatic,
                    displayRouter = router,
                    wallClockProvider = { "14:30" },
                )
            }
            Box(Modifier.size(900.dp, 600.dp)) {
                CiacolaJewelTheme(isDark = true) {
                    PresenterDashboard(controller = controller)
                }
            }
        }
        waitForIdle()

        // Focus "Next beat" button on beat 0/1, then activate it so we reach the final beat and "Next beat" disables!
        val nextBtn = onNodeWithTag("ciacola-presenter-next-beat")
        nextBtn.requestFocus()
        waitForIdle()
        nextBtn.performKeyInput { pressKey(Key.Spacebar) }
        waitForIdle()
        mainClock.advanceTimeBy(600)
        waitForIdle()
        assertEquals(Storyboard.Index(0, 1), animatic.currentIndex)
        nextBtn.assertIsNotEnabled()

        // Without clicking anything, keyboard shortcuts (T for timer, ← for previous beat) must still work!
        onNodeWithTag("ciacola-presenter-dashboard").performKeyInput { pressKey(Key.T) }
        waitForIdle()
        assertEquals(TimerRunState.Running, controller.timer.runState, "Keyboard shortcut T must work after focused button disables")

        onNodeWithTag("ciacola-presenter-dashboard").performKeyInput { pressKey(Key.DirectionLeft) }
        waitForIdle()
        mainClock.advanceTimeBy(600)
        waitForIdle()
        assertEquals(Storyboard.Index(0, 0), animatic.currentIndex, "Arrow navigation must work after focused button disables")

        // Hot-plug a second projector AFTER launch without rebuilding Storyboard or Animatic!
        connectedScreens = listOf(
            DisplayScreenInfo("d1", "Display 1 (1920×1080 · Primary)", Rectangle(0, 0, 1920, 1080), true),
            DisplayScreenInfo("d2", "Projector (1920×1080)", Rectangle(1920, 0, 1920, 1080), false),
        )
        onNodeWithTag("ciacola-presenter-displays-button").performClick()
        waitForIdle()
        onNodeWithText("Projector (1920×1080)").assertIsDisplayed().performClick()
        waitForIdle()
        assertEquals("d2", presentedScreenId, "Opening 'Present on…' must re-enumerate newly connected displays")

        onNodeWithTag("ciacola-presenter-displays-button").performClick()
        waitForIdle()
        onNodeWithTag("ciacola-presenter-swap-displays").performClick()
        waitForIdle()
        assertTrue(swapped, "Swap Displays must enable as soon as a second display is detected")
    }
}
