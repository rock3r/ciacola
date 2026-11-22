package dev.sebastiano.ciacola.effects.v2

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.TransformOrigin
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

/**
 * Evaluates Layer-tier build effects into pure [LayerState] values and computes clip geometry
 * (`wipe` 8-way polygon, `iris` circular clip) and deterministic `keyboard` typing schedules.
 */
object LayerEffects {

    fun evaluateAppear(kind: EffectBuildKind, progress: Float): LayerState {
        val p = progress.coerceIn(0f, 1f)
        return when (kind) {
            EffectBuildKind.Enter -> if (p <= 0f) LayerState.Hidden else LayerState.Identity
            EffectBuildKind.Exit -> if (p >= 1f) LayerState.Hidden else LayerState.Identity
            EffectBuildKind.Emphasis -> LayerState.Identity
        }
    }

    fun evaluateDissolve(
        kind: EffectBuildKind,
        progress: Float,
        curve: CurveV2? = null,
    ): LayerState {
        val p = progress.coerceIn(0f, 1f)
        val effectiveCurve = curve ?: CurveV2.EaseInOut
        val e = effectiveCurve.sample(p).coerceIn(0f, 1f)
        return when (kind) {
            EffectBuildKind.Enter -> when {
                p <= 0f -> LayerState.Hidden
                p >= 1f -> LayerState.Identity
                else -> LayerState(alpha = e)
            }
            EffectBuildKind.Exit -> when {
                p <= 0f -> LayerState.Identity
                p >= 1f -> LayerState.Hidden
                else -> LayerState(alpha = 1f - e)
            }
            EffectBuildKind.Emphasis -> LayerState.Identity
        }
    }

    fun evaluateFadeMove(
        kind: EffectBuildKind,
        progress: Float,
        context: EffectEvalContext,
        args: EffectArgs = EffectArgs.Empty,
        curve: CurveV2? = null,
    ): LayerState {
        val p = progress.coerceIn(0f, 1f)
        val direction = args.direction8("direction", EffectDirection8.BottomToTop)
        val distancePx = args.float("distance", 48f * context.density).coerceAtLeast(0f)

        if (context.reducedMotion) {
            return evaluateDissolve(kind, p, curve)
        }

        val u = direction.unitVector()
        return when (kind) {
            EffectBuildKind.Enter -> {
                if (p <= 0f) {
                    LayerState(
                        alpha = 0f,
                        translationX = -u.x * distancePx,
                        translationY = -u.y * distancePx,
                    )
                } else if (p >= 1f) {
                    LayerState.Identity
                } else {
                    val posCurve = curve ?: args.curve("posCurve") ?: CurveV2.Preset(KeynoteCurves.FADE_MOVE_POS)
                    val alphaCurve = args.curve("alphaCurve") ?: CurveV2.Preset(KeynoteCurves.FADE_MOVE_ALPHA)
                    val posE = posCurve.sample(p)
                    val alphaE = alphaCurve.sample(p).coerceIn(0f, 1f)
                    val remaining = 1f - posE
                    LayerState(
                        alpha = alphaE,
                        translationX = -u.x * distancePx * remaining,
                        translationY = -u.y * distancePx * remaining,
                    )
                }
            }
            EffectBuildKind.Exit -> {
                if (p <= 0f) {
                    LayerState.Identity
                } else if (p >= 1f) {
                    LayerState(
                        alpha = 0f,
                        translationX = u.x * distancePx,
                        translationY = u.y * distancePx,
                    )
                } else {
                    val exitCurve = curve ?: args.curve("exitCurve") ?: CurveV2.Preset(KeynoteCurves.FADE_MOVE_EXIT)
                    val e = exitCurve.sample(p)
                    LayerState(
                        alpha = (1f - e).coerceIn(0f, 1f),
                        translationX = u.x * distancePx * e,
                        translationY = u.y * distancePx * e,
                    )
                }
            }
            EffectBuildKind.Emphasis -> LayerState.Identity
        }
    }

