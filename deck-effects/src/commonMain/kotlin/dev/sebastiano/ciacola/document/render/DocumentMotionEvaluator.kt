package dev.sebastiano.ciacola.document.render

import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import dev.sebastiano.ciacola.document.Build
import dev.sebastiano.ciacola.document.Curve
import dev.sebastiano.ciacola.document.CurveEvaluator
import dev.sebastiano.ciacola.document.DeckClock
import dev.sebastiano.ciacola.document.DecomposedTransform
import dev.sebastiano.ciacola.document.DeliveryBy
import dev.sebastiano.ciacola.document.DocSegment
import dev.sebastiano.ciacola.document.JsonMergePatch
import dev.sebastiano.ciacola.document.MatchedPair
import dev.sebastiano.ciacola.document.MorphBy
import dev.sebastiano.ciacola.document.MotionChannelSpec
import dev.sebastiano.ciacola.document.NodeKind
import dev.sebastiano.ciacola.document.NodeSpec
import dev.sebastiano.ciacola.document.PaddingSpec
import dev.sebastiano.ciacola.document.SegmentPlan
import dev.sebastiano.ciacola.document.SlideSpec
import dev.sebastiano.ciacola.document.SpecimenDeclaration
import dev.sebastiano.ciacola.document.TextMorphSpec
import dev.sebastiano.ciacola.document.TextPayload
import dev.sebastiano.ciacola.document.TrackHandle
import dev.sebastiano.ciacola.document.TrackKind
import dev.sebastiano.ciacola.document.TrackPlan
import dev.sebastiano.ciacola.document.TransitionSpec
import dev.sebastiano.ciacola.effects.v2.BuildEffects
import dev.sebastiano.ciacola.effects.v2.EffectArgs
import dev.sebastiano.ciacola.effects.v2.EffectBuildKind
import dev.sebastiano.ciacola.effects.v2.EffectClip
import dev.sebastiano.ciacola.effects.v2.EffectEvalContext
import dev.sebastiano.ciacola.effects.v2.EffectTier
import dev.sebastiano.ciacola.effects.v2.LayerState
import dev.sebastiano.ciacola.effects.v2.PathTrimState
import kotlinx.serialization.json.JsonElement
import kotlin.math.roundToInt

/** Active Content-tier SkSL shader invocation for a node on the current frame. */
@Immutable
data class ContentEffectInvocation(
    val effectId: String,
    val kind: EffectBuildKind,
    val progress: Float,
    val args: EffectArgs,
)

/** Per-child delivery override propagated from a parent container's `by: child` enter/exit/emphasis build. */
@Immutable
data class ChildDeliveryOverride(
    val layerState: LayerState,
    val contentEffect: ContentEffectInvocation? = null,
)

/** Complete evaluated visual & motion state for a single node on a slide at the current [DeckClock]. */
@Immutable
data class EvaluatedNodeFrame(
    val resolvedNode: NodeSpec,
    val layerState: LayerState,
    val pathTrim: PathTrimState?,
    val contentEffect: ContentEffectInvocation?,
    val textState: DocumentTextRenderState?,
    val childDeliveries: Map<String, ChildDeliveryOverride>,
    val motionPathDelta: Offset,
    val isEffectivelyHidden: Boolean,
    val specimenFromParams: Map<String, JsonElement>,
    val specimenToParams: Map<String, JsonElement>?,
    val specimenParamProgress: Float,
    val specimenTrackProgress: (TrackHandle) -> Float,
    val shapeReferenceSize: Size? = null,
)

/** Evaluated travelling state for a matched cross-slide `magicMove` node in the root overlay. */
@Immutable
data class EvaluatedTravellingNode(
    val pair: MatchedPair,
    val resolvedNode: NodeSpec,
    val transform: DecomposedTransform,
    val opacity: Float,
    val moveProgress: Float,
    val exitAlpha: Float,
    val enterAlpha: Float,
    val textState: DocumentTextRenderState?,
    val specimenFromParams: Map<String, JsonElement>,
    val specimenToParams: Map<String, JsonElement>?,
    val specimenParamProgress: Float,
    val shapeReferenceSize: Size? = null,
)

/** Evaluated travelling state for a matched descendant inside a matched cross-slide container. */
@Immutable
data class EvaluatedTravellingDescendant(
    val fromNode: NodeSpec,
    val toNode: NodeSpec,
    val resolvedNode: NodeSpec,
    val moveProgress: Float,
    val textState: DocumentTextRenderState?,
    val specimenFromParams: Map<String, JsonElement>,
    val specimenToParams: Map<String, JsonElement>?,
    val specimenParamProgress: Float,
    val rawFromNode: NodeSpec = fromNode,
    val rawToNode: NodeSpec = toNode,
    val shapeReferenceSize: Size? = null,
)

/**
 * Pure per-frame node evaluator bridging `SegmentPlan` + `DeckClock` to `BuildEffects` and
 * `MagicTextV2Renderer`.
 */
object DocumentMotionEvaluator {

    /**
     * Returns `true` if [rawNode] uses character-level delivery (`by: character`, `keyboard`, or
     * character morph) on any beat or on either the outgoing ([slideTransition]) or incoming
     * ([incomingTransition]) slide transition so optional ligatures stay consistently disabled at
     * rest and in motion.
     */
    fun isCharacterDeliveryNode(
        rawNode: NodeSpec,
        slideTransition: TransitionSpec? = null,
        incomingTransition: TransitionSpec? = null,
    ): Boolean {
        if (rawNode.kind != NodeKind.Text) return false
        if (rawNode.text?.morph?.by == MorphBy.Character) return true
        if (slideTransition?.text?.get(rawNode.id)?.by == MorphBy.Character) return true
        if (incomingTransition?.text?.get(rawNode.id)?.by == MorphBy.Character) return true
        for (build in rawNode.builds) {
            when (build) {
                is Build.Enter -> if (build.by == DeliveryBy.Character || build.effect == BuildEffects.KEYBOARD) return true
                is Build.Exit -> if (build.by == DeliveryBy.Character || build.effect == BuildEffects.KEYBOARD) return true
                is Build.Emphasis -> if (build.by == DeliveryBy.Character) return true
                is Build.Change -> if (build.morph?.by == MorphBy.Character) return true
                is Build.Playback -> Unit
            }
        }
        return false
    }

