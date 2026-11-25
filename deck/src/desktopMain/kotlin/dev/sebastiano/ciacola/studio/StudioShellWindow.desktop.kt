package dev.sebastiano.ciacola.studio

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.KeyShortcut
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.MenuScope
import dev.bnorm.storyboard.ContentDecorator
import dev.bnorm.storyboard.easel.EaselWindow
import dev.bnorm.storyboard.easel.LocalOverviewOpen
import dev.bnorm.storyboard.easel.LocalPresentingFullscreen
import dev.bnorm.storyboard.easel.LocalStageViewportHairline
import dev.bnorm.storyboard.easel.overview.OverviewChromeStyle
import dev.sebastiano.ciacola.inspector.lint.LintAuditScope
import dev.sebastiano.ciacola.review.LocalAudiencePresenting
import dev.sebastiano.ciacola.review.LocalReviewHudOwnedByAppBar
import kotlinx.coroutines.delay
import org.jetbrains.jewel.foundation.theme.JewelTheme

/**
 * Story-window shell decorator hosting the Studio App Bar above the stage and right inspector,
 * the deck-level Theme sheet overlay, and the bottom Issues dock.
 *
 * Placed directly outside the timeline decorator in the window list so the App Bar spans the full
 * width above both the stage and the inspector. Every piece of chrome hides while presenting
 * (`Present` mode or fullscreen) and while the Esc Overview is open.
 *
 * Integration hook: once the timeline window exposes `topBarContent` /
 * `bottomAlternateTabContent` / `fullWindowOverlayContent` / `showChrome`, hand [appBar],
 * [issuesDock] and [themeSheet] to those slots and keep this decorator for the Present-mode
 * Esc / hover reveal and the review HUD hand-off.
 */
@Stable
class StudioShellWindow(
    var workspace: StudioWorkspaceController,
    var appBar: @Composable () -> Unit,
    var themeSheet: @Composable () -> Unit,
    var issuesDock: (@Composable () -> Unit)? = null,
) : EaselWindow {
    override val name: String = "Studio"
    override var visible: Boolean = false

    override val decorator: ContentDecorator = ContentDecorator { content ->
        StudioShell(
            workspace = workspace,
            appBar = appBar,
            themeSheet = themeSheet,
            issuesDock = issuesDock,
            content = content,
        )
    }

    @Composable
    override fun MenuScope.Menu() {
        val ws = workspace
        for (mode in StudioMode.entries) {
            RadioButtonItem(
                text = "${mode.label} Mode",
                selected = ws.mode == mode,
                shortcut = when (mode) {
                    StudioMode.Edit -> KeyShortcut(Key.E, meta = true, shift = true)
                    StudioMode.Review -> KeyShortcut(Key.R, meta = true, shift = true)
                    StudioMode.Present -> KeyShortcut(Key.P, meta = true, shift = true)
                },
                onClick = { ws.switchMode(mode) },
            )
        }
        Separator()
        CheckboxItem(text = "Deck Theme (F7)", checked = ws.themeSheetOpen) { ws.toggleDesignSystemEditor() }
        CheckboxItem(text = "Issues (F8)", checked = ws.issuesDockOpen) { ws.toggleDeckLinter() }
        Separator()
        for (tab in StudioInspectorTab.entries) {
            RadioButtonItem(
                text = "${tab.label} Inspector",
                selected = ws.isStudioOpen && ws.activeTab == tab,
                onClick = { ws.selectTabInStudio(tab) },
            )
        }
    }

    @Composable
    override fun Content() = Unit
}

@Composable
fun rememberStudioShellWindow(
    workspace: StudioWorkspaceController,
    appBar: @Composable () -> Unit,
    themeSheet: @Composable () -> Unit,
    issuesDock: (@Composable () -> Unit)? = null,
): StudioShellWindow = remember {
    StudioShellWindow(workspace, appBar, themeSheet, issuesDock)
}.also {
    it.workspace = workspace
    it.appBar = appBar
    it.themeSheet = themeSheet
    it.issuesDock = issuesDock
}

