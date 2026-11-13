package dev.sebastiano.ciacola.effects

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.TextStyle
import dev.bnorm.storyboard.layout.decorated.Decoration
import dev.bnorm.storyboard.layout.decorated.DecorationLayout
import dev.bnorm.storyboard.layout.decorated.DecorationScope
import dev.sebastiano.ciacola.review.reviewTarget
import dev.sebastiano.ciacola.theme.LocalDeckTheme

@Composable
fun DottedLeaderRow(
    leading: String,
    trailing: String,
    modifier: Modifier = Modifier,
    highlightTrailing: Boolean = false,
    trailingModifier: Modifier = Modifier,
    trailingStyle: TextStyle = LocalDeckTheme.current.typography.code,
    // 0..1 blend of the trailing text towards the accent, read at draw time; overrides
    // [highlightTrailing] when set.
    highlightProgress: (() -> Float)? = null,
) {
    val theme = LocalDeckTheme.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .reviewTarget(
                kind = "DottedLeaderRow",
                label = "$leading → $trailing",
                textSnippet = "$leading … $trailing",
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text(
            text = leading,
            style = theme.typography.bodyEmphasis,
            color = theme.colors.foreground,
        )
        Canvas(Modifier.weight(1f).height(4.dp)) {
            val dotEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 8.dp.toPx()), 0f)
            drawLine(
                color = theme.colors.muted.copy(alpha = 0.65f),
                start = Offset(0f, size.height / 2f),
                end = Offset(size.width, size.height / 2f),
                strokeWidth = 2.5.dp.toPx(),
                cap = StrokeCap.Round,
                pathEffect = dotEffect,
            )
        }
        BasicText(
            text = trailing,
            style = trailingStyle,
            color = {
                trailingHighlightColor(
                    base = theme.colors.foreground,
                    accent = theme.colors.accent,
                    progress = highlightProgress?.invoke() ?: if (highlightTrailing) 1f else 0f,
                )
            },
            modifier = trailingModifier,
        )
    }
}

internal fun trailingHighlightColor(base: Color, accent: Color, progress: Float): Color =
    lerp(base, accent, progress.coerceIn(0f, 1f))

/**
 * Region of the lens (in lens-local px) that may show magnified specimen, given the target [src]
 * rect in specimen coordinates, the lens centre, and the zoom: the target inflated by [padPx], so
 * neighbouring content (e.g. the stamp border right under the target row) never bleeds into the rim.
 */
internal fun loupeSampleClip(src: Rect, lensCenter: Offset, zoom: Float, padPx: Float): Rect {
    val padded = src.inflate(padPx)
    return Rect(
        left = lensCenter.x + (padded.left - src.center.x) * zoom,
        top = lensCenter.y + (padded.top - src.center.y) * zoom,
        right = lensCenter.x + (padded.right - src.center.x) * zoom,
        bottom = lensCenter.y + (padded.bottom - src.center.y) * zoom,
    )
}

@Composable
fun KeynoteStamp(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = LocalDeckTheme.current.colors.accent,
) {
    val theme = LocalDeckTheme.current
    val stampStyle = theme.styles.stamp
    val shape = RoundedCornerShape(stampStyle.cornerRadius)
    Box(
        modifier = modifier
            .reviewTarget(kind = "KeynoteStamp", label = text, textSnippet = text)
            .background(color.copy(alpha = 0.14f), shape)
            .border(stampStyle.borderWidth, color, shape)
            .padding(
                horizontal = stampStyle.horizontalPadding,
                vertical = stampStyle.verticalPadding,
            ),
    ) {
        Text(
            text = text,
            style = theme.typography.stamp,
            color = color,
        )
    }
}

