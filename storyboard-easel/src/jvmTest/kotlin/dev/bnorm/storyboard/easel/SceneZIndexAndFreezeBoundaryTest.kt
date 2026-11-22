package dev.bnorm.storyboard.easel

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.bnorm.storyboard.AdvanceDirection
import dev.bnorm.storyboard.ContentDecorator
import dev.bnorm.storyboard.SceneFormat
import dev.bnorm.storyboard.Storyboard
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo

@OptIn(ExperimentalComposeUiApi::class)
class SceneZIndexAndFreezeBoundaryTest {

    private val stageFormat = SceneFormat(IntSize(64, 64), Density(1f))

    /**
     * Builds a 2-scene deck where both scenes remain 100% opaque across the center pixel during
     * the entire 300ms cross-scene transition (sliding by 2px on a 64x64 stage so
     * `effectiveDurationNanos` is 300ms while the center pixel stays completely opaque), so the
     * center pixel unambiguously reveals whichever scene sibling is stacked on top.
     */
    private fun overlappingDeck(
        sceneZIndex: ((Storyboard.Index) -> Float)? = null,
        decorator: ContentDecorator = ContentDecorator.None,
    ): Storyboard = Storyboard.build(
        title = "OverlappingZIndexDeck",
        format = stageFormat,
        decorator = decorator,
        sceneZIndex = sceneZIndex,
    ) {
        val opaqueHoldEnter: (AdvanceDirection) -> EnterTransition = {
            slideInHorizontally(animationSpec = tween(durationMillis = 300, easing = LinearEasing)) { 2 }
        }
        val opaqueHoldExit: (AdvanceDirection) -> ExitTransition = {
            slideOutHorizontally(animationSpec = tween(durationMillis = 300, easing = LinearEasing)) { -2 }
        }
        scene(
            frames = listOf(0),
            enterTransition = opaqueHoldEnter,
            exitTransition = opaqueHoldExit,
        ) {
            Box(Modifier.fillMaxSize().background(Color.Red))
        }
        scene(
            frames = listOf(0),
            enterTransition = opaqueHoldEnter,
            exitTransition = opaqueHoldExit,
        ) {
            Box(Modifier.fillMaxSize().background(Color.Blue))
        }
    }

    private fun Image.centerRgb(): Triple<Int, Int, Int> {
        val info = ImageInfo(width, height, ColorType.RGBA_8888, ColorAlphaType.PREMUL)
        val bitmap = Bitmap().apply { allocPixels(info) }
        try {
            check(readPixels(bitmap)) { "Failed to read pixels from rendered Image" }
            val bytes = checkNotNull(
                bitmap.readPixels(dstInfo = info, dstRowBytes = width * 4, srcX = 0, srcY = 0)
            )
            val cx = width / 2
            val cy = height / 2
            val offset = (cy * width + cx) * 4
            return Triple(
                bytes[offset].toInt() and 0xFF,
                bytes[offset + 1].toInt() and 0xFF,
                bytes[offset + 2].toInt() and 0xFF,
            )
        } finally {
            bitmap.close()
        }
    }

    /**
     * Advances [storyboard] along [direction] and samples the midpoint pixel while both scenes are
     * actively mounted during the 300ms cross-scene transition.
     */
    private suspend fun sampleMidTransitionRgb(
        storyboard: Storyboard,
        initialIndex: Storyboard.Index,
        direction: AdvanceDirection,
        easelSceneZIndexOverride: ((Storyboard.Index) -> Float)? = storyboard.sceneZIndex,
    ): Triple<Int, Int, Int> {
        var animatic: Animatic? = null
        var scope: CoroutineScope? = null
        val scene = ImageComposeScene(
            width = stageFormat.size.width,
            height = stageFormat.size.height,
            density = stageFormat.density,
            coroutineContext = Dispatchers.Unconfined,
        ) {
            scope = rememberCoroutineScope()
            val a = rememberAnimatic(initialIndex = initialIndex, storyboard = { storyboard })
            animatic = a
            Easel(a, mode = SceneMode.Preview, sceneZIndex = easelSceneZIndexOverride)
        }
        try {
            var tick = 0L
            fun stepNanos(): Long = (tick++) * 16_666_667L

            repeat(3) { scene.render(stepNanos()).close() }
            val activeAnimatic = checkNotNull(animatic)
            val activeScope = checkNotNull(scope)

            val job = activeScope.launch { activeAnimatic.advance(direction) }
            try {
                // Step past initial settle frames into the middle of the 300ms transition (~150ms = 9 live frames).
                var liveFrames = 0
                var sampled: Triple<Int, Int, Int>? = null
                var guard = 0
                while (!job.isCompleted && guard++ < 120) {
                    val image = scene.render(stepNanos())
                    try {
                        if (!activeAnimatic.scrubSettling) {
                            liveFrames++
                            if (liveFrames == 8) {
                                sampled = image.centerRgb()
                                break
                            }
                        }
                    } finally {
                        image.close()
                    }
                }
                return checkNotNull(sampled) { "Did not reach mid-transition live frame (liveFrames=$liveFrames)" }
            } finally {
                job.cancel()
            }
        } finally {
            scene.close()
        }
    }

