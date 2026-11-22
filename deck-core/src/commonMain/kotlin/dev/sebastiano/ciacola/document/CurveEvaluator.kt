package dev.sebastiano.ciacola.document

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.runtime.Immutable
import dev.sebastiano.ciacola.theme.REDUCED_MOTION_FADE_DURATION_MS
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pure curve evaluation, analytical spring solver, and shared Warp choreography schedule.
 */
object CurveEvaluator {

    const val SPRING_MIN_DAMPING: Double = 0.2
    const val SPRING_MAX_DAMPING: Double = 1.5
    const val SPRING_MIN_STIFFNESS: Double = 50.0
    const val SPRING_MAX_STIFFNESS: Double = 4000.0
    const val SPRING_SETTLE_TOLERANCE: Double = 0.001

    /**
     * Built-in standard and Keynote bezier presets (`keynote.*` extracted by `kpf-audit`).
     */
    val PRESET_BEZIERS: Map<String, Curve.Bezier> = mapOf(
        "linear" to Curve.Bezier(0.0, 0.0, 1.0, 1.0),
        "ease" to Curve.Bezier(0.25, 0.1, 0.25, 1.0),
        "easeIn" to Curve.Bezier(0.42, 0.0, 1.0, 1.0),
        "easeOut" to Curve.Bezier(0.0, 0.0, 0.58, 1.0),
        "easeInOut" to Curve.Bezier(0.42, 0.0, 0.58, 1.0),
        "fastOutSlowIn" to Curve.Bezier(0.4, 0.0, 0.2, 1.0),
        "easeInCubic" to Curve.Bezier(0.32, 0.0, 0.67, 0.0),
        "easeOutCubic" to Curve.Bezier(0.33, 1.0, 0.68, 1.0),
        "easeInOutCubic" to Curve.Bezier(0.65, 0.0, 0.35, 1.0),
        // Exact 15 Apple Keynote presets (aligned with KeynoteCurves in deck-effects).
        "keynote.none" to Curve.Bezier(0.0, 0.0, 1.0, 1.0),
        "keynote.easeIn" to Curve.Bezier(0.42, 0.0, 1.0, 1.0),
        "keynote.easeOut" to Curve.Bezier(0.0, 0.0, 0.58, 1.0),
        "keynote.easeInOut" to Curve.Bezier(0.42, 0.0, 0.58, 1.0),
        "keynote.fadeMoveAlpha" to Curve.Bezier(0.7028, 0.0, 0.3007, 0.9992),
        "keynote.fadeMovePos" to Curve.Bezier(0.311, 0.208, 0.2013, 1.0),
        "keynote.fadeMoveExit" to Curve.Bezier(0.7003, 0.0, 0.5595, 0.7566),
        "keynote.moveIn" to Curve.Bezier(0.4063, 0.4998, 0.6016, 1.0),
        "keynote.moveOut" to Curve.Bezier(0.3983, 0.0, 0.5904, 0.5004),
        "keynote.moveOutDecel" to Curve.Bezier(0.3576, 0.0, 0.6662, 1.0),
        "keynote.moveOutAccel" to Curve.Bezier(0.3642, 0.0, 0.7397, 0.5179),
        "keynote.push" to Curve.Bezier(0.4971, 0.0, 0.4981, 0.996),
        "keynote.reveal" to Curve.Bezier(0.3747, 0.0, 0.4752, 0.0877),
        "keynote.slide" to Curve.Bezier(0.5532, 0.8018, 0.6019, 1.0),
        "keynote.zoomBigExit" to Curve.Bezier(0.742, 0.0, 0.642, 1.0),
        // Legacy / document convenience aliases.
        "keynote.fadeMoveInPosition" to Curve.Bezier(0.22, 0.61, 0.36, 1.0),
        "keynote.fadeMoveInAlpha" to Curve.Bezier(0.31, 0.21, 0.2, 1.0),
        "keynote.fadeMoveOut" to Curve.Bezier(0.42, 0.0, 0.78, 0.39),
        "keynote.moveInBounce" to Curve.Bezier(0.22, 1.18, 0.36, 1.0),
        "keynote.pop" to Curve.Bezier(0.17, 1.32, 0.32, 1.0),
        "keynote.zoom" to Curve.Bezier(0.2, 0.85, 0.3, 1.0),
        "keynote.magicMove" to Curve.Bezier(0.4, 0.0, 0.2, 1.0),
        "keynote.wipe" to Curve.Bezier(0.25, 0.1, 0.25, 1.0),
        "keynote.lineDraw" to Curve.Bezier(0.37, 0.0, 0.63, 1.0),
        "keynote.fromDarkness" to Curve.Bezier(0.25, 0.1, 0.25, 1.0),
    )

