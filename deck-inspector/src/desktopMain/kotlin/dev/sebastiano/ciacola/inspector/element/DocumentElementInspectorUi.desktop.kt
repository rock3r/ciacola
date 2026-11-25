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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.sebastiano.ciacola.inspector.design.InspectorTokens
import dev.sebastiano.ciacola.inspector.design.InspectorTooltip
import dev.sebastiano.ciacola.inspector.design.inspectorBodyStyle
import dev.sebastiano.ciacola.inspector.design.inspectorCaptionStyle
import dev.sebastiano.ciacola.inspector.design.inspectorSecondaryTextColor
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import kotlinx.coroutines.delay
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.VerticalScrollbar
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/** Copies [text] to the system clipboard; returns `false` when no clipboard is available (headless). */
fun copyTextToSystemClipboard(text: String): Boolean = runCatching {
    Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
    true
}.getOrDefault(false)

/**
 * Document-backed **Design** inspector bound to [DocumentElementInspectorController].
 *
 * Flat collapsible sections (Layers, Parameters, Layout, Fill & Stroke / Surface, Text) and a
 * copyable `slideId/nodeId` row at the bottom. Undo/Redo live in the App Bar; pass
 * [showHeaderUndoRedo] only when hosting the inspector standalone.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DocumentElementInspectorUi(
    controller: DocumentElementInspectorController,
    modifier: Modifier = Modifier,
    showHeaderUndoRedo: Boolean = false,
    onCopyText: (String) -> Boolean = ::copyTextToSystemClipboard,
) {
    controller.store.revision
    controller.store.document

    val colors = JewelTheme.globalColors
    val scroll = rememberScrollState()
    val resolvedNode = controller.selectedResolvedNode()
    val isPresent = controller.isSelectedNodePresentOnBeat()
    val targets = controller.editTargets()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.panelBackground)
            // Bubble phase: focused sliders and fields consume their own arrow keys first.
            .onKeyEvent { controller.handleKeyEvent(it) }
            .testTag("ciacola-doc-element-inspector-root"),
    ) {
        if (showHeaderUndoRedo || resolvedNode != null) {
            SelectionHeader(controller, showHeaderUndoRedo)
            Divider(Orientation.Horizontal, Modifier.fillMaxWidth())
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .testTag("ciacola-doc-element-inspector"),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scroll)
                    .padding(bottom = 32.dp, end = 8.dp),
            ) {
                RemapNoticeBanner(controller)
                DocumentLayersTree(controller)

                when {
                    resolvedNode == null -> Text(
                        text = "Select an element on the stage or in Layers. Shift-click or drag a marquee to select several.",
                        style = inspectorBodyStyle(),
                        color = inspectorSecondaryTextColor(),
                        modifier = Modifier.padding(horizontal = InspectorTokens.SectionHorizontalPadding, vertical = 12.dp),
                    )

                    !isPresent -> NotPresentBanner(controller, resolvedNode.id)

                    else -> {
                        val specimenDecl = controller.selectedSpecimenDeclaration()
                        if (resolvedNode.specimen != null && specimenDecl != null) {
                            DocumentSpecimenParamsSection(controller = controller, node = resolvedNode, declaration = specimenDecl)
                        }
                        DocumentLayoutSection(controller = controller, targets = targets)
                        DocumentFillStrokeSurfaceSection(controller = controller, node = resolvedNode)
                        if (resolvedNode.text != null) {
                            DocumentTextSection(controller = controller, node = resolvedNode, targets = targets)
                        }
                    }
                }

                val slideId = controller.activeSlide()?.id
                if (resolvedNode != null && slideId != null) {
                    RawIdCopyRow(reference = InspectorDisplayNames.rawReference(slideId, resolvedNode.id), onCopyText = onCopyText)
                }
            }

            if (scroll.value > 0) {
                // Fade scrolled content out under the opaque header so no half-clipped row shows.
                Box(
                    Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .height(16.dp)
                        .background(Brush.verticalGradient(listOf(colors.panelBackground, colors.panelBackground.copy(alpha = 0f))))
                        .testTag("ciacola-doc-inspector-scroll-fade"),
                )
            }
            if (scroll.canScrollForward) {
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(20.dp)
                        .background(Brush.verticalGradient(listOf(colors.panelBackground.copy(alpha = 0f), colors.panelBackground)))
                        .testTag("ciacola-doc-inspector-bottom-fade"),
                )
            }
            VerticalScrollbar(
                scrollState = scroll,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .padding(vertical = 4.dp, horizontal = 2.dp),
            )
        }
    }
}

@Composable
private fun SelectionHeader(controller: DocumentElementInspectorController, showHeaderUndoRedo: Boolean) {
    val colors = JewelTheme.globalColors
    val resolved = controller.selectedResolvedNode()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.panelBackground)
            .padding(horizontal = InspectorTokens.SectionHorizontalPadding, vertical = 8.dp)
            .testTag("ciacola-doc-inspector-sticky-header"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            if (resolved != null) {
                val name = InspectorDisplayNames.nodeDisplayName(
                    resolved,
                    specimenLabel = controller.selectedSpecimenDeclaration()?.label,
                )
                Text(
                    text = name,
                    style = inspectorBodyStyle().copy(fontSize = InspectorTokens.Title),
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("ciacola-doc-inspector-selected-label"),
                )
                val subtitle = inspectorSelectionSubtitle(
                    name = name,
                    typeLabel = InspectorDisplayNames.nodeTypeLabel(resolved),
                    selectedCount = controller.selectedNodeIds.size,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = inspectorCaptionStyle(),
                        color = colors.text.info,
                        maxLines = 1,
                        modifier = Modifier.testTag("ciacola-doc-inspector-selected-kind"),
                    )
                }
            }
        }
        if (showHeaderUndoRedo) {
            OutlinedButton(
                onClick = { controller.performUndo() },
                enabled = controller.store.canUndo,
                modifier = Modifier.testTag("ciacola-doc-inspector-undo"),
            ) { Text("Undo", style = inspectorBodyStyle()) }
            OutlinedButton(
                onClick = { controller.performRedo() },
                enabled = controller.store.canRedo,
                modifier = Modifier.testTag("ciacola-doc-inspector-redo"),
            ) { Text("Redo", style = inspectorBodyStyle()) }
        }
    }
}

/**
 * Header subtitle under the selection name: the type (`Text`), or `Text · 2 selected`; omitted when
 * the name already starts with the type (`Group · 17 items` needs no second `Group`).
 */
