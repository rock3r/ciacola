package dev.sebastiano.ciacola.timeline

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sebastiano.ciacola.theme.contrastRatio
import dev.sebastiano.ciacola.theme.relativeLuminance
import java.awt.Cursor
import java.util.Locale
import kotlin.math.PI
import kotlin.math.roundToLong
import kotlin.math.sin
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.Tooltip
import org.jetbrains.jewel.ui.theme.colorPalette
import org.jetbrains.jewel.ui.typography

internal val SUBCOMP_HEADER_HEIGHT = 48.dp
internal val SUBCOMP_RIBBON_HEIGHT = 24.dp

private data class TimelineTextLayoutCacheKey(
    val text: String,
    val style: TextStyle,
    val maxWidthPx: Int,
    val softWrap: Boolean,
    val overflow: TextOverflow,
    val density: Float,
)

/**
 * Bounded LRU cache for measured [TextLayoutResult] instances on timeline canvas draw passes.
 */
class TimelineTextLayoutCache(private val maxEntries: Int = 256) {
    private val entries = object : LinkedHashMap<TimelineTextLayoutCacheKey, TextLayoutResult>(64, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<TimelineTextLayoutCacheKey, TextLayoutResult>?,
        ): Boolean = size > maxEntries
    }

    val size: Int get() = entries.size

    fun measure(
        textMeasurer: TextMeasurer,
        text: String,
        style: TextStyle,
        density: Float,
        maxWidthPx: Int = Int.MAX_VALUE,
        softWrap: Boolean = false,
        overflow: TextOverflow = TextOverflow.Clip,
    ): TextLayoutResult {
        val key = TimelineTextLayoutCacheKey(
            text = text,
            style = style,
            maxWidthPx = maxWidthPx,
            softWrap = softWrap,
            overflow = overflow,
            density = density,
        )
        entries[key]?.let { return it }
        val constraints = if (maxWidthPx == Int.MAX_VALUE) Constraints() else Constraints(maxWidth = maxWidthPx)
        val result = textMeasurer.measure(
            text = text,
            style = style,
            maxLines = 1,
            softWrap = softWrap,
            overflow = overflow,
            constraints = constraints,
        )
        entries[key] = result
        return result
    }
}

/** Shared 5-point pentagon playhead cap + vertical hairline used by Master Deck and Loop Sub-Comp. */
internal fun DrawScope.drawPlayheadWithPentagonCap(
    x: Float,
    height: Float,
    color: Color,
) {
    drawLine(color, Offset(x, 0f), Offset(x, height), 2f)
    val cap = Path().apply {
        moveTo(x - 6f, 0f)
        lineTo(x + 6f, 0f)
        lineTo(x + 6f, 9f)
        lineTo(x, 15f)
        lineTo(x - 6f, 9f)
        close()
    }
    drawPath(cap, color = color)
}

/** Hover-highlighted 5 dp vertical splitter for resizing the track header column (`180..420.dp`). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun DocumentLayerColumnSplitter(
    viewport: TimelineViewportState,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val latestDensity by rememberUpdatedState(density.density)
    val globalColors = JewelTheme.globalColors
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    var dragging by remember { mutableStateOf(false) }
    val active = hovered || dragging

    Tooltip(tooltip = { Text("Drag to resize layer column (double-click to reset)") }) {
        Box(
            modifier = modifier
                .width(5.dp)
                .fillMaxHeight()
                .hoverable(interactionSource)
                .background(
                    if (active) globalColors.outlines.focused.copy(alpha = 0.14f) else Color.Transparent,
                )
                .pointerHoverIcon(PointerIcon(Cursor(Cursor.E_RESIZE_CURSOR)))
                .pointerInput(viewport) {
                    detectTapGestures(onDoubleTap = { viewport.resetLayerColumnWidth() })
                }
                .pointerInput(viewport) {
                    detectHorizontalDragGestures(
                        onDragStart = { dragging = true },
                        onDragEnd = { dragging = false },
                        onDragCancel = { dragging = false },
                    ) { change, dragAmount ->
                        change.consume()
                        viewport.resizeLayerColumnBy(dragAmount / latestDensity.coerceAtLeast(0.5f))
                    }
                }
                .testTag("ciacola-layer-column-splitter"),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .width(if (active) 2.dp else 1.dp)
                    .fillMaxHeight()
                    .background(if (active) globalColors.outlines.focused else globalColors.borders.normal),
            )
        }
    }
}

/**
 * Restored Loop Sub-Composition Scrub Bar (`0..periodMs`) bound to [DocumentTimelineController].
 * Scrubbing updates [DocumentTimelineController.subCompositionScrubMillis], driving live
 * `RenderSession` loop phase on the stage.
 */
