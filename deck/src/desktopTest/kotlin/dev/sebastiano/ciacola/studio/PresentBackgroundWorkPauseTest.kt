package dev.sebastiano.ciacola.studio

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.bnorm.storyboard.Storyboard
import dev.bnorm.storyboard.easel.Animatic
import dev.bnorm.storyboard.easel.Easel
import dev.bnorm.storyboard.easel.LocalOverviewOpen
import dev.bnorm.storyboard.easel.LocalPresentingFullscreen
import dev.bnorm.storyboard.easel.rememberAnimatic
import dev.bnorm.storyboard.plus
import dev.sebastiano.ciacola.document.BeatSpec
import dev.sebastiano.ciacola.document.CanonicalJson
import dev.sebastiano.ciacola.document.DeckDocument
import dev.sebastiano.ciacola.document.DeckDocumentCodec
import dev.sebastiano.ciacola.document.DeckDocumentStore
import dev.sebastiano.ciacola.document.DeckFileSync
import dev.sebastiano.ciacola.document.DeckLoadState
import dev.sebastiano.ciacola.document.DeckSpecimenRegistry
import dev.sebastiano.ciacola.document.DeckThemeSpec
import dev.sebastiano.ciacola.document.Fill
import dev.sebastiano.ciacola.document.FrameSpec
import dev.sebastiano.ciacola.document.NodeSpec
import dev.sebastiano.ciacola.document.RenderSession
import dev.sebastiano.ciacola.document.RichTextValue
import dev.sebastiano.ciacola.document.SlideSpec
import dev.sebastiano.ciacola.document.TextPayload
import dev.sebastiano.ciacola.document.render.DocumentRenderConfig
import dev.sebastiano.ciacola.document.render.rememberDocumentStoryboard
import dev.sebastiano.ciacola.inspector.StageInspectionRegistry
import dev.sebastiano.ciacola.inspector.design.DocumentThemeInspectorController
import dev.sebastiano.ciacola.inspector.element.DocumentElementInspectorController
import dev.sebastiano.ciacola.inspector.lint.DocumentDeckLintController
import dev.sebastiano.ciacola.inspector.lint.LintAuditScope
import dev.sebastiano.ciacola.review.ReviewClaim
import dev.sebastiano.ciacola.review.ReviewClaims
import dev.sebastiano.ciacola.review.ReviewCommentStatus
import dev.sebastiano.ciacola.review.ReviewCommentStore
import dev.sebastiano.ciacola.review.ReviewStageCoordinator
import dev.sebastiano.ciacola.review.ReviewTargetHit
import dev.sebastiano.ciacola.review.SlideReviewComment
import dev.sebastiano.ciacola.review.rememberReviewStageCoordinator
import dev.sebastiano.ciacola.review.rememberSlideReviewWindow
import dev.sebastiano.ciacola.timeline.DocumentTimelineController
import dev.sebastiano.ciacola.timeline.rememberDocumentTimelineWindow
import dev.sebastiano.ciacola.ui.CiacolaJewelTheme
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@OptIn(ExperimentalTestApi::class)
class PresentBackgroundWorkPauseTest {

    private val reviewJson = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private fun multiSlideDoc(slideCount: Int = 6): DeckDocument = DeckDocument(
        id = "present-pause-doc",
        title = "Present Pause Integration",
        defaultTheme = "dark",
        themes = mapOf(
            "dark" to DeckThemeSpec(
                preset = "stageDark",
                colors = mapOf("background" to "#111827", "foreground" to "#F8FAFC"),
            ),
        ),
        slides = List(slideCount) { idx ->
            SlideSpec(
                id = "s${idx + 1}",
                title = "Slide ${idx + 1}",
                background = Fill.Solid("#111827"),
                beats = listOf(BeatSpec(notes = "Initial note $idx")),
                nodes = listOf(
                    NodeSpec(
                        id = "s${idx + 1}:title",
                        frame = FrameSpec(x = 120.0, y = 140.0, w = 800.0, h = 90.0),
                        text = TextPayload(
                            value = RichTextValue.Plain("Headline ${idx + 1}"),
                            color = if (idx == slideCount - 1) "#182030" else "#F8FAFC",
                            size = 42.0,
                        ),
                    ),
                ),
            )
        },
    )

