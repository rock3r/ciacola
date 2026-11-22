package dev.sebastiano.ciacola.timeline

import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

/** Which timeline studio surface owns a canvas viewport measurement. */
enum class TimelineSurface {
    Docked,
    Detached,
}

/** Left padding (dp) before time 0 in the timeline canvas. */
internal const val TIMELINE_LEFT_PAD_DP = 16f

/** Right padding (dp) after the last frame in the timeline canvas. */
internal const val TIMELINE_RIGHT_PAD_DP = 48f

/** Frame-level zoom for the `;` toggle: one 60 fps frame spans ~12 dp. */
const val FRAME_LEVEL_DP_PER_MS = 0.72f
const val MIN_DP_PER_MS = 0.0005f
const val MAX_DP_PER_MS = 3f
const val DEFAULT_LAYER_COLUMN_WIDTH_DP = 236f
const val MIN_LAYER_COLUMN_WIDTH_DP = 180f
const val MAX_LAYER_COLUMN_WIDTH_DP = 420f

/**
 * Density-independent horizontal time zoom (`dpPerMs`) + 2D scroll position of the timeline canvas.
 * Viewport width and horizontal scroll are isolated per [TimelineSurface] so opening both docked
 * and detached Timeline windows simultaneously never clips or locks scrolling on either surface.
 */
@Stable
class TimelineViewportState {
    /** Density-independent zoom in `dp` per millisecond, preserved across 1x/2x DPI changes. */
    var dpPerMs by mutableFloatStateOf(0.12f)
        private set

    /** Whether zoom should automatically re-fit on window resize or snapshot duration updates. */
    var isFitLocked by mutableStateOf(true)
        private set

    var density by mutableFloatStateOf(1f)
        private set

    private val surfaceWidthsDp = mutableStateMapOf<TimelineSurface, Float>()
    private val surfaceScrollsDp = mutableStateMapOf<TimelineSurface, Float>()
    private val surfaceDensities = mutableStateMapOf<TimelineSurface, Float>()

    var activeSurface by mutableStateOf<TimelineSurface?>(null)
        private set

    val verticalScroll = ScrollState(0)
    var contentDurationMs by mutableIntStateOf(1)

    /** User-resizable layer header column width in dp (`180.dp .. 420.dp`). */
    var layerColumnWidthDp by mutableFloatStateOf(DEFAULT_LAYER_COLUMN_WIDTH_DP)
        private set

    fun resizeLayerColumnBy(deltaDp: Float) {
        layerColumnWidthDp = (layerColumnWidthDp + deltaDp).coerceIn(
            MIN_LAYER_COLUMN_WIDTH_DP,
            MAX_LAYER_COLUMN_WIDTH_DP,
        )
    }

    fun resetLayerColumnWidth() {
        layerColumnWidthDp = DEFAULT_LAYER_COLUMN_WIDTH_DP
    }

    /** Track id whose row should be scrolled into view by the canvas (consumed once). */
    var pendingRevealTrackId by mutableStateOf<String?>(null)

    private fun densityFor(surface: TimelineSurface?): Float =
        if (surface != null) surfaceDensities[surface] ?: density else density

    fun updateSurfaceMetrics(surface: TimelineSurface, widthPx: Float, surfaceDensity: Float) {
        val safeDensity = surfaceDensity.coerceAtLeast(0.5f)
        surfaceDensities[surface] = safeDensity
        surfaceWidthsDp[surface] = (widthPx / safeDensity).coerceAtLeast(0f)
        density = safeDensity
        if (activeSurface == null) {
            activeSurface = surface
        }
        val currentScrollDp = surfaceScrollsDp[surface] ?: 0f
        surfaceScrollsDp[surface] = currentScrollDp.coerceIn(0f, maxScrollDp(surface))
    }

    fun markSurfaceActive(surface: TimelineSurface) {
        activeSurface = surface
        surfaceDensities[surface]?.let { density = it }
    }

