package dev.sebastiano.ciacola.document

import dev.sebastiano.ciacola.theme.REDUCED_MOTION_FADE_DURATION_MS
import kotlinx.serialization.json.JsonObject

/**
 * Pure, layout-free, composition-free motion planner.
 *
 * Single source of timing for Timeline bars, carriers, `after` resolution, and renderers.
 */
object SegmentPlanner {

    private const val DEFAULT_DURATION_MS: Int = 500

    /**
     * Plans a single beat segment on [slideId] (`beat = 0` is the slide's Entrance `Start -> Value(0)`;
     * `beat > 0` is `Value(beat - 1) -> Value(beat)`).
     */
    fun planSegment(
        document: DeckDocument,
        slideId: String,
        beat: Int,
        reducedMotion: Boolean = false,
        specimens: DeckSpecimenRegistry = DeckSpecimenRegistry.Empty,
        effects: BuildEffectRegistry = BuildEffectRegistry.Standard,
        themeName: String? = document.defaultTheme,
    ): SegmentPlan {
        val slide = document.slides.firstOrNull { it.id == slideId } ?: return SegmentPlan.Empty
        if (beat !in slide.beats.indices) return SegmentPlan.Empty

        val beatSpec = slide.beats[beat]
        val themeMotion = resolveThemeMotion(document, themeName)
        val flatNodes = SegmentStaggerOrdering.flattenSlideNodes(slide.nodes)
        val resolveCache = SegmentStaggerOrdering.BeatResolveCache()

        // Collect all build tasks participating on this beat.
        val tasks = mutableListOf<BuildTask>()
        val taskIdByNodeAndBuildIdx = HashMap<Pair<String, Int>, Int>()
        val lastTaskIdxByNode = HashMap<String, Int>()
        val taskIdxByNodeAndBuildId = HashMap<Pair<String, String>, Int>()

        for (entry in flatNodes) {
            val node = entry.node
            var prevTaskOnSameNode: Int? = null
            for ((bIdx, build) in node.builds.withIndex()) {
                if (build.beat != beat) continue
                // Beat 0 only runs Enter builds as the Entrance segment.
                if (beat == 0 && build !is Build.Enter) continue

                val taskIndex = tasks.size
                val phase = phaseOf(build)
                val geomKey = SegmentStaggerOrdering.computeDocumentSortKey(
                    entry = entry,
                    beat = if (phase == SlotPhase.Exit && beat > 0) beat - 1 else beat,
                    resolveCache = resolveCache,
                )
                tasks += BuildTask(
                    index = taskIndex,
                    nodeEntry = entry,
                    buildIndex = bIdx,
                    build = build,
                    phase = phase,
                    prevSameNodeTask = prevTaskOnSameNode,
                    sortKey = geomKey,
                )
                taskIdByNodeAndBuildIdx[node.id to bIdx] = taskIndex
                lastTaskIdxByNode[node.id] = taskIndex
                build.id?.let { taskIdxByNodeAndBuildId[node.id to it] = taskIndex }
                prevTaskOnSameNode = taskIndex
            }
        }

        // Also include declared specimen tracks active on this beat.
        val specimenTracks = collectSpecimenTracks(flatNodes, beat, specimens, reducedMotion, themeMotion)

        if (tasks.isEmpty() && specimenTracks.isEmpty()) {
            return SegmentPlan.Empty
        }

        // Compute rank per participating unsequenced node (exit uses before-beat geometry,
        // move/enter uses after-beat geometry; ties within 8 units share a rank).
        val unsequencedNodeRank = computeNodeRanks(
            tasks = tasks,
            stagger = beatSpec.stagger,
        )

        // Resolve `after` pointers to task indices, ignoring invalid/cross-beat/self/later references.
        val afterTargetByTask = IntArray(tasks.size) { -1 }
        for (task in tasks) {
            val afterRaw = task.build.after ?: continue
            val hashIdx = afterRaw.indexOf('#')
            val targetNodeId = if (hashIdx >= 0) afterRaw.substring(0, hashIdx) else afterRaw
            val targetBuildId = if (hashIdx >= 0) afterRaw.substring(hashIdx + 1) else null
            val targetTaskIdx = if (targetBuildId != null) {
                taskIdxByNodeAndBuildId[targetNodeId to targetBuildId]
            } else {
                lastTaskIdxByNode[targetNodeId]
            } ?: continue
            val targetTask = tasks[targetTaskIdx]
            if (targetTask.nodeEntry.node.id == task.nodeEntry.node.id && targetTask.buildIndex >= task.buildIndex) {
                continue // Cannot point at self or later build on same node.
            }
            afterTargetByTask[task.index] = targetTaskIdx
        }

        // Topological sort (Kahn's algorithm, ties broken by document order `task.index`).
        val orderedTaskIndices = stableTopologicalOrder(tasks, afterTargetByTask)

        val resolvedTracks = arrayOfNulls<TrackPlan>(tasks.size)
        for (tIdx in orderedTaskIndices) {
            val task = tasks[tIdx]
            val build = task.build
            val prevEnd = task.prevSameNodeTask?.let { resolvedTracks[it]?.endMs }
            val afterIdx = afterTargetByTask[tIdx]
            val afterEnd = if (afterIdx >= 0) resolvedTracks[afterIdx]?.endMs else null

            val isSequenced = prevEnd != null || afterEnd != null
            val buildDelay = build.delayMs ?: 0

            val startMs = if (reducedMotion) {
                0
            } else if (isSequenced) {
                val depEnd = maxOf(prevEnd ?: 0, afterEnd ?: 0)
                depEnd + buildDelay
            } else {
                val phaseDelay = phaseTiming(beatSpec.phases, task.phase)?.delayMs ?: 0
                val stagger = beatSpec.stagger
                val nodeId = task.nodeEntry.node.id
                val staggerDelay = when {
                    stagger == null -> 0
                    nodeId in stagger.manual -> stagger.manual.getValue(nodeId)
                    else -> (unsequencedNodeRank[nodeId] ?: 0) * stagger.ms
                }
                phaseDelay + staggerDelay + buildDelay
            }

            resolvedTracks[tIdx] = buildTrackPlan(
                task = task,
                startMs = startMs,
                beatPhases = beatSpec.phases,
                themeMotion = themeMotion,
                reducedMotion = reducedMotion,
                effects = effects,
            )
        }

        val allTracks = buildList(tasks.size + specimenTracks.size) {
            for (t in resolvedTracks) {
                if (t != null) add(t)
            }
            addAll(specimenTracks)
        }
        val segmentMs = allTracks.maxOfOrNull { it.endMs } ?: 0
        return SegmentPlan(tracks = allTracks, segmentMs = segmentMs)
    }