    fun evaluateMoveIn(
        kind: EffectBuildKind,
        progress: Float,
        context: EffectEvalContext,
        args: EffectArgs = EffectArgs.Empty,
        curve: CurveV2? = null,
    ): LayerState {
        val p = progress.coerceIn(0f, 1f)
        val direction = args.direction8("direction", EffectDirection8.LeftToRight)
        val bounce = args.boolean("bounce", false)
        val explicitDistance = args.values["distance"]?.let { args.float("distance", 0f) }

        if (context.reducedMotion) {
            return evaluateDissolve(kind, p, curve)
        }

        val travel = computeStageClearingTranslation(
            direction = direction,
            bounds = context.nodeBounds,
            stageSize = context.stageSize,
            stageOffset = context.stageOffset,
            isExit = kind == EffectBuildKind.Exit,
            explicitDistancePx = explicitDistance,
        )

        return when (kind) {
            EffectBuildKind.Enter -> {
                if (p <= 0f) {
                    LayerState(alpha = 0f, translationX = travel.x, translationY = travel.y)
                } else if (p >= 1f) {
                    LayerState.Identity
                } else {
                    val moveCurve = curve ?: CurveV2.Preset(KeynoteCurves.MOVE_IN)
                    val e = moveCurve.sample(p)
                    val bounceOvershoot = if (bounce && p > 0.72f) {
                        val bt = ((p - 0.72f) / 0.28f).coerceIn(0f, 1f)
                        // Small damped settle bounce in the travel direction, strictly 0 at bt = 0 and bt = 1.
                        -0.045f * sin(bt * PI.toFloat()) * (1f - bt)
                    } else {
                        0f
                    }
                    val remaining = (1f - e) + bounceOvershoot
                    LayerState(
                        alpha = 1f, // MoveIn travels at full opacity without fade.
                        translationX = travel.x * remaining,
                        translationY = travel.y * remaining,
                    )
                }
            }
            EffectBuildKind.Exit -> {
                if (p <= 0f) {
                    LayerState.Identity
                } else if (p >= 1f) {
                    LayerState(alpha = 0f, translationX = travel.x, translationY = travel.y)
                } else {
                    val moveCurve = curve ?: CurveV2.Preset(KeynoteCurves.MOVE_OUT)
                    val e = moveCurve.sample(p)
                    LayerState(
                        alpha = 1f, // MoveOut travels at full opacity until completely off-stage.
                        translationX = travel.x * e,
                        translationY = travel.y * e,
                    )
                }
            }
            EffectBuildKind.Emphasis -> LayerState.Identity
        }
    }

    fun evaluateWipe(
        kind: EffectBuildKind,
        progress: Float,
        context: EffectEvalContext,
        args: EffectArgs = EffectArgs.Empty,
        curve: CurveV2? = null,
    ): LayerState {
        val p = progress.coerceIn(0f, 1f)
        if (context.reducedMotion) return evaluateDissolve(kind, p, curve)

        val direction = args.direction8("direction", EffectDirection8.LeftToRight)
        val e = (curve ?: CurveV2.EaseInOut).sample(p).coerceIn(0f, 1f)
        return when (kind) {
            EffectBuildKind.Enter -> when {
                p <= 0f -> LayerState.Hidden
                p >= 1f -> LayerState.Identity
                else -> LayerState(
                    alpha = 1f,
                    clip = EffectClip.Wipe(direction = direction, revealFraction = e, isExit = false),
                )
            }
            EffectBuildKind.Exit -> when {
                p <= 0f -> LayerState.Identity
                p >= 1f -> LayerState.Hidden
                else -> LayerState(
                    alpha = 1f,
                    clip = EffectClip.Wipe(direction = direction, revealFraction = 1f - e, isExit = true),
                )
            }
            EffectBuildKind.Emphasis -> LayerState.Identity
        }
    }

