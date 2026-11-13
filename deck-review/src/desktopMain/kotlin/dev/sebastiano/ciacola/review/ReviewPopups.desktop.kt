package dev.sebastiano.ciacola.review

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
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
import org.jetbrains.jewel.ui.Outline
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
    val density = LocalDensity.current
    val gapPx = with(density) { 12.dp.toPx() }
    val marginPx = with(density) { 8.dp.toPx() }
    val defaultPopupSize = with(density) { Size(336.dp.toPx(), 290.dp.toPx()) }
    var popupSizePx by remember(pending) { mutableStateOf(defaultPopupSize) }

    val clickPointPx = stagePointToOuterOffsetPx(
        stageX = pending.stageX,
        stageY = pending.stageY,
        stageFormatSize = pending.stageFormatSize,
        outerBoundsInRoot = outerBoundsInRoot,
        stageBoundsInRoot = stageBoundsInRoot,
    )
    val targetRectInOuter = stageRectToOuterRect(
        stageRect = pending.selectionBounds ?: pending.target.bounds,
        fallbackClickPx = clickPointPx,
        stageFormatSize = pending.stageFormatSize,
        outerBoundsInRoot = outerBoundsInRoot,
        stageBoundsInRoot = stageBoundsInRoot,
    )
    val anchorPx = if (outerBoundsInRoot != null && stageBoundsInRoot != null) {
        computePopupOffsetOutsideTarget(
            targetRectInOuter = targetRectInOuter,
            clickPointInOuter = clickPointPx,
            popupSizePx = popupSizePx,
            windowSizePx = Size(outerBoundsInRoot.width, outerBoundsInRoot.height),
            gapPx = gapPx,
            marginPx = marginPx,
        )
    } else {
        clickPointPx
    }

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
                .onSizeChanged { sz ->
                    if (sz.width > 0 && sz.height > 0) {
                        popupSizePx = Size(sz.width.toFloat(), sz.height.toFloat())
                    }
                }
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
                    outline = if (validationError != null && noteState.text.isBlank()) Outline.Error else Outline.None,
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
                            outline = if (validationError != null && noteState.text.isNotBlank()) Outline.Error else Outline.None,
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
    val density = LocalDensity.current
    val gapPx = with(density) { 12.dp.toPx() }
    val marginPx = with(density) { 8.dp.toPx() }
    val pinRadiusPx = with(density) { 12.dp.toPx() }
    val defaultPopupSize = with(density) { Size(336.dp.toPx(), 220.dp.toPx()) }
    var popupSizePx by remember(comment.id) { mutableStateOf(defaultPopupSize) }
    val pinCenterStage = computeStagePinCenter(
        comment = comment,
        stageFormatSize = stageFormatSize,
        pinRadiusPx = pinRadiusPx,
    )
    val pinCenterOuter = stagePointToOuterOffsetPx(
        stageX = pinCenterStage.x,
        stageY = pinCenterStage.y,
        stageFormatSize = stageFormatSize,
        outerBoundsInRoot = outerBoundsInRoot,
        stageBoundsInRoot = stageBoundsInRoot,
    )
    val anchorPx = if (outerBoundsInRoot != null && stageBoundsInRoot != null) {
        val pinBox = Rect(
            left = pinCenterOuter.x - pinRadiusPx,
            top = pinCenterOuter.y - pinRadiusPx,
            right = pinCenterOuter.x + pinRadiusPx,
            bottom = pinCenterOuter.y + pinRadiusPx,
        )
        computePopupOffsetOutsideTarget(
            targetRectInOuter = pinBox,
            clickPointInOuter = pinCenterOuter,
            popupSizePx = popupSizePx,
            windowSizePx = Size(outerBoundsInRoot.width, outerBoundsInRoot.height),
            gapPx = gapPx,
            marginPx = marginPx,
        )
    } else {
        pinCenterOuter
    }
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
                .onSizeChanged { sz ->
                    if (sz.width > 0 && sz.height > 0) {
                        popupSizePx = Size(sz.width.toFloat(), sz.height.toFloat())
                    }
                }
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

                val deliveryError = comment.deliveryError
                if (!deliveryError.isNullOrBlank()) {
                    Text(
                        text = deliveryError,
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

internal fun stageRectToOuterRect(
    stageRect: StageRectDto?,
    fallbackClickPx: Offset,
    stageFormatSize: IntSize,
    outerBoundsInRoot: Rect?,
    stageBoundsInRoot: Rect?,
): Rect {
    if (stageRect == null || outerBoundsInRoot == null || stageBoundsInRoot == null ||
        stageBoundsInRoot.width <= 0f || stageBoundsInRoot.height <= 0f
    ) {
        return Rect(
            left = fallbackClickPx.x - 16f,
            top = fallbackClickPx.y - 16f,
            right = fallbackClickPx.x + 16f,
            bottom = fallbackClickPx.y + 16f,
        )
    }
    val topLeft = stagePointToOuterOffsetPx(stageRect.left, stageRect.top, stageFormatSize, outerBoundsInRoot, stageBoundsInRoot)
    val bottomRight = stagePointToOuterOffsetPx(stageRect.right, stageRect.bottom, stageFormatSize, outerBoundsInRoot, stageBoundsInRoot)
    return Rect(
        left = minOf(topLeft.x, bottomRight.x),
        top = minOf(topLeft.y, bottomRight.y),
        right = maxOf(topLeft.x, bottomRight.x),
        bottom = maxOf(topLeft.y, bottomRight.y),
    )
}

/**
 * Places a review popup outside [targetRectInOuter] so it never covers the element being reviewed:
 * prefers below (`gapPx` clearance), then above, then whichever horizontal side has more room,
 * always clamped inside `[marginPx .. windowSizePx - popupSizePx - marginPx]`.
 */
fun computePopupOffsetOutsideTarget(
    targetRectInOuter: Rect,
    clickPointInOuter: Offset,
    popupSizePx: Size,
    windowSizePx: Size,
    gapPx: Float,
    marginPx: Float,
): Offset {
    val maxX = (windowSizePx.width - popupSizePx.width - marginPx).coerceAtLeast(marginPx)
    val maxY = (windowSizePx.height - popupSizePx.height - marginPx).coerceAtLeast(marginPx)
    val preferredX = (targetRectInOuter.left)
        .takeIf { targetRectInOuter.width <= popupSizePx.width * 1.4f }
        ?: (clickPointInOuter.x - popupSizePx.width * 0.25f)
    val clampedX = preferredX.coerceIn(marginPx, maxX)

    val belowY = targetRectInOuter.bottom + gapPx
    if (belowY <= maxY) {
        return Offset(clampedX, belowY.coerceIn(marginPx, maxY))
    }

    val aboveY = targetRectInOuter.top - gapPx - popupSizePx.height
    if (aboveY >= marginPx) {
        return Offset(clampedX, aboveY.coerceIn(marginPx, maxY))
    }

    val rightX = targetRectInOuter.right + gapPx
    val leftX = targetRectInOuter.left - gapPx - popupSizePx.width
    val rightRoom = (windowSizePx.width - marginPx) - rightX
    val leftRoom = (targetRectInOuter.left - gapPx) - marginPx
    val sideY = targetRectInOuter.top.coerceIn(marginPx, maxY)
    if (rightRoom >= popupSizePx.width || leftRoom >= popupSizePx.width) {
        val sideX = if (rightRoom >= leftRoom) rightX.coerceIn(marginPx, maxX) else leftX.coerceIn(marginPx, maxX)
        return Offset(sideX, sideY)
    }

    // Target fills almost the whole window: pick whichever vertical edge leaves more clearance from the click.
    val belowClickY = (clickPointInOuter.y + gapPx).coerceIn(marginPx, maxY)
    val aboveClickY = (clickPointInOuter.y - gapPx - popupSizePx.height).coerceIn(marginPx, maxY)
    val finalY = if (windowSizePx.height - clickPointInOuter.y >= clickPointInOuter.y) belowClickY else aboveClickY
    return Offset(clampedX, finalY)
}
