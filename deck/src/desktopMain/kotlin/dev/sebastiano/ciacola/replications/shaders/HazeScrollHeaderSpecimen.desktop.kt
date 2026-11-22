package dev.sebastiano.ciacola.replications.shaders

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sebastiano.ciacola.inspector.stageInteractive
import kotlin.math.cos
import kotlin.math.sin

internal object HazeRenderEffectSksl {
    /**
     * Progressive frosted-glass tint + micro-grain film noise SkSL shader chained directly after
     * Skia `ImageFilter.makeBlur` for Specimen #3 (`shaders.hazeScrollHeader`, S07).
     */
    val FrostedGlass: String = """
        uniform shader content;
        uniform float2 iResolution;
        uniform float uTintStrength;
        uniform float uNoiseAmount;

        half4 main(float2 fragCoord) {
            float2 res = max(iResolution, float2(1.0));
            float2 uv = clamp(fragCoord / res, float2(0.0), float2(1.0));
            half4 blurred = content.eval(fragCoord);

            // Progressive vertical frosting veil: denser near top chrome, airy along bottom edge
            float progressive = mix(0.96, 0.78, uv.y);
            float3 veilColor = float3(0.965, 0.958, 0.975);
            float mixAmt = clamp(uTintStrength * progressive, 0.0, 0.90);
            float3 frosted = mix(float3(blurred.rgb), veilColor, mixAmt);

            // Fine static micro-grain noise simulating etched frosted glass
            float grain = fract(sin(dot(floor(fragCoord), float2(12.9898, 78.233))) * 43758.5453) - 0.5;
            frosted += float3(grain * uNoiseAmount);

            // Crisp hairline shadow along the lower edge of the floating glass header
            float bottomEdge = smoothstep(res.y - 2.0, res.y, fragCoord.y);
            frosted = mix(frosted, float3(0.24, 0.22, 0.28), bottomEdge * 0.12);

            return half4(clamp(frosted, 0.0, 1.0), 1.0);
        }
    """.trimIndent()
}

private val HazeChromeBackground = Color(0xFFFAF8FB)
private val HazeInkColor = Color(0xFF1C1B1F)
private val HazeSubtleDivider = Color(0x181C1B1F)

private data class DropletSpec(
    val xFrac: Float,
    val yFrac: Float,
    val rxFrac: Float,
    val ryFrac: Float,
)

private val WaterDropletSpecs: List<DropletSpec> = listOf(
    DropletSpec(0.14f, 0.86f, 0.062f, 0.145f),
    DropletSpec(0.32f, 0.74f, 0.056f, 0.130f),
    DropletSpec(0.21f, 0.42f, 0.058f, 0.135f),
    DropletSpec(0.43f, 0.54f, 0.046f, 0.110f),
    DropletSpec(0.58f, 0.68f, 0.042f, 0.095f),
    DropletSpec(0.75f, 0.36f, 0.055f, 0.125f),
    DropletSpec(0.68f, 0.80f, 0.048f, 0.112f),
    DropletSpec(0.47f, 0.18f, 0.050f, 0.110f),
    DropletSpec(0.29f, 0.15f, 0.044f, 0.100f),
    DropletSpec(0.86f, 0.66f, 0.045f, 0.105f),
    DropletSpec(0.92f, 0.84f, 0.036f, 0.088f),
    DropletSpec(0.51f, 0.86f, 0.032f, 0.075f),
    DropletSpec(0.08f, 0.64f, 0.025f, 0.060f),
    DropletSpec(0.62f, 0.24f, 0.034f, 0.078f),
)

private data class CactusPadSpec(
    val cxFrac: Float,
    val bottomYFrac: Float,
    val wFrac: Float,
    val hFrac: Float,
)

private val CactusPadSpecs: List<CactusPadSpec> = listOf(
    CactusPadSpec(0.06f, 1.00f, 0.11f, 0.46f),
    CactusPadSpec(0.16f, 0.98f, 0.12f, 0.72f),
    CactusPadSpec(0.13f, 0.34f, 0.055f, 0.24f),
    CactusPadSpec(0.19f, 0.30f, 0.048f, 0.20f),
    CactusPadSpec(0.38f, 0.99f, 0.13f, 0.68f),
    CactusPadSpec(0.35f, 0.38f, 0.052f, 0.22f),
    CactusPadSpec(0.41f, 0.32f, 0.050f, 0.22f),
    CactusPadSpec(0.51f, 1.00f, 0.12f, 0.42f),
    CactusPadSpec(0.65f, 1.00f, 0.14f, 0.96f),
    CactusPadSpec(0.79f, 1.00f, 0.15f, 0.88f),
    CactusPadSpec(0.93f, 1.00f, 0.14f, 0.94f),
)

