package dev.sebastiano.ciacola

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.application
import dev.bnorm.storyboard.AdvanceDirection
import dev.bnorm.storyboard.ContentDecorator
import dev.bnorm.storyboard.Storyboard
import dev.bnorm.storyboard.easel.DesktopEasel
import dev.bnorm.storyboard.easel.DesktopExportActions
import dev.bnorm.storyboard.easel.overview.LocalOverviewChromeStyle
import dev.bnorm.storyboard.easel.overview.LocalOverviewSlideTitleProvider
import dev.bnorm.storyboard.easel.overview.LocalOverviewThumbnailFrameProvider
import dev.bnorm.storyboard.easel.rememberAnimatic
import dev.bnorm.storyboard.easel.resolveStoryboardWorkspaceRoot
import dev.bnorm.storyboard.plus
import dev.sebastiano.ciacola.decks.DECKS_DIR_SYSTEM_PROPERTY
import dev.sebastiano.ciacola.decks.DeckSource
import dev.sebastiano.ciacola.decks.resolveAppDataRecoveryRoot
import dev.sebastiano.ciacola.document.RenderSession
import dev.sebastiano.ciacola.document.render.AutoAdvanceEffect
import dev.sebastiano.ciacola.document.render.DefaultMagicTextV2Renderer
import dev.sebastiano.ciacola.document.render.DocumentKeyboardCaptureController
import dev.sebastiano.ciacola.document.render.DocumentRenderConfig
import dev.sebastiano.ciacola.document.render.buildDocumentStoryboard
import dev.sebastiano.ciacola.document.render.holdDurationForState
import dev.sebastiano.ciacola.document.render.rememberDocumentStoryboard
import dev.sebastiano.ciacola.document.render.resolveActiveThemeSpec
import dev.sebastiano.ciacola.document.render.resolveDeckTheme
import dev.sebastiano.ciacola.inspector.LocalStageDragPreview
import dev.sebastiano.ciacola.inspector.LocalStageInspectionRegistry
import dev.sebastiano.ciacola.inspector.LocalStageInteractiveRegistry
import dev.sebastiano.ciacola.inspector.StageDragPreview
import dev.sebastiano.ciacola.inspector.StageInspectionRegistry
import dev.sebastiano.ciacola.inspector.StageInteractiveRegistry
import dev.sebastiano.ciacola.inspector.design.DocumentThemeInspectorController
import dev.sebastiano.ciacola.inspector.design.rememberDocumentThemeInspectorWindow
import dev.sebastiano.ciacola.inspector.element.DocumentElementInspectorController
import dev.sebastiano.ciacola.inspector.element.DocumentStageDirectEditOverlay
import dev.sebastiano.ciacola.inspector.lint.DocumentDeckLintController
import dev.sebastiano.ciacola.inspector.lint.DocumentDeckLintStageOverlay
import dev.sebastiano.ciacola.inspector.lint.DocumentDeckLintWindow
import dev.sebastiano.ciacola.inspector.lint.rememberDocumentDeckLintWindow
import dev.sebastiano.ciacola.inspector.sync.CloseGuardState
import dev.sebastiano.ciacola.inspector.sync.DocumentSyncController
import dev.sebastiano.ciacola.presenter.PresenterWindow
import dev.sebastiano.ciacola.presenter.rememberPresenterWindow
import dev.sebastiano.ciacola.review.ReviewCommentStore
import dev.sebastiano.ciacola.review.SlideReviewWindow
import dev.sebastiano.ciacola.review.rememberReviewStageCoordinator
import dev.sebastiano.ciacola.review.rememberSlideReviewWindow
import dev.sebastiano.ciacola.studio.ActiveDeckSession
import dev.sebastiano.ciacola.studio.CiacolaVisualSmokeWindow
import dev.sebastiano.ciacola.studio.DeckSessionAnimaticPersistenceEffect
import dev.sebastiano.ciacola.studio.DeckSwitchProbeEffect
import dev.sebastiano.ciacola.studio.DeckSwitchTimer
import dev.sebastiano.ciacola.studio.DeckSwitcherDropdown
import dev.sebastiano.ciacola.studio.DocumentSpeakerNotesMemo
import dev.sebastiano.ciacola.studio.StageShortcutToastOverlay
import dev.sebastiano.ciacola.studio.StudioAppBarHost
import dev.sebastiano.ciacola.studio.StudioBottomDockTabStrip
import dev.sebastiano.ciacola.studio.StudioBottomIssuesDockBody
import dev.sebastiano.ciacola.studio.StudioBottomIssuesDockHeaderTrailing
import dev.sebastiano.ciacola.studio.StudioIssuesDock
import dev.sebastiano.ciacola.studio.StudioMode
import dev.sebastiano.ciacola.studio.StudioThemeSheet
import dev.sebastiano.ciacola.studio.StudioUnifiedInspectorPane
import dev.sebastiano.ciacola.studio.StudioWorkspaceController
import dev.sebastiano.ciacola.studio.StudioWorkspaceSyncEffects
import dev.sebastiano.ciacola.studio.TimelineAnimaticSyncEffects
import dev.sebastiano.ciacola.studio.awaitNextDrawnFrame
import dev.sebastiano.ciacola.studio.buildDualPassVideoStoryboard
import dev.sebastiano.ciacola.studio.buildPerThumbnailOverviewStoryboard
import dev.sebastiano.ciacola.studio.clearDesktopQuitHandler
import dev.sebastiano.ciacola.studio.createDebouncedNotesFontSizeSaver
import dev.sebastiano.ciacola.studio.externalDeckEntryFor
import dev.sebastiano.ciacola.studio.flushOutgoingBeforeSwap
import dev.sebastiano.ciacola.studio.formatStudioBreadcrumb
import dev.sebastiano.ciacola.studio.installDesktopQuitHandler
import dev.sebastiano.ciacola.studio.loadPersistedInspectorWidthDp
import dev.sebastiano.ciacola.studio.loadPersistedPresenterNotesFontSizeSp
import dev.sebastiano.ciacola.studio.openInitialDeckSession
import dev.sebastiano.ciacola.studio.openRecoveryCopyIntoStore
import dev.sebastiano.ciacola.studio.persistActiveDeckId
import dev.sebastiano.ciacola.studio.persistInspectorWidthDp
import dev.sebastiano.ciacola.studio.persistSelectedThemeForDeck
import dev.sebastiano.ciacola.studio.pickCodeSchemeFile
import dev.sebastiano.ciacola.studio.pickDeckFileForCopyThemes
import dev.sebastiano.ciacola.studio.pickDeckJsonFile
import dev.sebastiano.ciacola.studio.preOpenDeckSession
import dev.sebastiano.ciacola.studio.prepareToLeaveDeck
import dev.sebastiano.ciacola.studio.rememberDeckMenuWindow
import dev.sebastiano.ciacola.studio.rememberDocumentSyncGateWindow
import dev.sebastiano.ciacola.studio.rememberOverviewSlideProviders
import dev.sebastiano.ciacola.studio.rememberStudioOverviewChromeStyle
import dev.sebastiano.ciacola.studio.rememberStudioShellWindow
import dev.sebastiano.ciacola.studio.resolveDeckAssetsRootDir
import dev.sebastiano.ciacola.studio.resolveDeckFontsDir
import dev.sebastiano.ciacola.studio.resolveInitialDeckId
import dev.sebastiano.ciacola.studio.resolveSelectedThemeForDeck
import dev.sebastiano.ciacola.studio.saveSpeakerNote
import dev.sebastiano.ciacola.studio.storyboardDirForDeck
import dev.sebastiano.ciacola.studio.wireReviewAndWorkspaceCallbacks
import dev.sebastiano.ciacola.theme.DeckThemePreset
import dev.sebastiano.ciacola.theme.LocalAmbientLoopsPaused
import dev.sebastiano.ciacola.theme.ReducedMotionMode
import dev.sebastiano.ciacola.theme.parseReducedMotionEnv
import dev.sebastiano.ciacola.theme.rememberSystemReducedMotion
import dev.sebastiano.ciacola.theme.resolveEffectiveReducedMotion
import dev.sebastiano.ciacola.timeline.DocumentTimelineController
import dev.sebastiano.ciacola.timeline.DocumentTimelineStageOverlay
import dev.sebastiano.ciacola.timeline.DocumentTimelineWindow
import dev.sebastiano.ciacola.timeline.rememberDocumentTimelineWindow
import dev.sebastiano.ciacola.ui.AuthoringTextInputFocusTracker
import dev.sebastiano.ciacola.ui.CiacolaJewelTheme
import dev.sebastiano.ciacola.ui.LocalAuthoringTextInputTracker
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private class VideoExportSessionHolder {
    @Volatile
    var session: RenderSession? = null
}

