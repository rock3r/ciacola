package dev.sebastiano.ciacola.inspector.element

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import dev.sebastiano.ciacola.document.Build
import dev.sebastiano.ciacola.document.DeckDocument
import dev.sebastiano.ciacola.document.FrameSpec
import dev.sebastiano.ciacola.document.JsonMergePatch
import dev.sebastiano.ciacola.document.NodeSpec
import dev.sebastiano.ciacola.document.ParamSpec
import dev.sebastiano.ciacola.document.SlideSpec
import dev.sebastiano.ciacola.inspector.document.DocumentThemeResolver
import dev.sebastiano.ciacola.inspector.document.snapHalfUnit
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put

/**
 * Multi-node move, nudge, align and distribute operations for [DocumentElementInspectorController].
 *
 * Every operation writes ONE document edit (one undo step) through
 * [DocumentElementInspectorController.applyPatchesToNodes], so beat 0 edits the base frame and
 * beat > 0 merges into that beat's `Build.Change` patch.
 */
internal object DocumentElementArrangeOps {

    private const val DEFAULT_W = 240.0
    private const val DEFAULT_H = 64.0

    /** One "did it move" threshold (stage units) shared by every arrange branch. */
    private const val MOVE_EPSILON = 1e-6

    private fun moved(value: Float): Boolean = abs(value) > MOVE_EPSILON
    private fun moved(value: Double): Boolean = abs(value) > MOVE_EPSILON

    /**
     * Edit targets present on the active build: a multi-selection survives build changes, but
     * nudge / align / distribute / drag never touch a node that isn't on the current build.
     */
    fun presentTargetIds(controller: DocumentElementInspectorController): List<String> {
        val slide = controller.activeSlide() ?: return emptyList()
        return controller.editTargetIds().filter { id ->
            DocumentThemeResolver.findNode(slide.nodes, id)?.let { JsonMergePatch.isNodePresentAtBeat(it, controller.activeBeat) } == true
        }
    }

    private fun findAncestorChain(nodes: List<NodeSpec>, targetId: String): List<NodeSpec>? {
        for (node in nodes) {
            if (node.id == targetId) return listOf(node)
            val childChain = findAncestorChain(node.children, targetId)
            if (childChain != null) return listOf(node) + childChain
        }
        return null
    }

    private fun containerContentInset(node: NodeSpec): Pair<Double, Double> {
        val (containerPad, surfacePad) = when {
            node.stack != null -> node.stack!!.padding to node.stack!!.surface?.padding
            node.row != null -> node.row!!.padding to node.row!!.surface?.padding
            node.column != null -> node.column!!.padding to node.column!!.surface?.padding
            else -> null to null
        }
        val padLeft = (containerPad?.left ?: 0.0) + (surfacePad?.left ?: 0.0)
        val padTop = (containerPad?.top ?: 0.0) + (surfacePad?.top ?: 0.0)
        return padLeft to padTop
    }

    /** Stage-coordinate top-left of the direct parent's content box (`Offset.Zero` for top-level nodes). */
    fun parentContentStageOrigin(
        slide: SlideSpec,
        beat: Int,
        nodeId: String,
        stageBoundsProvider: ((String) -> Rect?)? = null,
    ): Offset {
        val chain = findAncestorChain(slide.nodes, nodeId) ?: return Offset.Zero
        return parentContentStageOrigin(chain, beat, stageBoundsProvider)
    }

    /** Stage-coordinate top-left of [nodeId]'s direct parent content box (`Offset.Zero` for top-level nodes). */
    fun parentContentStageOrigin(
        controller: DocumentElementInspectorController,
        nodeId: String,
        document: DeckDocument = controller.store.document,
        stageBoundsProvider: ((String) -> Rect?)? = controller.stageBoundsProvider,
    ): Offset {
        val slide = controller.slideIn(document) ?: return Offset.Zero
        return parentContentStageOrigin(slide, controller.activeBeat, nodeId, stageBoundsProvider)
    }