private data class GrapeSpec(
    val xFrac: Float,
    val yFrac: Float,
    val rFrac: Float,
)

private val GrapeClusterSpecs: List<GrapeSpec> = listOf(
    GrapeSpec(0.10f, 0.42f, 0.065f),
    GrapeSpec(0.20f, 0.24f, 0.068f),
    GrapeSpec(0.24f, 0.56f, 0.072f),
    GrapeSpec(0.34f, 0.36f, 0.076f),
    GrapeSpec(0.44f, 0.22f, 0.074f),
    GrapeSpec(0.49f, 0.54f, 0.082f),
    GrapeSpec(0.56f, 0.26f, 0.078f),
    GrapeSpec(0.61f, 0.62f, 0.080f),
    GrapeSpec(0.68f, 0.34f, 0.072f),
    GrapeSpec(0.75f, 0.58f, 0.068f),
    GrapeSpec(0.39f, 0.72f, 0.074f),
    GrapeSpec(0.52f, 0.82f, 0.070f),
    GrapeSpec(0.66f, 0.80f, 0.064f),
    GrapeSpec(0.16f, 0.76f, 0.060f),
)

/**
 * Specimen #3 (`shaders.hazeScrollHeader`, `periodMs = 8_000`, S07):
 * Live Compose scrollable card list passing behind a frosted-glass sticky top header using real
 * Skia `ImageFilter.makeBlur` + progressive frosting/grain `ImageFilter.makeRuntimeShader`
 * `RenderEffect`, auto-scrolling smoothly from [loopMillis] and supporting pointer drag override
 * when [isInteractive] is `true`.
 */