    fun evaluateIris(
        kind: EffectBuildKind,
        progress: Float,
        context: EffectEvalContext,
        args: EffectArgs = EffectArgs.Empty,
        curve: CurveV2? = null,
    ): LayerState {
        val p = progress.coerceIn(0f, 1f)
        if (context.reducedMotion) return evaluateDissolve(kind, p, curve)

        val irisDirection = args.irisDirection("direction", IrisDirection.In)
        val center = if (args.values["center"] is Offset) {
            val raw = args.offset("center", Offset(0.5f, 0.5f))
            Offset(raw.x.coerceIn(0f, 1f), raw.y.coerceIn(0f, 1f))
        } else {
            Offset(
                x = args.float("centerX", 0.5f).coerceIn(0f, 1f),
                y = args.float("centerY", 0.5f).coerceIn(0f, 1f),
            )
        }
        val e = (curve ?: CurveV2.EaseInOut).sample(p).coerceIn(0f, 1f)

        return when (kind) {
            EffectBuildKind.Enter -> when {
                p <= 0f -> LayerState.Hidden
                p >= 1f -> LayerState.Identity
                else -> when (irisDirection) {
                    IrisDirection.In -> LayerState(
                        alpha = 1f,
                        clip = EffectClip.Iris(center = center, radiusFraction = e, keepInside = true),
                    )
                    IrisDirection.Out -> LayerState(
                        alpha = 1f,
                        clip = EffectClip.Iris(center = center, radiusFraction = 1f - e, keepInside = false),
                    )
                }
            }
            EffectBuildKind.Exit -> when {
                p <= 0f -> LayerState.Identity
                p >= 1f -> LayerState.Hidden
                else -> when (irisDirection) {
                    IrisDirection.Out -> LayerState(
                        alpha = 1f,
                        clip = EffectClip.Iris(center = center, radiusFraction = 1f - e, keepInside = true),
                    )
                    IrisDirection.In -> LayerState(
                        alpha = 1f,
                        clip = EffectClip.Iris(center = center, radiusFraction = e, keepInside = false),
                    )
                }
            }
            EffectBuildKind.Emphasis -> LayerState.Identity
        }
    }

    fun evaluateZoom(
        kind: EffectBuildKind,
        progress: Float,
        context: EffectEvalContext,
        args: EffectArgs = EffectArgs.Empty,
        curve: CurveV2? = null,
        isBig: Boolean = false,
    ): LayerState {
        val p = progress.coerceIn(0f, 1f)
        if (context.reducedMotion) return evaluateDissolve(kind, p, curve)

        val scaleDelay = args.float("scaleDelay", 0f).coerceIn(0f, 0.85f)
        val fade = args.boolean("fade", true)
        val origin = TransformOrigin(
            pivotFractionX = args.float("originX", 0.5f),
            pivotFractionY = args.float("originY", 0.5f),
        )

        return when (kind) {
            EffectBuildKind.Enter -> {
                val fromScale = args.float("fromScale", if (isBig) 0.25f else 0.01f).coerceIn(0.01f, 20f)
                val toScale = args.float("toScale", 1f).coerceIn(0.01f, 20f)
                if (p <= 0f) {
                    LayerState(
                        alpha = if (fade) 0f else 1f,
                        scaleX = fromScale,
                        scaleY = fromScale,
                        transformOrigin = origin,
                    )
                } else if (p >= 1f) {
                    LayerState.Identity
                } else {
                    val effectiveCurve = curve ?: CurveV2.EaseOut
                    val alphaVal = if (fade) effectiveCurve.sample(p).coerceIn(0f, 1f) else 1f
                    val sp = if (p <= scaleDelay) 0f else ((p - scaleDelay) / (1f - scaleDelay)).coerceIn(0f, 1f)
                    val s = lerp(fromScale, toScale, effectiveCurve.sample(sp))
                    LayerState(
                        alpha = alphaVal,
                        scaleX = s,
                        scaleY = s,
                        transformOrigin = origin,
                    )
                }
            }
            EffectBuildKind.Exit -> {
                val fromScale = args.float("fromScale", 1f).coerceIn(0.01f, 20f)
                val toScale = args.float("toScale", if (isBig) 2.5f else 0.01f).coerceIn(0.01f, 20f)
                if (p <= 0f) {
                    LayerState.Identity
                } else if (p >= 1f) {
                    LayerState(
                        alpha = 0f,
                        scaleX = toScale,
                        scaleY = toScale,
                        transformOrigin = origin,
                    )
                } else {
                    val effectiveCurve = curve ?: if (isBig || toScale > 1.5f) {
                        CurveV2.Preset(KeynoteCurves.ZOOM_BIG_EXIT)
                    } else {
                        CurveV2.EaseIn
                    }
                    val alphaVal = if (fade) (1f - effectiveCurve.sample(p)).coerceIn(0f, 1f) else 1f
                    val sp = if (p <= scaleDelay) 0f else ((p - scaleDelay) / (1f - scaleDelay)).coerceIn(0f, 1f)
                    val s = lerp(fromScale, toScale, effectiveCurve.sample(sp))
                    LayerState(
                        alpha = alphaVal,
                        scaleX = s,
                        scaleY = s,
                        transformOrigin = origin,
                    )
                }
            }
            EffectBuildKind.Emphasis -> LayerState.Identity
        }
    }

