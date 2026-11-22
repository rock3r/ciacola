package dev.sebastiano.ciacola.inspector.element

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.isAltPressed
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.sebastiano.ciacola.document.JsonMergePatch
import dev.sebastiano.ciacola.document.NodeSpec
import dev.sebastiano.ciacola.inspector.LocalStageDragPreview
import dev.sebastiano.ciacola.inspector.LocalStageInspectionRegistry
import dev.sebastiano.ciacola.inspector.LocalStageInteractiveRegistry
import dev.sebastiano.ciacola.inspector.StageNodeSnapshot
import dev.sebastiano.ciacola.inspector.design.inspectorAccent
import dev.sebastiano.ciacola.inspector.design.inspectorBodyStyle
import dev.sebastiano.ciacola.ui.LocalAuthoringTextInputTracker
import kotlin.math.hypot
import kotlin.math.roundToInt
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text

/** Draws a 1dp accent outline with a 1dp contrasting halo so it reads on dark, light and mid-tone slides. */
internal fun DrawScope.drawHaloOutline(rect: Rect, accent: Color, halo: Color, strokePx: Float, dashed: Boolean = false) {
    val haloStroke = Stroke(width = strokePx * 3f)
    drawRect(halo, topLeft = rect.topLeft, size = rect.size, style = haloStroke)
    drawRect(
        accent,
        topLeft = rect.topLeft,
        size = rect.size,
        style = Stroke(width = strokePx, pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(4f * strokePx, 3f * strokePx)) else null),
    )
}

