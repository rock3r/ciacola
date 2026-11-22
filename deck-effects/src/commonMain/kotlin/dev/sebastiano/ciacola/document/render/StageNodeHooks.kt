package dev.sebastiano.ciacola.document.render

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.layout.ApproachLayoutModifierNode
import androidx.compose.ui.layout.ApproachMeasureScope
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.node.invalidateMeasurement
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import dev.sebastiano.ciacola.document.NodeKind
import dev.sebastiano.ciacola.document.NodeSpec
import dev.sebastiano.ciacola.effects.LocalStageAnchorRegistry
import dev.sebastiano.ciacola.effects.resolveStageBounds
import dev.sebastiano.ciacola.inspector.LocalStageInspectionRegistry
import dev.sebastiano.ciacola.inspector.LocalStageParentElementId
import dev.sebastiano.ciacola.inspector.LocalStageRootCoordinates
import dev.sebastiano.ciacola.inspector.StageElementMetadata
import dev.sebastiano.ciacola.inspector.StageNodeSnapshot

internal val LocalIsGhostSubtree = compositionLocalOf { false }

/**
 * Registers a visible stage node (or active `Overlay` travelling node) in both
 * [LocalStageAnchorRegistry] and [LocalStageInspectionRegistry] using a per-mount [ownerToken]
 * and cleans up on exit. Ghost subtrees pass `enabled = false` so ghosts never collide with
 * connectors or Studio inspection.
 */
@Composable
internal fun rememberStageNodeHooksModifier(
    nodeId: String,
    node: NodeSpec,
    sceneIndex: Int,
    beatIndex: Int,
    effectiveAlphaProvider: () -> Float,
    enabled: Boolean,
): Modifier {
    if (!enabled) return Modifier
    val ownerToken = remember(nodeId) { Any() }
    val anchorRegistry = LocalStageAnchorRegistry.current
    val inspectionRegistry = LocalStageInspectionRegistry.current
    val stageRootHolder = LocalStageRootCoordinates.current
    val parentElementId = LocalStageParentElementId.current?.takeIf { it != nodeId }
    val placedCoordsHolder = remember(nodeId) { object { var coords: LayoutCoordinates? = null } }

    val isAnchorCandidate = node.kind != NodeKind.Connector
    if (anchorRegistry != null && isAnchorCandidate) {
        DisposableEffect(anchorRegistry, nodeId) {
            onDispose { anchorRegistry.remove(nodeId, ownerToken) }
        }
    }
    if (inspectionRegistry != null) {
        DisposableEffect(inspectionRegistry, nodeId) {
            onDispose { inspectionRegistry.remove(nodeId, ownerToken) }
        }
        SideEffect {
            val bounds = resolveStageBounds(placedCoordsHolder.coords, stageRootHolder)
            if (bounds != null) {
                inspectionRegistry.recordMetadata(
                    meta = StageElementMetadata(
                        elementId = nodeId,
                        supportsText = node.text != null,
                        supportsBoxStyle = node.stack != null || node.row != null || node.column != null || node.shape != null,
                        defaultText = node.text?.value?.plainText.orEmpty(),
                    ),
                    owner = ownerToken,
                )
                inspectionRegistry.record(
                    snapshot = StageNodeSnapshot(
                        elementId = nodeId,
                        kind = node.kind?.payloadKey ?: "node",
                        label = node.text?.value?.plainText?.lineSequence()?.firstOrNull()?.take(48)
                            ?: node.specimen?.ref
                            ?: node.id,
                        sceneIndex = sceneIndex,
                        beatIndex = beatIndex,
                        stageBounds = bounds,
                        resolvedText = node.text?.value?.plainText.orEmpty(),
                        effectiveAlpha = effectiveAlphaProvider(),
                        parentElementId = parentElementId,
                    ),
                    owner = ownerToken,
                    alphaProvider = effectiveAlphaProvider,
                    coordinatesProvider = { placedCoordsHolder.coords },
                    stageRootProvider = { stageRootHolder?.coordinates },
                )
            }
        }
    }

    if ((!isAnchorCandidate || anchorRegistry == null) && inspectionRegistry == null) return Modifier
    return Modifier.onPlaced { coords ->
        placedCoordsHolder.coords = coords
        if (isAnchorCandidate) {
            anchorRegistry?.record(nodeId, coords, ownerToken)
        }
        if (inspectionRegistry != null) {
            val bounds = resolveStageBounds(coords, stageRootHolder)
            if (bounds != null) {
                inspectionRegistry.recordMetadata(
                    meta = StageElementMetadata(
                        elementId = nodeId,
                        supportsText = node.text != null,
                        supportsBoxStyle = node.stack != null || node.row != null || node.column != null || node.shape != null,
                        defaultText = node.text?.value?.plainText.orEmpty(),
                    ),
                    owner = ownerToken,
                )
                inspectionRegistry.record(
                    snapshot = StageNodeSnapshot(
                        elementId = nodeId,
                        kind = node.kind?.payloadKey ?: "node",
                        label = node.text?.value?.plainText?.lineSequence()?.firstOrNull()?.take(48)
                            ?: node.specimen?.ref
                            ?: node.id,
                        sceneIndex = sceneIndex,
                        beatIndex = beatIndex,
                        stageBounds = bounds,
                        resolvedText = node.text?.value?.plainText.orEmpty(),
                        effectiveAlpha = effectiveAlphaProvider(),
                        parentElementId = parentElementId,
                    ),
                    owner = ownerToken,
                    alphaProvider = effectiveAlphaProvider,
                    coordinatesProvider = { placedCoordsHolder.coords },
                    stageRootProvider = { stageRootHolder?.coordinates },
                )
            }
        }
    }
}