    fun evaluatePop(
        kind: EffectBuildKind,
        progress: Float,
        context: EffectEvalContext,
        args: EffectArgs = EffectArgs.Empty,
        curve: CurveV2? = null,
    ): LayerState {
        val p = progress.coerceIn(0f, 1f)
        if (context.reducedMotion) return evaluateDissolve(kind, p, curve)

        val initialScale = args.float("initialScale", 0.78f).coerceIn(0.01f, 0.99f)
        val overshootScale = args.float("overshootScale", 1.08f).coerceAtLeast(1f)
        return when (kind) {
            EffectBuildKind.Enter -> {
                if (p <= 0f) {
                    LayerState(alpha = 0f, scaleX = initialScale, scaleY = initialScale)
                } else if (p >= 1f) {
                    LayerState.Identity
                } else {
                    val s = popEnterScale(p, initialScale, overshootScale)
                    val a = smoothstep((p / 0.35f).coerceIn(0f, 1f))
                    LayerState(alpha = a, scaleX = s, scaleY = s)
                }
            }
            EffectBuildKind.Exit -> {
                if (p <= 0f) {
                    LayerState.Identity
                } else if (p >= 1f) {
                    LayerState(alpha = 0f, scaleX = initialScale, scaleY = initialScale)
                } else {
                    // Anticipation swell up to overshootScale during [0, 0.28], then rapid collapse to initialScale.
                    val s = if (p < 0.28f) {
                        val u = smoothstep((p / 0.28f).coerceIn(0f, 1f))
                        lerp(1f, overshootScale, u)
                    } else {
                        val u = ((p - 0.28f) / 0.72f).coerceIn(0f, 1f)
                        lerp(overshootScale, initialScale, smoothstep(u).coerceIn(0f, 1f))
                    }
                    val a = if (p < 0.55f) 1f else 1f - smoothstep(((p - 0.55f) / 0.45f).coerceIn(0f, 1f))
                    LayerState(alpha = a, scaleX = s, scaleY = s)
                }
            }
            EffectBuildKind.Emphasis -> LayerState.Identity
        }
    }

    fun evaluateBlur(
        kind: EffectBuildKind,
        progress: Float,
        context: EffectEvalContext,
        args: EffectArgs = EffectArgs.Empty,
        curve: CurveV2? = null,
    ): LayerState {
        val p = progress.coerceIn(0f, 1f)
        if (context.reducedMotion) return evaluateDissolve(kind, p, curve)

        val maxBlurPx = args.float("radius", 32f * context.density).coerceAtLeast(0f)
        val effectiveCurve = curve ?: CurveV2.EaseOut
        val e = effectiveCurve.sample(p).coerceIn(0f, 1f)

        return when (kind) {
            EffectBuildKind.Enter -> when {
                p <= 0f -> LayerState(alpha = 0f, blurRadiusPx = maxBlurPx)
                p >= 1f -> LayerState.Identity
                else -> LayerState(
                    alpha = smoothstep((p / 0.70f).coerceIn(0f, 1f)),
                    blurRadiusPx = maxBlurPx * (1f - e),
                )
            }
            EffectBuildKind.Exit -> when {
                p <= 0f -> LayerState.Identity
                p >= 1f -> LayerState(alpha = 0f, blurRadiusPx = maxBlurPx)
                else -> LayerState(
                    alpha = 1f - smoothstep(((p - 0.20f) / 0.80f).coerceIn(0f, 1f)),
                    blurRadiusPx = maxBlurPx * e,
                )
            }
            EffectBuildKind.Emphasis -> LayerState.Identity
        }
    }

