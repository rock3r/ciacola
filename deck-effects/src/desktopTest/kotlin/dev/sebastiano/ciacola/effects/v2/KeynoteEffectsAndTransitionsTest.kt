package dev.sebastiano.ciacola.effects.v2

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import dev.bnorm.storyboard.Storyboard
import dev.sebastiano.ciacola.effects.compileOrGetSkslEffect
import kotlin.math.abs
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image as SkiaImage
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Paint as SkiaPaint
import org.jetbrains.skia.Rect as SkiaRect
import org.jetbrains.skia.Surface

class KeynoteEffectsAndTransitionsTest {

    @Test
    fun all15KeynoteCurvesAreRegisteredAndWellBehaved() {
        assertEquals(15, KeynoteCurves.all.size, "Expected exactly 15 Keynote curves")
        val expectedNames = setOf(
            "keynote.none",
            "keynote.easeIn",
            "keynote.easeOut",
            "keynote.easeInOut",
            "keynote.fadeMoveAlpha",
            "keynote.fadeMovePos",
            "keynote.fadeMoveExit",
            "keynote.moveIn",
            "keynote.moveOut",
            "keynote.moveOutDecel",
            "keynote.moveOutAccel",
            "keynote.push",
            "keynote.reveal",
            "keynote.slide",
            "keynote.zoomBigExit",
        )
        assertEquals(expectedNames, KeynoteCurves.all.keys)

        for ((name, easing) in KeynoteCurves.all) {
            assertNotNull(KeynoteCurves.byName(name), "Missing byName lookup for $name")
            assertEquals(0f, easing.transform(0f), 1e-4f, "$name at 0")
            assertEquals(1f, easing.transform(1f), 1e-4f, "$name at 1")
            val mid = easing.transform(0.5f)
            assertTrue(mid in 0f..1f, "$name mid=$mid should be in [0, 1]")
            val preset = CurveV2.Preset(name)
            assertEquals(0f, preset.sample(-0.2f), 1e-5f)
            assertEquals(1f, preset.sample(1.3f), 1e-5f)
            assertEquals(mid, preset.sample(0.5f), 1e-4f)
        }

        // Apple fadeMovePos is faster early than fadeMoveAlpha (distinct dual curves).
        assertTrue(
            KeynoteCurves.FadeMovePos.transform(0.25f) > KeynoteCurves.FadeMoveAlpha.transform(0.25f),
            "fadeMovePos should lead fadeMoveAlpha early in entrance",
        )
    }

    @Test
    fun analyticalSpringPreservesOvershootAndSettlesExactlyToOne() {
        val underdamped = CurveV2.Spring(damping = 0.45f, stiffness = 280f)
        val settleMs = underdamped.settleTimeMs()
        assertTrue(settleMs in 300..5000, "Unexpected underdamped settle time: $settleMs ms")

        var maxVal = 0f
        for (ms in 0..settleMs step 10) {
            val v = underdamped.evaluateAtMs(ms.toFloat())
            if (v > maxVal) maxVal = v
        }
        assertTrue(maxVal > 1.05f, "Underdamped spring must preserve overshoot > 1.05 (got $maxVal)")
        assertEquals(0f, underdamped.evaluateAtMs(0f))
        assertEquals(1f, underdamped.evaluateAtMs(settleMs.toFloat()))
        assertEquals(1f, underdamped.evaluateAtMs((settleMs + 500).toFloat()))

        val critical = CurveV2.Spring(damping = 1.0f, stiffness = 240f)
        val over = CurveV2.Spring(damping = 1.4f, stiffness = 240f)
        for (sp in listOf(critical, over)) {
            val sMs = sp.settleTimeMs()
            assertTrue(sMs in 200..6000)
            var prev = 0f
            for (ms in 0..sMs step 20) {
                val cur = sp.evaluateAtMs(ms.toFloat())
                assertTrue(cur >= prev - 1e-4f && cur <= 1.001f, "Non-underdamped spring should be monotonic <= 1 (cur=$cur, prev=$prev)")
                prev = cur
            }
            assertEquals(1f, sp.evaluateAtMs(sMs.toFloat()))
        }

        // Overshoot cubic-bezier preserves y > 1 without clamping output.
        val backBezier = CurveV2.Bezier(0.34f, 1.56f, 0.64f, 1.0f)
        assertTrue(backBezier.sample(0.6f) > 1.02f, "Overshoot cubic-bezier must not clamp output to 1")
    }

