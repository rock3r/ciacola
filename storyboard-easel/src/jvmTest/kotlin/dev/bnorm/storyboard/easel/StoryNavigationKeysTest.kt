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
class StoryNavigationKeysTest {
    private val deck = Storyboard.build(title = "Keys") {
        scene(frames = listOf("a", "b", "c")) { Text("Scene 0") }
        scene(frames = listOf("d")) { Text("Scene 1") }
    }

    /** Presentation clickers send PageDown/PageUp (some Space/Enter); keyboards use ↓/↑ too. */
    @Test
    fun clickerAndKeyboardKeysAdvanceAndGoBack() = runComposeUiTest {
        lateinit var animatic: Animatic
        setContent {
            animatic = rememberAnimatic(storyboard = { deck })
            EaselOverview(animatic) { Easel(animatic) }
        }
        waitForIdle()

        val steps = listOf(
            // Arrows already worked; they prove keys reach the stage in this harness.
            Key.DirectionRight to Storyboard.Index(0, 1),
            Key.DirectionLeft to Storyboard.Index(0, 0),
            Key.PageDown to Storyboard.Index(0, 1),
            Key.Spacebar to Storyboard.Index(0, 2),
            Key.Enter to Storyboard.Index(1, 0),
            Key.PageUp to Storyboard.Index(0, 2),
            Key.DirectionUp to Storyboard.Index(0, 1),
            Key.Backspace to Storyboard.Index(0, 0),
            Key.DirectionDown to Storyboard.Index(0, 1),
        )
        for ((key, expected) in steps) {
            onRoot().performKeyInput { pressKey(key) }
            waitUntil(conditionDescription = "$key moves to $expected", timeoutMillis = 5_000) { animatic.currentIndex == expected }
        }
    }

    /**
     * Esc opens the overview; a second Esc must close it again. (The overview already moves the
     * deck live as its selection moves, so closing keeps the selected slide.)
     */
    @Test
    fun secondEscapeClosesTheOverview() = runComposeUiTest {
        lateinit var animatic: Animatic
        setContent {
            animatic = rememberAnimatic(storyboard = { deck })
            EaselOverview(animatic) { Easel(animatic) }
        }
        waitForIdle()
        fun sceneOneNodes() = onAllNodesWithText("Scene 1").fetchSemanticsNodes().size
        fun sceneZeroNodes() = onAllNodesWithText("Scene 0").fetchSemanticsNodes().size

        onRoot().performKeyInput { pressKey(Key.Escape) }
        waitUntil(conditionDescription = "first Esc opens the overview", timeoutMillis = 5_000) { sceneOneNodes() > 0 && sceneZeroNodes() > 1 }
        mainClock.advanceTimeBy(1_000)

        onRoot().performKeyInput { pressKey(Key.Escape) }
        waitUntil(conditionDescription = "second Esc closes the overview", timeoutMillis = 5_000) { sceneOneNodes() == 0 && sceneZeroNodes() == 1 }
        assertEquals(Storyboard.Index(0, 0), animatic.currentIndex)

        // The stage has the keyboard again: → still presents.
        onRoot().performKeyInput { pressKey(Key.DirectionRight) }
        waitUntil(conditionDescription = "→ advances after closing the overview", timeoutMillis = 5_000) { animatic.currentIndex == Storyboard.Index(0, 1) }
    }
}
