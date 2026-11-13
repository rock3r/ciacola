package dev.sebastiano.ciacola

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import dev.sebastiano.ciacola.theme.DeckThemes
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.foundation.background
import androidx.compose.ui.platform.testTag
import dev.sebastiano.ciacola.effects.DeckWarpCodeCard
import dev.sebastiano.ciacola.effects.arrowHeadLength
import dev.sebastiano.ciacola.effects.keynoteFadeMove
import dev.sebastiano.ciacola.effects.keynotePopAlpha
import dev.sebastiano.ciacola.effects.keynotePopScale
import dev.sebastiano.ciacola.effects.loupeSampleClip
import dev.sebastiano.ciacola.effects.trailingHighlightColor
import dev.sebastiano.ciacola.timeline.computeArcInterpolatedRect
import androidx.compose.ui.geometry.Rect
import kotlin.test.assertFalse
import kotlin.math.abs
import kotlin.test.assertTrue
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import dev.sebastiano.ciacola.effects.animateBuildProgress
import dev.sebastiano.ciacola.effects.animateStaggerGroup
import dev.sebastiano.ciacola.effects.keynoteFadeMoveTracked
import dev.sebastiano.ciacola.effects.keynoteFromDarkness
import dev.sebastiano.ciacola.effects.keynotePop
import dev.sebastiano.ciacola.effects.spatialEntranceOffset
import dev.sebastiano.ciacola.timeline.ActiveSceneTimelineContext
import dev.sebastiano.ciacola.timeline.DeckTimelineOverridesFile
import dev.sebastiano.ciacola.timeline.LocalActiveSceneTimeline
import dev.sebastiano.ciacola.timeline.LocalTimelineHarvestCollector
import dev.sebastiano.ciacola.timeline.LocalTimelineOverrides
import dev.sebastiano.ciacola.timeline.TimelineCurveSpec
import dev.sebastiano.ciacola.timeline.TimelineHarvestCollector
import dev.sebastiano.ciacola.timeline.TimelineSpatialSpec
import dev.sebastiano.ciacola.timeline.TrackCategory
import dev.sebastiano.ciacola.timeline.TrackOverride
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.launch

/** Regression tests for the Wave-2 motion / contrast fixes owned by `w-motion` (patches 70–74). */
@OptIn(ExperimentalTestApi::class)
class MotionAndContrastFixesTest {

    // --- 70: MagicText overrides --------------------------------------------------------------

    @Test
    fun magicTextHarvestKeepsOneBlockPerBeatWhenEnterBeatIsOverridden() = runComposeUiTest {
        val collector = TimelineHarvestCollector()
        val overrides = DeckTimelineOverridesFile(
            tracks = mapOf("s0:magic-text" to TrackOverride(trackId = "s0:magic-text", enterBeat = 2, delayMs = 40)),
        )
        val ctx = ActiveSceneTimelineContext(
            sceneIndex = 0,
            sceneTitle = "Beats",
            totalBeats = 3,
            frameBeatIndexOf = { it as? Int ?: 0 },
        )
        setContent {
            CompositionLocalProvider(
                LocalTimelineOverrides provides overrides,
                LocalActiveSceneTimeline provides ctx,
                LocalTimelineHarvestCollector provides collector,
            ) {
                DeckTextRendererProvider(MagicTextV1Renderer) {
                    DeckMagicText(updateTransition(AnnotatedString("one")))
                }
            }
        }
        waitForIdle()

        val magic = collector.records.values.filter { it.category == TrackCategory.MagicText }
        assertEquals(listOf(1, 2), magic.map { it.triggerBeat }.sorted(), "Each beat transition keeps its own block")
        assertEquals(setOf(40), magic.map { it.delayMs }.toSet())
    }

    @Test
    fun magicTextRendererAppliesDurationDelayAndCurveOverrides() {
        val defaults = magicTextOverrideTiming(null)
        assertEquals(0, defaults.delayMs)
        assertEquals(null, defaults.easing)
        assertEquals(300, defaults.phaseMs)

        val curve = TimelineCurveSpec(preset = "Linear")
        val timing = magicTextOverrideTiming(
            TrackOverride(trackId = "s0:magic-text", durationMs = 600, delayMs = 120, curve = curve),
        )
        assertEquals(200, timing.phaseMs)
        assertEquals(120, timing.delayMs)
        assertEquals(LinearEasing, timing.easing)
    }

