package dev.sebastiano.ciacola.decks

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.sp
import dev.sebastiano.ciacola.document.DeckSpecimenRegistry
import dev.sebastiano.ciacola.document.RenderPurpose
import dev.sebastiano.ciacola.document.deckSpecimens
import dev.sebastiano.ciacola.effects.DottedLeaderRow
import dev.sebastiano.ciacola.planMesh
import dev.sebastiano.ciacola.theme.DeckTheme
import kotlin.math.PI
import kotlin.math.sin

const val FOUNDATION_AMBIENT_MESH_REF: String = "foundation.ambientMesh"
const val FOUNDATION_DOTTED_LEADER_ROW_REF: String = "foundation.dottedLeaderRow"
const val FOUNDATION_AMBIENT_MESH_PERIOD_MS: Long = 6_000L

/**
 * Static [DeckSpecimenRegistry] for `foundation.deck.json`.
 *
 * Every animated specimen reads time exclusively from [SpecimenScope] (`loop` / `track`) so
 * Present, Still, Video, and scrub renders produce identical frames without wall-clock coupling.
 */
val foundationSpecimens: DeckSpecimenRegistry = deckSpecimens {
    specimen(FOUNDATION_AMBIENT_MESH_REF, label = "Ambient Vertex Mesh") {
        val time = loop(
            name = "time",
            label = "Ambient Vertex Mesh",
            periodMs = FOUNDATION_AMBIENT_MESH_PERIOD_MS,
        )
        val driftScale = uniformFloat(
            name = "driftScale",
            default = 1f,
            range = 0f..2f,
            step = 0.05f,
            label = "Drift Scale",
        )

        content {
            val phaseRad = if (reducedMotion) 0f else time.phaseRadians()
            val scaleFactor = driftScale()
            val points = scaledMeshPoints(phaseRad, scaleFactor, theme)
            val mesh = planMesh(points)
            val paint = Paint()

            ProvideSurfaceBackground(theme.colors.background) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .let { if (purpose == RenderPurpose.Present) it.stageHitRegion() else it }
                        .drawBehind {
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
                                        BlendMode.Dst,
                                        paint,
                                    )
                                }
                            }
                        },
                )
            }
        }
    }

    specimen(FOUNDATION_DOTTED_LEADER_ROW_REF, label = "Dotted Leader Row") {
        val leading = string(
            name = "leading",
            default = "Label",
            label = "Leading Label",
        )
        val trailing = string(
            name = "trailing",
            default = "Code",
            label = "Trailing Code",
        )
        val highlight = float(
            name = "highlight",
            default = 0f,
            range = 0f..1f,
            step = 0.05f,
            label = "Highlight",
        )
        val trailingSizeSp = float(
            name = "trailingSizeSp",
            default = 17f,
            range = 10f..40f,
            step = 0.5f,
            label = "Trailing Font Size (sp)",
        )
        val trailingLineHeightSp = float(
            name = "trailingLineHeightSp",
            default = 24f,
            range = 12f..56f,
            step = 0.5f,
            label = "Trailing Line Height (sp)",
        )

        content {
            val surfaceBg = effectiveBackground ?: theme.colors.background
            val highlightAmount = highlight().coerceIn(0f, 1f)
            val trailingStyle = theme.typography.code.copy(
                fontSize = trailingSizeSp().sp,
                lineHeight = trailingLineHeightSp().sp,
            )
            ProvideSurfaceBackground(surfaceBg) {
                DottedLeaderRow(
                    leading = leading(),
                    trailing = trailing(),
                    trailingStyle = trailingStyle,
                    highlightProgress = { highlightAmount },
                    modifier = Modifier
                        .fillMaxWidth()
                        .let { if (purpose == RenderPurpose.Present) it.stageHitRegion() else it },
                )
            }
        }
    }
}

private fun scaledMeshPoints(
    phaseRadians: Float,
    driftScale: Float,
    theme: DeckTheme,
): List<List<Pair<Offset, Color>>> {
    val drift = sin(phaseRadians) * 0.10f * driftScale
    val counterDrift = sin(phaseRadians + PI.toFloat() / 2f) * 0.08f * driftScale
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
