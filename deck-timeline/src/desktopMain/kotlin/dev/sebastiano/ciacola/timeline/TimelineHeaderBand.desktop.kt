package dev.sebastiano.ciacola.timeline

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.Tooltip

internal val HEADER_BAND_HEIGHT = 22.dp
private val SCRUBBER_HEIGHT = 5.dp
private val BEAT_TICK_HIT_WIDTH = 10.dp
private val ADD_BEAT_SIZE = 16.dp

/**
 * One crisp 22dp time-aligned header band drawn in the same time coordinates as the tracks:
 * slides as labelled segments, beats as clickable ticks (right-click → `Delete Beat`),
 * transitions as hatched joins, a `+` at the end of the active slide segment, and — when zoomed
 * in — a draggable viewport scrubber so the author always sees where they are in the deck.
 *
 * Replaces both the old scrolling beat pill strip and the unlabelled minimap stripe.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun TimelineHeaderBand(
    controller: DocumentTimelineController,
    surface: TimelineSurface,
    modifier: Modifier = Modifier,
    onFocusRange: (startMs: Int, segmentMs: Int) -> Unit = { startMs, _ ->
        controller.viewport.ensureTimeVisible(startMs.toFloat(), surface = surface)
    },
) {
    val docSnapshot = controller.snapshot
    val document = controller.store.document
    val entries = remember(docSnapshot, document) {
        val activeSlides = document.slides.filter { !it.skipped }
        buildHeaderBandEntries(docSnapshot) { slideId, sceneIdx ->
            (activeSlides.firstOrNull { it.id == slideId } ?: activeSlides.getOrNull(sceneIdx))?.title
        }
    }
    val slides = remember(entries) { entries.filterIsInstance<HeaderBandEntry.Slide>() }
    val beats = remember(entries) { entries.filterIsInstance<HeaderBandEntry.Beat>() }
    val transitions = remember(entries) { entries.filterIsInstance<HeaderBandEntry.Transition>() }

    val colors = JewelTheme.globalColors
    val accent = studioAccentColor()
    val isDark = JewelTheme.isDark
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val textCache = remember(textMeasurer) { TimelineTextLayoutCache(maxEntries = 512) }
    val viewport = controller.viewport
    val latestOnFocusRange by rememberUpdatedState(onFocusRange)
    val totalMs = docSnapshot.snapshot.totalDurationMs.coerceAtLeast(1)
    val activeScene = controller.currentPresentedSceneIndex
    val selection = controller.selection
    val showScrubber = slides.size > 1 && viewport.contentWidthDp > viewport.viewportWidthDp(surface) * 1.05f &&
        viewport.viewportWidthDp(surface) > 0f
    val segmentsHeight = if (showScrubber) HEADER_BAND_HEIGHT - SCRUBBER_HEIGHT else HEADER_BAND_HEIGHT

    val latestSlides by rememberUpdatedState(slides)
    val latestBeats by rememberUpdatedState(beats)

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(HEADER_BAND_HEIGHT)
            .background(colors.panelBackground)
            .clipToBounds()
            .semantics { contentDescription = "Deck header band: ${slides.size} slides" }
            .testTag("ciacola-timeline-header-band"),
    ) {
        val widthPx = with(density) { maxWidth.toPx() }
        val segPx = with(density) { segmentsHeight.toPx() }
        val segmentInk = colors.text.normal
        val mutedFill = if (isDark) Color.White.copy(alpha = 0.06f) else Color.Black.copy(alpha = 0.05f)
        val altFill = if (isDark) Color.White.copy(alpha = 0.10f) else Color.Black.copy(alpha = 0.09f)
        val hatchInk = colors.text.info.copy(alpha = 0.55f)
        val selectedBeat = selection as? DocumentTimelineSelection.BeatHeader
        val selectedTrans = selection as? DocumentTimelineSelection.TransitionHeader

        // 1. Segments + hatched joins + playhead (one canvas, visible range only).
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(segmentsHeight)
                // Keyed on the controller/viewport so a deck switch (new controller) restarts the
                // gesture; the focus callback is read through `rememberUpdatedState`.
                .pointerInput(surface, controller, viewport) {
                    detectTapGestures { offset ->
                        val ms = viewport.timeForX(offset.x, surface).roundToInt()
                        val slide = latestSlides.lastOrNull { ms >= it.startMs } ?: latestSlides.firstOrNull()
                            ?: return@detectTapGestures
                        val base = latestBeats.firstOrNull { it.info.sceneIndex == slide.sceneIndex && it.info.beatIndex == 0 }
                        controller.seekToMs(slide.startMs)
                        if (base != null) controller.selectBeatHeader(base.info.slideId, 0)
                        latestOnFocusRange(slide.startMs, (slide.endMs - slide.startMs).coerceAtLeast(400))
                    }
                }
                .testTag("ciacola-timeline-header-segments"),
        ) {
            val labelStyle = TextStyle(fontSize = 11.sp, color = segmentInk, fontWeight = FontWeight.Medium)
            val activeLabelStyle = labelStyle.copy(fontWeight = FontWeight.SemiBold)
            for (slide in slides) {
                val x0 = viewport.xForTime(slide.startMs.toFloat(), surface)
                val x1 = viewport.xForTime(slide.endMs.toFloat(), surface).coerceAtLeast(x0 + 2f)
                if (x1 < -2f || x0 > size.width + 2f) continue
                val isActive = slide.sceneIndex == activeScene
                val fill = when {
                    isActive -> accent.copy(alpha = 0.18f)
                    slide.sceneIndex % 2 == 0 -> mutedFill
                    else -> altFill
                }
                drawRoundRect(
                    color = fill,
                    topLeft = Offset(x0 + 0.5f, 1f),
                    size = Size((x1 - x0 - 1f).coerceAtLeast(1f), segPx - 2f),
                    cornerRadius = CornerRadius(3f, 3f),
                )
                if (isActive) {
                    drawRoundRect(
                        color = accent,
                        topLeft = Offset(x0 + 0.5f, 1f),
                        size = Size((x1 - x0 - 1f).coerceAtLeast(1f), segPx - 2f),
                        cornerRadius = CornerRadius(3f, 3f),
                        style = Stroke(width = 1f * density.density),
                    )
                }
                val labelLeft = x0.coerceAtLeast(0f) + 6f * density.density
                val reservedRight = if (isActive) with(density) { (ADD_BEAT_SIZE + 4.dp).toPx() } else 4f
                val labelMax = (x1.coerceAtMost(size.width) - labelLeft - reservedRight).toInt()
                if (labelMax > 18 * density.density) {
                    val measured = textCache.measure(
                        textMeasurer = textMeasurer,
                        text = slide.label,
                        style = if (isActive) activeLabelStyle else labelStyle,
                        density = density.density,
                        maxWidthPx = labelMax,
                        overflow = TextOverflow.Ellipsis,
                    )
                    drawText(measured, topLeft = Offset(labelLeft, ((segPx - measured.size.height) / 2f).coerceAtLeast(0f)))
                }
            }
            for (t in transitions) {
                val x0 = viewport.xForTime(t.startMs.toFloat(), surface)
                val x1 = viewport.xForTime(t.endMs.toFloat(), surface).coerceAtLeast(x0 + 4f * density.density)
                if (x1 < -2f || x0 > size.width + 2f) continue
                val isSel = selectedTrans?.fromSlideId == t.info.fromSlideId && selectedTrans.toSlideId == t.info.toSlideId
                clipRect(left = x0, top = 1f, right = x1, bottom = segPx - 1f) {
                    val gap = 4f * density.density
                    var hx = x0 - segPx
                    while (hx < x1) {
                        drawLine(
                            color = if (isSel) accent else hatchInk,
                            start = Offset(hx, segPx),
                            end = Offset(hx + segPx, 0f),
                            strokeWidth = 1f * density.density,
                        )
                        hx += gap
                    }
                }
            }
            val playX = viewport.xForTime(controller.playheadMs.toFloat(), surface)
            if (playX in -2f..(size.width + 2f)) {
                drawLine(Color(0xFFFF6B57), Offset(playX, 0f), Offset(playX, segPx), strokeWidth = 1.5f * density.density)
            }
        }

        // 2. Beat ticks (only visible ones are composed) with select/seek + `Delete Beat` menu.
        val tickHalfPx = with(density) { (BEAT_TICK_HIT_WIDTH / 2).toPx() }
        for (beat in beats) {
            val x = viewport.xForTime(beat.startMs.toFloat(), surface)
            if (x < -tickHalfPx || x > widthPx + tickHalfPx) continue
            val info = beat.info
            val isSel = selectedBeat?.slideId == info.slideId && selectedBeat.beatIndex == info.beatIndex
            val slideTitle = slides.firstOrNull { it.sceneIndex == info.sceneIndex }?.title ?: "Slide ${info.sceneIndex + 1}"
            val canDelete = info.beatIndex >= 1 && !controller.store.isReadOnly
            // Offset lives on the outermost box so hover, tooltip and context-menu hit areas follow the tick.
            Box(Modifier.offset { IntOffset((x - tickHalfPx).roundToInt(), 0) }.width(BEAT_TICK_HIT_WIDTH).height(segmentsHeight)) {
            ContextMenuArea(
                items = {
                    if (canDelete) {
                        listOf(ContextMenuItem("Delete Beat") { controller.deleteBeat(info.slideId, info.beatIndex) })
                    } else {
                        emptyList()
                    }
                },
            ) {
                Tooltip(tooltip = { Text("${beat.label} · $slideTitle") }) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clickable {
                                controller.seekToMs(info.startMs)
                                controller.selectBeatHeader(info.slideId, info.beatIndex)
                                onFocusRange(info.startMs, info.segmentMs)
                            }
                            .semantics { contentDescription = "${beat.label} of $slideTitle" }
                            .testTag("ciacola-header-beat-${info.slideId}-${info.beatIndex}"),
                        contentAlignment = Alignment.BottomCenter,
                    ) {
                        // Ruler-style tick in the lower part of the band so it never strikes through labels;
                        // the selected beat gets a taller accent marker.
                        Box(
                            Modifier
                                .width(if (isSel) 3.dp else 2.dp)
                                .height(if (isSel) 10.dp else 6.dp)
                                .background(if (isSel) accent else colors.text.info.copy(alpha = 0.9f)),
                        )
                    }
                }
            }
            }
        }

        // 3. Transition joins: clickable hit areas over the hatching.
        for (t in transitions) {
            val x0 = viewport.xForTime(t.startMs.toFloat(), surface)
            val x1 = viewport.xForTime(t.endMs.toFloat(), surface)
            val minW = with(density) { 8.dp.toPx() }
            val w = (x1 - x0).coerceAtLeast(minW)
            val left = if (x1 - x0 < minW) x0 - minW / 2f else x0
            if (left + w < 0f || left > widthPx) continue
            Tooltip(
                tooltip = { Text(t.label) },
                modifier = Modifier.offset { IntOffset(left.roundToInt(), 0) }.width(with(density) { w.toDp() }).height(segmentsHeight),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable {
                            controller.seekToMs(t.info.startMs)
                            controller.selectTransitionHeader(t.info.fromSlideId, t.info.toSlideId)
                            onFocusRange(t.info.startMs, t.info.segmentMs)
                        }
                        .semantics { contentDescription = "Transition ${t.label}" }
                        .testTag("ciacola-header-transition-${t.info.fromSlideId}-${t.info.toSlideId}"),
                )
            }
        }

        // 4. `+` at the end of the active slide segment appends a beat.
        val activeSlide = slides.firstOrNull { it.sceneIndex == activeScene }
        if (activeSlide != null && !controller.store.isReadOnly && controller.activeSubCompLoopId == null) {
            val endX = viewport.xForTime(activeSlide.endMs.toFloat(), surface)
            val btnPx = with(density) { ADD_BEAT_SIZE.toPx() }
            val startX = viewport.xForTime(activeSlide.startMs.toFloat(), surface)
            // Only inside the active segment, and only when it is wide enough: never spill over
            // earlier slides' labels or transitions in narrow (`All` scope / collapsed) segments.
            val left = addBeatButtonLeft(startX, endX, widthPx, btnPx, minSlackPx = 8f * density.density)
            if (left != null) {
                Tooltip(
                    tooltip = { Text("Add a build to ${activeSlide.title}") },
                    modifier = Modifier
                        .offset { IntOffset(left.roundToInt(), ((segPx - btnPx) / 2f).roundToInt().coerceAtLeast(0)) }
                        .width(ADD_BEAT_SIZE)
                        .height(ADD_BEAT_SIZE),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(4.dp))
                            .background(accent.copy(alpha = 0.16f))
                            .border(1.dp, accent, RoundedCornerShape(4.dp))
                            .clickable { controller.insertBeat(activeSlide.slideId, activeSlide.beatCount) }
                            .semantics { contentDescription = "Add beat to ${activeSlide.title}" }
                            .testTag("ciacola-doc-timeline-insert-beat"),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("+", style = studioCaptionStyle().copy(fontWeight = FontWeight.SemiBold), color = colors.text.normal)
                    }
                }
            }
        }

        // 5. Viewport scrubber (zoomed in): whole deck mapped to the band width + draggable window.
        if (showScrubber) {
            ViewportScrubber(
                controller = controller,
                surface = surface,
                slides = slides,
                totalMs = totalMs,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .height(SCRUBBER_HEIGHT),
            )
        }
    }
}

@Composable
private fun ViewportScrubber(
    controller: DocumentTimelineController,
    surface: TimelineSurface,
    slides: List<HeaderBandEntry.Slide>,
    totalMs: Int,
    modifier: Modifier,
) {
    val viewport = controller.viewport
    val accent = studioAccentColor()
    val isDark = JewelTheme.isDark
    val track = if (isDark) Color.White.copy(alpha = 0.08f) else Color.Black.copy(alpha = 0.07f)
    val stripe = if (isDark) Color.White.copy(alpha = 0.16f) else Color.Black.copy(alpha = 0.14f)
    val latestTotal by rememberUpdatedState(totalMs)
    Canvas(
        modifier = modifier
            .pointerInput(surface, controller, viewport) {
                fun centerOn(xPx: Float) {
                    val w = size.width.toFloat().coerceAtLeast(1f)
                    val ms = (xPx / w).coerceIn(0f, 1f) * latestTotal
                    val visibleMs = viewport.timeForX(viewport.viewportWidthPx(surface), surface) - viewport.timeForX(0f, surface)
                    val startMs = (ms - visibleMs / 2f).coerceAtLeast(0f)
                    viewport.scrollHorizontallyTo(startMs * viewport.pxPerMs(surface), surface)
                }
                detectHorizontalDragGestures(
                    onDragStart = { centerOn(it.x) },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        centerOn(change.position.x)
                    },
                )
            }
            .pointerInput(surface, controller, viewport) {
                detectTapGestures { offset ->
                    val w = size.width.toFloat().coerceAtLeast(1f)
                    val ms = (offset.x / w).coerceIn(0f, 1f) * latestTotal
                    val visibleMs = viewport.timeForX(viewport.viewportWidthPx(surface), surface) - viewport.timeForX(0f, surface)
                    viewport.scrollHorizontallyTo((ms - visibleMs / 2f).coerceAtLeast(0f) * viewport.pxPerMs(surface), surface)
                }
            }
            .testTag("ciacola-header-viewport-scrubber"),
    ) {
        drawRect(track, Offset.Zero, size)
        val total = totalMs.toFloat().coerceAtLeast(1f)
        for (s in slides) {
            if (s.sceneIndex % 2 == 1) {
                val x0 = s.startMs / total * size.width
                val x1 = s.endMs / total * size.width
                drawRect(stripe, Offset(x0, 0f), Size((x1 - x0).coerceAtLeast(1f), size.height))
            }
        }
        val visStart = viewport.timeForX(0f, surface).coerceAtLeast(0f)
        val visEnd = viewport.timeForX(viewport.viewportWidthPx(surface), surface).coerceAtMost(total)
        val wx0 = visStart / total * size.width
        val wx1 = (visEnd / total * size.width).coerceAtLeast(wx0 + 3f)
        drawRect(accent.copy(alpha = 0.35f), Offset(wx0, 0f), Size(wx1 - wx0, size.height))
        drawRect(accent, Offset(wx0, 0f), Size(wx1 - wx0, size.height), style = Stroke(1f))
    }
}

/**
 * Left edge of the inline `+` (add build) button inside the visible part of the active slide
 * segment `[startX, endX]`, or `null` when that visible part is narrower than the button plus
 * [minSlackPx]. The result is clamped to `[visibleStart + 2, visibleEnd - btnPx - 2]`.
 */
internal fun addBeatButtonLeft(startX: Float, endX: Float, widthPx: Float, btnPx: Float, minSlackPx: Float): Float? {
    val visibleStart = startX.coerceAtLeast(0f)
    val visibleEnd = endX.coerceAtMost(widthPx)
    if (visibleEnd - visibleStart < btnPx + minSlackPx) return null
    val minLeft = visibleStart + 2f
    val maxLeft = visibleEnd - btnPx - 2f
    return (endX - btnPx - 3f).coerceIn(minLeft, maxLeft.coerceAtLeast(minLeft))
}