    /**
     * Plans a cross-slide transition `fromSlideId -> toSlideId`.
     */
    fun planTransition(
        document: DeckDocument,
        fromSlideId: String,
        toSlideId: String,
        reducedMotion: Boolean = false,
        specimens: DeckSpecimenRegistry = DeckSpecimenRegistry.Empty,
        effects: BuildEffectRegistry = BuildEffectRegistry.Standard,
        themeName: String? = document.defaultTheme,
    ): SegmentPlan {
        val fromSlide = document.slides.firstOrNull { it.id == fromSlideId } ?: return SegmentPlan.Empty
        val toSlide = document.slides.firstOrNull { it.id == toSlideId } ?: return SegmentPlan.Empty
        val themeMotion = resolveThemeMotion(document, themeName)
        val transition = fromSlide.transition ?: themeMotion?.transition ?: TransitionSpec(type = TransitionType.Dissolve)

        if (transition.type == TransitionType.None) {
            return SegmentPlan.Empty
        }

        if (reducedMotion) {
            val track = TrackPlan(
                nodeId = "@transition:$fromSlideId->$toSlideId",
                buildId = null,
                startMs = 0,
                endMs = REDUCED_MOTION_FADE_DURATION_MS,
                curve = Curve.EaseInOut,
                pieces = null,
                kind = TrackKind.TransitionSlide,
            )
            return SegmentPlan(tracks = listOf(track), segmentMs = REDUCED_MOTION_FADE_DURATION_MS)
        }

        if (transition.type != TransitionType.MagicMove) {
            val delay = transition.delayMs ?: 0
            val curve = transition.curve ?: themeMotion?.curve ?: defaultTransitionCurve(transition.type)
            val dur = resolveCurveDurationMs(
                curve = curve,
                explicitDurationMs = transition.durationMs,
                fallbackDurationMs = themeMotion?.durationMs ?: DEFAULT_DURATION_MS,
            )
            val track = TrackPlan(
                nodeId = "@transition:$fromSlideId->$toSlideId",
                buildId = null,
                startMs = delay,
                endMs = delay + dur,
                curve = curve,
                pieces = null,
                kind = TrackKind.TransitionSlide,
            )
            return SegmentPlan(tracks = listOf(track), segmentMs = track.endMs)
        }

        // Magic Move: plan matched travelling nodes (Move phase), unmatched A nodes (Exit), unmatched B nodes (Enter).
        val matchResult = CrossSlideGeometry.matchCrossSlidePairs(fromSlide, toSlide, document.stage)
        val matchedIds = matchResult.pairs.mapTo(hashSetOf()) { it.nodeId }
        val fromLastBeat = fromSlide.beats.lastIndex.coerceAtLeast(0)

        // The outermost matched node owns the move; descendants inside a matched travelling
        // container MUST NOT receive separate unmatched Exit / Enter tracks.
        val exitNodes = SegmentStaggerOrdering.flattenSlideNodes(fromSlide.nodes).filter { entry ->
            JsonMergePatch.isNodePresentAtBeat(entry.node, fromLastBeat) &&
                entry.node.id !in matchedIds &&
                entry.ancestors.none { it.id in matchedIds }
        }
        val enterNodes = SegmentStaggerOrdering.flattenSlideNodes(toSlide.nodes).filter { entry ->
            JsonMergePatch.isNodePresentAtStart(entry.node) &&
                entry.node.id !in matchedIds &&
                entry.ancestors.none { it.id in matchedIds }
        }

        val phases = transition.phases
        val stagger = transition.stagger
        val defaultDuration = transition.durationMs ?: themeMotion?.durationMs ?: DEFAULT_DURATION_MS
        val defaultCurve = transition.curve ?: themeMotion?.curve ?: Curve.EaseInOut

        // Compute ranks: Exit by A's geometry, Move & Enter by B's geometry.
        val resolveCacheA = SegmentStaggerOrdering.BeatResolveCache()
        val resolveCacheB = SegmentStaggerOrdering.BeatResolveCache()
        val exitRanks = SegmentStaggerOrdering.computeGeometryRanks(
            items = exitNodes.map { it.node.id to SegmentStaggerOrdering.computeDocumentSortKey(it, fromLastBeat, resolveCacheA) },
            stagger = stagger,
        )
        val toFlatNodes = SegmentStaggerOrdering.flattenSlideNodes(toSlide.nodes)
        val toFlatById = toFlatNodes.associateBy { it.node.id }
        val moveRanks = SegmentStaggerOrdering.computeGeometryRanks(
            items = matchResult.pairs.map { p ->
                val toEntry = toFlatById.getValue(p.nodeId)
                p.nodeId to SegmentStaggerOrdering.computeDocumentSortKey(toEntry, 0, resolveCacheB)
            },
            stagger = stagger,
        )
        val enterRanks = SegmentStaggerOrdering.computeGeometryRanks(
            items = enterNodes.map { it.node.id to SegmentStaggerOrdering.computeDocumentSortKey(it, 0, resolveCacheB) },
            stagger = stagger,
        )

        val tracks = mutableListOf<TrackPlan>()

        // Exit tracks
        val exitPhaseTiming = phases?.exit
        val exitBaseDelay = (transition.delayMs ?: 0) + (exitPhaseTiming?.delayMs ?: 0)
        val exitCurve = exitPhaseTiming?.curve ?: defaultCurve
        val exitDur = resolveCurveDurationMs(exitCurve, exitPhaseTiming?.durationMs, defaultDuration)
        for (entry in exitNodes) {
            val id = entry.node.id
            val st = staggerDelayFor(id, exitRanks, stagger)
            tracks += TrackPlan(
                nodeId = id,
                buildId = null,
                startMs = exitBaseDelay + st,
                endMs = exitBaseDelay + st + exitDur,
                curve = exitCurve,
                pieces = null,
                kind = TrackKind.TransitionExit,
            )
        }

        // Travelling (Move) tracks
        val movePhaseTiming = phases?.move
        val moveBaseDelay = (transition.delayMs ?: 0) + (movePhaseTiming?.delayMs ?: 0)
        val moveCurve = movePhaseTiming?.curve ?: defaultCurve
        val moveDur = resolveCurveDurationMs(moveCurve, movePhaseTiming?.durationMs, defaultDuration)
        // `trackStart` already includes `transition.delayMs + phases.move.delayMs`, so slot
        // schedules evaluated relative to `trackStart` must not add `phases.move.delayMs` twice.
        val relativeParentPhases = phases?.let { p ->
            val mDelay = p.move?.delayMs ?: 0
            if (mDelay == 0) {
                p
            } else {
                p.copy(
                    exit = p.exit?.let { it.copy(delayMs = ((it.delayMs ?: 0) - mDelay).coerceAtLeast(0)) },
                    move = p.move?.copy(delayMs = 0),
                    enter = p.enter?.let { it.copy(delayMs = ((it.delayMs ?: 0) - mDelay).coerceAtLeast(0)) },
                )
            }
        }
        for (pair in matchResult.pairs) {
            val st = staggerDelayFor(pair.nodeId, moveRanks, stagger)
            val trackStart = moveBaseDelay + st
            if (pair.kind == NodeKind.Text) {
                val fromText = pair.fromNode.text?.value?.plainText.orEmpty()
                val toText = pair.toNode.text?.value?.plainText.orEmpty()
                val morphSpec = transition.text[pair.nodeId] ?: pair.toNode.text?.morph ?: pair.fromNode.text?.morph
                if (pair.fromNode.text != pair.toNode.text || morphSpec != null) {
                    val effectiveMorph = morphSpec ?: TextMorphSpec()
                    val schedule = buildTextMorphPieceSchedule(
                        fromText = fromText,
                        toText = toText,
                        morph = effectiveMorph,
                        parentPhases = relativeParentPhases,
                        fallbackCurve = moveCurve,
                        fallbackDurationMs = moveDur,
                    )
                    tracks += TrackPlan(
                        nodeId = pair.nodeId,
                        buildId = null,
                        startMs = trackStart,
                        endMs = trackStart + schedule.spanMs,
                        curve = moveCurve,
                        pieces = schedule,
                        kind = TrackKind.TransitionMove,
                    )
                    continue
                }
            }
            tracks += TrackPlan(
                nodeId = pair.nodeId,
                buildId = null,
                startMs = trackStart,
                endMs = trackStart + moveDur,
                curve = moveCurve,
                pieces = null,
                kind = TrackKind.TransitionMove,
            )
        }

        // Enter tracks
        val enterPhaseTiming = phases?.enter
        val enterBaseDelay = (transition.delayMs ?: 0) + (enterPhaseTiming?.delayMs ?: 0)
        val enterCurve = enterPhaseTiming?.curve ?: defaultCurve
        val enterDur = resolveCurveDurationMs(enterCurve, enterPhaseTiming?.durationMs, defaultDuration)
        for (entry in enterNodes) {
            val id = entry.node.id
            val st = staggerDelayFor(id, enterRanks, stagger)
            tracks += TrackPlan(
                nodeId = id,
                buildId = null,
                startMs = enterBaseDelay + st,
                endMs = enterBaseDelay + st + enterDur,
                curve = enterCurve,
                pieces = null,
                kind = TrackKind.TransitionEnter,
            )
        }

        // If neither slide has nodes (background-only Magic Move), still produce a background dissolve track.
        if (tracks.isEmpty()) {
            val delay = transition.delayMs ?: 0
            val dur = resolveCurveDurationMs(defaultCurve, transition.durationMs, defaultDuration)
            tracks += TrackPlan(
                nodeId = "@transition:$fromSlideId->$toSlideId",
                buildId = null,
                startMs = delay,
                endMs = delay + dur,
                curve = defaultCurve,
                pieces = null,
                kind = TrackKind.TransitionSlide,
            )
        }

        return SegmentPlan(
            tracks = tracks,
            segmentMs = tracks.maxOfOrNull { it.endMs } ?: 0,
        )
    }

