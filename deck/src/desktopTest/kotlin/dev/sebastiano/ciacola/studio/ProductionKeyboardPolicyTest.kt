package dev.sebastiano.ciacola.studio

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import dev.bnorm.storyboard.Storyboard
import dev.bnorm.storyboard.easel.Animatic
import dev.bnorm.storyboard.easel.Easel
import dev.bnorm.storyboard.easel.LocalExportEmittedFrameIndex
import dev.bnorm.storyboard.easel.LocalExportFps
import dev.bnorm.storyboard.easel.SceneMode
import dev.bnorm.storyboard.easel.ScenePreview
import dev.bnorm.storyboard.easel.rememberAnimatic
import dev.sebastiano.ciacola.DeckCatalog
import dev.sebastiano.ciacola.document.BeatSpec
import dev.sebastiano.ciacola.document.DeckDocument
import dev.sebastiano.ciacola.document.DeckDocumentStore
import dev.sebastiano.ciacola.document.DeckLoadState
import dev.sebastiano.ciacola.document.DeckThemeSpec
import dev.sebastiano.ciacola.document.FrameSpec
import dev.sebastiano.ciacola.document.LocalRenderSession
import dev.sebastiano.ciacola.document.NodeSpec
import dev.sebastiano.ciacola.document.RenderPurpose
import dev.sebastiano.ciacola.document.RenderSession
import dev.sebastiano.ciacola.document.RichTextValue
import dev.sebastiano.ciacola.document.SlideSpec
import dev.sebastiano.ciacola.document.SpecimenPayload
import dev.sebastiano.ciacola.document.TextPayload
import dev.sebastiano.ciacola.document.deckSpecimens
import dev.sebastiano.ciacola.document.render.DocumentKeyboardCaptureController
import dev.sebastiano.ciacola.document.render.DocumentRenderConfig
import dev.sebastiano.ciacola.document.render.buildDocumentStoryboard
import dev.sebastiano.ciacola.inspector.design.DocumentThemeInspectorController
import dev.sebastiano.ciacola.inspector.element.DocumentElementInspectorController
import dev.sebastiano.ciacola.inspector.lint.DocumentDeckLintController
import dev.sebastiano.ciacola.timeline.DocumentTimelineController
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class ProductionKeyboardPolicyTest {

    private fun twoSlideDoc(): DeckDocument = DeckDocument(
        id = "kb-policy-test",
        title = "Keyboard Policy Test",
        themes = mapOf("dark" to DeckThemeSpec()),
        defaultTheme = "dark",
        slides = listOf(
            SlideSpec(
                id = "s1",
                title = "Slide 1",
                beats = listOf(BeatSpec()),
                nodes = listOf(
                    NodeSpec(
                        id = "n1",
                        frame = FrameSpec(100.0, 100.0, 600.0, 120.0),
                        text = TextPayload(value = RichTextValue.Plain("Original Title")),
                    ),
                ),
            ),
            SlideSpec(
                id = "s2",
                title = "Slide 2",
                beats = listOf(BeatSpec()),
                nodes = listOf(
                    NodeSpec(
                        id = "n2",
                        frame = FrameSpec(100.0, 100.0, 600.0, 120.0),
                        text = TextPayload(value = RichTextValue.Plain("Slide 2 Title")),
                    ),
                ),
            ),
        ),
    )

    private fun createWorkspace(
        store: DeckDocumentStore,
        keyboardController: DocumentKeyboardCaptureController,
    ): StudioWorkspaceController = StudioWorkspaceController(
        store = store,
        timelineController = DocumentTimelineController(store),
        elementController = DocumentElementInspectorController(store),
        themeController = DocumentThemeInspectorController(store),
        lintController = DocumentDeckLintController(store),
        keyboardController = keyboardController,
    )

    @Test
    fun productionWiring_studioOpen_and_closed_escExitsCaptureWithoutOpeningOverview_cmdZStaysLocal_pageDownAdvances() =
        runComposeUiTest {
            val store = DeckDocumentStore(twoSlideDoc(), initialLoad = DeckLoadState.Loaded)
            // Seed one undoable edit on the document store so we can prove ⌘Z stays local to specimen.
            store.edit("Rename title") { doc ->
                doc.copy(title = "Edited Title")
            }
            assertTrue(store.canUndo)

            val keyboardController = DocumentKeyboardCaptureController()
            val workspace = createWorkspace(store, keyboardController)
            var studioOpen by mutableStateOf(true)
            var overviewOpenedCount by mutableStateOf(0)
            var specimenCmdZSeen by mutableStateOf(0)
            lateinit var animatic: Animatic

            val session = RenderSession.forPresent(store = store)
            val sb = buildDocumentStoryboard(session = session, config = DocumentRenderConfig())

            setContent {
                animatic = rememberAnimatic(storyboard = { sb })
                val menuWindow = rememberDeckMenuWindow(
                    activeDeckProvider = { DeckCatalog.entries.first() },
                    onSelectDeck = {},
                    animaticProvider = { animatic },
                    isTextInputActiveProvider = { false },
                    isStudioOpenProvider = { studioOpen },
                    keyboardController = keyboardController,
                    extraPreviewKeyHandler = { ev -> workspace.handleStudioPreviewKey(ev) },
                )

                val fieldFocus = remember { FocusRequester() }
                var specimenText by remember { mutableStateOf("hello markdown") }

                menuWindow.decorator.decorate {
                    // Simulate EaselOverview's KeyUp(Escape) listener underneath the DeckMenuWindow decorator.
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .onKeyEvent { ev ->
                                if (ev.type == KeyEventType.KeyUp && ev.key == Key.Escape) {
                                    overviewOpenedCount++
                                    true
                                } else {
                                    false
                                }
                            },
                    ) {
                        BasicTextField(
                            value = specimenText,
                            onValueChange = { specimenText = it },
                            modifier = Modifier
                                .focusRequester(fieldFocus)
                                .onPreviewKeyEvent { ev ->
                                    if (ev.type == KeyEventType.KeyDown && ev.key == Key.Z) {
                                        specimenCmdZSeen++
                                    }
                                    false
                                }
                                .testTag("specimen-markdown-field"),
                        )
                    }
                }
            }

            waitForIdle()
            onNodeWithTag("specimen-markdown-field").requestFocus()
            waitForIdle()

            // --- Scenario 1: Studio OPEN + captured "interactive": "keyboard" specimen ---
            studioOpen = true
            keyboardController.isEditMode = true
            keyboardController.enterInteractive(slideId = "s1", nodeId = "specimen-md")
            waitForIdle()

            // ⌘Z MUST stay local to the specimen's BasicTextField and MUST NOT undo the DeckDocumentStore!
            onNodeWithTag("specimen-markdown-field").performKeyInput {
                withKeyDown(Key.MetaLeft) {
                    pressKey(Key.Z)
                }
            }
            waitForIdle()
            assertEquals("Edited Title", store.document.title, "⌘Z while specimen captured must NOT undo DeckDocumentStore")
            assertTrue(specimenCmdZSeen >= 1, "Specimen BasicTextField should receive local ⌘Z")

            // Esc MUST exit specimen capture and swallow KeyUp so Overview NEVER opens!
            onNodeWithTag("specimen-markdown-field").performKeyInput {
                pressKey(Key.Escape)
            }
            waitForIdle()
            assertFalse(keyboardController.isCaptured, "Esc must exit specimen keyboard capture while Studio is open")
            assertEquals(0, overviewOpenedCount, "Esc exiting specimen capture must NOT open Overview")

            // Re-enter capture while Studio is OPEN: PageDown MUST advance the deck and exit capture!
            keyboardController.enterInteractive(slideId = "s1", nodeId = "specimen-md")
            waitForIdle()
            onNodeWithTag("specimen-markdown-field").performKeyInput {
                pressKey(Key.PageDown)
            }
            waitForIdle()
            assertFalse(keyboardController.isCaptured, "PageDown must exit specimen capture")
            assertEquals(1, animatic.targetIndex.sceneIndex, "PageDown must advance to slide 2 even while captured with Studio open")

            // --- Scenario 2: Studio CLOSED + captured "interactive": "keyboard" specimen ---
            studioOpen = false
            keyboardController.isEditMode = false
            keyboardController.enterInteractive(slideId = "s2", nodeId = "specimen-md")
            waitForIdle()

            // Esc exits capture without opening Overview.
            onNodeWithTag("specimen-markdown-field").performKeyInput {
                pressKey(Key.Escape)
            }
            waitForIdle()
            assertFalse(keyboardController.isCaptured, "Esc must exit capture in Show Mode")
            assertEquals(0, overviewOpenedCount, "Esc leaving capture must NOT open Overview")

            // Second Esc (now NOT captured) reaches Overview cleanly!
            onNodeWithTag("specimen-markdown-field").performKeyInput {
                pressKey(Key.Escape)
            }
            waitForIdle()
            assertEquals(1, overviewOpenedCount, "Second Esc after leaving capture should open Overview")
        }

    @Test
    fun overviewPerThumbnailStoryboard_isolatesEachThumbnailIntoOwnStillRenderSession() = runComposeUiTest {
        val observedSessions = mutableMapOf<String, RenderSession>()
        val registry = deckSpecimens {
            specimen("test.probe", label = "Probe") {
                content {
                    val session = LocalRenderSession.current!!
                    val slideId = session.document.slides.first().id
                    observedSessions[slideId] = session
                    session.getOrPutRetained<String>("marker") { slideId }
                }
            }
        }
        val doc = DeckDocument(
            id = "overview-isolation",
            slides = listOf(
                SlideSpec(
                    id = "s1",
                    beats = listOf(BeatSpec()),
                    nodes = listOf(
                        NodeSpec(
                            id = "p1",
                            frame = FrameSpec(0.0, 0.0, 400.0, 300.0),
                            specimen = SpecimenPayload(ref = "test.probe"),
                        ),
                    ),
                ),
                SlideSpec(
                    id = "s2",
                    beats = listOf(BeatSpec()),
                    nodes = listOf(
                        NodeSpec(
                            id = "p2",
                            frame = FrameSpec(0.0, 0.0, 400.0, 300.0),
                            specimen = SpecimenPayload(ref = "test.probe"),
                        ),
                    ),
                ),
            ),
        )
        val overviewSb = buildPerThumbnailOverviewStoryboard(
            frozenDoc = doc,
            config = DocumentRenderConfig(specimens = registry),
        )

        setContent {
            Column(Modifier.fillMaxSize()) {
                ScenePreview(storyboard = overviewSb, index = Storyboard.Index(0, 0), sceneMode = SceneMode.Preview)
                ScenePreview(storyboard = overviewSb, index = Storyboard.Index(1, 0), sceneMode = SceneMode.Preview)
            }
        }
        waitForIdle()

        val s1Session = observedSessions["s1"]
        val s2Session = observedSessions["s2"]
        assertTrue(s1Session != null && s2Session != null, "Both thumbnails should have rendered")
        assertNotSame(s1Session, s2Session, "Each Overview thumbnail must own an isolated Still RenderSession")
        assertEquals(RenderPurpose.Still, s1Session.purpose)
        assertEquals(RenderPurpose.Still, s2Session.purpose)
        assertEquals("s1", s1Session.getOrPutRetained("marker") { "unexpected" })
        assertEquals("s2", s2Session.getOrPutRetained("marker") { "unexpected" })
    }

    @Test
    fun dualPassVideoStoryboard_isolatesMeasurementPassFromRenderPassRenderSession() = runComposeUiTest {
        val observedMeasurementSessions = mutableListOf<RenderSession>()
        val observedRenderSessions = mutableListOf<RenderSession>()
        val registry = deckSpecimens {
            specimen("test.videoProbe", label = "Video Probe") {
                content {
                    val session = LocalRenderSession.current!!
                    val emitted = LocalExportEmittedFrameIndex.current
                    if (emitted == null) {
                        observedMeasurementSessions += session
                        session.getOrPutRetained<String>("pass") { "measurement" }
                    } else {
                        observedRenderSessions += session
                        session.getOrPutRetained<String>("pass") { "render" }
                    }
                }
            }
        }
        val doc = DeckDocument(
            id = "video-isolation",
            slides = listOf(
                SlideSpec(
                    id = "s1",
                    beats = listOf(BeatSpec()),
                    nodes = listOf(
                        NodeSpec(
                            id = "v1",
                            frame = FrameSpec(0.0, 0.0, 400.0, 300.0),
                            specimen = SpecimenPayload(ref = "test.videoProbe"),
                        ),
                    ),
                ),
            ),
        )
        val dualSb = buildDualPassVideoStoryboard(
            frozenDoc = doc,
            config = DocumentRenderConfig(specimens = registry),
        )

        var isRenderPass by mutableStateOf(false)
        setContent {
            val animatic = rememberAnimatic(storyboard = { dualSb })
            if (isRenderPass) {
                CompositionLocalProvider(
                    LocalExportEmittedFrameIndex provides 0L,
                    LocalExportFps provides 60,
                ) {
                    Easel(animatic, mode = SceneMode.Preview)
                }
            } else {
                Easel(animatic, mode = SceneMode.Preview)
            }
        }
        waitForIdle()
        isRenderPass = true
        waitForIdle()

        assertTrue(observedMeasurementSessions.isNotEmpty(), "Measurement pass should render")
        assertTrue(observedRenderSessions.isNotEmpty(), "Render pass should render")
        val mSession = observedMeasurementSessions.first()
        val rSession = observedRenderSessions.first()
        assertNotSame(mSession, rSession, "Video measurement pass and render pass must have distinct RenderSessions")
        assertEquals(RenderPurpose.Video, mSession.purpose)
        assertEquals(RenderPurpose.Video, rSession.purpose)
        assertEquals("measurement", mSession.getOrPutRetained("pass") { "leaked" })
        assertEquals("render", rSession.getOrPutRetained("pass") { "leaked" })
    }
}
