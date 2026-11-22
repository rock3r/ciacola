package dev.sebastiano.ciacola.replications.shaders

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

internal object ShadersTeachingSkslSources {

    /**
     * S14–S23 (`shaders.colorEvolution`): Real live SkSL `half4(uColor)` preview square with
     * subtle transparency checkerboard revealed whenever `uColor.a < 1.0`.
     */
    val ColorEvolution: String = """
        uniform float2 iResolution;
        uniform float4 uColor;

        half4 main(float2 fragCoord) {
            float cellSize = max(min(iResolution.x, iResolution.y) / 14.0, 8.0);
            float2 cell = floor(fragCoord / cellSize);
            float checker = mod(cell.x + cell.y, 2.0);
            float3 checkerboard = mix(float3(0.74, 0.76, 0.78), float3(0.96, 0.96, 0.97), checker);

            float alpha = clamp(uColor.a, 0.0, 1.0);
            float3 rgb = clamp(uColor.rgb, 0.0, 1.0);
            float3 outRgb = mix(checkerboard, rgb, alpha);
            return half4(outRgb, 1.0);
        }
    """.trimIndent()

    /**
     * S22–S26 (`shaders.liveUvStatic`): Literal live SkSL normalized UV gradient
     * `half4(uv.x, uv.y, uBlue, 1.0)`.
     */
    val LiveUvStatic: String = """
        uniform float2 iResolution;
        uniform float uBlue;

        half4 main(float2 fragCoord) {
            float2 uv = fragCoord.xy / max(iResolution.xy, float2(1.0));
            return half4(uv.x, uv.y, clamp(uBlue, 0.0, 1.0), 1.0);
        }
    """.trimIndent()

    /**
     * S27 (`shaders.liveUvAnimated`): Literal 5-line animated SkSL
     * `half4(uv.x, uv.y, 0.5 + 0.5 * sin(iTime * uSpeed), 1.0)`.
     */
    val LiveUvAnimated: String = """
        uniform float2 iResolution;
        uniform float iTime;
        uniform float uSpeed;

        half4 main(float2 fragCoord) {
            float2 uv = fragCoord.xy / max(iResolution.xy, float2(1.0));
            float blue = 0.5 + 0.5 * sin(iTime * uSpeed);
            return half4(uv.x, uv.y, blue, 1.0);
        }
    """.trimIndent()

    /**
     * S33–S34 (`shaders.sdfGoalCircle`): Target crisp anti-aliased white SDF circle on black.
     */
    val SdfGoalCircle: String = """
        uniform float2 iResolution;
        uniform float uRadius;

        half4 main(float2 fragCoord) {
            float minDim = max(min(iResolution.x, iResolution.y), 1.0);
            float2 uv = (fragCoord - 0.5 * iResolution) / minDim + float2(0.5);
            float aa = 1.0 / minDim;
            float d = distance(uv, float2(0.5)) - clamp(uRadius, 0.05, 0.95);
            float mask = smoothstep(aa, -aa, d);
            return half4(float3(mask), 1.0);
        }
    """.trimIndent()

    /**
     * S35–S39 (`shaders.sdfRawDistance`): Continuous radial signed-distance field visualization
     * for `float d = length(uv - 0.5) - radius`, bright white at the center fading smoothly outward
     * to dark charcoal at the corners (matching S35's preview square).
     */
    val SdfRawDistance: String = """
        uniform float2 iResolution;
        uniform float uRadius;

        half4 main(float2 fragCoord) {
            float minDim = max(min(iResolution.x, iResolution.y), 1.0);
            float2 uv = (fragCoord - 0.5 * iResolution) / minDim + float2(0.5);
            float r = clamp(uRadius, 0.05, 0.95);
            float d = length(uv - float2(0.5)) - r;
            float shade = clamp(r - d, 0.0, 1.0);
            return half4(float3(shade), 1.0);
        }
    """.trimIndent()

    /**
     * S39–S42 (`shaders.sdfStepCircle`): Crisp `step` / anti-aliased `smoothstep` SDF circle
     * threshold (`vec3(step(d, 0.0))`).
     */
    val SdfStepCircle: String = """
        uniform float2 iResolution;
        uniform float uRadius;

        half4 main(float2 fragCoord) {
            float minDim = max(min(iResolution.x, iResolution.y), 1.0);
            float2 uv = (fragCoord - 0.5 * iResolution) / minDim + float2(0.5);
            float aa = 1.0 / minDim;
            float d = distance(uv, float2(0.5)) - clamp(uRadius, 0.05, 0.95);
            float mask = smoothstep(aa, -aa, d);
            return half4(float3(mask), 1.0);
        }
    """.trimIndent()

