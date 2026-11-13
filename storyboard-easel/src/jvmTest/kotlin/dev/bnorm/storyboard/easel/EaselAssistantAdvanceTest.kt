package dev.bnorm.storyboard.easel

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.Text
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.bnorm.storyboard.Storyboard
import dev.bnorm.storyboard.easel.assist.Caption
import dev.bnorm.storyboard.easel.assist.EaselAssistant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class EaselAssistantAdvanceTest {
    @Test
    fun clickingNextFramePreviewAnimatesAdvanceInsteadOfHardCutting() = runComposeUiTest {
        val storyboard = Storyboard.build(title = "AssistantAdvance") {
            scene(
                frames = listOf(Unit),
                enterTransition = { fadeIn(tween(400)) },
                exitTransition = { fadeOut(tween(400)) },
            ) {
                Text("Slide 1")
            }
            scene(
                frames = listOf(Unit),
                enterTransition = { fadeIn(tween(400)) },
                exitTransition = { fadeOut(tween(400)) },
            ) {
                Text("Slide 2")
            }
        }

        lateinit var animatic: Animatic
        setContent {
            animatic = rememberAnimatic(storyboard = { storyboard })
            val captions = remember { mutableStateListOf<Caption>() }
            Box(Modifier.size(640.dp, 360.dp)) {
                Easel(animatic)
            }
            Box(Modifier.size(800.dp, 600.dp)) {
                EaselAssistant(animatic = animatic, captions = captions)
            }
        }
        waitForIdle()
        assertEquals(Storyboard.Index(0, 0), animatic.currentIndex)

        mainClock.autoAdvance = false
        onAllNodes(hasClickAction()).onLast().performClick()
        mainClock.advanceTimeBy(120)

        assertEquals(
            Storyboard.Index(1, 0),
            animatic.targetIndex,
            "Clicking Next Frame should target the next slide",
        )
        assertEquals(
            Storyboard.Index(0, 0),
            animatic.currentIndex,
            "Mid-transition at 120ms/400ms must still be animating rather than hard-cutting via jumpTo",
        )
        assertTrue(
            animatic.advancementProgress > 0f && animatic.advancementProgress < animatic.advancementDistance,
            "Expected partial transition progress at 120ms, got ${animatic.advancementProgress}/${animatic.advancementDistance}",
        )

        mainClock.autoAdvance = true
        waitForIdle()
        assertEquals(Storyboard.Index(1, 0), animatic.currentIndex)
    }
}