    fun unregisterSurface(surface: TimelineSurface) {
        surfaceWidthsDp.remove(surface)
        surfaceScrollsDp.remove(surface)
        surfaceDensities.remove(surface)
        if (activeSurface == surface) {
            activeSurface = surfaceWidthsDp.keys.firstOrNull()
        }
    }

    fun viewportWidthDp(surface: TimelineSurface? = activeSurface): Float =
        if (surface != null) surfaceWidthsDp[surface] ?: 0f else 0f

    fun viewportWidthPx(surface: TimelineSurface? = activeSurface): Float =
        viewportWidthDp(surface) * densityFor(surface)

    val viewportWidthPx: Float
        get() = viewportWidthPx(activeSurface)

    fun scrollDp(surface: TimelineSurface? = activeSurface): Float =
        if (surface != null) surfaceScrollsDp[surface] ?: 0f else 0f

    private fun setScrollDp(dp: Float, surface: TimelineSurface? = activeSurface) {
        val s = surface ?: TimelineSurface.Docked
        val clamped = dp.coerceIn(0f, maxScrollDp(s))
        surfaceScrollsDp[s] = clamped
    }

    val pxPerMs: Float get() = pxPerMs(activeSurface)
    fun pxPerMs(surface: TimelineSurface? = activeSurface): Float = dpPerMs * densityFor(surface)

    val scrollX: Float get() = scrollX(activeSurface)
    fun scrollX(surface: TimelineSurface? = activeSurface): Float = scrollDp(surface) * densityFor(surface)

    val contentWidthDp: Float
        get() = TIMELINE_LEFT_PAD_DP * 2 + contentDurationMs * dpPerMs + TIMELINE_RIGHT_PAD_DP

    val contentWidthPx: Float get() = contentWidthPx(activeSurface)
    fun contentWidthPx(surface: TimelineSurface? = activeSurface): Float = contentWidthDp * densityFor(surface)

    fun maxScrollDp(surface: TimelineSurface? = activeSurface): Float =
        max(0f, contentWidthDp - viewportWidthDp(surface))

    val maxScrollX: Float get() = maxScrollX(activeSurface)
    fun maxScrollX(surface: TimelineSurface? = activeSurface): Float =
        maxScrollDp(surface) * densityFor(surface)

    fun xForTime(timeMs: Float, surface: TimelineSurface? = activeSurface): Float {
        val d = densityFor(surface)
        return (TIMELINE_LEFT_PAD_DP + timeMs * dpPerMs - scrollDp(surface)) * d
    }

    fun timeForX(x: Float, surface: TimelineSurface? = activeSurface): Float {
        val d = densityFor(surface)
        val xDp = x / d
        return (xDp + scrollDp(surface) - TIMELINE_LEFT_PAD_DP) / dpPerMs
    }

    fun fitDpPerMs(surface: TimelineSurface? = activeSurface): Float {
        val usableDp = (viewportWidthDp(surface) - TIMELINE_LEFT_PAD_DP * 2 - TIMELINE_RIGHT_PAD_DP).coerceAtLeast(80f)
        return (usableDp / contentDurationMs.coerceAtLeast(1)).coerceIn(MIN_DP_PER_MS, MAX_DP_PER_MS)
    }

    fun fitPxPerMs(surface: TimelineSurface? = activeSurface): Float =
        fitDpPerMs(surface) * densityFor(surface)

    fun minZoomDpPerMs(surface: TimelineSurface? = activeSurface): Float =
        if (viewportWidthDp(surface) > 0f) fitDpPerMs(surface) else MIN_DP_PER_MS

    /** Scrolls horizontally by [deltaPx] (positive = later in time); returns the consumed delta. */
    fun scrollHorizontallyBy(deltaPx: Float, surface: TimelineSurface? = activeSurface): Float {
        val d = densityFor(surface)
        val beforePx = scrollX(surface)
        setScrollDp((beforePx + deltaPx) / d, surface)
        return scrollX(surface) - beforePx
    }