    @Test
    fun slide1MagicTextNeverFliesLonePeriodOrCollidesDuringNormalOrInterruptedAdvance() = runComposeUiTest {
        mainClock.autoAdvance = false
        val deck = createFoundationDeck()
        lateinit var animatic: dev.bnorm.storyboard.easel.Animatic
        lateinit var scope: kotlinx.coroutines.CoroutineScope
        setContent {
            scope = androidx.compose.runtime.rememberCoroutineScope()
            Box(Modifier.size(960.dp, 540.dp)) {
                animatic = dev.bnorm.storyboard.easel.rememberAnimatic(storyboard = { deck })
                dev.bnorm.storyboard.easel.Easel(animatic)
            }
        }
        mainClock.advanceTimeBy(200)

        // Advance S1:B1 -> B2 partway (~640 ms) and interrupt directly into S1:B2 -> B3, matching
        // the exact sequence that previously produced "stat[a]" and a detached "." on line 2.
        lateinit var b1ToB2: kotlinx.coroutines.Job
        runOnIdle { b1ToB2 = scope.launch { animatic.advance(dev.bnorm.storyboard.AdvanceDirection.Forward) } }
        repeat(40) { mainClock.advanceTimeByFrame() }
        lateinit var b2ToB3: kotlinx.coroutines.Job
        runOnIdle { b2ToB3 = scope.launch { animatic.advance(dev.bnorm.storyboard.AdvanceDirection.Forward) } }

        var observedEarlyB2ToB3 = false
        var safety = 0
        while (!b2ToB3.isCompleted && safety++ < 200) {
            mainClock.advanceTimeByFrame()
            val (settling, frac) = runOnIdle { animatic.scrubSettling to animatic.activeFraction }
            if (!settling && frac in 0.05f..0.35f) {
                observedEarlyB2ToB3 = true
                // During the fade-out half of S1:B2 -> B3, "." must stay attached to "state." on line 1,
                // never detached onto line 2 below "state".
                val stateNodes = onAllNodes(androidx.compose.ui.test.hasText("state", substring = false)).fetchSemanticsNodes()
                val periodNodes = onAllNodes(androidx.compose.ui.test.hasText(".", substring = false)).fetchSemanticsNodes()
                if (stateNodes.isNotEmpty() && periodNodes.size == 1) {
                    val stateBounds = stateNodes.single().boundsInRoot
                    val periodBounds = periodNodes.single().boundsInRoot
                    assertTrue(
                        abs(periodBounds.bottom - stateBounds.bottom) < 8f,
                        "Expected '.' to remain on the same baseline as 'state' (${stateBounds.bottom}), got ${periodBounds.bottom}",
                    )
                    assertTrue(
                        abs(periodBounds.left - stateBounds.right) < 8f,
                        "Expected '.' to remain attached to the right of 'state' (${stateBounds.right}), got ${periodBounds.left}",
                    )
                }
            }
        }
        assertTrue(b2ToB3.isCompleted, "Advance to S1:B3 completed")
        assertTrue(observedEarlyB2ToB3, "Observed early live frames of S1:B2 -> B3")
    }

    // --- 71: single-source trigger beat + spatial entrance offsets ------------------------------

    private fun beatCtx(totalBeats: Int) = ActiveSceneTimelineContext(
        sceneIndex = 4,
        sceneTitle = "Lab",
        totalBeats = totalBeats,
        frameBeatIndexOf = { it as? Int ?: 0 },
    )

    @Test
    fun buildVisibilityFollowsTheDeclaredTriggerBeatInsideAScene() = runComposeUiTest {
        var beat by mutableIntStateOf(0)
        var build = -1f
        var stagger = -1f
        setContent {
            CompositionLocalProvider(LocalActiveSceneTimeline provides beatCtx(5)) {
                val transition = updateTransition(beat)
                // The trailing lambda disagrees with defaultTriggerBeat: the beat must win (one source of truth).
                val progress by transition.animateBuildProgress(label = "b", trackId = "b", defaultTriggerBeat = 1) { it >= 3 }
                val group = transition.animateStaggerGroup(count = 2, label = "g", trackId = "g", defaultTriggerBeat = 1) { it >= 3 }
                // Read during composition so every animation frame recomposes and republishes.
                val buildNow = progress
                val staggerNow = group.progressFor(1)
                SideEffect {
                    build = buildNow
                    stagger = staggerNow
                }
            }
        }
        waitForIdle()
        assertEquals(0f, build)
        runOnIdle { beat = 1 }
        waitForIdle()
        assertEquals(1f, build, "Build declared for beat 1 must be visible on beat 1")
        assertEquals(1f, stagger, "Stagger declared for beat 1 must be visible on beat 1")
    }

