package dev.sebastiano.ciacola.qa

import dev.bnorm.storyboard.Storyboard
import dev.sebastiano.spectre.testing.runSpectreTest
import java.awt.event.KeyEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import org.junit.Rule

/**
 * Presenting keys must keep working after the presenter touches studio chrome (transport, layer
 * rows, the zoom slider) or dismisses a review popup: the keyboard must come back to the stage.
 */
class StageFocusRestoreSpectreTest {

    @get:Rule
    val qa = QaStudioRule()

    @Test
    fun arrowsStillPresentAfterClickingStudioControls(): Unit = runSpectreTest(timeout = 120.seconds) {
        val studio = qa.launchStudio(worker = "fix-stage")
        with(studio) {
            openTimeline()
            restOnBeat(sceneIndex = 0, frameIndex = 0)

            suspend fun pressRightExpect(expected: Storyboard.Index, after: String) {
                automator.pressKey(KeyEvent.VK_RIGHT)
                automator.waitUntil(description = "→ after $after reaches $expected (at ${animatic.currentIndex})", timeout = 5.seconds) {
                    animatic.currentIndex == expected
                }
            }

            // Transport play + pause.
            val play = automator.waitForNode(tag = "ciacola-timeline-play", timeout = 5.seconds)
            automator.click(play)
            delay(250)
            automator.click(play)
            settle()
            restOnBeat(sceneIndex = 0, frameIndex = 0)
            pressRightExpect(Storyboard.Index(0, 1), "clicking Play/Pause")

            // A layer row.
            automator.click(automator.waitForNode(tag = "ciacola-track-row-s0:magic-text", timeout = 5.seconds))
            settle()
            pressRightExpect(Storyboard.Index(0, 2), "clicking a layer row")

            // The zoom slider must not swallow the arrows.
            val zoom = controller.viewport.pxPerMs
            automator.click(automator.waitForNode(tag = "ciacola-timeline-zoom-slider", timeout = 5.seconds))
            settle()
            val zoomAfterClick = controller.viewport.pxPerMs
            pressRightExpect(Storyboard.Index(1, 0), "clicking the zoom slider")
            assertEquals(zoomAfterClick, controller.viewport.pxPerMs, "→ must not change the timeline zoom (was $zoom before the click)")
        }
    }

    @Test
    fun arrowsAndEscWorkAfterDismissingTheReviewComposer(): Unit = runSpectreTest(timeout = 120.seconds) {
        val studio = qa.launchStudio(worker = "fix-stage")
        with(studio) {
            automator.waitForNode(tag = "ciacola-hud-toggle-inspect", timeout = 10.seconds)
            settle()
            onUi { review.inspectModeEnabled = true }
            automator.waitUntil(description = "inspect on", timeout = 5.seconds) { review.inspectModeEnabled }
            automator.click(automator.waitForNode(tag = "ciacola-review-stage", timeout = 5.seconds))
            automator.waitForNode(tag = "ciacola-review-note-input", timeout = 5.seconds)
            automator.waitUntil(description = "composer focused", timeout = 5.seconds) {
                automator.findOneByTestTag("ciacola-review-note-input")?.isFocused == true
            }
            automator.pressKey(KeyEvent.VK_ESCAPE)
            automator.waitUntil(description = "composer closed", timeout = 5.seconds) { review.pendingSelection == null }
            onUi { review.inspectModeEnabled = false }
            automator.waitUntil(description = "inspect off", timeout = 5.seconds) { !review.inspectModeEnabled }
            settle()
            delay(700)

            val before = animatic.currentIndex
            automator.pressKey(KeyEvent.VK_RIGHT)
            automator.waitUntil(description = "→ after closing the composer advances (still $before)", timeout = 5.seconds) {
                animatic.currentIndex != before
            }
            // Esc must open the overview again (it must not be swallowed forever).
            automator.pressKey(KeyEvent.VK_ESCAPE)
            automator.waitUntil(description = "Esc opens the overview after the composer was dismissed", timeout = 5.seconds) {
                automator.findByText("FOUNDATION / PROVISIONAL", true).size > 1
            }
        }
    }
}
