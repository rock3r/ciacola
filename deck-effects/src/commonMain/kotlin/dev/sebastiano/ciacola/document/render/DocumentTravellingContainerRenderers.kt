package dev.sebastiano.ciacola.document.render

import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import dev.sebastiano.ciacola.document.Fill
import dev.sebastiano.ciacola.document.JsonMergePatch
import dev.sebastiano.ciacola.document.NodeKind
import dev.sebastiano.ciacola.document.NodeSpec
import dev.sebastiano.ciacola.document.PaddingSpec
import dev.sebastiano.ciacola.document.RichTextValue
import dev.sebastiano.ciacola.document.SegmentPlan
import dev.sebastiano.ciacola.document.SlideSpec
import dev.sebastiano.ciacola.document.TextPayload
import dev.sebastiano.ciacola.document.TrackPlan
import dev.sebastiano.ciacola.document.TransitionSpec
import dev.sebastiano.ciacola.effects.MagnifierLoupeStage
import dev.sebastiano.ciacola.inspector.LocalEffectiveBackgroundColor
import kotlin.math.roundToInt

internal sealed interface TravellingChildEntry {
    val key: String
    val layoutNode: NodeSpec
    val alpha: Float

    data class Matched(
        override val key: String,
        val eval: EvaluatedTravellingDescendant,
    ) : TravellingChildEntry {
        override val layoutNode: NodeSpec get() = eval.resolvedNode
        override val alpha: Float get() = eval.resolvedNode.opacity.toFloat().coerceIn(0f, 1f)
    }

    data class UnmatchedExiting(
        override val key: String,
        val fromNode: NodeSpec,
        override val alpha: Float,
        val rawFromNode: NodeSpec = fromNode,
    ) : TravellingChildEntry {
        override val layoutNode: NodeSpec get() = fromNode
    }

    data class UnmatchedEntering(
        override val key: String,
        val toNode: NodeSpec,
        override val alpha: Float,
        val rawToNode: NodeSpec = toNode,
    ) : TravellingChildEntry {
        override val layoutNode: NodeSpec get() = toNode
    }
}

private fun childrenMatch(fromChild: NodeSpec, toChild: NodeSpec): Boolean =
    fromChild.id == toChild.id &&
        fromChild.kind != null &&
        fromChild.kind == toChild.kind &&
        (fromChild.kind != NodeKind.Specimen || fromChild.specimen?.ref == toChild.specimen?.ref)

private fun buildTravellingChildEntries(
    fromChildren: List<NodeSpec>,
    toChildren: List<NodeSpec>,
    rawFromById: Map<String, NodeSpec>,
    rawToById: Map<String, NodeSpec>,
    fromSlide: SlideSpec,
    moveProgress: Float,
    exitAlpha: Float,
    enterAlpha: Float,
    transitionSpec: TransitionSpec,
    toSlideTransition: TransitionSpec?,
    moveTrack: TrackPlan?,
    ctx: DocumentNodeRenderContext,
): List<TravellingChildEntry> {
    val fromLastBeat = fromSlide.beats.lastIndex.coerceAtLeast(0)
    val presentFrom = fromChildren.filter { it.kind == NodeKind.Loupe || JsonMergePatch.isNodePresentAtBeat(it, fromLastBeat) }
    val presentTo = toChildren.filter { it.kind == NodeKind.Loupe || JsonMergePatch.isNodePresentAtStart(it) }
    val fromById = presentFrom.associateBy { it.id }

    val matchedIds = HashSet<String>()
    for (tc in presentTo) {
        val fc = fromById[tc.id]
        if (fc != null && childrenMatch(fc, tc)) {
            matchedIds += tc.id
        }
    }

    val entries = ArrayList<TravellingChildEntry>(presentFrom.size + presentTo.size)
    interleaveByAnchor(
        presentFrom = presentFrom,
        presentTo = presentTo,
        matchedIds = matchedIds,
        idOfFrom = { it.id },
        idOfTo = { it.id },
        emitMatched = { tc ->
            val fc = fromById[tc.id]!!
            val rawFc = rawFromById[tc.id] ?: fc
            val rawTc = rawToById[tc.id] ?: tc
            val descEval = DocumentMotionEvaluator.evaluateTravellingDescendant(
                fromNode = fc,
                toNode = tc,
                moveProgress = moveProgress,
                moveTrack = moveTrack,
                clock = ctx.clock,
                transitionSpec = transitionSpec,
                colorResolver = ctx.colorResolver,
                toSlideTransition = toSlideTransition,
                rawFromNode = rawFc,
                rawToNode = rawTc,
                fromSlideLastBeat = fromLastBeat,
                resolveToNodeAfterBuild = ctx.resolveNodeAfterBuild,
            )
            entries += TravellingChildEntry.Matched(key = "matched:${tc.id}", eval = descEval)
        },
        emitUnmatchedExiting = { fc ->
            if (exitAlpha > 0.0005f) {
                entries += TravellingChildEntry.UnmatchedExiting(
                    key = "exit:${fc.id}",
                    fromNode = fc,
                    alpha = (fc.opacity.toFloat() * exitAlpha).coerceIn(0f, 1f),
                    rawFromNode = rawFromById[fc.id] ?: fc,
                )
            }
        },
        emitUnmatchedEntering = { tc ->
            if (enterAlpha > 0.0005f) {
                entries += TravellingChildEntry.UnmatchedEntering(
                    key = "enter:${tc.id}",
                    toNode = tc,
                    alpha = (tc.opacity.toFloat() * enterAlpha).coerceIn(0f, 1f),
                    rawToNode = rawToById[tc.id] ?: tc,
                )
            }
        },
    )
    return entries
}

