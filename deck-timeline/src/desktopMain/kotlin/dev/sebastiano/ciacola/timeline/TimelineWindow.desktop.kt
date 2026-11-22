package dev.sebastiano.ciacola.timeline

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.MenuScope
import dev.bnorm.storyboard.ContentDecorator
import dev.bnorm.storyboard.easel.EaselWindow
import dev.bnorm.storyboard.easel.LocalOverviewOpen
import dev.bnorm.storyboard.easel.LocalPresentingFullscreen
import dev.sebastiano.ciacola.theme.DeckThemePreset
import dev.sebastiano.ciacola.ui.CiacolaJewelTheme
import java.awt.Cursor
import kotlinx.coroutines.CoroutineScope
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.Tooltip

/** Inset around the docked stage preview in Edit mode. */
internal val EDIT_STAGE_INSET = 8.dp

/** Default stage share of the docked split when the timeline is expanded (timeline = 34%). */
const val DEFAULT_TIMELINE_SPLIT_FRACTION: Float = 0.66f
internal const val MIN_SPLIT_FRACTION: Float = 0.28f
internal const val MAX_SPLIT_FRACTION: Float = 0.82f

/**
 * Storyboard [EaselWindow] hosting the analytical document-backed Timeline Studio (`F5` docked in
 * the Story window, `F6` detached standalone window).
 */
