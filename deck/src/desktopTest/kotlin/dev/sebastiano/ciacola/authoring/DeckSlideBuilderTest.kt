package dev.sebastiano.ciacola.authoring

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.bnorm.storyboard.easel.Animatic
import dev.bnorm.storyboard.easel.Easel
import dev.bnorm.storyboard.easel.rememberAnimatic
import dev.sebastiano.ciacola.effects.DeckEditableText
import dev.sebastiano.ciacola.theme.DeckThemes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@OptIn(ExperimentalTestApi::class)
class DeckSlideBuilderTest {

    @Test
    fun byBeatSlideRecomposesAtMostTwiceAcrossFullZeroToOneSeekToSegmentSweep() = runComposeUiTest {
        mainClock.autoAdvance = false

        var recomposeCount = 0
        var lastObservedLabel = ""
        var lastObservedBeat = -1

        val deck = ciacolaDeck(
            id = "test-bybeat-perf",
            title = "SlideScope DerivedState Perf Test",
            host = DeckHostParams(theme = DeckThemes.keynoteLight()),
        ) {
            slide(key = "s1", title = "ByBeat Sweep", beats = 2) {
                SideEffect {
                    recomposeCount++
                }
                val reveal = animateByBeat(durationMillis = 400) { it.toFloat() }
                val label = byBeat("Beat Zero Copy", "Beat One Copy")
                lastObservedLabel = label
                lastObservedBeat = beat
                DeckEditableText(
                    elementId = "s1:label",
                    text = label,
                    modifier = Modifier.graphicsLayer { alpha = 0.4f + 0.6f * reveal.value },
                )
            }
        }

        lateinit var animatic: Animatic
        lateinit var scope: CoroutineScope
        setContent {
            scope = rememberCoroutineScope()
            Box(Modifier.size(960.dp, 540.dp).testTag("bybeat-stage")) {
                animatic = rememberAnimatic(storyboard = { deck })
                Easel(animatic)
            }
        }
        repeat(4) { mainClock.advanceTimeByFrame() }
        assertEquals("Beat Zero Copy", lastObservedLabel)
        assertEquals(0, lastObservedBeat)
        onNodeWithText("Beat Zero Copy").assertExists()

        // Reset counter before sweeping 21 dense fractions across [0.0, 1.0].
        recomposeCount = 0
        val sweepSteps = 20
        for (step in 0..sweepSteps) {
            val fraction = step / sweepSteps.toFloat()
            lateinit var job: Job
            runOnIdle {
                job = scope.launch {
                    animatic.seekToSegment(fromStateIndex = 0, fraction = fraction, rebuildScene = false)
                }
            }
            var guard = 0
            while (!job.isCompleted && guard++ < 60) {
                mainClock.advanceTimeByFrame()
            }
            mainClock.advanceTimeByFrame()

            if (fraction < 0.49f) {
                assertEquals(
                    "Beat Zero Copy",
                    lastObservedLabel,
                    "Expected Beat 0 value before midpoint at fraction=$fraction",
                )
                assertEquals(0, lastObservedBeat)
            } else if (fraction > 0.51f) {
                assertEquals(
                    "Beat One Copy",
                    lastObservedLabel,
                    "Expected Beat 1 value after midpoint at fraction=$fraction",
                )
                assertEquals(1, lastObservedBeat)
            }
        }

        assertTrue(
            recomposeCount in 1..2,
            "Expected byBeat slide body to recompose at most twice across a 21-step 0->1 seekToSegment sweep, " +
                "got $recomposeCount recompositions",
        )
        onNodeWithText("Beat One Copy").assertExists()

        // Also verify an intra-segment reverse sweep (0.99 -> 0.00) recomposes at most twice and flips back cleanly.
        run {
            lateinit var anchorJob: Job
            runOnIdle {
                anchorJob = scope.launch {
                    animatic.seekToSegment(fromStateIndex = 0, fraction = 0.99f, rebuildScene = false)
                }
            }
            var guard = 0
            while (!anchorJob.isCompleted && guard++ < 60) {
                mainClock.advanceTimeByFrame()
            }
            mainClock.advanceTimeByFrame()
        }
        assertEquals("Beat One Copy", lastObservedLabel)
        recomposeCount = 0
        for (step in 1..sweepSteps) {
            val fraction = 1f - (step / sweepSteps.toFloat())
            lateinit var job: Job
            runOnIdle {
                job = scope.launch {
                    animatic.seekToSegment(fromStateIndex = 0, fraction = fraction, rebuildScene = false)
                }
            }
            var guard = 0
            while (!job.isCompleted && guard++ < 60) {
                mainClock.advanceTimeByFrame()
            }
            mainClock.advanceTimeByFrame()
        }
        assertEquals("Beat Zero Copy", lastObservedLabel)
        assertEquals(0, lastObservedBeat)
        assertTrue(
            recomposeCount in 1..2,
            "Expected reverse intra-segment seekToSegment sweep to recompose at most twice, got $recomposeCount",
        )
    }
}
