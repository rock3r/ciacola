package dev.sebastiano.ciacola.document

import androidx.compose.runtime.Immutable
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Author-facing description of how one animatable channel of a build piece changes over the
 * piece's progress.
 *
 * A channel value is `base + wave`:
 * - `base` comes from [keyframes] when present, otherwise from a tween between [from] and [to]
 *   shaped by [curve] inside the [window] slice of progress. When neither is set the channel sits
 *   at its rest value.
 * - `wave` is an optional oscillation added on top (see [MotionWaveSpec]).
 *
 * Every field is optional so a build's `channels` entry can override a single aspect of a preset's
 * default channel (for example only its `curve`). See [mergedOver].
 */
@Immutable
@Serializable
data class MotionChannelSpec(
    val from: Double? = null,
    val to: Double? = null,
    val curve: Curve? = null,
    /** `[start, end]` slice of the piece's progress (`0..1`) that the tween or keyframes span. */
    val window: List<Double>? = null,
    val keyframes: List<MotionKeyframeSpec>? = null,
    val wave: MotionWaveSpec? = null,
) {
    /** Returns this spec layered over [base]: fields set here win, the rest come from [base]. */
    fun mergedOver(base: MotionChannelSpec?): MotionChannelSpec {
        if (base == null) return this
        return MotionChannelSpec(
            from = from ?: base.from,
            to = to ?: base.to,
            curve = curve ?: base.curve,
            window = window ?: base.window,
            keyframes = keyframes ?: base.keyframes,
            wave = wave?.mergedOver(base.wave) ?: base.wave,
        )
    }
}

/** One stop of a keyframed channel. [curve] shapes the segment that ends at this stop. */
@Immutable
@Serializable
data class MotionKeyframeSpec(
    val at: Double,
    val value: Double,
    val curve: Curve? = null,
)

/**
 * Oscillation added to a channel.
 *
 * - [amplitude]: peak deviation from [offset], in the channel's units.
 * - [cycles]: full cycles across the piece's progress.
 * - [phase]: starting phase as a fraction of one cycle.
 * - [phasePerPiece]: extra phase per piece index, as a fraction of one cycle (the travelling-wave
 *   look of Cavalry staggers).
 * - [phasePerLine]: extra phase per line index, as a fraction of one cycle.
 * - [fade]: fraction of the progress spent fading the wave in at the start and out at the end, so
 *   it starts and ends at rest.
 * - [seed]: seed for the deterministic [WaveShape.Noise] shape.
 */
@Immutable
@Serializable
data class MotionWaveSpec(
    val amplitude: Double? = null,
    val offset: Double? = null,
    val shape: WaveShape? = null,
    val cycles: Double? = null,
    val phase: Double? = null,
    val phasePerPiece: Double? = null,
    val phasePerLine: Double? = null,
    val fade: Double? = null,
    val seed: Int? = null,
) {
    fun mergedOver(base: MotionWaveSpec?): MotionWaveSpec {
        if (base == null) return this
        return MotionWaveSpec(
            amplitude = amplitude ?: base.amplitude,
            offset = offset ?: base.offset,
            shape = shape ?: base.shape,
            cycles = cycles ?: base.cycles,
            phase = phase ?: base.phase,
            phasePerPiece = phasePerPiece ?: base.phasePerPiece,
            phasePerLine = phasePerLine ?: base.phasePerLine,
            fade = fade ?: base.fade,
            seed = seed ?: base.seed,
        )
    }
}

@Serializable
enum class WaveShape {
    @SerialName("sine") Sine,
    @SerialName("triangle") Triangle,
    @SerialName("square") Square,
    @SerialName("saw") Saw,
    @SerialName("noise") Noise,
}

/**
 * Names and units of the channels that motion presets animate per piece.
 *
 * Lengths are in em (multiples of the piece's font size) so a preset looks the same on a hero
 * title and on a caption. Rotation is in degrees, opacity and scale are unitless.
 */
object MotionChannelNames {
    const val OPACITY: String = "opacity"
    const val OFFSET_X: String = "offsetX"
    const val OFFSET_Y: String = "offsetY"
    const val SCALE: String = "scale"
    const val SCALE_X: String = "scaleX"
    const val SCALE_Y: String = "scaleY"
    const val ROTATION: String = "rotation"
    const val BLUR: String = "blur"
    const val TRACKING: String = "tracking"

