package dev.sebastiano.ciacola.document

import androidx.compose.runtime.Immutable
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Full 2-D affine transform matrix:
 * ```
 * | a  c  tx |
 * | b  d  ty |
 * | 0  0   1 |
 * ```
 * Maps local point `(x, y)` to `(a*x + c*y + tx, b*x + d*y + ty)`.
 */
@Immutable
data class Affine2D(
    val a: Double = 1.0,
    val b: Double = 0.0,
    val c: Double = 0.0,
    val d: Double = 1.0,
    val tx: Double = 0.0,
    val ty: Double = 0.0,
) {
    /** Matrix product `this * other` (applies `other` first, then `this`). */
    operator fun times(other: Affine2D): Affine2D = Affine2D(
        a = a * other.a + c * other.b,
        b = b * other.a + d * other.b,
        c = a * other.c + c * other.d,
        d = b * other.c + d * other.d,
        tx = a * other.tx + c * other.ty + tx,
        ty = b * other.tx + d * other.ty + ty,
    )

    fun mapPoint(x: Double, y: Double): Vec2 = Vec2(
        x = a * x + c * y + tx,
        y = b * x + d * y + ty,
    )

    /**
     * Decomposes this affine matrix into translation, rotation (degrees), and axis scale (`scaleX`,
     * `scaleY`) if and only if its linear part contains no shear beyond [shearTolerance].
     *
     * Returns `null` when the matrix is singular or sheared (for example, a rotated child of a
     * non-uniformly scaled parent).
     */
    fun decompose(shearTolerance: Double = DEFAULT_SHEAR_TOLERANCE): DecomposedTransform? {
        val sxMag = hypot(a, b)
        val syMag = hypot(c, d)
        if (sxMag < 1e-9 || syMag < 1e-9) return null

        // Check orthogonality of column vectors: cos(theta) = (col0 · col1) / (|col0| * |col1|).
        val dot = a * c + b * d
        val cosShear = abs(dot) / (sxMag * syMag)
        if (cosShear > shearTolerance) return null

        val det = a * d - b * c
        val scaleX = sxMag
        val scaleY = if (det < 0.0) -syMag else syMag
        val rotationRad = atan2(b, a)
        val rotationDeg = rotationRad * (180.0 / PI)

        return DecomposedTransform(
            tx = tx,
            ty = ty,
            rotationDeg = rotationDeg,
            scaleX = scaleX,
            scaleY = scaleY,
        )
    }

    companion object {
        const val DEFAULT_SHEAR_TOLERANCE: Double = 1e-3

        val Identity: Affine2D = Affine2D()

        fun translation(dx: Double, dy: Double): Affine2D = Affine2D(tx = dx, ty = dy)

        fun rotationDegrees(degrees: Double): Affine2D {
            if (degrees == 0.0) return Identity
            val rad = degrees * (PI / 180.0)
            val cosR = cos(rad)
            val sinR = sin(rad)
            return Affine2D(a = cosR, b = sinR, c = -sinR, d = cosR)
        }

        fun scale(sx: Double, sy: Double): Affine2D = Affine2D(a = sx, d = sy)

        fun fromDecomposed(t: DecomposedTransform): Affine2D =
            translation(t.tx, t.ty) * rotationDegrees(t.rotationDeg) * scale(t.scaleX, t.scaleY)
    }
}

/**
 * Shear-free decomposed 2-D transform (`translate(tx, ty) * rotate(rotationDeg) * scale(scaleX, scaleY)`).
 * Reproducing [toAffine] at `p = 0` and `p = 1` reproduces the endpoint affines exactly.
 */
@Immutable
data class DecomposedTransform(
    val tx: Double,
    val ty: Double,
    val rotationDeg: Double,
    val scaleX: Double,
    val scaleY: Double,
) {
    fun toAffine(): Affine2D = Affine2D.fromDecomposed(this)

    companion object {
        /** Linearly interpolates between [from] and [to] along shortest angular distance. */
        fun lerp(from: DecomposedTransform, to: DecomposedTransform, p: Double): DecomposedTransform {
            var deltaRot = (to.rotationDeg - from.rotationDeg) % 360.0
            if (deltaRot > 180.0) deltaRot -= 360.0
            if (deltaRot < -180.0) deltaRot += 360.0
            return DecomposedTransform(
                tx = from.tx + (to.tx - from.tx) * p,
                ty = from.ty + (to.ty - from.ty) * p,
                rotationDeg = from.rotationDeg + deltaRot * p,
                scaleX = from.scaleX + (to.scaleX - from.scaleX) * p,
                scaleY = from.scaleY + (to.scaleY - from.scaleY) * p,
            )
        }
    }
}