    /**
     * Evaluates a node's resolved properties, transforms, layer/path/content build effects, text
     * state, child delivery slots, and specimen tracks for `(slide, clock, segmentPlan)`.
     */
    fun evaluateNode(
        rawNode: NodeSpec,
        slide: SlideSpec,
        clock: DeckClock,
        segmentPlan: SegmentPlan,
        stageSize: Size,
        densityValue: Float,
        colorResolver: DocumentColorResolver,
        reducedMotion: Boolean,
        specimenDecl: SpecimenDeclaration? = null,
        inheritedDelivery: ChildDeliveryOverride? = null,
        magicMoveUnmatchedAlpha: Float = 1f,
        incomingTransition: TransitionSpec? = null,
        resolveNodeAtBeat: (NodeSpec, Int) -> NodeSpec = JsonMergePatch::resolveNodeAtBeat,
        resolveNodeAfterBuild: (NodeSpec, Int) -> NodeSpec = JsonMergePatch::resolveNodeAfterBuild,
    ): EvaluatedNodeFrame {
        val lastBeat = slide.beats.lastIndex.coerceAtLeast(0)
        val (fromBeat, toBeat, activeBuildBeat, isRestingOnSlide) = resolveSlideBeatsForClock(
            slide = slide,
            clock = clock,
            lastBeat = lastBeat,
        )

        val fromNode = resolveNodeAtBeat(rawNode, fromBeat)
        val toNode = if (toBeat == fromBeat) fromNode else resolveNodeAtBeat(rawNode, toBeat)

        // 1. Visibility & resting state outside an active Enter/Exit build.
        var baseLayer = inheritedDelivery?.layerState ?: LayerState.Identity
        var contentEffect: ContentEffectInvocation? = inheritedDelivery?.contentEffect
        var pathTrim: PathTrimState? = null
        val childDeliveries = LinkedHashMap<String, ChildDeliveryOverride>()
        var textBuildState: DocumentTextRenderState.Build? = null

        val activeBuildsWithIndex: List<Pair<Int, Build>> = if (activeBuildBeat != null) {
            rawNode.builds.withIndex()
                .filter { it.value.beat == activeBuildBeat }
                .map { it.index to it.value }
        } else {
            emptyList()
        }

        val hasActiveEnter = activeBuildsWithIndex.any { it.second is Build.Enter }
        val hasActiveExit = activeBuildsWithIndex.any { it.second is Build.Exit }

        // Nodes on slide B with a beat-0 `enter` build are pending entrance throughout
        // both Cross(A, B) AND AtStart(B); they only animate in during Entrance(B).
        val isPreEntrance = (clock.segment as? DocSegment.AtStart)?.slideId == slide.id ||
            (clock.segment as? DocSegment.Cross)?.toSlideId == slide.id

        val checkBeatForVisibility = when {
            isPreEntrance -> -1
            isRestingOnSlide -> toBeat
            else -> fromBeat
        }

        val isInitiallyHidden = isHiddenAtBeat(rawNode.builds, checkBeatForVisibility)
        var nodeVisibleByTimeline = !isInitiallyHidden || hasActiveEnter

        // 2. Interpolate active Change builds sequentially (in list order) and motion path delta.
        var resolvedNode = if (isRestingOnSlide) toNode else fromNode
        var motionPathDelta = Offset.Zero
        var textMorphState: DocumentTextRenderState.Morph? = null
        var specimenParamProgress = if (isRestingOnSlide) 1f else 0f
        var fromSpecParams = fromNode.specimen?.params.orEmpty()
        var toSpecParams: Map<String, JsonElement>? = if (toBeat != fromBeat) toNode.specimen?.params else null
        var shapeRefSize: Size? = null
        var lastCompletedBuildIdx: Int? = null

        if (!isRestingOnSlide && activeBuildBeat != null) {
            val changeBuilds = activeBuildsWithIndex.filter { it.second is Build.Change }
            if (changeBuilds.isNotEmpty()) {
                if (reducedMotion && changeBuilds.size > 1) {
                    val firstEntry = changeBuilds.first()
                    val lastEntry = changeBuilds.last()
                    val firstChange = firstEntry.second as Build.Change
                    val lastChange = lastEntry.second as Build.Change
                    val track = findTrackPlan(segmentPlan, rawNode.id, TrackKind.Change, firstChange.id, firstEntry.first)
                        ?: findTrackPlan(segmentPlan, rawNode.id, TrackKind.Change, lastChange.id, lastEntry.first)
                    if (track != null) {
                        val easedP = track.progressAt(clock.t)
                        val finalTarget = resolveNodeAfterBuild(rawNode, lastEntry.first)
                        resolvedNode = interpolateNodeProperties(
                            from = fromNode,
                            to = finalTarget,
                            t = easedP,
                            colorResolver = colorResolver,
                            interpolateFramePosition = true,
                        )
                        shapeRefSize = resolveChangeShapeReferenceSize(
                            rawNode = rawNode,
                            fromNode = fromNode,
                            toNode = finalTarget,
                            fromUpToBeat = fromBeat,
                            fromUpToBuildIndex = null,
                            toUpToBeat = activeBuildBeat,
                            toUpToBuildIndex = lastEntry.first,
                            t = easedP,
                            resolveNodeAfterBuild = resolveNodeAfterBuild,
                        )
                        if (rawNode.kind == NodeKind.Specimen) {
                            specimenParamProgress = easedP
                            toSpecParams = finalTarget.specimen?.params
                        }
                        textMorphState = buildChangeTextMorphState(rawNode, fromNode.text, finalTarget.text, lastChange, track, clock)
                    }
                } else {
                    var currentState = fromNode
                    for ((bIdx, build) in changeBuilds) {
                        val change = build as Build.Change
                        val targetAfterThis = resolveNodeAfterBuild(rawNode, bIdx)
                        val track = findTrackPlan(segmentPlan, rawNode.id, TrackKind.Change, change.id, bIdx)
                            ?: break
                        val rawP = rawProgress(clock.t, track.startMs, track.durationMs)
                        if (rawP <= 0f) {
                            // Not started yet; stay at the state accumulated from completed earlier builds.
                            break
                        }
                        if (rawP >= 1f) {
                            // Completed; advance accumulated state and inspect any subsequent build on this beat.
                            lastCompletedBuildIdx = bIdx
                            currentState = targetAfterThis
                            if (rawNode.kind == NodeKind.Specimen && change.set.containsKey("specimen")) {
                                fromSpecParams = targetAfterThis.specimen?.params.orEmpty()
                                toSpecParams = targetAfterThis.specimen?.params
                                specimenParamProgress = 1f
                            }
                            continue
                        }
                        // Actively animating (0 < rawP < 1): interpolate strictly between currentState -> targetAfterThis.
                        val easedP = track.progressAt(clock.t)
                        val changePath = change.path
                        val hasMotionPath = !changePath.isNullOrBlank() && !reducedMotion
                        val interpolated = interpolateNodeProperties(
                            from = currentState,
                            to = targetAfterThis,
                            t = easedP,
                            colorResolver = colorResolver,
                            // When a motion path drives translation, hold pre-change (x, y) and apply
                            // sampleSvgPathOffset(path, easedP) once so the node does not move by 2Δ!
                            interpolateFramePosition = !hasMotionPath,
                        )
                        shapeRefSize = resolveChangeShapeReferenceSize(
                            rawNode = rawNode,
                            fromNode = currentState,
                            toNode = targetAfterThis,
                            fromUpToBeat = if (lastCompletedBuildIdx != null) activeBuildBeat else fromBeat,
                            fromUpToBuildIndex = lastCompletedBuildIdx,
                            toUpToBeat = activeBuildBeat,
                            toUpToBuildIndex = bIdx,
                            t = easedP,
                            resolveNodeAfterBuild = resolveNodeAfterBuild,
                        )
                        if (hasMotionPath) {
                            motionPathDelta = sampleSvgPathOffset(changePath!!, easedP)
                        }
                        if (rawNode.kind == NodeKind.Specimen && change.set.containsKey("specimen")) {
                            fromSpecParams = currentState.specimen?.params.orEmpty()
                            toSpecParams = targetAfterThis.specimen?.params
                            specimenParamProgress = easedP
                        }
                        textMorphState = buildChangeTextMorphState(rawNode, currentState.text, targetAfterThis.text, change, track, clock)
                        currentState = interpolated
                        break
                    }
                    resolvedNode = currentState
                }
            }
        }
        if (shapeRefSize == null && rawNode.kind == NodeKind.Shape) {
            val refBeat = when {
                isRestingOnSlide -> toBeat
                lastCompletedBuildIdx != null && activeBuildBeat != null -> activeBuildBeat
                else -> fromBeat
            }
            shapeRefSize = resolveAuthoredShapeReferenceSize(
                rawNode = rawNode,
                resolvedNode = resolvedNode,
                upToBeat = refBeat,
                resolveNodeAfterBuild = resolveNodeAfterBuild,
                upToBuildIndex = lastCompletedBuildIdx,
            )
        }

        // 3. Evaluate Enter / Exit / Emphasis builds active on this beat (or Beat-0 pre-entrance state).
        val nodeBoundsEstimate = estimateNodeBounds(resolvedNode, stageSize)
        val evalContext = EffectEvalContext(
            nodeBounds = nodeBoundsEstimate,
            stageSize = stageSize,
            density = densityValue,
            reducedMotion = reducedMotion,
        )

        if (isPreEntrance) {
            val beat0Enter = rawNode.builds.firstOrNull { it is Build.Enter && it.beat == 0 } as? Build.Enter
            if (beat0Enter != null) {
                nodeVisibleByTimeline = false
                baseLayer = combineLayerStates(baseLayer, LayerState.Hidden)
                val canDrawPath = resolvedNode.kind == NodeKind.Shape || resolvedNode.kind == NodeKind.Connector
                if (beat0Enter.effect == BuildEffects.LINE_DRAW && !reducedMotion && canDrawPath) {
                    pathTrim = BuildEffects.evaluatePath(
                        effectId = beat0Enter.effect,
                        kind = EffectBuildKind.Enter,
                        progress = 0f,
                        args = buildEffectArgs(beat0Enter.args, beat0Enter.direction, colorResolver),
                        curve = beat0Enter.curve?.toCurveV2(),
                    )
                }
            }
        } else if (!isRestingOnSlide && activeBuildBeat != null) {
            for ((bIdx, build) in activeBuildsWithIndex) {
                when (build) {
                    is Build.Enter -> {
                        val track = findTrackPlan(segmentPlan, rawNode.id, TrackKind.Enter, build.id, bIdx)
                        applyBuildEffect(
                            rawNode = rawNode,
                            resolvedNode = resolvedNode,
                            effectId = build.effect,
                            kind = EffectBuildKind.Enter,
                            by = build.by,
                            rawArgs = build.args,
                            channels = build.channels,
                            direction = build.direction,
                            curve = build.curve ?: track?.effectCurveOverride,
                            track = track,
                            clock = clock,
                            evalContext = evalContext,
                            colorResolver = colorResolver,
                            reducedMotion = reducedMotion,
                            onLayerState = { baseLayer = combineLayerStates(baseLayer, it) },
                            onPathTrim = { pathTrim = it },
                            onContentEffect = { contentEffect = it },
                            onTextBuild = { textBuildState = it },
                            onChildDelivery = { childId, override -> childDeliveries[childId] = override },
                        )
                    }
                    is Build.Exit -> {
                        val track = findTrackPlan(segmentPlan, rawNode.id, TrackKind.Exit, build.id, bIdx)
                        applyBuildEffect(
                            rawNode = rawNode,
                            resolvedNode = resolvedNode,
                            effectId = build.effect,
                            kind = EffectBuildKind.Exit,
                            by = build.by,
                            rawArgs = build.args,
                            channels = build.channels,
                            direction = build.direction,
                            curve = build.curve ?: track?.effectCurveOverride,
                            track = track,
                            clock = clock,
                            evalContext = evalContext,
                            colorResolver = colorResolver,
                            reducedMotion = reducedMotion,
                            onLayerState = { baseLayer = combineLayerStates(baseLayer, it) },
                            onPathTrim = { pathTrim = it },
                            onContentEffect = { contentEffect = it },
                            onTextBuild = { textBuildState = it },
                            onChildDelivery = { childId, override -> childDeliveries[childId] = override },
                        )
                    }
                    is Build.Emphasis -> {
                        val track = findTrackPlan(segmentPlan, rawNode.id, TrackKind.Emphasis, build.id, bIdx)
                        applyBuildEffect(
                            rawNode = rawNode,
                            resolvedNode = resolvedNode,
                            effectId = build.effect,
                            kind = EffectBuildKind.Emphasis,
                            by = build.by,
                            rawArgs = build.args,
                            channels = build.channels,
                            direction = build.direction,
                            curve = build.curve ?: track?.effectCurveOverride,
                            track = track,
                            clock = clock,
                            evalContext = evalContext,
                            colorResolver = colorResolver,
                            reducedMotion = reducedMotion,
                            onLayerState = { baseLayer = combineLayerStates(baseLayer, it) },
                            onPathTrim = { pathTrim = it },
                            onContentEffect = { contentEffect = it },
                            onTextBuild = { textBuildState = it },
                            onChildDelivery = { childId, override -> childDeliveries[childId] = override },
                        )
                    }
                    is Build.Change, is Build.Playback -> Unit
                }
            }
        }

        // If node was hidden before and has no active enter, force hidden.
        if (!nodeVisibleByTimeline && !hasActiveExit) {
            baseLayer = combineLayerStates(baseLayer, LayerState.Hidden)
        }

        // Apply unmatched Magic Move slide-transition alpha when applicable.
        if (magicMoveUnmatchedAlpha < 0.9999f) {
            baseLayer = baseLayer.copy(alpha = (baseLayer.alpha * magicMoveUnmatchedAlpha).coerceIn(0f, 1f))
        }

        // 4. Resolve text render state (Morph > piece-level Build > Rest).
        val resolvedTextPayload = resolvedNode.text
        val textState: DocumentTextRenderState? = if (resolvedTextPayload != null) {
            val charDelivery = isCharacterDeliveryNode(rawNode, slide.transition, incomingTransition)
            textMorphState ?: textBuildState ?: DocumentTextRenderState.Rest(
                payload = resolvedTextPayload,
                characterDeliveryNode = charDelivery,
            )
        } else {
            null
        }

        // 5. Specimen deterministic track progress evaluator.
        val specimenTrackProgress: (TrackHandle) -> Float = buildSpecimenTrackEvaluator(
            rawNode = rawNode,
            specimenDecl = specimenDecl,
            clock = clock,
            slide = slide,
            segmentPlan = segmentPlan,
            activeBuildBeat = activeBuildBeat,
            restingBeat = if (isRestingOnSlide) toBeat else null,
        )

        val effectivelyHidden = !nodeVisibleByTimeline ||
            baseLayer.alpha <= 0.0005f ||
            resolvedNode.opacity <= 0.0005

        return EvaluatedNodeFrame(
            resolvedNode = resolvedNode,
            layerState = baseLayer,
            pathTrim = pathTrim,
            contentEffect = contentEffect,
            textState = textState,
            childDeliveries = childDeliveries,
            motionPathDelta = motionPathDelta,
            isEffectivelyHidden = effectivelyHidden,
            specimenFromParams = fromSpecParams,
            specimenToParams = toSpecParams,
            specimenParamProgress = specimenParamProgress,
            specimenTrackProgress = specimenTrackProgress,
            shapeReferenceSize = shapeRefSize,
        )
    }