    /**
     * Computes the per-slide plan invalidation cache key:
     * `hash(slide N, resolved active theme, palettes referenced, fonts, specimen declarations
     * referenced, loopPeriodsMs, stage size, last-beat nodes of slide N-1, beat-0 nodes of slide N+1)`.
     */
    fun slidePlanCacheKey(
        document: DeckDocument,
        slideIndex: Int,
        themeName: String? = document.defaultTheme,
        specimens: DeckSpecimenRegistry = DeckSpecimenRegistry.Empty,
    ): String = SlidePlanCacheKeys.slidePlanCacheKey(document, slideIndex, themeName, specimens)

    fun slideLintCacheKey(
        document: DeckDocument,
        slideIndex: Int,
        themeName: String? = document.defaultTheme,
        specimens: DeckSpecimenRegistry = DeckSpecimenRegistry.Empty,
        assetFingerprints: Map<String, String> = emptyMap(),
        fontResolverTag: String = "default",
    ): String = SlidePlanCacheKeys.slideLintCacheKey(
        document = document,
        slideIndex = slideIndex,
        themeName = themeName,
        specimens = specimens,
        assetFingerprints = assetFingerprints,
        fontResolverTag = fontResolverTag,
    )

    // ── Internal planning helpers ───────────────────────────────────────────────────────────────

