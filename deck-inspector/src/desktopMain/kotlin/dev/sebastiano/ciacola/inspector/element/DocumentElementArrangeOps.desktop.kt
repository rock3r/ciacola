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
import dev.sebastiano.ciacola.document.FrameSpec
import dev.sebastiano.ciacola.document.JsonMergePatch
import dev.sebastiano.ciacola.document.NodeSpec
import dev.sebastiano.ciacola.document.ParamSpec
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

    fun nodeStageBounds(controller: DocumentElementInspectorController, nodeId: String): Rect? {
        val slide = controller.activeSlide() ?: return null
        val base = DocumentThemeResolver.findNode(slide.nodes, nodeId) ?: return null
        val frame = JsonMergePatch.resolveNodeAtBeat(base, controller.activeBeat).frame
        val inspected = controller.stageBoundsProvider?.invoke(nodeId)
        val hasExplicitFrame = frame != null && (frame.x != null || frame.y != null || frame.w != null || frame.h != null)
        if (!hasExplicitFrame && inspected == null) return null
        val x = frame?.x ?: inspected?.left?.toDouble() ?: 0.0
        val y = frame?.y ?: inspected?.top?.toDouble() ?: 0.0
        val w = frame?.w ?: inspected?.width?.toDouble() ?: DEFAULT_W
        val h = frame?.h ?: inspected?.height?.toDouble() ?: DEFAULT_H
        return Rect(x.toFloat(), y.toFloat(), (x + w).toFloat(), (y + h).toFloat())
    }

    /** Moves each node by its delta, snapping `frame.x` / `frame.y` to half units, in ONE undo step. */
    fun moveNodesBy(
        controller: DocumentElementInspectorController,
        deltas: Map<String, Offset>,
        label: String,
    ): Boolean {
        if (controller.store.isReadOnly) return false
        val slideId = controller.activeSlide()?.id ?: return false
        if (controller.editAllBuilds && controller.activeBeat > 0) {
            val activeDeltas = LinkedHashMap<String, Pair<Offset, Rect>>()
            for ((id, delta) in deltas) {
                if (!moved(delta.x) && !moved(delta.y)) continue
                val bounds = nodeStageBounds(controller, id) ?: continue
                activeDeltas[id] = delta to bounds
            }
            if (activeDeltas.isEmpty()) return false
            return controller.applyMutation(label, useActiveGesture = false) { doc ->
                activeDeltas.entries.fold(doc) { acc, (id, pair) ->
                    val (delta, bounds) = pair
                    DocumentThemeResolver.updateNodeInSlide(acc, slideId, id) { node ->
                        offsetFrameAcrossAllBuilds(node, FrameDelta(dx = delta.x.toDouble(), dy = delta.y.toDouble()), bounds)
                    }
                }
            }
        }
        val patches = LinkedHashMap<String, JsonObject>()
        for ((id, delta) in deltas) {
            // Only the axis that actually moved is written, so a horizontal nudge never pins (or
            // re-snaps an off-grid) `y` into a build's change patch, and vice versa.
            val moveX = moved(delta.x)
            val moveY = moved(delta.y)
            if (!moveX && !moveY) continue
            val bounds = nodeStageBounds(controller, id) ?: continue
            patches[id] = buildJsonObject {
                put(
                    "frame",
                    buildJsonObject {
                        if (moveX) put("x", snapHalfUnit(bounds.left.toDouble() + delta.x))
                        if (moveY) put("y", snapHalfUnit(bounds.top.toDouble() + delta.y))
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
    fun offsetFrameAcrossAllBuilds(node: NodeSpec, delta: FrameDelta, fallbackBounds: Rect): NodeSpec {
        val keys = listOf(
            Triple("x", delta.dx, fallbackBounds.left.toDouble()),
            Triple("y", delta.dy, fallbackBounds.top.toDouble()),
            Triple("w", delta.dw, fallbackBounds.width.toDouble()),
            Triple("h", delta.dh, fallbackBounds.height.toDouble()),
        ).filter { moved(it.second) }
        if (keys.isEmpty()) return node
        fun adjust(key: String, value: Double, d: Double): Double {
            val next = snapHalfUnit(value + d)
            return if (key == "w" || key == "h") next.coerceAtLeast(1.0) else next
        }
        fun overrides(key: String) = node.builds.any { b -> b is Build.Change && (b.set["frame"] as? JsonObject)?.containsKey(key) == true }
        var frame = node.frame ?: FrameSpec()
        for ((key, d, fallback) in keys) {
            val base = when (key) { "x" -> frame.x; "y" -> frame.y; "w" -> frame.w; else -> frame.h }
            val next = when {
                base != null -> adjust(key, base, d)
                !overrides(key) -> adjust(key, fallback, d)
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
            if (keys.none { (key, _, _) -> (frameObj[key] as? JsonPrimitive)?.doubleOrNull != null }) return@map build
            val newFrameObj = buildJsonObject {
                for ((k, v) in frameObj) {
                    val d = keys.firstOrNull { it.first == k }?.second
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
        if (controller.editAllBuilds && controller.activeBeat > 0) {
            // Relative change across base + every build (like `moveNodesBy`), never stamping this
            // build's absolute frame onto Beat 0. The transaction re-applies it to its start snapshot.
            val slideId = controller.activeSlide()?.id ?: return false
            return controller.applyMutation(label, useActiveGesture = true) { doc ->
                frames.entries.fold(doc) { acc, (id, f) ->
                    val start = startRects[id] ?: return@fold acc
                    val delta = FrameDelta(
                        dx = (f.x ?: 0.0) - start.left,
                        dy = (f.y ?: 0.0) - start.top,
                        dw = (f.w ?: 0.0) - start.width,
                        dh = (f.h ?: 0.0) - start.height,
                    )
                    DocumentThemeResolver.updateNodeInSlide(acc, slideId, id) { offsetFrameAcrossAllBuilds(it, delta, start) }
                }
            }
        }
        val patches = frames.mapValues { (_, f) ->
            buildJsonObject {
                put(
                    "frame",
                    buildJsonObject {
                        put("x", f.x ?: 0.0)
                        put("y", f.y ?: 0.0)
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