/**
 * Pure document-determined endpoint geometry result.
 */
sealed interface DocumentTransformResult {
    @Immutable
    data class Determined(
        val affine: Affine2D,
        val decomposed: DecomposedTransform?,
        val width: Double?,
        val height: Double?,
        val availableWidth: Double,
    ) : DocumentTransformResult

    @Immutable
    data class NotDetermined(
        val reason: String,
    ) : DocumentTransformResult
}

/**
 * A cross-slide matched travelling node pair satisfying deterministic endpoint geometry and size rules.
 */
@Immutable
data class MatchedPair(
    val nodeId: String,
    val kind: NodeKind,
    val fromNode: NodeSpec,
    val toNode: NodeSpec,
    val fromAffine: Affine2D,
    val toAffine: Affine2D,
    val fromTransform: DecomposedTransform,
    val toTransform: DecomposedTransform,
    val fromAvailableWidth: Double,
    val toAvailableWidth: Double,
    val specimenRef: String? = null,
    val isDocumentDetermined: Boolean = true,
    val rawFromNode: NodeSpec = fromNode,
    val rawToNode: NodeSpec = toNode,
)

@Immutable
data class CrossSlideMatchResult(
    val pairs: List<MatchedPair>,
    val lintIssues: List<DocumentIssue>,
)

/**
 * Single-owner selector for a travelling node across a cross-slide boundary.
 */
enum class CrossSlideOwner {
    SceneA,
    SceneB,
    Overlay,
}

/**
 * Canonical stacking order for two overlapping slide layers `(A, B)` during `Cross(A, B)`.
 */
enum class SlideLayerOrder {
    /** Earlier slide A is drawn above later slide B (`reveal`, `iris` with `direction = "out"`). */
    AOverB,

    /** Later slide B is drawn above earlier slide A (all other transition types). */
    BOverA,
}

/**
 * Pure, composition-free, layout-free cross-slide endpoint geometry, pairing, ownership, and
 * stacking calculations.
 */
object CrossSlideGeometry {

    /**
     * Computes [nodeId]'s transform to stage space purely from [slide] at [beat] (already resolved
     * or resolved via [JsonMergePatch.resolveNodeAtBeat]).
     *
     * Rules:
     * - The node must have explicit `frame.x` and `frame.y`.
     * - Every ancestor must be a `stack` with explicit `x`, `y`, `w`, `h`.
     * - A `stack` places a framed child with top-left `(x, y)` inside the stack's **content box**,
     *   which is the stack's box inset by `stack.padding` plus `stack.surface.padding`.
     *   Explicit `x`/`y` take precedence over `align`.
     * - The node must have explicit `w` and `h`, OR have `rotation == 0` and unit scale (`(1, 1)`).
     */
    fun computeDocumentTransform(
        slide: SlideSpec,
        nodeId: String,
        beat: Int = 0,
        stage: StageSize = StageSize(),
        shearTolerance: Double = Affine2D.DEFAULT_SHEAR_TOLERANCE,
    ): DocumentTransformResult {
        val chain = findNodeChain(slide.nodes, nodeId)
            ?: return DocumentTransformResult.NotDetermined("Node '$nodeId' not found on slide '${slide.id}'.")

        val resolvedChain = chain.map { JsonMergePatch.resolveNodeAtBeat(it, beat) }
        var matrix = Affine2D.Identity
        var availableWidth = stage.width.toDouble()

        // Validate and compose every strict ancestor (0 until lastIndex).
        for (idx in 0 until resolvedChain.lastIndex) {
            val anc = resolvedChain[idx]
            val ancKind = anc.kind
            if (ancKind != NodeKind.Stack) {
                return DocumentTransformResult.NotDetermined(
                    reason = "matches across slides but it, or an ancestor ('${anc.id}' of kind '${ancKind?.payloadKey}'), " +
                        "is laid out by a non-stack container.",
                )
            }
            val f = anc.frame
            if (f?.x == null || f.y == null || f.w == null || f.h == null) {
                return DocumentTransformResult.NotDetermined(
                    reason = "Ancestor stack '${anc.id}' must declare explicit frame x, y, w, and h.",
                )
            }
            matrix *= nodeLocalTransform(anc, f.x, f.y, f.w, f.h)

            // Stack content box is inset by container padding + surface.padding.
            val stackPayload = anc.stack!!
            val padLeft = (stackPayload.padding?.left ?: 0.0) + (stackPayload.surface?.padding?.left ?: 0.0)
            val padTop = (stackPayload.padding?.top ?: 0.0) + (stackPayload.surface?.padding?.top ?: 0.0)
            val padRight = (stackPayload.padding?.right ?: 0.0) + (stackPayload.surface?.padding?.right ?: 0.0)
            if (padLeft != 0.0 || padTop != 0.0) {
                matrix *= Affine2D.translation(padLeft, padTop)
            }
            availableWidth = (f.w - padLeft - padRight).coerceAtLeast(0.0)
        }

        // Validate and compose the target node itself.
        val node = resolvedChain.last()
        val nf = node.frame
        if (nf?.x == null || nf.y == null) {
            return DocumentTransformResult.NotDetermined(
                reason = "Node '${node.id}' must declare explicit frame x and y to travel across slides.",
            )
        }
        val sx = node.scale?.scaleX ?: 1.0
        val sy = node.scale?.scaleY ?: 1.0
        val hasRotationOrNonUnitScale = node.rotation != 0.0 || sx != 1.0 || sy != 1.0
        if (hasRotationOrNonUnitScale && (nf.w == null || nf.h == null)) {
            return DocumentTransformResult.NotDetermined(
                reason = "Node '${node.id}' has rotation (${node.rotation}) or non-unit scale ($sx, $sy) and requires explicit frame w and h.",
            )
        }

        val wForPivot = nf.w ?: 0.0
        val hForPivot = nf.h ?: 0.0
        matrix *= nodeLocalTransform(node, nf.x, nf.y, wForPivot, hForPivot)

        val nodeAvailableWidth = nf.w ?: (availableWidth - nf.x).coerceAtLeast(0.0)
        return DocumentTransformResult.Determined(
            affine = matrix,
            decomposed = matrix.decompose(shearTolerance),
            width = nf.w,
            height = nf.h,
            availableWidth = nodeAvailableWidth,
        )
    }

