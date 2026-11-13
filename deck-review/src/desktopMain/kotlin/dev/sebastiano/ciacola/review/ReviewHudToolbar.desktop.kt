package dev.sebastiano.ciacola.review

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.sebastiano.ciacola.ui.rememberReviewStatusPalette
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.Tooltip
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.ui.typography

/**
 * Compact, flat Int UI status bar anchored in the top-right corner of the Story window.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun BoxScope.ReviewHudToolbar(
    inspectActive: Boolean,
    inspectPinned: Boolean,
    timelineDocked: Boolean = false,
    agentConfigured: Boolean = true,
    queuedCount: Int,
    applyingCount: Int,
    appliedCount: Int,
    onHudBoundsChanged: (Rect) -> Unit = {},
    onToggleInspectMode: () -> Unit,
    onToggleTimeline: (() -> Unit)? = null,
    onOpenReviewPanel: () -> Unit,
    onFlushQueued: () -> Unit,
) {
    val globalColors = JewelTheme.globalColors
    val statusPalette = rememberReviewStatusPalette()
    val borderColor = if (inspectActive) statusPalette.inspectAccent else globalColors.borders.normal
    val toolbarShape = RoundedCornerShape(6.dp)

    Row(
        modifier = Modifier
            .align(Alignment.TopEnd)
            .padding(10.dp)
            .onGloballyPositioned { coords ->
                if (coords.isAttached) {
                    onHudBoundsChanged(coords.boundsInRoot())
                }
            }
            .background(globalColors.panelBackground.copy(alpha = 0.92f), toolbarShape)
            .border(1.dp, borderColor, toolbarShape)
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .testTag("ciacola-hud-toolbar"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Tooltip(tooltip = { Text("Toggle Inspect Mode (F3) — or hold Ctrl and click any slide element") }) {
            Row(
                modifier = Modifier
                    .clickable(onClick = onToggleInspectMode)
                    .padding(horizontal = 2.dp, vertical = 2.dp)
                    .testTag("ciacola-hud-toggle-inspect"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Icon(
                    key = AllIconsKeys.General.InspectionsEye,
                    contentDescription = "Inspect slide elements",
                    modifier = Modifier.size(14.dp),
                )
                val label = if (inspectPinned) "Inspecting (F3)" else "Ctrl+Click stage to comment"
                Text(
                    text = label,
                    style = JewelTheme.typography.small,
                    color = if (inspectActive) {
                        statusPalette.inspectAccent
                    } else {
                        globalColors.text.info
                    },
                )
            }
        }

        if (applyingCount > 0) {
            Tooltip(tooltip = { Text("Open Review panel (F4) — $applyingCount comment(s) applying") }) {
                Row(
                    modifier = Modifier
                        .clickable(onClick = onOpenReviewPanel)
                        .padding(horizontal = 3.dp, vertical = 2.dp)
                        .testTag("ciacola-hud-applying-badge"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Icon(
                        key = AllIconsKeys.Actions.Refresh,
                        contentDescription = "Applying comments",
                        modifier = Modifier.size(12.dp),
                    )
                    Text(
                        text = "$applyingCount applying…",
                        style = JewelTheme.typography.small,
                        color = statusPalette.applying,
                    )
                }
            }
        }

        if (onToggleTimeline != null) {
            Tooltip(tooltip = { Text("Toggle After Effects Timeline Studio (F5)") }) {
                Row(
                    modifier = Modifier
                        .clickable(onClick = onToggleTimeline)
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                        .testTag("ciacola-hud-toggle-timeline"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        key = AllIconsKeys.Actions.Preview,
                        contentDescription = "Toggle Timeline Studio",
                        modifier = Modifier.size(13.dp),
                    )
                    Text(
                        text = if (timelineDocked) "Timeline ●" else "Timeline",
                        style = JewelTheme.typography.small,
                        color = if (timelineDocked) statusPalette.inspectAccent else globalColors.text.info,
                    )
                }
            }
        }

        if (appliedCount > 0) {
            Tooltip(tooltip = { Text("Open Review panel (F4) — $appliedCount applied") }) {
                Row(
                    modifier = Modifier
                        .clickable(onClick = onOpenReviewPanel)
                        .padding(horizontal = 3.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Icon(
                        key = AllIconsKeys.General.GreenCheckmark,
                        contentDescription = "Applied comments",
                        modifier = Modifier.size(12.dp),
                    )
                    Text(
                        text = appliedCount.toString(),
                        style = JewelTheme.typography.small,
                        color = statusPalette.applied,
                    )
                }
            }
        }

        if (queuedCount > 0) {
            if (agentConfigured) {
                Tooltip(tooltip = { Text("Dispatch $queuedCount queued comment(s) to agent") }) {
                    DefaultButton(
                        onClick = onFlushQueued,
                        modifier = Modifier.testTag("ciacola-hud-send-queued"),
                    ) {
                        Text("Send $queuedCount", style = JewelTheme.typography.small)
                    }
                }
            } else {
                Tooltip(
                    tooltip = {
                        Text("No Conversation ID configured — open Review panel (F4) to connect an agent for $queuedCount queued comment(s)")
                    },
                ) {
                    OutlinedButton(
                        onClick = onOpenReviewPanel,
                        modifier = Modifier.testTag("ciacola-hud-send-queued"),
                    ) {
                        Text("Set Agent ($queuedCount)", style = JewelTheme.typography.small)
                    }
                }
            }
        }
    }
}
