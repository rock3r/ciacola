package dev.sebastiano.deck

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.VertexMode
import androidx.compose.ui.graphics.Vertices
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.scale
import kotlin.math.PI
import kotlin.math.sin

data class MeshPlan(
    val positions: List<Offset>,
    val colors: List<Color>,
    val indices: List<Int>,
)

data class MeshLoopState(val sceneState: Int)

fun animatedMeshPoints(state: MeshLoopState, elapsedMillis: Long): List<List<Pair<Offset, Color>>> {
    require(state.sceneState == 0) { "the foundation loop intentionally has exactly one scene state" }
    val phase = (elapsedMillis % 6_000L) / 6_000f * (2f * PI.toFloat())
    val drift = sin(phase) * 0.10f
    val counterDrift = sin(phase + PI.toFloat() / 2f) * 0.08f
    return listOf(
        listOf(
            Offset(0f, 0f) to Color(0xFF121726),
            Offset(0.5f + drift, 0f) to Color(0xFF263B70),
            Offset(1f, 0f) to Color(0xFF161522),
        ),
        listOf(
            Offset(0f, 0.5f + counterDrift) to Color(0xFF4F2246),
            Offset(0.5f - drift, 0.5f + drift) to DeckDesign.accent,
            Offset(1f, 0.5f - counterDrift) to Color(0xFF174F50),
        ),
        listOf(
            Offset(0f, 1f) to Color(0xFF11141D),
            Offset(0.5f + counterDrift, 1f) to DeckDesign.secondary,
            Offset(1f, 1f) to Color(0xFF12131A),
        ),
    )
}

fun planMesh(points: List<List<Pair<Offset, Color>>>): MeshPlan {
    require(points.size >= 2) { "mesh requires at least two rows" }
    val columns = points.first().size
    require(columns >= 2 && points.all { it.size == columns }) { "mesh rows must have equal width >= 2" }

    val positions = points.flatten().map { it.first }
    val colors = points.flatten().map { it.second }
    val indices = buildList {
        for (row in 0 until points.lastIndex) {
            for (column in 0 until columns - 1) {
                val topLeft = row * columns + column
                val topRight = topLeft + 1
                val bottomLeft = (row + 1) * columns + column
                val bottomRight = bottomLeft + 1
                addAll(listOf(topLeft, bottomLeft, bottomRight, topLeft, bottomRight, topRight))
            }
        }
    }
    return MeshPlan(positions, colors, indices)
}

@Composable
fun AnimatedMeshBackdrop(modifier: Modifier = Modifier) {
    val loop = rememberInfiniteTransition("foundation-mesh-loop")
    val elapsed by loop.animateFloat(
        initialValue = 0f,
        targetValue = 6_000f,
        animationSpec = infiniteRepeatable(tween(6_000), RepeatMode.Restart),
        label = "mesh-time",
    )
    val mesh = planMesh(animatedMeshPoints(MeshLoopState(sceneState = 0), elapsed.toLong()))
    val paint = Paint()

    androidx.compose.foundation.layout.Box(
        modifier.drawBehind {
            drawIntoCanvas { canvas ->
                scale(size.width, size.height, Offset.Zero) {
                    canvas.drawVertices(
                        Vertices(
                            vertexMode = VertexMode.Triangles,
                            positions = mesh.positions,
                            textureCoordinates = mesh.positions,
                            colors = mesh.colors,
                            indices = mesh.indices,
                        ),
                        // drawVertices combines vertex colors with Paint; Dst retains the vertex
                        // color field instead of covering it with Paint's default solid color.
                        BlendMode.Dst,
                        paint,
                    )
                }
            }
        }
    )
}