    fun isKnownPreset(name: String): Boolean = name in PRESET_BEZIERS

    /**
     * Evaluates [curve] at elapsed time [elapsedMs] within a track of [durationMs]:
     * - Time is clamped to `[0, durationMs]`; output is NOT clamped so bezier and spring overshoot
     *   are preserved.
     * - Zero-duration tracks behave as a step: `0f` for `elapsedMs < 0f`, `1f` for `elapsedMs >= 0f`.
     * - Spring tracks return exact `1f` from their settle time onwards.
     */
    fun evaluateAtMs(curve: Curve, elapsedMs: Float, durationMs: Int): Float {
        if (durationMs <= 0) {
            return if (elapsedMs >= 0f) 1f else 0f
        }
        if (elapsedMs <= 0f) return 0f
        if (elapsedMs >= durationMs.toFloat()) return 1f
        return when (curve) {
            is Curve.Spring -> {
                val settleMs = springSettleDurationMs(curve.damping, curve.stiffness)
                if (elapsedMs >= settleMs.toFloat()) {
                    1f
                } else {
                    evaluateSpring(curve.damping, curve.stiffness, elapsedMs.toDouble() / 1000.0).toFloat()
                }
            }
            is Curve.Bezier -> {
                val fraction = (elapsedMs / durationMs.toFloat()).coerceIn(0f, 1f).toDouble()
                evaluateCubicBezier(curve.x1, curve.y1, curve.x2, curve.y2, fraction).toFloat()
            }
            is Curve.Preset -> {
                val fraction = (elapsedMs / durationMs.toFloat()).coerceIn(0f, 1f).toDouble()
                val bezier = PRESET_BEZIERS[curve.name] ?: PRESET_BEZIERS.getValue("linear")
                evaluateCubicBezier(bezier.x1, bezier.y1, bezier.x2, bezier.y2, fraction).toFloat()
            }
        }
    }

    /**
     * Evaluates a curve at normalized fraction [fraction] in `[0, 1]` (only input time is clamped;
     * overshoot in Y is preserved).
     */
    fun evaluateFraction(curve: Curve, fraction: Float): Float {
        val f = fraction.coerceIn(0f, 1f)
        if (f <= 0f) return 0f
        if (f >= 1f) return 1f
        return when (curve) {
            is Curve.Spring -> {
                val settleMs = springSettleDurationMs(curve.damping, curve.stiffness)
                evaluateSpring(curve.damping, curve.stiffness, (f * settleMs) / 1000.0).toFloat()
            }
            is Curve.Bezier -> evaluateCubicBezier(curve.x1, curve.y1, curve.x2, curve.y2, f.toDouble()).toFloat()
            is Curve.Preset -> {
                val bezier = PRESET_BEZIERS[curve.name] ?: PRESET_BEZIERS.getValue("linear")
                evaluateCubicBezier(bezier.x1, bezier.y1, bezier.x2, bezier.y2, f.toDouble()).toFloat()
            }
        }
    }

