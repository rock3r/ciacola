package dev.sebastiano.ciacola.timeline

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Scrollable2DState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.rememberScrollable2DState
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable2D
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.v2.ScrollbarAdapter
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
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
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.HorizontalScrollbar
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.IconButton
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Slider
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.Tooltip
import org.jetbrains.jewel.ui.component.VerticalScrollbar
import org.jetbrains.jewel.ui.icon.IconKey
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.ui.theme.colorPalette
import org.jetbrains.jewel.ui.typography

private val SLIDE_RIBBON_HEIGHT = 26.dp
private val TIME_RULER_HEIGHT = 26.dp
private val HEADER_TOTAL_HEIGHT = 52.dp
private val TRACK_ROW_HEIGHT = 28.dp
private val LAYER_COLUMN_WIDTH = 224.dp

private enum class DragMode {
    ScrubPlayhead,
    MoveBlockBeat,
    ResizeLeftDelay,
    ResizeRightDuration,
    Pan,
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TransportIconButton(
    icon: IconKey,
    contentDescription: String,
    tooltip: String,
    testTag: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Tooltip(tooltip = { Text(tooltip) }) {
        IconButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.size(24.dp).testTag(testTag),
        ) {
            Icon(key = icon, contentDescription = contentDescription, modifier = Modifier.size(14.dp))
        }
    }
}

