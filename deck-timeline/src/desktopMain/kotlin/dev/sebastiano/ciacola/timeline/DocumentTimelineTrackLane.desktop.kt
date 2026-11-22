package dev.sebastiano.ciacola.timeline

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sebastiano.ciacola.document.Curve
import dev.sebastiano.ciacola.document.CurveEvaluator
import dev.sebastiano.ciacola.document.DeckDocument
import dev.sebastiano.ciacola.document.Slot
import dev.sebastiano.ciacola.document.SlotPhase
import dev.sebastiano.ciacola.document.Timing
import dev.sebastiano.ciacola.ui.rememberAccessibleFocusRingColor
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.GroupHeader
import org.jetbrains.jewel.ui.component.InfoText
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Slider
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.Tooltip
import org.jetbrains.jewel.ui.typography

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
internal fun DocumentTrackRowItem(
    controller: DocumentTimelineController,
    row: TimelineTrackRow,
    ref: DocumentTimelineTrackRef?,
    document: DeckDocument,
    surface: TimelineSurface,
    isExpandedText: Boolean,
) {
    val globalColors = JewelTheme.globalColors
    val density = LocalDensity.current
    val isSelected = when (val sel = controller.selection) {
        is DocumentTimelineSelection.Track -> sel.trackId == row.trackId
        is DocumentTimelineSelection.Loop -> (ref as? DocumentTimelineTrackRef.LoopTrackBar)?.loopId == sel.loopId
        else -> false
    }
    DisposableEffect(controller, row.trackId) {
        onDispose { if (controller.hoveredTrackId == row.trackId) controller.hoveredTrackId = null }
    }
    val hasManualDelay = hasManualStaggerOverride(document, ref)
    val isExpandable = controller.isExpandableTextTrack(ref)
    val accent = studioAccentColor()
    val isNodeHighlighted = !isSelected && controller.highlightedNodeId != null &&
        ref?.referencedNodeId() == controller.highlightedNodeId
    val rawId = when (ref) {
        is DocumentTimelineTrackRef.SlideBuildTrack -> "${ref.slideId}/${ref.nodeId} · build ${ref.buildIndex}"
        is DocumentTimelineTrackRef.SpecimenTrackBar -> "${ref.slideId}/${ref.nodeId} · ${ref.trackName}"
        is DocumentTimelineTrackRef.TransitionTrackBar -> "${ref.fromSlideId} → ${ref.toSlideId} / ${ref.nodeId}"
        is DocumentTimelineTrackRef.LoopTrackBar -> ref.loopId
        null -> row.trackId
    }

    ContextMenuArea(
        items = {
            buildList {
                if (ref is DocumentTimelineTrackRef.SlideBuildTrack && !controller.store.isReadOnly) {
                    add(
                        ContextMenuItem("Delete Build") {
                            controller.deleteBuild(ref.slideId, ref.nodeId, ref.buildIndex)
                            controller.clearSelection()
                        },
                    )
                }
                if (ref is DocumentTimelineTrackRef.LoopTrackBar) {
                    add(ContextMenuItem("Open Loop Timeline") { controller.enterLoopSubComp(ref.loopId) })
                }
                add(ContextMenuItem("Copy ID") { controller.clipboardWriter(rawId) })
            }
        },
    ) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(DOC_TRACK_ROW_HEIGHT_DP)
            .background(
                when {
                    isSelected -> accent.copy(alpha = 0.20f)
                    isNodeHighlighted -> accent.copy(alpha = 0.08f)
                    else -> Color.Transparent
                },
            )
            .onPointerEvent(PointerEventType.Enter) { controller.hoveredTrackId = row.trackId }
            .onPointerEvent(PointerEventType.Exit) {
                if (controller.hoveredTrackId == row.trackId) controller.hoveredTrackId = null
            }
            .clickable {
                if (ref is DocumentTimelineTrackRef.LoopTrackBar) {
                    controller.selectLoop(ref.loopId)
                } else {
                    controller.selectTrack(row.trackId, row.blocks.firstOrNull()?.id)
                }
            }
            .testTag("ciacola-doc-track-row-${row.trackId}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Track Header cell.
        Row(
            modifier = Modifier
                .width(controller.viewport.layerColumnWidthDp.dp)
                .fillMaxHeight()
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (isExpandable) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(3.dp))
                        .clickable { controller.toggleExpandTextTrack(row.trackId) }
                        .padding(horizontal = 3.dp, vertical = 1.dp)
                        .testTag("ciacola-doc-track-expand-${row.trackId}"),
                ) {
                    Text(
                        text = if (isExpandedText) "▾" else "▸",
                        style = JewelTheme.typography.small,
                        color = globalColors.text.info,
                    )
                }
            }
            Tooltip(tooltip = { Text("${row.label}\n$rawId") }, modifier = Modifier.weight(1f)) {
                Text(
                    text = row.label,
                    style = studioLabelStyle(),
                    color = globalColors.text.normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("ciacola-doc-track-label-${row.trackId}"),
                )
            }
            if (hasManualDelay) {
                Tooltip(tooltip = { Text("Manual stagger override active — click track to inspect or reset") }) {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(3.dp))
                            .background(Color(0x33F5A623))
                            .padding(horizontal = 4.dp, vertical = 1.dp)
                            .testTag("ciacola-track-manual-badge-${row.trackId}"),
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFF5A623)),
                        )
                        Text(
                            text = "M",
                            style = JewelTheme.typography.small.copy(fontWeight = FontWeight.SemiBold),
                            color = Color(0xFFF5A623),
                            maxLines = 1,
                        )
                    }
                }
            }
            if (ref is DocumentTimelineTrackRef.LoopTrackBar) {
                Tooltip(tooltip = { Text("Open the loop timeline") }) {
                    Box(
                        modifier = Modifier
                            .border(1.dp, globalColors.borders.focused, RoundedCornerShape(4.dp))
                            .clickable { controller.enterLoopSubComp(ref.loopId) }
                            .padding(horizontal = 4.dp, vertical = 1.dp)
                            .testTag("ciacola-timeline-open-loop-${ref.loopId}"),
                    ) {
                        Text("Loop ▸", style = studioCaptionStyle(), color = globalColors.text.normal)
                    }
                }
            }
        }

        Divider(Orientation.Vertical, Modifier.fillMaxHeight().padding(horizontal = 2.dp))

        // Draggable Track Bar area (clipped cleanly without clamping scrolled-off bars to x=0).
        // Pointer drag is attached to this STATIONARY lane Box (keyed ONLY on stable `row.trackId`,
        // NEVER `pxPerMs` or `totalDurationMs`) so moving/resizing a bar past `segmentMs` never
        // self-cancels or suffers layout-shift coordinate feedback.
        val block = row.blocks.firstOrNull()
        val pxPerMs = controller.viewport.pxPerMs(surface)
        val barLeftPx = if (block != null) controller.viewport.xForTime(block.startMs.toFloat(), surface) else 0f
        val barWidthPx = if (block != null) (block.durationMs * pxPerMs).coerceAtLeast(18f) else 0f
        val resizeHandlePx = with(density) { 7.dp.toPx() }

        val latestPxPerMs by rememberUpdatedState(pxPerMs)
        val latestBlock by rememberUpdatedState(block)
        val latestRef by rememberUpdatedState(ref)
        val latestRow by rememberUpdatedState(row)
        val latestBarLeftPx by rememberUpdatedState(barLeftPx)
        val latestBarWidthPx by rememberUpdatedState(barWidthPx)

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .clipToBounds()
                .padding(vertical = 4.dp)
                .pointerInput(row.trackId) {
                    var gestureOpen = false
                    try {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val b = latestBlock ?: return@awaitEachGesture
                            val r = latestRow
                            val rf = latestRef ?: return@awaitEachGesture
                            val left = latestBarLeftPx
                            val width = latestBarWidthPx
                            val right = left + width
                            val effectiveHandleW = resizeHandlePx.coerceAtMost(width * 0.3f)
                            val resizeStart = right - effectiveHandleW
                            val dragMode = when {
                                down.position.x in resizeStart..(right + 4f) -> 2
                                down.position.x in (left - 4f)..resizeStart -> 1
                                else -> 0
                            }
                            if (dragMode == 0) return@awaitEachGesture

                            val dragBaseRelMs = b.delayMs
                            val resizeBaseDur = b.durationMs
                            var dragAccumPx = 0f

                            val slopChange = awaitHorizontalTouchSlopOrCancellation(down.id) { change, over ->
                                change.consume()
                                dragAccumPx = over
                            }
                            if (slopChange == null) {
                                return@awaitEachGesture
                            }

                            controller.selectTrack(r.trackId, b.id)
                            gestureOpen = true
                            controller.beginGesture(
                                if (dragMode == 1) "Move ${r.label}" else "Resize ${r.label}",
                            )

                            fun applyAccum() {
                                val currentRef = latestRef ?: return
                                val deltaMs = (dragAccumPx / latestPxPerMs.coerceAtLeast(0.0001f)).roundToInt()
                                if (dragMode == 1) {
                                    controller.dragTrackBarStart(
                                        ref = currentRef,
                                        newRelativeStartMs = (dragBaseRelMs + deltaMs).coerceAtLeast(0),
                                        useActiveGesture = true,
                                    )
                                } else {
                                    controller.resizeTrackBarDuration(
                                        ref = currentRef,
                                        newDurationMs = (resizeBaseDur + deltaMs).coerceAtLeast(0),
                                        useActiveGesture = true,
                                    )
                                }
                            }

                            applyAccum()
                            val completed = horizontalDrag(slopChange.id) { change ->
                                val dx = change.positionChange().x
                                change.consume()
                                dragAccumPx += dx
                                applyAccum()
                            }
                            if (completed) {
                                gestureOpen = false
                                controller.commitActiveGesture()
                            } else {
                                gestureOpen = false
                                controller.cancelActiveGesture()
                            }
                        }
                    } finally {
                        if (gestureOpen) {
                            gestureOpen = false
                            controller.cancelActiveGesture()
                        }
                    }
                },
        ) {
            if (block != null && ref != null) {
                val barColor = when (row.category) {
                    TrackCategory.MagicText -> Color(0xFF4FB3D9)
                    TrackCategory.WarpCode -> Color(0xFFD59BF6)
                    TrackCategory.SharedElement -> Color(0xFF66C285)
                    TrackCategory.LoopSubComp -> Color(0xFFF5A623)
                    else -> Color(0xFF7B93DB)
                }

                Box(
                    modifier = Modifier
                        .offset { IntOffset(barLeftPx.roundToInt(), 0) }
                        .width(with(density) { barWidthPx.roundToInt().toDp() })
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(3.dp))
                        .background(barColor.copy(alpha = if (isSelected) 0.95f else 0.72f))
                        .border(
                            width = if (isSelected) 1.5.dp else 1.dp,
                            color = if (isSelected) Color.White else globalColors.borders.normal,
                            shape = RoundedCornerShape(3.dp),
                        )
                        .testTag("ciacola-doc-track-bar-${row.trackId}"),
                ) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .width(7.dp)
                            .fillMaxHeight()
                            .background(Color.White.copy(alpha = 0.3f))
                            .testTag("ciacola-doc-track-resize-${row.trackId}"),
                    )
                }
            }
        }
    }
    }
}

