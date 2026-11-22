package dev.sebastiano.ciacola.inspector.element

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import dev.sebastiano.ciacola.document.DeckDocument
import dev.sebastiano.ciacola.document.DeckDocumentStore
import dev.sebastiano.ciacola.document.DeckSpecimenRegistry
import dev.sebastiano.ciacola.document.DeckStructureEdits
import dev.sebastiano.ciacola.document.EditTransaction
import dev.sebastiano.ciacola.document.FrameSpec
import dev.sebastiano.ciacola.document.JsonMergePatch
import dev.sebastiano.ciacola.document.ManualDelayRemapNotice
import dev.sebastiano.ciacola.document.MorphBy
import dev.sebastiano.ciacola.document.NodeKind
import dev.sebastiano.ciacola.document.NodeSpec
import dev.sebastiano.ciacola.document.PaddingSpec
import dev.sebastiano.ciacola.document.ParamSpec
import dev.sebastiano.ciacola.document.ScaleValue
import dev.sebastiano.ciacola.document.SlideSpec
import dev.sebastiano.ciacola.document.SpecimenDeclaration
import dev.sebastiano.ciacola.document.StrokeSpec
import dev.sebastiano.ciacola.document.TextRangeEdit
import dev.sebastiano.ciacola.inspector.document.DocumentThemeResolver
import dev.sebastiano.ciacola.inspector.document.snapHalfUnit
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Document-backed Element Inspector and Stage Direct Edit controller.
 *
 * All edits write through [DeckDocumentStore] onto the single unified undo stack using RFC 7386
 * beat-aware merge patches ([JsonMergePatch]) and edit-range fragment provenance
 * ([DeckStructureEdits.applyTextRangeEdit]).
 */
