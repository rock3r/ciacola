package dev.sebastiano.ciacola.effects.v2

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import dev.sebastiano.ciacola.effects.compileOrGetSkslEffect
import org.jetbrains.skia.ColorFilter
import org.jetbrains.skia.ColorMatrix
import org.jetbrains.skia.FilterTileMode
import org.jetbrains.skia.Image as SkiaImage
import org.jetbrains.skia.ImageFilter
import org.jetbrains.skia.Paint as SkiaPaint
import org.jetbrains.skia.Rect as SkiaRect
import org.jetbrains.skia.RuntimeShaderBuilder
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Surface

/**
 * Applies a full [LayerState] on Desktop Skia, including Skia [ImageFilter.makeBlur],
 * 4×5 [ColorMatrix] filters (`fromDarkness`), and 8-way `Wipe` / `Iris` clipping.
 */
fun Modifier.applyLayerState(state: LayerState): Modifier {
    if (state.isIdentity) return this
    val skiaRenderEffect = buildSkiaImageFilter(state)?.asComposeRenderEffect()
    val layerBlock: GraphicsLayerScope.() -> Unit = {
        alpha = state.alpha.coerceIn(0f, 1f)
        translationX = state.translationX
        translationY = state.translationY
        scaleX = state.scaleX
        scaleY = state.scaleY
        transformOrigin = state.transformOrigin
        rotationZ = state.rotationZ
        renderEffect = skiaRenderEffect
    }
    val transformed = this.graphicsLayer(layerBlock)
    return when (val clip = state.clip) {
        EffectClip.None -> transformed
        is EffectClip.Wipe -> transformed.drawWithContent {
            val path = LayerEffects.buildWipeClipPath(size, clip)
            clipPath(path) {
                this@drawWithContent.drawContent()
            }
        }
        is EffectClip.Iris -> transformed.drawWithContent {
            val path = LayerEffects.buildIrisClipPath(size, clip)
            clipPath(path) {
                this@drawWithContent.drawContent()
            }
        }
        is EffectClip.RestBounds -> this.restBoundsClip(clip).graphicsLayer(layerBlock)
    }
}

private fun buildSkiaImageFilter(state: LayerState): ImageFilter? {
    val blur = if (state.blurRadiusPx > 0.05f) {
        val sigma = state.blurRadiusPx * 0.57735f
        ImageFilter.makeBlur(sigma, sigma, FilterTileMode.DECAL)
    } else {
        null
    }
    val colorFilter = state.colorMatrix?.takeIf { it.size == 20 }?.let { matrix ->
        ImageFilter.makeColorFilter(
            f = ColorFilter.makeMatrix(ColorMatrix(matrix)),
            input = blur,
            crop = null,
        )
    }
    return colorFilter ?: blur
}

/**
 * Production SkSL runtime shaders for Keynote's Content-tier effects (`trace`, `sparkle`, `shimmer`).
 *
 * Each shader samples the rendered node pixels via `uniform shader content;` and converges cleanly:
 * - Enter: transparent `half4(0)` at `uProgress == 0`, exact `content.eval(fragCoord)` at `uProgress == 1`.
 * - Exit: exact `content.eval(fragCoord)` at `uProgress == 0`, transparent `half4(0)` at `uProgress == 1`.
 * - Emphasis (`shimmer`): exact `content.eval(fragCoord)` at both `uProgress == 0` and `uProgress == 1`.
 */