@Stable
class DocumentTimelineWindow(
    controller: DocumentTimelineController,
    var currentPresetProvider: () -> DeckThemePreset = { DeckThemePreset.StageDark },
    var suppressShortcutsProvider: () -> Boolean = { false },
    var onReturnFocusToStage: (() -> Unit)? = null,
    trailingPane: (@Composable () -> Unit)? = null,
    var leadingToolbarContent: (@Composable () -> Unit)? = null,
    var extraPreviewKeyHandler: ((KeyEvent, CoroutineScope) -> Boolean)? = null,
    var extraMenuContent: (@Composable MenuScope.() -> Unit)? = null,
    initialDocked: Boolean = false,
    initialDetachedVisible: Boolean = false,
    initialExpanded: Boolean = false,
    /** Full-width ~36dp App Bar rendered at the very top of the Story window (above stage + inspector). */
    topBarContent: (@Composable () -> Unit)? = null,
    /** Leading content of the bottom dock header (e.g. a `Timeline | Issues` tab switcher). */
    bottomDockHeaderLeading: (@Composable () -> Unit)? = null,
    /** Trailing content of the bottom dock header when [alternateBottomContent] is active. */
    bottomDockHeaderTrailing: (@Composable () -> Unit)? = null,
    /** When non-null, the bottom dock shows this (e.g. Issues) instead of the timeline tracks. */
    alternateBottomContent: (@Composable () -> Unit)? = null,
    /** Full-window overlay drawn above all Studio chrome (e.g. the App Bar Theme sheet). */
    fullWindowOverlayContent: (@Composable () -> Unit)? = null,
    /** When `false` (e.g. Present mode), the slide renders full-bleed with zero Studio chrome. */
    showStudioChrome: Boolean = true,
    /** Called whenever the user drags or resets the stage/timeline split (persist it per deck). */
    onSplitFractionChanged: ((Float) -> Unit)? = null,
) : EaselWindow {
    /**
     * When `true`, the window never pulls focus back to the stage (text input, or focus on Studio
     * chrome rendered outside this decorator: App Bar, Theme sheet, Issues dock).
     */
    var suppressFocusRecoveryProvider: () -> Boolean = { suppressShortcutsProvider() }

    /** Snapshot-backed so a deck switch (new controller / pane) always recomposes the window. */
    var controller: DocumentTimelineController by mutableStateOf(controller)
    var trailingPane: (@Composable () -> Unit)? by mutableStateOf(trailingPane)

    override val name: String = "Timeline"
    override val minimumSize: DpSize get() = DpSize(720.dp, 420.dp)

    /** Full-width ~36dp App Bar rendered at the very top of the Story window (above stage + inspector). */
    var topBarContent: (@Composable () -> Unit)? by mutableStateOf(topBarContent)

    /** Leading content of the bottom dock header (e.g. a `Timeline | Issues` tab switcher). */
    var bottomDockHeaderLeading: (@Composable () -> Unit)? by mutableStateOf(bottomDockHeaderLeading)

    /** Trailing content of the bottom dock header when [alternateBottomContent] is active. */
    var bottomDockHeaderTrailing: (@Composable () -> Unit)? by mutableStateOf(bottomDockHeaderTrailing)

    /** When non-null, the bottom dock shows this (e.g. Issues) while stage + inspector stay visible. */
    var alternateBottomContent: (@Composable () -> Unit)? by mutableStateOf(alternateBottomContent)

    /** Full-window overlay drawn above all Studio chrome (e.g. the App Bar Theme sheet). */
    var fullWindowOverlayContent: (@Composable () -> Unit)? by mutableStateOf(fullWindowOverlayContent)

    /** When `false` (e.g. Present mode), the slide renders full-bleed with zero Studio chrome. */
    var showStudioChrome: Boolean by mutableStateOf(showStudioChrome)

    /** Called whenever the user drags or resets the stage/timeline split (persist it per deck). */
    var onSplitFractionChanged: ((Float) -> Unit)? = onSplitFractionChanged

    var dockedInStoryWindow: Boolean by mutableStateOf(initialDocked)
    var detachedWindowVisible: Boolean by mutableStateOf(initialDetachedVisible)

    /**
     * Whether the docked timeline shows full tracks. Defaults to collapsed: only the compact
     * transport bar + header band (~60dp) sit under the stage until the author expands it.
     */
    var timelineExpanded: Boolean by mutableStateOf(initialExpanded)

    /** Stage share of the docked split when expanded (`0.66f` → timeline gets 34% of the height). */
    var splitFraction: Float by mutableFloatStateOf(DEFAULT_TIMELINE_SPLIT_FRACTION)

    fun toggleTimelineExpanded() {
        timelineExpanded = !timelineExpanded
    }

    private fun updateSplitFraction(value: Float) {
        val clamped = value.coerceIn(MIN_SPLIT_FRACTION, MAX_SPLIT_FRACTION)
        if (clamped == splitFraction) return
        splitFraction = clamped
        onSplitFractionChanged?.invoke(clamped)
    }

    override var visible: Boolean
        get() = detachedWindowVisible
        set(value) {
            detachedWindowVisible = value
        }

    val authoringActive: Boolean
        get() = dockedInStoryWindow || detachedWindowVisible

    override val decorator: ContentDecorator = ContentDecorator { content ->
        val scope = rememberCoroutineScope()
        val fallbackFocusRequester = remember { FocusRequester() }
        var subtreeHasFocus by remember { mutableStateOf(false) }
        val overviewOpen = LocalOverviewOpen.current
        val presentingFullscreen = LocalPresentingFullscreen.current
        // Overview (Esc), fullscreen Present and chrome-less modes always get the whole window:
        // never squeezed by the App Bar, the timeline or the right-hand inspector.
        val hideChrome = overviewOpen || presentingFullscreen || !this@DocumentTimelineWindow.showStudioChrome

        val suppressFocusRecovery = suppressFocusRecoveryProvider()
        // Keyed on the suppression too: closing chrome that held focus (Theme sheet via Esc/F7)
        // re-runs recovery and returns focus to the stage.
        LaunchedEffect(hideChrome, subtreeHasFocus, overviewOpen, suppressFocusRecovery) {
            if (!subtreeHasFocus && !overviewOpen && !suppressFocusRecoveryProvider()) {
                withFrameNanos {}
                if (!subtreeHasFocus && !overviewOpen && !suppressFocusRecoveryProvider()) {
                    onReturnFocusToStage?.invoke()
                    withFrameNanos {}
                    if (!subtreeHasFocus && !suppressFocusRecoveryProvider()) {
                        runCatching { fallbackFocusRequester.requestFocus() }
                    }
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .onFocusChanged { subtreeHasFocus = it.hasFocus }
                .focusRequester(fallbackFocusRequester)
                .focusable(),
        ) {
            if (hideChrome) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .onPreviewKeyEvent { ev -> extraPreviewKeyHandler?.invoke(ev, scope) == true }
                        .testTag("ciacola-preview-viewport"),
                ) { content() }
            } else {
                StudioDecoratedContent(content)
            }
        }
    }

    @Composable
    private fun StudioDecoratedContent(content: @Composable () -> Unit) {
        val scope = rememberCoroutineScope()
        val isDark = currentPresetProvider() == DeckThemePreset.StageDark
        var totalHeightPx by remember { mutableFloatStateOf(720f) }
        val rootFocusRequester = remember { FocusRequester() }
        val focusManager = LocalFocusManager.current
        val studioFocusState = remember { StudioA11yFocusState() }
        var rootHadFocus by remember { mutableStateOf(false) }
        var rootHasFocus by remember { mutableStateOf(false) }
        val hoistedPane = trailingPane
        val topBar = topBarContent
        val overlay = fullWindowOverlayContent

        LaunchedEffect(controller) {
            snapshotFlow { listOf(rootHasFocus, controller.store.canUndo, controller.store.canRedo, suppressFocusRecoveryProvider()) }
                .collect { state ->
                    val hasFocus = state[0]
                    if (rootHadFocus && !hasFocus && !suppressFocusRecoveryProvider()) {
                        withFrameNanos { }
                        if (rootHadFocus && !rootHasFocus && !suppressFocusRecoveryProvider()) {
                            runCatching { rootFocusRequester.requestFocus() }
                        }
                    }
                }
        }

        var wasDocked by remember { mutableStateOf(dockedInStoryWindow) }
        LaunchedEffect(dockedInStoryWindow) {
            val nowDocked = dockedInStoryWindow
            if (wasDocked && !nowDocked) {
                withFrameNanos {}
                val onReturn = onReturnFocusToStage
                if (onReturn != null) {
                    onReturn.invoke()
                } else {
                    runCatching {
                        rootFocusRequester.requestFocus()
                        focusManager.moveFocus(FocusDirection.Next)
                    }
                }
            }
            wasDocked = nowDocked
        }

        CompositionLocalProvider(LocalStudioA11yFocusState provides studioFocusState) {
            Box(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize()) {
                    if (topBar != null) {
                        CiacolaJewelTheme(isDark = isDark) {
                            Box(Modifier.fillMaxWidth().testTag("ciacola-studio-top-bar-slot")) { topBar() }
                        }
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .onSizeChanged {
                                totalHeightPx = it.height.toFloat().coerceAtLeast(240f)
                                totalHeightPxForDrag = totalHeightPx
                            }
                            .onFocusChanged { state ->
                                if (state.hasFocus) rootHadFocus = true
                                rootHasFocus = state.hasFocus
                            }
                            .focusRequester(rootFocusRequester)
                            .focusable()
                            .onPreviewKeyEvent { ev ->
                                if (extraPreviewKeyHandler?.invoke(ev, scope) == true) {
                                    true
                                } else if (authoringActive && !suppressShortcutsProvider()) {
                                    controller.handleKeyEvent(ev, studioFocusState.textInputHasFocus)
                                } else {
                                    false
                                }
                            },
                    ) {
                        if (dockedInStoryWindow) {
                            Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                                DockedStageAndBottomDock(isDark, hoistedPane == null, content)
                            }
                            if (hoistedPane != null) {
                                CiacolaJewelTheme(isDark = isDark) {
                                    Row(modifier = Modifier.fillMaxHeight()) { hoistedPane() }
                                }
                            }
                        } else if (detachedWindowVisible && hoistedPane != null) {
                            Box(Modifier.weight(1f).fillMaxHeight().testTag("ciacola-preview-viewport")) { content() }
                            CiacolaJewelTheme(isDark = isDark) {
                                Row(modifier = Modifier.fillMaxHeight()) { hoistedPane() }
                            }
                        } else {
                            Box(Modifier.fillMaxSize().testTag("ciacola-preview-viewport")) { content() }
                        }
                    }
                }
                if (overlay != null) {
                    CiacolaJewelTheme(isDark = isDark) {
                        Box(Modifier.fillMaxSize().testTag("ciacola-studio-overlay-slot")) { overlay() }
                    }
                }
            }
        }
    }

    /** Docked (Edit) stage preview: a crisp 8dp inset against the App Bar, inspector and dock. */
    @Composable
    private fun EditStageInset(content: @Composable () -> Unit) {
        Box(Modifier.fillMaxSize().padding(EDIT_STAGE_INSET).testTag("ciacola-edit-stage-inset")) { content() }
    }

    /** Stage on top; below it either the collapsed bar, the expanded timeline, or the alternate tab. */
    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun ColumnScope.DockedStageAndBottomDock(
        isDark: Boolean,
        showInlineInspector: Boolean,
        content: @Composable () -> Unit,
    ) {
        val alternate = alternateBottomContent
        val dockLeading = bottomDockHeaderLeading
        val showSplit = alternate != null || timelineExpanded
        if (!showSplit) {
            Box(Modifier.fillMaxWidth().weight(1f).testTag("ciacola-preview-viewport")) { EditStageInset(content) }
            CiacolaJewelTheme(isDark = isDark) {
                Divider(Orientation.Horizontal, Modifier.fillMaxWidth())
                Box(Modifier.fillMaxWidth().testTag("ciacola-timeline-studio")) {
                    DocumentTimelineCollapsedBar(
                        controller = controller,
                        surface = TimelineSurface.Docked,
                        leadingToolbarContent = leadingToolbarContent,
                        headerLeadingContent = dockLeading,
                        onToggleExpanded = ::toggleTimelineExpanded,
                    )
                }
            }
            return
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(splitFraction.coerceIn(MIN_SPLIT_FRACTION, MAX_SPLIT_FRACTION))
                .testTag("ciacola-preview-viewport"),
        ) { EditStageInset(content) }
        CiacolaJewelTheme(isDark = isDark) {
            val splitHoverSource = remember { MutableInteractionSource() }
            val splitHovered by splitHoverSource.collectIsHoveredAsState()
            val splitBg = if (splitHovered) JewelTheme.globalColors.outlines.focused else JewelTheme.globalColors.borders.normal
            Tooltip(tooltip = { Text("Drag to resize the stage and timeline (double-click to reset)") }) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(5.dp)
                        .background(splitBg)
                        .pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(Cursor.N_RESIZE_CURSOR)))
                        .combinedClickable(
                            interactionSource = splitHoverSource,
                            indication = null,
                            onClick = {},
                            onDoubleClick = { updateSplitFraction(DEFAULT_TIMELINE_SPLIT_FRACTION) },
                        )
                        .pointerInput(Unit) {
                            detectVerticalDragGestures { change, dragAmount ->
                                change.consume()
                                updateSplitFraction(splitFraction + dragAmount / totalHeightPxForDrag)
                            }
                        }
                        .testTag("ciacola-studio-horizontal-splitter"),
                )
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight((1f - splitFraction).coerceIn(1f - MAX_SPLIT_FRACTION, 1f - MIN_SPLIT_FRACTION))
                .testTag(if (alternate != null) "ciacola-bottom-dock-alternate" else "ciacola-timeline-studio"),
        ) {
            CiacolaJewelTheme(isDark = isDark) {
                if (alternate != null) {
                    Column(Modifier.fillMaxSize().background(JewelTheme.globalColors.panelBackground)) {
                        Row(
                            modifier = Modifier.fillMaxWidth().height(STUDIO_CONTROL_HEIGHT + 8.dp).padding(horizontal = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (dockLeading != null) {
                                Box(Modifier.testTag("ciacola-timeline-dock-header-leading")) { dockLeading() }
                            }
                            androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                            val dockTrailing = bottomDockHeaderTrailing
                            if (dockTrailing != null) {
                                Box(Modifier.testTag("ciacola-timeline-dock-header-trailing")) { dockTrailing() }
                            }
                        }
                        Divider(Orientation.Horizontal, Modifier.fillMaxWidth())
                        Box(Modifier.fillMaxWidth().weight(1f)) { alternate() }
                    }
                } else {
                    DocumentTimelineStudioPanel(
                        controller = controller,
                        surface = TimelineSurface.Docked,
                        showInlineInspector = showInlineInspector,
                        leadingToolbarContent = leadingToolbarContent,
                        headerLeadingContent = dockLeading,
                        onToggleExpanded = ::toggleTimelineExpanded,
                    )
                }
            }
        }
    }

    /** Height of the docked column, used to convert splitter drags into split fractions. */
    private var totalHeightPxForDrag: Float = 720f

    @Composable
    override fun MenuScope.Menu() {
        CheckboxItem(
            text = "Dock Timeline in Story Window (F5)",
            checked = dockedInStoryWindow,
        ) {
            dockedInStoryWindow = it
            if (it) detachedWindowVisible = false
        }
        CheckboxItem(
            text = "Show Detached Timeline Window (F6)",
            checked = visible,
        ) {
            if (it) dockedInStoryWindow = false
            visible = it
        }
        val extraMenu = extraMenuContent
        if (extraMenu != null) {
            Separator()
            extraMenu()
        }
        Separator()
        Item(text = "Zoom Timeline to Fit", onClick = { controller.viewport.zoomToFit() })
    }

    @Composable
    override fun Content() {
        val scope = rememberCoroutineScope()
        val isDark = currentPresetProvider() == DeckThemePreset.StageDark
        val rootFocusRequester = remember { FocusRequester() }
        val studioFocusState = remember { StudioA11yFocusState() }
        var rootHadFocus by remember { mutableStateOf(false) }
        var rootHasFocus by remember { mutableStateOf(false) }
        val hoistedPane = trailingPane
        LaunchedEffect(Unit) {
            if (!suppressShortcutsProvider()) {
                runCatching { rootFocusRequester.requestFocus() }
            }
        }
        LaunchedEffect(controller) {
            snapshotFlow { Triple(rootHasFocus, controller.store.canUndo, controller.store.canRedo) }
                .collect { (hasFocus, _, _) ->
                    if (rootHadFocus && !hasFocus && !suppressShortcutsProvider()) {
                        withFrameNanos { }
                        if (rootHadFocus && !rootHasFocus && !suppressShortcutsProvider()) {
                            runCatching { rootFocusRequester.requestFocus() }
                        }
                    }
                }
        }
        CompositionLocalProvider(LocalStudioA11yFocusState provides studioFocusState) {
            Box(
                Modifier
                    .fillMaxSize()
                    .onFocusChanged { state ->
                        if (state.hasFocus) rootHadFocus = true
                        rootHasFocus = state.hasFocus
                    }
                    .focusRequester(rootFocusRequester)
                    .focusable()
                    .onPreviewKeyEvent { ev ->
                        if (extraPreviewKeyHandler?.invoke(ev, scope) == true) {
                            true
                        } else if (!suppressShortcutsProvider()) {
                            controller.handleKeyEvent(ev, studioFocusState.textInputHasFocus)
                        } else {
                            false
                        }
                    }
                    .testTag("ciacola-timeline-studio"),
            ) {
                CiacolaJewelTheme(isDark = isDark) {
                    DocumentTimelineStudioPanel(
                        controller = controller,
                        surface = TimelineSurface.Detached,
                        showInlineInspector = hoistedPane == null,
                        leadingToolbarContent = leadingToolbarContent,
                    )
                }
            }
        }
    }
}