    val all: Set<String> = linkedSetOf(
        OPACITY, OFFSET_X, OFFSET_Y, SCALE, SCALE_X, SCALE_Y, ROTATION, BLUR, TRACKING,
    )

    /** Value a channel has when nothing animates it. */
    fun restValue(name: String): Double = when (name) {
        OPACITY, SCALE, SCALE_X, SCALE_Y -> 1.0
        else -> 0.0
    }
}

/**
 * Pure evaluation of [MotionChannelSpec]s, shared by the planner-side tests and the renderer.
 */
object MotionChannelMath {

    /**
     * Evaluates [spec] at piece progress [progress] (`0..1`) for the piece at [pieceIndex] on line
     * [lineIndex].
     * [restValue] is used when the spec defines no tween or keyframes. [curveEvaluator] maps a
     * curve and a fraction to an eased fraction; it defaults to [CurveEvaluator.evaluateFraction].
     */
    fun evaluate(
        spec: MotionChannelSpec,
        progress: Float,
        pieceIndex: Int,
        restValue: Double,
        lineIndex: Int = 0,
        curveEvaluator: (Curve, Float) -> Float = CurveEvaluator::evaluateFraction,
    ): Double {
        val p = progress.coerceIn(0f, 1f)
        val base = baseValue(spec, p, restValue, curveEvaluator)
        val wave = spec.wave?.let { waveValue(it, p, pieceIndex, lineIndex) } ?: 0.0
        return base + wave
    }

    private fun baseValue(
        spec: MotionChannelSpec,
        progress: Float,
        restValue: Double,
        curveEvaluator: (Curve, Float) -> Float,
    ): Double {
        val local = windowed(progress, spec.window)
        val keys = spec.keyframes
        if (!keys.isNullOrEmpty()) {
            return keyframeValue(keys, local, curveEvaluator)
        }
        val from = spec.from
        val to = spec.to
        if (from == null && to == null) return restValue
        val a = from ?: restValue
        val b = to ?: restValue
        val e = spec.curve?.let { curveEvaluator(it, local) } ?: local
        return a + (b - a) * e
    }

    /** Maps [progress] into the `[start, end]` [window] (clamped), or returns it unchanged. */
    fun windowed(progress: Float, window: List<Double>?): Float {
        if (window == null || window.size != 2) return progress
        val start = window[0].toFloat()
        val end = window[1].toFloat()
        if (end <= start) return if (progress >= end) 1f else 0f
        return ((progress - start) / (end - start)).coerceIn(0f, 1f)
    }

    private fun keyframeValue(
        keys: List<MotionKeyframeSpec>,
        progress: Float,
        curveEvaluator: (Curve, Float) -> Float,
    ): Double {
        val first = keys.first()
        if (progress <= first.at) return first.value
        for (i in 1 until keys.size) {
            val next = keys[i]
            if (progress <= next.at) {
                val prev = keys[i - 1]
                val span = (next.at - prev.at).toFloat()
                if (span <= 0f) return next.value
                val f = ((progress - prev.at.toFloat()) / span).coerceIn(0f, 1f)
                val e = next.curve?.let { curveEvaluator(it, f) } ?: f
                return prev.value + (next.value - prev.value) * e
            }
        }
        return keys.last().value
    }

    /** Evaluates [wave] at [progress] for [pieceIndex] on [lineIndex], including its fade envelope. */
    fun waveValue(wave: MotionWaveSpec, progress: Float, pieceIndex: Int, lineIndex: Int = 0): Double {
        val amplitude = wave.amplitude ?: 0.0
        val offset = wave.offset ?: 0.0
        if (amplitude == 0.0 && offset == 0.0) return 0.0
        val cycles = wave.cycles ?: 1.0
        val phase = (wave.phase ?: 0.0) +
            (wave.phasePerPiece ?: 0.0) * pieceIndex +
            (wave.phasePerLine ?: 0.0) * lineIndex
        val t = progress * cycles - phase
        val shaped = shapeAt(wave.shape ?: WaveShape.Sine, t, wave.seed ?: 1)
        return (offset + amplitude * shaped) * envelope(progress, wave.fade ?: 0.0)
    }