    fun scrollHorizontallyTo(px: Float, surface: TimelineSurface? = activeSurface) {
        val d = densityFor(surface)
        setScrollDp(px / d, surface)
    }

    /** Zooms by [factor] keeping the time under [anchorX] fixed (Alt+wheel). */
    fun zoomBy(factor: Float, anchorX: Float, surface: TimelineSurface? = activeSurface) {
        setZoomDpPerMs(dpPerMs * factor, anchorX, surface)
    }

    /**
     * Sets density-independent zoom ([newDpPerMs]), keeping the time at [anchorX] fixed and
     * clamping minimum zoom to fit so the timeline never shrinks to an unusable sliver.
     */
    fun setZoomDpPerMs(newDpPerMs: Float, anchorX: Float, surface: TimelineSurface? = activeSurface) {
        val s = surface ?: activeSurface
        val d = densityFor(s)
        val anchorTime = timeForX(anchorX, s)
        val minZoom = minZoomDpPerMs(s)
        dpPerMs = newDpPerMs.coerceIn(minZoom, MAX_DP_PER_MS)
        isFitLocked = false
        val newScrollDp = TIMELINE_LEFT_PAD_DP + anchorTime * dpPerMs - (anchorX / d)
        setScrollDp(newScrollDp, s)
    }

    fun zoomToFit(surface: TimelineSurface? = activeSurface) {
        val s = surface ?: activeSurface
        dpPerMs = fitDpPerMs(s)
        setScrollDp(0f, s)
        isFitLocked = true
    }

    /**
     * Zooms and horizontally frames the time interval `[startMs, endMs]` (with optional padding
     * fraction on either side) so a single slide or beat's tracks span ~75-85% of the canvas width
     * even inside 136-slide / 177s presentations.
     */
    fun zoomToRange(
        startMs: Int,
        endMs: Int,
        paddingFraction: Float = 0.08f,
        minSpanMs: Int = 1_000,
        surface: TimelineSurface? = activeSurface,
    ) {
        val s = surface ?: activeSurface
        val safeStart = startMs.coerceAtLeast(0)
        val safeEnd = endMs.coerceAtLeast(safeStart)
        val rawSpan = (safeEnd - safeStart).coerceAtLeast(minSpanMs)
        val padMs = (rawSpan * paddingFraction.coerceIn(0f, 0.4f))
        val paddedSpanMs = (rawSpan + padMs * 2f).coerceAtLeast(minSpanMs.toFloat())
        val viewW = viewportWidthDp(s)
        val usableDp = if (viewW > 0f) {
            (viewW - TIMELINE_LEFT_PAD_DP * 2 - TIMELINE_RIGHT_PAD_DP).coerceAtLeast(80f)
        } else {
            480f
        }
        val targetDpPerMs = (usableDp / paddedSpanMs).coerceIn(minZoomDpPerMs(s), MAX_DP_PER_MS)
        dpPerMs = targetDpPerMs
        isFitLocked = false
        val targetScrollDp = (safeStart - padMs).coerceAtLeast(0f) * dpPerMs
        setScrollDp(targetScrollDp, s)
    }

    /** Re-applies fit zoom on window resize or duration changes while in sticky fit mode. */
    fun applyStickyFitIfNeeded(surface: TimelineSurface? = activeSurface) {
        val s = surface ?: activeSurface
        if (!isFitLocked) return
        if (viewportWidthDp(s) <= 0f || contentDurationMs <= 0) return
        dpPerMs = fitDpPerMs(s)
        setScrollDp(0f, s)
    }

    val isNearFit: Boolean get() = isNearFit(activeSurface)
    fun isNearFit(surface: TimelineSurface? = activeSurface): Boolean {
        val fit = fitDpPerMs(surface).coerceAtLeast(1e-5f)
        return abs(dpPerMs - fit) / fit < 0.08f
    }