object ContentEffectShaders {
    const val TRACE_SKSL: String = """
        uniform shader content;
        uniform float2 iResolution;
        uniform float uProgress;
        uniform float2 uDir;
        uniform float4 uAccentColor;
        uniform float uIsExit;

        float directionalProj(float2 uv, float2 dir) {
            float2 c0 = float2(0.0, 0.0);
            float2 c1 = float2(1.0, 0.0);
            float2 c2 = float2(0.0, 1.0);
            float2 c3 = float2(1.0, 1.0);
            float p0 = dot(c0, dir);
            float p1 = dot(c1, dir);
            float p2 = dot(c2, dir);
            float p3 = dot(c3, dir);
            float pMin = min(min(p0, p1), min(p2, p3));
            float pMax = max(max(p0, p1), max(p2, p3));
            return (dot(uv, dir) - pMin) / max(pMax - pMin, 0.0001);
        }

        half4 main(float2 fragCoord) {
            float p = clamp(uProgress, 0.0, 1.0);
            half4 src = content.eval(fragCoord);
            if (uIsExit < 0.5) {
                if (p <= 0.0001) return half4(0.0);
                if (p >= 0.9999) return src;
            } else {
                if (p <= 0.0001) return src;
                if (p >= 0.9999) return half4(0.0);
            }

            float2 res = max(iResolution, float2(1.0));
            float2 uv = fragCoord / res;
            float proj = directionalProj(uv, normalize(uDir));

            // 8-neighbor alpha silhouette edge detector (2 px kernel for crisp 1080p strokes).
            float stepPx = 1.75;
            float aC = float(src.a);
            float aL = float(content.eval(fragCoord + float2(-stepPx, 0.0)).a);
            float aR = float(content.eval(fragCoord + float2( stepPx, 0.0)).a);
            float aU = float(content.eval(fragCoord + float2(0.0, -stepPx)).a);
            float aD = float(content.eval(fragCoord + float2(0.0,  stepPx)).a);
            float aUL = float(content.eval(fragCoord + float2(-stepPx, -stepPx)).a);
            float aUR = float(content.eval(fragCoord + float2( stepPx, -stepPx)).a);
            float aDL = float(content.eval(fragCoord + float2(-stepPx,  stepPx)).a);
            float aDR = float(content.eval(fragCoord + float2( stepPx,  stepPx)).a);

            float maxA = max(max(max(aL, aR), max(aU, aD)), max(max(aUL, aUR), max(aDL, aDR)));
            float minA = min(min(min(aL, aR), min(aU, aD)), min(min(aUL, aUR), min(aDL, aDR)));
            float edge = clamp((maxA - minA) * 1.45, 0.0, 1.0);

            float traceHead;
            float traceWindow;
            float fillAlpha;
            if (uIsExit < 0.5) {
                // Enter: edge trace sweeps 0..0.75 while interior fills smoothly over 0.60..1.00.
                traceHead = clamp(p / 0.75, 0.0, 1.0);
                traceWindow = smoothstep(traceHead + 0.04, traceHead - 0.02, proj);
                fillAlpha = smoothstep(0.60, 1.00, p);
            } else {
                // Exit: interior fades out 0..0.40, then silhouette trace erases 0.25..1.00.
                fillAlpha = 1.0 - smoothstep(0.0, 0.40, p);
                traceHead = clamp((p - 0.25) / 0.75, 0.0, 1.0);
                traceWindow = smoothstep(traceHead - 0.04, traceHead + 0.02, proj);
            }

            // Bright leading spark right at the advancing trace head along silhouette edges.
            float headProximity = exp(-pow((proj - traceHead) / 0.055, 2.0));
            float sparkActive = smoothstep(0.0, 0.06, p) * (1.0 - smoothstep(0.85, 1.0, p));
            float spark = edge * headProximity * sparkActive;
            float outlineMask = edge * traceWindow * (1.0 - fillAlpha * 0.85);

            float3 accent = uAccentColor.rgb;
            float3 fillPremul = float3(src.rgb) * fillAlpha;
            float3 traceRgb = mix(accent, float3(1.0, 1.0, 1.0), clamp(spark * 0.85, 0.0, 1.0));
            float traceAlpha = clamp(outlineMask * 0.92 + spark * 0.95, 0.0, 1.0);

            float outAlpha = clamp(float(src.a) * fillAlpha + traceAlpha * (1.0 - float(src.a) * fillAlpha), 0.0, 1.0);
            float3 outPremul = fillPremul + traceRgb * traceAlpha * (1.0 - float(src.a) * fillAlpha * 0.5);
            return half4(half3(clamp(outPremul, 0.0, 1.0)), half(outAlpha));
        }
    """

