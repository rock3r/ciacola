package dev.bnorm.storyboard.easel

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.createChildTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.bnorm.storyboard.AdvanceDirection
import dev.bnorm.storyboard.Frame
import dev.bnorm.storyboard.Storyboard
import dev.bnorm.storyboard.toValue
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

    /**
     * Regression for MagicText mid-transition interruption: when Beat 0 -> Beat 1 is interrupted
     * by a second advance to Beat 2, a shared-bounds key that only becomes canonical when Beat 1
     * is the settled `from` state (e.g. `c:0`, whereas during Beat 0 -> Beat 1 it was `stable:1`)
     * must lay out at Beat 1's source position before Beat 2 mounts, instead of teleporting straight
     * to Beat 2's target position at fraction 0.
     */
    @OptIn(ExperimentalSharedTransitionApi::class)
    @Test
    fun interruptedSegmentSettlesCanonicalSourceBoundsBeforeNextSegmentStarts() = runComposeUiTest {
        mainClock.autoAdvance = false
        val threeBeatDeck = Storyboard.build(title = "InterruptedSharedBounds") {
            scene(frames = listOf(0, 1, 2)) {
                val beat = transition.createChildTransition(label = "beat") { it.toValue() }
                SharedTransitionLayout {
                    beat.DeterministicStateHost { b ->
                        // Emulate MagicTextPlanner: a token inserted at Beat 1 has key "stable:1"
                        // while Beat 0 -> Beat 1 runs, then switches to canonical "c:0" when Beat 1
                        // is `from` on Beat 1 -> Beat 2.
                        val sharedKey = when {
                            beat.currentState == 0 && b == 1 -> "stable:1"
                            b >= 1 -> "c:0"
                            else -> "unused:0"
                        }
                        Row {
                            if (b == 2) Spacer(Modifier.width(180.dp))
                            Box(
                                Modifier
                                    .size(24.dp)
                                    .sharedBounds(
                                        sharedContentState = rememberSharedContentState(sharedKey),
                                        animatedVisibilityScope = this@DeterministicStateHost,
                                        enter = EnterTransition.None,
                                        exit = ExitTransition.None,
                                        boundsTransform = { _, _ -> tween(300, delayMillis = 300, easing = LinearEasing) },
                                    )
                                    .then(if (b == beat.targetState) Modifier.testTag("moving-token") else Modifier),
                            )
                        }
                    }
                }
            }
        }

        lateinit var animatic: Animatic
        lateinit var scope: CoroutineScope
        var densityPx = 1f
        setContent {
            densityPx = density.density
            scope = rememberCoroutineScope()
            Box(Modifier.size(400.dp, 120.dp)) {
                animatic = rememberAnimatic(storyboard = { threeBeatDeck })
                Easel(animatic)
            }
        }
        mainClock.advanceTimeBy(200)

        // Start Beat 0 -> Beat 1 and interrupt mid-flight (~160 ms in) with Beat 1 -> Beat 2.
        lateinit var firstJob: Job
        runOnIdle { firstJob = scope.launch { animatic.advance(AdvanceDirection.Forward) } }
        repeat(10) { mainClock.advanceTimeByFrame() }
        assertTrue(!firstJob.isCompleted, "First advance must still be in flight when interrupted")

        lateinit var secondJob: Job
        runOnIdle { secondJob = scope.launch { animatic.advance(AdvanceDirection.Forward) } }

        var firstLiveLeftPx: Float? = null
        var safety = 0
        while (!secondJob.isCompleted && safety++ < 200) {
            mainClock.advanceTimeByFrame()
            val (settling, frac) = runOnIdle { animatic.scrubSettling to animatic.activeFraction }
            if (firstLiveLeftPx == null && !settling && frac > 0f) {
                firstLiveLeftPx = onNodeWithTag("moving-token").fetchSemanticsNode().boundsInRoot.left
            }
        }
        assertTrue(secondJob.isCompleted, "Second advance finished")
        val initialLeft = assertNotNull(firstLiveLeftPx, "Observed first live frame of Beat 1 -> Beat 2")
        // During the 300 ms move delay of Beat 1 -> Beat 2, the token must sit at Beat 1's x = 0 dp,
        // NOT teleported to Beat 2's x = 180 dp.
        assertTrue(
            initialLeft < 20f * densityPx,
            "Token teleported to x=${initialLeft / densityPx}dp at the start of Beat 1 -> Beat 2 instead of starting at 0dp",
        )
    }
}