    @Test
    fun buildVisibilityWithoutALambdaUsesTheEnterBeatOverride() = runComposeUiTest {
        var beat by mutableIntStateOf(0)
        var build = -1f
        val overrides = DeckTimelineOverridesFile(tracks = mapOf("b" to TrackOverride(trackId = "b", enterBeat = 2)))
        setContent {
            CompositionLocalProvider(
                LocalActiveSceneTimeline provides beatCtx(5),
                LocalTimelineOverrides provides overrides,
            ) {
                val progress by updateTransition(beat).animateBuildProgress(label = "b", trackId = "b", defaultTriggerBeat = 1)
                val buildNow = progress
                SideEffect { build = buildNow }
            }
        }
        runOnIdle { beat = 1 }
        waitForIdle()
        assertEquals(0f, build)
        runOnIdle { beat = 2 }
        waitForIdle()
        assertEquals(1f, build)
    }

    private fun ComposeUiTest.entranceCenter(
        overrides: DeckTimelineOverridesFile,
        effect: @Composable Modifier.() -> Modifier,
    ): Offset = entranceBounds(overrides, effect).center

    private fun ComposeUiTest.entranceBounds(
        overrides: DeckTimelineOverridesFile,
        effect: @Composable Modifier.() -> Modifier,
    ): Rect {
        var bounds = Rect.Zero
        setContent {
            CompositionLocalProvider(LocalTimelineOverrides provides overrides) {
                Box(Modifier.size(400.dp)) {
                    Box(
                        Modifier
                            .offset(100.dp, 100.dp)
                            .size(50.dp)
                            .effect()
                            .onGloballyPositioned { bounds = it.boundsInRoot() },
                    )
                }
            }
        }
        waitForIdle()
        return bounds
    }

    @Test
    fun popAndFromDarknessHonourTheSpatialOverrideOnlyWhenSet() {
        val spatial = DeckTimelineOverridesFile(
            tracks = mapOf("stamp" to TrackOverride(trackId = "stamp", spatial = TimelineSpatialSpec(offsetXDp = 40f, offsetYDp = -30f))),
        )
        for (effect in listOf<@Composable Modifier.() -> Modifier>(
            { keynotePop(progress = { 0f }, entranceOffset = spatialEntranceOffset("stamp")) },
            { keynoteFromDarkness(progress = { 0f }, entranceOffset = spatialEntranceOffset("stamp")) },
        )) {
            var plain = Offset.Unspecified
            var moved = Offset.Unspecified
            var density = 1f
            runComposeUiTest {
                density = this.density.density
                plain = entranceCenter(DeckTimelineOverridesFile(), effect)
            }
            runComposeUiTest { moved = entranceCenter(spatial, effect) }
            assertEquals(125f * density, plain.x, 0.5f, "No spatial override: no translation")
            assertEquals(125f * density, plain.y, 0.5f)
            assertEquals(plain.x + 40f * density, moved.x, 0.5f, "offsetXDp shifts the entrance start")
            assertEquals(plain.y - 30f * density, moved.y, 0.5f, "offsetYDp shifts the entrance start")
        }
    }

    @Test
    fun fadeMoveTrackedHonoursBothSpatialAxes() {
        val spatial = DeckTimelineOverridesFile(
            tracks = mapOf("rows" to TrackOverride(trackId = "rows", spatial = TimelineSpatialSpec(offsetXDp = -20f, offsetYDp = 10f))),
        )
        var moved = Offset.Unspecified
        var density = 1f
        runComposeUiTest {
            density = this.density.density
            moved = entranceCenter(spatial) { keynoteFadeMoveTracked(trackId = "rows", progress = { 0f }) }
        }
        assertEquals((125f - 20f) * density, moved.x, 0.5f)
        assertEquals((125f + 10f) * density, moved.y, 0.5f)
    }

    // --- 72: warp code card height + settle ------------------------------------------------------

    private val warpSnippets = listOf(
        "fun fade(s: String) {\n  BasicText(s)\n}",
        "fun fade(\n  s: String,\n  a: Float,\n) {\n  BasicText(s, alpha = a)\n}",
    )