@Composable
fun HazeScrollHeaderSpecimen(
    loopMillis: Long,
    isInteractive: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val hazeHolder = rememberRuntimeRenderEffectHolder(
        sksl = HazeRenderEffectSksl.FrostedGlass,
        contentShaderName = "content",
    )
    var dragScrollOffsetPx by remember { mutableFloatStateOf(0f) }

    val interactiveModifier = if (isInteractive) {
        modifier
            .stageInteractive(ShadersSpecimenIds.HAZE_SCROLL_HEADER, "Haze Scroll Header")
            .pointerInput(Unit) {
                detectVerticalDragGestures { change, dragAmount ->
                    change.consume()
                    dragScrollOffsetPx -= dragAmount
                }
            }
    } else {
        modifier
    }

    BoxWithConstraints(
        modifier = interactiveModifier
            .background(HazeChromeBackground)
            .clipToBounds(),
    ) {
        val density = LocalDensity.current
        val viewportWidthPx = constraints.maxWidth.toFloat()
        val viewportHeightPx = constraints.maxHeight.toFloat()
        if (viewportWidthPx <= 0f || viewportHeightPx <= 0f) return@BoxWithConstraints

        val topBarHeightDp = (maxHeight * 0.145f).coerceIn(34.dp, 84.dp)
        val stickyHeaderHeightDp = (maxHeight * 0.140f).coerceIn(34.dp, 80.dp)
        val cardHeightDp = (maxHeight * 0.345f).coerceIn(88.dp, 240.dp)
        val inlineHeaderHeightDp = stickyHeaderHeightDp
        val totalFeedHeightDp = cardHeightDp * 6 + inlineHeaderHeightDp

        val topBarHeightPx = with(density) { topBarHeightDp.toPx() }
        val cardHeightPx = with(density) { cardHeightDp.toPx() }
        val totalFeedHeightPx = with(density) { totalFeedHeightDp.toPx() }
        val feedViewportHeightPx = (viewportHeightPx - topBarHeightPx).coerceAtLeast(1f)
        val maxScrollPx = (totalFeedHeightPx - feedViewportHeightPx).coerceAtLeast(1f)

        val phase = loopMillisToRadians(loopMillis, ShadersSpecimenIds.HAZE_SCROLL_HEADER_PERIOD_MS)
        // Smooth reversible scroll travel across cards 0..3 -> Header: 1 -> cards 4..5
        val autoFraction = (0.5f - 0.5f * cos(phase)).coerceIn(0f, 1f)
        val autoScrollPx = autoFraction * (maxScrollPx * 0.88f)
        val resolvedScrollPx = if (isInteractive) {
            (autoScrollPx + dragScrollOffsetPx).coerceIn(0f, maxScrollPx)
        } else {
            autoScrollPx.coerceIn(0f, maxScrollPx)
        }

        // Inline "Header: 1" sits directly after card 3 (4 cards * cardHeightPx)
        val header1ThresholdPx = cardHeightPx * 4f
        val activeHeaderLabel = if (resolvedScrollPx >= header1ThresholdPx - 12f) {
            "Header: 1"
        } else {
            "Header: 0"
        }

        val headerFontSize = (with(density) { stickyHeaderHeightDp.toPx() } * 0.31f / density.fontScale)
            .coerceIn(13f, 26f)
            .sp
        val horizontalPad = (maxWidth * 0.04f).coerceIn(12.dp, 28.dp)
        val blurSigmaPx = with(density) { 22.dp.toPx() }

        // 1. Live scrollable Compose card feed beneath the chrome (pinned strictly at TopStart)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = topBarHeightDp)
                .clipToBounds(),
            contentAlignment = Alignment.TopStart,
        ) {
            HazeCardFeed(
                cardHeight = cardHeightDp,
                inlineHeaderHeight = inlineHeaderHeightDp,
                totalHeight = totalFeedHeightDp,
                headerFontSizeSp = headerFontSize.value,
                horizontalPad = horizontalPad,
                scrollOffsetPx = resolvedScrollPx,
            )
        }

        // 2. Frosted-glass sticky header rendering the exact pixel-aligned live feed behind Skia Blur + SkSL Frost
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .offset(y = topBarHeightDp)
                .height(stickyHeaderHeightDp)
                .clipToBounds(),
            contentAlignment = Alignment.TopStart,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        clip = true
                        renderEffect = hazeHolder.obtainHazeEffect(
                            width = size.width,
                            height = size.height,
                            blurSigma = blurSigmaPx,
                            tintStrength = 0.54f,
                            noiseAmount = 0.032f,
                        )
                    },
                contentAlignment = Alignment.TopStart,
            ) {
                HazeCardFeed(
                    cardHeight = cardHeightDp,
                    inlineHeaderHeight = inlineHeaderHeightDp,
                    totalHeight = totalFeedHeightDp,
                    headerFontSizeSp = headerFontSize.value,
                    horizontalPad = horizontalPad,
                    scrollOffsetPx = resolvedScrollPx,
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = horizontalPad),
                contentAlignment = Alignment.CenterStart,
            ) {
                BasicText(
                    text = activeHeaderLabel,
                    style = TextStyle(
                        color = HazeInkColor,
                        fontSize = headerFontSize,
                        fontWeight = FontWeight.Normal,
                        fontFamily = FontFamily.SansSerif,
                    ),
                )
            }
        }

        // 3. Solid crisp Material top bar with left navigation back arrow
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(topBarHeightDp)
                .background(HazeChromeBackground)
                .padding(horizontal = horizontalPad),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val iconSize = (topBarHeightDp * 0.40f).coerceIn(18.dp, 30.dp)
            Canvas(modifier = Modifier.size(iconSize)) {
                val stroke = (size.minDimension * 0.11f).coerceAtLeast(1.8f)
                val cy = size.height * 0.5f
                val left = size.width * 0.16f
                val right = size.width * 0.84f
                val tipX = left + size.width * 0.28f
                val tipDy = size.height * 0.28f
                drawLine(
                    color = HazeInkColor,
                    start = Offset(right, cy),
                    end = Offset(left + stroke * 0.5f, cy),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = HazeInkColor,
                    start = Offset(tipX, cy - tipDy),
                    end = Offset(left, cy),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = HazeInkColor,
                    start = Offset(tipX, cy + tipDy),
                    end = Offset(left, cy),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round,
                )
            }
        }
    }
}

