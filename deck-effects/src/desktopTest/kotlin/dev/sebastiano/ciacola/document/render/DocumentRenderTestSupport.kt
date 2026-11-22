package dev.sebastiano.ciacola.document.render

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import dev.bnorm.storyboard.AdvanceDirection
import dev.bnorm.storyboard.Storyboard
import dev.bnorm.storyboard.easel.Animatic
import dev.bnorm.storyboard.easel.Easel
import dev.bnorm.storyboard.easel.SceneMode
import dev.bnorm.storyboard.easel.rememberAnimatic
import dev.sebastiano.ciacola.document.DeckDocument
import dev.sebastiano.ciacola.document.DeckDocumentStore
import dev.sebastiano.ciacola.document.DeckLoadState
import dev.sebastiano.ciacola.document.RenderPurpose
import dev.sebastiano.ciacola.document.RenderSession
import dev.sebastiano.ciacola.document.StageSize
import java.io.File
import javax.swing.SwingUtilities
import kotlin.math.abs
import kotlin.math.max
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image as SkiaImage
import org.jetbrains.skia.ImageInfo

/** Captured RGBA frame from an offscreen [ImageComposeScene] render. */
internal class RenderedFrame(
    val width: Int,
    val height: Int,
    val rgba: ByteArray,
) {
    fun pixelRgba(x: Int, y: Int): IntArray {
        val cx = x.coerceIn(0, width - 1)
        val cy = y.coerceIn(0, height - 1)
        val off = (cy * width + cx) * 4
        return intArrayOf(
            rgba[off].toInt() and 0xFF,
            rgba[off + 1].toInt() and 0xFF,
            rgba[off + 2].toInt() and 0xFF,
            rgba[off + 3].toInt() and 0xFF,
        )
    }

    fun pixelColor(x: Int, y: Int): Color {
        val p = pixelRgba(x, y)
        return Color(red = p[0], green = p[1], blue = p[2], alpha = p[3])
    }

    fun countNonMatchingPixels(expectedR: Int, expectedG: Int, expectedB: Int, tolerance: Int = 4): Int {
        var count = 0
        var i = 0
        while (i < rgba.size) {
            val dr = abs((rgba[i].toInt() and 0xFF) - expectedR)
            val dg = abs((rgba[i + 1].toInt() and 0xFF) - expectedG)
            val db = abs((rgba[i + 2].toInt() and 0xFF) - expectedB)
            if (dr > tolerance || dg > tolerance || db > tolerance) count++
            i += 4
        }
        return count
    }
}

internal data class FrameDiff(
    val maxChannelDelta: Int,
    val differingPixels: Int,
    val differingPixelsOutsideBox: Int = differingPixels,
)

internal fun diffFrames(
    a: RenderedFrame,
    b: RenderedFrame,
    tolerance: Int = 2,
    excludeBox: IntArray? = null, // [left, top, right, bottom] in pixels
    excludeBoxes: List<IntArray> = emptyList(),
): FrameDiff {
    require(a.width == b.width && a.height == b.height) {
        "Frame dimension mismatch: ${a.width}x${a.height} vs ${b.width}x${b.height}"
    }
    val allExcluded = buildList {
        if (excludeBox != null) add(excludeBox)
        addAll(excludeBoxes)
    }
    var maxDelta = 0
    var diffAll = 0
    var diffOutside = 0
    for (y in 0 until a.height) {
        val rowOff = y * a.width * 4
        for (x in 0 until a.width) {
            val off = rowOff + x * 4
            val d0 = abs((a.rgba[off].toInt() and 0xFF) - (b.rgba[off].toInt() and 0xFF))
            val d1 = abs((a.rgba[off + 1].toInt() and 0xFF) - (b.rgba[off + 1].toInt() and 0xFF))
            val d2 = abs((a.rgba[off + 2].toInt() and 0xFF) - (b.rgba[off + 2].toInt() and 0xFF))
            val d3 = abs((a.rgba[off + 3].toInt() and 0xFF) - (b.rgba[off + 3].toInt() and 0xFF))
            val d = max(max(d0, d1), max(d2, d3))
            if (d > maxDelta) maxDelta = d
            if (d > tolerance) {
                diffAll++
                val inExcluded = allExcluded.any { box ->
                    x >= box[0] && x < box[2] && y >= box[1] && y < box[3]
                }
                if (!inExcluded) diffOutside++
            }
        }
    }
    return FrameDiff(maxDelta, diffAll, diffOutside)
}