fun main() {
    val smokeDirectory = System.getenv("DECK_VISUAL_SMOKE_DIR")
    // Decode + lock the initial deck before any composition exists: the store's snapshot state must
    // live in the global snapshot, never be created mid-composition from another thread.
    val launch = if (smokeDirectory == null) StudioLaunch.resolve() else null
    ciacolaApplication(smokeDirectory, launch)
}

private class StudioLaunch(
    val workspaceRoot: Path,
    val rootStoryboardDir: Path,
    val recoveryRootDir: Path,
    val initialSession: ActiveDeckSession,
) {
    companion object {
        fun resolve(): StudioLaunch {
            val workspaceRoot = resolveStoryboardWorkspaceRoot()
            val rootStoryboardDir = workspaceRoot.resolve(".storyboard")
            val recoveryRootDir = resolveAppDataRecoveryRoot()
            val initialEntry = DeckCatalog.byId(resolveInitialDeckId(rootStoryboardDir))
            return StudioLaunch(
                workspaceRoot = workspaceRoot,
                rootStoryboardDir = rootStoryboardDir,
                recoveryRootDir = recoveryRootDir,
                initialSession = openInitialDeckSession(initialEntry, recoveryRootDir),
            )
        }
    }
}

private fun ciacolaApplication(smokeDirectory: String?, launch: StudioLaunch?) = application {
    var motionMode by remember {
        mutableStateOf(parseReducedMotionEnv(System.getenv("CIACOLA_REDUCE_MOTION")))
    }
    var ambientLoopsPaused by remember { mutableStateOf(false) }
    val systemReducedMotion = rememberSystemReducedMotion()
    val effectiveReducedMotion = resolveEffectiveReducedMotion(motionMode, systemReducedMotion)
    val textInputTracker = remember { AuthoringTextInputFocusTracker() }

    DeckTextRendererProvider(DefaultMagicTextV2Renderer) {
        if (smokeDirectory == null && launch != null) {
            CiacolaStudioApplication(
                launch = launch,
                motionMode = motionMode,
                onSelectMotionMode = { motionMode = it },
                ambientLoopsPaused = ambientLoopsPaused,
                onToggleAmbientLoopsPaused = { ambientLoopsPaused = it },
                systemReducedMotion = systemReducedMotion,
                effectiveReducedMotion = effectiveReducedMotion,
                textInputTracker = textInputTracker,
                onExitApplication = ::exitApplication,
            )
        } else if (smokeDirectory != null) {
            CiacolaVisualSmokeWindow(
                smokeDirectory = smokeDirectory,
                ambientLoopsPaused = ambientLoopsPaused,
                effectiveReducedMotion = effectiveReducedMotion,
                onExitApplication = ::exitApplication,
            )
        }
    }
}

