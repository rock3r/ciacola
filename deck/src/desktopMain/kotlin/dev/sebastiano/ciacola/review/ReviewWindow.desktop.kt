package dev.sebastiano.ciacola.review

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.KeyShortcut
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import dev.bnorm.storyboard.easel.LocalPresentingFullscreen
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.MenuScope
import dev.bnorm.storyboard.ContentDecorator
import dev.bnorm.storyboard.Storyboard
import dev.bnorm.storyboard.easel.Animatic
import dev.bnorm.storyboard.easel.EaselWindow
import dev.bnorm.storyboard.easel.LocalOverviewOpen
import dev.sebastiano.ciacola.theme.DeckThemePreset
import dev.sebastiano.ciacola.theme.DeckThemes
import dev.sebastiano.ciacola.ui.CiacolaJewelTheme
import dev.sebastiano.ciacola.ui.iconKey
import dev.sebastiano.ciacola.ui.labelText
import dev.sebastiano.ciacola.ui.rememberReviewStatusPalette
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.GroupHeader
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.IconButton
import org.jetbrains.jewel.ui.component.InfoText
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField
import org.jetbrains.jewel.ui.component.Tooltip
import org.jetbrains.jewel.ui.component.VerticallyScrollableContainer
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.ui.typography

/**
 * Storyboard [EaselWindow] providing Ciacola's interactive `Ctrl+Click` slide review overlay,
 * menu actions, and Jewel-styled review feed window.
 */