    /**
     * Purely determines all travelling [MatchedPair]s between [fromSlide] (at its last beat) and
     * [toSlide] (at beat 0, before any beat-0 `enter` builds).
     *
     * Enforces the outermost-owner rule: matched descendants of a matched container are NOT emitted
     * as separate travelling pairs.
     */
    fun matchCrossSlidePairs(
        fromSlide: SlideSpec,
        toSlide: SlideSpec,
        stage: StageSize = StageSize(),
        shearTolerance: Double = Affine2D.DEFAULT_SHEAR_TOLERANCE,
    ): CrossSlideMatchResult {
        val fromBeat = fromSlide.beats.lastIndex.coerceAtLeast(0)
        val fromPresent = collectEndpointNodes(fromSlide.nodes, beat = fromBeat, isStartEndpoint = false)
        val toPresent = collectEndpointNodes(toSlide.nodes, beat = 0, isStartEndpoint = true)

        val pairs = mutableListOf<MatchedPair>()
        val lintIssues = mutableListOf<DocumentIssue>()
        val matchedIds = hashSetOf<String>()

        // Traverse B's tree top-down (B's document order gives overlay z-order and enforces outermost-owner).
        fun walkTarget(nodes: List<NodeSpec>, ancestorMatched: Boolean, underUnmatchedLoupe: Boolean) {
            for (rawTo in nodes) {
                val id = rawTo.id
                val toEntry = toPresent[id]
                val fromEntry = fromPresent[id]

                if (ancestorMatched) {
                    // Outermost matched ancestor already owns this subtree.
                    walkTarget(rawTo.children, ancestorMatched = true, underUnmatchedLoupe = underUnmatchedLoupe)
                    continue
                }

                var isMatchedHere = false
                if (!underUnmatchedLoupe && fromEntry != null && toEntry != null) {
                    val fromNode = fromEntry.resolved
                    val toNode = toEntry.resolved
                    val fromKind = fromNode.kind
                    val toKind = toNode.kind
                    val sameKind = fromKind != null && fromKind == toKind
                    val sameSpecimenRef = fromKind != NodeKind.Specimen || fromNode.specimen?.ref == toNode.specimen?.ref
                    val crossesLoupeBoundary = fromEntry.nearestLoupeId != toEntry.nearestLoupeId ||
                        fromEntry.nearestLoupeId != null

                    if (sameKind && sameSpecimenRef && !crossesLoupeBoundary) {
                        val addr = "${fromSlide.id}->${toSlide.id}/$id"
                        when {
                            // Code and connector nodes stay on the scene cross-fade path;
                            // connectors resolve live moving endpoint anchors from the shared anchor registry.
                            fromKind == NodeKind.Code || fromKind == NodeKind.Connector -> Unit

                            // Specimens and containers holding specimens require deterministic sizes at each endpoint.
                            !satisfiesSpecimenSizeRule(fromNode, fromEntry.parentNode, beat = fromBeat, isStartEndpoint = false) ||
                                !satisfiesSpecimenSizeRule(toNode, toEntry.parentNode, beat = 0, isStartEndpoint = true) -> {
                                lintIssues += DocumentIssue(
                                    severity = IssueSeverity.LintWarning,
                                    code = "CROSS_SLIDE_SPECIMEN_MISSING_SIZE",
                                    address = addr,
                                    message = "Matched specimen (or container holding a specimen) '$id' lacks deterministic size (explicit w/h, or weight+cross-size in row/column) at one or both endpoints; it will cross-fade instead of travelling.",
                                )
                            }

                            else -> {
                                val fromGeom = computeDocumentTransform(fromSlide, id, fromBeat, stage, shearTolerance)
                                val toGeom = computeDocumentTransform(toSlide, id, 0, stage, shearTolerance)

                                val fromDetermined = fromGeom is DocumentTransformResult.Determined
                                val toDetermined = toGeom is DocumentTransformResult.Determined

                                val fromAffine: Affine2D
                                val dFrom: DecomposedTransform?
                                val fromAvailW: Double
                                if (fromGeom is DocumentTransformResult.Determined) {
                                    fromAffine = fromGeom.affine
                                    dFrom = fromGeom.decomposed
                                    fromAvailW = fromGeom.availableWidth
                                } else {
                                    val linear = computeDocumentLinearTransform(fromSlide, id, fromBeat) ?: Affine2D.Identity
                                    fromAffine = linear
                                    dFrom = linear.decompose(shearTolerance)
                                    fromAvailW = fromNode.frame?.w ?: stage.width.toDouble()
                                }

                                val toAffine: Affine2D
                                val dTo: DecomposedTransform?
                                val toAvailW: Double
                                if (toGeom is DocumentTransformResult.Determined) {
                                    toAffine = toGeom.affine
                                    dTo = toGeom.decomposed
                                    toAvailW = toGeom.availableWidth
                                } else {
                                    val linear = computeDocumentLinearTransform(toSlide, id, 0) ?: Affine2D.Identity
                                    toAffine = linear
                                    dTo = linear.decompose(shearTolerance)
                                    toAvailW = toNode.frame?.w ?: stage.width.toDouble()
                                }

                                if (dFrom == null || dTo == null) {
                                    lintIssues += DocumentIssue(
                                        severity = IssueSeverity.LintWarning,
                                        code = "CROSS_SLIDE_SHEARED_TRANSFORM",
                                        address = addr,
                                        message = "Node '$id' has a sheared endpoint transform (e.g. rotated child of a non-uniformly scaled parent); it will cross-fade instead of travelling.",
                                    )
                                } else {
                                    pairs += MatchedPair(
                                        nodeId = id,
                                        kind = fromKind,
                                        fromNode = fromNode,
                                        toNode = toNode,
                                        fromAffine = fromAffine,
                                        toAffine = toAffine,
                                        fromTransform = dFrom,
                                        toTransform = dTo,
                                        fromAvailableWidth = fromAvailW,
                                        toAvailableWidth = toAvailW,
                                        specimenRef = fromNode.specimen?.ref,
                                        isDocumentDetermined = fromDetermined && toDetermined,
                                        rawFromNode = fromEntry.raw,
                                        rawToNode = toEntry.raw,
                                    )
                                    matchedIds += id
                                    isMatchedHere = true
                                }
                            }
                        }
                    }
                }

                val childUnderUnmatchedLoupe = underUnmatchedLoupe ||
                    (toEntry?.resolved?.kind == NodeKind.Loupe && !isMatchedHere)
                walkTarget(
                    rawTo.children,
                    ancestorMatched = isMatchedHere,
                    underUnmatchedLoupe = childUnderUnmatchedLoupe,
                )
            }
        }

        walkTarget(toSlide.nodes, ancestorMatched = false, underUnmatchedLoupe = false)
        return CrossSlideMatchResult(pairs = pairs, lintIssues = lintIssues)
    }