    private data class BuildTask(
        val index: Int,
        val nodeEntry: SegmentStaggerOrdering.FlatNodeEntry,
        val buildIndex: Int,
        val build: Build,
        val phase: SlotPhase,
        val prevSameNodeTask: Int?,
        val sortKey: DocumentSortKey,
    )

    private fun buildTrackPlan(
        task: BuildTask,
        startMs: Int,
        beatPhases: Phases?,
        themeMotion: ThemeMotionDefaults?,
        reducedMotion: Boolean,
        effects: BuildEffectRegistry,
    ): TrackPlan {
        val node = task.nodeEntry.node
        val build = task.build
        val phaseTiming = phaseTiming(beatPhases, task.phase)

        if (reducedMotion) {
            return buildReducedMotionTrack(task, startMs, node, build)
        }

        return when (build) {
            is Build.Enter, is Build.Exit, is Build.Emphasis -> {
                val effectId = when (build) {
                    is Build.Enter -> build.effect
                    is Build.Exit -> build.effect
                    is Build.Emphasis -> build.effect
                }
                val by = when (build) {
                    is Build.Enter -> build.by
                    is Build.Exit -> build.by
                    is Build.Emphasis -> build.by
                }
                val stagger = when (build) {
                    is Build.Enter -> build.stagger
                    is Build.Exit -> build.stagger
                    is Build.Emphasis -> build.stagger
                }
                val args = when (build) {
                    is Build.Enter -> build.args
                    is Build.Exit -> build.args
                    is Build.Emphasis -> build.args
                }
                val effectDecl = effects.find(effectId)
                val explicitCurve = build.curve
                    ?: phaseTiming?.curve
                    ?: effectDecl?.defaultCurve
                val useEffectDefaultCurve = explicitCurve == null
                val curve = explicitCurve
                    ?: defaultBuildEffectDisplayCurve(effectId, build)
                val durationMs = resolveCurveDurationMs(
                    curve = curve,
                    explicitDurationMs = build.durationMs ?: phaseTiming?.durationMs,
                    fallbackDurationMs = effectDecl?.defaultDurationMs ?: themeMotion?.durationMs ?: DEFAULT_DURATION_MS,
                )
                val kind = when (build) {
                    is Build.Enter -> TrackKind.Enter
                    is Build.Exit -> TrackKind.Exit
                    is Build.Emphasis -> TrackKind.Emphasis
                }

                if (by == DeliveryBy.Whole) {
                    TrackPlan(
                        nodeId = node.id,
                        buildId = build.id,
                        startMs = startMs,
                        endMs = startMs + durationMs,
                        curve = curve,
                        pieces = null,
                        kind = kind,
                        buildIndex = task.buildIndex,
                        useEffectDefaultCurve = useEffectDefaultCurve,
                    )
                } else {
                    val resolvedNode = JsonMergePatch.resolveNodeAtBeat(
                        node,
                        if (build is Build.Exit && build.beat > 0) build.beat - 1 else build.beat,
                    )
                    val schedule = buildDeliveryPieceSchedule(
                        node = resolvedNode,
                        by = by,
                        stagger = stagger,
                        durationMs = durationMs,
                        curve = curve,
                        phase = task.phase,
                        effectDecl = effectDecl,
                        args = args,
                        isExit = build is Build.Exit,
                    )
                    TrackPlan(
                        nodeId = node.id,
                        buildId = build.id,
                        startMs = startMs,
                        endMs = startMs + schedule.spanMs,
                        curve = curve,
                        pieces = schedule,
                        kind = kind,
                        buildIndex = task.buildIndex,
                        useEffectDefaultCurve = useEffectDefaultCurve,
                    )
                }
            }

            is Build.Change -> {
                val curve = build.curve
                    ?: phaseTiming?.curve
                    ?: themeMotion?.curve
                    ?: Curve.EaseInOut
                val requestedDurationMs = resolveCurveDurationMs(
                    curve = curve,
                    explicitDurationMs = build.durationMs ?: phaseTiming?.durationMs,
                    fallbackDurationMs = themeMotion?.durationMs ?: DEFAULT_DURATION_MS,
                )

                // Code value or highlightLines change uses warpSchedule(tokenDurationMs, reducedMotion = false).
                if (node.kind == NodeKind.Code && isCodeWarpChange(build.set)) {
                    val ws = warpSchedule(requestedDurationMs, reducedMotion = false)
                    return TrackPlan(
                        nodeId = node.id,
                        buildId = build.id,
                        startMs = startMs,
                        endMs = startMs + ws.totalSpanMs,
                        curve = curve,
                        pieces = null,
                        kind = TrackKind.Change,
                        buildIndex = task.buildIndex,
                        warpSchedule = ws,
                    )
                }

                // Text change produces a PieceSchedule including mandatory `@size` slot.
                if (node.kind == NodeKind.Text && isTextValueOrStyleChange(build.set)) {
                    val beforeNode = JsonMergePatch.resolveNodeBeforeBuild(node, task.buildIndex)
                    val afterNode = JsonMergePatch.resolveNodeAfterBuild(node, task.buildIndex)
                    val fromText = beforeNode.text?.value?.plainText.orEmpty()
                    val toText = afterNode.text?.value?.plainText.orEmpty()
                    val morphSpec = build.morph ?: afterNode.text?.morph ?: beforeNode.text?.morph ?: TextMorphSpec()
                    val schedule = buildTextMorphPieceSchedule(
                        fromText = fromText,
                        toText = toText,
                        morph = morphSpec,
                        parentPhases = beatPhases,
                        fallbackCurve = curve,
                        fallbackDurationMs = requestedDurationMs,
                    )
                    return TrackPlan(
                        nodeId = node.id,
                        buildId = build.id,
                        startMs = startMs,
                        endMs = startMs + schedule.spanMs,
                        curve = curve,
                        pieces = schedule,
                        kind = TrackKind.Change,
                        buildIndex = task.buildIndex,
                    )
                }

                TrackPlan(
                    nodeId = node.id,
                    buildId = build.id,
                    startMs = startMs,
                    endMs = startMs + requestedDurationMs,
                    curve = curve,
                    pieces = null,
                    kind = TrackKind.Change,
                    buildIndex = task.buildIndex,
                )
            }
        }
    }