    const val SPARKLE_SKSL: String = """
        uniform shader content;
        uniform float2 iResolution;
        uniform float uProgress;
        uniform float2 uDir;
        uniform float4 uSparkleColor;
        uniform float uSeed;
        uniform float uIsExit;

        float hash21(float2 p) {
            p = fract(p * float2(123.34, 456.21) + uSeed * 0.137);
            p += dot(p, p + 45.32);
            return fract(p.x * p.y);
        }

        float directionalProj(float2 uv, float2 dir) {
            float p0 = 0.0;
            float p1 = dir.x;
            float p2 = dir.y;
            float p3 = dir.x + dir.y;
            float pMin = min(min(p0, p1), min(p2, p3));
            float pMax = max(max(p0, p1), max(p2, p3));
            return (dot(uv, dir) - pMin) / max(pMax - pMin, 0.0001);
        }

        float starGlint(float2 cellUv, float2 center, float size) {
            float2 d = (cellUv - center) / max(size, 0.001);
            float core = exp(-dot(d, d) * 6.0);
            float spikeH = exp(-abs(d.y) * 14.0) * exp(-abs(d.x) * 2.2);
            float spikeV = exp(-abs(d.x) * 14.0) * exp(-abs(d.y) * 2.2);
            return clamp(core + (spikeH + spikeV) * 0.65, 0.0, 1.0);
        }

        half4 main(float2 fragCoord) {
            float p = clamp(uProgress, 0.0, 1.0);
            half4 src = content.eval(fragCoord);
            if (uIsExit < 0.5) {
                if (p <= 0.0001) return half4(0.0);
                if (p >= 0.9999) return src;
            } else {
                if (p <= 0.0001) return src;
                if (p >= 0.9999) return half4(0.0);
            }

            float2 res = max(iResolution, float2(1.0));
            float2 uv = fragCoord / res;
            float proj = directionalProj(uv, normalize(uDir));

            // Sweep front slightly wider than [0, 1] so sparkle band clears both edges cleanly.
            float front = mix(-0.18, 1.18, p);
            float reveal = (uIsExit < 0.5)
                ? smoothstep(front + 0.10, front - 0.08, proj)
                : smoothstep(front - 0.10, front + 0.08, proj);

            // Neighborhood silhouette halo so sparkles hug the rendered content without polluting empty stage.
            float r = 6.0;
            float nearA = max(
                float(src.a),
                max(
                    max(float(content.eval(fragCoord + float2(r, 0.0)).a), float(content.eval(fragCoord + float2(-r, 0.0)).a)),
                    max(float(content.eval(fragCoord + float2(0.0, r)).a), float(content.eval(fragCoord + float2(0.0, -r)).a))
                )
            );

            float band = exp(-pow((proj - front) / 0.14, 2.0));
            float envelope = smoothstep(0.0, 0.08, p) * (1.0 - smoothstep(0.92, 1.0, p));

            // Two-layer twinkling glitter grid.
            float2 gridA = fragCoord / 18.0;
            float2 idA = floor(gridA);
            float2 fA = fract(gridA);
            float hA1 = hash21(idA);
            float hA2 = hash21(idA + 19.7);
            float twinkleA = 0.5 + 0.5 * sin(p * 28.0 + hA1 * 24.0);
            float gA = starGlint(fA, float2(0.2 + 0.6 * hA1, 0.2 + 0.6 * hA2), 0.34) * twinkleA;

            float2 gridB = fragCoord / 11.0 + float2(3.7, 1.3);
            float2 idB = floor(gridB);
            float2 fB = fract(gridB);
            float hB1 = hash21(idB + 53.1);
            float hB2 = hash21(idB + 91.4);
            float twinkleB = 0.5 + 0.5 * sin(p * 36.0 + hB2 * 31.0);
            float gB = starGlint(fB, float2(0.2 + 0.6 * hB1, 0.2 + 0.6 * hB2), 0.28) * twinkleB;

            float glitter = clamp((gA * 0.85 + gB * 0.65) * band * envelope * nearA, 0.0, 1.0);
            float3 glitterColor = mix(uSparkleColor.rgb, float3(1.0, 1.0, 1.0), glitter * 0.65);

            float baseAlpha = float(src.a) * reveal;
            float3 basePremul = float3(src.rgb) * reveal;
            float outAlpha = clamp(baseAlpha + glitter * (1.0 - baseAlpha), 0.0, 1.0);
            float3 outPremul = clamp(basePremul + glitterColor * glitter, 0.0, 1.0);
            return half4(half3(outPremul), half(outAlpha));
        }
    """

