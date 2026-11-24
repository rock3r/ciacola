package dev.sebastiano.ciacola.document.render

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Constraints
import dev.sebastiano.ciacola.document.CrossSlideGeometry
import dev.sebastiano.ciacola.document.CrossSlideOwner
import dev.sebastiano.ciacola.document.DeckClock
import dev.sebastiano.ciacola.document.DeckDocument
import dev.sebastiano.ciacola.document.DeckThemeSpec
import dev.sebastiano.ciacola.document.DecomposedTransform
import dev.sebastiano.ciacola.document.JsonMergePatch
import dev.sebastiano.ciacola.document.MatchedPair
import dev.sebastiano.ciacola.document.NodeKind
import dev.sebastiano.ciacola.document.NodeSpec
import dev.sebastiano.ciacola.document.RenderSession
import dev.sebastiano.ciacola.document.RichTextValue
import dev.sebastiano.ciacola.document.SegmentPlan
import dev.sebastiano.ciacola.document.SlideSpec
import dev.sebastiano.ciacola.document.SpecimenDeclaration
import dev.sebastiano.ciacola.document.TextPayload
import dev.sebastiano.ciacola.document.TrackHandle
import dev.sebastiano.ciacola.document.TrackKind
import dev.sebastiano.ciacola.document.TrackPlan
import dev.sebastiano.ciacola.document.TransitionSpec
import dev.sebastiano.ciacola.effects.LocalStageAnchorRegistry
import dev.sebastiano.ciacola.effects.StageAnchorRegistry
import dev.sebastiano.ciacola.effects.v2.SlideTransitionComposition
import dev.sebastiano.ciacola.inspector.LocalEffectiveBackgroundColor
import dev.sebastiano.ciacola.theme.DeckTheme
import dev.sebastiano.ciacola.theme.LocalSlidePalette
import dev.sebastiano.ciacola.theme.SlidePalette
import kotlin.math.roundToInt

private sealed interface RootOverlayEntry {
    data class Matched(val pair: MatchedPair) : RootOverlayEntry
    data class Exiting(val rawNode: NodeSpec, val node: NodeSpec) : RootOverlayEntry
    data class Entering(val rawNode: NodeSpec, val node: NodeSpec) : RootOverlayEntry
}

private class RootOverlaySkeleton(
    val entries: List<RootOverlayEntry>,
    val collidingUnmatchedIds: Set<String>,
    val exitingBaseOpacityById: Map<String, Float>,
    val enteringBaseOpacityById: Map<String, Float>,
)

internal fun containsUnsizedZeroSpecimen(
    node: NodeSpec,
    slideId: String,
    slotPool: MovableSpecimenSlotPool?,
): Boolean {
    if (node.kind == NodeKind.Specimen) {
        val explicitZero = node.frame?.w == 0.0 || node.frame?.h == 0.0
        return !explicitZero && slotPool?.lastKnownSize(slideId, node.id) == null
    }
    return node.children.any { containsUnsizedZeroSpecimen(it, slideId, slotPool) }
}

/**
 * Renders the anchor-interleaved Root Transition Overlay (`doc-transition-overlay`) during
 * cross-slide `magicMove` transitions, keeping unmatched exiting/entering root nodes in exact
 * anchor Z-order with matched travelling nodes and nested matched pairs.
 */
