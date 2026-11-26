package dev.sebastiano.ciacola.presenter

import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.window.WindowPlacement
import java.awt.Dimension
import java.awt.EventQueue
import java.awt.GraphicsDevice
import java.awt.GraphicsEnvironment
import java.awt.HeadlessException
import java.awt.Rectangle
import java.awt.Window as AwtWindow
import javax.swing.Timer

/** Connected physical/logical display metadata from AWT [GraphicsEnvironment]. */
data class DisplayScreenInfo(
    val id: String,
    val label: String,
    val bounds: Rectangle,
    val isPrimary: Boolean,
    val device: GraphicsDevice? = null,
)

/** Abstracts connected display discovery and window routing for testability. */
interface DisplayRoutingAdapter {
    fun screens(): List<DisplayScreenInfo>
    fun presentStoryOn(screen: DisplayScreenInfo): Boolean
    fun swapAudienceAndPresenter(): Boolean
}

internal interface DisplayWindowTarget {
    val key: Any
    val title: String
    val isShowing: Boolean
    val isDisplayable: Boolean
    var placement: WindowPlacement
    val bounds: Rectangle
    val minimumSize: Dimension?
    val screenDevice: GraphicsDevice?
    fun setBounds(x: Int, y: Int, width: Int, height: Int)
}

internal fun interface ScheduledHandle {
    fun cancel()
}

internal interface DisplayTransitionScheduler {
    fun runOnEdt(block: () -> Unit)
    fun scheduleOnce(delayMs: Int, block: () -> Unit): ScheduledHandle
}

internal data class DisplayTransitionTimings(
    val exitPollIntervalMs: Int = 40,
    val exitTimeoutMs: Long = 2_000L,
    val postExitSettleMs: Int = 120,
    val relocateSettleMs: Int = 150,
)

private class ComposeDisplayWindowTarget(
    private val window: ComposeWindow,
) : DisplayWindowTarget {
    override val key: Any get() = window
    override val title: String get() = window.title
    override val isShowing: Boolean get() = window.isShowing
    override val isDisplayable: Boolean get() = window.isDisplayable
    override var placement: WindowPlacement
        get() = window.placement
        set(value) {
            window.placement = value
        }
    override val bounds: Rectangle get() = window.bounds
    override val minimumSize: Dimension? get() = window.minimumSize
    override val screenDevice: GraphicsDevice? get() = window.graphicsConfiguration?.device

    override fun setBounds(x: Int, y: Int, width: Int, height: Int) {
        window.setBounds(x, y, width, height)
    }
}

private object SwingEdtTransitionScheduler : DisplayTransitionScheduler {
    override fun runOnEdt(block: () -> Unit) {
        if (EventQueue.isDispatchThread()) {
            block()
        } else {
            EventQueue.invokeLater(block)
        }
    }

    override fun scheduleOnce(delayMs: Int, block: () -> Unit): ScheduledHandle {
        val timer = Timer(delayMs.coerceAtLeast(0)) { block() }.apply {
            isRepeats = false
            start()
        }
        return ScheduledHandle { timer.stop() }
    }
}

private fun isMacOsPlatform(): Boolean =
    System.getProperty("os.name").orEmpty().contains("mac", ignoreCase = true)

private fun defaultAwtScreens(): List<DisplayScreenInfo> = try {
    val env = GraphicsEnvironment.getLocalGraphicsEnvironment()
    val primary = runCatching { env.defaultScreenDevice }.getOrNull()
    env.screenDevices.mapIndexed { index, device ->
        val bounds = device.defaultConfiguration.bounds
        val isPrimary = device === primary
        val suffix = if (isPrimary) " · Primary" else ""
        DisplayScreenInfo(
            id = device.iDstring ?: "display-$index",
            label = "Display ${index + 1} (${bounds.width}×${bounds.height}$suffix)",
            bounds = bounds,
            isPrimary = isPrimary,
            device = device,
        )
    }
} catch (_: HeadlessException) {
    emptyList()
}

/**
 * Default AWT/Swing display router backed by [GraphicsEnvironment] and open [ComposeWindow]s.
 *
 * On macOS, exiting `WindowPlacement.Fullscreen` triggers an asynchronous Cocoa Space transition
 * (~700-1000 ms). Relocating and re-entering fullscreen must wait until the window leaves the old
 * fullscreen bounds and settles, rather than running on an immediate nested `EventQueue.invokeLater`.
 */