    /**
     * Single-owner rule for a travelling node pair `(A, B)` under [clock]:
     * - `Rest` or `Beat` on A -> [CrossSlideOwner.SceneA]
     * - `Cross(A, B)` -> [CrossSlideOwner.Overlay]
     * - `AtStart`, `Entrance`, `Rest`, or `Beat` on B -> [CrossSlideOwner.SceneB]
     */
    fun owner(
        fromSlideId: String,
        toSlideId: String,
        clock: DeckClock,
    ): CrossSlideOwner = when (val seg = clock.segment) {
        is DocSegment.Cross -> if (seg.fromSlideId == fromSlideId && seg.toSlideId == toSlideId) {
            CrossSlideOwner.Overlay
        } else if (seg.toSlideId == fromSlideId) {
            CrossSlideOwner.SceneA
        } else {
            CrossSlideOwner.SceneB
        }
        is DocSegment.Rest -> if (seg.slideId == fromSlideId) CrossSlideOwner.SceneA else CrossSlideOwner.SceneB
        is DocSegment.Beat -> if (seg.slideId == fromSlideId) CrossSlideOwner.SceneA else CrossSlideOwner.SceneB
        is DocSegment.AtStart -> if (seg.slideId == fromSlideId) CrossSlideOwner.SceneA else CrossSlideOwner.SceneB
        is DocSegment.Entrance -> if (seg.slideId == fromSlideId) CrossSlideOwner.SceneA else CrossSlideOwner.SceneB
    }