    /**
     * S42–S44 (`shaders.sdfCsgSubtract`): Live SkSL CSG subtraction `max(d1, -d2)` cutting the
     * off-center pupil (`vec2(0.625, 0.625)`, radius `0.25`) out of the primary circle.
     */
    val SdfCsgSubtract: String = """
        uniform float2 iResolution;

        half4 main(float2 fragCoord) {
            float minDim = max(min(iResolution.x, iResolution.y), 1.0);
            float2 uv = (fragCoord - 0.5 * iResolution) / minDim + float2(0.5);
            float aa = 1.0 / minDim;
            float d1 = distance(uv, float2(0.5)) - 0.5;
            float d2 = distance(uv, float2(0.625, 0.625)) - 0.25;
            float d = max(d1, -d2);
            float mask = smoothstep(aa, -aa, d);
            return half4(float3(mask), 1.0);
        }
    """.trimIndent()

    /**
     * S44 (`shaders.sdfCsgUnionSubtract`): Live SkSL CSG union + subtraction
     * `min(max(d1, -d2), d3)` adding the inner bullseye circle (radius `0.15`) inside the cutout.
     */
    val SdfCsgUnionSubtract: String = """
        uniform float2 iResolution;

        half4 main(float2 fragCoord) {
            float minDim = max(min(iResolution.x, iResolution.y), 1.0);
            float2 uv = (fragCoord - 0.5 * iResolution) / minDim + float2(0.5);
            float aa = 1.0 / minDim;
            float d1 = distance(uv, float2(0.5)) - 0.5;
            float d2 = distance(uv, float2(0.625, 0.625)) - 0.25;
            float d3 = distance(uv, float2(0.625, 0.625)) - 0.15;
            float d = min(max(d1, -d2), d3);
            float mask = smoothstep(aa, -aa, d);
            return half4(float3(mask), 1.0);
        }
    """.trimIndent()
}

/**
 * Specimen #6 (`shaders.colorEvolution`, S14–S23):
 * Real live SkSL `half4(uColor)` shader preview square (`#000000` -> `#FFFFFF` -> `#FF0000`, plus
 * transparent checkerboard when `a < 1f`).
 */
@Composable
fun ColorEvolutionSpecimen(
    loopMillis: Long = 0L,
    r: Float = 0f,
    g: Float = 0f,
    b: Float = 0f,
    a: Float = 1f,
    isInteractive: Boolean = false,
    modifier: Modifier = Modifier,
) {
    @Suppress("UNUSED_VARIABLE")
    val unusedClock = loopMillis
    @Suppress("UNUSED_VARIABLE")
    val unusedInteractive = isInteractive
    Float4SkslBox(
        sksl = ShadersTeachingSkslSources.ColorEvolution,
        uniformName = "uColor",
        x = r.coerceIn(0f, 1f),
        y = g.coerceIn(0f, 1f),
        z = b.coerceIn(0f, 1f),
        w = a.coerceIn(0f, 1f),
        modifier = modifier,
    )
}

/**
 * Specimen #7 (`shaders.liveUvStatic`, S22–S26):
 * Live SkSL normalized UV gradient `half4(uv.x, uv.y, blue, 1.0)`.
 * Defaults to `blue = 1f` matching S23 & S26 (`return half4(uv.x, uv.y, 1.0, 1.0);`).
 */
@Composable
fun LiveUvStaticSpecimen(
    loopMillis: Long = 0L,
    blue: Float = 1f,
    isInteractive: Boolean = false,
    modifier: Modifier = Modifier,
) {
    @Suppress("UNUSED_VARIABLE")
    val unusedClock = loopMillis
    @Suppress("UNUSED_VARIABLE")
    val unusedInteractive = isInteractive
    SingleFloatSkslBox(
        sksl = ShadersTeachingSkslSources.LiveUvStatic,
        uniformName = "uBlue",
        uniformValue = blue.coerceIn(0f, 1f),
        modifier = modifier,
    )
}

/**
 * Specimen #8 (`shaders.liveUvAnimated`, `periodMs = 6_283`, S27):
 * Live SkSL UV + `0.5 + 0.5 * sin(iTime * speed)` animated blue channel shader square.
 */