    /**
     * Evaluates a matched cross-slide `magicMove` pair for the root transition overlay.
     */
    fun evaluateTravellingNode(
        pair: MatchedPair,
        moveTrack: TrackPlan?,
        clock: DeckClock,
        transitionSpec: TransitionSpec,
        colorResolver: DocumentColorResolver,
        toSlideTransition: TransitionSpec? = null,
        fromSlideLastBeat: Int = 0,
        resolveFromNodeAfterBuild: (NodeSpec, Int) -> NodeSpec = JsonMergePatch::resolveNodeAfterBuild,
        resolveToNodeAfterBuild: (NodeSpec, Int) -> NodeSpec = JsonMergePatch::resolveNodeAfterBuild,
    ): EvaluatedTravellingNode {
        val moveCurve = moveTrack?.curve ?: transitionSpec.phases?.move?.curve ?: transitionSpec.curve ?: Curve.EaseInOut
        val moveP = moveTrack?.progressAt(clock.t) ?: 0f

        val defaultDurMs = (transitionSpec.durationMs ?: clock.effMs.toInt()).coerceAtLeast(1)
        val baseDelayMs = transitionSpec.delayMs ?: 0
        val defaultCurve = transitionSpec.curve ?: Curve.EaseInOut

        val exitPhase = transitionSpec.phases?.exit
        val exitAlpha = if (exitPhase != null) {
            val start = baseDelayMs + (exitPhase.delayMs ?: 0)
            val dur = exitPhase.durationMs ?: defaultDurMs
            val raw = rawProgress(clock.t, start, dur)
            val eased = CurveEvaluator.evaluateFraction(exitPhase.curve ?: defaultCurve, raw)
            (1f - eased).coerceIn(0f, 1f)
        } else {
            (1f - moveP).coerceIn(0f, 1f)
        }

        val enterPhase = transitionSpec.phases?.enter
        val enterAlpha = if (enterPhase != null) {
            val start = baseDelayMs + (enterPhase.delayMs ?: 0)
            val dur = enterPhase.durationMs ?: defaultDurMs
            val raw = rawProgress(clock.t, start, dur)
            CurveEvaluator.evaluateFraction(enterPhase.curve ?: defaultCurve, raw).coerceIn(0f, 1f)
        } else {
            moveP.coerceIn(0f, 1f)
        }

        val interpTransform = DecomposedTransform.lerp(pair.fromTransform, pair.toTransform, moveP.toDouble())
        val interpNode = interpolateNodeProperties(
            from = pair.fromNode,
            to = pair.toNode,
            t = moveP,
            colorResolver = colorResolver,
        )

        val textState: DocumentTextRenderState? = resolveTravellingTextRenderState(
            fromNode = pair.fromNode,
            toNode = pair.toNode,
            nodeId = pair.nodeId,
            fromEndpointMinWidthPx = null,
            toEndpointMinWidthPx = null,
            fromEndpointMaxWidthPx = null,
            toEndpointMaxWidthPx = null,
            fromEndpointSurfacePadPx = null,
            toEndpointSurfacePadPx = null,
            fallbackFromAvailableWidth = pair.fromAvailableWidth,
            fallbackToAvailableWidth = pair.toAvailableWidth,
            moveTrack = moveTrack,
            clock = clock,
            transitionSpec = transitionSpec,
            toSlideTransition = toSlideTransition,
            isDescendant = false,
        )

        val shapeRefSize = resolveTravellingShapeReferenceSize(
            rawFromNode = pair.rawFromNode,
            fromNode = pair.fromNode,
            rawToNode = pair.rawToNode,
            toNode = pair.toNode,
            fromSlideLastBeat = fromSlideLastBeat,
            moveProgress = moveP,
            resolveFromNodeAfterBuild = resolveFromNodeAfterBuild,
            resolveToNodeAfterBuild = resolveToNodeAfterBuild,
        )

        return EvaluatedTravellingNode(
            pair = pair,
            resolvedNode = interpNode,
            transform = interpTransform,
            opacity = interpNode.opacity.toFloat().coerceIn(0f, 1f),
            moveProgress = moveP,
            exitAlpha = exitAlpha,
            enterAlpha = enterAlpha,
            textState = textState,
            specimenFromParams = pair.fromNode.specimen?.params.orEmpty(),
            specimenToParams = pair.toNode.specimen?.params,
            specimenParamProgress = moveP,
            shapeReferenceSize = shapeRefSize,
        )
    }

