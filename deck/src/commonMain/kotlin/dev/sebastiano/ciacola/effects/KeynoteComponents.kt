package dev.sebastiano.ciacola.effects

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
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
        Text(
            text = trailing,
            style = trailingStyle,
            color = if (highlightTrailing) theme.colors.accent else theme.colors.foreground,
            modifier = trailingModifier,
        )
    }
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

@Composable
fun MagnifierLoupeStage(
    targetKey: Any,
    progress: () -> Float,
    modifier: Modifier = Modifier,
    loupeAlignment: Alignment = Alignment.CenterEnd,
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
        DecorationLayout(
            modifier = Modifier
                .fillMaxSize()
                .drawWithContent {
                    specimenLayer.record {
                        this@drawWithContent.drawContent()
                    }
                    drawLayer(specimenLayer)
                },
            decorations = decorations + targetCaptureDecoration,
            content = content,
        )

        // Dotted connector tangents + iris-clipped live magnified loupe
        Canvas(Modifier.fillMaxSize()) {
            val p = progress().coerceIn(0f, 1f)
            val src = targetRect ?: return@Canvas
            val dst = loupeRect ?: return@Canvas
            if (p <= 0f) return@Canvas

            val dash = PathEffect.dashPathEffect(
                floatArrayOf(loupeStyle.connectorDashOn.toPx(), loupeStyle.connectorDashOff.toPx()),
                0f,
            )
            val topLine = Path().apply {
                moveTo(src.center.x, src.top)
                lineTo(dst.center.x, dst.top)
            }
            val bottomLine = Path().apply {
                moveTo(src.center.x, src.bottom)
                lineTo(dst.center.x, dst.bottom)
            }
            val trimmedTop = Path()
            val trimmedBottom = Path()
            trimPath(topLine, p, trimmedTop)
            trimPath(bottomLine, p, trimmedBottom)
            val connectorStroke = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, pathEffect = dash)
            drawPath(trimmedTop, color = theme.colors.muted, style = connectorStroke)
            drawPath(trimmedBottom, color = theme.colors.muted, style = connectorStroke)
            drawCircle(
                color = theme.colors.muted,
                radius = (src.maxDimension * 0.58f) * p,
                center = src.center,
                style = Stroke(width = 2.dp.toPx()),
            )
        }

        Box(
            modifier = Modifier
                .align(loupeAlignment)
                .padding(end = 24.dp)
                .size(loupeStyle.diameter)
                .onPlaced { coords ->
                    stageCoords?.let { root ->
                        loupeRect = root.localBoundingBoxOf(coords)
                    }
                }
                .drawWithContent {
                    val p = progress().coerceIn(0f, 1f)
                    val src = targetRect ?: return@drawWithContent
                    if (p <= 0f) return@drawWithContent

                    val radius = (size.minDimension / 2f) * p
                    val center = Offset(size.width / 2f, size.height / 2f)
                    val circle = Path().apply {
                        addOval(Rect(center = center, radius = radius))
                    }
                    clipPath(circle) {
                        drawRect(color = theme.colors.elevatedSurface)
                        val stageOffset = loupeRect?.topLeft ?: Offset.Zero
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
                        // Unused local read kept explicit for clarity: specimenLayer is recorded in stage space
                        @Suppress("UNUSED_VARIABLE")
                        val ignored = stageOffset
                    }
                    drawCircle(
                        color = theme.colors.accent,
                        radius = radius,
                        center = center,
                        style = Stroke(width = loupeStyle.ringWidth.toPx()),
                    )
                },
        )
    }
}