    /**
     * Visible card heights (rows drawn over a magenta backdrop at the card's centre column) sampled
     * every frame after switching [from] -> [to] (index 0 = before the switch).
     */
    private fun warpCardHeights(from: Int, to: Int): List<Float> {
        val heights = mutableListOf<Float>()
        runComposeUiTest {
            var step by mutableIntStateOf(from)
            setContent {
                Box(Modifier.size(900.dp, 700.dp).background(Color.Magenta)) {
                    DeckWarpCodeCard(
                        codeStates = warpSnippets,
                        stateTransition = updateTransition(step),
                        modifier = Modifier.testTag("warp"),
                    )
                }
            }
            fun visibleHeight(): Float {
                val pixels = onNodeWithTag("warp").captureToImage().toPixelMap()
                val x = pixels.width / 2
                return (0 until pixels.height).count { y -> pixels[x, y] != Color.Magenta }.toFloat()
            }
            waitForIdle()
            mainClock.autoAdvance = false
            heights += visibleHeight()
            step = to
            repeat(40) {
                mainClock.advanceTimeByFrame()
                heights += visibleHeight()
            }
        }
        return heights
    }

    private fun assertSmoothHeight(heights: List<Float>, label: String) {
        val start = heights.first()
        val end = heights.last()
        val delta = abs(end - start)
        assertTrue(delta > 20f, "$label: expected the card height to change, got $start -> $end")
        val maxJump = heights.zipWithNext { a, b -> abs(b - a) }.max()
        assertTrue(maxJump <= delta * 0.35f, "$label: largest single-frame height jump $maxJump of $delta ($heights)")
        val settledFrame = heights.indexOfFirst { abs(it - end) < 0.5f }
        assertTrue(settledFrame * 16 <= 450, "$label: height settled only after ${settledFrame * 16} ms ($heights)")
    }

    @Test
    fun warpCodeCardInterpolatesItsHeightForwardAndBackward() {
        assertSmoothHeight(warpCardHeights(0, 1), "forward 3 -> 6 lines")
        assertSmoothHeight(warpCardHeights(1, 0), "backward 6 -> 3 lines")
    }

    // --- 73: single easing, C1 pop, smooth highlight, loupe clip, slide 2 clearance ---------------

    @Test
    fun fadeMoveAndFromDarknessApplyTheBuildEasingOnlyOnce() {
        // Build progress arrives already eased (animateBuildProgress), so the modifiers must be linear in it.
        var density = 1f
        var fadeMove = Rect.Zero
        var fromDarkness = Rect.Zero
        runComposeUiTest {
            density = this.density.density
            fadeMove = entranceBounds(DeckTimelineOverridesFile()) { keynoteFadeMove(progress = { 0.5f }) }
        }
        runComposeUiTest {
            fromDarkness = entranceBounds(DeckTimelineOverridesFile()) { keynoteFromDarkness(progress = { 0.5f }) }
        }
        assertEquals((125f + 12f) * density, fadeMove.center.y, 0.5f, "Half-way build = half the 24 dp travel left")
        assertEquals(50f * (0.88f + 0.12f * 0.5f) * density, fromDarkness.width, 0.5f, "Half-way build = half the scale-up")
    }

    @Test
    fun popScaleAndAlphaAreC1SmoothOvershootOnceAndLandAtRest() {
        val n = 400
        val h = 1f / n
        val scale = (0..n).map { keynotePopScale(it * h, initialScale = 0.72f, overshootScale = 1.06f) }
        assertEquals(0.72f, scale.first(), 1e-4f)
        assertEquals(1f, scale.last(), 1e-4f)
        assertEquals(1.06f, scale.max(), 0.005f)
        val slope = scale.zipWithNext { a, b -> (b - a) / h }
        val kink = slope.zipWithNext { a, b -> abs(b - a) }.max()
        assertTrue(kink < 0.1f, "Scale velocity must be continuous, largest step change $kink")
        assertTrue(abs(slope.last()) < 0.05f, "Pop must land with ~zero velocity, got ${slope.last()}")

        val alpha = (0..n).map { keynotePopAlpha(it * h) }
        val alphaSlope = alpha.zipWithNext { a, b -> (b - a) / h }
        val alphaKink = alphaSlope.zipWithNext { a, b -> abs(b - a) }.max()
        assertTrue(alphaKink < 0.2f, "Alpha velocity must be continuous, largest step change $alphaKink")
        assertEquals(1f, alpha.last(), 1e-4f)
    }

