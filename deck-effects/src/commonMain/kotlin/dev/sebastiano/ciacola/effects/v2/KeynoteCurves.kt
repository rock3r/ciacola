package dev.sebastiano.ciacola.effects.v2

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.runtime.Immutable
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Exact catalog of all 15 Apple Keynote easing presets extracted from the reference KPF manifests
 * (4 standard timing curves + 11 bespoke Apple `cubic-bezier(...)` curves).
 */
object KeynoteCurves {
    const val LINEAR: String = "keynote.none"
    const val EASE_IN: String = "keynote.easeIn"
    const val EASE_OUT: String = "keynote.easeOut"
    const val EASE_IN_OUT: String = "keynote.easeInOut"

    const val FADE_MOVE_ALPHA: String = "keynote.fadeMoveAlpha"
    const val FADE_MOVE_POS: String = "keynote.fadeMovePos"
    const val FADE_MOVE_EXIT: String = "keynote.fadeMoveExit"
    const val MOVE_IN: String = "keynote.moveIn"
    const val MOVE_OUT: String = "keynote.moveOut"
    const val MOVE_OUT_DECEL: String = "keynote.moveOutDecel"
    const val MOVE_OUT_ACCEL: String = "keynote.moveOutAccel"
    const val PUSH: String = "keynote.push"
    const val REVEAL: String = "keynote.reveal"
    const val SLIDE: String = "keynote.slide"
    const val ZOOM_BIG_EXIT: String = "keynote.zoomBigExit"

    val Linear: Easing = LinearEasing
    val EaseIn: Easing = CubicBezierEasing(0.42f, 0.0f, 1.0f, 1.0f)
    val EaseOut: Easing = CubicBezierEasing(0.0f, 0.0f, 0.58f, 1.0f)
    val EaseInOut: Easing = CubicBezierEasing(0.42f, 0.0f, 0.58f, 1.0f)

    val FadeMoveAlpha: Easing = CubicBezierEasing(0.7028f, 0.0f, 0.3007f, 0.9992f)
    val FadeMovePos: Easing = CubicBezierEasing(0.311f, 0.208f, 0.2013f, 1.0f)
    val FadeMoveExit: Easing = CubicBezierEasing(0.7003f, 0.0f, 0.5595f, 0.7566f)
    val MoveIn: Easing = CubicBezierEasing(0.4063f, 0.4998f, 0.6016f, 1.0f)
    val MoveOut: Easing = CubicBezierEasing(0.3983f, 0.0f, 0.5904f, 0.5004f)
    val MoveOutDecel: Easing = CubicBezierEasing(0.3576f, 0.0f, 0.6662f, 1.0f)
    val MoveOutAccel: Easing = CubicBezierEasing(0.3642f, 0.0f, 0.7397f, 0.5179f)
    val Push: Easing = CubicBezierEasing(0.4971f, 0.0f, 0.4981f, 0.996f)
    val Reveal: Easing = CubicBezierEasing(0.3747f, 0.0f, 0.4752f, 0.0877f)
    val Slide: Easing = CubicBezierEasing(0.5532f, 0.8018f, 0.6019f, 1.0f)
    val ZoomBigExit: Easing = CubicBezierEasing(0.742f, 0.0f, 0.642f, 1.0f)

    /**
     * All 15 canonical Keynote curves keyed by their canonical preset name (`keynote.*`).
     */
    val all: Map<String, Easing> = linkedMapOf(
        LINEAR to Linear,
        EASE_IN to EaseIn,
        EASE_OUT to EaseOut,
        EASE_IN_OUT to EaseInOut,
        FADE_MOVE_ALPHA to FadeMoveAlpha,
        FADE_MOVE_POS to FadeMovePos,
        FADE_MOVE_EXIT to FadeMoveExit,
        MOVE_IN to MoveIn,
        MOVE_OUT to MoveOut,
        MOVE_OUT_DECEL to MoveOutDecel,
        MOVE_OUT_ACCEL to MoveOutAccel,
        PUSH to Push,
        REVEAL to Reveal,
        SLIDE to Slide,
        ZOOM_BIG_EXIT to ZoomBigExit,
    )

