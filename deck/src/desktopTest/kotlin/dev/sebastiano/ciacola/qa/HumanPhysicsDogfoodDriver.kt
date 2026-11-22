package dev.sebastiano.ciacola.qa

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sebastiano.spectre.core.AutomatorNode
import dev.sebastiano.spectre.core.ComposeAutomator
import dev.sebastiano.spectre.recording.AutoRecorder
import dev.sebastiano.spectre.recording.RecordingOptions
import dev.sebastiano.spectre.recording.screencapturekit.asTitledWindow
import java.awt.EventQueue
import java.awt.Point
import java.awt.Rectangle
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path as NioPath
import java.nio.file.StandardCopyOption
import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Ripple emitted on synthetic human pointer clicks so clicks are unmistakably visible in recordings. */
data class CursorRipple(
    val id: Long,
    val screenX: Float,
    val screenY: Float,
    val startNanos: Long,
    val isDoubleClick: Boolean = false,
)

/** Transient keycap badge rendered in the bottom-right corner when keyboard shortcuts are pressed. */
data class ShortcutBadge(
    val label: String,
    val expiresAtNanos: Long,
)

/** Shared observable cursor & click-ripple state rendered inside a Studio window overlay. */
@Stable
class HumanCursorState {
    var visible: Boolean by mutableStateOf(false)
    var screenX: Float by mutableFloatStateOf(0f)
    var screenY: Float by mutableFloatStateOf(0f)
    var pressed: Boolean by mutableStateOf(false)
    var shortcutBadge: ShortcutBadge? by mutableStateOf(null)
    val ripples = mutableStateListOf<CursorRipple>()

    private var nextRippleId = 1L

    fun moveTo(x: Float, y: Float) {
        screenX = x
        screenY = y
        visible = true
    }

    fun emitRipple(x: Float, y: Float, isDoubleClick: Boolean = false) {
        val now = System.nanoTime()
        ripples.removeAll { (now - it.startNanos) > 550_000_000L }
        ripples.add(
            CursorRipple(
                id = nextRippleId++,
                screenX = x,
                screenY = y,
                startNanos = now,
                isDoubleClick = isDoubleClick,
            ),
        )
    }

    fun showShortcut(label: String, durationMs: Long = 950L) {
        shortcutBadge = ShortcutBadge(
            label = label,
            expiresAtNanos = System.nanoTime() + durationMs * 1_000_000L,
        )
    }
}

/**
 * Pure non-hit-testing overlay rendering the synthetic human cursor arrow, drag press halo,
 * expanding click ripples, and transient keyboard shortcut badge directly inside a [ComposeWindow]
 * captured by Spectre ScreenCaptureKit.
 */
