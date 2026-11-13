package dev.sebastiano.ciacola.presenter

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import dev.bnorm.storyboard.AdvanceDirection
import dev.bnorm.storyboard.easel.Easel
import dev.bnorm.storyboard.easel.SceneMode
import dev.bnorm.storyboard.easel.ScenePreview
import dev.sebastiano.ciacola.timeline.accessibleFocusRing
import dev.sebastiano.ciacola.timeline.rememberAccessibleFocusRingColor
import kotlinx.coroutines.delay
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.Outline
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.IconButton
import org.jetbrains.jewel.ui.component.InfoText
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.PopupContainer
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField
import org.jetbrains.jewel.ui.component.Tooltip
import org.jetbrains.jewel.ui.component.VerticalScrollbar
import org.jetbrains.jewel.ui.icon.IconKey
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.ui.theme.colorPalette
import org.jetbrains.jewel.ui.typography

@Immutable
internal data class PresenterStatusPalette(
    val ready: Color,
    val readyInk: Color,
    val transitioning: Color,
    val transitioningInk: Color,
    val onPace: Color,
    val onPaceInk: Color,
    val tight: Color,
    val tightInk: Color,
    val over: Color,
    val overInk: Color,
    val warningBannerBg: Color,
    val warningBannerBorder: Color,
    val warningBannerInk: Color,
    val curtainActiveBg: Color,
    val curtainActiveBorder: Color,
    val curtainActiveInk: Color,
)

@Composable
internal fun rememberPresenterStatusPalette(): PresenterStatusPalette {
    val globalColors = JewelTheme.globalColors
    val palette = JewelTheme.colorPalette
    val isDark = JewelTheme.isDark
    val instanceUuid = JewelTheme.instanceUuid

    return remember(instanceUuid, isDark) {
        val ready = palette.greenOrNull(if (isDark) 6 else 5) ?: globalColors.outlines.focused
        val transitioning = palette.blueOrNull(if (isDark) 7 else 5) ?: globalColors.outlines.focused
        val tight = palette.yellowOrNull(if (isDark) 6 else 5) ?: globalColors.outlines.warning
        val over = palette.redOrNull(if (isDark) 6 else 5) ?: globalColors.outlines.error
        PresenterStatusPalette(
            ready = ready,
            readyInk = if (isDark) Color(0xFF8BE2B1) else Color(0xFF145A32),
            transitioning = transitioning,
            transitioningInk = if (isDark) Color(0xFF91BCFF) else Color(0xFF1A4B8C),
            onPace = ready,
            onPaceInk = if (isDark) Color(0xFF8BE2B1) else Color(0xFF145A32),
            tight = tight,
            tightInk = if (isDark) Color(0xFFFFD87A) else Color(0xFF6B4C00),
            over = over,
            overInk = if (isDark) Color(0xFFFF9C9C) else Color(0xFF8B1A1A),
            warningBannerBg = if (isDark) Color(0xFF3A2D12) else Color(0xFFFFF4D6),
            warningBannerBorder = tight,
            warningBannerInk = if (isDark) Color(0xFFFFD87A) else Color(0xFF6B4C00),
            curtainActiveBg = if (isDark) Color(0xFF3A2124) else Color(0xFFFDECEC),
            curtainActiveBorder = over,
            curtainActiveInk = if (isDark) Color(0xFFFFB0B0) else Color(0xFF8B1A1A),
        )
    }
}

/**
 * Full Jewel-styled Presenter View dashboard rendered inside the `F2` Presenter window.
 */
