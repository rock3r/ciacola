package dev.sebastiano.ciacola.presenter

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties
import dev.bnorm.storyboard.AdvanceDirection
import dev.sebastiano.ciacola.ui.accessibleFocusRing
import dev.sebastiano.ciacola.ui.rememberAccessibleFocusRingColor
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.IconButton
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.PopupContainer
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.Tooltip
import org.jetbrains.jewel.ui.icon.IconKey
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.ui.typography

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun PresenterTopControlBar(
    controller: PresenterController,
    wallClock: String,
    elapsedSeconds: Long,
    palette: PresenterStatusPalette,
    displayPopupOpen: Boolean,
    onDisplayPopupOpenChange: (Boolean) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val globalColors = JewelTheme.globalColors
    val resolvedNotes = controller.resolvedCurrentNotes()
    val displays = controller.availableDisplays

    val slideLabel = "Slide ${controller.currentSceneIndex + 1} / ${controller.slideCount.coerceAtLeast(1)}" +
        " · Beat ${controller.currentBeatIndex + 1} / ${controller.currentBeatCount}"
    val pace = controller.timer.paceStatus(
        elapsedSeconds = elapsedSeconds,
        flatBeatIndex = controller.flatBeatIndex,
        totalBeats = controller.totalFlatBeats,
    )
    val timerRunning = controller.timer.runState == TimerRunState.Running

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(globalColors.toolwindowBackground)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // Row 1: Slide & Beat navigation, scene title, stage status, and live stage aids.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                modifier = Modifier.weight(1f, fill = false),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                PresenterIconButton(
                    icon = AllIconsKeys.General.ChevronLeft,
                    contentDescription = "Previous beat",
                    tooltip = "Previous beat (← / ↑ / PgUp / Backspace)",
                    enabled = controller.canStepBackward,
                    testTag = "ciacola-presenter-prev-beat",
                    onClick = { controller.step(scope, AdvanceDirection.Backward) },
                )
                PresenterIconButton(
                    icon = AllIconsKeys.General.ChevronRight,
                    contentDescription = "Next beat",
                    tooltip = "Next beat (→ / ↓ / PgDn / Space / Enter)",
                    enabled = controller.canStepForward,
                    testTag = "ciacola-presenter-next-beat",
                    onClick = { controller.step(scope, AdvanceDirection.Forward) },
                )

                Text(
                    text = slideLabel,
                    style = JewelTheme.typography.medium.copy(
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Monospace,
                    ),
                    color = globalColors.text.normal,
                    maxLines = 1,
                    modifier = Modifier.testTag("ciacola-presenter-slide-beat"),
                )

                Tooltip(tooltip = { Text(resolvedNotes.sceneTitle) }) {
                    Text(
                        text = "· ${resolvedNotes.sceneTitle}",
                        style = JewelTheme.typography.medium,
                        color = globalColors.text.info,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .widthIn(max = 200.dp)
                            .testTag("ciacola-presenter-scene-title"),
                    )
                }

                StageStatusPill(
                    isTransitioning = controller.isTransitioning,
                    palette = palette,
                )

                if (controller.curtain != StageCurtain.None) {
                    val curtainLabel = when (controller.curtain) {
                        StageCurtain.Blackout -> "Blackout (B)"
                        StageCurtain.Whiteout -> "Whiteout (W)"
                        StageCurtain.None -> ""
                    }
                    Tooltip(tooltip = { Text("Click or press Esc / B / W to resume live stage") }) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(palette.curtainActiveBg)
                                .border(1.dp, palette.curtainActiveBorder, RoundedCornerShape(4.dp))
                                .clickable { controller.clearCurtain() }
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                                .testTag("ciacola-presenter-curtain-pill"),
                        ) {
                            Text(
                                text = curtainLabel,
                                style = JewelTheme.typography.small.copy(fontWeight = FontWeight.SemiBold),
                                color = palette.curtainActiveInk,
                            )
                        }
                    }
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Tooltip(tooltip = { Text("Jump to slide 1..${controller.slideCount} (⌘G)") }) {
                    OutlinedButton(
                        onClick = { controller.openJumpDialog(JumpDialogHost.Presenter) },
                        modifier = Modifier.testTag("ciacola-presenter-jump-button"),
                    ) {
                        Text("Jump…", style = JewelTheme.typography.small)
                    }
                }

                CurtainToggleChip(
                    label = "B",
                    active = controller.curtain == StageCurtain.Blackout,
                    tooltip = "Blackout audience stage (B)",
                    testTag = "ciacola-presenter-blackout-button",
                    palette = palette,
                    onClick = { controller.toggleCurtain(StageCurtain.Blackout) },
                )
                CurtainToggleChip(
                    label = "W",
                    active = controller.curtain == StageCurtain.Whiteout,
                    tooltip = "Whiteout audience stage (W)",
                    testTag = "ciacola-presenter-whiteout-button",
                    palette = palette,
                    onClick = { controller.toggleCurtain(StageCurtain.Whiteout) },
                )

                if (displays.isNotEmpty()) {
                    Box {
                        Tooltip(tooltip = { Text("Route audience stage to a display or swap screens") }) {
                            OutlinedButton(
                                onClick = { onDisplayPopupOpenChange(!displayPopupOpen) },
                                modifier = Modifier.testTag("ciacola-presenter-displays-button"),
                            ) {
                                Text("Present on…", style = JewelTheme.typography.small)
                            }
                        }

                        if (displayPopupOpen) {
                            DisplayRoutingPopup(
                                displays = displays,
                                onSelectDisplay = { screen ->
                                    onDisplayPopupOpenChange(false)
                                    controller.presentOnDisplay(screen)
                                },
                                onSwapDisplays = {
                                    onDisplayPopupOpenChange(false)
                                    controller.swapAudienceAndPresenterDisplays()
                                },
                                onDismiss = { onDisplayPopupOpenChange(false) },
                            )
                        }
                    }
                }
            }
        }

        // Row 2: Glanceable Wall Clock, Elapsed Timer (T/R), Target Duration (-5m/+5m), Remaining & Pace.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = wallClock,
                    style = JewelTheme.typography.medium.copy(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Medium,
                    ),
                    color = globalColors.text.info,
                    modifier = Modifier.testTag("ciacola-presenter-wall-clock"),
                )

                Divider(Orientation.Vertical, Modifier.height(18.dp))

                Text(
                    text = PresenterTimerState.formatMmSs(elapsedSeconds),
                    style = JewelTheme.typography.h2TextStyle.copy(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                    ),
                    color = globalColors.text.normal,
                    modifier = Modifier.testTag("ciacola-presenter-elapsed-timer"),
                )

                PresenterIconButton(
                    icon = if (timerRunning) AllIconsKeys.Actions.Pause else AllIconsKeys.Actions.Execute,
                    contentDescription = if (timerRunning) "Pause timer" else "Start timer",
                    tooltip = if (timerRunning) "Pause timer (T)" else "Start timer (T)",
                    testTag = "ciacola-presenter-timer-play-pause",
                    onClick = { controller.timer.togglePlayPause() },
                )

                PresenterIconButton(
                    icon = AllIconsKeys.Actions.Refresh,
                    contentDescription = "Reset timer",
                    tooltip = "Reset timer (R)",
                    testTag = "ciacola-presenter-timer-reset",
                    onClick = { controller.timer.reset() },
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = "Target",
                    style = JewelTheme.typography.small,
                    color = globalColors.text.info,
                )

                Tooltip(tooltip = { Text("Decrease target duration by 5 minutes") }) {
                    OutlinedButton(
                        onClick = { controller.timer.adjustTargetMinutes(-PresenterTimerState.TARGET_STEP_MINUTES) },
                        enabled = controller.timer.targetDurationMinutes > PresenterTimerState.MIN_TARGET_MINUTES,
                        modifier = Modifier.testTag("ciacola-presenter-target-dec"),
                    ) {
                        Text("-5m", style = JewelTheme.typography.small)
                    }
                }

                Text(
                    text = PresenterTimerState.formatMmSs(controller.timer.targetDurationMinutes * 60L),
                    style = JewelTheme.typography.medium.copy(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = globalColors.text.normal,
                    modifier = Modifier.testTag("ciacola-presenter-target-duration"),
                )

                Tooltip(tooltip = { Text("Increase target duration by 5 minutes") }) {
                    OutlinedButton(
                        onClick = { controller.timer.adjustTargetMinutes(PresenterTimerState.TARGET_STEP_MINUTES) },
                        enabled = controller.timer.targetDurationMinutes < PresenterTimerState.MAX_TARGET_MINUTES,
                        modifier = Modifier.testTag("ciacola-presenter-target-inc"),
                    ) {
                        Text("+5m", style = JewelTheme.typography.small)
                    }
                }

                PaceStatusPill(
                    remainingText = PresenterTimerState.formatRemaining(
                        elapsedSeconds = elapsedSeconds,
                        targetMinutes = controller.timer.targetDurationMinutes,
                    ),
                    pace = pace,
                    palette = palette,
                )
            }
        }
    }
}

