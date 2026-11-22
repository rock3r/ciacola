package dev.sebastiano.ciacola

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import dev.sebastiano.ciacola.theme.DeckTheme
import dev.sebastiano.ciacola.theme.DeckThemes
import kotlin.math.PI
import kotlin.math.sin

data class MeshPlan(
    val positions: List<Offset>,
    val colors: List<Color>,
    val indices: List<Int>,
)

data class MeshLoopState(val sceneState: Int)

fun animatedMeshPoints(
    state: MeshLoopState,
    elapsedMillis: Long,
    theme: DeckTheme = DeckThemes.stageDark(),
): List<List<Pair<Offset, Color>>> {
    require(state.sceneState == 0) { "the foundation loop intentionally has exactly one scene state" }
    val phase = (elapsedMillis % 6_000L) / 6_000f * (2f * PI.toFloat())
    val drift = sin(phase) * 0.10f
    val counterDrift = sin(phase + PI.toFloat() / 2f) * 0.08f
    return if (theme.isLight) {
        val base = theme.colors.background
        val surface = theme.colors.surface
        listOf(
            listOf(
                Offset(0f, 0f) to lerp(base, surface, 0.35f),
                Offset(0.5f + drift, 0f) to Color(0xFFD6E2F5),
                Offset(1f, 0f) to Color(0xFFECE6EE),
            ),
            listOf(
                Offset(0f, 0.5f + counterDrift) to lerp(surface, theme.colors.accent, 0.22f),
                Offset(0.5f - drift, 0.5f + drift) to lerp(surface, theme.colors.accent, 0.34f),
                Offset(1f, 0.5f - counterDrift) to lerp(surface, theme.colors.secondary, 0.26f),
            ),
            listOf(
                Offset(0f, 1f) to lerp(base, Color(0xFFDDE3EC), 0.55f),
                Offset(0.5f + counterDrift, 1f) to lerp(surface, theme.colors.secondary, 0.32f),
                Offset(1f, 1f) to lerp(base, surface, 0.50f),
            ),
        )
    } else {
        listOf(
            listOf(
                Offset(0f, 0f) to Color(0xFF121726),
                Offset(0.5f + drift, 0f) to Color(0xFF263B70),
                Offset(1f, 0f) to Color(0xFF161522),
            ),
            listOf(
                Offset(0f, 0.5f + counterDrift) to Color(0xFF4F2246),
                Offset(0.5f - drift, 0.5f + drift) to theme.colors.accent,
                Offset(1f, 0.5f - counterDrift) to Color(0xFF174F50),
            ),
            listOf(
                Offset(0f, 1f) to Color(0xFF11141D),
                Offset(0.5f + counterDrift, 1f) to theme.colors.secondary,
                Offset(1f, 1f) to Color(0xFF12131A),
            ),
        )
    }
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
