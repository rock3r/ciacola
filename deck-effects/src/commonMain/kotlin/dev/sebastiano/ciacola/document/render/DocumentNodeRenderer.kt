package dev.sebastiano.ciacola.document.render

import androidx.compose.animation.core.Transition
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntSize
import dev.bnorm.storyboard.Frame
import dev.bnorm.storyboard.layout.decorated.DecorationScope
import dev.sebastiano.ciacola.effects.LocalWarpSegmentObserver
import dev.sebastiano.ciacola.document.Affine2D
import dev.sebastiano.ciacola.document.ClipPlayback
import dev.sebastiano.ciacola.document.ContainerPayload
import dev.sebastiano.ciacola.document.CrossSlideGeometry
import dev.sebastiano.ciacola.document.DeckClock
import dev.sebastiano.ciacola.document.DeckDocument
import dev.sebastiano.ciacola.document.DecomposedTransform
import dev.sebastiano.ciacola.document.Fill
import dev.sebastiano.ciacola.document.ImagePayload
import dev.sebastiano.ciacola.document.JsonMergePatch
import dev.sebastiano.ciacola.document.LoupePayload
import dev.sebastiano.ciacola.document.NodeKind
import dev.sebastiano.ciacola.document.NodeSpec
import dev.sebastiano.ciacola.document.RenderPurpose
import dev.sebastiano.ciacola.document.RenderSession
import dev.sebastiano.ciacola.document.SegmentPlan
import dev.sebastiano.ciacola.document.SlideSpec
import dev.sebastiano.ciacola.document.SurfaceSpec
import dev.sebastiano.ciacola.document.TransitionSpec
import dev.sebastiano.ciacola.effects.LocalStageAnchorRegistry
import dev.sebastiano.ciacola.effects.MagnifierLoupeStage
import dev.sebastiano.ciacola.inspector.LocalEffectiveBackgroundColor
import dev.sebastiano.ciacola.inspector.LocalStageDragPreview
import dev.sebastiano.ciacola.inspector.LocalStageInspectionRegistry
import dev.sebastiano.ciacola.inspector.LocalStageParentElementId
import dev.sebastiano.ciacola.review.LocalReviewStageBridge
import dev.sebastiano.ciacola.review.reviewTarget
import dev.sebastiano.ciacola.theme.DeckTheme
import dev.sebastiano.ciacola.theme.SlidePalette
import kotlin.math.roundToInt

/** Shared render context threaded down a slide's node hierarchy. */
internal data class DocumentNodeRenderContext(
    val document: DeckDocument,
    val slide: SlideSpec,
    val sceneIndex: Int,
    val beatIndex: Int,
    val clock: DeckClock,
    val segmentPlan: SegmentPlan,
    val frameTransition: Transition<out Frame<Int>>?,
    val session: RenderSession,
    val config: DocumentRenderConfig,
    val theme: DeckTheme,
    val slidePalette: SlidePalette?,
    val effectiveSolidBg: Color?,
    val colorResolver: DocumentColorResolver,
    val fontResolverHandle: Any?,
    val reducedMotion: Boolean,
    val stageSize: Size,
    val isAnimatedMount: Boolean,
    val suppressedNodeIds: Set<String> = emptySet(),
    val unmatchedAlphaByNodeId: Map<String, Float> = emptyMap(),
    val movableSpecimenSlotKeys: Map<MovableSpecimenKey, String> = emptyMap(),
    val incomingTransition: TransitionSpec? = null,
    val resolveNodeAtBeat: (NodeSpec, Int) -> NodeSpec = JsonMergePatch::resolveNodeAtBeat,
    val resolveNodeAfterBuild: (NodeSpec, Int) -> NodeSpec = JsonMergePatch::resolveNodeAfterBuild,
    val loupeDecorationScope: DecorationScope? = null,
    val loupeTargetId: String? = null,
    val lookaheadStore: CrossSlideLookaheadGeometryStore? = null,
    val isGhostSubtree: Boolean = false,
    /** True inside a container that is hidden at the current beat (before its `enter`, after its `exit`). */
    val isHiddenSubtree: Boolean = false,
    val parentNodeId: String? = null,
    val parentContentPadLeftPx: Double = 0.0,
    val parentContentPadTopPx: Double = 0.0,
    val parentContentCoordsHolder: Array<LayoutCoordinates?>? = null,
)

/**
 * Renders the root node list of a slide inside its `1920x1080` fixed stage using exact stack
 * placement rules matching [dev.sebastiano.ciacola.document.CrossSlideGeometry].
 */
