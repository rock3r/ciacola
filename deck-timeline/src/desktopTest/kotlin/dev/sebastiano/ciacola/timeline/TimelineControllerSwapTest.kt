package dev.sebastiano.ciacola.timeline

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import dev.sebastiano.ciacola.ui.CiacolaJewelTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Header-band gestures follow the CURRENT controller after a deck switch swaps it. */
@OptIn(ExperimentalTestApi::class)
class TimelineControllerSwapTest {

    @Test
    fun headerBandTapDrivesTheNewControllerAfterASwap() = runDesktopComposeUiTest(width = 1_200, height = 200) {
        val first = TimelineFixtures.controller()
        val second = TimelineFixtures.controller()
        var controller by mutableStateOf(first)
        setContent {
            CiacolaJewelTheme(isDark = true) {
                Box(Modifier.width(1_100.dp).height(64.dp)) {
                    DocumentTimelineCollapsedBar(controller = controller, onToggleExpanded = {})
                }
            }
        }
        waitForIdle()
        val segments = { onNodeWithTag("ciacola-timeline-header-segments", useUnmergedTree = true) }
        // Start the gesture coroutine on the FIRST controller.
        segments().performMouseInput { click(Offset(6f, height / 2f)) }
        waitForIdle()
        assertTrue(first.selection is DocumentTimelineSelection.BeatHeader, "Tap selects on the first controller")
        runOnIdle { first.clearSelection() }
        controller = second
        waitForIdle()
        segments().performMouseInput { click(Offset(6f, height / 2f)) }
        waitForIdle()
        assertTrue(second.selection is DocumentTimelineSelection.BeatHeader, "Tap must select on the new controller (${second.selection})")
        assertEquals(DocumentTimelineSelection.None, first.selection, "The previous deck's controller must stay untouched")
    }
}
