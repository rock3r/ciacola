package dev.sebastiano.ciacola.timeline

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.Dp
import dev.sebastiano.ciacola.createLoadedTestDeckStore
import dev.sebastiano.ciacola.decks.AllDocumentDecks
import dev.sebastiano.ciacola.indexFoundationTimelineSnapshot
import dev.sebastiano.ciacola.theme.DeckThemePreset
import dev.sebastiano.ciacola.theme.DeckThemes
import dev.sebastiano.ciacola.theme.contrastRatio
import dev.sebastiano.ciacola.ui.CiacolaJewelTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class TimelineEditingRegressionTest {

    @Test
    fun crossSlideBoundaryEventBeatsRecordPlannedTransitionDurationInsteadOfZero() {
        val snapshot = indexFoundationTimelineSnapshot()
        val boundaryBeats = snapshot.eventBeats.filter { it.isSlideBoundary }
        assertEquals(4, boundaryBeats.size, "Expected 4 cross-slide boundary event beats across 5 slides")
        for (beat in boundaryBeats) {
            assertTrue(
                beat.segmentDurationMs > 0,
                "Cross-slide EventBeatLine at S${beat.sceneIndex + 1}:B${beat.frameIndex + 1} must record planned transition duration > 0ms, got ${beat.segmentDurationMs}ms",
            )
        }
        assertTrue(
            boundaryBeats[0].segmentDurationMs >= 500,
            "S1 -> S2 MagicMove cross-slide beat duration should reflect >= 500ms morph, got ${boundaryBeats[0].segmentDurationMs}ms",
        )
    }

    @Test
    fun studioUndoRedoShortcutsAndToolbarButtonsWorkEndToEndOnDeckDocumentStore() = runComposeUiTest {
        val store = createLoadedTestDeckStore()
        lateinit var controller: DocumentTimelineController

        setContent {
            controller = remember {
                DocumentTimelineController(
                    store = store,
                    specimens = AllDocumentDecks.foundation.specimens,
                ).apply {
                    onUndoRequest = { store.undo(); refreshSnapshot() }
                    onRedoRequest = { store.redo(); refreshSnapshot() }
                }
            }
            val studioWindow = rememberDocumentTimelineWindow(controller = controller)
            CiacolaJewelTheme(isDark = true) {
                androidx.compose.foundation.layout.Column(Modifier.size(Dp(1280f), Dp(720f))) {
                    dev.sebastiano.ciacola.studio.StudioAppBar(
                        model = dev.sebastiano.ciacola.studio.StudioAppBarModel(
                            breadcrumb = "Slide 1 of 5",
                            mode = dev.sebastiano.ciacola.studio.StudioMode.Edit,
                            canUndo = store.canUndo,
                            canRedo = store.canRedo,
                            saveState = dev.sebastiano.ciacola.studio.StudioSaveState.Saved,
                            themeOpen = false,
                            issuesOpen = false,
                            timelineOpen = true,
                            presenterOpen = false,
                            errorCount = 0,
                            warningCount = 0,
                        ),
                        actions = dev.sebastiano.ciacola.studio.StudioAppBarActions(
                            onSelectMode = {},
                            onUndo = { controller.performUndo() },
                            onRedo = { controller.performRedo() },
                            onToggleTheme = {},
                            onToggleIssues = {},
                            onToggleTimeline = {},
                            onTogglePresenter = {},
                        ),
                        deckSwitcher = {},
                    )
                    Box(Modifier.weight(1f)) {
                        studioWindow.Content()
                    }
                }
            }
        }
        waitForIdle()

        val loopId = "foundation.ambientMesh/time"
        val initialPeriod = store.document.loopPeriodsMs[loopId]
        runOnIdle {
            assertTrue(controller.setLoopPeriodMs(loopId, 7_500L))
        }
        waitForIdle()
        assertEquals(7_500L, store.document.loopPeriodsMs[loopId])

        // 1. App Bar Undo button undoes loop period edit; Redo button restores it.
        onNodeWithTag("ciacola-app-bar-undo").performClick()
        waitForIdle()
        assertEquals(initialPeriod, store.document.loopPeriodsMs[loopId])

        onNodeWithTag("ciacola-app-bar-redo").performClick()
        waitForIdle()
        assertEquals(7_500L, store.document.loopPeriodsMs[loopId])

        // 2. Keyboard Cmd+Z undoes, Cmd+Shift+Z redoes!
        onNodeWithTag("ciacola-doc-timeline-zoom-in", useUnmergedTree = true).performClick()
        waitForIdle()
        onNodeWithTag("ciacola-doc-timeline-panel").performKeyInput {
            keyDown(Key.MetaLeft)
            keyDown(Key.Z)
            keyUp(Key.Z)
            keyUp(Key.MetaLeft)
        }
        waitForIdle()
        assertEquals(initialPeriod, store.document.loopPeriodsMs[loopId], "Cmd+Z must undo")

        onNodeWithTag("ciacola-doc-timeline-panel").performKeyInput {
            keyDown(Key.MetaLeft)
            keyDown(Key.ShiftLeft)
            keyDown(Key.Z)
            keyUp(Key.Z)
            keyUp(Key.ShiftLeft)
            keyUp(Key.MetaLeft)
        }
        waitForIdle()
        assertEquals(7_500L, store.document.loopPeriodsMs[loopId], "Cmd+Shift+Z must redo")
    }

    @Test
    fun motionPathPreviewContrastAndLightThemeDocumentTimelinePanelRenderCleanly() = runComposeUiTest {
        for (theme in listOf(DeckThemes.stageDark(), DeckThemes.keynoteLight())) {
            val arcRatio = contrastRatio(theme.colors.motionPathPreview, theme.colors.background)
            assertTrue(
                arcRatio >= 3.5f,
                "motionPathPreview stroke must be >= 3.5:1 against ${theme.colors.background}, got ${"%.2f".format(arcRatio)}:1",
            )
        }

        val store = createLoadedTestDeckStore()
        lateinit var controller: DocumentTimelineController
        setContent {
            controller = remember {
                DocumentTimelineController(
                    store = store,
                    specimens = AllDocumentDecks.foundation.specimens,
                    themeNameProvider = { "light" },
                )
            }
            val window = rememberDocumentTimelineWindow(
                controller = controller,
                currentPresetProvider = { DeckThemePreset.KeynoteLight },
            )
            CiacolaJewelTheme(isDark = false) {
                Box(Modifier.size(Dp(1280f), Dp(600f))) {
                    window.Content()
                }
            }
        }
        waitForIdle()
        val firstTrackId = controller.snapshot.snapshot.tracks.first().trackId
        runOnIdle { controller.selectTrack(firstTrackId) }
        waitForIdle()
        onNodeWithTag("ciacola-doc-timeline-panel", useUnmergedTree = true).assertExists()
    }
}
