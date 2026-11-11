package dev.sebastiano.ciacola.timeline

import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import kotlin.math.abs
import kotlin.math.max

/** Left padding (px) before time 0 in the timeline canvas. */
internal const val TIMELINE_LEFT_PAD_PX = 16f

/** Frame-level zoom for the `;` toggle: one 60 fps frame spans ~12 px. */
internal const val FRAME_LEVEL_PX_PER_MS = 0.72f
internal const val MIN_PX_PER_MS = 0.01f
internal const val MAX_PX_PER_MS = 3f

/**
 * Horizontal time zoom + 2D scroll position of the timeline canvas. Vertical scrolling shares a
 * [ScrollState] with the layer-header column so rows always stay aligned.
 */
@Stable
class TimelineViewportState {
    var pxPerMs by mutableFloatStateOf(0.12f)
        private set
    var scrollX by mutableFloatStateOf(0f)
        private set
    val verticalScroll = ScrollState(0)
    var viewportWidthPx by mutableFloatStateOf(0f)
    var contentDurationMs by mutableIntStateOf(1)

    /** Track id whose row should be scrolled into view by the canvas (consumed once). */
    var pendingRevealTrackId by mutableStateOf<String?>(null)

    val contentWidthPx: Float get() = TIMELINE_LEFT_PAD_PX * 2 + contentDurationMs * pxPerMs + 48f
    val maxScrollX: Float get() = max(0f, contentWidthPx - viewportWidthPx)

    fun xForTime(timeMs: Float): Float = TIMELINE_LEFT_PAD_PX + timeMs * pxPerMs - scrollX
    fun timeForX(x: Float): Float = (x + scrollX - TIMELINE_LEFT_PAD_PX) / pxPerMs

    fun fitPxPerMs(): Float {
        val usable = (viewportWidthPx - TIMELINE_LEFT_PAD_PX * 2 - 48f).coerceAtLeast(80f)
        return (usable / contentDurationMs.coerceAtLeast(1)).coerceIn(MIN_PX_PER_MS, MAX_PX_PER_MS)
    }

    /** Scrolls horizontally by [deltaPx] (positive = later in time); returns the consumed delta. */
    fun scrollHorizontallyBy(deltaPx: Float): Float {
        val before = scrollX
        scrollX = (scrollX + deltaPx).coerceIn(0f, maxScrollX)
        return scrollX - before
    }

    fun scrollHorizontallyTo(px: Float) {
        scrollX = px.coerceIn(0f, maxScrollX)
    }

    /** Zooms by [factor] keeping the time under [anchorX] fixed (AE Alt+wheel behaviour). */
    fun zoomBy(factor: Float, anchorX: Float) {
        setZoom(pxPerMs * factor, anchorX)
    }

    fun setZoom(newPxPerMs: Float, anchorX: Float) {
        val anchorTime = timeForX(anchorX)
        pxPerMs = newPxPerMs.coerceIn(minOf(MIN_PX_PER_MS, fitPxPerMs()), MAX_PX_PER_MS)
        scrollX = (TIMELINE_LEFT_PAD_PX + anchorTime * pxPerMs - anchorX).coerceIn(0f, maxScrollX)
    }

    fun zoomToFit() {
        pxPerMs = fitPxPerMs()
        scrollX = 0f
    }

    val isNearFit: Boolean get() = abs(pxPerMs - fitPxPerMs()) / fitPxPerMs() < 0.08f

    /** `;` in After Effects: toggle between the whole composition and frame-level detail. */
    fun toggleFitAndFrames(anchorTimeMs: Float) {
        if (isNearFit) {
            val anchorX = xForTime(anchorTimeMs).coerceIn(0f, viewportWidthPx)
            setZoom(FRAME_LEVEL_PX_PER_MS, anchorX)
            ensureTimeVisible(anchorTimeMs)
        } else {
            zoomToFit()
        }
    }

    fun ensureTimeVisible(timeMs: Float, marginPx: Float = 48f) {
        if (viewportWidthPx <= 0f) return
        val x = xForTime(timeMs)
        if (x < marginPx) {
            scrollHorizontallyBy(x - marginPx)
        } else if (x > viewportWidthPx - marginPx) {
            scrollHorizontallyBy(x - (viewportWidthPx - marginPx))
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
