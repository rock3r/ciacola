package dev.sebastiano.ciacola.presenter

import androidx.compose.ui.window.WindowPlacement
import java.awt.Dimension
import java.awt.GraphicsDevice
import java.awt.Rectangle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AwtDisplayRouterTest {

    private class FakeWindowTarget(
        override val title: String,
        initialBounds: Rectangle,
        initialPlacement: WindowPlacement = WindowPlacement.Floating,
        override val minimumSize: Dimension? = Dimension(640, 420),
    ) : DisplayWindowTarget {
        override val key: Any = this
        override var isShowing: Boolean = true
        override var isDisplayable: Boolean = true
        override val screenDevice: GraphicsDevice? = null

        private var _placement: WindowPlacement = initialPlacement
        private var _bounds: Rectangle = Rectangle(initialBounds)

        /** Optional macOS-style fullscreen exit latency in ms after [placement] switches to Floating. */
        var simulatedFullscreenExitDurationMs: Long = 0L

        /** Simulates macOS reporting `placement == Fullscreen` until the Space exit animation completes. */
        var keepPlacementFullscreenDuringExit: Boolean = true

        /** Optional number of initial [setBounds] calls to ignore (e.g. window-manager clamping). */
        var clampSetBoundsCount: Int = 0

        var fullscreenEnteredOnBounds: Rectangle? = null
        val setBoundsCalls = mutableListOf<Rectangle>()
        val placementChanges = mutableListOf<WindowPlacement>()

        private var floatingRestoreBounds: Rectangle = Rectangle(120, 80, 1280, 720)
        private var exitStartedAtMs: Long? = null
        var nowMsProvider: () -> Long = { 0L }

        fun setFloatingRestoreBounds(bounds: Rectangle) {
            floatingRestoreBounds = Rectangle(bounds)
        }

        private fun syncAsyncExitIfComplete() {
            val exitStart = exitStartedAtMs ?: return
            if ((nowMsProvider() - exitStart) >= simulatedFullscreenExitDurationMs) {
                _placement = WindowPlacement.Floating
                _bounds = Rectangle(floatingRestoreBounds)
                exitStartedAtMs = null
            }
        }

        override var placement: WindowPlacement
            get() {
                syncAsyncExitIfComplete()
                return _placement
            }
            set(value) {
                syncAsyncExitIfComplete()
                val previous = _placement
                placementChanges += value
                if (previous == WindowPlacement.Fullscreen && value == WindowPlacement.Floating) {
                    if (simulatedFullscreenExitDurationMs > 0L) {
                        exitStartedAtMs = nowMsProvider()
                        if (!keepPlacementFullscreenDuringExit) {
                            _placement = WindowPlacement.Floating
                        }
                    } else {
                        _placement = WindowPlacement.Floating
                        _bounds = Rectangle(floatingRestoreBounds)
                        exitStartedAtMs = null
                    }
                } else {
                    _placement = value
                    if (value == WindowPlacement.Fullscreen) {
                        exitStartedAtMs = null
                        fullscreenEnteredOnBounds = Rectangle(_bounds)
                    } else if (value == WindowPlacement.Floating && previous == WindowPlacement.Maximized) {
                        _bounds = Rectangle(floatingRestoreBounds)
                    }
                }
            }

        override val bounds: Rectangle
            get() {
                syncAsyncExitIfComplete()
                return Rectangle(_bounds)
            }

        override fun setBounds(x: Int, y: Int, width: Int, height: Int) {
            syncAsyncExitIfComplete()
            val next = Rectangle(x, y, width, height)
            setBoundsCalls += next
            if (clampSetBoundsCount > 0) {
                clampSetBoundsCount--
                return
            }
            // Simulate Cocoa clamping setBounds while a Space exit animation is still active or Maximized/Fullscreen.
            if (exitStartedAtMs == null && _placement == WindowPlacement.Floating) {
                _bounds = next
                floatingRestoreBounds = Rectangle(next)
            }
        }
    }

    private class VirtualTransitionScheduler : DisplayTransitionScheduler {
        private data class ScheduledTask(
            val runAtMs: Long,
            val order: Long,
            val block: () -> Unit,
            var cancelled: Boolean = false,
        )

        var nowMs: Long = 0L
            private set
        private var nextOrder = 0L
        private val tasks = mutableListOf<ScheduledTask>()

        val activeTaskCount: Int
            get() = tasks.count { !it.cancelled }

        override fun runOnEdt(block: () -> Unit) {
            block()
        }

        override fun scheduleOnce(delayMs: Int, block: () -> Unit): ScheduledHandle {
            val task = ScheduledTask(
                runAtMs = nowMs + delayMs.coerceAtLeast(0),
                order = nextOrder++,
                block = block,
            )
            tasks += task
            return ScheduledHandle { task.cancelled = true }
        }

        fun advanceBy(deltaMs: Long) {
            val targetMs = nowMs + deltaMs.coerceAtLeast(0L)
            while (true) {
                val next = tasks
                    .filter { !it.cancelled && it.runAtMs <= targetMs }
                    .minWithOrNull(compareBy<ScheduledTask> { it.runAtMs }.thenBy { it.order })
                    ?: break
                nowMs = next.runAtMs
                tasks.remove(next)
                next.block()
            }
            nowMs = targetMs
        }

        fun drain(maxAdvanceMs: Long = 10_000L) {
            val limit = nowMs + maxAdvanceMs
            while (true) {
                val next = tasks
                    .filter { !it.cancelled && it.runAtMs <= limit }
                    .minWithOrNull(compareBy<ScheduledTask> { it.runAtMs }.thenBy { it.order })
                    ?: break
                nowMs = next.runAtMs
                tasks.remove(next)
                next.block()
            }
        }
    }

    private val display1 = DisplayScreenInfo("d1", "Display 1 (1920×1080 · Primary)", Rectangle(0, 0, 1920, 1080), true)
    private val display2 = DisplayScreenInfo("d2", "Display 2 (2560×1440)", Rectangle(1920, 0, 2560, 1440), false)

    private fun createRouter(
        windows: List<FakeWindowTarget>,
        screens: List<DisplayScreenInfo> = listOf(display1, display2),
        scheduler: VirtualTransitionScheduler = VirtualTransitionScheduler(),
        isMacOs: Boolean = true,
        timings: DisplayTransitionTimings = DisplayTransitionTimings(),
    ): AwtDisplayRouter {
        windows.forEach { it.nowMsProvider = { scheduler.nowMs } }
        return AwtDisplayRouter(
            targetLister = { windows.filter { it.isShowing } },
            screensProvider = { screens },
            scheduler = scheduler,
            clockMillis = { scheduler.nowMs },
            isMacOs = isMacOs,
            timings = timings,
        )
    }

    @Test
    fun presentStoryOnFloatingWindowRelocatesSettlesAndEntersFullscreen() {
        val scheduler = VirtualTransitionScheduler()
        val story = FakeWindowTarget("Talk - Story", Rectangle(100, 80, 1280, 720))
        val router = createRouter(listOf(story), scheduler = scheduler)

        assertTrue(router.presentStoryOn(display2))

        assertEquals(WindowPlacement.Floating, story.placement)
        assertEquals(Rectangle(2560, 360, 1280, 720), story.bounds)

        scheduler.advanceBy(149L)
        assertEquals(WindowPlacement.Floating, story.placement)

        scheduler.advanceBy(1L)
        assertEquals(WindowPlacement.Fullscreen, story.placement)
        assertEquals(Rectangle(2560, 360, 1280, 720), story.fullscreenEnteredOnBounds)
        assertEquals(0, scheduler.activeTaskCount)
    }

    @Test
    fun swapAudienceAndPresenterWaitsForMacOsFullscreenExitBeforeRelocatingStory() {
        val scheduler = VirtualTransitionScheduler()
        val story = FakeWindowTarget("Talk - Story", display2.bounds, WindowPlacement.Fullscreen).apply {
            simulatedFullscreenExitDurationMs = 800L
        }
        val presenter = FakeWindowTarget(
            title = "Ciacola - Presenter",
            initialBounds = Rectangle(200, 140, 1120, 680),
            minimumSize = Dimension(760, 500),
        )
        val router = createRouter(listOf(story, presenter), scheduler = scheduler)

        assertTrue(router.swapAudienceAndPresenter())

        // Presenter moves to Display 2 immediately; Story has begun exiting Fullscreen on Display 2.
        assertTrue(display2.bounds.contains(presenter.bounds.x + presenter.bounds.width / 2, presenter.bounds.y + presenter.bounds.height / 2))
        assertEquals(WindowPlacement.Floating, presenter.placement)
        assertEquals(WindowPlacement.Fullscreen, story.placement)
        assertTrue(story.setBoundsCalls.isEmpty(), "Story must not relocate while macOS fullscreen Space exit is in progress")

        // Still mid-transition at 760ms -> must not relocate or enter fullscreen yet.
        scheduler.advanceBy(760L)
        assertTrue(story.setBoundsCalls.isEmpty(), "Story must stay unmoved until fullscreen exit completes")
        assertEquals(WindowPlacement.Fullscreen, story.placement)

        // At 800ms exit completes -> Floating, then postExitSettleMs (120ms) runs before relocating to Display 1.
        scheduler.advanceBy(40L)
        assertEquals(WindowPlacement.Floating, story.placement)
        assertTrue(story.setBoundsCalls.isEmpty(), "Must allow post-exit settle after leaving fullscreen bounds")

        scheduler.advanceBy(120L)
        assertEquals(1, story.setBoundsCalls.size)
        assertTrue(display1.bounds.contains(story.bounds.x + story.bounds.width / 2, story.bounds.y + story.bounds.height / 2))
        assertEquals(WindowPlacement.Floating, story.placement)

        // After relocateSettleMs (150ms), Story enters Fullscreen on Display 1.
        scheduler.advanceBy(150L)
        assertEquals(WindowPlacement.Fullscreen, story.placement)
        val entered = story.fullscreenEnteredOnBounds!!
        assertTrue(display1.bounds.contains(entered.x + entered.width / 2, entered.y + entered.height / 2))
        assertEquals(0, scheduler.activeTaskCount)
    }

    @Test
    fun swapWhenBothWindowsStartFullscreenExitsBothCleanlyAndKeepsPresenterFloating() {
        val scheduler = VirtualTransitionScheduler()
        val story = FakeWindowTarget("Talk - Story", display1.bounds, WindowPlacement.Fullscreen).apply {
            simulatedFullscreenExitDurationMs = 750L
        }
        val presenter = FakeWindowTarget(
            title = "Ciacola - Presenter",
            initialBounds = display2.bounds,
            initialPlacement = WindowPlacement.Fullscreen,
            minimumSize = Dimension(760, 500),
        ).apply {
            simulatedFullscreenExitDurationMs = 600L
        }
        val router = createRouter(listOf(story, presenter), scheduler = scheduler)

        assertTrue(router.swapAudienceAndPresenter())
        scheduler.drain()

        assertEquals(WindowPlacement.Floating, presenter.placement)
        assertTrue(display1.bounds.contains(presenter.bounds.x + presenter.bounds.width / 2, presenter.bounds.y + presenter.bounds.height / 2))

        assertEquals(WindowPlacement.Fullscreen, story.placement)
        val entered = story.fullscreenEnteredOnBounds!!
        assertTrue(display2.bounds.contains(entered.x + entered.width / 2, entered.y + entered.height / 2))
        assertEquals(0, scheduler.activeTaskCount)
    }

    @Test
    fun rapidPresentThenSwapNeverIssuesDuplicateFloatingExitAndPreservesExitSettle() {
        val scheduler = VirtualTransitionScheduler()
        val story = FakeWindowTarget("Talk - Story", display1.bounds, WindowPlacement.Fullscreen).apply {
            simulatedFullscreenExitDurationMs = 800L
        }
        val presenter = FakeWindowTarget("Ciacola - Presenter", Rectangle(2200, 140, 1120, 680))
        val router = createRouter(listOf(story, presenter), scheduler = scheduler)

        // 1. Present Story on Display 2 while Story is still Fullscreen on Display 1.
        assertTrue(router.presentStoryOn(display2))
        scheduler.advanceBy(200L)
        assertEquals(WindowPlacement.Fullscreen, story.placement)
        assertEquals(1, story.placementChanges.count { it == WindowPlacement.Floating })

        // 2. Rapidly swap while macOS still reports Story as Fullscreen -> must NOT issue a second Floating toggle!
        assertTrue(router.swapAudienceAndPresenter())
        assertEquals(
            1,
            story.placementChanges.count { it == WindowPlacement.Floating },
            "In-flight fullscreen exit must not issue a second toggleFullScreen:",
        )
        assertTrue(story.setBoundsCalls.isEmpty())

        // 3. Also test a call arriving during the 120ms post-exit settle window -> preserves remaining settle!
        scheduler.advanceBy(600L) // t = 800ms: exit just finished, post-exit settle starts (until 920ms).
        assertEquals(WindowPlacement.Floating, story.placement)
        assertTrue(story.setBoundsCalls.isEmpty())

        scheduler.advanceBy(40L) // t = 840ms: 80ms of post-exit settle still remaining.
        assertTrue(router.presentStoryOn(display2))
        assertTrue(story.setBoundsCalls.isEmpty(), "Call during postExitSettleMs must honor remaining settle")

        scheduler.advanceBy(79L)
        assertTrue(story.setBoundsCalls.isEmpty())
        scheduler.advanceBy(1L) // t = 920ms
        assertEquals(1, story.setBoundsCalls.size)

        scheduler.drain()
        assertEquals(WindowPlacement.Fullscreen, story.placement)
        val entered = story.fullscreenEnteredOnBounds!!
        assertTrue(display2.bounds.contains(entered.x + entered.width / 2, entered.y + entered.height / 2))
        assertEquals(1, story.placementChanges.count { it == WindowPlacement.Floating })
        assertEquals(0, scheduler.activeTaskCount)
    }

    @Test
    fun rapidRepeatedSwapCancelsPriorTransitionAndStillRespectsOngoingFullscreenExit() {
        val scheduler = VirtualTransitionScheduler()
        val story = FakeWindowTarget("Talk - Story", display2.bounds, WindowPlacement.Fullscreen).apply {
            simulatedFullscreenExitDurationMs = 800L
        }
        val presenter = FakeWindowTarget("Ciacola - Presenter", Rectangle(200, 140, 1120, 680))
        val router = createRouter(listOf(story, presenter), scheduler = scheduler)

        assertTrue(router.swapAudienceAndPresenter())
        scheduler.advanceBy(200L)
        assertTrue(story.setBoundsCalls.isEmpty())

        // Second swap while Story is still exiting fullscreen on Display 2: swaps them back!
        assertTrue(router.swapAudienceAndPresenter())
        assertTrue(display1.bounds.contains(presenter.bounds.x + presenter.bounds.width / 2, presenter.bounds.y + presenter.bounds.height / 2))
        assertTrue(story.setBoundsCalls.isEmpty(), "Cancelled transition must keep waiting for ongoing exit")
        assertEquals(1, story.placementChanges.count { it == WindowPlacement.Floating })

        scheduler.drain()
        assertEquals(WindowPlacement.Fullscreen, story.placement)
        val entered = story.fullscreenEnteredOnBounds!!
        assertTrue(display2.bounds.contains(entered.x + entered.width / 2, entered.y + entered.height / 2))
        assertEquals(0, scheduler.activeTaskCount)
    }

    @Test
    fun maximizedWindowUnmaximizesBeforeRelocatingAcrossDisplays() {
        val scheduler = VirtualTransitionScheduler()
        val story = FakeWindowTarget("Talk - Story", display1.bounds, WindowPlacement.Maximized)
        val presenter = FakeWindowTarget("Ciacola - Presenter", Rectangle(2200, 140, 1120, 680))
        val router = createRouter(listOf(story, presenter), scheduler = scheduler)

        assertTrue(router.swapAudienceAndPresenter())
        assertEquals(WindowPlacement.Floating, story.placement)
        // Waits postExitSettleMs (120ms) on macOS before relocating from Maximized.
        assertTrue(story.setBoundsCalls.isEmpty())

        scheduler.advanceBy(120L)
        assertEquals(1, story.setBoundsCalls.size)
        assertEquals(WindowPlacement.Floating, story.placement)
        assertTrue(display2.bounds.contains(story.bounds.x + story.bounds.width / 2, story.bounds.y + story.bounds.height / 2))
        assertEquals(0, scheduler.activeTaskCount)
    }

    @Test
    fun relocateRetryReappliesBoundsWhenFirstSetBoundsMissesTargetDisplay() {
        val scheduler = VirtualTransitionScheduler()
        val story = FakeWindowTarget("Talk - Story", Rectangle(100, 80, 1280, 720)).apply {
            clampSetBoundsCount = 1
        }
        val router = createRouter(listOf(story), scheduler = scheduler)

        assertTrue(router.presentStoryOn(display2))
        // First setBounds was ignored/clamped -> still on display1 after initial settle.
        assertTrue(display1.bounds.contains(story.bounds.x + story.bounds.width / 2, story.bounds.y + story.bounds.height / 2))

        scheduler.advanceBy(150L)
        // Retried once and scheduled another 150ms settle before Fullscreen.
        assertEquals(2, story.setBoundsCalls.size)
        assertEquals(WindowPlacement.Floating, story.placement)
        assertTrue(display2.bounds.contains(story.bounds.x + story.bounds.width / 2, story.bounds.y + story.bounds.height / 2))

        scheduler.advanceBy(150L)
        assertEquals(WindowPlacement.Fullscreen, story.placement)
        assertEquals(0, scheduler.activeTaskCount)
    }

    @Test
    fun windowHiddenMidTransitionCancelsTimersWithoutMutatingWindow() {
        val scheduler = VirtualTransitionScheduler()
        val story = FakeWindowTarget("Talk - Story", display1.bounds, WindowPlacement.Fullscreen).apply {
            simulatedFullscreenExitDurationMs = 800L
        }
        val router = createRouter(listOf(story), scheduler = scheduler)

        assertTrue(router.presentStoryOn(display2))
        scheduler.advanceBy(120L)
        story.isShowing = false
        story.isDisplayable = false

        scheduler.drain()
        assertTrue(story.setBoundsCalls.isEmpty())
        assertEquals(listOf(WindowPlacement.Floating), story.placementChanges)
        assertEquals(0, scheduler.activeTaskCount)
    }

    @Test
    fun exitPollingIsBoundedWhenBoundsNeverShrinkAndAbortsIfStillFullscreen() {
        // Case 1: Pre-fullscreen floating size filled display1, but placement became Floating -> relocates at timeout.
        val scheduler1 = VirtualTransitionScheduler()
        val fillingFloatingStory = FakeWindowTarget("Talk - Story", display1.bounds, WindowPlacement.Fullscreen).apply {
            keepPlacementFullscreenDuringExit = false
            simulatedFullscreenExitDurationMs = 40L
            setFloatingRestoreBounds(display1.bounds)
        }
        val router1 = createRouter(listOf(fillingFloatingStory), scheduler = scheduler1)

        assertTrue(router1.presentStoryOn(display2))
        scheduler1.advanceBy(1_960L)
        assertTrue(fillingFloatingStory.setBoundsCalls.isEmpty())

        scheduler1.drain()
        assertEquals(1, fillingFloatingStory.setBoundsCalls.size)
        assertEquals(WindowPlacement.Fullscreen, fillingFloatingStory.placement)
        assertTrue(display2.bounds.contains(fillingFloatingStory.fullscreenEnteredOnBounds!!.x + fillingFloatingStory.fullscreenEnteredOnBounds!!.width / 2, fillingFloatingStory.fullscreenEnteredOnBounds!!.y + fillingFloatingStory.fullscreenEnteredOnBounds!!.height / 2))
        assertEquals(0, scheduler1.activeTaskCount)

        // Case 2: Window refuses to exit Fullscreen past exitTimeoutMs -> aborts cleanly without setBounds.
        val scheduler2 = VirtualTransitionScheduler()
        val stuckStory = FakeWindowTarget("Talk - Story", display1.bounds, WindowPlacement.Fullscreen).apply {
            simulatedFullscreenExitDurationMs = 10_000L
        }
        val router2 = createRouter(listOf(stuckStory), scheduler = scheduler2)

        assertTrue(router2.presentStoryOn(display2))
        scheduler2.advanceBy(2_500L)
        assertTrue(stuckStory.setBoundsCalls.isEmpty())
        assertEquals(WindowPlacement.Fullscreen, stuckStory.placement)
        assertEquals(0, scheduler2.activeTaskCount)
    }

    @Test
    fun relocateCenteredClampsOversizedWindowAndSupportsNegativeOriginDisplays() {
        val scheduler = VirtualTransitionScheduler()
        val leftScreen = DisplayScreenInfo("left", "Left (1440×900)", Rectangle(-1440, -100, 1440, 900), false)
        val story = FakeWindowTarget("Talk - Story", Rectangle(100, 100, 1800, 1100), WindowPlacement.Floating)
        val router = createRouter(listOf(story), screens = listOf(display1, leftScreen), scheduler = scheduler)

        assertTrue(router.presentStoryOn(leftScreen))
        assertEquals(Rectangle(-1440, -100, 1440, 900), story.bounds)

        scheduler.drain()
        assertEquals(WindowPlacement.Fullscreen, story.placement)
        assertEquals(0, scheduler.activeTaskCount)
    }

    @Test
    fun presentStoryOnSameFullscreenDisplayIsNoOpAndGuardChecksReturnFalse() {
        val scheduler = VirtualTransitionScheduler()
        val story = FakeWindowTarget("Talk - Story", display1.bounds, WindowPlacement.Fullscreen)
        val router = createRouter(listOf(story), screens = listOf(display1), scheduler = scheduler)

        assertTrue(router.presentStoryOn(display1))
        assertEquals(WindowPlacement.Fullscreen, story.placement)
        assertTrue(story.placementChanges.isEmpty())
        assertEquals(0, scheduler.activeTaskCount)

        assertFalse(router.swapAudienceAndPresenter())

        val noWindowsRouter = createRouter(emptyList(), screens = listOf(display1, display2), scheduler = scheduler)
        assertFalse(noWindowsRouter.presentStoryOn(display1))
        assertFalse(noWindowsRouter.swapAudienceAndPresenter())
    }

    @Test
    fun swapOnSameDisplayMovesStoryToSecondaryAndKeepsFloatingWhenStoryWasFloating() {
        val scheduler = VirtualTransitionScheduler()
        val story = FakeWindowTarget("Talk - Story", Rectangle(100, 100, 1200, 700), WindowPlacement.Floating)
        val presenter = FakeWindowTarget("Ciacola - Presenter", Rectangle(200, 150, 1120, 680), WindowPlacement.Floating)
        val router = createRouter(listOf(story, presenter), scheduler = scheduler)

        assertTrue(router.swapAudienceAndPresenter())
        assertEquals(WindowPlacement.Floating, story.placement)
        assertEquals(WindowPlacement.Floating, presenter.placement)
        assertTrue(display1.bounds.contains(presenter.bounds.x + presenter.bounds.width / 2, presenter.bounds.y + presenter.bounds.height / 2))
        assertTrue(display2.bounds.contains(story.bounds.x + story.bounds.width / 2, story.bounds.y + story.bounds.height / 2))
    }
}
