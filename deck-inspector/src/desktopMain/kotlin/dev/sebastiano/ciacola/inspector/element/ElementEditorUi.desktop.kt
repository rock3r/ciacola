package dev.sebastiano.ciacola.inspector.element

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.sebastiano.ciacola.inspector.ElementPatch
import dev.sebastiano.ciacola.inspector.StageElementMetadata
import dev.sebastiano.ciacola.inspector.StageNodeSnapshot
import dev.sebastiano.ciacola.inspector.design.DesignSectionCard
import dev.sebastiano.ciacola.inspector.design.DesignTooltipIconButton
import dev.sebastiano.ciacola.inspector.design.ModifiedDotBadge
import dev.sebastiano.ciacola.inspector.design.designActivatableFocus
import dev.sebastiano.ciacola.inspector.design.toArgbLong
import dev.sebastiano.ciacola.theme.LocalDeckTheme
import dev.sebastiano.ciacola.ui.rememberAccessibleFocusRingColor
import dev.sebastiano.ciacola.ui.trackAuthoringTextInputFocus
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.GroupHeader
import org.jetbrains.jewel.ui.component.InfoText
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextArea
import org.jetbrains.jewel.ui.component.Tooltip
import org.jetbrains.jewel.ui.component.VerticalScrollbar
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.ui.theme.colorPalette
import org.jetbrains.jewel.ui.typography