    /**
     * Evaluates a matched descendant `(fromNode, toNode)` inside a matched cross-slide travelling
     * container over the parent's [moveProgress] (the outermost matched container owns the
     * move, and matched descendants interpolate smoothly without fading out and back in).
     */
    fun evaluateTravellingDescendant(
        fromNode: NodeSpec,
        toNode: NodeSpec,
        moveProgress: Float,
        moveTrack: TrackPlan?,
        clock: DeckClock,
        transitionSpec: TransitionSpec,
        colorResolver: DocumentColorResolver,
        toSlideTransition: TransitionSpec? = null,
        rawFromNode: NodeSpec = fromNode,
        rawToNode: NodeSpec = toNode,
        fromSlideLastBeat: Int = 0,
        resolveFromNodeAfterBuild: (NodeSpec, Int) -> NodeSpec = JsonMergePatch::resolveNodeAfterBuild,
        resolveToNodeAfterBuild: (NodeSpec, Int) -> NodeSpec = JsonMergePatch::resolveNodeAfterBuild,
    ): EvaluatedTravellingDescendant {
        val interpNode = interpolateNodeProperties(
            from = fromNode,
            to = toNode,
            t = moveProgress,
            colorResolver = colorResolver,
        )
        val shapeRefSize = resolveTravellingShapeReferenceSize(
            rawFromNode = rawFromNode,
            fromNode = fromNode,
            rawToNode = rawToNode,
            toNode = toNode,
            fromSlideLastBeat = fromSlideLastBeat,
            moveProgress = moveProgress,
            resolveFromNodeAfterBuild = resolveFromNodeAfterBuild,
            resolveToNodeAfterBuild = resolveToNodeAfterBuild,
        )
        val textState: DocumentTextRenderState? = resolveTravellingTextRenderState(
            fromNode = fromNode,
            toNode = toNode,
            nodeId = toNode.id,
            fromEndpointMinWidthPx = null,
            toEndpointMinWidthPx = null,
            fromEndpointMaxWidthPx = null,
            toEndpointMaxWidthPx = null,
            fromEndpointSurfacePadPx = null,
            toEndpointSurfacePadPx = null,
            fallbackFromAvailableWidth = null,
            fallbackToAvailableWidth = null,
            moveTrack = moveTrack,
            clock = clock,
            transitionSpec = transitionSpec,
            toSlideTransition = toSlideTransition,
            isDescendant = true,
        )
        return EvaluatedTravellingDescendant(
            fromNode = fromNode,
            toNode = toNode,
            resolvedNode = interpNode,
            moveProgress = moveProgress,
            textState = textState,
            specimenFromParams = fromNode.specimen?.params.orEmpty(),
            specimenToParams = toNode.specimen?.params,
            specimenParamProgress = moveProgress,
            rawFromNode = rawFromNode,
            rawToNode = rawToNode,
            shapeReferenceSize = shapeRefSize,
        )
    }

