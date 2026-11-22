package dev.sebastiano.ciacola.studio

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.bnorm.storyboard.easel.Easel
import dev.bnorm.storyboard.easel.LocalPresentingFullscreen
import dev.bnorm.storyboard.easel.rememberAnimatic
import dev.bnorm.storyboard.plus
import dev.sebastiano.ciacola.createLoadedTestDeckStore
import dev.sebastiano.ciacola.decks.AllDocumentDecks
import dev.sebastiano.ciacola.document.CanonicalJson
import dev.sebastiano.ciacola.document.DeckDocumentStore
import dev.sebastiano.ciacola.document.DeckFileSync
import dev.sebastiano.ciacola.document.RenderSession
import dev.sebastiano.ciacola.document.RichTextValue
import dev.sebastiano.ciacola.document.SyncState
import dev.sebastiano.ciacola.document.render.DocumentRenderConfig
import dev.sebastiano.ciacola.document.render.rememberDocumentStoryboard
import dev.sebastiano.ciacola.inspector.LocalStageInspectionRegistry
import dev.sebastiano.ciacola.inspector.StageInspectionRegistry
import dev.sebastiano.ciacola.inspector.design.DocumentThemeInspectorController
import dev.sebastiano.ciacola.inspector.element.DocumentElementInspectorController
import dev.sebastiano.ciacola.inspector.element.DocumentStageDirectEditOverlay
import dev.sebastiano.ciacola.inspector.lint.DocumentDeckLintController
import dev.sebastiano.ciacola.inspector.lint.LintSeverity
import dev.sebastiano.ciacola.loadFoundationTestDocument
import dev.sebastiano.ciacola.presenter.PresenterController
import dev.sebastiano.ciacola.presenter.PresenterWindow
import dev.sebastiano.ciacola.presenter.StageCurtain
import dev.sebastiano.ciacola.timeline.DocumentTimelineController
import dev.sebastiano.ciacola.timeline.DocumentTimelineWindow
import dev.sebastiano.ciacola.timeline.rememberDocumentTimelineWindow
import dev.sebastiano.ciacola.ui.AuthoringTextInputFocusTracker
import dev.sebastiano.ciacola.ui.CiacolaJewelTheme
import dev.sebastiano.ciacola.ui.LocalAuthoringTextInputTracker
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking

@OptIn(ExperimentalTestApi::class)
class EditorOverhaulIntegrationTest {

    @Test
    fun foundationDeckPassesFullDeckLintAuditWithZeroErrorsInDarkAndLightThemes() = runBlocking {
        val store = createLoadedTestDeckStore()
        DocumentDeckLintController(
            store = store,
            specimens = AllDocumentDecks.foundation.specimens,
        ).use { lintController ->
            val report = lintController.engine.lintFullDeck(
                document = store.document,
                themeNames = listOf("dark", "light"),
            )
            val errors = report.issues.filter { it.severity == LintSeverity.Error }
            assertTrue(
                errors.isEmpty(),
                "Expected 0 lint errors across dark + light on foundation.deck.json, found:\n" +
                    errors.joinToString("\n") { "${it.code} (S${it.slideIndex + 1}:B${it.beatIndex + 1}): ${it.title} — ${it.detail}" },
            )
        }
    }