@Composable
private fun StageStatusPill(
    isTransitioning: Boolean,
    palette: PresenterStatusPalette,
) {
    val dotColor = if (isTransitioning) palette.transitioning else palette.ready
    val textColor = if (isTransitioning) palette.transitioningInk else palette.readyInk
    val label = if (isTransitioning) "Transitioning" else "Ready"

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(dotColor.copy(alpha = 0.14f))
            .border(1.dp, dotColor.copy(alpha = 0.55f), RoundedCornerShape(4.dp))
            .padding(horizontal = 7.dp, vertical = 2.dp)
            .semantics(mergeDescendants = true) {}
            .testTag("ciacola-presenter-stage-status"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(dotColor),
        )
        Text(
            text = label,
            style = JewelTheme.typography.small.copy(fontWeight = FontWeight.Medium),
            color = textColor,
        )
    }
}

@Composable
private fun PaceStatusPill(
    remainingText: String,
    pace: PaceStatus,
    palette: PresenterStatusPalette,
) {
    val (accent, ink) = when (pace) {
        PaceStatus.OnPace -> palette.onPace to palette.onPaceInk
        PaceStatus.Tight -> palette.tight to palette.tightInk
        PaceStatus.Over -> palette.over to palette.overInk
    }

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(accent.copy(alpha = 0.14f))
            .border(1.dp, accent.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp)
            .semantics(mergeDescendants = true) {}
            .testTag("ciacola-presenter-pace-pill"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = "$remainingText · ${pace.label}",
            style = JewelTheme.typography.small.copy(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
            ),
            color = ink,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CurtainToggleChip(
    label: String,
    active: Boolean,
    tooltip: String,
    testTag: String,
    palette: PresenterStatusPalette,
    onClick: () -> Unit,
) {
    val globalColors = JewelTheme.globalColors
    val focusRingColor = rememberAccessibleFocusRingColor()
    var focused by remember { mutableStateOf(false) }
    val bg = if (active) palette.curtainActiveBg else Color.Transparent
    val border = if (active) palette.curtainActiveBorder else globalColors.borders.normal
    val ink = if (active) palette.curtainActiveInk else globalColors.text.normal

    Tooltip(tooltip = { Text(tooltip) }) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(bg)
                .border(1.dp, border, RoundedCornerShape(4.dp))
                .onFocusChanged { focused = it.isFocused }
                .accessibleFocusRing(focused = focused, ringColor = focusRingColor, cornerRadius = 4.dp)
                .pointerHoverIcon(PointerIcon.Hand)
                .clickable(role = Role.Checkbox, onClick = onClick)
                .padding(horizontal = 8.dp, vertical = 3.dp)
                .semantics {
                    contentDescription = tooltip
                    stateDescription = if (active) "Active" else "Off"
                }
                .testTag(testTag),
        ) {
            Text(
                text = label,
                style = JewelTheme.typography.small.copy(fontWeight = FontWeight.SemiBold),
                color = ink,
            )
        }
    }
}

