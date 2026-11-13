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
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.bnorm.storyboard.easel.LocalPresentingFullscreen
import dev.bnorm.storyboard.easel.LocalStoryboard
import dev.sebastiano.ciacola.inspector.LocalStageInspectionRegistry
import dev.sebastiano.ciacola.inspector.LocalStageRootCoordinates
import dev.sebastiano.ciacola.inspector.StageNodeSnapshot
import dev.sebastiano.ciacola.inspector.StageRootCoordinatesHolder
import dev.sebastiano.ciacola.ui.CiacolaJewelTheme
import dev.sebastiano.ciacola.ui.rememberAccessibleFocusRingColor
import kotlin.math.roundToInt
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextArea
import org.jetbrains.jewel.ui.component.Tooltip
import org.jetbrains.jewel.ui.typography

internal fun findEditableNodeAt(
    stagePoint: Offset,
    nodes: List<StageNodeSnapshot>,
): StageNodeSnapshot? = nodes
    .filter { node ->
        node.isEditable &&
            node.stageBounds.width > 0f &&
            node.stageBounds.height > 0f &&
            node.stageBounds.inflate(4f).contains(stagePoint)
    }
    .minByOrNull { it.stageBounds.width * it.stageBounds.height }

/**
 * Stage decorator layer that exposes [LocalStageInspectionRegistry] and, strictly when
 * [ElementEditorController.editorActive] is `true` and fullscreen Show Mode is inactive, enables
 * direct click selection, double-click inline text editing, and arrow-key nudging.
 */
