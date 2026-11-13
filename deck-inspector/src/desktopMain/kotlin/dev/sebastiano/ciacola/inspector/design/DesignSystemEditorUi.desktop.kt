package dev.sebastiano.ciacola.inspector.design

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.KeyShortcut
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.MenuScope
import dev.bnorm.storyboard.easel.EaselWindow
import dev.sebastiano.ciacola.theme.DeckTheme
import dev.sebastiano.ciacola.theme.DeckThemePreset
import dev.sebastiano.ciacola.theme.DeckThemes
import dev.sebastiano.ciacola.theme.ReducedMotionMode
import dev.sebastiano.ciacola.ui.CiacolaJewelTheme
import dev.sebastiano.ciacola.ui.rememberAccessibleFocusRingColor
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.Tooltip
import org.jetbrains.jewel.ui.component.VerticalScrollbar
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.ui.typography

/**
 * Sections available inside [DesignSystemEditorPanel].
 */
enum class DesignSystemTab(val label: String) {
    Colors("Colors"),
    Typography("Typography"),
    Styles("Surfaces & Effects"),
    Motion("Motion Tokens"),
}

internal val DefaultClipboardWriter: (String) -> Unit = { text ->
    runCatching {
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
    }
}

/**
 * Embeddable Jewel-styled Design System Editor panel supporting live editing of per-preset colors
 * (with WCAG 2.1 contrast badges and 1-click Fix AA), typography scales & roles, surface/effect
 * geometry, and motion tokens.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun DesignSystemEditorPanel(
    store: DesignSystemStore,
    currentPreset: DeckThemePreset,
    onSelectPreset: (DeckThemePreset) -> Unit,
    modifier: Modifier = Modifier,
    baseThemeProvider: (DeckThemePreset) -> DeckTheme = { DeckThemes.forPreset(it) },
    clipboardWriter: (String) -> Unit = DefaultClipboardWriter,
    initialTab: DesignSystemTab = DesignSystemTab.Colors,
    onRequestClose: (() -> Unit)? = null,
) {
    val isDark = currentPreset == DeckThemePreset.StageDark
    var selectedTab by remember { mutableStateOf(initialTab) }
    var textInputFocused by remember { mutableStateOf(false) }
    var copiedConfirmation by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var copiedJob by remember { mutableStateOf<Job?>(null) }

    LaunchedEffect(store) {
        while (true) {
            delay(300)
            store.reloadFromDiskIfChanged()
        }
    }

    store.revision
    val baseTheme = remember(currentPreset, baseThemeProvider) { baseThemeProvider(currentPreset) }
    val overrides = store.overridesFor(currentPreset)
    val resolvedTheme = store.resolveTheme(currentPreset, baseTheme)

    CiacolaJewelTheme(isDark = isDark) {
        val globalColors = JewelTheme.globalColors
        val focusRingColor = rememberAccessibleFocusRingColor()
        val scrollState = rememberScrollState()

        Column(
            modifier = modifier
                .fillMaxSize()
                .background(globalColors.panelBackground)
                .focusable()
                .onPreviewKeyEvent { ev ->
                    if (ev.type != KeyEventType.KeyDown || ev.isAltPressed) return@onPreviewKeyEvent false
                    if (ev.key == Key.F7 && onRequestClose != null) {
                        onRequestClose()
                        return@onPreviewKeyEvent true
                    }
                    if (!textInputFocused) {
                        val primaryMod = ev.isMetaPressed || ev.isCtrlPressed
                        if (primaryMod && ev.key == Key.Z) {
                            if (ev.isShiftPressed) store.redo() else store.undo()
                            return@onPreviewKeyEvent true
                        }
                        if (ev.isCtrlPressed && !ev.isMetaPressed && !ev.isShiftPressed && ev.key == Key.Y) {
                            store.redo()
                            return@onPreviewKeyEvent true
                        }
                    }
                    false
                }
                .testTag("design-system-editor-panel"),
        ) {
            // Top Header: Preset Switcher, Undo/Redo, Copy Kotlin, Reset Preset
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        modifier = Modifier.selectableGroup(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        DesignTogglePill(
                            label = "Stage Dark",
                            tooltip = "Switch active deck preset to Stage Dark",
                            selected = currentPreset == DeckThemePreset.StageDark,
                            testTag = "design-preset-stage-dark",
                            onClick = { onSelectPreset(DeckThemePreset.StageDark) },
                        )
                        DesignTogglePill(
                            label = "Keynote Light",
                            tooltip = "Switch active deck preset to Keynote Light",
                            selected = currentPreset == DeckThemePreset.KeynoteLight,
                            testTag = "design-preset-keynote-light",
                            onClick = { onSelectPreset(DeckThemePreset.KeynoteLight) },
                        )
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        DesignTooltipIconButton(
                            icon = AllIconsKeys.Actions.Undo,
                            contentDescription = "Undo design token edit",
                            tooltip = "Undo theme token edit (⌘Z)",
                            testTag = "design-editor-undo",
                            enabled = store.canUndo,
                            onClick = { store.undo() },
                        )
                        DesignTooltipIconButton(
                            icon = AllIconsKeys.Actions.Redo,
                            contentDescription = "Redo design token edit",
                            tooltip = "Redo theme token edit (⇧⌘Z)",
                            testTag = "design-editor-redo",
                            enabled = store.canRedo,
                            onClick = { store.redo() },
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Tooltip(tooltip = { Text("Copy paste-ready DeckThemes / DesignSystemOverrides Kotlin snippet to clipboard") }) {
                        OutlinedButton(
                            onClick = {
                                val snippet = store.exportKotlinSnippet(currentPreset, baseTheme)
                                clipboardWriter(snippet)
                                copiedConfirmation = true
                                copiedJob?.cancel()
                                copiedJob = scope.launch {
                                    delay(2_000)
                                    copiedConfirmation = false
                                }
                            },
                            modifier = Modifier
                                .designActivatableFocus(focusRingColor)
                                .testTag("design-editor-copy-kotlin"),
                        ) {
                            Text(
                                text = if (copiedConfirmation) "Copied ✓" else "Copy Kotlin",
                                style = JewelTheme.typography.small,
                                maxLines = 1,
                            )
                        }
                    }

                    Tooltip(tooltip = { Text("Reset all overrides for ${currentPreset.name} back to defaults") }) {
                        OutlinedButton(
                            onClick = { store.resetAllForPreset(currentPreset) },
                            enabled = overrides.hasOverrides,
                            modifier = Modifier
                                .designActivatableFocus(focusRingColor)
                                .testTag("design-editor-reset-preset"),
                        ) {
                            Text("Reset Preset", style = JewelTheme.typography.small, maxLines = 1)
                        }
                    }
                }

                // 4 Sub-Tabs
                FlowRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectableGroup(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    DesignSystemTab.entries.forEach { tab ->
                        DesignTogglePill(
                            label = tab.label,
                            tooltip = "Show ${tab.label} editor",
                            selected = selectedTab == tab,
                            testTag = "design-tab-${tab.name.lowercase()}",
                            onClick = { selectedTab = tab },
                        )
                    }
                }
            }

            Divider(Orientation.Horizontal, Modifier.fillMaxWidth())

            Box(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(scrollState)
                        .padding(start = 12.dp, top = 10.dp, bottom = 14.dp, end = 18.dp),
                ) {
                    when (selectedTab) {
                        DesignSystemTab.Colors -> DesignSystemColorsTab(
                            store = store,
                            preset = currentPreset,
                            overrides = overrides,
                            resolvedTheme = resolvedTheme,
                            onTextInputFocusChanged = { textInputFocused = it },
                        )
                        DesignSystemTab.Typography -> DesignSystemTypographyTab(
                            store = store,
                            preset = currentPreset,
                            overrides = overrides,
                            baseTheme = baseTheme,
                            resolvedTheme = resolvedTheme,
                        )
                        DesignSystemTab.Styles -> DesignSystemStylesTab(
                            store = store,
                            preset = currentPreset,
                            overrides = overrides,
                            resolvedTheme = resolvedTheme,
                        )
                        DesignSystemTab.Motion -> DesignSystemMotionTab(
                            store = store,
                            preset = currentPreset,
                            overrides = overrides,
                            resolvedTheme = resolvedTheme,
                        )
                    }
                }
                VerticalScrollbar(
                    scrollState = scrollState,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .fillMaxHeight()
                        .padding(vertical = 4.dp, horizontal = 2.dp)
                        .testTag("design-editor-vscrollbar"),
                )
            }
        }
    }
}

/**
 * Standalone Storyboard [EaselWindow] (`F7` toggle, `minimumSize = 520x560`) hosting the live
 * Design System Editor and Theme/Motion menu controls.
 */