    /**
     * Canonical layer stacking order for a whole-slide transition.
     */
    fun transitionLayerOrder(transition: TransitionSpec?): SlideLayerOrder {
        val spec = transition ?: return SlideLayerOrder.BOverA
        return when (spec.type) {
            TransitionType.Reveal -> SlideLayerOrder.AOverB
            TransitionType.Iris -> {
                val dir = spec.direction?.trim()
                val isOut = dir != null && (
                    dir.equals("out", ignoreCase = true) ||
                        dir.equals("close", ignoreCase = true) ||
                        dir.equals("shrink", ignoreCase = true)
                    )
                if (isOut) SlideLayerOrder.AOverB else SlideLayerOrder.BOverA
            }
            else -> SlideLayerOrder.BOverA
        }
    }

    /**
     * Computes the `sceneZIndex` for scene [slideId] under [clock].
     *
     * During `Cross(A, B)`: returns canonical stacking order from [transitionLayerOrder] (`AOverB`
     * gives A higher z-index than B; `BOverA` gives B higher z-index than A), independent of
     * navigation direction. Outside `Cross`: returns slide index order.
     */
    fun sceneZIndex(
        slideIndex: Int,
        slideId: String,
        clock: DeckClock,
        transitionOfSlide: (String) -> TransitionSpec?,
    ): Float {
        val seg = clock.segment
        if (seg is DocSegment.Cross) {
            val order = transitionLayerOrder(transitionOfSlide(seg.fromSlideId))
            if (slideId == seg.fromSlideId) {
                return if (order == SlideLayerOrder.AOverB) 2f else 1f
            }
            if (slideId == seg.toSlideId) {
                return if (order == SlideLayerOrder.AOverB) 1f else 2f
            }
        }
        return slideIndex.toFloat()
    }

    /**
     * Computes the local 2-D affine transform of [node] placed at un-transformed top-left `(x, y)`
     * with laid-out dimensions `(w, h)` inside its parent's content box.
     */
    fun nodeLocalTransform(
        node: NodeSpec,
        x: Double,
        y: Double,
        w: Double,
        h: Double,
    ): Affine2D {
        val ox = node.offset?.x ?: 0.0
        val oy = node.offset?.y ?: 0.0
        val sx = node.scale?.scaleX ?: 1.0
        val sy = node.scale?.scaleY ?: 1.0
        val rot = node.rotation

        var m = Affine2D.translation(x + ox, y + oy)
        if (rot != 0.0 || sx != 1.0 || sy != 1.0) {
            val pivotX = (node.origin?.x ?: 0.5) * w
            val pivotY = (node.origin?.y ?: 0.5) * h
            m = m *
                Affine2D.translation(pivotX, pivotY) *
                Affine2D.rotationDegrees(rot) *
                Affine2D.scale(sx, sy) *
                Affine2D.translation(-pivotX, -pivotY)
        }
        return m
    }

