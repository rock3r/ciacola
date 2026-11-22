package dev.sebastiano.ciacola.timeline

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import dev.sebastiano.ciacola.document.SlideSpec
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Text

internal val DOC_TRACK_ROW_HEIGHT_DP = 30.dp
internal val DOC_SUB_ROW_HEIGHT_DP = 24.dp
internal val DOC_INSPECTOR_WIDTH_DP = 320.dp

/**
 * Full analytical document-backed Timeline Studio panel bound to [DocumentTimelineController]
 * and [dev.sebastiano.ciacola.document.DeckDocumentStore].
 *
 * Layout: responsive transport bar, one time-aligned header band (slides, beats, transitions),
 * the time ruler, then virtualized track rows sharing the same time coordinates.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DocumentTimelineStudioPanel(
    controller: DocumentTimelineController,
    modifier: Modifier = Modifier,
    surface: TimelineSurface = TimelineSurface.Docked,
    showInlineInspector: Boolean = true,
    leadingToolbarContent: (@Composable () -> Unit)? = null,
    headerLeadingContent: (@Composable () -> Unit)? = null,
    onToggleExpanded: (() -> Unit)? = null,
) {
    // Observe store revision & document mutations so Timeline recomposes incrementally.
    val revision = controller.store.revision
    val currentDoc = controller.store.document
    LaunchedEffect(revision, currentDoc) {
        controller.refreshSnapshot()
    }

    val globalColors = JewelTheme.globalColors
    val isDark = JewelTheme.isDark
    val docSnapshot = controller.snapshot
    val timelineSnapshot = docSnapshot.snapshot
    val focusState = LocalStudioA11yFocusState.current ?: remember { StudioA11yFocusState() }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var canvasWidthPx by remember { mutableIntStateOf(800) }
    val textMeasurer = rememberTextMeasurer()
    val textCache = remember(textMeasurer) { TimelineTextLayoutCache() }

    val density = LocalDensity.current
    LaunchedEffect(timelineSnapshot.totalDurationMs, canvasWidthPx, density.density, surface) {
        controller.viewport.contentDurationMs = timelineSnapshot.totalDurationMs.coerceAtLeast(1)
        controller.viewport.updateSurfaceMetrics(
            surface = surface,
            widthPx = canvasWidthPx.toFloat().coerceAtLeast(1f),
            surfaceDensity = density.density,
        )
    }

    val activeSceneIndex = controller.currentPresentedSceneIndex
    val activeSlideId = controller.currentPresentedSlideId
    val filteredTracks = remember(docSnapshot, controller.trackFilterMode, activeSceneIndex, controller.trackSearchQuery) {
        filterTracksForScope(docSnapshot, controller.trackFilterMode, activeSceneIndex, controller.trackSearchQuery)
    }

    // Stage <-> Timeline viewport <-> track list sync when the slide or scope changes.
    var lastAutoSyncedKey by remember(surface) { mutableStateOf<Triple<Int, TimelineTrackFilterMode, Boolean>?>(null) }
    LaunchedEffect(activeSceneIndex, activeSlideId, controller.trackFilterMode, canvasWidthPx, timelineSnapshot.slides.size) {
        if (controller.hasActiveGesture || canvasWidthPx <= 0) return@LaunchedEffect
        val isLargeDeck = timelineSnapshot.slides.size > 12
        val syncKey = Triple(activeSceneIndex, controller.trackFilterMode, isLargeDeck)
        val previous = lastAutoSyncedKey
        if (previous == syncKey) return@LaunchedEffect
        lastAutoSyncedKey = syncKey
        // A scope toggle that restored a remembered zoom must not be overridden by auto-framing.
        val onlyScopeChanged = previous != null && previous.first == syncKey.first && previous.second != syncKey.second
        val restored = controller.consumeScopeZoomRestored()
        if (onlyScopeChanged && restored) return@LaunchedEffect

        when (controller.trackFilterMode) {
            TimelineTrackFilterMode.CurrentSlide -> {
                if (filteredTracks.isNotEmpty()) listState.scrollToItem(0)
                controller.activeSlideTimeRange()?.let { (startMs, endMs) ->
                    controller.viewport.zoomToRange(startMs, endMs, paddingFraction = 0.08f, surface = surface)
                }
            }
            TimelineTrackFilterMode.AllSlides -> {
                val firstRowIdx = filteredTracks.indexOfFirst { it.sceneIndex == activeSceneIndex }
                if (firstRowIdx >= 0) listState.scrollToItem(firstRowIdx)
                controller.viewport.ensureTimeVisible(controller.playheadMs.toFloat(), surface = surface)
            }
        }
    }

    // Selection reveal: bring the selected row into view vertically AND its time bar horizontally.
    LaunchedEffect(controller.selection, controller.revealRequestNonce, filteredTracks) {
        revealSelection(controller, docSnapshot, filteredTracks, listState, surface)
    }

    val openLoopRef = controller.activeSubCompLoopRef()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(globalColors.panelBackground)
            .onPreviewKeyEvent { ev -> controller.handleKeyEvent(ev, focusState.textInputHasFocus) }
            .testTag("ciacola-doc-timeline-panel"),
    ) {
        DocumentTimelineTransportBar(
            controller = controller,
            docSnapshot = docSnapshot,
            canvasWidthPx = canvasWidthPx,
            surface = surface,
            leadingToolbarContent = leadingToolbarContent,
            headerLeadingContent = headerLeadingContent,
            isExpanded = true,
            onToggleExpanded = onToggleExpanded,
        )

        if (openLoopRef != null) {
            Divider(Orientation.Horizontal, Modifier.fillMaxWidth())
            Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                DocumentLoopSubCompTimelineView(
                    controller = controller,
                    ref = openLoopRef,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
                if (showInlineInspector) {
                    Divider(Orientation.Vertical, Modifier.fillMaxHeight())
                    DocumentTimelineMotionInspector(
                        controller = controller,
                        modifier = Modifier.width(DOC_INSPECTOR_WIDTH_DP).fillMaxHeight(),
                    )
                }
            }
            return
        }

        fun focusHeaderRange(startMs: Int, segmentMs: Int) {
            val spanMs = segmentMs.coerceAtLeast(400)
            val spanPx = spanMs * controller.viewport.pxPerMs(surface)
            val xStart = controller.viewport.xForTime(startMs.toFloat(), surface)
            val xEnd = controller.viewport.xForTime((startMs + spanMs).toFloat(), surface)
            val outOfView = xStart < 0f || xEnd > canvasWidthPx.toFloat()
            if (spanPx < 96f || (outOfView && timelineSnapshot.slides.size > 12)) {
                controller.viewport.zoomToRange(startMs, startMs + spanMs, paddingFraction = 0.15f, surface = surface)
            } else {
                controller.viewport.ensureTimeVisible(startMs.toFloat(), surface = surface)
            }
        }

        Divider(Orientation.Horizontal, Modifier.fillMaxWidth())

        Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .onSizeChanged { size ->
                        val headerPx = with(density) { (controller.viewport.layerColumnWidthDp.dp + 5.dp).roundToPx() }
                        canvasWidthPx = (size.width - headerPx).coerceAtLeast(200)
                    },
            ) {
                // Header band row: left caption cell + band aligned to the track canvas.
                Row(modifier = Modifier.fillMaxWidth().height(HEADER_BAND_HEIGHT), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .width(controller.viewport.layerColumnWidthDp.dp + 5.dp)
                            .fillMaxHeight()
                            .padding(horizontal = 8.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        val slideCount = timelineSnapshot.slides.size
                        Text(
                            text = "Slide ${(activeSceneIndex + 1).coerceAtMost(slideCount.coerceAtLeast(1))} of $slideCount",
                            style = studioCaptionStyle(),
                            color = globalColors.text.normal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    TimelineHeaderBand(
                        controller = controller,
                        surface = surface,
                        modifier = Modifier.weight(1f),
                        onFocusRange = ::focusHeaderRange,
                    )
                }

                // Master time ruler.
                Row(
                    modifier = Modifier.fillMaxWidth().height(22.dp).background(globalColors.panelBackground),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .width(controller.viewport.layerColumnWidthDp.dp)
                            .fillMaxHeight()
                            .padding(horizontal = 8.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        val scopeLabel = if (controller.trackFilterMode == TimelineTrackFilterMode.CurrentSlide) {
                            "Slide tracks · ${filteredTracks.size} of ${timelineSnapshot.tracks.size}"
                        } else {
                            "All tracks · ${filteredTracks.size}"
                        }
                        Text(
                            text = scopeLabel,
                            style = studioCaptionStyle(),
                            color = globalColors.text.normal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    DocumentLayerColumnSplitter(controller.viewport)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clipToBounds()
                            .pointerInput(surface) {
                                detectTapGestures { offset ->
                                    controller.seekToMs(controller.viewport.timeForX(offset.x, surface).roundToInt())
                                }
                            }
                            .pointerInput(surface) {
                                detectHorizontalDragGestures { change, _ ->
                                    change.consume()
                                    controller.seekToMs(controller.viewport.timeForX(change.position.x, surface).roundToInt())
                                }
                            }
                            .testTag("ciacola-doc-timeline-ruler"),
                    ) {
                        val playheadX = controller.viewport.xForTime(controller.playheadMs.toFloat(), surface)
                        val borderCol = globalColors.borders.normal
                        val tickTextColor = studioSecondaryTextColor(isDark)
                        Canvas(Modifier.fillMaxSize()) {
                            val stepMs = chooseMasterRulerTickStepMs(controller.viewport.dpPerMs)
                            val maxMs = timelineSnapshot.totalDurationMs.coerceAtLeast(stepMs)
                            val visStartMs = (floor(controller.viewport.timeForX(0f, surface) / stepMs).toInt() * stepMs)
                                .coerceAtLeast(0)
                            val visEndMs = (controller.viewport.timeForX(size.width, surface).roundToInt() + stepMs)
                                .coerceAtMost(maxMs)
                            val tickStyle = TextStyle(fontSize = 10.sp, color = tickTextColor, fontFeatureSettings = "tnum")
                            var lastLabelRight = Float.NEGATIVE_INFINITY
                            var t = visStartMs
                            while (t <= visEndMs) {
                                val tx = controller.viewport.xForTime(t.toFloat(), surface)
                                if (tx in -2f..(size.width + 2f)) {
                                    drawLine(borderCol, Offset(tx, size.height * 0.58f), Offset(tx, size.height), strokeWidth = 1f)
                                    val measured = textCache.measure(textMeasurer, formatTimelineRulerLabel(t, stepMs, maxVisibleMs = visEndMs), tickStyle, this.density)
                                    val labelX = tx + 3f
                                    if (labelX >= lastLabelRight + 6f && labelX + measured.size.width <= size.width) {
                                        drawText(measured, topLeft = Offset(labelX, 2f))
                                        lastLabelRight = labelX + measured.size.width
                                    }
                                }
                                t += stepMs
                            }
                            if (playheadX in -6f..(size.width + 6f)) {
                                drawPlayheadWithPentagonCap(playheadX, size.height, Color(0xFFFF6B57))
                            }
                        }
                    }
                }
                Divider(Orientation.Horizontal, Modifier.fillMaxWidth())

                if (filteredTracks.isEmpty()) {
                    TimelineEmptyState(controller, timelineSnapshot, Modifier.weight(1f).fillMaxWidth())
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .onKeyEvent { ev ->
                                if (ev.type == KeyEventType.KeyDown &&
                                    !ev.isCtrlPressed && !ev.isMetaPressed && !ev.isAltPressed &&
                                    !focusState.textInputHasFocus
                                ) {
                                    val currentTrackId = (controller.selection as? DocumentTimelineSelection.Track)?.trackId
                                    val curIdx = filteredTracks.indexOfFirst { it.trackId == currentTrackId }
                                    val nextIdx = when (ev.key) {
                                        Key.DirectionDown -> if (curIdx < 0) 0 else (curIdx + 1).coerceAtMost(filteredTracks.lastIndex)
                                        Key.DirectionUp -> if (curIdx < 0) 0 else (curIdx - 1).coerceAtLeast(0)
                                        else -> return@onKeyEvent false
                                    }
                                    val targetRow = filteredTracks[nextIdx]
                                    val targetRef = docSnapshot.trackRefsByTrackId[targetRow.trackId]
                                    if (targetRef is DocumentTimelineTrackRef.LoopTrackBar) {
                                        controller.selectLoop(targetRef.loopId)
                                    } else {
                                        controller.selectTrack(targetRow.trackId, targetRow.blocks.firstOrNull()?.id)
                                    }
                                    scope.launch { listState.animateScrollToItem(nextIdx) }
                                    true
                                } else {
                                    false
                                }
                            }
                            .testTag("ciacola-doc-timeline-track-list"),
                    ) {
                        itemsIndexed(items = filteredTracks, key = { _, row -> row.trackId }) { idx, row ->
                            val ref = docSnapshot.trackRefsByTrackId[row.trackId]
                            val isExpandedText = controller.expandedTextTrackId == row.trackId &&
                                controller.isExpandableTextTrack(ref)
                            Column(modifier = Modifier.fillMaxWidth()) {
                                // `All` scope: a labelled slide group header opens each slide's tracks
                                // (inside the row's item so list indices still match `filteredTracks`).
                                val groupScene = row.sceneIndex
                                if (controller.trackFilterMode == TimelineTrackFilterMode.AllSlides && groupScene != null &&
                                    (idx == 0 || filteredTracks[idx - 1].sceneIndex != groupScene)
                                ) {
                                    TimelineSlideGroupHeader(
                                        label = timelineSlideGroupLabel(
                                            currentDoc.slides.filterNot { it.skipped }.getOrNull(groupScene),
                                            groupScene,
                                        ),
                                        sceneIndex = groupScene,
                                    )
                                }
                                DocumentTrackRowItem(
                                    controller = controller,
                                    row = row,
                                    ref = ref,
                                    document = currentDoc,
                                    surface = surface,
                                    isExpandedText = isExpandedText,
                                )
                                if (isExpandedText && ref != null) {
                                    InlineExpandedTextTrackSubRows(controller = controller, row = row, ref = ref, surface = surface)
                                }
                            }
                        }
                    }
                }
            }

            if (showInlineInspector) {
                Divider(Orientation.Vertical, Modifier.fillMaxHeight())
                DocumentTimelineMotionInspector(
                    controller = controller,
                    modifier = Modifier.width(DOC_INSPECTOR_WIDTH_DP).fillMaxHeight(),
                )
            }
        }
    }
}

/**
 * Compact collapsed timeline (default in Edit mode): one responsive transport bar plus the 22dp
 * time-aligned header band, ~60dp in total, so the stage keeps maximum room.
 */
