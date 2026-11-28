package dev.sebastiano.ciacola.effects.v2

import androidx.compose.ui.geometry.Rect
import dev.sebastiano.ciacola.document.Curve
import dev.sebastiano.ciacola.document.MotionChannelSpec
import dev.sebastiano.ciacola.document.MotionKeyframeSpec
import dev.sebastiano.ciacola.document.MotionPresetDeclarations
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure evaluation of the motion presets: rest poses, preset arguments, channel overrides and the
 * per-piece context (tracking spread, travelling waves, focus sweep).
 */
class MotionPresetsTest {

    private val em = 100f
    private val eps = 1e-3f

    private fun context(
        index: Int = 0,
        count: Int = 1,
        line: Int = 0,
        indexInLine: Int = index,
        lineLength: Int = count,
        pieceBox: Rect = Rect(0f, 0f, 60f, 120f),
        textBounds: Rect? = Rect(0f, 0f, 1000f, 120f),
        reducedMotion: Boolean = false,
    ) = EffectEvalContext(
        nodeBounds = pieceBox,
        reducedMotion = reducedMotion,
        piece = PieceContext(
            index = index,
            count = count,
            line = line,
            indexInLine = indexInLine,
            lineLength = lineLength,
            emPx = em,
            textBounds = textBounds,
        ),
    )

    private fun eval(
        id: String,
        kind: EffectBuildKind,
        progress: Float,
        args: EffectArgs = EffectArgs.Empty,
        ctx: EffectEvalContext = context(),
        curve: CurveV2? = null,
    ): LayerState = MotionPresets.evaluate(assertNotNull(MotionPresets.resolve(id, kind, args)), progress, ctx, curve)

    @Test
    fun `rise starts below its line behind a mask and lands at rest`() {
        val start = eval(MotionPresetDeclarations.RISE, EffectBuildKind.Enter, 0f)
        assertEquals(0f, start.alpha, eps)
        assertEquals(0.9f * em, start.translationY, eps)
        val mask = assertIs<EffectClip.RestBounds>(start.clip)
        assertEquals(0.12f * em, mask.padTop, eps)

        val end = eval(MotionPresetDeclarations.RISE, EffectBuildKind.Enter, 1f)
        assertEquals(1f, end.alpha, eps)
        assertEquals(0f, end.translationY, eps)
        assertEquals(EffectClip.None, end.clip)

        // The fade finishes early (fadeWindow 0.35) while position keeps easing.
        val mid = eval(MotionPresetDeclarations.RISE, EffectBuildKind.Enter, 0.5f)
        assertEquals(1f, mid.alpha, eps)
        assertTrue(mid.translationY > 0f && mid.translationY < 0.9f * em)
    }

    @Test
    fun `rise arguments change direction, distance and masking`() {
        val down = eval(
            MotionPresetDeclarations.RISE,
            EffectBuildKind.Enter,
            0f,
            EffectArgs.of("direction" to "down", "distance" to 2f, "reveal" to false),
        )
        assertEquals(-2f * em, down.translationY, eps)
        assertEquals(EffectClip.None, down.clip)

        val exitEnd = eval(MotionPresetDeclarations.RISE, EffectBuildKind.Exit, 1f)
        assertEquals(0f, exitEnd.alpha, eps)
        assertEquals(-0.9f * em, exitEnd.translationY, eps)
        assertEquals(EffectClip.None, eval(MotionPresetDeclarations.RISE, EffectBuildKind.Exit, 0f).clip)
    }

    @Test
    fun `channel overrides replace single fields and add new channels`() {
        val args = EffectArgs.of(
            MotionPresets.CHANNELS_ARG to mapOf(
                // Only the curve changes: from/to still come from `distance`.
                "offsetY" to MotionChannelSpec(curve = Curve.Linear),
                "rotation" to MotionChannelSpec(
                    keyframes = listOf(MotionKeyframeSpec(0.0, -10.0), MotionKeyframeSpec(1.0, 0.0)),
                ),
            ),
        )
        val half = eval(MotionPresetDeclarations.RISE, EffectBuildKind.Enter, 0.5f, args)
        assertEquals(0.45f * em, half.translationY, eps)
        assertEquals(-5f, half.rotationZ, eps)
    }

    @Test
    fun `build curve remaps progress before the channels`() {
        val linear = EffectArgs.of(MotionPresets.CHANNELS_ARG to mapOf("offsetY" to MotionChannelSpec(curve = Curve.Linear)))
        val remapped = eval(MotionPresetDeclarations.RISE, EffectBuildKind.Enter, 0.5f, linear, curve = CurveV2.EaseIn)
        val plain = eval(MotionPresetDeclarations.RISE, EffectBuildKind.Enter, 0.5f, linear)
        assertTrue(remapped.translationY > plain.translationY, "${remapped.translationY} vs ${plain.translationY}")
    }

