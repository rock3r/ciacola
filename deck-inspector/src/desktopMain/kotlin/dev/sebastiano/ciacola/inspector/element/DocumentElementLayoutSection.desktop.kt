package dev.sebastiano.ciacola.inspector.element

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.sebastiano.ciacola.document.Build
import dev.sebastiano.ciacola.document.JsonMergePatch
import dev.sebastiano.ciacola.document.NodeSpec
import dev.sebastiano.ciacola.document.ScaleValue
import dev.sebastiano.ciacola.inspector.design.FlatCollapsibleSection
import dev.sebastiano.ciacola.inspector.design.InspectorSegmented
import dev.sebastiano.ciacola.inspector.design.InspectorTokens
import dev.sebastiano.ciacola.inspector.design.InspectorTooltip
import dev.sebastiano.ciacola.inspector.design.inspectorBodyStyle
import dev.sebastiano.ciacola.inspector.design.inspectorSecondaryTextColor
import dev.sebastiano.ciacola.inspector.document.DocumentThemeResolver
import dev.sebastiano.ciacola.inspector.document.snapHalfUnit
import kotlin.math.roundToInt
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Checkbox
import org.jetbrains.jewel.ui.component.IconButton
import org.jetbrains.jewel.ui.component.Text

/** A node being edited by the Design inspector: its base spec and its resolved spec at the active beat. */
internal data class EditTarget(val id: String, val base: NodeSpec, val resolved: NodeSpec)

/** Resolves every node in the multi-selection (primary first) at the active beat. */
internal fun DocumentElementInspectorController.editTargets(): List<EditTarget> {
    val slide = activeSlide() ?: return emptyList()
    return editTargetIds().mapNotNull { id ->
        val base = DocumentThemeResolver.findNode(slide.nodes, id) ?: return@mapNotNull null
        EditTarget(id, base, JsonMergePatch.resolveNodeAtBeat(base, activeBeat))
    }
}

/** Whether a `change` build on [beat] explicitly sets [keyPath] to a non-null value. */
internal fun isKeyPatchedAtBeat(base: NodeSpec, beat: Int, keyPath: List<String>): Boolean =
    base.builds.any { build ->
        build is Build.Change && build.beat == beat && jsonPathValue(build.set, keyPath).let { it != null && it !is JsonNull }
    }

private fun jsonPathValue(obj: JsonObject, keyPath: List<String>): JsonElement? {
    var cursor: JsonElement = obj
    for (key in keyPath) {
        cursor = (cursor as? JsonObject)?.get(key) ?: return null
    }
    return cursor
}

/** Property definition for one compact numeric grid field. */
internal class GridProperty(
    val label: String,
    val tag: String,
    val undoLabel: String,
    val keyPath: List<String>,
    val labelWidth: Dp = 18.dp,
    val step: Double = 1.0,
    val tooltip: String? = null,
    val format: (Double) -> String = { formatCompactNumber(it) },
    val resetAtBase: Boolean = true,
    val defaultValue: Double? = null,
    val read: (DocumentElementInspectorController, EditTarget) -> Double?,
    val write: (Double) -> JsonObject,
    /** Per-target patch (preserving each node's other values); falls back to [write] when `null`. */
    val writeFor: ((DocumentElementInspectorController, Double, EditTarget) -> JsonObject)? = null,
    /** Whole-selection patch builder when targets can share ancestor/descendant coordinate frames. */
    val writeBatch: ((DocumentElementInspectorController, Map<String, Double>) -> Map<String, JsonObject>)? = null,
)

internal fun GridProperty.patchFor(
    controller: DocumentElementInspectorController,
    value: Double,
    target: EditTarget,
): JsonObject = writeFor?.invoke(controller, value, target) ?: write(value)

private fun framePatch(key: String, value: Double, minimum: Double? = null): JsonObject = buildJsonObject {
    put("frame", buildJsonObject { put(key, snapHalfUnit(minimum?.let { value.coerceAtLeast(it) } ?: value)) })
}

private fun bounds(c: DocumentElementInspectorController, t: EditTarget) = c.nodeStageBounds(t.id)

