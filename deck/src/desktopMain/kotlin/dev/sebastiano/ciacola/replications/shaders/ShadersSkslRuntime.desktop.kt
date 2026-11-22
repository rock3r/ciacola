package dev.sebastiano.ciacola.replications.shaders

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.asComposeShader
import dev.sebastiano.ciacola.effects.compileOrGetSkslEffect
import kotlin.math.PI
import org.jetbrains.skia.FilterTileMode
import org.jetbrains.skia.ImageFilter
import org.jetbrains.skia.RuntimeEffect
import org.jetbrains.skia.RuntimeShaderBuilder
import org.jetbrains.skia.Shader as SkiaShader

internal const val TWO_PI: Double = 2.0 * PI

/**
 * Converts a deterministic [loopMillis] value into a seamlessly wrapping `[0, 2π)` phase over
 * [periodMs]. Negative timestamps wrap cleanly via floor-modulo arithmetic.
 */
internal fun loopMillisToRadians(loopMillis: Long, periodMs: Long): Float {
    val safePeriod = periodMs.coerceAtLeast(1L)
    val wrapped = ((loopMillis % safePeriod) + safePeriod) % safePeriod
    return ((wrapped.toDouble() / safePeriod.toDouble()) * TWO_PI).toFloat()
}

/**
 * Converts a deterministic [loopMillis] value into a normalized `[0, 1)` loop fraction over
 * [periodMs].
 */
internal fun loopMillisToFraction(loopMillis: Long, periodMs: Long): Float {
    val safePeriod = periodMs.coerceAtLeast(1L)
    val wrapped = ((loopMillis % safePeriod) + safePeriod) % safePeriod
    return (wrapped.toDouble() / safePeriod.toDouble()).toFloat()
}

/**
 * Reusable native Skia shader holder that caches the compiled [RuntimeShaderBuilder], a single
 * Compose [Paint], and the most recently synthesized [SkiaShader] keyed on primitive draw inputs
 * so draw hot loops allocate zero Kotlin wrapper objects (`Paint`, `Rect`, `Map`, lambdas).
 */
internal class ReusableSkslShaderHolder(effect: RuntimeEffect) {
    private val builder: RuntimeShaderBuilder = RuntimeShaderBuilder(effect)
    private val paint: Paint = Paint()
    private var currentShader: SkiaShader? = null
    private var retiredShader: SkiaShader? = null

    private var lastWidth: Float = Float.NaN
    private var lastHeight: Float = Float.NaN
    private var lastU0: Float = Float.NaN
    private var lastU1: Float = Float.NaN
    private var lastU2: Float = Float.NaN
    private var lastU3: Float = Float.NaN

    private fun isUnchanged(
        width: Float,
        height: Float,
        u0: Float,
        u1: Float,
        u2: Float,
        u3: Float,
    ): Boolean = currentShader != null &&
        lastWidth == width &&
        lastHeight == height &&
        lastU0 == u0 &&
        lastU1 == u1 &&
        lastU2 == u2 &&
        lastU3 == u3

    private fun commitShader(
        width: Float,
        height: Float,
        u0: Float,
        u1: Float,
        u2: Float,
        u3: Float,
    ): Paint {
        retiredShader?.close()
        retiredShader = currentShader
        val next = builder.makeShader()
        paint.shader = next.asComposeShader()
        currentShader = next
        lastWidth = width
        lastHeight = height
        lastU0 = u0
        lastU1 = u1
        lastU2 = u2
        lastU3 = u3
        return paint
    }

    fun prepareResolutionOnly(width: Float, height: Float): Paint {
        if (isUnchanged(width, height, 0f, 0f, 0f, 0f)) return paint
        builder.uniform("iResolution", width, height)
        return commitShader(width, height, 0f, 0f, 0f, 0f)
    }

    fun prepareSingleFloat(width: Float, height: Float, name: String, value: Float): Paint {
        if (isUnchanged(width, height, value, 0f, 0f, 0f)) return paint
        builder.uniform("iResolution", width, height)
        builder.uniform(name, value)
        return commitShader(width, height, value, 0f, 0f, 0f)
    }

    fun prepareTimeAndFloat(
        width: Float,
        height: Float,
        iTime: Float,
        paramName: String,
        paramValue: Float,
    ): Paint {
        if (isUnchanged(width, height, iTime, paramValue, 0f, 0f)) return paint
        builder.uniform("iResolution", width, height)
        builder.uniform("iTime", iTime)
        builder.uniform(paramName, paramValue)
        return commitShader(width, height, iTime, paramValue, 0f, 0f)
    }

    fun prepareTimeAndFloat2AndFloat(
        width: Float,
        height: Float,
        iTime: Float,
        vec2Name: String,
        x: Float,
        y: Float,
        paramName: String,
        paramValue: Float,
    ): Paint {
        if (isUnchanged(width, height, iTime, x, y, paramValue)) return paint
        builder.uniform("iResolution", width, height)
        builder.uniform("iTime", iTime)
        builder.uniform(vec2Name, x, y)
        builder.uniform(paramName, paramValue)
        return commitShader(width, height, iTime, x, y, paramValue)
    }

