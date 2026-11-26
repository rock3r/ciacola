package dev.sebastiano.ciacola.document.render

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import dev.sebastiano.ciacola.animation.AnimationFrame
import dev.sebastiano.ciacola.animation.animationDurationMs
import dev.sebastiano.ciacola.animation.frameIndexAt
import dev.sebastiano.ciacola.document.ClipPlayback
import dev.sebastiano.ciacola.document.ClipPlayhead
import dev.sebastiano.ciacola.document.ClipWindow
import dev.sebastiano.ciacola.document.DeckClipControls
import dev.sebastiano.ciacola.document.ImagePayload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runInterruptible

/** How long the scrub bar stays after the pointer leaves the clip, before it fades out. */
private const val SCRUB_BAR_LINGER_MS = 1000L
private const val SCRUB_BAR_FADE_MS = 250

/**
 * Renders an animated `image` node, or returns `false` when [playback]'s source is not an animated
 * GIF/WebP (or does not fit its decode budget), so the caller keeps the static rendering path.
 *
 * Live mounts decode in the background (showing the static first frame meanwhile) and drive a
 * session-retained [ClipPlayhead] from the beat timeline. Other mounts (stills, PDF, video, lint)
 * decode synchronously and show the poster frame only.
 */
@Composable
internal fun DocumentClipView(
    deckId: String,
    src: String,
    assetsRootDir: String?,
    contentScale: ContentScale,
    colorFilter: ColorFilter?,
    playback: DocumentImagePlayback,
    modifier: Modifier,
): Boolean {
    val payload = playback.payload
    val info = remember(deckId, src, assetsRootDir) { DocumentAssetLoader.probeAnimation(deckId, src, assetsRootDir) }
        ?: return false
    if (!playback.live) {
        val poster = posterMs(payload)
        // The static path already draws frame zero; only decode when the poster is a later frame.
        if (poster <= 0) return false
        val frames = remember(deckId, src, assetsRootDir, payload.maxPixels) {
            DocumentAssetLoader.loadAnimation(deckId, src, assetsRootDir, payload.maxPixels)
        } ?: return false
        ClipImage(frames[frameIndexAt(frames, poster.toDouble())].first, src, contentScale, colorFilter, modifier)
        return true
    }
    val frames by produceState<List<AnimationFrame>?>(null, deckId, src, assetsRootDir, payload.maxPixels, info) {
        value = runInterruptible(Dispatchers.IO) {
            DocumentAssetLoader.loadAnimation(deckId, src, assetsRootDir, payload.maxPixels)
        }
    }
    val decoded = frames ?: return false
    LiveClip(decoded, src, contentScale, colorFilter, playback, modifier)
    return true
}

@Composable
private fun LiveClip(
    frames: List<AnimationFrame>,
    src: String,
    contentScale: ContentScale,
    colorFilter: ColorFilter?,
    playback: DocumentImagePlayback,
    modifier: Modifier,
) {
    val payload = playback.payload
    val window = remember(payload, frames) { ClipWindow.of(payload, animationDurationMs(frames)) }
    val head = remember(playback.session, playback.retainKey) {
        playback.session.getOrPutRetained(playback.retainKey) { ClipPlayhead() }
    }
    val beat = playback.beat
    val beatState = remember(payload, playback.builds, beat) {
        beat?.let { ClipPlayback.stateAtBeat(payload, playback.builds, it) }
    }
    SideEffect {
        if (beat != null && beatState != null) head.enterBeat(beat, beatState, window)
    }
    DisposableEffect(head) {
        onDispose { head.detach() }
    }

    val restartEpoch = DeckClipControls.restartEpoch
    val composedEpoch = remember(head) { restartEpoch }
    LaunchedEffect(restartEpoch) {
        if (restartEpoch != composedEpoch) head.restart(window)
    }

    val heldAtEnd = !window.loop && head.positionMs >= window.endMs
    LaunchedEffect(head, window, head.playing, head.showPoster, heldAtEnd) {
        if (!head.playing || head.showPoster || heldAtEnd) return@LaunchedEffect
        var last = withFrameNanos { it }
        while (window.loop || head.positionMs < window.endMs) {
            withFrameNanos { now ->
                head.advance((now - last) / 1_000_000.0, window)
                last = now
            }
        }
    }

    val shownMs = if (head.showPoster) posterMs(payload).toDouble() else head.positionMs.coerceAtMost(window.endMs - 0.5)
    val bitmap = frames[frameIndexAt(frames, shownMs)].first
    val scrubModifier = if (playback.scrubbable) {
        Modifier.clipScrubbing(head, window, bitmap, contentScale)
    } else {
        Modifier
    }
    ClipImage(bitmap, src, contentScale, colorFilter, modifier.then(scrubModifier))
}