class DesignSystemWindow(
    val store: DesignSystemStore,
    initialPreset: DeckThemePreset = DeckThemePreset.StageDark,
    initialMotionMode: ReducedMotionMode = ReducedMotionMode.System,
    initialSystemReducedMotion: Boolean = false,
    initialAmbientLoopsPaused: Boolean = false,
    initialOnSelectPreset: (DeckThemePreset) -> Unit = {},
    initialOnSelectMotionMode: ((ReducedMotionMode) -> Unit)? = null,
    initialOnToggleAmbientLoopsPaused: ((Boolean) -> Unit)? = null,
    initialBaseThemeProvider: (DeckThemePreset) -> DeckTheme = { DeckThemes.forPreset(it) },
    var clipboardWriter: (String) -> Unit = DefaultClipboardWriter,
    var isEditorShownProvider: (() -> Boolean)? = null,
    var onToggleEditor: (() -> Unit)? = null,
) : EaselWindow {
    override val name: String = "Theme"
    override val initialSize: DpSize get() = DpSize(540.dp, 680.dp)
    override val minimumSize: DpSize get() = DpSize(520.dp, 560.dp)
    override var visible: Boolean by mutableStateOf(false)

    var currentPreset: DeckThemePreset by mutableStateOf(initialPreset)
    var currentMotionMode: ReducedMotionMode by mutableStateOf(initialMotionMode)
    var systemReducedMotion: Boolean by mutableStateOf(initialSystemReducedMotion)
    var ambientLoopsPaused: Boolean by mutableStateOf(initialAmbientLoopsPaused)
    var onSelectPreset: (DeckThemePreset) -> Unit by mutableStateOf(initialOnSelectPreset)
    var onSelectMotionMode: ((ReducedMotionMode) -> Unit)? by mutableStateOf(initialOnSelectMotionMode)
    var onToggleAmbientLoopsPaused: ((Boolean) -> Unit)? by mutableStateOf(initialOnToggleAmbientLoopsPaused)
    var baseThemeProvider: (DeckThemePreset) -> DeckTheme by mutableStateOf(initialBaseThemeProvider)

    @Composable
    override fun MenuScope.Menu() {
        RadioButtonItem(
            text = "Stage Dark",
            selected = currentPreset == DeckThemePreset.StageDark,
            onClick = { onSelectPreset(DeckThemePreset.StageDark) },
        )
        RadioButtonItem(
            text = "Keynote Light",
            selected = currentPreset == DeckThemePreset.KeynoteLight,
            onClick = { onSelectPreset(DeckThemePreset.KeynoteLight) },
        )
        val motionCallback = onSelectMotionMode
        val loopCallback = onToggleAmbientLoopsPaused
        if (motionCallback != null || loopCallback != null) {
            Separator()
            if (motionCallback != null) {
                Menu("Motion") {
                    val sysLabel = if (systemReducedMotion) "Reduced" else "Full"
                    RadioButtonItem(
                        text = "System ($sysLabel)",
                        selected = currentMotionMode == ReducedMotionMode.System,
                        onClick = { motionCallback(ReducedMotionMode.System) },
                    )
                    RadioButtonItem(
                        text = "Reduced Motion",
                        selected = currentMotionMode == ReducedMotionMode.Reduced,
                        onClick = { motionCallback(ReducedMotionMode.Reduced) },
                    )
                    RadioButtonItem(
                        text = "Full Motion",
                        selected = currentMotionMode == ReducedMotionMode.Full,
                        onClick = { motionCallback(ReducedMotionMode.Full) },
                    )
                }
            }
            if (loopCallback != null) {
                CheckboxItem(
                    text = "Pause Ambient Loops",
                    checked = ambientLoopsPaused,
                    onCheckedChange = loopCallback,
                )
            }
        }
        Separator()
        val customToggle = onToggleEditor
        if (customToggle != null) {
            // No Swing menu accelerator when F7 is handled by unified preview key routing,
            // preventing Swing + Compose double-toggle on a single F7 press.
            CheckboxItem(
                text = "Show Design System Editor (F7)",
                checked = isEditorShownProvider?.invoke() ?: visible,
                onCheckedChange = { customToggle() },
            )
        } else {
            CheckboxItem(
                text = "Show Design System Editor",
                checked = visible,
                shortcut = KeyShortcut(Key.F7),
                onCheckedChange = { visible = it },
            )
        }
    }

    @Composable
    override fun Content() {
        DesignSystemEditorPanel(
            store = store,
            currentPreset = currentPreset,
            onSelectPreset = onSelectPreset,
            baseThemeProvider = baseThemeProvider,
            clipboardWriter = clipboardWriter,
            onRequestClose = { visible = false },
        )
    }
}