    /** Value of [shape] at cycle position [t] (one cycle per unit), in `-1..1`. */
    fun shapeAt(shape: WaveShape, t: Double, seed: Int = 1): Double {
        val frac = t - floor(t)
        return when (shape) {
            WaveShape.Sine -> sin(frac * 2.0 * kotlin.math.PI)
            // Same phase as the sine: 0 at the start, +1 at a quarter, -1 at three quarters.
            WaveShape.Triangle -> when {
                frac < 0.25 -> 4.0 * frac
                frac < 0.75 -> 2.0 - 4.0 * frac
                else -> 4.0 * frac - 4.0
            }
            WaveShape.Square -> if (frac < 0.5) 1.0 else -1.0
            WaveShape.Saw -> 2.0 * frac - 1.0
            WaveShape.Noise -> smoothNoise(t, seed)
        }
    }

    private fun envelope(progress: Float, fade: Double): Double {
        if (fade <= 0.0) return 1.0
        val f = fade.coerceAtMost(0.5)
        val p = progress.toDouble()
        val inRamp = (p / f).coerceIn(0.0, 1.0)
        val outRamp = ((1.0 - p) / f).coerceIn(0.0, 1.0)
        val ramp = minOf(inRamp, outRamp)
        // Smoothstep so the wave leaves and returns to rest without a velocity jump.
        return ramp * ramp * (3.0 - 2.0 * ramp)
    }

    /** Deterministic 1D value noise in `-1..1`, smoothly interpolated between integer lattice points. */
    private fun smoothNoise(t: Double, seed: Int): Double {
        val i = floor(t).toLong()
        val f = t - i
        val a = latticeValue(i, seed)
        val b = latticeValue(i + 1, seed)
        val s = f * f * (3.0 - 2.0 * f)
        return a + (b - a) * s
    }

    private fun latticeValue(i: Long, seed: Int): Double {
        var x = i * -0x61c8864680b583ebL xor seed.toLong() * -0x40a7b892e31b1a47L
        x = (x xor (x ushr 30)) * -0x40a7b892e31b1a47L
        x = (x xor (x ushr 27)) * -0x6b2fb644ecceee15L
        x = x xor (x ushr 31)
        val unit = (x ushr 11).toDouble() / (1L shl 53).toDouble()
        return unit * 2.0 - 1.0
    }
}

/**
 * Stagger offsets with Cavalry-style distribution and falloff.
 *
 * The default (linear distribution, no curve, no `totalMs`) is exactly `rank * ms`, so existing
 * decks are unchanged.
 */
object StaggerShaping {

    /** Start offset in ms for the piece or node at [rank] out of [rankCount] ranks. */
    fun delayMs(stagger: Stagger, rank: Int, rankCount: Int): Int {
        val isPlainLinear = stagger.distribution == StaggerDistribution.Linear &&
            stagger.curve == null &&
            stagger.totalMs == null
        if (isPlainLinear) return rank * stagger.ms
        if (rankCount <= 1) return 0
        val last = rankCount - 1
        val totalMs = stagger.totalMs ?: (stagger.ms * last)
        val u = normalizedPosition(stagger, rank.coerceIn(0, last), last)
        val shaped = stagger.curve?.let { CurveEvaluator.evaluateFraction(it, u.toFloat()).toDouble() } ?: u
        return (shaped * totalMs).roundToInt().coerceAtLeast(0)
    }

    private fun normalizedPosition(stagger: Stagger, rank: Int, last: Int): Double {
        val linear = rank.toDouble() / last.toDouble()
        return when (stagger.distribution) {
            StaggerDistribution.Linear -> linear
            StaggerDistribution.FromCenter -> abs(linear - 0.5) * 2.0
            StaggerDistribution.FromEdges -> 1.0 - abs(linear - 0.5) * 2.0
            StaggerDistribution.Random -> randomUnit(stagger.seed, rank)
        }
    }

    private fun randomUnit(seed: Int, rank: Int): Double {
        var x = seed.toLong() * -0x61c8864680b583ebL xor (rank.toLong() + 1) * -0x40a7b892e31b1a47L
        x = (x xor (x ushr 30)) * -0x40a7b892e31b1a47L
        x = (x xor (x ushr 27)) * -0x6b2fb644ecceee15L
        x = x xor (x ushr 31)
        return (x ushr 11).toDouble() / (1L shl 53).toDouble()
    }
}
