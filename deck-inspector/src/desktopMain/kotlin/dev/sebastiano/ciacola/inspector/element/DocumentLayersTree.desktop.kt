package dev.sebastiano.ciacola.inspector.element

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.sebastiano.ciacola.ui.accessibleFocusRing
import dev.sebastiano.ciacola.ui.rememberAccessibleFocusRingColor
import dev.sebastiano.ciacola.document.DeckSpecimenRegistry
import dev.sebastiano.ciacola.document.JsonMergePatch
import dev.sebastiano.ciacola.document.NodeKind
import dev.sebastiano.ciacola.document.NodeSpec
import dev.sebastiano.ciacola.document.ShapeGeometry
import dev.sebastiano.ciacola.inspector.design.FlatCollapsibleSection
import dev.sebastiano.ciacola.inspector.design.InspectorTokens
import dev.sebastiano.ciacola.inspector.design.InspectorTooltip
import dev.sebastiano.ciacola.inspector.design.inspectorAccent
import dev.sebastiano.ciacola.inspector.design.inspectorBodyStyle
import dev.sebastiano.ciacola.inspector.design.inspectorCaptionStyle
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.VerticalScrollbar
import org.jetbrains.jewel.ui.icons.AllIconsKeys

private const val LAYER_ROW_HEIGHT_DP = 22
private const val LAYERS_MAX_HEIGHT_DP = 176

/** Layers list height: whole rows only, capped at [LAYERS_MAX_HEIGHT_DP] (8 rows). */
internal fun layersTreeHeightDp(rowCount: Int): Int {
    val maxRows = LAYERS_MAX_HEIGHT_DP / LAYER_ROW_HEIGHT_DP
    return rowCount.coerceIn(1, maxRows) * LAYER_ROW_HEIGHT_DP
}

/** One visible row of the Layers tree. */
internal data class LayerRow(
    val node: NodeSpec,
    val depth: Int,
    val hasChildren: Boolean,
    val presentOnBeat: Boolean,
    val firstBeat: Int,
)

/** Flattens [nodes] into Layers rows, skipping the children of [collapsed] containers. */
internal fun buildLayerRows(nodes: List<NodeSpec>, beat: Int, collapsed: Set<String>): List<LayerRow> {
    val out = ArrayList<LayerRow>()
    fun visit(list: List<NodeSpec>, depth: Int, parentPresent: Boolean) {
        for (node in list) {
            val present = parentPresent && JsonMergePatch.isNodePresentAtBeat(node, beat)
            val children = node.children
            out += LayerRow(node, depth, children.isNotEmpty(), present, InspectorDisplayNames.firstPresentBeat(node))
            if (children.isNotEmpty() && node.id !in collapsed) visit(children, depth + 1, present)
        }
    }
    visit(nodes, 0, true)
    return out
}

internal fun buildLabel(beat: Int): String = if (beat <= 0) "Base" else "Build $beat"

internal fun layerGlyph(node: NodeSpec): InspectorGlyph = when (node.kind) {
    NodeKind.Text -> InspectorGlyph.LayerText
    NodeKind.Code -> InspectorGlyph.LayerCode
    NodeKind.Shape -> when (node.shape?.geometry) {
        ShapeGeometry.Ellipse -> InspectorGlyph.LayerEllipse
        ShapeGeometry.Line -> InspectorGlyph.LayerLine
        else -> InspectorGlyph.LayerRect
    }
    NodeKind.Image -> InspectorGlyph.LayerImage
    NodeKind.Specimen -> InspectorGlyph.LayerSpecimen
    NodeKind.Stack, NodeKind.Row, NodeKind.Column, NodeKind.Loupe -> InspectorGlyph.LayerContainer
    else -> InspectorGlyph.LayerOther
}

/** Click handler that reports whether `Shift` was held (multi-select). */
internal fun Modifier.clickWithShift(key: Any, onClick: (shift: Boolean) -> Unit): Modifier =
    pointerInput(key) {
        awaitEachGesture {
            awaitFirstDown()
            val up = waitForUpOrCancellation()
            if (up != null) {
                up.consume()
                onClick(currentEvent.keyboardModifiers.isShiftPressed)
            }
        }
    }

