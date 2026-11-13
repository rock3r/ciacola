package dev.sebastiano.ciacola.effects

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.asSkiaPath
import org.jetbrains.skia.PathBuilder
import org.jetbrains.skia.PathMeasure as SkiaPathMeasure

internal actual fun trimMultiContourPath(
    source: Path,
    progress: Float,
    destination: Path,
): PathTipPose? {
    val clamped = progress.coerceIn(0f, 1f)
    destination.reset()
    if (clamped <= 0f) return null

    val skiaSource = source.asSkiaPath()
    val contourLengths = mutableListOf<Float>()
    var totalLength = 0f

    SkiaPathMeasure(skiaSource, false).use { measurer ->
        do {
            val len = measurer.length
            contourLengths += len
            totalLength += len
        } while (measurer.nextContour())
    }

    if (totalLength <= 0f) return null

    var remaining = totalLength * clamped
    var tipPose: PathTipPose? = null
    val builder = PathBuilder()

    SkiaPathMeasure(skiaSource, false).use { measurer ->
        var contourIndex = 0
        do {
            val len = contourLengths.getOrElse(contourIndex) { measurer.length }
            if (len > 0f && remaining > 0f) {
                val sliceEnd = minOf(len, remaining)
                measurer.getSegment(0f, sliceEnd, builder, true)
                val pos = measurer.getPosition(sliceEnd)
                val tan = measurer.getTangent(sliceEnd)
                if (pos != null && tan != null) {
                    tipPose = PathTipPose(
                        position = Offset(pos.x, pos.y),
                        tangent = Offset(tan.x, tan.y),
                    )
                }
                remaining -= sliceEnd
            }
            contourIndex++
        } while (remaining > 0f && measurer.nextContour())
    }

    val built = builder.detach().asComposePath()
    destination.addPath(built)
    return tipPose
}
