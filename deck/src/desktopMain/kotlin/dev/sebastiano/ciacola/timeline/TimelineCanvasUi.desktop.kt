package dev.sebastiano.ciacola.timeline

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
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
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.IconButton
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.Tooltip
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
}

/**
 * Top After Effects transport bar, 0-ms event keyframe `+ / -` controls, sub-composition
 * breadcrumb trail, zoom controls, and 1-click Kotlin writeback.
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

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(globalColors.panelBackground)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        // Left: Transport + Timecode + 0-ms Beat controls
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Tooltip(tooltip = { Text("Previous event beat") }) {
                IconButton(
                    onClick = { controller.stepBeat(scope, -1) },
                    modifier = Modifier.size(24.dp).testTag("ciacola-timeline-prev-beat"),
                ) {
                    Icon(
                        key = AllIconsKeys.General.ChevronLeft,
                        contentDescription = "Previous beat",
                        modifier = Modifier.size(12.dp),
                    )
                }
            }

            Tooltip(tooltip = { Text(if (controller.isPlaying) "Pause playback" else "Play timeline") }) {
                IconButton(
                    onClick = { controller.togglePlayback(scope) },
                    modifier = Modifier.size(24.dp).testTag("ciacola-timeline-play"),
                ) {
                    Icon(
                        key = if (controller.isPlaying) AllIconsKeys.Actions.Suspend else AllIconsKeys.Actions.Execute,
                        contentDescription = if (controller.isPlaying) "Pause" else "Play",
                        modifier = Modifier.size(14.dp),
                    )
                }
            }

            Tooltip(tooltip = { Text("Next event beat") }) {
                IconButton(
                    onClick = { controller.stepBeat(scope, +1) },
                    modifier = Modifier.size(24.dp).testTag("ciacola-timeline-next-beat"),
                ) {
                    Icon(
                        key = AllIconsKeys.General.ChevronRight,
                        contentDescription = "Next beat",
                        modifier = Modifier.size(12.dp),
                    )
                }
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
                    .border(1.dp, globalColors.borders.normal, RoundedCornerShape(4.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            ) {
                Text(
                    text = locationText,
                    style = JewelTheme.typography.small.copy(fontFamily = FontFamily.Monospace),
                    color = globalColors.text.normal,
                    modifier = Modifier.testTag("ciacola-timeline-timecode"),
                )
            }

            if (activeSubLoopId == null) {
                Tooltip(tooltip = { Text("Insert event keyframe (click beat) after current beat in 0 ms") }) {
                    OutlinedButton(
                        onClick = { controller.insertEventBeatAtPlayhead() },
                        modifier = Modifier.testTag("ciacola-timeline-add-beat"),
                    ) {
                        Text("+ Beat", style = JewelTheme.typography.small)
                    }
                }
                Tooltip(tooltip = { Text("Remove current event keyframe from this slide in 0 ms") }) {
                    OutlinedButton(
                        enabled = sceneBeatsCount > 1,
                        onClick = { controller.removeEventBeatAtPlayhead() },
                        modifier = Modifier.testTag("ciacola-timeline-remove-beat"),
                    ) {
                        Text("- Beat", style = JewelTheme.typography.small)
                    }
                }
            }
        }

        // Center: Breadcrumb (Master Deck vs Loop Sub-Composition)
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

        // Right: Zoom + Bake/Reset
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            OutlinedButton(
                onClick = {
                    controller.zoomPxPerSecond = (controller.zoomPxPerSecond * 0.8f).coerceIn(70f, 800f)
                },
            ) {
                Text("−", style = JewelTheme.typography.small)
            }
            OutlinedButton(
                onClick = {
                    controller.zoomPxPerSecond = (controller.zoomPxPerSecond * 1.25f).coerceIn(70f, 800f)
                },
            ) {
                Text("+", style = JewelTheme.typography.small)
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

/**
 * Synchronized left track headers + After Effects multi-track timeline canvas.
 */
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
    val verticalScroll = rememberScrollState()
    val horizontalScroll = rememberScrollState()
    val globalColors = JewelTheme.globalColors

    Row(modifier = modifier.fillMaxSize()) {
        // Left Layer Header List
        Column(
            modifier = Modifier
                .width(LAYER_COLUMN_WIDTH)
                .fillMaxHeight()
                .background(globalColors.panelBackground),
        ) {
            // Fixed header matching Slide Ribbon + Ruler height
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
                    .verticalScroll(verticalScroll),
            ) {
                for (track in snapshot.tracks) {
                    val isSelected = controller.selectedTrackId == track.trackId.substringBefore("#")
                    val loopBlock = track.blocks.firstOrNull { it.loopId != null }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(TRACK_ROW_HEIGHT)
                            .background(
                                if (isSelected) {
                                    globalColors.outlines.focused.copy(alpha = 0.16f)
                                } else {
                                    Color.Transparent
                                }
                            )
                            .clickable { controller.selectTrack(track.trackId) }
                            .padding(
                                start = (10 + track.indentLevel * 14).dp,
                                end = 6.dp,
                            )
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
                                            controller.enterLoopSubComp(
                                                scope = scope,
                                                loopId = loopBlock.loopId,
                                                sceneIndex = loopBlock.sceneIndex,
                                            )
                                        }
                                        .padding(horizontal = 5.dp, vertical = 1.dp)
                                        .testTag("ciacola-timeline-open-loop"),
                                ) {
                                    Text(
                                        text = "Sub-Comp ▸",
                                        style = JewelTheme.typography.small,
                                        color = globalColors.text.normal,
                                    )
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }

        Divider(Orientation.Vertical, Modifier.fillMaxHeight())

        // Right Synchronized Multi-Track Canvas
        MasterDeckTimelineCanvas(
            controller = controller,
            snapshot = snapshot,
            verticalScrollOffsetPx = verticalScroll.value,
            horizontalScroll = horizontalScroll,
            modifier = Modifier.weight(1f).fillMaxHeight(),
        )
    }
}