    @Test
    fun nullSceneZIndexPreservesLegacyTravelOrderOnForwardAndBackward() = runTest {
        val deck = overlappingDeck(sceneZIndex = null)

        val forwardRgb = sampleMidTransitionRgb(
            storyboard = deck,
            initialIndex = Storyboard.Index(0, 0),
            direction = AdvanceDirection.Forward,
        )
        // Legacy travel order: incoming Scene 1 (Blue) renders over outgoing Scene 0 (Red).
        assertEquals(Triple(0, 0, 255), forwardRgb, "Forward travel with sceneZIndex=null must stack incoming B (Blue) on top")

        val backwardRgb = sampleMidTransitionRgb(
            storyboard = deck,
            initialIndex = Storyboard.Index(1, 0),
            direction = AdvanceDirection.Backward,
        )
        // Legacy travel order: incoming Scene 0 (Red) renders over outgoing Scene 1 (Blue).
        assertEquals(Triple(255, 0, 0), backwardRgb, "Backward travel with sceneZIndex=null must stack incoming A (Red) on top")
    }

    @Test
    fun canonicalBOverASceneZIndexStacksBOnTopInBothDirections() = runTest {
        // B over A (dissolve / push / slide / wipe / iris-in / magicMove)
        val deck = overlappingDeck(sceneZIndex = { it.sceneIndex.toFloat() })

        val forwardRgb = sampleMidTransitionRgb(
            storyboard = deck,
            initialIndex = Storyboard.Index(0, 0),
            direction = AdvanceDirection.Forward,
        )
        assertEquals(Triple(0, 0, 255), forwardRgb, "Forward B-over-A must stack Scene 1 (Blue) on top")

        val backwardRgb = sampleMidTransitionRgb(
            storyboard = deck,
            initialIndex = Storyboard.Index(1, 0),
            direction = AdvanceDirection.Backward,
        )
        assertEquals(Triple(0, 0, 255), backwardRgb, "Backward B-over-A must still stack Scene 1 (Blue) on top")
    }

    @Test
    fun canonicalAOverBSceneZIndexStacksAOnTopInBothDirections() = runTest {
        // A over B (reveal / iris-out)
        val deck = overlappingDeck(sceneZIndex = { -it.sceneIndex.toFloat() })

        val forwardRgb = sampleMidTransitionRgb(
            storyboard = deck,
            initialIndex = Storyboard.Index(0, 0),
            direction = AdvanceDirection.Forward,
        )
        assertEquals(Triple(255, 0, 0), forwardRgb, "Forward A-over-B must stack Scene 0 (Red) on top")

        val backwardRgb = sampleMidTransitionRgb(
            storyboard = deck,
            initialIndex = Storyboard.Index(1, 0),
            direction = AdvanceDirection.Backward,
        )
        assertEquals(Triple(255, 0, 0), backwardRgb, "Backward A-over-B must still stack Scene 0 (Red) on top")
    }