/** Whether the App Bar and Studio chrome are shown (pure for tests). */
fun isStudioChromeVisible(mode: StudioMode, overviewOpen: Boolean, presentingFullscreen: Boolean): Boolean =
    mode != StudioMode.Present && !overviewOpen && !presentingFullscreen

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun StudioShell(
    workspace: StudioWorkspaceController,
    appBar: @Composable () -> Unit,
    themeSheet: @Composable () -> Unit,
    issuesDock: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val overviewOpen = LocalOverviewOpen.current
    val fullscreen = LocalPresentingFullscreen.current
    SideEffect {
        if (workspace.overviewOpen != overviewOpen) workspace.overviewOpen = overviewOpen
    }
    val chromeVisible = isStudioChromeVisible(workspace.mode, overviewOpen, fullscreen)
    val presenting = workspace.mode == StudioMode.Present
    var swallowEscapeKeyUp by remember { mutableStateOf(false) }
    var revealBar by remember { mutableStateOf(false) }
    var appBarFocused by remember { mutableStateOf(false) }
    var themeSheetFocused by remember { mutableStateOf(false) }
    var issuesDockFocused by remember { mutableStateOf(false) }
    val chromeFocused = appBarFocused || themeSheetFocused || issuesDockFocused
    SideEffect {
        // The timeline's focus recovery must never yank focus off Studio chrome back to the stage.
        if (workspace.chromeHasFocus != chromeFocused) workspace.chromeHasFocus = chromeFocused
    }
    var pointerOverBar by remember { mutableStateOf(false) }

    LaunchedEffect(revealBar, pointerOverBar) {
        if (revealBar && !pointerOverBar) {
            delay(PRESENT_REVEAL_HIDE_DELAY_MS)
            revealBar = false
        }
    }
    LaunchedEffect(presenting) {
        if (!presenting) {
            revealBar = false
            pointerOverBar = false
        }
    }

    CompositionLocalProvider(
        LocalReviewHudOwnedByAppBar provides (chromeVisible || (presenting && !fullscreen)),
        LocalAudiencePresenting provides (presenting || fullscreen),
    ) {
        val chromeBackground = studioChromeBackground()
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                // Opaque shell paint: the panel colour behind authoring chrome, black behind the stage
                // while presenting, so no light window paint ever bleeds through as a 1px stage edge.
                .background(studioShellBackground(chromeVisible, chromeBackground))
                .onPreviewKeyEvent { ev ->
                    if (ev.key != Key.Escape) return@onPreviewKeyEvent false
                    if (ev.type == KeyEventType.KeyUp && swallowEscapeKeyUp) {
                        swallowEscapeKeyUp = false
                        return@onPreviewKeyEvent true
                    }
                    if (ev.type == KeyEventType.KeyDown &&
                        workspace.themeSheetOpen &&
                        !workspace.isTextInputActiveProvider()
                    ) {
                        workspace.themeSheetOpen = false
                        swallowEscapeKeyUp = true
                        return@onPreviewKeyEvent true
                    }
                    false
                }
                .testTag("ciacola-studio-shell"),
        ) {
            val widthDp = maxWidth.value
            SideEffect {
                if (workspace.shellWidthDp != widthDp) workspace.shellWidthDp = widthDp
            }
            Column(modifier = Modifier.fillMaxSize()) {
                if (chromeVisible) {
                    Box(Modifier.onFocusChanged { appBarFocused = it.hasFocus }) { appBar() }
                }
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    // The stage hairline is an authoring aid: Edit only, never in Review or Present.
                    CompositionLocalProvider(LocalStageViewportHairline provides (workspace.mode == StudioMode.Edit)) {
                        content()
                    }
                    if (chromeVisible && workspace.themeSheetOpen) {
                        Box(modifier = Modifier.align(Alignment.TopEnd).onFocusChanged { themeSheetFocused = it.hasFocus }) {
                            themeSheet()
                        }
                    }
                }
                if (chromeVisible && workspace.issuesDockOpen && issuesDock != null) {
                    Box(Modifier.onFocusChanged { issuesDockFocused = it.hasFocus }) { issuesDock() }
                }
            }

            if (presenting && !overviewOpen && !fullscreen) {
                // Hover the top edge to reveal the App Bar while presenting (Esc also returns to Edit).
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .height(PRESENT_REVEAL_ZONE_HEIGHT)
                        .onPointerEvent(PointerEventType.Enter) { revealBar = true }
                        .testTag("ciacola-present-reveal-zone"),
                )
                AnimatedVisibility(
                    visible = revealBar,
                    enter = fadeIn() + slideInVertically { -it },
                    exit = fadeOut() + slideOutVertically { -it },
                    modifier = Modifier.align(Alignment.TopCenter),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .onPointerEvent(PointerEventType.Enter) { pointerOverBar = true }
                            .onPointerEvent(PointerEventType.Exit) { pointerOverBar = false }
                            .testTag("ciacola-present-revealed-app-bar"),
                    ) {
                        appBar()
                    }
                }
            }
        }
    }
}

