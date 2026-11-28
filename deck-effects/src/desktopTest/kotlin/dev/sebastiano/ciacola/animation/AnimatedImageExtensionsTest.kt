package dev.sebastiano.ciacola.animation

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
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
import kotlin.test.assertNull

/** Covers the additions made on top of the vendored library: one-shot playback, lookup, probe. */
@OptIn(ExperimentalTestApi::class)
class AnimatedImageExtensionsTest {
    @Test
    fun `frame lookup follows cumulative durations and clamps to the ends`() {
        val frames = assertNotNull(decodeAnimatedFrames(sample("sample.webp")))
        assertEquals(1000, animationDurationMs(frames))
        assertEquals(listOf(0, 0, 1, 2, 3, 3, 0), listOf(0.0, 99.9, 100.0, 300.0, 999.0, 5000.0, -10.0).map { frameIndexAt(frames, it) })
    }

    @Test
    fun `probe reads size and frame count without treating stills as animations`() {
        assertEquals(AnimationInfo(64, 48, 4), probeAnimation(sample("sample.gif")))
        assertEquals(AnimationInfo(64, 48, 4), probeAnimation(sample("sample.webp")))
        assertNull(probeAnimation(sample("still.gif")))
        assertNull(probeAnimation(sample("still.webp")))
        assertNull(probeAnimation("not an image".toByteArray()))
    }

    @Test
    fun `a still GIF is not checked against the animation budget`() {
        assertNull(decodeAnimatedFrames(sample("still.gif"), AnimationBudget(maxSidePx = 10, maxPixels = 10)))
    }

    @Test
    fun `a GIF with a zero logical screen falls back to its frame size`() {
        val bytes = sample("sample.gif").copyOf().also { for (i in 6..9) it[i] = 0 }
        assertEquals(AnimationInfo(64, 48, 4), probeAnimation(bytes))
        val first = assertNotNull(decodeAnimatedFrames(bytes)).first().first
        assertEquals(64 to 48, first.width to first.height)
    }

    @Test
    fun `non-looping playback holds the last frame`() = runComposeUiTest {
        val frames = assertNotNull(decodeAnimatedFrames(sample("sample.webp")))
        mainClock.autoAdvance = false
        setContent {
            Image(rememberAnimatedFrame(frames, playing = true, loop = false), null, Modifier.size(64.dp, 48.dp).testTag("image"))
        }
        fun pixel() = onNodeWithTag("image").captureToImage().toPixelMap().let { it[it.width * 10 / 64, it.height * 20 / 48] }
        mainClock.advanceTimeBy(1040)
        assertEquals(Color.Blue, pixel(), "last frame reached")
        mainClock.advanceTimeBy(1000)
        assertEquals(Color.Blue, pixel(), "last frame held instead of looping")
    }
}