@Composable
private fun HazeCardFeed(
    cardHeight: Dp,
    inlineHeaderHeight: Dp,
    totalHeight: Dp,
    headerFontSizeSp: Float,
    horizontalPad: Dp,
    scrollOffsetPx: Float,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .wrapContentHeight(align = Alignment.Top, unbounded = true)
            .height(totalHeight)
            .graphicsLayer { translationY = -scrollOffsetPx },
    ) {
        HazeWaterDropletsCard(modifier = Modifier.fillMaxWidth().height(cardHeight).clipToBounds())
        HazeDesertCactiCard(modifier = Modifier.fillMaxWidth().height(cardHeight).clipToBounds())
        HazeIndigoGrapesCard(modifier = Modifier.fillMaxWidth().height(cardHeight).clipToBounds())
        HazeCrimsonTaillightsCard(modifier = Modifier.fillMaxWidth().height(cardHeight).clipToBounds())

        // Inline section divider ("Header: 1") matching S07's sticky header handoff
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(inlineHeaderHeight)
                .background(HazeChromeBackground)
                .padding(horizontal = horizontalPad),
            contentAlignment = Alignment.CenterStart,
        ) {
            BasicText(
                text = "Header: 1",
                style = TextStyle(
                    color = HazeInkColor,
                    fontSize = headerFontSizeSp.sp,
                    fontWeight = FontWeight.Normal,
                    fontFamily = FontFamily.SansSerif,
                ),
            )
        }

        HazeCoastalDuneGrassCard(modifier = Modifier.fillMaxWidth().height(cardHeight).clipToBounds())
        HazeTwilightRidgeCard(modifier = Modifier.fillMaxWidth().height(cardHeight).clipToBounds())
    }
}

@Composable
private fun HazeWaterDropletsCard(modifier: Modifier) {
    Box(
        modifier = modifier.drawWithCache {
            val woodBg = Brush.verticalGradient(
                colors = listOf(Color(0xFF7D655E), Color(0xFF6B504B), Color(0xFF564241)),
            )
            val topFade = Brush.horizontalGradient(
                colors = listOf(Color(0x33221512), Color(0x22997B6C), Color(0x3334515C)),
            )
            val dropletHighlight = Color(0xF2E8FAFF)
            val dropletTeal = Color(0xFF3E8E9E)
            val dropletMid = Color(0xFF23667A)
            val dropletShadow = Color(0xCC0A1820)
            onDrawBehind {
                drawRect(brush = woodBg)
                drawRect(brush = topFade)
                for (d in WaterDropletSpecs) {
                    val cx = size.width * d.xFrac
                    val cy = size.height * d.yFrac
                    val rx = size.width * d.rxFrac
                    val ry = size.height * d.ryFrac
                    // Cast shadow underneath water bead
                    drawOval(
                        color = dropletShadow,
                        topLeft = Offset(cx - rx * 1.06f, cy - ry * 0.80f),
                        size = Size(rx * 2.12f, ry * 1.98f),
                    )
                    // Deep teal meniscus body
                    drawOval(
                        color = dropletMid,
                        topLeft = Offset(cx - rx, cy - ry),
                        size = Size(rx * 2f, ry * 1.85f),
                    )
                    // Luminous internal turquoise caustic pool
                    drawOval(
                        color = dropletTeal,
                        topLeft = Offset(cx - rx * 0.78f, cy - ry * 0.62f),
                        size = Size(rx * 1.56f, ry * 1.24f),
                    )
                    // Crisp specular catchlight dot
                    drawOval(
                        color = dropletHighlight,
                        topLeft = Offset(cx - rx * 0.56f, cy - ry * 0.68f),
                        size = Size(rx * 0.52f, ry * 0.36f),
                    )
                }
                drawLine(
                    color = HazeSubtleDivider,
                    start = Offset(0f, size.height - 1f),
                    end = Offset(size.width, size.height - 1f),
                    strokeWidth = 1f,
                )
            }
        },
    )
}