    private fun buildReducedMotionTrack(
        task: BuildTask,
        startMs: Int,
        node: NodeSpec,
        build: Build,
    ): TrackPlan {
        val kind = when (build) {
            is Build.Enter -> TrackKind.Enter
            is Build.Exit -> TrackKind.Exit
            is Build.Emphasis -> TrackKind.Emphasis
            is Build.Change -> TrackKind.Change
        }
        val isZeroAppear = (build is Build.Enter && build.effect == "appear") ||
            (build is Build.Exit && build.effect == "appear") ||
            (build.durationMs == 0)
        if (node.kind == NodeKind.Code && build is Build.Change && isCodeWarpChange(build.set)) {
            val ws = warpSchedule(build.durationMs ?: DEFAULT_DURATION_MS, reducedMotion = true)
            return TrackPlan(
                nodeId = node.id,
                buildId = build.id,
                startMs = startMs,
                endMs = startMs + ws.totalSpanMs,
                curve = Curve.EaseInOut,
                pieces = null,
                kind = kind,
                buildIndex = task.buildIndex,
                warpSchedule = ws,
            )
        }
        val dur = if (isZeroAppear) 0 else REDUCED_MOTION_FADE_DURATION_MS
        return TrackPlan(
            nodeId = node.id,
            buildId = build.id,
            startMs = startMs,
            endMs = startMs + dur,
            curve = Curve.EaseInOut,
            pieces = null,
            kind = kind,
            buildIndex = task.buildIndex,
        )
    }