    private val aliases: Map<String, Easing> = mapOf(
        "linear" to Linear,
        "none" to Linear,
        "ease" to CubicBezierEasing(0.25f, 0.1f, 0.25f, 1.0f),
        "easein" to EaseIn,
        "ease-in" to EaseIn,
        "ease_in" to EaseIn,
        "easeout" to EaseOut,
        "ease-out" to EaseOut,
        "ease_out" to EaseOut,
        "easeinout" to EaseInOut,
        "ease-in-out" to EaseInOut,
        "ease_in_out" to EaseInOut,
        "fastoutslowin" to CubicBezierEasing(0.4f, 0.0f, 0.2f, 1.0f),
        "easeincubic" to CubicBezierEasing(0.32f, 0.0f, 0.67f, 0.0f),
        "easeoutcubic" to CubicBezierEasing(0.33f, 1.0f, 0.68f, 1.0f),
        "easeinoutcubic" to CubicBezierEasing(0.65f, 0.0f, 0.35f, 1.0f),
        "fademovealpha" to FadeMoveAlpha,
        "fademovepos" to FadeMovePos,
        "fademoveexit" to FadeMoveExit,
        "fademoveinposition" to CubicBezierEasing(0.22f, 0.61f, 0.36f, 1.0f),
        "fademoveinalpha" to CubicBezierEasing(0.31f, 0.21f, 0.2f, 1.0f),
        "fademoveout" to CubicBezierEasing(0.42f, 0.0f, 0.78f, 0.39f),
        "movein" to MoveIn,
        "moveinbounce" to CubicBezierEasing(0.22f, 1.18f, 0.36f, 1.0f),
        "moveout" to MoveOut,
        "moveoutdecel" to MoveOutDecel,
        "moveoutaccel" to MoveOutAccel,
        "pop" to CubicBezierEasing(0.17f, 1.32f, 0.32f, 1.0f),
        "zoom" to CubicBezierEasing(0.2f, 0.85f, 0.3f, 1.0f),
        "magicmove" to CubicBezierEasing(0.4f, 0.0f, 0.2f, 1.0f),
        "wipe" to CubicBezierEasing(0.25f, 0.1f, 0.25f, 1.0f),
        "linedraw" to CubicBezierEasing(0.37f, 0.0f, 0.63f, 1.0f),
        "fromdarkness" to CubicBezierEasing(0.25f, 0.1f, 0.25f, 1.0f),
        "push" to Push,
        "reveal" to Reveal,
        "slide" to Slide,
        "zoombigexit" to ZoomBigExit,
    )

    /** Resolves a canonical preset name (`keynote.*`) or short alias into its [Easing]. */
    fun byName(name: String): Easing? {
        all[name]?.let { return it }
        val trimmed = name.trim()
        all[trimmed]?.let { return it }
        val normalized = trimmed.removePrefix("keynote.").lowercase()
        return aliases[normalized]
    }
}

/**
 * Deterministic timing curve supporting named presets, cubic-beziers (including overshoot
 * control points outside `[0, 1]`), and analytical damped springs with closed-form settle time.
 *
 * Time input is clamped to `[0, 1]`; curve output is **not** clamped so spring and back-ease
 * overshoots remain intact.
 */
@Immutable
sealed interface CurveV2 {
    /** Evaluates this curve at normalized time [fraction] (`0..1`). */
    fun sample(fraction: Float, durationMs: Float = 1000f): Float

    @Immutable
    data class Preset(val name: String) : CurveV2 {
        private val easing: Easing = KeynoteCurves.byName(name)
            ?: error("Unknown KeynoteCurve preset '$name'")

        override fun sample(fraction: Float, durationMs: Float): Float {
            val t = fraction.coerceIn(0f, 1f)
            if (t <= 0f) return 0f
            if (t >= 1f) return 1f
            return easing.transform(t)
        }
    }

    @Immutable
    data class Bezier(
        val x1: Float,
        val y1: Float,
        val x2: Float,
        val y2: Float,
    ) : CurveV2 {
        init {
            require(x1 in 0f..1f && x2 in 0f..1f) {
                "Cubic-bezier X control points must be within [0, 1] (got x1=$x1, x2=$x2)"
            }
        }

        private val easing = CubicBezierEasing(x1, y1, x2, y2)

        override fun sample(fraction: Float, durationMs: Float): Float {
            val t = fraction.coerceIn(0f, 1f)
            if (t <= 0f) return 0f
            if (t >= 1f) return 1f
            return easing.transform(t)
        }
    }