    @Test
    fun unifiedStudioWorkspacePropagatesThemeAndElementEditsToStageAndLinterWithUnifiedUndo() = runComposeUiTest {
        val store = createLoadedTestDeckStore()
        val textTracker = AuthoringTextInputFocusTracker()
        var fullscreen by mutableStateOf(false)

        lateinit var workspace: StudioWorkspaceController
        lateinit var lintController: DocumentDeckLintController
        lateinit var elementController: DocumentElementInspectorController
        lateinit var themeController: DocumentThemeInspectorController
        lateinit var studioWindow: DocumentTimelineWindow
        lateinit var presenterController: PresenterController
        lateinit var registry: StageInspectionRegistry
        lateinit var uiScope: CoroutineScope

        setContent {
            uiScope = rememberCoroutineScope()
            registry = remember { StageInspectionRegistry() }
            val timelineController = remember {
                DocumentTimelineController(
                    store = store,
                    specimens = AllDocumentDecks.foundation.specimens,
                )
            }
            elementController = remember {
                DocumentElementInspectorController(
                    store = store,
                    specimens = AllDocumentDecks.foundation.specimens,
                )
            }
            themeController = remember { DocumentThemeInspectorController(store = store) }
            lintController = remember {
                DocumentDeckLintController(
                    store = store,
                    specimens = AllDocumentDecks.foundation.specimens,
                )
            }
            workspace = remember(timelineController) {
                StudioWorkspaceController(
                    store = store,
                    timelineController = timelineController,
                    elementController = elementController,
                    themeController = themeController,
                    lintController = lintController,
                    isTextInputActiveProvider = { textTracker.hasFocusedTextInput },
                )
            }

            val session = remember { RenderSession.forPresent(store, freeClockMillis = { 0L }) }
            val sb = rememberDocumentStoryboard(
                session = session,
                config = DocumentRenderConfig(
                    themeName = themeController.activeThemeName,
                    specimens = AllDocumentDecks.foundation.specimens,
                ),
            )
            val animatic = rememberAnimatic(storyboard = { sb })

            presenterController = remember(animatic) {
                PresenterController(
                    initialAnimatic = animatic,
                    isStudioOpenProvider = { workspace.isAnyAuthoringToolOpen },
                    isExternalTextInputActiveProvider = {
                        textTracker.hasFocusedTextInput || elementController.inlineEditingNodeId != null
                    },
                    onEnterCleanShowModeAction = {
                        studioWindow.dockedInStoryWindow = false
                        elementController.cancelInlineTextEdit()
                        elementController.selectNode(null)
                        // Production wiring also records Present mode, closing the Theme sheet / Issues dock.
                        workspace.markPresentMode()
                    },
                )
            }

            studioWindow = rememberDocumentTimelineWindow(
                controller = timelineController,
                suppressShortcutsProvider = { textTracker.hasFocusedTextInput },
                trailingPane = {
                    CiacolaJewelTheme(isDark = true) {
                        StudioUnifiedInspectorPane(
                            workspace = workspace,
                            animatic = animatic,
                            scope = uiScope,
                        )
                    }
                },
                extraPreviewKeyHandler = { ev, _ -> workspace.handleStudioPreviewKey(ev) },
            )
            workspace.timelineWindow = studioWindow
            StudioWorkspaceSyncEffects(
                workspace = workspace,
                activeSlideId = store.document.slides.filterNot { it.skipped }.getOrNull(animatic.presentedIndex.sceneIndex)?.id,
                activeBeatIndex = animatic.presentedIndex.frameIndex.coerceAtLeast(0),
            )

            val presenterWindow = remember(presenterController) {
                PresenterWindow(controller = presenterController)
            }

            CompositionLocalProvider(
                LocalAuthoringTextInputTracker provides textTracker,
                LocalPresentingFullscreen provides fullscreen,
                LocalStageInspectionRegistry provides registry,
            ) {
                CiacolaJewelTheme(isDark = true) {
                    Box(Modifier.size(1280.dp, 820.dp)) {
                        (presenterWindow.decorator + studioWindow.decorator).decorate {
                            DocumentStageDirectEditOverlay(
                                controller = elementController,
                                stageScale = 0.5f,
                                enabled = workspace.isElementPickingEnabled && !fullscreen,
                            ) {
                                Easel(animatic)
                            }
                        }
                    }
                }
            }
        }
        waitForIdle()

        // 1. Show Mode zero-leak guarantee: no direct-edit overlay when Studio is closed.
        assertTrue(onAllNodesWithTag("ciacola-doc-stage-direct-edit").fetchSemanticsNodes().isEmpty())

        // 2. Dock Studio (F5) and verify the selection-scoped Design | Animate tabs mount cleanly
        //    (deck-level Theme and Issues live in the App Bar sheet and bottom dock instead).
        runOnIdle { studioWindow.dockedInStoryWindow = true }
        waitForIdle()
        onNodeWithTag("ciacola-studio-tab-design").assertExists()
        onNodeWithTag("ciacola-studio-tab-animate").assertExists()
        assertTrue(onAllNodesWithTag("ciacola-studio-tab-theme").fetchSemanticsNodes().isEmpty())
        assertTrue(onAllNodesWithTag("ciacola-studio-tab-lint").fetchSemanticsNodes().isEmpty())

        // 3. Open the deck Theme sheet (F7), update dark theme canvas color, and undo via workspace.undo().
        runOnIdle { workspace.toggleDesignSystemEditor() }
        waitForIdle()
        assertTrue(workspace.themeSheetOpen)
        assertEquals(StudioInspectorTab.Design, workspace.activeTab, "Theme sheet must not hijack the inspector tabs")

        runOnIdle {
            themeController.setThemeColor("background", "#101928")
        }
        waitForIdle()
        assertEquals("#101928", store.document.themes.getValue("dark").colors["background"])

        runOnIdle { assertTrue(workspace.undo()) }
        waitForIdle()
        assertNotNull(registry.nodes["s1:eyebrow"])

        // 4. Switch to Element tab, edit s1:eyebrow text + low-contrast color, verify live update + lint detection.
        onNodeWithTag("ciacola-studio-tab-design").performClick()
        waitForIdle()
        assertEquals(StudioInspectorTab.Design, workspace.activeTab)
        assertTrue(onAllNodesWithTag("ciacola-doc-stage-direct-edit").fetchSemanticsNodes().isNotEmpty())

        runOnIdle {
            elementController.selectNode("s1:eyebrow")
            store.edit("Edit s1:eyebrow copy and low-contrast color") { doc ->
                doc.copy(
                    slides = doc.slides.mapIndexed { idx, s ->
                        if (idx == 0) {
                            s.copy(
                                nodes = s.nodes.map { n ->
                                    if (n.id == "s1:eyebrow") {
                                        n.copy(
                                            text = n.text!!.copy(
                                                value = RichTextValue.Plain("CUSTOM EYEBROW COPY"),
                                                color = "#181C26",
                                            ),
                                        )
                                    } else {
                                        n
                                    }
                                },
                            )
                        } else {
                            s
                        }
                    },
                )
            }
        }
        waitForIdle()
        assertEquals("CUSTOM EYEBROW COPY", registry.nodes["s1:eyebrow"]?.resolvedText)

        // 5. Lint engine flags contrast issue on s1:eyebrow; unified undo clears it!
        val brokenReport = runBlocking {
            lintController.lintSlideNow(slideIndex = 0, themeNames = listOf("dark"))
        }
        assertTrue(
            brokenReport.issues.any { it.nodeId == "s1:eyebrow" && it.code.contains("CONTRAST", ignoreCase = true) },
            "Lint audit must flag low contrast on overridden s1:eyebrow, got ${brokenReport.issues}",
        )

        runOnIdle { assertTrue(workspace.undo()) }
        waitForIdle()
        val restoredReport = runBlocking {
            lintController.lintSlideNow(slideIndex = 0, themeNames = listOf("dark"))
        }
        assertTrue(
            restoredReport.issues.none { it.nodeId == "s1:eyebrow" && it.severity == LintSeverity.Error },
            "Unified undo must restore s1:eyebrow and clear contrast error",
        )

        // 6. Fullscreen presenting hides stage authoring overlays, and focused authoring text inputs guard B/W curtains!
        runOnIdle { fullscreen = true }
        waitForIdle()
        assertTrue(onAllNodesWithTag("ciacola-doc-stage-direct-edit").fetchSemanticsNodes().isEmpty())

        runOnIdle {
            fullscreen = false
            presenterController.enterCleanShowMode()
            textTracker.onFocusAcquired()
        }
        waitForIdle()
        onRoot().performKeyInput { pressKey(Key.B) }
        waitForIdle()
        assertEquals(StageCurtain.None, presenterController.curtain)

        runOnIdle { textTracker.onFocusReleased() }
        waitForIdle()
        onRoot().performKeyInput { pressKey(Key.B) }
        waitForIdle()
        assertEquals(StageCurtain.Blackout, presenterController.curtain)

        lintController.close()
    }

