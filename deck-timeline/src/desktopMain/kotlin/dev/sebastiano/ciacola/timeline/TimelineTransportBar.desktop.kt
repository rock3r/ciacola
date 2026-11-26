package dev.sebastiano.ciacola.timeline

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties
import dev.bnorm.storyboard.Storyboard
import dev.sebastiano.ciacola.ui.rememberAccessibleFocusRingColor
import java.util.Locale
import kotlin.math.exp
import kotlin.math.ln
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.IconButton
import org.jetbrains.jewel.ui.component.PopupMenu
import org.jetbrains.jewel.ui.component.Slider
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField
import org.jetbrains.jewel.ui.component.Tooltip
import org.jetbrains.jewel.ui.icon.IconKey
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/** Formats [timeMs] as `mm:ss.SSS` for the transport bar timecode display. */
fun formatTransportTimecode(timeMs: Int): String {
    val safeMs = timeMs.coerceAtLeast(0)
    val mins = safeMs / 60_000
    val secs = (safeMs % 60_000) / 1_000
    val ms = safeMs % 1_000
    return String.format(Locale.US, "%02d:%02d.%03d", mins, secs, ms)
}

/** How the zoom controls render at the current transport bar width. */
enum class TransportZoomMode { Slider, Buttons, Menu }

/** Responsive layout decision for the transport bar (pure, so it is unit-testable). */
data class TransportBarLayout(
    val zoomMode: TransportZoomMode,
    val inlineFilterField: Boolean,
    /** Legacy flag kept for callers; the deck toolbar now needs [TRANSPORT_DECK_TOOLBAR_MIN_WIDTH]. */
    val showLeadingToolbar: Boolean,
    val compactTimecode: Boolean,
    /** Below ~440dp the `Slide | All` toggle folds into the zoom menu. */
    val scopeInMenu: Boolean = false,
)

internal val TRANSPORT_SLIDER_MIN_WIDTH: Dp = 760.dp
internal val TRANSPORT_BUTTONS_MIN_WIDTH: Dp = 540.dp
internal val TRANSPORT_FILTER_FIELD_MIN_WIDTH: Dp = 880.dp
internal val TRANSPORT_SCOPE_MIN_WIDTH: Dp = 440.dp
internal val TRANSPORT_DECK_TOOLBAR_MIN_WIDTH: Dp = 1_100.dp
internal val TRANSPORT_DECK_TOOLBAR_BUDGET: Dp = 220.dp

/**
 * Three fixed priority groups: (1) transport + timecode + expand chevron never hide; (2) scope and
 * zoom collapse `slider → − + Fit → Zoom ▾`; (3) the filter field collapses to a search icon.
 */
fun transportBarLayoutFor(width: Dp): TransportBarLayout = TransportBarLayout(
    zoomMode = when {
        width >= TRANSPORT_SLIDER_MIN_WIDTH -> TransportZoomMode.Slider
        width >= TRANSPORT_BUTTONS_MIN_WIDTH -> TransportZoomMode.Buttons
        else -> TransportZoomMode.Menu
    },
    inlineFilterField = width >= TRANSPORT_FILTER_FIELD_MIN_WIDTH,
    showLeadingToolbar = width >= TRANSPORT_FILTER_FIELD_MIN_WIDTH,
    compactTimecode = width < TRANSPORT_BUTTONS_MIN_WIDTH,
    scopeInMenu = width < TRANSPORT_SCOPE_MIN_WIDTH,
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DocumentTransportIconButton(
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
                .size(STUDIO_CONTROL_HEIGHT)
                .studioActivatableFocus(focusRingColor)
                .testTag(testTag),
        ) {
            Icon(key = icon, contentDescription = contentDescription, modifier = Modifier.size(14.dp))
        }
    }
}

