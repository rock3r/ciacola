package dev.sebastiano.ciacola.review

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.Divider
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedButton
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.KeyShortcut
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.MenuScope
import dev.bnorm.storyboard.ContentDecorator
import dev.bnorm.storyboard.Storyboard
import dev.bnorm.storyboard.easel.Animatic
import dev.bnorm.storyboard.easel.EaselWindow
import dev.bnorm.storyboard.easel.LocalSceneMode
import dev.bnorm.storyboard.easel.LocalStoryboard
import dev.bnorm.storyboard.easel.SceneMode
import dev.sebastiano.ciacola.theme.DeckThemePreset
import java.awt.Window as AwtWindow
import java.time.Instant
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

data class PendingReviewSelection(
    val stageX: Float,
    val stageY: Float,
    val selectionBounds: StageRectDto?,
    val target: ReviewTargetHit,
    val sceneHeader: String,
    val stageFormatSize: IntSize,
    val capturedBitmap: ImageBitmap?,
)

private data class RegisteredEntry(
    val kind: String,
    val label: String,
    val textSnippet: String,
    val isContainer: Boolean,
    val coordinates: LayoutCoordinates,
)

class ReviewStageCoordinator : ReviewStageBridge {
    internal var stageRootCoordinates: LayoutCoordinates? = null
    internal var stageBoundsInRoot by mutableStateOf<Rect?>(null)
    internal var stageFormatSize by mutableStateOf(IntSize(1920, 1080))
    internal var activeGraphicsLayer: GraphicsLayer? = null

    var currentSceneHeader by mutableStateOf("")
        private set

    var inspectModeEnabled by mutableStateOf(false)
    var showStageBadges by mutableStateOf(true)

    internal var isCtrlKeyHeld by mutableStateOf(false)
    internal var hoverStagePoint by mutableStateOf<Offset?>(null)
    internal var dragStartStagePoint by mutableStateOf<Offset?>(null)
    internal var dragCurrentStagePoint by mutableStateOf<Offset?>(null)

    var pendingSelection by mutableStateOf<PendingReviewSelection?>(null)
    var selectedPinCommentId by mutableStateOf<String?>(null)

    internal var currentSlideCommentsProvider: () -> List<SlideReviewComment> = { emptyList() }

    private val entries = mutableStateMapOf<Any, RegisteredEntry>()

    override fun updateSceneHeader(header: String) {
        currentSceneHeader = header
    }

    override fun registerTarget(
        key: Any,
        kind: String,
        label: String,
        textSnippet: String,
        isContainer: Boolean,
        coordinates: LayoutCoordinates,
    ) {
        entries[key] = RegisteredEntry(
            kind = kind,
            label = label,
            textSnippet = textSnippet,
            isContainer = isContainer,
            coordinates = coordinates,
        )
    }

    override fun unregisterTarget(key: Any) {
        entries.remove(key)
    }

    internal fun snapshotRegisteredTargets(): List<RegisteredTargetSnapshot> {
        val root = stageRootCoordinates?.takeIf { it.isAttached } ?: return emptyList()
        return entries.mapNotNull { (key, entry) ->
            val coords = entry.coordinates.takeIf { it.isAttached } ?: return@mapNotNull null
            val rect = runCatching { root.localBoundingBoxOf(coords, clipBounds = false) }.getOrNull()
                ?: return@mapNotNull null
            if (rect.width <= 0f || rect.height <= 0f) return@mapNotNull null
            RegisteredTargetSnapshot(
                id = key,
                kind = entry.kind,
                label = entry.label,
                textSnippet = entry.textSnippet,
                isContainer = entry.isContainer,
                stageBounds = rect,
            )
        }
    }

