package dev.sebastiano.ciacola.animation

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@OptIn(ExperimentalTestApi::class)
class PlaybackTest {
    @Test
    fun `both formats advance loop pause and restart after leaving composition`() {
        for (name in listOf("sample.gif", "sample.webp")) runComposeUiTest {
            val frames = assertNotNull(decodeAnimatedFrames(sample(name)))
            val composed = mutableStateOf(true)
            val playing = mutableStateOf(true)
            mainClock.autoAdvance = false
            setContent {
                if (composed.value) {
                    Image(
                        rememberAnimatedFrame(frames, playing.value),
                        null,
                        Modifier.size(64.dp, 48.dp).testTag("image"),
                    )
                }
            }
            fun pixel() = onNodeWithTag("image").captureToImage().toPixelMap().let { pixels ->
                pixels[pixels.width * 10 / 64, pixels.height * 20 / 48]
            }
            mainClock.advanceTimeBy(32)
            assertEquals(Color.Red, pixel(), "$name first frame")
            mainClock.advanceTimeBy(112)
            assertEquals(Color.Yellow, pixel(), "$name second frame")
            mainClock.advanceTimeBy(896)
            assertEquals(Color.Red, pixel(), "$name loop")
            runOnIdle { playing.value = false }
            mainClock.advanceTimeBy(32)
            assertEquals(Color.Red, pixel(), "$name inactive resets to first")
            mainClock.advanceTimeBy(512)
            assertEquals(Color.Red, pixel(), "$name inactive does not advance")
            runOnIdle { composed.value = false }
            mainClock.advanceTimeBy(32)
            mainClock.advanceTimeBy(512)
            runOnIdle { composed.value = true; playing.value = true }
            mainClock.advanceTimeBy(32)
            assertEquals(Color.Red, pixel(), "$name re-entry starts at first")
            mainClock.advanceTimeBy(112)
            assertEquals(Color.Yellow, pixel(), "$name re-entry plays again")
        }
    }
}