/**
 * Remembers and keeps a [DesignSystemWindow] synchronized with the host's active preset and
 * reduced-motion/loop preferences.
 */
@Composable
fun rememberDesignSystemWindow(
    store: DesignSystemStore,
    currentPreset: DeckThemePreset,
    onSelectPreset: (DeckThemePreset) -> Unit,
    currentMotionMode: ReducedMotionMode = ReducedMotionMode.System,
    systemReducedMotion: Boolean = false,
    ambientLoopsPaused: Boolean = false,
    onSelectMotionMode: ((ReducedMotionMode) -> Unit)? = null,
    onToggleAmbientLoopsPaused: ((Boolean) -> Unit)? = null,
    baseThemeProvider: (DeckThemePreset) -> DeckTheme = { DeckThemes.forPreset(it) },
    isEditorShownProvider: (() -> Boolean)? = null,
    onToggleEditor: (() -> Unit)? = null,
): DesignSystemWindow = remember(store) {
    DesignSystemWindow(
        store = store,
        initialPreset = currentPreset,
        initialMotionMode = currentMotionMode,
        initialSystemReducedMotion = systemReducedMotion,
        initialAmbientLoopsPaused = ambientLoopsPaused,
        initialOnSelectPreset = onSelectPreset,
        initialOnSelectMotionMode = onSelectMotionMode,
        initialOnToggleAmbientLoopsPaused = onToggleAmbientLoopsPaused,
        initialBaseThemeProvider = baseThemeProvider,
        isEditorShownProvider = isEditorShownProvider,
        onToggleEditor = onToggleEditor,
    )
}.also {
    it.currentPreset = currentPreset
    it.onSelectPreset = onSelectPreset
    it.currentMotionMode = currentMotionMode
    it.systemReducedMotion = systemReducedMotion
    it.ambientLoopsPaused = ambientLoopsPaused
    it.onSelectMotionMode = onSelectMotionMode
    it.onToggleAmbientLoopsPaused = onToggleAmbientLoopsPaused
    it.baseThemeProvider = baseThemeProvider
    it.isEditorShownProvider = isEditorShownProvider
    it.onToggleEditor = onToggleEditor
}
