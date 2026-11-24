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
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
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
 * `true` while the Studio App Bar owns navigation, review mode, and the timeline toggle. The
 * floating stage HUD then retires: it only reappears to surface agent dispatch status (queued,
 * applying, applied comments), never the inspect pill or the timeline toggle.
 */
val LocalReviewHudOwnedByAppBar = compositionLocalOf { false }

private val isMacOs: Boolean = System.getProperty("os.name").orEmpty().lowercase().contains("mac")

/** Platform-correct modifier name for click-to-comment (`⌃` on macOS, `Ctrl` elsewhere). */
internal val reviewClickModifierLabel: String = if (isMacOs) "⌃-Click" else "Ctrl+Click"

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
    pushAvailable: Boolean = true,
    queuedCount: Int,
    applyingCount: Int,
    deliveringCount: Int = 0,
    appliedCount: Int,
    onHudBoundsChanged: (Rect) -> Unit = {},
    onToggleInspectMode: () -> Unit,
    onToggleTimeline: (() -> Unit)? = null,
    onOpenReviewPanel: () -> Unit,
    onFlushQueued: () -> Unit,
) {
    val appBarOwned = LocalReviewHudOwnedByAppBar.current
    if (appBarOwned && queuedCount == 0 && applyingCount == 0 && appliedCount == 0) {
        SideEffect { onHudBoundsChanged(Rect.Zero) }
        return
    }
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
        if (!appBarOwned) {
            Tooltip(tooltip = { Text("Toggle Inspect Mode (F3) — or $reviewClickModifierLabel any slide element") }) {
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
                    val label = if (inspectPinned) "Inspecting (F3)" else "$reviewClickModifierLabel stage to comment"
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
        }

        if (applyingCount > 0) {
            val applyingLabel = if (deliveringCount > 0) {
                "sending $deliveringCount…"
            } else {
                "$applyingCount with the agent"
            }
            Tooltip(tooltip = { Text("Open Review panel (F4) — $applyingLabel") }) {
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
                        text = applyingLabel,
                        style = JewelTheme.typography.small,
                        color = statusPalette.applying,
                    )
                }
            }
        }

        if (onToggleTimeline != null && !appBarOwned) {
            Tooltip(tooltip = { Text("Toggle Timeline (F5)") }) {
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
            // The count is a state, not a chore: these comments are already where an agent reading
            // the queue will find them. Pushing is the optional shortcut, so it is the button and
            // the waiting is the label, rather than the other way round.
            Tooltip(
                tooltip = {
                    Text("$queuedCount comment(s) waiting — an agent on :deck:reviewWait picks them up")
                },
            ) {
                Row(
                    modifier = Modifier
                        .clickable(onClick = onOpenReviewPanel)
                        .padding(horizontal = 3.dp, vertical = 2.dp)
                        .testTag("ciacola-hud-queued-badge"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Icon(
                        key = AllIconsKeys.Actions.Upload,
                        contentDescription = "Comments waiting for an agent",
                        modifier = Modifier.size(12.dp),
                    )
                    Text(
                        text = "$queuedCount waiting",
                        style = JewelTheme.typography.small,
                        color = statusPalette.queued,
                    )
                }
            }
            // No push command on this machine means Send can only land the comment back where
            // Queue already put it. Offering the button anyway is the dead end that made Send and
            // Queue feel like the same click with different wording.
            if (pushAvailable) {
                if (agentConfigured) {
                    Tooltip(tooltip = { Text("Push $queuedCount waiting comment(s) to the agent now") }) {
                        DefaultButton(
                            onClick = onFlushQueued,
                            modifier = Modifier.testTag("ciacola-hud-send-queued"),
                        ) {
                            Text("Send now", style = JewelTheme.typography.small)
                        }
                    }
                } else {
                    Tooltip(
                        tooltip = {
                            Text("No Conversation ID configured — open Review panel (F4) to push instead of waiting")
                        },
                    ) {
                        OutlinedButton(
                            onClick = onOpenReviewPanel,
                            modifier = Modifier.testTag("ciacola-hud-send-queued"),
                        ) {
                            Text("Set Agent", style = JewelTheme.typography.small)
                        }
                    }
                }
            }
        }
    }
}
