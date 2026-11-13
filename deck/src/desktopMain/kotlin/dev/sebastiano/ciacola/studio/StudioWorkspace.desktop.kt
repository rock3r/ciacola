package dev.sebastiano.ciacola.studio

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.bnorm.storyboard.Storyboard
import dev.bnorm.storyboard.easel.Animatic
import dev.sebastiano.ciacola.inspector.ElementPatch
import dev.sebastiano.ciacola.inspector.StageInspectionRegistry
import dev.sebastiano.ciacola.inspector.design.DesignSystemEditorPanel
import dev.sebastiano.ciacola.inspector.design.DesignSystemStore
import dev.sebastiano.ciacola.inspector.element.ElementEditorController
import dev.sebastiano.ciacola.inspector.element.ElementEditorPanel
import dev.sebastiano.ciacola.inspector.element.ElementOverridesStore
import dev.sebastiano.ciacola.inspector.lint.DeckLintController
import dev.sebastiano.ciacola.inspector.lint.DeckLintPanel
import dev.sebastiano.ciacola.theme.DeckTheme
import dev.sebastiano.ciacola.theme.DeckThemePreset
import dev.sebastiano.ciacola.timeline.TimelineBlockInspector
import dev.sebastiano.ciacola.timeline.TimelineOverridesStore
import dev.sebastiano.ciacola.timeline.TimelineStudioController
import dev.sebastiano.ciacola.ui.accessibleFocusRing
import dev.sebastiano.ciacola.ui.rememberAccessibleFocusRingColor
import java.awt.Cursor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.Tooltip
import org.jetbrains.jewel.ui.theme.colorPalette
import org.jetbrains.jewel.ui.typography

const val DEFAULT_STUDIO_INSPECTOR_WIDTH_DP = 388f
const val MIN_STUDIO_INSPECTOR_WIDTH_DP = 320f
const val MAX_STUDIO_INSPECTOR_WIDTH_DP = 560f

/**
 * Tabs hosted inside the unified right-hand Studio Inspector pane when Timeline Studio (`F5`/`F6`)
 * is active.
 */
enum class StudioInspectorTab(
    val label: String,
    val tooltip: String,
) {
    Motion(
        label = "Motion",
        tooltip = "Keyframe timing, Bezier curves, and spatial motion inspector",
    ),
    Element(
        label = "Element",
        tooltip = "Direct stage element text copy, typography, fill, border, and offset inspector",
    ),
    Theme(
        label = "Theme",
        tooltip = "Live Design System tokens and WCAG 2.1 contrast editor (F7)",
    ),
    Lint(
        label = "Lint",
        tooltip = "Live slide and full-deck diagnostic linter (F8)",
    ),
}

enum class UndoDomain {
    Motion,
    Element,
    Theme,
}

/**
 * Coordinates the unified Studio Inspector tabs (`Motion | Element | Theme | Lint`), `F7`/`F8`
 * routing between docked/detached Studio tabs and standalone sidecar windows (preventing window
 * sprawl), Lint quick-fix application, and context-aware `Cmd+Z` / `Cmd+Shift+Z` Undo/Redo.
 */