@Composable
internal fun DocumentMagicMoveOverlayLayer(
    doc: DeckDocument,
    nonSkipped: List<SlideSpec>,
    fromSlide: SlideSpec,
    toSlide: SlideSpec,
    transitionSpec: TransitionSpec,
    composition: SlideTransitionComposition,
    plan: SegmentPlan,
    matchedPairs: List<MatchedPair>,
    boundClock: DeckClock,
    session: RenderSession,
    config: DocumentRenderConfig,
    theme: DeckTheme,
    activeThemeSpec: DeckThemeSpec?,
    fontResolverHandle: Any?,
    reducedMotion: Boolean,
    stageSize: Size,
    renderCache: DocumentRenderCache,
    lookaheadStore: CrossSlideLookaheadGeometryStore?,
    overlayAnchorRegistry: StageAnchorRegistry?,
) {
    val colorResolverA = remember(activeThemeSpec, theme, fromSlide.palette, doc.palettes) {
        DocumentColorResolver(
            themeSpec = activeThemeSpec,
            baseTheme = theme,
            slidePaletteSpec = fromSlide.palette?.let { doc.palettes[it] },
        )
    }
    val slidePaletteA = remember(fromSlide.palette, doc.palettes, colorResolverA) {
        resolveSlidePalette(fromSlide, doc, colorResolverA)
    }
    val solidBgA = remember(fromSlide.background, colorResolverA) {
        resolveEffectiveSolidBackground(fromSlide, colorResolverA)
    }
    val colorResolverB = remember(activeThemeSpec, theme, toSlide.palette, doc.palettes) {
        DocumentColorResolver(
            themeSpec = activeThemeSpec,
            baseTheme = theme,
            slidePaletteSpec = toSlide.palette?.let { doc.palettes[it] },
        )
    }
    val slidePaletteB = remember(toSlide.palette, doc.palettes, colorResolverB) {
        resolveSlidePalette(toSlide, doc, colorResolverB)
    }
    val solidBgB = remember(toSlide.background, colorResolverB) {
        resolveEffectiveSolidBackground(toSlide, colorResolverB)
    }
    val fromLastBeat = fromSlide.beats.lastIndex.coerceAtLeast(0)
    val toSlideTransition = effectiveSlideTransition(toSlide, activeThemeSpec)
    val overlaySceneIdxA = nonSkipped.indexOfFirst { it.id == fromSlide.id }.coerceAtLeast(0)
    val overlaySceneIdxB = nonSkipped.indexOfFirst { it.id == toSlide.id }.coerceAtLeast(0)

    val pairsById = matchedPairs.associateBy { it.nodeId }
    val matchedPairIds = pairsById.keys

    fun buildOverlayCtx(
        targetSlide: SlideSpec,
        sceneIdx: Int,
        beatIdx: Int,
        palette: SlidePalette?,
        solidBg: Color?,
        resolver: DocumentColorResolver,
        incoming: TransitionSpec?,
    ) = DocumentNodeRenderContext(
        document = doc,
        slide = targetSlide,
        sceneIndex = sceneIdx,
        beatIndex = beatIdx,
        clock = boundClock,
        segmentPlan = plan,
        frameTransition = null,
        session = session,
        config = config,
        theme = theme,
        slidePalette = palette,
        effectiveSolidBg = solidBg,
        colorResolver = resolver,
        fontResolverHandle = fontResolverHandle,
        reducedMotion = reducedMotion,
        stageSize = stageSize,
        isAnimatedMount = true,
        suppressedNodeIds = matchedPairIds,
        movableSpecimenSlotKeys = renderCache.movableSpecimenSlotKeys,
        incomingTransition = incoming,
        resolveNodeAtBeat = { rawNode, beat ->
            renderCache.resolveNodeAtBeat(targetSlide.id, rawNode, beat)
        },
        resolveNodeAfterBuild = { rawNode, bIdx ->
            renderCache.resolveNodeAfterBuild(targetSlide.id, rawNode, bIdx)
        },
        lookaheadStore = lookaheadStore,
    )

    val overlayCtxA = buildOverlayCtx(
        targetSlide = fromSlide,
        sceneIdx = overlaySceneIdxA,
        beatIdx = fromLastBeat,
        palette = slidePaletteA,
        solidBg = solidBgA,
        resolver = colorResolverA,
        incoming = renderCache.incomingTransitionForSlide(fromSlide.id),
    )
    val overlayCtxB = buildOverlayCtx(
        targetSlide = toSlide,
        sceneIdx = overlaySceneIdxB,
        beatIdx = 0,
        palette = slidePaletteB,
        solidBg = solidBgB,
        resolver = colorResolverB,
        incoming = transitionSpec,
    )

    CompositionLocalProvider(
        LocalStageAnchorRegistry provides (LocalStageAnchorRegistry.current ?: overlayAnchorRegistry),
    ) {
        // Unbounded top-left root overlay layout so oversized nodes (w > stageW or h > stageH)
        // never undergo Compose Placeable constraint-coercion centering (`apparentToRealOffset`).
        Layout(
            modifier = Modifier.fillMaxSize().testTag("doc-transition-overlay"),
            content = {
                val currentOwner = CrossSlideGeometry.owner(
                    fromSlideId = fromSlide.id,
                    toSlideId = toSlide.id,
                    clock = boundClock,
                )
                if (currentOwner == CrossSlideOwner.Overlay) {
                    val exitAlphas = HashMap<String, Float>()
                    val enterAlphas = HashMap<String, Float>()
                    val moveTracksById = HashMap<String, TrackPlan>()
                    for (track in plan.tracks) {
                        when (track.kind) {
                            TrackKind.TransitionExit -> {
                                exitAlphas[track.nodeId] = 1f - track.progressAt(boundClock.t).coerceIn(0f, 1f)
                            }
                            TrackKind.TransitionEnter -> {
                                enterAlphas[track.nodeId] = track.progressAt(boundClock.t).coerceIn(0f, 1f)
                            }
                            TrackKind.TransitionMove -> {
                                moveTracksById.putIfAbsent(track.nodeId, track)
                            }
                            else -> Unit
                        }
                    }
                    val defaultExitAlpha = 1f - (composition.magicMove?.overlayProgress ?: boundClock.progress())
                    val defaultEnterAlpha = composition.magicMove?.overlayProgress ?: boundClock.progress()

                    val skeleton = remember(fromSlide, toSlide, matchedPairs, renderCache) {
                        val presentFrom = fromSlide.nodes
                            .filter { it.kind == NodeKind.Loupe || JsonMergePatch.isNodePresentAtBeat(it, fromLastBeat) }
                            .map { it to renderCache.resolveNodeAtBeat(fromSlide.id, it, fromLastBeat) }
                        val presentTo = toSlide.nodes
                            .filter { it.kind == NodeKind.Loupe || JsonMergePatch.isNodePresentAtStart(it) }
                            .map { it to renderCache.resolveNodeAtBeat(toSlide.id, it, 0) }
                        val toRootIds = presentTo.mapTo(HashSet(presentTo.size)) { it.second.id }

                        fun containsNodeId(node: NodeSpec, targetId: String): Boolean {
                            if (node.id == targetId) return true
                            return node.children.any { containsNodeId(it, targetId) }
                        }

                        val nestedPairsByToRootId = HashMap<String, MutableList<MatchedPair>>()
                        val unanchoredPairs = ArrayList<MatchedPair>()
                        for (pair in matchedPairs) {
                            if (pair.nodeId !in toRootIds) {
                                val hostRoot = presentTo.firstOrNull { containsNodeId(it.second, pair.nodeId) }?.second
                                if (hostRoot != null) {
                                    nestedPairsByToRootId.getOrPut(hostRoot.id) { ArrayList() }.add(pair)
                                } else {
                                    unanchoredPairs += pair
                                }
                            }
                        }

                        val entries = ArrayList<RootOverlayEntry>(presentFrom.size + presentTo.size + matchedPairs.size)
                        interleaveByAnchor(
                            presentFrom = presentFrom,
                            presentTo = presentTo,
                            matchedIds = matchedPairIds,
                            idOfFrom = { it.second.id },
                            idOfTo = { it.second.id },
                            emitMatched = { (_, toNode) ->
                                pairsById[toNode.id]?.let { entries += RootOverlayEntry.Matched(it) }
                            },
                            emitUnmatchedExiting = { (rawFrom, fromNode) ->
                                entries += RootOverlayEntry.Exiting(rawFrom, fromNode)
                            },
                            emitUnmatchedEntering = { (rawTo, toNode) ->
                                entries += RootOverlayEntry.Entering(rawTo, toNode)
                                nestedPairsByToRootId[toNode.id]?.forEach { nestedPair ->
                                    entries += RootOverlayEntry.Matched(nestedPair)
                                }
                            },
                        )
                        for (pair in unanchoredPairs) {
                            entries += RootOverlayEntry.Matched(pair)
                        }

                        val exitingOpacityById = HashMap<String, Float>()
                        val enteringOpacityById = HashMap<String, Float>()
                        for (e in entries) {
                            when (e) {
                                is RootOverlayEntry.Exiting -> exitingOpacityById[e.node.id] = e.node.opacity.toFloat()
                                is RootOverlayEntry.Entering -> enteringOpacityById[e.node.id] = e.node.opacity.toFloat()
                                is RootOverlayEntry.Matched -> Unit
                            }
                        }
                        val colliding = HashSet<String>()
                        for (id in exitingOpacityById.keys) {
                            if (id in enteringOpacityById) colliding += id
                        }
                        RootOverlaySkeleton(
                            entries = entries,
                            collidingUnmatchedIds = colliding,
                            exitingBaseOpacityById = exitingOpacityById,
                            enteringBaseOpacityById = enteringOpacityById,
                        )
                    }

                    for (entry in skeleton.entries) {
                        when (entry) {
                            is RootOverlayEntry.Matched -> key("overlay:${entry.pair.nodeId}") {
                                val moveTrack = moveTracksById[entry.pair.nodeId]
                                val eval = DocumentMotionEvaluator.evaluateTravellingNode(
                                    pair = entry.pair,
                                    moveTrack = moveTrack,
                                    clock = boundClock,
                                    transitionSpec = transitionSpec,
                                    colorResolver = colorResolverB,
                                    toSlideTransition = toSlideTransition,
                                    fromSlideLastBeat = fromLastBeat,
                                    resolveFromNodeAfterBuild = { raw, idx ->
                                        renderCache.resolveNodeAfterBuild(fromSlide.id, raw, idx)
                                    },
                                    resolveToNodeAfterBuild = { raw, idx ->
                                        renderCache.resolveNodeAfterBuild(toSlide.id, raw, idx)
                                    },
                                )
                                DocumentTravellingOverlayNode(
                                    eval = eval,
                                    fromSlide = fromSlide,
                                    transitionSpec = transitionSpec,
                                    toSlideTransition = toSlideTransition,
                                    moveTrack = moveTrack,
                                    ctx = overlayCtxB,
                                )
                            }
                            is RootOverlayEntry.Exiting -> key("overlay-exit:${entry.node.id}") {
                                val exitAlpha = (exitAlphas[entry.node.id] ?: defaultExitAlpha).coerceIn(0f, 1f)
                                val hooksOwned = if (entry.node.id in skeleton.collidingUnmatchedIds) {
                                    val enterAlpha = (enterAlphas[entry.node.id] ?: defaultEnterAlpha).coerceIn(0f, 1f)
                                    val exitEff = exitAlpha * entry.node.opacity.toFloat()
                                    val enterEff = enterAlpha * (skeleton.enteringBaseOpacityById[entry.node.id] ?: 1f)
                                    exitEff > enterEff
                                } else {
                                    true
                                }
                                DocumentStaticRootOverlayNode(
                                    node = entry.node,
                                    rawNode = entry.rawNode,
                                    slide = fromSlide,
                                    restingBeat = fromLastBeat,
                                    isFromSide = true,
                                    trackAlpha = exitAlpha,
                                    transitionSpec = transitionSpec,
                                    toSlideTransition = toSlideTransition,
                                    ctx = overlayCtxA,
                                    hooksEnabled = hooksOwned,
                                )
                            }
                            is RootOverlayEntry.Entering -> key("overlay-enter:${entry.node.id}") {
                                val enterAlpha = (enterAlphas[entry.node.id] ?: defaultEnterAlpha).coerceIn(0f, 1f)
                                val hooksOwned = if (entry.node.id in skeleton.collidingUnmatchedIds) {
                                    val exitAlpha = (exitAlphas[entry.node.id] ?: defaultExitAlpha).coerceIn(0f, 1f)
                                    val enterEff = enterAlpha * entry.node.opacity.toFloat()
                                    val exitEff = exitAlpha * (skeleton.exitingBaseOpacityById[entry.node.id] ?: 1f)
                                    enterEff >= exitEff
                                } else {
                                    true
                                }
                                DocumentStaticRootOverlayNode(
                                    node = entry.node,
                                    rawNode = entry.rawNode,
                                    slide = toSlide,
                                    restingBeat = -1,
                                    isFromSide = false,
                                    trackAlpha = enterAlpha,
                                    transitionSpec = transitionSpec,
                                    toSlideTransition = toSlideTransition,
                                    ctx = overlayCtxB,
                                    hooksEnabled = hooksOwned,
                                )
                            }
                        }
                    }
                }
            },
        ) { measurables, constraints ->
            val childConstraints = Constraints(
                minWidth = 0,
                maxWidth = Constraints.Infinity,
                minHeight = 0,
                maxHeight = Constraints.Infinity,
            )
            val placeables = ArrayList<Placeable>(measurables.size)
            for (measurable in measurables) {
                placeables += measurable.measure(childConstraints)
            }
            val outW = if (constraints.hasBoundedWidth) {
                constraints.maxWidth
            } else {
                placeables.maxOfOrNull { it.width } ?: constraints.minWidth
            }
            val outH = if (constraints.hasBoundedHeight) {
                constraints.maxHeight
            } else {
                placeables.maxOfOrNull { it.height } ?: constraints.minHeight
            }
            layout(outW, outH) {
                for (p in placeables) {
                    p.place(0, 0)
                }
            }
        }
    }
}

