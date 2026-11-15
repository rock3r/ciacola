package dev.sebastiano.ciacola.timeline

import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.bnorm.storyboard.AdvanceDirection
import dev.bnorm.storyboard.Storyboard
import dev.bnorm.storyboard.easel.Animatic
import dev.bnorm.storyboard.easel.Easel
import dev.bnorm.storyboard.easel.rememberAnimatic
import dev.bnorm.storyboard.toValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Verifies that interactive in-scene `remember { mutableIntStateOf(...) }` specimen state
 * survives both in-place scrubbing back to beat start (`SnapCurrent` via `snapToResting`)
 * and live `-> <- ->` arrow-key reversal without destroying the scene subtree.
 */
@OptIn(ExperimentalTestApi::class)
class SpecimenStateSurvivalTest {

    private fun ComposeUiTest.runOnUi(scope: CoroutineScope, block: suspend () -> Unit) {
        lateinit var job: Job
        runOnIdle { job = scope.launch { block() } }
        var guard = 0
        while (!job.isCompleted && guard++ < 1500) mainClock.advanceTimeByFrame()
        assertTrue(job.isCompleted, "UI coroutine did not complete")
    }

    @Test
    fun interactiveRememberStateSurvivesInPlaceSnapCurrentAndLiveReverse() = runComposeUiTest {
        mainClock.autoAdvance = false
        var observedCounter = -1

        val storyboard = Storyboard.build(title = "SpecimenStateSurvival") {
            scene(frameCount = 2) {
                var counter by remember { mutableIntStateOf(0) }
                observedCounter = counter
                val progress by transition.animateFloat(
                    transitionSpec = { tween(durationMillis = 500) },
                    label = "beat-progress",
                ) { frame ->
                    frame.toValue().toFloat()
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = 0.5f + 0.5f * progress }
                        .testTag("specimen-button")
                        .clickable { counter++ },
                ) {
                    Text("count=$counter")
                }
            }
        }

        lateinit var animatic: Animatic
        lateinit var scope: CoroutineScope
        setContent {
            scope = rememberCoroutineScope()
            Box(Modifier.size(480.dp, 270.dp)) {
                animatic = rememberAnimatic(storyboard = { storyboard })
                Easel(animatic)
            }
        }
        mainClock.advanceTimeBy(300)
        assertEquals(0, observedCounter)

        // Increment specimen counter to 3 via real user clicks.
        repeat(3) {
            onNodeWithTag("specimen-button").performClick()
            mainClock.advanceTimeByFrame()
        }
        mainClock.advanceTimeBy(64)
        assertEquals(3, observedCounter, "Specimen counter should reach 3 after 3 clicks")

        // 1. In-place drag forward -> back to beat start (SnapCurrent) -> forward -> back to start.
        runOnUi(scope) { animatic.seekToSegment(0, 0.5f, rebuildScene = false) }
        repeat(2) { mainClock.advanceTimeByFrame() }
        assertEquals(3, observedCounter, "Specimen counter must survive mid-segment scrub")

        runOnUi(scope) { animatic.seekToSegment(0, 0f, rebuildScene = false) }
        repeat(4) { mainClock.advanceTimeByFrame() }
        assertEquals(3, observedCounter, "Specimen counter must survive SnapCurrent to beat start")

        runOnUi(scope) { animatic.seekToSegment(0, 0.5f, rebuildScene = false) }
        repeat(2) { mainClock.advanceTimeByFrame() }
        assertEquals(3, observedCounter, "Specimen counter must survive re-scrubbing forward")

        runOnUi(scope) { animatic.seekToSegment(0, 0f, rebuildScene = false) }
        repeat(4) { mainClock.advanceTimeByFrame() }
        assertEquals(3, observedCounter, "Specimen counter must survive second SnapCurrent")

        // 2. Live arrow-key `-> <- ->` reversal inside the same scene.
        lateinit var job: Job
        runOnIdle { job = scope.launch { animatic.advance(AdvanceDirection.Forward) } }
        repeat(15) { mainClock.advanceTimeByFrame() }
        assertEquals(3, observedCounter, "Specimen counter must survive live forward mid-flight")

        runOnIdle { job = scope.launch { animatic.advance(AdvanceDirection.Backward) } }
        var guard = 0
        while (!job.isCompleted && guard++ < 400) mainClock.advanceTimeByFrame()
        assertTrue(job.isCompleted, "Live backward reversal did not complete")
        repeat(10) { mainClock.advanceTimeByFrame() }
        assertEquals(3, observedCounter, "Specimen counter must survive live reverse to beat start")

        runOnIdle { job = scope.launch { animatic.advance(AdvanceDirection.Forward) } }
        guard = 0
        while (!job.isCompleted && guard++ < 400) mainClock.advanceTimeByFrame()
        assertTrue(job.isCompleted, "Second live forward advance did not complete")
        repeat(5) { mainClock.advanceTimeByFrame() }
        assertEquals(3, observedCounter, "Specimen counter must survive full -> <- -> cycle")
    }
}