internal val LayoutXProperty = GridProperty(
    label = "X", tag = "ciacola-doc-frame-x", undoLabel = "Edit X", keyPath = listOf("frame", "x"), resetAtBase = false,
    tooltip = "Horizontal position (stage units). Drag to scrub: Shift ×10, Alt ×0.1",
    read = { c, t -> bounds(c, t)?.left?.toDouble() },
    write = { framePatch("x", it) },
    writeFor = { c, v, t -> DocumentElementArrangeOps.stagePositionPatch(c, t.id, stageX = v) },
    writeBatch = { c, values -> DocumentElementArrangeOps.stagePositionPatches(c, stageXById = values) },
)
internal val LayoutYProperty = GridProperty(
    label = "Y", tag = "ciacola-doc-frame-y", undoLabel = "Edit Y", keyPath = listOf("frame", "y"), resetAtBase = false,
    tooltip = "Vertical position (stage units). Drag to scrub: Shift ×10, Alt ×0.1",
    read = { c, t -> bounds(c, t)?.top?.toDouble() },
    write = { framePatch("y", it) },
    writeFor = { c, v, t -> DocumentElementArrangeOps.stagePositionPatch(c, t.id, stageY = v) },
    writeBatch = { c, values -> DocumentElementArrangeOps.stagePositionPatches(c, stageYById = values) },
)
internal val LayoutWProperty = GridProperty(
    label = "W", tag = "ciacola-doc-frame-w", undoLabel = "Edit width", keyPath = listOf("frame", "w"), resetAtBase = false,
    tooltip = "Width (stage units)",
    read = { c, t -> bounds(c, t)?.width?.toDouble() }, write = { framePatch("w", it, minimum = 1.0) },
)
internal val LayoutHProperty = GridProperty(
    label = "H", tag = "ciacola-doc-frame-h", undoLabel = "Edit height", keyPath = listOf("frame", "h"), resetAtBase = false,
    tooltip = "Height (stage units)",
    read = { c, t -> bounds(c, t)?.height?.toDouble() }, write = { framePatch("h", it, minimum = 1.0) },
)
internal val LayoutRotationProperty = GridProperty(
    label = "↻", tag = "ciacola-doc-rotation", undoLabel = "Edit rotation", keyPath = listOf("rotation"),
    tooltip = "Rotation in degrees", defaultValue = 0.0, format = { formatCompactNumber(it) + "°" },
    read = { _, t -> t.resolved.rotation }, write = { v -> buildJsonObject { put("rotation", snapHalfUnit(v)) } },
)
internal val LayoutScaleProperty = GridProperty(
    label = "⤢", tag = "ciacola-doc-scale", undoLabel = "Edit scale", keyPath = listOf("scale"),
    tooltip = "Uniform scale in percent", defaultValue = 100.0, format = { formatCompactNumber(it, 0) + "%" },
    read = { _, t -> (t.resolved.scale ?: ScaleValue.Identity).scaleX * 100.0 },
    write = { v -> buildJsonObject { put("scale", (v / 100.0).coerceIn(0.05, 10.0)) } },
)

/**
 * Writes absolute [value] to every target in ONE undo step (typed entry), or `start + delta`
 * per target (scrub / arrow step) so mixed selections keep their relative offsets.
 */