/**
 * Invalidates an overlay travelling node's measurement and graphics layer synchronously whenever
 * `SceneA` or `SceneB` mutates an entry in [CrossSlideLookaheadGeometryStore] mid-`Cross(A, B)`,
 * preventing stale overlay geometry without introducing Compose snapshot-state recomposition loops.
 */
internal fun Modifier.trackLookaheadGeometry(
    store: CrossSlideLookaheadGeometryStore?,
): Modifier = if (store == null) this else this.then(TrackLookaheadGeometryElement(store))

private data class TrackLookaheadGeometryElement(
    val store: CrossSlideLookaheadGeometryStore,
) : ModifierNodeElement<TrackLookaheadGeometryNode>() {
    override fun create(): TrackLookaheadGeometryNode = TrackLookaheadGeometryNode(store)
    override fun update(node: TrackLookaheadGeometryNode) {
        node.updateStore(store)
    }
    override fun InspectorInfo.inspectableProperties() {
        name = "trackLookaheadGeometry"
    }
}

private class TrackLookaheadGeometryNode(
    private var store: CrossSlideLookaheadGeometryStore,
) : Modifier.Node(), LayoutModifierNode, DrawModifierNode {
    private var unregister: (() -> Unit)? = null

    private fun register() {
        unregister?.invoke()
        unregister = store.registerOverlayInvalidator {
            if (isAttached) {
                invalidateMeasurement()
                invalidateDraw()
            }
        }
    }

    fun updateStore(next: CrossSlideLookaheadGeometryStore) {
        if (store !== next) {
            store = next
            if (isAttached) register()
        }
    }

    override fun onAttach() {
        register()
    }

    override fun onDetach() {
        unregister?.invoke()
        unregister = null
    }

    override fun MeasureScope.measure(
        measurable: Measurable,
        constraints: Constraints,
    ): MeasureResult {
        val placeable = measurable.measure(constraints)
        return layout(placeable.width, placeable.height) {
            placeable.place(0, 0)
        }
    }

    override fun ContentDrawScope.draw() {
        drawContent()
    }
}

/**
 * Synchronous `Lookahead` + `Approach` constraint applicator backed by
 * [CrossSlideLookaheadGeometryStore].
 *
 * Applies [resolveConstraints] in both `Approach` (`approachMeasure`) and steady-state `Lookahead`
 * (`measure`) once endpoint geometries are populated, while signaling
 * `isMeasurementApproachInProgress` whenever [CrossSlideLookaheadGeometryStore.version],
 * [progressKey], or `lookaheadSize` changes. This ensures zero 1-frame lag on Frame 0 or mid-`Cross`
 * layout mutations and zero infinite invalidations at steady state (`hasInvalidations() == false`).
 */
internal fun Modifier.approachSlideConstraints(
    store: CrossSlideLookaheadGeometryStore?,
    progressKey: Float,
    resolveConstraints: (Constraints) -> Constraints,
): Modifier = this.then(ApproachSlideConstraintsElement(store, progressKey, resolveConstraints))

private data class ApproachSlideConstraintsElement(
    val store: CrossSlideLookaheadGeometryStore?,
    val progressKey: Float,
    val resolveConstraints: (Constraints) -> Constraints,
) : ModifierNodeElement<ApproachSlideConstraintsNode>() {
    override fun create(): ApproachSlideConstraintsNode =
        ApproachSlideConstraintsNode(store, progressKey, resolveConstraints)

    override fun update(node: ApproachSlideConstraintsNode) {
        node.store = store
        node.progressKey = progressKey
        node.resolveConstraints = resolveConstraints
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "approachSlideConstraints"
    }
}

private class ApproachSlideConstraintsNode(
    var store: CrossSlideLookaheadGeometryStore?,
    var progressKey: Float,
    var resolveConstraints: (Constraints) -> Constraints,
) : Modifier.Node(), ApproachLayoutModifierNode {
    private var appliedVersion: Long = Long.MIN_VALUE
    private var appliedProgress: Float = Float.NaN
    private var appliedWidth: Int = -1
    private var appliedHeight: Int = -1

    override fun isMeasurementApproachInProgress(lookaheadSize: IntSize): Boolean {
        val ver = store?.version ?: -1L
        return ver != appliedVersion ||
            progressKey != appliedProgress ||
            lookaheadSize.width != appliedWidth ||
            lookaheadSize.height != appliedHeight
    }

    override fun MeasureScope.measure(
        measurable: Measurable,
        constraints: Constraints,
    ): MeasureResult {
        val resolved = resolveConstraints(constraints)
        val placeable = measurable.measure(resolved)
        return layout(placeable.width, placeable.height) {
            placeable.place(0, 0)
        }
    }

    override fun ApproachMeasureScope.approachMeasure(
        measurable: Measurable,
        constraints: Constraints,
    ): MeasureResult {
        val resolved = resolveConstraints(constraints)
        val placeable = measurable.measure(resolved)
        appliedVersion = store?.version ?: -1L
        appliedProgress = progressKey
        appliedWidth = placeable.width
        appliedHeight = placeable.height
        return layout(placeable.width, placeable.height) {
            placeable.place(0, 0)
        }
    }
}
