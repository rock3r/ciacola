package dev.sebastiano.ciacola.studio

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.bnorm.storyboard.easel.Animatic
import dev.sebastiano.ciacola.inspector.element.DocumentElementInspectorUi
import dev.sebastiano.ciacola.timeline.DocumentTimelineMotionInspector
import dev.sebastiano.ciacola.ui.accessibleFocusRing
import dev.sebastiano.ciacola.ui.rememberAccessibleFocusRingColor
import java.awt.Cursor
import java.nio.file.Path
import kotlinx.coroutines.CoroutineScope
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.Tooltip
import org.jetbrains.jewel.ui.typography

/**
 * Resizable right-hand Studio inspector with two equal-width tabs, `Design | Animate`, that both
 * follow the current selection. Deck-level theme editing lives in the App Bar Theme sheet and lint
 * findings live in the bottom Issues dock, so neither hijacks these tabs.
 *
 * Renders nothing while the Overview is open so the Overview takes the full window.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
@Suppress("UNUSED_PARAMETER")
fun StudioUnifiedInspectorPane(
    workspace: StudioWorkspaceController,
    animatic: Animatic,
    scope: CoroutineScope,
    // Kept for source compatibility; the deck-level Theme sheet owns theme file import now.
    onPickDeckFileToCopyThemes: (() -> Path?)? = null,
    modifier: Modifier = Modifier,
) {
    if (workspace.overviewOpen) return

    StudioInspectorSplitter(workspace)

    Column(
        modifier = modifier
            .width(workspace.effectiveInspectorWidthDp.dp)
            .fillMaxHeight()
            .background(JewelTheme.globalColors.panelBackground)
            .testTag("ciacola-studio-unified-inspector"),
    ) {
        StudioInspectorTabRow(
            activeTab = workspace.activeTab,
            onSelectTab = { workspace.activeTab = it },
        )

        Divider(Orientation.Horizontal, Modifier.fillMaxWidth())

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when (workspace.activeTab) {
                StudioInspectorTab.Design -> DocumentElementInspectorUi(
                    controller = workspace.elementController,
                    modifier = Modifier.fillMaxSize(),
                )

                StudioInspectorTab.Animate -> DocumentTimelineMotionInspector(
                    controller = workspace.timelineController,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StudioInspectorSplitter(workspace: StudioWorkspaceController) {
    val density = LocalDensity.current
    val globalColors = JewelTheme.globalColors
    val splitterHover = remember { MutableInteractionSource() }
    val isSplitterHovered by splitterHover.collectIsHoveredAsState()
    val splitterColor = if (isSplitterHovered) globalColors.outlines.focused else globalColors.borders.normal

    Tooltip(tooltip = { Text("Drag to resize the inspector (double-click to reset)") }) {
        Box(
            modifier = Modifier
                .width(5.dp)
                .fillMaxHeight()
                .background(splitterColor)
                .pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(Cursor.E_RESIZE_CURSOR)))
                .combinedClickable(
                    interactionSource = splitterHover,
                    indication = null,
                    onClick = {},
                    onDoubleClick = {
                        workspace.inspectorWidthDp = DEFAULT_STUDIO_INSPECTOR_WIDTH_DP
                        workspace.onInspectorWidthPersisted?.invoke(DEFAULT_STUDIO_INSPECTOR_WIDTH_DP)
                    },
                )
                .pointerInput(density) {
                    detectHorizontalDragGestures(
                        onDragEnd = { workspace.onInspectorWidthPersisted?.invoke(workspace.inspectorWidthDp) },
                        onDragCancel = { workspace.onInspectorWidthPersisted?.invoke(workspace.inspectorWidthDp) },
                    ) { change, dragAmount ->
                        change.consume()
                        val deltaDp = with(density) { dragAmount.toDp().value }
                        workspace.inspectorWidthDp = (workspace.inspectorWidthDp - deltaDp)
                            .coerceIn(MIN_STUDIO_INSPECTOR_WIDTH_DP, MAX_STUDIO_INSPECTOR_WIDTH_DP)
                    }
                }
                .testTag("ciacola-studio-inspector-splitter"),
        )
    }
}

/**
 * Two equal-width `Design | Animate` tabs. The `weight(1f)` sits on the direct `Row` child (the
 * tooltip host), never inside `Tooltip { }`, so each tab genuinely spans half the panel.
 */
@Composable
internal fun StudioInspectorTabRow(
    activeTab: StudioInspectorTab,
    onSelectTab: (StudioInspectorTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusRingColor = rememberAccessibleFocusRingColor()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(36.dp)
            .selectableGroup()
            .padding(horizontal = 8.dp, vertical = 5.dp)
            .testTag("ciacola-studio-tab-row"),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (tab in StudioInspectorTab.entries) {
            StudioInspectorTabPill(
                tab = tab,
                selected = activeTab == tab,
                focusRingColor = focusRingColor,
                onClick = { onSelectTab(tab) },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RowScope.StudioInspectorTabPill(
    tab: StudioInspectorTab,
    selected: Boolean,
    focusRingColor: Color,
    onClick: () -> Unit,
) {
    val globalColors = JewelTheme.globalColors
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val shape = RoundedCornerShape(5.dp)
    val bg = if (selected) globalColors.outlines.focused.copy(alpha = 0.16f) else Color.Transparent
    val border = if (selected) globalColors.outlines.focused else Color.Transparent

    Box(
        modifier = Modifier
            .weight(1f)
            .fillMaxHeight()
            .testTag("ciacola-studio-tab-slot-${tab.name.lowercase()}"),
    ) {
        // Unmerged sibling alias so automation addressing the pre-overhaul tab names
        // (`element` / `motion`) resolves in merged semantics too (`.clickable` merges descendants).
        Box(
            modifier = Modifier
                .fillMaxSize()
                .testTag("ciacola-studio-tab-${tab.legacyTagSuffix()}"),
        )
        Tooltip(tooltip = { Text(tab.tooltip) }) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(shape)
                    .background(bg, shape)
                    .border(1.dp, border, shape)
                    .accessibleFocusRing(focused, focusRingColor, cornerRadius = 5.dp)
                    .pointerHoverIcon(PointerIcon.Hand)
                    .clickable(
                        interactionSource = interactionSource,
                        indication = null,
                        role = Role.Tab,
                        onClick = onClick,
                    )
                    .semantics { this.selected = selected }
                    .testTag("ciacola-studio-tab-${tab.name.lowercase()}"),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = tab.label,
                    style = JewelTheme.typography.regular.copy(
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    ),
                    color = if (selected) globalColors.text.normal else globalColors.text.info,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun StudioInspectorTab.legacyTagSuffix(): String = when (this) {
    StudioInspectorTab.Design -> "element"
    StudioInspectorTab.Animate -> "motion"
}