    @Test
    fun all52KeynoteEffectsMappedAndValidated() {
        val mappings = BuildEffects.keynoteEffectMappings
        assertEquals(52, mappings.size, "Expected all 52 Keynote (type, name) pairs")
        assertEquals(5, mappings.count { it.keynoteType == "actionBuild" })
        assertEquals(24, mappings.count { it.keynoteType == "buildIn" })
        assertEquals(14, mappings.count { it.keynoteType == "buildOut" })
        assertEquals(9, mappings.count { it.keynoteType == "transition" })

        val uniquePairs = mappings.map { it.keynoteType to it.keynoteName }.toSet()
        assertEquals(52, uniquePairs.size, "Duplicate Keynote (type, name) mapping detected")

        for (m in mappings) {
            assertEquals(m, BuildEffects.resolveKeynoteEffect(m.keynoteType, m.keynoteName))
            if (m.category == KeynoteTargetCategory.BuildEffect) {
                val decl = BuildEffects.get(m.targetId)
                assertNotNull(decl, "Mapped targetId '${m.targetId}' not registered in BuildEffects")
                assertEquals(m.tier, decl.tier, "Tier mismatch on ${m.keynoteName}")
                assertNotNull(m.buildKind)
                assertTrue(m.buildKind in decl.supportedKinds, "BuildKind ${m.buildKind} unsupported for ${m.targetId}")
            }
            if (m.defaultCurvePreset != null) {
                assertNotNull(KeynoteCurves.byName(m.defaultCurvePreset), "Invalid default curve ${m.defaultCurvePreset}")
            }
        }

        // Validation checks: unsupported node kind produces Error + dissolve fallback.
        val lineDrawOnText = BuildEffects.validate(BuildEffects.LINE_DRAW, EffectBuildKind.Enter, EffectNodeKind.Text)
        assertEquals(1, lineDrawOnText.size)
        assertEquals(EffectValidationSeverity.Error, lineDrawOnText.single().severity)
        assertEquals(BuildEffects.DISSOLVE, lineDrawOnText.single().fallbackEffectId)

        // Connector is a valid target for lineDraw.
        assertEquals(
            emptyList(),
            BuildEffects.validate(BuildEffects.LINE_DRAW, EffectBuildKind.Enter, EffectNodeKind.Connector),
        )

        // Keyboard on Shape is unsupported -> Error + dissolve fallback.
        val kbOnShape = BuildEffects.validate(BuildEffects.KEYBOARD, EffectBuildKind.Enter, EffectNodeKind.Shape)
        assertEquals(1, kbOnShape.size)
        assertEquals(EffectValidationSeverity.Error, kbOnShape.single().severity)
        assertEquals(BuildEffects.DISSOLVE, kbOnShape.single().fallbackEffectId)

        // Runtime fallback resolver routes unsupported nodeKind to dissolve.
        assertEquals(BuildEffects.DISSOLVE, BuildEffects.resolveEffectiveEffectId(BuildEffects.LINE_DRAW, EffectNodeKind.Text))
        assertEquals(BuildEffects.LINE_DRAW, BuildEffects.resolveEffectiveEffectId(BuildEffects.LINE_DRAW, EffectNodeKind.Connector))
        assertEquals(
            BuildEffects.evaluateLayer(BuildEffects.DISSOLVE, EffectBuildKind.Enter, 0.5f),
            BuildEffects.evaluateLayer(BuildEffects.LINE_DRAW, EffectBuildKind.Enter, 0.5f, nodeKind = EffectNodeKind.Text),
        )

        val unknownEffect = BuildEffects.validate("nonexistent", EffectBuildKind.Enter, EffectNodeKind.Shape)
        assertEquals(EffectValidationSeverity.Error, unknownEffect.single().severity)

        val badRange = BuildEffects.validate(
            BuildEffects.FADE_MOVE,
            EffectBuildKind.Enter,
            EffectNodeKind.Text,
            EffectArgs.of("distance" to -10f),
        )
        assertEquals(EffectValidationSeverity.Error, badRange.single().severity)
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun ensureComposeRegistered() {
        val scene = ImageComposeScene(width = 1, height = 1, density = Density(1f)) {}
        try {
            scene.render(0L)
        } finally {
            scene.close()
        }
    }

    @Test
    fun allLayerEffectsSatisfyEndpointInvariantsAnd8WayGeometry() {
        ensureComposeRegistered()
        val ctx = EffectEvalContext(
            nodeBounds = Rect(400f, 300f, 720f, 420f),
            stageSize = Size(1920f, 1080f),
            density = 1f,
        )

        // Appear
        assertEquals(LayerState.Hidden, BuildEffects.evaluateLayer(BuildEffects.APPEAR, EffectBuildKind.Enter, 0f, ctx))
        assertEquals(LayerState.Identity, BuildEffects.evaluateLayer(BuildEffects.APPEAR, EffectBuildKind.Enter, 1f, ctx))
        assertEquals(LayerState.Identity, BuildEffects.evaluateLayer(BuildEffects.APPEAR, EffectBuildKind.Exit, 0f, ctx))
        assertEquals(LayerState.Hidden, BuildEffects.evaluateLayer(BuildEffects.APPEAR, EffectBuildKind.Exit, 1f, ctx))

        // FadeMove across all 8 directions (Enter + Exit asymmetric curves)
        for (dir in EffectDirection8.entries) {
            val args = EffectArgs.of("direction" to dir, "distance" to 60f)
            val s0 = BuildEffects.evaluateLayer(BuildEffects.FADE_MOVE, EffectBuildKind.Enter, 0f, ctx, args)
            val sMid = BuildEffects.evaluateLayer(BuildEffects.FADE_MOVE, EffectBuildKind.Enter, 0.4f, ctx, args)
            val s1 = BuildEffects.evaluateLayer(BuildEffects.FADE_MOVE, EffectBuildKind.Enter, 1f, ctx, args)
            assertEquals(0f, s0.alpha)
            assertEquals(LayerState.Identity, s1)
            assertTrue(sMid.alpha in 0.01f..0.99f)
            val u = dir.unitVector()
            assertEquals(-u.x * 60f, s0.translationX, 1e-3f)
            assertEquals(-u.y * 60f, s0.translationY, 1e-3f)

            val e0 = BuildEffects.evaluateLayer(BuildEffects.FADE_MOVE, EffectBuildKind.Exit, 0f, ctx, args)
            val e1 = BuildEffects.evaluateLayer(BuildEffects.FADE_MOVE, EffectBuildKind.Exit, 1f, ctx, args)
            assertEquals(LayerState.Identity, e0)
            assertEquals(0f, e1.alpha)
            assertEquals(u.x * 60f, e1.translationX, 1e-3f)
            assertEquals(u.y * 60f, e1.translationY, 1e-3f)
        }

        // MoveIn: full opacity during travel + clears stage edge + optional bounce overshoot
        for (dir in EffectDirection8.entries) {
            val args = EffectArgs.of("direction" to dir, "bounce" to true)
            val mid = BuildEffects.evaluateLayer(BuildEffects.MOVE_IN, EffectBuildKind.Enter, 0.5f, ctx, args)
            val end = BuildEffects.evaluateLayer(BuildEffects.MOVE_IN, EffectBuildKind.Enter, 1f, ctx, args)
            assertEquals(1f, mid.alpha, "moveIn must not fade mid-travel ($dir)")
            assertEquals(LayerState.Identity, end)
            val start = BuildEffects.evaluateLayer(BuildEffects.MOVE_IN, EffectBuildKind.Enter, 0f, ctx, args)
            val dist = kotlin.math.hypot(start.translationX, start.translationY)
            assertTrue(dist > 300f, "moveIn must travel from outside stage edge ($dir dist=$dist)")
        }
        // Bounce=true produces visible overshoot past resting position vs bounce=false.
        val bounceCrossedZero = (72..99).any { step ->
            val st = BuildEffects.evaluateLayer(
                BuildEffects.MOVE_IN,
                EffectBuildKind.Enter,
                step / 100f,
                ctx,
                EffectArgs.of("direction" to EffectDirection8.LeftToRight, "bounce" to true),
            )
            st.translationX > 1f
        }
        val noBounceCrossedZero = (72..99).any { step ->
            val st = BuildEffects.evaluateLayer(
                BuildEffects.MOVE_IN,
                EffectBuildKind.Enter,
                step / 100f,
                ctx,
                EffectArgs.of("direction" to EffectDirection8.LeftToRight, "bounce" to false),
            )
            st.translationX > 1f
        }
        assertTrue(bounceCrossedZero, "moveIn bounce=true must overshoot resting x=0 during settle phase")
        assertFalse(noBounceCrossedZero, "moveIn bounce=false must not overshoot resting x=0")

        // Stage-clearing travel against full 1920x1080 stage coordinates (with & without stageOffset).
        val positionedCtx = EffectEvalContext(
            nodeBounds = Rect(0f, 0f, 180f, 40f),
            stageSize = Size(1920f, 1080f),
            density = 1f,
            stageOffset = Offset(1500f, 300f),
        )
        val positionedStart = BuildEffects.evaluateLayer(
            BuildEffects.MOVE_IN,
            EffectBuildKind.Enter,
            0f,
            positionedCtx,
            EffectArgs.of("direction" to EffectDirection8.LeftToRight),
        )
        val startRightEdgeOnStage = 1500f + positionedStart.translationX + 180f
        assertTrue(
            startRightEdgeOnStage <= -32f,
            "moveIn LeftToRight with stageOffset=(1500,300) must clear left stage edge (rightEdge=$startRightEdgeOnStage)",
        )
        val unknownPosCtx = EffectEvalContext(
            nodeBounds = Rect(0f, 0f, 180f, 40f),
            stageSize = Size(1920f, 1080f),
            density = 1f,
            stageOffset = null,
        )
        val unknownPosStart = BuildEffects.evaluateLayer(
            BuildEffects.MOVE_IN,
            EffectBuildKind.Enter,
            0f,
            unknownPosCtx,
            EffectArgs.of("direction" to EffectDirection8.LeftToRight),
        )
        assertTrue(
            abs(unknownPosStart.translationX) >= 1920f + 32f,
            "moveIn without stageOffset must conservatively clear full 1920px stage (tx=${unknownPosStart.translationX})",
        )

        // Wipe 8-way polygon clip geometry + Iris In/Out + centerX/centerY wiring
        for (dir in EffectDirection8.entries) {
            val wMid = BuildEffects.evaluateLayer(BuildEffects.WIPE, EffectBuildKind.Enter, 0.5f, ctx, EffectArgs.of("direction" to dir))
            val clip = wMid.clip as EffectClip.Wipe
            val path = LayerEffects.buildWipeClipPath(Size(200f, 100f), clip)
            val bounds = path.getBounds()
            assertTrue(bounds.width > 10f && bounds.height > 10f, "Wipe clip path empty for $dir")
            when (dir) {
                EffectDirection8.LeftToRight -> assertTrue(bounds.left <= 1f && bounds.right in 98f..102f, "L->R wipe bounds=$bounds")
                EffectDirection8.RightToLeft -> assertTrue(bounds.left in 98f..102f && bounds.right >= 199f, "R->L wipe bounds=$bounds")
                EffectDirection8.TopToBottom -> assertTrue(bounds.top <= 1f && bounds.bottom in 48f..52f, "T->B wipe bounds=$bounds")
                EffectDirection8.BottomToTop -> assertTrue(bounds.top in 48f..52f && bounds.bottom >= 99f, "B->T wipe bounds=$bounds")
                else -> assertTrue(bounds.width > 50f && bounds.height > 40f, "Diagonal wipe bounds=$bounds")
            }
        }
        val irisIn = BuildEffects.evaluateLayer(BuildEffects.IRIS, EffectBuildKind.Enter, 0.5f, ctx, EffectArgs.of("direction" to IrisDirection.In))
        assertTrue((irisIn.clip as EffectClip.Iris).keepInside)
        val irisOutExit = BuildEffects.evaluateLayer(BuildEffects.IRIS, EffectBuildKind.Exit, 0.5f, ctx, EffectArgs.of("direction" to IrisDirection.Out))
        assertTrue((irisOutExit.clip as EffectClip.Iris).keepInside)
        val irisCenterXY = BuildEffects.evaluateLayer(
            BuildEffects.IRIS,
            EffectBuildKind.Enter,
            0.5f,
            ctx,
            EffectArgs.of("centerX" to 0.2f, "centerY" to 0.8f),
        )
        assertEquals(Offset(0.2f, 0.8f), (irisCenterXY.clip as EffectClip.Iris).center, "iris must wire centerX/centerY args")
        val irisExplicitCenter = BuildEffects.evaluateLayer(
            BuildEffects.IRIS,
            EffectBuildKind.Enter,
            0.5f,
            ctx,
            EffectArgs.of("center" to Offset(0.75f, 0.25f), "centerX" to 0.2f, "centerY" to 0.8f),
        )
        assertEquals(Offset(0.75f, 0.25f), (irisExplicitCenter.clip as EffectClip.Iris).center, "Explicit center takes precedence over centerX/centerY")

        // Zoom & ZoomBig (with scaleDelay)
        val zDelayed = BuildEffects.evaluateLayer(
            BuildEffects.ZOOM,
            EffectBuildKind.Enter,
            0.2f,
            ctx,
            EffectArgs.of("fromScale" to 0.2f, "toScale" to 1f, "scaleDelay" to 0.3f),
        )
        assertEquals(0.2f, zDelayed.scaleX, 1e-4f, "Scale should hold at fromScale during scaleDelay")
        assertTrue(zDelayed.alpha > 0f)
        val zBigExit = BuildEffects.evaluateLayer(BuildEffects.ZOOM_BIG, EffectBuildKind.Exit, 0.8f, ctx)
        assertTrue(zBigExit.scaleX > 1.5f, "zoomBig exit should expand beyond 1.5x (got ${zBigExit.scaleX})")

        // Pop Enter overshoot + Pop Exit anticipation
        val popEnterPeak = (1..20).maxOf {
            BuildEffects.evaluateLayer(BuildEffects.POP, EffectBuildKind.Enter, it / 20f, ctx).scaleX
        }
        assertTrue(popEnterPeak > 1.04f, "Pop Enter should overshoot 1.0 (got $popEnterPeak)")
        val popExitAnticipation = BuildEffects.evaluateLayer(BuildEffects.POP, EffectBuildKind.Exit, 0.20f, ctx)
        assertTrue(popExitAnticipation.scaleX > 1.02f, "Pop Exit should swell in anticipation before collapsing")
        assertEquals(LayerState.Identity, BuildEffects.evaluateLayer(BuildEffects.POP, EffectBuildKind.Exit, 0f, ctx))
        assertEquals(0f, BuildEffects.evaluateLayer(BuildEffects.POP, EffectBuildKind.Exit, 1f, ctx).alpha)

        // Blur & FromDarkness & Jiggle
        val blurMid = BuildEffects.evaluateLayer(BuildEffects.BLUR, EffectBuildKind.Enter, 0.4f, ctx)
        assertTrue(blurMid.blurRadiusPx > 5f)
        assertEquals(LayerState.Identity, BuildEffects.evaluateLayer(BuildEffects.BLUR, EffectBuildKind.Enter, 1f, ctx))

        val darkMid = BuildEffects.evaluateLayer(BuildEffects.FROM_DARKNESS, EffectBuildKind.Enter, 0.4f, ctx)
        val darkMatrix = assertNotNull(darkMid.colorMatrix)
        assertTrue(darkMatrix[0] in 0.01f..0.99f)
        assertEquals(LayerState.Identity, BuildEffects.evaluateLayer(BuildEffects.FROM_DARKNESS, EffectBuildKind.Enter, 1f, ctx))
        assertEquals(LayerState.Identity, BuildEffects.evaluateLayer(BuildEffects.FROM_DARKNESS, EffectBuildKind.Exit, 0f, ctx))

        assertEquals(LayerState.Identity, BuildEffects.evaluateLayer(BuildEffects.JIGGLE, EffectBuildKind.Emphasis, 0f, ctx))
        assertEquals(LayerState.Identity, BuildEffects.evaluateLayer(BuildEffects.JIGGLE, EffectBuildKind.Emphasis, 1f, ctx))
        val jiggleActive = (1..19).any {
            abs(BuildEffects.evaluateLayer(BuildEffects.JIGGLE, EffectBuildKind.Emphasis, it / 20f, ctx).rotationZ) > 0.5f
        }
        assertTrue(jiggleActive, "Jiggle must rotate mid-emphasis")
        val jigglePeakSmall = (1..39).maxOf {
            abs(
                BuildEffects.evaluateLayer(
                    BuildEffects.JIGGLE,
                    EffectBuildKind.Emphasis,
                    it / 40f,
                    ctx,
                    EffectArgs.of("amplitude" to 3f, "cycles" to 3f),
                ).rotationZ,
            )
        }
        val jigglePeakLarge = (1..39).maxOf {
            abs(
                BuildEffects.evaluateLayer(
                    BuildEffects.JIGGLE,
                    EffectBuildKind.Emphasis,
                    it / 40f,
                    ctx,
                    EffectArgs.of("amplitude" to 12f, "cycles" to 3f),
                ).rotationZ,
            )
        }
        assertTrue(
            jigglePeakLarge > jigglePeakSmall * 3.5f,
            "jiggle amplitude=12 must scale peak rotation ~4x vs amplitude=3 (small=$jigglePeakSmall, large=$jigglePeakLarge)",
        )
    }

    @Test
    fun lineDrawPathTrimsAll5DirectionsMultiContourAndComputesTipTangents() {
        ensureComposeRegistered()
        // Closed square contour drawn CW in Y-down coordinates: (10,10) -> (110,10) -> (110,110) -> (10,110) -> close
        val square = Path().apply {
            moveTo(10f, 10f)
            lineTo(110f, 10f)
            lineTo(110f, 110f)
            lineTo(10f, 110f)
            close()
        }

        // StartToEnd at 25% -> tip at (110, 10) pointing +X
        val ste = trimLineDrawPath(square, PathTrimState(0f, 0.25f, LineDrawDirection.StartToEnd))
        assertEquals(1, ste.tips.size)
        assertEquals(110f, ste.tips[0].position.x, 1.5f)
        assertEquals(10f, ste.tips[0].position.y, 1.5f)
        assertTrue(ste.tips[0].tangent.x > 0.9f)

        // EndToStart at 25% ([0.75, 1.0]) -> tip at (10, 110) pointing +Y (growing backwards from (10,10) down left edge)
        val ets = trimLineDrawPath(square, PathTrimState(0.75f, 1.0f, LineDrawDirection.EndToStart))
        assertEquals(1, ets.tips.size)
        assertEquals(10f, ets.tips[0].position.x, 1.5f)
        assertEquals(110f, ets.tips[0].position.y, 1.5f)
        assertTrue(ets.tips[0].tangent.y > 0.9f, "EndToStart tangent must point in backward growth direction (+Y), got ${ets.tips[0].tangent}")

        // FromCenter -> 2 outward tips
        val fc = trimLineDrawPath(square, PathTrimState(0.25f, 0.75f, LineDrawDirection.FromCenter))
        assertEquals(2, fc.tips.size, "FromCenter must emit two active outward tips")

        // Clockwise vs CounterClockwise produce opposite initial edge directions on same contour!
        val cw = trimLineDrawPath(square, PathTrimState(0f, 0.125f, LineDrawDirection.Clockwise))
        val ccw = trimLineDrawPath(square, PathTrimState(0f, 0.125f, LineDrawDirection.CounterClockwise))
        assertEquals(1, cw.tips.size)
        assertEquals(1, ccw.tips.size)
        assertTrue(cw.tips[0].position.x > 50f && cw.tips[0].position.y < 20f, "CW starts along top edge: ${cw.tips[0]}")
        assertTrue(ccw.tips[0].position.x < 20f && ccw.tips[0].position.y > 50f, "CCW starts along left edge: ${ccw.tips[0]}")

        // Multi-contour path trims continuously across contours.
        val multi = Path().apply {
            moveTo(0f, 0f)
            lineTo(100f, 0f)
            moveTo(0f, 50f)
            lineTo(100f, 50f)
        }
        val multi75 = trimLineDrawPath(multi, PathTrimState(0f, 0.75f, LineDrawDirection.StartToEnd))
        assertEquals(50f, multi75.tips[0].position.x, 1.5f)
        assertEquals(50f, multi75.tips[0].position.y, 1.5f)

        // includeEndpoints=false delays arrowhead scale until end of draw.
        val withEnd = BuildEffects.evaluatePath(BuildEffects.LINE_DRAW, EffectBuildKind.Enter, 0.4f, EffectArgs.of("includeEndpoints" to true))
        val withoutEnd = BuildEffects.evaluatePath(BuildEffects.LINE_DRAW, EffectBuildKind.Enter, 0.4f, EffectArgs.of("includeEndpoints" to false))
        assertTrue(withEnd.headScale > 0.8f)
        assertEquals(0f, withoutEnd.headScale, 1e-4f)
    }

    @Test
    fun contentTierSkslShadersCompileAndRenderDistinctTraceSparkleShimmerEffects() {
        for (id in listOf(BuildEffects.TRACE, BuildEffects.SPARKLE, BuildEffects.SHIMMER)) {
            val sksl = assertNotNull(ContentEffectShaders.skslFor(id))
            val compiled = compileOrGetSkslEffect(sksl)
            assertTrue(compiled.isSuccess, "SkSL shader '$id' failed to compile: ${compiled.exceptionOrNull()}")
        }

        // Create a crisp opaque white-and-blue square in the center of a 160x100 transparent image.
        val src = createTestShapeImage(160, 100)
        try {
            val srcPixels = readImageRgba(src)

            // TRACE Enter: 0 = empty, 0.35 = glowing edge outline without full interior fill, 1.0 = exact source.
            val trace0 = renderContentEffectImage(src, BuildEffects.TRACE, EffectBuildKind.Enter, 0f)
            val traceMid = renderContentEffectImage(src, BuildEffects.TRACE, EffectBuildKind.Enter, 0.35f)
            val trace1 = renderContentEffectImage(src, BuildEffects.TRACE, EffectBuildKind.Enter, 1f)
            try {
                assertEquals(0, countOpaqueishPixels(readImageRgba(trace0)), "trace Enter p=0 must be empty")
                assertEquals(0, maxChannelDiff(srcPixels, readImageRgba(trace1)), "trace Enter p=1 must match source")
                val midPx = readImageRgba(traceMid)
                val edgeAlpha = pixelAlpha(midPx, 160, 40, 50) // Left edge of rect [40..120, 20..80]
                val centerAlpha = pixelAlpha(midPx, 160, 80, 50) // Interior center (unfilled at p=0.35)
                assertTrue(edgeAlpha > 80, "trace p=0.35 must illuminate silhouette edge (edgeAlpha=$edgeAlpha)")
                assertTrue(centerAlpha < 15, "trace p=0.35 interior must remain unfilled until p>0.60 (centerAlpha=$centerAlpha)")
            } finally {
                trace0.close()
                traceMid.close()
                trace1.close()
            }

            // SPARKLE Enter: 0 = empty, 0.5 = partial reveal + bright sparkle particles, 1.0 = exact source.
            val sp0 = renderContentEffectImage(src, BuildEffects.SPARKLE, EffectBuildKind.Enter, 0f)
            val spMid = renderContentEffectImage(src, BuildEffects.SPARKLE, EffectBuildKind.Enter, 0.5f)
            val sp1 = renderContentEffectImage(src, BuildEffects.SPARKLE, EffectBuildKind.Enter, 1f)
            try {
                assertEquals(0, countOpaqueishPixels(readImageRgba(sp0)))
                assertEquals(0, maxChannelDiff(srcPixels, readImageRgba(sp1)))
                val midPx = readImageRgba(spMid)
                assertTrue(maxChannelDiff(srcPixels, midPx) > 40, "sparkle p=0.5 must visibly differ from resting source")
                assertTrue(countOpaqueishPixels(midPx) > 100, "sparkle p=0.5 must draw visible pixels")
            } finally {
                sp0.close()
                spMid.close()
                sp1.close()
            }

            // SHIMMER Emphasis: p=0 and p=1 exact source; p=0.5 boosts luminance across sweep band.
            val sh0 = renderContentEffectImage(src, BuildEffects.SHIMMER, EffectBuildKind.Emphasis, 0f)
            val shMid = renderContentEffectImage(src, BuildEffects.SHIMMER, EffectBuildKind.Emphasis, 0.5f)
            val sh1 = renderContentEffectImage(src, BuildEffects.SHIMMER, EffectBuildKind.Emphasis, 1f)
            try {
                assertEquals(0, maxChannelDiff(srcPixels, readImageRgba(sh0)))
                assertEquals(0, maxChannelDiff(srcPixels, readImageRgba(sh1)))
                val midPx = readImageRgba(shMid)
                assertTrue(meanRgb(midPx) > meanRgb(srcPixels) + 0.5, "shimmer Emphasis p=0.5 must brighten content")
            } finally {
                sh0.close()
                shMid.close()
                sh1.close()
            }
            // TRACE Exit: 0 = exact source, 0.65 = fading glowing edge, 1 = empty.
            val traceExit0 = renderContentEffectImage(src, BuildEffects.TRACE, EffectBuildKind.Exit, 0f)
            val traceExitMid = renderContentEffectImage(src, BuildEffects.TRACE, EffectBuildKind.Exit, 0.65f)
            val traceExit1 = renderContentEffectImage(src, BuildEffects.TRACE, EffectBuildKind.Exit, 1f)
            try {
                assertEquals(0, maxChannelDiff(srcPixels, readImageRgba(traceExit0)), "trace Exit p=0 must match source")
                assertEquals(0, countOpaqueishPixels(readImageRgba(traceExit1)), "trace Exit p=1 must be empty")
                assertTrue(countOpaqueishPixels(readImageRgba(traceExitMid)) > 50, "trace Exit mid must still render glowing trace")
            } finally {
                traceExit0.close()
                traceExitMid.close()
                traceExit1.close()
            }

            // SPARKLE Exit: 0 = exact source, 0.5 = dispersing sparkles, 1 = empty.
            val spExit0 = renderContentEffectImage(src, BuildEffects.SPARKLE, EffectBuildKind.Exit, 0f)
            val spExitMid = renderContentEffectImage(src, BuildEffects.SPARKLE, EffectBuildKind.Exit, 0.5f)
            val spExit1 = renderContentEffectImage(src, BuildEffects.SPARKLE, EffectBuildKind.Exit, 1f)
            try {
                assertEquals(0, maxChannelDiff(srcPixels, readImageRgba(spExit0)))
                assertEquals(0, countOpaqueishPixels(readImageRgba(spExit1)))
                assertTrue(countOpaqueishPixels(readImageRgba(spExitMid)) > 100)
            } finally {
                spExit0.close()
                spExitMid.close()
                spExit1.close()
            }

            // SHIMMER Enter: 0 = empty, 0.5 = shimmering reveal, 1 = exact source.
            val shEnter0 = renderContentEffectImage(src, BuildEffects.SHIMMER, EffectBuildKind.Enter, 0f)
            val shEnterMid = renderContentEffectImage(src, BuildEffects.SHIMMER, EffectBuildKind.Enter, 0.5f)
            val shEnter1 = renderContentEffectImage(src, BuildEffects.SHIMMER, EffectBuildKind.Enter, 1f)
            try {
                assertEquals(0, countOpaqueishPixels(readImageRgba(shEnter0)))
                assertEquals(0, maxChannelDiff(srcPixels, readImageRgba(shEnter1)))
                assertTrue(countOpaqueishPixels(readImageRgba(shEnterMid)) > 100)
            } finally {
                shEnter0.close()
                shEnterMid.close()
                shEnter1.close()
            }
        } finally {
            src.close()
        }

        // Verify Modifier.applyContentEffect renders crisp stage-resolution Compose GraphicsLayer +
        // ImageFilter.makeRuntimeShader output at HiDPI (density=2f) without CPU raster capture.
        verifyApplyContentEffectComposeModifier()
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun verifyApplyContentEffectComposeModifier() {
        fun renderModifierFrame(effectId: String, kind: EffectBuildKind, progress: Float): ByteArray {
            val scene = ImageComposeScene(width = 320, height = 200, density = Density(2f)) {
                Box(Modifier.fillMaxSize()) {
                    Box(
                        Modifier
                            .offset(40.dp, 20.dp)
                            .size(80.dp, 60.dp)
                            .applyContentEffect(effectId = effectId, kind = kind, progress = progress)
                            .background(Color(0xFF3366AA)),
                    )
                }
            }
            try {
                scene.render(0L)
                return readImageRgba(scene.render(16_000_000L))
            } finally {
                scene.close()
            }
        }

        val baseline = renderModifierFrame(BuildEffects.SHIMMER, EffectBuildKind.Emphasis, 0f)
        assertTrue(countOpaqueishPixels(baseline) > 10_000, "HiDPI 80x60 dp box at density=2 must produce crisp high-res pixels")

        for (id in listOf(BuildEffects.TRACE, BuildEffects.SPARKLE)) {
            val p0 = renderModifierFrame(id, EffectBuildKind.Enter, 0f)
            val pMid = renderModifierFrame(id, EffectBuildKind.Enter, 0.45f)
            val p1 = renderModifierFrame(id, EffectBuildKind.Enter, 1f)
            assertEquals(0, countOpaqueishPixels(p0), "Modifier.applyContentEffect $id Enter p=0 must be empty")
            assertEquals(0, maxChannelDiff(baseline, p1), "Modifier.applyContentEffect $id Enter p=1 must be pixel-identical to baseline")
            assertTrue(countOpaqueishPixels(pMid) > 200, "Modifier.applyContentEffect $id Enter p=0.45 must draw visible pixels")
            assertTrue(maxChannelDiff(baseline, pMid) > 25, "Modifier.applyContentEffect $id Enter p=0.45 must visibly differ from baseline")
        }

        val shimmerMid = renderModifierFrame(BuildEffects.SHIMMER, EffectBuildKind.Emphasis, 0.5f)
        val shimmerEnd = renderModifierFrame(BuildEffects.SHIMMER, EffectBuildKind.Emphasis, 1f)
        assertEquals(0, maxChannelDiff(baseline, shimmerEnd), "Modifier.applyContentEffect shimmer Emphasis p=1 must match baseline")
        assertTrue(meanRgb(shimmerMid) > meanRgb(baseline) + 0.5, "Modifier.applyContentEffect shimmer Emphasis p=0.5 must brighten layer")
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun all9SlideTransitionsPixelMatrixAndDirectionInvariance() {
        val w = 160
        val h = 100
        val stageSize = Size(w.toFloat(), h.toFloat())
        val colorA = Color(0xFFD62828) // Red slide A
        val colorB = Color(0xFF1D4ED8) // Blue slide B
        val accentA = Color(0xFFFACC15) // Yellow center badge on A
        val accentB = Color(0xFF22C55E) // Green center badge on B

        val transitions: List<SlideTransitionV2> = listOf(
            SlideTransitionV2.None,
            SlideTransitionV2.Dissolve(),
            SlideTransitionV2.Push(EffectDirection8.RightToLeft),
            SlideTransitionV2.Slide(EffectDirection8.RightToLeft),
            SlideTransitionV2.Reveal(EffectDirection8.RightToLeft),
            SlideTransitionV2.Wipe(EffectDirection8.LeftToRight),
            SlideTransitionV2.Wipe(EffectDirection8.TopLeftToBottomRight),
            SlideTransitionV2.Iris(IrisDirection.In),
            SlideTransitionV2.Iris(IrisDirection.Out),
            SlideTransitionV2.FadeThroughColor(color = Color(0x00102030)), // Alpha=0 input must be forced opaque!
            SlideTransitionV2.MagicMove(),
        )

        // Verify sceneZIndex stacking contract for every transition.
        for (tr in transitions) {
            val zFn = sceneZIndexForTransition(tr, fromSceneIndex = 0, toSceneIndex = 1)
            val zA = zFn(Storyboard.Index(0, 0))
            val zB = zFn(Storyboard.Index(1, 0))
            if (tr is SlideTransitionV2.Reveal || (tr is SlideTransitionV2.Iris && tr.direction == IrisDirection.Out)) {
                assertTrue(zA > zB, "Expected A above B for $tr (zA=$zA, zB=$zB)")
            } else {
                assertTrue(zB > zA, "Expected B above A for $tr (zA=$zA, zB=$zB)")
            }
        }

        @Composable
        fun RenderSlideTransitionForTest(
            transition: SlideTransitionV2,
            canonicalProgress: Float,
            withContentBadges: Boolean,
        ) {
            val comp = composeSlideTransition(transition, canonicalProgress, stageSize)
            Box(Modifier.fillMaxSize()) {
                if (comp.magicMove != null) {
                    val mm = comp.magicMove
                    if (mm.backgroundAAlpha > 0f) {
                        Box(Modifier.fillMaxSize().zIndex(0f).background(colorA.copy(alpha = mm.backgroundAAlpha)))
                    }
                    if (mm.backgroundBAlpha > 0f) {
                        Box(Modifier.fillMaxSize().zIndex(1f).background(colorB.copy(alpha = mm.backgroundBAlpha)))
                    }
                    if (comp.slideA.visible && withContentBadges) {
                        Box(Modifier.fillMaxSize().zIndex(2f).slideLayerTransform(comp.slideA.layer)) {
                            Box(Modifier.offset(20.dp, 20.dp).size(40.dp).background(accentA))
                        }
                    }
                    if (comp.slideB.visible && withContentBadges) {
                        Box(Modifier.fillMaxSize().zIndex(3f).slideLayerTransform(comp.slideB.layer)) {
                            Box(Modifier.offset(100.dp, 40.dp).size(40.dp).background(accentB))
                        }
                    }
                    val ox = (20f + 80f * mm.overlayProgress).dp
                    Box(Modifier.fillMaxSize().zIndex(10f)) {
                        Box(Modifier.offset(ox, 40.dp).size(24.dp).background(Color.White))
                    }
                } else {
                    for (slot in comp.drawOrder) {
                        val layer = if (slot == SlideSlot.OutgoingA) comp.slideA else comp.slideB
                        if (!layer.visible) continue
                        Box(
                            Modifier
                                .fillMaxSize()
                                .zIndex(layer.zIndex)
                                .slideLayerTransform(layer.layer)
                                .background(if (layer.slot == SlideSlot.OutgoingA) colorA else colorB),
                        ) {
                            if (withContentBadges) {
                                if (layer.slot == SlideSlot.OutgoingA) {
                                    Box(Modifier.offset(20.dp, 20.dp).size(40.dp).background(accentA))
                                } else {
                                    Box(Modifier.offset(100.dp, 40.dp).size(40.dp).background(accentB))
                                }
                            }
                        }
                    }
                    comp.veil?.let { veil ->
                        if (veil.alpha > 0f) {
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .zIndex(20f)
                                    .background(veil.color.copy(alpha = veil.alpha.coerceIn(0f, 1f))),
                            )
                        }
                    }
                }
            }
        }

        fun renderTransition(
            transition: SlideTransitionV2,
            p: Float,
            withContentBadges: Boolean,
        ): ByteArray {
            val scene = ImageComposeScene(width = w, height = h, density = Density(1f)) {
                RenderSlideTransitionForTest(
                    transition = transition,
                    canonicalProgress = p,
                    withContentBadges = withContentBadges,
                )
            }
            try {
                scene.render(0L)
                val img = scene.render(16_000_000L)
                return readImageRgba(img)
            } finally {
                scene.close()
            }
        }

        for (tr in transitions) {
            for (withBadges in listOf(false, true)) {
                // Non-tautological asymmetric canonical direction invariance:
                // forward p=0.35 == cold seek p=0.35 == reverse arrival at (1f - 0.65f),
                // AND p=0.35 visibly differs from p=0.65 for every non-cut transition!
                val forward35 = renderTransition(tr, 0.35f, withBadges)
                val reverseArrival35 = renderTransition(tr, 1f - 0.65f, withBadges)
                val forward65 = renderTransition(tr, 0.65f, withBadges)
                assertEquals(
                    0,
                    maxChannelDiff(forward35, reverseArrival35),
                    "Asymmetric direction invariance failed at p=0.35 for $tr (withBadges=$withBadges)",
                )
                if (tr != SlideTransitionV2.None) {
                    assertNotEquals(
                        0,
                        maxChannelDiff(forward35, forward65),
                        "Asymmetric canonical progress p=0.35 vs p=0.65 must produce distinct pixels for $tr (withBadges=$withBadges)",
                    )
                }

                val atStart = renderTransition(tr, 0f, withBadges)
                val atEnd = renderTransition(tr, 1f, withBadges)
                assertNotEquals(
                    0,
                    maxChannelDiff(atStart, atEnd),
                    "Slide A and Slide B must render distinct pixels for $tr",
                )
            }

            // Specific compositor pixel assertions at p = 0.5 (background-only for exact RGB inspection):
            val mid = renderTransition(tr, 0.5f, withContentBadges = false)
            when (tr) {
                SlideTransitionV2.None -> {
                    // Cut at 0.5 shows B.
                    assertPixelClose(mid, w, 80, 50, colorB)
                }
                is SlideTransitionV2.Dissolve -> {
                    // Midpoint blends A (red) and B (blue): both R and B channels active.
                    val (r, _, b) = pixelRgb(mid, w, 80, 50)
                    assertTrue(r > 50 && b > 50, "Dissolve p=0.5 must blend red & blue (r=$r, b=$b)")
                }
                is SlideTransitionV2.Push -> {
                    // RightToLeft push at p=0.5: left side still A (red), right side B (blue).
                    assertPixelClose(mid, w, 20, 50, colorA)
                    assertPixelClose(mid, w, 140, 50, colorB)
                }
                is SlideTransitionV2.Slide -> {
                    // RightToLeft slide at p=0.5: B slides from right over static A.
                    assertPixelClose(mid, w, 15, 50, colorA)
                    assertPixelClose(mid, w, 145, 50, colorB)
                }
                is SlideTransitionV2.Reveal -> {
                    // Reveal RightToLeft: A (on top) slides left off screen revealing B on the right!
                    assertPixelClose(mid, w, 15, 50, colorA)
                    assertPixelClose(mid, w, 145, 50, colorB)
                }
                is SlideTransitionV2.Wipe -> {
                    if (tr.direction == EffectDirection8.LeftToRight) {
                        assertPixelClose(mid, w, 20, 50, colorB)
                        assertPixelClose(mid, w, 140, 50, colorA)
                    } else {
                        // TopLeftToBottomRight diagonal wipe: top-left is B, bottom-right is A.
                        assertPixelClose(mid, w, 10, 10, colorB)
                        assertPixelClose(mid, w, 150, 90, colorA)
                    }
                }
                is SlideTransitionV2.Iris -> {
                    if (tr.direction == IrisDirection.In) {
                        // Growing circle of B at center; corners still A.
                        assertPixelClose(mid, w, 80, 50, colorB)
                        assertPixelClose(mid, w, 5, 5, colorA)
                    } else {
                        // Iris Out: A stacked ABOVE B shrinking into center circle; corners already B!
                        assertPixelClose(mid, w, 80, 50, colorA)
                        assertPixelClose(mid, w, 5, 5, colorB)
                    }
                }
                is SlideTransitionV2.FadeThroughColor -> {
                    // Opaque veil guard: even though alpha=0 color was passed, p=0.5 is 100% solid veil!
                    val expectedOpaque = Color(0xFF102030)
                    assertPixelClose(mid, w, 80, 50, expectedOpaque)
                    assertPixelClose(mid, w, 5, 5, expectedOpaque)
                }
                is SlideTransitionV2.MagicMove -> {
                    // Host background crossfades while white travelling overlay renders at center (x=60..84, y=40..64).
                    assertPixelClose(mid, w, 70, 50, Color.White)
                    val (r, _, b) = pixelRgb(mid, w, 5, 5)
                    assertTrue(r > 50 && b > 50, "MagicMove host background must crossfade at p=0.5 (r=$r, b=$b)")
                }
            }
        }
    }

    private fun createTestShapeImage(w: Int, h: Int): SkiaImage {
        val surface = Surface.makeRasterN32Premul(w, h)
        try {
            surface.canvas.clear(0)
            val paint = SkiaPaint().apply { color = 0xFF3366AA.toInt() }
            try {
                surface.canvas.drawRect(SkiaRect.makeLTRB(40f, 20f, 120f, 80f), paint)
            } finally {
                paint.close()
            }
            return surface.makeImageSnapshot()
        } finally {
            surface.close()
        }
    }

    private fun readImageRgba(image: SkiaImage): ByteArray {
        val w = image.width
        val h = image.height
        val info = ImageInfo(w, h, ColorType.RGBA_8888, ColorAlphaType.PREMUL)
        val bmp = Bitmap()
        try {
            bmp.allocPixels(info)
            image.readPixels(bmp, 0, 0)
            return bmp.readPixels(info, w * 4, 0, 0) ?: ByteArray(w * h * 4)
        } finally {
            bmp.close()
        }
    }

    private fun maxChannelDiff(a: ByteArray, b: ByteArray): Int {
        var maxD = 0
        for (i in a.indices) {
            val d = abs((a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF))
            if (d > maxD) maxD = d
        }
        return maxD
    }

    private fun countOpaqueishPixels(rgba: ByteArray): Int {
        var count = 0
        var i = 3
        while (i < rgba.size) {
            if ((rgba[i].toInt() and 0xFF) > 10) count++
            i += 4
        }
        return count
    }

    private fun pixelAlpha(rgba: ByteArray, w: Int, x: Int, y: Int): Int =
        rgba[(y * w + x) * 4 + 3].toInt() and 0xFF

    private fun pixelRgb(rgba: ByteArray, w: Int, x: Int, y: Int): Triple<Int, Int, Int> {
        val base = (y * w + x) * 4
        return Triple(
            rgba[base].toInt() and 0xFF,
            rgba[base + 1].toInt() and 0xFF,
            rgba[base + 2].toInt() and 0xFF,
        )
    }

    private fun meanRgb(rgba: ByteArray): Double {
        var sum = 0.0
        var n = 0
        var i = 0
        while (i < rgba.size) {
            if ((rgba[i + 3].toInt() and 0xFF) > 20) {
                sum += ((rgba[i].toInt() and 0xFF) + (rgba[i + 1].toInt() and 0xFF) + (rgba[i + 2].toInt() and 0xFF)) / 3.0
                n++
            }
            i += 4
        }
        return if (n == 0) 0.0 else sum / n
    }

    private fun assertPixelClose(rgba: ByteArray, w: Int, x: Int, y: Int, expected: Color, tol: Int = 6) {
        val (r, g, b) = pixelRgb(rgba, w, x, y)
        val er = (expected.red * 255f).toInt()
        val eg = (expected.green * 255f).toInt()
        val eb = (expected.blue * 255f).toInt()
        val diff = max(max(abs(r - er), abs(g - eg)), abs(b - eb))
        assertTrue(
            diff <= tol,
            "Pixel ($x,$y) RGB=($r,$g,$b) expected≈($er,$eg,$eb), maxDiff=$diff > $tol",
        )
    }
}
