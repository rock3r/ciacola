package dev.sebastiano.ciacola.review

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.KeyShortcut
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.MenuScope
import dev.bnorm.storyboard.ContentDecorator
import dev.bnorm.storyboard.easel.Animatic
import dev.bnorm.storyboard.easel.EaselWindow
import dev.bnorm.storyboard.easel.LocalOverviewOpen
import dev.bnorm.storyboard.easel.LocalPresentingFullscreen
import dev.sebastiano.ciacola.theme.DeckThemePreset
import dev.sebastiano.ciacola.theme.DeckThemes
import dev.sebastiano.ciacola.ui.CiacolaJewelTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Storyboard [EaselWindow] providing Ciacola's interactive `Ctrl+Click` slide review overlay,
 * menu actions, and Jewel-styled review feed window.
 */
class SlideReviewWindow(
    initialAnimatic: Animatic,
    val store: ReviewCommentStore,
    val stageCoordinator: ReviewStageCoordinator,
    var currentPresetProvider: () -> DeckThemePreset = { DeckThemePreset.StageDark },
    var timelineDockedProvider: () -> Boolean = { false },
    var onToggleTimeline: (() -> Unit)? = null,
    var deckIdProvider: () -> String? = { null },
    var deckFilePathProvider: () -> String? = { null },
    var slideIdProvider: ((sceneIndex: Int) -> String?)? = null,
) : EaselWindow {
    var animatic: Animatic by mutableStateOf(initialAnimatic)
        internal set

    override val name: String = "Review"

    /**
     * Re-checks the push command whenever the panel is shown, however it was shown.
     *
     * [openForAgentConfiguration] is only one of the ways in; F4 and the menu set [visible]
     * directly, and someone who has just gone off to install the agent will come back through one
     * of those.
     */
    private fun onBecameVisible() {
        store.refreshPushAvailability()
    }
    override val minimumSize: DpSize get() = DpSize(420.dp, 480.dp)
    private var visibleState: Boolean by mutableStateOf(false)

    override var visible: Boolean
        get() = visibleState
        set(value) {
            val wasVisible = visibleState
            visibleState = value
            if (value && !wasVisible) onBecameVisible()
        }
    var highlightAgentConfigBanner: Boolean by mutableStateOf(false)
        internal set

    fun openForAgentConfiguration() {
        highlightAgentConfigBanner = true
        visible = true
    }

    override val decorator: ContentDecorator = ContentDecorator { content ->
        ReviewOuterWindowOverlay(
            windowController = this,
            content = content,
        )
    }

    @Composable
    override fun MenuScope.Menu() {
        val scope = rememberCoroutineScope()
        val queuedCount = store.comments.count {
            (it.status == ReviewCommentStatus.Queued || it.status == ReviewCommentStatus.Failed) &&
                it.id !in store.claimedIds
        }
        val appliedCount = store.comments.count { it.status == ReviewCommentStatus.Applied }

        // No accelerator: F3 is handled on onPreviewKeyEvent in the Story and Review windows, and a
        // Swing menu accelerator would fire in addition to it and double-toggle inspectModeEnabled.
        CheckboxItem(
            text = "Inspect Mode (Click without Ctrl) (F3)",
            checked = stageCoordinator.inspectModeEnabled,
        ) {
            stageCoordinator.inspectModeEnabled = it
        }
        CheckboxItem(
            text = "Show Stage Pins & Toolbar",
            checked = stageCoordinator.showStageBadges,
        ) {
            stageCoordinator.showStageBadges = it
        }
        CheckboxItem(
            text = "Show Review Panel",
            checked = visible,
            shortcut = KeyShortcut(Key.F4),
        ) {
            visible = it
        }
        Separator()
        val agentConfigured = store.effectiveConversationId().isNotBlank()
        val pushAvailable = store.agentPushAvailable()
        Item(
            text = when {
                !pushAvailable -> "Waiting for an Agent ($queuedCount)"
                agentConfigured || queuedCount == 0 -> "Send Queued Comments ($queuedCount)"
                else -> "Configure Agent to Send ($queuedCount)…"
            },
            enabled = queuedCount > 0 && pushAvailable,
            onClick = {
                if (store.effectiveConversationId().isBlank()) {
                    openForAgentConfiguration()
                } else {
                    scope.launch { store.flushQueued() }
                }
            },
        )
        Item(
            text = "Clear Applied Comments ($appliedCount)",
            enabled = appliedCount > 0,
            onClick = { store.clearAppliedComments() },
        )
    }

    @Composable
    override fun Content() {
        val isDark = currentPresetProvider() == DeckThemePreset.StageDark
        CiacolaJewelTheme(isDark = isDark) {
            ReviewSidecarPanel(windowController = this)
        }
    }
}