/**
 * Merges [presentFrom] and [presentTo] in anchor-interleaved Z-order:
 * - Unmatched exiting items in [presentFrom] before the first matched item stay below everything.
 * - Walking [presentTo] in document order, each unmatched entering item emits in place, and each
 *   matched item emits followed immediately by any unmatched exiting items anchored after it in
 *   [presentFrom].
 */
internal inline fun <F, T> interleaveByAnchor(
    presentFrom: List<F>,
    presentTo: List<T>,
    matchedIds: Set<String>,
    idOfFrom: (F) -> String,
    idOfTo: (T) -> String,
    emitMatched: (T) -> Unit,
    emitUnmatchedExiting: (F) -> Unit,
    emitUnmatchedEntering: (T) -> Unit,
) {
    val exitsBeforeFirstMatch = ArrayList<F>()
    val exitsAfterAnchor = LinkedHashMap<String, MutableList<F>>()
    var currentAnchorId: String? = null
    for (fromItem in presentFrom) {
        val id = idOfFrom(fromItem)
        if (id in matchedIds) {
            currentAnchorId = id
        } else if (currentAnchorId == null) {
            exitsBeforeFirstMatch += fromItem
        } else {
            exitsAfterAnchor.getOrPut(currentAnchorId) { ArrayList() }.add(fromItem)
        }
    }

    for (exitItem in exitsBeforeFirstMatch) {
        emitUnmatchedExiting(exitItem)
    }
    val visitedMatched = HashSet<String>(matchedIds.size)
    for (toItem in presentTo) {
        val id = idOfTo(toItem)
        if (id in matchedIds) {
            visitedMatched += id
            emitMatched(toItem)
            val anchoredExits = exitsAfterAnchor[id]
            if (anchoredExits != null) {
                for (exitItem in anchoredExits) {
                    emitUnmatchedExiting(exitItem)
                }
            }
        } else {
            emitUnmatchedEntering(toItem)
        }
    }
    for ((anchorId, exits) in exitsAfterAnchor) {
        if (anchorId !in visitedMatched) {
            for (exitItem in exits) {
                emitUnmatchedExiting(exitItem)
            }
        }
    }
}

