package dev.sebastiano.ciacola.effects.v2

import androidx.compose.runtime.Immutable
import dev.sebastiano.ciacola.document.Curve
import dev.sebastiano.ciacola.document.MotionChannelMath
import dev.sebastiano.ciacola.document.MotionChannelNames
import dev.sebastiano.ciacola.document.MotionChannelSpec
import dev.sebastiano.ciacola.document.MotionPresetDeclarations
import dev.sebastiano.ciacola.document.MotionWaveSpec
import dev.sebastiano.ciacola.document.WaveShape
import kotlin.math.abs

/**
 * Motion presets: typographic build effects evaluated from per-piece motion channels.
 *
 * Each preset turns its declared arguments into a default set of [MotionChannelSpec]s (see
 * [resolve]); a build's `channels` map is then layered over those defaults field by field, so an
 * author can change one curve, window, amplitude or keyframe list without restating the rest.
 * [evaluate] runs per piece and per frame on the resolved result, so callers resolve once per frame
 * and evaluate many pieces.
 *
 * Units: lengths are in em of the piece's font size, rotation in degrees.
 */
object MotionPresets {
    /** [EffectArgs] key carrying a build's `channels` overrides (`Map<String, MotionChannelSpec>`). */
    const val CHANNELS_ARG: String = "channels"

    private val OUT_EXPO: Curve = Curve.Bezier(0.16, 1.0, 0.3, 1.0)
    private val IN_EXPO: Curve = Curve.Bezier(0.7, 0.0, 0.84, 0.0)
    private val OUT_QUINT: Curve = Curve.Bezier(0.22, 1.0, 0.36, 1.0)
    private val IN_QUINT: Curve = Curve.Bezier(0.64, 0.0, 0.78, 0.0)

    fun isPreset(effectId: String): Boolean = effectId in MotionPresetDeclarations.ids

    /**
     * Resolved channel set for one build, independent of which piece is drawn.
     *
     * [focusSweep] is set for `focusPull` used as an emphasis: blur and opacity then follow a
     * focal point travelling across the text, unless `channels` overrides them.
     *
     * [revealAlongX] and [revealAlongY] say which axes the reveal mask's tight [revealPaddingEm]
     * edges sit on, i.e. the axes the piece moves along. The other axis gets a generous 1 em.
     */
    @Immutable
    data class ResolvedMotion(
        val effectId: String,
        val kind: EffectBuildKind,
        val channels: Map<String, MotionChannelSpec>,
        val revealPaddingEm: Float?,
        val focusSweep: FocusSweep? = null,
        val revealAlongX: Boolean = false,
        val revealAlongY: Boolean = true,
    )

    @Immutable
    data class FocusSweep(
        val radiusEm: Float,
        val restOpacity: Float,
        val focusFrom: Float,
        val focusTo: Float,
        val focusWidthEm: Float,
        val settle: Boolean,
    )

    /** Builds the channel set for [effectId] from [args], or `null` when it is not a preset. */
    fun resolve(effectId: String, kind: EffectBuildKind, args: EffectArgs): ResolvedMotion? {
        val base = when (effectId) {
            MotionPresetDeclarations.RISE -> resolveRise(kind, args)
            MotionPresetDeclarations.FOCUS_PULL -> resolveFocusPull(kind, args)
            MotionPresetDeclarations.WAVE -> resolveWave(effectId, kind, args)
            MotionPresetDeclarations.TRACKING_BREATHE -> resolveTrackingBreathe(kind, args)
            else -> return null
        }
        val overrides = channelOverrides(args)
        if (overrides.isEmpty()) return base
        val merged = LinkedHashMap(base.channels)
        for ((name, spec) in overrides) {
            merged[name] = spec.mergedOver(base.channels[name])
        }
        // An explicit opacity or blur override takes over from the procedural focus sweep.
        val sweep = base.focusSweep?.takeIf {
            MotionChannelNames.BLUR !in overrides && MotionChannelNames.OPACITY !in overrides
        }
        return base.copy(channels = merged, focusSweep = sweep)
    }

