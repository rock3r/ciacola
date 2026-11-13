package dev.bnorm.storyboard.easel

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.bnorm.storyboard.Storyboard
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@OptIn(ExperimentalTestApi::class)
class EaselFreezeCaptureTest {

    /**
     * The presented-frame layer only references its children's render nodes, and every scrub step
     * disposes the scene subtree (it is keyed on [Animatic.scrubEpoch]). Snapshotting a layer that
     * was recorded under an older epoch would draw freed native nodes (a SIGBUS in Skia was seen
     * in a long scrub session), so the freeze must refuse such a layer.
     */
    @Test
    fun freezeRefusesToSnapshotALayerRecordedUnderAnOlderScrubEpoch() = runComposeUiTest {
        mainClock.autoAdvance = false
        var drawStage by mutableStateOf(true)
        val deck = Storyboard.build(title = "FreezeDeck") {
            scene(frames = listOf("a", "b")) { Text("Scene 0") }
            scene(frames = listOf("c", "d")) { Text("Scene 1") }
        }
        lateinit var animatic: Animatic
        lateinit var scope: CoroutineScope
        setContent {
            scope = rememberCoroutineScope()
            Box(Modifier.size(200.dp, 120.dp).drawWithContent { if (drawStage) drawContent() }) {
                animatic = rememberAnimatic(storyboard = { deck })
                Easel(animatic)
            }
        }
        mainClock.advanceTimeBy(200)

        fun freezeNow(): Boolean? {
            var fresh: Boolean? = null
            runOnIdle { scope.launch { fresh = checkNotNull(animatic.freezeCapture).invoke() } }
            repeat(3) { mainClock.advanceTimeByFrame() }
            return fresh
        }

        assertEquals(true, freezeNow(), "A layer recorded under the current epoch is snapshotted")

        // Stop drawing the stage, then scrub: the scene subtree is rebuilt under a new epoch but
        // never drawn, so the presented-frame layer still references the disposed subtree.
        runOnIdle { drawStage = false }
        mainClock.advanceTimeByFrame()
        lateinit var job: Job
        runOnIdle { job = scope.launch { animatic.seekToSegment(fromStateIndex = 2, fraction = 0.5f) } }
        repeat(120) { if (!job.isCompleted) mainClock.advanceTimeByFrame() }
        assertTrue(job.isCompleted, "Scrub finished")

        assertEquals(false, freezeNow(), "A layer recorded under an older scrub epoch must not be snapshotted")
    }
}