    private fun parentContentStageOrigin(
        chain: List<NodeSpec>,
        beat: Int,
        stageBoundsProvider: ((String) -> Rect?)?,
    ): Offset {
        if (chain.size <= 1) return Offset.Zero
        var ox = 0.0
        var oy = 0.0
        for (idx in 0 until chain.lastIndex) {
            val anc = JsonMergePatch.resolveNodeAtBeat(chain[idx], beat)
            val ancX = anc.frame?.x
            val ancY = anc.frame?.y
            val inspectedAnc = stageBoundsProvider?.invoke(anc.id)
            ox = if (ancX != null) ox + ancX else inspectedAnc?.left?.toDouble() ?: ox
            oy = if (ancY != null) oy + ancY else inspectedAnc?.top?.toDouble() ?: oy
            val (padLeft, padTop) = containerContentInset(anc)
            ox += padLeft
            oy += padTop
        }
        return Offset(ox.toFloat(), oy.toFloat())
    }

    /** Filters out nodes that already have an ancestor in [ids] so parent transforms are not applied twice. */
    fun topMostIds(slide: SlideSpec?, ids: Set<String>): Set<String> {
        if (slide == null || ids.size <= 1) return ids
        return ids.filterTo(LinkedHashSet()) { id ->
            val chain = findAncestorChain(slide.nodes, id) ?: return@filterTo true
            (0 until chain.lastIndex).none { chain[it].id in ids }
        }
    }

    /** Whether [nodeId] or any of its ancestors on [slide] is in [ids]. */
    fun hasSelfOrAncestorIn(slide: SlideSpec?, nodeId: String, ids: Set<String>): Boolean {
        if (nodeId in ids) return true
        if (slide == null || ids.isEmpty()) return false
        val chain = findAncestorChain(slide.nodes, nodeId) ?: return false
        return (0 until chain.lastIndex).any { chain[it].id in ids }
    }

    /** Stage-space shift contributed by the nearest ancestor of [chain] present in [stageShifts]. */
    private fun nearestAncestorShift(chain: List<NodeSpec>, stageShifts: Map<String, Offset>): Offset {
        for (idx in chain.lastIndex - 1 downTo 0) {
            stageShifts[chain[idx].id]?.let { return it }
        }
        return Offset.Zero
    }

    private fun nearestAncestorAxisShift(chain: List<NodeSpec>, shifts: Map<String, Double>): Double {
        for (idx in chain.lastIndex - 1 downTo 0) {
            shifts[chain[idx].id]?.let { return it }
        }
        return 0.0
    }

    private data class ResolvedArrangeNode(
        val resolvedFrame: FrameSpec?,
        val stageBounds: Rect,
        val parentOrigin: Offset,
    ) {
        val localLeft: Double get() = resolvedFrame?.x ?: (stageBounds.left.toDouble() - parentOrigin.x)
        val localTop: Double get() = resolvedFrame?.y ?: (stageBounds.top.toDouble() - parentOrigin.y)
        val localBounds: Rect
            get() = Rect(
                localLeft.toFloat(),
                localTop.toFloat(),
                (localLeft + stageBounds.width).toFloat(),
                (localTop + stageBounds.height).toFloat(),
            )
        val isImplicitlyAligned: Boolean get() = resolvedFrame?.x == null && resolvedFrame?.y == null
    }

