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
import kotlin.math.abs
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
const val MIN_DP_PER_MS = 0.01f
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

    /** Zooms by [factor] keeping the time under [anchorX] fixed (AE Alt+wheel behaviour). */
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

    /** `;` in After Effects: toggle between the whole composition and frame-level detail. */
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
}

/** Zoom/pan of the slide preview (After Effects composition viewer behaviour). */
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
