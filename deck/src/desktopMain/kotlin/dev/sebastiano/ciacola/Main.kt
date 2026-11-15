package dev.sebastiano.ciacola

import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.ui.unit.dp
import dev.bnorm.storyboard.AdvanceDirection
import dev.bnorm.storyboard.Storyboard
import dev.bnorm.storyboard.easel.DesktopEasel
import dev.bnorm.storyboard.easel.Easel
import dev.bnorm.storyboard.easel.EaselWindow
import dev.bnorm.storyboard.easel.rememberAnimatic
import dev.bnorm.storyboard.easel.resolveStoryboardWorkspaceRoot
import dev.bnorm.storyboard.plus
import dev.sebastiano.ciacola.authoring.DeckHostParams
import dev.sebastiano.ciacola.inspector.LocalStageDragPreview
import dev.sebastiano.ciacola.inspector.LocalStageInteractiveRegistry
import dev.sebastiano.ciacola.inspector.StageDragPreview
import dev.sebastiano.ciacola.inspector.StageInspectionRegistry
import dev.sebastiano.ciacola.inspector.StageInteractiveRegistry
import dev.sebastiano.ciacola.inspector.design.DesignSystemStore
import dev.sebastiano.ciacola.inspector.design.rememberDesignSystemWindow
import dev.sebastiano.ciacola.inspector.element.ElementOverridesStore
import dev.sebastiano.ciacola.inspector.element.rememberElementEditorController
import dev.sebastiano.ciacola.inspector.lint.DeckLintController
import dev.sebastiano.ciacola.inspector.lint.DeckLintWindow
import dev.sebastiano.ciacola.inspector.lint.rememberDeckLintStageDecorator
import dev.sebastiano.ciacola.inspector.lint.rememberDeckLintWindow
import dev.sebastiano.ciacola.presenter.PresenterWindow
import dev.sebastiano.ciacola.presenter.rememberPresenterWindow
import dev.sebastiano.ciacola.review.ReviewCommentStore
import dev.sebastiano.ciacola.review.SlideReviewWindow
import dev.sebastiano.ciacola.review.rememberReviewStageCoordinator
import dev.sebastiano.ciacola.review.rememberSlideReviewWindow
import dev.sebastiano.ciacola.studio.DeckAnimaticPersistenceEffect
import dev.sebastiano.ciacola.studio.DeckSwitcherDropdown
import dev.sebastiano.ciacola.studio.StudioInspectorTab
import dev.sebastiano.ciacola.studio.StudioUnifiedInspectorPane
import dev.sebastiano.ciacola.studio.StudioWorkspaceController
import dev.sebastiano.ciacola.studio.StudioWorkspaceSyncEffects
import dev.sebastiano.ciacola.studio.loadPersistedInspectorWidthDp
import dev.sebastiano.ciacola.studio.persistActiveDeckId
import dev.sebastiano.ciacola.studio.persistInspectorWidthDp
import dev.sebastiano.ciacola.studio.rememberDeckMenuWindow
import dev.sebastiano.ciacola.studio.resolveInitialDeckId
import dev.sebastiano.ciacola.studio.storyboardDirForDeck
import dev.sebastiano.ciacola.theme.DeckThemePreset
import dev.sebastiano.ciacola.theme.DeckThemes
import dev.sebastiano.ciacola.theme.LocalAmbientLoopsPaused
import dev.sebastiano.ciacola.theme.ReducedMotionMode
import dev.sebastiano.ciacola.theme.parseReducedMotionEnv
import dev.sebastiano.ciacola.theme.rememberSystemReducedMotion
import dev.sebastiano.ciacola.theme.resolveEffectiveReducedMotion
import dev.sebastiano.ciacola.theme.withOverrides
import dev.sebastiano.ciacola.timeline.DeckLoopClock
import dev.sebastiano.ciacola.timeline.MotionPathPreviewState
import dev.sebastiano.ciacola.timeline.TimelineOverridesStore
import dev.sebastiano.ciacola.timeline.TimelineStageSelection
import dev.sebastiano.ciacola.timeline.rememberTimelineStageDecorator
import dev.sebastiano.ciacola.timeline.rememberTimelineStudioController
import dev.sebastiano.ciacola.timeline.rememberTimelineStudioWindow
import dev.sebastiano.ciacola.ui.AuthoringTextInputFocusTracker
import dev.sebastiano.ciacola.ui.LocalAuthoringTextInputTracker
import java.awt.Rectangle
import java.awt.Robot
import java.io.File
import java.nio.file.Files
import java.util.Collections
import java.util.WeakHashMap
import javax.imageio.ImageIO
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

