package dev.sebastiano.ciacola.studio

import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.isFocused
import dev.sebastiano.ciacola.document.DeckDocumentStore
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.bnorm.storyboard.easel.LocalOverviewOpen
import dev.bnorm.storyboard.easel.LocalPresentingFullscreen
import dev.bnorm.storyboard.easel.LocalStageViewportHairline
import dev.sebastiano.ciacola.createLoadedTestDeckStore
import dev.sebastiano.ciacola.decks.AllDocumentDecks
import dev.sebastiano.ciacola.inspector.design.DocumentThemeInspectorController
import dev.sebastiano.ciacola.inspector.element.DocumentElementInspectorController
import dev.sebastiano.ciacola.inspector.lint.DocumentDeckLintController
import dev.sebastiano.ciacola.theme.contrastRatio
import dev.sebastiano.ciacola.theme.relativeLuminance
import dev.sebastiano.ciacola.timeline.DocumentTimelineController
import dev.sebastiano.ciacola.timeline.rememberDocumentTimelineWindow
import dev.sebastiano.ciacola.ui.CiacolaJewelTheme
import org.jetbrains.jewel.foundation.theme.JewelTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** App Bar contrast, fullscreen Esc ownership and Studio shell interaction checks. */
@OptIn(ExperimentalTestApi::class)
class StudioShellInteractionTest {

    private fun model() = StudioAppBarModel(
        breadcrumb = formatStudioBreadcrumb(0, 5, "Beats", 0, 3),
        mode = StudioMode.Edit,
        canUndo = true,
        canRedo = false,
        saveState = StudioSaveState.Saved,
        themeOpen = false,
        issuesOpen = false,
        timelineOpen = false,
        presenterOpen = false,
        errorCount = 0,
        warningCount = 0,
    )

    private val noActions = StudioAppBarActions({}, {}, {}, {}, {}, {}, {})

    /** Renders the App Bar over a WHITE window (the case that bled through) and samples its paint. */
    private fun assertAppBarPaint(isDark: Boolean) = runComposeUiTest {
        var textColor = Color.Unspecified
        setContent {
            CiacolaJewelTheme(isDark = isDark) {
                textColor = JewelTheme.globalColors.text.normal
                Box(Modifier.size(1440.dp, 60.dp).background(Color.White)) {
                    StudioAppBar(model = model(), actions = noActions, deckSwitcher = { Text("Ciacola Foundation") })
                }
            }
        }
        waitForIdle()
        val pixels = onNodeWithTag("ciacola-studio-app-bar").captureToImage().toPixelMap()
        // Sample an empty strip just left of the right-hand group, well away from any label.
        val sample = pixels[pixels.width / 2 + pixels.width / 8, 2]
        assertEquals(1f, sample.alpha, 0.01f, "App Bar must be opaque")
        val luminance = relativeLuminance(sample)
        if (isDark) {
            assertTrue(luminance < 0.08f, "Dark App Bar must paint a dark panel, not the window white (L=$luminance, $sample)")
        } else {
            assertTrue(luminance > 0.6f, "Light App Bar must paint a light panel (L=$luminance, $sample)")
        }
        val contrast = contrastRatio(textColor, sample.copy(alpha = 1f))
        assertTrue(contrast >= 4.5f, "Deck name / Theme / Timeline labels need >= 4.5:1 on the bar ($contrast)")
    }

    @Test
    fun darkAppBarPaintsAnOpaqueDarkPanelWithReadableLabels() = assertAppBarPaint(isDark = true)

    @Test
    fun lightAppBarPaintsAnOpaqueLightPanelWithReadableLabels() = assertAppBarPaint(isDark = false)