    private fun resolveArrangeNode(
        controller: DocumentElementInspectorController,
        nodeId: String,
        document: DeckDocument = controller.store.document,
        stageBoundsProvider: ((String) -> Rect?)? = controller.stageBoundsProvider,
    ): ResolvedArrangeNode? {
        val slide = controller.slideIn(document) ?: return null
        val chain = findAncestorChain(slide.nodes, nodeId) ?: return null
        val frame = JsonMergePatch.resolveNodeAtBeat(chain.last(), controller.activeBeat).frame
        val inspected = stageBoundsProvider?.invoke(nodeId)
        val hasExplicitFrame = frame != null && (frame.x != null || frame.y != null || frame.w != null || frame.h != null)
        if (!hasExplicitFrame && inspected == null) return null
        val parentOrigin = parentContentStageOrigin(chain, controller.activeBeat, stageBoundsProvider)
        val frameX = frame?.x
        val frameY = frame?.y
        val stageX = if (frameX != null) parentOrigin.x + frameX else inspected?.left?.toDouble() ?: parentOrigin.x.toDouble()
        val stageY = if (frameY != null) parentOrigin.y + frameY else inspected?.top?.toDouble() ?: parentOrigin.y.toDouble()
        val w = frame?.w ?: inspected?.width?.toDouble() ?: DEFAULT_W
        val h = frame?.h ?: inspected?.height?.toDouble() ?: DEFAULT_H
        return ResolvedArrangeNode(
            resolvedFrame = frame,
            stageBounds = Rect(stageX.toFloat(), stageY.toFloat(), (stageX + w).toFloat(), (stageY + h).toFloat()),
            parentOrigin = parentOrigin,
        )
    }

    fun nodeStageBounds(
        controller: DocumentElementInspectorController,
        nodeId: String,
        stageBoundsProvider: ((String) -> Rect?)? = controller.stageBoundsProvider,
    ): Rect? = resolveArrangeNode(controller, nodeId, stageBoundsProvider = stageBoundsProvider)?.stageBounds

    /**
     * Converts stage-coordinate [stageX] / [stageY] edits into a parent-local `frame` merge patch
     * for [nodeId]. When an alignment-positioned node has neither `frame.x` nor `frame.y`, also
     * pins the untouched local axis so it does not collapse to `0` on its first explicit coordinate.
     */
    fun stagePositionPatch(
        controller: DocumentElementInspectorController,
        nodeId: String,
        stageX: Double? = null,
        stageY: Double? = null,
    ): JsonObject = stagePositionPatches(
        controller = controller,
        stageXById = stageX?.let { mapOf(nodeId to it) }.orEmpty(),
        stageYById = stageY?.let { mapOf(nodeId to it) }.orEmpty(),
    )[nodeId] ?: buildJsonObject {
        put(
            "frame",
            buildJsonObject {
                stageX?.let { put("x", snapHalfUnit(it)) }
                stageY?.let { put("y", snapHalfUnit(it)) }
            },
        )
    }