    fun evaluateFromDarkness(
        kind: EffectBuildKind,
        progress: Float,
        context: EffectEvalContext,
        args: EffectArgs = EffectArgs.Empty,
        curve: CurveV2? = null,
    ): LayerState {
        val p = progress.coerceIn(0f, 1f)
        if (context.reducedMotion) return evaluateDissolve(kind, p, curve)

        val initialScale = args.float("initialScale", 0.94f).coerceIn(0.5f, 1.5f)
        val effectiveCurve = curve ?: CurveV2.EaseInOut
        val e = effectiveCurve.sample(p).coerceIn(0f, 1f)

        return when (kind) {
            EffectBuildKind.Enter -> when {
                p <= 0f -> LayerState(
                    alpha = 0f,
                    scaleX = initialScale,
                    scaleY = initialScale,
                    colorMatrix = brightnessColorMatrix(0f),
                )
                p >= 1f -> LayerState.Identity
                else -> {
                    val s = lerp(initialScale, 1f, e)
                    val brightness = smoothstep(((p - 0.10f) / 0.90f).coerceIn(0f, 1f))
                    val alphaVal = smoothstep((p / 0.65f).coerceIn(0f, 1f))
                    LayerState(
                        alpha = alphaVal,
                        scaleX = s,
                        scaleY = s,
                        colorMatrix = brightnessColorMatrix(brightness),
                    )
                }
            }
            EffectBuildKind.Exit -> when {
                p <= 0f -> LayerState.Identity
                p >= 1f -> LayerState(
                    alpha = 0f,
                    scaleX = initialScale,
                    scaleY = initialScale,
                    colorMatrix = brightnessColorMatrix(0f),
                )
                else -> {
                    val s = lerp(1f, initialScale, e)
                    val brightness = 1f - smoothstep((p / 0.85f).coerceIn(0f, 1f))
                    val alphaVal = 1f - smoothstep(((p - 0.35f) / 0.65f).coerceIn(0f, 1f))
                    LayerState(
                        alpha = alphaVal,
                        scaleX = s,
                        scaleY = s,
                        colorMatrix = brightnessColorMatrix(brightness),
                    )
                }
            }
            EffectBuildKind.Emphasis -> LayerState.Identity
        }
    }

    fun evaluateJiggle(
        kind: EffectBuildKind,
        progress: Float,
        context: EffectEvalContext,
        args: EffectArgs = EffectArgs.Empty,
    ): LayerState {
        val p = progress.coerceIn(0f, 1f)
        if (context.reducedMotion || kind != EffectBuildKind.Emphasis || p <= 0f || p >= 1f) {
            return LayerState.Identity
        }
        val intensity = args.float("intensity", 1f).coerceAtLeast(0f)
        val cycles = args.float("cycles", 4f).coerceAtLeast(1f)
        val baseAngleDeg = when {
            args.values["maxAngleDeg"] != null -> args.float("maxAngleDeg", 4.5f)
            args.values["amplitude"] != null -> args.float("amplitude", 4.5f)
            else -> 4.5f
        }.coerceAtLeast(0f)
        val maxAngleDeg = baseAngleDeg * intensity
        val defaultShiftPx = (baseAngleDeg / 4.5f) * 3f * context.density
        val maxShiftPx = args.float("maxShiftPx", defaultShiftPx).coerceAtLeast(0f) * intensity

        // Sin(pi*p) envelope guarantees exact 0 displacement/rotation at p=0 and p=1.
        val envelope = sin(PI * p).toFloat() * exp(-1.15 * p).toFloat()
        val angle = maxAngleDeg * envelope * sin(2.0 * PI * cycles * p).toFloat()
        val shiftX = maxShiftPx * envelope * sin(2.0 * PI * cycles * p + PI * 0.5).toFloat()
        return LayerState(
            alpha = 1f,
            translationX = shiftX,
            rotationZ = angle,
        )
    }

