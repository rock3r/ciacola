package dev.sebastiano.ciacola.a11y

import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import dev.bnorm.storyboard.AdvanceDirection
import dev.bnorm.storyboard.Storyboard
import dev.bnorm.storyboard.easel.Easel
import dev.bnorm.storyboard.easel.ScenePreview
import dev.bnorm.storyboard.easel.rememberAnimatic
import dev.sebastiano.ciacola.AnimatedMeshBackdrop
import dev.sebastiano.ciacola.DeckMagicText
import dev.sebastiano.ciacola.DeckTextRendererProvider
import dev.sebastiano.ciacola.MagicTextV1Renderer
import dev.sebastiano.ciacola.createFoundationDeck
import dev.sebastiano.ciacola.effects.DeckWarpCodeCard
import dev.sebastiano.ciacola.effects.animateBuildProgress
import dev.sebastiano.ciacola.effects.animateStaggerGroup
import dev.sebastiano.ciacola.effects.keynoteFadeMove
import dev.sebastiano.ciacola.effects.keynoteFadeMoveTracked
import dev.sebastiano.ciacola.effects.keynoteFromDarkness
import dev.sebastiano.ciacola.effects.keynotePop
import dev.sebastiano.ciacola.effects.keynoteWipe
import dev.sebastiano.ciacola.theme.LocalAmbientLoopsPaused
import dev.sebastiano.ciacola.theme.LocalReducedMotion
import dev.sebastiano.ciacola.theme.REDUCED_MOTION_FADE_DURATION_MS
import dev.sebastiano.ciacola.theme.ReducedMotionMode
import dev.sebastiano.ciacola.theme.SystemReducedMotionDetector
import dev.sebastiano.ciacola.theme.parseReducedMotionEnv
import dev.sebastiano.ciacola.theme.queryOsReduceMotion
import dev.sebastiano.ciacola.theme.resolveEffectiveReducedMotion
import dev.sebastiano.ciacola.timeline.DeckTimelineOverridesFile
import dev.sebastiano.ciacola.timeline.LocalTimelineOverrides
import dev.sebastiano.ciacola.timeline.TimelineSpatialSpec
import dev.sebastiano.ciacola.timeline.TrackOverride
import dev.sebastiano.ciacola.timeline.indexDeckTimeline
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

@OptIn(ExperimentalTestApi::class)
class ReducedMotionTest {

    @Test
    fun reducedMotionEnvParsingAndModeResolutionWorkAsExpected() {
        assertEquals(ReducedMotionMode.Reduced, parseReducedMotionEnv("true"))
        assertEquals(ReducedMotionMode.Reduced, parseReducedMotionEnv("1"))
        assertEquals(ReducedMotionMode.Reduced, parseReducedMotionEnv("reduced"))
        assertEquals(ReducedMotionMode.Full, parseReducedMotionEnv("false"))
        assertEquals(ReducedMotionMode.Full, parseReducedMotionEnv("0"))
        assertEquals(ReducedMotionMode.Full, parseReducedMotionEnv("full"))
        assertEquals(ReducedMotionMode.System, parseReducedMotionEnv(null))
        assertEquals(ReducedMotionMode.System, parseReducedMotionEnv("system"))

        assertTrue(resolveEffectiveReducedMotion(ReducedMotionMode.System, systemReducedMotion = true))
        assertFalse(resolveEffectiveReducedMotion(ReducedMotionMode.System, systemReducedMotion = false))
        assertTrue(resolveEffectiveReducedMotion(ReducedMotionMode.Reduced, systemReducedMotion = false))
        assertFalse(resolveEffectiveReducedMotion(ReducedMotionMode.Full, systemReducedMotion = true))
    }

    @Test
    fun systemDetectorCachesOffEdtAndTimeoutFailureReturnsFalseSafely() = runBlocking {
        SystemReducedMotionDetector.resetForTesting()
        val calls = AtomicInteger(0)
        val first = SystemReducedMotionDetector.detectOnce {
            calls.incrementAndGet()
            true
        }
        val second = SystemReducedMotionDetector.detectOnce {
            calls.incrementAndGet()
            false
        }
        assertTrue(first)
        assertTrue(second, "Cached value must be reused")
        assertEquals(1, calls.get(), "OS query must execute at most once")
        SystemReducedMotionDetector.resetForTesting()

        assertFalse(queryOsReduceMotion(osName = "Linux"))
        assertFalse(queryOsReduceMotion(osName = "Mac OS X", defaultsBinary = "/nonexistent/defaults-binary"))
    }

