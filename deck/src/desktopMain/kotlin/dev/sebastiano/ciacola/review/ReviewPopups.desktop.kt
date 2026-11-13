package dev.sebastiano.ciacola.review

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties
import dev.sebastiano.ciacola.ui.ReviewStatusPalette
import dev.sebastiano.ciacola.ui.iconKey
import dev.sebastiano.ciacola.ui.labelText
import dev.sebastiano.ciacola.ui.rememberReviewStatusPalette
import java.time.Instant
import kotlin.math.roundToInt
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.IconButton
import org.jetbrains.jewel.ui.component.InfoText
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.PopupContainer
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextArea
import org.jetbrains.jewel.ui.component.TextField
import org.jetbrains.jewel.ui.component.Tooltip
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.ui.typography

/**
 * Compact, flat Int UI status bar anchored in the top-right corner of the Story window.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun BoxScope.ReviewHudToolbar(
    inspectActive: Boolean,
    inspectPinned: Boolean,
    timelineDocked: Boolean = false,
    agentConfigured: Boolean = true,
    queuedCount: Int,
    applyingCount: Int,
    appliedCount: Int,
    onHudBoundsChanged: (Rect) -> Unit = {},
    onToggleInspectMode: () -> Unit,
    onToggleTimeline: (() -> Unit)? = null,
    onOpenReviewPanel: () -> Unit,
    onFlushQueued: () -> Unit,
) {
    val globalColors = JewelTheme.globalColors
    val statusPalette = rememberReviewStatusPalette()
    val borderColor = if (inspectActive) statusPalette.inspectAccent else globalColors.borders.normal
    val toolbarShape = RoundedCornerShape(6.dp)

    Row(
        modifier = Modifier
            .align(Alignment.TopEnd)
            .padding(10.dp)
            .onGloballyPositioned { coords ->
                if (coords.isAttached) {
                    onHudBoundsChanged(coords.boundsInRoot())
                }
            }
            .background(globalColors.panelBackground.copy(alpha = 0.92f), toolbarShape)
            .border(1.dp, borderColor, toolbarShape)
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .testTag("ciacola-hud-toolbar"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Tooltip(tooltip = { Text("Toggle Inspect Mode (F3) — or hold Ctrl and click any slide element") }) {
            Row(
                modifier = Modifier
                    .clickable(onClick = onToggleInspectMode)
                    .padding(horizontal = 2.dp, vertical = 2.dp)
                    .testTag("ciacola-hud-toggle-inspect"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Icon(
                    key = AllIconsKeys.General.InspectionsEye,
                    contentDescription = "Inspect slide elements",
                    modifier = Modifier.size(14.dp),
                )
                val label = if (inspectPinned) "Inspecting (F3)" else "Ctrl+Click stage to comment"
                Text(
                    text = label,
                    style = JewelTheme.typography.small,
                    color = if (inspectActive) {
                        statusPalette.inspectAccent
                    } else {
                        globalColors.text.info
                    },
                )
            }
        }

        if (applyingCount > 0) {
            Tooltip(tooltip = { Text("Open Review panel (F4) — $applyingCount comment(s) applying") }) {
                Row(
                    modifier = Modifier
                        .clickable(onClick = onOpenReviewPanel)
                        .padding(horizontal = 3.dp, vertical = 2.dp)
                        .testTag("ciacola-hud-applying-badge"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Icon(
                        key = AllIconsKeys.Actions.Refresh,
                        contentDescription = "Applying comments",
                        modifier = Modifier.size(12.dp),
                    )
                    Text(
                        text = "$applyingCount applying…",
                        style = JewelTheme.typography.small,
                        color = statusPalette.applying,
                    )
                }
            }
        }

        if (onToggleTimeline != null) {
            Tooltip(tooltip = { Text("Toggle After Effects Timeline Studio (F5)") }) {
                Row(
                    modifier = Modifier
                        .clickable(onClick = onToggleTimeline)
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                        .testTag("ciacola-hud-toggle-timeline"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        key = AllIconsKeys.Actions.Preview,
                        contentDescription = "Toggle Timeline Studio",
                        modifier = Modifier.size(13.dp),
                    )
                    Text(
                        text = if (timelineDocked) "Timeline ●" else "Timeline",
                        style = JewelTheme.typography.small,
                        color = if (timelineDocked) statusPalette.inspectAccent else globalColors.text.info,
                    )
                }
            }
        }

        if (appliedCount > 0) {
            Tooltip(tooltip = { Text("Open Review panel (F4) — $appliedCount applied") }) {
                Row(
                    modifier = Modifier
                        .clickable(onClick = onOpenReviewPanel)
                        .padding(horizontal = 3.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Icon(
                        key = AllIconsKeys.General.GreenCheckmark,
                        contentDescription = "Applied comments",
                        modifier = Modifier.size(12.dp),
                    )
                    Text(
                        text = appliedCount.toString(),
                        style = JewelTheme.typography.small,
                        color = statusPalette.applied,
                    )
                }
            }
        }

        if (queuedCount > 0) {
            if (agentConfigured) {
                Tooltip(tooltip = { Text("Dispatch $queuedCount queued comment(s) to agent") }) {
                    DefaultButton(
                        onClick = onFlushQueued,
                        modifier = Modifier.testTag("ciacola-hud-send-queued"),
                    ) {
                        Text("Send $queuedCount", style = JewelTheme.typography.small)
                    }
                }
            } else {
                Tooltip(
                    tooltip = {
                        Text("No Conversation ID configured — open Review panel (F4) to connect an agent for $queuedCount queued comment(s)")
                    },
                ) {
                    OutlinedButton(
                        onClick = onOpenReviewPanel,
                        modifier = Modifier.testTag("ciacola-hud-send-queued"),
                    ) {
                        Text("Set Agent ($queuedCount)", style = JewelTheme.typography.small)
                    }
                }
            }
        }
    }
}

/**
 * Anchored Jewel [PopupContainer] for composing a new slide review comment at the clicked point.
 *
 * Uses its own focusable popup layer so keyboard typing and `Esc` dismissal never collide with
 * Storyboard's stage navigation or overview shortcuts.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun AnchoredCommentComposerPopup(
    pending: PendingReviewSelection,
    store: ReviewCommentStore,
    themePresetName: String,
    outerBoundsInRoot: Rect?,
    stageBoundsInRoot: Rect?,
    noteState: TextFieldState = rememberTextFieldState(),
    draftRestored: Boolean = false,
    restoredFromTarget: ReviewTargetHit? = null,
    onClearDraft: () -> Unit = {},
    onDismiss: (fromEscapeKey: Boolean) -> Unit,
    onSubmit: (SlideReviewComment, Boolean, ByteArray) -> Unit,
) {
    val anchorPx = stagePointToOuterOffsetPx(
        stageX = pending.stageX,
        stageY = pending.stageY,
        stageFormatSize = pending.stageFormatSize,
        outerBoundsInRoot = outerBoundsInRoot,
        stageBoundsInRoot = stageBoundsInRoot,
    )

    val convIdState = rememberTextFieldState(initialText = store.effectiveConversationId())
    var submitting by remember(pending) { mutableStateOf(false) }
    var validationError by remember(pending) { mutableStateOf<String?>(null) }
    val noteFocusRequester = remember(pending) { FocusRequester() }
    val convIdFocusRequester = remember(pending) { FocusRequester() }
    var noteFocused by remember(pending) { mutableStateOf(false) }
    var initialFocusDone by remember(pending) { mutableStateOf(false) }

    LaunchedEffect(pending) {
        var attempts = 0
        while (!noteFocused && !initialFocusDone && attempts < 12) {
            val requested = runCatching { noteFocusRequester.requestFocus() }.getOrDefault(false)
            if (requested && noteFocused) {
                initialFocusDone = true
                break
            }
            withFrameNanos {}
            attempts++
        }
        initialFocusDone = true
    }

    fun submit(dispatchImmediate: Boolean) {
        val trimmed = noteState.text.toString().trim()
        if (trimmed.isEmpty()) {
            validationError = "Enter feedback before sending."
            return
        }
        val enteredConv = convIdState.text.toString().trim()
        val effectiveConv = enteredConv.ifEmpty { store.effectiveConversationId() }
        if (dispatchImmediate && effectiveConv.isBlank()) {
            validationError = "Enter a Target Agent Conversation ID to send now, or click Queue to save for later."
            runCatching { convIdFocusRequester.requestFocus() }
            return
        }
        if (submitting) return
        submitting = true
        validationError = null

        if (enteredConv.isNotEmpty() && enteredConv != store.config.conversationId) {
            store.saveConfig(store.config.copy(conversationId = enteredConv))
        }

        val comment = SlideReviewComment(
            id = generateReviewCommentId(),
            sceneIndex = pending.sceneIndex,
            frameIndex = pending.frameIndex,
            sceneCount = pending.sceneCount,
            frameCount = pending.frameCount,
            sceneTitle = pending.sceneTitle,
            sceneHeader = pending.sceneHeader,
            themePreset = themePresetName,
            stageX = pending.stageX,
            stageY = pending.stageY,
            selectionBounds = pending.selectionBounds,
            target = pending.target,
            note = trimmed,
            createdAt = Instant.now().toString(),
        )
        val pngBytes = encodeAnnotatedStagePng(
            stageBitmap = pending.capturedBitmap,
            stageWidth = pending.stageFormatSize.width.toFloat(),
            stageHeight = pending.stageFormatSize.height.toFloat(),
            stageX = pending.stageX,
            stageY = pending.stageY,
            highlightRect = pending.selectionBounds,
        )
        onSubmit(comment, dispatchImmediate, pngBytes)
    }

    fun handleComposerKeyEvent(ev: KeyEvent): Boolean {
        if (ev.key == Key.Escape) {
            if (ev.type == KeyEventType.KeyDown) {
                onDismiss(true)
            }
            return true
        }
        if (ev.type == KeyEventType.KeyDown &&
            (ev.key == Key.Enter || ev.key == Key.NumPadEnter) &&
            (ev.isMetaPressed || ev.isCtrlPressed)
        ) {
            submit(dispatchImmediate = !ev.isShiftPressed)
            return true
        }
        return false
    }

    Box(
        modifier = Modifier.offset {
            IntOffset(anchorPx.x.roundToInt(), anchorPx.y.roundToInt())
        },
    ) {
        PopupContainer(
            onDismissRequest = { onDismiss(false) },
            horizontalAlignment = Alignment.Start,
            popupProperties = PopupProperties(
                focusable = true,
                dismissOnBackPress = false,
                dismissOnClickOutside = true,
            ),
            modifier = Modifier
                .width(336.dp)
                .testTag("ciacola-review-composer-popup")
                .onPreviewKeyEvent(::handleComposerKeyEvent),
        ) {
            val globalColors = JewelTheme.globalColors
            val statusPalette = rememberReviewStatusPalette()

            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ComposerPopupHeader(
                    sceneNumber = pending.sceneIndex + 1,
                    frameNumber = pending.frameIndex + 1,
                    sceneTitle = pending.sceneTitle,
                    targetKind = pending.target.kind,
                    stageX = pending.stageX.toInt(),
                    stageY = pending.stageY.toInt(),
                    accentColor = statusPalette.inspectAccent,
                    onClose = { onDismiss(false) },
                )

                val quotePreview = pending.target.clickedToken.ifBlank {
                    pending.target.textSnippet.ifBlank { pending.target.label }
                }
                if (quotePreview.isNotBlank()) {
                    QuotedTargetPreview(quotePreview)
                }

                if (draftRestored && noteState.text.isNotBlank()) {
                    val hintLabel = if (
                        restoredFromTarget != null &&
                        restoredFromTarget.label.isNotBlank() &&
                        restoredFromTarget.label != pending.target.label
                    ) {
                        "Draft restored · from ${restoredFromTarget.label}"
                    } else {
                        "Draft restored"
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = hintLabel,
                            style = JewelTheme.typography.small,
                            color = globalColors.text.info,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 8.dp)
                                .testTag("ciacola-review-draft-restored-hint"),
                        )
                        OutlinedButton(
                            onClick = {
                                onClearDraft()
                                runCatching { noteFocusRequester.requestFocus() }
                            },
                            modifier = Modifier.testTag("ciacola-review-clear-draft-button"),
                        ) {
                            Text("Clear", style = JewelTheme.typography.small)
                        }
                    }
                }

                TextArea(
                    state = noteState,
                    placeholder = {
                        Text(
                            text = "Describe what to change (⌘↵ sends & hot-reloads)…",
                            style = JewelTheme.typography.regular,
                        )
                    },
                    lineLimits = TextFieldLineLimits.MultiLine(
                        minHeightInLines = 3,
                        maxHeightInLines = 6,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .onPlaced {
                            if (!initialFocusDone && !noteFocused) {
                                runCatching { noteFocusRequester.requestFocus() }
                            }
                        }
                        .onFocusChanged { state ->
                            if (state.isFocused) {
                                noteFocused = true
                                initialFocusDone = true
                            }
                        }
                        .focusRequester(noteFocusRequester)
                        .testTag("ciacola-review-note-input"),
                )

                if (validationError != null) {
                    Text(
                        text = validationError.orEmpty(),
                        style = JewelTheme.typography.small,
                        color = globalColors.text.error,
                    )
                }

                if (store.effectiveConversationId().isBlank()) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextField(
                            state = convIdState,
                            placeholder = { Text("Target Agent Conversation ID") },
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(convIdFocusRequester)
                                .testTag("ciacola-review-conv-id-input"),
                        )
                        InfoText("Saved to .storyboard/review-config.json for live agentapi push.")
                    }
                }

                Divider(Orientation.Horizontal, Modifier.fillMaxWidth())

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(onClick = { onDismiss(false) }) {
                        Text("Cancel")
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        val hasNote = noteState.text.isNotBlank()
                        OutlinedButton(
                            enabled = hasNote && !submitting,
                            onClick = { submit(dispatchImmediate = false) },
                            modifier = Modifier.testTag("ciacola-review-queue-button"),
                        ) {
                            Text("Queue")
                        }
                        DefaultButton(
                            enabled = hasNote && !submitting,
                            onClick = { submit(dispatchImmediate = true) },
                            modifier = Modifier.testTag("ciacola-review-send-button"),
                        ) {
                            Text("Send")
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ComposerPopupHeader(
    sceneNumber: Int,
    frameNumber: Int,
    sceneTitle: String,
    targetKind: String,
    stageX: Int,
    stageY: Int,
    accentColor: androidx.compose.ui.graphics.Color,
    onClose: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val slideLabel = if (sceneTitle.isNotBlank()) {
                "Scene $sceneNumber ($sceneTitle) · Frame $frameNumber"
            } else {
                "Scene $sceneNumber · Frame $frameNumber"
            }
            Text(
                text = slideLabel,
                style = JewelTheme.typography.medium.copy(fontWeight = FontWeight.SemiBold),
                color = accentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "$targetKind ($stageX, $stageY)",
                style = JewelTheme.typography.small,
                color = JewelTheme.globalColors.text.info,
                maxLines = 1,
            )
        }
        Tooltip(tooltip = { Text("Cancel comment (Esc)") }) {
            IconButton(onClick = onClose, modifier = Modifier.size(20.dp)) {
                Icon(
                    key = AllIconsKeys.General.Close,
                    contentDescription = "Cancel comment",
                    modifier = Modifier.size(12.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun QuotedTargetPreview(quote: String) {
    val globalColors = JewelTheme.globalColors
    val shape = RoundedCornerShape(4.dp)
    Tooltip(tooltip = { Text(quote) }) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, globalColors.borders.normal, shape)
                .padding(horizontal = 8.dp, vertical = 5.dp),
        ) {
            Text(
                text = "“$quote”",
                style = JewelTheme.typography.small,
                color = globalColors.text.info,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Anchored Jewel [PopupContainer] displaying an existing pin's comment, status, and agent resolution.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun AnchoredSelectedPinPopup(
    comment: SlideReviewComment,
    displayNumber: Int = 1,
    outerBoundsInRoot: Rect?,
    stageBoundsInRoot: Rect?,
    stageFormatSize: IntSize,
    agentConfigured: Boolean = true,
    onClose: (fromEscapeKey: Boolean) -> Unit,
    onDelete: () -> Unit,
    onMarkApplied: () -> Unit = {},
    onRetry: () -> Unit,
    onConfigureAgent: () -> Unit = {},
) {
    val anchorPx = stagePointToOuterOffsetPx(
        stageX = comment.stageX,
        stageY = comment.stageY,
        stageFormatSize = stageFormatSize,
        outerBoundsInRoot = outerBoundsInRoot,
        stageBoundsInRoot = stageBoundsInRoot,
    )
    val statusPalette: ReviewStatusPalette = rememberReviewStatusPalette()
    val globalColors = JewelTheme.globalColors
    val pinPopupFocusRequester = remember(comment.id) { FocusRequester() }
    var confirmDiscard by remember(comment.id) { mutableStateOf(false) }

    LaunchedEffect(comment.id) {
        withFrameNanos {}
        runCatching { pinPopupFocusRequester.requestFocus() }
    }

    Box(
        modifier = Modifier.offset {
            IntOffset(anchorPx.x.roundToInt(), anchorPx.y.roundToInt())
        },
    ) {
        PopupContainer(
            onDismissRequest = { onClose(false) },
            horizontalAlignment = Alignment.Start,
            popupProperties = PopupProperties(
                focusable = true,
                dismissOnBackPress = false,
                dismissOnClickOutside = true,
            ),
            modifier = Modifier
                .width(336.dp)
                .testTag("ciacola-review-pin-popup")
                .focusRequester(pinPopupFocusRequester)
                .focusable()
                .onPreviewKeyEvent { ev ->
                    if (ev.key == Key.Escape) {
                        if (ev.type == KeyEventType.KeyDown) onClose(true)
                        true
                    } else {
                        false
                    }
                },
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            key = comment.status.iconKey(),
                            contentDescription = comment.status.labelText(),
                            modifier = Modifier.size(14.dp),
                        )
                        Text(
                            text = "#$displayNumber · ${comment.status.labelText()}",
                            style = JewelTheme.typography.medium.copy(fontWeight = FontWeight.SemiBold),
                            color = statusPalette.colorFor(comment.status),
                        )
                    }
                    Tooltip(tooltip = { Text("Close (Esc)") }) {
                        IconButton(onClick = { onClose(false) }, modifier = Modifier.size(20.dp)) {
                            Icon(
                                key = AllIconsKeys.General.Close,
                                contentDescription = "Close",
                                modifier = Modifier.size(12.dp),
                            )
                        }
                    }
                }

                Text(
                    text = comment.note,
                    style = JewelTheme.typography.regular,
                    color = globalColors.text.normal,
                )

                if (comment.resolution.isNotBlank()) {
                    val resShape = RoundedCornerShape(4.dp)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, statusPalette.applied, resShape)
                            .padding(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Icon(
                            key = AllIconsKeys.General.GreenCheckmark,
                            contentDescription = "Resolution",
                            modifier = Modifier.size(14.dp),
                        )
                        Text(
                            text = comment.resolution,
                            style = JewelTheme.typography.small,
                            color = globalColors.text.normal,
                        )
                    }
                }

                if (!comment.deliveryError.isNullOrBlank()) {
                    Text(
                        text = comment.deliveryError,
                        style = JewelTheme.typography.small,
                        color = globalColors.text.error,
                    )
                }

                Divider(Orientation.Horizontal, Modifier.fillMaxWidth())

                if (confirmDiscard) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, statusPalette.queued, RoundedCornerShape(4.dp))
                            .padding(8.dp)
                            .testTag("ciacola-review-pin-discard-confirm"),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = "This comment has not been applied yet. Discard it?",
                            style = JewelTheme.typography.small,
                            color = globalColors.text.normal,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                OutlinedButton(onClick = { confirmDiscard = false }) {
                                    Text("Keep")
                                }
                                DefaultButton(
                                    onClick = onDelete,
                                    modifier = Modifier.testTag("ciacola-review-pin-confirm-discard"),
                                ) {
                                    Text("Discard")
                                }
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
                            Tooltip(
                                tooltip = {
                                    Text(
                                        if (comment.status == ReviewCommentStatus.Applied) {
                                            "Resolve and dismiss this comment"
                                        } else {
                                            "Discard this unapplied comment (confirmation required)"
                                        },
                                    )
                                },
                            ) {
                                OutlinedButton(
                                    onClick = {
                                        if (comment.status == ReviewCommentStatus.Applied) {
                                            onDelete()
                                        } else {
                                            confirmDiscard = true
                                        }
                                    },
                                    modifier = Modifier.testTag("ciacola-review-pin-resolve"),
                                ) {
                                    Text("Resolve")
                                }
                            }
                            if (comment.status != ReviewCommentStatus.Applied) {
                                Tooltip(tooltip = { Text("Mark this comment as applied") }) {
                                    OutlinedButton(
                                        onClick = onMarkApplied,
                                        modifier = Modifier.testTag("ciacola-review-pin-mark-applied"),
                                    ) {
                                        Text("Mark Applied")
                                    }
                                }
                                if (agentConfigured) {
                                    val isQueued = comment.status == ReviewCommentStatus.Queued
                                    val label = if (isQueued) "Send Now" else "Retry Send"
                                    val tip = if (isQueued) {
                                        "Send this queued comment to agent"
                                    } else {
                                        "Retry dispatching this comment to agent"
                                    }
                                    Tooltip(tooltip = { Text(tip) }) {
                                        DefaultButton(
                                            onClick = onRetry,
                                            modifier = Modifier.testTag("ciacola-review-pin-retry"),
                                        ) {
                                            Text(label)
                                        }
                                    }
                                } else {
                                    Tooltip(tooltip = { Text("Configure Target Agent Conversation ID") }) {
                                        OutlinedButton(onClick = onConfigureAgent) {
                                            Text("Set Agent…")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

internal fun stagePointToOuterOffsetPx(
    stageX: Float,
    stageY: Float,
    stageFormatSize: IntSize,
    outerBoundsInRoot: Rect?,
    stageBoundsInRoot: Rect?,
): Offset {
    if (outerBoundsInRoot == null || stageBoundsInRoot == null ||
        stageBoundsInRoot.width <= 0f || stageBoundsInRoot.height <= 0f
    ) {
        return Offset(48f, 48f)
    }
    val normX = stageX / stageFormatSize.width.toFloat().coerceAtLeast(1f)
    val normY = stageY / stageFormatSize.height.toFloat().coerceAtLeast(1f)
    return Offset(
        x = (stageBoundsInRoot.left - outerBoundsInRoot.left) + normX * stageBoundsInRoot.width,
        y = (stageBoundsInRoot.top - outerBoundsInRoot.top) + normY * stageBoundsInRoot.height,
    )
}