class SlideReviewWindow(
    initialAnimatic: Animatic,
    val store: ReviewCommentStore,
    val stageCoordinator: ReviewStageCoordinator,
    val currentPresetProvider: () -> DeckThemePreset = { DeckThemePreset.StageDark },
    val timelineDockedProvider: () -> Boolean = { false },
    val onToggleTimeline: (() -> Unit)? = null,
) : EaselWindow {
    var animatic: Animatic by mutableStateOf(initialAnimatic)
        internal set

    override val name: String = "Review"
    override val minimumSize: DpSize get() = DpSize(420.dp, 480.dp)
    override var visible: Boolean by mutableStateOf(false)
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
            it.status == ReviewCommentStatus.Queued || it.status == ReviewCommentStatus.Failed
        }
        val appliedCount = store.comments.count { it.status == ReviewCommentStatus.Applied }

        CheckboxItem(
            text = "Inspect Mode (Click without Ctrl)",
            checked = stageCoordinator.inspectModeEnabled,
            shortcut = KeyShortcut(Key.F3),
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
        Item(
            text = if (agentConfigured || queuedCount == 0) {
                "Send Queued Comments ($queuedCount)"
            } else {
                "Configure Agent to Send ($queuedCount)…"
            },
            enabled = queuedCount > 0,
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
): SlideReviewWindow = remember(store, stageCoordinator) {
    SlideReviewWindow(
        initialAnimatic = animatic,
        store = store,
        stageCoordinator = stageCoordinator,
        currentPresetProvider = currentPresetProvider,
        timelineDockedProvider = timelineDockedProvider,
        onToggleTimeline = onToggleTimeline,
    )
}.also {
    it.animatic = animatic
}

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

        // Fullscreen presenting is audience-facing: keep the review HUD off it unless reviewing.
        // Also hide the HUD whenever the Esc Overview grid is open so it never occludes thumbnails.
        val hideForAudience = LocalPresentingFullscreen.current && !coordinator.inspectModeEnabled &&
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
        CiacolaJewelTheme(isDark = isDark) {
            Box(Modifier.fillMaxSize()) {
                if (showHud) {
                    val queuedCount = store.comments.count {
                        it.status == ReviewCommentStatus.Queued || it.status == ReviewCommentStatus.Failed
                    }
                    val applyingCount = store.comments.count { it.status == ReviewCommentStatus.Applying }
                    val appliedCount = store.comments.count { it.status == ReviewCommentStatus.Applied }

                    ReviewHudToolbar(
                        inspectActive = coordinator.inspectModeEnabled || coordinator.isCtrlKeyHeld,
                        inspectPinned = coordinator.inspectModeEnabled,
                        timelineDocked = windowController.timelineDockedProvider(),
                        agentConfigured = agentConfigured,
                        queuedCount = queuedCount,
                        applyingCount = applyingCount,
                        appliedCount = appliedCount,
                        onHudBoundsChanged = { coordinator.hudBoundsInRoot = it },
                        onToggleInspectMode = {
                            coordinator.inspectModeEnabled = !coordinator.inspectModeEnabled
                        },
                        onToggleTimeline = windowController.onToggleTimeline,
                        onOpenReviewPanel = {
                            if (agentConfigured) {
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
                            coordinator.clearComposerDraft()
                            composerNoteState.setTextAndPlaceCursorAtEnd("")
                            coordinator.dismissActivePopups(fromEscapeKey = false)
                            scope.launch {
                                store.submitComment(
                                    comment = comment,
                                    dispatchImmediate = dispatchImmediate,
                                    pngBytes = pngBytes,
                                )
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
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ReviewSidecarPanel(windowController: SlideReviewWindow) {
    val store = windowController.store
    val animatic = windowController.animatic
    val scope = rememberCoroutineScope()
    val globalColors = JewelTheme.globalColors
    val statusPalette = rememberReviewStatusPalette()

    val resolvedConv = store.resolveConversationId()
    val convIdState = rememberTextFieldState(initialText = resolvedConv.id)
    val convIdFocusRequester = remember { FocusRequester() }
    LaunchedEffect(store.config.conversationId) {
        val current = store.effectiveConversationId()
        if (current != convIdState.text.toString()) {
            convIdState.setTextAndPlaceCursorAtEnd(current)
        }
    }
    LaunchedEffect(windowController.highlightAgentConfigBanner) {
        if (windowController.highlightAgentConfigBanner) {
            runCatching { convIdFocusRequester.requestFocus() }
        }
    }

    val queuedCount = store.comments.count {
        it.status == ReviewCommentStatus.Queued || it.status == ReviewCommentStatus.Failed
    }
    val hasApplied = store.comments.any { it.status == ReviewCommentStatus.Applied }
    val typedConvId = convIdState.text.toString().trim()
    val effectiveConvId = typedConvId.ifEmpty { resolvedConv.id }
    val isShowingAutoDetected = resolvedConv.isAutoDetected &&
        (typedConvId.isEmpty() || typedConvId == resolvedConv.id)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(globalColors.panelBackground),
    ) {
        // Compact, always-visible header bar pinned at the top of the F4 Slide Review window.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .testTag("ciacola-review-header-bar"),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Target Agent Conversation ID",
                    style = JewelTheme.typography.small.copy(fontWeight = FontWeight.SemiBold),
                    color = globalColors.text.normal,
                )
                when {
                    isShowingAutoDetected -> {
                        Tooltip(
                            tooltip = {
                                Text(
                                    "Auto-populated from ${resolvedConv.sourceName} because .storyboard/review-config.json has a blank conversationId."
                                )
                            },
                        ) {
                            Row(
                                modifier = Modifier.testTag("ciacola-review-conv-autodetected-badge"),
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    key = AllIconsKeys.General.GreenCheckmark,
                                    contentDescription = "Auto-detected Conversation ID",
                                    modifier = Modifier.size(12.dp),
                                )
                                Text(
                                    text = "Auto-detected (${resolvedConv.sourceName})",
                                    style = JewelTheme.typography.small,
                                    color = statusPalette.applied,
                                )
                            }
                        }
                    }

                    resolvedConv.source == ConversationIdSource.Config && typedConvId == resolvedConv.id -> {
                        Text(
                            text = "Saved in review-config.json",
                            style = JewelTheme.typography.small,
                            color = globalColors.text.info,
                            modifier = Modifier.testTag("ciacola-review-conv-saved-badge"),
                        )
                    }

                    effectiveConvId.isBlank() -> {
                        Text(
                            text = "Not configured",
                            style = JewelTheme.typography.small,
                            color = statusPalette.failed,
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextField(
                    state = convIdState,
                    placeholder = { Text("Target Agent Conversation ID") },
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(convIdFocusRequester)
                        .testTag("ciacola-review-panel-conv-id-input"),
                )
                OutlinedButton(
                    onClick = {
                        store.saveConfig(
                            store.config.copy(conversationId = typedConvId)
                        )
                        if (typedConvId.isNotEmpty()) {
                            windowController.highlightAgentConfigBanner = false
                        }
                    },
                    modifier = Modifier.testTag("ciacola-review-panel-save-conv-button"),
                ) {
                    Text("Save")
                }
            }
        }

        Divider(Orientation.Horizontal, Modifier.fillMaxWidth())

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "Slide Review & Hot-Reload Feedback",
                style = JewelTheme.typography.h2TextStyle,
                color = globalColors.text.normal,
            )
            InfoText("Hold Ctrl + Click (or Ctrl + Drag a marquee) on any slide element in the Story window, or press F3 for persistent Inspect Mode.")

            if (effectiveConvId.isBlank() && (queuedCount > 0 || windowController.highlightAgentConfigBanner)) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, statusPalette.failed, RoundedCornerShape(6.dp))
                        .padding(10.dp)
                        .testTag("ciacola-review-missing-conv-banner"),
                ) {
                    Text(
                        text = "No Conversation ID configured. Paste your target agent's Conversation ID above and click Save (or Send Queued) to dispatch comments.",
                        style = JewelTheme.typography.small,
                        color = globalColors.text.error,
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Tooltip(tooltip = { Text("Dispatch all queued and failed comments to target agent") }) {
                    DefaultButton(
                        enabled = queuedCount > 0 && effectiveConvId.isNotBlank(),
                        onClick = {
                            if (typedConvId.isNotEmpty() && typedConvId != store.config.conversationId) {
                                store.saveConfig(store.config.copy(conversationId = typedConvId))
                            }
                            windowController.highlightAgentConfigBanner = false
                            scope.launch { store.flushQueued() }
                        },
                    ) {
                        Text("Send Queued ($queuedCount)")
                    }
                }
                Tooltip(tooltip = { Text("Remove all applied comments from the review feed") }) {
                    OutlinedButton(
                        enabled = hasApplied,
                        onClick = { store.clearAppliedComments() },
                    ) {
                        Text("Clear Applied")
                    }
                }
            }

            val lastTrashed = store.trashedComments.lastOrNull()
            if (lastTrashed != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, statusPalette.queued, RoundedCornerShape(6.dp))
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                        .testTag("ciacola-review-trash-restore-banner"),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Discarded (${store.trashedComments.size}): “${lastTrashed.note}”",
                        style = JewelTheme.typography.small,
                        color = globalColors.text.normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(end = 8.dp),
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Tooltip(tooltip = { Text("Restore the most recently discarded comment") }) {
                            OutlinedButton(
                                onClick = { store.restoreLastDeleted() },
                                modifier = Modifier.testTag("ciacola-review-restore-trash-button"),
                            ) {
                                Text("Undo", style = JewelTheme.typography.small)
                            }
                        }
                        Tooltip(tooltip = { Text("Dismiss undo notice") }) {
                            IconButton(
                                onClick = { store.dismissTrashItem(lastTrashed.id) },
                                modifier = Modifier.size(20.dp),
                            ) {
                                Icon(
                                    key = AllIconsKeys.General.Close,
                                    contentDescription = "Dismiss undo notice",
                                    modifier = Modifier.size(12.dp),
                                )
                            }
                        }
                    }
                }
            }

            GroupHeader("Slide comments (${store.comments.size}):")

            if (store.comments.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    InfoText("No review comments yet. Ctrl+Click any element on the slide stage.")
                }
            } else {
                val listState = rememberLazyListState()
                VerticallyScrollableContainer(
                    scrollState = listState,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(end = 10.dp)
                            .testTag("ciacola-review-comments-list"),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        itemsIndexed(
                            items = store.comments,
                            key = { _, item -> item.id },
                            contentType = { _, _ -> "review-comment-card" },
                        ) { idx, item ->
                            ReviewCommentRowCard(
                                indexNumber = idx + 1,
                                item = item,
                                statusColor = statusPalette.colorFor(item.status),
                                agentConfigured = effectiveConvId.isNotBlank(),
                                onJumpToSlide = {
                                    scope.launch {
                                        val target = Storyboard.Index(item.sceneIndex, item.frameIndex)
                                        if (target in animatic.storyboard.indices) {
                                            animatic.jumpTo(target)
                                        }
                                    }
                                },
                                onMarkApplied = {
                                    store.markCommentApplied(item.id)
                                },
                                onRetry = {
                                    if (typedConvId.isNotEmpty() && typedConvId != store.config.conversationId) {
                                        store.saveConfig(store.config.copy(conversationId = typedConvId))
                                    }
                                    scope.launch { store.retryComment(item.id) }
                                },
                                onConfigureAgent = {
                                    windowController.openForAgentConfiguration()
                                },
                                onResolve = {
                                    store.deleteComment(item.id)
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ReviewCommentRowCard(
    indexNumber: Int,
    item: SlideReviewComment,
    statusColor: androidx.compose.ui.graphics.Color,
    agentConfigured: Boolean,
    onJumpToSlide: () -> Unit,
    onMarkApplied: () -> Unit,
    onRetry: () -> Unit,
    onConfigureAgent: () -> Unit,
    onResolve: () -> Unit,
) {
    val globalColors = JewelTheme.globalColors
    val statusPalette = rememberReviewStatusPalette()
    val cardShape = RoundedCornerShape(6.dp)
    var confirmDiscard by remember(item.id) { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, globalColors.borders.normal, cardShape)
            .clickable(onClick = onJumpToSlide)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val scenePart = if (item.sceneTitle.isNotBlank()) {
                "Scene ${item.sceneIndex + 1} (${item.sceneTitle})"
            } else {
                "Scene ${item.sceneIndex + 1}"
            }
            Text(
                text = "#$indexNumber · $scenePart, Frame ${item.frameIndex + 1} (${item.target.kind})",
                style = JewelTheme.typography.medium.copy(fontWeight = FontWeight.SemiBold),
                color = globalColors.text.normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(end = 8.dp),
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    key = item.status.iconKey(),
                    contentDescription = item.status.labelText(),
                    modifier = Modifier.size(13.dp),
                )
                Text(
                    text = item.status.labelText(),
                    style = JewelTheme.typography.small,
                    color = statusColor,
                )
            }
        }

        if (item.target.textSnippet.isNotBlank()) {
            Tooltip(tooltip = { Text(item.target.textSnippet) }) {
                Text(
                    text = "“${item.target.textSnippet}”",
                    style = JewelTheme.typography.small,
                    color = globalColors.text.info,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Text(
            text = item.note,
            style = JewelTheme.typography.regular,
            color = globalColors.text.normal,
        )

        if (!item.deliveryError.isNullOrBlank()) {
            Text(
                text = item.deliveryError,
                style = JewelTheme.typography.small,
                color = globalColors.text.error,
            )
        }

        if (item.resolution.isNotBlank()) {
            Divider(Orientation.Horizontal, Modifier.fillMaxWidth())
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(
                    key = AllIconsKeys.General.GreenCheckmark,
                    contentDescription = "Resolution",
                    modifier = Modifier.size(13.dp),
                )
                Text(
                    text = item.resolution,
                    style = JewelTheme.typography.small,
                    color = globalColors.text.info,
                )
            }
        }

        if (confirmDiscard) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, statusPalette.queued, RoundedCornerShape(4.dp))
                    .padding(6.dp)
                    .testTag("ciacola-review-card-discard-confirm-${item.id}"),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Discard unapplied feedback?",
                    style = JewelTheme.typography.small,
                    color = globalColors.text.normal,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = { confirmDiscard = false }) {
                        Text("Keep", style = JewelTheme.typography.small)
                    }
                    DefaultButton(
                        onClick = onResolve,
                        modifier = Modifier.testTag("ciacola-review-card-confirm-discard-${item.id}"),
                    ) {
                        Text("Discard", style = JewelTheme.typography.small)
                    }
                }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (item.status != ReviewCommentStatus.Applied) {
                        Tooltip(tooltip = { Text("Mark this comment as applied") }) {
                            OutlinedButton(
                                onClick = onMarkApplied,
                                modifier = Modifier.testTag("ciacola-review-mark-applied-${item.id}"),
                            ) {
                                Text("Mark Applied", style = JewelTheme.typography.small)
                            }
                        }
                        if (agentConfigured) {
                            val isQueued = item.status == ReviewCommentStatus.Queued
                            val label = if (isQueued) "Send Now" else "Retry Send"
                            val tip = if (isQueued) {
                                "Send this queued comment to agent"
                            } else {
                                "Retry dispatching this comment to agent"
                            }
                            Tooltip(tooltip = { Text(tip) }) {
                                OutlinedButton(
                                    onClick = onRetry,
                                    modifier = Modifier.testTag("ciacola-review-retry-${item.id}"),
                                ) {
                                    Text(label, style = JewelTheme.typography.small)
                                }
                            }
                        } else {
                            Tooltip(tooltip = { Text("Configure Target Agent Conversation ID") }) {
                                OutlinedButton(onClick = onConfigureAgent) {
                                    Text("Set Agent…", style = JewelTheme.typography.small)
                                }
                            }
                        }
                    }
                    Tooltip(
                        tooltip = {
                            Text(
                                if (item.status == ReviewCommentStatus.Applied) {
                                    "Resolve and remove this comment"
                                } else {
                                    "Discard this unapplied comment (confirmation required)"
                                },
                            )
                        },
                    ) {
                        OutlinedButton(
                            onClick = {
                                if (item.status == ReviewCommentStatus.Applied) {
                                    onResolve()
                                } else {
                                    confirmDiscard = true
                                }
                            },
                            modifier = Modifier.testTag("ciacola-review-resolve-${item.id}"),
                        ) {
                            Text("Resolve", style = JewelTheme.typography.small)
                        }
                    }
                }
            }
        }
    }
}
