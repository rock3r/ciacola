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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.v2.ScrollbarAdapter
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.awt.Cursor
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
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
    surface: TimelineSurface = TimelineSurface.Docked,
) {
    val scope = rememberCoroutineScope()
    val globalColors = JewelTheme.globalColors
    val isDark = JewelTheme.isDark
    val secondaryCaptionColor = studioSecondaryTextColor(isDark)
    val activeSubLoopId = controller.loopClock.activeSubCompLoopId
    val nowMs = controller.currentPlayheadTimeMs
    val idx = controller.animatic.targetIndex
    val sceneBeatsCount = controller.animatic.storyboard.scenes.getOrNull(idx.sceneIndex)?.frames?.size ?: 1
    controller.overridesStore.parameterRevision
    controller.overridesStore.structuralRevision
    val overridesData = controller.overridesStore.data()
    val hasOverrides = overridesData.hasOverrides
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
            TransportIconButton(
                icon = AllIconsKeys.Actions.Undo,
                contentDescription = "Undo",
                tooltip = "Undo timeline edit (Cmd/Ctrl+Z)",
                testTag = "ciacola-timeline-undo",
                enabled = controller.overridesStore.canUndo,
            ) { controller.undo() }
            TransportIconButton(
                icon = AllIconsKeys.Actions.Redo,
                contentDescription = "Redo",
                tooltip = "Redo timeline edit (Cmd/Ctrl+Shift+Z)",
                testTag = "ciacola-timeline-redo",
                enabled = controller.overridesStore.canRedo,
            ) { controller.redo() }
        }

        Row(
            modifier = Modifier.weight(1f, fill = false).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (activeSubLoopId != null) {
                val loopLabel = controller.loopClock.registeredLoops[activeSubLoopId]?.label ?: activeSubLoopId
                OutlinedButton(
                    onClick = { controller.exitLoopSubComp() },
                    modifier = Modifier.testTag("ciacola-timeline-exit-subcomp"),
                ) {
                    Text("← Master Deck", style = JewelTheme.typography.small, maxLines = 1)
                }
                Text(
                    text = "▸ Sub-Comp: $loopLabel",
                    style = JewelTheme.typography.medium.copy(fontWeight = FontWeight.SemiBold),
                    color = globalColors.text.normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("ciacola-timeline-subcomp-title"),
                )
            } else {
                Text(
                    text = "Master Deck (${controller.snapshot.slides.size} slides · ${controller.snapshot.tracks.size} tracks)",
                    style = JewelTheme.typography.small,
                    color = secondaryCaptionColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
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
                    viewport.markSurfaceActive(surface)
                    viewport.zoomBy(0.8f, viewport.xForTime(nowMs.toFloat(), surface).coerceIn(0f, viewport.viewportWidthPx(surface)), surface)
                }
                // Logarithmic zoom slider (After Effects-style), anchored on the playhead.
                val minZoom = viewport.minZoomDpPerMs(surface).coerceAtMost(MAX_DP_PER_MS)
                val logMin = ln(minZoom)
                val logMax = ln(MAX_DP_PER_MS)
                val sliderValue = if (logMax > logMin) {
                    ((ln(viewport.dpPerMs) - logMin) / (logMax - logMin)).coerceIn(0f, 1f)
                } else {
                    0f
                }
                Slider(
                    value = sliderValue,
                    onValueChange = { v ->
                        viewport.markSurfaceActive(surface)
                        val anchor = viewport.xForTime(nowMs.toFloat(), surface).coerceIn(0f, viewport.viewportWidthPx(surface))
                        viewport.setZoomDpPerMs(exp(logMin + v * (logMax - logMin)), anchor, surface)
                    },
                    modifier = Modifier.width(84.dp).testTag("ciacola-timeline-zoom-slider"),
                )
                TransportIconButton(AllIconsKeys.General.ZoomIn, "Zoom in", "Zoom in (=, or Alt+wheel)", "ciacola-timeline-zoom-in") {
                    viewport.markSurfaceActive(surface)
                    viewport.zoomBy(1.25f, viewport.xForTime(nowMs.toFloat(), surface).coerceIn(0f, viewport.viewportWidthPx(surface)), surface)
                }
                TransportIconButton(AllIconsKeys.General.FitContent, "Zoom to fit", "Toggle fit / frame level (;)", "ciacola-timeline-zoom-fit") {
                    viewport.markSurfaceActive(surface)
                    viewport.toggleFitAndFrames(nowMs.toFloat(), surface)
                }
            }
            if (controller.bakeStatus != TimelineBakeStatus.Idle) {
                val palette = JewelTheme.colorPalette
                val isDark = JewelTheme.isDark
                val pillColor = when (controller.bakeStatus) {
                    TimelineBakeStatus.Sending -> palette.blueOrNull(if (isDark) 6 else 5) ?: Color(0xFF4C8BF5)
                    TimelineBakeStatus.Sent -> palette.greenOrNull(if (isDark) 6 else 5) ?: Color(0xFF4AB57D)
                    TimelineBakeStatus.Error -> palette.redOrNull(if (isDark) 6 else 5) ?: Color(0xFFE5534B)
                    TimelineBakeStatus.Idle -> globalColors.borders.normal
                }
                val fullMessage = controller.bakeStatusMessage ?: controller.bakeStatus.name
                Tooltip(tooltip = { Text(fullMessage) }) {
                    Box(
                        modifier = Modifier
                            .widthIn(max = 156.dp)
                            .background(pillColor.copy(alpha = 0.16f), RoundedCornerShape(4.dp))
                            .border(1.dp, pillColor.copy(alpha = 0.70f), RoundedCornerShape(4.dp))
                            .clickable(enabled = controller.bakeStatus != TimelineBakeStatus.Sending) {
                                controller.clearBakeStatus()
                            }
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                            .testTag("ciacola-timeline-bake-status"),
                    ) {
                        Text(
                            text = fullMessage,
                            style = JewelTheme.typography.small,
                            color = globalColors.text.normal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            if (hasOverrides || controller.copiedConfirmationVisible) {
                Tooltip(tooltip = { Text("Copy active overrides as Kotlin modifier/parameter snippets + JSON") }) {
                    OutlinedButton(
                        onClick = { controller.copyOverridesAsKotlinSnippet(scope) },
                        modifier = Modifier.testTag("ciacola-timeline-copy-kotlin"),
                    ) {
                        Text(
                            text = if (controller.copiedConfirmationVisible) "Copied!" else "Copy Kotlin",
                            style = JewelTheme.typography.small,
                        )
                    }
                }
            }
            if (hasOverrides) {
                Tooltip(tooltip = { Text("Discard all live timeline overrides across the deck") }) {
                    OutlinedButton(
                        onClick = { controller.resetAllOverrides() },
                        modifier = Modifier.testTag("ciacola-timeline-reset"),
                    ) {
                        Text("Reset All", style = JewelTheme.typography.small)
                    }
                }
                if (controller.reviewStore != null) {
                    Tooltip(tooltip = { Text("Send live overrides to agent to fold into Kotlin source") }) {
                        DefaultButton(
                            onClick = { controller.bakeOverridesViaAgent(scope) },
                            enabled = controller.bakeStatus != TimelineBakeStatus.Sending,
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
    surface: TimelineSurface = TimelineSurface.Docked,
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
    val palette = JewelTheme.colorPalette
    val isDark = JewelTheme.isDark
    val secondaryLayerColor = studioSecondaryTextColor(isDark)
    controller.overridesStore.parameterRevision
    val overridesData = controller.overridesStore.data()
    val overrideBadgeColor = palette.yellowOrNull(if (isDark) 6 else 5) ?: Color(0xFFE0A82E)

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
                    color = secondaryLayerColor,
                )
                Text(
                    text = "EVENT BEATS / LAYERS",
                    style = JewelTheme.typography.small,
                    color = secondaryLayerColor,
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
                    val isRowOverridden = if (loopBlock?.loopId != null) {
                        loopBlock.loopId in overridesData.loopPeriodsMs
                    } else {
                        track.trackId in overridesData.tracks
                    }
                    val displayRowLabel = if (loopBlock?.loopId != null) {
                        val livePeriod = overridesData.loopPeriodsMs[loopBlock.loopId] ?: loopBlock.loopPeriodMs ?: 6_000L
                        val prefix = track.label.substringBeforeLast(" · ")
                        "$prefix · ${livePeriod}ms"
                    } else {
                        track.label
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(TRACK_ROW_HEIGHT)
                            .background(
                                when {
                                    isSelected -> globalColors.outlines.focused.copy(alpha = 0.16f)
                                    isRowOverridden -> overrideBadgeColor.copy(alpha = 0.08f)
                                    else -> Color.Transparent
                                }
                            )
                            .clickable { controller.selectTrack(track.trackId) }
                            .padding(start = (10 + track.indentLevel * 14).dp, end = 6.dp)
                            .testTag("ciacola-track-row-${track.trackId}"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            if (isRowOverridden) {
                                Text(
                                    text = "●",
                                    style = JewelTheme.typography.small,
                                    color = overrideBadgeColor,
                                    modifier = Modifier
                                        .testTag("ciacola-override-badge-${track.trackId}")
                                        .semantics { contentDescription = "overridden" },
                                )
                            }
                            Text(
                                text = displayRowLabel,
                                style = JewelTheme.typography.small,
                                color = if (isSelected || isRowOverridden) globalColors.text.normal else secondaryLayerColor,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
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
                surface = surface,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(end = 12.dp, bottom = 10.dp),
            )
            VerticalScrollbar(
                scrollState = viewport.verticalScroll,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .fillMaxHeight()
                    .padding(top = HEADER_TOTAL_HEIGHT, bottom = 10.dp)
                    .testTag("ciacola-timeline-vscrollbar"),
            )
            val horizontalState = rememberScrollableState { delta ->
                viewport.scrollHorizontallyBy(-delta, surface).let { -it }
            }
            val horizontalAdapter = remember(viewport, surface) {
                TimelineHorizontalScrollbarAdapter(viewport, surface)
            }
            HorizontalScrollbar(
                scrollState = horizontalState,
                adapter = horizontalAdapter,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(end = 12.dp)
                    .testTag("ciacola-timeline-hscrollbar"),
            )
        }
    }
}

private class TimelineHorizontalScrollbarAdapter(
    private val viewport: TimelineViewportState,
    private val surface: TimelineSurface,
) : ScrollbarAdapter {
    override val scrollOffset: Double get() = viewport.scrollX(surface).toDouble()
    override val contentSize: Double get() = viewport.contentWidthPx(surface).toDouble()
    override val viewportSize: Double get() = viewport.viewportWidthPx(surface).toDouble()
    override suspend fun scrollTo(scrollOffset: Double) {
        viewport.scrollHorizontallyTo(scrollOffset.toFloat(), surface)
    }
}

private fun chooseTickStepMs(pxPerMs: Float): Int {
    val candidates = intArrayOf(10, 20, 50, 100, 200, 250, 500, 1_000, 2_000, 5_000, 10_000, 30_000)
    return candidates.firstOrNull { it * pxPerMs >= 80f } ?: candidates.last()
}

internal fun formatTimelineTick(ms: Int, stepMs: Int): String {
    val seconds = ms / 1000.0
    return when {
        stepMs >= 1000 -> "${ms / 1000}s"
        stepMs >= 100 -> String.format(Locale.US, "%.1fs", seconds)
        else -> String.format(Locale.US, "%.2fs", seconds)
    }
}

private fun effectiveBlockSpanMs(
    row: TimelineTrackRow,
    blk: TimelineBlockBar,
    liveOvr: TrackOverride?,
): Pair<Int, Int> {
    val drawStartMs = if (
        row.category != TrackCategory.SharedElement &&
        row.category != TrackCategory.LoopSubComp &&
        liveOvr?.delayMs != null
    ) {
        val stepOffset = (blk.staggerIndex ?: 0) * (liveOvr.staggerStepMs ?: 55)
        (blk.startMs - blk.delayMs) + liveOvr.delayMs + stepOffset
    } else {
        blk.startMs
    }
    val drawDurationMs = when {
        row.category == TrackCategory.SharedElement || row.category == TrackCategory.LoopSubComp -> blk.durationMs
        liveOvr?.curve?.isSpring == true -> liveOvr.curve.estimatedSpringDurationMs()
        row.category == TrackCategory.StaggerGroup && liveOvr?.durationMs != null -> {
            val count = (blk.staggerCount ?: 1).coerceAtLeast(1)
            val step = liveOvr.staggerStepMs ?: 55
            liveOvr.durationMs + (count - 1) * step
        }
        liveOvr?.durationMs != null -> liveOvr.durationMs
        else -> blk.durationMs
    }
    return drawStartMs to drawDurationMs
}

private data class TimelineBlockHit(
    val row: TimelineTrackRow,
    val block: TimelineBlockBar,
    val dragMode: DragMode,
)

private fun resolveTimelineBlockHit(
    pos: Offset,
    snapshot: DeckTimelineSnapshot,
    viewport: TimelineViewportState,
    surface: TimelineSurface,
    headerTotalPx: Float,
    rowHeightPx: Float,
    liveOverrides: Map<String, TrackOverride>,
): TimelineBlockHit? {
    if (pos.y <= headerTotalPx) return null
    val trackIdx = ((pos.y - headerTotalPx + viewport.verticalScroll.value) / rowHeightPx).toInt()
    val hitRow = snapshot.tracks.getOrNull(trackIdx) ?: return null
    val pxPerMs = viewport.pxPerMs(surface)
    val hitBlock = hitRow.blocks.firstOrNull { blk ->
        val (drawStartMs, drawDurationMs) = effectiveBlockSpanMs(hitRow, blk, liveOverrides[blk.trackId])
        val bx0 = viewport.xForTime(drawStartMs.toFloat(), surface)
        val bx1 = bx0 + max(18f, drawDurationMs * pxPerMs)
        pos.x in (bx0 - 6f)..(bx1 + 6f)
    } ?: return null

    val (drawStartMs, drawDurationMs) = effectiveBlockSpanMs(hitRow, hitBlock, liveOverrides[hitBlock.trackId])
    val bx0 = viewport.xForTime(drawStartMs.toFloat(), surface)
    val bx1 = bx0 + max(18f, drawDurationMs * pxPerMs)
    val dragMode = when {
        hitBlock.category == TrackCategory.LoopSubComp -> DragMode.ScrubPlayhead
        abs(pos.x - bx1) <= 9f -> DragMode.ResizeRightDuration
        abs(pos.x - bx0) <= 9f -> DragMode.ResizeLeftDelay
        hitBlock.category == TrackCategory.SharedElement || hitBlock.category == TrackCategory.WarpCode -> DragMode.ScrubPlayhead
        else -> DragMode.MoveBlockBeat
    }
    return TimelineBlockHit(hitRow, hitBlock, dragMode)
}

private fun pointerIconForDragMode(dragMode: DragMode?): PointerIcon = when (dragMode) {
    DragMode.ResizeLeftDelay, DragMode.ResizeRightDuration -> PointerIcon(Cursor(Cursor.E_RESIZE_CURSOR))
    DragMode.MoveBlockBeat -> PointerIcon(Cursor(Cursor.HAND_CURSOR))
    DragMode.ScrubPlayhead, DragMode.Pan, null -> PointerIcon.Default
}

private fun DrawScope.drawEllipsizedLabel(
    textMeasurer: TextMeasurer,
    text: String,
    style: TextStyle,
    left: Float,
    top: Float,
    availWidth: Float,
    clipTop: Float,
    clipBottom: Float,
) {
    val maxW = availWidth.roundToInt()
    if (maxW < 8) return
    val measured = textMeasurer.measure(
        text = text,
        style = style,
        maxLines = 1,
        softWrap = true,
        overflow = TextOverflow.Ellipsis,
        constraints = Constraints(maxWidth = maxW),
    )
    if (measured.size.width > availWidth + 1.5f) return
    clipRect(left = left - 1f, top = clipTop, right = left + availWidth, bottom = clipBottom) {
        drawText(textLayoutResult = measured, topLeft = Offset(left, top))
    }
}

@Composable
private fun MasterDeckTimelineCanvas(
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

    Canvas(
        modifier = modifier
            .clipToBounds()
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
                contentDescription = "zoom=${String.format(Locale.US, "%.4f", viewport.pxPerMs(surface))} " +
                    "scrollX=${viewport.scrollX(surface).roundToInt()} " +
                    "scrollY=${viewport.verticalScroll.value}"
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

                    fun scrubLoop(initialX: Float) {
                        controller.scrubToTimeMs(timeAt(initialX))
                    }

                    if (startPos.y <= headerTotalPx) {
                        controller.beginScrubDrag()
                        try {
                            scrubLoop(startPos.x)
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
                    Text(text = "${idx + 1}. $ch", style = JewelTheme.typography.small, color = studioSecondaryTextColor(isDark))
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
            val tickStyle = TextStyle(fontSize = 10.sp, color = studioSecondaryTextColor(isDark), fontFamily = FontFamily.Monospace)
            for (s in 0..steps) {
                val frac = s.toFloat() / steps.toFloat()
                val x = leftPad + frac * usableW
                drawLine(
                    color = globalColors.borders.normal.copy(alpha = 0.5f),
                    start = Offset(x, rulerPx - 14f),
                    end = Offset(x, size.height),
                    strokeWidth = 1f,
                )
                val label = String.format(Locale.US, "%.2fs", (frac * periodMs) / 1000.0)
                val measured = textMeasurer.measure(label, style = tickStyle, maxLines = 1, softWrap = false)
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

internal data class TimelineCanvasPalette(
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
    val selectedBarOuterRing: Color,
    val selectedBarInnerRing: Color,
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

internal fun studioSecondaryTextColor(isDark: Boolean): Color =
    if (isDark) Color(0xFFB4BCC8) else Color(0xFF475061)

internal fun buildStudioCanvasPaletteForTheme(
    isDark: Boolean,
    panelBg: Color = if (isDark) Color(0xFF2B2D30) else Color(0xFFF7F8FA),
    borderNormal: Color = if (isDark) Color(0xFF393B40) else Color(0xFFD3D5DB),
    textPrimary: Color = if (isDark) Color(0xFFDFE1E5) else Color(0xFF1E1F22),
    focusedOutline: Color = if (isDark) Color(0xFF3574F0) else Color(0xFF2563EB),
    injectedBeatLine: Color = Color(0xFFE0A82E),
    boundaryLine: Color = Color(0xFF2FA896),
    playhead: Color = Color(0xFFE5534B),
): TimelineCanvasPalette = TimelineCanvasPalette(
    bg = panelBg,
    headerBg = if (isDark) Color(0xFF1A1E27) else Color(0xFFEBEEF4),
    gridLine = borderNormal.copy(alpha = 0.45f),
    beatLine = if (isDark) Color(0xFF94A0B8) else Color(0xFF5A6578),
    injectedBeatLine = injectedBeatLine,
    boundaryLine = boundaryLine,
    playhead = playhead,
    textPrimary = textPrimary,
    textSecondary = studioSecondaryTextColor(isDark),
    selectedBorder = focusedOutline,
    selectedBarOuterRing = if (isDark) Color.White else Color(0xFF1B3A7A),
    selectedBarInnerRing = if (isDark) Color(0xFF141820) else Color.White,
    sharedBar = if (isDark) Color(0xFF6D44B8) else Color(0xFF5B21B6),
    magicTextBar = if (isDark) Color(0xFF2356C2) else Color(0xFF1D4ED8),
    warpBar = if (isDark) Color(0xFF45C4BA) else Color(0xFF52C7BE),
    staggerBar = if (isDark) Color(0xFF1F7A4C) else Color(0xFF6BD19A),
    buildBar = if (isDark) Color(0xFFEB8A44) else Color(0xFFF59E56),
    lineDrawBar = if (isDark) Color(0xFFE5B546) else Color(0xFFF4C552),
    loopBar = if (isDark) Color(0xFF1B746B) else Color(0xFF68C9BF),
    slideColors = if (isDark) {
        listOf(
            Color(0xFF334466),
            Color(0xFF4A3861),
            Color(0xFF2B5450),
            Color(0xFF5A3F37),
            Color(0xFF3B4F3B),
        )
    } else {
        listOf(
            Color(0xFFD4E1F5),
            Color(0xFFE4D8F2),
            Color(0xFFCDE9E5),
            Color(0xFFF2DDD4),
            Color(0xFFD8EAD9),
        )
    },
)

internal fun wcagRelativeLuminance(color: Color): Float {
    fun linearize(channel: Float): Float {
        val c = channel.coerceIn(0f, 1f)
        return if (c <= 0.03928f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)
    }
    return 0.2126f * linearize(color.red) +
        0.7152f * linearize(color.green) +
        0.0722f * linearize(color.blue)
}

internal fun wcagContrastRatio(fg: Color, bg: Color): Float {
    val l1 = wcagRelativeLuminance(fg)
    val l2 = wcagRelativeLuminance(bg)
    val lighter = maxOf(l1, l2)
    val darker = minOf(l1, l2)
    return (lighter + 0.05f) / (darker + 0.05f)
}

internal fun compositeColorOver(fg: Color, bg: Color): Color {
    val a = fg.alpha.coerceIn(0f, 1f)
    return Color(
        red = fg.red * a + bg.red * (1f - a),
        green = fg.green * a + bg.green * (1f - a),
        blue = fg.blue * a + bg.blue * (1f - a),
        alpha = 1f,
    )
}

internal fun barLabelInkForFill(compositedBarFill: Color): Color {
    val darkInk = Color(0xFF101318)
    val lightInk = Color.White
    return if (wcagContrastRatio(darkInk, compositedBarFill) >= wcagContrastRatio(lightInk, compositedBarFill)) {
        darkInk
    } else {
        lightInk
    }
}