@Composable
fun DocumentTimelineCollapsedBar(
    controller: DocumentTimelineController,
    modifier: Modifier = Modifier,
    surface: TimelineSurface = TimelineSurface.Docked,
    leadingToolbarContent: (@Composable () -> Unit)? = null,
    headerLeadingContent: (@Composable () -> Unit)? = null,
    onToggleExpanded: (() -> Unit)? = null,
) {
    val revision = controller.store.revision
    val currentDoc = controller.store.document
    LaunchedEffect(revision, currentDoc) { controller.refreshSnapshot() }
    val density = LocalDensity.current
    var bandWidthPx by remember { mutableIntStateOf(0) }
    val totalMs = controller.snapshot.snapshot.totalDurationMs
    LaunchedEffect(totalMs, bandWidthPx, density.density, surface) {
        if (bandWidthPx <= 0) return@LaunchedEffect
        controller.viewport.contentDurationMs = totalMs.coerceAtLeast(1)
        controller.viewport.updateSurfaceMetrics(surface, bandWidthPx.toFloat(), density.density)
        controller.viewport.applyStickyFitIfNeeded(surface)
    }
    val activeScene = controller.currentPresentedSceneIndex
    LaunchedEffect(activeScene, controller.trackFilterMode, bandWidthPx) {
        if (bandWidthPx <= 0 || controller.hasActiveGesture) return@LaunchedEffect
        if (controller.trackFilterMode == TimelineTrackFilterMode.CurrentSlide) {
            controller.activeSlideTimeRange()?.let { (s, e) ->
                controller.viewport.zoomToRange(s, e, paddingFraction = 0.08f, surface = surface)
            }
        } else {
            controller.viewport.ensureTimeVisible(controller.playheadMs.toFloat(), surface = surface)
        }
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(JewelTheme.globalColors.panelBackground)
            .testTag("ciacola-timeline-collapsed-bar"),
    ) {
        DocumentTimelineTransportBar(
            controller = controller,
            docSnapshot = controller.snapshot,
            canvasWidthPx = bandWidthPx,
            surface = surface,
            leadingToolbarContent = leadingToolbarContent,
            headerLeadingContent = headerLeadingContent,
            isExpanded = false,
            onToggleExpanded = onToggleExpanded,
        )
        TimelineHeaderBand(
            controller = controller,
            surface = surface,
            modifier = Modifier
                .padding(start = 6.dp, end = 6.dp, bottom = 4.dp)
                .onSizeChanged { bandWidthPx = it.width },
        )
    }
}