    @Test
    fun keynoteEffectsSuppressTranslationScaleAndStaggerCascadesUnderReducedMotion() = runComposeUiTest {
        val overrides = DeckTimelineOverridesFile(
            tracks = mapOf(
                "tracked" to TrackOverride(
                    trackId = "tracked",
                    spatial = TimelineSpatialSpec(offsetXDp = 40f, offsetYDp = -30f),
                ),
            ),
        )
        var density = 1f
        var fadeMoveBounds = Rect.Zero
        var trackedBounds = Rect.Zero
        var darknessBounds = Rect.Zero
        var popBounds = Rect.Zero

        setContent {
            density = this.density.density
            CompositionLocalProvider(
                LocalReducedMotion provides true,
                LocalTimelineOverrides provides overrides,
            ) {
                Box(Modifier.size(400.dp)) {
                    Box(
                        Modifier
                            .offset(100.dp, 100.dp)
                            .size(50.dp)
                            .keynoteFadeMove(progress = { 0.25f })
                            .onGloballyPositioned { fadeMoveBounds = it.boundsInRoot() },
                    )
                    Box(
                        Modifier
                            .offset(100.dp, 100.dp)
                            .size(50.dp)
                            .keynoteFadeMoveTracked(trackId = "tracked", progress = { 0.25f })
                            .onGloballyPositioned { trackedBounds = it.boundsInRoot() },
                    )
                    Box(
                        Modifier
                            .offset(100.dp, 100.dp)
                            .size(50.dp)
                            .keynoteFromDarkness(
                                progress = { 0.25f },
                                entranceOffset = DpOffset(20.dp, 20.dp),
                            )
                            .onGloballyPositioned { darknessBounds = it.boundsInRoot() },
                    )
                    Box(
                        Modifier
                            .offset(100.dp, 100.dp)
                            .size(50.dp)
                            .keynotePop(
                                progress = { 0.25f },
                                entranceOffset = DpOffset(20.dp, 20.dp),
                            )
                            .onGloballyPositioned { popBounds = it.boundsInRoot() },
                    )
                }
            }
        }
        waitForIdle()

        val expectedLeft = 100f * density
        val expectedTop = 100f * density
        val expectedSize = 50f * density
        for ((name, rect) in listOf(
            "keynoteFadeMove" to fadeMoveBounds,
            "keynoteFadeMoveTracked" to trackedBounds,
            "keynoteFromDarkness" to darknessBounds,
            "keynotePop" to popBounds,
        )) {
            assertEquals(expectedLeft, rect.left, 0.5f, "$name must have zero X translation")
            assertEquals(expectedTop, rect.top, 0.5f, "$name must have zero Y translation")
            assertEquals(expectedSize, rect.width, 0.5f, "$name must have zero X scale")
            assertEquals(expectedSize, rect.height, 0.5f, "$name must have zero Y scale")
        }
    }

    @Test
    fun keynoteWipeDegradesToOpacityFadeWithoutDirectionalClipUnderReducedMotion() = runComposeUiTest {
        setContent {
            CompositionLocalProvider(LocalReducedMotion provides true) {
                Box(
                    Modifier
                        .size(100.dp, 40.dp)
                        .testTag("wipe-box")
                        .keynoteWipe(progress = { 0.5f }),
                ) {
                    androidx.compose.foundation.Canvas(Modifier.size(100.dp, 40.dp)) {
                        drawRect(Color.Red)
                    }
                }
            }
        }
        waitForIdle()

        val pixels = onNodeWithTag("wipe-box").captureToImage().toPixelMap()
        // At progress=0.5f under reduced motion, the right half must NOT be clipped away.
        val leftPixel = pixels[pixels.width / 4, pixels.height / 2]
        val rightPixel = pixels[(pixels.width * 3) / 4, pixels.height / 2]
        assertTrue(leftPixel.red > 0.3f, "Expected left half visible, got $leftPixel")
        assertEquals(leftPixel.red, rightPixel.red, 0.05f, "Right half must fade uniformly with left half")
        assertEquals(leftPixel.alpha, rightPixel.alpha, 0.05f)
    }

