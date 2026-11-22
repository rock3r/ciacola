package dev.bnorm.storyboard.easel

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.unit.dp
import dev.bnorm.storyboard.Storyboard
import dev.bnorm.storyboard.easel.overview.EaselOverview
import dev.bnorm.storyboard.easel.overview.OverviewState
import dev.bnorm.storyboard.easel.overview.OverviewVisibility
import dev.bnorm.storyboard.easel.overview.filterOverviewSlideColumns
import dev.bnorm.storyboard.easel.overview.rememberOverviewVisibility
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class EaselOverviewScaleTest {

    private fun buildDeck(slideCount: Int): Storyboard = Storyboard.build(title = "ScaleOverview$slideCount") {
        repeat(slideCount) { s ->
            val frames = if (s % 3 == 0) listOf(0, 1) else listOf(0)
            scene(frames = frames) {
                Box(Modifier.fillMaxSize()) {
                    Text("Slide ${s + 1}")
                }
            }
        }
    }

    @Test
    fun filterOverviewSlideColumnsPrioritizesExactNumberAndMatchesTitles() {
        val deck = buildDeck(136)
        val state = OverviewState.of(deck)
        val titles: (Int) -> String = { idx ->
            when (idx) {
                0 -> "Opening"
                24 -> "Architecture Deep Dive"
                135 -> "Closing Credits"
                else -> "Topic ${idx + 1}"
            }
        }

        // Exact number ranked first (Slide 25 before Slide 125).
        val numMatches = filterOverviewSlideColumns(state.columns, "25", titles)
        assertEquals(24, numMatches.first().index, "Query '25' must rank Slide 25 (#25) first")

        // Numeric prefix ranking: "1" orders #1, then #10..#19, then #100..#136 cleanly.
        val onePrefix = filterOverviewSlideColumns(state.columns, "1", titles).map { it.index + 1 }
        assertEquals(1, onePrefix.first(), "Query '1' must rank #1 first")
        assertEquals((10..19).toList(), onePrefix.subList(1, 11), "Query '1' must rank #10..#19 before #100+")
        assertEquals(100, onePrefix[11], "Query '1' must rank #100 immediately after #19")

        val hashMatches = filterOverviewSlideColumns(state.columns, "#136", titles)
        assertEquals(listOf(135), hashMatches.map { it.index })

        val titleMatches = filterOverviewSlideColumns(state.columns, "architecture", titles)
        assertEquals(listOf(24), titleMatches.map { it.index })

        val closingMatches = filterOverviewSlideColumns(state.columns, "credits", titles)
        assertEquals(listOf(135), closingMatches.map { it.index })
    }

    @Test
    fun smallDeckDoesNotRenderLargeDeckGridOrQuickJumpControls() = runComposeUiTest {
        val deck = buildDeck(6)
        lateinit var visibility: OverviewVisibility
        setContent {
            val animatic = rememberAnimatic(storyboard = { deck })
            visibility = rememberOverviewVisibility(initiallyVisible = true)
            Box(Modifier.size(1_000.dp, 640.dp)) {
                EaselOverview(animatic, visibility = visibility) { Easel(animatic) }
            }
        }
        mainClock.advanceTimeBy(600)
        waitForIdle()

        onNodeWithTag("easel-overview-grid-toggle", useUnmergedTree = true).assertDoesNotExist()
        onNodeWithTag("easel-overview-strip-toggle", useUnmergedTree = true).assertDoesNotExist()
        onNodeWithTag("easel-overview-quick-jump-trigger", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun largeDeckDefaultsToGridSupportsStripToggleAndNonMutatingCmdKQuickJump() = runComposeUiTest {
        val deck = buildDeck(30)
        val titles: (Int) -> String = { idx ->
            when (idx) {
                24 -> "Architecture"
                29 -> "Finale"
                else -> "Chapter ${idx + 1}"
            }
        }
        lateinit var animatic: Animatic
        lateinit var visibility: OverviewVisibility

        setContent {
            animatic = rememberAnimatic(storyboard = { deck })
            visibility = rememberOverviewVisibility(initiallyVisible = true)
            Box(Modifier.size(1_100.dp, 720.dp)) {
                EaselOverview(
                    animatic = animatic,
                    visibility = visibility,
                    slideTitle = titles,
                ) {
                    Easel(animatic)
                }
            }
        }
        mainClock.advanceTimeBy(600)
        waitForIdle()

        // 1. Large decks (> 12 slides) default to 2D Grid mode with segmented [Strip | Grid] chrome.
        onNodeWithTag("easel-overview-strip-toggle", useUnmergedTree = true).assertExists()
        onNodeWithTag("easel-overview-grid-toggle", useUnmergedTree = true).assertExists()
        onNodeWithTag("easel-overview-quick-jump-trigger", useUnmergedTree = true).assertExists()
        onNodeWithTag("easel-overview-grid", useUnmergedTree = true).assertExists()
        onNodeWithTag("easel-overview-grid-card-0", useUnmergedTree = true).assertExists()

        // Toggle Strip -> Grid round-trip works cleanly.
        onNodeWithTag("easel-overview-strip-toggle", useUnmergedTree = true).performClick()
        waitForIdle()
        onNodeWithTag("easel-overview-grid", useUnmergedTree = true).assertDoesNotExist()
        onNodeWithTag("easel-overview-grid-toggle", useUnmergedTree = true).performClick()
        waitForIdle()
        onNodeWithTag("easel-overview-grid", useUnmergedTree = true).assertExists()

        // 2D arrow navigation in Grid mode: Right moves to slide 2 (index 1).
        onRoot().performKeyInput { pressKey(Key.DirectionRight) }
        mainClock.advanceTimeBy(300)
        waitForIdle()
        assertEquals(1, animatic.currentIndex.sceneIndex, "DirectionRight in grid must advance to slide 2")

        // 2. Press Cmd+K to open Quick Slide Jump, type "25": active slide badge stays on Slide 2 until Enter!
        onRoot().performKeyInput {
            withKeyDown(Key.MetaLeft) { pressKey(Key.K) }
        }
        waitForIdle()

        val input = onNodeWithTag("easel-overview-quick-jump-input", useUnmergedTree = true)
        input.assertExists()
        input.performTextInput("25")
        waitForIdle()

        // Non-mutating preview: animatic stays on Slide 2 until confirmed via Enter.
        assertEquals(1, animatic.currentIndex.sceneIndex, "Typing in Cmd+K must not mutate active slide before Enter")
        onNodeWithTag("easel-overview-grid-card-24", useUnmergedTree = true).assertExists()
        input.performKeyInput { pressKey(Key.Enter) }
        mainClock.advanceTimeBy(600)
        waitForIdle()

        assertEquals(Storyboard.Index(24, 0), animatic.currentIndex, "Quick Jump Enter must land on Slide 25")
        assertFalse(visibility.isVisible, "Quick Jump Enter must exit Overview")

        // 3. Re-open Overview, open Quick Jump via Ctrl+K, search by title "Finale", press Esc:
        // active slide stays Slide 25 because Esc cancels without committing!
        runOnIdle { visibility.isVisible = true }
        mainClock.advanceTimeBy(600)
        waitForIdle()

        onRoot().performKeyInput {
            withKeyDown(Key.CtrlLeft) { pressKey(Key.K) }
        }
        waitForIdle()
        val input2 = onNodeWithTag("easel-overview-quick-jump-input", useUnmergedTree = true)
        input2.assertExists()
        input2.performTextInput("Finale")
        waitForIdle()
        onNodeWithTag("easel-overview-grid-card-29", useUnmergedTree = true).assertExists()
        assertEquals(24, animatic.currentIndex.sceneIndex, "Typing 'Finale' must highlight without mutating Slide 25")

        input2.performKeyInput { pressKey(Key.Escape) }
        waitForIdle()
        assertTrue(visibility.isVisible, "First Esc must close Quick Jump without dismissing Overview")
        assertEquals(24, animatic.currentIndex.sceneIndex, "Esc must keep active slide unchanged on Slide 25")
        onNodeWithTag("easel-overview-quick-jump-input", useUnmergedTree = true).assertDoesNotExist()
        onNodeWithTag("easel-overview-quick-jump-trigger", useUnmergedTree = true).assertExists()

        onRoot().performKeyInput { pressKey(Key.Escape) }
        mainClock.advanceTimeBy(600)
        waitForIdle()
        assertFalse(visibility.isVisible, "Second Esc must dismiss Overview")
    }
}