    fun prepareFloat4(
        width: Float,
        height: Float,
        name: String,
        x: Float,
        y: Float,
        z: Float,
        w: Float,
    ): Paint {
        if (isUnchanged(width, height, x, y, z, w)) return paint
        builder.uniform("iResolution", width, height)
        builder.uniform(name, x, y, z, w)
        return commitShader(width, height, x, y, z, w)
    }

    fun close() {
        paint.shader = null
        currentShader?.close()
        currentShader = null
        retiredShader?.close()
        retiredShader = null
        builder.close()
    }
}

/**
 * Compiles [sksl] once (via the shared bounded `RuntimeEffect` cache) and remembers a disposable
 * [ReusableSkslShaderHolder]. Throws on invalid SkSL so tests catch compile errors immediately.
 */
@Composable
internal fun rememberSkslShaderHolder(sksl: String): ReusableSkslShaderHolder {
    val effect = remember(sksl) { compileOrGetSkslEffect(sksl).getOrThrow() }
    val holder = remember(effect) { ReusableSkslShaderHolder(effect) }
    DisposableEffect(holder) {
        onDispose { holder.close() }
    }
    return holder
}

/**
 * Renders a resolution-only SkSL shader with zero per-frame allocations in `drawBehind`.
 */
@Composable
internal fun ResolutionOnlySkslBox(
    sksl: String,
    modifier: Modifier = Modifier,
) {
    val holder = rememberSkslShaderHolder(sksl)
    Box(
        modifier = modifier.drawBehind {
            if (size.width <= 0f || size.height <= 0f) return@drawBehind
            val shaderPaint = holder.prepareResolutionOnly(size.width, size.height)
            drawContext.canvas.drawRect(0f, 0f, size.width, size.height, shaderPaint)
        },
    )
}

/**
 * Renders a SkSL shader taking `iResolution` and a single float uniform [uniformName].
 */
@Composable
internal fun SingleFloatSkslBox(
    sksl: String,
    uniformName: String,
    uniformValue: Float,
    modifier: Modifier = Modifier,
) {
    val holder = rememberSkslShaderHolder(sksl)
    Box(
        modifier = modifier.drawBehind {
            if (size.width <= 0f || size.height <= 0f) return@drawBehind
            val shaderPaint = holder.prepareSingleFloat(size.width, size.height, uniformName, uniformValue)
            drawContext.canvas.drawRect(0f, 0f, size.width, size.height, shaderPaint)
        },
    )
}

/**
 * Renders an animated SkSL shader taking `iResolution`, `iTime`, and one typed float parameter.
 */
@Composable
internal fun TimeParamSkslBox(
    sksl: String,
    iTime: Float,
    paramName: String,
    paramValue: Float,
    modifier: Modifier = Modifier,
) {
    val holder = rememberSkslShaderHolder(sksl)
    Box(
        modifier = modifier.drawBehind {
            if (size.width <= 0f || size.height <= 0f) return@drawBehind
            val shaderPaint = holder.prepareTimeAndFloat(size.width, size.height, iTime, paramName, paramValue)
            drawContext.canvas.drawRect(0f, 0f, size.width, size.height, shaderPaint)
        },
    )
}

/**
 * Renders an animated SkSL shader taking `iResolution`, `iTime`, a `float2` offset, and a `float`.
 */
@Composable
internal fun TimeVec2ParamSkslBox(
    sksl: String,
    iTime: Float,
    vec2Name: String,
    x: Float,
    y: Float,
    paramName: String,
    paramValue: Float,
    modifier: Modifier = Modifier,
) {
    val holder = rememberSkslShaderHolder(sksl)
    Box(
        modifier = modifier.drawBehind {
            if (size.width <= 0f || size.height <= 0f) return@drawBehind
            val shaderPaint = holder.prepareTimeAndFloat2AndFloat(
                width = size.width,
                height = size.height,
                iTime = iTime,
                vec2Name = vec2Name,
                x = x,
                y = y,
                paramName = paramName,
                paramValue = paramValue,
            )
            drawContext.canvas.drawRect(0f, 0f, size.width, size.height, shaderPaint)
        },
    )
}

/**
 * Renders a SkSL shader taking `iResolution` and a `float4` uniform.
 */
@Composable
internal fun Float4SkslBox(
    sksl: String,
    uniformName: String,
    x: Float,
    y: Float,
    z: Float,
    w: Float,
    modifier: Modifier = Modifier,
) {
    val holder = rememberSkslShaderHolder(sksl)
    Box(
        modifier = modifier.drawBehind {
            if (size.width <= 0f || size.height <= 0f) return@drawBehind
            val shaderPaint = holder.prepareFloat4(size.width, size.height, uniformName, x, y, z, w)
            drawContext.canvas.drawRect(0f, 0f, size.width, size.height, shaderPaint)
        },
    )
}

/**
 * Reusable native Skia `ImageFilter` holder for `RuntimeShader` + optional upstream blur
 * `RenderEffect` pipelines (used by Haze frosted glass and Liquid Glass optical refraction).
 */