private data class ResolvedLookaheadChildBox(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
)

private fun resolveChildLookaheadBox(
    entry: TravellingChildEntry,
    fromSlideId: String,
    toSlideId: String,
    moveProgress: Float,
    fromPad: PaddingSpec,
    toPad: PaddingSpec,
    effectivePad: PaddingSpec,
    lookaheadStore: CrossSlideLookaheadGeometryStore?,
): ResolvedLookaheadChildBox? {
    if (lookaheadStore == null) return null
    return when (entry) {
        is TravellingChildEntry.Matched -> {
            val geoA = lookaheadStore.get(fromSlideId, entry.eval.fromNode.id) ?: return null
            val geoB = lookaheadStore.get(toSlideId, entry.eval.toNode.id) ?: return null
            val p = moveProgress.coerceIn(0f, 1f)
            ResolvedLookaheadChildBox(
                x = (geoA.localX + (geoB.localX - geoA.localX) * p).roundToInt(),
                y = (geoA.localY + (geoB.localY - geoA.localY) * p).roundToInt(),
                width = (geoA.widthPx + (geoB.widthPx - geoA.widthPx) * p).roundToInt().coerceAtLeast(0),
                height = (geoA.heightPx + (geoB.heightPx - geoA.heightPx) * p).roundToInt().coerceAtLeast(0),
            )
        }
        is TravellingChildEntry.UnmatchedExiting -> {
            val geoA = lookaheadStore.get(fromSlideId, entry.fromNode.id) ?: return null
            ResolvedLookaheadChildBox(
                x = (geoA.localX + fromPad.left - effectivePad.left).roundToInt(),
                y = (geoA.localY + fromPad.top - effectivePad.top).roundToInt(),
                width = geoA.widthPx.coerceAtLeast(0),
                height = geoA.heightPx.coerceAtLeast(0),
            )
        }
        is TravellingChildEntry.UnmatchedEntering -> {
            val geoB = lookaheadStore.get(toSlideId, entry.toNode.id) ?: return null
            ResolvedLookaheadChildBox(
                x = (geoB.localX + toPad.left - effectivePad.left).roundToInt(),
                y = (geoB.localY + toPad.top - effectivePad.top).roundToInt(),
                width = geoB.widthPx.coerceAtLeast(0),
                height = geoB.heightPx.coerceAtLeast(0),
            )
        }
    }
}