    /**
     * Analytical unit-mass spring (`m = 1`, damping ratio `zeta = damping`, spring constant
     * `k = stiffness`).
     *
     * Settles when `|x(t) - 1| < 0.001` and `|v(t)| < 0.001` and remains within that band.
     * At or after [settleTimeMs], [evaluateAtMs] returns exact `1f`.
     */
    @Immutable
    data class Spring(
        val damping: Float = 0.72f,
        val stiffness: Float = 260f,
    ) : CurveV2 {
        init {
            require(damping > 0f && damping.isFinite()) { "Spring damping ratio must be > 0 (got $damping)" }
            require(stiffness > 0f && stiffness.isFinite()) { "Spring stiffness must be > 0 (got $stiffness)" }
        }

        private val omegaN: Double = sqrt(stiffness.toDouble())
        private val settleSec: Double = computeSettleTimeSec()

        /** Settling duration in milliseconds (`|x - 1| < 0.001 && |v| < 0.001`). */
        fun settleTimeMs(): Int = kotlin.math.ceil(settleSec * 1000.0).toInt().coerceAtLeast(1)

        /** Evaluates exact displacement at [elapsedMs], preserving overshoot and snapping to `1f` at settle. */
        fun evaluateAtMs(elapsedMs: Float): Float {
            if (elapsedMs <= 0f) return 0f
            val tSec = elapsedMs.toDouble() / 1000.0
            if (tSec >= settleSec) return 1f
            return positionAtSec(tSec).toFloat()
        }

        /** Evaluates displacement at normalized time [fraction] over [durationMs] (or [settleTimeMs] if non-positive). */
        override fun sample(fraction: Float, durationMs: Float): Float {
            val t = fraction.coerceIn(0f, 1f)
            if (t <= 0f) return 0f
            if (t >= 1f) return 1f
            val effectiveSec = if (durationMs > 0f) {
                minOf(durationMs.toDouble() / 1000.0, settleSec)
            } else {
                settleSec
            }
            return positionAtSec(t.toDouble() * effectiveSec).toFloat()
        }

        internal fun positionAndVelocityAtSec(t: Double): Pair<Double, Double> {
            if (t <= 0.0) return 0.0 to 0.0
            val zeta = damping.toDouble()
            val wn = omegaN
            return when {
                zeta < 1.0 - 1e-6 -> {
                    // Underdamped: x(t) = 1 - e^(-zeta*wn*t) * (cos(wd*t) + (zeta/sqrt(1-zeta^2)) * sin(wd*t))
                    val wd = wn * sqrt(1.0 - zeta * zeta)
                    val decay = exp(-zeta * wn * t)
                    val c = cos(wd * t)
                    val s = sin(wd * t)
                    val ratio = zeta / sqrt(1.0 - zeta * zeta)
                    val x = 1.0 - decay * (c + ratio * s)
                    val v = decay * (wn * wn / wd) * s
                    x to v
                }
                zeta <= 1.0 + 1e-6 -> {
                    // Critically damped: x(t) = 1 - e^(-wn*t) * (1 + wn*t)
                    val decay = exp(-wn * t)
                    val x = 1.0 - decay * (1.0 + wn * t)
                    val v = decay * wn * wn * t
                    x to v
                }
                else -> {
                    // Overdamped: two real negative roots r1, r2
                    val disc = sqrt(zeta * zeta - 1.0)
                    val r1 = -wn * (zeta - disc)
                    val r2 = -wn * (zeta + disc)
                    val denom = r1 - r2
                    val c1 = r2 / denom
                    val c2 = -r1 / denom
                    val e1 = exp(r1 * t)
                    val e2 = exp(r2 * t)
                    val x = 1.0 + c1 * e1 + c2 * e2
                    val v = c1 * r1 * e1 + c2 * r2 * e2
                    x to v
                }
            }
        }

        private fun positionAtSec(t: Double): Double = positionAndVelocityAtSec(t).first

        private fun computeSettleTimeSec(): Double {
            val epsPos = 0.001
            val epsVel = 0.001
            val zeta = damping.toDouble()
            val wn = omegaN
            // Conservative upper bound where envelope is guaranteed below tolerance.
            val decayRate = if (zeta < 1.0) {
                zeta * wn
            } else {
                wn * (zeta - sqrt(max(0.0, zeta * zeta - 1.0)))
            }
            val safeDecay = max(decayRate, 1e-4)
            val maxT = minOf(30.0, (16.0 + kotlin.math.ln(max(1.0, wn))) / safeDecay)

            // Scan backward in fine steps to guarantee the spring never re-exits the settle band
            // (crucial for underdamped springs where x(t) passes through 1.0 with nonzero velocity).
            val step = minOf(0.002, max(0.0002, (PI / max(wn, 1.0)) * 0.05))
            var t = maxT
            var lastOutside = 0.0
            while (t >= 0.0) {
                val (x, v) = positionAndVelocityAtSec(t)
                if (abs(x - 1.0) >= epsPos || abs(v) >= epsVel) {
                    lastOutside = t
                    break
                }
                t -= step
            }
            return (lastOutside + step).coerceAtLeast(0.001)
        }
    }

    companion object {
        val Linear: CurveV2 = Preset(KeynoteCurves.LINEAR)
        val EaseIn: CurveV2 = Preset(KeynoteCurves.EASE_IN)
        val EaseOut: CurveV2 = Preset(KeynoteCurves.EASE_OUT)
        val EaseInOut: CurveV2 = Preset(KeynoteCurves.EASE_IN_OUT)
    }
}
