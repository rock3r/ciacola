package dev.sebastiano.ciacola.inspector.element

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isAltPressed as isPointerAltPressed
import androidx.compose.ui.input.pointer.isCtrlPressed as isPointerCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed as isPointerMetaPressed
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed as isPointerShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.bnorm.storyboard.easel.LocalPresentingFullscreen
import dev.bnorm.storyboard.easel.LocalStoryboard
import dev.sebastiano.ciacola.inspector.LocalStageDragPreview
import dev.sebastiano.ciacola.inspector.LocalStageInspectionRegistry
import dev.sebastiano.ciacola.inspector.LocalStageInteractiveRegistry
import dev.sebastiano.ciacola.inspector.LocalStageRootCoordinates
import dev.sebastiano.ciacola.inspector.StageInteractiveRegistry
import dev.sebastiano.ciacola.inspector.StageNodeSnapshot
import dev.sebastiano.ciacola.inspector.StageRootCoordinatesHolder
import dev.sebastiano.ciacola.ui.CiacolaJewelTheme
import dev.sebastiano.ciacola.ui.CiacolaPrimaryButton
import dev.sebastiano.ciacola.ui.rememberAccessibleFocusRingColor
import dev.sebastiano.ciacola.ui.trackAuthoringTextInputFocus
import kotlin.math.max
import kotlin.math.roundToInt
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextArea
import org.jetbrains.jewel.ui.component.Tooltip
import org.jetbrains.jewel.ui.typography

private const val INLINE_EDITOR_MARGIN_PX = 16f

internal fun findEditableNodeAt(
    stagePoint: Offset,
    nodes: List<StageNodeSnapshot>,
    context: StageSelectionContext = StageSelectionContext(),
): StageNodeSnapshot? = StageSelectionModel.resolveHover(
    point = stagePoint,
    nodes = nodes,
    current = StageSelectionModel.sanitizeContext(context, nodes),
)

/**
 * Pure clamp helper computing the inline stage editor top-left offset in stage pixels so the
 * popover and its action buttons stay completely inside `[marginPx, stageSize - popoverSize - marginPx]`.
 */
internal fun computeClampedInlineEditorOffset(
    targetBounds: Rect,
    popoverSizePx: Size,
    stageWidthPx: Float,
    stageHeightPx: Float,
    marginPx: Float = INLINE_EDITOR_MARGIN_PX,
): Offset {
    val maxX = (stageWidthPx - popoverSizePx.width - marginPx).coerceAtLeast(marginPx)
    val maxY = (stageHeightPx - popoverSizePx.height - marginPx).coerceAtLeast(marginPx)
    return Offset(
        x = targetBounds.left.coerceIn(marginPx, maxX),
        y = targetBounds.top.coerceIn(marginPx, maxY),
    )
}

/**
 * Stage decorator layer that exposes [LocalStageInspectionRegistry], [LocalStageDragPreview], and
 * [LocalStageInteractiveRegistry], and strictly when [ElementEditorController.editorActive] is
 * `true` and fullscreen Show Mode is inactive, enables container-first selection, 60 fps
 * placement-phase drag with smart snap guides, live specimen safety, inline text editing, and
 * arrow-key nudging.
 */
