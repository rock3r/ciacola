package dev.sebastiano.ciacola.timeline

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.rememberScrollableState
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed as isKeyAltPressed
import androidx.compose.ui.input.key.isCtrlPressed as isKeyCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed as isKeyMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.sebastiano.ciacola.ui.rememberAccessibleFocusRingColor
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
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

internal val SLIDE_RIBBON_HEIGHT = 26.dp
internal val TIME_RULER_HEIGHT = 26.dp
internal val HEADER_TOTAL_HEIGHT = 52.dp
internal val TRACK_ROW_HEIGHT = 28.dp
internal val LAYER_COLUMN_WIDTH = 224.dp

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
    val focusRingColor = rememberAccessibleFocusRingColor()
    Tooltip(tooltip = { Text(tooltip) }) {
        IconButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier
                .size(24.dp)
                .studioActivatableFocus(focusRingColor)
                .testTag(testTag),
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
    val focusRingColor = rememberAccessibleFocusRingColor()

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
            TransportIconButton(AllIconsKeys.General.ChevronLeft, "Previous beat", "Previous Beat ([)", "ciacola-timeline-prev-beat") {
                controller.stepBeat(-1)
            }
            TransportIconButton(
                icon = if (controller.isPlaying) AllIconsKeys.Actions.Pause else AllIconsKeys.Actions.Execute,
                contentDescription = if (controller.isPlaying) "Pause" else "Play",
                tooltip = "Play / Pause (Space)",
                testTag = "ciacola-timeline-play",
            ) { controller.togglePlayback(scope) }
            TransportIconButton(AllIconsKeys.General.ChevronRight, "Next beat", "Next Beat (])", "ciacola-timeline-next-beat") {
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
                TransportIconButton(AllIconsKeys.General.Add, "Add beat", "Insert event keyframe (+ Beat)", "ciacola-timeline-add-beat") {
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
                tooltip = "Undo (⌘Z)",
                testTag = "ciacola-timeline-undo",
                enabled = controller.overridesStore.canUndo,
            ) { controller.undo() }
            TransportIconButton(
                icon = AllIconsKeys.Actions.Redo,
                contentDescription = "Redo",
                tooltip = "Redo (⇧⌘Z)",
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
                Tooltip(tooltip = { Text("Return to Master Deck timeline (Esc)") }) {
                    OutlinedButton(
                        onClick = { controller.exitLoopSubComp() },
                        modifier = Modifier
                            .studioActivatableFocus(focusRingColor)
                            .testTag("ciacola-timeline-exit-subcomp"),
                    ) {
                        Text("← Master Deck", style = JewelTheme.typography.small, maxLines = 1)
                    }
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
                TransportIconButton(AllIconsKeys.General.ZoomOut, "Zoom out", "Zoom Out (-, or Alt+wheel)", "ciacola-timeline-zoom-out") {
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
                TransportIconButton(AllIconsKeys.General.ZoomIn, "Zoom in", "Zoom In (=, or Alt+wheel)", "ciacola-timeline-zoom-in") {
                    viewport.markSurfaceActive(surface)
                    viewport.zoomBy(1.25f, viewport.xForTime(nowMs.toFloat(), surface).coerceIn(0f, viewport.viewportWidthPx(surface)), surface)
                }
                TransportIconButton(AllIconsKeys.General.FitContent, "Zoom to fit", "Zoom to Fit (;)", "ciacola-timeline-zoom-fit") {
                    viewport.markSurfaceActive(surface)
                    viewport.toggleFitAndFrames(nowMs.toFloat(), surface)
                }
            }
            if (controller.bakeStatus != TimelineBakeStatus.Idle) {
                val palette = JewelTheme.colorPalette
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
                            .studioActivatableFocus(focusRingColor)
                            .clickable(enabled = controller.bakeStatus != TimelineBakeStatus.Sending) {
                                controller.clearBakeStatus()
                            }
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                            .testTag("ciacola-timeline-bake-status")
                            .semantics {
                                role = Role.Button
                                contentDescription = "Bake status: $fullMessage"
                            },
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
                        modifier = Modifier
                            .studioActivatableFocus(focusRingColor)
                            .testTag("ciacola-timeline-copy-kotlin"),
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
                        modifier = Modifier
                            .studioActivatableFocus(focusRingColor)
                            .testTag("ciacola-timeline-reset"),
                    ) {
                        Text("Reset All", style = JewelTheme.typography.small)
                    }
                }
                if (controller.bakeDispatcher != null) {
                    Tooltip(tooltip = { Text("Send live overrides to agent to fold into Kotlin source") }) {
                        DefaultButton(
                            onClick = { controller.bakeOverridesViaAgent(scope) },
                            enabled = controller.bakeStatus != TimelineBakeStatus.Sending,
                            modifier = Modifier
                                .studioActivatableFocus(focusRingColor)
                                .testTag("ciacola-timeline-bake"),
                        ) {
                            Text("Bake to Kotlin", style = JewelTheme.typography.small)
                        }
                    }
                }
            }
            if (controller.dockedInStoryWindow) {
                TransportIconButton(
                    icon = AllIconsKeys.General.Close,
                    contentDescription = "Close Timeline",
                    tooltip = "Close Timeline (F5)",
                    testTag = "ciacola-timeline-close",
                ) {
                    controller.dockedInStoryWindow = false
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

    val focusRingColor = rememberAccessibleFocusRingColor()
    val focusState = LocalStudioA11yFocusState.current
    val density = LocalDensity.current
    val rowHeightPx = with(density) { TRACK_ROW_HEIGHT.toPx() }
    val trackIds = remember(snapshot.tracks) { snapshot.tracks.map { it.trackId } }
    val rowFocusRequesters = remember(trackIds) { List(trackIds.size) { FocusRequester() } }
    var layerColumnHeightPx by remember { mutableFloatStateOf(0f) }

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
                    .onSizeChanged { layerColumnHeightPx = it.height.toFloat() }
                    .onFocusChanged { focusState?.layerListHasFocus = it.hasFocus }
                    .verticalScroll(viewport.verticalScroll)
                    .testTag("ciacola-timeline-layer-headers"),
            ) {
                snapshot.tracks.forEachIndexed { rowIdx, track ->
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
                            .focusRequester(rowFocusRequesters[rowIdx])
                            .studioActivatableFocus(focusRingColor, cornerRadius = 2.dp)
                            .onKeyEvent { ev ->
                                if (ev.type == KeyEventType.KeyDown &&
                                    !ev.isKeyCtrlPressed && !ev.isKeyMetaPressed && !ev.isKeyAltPressed
                                ) {
                                    val nextIdx = when (ev.key) {
                                        Key.DirectionDown -> (rowIdx + 1).coerceAtMost(snapshot.tracks.lastIndex)
                                        Key.DirectionUp -> (rowIdx - 1).coerceAtLeast(0)
                                        else -> return@onKeyEvent false
                                    }
                                    if (nextIdx != rowIdx) {
                                        runCatching { rowFocusRequesters[nextIdx].requestFocus() }
                                        val nextTop = (nextIdx * rowHeightPx).roundToInt()
                                        val nextBottom = ((nextIdx + 1) * rowHeightPx).roundToInt()
                                        val visH = layerColumnHeightPx.roundToInt().coerceAtLeast(rowHeightPx.roundToInt())
                                        val curScroll = viewport.verticalScroll.value
                                        val targetScroll = when {
                                            nextTop < curScroll -> nextTop
                                            nextBottom > curScroll + visH -> nextBottom - visH
                                            else -> null
                                        }
                                        if (targetScroll != null) {
                                            scope.launch { viewport.verticalScroll.animateScrollTo(targetScroll.coerceAtLeast(0)) }
                                        }
                                    }
                                    true
                                } else {
                                    false
                                }
                            }
                            .clickable { controller.selectTrack(track.trackId) }
                            .padding(start = (10 + track.indentLevel * 14).dp, end = 6.dp)
                            .testTag("ciacola-track-row-${track.trackId}")
                            .semantics {
                                contentDescription = "$displayRowLabel, ${track.category.accessibleLabel()}"
                                stateDescription = buildString {
                                    append(if (isSelected) "Selected" else "Not selected")
                                    if (isRowOverridden) append(", Overridden")
                                }
                            },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            if (isRowOverridden) {
                                Tooltip(tooltip = { Text("Track has live overrides") }) {
                                    Text(
                                        text = "●",
                                        style = JewelTheme.typography.small,
                                        color = overrideBadgeColor,
                                        modifier = Modifier
                                            .testTag("ciacola-override-badge-${track.trackId}")
                                            .semantics { contentDescription = "Track overridden" },
                                    )
                                }
                            }
                            Tooltip(tooltip = { Text(displayRowLabel) }) {
                                Text(
                                    text = displayRowLabel,
                                    style = JewelTheme.typography.small,
                                    color = if (isSelected || isRowOverridden) globalColors.text.normal else secondaryLayerColor,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        if (loopBlock?.loopId != null) {
                            Tooltip(tooltip = { Text("Enter Loop Sub-Composition") }) {
                                Box(
                                    modifier = Modifier
                                        .border(1.dp, globalColors.borders.focused, RoundedCornerShape(4.dp))
                                        .studioActivatableFocus(focusRingColor)
                                        .clickable {
                                            controller.enterLoopSubComp(scope, loopBlock.loopId, loopBlock.sceneIndex)
                                        }
                                        .padding(horizontal = 5.dp, vertical = 1.dp)
                                        .testTag("ciacola-timeline-open-loop")
                                        .semantics {
                                            role = Role.Button
                                            contentDescription = "Enter loop sub-composition for ${track.label}"
                                        },
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