@Stable
class StudioWorkspaceController(
    val timelineController: TimelineStudioController,
    val timelineOverridesStore: TimelineOverridesStore,
    val designSystemStore: DesignSystemStore,
    val elementOverridesStore: ElementOverridesStore,
    val elementController: ElementEditorController,
    val lintController: DeckLintController,
    val stageInspectionRegistry: StageInspectionRegistry,
    var isTextInputActiveProvider: () -> Boolean = { false },
) {
    var activeTab by mutableStateOf(StudioInspectorTab.Motion)
    var inspectorWidthDp by mutableFloatStateOf(DEFAULT_STUDIO_INSPECTOR_WIDTH_DP)
    var lastEditedDomain by mutableStateOf(UndoDomain.Motion)
    var swallowNextEscapeKeyUp by mutableStateOf(false)

    var designSidecarVisibleProvider: () -> Boolean = { false }
    var setDesignSidecarVisible: (Boolean) -> Unit = {}
    var lintSidecarVisibleProvider: () -> Boolean = { false }
    var setLintSidecarVisible: (Boolean) -> Unit = {}

    val isStudioOpen: Boolean
        get() = timelineController.authoringActive

    val isDesignEditorVisible: Boolean
        get() = (isStudioOpen && activeTab == StudioInspectorTab.Theme) || designSidecarVisibleProvider()

    val isLinterVisible: Boolean
        get() = (isStudioOpen && activeTab == StudioInspectorTab.Lint) || lintSidecarVisibleProvider()

    val isAnyAuthoringToolOpen: Boolean
        get() = isStudioOpen || designSidecarVisibleProvider() || lintSidecarVisibleProvider()

    fun selectTabInStudio(tab: StudioInspectorTab) {
        if (!timelineController.authoringActive) {
            timelineController.dockedInStoryWindow = true
        }
        setDesignSidecarVisible(false)
        setLintSidecarVisible(false)
        activeTab = tab
    }

    fun toggleDesignSystemEditor() {
        if (timelineController.authoringActive) {
            setDesignSidecarVisible(false)
            activeTab = if (activeTab == StudioInspectorTab.Theme) {
                StudioInspectorTab.Motion
            } else {
                StudioInspectorTab.Theme
            }
        } else {
            val next = !designSidecarVisibleProvider()
            setDesignSidecarVisible(next)
        }
    }

    fun toggleDeckLinter() {
        if (timelineController.authoringActive) {
            setLintSidecarVisible(false)
            activeTab = if (activeTab == StudioInspectorTab.Lint) {
                StudioInspectorTab.Motion
            } else {
                StudioInspectorTab.Lint
            }
        } else {
            val next = !lintSidecarVisibleProvider()
            setLintSidecarVisible(next)
        }
    }

    fun openElementInInspector(
        elementId: String,
        animatic: Animatic,
        scope: CoroutineScope,
        sceneIndex: Int? = null,
        beatIndex: Int? = null,
    ) {
        if (sceneIndex != null && beatIndex != null) {
            val target = Storyboard.Index(sceneIndex = sceneIndex, frameIndex = beatIndex)
            if (animatic.targetIndex != target && target in animatic.storyboard.indices) {
                scope.launch { animatic.jumpTo(target) }
            }
        }
        selectTabInStudio(StudioInspectorTab.Element)
        elementController.selectElement(elementId)
    }

    fun applyElementPatch(patch: ElementPatch) {
        lastEditedDomain = UndoDomain.Element
        elementOverridesStore.updateElement(
            elementId = patch.elementId,
            coalesce = false,
            coalesceKey = "lint-fix:${patch.elementId}",
        ) { existing ->
            existing.copy(
                text = patch.text ?: existing.text,
                textColorArgb = patch.textColorArgb ?: existing.textColorArgb,
                fontSizeSp = patch.fontSizeSp ?: existing.fontSizeSp,
                fontWeight = patch.fontWeight ?: existing.fontWeight,
                fillColorArgb = patch.fillColorArgb ?: existing.fillColorArgb,
                borderColorArgb = patch.borderColorArgb ?: existing.borderColorArgb,
                borderWidthDp = patch.borderWidthDp ?: existing.borderWidthDp,
                cornerRadiusDp = patch.cornerRadiusDp ?: existing.cornerRadiusDp,
                paddingDp = patch.paddingDp ?: existing.paddingDp,
                offsetXDp = patch.offsetXDp ?: existing.offsetXDp,
                offsetYDp = patch.offsetYDp ?: existing.offsetYDp,
            )
        }
    }

    fun activeUndoDomain(): UndoDomain = when {
        designSidecarVisibleProvider() && !isStudioOpen -> UndoDomain.Theme
        lintSidecarVisibleProvider() && !isStudioOpen -> lastEditedDomain
        activeTab == StudioInspectorTab.Theme -> UndoDomain.Theme
        activeTab == StudioInspectorTab.Element -> UndoDomain.Element
        activeTab == StudioInspectorTab.Lint -> lastEditedDomain
        else -> UndoDomain.Motion
    }

    fun undoInActiveContext(): Boolean = when (activeUndoDomain()) {
        UndoDomain.Theme -> designSystemStore.undo()
        UndoDomain.Element -> elementOverridesStore.undo()
        UndoDomain.Motion -> {
            timelineController.undo()
            true
        }
    }

    fun redoInActiveContext(): Boolean = when (activeUndoDomain()) {
        UndoDomain.Theme -> designSystemStore.redo()
        UndoDomain.Element -> elementOverridesStore.redo()
        UndoDomain.Motion -> {
            timelineController.redo()
            true
        }
    }

    /**
     * Capture-phase shortcut router shared by the Story window and Studio panels:
     * - `F7` / `F8`: toggle/focus Design System Editor or Deck Linter without window sprawl.
     * - When an element is selected on the `Element` tab (and no text input has focus): `Arrow`
     *   keys nudge `1 dp` (`Shift+Arrow` `10 dp`), `Cmd+Backspace` resets the element, and `Esc`
     *   clears selection without opening the Overview grid.
     * - `Cmd+Z` / `Cmd+Shift+Z` (and `Ctrl+Z` / `Ctrl+Y`): routes Undo/Redo to the active editor
     *   tab (`Motion`, `Element`, `Theme`, or last quick-fixed domain on `Lint`).
     */
    fun handleStudioPreviewKey(event: KeyEvent): Boolean {
        if (event.key == Key.Escape && event.type == KeyEventType.KeyUp && swallowNextEscapeKeyUp) {
            swallowNextEscapeKeyUp = false
            return true
        }
        if (isTextInputActiveProvider()) return false

        if (event.type == KeyEventType.KeyDown && !event.isCtrlPressed && !event.isMetaPressed && !event.isAltPressed && !event.isShiftPressed) {
            when (event.key) {
                Key.F7 -> {
                    toggleDesignSystemEditor()
                    return true
                }
                Key.F8 -> {
                    toggleDeckLinter()
                    return true
                }
            }
        }

        if (elementController.inlineEditingElementId != null) {
            return false
        }

        val elementSelectionActive = elementController.editorActive &&
            activeTab == StudioInspectorTab.Element &&
            elementController.selectedElementId != null

        if (elementSelectionActive && event.type == KeyEventType.KeyDown && !event.isAltPressed) {
            val step = if (event.isShiftPressed) 10f else 1f
            when {
                !event.isMetaPressed && !event.isCtrlPressed && event.key == Key.DirectionLeft -> {
                    elementController.nudgeSelected(-step, 0f)
                    return true
                }
                !event.isMetaPressed && !event.isCtrlPressed && event.key == Key.DirectionRight -> {
                    elementController.nudgeSelected(step, 0f)
                    return true
                }
                !event.isMetaPressed && !event.isCtrlPressed && event.key == Key.DirectionUp -> {
                    elementController.nudgeSelected(0f, -step)
                    return true
                }
                !event.isMetaPressed && !event.isCtrlPressed && event.key == Key.DirectionDown -> {
                    elementController.nudgeSelected(0f, step)
                    return true
                }
                !event.isMetaPressed && !event.isCtrlPressed && event.key == Key.Escape -> {
                    swallowNextEscapeKeyUp = true
                    elementController.selectElement(null)
                    return true
                }
                (event.isMetaPressed || event.isCtrlPressed) && (event.key == Key.Backspace || event.key == Key.Delete) -> {
                    elementController.resetSelectedElement()
                    return true
                }
            }
        }

        if (isAnyAuthoringToolOpen && event.type == KeyEventType.KeyDown && !event.isAltPressed) {
            val primaryMod = event.isMetaPressed || event.isCtrlPressed
            if (primaryMod && event.key == Key.Z) {
                if (event.isShiftPressed) redoInActiveContext() else undoInActiveContext()
                return true
            }
            if (event.isCtrlPressed && !event.isMetaPressed && !event.isShiftPressed && event.key == Key.Y) {
                redoInActiveContext()
                return true
            }
        }

        return false
    }
}