    fun evaluateLineDrawTrim(
        kind: EffectBuildKind,
        progress: Float,
        args: EffectArgs = EffectArgs.Empty,
        curve: CurveV2? = null,
    ): PathTrimState {
        val p = progress.coerceIn(0f, 1f)
        val direction = args.lineDrawDirection("direction", LineDrawDirection.StartToEnd)
        val includeEndpoints = args.boolean("includeEndpoints", true)
        val e = (curve ?: CurveV2.EaseInOut).sample(p).coerceIn(0f, 1f)

        val visibleFraction = when (kind) {
            EffectBuildKind.Enter -> e
            EffectBuildKind.Exit -> 1f - e
            EffectBuildKind.Emphasis -> 1f
        }

        if (visibleFraction <= 0f) {
            return PathTrimState(
                startFraction = 0f,
                endFraction = 0f,
                direction = direction,
                includeEndpoints = includeEndpoints,
                alpha = 0f,
                headScale = 0f,
            )
        }

        val (startF, endF) = when (direction) {
            LineDrawDirection.StartToEnd,
            LineDrawDirection.Clockwise,
            LineDrawDirection.CounterClockwise,
            -> if (kind == EffectBuildKind.Exit) {
                // Exit retracts from start toward end (wiping off forward along the path).
                e to 1f
            } else {
                0f to visibleFraction
            }
            LineDrawDirection.EndToStart -> if (kind == EffectBuildKind.Exit) {
                0f to (1f - e)
            } else {
                (1f - visibleFraction) to 1f
            }
            LineDrawDirection.FromCenter -> {
                val half = visibleFraction * 0.5f
                (0.5f - half) to (0.5f + half)
            }
        }

        val edgeAlpha = smoothstep((visibleFraction / 0.08f).coerceIn(0f, 1f))
        val headScale = if (includeEndpoints) {
            smoothstep((visibleFraction / 0.25f).coerceIn(0f, 1f))
        } else {
            smoothstep(((visibleFraction - 0.85f) / 0.15f).coerceIn(0f, 1f))
        }

        return PathTrimState(
            startFraction = startF.coerceIn(0f, 1f),
            endFraction = endF.coerceIn(0f, 1f),
            direction = direction,
            includeEndpoints = includeEndpoints,
            alpha = edgeAlpha,
            headScale = headScale,
        )
    }

    /**
     * Builds a 4×5 RGBA [FloatArray] color matrix that scales RGB exposure by [brightness]
     * (`0f` = pure black, `1f` = identity) while leaving alpha untouched.
     */
    fun brightnessColorMatrix(brightness: Float): FloatArray {
        val b = brightness.coerceIn(0f, 2f)
        return floatArrayOf(
            b, 0f, 0f, 0f, 0f,
            0f, b, 0f, 0f, 0f,
            0f, 0f, b, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        )
    }

    /**
     * Constructs an exact clip [Path] for [EffectClip.Wipe] over [size] for all 8 cardinal and
     * diagonal directions via Sutherland–Hodgman half-plane polygon clipping.
     */
    fun buildWipeClipPath(size: Size, clip: EffectClip.Wipe): Path {
        val path = Path()
        val w = size.width
        val h = size.height
        val reveal = clip.revealFraction.coerceIn(0f, 1f)
        if (w <= 0f || h <= 0f || reveal <= 0f) return path
        if (reveal >= 1f) {
            path.addRect(Rect(0f, 0f, w, h))
            return path
        }

        val u = clip.direction.unitVector()
        val corners = listOf(
            Offset(0f, 0f),
            Offset(w, 0f),
            Offset(w, h),
            Offset(0f, h),
        )
        var projMin = Float.POSITIVE_INFINITY
        var projMax = Float.NEGATIVE_INFINITY
        for (c in corners) {
            val d = c.x * u.x + c.y * u.y
            if (d < projMin) projMin = d
            if (d > projMax) projMax = d
        }
        val span = projMax - projMin
        if (span <= 1e-6f) {
            path.addRect(Rect(0f, 0f, w, h))
            return path
        }

        // Half-plane test: inside(p) == signedDist(p) <= 0
        val clipped = if (!clip.isExit) {
            val threshold = projMin + span * reveal
            clipPolygonHalfPlane(corners) { p -> (p.x * u.x + p.y * u.y) - threshold }
        } else {
            // Exit: hidden region advances along u; visible region is p · u >= projMin + span * (1 - reveal)
            val threshold = projMin + span * (1f - reveal)
            clipPolygonHalfPlane(corners) { p -> threshold - (p.x * u.x + p.y * u.y) }
        }

        if (clipped.size >= 3) {
            path.moveTo(clipped[0].x, clipped[0].y)
            for (i in 1 until clipped.size) {
                path.lineTo(clipped[i].x, clipped[i].y)
            }
            path.close()
        }
        return path
    }

