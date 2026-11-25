package dev.sebastiano.ciacola.studio

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.bnorm.storyboard.easel.LocalOverviewOpen
import dev.bnorm.storyboard.easel.LocalPresentingFullscreen
import dev.sebastiano.ciacola.createLoadedTestDeckStore
import dev.sebastiano.ciacola.decks.AllDocumentDecks
import dev.sebastiano.ciacola.document.DeckDocumentStore
import dev.sebastiano.ciacola.inspector.design.DocumentThemeInspectorController
import dev.sebastiano.ciacola.inspector.element.DocumentElementInspectorController
import dev.sebastiano.ciacola.inspector.lint.DocumentDeckLintController
import dev.sebastiano.ciacola.timeline.DocumentTimelineController
import dev.sebastiano.ciacola.timeline.DocumentTimelineSelection
import dev.sebastiano.ciacola.timeline.DocumentTimelineWindow
import dev.sebastiano.ciacola.timeline.rememberDocumentTimelineWindow
import dev.sebastiano.ciacola.ui.CiacolaJewelTheme
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class StudioAppBarAndShellTest {

    private fun model(mode: StudioMode = StudioMode.Edit, save: StudioSaveState = StudioSaveState.Saved) = StudioAppBarModel(
        breadcrumb = formatStudioBreadcrumb(2, 136, "I want to display rich text", 0, 2),
        mode = mode,
        canUndo = true,
        canRedo = false,
        saveState = save,
        themeOpen = false,
        issuesOpen = false,
        timelineOpen = true,
        presenterOpen = false,
        errorCount = 52,
        warningCount = 270,
    )

    @Test
    fun breadcrumbReadsInPlainLanguageWithFallbacks() {
        assertEquals(
            "Slide 3 of 136 · \u201CI want to display rich text\u201D · Build 1 of 2",
            formatStudioBreadcrumb(2, 136, "I want to display rich text", 0, 2),
        )
        assertEquals("Slide 4 of 9", formatStudioBreadcrumb(3, 9, "  ", 0, 1))
        assertEquals("Slide 1 of 1 · Build 2 of 3", formatStudioBreadcrumb(0, 1, null, 1, 3))
        assertEquals("52 · 270", formatIssuesBadge(52, 270))
    }

    @Test
    fun inspectorClampsTo320dpBelow1200dpWindows() {
        assertEquals(388f, effectiveInspectorWidthFor(388f, 1440f))
        assertEquals(320f, effectiveInspectorWidthFor(388f, 1100f))
        assertEquals(320f, effectiveInspectorWidthFor(520f, 1199f))
        assertEquals(388f, effectiveInspectorWidthFor(388f, Float.NaN), "Unmeasured shells never clamp")
    }

    @Test
    fun chromeHidesWhilePresentingInOverviewOrFullscreen() {
        assertTrue(isStudioChromeVisible(StudioMode.Edit, overviewOpen = false, presentingFullscreen = false))
        assertTrue(isStudioChromeVisible(StudioMode.Review, overviewOpen = false, presentingFullscreen = false))
        assertFalse(isStudioChromeVisible(StudioMode.Present, overviewOpen = false, presentingFullscreen = false))
        assertFalse(isStudioChromeVisible(StudioMode.Edit, overviewOpen = true, presentingFullscreen = false))
        assertFalse(isStudioChromeVisible(StudioMode.Edit, overviewOpen = false, presentingFullscreen = true))
    }

    @Test
    fun appBarCentersModeControlAndKeepsRightGroupInsideNarrowWindows() = runComposeUiTest {
        var selected: StudioMode? = null
        var undos = 0
        var widthDp by mutableStateOf(1440)
        val actions = StudioAppBarActions(
            onSelectMode = { selected = it },
            onUndo = { undos++ },
            onRedo = {},
            onToggleTheme = {},
            onToggleIssues = {},
            onToggleTimeline = {},
            onTogglePresenter = {},
        )
        setContent {
            CiacolaJewelTheme(isDark = true) {
                Box(Modifier.size(widthDp.dp, 60.dp)) {
                    StudioAppBar(
                        model = model(save = StudioSaveState.ReadOnly),
                        actions = actions,
                        deckSwitcher = { Text("Foundation", Modifier.testTag("fake-deck-switcher")) },
                    )
                }
            }
        }
        waitForIdle()

        val bar = onNodeWithTag("ciacola-studio-app-bar").getBoundsInRoot()
        assertEquals(36f, (bar.bottom - bar.top).value, 0.5f, "App Bar must be exactly 36dp tall")
        val mode = onNodeWithTag("ciacola-app-bar-mode").getBoundsInRoot()
        val barCenter = (bar.left + bar.right).value / 2f
        val modeCenter = (mode.left + mode.right).value / 2f
        assertTrue(abs(barCenter - modeCenter) < 2f, "Mode control must be centered ($modeCenter vs $barCenter)")
        onNodeWithTag("ciacola-app-bar-save-state", useUnmergedTree = true).assertTextEquals("Read-only")
        onNodeWithTag("ciacola-app-bar-issues-errors", useUnmergedTree = true).assertTextEquals("52")
        onNodeWithTag("ciacola-app-bar-issues-warnings", useUnmergedTree = true).assertTextEquals("270")
        onNodeWithTag("ciacola-app-bar-breadcrumb", useUnmergedTree = true)
            .assertTextEquals("Slide 3 of 136 · \u201CI want to display rich text\u201D · Build 1 of 2")

        onNodeWithTag("ciacola-app-bar-mode-review").performClick()
        assertEquals(StudioMode.Review, selected)
        onNodeWithTag("ciacola-app-bar-mode-present").performClick()
        assertEquals(StudioMode.Present, selected)
        onNodeWithTag("ciacola-app-bar-undo").performClick()
        assertEquals(1, undos)

        runOnIdle { widthDp = 1100 }
        waitForIdle()
        val narrowBar = onNodeWithTag("ciacola-studio-app-bar").getBoundsInRoot()
        val presenter = onNodeWithTag("ciacola-app-bar-presenter").getBoundsInRoot()
        val narrowMode = onNodeWithTag("ciacola-app-bar-mode").getBoundsInRoot()
        assertTrue(presenter.right <= narrowBar.right, "Right group must not clip at 1100dp")
        assertTrue(presenter.left >= narrowMode.right, "Right group must not overlap the mode control at 1100dp")
    }

    @Test
    fun designAndAnimateTabsSplitThePanelEvenly() = runComposeUiTest {
        var tab by mutableStateOf(StudioInspectorTab.Design)
        setContent {
            CiacolaJewelTheme(isDark = true) {
                Box(Modifier.size(388.dp, 80.dp)) {
                    StudioInspectorTabRow(activeTab = tab, onSelectTab = { tab = it })
                }
            }
        }
        waitForIdle()
        val design = onNodeWithTag("ciacola-studio-tab-slot-design", useUnmergedTree = true).getBoundsInRoot()
        val animate = onNodeWithTag("ciacola-studio-tab-slot-animate", useUnmergedTree = true).getBoundsInRoot()
        val designWidth = (design.right - design.left).value
        val animateWidth = (animate.right - animate.left).value
        assertTrue(abs(designWidth - animateWidth) < 1f, "Tabs must be equal width ($designWidth vs $animateWidth)")
        assertTrue(designWidth > 170f, "Each tab must span about half of the 388dp panel ($designWidth)")
        onNodeWithTag("ciacola-studio-tab-animate").performClick()
        assertEquals(StudioInspectorTab.Animate, tab)
    }

    private class Harness(
        val store: DeckDocumentStore,
        val workspace: StudioWorkspaceController,
        val timelineWindow: DocumentTimelineWindow,
        val elementController: DocumentElementInspectorController,
        val timelineController: DocumentTimelineController,
    )

    @Test
    fun modeTransitionsThemeSheetAndIssuesDockDriveTheShell() = runComposeUiTest {
        val store = createLoadedTestDeckStore()
        var overviewOpen by mutableStateOf(false)
        var fullscreen by mutableStateOf(false)
        var inspect = false
        var cleanShow = 0
        var reviewPanelOpened = 0
        lateinit var h: Harness
        val stageFocus = FocusRequester()

        setContent {
            val specimens = AllDocumentDecks.foundation.specimens
            val timelineController = remember { DocumentTimelineController(store = store, specimens = specimens) }
            val elementController = remember { DocumentElementInspectorController(store = store, specimens = specimens) }
            val workspace = remember {
                StudioWorkspaceController(
                    store = store,
                    timelineController = timelineController,
                    elementController = elementController,
                    themeController = DocumentThemeInspectorController(store = store),
                    lintController = DocumentDeckLintController(store = store, specimens = specimens),
                )
            }
            val timelineWindow = rememberDocumentTimelineWindow(controller = timelineController, initialDocked = true)
            workspace.timelineWindow = timelineWindow
            workspace.setReviewInspectMode = { inspect = it }
            workspace.onEnterCleanShowMode = { cleanShow++ }
            workspace.onOpenReviewPanel = { reviewPanelOpened++ }
            h = Harness(store, workspace, timelineWindow, elementController, timelineController)
            CompositionLocalProvider(
                LocalOverviewOpen provides overviewOpen,
                LocalPresentingFullscreen provides fullscreen,
            ) {
                CiacolaJewelTheme(isDark = true) {
                    Box(Modifier.size(1000.dp, 700.dp)) {
                        StudioShell(
                            workspace = workspace,
                            appBar = { Box(Modifier.size(1000.dp, 36.dp).testTag("fake-app-bar")) },
                            themeSheet = { Box(Modifier.size(380.dp, 400.dp).testTag("fake-theme-sheet")) },
                            issuesDock = { Box(Modifier.size(1000.dp, 160.dp).testTag("fake-issues-dock")) },
                        ) {
                            Box(Modifier.fillMaxSize().focusRequester(stageFocus).focusable().testTag("fake-stage"))
                        }
                    }
                }
            }
        }
        waitForIdle()

        // Edit mode: App Bar visible, shell measured at 1000dp (narrow window → inspector clamps to 320dp).
        onNodeWithTag("fake-app-bar").assertExists()
        assertEquals(1000f, h.workspace.shellWidthDp, 0.5f)
        assertEquals(320f, h.workspace.effectiveInspectorWidthDp)
        assertEquals(StudioMode.Edit, h.workspace.mode)

        // Theme sheet (F7) opens as an overlay without touching the inspector tabs.
        runOnIdle { h.workspace.toggleDesignSystemEditor() }
        waitForIdle()
        onNodeWithTag("fake-theme-sheet").assertExists()
        assertEquals(StudioInspectorTab.Design, h.workspace.activeTab)
        val sheet = onNodeWithTag("fake-theme-sheet").getBoundsInRoot()
        assertEquals(1000f, sheet.right.value, 0.5f, "Theme sheet must be right-aligned")

        // Issues badge opens the bottom dock; jumping to an issue keeps the dock open and selects in Design.
        runOnIdle { h.workspace.toggleDeckLinter() }
        waitForIdle()
        onNodeWithTag("fake-issues-dock").assertExists()
        runOnIdle {
            h.workspace.activeTab = StudioInspectorTab.Animate
            h.workspace.selectTabInStudio(StudioInspectorTab.Design)
            h.elementController.selectNode("s1:eyebrow")
        }
        waitForIdle()
        assertTrue(h.workspace.issuesDockOpen, "Issues dock must stay visible after jumping to an issue")
        onNodeWithTag("fake-issues-dock").assertExists()

        // Review mode: inspect on, review panel opened, timeline folded, picking disabled.
        runOnIdle { h.workspace.switchMode(StudioMode.Review) }
        waitForIdle()
        assertTrue(inspect)
        assertEquals(1, reviewPanelOpened)
        assertFalse(h.timelineWindow.dockedInStoryWindow)
        assertFalse(h.workspace.isElementPickingEnabled)
        assertFalse(h.workspace.themeSheetOpen)

        // Present mode: clean show, chrome hidden, App Bar gone.
        runOnIdle { h.workspace.switchMode(StudioMode.Present) }
        waitForIdle()
        assertEquals(1, cleanShow)
        assertFalse(inspect)
        assertFalse(h.workspace.issuesDockOpen)
        assertTrue(onAllNodesWithTag("fake-app-bar").fetchSemanticsNodes().isEmpty(), "App Bar hides in Present")
        onNodeWithTag("ciacola-present-reveal-zone").assertExists()

        // Windowed Present leaves Esc unconsumed so EaselOverview opens identically to fullscreen;
        // switching back to Edit restores the App Bar and re-docks the timeline.
        runOnIdle { stageFocus.requestFocus() }
        onNodeWithTag("fake-stage").performKeyInput { pressKey(Key.Escape) }
        waitForIdle()
        assertEquals(StudioMode.Present, h.workspace.mode)
        runOnIdle { h.workspace.switchMode(StudioMode.Edit) }
        waitForIdle()
        assertEquals(StudioMode.Edit, h.workspace.mode)
        assertTrue(h.timelineWindow.dockedInStoryWindow)
        onNodeWithTag("fake-app-bar").assertExists()

        // Esc closes the Deck Theme sheet even when focus sits on the App Bar / stage.
        runOnIdle { h.workspace.toggleDesignSystemEditor() }
        waitForIdle()
        assertTrue(h.workspace.themeSheetOpen)
        onNodeWithTag("fake-stage").performKeyInput { pressKey(Key.Escape) }
        waitForIdle()
        assertFalse(h.workspace.themeSheetOpen)

        // Overview and fullscreen presenting hide the App Bar too.
        runOnIdle { overviewOpen = true }
        waitForIdle()
        assertTrue(onAllNodesWithTag("fake-app-bar").fetchSemanticsNodes().isEmpty())
        assertTrue(h.workspace.overviewOpen)
        runOnIdle {
            overviewOpen = false
            fullscreen = true
        }
        waitForIdle()
        assertTrue(onAllNodesWithTag("fake-app-bar").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun stageSelectionAndTimelineSelectionStayInSync() = runComposeUiTest {
        val store = createLoadedTestDeckStore()
        lateinit var h: Harness
        setContent {
            val specimens = AllDocumentDecks.foundation.specimens
            val timelineController = remember { DocumentTimelineController(store = store, specimens = specimens) }
            val elementController = remember { DocumentElementInspectorController(store = store, specimens = specimens) }
            val workspace = remember {
                StudioWorkspaceController(
                    store = store,
                    timelineController = timelineController,
                    elementController = elementController,
                    themeController = DocumentThemeInspectorController(store = store),
                    lintController = DocumentDeckLintController(store = store, specimens = specimens),
                )
            }
            val timelineWindow = rememberDocumentTimelineWindow(controller = timelineController, initialDocked = true)
            workspace.timelineWindow = timelineWindow
            h = Harness(store, workspace, timelineWindow, elementController, timelineController)
            val firstSlide = store.document.slides.first().id
            StudioWorkspaceSyncEffects(workspace = workspace, activeSlideId = firstSlide, activeBeatIndex = 0)
        }
        waitForIdle()

        val firstSlideId = store.document.slides.first().id
        val trackedNode = h.timelineController.snapshot.trackRefsByTrackId.values
            .mapNotNull { it.targetNodeId() }
            .firstOrNull { it.first == firstSlideId }
        assertNotNull(trackedNode, "Foundation slide 1 must have at least one node track")

        // Stage → timeline.
        runOnIdle { h.elementController.selectNode(trackedNode.second) }
        waitForIdle()
        val selection = h.timelineController.selection
        assertTrue(selection is DocumentTimelineSelection.Track, "Selecting a node must select its track ($selection)")
        assertEquals(trackedNode, h.timelineController.snapshot.trackRefsByTrackId[selection.trackId]?.targetNodeId())

        // Timeline → stage.
        runOnIdle {
            h.elementController.selectNode(null)
            h.timelineController.clearSelection()
        }
        waitForIdle()
        runOnIdle { h.timelineController.selectTrack(selection.trackId) }
        waitForIdle()
        assertEquals(trackedNode.second, h.elementController.selectedNodeId, "Selecting a track must select its node")
    }

    @Test
    fun speakerNoteSavesAreAtomicUndoableEdits() {
        val store = createLoadedTestDeckStore()
        val slide = store.document.slides.first()
        val revision = store.revision
        assertTrue(saveSpeakerNote(store, slide.id, 0, "Open with the hook"))
        assertEquals("Open with the hook", store.document.slides.first().beats[0].notes)
        assertEquals(revision + 1, store.revision, "One save must be one history entry")
        assertTrue(saveSpeakerNote(store, slide.id, 0, "   "), "Blank notes clear the field")
        assertEquals(null, store.document.slides.first().beats[0].notes)
        store.undo()
        assertEquals("Open with the hook", store.document.slides.first().beats[0].notes)
        assertFalse(saveSpeakerNote(store, "missing-slide", 0, "x"))
        assertFalse(saveSpeakerNote(store, slide.id, 99, "x"))
    }

    @Test
    fun externalDeckFilesResolveToCatalogOrSharedSpecimenEntries() {
        val catalogFile = java.nio.file.Path.of("/tmp/decks/foundation.deck.json")
        assertEquals("foundation", externalDeckEntryFor(catalogFile)?.id)
        val external = externalDeckEntryFor(java.nio.file.Path.of("/tmp/decks/my-new-talk.deck.json"))
        assertNotNull(external)
        assertEquals("My New Talk", external.title)
        assertTrue(external.specimens.all.isNotEmpty(), "External decks fall back to shared catalog specimens")
        assertEquals(null, externalDeckEntryFor(java.nio.file.Path.of("/tmp/notes.json")))
        assertEquals("/tmp/decks", decksDirFor(catalogFile))
    }

    @Test
    fun deckSwitchTimerReportsPreOpenAndFirstFrameDurations() {
        var now = 0L
        val timer = DeckSwitchTimer(nanoClock = { now })
        timer.markRequested("foundation", "shaders-quickie-2025")
        now = 40_000_000L
        timer.markPreOpened()
        now = 180_000_000L
        val timing = assertNotNull(timer.markFirstFrameDrawn())
        assertEquals(40L, timing.preOpenMs)
        assertEquals(180L, timing.totalMs)
        assertEquals("deck-switch foundation -> shaders-quickie-2025: preOpen=40ms firstFrame=180ms", timing.formatLine())
        assertEquals(null, timer.markFirstFrameDrawn(), "A timing is only reported once per request")
    }

    @Test
    fun inspectorAutoCollapsesBelow900dpUnlessToggled() {
        assertFalse(isInspectorCollapsedForWidth(1200f, null))
        assertFalse(isInspectorCollapsedForWidth(900f, null))
        assertTrue(isInspectorCollapsedForWidth(899f, null))
        assertTrue(isInspectorCollapsedForWidth(640f, null))
        assertFalse(isInspectorCollapsedForWidth(640f, false))
        assertTrue(isInspectorCollapsedForWidth(1440f, true))
    }

    @Test
    fun appBar640dpNeverClipsCoreControlsAndOverflowMenuInvokesActions() = runComposeUiTest {
        var inspectorVisible by mutableStateOf(false)
        var pdfExports = 0
        var videoExports = 0
        var reducedMotion by mutableStateOf(false)
        var pausedLoops by mutableStateOf(false)
        var audited by mutableStateOf(false)

        val actions = StudioAppBarActions(
            onSelectMode = {},
            onUndo = {},
            onRedo = {},
            onToggleTheme = {},
            onToggleIssues = {},
            onToggleTimeline = {},
            onTogglePresenter = {},
            onToggleInspector = { inspectorVisible = !inspectorVisible },
            onExportPdf = { pdfExports++ },
            onExportVideo = { videoExports++ },
            onToggleReducedMotion = { reducedMotion = !reducedMotion },
            onToggleAmbientLoopsPaused = { pausedLoops = !pausedLoops },
        )

        setContent {
            CiacolaJewelTheme(isDark = true) {
                Box(Modifier.size(640.dp, 60.dp)) {
                    StudioAppBar(
                        model = model().copy(
                            inspectorVisible = inspectorVisible,
                            reducedMotion = reducedMotion,
                            ambientLoopsPaused = pausedLoops,
                            issuesAudited = audited,
                        ),
                        actions = actions,
                        deckSwitcher = { Text("Shaders Quickie", Modifier.testTag("fake-deck-switcher")) },
                    )
                }
            }
        }
        waitForIdle()

        val bar = onNodeWithTag("ciacola-studio-app-bar").getBoundsInRoot()
        val mode = onNodeWithTag("ciacola-app-bar-mode").getBoundsInRoot()
        val issues = onNodeWithTag("ciacola-app-bar-issues").getBoundsInRoot()
        val timeline = onNodeWithTag("ciacola-app-bar-timeline").getBoundsInRoot()
        val presenter = onNodeWithTag("ciacola-app-bar-presenter").getBoundsInRoot()
        val overflow = onNodeWithTag("ciacola-app-bar-overflow").getBoundsInRoot()

        onNodeWithTag("ciacola-app-bar-issues-unchecked", useUnmergedTree = true).assertTextEquals("Check")
        assertTrue(overflow.right <= bar.right, "Overflow button must not clip at 640dp ($overflow vs $bar)")
        assertTrue(issues.left >= mode.right, "Right controls must not overlap mode segmented control ($issues vs $mode)")
        assertTrue(timeline.left >= issues.right)
        assertTrue(presenter.left >= timeline.right)
        assertTrue(overflow.left >= presenter.right)

        runOnIdle { audited = true }
        waitForIdle()
        onNodeWithTag("ciacola-app-bar-issues-errors", useUnmergedTree = true).assertTextEquals("52")

        onNodeWithTag("ciacola-app-bar-overflow").performClick()
        waitForIdle()
        onNodeWithTag("ciacola-overflow-toggle-inspector", useUnmergedTree = true).performClick()
        assertTrue(inspectorVisible, "Overflow menu must toggle inspector")

        onNodeWithTag("ciacola-app-bar-overflow").performClick()
        waitForIdle()
        onNodeWithTag("ciacola-overflow-export-pdf", useUnmergedTree = true).performClick()
        assertEquals(1, pdfExports)

        onNodeWithTag("ciacola-app-bar-overflow").performClick()
        waitForIdle()
        onNodeWithTag("ciacola-overflow-export-video", useUnmergedTree = true).performClick()
        assertEquals(1, videoExports)

        onNodeWithTag("ciacola-app-bar-overflow").performClick()
        waitForIdle()
        onNodeWithTag("ciacola-overflow-reduced-motion", useUnmergedTree = true).performClick()
        assertTrue(reducedMotion)

        onNodeWithTag("ciacola-app-bar-overflow").performClick()
        waitForIdle()
        onNodeWithTag("ciacola-overflow-pause-loops", useUnmergedTree = true).performClick()
        assertTrue(pausedLoops)
    }
}