    @Test
    fun buildAndStaggerAnimationsCompleteWithin150MsWithoutCascadeUnderReducedMotion() = runComposeUiTest {
        mainClock.autoAdvance = false
        var visible by mutableStateOf(false)
        var buildVal = 0f
        var item0 = 0f
        var item1 = 0f
        var item2 = 0f

        setContent {
            CompositionLocalProvider(LocalReducedMotion provides true) {
                val t = updateTransition(visible)
                val b by t.animateBuildProgress(durationMillis = 400, visible = { it })
                val group = t.animateStaggerGroup(count = 3, itemDurationMs = 400, staggerStepMs = 120, visible = { it })
                val bNow = b
                val i0 = group.progressFor(0)
                val i1 = group.progressFor(1)
                val i2 = group.progressFor(2)
                SideEffect {
                    buildVal = bNow
                    item0 = i0
                    item1 = i1
                    item2 = i2
                }
            }
        }
        mainClock.advanceTimeByFrame()
        runOnIdle { visible = true }

        // Mid-transition (~64 ms): stagger items must advance in lockstep (no cascade!).
        repeat(4) { mainClock.advanceTimeByFrame() }
        assertTrue(item0 > 0.1f && item0 < 1f, "Expected mid-fade progress, got $item0")
        assertEquals(item0, item1, 0.001f, "Stagger item 1 must match item 0 under reduced motion")
        assertEquals(item0, item2, 0.001f, "Stagger item 2 must match item 0 under reduced motion")

        // Past REDUCED_MOTION_FADE_DURATION_MS (150 ms): both build and stagger must be settled at 1f!
        mainClock.advanceTimeBy(REDUCED_MOTION_FADE_DURATION_MS.toLong() + 32L)
        waitForIdle()
        assertEquals(1f, buildVal, 0.001f)
        assertEquals(1f, item0, 0.001f)
        assertEquals(1f, item2, 0.001f)
    }

    @Test
    fun animatedMeshBackdropFreezesUnderReducedMotionAndWhenExplicitlyPaused() = runComposeUiTest {
        mainClock.autoAdvance = false
        var reduced by mutableStateOf(true)
        var paused by mutableStateOf(false)

        setContent {
            CompositionLocalProvider(
                LocalReducedMotion provides reduced,
                LocalAmbientLoopsPaused provides paused,
            ) {
                AnimatedMeshBackdrop(Modifier.size(160.dp, 90.dp).testTag("mesh"))
            }
        }
        mainClock.advanceTimeByFrame()
        val reducedT0 = onNodeWithTag("mesh").captureToImage().toPixelMap()
        mainClock.advanceTimeBy(1_500L)
        val reducedT1 = onNodeWithTag("mesh").captureToImage().toPixelMap()
        assertPixelsMatch(reducedT0, reducedT1, "Mesh must stay frozen under reduced motion")

        // Full motion + explicit pause (WCAG 2.2.2) also freezes the loop.
        runOnIdle {
            reduced = false
            paused = true
        }
        mainClock.advanceTimeByFrame()
        val pausedT0 = onNodeWithTag("mesh").captureToImage().toPixelMap()
        mainClock.advanceTimeBy(1_500L)
        val pausedT1 = onNodeWithTag("mesh").captureToImage().toPixelMap()
        assertPixelsMatch(pausedT0, pausedT1, "Mesh must stay frozen when paused in Full Motion mode")

        // Unpausing in Full Motion resumes animation.
        runOnIdle { paused = false }
        mainClock.advanceTimeByFrame()
        val liveT0 = onNodeWithTag("mesh").captureToImage().toPixelMap()
        mainClock.advanceTimeBy(1_500L)
        val liveT1 = onNodeWithTag("mesh").captureToImage().toPixelMap()
        assertFalse(pixelsMatch(liveT0, liveT1), "Mesh must animate when unpaused in Full Motion mode")
    }