fun main() = application {
    var preset by remember {
        val envPreset = System.getenv("CIACOLA_THEME")
        mutableStateOf(
            if (envPreset.equals("light", ignoreCase = true)) {
                DeckThemePreset.KeynoteLight
            } else {
                DeckThemePreset.StageDark
            }
        )
    }
    var motionMode by remember {
        mutableStateOf(parseReducedMotionEnv(System.getenv("CIACOLA_REDUCE_MOTION")))
    }
    var ambientLoopsPaused by remember { mutableStateOf(false) }
    val systemReducedMotion = rememberSystemReducedMotion()
    val effectiveReducedMotion = resolveEffectiveReducedMotion(motionMode, systemReducedMotion)
    val textInputTracker = remember { AuthoringTextInputFocusTracker() }

    val smokeDirectory = System.getenv("DECK_VISUAL_SMOKE_DIR")
    if (smokeDirectory == null) {
        val appScope = rememberCoroutineScope()
        val workspaceRoot = remember { resolveStoryboardWorkspaceRoot() }
        val rootStoryboardDir = remember(workspaceRoot) { workspaceRoot.resolve(".storyboard") }
        var activeDeckId by remember(rootStoryboardDir) {
            mutableStateOf(resolveInitialDeckId(rootStoryboardDir))
        }
        var deckSwitchCount by remember { mutableIntStateOf(0) }
        val activeDeck = remember(activeDeckId) { DeckCatalog.byId(activeDeckId) }
        val deckStoryboardDir = remember(rootStoryboardDir, activeDeck.id) {
            storyboardDirForDeck(rootStoryboardDir, activeDeck.id)
        }

        // Theme / design system tokens stay global across decks; deck overrides & comments are per-deck.
        val designSystemStore = remember(rootStoryboardDir) {
            DesignSystemStore(storyboardDir = rootStoryboardDir)
        }
        val reviewStore = remember(deckStoryboardDir, workspaceRoot) {
            ReviewCommentStore(storyboardDir = deckStoryboardDir, workspaceRoot = workspaceRoot)
        }
        val timelineOverridesStore = remember(deckStoryboardDir) {
            TimelineOverridesStore(storyboardDir = deckStoryboardDir)
        }
        val elementOverridesStore = remember(deckStoryboardDir) {
            ElementOverridesStore(storyboardDir = deckStoryboardDir)
        }
        DisposableEffect(designSystemStore) {
            onDispose { designSystemStore.close() }
        }
        DisposableEffect(elementOverridesStore) {
            onDispose { elementOverridesStore.close() }
        }
        val stageInspectionRegistry = remember(activeDeck.id) { StageInspectionRegistry() }
        val stageInteractiveRegistry = remember(activeDeck.id) { StageInteractiveRegistry() }
        var animaticHolder by remember { mutableStateOf<dev.bnorm.storyboard.easel.Animatic?>(null) }
        val elementController = rememberElementEditorController(
            overridesStore = elementOverridesStore,
            inspectionRegistry = stageInspectionRegistry,
            isStageSettlingProvider = { animaticHolder?.scrubSettling == true },
        )
        val lintController = remember(activeDeck.id) { DeckLintController() }

        val designRev = designSystemStore.revision
        val designOverrides = remember(preset, designRev) {
            designSystemStore.overridesFor(preset)
        }
        val baseHostTheme = remember(preset) { DeckThemes.forPreset(preset) }
        val hostTheme = remember(baseHostTheme, designOverrides) {
            baseHostTheme.withOverrides(designOverrides)
        }
        val materialColors = remember(hostTheme) { hostTheme.toMaterialColors() }
        val materialTypography = remember(hostTheme) { hostTheme.toMaterialTypography() }

        val elementRev = elementOverridesStore.revision
        val structuralRev = timelineOverridesStore.structuralRevision
        val paramRev = timelineOverridesStore.parameterRevision

        val deckLoopClock = remember(activeDeck.id) { DeckLoopClock() }
        val motionPathPreview = remember { MotionPathPreviewState() }
        val stageSelection = remember { TimelineStageSelection() }
        val reviewStageCoordinator = rememberReviewStageCoordinator()

        var workspaceHolder by remember { mutableStateOf<StudioWorkspaceController?>(null) }

        val presetRegistry = remember { StoryboardPresetRegistry() }
        val stageLoopAndArcDecorator = rememberTimelineStageDecorator(
            loopClock = deckLoopClock,
            motionPathPreview = motionPathPreview,
            overridesStore = timelineOverridesStore,
            stageSelection = stageSelection,
        )
        val lintStageDecorator = rememberDeckLintStageDecorator(
            controller = lintController,
            isInspectionVisible = { workspaceHolder?.isLinterVisible == true },
        )

        val storyboard = rememberHotReloadStoryboard(
            activeDeck.id,
            preset,
            effectiveReducedMotion,
            reviewStageCoordinator,
            structuralRev,
        ) {
            activeDeck.create(
                DeckHostParams(
                    theme = baseHostTheme,
                    bundledPreset = preset,
                    stageDecorator = stageLoopAndArcDecorator +
                        reviewStageCoordinator.stageDecorator +
                        elementController.stageDecorator +
                        lintStageDecorator,
                    timelineOverrides = timelineOverridesStore.data(),
                    reducedMotion = effectiveReducedMotion,
                    timelineOverridesProvider = {
                        timelineOverridesStore.parameterRevision
                        timelineOverridesStore.structuralRevision
                        timelineOverridesStore.data()
                    },
                    elementOverridesProvider = {
                        elementOverridesStore.revision
                        elementOverridesStore.data()
                    },
                    designOverridesProvider = {
                        designSystemStore.revision
                        designSystemStore.overridesFor(preset)
                    },
                ),
            ).also { built ->
                presetRegistry.record(built, preset)
            }
        }

        val buildAuditStoryboardForPreset: (DeckThemePreset) -> Storyboard = remember(
            activeDeck,
            timelineOverridesStore,
            elementOverridesStore,
            designSystemStore,
            effectiveReducedMotion,
        ) {
            { targetPreset ->
                val targetDesignOverrides = designSystemStore.overridesFor(targetPreset)
                activeDeck.create(
                    DeckHostParams(
                        theme = DeckThemes.forPreset(targetPreset),
                        bundledPreset = targetPreset,
                        timelineOverrides = timelineOverridesStore.data(),
                        reducedMotion = effectiveReducedMotion,
                        elementOverrides = elementOverridesStore.data(),
                        designOverrides = targetDesignOverrides,
                    ),
                )
            }
        }

        val animatic = key(activeDeck.id) {
            rememberAnimatic(
                initialIndex = Storyboard.Index(0, 0),
                storyboard = { storyboard },
            )
        }.also { animaticHolder = it }
        DeckAnimaticPersistenceEffect(
            animatic = animatic,
            storyboardDir = deckStoryboardDir,
            restoreOnEnter = deckSwitchCount == 0,
        )

        val timelineController = rememberTimelineStudioController(
            animatic = animatic,
            overridesStore = timelineOverridesStore,
            loopClock = deckLoopClock,
            bakeDispatcher = reviewStore.asBakeDispatcher(),
            bakeWorkspaceRoot = reviewStore.workspaceRoot,
            bakeGradleTaskHint = reviewStore.config.gradleTaskHint,
            motionPathPreview = motionPathPreview,
            stageSelection = stageSelection,
            externalTimingKey = designOverrides.motion,
            externalInEditGesture = designSystemStore.isInEditGesture,
        )

        val studioWorkspace = remember(timelineController, designSystemStore) {
            StudioWorkspaceController(
                timelineController = timelineController,
                timelineOverridesStore = timelineOverridesStore,
                designSystemStore = designSystemStore,
                elementOverridesStore = elementOverridesStore,
                elementController = elementController,
                lintController = lintController,
                stageInspectionRegistry = stageInspectionRegistry,
                initialInspectorWidthDp = loadPersistedInspectorWidthDp(rootStoryboardDir),
            )
        }.also {
            it.updateForDeckSwitch(
                newTimelineOverridesStore = timelineOverridesStore,
                newElementOverridesStore = elementOverridesStore,
                newElementController = elementController,
                newLintController = lintController,
                newStageInspectionRegistry = stageInspectionRegistry,
            )
            it.onInspectorWidthPersisted = { widthDp ->
                persistInspectorWidthDp(rootStoryboardDir, widthDp)
            }
            workspaceHolder = it
        }

        fun switchToDeck(target: DeckEntry) {
            if (target.id == activeDeckId) return
            elementController.commitInlineEdit(restoreStageFocus = false)
            elementController.selectElement(null)
            timelineController.exitLoopSubComp()
            timelineController.stopPlayback()
            timelineController.selectTrack(null)
            reviewStageCoordinator.dismissActivePopups()
            persistActiveDeckId(rootStoryboardDir, target.id)
            deckSwitchCount++
            activeDeckId = target.id
        }

        var presenterWindowHolder by remember { mutableStateOf<PresenterWindow?>(null) }

        val isAnyTextInputActive = {
            textInputTracker.hasFocusedTextInput ||
                elementController.inlineEditingElementId != null ||
                reviewStageCoordinator.pendingSelection != null ||
                reviewStageCoordinator.selectedPinCommentId != null ||
                presenterWindowHolder?.controller?.jumpDialogOpen == true
        }
        studioWorkspace.isTextInputActiveProvider = isAnyTextInputActive
        studioWorkspace.onTogglePresenter = {
            presenterWindowHolder?.let { it.visible = !it.visible }
        }
        studioWorkspace.onToggleDockedTimeline = {
            timelineController.dockedInStoryWindow = !timelineController.dockedInStoryWindow
        }
        studioWorkspace.onToggleDetachedTimeline = {
            val next = !timelineController.detachedWindowVisible
            if (next) timelineController.dockedInStoryWindow = false
            timelineController.detachedWindowVisible = next
        }
        studioWorkspace.onSelectDeckByIndex = { idx ->
            DeckCatalog.entries.getOrNull(idx)?.let(::switchToDeck)
        }

        val reviewOwnsStageClick = {
            reviewStageCoordinator.inspectModeEnabled ||
                reviewStageCoordinator.pendingSelection != null ||
                reviewStageCoordinator.selectedPinCommentId != null
        }

        stageSelection.pickingSuppressed = {
            reviewOwnsStageClick() ||
                timelineController.spaceHeld ||
                elementController.inlineEditingElementId != null ||
                (studioWorkspace.isStudioOpen && studioWorkspace.activeTab == StudioInspectorTab.Element)
        }

        remember(elementController, reviewStageCoordinator) {
            elementController.onReturnFocusToStage = {
                runCatching { reviewStageCoordinator.stageFocusRequester.requestFocus() }
            }
        }

        val activeStoryboard = animatic.storyboard
        SideEffect {
            val activePreset = presetRegistry.presetFor(activeStoryboard, fallback = preset)
            elementController.suppressPicking = {
                reviewOwnsStageClick() || timelineController.spaceHeld
            }
            elementController.isStagePointOverHud = reviewStageCoordinator::isStagePointOverHud
            elementController.isExternalTextInputActive = isAnyTextInputActive
            elementController.currentSceneIndexProvider = { animatic.presentedIndex.sceneIndex }
            elementController.currentBeatIndexProvider = { animatic.presentedIndex.frameIndex }
            elementController.isStageSettlingProvider = { animatic.scrubSettling }
            elementController.isDarkThemeProvider = { activePreset == DeckThemePreset.StageDark }
        }

        val themeWindow = rememberDesignSystemWindow(
            store = designSystemStore,
            currentPreset = preset,
            onSelectPreset = { preset = it },
            currentMotionMode = motionMode,
            systemReducedMotion = systemReducedMotion,
            ambientLoopsPaused = ambientLoopsPaused,
            onSelectMotionMode = { motionMode = it },
            onToggleAmbientLoopsPaused = { ambientLoopsPaused = it },
            baseThemeProvider = { DeckThemes.forPreset(it) },
            isEditorShownProvider = { studioWorkspace.isDesignEditorVisible },
            onToggleEditor = { studioWorkspace.toggleDesignSystemEditor() },
        )

        var lintWindowHolder by remember { mutableStateOf<DeckLintWindow?>(null) }
        val prevLintVisible = lintWindowHolder?.visible == true
        val lintWindow = rememberDeckLintWindow(
            controller = lintController,
            animatic = animatic,
            currentPresetProvider = {
                presetRegistry.presetFor(animatic.storyboard, fallback = preset)
            },
            storyboardProvider = { animatic.storyboard },
            presetsToCheck = DeckThemePreset.entries,
            storyboardForPreset = buildAuditStoryboardForPreset,
            timelineOverridesProvider = { timelineOverridesStore.data() },
            fallbackBackgroundProvider = { hostTheme.colors.background },
            externalRevisionKeyProvider = {
                structuralRev + paramRev + elementRev + designRev
            },
            stageInspectionRegistry = stageInspectionRegistry,
            onApplyElementPatch = { patch -> studioWorkspace.applyElementPatch(patch) },
            onEditElement = { elementId, sceneIndex, beatIndex ->
                studioWorkspace.openElementInInspector(
                    elementId = elementId,
                    animatic = animatic,
                    scope = appScope,
                    sceneIndex = sceneIndex,
                    beatIndex = beatIndex,
                )
            },
            onOpenThemeEditor = { studioWorkspace.toggleDesignSystemEditor() },
            isLinterShownProvider = { studioWorkspace.isLinterVisible },
            onToggleLinter = { studioWorkspace.toggleDeckLinter() },
            onPreviewKeyHandler = { ev -> studioWorkspace.handleStudioPreviewKey(ev) },
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

        StudioWorkspaceSyncEffects(studioWorkspace)

        val presenterWindow = rememberPresenterWindow(
            animatic = animatic,
            currentPresetProvider = {
                presetRegistry.presetFor(presenterWindowHolder?.controller?.animatic?.storyboard, fallback = preset)
            },
            isStudioOpenProvider = { studioWorkspace.isAnyAuthoringToolOpen },
            isInspectOpenProvider = { reviewStageCoordinator.inspectModeEnabled },
            openAuthoringToolsProvider = {
                buildList {
                    if (timelineController.authoringActive) add("Timeline Studio is open (F5/F6)")
                    if (themeWindow.visible) add("Design System Editor is open (F7)")
                    if (lintWindow.visible) add("Deck Linter is open (F8)")
                    if (reviewStageCoordinator.inspectModeEnabled) add("Review Inspect mode is active (F3)")
                }
            },
            hasUnbakedOverridesProvider = {
                timelineOverridesStore.structuralRevision
                timelineOverridesStore.parameterRevision
                elementOverridesStore.revision
                designSystemStore.revision
                timelineOverridesStore.data().hasOverrides ||
                    elementOverridesStore.data().hasOverrides ||
                    designSystemStore.data().hasOverrides
            },
            unbakedDomainsProvider = {
                timelineOverridesStore.structuralRevision
                timelineOverridesStore.parameterRevision
                elementOverridesStore.revision
                designSystemStore.revision
                buildList {
                    if (timelineOverridesStore.data().hasOverrides) add("Timeline")
                    if (elementOverridesStore.data().hasOverrides) add("Element")
                    if (designSystemStore.data().hasOverrides) add("Theme")
                }
            },
            onEnterCleanShowMode = {
                timelineController.dockedInStoryWindow = false
                timelineController.detachedWindowVisible = false
                themeWindow.visible = false
                lintWindow.visible = false
                elementController.cancelInlineEdit(restoreStageFocus = false)
                elementController.selectElement(null)
                reviewStageCoordinator.inspectModeEnabled = false
                reviewStageCoordinator.dismissActivePopups()
            },
            isExternalTextInputActiveProvider = {
                textInputTracker.hasFocusedTextInput ||
                    elementController.inlineEditingElementId != null ||
                    reviewStageCoordinator.pendingSelection != null ||
                    reviewStageCoordinator.selectedPinCommentId != null
            },
            onRestoreStageFocus = {
                runCatching { reviewStageCoordinator.stageFocusRequester.requestFocus() }.isSuccess
            },
            extraPreviewKeyHandler = { ev, _ ->
                studioWorkspace.handleStudioPreviewKey(ev)
            },
        ).also { presenterWindowHolder = it }

        val timelineWindow = rememberTimelineStudioWindow(
            controller = timelineController,
            currentPresetProvider = {
                presetRegistry.presetFor(timelineController.animatic.storyboard, fallback = preset)
            },
            suppressShortcutsProvider = isAnyTextInputActive,
            onReturnFocusToStage = {
                runCatching { reviewStageCoordinator.stageFocusRequester.requestFocus() }
            },
            trailingPane = {
                StudioUnifiedInspectorPane(
                    workspace = studioWorkspace,
                    animatic = animatic,
                    currentPreset = preset,
                    onSelectPreset = { preset = it },
                    baseThemeProvider = { DeckThemes.forPreset(it) },
                    storyboardForPreset = buildAuditStoryboardForPreset,
                    scope = appScope,
                )
            },
            leadingToolbarContent = {
                DeckSwitcherDropdown(
                    activeDeck = activeDeck,
                    onSelectDeck = ::switchToDeck,
                )
            },
            extraPreviewKeyHandler = { ev, _ ->
                studioWorkspace.handleStudioPreviewKey(ev)
            },
            extraMenuContent = {
                Menu("Studio Inspector") {
                    for (tab in StudioInspectorTab.entries) {
                        RadioButtonItem(
                            text = tab.label,
                            selected = studioWorkspace.isStudioOpen && studioWorkspace.activeTab == tab,
                            onClick = { studioWorkspace.selectTabInStudio(tab) },
                        )
                    }
                }
            },
        )

        var reviewWindowHolder by remember { mutableStateOf<SlideReviewWindow?>(null) }
        val prevReviewVisible = reviewWindowHolder?.visible == true
        val reviewWindow = rememberSlideReviewWindow(
            animatic = animatic,
            store = reviewStore,
            stageCoordinator = reviewStageCoordinator,
            currentPresetProvider = {
                val currentStoryboard = reviewWindowHolder?.animatic?.storyboard
                presetRegistry.presetFor(currentStoryboard, fallback = preset)
            },
            timelineDockedProvider = { timelineController.dockedInStoryWindow },
            onToggleTimeline = {
                timelineController.dockedInStoryWindow = !timelineController.dockedInStoryWindow
            },
        ).also {
            if (reviewWindowHolder != null && reviewWindowHolder !== it) {
                it.visible = prevReviewVisible
            }
            reviewWindowHolder = it
        }

        val deckMenuWindow = rememberDeckMenuWindow(
            activeDeckProvider = { activeDeck },
            onSelectDeck = ::switchToDeck,
            animaticProvider = { animatic },
            isTextInputActiveProvider = isAnyTextInputActive,
            isStudioOpenProvider = { studioWorkspace.isStudioOpen },
            onBeforeNavigate = { presenterWindowHolder?.controller?.clearCurtain() },
            extraPreviewKeyHandler = { ev -> workspaceHolder?.handleStudioPreviewKey(ev) == true },
        )

        // DeckMenuWindow sits outermost as a pure window-level key capture + focus guard; Timeline
        // sits before Review so the hoisted right Inspector sidebar wraps the Stage cleanly.
        val decoratedWindows = remember(presenterWindow, timelineWindow, reviewWindow, themeWindow, lintWindow, deckMenuWindow) {
            listOf(
                deckMenuWindow,
                presenterWindow,
                timelineWindow,
                reviewWindow,
                themeWindow,
                lintWindow,
            )
        }

        CompositionLocalProvider(
            LocalAmbientLoopsPaused provides ambientLoopsPaused,
            LocalAuthoringTextInputTracker provides textInputTracker,
            LocalStageInteractiveRegistry provides stageInteractiveRegistry,
        ) {
            MaterialTheme(
                colors = materialColors,
                typography = materialTypography,
            ) {
                DesktopEasel(
                    animatic = animatic,
                    windows = decoratedWindows,
                )
            }
        }
    } else {
        val hostTheme = remember(preset) { DeckThemes.forPreset(preset) }
        val materialColors = remember(hostTheme) { hostTheme.toMaterialColors() }
        val materialTypography = remember(hostTheme) { hostTheme.toMaterialTypography() }
        CompositionLocalProvider(LocalAmbientLoopsPaused provides ambientLoopsPaused) {
            val storyboard = rememberHotReloadStoryboard(preset, effectiveReducedMotion) {
                createFoundationDeck(
                    theme = hostTheme,
                    bundledPreset = preset,
                    reducedMotion = effectiveReducedMotion,
                )
            }
            val animatic = rememberAnimatic(storyboard = { storyboard })
            Window(
                onCloseRequest = ::exitApplication,
                title = "Deck visual smoke",
                state = rememberWindowState(width = 960.dp, height = 540.dp),
            ) {
                MaterialTheme(colors = materialColors, typography = materialTypography) {
                    Easel(animatic)
                    LaunchedEffect(animatic, window) {
                        val directory = File(smokeDirectory).apply { mkdirs() }
                        val robot = Robot()

                        suspend fun capture(name: String) {
                            delay(150)
                            val location = window.locationOnScreen
                            val image = robot.createScreenCapture(
                                Rectangle(location.x, location.y, window.width, window.height)
                            )
                            ImageIO.write(image, "png", File(directory, "$name.png"))
                        }

                        delay(750)
                        capture("00-start")
                        var forward = 1
                        while (true) {
                            val movement = launch { animatic.advance(AdvanceDirection.Forward) }
                            delay(350)
                            capture("f${forward.toString().padStart(2, '0')}-transition")
                            movement.join()
                            capture("f${forward.toString().padStart(2, '0')}-settled")
                            if (animatic.currentIndex.sceneIndex == 2) {
                                capture("loop-a")
                                delay(1_000)
                                capture("loop-b")
                            }
                            forward++
                            if (animatic.currentIndex == animatic.storyboard.indices.last()) break
                        }

                        var backward = 1
                        while (animatic.currentIndex != animatic.storyboard.indices.first()) {
                            val movement = launch { animatic.advance(AdvanceDirection.Backward) }
                            delay(350)
                            capture("b${backward.toString().padStart(2, '0')}-transition")
                            movement.join()
                            capture("b${backward.toString().padStart(2, '0')}-settled")
                            backward++
                        }
                        window.dispose()
                        exitApplication()
                    }
                }
            }
        }
    }
}

internal class StoryboardPresetRegistry {
    private val map = Collections.synchronizedMap(WeakHashMap<Storyboard, DeckThemePreset>())

    fun record(storyboard: Storyboard, preset: DeckThemePreset) {
        map[storyboard] = preset
    }

    fun presetFor(storyboard: Storyboard?, fallback: DeckThemePreset): DeckThemePreset =
        storyboard?.let { map[it] } ?: fallback
}

@Composable
internal fun rememberThemeSwitcherWindow(
    currentPreset: DeckThemePreset,
    currentMotionMode: ReducedMotionMode = ReducedMotionMode.System,
    systemReducedMotion: Boolean = false,
    ambientLoopsPaused: Boolean = false,
    onSelectMotionMode: (ReducedMotionMode) -> Unit = {},
    onToggleAmbientLoopsPaused: (Boolean) -> Unit = {},
    store: DesignSystemStore? = null,
    onSelectPreset: (DeckThemePreset) -> Unit,
): EaselWindow {
    val ownsFallbackStore = store == null
    val effectiveStore = store ?: remember {
        DesignSystemStore(storyboardDir = Files.createTempDirectory("ciacola-theme-window-"))
    }
    DisposableEffect(effectiveStore, ownsFallbackStore) {
        onDispose {
            if (ownsFallbackStore) {
                effectiveStore.close()
            }
        }
    }
    return rememberDesignSystemWindow(
        store = effectiveStore,
        currentPreset = currentPreset,
        onSelectPreset = onSelectPreset,
        currentMotionMode = currentMotionMode,
        systemReducedMotion = systemReducedMotion,
        ambientLoopsPaused = ambientLoopsPaused,
        onSelectMotionMode = onSelectMotionMode,
        onToggleAmbientLoopsPaused = onToggleAmbientLoopsPaused,
    )
}