@Composable
fun rememberDocumentTimelineWindow(
    controller: DocumentTimelineController,
    currentPresetProvider: () -> DeckThemePreset = { DeckThemePreset.StageDark },
    suppressShortcutsProvider: () -> Boolean = { false },
    onReturnFocusToStage: (() -> Unit)? = null,
    trailingPane: (@Composable () -> Unit)? = null,
    leadingToolbarContent: (@Composable () -> Unit)? = null,
    extraPreviewKeyHandler: ((KeyEvent, CoroutineScope) -> Boolean)? = null,
    extraMenuContent: (@Composable MenuScope.() -> Unit)? = null,
    initialDocked: Boolean = false,
    initialExpanded: Boolean = false,
    topBarContent: (@Composable () -> Unit)? = null,
    bottomDockHeaderLeading: (@Composable () -> Unit)? = null,
    bottomDockHeaderTrailing: (@Composable () -> Unit)? = null,
    alternateBottomContent: (@Composable () -> Unit)? = null,
    fullWindowOverlayContent: (@Composable () -> Unit)? = null,
    showStudioChrome: Boolean = true,
    onSplitFractionChanged: ((Float) -> Unit)? = null,
): DocumentTimelineWindow = remember {
    DocumentTimelineWindow(
        controller = controller,
        currentPresetProvider = currentPresetProvider,
        suppressShortcutsProvider = suppressShortcutsProvider,
        onReturnFocusToStage = onReturnFocusToStage,
        trailingPane = trailingPane,
        leadingToolbarContent = leadingToolbarContent,
        extraPreviewKeyHandler = extraPreviewKeyHandler,
        extraMenuContent = extraMenuContent,
        initialDocked = initialDocked,
        initialExpanded = initialExpanded,
    )
}.also {
    it.topBarContent = topBarContent
    it.bottomDockHeaderLeading = bottomDockHeaderLeading
    it.bottomDockHeaderTrailing = bottomDockHeaderTrailing
    it.alternateBottomContent = alternateBottomContent
    it.fullWindowOverlayContent = fullWindowOverlayContent
    it.showStudioChrome = showStudioChrome
    it.onSplitFractionChanged = onSplitFractionChanged
    it.controller = controller
    it.currentPresetProvider = currentPresetProvider
    it.suppressShortcutsProvider = suppressShortcutsProvider
    it.onReturnFocusToStage = onReturnFocusToStage
    it.trailingPane = trailingPane
    it.leadingToolbarContent = leadingToolbarContent
    it.extraPreviewKeyHandler = extraPreviewKeyHandler
    it.extraMenuContent = extraMenuContent
}