/**
 * Transport bar: beat stepping, playback, timecode, 0-ms event keyframe controls, sub-composition
 * breadcrumb, After Effects style zoom (slider + icon buttons), and Kotlin writeback.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun TimelineTransportBar(
    controller: TimelineStudioController,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val globalColors = JewelTheme.globalColors
    val activeSubLoopId = controller.loopClock.activeSubCompLoopId
    val nowMs = controller.currentPlayheadTimeMs
    val idx = controller.animatic.targetIndex
    val sceneBeatsCount = controller.animatic.storyboard.scenes.getOrNull(idx.sceneIndex)?.frames?.size ?: 1
    val overridesData = controller.overridesStore.data()
    val hasOverrides = overridesData.tracks.isNotEmpty() || overridesData.sceneBeats.isNotEmpty()
    val viewport = controller.viewport

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(globalColors.panelBackground)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            TransportIconButton(AllIconsKeys.General.ChevronLeft, "Previous beat", "Previous event beat", "ciacola-timeline-prev-beat") {
                controller.stepBeat(-1)
            }
            TransportIconButton(
                icon = if (controller.isPlaying) AllIconsKeys.Actions.Pause else AllIconsKeys.Actions.Execute,
                contentDescription = if (controller.isPlaying) "Pause" else "Play",
                tooltip = "Play / pause (Space)",
                testTag = "ciacola-timeline-play",
            ) { controller.togglePlayback(scope) }
            TransportIconButton(AllIconsKeys.General.ChevronRight, "Next beat", "Next event beat", "ciacola-timeline-next-beat") {
                controller.stepBeat(+1)
            }

            val seconds = nowMs / 1000
            val millis = nowMs % 1000
            val formattedTime = "${seconds.toString().padStart(2, '0')}.${millis.toString().padStart(3, '0')}s"
            val locationText = if (activeSubLoopId != null) {
                "$formattedTime · Loop"
            } else {
                "$formattedTime · S${idx.sceneIndex + 1}:B${idx.frameIndex + 1}/$sceneBeatsCount"
            }
            Box(
                modifier = Modifier
                    .padding(start = 4.dp)
                    .border(1.dp, globalColors.borders.normal, RoundedCornerShape(4.dp))
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            ) {
                Text(
                    text = locationText,
                    style = JewelTheme.typography.small.copy(fontFamily = FontFamily.Monospace),
                    color = globalColors.text.normal,
                    modifier = Modifier.testTag("ciacola-timeline-timecode"),
                )
            }

            if (activeSubLoopId == null) {
                TransportIconButton(AllIconsKeys.General.Add, "Add beat", "Insert event keyframe (click beat) after the current beat", "ciacola-timeline-add-beat") {
                    controller.insertEventBeatAtPlayhead()
                }
                TransportIconButton(
                    icon = AllIconsKeys.General.Remove,
                    contentDescription = "Remove beat",
                    tooltip = "Remove the current event keyframe from this slide",
                    testTag = "ciacola-timeline-remove-beat",
                    enabled = sceneBeatsCount > 1,
                ) { controller.removeEventBeatAtPlayhead() }
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (activeSubLoopId != null) {
                val loopLabel = controller.loopClock.registeredLoops[activeSubLoopId]?.label ?: activeSubLoopId
                OutlinedButton(
                    onClick = { controller.exitLoopSubComp() },
                    modifier = Modifier.testTag("ciacola-timeline-exit-subcomp"),
                ) {
                    Text("← Master Deck", style = JewelTheme.typography.small)
                }
                Text(
                    text = "▸ Sub-Comp: $loopLabel",
                    style = JewelTheme.typography.medium.copy(fontWeight = FontWeight.SemiBold),
                    color = globalColors.text.normal,
                    modifier = Modifier.testTag("ciacola-timeline-subcomp-title"),
                )
            } else {
                Text(
                    text = "Master Deck (${controller.snapshot.slides.size} slides · ${controller.snapshot.tracks.size} tracks)",
                    style = JewelTheme.typography.small,
                    color = globalColors.text.info,
                    modifier = Modifier.testTag("ciacola-timeline-master-summary"),
                )
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (activeSubLoopId == null) {
                TransportIconButton(AllIconsKeys.General.ZoomOut, "Zoom out", "Zoom out (-, or Alt+wheel)", "ciacola-timeline-zoom-out") {
                    viewport.zoomBy(0.8f, viewport.xForTime(nowMs.toFloat()).coerceIn(0f, viewport.viewportWidthPx))
                }
                // Logarithmic zoom slider (After Effects-style), anchored on the playhead.
                val minZoom = minOf(MIN_PX_PER_MS, viewport.fitPxPerMs())
                val logMin = ln(minZoom)
                val logMax = ln(MAX_PX_PER_MS)
                Slider(
                    value = ((ln(viewport.pxPerMs) - logMin) / (logMax - logMin)).coerceIn(0f, 1f),
                    onValueChange = { v ->
                        val anchor = viewport.xForTime(nowMs.toFloat()).coerceIn(0f, viewport.viewportWidthPx)
                        viewport.setZoom(exp(logMin + v * (logMax - logMin)), anchor)
                    },
                    modifier = Modifier.width(110.dp).testTag("ciacola-timeline-zoom-slider"),
                )
                TransportIconButton(AllIconsKeys.General.ZoomIn, "Zoom in", "Zoom in (=, or Alt+wheel)", "ciacola-timeline-zoom-in") {
                    viewport.zoomBy(1.25f, viewport.xForTime(nowMs.toFloat()).coerceIn(0f, viewport.viewportWidthPx))
                }
                TransportIconButton(AllIconsKeys.General.FitContent, "Zoom to fit", "Toggle fit / frame level (;)", "ciacola-timeline-zoom-fit") {
                    viewport.toggleFitAndFrames(nowMs.toFloat())
                }
            }
            if (hasOverrides) {
                Tooltip(tooltip = { Text("Discard live timeline overrides") }) {
                    OutlinedButton(
                        onClick = { controller.overridesStore.resetAll() },
                        modifier = Modifier.testTag("ciacola-timeline-reset"),
                    ) {
                        Text("Reset", style = JewelTheme.typography.small)
                    }
                }
                if (controller.reviewStore != null) {
                    Tooltip(tooltip = { Text("Send live overrides to agent to fold into Kotlin source") }) {
                        DefaultButton(
                            onClick = { controller.bakeOverridesViaAgent(scope) },
                            modifier = Modifier.testTag("ciacola-timeline-bake"),
                        ) {
                            Text("Bake to Kotlin", style = JewelTheme.typography.small)
                        }
                    }
                }
            }
        }
    }
}

/** Layer headers + multi-track canvas, both scrolling vertically through one shared state. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun TimelineMultiTrackArea(
    controller: TimelineStudioController,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val activeSubLoopId = controller.loopClock.activeSubCompLoopId
    if (activeSubLoopId != null) {
        LoopSubCompTimelineView(
            controller = controller,
            loopId = activeSubLoopId,
            modifier = modifier,
        )
        return
    }

    val snapshot = controller.snapshot
    val viewport = controller.viewport
    val globalColors = JewelTheme.globalColors

    Row(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .width(LAYER_COLUMN_WIDTH)
                .fillMaxHeight()
                .background(globalColors.panelBackground),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(HEADER_TOTAL_HEIGHT)
                    .padding(horizontal = 10.dp),
                verticalArrangement = Arrangement.SpaceEvenly,
            ) {
                Text(
                    text = "SLIDES & OVERLAPS",
                    style = JewelTheme.typography.small.copy(fontWeight = FontWeight.Bold),
                    color = globalColors.text.info,
                )
                Text(
                    text = "EVENT BEATS / LAYERS",
                    style = JewelTheme.typography.small,
                    color = globalColors.text.info,
                )
            }
            Divider(Orientation.Horizontal, Modifier.fillMaxWidth())

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(viewport.verticalScroll)
                    .testTag("ciacola-timeline-layer-headers"),
            ) {
                for (track in snapshot.tracks) {
                    val isSelected = controller.isTrackRowSelected(track.trackId)
                    val loopBlock = track.blocks.firstOrNull { it.loopId != null }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(TRACK_ROW_HEIGHT)
                            .background(
                                if (isSelected) globalColors.outlines.focused.copy(alpha = 0.16f) else Color.Transparent
                            )
                            .clickable { controller.selectTrack(track.trackId) }
                            .padding(start = (10 + track.indentLevel * 14).dp, end = 6.dp)
                            .testTag("ciacola-track-row-${track.trackId}"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = track.label,
                            style = JewelTheme.typography.small,
                            color = if (isSelected) globalColors.text.normal else globalColors.text.info,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        if (loopBlock?.loopId != null) {
                            Tooltip(tooltip = { Text("Enter Loop Sub-Composition") }) {
                                Box(
                                    modifier = Modifier
                                        .border(1.dp, globalColors.borders.focused, RoundedCornerShape(4.dp))
                                        .clickable {
                                            controller.enterLoopSubComp(scope, loopBlock.loopId, loopBlock.sceneIndex)
                                        }
                                        .padding(horizontal = 5.dp, vertical = 1.dp)
                                        .testTag("ciacola-timeline-open-loop"),
                                ) {
                                    Text("Sub-Comp ▸", style = JewelTheme.typography.small, color = globalColors.text.normal)
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }

        Divider(Orientation.Vertical, Modifier.fillMaxHeight())

        Box(Modifier.weight(1f).fillMaxHeight()) {
            MasterDeckTimelineCanvas(
                controller = controller,
                snapshot = snapshot,
                modifier = Modifier.fillMaxSize(),
            )
            VerticalScrollbar(
                scrollState = viewport.verticalScroll,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .fillMaxHeight()
                    .padding(top = HEADER_TOTAL_HEIGHT, bottom = 10.dp)
                    .testTag("ciacola-timeline-vscrollbar"),
            )
            val horizontalState = rememberScrollableState { delta -> viewport.scrollHorizontallyBy(-delta).let { -it } }
            val horizontalAdapter = remember(viewport) { TimelineHorizontalScrollbarAdapter(viewport) }
            HorizontalScrollbar(
                scrollState = horizontalState,
                adapter = horizontalAdapter,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(end = 10.dp)
                    .testTag("ciacola-timeline-hscrollbar"),
            )
        }
    }
}

private class TimelineHorizontalScrollbarAdapter(private val viewport: TimelineViewportState) : ScrollbarAdapter {
    override val scrollOffset: Double get() = viewport.scrollX.toDouble()
    override val contentSize: Double get() = viewport.contentWidthPx.toDouble()
    override val viewportSize: Double get() = viewport.viewportWidthPx.toDouble()
    override suspend fun scrollTo(scrollOffset: Double) {
        viewport.scrollHorizontallyTo(scrollOffset.toFloat())
    }
}

private fun chooseTickStepMs(pxPerMs: Float): Int {
    val candidates = intArrayOf(10, 20, 50, 100, 200, 250, 500, 1_000, 2_000, 5_000, 10_000, 30_000)
    return candidates.firstOrNull { it * pxPerMs >= 80f } ?: candidates.last()
}

private fun formatTick(ms: Int, stepMs: Int): String {
    val seconds = ms / 1000.0
    return when {
        stepMs >= 1000 -> "${(ms / 1000)}s"
        stepMs >= 100 -> "%.1fs".format(seconds)
        else -> "%.2fs".format(seconds)
    }
}

@Composable
private fun MasterDeckTimelineCanvas(
    controller: TimelineStudioController,
    snapshot: DeckTimelineSnapshot,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val textMeasurer = rememberTextMeasurer()
    val globalColors = JewelTheme.globalColors
    val palette = JewelTheme.colorPalette
    val isDark = JewelTheme.isDark
    val viewport = controller.viewport

    val slideRibbonPx = with(density) { SLIDE_RIBBON_HEIGHT.toPx() }
    val rulerHeightPx = with(density) { TIME_RULER_HEIGHT.toPx() }
    val headerTotalPx = slideRibbonPx + rulerHeightPx
    val rowHeightPx = with(density) { TRACK_ROW_HEIGHT.toPx() }
    var canvasHeightPx by remember { mutableFloatStateOf(0f) }
    var didInitialFit by remember { mutableStateOf(false) }

    val colors = remember(JewelTheme.instanceUuid, isDark) {
        TimelineCanvasPalette(
            bg = globalColors.panelBackground,
            headerBg = if (isDark) Color(0xFF1A1E27) else Color(0xFFEBEEF4),
            gridLine = globalColors.borders.normal.copy(alpha = 0.45f),
            beatLine = if (isDark) Color(0xFF8A95AC) else Color(0xFF687387),
            injectedBeatLine = palette.yellowOrNull(if (isDark) 6 else 5) ?: Color(0xFFE0A82E),
            boundaryLine = palette.tealOrNull(if (isDark) 6 else 5) ?: Color(0xFF2FA896),
            playhead = palette.redOrNull(if (isDark) 6 else 5) ?: Color(0xFFE5534B),
            textPrimary = globalColors.text.normal,
            textSecondary = globalColors.text.info,
            selectedBorder = globalColors.outlines.focused,
            sharedBar = palette.purpleOrNull(if (isDark) 6 else 5) ?: Color(0xFF9B6EF3),
            magicTextBar = palette.blueOrNull(if (isDark) 6 else 5) ?: Color(0xFF4C8BF5),
            warpBar = palette.tealOrNull(if (isDark) 5 else 4) ?: Color(0xFF35B5AC),
            staggerBar = palette.greenOrNull(if (isDark) 6 else 5) ?: Color(0xFF4AB57D),
            buildBar = palette.orangeOrNull(if (isDark) 6 else 5) ?: Color(0xFFE57C4E),
            lineDrawBar = palette.yellowOrNull(if (isDark) 6 else 5) ?: Color(0xFFD9A33A),
            loopBar = palette.tealOrNull(if (isDark) 7 else 6) ?: Color(0xFF289E8F),
            slideColors = listOf(
                Color(0xFF334466),
                Color(0xFF4A3861),
                Color(0xFF2B5450),
                Color(0xFF5A3F37),
                Color(0xFF3B4F3B),
            ),
        )
    }

    val labelTextStyle = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Medium, color = Color.White)
    val rulerTextStyle = TextStyle(fontSize = 9.sp, fontFamily = FontFamily.Monospace, color = colors.textSecondary)

    val playheadMs = controller.currentPlayheadTimeMs
    // Follow the playhead only while playing (scrub drags and stepping manage visibility themselves).
    LaunchedEffect(playheadMs, controller.isPlaying) {
        if (controller.isPlaying) viewport.ensureTimeVisible(playheadMs.toFloat())
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
            if (block != null) viewport.ensureTimeVisible(block.startMs.toFloat())
        }
        viewport.pendingRevealTrackId = null
    }

    // Plain wheel / trackpad scrolling in both axes (CMP 1.13 scrollable2D).
    val scrollState: Scrollable2DState = rememberScrollable2DState { delta ->
        val consumedX = -viewport.scrollHorizontallyBy(-delta.x)
        val consumedY = -viewport.verticalScroll.dispatchRawDelta(-delta.y)
        Offset(consumedX, consumedY)
    }

    Canvas(
        modifier = modifier
            .clipToBounds()
            .onSizeChanged {
                viewport.viewportWidthPx = it.width.toFloat()
                canvasHeightPx = it.height.toFloat()
                if (!didInitialFit && it.width > 0 && snapshot.totalDurationMs > 0) {
                    viewport.contentDurationMs = snapshot.totalDurationMs
                    viewport.zoomToFit()
                    didInitialFit = true
                }
            }
            .pointerHoverIcon(if (controller.spaceHeld) PointerIcon.Hand else PointerIcon.Default)
            // After Effects modifiers: Alt/Ctrl+wheel zooms time at the cursor, Shift+wheel scrolls
            // horizontally; middle-drag or Space+drag pans (hand tool). Handled on the Initial pass,
            // before scrollable2D and the scrub/edit gestures see the events.
            .pointerInput(viewport) {
                awaitPointerEventScope {
                    var panning = false
                    var last = Offset.Zero
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull() ?: continue
                        when (event.type) {
                            PointerEventType.Scroll -> {
                                val mods = event.keyboardModifiers
                                val delta = change.scrollDelta
                                when {
                                    mods.isAltPressed || mods.isCtrlPressed -> {
                                        val steps = if (delta.y != 0f) delta.y else delta.x
                                        viewport.zoomBy(exp(-steps * 0.15f), change.position.x)
                                        change.consume()
                                    }
                                    mods.isShiftPressed -> {
                                        val steps = if (delta.x != 0f) delta.x else delta.y
                                        viewport.scrollHorizontallyBy(steps * 48f)
                                        change.consume()
                                    }
                                }
                            }
                            PointerEventType.Press -> if (event.buttons.isTertiaryPressed || controller.spaceHeld) {
                                panning = true
                                controller.spaceUsedForPan = true
                                last = change.position
                                change.consume()
                            }
                            PointerEventType.Move -> if (panning) {
                                val d = change.position - last
                                last = change.position
                                viewport.scrollHorizontallyBy(-d.x)
                                viewport.verticalScroll.dispatchRawDelta(-d.y)
                                change.consume()
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
                contentDescription = "zoom=${"%.4f".format(viewport.pxPerMs)} scrollX=${viewport.scrollX.roundToInt()} " +
                    "scrollY=${viewport.verticalScroll.value}"
            }
            .pointerInput(snapshot) {
                var lastClickBlockId: String? = null
                var lastClickMillis = 0L

                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val buttons = currentEvent.buttons
                    val startPos = down.position
                    // Panning (middle button / Space held) is handled by the Initial-pass handler.
                    if (down.isConsumed || buttons.isTertiaryPressed || controller.spaceHeld) return@awaitEachGesture
                    if (!buttons.isPrimaryPressed) return@awaitEachGesture
                    down.consume()

                    fun timeAt(x: Float): Int = viewport.timeForX(x).roundToInt()

                    fun scrubLoop(initialX: Float) {
                        controller.scrubToTimeMs(timeAt(initialX))
                    }

                    if (startPos.y <= headerTotalPx) {
                        scrubLoop(startPos.x)
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull() ?: break
                            if (!change.pressed) break
                            change.consume()
                            // Auto-scroll when dragging the playhead past either edge.
                            val edge = 24f
                            if (change.position.x > size.width - edge) viewport.scrollHorizontallyBy(12f)
                            if (change.position.x < edge) viewport.scrollHorizontallyBy(-12f)
                            controller.scrubToTimeMs(timeAt(change.position.x))
                        }
                        return@awaitEachGesture
                    }

                    val trackIdx = ((startPos.y - headerTotalPx + viewport.verticalScroll.value) / rowHeightPx).toInt()
                    val hitRow = snapshot.tracks.getOrNull(trackIdx)
                    val hitBlock = hitRow?.blocks?.firstOrNull { blk ->
                        val bx0 = viewport.xForTime(blk.startMs.toFloat())
                        val bx1 = bx0 + max(18f, blk.durationMs * viewport.pxPerMs)
                        startPos.x in (bx0 - 6f)..(bx1 + 6f)
                    }

                    if (hitBlock == null) {
                        controller.scrubToTimeMs(timeAt(startPos.x))
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull() ?: break
                            if (!change.pressed) break
                            change.consume()
                            controller.scrubToTimeMs(timeAt(change.position.x))
                        }
                        return@awaitEachGesture
                    }

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

                    val bx0 = viewport.xForTime(hitBlock.startMs.toFloat())
                    val bx1 = bx0 + max(18f, hitBlock.durationMs * viewport.pxPerMs)
                    val dragMode = when {
                        hitBlock.category == TrackCategory.LoopSubComp -> DragMode.ScrubPlayhead
                        abs(startPos.x - bx1) <= 9f -> DragMode.ResizeRightDuration
                        abs(startPos.x - bx0) <= 9f -> DragMode.ResizeLeftDelay
                        else -> DragMode.MoveBlockBeat
                    }
                    val origDuration = hitBlock.durationMs
                    val origDelay = hitBlock.delayMs

                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: break
                        if (!change.pressed) break
                        change.consume()
                        val dxPx = change.position.x - startPos.x
                        if (abs(dxPx) < 3f) continue
                        val deltaMs = (dxPx / viewport.pxPerMs).roundToInt()

                        when (dragMode) {
                            DragMode.ResizeRightDuration -> {
                                val newDur = (origDuration + deltaMs).coerceIn(60, 4_000)
                                controller.overridesStore.updateTrack(hitBlock.trackId) { it.copy(durationMs = newDur) }
                            }
                            DragMode.ResizeLeftDelay -> {
                                val newDelay = (origDelay + deltaMs).coerceIn(0, 2_000)
                                controller.overridesStore.updateTrack(hitBlock.trackId) { it.copy(delayMs = newDelay) }
                            }
                            DragMode.MoveBlockBeat -> {
                                val cursorTimeMs = timeAt(change.position.x)
                                val sameSlideBeats = snapshot.eventBeats.filter {
                                    it.sceneIndex == hitBlock.sceneIndex && !it.isSlideBoundary
                                }
                                val nearest = sameSlideBeats.minByOrNull { abs(it.timeMs - cursorTimeMs) }
                                if (nearest != null) {
                                    val targetTrigger = (nearest.frameIndex + 1).coerceIn(1, sameSlideBeats.size)
                                    if (targetTrigger != hitBlock.triggerBeatInScene) {
                                        controller.overridesStore.updateTrack(hitBlock.trackId) { it.copy(enterBeat = targetTrigger) }
                                    }
                                }
                            }
                            DragMode.ScrubPlayhead -> controller.scrubToTimeMs(timeAt(change.position.x))
                            DragMode.Pan -> Unit
                        }
                    }
                }
            },
    ) {
        val pxPerMs = viewport.pxPerMs
        val scrollY = viewport.verticalScroll.value.toFloat()
        fun xOf(timeMs: Int): Float = viewport.xForTime(timeMs.toFloat())
        val visibleStartMs = viewport.timeForX(-40f)
        val visibleEndMs = viewport.timeForX(size.width + 40f)

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
                    if (blk.startMs + blk.durationMs < visibleStartMs || blk.startMs > visibleEndMs) continue
                    val x0 = xOf(blk.startMs)
                    val w = max(6f, blk.durationMs * pxPerMs)
                    drawRoundRect(
                        color = fill.copy(alpha = if (row.category == TrackCategory.StaggerItem) 0.74f else 0.90f),
                        topLeft = Offset(x0, yTop),
                        size = Size(w, barH),
                        cornerRadius = CornerRadius(5f, 5f),
                    )
                    if (isSelected) {
                        drawRoundRect(
                            color = Color.White,
                            topLeft = Offset(x0, yTop),
                            size = Size(w, barH),
                            cornerRadius = CornerRadius(5f, 5f),
                            style = Stroke(width = 2f),
                        )
                    }
                    if (row.category != TrackCategory.LoopSubComp && w > 28f) {
                        drawLine(Color.White.copy(alpha = 0.65f), Offset(x0 + 3f, yTop + 4f), Offset(x0 + 3f, yTop + barH - 4f), 1.5f)
                        drawLine(Color.White.copy(alpha = 0.65f), Offset(x0 + w - 3f, yTop + 4f), Offset(x0 + w - 3f, yTop + barH - 4f), 1.5f)
                    }
                    if (w > 42f) {
                        clipRect(left = x0 + 6f, top = yTop, right = x0 + w - 6f, bottom = yTop + barH) {
                            drawText(
                                textMeasurer = textMeasurer,
                                text = blk.label,
                                topLeft = Offset(x0 + 7f, yTop + 2f),
                                style = labelTextStyle,
                                maxLines = 1,
                                overflow = TextOverflow.Clip,
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
            drawRoundRect(
                color = baseColor.copy(alpha = 0.85f),
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
            val labelRight = sx + sw - slide.overlapOutMs * pxPerMs - 6f
            if (labelRight > labelLeft + 14f) {
                clipRect(left = labelLeft, top = 2f, right = labelRight, bottom = slideRibbonPx - 2f) {
                    drawText(
                        textMeasurer = textMeasurer,
                        text = slide.label,
                        topLeft = Offset(labelLeft, 5f),
                        style = labelTextStyle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        size = Size(labelRight - labelLeft, slideRibbonPx - 4f),
                    )
                }
            }
        }

        val majorStep = chooseTickStepMs(pxPerMs)
        val minorStep = (majorStep / 5).coerceAtLeast(1)
        var tickMs = (visibleStartMs.coerceAtLeast(0f) / minorStep).toInt() * minorStep
        val lastTick = minOf(visibleEndMs.toInt(), snapshot.totalDurationMs + majorStep)
        while (tickMs <= lastTick) {
            val tx = xOf(tickMs)
            val isMajor = tickMs % majorStep == 0
            drawLine(
                color = colors.gridLine,
                start = Offset(tx, if (isMajor) slideRibbonPx + 4f else slideRibbonPx + 16f),
                end = Offset(tx, headerTotalPx),
                strokeWidth = 1f,
            )
            if (isMajor) {
                drawText(
                    textMeasurer = textMeasurer,
                    text = formatTick(tickMs, majorStep),
                    topLeft = Offset(tx + 3f, slideRibbonPx + 2f),
                    style = rulerTextStyle,
                )
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
}

/** Dedicated sub-composition timeline (`0..periodMs`) when the user drills into an ambient loop. */
@Composable
private fun LoopSubCompTimelineView(
    controller: TimelineStudioController,
    loopId: String,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val textMeasurer: TextMeasurer = rememberTextMeasurer()
    val globalColors = JewelTheme.globalColors
    val palette = JewelTheme.colorPalette
    val isDark = JewelTheme.isDark

    val desc = controller.loopClock.registeredLoops[loopId]
        ?: LoopSubCompDescriptor(loopId = loopId, label = loopId, defaultPeriodMillis = 6_000L)
    val periodMs = controller.loopClock.effectivePeriodMillis(loopId, desc.defaultPeriodMillis).toInt()
    val channels = desc.harmonicChannels
    val rulerPx = with(density) { HEADER_TOTAL_HEIGHT.toPx() }
    val rowPx = with(density) { 36.dp.toPx() }

    Row(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .width(LAYER_COLUMN_WIDTH)
                .fillMaxHeight()
                .background(globalColors.panelBackground),
        ) {
            Box(
                modifier = Modifier.fillMaxWidth().height(HEADER_TOTAL_HEIGHT).padding(horizontal = 10.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(
                    text = "LOOP SUB-COMP (${periodMs}ms)",
                    style = JewelTheme.typography.small.copy(fontWeight = FontWeight.Bold),
                    color = globalColors.text.normal,
                )
            }
            Divider(Orientation.Horizontal, Modifier.fillMaxWidth())
            channels.forEachIndexed { idx, ch ->
                Box(
                    modifier = Modifier.fillMaxWidth().height(36.dp).padding(horizontal = 12.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(text = "${idx + 1}. $ch", style = JewelTheme.typography.small, color = globalColors.text.info)
                }
            }
        }

        Divider(Orientation.Vertical, Modifier.fillMaxHeight())

        Canvas(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .testTag("ciacola-subcomp-canvas")
                .pointerInput(periodMs) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        val usableW = (size.width - 32f).coerceAtLeast(1f)
                        controller.scrubToTimeMs((((down.position.x - 16f) / usableW) * periodMs).roundToInt())
                        while (true) {
                            val ev = awaitPointerEvent()
                            val ch = ev.changes.firstOrNull() ?: break
                            if (!ch.pressed) break
                            ch.consume()
                            controller.scrubToTimeMs((((ch.position.x - 16f) / usableW) * periodMs).roundToInt())
                        }
                    }
                },
        ) {
            val leftPad = 16f
            val usableW = (size.width - 32f).coerceAtLeast(1f)
            val teal = palette.tealOrNull(if (isDark) 6 else 5) ?: Color(0xFF35B5AC)
            val playheadColor = palette.redOrNull(if (isDark) 6 else 5) ?: Color(0xFFE5534B)

            drawRect(color = globalColors.panelBackground)

            val steps = 6
            val tickStyle = TextStyle(fontSize = 10.sp, color = globalColors.text.info, fontFamily = FontFamily.Monospace)
            for (s in 0..steps) {
                val frac = s.toFloat() / steps.toFloat()
                val x = leftPad + frac * usableW
                drawLine(
                    color = globalColors.borders.normal.copy(alpha = 0.5f),
                    start = Offset(x, rulerPx - 14f),
                    end = Offset(x, size.height),
                    strokeWidth = 1f,
                )
                val label = "${((frac * periodMs).roundToInt()) / 1000f}s"
                val measured = textMeasurer.measure(label, style = tickStyle, maxLines = 1)
                val textX = if (s == steps) (x - measured.size.width - 3f).coerceAtLeast(0f) else x + 3f
                drawText(textLayoutResult = measured, topLeft = Offset(textX, rulerPx - 22f))
            }
            drawLine(globalColors.borders.normal, Offset(0f, rulerPx), Offset(size.width, rulerPx), 1.5f)

            channels.forEachIndexed { idx, _ ->
                val yTop = rulerPx + idx * rowPx + 4f
                val barH = rowPx - 8f
                drawRoundRect(
                    color = teal.copy(alpha = 0.22f),
                    topLeft = Offset(leftPad, yTop),
                    size = Size(usableW, barH),
                    cornerRadius = CornerRadius(5f, 5f),
                )
                val wave = Path()
                val samples = 72
                for (k in 0..samples) {
                    val f = k.toFloat() / samples.toFloat()
                    val wx = leftPad + f * usableW
                    val phase = f * 2f * PI.toFloat() + idx * (PI.toFloat() / 2f)
                    val wy = yTop + barH * 0.5f - sin(phase) * (barH * 0.34f)
                    if (k == 0) wave.moveTo(wx, wy) else wave.lineTo(wx, wy)
                }
                drawPath(wave, color = teal, style = Stroke(width = 2f))
            }

            val scrubFrac = (controller.currentPlayheadTimeMs.toFloat() / periodMs.coerceAtLeast(1)).coerceIn(0f, 1f)
            val px = leftPad + scrubFrac * usableW
            drawLine(playheadColor, Offset(px, 0f), Offset(px, size.height), 2f)
        }
    }
}

