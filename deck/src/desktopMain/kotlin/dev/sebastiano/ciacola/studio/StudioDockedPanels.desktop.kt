package dev.sebastiano.ciacola.studio

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
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
import androidx.compose.ui.unit.dp
import dev.bnorm.storyboard.easel.Animatic
import dev.sebastiano.ciacola.inspector.design.DocumentThemeInspectorUi
import dev.sebastiano.ciacola.inspector.lint.DocumentDeckLintPanel
import dev.sebastiano.ciacola.inspector.lint.DocumentLintIssue
import dev.sebastiano.ciacola.ui.accessibleFocusRing
import dev.sebastiano.ciacola.ui.rememberAccessibleFocusRingColor
import java.awt.Cursor
import java.nio.file.Path
import kotlinx.coroutines.CoroutineScope
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.ui.typography

private const val THEME_SHEET_WIDTH_DP = 380
private const val DEFAULT_ISSUES_DOCK_HEIGHT_DP = 260f
private const val MIN_ISSUES_DOCK_HEIGHT_DP = 140f
private const val MAX_ISSUES_DOCK_HEIGHT_DP = 560f

/**
 * Deck-level Theme sheet (`F7`): a right-aligned elevated overlay over the workspace with the
 * `Colors / Typography / Motion defaults / Palettes` editor. It never replaces the selection-scoped
 * `Design | Animate` inspector tabs. Close with `×`, `Esc`, or `F7`.
 */
