package dev.sebastiano.ciacola.studio

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.bnorm.storyboard.easel.rememberAnimatic
import dev.sebastiano.ciacola.DeckCatalog
import dev.sebastiano.ciacola.document.BeatSpec
import dev.sebastiano.ciacola.document.CanonicalJson
import dev.sebastiano.ciacola.document.DeckDocument
import dev.sebastiano.ciacola.document.DeckDocumentStore
import dev.sebastiano.ciacola.document.DeckFileSync
import dev.sebastiano.ciacola.document.DeckLoadState
import dev.sebastiano.ciacola.document.DeckThemeSpec
import dev.sebastiano.ciacola.document.Fill
import dev.sebastiano.ciacola.document.FrameSpec
import dev.sebastiano.ciacola.document.NodeSpec
import dev.sebastiano.ciacola.document.RenderSession
import dev.sebastiano.ciacola.document.RichTextValue
import dev.sebastiano.ciacola.document.SlideSpec
import dev.sebastiano.ciacola.document.SyncState
import dev.sebastiano.ciacola.document.TextPayload
import dev.sebastiano.ciacola.document.render.DocumentRenderConfig
import dev.sebastiano.ciacola.document.render.buildDocumentStoryboard
import dev.sebastiano.ciacola.forExternalFile
import dev.sebastiano.ciacola.inspector.design.DocumentThemeInspectorController
import dev.sebastiano.ciacola.inspector.element.DocumentElementInspectorController
import dev.sebastiano.ciacola.inspector.lint.DocumentDeckLintController
import dev.sebastiano.ciacola.inspector.lint.DocumentLintIssue
import dev.sebastiano.ciacola.inspector.lint.DocumentLintSource
import dev.sebastiano.ciacola.inspector.lint.LintSeverity
import dev.sebastiano.ciacola.inspector.sync.CloseGuardState
import dev.sebastiano.ciacola.inspector.sync.DocumentSyncController
import dev.sebastiano.ciacola.loadFoundationTestDocument
import dev.sebastiano.ciacola.timeline.DocumentTimelineController
import dev.sebastiano.ciacola.ui.CiacolaJewelTheme
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

@OptIn(ExperimentalTestApi::class)
class StudioUxPolishTest {

    @Test
    fun preOpenDeckSessionDecodesBothFileAndResourceDecksAtomicallyBeforeReturn() = runBlocking {
        val tempRoot = Files.createTempDirectory("studio-atomic-session")
        val decksDir = tempRoot.resolve("decks").also { Files.createDirectories(it) }
        val recoveryDir = tempRoot.resolve("recovery").also { Files.createDirectories(it) }
        try {
            val entry = DeckCatalog.byId(DeckCatalog.FOUNDATION_ID)
            val doc = loadFoundationTestDocument()
            val deckFile = decksDir.resolve("${entry.id}.deck.json")
            Files.writeString(deckFile, CanonicalJson.print(doc), StandardCharsets.UTF_8)

            // File-backed session arrives ALREADY loaded (zero Loading flash frame).
            val fileSession = preOpenDeckSession(
                entry = entry,
                recoveryRootDir = recoveryDir,
                loadToken = 1,
                decksDirProperty = decksDir.toString(),
            )
            try {
                assertEquals(DeckLoadState.Loaded, fileSession.store.load)
                assertEquals(doc.id, fileSession.store.document.id)
                assertTrue(fileSession.store.document.slides.isNotEmpty())
                assertNotNull(fileSession.fileSync)
            } finally {
                fileSession.fileSync?.close()
            }

            // Resource-backed session also arrives ALREADY loaded.
            val resSession = preOpenDeckSession(
                entry = entry,
                recoveryRootDir = recoveryDir,
                loadToken = 2,
                decksDirProperty = "",
            )
            assertEquals(DeckLoadState.Loaded, resSession.store.load)
            assertTrue(resSession.store.document.slides.isNotEmpty())
        } finally {
            tempRoot.toFile().deleteRecursively()
        }
    }

