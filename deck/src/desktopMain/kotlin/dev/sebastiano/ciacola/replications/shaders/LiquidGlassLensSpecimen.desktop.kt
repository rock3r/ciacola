package dev.sebastiano.ciacola.replications.shaders

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sebastiano.ciacola.inspector.stageInteractive
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

internal object LiquidGlassRenderEffectSksl {
    /**
     * Optical refractive liquid-glass magnifier pill `RuntimeShader` `ImageFilter` for Specimen #4
     * (`shaders.liquidGlassLens`, S08): signed-distance rounded pill bevel refraction, RGB
     * chromatic dispersion, Fresnel specular rim, and soft contact shadow over live Compose UI.
     */
    val RefractivePill: String = """
        uniform shader content;
        uniform float2 iResolution;
        uniform float2 uLensCenter;
        uniform float2 uLensHalfSize;
        uniform float uCornerRadius;
        uniform float uTime;

        float sdRoundedBox(float2 p, float2 b, float r) {
            float2 q = abs(p) - b + float2(r);
            return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
        }

        half4 main(float2 fragCoord) {
            float2 res = max(iResolution, float2(1.0));
            float2 maxCoord = max(res - float2(0.5), float2(0.5));
            float2 p = fragCoord - uLensCenter;
            float r = clamp(uCornerRadius, 1.0, min(uLensHalfSize.x, uLensHalfSize.y));
            float d = sdRoundedBox(p, uLensHalfSize, r);

            // Base unrefracted UI pixel
            half4 baseColor = content.eval(clamp(fragCoord, float2(0.5), maxCoord));

            // Soft drop shadow cast slightly below the floating glass lens
            float dShadow = sdRoundedBox(p - float2(0.0, 8.0), uLensHalfSize * 1.015, r);
            float shadowAlpha = smoothstep(28.0, 0.0, max(dShadow, 0.0)) * 0.28;
            float3 outsideRgb = float3(baseColor.rgb) * (1.0 - shadowAlpha);

            // Fast path well outside the pill boundary
            if (d > 2.0) {
                return half4(clamp(outsideRgb, 0.0, 1.0), 1.0);
            }

            // Smooth outward SDF normal via symmetric central differences
            float eps = 1.25;
            float dx = sdRoundedBox(p + float2(eps, 0.0), uLensHalfSize, r)
                     - sdRoundedBox(p - float2(eps, 0.0), uLensHalfSize, r);
            float dy = sdRoundedBox(p + float2(0.0, eps), uLensHalfSize, r)
                     - sdRoundedBox(p - float2(0.0, eps), uLensHalfSize, r);
            float2 normal = normalize(float2(dx, dy) + float2(1e-5, 0.0));

            // Curved beveled glass thickness profile: steep refraction at rim, gentle zoom inside
            float minHalf = max(min(uLensHalfSize.x, uLensHalfSize.y), 1.0);
            float bevel = minHalf * 0.68;
            float insideDepth = clamp(-d / max(bevel, 1.0), 0.0, 1.0);
            float rimCurve = pow(1.0 - insideDepth, 2.15);

            // Mild magnification in the core + pronounced edge-bevel liquid refraction
            float2 magOffset = -p * (0.15 * (1.0 - rimCurve * 0.45));
            float2 edgeBend = -normal * (rimCurve * bevel * 0.62);
            float2 refractOffset = magOffset + edgeBend;

            // Chromatic dispersion: R/G/B split subtly along outward optical normal near glass edge
            float dispPixels = rimCurve * 5.2 + 0.6;
            float2 coordR = clamp(fragCoord + refractOffset * (1.0 + 0.16 * rimCurve) + normal * dispPixels, float2(0.5), maxCoord);
            float2 coordG = clamp(fragCoord + refractOffset, float2(0.5), maxCoord);
            float2 coordB = clamp(fragCoord + refractOffset * (1.0 - 0.16 * rimCurve) - normal * dispPixels, float2(0.5), maxCoord);

            // Gentle 3-tap optical smoothing blended lightly on the curved bevel for liquid clarity
            float2 tanDir = float2(-normal.y, normal.x) * (1.6 * rimCurve);
            float rChan = (content.eval(coordR).r * 0.60
                + content.eval(clamp(coordR + tanDir, float2(0.5), maxCoord)).r * 0.20
                + content.eval(clamp(coordR - tanDir, float2(0.5), maxCoord)).r * 0.20);
            float gChan = (content.eval(coordG).g * 0.60
                + content.eval(clamp(coordG + tanDir, float2(0.5), maxCoord)).g * 0.20
                + content.eval(clamp(coordG - tanDir, float2(0.5), maxCoord)).g * 0.20);
            float bChan = (content.eval(coordB).b * 0.60
                + content.eval(clamp(coordB + tanDir, float2(0.5), maxCoord)).b * 0.20
                + content.eval(clamp(coordB - tanDir, float2(0.5), maxCoord)).b * 0.20);

            float3 glassRgb = float3(rChan, gChan, bChan);

            // Subtle optical glass clarity tint + vibrant saturation boost inside lens
            float luma = dot(glassRgb, float3(0.299, 0.587, 0.114));
            glassRgb = mix(float3(luma), glassRgb, 1.14);
            glassRgb = glassRgb * 1.05 + float3(0.035, 0.055, 0.080) * (0.28 + 0.72 * rimCurve);

            // Opposing directional Fresnel specular rim lights subtly animated with uTime
            float2 lightA = normalize(float2(-0.68 + 0.14 * cos(uTime), -0.73 + 0.10 * sin(uTime)));
            float2 lightB = -lightA;
            float rimZone = smoothstep(bevel * 0.42, 0.0, -d);
            float specA = pow(max(dot(normal, lightA), 0.0), 2.8) * rimZone;
            float specB = pow(max(dot(normal, lightB), 0.0), 3.6) * rimZone;

            // Crisp razor-sharp reflective rim border & prismatic edge caustics
            float edgeLip = exp(-pow((-d - 1.35) / 1.9, 2.0));
            float lipMod = 0.38 + 0.62 * pow(abs(dot(normal, lightA)), 1.4);

            // Upper-surface curved glossy dome reflection
            float topGloss = smoothstep(0.25, -0.95, p.y / max(uLensHalfSize.y, 1.0))
                * smoothstep(1.0, 0.15, insideDepth) * 0.15;

            glassRgb += float3(1.0, 0.99, 0.97) * (specA * 0.60 + edgeLip * lipMod * 0.90 + topGloss);
            glassRgb += float3(0.70, 0.92, 1.00) * (specB * 0.36);
            glassRgb += float3(1.00, 0.48, 0.80) * (edgeLip * 0.16 * max(normal.x, 0.0));
            glassRgb += float3(0.42, 0.90, 1.00) * (edgeLip * 0.16 * max(-normal.x, 0.0));

            float lensMask = smoothstep(1.25, -1.25, d);
            float3 finalRgb = mix(outsideRgb, glassRgb, lensMask);
            return half4(clamp(finalRgb, 0.0, 1.0), 1.0);
        }
    """.trimIndent()
}