@Composable
internal fun TravellingStackContainer(
    fromNode: NodeSpec,
    toNode: NodeSpec,
    resolvedNode: NodeSpec,
    fromSlide: SlideSpec,
    moveProgress: Float,
    exitAlpha: Float,
    enterAlpha: Float,
    transitionSpec: TransitionSpec,
    toSlideTransition: TransitionSpec?,
    moveTrack: TrackPlan?,
    ctx: DocumentNodeRenderContext,
    modifier: Modifier,
    rawFromNode: NodeSpec = fromNode,
    rawToNode: NodeSpec = toNode,
) {
    val density = LocalDensity.current
    val container = resolvedNode.stack ?: return
    val fromPad = combinePadding(fromNode.stack?.padding, fromNode.stack?.surface?.padding)
    val toPad = combinePadding(toNode.stack?.padding, toNode.stack?.surface?.padding)
    val effectivePad = DocumentMotionEvaluator.interpolatePadding(fromPad, toPad, moveProgress.toDouble())
    val containerMod = modifier
        .let { if (container.clip) it.clipToBounds() else it }
        .applyDocumentSurfaceChrome(container.surface, ctx.colorResolver, density)

    Box(modifier = containerMod, propagateMinConstraints = true) {
        DocumentContainerSurfaceImageFill(container.surface, ctx)
        TravellingStackChildrenLayout(
            fromChildren = fromNode.children,
            toChildren = toNode.children,
            rawFromChildren = rawFromNode.children,
            rawToChildren = rawToNode.children,
            defaultAlign = container.alignItems ?: toNode.stack?.alignItems ?: fromNode.stack?.alignItems ?: "topStart",
            fromPad = fromPad,
            toPad = toPad,
            effectivePad = effectivePad,
            fromSlide = fromSlide,
            moveProgress = moveProgress,
            exitAlpha = exitAlpha,
            enterAlpha = enterAlpha,
            transitionSpec = transitionSpec,
            toSlideTransition = toSlideTransition,
            moveTrack = moveTrack,
            ctx = ctx,
            density = density,
            modifier = Modifier
                .fillMaxSize()
                .padding(effectivePad.toPaddingValues(density)),
        )
    }
}