@Composable
private fun HazeDesertCactiCard(modifier: Modifier) {
    Box(
        modifier = modifier.drawWithCache {
            val sunsetBackdrop = Brush.linearGradient(
                colors = listOf(Color(0xFFC87D55), Color(0xFFE9A958), Color(0xFFF6CF76), Color(0xFFBC7B3E)),
                start = Offset(0f, 0f),
                end = Offset(size.width, size.height),
            )
            val rimGlow = Color(0xFFFBE196)
            val spineGold = Color(0xFFD7A048)
            val padCore = Color(0xFF7B572E)
            val padDeep = Color(0xFF5D4120)
            onDrawBehind {
                drawRect(brush = sunsetBackdrop)
                // Soft warm bokeh discs in background
                drawCircle(
                    color = Color(0x55FFF1B8),
                    radius = size.height * 0.42f,
                    center = Offset(size.width * 0.55f, size.height * 0.25f),
                )
                drawCircle(
                    color = Color(0x44FFCE75),
                    radius = size.height * 0.36f,
                    center = Offset(size.width * 0.24f, size.height * 0.32f),
                )
                for (pad in CactusPadSpecs) {
                    val w = size.width * pad.wFrac
                    val h = size.height * pad.hFrac
                    val left = size.width * pad.cxFrac - w * 0.5f
                    val top = size.height * pad.bottomYFrac - h
                    val r = CornerRadius(w * 0.48f, w * 0.48f)
                    // Golden sunlit rim halo around cactus bristles
                    drawRoundRect(
                        color = rimGlow,
                        topLeft = Offset(left - 5f, top - 5f),
                        size = Size(w + 10f, h + 10f),
                        cornerRadius = r,
                    )
                    drawRoundRect(
                        color = spineGold,
                        topLeft = Offset(left - 2f, top - 2f),
                        size = Size(w + 4f, h + 4f),
                        cornerRadius = r,
                    )
                    drawRoundRect(
                        color = padCore,
                        topLeft = Offset(left + 2f, top + 3f),
                        size = Size((w - 4f).coerceAtLeast(2f), (h - 3f).coerceAtLeast(2f)),
                        cornerRadius = r,
                    )
                    drawRoundRect(
                        color = padDeep,
                        topLeft = Offset(left + w * 0.22f, top + h * 0.14f),
                        size = Size(w * 0.52f, h * 0.78f),
                        cornerRadius = r,
                    )
                }
            }
        },
    )
}

@Composable
private fun HazeIndigoGrapesCard(modifier: Modifier) {
    Box(
        modifier = modifier.drawWithCache {
            val foliageBg = Brush.horizontalGradient(
                colors = listOf(Color(0xFF1F2B24), Color(0xFF283A3E), Color(0xFFCBD4A8), Color(0xFFF3EED5)),
            )
            val berryDark = Color(0xFF23303B)
            val berryMid = Color(0xFF476072)
            val berryBloom = Color(0xFF8AA4B5)
            val stemRed = Color(0xFF9C352E)
            onDrawBehind {
                drawRect(brush = foliageBg)
                drawLine(
                    color = stemRed,
                    start = Offset(size.width * 0.04f, size.height * 0.08f),
                    end = Offset(size.width * 0.28f, size.height * 0.96f),
                    strokeWidth = (size.height * 0.045f).coerceAtLeast(3f),
                    cap = StrokeCap.Round,
                )
                for (g in GrapeClusterSpecs) {
                    val cx = size.width * g.xFrac
                    val cy = size.height * g.yFrac
                    val r = size.height * g.rFrac * 1.45f
                    drawCircle(color = berryDark, radius = r * 1.04f, center = Offset(cx + 2f, cy + 3f))
                    drawCircle(color = berryMid, radius = r, center = Offset(cx, cy))
                    drawCircle(
                        color = berryBloom,
                        radius = r * 0.56f,
                        center = Offset(cx - r * 0.22f, cy - r * 0.22f),
                    )
                }
            }
        },
    )
}

@Composable
private fun HazeCrimsonTaillightsCard(modifier: Modifier) {
    Box(
        modifier = modifier.drawWithCache {
            val studioBg = Brush.verticalGradient(
                colors = listOf(Color(0xFF6A6968), Color(0xFF958E84), Color(0xFF535C63)),
            )
            val taillightGlow = Brush.radialGradient(
                colors = listOf(Color(0xE6FF2222), Color(0x66FF1818), Color(0x00FF0000)),
                center = Offset(size.width * 0.57f, size.height * 0.84f),
                radius = size.height * 0.42f,
            )
            val carSilhouette = Color(0xFF1A2229)
            val tireBlack = Color(0xFF11181E)
            val brakeCore = Color(0xFFFF4048)
            onDrawBehind {
                drawRect(brush = studioBg)
                // Ground shadow pool beneath chassis
                drawOval(
                    color = Color(0xAA141E28),
                    topLeft = Offset(size.width * 0.14f, size.height * 0.52f),
                    size = Size(size.width * 0.72f, size.height * 0.42f),
                )
                // Wet floor reflection of glowing crimson brake light
                drawRect(brush = taillightGlow)
                // Rear tires
                drawRoundRect(
                    color = tireBlack,
                    topLeft = Offset(size.width * 0.22f, size.height * 0.34f),
                    size = Size(size.width * 0.11f, size.height * 0.42f),
                    cornerRadius = CornerRadius(12f, 12f),
                )
                drawRoundRect(
                    color = tireBlack,
                    topLeft = Offset(size.width * 0.67f, size.height * 0.34f),
                    size = Size(size.width * 0.11f, size.height * 0.42f),
                    cornerRadius = CornerRadius(12f, 12f),
                )
                // Sleek dark chassis body
                drawRoundRect(
                    color = carSilhouette,
                    topLeft = Offset(size.width * 0.17f, -size.height * 0.12f),
                    size = Size(size.width * 0.66f, size.height * 0.62f),
                    cornerRadius = CornerRadius(size.height * 0.25f, size.height * 0.25f),
                )
                // Glowing red LED taillight dot
                drawCircle(
                    color = brakeCore,
                    radius = size.height * 0.06f,
                    center = Offset(size.width * 0.57f, size.height * 0.06f),
                )
                drawCircle(
                    color = Color.White,
                    radius = size.height * 0.025f,
                    center = Offset(size.width * 0.57f, size.height * 0.06f),
                )
            }
        },
    )
}