    /**
     * Analytical cubic Bezier `y(x)` evaluation for control points `(0,0), (x1,y1), (x2,y2), (1,1)`.
     * Preserves Y overshoot (`y1 < 0` or `y1 > 1` or `y2 < 0` or `y2 > 1`).
     */
    fun evaluateCubicBezier(
        x1: Double,
        y1: Double,
        x2: Double,
        y2: Double,
        x: Double,
    ): Double {
        if (x <= 0.0) return 0.0
        if (x >= 1.0) return 1.0
        if (x1 == y1 && x2 == y2) return x

        // Solve Bx(u) = x using Newton-Raphson with bracketed bisection fallback.
        var lo = 0.0
        var hi = 1.0
        var u = x
        for (iter in 0 until 12) {
            val bx = sampleCubic(x1, x2, u)
            val err = bx - x
            if (abs(err) < 1e-7) break
            if (err < 0.0) lo = u else hi = u
            val dbx = sampleCubicDerivative(x1, x2, u)
            val next = if (abs(dbx) > 1e-6) u - err / dbx else 0.5 * (lo + hi)
            u = if (next > lo && next < hi) next else 0.5 * (lo + hi)
        }
        return sampleCubic(y1, y2, u)
    }

    private fun sampleCubic(p1: Double, p2: Double, u: Double): Double {
        val inv = 1.0 - u
        return 3.0 * inv * inv * u * p1 + 3.0 * inv * u * u * p2 + u * u * u
    }

    private fun sampleCubicDerivative(p1: Double, p2: Double, u: Double): Double {
        val inv = 1.0 - u
        return 3.0 * inv * inv * p1 + 6.0 * inv * u * (p2 - p1) + 3.0 * u * u * (1.0 - p2)
    }

    /**
     * Analytical displacement `x(t)` (from 0 towards 1) of a unit-mass damped harmonic oscillator
     * with damping ratio [damping] and spring constant [stiffness] at time [timeSeconds].
     */
    fun evaluateSpring(damping: Double, stiffness: Double, timeSeconds: Double): Double {
        if (timeSeconds <= 0.0) return 0.0
        val omega = sqrt(stiffness)
        return when {
            damping < 1.0 - 1e-6 -> {
                val omegaD = omega * sqrt(1.0 - damping * damping)
                val decay = exp(-damping * omega * timeSeconds)
                1.0 - decay * (cos(omegaD * timeSeconds) + (damping * omega / omegaD) * sin(omegaD * timeSeconds))
            }
            damping <= 1.0 + 1e-6 -> {
                val decay = exp(-omega * timeSeconds)
                1.0 - decay * (1.0 + omega * timeSeconds)
            }
            else -> {
                val s = omega * sqrt(damping * damping - 1.0)
                val r1 = -damping * omega + s
                val r2 = -damping * omega - s
                val c1 = -r2 / (r1 - r2)
                val c2 = r1 / (r1 - r2)
                1.0 - (c1 * exp(r1 * timeSeconds) + c2 * exp(r2 * timeSeconds))
            }
        }
    }

    /**
     * Analytical velocity `v(t) = dx/dt` of the unit-mass damped harmonic oscillator.
     */
    fun evaluateSpringVelocity(damping: Double, stiffness: Double, timeSeconds: Double): Double {
        if (timeSeconds <= 0.0) return 0.0
        val omega = sqrt(stiffness)
        return when {
            damping < 1.0 - 1e-6 -> {
                val omegaD = omega * sqrt(1.0 - damping * damping)
                val decay = exp(-damping * omega * timeSeconds)
                decay * (stiffness / omegaD) * sin(omegaD * timeSeconds)
            }
            damping <= 1.0 + 1e-6 -> {
                stiffness * timeSeconds * exp(-omega * timeSeconds)
            }
            else -> {
                val s = omega * sqrt(damping * damping - 1.0)
                val r1 = -damping * omega + s
                val r2 = -damping * omega - s
                val c1 = -r2 / (r1 - r2)
                val c2 = r1 / (r1 - r2)
                -(c1 * r1 * exp(r1 * timeSeconds) + c2 * r2 * exp(r2 * timeSeconds))
            }
        }
    }

