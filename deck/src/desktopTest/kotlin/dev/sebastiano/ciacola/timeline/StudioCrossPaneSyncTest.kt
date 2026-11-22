package dev.sebastiano.ciacola.timeline

import androidx.compose.runtime.remember
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import dev.bnorm.storyboard.easel.EaselWindow
import dev.sebastiano.ciacola.createLoadedTestDeckStore
import dev.sebastiano.ciacola.indexFoundationDocumentTimeline
import dev.sebastiano.ciacola.inspector.design.DocumentThemeInspectorController
import dev.sebastiano.ciacola.inspector.design.rememberDocumentThemeInspectorWindow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Cross-module seam checks between Easel windows, Theme Inspector, and Analytical Timeline. */
@OptIn(ExperimentalTestApi::class)
class StudioCrossPaneSyncTest {

    @Test
    fun documentThemeInspectorWindowOpensWithExpectedInitialAndMinimumBounds() = runComposeUiTest {
        val store = createLoadedTestDeckStore()
        lateinit var window: EaselWindow
        setContent {
            val controller = remember { DocumentThemeInspectorController(store = store) }
            window = rememberDocumentThemeInspectorWindow(controller = controller)
        }
        waitForIdle()
        assertEquals(DpSize(440.dp, 680.dp), window.initialSize)
        assertEquals(DpSize(360.dp, 460.dp), window.minimumSize)
    }

    @Test
    fun analyticalTimelineIndexesSlide5LabTracksWithPositiveDurations() {
        val docTimeline = indexFoundationDocumentTimeline()
        val slide5Tracks = docTimeline.trackRefsByTrackId.values
            .filterIsInstance<DocumentTimelineTrackRef.SlideBuildTrack>()
            .filter { it.sceneIndex == 4 }
        assertTrue(slide5Tracks.isNotEmpty(), "Expected Slide 5 Keynote Effects Lab build tracks")
        assertTrue(slide5Tracks.all { it.trackPlan.durationMs > 0 })
    }
}
