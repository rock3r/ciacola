package dev.sebastiano.ciacola.presenter

import androidx.compose.ui.window.WindowPlacement
import dev.sebastiano.ciacola.DeckCatalog
import dev.sebastiano.ciacola.qa.QaStudioRule
import dev.sebastiano.spectre.testing.runSpectreTest
import java.awt.EventQueue
import java.awt.Rectangle
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import org.junit.Assume.assumeTrue
import org.junit.Rule

/**
 * Hardware-guarded real-window verification of [AwtDisplayRouter] across two attached displays.
 * Skipped cleanly via `assumeTrue` on headless or single-display environments.
 */
class DualDisplaySwapSpectreTest {

    @get:Rule
    val qa = QaStudioRule()

    @Test
    fun presentOnSecondaryAndSwapSwapsFullscreenStoryAndPresenterAcrossDisplays(): Unit = runSpectreTest(timeout = 120.seconds) {
        val probeRouter = AwtDisplayRouter()
        val screens = probeRouter.screens()
        assumeTrue("Requires at least 2 connected displays (found ${screens.size})", screens.size >= 2)

        val primary = screens[0]
        val secondary = screens[1]

        val studio = qa.launchStudio(
            deckId = DeckCatalog.FOUNDATION_ID,
            timelineDocked = false,
            worker = "dual-display-swap",
        )
        with(studio) {
            onUi { presenterWindow.visible = true }
            automator.waitUntil(description = "Presenter window opened", timeout = 10.seconds) {
                presenterWindow.visible && presenterComposeWindow?.isShowing == true
            }
            settle()

            val router = AwtDisplayRouter()
            val storyWin = window
            val presenterWin = requireNotNull(presenterComposeWindow)

            // 1. Place Story floating on primary and Presenter floating on secondary, then swap while floating.
            EventQueue.invokeAndWait {
                storyWin.placement = WindowPlacement.Floating
                storyWin.setBounds(primary.bounds.x + 80, primary.bounds.y + 80, 1100, 680)
                presenterWin.placement = WindowPlacement.Floating
                presenterWin.setBounds(secondary.bounds.x + 80, secondary.bounds.y + 80, 1000, 640)
            }
            delay(300)

            check(router.swapAudienceAndPresenter()) { "swapAudienceAndPresenter() returned false" }
            automator.waitUntil(description = "Floating swap moves Story to secondary and Presenter to primary", timeout = 8.seconds) {
                isWindowCenteredIn(storyWin.bounds, secondary.bounds) &&
                    isWindowCenteredIn(presenterWin.bounds, primary.bounds) &&
                    storyWin.placement == WindowPlacement.Floating &&
                    presenterWin.placement == WindowPlacement.Floating
            }

            // 2. Present Story fullscreen on secondary, wait for Space transition to settle, then swap while fullscreen.
            try {
                check(router.presentStoryOn(secondary)) { "presentStoryOn(secondary) returned false" }
                automator.waitUntil(description = "Story enters fullscreen on secondary display", timeout = 8.seconds) {
                    storyWin.placement == WindowPlacement.Fullscreen &&
                        isFillingDisplay(storyWin.bounds, secondary.bounds)
                }
                // Allow AppKit fullscreen Space animation to finish before initiating the swap.
                delay(1_200)

                check(router.swapAudienceAndPresenter()) { "swapAudienceAndPresenter() while fullscreen returned false" }
                automator.waitUntil(
                    description = "Fullscreen swap moves Story fullscreen to primary (${primary.bounds}) and Presenter to secondary (${secondary.bounds})",
                    timeout = 10.seconds,
                ) {
                    storyWin.placement == WindowPlacement.Fullscreen &&
                        isFillingDisplay(storyWin.bounds, primary.bounds) &&
                        isWindowCenteredIn(presenterWin.bounds, secondary.bounds) &&
                        presenterWin.placement == WindowPlacement.Floating
                }
                delay(1_200)

                // 3. Swap back while Story is fullscreen on primary -> Story returns fullscreen to secondary, Presenter to primary.
                check(router.swapAudienceAndPresenter()) { "swapAudienceAndPresenter() return swap returned false" }
                automator.waitUntil(
                    description = "Return swap moves Story fullscreen to secondary (${secondary.bounds}) and Presenter to primary (${primary.bounds})",
                    timeout = 10.seconds,
                ) {
                    storyWin.placement == WindowPlacement.Fullscreen &&
                        isFillingDisplay(storyWin.bounds, secondary.bounds) &&
                        isWindowCenteredIn(presenterWin.bounds, primary.bounds) &&
                        presenterWin.placement == WindowPlacement.Floating
                }
                delay(1_200)
            } finally {
                runCatching { closePresenterWindow() }
                if (storyWin.isDisplayable) {
                    runCatching {
                        EventQueue.invokeAndWait {
                            if (storyWin.placement == WindowPlacement.Fullscreen) {
                                storyWin.placement = WindowPlacement.Floating
                            }
                        }
                    }
                    runCatching {
                        automator.waitUntil(
                            description = "Story exits fullscreen before teardown",
                            timeout = 4.seconds,
                        ) {
                            storyWin.placement == WindowPlacement.Floating &&
                                !isFillingDisplay(storyWin.bounds, secondary.bounds) &&
                                !isFillingDisplay(storyWin.bounds, primary.bounds)
                        }
                    }
                    delay(500)
                }
            }
        }
    }

    private fun isWindowCenteredIn(windowBounds: Rectangle, displayBounds: Rectangle): Boolean {
        if (windowBounds.width <= 0 || windowBounds.height <= 0) return false
        val cx = windowBounds.x + windowBounds.width / 2
        val cy = windowBounds.y + windowBounds.height / 2
        return displayBounds.contains(cx, cy)
    }

    private fun isFillingDisplay(windowBounds: Rectangle, displayBounds: Rectangle): Boolean {
        if (!isWindowCenteredIn(windowBounds, displayBounds)) return false
        return windowBounds.width >= (displayBounds.width * 0.95).toInt() &&
            windowBounds.height >= (displayBounds.height * 0.90).toInt()
    }
}