/**
 * Renders an unmatched exiting (`SceneA`) or entering (`SceneB`) root node inside
 * `doc-transition-overlay` at its Lookahead-measured stage position and size, interleaved in
 * anchor Z-order with matched travelling nodes.
 */
@Composable
internal fun DocumentStaticRootOverlayNode(
    node: NodeSpec,
    slide: SlideSpec,
    restingBeat: Int,
    isFromSide: Boolean,
    trackAlpha: Float,
    transitionSpec: TransitionSpec,
    toSlideTransition: TransitionSpec?,
    ctx: DocumentNodeRenderContext,
    hooksEnabled: Boolean = true,
    rawNode: NodeSpec = node,
) {
    val lookaheadStore = ctx.lookaheadStore
    val slotPool = LocalMovableSpecimenSlotPool.current
    val slideId = slide.id
    val stageW = ctx.stageSize.width.roundToInt().coerceAtLeast(1)
    val stageH = ctx.stageSize.height.roundToInt().coerceAtLeast(1)
    val rawEffectiveAlpha = (node.opacity.toFloat() * trackAlpha).coerceIn(0f, 1f)
    val effectiveAlpha = if (rawEffectiveAlpha >= 0.9999f) 1f else rawEffectiveAlpha
    val stageHooksMod = rememberStageNodeHooksModifier(
        nodeId = node.id,
        node = node,
        sceneIndex = ctx.sceneIndex,
        beatIndex = ctx.beatIndex,
        effectiveAlphaProvider = { effectiveAlpha },
        enabled = hooksEnabled,
    )
    val rootMod = Modifier
        .testTag(if (isFromSide) "doc-overlay-exit-${node.id}" else "doc-overlay-enter-${node.id}")
        .trackLookaheadGeometry(lookaheadStore)
        .graphicsLayer {
            val t = lookaheadStore?.get(slideId, node.id)?.decomposed
                ?: CrossSlideGeometry.nodeLocalTransform(
                    node = node,
                    x = node.frame?.x ?: 0.0,
                    y = node.frame?.y ?: 0.0,
                    w = node.frame?.w ?: 0.0,
                    h = node.frame?.h ?: 0.0,
                ).decompose()
                ?: DecomposedTransform(
                    tx = node.frame?.x ?: 0.0,
                    ty = node.frame?.y ?: 0.0,
                    rotationDeg = 0.0,
                    scaleX = 1.0,
                    scaleY = 1.0,
                )
            transformOrigin = TransformOrigin(0f, 0f)
            translationX = t.tx.toFloat()
            translationY = t.ty.toFloat()
            rotationZ = t.rotationDeg.toFloat()
            scaleX = t.scaleX.toFloat()
            scaleY = t.scaleY.toFloat()
            alpha = effectiveAlpha
        }
        .then(stageHooksMod)
        .approachSlideConstraints(
            store = lookaheadStore,
            progressKey = trackAlpha,
        ) { _ ->
            val geo = lookaheadStore?.get(slideId, node.id)
            val rawW = geo?.widthPx ?: node.frame?.w?.roundToInt()
            val rawH = geo?.heightPx ?: node.frame?.h?.roundToInt()
            val ignoreZeroW = rawW == 0 && node.frame?.w == null && containsUnsizedZeroSpecimen(node, slideId, slotPool)
            val ignoreZeroH = rawH == 0 && node.frame?.h == null && containsUnsizedZeroSpecimen(node, slideId, slotPool)
            val w = if (ignoreZeroW) null else rawW
            val h = if (ignoreZeroH) null else rawH
            when {
                w != null && h != null -> Constraints.fixed(w, h)
                w != null -> Constraints(minWidth = w, maxWidth = w, minHeight = 0, maxHeight = stageH)
                h != null -> Constraints(minWidth = 0, maxWidth = stageW, minHeight = h, maxHeight = h)
                else -> Constraints(minWidth = 0, maxWidth = stageW, minHeight = 0, maxHeight = stageH)
            }
        }

    CompositionLocalProvider(
        LocalSlidePalette provides ctx.slidePalette,
        LocalEffectiveBackgroundColor provides (ctx.effectiveSolidBg ?: ctx.theme.colors.background),
    ) {
        RenderStaticOverlaySubtree(
            node = node,
            rawNode = rawNode,
            slide = slide,
            restingBeat = restingBeat,
            isFromSide = isFromSide,
            transitionSpec = transitionSpec,
            toSlideTransition = toSlideTransition,
            ctx = ctx,
            maxWidthPx = resolveStackChildMaxWidthPx(node, ctx.stageSize.width.roundToInt()),
            modifier = rootMod,
            effectiveAlpha = trackAlpha.coerceIn(0f, 1f),
            hooksEnabled = hooksEnabled,
            attachSelfHooks = false,
        )
    }
}

