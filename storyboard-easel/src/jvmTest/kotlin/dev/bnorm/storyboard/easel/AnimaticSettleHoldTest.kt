package dev.bnorm.storyboard.easel

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.bnorm.storyboard.AdvanceDirection
import dev.bnorm.storyboard.Frame
import dev.bnorm.storyboard.Storyboard
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@OptIn(ExperimentalTestApi::class)
class AnimaticSettleHoldTest {

    private val deck = Storyboard.build(title = "SettleHold") {
        scene(frames = listOf(0, 1)) {
            val x by transition.animateFloat(
                transitionSpec = { tween(durationMillis = 300, easing = LinearEasing) },
                label = "x",
            ) { frame ->
                when (frame) {
                    is Frame.Value -> frame.value.toFloat()
                    else -> 0f
                }
            }
            Text("x=$x")
        }
    }

    /**
     * While the settle frames hold the stage frozen on the resting beat, play time must not
     * advance: the first live frame of a fresh segment starts at (or one frame past) fraction 0
     * instead of lurching ahead by the frozen settle time.
     */
    @Test
    fun freshSegmentStartsFromFractionZeroAfterSettleFreeze() = runComposeUiTest {
        mainClock.autoAdvance = false
        lateinit var animatic: Animatic
        lateinit var scope: CoroutineScope
        setContent {
            scope = rememberCoroutineScope()
            Box(Modifier.size(200.dp, 120.dp)) {
                animatic = rememberAnimatic(storyboard = { deck })
                Easel(animatic)
            }
        }
        mainClock.advanceTimeBy(200)

        lateinit var job: Job
        runOnIdle { job = scope.launch { animatic.advance(AdvanceDirection.Forward) } }
        var firstLiveFraction: Float? = null
        var frames = 0
        while (!job.isCompleted && frames++ < 200) {
            mainClock.advanceTimeByFrame()
            val (settling, fraction) = runOnIdle { animatic.scrubSettling to animatic.activeFraction }
            if (firstLiveFraction == null && !settling && fraction > 0f) firstLiveFraction = fraction
        }
        assertTrue(job.isCompleted, "Advance finished")
        assertEquals(Storyboard.Index(0, 1), animatic.currentIndex)
        val first = assertNotNull(firstLiveFraction, "Segment played at least one live frame")
        // One 16 ms frame of a 300 ms linear tween is ~0.053; the frozen settle frames must not count.
        assertTrue(first <= 0.07f, "First live frame lurched to fraction $first instead of starting near 0")
    }
}