    private fun buildDeliveryPieceSchedule(
        node: NodeSpec,
        by: DeliveryBy,
        stagger: Stagger?,
        durationMs: Int,
        curve: Curve,
        phase: SlotPhase,
        effectDecl: BuildEffectDeclaration?,
        args: Map<String, kotlinx.serialization.json.JsonElement>,
        isExit: Boolean,
    ): PieceSchedule {
        val items: List<Pair<String, Int>> = when (by) {
            DeliveryBy.Child -> node.children.mapIndexed { idx, child -> child.id to idx }
            DeliveryBy.Paragraph, DeliveryBy.Word, DeliveryBy.Character -> {
                val plain = node.text?.value?.plainText.orEmpty()
                val tokens = TextTokenizer.tokenizeForDelivery(plain, by)
                tokens.map { tok ->
                    val rankGroup = if (stagger?.order == StaggerOrder.TopToBottom) tok.lineIndex else -1
                    tok.address to rankGroup
                }
            }
            DeliveryBy.Whole -> emptyList()
        }

        if (items.isEmpty()) {
            return PieceSchedule(emptyList())
        }

        val count = items.size
        val reverse = (stagger?.reverse == true) xor (isExit && effectDecl?.id == "keyboard")
        val orderedIndices = if (reverse) (count - 1 downTo 0).toList() else (0 until count).toList()

        // Compute rank per item (for TopToBottom inside text, items on the same logical line share a rank).
        val rankByOrigIndex = IntArray(count)
        if (stagger?.order == StaggerOrder.TopToBottom && by != DeliveryBy.Child) {
            val distinctLines = items.map { it.second }.distinct().let { if (reverse) it.reversed() else it }
            val lineRank = distinctLines.withIndex().associate { (r, line) -> line to r }
            for (i in items.indices) {
                rankByOrigIndex[i] = lineRank[items[i].second] ?: 0
            }
        } else {
            for ((rank, origIdx) in orderedIndices.withIndex()) {
                rankByOrigIndex[origIdx] = rank
            }
        }

        val slots = ArrayList<Slot>(count)
        for ((origIdx, item) in items.withIndex()) {
            val addr = item.first
            val rank = rankByOrigIndex[origIdx]
            val slotStart = when {
                stagger != null && addr in stagger.manual -> stagger.manual.getValue(addr)
                effectDecl?.pieceStartMs != null -> effectDecl.pieceStartMs.invoke(rank, count, stagger, args)
                stagger != null -> rank * stagger.ms
                else -> 0
            }
            slots += Slot(
                address = addr,
                startMs = slotStart,
                durationMs = durationMs,
                curve = curve,
                phase = phase,
            )
        }
        return PieceSchedule(slots)
    }