    /**
     * Constructs an exact circular clip [Path] for [EffectClip.Iris] over [size], sized to reach
     * the farthest corner from [EffectClip.Iris.center] at `radiusFraction = 1f`.
     */
    fun buildIrisClipPath(size: Size, clip: EffectClip.Iris): Path {
        val path = Path()
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return path

        val cx = w * clip.center.x.coerceIn(0f, 1f)
        val cy = h * clip.center.y.coerceIn(0f, 1f)
        val maxR = max(
            max(hypot(cx, cy), hypot(w - cx, cy)),
            max(hypot(cx, h - cy), hypot(w - cx, h - cy)),
        ) + 1f
        val r = maxR * clip.radiusFraction.coerceIn(0f, 1f)

        if (clip.keepInside) {
            if (r <= 0f) return path
            if (clip.radiusFraction >= 1f) {
                path.addRect(Rect(0f, 0f, w, h))
            } else {
                path.addOval(Rect(cx - r, cy - r, cx + r, cy + r))
            }
        } else {
            if (clip.radiusFraction >= 1f) return path
            path.fillType = PathFillType.EvenOdd
            path.addRect(Rect(0f, 0f, w, h))
            if (r > 0f) {
                path.addOval(Rect(cx - r, cy - r, cx + r, cy + r))
            }
        }
        return path
    }

    /**
     * Generates deterministic per-piece start offsets (in ms) for Keynote's `keyboard` typewriter
     * build using a seeded SplitMix64 PRNG so human typing cadence is 100% reproducible across live
     * playback, scrubbing, and video export.
     *
     * When [reverseForExit] is `true`, pieces disappear from right to left (backspace cadence)
     * slightly faster than typing.
     */
    fun computeKeyboardPieceStartsMs(
        count: Int,
        seed: Long = 0x5EED_CAFE_1234L,
        charsPerSec: Float = 18f,
        initialPauseMs: Int = 80,
        pieceTexts: List<String>? = null,
        reverseForExit: Boolean = false,
    ): IntArray {
        if (count <= 0) return IntArray(0)
        val safeCps = charsPerSec.coerceIn(1f, 200f)
        val baseStepMs = (1000.0 / safeCps) * if (reverseForExit) 0.65 else 1.0
        val result = IntArray(count)
        var state = seed xor if (reverseForExit) 0x9E37_79B9_7F4A_7C15UL.toLong() else 0L
        var elapsed = initialPauseMs.coerceAtLeast(0).toDouble()

        for (stepIdx in 0 until count) {
            val pieceIdx = if (reverseForExit) count - 1 - stepIdx else stepIdx
            result[pieceIdx] = elapsed.toInt()
            // SplitMix64 next double in [0, 1)
            state += 0x9E37_79B9_7F4A_7C15UL.toLong()
            var z = state
            z = (z xor (z ushr 30)) * 0xBF58_476D_1CE4_E5B9UL.toLong()
            z = (z xor (z ushr 27)) * 0x94D0_49BB_1331_11EBUL.toLong()
            z = z xor (z ushr 31)
            val u = ((z ushr 11).toDouble()) * (1.0 / (1L shl 53).toDouble())

            val jitterFactor = 0.62 + u * 0.76 // [0.62, 1.38] around baseStepMs
            val tokenText = pieceTexts?.getOrNull(pieceIdx)
            val cadenceBonus = if (!reverseForExit && tokenText != null) {
                when {
                    tokenText.any { it == '.' || it == '!' || it == '?' || it == '\n' } -> 1.8
                    tokenText.any { it == ',' || it == ';' || it == ':' } -> 1.35
                    tokenText.all { it.isWhitespace() } -> 1.15
                    else -> 1.0
                }
            } else {
                1.0
            }
            elapsed += baseStepMs * jitterFactor * cadenceBonus
        }
        return result
    }