@Composable
fun DetourSign(
    label: String = "Small detour",
    symbol: String = "↱",
    modifier: Modifier = Modifier,
) {
    val theme = LocalDeckTheme.current
    val badge = theme.styles.detourBadge
    val signShape = RoundedCornerShape(badge.cornerRadius)
    Column(
        modifier = modifier.reviewTarget(
            kind = "DetourSign",
            label = "$symbol $label",
            textSnippet = label,
        ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        Box(
            modifier = Modifier
                .size(badge.diamondSize)
                .rotate(45f)
                .background(theme.colors.warning, signShape)
                .border(badge.strokeWidth, Color(0xFF1E1B16), signShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = symbol,
                style = theme.typography.heading,
                color = Color(0xFF1E1B16),
                modifier = Modifier.rotate(-45f),
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = label,
            style = theme.typography.subheading,
            color = theme.colors.foreground,
        )
    }
}

private val LoupeIrisEasing = CubicBezierEasing(0.32f, 0f, 0.24f, 1f)

@Composable
fun MagnifierLoupeStage(
    targetKey: Any,
    progress: () -> Float,
    modifier: Modifier = Modifier,
    loupeAlignment: Alignment = Alignment.CenterEnd,
    loupeDiameter: Dp = LocalDeckTheme.current.styles.loupe.diameter,
    loupePadding: PaddingValues = PaddingValues(end = 24.dp),
    decorations: List<Decoration> = emptyList(),
    zoom: Float = LocalDeckTheme.current.styles.loupe.defaultZoom,
    content: @Composable DecorationScope.() -> Unit,
) {
    val theme = LocalDeckTheme.current
    val loupeStyle = theme.styles.loupe
    val specimenLayer = rememberGraphicsLayer()
    var stageCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var targetRect by remember { mutableStateOf<Rect?>(null) }
    var loupeRect by remember { mutableStateOf<Rect?>(null) }

    val targetCaptureDecoration = remember(targetKey) {
        Decoration { container ->
            targetRect = container.getBoundingBox(targetKey)
        }
    }

    Box(
        modifier = modifier.onPlaced { stageCoords = it },
    ) {
        // Record only the slide content (inside DecorationLayout) into specimenLayer so overlays
        // like leader arrowheads, callout outlines, and strikeouts are never magnified inside the lens.
        DecorationLayout(
            modifier = Modifier.fillMaxSize(),
            decorations = decorations + targetCaptureDecoration,
        ) {
            val decorationScope = this
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .drawWithContent {
                        specimenLayer.record {
                            drawRect(color = theme.colors.background)
                            this@drawWithContent.drawContent()
                        }
                        drawLayer(specimenLayer)
                    },
            ) {
                with(decorationScope) {
                    content()
                }
            }
        }

        // Dotted connector tangents + tight pill source frame + iris-clipped live magnified loupe
        Canvas(Modifier.fillMaxSize()) {
            val p = progress().coerceIn(0f, 1f)
            val src = targetRect ?: return@Canvas
            val dst = loupeRect ?: return@Canvas
            if (p <= 0f) return@Canvas

            val edgeAlpha = (p / 0.18f).coerceIn(0f, 1f)
            val iris = LoupeIrisEasing.transform(p)
            val padPx = 6.dp.toPx()
            val halfW = (src.width / 2f + padPx) * iris
            val halfH = (src.height / 2f + padPx) * iris
            val pillRect = Rect(
                left = src.center.x - halfW,
                top = src.center.y - halfH,
                right = src.center.x + halfW,
                bottom = src.center.y + halfH,
            )
            val pillRadius = (pillRect.height / 2f).coerceAtMost(14.dp.toPx())
            val dstRadius = (dst.minDimension / 2f) * iris
            val anchorX = if (dst.center.x >= src.center.x) {
                (pillRect.right - pillRadius * 0.35f).coerceAtLeast(src.center.x)
            } else {
                (pillRect.left + pillRadius * 0.35f).coerceAtMost(src.center.x)
            }

            val dash = PathEffect.dashPathEffect(
                floatArrayOf(loupeStyle.connectorDashOn.toPx(), loupeStyle.connectorDashOff.toPx()),
                0f,
            )
            val topLine = Path().apply {
                moveTo(anchorX, pillRect.top)
                lineTo(dst.center.x, dst.center.y - dstRadius)
            }
            val bottomLine = Path().apply {
                moveTo(anchorX, pillRect.bottom)
                lineTo(dst.center.x, dst.center.y + dstRadius)
            }
            val trimmedTop = Path()
            val trimmedBottom = Path()
            trimPath(topLine, iris, trimmedTop)
            trimPath(bottomLine, iris, trimmedBottom)
            val strokeColor = theme.colors.muted.copy(alpha = theme.colors.muted.alpha * edgeAlpha)
            val connectorStroke = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, pathEffect = dash)
            drawPath(trimmedTop, color = strokeColor, style = connectorStroke)
            drawPath(trimmedBottom, color = strokeColor, style = connectorStroke)
            drawRoundRect(
                color = strokeColor,
                topLeft = pillRect.topLeft,
                size = Size(pillRect.width, pillRect.height),
                cornerRadius = CornerRadius(pillRadius, pillRadius),
                style = Stroke(width = 2.dp.toPx()),
            )
        }

        Box(
            modifier = Modifier
                .align(loupeAlignment)
                .padding(loupePadding)
                .size(loupeDiameter)
                .testTag("ciacola-magnifier-loupe")
                .onPlaced { coords ->
                    stageCoords?.let { root ->
                        loupeRect = root.localBoundingBoxOf(coords)
                    }
                }
                .graphicsLayer {
                    val p = progress().coerceIn(0f, 1f)
                    val a = (p / 0.18f).coerceIn(0f, 1f)
                    alpha = a
                    compositingStrategy = if (a < 1f) CompositingStrategy.Offscreen else CompositingStrategy.Auto
                }
                .drawWithContent {
                    val p = progress().coerceIn(0f, 1f)
                    val src = targetRect ?: return@drawWithContent
                    if (p <= 0f) return@drawWithContent

                    val edgeAlpha = (p / 0.18f).coerceIn(0f, 1f)
                    val iris = LoupeIrisEasing.transform(p)
                    val radius = (size.minDimension / 2f) * iris
                    if (radius <= 0.5f) return@drawWithContent
                    val center = Offset(size.width / 2f, size.height / 2f)
                    val circle = Path().apply {
                        addOval(Rect(center = center, radius = radius))
                    }
                    clipPath(circle) {
                        drawRect(color = theme.colors.background)
                        val sample = loupeSampleClip(src, center, zoom, padPx = 4.dp.toPx())
                        clipRect(sample.left, sample.top, sample.right, sample.bottom) {
                            withTransform({
                                // Translate so src.center inside specimenLayer lands at loupe center, scaled by zoom
                                translate(
                                    left = center.x - src.center.x * zoom,
                                    top = center.y - src.center.y * zoom,
                                )
                                scale(scaleX = zoom, scaleY = zoom, pivot = Offset.Zero)
                            }) {
                                drawLayer(specimenLayer)
                            }
                        }
                    }
                    drawCircle(
                        color = theme.colors.accent.copy(alpha = theme.colors.accent.alpha * edgeAlpha),
                        radius = radius,
                        center = center,
                        style = Stroke(width = loupeStyle.ringWidth.toPx()),
                    )
                },
        )
    }
}