/**
 * Synchronizes sidecar folding, unified off-EDT disk polling + exit flush for both inspector
 * stores, shortcut delegation, and the most recently edited store domain for Undo/Redo on Lint.
 */
@Composable
fun StudioWorkspaceSyncEffects(workspace: StudioWorkspaceController) {
    val studioOpen = workspace.isStudioOpen
    val anyAuthoringOpen = workspace.isAnyAuthoringToolOpen
    val activeTab = workspace.activeTab
    val elementPicking = studioOpen && activeTab == StudioInspectorTab.Element

    SideEffect {
        workspace.elementController.editorActive = anyAuthoringOpen
        workspace.elementController.elementPickingEnabled = elementPicking
        workspace.elementController.onStageKeyHandler = { ev ->
            workspace.handleStudioPreviewKey(ev)
        }
        workspace.elementController.onInlineEditOpened = {
            if (workspace.isStudioOpen) {
                workspace.activeTab = StudioInspectorTab.Element
            }
        }
        workspace.timelineController.onTrackSelected = { trackId ->
            if (trackId != null && workspace.isStudioOpen) {
                workspace.activeTab = StudioInspectorTab.Motion
            }
        }
    }

    DisposableEffect(workspace) {
        onDispose {
            if (workspace.elementController.onStageKeyHandler != null) {
                workspace.elementController.onStageKeyHandler = null
            }
            workspace.designSystemStore.flushPendingPersistence()
            workspace.elementOverridesStore.flushPendingPersistence()
        }
    }

    LaunchedEffect(workspace.designSystemStore, workspace.elementOverridesStore) {
        while (true) {
            kotlinx.coroutines.delay(300L)
            workspace.designSystemStore.pollExternalChangesFromDisk()
            workspace.elementOverridesStore.pollExternalChangesFromDisk()
        }
    }

    LaunchedEffect(studioOpen) {
        if (studioOpen) {
            when {
                workspace.designSidecarVisibleProvider() -> {
                    workspace.setDesignSidecarVisible(false)
                    workspace.activeTab = StudioInspectorTab.Theme
                }
                workspace.lintSidecarVisibleProvider() -> {
                    workspace.setLintSidecarVisible(false)
                    workspace.activeTab = StudioInspectorTab.Lint
                }
            }
        } else {
            workspace.elementController.cancelInlineEdit(restoreStageFocus = false)
        }
    }

    val motionStructural = workspace.timelineOverridesStore.structuralRevision
    val motionParam = workspace.timelineOverridesStore.parameterRevision
    var seenMotion by remember { mutableIntStateOf(motionStructural + motionParam) }
    LaunchedEffect(motionStructural, motionParam) {
        val next = motionStructural + motionParam
        if (next != seenMotion) {
            seenMotion = next
            workspace.lastEditedDomain = UndoDomain.Motion
        }
    }

    val elementRev = workspace.elementOverridesStore.revision
    var seenElement by remember { mutableIntStateOf(elementRev) }
    LaunchedEffect(elementRev) {
        if (elementRev != seenElement) {
            seenElement = elementRev
            workspace.lastEditedDomain = UndoDomain.Element
        }
    }

    val designRev = workspace.designSystemStore.revision
    var seenDesign by remember { mutableIntStateOf(designRev) }
    LaunchedEffect(designRev) {
        if (designRev != seenDesign) {
            seenDesign = designRev
            workspace.lastEditedDomain = UndoDomain.Theme
        }
    }
}