    @Test
    fun unifiedWorkspaceUndoCommitsCrossControllerGesturesEmitsToastAndShowsPersistentDirtyDot() = runComposeUiTest {
        val doc = loadFoundationTestDocument()
        val store = DeckDocumentStore(doc, initialLoad = DeckLoadState.Loaded)
        val timelineController = DocumentTimelineController(store)
        val elementController = DocumentElementInspectorController(store)
        val themeController = DocumentThemeInspectorController(store)
        val lintController = DocumentDeckLintController(store)
        val workspace = StudioWorkspaceController(
            store = store,
            timelineController = timelineController,
            elementController = elementController,
            themeController = themeController,
            lintController = lintController,
        )

        // 1. Open a gesture on Theme, then start a gesture on Element -> Theme gesture commits cleanly.
        val firstSlideId = doc.slides.first().id
        val firstNodeId = doc.slides.first().nodes.first().id
        elementController.selectLocation(firstSlideId, 0)
        elementController.selectNode(firstNodeId)

        assertTrue(themeController.beginGesture("Theme drag"))
        themeController.setThemeColor("accent", "#38BDF8", useActiveGesture = true)
        assertTrue(themeController.hasActiveTransaction)

        assertTrue(elementController.beginGesture("Element drag"))
        assertFalse(themeController.hasActiveTransaction, "Starting an Element gesture must commit dangling Theme gesture")
        elementController.setOpacity(0.5, useActiveGesture = true)
        elementController.commitGesture()

        // 2. Undo routed via controller emits top-center toast and reverts step-by-step.
        elementController.performUndo()
        assertEquals("⌘Z Undo", workspace.lastShortcutToast?.second)
        assertTrue(store.isDirty, "Theme edit remains committed so store is still dirty")

        elementController.performRedo()
        assertEquals("⇧⌘Z Redo", workspace.lastShortcutToast?.second)

        // 3. Save state remains visible across ALL 4 Studio Inspector tabs.
        val storyboard = buildDocumentStoryboard(
            session = RenderSession.forStill(doc),
            config = DocumentRenderConfig(),
        )
        setContent {
            val animatic = rememberAnimatic(storyboard = { storyboard })
            val scope = rememberCoroutineScope()
            CiacolaJewelTheme(isDark = true) {
                Box(Modifier.size(440.dp, 680.dp)) {
                    StudioUnifiedInspectorPane(
                        workspace = workspace,
                        animatic = animatic,
                        scope = scope,
                    )
                }
            }
        }
        waitForIdle()

        // Save state moved from an unlabelled amber dot into the App Bar's plain-language indicator.
        for (tab in StudioInspectorTab.entries) {
            onNodeWithTag("ciacola-studio-tab-${tab.name.lowercase()}", useUnmergedTree = true).performClick()
            waitForIdle()
            assertEquals(tab, workspace.activeTab)
            assertEquals(StudioSaveState.Saving, workspace.saveState, "Unsaved edits must read as Saving…")
        }
        assertTrue(onAllNodesWithTag("ciacola-studio-dirty-dot", useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        lintController.close()
    }

    @Test
    fun preOpenDeckSessionOpensReadOnlyExternalFileWithoutThrowing() = runBlocking {
        val tempRoot = Files.createTempDirectory("studio-readonly-external-")
        val recoveryDir = tempRoot.resolve("recovery").also { Files.createDirectories(it) }
        val readOnlyFile = tempRoot.resolve("locked-talk.deck.json")
        try {
            val doc = loadFoundationTestDocument()
            Files.writeString(readOnlyFile, CanonicalJson.print(doc), StandardCharsets.UTF_8)
            Files.setPosixFilePermissions(readOnlyFile, PosixFilePermissions.fromString("r--r--r--"))

            val entry = dev.sebastiano.ciacola.DeckEntry.forExternalFile(readOnlyFile)
            val session = preOpenDeckSession(
                entry = entry,
                recoveryRootDir = recoveryDir,
                loadToken = 7,
                decksDirProperty = null,
            )
            assertEquals(DeckLoadState.Loaded, session.store.load)
            assertTrue(session.store.isReadOnly)
            assertIs<SyncState.ReadOnly>(session.store.sync)
            assertNull(session.fileSync)
            assertEquals(doc.id, session.store.document.id)
        } finally {
            runCatching {
                Files.setPosixFilePermissions(readOnlyFile, PosixFilePermissions.fromString("rw-r--r--"))
            }
            tempRoot.toFile().deleteRecursively()
        }
    }

    @Test
    fun skippedSlideIndexMapsBetweenAnimaticSceneIndexAndDocumentLintSlideIndex() = runComposeUiTest {
        val doc = DeckDocument(
            id = "skipped-lint-map",
            title = "Skipped Map",
            defaultTheme = "dark",
            themes = mapOf("dark" to DeckThemeSpec(preset = "stageDark")),
            slides = listOf(
                SlideSpec(
                    id = "s-skip",
                    title = "Hidden Slide",
                    skipped = true,
                    background = Fill.Solid("#101218"),
                    nodes = listOf(
                        NodeSpec(
                            id = "n0",
                            frame = FrameSpec(x = 100.0, y = 100.0, w = 400.0, h = 100.0),
                            text = TextPayload(value = RichTextValue.Plain("Skipped"), size = 40.0),
                        ),
                    ),
                ),
                SlideSpec(
                    id = "s-visible-1",
                    title = "Visible One",
                    background = Fill.Solid("#101218"),
                    nodes = listOf(
                        NodeSpec(
                            id = "n1",
                            frame = FrameSpec(x = 100.0, y = 100.0, w = 400.0, h = 100.0),
                            text = TextPayload(value = RichTextValue.Plain("First visible"), size = 40.0),
                        ),
                    ),
                ),
            ),
        )
        val store = DeckDocumentStore(doc, initialLoad = DeckLoadState.Loaded)
        val timelineController = DocumentTimelineController(store)
        val elementController = DocumentElementInspectorController(store)
        val themeController = DocumentThemeInspectorController(store)
        val lintController = DocumentDeckLintController(store)
        val workspace = StudioWorkspaceController(
            store = store,
            timelineController = timelineController,
            elementController = elementController,
            themeController = themeController,
            lintController = lintController,
        )
        val storyboard = buildDocumentStoryboard(
            session = RenderSession.forStill(doc),
            config = DocumentRenderConfig(),
        )

        lateinit var animaticRef: dev.bnorm.storyboard.easel.Animatic
        setContent {
            animaticRef = rememberAnimatic(storyboard = { storyboard })
        }
        waitForIdle()

        assertEquals(0, animaticRef.presentedIndex.sceneIndex)
        assertEquals(1, rawSlideIndexForPresented(workspace, animaticRef))

        val issueOnVisibleOne = DocumentLintIssue(
            id = "test-issue",
            code = "LOW_CONTRAST",
            severity = LintSeverity.Error,
            source = DocumentLintSource.StaticContrast,
            slideIndex = 1,
            slideId = "s-visible-1",
            beatIndex = 0,
            nodeId = "n1",
            themeName = "dark",
            title = "Low contrast",
            detail = "Test",
        )
        assertEquals(0, nonSkippedSceneIndexForIssue(workspace, issueOnVisibleOne))
        val issueOnSkipped = issueOnVisibleOne.copy(slideIndex = 0, slideId = "s-skip", nodeId = "n0")
        assertNull(nonSkippedSceneIndexForIssue(workspace, issueOnSkipped))

        lintController.close()
    }

    @Test
    fun flushOutgoingBeforeSwapPersistsLateEditsAndHoldsOnExternalConflict(): Unit = runBlocking {
        val tempRoot = Files.createTempDirectory("studio-preswap-flush-")
        try {
            val deckFile = tempRoot.resolve("outgoing.deck.json")
            val recoveryDir = tempRoot.resolve("recovery")
            val initialDoc = DeckDocument(
                format = 1,
                id = "outgoing",
                title = "Initial Title",
                defaultTheme = "dark",
                themes = mapOf("dark" to DeckThemeSpec(preset = "stageDark")),
                slides = listOf(SlideSpec(id = "s0", title = "Slide 1", beats = listOf(BeatSpec()))),
            )
            Files.writeString(deckFile, CanonicalJson.print(initialDoc))

            val store = DeckDocumentStore()
            val fileSync = DeckFileSync(targetFile = deckFile, recoveryRootDir = recoveryDir, store = store)
            val syncController = DocumentSyncController(store = store, fileSync = fileSync)
            try {
                assertEquals(DeckLoadState.Loaded, fileSync.open())
                val revAtSwitchStart = store.revision
                val dirtyAtSwitchStart = store.isDirty

                // Simulate a late edit made while the incoming deck was pre-opening asynchronously.
                assertTrue(store.edit("Late edit during switch") { d -> d.copy(title = "Late Saved Title") })
                assertTrue(store.isDirty)

                assertTrue(
                    flushOutgoingBeforeSwap(syncController, revAtSwitchStart, dirtyAtSwitchStart),
                    "Clean late edit must flush before swapping sessions",
                )
                assertEquals(SyncState.Saved, store.sync)
                assertTrue(
                    Files.readString(deckFile).contains("\"Late Saved Title\""),
                    "Late edit must be written to disk before outgoing session is disposed",
                )

                // Now simulate a second late edit that conflicts with an external disk modification.
                val revBeforeConflict = store.revision
                assertTrue(store.edit("Conflicting late edit") { d -> d.copy(title = "Mine Late") })
                Files.writeString(deckFile, CanonicalJson.print(initialDoc.copy(title = "Theirs External")))

                assertFalse(
                    flushOutgoingBeforeSwap(syncController, revBeforeConflict, dirtyAtSwitchStart = false),
                    "Conflicting late edit must hold the swap and open the close guard",
                )
                assertIs<CloseGuardState.Confirming>(syncController.closeGuard)
            } finally {
                fileSync.close()
            }
        } finally {
            tempRoot.toFile().deleteRecursively()
        }
    }

    @Test
    fun resolveDeckFontsDirLoadsDeclaredFontsStoredAlongsideExternalDeck() {
        val tempRoot = Files.createTempDirectory("studio-external-fonts-")
        try {
            val deckFile = tempRoot.resolve("custom.deck.json")
            val fontsSubdir = Files.createDirectories(tempRoot.resolve("fonts"))
            val bundledFontBytes = requireNotNull(
                javaClass.classLoader.getResourceAsStream("fonts/Inter-Regular.ttf"),
                { "Bundled Inter-Regular.ttf resource must exist" },
            ).use { it.readAllBytes() }
            Files.write(fontsSubdir.resolve("SidecarFont.ttf"), bundledFontBytes)

            val doc = DeckDocument(
                format = 1,
                id = "external-fonts",
                title = "External Fonts",
                defaultTheme = "dark",
                themes = mapOf("dark" to DeckThemeSpec(preset = "stageDark")),
                fonts = listOf(
                    dev.sebastiano.ciacola.core.fonts.DeckFontDeclaration(
                        family = "SidecarFont",
                        src = "fonts/SidecarFont.ttf",
                        weight = 400,
                    ),
                ),
                slides = listOf(SlideSpec(id = "s0", title = "Slide 1", beats = listOf(BeatSpec()))),
            )
            Files.writeString(deckFile, CanonicalJson.print(doc))

            val fileSource = dev.sebastiano.ciacola.decks.DeckSource.File(file = deckFile, assetsDir = null)
            val resolvedDir = resolveDeckFontsDir(fileSource)
            assertEquals(tempRoot.toAbsolutePath().normalize().toString(), resolvedDir)

            val resolver = assertNotNull(
                dev.sebastiano.ciacola.document.render.createDocumentFontResolver(doc, fontsDir = resolvedDir),
            )
            assertNotNull(
                resolver.resolveFace(
                    "SidecarFont",
                    androidx.compose.ui.text.font.FontWeight.Normal,
                    androidx.compose.ui.text.font.FontStyle.Normal,
                ),
                "Sidecar font alongside external deck must load",
            )
            assertNull(
                resolveDeckFontsDir(
                    dev.sebastiano.ciacola.decks.DeckSource.Resource("decks/foundation.deck.json", "decks/foundation.assets"),
                ),
            )
        } finally {
            tempRoot.toFile().deleteRecursively()
        }
    }

    @Test
    fun resolveDeckAssetsRootDirFallsBackToExternalDeckDirectoryAndRendersSiblingImage() = runComposeUiTest {
        val tempRoot = Files.createTempDirectory("studio-external-assets-")
        try {
            val deckFile = tempRoot.resolve("custom.deck.json")
            val siblingImage = tempRoot.resolve("hero.png")
            val img = java.awt.image.BufferedImage(16, 16, java.awt.image.BufferedImage.TYPE_INT_ARGB)
            for (y in 0 until img.height) {
                for (x in 0 until img.width) {
                    img.setRGB(x, y, 0xFF33AA55.toInt())
                }
            }
            javax.imageio.ImageIO.write(img, "png", siblingImage.toFile())

            val fileWithoutAssetsDir = dev.sebastiano.ciacola.decks.DeckSource.File(file = deckFile, assetsDir = null)
            val resolvedFallback = resolveDeckAssetsRootDir(fileWithoutAssetsDir, fallbackResourceDir = "decks/fallback.assets")
            assertEquals(tempRoot.toAbsolutePath().normalize().toString(), resolvedFallback)

            val assetsDir = Files.createDirectories(tempRoot.resolve("custom.assets"))
            val fileWithAssetsDir = dev.sebastiano.ciacola.decks.DeckSource.File(file = deckFile, assetsDir = assetsDir)
            assertEquals(assetsDir.toString(), resolveDeckAssetsRootDir(fileWithAssetsDir, fallbackResourceDir = null))

            assertEquals(
                "decks/foundation.assets",
                resolveDeckAssetsRootDir(
                    dev.sebastiano.ciacola.decks.DeckSource.Resource("decks/foundation.deck.json", "decks/foundation.assets"),
                    fallbackResourceDir = "decks/foundation.assets",
                ),
            )

            setContent {
                dev.sebastiano.ciacola.document.render.DocumentImagePayloadView(
                    deckId = "external-img",
                    src = "hero.png",
                    fit = "contain",
                    tint = null,
                    assetsRootDir = resolvedFallback,
                    modifier = Modifier.size(64.dp),
                )
            }
            waitForIdle()
            onNodeWithContentDescription("hero.png").assertExists()
            onNodeWithText("Missing image: hero.png").assertDoesNotExist()
        } finally {
            tempRoot.toFile().deleteRecursively()
        }
    }
}
