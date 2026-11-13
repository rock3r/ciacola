package dev.sebastiano.ciacola.presenter

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.bnorm.storyboard.Storyboard
import dev.bnorm.storyboard.easel.rememberAnimatic
import dev.sebastiano.ciacola.createFoundationDeck
import dev.sebastiano.ciacola.timeline.DeckTimelineOverridesFile
import dev.sebastiano.ciacola.timeline.SceneBeatOverride
import dev.sebastiano.ciacola.ui.CiacolaJewelTheme
import java.awt.Rectangle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PresenterNotesAndControllerTest {

    private fun contrast(fg: Color, bg: Color): Float {
        val l1 = fg.luminance()
        val l2 = bg.luminance()
        return (maxOf(l1, l2) + 0.05f) / (minOf(l1, l2) + 0.05f)
    }

    private fun compositeOver(fg: Color, bg: Color): Color {
        val a = fg.alpha.coerceIn(0f, 1f)
        return Color(
            red = fg.red * a + bg.red * (1f - a),
            green = fg.green * a + bg.green * (1f - a),
            blue = fg.blue * a + bg.blue * (1f - a),
            alpha = 1f,
        )
    }

    @Test
    fun foundationDeckPopulatesProvisionalSpeakerNotesAcrossAllScenesAndResolvesVirtualBeats() {
        val overrides = DeckTimelineOverridesFile(
            sceneBeats = mapOf(
                0 to SceneBeatOverride(sceneIndex = 0, injectedAfterBeats = listOf(1)),
            ),
        )
        val storyboard = createFoundationDeck(
            includeKeynoteEffectsLab = true,
            timelineOverrides = overrides,
        )
        val notes = StoryboardSpeakerNotesRegistry.forStoryboard(storyboard)
        assertNotNull(notes, "Expected FoundationDeck to register speaker notes")
        assertEquals(5, notes.scenes.size)

        for (sceneIdx in 0 until 5) {
            val sceneNotes = notes.scenes[sceneIdx]
            assertNotNull(sceneNotes, "Missing speaker notes for scene $sceneIdx")
            assertTrue(sceneNotes.hasAnyNotes, "Expected non-empty notes for scene $sceneIdx")
        }

        // Scene 0 has 3 base beats + 1 injected hold beat after base beat 1 -> virtual beat 2 is a hold on base beat 1.
        val virtualHold = notes.resolve(storyboard, Storyboard.Index(0, 2))
        assertTrue(virtualHold.isVirtualHoldBeat, "Virtual beat 2 should be marked as a hold beat")
        assertEquals(1, virtualHold.baseBeatIndex)
        assertEquals("Beats", virtualHold.sceneTitle)
        assertEquals(
            notes.resolve(storyboard, Storyboard.Index(0, 1)).beatNote,
            virtualHold.beatNote,
            "Virtual hold beat must inherit its base beat's cue",
        )
    }

    @Test
    fun unregisteredSceneReturnsCleanEmptySpeakerNotes() {
        val storyboard = Storyboard.build(title = "Empty") {
            scene(frameCount = 2) {}
        }
        val resolved = DeckSpeakerNotes.Empty.resolve(storyboard, Storyboard.Index(0, 1))
        assertFalse(resolved.hasNotes)
        assertEquals("Slide 1", resolved.sceneTitle)
        assertEquals(1, resolved.baseBeatIndex)
    }

    @Test
    fun timerStateTracksPlayPauseResumeResetAndPaceStatuses() {
        var nowMs = 1_000L
        val timer = PresenterTimerState(clockMillis = { nowMs })

        assertEquals(TimerRunState.Stopped, timer.runState)
        assertEquals(0L, timer.elapsedSeconds())
        assertEquals("00:00", PresenterTimerState.formatMmSs(0))
        assertEquals(35, timer.targetDurationMinutes)
        assertEquals(PaceStatus.OnPace, timer.paceStatus(elapsedSeconds = 0, flatBeatIndex = 0, totalBeats = 10))

        // Start timer and advance 65 seconds.
        timer.togglePlayPause()
        assertEquals(TimerRunState.Running, timer.runState)
        nowMs += 65_000L
        assertEquals(65L, timer.elapsedSeconds())
        assertEquals("01:05", PresenterTimerState.formatMmSs(65L))
        assertEquals("33:55 left", PresenterTimerState.formatRemaining(65L, 35))

        // Pause for 100s — elapsed must freeze at 65s.
        timer.togglePlayPause()
        assertEquals(TimerRunState.Paused, timer.runState)
        nowMs += 100_000L
        assertEquals(65L, timer.elapsedSeconds())

        // Resume and advance 5s -> 70s.
        timer.togglePlayPause()
        assertEquals(TimerRunState.Running, timer.runState)
        nowMs += 5_000L
        assertEquals(70L, timer.elapsedSeconds())

        // Tight pace: 80% through target time while still on beat 1/10.
        assertEquals(
            PaceStatus.Tight,
            timer.paceStatus(elapsedSeconds = 28 * 60L, flatBeatIndex = 1, totalBeats = 10),
        )

        // Over pace: past target duration.
        assertEquals(
            PaceStatus.Over,
            timer.paceStatus(elapsedSeconds = 36 * 60L + 12L, flatBeatIndex = 5, totalBeats = 10),
        )
        assertEquals("+01:12 over", PresenterTimerState.formatRemaining(36 * 60L + 12L, 35))

        // Target adjustment clamps cleanly to 5..180.
        repeat(10) { timer.adjustTargetMinutes(-5) }
        assertEquals(PresenterTimerState.MIN_TARGET_MINUTES, timer.targetDurationMinutes)
        repeat(40) { timer.adjustTargetMinutes(+5) }
        assertEquals(PresenterTimerState.MAX_TARGET_MINUTES, timer.targetDurationMinutes)

        // Reset while running resets elapsed to 0 and keeps running.
        timer.reset()
        assertEquals(TimerRunState.Running, timer.runState)
        assertEquals(0L, timer.elapsedSeconds())

        // Pause then reset returns to Stopped.
        timer.togglePlayPause()
        timer.reset()
        assertEquals(TimerRunState.Stopped, timer.runState)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun presenterStatusPaletteMeetsWcagContrastInDarkAndLightThemes() = runComposeUiTest {
        for (isDark in listOf(true, false)) {
            lateinit var palette: PresenterStatusPalette
            var panelBg = Color.Unspecified
            setContent {
                CiacolaJewelTheme(isDark = isDark) {
                    palette = rememberPresenterStatusPalette()
                    panelBg = org.jetbrains.jewel.foundation.theme.JewelTheme.globalColors.panelBackground
                    Box(Modifier.size(10.dp))
                }
            }
            waitForIdle()

            val readyBg = compositeOver(palette.ready.copy(alpha = 0.14f), panelBg)
            val transBg = compositeOver(palette.transitioning.copy(alpha = 0.14f), panelBg)
            val tightBg = compositeOver(palette.tight.copy(alpha = 0.14f), panelBg)
            val overBg = compositeOver(palette.over.copy(alpha = 0.14f), panelBg)

            assertTrue(
                contrast(palette.readyInk, readyBg) >= 4.5f,
                "isDark=$isDark readyInk contrast ${contrast(palette.readyInk, readyBg)} < 4.5",
            )
            assertTrue(
                contrast(palette.transitioningInk, transBg) >= 4.5f,
                "isDark=$isDark transitioningInk contrast ${contrast(palette.transitioningInk, transBg)} < 4.5",
            )
            assertTrue(
                contrast(palette.tightInk, tightBg) >= 4.5f,
                "isDark=$isDark tightInk contrast ${contrast(palette.tightInk, tightBg)} < 4.5",
            )
            assertTrue(
                contrast(palette.overInk, overBg) >= 4.5f,
                "isDark=$isDark overInk contrast ${contrast(palette.overInk, overBg)} < 4.5",
            )
            assertTrue(
                contrast(palette.warningBannerInk, palette.warningBannerBg) >= 4.5f,
                "isDark=$isDark warning banner contrast ${contrast(palette.warningBannerInk, palette.warningBannerBg)} < 4.5",
            )
            assertTrue(
                contrast(palette.curtainActiveInk, palette.curtainActiveBg) >= 4.5f,
                "isDark=$isDark curtain badge contrast ${contrast(palette.curtainActiveInk, palette.curtainActiveBg)} < 4.5",
            )
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun displayRoutingInvokesRouterActions() = runComposeUiTest {
        val fakeScreens = listOf(
            DisplayScreenInfo("d1", "Display 1 (1920×1080 · Primary)", Rectangle(0, 0, 1920, 1080), true),
            DisplayScreenInfo("d2", "Display 2 (2560×1440)", Rectangle(1920, 0, 2560, 1440), false),
        )
        var presentedOn: DisplayScreenInfo? = null
        var swapped = false
        val router = object : DisplayRoutingAdapter {
            override fun screens(): List<DisplayScreenInfo> = fakeScreens
            override fun presentStoryOn(screen: DisplayScreenInfo): Boolean {
                presentedOn = screen
                return true
            }

            override fun swapAudienceAndPresenter(): Boolean {
                swapped = true
                return true
            }
        }

        val deck = createFoundationDeck(includeKeynoteEffectsLab = true)
        lateinit var controller: PresenterController
        setContent {
            val animatic = rememberAnimatic(storyboard = { deck })
            controller = PresenterController(
                initialAnimatic = animatic,
                displayRouter = router,
            )
            Box(Modifier.size(10.dp))
        }
        waitForIdle()

        assertEquals(2, controller.refreshDisplays().size)
        assertTrue(controller.presentOnDisplay(fakeScreens[1]))
        assertEquals("d2", presentedOn?.id)
        assertTrue(controller.swapAudienceAndPresenterDisplays())
        assertTrue(swapped)
    }
}