/**
 * Jewel-styled inspector panel for direct per-element editing of text copy, typography, colours,
 * borders, corner radii, padding, and stage X/Y offsets.
 *
 * Designed to be embedded inside Studio's right-hand Inspector tabs (`Element` tab) or a
 * standalone inspector window.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ElementEditorPanel(
    controller: ElementEditorController,
    modifier: Modifier = Modifier,
    onCopyFeedback: ((String) -> Unit)? = null,
) {
    val globalColors = JewelTheme.globalColors
    val focusRingColor = rememberAccessibleFocusRingColor()
    val scroll = rememberScrollState()
    val store = controller.overridesStore

    // Observe store revision so live edits and undo/redo recompose immediately.
    store.revision
    val overridesFile = store.data()
    val slideNodes = controller.slidesNodesForCurrentSlide()
    val selectedId = controller.selectedElementId
    val isOnCurrentBeat = controller.isSelectedElementOnCurrentBeat()
    val selectedSnapshot = selectedId?.let { id ->
        slideNodes.firstOrNull { it.elementId == id } ?: controller.inspectionRegistry.nodes[id]
    }
    val selectedMeta = selectedId?.let { controller.inspectionRegistry.metadataFor(it) }
    val patch = selectedId?.let { overridesFile.patchFor(it) } ?: selectedId?.let { ElementPatch(it) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(globalColors.panelBackground)
            .testTag("ciacola-element-editor-panel"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .padding(start = 10.dp, top = 8.dp, bottom = 10.dp, end = 16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ElementEditorToolbar(
                canUndo = store.canUndo,
                canRedo = store.canRedo,
                hasAnyOverrides = overridesFile.hasOverrides,
                focusRingColor = focusRingColor,
                onUndo = { controller.undo() },
                onRedo = { controller.redo() },
                onResetAll = { store.resetAll() },
            )

            SlideElementPicker(
                nodes = slideNodes,
                selectedElementId = selectedId,
                overriddenIds = overridesFile.elements.keys,
                focusRingColor = focusRingColor,
                onSelect = { controller.selectElement(it) },
            )

            if (selectedId == null || patch == null) {
                InfoText(
                    "Click an editable element on the stage or pick one above. Double-click text on stage to edit inline.",
                )
                return@Column
            }

            Divider(Orientation.Horizontal, Modifier.fillMaxWidth())

            if (!isOnCurrentBeat) {
                DesignSectionCard(modifier = Modifier.testTag("ciacola-element-missing-on-beat")) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        InfoText("$selectedId is not on this beat.")
                        Tooltip(tooltip = { Text("Clear element selection (Esc)") }) {
                            OutlinedButton(
                                onClick = { controller.selectElement(null) },
                                modifier = Modifier
                                    .designActivatableFocus(focusRingColor)
                                    .testTag("ciacola-element-clear-selection"),
                            ) {
                                Text("Clear Selection", style = JewelTheme.typography.small)
                            }
                        }
                    }
                }
                return@Column
            }

            SelectedElementHeader(
                elementId = selectedId,
                snapshot = selectedSnapshot,
                hasPatch = !patch.isEmpty,
                canInlineEdit = controller.canInlineEdit(selectedId),
                focusRingColor = focusRingColor,
                onEditInline = { controller.startInlineEdit(selectedId) },
                onCopyKotlin = {
                    val snippet = store.exportKotlinSnippet(selectedId, copyToClipboard = true)
                    onCopyFeedback?.invoke(snippet)
                },
                onResetElement = { store.resetElement(selectedId) },
            )

            val supportsText = selectedMeta?.supportsText == true ||
                selectedSnapshot?.resolvedText?.isNotEmpty() == true ||
                patch.text != null

            if (supportsText) {
                TextCopySection(
                    elementId = selectedId,
                    currentText = patch.text ?: selectedSnapshot?.resolvedText ?: selectedMeta?.defaultText.orEmpty(),
                    isOverridden = patch.text != null,
                    store = store,
                    focusRingColor = focusRingColor,
                )

                TypographySection(
                    elementId = selectedId,
                    patch = patch,
                    snapshot = selectedSnapshot,
                    meta = selectedMeta,
                    store = store,
                    focusRingColor = focusRingColor,
                )
            }

            FillAndBorderSection(
                elementId = selectedId,
                patch = patch,
                meta = selectedMeta,
                store = store,
            )

            SpacingAndPositionSection(
                elementId = selectedId,
                patch = patch,
                meta = selectedMeta,
                store = store,
            )
        }

        VerticalScrollbar(
            scrollState = scroll,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .padding(vertical = 4.dp, horizontal = 2.dp)
                .testTag("ciacola-element-editor-vscrollbar"),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ElementEditorToolbar(
    canUndo: Boolean,
    canRedo: Boolean,
    hasAnyOverrides: Boolean,
    focusRingColor: Color,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onResetAll: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DesignTooltipIconButton(
                icon = AllIconsKeys.Actions.Undo,
                contentDescription = "Undo element edit",
                tooltip = "Undo element edit (⌘Z)",
                testTag = "ciacola-element-undo",
                enabled = canUndo,
                size = 26.dp,
                iconSize = 14.dp,
                onClick = onUndo,
            )
            DesignTooltipIconButton(
                icon = AllIconsKeys.Actions.Redo,
                contentDescription = "Redo element edit",
                tooltip = "Redo element edit (⇧⌘Z)",
                testTag = "ciacola-element-redo",
                enabled = canRedo,
                size = 26.dp,
                iconSize = 14.dp,
                onClick = onRedo,
            )
        }

        Tooltip(tooltip = { Text("Reset all element overrides across the deck") }) {
            OutlinedButton(
                onClick = onResetAll,
                enabled = hasAnyOverrides,
                modifier = Modifier
                    .designActivatableFocus(focusRingColor)
                    .testTag("ciacola-element-reset-all"),
            ) {
                Text("Reset All", style = JewelTheme.typography.small)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SlideElementPicker(
    nodes: List<StageNodeSnapshot>,
    selectedElementId: String?,
    overriddenIds: Set<String>,
    focusRingColor: Color,
    onSelect: (String?) -> Unit,
) {
    val globalColors = JewelTheme.globalColors
    GroupHeader("Slide Elements (${nodes.size}):")
    if (nodes.isEmpty()) {
        InfoText("No editable elements detected on this beat yet.")
        return
    }
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        nodes.forEach { node ->
            val isSelected = node.elementId == selectedElementId
            val isEdited = node.elementId in overriddenIds
            val rowBg = if (isSelected) {
                focusRingColor.copy(alpha = 0.16f)
            } else {
                globalColors.panelBackground
            }
            val rowBorder = if (isSelected) focusRingColor else globalColors.borders.normal.copy(alpha = 0.6f)
            Tooltip(tooltip = { Text("Select ${node.elementId} (${node.kind})") }) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .designActivatableFocus(focusRingColor, cornerRadius = 5.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(rowBg, RoundedCornerShape(5.dp))
                        .border(1.dp, rowBorder, RoundedCornerShape(5.dp))
                        .pointerHoverIcon(PointerIcon.Hand)
                        .clickable { onSelect(node.elementId) }
                        .padding(horizontal = 8.dp, vertical = 5.dp)
                        .semantics {
                            role = Role.RadioButton
                            selected = isSelected
                            stateDescription = if (isSelected) "Selected" else "Not selected"
                        }
                        .testTag("ciacola-element-picker-${node.elementId}"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (isEdited) {
                        ModifiedDotBadge(tooltip = "${node.elementId} has overrides")
                    }
                    Text(
                        text = node.label.ifBlank { node.elementId },
                        style = JewelTheme.typography.small.copy(
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        ),
                        color = globalColors.text.normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = node.kind,
                        style = JewelTheme.typography.small,
                        color = globalColors.text.info,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SelectedElementHeader(
    elementId: String,
    snapshot: StageNodeSnapshot?,
    hasPatch: Boolean,
    canInlineEdit: Boolean,
    focusRingColor: Color,
    onEditInline: () -> Unit,
    onCopyKotlin: () -> Unit,
    onResetElement: () -> Unit,
) {
    val globalColors = JewelTheme.globalColors
    val palette = JewelTheme.colorPalette
    val isDark = JewelTheme.isDark
    val editedAccent = palette.yellowOrNull(if (isDark) 6 else 5) ?: Color(0xFFE0A82E)
    var copiedBanner by remember(elementId) { mutableStateOf(false) }

    LaunchedEffect(copiedBanner) {
        if (copiedBanner) {
            delay(1_500)
            copiedBanner = false
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Tooltip(tooltip = { Text("ID: $elementId") }) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = snapshot?.label?.ifBlank { elementId } ?: elementId,
                        style = JewelTheme.typography.medium.copy(fontWeight = FontWeight.SemiBold),
                        color = globalColors.text.normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "$elementId · ${snapshot?.kind ?: "Element"}",
                        style = JewelTheme.typography.small,
                        color = globalColors.text.info,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (hasPatch) {
                Tooltip(tooltip = { Text("Element has active overrides in .storyboard/element-overrides.json") }) {
                    Row(
                        modifier = Modifier
                            .padding(start = 6.dp)
                            .background(editedAccent.copy(alpha = 0.18f), RoundedCornerShape(4.dp))
                            .border(1.dp, editedAccent.copy(alpha = 0.65f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                            .testTag("ciacola-element-edited-badge"),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ModifiedDotBadge(tooltip = "Modified element")
                        Text("Edited", style = JewelTheme.typography.small, color = globalColors.text.normal)
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (canInlineEdit) {
                Tooltip(tooltip = { Text("Edit text directly on the stage (or double-click element)") }) {
                    DefaultButton(
                        onClick = onEditInline,
                        modifier = Modifier
                            .designActivatableFocus(focusRingColor)
                            .testTag("ciacola-element-edit-inline"),
                    ) {
                        Text("Edit Text", style = JewelTheme.typography.small)
                    }
                }
            }
            Tooltip(tooltip = { Text("Copy clean ElementPatch Kotlin snippet to clipboard") }) {
                OutlinedButton(
                    onClick = {
                        onCopyKotlin()
                        copiedBanner = true
                    },
                    modifier = Modifier
                        .designActivatableFocus(focusRingColor)
                        .testTag("ciacola-element-copy-kotlin"),
                ) {
                    Text(if (copiedBanner) "Copied!" else "Copy Kotlin", style = JewelTheme.typography.small)
                }
            }
            Tooltip(tooltip = { Text("Reset all overrides for $elementId (⌘⌫)") }) {
                OutlinedButton(
                    onClick = onResetElement,
                    enabled = hasPatch,
                    modifier = Modifier
                        .designActivatableFocus(focusRingColor)
                        .testTag("ciacola-element-reset"),
                ) {
                    Text("Reset", style = JewelTheme.typography.small)
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TextCopySection(
    elementId: String,
    currentText: String,
    isOverridden: Boolean,
    store: ElementOverridesStore,
    focusRingColor: Color,
) {
    val textFieldState = rememberTextFieldState(initialText = currentText)
    var textHasFocus by remember(elementId) { mutableStateOf(false) }
    var lastExternalElementId by remember { mutableStateOf(elementId) }
    var lastExternalText by remember { mutableStateOf(currentText) }

    LaunchedEffect(elementId, currentText) {
        if (elementId != lastExternalElementId || currentText != lastExternalText) {
            lastExternalElementId = elementId
            lastExternalText = currentText
            if (textFieldState.text.toString() != currentText) {
                textFieldState.setTextAndPlaceCursorAtEnd(currentText)
            }
        }
    }

    val liveTyped = textFieldState.text.toString()
    LaunchedEffect(liveTyped) {
        val typed = textFieldState.text.toString()
        if (textHasFocus && elementId == lastExternalElementId && typed != lastExternalText) {
            lastExternalText = typed
            store.updateElement(
                elementId = elementId,
                coalesce = true,
                coalesceKey = "text:$elementId",
            ) { it.copy(text = typed) }
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (isOverridden) {
                ModifiedDotBadge(tooltip = "Text Copy is overridden")
            }
            GroupHeader("Text Copy:", modifier = Modifier.weight(1f))
        }
        DesignTooltipIconButton(
            icon = AllIconsKeys.Actions.Rollback,
            contentDescription = "Reset text copy",
            tooltip = "Reset text copy to slide default",
            testTag = "ciacola-element-text-reset",
            enabled = isOverridden,
            size = 22.dp,
            iconSize = 13.dp,
            onClick = { store.updateElement(elementId) { it.copy(text = null) } },
        )
    }

    TextArea(
        state = textFieldState,
        lineLimits = TextFieldLineLimits.MultiLine(minHeightInLines = 2, maxHeightInLines = 5),
        modifier = Modifier
            .fillMaxWidth()
            .height(68.dp)
            .trackAuthoringTextInputFocus()
            .designActivatableFocus(focusRingColor) { focused ->
                textHasFocus = focused
            }
            .testTag("ciacola-element-text-input"),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TypographySection(
    elementId: String,
    patch: ElementPatch,
    snapshot: StageNodeSnapshot?,
    meta: StageElementMetadata?,
    store: ElementOverridesStore,
    focusRingColor: Color,
) {
    val theme = LocalDeckTheme.current
    val defaultFontSize = meta?.defaultFontSizeSp ?: snapshot?.effectiveFontSizeSp ?: 24f
    val currentFontSize = (patch.fontSizeSp ?: defaultFontSize).coerceIn(8f, 140f)
    val defaultWeight = meta?.defaultFontWeight ?: 400
    val currentWeight = patch.fontWeight ?: defaultWeight
    val defaultTextColor = meta?.defaultTextColor ?: snapshot?.effectiveForeground ?: theme.colors.foreground
    val currentTextColor = patch.textColorArgb?.let(::Color) ?: defaultTextColor
    val effectiveBg = snapshot?.effectiveBackground ?: theme.colors.background

    GroupHeader("Typography:")

    ElementStepperSliderRow(
        label = "Font Size",
        value = currentFontSize,
        valueText = "${currentFontSize.roundToInt()} sp",
        valueRange = 8f..140f,
        step = 2f,
        isOverridden = patch.fontSizeSp != null,
        testTagPrefix = "ciacola-element-fontsize",
        onGestureStart = { store.beginEditGesture() },
        onGestureEnd = { store.endEditGesture() },
        onValueChanged = { next, coalesce ->
            store.updateElement(elementId, coalesce = coalesce, coalesceKey = "fontsize:$elementId") {
                it.copy(fontSizeSp = next.coerceIn(8f, 140f))
            }
        },
        onReset = { store.updateElement(elementId) { it.copy(fontSizeSp = null) } },
    )

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().height(24.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (patch.fontWeight != null) {
                    ModifiedDotBadge(tooltip = "Weight is overridden")
                }
                Text(
                    text = "Weight",
                    style = JewelTheme.typography.small,
                    color = if (patch.fontWeight != null) {
                        JewelTheme.globalColors.text.normal
                    } else {
                        JewelTheme.globalColors.text.info
                    },
                )
            }
            DesignTooltipIconButton(
                icon = AllIconsKeys.Actions.Rollback,
                contentDescription = "Reset Weight",
                tooltip = "Reset Weight to default",
                testTag = "ciacola-element-weight-reset",
                enabled = patch.fontWeight != null,
                size = 22.dp,
                iconSize = 12.dp,
                onClick = { store.updateElement(elementId) { it.copy(fontWeight = null) } },
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            listOf(400 to "400", 500 to "500", 600 to "600", 700 to "700").forEach { (weight, label) ->
                val selected = currentWeight == weight
                val pillMod = Modifier
                    .weight(1f)
                    .designActivatableFocus(focusRingColor)
                    .semantics {
                        role = Role.RadioButton
                        this.selected = selected
                    }
                    .testTag("ciacola-element-weight-$weight")
                val onClick = {
                    store.updateElement(elementId) { it.copy(fontWeight = weight) }
                }
                Tooltip(tooltip = { Text("Font weight $weight") }) {
                    if (selected) {
                        DefaultButton(onClick = onClick, modifier = pillMod) {
                            Text(label, style = JewelTheme.typography.small, maxLines = 1)
                        }
                    } else {
                        OutlinedButton(onClick = onClick, modifier = pillMod) {
                            Text(label, style = JewelTheme.typography.small, maxLines = 1)
                        }
                    }
                }
            }
        }
    }

    ElementColorControlRow(
        label = "Text Color",
        color = currentTextColor,
        isOverridden = patch.textColorArgb != null,
        testTagPrefix = "ciacola-element-textcolor",
        contrastAgainst = effectiveBg,
        onGestureStart = { store.beginEditGesture() },
        onGestureEnd = { store.endEditGesture() },
        onColorChanged = { c, coalesce ->
            store.updateElement(elementId, coalesce = coalesce, coalesceKey = "textcolor:$elementId") {
                it.copy(textColorArgb = c.toArgbLong())
            }
        },
        onReset = { store.updateElement(elementId) { it.copy(textColorArgb = null) } },
    )
}
