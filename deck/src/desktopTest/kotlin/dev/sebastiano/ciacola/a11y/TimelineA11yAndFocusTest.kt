package dev.sebastiano.ciacola.a11y

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.bnorm.storyboard.easel.Easel
import dev.bnorm.storyboard.easel.overview.EaselOverview
import dev.bnorm.storyboard.easel.overview.OverviewVisibility
import dev.bnorm.storyboard.easel.overview.rememberOverviewVisibility
import dev.bnorm.storyboard.easel.rememberAnimatic
import dev.sebastiano.ciacola.buildFoundationTestStoryboard
import dev.sebastiano.ciacola.createLoadedTestDeckStore
import dev.sebastiano.ciacola.decks.AllDocumentDecks
import dev.sebastiano.ciacola.document.Curve
import dev.sebastiano.ciacola.inspector.design.DocumentThemeInspectorController
import dev.sebastiano.ciacola.inspector.element.DocumentElementInspectorController
import dev.sebastiano.ciacola.inspector.element.DocumentElementInspectorUi
import dev.sebastiano.ciacola.inspector.lint.DocumentDeckLintController
import dev.sebastiano.ciacola.studio.StudioWorkspaceController
import dev.sebastiano.ciacola.theme.contrastRatio as wcagContrastRatio
import dev.sebastiano.ciacola.timeline.DocumentTimelineController
import dev.sebastiano.ciacola.timeline.DocumentTimelineCurveEditor
import dev.sebastiano.ciacola.timeline.DocumentTimelineSelection
import dev.sebastiano.ciacola.timeline.DocumentTimelineWindow
import dev.sebastiano.ciacola.timeline.rememberDocumentTimelineWindow
import dev.sebastiano.ciacola.ui.CiacolaJewelTheme
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

    @Test
    fun layersRowsAndBezierHandlesHaveVoiceOverSemanticsFocusAndKeyboardControl() = runComposeUiTest {
        val store = createLoadedTestDeckStore()
        val firstSlide = store.document.slides.first()
        val firstNodeId = firstSlide.nodes.first().id
        val elementController = DocumentElementInspectorController(
            store = store,
            specimens = AllDocumentDecks.foundation.specimens,
            initialSlideId = firstSlide.id,
        )
        var currentCurve by mutableStateOf<Curve>(Curve.Bezier(0.25, 0.1, 0.25, 1.0))

        setContent {
            CiacolaJewelTheme(isDark = true) {
                Box(Modifier.size(320.dp, 640.dp)) {
                    androidx.compose.foundation.layout.Column {
                        DocumentElementInspectorUi(
                            controller = elementController,
                            modifier = Modifier.weight(1f),
                        )
                        DocumentTimelineCurveEditor(
                            targetKey = "a11y-curve",
                            curve = currentCurve,
                            onBeginGesture = {},
                            onCommitGesture = {},
                            onCancelGesture = {},
                            onCurveChanged = { next, _ -> currentCurve = next },
                        )
                    }
                }
            }
        }
        waitForIdle()

        val rowNode = onNodeWithTag("ciacola-doc-node-item-$firstNodeId", useUnmergedTree = true)
            .assertIsDisplayed()
            .fetchSemanticsNode()
        assertFalse(rowNode.config.getOrNull(SemanticsProperties.ContentDescription).isNullOrEmpty())
        assertEquals(false, rowNode.config.getOrNull(SemanticsProperties.Selected))
        assertEquals(null, rowNode.config.getOrNull(SemanticsProperties.StateDescription))
        onNodeWithTag("ciacola-doc-node-item-$firstNodeId", useUnmergedTree = true).requestFocus()
        waitForIdle()
        onNodeWithTag("ciacola-doc-node-item-$firstNodeId", useUnmergedTree = true).performKeyInput {
            pressKey(Key.Enter)
        }
        waitForIdle()
        assertEquals(firstNodeId, elementController.selectedNodeId)
        val selectedRow = onNodeWithTag("ciacola-doc-node-item-$firstNodeId", useUnmergedTree = true).fetchSemanticsNode()
        assertEquals(true, selectedRow.config.getOrNull(SemanticsProperties.Selected))

        val p1 = onNodeWithTag("ciacola-bezier-handle-p1", useUnmergedTree = true).assertIsDisplayed()
        val p1Semantics = p1.fetchSemanticsNode()
        assertTrue(
            p1Semantics.config.getOrNull(SemanticsProperties.ContentDescription)?.any { it.contains("P1") } == true,
        )
        assertFalse(p1Semantics.config.getOrNull(SemanticsProperties.StateDescription).isNullOrEmpty())
        onNodeWithTag("ciacola-bezier-handle-p2", useUnmergedTree = true).assertIsDisplayed()

        p1.requestFocus()
        waitForIdle()
        p1.performKeyInput { pressKey(Key.DirectionRight) }
        waitForIdle()
        val updated = currentCurve as Curve.Bezier
        assertEquals(0.30, updated.x1, absoluteTolerance = 0.005)
    }
}