    /**
     * Builds the [PieceSchedule] for a text morph, including one slot per [PureTextDiff] fragment
     * AND the mandatory `@size` slot carrying the Move phase's delay, duration, and curve.
     */
    internal fun buildTextMorphPieceSchedule(
        fromText: String,
        toText: String,
        morph: TextMorphSpec,
        parentPhases: Phases?,
        fallbackCurve: Curve,
        fallbackDurationMs: Int,
    ): PieceSchedule {
        val diff = TextTokenizer.diff(fromText, toText, morph.by, morph.pruneStopwords)
        val effectivePhases = morph.phases ?: parentPhases
        val stagger = morph.stagger

        fun phaseSpec(phase: SlotPhase): Triple<Int, Int, Curve> {
            val pt = phaseTiming(effectivePhases, phase)
            val delay = pt?.delayMs ?: 0
            val curve = pt?.curve ?: fallbackCurve
            val dur = resolveCurveDurationMs(curve, pt?.durationMs, fallbackDurationMs)
            return Triple(delay, dur, curve)
        }

        val (exitDelay, exitDur, exitCurve) = phaseSpec(SlotPhase.Exit)
        val (moveDelay, moveDur, moveCurve) = phaseSpec(SlotPhase.Move)
        val (enterDelay, enterDur, enterCurve) = phaseSpec(SlotPhase.Enter)

        val slots = ArrayList<Slot>(diff.fragments.size + 1)

        fun appendPhaseFragments(
            frags: List<TextDiffFragment>,
            phase: SlotPhase,
            phaseDelay: Int,
            phaseDur: Int,
            phaseCurve: Curve,
        ) {
            if (frags.isEmpty()) return
            val reverse = stagger?.reverse == true
            val ordered = if (reverse) frags.reversed() else frags
            val rankByAddr = HashMap<String, Int>(frags.size)
            if (stagger?.order == StaggerOrder.TopToBottom) {
                val distinctLines = ordered.map { it.lineIndex }.distinct()
                val lineRank = distinctLines.withIndex().associate { (r, ln) -> ln to r }
                for (f in frags) {
                    rankByAddr[f.address] = lineRank[f.lineIndex] ?: 0
                }
            } else {
                for ((rank, f) in ordered.withIndex()) {
                    rankByAddr[f.address] = rank
                }
            }

            for (frag in frags) {
                val rank = rankByAddr[frag.address] ?: 0
                val start = if (stagger != null && frag.address in stagger.manual) {
                    stagger.manual.getValue(frag.address)
                } else {
                    phaseDelay + rank * (stagger?.ms ?: 0)
                }
                slots += Slot(
                    address = frag.address,
                    startMs = start,
                    durationMs = phaseDur,
                    curve = phaseCurve,
                    phase = phase,
                )
            }
        }

        appendPhaseFragments(diff.exiting, SlotPhase.Exit, exitDelay, exitDur, exitCurve)
        appendPhaseFragments(diff.kept, SlotPhase.Move, moveDelay, moveDur, moveCurve)
        appendPhaseFragments(diff.entering, SlotPhase.Enter, enterDelay, enterDur, enterCurve)

        // Mandatory `@size` slot on EVERY text morph (even with 0 kept fragments or empty endpoints).
        slots += Slot(
            address = Slot.SIZE_ADDRESS,
            startMs = moveDelay,
            durationMs = moveDur,
            curve = moveCurve,
            phase = SlotPhase.Move,
        )

        return PieceSchedule(slots)
    }

    private fun collectSpecimenTracks(
        flatNodes: List<SegmentStaggerOrdering.FlatNodeEntry>,
        beat: Int,
        specimens: DeckSpecimenRegistry,
        reducedMotion: Boolean,
        themeMotion: ThemeMotionDefaults?,
    ): List<TrackPlan> {
        val out = mutableListOf<TrackPlan>()
        for (entry in flatNodes) {
            val sp = entry.node.specimen ?: continue
            val decl = specimens.find(sp.ref) ?: continue
            for (trackDecl in decl.tracks) {
                val override = sp.tracks[trackDecl.name]
                val effectiveBeat = override?.beat ?: trackDecl.beat
                if (effectiveBeat != beat) continue
                val startMs = if (reducedMotion) 0 else (override?.delayMs ?: trackDecl.delayMs)
                val curve = override?.curve ?: trackDecl.curve
                val dur = if (reducedMotion) {
                    if ((override?.durationMs ?: trackDecl.durationMs) == 0) 0 else REDUCED_MOTION_FADE_DURATION_MS
                } else {
                    resolveCurveDurationMs(
                        curve = curve,
                        explicitDurationMs = override?.durationMs ?: trackDecl.durationMs,
                        fallbackDurationMs = themeMotion?.durationMs ?: DEFAULT_DURATION_MS,
                    )
                }
                out += TrackPlan(
                    nodeId = entry.node.id,
                    buildId = trackDecl.name,
                    startMs = startMs,
                    endMs = startMs + dur,
                    curve = if (reducedMotion) Curve.EaseInOut else curve,
                    pieces = null,
                    kind = TrackKind.SpecimenTrack,
                )
            }
        }
        return out
    }