    /**
     * Converts a batch of stage-coordinate [stageXById] / [stageYById] targets into parent-local
     * `frame` patches, subtracting the translation of any selected ancestor so descendants are
     * never moved twice when both a container and its children are selected.
     */
    fun stagePositionPatches(
        controller: DocumentElementInspectorController,
        stageXById: Map<String, Double> = emptyMap(),
        stageYById: Map<String, Double> = emptyMap(),
    ): Map<String, JsonObject> {
        val ids = LinkedHashSet<String>().apply {
            addAll(stageXById.keys)
            addAll(stageYById.keys)
        }
        if (ids.isEmpty()) return emptyMap()
        val baseDocument = controller.activeGestureStartDocument() ?: controller.store.document
        val slide = controller.slideIn(baseDocument) ?: return emptyMap()

        val chains = ids.associateWith { findAncestorChain(slide.nodes, it) }
        val resolvedById = ids.associateWith { resolveArrangeNode(controller, it, document = baseDocument) }
        val orderedIds = ids.sortedBy { chains[it]?.size ?: 0 }

        val stageShiftX = HashMap<String, Double>()
        val stageShiftY = HashMap<String, Double>()
        val patches = LinkedHashMap<String, JsonObject>()

        for (id in orderedIds) {
            val chain = chains[id]
            val resolved = resolvedById[id]
            val preParentOrigin = resolved?.parentOrigin ?: chain?.let {
                parentContentStageOrigin(it, controller.activeBeat, controller.stageBoundsProvider)
            } ?: Offset.Zero
            val ancestorShiftX = chain?.let { nearestAncestorAxisShift(it, stageShiftX) } ?: 0.0
            val ancestorShiftY = chain?.let { nearestAncestorAxisShift(it, stageShiftY) } ?: 0.0
            val postParentX = preParentOrigin.x + ancestorShiftX
            val postParentY = preParentOrigin.y + ancestorShiftY

            val stageX = stageXById[id]
            val stageY = stageYById[id]
            if (resolved != null) {
                stageX?.let { stageShiftX[id] = it - resolved.stageBounds.left.toDouble() }
                stageY?.let { stageShiftY[id] = it - resolved.stageBounds.top.toDouble() }
            }

            val newLocalX = stageX?.let { it - postParentX }
            val newLocalY = stageY?.let { it - postParentY }
            val parentShifted = moved(ancestorShiftX) || moved(ancestorShiftY)
            if (parentShifted && resolved != null) {
                val xUnchanged = newLocalX == null || !moved(newLocalX - resolved.localLeft)
                val yUnchanged = newLocalY == null || !moved(newLocalY - resolved.localTop)
                if (xUnchanged && yUnchanged) continue
            }

            val preserveOtherAxis = resolved != null && resolved.isImplicitlyAligned
            val finalLocalX = newLocalX ?: if (preserveOtherAxis && stageY != null) resolved.localLeft else null
            val finalLocalY = newLocalY ?: if (preserveOtherAxis && stageX != null) resolved.localTop else null
            patches[id] = buildJsonObject {
                put(
                    "frame",
                    buildJsonObject {
                        finalLocalX?.let { put("x", snapHalfUnit(it)) }
                        finalLocalY?.let { put("y", snapHalfUnit(it)) }
                    },
                )
            }
        }
        return patches
    }

    /** Moves each node by its stage delta (compensating for selected moving ancestors) in ONE undo step. */
    fun moveNodesBy(
        controller: DocumentElementInspectorController,
        deltas: Map<String, Offset>,
        label: String,
    ): Boolean {
        if (controller.store.isReadOnly) return false
        val slide = controller.activeSlide() ?: return false
        val slideId = slide.id
        data class MoveCandidate(
            val id: String,
            val stageDelta: Offset,
            val chain: List<NodeSpec>,
            val resolved: ResolvedArrangeNode,
        )
        val candidates = deltas.entries.mapNotNull { (id, delta) ->
            val chain = findAncestorChain(slide.nodes, id) ?: return@mapNotNull null
            val resolved = resolveArrangeNode(controller, id) ?: return@mapNotNull null
            MoveCandidate(id, delta, chain, resolved)
        }.sortedBy { it.chain.size }

        // Subtract the nearest selected ancestor's stage movement so descendants inside a moving
        // container are not shifted twice.
        val effectiveStageShifts = HashMap<String, Offset>()
        val localMoves = LinkedHashMap<String, Pair<Offset, ResolvedArrangeNode>>()
        for (c in candidates) {
            val ancestorShift = nearestAncestorShift(c.chain, effectiveStageShifts)
            val localDelta = Offset(c.stageDelta.x - ancestorShift.x, c.stageDelta.y - ancestorShift.y)
            effectiveStageShifts[c.id] = c.stageDelta
            if (moved(localDelta.x) || moved(localDelta.y)) {
                localMoves[c.id] = localDelta to c.resolved
            }
        }
        if (localMoves.isEmpty()) return false

        if (controller.editAllBuilds && controller.activeBeat > 0) {
            val activeBeat = controller.activeBeat
            return controller.applyMutation(label, useActiveGesture = false) { doc ->
                localMoves.entries.fold(doc) { acc, (id, pair) ->
                    val (localDelta, resolved) = pair
                    DocumentThemeResolver.updateNodeInSlide(acc, slideId, id) { node ->
                        offsetFrameAcrossAllBuilds(
                            node = node,
                            delta = FrameDelta(dx = localDelta.x.toDouble(), dy = localDelta.y.toDouble()),
                            fallbackBounds = resolved.localBounds,
                            activeBeat = activeBeat,
                        )
                    }
                }
            }
        }
        val patches = LinkedHashMap<String, JsonObject>()
        for ((id, pair) in localMoves) {
            val (localDelta, resolved) = pair
            val moveX = moved(localDelta.x)
            val moveY = moved(localDelta.y)
            // When an alignment-positioned node has neither frame.x nor frame.y, setting one axis
            // disables alignment on BOTH axes in DocumentStackChildrenLayout, so preserve the
            // orthogonal local coordinate as well on this first explicit positioning.
            val writeX = moveX || resolved.isImplicitlyAligned
            val writeY = moveY || resolved.isImplicitlyAligned
            patches[id] = buildJsonObject {
                put(
                    "frame",
                    buildJsonObject {
                        if (writeX) put("x", snapHalfUnit(resolved.localLeft + localDelta.x))
                        if (writeY) put("y", snapHalfUnit(resolved.localTop + localDelta.y))
                    },
                )
            }
        }
        if (patches.isEmpty()) return false
        return controller.applyPatchesToNodes(label, patches)
    }