    @Test
    fun escInFullscreenPresentStaysInPresentSoTheOverviewOwnsIt() = runComposeUiTest {
        val store = createLoadedTestDeckStore()
        var fullscreen by mutableStateOf(true)
        lateinit var workspace: StudioWorkspaceController
        var hairline: Boolean? = null
        val stageFocus = FocusRequester()
        setContent {
            val specimens = AllDocumentDecks.foundation.specimens
            val timelineController = remember { DocumentTimelineController(store = store, specimens = specimens) }
            val elementController = remember { DocumentElementInspectorController(store = store, specimens = specimens) }
            workspace = remember {
                StudioWorkspaceController(
                    store = store,
                    timelineController = timelineController,
                    elementController = elementController,
                    themeController = DocumentThemeInspectorController(store = store),
                    lintController = DocumentDeckLintController(store = store, specimens = specimens),
                )
            }
            workspace.timelineWindow = rememberDocumentTimelineWindow(controller = timelineController, initialDocked = true)
            CompositionLocalProvider(LocalOverviewOpen provides false, LocalPresentingFullscreen provides fullscreen) {
                CiacolaJewelTheme(isDark = true) {
                    Box(Modifier.size(1000.dp, 700.dp)) {
                        StudioShell(workspace = workspace, appBar = {}, themeSheet = {}) {
                            hairline = LocalStageViewportHairline.current
                            Box(Modifier.fillMaxSize().focusRequester(stageFocus).focusable().testTag("fake-stage"))
                        }
                    }
                }
            }
        }
        waitForIdle()
        assertEquals(true, hairline, "Edit keeps the stage hairline as an authoring aid")
        runOnIdle { workspace.switchMode(StudioMode.Present) }
        waitForIdle()
        assertEquals(false, hairline, "Present never frames the slide with a hairline")
        runOnIdle { stageFocus.requestFocus() }
        onNodeWithTag("fake-stage").performKeyInput { pressKey(Key.Escape) }
        waitForIdle()
        assertEquals(StudioMode.Present, workspace.mode, "Esc in fullscreen must not flip the workspace to Edit behind the stage")

        // Windowed Present keeps Esc → Edit.
        runOnIdle { fullscreen = false }
        waitForIdle()
        runOnIdle { stageFocus.requestFocus() }
        onNodeWithTag("fake-stage").performKeyInput { pressKey(Key.Escape) }
        waitForIdle()
        assertEquals(StudioMode.Edit, workspace.mode)
    }

    @Test
    fun breadcrumbNeverDoublesQuotesAndQuickJumpLabelIsPlatformAware() {
        assertEquals(
            "Slide 3 of 136 · \u201CI want to display rich text\u201D · Build 1 of 2",
            formatStudioBreadcrumb(2, 136, "\u201CI want to display rich text\u201D", 0, 2),
        )
        assertEquals("Slide 1 of 1 · \u201CQuoted\u201D", formatStudioBreadcrumb(0, 1, "\"Quoted\"", 0, 1))
        assertEquals("Slide 2 of 2", formatStudioBreadcrumb(1, 2, "\u201C\u201D", 0, 1))
        assertEquals("⌘K", quickJumpShortcutLabel("Mac OS X"))
        assertEquals("Ctrl+K", quickJumpShortcutLabel("Linux"))
        assertEquals("Ctrl+K", quickJumpShortcutLabel("Windows 11"))
        assertEquals(Color.Black, studioShellBackground(chromeVisible = false, panelBackground = Color.Gray))
        assertEquals(Color.Gray, studioShellBackground(chromeVisible = true, panelBackground = Color.Gray))
    }