    @Test
    fun trailingHighlightBlendsInsteadOfSnapping() {
        val quarter = trailingHighlightColor(base = Color.White, accent = Color.Red, progress = 0.25f)
        val half = trailingHighlightColor(base = Color.White, accent = Color.Red, progress = 0.5f)
        assertTrue(quarter.green in 0.3f..0.95f, "A quarter-way highlight is a partial blend, got $quarter")
        assertTrue(half.green < quarter.green, "The blend progresses monotonically ($quarter -> $half)")
        assertEquals(Color.Red, trailingHighlightColor(Color.White, Color.Red, 1f))
        assertEquals(Color.White, trailingHighlightColor(Color.White, Color.Red, 0f))
    }

    @Test
    fun leaderArrowHeadGrowsWithTheShaft() {
        assertTrue(arrowHeadLength(14f, 0.05f) < 14f * 0.3f, "A barely drawn leader must not carry a full head")
        assertEquals(14f, arrowHeadLength(14f, 1f), 1e-4f)
        assertTrue(arrowHeadLength(14f, 0.1f) < arrowHeadLength(14f, 0.2f))
    }

    @Test
    fun loupeOnlyMagnifiesTheTargetCallout() {
        val target = Rect(100f, 100f, 300f, 124f)
        val lensCenter = Offset(82f, 82f)
        val zoom = 2.4f
        val clip = loupeSampleClip(target, lensCenter, zoom, padPx = 4f)
        fun toLens(p: Offset) = Offset(lensCenter.x + (p.x - target.center.x) * zoom, lensCenter.y + (p.y - target.center.y) * zoom)
        assertTrue(clip.contains(toLens(target.center)))
        assertTrue(clip.contains(toLens(Offset(target.center.x, target.bottom + 3f))))
        // The IMMUTABLE stamp border sits ~10 px under the target row: it must not bleed into the lens.
        assertFalse(clip.contains(toLens(Offset(target.center.x, target.bottom + 10f))))
    }

    @Test
    fun slide2SharedCardNeverRisesIntoTheEyebrow() {
        val start = Rect(0f, 0f, 240f, 240f)
        val end = Rect((1792f - 520f) / 2f, (896f - 520f) / 2f, (1792f + 520f) / 2f, (896f + 520f) / 2f)
        for (bend in listOf(-1f, -0.5f, 0f, 0.5f, 1f)) {
            for (i in 0..20) {
                val f = i / 20f
                val rect = sharedCardRect(start, end, f, bend, minTop = 0f)
                assertTrue(rect.top >= -0.01f, "arcBend=$bend fraction=$f: card top ${rect.top} rises above the content top")
            }
        }
        for (i in 0..20) {
            val f = i / 20f
            assertEquals(computeArcInterpolatedRect(start, end, f, 0f), sharedCardRect(start, end, f, 0f, minTop = 0f))
        }
    }

    // --- 74: deck theme WCAG contrast -------------------------------------------------------------

    private fun contrast(a: Color, b: Color): Float {
        val la = a.luminance()
        val lb = b.luminance()
        return (maxOf(la, lb) + 0.05f) / (minOf(la, lb) + 0.05f)
    }

    private fun over(fg: Color, alpha: Float, bg: Color) = Color(
        red = fg.red * alpha + bg.red * (1f - alpha),
        green = fg.green * alpha + bg.green * (1f - alpha),
        blue = fg.blue * alpha + bg.blue * (1f - alpha),
    )

    @Test
    fun deckThemeTokensMeetWcagContrastInBothThemes() {
        for (theme in listOf(DeckThemes.stageDark(), DeckThemes.keynoteLight())) {
            val c = theme.colors
            val name = theme.name
            assertTrue(contrast(c.muted, c.background) >= 4.5f, "$name: muted text ${contrast(c.muted, c.background)}:1")
            assertTrue(contrast(c.onAccent, c.accent) >= 4.5f, "$name: Shared Card label ${contrast(c.onAccent, c.accent)}:1")
            val stampFill = over(c.accent, 0.14f, c.background)
            assertTrue(contrast(c.accent, stampFill) >= 3.3f, "$name: stamp text ${contrast(c.accent, stampFill)}:1 (large display)")
            val dimmed = over(c.foreground, c.spotlightDimAlpha, c.background)
            assertTrue(contrast(dimmed, c.background) >= 3.0f, "$name: spotlight-dimmed text ${contrast(dimmed, c.background)}:1")
            assertTrue(
                contrast(c.motionPathPreview, c.background) >= 3.0f,
                "$name: motion-path preview ${contrast(c.motionPathPreview, c.background)}:1 (graphical)",
            )
        }
    }
}