@Composable
private fun ApplicationScope.CiacolaStudioApplication(
    launch: StudioLaunch,
    motionMode: ReducedMotionMode,
    onSelectMotionMode: (ReducedMotionMode) -> Unit,
    ambientLoopsPaused: Boolean,
    onToggleAmbientLoopsPaused: (Boolean) -> Unit,
    systemReducedMotion: Boolean,
    effectiveReducedMotion: Boolean,
    textInputTracker: AuthoringTextInputFocusTracker,
    onExitApplication: () -> Unit,
) {
    val appScope = rememberCoroutineScope()
    val workspaceRoot = launch.workspaceRoot
    val rootStoryboardDir = launch.rootStoryboardDir
    val recoveryRootDir = launch.recoveryRootDir

    // Atomic deck session: decoded & opened BEFORE state swap for zero-flash deck switching.
    var activeSession by remember { mutableStateOf(launch.initialSession) }
    var deckSwitchCount by remember { mutableIntStateOf(0) }
    var switchToken by remember { mutableIntStateOf(0) }
    var switchJob by remember { mutableStateOf<Job?>(null) }
    var switchingToDeckId by remember { mutableStateOf<String?>(null) }
    val deckSwitchTimer = remember { DeckSwitchTimer() }
    val activeDeck = activeSession.entry
    val deckSource = activeSession.source
    val store = activeSession.store
    val fileSync = activeSession.fileSync

    val deckStoryboardDir = remember(rootStoryboardDir, activeDeck.id) {
        storyboardDirForDeck(rootStoryboardDir, activeDeck.id)
    }
    val resolvedDeckFilePath: String? = remember(deckSource, workspaceRoot) {
        when (deckSource) {
            is DeckSource.File -> deckSource.file.toAbsolutePath().normalize().toString()
            is DeckSource.Resource -> workspaceRoot.resolve("deck/src/desktopMain/resources")
                .resolve(deckSource.resourcePath)
                .takeIf { Files.isRegularFile(it) }
                ?.toAbsolutePath()
                ?.normalize()
                ?.toString()
        }
    }
    val reviewStore = remember(deckStoryboardDir, workspaceRoot) {
        ReviewCommentStore(storyboardDir = deckStoryboardDir, workspaceRoot = workspaceRoot)
    }.also {
        it.defaultDeckId = activeDeck.id
        it.defaultDeckFilePath = resolvedDeckFilePath
    }
    val sessionOpenedExternalDecks = remember { mutableStateListOf<DeckEntry>() }
    val discoverAvailableDecks: (Boolean) -> List<DeckEntry> = { forceRefresh ->
        val discovered = DeckCatalog.discoverEntries(forceRefresh = forceRefresh)
        val extras = sessionOpenedExternalDecks.filter { ext ->
            discovered.none { d -> d.id == ext.id && d.externalFilePath == ext.externalFilePath }
        }
        discovered + extras
    }

    LaunchedEffect(fileSync) {
        fileSync?.startBackgroundSync(this)
    }
    DisposableEffect(fileSync) {
        onDispose { fileSync?.close() }
    }
    DisposableEffect(Unit) {
        onDispose { switchJob?.cancel() }
    }

    val syncController: DocumentSyncController? = remember(store, fileSync, activeDeck.id) {
        fileSync?.let { sync ->
            DocumentSyncController(
                store = store,
                fileSync = sync,
                onOpenRecoveryFile = { recoveryPath ->
                    openRecoveryCopyIntoStore(recoveryPath, activeDeck, store)
                },
            )
        }
    }

    val currentDoc = store.document
    var selectedThemeName by remember(activeDeck.id) {
        mutableStateOf<String?>(null)
    }
    val resolvedThemeName = selectedThemeName ?: remember(rootStoryboardDir, activeDeck.id, currentDoc.themes, currentDoc.defaultTheme) {
        resolveSelectedThemeForDeck(rootStoryboardDir, activeDeck.id, currentDoc)
    }

    val activeThemeSpec = remember(currentDoc, resolvedThemeName) {
        resolveActiveThemeSpec(currentDoc, resolvedThemeName)
    }
    val isLightPreset = activeThemeSpec?.preset.equals("stageLight", ignoreCase = true) ||
        activeThemeSpec?.preset.equals("keynoteLight", ignoreCase = true)
    val isDarkTheme = !isLightPreset
    val currentPreset = if (isDarkTheme) DeckThemePreset.StageDark else DeckThemePreset.KeynoteLight
    val hostTheme = remember(currentDoc, resolvedThemeName) {
        resolveDeckTheme(currentDoc, resolvedThemeName)
    }
    val materialColors = remember(hostTheme) { hostTheme.toMaterialColors() }
    val materialTypography = remember(hostTheme) { hostTheme.toMaterialTypography() }

    val elementController = remember(store, activeDeck.id) {
        DocumentElementInspectorController(
            store = store,
            specimens = activeDeck.specimens,
        )
    }
    val themeController = remember(store, activeDeck.id) {
        DocumentThemeInspectorController(
            store = store,
            initialThemeName = resolvedThemeName,
        )
    }
    LaunchedEffect(themeController.activeThemeName, currentDoc.themes) {
        val next = themeController.activeThemeName
        if (currentDoc.themes.isNotEmpty() && next.isNotBlank() && next in currentDoc.themes && next != selectedThemeName) {
            selectedThemeName = next
            persistSelectedThemeForDeck(rootStoryboardDir, activeDeck.id, next)
        }
    }

    // Isolated RenderSession #4: Lint Engine (owns its own Still RenderSession internally).
    val lintController = remember(store, activeDeck.id, deckSource, fileSync) {
        DocumentDeckLintController(
            store = store,
            specimens = activeDeck.specimens,
            deckDir = (deckSource as? DeckSource.File)?.file?.toAbsolutePath()?.normalize()?.parent,
            assetsDir = (deckSource as? DeckSource.File)?.assetsDir,
            externalEditWarningsProvider = { fileSync?.externalEditLintWarnings.orEmpty() },
        )
    }
    DisposableEffect(lintController) {
        onDispose { lintController.close() }
    }

    val latestReducedMotion by rememberUpdatedState(effectiveReducedMotion)
    val timelineController = remember(store, activeDeck.id) {
        DocumentTimelineController(
            store = store,
            specimens = activeDeck.specimens,
            themeNameProvider = { themeController.activeThemeName.ifBlank { store.document.defaultTheme } },
            reducedMotionProvider = { latestReducedMotion },
        )
    }
    LaunchedEffect(timelineController, effectiveReducedMotion, resolvedThemeName) {
        timelineController.refreshSnapshot()
    }

    val reviewStageCoordinator = rememberReviewStageCoordinator()
    val keyboardController = remember(activeDeck.id) { DocumentKeyboardCaptureController() }
    val stageDragPreview = remember(activeDeck.id) { StageDragPreview() }
    val stageInspectionRegistry = remember(activeDeck.id) { StageInspectionRegistry() }
    val stageInteractiveRegistry = remember(activeDeck.id) { StageInteractiveRegistry() }

    var workspaceHolder by remember { mutableStateOf<StudioWorkspaceController?>(null) }
    val studioWorkspace = remember(rootStoryboardDir) {
        StudioWorkspaceController(
            store = store,
            timelineController = timelineController,
            elementController = elementController,
            themeController = themeController,
            lintController = lintController,
            syncController = syncController,
            keyboardController = keyboardController,
            initialInspectorWidthDp = loadPersistedInspectorWidthDp(rootStoryboardDir),
        )
    }.also {
        it.updateBindings(
            newStore = store,
            newTimelineController = timelineController,
            newElementController = elementController,
            newThemeController = themeController,
            newLintController = lintController,
            newSyncController = syncController,
            newKeyboardController = keyboardController,
        )
        it.onInspectorWidthPersisted = { widthDp ->
            persistInspectorWidthDp(rootStoryboardDir, widthDp)
        }
        workspaceHolder = it
    }

    val assetsRootDir: String? = remember(deckSource, activeDeck) {
        resolveDeckAssetsRootDir(deckSource, activeDeck.assetsResourceDir)
    }
    val deckFontsDir: String? = remember(deckSource) { resolveDeckFontsDir(deckSource) }

    // Free-running ambient loop clock for Show Mode (paused under reducedMotion, user pause, or Studio scrub).
    var freeClockMs by remember(activeDeck.id) { mutableLongStateOf(0L) }
    val animateFreeLoops = !ambientLoopsPaused && !effectiveReducedMotion && !studioWorkspace.isStudioOpen
    LaunchedEffect(activeDeck.id, animateFreeLoops) {
        if (!animateFreeLoops) return@LaunchedEffect
        var prevNanos = -1L
        while (true) {
            withFrameNanos { nanos ->
                if (prevNanos >= 0L) {
                    freeClockMs += ((nanos - prevNanos) / 1_000_000L).coerceAtLeast(0L)
                }
                prevNanos = nanos
            }
        }
    }

    // Isolated RenderSessions #1 (Live Stage) and #2a/#2b (Presenter Current & Next previews).
    // Keyed only on (store, activeDeck.id) so mid-drag tx.update frames never rebuild them.
    fun newPresentSession(): RenderSession = RenderSession.forPresent(
        store = store,
        freeClockMillis = { freeClockMs },
        masterPlayheadMillis = {
            if (studioWorkspace.isStudioOpen || timelineController.hasActiveGesture) {
                timelineController.playheadMs.toLong()
            } else {
                null
            }
        },
        subCompositionScrubMillis = { timelineController.subCompositionScrubMillis },
    )
    var presenterWindowHolder by remember { mutableStateOf<PresenterWindow?>(null) }
    var exportActionsHolder by remember { mutableStateOf<DesktopExportActions?>(null) }
    val presenterVisible = presenterWindowHolder?.visible == true
    val liveSession = remember(store, activeDeck.id) { newPresentSession() }
    val presenterCurrentSession = remember(store, activeDeck.id, presenterVisible, liveSession) {
        if (presenterVisible) newPresentSession().also { it.clipMirrorSource = liveSession } else null
    }
    val presenterNextSession = remember(store, activeDeck.id, presenterVisible) {
        if (presenterVisible) newPresentSession() else null
    }
    // Committed document snapshot for Overview so mid-drag tx.update frames never rebuild Overview thumbnails.
    var committedOverviewDoc by remember(activeDeck.id) { mutableStateOf(currentDoc) }
    LaunchedEffect(currentDoc, store.hasOpenTransaction) {
        if (!store.hasOpenTransaction) {
            committedOverviewDoc = currentDoc
        }
    }
    val committedOverviewSlides = remember(committedOverviewDoc) { committedOverviewDoc.slides.filterNot { it.skipped } }
    val overviewProviders = rememberOverviewSlideProviders(committedOverviewSlides)
    // Isolated RenderSession #6 holder: Video Export (Video @ 60 fps).
    val videoExportSessionHolder = remember(activeDeck.id) { VideoExportSessionHolder() }

    val directEditStageDecorator = remember(
        elementController,
        timelineController,
        lintController,
        studioWorkspace,
        reviewStageCoordinator,
    ) {
        ContentDecorator { content ->
            val reviewOwnsClick = reviewStageCoordinator.inspectModeEnabled ||
                reviewStageCoordinator.pendingSelection != null ||
                reviewStageCoordinator.selectedPinCommentId != null
            val stageSpec = store.document.stage
            DocumentStageDirectEditOverlay(
                controller = elementController,
                enabled = studioWorkspace.isElementPickingEnabled && !reviewOwnsClick,
                keyboardCapturedProvider = { keyboardController.isCaptured },
                inlineEditBlockedProvider = { studioWorkspace.inlineEditBlockedProvider() },
                onRestoreStageFocus = { studioWorkspace.onRestoreStageFocus?.invoke() },
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    content()
                    DocumentTimelineStageOverlay(
                        controller = timelineController,
                        enabled = studioWorkspace.isStudioOpen,
                        stageSelectedNodeIds = elementController.selectedNodeIds,
                    )
                    DocumentDeckLintStageOverlay(
                        controller = lintController,
                        activeSlideId = timelineController.currentPresentedSlideId ?: elementController.activeSlideId,
                        activeBeatIndex = timelineController.currentPresentedBeatIndex,
                        isInspectionVisible = studioWorkspace.isLinterVisible,
                        stageFormatSize = IntSize(stageSpec.width, stageSpec.height),
                    )
                    StageShortcutToastOverlay(
                        toast = studioWorkspace.lastShortcutToast,
                        enabled = studioWorkspace.mode != StudioMode.Present,
                        onDismissed = studioWorkspace::clearShortcutToast,
                        modifier = Modifier.align(Alignment.TopCenter),
                    )
                }
            }
        }
    }

    val liveConfig = DocumentRenderConfig(
        themeName = resolvedThemeName,
        specimens = activeDeck.specimens,
        reducedMotion = effectiveReducedMotion,
        assetsRootDir = assetsRootDir,
        fontsDir = deckFontsDir,
        isEditMode = studioWorkspace.isStudioOpen,
        keyboardController = keyboardController,
        onNodeClick = { _, nodeId -> if (studioWorkspace.isElementPickingEnabled) elementController.selectNode(nodeId) },
        onNodeDoubleClick = { _, nodeId -> if (studioWorkspace.isElementPickingEnabled) elementController.startInlineTextEdit(nodeId) },
        stageDecorator = reviewStageCoordinator.stageDecorator + directEditStageDecorator,
    )

    val stillConfig = DocumentRenderConfig(
        themeName = resolvedThemeName,
        specimens = activeDeck.specimens,
        reducedMotion = effectiveReducedMotion,
        assetsRootDir = assetsRootDir,
        fontsDir = deckFontsDir,
        isEditMode = false,
        keyboardController = null,
        stageDecorator = ContentDecorator.None,
    )

    val liveStoryboard = rememberDocumentStoryboard(
        session = liveSession,
        config = liveConfig,
    )
    // Isolated RenderSession #3: Overview thumbnails each receive their own Still RenderSession.
    val overviewStoryboard = remember(committedOverviewDoc, stillConfig) {
        buildPerThumbnailOverviewStoryboard(
            frozenDoc = committedOverviewDoc,
            config = stillConfig,
        )
    }
    val presenterCurrentStoryboard = if (presenterVisible && presenterCurrentSession != null) {
        rememberDocumentStoryboard(session = presenterCurrentSession, config = stillConfig)
    } else null
    val presenterNextStoryboard = if (presenterVisible && presenterNextSession != null) {
        rememberDocumentStoryboard(session = presenterNextSession, config = stillConfig)
    } else null

    // Isolated RenderSession #5 (PDF Export, Still) & #6a/#6b (Video Export measurement vs render pass).
    val pdfExportStoryboardProvider: () -> Storyboard = remember(store, activeDeck, stillConfig) {
        {
            val pdfSession = RenderSession.forStill(documentSnapshot = store.document)
            buildDocumentStoryboard(session = pdfSession, config = stillConfig)
        }
    }
    val videoExportStoryboardProvider: () -> Storyboard = remember(store, activeDeck, stillConfig, videoExportSessionHolder) {
        {
            val frozenDoc = store.document
            val measurementSession = RenderSession.forVideo(documentSnapshot = frozenDoc)
            val renderSession = RenderSession.forVideo(documentSnapshot = frozenDoc)
            videoExportSessionHolder.session = renderSession
            buildDualPassVideoStoryboard(
                frozenDoc = frozenDoc,
                config = stillConfig,
                measurementSession = measurementSession,
                renderSession = renderSession,
            )
        }
    }
    val videoHoldDurationForState = remember(videoExportSessionHolder, liveSession) {
        { index: Storyboard.Index ->
            holdDurationForState(videoExportSessionHolder.session ?: liveSession, defaultHoldMs = 400L)(index)
        }
    }

    val animatic = key(activeSession.entry.id, activeSession.loadToken) {
        rememberAnimatic(
            initialIndex = Storyboard.Index(0, 0),
            storyboard = { liveStoryboard },
        )
    }

    TimelineAnimaticSyncEffects(timelineController, animatic, appScope)

    AutoAdvanceEffect(
        animatic = animatic,
        session = liveSession,
        enabled = !studioWorkspace.isStudioOpen,
        isScrubbing = timelineController.hasActiveGesture,
    )

    DeckSessionAnimaticPersistenceEffect(
        animatic = animatic,
        document = currentDoc,
        rootStoryboardDir = rootStoryboardDir,
        deckId = activeDeck.id,
        restoreOnEnter = deckSwitchCount == 0,
    )

    // Guarded close & deck-switch flow.
    var pendingCloseAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    // `true` while a switch/quit flush is in flight; speaker-note editing is disabled meanwhile.
    var closingDeckTransition by remember { mutableStateOf(false) }

    fun isSameDeckTarget(a: DeckEntry, b: DeckEntry): Boolean =
        a.id == b.id && a.externalFilePath == b.externalFilePath

    fun performSwitchToDeck(target: DeckEntry, decksDirOverride: String? = null) {
        if (isSameDeckTarget(target, activeSession.entry) && switchingToDeckId == null) return
        if (target.id == switchingToDeckId && target.externalFilePath == null) return
        switchJob?.cancel()
        val token = ++switchToken
        val outgoingRevision = store.revision
        val outgoingDirty = store.isDirty
        switchingToDeckId = target.id
        deckSwitchTimer.markRequested(activeSession.entry.id, target.id)
        switchJob = appScope.launch {
            try {
                val nextSession = preOpenDeckSession(
                    entry = target,
                    recoveryRootDir = recoveryRootDir,
                    loadToken = token,
                    decksDirProperty = decksDirOverride ?: System.getProperty(DECKS_DIR_SYSTEM_PROPERTY),
                )
                deckSwitchTimer.markPreOpened()
                if (token != switchToken) {
                    runCatching { nextSession.fileSync?.close() }
                    return@launch
                }
                prepareToLeaveDeck(studioWorkspace, elementController, presenterWindowHolder?.controller)
                if (!flushOutgoingBeforeSwap(syncController, outgoingRevision, outgoingDirty)) {
                    runCatching { nextSession.fileSync?.close() }
                    pendingCloseAction = { performSwitchToDeck(target, decksDirOverride) }
                    return@launch
                }
                if (token != switchToken) {
                    runCatching { nextSession.fileSync?.close() }
                    return@launch
                }
                elementController.selectNode(null)
                timelineController.clearSelection()
                keyboardController.exitInteractive()
                reviewStageCoordinator.preferredTargetNodeId = null
                reviewStageCoordinator.dismissActivePopups()
                presenterWindowHolder?.controller?.cancelEditingNotes()
                persistActiveDeckId(rootStoryboardDir, target.id)
                Snapshot.withMutableSnapshot {
                    deckSwitchCount++
                    activeSession = nextSession
                    switchingToDeckId = null
                }
                awaitNextDrawnFrame()
                deckSwitchTimer.markFirstFrameDrawn()
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                val detail = t.message?.takeIf { it.isNotBlank() } ?: t::class.simpleName.orEmpty()
                studioWorkspace.showShortcutToast("Failed to open '${target.title}': $detail")
            } finally {
                if (switchToken == token) switchingToDeckId = null
            }
        }
    }

    fun requestSwitchToDeck(target: DeckEntry, decksDirOverride: String? = null) {
        if (isSameDeckTarget(target, activeSession.entry) && switchingToDeckId == null) return
        if (target.id == switchingToDeckId && target.externalFilePath == null) return
        // Cancel uncommitted gestures and commit an open speaker-notes draft BEFORE flushing/closing.
        prepareToLeaveDeck(studioWorkspace, elementController, presenterWindowHolder?.controller)
        val sync = syncController
        if (sync != null) {
            val action = { performSwitchToDeck(target, decksDirOverride) }
            pendingCloseAction = action
            closingDeckTransition = true
            appScope.launch {
                try {
                    sync.requestClose(onAllowClose = {
                        pendingCloseAction = null
                        action()
                    })
                } finally {
                    closingDeckTransition = false
                }
            }
        } else {
            performSwitchToDeck(target, decksDirOverride)
        }
    }

    fun requestOpenDeckFile() {
        val file = pickDeckJsonFile() ?: return
        val entry = externalDeckEntryFor(file) ?: return
        sessionOpenedExternalDecks.removeAll { it.externalFilePath == entry.externalFilePath }
        sessionOpenedExternalDecks.add(entry)
        requestSwitchToDeck(entry)
    }

    fun requestExitApplication() {
        // Cancel uncommitted gestures and commit an open speaker-notes draft BEFORE flushing/closing.
        prepareToLeaveDeck(studioWorkspace, elementController, presenterWindowHolder?.controller)
        val sync = syncController
        if (sync != null) {
            pendingCloseAction = onExitApplication
            closingDeckTransition = true
            appScope.launch {
                try {
                    sync.requestClose(onAllowClose = {
                        pendingCloseAction = null
                        onExitApplication()
                    })
                } finally {
                    closingDeckTransition = false
                }
            }
        } else {
            onExitApplication()
        }
    }

    // Intercept macOS ⌘Q / Dock Quit / app menu Quit through the guarded close-hold flow.
    val latestRequestExit by rememberUpdatedState(::requestExitApplication)
    DisposableEffect(Unit) {
        val installed = installDesktopQuitHandler {
            latestRequestExit()
        }
        onDispose {
            if (installed) {
                clearDesktopQuitHandler()
            }
        }
    }

    val isAnyTextInputActive = {
        textInputTracker.hasFocusedTextInput ||
            elementController.textInputHasFocus ||
            elementController.inlineEditingNodeId != null ||
            reviewStageCoordinator.pendingSelection != null ||
            reviewStageCoordinator.selectedPinCommentId != null ||
            presenterWindowHolder?.controller?.jumpDialogOpen == true ||
            presenterWindowHolder?.controller?.isEditingNotes == true
    }
    studioWorkspace.isTextInputActiveProvider = isAnyTextInputActive
    studioWorkspace.isStageFocusedProvider = { reviewStageCoordinator.stageHasFocus }
    studioWorkspace.inlineEditBlockedProvider = {
        studioWorkspace.themeSheetOpen || reviewStageCoordinator.inspectModeEnabled ||
            reviewStageCoordinator.pendingSelection != null || reviewStageCoordinator.selectedPinCommentId != null
    }
    studioWorkspace.onTogglePresenter = {
        presenterWindowHolder?.let { it.visible = !it.visible }
    }
    studioWorkspace.onSelectDeckByIndex = { idx ->
        discoverAvailableDecks(true).getOrNull(idx)?.let { requestSwitchToDeck(it) }
    }
    DeckSwitchProbeEffect(
        timer = deckSwitchTimer,
        onRequestSwitch = { requestSwitchToDeck(it) },
        entriesProvider = { discoverAvailableDecks(false) },
    )

    val activeNonSkipped = remember(currentDoc) { currentDoc.slides.filterNot { it.skipped } }
    val activeSceneIndex = animatic.presentedIndex.sceneIndex.coerceAtLeast(0)
    val activeBeatIndex = animatic.presentedIndex.frameIndex.coerceAtLeast(0)
    val activeSlideId = activeNonSkipped.getOrNull(activeSceneIndex)?.id
    LaunchedEffect(timelineController, activeSlideId, activeSceneIndex, activeBeatIndex) {
        timelineController.updatePresentedLocation(
            slideId = activeSlideId,
            sceneIndex = activeSceneIndex,
            beatIndex = activeBeatIndex,
        )
    }
    StudioWorkspaceSyncEffects(
        workspace = studioWorkspace,
        activeSlideId = activeSlideId,
        activeBeatIndex = activeBeatIndex,
        animatic = animatic,
    )

    val themeWindow = rememberDocumentThemeInspectorWindow(
        controller = themeController,
        isDarkProvider = { isDarkTheme },
        currentMotionModeProvider = { motionMode },
        systemReducedMotionProvider = { systemReducedMotion },
        ambientLoopsPausedProvider = { ambientLoopsPaused },
        onSelectMotionMode = onSelectMotionMode,
        onToggleAmbientLoopsPaused = onToggleAmbientLoopsPaused,
        isEditorShownProvider = { studioWorkspace.isDesignEditorVisible },
        onToggleEditor = { studioWorkspace.toggleDesignSystemEditor() },
        onPickDeckFileToCopyThemes = ::pickDeckFileForCopyThemes,
        onPickCodeSchemeFile = ::pickCodeSchemeFile,
    )

    var lintWindowHolder by remember { mutableStateOf<DocumentDeckLintWindow?>(null) }
    val prevLintVisible = lintWindowHolder?.visible == true
    val lintWindow = rememberDocumentDeckLintWindow(
        controller = lintController,
        isDarkProvider = { isDarkTheme },
        currentSlideIndexProvider = {
            val slides = store.document.slides
            val targetId = activeSlideId
            if (targetId != null) {
                slides.indexOfFirst { it.id == targetId }.takeIf { it >= 0 } ?: 0
            } else {
                animatic.presentedIndex.sceneIndex.coerceAtLeast(0)
            }
        },
        onJumpToIssue = { issue ->
            val targetSceneIndex = store.document.slides
                .filterNot { it.skipped }
                .indexOfFirst { it.id == issue.slideId }
                .takeIf { it >= 0 }
            studioWorkspace.openNodeInElementInspector(
                nodeId = issue.nodeId,
                animatic = animatic,
                scope = appScope,
                sceneIndex = targetSceneIndex,
                beatIndex = issue.beatIndex,
            )
        },
        isLinterShownProvider = { studioWorkspace.isLinterVisible },
        onToggleLinter = { studioWorkspace.toggleDeckLinter() },
        onPreviewKeyHandler = { ev -> studioWorkspace.handleStudioPreviewKey(ev, allowStageAuthoringShortcuts = false) },
    ).also {
        if (lintWindowHolder != null && lintWindowHolder !== it) {
            it.visible = prevLintVisible
        }
        lintWindowHolder = it
    }

    studioWorkspace.designSidecarVisibleProvider = { themeWindow.visible }
    studioWorkspace.setDesignSidecarVisible = { themeWindow.visible = it }
    studioWorkspace.lintSidecarVisibleProvider = { lintWindow.visible }
    studioWorkspace.setLintSidecarVisible = { lintWindow.visible = it }

    val hoistedTrailingPane: @Composable () -> Unit = remember(studioWorkspace, animatic, appScope) {
        {
            StudioUnifiedInspectorPane(
                workspace = studioWorkspace,
                animatic = animatic,
                scope = appScope,
            )
        }
    }

    var timelineWindowHolder by remember { mutableStateOf<DocumentTimelineWindow?>(null) }
    val showBottomDockTabs = studioWorkspace.issuesDockOpen || timelineWindowHolder?.timelineExpanded == true
    val bottomDockTabs: (@Composable () -> Unit)? = if (showBottomDockTabs) {
        {
            StudioBottomDockTabStrip(
                issuesActive = studioWorkspace.issuesDockOpen,
                errorCount = lintController.errorCount,
                warningCount = lintController.warningCount,
                onSelectTimeline = {
                    studioWorkspace.issuesDockOpen = false
                    timelineWindowHolder?.timelineExpanded = true
                },
                onSelectIssues = {
                    studioWorkspace.issuesDockOpen = true
                },
            )
        }
    } else {
        null
    }
    val timelineWindow = rememberDocumentTimelineWindow(
        controller = timelineController,
        currentPresetProvider = { currentPreset },
        suppressShortcutsProvider = isAnyTextInputActive,
        onReturnFocusToStage = {
            runCatching { reviewStageCoordinator.stageFocusRequester.requestFocus() }
        },
        trailingPane = hoistedTrailingPane,
        // The deck switcher lives in the App Bar now, never in the timeline transport bar.
        leadingToolbarContent = null,
        extraPreviewKeyHandler = { ev, _ ->
            studioWorkspace.handleStudioPreviewKey(ev, allowStageAuthoringShortcuts = false)
        },
        // Studio opens in Edit mode: timeline docked under the stage, collapsed to the header band.
        initialDocked = true,
        initialExpanded = false,
        bottomDockHeaderLeading = bottomDockTabs,
        bottomDockHeaderTrailing = if (studioWorkspace.issuesDockOpen) {
            { StudioBottomIssuesDockHeaderTrailing(studioWorkspace) }
        } else {
            null
        },
        alternateBottomContent = if (studioWorkspace.issuesDockOpen) {
            { StudioBottomIssuesDockBody(workspace = studioWorkspace, animatic = animatic, scope = appScope) }
        } else {
            null
        },
        showStudioChrome = studioWorkspace.isAppBarActive,
    ).also {
        timelineWindowHolder = it
        studioWorkspace.timelineWindow = it
        it.suppressFocusRecoveryProvider = { isAnyTextInputActive() || studioWorkspace.chromeHasFocus }
    }

    fun closeAuthoringChrome() {
        timelineWindow.dockedInStoryWindow = false
        timelineWindow.detachedWindowVisible = false
        themeWindow.visible = false
        lintWindow.visible = false
        elementController.cancelInlineTextEdit()
        elementController.selectNode(null)
        reviewStageCoordinator.inspectModeEnabled = false
        reviewStageCoordinator.dismissActivePopups()
    }

    val speakerNotesMemo = remember(store) { DocumentSpeakerNotesMemo() }
    val presenterWindow = rememberPresenterWindow(
        animatic = animatic,
        speakerNotesProvider = { speakerNotesMemo.notesFor(store.document) },
        documentProvider = { store.document },
        onSaveSpeakerNote = if (store.isReadOnly) null else { id, beat, note -> saveSpeakerNote(store, id, beat, note) },
        initialNotesFontSizeSp = remember(rootStoryboardDir) { loadPersistedPresenterNotesFontSizeSp(rootStoryboardDir) },
        onNotesFontSizeChanged = remember(appScope, rootStoryboardDir) {
            createDebouncedNotesFontSizeSaver(appScope, rootStoryboardDir)
        },
        notesEditingBlockedProvider = { // No new note edits mid switch/quit (drafts commit first).
            switchingToDeckId != null || closingDeckTransition ||
                (syncController != null && syncController.closeGuard !is CloseGuardState.Idle)
        },
        previewStoryboardProvider = presenterCurrentStoryboard?.let { sb -> { sb } },
        nextPreviewStoryboardProvider = presenterNextStoryboard?.let { sb -> { sb } },
        currentPresetProvider = { currentPreset },
        isStudioOpenProvider = { studioWorkspace.hasExpandedAuthoringTools },
        isStageAuthoringChromeOpenProvider = { studioWorkspace.isAnyAuthoringToolOpen },
        isInspectOpenProvider = { reviewStageCoordinator.inspectModeEnabled },
        openAuthoringToolsProvider = {
            buildList {
                if (studioWorkspace.isTimelineTracksOpen) add("Timeline (F5)")
                if (studioWorkspace.isDesignEditorVisible) add("Deck Theme (F7)")
                if (studioWorkspace.isLinterVisible) add("Issues (F8)")
                if (reviewStageCoordinator.inspectModeEnabled) add("Review Inspect (F3)")
            }
        },
        onEnterCleanShowMode = {
            closeAuthoringChrome()
            studioWorkspace.markPresentMode()
        },
        isExternalTextInputActiveProvider = {
            textInputTracker.hasFocusedTextInput ||
                elementController.textInputHasFocus ||
                elementController.inlineEditingNodeId != null ||
                reviewStageCoordinator.pendingSelection != null ||
                reviewStageCoordinator.selectedPinCommentId != null ||
                presenterWindowHolder?.controller?.isEditingNotes == true
        },
        onRestoreStageFocus = {
            runCatching { reviewStageCoordinator.stageFocusRequester.requestFocus() }.isSuccess
        },
        extraPreviewKeyHandler = { ev, _ ->
            studioWorkspace.handleStudioPreviewKey(ev, allowStageAuthoringShortcuts = false)
        },
    ).also { presenterWindowHolder = it }

    var reviewWindowHolder by remember { mutableStateOf<SlideReviewWindow?>(null) }
    val prevReviewVisible = reviewWindowHolder?.visible == true
    val reviewWindow = rememberSlideReviewWindow(
        animatic = animatic,
        store = reviewStore,
        stageCoordinator = reviewStageCoordinator,
        currentPresetProvider = { currentPreset },
        timelineDockedProvider = { timelineWindow.dockedInStoryWindow },
        onToggleTimeline = { studioWorkspace.toggleStudioTimeline() },
        deckIdProvider = { activeDeck.id },
        deckFilePathProvider = { resolvedDeckFilePath },
        slideIdProvider = { sceneIdx -> activeNonSkipped.getOrNull(sceneIdx)?.id },
    ).also {
        if (reviewWindowHolder != null && reviewWindowHolder !== it) {
            it.visible = prevReviewVisible
        }
        reviewWindowHolder = it
    }

    val restoreStageFocus: () -> Unit = {
        runCatching { reviewStageCoordinator.stageFocusRequester.requestFocus() }
    }
    wireReviewAndWorkspaceCallbacks(
        reviewStageCoordinator = reviewStageCoordinator,
        stageInspectionRegistry = stageInspectionRegistry,
        studioWorkspace = studioWorkspace,
        reviewWindowHolderProvider = { reviewWindowHolder },
        appScope = appScope,
        isAnyTextInputActive = isAnyTextInputActive,
        isAudiencePresentingProvider = {
            studioWorkspace.mode == StudioMode.Present ||
                studioWorkspace.isFullscreenPresenting ||
                presenterWindowHolder?.visible == true
        },
        closeAuthoringChrome = ::closeAuthoringChrome,
        restoreStageFocus = restoreStageFocus,
    )

    val activeSlide = activeNonSkipped.getOrNull(activeSceneIndex)
    val breadcrumb = formatStudioBreadcrumb(
        sceneIndex = activeSceneIndex,
        sceneCount = activeNonSkipped.size,
        title = activeSlide?.title,
        beatIndex = activeBeatIndex,
        beatCount = activeSlide?.beats?.size ?: 1,
    )
    val shellWindow = rememberStudioShellWindow(
        workspace = studioWorkspace,
        appBar = {
            StudioAppBarHost(
                workspace = studioWorkspace,
                breadcrumb = breadcrumb,
                presenterOpen = presenterWindow.visible,
                onTogglePresenter = { presenterWindow.visible = !presenterWindow.visible },
                reducedMotion = effectiveReducedMotion,
                ambientLoopsPaused = ambientLoopsPaused,
                isExporting = exportActionsHolder?.isExporting?.invoke() == true,
                onExportPdf = { exportActionsHolder?.exportPdf?.invoke() },
                onExportVideo = { exportActionsHolder?.exportVideo?.invoke() },
                onToggleReducedMotion = {
                    onSelectMotionMode(if (effectiveReducedMotion) ReducedMotionMode.Full else ReducedMotionMode.Reduced)
                },
                onToggleAmbientLoopsPaused = { onToggleAmbientLoopsPaused(!ambientLoopsPaused) },
                deckSwitcher = {
                    DeckSwitcherDropdown(
                        activeDeck = activeDeck,
                        onSelectDeck = { requestSwitchToDeck(it) },
                        entries = emptyList(),
                        entriesProvider = { discoverAvailableDecks(true) },
                        onOpenDeckFile = ::requestOpenDeckFile,
                        onPopupClosed = restoreStageFocus,
                    )
                },
            )
        },
        themeSheet = {
            StudioThemeSheet(
                workspace = studioWorkspace,
                onPickDeckFileToCopyThemes = ::pickDeckFileForCopyThemes,
                onPickCodeSchemeFile = ::pickCodeSchemeFile,
            )
        },
        issuesDock = if (timelineWindow.dockedInStoryWindow) {
            null
        } else {
            { StudioIssuesDock(workspace = studioWorkspace, animatic = animatic, scope = appScope) }
        },
    )

    val deckMenuWindow = rememberDeckMenuWindow(
        activeDeckProvider = { activeDeck },
        onSelectDeck = { requestSwitchToDeck(it) },
        animaticProvider = { animatic },
        isTextInputActiveProvider = isAnyTextInputActive,
        isStudioOpenProvider = { studioWorkspace.isStudioOpen },
        keyboardController = keyboardController,
        onBeforeNavigate = { presenterWindowHolder?.controller?.clearCurtain() },
        extraPreviewKeyHandler = { ev -> workspaceHolder?.handleStudioPreviewKey(ev) == true },
        entries = emptyList(),
        entriesProvider = { discoverAvailableDecks(false) },
    )

    val syncGateWindow = rememberDocumentSyncGateWindow(
        syncController = syncController,
        store = store,
        onAllowClose = {
            val action = pendingCloseAction ?: onExitApplication
            pendingCloseAction = null
            action()
        },
        isAudiencePresentingProvider = {
            studioWorkspace.mode == StudioMode.Present || presenterWindowHolder?.visible == true
        },
    )

    // Outermost first: the shell sits directly outside the timeline so the App Bar spans the stage
    // and the hoisted right inspector.
    val decoratedWindows = remember(
        deckMenuWindow, syncGateWindow, presenterWindow, shellWindow, timelineWindow, reviewWindow, themeWindow, lintWindow,
    ) {
        listOf(deckMenuWindow, syncGateWindow, presenterWindow, shellWindow, timelineWindow, reviewWindow, themeWindow, lintWindow)
    }

    CompositionLocalProvider(
        LocalAmbientLoopsPaused provides ambientLoopsPaused,
        LocalAuthoringTextInputTracker provides textInputTracker,
        LocalStageDragPreview provides stageDragPreview,
        LocalStageInspectionRegistry provides stageInspectionRegistry,
        LocalStageInteractiveRegistry provides stageInteractiveRegistry,
        LocalOverviewSlideTitleProvider provides overviewProviders.titleProvider,
        LocalOverviewThumbnailFrameProvider provides overviewProviders.thumbnailFrameProvider,
    ) {
        CiacolaJewelTheme(isDark = isDarkTheme) {
            CompositionLocalProvider(LocalOverviewChromeStyle provides rememberStudioOverviewChromeStyle()) {
                MaterialTheme(
                    colors = materialColors,
                    typography = materialTypography,
                ) {
                    DesktopEasel(
                        animatic = animatic,
                        windows = decoratedWindows,
                        onCloseRequest = ::requestExitApplication,
                        overviewStoryboard = overviewStoryboard,
                        pdfExportStoryboardProvider = pdfExportStoryboardProvider,
                        videoExportStoryboardProvider = videoExportStoryboardProvider,
                        holdDurationForState = videoHoldDurationForState,
                        onRegisterExportActions = { exportActionsHolder = it },
                    )
                }
            }
        }
    }
}