    @Test
    fun `wave lifts pieces in a travelling wave and returns to rest`() {
        val args = EffectArgs.of("fade" to 0.2f)
        for (p in listOf(0f, 1f)) {
            assertEquals(0f, eval(MotionPresetDeclarations.WAVE, EffectBuildKind.Emphasis, p, args).translationY, eps)
        }
        val a = eval(MotionPresetDeclarations.WAVE, EffectBuildKind.Emphasis, 0.4f, args, context(index = 0, count = 8))
        val b = eval(MotionPresetDeclarations.WAVE, EffectBuildKind.Emphasis, 0.4f, args, context(index = 3, count = 8))
        assertTrue(abs(a.translationY - b.translationY) > 1f, "${a.translationY} vs ${b.translationY}")
        assertTrue(abs(a.translationY) <= 0.18f * em + eps)

        val tilt = eval(
            MotionPresetDeclarations.WAVE,
            EffectBuildKind.Emphasis,
            0.125f,
            EffectArgs.of("amplitude" to 0f, "rotation" to 12f, "fade" to 0f, "phasePerPiece" to 0f),
        )
        assertEquals(12f, tilt.rotationZ, eps)
        assertEquals(0f, tilt.translationY, eps)
    }

    @Test
    fun `tracking breathe spreads glyphs around the line centre`() {
        val args = EffectArgs.of("amplitude" to 0.2f, "fade" to 0f, "phasePerLine" to 0f)
        fun at(indexInLine: Int, progress: Float) = eval(
            MotionPresetDeclarations.TRACKING_BREATHE,
            EffectBuildKind.Emphasis,
            progress,
            args,
            context(index = indexInLine, count = 5, indexInLine = indexInLine, lineLength = 5),
        ).translationX

        assertEquals(0f, at(0, 0f), eps)
        assertEquals(-0.4f * em, at(0, 0.5f), eps)
        assertEquals(0f, at(2, 0.5f), eps)
        assertEquals(0.4f * em, at(4, 0.5f), eps)
        assertEquals(0f, at(4, 1f), eps)
    }

    @Test
    fun `tracking ranks follow visual order so rtl lines spread outwards`() {
        // Line 0 is RTL: logical order runs from the right edge. Line 1 is LTR.
        val lines = intArrayOf(0, 0, 0, 1, 1)
        val centres = floatArrayOf(250f, 150f, 50f, 30f, 90f)
        val ranks = MotionPresets.visualRanksInLine(lines, centres)
        assertEquals(listOf(2, 1, 0, 0, 1), ranks.toList())

        val args = EffectArgs.of("amplitude" to 0.2f, "fade" to 0f, "phasePerLine" to 0f)
        val firstLogical = eval(
            MotionPresetDeclarations.TRACKING_BREATHE,
            EffectBuildKind.Emphasis,
            0.5f,
            args,
            context(index = 0, count = 3, indexInLine = ranks[0], lineLength = 3),
        )
        // The rightmost glyph moves right even though it comes first logically.
        assertTrue(firstLogical.translationX > 0f, "${firstLogical.translationX}")
    }

    @Test
    fun `focus pull sweep blurs pieces by distance from the focal point`() {
        val args = EffectArgs.of("radius" to 0.3f, "focusWidth" to 1f, "settle" to false)
        val near = eval(
            MotionPresetDeclarations.FOCUS_PULL,
            EffectBuildKind.Emphasis,
            0.5f,
            args,
            context(pieceBox = Rect(470f, 0f, 530f, 120f)),
        )
        val far = eval(
            MotionPresetDeclarations.FOCUS_PULL,
            EffectBuildKind.Emphasis,
            0.5f,
            args,
            context(pieceBox = Rect(0f, 0f, 60f, 120f)),
        )
        assertEquals(0f, near.blurRadiusPx, eps)
        assertEquals(1f, near.alpha, eps)
        assertEquals(0.3f * em, far.blurRadiusPx, eps)
        assertEquals(0.45f, far.alpha, eps)

        val settled = EffectArgs.of("radius" to 0.3f, "focusWidth" to 1f)
        for (p in listOf(0f, 1f)) {
            val s = eval(MotionPresetDeclarations.FOCUS_PULL, EffectBuildKind.Emphasis, p, settled, context(pieceBox = Rect(0f, 0f, 60f, 120f)))
            assertEquals(0f, s.blurRadiusPx, eps)
        }
    }

    @Test
    fun `focus pull enter resolves from blurred and enlarged to sharp`() {
        val start = eval(MotionPresetDeclarations.FOCUS_PULL, EffectBuildKind.Enter, 0f)
        assertEquals(0.3f * em, start.blurRadiusPx, eps)
        assertEquals(1.06f, start.scaleX, eps)
        val end = eval(MotionPresetDeclarations.FOCUS_PULL, EffectBuildKind.Enter, 1f)
        assertEquals(0f, end.blurRadiusPx, eps)
        assertEquals(1f, end.scaleX, eps)
        assertEquals(1f, end.alpha, eps)
    }

    @Test
    fun `reduced motion degrades to a dissolve`() {
        val state = eval(MotionPresetDeclarations.RISE, EffectBuildKind.Enter, 0.5f, ctx = context(reducedMotion = true))
        assertEquals(0f, state.translationY, eps)
        assertEquals(EffectClip.None, state.clip)
    }

    @Test
    fun `build effects registry dispatches presets`() {
        val viaRegistry = BuildEffects.evaluateLayer(
            effectId = MotionPresetDeclarations.RISE,
            kind = EffectBuildKind.Enter,
            progress = 0f,
            context = context(),
        )
        assertEquals(0.9f * em, viaRegistry.translationY, eps)
        for (id in MotionPresetDeclarations.ids) {
            assertNotNull(BuildEffects.get(id), id)
        }
        assertNull(MotionPresets.resolve(BuildEffects.DISSOLVE, EffectBuildKind.Enter, EffectArgs.Empty))
    }
}