/**
 * Responsive document-backed Timeline transport bar. Undo/Redo live in the App Bar (opt back in with
 * [showInlineUndoRedo]); beat insert/delete live on the header band.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun DocumentTimelineTransportBar(
    controller: DocumentTimelineController,
    docSnapshot: DocumentTimelineSnapshot,
    canvasWidthPx: Int,
    surface: TimelineSurface = TimelineSurface.Docked,
    leadingToolbarContent: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
    headerLeadingContent: (@Composable () -> Unit)? = null,
    isExpanded: Boolean = true,
    onToggleExpanded: (() -> Unit)? = null,
    showInlineUndoRedo: Boolean = false,
) {
    val globalColors = JewelTheme.globalColors
    val activeSubLoopId = controller.activeSubCompLoopId
    val activeSubLoopRef = controller.activeSubCompLoopRef()
    val nowMs = if (activeSubLoopId != null) {
        (controller.subCompositionScrubMillis ?: 0L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    } else {
        controller.playheadMs
    }
    val fallbackIndex = Storyboard.Index(
        sceneIndex = controller.currentPresentedSceneIndex,
        frameIndex = controller.currentPresentedBeatIndex,
    )
    val locationBadge = formatTimelineLocationBadge(
        snapshot = docSnapshot.snapshot,
        timeMs = nowMs,
        activeSubLoopId = activeSubLoopId,
        fallbackIndex = fallbackIndex,
        sceneBeatsCountProvider = { sIdx -> docSnapshot.snapshot.slides.getOrNull(sIdx)?.beatCount ?: 1 },
    )
    val location = locationBadge.substringAfter(" · ", locationBadge)

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .background(globalColors.panelBackground)
            .testTag("ciacola-doc-timeline-toolbar"),
    ) {
        // Width taken by host-provided leading content (e.g. `Timeline | Issues` tabs) is measured
        // and subtracted so the priority groups always fit the space that is really left.
        var leadingWidth by remember { mutableStateOf(0.dp) }
        val density = LocalDensity.current
        // The optional deck toolbar only shows when there is generous room, and reserves a fixed
        // budget, so its own visibility never feeds back into the measurement above.
        val showDeckToolbar = leadingToolbarContent != null && maxWidth - leadingWidth >= TRANSPORT_DECK_TOOLBAR_MIN_WIDTH
        val availableWidth = maxWidth - leadingWidth - if (showDeckToolbar) TRANSPORT_DECK_TOOLBAR_BUDGET else 0.dp
        val layout = transportBarLayoutFor(availableWidth)
        var searchOpen by remember(surface) { mutableStateOf(false) }
        val searchActive = layout.inlineFilterField || searchOpen || controller.trackSearchQuery.isNotEmpty()
        val narrowSearchActive = searchActive && !layout.inlineFilterField && availableWidth < 680.dp
        val effectiveZoomMode = if (narrowSearchActive) TransportZoomMode.Menu else layout.zoomMode
        val effectiveScopeInMenu = layout.scopeInMenu ||
            (searchActive && !layout.inlineFilterField && effectiveZoomMode == TransportZoomMode.Menu)
        val effectiveCompactTimecode = layout.compactTimecode || narrowSearchActive
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(STUDIO_CONTROL_HEIGHT + 8.dp)
                .padding(horizontal = 6.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // ── Priority 1: never hidden ────────────────────────────────────────────────────────
            val hasHeaderLeading = headerLeadingContent != null
            LaunchedEffect(hasHeaderLeading) { if (!hasHeaderLeading) leadingWidth = 0.dp }
            if (headerLeadingContent != null) {
                Row(
                    modifier = Modifier
                        .onSizeChanged { leadingWidth = with(density) { it.width.toDp() } + 4.dp }
                        .testTag("ciacola-timeline-dock-header-leading"),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    headerLeadingContent()
                    Divider(Orientation.Vertical, Modifier.height(16.dp).padding(horizontal = 2.dp))
                }
            }
            if (leadingToolbarContent != null && showDeckToolbar) {
                leadingToolbarContent()
                Divider(Orientation.Vertical, Modifier.height(16.dp).padding(horizontal = 2.dp))
            }
            DocumentTransportIconButton(
                icon = AllIconsKeys.General.ChevronLeft,
                contentDescription = "Previous beat",
                tooltip = "Previous beat",
                testTag = "ciacola-timeline-prev-beat",
                enabled = activeSubLoopId == null,
            ) { controller.stepBeat(-1) }
            DocumentTransportIconButton(
                icon = if (controller.isPlaying) AllIconsKeys.Actions.Pause else AllIconsKeys.Actions.Execute,
                contentDescription = if (controller.isPlaying) "Pause" else "Play",
                tooltip = if (controller.isPlaying) "Pause" else "Play",
                testTag = "ciacola-timeline-play",
            ) { controller.togglePlayback() }
            DocumentTransportIconButton(
                icon = AllIconsKeys.General.ChevronRight,
                contentDescription = "Next beat",
                tooltip = "Next beat",
                testTag = "ciacola-timeline-next-beat",
                enabled = activeSubLoopId == null,
            ) { controller.stepBeat(+1) }

            Box(
                modifier = Modifier
                    .height(STUDIO_CONTROL_HEIGHT)
                    .border(1.dp, globalColors.borders.normal, STUDIO_CONTROL_SHAPE)
                    .padding(horizontal = 7.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (effectiveCompactTimecode) {
                        "${formatTransportTimecode(nowMs).drop(1)} · ${location.substringBefore(" →").substringBefore("/")}"
                    } else {
                        "${formatTransportTimecode(nowMs)} · $location"
                    },
                    style = studioLabelStyle().copy(fontFeatureSettings = "tnum"),
                    color = globalColors.text.normal,
                    maxLines = 1,
                    modifier = Modifier.testTag("ciacola-doc-timeline-playhead-readout"),
                )
            }

            if (showInlineUndoRedo) {
                CompactTextButton("Undo", { controller.performUndo() }, "ciacola-doc-timeline-undo", enabled = controller.store.canUndo)
                CompactTextButton("Redo", { controller.performRedo() }, "ciacola-doc-timeline-redo", enabled = controller.store.canRedo)
            }

            if (activeSubLoopId != null) {
                CompactTextButton("← Master", { controller.exitLoopSubComp() }, "ciacola-timeline-exit-subcomp")
                Text(
                    text = "Loop · ${activeSubLoopRef?.label ?: activeSubLoopId}",
                    style = studioLabelStyle().copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).testTag("ciacola-timeline-subcomp-title"),
                )
                ExpandToggle(isExpanded, onToggleExpanded)
                return@Row
            }

            Spacer(Modifier.weight(1f))

            // ── Priority 3: filter (inline field when wide, search icon otherwise) ──────────────
            if (searchActive) {
                TransportSearchField(
                    controller = controller,
                    width = when {
                        layout.inlineFilterField -> 140.dp
                        narrowSearchActive || effectiveZoomMode == TransportZoomMode.Menu -> 84.dp
                        else -> 112.dp
                    },
                    autoFocus = !layout.inlineFilterField && searchOpen,
                    onBlurEmpty = { if (!layout.inlineFilterField) searchOpen = false },
                )
            } else {
                DocumentTransportIconButton(
                    icon = AllIconsKeys.Actions.Search,
                    contentDescription = "Filter tracks",
                    tooltip = "Filter tracks",
                    testTag = "ciacola-doc-timeline-search-toggle",
                ) { searchOpen = true }
            }

            // ── Priority 2: scope + zoom ────────────────────────────────────────────────────────
            val isSlideScope = controller.trackFilterMode == TimelineTrackFilterMode.CurrentSlide
            if (!effectiveScopeInMenu) Row(horizontalArrangement = Arrangement.spacedBy(0.dp), verticalAlignment = Alignment.CenterVertically) {
                CompactToggleChip(
                    label = "Slide",
                    selected = isSlideScope,
                    onClick = { controller.setTrackFilter(TimelineTrackFilterMode.CurrentSlide) },
                    tag = "ciacola-timeline-filter-current",
                )
                Spacer(Modifier.width(2.dp))
                CompactToggleChip(
                    label = "All",
                    selected = !isSlideScope,
                    onClick = { controller.setTrackFilter(TimelineTrackFilterMode.AllSlides) },
                    tag = "ciacola-timeline-filter-all",
                )
            }
            TransportZoomControls(controller, effectiveZoomMode, canvasWidthPx, surface, nowMs, docSnapshot, effectiveScopeInMenu)
            ExpandToggle(isExpanded, onToggleExpanded)
        }
    }
}

@Composable
private fun ExpandToggle(isExpanded: Boolean, onToggleExpanded: (() -> Unit)?) {
    if (onToggleExpanded == null) return
    DocumentTransportIconButton(
        icon = if (isExpanded) AllIconsKeys.General.ChevronDown else AllIconsKeys.General.ChevronUp,
        contentDescription = if (isExpanded) "Collapse timeline" else "Expand timeline",
        tooltip = if (isExpanded) "Collapse timeline (F5)" else "Expand timeline tracks (F5)",
        testTag = "ciacola-timeline-expand-toggle",
        onClick = onToggleExpanded,
    )
}

@Composable
private fun TransportSearchField(
    controller: DocumentTimelineController,
    width: Dp,
    autoFocus: Boolean,
    onBlurEmpty: () -> Unit,
) {
    val searchState = rememberTextFieldState(initialText = controller.trackSearchQuery)
    val focusRequester = remember { FocusRequester() }
    var hadFocus by remember { mutableStateOf(false) }
    LaunchedEffect(searchState.text.toString()) {
        val next = searchState.text.toString()
        if (controller.trackSearchQuery != next) controller.trackSearchQuery = next
    }
    LaunchedEffect(controller.trackSearchQuery) {
        if (searchState.text.toString() != controller.trackSearchQuery) {
            searchState.setTextAndPlaceCursorAtEnd(controller.trackSearchQuery)
        }
    }
    LaunchedEffect(autoFocus) { if (autoFocus) runCatching { focusRequester.requestFocus() } }
    TextField(
        state = searchState,
        placeholder = {
            Text(
                text = "Filter tracks",
                style = studioCaptionStyle(),
                color = studioSecondaryTextColor(JewelTheme.isDark),
                maxLines = 1,
            )
        },
        modifier = Modifier
            .width(width)
            .height(STUDIO_CONTROL_HEIGHT)
            .focusRequester(focusRequester)
            .onFocusChanged { st ->
                if (hadFocus && !st.hasFocus && searchState.text.isEmpty()) onBlurEmpty()
                hadFocus = st.hasFocus
            }
            .studioTextInputFocus()
            .testTag("ciacola-doc-timeline-search"),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TransportZoomControls(
    controller: DocumentTimelineController,
    mode: TransportZoomMode,
    canvasWidthPx: Int,
    surface: TimelineSurface,
    nowMs: Int,
    docSnapshot: DocumentTimelineSnapshot,
    scopeInMenu: Boolean = false,
) {
    val viewport = controller.viewport
    val isSlideScope = controller.trackFilterMode == TimelineTrackFilterMode.CurrentSlide
    val isLargeDeck = docSnapshot.snapshot.slides.size > 12
    val zoomOut = {
        viewport.markSurfaceActive(surface)
        viewport.zoomBy(factor = 0.7f, anchorX = canvasWidthPx / 2f, surface = surface)
    }
    val zoomIn = {
        viewport.markSurfaceActive(surface)
        viewport.zoomBy(factor = 1.4f, anchorX = canvasWidthPx / 2f, surface = surface)
    }
    val fit = {
        viewport.markSurfaceActive(surface)
        controller.fitCurrentScope(surface)
    }
    val fitSlide = {
        viewport.markSurfaceActive(surface)
        controller.activeSlideTimeRange()?.let { (start, end) -> viewport.zoomToRange(start, end, surface = surface) }
        Unit
    }
    val fitTooltip = if (isSlideScope) "Fit the current slide to the timeline width" else "Fit the whole deck to the timeline width"

    when (mode) {
        TransportZoomMode.Menu -> {
            var open by remember { mutableStateOf(false) }
            Box {
                CompactTextButton("Zoom ▾", { open = !open }, "ciacola-doc-timeline-zoom-menu")
                if (open) {
                    PopupMenu(
                        onDismissRequest = {
                            open = false
                            true
                        },
                        horizontalAlignment = Alignment.End,
                        popupProperties = PopupProperties(focusable = true),
                        modifier = Modifier.testTag("ciacola-doc-timeline-zoom-popup"),
                    ) {
                        selectableItem(selected = false, onClick = { open = false; zoomIn() }) { Text("Zoom in", style = studioLabelStyle()) }
                        selectableItem(selected = false, onClick = { open = false; zoomOut() }) { Text("Zoom out", style = studioLabelStyle()) }
                        selectableItem(selected = false, onClick = { open = false; fit() }) {
                            Text(if (isSlideScope) "Fit slide" else "Fit deck", style = studioLabelStyle())
                        }
                        if (!isSlideScope) {
                            selectableItem(selected = false, onClick = { open = false; fitSlide() }) {
                                Text("Zoom to current slide", style = studioLabelStyle())
                            }
                        }
                        if (scopeInMenu) {
                            passiveItem { Divider(Orientation.Horizontal, Modifier.fillMaxWidth().padding(vertical = 2.dp)) }
                            selectableItem(
                                selected = isSlideScope,
                                onClick = { open = false; controller.setTrackFilter(TimelineTrackFilterMode.CurrentSlide) },
                            ) { Text("Show this slide's tracks", style = studioLabelStyle(), modifier = Modifier.testTag("ciacola-timeline-filter-current")) }
                            selectableItem(
                                selected = !isSlideScope,
                                onClick = { open = false; controller.setTrackFilter(TimelineTrackFilterMode.AllSlides) },
                            ) { Text("Show all tracks", style = studioLabelStyle(), modifier = Modifier.testTag("ciacola-timeline-filter-all")) }
                        }
                    }
                }
            }
        }
        TransportZoomMode.Buttons, TransportZoomMode.Slider -> {
            Tooltip(tooltip = { Text("Zoom out") }) { CompactTextButton("−", zoomOut, "ciacola-doc-timeline-zoom-out") }
            if (mode == TransportZoomMode.Slider) {
                ZoomSlider(controller, canvasWidthPx, surface, nowMs)
            }
            Tooltip(tooltip = { Text("Zoom in") }) { CompactTextButton("+", zoomIn, "ciacola-doc-timeline-zoom-in") }
            Tooltip(tooltip = { Text(fitTooltip) }) { CompactTextButton("Fit", fit, "ciacola-doc-timeline-fit-all") }
            if (mode == TransportZoomMode.Slider && isLargeDeck && !isSlideScope) {
                Tooltip(tooltip = { Text("Zoom to the current slide's time span") }) {
                    CompactTextButton("Fit slide", fitSlide, "ciacola-doc-timeline-fit-slide")
                }
            }
        }
    }
}

@Composable
private fun ZoomSlider(
    controller: DocumentTimelineController,
    canvasWidthPx: Int,
    surface: TimelineSurface,
    nowMs: Int,
) {
    val viewport = controller.viewport
    val minZoom = viewport.minZoomDpPerMs(surface).coerceAtMost(MAX_DP_PER_MS)
    val logMin = ln(minZoom.coerceAtLeast(MIN_DP_PER_MS))
    val logMax = ln(MAX_DP_PER_MS)
    val sliderValue = if (logMax > logMin) {
        ((ln(viewport.dpPerMs.coerceAtLeast(MIN_DP_PER_MS)) - logMin) / (logMax - logMin)).coerceIn(0f, 1f)
    } else {
        0f
    }
    var zoomDragging by remember(surface) { mutableStateOf(false) }
    var zoomHadFocus by remember(surface) { mutableStateOf(false) }
    var localZoomValue by remember(surface) { mutableFloatStateOf(sliderValue) }
    LaunchedEffect(sliderValue, zoomDragging) { if (!zoomDragging) localZoomValue = sliderValue }
    Slider(
        value = if (zoomDragging) localZoomValue else sliderValue,
        onValueChange = { v ->
            zoomDragging = true
            val clamped = v.coerceIn(0f, 1f)
            localZoomValue = clamped
            viewport.markSurfaceActive(surface)
            val anchor = viewport.xForTime(nowMs.toFloat(), surface).coerceIn(0f, canvasWidthPx.toFloat().coerceAtLeast(1f))
            viewport.setZoomDpPerMs(exp(logMin + clamped * (logMax - logMin)), anchor, surface)
        },
        onValueChangeFinished = { zoomDragging = false },
        modifier = Modifier
            .width(84.dp)
            .onFocusChanged { focusState ->
                if (focusState.hasFocus) {
                    zoomHadFocus = true
                } else if (zoomHadFocus) {
                    zoomHadFocus = false
                    zoomDragging = false
                }
            }
            .testTag("ciacola-timeline-zoom-slider"),
    )
}