    /**
     * Computes the ancestor-composed 2×2 linear matrix (`rotation * scale` chain) for [nodeId] on
     * [slide] at [beat]. Because the 2×2 linear part depends solely on `rotation` and `scale` down
     * the ancestor chain and never on `(x, y, w, h)`, this enables plan-time shear and singularity
     * checks for flow-laid-out and auto-sized nodes before Lookahead layout runs.
     */
    fun computeDocumentLinearTransform(
        slide: SlideSpec,
        nodeId: String,
        beat: Int = 0,
    ): Affine2D? {
        val chain = findNodeChain(slide.nodes, nodeId) ?: return null
        var linear = Affine2D.Identity
        for (raw in chain) {
            val resolved = JsonMergePatch.resolveNodeAtBeat(raw, beat)
            val rot = resolved.rotation
            val sx = resolved.scale?.scaleX ?: 1.0
            val sy = resolved.scale?.scaleY ?: 1.0
            if (rot != 0.0) linear *= Affine2D.rotationDegrees(rot)
            if (sx != 1.0 || sy != 1.0) linear *= Affine2D.scale(sx, sy)
        }
        return linear
    }

    private fun satisfiesSpecimenSizeRule(
        node: NodeSpec,
        parentNode: NodeSpec?,
        beat: Int = 0,
        isStartEndpoint: Boolean = false,
    ): Boolean {
        val parentKind = parentNode?.kind
        if (node.kind == NodeKind.Specimen) {
            val w = node.frame?.w
            val h = node.frame?.h
            val weight = node.weight
            val hasExplicitSize = w != null && w > 0.0 && h != null && h > 0.0
            val parentHasWidth = (parentNode?.frame?.w ?: 0.0) > 0.0 || (parentNode?.weight ?: 0.0) > 0.0
            val parentHasHeight = (parentNode?.frame?.h ?: 0.0) > 0.0 || (parentNode?.weight ?: 0.0) > 0.0
            val hasRowWeightedSize = parentKind == NodeKind.Row &&
                parentHasWidth && weight != null && weight > 0.0 && h != null && h > 0.0
            val hasColWeightedSize = parentKind == NodeKind.Column &&
                parentHasHeight && weight != null && weight > 0.0 && w != null && w > 0.0
            if (!hasExplicitSize && !hasRowWeightedSize && !hasColWeightedSize) {
                return false
            }
        }
        for (child in node.children) {
            val present = if (isStartEndpoint) {
                JsonMergePatch.isNodePresentAtStart(child)
            } else {
                JsonMergePatch.isNodePresentAtBeat(child, beat)
            }
            if (!present) continue
            val resolvedChild = JsonMergePatch.resolveNodeAtBeat(child, beat)
            if (!satisfiesSpecimenSizeRule(resolvedChild, parentNode = node, beat = beat, isStartEndpoint = isStartEndpoint)) {
                return false
            }
        }
        return true
    }

    private data class EndpointNodeEntry(
        val raw: NodeSpec,
        val resolved: NodeSpec,
        val parentNode: NodeSpec?,
        val nearestLoupeId: String?,
    )

    private fun collectEndpointNodes(
        nodes: List<NodeSpec>,
        beat: Int,
        isStartEndpoint: Boolean,
    ): LinkedHashMap<String, EndpointNodeEntry> {
        val map = LinkedHashMap<String, EndpointNodeEntry>()
        fun visit(list: List<NodeSpec>, parentNode: NodeSpec?, nearestLoupeId: String?) {
            for (raw in list) {
                val present = if (isStartEndpoint) {
                    // During Cross(A, B), nodes on B with a beat-0 enter build are still hidden.
                    JsonMergePatch.isNodePresentAtStart(raw)
                } else {
                    JsonMergePatch.isNodePresentAtBeat(raw, beat)
                }
                if (present) {
                    val resolved = JsonMergePatch.resolveNodeAtBeat(raw, beat)
                    map[raw.id] = EndpointNodeEntry(raw, resolved, parentNode, nearestLoupeId)
                    val childLoupeId = if (resolved.kind == NodeKind.Loupe) resolved.id else nearestLoupeId
                    visit(raw.children, parentNode = resolved, nearestLoupeId = childLoupeId)
                }
            }
        }
        visit(nodes, parentNode = null, nearestLoupeId = null)
        return map
    }

    private fun findNodeChain(nodes: List<NodeSpec>, targetId: String): List<NodeSpec>? {
        for (node in nodes) {
            if (node.id == targetId) return listOf(node)
            val sub = findNodeChain(node.children, targetId)
            if (sub != null) return listOf(node) + sub
        }
        return null
    }
}