    @Test
    fun deckFileSyncDefersWritesDuringActiveGestureTransactionAndWritesOnceOnCommit(): Unit = runBlocking {
        val tempDir = Files.createTempDirectory("ciacola-file-sync-gesture-")
        val recoveryDir = tempDir.resolve("recovery")
        val targetFile = tempDir.resolve("foundation.deck.json")
        val initialDoc = loadFoundationTestDocument()
        val initialText = CanonicalJson.print(initialDoc)
        Files.writeString(targetFile, initialText, StandardCharsets.UTF_8)

        val store = DeckDocumentStore()
        DeckFileSync(
            targetFile = targetFile,
            recoveryRootDir = recoveryDir,
            store = store,
            specimenRegistry = AllDocumentDecks.foundation.specimens,
            debounceMs = 20L,
            pollIntervalMs = 15L,
        ).use { sync ->
            sync.open()
            sync.startBackgroundSync(this)

            // Begin explicit gesture transaction and perform 25 intermediate updates: disk file must stay untouched!
            val tx = checkNotNull(store.begin("Drag node"))
            repeat(25) { step ->
                tx.update { doc ->
                    doc.copy(title = "Dragging $step")
                }
            }
            kotlinx.coroutines.delay(60L)
            assertEquals(
                initialText,
                Files.readString(targetFile, StandardCharsets.UTF_8),
                "DeckFileSync must not write to disk while a gesture transaction is open",
            )

            tx.commit()
            assertEquals(SyncState.Saved, sync.flushNow())
            val savedText = Files.readString(targetFile, StandardCharsets.UTF_8)
            assertTrue(savedText.contains("\"Dragging 24\""))
        }
        tempDir.toFile().deleteRecursively()
    }