    const val SHIMMER_SKSL: String = """
        uniform shader content;
        uniform float2 iResolution;
        uniform float uProgress;
        uniform float2 uDir;
        uniform float4 uHighlightColor;
        uniform float uIntensity;
        uniform float uIsEmphasis;

        float hash21(float2 p) {
            p = fract(p * float2(234.34, 435.345));
            p += dot(p, p + 34.23);
            return fract(p.x * p.y);
        }

        float directionalProj(float2 uv, float2 dir) {
            float p0 = 0.0;
            float p1 = dir.x;
            float p2 = dir.y;
            float p3 = dir.x + dir.y;
            float pMin = min(min(p0, p1), min(p2, p3));
            float pMax = max(max(p0, p1), max(p2, p3));
            return (dot(uv, dir) - pMin) / max(pMax - pMin, 0.0001);
        }

        float glint4(float2 cellUv, float2 center) {
            float2 d = (cellUv - center) * 3.4;
            float core = exp(-dot(d, d) * 5.0);
            float crossH = exp(-abs(d.y) * 16.0) * exp(-abs(d.x) * 1.9);
            float crossV = exp(-abs(d.x) * 16.0) * exp(-abs(d.y) * 1.9);
            return clamp(core + (crossH + crossV) * 0.85, 0.0, 1.0);
        }

        half4 main(float2 fragCoord) {
            float p = clamp(uProgress, 0.0, 1.0);
            half4 src = content.eval(fragCoord);
            if (uIsEmphasis > 0.5) {
                if (p <= 0.0001 || p >= 0.9999) return src;
            } else {
                if (p <= 0.0001) return half4(0.0);
                if (p >= 0.9999) return src;
            }

            float2 res = max(iResolution, float2(1.0));
            float2 uv = fragCoord / res;
            float proj = directionalProj(uv, normalize(uDir));

            float sweepCenter = mix(-0.22, 1.22, p);
            float sheenBand = exp(-pow((proj - sweepCenter) / 0.13, 2.0));
            float envelope = smoothstep(0.0, 0.10, p) * (1.0 - smoothstep(0.90, 1.0, p));

            // Crisp 4-point star glints riding the specular sweep across visible content alpha.
            float2 cell = fragCoord / 28.0;
            float2 id = floor(cell);
            float2 f = fract(cell);
            float h1 = hash21(id);
            float h2 = hash21(id + 41.2);
            float starActive = step(0.55, h1);
            float star = glint4(f, float2(0.25 + 0.5 * h1, 0.25 + 0.5 * h2)) * starActive * sheenBand;

            float reveal = (uIsEmphasis > 0.5)
                ? 1.0
                : smoothstep(sweepCenter + 0.14, sweepCenter - 0.10, proj);

            float srcA = float(src.a) * reveal;
            float3 basePremul = float3(src.rgb) * reveal;
            float highlightAmt = clamp((sheenBand * 0.55 + star * 1.15) * uIntensity * envelope, 0.0, 1.5);
            float3 outPremul = clamp(basePremul + uHighlightColor.rgb * highlightAmt * srcA, 0.0, 1.0);
            return half4(half3(outPremul), half(clamp(srcA, 0.0, 1.0)));
        }
    """

    fun skslFor(effectId: String): String? = when (effectId) {
        BuildEffects.TRACE -> TRACE_SKSL
        BuildEffects.SPARKLE -> SPARKLE_SKSL
        BuildEffects.SHIMMER -> SHIMMER_SKSL
        else -> null
    }
}

/**
 * Pure Skia offscreen evaluator for Content-tier effects (`trace`, `sparkle`, `shimmer`), rendering
 * [sourceImage] through the compiled SkSL `RuntimeShader` into a new raster [SkiaImage].
 */
fun renderContentEffectImage(
    sourceImage: SkiaImage,
    effectId: String,
    kind: EffectBuildKind,
    progress: Float,
    args: EffectArgs = EffectArgs.Empty,
): SkiaImage {
    val w = sourceImage.width.coerceAtLeast(1)
    val h = sourceImage.height.coerceAtLeast(1)
    val surface = Surface.makeRasterN32Premul(w, h)
    try {
        val sksl = ContentEffectShaders.skslFor(effectId)
            ?: error("Effect '$effectId' is not a Content-tier SkSL effect")
        val runtimeEffect = compileOrGetSkslEffect(sksl).getOrThrow()
        val builder = RuntimeShaderBuilder(runtimeEffect)
        try {
            val contentShader = sourceImage.makeShader(
                tmx = FilterTileMode.CLAMP,
                tmy = FilterTileMode.CLAMP,
                sampling = SamplingMode.LINEAR,
            )
            try {
                bindContentEffectUniforms(
                    builder = builder,
                    effectId = effectId,
                    kind = kind,
                    progress = progress.coerceIn(0f, 1f),
                    width = w.toFloat(),
                    height = h.toFloat(),
                    args = args,
                )
                builder.child("content", contentShader)
                val shader = builder.makeShader()
                try {
                    val paint = SkiaPaint().apply { this.shader = shader }
                    try {
                        surface.canvas.drawRect(SkiaRect.makeWH(w.toFloat(), h.toFloat()), paint)
                    } finally {
                        paint.close()
                    }
                } finally {
                    shader.close()
                }
            } finally {
                contentShader.close()
            }
        } finally {
            builder.close()
        }
        return surface.makeImageSnapshot()
    } finally {
        surface.close()
    }
}