    /**
     * Resolves the [DocumentTextRenderState] for a travelling `Text` node (top-level or descendant),
     * honoring Lookahead-captured endpoint constraint widths when available so reflowing or aligned
     * paragraphs morph cleanly between exact Slide A and Slide B layouts.
     */
    fun resolveTravellingTextRenderState(
        fromNode: NodeSpec,
        toNode: NodeSpec,
        nodeId: String,
        fromEndpointMinWidthPx: Int?,
        toEndpointMinWidthPx: Int?,
        fromEndpointMaxWidthPx: Int?,
        toEndpointMaxWidthPx: Int?,
        fromEndpointSurfacePadPx: Int?,
        toEndpointSurfacePadPx: Int?,
        fallbackFromAvailableWidth: Double?,
        fallbackToAvailableWidth: Double?,
        moveTrack: TrackPlan?,
        clock: DeckClock,
        transitionSpec: TransitionSpec,
        toSlideTransition: TransitionSpec? = null,
        isDescendant: Boolean = false,
        fromEndpointLayoutWidthPx: Int? = null,
        toEndpointLayoutWidthPx: Int? = null,
    ): DocumentTextRenderState? {
        val fromTxt = fromNode.text ?: return null
        val toTxt = toNode.text ?: return null
        val fromMaxIsInner = fromEndpointMaxWidthPx != null
        val toMaxIsInner = toEndpointMaxWidthPx != null
        val fromSurfPad = fromEndpointSurfacePadPx ?: 0
        val toSurfPad = toEndpointSurfacePadPx ?: 0
        val fromMinW = fromEndpointMinWidthPx ?: (fromNode.frame?.w?.let { (it.roundToInt() - fromSurfPad).coerceAtLeast(0) } ?: 0)
        val toMinW = toEndpointMinWidthPx ?: (toNode.frame?.w?.let { (it.roundToInt() - toSurfPad).coerceAtLeast(0) } ?: 0)
        val fromMaxW = fromEndpointMaxWidthPx ?: fallbackFromAvailableWidth?.roundToInt()?.coerceAtLeast(1) ?: Int.MAX_VALUE
        val toMaxW = toEndpointMaxWidthPx ?: fallbackToAvailableWidth?.roundToInt()?.coerceAtLeast(1) ?: Int.MAX_VALUE

        val sameUnwrappedLayout = fromEndpointLayoutWidthPx != null &&
            toEndpointLayoutWidthPx != null &&
            fromEndpointLayoutWidthPx > 0 &&
            fromEndpointLayoutWidthPx == toEndpointLayoutWidthPx &&
            fromEndpointLayoutWidthPx < fromMaxW &&
            toEndpointLayoutWidthPx < toMaxW
        if (fromTxt == toTxt && fromMinW == toMinW && fromSurfPad == toSurfPad && (fromMaxW == toMaxW || sameUnwrappedLayout)) {
            val charDelivery = isCharacterDeliveryNode(fromNode, transitionSpec) ||
                isCharacterDeliveryNode(toNode, toSlideTransition, transitionSpec)
            return DocumentTextRenderState.Rest(
                payload = toTxt,
                characterDeliveryNode = charDelivery,
            )
        }

        val moveCurve = moveTrack?.curve ?: transitionSpec.phases?.move?.curve ?: transitionSpec.curve ?: Curve.EaseInOut
        val morphSpec = transitionSpec.text[nodeId] ?: toTxt.morph ?: fromTxt.morph ?: TextMorphSpec()
        val startMs = moveTrack?.startMs ?: 0
        val durMs = (moveTrack?.durationMs ?: clock.effMs.toInt()).coerceAtLeast(1)
        val elapsedMs = (clock.t - startMs.toFloat()).coerceIn(0f, durMs.toFloat())
        return DocumentTextRenderState.Morph(
            from = fromTxt,
            to = toTxt,
            by = morphSpec.by,
            pruneStopwords = morphSpec.pruneStopwords,
            schedule = if (isDescendant) null else moveTrack?.pieces,
            totalDurationMs = durMs,
            elapsedMs = elapsedMs,
            moveCurve = (morphSpec.phases?.move?.curve ?: moveCurve).toCurveV2(),
            // Text layout `@size` never interpolates during Cross(A, B);
            // uniform transform scaling is handled by the travelling node's graphicsLayer.
            interpolateLayoutSize = false,
            fromMinWidthPx = fromMinW,
            toMinWidthPx = toMinW,
            fromMaxWidthPx = fromMaxW,
            toMaxWidthPx = toMaxW,
            fromMaxWidthIsInner = fromMaxIsInner,
            toMaxWidthIsInner = toMaxIsInner,
            clockSegment = clock.segment.takeIf { clock.isActive },
            trackStartMs = startMs,
        )
    }

