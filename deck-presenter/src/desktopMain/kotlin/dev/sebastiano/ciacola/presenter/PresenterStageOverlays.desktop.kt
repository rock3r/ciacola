package dev.sebastiano.ciacola.presenter

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import dev.bnorm.storyboard.easel.LocalOverviewOpen
import dev.sebastiano.ciacola.ui.CiacolaJewelTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Outline
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.InfoText
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.ui.theme.colorPalette
import org.jetbrains.jewel.ui.typography

@Immutable
data class PresenterStatusPalette(
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
fun rememberPresenterStatusPalette(): PresenterStatusPalette {
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

@Composable
internal fun PreShowReadinessBanner(
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

@Composable
internal fun PresenterStageDecorator(
    controller: PresenterController,
    isDark: Boolean,
    onRestoreStageFocus: () -> Boolean,
    content: @Composable () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val overviewOpen = LocalOverviewOpen.current
    SideEffect {
        controller.isOverviewOpen = overviewOpen
    }
    DisposableEffect(controller) {
        onDispose { controller.isOverviewOpen = false }
    }
    var lastObservedIndex by remember { mutableStateOf(controller.animatic.targetIndex) }

    // Advancing/jumping or leaving Show Mode clears any active blackout/whiteout curtain cleanly.
    val currentTarget = controller.animatic.targetIndex
    LaunchedEffect(currentTarget) {
        if (currentTarget != lastObservedIndex) {
            lastObservedIndex = currentTarget
            if (controller.curtain != StageCurtain.None) {
                controller.clearCurtain()
            }
        }
    }
    val showModeActive = controller.isShowModeActive()
    LaunchedEffect(showModeActive) {
        if (!showModeActive && controller.curtain != StageCurtain.None) {
            controller.clearCurtain()
        }
    }

    // Safety guard: clear swallowNextEscapeKeyUp if focus moved before KeyUp arrived.
    LaunchedEffect(controller.swallowNextEscapeKeyUp) {
        if (controller.swallowNextEscapeKeyUp) {
            delay(500L)
            controller.swallowNextEscapeKeyUp = false
        }
    }

    // Restore keyboard focus to the stage when the Quick Slide Jump dialog closes.
    var wasJumpOpen by remember { mutableStateOf(false) }
    val isStoryJumpOpen = controller.jumpDialogHost == JumpDialogHost.Story
    LaunchedEffect(isStoryJumpOpen) {
        if (!isStoryJumpOpen && wasJumpOpen) {
            withFrameNanos { }
            if (!onRestoreStageFocus()) {
                runCatching {
                    if (!focusManager.moveFocus(FocusDirection.Enter)) {
                        focusManager.moveFocus(FocusDirection.Next)
                    }
                }
            }
        }
        wasJumpOpen = isStoryJumpOpen
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { ev -> controller.handleStoryKeyEvent(ev) },
    ) {
        content()

        val activeCurtain = controller.curtain
        if (activeCurtain != StageCurtain.None && showModeActive) {
            val curtainColor = when (activeCurtain) {
                StageCurtain.Blackout -> Color.Black
                StageCurtain.Whiteout -> Color.White
                StageCurtain.None -> Color.Transparent
            }
            val curtainDesc = when (activeCurtain) {
                StageCurtain.Blackout -> "Stage blackout screen active. Press Escape, B, or click to resume."
                StageCurtain.Whiteout -> "Stage whiteout screen active. Press Escape, W, or click to resume."
                StageCurtain.None -> ""
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(curtainColor)
                    .pointerInput(activeCurtain) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent()
                                event.changes.forEach { it.consume() }
                                if (event.type == PointerEventType.Press) {
                                    controller.clearCurtain()
                                }
                            }
                        }
                    }
                    .testTag("ciacola-stage-curtain")
                    .semantics { contentDescription = curtainDesc },
            )
        }

        if (isStoryJumpOpen) {
            CiacolaJewelTheme(isDark = isDark) {
                QuickSlideJumpOverlay(
                    controller = controller,
                    navigationScope = scope,
                    onDismiss = { fromEsc -> controller.closeJumpDialog(fromEscapeKey = fromEsc) },
                )
            }
        }
    }
}

/**
 * Lightweight Jewel dialog overlay for `Cmd+G` quick slide jump (`1..N`).
 */
@Composable
internal fun QuickSlideJumpOverlay(
    controller: PresenterController,
    navigationScope: CoroutineScope,
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