@Composable
fun StudioThemeSheet(
    workspace: StudioWorkspaceController,
    onPickDeckFileToCopyThemes: (() -> Path?)?,
    onPickCodeSchemeFile: (() -> Path?)? = null,
    modifier: Modifier = Modifier,
) {
    val globalColors = JewelTheme.globalColors
    val shape = RoundedCornerShape(topStart = 8.dp, bottomStart = 8.dp)
    Column(
        modifier = modifier
            .width(THEME_SHEET_WIDTH_DP.dp)
            .fillMaxHeight()
            .padding(top = 6.dp, bottom = 6.dp)
            .shadow(elevation = 14.dp, shape = shape)
            .background(globalColors.panelBackground, shape)
            .testTag("ciacola-theme-sheet"),
    ) {
        PanelHeader(
            title = "Deck Theme",
            subtitle = "Applies to every slide",
            closeTooltip = "Close deck theme (Esc / F7)",
            closeTestTag = "ciacola-theme-sheet-close",
            onClose = { workspace.themeSheetOpen = false },
        )
        Divider(Orientation.Horizontal, Modifier.fillMaxWidth())
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            DocumentThemeInspectorUi(
                controller = workspace.themeController,
                onPickDeckFileToCopyThemes = onPickDeckFileToCopyThemes,
                onPickCodeSchemeFile = onPickCodeSchemeFile,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * Compact `Timeline | Issues` tab switcher rendered in the leading slot of the docked bottom split
 * (`DocumentTimelineWindow.bottomDockHeaderLeading`) so Timeline tracks and Issues share one split
 * pane instead of stacking two bottom panels.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun StudioBottomDockTabStrip(
    issuesActive: Boolean,
    errorCount: Int,
    warningCount: Int,
    onSelectTimeline: () -> Unit,
    onSelectIssues: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusRingColor = rememberAccessibleFocusRingColor()
    // Counts live in the App Bar badge and the Issues filter pills; the tab is just `Issues`.
    val issuesLabel = "Issues"
    Row(
        modifier = modifier
            .selectableGroup()
            .testTag("ciacola-bottom-dock-tabs"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        BottomDockTabChip(
            label = "Timeline",
            selected = !issuesActive,
            focusRingColor = focusRingColor,
            testTag = "ciacola-bottom-dock-tab-timeline",
            onClick = onSelectTimeline,
        )
        BottomDockTabChip(
            label = issuesLabel,
            selected = issuesActive,
            focusRingColor = focusRingColor,
            testTag = "ciacola-bottom-dock-tab-issues",
            onClick = onSelectIssues,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BottomDockTabChip(
    label: String,
    selected: Boolean,
    focusRingColor: Color,
    testTag: String,
    onClick: () -> Unit,
) {
    val globalColors = JewelTheme.globalColors
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val hovered by interaction.collectIsHoveredAsState()
    val shape = RoundedCornerShape(4.dp)
    val bg = when {
        selected -> globalColors.outlines.focused.copy(alpha = 0.16f)
        hovered -> globalColors.borders.normal.copy(alpha = 0.35f)
        else -> Color.Transparent
    }
    val border = if (selected) globalColors.outlines.focused else globalColors.borders.normal
    Box(
        modifier = Modifier
            .height(24.dp)
            .clip(shape)
            .background(bg, shape)
            .border(1.dp, border, shape)
            .accessibleFocusRing(focused, focusRingColor, cornerRadius = 4.dp)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Tab,
                onClick = onClick,
            )
            .semantics { this.selected = selected }
            .padding(horizontal = 8.dp)
            .testTag(testTag),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = JewelTheme.typography.regular.copy(
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                fontFeatureSettings = "tnum",
            ),
            color = if (selected) globalColors.text.normal else globalColors.text.info,
            maxLines = 1,
        )
    }
}

/** Trailing summary + close button rendered on the docked bottom header when Issues is active. */
@Composable
fun StudioBottomIssuesDockHeaderTrailing(
    workspace: StudioWorkspaceController,
) {
    // Counts live in the Issues filter pills only (no duplicate header summary).
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AppBarButton(
            icon = AllIconsKeys.Actions.Close,
            label = null,
            tooltip = "Close issues (F8)",
            testTag = "ciacola-issues-dock-close",
            onClick = { workspace.issuesDockOpen = false },
        )
    }
}

internal fun rawSlideIndexForPresented(
    workspace: StudioWorkspaceController,
    animatic: Animatic,
): Int {
    val slides = workspace.store.document.slides
    val sceneIdx = animatic.presentedIndex.sceneIndex.coerceAtLeast(0)
    val activeSlideId = slides.filterNot { it.skipped }.getOrNull(sceneIdx)?.id ?: return sceneIdx
    return slides.indexOfFirst { it.id == activeSlideId }.takeIf { it >= 0 } ?: sceneIdx
}

internal fun nonSkippedSceneIndexForIssue(
    workspace: StudioWorkspaceController,
    issue: DocumentLintIssue,
): Int? {
    val nonSkipped = workspace.store.document.slides.filterNot { it.skipped }
    val idx = nonSkipped.indexOfFirst { it.id == issue.slideId }
    return idx.takeIf { it >= 0 }
}

/** Issues panel body hosted inside `DocumentTimelineWindow.alternateBottomContent`. */
@Composable
fun StudioBottomIssuesDockBody(
    workspace: StudioWorkspaceController,
    animatic: Animatic,
    scope: CoroutineScope,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(JewelTheme.globalColors.panelBackground)
            .testTag("ciacola-issues-dock"),
    ) {
        DocumentDeckLintPanel(
            controller = workspace.lintController,
            currentSlideIndex = rawSlideIndexForPresented(workspace, animatic),
            onJumpToIssue = { issue: DocumentLintIssue ->
                workspace.openNodeInElementInspector(
                    nodeId = issue.nodeId,
                    animatic = animatic,
                    scope = scope,
                    sceneIndex = nonSkippedSceneIndexForIssue(workspace, issue),
                    beatIndex = issue.beatIndex,
                )
            },
            compactHorizontal = true,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/**
 * Standalone bottom Issues panel (`F8` / App Bar Issues badge) used when the timeline is not docked
 * in the Story window (e.g. Review mode or detached F6 timeline).
 */
@Composable
fun StudioIssuesDock(
    workspace: StudioWorkspaceController,
    animatic: Animatic,
    scope: CoroutineScope,
    modifier: Modifier = Modifier,
) {
    var heightDp by rememberSaveable { mutableFloatStateOf(DEFAULT_ISSUES_DOCK_HEIGHT_DP) }
    val globalColors = JewelTheme.globalColors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .height(heightDp.dp)
            .background(globalColors.panelBackground)
            .testTag("ciacola-issues-dock"),
    ) {
        IssuesDockSplitter(onDrag = { deltaDp ->
            heightDp = (heightDp - deltaDp).coerceIn(MIN_ISSUES_DOCK_HEIGHT_DP, MAX_ISSUES_DOCK_HEIGHT_DP)
        })
        PanelHeader(
            title = "Issues",
            closeTooltip = "Close issues (F8)",
            closeTestTag = "ciacola-issues-dock-close",
            onClose = { workspace.issuesDockOpen = false },
        )
        Divider(Orientation.Horizontal, Modifier.fillMaxWidth())
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            DocumentDeckLintPanel(
                controller = workspace.lintController,
                currentSlideIndex = rawSlideIndexForPresented(workspace, animatic),
                onJumpToIssue = { issue: DocumentLintIssue ->
                    workspace.openNodeInElementInspector(
                        nodeId = issue.nodeId,
                        animatic = animatic,
                        scope = scope,
                        sceneIndex = nonSkippedSceneIndexForIssue(workspace, issue),
                        beatIndex = issue.beatIndex,
                    )
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun IssuesDockSplitter(onDrag: (Float) -> Unit) {
    val density = LocalDensity.current
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val globalColors = JewelTheme.globalColors
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(5.dp)
            .background(if (hovered) globalColors.outlines.focused else globalColors.borders.normal)
            .hoverable(hover)
            .pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(Cursor.N_RESIZE_CURSOR)))
            .pointerInput(density) {
                detectVerticalDragGestures { change, dragAmount ->
                    change.consume()
                    onDrag(with(density) { dragAmount.toDp().value })
                }
            }
            .testTag("ciacola-issues-dock-splitter"),
    )
}

@Composable
private fun PanelHeader(
    title: String,
    closeTooltip: String,
    subtitle: String? = null,
    closeTestTag: String,
    onClose: () -> Unit,
) {
    val globalColors = JewelTheme.globalColors
    Row(
        modifier = Modifier.fillMaxWidth().height(34.dp).padding(start = 12.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = title,
            style = JewelTheme.typography.regular.copy(fontWeight = FontWeight.SemiBold),
            color = globalColors.text.normal,
            maxLines = 1,
            modifier = if (subtitle == null) Modifier.weight(1f) else Modifier,
        )
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = JewelTheme.typography.regular.copy(fontFeatureSettings = "tnum"),
                color = globalColors.text.info,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
        }
        AppBarButton(
            icon = AllIconsKeys.Actions.Close,
            label = null,
            tooltip = closeTooltip,
            testTag = closeTestTag,
            onClick = onClose,
        )
    }
}
