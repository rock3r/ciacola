package dev.sebastiano.ciacola.presenter

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyShortcut
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.MenuScope
import dev.bnorm.storyboard.AdvanceDirection
import dev.bnorm.storyboard.ContentDecorator
import dev.bnorm.storyboard.Storyboard
import dev.bnorm.storyboard.easel.Animatic
import dev.bnorm.storyboard.easel.EaselWindow
import dev.bnorm.storyboard.easel.LocalOverviewOpen
import dev.sebastiano.ciacola.theme.DeckThemePreset
import dev.sebastiano.ciacola.ui.CiacolaJewelTheme
import java.awt.Window as AwtWindow
import kotlinx.coroutines.delay

/**
 * Storyboard [EaselWindow] providing Ciacola's Jewel-styled `F2` Presenter View, live stage
 * blackout/whiteout curtains (`B`/`W`), quick slide jump (`Cmd+G`), display routing, and
 * pre-show readiness guardrails.
 */
class PresenterWindow(
    val controller: PresenterController,
    val currentPresetProvider: () -> DeckThemePreset = { DeckThemePreset.StageDark },
    internal val onRestoreStageFocus: () -> Boolean = { false },
) : EaselWindow {
    override val name: String = "Presenter"
    override var visible: Boolean by mutableStateOf(false)
    override val initialSize: DpSize get() = DpSize(1120.dp, 680.dp)
    override val minimumSize: DpSize get() = DpSize(760.dp, 500.dp)

    override val decorator: ContentDecorator = ContentDecorator { content ->
        PresenterStageDecorator(
            controller = controller,
            isDark = currentPresetProvider() == DeckThemePreset.StageDark,
            onRestoreStageFocus = onRestoreStageFocus,
            content = content,
        )
    }

    @Composable
    override fun MenuScope.Menu() {
        val scope = rememberCoroutineScope()
        LaunchedEffect(controller, visible) {
            controller.refreshDisplays()
            while (visible) {
                delay(2_000L)
                controller.refreshDisplays()
            }
        }
        val displays = controller.availableDisplays
        val readiness = controller.readinessStatus()

        CheckboxItem(
            text = "Show Presenter View",
            checked = visible,
            shortcut = KeyShortcut(Key.F2),
        ) {
            visible = it
        }
        Separator()
        Item(
            text = "Previous Beat",
            enabled = controller.canStepBackward,
            onClick = { controller.step(scope, AdvanceDirection.Backward) },
        )
        Item(
            text = "Next Beat",
            enabled = controller.canStepForward,
            onClick = { controller.step(scope, AdvanceDirection.Forward) },
        )
        Item(
            text = "Jump to Slide…",
            shortcut = KeyShortcut(Key.G, meta = true),
            enabled = !controller.isOverviewOpen && !controller.isExternalTextInputActive,
            onClick = {
                val presenterFocused = runCatching {
                    AwtWindow.getWindows()
                        .filterIsInstance<ComposeWindow>()
                        .any { it.isFocused && it.title.endsWith(" - Presenter") }
                }.getOrDefault(false)
                controller.openJumpDialog(
                    if (presenterFocused) JumpDialogHost.Presenter else JumpDialogHost.Story
                )
            },
        )
        Separator()
        CheckboxItem(
            text = "Blackout Screen (B)",
            checked = controller.curtain == StageCurtain.Blackout,
            enabled = controller.isShowModeActive(),
        ) {
            controller.toggleCurtain(StageCurtain.Blackout)
        }
        CheckboxItem(
            text = "Whiteout Screen (W)",
            checked = controller.curtain == StageCurtain.Whiteout,
            enabled = controller.isShowModeActive(),
        ) {
            controller.toggleCurtain(StageCurtain.Whiteout)
        }
        if (displays.isNotEmpty()) {
            Separator()
            Menu("Present on Display") {
                for (screen in displays) {
                    Item(
                        text = screen.label,
                        onClick = { controller.presentOnDisplay(screen) },
                    )
                }
            }
            Item(
                text = "Swap Audience & Presenter Displays",
                enabled = displays.size >= 2,
                onClick = { controller.swapAudienceAndPresenterDisplays() },
            )
        }
        Separator()
        Item(
            text = "Enter Clean Show Mode",
            enabled = readiness.hasClosableAuthoringTools,
            onClick = { controller.enterCleanShowMode() },
        )
    }

    @Composable
    override fun Content() {
        val isDark = currentPresetProvider() == DeckThemePreset.StageDark
        CiacolaJewelTheme(isDark = isDark) {
            PresenterDashboard(controller = controller)
        }
    }
}

/**
 * Remembers a [PresenterWindow] across Storyboard rebuilds (hot reload, theme switches,
 * timeline edits) without closing the window or resetting the talk timer.
 */
@Composable
fun rememberPresenterWindow(
    animatic: Animatic,
    speakerNotesProvider: (Storyboard) -> DeckSpeakerNotes? = {
        StoryboardSpeakerNotesRegistry.forStoryboard(it)
    },
    currentPresetProvider: () -> DeckThemePreset = { DeckThemePreset.StageDark },
    isStudioOpenProvider: () -> Boolean = { false },
    isInspectOpenProvider: () -> Boolean = { false },
    hasUnbakedOverridesProvider: () -> Boolean = { false },
    onEnterCleanShowMode: () -> Unit = {},
    isExternalTextInputActiveProvider: () -> Boolean = { false },
    onRestoreStageFocus: () -> Boolean = { false },
    displayRouter: DisplayRoutingAdapter = remember { AwtDisplayRouter() },
): PresenterWindow {
    val controller = remember {
        PresenterController(
            initialAnimatic = animatic,
            speakerNotesProvider = speakerNotesProvider,
            isStudioOpenProvider = isStudioOpenProvider,
            isInspectOpenProvider = isInspectOpenProvider,
            hasUnbakedOverridesProvider = hasUnbakedOverridesProvider,
            onEnterCleanShowModeAction = onEnterCleanShowMode,
            isExternalTextInputActiveProvider = isExternalTextInputActiveProvider,
            onRestoreStageFocus = { onRestoreStageFocus() },
            displayRouter = displayRouter,
        )
    }.also {
        it.animatic = animatic
    }

    return remember(controller) {
        PresenterWindow(
            controller = controller,
            currentPresetProvider = currentPresetProvider,
            onRestoreStageFocus = onRestoreStageFocus,
        )
    }
}

@Composable
private fun PresenterStageDecorator(
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
