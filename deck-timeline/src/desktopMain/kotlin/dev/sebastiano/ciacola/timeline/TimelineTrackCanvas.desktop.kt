package dev.sebastiano.ciacola.timeline

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.Scrollable2DState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.rememberScrollable2DState
import androidx.compose.foundation.gestures.scrollable2D
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.isAltPressed
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.roundToInt
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.theme.colorPalette

@Composable
internal fun MasterDeckTimelineCanvas(
    controller: TimelineStudioController,
    snapshot: DeckTimelineSnapshot,
    surface: TimelineSurface,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val textMeasurer = rememberTextMeasurer()
    val globalColors = JewelTheme.globalColors
    val palette = JewelTheme.colorPalette
    val isDark = JewelTheme.isDark
    val viewport = controller.viewport
    val latestSnapshot by rememberUpdatedState(snapshot)
    controller.overridesStore.parameterRevision
    val overridesFile = controller.overridesStore.data()
    val liveOverrides = overridesFile.tracks
    val liveLoopPeriods = overridesFile.loopPeriodsMs
    val latestOverrides by rememberUpdatedState(liveOverrides)

    val slideRibbonPx = with(density) { SLIDE_RIBBON_HEIGHT.toPx() }
    val rulerHeightPx = with(density) { TIME_RULER_HEIGHT.toPx() }
    val headerTotalPx = slideRibbonPx + rulerHeightPx
    val rowHeightPx = with(density) { TRACK_ROW_HEIGHT.toPx() }
    var canvasHeightPx by remember { mutableFloatStateOf(0f) }
    var hoverIcon by remember { mutableStateOf(PointerIcon.Default) }

    DisposableEffect(viewport, surface) {
        onDispose { viewport.unregisterSurface(surface) }
    }

    val surfaceWidthDp = viewport.viewportWidthDp(surface)
    LaunchedEffect(surface, snapshot.totalDurationMs, snapshot.stateSpans.isNotEmpty(), surfaceWidthDp, viewport.isFitLocked) {
        if (snapshot.stateSpans.isNotEmpty() && surfaceWidthDp > 0f && snapshot.totalDurationMs > 0) {
            viewport.contentDurationMs = snapshot.totalDurationMs
            viewport.applyStickyFitIfNeeded(surface)
        }
    }

    val colors = remember(JewelTheme.instanceUuid, isDark) {
        buildStudioCanvasPaletteForTheme(
            isDark = isDark,
            panelBg = globalColors.panelBackground,
            borderNormal = globalColors.borders.normal,
            textPrimary = globalColors.text.normal,
            focusedOutline = globalColors.outlines.focused,
            injectedBeatLine = palette.yellowOrNull(if (isDark) 6 else 5) ?: Color(0xFFE0A82E),
            boundaryLine = palette.tealOrNull(if (isDark) 6 else 5) ?: Color(0xFF2FA896),
            playhead = palette.redOrNull(if (isDark) 6 else 5) ?: Color(0xFFE5534B),
        )
    }

    val rulerTextStyle = TextStyle(fontSize = 9.sp, fontFamily = FontFamily.Monospace, color = colors.textSecondary)

    val playheadMs = controller.currentPlayheadTimeMs
    // Auto-scroll when playing, advancing, or jumping moves the playhead off-screen (but not during scrub drags).
    LaunchedEffect(playheadMs, controller.isScrubDragging, surface, snapshot.totalDurationMs) {
        if (!controller.isScrubDragging) {
            viewport.ensureTimeVisible(playheadMs.toFloat(), surface = surface)
        }
    }
    LaunchedEffect(viewport.pendingRevealTrackId, snapshot) {
        val revealId = viewport.pendingRevealTrackId ?: return@LaunchedEffect
        val rowIndex = snapshot.tracks.indexOfFirst { it.trackId == revealId }
            .takeIf { it >= 0 } ?: snapshot.tracks.indexOfFirst { it.trackId == revealId.substringBefore("#") }
        if (rowIndex >= 0) {
            val rowTop = (rowIndex * rowHeightPx).roundToInt()
            val visibleHeight = (canvasHeightPx - headerTotalPx).roundToInt().coerceAtLeast(rowHeightPx.roundToInt())
            val current = viewport.verticalScroll.value
            if (rowTop < current || rowTop + rowHeightPx > current + visibleHeight) {
                viewport.verticalScroll.animateScrollTo((rowTop - visibleHeight / 3).coerceAtLeast(0))
            }
            val block = snapshot.tracks[rowIndex].blocks.firstOrNull()
            if (block != null) viewport.ensureTimeVisible(block.startMs.toFloat(), surface = surface)
        }
        viewport.pendingRevealTrackId = null
    }

    // Plain wheel / trackpad scrolling in both axes (CMP 1.13 scrollable2D).
    val scrollState: Scrollable2DState = rememberScrollable2DState { delta ->
        viewport.markSurfaceActive(surface)
        val consumedX = -viewport.scrollHorizontallyBy(-delta.x, surface)
        val consumedY = -viewport.verticalScroll.dispatchRawDelta(-delta.y)
        Offset(consumedX, consumedY)
    }
    val canvasFocusRequester = remember { FocusRequester() }
    val idx = controller.animatic.targetIndex
    val sceneBeatsCount = controller.animatic.storyboard.scenes.getOrNull(idx.sceneIndex)?.frames?.size ?: 1
    val playheadStateDesc = "Playhead at ${formatMillisForAccessibility(playheadMs)}, " +
        "Slide ${idx.sceneIndex + 1} of ${snapshot.slides.size}, Beat ${idx.frameIndex + 1} of $sceneBeatsCount"

    Box(modifier = modifier) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .clipToBounds()
                .focusRequester(canvasFocusRequester)
                .focusable()
                .onSizeChanged {
                    viewport.updateSurfaceMetrics(surface, it.width.toFloat(), density.density)
                    canvasHeightPx = it.height.toFloat()
                }
                .pointerHoverIcon(if (controller.spaceHeld) PointerIcon.Hand else hoverIcon)
                // After Effects modifiers: Alt/Ctrl+wheel zooms time at the cursor, Shift+wheel scrolls
                // horizontally; middle-drag or Space+drag pans (hand tool). Handled on the Initial pass,
                // before scrollable2D and the scrub/edit gestures see the events.
                .pointerInput(viewport, surface) {
                    awaitPointerEventScope {
                        var panning = false
                        var last = Offset.Zero
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change = event.changes.firstOrNull() ?: continue
                            when (event.type) {
                                PointerEventType.Scroll -> {
                                    viewport.markSurfaceActive(surface)
                                    val mods = event.keyboardModifiers
                                    val delta = change.scrollDelta
                                    when {
                                        mods.isAltPressed || mods.isCtrlPressed -> {
                                            val steps = if (delta.y != 0f) delta.y else delta.x
                                            viewport.zoomBy(exp(-steps * 0.15f), change.position.x, surface)
                                            change.consume()
                                        }
                                        mods.isShiftPressed -> {
                                            val steps = if (delta.x != 0f) delta.x else delta.y
                                            viewport.scrollHorizontallyBy(steps * 48f, surface)
                                            change.consume()
                                        }
                                    }
                                }
                                PointerEventType.Press -> {
                                    viewport.markSurfaceActive(surface)
                                    runCatching { canvasFocusRequester.requestFocus() }
                                    if (event.buttons.isTertiaryPressed || controller.spaceHeld) {
                                        panning = true
                                        controller.spaceUsedForPan = true
                                        last = change.position
                                        change.consume()
                                    }
                                }
                                PointerEventType.Move -> {
                                    if (panning) {
                                        val d = change.position - last
                                        last = change.position
                                        viewport.scrollHorizontallyBy(-d.x, surface)
                                        viewport.verticalScroll.dispatchRawDelta(-d.y)
                                        change.consume()
                                    } else if (!event.buttons.isPrimaryPressed && !controller.spaceHeld) {
                                        val hit = resolveTimelineBlockHit(
                                            pos = change.position,
                                            snapshot = latestSnapshot,
                                            viewport = viewport,
                                            surface = surface,
                                            headerTotalPx = headerTotalPx,
                                            rowHeightPx = rowHeightPx,
                                            liveOverrides = latestOverrides,
                                        )
                                        hoverIcon = pointerIconForDragMode(hit?.dragMode)
                                    }
                                }
                                PointerEventType.Exit -> {
                                    if (!panning) {
                                        hoverIcon = PointerIcon.Default
                                    }
                                }
                                PointerEventType.Release -> if (panning) {
                                    panning = false
                                    change.consume()
                                }
                            }
                        }
                    }
                }
                .scrollable2D(scrollState)
                .testTag("ciacola-timeline-canvas")
                .semantics {
                    contentDescription = "Timeline canvas and time ruler"
                    stateDescription = playheadStateDesc
                }
                // Keyed on stable controller/viewport/surface (reading latestSnapshot) so in-flight block
                // edge/body drags are never cancelled when an override edit refreshes the snapshot.
                .pointerInput(controller, viewport, surface) {
                    var lastClickBlockId: String? = null
                    var lastClickMillis = 0L

                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val buttons = currentEvent.buttons
                        val startPos = down.position
                        val currentSnapshot = latestSnapshot
                        // Panning (middle button / Space held) is handled by the Initial-pass handler.
                        if (down.isConsumed || buttons.isTertiaryPressed || controller.spaceHeld) return@awaitEachGesture
                        if (!buttons.isPrimaryPressed) return@awaitEachGesture
                        down.consume()

                        fun timeAt(x: Float): Int = viewport.timeForX(x, surface).roundToInt()

                        if (startPos.y <= headerTotalPx) {
                            controller.beginScrubDrag()
                            try {
                                controller.scrubToTimeMs(timeAt(startPos.x))
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull() ?: break
                                    if (!change.pressed) break
                                    change.consume()
                                    // Auto-scroll when dragging the playhead past either edge.
                                    val edge = 24f
                                    if (change.position.x > size.width - edge) viewport.scrollHorizontallyBy(12f, surface)
                                    if (change.position.x < edge) viewport.scrollHorizontallyBy(-12f, surface)
                                    controller.scrubToTimeMs(timeAt(change.position.x))
                                }
                            } finally {
                                controller.endScrubDrag()
                            }
                            return@awaitEachGesture
                        }

                        val blockHit = resolveTimelineBlockHit(
                            pos = startPos,
                            snapshot = currentSnapshot,
                            viewport = viewport,
                            surface = surface,
                            headerTotalPx = headerTotalPx,
                            rowHeightPx = rowHeightPx,
                            liveOverrides = latestOverrides,
                        )

                        if (blockHit == null) {
                            controller.beginScrubDrag()
                            try {
                                controller.scrubToTimeMs(timeAt(startPos.x))
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull() ?: break
                                    if (!change.pressed) break
                                    change.consume()
                                    controller.scrubToTimeMs(timeAt(change.position.x))
                                }
                            } finally {
                                controller.endScrubDrag()
                            }
                            return@awaitEachGesture
                        }

                        val hitRow = blockHit.row
                        val hitBlock = blockHit.block
                        val dragMode = blockHit.dragMode

                        val nowWall = System.currentTimeMillis()
                        if (hitBlock.loopId != null && lastClickBlockId == hitBlock.id && (nowWall - lastClickMillis) < 380L) {
                            controller.enterLoopSubComp(scope, hitBlock.loopId, hitBlock.sceneIndex)
                            lastClickBlockId = null
                            return@awaitEachGesture
                        }
                        lastClickBlockId = hitBlock.id
                        lastClickMillis = nowWall

                        // Rows of stagger items select the individual item; everything else its track.
                        controller.selectTrack(hitRow.trackId)

                        val staggerSpreadOnHit = if (hitBlock.category == TrackCategory.StaggerGroup && (hitBlock.staggerCount ?: 1) > 1) {
                            val step = latestOverrides[hitBlock.trackId]?.staggerStepMs ?: hitBlock.staggerStepMs ?: 55
                            ((hitBlock.staggerCount ?: 1) - 1) * step
                        } else {
                            0
                        }
                        val origDuration = ((hitBlock.morphDurationMs ?: hitBlock.durationMs) - staggerSpreadOnHit).coerceAtLeast(60)
                        val origDelay = hitBlock.delayMs
                        var lastDuration = origDuration
                        var lastDelay = origDelay
                        var lastTriggerBeat = hitBlock.triggerBeatInScene

                        hoverIcon = pointerIconForDragMode(dragMode)
                        controller.overridesStore.beginEditGesture()
                        if (dragMode == DragMode.ScrubPlayhead) controller.beginScrubDrag()
                        try {
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull() ?: break
                                if (!change.pressed) break
                                change.consume()
                                val dxPx = change.position.x - startPos.x
                                if (abs(dxPx) < 3f) continue
                                val deltaMs = (dxPx / viewport.pxPerMs(surface)).roundToInt()

                                when (dragMode) {
                                    DragMode.ResizeRightDuration -> {
                                        val newDur = (origDuration + deltaMs).coerceIn(60, 4_000)
                                        if (newDur != lastDuration) {
                                            lastDuration = newDur
                                            controller.overridesStore.updateTrack(hitBlock.trackId, coalesce = true) { it.copy(durationMs = newDur) }
                                        }
                                    }
                                    DragMode.ResizeLeftDelay -> {
                                        val newDelay = (origDelay + deltaMs).coerceIn(0, 2_000)
                                        if (newDelay != lastDelay) {
                                            lastDelay = newDelay
                                            controller.overridesStore.updateTrack(hitBlock.trackId, coalesce = true) { it.copy(delayMs = newDelay) }
                                        }
                                    }
                                    DragMode.MoveBlockBeat -> {
                                        val cursorTimeMs = timeAt(change.position.x)
                                        val sameSlideBeats = currentSnapshot.eventBeats.filter {
                                            it.sceneIndex == hitBlock.sceneIndex && !it.isSlideBoundary
                                        }
                                        val nearest = sameSlideBeats.minByOrNull { abs(it.timeMs - cursorTimeMs) }
                                        if (nearest != null) {
                                            val targetTrigger = (nearest.frameIndex + 1).coerceIn(1, sameSlideBeats.size)
                                            if (targetTrigger != lastTriggerBeat) {
                                                lastTriggerBeat = targetTrigger
                                                controller.overridesStore.updateTrack(hitBlock.trackId, coalesce = true) { it.copy(enterBeat = targetTrigger) }
                                            }
                                        }
                                    }
                                    DragMode.ScrubPlayhead -> controller.scrubToTimeMs(timeAt(change.position.x))
                                    DragMode.Pan -> Unit
                                }
                            }
                        } finally {
                            hoverIcon = PointerIcon.Default
                            if (dragMode == DragMode.ScrubPlayhead) controller.endScrubDrag()
                            controller.overridesStore.endEditGesture()
                        }
                    }
                },
        ) {
            val pxPerMs = viewport.pxPerMs(surface)
            val scrollY = viewport.verticalScroll.value.toFloat()
            fun xOf(timeMs: Int): Float = viewport.xForTime(timeMs.toFloat(), surface)
            val visibleStartMs = viewport.timeForX(-40f, surface)
            val visibleEndMs = viewport.timeForX(size.width + 40f, surface)

            drawRect(color = colors.bg)

            // 1. Rows, full-height dotted event keyframe lines, and animation blocks.
            clipRect(top = headerTotalPx) {
                snapshot.tracks.forEachIndexed { rIdx, row ->
                    val y = headerTotalPx + rIdx * rowHeightPx - scrollY
                    if (y + rowHeightPx >= headerTotalPx && y <= size.height) {
                        val isSelected = controller.isTrackRowSelected(row.trackId)
                        if (isSelected) {
                            drawRect(
                                color = colors.selectedBorder.copy(alpha = 0.10f),
                                topLeft = Offset(0f, y),
                                size = Size(size.width, rowHeightPx),
                            )
                        }
                        drawLine(
                            color = colors.gridLine,
                            start = Offset(0f, y + rowHeightPx),
                            end = Offset(size.width, y + rowHeightPx),
                            strokeWidth = 1f,
                        )
                    }
                }

                val beatDash = PathEffect.dashPathEffect(floatArrayOf(5f, 5f), 0f)
                for (beat in snapshot.eventBeats) {
                    if (beat.timeMs < visibleStartMs || beat.timeMs > visibleEndMs) continue
                    val x = xOf(beat.timeMs)
                    val lineColor = when {
                        beat.isSlideBoundary -> colors.boundaryLine.copy(alpha = 0.72f)
                        beat.isInjected -> colors.injectedBeatLine.copy(alpha = 0.85f)
                        else -> colors.beatLine.copy(alpha = 0.52f)
                    }
                    drawLine(
                        color = lineColor,
                        start = Offset(x, headerTotalPx),
                        end = Offset(x, size.height),
                        strokeWidth = if (beat.isSlideBoundary || beat.isInjected) 1.8f else 1.2f,
                        pathEffect = beatDash,
                    )
                }

                snapshot.tracks.forEachIndexed { rIdx, row ->
                    val yTop = headerTotalPx + rIdx * rowHeightPx - scrollY + 4f
                    val barH = rowHeightPx - 8f
                    if (yTop + barH < headerTotalPx || yTop > size.height) return@forEachIndexed

                    val isSelected = controller.isTrackRowSelected(row.trackId)
                    val fill = colors.colorForCategory(row.category)
                    for (blk in row.blocks) {
                        val liveOvr = liveOverrides[blk.trackId]
                        val isBarOverridden = if (blk.loopId != null) {
                            blk.loopId in liveLoopPeriods
                        } else {
                            liveOvr != null
                        }
                        val (drawStartMs, drawDurationMs) = effectiveBlockSpanMs(row, blk, liveOvr)
                        if (drawStartMs + drawDurationMs < visibleStartMs || drawStartMs > visibleEndMs) continue
                        val x0 = xOf(drawStartMs)
                        val w = max(6f, drawDurationMs * pxPerMs)
                        val barAlpha = if (row.category == TrackCategory.StaggerItem) 0.74f else 0.90f
                        val paintedFill = fill.copy(alpha = barAlpha)
                        val compositedFill = compositeColorOver(paintedFill, colors.bg)
                        val barLabelInk = barLabelInkForFill(compositedFill)
                        drawRoundRect(
                            color = paintedFill,
                            topLeft = Offset(x0, yTop),
                            size = Size(w, barH),
                            cornerRadius = CornerRadius(5f, 5f),
                        )
                        if (isBarOverridden && w > 18f) {
                            val dotCenter = Offset(x0 + w - 7f, yTop + 6f)
                            drawCircle(color = barLabelInk.copy(alpha = 0.85f), radius = 4.2f, center = dotCenter)
                            drawCircle(color = Color(0xFFFFD54F), radius = 2.8f, center = dotCenter)
                        }
                        if (isSelected) {
                            // Crisp high-contrast double-ring selection border (>= 4.5:1 against both light & dark canvas).
                            drawRoundRect(
                                color = colors.selectedBarInnerRing,
                                topLeft = Offset(x0 + 1f, yTop + 1f),
                                size = Size(max(2f, w - 2f), max(2f, barH - 2f)),
                                cornerRadius = CornerRadius(4f, 4f),
                                style = Stroke(width = 1.4f),
                            )
                            drawRoundRect(
                                color = colors.selectedBarOuterRing,
                                topLeft = Offset(x0 - 0.5f, yTop - 0.5f),
                                size = Size(w + 1f, barH + 1f),
                                cornerRadius = CornerRadius(5.5f, 5.5f),
                                style = Stroke(width = 2.2f),
                            )
                        }
                        if (row.category != TrackCategory.LoopSubComp && w > 28f) {
                            val gripColor = barLabelInk.copy(alpha = 0.62f)
                            drawLine(gripColor, Offset(x0 + 3f, yTop + 4f), Offset(x0 + 3f, yTop + barH - 4f), 1.5f)
                            drawLine(gripColor, Offset(x0 + w - 3f, yTop + 4f), Offset(x0 + w - 3f, yTop + barH - 4f), 1.5f)
                        }
                        if (w > 42f) {
                            val barText = if (blk.loopId != null && blk.loopId in liveLoopPeriods) {
                                val liveP = liveLoopPeriods.getValue(blk.loopId)
                                "${blk.label.substringBeforeLast(" · ")} · ${liveP}ms ▸"
                            } else {
                                blk.label
                            }
                            val rightClip = x0 + w - (if (isBarOverridden) 13f else 6f)
                            val labelAvailW = rightClip - (x0 + 7f)
                            if (labelAvailW > 6f) {
                                drawEllipsizedLabel(
                                    textMeasurer = textMeasurer,
                                    text = barText,
                                    style = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = barLabelInk),
                                    left = x0 + 7f,
                                    top = yTop + 2f,
                                    availWidth = labelAvailW,
                                    clipTop = yTop,
                                    clipBottom = yTop + barH,
                                )
                            }
                        }
                    }
                }
            }

            // 2. Fixed header: contiguous slide ribbon (hatched overlaps) + adaptive time ruler.
            drawRect(color = colors.headerBg, topLeft = Offset.Zero, size = Size(size.width, headerTotalPx))

            for (slide in snapshot.slides) {
                val sx = xOf(slide.startMs)
                val sw = max(4f, (slide.endMs - slide.startMs) * pxPerMs)
                if (sx + sw < 0f || sx > size.width) continue
                val baseColor = colors.slideColors[slide.sceneIndex % colors.slideColors.size]
                val paintedSlide = baseColor.copy(alpha = 0.88f)
                val slideLabelInk = barLabelInkForFill(compositeColorOver(paintedSlide, colors.headerBg))
                drawRoundRect(
                    color = paintedSlide,
                    topLeft = Offset(sx, 2f),
                    size = Size(sw, slideRibbonPx - 4f),
                    cornerRadius = CornerRadius(4f, 4f),
                )
                if (slide.overlapOutMs > 0) {
                    drawHatchedOverlapZone(
                        left = xOf(slide.endMs - slide.overlapOutMs),
                        top = 2f,
                        width = slide.overlapOutMs * pxPerMs,
                        height = slideRibbonPx - 4f,
                        accent = colors.boundaryLine,
                    )
                }
                val labelLeft = maxOf(sx + slide.overlapInMs * pxPerMs, 0f) + 6f
                val labelRight = minOf(sx + sw - slide.overlapOutMs * pxPerMs - 6f, size.width - 4f)
                val slideLabelAvailW = labelRight - labelLeft
                if (slideLabelAvailW > 14f) {
                    drawEllipsizedLabel(
                        textMeasurer = textMeasurer,
                        text = slide.label,
                        style = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Medium, color = slideLabelInk),
                        left = labelLeft,
                        top = 5f,
                        availWidth = slideLabelAvailW,
                        clipTop = 2f,
                        clipBottom = slideRibbonPx - 2f,
                    )
                }
            }

            val majorStep = chooseTickStepMs(pxPerMs)
            val minorStep = (majorStep / 5).coerceAtLeast(1)
            var tickMs = (visibleStartMs.coerceAtLeast(0f) / minorStep).toInt() * minorStep
            val lastTick = minOf(visibleEndMs.toInt(), snapshot.totalDurationMs + majorStep)
            var lastDrawnTickRight = Float.NEGATIVE_INFINITY
            while (tickMs <= lastTick) {
                val tx = xOf(tickMs)
                val isMajor = tickMs % majorStep == 0
                drawLine(
                    color = colors.gridLine,
                    start = Offset(tx, if (isMajor) slideRibbonPx + 4f else slideRibbonPx + 16f),
                    end = Offset(tx, headerTotalPx),
                    strokeWidth = 1f,
                )
                if (isMajor && tx in -4f..size.width) {
                    val tickMeasured = textMeasurer.measure(
                        text = formatTimelineTick(tickMs, majorStep),
                        style = rulerTextStyle,
                        maxLines = 1,
                        softWrap = false,
                    )
                    val maxTickLeft = (size.width - tickMeasured.size.width - 2f).coerceAtLeast(0f)
                    val tickLeft = (tx + 3f).coerceIn(0f, maxTickLeft)
                    if (tickLeft >= lastDrawnTickRight + 6f) {
                        drawText(
                            textLayoutResult = tickMeasured,
                            topLeft = Offset(tickLeft, slideRibbonPx + 2f),
                        )
                        lastDrawnTickRight = tickLeft + tickMeasured.size.width
                    }
                }
                tickMs += minorStep
            }

            for (beat in snapshot.eventBeats) {
                if (beat.timeMs < visibleStartMs || beat.timeMs > visibleEndMs) continue
                val bx = xOf(beat.timeMs)
                val cy = headerTotalPx - 6f
                val dColor = when {
                    beat.isSlideBoundary -> colors.boundaryLine
                    beat.isInjected -> colors.injectedBeatLine
                    else -> colors.beatLine
                }
                val diamond = Path().apply {
                    moveTo(bx, cy - 4.5f)
                    lineTo(bx + 4.5f, cy)
                    lineTo(bx, cy + 4.5f)
                    lineTo(bx - 4.5f, cy)
                    close()
                }
                drawPath(diamond, color = dColor)
                if (beat.isInjected) {
                    val holdMeasured = textMeasurer.measure(
                        text = "HOLD",
                        style = TextStyle(
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF1A1404),
                        ),
                        maxLines = 1,
                    )
                    val tagW = holdMeasured.size.width + 6f
                    val tagH = holdMeasured.size.height + 2f
                    val tagTop = (cy - tagH / 2f).coerceAtLeast(slideRibbonPx + 1f)
                    drawRoundRect(
                        color = colors.injectedBeatLine,
                        topLeft = Offset(bx + 6f, tagTop),
                        size = Size(tagW, tagH),
                        cornerRadius = CornerRadius(3f, 3f),
                    )
                    drawText(
                        textLayoutResult = holdMeasured,
                        topLeft = Offset(bx + 9f, tagTop + 1f),
                    )
                }
            }

            drawLine(colors.gridLine, Offset(0f, headerTotalPx), Offset(size.width, headerTotalPx), 1.5f)

            // 3. Playhead.
            val playheadX = xOf(playheadMs)
            if (playheadX in -8f..(size.width + 8f)) {
                drawLine(colors.playhead, Offset(playheadX, 0f), Offset(playheadX, size.height), 2f)
                val cap = Path().apply {
                    moveTo(playheadX - 6f, 0f)
                    lineTo(playheadX + 6f, 0f)
                    lineTo(playheadX + 6f, 9f)
                    lineTo(playheadX, 15f)
                    lineTo(playheadX - 6f, 9f)
                    close()
                }
                drawPath(cap, color = colors.playhead)
            }
        }

        // Lightweight non-interactive accessibility overlay nodes for the ruler/playhead and blocks
        // so screen readers can inspect timeline timing without duplicating focus stops.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clipToBounds(),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(HEADER_TOTAL_HEIGHT)
                    .testTag("ciacola-timeline-ruler")
                    .semantics {
                        contentDescription = "Timeline ruler and playhead"
                        stateDescription = playheadStateDesc
                    },
            )
            val scrollY = viewport.verticalScroll.value.toFloat()
            val pxPerMs = viewport.pxPerMs(surface)
            val surfaceWidthPx = viewport.viewportWidthPx(surface)
            snapshot.tracks.forEachIndexed { rIdx, row ->
                val yTop = headerTotalPx + rIdx * rowHeightPx - scrollY + 4f
                val barH = rowHeightPx - 8f
                if (yTop + barH < headerTotalPx || yTop > canvasHeightPx) return@forEachIndexed
                val isSelected = controller.isTrackRowSelected(row.trackId)
                for (blk in row.blocks) {
                    val liveOvr = liveOverrides[blk.trackId]
                    val isBarOverridden = if (blk.loopId != null) {
                        blk.loopId in liveLoopPeriods
                    } else {
                        liveOvr != null
                    }
                    val (drawStartMs, drawDurationMs) = effectiveBlockSpanMs(row, blk, liveOvr)
                    val x0 = viewport.xForTime(drawStartMs.toFloat(), surface)
                    val w = max(6f, drawDurationMs * pxPerMs)
                    if (x0 + w < 0f || x0 > surfaceWidthPx) continue
                    val wDp = with(density) { w.toDp() }
                    val hDp = with(density) { barH.toDp() }
                    Box(
                        modifier = Modifier
                            .offset { IntOffset(x0.roundToInt(), yTop.roundToInt()) }
                            .size(wDp, hDp)
                            .testTag("ciacola-timeline-block-${blk.id}")
                            .semantics {
                                contentDescription = "${blk.label}, ${row.category.accessibleLabel()}, " +
                                    "Slide ${blk.sceneIndex + 1} Beat ${blk.triggerBeatInScene + 1}"
                                stateDescription = buildString {
                                    append("Starts at ${formatMillisForAccessibility(drawStartMs)}, ")
                                    append("duration ${formatMillisForAccessibility(drawDurationMs)}")
                                    if (isSelected) append(", Selected")
                                    if (isBarOverridden) append(", Overridden")
                                }
                            },
                    )
                }
            }
        }
    }
}