    /** `;` toggles between the whole composition and frame-level detail. */
    fun toggleFitAndFrames(anchorTimeMs: Float, surface: TimelineSurface? = activeSurface) {
        val s = surface ?: activeSurface
        if (isNearFit(s)) {
            val anchorX = xForTime(anchorTimeMs, s).coerceIn(0f, viewportWidthPx(s))
            setZoomDpPerMs(FRAME_LEVEL_DP_PER_MS, anchorX, s)
            ensureTimeVisible(anchorTimeMs, surface = s)
        } else {
            zoomToFit(s)
        }
    }

    fun ensureTimeVisible(
        timeMs: Float,
        marginPx: Float = 48f * density,
        surface: TimelineSurface? = activeSurface,
    ) {
        val s = surface ?: activeSurface
        val widthPx = viewportWidthPx(s)
        if (widthPx <= 0f) return
        val margin = marginPx.coerceAtMost(widthPx * 0.4f)
        val x = xForTime(timeMs, s)
        if (x < margin) {
            scrollHorizontallyBy(x - margin, s)
        } else if (x > widthPx - margin) {
            scrollHorizontallyBy(x - (widthPx - margin), s)
        }
    }

    /**
     * Brings `[startMs, endMs]` into the horizontal viewport, preferring the start edge when the
     * range is wider than the viewport. Never changes zoom.
     */
    fun ensureRangeVisible(
        startMs: Float,
        endMs: Float,
        marginPx: Float = 32f * density,
        surface: TimelineSurface? = activeSurface,
    ) {
        val s = surface ?: activeSurface
        val widthPx = viewportWidthPx(s)
        if (widthPx <= 0f) return
        val margin = marginPx.coerceAtMost(widthPx * 0.25f)
        val x0 = xForTime(startMs, s)
        val x1 = xForTime(endMs.coerceAtLeast(startMs), s)
        when {
            x1 - x0 > widthPx - margin * 2 || x0 < margin -> scrollHorizontallyBy(x0 - margin, s)
            x1 > widthPx - margin -> scrollHorizontallyBy(x1 - (widthPx - margin), s)
        }
    }

    /** Snapshot of zoom + horizontal scroll so a scope can restore exactly where the user left it. */
    fun captureZoom(surface: TimelineSurface? = activeSurface): TimelineZoomMemento =
        TimelineZoomMemento(dpPerMs = dpPerMs, scrollDp = scrollDp(surface), fitLocked = isFitLocked)

    fun restoreZoom(memento: TimelineZoomMemento, surface: TimelineSurface? = activeSurface) {
        val s = surface ?: activeSurface
        if (memento.fitLocked) {
            zoomToFit(s)
            return
        }
        dpPerMs = memento.dpPerMs.coerceIn(minZoomDpPerMs(s), MAX_DP_PER_MS)
        isFitLocked = false
        setScrollDp(memento.scrollDp, s)
    }
}

/** Saved zoom level and horizontal scroll for one timeline scope (`Slide` or `All`). */
data class TimelineZoomMemento(
    val dpPerMs: Float,
    val scrollDp: Float,
    val fitLocked: Boolean,
)

/** Zoom/pan of the slide preview (pro compositing viewer behaviour). */
@Stable
class StagePreviewViewportState {
    var scale by mutableFloatStateOf(1f)
        private set
    var offset by mutableStateOf(Offset.Zero)
        private set
    var containerSize by mutableStateOf(Size.Zero)

    val isIdentity: Boolean get() = scale == 1f && offset == Offset.Zero

    fun zoomBy(factor: Float, anchor: Offset) {
        val newScale = (scale * factor).coerceIn(MIN_PREVIEW_SCALE, MAX_PREVIEW_SCALE)
        val applied = newScale / scale
        // Keep the content point under the anchor fixed: anchor = offset + p * scale.
        offset = anchor - (anchor - offset) * applied
        scale = newScale
    }

    fun panBy(delta: Offset) {
        offset += delta
    }

    fun reset() {
        scale = 1f
        offset = Offset.Zero
    }