@Composable
private fun HazeCoastalDuneGrassCard(modifier: Modifier) {
    Box(
        modifier = modifier.drawWithCache {
            val skyBrush = Brush.verticalGradient(
                colors = listOf(Color(0xFFF3F6F6), Color(0xFFE7ECEB), Color(0xFFDDE3E1)),
                startY = 0f,
                endY = size.height * 0.74f,
            )
            val seaBrush = Brush.verticalGradient(
                colors = listOf(Color(0xFF939691), Color(0xFFB5B4AC), Color(0xFF7E7F78)),
                startY = size.height * 0.70f,
                endY = size.height,
            )
            val grassLight = Color(0xFFC3A569)
            val grassDark = Color(0xFF7D633A)
            onDrawBehind {
                drawRect(brush = skyBrush, size = Size(size.width, size.height * 0.74f))
                drawRect(
                    brush = seaBrush,
                    topLeft = Offset(0f, size.height * 0.70f),
                    size = Size(size.width, size.height * 0.30f),
                )
                // Sea foam breaker line
                drawLine(
                    color = Color(0xCCF4F5F2),
                    start = Offset(0f, size.height * 0.84f),
                    end = Offset(size.width, size.height * 0.84f),
                    strokeWidth = (size.height * 0.025f).coerceAtLeast(2f),
                )
                // Sweeping golden coastal dune grass stalks along right foreground
                for (i in 0 until 24) {
                    val f = i / 23f
                    val baseX = size.width * (0.58f + f * 0.44f)
                    val sway = sin(i * 1.3f) * size.width * 0.04f
                    val tipX = baseX + size.width * (0.06f + 0.04f * f) + sway
                    val tipY = size.height * (0.64f - (i % 5) * 0.07f)
                    drawLine(
                        color = if (i % 2 == 0) grassLight else grassDark,
                        start = Offset(baseX, size.height),
                        end = Offset(tipX, tipY),
                        strokeWidth = (size.width * 0.0045f).coerceAtLeast(1.5f),
                        cap = StrokeCap.Round,
                    )
                }
            }
        },
    )
}

@Composable
private fun HazeTwilightRidgeCard(modifier: Modifier) {
    Box(
        modifier = modifier.drawWithCache {
            val duskSky = Brush.verticalGradient(
                colors = listOf(Color(0xFF2A315A), Color(0xFF6B4E71), Color(0xFFD9875B), Color(0xFFF3C677)),
            )
            val ridgeFar = Color(0xFF3E3454)
            val ridgeNear = Color(0xFF1D1E34)
            onDrawBehind {
                drawRect(brush = duskSky)
                // Warm twilight sun disc
                drawCircle(
                    color = Color(0x99FFE7A8),
                    radius = size.height * 0.24f,
                    center = Offset(size.width * 0.68f, size.height * 0.42f),
                )
                // Far indigo mountain band
                drawOval(
                    color = ridgeFar,
                    topLeft = Offset(-size.width * 0.12f, size.height * 0.52f),
                    size = Size(size.width * 0.78f, size.height * 0.85f),
                )
                drawOval(
                    color = ridgeFar,
                    topLeft = Offset(size.width * 0.42f, size.height * 0.46f),
                    size = Size(size.width * 0.76f, size.height * 0.90f),
                )
                // Near silhouette ridge
                drawOval(
                    color = ridgeNear,
                    topLeft = Offset(size.width * 0.14f, size.height * 0.66f),
                    size = Size(size.width * 0.96f, size.height * 0.82f),
                )
            }
        },
    )
}