    @Test
    fun decoratorRunsInsideFixedSizeStageReceivesMountLocalTransitionBeforeZIndexAndFreezesWithStage() = runTest {
        // Verify decorator executes inside FixedSize(1920x1080 @ Density 2 -> 960.dp x 540.dp),
        // receives LocalStoryboardTransition BEFORE sceneZIndex runs, is isolated per mount,
        // and its overlay pixels are captured inside Easel's freeze boundary.
        val hdFormat = SceneFormat(IntSize(1920, 1080), Density(2f))
        val events = mutableListOf<String>()
        var observedDecoratorWidthDp = -1f
        var observedDecoratorHeightDp = -1f
        var observedTransitionInDecorator: Any? = null
        var overlayColor by mutableStateOf(Color.Green)

        val decorator = ContentDecorator { content ->
            val transition = LocalStoryboardTransition.current
            observedTransitionInDecorator = transition
            events += "decorator"
            BoxWithConstraints(Modifier.fillMaxSize()) {
                observedDecoratorWidthDp = maxWidth.value
                observedDecoratorHeightDp = maxHeight.value
                content()
                // Host-level overlay (e.g., Magic Move travelling overlay or fade-through-color cover).
                Box(Modifier.fillMaxSize().background(overlayColor))
            }
        }

        val deck = Storyboard.build(
            title = "DecoratorFreezeBoundaryDeck",
            format = hdFormat,
            decorator = decorator,
            sceneZIndex = { index ->
                events += "zIndex:${index.sceneIndex}"
                index.sceneIndex.toFloat()
            },
        ) {
            scene(frames = listOf(0, 1)) {
                Box(Modifier.fillMaxSize().background(Color.Red))
            }
            scene(frames = listOf(0)) {
                Box(Modifier.fillMaxSize().background(Color.Blue))
            }
        }

        var animatic: Animatic? = null
        var scope: CoroutineScope? = null
        val scene = ImageComposeScene(
            width = 320,
            height = 180,
            density = Density(1f),
            coroutineContext = Dispatchers.Unconfined,
        ) {
            scope = rememberCoroutineScope()
            val a = rememberAnimatic(storyboard = { deck })
            animatic = a
            Easel(a, mode = SceneMode.Story, modifier = Modifier.fillMaxSize())
        }
        try {
            var tick = 0L
            fun stepNanos(): Long = (tick++) * 16_666_667L

            val liveFrame = scene.render(stepNanos())
            try {
                assertEquals(Triple(0, 255, 0), liveFrame.centerRgb(), "Live stage must show Green decorator overlay")
            } finally {
                liveFrame.close()
            }

            assertNotNull(observedTransitionInDecorator, "Decorator must observe mount-local LocalStoryboardTransition")
            assertEquals(960f, observedDecoratorWidthDp, "Decorator must run inside FixedSize 1920px @ 2x = 960.dp width")
            assertEquals(540f, observedDecoratorHeightDp, "Decorator must run inside FixedSize 1080px @ 2x = 540.dp height")
            val firstDecoratorIdx = events.indexOf("decorator")
            val firstZIndexIdx = events.indexOfFirst { it.startsWith("zIndex:") }
            assertTrue(
                firstDecoratorIdx in 0 until firstZIndexIdx,
                "Decorator must compose before sceneZIndex is evaluated (events=$events)",
            )

            // Verify freeze boundary: trigger a scrub (which captures presentedFrame including
            // the Green decorator overlay and sets scrubSettling = true). Flip live overlayColor to
            // Magenta while frozen; the rendered stage must still display Green from frozenFrame!
            val activeAnimatic = checkNotNull(animatic)
            val activeScope = checkNotNull(scope)
            val scrubJob = activeScope.launch {
                activeAnimatic.seekToSegment(fromStateIndex = 1, fraction = 0.5f, rebuildScene = true)
            }
            try {
                var sawFrozenGreen = false
                var guard = 0
                while (!scrubJob.isCompleted && guard++ < 30) {
                    if (activeAnimatic.scrubSettling) {
                        overlayColor = Color.Magenta
                    }
                    val frame = scene.render(stepNanos())
                    try {
                        if (activeAnimatic.scrubSettling && frame.centerRgb() == Triple(0, 255, 0)) {
                            sawFrozenGreen = true
                        }
                    } finally {
                        frame.close()
                    }
                }
                assertTrue(
                    sawFrozenGreen,
                    "Decorator overlay pixels must be captured inside Easel's freeze boundary during scrubSettling",
                )
            } finally {
                scrubJob.cancel()
            }
        } finally {
            scene.close()
        }

        // Concurrent-mount isolation check: two Easels mounting the same
        // Storyboard receive distinct mount-local Transition instances.
        val transitionsByMount = mutableListOf<Any?>()
        val isolationDeck = Storyboard.build(
            title = "ConcurrentMounts",
            format = stageFormat,
            decorator = ContentDecorator { content ->
                transitionsByMount += LocalStoryboardTransition.current
                content()
            },
        ) {
            scene(frames = listOf(0)) { Box(Modifier.size(10.dp)) }
        }
        val dualScene = ImageComposeScene(
            width = 128,
            height = 64,
            density = Density(1f),
            coroutineContext = Dispatchers.Unconfined,
        ) {
            val a1 = rememberAnimatic(storyboard = { isolationDeck })
            val a2 = rememberAnimatic(storyboard = { isolationDeck })
            Easel(a1, mode = SceneMode.Preview)
            Easel(a2, mode = SceneMode.Preview)
        }
        try {
            dualScene.render(0L).close()
            assertEquals(2, transitionsByMount.size, "Expected two mount compositions")
            assertNotNull(transitionsByMount[0])
            assertNotNull(transitionsByMount[1])
            assertNotSame(
                transitionsByMount[0],
                transitionsByMount[1],
                "Concurrent Easel mounts must observe independent mount-local transitions",
            )
        } finally {
            dualScene.close()
        }
    }
}