@Composable
internal fun StageDirectEditLayer(
    controller: ElementEditorController,
    content: @Composable () -> Unit,
) {
    val existingRoot = LocalStageRootCoordinates.current
    val stageRootHolder = existingRoot ?: remember { StageRootCoordinatesHolder() }
    val active = controller.editorActive && !LocalPresentingFullscreen.current
    val storyboard = LocalStoryboard.current
    val formatSize = storyboard?.format?.size ?: androidx.compose.ui.unit.IntSize(1920, 1080)
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
        }
    }

    var lastClickTimeMs by remember { mutableLongStateOf(0L) }
    var lastClickElementId by remember { mutableStateOf<String?>(null) }
    var lastClickPos by remember { mutableStateOf(Offset.Zero) }
    var pressStartPos by remember { mutableStateOf<Offset?>(null) }
    var inlineEditorBounds by remember { mutableStateOf<Rect?>(null) }

    fun handleStageKeyEvent(ev: KeyEvent): Boolean {
        if (!active || controller.inlineEditingElementId != null) return false
        if (controller.isExternalTextInputActive()) return false
        val selectedId = controller.selectedElementId ?: return false
        if (ev.type != KeyEventType.KeyDown) return false

        val step = if (ev.isShiftPressed) 10f else 1f
        return when {
            ev.key == Key.DirectionLeft -> {
                controller.nudgeSelected(-step, 0f)
                true
            }
            ev.key == Key.DirectionRight -> {
                controller.nudgeSelected(step, 0f)
                true
            }
            ev.key == Key.DirectionUp -> {
                controller.nudgeSelected(0f, -step)
                true
            }
            ev.key == Key.DirectionDown -> {
                controller.nudgeSelected(0f, step)
                true
            }
            ev.key == Key.Escape -> {
                controller.selectElement(null)
                true
            }
            ev.isMetaPressed && (ev.key == Key.Backspace || ev.key == Key.Delete) -> {
                controller.resetSelectedElement()
                true
            }
            ev.isMetaPressed && ev.key == Key.Z -> {
                if (ev.isShiftPressed) controller.redo() else controller.undo()
                true
            }
            else -> false
        }
    }

    val interactionModifier = if (!active) {
        Modifier
    } else {
        Modifier
            .focusRequester(stageFocusRequester)
            .focusable()
            .onPreviewKeyEvent(::handleStageKeyEvent)
            .pointerInput(controller) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val mods = event.keyboardModifiers
                        val blocked = controller.suppressPicking() ||
                            mods.isPointerCtrlPressed ||
                            mods.isPointerMetaPressed ||
                            mods.isPointerAltPressed
                        val pos = event.changes.firstOrNull()?.position
                        if (blocked) {
                            if (controller.hoveredElementId != null) {
                                controller.hoveredElementId = null
                            }
                            pressStartPos = null
                            continue
                        }

                        val slideNodes = controller.slidesNodesForCurrentSlide()
                        when (event.type) {
                            PointerEventType.Move -> {
                                controller.hoveredElementId = if (pos != null &&
                                    !controller.isStagePointOverHud(pos) &&
                                    controller.inlineEditingElementId == null
                                ) {
                                    findEditableNodeAt(pos, slideNodes)?.elementId
                                } else {
                                    null
                                }
                            }

                            PointerEventType.Exit -> {
                                controller.hoveredElementId = null
                            }

                            PointerEventType.Press -> {
                                if (pos != null &&
                                    event.buttons.isPrimaryPressed &&
                                    !mods.isPointerShiftPressed &&
                                    !controller.isStagePointOverHud(pos)
                                ) {
                                    val inlineId = controller.inlineEditingElementId
                                    if (inlineId != null) {
                                        val insideInline = inlineEditorBounds?.contains(pos) == true
                                        if (!insideInline) {
                                            controller.commitInlineEdit(restoreStageFocus = true)
                                        }
                                        continue
                                    }
                                    pressStartPos = pos
                                }
                            }

                            PointerEventType.Release -> {
                                val start = pressStartPos
                                pressStartPos = null
                                if (start != null && pos != null && (pos - start).getDistance() <= 6f) {
                                    val hit = findEditableNodeAt(pos, slideNodes)
                                    val nowMs = System.currentTimeMillis()
                                    val isDoubleClick = hit != null &&
                                        hit.elementId == lastClickElementId &&
                                        (nowMs - lastClickTimeMs) <= 420L &&
                                        (pos - lastClickPos).getDistance() <= 16f

                                    if (isDoubleClick && controller.canInlineEdit(hit.elementId)) {
                                        lastClickElementId = null
                                        lastClickTimeMs = 0L
                                        event.changes.forEach { it.consume() }
                                        controller.startInlineEdit(hit.elementId)
                                    } else {
                                        lastClickTimeMs = nowMs
                                        lastClickElementId = hit?.elementId
                                        lastClickPos = pos
                                        if (controller.elementPickingEnabled) {
                                            controller.selectElement(hit?.elementId)
                                            if (hit != null) {
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
        ) {
            content()
        }

        if (active) {
            CiacolaJewelTheme(isDark = controller.isDarkThemeProvider()) {
                StageSelectionAndInlineOverlay(
                    controller = controller,
                    stageRootHolder = stageRootHolder,
                    stageWidthPx = formatSize.width.toFloat(),
                    stageHeightPx = formatSize.height.toFloat(),
                    onInlineBoundsChanged = { inlineEditorBounds = it },
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
    stageWidthPx: Float,
    stageHeightPx: Float,
    onInlineBoundsChanged: (Rect?) -> Unit,
) {
    val slideNodes = controller.slidesNodesForCurrentSlide()
    val hoveredNode = controller.hoveredElementId?.let { id ->
        slideNodes.firstOrNull { it.elementId == id }
    }
    val selectedNode = controller.selectedElementId?.let { id ->
        slideNodes.firstOrNull { it.elementId == id }
    }
    val inlineId = controller.inlineEditingElementId
    val inlineNode = inlineId?.let { id ->
        slideNodes.firstOrNull { it.elementId == id } ?: controller.inspectionRegistry.nodes[id]
    }
    if (inlineId == null || inlineNode == null) {
        onInlineBoundsChanged(null)
    }
    val textMeasurer = rememberTextMeasurer()
    val accent = Color(0xFF3D9EFF)
    val hoverAccent = accent.copy(alpha = 0.65f)

    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .testTag("ciacola-direct-edit-overlay"),
    ) {
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

        if (selectedNode != null && inlineId == null) {
            val r = selectedNode.stageBounds.inflate(6f)
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
                style = Stroke(width = 3f),
            )

            // 4 crisp corner selection handles
            val handleSize = 10f
            val half = handleSize / 2f
            val corners = listOf(
                Offset(r.left, r.top),
                Offset(r.right, r.top),
                Offset(r.left, r.bottom),
                Offset(r.right, r.bottom),
            )
            for (c in corners) {
                drawRect(
                    color = Color.White,
                    topLeft = Offset(c.x - half, c.y - half),
                    size = Size(handleSize, handleSize),
                )
                drawRect(
                    color = accent,
                    topLeft = Offset(c.x - half, c.y - half),
                    size = Size(handleSize, handleSize),
                    style = Stroke(width = 2f),
                )
            }

            // High-contrast floating tag chip
            val chipLabel = "${selectedNode.elementId} · ${selectedNode.kind}"
            val layout = textMeasurer.measure(
                text = chipLabel,
                style = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = Color.White),
                maxLines = 1,
            )
            val padH = 12f
            val padV = 6f
            val chipW = layout.size.width + padH * 2
            val chipH = layout.size.height + padV * 2
            val aboveY = r.top - chipH - 8f
            val chipTop = if (aboveY >= 4f) aboveY else (r.bottom + 8f).coerceAtMost(size.height - chipH - 4f)
            val chipLeft = r.left.coerceIn(4f, (size.width - chipW - 4f).coerceAtLeast(4f))
            drawRoundRect(
                color = Color(0xFF0F2647),
                topLeft = Offset(chipLeft, chipTop),
                size = Size(chipW, chipH),
                cornerRadius = CornerRadius(8f, 8f),
            )
            drawRoundRect(
                color = accent,
                topLeft = Offset(chipLeft, chipTop),
                size = Size(chipW, chipH),
                cornerRadius = CornerRadius(8f, 8f),
                style = Stroke(width = 1.5f),
            )
            drawText(layout, topLeft = Offset(chipLeft + padH, chipTop + padV))
        }
    }

    if (inlineId != null && inlineNode != null) {
        InlineStageTextEditor(
            controller = controller,
            elementId = inlineId,
            targetBounds = inlineNode.stageBounds,
            stageRootHolder = stageRootHolder,
            stageWidthPx = stageWidthPx,
            stageHeightPx = stageHeightPx,
            onBoundsChanged = onInlineBoundsChanged,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun InlineStageTextEditor(
    controller: ElementEditorController,
    elementId: String,
    targetBounds: Rect,
    stageRootHolder: StageRootCoordinatesHolder,
    stageWidthPx: Float,
    stageHeightPx: Float,
    onBoundsChanged: (Rect?) -> Unit,
) {
    val density = LocalDensity.current
    val globalColors = JewelTheme.globalColors
    val focusRingColor = rememberAccessibleFocusRingColor()
    val focusRequester = remember(elementId) { FocusRequester() }
    val textFieldState = rememberTextFieldState(initialText = controller.inlineDraftText)
    var editorFocused by remember(elementId) { mutableStateOf(false) }

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

    LaunchedEffect(textFieldState.text.toString()) {
        controller.inlineDraftText = textFieldState.text.toString()
    }

    val editorWidthDp = with(density) { targetBounds.width.toDp() }.coerceIn(280.dp, 680.dp)
    val editorWidthPx = with(density) { editorWidthDp.toPx() }
    val approxHeightPx = with(density) { 128.dp.toPx() }
    val xPx = targetBounds.left.coerceIn(16f, (stageWidthPx - editorWidthPx - 16f).coerceAtLeast(16f))
    val yPx = targetBounds.top.coerceIn(16f, (stageHeightPx - approxHeightPx - 16f).coerceAtLeast(16f))

    fun handleInlineKey(ev: KeyEvent): Boolean {
        if (ev.type != KeyEventType.KeyDown) return false
        if (ev.key == Key.Escape) {
            controller.cancelInlineEdit(restoreStageFocus = true)
            return true
        }
        if (ev.key == Key.Enter || ev.key == Key.NumPadEnter) {
            if (ev.isMetaPressed || (!ev.isShiftPressed && !ev.isAltPressed)) {
                controller.commitInlineEdit(restoreStageFocus = true)
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
            .offset { IntOffset(xPx.roundToInt(), yPx.roundToInt()) }
            .widthIn(min = editorWidthDp, max = 680.dp)
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
            .testTag("ciacola-inline-stage-editor"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Edit $elementId",
                style = JewelTheme.typography.small.copy(fontWeight = FontWeight.SemiBold),
                color = globalColors.text.normal,
            )
            Text(
                text = "↵ Save · ⇧↵ Newline · Esc Cancel",
                style = JewelTheme.typography.small,
                color = globalColors.text.info,
            )
        }
        TextArea(
            state = textFieldState,
            lineLimits = TextFieldLineLimits.MultiLine(minHeightInLines = 1, maxHeightInLines = 6),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester)
                .onFocusChanged { if (it.isFocused) editorFocused = true }
                .testTag("ciacola-inline-stage-editor-input"),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Tooltip(tooltip = { Text("Discard changes and restore stage focus (Esc)") }) {
                OutlinedButton(
                    onClick = { controller.cancelInlineEdit(restoreStageFocus = true) },
                    modifier = Modifier.testTag("ciacola-inline-stage-editor-cancel"),
                ) {
                    Text("Cancel", style = JewelTheme.typography.small)
                }
            }
            Tooltip(tooltip = { Text("Apply text override (Enter / ⌘Enter)") }) {
                DefaultButton(
                    onClick = { controller.commitInlineEdit(restoreStageFocus = true) },
                    modifier = Modifier.testTag("ciacola-inline-stage-editor-save"),
                ) {
                    Text("Apply", style = JewelTheme.typography.small)
                }
            }
        }
    }
}