    /** Relative frame change applied across base + every `Build.Change` frame override. */
    data class FrameDelta(val dx: Double = 0.0, val dy: Double = 0.0, val dw: Double = 0.0, val dh: Double = 0.0)

    /**
     * Offsets [node]'s frame by [delta] on the base AND on every build override that sets that key,
     * so per-build differences (motion between builds) survive `All builds` moves and resizes.
     * Width / height stay at least 1 stage unit; everything snaps to half units.
     */
    fun offsetFrameAcrossAllBuilds(
        node: NodeSpec,
        delta: FrameDelta,
        fallbackBounds: Rect,
        activeBeat: Int = 0,
    ): NodeSpec {
        fun hasBeat0NumericOverride(key: String) = node.builds.any { b ->
            b is Build.Change && b.beat == 0 && ((b.set["frame"] as? JsonObject)?.get(key) as? JsonPrimitive)?.doubleOrNull != null
        }
        fun overriddenAtOrBeforeActiveBeat(key: String) = node.builds.any { b ->
            b is Build.Change && b.beat <= activeBeat && (b.set["frame"] as? JsonObject)?.containsKey(key) == true
        }
        val implicitlyAlignedBase = node.frame?.x == null &&
            node.frame?.y == null &&
            !hasBeat0NumericOverride("x") &&
            !hasBeat0NumericOverride("y")
        val movingPos = moved(delta.dx) || moved(delta.dy)
        val keys = listOf(
            Triple("x", delta.dx, fallbackBounds.left.toDouble()),
            Triple("y", delta.dy, fallbackBounds.top.toDouble()),
            Triple("w", delta.dw, fallbackBounds.width.toDouble()),
            Triple("h", delta.dh, fallbackBounds.height.toDouble()),
        ).filter { (key, d, _) ->
            moved(d) || (implicitlyAlignedBase && movingPos && (key == "x" || key == "y"))
        }
        if (keys.isEmpty()) return node
        fun adjust(key: String, value: Double, d: Double): Double {
            val next = snapHalfUnit(value + d)
            return if (key == "w" || key == "h") next.coerceAtLeast(1.0) else next
        }
        var frame = node.frame ?: FrameSpec()
        for ((key, d, fallback) in keys) {
            val base = when (key) { "x" -> frame.x; "y" -> frame.y; "w" -> frame.w; else -> frame.h }
            val next = when {
                base != null -> adjust(key, base, d)
                hasBeat0NumericOverride(key) -> null
                !overriddenAtOrBeforeActiveBeat(key) -> adjust(key, fallback, d)
                key == "x" || key == "y" -> adjust(key, 0.0, d)
                else -> null
            }
            frame = when (key) {
                "x" -> frame.copy(x = next)
                "y" -> frame.copy(y = next)
                "w" -> frame.copy(w = next)
                else -> frame.copy(h = next)
            }
        }
        val nextFrame = if (frame == (node.frame ?: FrameSpec())) node.frame else frame
        val nextBuilds = node.builds.map { build ->
            val frameObj = (build as? Build.Change)?.set?.get("frame") as? JsonObject ?: return@map build
            if (keys.none { (key, d, _) -> moved(d) && (frameObj[key] as? JsonPrimitive)?.doubleOrNull != null }) return@map build
            val newFrameObj = buildJsonObject {
                for ((k, v) in frameObj) {
                    val d = keys.firstOrNull { it.first == k && moved(it.second) }?.second
                    val old = (v as? JsonPrimitive)?.doubleOrNull
                    if (d != null && old != null) put(k, adjust(k, old, d)) else put(k, v)
                }
            }
            build.copy(set = buildJsonObject { for ((k, v) in build.set) put(k, if (k == "frame") newFrameObj else v) })
        }
        return node.copy(frame = nextFrame, builds = nextBuilds)
    }