class AwtDisplayRouter internal constructor(
    private val targetLister: () -> List<DisplayWindowTarget>,
    private val screensProvider: () -> List<DisplayScreenInfo>,
    private val scheduler: DisplayTransitionScheduler,
    private val clockMillis: () -> Long,
    private val isMacOs: Boolean,
    private val timings: DisplayTransitionTimings = DisplayTransitionTimings(),
) : DisplayRoutingAdapter {

    constructor(
        windowLister: () -> List<ComposeWindow> = {
            runCatching {
                AwtWindow.getWindows().filterIsInstance<ComposeWindow>().filter { it.isShowing }
            }.getOrDefault(emptyList())
        },
    ) : this(
        targetLister = { windowLister().map(::ComposeDisplayWindowTarget) },
        screensProvider = ::defaultAwtScreens,
        scheduler = SwingEdtTransitionScheduler,
        clockMillis = System::currentTimeMillis,
        isMacOs = isMacOsPlatform(),
    )

    private class PendingTransition(
        val targetBounds: Rectangle,
        val targetFullscreen: Boolean,
        var scheduledHandle: ScheduledHandle? = null,
        var cancelled: Boolean = false,
    ) {
        fun cancel() {
            cancelled = true
            scheduledHandle?.cancel()
            scheduledHandle = null
        }
    }

    private data class ActiveExitState(
        val sourceBounds: Rectangle,
        val startedAtMs: Long,
        var postExitSettleDeadlineMs: Long? = null,
    )

    // Accessed only on the EDT via scheduler.runOnEdt / Swing Timer callbacks.
    private val pendingTransitions = mutableMapOf<Any, PendingTransition>()
    private val activeExits = mutableMapOf<Any, ActiveExitState>()

    override fun screens(): List<DisplayScreenInfo> = screensProvider()

    override fun presentStoryOn(screen: DisplayScreenInfo): Boolean {
        val storyWin = findStoryWindow() ?: return false
        val targetBounds = Rectangle(screen.bounds)
        scheduler.runOnEdt {
            val resolvedStory = findStoryWindow() ?: storyWin
            moveAndFullscreen(resolvedStory, targetBounds)
        }
        return true
    }

    override fun swapAudienceAndPresenter(): Boolean {
        if (screens().size < 2) return false
        if (findStoryWindow() == null || findPresenterWindow() == null) return false

        scheduler.runOnEdt {
            val allScreens = screens()
            if (allScreens.size < 2) return@runOnEdt
            val storyWin = findStoryWindow() ?: return@runOnEdt
            val presenterWin = findPresenterWindow() ?: return@runOnEdt

            val storyPending = pendingTransitions[storyWin.key]
            val presenterPending = pendingTransitions[presenterWin.key]

            val currentStoryScreen = storyPending?.let { screenForBounds(it.targetBounds, allScreens) }
                ?: screenForWindow(storyWin, allScreens)
                ?: allScreens.first()
            val currentPresenterScreen = (presenterPending?.let { screenForBounds(it.targetBounds, allScreens) }
                ?: screenForWindow(presenterWin, allScreens))
                ?.takeIf { it.id != currentStoryScreen.id }
                ?: allScreens.first { it.id != currentStoryScreen.id }

            val storyWasFullscreen = storyPending?.targetFullscreen
                ?: (storyWin.placement == WindowPlacement.Fullscreen)

            moveWindowToBounds(presenterWin, Rectangle(currentStoryScreen.bounds), fullscreen = false)
            if (storyWasFullscreen) {
                moveAndFullscreen(storyWin, Rectangle(currentPresenterScreen.bounds))
            } else {
                moveWindowToBounds(storyWin, Rectangle(currentPresenterScreen.bounds), fullscreen = false)
            }
        }
        return true
    }

    private fun findStoryWindow(): DisplayWindowTarget? =
        targetLister().firstOrNull { it.title.endsWith(" - Story") }

    private fun findPresenterWindow(): DisplayWindowTarget? =
        targetLister().firstOrNull { it.title.endsWith(" - Presenter") }

    private fun screenForWindow(window: DisplayWindowTarget, screens: List<DisplayScreenInfo>): DisplayScreenInfo? {
        val device = window.screenDevice
        if (device != null) {
            screens.firstOrNull { it.device === device }?.let { return it }
        }
        return screenForBounds(window.bounds, screens)
    }

    private fun screenForBounds(bounds: Rectangle, screens: List<DisplayScreenInfo>): DisplayScreenInfo? {
        if (bounds.width <= 0 || bounds.height <= 0) return null
        val centerX = bounds.x + bounds.width / 2
        val centerY = bounds.y + bounds.height / 2
        screens.firstOrNull { it.bounds.contains(centerX, centerY) }?.let { return it }
        return screens.maxByOrNull { screen ->
            val intersection = screen.bounds.intersection(bounds)
            if (intersection.isEmpty) 0L else intersection.width.toLong() * intersection.height.toLong()
        }?.takeIf { !it.bounds.intersection(bounds).isEmpty }
    }

    private fun moveAndFullscreen(window: DisplayWindowTarget, targetBounds: Rectangle) {
        moveWindowToBounds(window, targetBounds, fullscreen = true)
    }

    private fun moveWindowToBounds(window: DisplayWindowTarget, targetBounds: Rectangle, fullscreen: Boolean) {
        pendingTransitions.remove(window.key)?.cancel()

        if (!isWindowAlive(window)) {
            activeExits.remove(window.key)
            return
        }

        val existingExit = activeExits[window.key]
        if (fullscreen &&
            existingExit == null &&
            window.placement == WindowPlacement.Fullscreen &&
            isBoundsFillingDisplay(window.bounds, targetBounds)
        ) {
            return
        }

        if (existingExit == null) {
            val currentPlacement = window.placement
            if (currentPlacement == WindowPlacement.Fullscreen) {
                // Capture source bounds before exiting; macOS keeps placement == Fullscreen while animating.
                val oldDisplayBounds = screenForWindow(window, screensProvider())?.bounds
                    ?: window.bounds.takeIf { it.width > 0 && it.height > 0 }
                if (oldDisplayBounds != null) {
                    activeExits[window.key] = ActiveExitState(
                        sourceBounds = Rectangle(oldDisplayBounds),
                        startedAtMs = clockMillis(),
                    )
                }
                window.placement = WindowPlacement.Floating
            } else if (currentPlacement != WindowPlacement.Floating) {
                window.placement = WindowPlacement.Floating
                if (isMacOs && timings.postExitSettleMs > 0) {
                    activeExits[window.key] = ActiveExitState(
                        sourceBounds = Rectangle(),
                        startedAtMs = clockMillis(),
                        postExitSettleDeadlineMs = clockMillis() + timings.postExitSettleMs,
                    )
                }
            }
        }

        val exitState = activeExits[window.key]
        if (exitState != null) {
            val transition = PendingTransition(
                targetBounds = Rectangle(targetBounds),
                targetFullscreen = fullscreen,
            )
            pendingTransitions[window.key] = transition
            continuePendingExit(window, exitState, transition)
            return
        }

        relocateAndMaybeFullscreen(window, targetBounds, fullscreen, existingTransition = null)
    }

    private fun continuePendingExit(
        window: DisplayWindowTarget,
        exitState: ActiveExitState,
        transition: PendingTransition,
    ) {
        if (!isFullscreenOrFillingOldDisplay(window, exitState.sourceBounds)) {
            schedulePostExitSettleAndRelocate(window, exitState, transition)
        } else {
            pollExitThenRelocate(window, exitState, transition)
        }
    }

    private fun schedulePostExitSettleAndRelocate(
        window: DisplayWindowTarget,
        exitState: ActiveExitState,
        transition: PendingTransition,
    ) {
        if (!isMacOs || timings.postExitSettleMs <= 0) {
            activeExits.remove(window.key)
            relocateAndMaybeFullscreen(window, transition.targetBounds, transition.targetFullscreen, transition)
            return
        }

        val now = clockMillis()
        val deadline = exitState.postExitSettleDeadlineMs ?: (now + timings.postExitSettleMs).also {
            exitState.postExitSettleDeadlineMs = it
        }
        val remainingMs = (deadline - now).coerceAtLeast(0L).toInt()
        if (remainingMs == 0) {
            activeExits.remove(window.key)
            relocateAndMaybeFullscreen(window, transition.targetBounds, transition.targetFullscreen, transition)
            return
        }

        transition.scheduledHandle = scheduler.scheduleOnce(remainingMs) {
            if (transition.cancelled) return@scheduleOnce
            if (!isWindowAlive(window)) {
                cleanupDeadWindow(window.key, transition)
                return@scheduleOnce
            }
            activeExits.remove(window.key)
            relocateAndMaybeFullscreen(window, transition.targetBounds, transition.targetFullscreen, transition)
        }
    }

    private fun pollExitThenRelocate(
        window: DisplayWindowTarget,
        exitState: ActiveExitState,
        transition: PendingTransition,
    ) {
        transition.scheduledHandle = scheduler.scheduleOnce(timings.exitPollIntervalMs) {
            if (transition.cancelled) return@scheduleOnce
            if (!isWindowAlive(window)) {
                cleanupDeadWindow(window.key, transition)
                return@scheduleOnce
            }

            if (!isFullscreenOrFillingOldDisplay(window, exitState.sourceBounds)) {
                schedulePostExitSettleAndRelocate(window, exitState, transition)
                return@scheduleOnce
            }

            val elapsedMs = (clockMillis() - exitState.startedAtMs).coerceAtLeast(0L)
            if (elapsedMs >= timings.exitTimeoutMs) {
                activeExits.remove(window.key)
                if (window.placement == WindowPlacement.Fullscreen) {
                    cleanupWindow(window.key, transition)
                } else {
                    relocateAndMaybeFullscreen(
                        window,
                        transition.targetBounds,
                        transition.targetFullscreen,
                        transition,
                    )
                }
                return@scheduleOnce
            }

            pollExitThenRelocate(window, exitState, transition)
        }
    }

    private fun relocateAndMaybeFullscreen(
        window: DisplayWindowTarget,
        targetBounds: Rectangle,
        fullscreen: Boolean,
        existingTransition: PendingTransition?,
    ) {
        if (!isWindowAlive(window)) {
            cleanupDeadWindow(window.key, existingTransition)
            return
        }

        relocateCentered(window, targetBounds)
        if (!fullscreen) {
            cleanupWindow(window.key, existingTransition)
            return
        }

        val transition = existingTransition ?: PendingTransition(
            targetBounds = Rectangle(targetBounds),
            targetFullscreen = true,
        ).also { pendingTransitions[window.key] = it }

        scheduleFullscreenAfterRelocate(window, targetBounds, transition, relocateRetryCount = 0)
    }

    private fun scheduleFullscreenAfterRelocate(
        window: DisplayWindowTarget,
        targetBounds: Rectangle,
        transition: PendingTransition,
        relocateRetryCount: Int,
    ) {
        transition.scheduledHandle = scheduler.scheduleOnce(timings.relocateSettleMs) {
            if (transition.cancelled) return@scheduleOnce
            if (!isWindowAlive(window)) {
                cleanupDeadWindow(window.key, transition)
                return@scheduleOnce
            }

            val bounds = window.bounds
            val centerX = bounds.x + bounds.width / 2
            val centerY = bounds.y + bounds.height / 2
            if (!targetBounds.contains(centerX, centerY) && relocateRetryCount < 1) {
                relocateCentered(window, targetBounds)
                scheduleFullscreenAfterRelocate(window, targetBounds, transition, relocateRetryCount + 1)
                return@scheduleOnce
            }

            window.placement = WindowPlacement.Fullscreen
            cleanupWindow(window.key, transition)
        }
    }

    private fun relocateCentered(window: DisplayWindowTarget, targetBounds: Rectangle) {
        val minSize = window.minimumSize
        val minW = (minSize?.width ?: 640).coerceAtLeast(320)
        val minH = (minSize?.height ?: 420).coerceAtLeast(240)
        val currentBounds = window.bounds
        val w = currentBounds.width.coerceIn(minW, targetBounds.width.coerceAtLeast(minW))
        val h = currentBounds.height.coerceIn(minH, targetBounds.height.coerceAtLeast(minH))
        val x = targetBounds.x + ((targetBounds.width - w) / 2).coerceAtLeast(0)
        val y = targetBounds.y + ((targetBounds.height - h) / 2).coerceAtLeast(0)
        window.setBounds(x, y, w, h)
    }

    private fun isWindowAlive(window: DisplayWindowTarget): Boolean =
        window.isShowing && window.isDisplayable

    private fun isFullscreenOrFillingOldDisplay(window: DisplayWindowTarget, oldDisplayBounds: Rectangle): Boolean =
        window.placement == WindowPlacement.Fullscreen || isBoundsFillingDisplay(window.bounds, oldDisplayBounds)

    private fun cleanupWindow(key: Any, transition: PendingTransition?) {
        if (transition != null) {
            if (pendingTransitions[key] === transition) {
                pendingTransitions.remove(key)
            }
            transition.scheduledHandle = null
        } else {
            pendingTransitions.remove(key)?.cancel()
        }
    }

    private fun cleanupDeadWindow(key: Any, transition: PendingTransition?) {
        cleanupWindow(key, transition)
        activeExits.remove(key)
    }

    companion object {
        internal fun isBoundsFillingDisplay(windowBounds: Rectangle, displayBounds: Rectangle): Boolean {
            if (windowBounds.width <= 0 || windowBounds.height <= 0 ||
                displayBounds.width <= 0 || displayBounds.height <= 0
            ) {
                return false
            }
            val centerX = windowBounds.x + windowBounds.width / 2
            val centerY = windowBounds.y + windowBounds.height / 2
            if (!displayBounds.contains(centerX, centerY)) return false

            val minFillWidth = (displayBounds.width * 0.98).toInt()
            val minFillHeight = (displayBounds.height * 0.90).toInt()
            return windowBounds.width >= minFillWidth && windowBounds.height >= minFillHeight
        }
    }
}