internal class GridPropertyEditor(
    private val controller: DocumentElementInspectorController,
    var property: GridProperty,
    /** Refreshed on every recomposition; scrub start values survive document updates mid-gesture. */
    var targets: List<EditTarget>,
) {
    private var scrubStart: Map<String, Double> = emptyMap()

    private fun current(): Map<String, Double> =
        targets.mapNotNull { t -> property.read(controller, t)?.let { t.id to it } }.toMap()

    private fun target(id: String): EditTarget? = targets.firstOrNull { it.id == id }

    private fun patchesFor(desired: Map<String, Double>): Map<String, JsonObject> {
        property.writeBatch?.let { return it(controller, desired) }
        return desired.mapNotNull { (id, v) -> target(id)?.let { id to property.patchFor(controller, v, it) } }.toMap()
    }

    private fun patchesFrom(values: Map<String, Double>, delta: Double): Map<String, JsonObject> =
        patchesFor(values.mapValues { (_, v) -> v + delta })

    fun commit(value: Double) {
        controller.applyPatchesToNodes(
            property.undoLabel,
            patchesFor(targets.associate { it.id to value }),
        )
    }

    fun step(delta: Double) {
        controller.applyPatchesToNodes(property.undoLabel, patchesFrom(current(), delta))
    }

    fun scrubStart() {
        scrubStart = current()
        controller.beginGesture(property.undoLabel)
    }

    fun scrub(total: Double) {
        if (scrubStart.isEmpty()) return
        controller.applyPatchesToNodes(
            property.undoLabel,
            patchesFrom(scrubStart, total),
            useActiveGesture = true,
        )
    }

    fun scrubEnd() {
        scrubStart = emptyMap()
        controller.commitGesture()
    }

    fun reset() {
        controller.resetPropertyAtCurrentBeat("Reset ${property.label}", property.keyPath)
    }

    fun isModified(): Boolean {
        val beat = controller.activeBeat
        return targets.any { t ->
            if (beat > 0) {
                isKeyPatchedAtBeat(t.base, beat, property.keyPath)
            } else {
                val default = property.defaultValue ?: return@any false
                val v = property.read(controller, t) ?: return@any false
                kotlin.math.abs(v - default) > 1e-6
            }
        }
    }

    fun sharedValue(): Double? = sharedValueOrMixed(targets.mapNotNull { property.read(controller, it) })
}

@Composable
internal fun GridPropertyField(
    controller: DocumentElementInspectorController,
    property: GridProperty,
    targets: List<EditTarget>,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tooltipOverride: String? = null,
) {
    val editor = remember(property.tag, controller) { GridPropertyEditor(controller, property, targets) }
    editor.targets = targets
    editor.property = property
    val effectiveEnabled = enabled && !controller.store.isReadOnly
    val canReset = effectiveEnabled && (controller.activeBeat > 0 || property.resetAtBase)
    NumericPropertyField(
        label = property.label,
        value = editor.sharedValue(),
        tag = property.tag,
        format = property.format,
        onCommit = editor::commit,
        onStep = editor::step,
        onScrubStart = editor::scrubStart,
        onScrub = editor::scrub,
        onScrubEnd = editor::scrubEnd,
        modifier = modifier,
        labelWidth = property.labelWidth,
        step = property.step,
        modified = canReset && editor.isModified(),
        onReset = if (canReset) editor::reset else null,
        onFocusChanged = { controller.textInputHasFocus = it },
        tooltip = tooltipOverride ?: property.tooltip,
        enabled = effectiveEnabled,
    )
}

/** `This build | All builds` toggle shown in the Layout section header on beats > 0. */
@Composable
internal fun ApplyScopeToggle(controller: DocumentElementInspectorController) {
    if (controller.activeBeat <= 0) return
    InspectorSegmented(
        options = listOf(false, true),
        selected = controller.editAllBuilds,
        label = { if (it) "All builds" else "This build" },
        testTag = { if (it) "ciacola-doc-apply-scope-all" else "ciacola-doc-apply-scope-this" },
        tooltip = {
            if (it) "Edits change the value on every build of this slide" else "Edits only change Build ${controller.activeBeat}"
        },
        onSelect = { controller.editAllBuilds = it },
        modifier = Modifier.width(150.dp),
    )
}

/**
 * Compact `Layout` section: X/Y/W/H, rotation and scale fields, an opacity slider row, a 3×3
 * origin grid, and the Align & Distribute toolbar.
 */