    @Test
    fun focusOnTheAppBarIsTrackedSoTheTimelineNeverYanksItBackToTheStage() = runComposeUiTest {
        val store = createLoadedTestDeckStore()
        lateinit var workspace: StudioWorkspaceController
        val modeFocus = FocusRequester()
        val stageFocusReq = FocusRequester()
        setContent {
            val specimens = AllDocumentDecks.foundation.specimens
            val timelineController = remember { DocumentTimelineController(store = store, specimens = specimens) }
            val elementController = remember { DocumentElementInspectorController(store = store, specimens = specimens) }
            workspace = remember {
                StudioWorkspaceController(
                    store = store,
                    timelineController = timelineController,
                    elementController = elementController,
                    themeController = DocumentThemeInspectorController(store = store),
                    lintController = DocumentDeckLintController(store = store, specimens = specimens),
                )
            }
            val timelineWindow = rememberDocumentTimelineWindow(controller = timelineController, initialDocked = true)
            timelineWindow.suppressFocusRecoveryProvider = { workspace.chromeHasFocus }
            workspace.timelineWindow = timelineWindow
            CompositionLocalProvider(LocalOverviewOpen provides false, LocalPresentingFullscreen provides false) {
                CiacolaJewelTheme(isDark = true) {
                    Box(Modifier.size(1000.dp, 700.dp)) {
                        StudioShell(
                            workspace = workspace,
                            appBar = { Box(Modifier.size(80.dp, 30.dp).focusRequester(modeFocus).focusable().testTag("fake-mode-button")) },
                            themeSheet = {},
                        ) {
                            timelineWindow.decorator.decorate {
                                Box(Modifier.fillMaxSize().focusRequester(stageFocusReq).focusable().testTag("fake-stage"))
                            }
                        }
                    }
                }
            }
        }
        waitForIdle()
        // Stage first, so the timeline's root has had focus and its recovery effect is armed.
        runOnIdle { stageFocusReq.requestFocus() }
        waitForIdle()
        onNodeWithTag("fake-stage").assertIsFocused()
        runOnIdle { modeFocus.requestFocus() }
        repeat(6) { mainClock.advanceTimeByFrame() }
        waitForIdle()
        assertTrue(workspace.chromeHasFocus, "App Bar focus must be tracked on the workspace")
        onNodeWithTag("fake-mode-button").assertIsFocused()
    }

    private class AppBarHarness(val workspace: StudioWorkspaceController, val stageFocus: FocusRequester) {
        var presenterOpen by mutableStateOf(false)
        var presenterToggles = 0
    }

    private fun ComposeUiTest.mountAppBarInShell(store: DeckDocumentStore): AppBarHarness {
        lateinit var harness: AppBarHarness
        setContent {
            val specimens = AllDocumentDecks.foundation.specimens
            val h = remember {
                val timelineController = DocumentTimelineController(store = store, specimens = specimens)
                AppBarHarness(
                    workspace = StudioWorkspaceController(
                        store = store,
                        timelineController = timelineController,
                        elementController = DocumentElementInspectorController(store = store, specimens = specimens),
                        themeController = DocumentThemeInspectorController(store = store),
                        lintController = DocumentDeckLintController(store = store, specimens = specimens),
                    ),
                    stageFocus = FocusRequester(),
                )
            }
            harness = h
            val workspace = h.workspace
            val timelineWindow = rememberDocumentTimelineWindow(controller = workspace.timelineController, initialDocked = true)
            timelineWindow.suppressFocusRecoveryProvider = { workspace.chromeHasFocus }
            workspace.timelineWindow = timelineWindow
            workspace.onRestoreStageFocus = { runCatching { h.stageFocus.requestFocus() } }
            CompositionLocalProvider(LocalOverviewOpen provides false, LocalPresentingFullscreen provides false) {
                CiacolaJewelTheme(isDark = true) {
                    Box(Modifier.size(1400.dp, 800.dp)) {
                        StudioShell(
                            workspace = workspace,
                            appBar = {
                                StudioAppBarHost(
                                    workspace = workspace,
                                    breadcrumb = "Slide 1 of 1",
                                    presenterOpen = h.presenterOpen,
                                    onTogglePresenter = {
                                        h.presenterToggles++
                                        h.presenterOpen = !h.presenterOpen
                                    },
                                    deckSwitcher = {},
                                )
                            },
                            themeSheet = {},
                        ) {
                            timelineWindow.decorator.decorate {
                                Box(Modifier.fillMaxSize().focusRequester(h.stageFocus).focusable().testTag("fake-stage"))
                            }
                        }
                    }
                }
            }
        }
        waitForIdle()
        return harness
    }