    /**
     * Computes the exact analytical settle duration in whole milliseconds:
     * the first millisecond at and after which BOTH `|x(t) - 1| < 0.001` AND `|v(t)| < 0.001`
     * permanently hold.
     */
    fun springSettleDurationMs(
        damping: Double,
        stiffness: Double,
        tolerance: Double = SPRING_SETTLE_TOLERANCE,
    ): Int {
        val zeta = damping.coerceIn(SPRING_MIN_DAMPING, SPRING_MAX_DAMPING)
        val k = stiffness.coerceIn(SPRING_MIN_STIFFNESS, SPRING_MAX_STIFFNESS)
        val omega = sqrt(k)

        // Compute guaranteed upper bound T_hi where envelopes of both |x-1| and |v| stay below tolerance.
        val tHiSeconds: Double = when {
            zeta < 1.0 - 1e-6 -> {
                val factor = sqrt(1.0 - zeta * zeta)
                val maxAmp = maxOf(1.0 / factor, omega / factor)
                (ln(maxAmp / tolerance) / (zeta * omega)).coerceAtLeast(0.01) + 0.02
            }
            zeta <= 1.0 + 1e-6 -> {
                // Find t where (1 + omega*t)*e^(-omega*t) < tol and k*t*e^(-omega*t) < tol.
                var t = 1.0 / omega
                while (t < 60.0) {
                    if (abs(evaluateSpring(zeta, k, t) - 1.0) < tolerance &&
                        abs(evaluateSpringVelocity(zeta, k, t)) < tolerance
                    ) {
                        break
                    }
                    t *= 1.5
                }
                t
            }
            else -> {
                // Slowest decaying mode is r1 = -zeta*omega + omega*sqrt(zeta^2 - 1) < 0.
                val s = omega * sqrt(zeta * zeta - 1.0)
                val r1 = -zeta * omega + s
                val r2 = -zeta * omega - s
                val c1 = abs(-r2 / (r1 - r2))
                val c2 = abs(r1 / (r1 - r2))
                val maxAmp = maxOf(c1 + c2, c1 * abs(r1) + c2 * abs(r2))
                (ln(maxAmp / tolerance) / abs(r1)).coerceAtLeast(0.01) + 0.02
            }
        }

        fun outsideTolerance(tSec: Double): Boolean =
            abs(evaluateSpring(zeta, k, tSec) - 1.0) >= tolerance ||
                abs(evaluateSpringVelocity(zeta, k, tSec)) >= tolerance

        // Walk backwards in fine steps from tHiSeconds to locate the last excursion above tolerance.
        val maxMs = ceil(tHiSeconds * 1000.0).toInt().coerceAtLeast(1)
        var lastExcursionMs = 0
        for (ms in maxMs downTo 0) {
            // Check midpoint and integer ms to avoid skipping a narrow oscillation peak.
            val t1 = ms / 1000.0
            val tMid = (ms + 0.5) / 1000.0
            if (outsideTolerance(tMid) || outsideTolerance(t1)) {
                lastExcursionMs = ms + 1
                break
            }
        }
        return lastExcursionMs.coerceAtLeast(1)
    }
}

/**
 * A single choreography part relative to the code track's start.
 */
@Immutable
data class Part(
    val startMs: Int,
    val durationMs: Int,
    val easing: Easing,
) {
    val endMs: Int get() = startMs + durationMs
}

/**
 * Pure Warp choreography schedule shared by the planner and the code-card adapter.
 *
 * [totalSpanMs] is an upper bound for any diff (assuming leaving, moving, and arriving tokens are
 * all present), and has NO track-start delay baked in.
 */