@Composable
internal fun DocumentLayoutSection(
    controller: DocumentElementInspectorController,
    targets: List<EditTarget>,
) {
    if (targets.isEmpty()) return
    // Uniform vs non-uniform is decided across the WHOLE selection, not just the primary node.
    val nonUniform = targets.any { it.resolved.scale is ScaleValue.NonUniform }
    val anyPositionable = targets.any { !DocumentElementArrangeOps.isFlowChild(controller, it.id) }
    val flowPositionTooltip = if (anyPositionable) null else "Position is controlled by the parent row or column"
    FlatCollapsibleSection(
        title = "Layout",
        testTag = "ciacola-doc-inspector-frame-section",
        headerTrailing = { ApplyScopeToggle(controller) },
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GridPropertyField(controller, LayoutXProperty, targets, Modifier.weight(1f), enabled = anyPositionable, tooltipOverride = flowPositionTooltip)
            GridPropertyField(controller, LayoutYProperty, targets, Modifier.weight(1f), enabled = anyPositionable, tooltipOverride = flowPositionTooltip)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GridPropertyField(controller, LayoutWProperty, targets, Modifier.weight(1f))
            GridPropertyField(controller, LayoutHProperty, targets, Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GridPropertyField(controller, LayoutRotationProperty, targets, Modifier.weight(1f))
            if (!nonUniform) {
                GridPropertyField(controller, LayoutScaleProperty, targets, Modifier.weight(1f))
            } else {
                Spacer(Modifier.weight(1f))
            }
        }
        OpacityRow(controller, targets)
        OriginAndScaleRow(controller, targets, nonUniform)
        AlignDistributeToolbar(
            controller,
            positionedCount = targets.count {
                !DocumentElementArrangeOps.isFlowChild(controller, it.id) && controller.nodeStageBounds(it.id) != null
            },
        )
    }
}

@Composable
private fun OpacityRow(controller: DocumentElementInspectorController, targets: List<EditTarget>) {
    val values = targets.map { it.resolved.opacity }
    val shared = sharedValueOrMixed(values)
    val beat = controller.activeBeat
    val modified = targets.any { t -> if (beat > 0) isKeyPatchedAtBeat(t.base, beat, listOf("opacity")) else t.resolved.opacity < 0.999 }
    CompactSliderRow(
        label = "Opacity",
        value = (shared ?: values.first()).toFloat(),
        valueRange = 0f..1f,
        valueText = shared?.let { "${(it * 100).roundToInt()}%" } ?: MIXED_VALUE_PLACEHOLDER,
        tagPrefix = "doc-opacity",
        modified = modified,
        onReset = { controller.resetPropertyAtCurrentBeat("Reset opacity", listOf("opacity")) },
        onGestureStart = { controller.beginGesture("Adjust opacity") },
        onChange = { v ->
            val snapped = (v * 100f).roundToInt() / 100.0
            controller.applyPatchAtCurrentBeat("Edit opacity", buildJsonObject { put("opacity", snapped) }, useActiveGesture = true)
        },
        onGestureEnd = { controller.commitGesture() },
    )
}

@Composable
private fun OriginAndScaleRow(
    controller: DocumentElementInspectorController,
    targets: List<EditTarget>,
    nonUniform: Boolean,
) {
    val origin = targets.first().resolved.origin
    val colors = JewelTheme.globalColors
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Origin", style = inspectorBodyStyle(), color = inspectorSecondaryTextColor())
            AnchorGrid(
                originX = origin?.x ?: 0.5,
                originY = origin?.y ?: 0.5,
                tag = "ciacola-doc-origin-grid",
                onPick = { x, y ->
                    controller.applyPatchAtCurrentBeat(
                        "Edit origin",
                        buildJsonObject { put("origin", buildJsonObject { put("x", x); put("y", y) }) },
                    )
                },
            )
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(InspectorTokens.RowGap)) {
            Row(
                modifier = Modifier.testTag("ciacola-doc-scale-non-uniform"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Checkbox(
                    checked = nonUniform,
                    onCheckedChange = { separate ->
                        // Each node converts its OWN scale (never the primary's baked into all).
                        controller.applyPatchesToNodes("Edit scale", targets.associate { it.id to scaleModePatch(it, separate) })
                    },
                )
                Text("Scale X/Y separately", style = inspectorBodyStyle(), color = colors.text.normal)
            }
            if (nonUniform) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GridPropertyField(controller, NonUniformScaleXProperty, targets, Modifier.weight(1f))
                    GridPropertyField(controller, NonUniformScaleYProperty, targets, Modifier.weight(1f))
                }
            }
        }
    }
}

/** `Scale X/Y separately` toggle patch for one node, converting that node's own scale. */
internal fun scaleModePatch(target: EditTarget, separate: Boolean): JsonObject {
    val own = target.resolved.scale ?: ScaleValue.Identity
    val next: JsonElement = if (separate) {
        buildJsonObject { put("x", own.scaleX); put("y", own.scaleY) }
    } else {
        JsonPrimitive(own.scaleX)
    }
    return buildJsonObject { put("scale", next) }
}

