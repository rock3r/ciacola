package dev.sebastiano.ciacola.qa

import dev.sebastiano.spectre.testing.runSpectreTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import org.junit.Rule

/** Closing the studio must leave a clean presenting stage: no authoring overlays, no frozen loops. */
class CloseStudioCleanupSpectreTest {

    @get:Rule
    val qa = QaStudioRule()

    @Test
    fun closingTheTimelineHidesSelectionAndExitsTheLoopSubComp(): Unit = runSpectreTest(timeout = 120.seconds) {
        val studio = qa.launchStudio(worker = "fix-stage")
        with(studio) {
            openTimeline()
            restOnBeat(sceneIndex = 1, frameIndex = 0)
            automator.click(automator.waitForNode(tag = "ciacola-track-row-shared:foundation-shared-card", timeout = 5.seconds))
            val overlay = { automator.findOneByTestTag("ciacola-stage-selection-overlay")?.contentDescription.orEmpty() }
            automator.waitUntil(description = "card highlighted while authoring", timeout = 5.seconds) {
                overlay().startsWith("selected=shared:foundation-shared-card")
            }

            // Enter the loop sub-comp, then close the studio from the HUD.
            automator.click(automator.waitForNode(tag = "ciacola-timeline-open-loop", timeout = 5.seconds))
            automator.waitForNode(tag = "ciacola-timeline-exit-subcomp", timeout = 5.seconds)
            settle()
            automator.click(automator.waitForNode(tag = "ciacola-hud-toggle-timeline", timeout = 5.seconds))
            automator.waitUntil(description = "studio closed", timeout = 5.seconds) { !controller.authoringActive }
            settle()

            automator.waitUntil(description = "no selection highlight while presenting (overlay: ${overlay()})", timeout = 5.seconds) {
                overlay() == "selected=none highlighted=0"
            }
            assertNull(loopClock.activeSubCompLoopId, "Closing the studio leaves the loop sub-comp")
            assertTrue(loopClock.isPlaying, "Ambient loops free-run again")
            assertNull(loopClock.scrubbedMillis)

            // Re-opening the studio brings the selection back.
            automator.click(automator.waitForNode(tag = "ciacola-hud-toggle-timeline", timeout = 5.seconds))
            automator.waitForNode(tag = "ciacola-timeline-canvas", timeout = 5.seconds)
            automator.waitUntil(description = "selection restored when authoring again", timeout = 5.seconds) {
                overlay().startsWith("selected=shared:foundation-shared-card")
            }
            assertEquals("shared:foundation-shared-card", controller.selectedTrackId)
        }
    }
}