/**
 * Document-backed Stage Direct Edit overlay:
 * - Click selects; `Shift+Click` toggles nodes in the multi-selection; dragging empty canvas draws
 *   a marquee selecting intersecting top-level nodes; `Alt+Click` selects a live specimen container.
 * - Dragging any selected node moves the whole selection together (live `StageDragPreview` offsets,
 *   smart snap guides, `Alt` disables snap, `Shift` locks the dominant axis) and commits ONE undo step.
 * - Selection chrome: 1dp accent outline + 1dp halo, a unified bounding box for multi-selections,
 *   8dp resize handles 4dp outside the frame, and a dimension pill only while dragging or resizing.
 * - The inline text editor keeps local `⌘Z` and records real UTF-16 range provenance.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DocumentStageDirectEditOverlay(
    controller: DocumentElementInspectorController,
    stageScale: Float = 1f,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    /** `true` while an `interactive = "keyboard"` specimen owns the keyboard (arrows, `⌘A`). */
    keyboardCapturedProvider: () -> Boolean = { false },
    content: @Composable () -> Unit = {},
) {
    if (!enabled) {
        content()
        return
    }

    val density = LocalDensity.current
    val inspectionRegistry = LocalStageInspectionRegistry.current
    val interactiveRegistry = LocalStageInteractiveRegistry.current
    val stageDragPreview = LocalStageDragPreview.current
    val safeScale = stageScale.coerceAtLeast(0.05f)
    val slide = controller.activeSlide()
    val stageWidth = controller.store.document.stage.width.toFloat().coerceAtLeast(1f)
    val stageHeight = controller.store.document.stage.height.toFloat().coerceAtLeast(1f)
    val resolvedNodes = remember(slide, controller.activeBeat, controller.store.revision) {
        controller.activeSlideNodes()
            .filter { JsonMergePatch.isNodePresentAtBeat(it, controller.activeBeat) }
            .map { JsonMergePatch.resolveNodeAtBeat(it, controller.activeBeat) }
    }
    val topLevelIds = remember(slide) { slide?.nodes?.mapTo(HashSet()) { it.id }.orEmpty() }
    var overlaySizePx by remember { mutableStateOf(IntSize(1920, 1080)) }
    var activeSnapGuides by remember { mutableStateOf<List<SnapGuideLine>>(emptyList()) }
    var marqueeStage by remember { mutableStateOf<Rect?>(null) }

    val latestSafeScale by rememberUpdatedState(safeScale)
    val latestResolvedNodes by rememberUpdatedState(resolvedNodes)
    val latestStageWidth by rememberUpdatedState(stageWidth)
    val latestStageHeight by rememberUpdatedState(stageHeight)
    val latestTopLevelIds by rememberUpdatedState(topLevelIds)

    SideEffect {
        controller.stageBoundsProvider = { id -> inspectionRegistry?.nodes?.get(id)?.stageBounds }
    }
    DisposableEffect(controller) {
        // Never leave a stale registry closure behind once the overlay leaves composition.
        onDispose { controller.stageBoundsProvider = null }
    }
    val textInputTracker = LocalAuthoringTextInputTracker.current
    val latestKeyboardCaptured by rememberUpdatedState(keyboardCapturedProvider)

    val movingIds = controller.dragPreviewNodeIds
    val activeDragDx = controller.dragPreviewDeltaX
    val activeDragDy = controller.dragPreviewDeltaY
    LaunchedEffect(stageDragPreview, movingIds, activeDragDx, activeDragDy, safeScale) {
        if (stageDragPreview != null) {
            stageDragPreview.clear()
            // Stage-unit deltas → overlay pixels: the same `safeScale` the selection chrome uses,
            // so the dragged node and its outline/handles never detach when `stageScale != 1`.
            val offset = stageDragPreviewOffset(activeDragDx, activeDragDy, safeScale, density.density)
            for (id in movingIds) stageDragPreview.set(id, offset)
        }
    }

    fun nodeStageRect(n: NodeSpec, scale: Float = latestSafeScale): Rect? {
        val bounds = DocumentElementArrangeOps.nodeStageBounds(
            controller = controller,
            nodeId = n.id,
            stageBoundsProvider = { id -> inspectionRegistry?.nodes?.get(id)?.stageBounds },
        ) ?: return null
        val bw = bounds.width.coerceAtLeast(16f / scale)
        val bh = bounds.height.coerceAtLeast(16f / scale)
        return Rect(bounds.left, bounds.top, bounds.left + bw, bounds.top + bh)
    }

    fun buildSnapSnapshots(nodes: List<NodeSpec>): List<StageNodeSnapshot> {
        val inspected = inspectionRegistry?.nodes?.values?.toList().orEmpty()
        if (inspected.isNotEmpty()) return inspected
        return nodes.mapNotNull { n ->
            val r = nodeStageRect(n) ?: return@mapNotNull null
            StageNodeSnapshot(
                elementId = n.id,
                kind = n.kind?.name ?: "Node",
                label = n.id,
                sceneIndex = 0,
                beatIndex = controller.activeBeat,
                stageBounds = r,
                effectiveAlpha = n.opacity.toFloat().coerceIn(0f, 1f),
                parentElementId = null,
            )
        }
    }

    fun hitTestNonInteractiveNodeAt(stagePt: Offset, nodes: List<NodeSpec>): NodeSpec? {
        // Never steal pointer events from `interactive = "pointer"` or `"both"` specimen subtrees.
        for (n in nodes) {
            if (n.interactive != null && n.interactive != "keyboard") {
                val r = nodeStageRect(n)
                if (r != null && r.contains(stagePt)) return null
            }
        }
        return nodes
            .withIndex()
            .mapNotNull { (idx, n) ->
                if (n.interactive != null) return@mapNotNull null
                val r = nodeStageRect(n) ?: return@mapNotNull null
                if (!r.contains(stagePt)) return@mapNotNull null
                Triple(n, r.width * r.height, idx)
            }
            .minWithOrNull(compareBy<Triple<NodeSpec, Float, Int>> { it.second }.thenByDescending { it.third })
            ?.first
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { overlaySizePx = it }
            .onPreviewKeyEvent { ev ->
                // Focused text fields and keyboard-captured specimens keep their arrows and `⌘A`.
                controller.handleKeyEvent(
                    ev,
                    externalInputActive = textInputTracker?.hasFocusedTextInput == true || latestKeyboardCaptured(),
                )
            }
            .pointerInput(controller, interactiveRegistry, inspectionRegistry) {
                // Alt+Click selects the container of a live interactive specimen.
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    if (!currentEvent.keyboardModifiers.isAltPressed || controller.inlineEditingNodeId != null) {
                        return@awaitEachGesture
                    }
                    down.consume()
                    val scale = latestSafeScale
                    val stagePt = Offset(down.position.x / scale, down.position.y / scale)
                    val up = waitForUpOrCancellation(pass = PointerEventPass.Initial)
                    if (up != null) {
                        up.consume()
                        val interactiveHit = interactiveRegistry?.hitTestEntry(stagePt)
                        val targetId = interactiveHit?.parentElementId ?: latestResolvedNodes
                            .mapNotNull { n -> nodeStageRect(n, scale)?.takeIf { it.contains(stagePt) }?.let { n.id to (it.width * it.height) } }
                            .minByOrNull { it.second }
                            ?.first
                        if (targetId != null) controller.selectNode(targetId)
                    }
                }
            }
            .pointerInput(controller, interactiveRegistry, inspectionRegistry) {
                var lastTapNodeId: String? = null
                var lastTapUptimeMs = -1L
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = true, pass = PointerEventPass.Main)
                    if (controller.inlineEditingNodeId != null || controller.resizePreviewNodeId != null) {
                        return@awaitEachGesture
                    }
                    val mods = currentEvent.keyboardModifiers
                    if (mods.isCtrlPressed || mods.isMetaPressed) return@awaitEachGesture
                    val shift = mods.isShiftPressed
                    val scale = latestSafeScale
                    val nodes = latestResolvedNodes
                    val stagePt = Offset(down.position.x / scale, down.position.y / scale)
                    if (interactiveRegistry?.hitTestEntry(stagePt) != null) return@awaitEachGesture
                    val hitNode = hitTestNonInteractiveNodeAt(stagePt, nodes)
                    val pointerId = down.id
                    val slop = viewConfiguration.touchSlop

                    if (hitNode == null) {
                        // Empty canvas: rubber-band marquee (or click to clear the selection).
                        var dragging = false
                        var upSeen = false
                        try {
                            while (true) {
                                val event = awaitPointerEvent(pass = PointerEventPass.Main)
                                val change = event.changes.firstOrNull { it.id == pointerId } ?: break
                                if (!change.pressed) {
                                    upSeen = true
                                    if (dragging) change.consume()
                                    break
                                }
                                val cur = Offset(change.position.x / latestSafeScale, change.position.y / latestSafeScale)
                                if (!dragging && hypot((cur.x - stagePt.x) * latestSafeScale, (cur.y - stagePt.y) * latestSafeScale) > slop) {
                                    dragging = true
                                }
                                if (dragging) {
                                    change.consume()
                                    marqueeStage = Rect(
                                        minOf(stagePt.x, cur.x), minOf(stagePt.y, cur.y),
                                        maxOf(stagePt.x, cur.x), maxOf(stagePt.y, cur.y),
                                    )
                                }
                            }
                            val rect = marqueeStage
                            if (dragging && upSeen && rect != null) {
                                val picked = filterMarqueePicks(
                                    picked = StageSelectionModel.resolveMarquee(rect, buildSnapSnapshots(latestResolvedNodes)),
                                    topLevelIds = latestTopLevelIds,
                                    presentIds = latestResolvedNodes.mapTo(HashSet()) { it.id },
                                )
                                val next = if (shift) LinkedHashSet(controller.selectedNodeIds + picked) else LinkedHashSet(picked)
                                controller.selectNodes(next, primaryId = picked.firstOrNull() ?: controller.selectedNodeId)
                            } else if (!dragging && upSeen && !shift && controller.selectedNodeIds.isNotEmpty()) {
                                controller.selectNode(null)
                            }
                        } finally {
                            marqueeStage = null
                        }
                        return@awaitEachGesture
                    }

                    down.consume()
                    var dragging = false
                    var rawStageDx = 0f
                    var rawStageDy = 0f
                    var snappedStageDx = 0.0
                    var snappedStageDy = 0.0
                    var upUptimeMs: Long? = null
                    var dragStartBounds: Rect? = null
                    var movingSet: Set<String> = emptySet()

                    try {
                        while (true) {
                            val event = awaitPointerEvent(pass = PointerEventPass.Main)
                            val change = event.changes.firstOrNull { it.id == pointerId } ?: break
                            if (!change.pressed) {
                                change.consume()
                                upUptimeMs = change.uptimeMillis
                                break
                            }
                            val delta = change.positionChange()
                            if (delta != Offset.Zero) {
                                val curScale = latestSafeScale
                                rawStageDx += delta.x / curScale
                                rawStageDy += delta.y / curScale
                                if (!dragging && hypot(rawStageDx * curScale, rawStageDy * curScale) > slop) {
                                    dragging = true
                                    if (hitNode.id !in controller.selectedNodeIds) controller.selectNode(hitNode.id, additive = shift)
                                    controller.beginMultiDrag(hitNode.id)
                                    movingSet = controller.dragPreviewNodeIds
                                    dragStartBounds = StageArrangeMath.unionBounds(
                                        latestResolvedNodes.filter { it.id in movingSet }.mapNotNull { nodeStageRect(it, curScale) },
                                    )
                                }
                                if (dragging) {
                                    change.consume()
                                    val snapResult = computeDragSnap(
                                        draggedElementId = hitNode.id,
                                        startBounds = dragStartBounds ?: nodeStageRect(hitNode, curScale) ?: Rect.Zero,
                                        rawDeltaStagePx = Offset(rawStageDx, rawStageDy),
                                        allNodes = buildSnapSnapshots(latestResolvedNodes).filter { it.elementId !in movingSet },
                                        stageWidthPx = latestStageWidth,
                                        stageHeightPx = latestStageHeight,
                                        viewportScale = curScale,
                                        disableSnap = event.keyboardModifiers.isAltPressed,
                                        axisLock = event.keyboardModifiers.isShiftPressed,
                                    )
                                    snappedStageDx = snapResult.deltaStagePx.x.toDouble()
                                    snappedStageDy = snapResult.deltaStagePx.y.toDouble()
                                    activeSnapGuides = snapResult.guides
                                    controller.updateMultiDrag(snappedStageDx, snappedStageDy)
                                }
                            }
                        }

                        activeSnapGuides = emptyList()
                        if (dragging) {
                            if (upUptimeMs != null) {
                                controller.commitMultiDrag(snappedStageDx, snappedStageDy)
                            } else {
                                controller.cancelStageDragPreview()
                            }
                        } else if (upUptimeMs != null) {
                            val isDoubleTap = lastTapNodeId == hitNode.id &&
                                lastTapUptimeMs >= 0L &&
                                (upUptimeMs - lastTapUptimeMs) <= viewConfiguration.doubleTapTimeoutMillis
                            if (isDoubleTap) {
                                lastTapNodeId = null
                                lastTapUptimeMs = -1L
                            } else {
                                lastTapNodeId = hitNode.id
                                lastTapUptimeMs = upUptimeMs
                            }
                            when {
                                shift -> controller.selectNode(hitNode.id, additive = true)
                                isDoubleTap && hitNode.text != null -> controller.startInlineTextEdit(hitNode.id)
                                else -> controller.selectNode(hitNode.id)
                            }
                        }
                    } finally {
                        if (dragging && upUptimeMs == null) {
                            activeSnapGuides = emptyList()
                            controller.cancelStageDragPreview()
                        }
                    }
                }
            }
            .testTag("ciacola-doc-stage-direct-edit"),
    ) {
        content()

        if (activeSnapGuides.isNotEmpty()) {
            Canvas(Modifier.fillMaxSize().testTag("ciacola-doc-stage-snap-guides")) {
                val sx = size.width / stageWidth
                val sy = size.height / stageHeight
                withTransform({ scale(scaleX = sx, scaleY = sy, pivot = Offset.Zero) }) {
                    drawSnapGuides(guides = activeSnapGuides, viewportScale = safeScale)
                }
            }
        }

        StageSelectionChrome(
            controller = controller,
            resolvedNodes = resolvedNodes,
            safeScale = safeScale,
            overlaySizePx = overlaySizePx,
            nodeRect = { nodeStageRect(it, safeScale) },
        )

        marqueeStage?.let { m -> MarqueeRect(m, safeScale) }

        val editingNodeId = controller.inlineEditingNodeId
        if (editingNodeId != null) {
            val targetNode = resolvedNodes.firstOrNull { it.id == editingNodeId }
            val targetStage = targetNode?.let { nodeStageRect(it, safeScale) }
                ?: inspectionRegistry?.nodes?.get(editingNodeId)?.stageBounds
                ?: Rect(40f, 40f, 280f, 104f)
            DocumentInlineTextEditorPopover(
                controller = controller,
                editingNodeId = editingNodeId,
                displayName = targetNode?.let { InspectorDisplayNames.nodeDisplayName(it) } ?: editingNodeId,
                targetRectPx = targetStage.scaled(safeScale),
                siblingTextRectsPx = resolvedNodes.mapNotNull { sibling ->
                    if (sibling.id == editingNodeId || sibling.text == null) null else nodeStageRect(sibling, safeScale)?.scaled(safeScale)
                },
                overlaySizePx = overlaySizePx,
            )
        }

        if (controller.remapNotices.isNotEmpty()) {
            val warning = JewelTheme.globalColors.outlines.warning
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(12.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(JewelTheme.globalColors.panelBackground)
                    .border(1.dp, warning, RoundedCornerShape(6.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp)
                    .testTag("ciacola-stage-remap-toast"),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Pruned ${controller.remapNotices.size} ambiguous manual fragment delay(s): " +
                        controller.remapNotices.joinToString(", ") { it.oldAddress },
                    style = inspectorBodyStyle(),
                )
                OutlinedButton(
                    onClick = { controller.dismissRemapNotices() },
                    modifier = Modifier.testTag("ciacola-stage-remap-toast-dismiss"),
                ) { Text("OK", style = inspectorBodyStyle()) }
            }
        }
    }
}