    fun nudgeSelected(controller: DocumentElementInspectorController, dx: Double, dy: Double): Boolean {
        val ids = presentTargetIds(controller)
        if (ids.isEmpty()) return false
        val delta = Offset(dx.toFloat(), dy.toFloat())
        return moveNodesBy(controller, ids.associateWith { delta }, nudgeLabel(ids.size))
    }

    fun alignSelected(controller: DocumentElementInspectorController, alignment: NodeAlignment): Boolean {
        val bounds = selectedBounds(controller)
        if (bounds.isEmpty()) return false
        val stage = controller.store.document.stage
        val deltas = StageArrangeMath.alignmentDeltas(
            bounds = bounds,
            alignment = alignment,
            stage = Size(stage.width.toFloat(), stage.height.toFloat()),
        )
        return moveNodesBy(controller, deltas, alignment.label)
    }

    fun distributeSelected(controller: DocumentElementInspectorController, axis: DistributeAxis): Boolean {
        val bounds = selectedBounds(controller)
        if (bounds.size < 3) return false
        return moveNodesBy(controller, StageArrangeMath.distributionDeltas(bounds, axis), axis.label)
    }

    private fun selectedBounds(controller: DocumentElementInspectorController): Map<String, Rect> {
        val out = LinkedHashMap<String, Rect>()
        for (id in presentTargetIds(controller)) {
            nodeStageBounds(controller, id)?.let { out[id] = it }
        }
        return out
    }

    /**
     * Proportional frames for every node when the multi-selection union [startUnion] is resized to
     * [nextUnion] from its outside handles: each node keeps its relative position and size inside
     * the union. Values snap to half stage units.
     */
    fun groupResizeFrames(startUnion: Rect, nextUnion: Rect, startRects: Map<String, Rect>): Map<String, FrameSpec> {
        if (startUnion.width <= 0f || startUnion.height <= 0f) return emptyMap()
        val sx = nextUnion.width.toDouble() / startUnion.width
        val sy = nextUnion.height.toDouble() / startUnion.height
        return startRects.mapValues { (_, r) ->
            FrameSpec(
                x = snapHalfUnit(nextUnion.left + (r.left - startUnion.left) * sx),
                y = snapHalfUnit(nextUnion.top + (r.top - startUnion.top) * sy),
                w = snapHalfUnit((r.width * sx).coerceAtLeast(16.0)),
                h = snapHalfUnit((r.height * sy).coerceAtLeast(16.0)),
            )
        }
    }

