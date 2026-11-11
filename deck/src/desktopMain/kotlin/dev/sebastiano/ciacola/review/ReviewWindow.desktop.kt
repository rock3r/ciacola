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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.MenuScope
import dev.bnorm.storyboard.ContentDecorator
import dev.bnorm.storyboard.Storyboard
import dev.bnorm.storyboard.easel.Animatic
import dev.bnorm.storyboard.easel.EaselWindow
import dev.sebastiano.ciacola.theme.DeckThemePreset
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
import org.jetbrains.jewel.ui.component.InfoText
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField
import org.jetbrains.jewel.ui.component.Tooltip
import org.jetbrains.jewel.ui.component.VerticallyScrollableContainer
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
    override var visible: Boolean by mutableStateOf(false)

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
        Item(
            text = "Send Queued Comments ($queuedCount)",
            enabled = queuedCount > 0,
            onClick = { scope.launch { store.flushQueued() } },
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
    coordinator.isDarkThemeProvider = { isDark }

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
    coordinator.currentSlideCommentsProvider = { currentSlideComments }

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
                    if (coordinator.pendingSelection != null || coordinator.selectedPinCommentId != null) {
                        if (ev.type == KeyEventType.KeyDown) {
                            coordinator.dismissActivePopups(fromEscapeKey = true)
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
        content()

        CiacolaJewelTheme(isDark = isDark) {
            Box(Modifier.fillMaxSize()) {
                if (coordinator.showStageBadges && coordinator.stageBoundsInRoot != null) {
                    val queuedCount = store.comments.count {
                        it.status == ReviewCommentStatus.Queued || it.status == ReviewCommentStatus.Failed
                    }
                    val applyingCount = store.comments.count { it.status == ReviewCommentStatus.Applying }
                    val appliedCount = store.comments.count { it.status == ReviewCommentStatus.Applied }

                    ReviewHudToolbar(
                        inspectActive = coordinator.inspectModeEnabled || coordinator.isCtrlKeyHeld,
                        inspectPinned = coordinator.inspectModeEnabled,
                        timelineDocked = windowController.timelineDockedProvider(),
                        queuedCount = queuedCount,
                        applyingCount = applyingCount,
                        appliedCount = appliedCount,
                        onToggleInspectMode = {
                            coordinator.inspectModeEnabled = !coordinator.inspectModeEnabled
                        },
                        onToggleTimeline = windowController.onToggleTimeline,
                        onOpenReviewPanel = { windowController.visible = true },
                        onFlushQueued = { scope.launch { store.flushQueued() } },
                    )
                }

                val pending = coordinator.pendingSelection
                if (pending != null) {
                    AnchoredCommentComposerPopup(
                        pending = pending,
                        animatic = animatic,
                        store = store,
                        themePresetName = preset.name,
                        outerBoundsInRoot = outerBoundsInRoot,
                        stageBoundsInRoot = coordinator.stageBoundsInRoot,
                        onDismiss = { fromEsc ->
                            coordinator.dismissActivePopups(fromEscapeKey = fromEsc)
                        },
                        onSubmit = { comment, dispatchImmediate, pngBytes ->
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
                        outerBoundsInRoot = outerBoundsInRoot,
                        stageBoundsInRoot = coordinator.stageBoundsInRoot,
                        stageFormatSize = coordinator.stageFormatSize,
                        onClose = { fromEsc ->
                            coordinator.dismissActivePopups(fromEscapeKey = fromEsc)
                        },
                        onDelete = {
                            store.deleteComment(activePinComment.id)
                            coordinator.selectedPinCommentId = null
                        },
                        onRetry = {
                            scope.launch { store.retryComment(activePinComment.id) }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ReviewSidecarPanel(windowController: SlideReviewWindow) {
    val store = windowController.store
    val animatic = windowController.animatic
    val scope = rememberCoroutineScope()
    val globalColors = JewelTheme.globalColors
    val statusPalette = rememberReviewStatusPalette()

    val convIdState = rememberTextFieldState(initialText = store.effectiveConversationId())
    LaunchedEffect(store.config.conversationId) {
        val current = store.effectiveConversationId()
        if (current != convIdState.text.toString()) {
            convIdState.setTextAndPlaceCursorAtEnd(current)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(globalColors.panelBackground)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "Slide Review & Hot-Reload Feedback",
            style = JewelTheme.typography.h2TextStyle,
            color = globalColors.text.normal,
        )
        InfoText("Hold Ctrl + Click (or Ctrl + Drag a marquee) on any slide element in the Story window, or press F3 for persistent Inspect Mode.")

        GroupHeader("Target agent:")
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextField(
                state = convIdState,
                placeholder = { Text("Conversation ID") },
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(
                onClick = {
                    store.saveConfig(
                        store.config.copy(conversationId = convIdState.text.toString().trim())
                    )
                },
            ) {
                Text("Save")
            }
        }

        val queuedCount = store.comments.count {
            it.status == ReviewCommentStatus.Queued || it.status == ReviewCommentStatus.Failed
        }
        val hasApplied = store.comments.any { it.status == ReviewCommentStatus.Applied }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DefaultButton(
                enabled = queuedCount > 0,
                onClick = { scope.launch { store.flushQueued() } },
            ) {
                Text("Send Queued ($queuedCount)")
            }
            OutlinedButton(
                enabled = hasApplied,
                onClick = { store.clearAppliedComments() },
            ) {
                Text("Clear Applied")
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
                    modifier = Modifier.fillMaxSize().padding(end = 10.dp),
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
                            onJumpToSlide = {
                                scope.launch {
                                    val target = Storyboard.Index(item.sceneIndex, item.frameIndex)
                                    if (target in animatic.storyboard.indices) {
                                        animatic.jumpTo(target)
                                    }
                                }
                            },
                        )
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
    onJumpToSlide: () -> Unit,
) {
    val globalColors = JewelTheme.globalColors
    val cardShape = RoundedCornerShape(6.dp)
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
            Text(
                text = "#$indexNumber · Scene ${item.sceneIndex + 1}, Frame ${item.frameIndex + 1} (${item.target.kind})",
                style = JewelTheme.typography.medium.copy(fontWeight = FontWeight.SemiBold),
                color = globalColors.text.normal,
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

        if (item.resolution.isNotBlank()) {
            Divider(Orientation.Horizontal, Modifier.fillMaxWidth())
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(
                    key = org.jetbrains.jewel.ui.icons.AllIconsKeys.General.GreenCheckmark,
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
    }
}