/**
 * Marquee candidates limited to top-level nodes PRESENT on the current build (like `⌘A`), so an
 * absent / invisible node can never be rubber-band selected and moved.
 */
internal fun filterMarqueePicks(picked: List<String>, topLevelIds: Set<String>, presentIds: Set<String>): List<String> =
    picked.filter { it in topLevelIds && it in presentIds }

/** Converts a stage-unit drag delta into the `StageDragPreview` dp offset at [stageScale]. */
internal fun stageDragPreviewOffset(dxStage: Double, dyStage: Double, stageScale: Float, density: Float): DpOffset =
    DpOffset(
        x = (dxStage.toFloat() * stageScale / density).dp,
        y = (dyStage.toFloat() * stageScale / density).dp,
    )

/** Test-tag key of the union resize handles (`ciacola-stage-resize-handle-selection-NW`, …). */
internal const val MULTI_SELECTION_HANDLE_KEY = "selection"

internal fun Rect.scaled(scale: Float): Rect = Rect(left * scale, top * scale, right * scale, bottom * scale)

@Composable
private fun MarqueeRect(marqueeStage: Rect, scale: Float) {
    val accent = inspectorAccent()
    Canvas(Modifier.fillMaxSize().testTag("ciacola-doc-stage-marquee")) {
        val r = marqueeStage.scaled(scale)
        drawRect(accent.copy(alpha = 0.10f), topLeft = r.topLeft, size = r.size)
        drawRect(accent.copy(alpha = 0.9f), topLeft = r.topLeft, size = r.size, style = Stroke(width = 1.dp.toPx()))
    }
}

