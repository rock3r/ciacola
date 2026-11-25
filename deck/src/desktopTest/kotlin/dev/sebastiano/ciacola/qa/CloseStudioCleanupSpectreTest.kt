package dev.sebastiano.ciacola.qa

import dev.sebastiano.ciacola.studio.StudioInspectorTab
import dev.sebastiano.ciacola.studio.StudioMode
import dev.sebastiano.spectre.testing.runSpectreTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import org.junit.Rule

/** Entering Present mode must leave a clean presenting stage with zero authoring overlays. */
class CloseStudioCleanupSpectreTest {

    @get:Rule
    val qa = QaStudioRule()

    @Test
    fun closingTheTimelineHidesDirectEditOverlayAndRestoresOnReopen(): Unit = runSpectreTest(timeout = 120.seconds) {
        val studio = qa.launchStudio(worker = "fix-stage")
        with(studio) {
            openTimeline()
            restOnBeat(sceneIndex = 0, frameIndex = 0)
            val designTab = automator.waitForNode(tag = "ciacola-studio-tab-design", timeout = 5.seconds)
            automator.click(designTab)
            settle()
            assertTrue(studioWorkspace.isElementPickingEnabled)
            automator.waitForNode(tag = "ciacola-doc-stage-direct-edit", timeout = 5.seconds)

            // Select a node on stage while authoring, then switch to Present mode from the App Bar.
            onUi { elementController.selectNode("s1:eyebrow") }
            settle()
            automator.click(automator.waitForNode(tag = "ciacola-app-bar-mode-present", timeout = 5.seconds))
            automator.waitUntil(description = "studio closed in Present mode", timeout = 5.seconds) {
                studioWorkspace.mode == StudioMode.Present && !timelineWindow.authoringActive
            }
            settle()

            assertFalse(studioWorkspace.isElementPickingEnabled)
            assertNull(
                automator.findOneByTestTag("ciacola-doc-stage-direct-edit"),
                "Present mode must unmount direct-edit overlay",
            )
            assertNull(
                automator.findOneByTestTag("ciacola-studio-app-bar"),
                "Present mode must hide Studio App Bar",
            )

            // Switching back to Edit mode restores the App Bar, Design tab, and direct-edit overlay.
            onUi { studioWorkspace.switchMode(StudioMode.Edit) }
            automator.waitForNode(tag = "ciacola-studio-app-bar", timeout = 5.seconds)
            settle()
            assertTrue(studioWorkspace.activeTab == StudioInspectorTab.Design)
            automator.waitForNode(tag = "ciacola-doc-stage-direct-edit", timeout = 5.seconds)
        }
    }
}