/**
 * Compose modifier applying a Content-tier SkSL shader (`trace`, `sparkle`, `shimmer`) to any child
 * composable via Compose's native `GraphicsLayer` + Skia `ImageFilter.makeRuntimeShader`, so the
 * child is captured at full stage/device resolution without per-frame CPU raster allocations.
 */
fun Modifier.applyContentEffect(
    effectId: String,
    kind: EffectBuildKind,
    progress: Float,
    args: EffectArgs = EffectArgs.Empty,
    reducedMotion: Boolean = false,
    curve: CurveV2? = null,
): Modifier {
    val rawP = progress.coerceIn(0f, 1f)
    if (reducedMotion) {
        return applyLayerState(LayerEffects.evaluateDissolve(kind, rawP, curve))
    }

    // Fast-path identity and fully-hidden endpoints without activating a render-effect layer.
    when (kind) {
        EffectBuildKind.Enter -> {
            if (rawP <= 0f) return graphicsLayer { alpha = 0f }
            if (rawP >= 1f) return this
        }
        EffectBuildKind.Exit -> {
            if (rawP <= 0f) return this
            if (rawP >= 1f) return graphicsLayer { alpha = 0f }
        }
        EffectBuildKind.Emphasis -> {
            if (rawP <= 0f || rawP >= 1f) return this
        }
    }

    val p = if (curve != null) curve.sample(rawP).coerceIn(0f, 1f) else rawP
    val sksl = ContentEffectShaders.skslFor(effectId) ?: return this
    val effect = compileOrGetSkslEffect(sksl).getOrNull() ?: return this

    return this.graphicsLayer {
        val w = size.width.coerceAtLeast(1f)
        val h = size.height.coerceAtLeast(1f)
        val builder = RuntimeShaderBuilder(effect)
        try {
            bindContentEffectUniforms(
                builder = builder,
                effectId = effectId,
                kind = kind,
                progress = p,
                width = w,
                height = h,
                args = args,
            )
            renderEffect = ImageFilter.makeRuntimeShader(
                runtimeShaderBuilder = builder,
                shaderName = "content",
                input = null,
            ).asComposeRenderEffect()
        } finally {
            builder.close()
        }
    }
}

private fun bindContentEffectUniforms(
    builder: RuntimeShaderBuilder,
    effectId: String,
    kind: EffectBuildKind,
    progress: Float,
    width: Float,
    height: Float,
    args: EffectArgs,
) {
    builder.uniform("iResolution", width, height)
    builder.uniform("uProgress", progress)
    val defaultDir = when (effectId) {
        BuildEffects.SHIMMER -> EffectDirection8.TopLeftToBottomRight
        else -> EffectDirection8.LeftToRight
    }
    val dirVec: Offset = args.direction8("direction", defaultDir).unitVector()
    builder.uniform("uDir", dirVec.x, dirVec.y)

    when (effectId) {
        BuildEffects.TRACE -> {
            val accent = args.color("accentColor", Color(0xFF8BE9FD))
            builder.uniform("uAccentColor", accent.red, accent.green, accent.blue, accent.alpha)
            builder.uniform("uIsExit", if (kind == EffectBuildKind.Exit) 1f else 0f)
        }
        BuildEffects.SPARKLE -> {
            val sparkle = args.color("sparkleColor", Color(0xFFFFF3B0))
            val seed = (args.long("seed", 1337L) % 10_000L).toFloat()
            builder.uniform("uSparkleColor", sparkle.red, sparkle.green, sparkle.blue, sparkle.alpha)
            builder.uniform("uSeed", seed)
            builder.uniform("uIsExit", if (kind == EffectBuildKind.Exit) 1f else 0f)
        }
        BuildEffects.SHIMMER -> {
            val highlight = args.color("highlightColor", Color(0xFFFFFFFF))
            val intensity = args.float("intensity", 1.15f).coerceIn(0f, 3f)
            builder.uniform("uHighlightColor", highlight.red, highlight.green, highlight.blue, highlight.alpha)
            builder.uniform("uIntensity", intensity)
            builder.uniform("uIsEmphasis", if (kind == EffectBuildKind.Emphasis) 1f else 0f)
        }
    }
}
