package dev.sebastiano.ciacola.document.render

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import dev.sebastiano.ciacola.document.Affine2D
import dev.sebastiano.ciacola.document.CrossSlideGeometry
import dev.sebastiano.ciacola.document.DecomposedTransform
import dev.sebastiano.ciacola.document.Fill
import dev.sebastiano.ciacola.document.JsonMergePatch
import dev.sebastiano.ciacola.document.NodeKind
import dev.sebastiano.ciacola.document.NodeSpec
import dev.sebastiano.ciacola.document.SegmentPlan
import dev.sebastiano.ciacola.document.SlideSpec
import dev.sebastiano.ciacola.document.TransitionSpec
import dev.sebastiano.ciacola.effects.MagnifierLoupeStage
import dev.sebastiano.ciacola.inspector.LocalStageParentElementId
import kotlin.math.roundToInt

/**
 * Renders an unmatched (exiting-only or entering-only) static overlay subtree inside
 * `doc-transition-overlay`, both for root-level unmatched nodes ([DocumentStaticRootOverlayNode])
 * and for unmatched children inside matched travelling containers.
 */
@Composable
internal fun RenderStaticOverlaySubtree(
    node: NodeSpec,
    slide: SlideSpec,
    restingBeat: Int,
    isFromSide: Boolean,
    transitionSpec: TransitionSpec,
    toSlideTransition: TransitionSpec?,
    ctx: DocumentNodeRenderContext,
    maxWidthPx: Int,
    modifier: Modifier,
    suppressWidthAxis: Boolean = false,
    suppressHeightAxis: Boolean = false,
    parentContentCoordsHolder: Array<LayoutCoordinates?>? = null,
    parentNodeId: String? = null,
    parentPadLeftPx: Double = 0.0,
    parentPadTopPx: Double = 0.0,
    effectiveAlpha: Float = 1f,
    hooksEnabled: Boolean = true,
    attachSelfHooks: Boolean = false,
    rawNode: NodeSpec = node,
) {
    val density = LocalDensity.current
    if (node.id in ctx.suppressedNodeIds) {
        // Descendant is owned by an active travelling MatchedPair in doc-transition-overlay;
        // preserve its exact Lookahead dimensions (except along a weighted main axis) and refresh
        // lookaheadStore ONLY when parent affine & attached coordinates are valid (never overwrite with (0, 0)!).
        val lookaheadStore = ctx.lookaheadStore
        val geo = lookaheadStore?.get(slide.id, node.id)
        val slotPool = LocalMovableSpecimenSlotPool.current
        val lastKnown = if (geo == null && node.kind == NodeKind.Specimen) slotPool?.lastKnownSize(slide.id, node.id) else null
        val rawW = if (suppressWidthAxis) null else (geo?.widthPx ?: lastKnown?.width ?: node.frame?.w?.roundToInt())
        val rawH = if (suppressHeightAxis) null else (geo?.heightPx ?: lastKnown?.height ?: node.frame?.h?.roundToInt())
        val ghostW = if (rawW == 0 && node.frame?.w == null && containsUnsizedZeroSpecimen(node, slide.id, slotPool)) null else rawW
        val ghostH = if (rawH == 0 && node.frame?.h == null && containsUnsizedZeroSpecimen(node, slide.id, slotPool)) null else rawH
        var ghostMod = modifier
            .let { if (ghostW != null) it.requiredWidth(ghostW.toDouble().toStageDp(density)) else it }
            .let { if (ghostH != null) it.requiredHeight(ghostH.toDouble().toStageDp(density)) else it }
        if (lookaheadStore != null && parentNodeId != null) {
            ghostMod = ghostMod.layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                val wPx = placeable.width
                val hPx = placeable.height
                val lookingAhead = isLookingAhead
                layout(wPx, hPx) {
                    if (lookingAhead) {
                        val coords = coordinates
                        val parentCoords = parentContentCoordsHolder?.get(0)
                        val parentEntry = lookaheadStore.get(slide.id, parentNodeId)
                        if (parentEntry != null &&
                            coords != null &&
                            parentCoords != null &&
                            coords.isAttached &&
                            parentCoords.isAttached
                        ) {
                            val localOffset = parentCoords.localPositionOf(coords, Offset.Zero)
                            val parentAffine = parentEntry.stageAffine
                            val withPad = if (parentPadLeftPx != 0.0 || parentPadTopPx != 0.0) {
                                parentAffine * Affine2D.translation(parentPadLeftPx, parentPadTopPx)
                            } else {
                                parentAffine
                            }
                            val stageAffine = withPad * CrossSlideGeometry.nodeLocalTransform(
                                node = node,
                                x = localOffset.x.toDouble(),
                                y = localOffset.y.toDouble(),
                                w = wPx.toDouble(),
                                h = hPx.toDouble(),
                            )
                            val decomposed = stageAffine.decompose() ?: DecomposedTransform(
                                tx = stageAffine.tx,
                                ty = stageAffine.ty,
                                rotationDeg = 0.0,
                                scaleX = 1.0,
                                scaleY = 1.0,
                            )
                            val existing = lookaheadStore.get(slide.id, node.id)
                            lookaheadStore.put(
                                MeasuredEndpointGeometry(
                                    slideId = slide.id,
                                    nodeId = node.id,
                                    localX = localOffset.x.toDouble(),
                                    localY = localOffset.y.toDouble(),
                                    widthPx = wPx,
                                    heightPx = hPx,
                                    stageAffine = stageAffine,
                                    decomposed = decomposed,
                                    measuredTextMinWidthPx = existing?.measuredTextMinWidthPx ?: 0,
                                    measuredTextMaxWidthPx = existing?.measuredTextMaxWidthPx ?: Int.MAX_VALUE,
                                    measuredTextLayoutWidthPx = existing?.measuredTextLayoutWidthPx ?: 0,
                                    surfaceHorizontalPadPx = existing?.surfaceHorizontalPadPx ?: 0,
                                ),
                            )
                        }
                    }
                    placeable.place(0, 0)
                }
            }
        }
        Box(modifier = ghostMod)
        return
    }
    val rawNodeAlpha = (effectiveAlpha * node.opacity.toFloat()).coerceIn(0f, 1f)
    val nodeAlpha = if (rawNodeAlpha >= 0.9999f) 1f else rawNodeAlpha
    val effectiveMod = if (attachSelfHooks && hooksEnabled) {
        modifier.then(
            rememberStageNodeHooksModifier(
                nodeId = node.id,
                node = node,
                sceneIndex = ctx.sceneIndex,
                beatIndex = ctx.beatIndex,
                effectiveAlphaProvider = { nodeAlpha },
                enabled = true,
            ),
        )
    } else {
        modifier
    }
    when (node.kind) {
        NodeKind.Text -> {
            val payload = node.text ?: return
            val charDelivery = if (isFromSide) {
                DocumentMotionEvaluator.isCharacterDeliveryNode(node, transitionSpec)
            } else {
                DocumentMotionEvaluator.isCharacterDeliveryNode(node, toSlideTransition, transitionSpec)
            }
            val restState = DocumentTextRenderState.Rest(
                payload = payload,
                characterDeliveryNode = charDelivery,
            )
            DocumentTextPayloadView(
                renderState = { restState },
                surface = payload.surface,
                document = ctx.document,
                theme = ctx.theme,
                colorResolver = ctx.colorResolver,
                fontResolverHandle = ctx.fontResolverHandle,
                density = density,
                reducedMotion = ctx.reducedMotion,
                deckId = ctx.document.id,
                assetsRootDir = ctx.config.assetsRootDir,
                maxWidthPx = node.frame?.w?.roundToInt() ?: maxWidthPx,
                modifier = effectiveMod,
            )
        }
        NodeKind.Shape -> DocumentShapePayloadView(
            payload = node.shape!!,
            colorResolver = ctx.colorResolver,
            density = density,
            deckId = ctx.document.id,
            assetsRootDir = ctx.config.assetsRootDir,
            pathTrim = null,
            modifier = effectiveMod,
            shapeReferenceSize = resolveAuthoredShapeReferenceSize(
                rawNode = rawNode,
                resolvedNode = node,
                upToBeat = restingBeat.coerceAtLeast(0),
                resolveNodeAfterBuild = ctx.resolveNodeAfterBuild,
            ),
        )
        NodeKind.Image -> {
            val img = node.image!!
            DocumentImagePayloadView(
                deckId = ctx.document.id,
                src = img.src,
                fit = img.fit ?: "contain",
                tint = img.tint?.let { ctx.colorResolver.resolveOrNull(it) },
                assetsRootDir = ctx.config.assetsRootDir,
                modifier = effectiveMod,
            )
        }
        NodeKind.Connector -> DocumentConnectorPayloadView(
            payload = node.connector!!,
            colorResolver = ctx.colorResolver,
            density = density,
            pathTrim = null,
            modifier = effectiveMod,
        )
        NodeKind.Code -> WarpCodeAdapter(
            rawNode = node,
            resolvedNode = node,
            slide = slide,
            frameTransition = null,
            segmentPlan = SegmentPlan.Empty,
            theme = ctx.theme,
            colorResolver = ctx.colorResolver,
            reducedMotion = ctx.reducedMotion,
            modifier = effectiveMod,
            fontResolverHandle = ctx.fontResolverHandle,
        )
        NodeKind.Specimen -> {
            val payload = node.specimen!!
            val decl = ctx.config.specimens.find(payload.ref)
            val overrides = payload.tracks
            DocumentSpecimenPayloadView(
                nodeId = node.id,
                interactive = node.interactive,
                payload = payload,
                fromParams = payload.params,
                toParams = null,
                paramProgress = if (isFromSide) 1f else 0f,
                trackProgress = { handle ->
                    val targetBeat = overrides[handle.name]?.beat
                        ?: decl?.tracks?.firstOrNull { it.name == handle.name }?.beat
                        ?: 0
                    if (restingBeat >= targetBeat) 1f else 0f
                },
                ctx = ctx,
                modifier = effectiveMod,
            )
        }
        NodeKind.Stack -> {
            val container = node.stack ?: return
            val pad = combinePadding(container.padding, container.surface?.padding)
            val contentCoordsHolder = remember(node.id) { arrayOfNulls<LayoutCoordinates>(1) }
            val containerMod = effectiveMod
                .let { if (container.clip) it.clipToBounds() else it }
                .applyDocumentSurfaceChrome(container.surface, ctx.colorResolver, density)
            val visibleChildren = node.children.filter {
                it.kind == NodeKind.Loupe || if (isFromSide) JsonMergePatch.isNodePresentAtBeat(it, restingBeat) else JsonMergePatch.isNodePresentAtStart(it)
            }
            val rawChildrenById = remember(rawNode) { rawNode.children.associateBy { it.id } }
            Box(modifier = containerMod, propagateMinConstraints = true) {
                DocumentContainerSurfaceImageFill(container.surface, ctx)
                CompositionLocalProvider(LocalStageParentElementId provides node.id) {
                    StaticOverlayStackChildren(
                        children = visibleChildren,
                        rawChildrenById = rawChildrenById,
                        defaultAlign = container.alignItems ?: "topStart",
                        slide = slide,
                        restingBeat = restingBeat,
                        isFromSide = isFromSide,
                        transitionSpec = transitionSpec,
                        toSlideTransition = toSlideTransition,
                        ctx = ctx,
                        modifier = Modifier.fillMaxSize().padding(pad.toPaddingValues(density)),
                        parentContentCoordsHolder = contentCoordsHolder,
                        parentNodeId = node.id,
                        parentPadLeftPx = pad.left,
                        parentPadTopPx = pad.top,
                        effectiveAlpha = nodeAlpha,
                        hooksEnabled = hooksEnabled,
                    )
                }
            }
        }
        NodeKind.Row, NodeKind.Column -> {
            val isRow = node.kind == NodeKind.Row
            val container = (if (isRow) node.row else node.column) ?: return
            val pad = combinePadding(container.padding, container.surface?.padding)
            val gapDp = (container.gap ?: 0.0).toStageDp(density)
            val contentCoordsHolder = remember(node.id) { arrayOfNulls<LayoutCoordinates>(1) }
            val containerMod = effectiveMod
                .let { if (container.clip) it.clipToBounds() else it }
                .applyDocumentSurfaceChrome(container.surface, ctx.colorResolver, density)
            val visibleChildren = node.children.filter {
                it.kind == NodeKind.Loupe || if (isFromSide) JsonMergePatch.isNodePresentAtBeat(it, restingBeat) else JsonMergePatch.isNodePresentAtStart(it)
            }
            val rawChildrenById = remember(rawNode) { rawNode.children.associateBy { it.id } }
            Box(modifier = containerMod, propagateMinConstraints = true) {
                DocumentContainerSurfaceImageFill(container.surface, ctx)
                CompositionLocalProvider(LocalStageParentElementId provides node.id) {
                    val innerMod = Modifier
                        .padding(pad.toPaddingValues(density))
                        .layout { measurable, constraints ->
                            val p = measurable.measure(constraints)
                            val lookingAhead = isLookingAhead
                            layout(p.width, p.height) {
                                if (lookingAhead) {
                                    contentCoordsHolder[0] = coordinates
                                }
                                p.place(0, 0)
                            }
                        }
                    if (isRow) {
                        Row(
                            modifier = innerMod,
                            horizontalArrangement = parseHorizontalArrangement(container.justify, gapDp),
                            verticalAlignment = parseVerticalAlignment(container.alignItems),
                        ) {
                            for (child in visibleChildren) {
                                key(child.id) {
                                    val w = child.weight
                                    val hasWeight = w != null && w > 0.0
                                    val wMod = if (hasWeight) Modifier.weight(w!!.toFloat()) else Modifier
                                    RenderStaticOverlaySubtree(
                                        node = child,
                                        rawNode = rawChildrenById[child.id] ?: child,
                                        slide = slide,
                                        restingBeat = restingBeat,
                                        isFromSide = isFromSide,
                                        transitionSpec = transitionSpec,
                                        toSlideTransition = toSlideTransition,
                                        ctx = ctx,
                                        maxWidthPx = Int.MAX_VALUE,
                                        modifier = staticOverlayNodeModifier(child, wMod, density, ctx),
                                        suppressWidthAxis = hasWeight,
                                        parentContentCoordsHolder = contentCoordsHolder,
                                        parentNodeId = node.id,
                                        parentPadLeftPx = pad.left,
                                        parentPadTopPx = pad.top,
                                        effectiveAlpha = nodeAlpha,
                                        hooksEnabled = hooksEnabled,
                                        attachSelfHooks = hooksEnabled,
                                    )
                                }
                            }
                        }
                    } else {
                        Column(
                            modifier = innerMod,
                            verticalArrangement = parseVerticalArrangement(container.justify, gapDp),
                            horizontalAlignment = parseHorizontalAlignment(container.alignItems),
                        ) {
                            for (child in visibleChildren) {
                                key(child.id) {
                                    val w = child.weight
                                    val hasWeight = w != null && w > 0.0
                                    val wMod = if (hasWeight) Modifier.weight(w!!.toFloat()) else Modifier
                                    RenderStaticOverlaySubtree(
                                        node = child,
                                        rawNode = rawChildrenById[child.id] ?: child,
                                        slide = slide,
                                        restingBeat = restingBeat,
                                        isFromSide = isFromSide,
                                        transitionSpec = transitionSpec,
                                        toSlideTransition = toSlideTransition,
                                        ctx = ctx,
                                        maxWidthPx = Int.MAX_VALUE,
                                        modifier = staticOverlayNodeModifier(child, wMod, density, ctx),
                                        suppressHeightAxis = hasWeight,
                                        parentContentCoordsHolder = contentCoordsHolder,
                                        parentNodeId = node.id,
                                        parentPadLeftPx = pad.left,
                                        parentPadTopPx = pad.top,
                                        effectiveAlpha = nodeAlpha,
                                        hooksEnabled = hooksEnabled,
                                        attachSelfHooks = hooksEnabled,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        NodeKind.Loupe -> {
            val loupe = node.loupe ?: return
            val diameterDp = loupe.diameter?.toStageDp(density) ?: ctx.theme.styles.loupe.diameter
            val lensBg = when (val fill = loupe.lensFill) {
                is Fill.Solid -> ctx.colorResolver.resolve(fill.color, Color.Unspecified)
                else -> Color.Unspecified
            }
            val isLensVisible = if (isFromSide) {
                JsonMergePatch.isNodePresentAtBeat(node, restingBeat)
            } else {
                JsonMergePatch.isNodePresentAtStart(node)
            }
            val visibleChildren = node.children.filter {
                it.kind == NodeKind.Loupe || if (isFromSide) JsonMergePatch.isNodePresentAtBeat(it, restingBeat) else JsonMergePatch.isNodePresentAtStart(it)
            }
            val rawChildrenById = remember(rawNode) { rawNode.children.associateBy { it.id } }
            MagnifierLoupeStage(
                targetKey = loupe.target,
                progress = { if (isLensVisible) 1f else 0f },
                modifier = effectiveMod.fillMaxSize(),
                loupeAlignment = parseBoxAlignment(loupe.align ?: "centerEnd"),
                loupeDiameter = diameterDp,
                zoom = loupe.zoom.toFloat(),
                lensBackgroundColor = lensBg,
            ) {
                val childCtx = ctx.copy(loupeDecorationScope = this, loupeTargetId = loupe.target)
                CompositionLocalProvider(LocalStageParentElementId provides node.id) {
                    StaticOverlayStackChildren(
                        children = visibleChildren,
                        rawChildrenById = rawChildrenById,
                        defaultAlign = "topStart",
                        slide = slide,
                        restingBeat = restingBeat,
                        isFromSide = isFromSide,
                        transitionSpec = transitionSpec,
                        toSlideTransition = toSlideTransition,
                        ctx = childCtx,
                        modifier = Modifier.fillMaxSize(),
                        effectiveAlpha = nodeAlpha,
                        hooksEnabled = hooksEnabled,
                    )
                }
            }
        }
        null -> Unit
    }
}

@Composable
private fun StaticOverlayStackChildren(
    children: List<NodeSpec>,
    rawChildrenById: Map<String, NodeSpec>,
    defaultAlign: String?,
    slide: SlideSpec,
    restingBeat: Int,
    isFromSide: Boolean,
    transitionSpec: TransitionSpec,
    toSlideTransition: TransitionSpec?,
    ctx: DocumentNodeRenderContext,
    modifier: Modifier,
    parentContentCoordsHolder: Array<LayoutCoordinates?>? = null,
    parentNodeId: String? = null,
    parentPadLeftPx: Double = 0.0,
    parentPadTopPx: Double = 0.0,
    effectiveAlpha: Float = 1f,
    hooksEnabled: Boolean = true,
) {
    val density = LocalDensity.current
    Layout(
        modifier = modifier,
        content = {
            for (child in children) {
                key(child.id) {
                    val childMod = staticOverlayNodeModifier(child, Modifier, density, ctx)
                    RenderStaticOverlaySubtree(
                        node = child,
                        rawNode = rawChildrenById[child.id] ?: child,
                        slide = slide,
                        restingBeat = restingBeat,
                        isFromSide = isFromSide,
                        transitionSpec = transitionSpec,
                        toSlideTransition = toSlideTransition,
                        ctx = ctx,
                        maxWidthPx = resolveStackChildMaxWidthPx(child, ctx.stageSize.width.roundToInt()),
                        modifier = childMod,
                        parentContentCoordsHolder = parentContentCoordsHolder,
                        parentNodeId = parentNodeId,
                        parentPadLeftPx = parentPadLeftPx,
                        parentPadTopPx = parentPadTopPx,
                        effectiveAlpha = effectiveAlpha,
                        hooksEnabled = hooksEnabled,
                        attachSelfHooks = hooksEnabled,
                    )
                }
            }
        },
    ) { measurables, constraints ->
        val lookingAhead = isLookingAhead
        val boxW = if (constraints.hasBoundedWidth) constraints.maxWidth else ctx.stageSize.width.roundToInt()
        val boxH = if (constraints.hasBoundedHeight) constraints.maxHeight else ctx.stageSize.height.roundToInt()
        val placeables = ArrayList<Placeable>(measurables.size)
        val positions = ArrayList<Pair<Int, Int>>(measurables.size)
        for (i in measurables.indices) {
            val child = children[i]
            val frame = child.frame
            val hasExplicitPos = frame?.x != null || frame?.y != null
            val xPx = (frame?.x ?: 0.0).roundToInt()
            val yPx = (frame?.y ?: 0.0).roundToInt()
            val wPx = frame?.w?.roundToInt()?.coerceAtLeast(0)
            val hPx = frame?.h?.roundToInt()?.coerceAtLeast(0)
            val childMaxW = when {
                wPx != null -> wPx
                hasExplicitPos -> (boxW - xPx).coerceAtLeast(0)
                else -> boxW
            }
            val childMaxH = when {
                hPx != null -> hPx
                hasExplicitPos -> (boxH - yPx).coerceAtLeast(0)
                else -> boxH
            }
            val placeable = measurables[i].measure(
                Constraints(
                    minWidth = wPx ?: 0,
                    maxWidth = childMaxW.coerceAtLeast(wPx ?: 0),
                    minHeight = hPx ?: 0,
                    maxHeight = childMaxH.coerceAtLeast(hPx ?: 0),
                ),
            )
            placeables += placeable
            if (hasExplicitPos) {
                positions += (xPx to yPx)
            } else {
                val align = parseBoxAlignment(child.align ?: defaultAlign)
                val offset = align.align(
                    size = IntSize(placeable.width, placeable.height),
                    space = IntSize(boxW, boxH),
                    layoutDirection = layoutDirection,
                )
                positions += (offset.x to offset.y)
            }
        }
        val outW = if (constraints.hasBoundedWidth) constraints.maxWidth else {
            placeables.indices.maxOfOrNull { positions[it].first + placeables[it].width }
                ?.coerceIn(constraints.minWidth, constraints.maxWidth) ?: constraints.minWidth
        }
        val outH = if (constraints.hasBoundedHeight) constraints.maxHeight else {
            placeables.indices.maxOfOrNull { positions[it].second + placeables[it].height }
                ?.coerceIn(constraints.minHeight, constraints.maxHeight) ?: constraints.minHeight
        }
        layout(outW, outH) {
            if (lookingAhead && parentContentCoordsHolder != null) {
                parentContentCoordsHolder[0] = coordinates
            }
            for (i in placeables.indices) {
                val (px, py) = positions[i]
                placeables[i].place(px, py)
            }
        }
    }
}

private fun staticOverlayNodeModifier(
    node: NodeSpec,
    base: Modifier,
    density: Density,
    ctx: DocumentNodeRenderContext,
): Modifier {
    var mod = base
    val fw = node.frame?.w
    val fh = node.frame?.h
    if (fw != null) mod = mod.requiredWidth(fw.toStageDp(density))
    if (fh != null) mod = mod.requiredHeight(fh.toStageDp(density))
    val scope = ctx.loupeDecorationScope
    if (scope != null && ctx.loupeTargetId == node.id) {
        mod = with(scope) { mod.decorate(node.id) }
    }
    val tx = (node.offset?.x ?: 0.0).toFloat()
    val ty = (node.offset?.y ?: 0.0).toFloat()
    val rot = node.rotation.toFloat()
    val sx = (node.scale?.scaleX ?: 1.0).toFloat()
    val sy = (node.scale?.scaleY ?: 1.0).toFloat()
    val alpha = node.opacity.toFloat().coerceIn(0f, 1f)
    if (tx != 0f || ty != 0f || rot != 0f || sx != 1f || sy != 1f || alpha < 0.9999f) {
        val origin = node.origin?.let { TransformOrigin(it.x.toFloat(), it.y.toFloat()) } ?: TransformOrigin.Center
        mod = mod.graphicsLayer {
            translationX = tx
            translationY = ty
            rotationZ = rot
            scaleX = sx
            scaleY = sy
            transformOrigin = origin
            this.alpha = alpha
        }
    }
    return mod
}