internal fun assertFramesEqual(
    expected: RenderedFrame,
    actual: RenderedFrame,
    message: String = "Frames should match pixel-for-pixel",
    tolerance: Int = 2,
    maxDifferingPixels: Int = 0,
) {
    val diff = diffFrames(expected, actual, tolerance = tolerance)
    assertTrue(
        diff.differingPixels <= maxDifferingPixels,
        "$message (maxChannelDelta=${diff.maxChannelDelta}, differingPixels=${diff.differingPixels}, tol=$tolerance)",
    )
}

internal fun assertColorNear(
    actual: Color,
    expectedR: Int,
    expectedG: Int,
    expectedB: Int,
    tolerance: Int = 8,
    label: String = "pixel",
) {
    val ar = (actual.red * 255f).toInt()
    val ag = (actual.green * 255f).toInt()
    val ab = (actual.blue * 255f).toInt()
    assertTrue(
        abs(ar - expectedR) <= tolerance &&
            abs(ag - expectedG) <= tolerance &&
            abs(ab - expectedB) <= tolerance,
        "$label expected RGB($expectedR,$expectedG,$expectedB)±$tolerance, got RGB($ar,$ag,$ab)",
    )
}

internal fun readSkiaImageFrame(image: SkiaImage): RenderedFrame {
    val w = image.width
    val h = image.height
    val info = ImageInfo(w, h, ColorType.RGBA_8888, ColorAlphaType.PREMUL)
    val bmp = Bitmap()
    try {
        bmp.allocPixels(info)
        check(image.readPixels(bmp, 0, 0)) { "Failed to read Skia Image pixels" }
        val bytes = bmp.readPixels(info, w * 4, 0, 0) ?: ByteArray(w * h * 4)
        return RenderedFrame(w, h, bytes)
    } finally {
        bmp.close()
    }
}

internal fun bundledFontsDirPath(): String? {
    val prop = System.getProperty("ciacola.fonts.dir")
    if (!prop.isNullOrBlank() && File(prop).isDirectory) return prop
    val candidates = listOf(
        File("deck/src/desktopMain/resources/fonts"),
        File("../deck/src/desktopMain/resources/fonts"),
    )
    return candidates.firstOrNull { it.isDirectory }?.absolutePath
}

/**
 * Deterministic offscreen Compose Desktop test harness mounting a [DeckDocument] through
 * [rememberDocumentStoryboard], [rememberAnimatic], and [Easel].
 */