internal fun inspectorSelectionSubtitle(name: String, typeLabel: String, selectedCount: Int): String? = when {
    selectedCount > 1 -> "$typeLabel · $selectedCount selected"
    name.startsWith(typeLabel) -> null
    else -> typeLabel
}

/** `“Title” isn’t on Base.`; generic names (`Group · 17 items`) read `This group isn’t on Base.` */
internal fun absentNodeNotice(name: String, typeLabel: String?, build: String): String =
    if (typeLabel != null && name.startsWith(typeLabel)) {
        "This ${typeLabel.lowercase()} isn\u2019t on $build."
    } else {
        "\u201C$name\u201D isn\u2019t on $build."
    }

@Composable
private fun NotPresentBanner(controller: DocumentElementInspectorController, nodeId: String) {
    val colors = JewelTheme.globalColors
    val base = controller.selectedBaseNode()
    val firstBeat = base?.let(InspectorDisplayNames::firstPresentBeat) ?: 0
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = InspectorTokens.SectionHorizontalPadding, vertical = 10.dp)
            .testTag("ciacola-doc-node-not-present"),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val name = base?.let { InspectorDisplayNames.nodeDisplayName(it) } ?: nodeId
        Text(
            text = absentNodeNotice(
                name = name,
                typeLabel = base?.let { InspectorDisplayNames.nodeTypeLabel(it) },
                build = buildLabel(controller.activeBeat),
            ),
            style = inspectorBodyStyle(),
            color = colors.text.normal,
        )
        if (firstBeat != controller.activeBeat) {
            OutlinedButton(
                onClick = { controller.onNavigateToBeat?.invoke(firstBeat) },
                enabled = controller.onNavigateToBeat != null,
                modifier = Modifier.testTag("ciacola-doc-go-to-build"),
            ) { Text("Go to ${buildLabel(firstBeat)}", style = inspectorBodyStyle()) }
        }
    }
}

@Composable
private fun RemapNoticeBanner(controller: DocumentElementInspectorController) {
    if (controller.remapNotices.isEmpty()) return
    val colors = JewelTheme.globalColors
    val warning = colors.outlines.warning
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(InspectorTokens.SectionHorizontalPadding, 8.dp)
            .clip(RoundedCornerShape(InspectorTokens.Corner))
            .border(1.dp, warning.copy(alpha = 0.7f), RoundedCornerShape(InspectorTokens.Corner))
            .padding(8.dp)
            .testTag("ciacola-remap-notice-banner"),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Manual fragment delays updated (${controller.remapNotices.size})",
                style = inspectorBodyStyle(),
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(
                onClick = { controller.dismissRemapNotices() },
                modifier = Modifier.testTag("ciacola-remap-notice-dismiss"),
            ) { Text("Dismiss", style = inspectorBodyStyle()) }
        }
        for (notice in controller.remapNotices.take(4)) {
            Text("• ${notice.oldAddress}: ${notice.reason}", style = inspectorCaptionStyle(), color = colors.text.info)
        }
    }
}

/**
 * Subtle, one-click copyable monospace `slideId/nodeId` reference so authors can point an agent
 * at an exact node. Shows a brief `Copied` state after copying.
 */
@Composable
internal fun RawIdCopyRow(reference: String, onCopyText: (String) -> Boolean) {
    val colors = JewelTheme.globalColors
    var copied by remember(reference) { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1_600)
            copied = false
        }
    }
    InspectorTooltip("Copy the node reference to paste into an agent prompt") {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = InspectorTokens.SectionHorizontalPadding, vertical = 10.dp)
                .clip(RoundedCornerShape(InspectorTokens.Corner))
                .clickable {
                    onCopyText(reference)
                    copied = true
                }
                .semantics {
                    role = Role.Button
                    contentDescription = "Copy $reference"
                }
                .padding(horizontal = 4.dp, vertical = 3.dp)
                .testTag("ciacola-doc-raw-id-row"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = reference,
                style = inspectorCaptionStyle().copy(fontFamily = FontFamily.Monospace),
                color = inspectorSecondaryTextColor(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).testTag("ciacola-doc-raw-id-text"),
            )
            if (copied) {
                Text("Copied", style = inspectorCaptionStyle(), color = colors.text.normal, modifier = Modifier.testTag("ciacola-doc-raw-id-copied"))
            }
            Icon(AllIconsKeys.Actions.Copy, contentDescription = null, modifier = Modifier.size(13.dp).testTag("ciacola-doc-raw-id-copy"))
        }
    }
}