    private fun computeNodeRanks(
        tasks: List<BuildTask>,
        stagger: Stagger?,
    ): Map<String, Int> {
        // First unsequenced task per node supplies that node's sort key.
        val items = LinkedHashMap<String, DocumentSortKey>()
        for (task in tasks) {
            if (task.prevSameNodeTask == null && task.build.after == null) {
                items.putIfAbsent(task.nodeEntry.node.id, task.sortKey)
            }
        }
        return SegmentStaggerOrdering.computeGeometryRanks(items.entries.map { it.key to it.value }, stagger)
    }

    private fun stableTopologicalOrder(
        tasks: List<BuildTask>,
        afterTargetByTask: IntArray,
    ): List<Int> {
        val n = tasks.size
        val inDegree = IntArray(n)
        val dependents = Array(n) { mutableListOf<Int>() }

        for (task in tasks) {
            val deps = linkedSetOf<Int>()
            task.prevSameNodeTask?.let { deps += it }
            val afterIdx = afterTargetByTask[task.index]
            if (afterIdx >= 0) deps += afterIdx
            inDegree[task.index] = deps.size
            for (d in deps) {
                dependents[d] += task.index
            }
        }

        val ready = sortedSetOf<Int>()
        for (i in 0 until n) {
            if (inDegree[i] == 0) ready += i
        }

        val result = ArrayList<Int>(n)
        val emitted = BooleanArray(n)
        while (ready.isNotEmpty()) {
            val next = ready.first()
            ready.remove(next)
            result += next
            emitted[next] = true
            for (dep in dependents[next]) {
                inDegree[dep]--
                if (inDegree[dep] == 0) {
                    ready += dep
                }
            }
        }

        // Cycle fallback: emit remaining nodes in document order (treating cyclic `after` as absent).
        if (result.size < n) {
            for (i in 0 until n) {
                if (!emitted[i]) {
                    afterTargetByTask[i] = -1
                    result += i
                }
            }
        }
        return result
    }

    private fun phaseOf(build: Build): SlotPhase = when (build) {
        is Build.Exit -> SlotPhase.Exit
        is Build.Change, is Build.Emphasis -> SlotPhase.Move
        is Build.Enter -> SlotPhase.Enter
    }

    private fun phaseTiming(phases: Phases?, phase: SlotPhase): Timing? = when (phase) {
        SlotPhase.Exit -> phases?.exit
        SlotPhase.Move -> phases?.move
        SlotPhase.Enter -> phases?.enter
    }

    private fun resolveCurveDurationMs(
        curve: Curve,
        explicitDurationMs: Int?,
        fallbackDurationMs: Int,
    ): Int = when (curve) {
        is Curve.Spring -> CurveEvaluator.springSettleDurationMs(curve.damping, curve.stiffness)
        else -> (explicitDurationMs ?: fallbackDurationMs).coerceAtLeast(0)
    }

    private fun resolveThemeMotion(document: DeckDocument, themeName: String?): ThemeMotionDefaults? =
        (themeName?.let { document.themes[it] } ?: document.themes.values.firstOrNull())?.motion

    private fun staggerDelayFor(nodeId: String, ranks: Map<String, Int>, stagger: Stagger?): Int = when {
        stagger == null -> 0
        nodeId in stagger.manual -> stagger.manual.getValue(nodeId)
        else -> (ranks[nodeId] ?: 0) * stagger.ms
    }

    private fun isCodeWarpChange(setObj: JsonObject): Boolean {
        val codeObj = setObj["code"] as? JsonObject ?: return false
        return "value" in codeObj || "highlightLines" in codeObj
    }

    private fun defaultTransitionCurve(type: TransitionType): Curve = when (type) {
        TransitionType.Push -> Curve.Preset("keynote.push")
        TransitionType.Slide -> Curve.Preset("keynote.slide")
        TransitionType.Reveal -> Curve.Preset("keynote.reveal")
        else -> Curve.EaseInOut
    }

    private fun defaultBuildEffectDisplayCurve(effectId: String, build: Build): Curve = when (effectId) {
        "appear" -> Curve.Linear
        "fadeMove" -> if (build is Build.Exit) Curve.Preset("keynote.fadeMoveExit") else Curve.Preset("keynote.fadeMovePos")
        "moveIn" -> if (build is Build.Exit) Curve.Preset("keynote.moveOut") else Curve.Preset("keynote.moveIn")
        "zoom" -> if (build is Build.Exit) Curve.EaseIn else Curve.EaseOut
        "blur" -> Curve.EaseOut
        "pop" -> Curve.Preset("keynote.pop")
        else -> Curve.EaseInOut
    }

    private fun isTextValueOrStyleChange(setObj: JsonObject): Boolean {
        val textObj = setObj["text"] as? JsonObject ?: return false
        return "value" in textObj || "size" in textObj || "color" in textObj || "weight" in textObj
    }
}