/** Shell background: the chrome panel colour while authoring, black while presenting. */
fun studioShellBackground(chromeVisible: Boolean, panelBackground: Color): Color =
    if (chromeVisible) panelBackground else Color.Black

private val PRESENT_REVEAL_ZONE_HEIGHT = 6.dp
private const val PRESENT_REVEAL_HIDE_DELAY_MS = 600L

/** Binds [StudioAppBar] to the live [StudioWorkspaceController] state. */
@Composable
fun StudioAppBarHost(
    workspace: StudioWorkspaceController,
    breadcrumb: String,
    presenterOpen: Boolean,
    onTogglePresenter: () -> Unit,
    reducedMotion: Boolean = false,
    ambientLoopsPaused: Boolean = false,
    isExporting: Boolean = false,
    onExportPdf: (() -> Unit)? = null,
    onExportVideo: (() -> Unit)? = null,
    onToggleReducedMotion: (() -> Unit)? = null,
    onToggleAmbientLoopsPaused: (() -> Unit)? = null,
    deckSwitcher: @Composable () -> Unit,
) {
    val store = workspace.store
    val hasActiveGesture = workspace.timelineController.hasActiveGesture ||
        workspace.elementController.hasActiveTransaction ||
        workspace.themeController.hasActiveTransaction ||
        store.hasOpenTransaction
    val model = StudioAppBarModel(
        breadcrumb = breadcrumb,
        mode = workspace.mode,
        canUndo = store.canUndo || hasActiveGesture,
        canRedo = store.canRedo,
        saveState = workspace.saveState,
        themeOpen = workspace.themeSheetOpen,
        issuesOpen = workspace.issuesDockOpen,
        timelineOpen = workspace.isTimelineTracksOpen,
        presenterOpen = presenterOpen,
        errorCount = workspace.lintController.errorCount,
        warningCount = workspace.lintController.warningCount,
        inspectorVisible = workspace.isInspectorVisible,
        reducedMotion = reducedMotion,
        ambientLoopsPaused = ambientLoopsPaused,
        isExporting = isExporting,
        issuesAudited = workspace.lintController.auditScope != LintAuditScope.None,
    )
    val actions = remember(
        workspace,
        onTogglePresenter,
        onExportPdf,
        onExportVideo,
        onToggleReducedMotion,
        onToggleAmbientLoopsPaused,
    ) {
        StudioAppBarActions(
            onSelectMode = { workspace.switchMode(it) },
            onUndo = { workspace.undo() },
            onRedo = { workspace.redo() },
            onToggleTheme = { workspace.toggleDesignSystemEditor() },
            onToggleIssues = { workspace.toggleDeckLinter() },
            onToggleTimeline = { workspace.toggleStudioTimeline() },
            onTogglePresenter = onTogglePresenter,
            onRestoreStageFocus = { workspace.onRestoreStageFocus?.invoke() },
            onToggleInspector = { workspace.toggleInspector() },
            onExportPdf = onExportPdf,
            onExportVideo = onExportVideo,
            onToggleReducedMotion = onToggleReducedMotion,
            onToggleAmbientLoopsPaused = onToggleAmbientLoopsPaused,
        )
    }
    StudioAppBar(model = model, actions = actions, deckSwitcher = deckSwitcher)
}

/** Overview chrome palette and typeface taken from the Studio's Jewel theme. */
@Composable
fun rememberStudioOverviewChromeStyle(): OverviewChromeStyle {
    val colors = JewelTheme.globalColors
    val fontFamily = JewelTheme.defaultTextStyle.fontFamily ?: FontFamily.Default
    val isDark = JewelTheme.isDark
    return remember(colors, fontFamily, isDark) {
        val neutral = if (isDark) OverviewChromeStyle.NeutralDark else OverviewChromeStyle.NeutralLight
        neutral.copy(
            surface = colors.panelBackground,
            onSurface = colors.text.normal,
            mutedOnSurface = colors.text.info,
            accent = colors.outlines.focused,
            border = colors.borders.normal,
            fontFamily = fontFamily,
            quickJumpShortcutLabel = quickJumpShortcutLabel(),
        )
    }
}

/** Platform-aware Quick Jump hint: `⌘K` on macOS, `Ctrl+K` elsewhere. */
fun quickJumpShortcutLabel(osName: String = System.getProperty("os.name").orEmpty()): String =
    if (osName.lowercase().contains("mac")) "⌘K" else "Ctrl+K"