/**
 * Resizable right-hand Studio Inspector hosting segmented `Motion | Element | Theme | Lint` tabs.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun StudioUnifiedInspectorPane(
    workspace: StudioWorkspaceController,
    animatic: Animatic,
    currentPreset: DeckThemePreset,
    onSelectPreset: (DeckThemePreset) -> Unit,
    baseThemeProvider: (DeckThemePreset) -> DeckTheme,
    storyboardForPreset: (DeckThemePreset) -> Storyboard,
    scope: CoroutineScope,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val globalColors = JewelTheme.globalColors
    val focusRingColor = rememberAccessibleFocusRingColor()
    val splitterHover = remember { MutableInteractionSource() }
    val isSplitterHovered by splitterHover.collectIsHoveredAsState()
    val splitterColor = if (isSplitterHovered) globalColors.outlines.focused else globalColors.borders.normal

    Tooltip(tooltip = { Text("Drag to resize Studio Inspector (double-click to reset)") }) {
        Box(
            modifier = Modifier
                .width(5.dp)
                .fillMaxHeight()
                .background(splitterColor)
                .pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(Cursor.E_RESIZE_CURSOR)))
                .combinedClickable(
                    interactionSource = splitterHover,
                    indication = null,
                    onClick = {},
                    onDoubleClick = { workspace.inspectorWidthDp = DEFAULT_STUDIO_INSPECTOR_WIDTH_DP },
                )
                .pointerInput(density) {
                    detectHorizontalDragGestures { change, dragAmount ->
                        change.consume()
                        val deltaDp = with(density) { dragAmount.toDp().value }
                        workspace.inspectorWidthDp = (workspace.inspectorWidthDp - deltaDp)
                            .coerceIn(MIN_STUDIO_INSPECTOR_WIDTH_DP, MAX_STUDIO_INSPECTOR_WIDTH_DP)
                    }
                }
                .testTag("ciacola-studio-inspector-splitter"),
        )
    }

    val motionParamRev = workspace.timelineOverridesStore.parameterRevision
    val motionStructRev = workspace.timelineOverridesStore.structuralRevision
    val elemRev = workspace.elementOverridesStore.revision
    val desRev = workspace.designSystemStore.revision

    val hasMotionOverrides = workspace.timelineOverridesStore.data().hasOverrides
    val hasElementOverrides = workspace.elementOverridesStore.data().hasOverrides
    val hasThemeOverrides = workspace.designSystemStore.overridesFor(currentPreset).hasOverrides
    val resolvedFallbackBg = remember(currentPreset, desRev, baseThemeProvider) {
        workspace.designSystemStore.resolveTheme(currentPreset, baseThemeProvider(currentPreset)).colors.background
    }
    val lintErrorCount = workspace.lintController.errorCount
    val lintWarningCount = workspace.lintController.warningCount
    val lintTotalCount = lintErrorCount + lintWarningCount

    Column(
        modifier = modifier
            .width(workspace.inspectorWidthDp.dp)
            .fillMaxHeight()
            .background(globalColors.panelBackground)
            .testTag("ciacola-studio-unified-inspector"),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .selectableGroup()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (tab in StudioInspectorTab.entries) {
                val badgeText = when (tab) {
                    StudioInspectorTab.Motion -> if (hasMotionOverrides) "${tab.label} ●" else tab.label
                    StudioInspectorTab.Element -> if (hasElementOverrides) "${tab.label} ●" else tab.label
                    StudioInspectorTab.Theme -> if (hasThemeOverrides) "${tab.label} ●" else tab.label
                    StudioInspectorTab.Lint -> if (lintTotalCount > 0) "${tab.label} ($lintTotalCount)" else tab.label
                }
                val undoTargetLabel = when (tab) {
                    StudioInspectorTab.Motion -> "Motion"
                    StudioInspectorTab.Element -> "Element"
                    StudioInspectorTab.Theme -> "Theme"
                    StudioInspectorTab.Lint -> workspace.lastEditedDomain.name
                }
                StudioInspectorTabPill(
                    label = badgeText,
                    tooltip = "${tab.tooltip} • ⌘Z / ⇧⌘Z: Undo/Redo $undoTargetLabel",
                    selected = workspace.activeTab == tab,
                    hasAlert = tab == StudioInspectorTab.Lint && lintErrorCount > 0,
                    hasWarning = tab == StudioInspectorTab.Lint && lintErrorCount == 0 && lintWarningCount > 0,
                    focusRingColor = focusRingColor,
                    testTag = "ciacola-studio-tab-${tab.name.lowercase()}",
                    modifier = Modifier.weight(1f),
                    onClick = { workspace.activeTab = tab },
                )
            }
        }

        Divider(Orientation.Horizontal, Modifier.fillMaxWidth())

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when (workspace.activeTab) {
                StudioInspectorTab.Motion -> TimelineBlockInspector(
                    controller = workspace.timelineController,
                    modifier = Modifier.fillMaxSize(),
                    fillWidth = true,
                )

                StudioInspectorTab.Element -> ElementEditorPanel(
                    controller = workspace.elementController,
                    modifier = Modifier.fillMaxSize(),
                )

                StudioInspectorTab.Theme -> DesignSystemEditorPanel(
                    store = workspace.designSystemStore,
                    currentPreset = currentPreset,
                    onSelectPreset = onSelectPreset,
                    baseThemeProvider = baseThemeProvider,
                    modifier = Modifier.fillMaxSize(),
                )

                StudioInspectorTab.Lint -> DeckLintPanel(
                    controller = workspace.lintController,
                    animatic = animatic,
                    storyboardProvider = { animatic.storyboard },
                    presetsToCheck = DeckThemePreset.entries,
                    storyboardForPreset = storyboardForPreset,
                    timelineOverridesProvider = { workspace.timelineOverridesStore.data() },
                    fallbackBackground = resolvedFallbackBg,
                    externalRevisionKey = motionStructRev + motionParamRev + elemRev + desRev,
                    stageInspectionRegistry = workspace.stageInspectionRegistry,
                    onApplyElementPatch = { patch -> workspace.applyElementPatch(patch) },
                    onEditElement = { elementId, sceneIndex, beatIndex ->
                        workspace.openElementInInspector(
                            elementId = elementId,
                            animatic = animatic,
                            scope = scope,
                            sceneIndex = sceneIndex,
                            beatIndex = beatIndex,
                        )
                    },
                    onOpenThemeEditor = {
                        workspace.selectTabInStudio(StudioInspectorTab.Theme)
                    },
                    isActive = true,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StudioInspectorTabPill(
    label: String,
    tooltip: String,
    selected: Boolean,
    hasAlert: Boolean,
    hasWarning: Boolean,
    focusRingColor: Color,
    testTag: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val globalColors = JewelTheme.globalColors
    val palette = JewelTheme.colorPalette
    val isDark = JewelTheme.isDark
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val shape = RoundedCornerShape(5.dp)

    val alertColor = palette.redOrNull(if (isDark) 6 else 5) ?: globalColors.outlines.error
    val warnColor = palette.yellowOrNull(if (isDark) 6 else 5) ?: globalColors.outlines.warning

    val bg = when {
        selected -> globalColors.outlines.focused.copy(alpha = 0.20f)
        hasAlert -> alertColor.copy(alpha = 0.12f)
        hasWarning -> warnColor.copy(alpha = 0.12f)
        else -> Color.Transparent
    }
    val border = when {
        selected -> globalColors.outlines.focused
        hasAlert -> alertColor.copy(alpha = 0.65f)
        hasWarning -> warnColor.copy(alpha = 0.65f)
        else -> globalColors.borders.normal
    }

    Tooltip(tooltip = { Text(tooltip) }) {
        Box(
            modifier = modifier
                .clip(shape)
                .background(bg, shape)
                .border(1.dp, border, shape)
                .accessibleFocusRing(focused, focusRingColor, cornerRadius = 5.dp)
                .pointerHoverIcon(PointerIcon.Hand)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    role = Role.Tab,
                    onClick = onClick,
                )
                .semantics {
                    this.selected = selected
                }
                .padding(horizontal = 4.dp, vertical = 4.dp)
                .testTag(testTag),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                style = JewelTheme.typography.small.copy(
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                ),
                color = globalColors.text.normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