    companion object {
        const val MIN_PREVIEW_SCALE = 0.25f
        const val MAX_PREVIEW_SCALE = 8f
    }
}

private val MASTER_RULER_TICK_STEPS_MS = intArrayOf(
    50,
    100,
    250,
    500,
    1_000,
    2_000,
    5_000,
    10_000,
    15_000,
    30_000,
    60_000,
    120_000,
    300_000,
    600_000,
)

/**
 * Picks the smallest readable major ruler tick interval (in milliseconds) whose horizontal spacing
 * is at least [minSpacingDp] at [dpPerMs], adapting cleanly from frame-level zoom (`50ms`) out to
 * 136-slide / 15+ minute master decks (`10s`, `15s`, `30s`, `60s`, `120s`, `300s`, `600s`).
 */
internal fun chooseMasterRulerTickStepMs(
    dpPerMs: Float,
    minSpacingDp: Float = 84f,
): Int {
    val safeDpPerMs = dpPerMs.coerceAtLeast(MIN_DP_PER_MS)
    for (step in MASTER_RULER_TICK_STEPS_MS) {
        if (step * safeDpPerMs >= minSpacingDp) return step
    }
    return MASTER_RULER_TICK_STEPS_MS.last()
}

internal fun chooseMasterRulerMinorStepMs(majorStepMs: Int): Int = when (majorStepMs) {
    600_000 -> 120_000
    300_000 -> 60_000
    120_000 -> 30_000
    60_000 -> 15_000
    30_000 -> 10_000
    15_000 -> 5_000
    else -> (majorStepMs / 5).coerceAtLeast(1)
}

/** Master ruler tick label; delegates to the single shared [formatTimelineRulerLabel]. */
fun formatMasterTimelineTick(ms: Int, stepMs: Int, maxVisibleMs: Int = ms): String =
    formatTimelineRulerLabel(ms, stepMs, maxVisibleMs)

/**
 * Maps an x-coordinate on the bird's-eye slide minimap strip to the target 0-based slide index.
 */
internal fun minimapSlideIndexForX(
    xPx: Float,
    widthPx: Float,
    slides: List<SlideSectionBar>,
    totalDurationMs: Int,
): Int {
    if (slides.isEmpty() || widthPx <= 0f) return 0
    val frac = (xPx / widthPx).coerceIn(0f, 1f)
    if (totalDurationMs <= 0) {
        return (frac * slides.size).toInt().coerceIn(0, slides.lastIndex)
    }
    val targetMs = frac * totalDurationMs
    val idx = slides.indexOfFirst { targetMs < it.endMs }
    return if (idx >= 0) idx else slides.lastIndex
}

internal val SLIDE_MINIMAP_HEIGHT = 20.dp
internal const val SLIDE_MINIMAP_THRESHOLD = 20
internal const val LAYER_HEADER_OVERSCAN_ROWS = 4

/**
 * Computes the inclusive row index window `[first..last]` to compose for the virtualized left
 * track-header column, including [overscanRows] rows above and below the visible scroll window.
 */
internal fun computeVisibleTrackRowRange(
    totalRows: Int,
    scrollYPx: Float,
    viewportHeightPx: Float,
    rowHeightPx: Float,
    overscanRows: Int = LAYER_HEADER_OVERSCAN_ROWS,
): IntRange {
    if (totalRows <= 0 || rowHeightPx <= 0f) return IntRange.EMPTY
    val safeScrollY = scrollYPx.coerceAtLeast(0f)
    val effectiveViewportPx = if (viewportHeightPx > 0f) viewportHeightPx else rowHeightPx * 12f
    val firstVisible = floor(safeScrollY / rowHeightPx).toInt()
    val lastVisible = ceil((safeScrollY + effectiveViewportPx) / rowHeightPx).toInt()
    val start = (firstVisible - overscanRows).coerceIn(0, totalRows - 1)
    val end = (lastVisible + overscanRows).coerceIn(start, totalRows - 1)
    return start..end
}
