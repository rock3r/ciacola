package dev.sebastiano.ciacola.inspector.element

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.sebastiano.ciacola.document.Fill
import dev.sebastiano.ciacola.document.NodeKind
import dev.sebastiano.ciacola.document.NodeSpec
import dev.sebastiano.ciacola.document.PaddingSpec
import dev.sebastiano.ciacola.document.ParamResolver
import dev.sebastiano.ciacola.document.ParamSpec
import dev.sebastiano.ciacola.document.SpecimenDeclaration
import dev.sebastiano.ciacola.document.StrokeSpec
import dev.sebastiano.ciacola.inspector.design.FlatCollapsibleSection
import dev.sebastiano.ciacola.inspector.design.InspectorSegmented
import dev.sebastiano.ciacola.inspector.design.InspectorTokens
import dev.sebastiano.ciacola.inspector.design.humanizeTokenKey
import dev.sebastiano.ciacola.inspector.design.inspectorBodyStyle
import dev.sebastiano.ciacola.inspector.design.inspectorSecondaryTextColor
import dev.sebastiano.ciacola.inspector.design.toHexString
import dev.sebastiano.ciacola.inspector.document.DOCUMENT_TYPOGRAPHY_ROLES
import dev.sebastiano.ciacola.inspector.document.DocumentThemeResolver
import dev.sebastiano.ciacola.ui.trackAuthoringTextInputFocus
import kotlin.math.roundToInt
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Checkbox
import org.jetbrains.jewel.ui.component.Dropdown
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextArea
import org.jetbrains.jewel.ui.component.TextField

/** Text alignment values written to `text.align`, with their readable labels and glyphs. */
internal enum class TextAlignOption(val value: String, val label: String, val glyph: InspectorGlyph) {
    Left("start", "Align left", InspectorGlyph.TextLeft),
    Center("center", "Align center", InspectorGlyph.TextCenter),
    Right("end", "Align right", InspectorGlyph.TextRight),
}

/** Labelled 24dp row: 72dp caption column + flexible control. */
@Composable
internal fun InspectorLabeledRow(label: String, content: @Composable () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().height(InspectorTokens.RowHeight),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        ModifiedDot(modified = false, label = label, tag = "", onReset = null)
        Text(label, style = inspectorBodyStyle(), color = inspectorSecondaryTextColor(), modifier = Modifier.width(72.dp), maxLines = 1)
        Row(Modifier.weight(1f)) { content() }
    }
}

private fun textProperty(
    label: String,
    tag: String,
    field: String,
    step: Double,
    decimals: Int,
    read: (EditTarget) -> Double?,
) = GridProperty(
    label = label,
    tag = tag,
    undoLabel = "Edit $label",
    keyPath = listOf("text", field),
    labelWidth = 72.dp,
    step = step,
    format = { formatCompactNumber(it, decimals) },
    read = { _, t -> read(t) },
    write = { v -> buildJsonObject { put("text", buildJsonObject { put(field, (v * 10).roundToInt() / 10.0) }) } },
)