    /**
     * Evaluates [resolved] for one piece at raw build [progress] (`0..1`).
     *
     * [curve], when set, remaps the piece's progress before the channels apply their own curves.
     */
    fun evaluate(
        resolved: ResolvedMotion,
        progress: Float,
        context: EffectEvalContext,
        curve: CurveV2? = null,
    ): LayerState {
        val raw = progress.coerceIn(0f, 1f)
        if (context.reducedMotion) {
            return LayerEffects.evaluateDissolve(resolved.kind, raw, null)
        }
        val p = curve?.sample(raw)?.coerceIn(0f, 1f) ?: raw
        val piece = context.piece
        val em = emPx(context)

        fun value(name: String): Double {
            val spec = resolved.channels[name] ?: return MotionChannelNames.restValue(name)
            return MotionChannelMath.evaluate(
                spec = spec,
                progress = p,
                pieceIndex = piece.index,
                restValue = MotionChannelNames.restValue(name),
                lineIndex = piece.line,
            )
        }

        var opacity = value(MotionChannelNames.OPACITY).toFloat()
        var blurEm = value(MotionChannelNames.BLUR).toFloat()
        resolved.focusSweep?.let { sweep ->
            val weight = focusWeight(sweep, p, context, em)
            blurEm += sweep.radiusEm * weight
            opacity *= 1f - (1f - sweep.restOpacity) * weight
        }
        val tracking = value(MotionChannelNames.TRACKING).toFloat()
        val trackingSlots = piece.indexInLine - (piece.lineLength - 1) / 2f
        val scale = value(MotionChannelNames.SCALE).toFloat()

        val clip = resolved.revealPaddingEm?.let { padEm ->
            val finished = when (resolved.kind) {
                EffectBuildKind.Enter -> raw >= 1f
                EffectBuildKind.Exit -> raw <= 0f
                EffectBuildKind.Emphasis -> false
            }
            if (finished) {
                EffectClip.None
            } else {
                // The tight edge sits across the movement; the generous 1 em on the other axis
                // keeps italic overhangs, accents and neighbours' kerning intact.
                val pad = padEm * em
                val padX = if (resolved.revealAlongX) pad else em
                val padY = if (resolved.revealAlongY) pad else em
                EffectClip.RestBounds(padLeft = padX, padTop = padY, padRight = padX, padBottom = padY)
            }
        } ?: EffectClip.None

        return LayerState(
            alpha = opacity.coerceIn(0f, 1f),
            translationX = (value(MotionChannelNames.OFFSET_X).toFloat() + tracking * trackingSlots) * em,
            translationY = value(MotionChannelNames.OFFSET_Y).toFloat() * em,
            scaleX = scale * value(MotionChannelNames.SCALE_X).toFloat(),
            scaleY = scale * value(MotionChannelNames.SCALE_Y).toFloat(),
            rotationZ = value(MotionChannelNames.ROTATION).toFloat(),
            blurRadiusPx = (blurEm * em).coerceAtLeast(0f),
            clip = clip,
        )
    }

    /** Convenience for single-shot callers: resolve then evaluate. */
    fun evaluateLayer(
        effectId: String,
        kind: EffectBuildKind,
        progress: Float,
        context: EffectEvalContext,
        args: EffectArgs,
        curve: CurveV2?,
    ): LayerState {
        val resolved = resolve(effectId, kind, args) ?: return LayerEffects.evaluateDissolve(kind, progress, curve)
        return evaluate(resolved, progress, context, curve)
    }

    /**
     * Left-to-right rank of each piece inside its line, for spatial channels such as tracking.
     * Pieces arrive in logical order, which runs right-to-left for RTL runs, so ranks come from
     * the pieces' horizontal centres instead. Ties keep logical order.
     */
    fun visualRanksInLine(lines: IntArray, centerX: FloatArray): IntArray {
        require(lines.size == centerX.size) { "lines and centerX must have the same size" }
        val order = lines.indices.sortedWith(compareBy<Int>({ lines[it] }, { centerX[it] }))
        val ranks = IntArray(lines.size)
        var previousLine = Int.MIN_VALUE
        var rank = 0
        for (i in order) {
            if (lines[i] != previousLine) {
                previousLine = lines[i]
                rank = 0
            }
            ranks[i] = rank++
        }
        return ranks
    }

    // ── Presets ─────────────────────────────────────────────────────────────────────────────────