private fun nonUniformScaleProperty(isX: Boolean) = GridProperty(
    label = if (isX) "SX" else "SY",
    tag = if (isX) "ciacola-doc-scale-x" else "ciacola-doc-scale-y",
    undoLabel = "Edit scale",
    keyPath = listOf("scale"),
    labelWidth = 20.dp,
    defaultValue = 100.0,
    format = { formatCompactNumber(it, 0) + "%" },
    read = { _, t -> (t.resolved.scale ?: ScaleValue.Identity).let { if (isX) it.scaleX else it.scaleY } * 100.0 },
    write = { v -> buildJsonObject { put("scale", (v / 100.0).coerceIn(0.05, 10.0)) } },
    // Editing SX keeps each node's own SY (and vice versa).
    writeFor = { _, v, t -> nonUniformScalePatch(isX, v, t) },
)

/** SX/SY edit for one node: the edited axis takes [percent], the other keeps the node's own value. */
internal fun nonUniformScalePatch(isX: Boolean, percent: Double, target: EditTarget): JsonObject {
    val own = target.resolved.scale ?: ScaleValue.Identity
    val mine = (percent / 100.0).coerceIn(0.05, 10.0)
    return buildJsonObject {
        put("scale", buildJsonObject { put("x", if (isX) mine else own.scaleX); put("y", if (isX) own.scaleY else mine) })
    }
}

internal val NonUniformScaleXProperty = nonUniformScaleProperty(isX = true)
internal val NonUniformScaleYProperty = nonUniformScaleProperty(isX = false)

/** Align (6) and Distribute (2) icon toolbar with clear tooltips. */
@Composable
internal fun AlignDistributeToolbar(controller: DocumentElementInspectorController, positionedCount: Int) {
    val colors = JewelTheme.globalColors
    val toSlide = positionedCount == 1
    Row(
        modifier = Modifier.fillMaxWidth().height(InspectorTokens.RowHeight).testTag("ciacola-doc-align-toolbar"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        for (alignment in NodeAlignment.entries) {
            ToolbarGlyphButton(
                glyph = alignment.glyph(),
                tooltip = alignment.label + if (toSlide) " to the slide" else " of the selection",
                tag = "ciacola-doc-align-${alignment.name.lowercase()}",
                enabled = positionedCount >= 1 && !controller.store.isReadOnly,
                onClick = { controller.alignSelectedNodes(alignment) },
            )
        }
        Spacer(Modifier.width(6.dp))
        Box(Modifier.width(1.dp).height(14.dp).background(colors.borders.normal))
        Spacer(Modifier.width(6.dp))
        for (axis in DistributeAxis.entries) {
            val enabled = positionedCount >= 3 && !controller.store.isReadOnly
            ToolbarGlyphButton(
                glyph = if (axis == DistributeAxis.Horizontal) InspectorGlyph.DistributeH else InspectorGlyph.DistributeV,
                tooltip = if (enabled) axis.label else "${axis.label} (select 3 or more elements)",
                tag = "ciacola-doc-distribute-${axis.name.lowercase()}",
                enabled = enabled,
                onClick = { controller.distributeSelectedNodes(axis) },
            )
        }
        Spacer(Modifier.weight(1f))
        if (positionedCount > 1) {
            Text("$positionedCount selected", style = inspectorBodyStyle(), color = inspectorSecondaryTextColor())
        }
    }
}

@Composable
internal fun ToolbarGlyphButton(
    glyph: InspectorGlyph,
    tooltip: String,
    tag: String,
    enabled: Boolean,
    selected: Boolean = false,
    onClick: () -> Unit,
) {
    val colors = JewelTheme.globalColors
    InspectorTooltip(tooltip) {
        IconButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.size(InspectorTokens.RowHeight).testTag(tag),
        ) {
            InspectorGlyphIcon(
                glyph = glyph,
                tint = when {
                    !enabled -> colors.text.disabled
                    selected -> dev.sebastiano.ciacola.inspector.design.inspectorAccent()
                    else -> colors.text.normal
                },
            )
        }
    }
}