private fun DrawScope.drawHatchedOverlapZone(
    left: Float,
    top: Float,
    width: Float,
    height: Float,
    accent: Color,
) {
    clipRect(left = left, top = top, right = left + width, bottom = top + height) {
        drawRect(color = accent.copy(alpha = 0.20f), topLeft = Offset(left, top), size = Size(width, height))
        var cursor = left - height
        while (cursor < left + width + height) {
            drawLine(
                color = accent.copy(alpha = 0.55f),
                start = Offset(cursor, top + height),
                end = Offset(cursor + height, top),
                strokeWidth = 1.5f,
            )
            cursor += 8f
        }
    }
}

private data class TimelineCanvasPalette(
    val bg: Color,
    val headerBg: Color,
    val gridLine: Color,
    val beatLine: Color,
    val injectedBeatLine: Color,
    val boundaryLine: Color,
    val playhead: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val selectedBorder: Color,
    val sharedBar: Color,
    val magicTextBar: Color,
    val warpBar: Color,
    val staggerBar: Color,
    val buildBar: Color,
    val lineDrawBar: Color,
    val loopBar: Color,
    val slideColors: List<Color>,
) {
    fun colorForCategory(category: TrackCategory): Color = when (category) {
        TrackCategory.SharedElement -> sharedBar
        TrackCategory.MagicText -> magicTextBar
        TrackCategory.WarpCode -> warpBar
        TrackCategory.StaggerGroup, TrackCategory.StaggerItem -> staggerBar
        TrackCategory.Build -> buildBar
        TrackCategory.LineDraw -> lineDrawBar
        TrackCategory.LoopSubComp -> loopBar
    }
}