    internal fun collectDesktopSemanticsHits(): List<ReviewTargetHit> {
        val rootBox = stageBoundsInRoot ?: return emptyList()
        if (rootBox.width <= 0f || rootBox.height <= 0f) return emptyList()
        val formatW = stageFormatSize.width.toFloat().coerceAtLeast(1f)
        val formatH = stageFormatSize.height.toFloat().coerceAtLeast(1f)

        val composeWindows = runCatching {
            AwtWindow.getWindows().filterIsInstance<ComposeWindow>().filter { it.isShowing }
        }.getOrDefault(emptyList())
        if (composeWindows.isEmpty()) return emptyList()

        val storyWin = composeWindows.firstOrNull { it.title.endsWith("- Story") } ?: composeWindows.first()
        val hits = mutableListOf<ReviewTargetHit>()

        fun walk(node: SemanticsNode) {
            val tag = node.config.getOrNull(SemanticsProperties.TestTag).orEmpty()
            if (tag.startsWith("ciacola-review-")) return

            val textList = node.config.getOrNull(SemanticsProperties.Text)
            val joinedText = textList?.joinToString("") { it.text }?.trim().orEmpty()
            val desc = node.config.getOrNull(SemanticsProperties.ContentDescription)
                ?.joinToString(" ")
                ?.trim()
                .orEmpty()
            val snippet = joinedText.ifBlank { desc }
            if (snippet.isNotBlank()) {
                val b = node.boundsInRoot
                if (b.overlaps(rootBox) && b.width > 1f && b.height > 1f) {
                    val stageRect = StageRectDto(
                        left = ((b.left - rootBox.left) / rootBox.width) * formatW,
                        top = ((b.top - rootBox.top) / rootBox.height) * formatH,
                        right = ((b.right - rootBox.left) / rootBox.width) * formatW,
                        bottom = ((b.bottom - rootBox.top) / rootBox.height) * formatH,
                    )
                    hits += ReviewTargetHit(
                        kind = "Text",
                        label = snippet.take(48),
                        textSnippet = snippet,
                        bounds = stageRect,
                    )
                }
            }
            node.children.forEach(::walk)
        }

        runCatching {
            @Suppress("UNCHECKED_CAST")
            val owners = ComposeWindow::class.java
                .getMethod("getSemanticsOwners")
                .invoke(storyWin) as? Collection<SemanticsOwner>
            owners?.forEach { owner ->
                walk(owner.unmergedRootSemanticsNode)
            }
        }
        return hits
    }

    internal fun resolveHit(stagePoint: Offset, dragRect: Rect?): ReviewTargetHit {
        return resolveBestReviewHit(
            stagePoint = stagePoint,
            dragRect = dragRect,
            registeredTargets = snapshotRegisteredTargets(),
            semanticsHits = collectDesktopSemanticsHits(),
        )
    }

    val stageDecorator: ContentDecorator = ContentDecorator { content ->
        if (LocalSceneMode.current != SceneMode.Story) {
            content()
            return@ContentDecorator
        }
        ReviewStageLayer(coordinator = this, content = content)
    }
}

@Composable
fun rememberReviewStageCoordinator(): ReviewStageCoordinator = remember {
    ReviewStageCoordinator()
}