private enum class SpringboardIconKind {
    FACETIME,
    CALENDAR,
    PHOTOS,
    CAMERA,
    MAIL,
    NOTES,
    REMINDERS,
    CLOCK,
    NEWS,
    TV,
    GAMES,
    APP_STORE,
    MAPS,
    HEALTH,
    WALLET,
    SETTINGS,
}

private data class SpringboardAppSpec(
    val label: String,
    val kind: SpringboardIconKind,
)

private val SpringboardApps: List<SpringboardAppSpec> = listOf(
    SpringboardAppSpec("FaceTime", SpringboardIconKind.FACETIME),
    SpringboardAppSpec("Calendar", SpringboardIconKind.CALENDAR),
    SpringboardAppSpec("Photos", SpringboardIconKind.PHOTOS),
    SpringboardAppSpec("Camera", SpringboardIconKind.CAMERA),
    SpringboardAppSpec("Mail", SpringboardIconKind.MAIL),
    SpringboardAppSpec("Notes", SpringboardIconKind.NOTES),
    SpringboardAppSpec("Reminders", SpringboardIconKind.REMINDERS),
    SpringboardAppSpec("Clock", SpringboardIconKind.CLOCK),
    SpringboardAppSpec("News", SpringboardIconKind.NEWS),
    SpringboardAppSpec("TV", SpringboardIconKind.TV),
    SpringboardAppSpec("Games", SpringboardIconKind.GAMES),
    SpringboardAppSpec("App Store", SpringboardIconKind.APP_STORE),
    SpringboardAppSpec("Maps", SpringboardIconKind.MAPS),
    SpringboardAppSpec("Health", SpringboardIconKind.HEALTH),
    SpringboardAppSpec("Wallet", SpringboardIconKind.WALLET),
    SpringboardAppSpec("Settings", SpringboardIconKind.SETTINGS),
)

