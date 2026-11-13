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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.bnorm.storyboard.Storyboard
import dev.sebastiano.ciacola.ui.iconKey
import dev.sebastiano.ciacola.ui.labelText
import dev.sebastiano.ciacola.ui.rememberReviewStatusPalette
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ReviewSidecarPanel(windowController: SlideReviewWindow) {
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
    var agentConfigExpanded by remember { mutableStateOf(false) }
    val showAgentConfigDetails = store.comments.isNotEmpty() ||
        windowController.highlightAgentConfigBanner ||
        agentConfigExpanded

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(globalColors.panelBackground)
            .onPreviewKeyEvent { ev ->
                if (ev.type == KeyEventType.KeyDown && ev.key == Key.F3) {
                    windowController.stageCoordinator.inspectModeEnabled =
                        !windowController.stageCoordinator.inspectModeEnabled
                    true
                } else {
                    false
                }
            },
    ) {
        if (showAgentConfigDetails) {
            // Compact, always-visible header bar when comments exist or Agent Configuration is expanded.
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
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
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
                        if (store.comments.isEmpty() && !windowController.highlightAgentConfigBanner) {
                            OutlinedButton(
                                onClick = { agentConfigExpanded = false },
                                modifier = Modifier.testTag("ciacola-review-agent-config-toggle"),
                            ) {
                                Text("Hide", style = JewelTheme.typography.small)
                            }
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
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Slide Review & Hot-Reload Feedback",
                    style = JewelTheme.typography.h2TextStyle,
                    color = globalColors.text.normal,
                    modifier = Modifier.weight(1f),
                )
                if (!showAgentConfigDetails) {
                    val cfgStatus = when {
                        isShowingAutoDetected -> "Auto-detected"
                        effectiveConvId.isNotBlank() -> "Configured"
                        else -> "Not set"
                    }
                    Tooltip(tooltip = { Text("Show Target Agent Conversation ID settings ($cfgStatus)") }) {
                        OutlinedButton(
                            onClick = { agentConfigExpanded = true },
                            modifier = Modifier.testTag("ciacola-review-agent-config-toggle"),
                        ) {
                            Text("Agent Configuration", style = JewelTheme.typography.small)
                        }
                    }
                }
            }
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

            if (store.comments.isNotEmpty()) {
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
    statusColor: Color,
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

        val deliveryError = item.deliveryError
        if (!deliveryError.isNullOrBlank()) {
            Text(
                text = deliveryError,
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