@Composable
internal fun DocumentSlideRootNodes(
    nodes: List<NodeSpec>,
    ctx: DocumentNodeRenderContext,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val rootCoordsHolder = remember(ctx.slide.id) { arrayOfNulls<LayoutCoordinates>(1) }
    val rootCtx = ctx.copy(
        parentNodeId = null,
        parentContentPadLeftPx = 0.0,
        parentContentPadTopPx = 0.0,
        parentContentCoordsHolder = rootCoordsHolder,
    )
    val rootRecordMod = if (ctx.lookaheadStore != null) {
        Modifier.layout { measurable, constraints ->
            val placeable = measurable.measure(constraints)
            val lookingAhead = isLookingAhead
            layout(placeable.width, placeable.height) {
                if (lookingAhead) {
                    rootCoordsHolder[0] = coordinates
                }
                placeable.place(0, 0)
            }
        }
    } else {
        Modifier
    }
    DocumentStackChildrenLayout(
        children = nodes,
        defaultAlign = "topStart",
        ctx = rootCtx,
        density = density,
        inheritedDeliveries = emptyMap(),
        parentAlreadyFading = false,
        modifier = modifier.fillMaxSize().then(rootRecordMod),
    )
}

/**
 * Custom [Layout] implementing stack child placement:
 * - Children with explicit `frame.x` or `frame.y` are placed at `(x, y)` (taking precedence over
 *   `align`) and measured with `maxWidth = w ?: (availableWidth - x)` so unsized text wraps at
 *   the exact width predicted by `CrossSlideGeometry.computeDocumentTransform`.
 * - Children without explicit `x`/`y` are aligned inside the stack's content box via `child.align`
 *   (or [defaultAlign]).
 */