/**
 * Selection outlines (1dp accent + 1dp halo), the unified multi-selection bounding box, resize
 * handles for a single selection, and the dimension pill while dragging or resizing.
 */
@Composable
private fun StageSelectionChrome(
    controller: DocumentElementInspectorController,
    resolvedNodes: List<NodeSpec>,
    safeScale: Float,
    overlaySizePx: IntSize,
    nodeRect: (NodeSpec) -> Rect?,
) {
    val density = LocalDensity.current
    val accent = inspectorAccent()
    val halo = if (JewelTheme.isDark) Color(0xB3000000) else Color(0xCCFFFFFF)
    val selectedIds = controller.selectedNodeIds
    val movingIds = controller.dragPreviewNodeIds
    val dx = controller.dragPreviewDeltaX.toFloat()
    val dy = controller.dragPreviewDeltaY.toFloat()

    val displayRects = LinkedHashMap<String, Rect>()
    for (node in resolvedNodes) {
        var r = nodeRect(node) ?: continue
        if (node.id == controller.resizePreviewNodeId) {
            controller.resizePreviewFrame?.let { f ->
                val x = (f.x ?: r.left.toDouble()).toFloat()
                val y = (f.y ?: r.top.toDouble()).toFloat()
                r = Rect(x, y, x + (f.w ?: r.width.toDouble()).toFloat(), y + (f.h ?: r.height.toDouble()).toFloat())
            }
        }
        if (node.id in movingIds) r = r.translate(dx, dy)
        displayRects[node.id] = r
    }

    // Per-node hit/test boxes (no chrome) keep stable test tags for automation.
    for ((id, r) in displayRects) {
        val px = r.scaled(safeScale)
        Box(
            modifier = Modifier
                .offset { IntOffset(px.left.roundToInt(), px.top.roundToInt()) }
                .size(with(density) { px.width.coerceAtLeast(16f).toDp() }, with(density) { px.height.coerceAtLeast(16f).toDp() })
                .then(if (id in selectedIds) Modifier.testTag("ciacola-doc-stage-handles-$id") else Modifier)
                .testTag("ciacola-doc-stage-node-$id"),
        )
    }

    val selectedStageRects = selectedIds.mapNotNull { id -> displayRects[id]?.let { id to it } }.toMap()
    val selectedRects = selectedStageRects.values.map { it.scaled(safeScale) }
    val unionStage = if (selectedStageRects.size > 1) StageArrangeMath.unionBounds(selectedStageRects.values) else null
    val union = unionStage?.scaled(safeScale)
    if (selectedRects.isNotEmpty() && controller.inlineEditingNodeId == null) {
        Canvas(Modifier.fillMaxSize().testTag("ciacola-doc-stage-selection-outline")) {
            val stroke = 1.dp.toPx()
            // Multi-selection: ONE union frame (no per-node outlines competing with it).
            drawHaloOutline(union ?: selectedRects.first(), accent, halo, stroke)
        }
        if (union != null) {
            Box(
                Modifier
                    .offset { IntOffset(union.left.roundToInt(), union.top.roundToInt()) }
                    .size(with(density) { union.width.toDp() }, with(density) { union.height.toDp() })
                    .testTag("ciacola-doc-stage-multi-selection-bounds"),
            )
        }
    }
    if (unionStage != null && controller.inlineEditingNodeId == null && movingIds.isEmpty()) {
        // Eight outside handles on the union frame resize the whole selection proportionally.
        StageSelectionHandlesOverlay(
            nodeId = MULTI_SELECTION_HANDLE_KEY,
            frameX = unionStage.left.toDouble(),
            frameY = unionStage.top.toDouble(),
            frameW = unionStage.width.toDouble(),
            frameH = unionStage.height.toDouble(),
            stageScale = safeScale,
            controller = controller,
            groupStartRects = selectedStageRects,
        )
    }

    val single = selectedIds.singleOrNull()
    val singleRect = single?.let { displayRects[it] }
    if (single != null && singleRect != null && controller.inlineEditingNodeId != single) {
        StageSelectionHandlesOverlay(
            nodeId = single,
            frameX = singleRect.left.toDouble(),
            frameY = singleRect.top.toDouble(),
            frameW = singleRect.width.toDouble(),
            frameH = singleRect.height.toDouble(),
            stageScale = safeScale,
            controller = controller,
        )
    }

    val isMoving = movingIds.isNotEmpty() && (dx != 0f || dy != 0f)
    val resizingId = controller.resizePreviewNodeId
    if (isMoving || resizingId != null) {
        val activeIds = if (isMoving) movingIds else setOf(resizingId!!)
        val activeStage = StageArrangeMath.unionBounds(activeIds.mapNotNull { displayRects[it] }) ?: return
        val text = if (isMoving) {
            "X ${formatHalfUnit(activeStage.left.toDouble())}  Y ${formatHalfUnit(activeStage.top.toDouble())}"
        } else {
            "${formatHalfUnit(activeStage.width.toDouble())} × ${formatHalfUnit(activeStage.height.toDouble())}"
        }
        StageDimensionPill(
            text = text,
            selectionPx = activeStage.scaled(safeScale),
            overlaySizePx = overlaySizePx,
            obstaclesPx = displayRects.filterKeys { it !in activeIds }.values.map { it.scaled(safeScale) },
        )
    }
}