@Composable
fun PresenterDashboard(
    controller: PresenterController,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val globalColors = JewelTheme.globalColors
    val palette = rememberPresenterStatusPalette()

    var nowMillis by remember { mutableStateOf(System.currentTimeMillis()) }
    var wallClock by remember { mutableStateOf(controller.wallClockText()) }
    LaunchedEffect(controller) {
        while (true) {
            nowMillis = System.currentTimeMillis()
            wallClock = controller.wallClockText()
            delay(250L)
        }
    }

    val readiness = controller.readinessStatus()
    LaunchedEffect(readiness.hasUnbakedOverrides) {
        if (readiness.hasUnbakedOverrides) {
            // Re-surface if new overrides appear after dismissal.
        } else {
            controller.unbakedNoticeDismissed = false
        }
    }

    val dashboardFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        runCatching { dashboardFocusRequester.requestFocus() }
    }
    var wasPresenterJumpOpen by remember { mutableStateOf(false) }
    LaunchedEffect(controller.jumpDialogHost) {
        val isOpen = controller.jumpDialogHost == JumpDialogHost.Presenter
        if (!isOpen && wasPresenterJumpOpen) {
            runCatching { dashboardFocusRequester.requestFocus() }
        }
        wasPresenterJumpOpen = isOpen
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(globalColors.panelBackground)
            .focusRequester(dashboardFocusRequester)
            .focusable()
            .onPreviewKeyEvent { ev -> controller.handlePresenterPreviewKeyEvent(ev) }
            .onKeyEvent { ev -> controller.handlePresenterBubbleKeyEvent(ev, scope) }
            .testTag("ciacola-presenter-dashboard"),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            PresenterTopControlBar(
                controller = controller,
                wallClock = wallClock,
                elapsedSeconds = controller.timer.elapsedSeconds(nowMillis),
                palette = palette,
            )

            Divider(Orientation.Horizontal, Modifier.fillMaxWidth())

            val onlyUnbaked = !readiness.hasClosableAuthoringTools && readiness.hasUnbakedOverrides
            if (readiness.hasAnyHazard && (!onlyUnbaked || !controller.unbakedNoticeDismissed)) {
                PreShowReadinessBanner(
                    readiness = readiness,
                    palette = palette,
                    onEnterCleanShowMode = { controller.enterCleanShowMode() },
                    onDismissUnbakedNotice = { controller.unbakedNoticeDismissed = true },
                )
                Divider(Orientation.Horizontal, Modifier.fillMaxWidth())
            }

            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f),
            ) {
                PresenterPreviewColumn(
                    controller = controller,
                    palette = palette,
                    modifier = Modifier
                        .weight(0.46f)
                        .fillMaxHeight(),
                )

                Divider(Orientation.Vertical, Modifier.fillMaxHeight())

                PresenterSpeakerNotesPane(
                    controller = controller,
                    modifier = Modifier
                        .weight(0.54f)
                        .fillMaxHeight(),
                )
            }
        }

        if (controller.jumpDialogHost == JumpDialogHost.Presenter) {
            QuickSlideJumpOverlay(
                controller = controller,
                navigationScope = scope,
                onDismiss = { fromEsc -> controller.closeJumpDialog(fromEscapeKey = fromEsc) },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PresenterTopControlBar(
    controller: PresenterController,
    wallClock: String,
    elapsedSeconds: Long,
    palette: PresenterStatusPalette,
) {
    val scope = rememberCoroutineScope()
    val globalColors = JewelTheme.globalColors
    val resolvedNotes = controller.resolvedCurrentNotes()
    val displays = remember(controller.animatic) { controller.connectedDisplays() }
    var displayPopupOpen by remember { mutableStateOf(false) }

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
                                onClick = { displayPopupOpen = !displayPopupOpen },
                                modifier = Modifier.testTag("ciacola-presenter-displays-button"),
                            ) {
                                Text("Present on…", style = JewelTheme.typography.small)
                            }
                        }

                        if (displayPopupOpen) {
                            DisplayRoutingPopup(
                                displays = displays,
                                onSelectDisplay = { screen ->
                                    displayPopupOpen = false
                                    controller.presentOnDisplay(screen)
                                },
                                onSwapDisplays = {
                                    displayPopupOpen = false
                                    controller.swapAudienceAndPresenterDisplays()
                                },
                                onDismiss = { displayPopupOpen = false },
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
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(4.dp))
                        .pointerHoverIcon(PointerIcon.Hand)
                        .clickable(role = Role.Button) { onSelectDisplay(screen) }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        key = AllIconsKeys.Actions.Preview,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                    )
                    Text(
                        text = screen.label,
                        style = JewelTheme.typography.regular,
                        color = globalColors.text.normal,
                    )
                }
            }

            Divider(Orientation.Horizontal, Modifier.fillMaxWidth().padding(vertical = 2.dp))

            val canSwap = displays.size >= 2
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(4.dp))
                    .then(
                        if (canSwap) {
                            Modifier.pointerHoverIcon(PointerIcon.Hand).clickable(role = Role.Button) { onSwapDisplays() }
                        } else {
                            Modifier
                        }
                    )
                    .padding(horizontal = 8.dp, vertical = 6.dp)
                    .testTag("ciacola-presenter-swap-displays"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    key = AllIconsKeys.Actions.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    text = if (canSwap) {
                        "Swap Audience & Presenter Displays"
                    } else {
                        "Swap Displays (needs 2+ monitors)"
                    },
                    style = JewelTheme.typography.regular,
                    color = if (canSwap) globalColors.text.normal else globalColors.text.disabled,
                )
            }
        }
    }
}