    private fun computeStageClearingTranslation(
        direction: EffectDirection8,
        bounds: Rect,
        stageSize: Size,
        stageOffset: Offset?,
        isExit: Boolean,
        explicitDistancePx: Float?,
    ): Offset {
        val u = direction.unitVector()
        if (explicitDistancePx != null && explicitDistancePx > 0f) {
            val sign = if (isExit) 1f else -1f
            return Offset(u.x * explicitDistancePx * sign, u.y * explicitDistancePx * sign)
        }

        val w = stageSize.width.coerceAtLeast(1f)
        val h = stageSize.height.coerceAtLeast(1f)
        val rawBounds = if (bounds.width > 0f && bounds.height > 0f) bounds else Rect(0f, 0f, w * 0.2f, h * 0.1f)
        val margin = 32f
        val signs = direction.axisSigns()

        // For Enter, node begins off-stage in direction -signs so its far edge clears the stage.
        // For Exit, node travels off-stage in direction +signs so its near edge clears the stage.
        val travelSignX = if (isExit) signs.x else -signs.x
        val travelSignY = if (isExit) signs.y else -signs.y

        if (stageOffset != null) {
            val b = rawBounds.translate(stageOffset)
            val tx = when {
                travelSignX < 0f -> -(b.right.coerceAtLeast(0f) + margin)
                travelSignX > 0f -> (w - b.left).coerceAtLeast(0f) + margin
                else -> 0f
            }
            val ty = when {
                travelSignY < 0f -> -(b.bottom.coerceAtLeast(0f) + margin)
                travelSignY > 0f -> (h - b.top).coerceAtLeast(0f) + margin
                else -> 0f
            }
            return Offset(tx, ty)
        }

        // Without an explicit stageOffset, nodeBounds may be parent-relative or piece-local
        // (e.g. a Row/Column child or per-character text piece). Guarantee full stageSize
        // clearance regardless of where the node sits on the 1920x1080 stage.
        val tx = when {
            travelSignX < 0f -> -(max(rawBounds.right, w) + margin)
            travelSignX > 0f -> max(w - rawBounds.left, w) + margin
            else -> 0f
        }
        val ty = when {
            travelSignY < 0f -> -(max(rawBounds.bottom, h) + margin)
            travelSignY > 0f -> max(h - rawBounds.top, h) + margin
            else -> 0f
        }
        return Offset(tx, ty)
    }

    private fun popEnterScale(progress: Float, initialScale: Float, overshootScale: Float): Float {
        val p = progress.coerceIn(0f, 1f)
        val span = 1f - initialScale
        val u = p - 1f
        if (span <= 0f || overshootScale <= 1f) return initialScale + span * (1f + u * u * u)
        val peak = (overshootScale - 1f) / span
        var lo = 0f
        var hi = 50f
        repeat(40) {
            val mid = (lo + hi) * 0.5f
            val value = 4f * mid * mid * mid / (27f * (mid + 1f) * (mid + 1f))
            if (value < peak) lo = mid else hi = mid
        }
        val c = (lo + hi) * 0.5f
        return initialScale + span * (1f + (c + 1f) * u * u * u + c * u * u)
    }

    private fun clipPolygonHalfPlane(
        polygon: List<Offset>,
        signedDist: (Offset) -> Float,
    ): List<Offset> {
        if (polygon.isEmpty()) return emptyList()
        val output = ArrayList<Offset>(polygon.size + 1)
        var prev = polygon.last()
        var prevDist = signedDist(prev)
        for (curr in polygon) {
            val currDist = signedDist(curr)
            val prevInside = prevDist <= 1e-5f
            val currInside = currDist <= 1e-5f
            if (currInside) {
                if (!prevInside) {
                    output += intersectEdge(prev, curr, prevDist, currDist)
                }
                output += curr
            } else if (prevInside) {
                output += intersectEdge(prev, curr, prevDist, currDist)
            }
            prev = curr
            prevDist = currDist
        }
        return output
    }

    private fun intersectEdge(a: Offset, b: Offset, da: Float, db: Float): Offset {
        val denom = da - db
        if (abs(denom) < 1e-6f) return a
        val t = (da / denom).coerceIn(0f, 1f)
        return Offset(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    private fun smoothstep(t: Float): Float = t * t * (3f - 2f * t)
}