    @Test
    fun magicTextWarpCodeAndFoundationDeckCrossfadeCleanlyUnderReducedMotion() = runComposeUiTest {
        var warpStep by mutableIntStateOf(0)
        var prose by mutableStateOf(AnnotatedString("First beat"))
        lateinit var animatic: dev.bnorm.storyboard.easel.Animatic
        lateinit var scope: CoroutineScope
        val deck = createFoundationDeck(includeKeynoteEffectsLab = true, reducedMotion = true)

        setContent {
            scope = rememberCoroutineScope()
            CompositionLocalProvider(LocalReducedMotion provides true) {
                Box(Modifier.size(960.dp, 540.dp)) {
                    DeckTextRendererProvider(MagicTextV1Renderer) {
                        DeckMagicText(updateTransition(prose))
                    }
                    DeckWarpCodeCard(
                        codeStates = listOf("val a = 1", "val a = 2\nval b = 3"),
                        stateTransition = updateTransition(warpStep),
                    )
                    animatic = rememberAnimatic(storyboard = { deck })
                    Easel(animatic)
                }
            }
        }
        waitForIdle()

        runOnIdle {
            prose = AnnotatedString("Second beat")
            warpStep = 1
        }
        mainClock.advanceTimeBy(REDUCED_MOTION_FADE_DURATION_MS.toLong() + 100L)
        waitForIdle()
        onNodeWithText("Second beat").assertExists()

        // Navigate across Slide 1 -> Slide 2 (SharedMove) under reduced motion.
        repeat(3) {
            runOnIdle { scope.launch { animatic.advance(AdvanceDirection.Forward) } }
            mainClock.advanceTimeBy(400)
            waitForIdle()
        }
        assertEquals(Storyboard.Index(1, 0), animatic.currentIndex)
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun offscreenSceneAndTimelineMeasurementObserveReducedMotionFromDeckDecorator() {
        var observedReduced = false
        val probeDecorator = dev.bnorm.storyboard.ContentDecorator { content ->
            observedReduced = LocalReducedMotion.current
            content()
        }
        val reducedDeck = createFoundationDeck(
            includeKeynoteEffectsLab = true,
            stageDecorator = probeDecorator,
            reducedMotion = true,
        )
        val fullDeck = createFoundationDeck(
            includeKeynoteEffectsLab = true,
            reducedMotion = false,
        )

        val scene = ImageComposeScene(
            width = 320,
            height = 180,
            density = reducedDeck.format.density,
            coroutineContext = Dispatchers.Unconfined,
        ) {
            ScenePreview(reducedDeck, Storyboard.Index(0, 0))
        }
        try {
            scene.render(0L).close()
        } finally {
            scene.close()
        }
        assertTrue(observedReduced, "Offscreen ImageComposeScene must observe LocalReducedMotion == true from deckDecorator")

        val fullSnapshot = indexDeckTimeline(fullDeck)
        val reducedSnapshot = indexDeckTimeline(reducedDeck)
        assertTrue(
            reducedSnapshot.totalDurationMs < fullSnapshot.totalDurationMs,
            "Offscreen timeline measurement must use shortened reduced-motion fades (${reducedSnapshot.totalDurationMs}ms vs ${fullSnapshot.totalDurationMs}ms)",
        )
    }

    private fun pixelsMatch(
        a: androidx.compose.ui.graphics.PixelMap,
        b: androidx.compose.ui.graphics.PixelMap,
    ): Boolean {
        if (a.width != b.width || a.height != b.height) return false
        for (y in 0 until a.height step 4) {
            for (x in 0 until a.width step 4) {
                val pa = a[x, y]
                val pb = b[x, y]
                if (abs(pa.red - pb.red) > 0.02f ||
                    abs(pa.green - pb.green) > 0.02f ||
                    abs(pa.blue - pb.blue) > 0.02f
                ) {
                    return false
                }
            }
        }
        return true
    }

    private fun assertPixelsMatch(
        a: androidx.compose.ui.graphics.PixelMap,
        b: androidx.compose.ui.graphics.PixelMap,
        message: String,
    ) {
        assertTrue(pixelsMatch(a, b), message)
    }
}