internal class ReusableRuntimeRenderEffectHolder(
    effect: RuntimeEffect,
    private val contentShaderName: String = "content",
) {
    private val builder: RuntimeShaderBuilder = RuntimeShaderBuilder(effect)
    private var currentBlurFilter: ImageFilter? = null
    private var currentRuntimeFilter: ImageFilter? = null
    private var retiredBlurFilter: ImageFilter? = null
    private var retiredRuntimeFilter: ImageFilter? = null
    private var currentRenderEffect: RenderEffect? = null

    private var lastWidth: Float = Float.NaN
    private var lastHeight: Float = Float.NaN
    private var lastBlurSigma: Float = Float.NaN
    private var lastA: Float = Float.NaN
    private var lastB: Float = Float.NaN
    private var lastC: Float = Float.NaN
    private var lastD: Float = Float.NaN
    private var lastE: Float = Float.NaN

    fun obtainHazeEffect(
        width: Float,
        height: Float,
        blurSigma: Float,
        tintStrength: Float,
        noiseAmount: Float,
    ): RenderEffect? {
        if (width <= 0f || height <= 0f) return null
        val cached = currentRenderEffect
        if (
            cached != null &&
            lastWidth == width &&
            lastHeight == height &&
            lastBlurSigma == blurSigma &&
            lastA == tintStrength &&
            lastB == noiseAmount
        ) {
            return cached
        }

        retiredRuntimeFilter?.close()
        retiredBlurFilter?.close()
        retiredRuntimeFilter = currentRuntimeFilter
        retiredBlurFilter = currentBlurFilter
        val inputBlur = ImageFilter.makeBlur(
            sigmaX = blurSigma,
            sigmaY = blurSigma,
            mode = FilterTileMode.CLAMP,
        )
        builder.uniform("iResolution", width, height)
        builder.uniform("uTintStrength", tintStrength)
        builder.uniform("uNoiseAmount", noiseAmount)
        val nextRuntime = ImageFilter.makeRuntimeShader(
            runtimeShaderBuilder = builder,
            shaderName = contentShaderName,
            input = inputBlur,
        )
        val nextCompose = nextRuntime.asComposeRenderEffect()
        currentBlurFilter = inputBlur
        currentRuntimeFilter = nextRuntime
        currentRenderEffect = nextCompose
        lastWidth = width
        lastHeight = height
        lastBlurSigma = blurSigma
        lastA = tintStrength
        lastB = noiseAmount
        return nextCompose
    }

    fun obtainLiquidGlassEffect(
        width: Float,
        height: Float,
        centerX: Float,
        centerY: Float,
        halfWidth: Float,
        halfHeight: Float,
        cornerRadius: Float,
        timeRad: Float,
    ): RenderEffect? {
        if (width <= 0f || height <= 0f) return null
        val cached = currentRenderEffect
        if (
            cached != null &&
            lastWidth == width &&
            lastHeight == height &&
            lastA == centerX &&
            lastB == centerY &&
            lastC == halfWidth &&
            lastD == halfHeight &&
            lastE == timeRad &&
            lastBlurSigma == cornerRadius
        ) {
            return cached
        }

        retiredRuntimeFilter?.close()
        retiredBlurFilter?.close()
        retiredRuntimeFilter = currentRuntimeFilter
        retiredBlurFilter = currentBlurFilter
        builder.uniform("iResolution", width, height)
        builder.uniform("uLensCenter", centerX, centerY)
        builder.uniform("uLensHalfSize", halfWidth, halfHeight)
        builder.uniform("uCornerRadius", cornerRadius)
        builder.uniform("uTime", timeRad)
        val nextRuntime = ImageFilter.makeRuntimeShader(
            runtimeShaderBuilder = builder,
            shaderName = contentShaderName,
            input = null,
        )
        val nextCompose = nextRuntime.asComposeRenderEffect()
        currentBlurFilter = null
        currentRuntimeFilter = nextRuntime
        currentRenderEffect = nextCompose
        lastWidth = width
        lastHeight = height
        lastBlurSigma = cornerRadius
        lastA = centerX
        lastB = centerY
        lastC = halfWidth
        lastD = halfHeight
        lastE = timeRad
        return nextCompose
    }

    fun close() {
        currentRenderEffect = null
        currentRuntimeFilter?.close()
        currentRuntimeFilter = null
        currentBlurFilter?.close()
        currentBlurFilter = null
        retiredRuntimeFilter?.close()
        retiredRuntimeFilter = null
        retiredBlurFilter?.close()
        retiredBlurFilter = null
        builder.close()
    }
}

@Composable
internal fun rememberRuntimeRenderEffectHolder(
    sksl: String,
    contentShaderName: String = "content",
): ReusableRuntimeRenderEffectHolder {
    val effect = remember(sksl) { compileOrGetSkslEffect(sksl).getOrThrow() }
    val holder = remember(effect, contentShaderName) {
        ReusableRuntimeRenderEffectHolder(effect, contentShaderName)
    }
    DisposableEffect(holder) {
        onDispose { holder.close() }
    }
    return holder
}