    private fun sampleComment(id: String, note: String = "Fix spacing"): SlideReviewComment = SlideReviewComment(
        id = id,
        sceneIndex = 0,
        frameIndex = 0,
        sceneCount = 6,
        frameCount = 1,
        sceneTitle = "Slide 1",
        sceneHeader = "Slide 1",
        themePreset = "StageDark",
        stageX = 240f,
        stageY = 180f,
        target = ReviewTargetHit(kind = "Text", label = "Headline 1", textSnippet = "Headline 1"),
        note = note,
        status = ReviewCommentStatus.Queued,
        createdAt = "2026-11-27T00:00:00Z",
    )

    @Test
    fun enteringPresentFullscreenAndPresenterViewPausesInFlightLintAndResumesCleanly() = runComposeUiTest {
        mainClock.autoAdvance = false
        val doc = multiSlideDoc(slideCount = 6)
        val store = DeckDocumentStore(doc, initialLoad = DeckLoadState.Loaded)
        val lintController = DocumentDeckLintController(store = store, specimens = DeckSpecimenRegistry.Empty)
        var fullscreen by mutableStateOf(false)
        var presenterOpen by mutableStateOf(false)
        lateinit var workspace: StudioWorkspaceController
        lateinit var appScope: CoroutineScope

        try {
            setContent {
                appScope = rememberCoroutineScope()
                val timelineController = remember { DocumentTimelineController(store = store) }
                val elementController = remember { DocumentElementInspectorController(store = store) }
                val themeController = remember { DocumentThemeInspectorController(store = store) }
                val renderSession = remember { RenderSession.forPresent(store, freeClockMillis = { 0L }) }
                val storyboard = rememberDocumentStoryboard(
                    session = renderSession,
                    config = DocumentRenderConfig(themeName = "dark"),
                )
                val animatic = rememberAnimatic(storyboard = { storyboard })
                workspace = remember {
                    StudioWorkspaceController(
                        store = store,
                        timelineController = timelineController,
                        elementController = elementController,
                        themeController = themeController,
                        lintController = lintController,
                    ).apply {
                        isPresenterVisibleProvider = { presenterOpen }
                        issuesDockOpen = true
                    }
                }
                val timelineWindow = rememberDocumentTimelineWindow(
                    controller = timelineController,
                    initialDocked = true,
                    initialExpanded = false,
                    alternateBottomContent = if (workspace.issuesDockOpen) {
                        { StudioBottomIssuesDockBody(workspace = workspace, animatic = animatic, scope = appScope) }
                    } else {
                        null
                    },
                    showStudioChrome = workspace.isAppBarActive,
                ).also { workspace.timelineWindow = it }
                workspace.onEnterCleanShowMode = {
                    timelineWindow.dockedInStoryWindow = false
                    timelineWindow.detachedWindowVisible = false
                }
                val activeSceneIndex = animatic.presentedIndex.sceneIndex.coerceAtLeast(0)
                val activeBeatIndex = animatic.presentedIndex.frameIndex.coerceAtLeast(0)
                val activeSlideId = store.document.slides.getOrNull(activeSceneIndex)?.id
                StudioWorkspaceSyncEffects(
                    workspace = workspace,
                    activeSlideId = activeSlideId,
                    activeBeatIndex = activeBeatIndex,
                    animatic = animatic,
                    activeSceneIndex = activeSceneIndex,
                    lintResumeScope = appScope,
                )
                val shellWindow = rememberStudioShellWindow(
                    workspace = workspace,
                    appBar = { Box(Modifier.size(1.dp)) },
                    themeSheet = { Box(Modifier.size(1.dp)) },
                )

                CompositionLocalProvider(
                    LocalOverviewOpen provides false,
                    LocalPresentingFullscreen provides fullscreen,
                ) {
                    CiacolaJewelTheme(isDark = true) {
                        Box(Modifier.size(1000.dp, 700.dp)) {
                            (shellWindow.decorator + timelineWindow.decorator).decorate {
                                Easel(animatic)
                            }
                        }
                    }
                }
            }

            mainClock.advanceTimeByFrame()
            mainClock.advanceTimeByFrame()

            // Start a full-deck audit on appScope (as StudioBottomIssuesDockBody does) and advance two slides.
            runOnIdle {
                lintController.startFullDeckAudit(scope = appScope, themeNames = listOf("dark"), sliceBudgetMs = 0.0)
            }
            var frames = 0
            while (lintController.auditedSlides < 2 && frames < 20) {
                mainClock.advanceTimeByFrame()
                frames++
            }
            assertTrue(lintController.isAuditing, "Audit should still be in-flight after 2 slides")
            assertTrue(lintController.auditedSlides in 2 until 6)
            val cachedOnFirstPause = lintController.engine.cacheEntryCount()
            assertTrue(cachedOnFirstPause >= 2)

            // 1. Entering Present mode hides the Issues dock and pauses the in-flight audit without error.
            runOnIdle { workspace.switchMode(StudioMode.Present) }
            repeat(5) { mainClock.advanceTimeByFrame() }
            assertTrue(lintController.isPaused, "Lint controller must be paused in Present mode")
            assertFalse(lintController.isAuditing, "In-flight audit must stop while presenting")
            assertTrue(lintController.hasPausedAudit, "Interrupted full-deck audit must be remembered for resume")
            assertNull(lintController.lastError)
            assertEquals(cachedOnFirstPause, lintController.engine.cacheEntryCount())

            // 2. Also entering fullscreen while in Present mode keeps the paused audit intact.
            runOnIdle { fullscreen = true }
            repeat(3) { mainClock.advanceTimeByFrame() }
            assertTrue(lintController.isPaused)
            assertTrue(lintController.hasPausedAudit)

            // Exiting Present mode while STILL fullscreen must keep linting paused until fullscreen exits too.
            runOnIdle { workspace.switchMode(StudioMode.Edit) }
            repeat(3) { mainClock.advanceTimeByFrame() }
            assertTrue(lintController.isPaused, "Fullscreen presenting must keep linting paused")
            assertFalse(workspace.isElementPickingEnabled, "Fullscreen must disable stage element picking")
            assertFalse(lintController.isAuditing)

            // 3. Opening Presenter View before exiting fullscreen also keeps linting paused until Presenter closes.
            runOnIdle {
                presenterOpen = true
                fullscreen = false
            }
            repeat(3) { mainClock.advanceTimeByFrame() }
            assertTrue(lintController.isPaused, "Open Presenter View must keep linting paused")
            assertFalse(lintController.isAuditing)

            // 4. Closing Presenter View leaves all presenting modes and resumes the interrupted audit cleanly.
            runOnIdle { presenterOpen = false }
            var resumeFrames = 0
            while ((lintController.isAuditing || lintController.auditScope != LintAuditScope.Deck) && resumeFrames < 40) {
                mainClock.advanceTimeByFrame()
                resumeFrames++
            }
            assertFalse(lintController.isPaused)
            assertFalse(lintController.isAuditing)
            assertFalse(lintController.hasPausedAudit)
            assertNull(lintController.lastError)
            assertEquals(LintAuditScope.Deck, lintController.auditScope)
            assertEquals(6, lintController.auditedSlides)
            assertTrue(
                lintController.lastStats.cacheHits >= cachedOnFirstPause,
                "Resumed audit should reuse cached slides (cacheHits=${lintController.lastStats.cacheHits}, cachedBefore=$cachedOnFirstPause)",
            )
            assertTrue(
                lintController.issues.any { it.slideId == "s6" && it.code == "LOW_CONTRAST" },
                "Resumed audit must complete remaining slides and report s6 contrast issue",
            )
        } finally {
            lintController.close()
        }
    }