@Composable
internal fun DocumentLoopSubCompTimelineView(
    controller: DocumentTimelineController,
    ref: DocumentTimelineTrackRef.LoopTrackBar,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val textMeasurer: TextMeasurer = rememberTextMeasurer()
    val textCache = remember(textMeasurer) { TimelineTextLayoutCache() }
    val globalColors = JewelTheme.globalColors
    val palette = JewelTheme.colorPalette
    val isDark = JewelTheme.isDark
    val colors = remember(JewelTheme.instanceUuid, isDark) {
        buildStudioCanvasPaletteForTheme(
            isDark = isDark,
            panelBg = globalColors.panelBackground,
            borderNormal = globalColors.borders.normal,
            textPrimary = globalColors.text.normal,
            focusedOutline = globalColors.outlines.focused,
            playhead = palette.redOrNull(if (isDark) 6 else 5) ?: Color(0xFFE5534B),
        )
    }

    val periodMs = ref.effectivePeriodMs.coerceAtLeast(100L)
    val latestPeriodMs by rememberUpdatedState(periodMs)
    val scrubMs = (controller.subCompositionScrubMillis ?: 0L).coerceIn(0L, periodMs)
    val channels = remember(ref.loopId) {
        listOf(
            "Phase sin(2πt)",
            "Quadrature cos(2πt)",
            "Harmonic 2×",
        )
    }
    val ribbonPx = with(density) { SUBCOMP_RIBBON_HEIGHT.toPx() }
    val rulerPx = with(density) { SUBCOMP_HEADER_HEIGHT.toPx() }
    val rowPx = with(density) { 36.dp.toPx() }
    val verticalScroll = rememberScrollState()

    Row(modifier = modifier.fillMaxSize().testTag("ciacola-loop-subcomp-view")) {
        Column(
            modifier = Modifier
                .width(controller.viewport.layerColumnWidthDp.dp)
                .fillMaxHeight()
                .clipToBounds()
                .background(globalColors.panelBackground),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(SUBCOMP_HEADER_HEIGHT)
                    .background(colors.headerBg)
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(
                    text = "LOOP SUB-COMP (${periodMs}ms)",
                    style = JewelTheme.typography.small.copy(fontWeight = FontWeight.Bold),
                    color = globalColors.text.normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Divider(Orientation.Horizontal, Modifier.fillMaxWidth())
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(verticalScroll),
            ) {
                channels.forEachIndexed { idx, ch ->
                    Box(
                        modifier = Modifier.fillMaxWidth().height(36.dp).padding(horizontal = 12.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Text(
                            text = "${idx + 1}. $ch",
                            style = JewelTheme.typography.small,
                            color = studioSecondaryTextColor(isDark),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }

        DocumentLayerColumnSplitter(controller.viewport)

        Box(Modifier.weight(1f).fillMaxHeight()) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .clipToBounds()
                    .testTag("ciacola-subcomp-canvas")
                    .semantics {
                        contentDescription = "Loop sub-composition scrub bar for ${ref.label}"
                        stateDescription = "Scrub at ${formatMillisForAccessibility(scrubMs.toInt())} of ${formatMillisForAccessibility(periodMs.toInt())}"
                    }
                    // Keyed ONLY on stable loopId (never periodMs) so dragging never self-cancels.
                    .pointerInput(ref.loopId) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            down.consume()
                            val usableW = (size.width - 32f).coerceAtLeast(1f)
                            fun msAt(x: Float): Long =
                                (((x - 16f) / usableW).coerceIn(0f, 1f) * latestPeriodMs).roundToLong()
                            controller.scrubLoopSubComp(msAt(down.position.x))
                            while (true) {
                                val ev = awaitPointerEvent()
                                val ch = ev.changes.firstOrNull() ?: break
                                if (!ch.pressed) break
                                ch.consume()
                                controller.scrubLoopSubComp(msAt(ch.position.x))
                            }
                        }
                    },
            ) {
                val leftPad = 16f
                val usableW = (size.width - 32f).coerceAtLeast(1f)
                val teal = colors.loopBar
                val scrollY = verticalScroll.value.toFloat()

                drawRect(color = colors.bg)

                clipRect(top = rulerPx) {
                    channels.forEachIndexed { idx, _ ->
                        val yTop = rulerPx + idx * rowPx - scrollY + 4f
                        val barH = rowPx - 8f
                        if (yTop + barH < rulerPx || yTop > size.height) return@forEachIndexed
                        drawLine(
                            color = colors.gridLine,
                            start = Offset(0f, yTop - 4f + rowPx),
                            end = Offset(size.width, yTop - 4f + rowPx),
                            strokeWidth = 1f,
                        )
                        drawRoundRect(
                            color = teal.copy(alpha = 0.22f),
                            topLeft = Offset(leftPad, yTop),
                            size = Size(usableW, barH),
                            cornerRadius = CornerRadius(5f, 5f),
                        )
                        val wave = Path()
                        val samples = 72
                        val freqMult = if (idx == 2) 2f else 1f
                        val phaseOffset = if (idx == 1) (PI / 2.0).toFloat() else 0f
                        for (k in 0..samples) {
                            val f = k.toFloat() / samples.toFloat()
                            val wx = leftPad + f * usableW
                            val phase = f * 2f * PI.toFloat() * freqMult + phaseOffset
                            val wy = yTop + barH * 0.5f - sin(phase) * (barH * 0.34f)
                            if (k == 0) wave.moveTo(wx, wy) else wave.lineTo(wx, wy)
                        }
                        drawPath(wave, color = teal, style = Stroke(width = 2f))
                    }
                }

                // Header ribbon + tick marks
                drawRect(color = colors.headerBg, topLeft = Offset.Zero, size = Size(size.width, rulerPx))
                val ribbonFill = teal.copy(alpha = 0.85f)
                val ribbonInk = barLabelInkForFill(compositeColorOver(ribbonFill, colors.headerBg))
                drawRoundRect(
                    color = ribbonFill,
                    topLeft = Offset(leftPad, 2f),
                    size = Size(usableW, ribbonPx - 4f),
                    cornerRadius = CornerRadius(4f, 4f),
                )
                val headerLabel = textCache.measure(
                    textMeasurer = textMeasurer,
                    text = "⟳ ${ref.label} (${periodMs}ms cycle)",
                    style = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Medium, color = ribbonInk),
                    density = this.density,
                    maxWidthPx = (usableW - 12f).toInt().coerceAtLeast(16),
                    overflow = TextOverflow.Ellipsis,
                )
                drawText(headerLabel, topLeft = Offset(leftPad + 6f, 4f))

                val steps = 6
                val tickStyle = TextStyle(fontSize = 10.sp, color = colors.textSecondary, fontFamily = FontFamily.Monospace)
                var lastTickRight = Float.NEGATIVE_INFINITY
                for (s in 0..steps) {
                    val frac = s.toFloat() / steps.toFloat()
                    val x = leftPad + frac * usableW
                    drawLine(
                        color = colors.gridLine,
                        start = Offset(x, ribbonPx + 2f),
                        end = Offset(x, size.height),
                        strokeWidth = 1f,
                    )
                    val label = formatTimelineRulerLabel((frac * periodMs).toInt(), (periodMs / steps).toInt(), maxVisibleMs = periodMs.toInt())
                    val measured = textCache.measure(textMeasurer, label, tickStyle, this.density)
                    val tx = if (s == steps) (x - measured.size.width - 3f).coerceAtLeast(0f) else x + 3f
                    if (tx >= lastTickRight + 6f && tx + measured.size.width <= size.width) {
                        drawText(measured, topLeft = Offset(tx, ribbonPx + 3f))
                        lastTickRight = tx + measured.size.width
                    }
                }
                drawLine(colors.gridLine, Offset(0f, rulerPx), Offset(size.width, rulerPx), 1.5f)

                val scrubFrac = (scrubMs.toFloat() / periodMs.toFloat()).coerceIn(0f, 1f)
                val px = leftPad + scrubFrac * usableW
                drawPlayheadWithPentagonCap(x = px, height = size.height, color = colors.playhead)
            }
        }
    }
}