    /** Applies [groupResizeFrames] inside the controller's active gesture transaction. */
    fun resizeGroupTo(
        controller: DocumentElementInspectorController,
        startUnion: Rect,
        nextUnion: Rect,
        startRects: Map<String, Rect>,
    ): Boolean {
        val frames = groupResizeFrames(startUnion, nextUnion, startRects)
        if (frames.isEmpty()) return false
        val label = "Resize ${frames.size} elements"
        val baseDocument = controller.activeGestureStartDocument() ?: controller.store.document
        val slide = controller.slideIn(baseDocument) ?: return false
        val chains = frames.keys.associateWith { findAncestorChain(slide.nodes, it) }
        val preParentOrigins = frames.keys.associateWith { id ->
            chains[id]?.let { parentContentStageOrigin(it, controller.activeBeat, controller.stageBoundsProvider) } ?: Offset.Zero
        }
        val stageShifts = frames.mapNotNull { (id, f) ->
            val start = startRects[id] ?: return@mapNotNull null
            id to Offset(((f.x ?: start.left.toDouble()) - start.left).toFloat(), ((f.y ?: start.top.toDouble()) - start.top).toFloat())
        }.toMap()
        val ancestorShifts = frames.keys.associateWith { id ->
            chains[id]?.let { nearestAncestorShift(it, stageShifts) } ?: Offset.Zero
        }
        if (controller.editAllBuilds && controller.activeBeat > 0) {
            val activeBeat = controller.activeBeat
            // Relative change across base + every build (like `moveNodesBy`), never stamping this
            // build's absolute frame onto Beat 0. The transaction re-applies it to its start snapshot.
            return controller.applyMutation(label, useActiveGesture = true) { doc ->
                frames.entries.fold(doc) { acc, (id, f) ->
                    val start = startRects[id] ?: return@fold acc
                    val preParentOrigin = preParentOrigins[id] ?: Offset.Zero
                    val ancShift = ancestorShifts[id] ?: Offset.Zero
                    val startLocal = start.translate(-preParentOrigin.x, -preParentOrigin.y)
                    val delta = FrameDelta(
                        dx = (f.x ?: 0.0) - start.left - ancShift.x,
                        dy = (f.y ?: 0.0) - start.top - ancShift.y,
                        dw = (f.w ?: 0.0) - start.width,
                        dh = (f.h ?: 0.0) - start.height,
                    )
                    DocumentThemeResolver.updateNodeInSlide(acc, slide.id, id) {
                        offsetFrameAcrossAllBuilds(it, delta, startLocal, activeBeat = activeBeat)
                    }
                }
            }
        }
        val patches = frames.mapValues { (id, f) ->
            val preParentOrigin = preParentOrigins[id] ?: Offset.Zero
            val ancShift = ancestorShifts[id] ?: Offset.Zero
            val postParentX = preParentOrigin.x + ancShift.x
            val postParentY = preParentOrigin.y + ancShift.y
            buildJsonObject {
                put(
                    "frame",
                    buildJsonObject {
                        put("x", snapHalfUnit((f.x ?: 0.0) - postParentX))
                        put("y", snapHalfUnit((f.y ?: 0.0) - postParentY))
                        put("w", f.w ?: DEFAULT_W)
                        put("h", f.h ?: DEFAULT_H)
                    },
                )
            }
        }
        return controller.applyPatchesToNodes(label, patches, useActiveGesture = true)
    }

    private fun nudgeLabel(count: Int): String = if (count == 1) "Nudge element" else "Nudge $count elements"
}

/** Keyboard routing for the Design inspector and stage direct-edit overlay. */
internal object DocumentElementInspectorKeys {

    private val arrowKeys = setOf(Key.DirectionLeft, Key.DirectionRight, Key.DirectionUp, Key.DirectionDown)