/** `true` when 2+ selected text nodes carry different copy (the Text field then reads `Mixed`). */
internal fun isMixedText(values: List<String?>): Boolean = values.size > 1 && values.distinct().size > 1

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun DocumentTextSection(
    controller: DocumentElementInspectorController,
    node: NodeSpec,
    targets: List<EditTarget> = controller.editTargets(),
) {
    val textPayload = node.text ?: return
    val textTargets = targets.filter { it.resolved.text != null }
    // Multi-selection with differing copy: show a `Mixed` placeholder and disable Apply.
    val mixedText = isMixedText(textTargets.map { it.resolved.text?.value?.plainText })
    val currentText = if (mixedText) "" else textPayload.value.plainText
    val textFieldState = rememberTextFieldState(initialText = currentText)
    var isFocused by remember(node.id) { mutableStateOf(false) }
    val beat = controller.activeBeat
    val themeSpec = DocumentThemeResolver.resolveThemeSpec(controller.store.document)

    LaunchedEffect(node.id, beat, currentText, mixedText) {
        if (!isFocused && textFieldState.text.toString() != currentText) {
            textFieldState.setTextAndPlaceCursorAtEnd(currentText)
        }
    }

    fun modified(field: String) = textTargets.any { t ->
        if (beat > 0) isKeyPatchedAtBeat(t.base, beat, listOf("text", field)) else false
    }

    FlatCollapsibleSection(title = "Text", testTag = "ciacola-doc-inspector-text-section") {
        TextArea(
            state = textFieldState,
            lineLimits = TextFieldLineLimits.MultiLine(minHeightInLines = 2, maxHeightInLines = 6),
            readOnly = mixedText,
            placeholder = if (mixedText) {
                { Text("Mixed", style = inspectorBodyStyle(), modifier = Modifier.testTag("ciacola-doc-text-mixed")) }
            } else {
                null
            },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp, max = 120.dp)
                .onFocusChanged { state ->
                    val wasFocused = isFocused
                    isFocused = state.hasFocus
                    controller.textInputHasFocus = state.hasFocus
                    if (wasFocused && !state.hasFocus && !mixedText) {
                        val edited = textFieldState.text.toString()
                        if (edited != currentText) controller.applyWholeTextReplacement(edited)
                    }
                }
                .trackAuthoringTextInputFocus()
                .testTag("ciacola-doc-text-value-input"),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedButton(
                onClick = {
                    val edited = textFieldState.text.toString()
                    if (edited != currentText) controller.applyWholeTextReplacement(edited)
                },
                enabled = !mixedText,
                modifier = Modifier.testTag("ciacola-doc-text-apply-button"),
            ) { Text("Apply", style = inspectorBodyStyle()) }
            OutlinedButton(
                onClick = { controller.startInlineTextEdit(node.id) },
                modifier = Modifier.testTag("ciacola-doc-text-inline-edit-button"),
            ) { Text("Edit on stage", style = inspectorBodyStyle()) }
        }

        InspectorLabeledRow("Style") {
            Dropdown(
                modifier = Modifier.fillMaxWidth().height(InspectorTokens.RowHeight).testTag("ciacola-doc-text-style-dropdown"),
                menuContent = {
                    for (role in DOCUMENT_TYPOGRAPHY_ROLES) {
                        selectableItem(
                            selected = textPayload.style == role,
                            onClick = { controller.setTextProperty("style", JsonPrimitive(role)) },
                        ) { Text(humanizeTokenKey(role), style = inspectorBodyStyle()) }
                    }
                },
            ) { Text(humanizeTokenKey(textPayload.style ?: "body"), style = inspectorBodyStyle()) }
        }

        val fontOptions = remember { listOf("Inter", "JetBrains Mono", "System Sans", "System Serif") }
        InspectorLabeledRow("Font") {
            Dropdown(
                modifier = Modifier.fillMaxWidth().height(InspectorTokens.RowHeight).testTag("ciacola-doc-text-font-dropdown"),
                menuContent = {
                    for (fontName in fontOptions) {
                        selectableItem(
                            selected = textPayload.font == fontName,
                            onClick = { controller.setTextProperty("font", JsonPrimitive(fontName)) },
                        ) { Text(fontName, style = inspectorBodyStyle()) }
                    }
                },
            ) { Text(textPayload.font ?: "Theme default", style = inspectorBodyStyle()) }
        }

        val weights = remember { listOf(300, 400, 500, 600, 700, 800, 900) }
        InspectorLabeledRow("Weight") {
            Dropdown(
                modifier = Modifier.fillMaxWidth().height(InspectorTokens.RowHeight).testTag("ciacola-doc-text-weight-dropdown"),
                menuContent = {
                    for (w in weights) {
                        selectableItem(
                            selected = textPayload.weight == w,
                            onClick = { controller.setTextProperty("weight", JsonPrimitive(w)) },
                        ) { Text("$w", style = inspectorBodyStyle()) }
                    }
                },
            ) { Text("${textPayload.weight ?: 400}", style = inspectorBodyStyle()) }
        }

        val sizeProperty = remember(themeSpec) {
            textProperty("Size", "ciacola-doc-text-size", "size", step = 1.0, decimals = 1) { t ->
                val p = t.resolved.text ?: return@textProperty null
                p.size ?: themeSpec.typography[p.style ?: "body"]?.size ?: 60.0
            }
        }
        GridPropertyField(controller, sizeProperty, textTargets, Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val lineHeight = remember {
                textProperty("Line height", "ciacola-doc-text-line-height", "lineHeight", step = 1.0, decimals = 1) { t ->
                    val p = t.resolved.text ?: return@textProperty null
                    p.lineHeight ?: ((p.size ?: 30.0) * 1.3)
                }
            }
            GridPropertyField(controller, lineHeight, textTargets, Modifier.fillMaxWidth())
        }
        val tracking = remember {
            textProperty("Tracking", "ciacola-doc-text-tracking", "tracking", step = 0.1, decimals = 1) { t ->
                t.resolved.text?.let { it.tracking ?: 0.0 }
            }
        }
        GridPropertyField(controller, tracking, textTargets, Modifier.fillMaxWidth())

        InspectorColorRow(
            label = "Color",
            currentValue = textPayload.color ?: "foreground",
            controller = controller,
            tagPrefix = "doc-text-color",
            modified = modified("color") || (beat == 0 && textPayload.color != null),
            onChanged = { value, inGesture ->
                if (value == null) {
                    controller.resetPropertyAtCurrentBeat("Reset text color", listOf("text", "color"))
                } else {
                    controller.setTextProperty("color", JsonPrimitive(value), useActiveGesture = inGesture)
                }
            },
            onReset = { controller.resetPropertyAtCurrentBeat("Reset text color", listOf("text", "color")) },
        )

        Row(
            modifier = Modifier.fillMaxWidth().height(InspectorTokens.RowHeight),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val currentAlign = TextAlignOption.entries.firstOrNull { it.value == (textPayload.align ?: "start") } ?: TextAlignOption.Left
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                for (option in TextAlignOption.entries) {
                    ToolbarGlyphButton(
                        glyph = option.glyph,
                        tooltip = option.label,
                        tag = "ciacola-doc-text-align-${option.value}",
                        enabled = !controller.store.isReadOnly,
                        selected = option == currentAlign,
                        isToggle = true,
                        onClick = { controller.setTextProperty("align", JsonPrimitive(option.value)) },
                    )
                }
            }
            Row(
                modifier = Modifier.testTag("ciacola-doc-text-italic"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Checkbox(
                    checked = textPayload.italic == true,
                    onCheckedChange = { controller.setTextProperty("italic", JsonPrimitive(it)) },
                    modifier = Modifier.semantics { contentDescription = "Italic" },
                )
                Text("Italic", style = inspectorBodyStyle())
            }
        }

        Text("Morph by", style = inspectorBodyStyle(), color = inspectorSecondaryTextColor())
        val currentGranularity = TextMorphGranularity.entries.firstOrNull { it.morphBy == textPayload.morph?.by }
            ?: TextMorphGranularity.Whole
        InspectorSegmented(
            options = listOf(
                TextMorphGranularity.Whole,
                TextMorphGranularity.Paragraph,
                TextMorphGranularity.Word,
                TextMorphGranularity.Character,
            ),
            selected = currentGranularity,
            label = { it.label },
            testTag = { "ciacola-doc-text-morph-${it.name.lowercase()}" },
            onSelect = { controller.setTextMorphGranularity(it) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
internal fun DocumentFillStrokeSurfaceSection(
    controller: DocumentElementInspectorController,
    node: NodeSpec,
) {
    val kind = node.kind ?: return
    val shape = node.shape
    val surface = node.text?.surface ?: node.code?.surface ?: node.stack?.surface ?: node.row?.surface ?: node.column?.surface
    val supportsSurface = kind in setOf(NodeKind.Text, NodeKind.Code, NodeKind.Stack, NodeKind.Row, NodeKind.Column)
    if (shape == null && !supportsSurface) return
    val beat = controller.activeBeat
    val base = controller.selectedBaseNode()
    fun patched(vararg path: String) = base != null && beat > 0 && isKeyPatchedAtBeat(base, beat, path.toList())

    FlatCollapsibleSection(
        title = if (shape != null) "Fill & Stroke" else "Surface",
        testTag = "ciacola-doc-inspector-surface-section",
    ) {
        if (shape != null) {
            InspectorColorRow(
                label = "Fill",
                currentValue = (shape.fill as? Fill.Solid)?.color,
                controller = controller,
                tagPrefix = "doc-shape-fill",
                modified = patched("shape", "fill"),
                onChanged = { v, inGesture -> controller.setShapeFill(v, useActiveGesture = inGesture) },
                onReset = { controller.resetPropertyAtCurrentBeat("Reset fill", listOf("shape", "fill")) },
            )
            val stroke = shape.stroke
            InspectorColorRow(
                label = "Stroke",
                currentValue = stroke?.color,
                controller = controller,
                tagPrefix = "doc-shape-stroke",
                modified = patched("shape", "stroke"),
                onChanged = { c, inGesture ->
                    controller.setShapeStroke(c?.let { StrokeSpec(color = it, width = stroke?.width ?: 2.0) }, useActiveGesture = inGesture)
                },
                onReset = { controller.resetPropertyAtCurrentBeat("Reset stroke", listOf("shape", "stroke")) },
            )
            if (stroke != null) {
                val widthProperty = remember(stroke.color) {
                    GridProperty(
                        label = "Stroke width",
                        tag = "ciacola-doc-shape-stroke-width",
                        undoLabel = "Edit stroke width",
                        keyPath = listOf("shape", "stroke"),
                        labelWidth = 72.dp,
                        step = 0.5,
                        read = { _, t -> t.resolved.shape?.stroke?.width },
                        write = { v ->
                            buildJsonObject {
                                put("shape", buildJsonObject {
                                    put("stroke", buildJsonObject { put("color", stroke.color); put("width", v.coerceAtLeast(0.0)) })
                                })
                            }
                        },
                    )
                }
                GridPropertyField(controller, widthProperty, controller.editTargets().filter { it.resolved.shape != null })
            }
        } else {
            InspectorColorRow(
                label = "Fill",
                currentValue = (surface?.fill as? Fill.Solid)?.color,
                controller = controller,
                tagPrefix = "doc-surface-fill",
                modified = patched(kind.payloadKey, "surface", "fill"),
                onChanged = { v, inGesture -> controller.setSurfaceFill(kind, v, useActiveGesture = inGesture) },
                onReset = { controller.resetPropertyAtCurrentBeat("Reset surface fill", listOf(kind.payloadKey, "surface", "fill")) },
            )
            CompactSliderRow(
                label = "Radius",
                value = (surface?.cornerRadius ?: 0.0).toFloat(),
                valueRange = 0f..64f,
                valueText = formatCompactNumber(surface?.cornerRadius ?: 0.0, 0),
                tagPrefix = "doc-surface-radius",
                modified = patched(kind.payloadKey, "surface", "cornerRadius") || (beat == 0 && surface?.cornerRadius != null),
                onReset = { controller.resetPropertyAtCurrentBeat("Reset corner radius", listOf(kind.payloadKey, "surface", "cornerRadius")) },
                onGestureStart = { controller.beginGesture("Adjust corner radius") },
                onChange = { v -> controller.setSurfaceCornerRadius(kind, v.roundToInt().toDouble(), useActiveGesture = true) },
                onGestureEnd = { controller.commitGesture() },
            )
            val paddingProperty = remember(kind) {
                GridProperty(
                    label = "Padding",
                    tag = "ciacola-doc-surface-padding",
                    undoLabel = "Edit padding",
                    keyPath = listOf(kind.payloadKey, "surface", "padding"),
                    labelWidth = 72.dp,
                    read = { _, t ->
                        val r = t.resolved
                        (r.text?.surface ?: r.code?.surface ?: r.stack?.surface ?: r.row?.surface ?: r.column?.surface)?.padding?.left ?: 0.0
                    },
                    write = { v ->
                        buildJsonObject {
                            put(kind.payloadKey, buildJsonObject { put("surface", buildJsonObject { put("padding", v.coerceAtLeast(0.0).roundToInt().toDouble()) }) })
                        }
                    },
                )
            }
            GridPropertyField(controller, paddingProperty, controller.editTargets().filter { it.resolved.kind == kind })
        }
    }
}

/**
 * Declared specimen **Parameters**: shader uniforms and other declared parameters share one
 * flat section. Float/int parameters use a compact slider row; every parameter shows a modified
 * dot (click to reset).
 */
@Composable
internal fun DocumentSpecimenParamsSection(
    controller: DocumentElementInspectorController,
    node: NodeSpec,
    declaration: SpecimenDeclaration,
) {
    if (declaration.params.isEmpty()) return
    val paramsMap = node.specimen?.params.orEmpty()
    val base = controller.selectedBaseNode()
    val beat = controller.activeBeat
    fun isModified(name: String): Boolean = when {
        base == null -> false
        beat > 0 -> isKeyPatchedAtBeat(base, beat, listOf("specimen", "params", name))
        else -> base.specimen?.params?.containsKey(name) == true
    }
    val ordered = declaration.params.sortedBy { if (it is ParamSpec.FloatParam && it.isUniform) 0 else 1 }

    FlatCollapsibleSection(
        title = "Parameters · ${declaration.label}",
        testTag = "ciacola-doc-specimen-params-section",
    ) {
        for (spec in ordered) {
            when (spec) {
                is ParamSpec.FloatParam -> {
                    val resolved = ParamResolver.resolve(spec, paramsMap[spec.name]).value
                    DocumentSliderControl(
                        label = spec.label,
                        value = resolved,
                        valueRange = spec.range,
                        valueLabel = formatSteppedFloatLabel(resolved, spec.step),
                        tagPrefix = "specimen-param-${spec.name}",
                        controller = controller,
                        step = spec.step,
                        formatValue = { formatSteppedFloatLabel(it, spec.step) },
                        onChanged = { v, inTx -> controller.setSpecimenFloatParam(spec, v, useActiveGesture = inTx) },
                        onReset = { controller.resetSpecimenParam(spec.name) },
                        onApplyAll = null,
                        modified = isModified(spec.name),
                    )
                }
                is ParamSpec.IntParam -> {
                    val resolved = ParamResolver.resolve(spec, paramsMap[spec.name]).value
                    DocumentSliderControl(
                        label = spec.label,
                        value = resolved.toFloat(),
                        valueRange = spec.range.first.toFloat()..spec.range.last.toFloat(),
                        valueLabel = "$resolved",
                        tagPrefix = "specimen-param-${spec.name}",
                        controller = controller,
                        step = spec.step.coerceAtLeast(1).toFloat(),
                        formatValue = { "${it.roundToInt()}" },
                        onChanged = { v, inTx -> controller.setSpecimenIntParam(spec, v.roundToInt(), useActiveGesture = inTx) },
                        onReset = { controller.resetSpecimenParam(spec.name) },
                        onApplyAll = null,
                        modified = isModified(spec.name),
                    )
                }
                is ParamSpec.BooleanParam -> {
                    val resolved = ParamResolver.resolve(spec, paramsMap[spec.name]).value
                    Row(
                        modifier = Modifier.fillMaxWidth().height(InspectorTokens.RowHeight),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        ModifiedDot(isModified(spec.name), spec.label, "ciacola-specimen-param-${spec.name}") { controller.resetSpecimenParam(spec.name) }
                        Checkbox(
                            checked = resolved,
                            onCheckedChange = { controller.setSpecimenBooleanParam(spec, it) },
                            modifier = Modifier
                                .semantics { contentDescription = spec.label }
                                .testTag("ciacola-specimen-param-${spec.name}"),
                        )
                        Text(spec.label, style = inspectorBodyStyle())
                    }
                }
                is ParamSpec.ColorParam -> {
                    val resolvedColor = ParamResolver.resolve(spec, paramsMap[spec.name]).value
                    val currentStr = (paramsMap[spec.name] as? JsonPrimitive)?.content ?: resolvedColor.toHexString()
                    InspectorColorRow(
                        label = spec.label,
                        currentValue = currentStr,
                        controller = controller,
                        tagPrefix = "specimen-param-${spec.name}",
                        modified = isModified(spec.name),
                        allowClear = false,
                        onChanged = { v, inGesture -> if (v != null) controller.setSpecimenColorParam(spec, v, useActiveGesture = inGesture) },
                        onReset = { controller.resetSpecimenParam(spec.name) },
                    )
                }
                is ParamSpec.ChoiceParam -> {
                    val resolved = ParamResolver.resolve(spec, paramsMap[spec.name]).value
                    Row(
                        modifier = Modifier.fillMaxWidth().height(InspectorTokens.RowHeight),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        ModifiedDot(isModified(spec.name), spec.label, "ciacola-specimen-param-${spec.name}") { controller.resetSpecimenParam(spec.name) }
                        Text(spec.label, style = inspectorBodyStyle(), color = inspectorSecondaryTextColor(), modifier = Modifier.width(72.dp))
                        Dropdown(
                            modifier = Modifier.weight(1f).height(InspectorTokens.RowHeight).testTag("ciacola-specimen-param-${spec.name}"),
                            menuContent = {
                                for (choice in spec.choices) {
                                    selectableItem(
                                        selected = resolved == choice,
                                        onClick = { controller.setSpecimenChoiceParam(spec, choice) },
                                    ) { Text(humanizeTokenKey(choice), style = inspectorBodyStyle()) }
                                }
                            },
                        ) { Text(humanizeTokenKey(resolved), style = inspectorBodyStyle()) }
                    }
                }
                is ParamSpec.StringParam -> {
                    val resolved = ParamResolver.resolve(spec, paramsMap[spec.name]).value
                    val tfState = rememberTextFieldState(initialText = resolved)
                    LaunchedEffect(node.id, beat, resolved) {
                        if (tfState.text.toString() != resolved) tfState.setTextAndPlaceCursorAtEnd(resolved)
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        ModifiedDot(isModified(spec.name), spec.label, "ciacola-specimen-param-${spec.name}") { controller.resetSpecimenParam(spec.name) }
                        Text(spec.label, style = inspectorBodyStyle(), color = inspectorSecondaryTextColor(), modifier = Modifier.width(72.dp))
                        TextField(
                            state = tfState,
                            modifier = Modifier
                                .weight(1f)
                                .onFocusChanged { st ->
                                    controller.textInputHasFocus = st.isFocused
                                    if (!st.isFocused) {
                                        val v = tfState.text.toString()
                                        if (v != resolved) controller.setSpecimenStringParam(spec, v)
                                    }
                                }
                                .trackAuthoringTextInputFocus()
                                .testTag("ciacola-specimen-param-${spec.name}"),
                        )
                    }
                }
            }
        }
    }
}

internal fun snapSliderValue(
    raw: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    step: Float?,
): Float {
    val clamped = raw.coerceIn(valueRange.start, valueRange.endInclusive)
    if (step == null || step <= 0f) return clamped
    val steps = ((clamped - valueRange.start) / step).roundToInt()
    return (valueRange.start + steps * step).coerceIn(valueRange.start, valueRange.endInclusive)
}

internal fun formatSteppedFloatLabel(value: Float, step: Float?): String {
    if (step != null && step >= 1f && step % 1f == 0f) {
        return "%.0f".format(java.util.Locale.US, value)
    }
    return "%.2f".format(java.util.Locale.US, value)
}

/**
 * Compact 24dp slider row (label · slider · readout) backed by one coalesced gesture transaction.
 * The legacy per-row `All` button is gone ([onApplyAll] is ignored; the section-level
 * `This build | All builds` toggle owns that) and `Reset` is the modified dot.
 */
@Composable
internal fun DocumentSliderControl(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    valueLabel: String,
    tagPrefix: String,
    controller: DocumentElementInspectorController,
    onChanged: (value: Float, inGesture: Boolean) -> Unit,
    onReset: (() -> Unit)?,
    @Suppress("UNUSED_PARAMETER") onApplyAll: (() -> Unit)?,
    step: Float? = null,
    formatValue: ((Float) -> String)? = null,
    modified: Boolean = false,
) {
    val clampedExternal = snapSliderValue(value, valueRange, step)
    var dragging by remember(tagPrefix) { mutableStateOf(false) }
    var dragValue by remember(tagPrefix) { mutableStateOf(clampedExternal) }
    var lastEmitted by remember(tagPrefix) { mutableStateOf(clampedExternal) }
    val shown = if (dragging && formatValue != null) formatValue(dragValue) else valueLabel
    CompactSliderRow(
        label = label,
        value = clampedExternal,
        valueRange = valueRange,
        valueText = shown,
        tagPrefix = tagPrefix,
        modified = modified,
        onReset = onReset,
        onGestureStart = {
            dragging = true
            lastEmitted = clampedExternal
            controller.beginGesture("Adjust $label")
        },
        onChange = { raw ->
            val snapped = snapSliderValue(raw, valueRange, step)
            dragValue = snapped
            if (snapped != lastEmitted) {
                lastEmitted = snapped
                onChanged(snapped, true)
            }
        },
        onGestureEnd = {
            dragging = false
            controller.commitGesture()
        },
    )
}