/**
 * Inline expanded text sub-rows rendered directly beneath an expanded text morph or text delivery
 * track in the Timeline: shows `Exit / Move / Enter` phase sub-bars AND draggable per-fragment
 * ticks (`"token#0"`, `"-old#1"`, etc.) positioned on the master timeline scale.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun InlineExpandedTextTrackSubRows(
    controller: DocumentTimelineController,
    row: TimelineTrackRow,
    ref: DocumentTimelineTrackRef,
    surface: TimelineSurface,
) {
    val globalColors = JewelTheme.globalColors
    val density = LocalDensity.current
    val slots = controller.fragmentSlotsFor(ref)
    if (slots.isEmpty()) return

    val manualMap = controller.currentFragmentManualDelays(ref)
    val morphSpec = controller.currentTextMorphSpec(ref)
    val resolvedPhases = controller.resolvedTextMorphPhases(ref)
    val trackAbsStartMs = row.blocks.firstOrNull()?.startMs ?: 0
    val pxPerMs = controller.viewport.pxPerMs(surface)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(globalColors.borders.normal.copy(alpha = 0.08f))
            .testTag("ciacola-timeline-expanded-text-${row.trackId}"),
    ) {
        // 1. Inline Exit / Move / Enter phase sub-bars (for text morphs).
        if (morphSpec != null) {
            val phaseEntries = listOf(
                SlotPhase.Exit to resolvedPhases.exit,
                SlotPhase.Move to resolvedPhases.move,
                SlotPhase.Enter to resolvedPhases.enter,
            )
            for ((phase, timing) in phaseEntries) {
                val pDelay = timing.delayMs ?: 0
                val pDur = effectivePhaseDurationMs(timing)
                val accent = phaseAccentColor(phase)
                val leftPx = controller.viewport.xForTime((trackAbsStartMs + pDelay).toFloat(), surface).roundToInt()
                val widthPx = (pDur * pxPerMs).roundToInt().coerceAtLeast(16)

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(DOC_SUB_ROW_HEIGHT_DP)
                        .testTag("ciacola-timeline-phase-subrow-${row.trackId}-${phase.name.lowercase()}"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .width(controller.viewport.layerColumnWidthDp.dp)
                            .fillMaxHeight()
                            .padding(start = 24.dp, end = 8.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Text(
                            text = "${phase.name} (+${pDelay}ms · ${pDur}ms)",
                            style = JewelTheme.typography.small,
                            color = accent,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Divider(Orientation.Vertical, Modifier.fillMaxHeight().padding(horizontal = 2.dp))
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clipToBounds()
                            .padding(vertical = 4.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .offset { IntOffset(leftPx, 0) }
                                .width(with(density) { widthPx.toDp() })
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(3.dp))
                                .background(accent.copy(alpha = 0.55f)),
                        )
                    }
                }
            }
        }

        // 2. Draggable per-fragment ticks inline on the master timeline scale.
        val baseTokenCounts = remember(slots) { computeFragmentBaseTokenCounts(slots) }
        for (slot in slots) {
            val isManual = slot.address in manualMap
            val manualMs = manualMap[slot.address]
            val accent = phaseAccentColor(slot.phase)
            val baseToken = extractFragmentBaseToken(slot.address)
            val formatted = formatHumanFragmentLabel(
                address = slot.address,
                phase = slot.phase,
                duplicateCountForBaseToken = baseTokenCounts[baseToken] ?: 1,
            )
            val tickAbsStartMs = trackAbsStartMs + slot.startMs
            val leftPx = controller.viewport.xForTime(tickAbsStartMs.toFloat(), surface).roundToInt()
            val widthPx = (slot.durationMs * pxPerMs).roundToInt().coerceAtLeast(16)

            val latestPxPerMs by rememberUpdatedState(pxPerMs)
            val latestRef by rememberUpdatedState(ref)
            val latestSlot by rememberUpdatedState(slot)
            val latestManualMs by rememberUpdatedState(manualMs)

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(DOC_SUB_ROW_HEIGHT_DP)
                    .testTag("ciacola-timeline-fragment-subrow-${slot.address}"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier
                        .width(controller.viewport.layerColumnWidthDp.dp)
                        .fillMaxHeight()
                        .padding(start = 24.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = formatted.displayLabel,
                        style = JewelTheme.typography.small,
                        color = if (isManual) Color(0xFFF5A623) else accent,
                        fontWeight = if (isManual) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f)
                            .semantics { contentDescription = formatted.tooltipText(slot.startMs, slot.endMs) },
                    )
                    Text(
                        text = "${slot.startMs}ms",
                        style = JewelTheme.typography.small.copy(fontFamily = FontFamily.Monospace),
                        color = globalColors.text.info,
                        maxLines = 1,
                    )
                }
                Divider(Orientation.Vertical, Modifier.fillMaxHeight().padding(horizontal = 2.dp))
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clipToBounds()
                        .padding(vertical = 4.dp),
                ) {
                    Tooltip(tooltip = { Text(formatted.tooltipText(slot.startMs, slot.endMs)) }) {
                        Box(
                            modifier = Modifier
                                .offset { IntOffset(leftPx, 0) }
                                .width(with(density) { widthPx.toDp() })
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(3.dp))
                                .background(if (isManual) Color(0xFFF5A623).copy(alpha = 0.85f) else accent.copy(alpha = 0.78f))
                                // Keyed ONLY on stable (trackId, address).
                                .pointerInput(row.trackId, slot.address) {
                                    var baseDelay = 0
                                    var accumPx = 0f
                                    var gestureOpen = false
                                    try {
                                        detectHorizontalDragGestures(
                                            onDragStart = {
                                                val s = latestSlot
                                                baseDelay = latestManualMs ?: s.startMs
                                                accumPx = 0f
                                                controller.selectedFragmentAddress = s.address
                                                gestureOpen = true
                                                controller.beginGesture("Drag fragment ${s.address}")
                                            },
                                            onDragEnd = {
                                                gestureOpen = false
                                                controller.commitActiveGesture()
                                            },
                                            onDragCancel = {
                                                gestureOpen = false
                                                controller.cancelActiveGesture()
                                            },
                                            onHorizontalDrag = { change, dragAmount ->
                                                change.consume()
                                                accumPx += dragAmount
                                                val deltaMs = (accumPx / latestPxPerMs.coerceAtLeast(0.0001f)).roundToInt()
                                                val nextMs = (baseDelay + deltaMs).coerceAtLeast(0)
                                                controller.setFragmentManualDelay(
                                                    ref = latestRef,
                                                    fragmentAddress = latestSlot.address,
                                                    delayMs = nextMs,
                                                    useActiveGesture = true,
                                                )
                                            },
                                        )
                                    } finally {
                                        if (gestureOpen) {
                                            gestureOpen = false
                                            controller.cancelActiveGesture()
                                        }
                                    }
                                }
                                .testTag("ciacola-timeline-fragment-tick-${slot.address}"),
                        )
                    }
                }
            }
        }
    }
}

private fun effectivePhaseDurationMs(timing: Timing): Int {
    val curve = timing.curve
    return if (curve is Curve.Spring) {
        CurveEvaluator.springSettleDurationMs(curve.damping, curve.stiffness)
    } else {
        (timing.durationMs ?: 500).coerceAtLeast(0)
    }
}

internal fun hasManualStaggerOverride(
    document: DeckDocument,
    ref: DocumentTimelineTrackRef?,
): Boolean = when (ref) {
    is DocumentTimelineTrackRef.SlideBuildTrack -> {
        val slide = document.slides.firstOrNull { it.id == ref.slideId }
        val beat = slide?.beats?.getOrNull(ref.beatIndex)
        ref.nodeId in beat?.stagger?.manual.orEmpty()
    }
    is DocumentTimelineTrackRef.TransitionTrackBar -> {
        val slide = document.slides.firstOrNull { it.id == ref.fromSlideId }
        ref.nodeId in slide?.transition?.stagger?.manual.orEmpty()
    }
    else -> false
}