private fun isUnmodifiedArrowKey(event: androidx.compose.ui.input.key.KeyEvent): Boolean =
    !event.isAltPressed &&
        !event.isCtrlPressed &&
        !event.isMetaPressed &&
        (event.key == Key.DirectionLeft ||
            event.key == Key.DirectionRight ||
            event.key == Key.DirectionUp ||
            event.key == Key.DirectionDown)

/**
 * Hierarchical, collapsible Layers tree (max 168dp with its own scrollbar): type glyphs, plain
 * display names (raw IDs in tooltips), `Shift+Click` multi-select, and dimmed rows with a
 * `Go to Build N` action for nodes absent on the current beat.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun DocumentLayersTree(
    controller: DocumentElementInspectorController,
    specimens: DeckSpecimenRegistry = controller.specimens,
) {
    val slide = controller.activeSlide() ?: return
    val beat = controller.activeBeat
    val collapsed = remember(slide.id) { mutableStateMapOf<String, Boolean>() }
    val rows = buildLayerRows(slide.nodes, beat, collapsed.filterValues { it }.keys)
    val colors = JewelTheme.globalColors
    val accent = inspectorAccent()
    val scroll = rememberScrollState()

    FlatCollapsibleSection(
        title = "Layers",
        testTag = "ciacola-doc-inspector-node-picker",
        headerTrailing = {
            Text("${rows.size}", style = inspectorCaptionStyle(), color = colors.text.info)
        },
    ) {
        // Snap to whole rows so the list never cuts a row mid-text.
        val treeHeight = layersTreeHeightDp(rows.size)
        Box(Modifier.fillMaxWidth().height(treeHeight.dp).testTag("ciacola-doc-layers-tree")) {
            DisposableEffect(controller) {
                onDispose { controller.layersTreeHasFocus = false }
            }
            val focusRingColor = rememberAccessibleFocusRingColor()
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(scroll)
                    .padding(end = 10.dp)
                    .onFocusChanged { controller.layersTreeHasFocus = it.hasFocus }
                    .focusGroup(),
            ) {
                for (row in rows) {
                    val node = row.node
                    val isSelected = node.id in controller.selectedNodeIds
                    val isPrimary = node.id == controller.selectedNodeId
                    val resolved = JsonMergePatch.resolveNodeAtBeat(node, beat)
                    val typeLabel = InspectorDisplayNames.nodeTypeLabel(node)
                    val name = InspectorDisplayNames.nodeDisplayName(
                        resolved,
                        specimenLabel = resolved.specimen?.ref?.let { specimens.find(it)?.label },
                    )
                    val rowInteraction = remember(node.id) { MutableInteractionSource() }
                    val rowHovered by rowInteraction.collectIsHoveredAsState()
                    val rowFocused by rowInteraction.collectIsFocusedAsState()
                    InspectorTooltip("${slide.id}/${node.id} · $typeLabel") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(LAYER_ROW_HEIGHT_DP.dp)
                                .clip(RoundedCornerShape(InspectorTokens.Corner))
                                .accessibleFocusRing(rowFocused, focusRingColor, cornerRadius = InspectorTokens.Corner)
                                .then(
                                    when {
                                        isPrimary -> Modifier.background(accent.copy(alpha = 0.20f)).border(1.dp, accent, RoundedCornerShape(InspectorTokens.Corner))
                                        isSelected -> Modifier.background(accent.copy(alpha = 0.12f))
                                        else -> Modifier
                                    },
                                )
                                .padding(start = (4 + row.depth * 12).dp, end = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            if (row.hasChildren) {
                                val isCollapsed = collapsed[node.id] == true
                                val toggleInteraction = remember(node.id) { MutableInteractionSource() }
                                val toggleFocused by toggleInteraction.collectIsFocusedAsState()
                                Box(
                                    modifier = Modifier
                                        .size(16.dp)
                                        .clip(RoundedCornerShape(3.dp))
                                        .accessibleFocusRing(toggleFocused, focusRingColor, cornerRadius = 3.dp)
                                        .onKeyEvent { event ->
                                            if (!isUnmodifiedArrowKey(event)) return@onKeyEvent false
                                            if (event.type == KeyEventType.KeyDown) {
                                                if (event.key == Key.DirectionRight) collapsed[node.id] = false
                                                else if (event.key == Key.DirectionLeft) collapsed[node.id] = true
                                            }
                                            true
                                        }
                                        .clickable(
                                            interactionSource = toggleInteraction,
                                            indication = null,
                                            role = Role.Button,
                                            onClick = { collapsed[node.id] = !isCollapsed },
                                        )
                                        .semantics {
                                            contentDescription = if (isCollapsed) "Expand $name" else "Collapse $name"
                                            stateDescription = if (isCollapsed) "Collapsed" else "Expanded"
                                        }
                                        .testTag("ciacola-doc-node-toggle-${node.id}"),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        key = if (isCollapsed) AllIconsKeys.General.ChevronRight else AllIconsKeys.General.ChevronDown,
                                        contentDescription = null,
                                        modifier = Modifier.size(12.dp),
                                    )
                                }
                            } else {
                                Spacer(Modifier.width(12.dp))
                            }
                            Row(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .hoverable(rowInteraction)
                                    .focusable(interactionSource = rowInteraction)
                                    .onKeyEvent { event ->
                                        if (isUnmodifiedArrowKey(event)) {
                                            // Prevent tree navigation arrows from bubbling into stage X/Y nudging.
                                            return@onKeyEvent true
                                        }
                                        if (event.type == KeyEventType.KeyDown &&
                                            (event.key == Key.Enter || event.key == Key.NumPadEnter || event.key == Key.Spacebar)
                                        ) {
                                            controller.selectNode(node.id, additive = event.isShiftPressed)
                                            true
                                        } else {
                                            false
                                        }
                                    }
                                    .clickWithShift(node.id) { shift -> controller.selectNode(node.id, additive = shift) }
                                    .semantics {
                                        role = Role.Button
                                        selected = isSelected
                                        contentDescription = "$name, $typeLabel"
                                        val rowState = buildList {
                                            if (!row.presentOnBeat) add("hidden on current beat")
                                            if (row.hasChildren) add(if (collapsed[node.id] == true) "collapsed" else "expanded")
                                        }.joinToString(", ")
                                        if (rowState.isNotEmpty()) {
                                            stateDescription = rowState
                                        }
                                        onClick { controller.selectNode(node.id); true }
                                    }
                                    .alpha(if (row.presentOnBeat) 1f else 0.45f)
                                    .testTag("ciacola-doc-node-item-${node.id}"),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                InspectorGlyphIcon(layerGlyph(node), tint = colors.text.info, size = 13.dp)
                                Text(
                                    text = name,
                                    style = inspectorBodyStyle(),
                                    fontWeight = if (isPrimary) FontWeight.Medium else FontWeight.Normal,
                                    color = colors.text.normal,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            // Dimming already says "absent"; the link only appears on hover or selection.
                            if (!row.presentOnBeat && row.firstBeat != beat && (rowHovered || isSelected)) {
                                GoToBuildLink(controller, row.firstBeat, tag = "ciacola-doc-node-go-to-build-${node.id}")
                            }
                        }
                    }
                }
            }
            if (rows.size * LAYER_ROW_HEIGHT_DP > LAYERS_MAX_HEIGHT_DP) {
                VerticalScrollbar(
                    scrollState = scroll,
                    modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                )
            }
        }
    }
}

/** Compact `Go to Build N` link calling [DocumentElementInspectorController.onNavigateToBeat]. */
@Composable
internal fun GoToBuildLink(controller: DocumentElementInspectorController, beat: Int, tag: String) {
    val accent = inspectorAccent()
    Text(
        text = "Go to ${buildLabel(beat)}",
        style = inspectorCaptionStyle(),
        color = accent,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(InspectorTokens.Corner))
            .clickable { controller.onNavigateToBeat?.invoke(beat) }
            .semantics { role = Role.Button }
            .padding(horizontal = 4.dp, vertical = 1.dp)
            .testTag(tag),
    )
}