@Composable
private fun TimelineEmptyState(
    controller: DocumentTimelineController,
    timelineSnapshot: DeckTimelineSnapshot,
    modifier: Modifier,
) {
    Box(modifier = modifier.padding(16.dp).testTag("ciacola-timeline-empty-state"), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val slideLabel = "${controller.currentPresentedSceneIndex + 1}"
            val isStaticSlideInScope = controller.trackFilterMode == TimelineTrackFilterMode.CurrentSlide &&
                timelineSnapshot.tracks.isNotEmpty() && controller.trackSearchQuery.isBlank()
            val emptyMessage = when {
                controller.trackSearchQuery.isNotBlank() -> "No tracks matching \"${controller.trackSearchQuery.trim()}\"."
                isStaticSlideInScope -> "No motion tracks on slide $slideLabel."
                else -> "No motion tracks in this deck."
            }
            Text(text = emptyMessage, style = studioLabelStyle(), color = JewelTheme.globalColors.text.normal)
            if (isStaticSlideInScope) {
                CompactTextButton(
                    label = "Show all ${timelineSnapshot.tracks.size} tracks",
                    onClick = { controller.setTrackFilter(TimelineTrackFilterMode.AllSlides) },
                    tag = "ciacola-timeline-empty-show-all",
                )
            } else if (controller.trackSearchQuery.isNotBlank()) {
                CompactTextButton("Clear search", { controller.trackSearchQuery = "" }, "ciacola-timeline-empty-clear-search")
            }
        }
    }
}

