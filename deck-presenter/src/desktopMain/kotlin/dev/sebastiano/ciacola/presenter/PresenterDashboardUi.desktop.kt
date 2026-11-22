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
        controller.isEditingNotes,
    ) {
        if (dashboardHadFocus &&
            !dashboardHasFocus &&
            controller.jumpDialogHost != JumpDialogHost.Presenter &&
            !displayPopupOpen &&
            !controller.isEditingNotes
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

            if (readiness.hasAnyHazard) {
                PreShowReadinessBanner(
                    readiness = readiness,
                    palette = palette,
                    onEnterCleanShowMode = { controller.enterCleanShowMode() },
                )
                Divider(Orientation.Horizontal, Modifier.fillMaxWidth())
            }

            val notesFraction = presenterNotesPaneFraction(
                hasNotes = controller.resolvedCurrentNotes().hasNotes,
                editing = controller.isEditingNotes,
            )
            BoxWithConstraints(modifier = Modifier.fillMaxSize().weight(1f)) {
                // The preview column hugs its previews (no dead strip); spare width goes to the notes.
                val previewArea = maxWidth * (1f - notesFraction)
                val layout = presenterPreviewLayout(
                    availableWidth = previewArea - PresenterPreviewPadding * 2,
                    availableHeight = maxHeight - PresenterPreviewPadding * 2,
                )
                val previewColumnWidth = min(previewArea, layout.contentWidth + PresenterPreviewPadding * 2)
                Row(modifier = Modifier.fillMaxSize()) {
                    PresenterPreviewColumn(
                        controller = controller,
                        palette = palette,
                        layout = layout,
                        modifier = Modifier
                            .width(previewColumnWidth)
                            .fillMaxHeight(),
                    )

                    Divider(Orientation.Vertical, Modifier.fillMaxHeight())

                    PresenterSpeakerNotesPane(
                        controller = controller,
                        onEditorClosed = { runCatching { dashboardFocusRequester.requestFocus() } },
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .testTag("ciacola-presenter-notes-pane"),
                    )
                }
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

/** Width share of the speaker notes pane: 40% with notes (or while editing), 28% when empty. */
fun presenterNotesPaneFraction(hasNotes: Boolean, editing: Boolean): Float =
    if (hasNotes || editing) NOTES_PANE_FRACTION_WITH_NOTES else NOTES_PANE_FRACTION_EMPTY

const val NOTES_PANE_FRACTION_WITH_NOTES = 0.40f
const val NOTES_PANE_FRACTION_EMPTY = 0.28f

/** Vertical space reserved per preview block for its header line and spacing. */
val PresenterPreviewHeaderHeight: Dp = 26.dp
val PresenterPreviewGap: Dp = 14.dp

/**
 * Shared width of the `Current` and `Next` previews: the largest 16:9 frame such that both stacked
 * previews (plus their headers and the gap between them) fit in the column.
 */
fun presenterPreviewWidth(
    availableWidth: Dp,
    availableHeight: Dp,
    aspectRatio: Float = 16f / 9f,
    headerHeight: Dp = PresenterPreviewHeaderHeight,
    gap: Dp = PresenterPreviewGap,
): Dp {
    val heightForFrames = (availableHeight - headerHeight * 2 - gap).coerceAtLeast(0.dp)
    val widthFromHeight = (heightForFrames / 2) * aspectRatio
    return min(availableWidth, widthFromHeight).coerceAtLeast(1.dp)
}

/** Padding around the preview column. */
val PresenterPreviewPadding: Dp = 16.dp

/** Resolved `Current` / `Next` preview arrangement for the available preview area. */
data class PresenterPreviewLayout(
    val sideBySide: Boolean,
    val currentWidth: Dp,
    val nextWidth: Dp,
    val contentWidth: Dp,
)

/**
 * Chooses stacked (`Current` above `Next`, one shared width) or side-by-side (`Current` ~60%,
 * `Next` ~40%) — whichever gives `Current` the larger frame — and reports the content width so the
 * column can hug its previews instead of leaving a dead strip.
 */
fun presenterPreviewLayout(
    availableWidth: Dp,
    availableHeight: Dp,
    aspectRatio: Float = 16f / 9f,
    headerHeight: Dp = PresenterPreviewHeaderHeight,
    gap: Dp = PresenterPreviewGap,
): PresenterPreviewLayout {
    val stacked = presenterPreviewWidth(availableWidth, availableHeight, aspectRatio, headerHeight, gap)
    val rowWidth = (availableWidth - gap).coerceAtLeast(0.dp)
    var sideCurrent = rowWidth * 0.6f
    val maxByHeight = (availableHeight - headerHeight).coerceAtLeast(0.dp) * aspectRatio
    if (sideCurrent > maxByHeight) sideCurrent = maxByHeight
    val sideNext = sideCurrent * (2f / 3f)
    return if (sideCurrent > stacked + 8.dp) {
        PresenterPreviewLayout(true, sideCurrent, sideNext, sideCurrent + gap + sideNext)
    } else {
        PresenterPreviewLayout(false, stacked, stacked, stacked)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PresenterPreviewColumn(
    controller: PresenterController,
    palette: PresenterStatusPalette,
    layout: PresenterPreviewLayout,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val globalColors = JewelTheme.globalColors

    // Side by side, the pair is vertically centred so a short row never strands the column's lower half.
    Box(
        modifier = modifier.padding(PresenterPreviewPadding),
        contentAlignment = if (layout.sideBySide) Alignment.CenterStart else Alignment.TopStart,
    ) {
        val previewWidth = layout.currentWidth
        val previewHeight = previewWidth / (16f / 9f)
        val nextWidth = layout.nextWidth
        val nextHeight = nextWidth / (16f / 9f)
        // Stacked: `Current` and `Next` share one left edge and one width so the eye tracks straight
        // down. Side by side: top-aligned, `Current` larger.
        PresenterPreviewArrangement(
            sideBySide = layout.sideBySide,
            modifier = Modifier.testTag("ciacola-presenter-preview-column"),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                PreviewHeader(width = previewWidth) {
                    Text(
                        text = "Current · Slide ${controller.currentSceneIndex + 1}",
                        style = JewelTheme.typography.h4TextStyle,
                        color = globalColors.text.normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false).testTag("ciacola-presenter-current-header"),
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
                            maxLines = 1,
                        )
                    }
                }
                val frameBorder = if (controller.curtain != StageCurtain.None) {
                    palette.curtainActiveBorder
                } else {
                    globalColors.borders.normal
                }
                Box(
                    modifier = Modifier
                        .size(previewWidth, previewHeight)
                        .clip(RoundedCornerShape(6.dp))
                        .border(1.dp, frameBorder, RoundedCornerShape(6.dp))
                        .testTag("ciacola-presenter-current-preview"),
                ) {
                    val customCurrentSb = controller.previewStoryboardProvider?.invoke()
                    if (customCurrentSb != null) {
                        ScenePreview(
                            storyboard = customCurrentSb,
                            index = controller.currentIndex,
                            sceneMode = SceneMode.Preview,
                            modifier = Modifier.fillMaxSize().padding(bottom = 2.dp),
                        )
                    } else {
                        Easel(
                            animatic = controller.animatic,
                            mode = SceneMode.Preview,
                            modifier = Modifier.fillMaxSize().padding(bottom = 2.dp),
                        )
                    }
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

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                PreviewHeader(width = nextWidth) {
                    Text(
                        text = controller.nextStepLabel(),
                        style = JewelTheme.typography.h4TextStyle,
                        color = globalColors.text.info,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("ciacola-presenter-next-header"),
                    )
                }
                val nextIdx = controller.nextPreviewIndex
                if (nextIdx != null) {
                    Tooltip(tooltip = { Text("Advance to ${controller.nextStepLabel()} (→ / Space)") }) {
                        Box(
                            modifier = Modifier
                                .size(nextWidth, nextHeight)
                                .clip(RoundedCornerShape(6.dp))
                                .border(1.dp, globalColors.borders.normal, RoundedCornerShape(6.dp))
                                .testTag("ciacola-presenter-next-preview"),
                        ) {
                            ScenePreview(
                                storyboard = controller.nextPreviewStoryboardProvider?.invoke()
                                    ?: controller.animatic.storyboard,
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
                            .size(nextWidth, nextHeight)
                            .clip(RoundedCornerShape(6.dp))
                            .background(globalColors.panelBackground)
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
private fun PresenterPreviewArrangement(
    sideBySide: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    if (sideBySide) {
        Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(PresenterPreviewGap), verticalAlignment = Alignment.Top) {
            content()
        }
    } else {
        Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(PresenterPreviewGap), horizontalAlignment = Alignment.Start) {
            content()
        }
    }
}

@Composable
private fun PreviewHeader(
    width: Dp,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    Row(
        modifier = Modifier.width(width).height(PresenterPreviewHeaderHeight - 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        content = content,
    )
}