@Composable
fun HumanPhysicsCursorOverlay(
    state: HumanCursorState,
    window: ComposeWindow,
) {
    var frameNanos by rememberFrameClockNanos()
    val density = LocalDensity.current

    Box(modifier = Modifier.fillMaxSize()) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val origin = runCatching { window.contentPane.locationOnScreen }.getOrNull()
                ?: runCatching { window.locationOnScreen }.getOrNull()
                ?: return@Canvas
            val scale = density.density

            // 1. Expanding click ripples (440ms ease-out).
            val rippleDurationNs = 440_000_000.0
            for (ripple in state.ripples.toList()) {
                val elapsed = (frameNanos - ripple.startNanos).toDouble()
                if (elapsed < 0.0 || elapsed > rippleDurationNs) continue
                val t = (elapsed / rippleDurationNs).toFloat().coerceIn(0f, 1f)
                val eased = 1f - (1f - t) * (1f - t) * (1f - t)
                val cx = (ripple.screenX - origin.x) * scale
                val cy = (ripple.screenY - origin.y) * scale
                val baseColor = if (ripple.isDoubleClick) Color(0xFFFF9B42) else Color(0xFF4FB3D9)
                val radius = (5.dp.toPx()) + eased * (24.dp.toPx())
                val alpha = (1f - t) * 0.88f

                drawCircle(
                    color = baseColor.copy(alpha = alpha * 0.24f),
                    radius = radius,
                    center = Offset(cx, cy),
                )
                drawCircle(
                    color = baseColor.copy(alpha = alpha),
                    radius = radius,
                    center = Offset(cx, cy),
                    style = Stroke(width = (2.4.dp.toPx()) * (1f - t * 0.45f)),
                )
                if (ripple.isDoubleClick && t > 0.15f) {
                    val innerT = ((t - 0.15f) / 0.85f).coerceIn(0f, 1f)
                    val innerRadius = (3.dp.toPx()) + innerT * (16.dp.toPx())
                    drawCircle(
                        color = Color.White.copy(alpha = (1f - innerT) * 0.7f),
                        radius = innerRadius,
                        center = Offset(cx, cy),
                        style = Stroke(width = 1.6.dp.toPx()),
                    )
                }
            }

            // 2. Visible vector pointer cursor anchored accurately at tip (cx, cy).
            if (state.visible) {
                val cx = (state.screenX - origin.x) * scale
                val cy = (state.screenY - origin.y) * scale

                if (state.pressed) {
                    drawCircle(
                        color = Color(0xFF4FB3D9).copy(alpha = 0.32f),
                        radius = 12.dp.toPx(),
                        center = Offset(cx, cy),
                    )
                    drawCircle(
                        color = Color(0xFF4FB3D9).copy(alpha = 0.85f),
                        radius = 7.dp.toPx(),
                        center = Offset(cx, cy),
                        style = Stroke(width = 1.6.dp.toPx()),
                    )
                }

                val s = 1.15f * scale
                fun arrowPath(ox: Float, oy: Float): Path = Path().apply {
                    moveTo(ox + 0f * s, oy + 0f * s)
                    lineTo(ox + 0f * s, oy + 19.5f * s)
                    lineTo(ox + 4.8f * s, oy + 15.2f * s)
                    lineTo(ox + 8.3f * s, oy + 22.0f * s)
                    lineTo(ox + 11.4f * s, oy + 20.4f * s)
                    lineTo(ox + 7.9f * s, oy + 13.7f * s)
                    lineTo(ox + 14.0f * s, oy + 13.4f * s)
                    close()
                }

                // Soft drop shadow
                drawPath(
                    path = arrowPath(cx + 1.6f * scale, cy + 2.0f * scale),
                    color = Color.Black.copy(alpha = 0.38f),
                )
                // Crisp outer white halo
                val mainPath = arrowPath(cx, cy)
                drawPath(
                    path = mainPath,
                    color = Color.White,
                    style = Stroke(
                        width = 3.2f * scale,
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round,
                    ),
                )
                // Dark pointer core
                drawPath(
                    path = mainPath,
                    color = if (state.pressed) Color(0xFF15202B) else Color(0xFF0E1015),
                )
            }
        }

        val badge = state.shortcutBadge
        if (badge != null && frameNanos <= badge.expiresAtNanos) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 20.dp, bottom = 18.dp)
                    .background(Color(0xD9121620), RoundedCornerShape(8.dp))
                    .border(1.dp, Color(0x884FB3D9), RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text(
                    text = badge.label,
                    color = Color(0xFFF2F6FA),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

@Composable
private fun rememberFrameClockNanos(): androidx.compose.runtime.MutableLongState {
    val state = androidx.compose.runtime.remember { mutableLongStateOf(System.nanoTime()) }
    LaunchedEffect(Unit) {
        while (true) {
            withFrameNanos {
                state.longValue = System.nanoTime()
            }
        }
    }
    return state
}

/** Recorded timeline cue for writing exact action timestamps into `capture-manifest.md`. */
data class StudioCueEntry(
    val elapsedSec: Double,
    val label: String,
)

class StudioCueLog {
    private var startNanos: Long = System.nanoTime()
    private val entries = ArrayList<StudioCueEntry>()

    fun markRecordingStart() {
        startNanos = System.nanoTime()
        entries.clear()
    }

    fun cue(label: String) {
        val elapsed = ((System.nanoTime() - startNanos) / 1_000_000_000.0).coerceAtLeast(0.0)
        entries += StudioCueEntry(elapsedSec = elapsed, label = label)
        println(String.format(Locale.US, "[cue %6.2fs] %s", elapsed, label))
    }

    fun elapsedNowSec(): Double = ((System.nanoTime() - startNanos) / 1_000_000_000.0).coerceAtLeast(0.0)

    fun appendShifted(other: StudioCueLog, offsetSec: Double) {
        for (e in other.entries) {
            entries += StudioCueEntry(elapsedSec = offsetSec + e.elapsedSec, label = e.label)
        }
    }

    fun writeTsv(path: NioPath) {
        Files.createDirectories(path.parent)
        val sb = StringBuilder()
        sb.appendLine("elapsedSec\tlabel")
        for (e in entries) {
            sb.appendLine(String.format(Locale.US, "%.3f\t%s", e.elapsedSec, e.label.replace('\t', ' ')))
        }
        Files.writeString(path, sb.toString(), StandardCharsets.UTF_8)
    }
}

/**
 * Continuous 16ms cubic-bezier human-physics synthetic input driver.
 * Never teleports the pointer; every movement follows an organic curved cubic-bezier path with
 * minimum-jerk velocity profile and visible cursor + click ripples.
 */
class HumanPhysicsDriver(
    val automator: ComposeAutomator,
    val window: ComposeWindow,
    val input: SyntheticInput,
    val cursor: HumanCursorState,
) {
    private var curX: Float
    private var curY: Float
    private var arcToggle: Float = 1f

    init {
        val origin = runCatching { window.contentPane.locationOnScreen }.getOrNull() ?: window.locationOnScreen
        val w = window.contentPane.width.coerceAtLeast(400)
        val h = window.contentPane.height.coerceAtLeast(300)
        curX = origin.x + w * 0.5f
        curY = origin.y + h * 0.45f
        cursor.moveTo(curX, curY)
        input.mouseMove(curX.roundToInt(), curY.roundToInt())
    }

    fun currentPoint(): Point = Point(curX.roundToInt(), curY.roundToInt())

    fun windowScreenBounds(): Rectangle {
        val loc = window.locationOnScreen
        return Rectangle(loc.x, loc.y, window.width, window.height)
    }

    /** Moves smoothly along a cubic Bezier arc sampled every 16ms (never teleports). */
    suspend fun moveTo(
        target: Point,
        durationMs: Long? = null,
        curved: Boolean = true,
    ) {
        val sx = curX
        val sy = curY
        val ex = target.x.toFloat()
        val ey = target.y.toFloat()
        val dist = hypot((ex - sx).toDouble(), (ey - sy).toDouble()).toFloat()
        if (dist < 1.5f) {
            curX = ex
            curY = ey
            cursor.moveTo(curX, curY)
            input.mouseMove(target.x, target.y)
            return
        }

        val resolvedDurationMs = durationMs ?: (170L + (dist * 0.42f).toLong()).coerceIn(210L, 580L)
        val steps = max(8, (resolvedDurationMs / 16L).toInt())

        // Organic perpendicular control-point arc for natural hand motion (disabled on linear slider/ruler drags).
        val dx = ex - sx
        val dy = ey - sy
        val perpX = -dy / dist
        val perpY = dx / dist
        val arcMag = if (curved) (dist * 0.065f).coerceIn(4f, 26f) * arcToggle else 0f
        if (curved) arcToggle = -arcToggle

        val c1x = sx + dx * 0.30f + perpX * arcMag
        val c1y = sy + dy * 0.30f + perpY * arcMag
        val c2x = sx + dx * 0.72f + perpX * (arcMag * 0.65f)
        val c2y = sy + dy * 0.72f + perpY * (arcMag * 0.65f)

        for (step in 1..steps) {
            val rawT = step.toFloat() / steps.toFloat()
            // Minimum-jerk easing: 10t^3 - 15t^4 + 6t^5
            val u = rawT * rawT * rawT * (rawT * (rawT * 6f - 15f) + 10f)
            val inv = 1f - u
            val x = inv * inv * inv * sx +
                3f * inv * inv * u * c1x +
                3f * inv * u * u * c2x +
                u * u * u * ex
            val y = inv * inv * inv * sy +
                3f * inv * inv * u * c1y +
                3f * inv * u * u * c2y +
                u * u * u * ey
            curX = x
            curY = y
            cursor.moveTo(x, y)
            input.mouseMove(x.roundToInt(), y.roundToInt())
            delay(16L)
        }
        curX = ex
        curY = ey
        cursor.moveTo(ex, ey)
        input.mouseMove(target.x, target.y)
    }

    /** Moves smoothly to [target] and performs a human-paced click with visible click ripple. */
    suspend fun click(
        target: Point,
        moveDurationMs: Long? = null,
        postDelayMs: Long = 160L,
    ) {
        moveTo(target, durationMs = moveDurationMs, curved = true)
        delay(45L)
        cursor.pressed = true
        cursor.emitRipple(curX, curY, isDoubleClick = false)
        input.mousePress(InputEvent.BUTTON1_DOWN_MASK)
        delay(55L)
        input.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
        cursor.pressed = false
        delay(postDelayMs)
    }

    /** Moves smoothly to [target] and clicks while holding [modifiers] (e.g. `⌥ Click` on interactive specimens). */
    suspend fun clickWithModifiers(
        target: Point,
        modifiers: List<Int>,
        badgeLabel: String? = null,
        moveDurationMs: Long? = null,
        postDelayMs: Long = 180L,
    ) {
        moveTo(target, durationMs = moveDurationMs, curved = true)
        badgeLabel?.let { cursor.showShortcut(it) }
        delay(45L)
        modifiers.forEach(input::keyPress)
        try {
            delay(28L)
            cursor.pressed = true
            cursor.emitRipple(curX, curY, isDoubleClick = false)
            input.mousePress(InputEvent.BUTTON1_DOWN_MASK)
            delay(55L)
            input.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
            cursor.pressed = false
            delay(28L)
        } finally {
            modifiers.asReversed().forEach(input::keyRelease)
        }
        delay(postDelayMs)
    }

    /** Moves smoothly to [target] and performs a realistic double-click with double-ring ripple. */
    suspend fun doubleClick(
        target: Point,
        moveDurationMs: Long? = null,
        postDelayMs: Long = 220L,
    ) {
        moveTo(target, durationMs = moveDurationMs, curved = true)
        delay(45L)
        // Click 1
        cursor.pressed = true
        cursor.emitRipple(curX, curY, isDoubleClick = false)
        input.mousePress(InputEvent.BUTTON1_DOWN_MASK)
        delay(36L)
        input.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
        cursor.pressed = false
        delay(68L)
        // Click 2
        cursor.pressed = true
        cursor.emitRipple(curX, curY, isDoubleClick = true)
        input.mousePress(InputEvent.BUTTON1_DOWN_MASK)
        delay(40L)
        input.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
        cursor.pressed = false
        delay(postDelayMs)
    }

    /**
     * Smooth 16ms drag from [from] to [to] (with optional stationary [midPauseMs] and continuous
     * return/continuation leg [thenTo] before releasing the mouse button).
     */
    suspend fun drag(
        from: Point,
        to: Point,
        durationMs: Long = 460L,
        midPauseMs: Long = 0L,
        thenTo: Point? = null,
        thenDurationMs: Long = 420L,
        curved: Boolean = false,
        postDelayMs: Long = 180L,
    ) {
        moveTo(from, curved = true)
        delay(55L)
        cursor.pressed = true
        cursor.emitRipple(curX, curY, isDoubleClick = false)
        input.mousePress(InputEvent.BUTTON1_DOWN_MASK)
        try {
            delay(32L)
            moveTo(to, durationMs = durationMs, curved = curved)
            if (midPauseMs > 0L) {
                delay(midPauseMs)
            }
            if (thenTo != null) {
                moveTo(thenTo, durationMs = thenDurationMs, curved = curved)
            }
            delay(32L)
        } finally {
            input.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
            cursor.pressed = false
        }
        delay(postDelayMs)
    }

    /** Smooth multi-notch human scroll-wheel glide at [at]. */
    suspend fun wheelScroll(
        at: Point,
        notchesPerStep: Int,
        steps: Int,
        stepDelayMs: Long = 32L,
        postDelayMs: Long = 160L,
    ) {
        moveTo(at, curved = true)
        delay(40L)
        repeat(steps) {
            input.mouseWheel(notchesPerStep)
            delay(stepDelayMs)
        }
        delay(postDelayMs)
    }

    /**
     * Dispatches a synthetic keyboard shortcut while briefly displaying a crisp keycap badge
     * (suppressed automatically on `⌘Z`/`⇧⌘Z` where Studio already renders its top-center Undo/Redo toast).
     */
    suspend fun pressShortcut(
        label: String,
        keyCode: Int,
        modifiers: List<Int> = emptyList(),
        postDelayMs: Long = 260L,
        showBadge: Boolean = !(keyCode == KeyEvent.VK_Z && modifiers.contains(KeyEvent.VK_META)),
    ) {
        if (showBadge) {
            cursor.showShortcut(label)
        }
        delay(40L)
        modifiers.forEach(input::keyPress)
        try {
            delay(28L)
            input.keyPress(keyCode)
            delay(48L)
            input.keyRelease(keyCode)
            delay(28L)
        } finally {
            modifiers.asReversed().forEach(input::keyRelease)
        }
        delay(postDelayMs)
    }

    /** Types printable ASCII text character-by-character at human typing cadence. */
    suspend fun typeText(
        text: String,
        charDelayMs: Long = 52L,
        postDelayMs: Long = 180L,
    ) {
        for (ch in text) {
            val (keyCode, shift) = charToKeyEvent(ch) ?: continue
            if (shift) input.keyPress(KeyEvent.VK_SHIFT)
            try {
                input.keyPress(keyCode)
                delay(20L)
                input.keyRelease(keyCode)
            } finally {
                if (shift) input.keyRelease(KeyEvent.VK_SHIFT)
            }
            delay(charDelayMs)
        }
        delay(postDelayMs)
    }

    suspend fun waitForNode(tag: String, timeout: Duration = 6.seconds): AutomatorNode =
        automator.waitForNode(tag = tag, timeout = timeout)

    suspend fun clickNode(tag: String, timeout: Duration = 6.seconds, postDelayMs: Long = 180L): AutomatorNode {
        val node = waitForNode(tag, timeout)
        click(node.centerOnScreen, postDelayMs = postDelayMs)
        return node
    }

    /** Scrolls a vertical container smoothly via mouse wheel until [targetTag] is well inside view. */
    suspend fun scrollIntoView(
        scrollContainerTag: String,
        targetTag: String,
        maxSteps: Int = 60,
        scrollDownNotches: Int = 1,
    ): AutomatorNode {
        val container = waitForNode(scrollContainerTag)
        val cBounds = checkNotNull(container.boundsOnScreen) { "Container '$scrollContainerTag' has no bounds" }
        val scrollPt = Point(cBounds.x + cBounds.width / 2, cBounds.y + cBounds.height / 2)
        val winBounds = windowScreenBounds()
        val visTop = max(cBounds.y, winBounds.y) + 6
        val visBottom = min(cBounds.y + cBounds.height, winBounds.y + winBounds.height) - 6

        fun isVisiblyInRange(node: AutomatorNode?): Boolean {
            val b = node?.boundsOnScreen ?: return false
            val cy = b.y + b.height / 2
            return b.width > 2 && b.height > 2 && cy in visTop..visBottom
        }

        automator.findOneByTestTag(targetTag)?.takeIf(::isVisiblyInRange)?.let { return it }

        moveTo(scrollPt, curved = true)
        delay(24L)
        // First pass: scroll gently down (1 notch per step so virtualized short lists never jump over a row).
        // Off-screen clipped nodes can report 1x1 bounds at the window origin, so only scroll UP when the node
        // has real bounds positioned above visTop.
        repeat(maxSteps) {
            val current = automator.findOneByTestTag(targetTag)
            if (isVisiblyInRange(current)) return current!!
            val b = current?.boundsOnScreen
            val realBoundsAbove = b != null && b.width > 2 && b.height > 2 && (b.y + b.height / 2) < visTop
            val dir = if (realBoundsAbove) -abs(scrollDownNotches) else abs(scrollDownNotches)
            input.mouseWheel(dir)
            delay(28L)
        }
        automator.waitForIdle()
        automator.findOneByTestTag(targetTag)?.takeIf(::isVisiblyInRange)?.let { return it }
        // Second pass fallback: sweep gently upward.
        repeat(maxSteps) {
            val current = automator.findOneByTestTag(targetTag)
            if (isVisiblyInRange(current)) return current!!
            input.mouseWheel(-abs(scrollDownNotches))
            delay(28L)
        }
        automator.waitForIdle()
        return checkNotNull(automator.findOneByTestTag(targetTag)) {
            "Could not scroll '$targetTag' into view inside '$scrollContainerTag'"
        }
    }

    /** Scrolls a horizontal container via Shift+wheel (then plain wheel fallback) until [targetTag] is well inside view. */
    suspend fun scrollHorizontallyIntoView(
        scrollContainerTag: String,
        targetTag: String,
        maxSteps: Int = 36,
        scrollRightNotches: Int = 5,
    ): AutomatorNode {
        val container = waitForNode(scrollContainerTag)
        val cBounds = checkNotNull(container.boundsOnScreen) { "Container '$scrollContainerTag' has no bounds" }
        val scrollPt = Point(cBounds.x + cBounds.width / 2, cBounds.y + cBounds.height / 2)
        val winBounds = windowScreenBounds()
        val visLeft = max(cBounds.x, winBounds.x) + 12
        val visRight = min(cBounds.x + cBounds.width, winBounds.x + winBounds.width) - 12

        fun isVisiblyInRange(node: AutomatorNode?): Boolean {
            val b = node?.boundsOnScreen ?: return false
            return b.width > 2 && b.height > 2 && b.x >= visLeft && (b.x + b.width) <= visRight
        }

        automator.findOneByTestTag(targetTag)?.takeIf(::isVisiblyInRange)?.let { return it }

        moveTo(scrollPt, curved = true)
        delay(24L)
        // Compose Desktop horizontalScroll responds to Shift+wheel; try Shift first, then plain wheel fallback.
        for (useShift in listOf(true, false)) {
            if (useShift) input.keyPress(KeyEvent.VK_SHIFT)
            try {
                repeat(maxSteps) {
                    val current = automator.findOneByTestTag(targetTag)
                    if (isVisiblyInRange(current)) return current!!
                    val b = current?.boundsOnScreen
                    val realBoundsLeft = b != null && b.width > 2 && b.height > 2 && b.x < visLeft
                    val dir = if (realBoundsLeft) -abs(scrollRightNotches) else abs(scrollRightNotches)
                    input.mouseWheel(dir)
                    delay(20L)
                }
            } finally {
                if (useShift) input.keyRelease(KeyEvent.VK_SHIFT)
            }
            automator.waitForIdle()
            automator.findOneByTestTag(targetTag)?.takeIf(::isVisiblyInRange)?.let { return it }
        }
        val finalNode = checkNotNull(automator.findOneByTestTag(targetTag)) {
            "Could not horizontally scroll '$targetTag' into view inside '$scrollContainerTag'"
        }
        println(
            "[scrollHorizontallyIntoView] container=$cBounds vis=[$visLeft..$visRight] target=${finalNode.boundsOnScreen}",
        )
        return finalNode
    }

    fun sliderPointForValue(bounds: Rectangle, value: Float, min: Float, max: Float): Point {
        val frac = ((value - min) / (max - min)).coerceIn(0.03f, 0.97f)
        val inset = 10
        val usableW = (bounds.width - inset * 2).coerceAtLeast(1)
        return Point(
            bounds.x + inset + (usableW * frac).roundToInt(),
            bounds.y + bounds.height / 2,
        )
    }

    private fun charToKeyEvent(ch: Char): Pair<Int, Boolean>? = when (ch) {
        in 'a'..'z' -> (KeyEvent.VK_A + (ch - 'a')) to false
        in 'A'..'Z' -> (KeyEvent.VK_A + (ch - 'A')) to true
        in '0'..'9' -> (KeyEvent.VK_0 + (ch - '0')) to false
        ' ' -> KeyEvent.VK_SPACE to false
        '-' -> KeyEvent.VK_MINUS to false
        '.' -> KeyEvent.VK_PERIOD to false
        ',' -> KeyEvent.VK_COMMA to false
        '!' -> KeyEvent.VK_1 to true
        ':' -> KeyEvent.VK_SEMICOLON to true
        '/' -> KeyEvent.VK_SLASH to false
        '_' -> KeyEvent.VK_MINUS to true
        else -> null
    }
}

/**
 * Copies tracked `deck/src/desktopMain/resources/decks/` into a fresh isolated writable directory so
 * Studio dogfooding can exercise real file-backed editing via `-Dciacola.decks.dir` / `decksDir`
 * without ever touching tracked `.deck.json` files in git.
 */
fun createWritableDecksTempCopy(prefix: String = "ciacola-dogfood-decks-"): NioPath {
    val candidates = listOf(
        NioPath.of("src/desktopMain/resources/decks"),
        NioPath.of("deck/src/desktopMain/resources/decks"),
    )
    val sourceDir = candidates.firstOrNull { Files.isDirectory(it) }
        ?: error("Could not locate tracked resources/decks directory from ${NioPath.of("").toAbsolutePath()}")
    val destDir = Files.createTempDirectory(prefix)
    Files.walk(sourceDir).use { stream ->
        stream.forEach { src ->
            val rel = sourceDir.relativize(src)
            val target = destDir.resolve(rel)
            if (Files.isDirectory(src)) {
                Files.createDirectories(target)
            } else {
                Files.createDirectories(target.parent)
                Files.copy(src, target, StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }
    return destDir
}

/**
 * Records [window] natively via Spectre ScreenCaptureKit (`AutoRecorder`, `captureCursor = false`)
 * at [fps] and normalizes the output to exact CFR (`r_frame_rate == avg_frame_rate == fps/1`),
 * H.264 `yuv420p`, Lanczos-scaled to at most [targetWidth]x[targetHeight] with `+faststart`.
 */
suspend fun recordWindowMp4Cfr(
    window: ComposeWindow,
    outputMp4: NioPath,
    fps: Int = 60,
    targetWidth: Int = 1920,
    targetHeight: Int = 1080,
    cueLog: StudioCueLog? = null,
    block: suspend () -> Unit,
): Double {
    Files.createDirectories(outputMp4.parent)
    val rawMp4 = Files.createTempFile(outputMp4.parent, ".sck-raw-", ".mp4")
    val recorder = AutoRecorder()
    val options = RecordingOptions(frameRate = fps, captureCursor = false)

    val nudgeScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    val nudgePeriodMs = (1_000L / fps).coerceAtLeast(16L)
    val redrawNudgeJob = nudgeScope.launch {
        while (isActive) {
            EventQueue.invokeLater {
                if (window.isDisplayable && window.isShowing) {
                    window.repaint()
                    findSkiaLayer(window)?.needRender()
                }
            }
            delay(nudgePeriodMs)
        }
    }

    var blockError: Throwable? = null
    val caffeinateProc = runCatching {
        if (File("/usr/bin/caffeinate").canExecute()) {
            ProcessBuilder(
                "/usr/bin/caffeinate",
                "-u",
                "-d",
                "-w",
                ProcessHandle.current().pid().toString(),
            ).start()
        } else {
            null
        }
    }.getOrNull()
    if (caffeinateProc != null) {
        delay(250L)
    }
    try {
        val handle = withContext(Dispatchers.IO) {
            recorder.startWindow(
                window = window.asTitledWindow(),
                output = rawMp4,
                options = options,
            )
        }
        cueLog?.markRecordingStart()
        try {
            block()
        } catch (t: Throwable) {
            blockError = t
        } finally {
            withContext(Dispatchers.IO) {
                runCatching { handle.stop() }
            }
        }
    } finally {
        runCatching { caffeinateProc?.destroy() }
        redrawNudgeJob.cancel()
        redrawNudgeJob.join()
        nudgeScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    if (blockError != null) {
        runCatching { Files.deleteIfExists(rawMp4) }
        throw blockError
    }

    check(Files.isRegularFile(rawMp4) && Files.size(rawMp4) > 0L) {
        "Spectre SCK produced an empty raw recording: $rawMp4"
    }

    val filter = "scale=$targetWidth:$targetHeight:force_original_aspect_ratio=decrease:flags=lanczos," +
        "pad=$targetWidth:$targetHeight:(ow-iw)/2:(oh-ih)/2:color=0x0E1015"
    withContext(Dispatchers.IO) {
        try {
            runDogfoodFfmpeg(
                "-y",
                "-i", rawMp4.toAbsolutePath().toString(),
                "-vf", filter,
                "-fps_mode", "cfr",
                "-r", fps.toString(),
                "-c:v", "libx264",
                "-preset", "fast",
                "-crf", "18",
                "-pix_fmt", "yuv420p",
                "-movflags", "+faststart",
                outputMp4.toAbsolutePath().toString(),
            )
        } finally {
            runCatching { Files.deleteIfExists(rawMp4) }
        }
    }
    check(Files.isRegularFile(outputMp4) && Files.size(outputMp4) > 0L) {
        "CFR-normalized MP4 missing or empty: $outputMp4"
    }
    return withContext(Dispatchers.IO) { probeMp4DurationSec(outputMp4) }
}

private fun probeMp4DurationSec(mp4: NioPath): Double {
    val ffprobe = resolveDogfoodBinary("FFPROBE_BIN", "ffprobe")
    val cmd = listOf(
        ffprobe,
        "-v", "error",
        "-show_entries", "format=duration",
        "-of", "default=noprint_wrappers=1:nokey=1",
        mp4.toAbsolutePath().toString(),
    )
    val proc = ProcessBuilder(cmd).redirectErrorStream(true).start()
    val out = proc.inputStream.bufferedReader(StandardCharsets.UTF_8).readText().trim()
    val exit = proc.waitFor()
    check(exit == 0) { "ffprobe failed ($exit) on $mp4: $out" }
    return out.toDoubleOrNull() ?: error("Could not parse ffprobe duration '$out' for $mp4")
}

/**
 * Concatenates multiple same-resolution CFR MP4 segments (e.g. Story window + Presenter window)
 * into a single exact CFR H.264 `yuv420p` MP4.
 */
suspend fun concatMp4SegmentsCfr(
    segments: List<NioPath>,
    outputMp4: NioPath,
    fps: Int = 60,
) {
    require(segments.isNotEmpty()) { "At least one MP4 segment is required" }
    Files.createDirectories(outputMp4.parent)
    val listFile = Files.createTempFile(outputMp4.parent, ".concat-list-", ".txt")
    val content = segments.joinToString("\n") { seg ->
        "file '${seg.toAbsolutePath().toString().replace("'", "'\\''")}'"
    }
    Files.writeString(listFile, content, StandardCharsets.UTF_8)
    withContext(Dispatchers.IO) {
        try {
            runDogfoodFfmpeg(
                "-y",
                "-f", "concat",
                "-safe", "0",
                "-i", listFile.toAbsolutePath().toString(),
                "-fps_mode", "cfr",
                "-r", fps.toString(),
                "-c:v", "libx264",
                "-preset", "fast",
                "-crf", "18",
                "-pix_fmt", "yuv420p",
                "-movflags", "+faststart",
                outputMp4.toAbsolutePath().toString(),
            )
        } finally {
            runCatching { Files.deleteIfExists(listFile) }
        }
    }
}

private fun resolveDogfoodBinary(envVar: String, binaryName: String): String {
    System.getenv(envVar)?.takeIf { it.isNotBlank() && File(it).canExecute() }?.let { return it }
    val candidates = buildList {
        System.getenv("PATH")
            ?.split(File.pathSeparator)
            ?.filter { it.isNotBlank() }
            ?.forEach { add(File(it, binaryName).absolutePath) }
        add("/opt/homebrew/bin/$binaryName")
        add("/usr/local/bin/$binaryName")
        add("/usr/bin/$binaryName")
    }
    return candidates.firstOrNull { File(it).canExecute() } ?: binaryName
}

private fun resolveDogfoodFfmpeg(): String = resolveDogfoodBinary("FFMPEG_BIN", "ffmpeg")

private fun runDogfoodFfmpeg(vararg args: String) {
    val cmd = buildList {
        add(resolveDogfoodFfmpeg())
        add("-hide_banner")
        add("-loglevel")
        add("error")
        addAll(args)
    }
    val proc = ProcessBuilder(cmd).redirectErrorStream(true).start()
    val output = proc.inputStream.bufferedReader(StandardCharsets.UTF_8).readText()
    val exit = proc.waitFor()
    check(exit == 0) {
        "ffmpeg exited with code $exit for ${cmd.joinToString(" ")}:\n$output"
    }
}