@Composable
internal fun TravellingLoupeContainer(
    fromNode: NodeSpec,
    toNode: NodeSpec,
    resolvedNode: NodeSpec,
    fromSlide: SlideSpec,
    moveProgress: Float,
    exitAlpha: Float,
    enterAlpha: Float,
    transitionSpec: TransitionSpec,
    toSlideTransition: TransitionSpec?,
    moveTrack: TrackPlan?,
    ctx: DocumentNodeRenderContext,
    modifier: Modifier,
    rawFromNode: NodeSpec = fromNode,
    rawToNode: NodeSpec = toNode,
) {
    val density = LocalDensity.current
    val loupe = resolvedNode.loupe ?: return
    val diameterDp = loupe.diameter?.toStageDp(density) ?: ctx.theme.styles.loupe.diameter
    val lensBg = when (val fill = loupe.lensFill) {
        is Fill.Solid -> ctx.colorResolver.resolve(fill.color, Color.Unspecified)
        else -> Color.Unspecified
    }
    val fromLastBeat = fromSlide.beats.lastIndex.coerceAtLeast(0)
    val fromLensVisible = JsonMergePatch.isNodePresentAtBeat(fromNode, fromLastBeat)
    val toLensVisible = JsonMergePatch.isNodePresentAtStart(toNode)
    val loupeProgress = when {
        !fromLensVisible && !toLensVisible -> 0f
        fromLensVisible && toLensVisible -> resolvedNode.opacity.toFloat().coerceIn(0f, 1f)
        toLensVisible -> (moveProgress * toNode.opacity.toFloat()).coerceIn(0f, 1f)
        else -> ((1f - moveProgress) * fromNode.opacity.toFloat()).coerceIn(0f, 1f)
    }
    val effectiveBg = ctx.effectiveSolidBg ?: ctx.theme.colors.background

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
            )
            TravellingStackChildrenLayout(
                fromChildren = fromNode.children,
                toChildren = toNode.children,
                rawFromChildren = rawFromNode.children,
                rawToChildren = rawToNode.children,
                defaultAlign = "topStart",
                fromPad = PaddingSpec(),
                toPad = PaddingSpec(),
                effectivePad = PaddingSpec(),
                fromSlide = fromSlide,
                moveProgress = moveProgress,
                exitAlpha = exitAlpha,
                enterAlpha = enterAlpha,
                transitionSpec = transitionSpec,
                toSlideTransition = toSlideTransition,
                moveTrack = moveTrack,
                ctx = childCtx,
                density = density,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun TravellingStackChildrenLayout(
    fromChildren: List<NodeSpec>,
    toChildren: List<NodeSpec>,
    rawFromChildren: List<NodeSpec>,
    rawToChildren: List<NodeSpec>,
    defaultAlign: String?,
    fromPad: PaddingSpec,
    toPad: PaddingSpec,
    effectivePad: PaddingSpec,
    fromSlide: SlideSpec,
    moveProgress: Float,
    exitAlpha: Float,
    enterAlpha: Float,
    transitionSpec: TransitionSpec,
    toSlideTransition: TransitionSpec?,
    moveTrack: TrackPlan?,
    ctx: DocumentNodeRenderContext,
    density: Density,
    modifier: Modifier,
) {
    val rawFromById = remember(rawFromChildren) { rawFromChildren.associateBy { it.id } }
    val rawToById = remember(rawToChildren) { rawToChildren.associateBy { it.id } }
    val entries = buildTravellingChildEntries(
        fromChildren = fromChildren,
        toChildren = toChildren,
        rawFromById = rawFromById,
        rawToById = rawToById,
        fromSlide = fromSlide,
        moveProgress = moveProgress,
        exitAlpha = exitAlpha,
        enterAlpha = enterAlpha,
        transitionSpec = transitionSpec,
        toSlideTransition = toSlideTransition,
        moveTrack = moveTrack,
        ctx = ctx,
    )
    val lookaheadStore = ctx.lookaheadStore
    val fromSlideId = fromSlide.id
    val toSlideId = ctx.slide.id

    Layout(
        modifier = modifier.trackLookaheadGeometry(lookaheadStore),
        content = {
            for (entry in entries) {
                key(entry.key) {
                    val childMaxW = resolveStackChildMaxWidthPx(entry.layoutNode, ctx.stageSize.width.roundToInt())
                    val childMod = if (lookaheadStore != null) {
                        Modifier.approachSlideConstraints(
                            store = lookaheadStore,
                            progressKey = moveProgress,
                        ) { constraints ->
                            val box = resolveChildLookaheadBox(
                                entry, fromSlideId, toSlideId, moveProgress, fromPad, toPad, effectivePad, lookaheadStore,
                            )
                            if (box != null) Constraints.fixed(box.width, box.height) else constraints
                        }
                    } else {
                        Modifier
                    }
                    RenderTravellingChildEntry(
                        entry = entry,
                        fromSlide = fromSlide,
                        moveProgress = moveProgress,
                        exitAlpha = exitAlpha,
                        enterAlpha = enterAlpha,
                        transitionSpec = transitionSpec,
                        toSlideTransition = toSlideTransition,
                        moveTrack = moveTrack,
                        ctx = ctx,
                        maxWidthPx = childMaxW,
                        modifier = childMod,
                    )
                }
            }
        },
    ) { measurables, constraints ->
        val boxW = if (constraints.hasBoundedWidth) constraints.maxWidth else ctx.stageSize.width.roundToInt()
        val boxH = if (constraints.hasBoundedHeight) constraints.maxHeight else ctx.stageSize.height.roundToInt()

        val placeables = ArrayList<Placeable>(measurables.size)
        val positions = ArrayList<Pair<Int, Int>>(measurables.size)

        for (i in measurables.indices) {
            val entry = entries[i]
            val lookaheadBox = resolveChildLookaheadBox(
                entry, fromSlideId, toSlideId, moveProgress, fromPad, toPad, effectivePad, lookaheadStore,
            )
            if (lookaheadBox != null) {
                val placeable = measurables[i].measure(Constraints.fixed(lookaheadBox.width, lookaheadBox.height))
                placeables += placeable
                positions += (lookaheadBox.x to lookaheadBox.y)
            } else {
                val node = entry.layoutNode
                val frame = node.frame
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
                    val align = parseBoxAlignment(node.align ?: defaultAlign)
                    val offset = align.align(
                        size = IntSize(placeable.width, placeable.height),
                        space = IntSize(boxW, boxH),
                        layoutDirection = layoutDirection,
                    )
                    positions += (offset.x to offset.y)
                }
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

@Composable
internal fun TravellingFlowContainer(
    fromNode: NodeSpec,
    toNode: NodeSpec,
    resolvedNode: NodeSpec,
    isRow: Boolean,
    fromSlide: SlideSpec,
    moveProgress: Float,
    exitAlpha: Float,
    enterAlpha: Float,
    transitionSpec: TransitionSpec,
    toSlideTransition: TransitionSpec?,
    moveTrack: TrackPlan?,
    ctx: DocumentNodeRenderContext,
    modifier: Modifier,
    rawFromNode: NodeSpec = fromNode,
    rawToNode: NodeSpec = toNode,
) {
    val density = LocalDensity.current
    val container = (if (isRow) resolvedNode.row else resolvedNode.column) ?: return
    val fromContainer = if (isRow) fromNode.row else fromNode.column
    val toContainer = if (isRow) toNode.row else toNode.column

    val fromPad = combinePadding(fromContainer?.padding, fromContainer?.surface?.padding)
    val toPad = combinePadding(toContainer?.padding, toContainer?.surface?.padding)
    val effectivePad = DocumentMotionEvaluator.interpolatePadding(fromPad, toPad, moveProgress.toDouble())
    val containerMod = modifier
        .let { if (container.clip) it.clipToBounds() else it }
        .applyDocumentSurfaceChrome(container.surface, ctx.colorResolver, density)

    val rawFromById = remember(rawFromNode) { rawFromNode.children.associateBy { it.id } }
    val rawToById = remember(rawToNode) { rawToNode.children.associateBy { it.id } }
    val entries = buildTravellingChildEntries(
        fromChildren = fromNode.children,
        toChildren = toNode.children,
        rawFromById = rawFromById,
        rawToById = rawToById,
        fromSlide = fromSlide,
        moveProgress = moveProgress,
        exitAlpha = exitAlpha,
        enterAlpha = enterAlpha,
        transitionSpec = transitionSpec,
        toSlideTransition = toSlideTransition,
        moveTrack = moveTrack,
        ctx = ctx,
    )
    val lookaheadStore = ctx.lookaheadStore
    val fromSlideId = fromSlide.id
    val toSlideId = ctx.slide.id

    Box(modifier = containerMod, propagateMinConstraints = true) {
        DocumentContainerSurfaceImageFill(container.surface, ctx)
        Layout(
            modifier = Modifier
                .matchParentSize()
                .padding(effectivePad.toPaddingValues(density))
                .trackLookaheadGeometry(lookaheadStore),
            content = {
                for (entry in entries) {
                    key(entry.key) {
                        val childMod = if (lookaheadStore != null) {
                            Modifier.approachSlideConstraints(
                                store = lookaheadStore,
                                progressKey = moveProgress,
                            ) { constraints ->
                                val box = resolveChildLookaheadBox(
                                    entry, fromSlideId, toSlideId, moveProgress, fromPad, toPad, effectivePad, lookaheadStore,
                                )
                                if (box != null) Constraints.fixed(box.width, box.height) else constraints
                            }
                        } else {
                            Modifier
                        }
                        RenderTravellingChildEntry(
                            entry = entry,
                            fromSlide = fromSlide,
                            moveProgress = moveProgress,
                            exitAlpha = exitAlpha,
                            enterAlpha = enterAlpha,
                            transitionSpec = transitionSpec,
                            toSlideTransition = toSlideTransition,
                            moveTrack = moveTrack,
                            ctx = ctx,
                            maxWidthPx = Int.MAX_VALUE,
                            modifier = childMod,
                        )
                    }
                }
            },
        ) { measurables, constraints ->
            val gapPx = with(density) { (container.gap ?: 0.0).toStageDp(density).roundToPx() }.coerceAtLeast(0)
            val placeables = ArrayList<Placeable>(measurables.size)
            val positions = ArrayList<Pair<Int, Int>>(measurables.size)
            var fallbackX = 0
            var fallbackY = 0

            for (i in measurables.indices) {
                val entry = entries[i]
                val lookaheadBox = resolveChildLookaheadBox(
                    entry, fromSlideId, toSlideId, moveProgress, fromPad, toPad, effectivePad, lookaheadStore,
                )
                if (lookaheadBox != null) {
                    val pl = measurables[i].measure(Constraints.fixed(lookaheadBox.width, lookaheadBox.height))
                    placeables += pl
                    positions += (lookaheadBox.x to lookaheadBox.y)
                    if (isRow) {
                        fallbackX = maxOf(fallbackX, lookaheadBox.x + pl.width + gapPx)
                    } else {
                        fallbackY = maxOf(fallbackY, lookaheadBox.y + pl.height + gapPx)
                    }
                } else {
                    val f = entry.layoutNode.frame
                    val wPx = f?.w?.roundToInt()?.coerceAtLeast(0)
                    val hPx = f?.h?.roundToInt()?.coerceAtLeast(0)
                    val fallbackConstraints = Constraints(
                        minWidth = wPx ?: 0,
                        maxWidth = wPx ?: constraints.maxWidth,
                        minHeight = hPx ?: 0,
                        maxHeight = hPx ?: constraints.maxHeight,
                    )
                    val pl = measurables[i].measure(fallbackConstraints)
                    placeables += pl
                    positions += (fallbackX to fallbackY)
                    if (isRow) {
                        fallbackX += pl.width + gapPx
                    } else {
                        fallbackY += pl.height + gapPx
                    }
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
}

@Composable
private fun RenderTravellingChildEntry(
    entry: TravellingChildEntry,
    fromSlide: SlideSpec,
    moveProgress: Float,
    exitAlpha: Float,
    enterAlpha: Float,
    transitionSpec: TransitionSpec,
    toSlideTransition: TransitionSpec?,
    moveTrack: TrackPlan?,
    ctx: DocumentNodeRenderContext,
    maxWidthPx: Int,
    modifier: Modifier,
) {
    val density = LocalDensity.current
    val node = entry.layoutNode
    val alpha = entry.alpha
    val fromLastBeat = fromSlide.beats.lastIndex.coerceAtLeast(0)

    val nodeTx = (node.offset?.x ?: 0.0).toFloat()
    val nodeTy = (node.offset?.y ?: 0.0).toFloat()
    val nodeRot = node.rotation.toFloat()
    val nodeSx = (node.scale?.scaleX ?: 1.0).toFloat()
    val nodeSy = (node.scale?.scaleY ?: 1.0).toFloat()
    val nodeOrigin = node.origin?.let { TransformOrigin(it.x.toFloat(), it.y.toFloat()) } ?: TransformOrigin.Center
    val hasTransform = nodeTx != 0f || nodeTy != 0f || nodeRot != 0f || nodeSx != 1f || nodeSy != 1f || alpha < 0.9999f

    val childHooksMod = rememberStageNodeHooksModifier(
        nodeId = node.id,
        node = node,
        sceneIndex = ctx.sceneIndex,
        beatIndex = ctx.beatIndex,
        effectiveAlphaProvider = { alpha },
        enabled = entry is TravellingChildEntry.Matched,
    )
    var childMod = modifier
        .testTag("doc-overlay-child-${node.id}")
        .let { m ->
            if (ctx.lookaheadStore == null) {
                var sized = m
                val fw = node.frame?.w
                val fh = node.frame?.h
                if (fw != null) sized = sized.requiredWidth(fw.toStageDp(density))
                if (fh != null) sized = sized.requiredHeight(fh.toStageDp(density))
                sized
            } else {
                m
            }
        }
        .let { m ->
            val scope = ctx.loupeDecorationScope
            if (scope != null && ctx.loupeTargetId == node.id) {
                with(scope) { m.decorate(node.id) }
            } else {
                m
            }
        }

    if (hasTransform) {
        childMod = childMod.graphicsLayer {
            translationX = nodeTx
            translationY = nodeTy
            rotationZ = nodeRot
            scaleX = nodeSx
            scaleY = nodeSy
            transformOrigin = nodeOrigin
            this.alpha = alpha
        }
    }
    childMod = childMod.then(childHooksMod)

    when (entry) {
        is TravellingChildEntry.Matched -> {
            val eval = entry.eval
            when (node.kind) {
                NodeKind.Stack -> TravellingStackContainer(
                    fromNode = eval.fromNode,
                    toNode = eval.toNode,
                    resolvedNode = node,
                    fromSlide = fromSlide,
                    moveProgress = moveProgress,
                    exitAlpha = exitAlpha,
                    enterAlpha = enterAlpha,
                    transitionSpec = transitionSpec,
                    toSlideTransition = toSlideTransition,
                    moveTrack = moveTrack,
                    ctx = ctx,
                    modifier = childMod,
                    rawFromNode = eval.rawFromNode,
                    rawToNode = eval.rawToNode,
                )
                NodeKind.Row, NodeKind.Column -> TravellingFlowContainer(
                    fromNode = eval.fromNode,
                    toNode = eval.toNode,
                    resolvedNode = node,
                    isRow = node.kind == NodeKind.Row,
                    fromSlide = fromSlide,
                    moveProgress = moveProgress,
                    exitAlpha = exitAlpha,
                    enterAlpha = enterAlpha,
                    transitionSpec = transitionSpec,
                    toSlideTransition = toSlideTransition,
                    moveTrack = moveTrack,
                    ctx = ctx,
                    modifier = childMod,
                    rawFromNode = eval.rawFromNode,
                    rawToNode = eval.rawToNode,
                )
                NodeKind.Loupe -> TravellingLoupeContainer(
                    fromNode = eval.fromNode,
                    toNode = eval.toNode,
                    resolvedNode = node,
                    fromSlide = fromSlide,
                    moveProgress = moveProgress,
                    exitAlpha = exitAlpha,
                    enterAlpha = enterAlpha,
                    transitionSpec = transitionSpec,
                    toSlideTransition = toSlideTransition,
                    moveTrack = moveTrack,
                    ctx = ctx,
                    modifier = childMod,
                    rawFromNode = eval.rawFromNode,
                    rawToNode = eval.rawToNode,
                )
                NodeKind.Text -> {
                    val lookaheadStore = ctx.lookaheadStore
                    val fromSlideId = fromSlide.id
                    val toSlideId = ctx.slide.id
                    val lazyTextRenderState: () -> DocumentTextRenderState = {
                        val fromGeo = lookaheadStore?.get(fromSlideId, eval.fromNode.id)
                        val toGeo = lookaheadStore?.get(toSlideId, eval.toNode.id)
                        DocumentMotionEvaluator.resolveTravellingTextRenderState(
                            fromNode = eval.fromNode,
                            toNode = eval.toNode,
                            nodeId = node.id,
                            fromEndpointMinWidthPx = fromGeo?.measuredTextMinWidthPx,
                            toEndpointMinWidthPx = toGeo?.measuredTextMinWidthPx,
                            fromEndpointMaxWidthPx = fromGeo?.measuredTextMaxWidthPx,
                            toEndpointMaxWidthPx = toGeo?.measuredTextMaxWidthPx,
                            fromEndpointSurfacePadPx = fromGeo?.surfaceHorizontalPadPx,
                            toEndpointSurfacePadPx = toGeo?.surfaceHorizontalPadPx,
                            fallbackFromAvailableWidth = null,
                            fallbackToAvailableWidth = null,
                            moveTrack = moveTrack,
                            clock = ctx.clock,
                            transitionSpec = transitionSpec,
                            toSlideTransition = toSlideTransition,
                            isDescendant = true,
                            fromEndpointLayoutWidthPx = fromGeo?.measuredTextLayoutWidthPx,
                            toEndpointLayoutWidthPx = toGeo?.measuredTextLayoutWidthPx,
                        ) ?: eval.textState ?: DocumentTextRenderState.Rest(
                            payload = node.text ?: eval.toNode.text ?: eval.fromNode.text ?: TextPayload(RichTextValue.Plain("")),
                        )
                    }
                    val textSurface = node.text?.surface ?: eval.toNode.text?.surface ?: eval.fromNode.text?.surface
                    DocumentTextPayloadView(
                        renderState = lazyTextRenderState,
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
                        modifier = childMod,
                    )
                }
                NodeKind.Shape -> DocumentShapePayloadView(
                    payload = node.shape!!,
                    colorResolver = ctx.colorResolver,
                    density = density,
                    deckId = ctx.document.id,
                    assetsRootDir = ctx.config.assetsRootDir,
                    pathTrim = null,
                    modifier = childMod,
                    shapeReferenceSize = eval.shapeReferenceSize,
                )
                NodeKind.Image -> {
                    val img = node.image!!
                    DocumentImagePayloadView(
                        deckId = ctx.document.id,
                        src = img.src,
                        fit = img.fit ?: "contain",
                        tint = img.tint?.let { ctx.colorResolver.resolveOrNull(it) },
                        assetsRootDir = ctx.config.assetsRootDir,
                        modifier = childMod,
                        playback = overlayImagePlayback(
                            ctx,
                            slideId = if (entry is TravellingChildEntry.UnmatchedEntering) ctx.slide.id else fromSlide.id,
                            nodeId = node.id,
                            image = img,
                        ),
                    )
                }
                NodeKind.Connector -> DocumentConnectorPayloadView(
                    payload = node.connector!!,
                    colorResolver = ctx.colorResolver,
                    density = density,
                    pathTrim = null,
                    modifier = childMod,
                )
                NodeKind.Code -> WarpCodeAdapter(
                    rawNode = eval.toNode,
                    resolvedNode = node,
                    slide = ctx.slide,
                    frameTransition = null,
                    segmentPlan = SegmentPlan.Empty,
                    theme = ctx.theme,
                    colorResolver = ctx.colorResolver,
                    reducedMotion = ctx.reducedMotion,
                    modifier = childMod,
                    fontResolverHandle = ctx.fontResolverHandle,
                )
                NodeKind.Specimen -> {
                    val payload = node.specimen!!
                    val decl = ctx.config.specimens.find(payload.ref)
                    DocumentSpecimenPayloadView(
                        nodeId = node.id,
                        interactive = node.interactive,
                        payload = payload,
                        fromParams = eval.specimenFromParams,
                        toParams = eval.specimenToParams,
                        paramProgress = eval.specimenParamProgress,
                        trackProgress = buildTravellingSpecimenTrackProgress(
                            fromNode = eval.fromNode,
                            toNode = eval.toNode,
                            fromLastBeat = fromLastBeat,
                            moveProgress = moveProgress,
                            specimenDecl = decl,
                        ),
                        ctx = ctx,
                        modifier = childMod,
                        isTravelling = true,
                    )
                }
                null -> Unit
            }
        }
        is TravellingChildEntry.UnmatchedExiting -> {
            RenderStaticOverlaySubtree(
                node = entry.fromNode,
                rawNode = entry.rawFromNode,
                slide = fromSlide,
                restingBeat = fromLastBeat,
                isFromSide = true,
                transitionSpec = transitionSpec,
                toSlideTransition = toSlideTransition,
                ctx = ctx.copy(
                    slide = fromSlide,
                    resolveNodeAtBeat = JsonMergePatch::resolveNodeAtBeat,
                    resolveNodeAfterBuild = JsonMergePatch::resolveNodeAfterBuild,
                ),
                maxWidthPx = maxWidthPx,
                modifier = childMod,
                effectiveAlpha = exitAlpha,
            )
        }
        is TravellingChildEntry.UnmatchedEntering -> {
            RenderStaticOverlaySubtree(
                node = entry.toNode,
                rawNode = entry.rawToNode,
                slide = ctx.slide,
                restingBeat = -1,
                isFromSide = false,
                transitionSpec = transitionSpec,
                toSlideTransition = toSlideTransition,
                ctx = ctx,
                maxWidthPx = maxWidthPx,
                modifier = childMod,
                effectiveAlpha = enterAlpha,
            )
        }
    }
}
