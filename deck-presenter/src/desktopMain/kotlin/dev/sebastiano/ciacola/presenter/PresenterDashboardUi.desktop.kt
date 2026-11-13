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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import dev.bnorm.storyboard.AdvanceDirection
import dev.bnorm.storyboard.easel.Easel
import dev.bnorm.storyboard.easel.SceneMode
import dev.bnorm.storyboard.easel.ScenePreview
import kotlinx.coroutines.delay
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.InfoText
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.Tooltip
import org.jetbrains.jewel.ui.component.VerticalScrollbar
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.ui.typography

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
    var displayPopupOpen by remember { mutableStateOf(false) }
    LaunchedEffect(controller) {
        var tick = 0
        while (true) {
            nowMillis = System.currentTimeMillis()
            wallClock = controller.wallClockText()
            if (tick % 8 == 0) {
                controller.refreshDisplays()
            }
            tick++
            delay(250L)
        }
    }

    val readiness = controller.readinessStatus()
    LaunchedEffect(readiness.hasUnbakedOverrides) {
        if (!readiness.hasUnbakedOverrides) {
            controller.unbakedNoticeDismissed = false
        }
    }

    val dashboardFocusRequester = remember { FocusRequester() }
    var dashboardHadFocus by remember { mutableStateOf(false) }
    var dashboardHasFocus by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        runCatching { dashboardFocusRequester.requestFocus() }
    }
    LaunchedEffect(
        dashboardHasFocus,
        controller.jumpDialogHost,
        displayPopupOpen,
        controller.canStepBackward,
        controller.canStepForward,
        controller.notesFontSizeSp,
        controller.timer.targetDurationMinutes,
    ) {
        if (dashboardHadFocus &&
            !dashboardHasFocus &&
            controller.jumpDialogHost != JumpDialogHost.Presenter &&
            !displayPopupOpen
        ) {
            runCatching { dashboardFocusRequester.requestFocus() }
        }
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
            .onFocusChanged { state ->
                if (state.hasFocus) dashboardHadFocus = true
                dashboardHasFocus = state.hasFocus
            }
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
                displayPopupOpen = displayPopupOpen,
                onDisplayPopupOpenChange = { open ->
                    if (open) controller.refreshDisplays()
                    displayPopupOpen = open
                },
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
                    text = "${controller.notesFontSizeSp.toInt()} pt",
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