@Composable
private fun MasterDeckTimelineCanvas(
    controller: TimelineStudioController,
    snapshot: DeckTimelineSnapshot,
    verticalScrollOffsetPx: Int,
    horizontalScroll: androidx.compose.foundation.ScrollState,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val textMeasurer = rememberTextMeasurer()
    val globalColors = JewelTheme.globalColors
    val palette = JewelTheme.colorPalette
    val isDark = JewelTheme.isDark

    val pxPerMs = controller.zoomPxPerSecond / 1000f
    val canvasWidthDp = with(density) {
        max(760f, snapshot.totalDurationMs * pxPerMs + 140f).toDp()
    }
    val slideRibbonPx = with(density) { SLIDE_RIBBON_HEIGHT.toPx() }
    val rulerHeightPx = with(density) { TIME_RULER_HEIGHT.toPx() }
    val headerTotalPx = slideRibbonPx + rulerHeightPx
    val rowHeightPx = with(density) { TRACK_ROW_HEIGHT.toPx() }

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

    val labelTextStyle = TextStyle(
        fontSize = 10.sp,
        fontWeight = FontWeight.Medium,
        color = Color.White,
    )
    val rulerTextStyle = TextStyle(
        fontSize = 9.sp,
        fontFamily = FontFamily.Monospace,
        color = colors.textSecondary,
    )

    val playheadMs = controller.currentPlayheadTimeMs
    LaunchedEffect(playheadMs, pxPerMs) {
        val px = (16f + playheadMs * pxPerMs).roundToInt()
        val viewport = horizontalScroll.viewportSize
        if (viewport > 80) {
            val minVisible = horizontalScroll.value + 32
            val maxVisible = horizontalScroll.value + viewport - 48
            if (px < minVisible || px > maxVisible) {
                horizontalScroll.scrollTo((px - viewport / 3).coerceAtLeast(0))
            }
        }
    }

    Box(
        modifier = modifier
            .horizontalScroll(horizontalScroll)
            .clipToBounds(),
    ) {
        Canvas(
            modifier = Modifier
                .width(canvasWidthDp)
                .fillMaxHeight()
                .testTag("ciacola-timeline-canvas")
                .pointerInput(snapshot, pxPerMs, verticalScrollOffsetPx) {
                    var lastClickBlockId: String? = null
                    var lastClickMillis = 0L

                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        val startPos = down.position

                        if (startPos.y <= headerTotalPx) {
                            // Dragging in Slide Ribbon or Time Ruler -> live scrub playhead!
                            val initialMs = ((startPos.x - 16f) / pxPerMs).roundToInt()
                            controller.scrubToTimeMs(scope, initialMs)
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull() ?: break
                                if (!change.pressed) break
                                change.consume()
                                val ms = ((change.position.x - 16f) / pxPerMs).roundToInt()
                                controller.scrubToTimeMs(scope, ms)
                            }
                        } else {
                            val trackIdx = ((startPos.y - headerTotalPx + verticalScrollOffsetPx) / rowHeightPx).toInt()
                            val hitRow = snapshot.tracks.getOrNull(trackIdx)
                            val hitBlock = hitRow?.blocks?.firstOrNull { blk ->
                                val bx0 = 16f + blk.startMs * pxPerMs
                                val bx1 = bx0 + max(18f, blk.durationMs * pxPerMs)
                                startPos.x in (bx0 - 6f)..(bx1 + 6f)
                            }

                            if (hitBlock == null) {
                                val ms = ((startPos.x - 16f) / pxPerMs).roundToInt()
                                controller.scrubToTimeMs(scope, ms)
                            } else {
                                val nowWall = System.currentTimeMillis()
                                if (hitBlock.loopId != null &&
                                    lastClickBlockId == hitBlock.id &&
                                    (nowWall - lastClickMillis) < 380L
                                ) {
                                    controller.enterLoopSubComp(scope, hitBlock.loopId, hitBlock.sceneIndex)
                                    lastClickBlockId = null
                                    return@awaitEachGesture
                                }
                                lastClickBlockId = hitBlock.id
                                lastClickMillis = nowWall

                                controller.selectTrack(hitBlock.trackId)

                                val bx0 = 16f + hitBlock.startMs * pxPerMs
                                val bx1 = bx0 + max(18f, hitBlock.durationMs * pxPerMs)
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
                                    val deltaMs = (dxPx / pxPerMs).roundToInt()

                                    when (dragMode) {
                                        DragMode.ResizeRightDuration -> {
                                            val newDur = (origDuration + deltaMs).coerceIn(60, 4_000)
                                            controller.overridesStore.updateTrack(hitBlock.trackId) {
                                                it.copy(durationMs = newDur)
                                            }
                                        }
                                        DragMode.ResizeLeftDelay -> {
                                            val newDelay = (origDelay + deltaMs).coerceIn(0, 2_000)
                                            controller.overridesStore.updateTrack(hitBlock.trackId) {
                                                it.copy(delayMs = newDelay)
                                            }
                                        }
                                        DragMode.MoveBlockBeat -> {
                                            val cursorTimeMs = ((change.position.x - 16f) / pxPerMs).roundToInt()
                                            val sameSlideBeats = snapshot.eventBeats.filter {
                                                it.sceneIndex == hitBlock.sceneIndex && !it.isSlideBoundary
                                            }
                                            val nearest = sameSlideBeats.minByOrNull { abs(it.timeMs - cursorTimeMs) }
                                            if (nearest != null) {
                                                val targetTrigger = (nearest.frameIndex + 1)
                                                    .coerceIn(0, sameSlideBeats.size - 1)
                                                controller.overridesStore.updateTrack(hitBlock.trackId) {
                                                    it.copy(enterBeat = targetTrigger)
                                                }
                                            }
                                        }
                                        DragMode.ScrubPlayhead -> {
                                            val ms = ((change.position.x - 16f) / pxPerMs).roundToInt()
                                            controller.scrubToTimeMs(scope, ms)
                                        }
                                    }
                                }
                            }
                        }
                    }
                },
        ) {
            val leftPad = 16f
            drawRect(color = colors.bg)

            // 1. Track rows background + vertical dotted Event Keyframe lines spanning full Y!
            clipRect(top = headerTotalPx) {
                snapshot.tracks.forEachIndexed { rIdx, row ->
                    val y = headerTotalPx + rIdx * rowHeightPx - verticalScrollOffsetPx
                    if (y + rowHeightPx >= headerTotalPx && y <= size.height) {
                        val isSelected = controller.selectedTrackId == row.trackId.substringBefore("#")
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

                // Full-height vertical dotted Event Keyframe lines behind blocks!
                val beatDash = PathEffect.dashPathEffect(floatArrayOf(5f, 5f), 0f)
                for (beat in snapshot.eventBeats) {
                    val x = leftPad + beat.timeMs * pxPerMs
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

                // Animation Block Bars
                snapshot.tracks.forEachIndexed { rIdx, row ->
                    val yTop = headerTotalPx + rIdx * rowHeightPx - verticalScrollOffsetPx + 4f
                    val barH = rowHeightPx - 8f
                    if (yTop + barH < headerTotalPx || yTop > size.height) return@forEachIndexed

                    val isSelected = controller.selectedTrackId == row.trackId.substringBefore("#")
                    val fill = colors.colorForCategory(row.category)
                    for (blk in row.blocks) {
                        val x0 = leftPad + blk.startMs * pxPerMs
                        val w = max(20f, blk.durationMs * pxPerMs)
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
                        // Grip handles on editable bars
                        if (row.category != TrackCategory.LoopSubComp && w > 28f) {
                            drawLine(
                                color = Color.White.copy(alpha = 0.65f),
                                start = Offset(x0 + 3f, yTop + 4f),
                                end = Offset(x0 + 3f, yTop + barH - 4f),
                                strokeWidth = 1.5f,
                            )
                            drawLine(
                                color = Color.White.copy(alpha = 0.65f),
                                start = Offset(x0 + w - 3f, yTop + 4f),
                                end = Offset(x0 + w - 3f, yTop + barH - 4f),
                                strokeWidth = 1.5f,
                            )
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

            // 2. Fixed Top Header: Contiguous Slide Ribbon (with Overlapping Transitions!) + Time Ruler
            drawRect(
                color = colors.headerBg,
                topLeft = Offset.Zero,
                size = Size(size.width, headerTotalPx),
            )

            // Contiguous Slide Section Bars + Diagonal-Hatched Overlap Zones
            for (slide in snapshot.slides) {
                val sx = leftPad + slide.startMs * pxPerMs
                val sw = max(24f, (slide.endMs - slide.startMs) * pxPerMs)
                val baseColor = colors.slideColors[slide.sceneIndex % colors.slideColors.size]
                drawRoundRect(
                    color = baseColor.copy(alpha = 0.85f),
                    topLeft = Offset(sx, 2f),
                    size = Size(sw, slideRibbonPx - 4f),
                    cornerRadius = CornerRadius(4f, 4f),
                )
                if (slide.overlapOutMs > 0) {
                    val ovStart = leftPad + (slide.endMs - slide.overlapOutMs) * pxPerMs
                    val ovW = slide.overlapOutMs * pxPerMs
                    drawHatchedOverlapZone(
                        left = ovStart,
                        top = 2f,
                        width = ovW,
                        height = slideRibbonPx - 4f,
                        accent = colors.boundaryLine,
                    )
                }
                val nonOverlapRight = sx + sw - slide.overlapOutMs * pxPerMs - 6f
                if (nonOverlapRight > sx + 14f) {
                    clipRect(left = sx + 6f, top = 2f, right = nonOverlapRight, bottom = slideRibbonPx - 2f) {
                        drawText(
                            textMeasurer = textMeasurer,
                            text = slide.label,
                            topLeft = Offset(sx + 6f, 5f),
                            style = labelTextStyle,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            size = Size(nonOverlapRight - (sx + 6f), slideRibbonPx - 4f),
                        )
                    }
                }
            }

            // Time Ruler ticks + Event Beat Diamonds
            var tickMs = 0
            while (tickMs <= snapshot.totalDurationMs + 250) {
                val tx = leftPad + tickMs * pxPerMs
                val isMajor = tickMs % 500 == 0
                drawLine(
                    color = colors.gridLine,
                    start = Offset(tx, if (isMajor) slideRibbonPx + 4f else slideRibbonPx + 14f),
                    end = Offset(tx, headerTotalPx),
                    strokeWidth = 1f,
                )
                if (isMajor) {
                    drawText(
                        textMeasurer = textMeasurer,
                        text = "${tickMs / 1000}.${(tickMs % 1000) / 100}s",
                        topLeft = Offset(tx + 3f, slideRibbonPx + 2f),
                        style = rulerTextStyle,
                    )
                }
                tickMs += 100
            }

            for (beat in snapshot.eventBeats) {
                val bx = leftPad + beat.timeMs * pxPerMs
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

            drawLine(
                color = colors.gridLine,
                start = Offset(0f, headerTotalPx),
                end = Offset(size.width, headerTotalPx),
                strokeWidth = 1.5f,
            )

            // 3. Red After Effects Playhead Cap + Vertical Stem
            val playheadX = leftPad + controller.currentPlayheadTimeMs * pxPerMs
            drawLine(
                color = colors.playhead,
                start = Offset(playheadX, 0f),
                end = Offset(playheadX, size.height),
                strokeWidth = 2f,
            )
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

/**
 * Dedicated sub-composition timeline (`0..periodMs`) when the user drills "into" an ambient loop.
 */
@Composable
private fun LoopSubCompTimelineView(
    controller: TimelineStudioController,
    loopId: String,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
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
                    Text(
                        text = "${idx + 1}. $ch",
                        style = JewelTheme.typography.small,
                        color = globalColors.text.info,
                    )
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
                        val initMs = (((down.position.x - 16f) / usableW) * periodMs).roundToInt()
                        controller.scrubToTimeMs(scope, initMs)
                        while (true) {
                            val ev = awaitPointerEvent()
                            val ch = ev.changes.firstOrNull() ?: break
                            if (!ch.pressed) break
                            ch.consume()
                            val ms = (((ch.position.x - 16f) / usableW) * periodMs).roundToInt()
                            controller.scrubToTimeMs(scope, ms)
                        }
                    }
                },
        ) {
            val leftPad = 16f
            val usableW = (size.width - 32f).coerceAtLeast(1f)
            val teal = palette.tealOrNull(if (isDark) 6 else 5) ?: Color(0xFF35B5AC)
            val playheadColor = palette.redOrNull(if (isDark) 6 else 5) ?: Color(0xFFE5534B)

            drawRect(color = globalColors.panelBackground)

            // Ticks across 0..periodMs
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
                val textX = if (s == steps) {
                    (x - measured.size.width - 3f).coerceAtLeast(0f)
                } else {
                    x + 3f
                }
                drawText(
                    textLayoutResult = measured,
                    topLeft = Offset(textX, rulerPx - 22f),
                )
            }
            drawLine(
                color = globalColors.borders.normal,
                start = Offset(0f, rulerPx),
                end = Offset(size.width, rulerPx),
                strokeWidth = 1.5f,
            )

            // Draw harmonic wave blocks
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
            drawLine(
                color = playheadColor,
                start = Offset(px, 0f),
                end = Offset(px, size.height),
                strokeWidth = 2f,
            )
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
        drawRect(
            color = accent.copy(alpha = 0.20f),
            topLeft = Offset(left, top),
            size = Size(width, height),
        )
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
