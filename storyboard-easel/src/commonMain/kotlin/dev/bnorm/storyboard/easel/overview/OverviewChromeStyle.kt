package dev.bnorm.storyboard.easel.overview

import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Host-application chrome style for the Overview (background, text, accent, and UI typeface), so the
 * Overview matches the surrounding tool instead of borrowing the deck's own presentation theme.
 *
 * Hosts provide it through [LocalOverviewChromeStyle]; without one, a neutral dark or light palette
 * is derived from [MaterialTheme.colors].
 */
@Immutable
data class OverviewChromeStyle(
    val background: Color,
    val surface: Color,
    val onSurface: Color,
    val mutedOnSurface: Color,
    val accent: Color,
    val border: Color,
    val fontFamily: FontFamily = FontFamily.Default,
    /** Quick Jump shortcut hint: `⌘K` on macOS, `Ctrl+K` elsewhere (set by the host). */
    val quickJumpShortcutLabel: String = "⌘K",
) {
    /** 13sp UI text (chrome bar, captions). */
    fun body(weight: FontWeight = FontWeight.Normal, color: Color = onSurface): TextStyle =
        textStyle(13.sp, weight, color)

    /** 12sp secondary UI text (badges, counts). */
    fun caption(weight: FontWeight = FontWeight.Normal, color: Color = mutedOnSurface): TextStyle =
        textStyle(12.sp, weight, color)

    private fun textStyle(size: TextUnit, weight: FontWeight, color: Color): TextStyle = TextStyle(
        fontFamily = fontFamily,
        fontSize = size,
        fontWeight = weight,
        color = color,
        // Tabular numerals keep slide and build counters from jittering as they change.
        fontFeatureSettings = "tnum",
    )

    companion object {
        val NeutralDark = OverviewChromeStyle(
            background = Color(0xFF1E1F22),
            surface = Color(0xFF2B2D30),
            onSurface = Color(0xFFDFE1E5),
            mutedOnSurface = Color(0xFF9DA0A8),
            accent = Color(0xFF548AF7),
            border = Color(0xFF43454A),
        )

        val NeutralLight = OverviewChromeStyle(
            background = Color(0xFFF7F8FA),
            surface = Color(0xFFFFFFFF),
            onSurface = Color(0xFF1E1F22),
            mutedOnSurface = Color(0xFF6C707E),
            accent = Color(0xFF3574F0),
            border = Color(0xFFDFE1E5),
        )
    }
}

/** Host-provided [OverviewChromeStyle]; `null` falls back to a neutral palette. */
val LocalOverviewChromeStyle = compositionLocalOf<OverviewChromeStyle?> { null }

@Composable
internal fun currentOverviewChromeStyle(): OverviewChromeStyle =
    LocalOverviewChromeStyle.current
        ?: if (MaterialTheme.colors.isLight) OverviewChromeStyle.NeutralLight else OverviewChromeStyle.NeutralDark

/** `1 build` / `2 builds`, spelled out (never `2b`). */
fun formatOverviewBuildCount(count: Int): String = if (count == 1) "1 build" else "$count builds"

/** Single-line card caption: `#03 · Title` (the badge alone when the slide has no title). */
internal fun overviewCardCaption(slideIndex: Int, label: String): String {
    val badge = formatOverviewBadgeNumber(slideIndex)
    return if (label == "Slide ${slideIndex + 1}") badge else "$badge · $label"
}

/**
 * Strip-mode geometry: `rowsVisible` thumbnails per column fill the available height exactly, so
 * there is no dead band above the first row and no row is clipped at the bottom edge.
 */
@Immutable
internal data class OverviewStripLayout(
    val itemSize: DpSize,
    val rowsVisible: Int,
    val verticalPadding: Dp,
)

internal fun calculateOverviewStripLayout(
    viewSize: DpSize,
    contentSize: IntSize,
    maxItemsInColumn: Int,
    itemPadding: Dp = 20.dp,
    edgePadding: Dp = 16.dp,
    minItemHeight: Dp = 120.dp,
    maxRows: Int = 3,
    maxWidthFraction: Float = 0.42f,
): OverviewStripLayout {
    val aspect = (contentSize.width.toFloat() / contentSize.height.coerceAtLeast(1)).coerceIn(0.25f, 4f)
    val availableHeight = (viewSize.height - edgePadding * 2).coerceAtLeast(minItemHeight)
    var rows = maxItemsInColumn.coerceIn(1, maxRows)
    while (rows > 1 && availableHeight / rows < minItemHeight) rows--
    var itemHeight = availableHeight / rows
    var itemWidth = itemPadding + (itemHeight - itemPadding) * aspect
    val maxItemWidth = (viewSize.width * maxWidthFraction).coerceAtLeast(itemPadding * 2)
    if (itemWidth > maxItemWidth) {
        itemWidth = maxItemWidth
        itemHeight = itemPadding + (itemWidth - itemPadding) / aspect
    }
    val used = itemHeight * rows
    val verticalPadding = ((viewSize.height - used) / 2).coerceAtLeast(0.dp)
    return OverviewStripLayout(
        itemSize = DpSize(itemWidth, itemHeight),
        rowsVisible = rows,
        verticalPadding = verticalPadding,
    )
}

/** First list index to scroll to so [currentIndex] stays fully visible within [rowsVisible] rows. */
internal fun stripFirstVisibleIndex(currentIndex: Int, rowsVisible: Int): Int =
    (currentIndex - rowsVisible + 1).coerceAtLeast(0)