@Immutable
data class WarpSchedule(
    val leaving: Part,
    val moving: Part,
    val arriving: Part,
    val totalSpanMs: Int,
)

private val WarpEaseInCubic: Easing = CubicBezierEasing(0.32f, 0f, 0.67f, 0f)
private val WarpEaseOutCubic: Easing = CubicBezierEasing(0.33f, 1f, 0.68f, 1f)

/**
 * Computes the single authoritative Warp choreography schedule for [tokenDurationMs] and
 * [reducedMotion].
 *
 * - Reduced motion: all three parts collapse to a single cross-fade of
 *   [REDUCED_MOTION_FADE_DURATION_MS] (150 ms).
 * - Full motion: reproduces the exact staggered delete / move / insert choreography and minimums
 *   (e.g. `tokenDurationMs = 60` -> leaving `[0, 40]`, moving `[9, 69]`, arriving `[33, 83]`,
 *   `totalSpanMs = 83`; `tokenDurationMs = 10` -> leaving `[0, 40]`, moving `[2, 62]`, arriving
 *   `[26, 76]`, `totalSpanMs = 76`).
 */
fun warpSchedule(tokenDurationMs: Int, reducedMotion: Boolean): WarpSchedule {
    if (reducedMotion) {
        val part = Part(
            startMs = 0,
            durationMs = REDUCED_MOTION_FADE_DURATION_MS,
            easing = FastOutSlowInEasing,
        )
        return WarpSchedule(
            leaving = part,
            moving = part,
            arriving = part,
            totalSpanMs = REDUCED_MOTION_FADE_DURATION_MS,
        )
    }

    val duration = tokenDurationMs.coerceAtLeast(0)
    val deleteDurationMs = (duration * 0.48f).roundToInt().coerceAtLeast(40)
    val moveStartMs = (duration * 0.15f).roundToInt()
    val moveDurationMs = (duration * 0.70f).roundToInt().coerceAtLeast(60)
    val insertStartMs = moveStartMs + (moveDurationMs * 0.40f).roundToInt()
    val insertDurationMs = (duration * 0.45f).roundToInt().coerceAtLeast(50)

    val leaving = Part(startMs = 0, durationMs = deleteDurationMs, easing = WarpEaseInCubic)
    val moving = Part(startMs = moveStartMs, durationMs = moveDurationMs, easing = FastOutSlowInEasing)
    val arriving = Part(startMs = insertStartMs, durationMs = insertDurationMs, easing = WarpEaseOutCubic)
    val totalSpanMs = maxOf(leaving.endMs, moving.endMs, arriving.endMs)

    return WarpSchedule(
        leaving = leaving,
        moving = moving,
        arriving = arriving,
        totalSpanMs = totalSpanMs,
    )
}

/**
 * Computes the mirrored time window `[windowStartMs, windowEndMs]` for a Warp track on a backward
 * root pair.
 */
fun mirroredWarpWindow(segmentMs: Int, trackStartMs: Int, totalSpanMs: Int): IntRange {
    val start = (segmentMs - trackStartMs - totalSpanMs).coerceAtLeast(0)
    val end = (segmentMs - trackStartMs).coerceAtLeast(start)
    return start..end
}

/** Converts a [Curve] into a Compose [Easing] for animation specs where needed. */
fun Curve.toComposeEasing(): Easing = when (this) {
    is Curve.Preset -> {
        val b = CurveEvaluator.PRESET_BEZIERS[name]
        if (b == null || name == "linear") {
            LinearEasing
        } else {
            CubicBezierEasing(b.x1.toFloat(), b.y1.toFloat(), b.x2.toFloat(), b.y2.toFloat())
        }
    }
    is Curve.Bezier -> CubicBezierEasing(x1.toFloat(), y1.toFloat(), x2.toFloat(), y2.toFloat())
    is Curve.Spring -> Easing { fraction -> CurveEvaluator.evaluateFraction(this, fraction) }
}
