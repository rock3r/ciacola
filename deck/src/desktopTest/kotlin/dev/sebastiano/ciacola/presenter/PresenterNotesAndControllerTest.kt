package dev.sebastiano.ciacola.presenter

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.bnorm.storyboard.Storyboard
import dev.bnorm.storyboard.easel.Animatic
import dev.bnorm.storyboard.easel.rememberAnimatic
import dev.sebastiano.ciacola.buildFoundationTestStoryboard
import dev.sebastiano.ciacola.document.DeckStructureEdits
import dev.sebastiano.ciacola.loadFoundationTestDocument
import dev.sebastiano.ciacola.studio.loadPersistedPresenterNotesFontSizeSp
import dev.sebastiano.ciacola.studio.persistPresenterNotesFontSizeSp
import dev.sebastiano.ciacola.ui.CiacolaJewelTheme
import java.awt.Rectangle
import java.nio.file.Files
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
    fun foundationDeckPopulatesProvisionalSpeakerNotesAcrossAllScenesAndSupportsBeatInsertion() {
        val doc = loadFoundationTestDocument()
        val withInsertedBeat = DeckStructureEdits.insertBeat(doc, slideId = "beats", atBeat = 2)
        val storyboard = buildFoundationTestStoryboard(document = withInsertedBeat)
        val notes = StoryboardSpeakerNotesRegistry.forStoryboard(storyboard)
        assertNotNull(notes, "Expected FoundationDeck to register speaker notes")
        assertEquals(5, notes.scenes.size)

        for (sceneIdx in 0 until 5) {
            val sceneNotes = notes.scenes[sceneIdx]
            assertNotNull(sceneNotes, "Missing speaker notes for scene $sceneIdx")
            assertTrue(sceneNotes.hasAnyNotes, "Expected non-empty notes for scene $sceneIdx")
        }

        assertEquals(4, storyboard.scenes[0].frames.size)
        val beat1Resolved = notes.resolve(storyboard, Storyboard.Index(0, 1))
        assertEquals("Beats", beat1Resolved.sceneTitle)
        assertTrue(beat1Resolved.hasNotes)
        val beat3Resolved = notes.resolve(storyboard, Storyboard.Index(0, 3))
        assertTrue(beat3Resolved.hasNotes, "Original beat 2 shifted to beat 3 must preserve its speaker notes")
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

        timer.togglePlayPause()
        assertEquals(TimerRunState.Running, timer.runState)
        nowMs += 65_000L
        assertEquals(65L, timer.elapsedSeconds())
        assertEquals("01:05", PresenterTimerState.formatMmSs(65L))
        assertEquals("33:55 left", PresenterTimerState.formatRemaining(65L, 35))

        timer.togglePlayPause()
        assertEquals(TimerRunState.Paused, timer.runState)
        nowMs += 100_000L
        assertEquals(65L, timer.elapsedSeconds())

        timer.togglePlayPause()
        assertEquals(TimerRunState.Running, timer.runState)
        nowMs += 5_000L
        assertEquals(70L, timer.elapsedSeconds())

        assertEquals(
            PaceStatus.Tight,
            timer.paceStatus(elapsedSeconds = 28 * 60L, flatBeatIndex = 1, totalBeats = 10),
        )

        assertEquals(
            PaceStatus.Over,
            timer.paceStatus(elapsedSeconds = 36 * 60L + 12L, flatBeatIndex = 5, totalBeats = 10),
        )
        assertEquals("+01:12 over", PresenterTimerState.formatRemaining(36 * 60L + 12L, 35))

        repeat(10) { timer.adjustTargetMinutes(-5) }
        assertEquals(PresenterTimerState.MIN_TARGET_MINUTES, timer.targetDurationMinutes)
        repeat(40) { timer.adjustTargetMinutes(+5) }
        assertEquals(PresenterTimerState.MAX_TARGET_MINUTES, timer.targetDurationMinutes)

        timer.reset()
        assertEquals(TimerRunState.Running, timer.runState)
        assertEquals(0L, timer.elapsedSeconds())

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

        val deck = buildFoundationTestStoryboard()
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

    @Test
    fun notesFontSizePersistsAcrossSessions() {
        val dir = Files.createTempDirectory("presenter-notes-zoom-")
        try {
            assertEquals(PresenterController.DEFAULT_NOTES_FONT_SP, loadPersistedPresenterNotesFontSizeSp(dir))
            persistPresenterNotesFontSizeSp(dir, 26f)
            assertEquals(26f, loadPersistedPresenterNotesFontSizeSp(dir))

            // Out-of-range values clamp cleanly when persisted and reloaded.
            persistPresenterNotesFontSizeSp(dir, 99f)
            assertEquals(PresenterController.MAX_NOTES_FONT_SP, loadPersistedPresenterNotesFontSizeSp(dir))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun nKeyStartsEditingNotesAndEscapeRestoresPresenterShortcuts() = runComposeUiTest {
        val doc = loadFoundationTestDocument()
        val deck = buildFoundationTestStoryboard()
        lateinit var animatic: Animatic
        lateinit var controller: PresenterController
        val savedNotes = mutableListOf<String?>()

        setContent {
            animatic = rememberAnimatic(storyboard = { deck })
            controller = androidx.compose.runtime.remember(animatic) {
                PresenterController(
                    initialAnimatic = animatic,
                    documentProvider = { doc },
                    onSaveSpeakerNote = { _, _, note -> savedNotes += note },
                    initialNotesFontSizeSp = 24f,
                    onNotesFontSizeChanged = { },
                    wallClockProvider = { "14:30" },
                )
            }
            CiacolaJewelTheme(isDark = true) {
                Box(Modifier.size(900.dp, 600.dp)) {
                    PresenterDashboard(controller = controller)
                }
            }
        }
        waitForIdle()

        assertEquals(24f, controller.notesFontSizeSp)
        onNodeWithTag("ciacola-presenter-dashboard").performKeyInput { pressKey(Key.N) }
        waitForIdle()
        assertTrue(controller.isEditingNotes, "Expected N shortcut to enter speaker notes editing")
        onNodeWithTag("ciacola-presenter-notes-editor").assertIsDisplayed()

        onNodeWithTag("ciacola-presenter-notes-editor").performKeyInput { pressKey(Key.Escape) }
        waitForIdle()
        assertFalse(controller.isEditingNotes, "Expected Escape to exit notes editing")

        // Presenter shortcuts work again immediately after Escape closes notes editing.
        val initialIdx = animatic.currentIndex
        onNodeWithTag("ciacola-presenter-dashboard").performKeyInput { pressKey(Key.Spacebar) }
        waitForIdle()
        assertTrue(animatic.targetIndex != initialIdx, "Spacebar must advance after exiting notes editor")
    }
}