@Composable
private fun PreShowReadinessBanner(
    readiness: ReadinessStatus,
    palette: PresenterStatusPalette,
    onEnterCleanShowMode: () -> Unit,
    onDismissUnbakedNotice: () -> Unit,
) {
    val activeItems = buildList {
        if (readiness.studioOpen) add("Timeline Studio is open (F5/F6)")
        if (readiness.inspectOpen) add("Review Inspect mode is active (F3)")
        if (readiness.hasUnbakedOverrides) add("Unbaked timeline overrides are active")
    }
    val summary = activeItems.joinToString(" · ")

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(palette.warningBannerBg)
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .testTag("ciacola-presenter-readiness-banner"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            modifier = Modifier.weight(1f).padding(end = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                key = AllIconsKeys.General.Warning,
                contentDescription = "Pre-show readiness warning",
                modifier = Modifier.size(15.dp),
            )
            Text(
                text = "Pre-show check: $summary",
                style = JewelTheme.typography.small.copy(fontWeight = FontWeight.Medium),
                color = palette.warningBannerInk,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (readiness.hasClosableAuthoringTools) {
            DefaultButton(
                onClick = onEnterCleanShowMode,
                modifier = Modifier.testTag("ciacola-presenter-clean-show-button"),
            ) {
                Text("Enter Clean Show Mode", style = JewelTheme.typography.small)
            }
        } else {
            OutlinedButton(
                onClick = onDismissUnbakedNotice,
                modifier = Modifier.testTag("ciacola-presenter-dismiss-readiness"),
            ) {
                Text("Dismiss", style = JewelTheme.typography.small)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PresenterPreviewColumn(
    controller: PresenterController,
    palette: PresenterStatusPalette,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val globalColors = JewelTheme.globalColors

    Column(
        modifier = modifier.padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Current live slide preview (never SceneMode.Story so it never steals Story clock/capture).
        Column(
            modifier = Modifier
                .weight(1.12f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "Current Slide",
                    style = JewelTheme.typography.h4TextStyle,
                    color = globalColors.text.normal,
                )
                if (controller.curtain != StageCurtain.None) {
                    Text(
                        text = when (controller.curtain) {
                            StageCurtain.Blackout -> "Audience sees Blackout"
                            StageCurtain.Whiteout -> "Audience sees Whiteout"
                            StageCurtain.None -> ""
                        },
                        style = JewelTheme.typography.small.copy(fontWeight = FontWeight.SemiBold),
                        color = palette.curtainActiveInk,
                    )
                }
            }

            FittedAspectBox(
                aspectRatio = 16f / 9f,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            ) {
                val frameBorder = if (controller.curtain != StageCurtain.None) {
                    palette.curtainActiveBorder
                } else {
                    globalColors.borders.normal
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(6.dp))
                        .border(1.dp, frameBorder, RoundedCornerShape(6.dp))
                        .testTag("ciacola-presenter-current-preview"),
                ) {
                    Easel(
                        animatic = controller.animatic,
                        mode = SceneMode.Preview,
                        modifier = Modifier.fillMaxSize().padding(bottom = 2.dp),
                    )
                    // Subtle themed transition progress track at the bottom of the preview frame.
                    val progress = controller.transitionProgress
                    val barColor = if (controller.isTransitioning) palette.transitioning else palette.ready
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(2.dp)
                            .background(globalColors.borders.normal.copy(alpha = 0.4f)),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(progress)
                                .background(barColor),
                        )
                    }
                }
            }
        }

        // Next beat / next slide preview (clickable to advance smoothly).
        Column(
            modifier = Modifier
                .weight(0.88f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = controller.nextStepLabel(),
                style = JewelTheme.typography.h4TextStyle,
                color = globalColors.text.info,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            FittedAspectBox(
                aspectRatio = 16f / 9f,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            ) {
                val nextIdx = controller.nextIndex
                if (nextIdx != null) {
                    Tooltip(tooltip = { Text("Advance to ${controller.nextStepLabel()} (→ / Space)") }) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(RoundedCornerShape(6.dp))
                                .border(1.dp, globalColors.borders.normal, RoundedCornerShape(6.dp))
                                .testTag("ciacola-presenter-next-preview"),
                        ) {
                            ScenePreview(
                                storyboard = controller.animatic.storyboard,
                                index = nextIdx,
                                sceneMode = SceneMode.Preview,
                                modifier = Modifier.fillMaxSize(),
                            )
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .pointerHoverIcon(PointerIcon.Hand)
                                    .clickable { controller.step(scope, AdvanceDirection.Forward) },
                            )
                        }
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(6.dp))
                            .background(globalColors.toolwindowBackground)
                            .border(1.dp, globalColors.borders.normal, RoundedCornerShape(6.dp))
                            .testTag("ciacola-presenter-end-of-deck"),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Icon(
                                key = AllIconsKeys.General.GreenCheckmark,
                                contentDescription = "End of deck",
                                modifier = Modifier.size(18.dp),
                            )
                            Text(
                                text = "End of Deck",
                                style = JewelTheme.typography.medium.copy(fontWeight = FontWeight.SemiBold),
                                color = globalColors.text.normal,
                            )
                            Text(
                                text = "Final beat reached",
                                style = JewelTheme.typography.small,
                                color = globalColors.text.info,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FittedAspectBox(
    aspectRatio: Float,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    BoxWithConstraints(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        val widthFromHeight = maxHeight * aspectRatio
        val fittedWidth: Dp = min(maxWidth, widthFromHeight)
        val fittedHeight: Dp = fittedWidth / aspectRatio
        Box(
            modifier = Modifier.size(fittedWidth.coerceAtLeast(1.dp), fittedHeight.coerceAtLeast(1.dp)),
        ) {
            content()
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PresenterSpeakerNotesPane(
    controller: PresenterController,
    modifier: Modifier = Modifier,
) {
    val globalColors = JewelTheme.globalColors
    val resolved = controller.resolvedCurrentNotes()
    val scrollState = rememberScrollState()

    LaunchedEffect(resolved.sceneIndex, resolved.beatIndex) {
        scrollState.scrollTo(0)
    }

    Column(
        modifier = modifier.padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
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
                    text = "Speaker Notes",
                    style = JewelTheme.typography.h3TextStyle,
                    color = globalColors.text.normal,
                )
                if (resolved.isVirtualHoldBeat) {
                    Text(
                        text = "Hold beat",
                        style = JewelTheme.typography.small,
                        color = globalColors.text.info,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .border(1.dp, globalColors.borders.normal, RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 1.dp),
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                PresenterIconButton(
                    icon = AllIconsKeys.General.Remove,
                    contentDescription = "Decrease speaker notes font size",
                    tooltip = "Decrease notes font size (⌘-)",
                    enabled = controller.notesFontSizeSp > PresenterController.MIN_NOTES_FONT_SP,
                    testTag = "ciacola-presenter-notes-font-dec",
                    onClick = { controller.decreaseNotesFontSize() },
                )
                Text(
                    text = "${controller.notesFontSizeSp.toInt()} sp",
                    style = JewelTheme.typography.small.copy(fontFamily = FontFamily.Monospace),
                    color = globalColors.text.info,
                    modifier = Modifier.testTag("ciacola-presenter-notes-font-size"),
                )
                PresenterIconButton(
                    icon = AllIconsKeys.General.Add,
                    contentDescription = "Increase speaker notes font size",
                    tooltip = "Increase notes font size (⌘=)",
                    enabled = controller.notesFontSizeSp < PresenterController.MAX_NOTES_FONT_SP,
                    testTag = "ciacola-presenter-notes-font-inc",
                    onClick = { controller.increaseNotesFontSize() },
                )
            }
        }

        Divider(Orientation.Horizontal, Modifier.fillMaxWidth())

        Box(
            modifier = Modifier
                .fillMaxSize()
                .weight(1f)
                .clip(RoundedCornerShape(6.dp))
                .background(globalColors.toolwindowBackground)
                .border(1.dp, globalColors.borders.normal, RoundedCornerShape(6.dp)),
        ) {
            if (!resolved.hasNotes) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp)
                        .testTag("ciacola-presenter-notes-empty"),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = "No speaker notes for this slide",
                        style = JewelTheme.typography.h4TextStyle,
                        color = globalColors.text.normal,
                    )
                    Spacer(Modifier.height(6.dp))
                    InfoText("Add overview(…) or beat(index, …) notes on this scene to view delivery cues here.")
                }
            } else {
                Box(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(scrollState)
                            .padding(start = 16.dp, top = 16.dp, bottom = 16.dp, end = 22.dp)
                            .testTag("ciacola-presenter-notes-content"),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        val noteFontSize = controller.notesFontSizeSp.sp
                        val noteLineHeight = (controller.notesFontSizeSp * 1.45f).sp
                        val beatNote = resolved.beatNote
                        if (!beatNote.isNullOrBlank()) {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    text = "BEAT ${resolved.beatIndex + 1} CUE",
                                    style = JewelTheme.typography.small.copy(fontWeight = FontWeight.Bold),
                                    color = globalColors.text.info,
                                )
                                Text(
                                    text = beatNote,
                                    style = JewelTheme.typography.regular.copy(
                                        fontSize = noteFontSize,
                                        lineHeight = noteLineHeight,
                                    ),
                                    color = globalColors.text.normal,
                                )
                            }
                        }

                        if (resolved.slideNotes.isNotBlank()) {
                            if (!beatNote.isNullOrBlank()) {
                                Divider(Orientation.Horizontal, Modifier.fillMaxWidth())
                            }
                            val contextSize = (controller.notesFontSizeSp * 0.85f).coerceAtLeast(14f)
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    text = "SLIDE CONTEXT",
                                    style = JewelTheme.typography.small.copy(fontWeight = FontWeight.Bold),
                                    color = globalColors.text.info,
                                )
                                Text(
                                    text = resolved.slideNotes,
                                    style = JewelTheme.typography.regular.copy(
                                        fontSize = contextSize.sp,
                                        lineHeight = (contextSize * 1.42f).sp,
                                    ),
                                    color = if (beatNote.isNullOrBlank()) globalColors.text.normal else globalColors.text.info,
                                )
                            }
                        }
                    }
                    VerticalScrollbar(
                        scrollState = scrollState,
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .fillMaxHeight()
                            .padding(vertical = 4.dp, horizontal = 2.dp),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PresenterIconButton(
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

/**
 * Lightweight Jewel dialog overlay for `Cmd+G` quick slide jump (`1..N`).
 */
@Composable
internal fun QuickSlideJumpOverlay(
    controller: PresenterController,
    navigationScope: kotlinx.coroutines.CoroutineScope,
    onDismiss: (fromEscapeKey: Boolean) -> Unit,
) {
    val globalColors = JewelTheme.globalColors
    val slideCount = controller.slideCount.coerceAtLeast(1)
    val initialText = (controller.currentSceneIndex + 1).toString()
    val inputState = rememberTextFieldState(initialText = initialText)
    var validationError by remember { mutableStateOf<String?>(null) }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        inputState.setTextAndPlaceCursorAtEnd(initialText)
        runCatching { focusRequester.requestFocus() }
    }

    LaunchedEffect(inputState.text.toString()) {
        if (validationError != null) {
            validationError = null
        }
    }

    fun submitJump() {
        val parsed = inputState.text.toString().trim().toIntOrNull()
        if (parsed == null || parsed !in 1..slideCount) {
            validationError = "Enter a slide number from 1 to $slideCount"
            return
        }
        controller.jumpToSlide(navigationScope, parsed)
    }

    Popup(
        alignment = Alignment.Center,
        onDismissRequest = { onDismiss(false) },
        properties = PopupProperties(focusable = true),
    ) {
        Column(
            modifier = Modifier
                .width(320.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(globalColors.panelBackground)
                .border(1.dp, globalColors.borders.focused, RoundedCornerShape(8.dp))
                .padding(14.dp)
                .onPreviewKeyEvent { ev ->
                    if (ev.type == KeyEventType.KeyDown) {
                        when (ev.key) {
                            Key.Enter, Key.NumPadEnter -> {
                                submitJump()
                                true
                            }

                            Key.Escape -> {
                                onDismiss(true)
                                true
                            }

                            else -> false
                        }
                    } else {
                        false
                    }
                }
                .testTag("ciacola-presenter-jump-dialog"),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "Jump to Slide",
                style = JewelTheme.typography.h3TextStyle,
                color = globalColors.text.normal,
            )

            InfoText("Enter slide number (1..$slideCount):")

            TextField(
                state = inputState,
                outline = if (validationError != null) Outline.Error else Outline.None,
                placeholder = { Text("1..$slideCount") },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .testTag("ciacola-presenter-jump-input"),
            )

            val err = validationError
            if (err != null) {
                Text(
                    text = err,
                    style = JewelTheme.typography.small,
                    color = globalColors.text.error,
                    modifier = Modifier.testTag("ciacola-presenter-jump-error"),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = { onDismiss(false) },
                    modifier = Modifier.testTag("ciacola-presenter-jump-cancel"),
                ) {
                    Text("Cancel")
                }
                Spacer(Modifier.width(8.dp))
                DefaultButton(
                    onClick = ::submitJump,
                    modifier = Modifier.testTag("ciacola-presenter-jump-confirm"),
                ) {
                    Text("Jump")
                }
            }
        }
    }
}
