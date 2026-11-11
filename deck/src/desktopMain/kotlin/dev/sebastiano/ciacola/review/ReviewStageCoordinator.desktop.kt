package dev.sebastiano.ciacola.review

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
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
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.bnorm.storyboard.ContentDecorator
import dev.bnorm.storyboard.Storyboard
import dev.bnorm.storyboard.easel.LocalSceneMode
import dev.bnorm.storyboard.easel.LocalStoryboard
import dev.bnorm.storyboard.easel.SceneMode
import dev.sebastiano.ciacola.timeline.LocalTimelineOffscreen
import dev.sebastiano.ciacola.ui.CiacolaJewelTheme
import dev.sebastiano.ciacola.ui.rememberReviewStatusPalette
import java.awt.Window as AwtWindow
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.typography

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

/**
 * Coordinates 1920x1080 slide stage hit-testing, clean [GraphicsLayer] screenshot capture,
 * and active review pin selection between the Storyboard scene decorator and outer window popups.
 */
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
    internal var swallowNextEscapeKeyUp by mutableStateOf(false)
    internal var hoverStagePoint by mutableStateOf<Offset?>(null)
    internal var dragStartStagePoint by mutableStateOf<Offset?>(null)
    internal var dragCurrentStagePoint by mutableStateOf<Offset?>(null)

    var pendingSelection by mutableStateOf<PendingReviewSelection?>(null)
    var selectedPinCommentId by mutableStateOf<String?>(null)

    internal var currentSlideCommentsProvider: () -> List<SlideReviewComment> = { emptyList() }
    internal var isDarkThemeProvider: () -> Boolean = { true }

    private val entries = mutableStateMapOf<Any, RegisteredEntry>()

    fun dismissActivePopups(fromEscapeKey: Boolean = false) {
        if (fromEscapeKey && (pendingSelection != null || selectedPinCommentId != null)) {
            swallowNextEscapeKeyUp = true
        }
        pendingSelection = null
        selectedPinCommentId = null
    }

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
        // Offscreen timeline indexing scenes also render in Story mode; they must never take over
        // (or, when they close, reset) the live stage's coordinates, capture layer, or popups.
        if (LocalSceneMode.current != SceneMode.Story || LocalTimelineOffscreen.current) {
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

    DisposableEffect(coordinator) {
        onDispose {
            coordinator.stageRootCoordinates = null
            coordinator.stageBoundsInRoot = null
            coordinator.hoverStagePoint = null
            coordinator.dragStartStagePoint = null
            coordinator.dragCurrentStagePoint = null
            coordinator.dismissActivePopups()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .testTag("ciacola-review-stage")
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

        CiacolaJewelTheme(isDark = coordinator.isDarkThemeProvider()) {
            ReviewStageHighlightsAndPins(
                coordinator = coordinator,
                isInspectActive = isInspectActive,
            )
        }
    }
}

@Composable
private fun ReviewStageHighlightsAndPins(
    coordinator: ReviewStageCoordinator,
    isInspectActive: Boolean,
) {
    val statusPalette = rememberReviewStatusPalette()
    val borderOutline = JewelTheme.globalColors.panelBackground
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
        val inspectColor = statusPalette.inspectAccent
        val marqueeColor = statusPalette.marqueeAccent

        hoveredHit?.bounds?.toRect()?.let { rect ->
            drawRoundRect(
                color = inspectColor.copy(alpha = 0.12f),
                topLeft = rect.topLeft,
                size = rect.size,
                cornerRadius = CornerRadius(8f, 8f),
            )
            drawRoundRect(
                color = inspectColor,
                topLeft = rect.topLeft,
                size = rect.size,
                cornerRadius = CornerRadius(8f, 8f),
                style = Stroke(width = 3f, pathEffect = dash),
            )
        }

        val dragStart = coordinator.dragStartStagePoint
        val dragCurrent = coordinator.dragCurrentStagePoint
        if (dragStart != null && dragCurrent != null) {
            val marquee = normalizeDragRect(dragStart, dragCurrent)
            if (isSignificantDrag(marquee)) {
                drawRoundRect(
                    color = marqueeColor.copy(alpha = 0.14f),
                    topLeft = marquee.topLeft,
                    size = marquee.size,
                    cornerRadius = CornerRadius(8f, 8f),
                )
                drawRoundRect(
                    color = marqueeColor,
                    topLeft = marquee.topLeft,
                    size = marquee.size,
                    cornerRadius = CornerRadius(8f, 8f),
                    style = Stroke(width = 3.5f, pathEffect = dash),
                )
            }
        }

        coordinator.pendingSelection?.selectionBounds?.toRect()?.let { active ->
            drawRoundRect(
                color = marqueeColor.copy(alpha = 0.14f),
                topLeft = active.topLeft,
                size = active.size,
                cornerRadius = CornerRadius(8f, 8f),
            )
            drawRoundRect(
                color = marqueeColor,
                topLeft = active.topLeft,
                size = active.size,
                cornerRadius = CornerRadius(8f, 8f),
                style = Stroke(width = 3f),
            )
        }
    }

    if (coordinator.showStageBadges) {
        val slideComments = coordinator.currentSlideCommentsProvider()
        val density = LocalDensity.current
        slideComments.forEachIndexed { idx, comment ->
            val pinColor = statusPalette.colorFor(comment.status)
            val pinSizeDp = 24.dp
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
                    .border(1.5.dp, borderOutline, CircleShape)
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
                    color = statusPalette.pinBadgeForeground,
                    style = JewelTheme.typography.small.copy(fontWeight = FontWeight.Bold),
                )
            }
        }
    }
}
