package dev.sebastiano.ciacola.a11y

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.Dp
import dev.bnorm.storyboard.easel.Easel
import dev.bnorm.storyboard.easel.overview.EaselOverview
import dev.bnorm.storyboard.easel.overview.OverviewVisibility
import dev.bnorm.storyboard.easel.overview.rememberOverviewVisibility
import dev.bnorm.storyboard.easel.rememberAnimatic
import dev.sebastiano.ciacola.buildFoundationTestStoryboard
import dev.sebastiano.ciacola.createLoadedTestDeckStore
import dev.sebastiano.ciacola.decks.AllDocumentDecks
import dev.sebastiano.ciacola.inspector.design.DocumentThemeInspectorController
import dev.sebastiano.ciacola.inspector.element.DocumentElementInspectorController
import dev.sebastiano.ciacola.inspector.lint.DocumentDeckLintController
import dev.sebastiano.ciacola.studio.StudioWorkspaceController
import dev.sebastiano.ciacola.theme.contrastRatio as wcagContrastRatio
import dev.sebastiano.ciacola.timeline.DocumentTimelineController
import dev.sebastiano.ciacola.timeline.DocumentTimelineSelection
import dev.sebastiano.ciacola.timeline.DocumentTimelineWindow
import dev.sebastiano.ciacola.timeline.rememberDocumentTimelineWindow
import dev.sebastiano.ciacola.ui.accessibleFocusRingColor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class TimelineA11yAndFocusTest {

    @Test
    fun focusRingColorsMeetWcag3To1ContrastInBothStageDarkAndKeynoteLight() {
        val darkPanel = Color(0xFF2B2D30)
        val lightPanel = Color(0xFFF7F8FA)
        val lowContrast = Color(0xFF31343A)

        val darkRing = accessibleFocusRingColor(focused = Color(0xFF3574F0), isDark = true, surface = darkPanel)
        val lightRing = accessibleFocusRingColor(focused = Color(0xFF3574F0), isDark = false, surface = lightPanel)
        val boostedDark = accessibleFocusRingColor(focused = lowContrast, isDark = true, surface = darkPanel)
        val boostedLight = accessibleFocusRingColor(focused = Color(0xFFEAECEF), isDark = false, surface = lightPanel)

        assertTrue(wcagContrastRatio(darkRing, darkPanel) >= 3.0f, "Dark focus ring must be >= 3:1")
        assertTrue(wcagContrastRatio(lightRing, lightPanel) >= 3.0f, "Light focus ring must be >= 3:1")
        assertTrue(wcagContrastRatio(boostedDark, darkPanel) >= 3.0f, "Boosted dark focus ring must be >= 3:1")
        assertTrue(wcagContrastRatio(boostedLight, lightPanel) >= 3.0f, "Boosted light focus ring must be >= 3:1")
    }

    @Test
    fun escClearsSelectionOrActiveGestureBeforeClosingDockedStudioWithoutOpeningOverview() = runComposeUiTest {
        val store = createLoadedTestDeckStore()
        val deck = buildFoundationTestStoryboard(store = store)
        lateinit var timelineController: DocumentTimelineController
        lateinit var timelineWindow: DocumentTimelineWindow
        lateinit var workspace: StudioWorkspaceController
        lateinit var overviewVisibility: OverviewVisibility

        setContent {
            val animatic = rememberAnimatic(storyboard = { deck })
            overviewVisibility = rememberOverviewVisibility()
            timelineController = remember {
                DocumentTimelineController(
                    store = store,
                    specimens = AllDocumentDecks.foundation.specimens,
                )
            }
            val elementController = remember { DocumentElementInspectorController(store = store) }
            val themeController = remember { DocumentThemeInspectorController(store = store) }
            val lintController = remember { DocumentDeckLintController(store = store, specimens = AllDocumentDecks.foundation.specimens) }
            timelineWindow = rememberDocumentTimelineWindow(
                controller = timelineController,
                initialDocked = true,
                initialExpanded = true,
            )
            workspace = remember {
                StudioWorkspaceController(
                    store = store,
                    timelineController = timelineController,
                    elementController = elementController,
                    themeController = themeController,
                    lintController = lintController,
                    initialInspectorWidthDp = 388f,
                )
            }
            workspace.timelineWindow = timelineWindow
            timelineWindow.extraPreviewKeyHandler = { ev, _ -> workspace.handleStudioPreviewKey(ev) }
            Box(Modifier.size(Dp(1280f), Dp(720f))) {
                timelineWindow.decorator.decorate {
                    EaselOverview(animatic, visibility = overviewVisibility) {
                        Easel(animatic)
                    }
                }
            }
        }
        waitForIdle()

        // Click a track row to select it.
        val firstTrackId = timelineController.snapshot.snapshot.tracks.first().trackId
        onNodeWithTag("ciacola-doc-track-row-$firstTrackId", useUnmergedTree = true).performClick()
        waitForIdle()
        assertTrue(timelineController.selection is DocumentTimelineSelection.Track)

        // First Esc inside Timeline clears track selection while keeping Studio docked and Overview closed.
        onNodeWithTag("ciacola-timeline-studio", useUnmergedTree = true).performKeyInput { pressKey(Key.Escape) }
        waitForIdle()
        assertEquals(DocumentTimelineSelection.None, timelineController.selection)
        assertTrue(timelineWindow.dockedInStoryWindow)
        assertFalse(overviewVisibility.isVisible)

        // F5 toggles expanded timeline tracks cleanly once per press in Edit mode.
        onRoot().performKeyInput { pressKey(Key.F5) }
        waitForIdle()
        assertFalse(timelineWindow.timelineExpanded, "F5 must collapse timeline tracks cleanly")
        assertTrue(timelineWindow.dockedInStoryWindow, "Studio stays docked in Edit mode when timeline collapses")
        onRoot().performKeyInput { pressKey(Key.F5) }
        waitForIdle()
        assertTrue(timelineWindow.timelineExpanded, "F5 must expand timeline tracks cleanly")
    }

    @Test
    fun documentTimelineSemanticsAreFreeOfInternalDebugStrings() = runComposeUiTest {
        val store = createLoadedTestDeckStore()
        val deck = buildFoundationTestStoryboard(store = store)
        lateinit var timelineController: DocumentTimelineController

        setContent {
            val animatic = rememberAnimatic(storyboard = { deck })
            timelineController = remember {
                DocumentTimelineController(
                    store = store,
                    specimens = AllDocumentDecks.foundation.specimens,
                )
            }
            val studioWindow = rememberDocumentTimelineWindow(
                controller = timelineController,
                initialDocked = true,
                initialExpanded = true,
            )
            Box(Modifier.size(Dp(1280f), Dp(720f))) {
                studioWindow.decorator.decorate {
                    EaselOverview(animatic) {
                        Easel(animatic)
                    }
                }
            }
        }
        waitForIdle()

        val firstTrackId = timelineController.snapshot.snapshot.tracks.first().trackId
        runOnIdle { timelineController.selectTrack(firstTrackId) }
        waitForIdle()

        val forbiddenPrefixes = listOf("zoom=", "scrollX=", "scrollY=", "previewScale=", "previewOffset=", "preset=", "p1=", "p2=")
        fun checkTree(node: SemanticsNode) {
            val tag = node.config.getOrNull(SemanticsProperties.TestTag).orEmpty()
            val desc = node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ").orEmpty()
            val state = node.config.getOrNull(SemanticsProperties.StateDescription).orEmpty()
            for (prefix in forbiddenPrefixes) {
                assertFalse(desc.contains(prefix), "Raw debug string '$prefix' leaked in $tag contentDescription: '$desc'")
                assertFalse(state.contains(prefix), "Raw debug string '$prefix' leaked in $tag stateDescription: '$state'")
            }
            node.children.forEach(::checkTree)
        }
        checkTree(onRoot(useUnmergedTree = true).fetchSemanticsNode())
    }
}