@Composable
private fun DisplayRoutingPopup(
    displays: List<DisplayScreenInfo>,
    onSelectDisplay: (DisplayScreenInfo) -> Unit,
    onSwapDisplays: () -> Unit,
    onDismiss: () -> Unit,
) {
    val globalColors = JewelTheme.globalColors
    PopupContainer(
        onDismissRequest = { onDismiss() },
        horizontalAlignment = Alignment.End,
        popupProperties = PopupProperties(focusable = true),
        modifier = Modifier.onPreviewKeyEvent { ev ->
            if (ev.key == Key.Escape) {
                if (ev.type == KeyEventType.KeyDown) onDismiss()
                true
            } else {
                false
            }
        },
    ) {
        Column(
            modifier = Modifier
                .widthIn(min = 260.dp, max = 340.dp)
                .padding(8.dp)
                .testTag("ciacola-presenter-displays-popup"),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = "Present Audience Stage On:",
                style = JewelTheme.typography.small.copy(fontWeight = FontWeight.SemiBold),
                color = globalColors.text.info,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            )
            displays.forEach { screen ->
                DisplayPopupRow(
                    icon = AllIconsKeys.Actions.Preview,
                    label = screen.label,
                    enabled = true,
                    onClick = { onSelectDisplay(screen) },
                )
            }

            Divider(Orientation.Horizontal, Modifier.fillMaxWidth().padding(vertical = 2.dp))

            val canSwap = displays.size >= 2
            DisplayPopupRow(
                icon = AllIconsKeys.Actions.Refresh,
                label = if (canSwap) {
                    "Swap Audience & Presenter Displays"
                } else {
                    "Swap Displays (needs 2+ monitors)"
                },
                enabled = canSwap,
                testTag = "ciacola-presenter-swap-displays",
                onClick = onSwapDisplays,
            )
        }
    }
}