@Composable
fun LiveUvAnimatedSpecimen(
    loopMillis: Long,
    speed: Float = 1f,
    isInteractive: Boolean = false,
    modifier: Modifier = Modifier,
) {
    @Suppress("UNUSED_VARIABLE")
    val unusedInteractive = isInteractive
    val iTime = loopMillisToRadians(loopMillis, ShadersSpecimenIds.LIVE_UV_ANIMATED_PERIOD_MS)
    TimeParamSkslBox(
        sksl = ShadersTeachingSkslSources.LiveUvAnimated,
        iTime = iTime,
        paramName = "uSpeed",
        paramValue = speed,
        modifier = modifier,
    )
}

/**
 * Specimen #9 (`shaders.sdfGoalCircle`, S33–S34):
 * Target crisp anti-aliased white SDF circle on black.
 */
@Composable
fun SdfGoalCircleSpecimen(
    loopMillis: Long = 0L,
    radius: Float = 0.5f,
    isInteractive: Boolean = false,
    modifier: Modifier = Modifier,
) {
    @Suppress("UNUSED_VARIABLE")
    val unusedClock = loopMillis
    @Suppress("UNUSED_VARIABLE")
    val unusedInteractive = isInteractive
    SingleFloatSkslBox(
        sksl = ShadersTeachingSkslSources.SdfGoalCircle,
        uniformName = "uRadius",
        uniformValue = radius.coerceIn(0.05f, 0.95f),
        modifier = modifier,
    )
}

/**
 * Specimen #10 (`shaders.sdfRawDistance`, S35–S39):
 * Live SkSL `length(uv - 0.5) - radius` continuous grayscale signed-distance field visualization.
 */
@Composable
fun SdfRawDistanceSpecimen(
    loopMillis: Long = 0L,
    radius: Float = 0.5f,
    isInteractive: Boolean = false,
    modifier: Modifier = Modifier,
) {
    @Suppress("UNUSED_VARIABLE")
    val unusedClock = loopMillis
    @Suppress("UNUSED_VARIABLE")
    val unusedInteractive = isInteractive
    SingleFloatSkslBox(
        sksl = ShadersTeachingSkslSources.SdfRawDistance,
        uniformName = "uRadius",
        uniformValue = radius.coerceIn(0.05f, 0.95f),
        modifier = modifier,
    )
}

/**
 * Specimen #11 (`shaders.sdfStepCircle`, S39–S42):
 * Live SkSL `step` / anti-aliased `smoothstep` crisp SDF circle threshold square.
 */
@Composable
fun SdfStepCircleSpecimen(
    loopMillis: Long = 0L,
    radius: Float = 0.5f,
    isInteractive: Boolean = false,
    modifier: Modifier = Modifier,
) {
    @Suppress("UNUSED_VARIABLE")
    val unusedClock = loopMillis
    @Suppress("UNUSED_VARIABLE")
    val unusedInteractive = isInteractive
    SingleFloatSkslBox(
        sksl = ShadersTeachingSkslSources.SdfStepCircle,
        uniformName = "uRadius",
        uniformValue = radius.coerceIn(0.05f, 0.95f),
        modifier = modifier,
    )
}

/**
 * Specimen #12 (`shaders.sdfCsgSubtract`, S42–S44):
 * Live SkSL CSG subtraction `max(d1, -d2)` off-center circular cutout SDF square.
 */
@Composable
fun SdfCsgSubtractSpecimen(
    loopMillis: Long = 0L,
    isInteractive: Boolean = false,
    modifier: Modifier = Modifier,
) {
    @Suppress("UNUSED_VARIABLE")
    val unusedClock = loopMillis
    @Suppress("UNUSED_VARIABLE")
    val unusedInteractive = isInteractive
    ResolutionOnlySkslBox(
        sksl = ShadersTeachingSkslSources.SdfCsgSubtract,
        modifier = modifier,
    )
}

/**
 * Specimen #13 (`shaders.sdfCsgUnionSubtract`, S44):
 * Live SkSL CSG union + subtraction `min(max(d1, -d2), d3)` compound bullseye SDF square.
 */
@Composable
fun SdfCsgUnionSubtractSpecimen(
    loopMillis: Long = 0L,
    isInteractive: Boolean = false,
    modifier: Modifier = Modifier,
) {
    @Suppress("UNUSED_VARIABLE")
    val unusedClock = loopMillis
    @Suppress("UNUSED_VARIABLE")
    val unusedInteractive = isInteractive
    ResolutionOnlySkslBox(
        sksl = ShadersTeachingSkslSources.SdfCsgUnionSubtract,
        modifier = modifier,
    )
}
