package dev.sebastiano.ciacola.document.render

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import dev.sebastiano.ciacola.document.Affine2D
import dev.sebastiano.ciacola.document.DecomposedTransform

/**
 * Lookahead-captured endpoint geometry for a present node on a slide.
 *
 * Captured synchronously during Compose's Lookahead pass (`isLookingAhead == true`) inside
 * `SceneA` and `SceneB` so `Overlay` consumes exact resting coordinates, dimensions, stage
 * affines, and text wrapping widths on the very same frame (zero 1-frame lag).
 */
@Immutable
internal data class MeasuredEndpointGeometry(
    val slideId: String,
    val nodeId: String,
    val localX: Double,
    val localY: Double,
    val widthPx: Int,
    val heightPx: Int,
    val stageAffine: Affine2D,
    val decomposed: DecomposedTransform,
    val measuredTextMinWidthPx: Int = 0,
    val measuredTextMaxWidthPx: Int = Int.MAX_VALUE,
    val measuredTextLayoutWidthPx: Int = 0,
    val surfaceHorizontalPadPx: Int = 0,
)

/**
 * Mount-local plain (non-Compose-state) store holding per-slide Lookahead endpoint geometries.
 *
 * Intentionally avoids Compose snapshot state so writes during Lookahead placement never trigger
 * recomposition loops; downstream `approachLayout`, `graphicsLayer`, and deferred `TextNode`
 * lambdas read directly from this store during the subsequent Approach/Main pass of the same frame.
 */
internal class CrossSlideLookaheadGeometryStore {
    private val bySlide = HashMap<String, HashMap<String, MeasuredEndpointGeometry>>()
    private val overlayInvalidators = LinkedHashSet<() -> Unit>()

    var version: Long = 0L
        private set

    fun registerOverlayInvalidator(invalidator: () -> Unit): () -> Unit {
        overlayInvalidators += invalidator
        return { overlayInvalidators -= invalidator }
    }

    private fun notifyOverlayInvalidators() {
        if (overlayInvalidators.isNotEmpty()) {
            for (inv in overlayInvalidators.toList()) {
                inv()
            }
        }
    }

    fun remove(slideId: String, nodeId: String) {
        val slideMap = bySlide[slideId] ?: return
        if (slideMap.remove(nodeId) != null) {
            if (slideMap.isEmpty()) bySlide.remove(slideId)
            version++
            notifyOverlayInvalidators()
        }
    }

    fun put(geometry: MeasuredEndpointGeometry) {
        val slideMap = bySlide.getOrPut(geometry.slideId) { HashMap() }
        val prev = slideMap.put(geometry.nodeId, geometry)
        if (prev != geometry) {
            version++
            notifyOverlayInvalidators()
        }
    }

    fun get(slideId: String, nodeId: String): MeasuredEndpointGeometry? =
        bySlide[slideId]?.get(nodeId)
}

/**
 * Plain per-node mutable holder capturing `MagicTextV2Renderer`'s exact Lookahead constraint width
 * and measured paragraph width without allocating during measure/layout.
 */
internal class LookaheadTextMetricsHolder {
    var constraintMinWidthPx: Int = 0
    var constraintMaxWidthPx: Int = Int.MAX_VALUE
    var layoutWidthPx: Int = 0
    var surfaceHorizontalPadPx: Int = 0
}

internal val LocalLookaheadTextMetricsHolder = compositionLocalOf<LookaheadTextMetricsHolder?> { null }

internal val LocalCrossSlideLookaheadGeometryStore =
    compositionLocalOf<CrossSlideLookaheadGeometryStore?> { null }
