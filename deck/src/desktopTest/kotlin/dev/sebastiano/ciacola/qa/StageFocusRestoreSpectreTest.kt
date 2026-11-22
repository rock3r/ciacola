package dev.sebastiano.ciacola.qa

import dev.bnorm.storyboard.Storyboard
import dev.sebastiano.spectre.testing.runSpectreTest
import java.awt.event.KeyEvent
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import org.junit.Rule

/**
 * Presenting keys must keep working after closing Studio or dismissing a review popup:
 * keyboard focus must return cleanly to the stage.
 */
class StageFocusRestoreSpectreTest {

    @get:Rule
    val qa = QaStudioRule()

    @Test
    fun arrowsStillPresentAfterInteractingWithStudioAndClosingIt(): Unit = runSpectreTest(timeout = 120.seconds) {
        val studio = qa.launchStudio(worker = "fix-stage")
        with(studio) {
            openTimeline()
            restOnBeat(sceneIndex = 0, frameIndex = 0)

            // Click a track row and zoom buttons inside Studio, then enter Present mode -> stage arrows must work!
            val firstTrackId = controller.snapshot.snapshot.tracks.first().trackId
            automator.click(automator.waitForNode(tag = "ciacola-doc-track-row-$firstTrackId", timeout = 5.seconds))
            settle()
            automator.click(automator.waitForNode(tag = "ciacola-doc-timeline-zoom-in", timeout = 5.seconds))
            settle()
            automator.click(automator.waitForNode(tag = "ciacola-app-bar-mode-present", timeout = 5.seconds))
            automator.waitUntil(description = "studio closed in Present mode", timeout = 5.seconds) { !timelineWindow.authoringActive }
            settle()

            automator.pressKey(KeyEvent.VK_RIGHT)
            automator.waitUntil(description = "→ after closing Studio reaches (0, 1)", timeout = 5.seconds) {
                animatic.currentIndex == Storyboard.Index(0, 1)
            }
            automator.pressKey(KeyEvent.VK_RIGHT)
            automator.waitUntil(description = "→ reaches (0, 2)", timeout = 5.seconds) {
                animatic.currentIndex == Storyboard.Index(0, 2)
            }
        }
    }

    @Test
    fun arrowsAndEscWorkAfterDismissingTheReviewComposer(): Unit = runSpectreTest(timeout = 120.seconds) {
        val studio = qa.launchStudio(worker = "fix-stage")
        with(studio) {
            automator.click(automator.waitForNode(tag = "ciacola-app-bar-mode-review", timeout = 10.seconds))
            automator.waitUntil(description = "inspect on", timeout = 5.seconds) { review.inspectModeEnabled }
            settle()
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