/** `Slide 3 · Title` (or `Slide 3`) for the `All`-scope track group header. */
internal fun timelineSlideGroupLabel(slide: SlideSpec?, sceneIndex: Int): String {
    val title = slide?.title?.trim()?.takeIf { it.isNotEmpty() }
    return if (title == null) "Slide ${sceneIndex + 1}" else "Slide ${sceneIndex + 1} · $title"
}

@Composable
private fun TimelineSlideGroupHeader(label: String, sceneIndex: Int) {
    val colors = JewelTheme.globalColors
    Column(Modifier.fillMaxWidth().testTag("ciacola-doc-timeline-slide-group-$sceneIndex")) {
        Divider(Orientation.Horizontal, Modifier.fillMaxWidth())
        Text(
            text = label,
            style = studioCaptionStyle().copy(fontWeight = FontWeight.SemiBold),
            color = colors.text.normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.borders.normal.copy(alpha = 0.12f))
                .padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

private suspend fun revealSelection(
    controller: DocumentTimelineController,
    docSnapshot: DocumentTimelineSnapshot,
    rows: List<TimelineTrackRow>,
    listState: LazyListState,
    surface: TimelineSurface,
) {
    if (controller.hasActiveGesture) return
    val rowIdx = when (val sel = controller.selection) {
        is DocumentTimelineSelection.Track -> rows.indexOfFirst { it.trackId == sel.trackId }
        is DocumentTimelineSelection.Loop -> rows.indexOfFirst { it.trackId == "doc:loop:${sel.loopId}" }
        is DocumentTimelineSelection.BeatHeader -> rows.indexOfFirst { row ->
            when (val ref = docSnapshot.trackRefsByTrackId[row.trackId]) {
                is DocumentTimelineTrackRef.SlideBuildTrack -> ref.slideId == sel.slideId && ref.beatIndex == sel.beatIndex
                is DocumentTimelineTrackRef.SpecimenTrackBar -> ref.slideId == sel.slideId && ref.beatIndex == sel.beatIndex
                else -> false
            }
        }
        is DocumentTimelineSelection.TransitionHeader -> rows.indexOfFirst { row ->
            val ref = docSnapshot.trackRefsByTrackId[row.trackId] as? DocumentTimelineTrackRef.TransitionTrackBar
            ref != null && ref.fromSlideId == sel.fromSlideId && ref.toSlideId == sel.toSlideId
        }
        DocumentTimelineSelection.None -> -1
    }
    if (rowIdx < 0) return
    val info = listState.layoutInfo
    val item = info.visibleItemsInfo.firstOrNull { it.index == rowIdx }
    val fullyVisible = item != null && item.offset >= info.viewportStartOffset && item.offset + item.size <= info.viewportEndOffset
    if (!fullyVisible) listState.animateScrollToItem(rowIdx)
    if (controller.selection is DocumentTimelineSelection.Track || controller.selection is DocumentTimelineSelection.Loop) {
        rows[rowIdx].timeRange()?.let { (start, end) ->
            controller.viewport.ensureRangeVisible(start.toFloat(), end.toFloat(), surface = surface)
        }
    }
}

/**
 * Scrollable `Animate` pane for a [DocumentTimelineController], usable both inline inside
 * [DocumentTimelineStudioPanel] and inside Studio's unified right-hand inspector.
 */
@Composable
fun DocumentTimelineMotionInspector(
    controller: DocumentTimelineController,
    modifier: Modifier = Modifier,
) {
    controller.store.revision
    controller.store.document
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .testTag("ciacola-doc-timeline-inspector"),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        DocumentTimelineRightInspector(controller = controller)
    }
}
