package dev.sebastiano.ciacola.presenter

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyShortcut
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.MenuScope
import dev.bnorm.storyboard.AdvanceDirection
import dev.bnorm.storyboard.ContentDecorator
import dev.bnorm.storyboard.Storyboard
import dev.bnorm.storyboard.easel.Animatic
import dev.bnorm.storyboard.easel.EaselWindow
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
    val onRestoreStageFocus: () -> Boolean = { false },
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
        LaunchedEffect(controller) {
            while (true) {
                controller.refreshDisplays()
                delay(2_000L)
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
    openAuthoringToolsProvider: () -> List<String> = { emptyList() },
    unbakedDomainsProvider: () -> List<String> = { emptyList() },
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
            openAuthoringToolsProvider = openAuthoringToolsProvider,
            unbakedDomainsProvider = unbakedDomainsProvider,
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