@Composable
internal fun DocumentStackChildrenLayout(
    children: List<NodeSpec>,
    defaultAlign: String?,
    ctx: DocumentNodeRenderContext,
    density: Density,
    inheritedDeliveries: Map<String, ChildDeliveryOverride>,
    parentAlreadyFading: Boolean,
    modifier: Modifier = Modifier,
) {
    // Pre-evaluate child frames at current beat/clock so Layout measurement sees live x/y/w/h.
    // Ghosted (Overlay-owned) nodes still evaluate and lay out authentically so sibling layout
    // and Lookahead endpoint geometry remain 100% identical to resting Slide A and Slide B.
    val evaluatedChildren = children.map { rawChild ->
        val rawId = rawChild.id
        val isGhostChild = ctx.isGhostSubtree || rawId in ctx.suppressedNodeIds
        val unmatchedAlpha = if (isGhostChild || parentAlreadyFading) 1f else (ctx.unmatchedAlphaByNodeId[rawId] ?: 1f)
        val childFading = parentAlreadyFading || (!isGhostChild && rawId in ctx.unmatchedAlphaByNodeId)
        val eval = DocumentMotionEvaluator.evaluateNode(
            rawNode = rawChild,
            slide = ctx.slide,
            clock = ctx.clock,
            segmentPlan = ctx.segmentPlan,
            stageSize = ctx.stageSize,
            densityValue = density.density,
            colorResolver = ctx.colorResolver,
            reducedMotion = ctx.reducedMotion,
            specimenDecl = rawChild.specimen?.ref?.let { ctx.config.specimens.find(it) },
            inheritedDelivery = inheritedDeliveries[rawId],
            magicMoveUnmatchedAlpha = unmatchedAlpha,
            incomingTransition = ctx.incomingTransition,
            resolveNodeAtBeat = ctx.resolveNodeAtBeat,
            resolveNodeAfterBuild = ctx.resolveNodeAfterBuild,
        )
        Triple(rawChild, eval, childFading)
    }

    Layout(
        modifier = modifier,
        content = {
            for ((rawChild, eval, childFading) in evaluatedChildren) {
                key(rawChild.id) {
                    val isGhostChild = ctx.isGhostSubtree || rawChild.id in ctx.suppressedNodeIds
                    val childCtx = if (isGhostChild && !ctx.isGhostSubtree) ctx.copy(isGhostSubtree = true) else ctx
                    DocumentNodeItem(
                        rawNode = rawChild,
                        eval = eval,
                        ctx = childCtx,
                        parentAlreadyFading = childFading,
                        maxWidthPx = resolveStackChildMaxWidthPx(eval.resolvedNode, ctx.stageSize.width.roundToInt()),
                    )
                }
            }
        },
    ) { measurables, constraints ->
        val boxW = if (constraints.hasBoundedWidth) constraints.maxWidth else ctx.stageSize.width.roundToInt()
        val boxH = if (constraints.hasBoundedHeight) constraints.maxHeight else ctx.stageSize.height.roundToInt()

        val placeables = ArrayList<androidx.compose.ui.layout.Placeable>(measurables.size)
        val positions = ArrayList<Pair<Int, Int>>(measurables.size)

        for (i in measurables.indices) {
            val (rawChild, eval, _) = evaluatedChildren[i]
            val frame = eval.resolvedNode.frame
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
            val childConstraints = Constraints(
                minWidth = wPx ?: 0,
                maxWidth = childMaxW.coerceAtLeast(wPx ?: 0),
                minHeight = hPx ?: 0,
                maxHeight = childMaxH.coerceAtLeast(hPx ?: 0),
            )
            val placeable = measurables[i].measure(childConstraints)
            placeables += placeable

            if (hasExplicitPos) {
                positions += (xPx to yPx)
            } else {
                val align = parseBoxAlignment(eval.resolvedNode.align ?: defaultAlign)
                val offset = align.align(
                    size = androidx.compose.ui.unit.IntSize(placeable.width, placeable.height),
                    space = androidx.compose.ui.unit.IntSize(boxW, boxH),
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
            for (i in placeables.indices) {
                val (px, py) = positions[i]
                placeables[i].place(px, py)
            }
        }
    }
}

/**
 * Renders a single evaluated [NodeSpec] with anchor/inspector registration, drag-preview delta,
 * node transforms, Layer/Content/Path build effects, and payload dispatch.
 */
@Composable
internal fun DocumentNodeItem(
    rawNode: NodeSpec,
    eval: EvaluatedNodeFrame,
    ctx: DocumentNodeRenderContext,
    parentAlreadyFading: Boolean,
    maxWidthPx: Int,
    modifier: Modifier = Modifier,
) {
    val node = eval.resolvedNode
    val density = LocalDensity.current
    val isGhost = ctx.isGhostSubtree || rawNode.id in ctx.suppressedNodeIds
    val ghostCtx = if (isGhost && !ctx.isGhostSubtree) ctx.copy(isGhostSubtree = true) else ctx
    val nodeCtx = if (!ghostCtx.isHiddenSubtree && rawNode.kind in HIDEABLE_CONTAINERS && rawNode.builds.isNotEmpty() &&
        !isNodePresent(ghostCtx, rawNode)) {
        ghostCtx.copy(isHiddenSubtree = true)
    } else {
        ghostCtx
    }
    val dragPreview = LocalStageDragPreview.current
    val slotPool = LocalMovableSpecimenSlotPool.current
    val textMetricsHolder = remember(rawNode.id) {
        if (node.kind == NodeKind.Text || rawNode.kind == NodeKind.Text) LookaheadTextMetricsHolder() else null
    }
    val stageHooksMod = rememberStageNodeHooksModifier(
        nodeId = rawNode.id,
        node = node,
        sceneIndex = ctx.sceneIndex,
        beatIndex = ctx.beatIndex,
        effectiveAlphaProvider = { (node.opacity.toFloat() * eval.layerState.alpha).coerceIn(0f, 1f) },
        enabled = !isGhost,
    )

    val previewDelta: DpOffset = dragPreview?.offsetFor(rawNode.id) ?: DpOffset.Zero
    val previewPxX = with(density) { previewDelta.x.toPx() }
    val previewPxY = with(density) { previewDelta.y.toPx() }

    val nodeTx = (node.offset?.x ?: 0.0).toFloat() + eval.motionPathDelta.x + previewPxX
    val nodeTy = (node.offset?.y ?: 0.0).toFloat() + eval.motionPathDelta.y + previewPxY
    val nodeRot = node.rotation.toFloat()
    val nodeSx = (node.scale?.scaleX ?: 1.0).toFloat()
    val nodeSy = (node.scale?.scaleY ?: 1.0).toFloat()
    val nodeAlpha = node.opacity.toFloat().coerceIn(0f, 1f)
    val nodeOrigin = node.origin?.let { TransformOrigin(it.x.toFloat(), it.y.toFloat()) } ?: TransformOrigin.Center

    val hasNodeTransform = nodeTx != 0f || nodeTy != 0f || nodeRot != 0f || nodeSx != 1f || nodeSy != 1f || nodeAlpha < 0.9999f

    val reviewLabel = node.text?.value?.plainText?.lineSequence()?.firstOrNull()?.take(48) ?: node.specimen?.ref ?: node.id
    var nodeModifier = modifier
        .testTag(if (isGhost) "doc-ghost-node-${rawNode.id}" else "doc-node-${rawNode.id}")
        .let { m ->
            if (isGhost) {
                m.clearAndSetSemantics {}.drawWithContent { /* ghost: Overlay owns drawing */ }
            } else if (!JsonMergePatch.isNodePresentAtBeat(rawNode, ctx.beatIndex)) {
                // Absent on this build: no review hit target, so no phantom hover outline.
                m
            } else {
                m.reviewTarget(
                    kind = node.kind?.name ?: "Node",
                    label = reviewLabel,
                    textSnippet = node.text?.value?.plainText.orEmpty(),
                    isContainer = node.kind in setOf(NodeKind.Stack, NodeKind.Row, NodeKind.Column, NodeKind.Loupe),
                    nodeId = rawNode.id,
                )
            }
        }

    nodeModifier = nodeModifier
        .let { m ->
            val f = node.frame
            val fw = f?.w
            val fh = f?.h
            var sized = m
            if (fw != null) sized = sized.requiredWidth(fw.toStageDp(density))
            if (fh != null) sized = sized.requiredHeight(fh.toStageDp(density))
            sized
        }

    // Record Lookahead endpoint geometry AFTER requiredWidth/requiredHeight (so unconstrained
    // outer bounds never clamp overflowing children or report wrong local offsets) and BEFORE
    // graphicsLayer (capturing un-transformed layout box relative to parent content box).
    val lookaheadStore = ctx.lookaheadStore
    if (lookaheadStore != null) {
        val parentId = ctx.parentNodeId
        val padL = ctx.parentContentPadLeftPx
        val padT = ctx.parentContentPadTopPx
        val parentCoordsHolder = ctx.parentContentCoordsHolder
        val slideId = ctx.slide.id
        DisposableEffect(lookaheadStore, slideId, rawNode.id) {
            onDispose { lookaheadStore.remove(slideId, rawNode.id) }
        }
        nodeModifier = nodeModifier.layout { measurable, constraints ->
            val placeable = measurable.measure(constraints)
            val wPx = placeable.width
            val hPx = placeable.height
            val lookingAhead = isLookingAhead
            layout(wPx, hPx) {
                if (lookingAhead) {
                    val coords = coordinates
                    val parentCoords = parentCoordsHolder?.get(0)
                    val localOffset = if (coords != null && parentCoords != null && coords.isAttached && parentCoords.isAttached) {
                        parentCoords.localPositionOf(coords, Offset.Zero)
                    } else {
                        Offset.Zero
                    }
                    val parentAffine = if (parentId != null) {
                        lookaheadStore.get(slideId, parentId)?.stageAffine ?: Affine2D.Identity
                    } else {
                        Affine2D.Identity
                    }
                    val withPad = if (padL != 0.0 || padT != 0.0) {
                        parentAffine * Affine2D.translation(padL, padT)
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
                    lookaheadStore.put(
                        MeasuredEndpointGeometry(
                            slideId = slideId,
                            nodeId = rawNode.id,
                            localX = localOffset.x.toDouble(),
                            localY = localOffset.y.toDouble(),
                            widthPx = wPx,
                            heightPx = hPx,
                            stageAffine = stageAffine,
                            decomposed = decomposed,
                            measuredTextMinWidthPx = textMetricsHolder?.constraintMinWidthPx ?: 0,
                            measuredTextMaxWidthPx = textMetricsHolder?.constraintMaxWidthPx ?: Int.MAX_VALUE,
                            measuredTextLayoutWidthPx = textMetricsHolder?.layoutWidthPx ?: 0,
                            surfaceHorizontalPadPx = textMetricsHolder?.surfaceHorizontalPadPx ?: 0,
                        ),
                    )
                    if (node.kind == NodeKind.Specimen) {
                        slotPool?.recordSize(slideId, rawNode.id, wPx, hPx)
                    }
                }
                placeable.place(0, 0)
            }
        }
    }

    nodeModifier = nodeModifier
        .let { m ->
            val scope = ctx.loupeDecorationScope
            if (!isGhost && scope != null && ctx.loupeTargetId == rawNode.id) {
                with(scope) { m.decorate(rawNode.id) }
            } else {
                m
            }
        }
        .then(stageHooksMod)

    // Apply node-level transform (`offset + motionPath + dragPreview`, `rotation`, `scale`, `opacity`).
    if (hasNodeTransform) {
        nodeModifier = nodeModifier.graphicsLayer {
            translationX = nodeTx
            translationY = nodeTy
            rotationZ = nodeRot
            scaleX = nodeSx
            scaleY = nodeSy
            transformOrigin = nodeOrigin
            alpha = nodeAlpha
        }
    }

    // Apply Layer-tier build effect state (`eval.layerState`); for `loupe` containers, builds drive
    // the magnifier lens (`loupeProgress`) rather than hiding the wrapped stage children.
    if (node.kind != NodeKind.Loupe) {
        nodeModifier = nodeModifier.applyDocumentLayerState(eval.layerState)
    } else {
        val ua = ctx.unmatchedAlphaByNodeId[rawNode.id]?.coerceIn(0f, 1f) ?: 1f
        if (ua < 0.9999f) nodeModifier = nodeModifier.graphicsLayer { alpha = ua }
    }

    // Apply Content-tier SkSL shader effect if active.
    val ce = eval.contentEffect
    if (ce != null) {
        nodeModifier = nodeModifier.applyDocumentContentEffect(
            effectId = ce.effectId,
            kind = ce.kind,
            progress = ce.progress,
            args = ce.args,
            reducedMotion = ctx.reducedMotion,
        )
    }

    // Dispatch payload by NodeKind; ghost subtrees isolate CompositionLocals so nested
    // code/review/anchor/inspection consumers never emit duplicate tags or observations.
    @Composable
    fun DispatchPayload() {
        when (node.kind) {
            NodeKind.Stack -> DocumentStackContainer(
                rawNode = rawNode,
                container = node.stack!!,
                eval = eval,
                ctx = nodeCtx,
                parentAlreadyFading = parentAlreadyFading,
                modifier = nodeModifier,
            )
            NodeKind.Row -> DocumentFlowContainer(
                rawNode = rawNode,
                container = node.row!!,
                isRow = true,
                eval = eval,
                ctx = nodeCtx,
                parentAlreadyFading = parentAlreadyFading,
                modifier = nodeModifier,
            )
            NodeKind.Column -> DocumentFlowContainer(
                rawNode = rawNode,
                container = node.column!!,
                isRow = false,
                eval = eval,
                ctx = nodeCtx,
                parentAlreadyFading = parentAlreadyFading,
                modifier = nodeModifier,
            )
            NodeKind.Loupe -> DocumentLoupeContainer(
                rawNode = rawNode,
                loupe = node.loupe!!,
                eval = eval,
                ctx = nodeCtx,
                parentAlreadyFading = parentAlreadyFading,
                modifier = nodeModifier,
            )
            NodeKind.Text -> {
                val textState = eval.textState ?: return
                val textSurface = when (textState) {
                    is DocumentTextRenderState.Rest -> textState.payload.surface
                    is DocumentTextRenderState.Morph -> textState.to.surface ?: textState.from.surface
                    is DocumentTextRenderState.Build -> textState.payload.surface
                }
                CompositionLocalProvider(LocalLookaheadTextMetricsHolder provides textMetricsHolder) {
                    DocumentTextPayloadView(
                        renderState = { textState },
                        surface = textSurface,
                        document = ctx.document,
                        theme = ctx.theme,
                        colorResolver = ctx.colorResolver,
                        fontResolverHandle = ctx.fontResolverHandle,
                        density = density,
                        reducedMotion = ctx.reducedMotion,
                        deckId = ctx.document.id,
                        assetsRootDir = ctx.config.assetsRootDir,
                        maxWidthPx = node.frame?.w?.roundToInt() ?: maxWidthPx,
                        modifier = nodeModifier,
                    )
                }
            }
            NodeKind.Code -> {
                WarpCodeAdapter(
                    rawNode = rawNode,
                    resolvedNode = node,
                    slide = ctx.slide,
                    frameTransition = ctx.frameTransition,
                    segmentPlan = ctx.segmentPlan,
                    theme = ctx.theme,
                    colorResolver = ctx.colorResolver,
                    reducedMotion = ctx.reducedMotion,
                    modifier = nodeModifier,
                    fontResolverHandle = ctx.fontResolverHandle,
                )
            }
            NodeKind.Shape -> {
                DocumentShapePayloadView(
                    payload = node.shape!!,
                    colorResolver = ctx.colorResolver,
                    density = density,
                    deckId = ctx.document.id,
                    assetsRootDir = ctx.config.assetsRootDir,
                    pathTrim = eval.pathTrim,
                    modifier = nodeModifier,
                    shapeReferenceSize = eval.shapeReferenceSize,
                )
            }
            NodeKind.Image -> {
                val img = node.image!!
                DocumentImagePayloadView(
                    deckId = ctx.document.id,
                    src = img.src,
                    fit = img.fit ?: "contain",
                    tint = img.tint?.let { ctx.colorResolver.resolveOrNull(it) },
                    assetsRootDir = ctx.config.assetsRootDir,
                    modifier = nodeModifier,
                    playback = documentImagePlayback(nodeCtx, rawNode, img),
                )
            }
            NodeKind.Connector -> {
                DocumentConnectorPayloadView(
                    payload = node.connector!!,
                    colorResolver = ctx.colorResolver,
                    density = density,
                    pathTrim = eval.pathTrim,
                    modifier = nodeModifier,
                )
            }
            NodeKind.Specimen -> {
                DocumentSpecimenPayloadView(
                    nodeId = rawNode.id,
                    interactive = node.interactive,
                    payload = node.specimen!!,
                    fromParams = eval.specimenFromParams,
                    toParams = eval.specimenToParams,
                    paramProgress = eval.specimenParamProgress,
                    trackProgress = eval.specimenTrackProgress,
                    ctx = nodeCtx,
                    modifier = nodeModifier,
                )
            }
            null -> Unit
        }
    }

    if (isGhost) {
        CompositionLocalProvider(
            LocalIsGhostSubtree provides true,
            LocalStageInspectionRegistry provides null,
            LocalStageAnchorRegistry provides null,
            LocalReviewStageBridge provides null,
            LocalWarpSegmentObserver provides null,
            LocalWarpTimingObserver provides null,
        ) {
            DispatchPayload()
        }
    } else {
        DispatchPayload()
    }
}

@Composable
internal fun BoxScope.DocumentContainerSurfaceImageFill(
    surface: SurfaceSpec?,
    ctx: DocumentNodeRenderContext,
) {
    val imageFill = surface?.fill as? Fill.Image ?: return
    DocumentImageFillView(
        deckId = ctx.document.id,
        src = imageFill.image.src,
        fit = imageFill.image.fit ?: "cover",
        tint = imageFill.image.tint?.let { ctx.colorResolver.resolveOrNull(it) },
        assetsRootDir = ctx.config.assetsRootDir,
        modifier = Modifier.matchParentSize(),
    )
}

@Composable
private fun DocumentStackContainer(
    rawNode: NodeSpec,
    container: ContainerPayload,
    eval: EvaluatedNodeFrame,
    ctx: DocumentNodeRenderContext,
    parentAlreadyFading: Boolean,
    modifier: Modifier,
) {
    val density = LocalDensity.current
    val totalPad = combinePadding(container.padding, container.surface?.padding)
    val contentCoordsHolder = remember(rawNode.id) { arrayOfNulls<LayoutCoordinates>(1) }
    val childCtx = ctx.copy(
        parentNodeId = rawNode.id,
        parentContentPadLeftPx = totalPad.left,
        parentContentPadTopPx = totalPad.top,
        parentContentCoordsHolder = contentCoordsHolder,
    )
    val containerMod = modifier
        .let { if (container.clip) it.clipToBounds() else it }
        .applyDocumentSurfaceChrome(container.surface, ctx.colorResolver, density)
    val contentRecordMod = if (ctx.lookaheadStore != null) {
        Modifier.layout { measurable, constraints ->
            val placeable = measurable.measure(constraints)
            val lookingAhead = isLookingAhead
            layout(placeable.width, placeable.height) {
                if (lookingAhead) {
                    contentCoordsHolder[0] = coordinates
                }
                placeable.place(0, 0)
            }
        }
    } else {
        Modifier
    }

    CompositionLocalProvider(LocalStageParentElementId provides rawNode.id) {
        Box(modifier = containerMod, propagateMinConstraints = true) {
            DocumentContainerSurfaceImageFill(container.surface, ctx)
            DocumentStackChildrenLayout(
                children = rawNode.children,
                defaultAlign = container.alignItems ?: "topStart",
                ctx = childCtx,
                density = density,
                inheritedDeliveries = eval.childDeliveries,
                parentAlreadyFading = parentAlreadyFading,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(totalPad.toPaddingValues(density))
                    .then(contentRecordMod),
            )
        }
    }
}

@Composable
internal fun DocumentFlowContainer(
    rawNode: NodeSpec,
    container: ContainerPayload,
    isRow: Boolean,
    eval: EvaluatedNodeFrame,
    ctx: DocumentNodeRenderContext,
    parentAlreadyFading: Boolean,
    modifier: Modifier,
) {
    val density = LocalDensity.current
    val totalPad = combinePadding(container.padding, container.surface?.padding)
    val gapDp = (container.gap ?: 0.0).toStageDp(density)
    val contentCoordsHolder = remember(rawNode.id) { arrayOfNulls<LayoutCoordinates>(1) }
    val childCtx = ctx.copy(
        parentNodeId = rawNode.id,
        parentContentPadLeftPx = totalPad.left,
        parentContentPadTopPx = totalPad.top,
        parentContentCoordsHolder = contentCoordsHolder,
    )
    val containerMod = modifier
        .let { if (container.clip) it.clipToBounds() else it }
        .applyDocumentSurfaceChrome(container.surface, ctx.colorResolver, density)
    val contentRecordMod = if (ctx.lookaheadStore != null) {
        Modifier.layout { measurable, constraints ->
            val placeable = measurable.measure(constraints)
            val lookingAhead = isLookingAhead
            layout(placeable.width, placeable.height) {
                if (lookingAhead) {
                    contentCoordsHolder[0] = coordinates
                }
                placeable.place(0, 0)
            }
        }
    } else {
        Modifier
    }

    val evaluatedChildren = rawNode.children.map { rawChild ->
        val isGhostChild = childCtx.isGhostSubtree || rawChild.id in childCtx.suppressedNodeIds
        val unmatchedAlpha = if (isGhostChild || parentAlreadyFading) 1f else (childCtx.unmatchedAlphaByNodeId[rawChild.id] ?: 1f)
        val childEval = DocumentMotionEvaluator.evaluateNode(
            rawNode = rawChild,
            slide = childCtx.slide,
            clock = childCtx.clock,
            segmentPlan = childCtx.segmentPlan,
            stageSize = childCtx.stageSize,
            densityValue = density.density,
            colorResolver = childCtx.colorResolver,
            reducedMotion = childCtx.reducedMotion,
            specimenDecl = rawChild.specimen?.ref?.let { childCtx.config.specimens.find(it) },
            inheritedDelivery = eval.childDeliveries[rawChild.id],
            magicMoveUnmatchedAlpha = unmatchedAlpha,
            incomingTransition = childCtx.incomingTransition,
            resolveNodeAtBeat = childCtx.resolveNodeAtBeat,
            resolveNodeAfterBuild = childCtx.resolveNodeAfterBuild,
        )
        Triple(rawChild, childEval, isGhostChild)
    }

    CompositionLocalProvider(LocalStageParentElementId provides rawNode.id) {
        Box(modifier = containerMod, propagateMinConstraints = true) {
            DocumentContainerSurfaceImageFill(container.surface, ctx)
            val innerMod = Modifier
                .padding(totalPad.toPaddingValues(density))
                .then(contentRecordMod)
            if (isRow) {
                Row(
                    modifier = innerMod,
                    horizontalArrangement = parseHorizontalArrangement(container.justify, gapDp),
                    verticalAlignment = parseVerticalAlignment(container.alignItems),
                ) {
                    for ((rawChild, childEval, isGhostChild) in evaluatedChildren) {
                        key(rawChild.id) {
                            val w = childEval.resolvedNode.weight
                            val weightMod = if (w != null && w > 0.0) Modifier.weight(w.toFloat()) else Modifier
                            DocumentFlowChildSlot(
                                rawChild = rawChild,
                                childEval = childEval,
                                isGhostChild = isGhostChild,
                                childCtx = childCtx,
                                parentAlreadyFading = parentAlreadyFading,
                                modifier = weightMod,
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
                    for ((rawChild, childEval, isGhostChild) in evaluatedChildren) {
                        key(rawChild.id) {
                            val w = childEval.resolvedNode.weight
                            val weightMod = if (w != null && w > 0.0) Modifier.weight(w.toFloat()) else Modifier
                            DocumentFlowChildSlot(
                                rawChild = rawChild,
                                childEval = childEval,
                                isGhostChild = isGhostChild,
                                childCtx = childCtx,
                                parentAlreadyFading = parentAlreadyFading,
                                modifier = weightMod,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DocumentFlowChildSlot(
    rawChild: NodeSpec,
    childEval: EvaluatedNodeFrame,
    isGhostChild: Boolean,
    childCtx: DocumentNodeRenderContext,
    parentAlreadyFading: Boolean,
    modifier: Modifier,
) {
    val itemCtx = if (isGhostChild && !childCtx.isGhostSubtree) childCtx.copy(isGhostSubtree = true) else childCtx
    DocumentNodeItem(
        rawNode = rawChild,
        eval = childEval,
        ctx = itemCtx,
        parentAlreadyFading = parentAlreadyFading || (!isGhostChild && rawChild.id in childCtx.unmatchedAlphaByNodeId),
        maxWidthPx = Int.MAX_VALUE,
        modifier = modifier,
    )
}

@Composable
private fun DocumentLoupeContainer(
    rawNode: NodeSpec,
    loupe: LoupePayload,
    eval: EvaluatedNodeFrame,
    ctx: DocumentNodeRenderContext,
    parentAlreadyFading: Boolean,
    modifier: Modifier,
) {
    val density = LocalDensity.current
    val diameterDp = loupe.diameter?.toStageDp(density) ?: ctx.theme.styles.loupe.diameter
    val lensBg = when (val fill = loupe.lensFill) {
        is Fill.Solid -> ctx.colorResolver.resolve(fill.color, Color.Unspecified)
        else -> Color.Unspecified
    }
    val loupeProgress = if (eval.isEffectivelyHidden) 0f else eval.layerState.alpha.coerceIn(0f, 1f)
    val effectiveBg = ctx.effectiveSolidBg ?: ctx.theme.colors.background
    val contentCoordsHolder = remember(rawNode.id) { arrayOfNulls<LayoutCoordinates>(1) }
    val contentRecordMod = if (ctx.lookaheadStore != null) {
        Modifier.layout { measurable, constraints ->
            val placeable = measurable.measure(constraints)
            val lookingAhead = isLookingAhead
            layout(placeable.width, placeable.height) {
                if (lookingAhead) {
                    contentCoordsHolder[0] = coordinates
                }
                placeable.place(0, 0)
            }
        }
    } else {
        Modifier
    }

    CompositionLocalProvider(LocalEffectiveBackgroundColor provides effectiveBg) {
        MagnifierLoupeStage(
            targetKey = loupe.target,
            progress = { loupeProgress },
            modifier = modifier.fillMaxSize(),
            loupeAlignment = parseBoxAlignment(loupe.align ?: "centerEnd"),
            loupeDiameter = diameterDp,
            zoom = loupe.zoom.toFloat(),
            lensBackgroundColor = lensBg,
        ) {
            val childCtx = ctx.copy(
                loupeDecorationScope = this,
                loupeTargetId = loupe.target,
                parentNodeId = rawNode.id,
                parentContentPadLeftPx = 0.0,
                parentContentPadTopPx = 0.0,
                parentContentCoordsHolder = contentCoordsHolder,
            )
            DocumentStackChildrenLayout(
                children = rawNode.children,
                defaultAlign = "topStart",
                ctx = childCtx,
                density = density,
                inheritedDeliveries = eval.childDeliveries,
                parentAlreadyFading = parentAlreadyFading,
                modifier = Modifier.fillMaxSize().then(contentRecordMod),
            )
        }
    }
}

internal fun resolveStackChildMaxWidthPx(node: NodeSpec, parentWidthPx: Int): Int {
    val f = node.frame
    val fw = f?.w
    val fx = f?.x
    if (fw != null) return fw.roundToInt().coerceAtLeast(1)
    if (fx != null) return (parentWidthPx - fx.roundToInt()).coerceAtLeast(1)
    return parentWidthPx
}

internal fun parseBoxAlignment(value: String?): Alignment = when (value?.lowercase()) {
    "topstart", "topleft" -> Alignment.TopStart
    "topcenter", "top" -> Alignment.TopCenter
    "topend", "topright" -> Alignment.TopEnd
    "centerstart", "centerleft", "left", "start" -> Alignment.CenterStart
    "center" -> Alignment.Center
    "centerend", "centerright", "right", "end" -> Alignment.CenterEnd
    "bottomstart", "bottomleft" -> Alignment.BottomStart
    "bottomcenter", "bottom" -> Alignment.BottomCenter
    "bottomend", "bottomright" -> Alignment.BottomEnd
    else -> Alignment.TopStart
}

internal fun parseHorizontalAlignment(value: String?): Alignment.Horizontal = when (value?.lowercase()) {
    "center" -> Alignment.CenterHorizontally
    "end", "right" -> Alignment.End
    else -> Alignment.Start
}

internal fun parseVerticalAlignment(value: String?): Alignment.Vertical = when (value?.lowercase()) {
    "center" -> Alignment.CenterVertically
    "end", "bottom" -> Alignment.Bottom
    else -> Alignment.Top
}

internal fun parseHorizontalArrangement(justify: String?, gapDp: androidx.compose.ui.unit.Dp): Arrangement.Horizontal =
    when (justify?.lowercase()) {
        "center" -> Arrangement.spacedBy(gapDp, Alignment.CenterHorizontally)
        "end", "right" -> Arrangement.spacedBy(gapDp, Alignment.End)
        "spacebetween" -> Arrangement.SpaceBetween
        "spacearound" -> Arrangement.SpaceAround
        "spaceevenly" -> Arrangement.SpaceEvenly
        else -> Arrangement.spacedBy(gapDp, Alignment.Start)
    }

internal fun parseVerticalArrangement(justify: String?, gapDp: androidx.compose.ui.unit.Dp): Arrangement.Vertical =
    when (justify?.lowercase()) {
        "center" -> Arrangement.spacedBy(gapDp, Alignment.CenterVertically)
        "end", "bottom" -> Arrangement.spacedBy(gapDp, Alignment.Bottom)
        "spacebetween" -> Arrangement.SpaceBetween
        "spacearound" -> Arrangement.SpaceAround
        "spaceevenly" -> Arrangement.SpaceEvenly
        else -> Arrangement.spacedBy(gapDp, Alignment.Top)
    }

private fun documentImagePlayback(
    ctx: DocumentNodeRenderContext,
    rawNode: NodeSpec,
    image: ImagePayload,
): DocumentImagePlayback {
    val live = ctx.isAnimatedMount && ctx.session.purpose == RenderPurpose.Present && !ctx.isGhostSubtree
    val beat = clipBeat(ctx)
    // Before its `enter` or after its `exit` the node, or a container holding it, is drawn invisible:
    // it must not play or take clicks.
    val present = !ctx.isHiddenSubtree && isNodePresent(ctx, rawNode, beat)
    return DocumentImagePlayback(
        payload = image,
        builds = rawNode.builds,
        // Keyed by source too, so a `change` build swapping `src` starts the new clip on its own playhead.
        retainKey = clipRetainKey(ctx.slide.id, rawNode.id, image),
        session = ctx.session,
        beat = beat,
        live = live,
        present = present,
        scrubbable = live && present && ctx.config.clipScrubbing,
        mirrorOf = ctx.session.clipMirrorSource,
    )
}

private val HIDEABLE_CONTAINERS = setOf(NodeKind.Stack, NodeKind.Row, NodeKind.Column)

private fun clipBeat(ctx: DocumentNodeRenderContext): Int? =
    ClipPlayback.effectiveBeat(ctx.clock, ctx.slide.id, lastBeat = ctx.slide.beats.lastIndex)

private fun isNodePresent(ctx: DocumentNodeRenderContext, rawNode: NodeSpec, beat: Int? = clipBeat(ctx)): Boolean =
    beat == null || beat < 0 || JsonMergePatch.isNodePresentAtBeat(rawNode, beat)

private fun clipRetainKey(slideId: String, nodeId: String, image: ImagePayload): String = "clip:$slideId/$nodeId/${image.src}"

/**
 * Playback for an image drawn by a magicMove overlay: in show mode it shows the frame the node's
 * clip on [slideId] is on, elsewhere its poster frame.
 */
internal fun overlayImagePlayback(ctx: DocumentNodeRenderContext, slideId: String, nodeId: String, image: ImagePayload) =
    DocumentImagePlayback(
        payload = image,
        builds = emptyList(),
        retainKey = clipRetainKey(slideId, nodeId, image),
        session = ctx.session,
        beat = null,
        live = false,
        present = true,
        scrubbable = false,
        mirrorOf = if (ctx.isAnimatedMount && ctx.session.purpose == RenderPurpose.Present) ctx.session else ctx.session.clipMirrorSource,
    )