    private fun resolveRise(kind: EffectBuildKind, args: EffectArgs): ResolvedMotion {
        val direction = args.direction8("direction", EffectDirection8.BottomToTop).unitVector()
        val distance = args.float("distance", 0.9f).toDouble()
        val reveal = args.boolean("reveal", true)
        val revealPadding = args.float("revealPadding", 0.12f)
        val fadeWindow = args.float("fadeWindow", 0.35f).toDouble().coerceIn(0.0, 1.0)
        val channels = LinkedHashMap<String, MotionChannelSpec>()
        val dx = direction.x * distance
        val dy = direction.y * distance
        when (kind) {
            EffectBuildKind.Enter, EffectBuildKind.Emphasis -> {
                if (dx != 0.0) channels[MotionChannelNames.OFFSET_X] = MotionChannelSpec(from = -dx, to = 0.0, curve = OUT_EXPO)
                if (dy != 0.0) channels[MotionChannelNames.OFFSET_Y] = MotionChannelSpec(from = -dy, to = 0.0, curve = OUT_EXPO)
                if (fadeWindow > 0.0) {
                    channels[MotionChannelNames.OPACITY] = MotionChannelSpec(from = 0.0, to = 1.0, window = listOf(0.0, fadeWindow))
                }
            }
            EffectBuildKind.Exit -> {
                if (dx != 0.0) channels[MotionChannelNames.OFFSET_X] = MotionChannelSpec(from = 0.0, to = dx, curve = IN_EXPO)
                if (dy != 0.0) channels[MotionChannelNames.OFFSET_Y] = MotionChannelSpec(from = 0.0, to = dy, curve = IN_EXPO)
                if (fadeWindow > 0.0) {
                    channels[MotionChannelNames.OPACITY] = MotionChannelSpec(
                        from = 1.0,
                        to = 0.0,
                        window = listOf(1.0 - fadeWindow, 1.0),
                    )
                }
            }
        }
        return ResolvedMotion(
            effectId = MotionPresetDeclarations.RISE,
            kind = kind,
            channels = channels,
            revealPaddingEm = if (reveal && kind != EffectBuildKind.Emphasis) revealPadding else null,
            revealAlongX = dx != 0.0,
            revealAlongY = dy != 0.0,
        )
    }

    private fun resolveFocusPull(kind: EffectBuildKind, args: EffectArgs): ResolvedMotion {
        val radius = args.float("radius", 0.3f)
        val restOpacity = args.float("restOpacity", 0.45f).coerceIn(0f, 1f)
        val fromScale = args.float("fromScale", 1.06f).toDouble()
        val channels = LinkedHashMap<String, MotionChannelSpec>()
        return when (kind) {
            EffectBuildKind.Enter -> {
                channels[MotionChannelNames.BLUR] = MotionChannelSpec(from = radius.toDouble(), to = 0.0, curve = OUT_QUINT)
                channels[MotionChannelNames.OPACITY] = MotionChannelSpec(from = 0.0, to = 1.0, window = listOf(0.0, 0.6))
                if (fromScale != 1.0) {
                    channels[MotionChannelNames.SCALE] = MotionChannelSpec(from = fromScale, to = 1.0, curve = OUT_QUINT)
                }
                ResolvedMotion(MotionPresetDeclarations.FOCUS_PULL, kind, channels, revealPaddingEm = null)
            }
            EffectBuildKind.Exit -> {
                channels[MotionChannelNames.BLUR] = MotionChannelSpec(from = 0.0, to = radius.toDouble(), curve = IN_QUINT)
                channels[MotionChannelNames.OPACITY] = MotionChannelSpec(from = 1.0, to = 0.0, window = listOf(0.4, 1.0))
                if (fromScale != 1.0) {
                    channels[MotionChannelNames.SCALE] = MotionChannelSpec(from = 1.0, to = fromScale, curve = IN_QUINT)
                }
                ResolvedMotion(MotionPresetDeclarations.FOCUS_PULL, kind, channels, revealPaddingEm = null)
            }
            EffectBuildKind.Emphasis -> ResolvedMotion(
                effectId = MotionPresetDeclarations.FOCUS_PULL,
                kind = kind,
                channels = channels,
                revealPaddingEm = null,
                focusSweep = FocusSweep(
                    radiusEm = radius,
                    restOpacity = restOpacity,
                    focusFrom = args.float("focusFrom", 0f),
                    focusTo = args.float("focusTo", 1f),
                    focusWidthEm = args.float("focusWidth", 1.2f).coerceAtLeast(0.05f),
                    settle = args.boolean("settle", true),
                ),
            )
        }
    }