@Composable
private fun DisplayPopupRow(
    icon: IconKey,
    label: String,
    enabled: Boolean,
    testTag: String? = null,
    onClick: () -> Unit,
) {
    val globalColors = JewelTheme.globalColors
    val focusRingColor = rememberAccessibleFocusRingColor()
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .onFocusChanged { focused = it.isFocused }
            .accessibleFocusRing(focused = focused, ringColor = focusRingColor, cornerRadius = 4.dp)
            .then(
                if (enabled) {
                    Modifier.pointerHoverIcon(PointerIcon.Hand).clickable(role = Role.Button, onClick = onClick)
                } else {
                    Modifier
                }
            )
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .let { if (testTag != null) it.testTag(testTag) else it },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            key = icon,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
        )
        Text(
            text = label,
            style = JewelTheme.typography.regular,
            color = if (enabled) globalColors.text.normal else globalColors.text.disabled,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun PresenterIconButton(
    icon: IconKey,
    contentDescription: String,
    tooltip: String,
    testTag: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val focusRingColor = rememberAccessibleFocusRingColor()
    var focused by remember { mutableStateOf(false) }
    Tooltip(tooltip = { Text(tooltip) }) {
        IconButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier
                .size(26.dp)
                .onFocusChanged { focused = it.isFocused }
                .accessibleFocusRing(focused = focused, ringColor = focusRingColor, cornerRadius = 4.dp)
                .testTag(testTag),
        ) {
            Icon(
                key = icon,
                contentDescription = contentDescription,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}