@Composable
internal fun StageDirectEditLayer(
    controller: ElementEditorController,
    content: @Composable () -> Unit,
) {
    val existingRoot = LocalStageRootCoordinates.current
    val stageRootHolder = existingRoot ?: remember { StageRootCoordinatesHolder() }
    val existingInteractiveRegistry = LocalStageInteractiveRegistry.current
    val interactiveRegistry = existingInteractiveRegistry ?: remember { StageInteractiveRegistry() }
    val active = controller.editorActive && !LocalPresentingFullscreen.current
    val storyboard = LocalStoryboard.current
    val formatSize = storyboard?.format?.size ?: IntSize(1920, 1080)
    val density = LocalDensity.current
    val stageFocusRequester = remember(controller) { FocusRequester() }

    val previousReturnFocus = controller.onReturnFocusToStage
    DisposableEffect(controller, stageFocusRequester) {
        val handler = {
            val moved = runCatching { stageFocusRequester.requestFocus() }.getOrDefault(false)
            if (!moved) previousReturnFocus()
        }
        controller.onReturnFocusToStage = handler
        onDispose {
            if (controller.onReturnFocusToStage === handler) {
                controller.onReturnFocusToStage = previousReturnFocus
            }
            controller.hoveredElementId = null
            controller.hoveredInteractiveId = null
            controller.cancelActiveDrag()
        }
    }

    var lastClickTimeMs by remember { mutableLongStateOf(0L) }
    var lastClickPos by remember { mutableStateOf(Offset.Zero) }
    var lastClickHadTarget by remember { mutableStateOf(false) }
    var inlineEditorBounds by remember { mutableStateOf<Rect?>(null) }
    var swallowNextEscapeKeyUp by remember { mutableStateOf(false) }

    fun handleStageKeyEvent(ev: KeyEvent): Boolean {
        if (ev.key == Key.Escape && ev.type == KeyEventType.KeyUp && swallowNextEscapeKeyUp) {
            swallowNextEscapeKeyUp = false
            return true
        }
        val delegated = controller.onStageKeyHandler
        if (delegated != null) {
            return delegated(ev)
        }
        if (!active) return false
        return controller.handleElementStageKeyEvent(
            ev = ev,
            includeUndoShortcuts = true,
            onEscapeHandled = { swallowNextEscapeKeyUp = true },
        )
    }

    val interactionModifier = if (!active) {
        Modifier
    } else {
        Modifier
            .focusRequester(stageFocusRequester)
            .focusable()
            .onPreviewKeyEvent(::handleStageKeyEvent)
            // Initial pass: commit inline editor on outside clicks, and intercept Alt+Click on
            // live interactive specimens so their container can be selected without toggling controls.
            .pointerInput(controller, interactiveRegistry) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.type != PointerEventType.Press) continue
                        val pos = event.changes.firstOrNull()?.position ?: continue
                        if (!event.buttons.isPrimaryPressed || controller.isStagePointOverHud(pos)) continue

                        val inlineId = controller.inlineEditingElementId
                        if (inlineId != null) {
                            val insideInline = inlineEditorBounds?.contains(pos) == true
                            if (!insideInline) {
                                controller.commitInlineEdit(restoreStageFocus = true)
                            }
                            continue
                        }

                        val mods = event.keyboardModifiers
                        if (!controller.suppressPicking() &&
                            controller.elementPickingEnabled &&
                            mods.isPointerAltPressed &&
                            !mods.isPointerCtrlPressed &&
                            !mods.isPointerMetaPressed
                        ) {
                            val interactiveHit = interactiveRegistry.hitTestEntry(pos)
                            if (interactiveHit != null) {
                                val slideNodes = controller.slidesNodesForCurrentSlide()
                                val containerId = StageSelectionModel.resolveContainerForInteractive(
                                    entry = interactiveHit,
                                    nodes = slideNodes,
                                )
                                event.changes.forEach { it.consume() }
                                if (containerId != null) {
                                    controller.selectElement(containerId)
                                    runCatching { stageFocusRequester.requestFocus() }
                                }
                            }
                        }
                    }
                }
            }
            // Final pass: yield to any consumed child pointer event (live interactive widgets),
            // otherwise perform container-first selection, double-click drill-in/inline edit, and
            // 60 fps placement-phase drag with smart snap guides.
            .pointerInput(controller, interactiveRegistry, density, formatSize) {
                var pressStartPos: Offset? = null
                var pressStartedInInteractive = false
                var dragCandidateId: String? = null
                var dragStartBounds: Rect? = null
                var dragSlideNodes: List<StageNodeSnapshot> = emptyList()
                var isDragging = false
                var dragAborted = false
                var lastDragDeltaDp = DpOffset.Zero

                fun resetDragSession(cancelPreview: Boolean) {
                    if (cancelPreview && (isDragging || controller.activeDragElementId != null)) {
                        controller.cancelActiveDrag()
                    }
                    pressStartPos = null
                    pressStartedInInteractive = false
                    dragCandidateId = null
                    dragStartBounds = null
                    dragSlideNodes = emptyList()
                    isDragging = false
                    dragAborted = false
                    lastDragDeltaDp = DpOffset.Zero
                }

                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Final)
                        val mods = event.keyboardModifiers
                        val anyConsumed = event.changes.any { it.isConsumed }
                        val pos = event.changes.firstOrNull()?.position
                        val hardBlocked = controller.suppressPicking() ||
                            mods.isPointerCtrlPressed ||
                            mods.isPointerMetaPressed

                        if (hardBlocked) {
                            controller.hoveredElementId = null
                            controller.hoveredInteractiveId = null
                            resetDragSession(cancelPreview = true)
                            continue
                        }

                        val stageCoords = stageRootHolder.coordinates?.takeIf { it.isAttached }
                        val effStageW = stageCoords?.size?.width?.toFloat()?.takeIf { it > 0f }
                            ?: formatSize.width.toFloat()
                        val effStageH = stageCoords?.size?.height?.toFloat()?.takeIf { it > 0f }
                            ?: formatSize.height.toFloat()
                        val viewportScale = computeViewportScale(stageCoords, effStageW)
                        val slideNodes = controller.slidesNodesForCurrentSlide()

                        when (event.type) {
                            PointerEventType.Move -> {
                                if (anyConsumed) {
                                    if (isDragging) resetDragSession(cancelPreview = true)
                                    continue
                                }

                                if (isDragging && controller.activeDragElementId == null) {
                                    // Cancelled mid-drag (e.g. Escape -> cancelActiveDrag()): abort until release.
                                    dragAborted = true
                                    isDragging = false
                                    dragCandidateId = null
                                    dragStartBounds = null
                                    dragSlideNodes = emptyList()
                                    lastDragDeltaDp = DpOffset.Zero
                                }

                                if (dragAborted) {
                                    if (event.buttons.isPrimaryPressed) {
                                        event.changes.forEach { it.consume() }
                                        continue
                                    } else {
                                        dragAborted = false
                                    }
                                }

                                val start = pressStartPos
                                val candidateId = dragCandidateId
                                val startBounds = dragStartBounds
                                if (start != null &&
                                    pos != null &&
                                    candidateId != null &&
                                    startBounds != null &&
                                    !pressStartedInInteractive &&
                                    event.buttons.isPrimaryPressed &&
                                    controller.inlineEditingElementId == null
                                ) {
                                    val rawDelta = pos - start
                                    val dragSlopStagePx = screenPxToStagePx(STAGE_DRAG_SLOP_SCREEN_PX, viewportScale)
                                    if (isDragging || rawDelta.getDistance() > dragSlopStagePx) {
                                        isDragging = true
                                        controller.activeDragElementId = candidateId
                                        controller.hoveredElementId = null
                                        controller.hoveredInteractiveId = null
                                        val snap = computeDragSnap(
                                            draggedElementId = candidateId,
                                            startBounds = startBounds,
                                            rawDeltaStagePx = rawDelta,
                                            allNodes = dragSlideNodes,
                                            stageWidthPx = effStageW,
                                            stageHeightPx = effStageH,
                                            viewportScale = viewportScale,
                                            disableSnap = mods.isPointerAltPressed,
                                            axisLock = mods.isPointerShiftPressed,
                                        )
                                        val dxDp = with(density) { snap.deltaStagePx.x.toDp() }
                                        val dyDp = with(density) { snap.deltaStagePx.y.toDp() }
                                        lastDragDeltaDp = DpOffset(dxDp, dyDp)
                                        controller.dragPreview.set(candidateId, lastDragDeltaDp)
                                        controller.activeSnapGuides = snap.guides
                                        event.changes.forEach { it.consume() }
                                        continue
                                    }
                                }

                                if (!isDragging &&
                                    pos != null &&
                                    !controller.isStagePointOverHud(pos) &&
                                    controller.inlineEditingElementId == null
                                ) {
                                    val interactiveHit = interactiveRegistry.hitTestEntry(pos)
                                    if (interactiveHit != null) {
                                        controller.hoveredInteractiveId = interactiveHit.id
                                        controller.hoveredElementId = null
                                    } else {
                                        controller.hoveredInteractiveId = null
                                        val sanitized = StageSelectionModel.sanitizeContext(
                                            controller.currentSelectionContext(),
                                            slideNodes,
                                        )
                                        controller.hoveredElementId = StageSelectionModel.resolveHover(
                                            point = pos,
                                            nodes = slideNodes,
                                            current = sanitized,
                                        )?.elementId
                                    }
                                } else if (!isDragging) {
                                    controller.hoveredElementId = null
                                    controller.hoveredInteractiveId = null
                                }
                            }

                            PointerEventType.Exit -> {
                                controller.hoveredElementId = null
                                controller.hoveredInteractiveId = null
                            }

                            PointerEventType.Press -> {
                                if (anyConsumed ||
                                    pos == null ||
                                    !event.buttons.isPrimaryPressed ||
                                    mods.isPointerAltPressed ||
                                    controller.isStagePointOverHud(pos) ||
                                    controller.inlineEditingElementId != null
                                ) {
                                    resetDragSession(cancelPreview = false)
                                    continue
                                }

                                pressStartPos = pos
                                pressStartedInInteractive = interactiveRegistry.hitTestEntry(pos) != null
                                isDragging = false
                                dragAborted = false
                                lastDragDeltaDp = DpOffset.Zero

                                if (!pressStartedInInteractive && controller.elementPickingEnabled) {
                                    val selectedId = controller.resolveSelectedElementIdForCurrentBeat()
                                    val selectedNode = selectedId?.let { id ->
                                        slideNodes.firstOrNull { it.elementId == id }
                                    }
                                    if (selectedNode != null && selectedNode.stageBounds.inflate(6f).contains(pos)) {
                                        dragCandidateId = selectedNode.elementId
                                        dragStartBounds = selectedNode.stageBounds
                                        dragSlideNodes = slideNodes
                                    } else {
                                        dragCandidateId = null
                                        dragStartBounds = null
                                        dragSlideNodes = emptyList()
                                    }
                                } else {
                                    dragCandidateId = null
                                    dragStartBounds = null
                                    dragSlideNodes = emptyList()
                                }
                            }

                            PointerEventType.Release -> {
                                if (dragAborted) {
                                    resetDragSession(cancelPreview = true)
                                    event.changes.forEach { it.consume() }
                                    continue
                                }
                                if (isDragging) {
                                    val wasStillActive = controller.activeDragElementId != null
                                    val committedId = dragCandidateId
                                    val committedDelta = lastDragDeltaDp
                                    resetDragSession(cancelPreview = false)
                                    if (wasStillActive && committedId != null) {
                                        controller.commitDragOffset(
                                            elementId = committedId,
                                            deltaXDp = committedDelta.x.value,
                                            deltaYDp = committedDelta.y.value,
                                        )
                                    } else {
                                        controller.cancelActiveDrag()
                                    }
                                    event.changes.forEach { it.consume() }
                                    continue
                                }

                                val start = pressStartPos
                                val startedInInteractive = pressStartedInInteractive
                                resetDragSession(cancelPreview = false)

                                if (anyConsumed || startedInInteractive || start == null || pos == null) {
                                    continue
                                }

                                val clickSlopStagePx = max(6f, screenPxToStagePx(6f, viewportScale))
                                if ((pos - start).getDistance() > clickSlopStagePx) continue

                                val nowMs = System.currentTimeMillis()
                                val doubleClickDistStagePx = max(16f, screenPxToStagePx(12f, viewportScale))
                                val isDoubleClick = lastClickHadTarget &&
                                    (nowMs - lastClickTimeMs) <= viewConfiguration.doubleTapTimeoutMillis &&
                                    (pos - lastClickPos).getDistance() <= doubleClickDistStagePx

                                val sanitizedContext = StageSelectionModel.sanitizeContext(
                                    controller.currentSelectionContext(),
                                    slideNodes,
                                )

                                if (isDoubleClick) {
                                    if (controller.elementPickingEnabled) {
                                        when (
                                            val outcome = StageSelectionModel.resolveDoubleClick(
                                                point = pos,
                                                nodes = slideNodes,
                                                current = sanitizedContext,
                                                supportsInlineTextEdit = { controller.canInlineEdit(it.elementId) },
                                            )
                                        ) {
                                            is StageDoubleClickOutcome.EnteredContainer -> {
                                                lastClickTimeMs = 0L
                                                lastClickHadTarget = false
                                                event.changes.forEach { it.consume() }
                                                controller.applySelectionContext(outcome.next)
                                                runCatching { stageFocusRequester.requestFocus() }
                                            }

                                            is StageDoubleClickOutcome.StartInlineTextEdit -> {
                                                lastClickTimeMs = 0L
                                                lastClickHadTarget = false
                                                event.changes.forEach { it.consume() }
                                                controller.applySelectionContext(outcome.next)
                                                controller.startInlineEdit(outcome.elementId)
                                            }

                                            is StageDoubleClickOutcome.Selected -> {
                                                lastClickTimeMs = nowMs
                                                lastClickPos = pos
                                                lastClickHadTarget = outcome.next.selectedElementId != null
                                                controller.applySelectionContext(outcome.next)
                                            }

                                            StageDoubleClickOutcome.NoOp -> {
                                                lastClickTimeMs = 0L
                                                lastClickHadTarget = false
                                            }
                                        }
                                    } else {
                                        val leaf = findEditableNodeAt(pos, slideNodes)
                                        if (leaf != null && controller.canInlineEdit(leaf.elementId)) {
                                            lastClickTimeMs = 0L
                                            lastClickHadTarget = false
                                            event.changes.forEach { it.consume() }
                                            controller.startInlineEdit(leaf.elementId)
                                        }
                                    }
                                } else {
                                    val next = StageSelectionModel.resolveSingleClick(
                                        point = pos,
                                        nodes = slideNodes,
                                        current = sanitizedContext,
                                    )
                                    lastClickTimeMs = nowMs
                                    lastClickPos = pos
                                    lastClickHadTarget = next.selectedElementId != null
                                    if (controller.elementPickingEnabled) {
                                        controller.applySelectionContext(next)
                                        if (next.selectedElementId != null) {
                                            runCatching { stageFocusRequester.requestFocus() }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onPlaced { coords ->
                if (existingRoot == null) {
                    stageRootHolder.coordinates = coords
                }
            }
            .then(interactionModifier),
    ) {
        CompositionLocalProvider(
            LocalStageInspectionRegistry provides controller.inspectionRegistry,
            LocalStageRootCoordinates provides stageRootHolder,
            LocalStageDragPreview provides controller.dragPreview,
            LocalStageInteractiveRegistry provides interactiveRegistry,
        ) {
            content()
        }

        if (active) {
            CiacolaJewelTheme(isDark = controller.isDarkThemeProvider()) {
                StageSelectionAndInlineOverlay(
                    controller = controller,
                    stageRootHolder = stageRootHolder,
                    interactiveRegistry = interactiveRegistry,
                    stageWidthPx = formatSize.width.toFloat(),
                    stageHeightPx = formatSize.height.toFloat(),
                    onInlineBoundsChanged = { inlineEditorBounds = it },
                    onEscapeKeyDownHandled = { swallowNextEscapeKeyUp = true },
                )
            }
        } else {
            inlineEditorBounds = null
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StageSelectionAndInlineOverlay(
    controller: ElementEditorController,
    stageRootHolder: StageRootCoordinatesHolder,
    interactiveRegistry: StageInteractiveRegistry,
    stageWidthPx: Float,
    stageHeightPx: Float,
    onInlineBoundsChanged: (Rect?) -> Unit,
    onEscapeKeyDownHandled: () -> Unit = {},
) {
    val slideNodes = controller.slidesNodesForCurrentSlide()
    val rawSelectedId = controller.selectedElementId
    val resolvedSelectedId = controller.resolveSelectedElementIdForCurrentBeat()
    LaunchedEffect(resolvedSelectedId) {
        if (resolvedSelectedId != null && resolvedSelectedId != rawSelectedId) {
            controller.followBeatScopedSelection(resolvedSelectedId)
        }
    }
    val hoveredNode = controller.hoveredElementId?.let { id ->
        slideNodes.firstOrNull { it.elementId == id }
    }
    val hoveredInteractive = controller.hoveredInteractiveId?.let { id ->
        interactiveRegistry.entryFor(id)
    }
    val selectedNode = resolvedSelectedId?.let { id ->
        slideNodes.firstOrNull { it.elementId == id }
    }
    val inlineId = controller.inlineEditingElementId
    val inlineNode = inlineId?.let { id ->
        slideNodes.firstOrNull { it.elementId == id } ?: controller.inspectionRegistry.nodes[id]
    }
    if (inlineId == null || inlineNode == null) {
        onInlineBoundsChanged(null)
    }
    val snapGuides = controller.activeSnapGuides
    val textMeasurer = rememberTextMeasurer()
    val accent = Color(0xFF3D9EFF)
    val hoverAccent = accent.copy(alpha = 0.65f)
    val liveAccent = Color(0xFF2DD4BF)

    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .testTag("ciacola-direct-edit-overlay"),
    ) {
        val viewportScale = computeViewportScale(stageRootHolder.coordinates, size.width.takeIf { it > 0f } ?: stageWidthPx)

        if (hoveredNode != null && hoveredNode.elementId != controller.selectedElementId && inlineId == null) {
            val r = hoveredNode.stageBounds.inflate(4f)
            val dash = PathEffect.dashPathEffect(floatArrayOf(8f, 6f), 0f)
            drawRoundRect(
                color = hoverAccent,
                topLeft = r.topLeft,
                size = r.size,
                cornerRadius = CornerRadius(6f, 6f),
                style = Stroke(width = 2f, pathEffect = dash),
            )
        }

        if (hoveredInteractive != null && inlineId == null) {
            val r = hoveredInteractive.stageBounds.inflate(4f)
            val dash = PathEffect.dashPathEffect(floatArrayOf(6f, 4f), 0f)
            drawRoundRect(
                color = liveAccent,
                topLeft = r.topLeft,
                size = r.size,
                cornerRadius = CornerRadius(6f, 6f),
                style = Stroke(width = 2f, pathEffect = dash),
            )
            val badgeLayout = textMeasurer.measure(
                text = "Live · ⌥Click selects",
                style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color.White),
                maxLines = 1,
            )
            val padH = 8f
            val padV = 4f
            val badgeW = badgeLayout.size.width + padH * 2f
            val badgeH = badgeLayout.size.height + padV * 2f
            val aboveY = r.top - badgeH - 6f
            val badgeTop = if (aboveY >= 4f) aboveY else (r.bottom + 6f).coerceAtMost(size.height - badgeH - 4f)
            val badgeLeft = r.left.coerceIn(4f, (size.width - badgeW - 4f).coerceAtLeast(4f))
            drawRoundRect(
                color = Color(0xFF0B2F2B),
                topLeft = Offset(badgeLeft, badgeTop),
                size = Size(badgeW, badgeH),
                cornerRadius = CornerRadius(5f, 5f),
            )
            drawRoundRect(
                color = liveAccent,
                topLeft = Offset(badgeLeft, badgeTop),
                size = Size(badgeW, badgeH),
                cornerRadius = CornerRadius(5f, 5f),
                style = Stroke(width = 1.5f),
            )
            drawText(badgeLayout, topLeft = Offset(badgeLeft + padH, badgeTop + padV))
        }

        if (selectedNode != null && inlineId == null) {
            val r = selectedNode.stageBounds.inflate(4f)
            drawRoundRect(
                color = accent.copy(alpha = 0.08f),
                topLeft = r.topLeft,
                size = r.size,
                cornerRadius = CornerRadius(6f, 6f),
            )
            drawRoundRect(
                color = accent,
                topLeft = r.topLeft,
                size = r.size,
                cornerRadius = CornerRadius(6f, 6f),
                style = Stroke(width = 2.5f),
            )

            // Compact high-contrast floating tag chip (13.sp, no fake resize handles).
            val chipLabel = "${selectedNode.elementId} · ${selectedNode.kind}"
            val layout = textMeasurer.measure(
                text = chipLabel,
                style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color.White),
                maxLines = 1,
            )
            val padH = 8f
            val padV = 4f
            val chipW = layout.size.width + padH * 2f
            val chipH = layout.size.height + padV * 2f
            val aboveY = r.top - chipH - 6f
            val chipTop = if (aboveY >= 4f) aboveY else (r.bottom + 6f).coerceAtMost(size.height - chipH - 4f)
            val chipLeft = r.left.coerceIn(4f, (size.width - chipW - 4f).coerceAtLeast(4f))
            drawRoundRect(
                color = Color(0xFF0F2647),
                topLeft = Offset(chipLeft, chipTop),
                size = Size(chipW, chipH),
                cornerRadius = CornerRadius(5f, 5f),
            )
            drawRoundRect(
                color = accent,
                topLeft = Offset(chipLeft, chipTop),
                size = Size(chipW, chipH),
                cornerRadius = CornerRadius(5f, 5f),
                style = Stroke(width = 1.5f),
            )
            drawText(layout, topLeft = Offset(chipLeft + padH, chipTop + padV))
        }

        if (snapGuides.isNotEmpty() && inlineId == null) {
            drawSnapGuides(guides = snapGuides, viewportScale = viewportScale)
        }
    }

    if (inlineId != null && inlineNode != null) {
        InlineStageTextEditor(
            controller = controller,
            elementId = inlineId,
            elementLabel = inlineNode.label.ifBlank { inlineId },
            targetBounds = inlineNode.stageBounds,
            stageRootHolder = stageRootHolder,
            stageWidthPx = stageWidthPx,
            stageHeightPx = stageHeightPx,
            onBoundsChanged = onInlineBoundsChanged,
            onEscapeKeyDownHandled = onEscapeKeyDownHandled,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun InlineStageTextEditor(
    controller: ElementEditorController,
    elementId: String,
    elementLabel: String,
    targetBounds: Rect,
    stageRootHolder: StageRootCoordinatesHolder,
    stageWidthPx: Float,
    stageHeightPx: Float,
    onBoundsChanged: (Rect?) -> Unit,
    onEscapeKeyDownHandled: () -> Unit = {},
) {
    val density = LocalDensity.current
    val globalColors = JewelTheme.globalColors
    val focusRingColor = rememberAccessibleFocusRingColor()
    val focusRequester = remember(elementId) { FocusRequester() }
    val textFieldState = rememberTextFieldState(initialText = controller.inlineDraftText)
    var editorFocused by remember(elementId) { mutableStateOf(false) }
    var popoverSizePx by remember(elementId) { mutableStateOf(IntSize.Zero) }

    val liveTextReader = remember(textFieldState) {
        { textFieldState.text.toString() }
    }
    controller.inlineDraftTextProvider = liveTextReader
    DisposableEffect(controller, liveTextReader) {
        controller.inlineDraftTextProvider = liveTextReader
        onDispose {
            if (controller.inlineDraftTextProvider === liveTextReader) {
                controller.inlineDraftTextProvider = null
            }
        }
    }

    LaunchedEffect(elementId) {
        if (textFieldState.text.toString() != controller.inlineDraftText) {
            textFieldState.setTextAndPlaceCursorAtEnd(controller.inlineDraftText)
        }
        var attempts = 0
        while (!editorFocused && attempts < 10) {
            val ok = runCatching { focusRequester.requestFocus() }.getOrDefault(false)
            if (ok && editorFocused) break
            withFrameNanos { }
            attempts++
        }
    }

    val liveStageSize = stageRootHolder.coordinates?.takeIf { it.isAttached }?.size
    val effStageW = liveStageSize?.width?.toFloat()?.takeIf { it > 0f } ?: stageWidthPx
    val effStageH = liveStageSize?.height?.toFloat()?.takeIf { it > 0f } ?: stageHeightPx
    val maxAllowedWidthDp = with(density) { (effStageW - INLINE_EDITOR_MARGIN_PX * 2f).toDp() }
        .coerceIn(260.dp, 440.dp)
    val editorWidthDp = with(density) { targetBounds.width.toDp() }.coerceIn(260.dp, maxAllowedWidthDp)
    val measuredSize = Size(
        width = if (popoverSizePx.width > 0) popoverSizePx.width.toFloat() else with(density) { editorWidthDp.toPx() },
        height = if (popoverSizePx.height > 0) popoverSizePx.height.toFloat() else with(density) { 148.dp.toPx() },
    )
    val clampedOffset = computeClampedInlineEditorOffset(
        targetBounds = targetBounds,
        popoverSizePx = measuredSize,
        stageWidthPx = effStageW,
        stageHeightPx = effStageH,
        marginPx = INLINE_EDITOR_MARGIN_PX,
    )

    fun handleInlineKey(ev: KeyEvent): Boolean {
        if (ev.type != KeyEventType.KeyDown) return false
        if (ev.key == Key.Escape) {
            onEscapeKeyDownHandled()
            controller.cancelInlineEdit(restoreStageFocus = true)
            return true
        }
        val awtKeyCode = (ev.nativeKeyEvent as? java.awt.event.KeyEvent)?.keyCode
        val isEnter = ev.key == Key.Enter ||
            ev.key == Key.NumPadEnter ||
            awtKeyCode == java.awt.event.KeyEvent.VK_ENTER
        if (isEnter) {
            if (ev.isMetaPressed || (!ev.isShiftPressed && !ev.isAltPressed)) {
                controller.commitInlineEdit(
                    textOverride = textFieldState.text.toString(),
                    restoreStageFocus = true,
                )
                return true
            }
        }
        return false
    }

    DisposableEffect(elementId) {
        onDispose { onBoundsChanged(null) }
    }

    Column(
        modifier = Modifier
            .offset { IntOffset(clampedOffset.x.roundToInt(), clampedOffset.y.roundToInt()) }
            .width(editorWidthDp)
            .onSizeChanged { sz ->
                if (sz.width > 0 && sz.height > 0 && sz != popoverSizePx) {
                    popoverSizePx = sz
                }
            }
            .onPlaced { coords ->
                val stageRoot = stageRootHolder.coordinates?.takeIf { it.isAttached }
                val rect = if (stageRoot != null && coords.isAttached) {
                    runCatching { stageRoot.localBoundingBoxOf(coords, clipBounds = false) }.getOrNull()
                } else if (coords.isAttached) {
                    runCatching { coords.boundsInRoot() }.getOrNull()
                } else {
                    null
                }
                onBoundsChanged(rect)
            }
            .background(globalColors.panelBackground, RoundedCornerShape(8.dp))
            .border(1.5.dp, focusRingColor, RoundedCornerShape(8.dp))
            .padding(10.dp)
            .onPreviewKeyEvent(::handleInlineKey)
            .testTag("ciacola-inline-stage-editor")
            // Consume all unhandled bubbling key events (notably Spacebar) so they never leak to StoryNavigation.
            .onKeyEvent { true },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val headerTitle = elementLabel.takeIf { it.isNotBlank() } ?: elementId
            Box(modifier = Modifier.weight(1f)) {
                Tooltip(tooltip = { Text(elementId, style = JewelTheme.typography.small) }) {
                    Text(
                        text = "Edit $headerTitle",
                        style = JewelTheme.typography.small.copy(fontWeight = FontWeight.SemiBold),
                        color = globalColors.text.normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Text(
                text = "↵ Save · ⇧↵ Line · Esc",
                style = JewelTheme.typography.small,
                color = globalColors.text.info,
                maxLines = 1,
            )
        }
        TextArea(
            state = textFieldState,
            lineLimits = TextFieldLineLimits.MultiLine(minHeightInLines = 1, maxHeightInLines = 6),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester)
                .trackAuthoringTextInputFocus()
                .onFocusChanged { if (it.isFocused) editorFocused = true }
                .testTag("ciacola-inline-stage-editor-input"),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(
                onClick = { controller.cancelInlineEdit(restoreStageFocus = true) },
                modifier = Modifier.testTag("ciacola-inline-stage-editor-cancel"),
            ) {
                Text("Cancel", style = JewelTheme.typography.small)
            }
            CiacolaPrimaryButton(
                text = "Apply",
                onClick = {
                    controller.commitInlineEdit(
                        textOverride = textFieldState.text.toString(),
                        restoreStageFocus = true,
                    )
                },
                textStyle = JewelTheme.typography.small,
                modifier = Modifier.testTag("ciacola-inline-stage-editor-save"),
            )
        }
    }
}
