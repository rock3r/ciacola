package dev.bnorm.storyboard.easel

import androidx.compose.material.Text
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.bnorm.storyboard.Storyboard
import dev.bnorm.storyboard.easel.overview.EaselOverview
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class EaselOverviewCardsTest {

    @Test
    fun overviewRendersOnlyRealBeatsWithoutGhostDuplicatesOrRawZeroZeroBadge() = runComposeUiTest {
        val deck = Storyboard.build(title = "OverviewTest") {
            scene(frames = listOf(1, 2)) { Text("Slide Zero") }
            scene(frames = listOf(1)) { Text("Interior Single Beat") }
            scene(frames = listOf(1, 2)) { Text("Slide Two") }
        }

        setContent {
            val animatic = rememberAnimatic(storyboard = { deck })
            EaselOverview(animatic) { Easel(animatic) }
        }
        waitForIdle()

        onRoot().performKeyInput { pressKey(Key.Escape) }
        mainClock.advanceTimeBy(600)
        waitForIdle()

        // Interior single-beat slide must render exactly 1 card (no Frame.Start / Frame.End ghost duplicates).
        assertEquals(
            1,
            onAllNodesWithText("Interior Single Beat").fetchSemanticsNodes().size,
            "Expected interior single-beat slide to render 1 overview card without Start/End duplicates",
        )
        assertEquals(
            0,
            onAllNodesWithText("0,0").fetchSemanticsNodes().size,
            "Overview must not display raw '0,0' Storyboard.Index debug string",
        )
        assertEquals(
            1,
            onAllNodesWithText("Slide 1 / 3 · Beat 1 / 2").fetchSemanticsNodes().size,
            "Expected human-readable 1-based slide/beat badge in Overview",
        )
    }
}