private val PhotosPetalColors: List<Color> = listOf(
    Color(0xFFFF9F0A),
    Color(0xFFFFD60A),
    Color(0xFF34C759),
    Color(0xFF30B0C7),
    Color(0xFF0A84FF),
    Color(0xFF5E5CE6),
    Color(0xFFBF5AF2),
    Color(0xFFFF375F),
)

private val ReminderDotColors: List<Color> = listOf(
    Color(0xFF0A84FF),
    Color(0xFFFF3B30),
    Color(0xFFFF9500),
)

private val WalletPassColors: List<Color> = listOf(
    Color(0xFF29B6F6),
    Color(0xFFFFCA28),
    Color(0xFF66BB6A),
    Color(0xFFFF5252),
)

/**
 * Specimen #4 (`shaders.liquidGlassLens`, `periodMs = 8_000`, S08):
 * Live Skia `ImageFilter.makeRuntimeShader` refractive liquid-glass magnifier pill gliding
 * smoothly along a figure-8 path from [loopMillis] (plus pointer drag override when
 * [isInteractive] is `true`) across vibrant Compose UI cards with chromatic dispersion and a
 * Fresnel specular rim.
 */
@Composable
fun LiquidGlassLensSpecimen(
    loopMillis: Long,
    isInteractive: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val glassHolder = rememberRuntimeRenderEffectHolder(
        sksl = LiquidGlassRenderEffectSksl.RefractivePill,
        contentShaderName = "content",
    )
    var hasManualDrag by remember { mutableStateOf(false) }
    var manualNormX by remember { mutableFloatStateOf(0.50f) }
    var manualNormY by remember { mutableFloatStateOf(0.46f) }

    val theta = loopMillisToRadians(loopMillis, ShadersSpecimenIds.LIQUID_GLASS_LENS_PERIOD_MS)
    val autoNormX = (0.50f + 0.26f * sin(theta)).coerceIn(0.16f, 0.84f)
    val autoNormY = (0.46f + 0.22f * sin(2f * theta)).coerceIn(0.16f, 0.84f)

    val normX = if (isInteractive && hasManualDrag) manualNormX else autoNormX
    val normY = if (isInteractive && hasManualDrag) manualNormY else autoNormY

    val interactiveModifier = if (isInteractive) {
        modifier
            .stageInteractive(ShadersSpecimenIds.LIQUID_GLASS_LENS, "Liquid Glass Lens")
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { startOffset ->
                        if (size.width > 0 && size.height > 0) {
                            hasManualDrag = true
                            manualNormX = (startOffset.x / size.width.toFloat()).coerceIn(0.16f, 0.84f)
                            manualNormY = (startOffset.y / size.height.toFloat()).coerceIn(0.16f, 0.84f)
                        }
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        if (size.width > 0 && size.height > 0) {
                            hasManualDrag = true
                            manualNormX = (manualNormX + dragAmount.x / size.width.toFloat()).coerceIn(0.16f, 0.84f)
                            manualNormY = (manualNormY + dragAmount.y / size.height.toFloat()).coerceIn(0.16f, 0.84f)
                        }
                    },
                )
            }
    } else {
        modifier
    }

    BoxWithConstraints(
        modifier = interactiveModifier.clipToBounds(),
    ) {
        val viewportW = constraints.maxWidth.toFloat()
        val viewportH = constraints.maxHeight.toFloat()
        if (viewportW <= 0f || viewportH <= 0f) return@BoxWithConstraints

        val containerHeight = maxHeight
        val isLandscape = maxWidth > containerHeight * 1.05f
        val columns = 4
        val displayedApps = if (isLandscape) SpringboardApps.take(12) else SpringboardApps
        val rows = (displayedApps.size + columns - 1) / columns
        val horizontalPad = (maxWidth * 0.055f).coerceIn(12.dp, 36.dp)
        val verticalPad = (containerHeight * 0.025f).coerceIn(8.dp, 22.dp)
        val statusBarHeight = (containerHeight * 0.055f).coerceIn(18.dp, 34.dp)
        val gridVerticalPad = (containerHeight * 0.02f).coerceIn(6.dp, 18.dp)
        val dockHeight = (containerHeight * 0.155f).coerceIn(52.dp, 98.dp)
        val tileGap = (min(maxWidth.value, containerHeight.value) * 0.028f).coerceIn(8f, 20f).dp
        val labelFontSize = (min(maxWidth.value, containerHeight.value) * 0.022f).coerceIn(9f, 13f).sp

        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    clip = true
                    val minDim = min(size.width, size.height)
                    val halfW = (minDim * 0.26f).coerceIn(42f, 175f)
                    val halfH = (minDim * 0.145f).coerceIn(28f, 102f)
                    val cornerR = halfH * 0.86f
                    renderEffect = glassHolder.obtainLiquidGlassEffect(
                        width = size.width,
                        height = size.height,
                        centerX = size.width * normX,
                        centerY = size.height * normY,
                        halfWidth = halfW,
                        halfHeight = halfH,
                        cornerRadius = cornerR,
                        timeRad = theta,
                    )
                },
        ) {
            // 1. Teal-aqua-to-deep-cobalt abstract wave wallpaper
            LiquidGlassWallpaperBackdrop(modifier = Modifier.fillMaxSize())

            // 2. Live Compose phone springboard UI cards + bottom dock
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = horizontalPad, vertical = verticalPad),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                // Top iOS-style status bar
                SpringboardStatusBar(
                    modifier = Modifier.fillMaxWidth().height(statusBarHeight),
                )

                // Vibrant Compose app card grid
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(vertical = gridVerticalPad),
                    verticalArrangement = Arrangement.SpaceEvenly,
                ) {
                    for (r in 0 until rows) {
                        Row(
                            modifier = Modifier.fillMaxWidth().weight(1f),
                            horizontalArrangement = Arrangement.spacedBy(tileGap),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            for (c in 0 until columns) {
                                val index = r * columns + c
                                if (index < displayedApps.size) {
                                    SpringboardAppTile(
                                        spec = displayedApps[index],
                                        labelFontSizeSp = labelFontSize.value,
                                        modifier = Modifier.weight(1f).fillMaxHeight(),
                                    )
                                } else {
                                    Spacer(modifier = Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }

                // Bottom frosted dock with vibrant "Pick an image" pill matching S08
                SpringboardDock(
                    height = dockHeight,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun LiquidGlassWallpaperBackdrop(modifier: Modifier) {
    Box(
        modifier = modifier.drawWithCache {
            val baseSky = Brush.linearGradient(
                colors = listOf(
                    Color(0xFF9CD4DA),
                    Color(0xFF5FAEC1),
                    Color(0xFF2F84A6),
                    Color(0xFF0A4766),
                ),
                start = Offset(0f, 0f),
                end = Offset(size.width * 0.8f, size.height),
            )
            val cobaltSwirl = Brush.radialGradient(
                colors = listOf(Color(0xFF0D2E7E), Color(0xFF19429B), Color(0x0012337A)),
                center = Offset(size.width * 0.80f, size.height * 0.24f),
                radius = size.maxDimension * 0.54f,
            )
            val mintRibbon = Brush.radialGradient(
                colors = listOf(Color(0xDD96E4D9), Color(0x995DC5C2), Color(0x002B8A9C)),
                center = Offset(size.width * 0.26f, size.height * 0.64f),
                radius = size.maxDimension * 0.48f,
            )
            val deepTealBottom = Brush.verticalGradient(
                colors = listOf(Color(0x0007334D), Color(0xBB083B5E), Color(0xEE18568C)),
                startY = size.height * 0.65f,
                endY = size.height,
            )
            onDrawBehind {
                drawRect(brush = baseSky)
                drawRect(brush = cobaltSwirl)
                drawRect(brush = mintRibbon)
                // Soft sculptural light fold curve across upper right
                drawOval(
                    color = Color(0x36FFFFFF),
                    topLeft = Offset(size.width * 0.46f, -size.height * 0.08f),
                    size = Size(size.width * 0.72f, size.height * 0.56f),
                )
                drawRect(brush = deepTealBottom)
            }
        },
    )
}

@Composable
private fun SpringboardStatusBar(modifier: Modifier) {
    Row(
        modifier = modifier.padding(horizontal = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(
            text = "9:41",
            style = TextStyle(
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.SansSerif,
            ),
        )
        Box(
            modifier = Modifier
                .width(64.dp)
                .height(14.dp)
                .drawWithCache {
                    val ink = Color.White
                    val dimInk = Color(0x66FFFFFF)
                    onDrawBehind {
                        // Signal bars
                        val barW = size.width * 0.06f
                        for (i in 0 until 4) {
                            val h = size.height * (0.32f + i * 0.19f)
                            drawRoundRect(
                                color = ink,
                                topLeft = Offset(i * (barW * 1.65f), size.height - h),
                                size = Size(barW, h),
                                cornerRadius = CornerRadius(1.5f, 1.5f),
                            )
                        }
                        // Battery body + cap
                        val batLeft = size.width * 0.52f
                        val batW = size.width * 0.40f
                        val batH = size.height * 0.80f
                        val batTop = (size.height - batH) * 0.5f
                        drawRoundRect(
                            color = dimInk,
                            topLeft = Offset(batLeft, batTop),
                            size = Size(batW, batH),
                            cornerRadius = CornerRadius(3f, 3f),
                            style = Stroke(width = 1.5f),
                        )
                        drawRoundRect(
                            color = ink,
                            topLeft = Offset(batLeft + 2.5f, batTop + 2.5f),
                            size = Size((batW - 5f).coerceAtLeast(1f), (batH - 5f).coerceAtLeast(1f)),
                            cornerRadius = CornerRadius(2f, 2f),
                        )
                        drawRoundRect(
                            color = dimInk,
                            topLeft = Offset(batLeft + batW + 1.5f, size.height * 0.34f),
                            size = Size(2.5f, size.height * 0.32f),
                            cornerRadius = CornerRadius(1f, 1f),
                        )
                    }
                },
        )
    }
}

@Composable
private fun SpringboardAppTile(
    spec: SpringboardAppSpec,
    labelFontSizeSp: Float,
    modifier: Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .weight(1f, fill = false)
                .aspectRatio(1f)
                .clip(RoundedCornerShape(22)),
            contentAlignment = Alignment.Center,
        ) {
            SpringboardIconArtwork(kind = spec.kind, modifier = Modifier.fillMaxSize())
            if (spec.kind == SpringboardIconKind.CALENDAR) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    BasicText(
                        text = "Tue",
                        style = TextStyle(
                            color = Color(0xFFE53935),
                            fontSize = (labelFontSizeSp * 1.05f).sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.SansSerif,
                        ),
                    )
                    BasicText(
                        text = "1",
                        style = TextStyle(
                            color = Color(0xFF1A1C1E),
                            fontSize = (labelFontSizeSp * 2.25f).sp,
                            fontWeight = FontWeight.Medium,
                            fontFamily = FontFamily.SansSerif,
                        ),
                    )
                }
            } else if (spec.kind == SpringboardIconKind.TV) {
                BasicText(
                    text = "tv",
                    style = TextStyle(
                        color = Color.White,
                        fontSize = (labelFontSizeSp * 1.85f).sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.SansSerif,
                    ),
                )
            }
        }
        Spacer(modifier = Modifier.height(3.dp))
        BasicText(
            text = spec.label,
            style = TextStyle(
                color = Color(0xF0FFFFFF),
                fontSize = labelFontSizeSp.sp,
                fontWeight = FontWeight.Medium,
                fontFamily = FontFamily.SansSerif,
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SpringboardIconArtwork(
    kind: SpringboardIconKind,
    modifier: Modifier,
) {
    Box(
        modifier = modifier.drawWithCache {
            val w = size.width
            val h = size.height
            val bgBrush = when (kind) {
                SpringboardIconKind.FACETIME -> Brush.verticalGradient(listOf(Color(0xFF4BE065), Color(0xFF1BB33B)))
                SpringboardIconKind.CALENDAR,
                SpringboardIconKind.PHOTOS,
                SpringboardIconKind.REMINDERS,
                SpringboardIconKind.NEWS,
                SpringboardIconKind.HEALTH,
                -> Brush.verticalGradient(listOf(Color(0xFFFFFFFF), Color(0xFFEBEEF2)))
                SpringboardIconKind.CAMERA -> Brush.verticalGradient(listOf(Color(0xFFE3E5E9), Color(0xFFAAB0B9)))
                SpringboardIconKind.MAIL -> Brush.verticalGradient(listOf(Color(0xFF39A7FF), Color(0xFF1464F4)))
                SpringboardIconKind.NOTES -> Brush.verticalGradient(listOf(Color(0xFFFFFFFF), Color(0xFFF3F1EA)))
                SpringboardIconKind.CLOCK -> Brush.verticalGradient(listOf(Color(0xFF2B2C30), Color(0xFF141518)))
                SpringboardIconKind.TV -> Brush.verticalGradient(listOf(Color(0xFF282B31), Color(0xFF0E0F12)))
                SpringboardIconKind.GAMES -> Brush.verticalGradient(listOf(Color(0xFFFF5938), Color(0xFFE01F26)))
                SpringboardIconKind.APP_STORE -> Brush.verticalGradient(listOf(Color(0xFF23AEFF), Color(0xFF145CE0)))
                SpringboardIconKind.MAPS -> Brush.linearGradient(listOf(Color(0xFF8BE280), Color(0xFFF6F6F2), Color(0xFFFFD84A)))
                SpringboardIconKind.WALLET -> Brush.verticalGradient(listOf(Color(0xFF25282E), Color(0xFF121417)))
                SpringboardIconKind.SETTINGS -> Brush.verticalGradient(listOf(Color(0xFFBDC2C9), Color(0xFF7B818A)))
            }
            val gearStroke = Stroke(width = (w * 0.08f).coerceAtLeast(2f))
            onDrawBehind {
                drawRect(brush = bgBrush)
                when (kind) {
                    SpringboardIconKind.FACETIME -> {
                        drawRoundRect(
                            color = Color.White,
                            topLeft = Offset(w * 0.16f, h * 0.28f),
                            size = Size(w * 0.48f, h * 0.44f),
                            cornerRadius = CornerRadius(w * 0.09f, w * 0.09f),
                        )
                        drawRoundRect(
                            color = Color.White,
                            topLeft = Offset(w * 0.67f, h * 0.34f),
                            size = Size(w * 0.18f, h * 0.32f),
                            cornerRadius = CornerRadius(w * 0.05f, w * 0.05f),
                        )
                    }
                    SpringboardIconKind.PHOTOS -> {
                        val cx = w * 0.5f
                        val cy = h * 0.5f
                        val dist = w * 0.20f
                        val r = w * 0.145f
                        for (i in 0 until 8) {
                            val ang = (i * PI / 4.0).toFloat()
                            drawCircle(
                                color = PhotosPetalColors[i].copy(alpha = 0.84f),
                                radius = r,
                                center = Offset(cx + cos(ang) * dist, cy + sin(ang) * dist),
                            )
                        }
                    }
                    SpringboardIconKind.CAMERA -> {
                        drawCircle(color = Color(0xFF23272D), radius = w * 0.38f, center = Offset(w * 0.5f, h * 0.5f))
                        drawCircle(color = Color(0xFF111C38), radius = w * 0.24f, center = Offset(w * 0.5f, h * 0.5f))
                        drawCircle(color = Color(0xFF3E8BE8), radius = w * 0.12f, center = Offset(w * 0.46f, h * 0.46f))
                        drawCircle(color = Color(0xD8FFFFFF), radius = w * 0.05f, center = Offset(w * 0.40f, h * 0.40f))
                    }
                    SpringboardIconKind.MAIL -> {
                        drawRoundRect(
                            color = Color.White,
                            topLeft = Offset(w * 0.14f, h * 0.25f),
                            size = Size(w * 0.72f, h * 0.50f),
                            cornerRadius = CornerRadius(w * 0.07f, w * 0.07f),
                        )
                        drawLine(
                            color = Color(0xFF217DF8),
                            start = Offset(w * 0.17f, h * 0.29f),
                            end = Offset(w * 0.50f, h * 0.54f),
                            strokeWidth = (w * 0.045f).coerceAtLeast(1.5f),
                            cap = StrokeCap.Round,
                        )
                        drawLine(
                            color = Color(0xFF217DF8),
                            start = Offset(w * 0.83f, h * 0.29f),
                            end = Offset(w * 0.50f, h * 0.54f),
                            strokeWidth = (w * 0.045f).coerceAtLeast(1.5f),
                            cap = StrokeCap.Round,
                        )
                    }
                    SpringboardIconKind.NOTES -> {
                        drawRect(color = Color(0xFFFFCC00), size = Size(w, h * 0.26f))
                        for (i in 1..3) {
                            val y = h * (0.28f + i * 0.18f)
                            drawLine(
                                color = Color(0x33000000),
                                start = Offset(w * 0.10f, y),
                                end = Offset(w * 0.90f, y),
                                strokeWidth = 1.5f,
                            )
                        }
                    }
                    SpringboardIconKind.REMINDERS -> {
                        for (i in ReminderDotColors.indices) {
                            val y = h * (0.26f + i * 0.24f)
                            drawCircle(color = ReminderDotColors[i], radius = w * 0.08f, center = Offset(w * 0.24f, y))
                            drawLine(
                                color = Color(0xFF9EA4AE),
                                start = Offset(w * 0.40f, y),
                                end = Offset(w * 0.84f, y),
                                strokeWidth = (h * 0.06f).coerceAtLeast(2f),
                                cap = StrokeCap.Round,
                            )
                        }
                    }
                    SpringboardIconKind.CLOCK -> {
                        drawCircle(color = Color.White, radius = w * 0.42f, center = Offset(w * 0.5f, h * 0.5f))
                        drawLine(
                            color = Color(0xFF1C1C1E),
                            start = Offset(w * 0.5f, h * 0.5f),
                            end = Offset(w * 0.5f, h * 0.22f),
                            strokeWidth = (w * 0.05f).coerceAtLeast(1.8f),
                            cap = StrokeCap.Round,
                        )
                        drawLine(
                            color = Color(0xFF1C1C1E),
                            start = Offset(w * 0.5f, h * 0.5f),
                            end = Offset(w * 0.72f, h * 0.42f),
                            strokeWidth = (w * 0.05f).coerceAtLeast(1.8f),
                            cap = StrokeCap.Round,
                        )
                        drawLine(
                            color = Color(0xFFFF9500),
                            start = Offset(w * 0.5f, h * 0.5f),
                            end = Offset(w * 0.5f, h * 0.82f),
                            strokeWidth = (w * 0.025f).coerceAtLeast(1.2f),
                            cap = StrokeCap.Round,
                        )
                    }
                    SpringboardIconKind.NEWS -> {
                        drawLine(
                            color = Color(0xFFFA2E56),
                            start = Offset(w * 0.26f, h * 0.22f),
                            end = Offset(w * 0.74f, h * 0.78f),
                            strokeWidth = (w * 0.16f).coerceAtLeast(4f),
                            cap = StrokeCap.Round,
                        )
                        drawLine(
                            color = Color(0xFFFA2E56),
                            start = Offset(w * 0.24f, h * 0.56f),
                            end = Offset(w * 0.46f, h * 0.78f),
                            strokeWidth = (w * 0.12f).coerceAtLeast(3f),
                            cap = StrokeCap.Round,
                        )
                    }
                    SpringboardIconKind.GAMES -> {
                        drawOval(
                            color = Color.White,
                            topLeft = Offset(w * 0.24f, h * 0.20f),
                            size = Size(w * 0.52f, h * 0.52f),
                        )
                        drawCircle(color = Color(0xFFFF4D38), radius = w * 0.11f, center = Offset(w * 0.54f, h * 0.44f))
                        drawCircle(color = Color(0xFFFFD080), radius = w * 0.11f, center = Offset(w * 0.30f, h * 0.72f))
                    }
                    SpringboardIconKind.APP_STORE -> {
                        val sw = (w * 0.095f).coerceAtLeast(2.5f)
                        drawLine(Color.White, Offset(w * 0.50f, h * 0.18f), Offset(w * 0.25f, h * 0.78f), sw, StrokeCap.Round)
                        drawLine(Color.White, Offset(w * 0.50f, h * 0.18f), Offset(w * 0.75f, h * 0.78f), sw, StrokeCap.Round)
                        drawLine(Color.White, Offset(w * 0.20f, h * 0.62f), Offset(w * 0.80f, h * 0.62f), sw, StrokeCap.Round)
                    }
                    SpringboardIconKind.MAPS -> {
                        drawRect(color = Color(0xFF42A5F5), topLeft = Offset(0f, 0f), size = Size(w * 0.44f, h * 0.44f))
                        drawRect(color = Color(0xFFFF6BA3), topLeft = Offset(0f, h * 0.54f), size = Size(w * 0.34f, h * 0.46f))
                        drawLine(Color.White, Offset(w * 0.45f, 0f), Offset(w * 0.45f, h), w * 0.12f)
                        drawLine(Color.White, Offset(0f, h * 0.55f), Offset(w, h * 0.84f), w * 0.12f)
                        drawCircle(color = Color(0xFF1976D2), radius = w * 0.20f, center = Offset(w * 0.36f, h * 0.64f))
                        drawCircle(color = Color.White, radius = w * 0.08f, center = Offset(w * 0.36f, h * 0.64f))
                    }
                    SpringboardIconKind.HEALTH -> {
                        val heartCol = Color(0xFFFF2D55)
                        drawCircle(color = heartCol, radius = w * 0.16f, center = Offset(w * 0.50f, h * 0.36f))
                        drawCircle(color = heartCol, radius = w * 0.16f, center = Offset(w * 0.68f, h * 0.36f))
                        drawOval(
                            color = heartCol,
                            topLeft = Offset(w * 0.39f, h * 0.36f),
                            size = Size(w * 0.40f, h * 0.34f),
                        )
                    }
                    SpringboardIconKind.WALLET -> {
                        for (i in WalletPassColors.indices) {
                            drawRoundRect(
                                color = WalletPassColors[i],
                                topLeft = Offset(w * 0.14f, h * (0.20f + i * 0.09f)),
                                size = Size(w * 0.72f, h * 0.36f),
                                cornerRadius = CornerRadius(w * 0.08f, w * 0.08f),
                            )
                        }
                        drawRoundRect(
                            color = Color(0xFFE5D4C3),
                            topLeft = Offset(w * 0.14f, h * 0.48f),
                            size = Size(w * 0.72f, h * 0.34f),
                            cornerRadius = CornerRadius(w * 0.08f, w * 0.08f),
                        )
                    }
                    SpringboardIconKind.SETTINGS -> {
                        drawCircle(
                            color = Color(0xFF3E424A),
                            radius = w * 0.34f,
                            center = Offset(w * 0.5f, h * 0.5f),
                            style = gearStroke,
                        )
                        drawCircle(
                            color = Color(0xFFE9ECEF),
                            radius = w * 0.18f,
                            center = Offset(w * 0.5f, h * 0.5f),
                            style = gearStroke,
                        )
                    }
                    SpringboardIconKind.CALENDAR,
                    SpringboardIconKind.TV,
                    -> Unit
                }
            }
        },
    )
}

@Composable
private fun SpringboardDock(
    height: Dp,
    modifier: Modifier,
) {
    Box(
        modifier = modifier
            .height(height)
            .clip(RoundedCornerShape(32))
            .background(Color(0x3D122A44))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(24))
                    .background(Color(0xFF34C759)),
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp)
                    .fillMaxHeight(0.76f)
                    .clip(RoundedCornerShape(50))
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(Color(0xFF1A91FF), Color(0xFF2882FA), Color(0xFF1F9CFF)),
                        ),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                BasicText(
                    text = "Pick an image",
                    style = TextStyle(
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        fontFamily = FontFamily.SansSerif,
                    ),
                    maxLines = 1,
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(24))
                    .background(Color(0xFFFF2D55)),
            )
        }
    }
}