    private fun ComposeUiTest.assertStageOwnsFocusAfterClicking(harness: AppBarHarness, tag: String) {
        onNodeWithTag(tag).performClick()
        repeat(4) { mainClock.advanceTimeByFrame() }
        waitForIdle()
        onNodeWithTag("fake-stage").assertIsFocused()
        // A button disabled by its own click (Undo) exposes no Focused property at all.
        onNodeWithTag(tag).assert(!isFocused())
        assertEquals(false, harness.workspace.chromeHasFocus, "Pointer-clicking $tag must hand focus back to the stage")
    }

    @Test
    fun pointerClicksOnAppBarButtonsReturnFocusToTheStage() = runComposeUiTest {
        val store = createLoadedTestDeckStore()
        val harness = mountAppBarInShell(store)
        runOnIdle {
            store.edit("Touch title") { doc -> doc.copy(slides = doc.slides.mapIndexed { i, s -> if (i == 0) s.copy(id = s.id + "-x") else s }) }
        }
        runOnIdle { harness.stageFocus.requestFocus() }
        waitForIdle()
        val revision = store.revision

        // Undo becomes disabled by this very click (single history entry).
        assertStageOwnsFocusAfterClicking(harness, "ciacola-app-bar-undo")
        assertTrue(store.revision != revision || !store.canUndo, "Undo must have run")
        onNodeWithTag("ciacola-app-bar-undo").assertIsNotEnabled()
        assertStageOwnsFocusAfterClicking(harness, "ciacola-app-bar-redo")
        assertTrue(store.canUndo, "Redo must have run")

        val themeBefore = harness.workspace.themeSheetOpen
        assertStageOwnsFocusAfterClicking(harness, "ciacola-app-bar-theme")
        assertEquals(!themeBefore, harness.workspace.themeSheetOpen)

        val issuesBefore = harness.workspace.issuesDockOpen
        assertStageOwnsFocusAfterClicking(harness, "ciacola-app-bar-issues")
        assertEquals(!issuesBefore, harness.workspace.issuesDockOpen)

        assertStageOwnsFocusAfterClicking(harness, "ciacola-app-bar-timeline")

        assertStageOwnsFocusAfterClicking(harness, "ciacola-app-bar-presenter")
        assertEquals(true, harness.presenterOpen, "Presenter click must open it")
        assertStageOwnsFocusAfterClicking(harness, "ciacola-app-bar-presenter")
        assertEquals(false, harness.presenterOpen, "Second Presenter click must close it")
    }

    @Test
    fun keyboardActivationOfAppBarButtonsKeepsFocusOnTheButton() = runComposeUiTest {
        val store = createLoadedTestDeckStore()
        val harness = mountAppBarInShell(store)
        runOnIdle { harness.stageFocus.requestFocus() }
        waitForIdle()

        for ((tag, key) in listOf(
            "ciacola-app-bar-theme" to Key.Spacebar,
            "ciacola-app-bar-issues" to Key.Enter,
            "ciacola-app-bar-timeline" to Key.Spacebar,
        )) {
            val themeBefore = harness.workspace.themeSheetOpen
            val issuesBefore = harness.workspace.issuesDockOpen
            onNodeWithTag(tag).requestFocus()
            waitForIdle()
            onNodeWithTag(tag).assertIsFocused()
            onNodeWithTag(tag).performKeyInput { pressKey(key) }
            repeat(4) { mainClock.advanceTimeByFrame() }
            waitForIdle()
            when (tag) {
                "ciacola-app-bar-theme" -> assertEquals(!themeBefore, harness.workspace.themeSheetOpen, "Space must toggle Theme")
                "ciacola-app-bar-issues" -> assertEquals(!issuesBefore, harness.workspace.issuesDockOpen, "Enter must toggle Issues")
            }
            onNodeWithTag(tag).assertIsFocused()
            onNodeWithTag("fake-stage").assertIsNotFocused()
            assertTrue(harness.workspace.chromeHasFocus, "Keyboard activation of $tag must keep focus on the App Bar")
        }

        val toggles = harness.presenterToggles
        onNodeWithTag("ciacola-app-bar-presenter").requestFocus()
        waitForIdle()
        onNodeWithTag("ciacola-app-bar-presenter").performKeyInput { pressKey(Key.Enter) }
        repeat(4) { mainClock.advanceTimeByFrame() }
        waitForIdle()
        assertEquals(toggles + 1, harness.presenterToggles, "Enter must toggle Presenter")
        onNodeWithTag("ciacola-app-bar-presenter").assertIsFocused()
        assertTrue(harness.workspace.chromeHasFocus)
    }