/**
 * Renders a single travelling matched cross-slide `magicMove` node in the Root Transition Overlay
 * at stage origin `(0, 0)` transformed via [EvaluatedTravellingNode.transform] (`TransformOrigin(0, 0)`)
 * and completely unclipped.
 */
@Composable
internal fun DocumentTravellingOverlayNode(
    eval: EvaluatedTravellingNode,
    fromSlide: SlideSpec,
    transitionSpec: TransitionSpec,
    toSlideTransition: TransitionSpec?,
    moveTrack: TrackPlan?,
    ctx: DocumentNodeRenderContext,
) {
    val density = LocalDensity.current
    val node = eval.resolvedNode
    val pair = eval.pair
    val fromLastBeat = fromSlide.beats.lastIndex.coerceAtLeast(0)
    val lookaheadStore = ctx.lookaheadStore
    val fromSlideId = fromSlide.id
    val toSlideId = ctx.slide.id
    val p = eval.moveProgress.coerceIn(0f, 1f)
    val slotPool = LocalMovableSpecimenSlotPool.current
    val stageHooksMod = rememberStageNodeHooksModifier(
        nodeId = pair.nodeId,
        node = node,
        sceneIndex = ctx.sceneIndex,
        beatIndex = ctx.beatIndex,
        effectiveAlphaProvider = { (node.opacity.toFloat() * eval.opacity).coerceIn(0f, 1f) },
        enabled = true,
    )

    val overlayTransformMod = Modifier
        .testTag("doc-overlay-node-${pair.nodeId}")
        .trackLookaheadGeometry(lookaheadStore)
        .graphicsLayer {
            val fromT = lookaheadStore?.get(fromSlideId, pair.nodeId)?.decomposed ?: pair.fromTransform
            val toT = lookaheadStore?.get(toSlideId, pair.nodeId)?.decomposed ?: pair.toTransform
            val t = DecomposedTransform.lerp(fromT, toT, p.toDouble())
            transformOrigin = TransformOrigin(0f, 0f)
            translationX = t.tx.toFloat()
            translationY = t.ty.toFloat()
            rotationZ = t.rotationDeg.toFloat()
            scaleX = t.scaleX.toFloat()
            scaleY = t.scaleY.toFloat()
            alpha = eval.opacity
        }
        .then(stageHooksMod)
        .approachSlideConstraints(
            store = lookaheadStore,
            progressKey = p,
        ) { _ ->
            // Compose guarantees approachMeasure executes strictly after SceneA/SceneB Lookahead
            // placements have populated lookaheadStore on the very same frame (zero 1-frame lag).
            val stageW = ctx.stageSize.width.roundToInt().coerceAtLeast(1)
            val stageH = ctx.stageSize.height.roundToInt().coerceAtLeast(1)
            val fromGeo = lookaheadStore?.get(fromSlideId, pair.nodeId)
            val toGeo = lookaheadStore?.get(toSlideId, pair.nodeId)
            val rawFromW = fromGeo?.widthPx ?: pair.fromNode.frame?.w?.roundToInt()
            val rawFromH = fromGeo?.heightPx ?: pair.fromNode.frame?.h?.roundToInt()
            val rawToW = toGeo?.widthPx ?: pair.toNode.frame?.w?.roundToInt()
            val rawToH = toGeo?.heightPx ?: pair.toNode.frame?.h?.roundToInt()
            val fromW = if (rawFromW == 0 && pair.fromNode.frame?.w == null && containsUnsizedZeroSpecimen(pair.fromNode, fromSlideId, slotPool)) null else rawFromW
            val fromH = if (rawFromH == 0 && pair.fromNode.frame?.h == null && containsUnsizedZeroSpecimen(pair.fromNode, fromSlideId, slotPool)) null else rawFromH
            val toW = if (rawToW == 0 && pair.toNode.frame?.w == null && containsUnsizedZeroSpecimen(pair.toNode, toSlideId, slotPool)) null else rawToW
            val toH = if (rawToH == 0 && pair.toNode.frame?.h == null && containsUnsizedZeroSpecimen(pair.toNode, toSlideId, slotPool)) null else rawToH
            when {
                fromW != null && toW != null && fromH != null && toH != null -> {
                    val iw = (fromW + (toW - fromW) * p).roundToInt().coerceAtLeast(0)
                    val ih = (fromH + (toH - fromH) * p).roundToInt().coerceAtLeast(0)
                    Constraints.fixed(iw, ih)
                }
                fromW != null && toW != null -> {
                    val iw = (fromW + (toW - fromW) * p).roundToInt().coerceAtLeast(0)
                    Constraints(minWidth = iw, maxWidth = iw, minHeight = 0, maxHeight = stageH)
                }
                fromH != null && toH != null -> {
                    val ih = (fromH + (toH - fromH) * p).roundToInt().coerceAtLeast(0)
                    Constraints(minWidth = 0, maxWidth = stageW, minHeight = ih, maxHeight = ih)
                }
                else -> Constraints(minWidth = 0, maxWidth = stageW, minHeight = 0, maxHeight = stageH)
            }
        }

    when (node.kind) {
        NodeKind.Text -> {
            val lazyTextRenderState: () -> DocumentTextRenderState = {
                val fromGeo = lookaheadStore?.get(fromSlideId, pair.nodeId)
                val toGeo = lookaheadStore?.get(toSlideId, pair.nodeId)
                DocumentMotionEvaluator.resolveTravellingTextRenderState(
                    fromNode = pair.fromNode,
                    toNode = pair.toNode,
                    nodeId = pair.nodeId,
                    fromEndpointMinWidthPx = fromGeo?.measuredTextMinWidthPx,
                    toEndpointMinWidthPx = toGeo?.measuredTextMinWidthPx,
                    fromEndpointMaxWidthPx = fromGeo?.measuredTextMaxWidthPx,
                    toEndpointMaxWidthPx = toGeo?.measuredTextMaxWidthPx,
                    fromEndpointSurfacePadPx = fromGeo?.surfaceHorizontalPadPx,
                    toEndpointSurfacePadPx = toGeo?.surfaceHorizontalPadPx,
                    fallbackFromAvailableWidth = pair.fromAvailableWidth,
                    fallbackToAvailableWidth = pair.toAvailableWidth,
                    moveTrack = moveTrack,
                    clock = ctx.clock,
                    transitionSpec = transitionSpec,
                    toSlideTransition = toSlideTransition,
                    isDescendant = false,
                    fromEndpointLayoutWidthPx = fromGeo?.measuredTextLayoutWidthPx,
                    toEndpointLayoutWidthPx = toGeo?.measuredTextLayoutWidthPx,
                ) ?: eval.textState ?: DocumentTextRenderState.Rest(
                    payload = node.text ?: pair.toNode.text ?: pair.fromNode.text ?: TextPayload(RichTextValue.Plain("")),
                )
            }
            val textSurface = node.text?.surface ?: pair.toNode.text?.surface ?: pair.fromNode.text?.surface
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
                maxWidthPx = node.frame?.w?.roundToInt() ?: Int.MAX_VALUE,
                modifier = overlayTransformMod,
            )
        }
        NodeKind.Shape -> {
            DocumentShapePayloadView(
                payload = node.shape!!,
                colorResolver = ctx.colorResolver,
                density = density,
                deckId = ctx.document.id,
                assetsRootDir = ctx.config.assetsRootDir,
                pathTrim = null,
                modifier = overlayTransformMod,
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
                modifier = overlayTransformMod,
            )
        }
        NodeKind.Specimen -> {
            val payload = node.specimen!!
            val decl = ctx.config.specimens.find(payload.ref)
            DocumentSpecimenPayloadView(
                nodeId = pair.nodeId,
                interactive = node.interactive,
                payload = payload,
                fromParams = eval.specimenFromParams,
                toParams = eval.specimenToParams,
                paramProgress = eval.specimenParamProgress,
                trackProgress = buildTravellingSpecimenTrackProgress(
                    fromNode = pair.fromNode,
                    toNode = pair.toNode,
                    fromLastBeat = fromLastBeat,
                    moveProgress = eval.moveProgress,
                    specimenDecl = decl,
                ),
                ctx = ctx,
                modifier = overlayTransformMod,
                isTravelling = true,
            )
        }
        NodeKind.Code -> {
            WarpCodeAdapter(
                rawNode = pair.toNode,
                resolvedNode = node,
                slide = ctx.slide,
                frameTransition = null,
                segmentPlan = SegmentPlan.Empty,
                theme = ctx.theme,
                colorResolver = ctx.colorResolver,
                reducedMotion = ctx.reducedMotion,
                modifier = overlayTransformMod,
                fontResolverHandle = ctx.fontResolverHandle,
            )
        }
        NodeKind.Stack -> {
            TravellingStackContainer(
                fromNode = pair.fromNode,
                toNode = pair.toNode,
                resolvedNode = node,
                fromSlide = fromSlide,
                moveProgress = eval.moveProgress,
                exitAlpha = eval.exitAlpha,
                enterAlpha = eval.enterAlpha,
                transitionSpec = transitionSpec,
                toSlideTransition = toSlideTransition,
                moveTrack = moveTrack,
                ctx = ctx,
                modifier = overlayTransformMod,
                rawFromNode = pair.rawFromNode,
                rawToNode = pair.rawToNode,
            )
        }
        NodeKind.Row, NodeKind.Column -> {
            TravellingFlowContainer(
                fromNode = pair.fromNode,
                toNode = pair.toNode,
                resolvedNode = node,
                isRow = node.kind == NodeKind.Row,
                fromSlide = fromSlide,
                moveProgress = eval.moveProgress,
                exitAlpha = eval.exitAlpha,
                enterAlpha = eval.enterAlpha,
                transitionSpec = transitionSpec,
                toSlideTransition = toSlideTransition,
                moveTrack = moveTrack,
                ctx = ctx,
                modifier = overlayTransformMod,
                rawFromNode = pair.rawFromNode,
                rawToNode = pair.rawToNode,
            )
        }
        NodeKind.Connector -> {
            DocumentConnectorPayloadView(
                payload = node.connector!!,
                colorResolver = ctx.colorResolver,
                density = density,
                pathTrim = null,
                modifier = overlayTransformMod,
            )
        }
        NodeKind.Loupe -> {
            TravellingLoupeContainer(
                fromNode = pair.fromNode,
                toNode = pair.toNode,
                resolvedNode = node,
                fromSlide = fromSlide,
                moveProgress = eval.moveProgress,
                exitAlpha = eval.exitAlpha,
                enterAlpha = eval.enterAlpha,
                transitionSpec = transitionSpec,
                toSlideTransition = toSlideTransition,
                moveTrack = moveTrack,
                ctx = ctx,
                modifier = overlayTransformMod,
                rawFromNode = pair.rawFromNode,
                rawToNode = pair.rawToNode,
            )
        }
        null -> Unit
    }
}

internal fun buildTravellingSpecimenTrackProgress(
    fromNode: NodeSpec,
    toNode: NodeSpec,
    fromLastBeat: Int,
    moveProgress: Float,
    specimenDecl: SpecimenDeclaration?,
): (TrackHandle) -> Float {
    if (specimenDecl == null) return { 0f }
    val fromOverrides = fromNode.specimen?.tracks.orEmpty()
    val toOverrides = toNode.specimen?.tracks.orEmpty()
    val p = moveProgress.coerceIn(0f, 1f)
    return { handle ->
        val decl = specimenDecl.tracks.firstOrNull { it.name == handle.name }
        val fromBeat = fromOverrides[handle.name]?.beat ?: decl?.beat ?: 0
        val toBeat = toOverrides[handle.name]?.beat ?: decl?.beat ?: 0
        val fromVal = if (fromLastBeat >= fromBeat) 1f else 0f
        // At slide B's Start (before Entrance(B)), beat-0 and later tracks are at 0f.
        val toVal = if (toBeat < 0) 1f else 0f
        fromVal + (toVal - fromVal) * p
    }
}