    private fun buildChangeTextMorphState(
        rawNode: NodeSpec,
        fromTxt: TextPayload?,
        toTxt: TextPayload?,
        change: Build.Change,
        track: TrackPlan,
        clock: DeckClock,
    ): DocumentTextRenderState.Morph? {
        if (rawNode.kind != NodeKind.Text || fromTxt == null || toTxt == null || fromTxt == toTxt) return null
        val morphSpec = change.morph ?: toTxt.morph ?: fromTxt.morph ?: TextMorphSpec()
        val durMs = track.durationMs.coerceAtLeast(1)
        val elapsedMs = (clock.t - track.startMs.toFloat()).coerceIn(0f, durMs.toFloat())
        return DocumentTextRenderState.Morph(
            from = fromTxt,
            to = toTxt,
            by = morphSpec.by,
            pruneStopwords = morphSpec.pruneStopwords,
            schedule = track.pieces,
            totalDurationMs = durMs,
            elapsedMs = elapsedMs,
            moveCurve = (morphSpec.phases?.move?.curve ?: track.curve).toCurveV2(),
            interpolateLayoutSize = true,
            clockSegment = clock.segment.takeIf { clock.isActive },
            trackStartMs = track.startMs,
        )
    }

    private data class SlideBeatContext(
        val fromBeat: Int,
        val toBeat: Int,
        val activeBuildBeat: Int?,
        val isRestingOnSlide: Boolean,
    )

