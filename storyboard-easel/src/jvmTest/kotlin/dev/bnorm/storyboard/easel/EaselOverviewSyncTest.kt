package dev.bnorm.storyboard.easel

import androidx.compose.material.Text
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.bnorm.storyboard.Storyboard
import dev.bnorm.storyboard.easel.overview.EaselOverview
import dev.bnorm.storyboard.easel.overview.OverviewVisibility
import dev.bnorm.storyboard.easel.overview.rememberOverviewVisibility
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@OptIn(ExperimentalTestApi::class)
class EaselOverviewSyncTest {
    private val deck = Storyboard.build(title = "OverviewSync") {
        scene(frames = listOf("a", "b")) { Text("Scene 0") }
        scene(frames = listOf("c", "d", "e")) { Text("Scene 1") }
        scene(frames = listOf("f")) { Text("Scene 2") }
    }

    /**
     * A timeline scrub or step while the overview is open moves the animatic without touching the
     * overview's selection; Enter must then keep the scrubbed position instead of jumping back.
     */
    @Test
    fun externalMoveWhileOverviewIsOpenIsTrackedSoEnterKeepsIt() = runComposeUiTest {
        lateinit var animatic: Animatic
        lateinit var scope: CoroutineScope
        setContent {
            scope = rememberCoroutineScope()
            animatic = rememberAnimatic(storyboard = { deck })
            EaselOverview(animatic) { Easel(animatic) }
        }
        waitForIdle()
        fun overviewOpen() = onAllNodesWithText("Scene 2").fetchSemanticsNodes().isNotEmpty()

        onRoot().performKeyInput { pressKey(Key.Escape) }
        waitUntil(conditionDescription = "Esc opens the overview", timeoutMillis = 5_000) { overviewOpen() }
        mainClock.advanceTimeBy(1_000)

        runOnIdle { scope.launch { animatic.jumpTo(Storyboard.Index(1, 2)) } }
        waitUntil(conditionDescription = "external move lands", timeoutMillis = 5_000) {
            animatic.currentIndex == Storyboard.Index(1, 2)
        }
        mainClock.advanceTimeBy(500)

        onRoot().performKeyInput { pressKey(Key.Enter) }
        waitUntil(conditionDescription = "Enter closes the overview", timeoutMillis = 5_000) { !overviewOpen() }
        assertEquals(Storyboard.Index(1, 2), animatic.currentIndex, "Enter must not clobber the scrubbed position")
    }

    /** The menu bar's Overview item toggles the grid through a hoisted [OverviewVisibility]. */
    @Test
    fun hoistedVisibilityOpensAndClosesTheOverview() = runComposeUiTest {
        lateinit var animatic: Animatic
        lateinit var visibility: OverviewVisibility
        setContent {
            animatic = rememberAnimatic(storyboard = { deck })
            visibility = rememberOverviewVisibility()
            EaselOverview(animatic, visibility = visibility) { Easel(animatic) }
        }
        waitForIdle()
        fun overviewOpen() = onAllNodesWithText("Scene 2").fetchSemanticsNodes().isNotEmpty()

        runOnIdle { visibility.toggle() }
        waitUntil(conditionDescription = "toggle opens the overview", timeoutMillis = 5_000) { overviewOpen() }
        mainClock.advanceTimeBy(1_000)
        runOnIdle { visibility.toggle() }
        waitUntil(conditionDescription = "toggle closes the overview", timeoutMillis = 5_000) { !overviewOpen() }
        assertEquals(Storyboard.Index(0, 0), animatic.currentIndex)
    }
}