    @Test
    fun cancelledPointerPressThenKeyboardActivationKeepsFocusOnTheButton() = runComposeUiTest {
        val store = createLoadedTestDeckStore()
        val harness = mountAppBarInShell(store)
        runOnIdle { harness.stageFocus.requestFocus() }
        waitForIdle()
        val tag = "ciacola-app-bar-theme"
        val themeBefore = harness.workspace.themeSheetOpen
        // Press, drag off the button, release: a cancelled click that must not arm a later restore.
        onNodeWithTag(tag).performTouchInput {
            down(center)
            moveTo(Offset(-200f, height * 4f))
            up()
        }
        waitForIdle()
        assertEquals(themeBefore, harness.workspace.themeSheetOpen, "A cancelled press must not activate")
        onNodeWithTag(tag).requestFocus()
        waitForIdle()
        onNodeWithTag(tag).performKeyInput { pressKey(Key.Spacebar) }
        repeat(4) { mainClock.advanceTimeByFrame() }
        waitForIdle()
        assertEquals(!themeBefore, harness.workspace.themeSheetOpen, "Space must toggle Theme")
        onNodeWithTag(tag).assertIsFocused()
        onNodeWithTag("fake-stage").assertIsNotFocused()
        assertTrue(harness.workspace.chromeHasFocus)
    }

    @Test
    fun keyboardActivationOfUndoAndRedoRunsAndKeepsFocusWhileEnabled() = runComposeUiTest {
        val store = createLoadedTestDeckStore()
        val harness = mountAppBarInShell(store)
        runOnIdle {
            store.edit("First") { doc -> doc.copy(title = doc.title + " A") }
            store.edit("Second") { doc -> doc.copy(title = doc.title + " B") }
        }
        runOnIdle { harness.stageFocus.requestFocus() }
        waitForIdle()
        val titleAfterEdits = store.document.title

        onNodeWithTag("ciacola-app-bar-undo").requestFocus()
        waitForIdle()
        onNodeWithTag("ciacola-app-bar-undo").performKeyInput { pressKey(Key.Spacebar) }
        repeat(4) { mainClock.advanceTimeByFrame() }
        waitForIdle()
        assertTrue(store.document.title != titleAfterEdits, "Space must undo")
        assertTrue(store.canUndo, "One edit remains, so Undo stays enabled")
        onNodeWithTag("ciacola-app-bar-undo").assertIsFocused()
        assertTrue(harness.workspace.chromeHasFocus)

        runOnIdle { store.undo() }
        waitForIdle()
        onNodeWithTag("ciacola-app-bar-redo").requestFocus()
        waitForIdle()
        onNodeWithTag("ciacola-app-bar-redo").performKeyInput { pressKey(Key.Enter) }
        repeat(4) { mainClock.advanceTimeByFrame() }
        waitForIdle()
        assertTrue(store.canRedo, "One redo remains, so Redo stays enabled")
        assertTrue(store.canUndo, "Enter must redo")
        onNodeWithTag("ciacola-app-bar-redo").assertIsFocused()
        onNodeWithTag("fake-stage").assertIsNotFocused()
        assertTrue(harness.workspace.chromeHasFocus)
    }
}