    private fun resolveSlideBeatsForClock(
        slide: SlideSpec,
        clock: DeckClock,
        lastBeat: Int,
    ): SlideBeatContext = when (val seg = clock.segment) {
        is DocSegment.Rest -> {
            if (seg.slideId == slide.id) {
                val b = seg.beat.coerceIn(0, lastBeat)
                SlideBeatContext(b, b, activeBuildBeat = null, isRestingOnSlide = true)
            } else {
                SlideBeatContext(0, 0, activeBuildBeat = null, isRestingOnSlide = true)
            }
        }
        is DocSegment.AtStart -> {
            SlideBeatContext(0, 0, activeBuildBeat = null, isRestingOnSlide = true)
        }
        is DocSegment.Entrance -> {
            if (seg.slideId == slide.id) {
                SlideBeatContext(0, 0, activeBuildBeat = 0, isRestingOnSlide = false)
            } else {
                SlideBeatContext(0, 0, activeBuildBeat = null, isRestingOnSlide = true)
            }
        }
        is DocSegment.Beat -> {
            if (seg.slideId == slide.id) {
                val f = seg.fromBeat.coerceIn(0, lastBeat)
                val t = (f + 1).coerceIn(0, lastBeat)
                SlideBeatContext(f, t, activeBuildBeat = t, isRestingOnSlide = false)
            } else {
                SlideBeatContext(0, 0, activeBuildBeat = null, isRestingOnSlide = true)
            }
        }
        is DocSegment.Cross -> {
            when (slide.id) {
                seg.fromSlideId -> SlideBeatContext(lastBeat, lastBeat, activeBuildBeat = null, isRestingOnSlide = true)
                seg.toSlideId -> SlideBeatContext(0, 0, activeBuildBeat = null, isRestingOnSlide = true)
                else -> SlideBeatContext(0, 0, activeBuildBeat = null, isRestingOnSlide = true)
            }
        }
    }

    /**
     * Determines whether a node with [builds] is hidden when resting at [beat] (`beat == -1` means
     * `AtStart` before beat 0's entrance).
     */
    internal fun isHiddenAtBeat(builds: List<Build>, beat: Int): Boolean {
        val enterBeats = builds.filterIsInstance<Build.Enter>().map { it.beat }.sorted()
        val exitBeats = builds.filterIsInstance<Build.Exit>().map { it.beat }.sorted()
        if (enterBeats.isEmpty() && exitBeats.isEmpty()) return false

        val firstEnter = enterBeats.firstOrNull()
        val lastEnterAtOrBefore = enterBeats.lastOrNull { it <= beat }
        val lastExitAtOrBefore = exitBeats.lastOrNull { it <= beat }

        // If node has an Enter build, it starts hidden until its first Enter beat.
        if (firstEnter != null && beat < firstEnter) return true
        // If an Exit happened at or before `beat` and no later Enter re-entered it, it is hidden.
        if (lastExitAtOrBefore != null) {
            if (lastEnterAtOrBefore == null || lastExitAtOrBefore >= lastEnterAtOrBefore) {
                return true
            }
        }
        return false
    }

    private fun applyBuildEffect(
        rawNode: NodeSpec,
        resolvedNode: NodeSpec,
        effectId: String,
        kind: EffectBuildKind,
        by: DeliveryBy,
        rawArgs: Map<String, JsonElement>,
        channels: Map<String, MotionChannelSpec>,
        direction: String?,
        curve: Curve?,
        track: TrackPlan?,
        clock: DeckClock,
        evalContext: EffectEvalContext,
        colorResolver: DocumentColorResolver,
        reducedMotion: Boolean,
        onLayerState: (LayerState) -> Unit,
        onPathTrim: (PathTrimState) -> Unit,
        onContentEffect: (ContentEffectInvocation) -> Unit,
        onTextBuild: (DocumentTextRenderState.Build) -> Unit,
        onChildDelivery: (String, ChildDeliveryOverride) -> Unit,
    ) {
        if (track == null) return
        val args = buildEffectArgs(rawArgs, direction, colorResolver, channels)
        val curveV2 = curve?.toCurveV2()
        val startMs = track.startMs
        val rawDurMs = track.durationMs
        val rawP = rawProgress(clock.t, startMs, rawDurMs)
        val tier = BuildEffects.declarations[effectId]?.tier ?: EffectTier.Layer

        // Text-level delivery (word / character / paragraph or keyboard effect on text)
        val isTextPieceDelivery = resolvedNode.text != null &&
            (effectId == BuildEffects.KEYBOARD ||
                by == DeliveryBy.Word ||
                by == DeliveryBy.Character ||
                by == DeliveryBy.Paragraph)

        if (isTextPieceDelivery) {
            val durMs = rawDurMs.coerceAtLeast(1)
            val elapsedMs = (clock.t - startMs.toFloat()).coerceIn(0f, durMs.toFloat())
            val effectiveBy = if (effectId == BuildEffects.KEYBOARD && by == DeliveryBy.Whole) {
                DeliveryBy.Character
            } else {
                by
            }
            onTextBuild(
                DocumentTextRenderState.Build(
                    payload = resolvedNode.text!!,
                    by = effectiveBy,
                    effectId = effectId,
                    kind = kind,
                    args = args,
                    curve = curveV2,
                    schedule = track.pieces,
                    totalDurationMs = durMs,
                    elapsedMs = elapsedMs,
                    clockSegment = clock.segment.takeIf { clock.isActive },
                    trackStartMs = startMs,
                ),
            )
            return
        }

        // Container child-by-child staggered delivery (`by: child`)
        if (by == DeliveryBy.Child && rawNode.children.isNotEmpty()) {
            val schedule = track.pieces
            for (child in rawNode.children) {
                val slot = schedule?.slot(child.id)
                val childRawP = if (slot != null) {
                    rawProgress(clock.t, startMs + slot.startMs, slot.durationMs)
                } else {
                    rawP
                }
                val childCurve = if (track.useEffectDefaultCurve && curve == null) {
                    null
                } else {
                    slot?.curve?.toCurveV2() ?: curveV2
                }
                when (tier) {
                    EffectTier.Content -> {
                        val shapedP = childCurve?.sample(childRawP)?.coerceIn(0f, 1f) ?: childRawP
                        val content = ContentEffectInvocation(effectId, kind, shapedP, args)
                        onChildDelivery(child.id, ChildDeliveryOverride(LayerState.Identity, content))
                    }
                    else -> {
                        val ls = BuildEffects.evaluateLayer(
                            effectId = effectId,
                            kind = kind,
                            progress = childRawP,
                            context = evalContext,
                            args = args,
                            curve = childCurve,
                        )
                        onChildDelivery(child.id, ChildDeliveryOverride(ls))
                    }
                }
            }
            return
        }

        // Whole-node build dispatch by tier
        when (tier) {
            EffectTier.Path -> {
                val canDrawPath = resolvedNode.kind == NodeKind.Shape || resolvedNode.kind == NodeKind.Connector
                if (reducedMotion || !canDrawPath) {
                    onLayerState(
                        BuildEffects.evaluateLayer(
                            effectId = BuildEffects.DISSOLVE,
                            kind = kind,
                            progress = rawP,
                            context = evalContext,
                            args = EffectArgs.Empty,
                            curve = curveV2,
                        ),
                    )
                } else {
                    onPathTrim(
                        BuildEffects.evaluatePath(
                            effectId = effectId,
                            kind = kind,
                            progress = rawP,
                            args = args,
                            curve = curveV2,
                        ),
                    )
                }
            }
            EffectTier.Content -> {
                val shapedP = curveV2?.sample(rawP)?.coerceIn(0f, 1f) ?: rawP
                onContentEffect(
                    ContentEffectInvocation(
                        effectId = effectId,
                        kind = kind,
                        progress = shapedP,
                        args = args,
                    ),
                )
            }
            EffectTier.Layer, EffectTier.Text -> {
                val effectiveEffect = if (effectId == BuildEffects.KEYBOARD) BuildEffects.DISSOLVE else effectId
                onLayerState(
                    BuildEffects.evaluateLayer(
                        effectId = effectiveEffect,
                        kind = kind,
                        progress = rawP,
                        context = evalContext,
                        args = args,
                        curve = curveV2,
                    ),
                )
            }
        }
    }