    @Test
    fun presentModePausesReviewDiskPollingAndTimelineSyncWhileKeepingSpeakerNoteSavesWorking() = runComposeUiTest {
        mainClock.autoAdvance = false
        val tempDir = Files.createTempDirectory("ciacola-present-pause-integration-")
        val deckFile = tempDir.resolve("present-pause-doc.deck.json")
        val recoveryDir = tempDir.resolve("recovery")
        val reviewDir = tempDir.resolve(".storyboard")
        val initialDoc = multiSlideDoc(slideCount = 4)
        deckFile.writeText(CanonicalJson.print(initialDoc))

        val store = DeckDocumentStore(validator = { DeckDocumentCodec.validate(it) })
        val fileSync = DeckFileSync(
            targetFile = deckFile,
            recoveryRootDir = recoveryDir,
            store = store,
            debounceMs = 20L,
            pollIntervalMs = 20L,
        )
        runBlocking { fileSync.open() }

        val reviewStore = ReviewCommentStore(
            storyboardDir = reviewDir,
            workspaceRoot = tempDir,
            envProvider = { null },
            systemPropertyProvider = { null },
        )
        val lintController = DocumentDeckLintController(store = store, specimens = DeckSpecimenRegistry.Empty)
        lateinit var workspace: StudioWorkspaceController
        lateinit var timelineController: DocumentTimelineController
        lateinit var reviewCoordinator: ReviewStageCoordinator
        lateinit var animatic: Animatic
        lateinit var appScope: CoroutineScope
        var fullscreen by mutableStateOf(false)
        var presenterOpen by mutableStateOf(false)

        try {
            setContent {
                appScope = rememberCoroutineScope()
                LaunchedEffect(fileSync) {
                    fileSync.startBackgroundSync(this)
                }
                timelineController = remember { DocumentTimelineController(store = store) }
                val elementController = remember { DocumentElementInspectorController(store = store) }
                val themeController = remember { DocumentThemeInspectorController(store = store) }
                reviewCoordinator = rememberReviewStageCoordinator()
                val inspectionRegistry = remember { StageInspectionRegistry() }
                val renderSession = remember { RenderSession.forPresent(store, freeClockMillis = { 0L }) }
                val storyboard = rememberDocumentStoryboard(
                    session = renderSession,
                    config = DocumentRenderConfig(themeName = "dark"),
                )
                animatic = rememberAnimatic(storyboard = { storyboard })
                workspace = remember {
                    StudioWorkspaceController(
                        store = store,
                        timelineController = timelineController,
                        elementController = elementController,
                        themeController = themeController,
                        lintController = lintController,
                    ).apply {
                        isPresenterVisibleProvider = { presenterOpen }
                    }
                }
                val timelineWindow = rememberDocumentTimelineWindow(
                    controller = timelineController,
                    initialDocked = true,
                    initialExpanded = false,
                    showStudioChrome = workspace.isAppBarActive,
                ).also { workspace.timelineWindow = it }
                val reviewWindow = rememberSlideReviewWindow(
                    animatic = animatic,
                    store = reviewStore,
                    stageCoordinator = reviewCoordinator,
                )
                val isAudiencePresentingProvider = remember(workspace) { { workspace.isAudiencePresenting } }
                wireReviewAndWorkspaceCallbacks(
                    reviewStageCoordinator = reviewCoordinator,
                    stageInspectionRegistry = inspectionRegistry,
                    studioWorkspace = workspace,
                    reviewWindowHolderProvider = { reviewWindow },
                    appScope = appScope,
                    isAnyTextInputActive = { false },
                    isAudiencePresentingProvider = isAudiencePresentingProvider,
                    closeAuthoringChrome = {
                        timelineWindow.dockedInStoryWindow = false
                        timelineWindow.detachedWindowVisible = false
                        reviewCoordinator.inspectModeEnabled = false
                        reviewCoordinator.dismissActivePopups()
                    },
                    restoreStageFocus = {},
                )
                TimelineAnimaticSyncEffects(
                    timelineController = timelineController,
                    animatic = animatic,
                    appScope = appScope,
                    paused = workspace.isTimelineBackgroundSyncPaused,
                )
                val activeSceneIndex = animatic.presentedIndex.sceneIndex.coerceAtLeast(0)
                val activeBeatIndex = animatic.presentedIndex.frameIndex.coerceAtLeast(0)
                val activeSlideId = store.document.slides.getOrNull(activeSceneIndex)?.id
                StudioWorkspaceSyncEffects(
                    workspace = workspace,
                    activeSlideId = activeSlideId,
                    activeBeatIndex = activeBeatIndex,
                    animatic = animatic,
                    activeSceneIndex = activeSceneIndex,
                    lintResumeScope = appScope,
                )
                val shellWindow = rememberStudioShellWindow(
                    workspace = workspace,
                    appBar = { Box(Modifier.size(1.dp)) },
                    themeSheet = { Box(Modifier.size(1.dp)) },
                )

                CompositionLocalProvider(
                    LocalOverviewOpen provides false,
                    LocalPresentingFullscreen provides fullscreen,
                ) {
                    CiacolaJewelTheme(isDark = true) {
                        Box(Modifier.size(1000.dp, 700.dp)) {
                            (shellWindow.decorator + timelineWindow.decorator + reviewWindow.decorator).decorate {
                                Easel(animatic)
                            }
                        }
                    }
                }
            }

            mainClock.advanceTimeByFrame()
            mainClock.advanceTimeByFrame()
            assertEquals("s1", timelineController.currentPresentedSlideId)

            // Enter Present mode via markPresentMode() (Presenter View clean show mode flow).
            runOnIdle {
                workspace.onEnterCleanShowMode?.invoke()
                workspace.markPresentMode()
            }
            mainClock.advanceTimeByFrame()
            assertTrue(workspace.isAudiencePresenting)
            assertTrue(workspace.isTimelineBackgroundSyncPaused)
            assertTrue(lintController.isPaused)

            // Write external review comments + claims while presenting and advance past several 250ms poll ticks.
            Files.createDirectories(reviewDir)
            val commentsJson = reviewJson.encodeToString(listOf(sampleComment("rc-during-present")))
            reviewStore.commentsFile.writeText(commentsJson)
            Files.setLastModifiedTime(reviewStore.commentsFile, FileTime.fromMillis(System.currentTimeMillis() + 5_000L))
            ReviewClaims.write(reviewStore.claimsFile, listOf(ReviewClaim(id = "rc-during-present")))
            Files.setLastModifiedTime(reviewStore.claimsFile, FileTime.fromMillis(System.currentTimeMillis() + 5_000L))

            mainClock.advanceTimeBy(1_000L)
            mainClock.advanceTimeByFrame()
            assertTrue(reviewStore.comments.isEmpty(), "Review disk polling must stay paused while presenting")
            assertTrue(reviewStore.claimedIds.isEmpty(), "Review claims polling must stay paused while presenting")

            // Navigate slides during Present mode: timeline location/playhead must not churn while presenting.
            runOnIdle {
                appScope.launch { animatic.jumpTo(Storyboard.Index(sceneIndex = 2, frameIndex = 0)) }
            }
            mainClock.advanceTimeBy(200L)
            mainClock.advanceTimeByFrame()
            assertEquals(2, animatic.currentIndex.sceneIndex)
            assertEquals(
                "s1",
                timelineController.currentPresentedSlideId,
                "Timeline location should stay paused while presenting with timeline closed",
            )

            // Presenter View speaker-note edits must still save through DeckFileSync while presenting.
            runOnIdle {
                assertTrue(saveSpeakerNote(store, "s3", 0, "Updated live presenter cue"))
            }
            val deadlineMs = System.currentTimeMillis() + 5_000L
            while (!deckFile.readText().contains("Updated live presenter cue") && System.currentTimeMillis() < deadlineMs) {
                Thread.sleep(25L)
            }
            assertTrue(
                deckFile.readText().contains("Updated live presenter cue"),
                "DeckFileSync must keep persisting Presenter View speaker-note edits while presenting",
            )

            // Exit Present mode: review comments/claims refresh immediately and timeline catches up to Slide 3.
            runOnIdle { workspace.switchMode(StudioMode.Edit) }
            mainClock.advanceTimeByFrame()
            mainClock.advanceTimeByFrame()
            assertEquals(
                listOf("rc-during-present"),
                reviewStore.comments.map { it.id },
                "Exiting Present mode must immediately reload review comments from disk",
            )
            assertEquals(
                listOf("rc-during-present"),
                reviewStore.claimedIds.toList(),
                "Exiting Present mode must immediately reload review claims from disk",
            )
            assertEquals(
                "s3",
                timelineController.currentPresentedSlideId,
                "Exiting Present mode must catch up timeline presented location",
            )
            assertEquals(2, timelineController.currentPresentedSceneIndex)
            assertFalse(lintController.isPaused)
            assertFalse(lintController.isAuditing, "Idle lint controller must stay idle on exit")
        } finally {
            fileSync.close()
            lintController.close()
            tempDir.toFile().deleteRecursively()
        }
    }
}
