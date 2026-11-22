package dev.sebastiano.ciacola.inspector.element

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Crisp vector glyphs drawn in a 16-unit grid for inspector toolbars and the Layers tree. */
internal enum class InspectorGlyph {
    AlignLeft, AlignCenterH, AlignRight, AlignTop, AlignCenterV, AlignBottom,
    DistributeH, DistributeV,
    TextLeft, TextCenter, TextRight,
    LayerText, LayerRect, LayerEllipse, LayerLine, LayerImage, LayerContainer, LayerSpecimen, LayerCode, LayerOther,
}

internal fun NodeAlignment.glyph(): InspectorGlyph = when (this) {
    NodeAlignment.Left -> InspectorGlyph.AlignLeft
    NodeAlignment.CenterH -> InspectorGlyph.AlignCenterH
    NodeAlignment.Right -> InspectorGlyph.AlignRight
    NodeAlignment.Top -> InspectorGlyph.AlignTop
    NodeAlignment.CenterV -> InspectorGlyph.AlignCenterV
    NodeAlignment.Bottom -> InspectorGlyph.AlignBottom
}

@Composable
internal fun InspectorGlyphIcon(glyph: InspectorGlyph, tint: Color, size: Dp = 14.dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val u = this.size.minDimension / 16f
        drawGlyph(glyph, tint, u)
    }
}

private fun DrawScope.line(tint: Color, u: Float, x1: Float, y1: Float, x2: Float, y2: Float, w: Float = 1.25f) {
    drawLine(tint, Offset(x1 * u, y1 * u), Offset(x2 * u, y2 * u), strokeWidth = w * u, cap = StrokeCap.Round)
}

private fun DrawScope.box(tint: Color, u: Float, x: Float, y: Float, w: Float, h: Float, filled: Boolean = true) {
    if (filled) {
        drawRoundRect(tint.copy(alpha = 0.85f), Offset(x * u, y * u), Size(w * u, h * u), CornerRadius(1f * u))
    } else {
        drawRoundRect(tint, Offset(x * u, y * u), Size(w * u, h * u), CornerRadius(1f * u), style = Stroke(1.2f * u))
    }
}

private fun DrawScope.drawGlyph(glyph: InspectorGlyph, tint: Color, u: Float) {
    when (glyph) {
        InspectorGlyph.AlignLeft -> { line(tint, u, 2f, 1.5f, 2f, 14.5f); box(tint, u, 4f, 3.5f, 9f, 3.5f); box(tint, u, 4f, 9f, 6f, 3.5f) }
        InspectorGlyph.AlignCenterH -> { line(tint, u, 8f, 1.5f, 8f, 14.5f); box(tint, u, 3f, 3.5f, 10f, 3.5f); box(tint, u, 5f, 9f, 6f, 3.5f) }
        InspectorGlyph.AlignRight -> { line(tint, u, 14f, 1.5f, 14f, 14.5f); box(tint, u, 3f, 3.5f, 9f, 3.5f); box(tint, u, 6f, 9f, 6f, 3.5f) }
        InspectorGlyph.AlignTop -> { line(tint, u, 1.5f, 2f, 14.5f, 2f); box(tint, u, 3.5f, 4f, 3.5f, 9f); box(tint, u, 9f, 4f, 3.5f, 6f) }
        InspectorGlyph.AlignCenterV -> { line(tint, u, 1.5f, 8f, 14.5f, 8f); box(tint, u, 3.5f, 3f, 3.5f, 10f); box(tint, u, 9f, 5f, 3.5f, 6f) }
        InspectorGlyph.AlignBottom -> { line(tint, u, 1.5f, 14f, 14.5f, 14f); box(tint, u, 3.5f, 3f, 3.5f, 9f); box(tint, u, 9f, 6f, 3.5f, 6f) }
        InspectorGlyph.DistributeH -> { line(tint, u, 1.5f, 2f, 1.5f, 14f); line(tint, u, 14.5f, 2f, 14.5f, 14f); box(tint, u, 6.25f, 4f, 3.5f, 8f) }
        InspectorGlyph.DistributeV -> { line(tint, u, 2f, 1.5f, 14f, 1.5f); line(tint, u, 2f, 14.5f, 14f, 14.5f); box(tint, u, 4f, 6.25f, 8f, 3.5f) }
        InspectorGlyph.TextLeft -> { line(tint, u, 2f, 4f, 14f, 4f); line(tint, u, 2f, 8f, 10f, 8f); line(tint, u, 2f, 12f, 12f, 12f) }
        InspectorGlyph.TextCenter -> { line(tint, u, 2f, 4f, 14f, 4f); line(tint, u, 4f, 8f, 12f, 8f); line(tint, u, 3f, 12f, 13f, 12f) }
        InspectorGlyph.TextRight -> { line(tint, u, 2f, 4f, 14f, 4f); line(tint, u, 6f, 8f, 14f, 8f); line(tint, u, 4f, 12f, 14f, 12f) }
        InspectorGlyph.LayerText -> { line(tint, u, 3f, 3.5f, 13f, 3.5f, 1.6f); line(tint, u, 8f, 3.5f, 8f, 13f, 1.6f) }
        InspectorGlyph.LayerRect -> box(tint, u, 2.5f, 3.5f, 11f, 9f, filled = false)
        InspectorGlyph.LayerEllipse -> drawCircle(tint, radius = 5.5f * u, center = Offset(8f * u, 8f * u), style = Stroke(1.2f * u))
        InspectorGlyph.LayerLine -> line(tint, u, 3f, 13f, 13f, 3f)
        InspectorGlyph.LayerImage -> { box(tint, u, 2.5f, 3.5f, 11f, 9f, filled = false); line(tint, u, 4f, 11f, 7f, 7.5f); line(tint, u, 7f, 7.5f, 12f, 11f) }
        InspectorGlyph.LayerContainer -> { box(tint, u, 2.5f, 2.5f, 11f, 4.5f, filled = false); box(tint, u, 2.5f, 9f, 11f, 4.5f, filled = false) }
        InspectorGlyph.LayerSpecimen -> { drawCircle(tint, radius = 2.2f * u, center = Offset(8f * u, 8f * u)); drawCircle(tint, radius = 5.5f * u, center = Offset(8f * u, 8f * u), style = Stroke(1.1f * u)) }
        InspectorGlyph.LayerCode -> { line(tint, u, 6f, 4f, 2.5f, 8f); line(tint, u, 2.5f, 8f, 6f, 12f); line(tint, u, 10f, 4f, 13.5f, 8f); line(tint, u, 13.5f, 8f, 10f, 12f) }
        InspectorGlyph.LayerOther -> drawCircle(tint, radius = 2f * u, center = Offset(8f * u, 8f * u))
    }
}