    @Test
    fun inlineStageTextEditorCommitsSynchronouslyOnCmdEnterAndCommitButton() = runComposeUiTest {
        val store = createLoadedTestDeckStore()
        lateinit var elementController: DocumentElementInspectorController

        setContent {
            elementController = remember { DocumentElementInspectorController(store = store) }
            val session = remember { RenderSession.forPresent(store, freeClockMillis = { 0L }) }
            val sb = rememberDocumentStoryboard(
                session = session,
                config = DocumentRenderConfig(
                    themeName = "dark",
                    specimens = AllDocumentDecks.foundation.specimens,
                ),
            )
            val animatic = rememberAnimatic(storyboard = { sb })
            CiacolaJewelTheme(isDark = true) {
                Box(Modifier.size(960.dp, 540.dp)) {
                    DocumentStageDirectEditOverlay(
                        controller = elementController,
                        stageScale = 0.5f,
                        enabled = true,
                    ) {
                        Easel(animatic)
                    }
                }
            }
        }
        waitForIdle()

        // 1. Open inline editor on s1:eyebrow, type text, and commit with Cmd+Enter.
        runOnIdle { assertTrue(elementController.startInlineTextEdit("s1:eyebrow")) }
        waitForIdle()
        onNodeWithTag("ciacola-doc-inline-text-field").performTextInput(" Z")
        onNodeWithTag("ciacola-doc-inline-text-field").performKeyInput {
            keyDown(Key.MetaLeft)
            pressKey(Key.Enter)
            keyUp(Key.MetaLeft)
        }
        waitForIdle()
        assertEquals(
            "FOUNDATION / PROVISIONAL Z",
            store.document.slides[0].nodes.first { it.id == "s1:eyebrow" }.text?.value?.plainText,
        )

        // 2. Open inline editor again, type text, and click Commit button.
        runOnIdle { assertTrue(elementController.startInlineTextEdit("s1:eyebrow")) }
        waitForIdle()
        onNodeWithTag("ciacola-doc-inline-text-field").performTextInput("!")
        onNodeWithTag("ciacola-doc-inline-text-commit").performClick()
        waitForIdle()
        assertEquals(
            "FOUNDATION / PROVISIONAL Z!",
            store.document.slides[0].nodes.first { it.id == "s1:eyebrow" }.text?.value?.plainText,
        )
    }
}