    private fun buildSpecimenTrackEvaluator(
        rawNode: NodeSpec,
        specimenDecl: SpecimenDeclaration?,
        clock: DeckClock,
        slide: SlideSpec,
        segmentPlan: SegmentPlan,
        activeBuildBeat: Int?,
        restingBeat: Int?,
    ): (TrackHandle) -> Float {
        if (rawNode.kind != NodeKind.Specimen || specimenDecl == null) {
            return { 0f }
        }
        val overrides = rawNode.specimen?.tracks.orEmpty()
        val isPreEntrance = (clock.segment as? DocSegment.AtStart)?.slideId == slide.id ||
            (clock.segment as? DocSegment.Cross)?.toSlideId == slide.id
        return { handle ->
            val decl = specimenDecl.tracks.firstOrNull { it.name == handle.name }
            val override = overrides[handle.name]
            val targetBeat = override?.beat ?: decl?.beat ?: 0

            val planned = segmentPlan.tracks.firstOrNull {
                it.kind == TrackKind.SpecimenTrack &&
                    it.nodeId == rawNode.id &&
                    it.buildId == handle.name
            }

            when {
                planned != null && activeBuildBeat == targetBeat -> planned.progressAt(clock.t)
                restingBeat != null -> {
                    if (isPreEntrance && targetBeat == 0) 0f
                    else if (restingBeat >= targetBeat) 1f else 0f
                }
                activeBuildBeat != null -> {
                    when {
                        activeBuildBeat > targetBeat -> 1f
                        activeBuildBeat < targetBeat -> 0f
                        else -> 0f
                    }
                }
                else -> 0f
            }
        }
    }

    private fun findTrackPlan(
        plan: SegmentPlan,
        nodeId: String,
        kind: TrackKind,
        buildId: String?,
        buildIndex: Int,
    ): TrackPlan? {
        if (buildId != null) {
            plan.tracks.firstOrNull { it.nodeId == nodeId && it.kind == kind && it.buildId == buildId }
                ?.let { return it }
        }
        return plan.tracks.firstOrNull { it.nodeId == nodeId && it.kind == kind && it.buildIndex == buildIndex }
    }

    private fun rawProgress(tMs: Float, startMs: Int, durationMs: Int): Float {
        if (durationMs <= 0) {
            return if (startMs <= 0) {
                if (tMs > 0f) 1f else 0f
            } else {
                if (tMs >= startMs.toFloat()) 1f else 0f
            }
        }
        return ((tMs - startMs.toFloat()) / durationMs.toFloat()).coerceIn(0f, 1f)
    }

    private fun estimateNodeBounds(node: NodeSpec, stageSize: Size): Rect {
        val f = node.frame
        val x = (f?.x ?: 0.0).toFloat()
        val y = (f?.y ?: 0.0).toFloat()
        val w = (f?.w ?: stageSize.width.toDouble()).toFloat().coerceAtLeast(1f)
        val h = (f?.h ?: stageSize.height.toDouble()).toFloat().coerceAtLeast(1f)
        return Rect(x, y, x + w, y + h)
    }

    /** Multiplies/composes two [LayerState]s cleanly (used for parent `by: child` + node build). */
    fun combineLayerStates(a: LayerState, b: LayerState): LayerState {
        if (a.isIdentity) return b
        if (b.isIdentity) return a
        return LayerState(
            alpha = (a.alpha * b.alpha).coerceIn(0f, 1f),
            translationX = a.translationX + b.translationX,
            translationY = a.translationY + b.translationY,
            scaleX = a.scaleX * b.scaleX,
            scaleY = a.scaleY * b.scaleY,
            rotationZ = a.rotationZ + b.rotationZ,
            transformOrigin = if (b.scaleX != 1f || b.scaleY != 1f || b.rotationZ != 0f) {
                b.transformOrigin
            } else {
                a.transformOrigin
            },
            blurRadiusPx = a.blurRadiusPx + b.blurRadiusPx,
            colorMatrix = b.colorMatrix ?: a.colorMatrix,
            clip = if (b.clip != EffectClip.None) b.clip else a.clip,
        )
    }

    /** Interpolates all animatable properties between [from] and [to] at eased progress [t]. */
    fun interpolateNodeProperties(
        from: NodeSpec,
        to: NodeSpec,
        t: Float,
        colorResolver: DocumentColorResolver,
        interpolateFramePosition: Boolean = true,
    ): NodeSpec = interpolateDocumentNodeProperties(
        from = from,
        to = to,
        t = t,
        colorResolver = colorResolver,
        interpolateFramePosition = interpolateFramePosition,
    )

    internal fun interpolatePadding(a: PaddingSpec?, b: PaddingSpec?, t: Double): PaddingSpec =
        interpolateDocumentPadding(a, b, t)
}