@Stable
class DocumentElementInspectorController(
    val store: DeckDocumentStore,
    val specimens: DeckSpecimenRegistry = DeckSpecimenRegistry.Empty,
    initialSlideId: String? = null,
    initialBeat: Int = 0,
    var onUndoRequest: (() -> Unit)? = null,
    var onRedoRequest: (() -> Unit)? = null,
    var onBeforeBeginGesture: (() -> Unit)? = null,
) {
    var activeSlideId: String by mutableStateOf(
        initialSlideId ?: store.document.slides.firstOrNull { !it.skipped }?.id.orEmpty(),
    )

    var activeBeat: Int by mutableIntStateOf(initialBeat.coerceAtLeast(0))

    private var primarySelectionId: String? by mutableStateOf(null)

    /** Full multi-selection (`Shift+Click`, marquee, `Cmd+A`); always contains [selectedNodeId]. */
    var selectedNodeIds: Set<String> by mutableStateOf(emptySet())
        private set

    /** Primary selected node shown in the Design inspector. Setting it replaces the multi-selection. */
    var selectedNodeId: String?
        get() = primarySelectionId
        set(value) = updateSelection(setOfNotNull(value), value)

    /** Invoked whenever the primary selection changes (bidirectional Timeline sync). */
    var onSelectionChanged: ((String?) -> Unit)? = null

    /** Invoked by `Go to Build N` when a selected node is absent from the current beat. */
    var onNavigateToBeat: ((Int) -> Unit)? = null

    /**
     * When `true` on a beat > 0, inspector edits apply to every build (`This build | All builds`
     * toggle) instead of the current build's change patch.
     */
    var editAllBuilds: Boolean by mutableStateOf(false)

    /** Fallback stage bounds for nodes without an explicit frame (fed by the stage overlay). */
    var stageBoundsProvider: ((String) -> Rect?)? = null

    var textInputHasFocus: Boolean by mutableStateOf(false)

    /** Ephemeral stage drag preview (zero document writes until drag release). */
    var dragPreviewNodeId: String? by mutableStateOf(null)
        private set

    /** All nodes moving together during a shared multi-node stage drag. */
    var dragPreviewNodeIds: Set<String> by mutableStateOf(emptySet())
        private set
    var dragPreviewDeltaX: Double by mutableDoubleStateOf(0.0)
        private set
    var dragPreviewDeltaY: Double by mutableDoubleStateOf(0.0)
        private set

    /** Active 8-handle stage resize gesture state (backed by a single `EditTransaction`). */
    var resizePreviewNodeId: String? by mutableStateOf(null)
        private set
    var resizePreviewFrame: FrameSpec? by mutableStateOf(null)
        private set

    /** Active inline stage text editing session. */
    var inlineEditingNodeId: String? by mutableStateOf(null)
        private set
    var inlineOriginalText: String by mutableStateOf("")
        private set

    /** Notices emitted when manual fragment delays are pruned after a text edit. */
    var remapNotices: List<ManualDelayRemapNotice> by mutableStateOf(emptyList())
        private set

    private var activeTx: EditTransaction? = null

    val hasActiveTransaction: Boolean
        get() = activeTx?.isActive == true || store.hasOpenTransaction

    fun activeSlide(): SlideSpec? =
        store.document.slides.firstOrNull { it.id == activeSlideId }
            ?: store.document.slides.firstOrNull { !it.skipped }

    fun activeSlideNodes(): List<NodeSpec> =
        activeSlide()?.let { DocumentThemeResolver.flattenNodes(it.nodes) }.orEmpty()

    fun selectedBaseNode(): NodeSpec? {
        val id = selectedNodeId ?: return null
        val slide = activeSlide() ?: return null
        return DocumentThemeResolver.findNode(slide.nodes, id)
    }

    fun selectedResolvedNode(): NodeSpec? {
        val base = selectedBaseNode() ?: return null
        return JsonMergePatch.resolveNodeAtBeat(base, activeBeat)
    }

    fun isSelectedNodePresentOnBeat(): Boolean {
        val base = selectedBaseNode() ?: return false
        return JsonMergePatch.isNodePresentAtBeat(base, activeBeat)
    }

    fun selectedSpecimenDeclaration(): SpecimenDeclaration? {
        val ref = selectedResolvedNode()?.specimen?.ref ?: return null
        return specimens.find(ref)
    }

    fun selectLocation(slideId: String, beat: Int) {
        if (activeSlideId != slideId) {
            cancelInlineTextEdit()
            cancelStageDragPreview()
            activeSlideId = slideId
            val slide = store.document.slides.firstOrNull { it.id == slideId }
            if (slide != null && selectedNodeIds.isNotEmpty()) {
                val surviving = selectedNodeIds.filterTo(LinkedHashSet()) {
                    DocumentThemeResolver.findNode(slide.nodes, it) != null
                }
                updateSelection(surviving, selectedNodeId)
            }
        }
        activeBeat = beat.coerceAtLeast(0)
    }

    /**
     * Selects [nodeId]. With [additive] (`Shift+Click`) the node is toggled in the multi-selection
     * instead of replacing it.
     */
    fun selectNode(nodeId: String?, additive: Boolean = false) {
        if (inlineEditingNodeId != null && inlineEditingNodeId != nodeId) {
            cancelInlineTextEdit()
        }
        if (additive && nodeId != null) {
            val next = LinkedHashSet(selectedNodeIds)
            if (next.remove(nodeId)) {
                updateSelection(next, selectedNodeId?.takeIf { it != nodeId } ?: next.lastOrNull())
            } else {
                next.add(nodeId)
                updateSelection(next, nodeId)
            }
        } else {
            updateSelection(setOfNotNull(nodeId), nodeId)
        }
    }

    /** Replaces the multi-selection with [nodeIds], making [primaryId] (or the last ID) primary. */
    fun selectNodes(nodeIds: Set<String>, primaryId: String? = null) {
        if (inlineEditingNodeId != null && inlineEditingNodeId !in nodeIds) {
            cancelInlineTextEdit()
        }
        updateSelection(nodeIds, primaryId)
    }

    /** `Cmd+A`: selects every top-level node present on the current beat of the active slide. */
    fun selectAllOnCurrentSlide(): Boolean {
        val slide = activeSlide() ?: return false
        val ids = slide.nodes
            .filter { JsonMergePatch.isNodePresentAtBeat(it, activeBeat) }
            .mapTo(LinkedHashSet()) { it.id }
        if (ids.isEmpty()) return false
        selectNodes(ids, primaryId = selectedNodeId?.takeIf { it in ids } ?: ids.first())
        return true
    }

    /** IDs edited by inspector setters: the whole multi-selection, primary first. */
    fun editTargetIds(): List<String> {
        val primary = selectedNodeId ?: return emptyList()
        return listOf(primary) + selectedNodeIds.filter { it != primary }
    }

    private fun updateSelection(ids: Set<String>, primary: String?) {
        val normalized = LinkedHashSet(ids)
        val nextPrimary = primary?.takeIf { it in normalized } ?: normalized.lastOrNull()
        val changed = nextPrimary != primarySelectionId
        selectedNodeIds = normalized
        primarySelectionId = nextPrimary
        if (changed) onSelectionChanged?.invoke(nextPrimary)
    }

    fun dismissRemapNotices() {
        remapNotices = emptyList()
    }

    // ── Explicit Gesture Transactions ────────────────────────────────────────────────────────────

    fun beginGesture(label: String): Boolean {
        if (activeTx?.isActive == true) return true
        onBeforeBeginGesture?.invoke()
        val tx = store.begin(label) ?: return false
        activeTx = tx
        return true
    }

    fun commitGesture() {
        val tx = activeTx ?: return
        activeTx = null
        tx.commit()
    }

    fun cancelGesture(): Boolean {
        resizePreviewNodeId = null
        resizePreviewFrame = null
        val tx = activeTx
        if (tx != null && tx.isActive) {
            activeTx = null
            tx.cancel()
            return true
        }
        if (store.hasOpenTransaction) {
            store.cancelActiveTransaction()
            return true
        }
        return false
    }

    fun performUndo() {
        val external = onUndoRequest
        if (external != null) {
            external()
        } else {
            cancelGesture()
            store.undo()
        }
    }

    fun performRedo() {
        val external = onRedoRequest
        if (external != null) {
            external()
        } else {
            cancelGesture()
            store.redo()
        }
    }

    internal fun applyMutation(
        label: String,
        useActiveGesture: Boolean,
        transform: (DeckDocument) -> DeckDocument,
    ): Boolean {
        if (useActiveGesture) {
            val tx = activeTx ?: run {
                if (beginGesture(label)) activeTx else null
            }
            if (tx != null && tx.isActive) {
                tx.update(transform)
                return true
            }
        }
        return store.edit(label, transform)
    }

    // ── Beat-Aware Patching ──────────────────────────────────────────────────────────────────────

    /** Resolves `nodeId == null` to the whole multi-selection, otherwise just [nodeId]. */
    private fun targetsFor(nodeId: String?): List<String> =
        if (nodeId != null) listOf(nodeId) else editTargetIds()

    /**
     * Applies [patch] to [nodeId] (or every selected node when `null`) at the active beat, or to
     * all builds when [editAllBuilds] is on a beat > 0. One undo step.
     */
    fun applyPatchAtCurrentBeat(
        label: String,
        patch: JsonObject,
        nodeId: String? = null,
        useActiveGesture: Boolean = false,
    ): Boolean {
        val ids = targetsFor(nodeId)
        if (ids.isEmpty()) return false
        return applyPatchesToNodes(label, ids.associateWith { patch }, useActiveGesture)
    }

    /**
     * Applies a distinct patch per node in ONE document edit (one undo step), honouring the active
     * beat and the `All builds` toggle.
     */
    fun applyPatchesToNodes(
        label: String,
        patches: Map<String, JsonObject>,
        useActiveGesture: Boolean = false,
    ): Boolean {
        if (patches.isEmpty()) return false
        val slideId = activeSlide()?.id ?: return false
        val beat = activeBeat
        val allBuilds = editAllBuilds && beat > 0
        return applyMutation(label, useActiveGesture) { doc ->
            patches.entries.fold(doc) { acc, (id, patch) ->
                DocumentThemeResolver.updateNodeInSlide(acc, slideId, id) { node ->
                    when {
                        !isPatchCompatible(node, patch.keys) -> node
                        allBuilds -> JsonMergePatch.applyToAllBeats(node, patch)
                        else -> JsonMergePatch.applyEditAtBeat(node, beat, patch)
                    }
                }
            }
        }
    }

    /**
     * Multi-selection guard: a patch touching a payload key (`text`, `shape`, `specimen`, …) only
     * applies to nodes that already carry that payload, so mixed selections never gain a second payload.
     */
    private fun isPatchCompatible(node: NodeSpec, keys: Collection<String>): Boolean =
        keys.none { key -> key in PAYLOAD_KEYS && node.payloadKinds.none { it.payloadKey == key } }

    fun applyPatchToAllBeats(
        label: String,
        patch: JsonObject,
        nodeId: String? = null,
    ): Boolean {
        val ids = targetsFor(nodeId)
        if (ids.isEmpty()) return false
        val slideId = activeSlide()?.id ?: return false
        return applyMutation(label, useActiveGesture = false) { doc ->
            ids.fold(doc) { acc, id ->
                DocumentThemeResolver.updateNodeInSlide(acc, slideId, id) { node ->
                    if (isPatchCompatible(node, patch.keys)) JsonMergePatch.applyToAllBeats(node, patch) else node
                }
            }
        }
    }

    fun resetPropertyAtCurrentBeat(
        label: String,
        keyPath: List<String>,
        nodeId: String? = null,
        nodeIds: List<String>? = null,
    ): Boolean {
        val ids = nodeIds ?: targetsFor(nodeId)
        if (ids.isEmpty()) return false
        val slideId = activeSlide()?.id ?: return false
        val beat = activeBeat
        // `All builds` resets the property everywhere (base + every build), like every other edit.
        val allBuilds = editAllBuilds && beat > 0
        return applyMutation(label, useActiveGesture = false) { doc ->
            ids.fold(doc) { acc, id ->
                DocumentThemeResolver.updateNodeInSlide(acc, slideId, id) { node ->
                    when {
                        !isPatchCompatible(node, keyPath.take(1)) -> node
                        allBuilds -> JsonMergePatch.applyToAllBeats(node, JsonMergePatch.nestedPatch(keyPath, JsonNull))
                        else -> JsonMergePatch.resetAtBeat(node, beat, keyPath)
                    }
                }
            }
        }
    }

    private fun applyScoped(
        label: String,
        allBeatsLabel: String,
        patch: JsonObject,
        allBeats: Boolean,
        useActiveGesture: Boolean,
        nodeId: String? = null,
    ): Boolean = if (allBeats) {
        applyPatchToAllBeats(allBeatsLabel, patch, nodeId = nodeId)
    } else {
        applyPatchAtCurrentBeat(label, patch, nodeId = nodeId, useActiveGesture = useActiveGesture)
    }

    // ── Frame, Transform, Opacity, Rotation, Scale, Origin ───────────────────────────────────────

    fun setFrame(
        x: Double? = null,
        y: Double? = null,
        w: Double? = null,
        h: Double? = null,
        useActiveGesture: Boolean = false,
        allBeats: Boolean = false,
        nodeId: String? = null,
    ): Boolean {
        val framePatch = buildJsonObject {
            x?.let { put("x", snapHalfUnit(it)) }
            y?.let { put("y", snapHalfUnit(it)) }
            w?.let { put("w", snapHalfUnit(it).coerceAtLeast(1.0)) }
            h?.let { put("h", snapHalfUnit(it).coerceAtLeast(1.0)) }
        }
        if (framePatch.isEmpty()) return false
        val patch = buildJsonObject { put("frame", framePatch) }
        return applyScoped("Edit frame", "Apply frame to all beats", patch, allBeats, useActiveGesture, nodeId)
    }

    fun setOpacity(
        opacity: Double,
        useActiveGesture: Boolean = false,
        allBeats: Boolean = false,
    ): Boolean {
        val clamped = opacity.coerceIn(0.0, 1.0)
        val patch = buildJsonObject { put("opacity", clamped) }
        return applyScoped("Edit opacity", "Apply opacity to all beats", patch, allBeats, useActiveGesture)
    }

    fun setRotation(
        degrees: Double,
        useActiveGesture: Boolean = false,
        allBeats: Boolean = false,
    ): Boolean {
        val snapped = snapHalfUnit(degrees)
        val patch = buildJsonObject { put("rotation", snapped) }
        return applyScoped("Edit rotation", "Apply rotation to all beats", patch, allBeats, useActiveGesture)
    }

    fun setScale(
        scale: ScaleValue,
        useActiveGesture: Boolean = false,
        allBeats: Boolean = false,
    ): Boolean {
        val el: JsonElement = when (scale) {
            is ScaleValue.Uniform -> JsonPrimitive(scale.value.coerceIn(0.05, 10.0))
            is ScaleValue.NonUniform -> buildJsonObject {
                put("x", scale.x.coerceIn(0.05, 10.0))
                put("y", scale.y.coerceIn(0.05, 10.0))
            }
        }
        val patch = buildJsonObject { put("scale", el) }
        return applyScoped("Edit scale", "Apply scale to all beats", patch, allBeats, useActiveGesture)
    }

    fun setOrigin(
        x: Double,
        y: Double,
        useActiveGesture: Boolean = false,
        allBeats: Boolean = false,
    ): Boolean {
        val patch = buildJsonObject {
            put(
                "origin",
                buildJsonObject {
                    put("x", x.coerceIn(0.0, 1.0))
                    put("y", y.coerceIn(0.0, 1.0))
                },
            )
        }
        return applyScoped("Edit origin", "Apply origin to all beats", patch, allBeats, useActiveGesture)
    }

    // ── Fill, Stroke, Surface ────────────────────────────────────────────────────────────────────

    fun setShapeFill(
        colorTokenOrHex: String?,
        useActiveGesture: Boolean = false,
        allBeats: Boolean = false,
    ): Boolean {
        val value: JsonElement = if (colorTokenOrHex == null) JsonNull else JsonPrimitive(colorTokenOrHex)
        val patch = JsonMergePatch.nestedPatch(listOf("shape", "fill"), value)
        return applyScoped("Edit fill", "Apply fill to all beats", patch, allBeats, useActiveGesture)
    }

    fun setShapeStroke(
        stroke: StrokeSpec?,
        useActiveGesture: Boolean = false,
        allBeats: Boolean = false,
    ): Boolean {
        val el = strokeJson(stroke)
        val patch = JsonMergePatch.nestedPatch(listOf("shape", "stroke"), el)
        return applyScoped("Edit stroke", "Apply stroke to all beats", patch, allBeats, useActiveGesture)
    }

    fun setSurfaceFill(
        nodeKind: NodeKind,
        colorTokenOrHex: String?,
        useActiveGesture: Boolean = false,
        allBeats: Boolean = false,
    ): Boolean {
        val value: JsonElement = if (colorTokenOrHex == null) JsonNull else JsonPrimitive(colorTokenOrHex)
        val patch = JsonMergePatch.nestedPatch(listOf(nodeKind.payloadKey, "surface", "fill"), value)
        return applyScoped("Edit surface fill", "Apply surface fill to all beats", patch, allBeats, useActiveGesture)
    }

    fun setSurfaceStroke(
        nodeKind: NodeKind,
        stroke: StrokeSpec?,
        useActiveGesture: Boolean = false,
        allBeats: Boolean = false,
    ): Boolean {
        val el = strokeJson(stroke)
        val patch = JsonMergePatch.nestedPatch(listOf(nodeKind.payloadKey, "surface", "stroke"), el)
        return applyScoped("Edit surface stroke", "Apply surface stroke to all beats", patch, allBeats, useActiveGesture)
    }

    fun setSurfaceCornerRadius(
        nodeKind: NodeKind,
        radius: Double?,
        useActiveGesture: Boolean = false,
        allBeats: Boolean = false,
    ): Boolean {
        val el: JsonElement = if (radius == null) JsonNull else JsonPrimitive(radius.coerceAtLeast(0.0))
        val patch = JsonMergePatch.nestedPatch(listOf(nodeKind.payloadKey, "surface", "cornerRadius"), el)
        return applyScoped("Edit corner radius", "Apply corner radius to all beats", patch, allBeats, useActiveGesture)
    }

    fun setSurfacePadding(
        nodeKind: NodeKind,
        padding: PaddingSpec?,
        useActiveGesture: Boolean = false,
        allBeats: Boolean = false,
    ): Boolean {
        val el: JsonElement = when {
            padding == null -> JsonNull
            padding.isUniform -> JsonPrimitive(padding.left.coerceAtLeast(0.0))
            else -> buildJsonObject {
                put("left", padding.left.coerceAtLeast(0.0))
                put("top", padding.top.coerceAtLeast(0.0))
                put("right", padding.right.coerceAtLeast(0.0))
                put("bottom", padding.bottom.coerceAtLeast(0.0))
            }
        }
        val patch = JsonMergePatch.nestedPatch(listOf(nodeKind.payloadKey, "surface", "padding"), el)
        return applyScoped("Edit padding", "Apply padding to all beats", patch, allBeats, useActiveGesture)
    }

    private fun strokeJson(stroke: StrokeSpec?): JsonElement = stroke?.let {
        buildJsonObject {
            put("color", it.color)
            put("width", it.width.coerceAtLeast(0.0))
        }
    } ?: JsonNull

    // ── Text Properties & Provenance-Safe Text Edits ─────────────────────────────────────────────

    fun setTextProperty(
        field: String,
        value: JsonElement,
        useActiveGesture: Boolean = false,
        allBeats: Boolean = false,
    ): Boolean {
        val patch = JsonMergePatch.nestedPatch(listOf("text", field), value)
        return applyScoped("Edit text.$field", "Apply text.$field to all beats", patch, allBeats, useActiveGesture)
    }

    fun setTextMorphGranularity(granularity: TextMorphGranularity): Boolean {
        val el: JsonElement = if (granularity.morphBy == null) {
            JsonNull
        } else {
            val serial = when (granularity.morphBy) {
                MorphBy.Word -> "word"
                MorphBy.Character -> "character"
                MorphBy.Line -> "line"
                MorphBy.Paragraph -> "paragraph"
            }
            buildJsonObject { put("by", serial) }
        }
        return setTextProperty("morph", el)
    }

    fun applyWholeTextReplacement(newText: String, nodeId: String? = selectedNodeId): Boolean =
        applyTextEdits(nodeId) { doc, slideId, id, beat ->
            DeckStructureEdits.applyWholeTextReplacement(doc, slideId, id, beat, newText)
        }

    fun applyTextRangeEdit(edit: TextRangeEdit, nodeId: String? = selectedNodeId): Boolean =
        applyTextEdits(nodeId) { doc, slideId, id, beat ->
            DeckStructureEdits.applyTextRangeEdit(doc, slideId, id, beat, edit)
        }

    /** Runs provenance-safe text edits in ONE `store.edit`, collecting manual-delay remap notices. */
    private fun applyTextEdits(
        nodeId: String?,
        edit: (DeckDocument, String, String, Int) -> Pair<DeckDocument, List<ManualDelayRemapNotice>>,
    ): Boolean {
        val id = nodeId ?: return false
        val slideId = activeSlide()?.id ?: return false
        val beat = activeBeat
        var capturedNotices: List<ManualDelayRemapNotice> = emptyList()
        val allBuilds = editAllBuilds && beat > 0
        val ok = store.edit("Edit text") { doc ->
            val (updated, notices) = edit(doc, slideId, id, beat)
            if (allBuilds) {
                // The text produced on this build becomes the text of EVERY build. Re-run it as a
                // Base replacement so manual fragment delays on base + every build are remapped
                // through `FragmentProvenance` against the Base text (not just this build's text).
                val finalText = resolvedNodeText(updated, slideId, id, beat)
                if (finalText == null) {
                    capturedNotices = notices
                    updated
                } else {
                    val (rebased, baseNotices) = DeckStructureEdits.applyWholeTextReplacement(doc, slideId, id, 0, finalText)
                    capturedNotices = baseNotices
                    val baseValue = doc.slides.firstOrNull { it.id == slideId }
                        ?.let { DocumentThemeResolver.findNode(it.nodes, id) }?.text?.value
                    withTextOnAllBuilds(rebased, slideId, id, finalText, styleSource = baseValue)
                }
            } else {
                capturedNotices = notices
                updated
            }
        }
        if (ok && capturedNotices.isNotEmpty()) {
            remapNotices = remapNotices + capturedNotices
        }
        return ok
    }

    // ── Declared Specimen Parameters ─────────────────────────────────────────────────────────────

    fun setSpecimenFloatParam(
        spec: ParamSpec.FloatParam,
        rawValue: Float,
        useActiveGesture: Boolean = false,
        allBeats: Boolean = false,
    ): Boolean = setSpecimenParamRaw(spec.name, JsonPrimitive(steppedFloatParam(spec, rawValue).toDouble()), useActiveGesture, allBeats)

    fun setSpecimenIntParam(
        spec: ParamSpec.IntParam,
        rawValue: Int,
        useActiveGesture: Boolean = false,
        allBeats: Boolean = false,
    ): Boolean = setSpecimenParamRaw(spec.name, JsonPrimitive(steppedIntParam(spec, rawValue)), useActiveGesture, allBeats)

    fun setSpecimenBooleanParam(
        spec: ParamSpec.BooleanParam,
        value: Boolean,
        allBeats: Boolean = false,
    ): Boolean = setSpecimenParamRaw(spec.name, JsonPrimitive(value), useActiveGesture = false, allBeats = allBeats)

    fun setSpecimenColorParam(
        spec: ParamSpec.ColorParam,
        colorTokenOrHex: String,
        useActiveGesture: Boolean = false,
        allBeats: Boolean = false,
    ): Boolean = setSpecimenParamRaw(spec.name, JsonPrimitive(colorTokenOrHex), useActiveGesture, allBeats)

    fun setSpecimenChoiceParam(
        spec: ParamSpec.ChoiceParam,
        choice: String,
        allBeats: Boolean = false,
    ): Boolean = setSpecimenParamRaw(spec.name, JsonPrimitive(choice), useActiveGesture = false, allBeats = allBeats)

    fun setSpecimenStringParam(
        spec: ParamSpec.StringParam,
        value: String,
        allBeats: Boolean = false,
    ): Boolean = setSpecimenParamRaw(spec.name, JsonPrimitive(value), useActiveGesture = false, allBeats = allBeats)

    fun setSpecimenParamRaw(
        paramName: String,
        value: JsonElement,
        useActiveGesture: Boolean = false,
        allBeats: Boolean = false,
    ): Boolean {
        val patch = JsonMergePatch.nestedPatch(listOf("specimen", "params", paramName), value)
        // Only selected specimens of the SAME ref as the primary share a parameter edit; another
        // specimen type never gains a foreign `specimen.params.<name>`.
        val ids = specimenPeerIds()
        if (ids.isEmpty()) return false
        if (!allBeats) return applyPatchesToNodes("Edit specimen.$paramName", ids.associateWith { patch }, useActiveGesture)
        val slideId = activeSlide()?.id ?: return false
        return applyMutation("Apply specimen.$paramName to all beats", useActiveGesture = false) { doc ->
            ids.fold(doc) { acc, id -> DocumentThemeResolver.updateNodeInSlide(acc, slideId, id) { JsonMergePatch.applyToAllBeats(it, patch) } }
        }
    }

    /** Selected node ids whose `specimen.ref` matches the primary selection's ref (primary first). */
    internal fun specimenPeerIds(): List<String> {
        val slide = activeSlide() ?: return emptyList()
        val primaryRef = selectedBaseNode()?.specimen?.ref ?: return emptyList()
        return editTargetIds().filter { id -> DocumentThemeResolver.findNode(slide.nodes, id)?.specimen?.ref == primaryRef }
    }

    /** Resets a declared parameter on the selected specimens sharing the primary's `ref` only. */
    fun resetSpecimenParam(paramName: String): Boolean {
        val ids = specimenPeerIds()
        if (ids.isEmpty()) return false
        return resetPropertyAtCurrentBeat("Reset specimen.$paramName", listOf("specimen", "params", paramName), nodeIds = ids)
    }

    // ── Stage Direct Edit (Placement Drag & Inline Text Editor) ──────────────────────────────────

    fun updateStageDragPreview(nodeId: String, deltaX: Double, deltaY: Double) {
        dragPreviewNodeId = nodeId
        if (nodeId !in dragPreviewNodeIds) dragPreviewNodeIds = setOf(nodeId)
        dragPreviewDeltaX = deltaX
        dragPreviewDeltaY = deltaY
    }

    /**
     * Starts a shared multi-node drag preview: grabbing any selected node moves every selected
     * node; grabbing an unselected node moves just that node.
     */
    fun beginMultiDrag(grabbedNodeId: String) {
        val moving = if (grabbedNodeId in selectedNodeIds && selectedNodeIds.size > 1) {
            // Only nodes present on the current build travel with the drag.
            LinkedHashSet(DocumentElementArrangeOps.presentTargetIds(this).ifEmpty { listOf(grabbedNodeId) })
        } else {
            setOf(grabbedNodeId)
        }
        dragPreviewNodeIds = moving
        dragPreviewNodeId = grabbedNodeId
        dragPreviewDeltaX = 0.0
        dragPreviewDeltaY = 0.0
    }

    fun updateMultiDrag(deltaX: Double, deltaY: Double) {
        val grabbed = dragPreviewNodeId ?: return
        updateStageDragPreview(grabbed, deltaX, deltaY)
    }

    /** Commits the shared multi-node drag in ONE atomic undo step. */
    fun commitMultiDrag(deltaX: Double, deltaY: Double): Boolean {
        val moving = dragPreviewNodeIds.ifEmpty { setOfNotNull(dragPreviewNodeId) }
        cancelStageDragPreview()
        if (moving.isEmpty() || (abs(deltaX) < 0.01 && abs(deltaY) < 0.01)) return false
        val label = if (moving.size == 1) "Move ${displayNameOf(moving.first())}" else "Move ${moving.size} elements"
        return DocumentElementArrangeOps.moveNodesBy(this, moving.associateWith { Offset(deltaX.toFloat(), deltaY.toFloat()) }, label)
    }

    fun cancelStageDragPreview() {
        dragPreviewNodeId = null
        dragPreviewNodeIds = emptySet()
        dragPreviewDeltaX = 0.0
        dragPreviewDeltaY = 0.0
    }

    // ── Multi-Node Nudge, Align & Distribute (one undo step each) ────────────────────────────────

    /** Nudges every selected node by `(dx, dy)` stage units at the active beat (one undo step). */
    fun nudgeSelectedNodes(dx: Double, dy: Double): Boolean =
        DocumentElementArrangeOps.nudgeSelected(this, dx, dy)

    /** Aligns the selection to its bounding box (2+ nodes) or to the stage (1 node). */
    fun alignSelectedNodes(alignment: NodeAlignment): Boolean =
        DocumentElementArrangeOps.alignSelected(this, alignment)

    /** Evenly distributes 3+ selected nodes along [axis], pinning the outermost ones. */
    fun distributeSelectedNodes(axis: DistributeAxis): Boolean =
        DocumentElementArrangeOps.distributeSelected(this, axis)

    /** Stage-unit bounds of [nodeId] at the active beat (explicit frame, falling back to the live layout). */
    fun nodeStageBounds(nodeId: String): Rect? = DocumentElementArrangeOps.nodeStageBounds(this, nodeId)

    /** Frame at the start of the active single-node resize (deltas for `All builds` mode). */
    private var resizeStartFrame: FrameSpec? = null

    /** Begins an interactive 8-handle resize transaction (`store.begin`/`update`/`commit`, 1 undo step). */
    fun beginResizeGesture(nodeId: String, initialFrame: FrameSpec): Boolean {
        if (store.isReadOnly) return false
        if (nodeId !in selectedNodeIds) selectedNodeId = nodeId
        if (!beginGesture("Resize ${displayNameOf(nodeId)}")) return false
        resizePreviewNodeId = nodeId
        resizePreviewFrame = initialFrame
        resizeStartFrame = initialFrame
        return true
    }

    /** Updates the active resize transaction snapped to `0.5` stage units so stage content reflows live. */
    fun updateResizeGesture(nodeId: String, nextFrame: FrameSpec): Boolean {
        val snapped = FrameSpec(
            x = nextFrame.x?.let(::snapHalfUnit),
            y = nextFrame.y?.let(::snapHalfUnit),
            w = nextFrame.w?.let { snapHalfUnit(it).coerceAtLeast(16.0) },
            h = nextFrame.h?.let { snapHalfUnit(it).coerceAtLeast(16.0) },
        )
        resizePreviewNodeId = nodeId
        resizePreviewFrame = snapped
        val start = resizeStartFrame
        val slideId = activeSlide()?.id
        if (editAllBuilds && activeBeat > 0 && start != null && slideId != null) {
            // Relative change on base + every build (like moves and union resizes), never stamping
            // this build's absolute frame onto Beat 0. Re-applied to the transaction's start snapshot.
            val sx = start.x ?: 0.0
            val sy = start.y ?: 0.0
            val sw = start.w ?: 0.0
            val sh = start.h ?: 0.0
            val delta = DocumentElementArrangeOps.FrameDelta(
                dx = (snapped.x ?: sx) - sx,
                dy = (snapped.y ?: sy) - sy,
                dw = (snapped.w ?: sw) - sw,
                dh = (snapped.h ?: sh) - sh,
            )
            val startRect = Rect(sx.toFloat(), sy.toFloat(), (sx + sw).toFloat(), (sy + sh).toFloat())
            return applyMutation("Resize ${displayNameOf(nodeId)}", useActiveGesture = true) { doc ->
                DocumentThemeResolver.updateNodeInSlide(doc, slideId, nodeId) {
                    DocumentElementArrangeOps.offsetFrameAcrossAllBuilds(it, delta, startRect)
                }
            }
        }
        return setFrame(
            x = snapped.x,
            y = snapped.y,
            w = snapped.w,
            h = snapped.h,
            useActiveGesture = true,
            nodeId = nodeId,
        )
    }

    fun commitResizeGesture() {
        resizePreviewNodeId = null
        resizePreviewFrame = null
        resizeStartFrame = null
        commitGesture()
    }

    fun cancelResizeGesture() {
        resizePreviewNodeId = null
        resizePreviewFrame = null
        resizeStartFrame = null
        cancelGesture()
    }

    /** Plain display name for undo labels (`Move “Hello stage world”`), never the raw node id. */
    internal fun displayNameOf(nodeId: String): String {
        val node = activeSlide()?.let { DocumentThemeResolver.findNode(it.nodes, nodeId) } ?: return nodeId
        return InspectorDisplayNames.nodeDisplayName(JsonMergePatch.resolveNodeAtBeat(node, activeBeat))
    }

    /** Begins a proportional resize of the whole multi-selection from its union handles (1 undo step). */
    fun beginGroupResizeGesture(nodeCount: Int): Boolean {
        if (store.isReadOnly || nodeCount < 2) return false
        return beginGesture("Resize $nodeCount elements")
    }

    /** Resizes every node in [startRects] proportionally so their union becomes [nextUnion]. */
    fun updateGroupResizeGesture(startUnion: Rect, nextUnion: Rect, startRects: Map<String, Rect>): Boolean =
        DocumentElementArrangeOps.resizeGroupTo(this, startUnion, nextUnion, startRects)

    /**
     * Commits a stage drag release as ONE `store.edit` snapping `frame.x` / `frame.y` to `0.5`
     * stage units.
     */
    fun commitStageDrag(nodeId: String, deltaX: Double, deltaY: Double): Boolean {
        cancelStageDragPreview()
        if (abs(deltaX) < 0.01 && abs(deltaY) < 0.01) return false
        return DocumentElementArrangeOps.moveNodesBy(
            controller = this,
            deltas = mapOf(nodeId to Offset(deltaX.toFloat(), deltaY.toFloat())),
            label = "Move ${displayNameOf(nodeId)}",
        )
    }

    fun startInlineTextEdit(nodeId: String): Boolean {
        if (store.isReadOnly) return false
        val slide = activeSlide() ?: return false
        val base = DocumentThemeResolver.findNode(slide.nodes, nodeId) ?: return false
        val resolved = JsonMergePatch.resolveNodeAtBeat(base, activeBeat)
        val text = resolved.text?.value?.plainText ?: return false
        selectedNodeId = nodeId
        inlineEditingNodeId = nodeId
        inlineOriginalText = text
        textInputHasFocus = true
        return true
    }

    /**
     * Commits the active inline text edit using explicit UTF-16 range provenance `(start, removedLength, insertedText)`
     * so unchanged fragments keep their `stagger.manual` entries and ambiguous entries emit notices.
     */
    fun commitInlineTextEdit(
        newText: String,
        explicitRangeEdit: TextRangeEdit? = null,
    ): Boolean {
        val id = inlineEditingNodeId ?: return false
        val oldText = inlineOriginalText
        inlineEditingNodeId = null
        inlineOriginalText = ""
        textInputHasFocus = false

        val rangeEdit = explicitRangeEdit ?: DocumentThemeResolver.computeTextRangeEdit(oldText, newText)
            ?: return false
        return applyTextRangeEdit(rangeEdit, nodeId = id)
    }

    /**
     * Commits a sequence of real captured per-edit UTF-16 range edits `(start, removedLength, insertedText)`
     * from the inline text editor in ONE unified `store.edit` step.
     *
     * Verifies that replaying [capturedEdits] against [inlineOriginalText] reproduces [finalText]
     * (covering any local `⌘Z` inside `TextFieldState`); if not, falls back to [DocumentThemeResolver.computeTextRangeEdit].
     */
    fun commitInlineTextEdits(
        capturedEdits: List<TextRangeEdit>,
        finalText: String,
    ): Boolean {
        val id = inlineEditingNodeId ?: return false
        val oldText = inlineOriginalText
        inlineEditingNodeId = null
        inlineOriginalText = ""
        textInputHasFocus = false

        if (oldText == finalText) return false

        val coalesced = coalesceTextRangeEdits(capturedEdits)
        val editsToApply = if (coalesced.isNotEmpty() && replayTextRangeEdits(oldText, coalesced) == finalText) {
            coalesced
        } else {
            listOfNotNull(DocumentThemeResolver.computeTextRangeEdit(oldText, finalText))
        }
        if (editsToApply.isEmpty()) return false

        return applyTextEdits(id) { doc, slideId, nodeId, beat ->
            val collectedNotices = mutableListOf<ManualDelayRemapNotice>()
            var current = doc
            for (edit in editsToApply) {
                val (next, notices) = DeckStructureEdits.applyTextRangeEdit(current, slideId, nodeId, beat, edit)
                current = next
                collectedNotices += notices
            }
            current to collectedNotices
        }
    }

    fun cancelInlineTextEdit() {
        inlineEditingNodeId = null
        inlineOriginalText = ""
        textInputHasFocus = false
    }

    /**
     * Routes keyboard shortcuts while respecting focus discipline (see [DocumentElementInspectorKeys]):
     * arrow nudging (`1` / `Alt` or `Shift` `10` / `Alt+Shift` `25` stage units), `Cmd+A`, `Esc`,
     * and `⌘Z` / `⇧⌘Z` (never intercepted while a text input has focus).
     *
     * With [externalInputActive] (a focused authoring text field or a
     * keyboard-captured interactive specimen) arrows, `⌘A` and every other shortcut are yielded.
     */
    fun handleKeyEvent(ev: KeyEvent, externalInputActive: Boolean = false): Boolean =
        if (externalInputActive) false else DocumentElementInspectorKeys.handleKeyEvent(this, ev)

    /** Alias of [handleKeyEvent] for stage-level key routing. */
    fun handleStageKeyEvent(ev: KeyEvent): Boolean = handleKeyEvent(ev)

    /** Arrow-key nudging of the selection; consumes the key so nudging never advances slides. */
    fun handleStageNudgeKeyEvent(ev: KeyEvent): Boolean = DocumentElementInspectorKeys.handleNudge(this, ev)

    fun handleShortcut(
        key: Key,
        isCommandDown: Boolean = false,
        isShiftDown: Boolean = false,
    ): Boolean = DocumentElementInspectorKeys.handleShortcut(this, key, isCommandDown, isShiftDown)
}

private val PAYLOAD_KEYS: Set<String> = NodeKind.entries.mapTo(HashSet()) { it.payloadKey }