    fun handleKeyEvent(controller: DocumentElementInspectorController, ev: KeyEvent): Boolean {
        if (handleNudge(controller, ev)) return true
        val isCommandDown = ev.isMetaPressed || ev.isCtrlPressed
        if (ev.type == KeyEventType.KeyDown && isCommandDown && !ev.isAltPressed && !ev.isShiftPressed && ev.key == Key.A) {
            if (controller.textInputHasFocus || controller.inlineEditingNodeId != null) return false
            return controller.selectAllOnCurrentSlide()
        }
        if (ev.type != KeyEventType.KeyDown || ev.isAltPressed) return false
        return handleShortcut(controller, ev.key, isCommandDown, ev.isShiftPressed)
    }

    /**
     * Arrow keys nudge the selection by `1` stage unit, `Alt`/`Option` or `Shift` by `10`, and
     * `Alt+Shift` by `25`. Consumes both key-down and key-up so nudging never advances slides.
     */
    fun handleNudge(controller: DocumentElementInspectorController, ev: KeyEvent): Boolean {
        if (ev.key !in arrowKeys) return false
        if (ev.isMetaPressed || ev.isCtrlPressed) return false
        if (controller.textInputHasFocus || controller.inlineEditingNodeId != null) return false
        if (controller.selectedNodeIds.isEmpty() || controller.store.isReadOnly) return false
        // Nothing selected is on this build: leave the arrows to slide navigation.
        if (DocumentElementArrangeOps.presentTargetIds(controller).isEmpty()) return false
        if (ev.type != KeyEventType.KeyDown) return ev.type == KeyEventType.KeyUp
        val step = StageArrangeMath.nudgeStep(isAltPressed = ev.isAltPressed, isShiftPressed = ev.isShiftPressed)
        val (dx, dy) = when (ev.key) {
            Key.DirectionLeft -> -step to 0.0
            Key.DirectionRight -> step to 0.0
            Key.DirectionUp -> 0.0 to -step
            else -> 0.0 to step
        }
        controller.nudgeSelectedNodes(dx, dy)
        return true
    }

    fun handleShortcut(
        controller: DocumentElementInspectorController,
        key: Key,
        isCommandDown: Boolean,
        isShiftDown: Boolean,
    ): Boolean {
        if (!isCommandDown && key == Key.Escape) {
            if (controller.cancelGesture()) return true
            if (controller.inlineEditingNodeId != null) {
                controller.cancelInlineTextEdit()
                return true
            }
            if (controller.dragPreviewNodeId != null) {
                controller.cancelStageDragPreview()
                return true
            }
            if (controller.selectedNodeId != null) {
                controller.selectNode(null)
                return true
            }
            return false
        }
        if (isCommandDown && key == Key.Z) {
            if (controller.textInputHasFocus || controller.inlineEditingNodeId != null) {
                // Keep local ⌘Z inside focused text input.
                return false
            }
            if (isShiftDown) controller.performRedo() else controller.performUndo()
            return true
        }
        return false
    }
}

/** Clamps and steps a declared float specimen parameter to its range and step. */
internal fun steppedFloatParam(spec: ParamSpec.FloatParam, rawValue: Float): Float {
    val clamped = rawValue.coerceIn(spec.range.start, spec.range.endInclusive)
    return spec.step?.takeIf { it > 0f }?.let { step ->
        val steps = ((clamped - spec.range.start) / step).roundToInt()
        (spec.range.start + steps * step).coerceIn(spec.range.start, spec.range.endInclusive)
    } ?: clamped
}

/** Clamps and steps a declared int specimen parameter to its range and step. */
internal fun steppedIntParam(spec: ParamSpec.IntParam, rawValue: Int): Int {
    val clamped = rawValue.coerceIn(spec.range.first, spec.range.last)
    if (spec.step <= 1) return clamped
    val steps = ((clamped - spec.range.first).toDouble() / spec.step).roundToInt()
    return (spec.range.first + steps * spec.step).coerceIn(spec.range.first, spec.range.last)
}
