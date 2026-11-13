package dev.bnorm.storyboard.easel

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.bnorm.storyboard.Storyboard
import dev.bnorm.storyboard.easel.assist.AssistantWindow
import dev.bnorm.storyboard.easel.assist.rememberAssistantWindow
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class EaselOwnershipTest {
    private fun deck(n: Int) = Storyboard.build(title = "Own$n") {
        scene(frames = listOf("a", "b")) { Text("Scene 0") }
        scene(frames = listOf("c")) { Text("Scene 1") }
    }

    /**
     * A preview Easel on the same animatic (the F2 Assistant's "Current Frame") must not take over,
     * or on dispose clear, the Story Easel's frame clock and freeze capture.
     */
    @Test
    fun previewEaselNeverTakesOrClearsTheStoryEaselsHooks() = runComposeUiTest {
        val deck = deck(0)
        var showPreview by mutableStateOf(false)
        lateinit var animatic: Animatic
        setContent {
            animatic = rememberAnimatic(storyboard = { deck })
            Box(Modifier.size(300.dp, 200.dp)) { Easel(animatic) }
            if (showPreview) Box(Modifier.size(150.dp, 100.dp)) { Easel(animatic, mode = SceneMode.Preview) }
        }
        waitForIdle()
        val storyCapture = assertNotNull(animatic.freezeCapture, "Story Easel registers a freeze capture")
        val storyClock = assertNotNull(animatic.renderFrameClock, "Story Easel registers its frame clock")

        runOnIdle { showPreview = true }
        waitForIdle()
        assertSame(storyCapture, animatic.freezeCapture, "Preview Easel must not replace the Story freeze capture")
        assertSame(storyClock, animatic.renderFrameClock, "Preview Easel must not replace the Story frame clock")

        runOnIdle { showPreview = false }
        waitForIdle()
        assertSame(storyCapture, animatic.freezeCapture, "Disposing the preview must not clear the Story freeze capture")
        assertSame(storyClock, animatic.renderFrameClock, "Disposing the preview must not clear the Story frame clock")
    }

    /** Rebuilding the storyboard (hot reload, theme switch, timeline edits) must not close the Assistant. */
    @Test
    fun assistantWindowSurvivesAStoryboardRebuild() = runComposeUiTest {
        var generation by mutableIntStateOf(0)
        lateinit var window: AssistantWindow
        lateinit var animatic: Animatic
        setContent {
            val deck = remember(generation) { deck(generation) }
            animatic = rememberAnimatic(storyboard = { deck })
            window = rememberAssistantWindow(animatic)
        }
        waitForIdle()
        val first = window
        runOnIdle { first.visible = true }
        val firstAnimatic = animatic

        runOnIdle { generation++ }
        waitForIdle()
        assertTrue(animatic !== firstAnimatic, "The rebuild produced a new animatic")
        assertSame(first, window, "Same Assistant window instance after the rebuild")
        assertTrue(window.visible, "The Assistant stays open after the rebuild")
    }
}