    private fun resolveWave(effectId: String, kind: EffectBuildKind, args: EffectArgs): ResolvedMotion {
        val shape = parseShape(args.string("shape", "sine"))
        val cycles = args.float("cycles", 2f).toDouble()
        val phasePerPiece = args.float("phasePerPiece", 0.08f).toDouble()
        val fade = args.float("fade", 0.15f).toDouble().coerceIn(0.0, 0.5)
        fun wave(amplitude: Double) = MotionWaveSpec(
            amplitude = amplitude,
            shape = shape,
            cycles = cycles,
            phasePerPiece = phasePerPiece,
            fade = fade,
        )
        val channels = LinkedHashMap<String, MotionChannelSpec>()
        // Positive lift moves up the screen, which is negative y.
        val lift = args.float("amplitude", 0.18f).toDouble()
        if (lift != 0.0) channels[MotionChannelNames.OFFSET_Y] = MotionChannelSpec(wave = wave(-lift))
        val tilt = args.float("rotation", 0f).toDouble()
        if (tilt != 0.0) channels[MotionChannelNames.ROTATION] = MotionChannelSpec(wave = wave(tilt))
        val swell = args.float("scale", 0f).toDouble()
        if (swell != 0.0) channels[MotionChannelNames.SCALE] = MotionChannelSpec(wave = wave(swell))
        return ResolvedMotion(effectId, kind, channels, revealPaddingEm = null)
    }

    private fun resolveTrackingBreathe(kind: EffectBuildKind, args: EffectArgs): ResolvedMotion {
        val amplitude = args.float("amplitude", 0.12f).toDouble()
        val channels = LinkedHashMap<String, MotionChannelSpec>()
        if (amplitude != 0.0) {
            // 0.5 * (1 - cos): opens from rest to `amplitude` and closes again within each cycle.
            channels[MotionChannelNames.TRACKING] = MotionChannelSpec(
                wave = MotionWaveSpec(
                    amplitude = amplitude / 2.0,
                    offset = amplitude / 2.0,
                    shape = parseShape(args.string("shape", "sine")),
                    cycles = args.float("cycles", 1f).toDouble(),
                    phase = 0.25,
                    phasePerLine = args.float("phasePerLine", 0.15f).toDouble(),
                    fade = args.float("fade", 0.12f).toDouble().coerceIn(0.0, 0.5),
                ),
            )
        }
        return ResolvedMotion(MotionPresetDeclarations.TRACKING_BREATHE, kind, channels, revealPaddingEm = null)
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────────────────────

    /**
     * Size of 1 em in px: the piece's font size when known, otherwise one line of the node's height
     * (height / 1.2) for whole-node delivery, with `16 * density` as the last resort.
     */
    internal fun emPx(context: EffectEvalContext): Float {
        val piece = context.piece
        if (piece.emPx > 0f) return piece.emPx
        val lineGuess = context.nodeBounds.height / 1.2f
        return if (lineGuess.isFinite() && lineGuess > 1f) lineGuess else 16f * context.density
    }

    /**
     * 0 when the piece sits in the focal plane, 1 when it is fully out of focus. For a single
     * whole-node piece the focal point crosses the node, so it pulls into focus and out again.
     */
    private fun focusWeight(sweep: FocusSweep, progress: Float, context: EffectEvalContext, em: Float): Float {
        // Whole-node delivery has no text box: the node itself is the text the focal point crosses.
        val text = context.piece.textBounds ?: context.nodeBounds
        val focusFraction = sweep.focusFrom + (sweep.focusTo - sweep.focusFrom) * smoothstep(progress)
        val focusX = text.left + text.width * focusFraction
        val pieceX = context.nodeBounds.center.x
        val distanceEm = abs(pieceX - focusX) / em
        val w = (distanceEm / sweep.focusWidthEm).coerceIn(0f, 1f)
        val weight = smoothstep(w)
        if (!sweep.settle) return weight
        // Ease the blur in over the first and out over the last fifth so the text starts and ends sharp.
        val ramp = (minOf(progress, 1f - progress) / 0.2f).coerceIn(0f, 1f)
        return weight * smoothstep(ramp)
    }

    private fun smoothstep(x: Float): Float {
        val t = x.coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun parseShape(raw: String): WaveShape = when (raw.trim().lowercase()) {
        "triangle" -> WaveShape.Triangle
        "square" -> WaveShape.Square
        "saw" -> WaveShape.Saw
        "noise" -> WaveShape.Noise
        else -> WaveShape.Sine
    }

    @Suppress("UNCHECKED_CAST")
    private fun channelOverrides(args: EffectArgs): Map<String, MotionChannelSpec> {
        val raw = args.values[CHANNELS_ARG] as? Map<*, *> ?: return emptyMap()
        val out = LinkedHashMap<String, MotionChannelSpec>(raw.size)
        for ((k, v) in raw) {
            if (k is String && v is MotionChannelSpec) out[k] = v
        }
        return out
    }
}