@Composable
private fun ClipImage(
    bitmap: ImageBitmap,
    src: String,
    contentScale: ContentScale,
    colorFilter: ColorFilter?,
    modifier: Modifier,
) {
    Image(
        bitmap = bitmap,
        contentDescription = src,
        contentScale = contentScale,
        colorFilter = colorFilter,
        modifier = modifier,
    )
}

/**
 * Show-mode scrubbing: hovering reveals a thin progress bar along the bottom of the drawn image,
 * clicking or dragging seeks, and the bar fades out [SCRUB_BAR_LINGER_MS] after the pointer leaves.
 */
@Composable
private fun Modifier.clipScrubbing(
    head: ClipPlayhead,
    window: ClipWindow,
    bitmap: ImageBitmap,
    contentScale: ContentScale,
): Modifier {
    var hovered by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }
    val barAlpha = remember { Animatable(0f) }
    LaunchedEffect(hovered || dragging) {
        if (hovered || dragging) {
            barAlpha.snapTo(1f)
        } else if (barAlpha.value > 0f) {
            delay(SCRUB_BAR_LINGER_MS)
            barAlpha.animateTo(0f, tween(SCRUB_BAR_FADE_MS))
        }
    }
    val intrinsic = Size(bitmap.width.toFloat(), bitmap.height.toFloat())
    return this
        .testTag("doc-clip-scrub")
        .pointerInput(head, window, intrinsic, contentScale) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull() ?: continue
                    val drawn = drawnImageRect(intrinsic, Size(size.width.toFloat(), size.height.toFloat()), contentScale)
                    fun seekTo(x: Float) {
                        val fraction = if (drawn.width > 0f) ((x - drawn.left) / drawn.width).coerceIn(0f, 1f) else 0f
                        head.seek(window.startMs + fraction * window.lengthMs, window)
                    }
                    when (event.type) {
                        PointerEventType.Enter -> hovered = true
                        PointerEventType.Exit -> hovered = false
                        PointerEventType.Press -> {
                            hovered = true
                            dragging = true
                            seekTo(change.position.x)
                            change.consume()
                        }
                        PointerEventType.Move -> {
                            hovered = true
                            if (dragging && change.pressed) {
                                seekTo(change.position.x)
                                change.consume()
                            }
                        }
                        PointerEventType.Release -> {
                            if (dragging) change.consume()
                            dragging = false
                        }
                    }
                }
            }
        }
        .drawWithContent {
            drawContent()
            val alpha = barAlpha.value
            if (alpha <= 0f) return@drawWithContent
            val drawn = drawnImageRect(intrinsic, size, contentScale)
            val height = (drawn.height * 0.008f).coerceIn(3f, 10f)
            val top = drawn.bottom - height
            val progress = if (window.lengthMs > 0.0) ((head.positionMs - window.startMs) / window.lengthMs).toFloat().coerceIn(0f, 1f) else 0f
            drawRect(Color.Black.copy(alpha = 0.45f * alpha), Offset(drawn.left, top), Size(drawn.width, height))
            drawRect(Color.White.copy(alpha = 0.9f * alpha), Offset(drawn.left, top), Size(drawn.width * progress, height))
        }
}

/** Rectangle the image occupies inside [box] for [contentScale] (centred, like `Image`). */
internal fun drawnImageRect(intrinsic: Size, box: Size, contentScale: ContentScale): Rect {
    if (intrinsic.width <= 0f || intrinsic.height <= 0f || box.width <= 0f || box.height <= 0f) {
        return Rect(Offset.Zero, box)
    }
    val scale = contentScale.computeScaleFactor(intrinsic, box)
    val drawnSize = Size(intrinsic.width * scale.scaleX, intrinsic.height * scale.scaleY)
    val offset: IntOffset = Alignment.Center.align(
        IntSize(drawnSize.width.toInt(), drawnSize.height.toInt()),
        IntSize(box.width.toInt(), box.height.toInt()),
        LayoutDirection.Ltr,
    )
    val left = offset.x.toFloat().coerceAtLeast(0f)
    val top = offset.y.toFloat().coerceAtLeast(0f)
    return Rect(left, top, (left + drawnSize.width).coerceAtMost(box.width), (top + drawnSize.height).coerceAtMost(box.height))
}

private fun posterMs(payload: ImagePayload): Int = payload.posterMs ?: payload.startMs ?: 0