@Composable
private fun ReviewStageLayer(
    coordinator: ReviewStageCoordinator,
    content: @Composable () -> Unit,
) {
    val storyboard: Storyboard? = LocalStoryboard.current
    val formatSize = storyboard?.format?.size ?: IntSize(1920, 1080)
    coordinator.stageFormatSize = formatSize

    val cleanStageGraphicsLayer = rememberGraphicsLayer()
    coordinator.activeGraphicsLayer = cleanStageGraphicsLayer

    val scope = rememberCoroutineScope()
    val isInspectActive = coordinator.isCtrlKeyHeld || coordinator.inspectModeEnabled

    Box(
        modifier = Modifier
            .fillMaxSize()
            .testTag("ciacola-review-stage")
            .focusTarget()
            .onPlaced { coords ->
                coordinator.stageRootCoordinates = coords
                if (coords.isAttached) {
                    coordinator.stageBoundsInRoot = runCatching { coords.boundsInRoot() }.getOrNull()
                }
            }
            .onPreviewKeyEvent { keyEvent ->
                if (keyEvent.key == Key.CtrlLeft || keyEvent.key == Key.CtrlRight) {
                    coordinator.isCtrlKeyHeld = keyEvent.type == KeyEventType.KeyDown
                }
                false
            }
            .let { base ->
                if (isInspectActive) base.pointerHoverIcon(PointerIcon.Crosshair) else base
            }
            .pointerInput(coordinator) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val ctrlNow = event.keyboardModifiers.isCtrlPressed ||
                            coordinator.isCtrlKeyHeld ||
                            coordinator.inspectModeEnabled

                        val pos = event.changes.firstOrNull()?.position
                        if (!ctrlNow && coordinator.dragStartStagePoint == null) {
                            if (coordinator.hoverStagePoint != null) {
                                coordinator.hoverStagePoint = null
                            }
                            continue
                        }

                        when (event.type) {
                            PointerEventType.Move -> {
                                if (pos != null) {
                                    coordinator.hoverStagePoint = pos
                                    if (coordinator.dragStartStagePoint != null) {
                                        coordinator.dragCurrentStagePoint = pos
                                        event.changes.forEach { it.consume() }
                                    }
                                }
                            }

                            PointerEventType.Exit -> {
                                if (coordinator.dragStartStagePoint == null) {
                                    coordinator.hoverStagePoint = null
                                }
                            }

                            PointerEventType.Press -> {
                                if (pos != null && ctrlNow) {
                                    event.changes.forEach { it.consume() }
                                    coordinator.selectedPinCommentId = null
                                    coordinator.dragStartStagePoint = pos
                                    coordinator.dragCurrentStagePoint = pos
                                    coordinator.hoverStagePoint = pos
                                }
                            }

                            PointerEventType.Release -> {
                                    val start = coordinator.dragStartStagePoint
                                if (start != null) {
                                    event.changes.forEach { it.consume() }
                                    val end = pos ?: coordinator.dragCurrentStagePoint ?: start
                                    coordinator.dragStartStagePoint = null
                                    coordinator.dragCurrentStagePoint = null
                                    coordinator.isCtrlKeyHeld = false

                                    val rawDrag = normalizeDragRect(start, end)
                                    val significantDrag = isSignificantDrag(rawDrag)
                                    val anchor = if (significantDrag) rawDrag.center else end
                                    val hit = coordinator.resolveHit(
                                        stagePoint = anchor,
                                        dragRect = rawDrag.takeIf { significantDrag },
                                    )
                                    val selectionBounds = if (significantDrag) {
                                        StageRectDto.fromRect(rawDrag)
                                    } else {
                                        hit.bounds
                                    }

                                    scope.launch {
                                        val bitmap = runCatching {
                                            cleanStageGraphicsLayer.toImageBitmap()
                                        }.getOrNull()
                                        coordinator.pendingSelection = PendingReviewSelection(
                                            stageX = anchor.x.coerceIn(0f, formatSize.width.toFloat()),
                                            stageY = anchor.y.coerceIn(0f, formatSize.height.toFloat()),
                                            selectionBounds = selectionBounds,
                                            target = hit,
                                            sceneHeader = coordinator.currentSceneHeader,
                                            stageFormatSize = formatSize,
                                            capturedBitmap = bitmap,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            },
    ) {
        // 1. Clean slide layer recorded for screenshot export (free of review chrome).
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawWithContent {
                    cleanStageGraphicsLayer.record {
                        this@drawWithContent.drawContent()
                    }
                    drawLayer(cleanStageGraphicsLayer)
                },
        ) {
            CompositionLocalProvider(LocalReviewStageBridge provides coordinator) {
                content()
            }
        }

        // 2. Live Ctrl-hover highlight, Ctrl-drag marquee, and active selection box in stage space.
        val hoveredHit = remember(coordinator.hoverStagePoint, isInspectActive) {
            val pt = coordinator.hoverStagePoint
            if (isInspectActive && pt != null && coordinator.dragStartStagePoint == null) {
                coordinator.resolveHit(pt, null)
            } else {
                null
            }
        }

        Canvas(Modifier.fillMaxSize()) {
            val dash = PathEffect.dashPathEffect(floatArrayOf(12f, 8f), 0f)
            val coral = Color(0xFFFF6B5E)
            val teal = Color(0xFF71D7C5)

            hoveredHit?.bounds?.toRect()?.let { rect ->
                drawRoundRect(
                    color = teal.copy(alpha = 0.12f),
                    topLeft = rect.topLeft,
                    size = rect.size,
                    cornerRadius = CornerRadius(10f, 10f),
                )
                drawRoundRect(
                    color = teal,
                    topLeft = rect.topLeft,
                    size = rect.size,
                    cornerRadius = CornerRadius(10f, 10f),
                    style = Stroke(width = 3f, pathEffect = dash),
                )
            }

            val dragStart = coordinator.dragStartStagePoint
            val dragCurrent = coordinator.dragCurrentStagePoint
            if (dragStart != null && dragCurrent != null) {
                val marquee = normalizeDragRect(dragStart, dragCurrent)
                if (isSignificantDrag(marquee)) {
                    drawRoundRect(
                        color = coral.copy(alpha = 0.14f),
                        topLeft = marquee.topLeft,
                        size = marquee.size,
                        cornerRadius = CornerRadius(10f, 10f),
                    )
                    drawRoundRect(
                        color = coral,
                        topLeft = marquee.topLeft,
                        size = marquee.size,
                        cornerRadius = CornerRadius(10f, 10f),
                        style = Stroke(width = 3.5f, pathEffect = dash),
                    )
                }
            }

            coordinator.pendingSelection?.selectionBounds?.toRect()?.let { active ->
                drawRoundRect(
                    color = coral.copy(alpha = 0.14f),
                    topLeft = active.topLeft,
                    size = active.size,
                    cornerRadius = CornerRadius(10f, 10f),
                )
                drawRoundRect(
                    color = coral,
                    topLeft = active.topLeft,
                    size = active.size,
                    cornerRadius = CornerRadius(10f, 10f),
                    style = Stroke(width = 3.5f),
                )
            }
        }

        // 3. Numbered pins on the current slide/frame (when enabled).
        if (coordinator.showStageBadges) {
            val slideComments = coordinator.currentSlideCommentsProvider()
            val density = LocalDensity.current
            slideComments.forEachIndexed { idx, comment ->
                val pinColor = comment.status.badgeColor()
                val pinSizeDp = 26.dp
                val pinHalfPx = with(density) { (pinSizeDp / 2).roundToPx() }
                Box(
                    modifier = Modifier
                        .offset {
                            IntOffset(
                                x = comment.stageX.roundToInt() - pinHalfPx,
                                y = comment.stageY.roundToInt() - pinHalfPx,
                            )
                        }
                        .size(pinSizeDp)
                        .clip(CircleShape)
                        .background(pinColor)
                        .border(2.dp, Color.White, CircleShape)
                        .pointerHoverIcon(PointerIcon.Hand)
                        .clickable {
                            coordinator.pendingSelection = null
                            coordinator.selectedPinCommentId =
                                if (coordinator.selectedPinCommentId == comment.id) null else comment.id
                        }
                        .testTag("ciacola-review-pin-${idx + 1}"),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = (idx + 1).toString(),
                        color = Color(0xFF0E1117),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.ExtraBold,
                    )
                }
            }
        }
    }
}

class SlideReviewWindow(
    val animatic: Animatic,
    val store: ReviewCommentStore,
    val stageCoordinator: ReviewStageCoordinator,
    val currentPresetProvider: () -> DeckThemePreset = { DeckThemePreset.StageDark },
) : EaselWindow {
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
            text = "Show Stage Pins & Pill",
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
        ReviewSidecarPanel(windowController = this)
    }
}

@Composable
fun rememberSlideReviewWindow(
    animatic: Animatic,
    store: ReviewCommentStore,
    stageCoordinator: ReviewStageCoordinator,
    currentPresetProvider: () -> DeckThemePreset = { DeckThemePreset.StageDark },
): SlideReviewWindow = remember(animatic, store, stageCoordinator) {
    SlideReviewWindow(animatic, store, stageCoordinator, currentPresetProvider)
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

    // Poll review-comments.json every 250ms so agent status/resolution updates appear live.
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
                false
            }
            .onGloballyPositioned { coords ->
                if (coords.isAttached) {
                    outerBoundsInRoot = runCatching { coords.boundsInRoot() }.getOrNull()
                }
            },
    ) {
        content()

        // Top-right compact review status pill
        if (coordinator.showStageBadges) {
            val queuedCount = store.comments.count {
                it.status == ReviewCommentStatus.Queued || it.status == ReviewCommentStatus.Failed
            }
            val applyingCount = store.comments.count { it.status == ReviewCommentStatus.Applying }
            val appliedCount = store.comments.count { it.status == ReviewCommentStatus.Applied }

            Surface(
                color = Color(0xEB151922),
                contentColor = Color(0xFFF4F1EA),
                shape = RoundedCornerShape(999.dp),
                elevation = 6.dp,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(14.dp)
                    .border(
                        width = 1.dp,
                        color = if (coordinator.inspectModeEnabled || coordinator.isCtrlKeyHeld) {
                            Color(0xFF71D7C5)
                        } else {
                            Color(0xFF2E3648)
                        },
                        shape = RoundedCornerShape(999.dp),
                    ),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val statusHint = when {
                        coordinator.inspectModeEnabled -> "● Inspecting (F3)"
                        applyingCount > 0 -> "● $applyingCount applying…"
                        else -> "Ctrl+Click to comment"
                    }
                    Text(
                        text = statusHint,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (coordinator.inspectModeEnabled || applyingCount > 0) {
                            Color(0xFF71D7C5)
                        } else {
                            Color(0xFFD5DAE5)
                        },
                        modifier = Modifier
                            .pointerHoverIcon(PointerIcon.Hand)
                            .clickable {
                                coordinator.inspectModeEnabled = !coordinator.inspectModeEnabled
                            },
                    )
                    if (appliedCount > 0) {
                        Text(
                            text = "$appliedCount ✓",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF66D19E),
                            modifier = Modifier
                                .pointerHoverIcon(PointerIcon.Hand)
                                .clickable { windowController.visible = true },
                        )
                    }
                    if (queuedCount > 0) {
                        Surface(
                            color = Color(0xFFFF6B5E),
                            shape = RoundedCornerShape(999.dp),
                            modifier = Modifier
                                .pointerHoverIcon(PointerIcon.Hand)
                                .clickable { scope.launch { store.flushQueued() } },
                        ) {
                            Text(
                                text = "Send $queuedCount",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF0B0D12),
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
            }
        }

        // Floating Comment Composer Card (Claude Artifacts style)
        val pending = coordinator.pendingSelection
        if (pending != null) {
            FloatingCommentComposerCard(
                pending = pending,
                windowController = windowController,
                outerBoundsInRoot = outerBoundsInRoot,
                stageBoundsInRoot = coordinator.stageBoundsInRoot,
                onDismiss = { coordinator.pendingSelection = null },
                onSubmit = { comment, dispatchImmediate, pngBytes ->
                    coordinator.pendingSelection = null
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

        // Selected Existing Pin Popover
        val activePinId = coordinator.selectedPinCommentId
        val activePinComment = currentSlideComments.firstOrNull { it.id == activePinId }
        if (activePinComment != null && pending == null) {
            SelectedPinPopoverCard(
                comment = activePinComment,
                outerBoundsInRoot = outerBoundsInRoot,
                stageBoundsInRoot = coordinator.stageBoundsInRoot,
                stageFormatSize = coordinator.stageFormatSize,
                onClose = { coordinator.selectedPinCommentId = null },
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

private fun stagePointToOuterOffsetPx(
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

@Composable
private fun FloatingCommentComposerCard(
    pending: PendingReviewSelection,
    windowController: SlideReviewWindow,
    outerBoundsInRoot: Rect?,
    stageBoundsInRoot: Rect?,
    onDismiss: () -> Unit,
    onSubmit: (SlideReviewComment, Boolean, ByteArray) -> Unit,
) {
    val store = windowController.store
    val animatic = windowController.animatic
    val density = LocalDensity.current

    var noteText by remember(pending) { mutableStateOf("") }
    var inlineConvId by remember(pending) { mutableStateOf(store.effectiveConversationId()) }
    var submitting by remember(pending) { mutableStateOf(false) }
    val focusRequester = remember(pending) { FocusRequester() }

    LaunchedEffect(pending) {
        runCatching { focusRequester.requestFocus() }
    }

    val cardWidthDp = 360.dp
    val cardHeightEstDp = 250.dp
    val cardWidthPx = with(density) { cardWidthDp.roundToPx() }
    val cardHeightPx = with(density) { cardHeightEstDp.roundToPx() }
    val marginPx = with(density) { 16.dp.roundToPx() }

    val pinOuter = stagePointToOuterOffsetPx(
        stageX = pending.stageX,
        stageY = pending.stageY,
        stageFormatSize = pending.stageFormatSize,
        outerBoundsInRoot = outerBoundsInRoot,
        stageBoundsInRoot = stageBoundsInRoot,
    )
    val containerW = (outerBoundsInRoot?.width?.roundToInt() ?: 960).coerceAtLeast(cardWidthPx + marginPx * 2)
    val containerH = (outerBoundsInRoot?.height?.roundToInt() ?: 540).coerceAtLeast(cardHeightPx + marginPx * 2)

    val placeRightX = pinOuter.x.roundToInt() + marginPx
    val chosenX = if (placeRightX + cardWidthPx <= containerW - marginPx) {
        placeRightX
    } else {
        (pinOuter.x.roundToInt() - cardWidthPx - marginPx).coerceIn(marginPx, containerW - cardWidthPx - marginPx)
    }
    val chosenY = (pinOuter.y.roundToInt() - marginPx).coerceIn(marginPx, containerH - cardHeightPx - marginPx)

    fun submit(dispatchImmediate: Boolean) {
        val trimmed = noteText.trim()
        if (trimmed.isEmpty() || submitting) return
        submitting = true
        if (inlineConvId.trim().isNotEmpty() && inlineConvId.trim() != store.config.conversationId) {
            store.saveConfig(store.config.copy(conversationId = inlineConvId.trim()))
        }

        val storyboard = animatic.storyboard
        val targetIdx = animatic.targetIndex
        val frameCount = storyboard.scenes.getOrNull(targetIdx.sceneIndex)?.frames?.size ?: 1
        val comment = SlideReviewComment(
            id = generateReviewCommentId(),
            sceneIndex = targetIdx.sceneIndex,
            frameIndex = targetIdx.frameIndex,
            sceneCount = storyboard.scenes.size,
            frameCount = frameCount,
            sceneHeader = pending.sceneHeader,
            themePreset = windowController.currentPresetProvider().name,
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

    Surface(
        color = Color(0xF7191E2B),
        contentColor = Color(0xFFF4F1EA),
        shape = RoundedCornerShape(14.dp),
        elevation = 12.dp,
        modifier = Modifier
            .offset { IntOffset(chosenX, chosenY) }
            .width(cardWidthDp)
            .border(1.dp, Color(0xFF343E54), RoundedCornerShape(14.dp))
            .onPreviewKeyEvent { ev ->
                if (ev.type == KeyEventType.KeyDown) {
                    when {
                        ev.key == Key.Escape -> {
                            onDismiss()
                            true
                        }
                        ev.key == Key.Enter && (ev.isMetaPressed || ev.isCtrlPressed) -> {
                            submit(dispatchImmediate = !ev.isShiftPressed)
                            true
                        }
                        else -> false
                    }
                } else {
                    false
                }
            },
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val targetIdx = animatic.targetIndex
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Scene ${targetIdx.sceneIndex + 1} · Frame ${targetIdx.frameIndex + 1}",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF71D7C5),
                )
                Text(
                    text = "${pending.target.kind} @ (${pending.stageX.toInt()}, ${pending.stageY.toInt()})",
                    fontSize = 11.sp,
                    color = Color(0xFFA9B0C0),
                )
            }

            val quotePreview = pending.target.clickedToken.ifBlank {
                pending.target.textSnippet.ifBlank { pending.target.label }
            }
            if (quotePreview.isNotBlank()) {
                Surface(
                    color = Color(0xFF11151F),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = "“$quotePreview”",
                        fontSize = 12.sp,
                        color = Color(0xFFD7DCE8),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            }

            OutlinedTextField(
                value = noteText,
                onValueChange = { noteText = it },
                placeholder = {
                    Text("Describe what to change (⌘↵ sends & hot-reloads)…", fontSize = 12.sp)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(86.dp)
                    .focusRequester(focusRequester)
                    .testTag("ciacola-review-note-input"),
            )

            if (store.effectiveConversationId().isBlank()) {
                OutlinedTextField(
                    value = inlineConvId,
                    onValueChange = { inlineConvId = it },
                    singleLine = true,
                    placeholder = { Text("Agent Conversation ID (for live push)", fontSize = 11.sp) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDismiss) {
                    Text("Cancel", color = Color(0xFFA9B0C0), fontSize = 12.sp)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        enabled = noteText.isNotBlank() && !submitting,
                        onClick = { submit(dispatchImmediate = false) },
                        modifier = Modifier.testTag("ciacola-review-queue-button"),
                    ) {
                        Text("Queue", fontSize = 12.sp)
                    }
                    Button(
                        enabled = noteText.isNotBlank() && !submitting,
                        onClick = { submit(dispatchImmediate = true) },
                        colors = ButtonDefaults.buttonColors(
                            backgroundColor = Color(0xFFFF6B5E),
                            contentColor = Color(0xFF0B0D12),
                        ),
                        modifier = Modifier.testTag("ciacola-review-send-button"),
                    ) {
                        Text("Send ↵", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun SelectedPinPopoverCard(
    comment: SlideReviewComment,
    outerBoundsInRoot: Rect?,
    stageBoundsInRoot: Rect?,
    stageFormatSize: IntSize,
    onClose: () -> Unit,
    onDelete: () -> Unit,
    onRetry: () -> Unit,
) {
    val density = LocalDensity.current
    val cardWidthDp = 320.dp
    val cardWidthPx = with(density) { cardWidthDp.roundToPx() }
    val marginPx = with(density) { 16.dp.roundToPx() }
    val pinOuter = stagePointToOuterOffsetPx(
        stageX = comment.stageX,
        stageY = comment.stageY,
        stageFormatSize = stageFormatSize,
        outerBoundsInRoot = outerBoundsInRoot,
        stageBoundsInRoot = stageBoundsInRoot,
    )
    val containerW = (outerBoundsInRoot?.width?.roundToInt() ?: 960).coerceAtLeast(cardWidthPx + marginPx * 2)
    val containerH = (outerBoundsInRoot?.height?.roundToInt() ?: 540).coerceAtLeast(220)
    val x = (pinOuter.x.roundToInt() + marginPx).coerceIn(marginPx, containerW - cardWidthPx - marginPx)
    val y = (pinOuter.y.roundToInt() - marginPx).coerceIn(marginPx, containerH - 200)

    Surface(
        color = Color(0xF7191E2B),
        contentColor = Color(0xFFF4F1EA),
        shape = RoundedCornerShape(12.dp),
        elevation = 10.dp,
        modifier = Modifier
            .offset { IntOffset(x, y) }
            .width(cardWidthDp)
            .border(1.dp, comment.status.badgeColor(), RoundedCornerShape(12.dp)),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = comment.status.labelText(),
                    color = comment.status.badgeColor(),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                )
                TextButton(onClick = onClose) {
                    Text("Close", fontSize = 11.sp, color = Color(0xFFA9B0C0))
                }
            }
            Text(text = comment.note, fontSize = 13.sp, color = Color(0xFFF4F1EA))
            if (comment.resolution.isNotBlank()) {
                Surface(
                    color = Color(0xFF132620),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = "✓ ${comment.resolution}",
                        fontSize = 12.sp,
                        color = Color(0xFF8BE8B9),
                        modifier = Modifier.padding(8.dp),
                    )
                }
            }
            if (!comment.deliveryError.isNullOrBlank()) {
                Text(
                    text = comment.deliveryError,
                    fontSize = 11.sp,
                    color = Color(0xFFFF8585),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                if (comment.status == ReviewCommentStatus.Failed || comment.status == ReviewCommentStatus.Queued) {
                    TextButton(onClick = onRetry) {
                        Text("Send Now", fontSize = 12.sp, color = Color(0xFF71D7C5))
                    }
                }
                TextButton(onClick = onDelete) {
                    Text("Resolve / Remove", fontSize = 12.sp, color = Color(0xFFFF6B5E))
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
    var convIdField by remember(store.config.conversationId) {
        mutableStateOf(store.effectiveConversationId())
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colors.background)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "Slide Review & Hot-Reload Feedback",
            style = MaterialTheme.typography.h6,
        )
        Text(
            text = "Hold Ctrl + Click (or Ctrl + Drag a box) on any slide element in the Story window, or press F3 to toggle persistent Inspect Mode.",
            fontSize = 12.sp,
            color = MaterialTheme.colors.onBackground.copy(alpha = 0.72f),
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = convIdField,
                onValueChange = { convIdField = it },
                singleLine = true,
                label = { Text("Target Agent Conversation ID") },
                modifier = Modifier.weight(1f),
            )
            Button(
                onClick = {
                    store.saveConfig(store.config.copy(conversationId = convIdField.trim()))
                },
            ) {
                Text("Save")
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val queued = store.comments.count {
                it.status == ReviewCommentStatus.Queued || it.status == ReviewCommentStatus.Failed
            }
            Button(
                enabled = queued > 0,
                onClick = { scope.launch { store.flushQueued() } },
            ) {
                Text("Send Queued ($queued)")
            }
            OutlinedButton(
                enabled = store.comments.any { it.status == ReviewCommentStatus.Applied },
                onClick = { store.clearAppliedComments() },
            ) {
                Text("Clear Applied")
            }
        }

        Divider()

        if (store.comments.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "No review comments yet. Ctrl+Click anything on the slide stage!",
                    color = MaterialTheme.colors.onBackground.copy(alpha = 0.6f),
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                itemsIndexed(store.comments, key = { _, item -> item.id }) { idx, item ->
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        elevation = 2.dp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, item.status.badgeColor().copy(alpha = 0.6f), RoundedCornerShape(10.dp))
                            .clickable {
                                scope.launch {
                                    val target = Storyboard.Index(item.sceneIndex, item.frameIndex)
                                    if (target in animatic.storyboard.indices) {
                                        animatic.jumpTo(target)
                                    }
                                }
                            },
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    text = "#${idx + 1} · Scene ${item.sceneIndex + 1}, Frame ${item.frameIndex + 1} (${item.target.kind})",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                )
                                Text(
                                    text = item.status.labelText(),
                                    color = item.status.badgeColor(),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.sp,
                                )
                            }
                            if (item.target.textSnippet.isNotBlank()) {
                                Text(
                                    text = "“${item.target.textSnippet}”",
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
                                )
                            }
                            Text(text = item.note, fontSize = 13.sp)
                            if (item.resolution.isNotBlank()) {
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = "✓ ${item.resolution}",
                                    fontSize = 12.sp,
                                    color = Color(0xFF4BB582),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun ReviewCommentStatus.badgeColor(): Color = when (this) {
    ReviewCommentStatus.Queued -> Color(0xFFFFC857)
    ReviewCommentStatus.Applying -> Color(0xFF71D7C5)
    ReviewCommentStatus.Applied -> Color(0xFF66D19E)
    ReviewCommentStatus.Failed -> Color(0xFFFF6B6B)
}

private fun ReviewCommentStatus.labelText(): String = when (this) {
    ReviewCommentStatus.Queued -> "Queued"
    ReviewCommentStatus.Applying -> "Applying…"
    ReviewCommentStatus.Applied -> "✓ Applied"
    ReviewCommentStatus.Failed -> "Delivery Failed"
}