data class TimelineCanvasPalette(
    val bg: Color,
    val headerBg: Color,
    val gridLine: Color,
    val beatLine: Color,
    val injectedBeatLine: Color,
    val boundaryLine: Color,
    val playhead: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val selectedBorder: Color,
    val selectedBarOuterRing: Color,
    val selectedBarInnerRing: Color,
    val sharedBar: Color,
    val magicTextBar: Color,
    val warpBar: Color,
    val staggerBar: Color,
    val buildBar: Color,
    val lineDrawBar: Color,
    val loopBar: Color,
    val slideColors: List<Color>,
) {
    fun colorForCategory(category: TrackCategory): Color = when (category) {
        TrackCategory.SharedElement -> sharedBar
        TrackCategory.MagicText -> magicTextBar
        TrackCategory.WarpCode -> warpBar
        TrackCategory.StaggerGroup, TrackCategory.StaggerItem -> staggerBar
        TrackCategory.Build -> buildBar
        TrackCategory.LineDraw -> lineDrawBar
        TrackCategory.LoopSubComp -> loopBar
    }
}

fun studioSecondaryTextColor(isDark: Boolean): Color =
    if (isDark) Color(0xFFB4BCC8) else Color(0xFF475061)

fun buildStudioCanvasPaletteForTheme(
    isDark: Boolean,
    panelBg: Color = if (isDark) Color(0xFF2B2D30) else Color(0xFFF7F8FA),
    borderNormal: Color = if (isDark) Color(0xFF393B40) else Color(0xFFD3D5DB),
    textPrimary: Color = if (isDark) Color(0xFFDFE1E5) else Color(0xFF1E1F22),
    focusedOutline: Color = if (isDark) Color(0xFF3574F0) else Color(0xFF2563EB),
    injectedBeatLine: Color = Color(0xFFE0A82E),
    boundaryLine: Color = Color(0xFF2FA896),
    playhead: Color = Color(0xFFE5534B),
): TimelineCanvasPalette = TimelineCanvasPalette(
    bg = panelBg,
    headerBg = if (isDark) Color(0xFF1A1E27) else Color(0xFFEBEEF4),
    gridLine = borderNormal.copy(alpha = 0.45f),
    beatLine = if (isDark) Color(0xFF94A0B8) else Color(0xFF5A6578),
    injectedBeatLine = injectedBeatLine,
    boundaryLine = boundaryLine,
    playhead = playhead,
    textPrimary = textPrimary,
    textSecondary = studioSecondaryTextColor(isDark),
    selectedBorder = focusedOutline,
    selectedBarOuterRing = if (isDark) Color.White else Color(0xFF1B3A7A),
    selectedBarInnerRing = if (isDark) Color(0xFF141820) else Color.White,
    sharedBar = if (isDark) Color(0xFF6D44B8) else Color(0xFF5B21B6),
    magicTextBar = if (isDark) Color(0xFF2356C2) else Color(0xFF1D4ED8),
    warpBar = if (isDark) Color(0xFF45C4BA) else Color(0xFF52C7BE),
    staggerBar = if (isDark) Color(0xFF1F7A4C) else Color(0xFF6BD19A),
    buildBar = if (isDark) Color(0xFFEB8A44) else Color(0xFFF59E56),
    lineDrawBar = if (isDark) Color(0xFFE5B546) else Color(0xFFF4C552),
    loopBar = if (isDark) Color(0xFF1B746B) else Color(0xFF68C9BF),
    slideColors = if (isDark) {
        listOf(
            Color(0xFF334466),
            Color(0xFF4A3861),
            Color(0xFF2B5450),
            Color(0xFF5A3F37),
            Color(0xFF3B4F3B),
        )
    } else {
        listOf(
            Color(0xFFD4E1F5),
            Color(0xFFE4D8F2),
            Color(0xFFCDE9E5),
            Color(0xFFF2DDD4),
            Color(0xFFD8EAD9),
        )
    },
)

fun wcagRelativeLuminance(color: Color): Float = relativeLuminance(color)

fun wcagContrastRatio(fg: Color, bg: Color): Float = contrastRatio(fg, bg)

fun compositeColorOver(fg: Color, bg: Color): Color {
    val a = fg.alpha.coerceIn(0f, 1f)
    return Color(
        red = fg.red * a + bg.red * (1f - a),
        green = fg.green * a + bg.green * (1f - a),
        blue = fg.blue * a + bg.blue * (1f - a),
        alpha = 1f,
    )
}

fun barLabelInkForFill(compositedBarFill: Color): Color {
    val darkInk = Color(0xFF101318)
    val lightInk = Color.White
    return if (wcagContrastRatio(darkInk, compositedBarFill) >= wcagContrastRatio(lightInk, compositedBarFill)) {
        darkInk
    } else {
        lightInk
    }
}