@Composable
fun rememberSlideReviewWindow(
    animatic: Animatic,
    store: ReviewCommentStore,
    stageCoordinator: ReviewStageCoordinator,
    currentPresetProvider: () -> DeckThemePreset = { DeckThemePreset.StageDark },
    timelineDockedProvider: () -> Boolean = { false },
    onToggleTimeline: (() -> Unit)? = null,
    deckIdProvider: () -> String? = { null },
    deckFilePathProvider: () -> String? = { null },
    slideIdProvider: ((sceneIndex: Int) -> String?)? = null,
): SlideReviewWindow = remember(store, stageCoordinator) {
    SlideReviewWindow(
        initialAnimatic = animatic,
        store = store,
        stageCoordinator = stageCoordinator,
        currentPresetProvider = currentPresetProvider,
        timelineDockedProvider = timelineDockedProvider,
        onToggleTimeline = onToggleTimeline,
        deckIdProvider = deckIdProvider,
        deckFilePathProvider = deckFilePathProvider,
        slideIdProvider = slideIdProvider,
    )
}.also {
    it.animatic = animatic
    it.currentPresetProvider = currentPresetProvider
    it.timelineDockedProvider = timelineDockedProvider
    it.onToggleTimeline = onToggleTimeline
    it.deckIdProvider = deckIdProvider
    it.deckFilePathProvider = deckFilePathProvider
    it.slideIdProvider = slideIdProvider
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ReviewOuterWindowOverlay(
    windowController: SlideReviewWindow,
    content: @Composable () -> Unit,
) {
    val store = windowController.store
    val coordinator = windowController.stageCoordinator
    val animatic = windowController.animatic
    val scope = rememberCoroutineScope()
    val preset = windowController.currentPresetProvider()
    val isDark = preset == DeckThemePreset.StageDark
    val stageHostTheme = remember(preset) { DeckThemes.forPreset(preset) }
    val stageMaterialColors = remember(stageHostTheme) { stageHostTheme.toMaterialColors() }
    val stageMaterialTypography = remember(stageHostTheme) { stageHostTheme.toMaterialTypography() }
    coordinator.isDarkThemeProvider = { isDark }

    val composerNoteState = rememberTextFieldState(initialText = coordinator.composerDraftText)
    var draftWasRestored by remember { mutableStateOf(false) }
    var restoredFromTarget by remember { mutableStateOf<ReviewTargetHit?>(null) }
    var lastSeenPending by remember { mutableStateOf<PendingReviewSelection?>(null) }
    var lastStoryboard by remember { mutableStateOf(animatic.storyboard) }
    var rebuildRestoredSelection by remember { mutableStateOf<PendingReviewSelection?>(null) }

    if (animatic.storyboard !== lastStoryboard) {
        val openBeforeRebuild = coordinator.pendingSelection ?: lastSeenPending
        if (openBeforeRebuild != null) {
            rebuildRestoredSelection = openBeforeRebuild
        }
        lastStoryboard = animatic.storyboard
    }

    val rawPending = coordinator.pendingSelection
    if (rawPending != null && rawPending !== lastSeenPending) {
        if (rawPending !== rebuildRestoredSelection) {
            val savedDraft = coordinator.composerDraftText
            if (savedDraft.isNotBlank()) {
                restoredFromTarget = coordinator.composerDraftTarget
                composerNoteState.setTextAndPlaceCursorAtEnd(savedDraft)
                draftWasRestored = true
            } else {
                restoredFromTarget = null
                composerNoteState.setTextAndPlaceCursorAtEnd("")
                draftWasRestored = false
            }
        }
        lastSeenPending = rawPending
        rebuildRestoredSelection = null
    } else if (rawPending == null && rebuildRestoredSelection == null) {
        lastSeenPending = null
    }

    val pending = rawPending ?: rebuildRestoredSelection
    val liveDraftText = composerNoteState.text.toString()
    if (pending != null && draftWasRestored && liveDraftText.isBlank()) {
        draftWasRestored = false
        restoredFromTarget = null
    }
    SideEffect {
        val restore = rebuildRestoredSelection
        if (coordinator.pendingSelection == null && restore != null) {
            coordinator.pendingSelection = restore
            rebuildRestoredSelection = null
        }
        if (pending != null) {
            if (liveDraftText.isNotBlank()) {
                coordinator.composerDraftText = liveDraftText
                if (coordinator.composerDraftTarget == null || !draftWasRestored) {
                    coordinator.composerDraftTarget = pending.target
                }
            } else {
                coordinator.clearComposerDraft()
            }
        }
    }

    fun dismissComposerPreservingDraft(fromEscapeKey: Boolean) {
        val currentText = composerNoteState.text.toString()
        if (currentText.isNotBlank()) {
            coordinator.composerDraftText = currentText
            if (pending != null && coordinator.composerDraftTarget == null) {
                coordinator.composerDraftTarget = pending.target
            }
        } else {
            coordinator.clearComposerDraft()
        }
        rebuildRestoredSelection = null
        lastSeenPending = null
        coordinator.dismissActivePopups(fromEscapeKey = fromEscapeKey)
    }

    LaunchedEffect(store) {
        while (true) {
            delay(250)
            store.reloadFromDisk()
            store.reloadClaims()
        }
    }

    val targetIndex = animatic.targetIndex
    val currentSlideComments = store.comments.filter {
        it.sceneIndex == targetIndex.sceneIndex && it.frameIndex == targetIndex.frameIndex
    }
    coordinator.currentSlideCommentsProvider = {
        val idx = animatic.targetIndex
        store.comments.filter {
            it.sceneIndex == idx.sceneIndex && it.frameIndex == idx.frameIndex
        }
    }
    coordinator.commentDisplayNumberProvider = { id -> reviewDisplayNumber(store.comments, id) }
    coordinator.currentSlideContextProvider = {
        val idx = animatic.targetIndex
        val sb = animatic.storyboard
        SlideCaptureContext(
            sceneIndex = idx.sceneIndex,
            frameIndex = idx.frameIndex,
            sceneCount = sb.scenes.size,
            frameCount = sb.scenes.getOrNull(idx.sceneIndex)?.frames?.size ?: 1,
        )
    }

    var outerBoundsInRoot by remember { mutableStateOf<Rect?>(null) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { ev ->
                if (ev.key == Key.CtrlLeft || ev.key == Key.CtrlRight) {
                    coordinator.isCtrlKeyHeld = ev.type == KeyEventType.KeyDown
                }
                if (ev.type == KeyEventType.KeyDown && ev.key == Key.F3) {
                    coordinator.inspectModeEnabled = !coordinator.inspectModeEnabled
                    return@onPreviewKeyEvent true
                }
                if (ev.key == Key.Escape) {
                    if (coordinator.swallowNextEscapeKeyUp) {
                        if (ev.type == KeyEventType.KeyUp) {
                            coordinator.swallowNextEscapeKeyUp = false
                        }
                        return@onPreviewKeyEvent true
                    }
                    if (coordinator.pendingSelection != null ||
                        rebuildRestoredSelection != null ||
                        coordinator.selectedPinCommentId != null
                    ) {
                        if (ev.type == KeyEventType.KeyDown) {
                            if (coordinator.pendingSelection != null || rebuildRestoredSelection != null) {
                                dismissComposerPreservingDraft(fromEscapeKey = true)
                            } else {
                                coordinator.dismissActivePopups(fromEscapeKey = true)
                            }
                        }
                        return@onPreviewKeyEvent true
                    }
                }
                false
            }
            .onGloballyPositioned { coords ->
                if (coords.isAttached) {
                    outerBoundsInRoot = runCatching { coords.boundsInRoot() }.getOrNull()
                }
            },
    ) {
        MaterialTheme(
            colors = stageMaterialColors,
            typography = stageMaterialTypography,
        ) {
            content()
        }

        // Audience-facing presenting (windowed or fullscreen) keeps the review HUD and notices off stage.
        // Also hide the HUD whenever the Esc Overview grid is open so it never occludes thumbnails.
        val isAudience = LocalPresentingFullscreen.current || LocalAudiencePresenting.current
        val hideForAudience = isAudience && !coordinator.inspectModeEnabled &&
            pending == null && coordinator.selectedPinCommentId == null
        val overviewOpen = LocalOverviewOpen.current
        val showHud = coordinator.showStageBadges &&
            !hideForAudience &&
            !overviewOpen &&
            coordinator.stageBoundsInRoot != null
        if (!showHud) {
            coordinator.hudBoundsInRoot = null
        }
        val agentConfigured = store.effectiveConversationId().isNotBlank()
        val pushAvailable = store.agentPushAvailable()
        CiacolaJewelTheme(isDark = isDark) {
            Box(Modifier.fillMaxSize()) {
                if (showHud) {
                    // A claimed comment is with an agent, not waiting for one: counting it as
                    // waiting is how the HUD offered to send work that was already under way.
                    val queuedCount = store.comments.count {
                        (it.status == ReviewCommentStatus.Queued ||
                            it.status == ReviewCommentStatus.Failed) &&
                            it.id !in store.claimedIds
                    }
                    val applyingCount = store.comments.count {
                        // A claim only means work in progress while the comment is still waiting
                        // for a verdict. Resolved or failed, it is no longer with anybody, and
                        // counting a stale sidecar entry here spins the HUD for ever.
                        it.status == ReviewCommentStatus.Applying ||
                            (it.status == ReviewCommentStatus.Queued && it.id in store.claimedIds)
                    }
                    val appliedCount = store.comments.count { it.status == ReviewCommentStatus.Applied }
                    // Failed counts as waiting again once the claim is gone, so this is only the
                    // case nothing else reports: an agent holds it and has said it cannot do it.
                    val needsAttentionCount = store.comments.count {
                        it.status == ReviewCommentStatus.Failed && it.id in store.claimedIds
                    }

                    ReviewHudToolbar(
                        inspectActive = coordinator.inspectModeEnabled || coordinator.isCtrlKeyHeld,
                        inspectPinned = coordinator.inspectModeEnabled,
                        timelineDocked = windowController.timelineDockedProvider(),
                        agentConfigured = agentConfigured,
                        pushAvailable = pushAvailable,
                        queuedCount = queuedCount,
                        applyingCount = applyingCount,
                        deliveringCount = store.deliveringIds.size,
                        appliedCount = appliedCount,
                        needsAttentionCount = needsAttentionCount,
                        onHudBoundsChanged = { coordinator.hudBoundsInRoot = it },
                        onToggleInspectMode = {
                            coordinator.inspectModeEnabled = !coordinator.inspectModeEnabled
                        },
                        onToggleTimeline = windowController.onToggleTimeline,
                        onOpenReviewPanel = {
                            if (agentConfigured || !pushAvailable) {
                                windowController.visible = true
                            } else {
                                windowController.openForAgentConfiguration()
                            }
                        },
                        onFlushQueued = {
                            if (store.effectiveConversationId().isBlank()) {
                                windowController.openForAgentConfiguration()
                            } else {
                                scope.launch { store.flushQueued() }
                            }
                        },
                    )
                }

                val notice = store.activityNotice
                if (notice != null && !overviewOpen && !hideForAudience) {
                    ReviewActivityNoticeBar(
                        notice = notice,
                        onDismiss = { store.dismissNotice(notice.id) },
                        onOpenReviewPanel = {
                            store.dismissNotice(notice.id)
                            // Only chase a Conversation ID when one would change anything. With
                            // no push command the panel's red "not configured" banner is an
                            // error about a feature that is not on offer.
                            if (agentConfigured || !pushAvailable) {
                                windowController.visible = true
                            } else {
                                windowController.openForAgentConfiguration()
                            }
                        },
                    )
                }

                if (pending != null) {
                    AnchoredCommentComposerPopup(
                        pending = pending,
                        store = store,
                        themePresetName = preset.name,
                        outerBoundsInRoot = outerBoundsInRoot,
                        stageBoundsInRoot = coordinator.stageBoundsInRoot,
                        noteState = composerNoteState,
                        draftRestored = draftWasRestored,
                        restoredFromTarget = restoredFromTarget,
                        deckId = windowController.deckIdProvider(),
                        deckFilePath = windowController.deckFilePathProvider(),
                        slideId = windowController.slideIdProvider?.invoke(pending.sceneIndex),
                        onClearDraft = {
                            draftWasRestored = false
                            restoredFromTarget = null
                            coordinator.clearComposerDraft()
                            composerNoteState.setTextAndPlaceCursorAtEnd("")
                        },
                        onDismiss = { fromEsc ->
                            dismissComposerPreservingDraft(fromEscapeKey = fromEsc)
                        },
                        onSubmit = { comment, dispatchImmediate, pngBytes ->
                            rebuildRestoredSelection = null
                            lastSeenPending = null
                            draftWasRestored = false
                            restoredFromTarget = null
                            val submittedNote = composerNoteState.text.toString()
                            coordinator.clearComposerDraft()
                            composerNoteState.setTextAndPlaceCursorAtEnd("")
                            coordinator.dismissActivePopups(fromEscapeKey = false)
                            scope.launch {
                                val saved = store.submitComment(
                                    comment = comment,
                                    dispatchImmediate = dispatchImmediate,
                                    pngBytes = pngBytes,
                                )
                                if (saved == null) {
                                    // The queue was busy and nothing was written. A notice alone
                                    // would leave the author retyping a note they had already
                                    // sent, so the draft comes back with its target.
                                    coordinator.composerDraftText = submittedNote
                                    coordinator.composerDraftTarget = comment.target
                                }
                            }
                        },
                    )
                }

                val activePinId = coordinator.selectedPinCommentId
                val activePinComment = currentSlideComments.firstOrNull { it.id == activePinId }
                if (activePinComment != null && pending == null) {
                    AnchoredSelectedPinPopup(
                        comment = activePinComment,
                        displayNumber = reviewDisplayNumber(store.comments, activePinComment.id),
                        outerBoundsInRoot = outerBoundsInRoot,
                        stageBoundsInRoot = coordinator.stageBoundsInRoot,
                        stageFormatSize = coordinator.stageFormatSize,
                        agentConfigured = agentConfigured,
                        pushAvailable = pushAvailable,
                        delivering = activePinComment.id in store.deliveringIds,
                        claimed = activePinComment.id in store.claimedIds,
                        onReleaseClaim = {
                            scope.launch { store.releaseClaim(activePinComment.id) }
                            coordinator.dismissActivePopups(fromEscapeKey = false)
                        },
                        onClose = { fromEsc ->
                            coordinator.dismissActivePopups(fromEscapeKey = fromEsc)
                        },
                        onDelete = {
                            store.deleteComment(activePinComment.id)
                            coordinator.selectedPinCommentId = null
                        },
                        onMarkApplied = {
                            store.markCommentApplied(activePinComment.id)
                            coordinator.selectedPinCommentId = null
                        },
                        onRetry = {
                            if (store.effectiveConversationId().isBlank()) {
                                coordinator.dismissActivePopups(fromEscapeKey = false)
                                windowController.openForAgentConfiguration()
                            } else {
                                scope.launch { store.retryComment(activePinComment.id) }
                            }
                        },
                        onConfigureAgent = {
                            coordinator.dismissActivePopups(fromEscapeKey = false)
                            windowController.openForAgentConfiguration()
                        },
                        onCopyPrompt = {
                            store.copyPromptToClipboard(listOf(activePinComment))
                        },
                    )
                }
            }
        }
    }
}