@OptIn(ExperimentalComposeUiApi::class)
internal class DocumentStageHarness(
    initialDocument: DeckDocument,
    initialConfig: DocumentRenderConfig = DocumentRenderConfig(
        baseDensity = 1f,
        fontsDir = bundledFontsDirPath(),
    ),
    initialIndex: Storyboard.Index = Storyboard.Index(0, 0),
    existingSession: RenderSession? = null,
    purpose: RenderPurpose = RenderPurpose.Present,
    sceneMode: SceneMode = SceneMode.Preview,
    extraOverlayContent: (@Composable (Storyboard, Animatic) -> Unit)? = null,
) : AutoCloseable {
    val store: DeckDocumentStore = DeckDocumentStore(
        initialDocument = initialDocument,
        initialLoad = DeckLoadState.Loaded,
    )
    val session: RenderSession = existingSession ?: when (purpose) {
        RenderPurpose.Present -> RenderSession.forPresent(store = store)
        RenderPurpose.Still -> RenderSession.forStill(initialDocument)
        RenderPurpose.Video -> RenderSession.forVideo(initialDocument)
    }
    private var _config: DocumentRenderConfig by mutableStateOf(initialConfig)
    var config: DocumentRenderConfig
        get() = onUiThread { _config }
        set(value) = onUiThread { _config = value }

    private var _storyboard: Storyboard? = null
    private var _animatic: Animatic? = null
    private var _scope: CoroutineScope? = null

    val storyboard: Storyboard get() = checkNotNull(_storyboard)
    val animatic: Animatic get() = checkNotNull(_animatic)
    val scope: CoroutineScope get() = checkNotNull(_scope)

    val width: Int = initialDocument.stage.width
    val height: Int = initialDocument.stage.height

    private var clockNanos: Long = 0L

    /**
     * Compose Desktop's internal `RectManager` schedules debounced `RectList.defragment()` callbacks
     * on Skiko's `MainUIDispatcher` (the AWT Event Dispatch Thread). Serializing all offscreen
     * `ImageComposeScene` lifecycle calls onto the EDT avoids concurrent `RectList` / `SlotTable`
     * races when tests run off-EDT.
     */
    fun <T> onUiThread(block: () -> T): T {
        if (SwingUtilities.isEventDispatchThread()) return block()
        var result: T? = null
        var failure: Throwable? = null
        SwingUtilities.invokeAndWait {
            try {
                result = block()
            } catch (t: Throwable) {
                failure = t
            }
        }
        failure?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    val scene: ImageComposeScene = onUiThread {
        ImageComposeScene(
            width = width,
            height = height,
            density = Density(initialConfig.baseDensity, initialConfig.fontScale),
            coroutineContext = Dispatchers.Unconfined,
        ) {
            _scope = rememberCoroutineScope()
            val sb = rememberDocumentStoryboard(session = session, config = _config)
            _storyboard = sb
            val a = rememberAnimatic(initialIndex = initialIndex, storyboard = { sb })
            _animatic = a
            Box(Modifier.fillMaxSize()) {
                Easel(a, mode = sceneMode)
                extraOverlayContent?.invoke(sb, a)
            }
        }
    }

    init {
        settle(4)
    }

    fun renderStep(stepNanos: Long = 10_000_000L): RenderedFrame = onUiThread {
        clockNanos += stepNanos
        val img = scene.render(clockNanos)
        try {
            readSkiaImageFrame(img)
        } finally {
            img.close()
        }
    }

    fun renderCurrent(): RenderedFrame = onUiThread {
        val img = scene.render(clockNanos)
        try {
            readSkiaImageFrame(img)
        } finally {
            img.close()
        }
    }

    fun settle(frames: Int = 4, stepNanos: Long = 10_000_000L): RenderedFrame = onUiThread {
        var last: RenderedFrame? = null
        repeat(frames.coerceAtLeast(1)) {
            last = renderStep(stepNanos)
        }
        checkNotNull(last)
    }

    /** Finds the Animatic state index of a resting beat `(sceneIndex, frameIndex)`. */
    fun valueStateIndex(sceneIndex: Int, frameIndex: Int): Int =
        animatic.stateDescriptors.first {
            it.sceneIndex == sceneIndex &&
                it.frameIndex == frameIndex &&
                !it.isBoundaryStart &&
                !it.isBoundaryEnd
        }.stateIndex

    /** Finds the Animatic state index of `End` of [sceneIndex] (start of cross-slide to `sceneIndex + 1`). */
    fun crossFromStateIndex(fromSceneIndex: Int): Int =
        animatic.stateDescriptors.first {
            it.sceneIndex == fromSceneIndex && it.isBoundaryEnd
        }.stateIndex

    /** Finds the Animatic state index of `Start` of [sceneIndex] (start of beat-0 Entrance). */
    fun entranceFromStateIndex(sceneIndex: Int): Int =
        animatic.stateDescriptors.first {
            it.sceneIndex == sceneIndex && it.isBoundaryStart
        }.stateIndex

    /** Jumps directly to resting [index] and settles. */
    fun jumpAndSettle(index: Storyboard.Index, settleFrames: Int = 4): RenderedFrame = onUiThread {
        val job = scope.launch { animatic.jumpTo(index) }
        drainJob(job)
        settle(settleFrames)
    }

    /** Seeks cold or warm to exact segment [fromStateIndex] at [fraction] (`0f..1f`) and renders. */
    fun seekSegment(fromStateIndex: Int, fraction: Float, rebuildScene: Boolean = true): RenderedFrame = onUiThread {
        val job = scope.launch {
            animatic.seekToSegment(fromStateIndex, fraction, rebuildScene = rebuildScene)
        }
        drainJob(job)
        renderStep(1_000_000L)
    }

    /**
     * Seeks to [fromStateIndex] at [fraction] and returns the very first rendered frame once
     * `seekToSegment` completes (`!animatic.scrubSettling`), without discarding Frame 0.
     */
    fun seekSegmentCaptureFirstLiveFrame(
        fromStateIndex: Int,
        fraction: Float,
        rebuildScene: Boolean = true,
    ): RenderedFrame = onUiThread {
        val job = scope.launch {
            animatic.seekToSegment(fromStateIndex, fraction, rebuildScene = rebuildScene)
        }
        var steps = 0
        var lastFrame: RenderedFrame? = null
        while (!job.isCompleted && steps++ < 200) {
            lastFrame = renderStep(10_000_000L)
        }
        assertTrue(job.isCompleted, "seekToSegment did not complete within 200 steps")
        lastFrame ?: renderStep(1_000_000L)
    }

    /** Advances in [direction] until settled, returning observed non-zero effective durations (in ms) per segment traversed. */
    fun advanceToRest(
        direction: AdvanceDirection,
        stepMs: Long = 10L,
        maxSteps: Int = 400,
    ): List<Long> = onUiThread {
        val seenDurationsNanos = LinkedHashMap<Pair<Int, Int>, Long>()
        val job = scope.launch { animatic.advance(direction) }
        var steps = 0
        while (!job.isCompleted && steps++ < maxSteps) {
            renderStep(stepMs * 1_000_000L)
            if (!animatic.scrubSettling && animatic.currentIndex != animatic.targetIndex) {
                val eff = animatic.effectiveDurationNanos()
                if (eff > 0L) {
                    val key = animatic.activeFromStateIndex to animatic.activeToStateIndex
                    seenDurationsNanos[key] = maxOf(seenDurationsNanos[key] ?: 0L, eff)
                }
            }
        }
        assertTrue(job.isCompleted, "advance($direction) did not finish within $maxSteps steps")
        settle(2)
        seenDurationsNanos.values.toList()
    }

    /**
     * Drives `advance(direction)` and captures the frame when the active transition reaches
     * canonical progress closest to [targetCanonicalFraction] on the segment matching [matchSegment].
     */
    fun advanceAndCaptureAt(
        direction: AdvanceDirection,
        targetCanonicalFraction: Float,
        stepMs: Long = 10L,
        matchSegment: (fromState: Int, toState: Int) -> Boolean = { _, _ -> true },
    ): Pair<RenderedFrame, Long> = onUiThread {
        val job = scope.launch { animatic.advance(direction) }
        var bestFrame: RenderedFrame? = null
        var bestError = Float.MAX_VALUE
        var observedEffNanos = 0L
        var guard = 0
        while (!job.isCompleted && guard++ < 500) {
            val frame = renderStep(stepMs * 1_000_000L)
            if (!animatic.scrubSettling && animatic.currentIndex != animatic.targetIndex) {
                val fromIdx = animatic.activeFromStateIndex
                val toIdx = animatic.activeToStateIndex
                val eff = animatic.effectiveDurationNanos()
                if (eff > 0L && matchSegment(fromIdx, toIdx)) {
                    observedEffNanos = eff
                    val rawFrac = animatic.activeFraction
                    val canonicalFrac = if (toIdx > fromIdx) rawFrac else 1f - rawFrac
                    val err = abs(canonicalFrac - targetCanonicalFraction)
                    if (err < bestError) {
                        bestError = err
                        bestFrame = frame
                    }
                }
            }
        }
        assertTrue(job.isCompleted, "advanceAndCaptureAt did not complete")
        settle(2)
        checkNotNull(bestFrame) { "Never entered matching active segment during advance($direction)" } to observedEffNanos
    }

    /**
     * Drives a single `advance(direction)` and captures the closest frame to each canonical fraction
     * in [targetCanonicalFractions] on the segment matching [matchSegment].
     */
    fun advanceAndCaptureMultiple(
        direction: AdvanceDirection,
        targetCanonicalFractions: List<Float>,
        stepMs: Long = 10L,
        matchSegment: (fromState: Int, toState: Int) -> Boolean = { _, _ -> true },
    ): Pair<Map<Float, RenderedFrame>, Long> = onUiThread {
        val job = scope.launch { animatic.advance(direction) }
        val bestFrames = LinkedHashMap<Float, RenderedFrame>()
        val bestErrors = HashMap<Float, Float>()
        var observedEffNanos = 0L
        var guard = 0
        while (!job.isCompleted && guard++ < 600) {
            val frame = renderStep(stepMs * 1_000_000L)
            if (!animatic.scrubSettling && animatic.currentIndex != animatic.targetIndex) {
                val fromIdx = animatic.activeFromStateIndex
                val toIdx = animatic.activeToStateIndex
                val eff = animatic.effectiveDurationNanos()
                if (eff > 0L && matchSegment(fromIdx, toIdx)) {
                    observedEffNanos = eff
                    val rawFrac = animatic.activeFraction
                    val canonicalFrac = if (toIdx > fromIdx) rawFrac else 1f - rawFrac
                    for (target in targetCanonicalFractions) {
                        val err = abs(canonicalFrac - target)
                        if (err < (bestErrors[target] ?: Float.MAX_VALUE)) {
                            bestErrors[target] = err
                            bestFrames[target] = frame
                        }
                    }
                }
            }
        }
        assertTrue(job.isCompleted, "advanceAndCaptureMultiple did not complete")
        settle(2)
        for (target in targetCanonicalFractions) {
            check(target in bestFrames) { "Never captured target fraction $target during advance($direction)" }
        }
        bestFrames to observedEffNanos
    }

    /**
     * Drives `advance(Forward)` until canonical fraction crosses [reverseAtCanonicalFraction] on
     * [matchForwardSegment], then reverses in-flight via `advance(Backward)` and captures the
     * closest frame to each fraction in [captureBackwardFractions].
     */
    fun advanceForwardThenReverseAndCapture(
        reverseAtCanonicalFraction: Float,
        captureBackwardFractions: List<Float>,
        stepMs: Long = 10L,
        matchForwardSegment: (fromState: Int, toState: Int) -> Boolean,
    ): Map<Float, RenderedFrame> = onUiThread {
        val fwdJob = scope.launch { animatic.advance(AdvanceDirection.Forward) }
        var guard = 0
        var reversed = false
        while (!fwdJob.isCompleted && !reversed && guard++ < 500) {
            renderStep(stepMs * 1_000_000L)
            if (!animatic.scrubSettling && animatic.currentIndex != animatic.targetIndex) {
                val fromIdx = animatic.activeFromStateIndex
                val toIdx = animatic.activeToStateIndex
                if (matchForwardSegment(fromIdx, toIdx) && animatic.activeFraction >= reverseAtCanonicalFraction - 1e-3f) {
                    reversed = true
                }
            }
        }
        assertTrue(reversed, "Never reached reverse point $reverseAtCanonicalFraction during forward advance")

        val bwdJob = scope.launch { animatic.advance(AdvanceDirection.Backward) }
        val bestFrames = LinkedHashMap<Float, RenderedFrame>()
        val bestErrors = HashMap<Float, Float>()
        var bwdGuard = 0
        while (!bwdJob.isCompleted && bwdGuard++ < 600) {
            val frame = renderStep(stepMs * 1_000_000L)
            if (!animatic.scrubSettling && animatic.currentIndex != animatic.targetIndex) {
                val fromIdx = animatic.activeFromStateIndex
                val toIdx = animatic.activeToStateIndex
                val eff = animatic.effectiveDurationNanos()
                if (eff > 0L && (matchForwardSegment(fromIdx, toIdx) || matchForwardSegment(toIdx, fromIdx))) {
                    val rawFrac = animatic.activeFraction
                    val canonicalFrac = if (toIdx > fromIdx) rawFrac else 1f - rawFrac
                    for (target in captureBackwardFractions) {
                        val err = abs(canonicalFrac - target)
                        if (err < (bestErrors[target] ?: Float.MAX_VALUE)) {
                            bestErrors[target] = err
                            bestFrames[target] = frame
                        }
                    }
                }
            }
        }
        assertTrue(bwdJob.isCompleted, "Mid-flight backward reversal did not complete")
        settle(2)
        for (target in captureBackwardFractions) {
            check(target in bestFrames) { "Never captured reversed fraction $target" }
        }
        bestFrames
    }

    fun drainJob(job: Job, maxSteps: Int = 200, stepNanos: Long = 10_000_000L) = onUiThread {
        var steps = 0
        while (!job.isCompleted && steps++ < maxSteps) {
            renderStep(stepNanos)
        }
        assertTrue(job.isCompleted, "Coroutine job did not complete within $maxSteps steps")
    }

    override fun close() = onUiThread {
        Snapshot.sendApplyNotifications()
        if (scene.hasInvalidations()) {
            scene.render(clockNanos).close()
        }
        scene.close()
    }
}

internal fun compactStage(width: Int = 400, height: Int = 240): StageSize = StageSize(width = width, height = height)
