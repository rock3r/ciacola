package dev.sebastiano.ciacola.studio

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import dev.bnorm.storyboard.Storyboard
import dev.bnorm.storyboard.easel.Animatic
import dev.sebastiano.ciacola.document.DeckDocumentStore
import dev.sebastiano.ciacola.document.render.DocumentKeyboardCaptureController
import dev.sebastiano.ciacola.inspector.design.DocumentThemeInspectorController
import dev.sebastiano.ciacola.inspector.element.DocumentElementInspectorController
import dev.sebastiano.ciacola.inspector.lint.DocumentDeckLintController
import dev.sebastiano.ciacola.inspector.sync.DocumentSyncController
import dev.sebastiano.ciacola.timeline.DocumentTimelineController
import dev.sebastiano.ciacola.timeline.DocumentTimelineWindow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

const val DEFAULT_STUDIO_INSPECTOR_WIDTH_DP = 388f
const val MIN_STUDIO_INSPECTOR_WIDTH_DP = 320f
const val MAX_STUDIO_INSPECTOR_WIDTH_DP = 520f

/** Below this window width the right inspector is clamped to [NARROW_WINDOW_INSPECTOR_WIDTH_DP]. */
const val NARROW_WINDOW_THRESHOLD_DP = 1200f
const val NARROW_WINDOW_INSPECTOR_WIDTH_DP = 320f

/** Below this window width the right inspector auto-collapses so the stage keeps breathing room. */
const val COLLAPSE_INSPECTOR_THRESHOLD_DP = 900f

/**
 * The two selection-scoped tabs of the right-hand Studio inspector.
 *
 * Deck-level surfaces live elsewhere: the theme opens as the App Bar `Theme` sheet (`F7`) and lint
 * findings open in the bottom `Issues` dock (`F8`).
 */
enum class StudioInspectorTab(
    val label: String,
    val tooltip: String,
) {
    Design(
        label = "Design",
        tooltip = "Layout, appearance, text, and parameters of the selected element",
    ),
    Animate(
        label = "Animate",
        tooltip = "Builds, timing, and stagger for the selected element, build, or transition",
    ),
    ;

    companion object {
        /** Legacy alias for [Design] (the pre-overhaul `Element` tab). */
        val Element: StudioInspectorTab get() = Design

        /** Legacy alias for [Animate] (the pre-overhaul `Motion` tab). */
        val Motion: StudioInspectorTab get() = Animate
    }
}

/**
 * The three top-level working modes selected from the App Bar segmented control.
 *
 * - [Edit]: authoring chrome (docked timeline + `Design | Animate` inspector).
 * - [Review]: click-to-comment inspect mode with review pins and the review panel.
 * - [Present]: Clean Show Mode. Every authoring surface closes and the App Bar hides.
 */
enum class StudioMode(val label: String, val tooltip: String) {
    Edit(label = "Edit", tooltip = "Edit slides with the timeline and inspector"),
    Review(label = "Review", tooltip = "Click the stage or press C / ⌘M to leave review comments"),
    Present(label = "Present", tooltip = "Clean show mode for live presenting (Esc returns to Edit)"),
}

/** Plain-language persistence state shown by the App Bar save indicator. */
enum class StudioSaveState(val label: String) {
    Saved("Saved"),
    Saving("Saving…"),
    ReadOnly("Read-only"),
    Conflict("Conflict"),
    Failed("Save failed"),
    Invalid("File invalid"),
}

/**
 * Coordinates the document-backed Studio shell: App Bar mode (`Edit | Review | Present`), the
 * `Design | Animate` inspector, the deck-level Theme sheet (`F7`), the bottom Issues dock (`F8`),
 * `F2`/`F5`/`F6` routing, `⌘⌥1..9` deck switching, and unified `⌘Z` / `⇧⌘Z`
 * (`DeckDocumentStore.undo()` / `redo()`) with text-input focus protection.
 */
@Stable
class StudioWorkspaceController(
    store: DeckDocumentStore,
    timelineController: DocumentTimelineController,
    elementController: DocumentElementInspectorController,
    themeController: DocumentThemeInspectorController,
    lintController: DocumentDeckLintController,
    syncController: DocumentSyncController? = null,
    keyboardController: DocumentKeyboardCaptureController? = null,
    var isTextInputActiveProvider: () -> Boolean = { false },
    initialInspectorWidthDp: Float = DEFAULT_STUDIO_INSPECTOR_WIDTH_DP,
) {
    var store: DeckDocumentStore by mutableStateOf(store)
    var timelineController: DocumentTimelineController by mutableStateOf(timelineController)
    var elementController: DocumentElementInspectorController by mutableStateOf(elementController)
    var themeController: DocumentThemeInspectorController by mutableStateOf(themeController)
    var lintController: DocumentDeckLintController by mutableStateOf(lintController)
    var syncController: DocumentSyncController? by mutableStateOf(syncController)
    var keyboardController: DocumentKeyboardCaptureController? by mutableStateOf(keyboardController)
    var timelineWindow: DocumentTimelineWindow? by mutableStateOf(null)

    var activeTab: StudioInspectorTab by mutableStateOf(StudioInspectorTab.Design)
    var mode: StudioMode by mutableStateOf(StudioMode.Edit)
        private set
    var themeSheetOpen: Boolean by mutableStateOf(false)
    var issuesDockOpen: Boolean by mutableStateOf(false)

    /** Studio shell width measured by the App Bar shell (`NaN` until first layout). */
    var shellWidthDp: Float by mutableFloatStateOf(Float.NaN)

    /** Explicit user override for showing/hiding the right inspector (`null` = auto at `< 900dp`). */
    var inspectorCollapsedOverride: Boolean? by mutableStateOf(null)

    /** `true` while the Esc Overview grid is open (hides the App Bar and all Studio chrome). */
    var overviewOpen: Boolean by mutableStateOf(false)

    /** `true` while focus sits on Studio chrome (App Bar, Theme sheet, Issues dock) outside the timeline. */
    var chromeHasFocus: Boolean by mutableStateOf(false)

    var inspectorWidthDp: Float by mutableFloatStateOf(
        initialInspectorWidthDp.coerceIn(MIN_STUDIO_INSPECTOR_WIDTH_DP, MAX_STUDIO_INSPECTOR_WIDTH_DP),
    )
    var swallowNextEscapeKeyUp: Boolean by mutableStateOf(false)

    /** Top-center stage toast `(seq, message)` emitted on `⌘Z`/`⇧⌘Z`/gesture cancel. */
    var lastShortcutToast: Pair<Long, String>? by mutableStateOf(null)
        private set
    private var shortcutToastSeq: Long = 0L

    var designSidecarVisibleProvider: () -> Boolean = { false }
    var setDesignSidecarVisible: (Boolean) -> Unit = {}
    var lintSidecarVisibleProvider: () -> Boolean = { false }
    var setLintSidecarVisible: (Boolean) -> Unit = {}

    var onInspectorWidthPersisted: ((Float) -> Unit)? = null
    var onTogglePresenter: (() -> Unit)? = null
    var onToggleDockedTimeline: (() -> Unit)? = null
    var onToggleDetachedTimeline: (() -> Unit)? = null
    var onSelectDeckByIndex: ((Int) -> Unit)? = null

    /** Turns review click-to-comment inspect mode on/off (wired to `ReviewStageCoordinator`). */
    var setReviewInspectMode: (Boolean) -> Unit = {}

    /** Toggles review click-to-comment inspect mode (`F3`) regardless of which Studio pane holds focus. */
    var onToggleReviewInspect: (() -> Unit)? = null

    /** Pins a review comment to the active target when `C` or `⌘M` is pressed in Review mode. */
    var onPinReviewComment: (() -> Boolean)? = null

    /** Captures the currently selected element ID before entering Review mode (cleared on Edit/Present). */
    var onCaptureReviewTargetNodeId: ((String?) -> Unit)? = null

    /** Opens the review panel when entering [StudioMode.Review]. */
    var onOpenReviewPanel: (() -> Unit)? = null

    /** Closes every authoring surface for [StudioMode.Present] (Clean Show Mode). */
    var onEnterCleanShowMode: (() -> Unit)? = null

    /** Restores keyboard focus to the stage after a mode switch. */
    var onRestoreStageFocus: (() -> Unit)? = null

    /** Synced from [LocalPresentingFullscreen]; hides window chrome and blocks authoring shortcuts. */
    var isFullscreenPresenting: Boolean by mutableStateOf(false)
        internal set

    /** Reports whether the Story stage currently owns keyboard focus (guards stage-local Enter-to-edit). */
    var isStageFocusedProvider: () -> Boolean = { false }

    /** Reports whether stage chrome or Review inspect/pin state blocks Enter-to-edit. */
    var inlineEditBlockedProvider: () -> Boolean = { false }

    /** Notifies listeners (e.g., review target tracking) when the active slide changes. */
    var onActiveSlideChanged: ((String?) -> Unit)? = null

    init {
        bindControllerHooks()
    }

    fun showShortcutToast(message: String) {
        shortcutToastSeq += 1L
        lastShortcutToast = shortcutToastSeq to message
    }

    fun clearShortcutToast(seq: Long) {
        if (lastShortcutToast?.first == seq) {
            lastShortcutToast = null
        }
    }

    fun updateBindings(
        newStore: DeckDocumentStore,
        newTimelineController: DocumentTimelineController,
        newElementController: DocumentElementInspectorController,
        newThemeController: DocumentThemeInspectorController,
        newLintController: DocumentDeckLintController,
        newSyncController: DocumentSyncController?,
        newKeyboardController: DocumentKeyboardCaptureController? = keyboardController,
    ) {
        store = newStore
        timelineController = newTimelineController
        elementController = newElementController
        themeController = newThemeController
        lintController = newLintController
        syncController = newSyncController
        keyboardController = newKeyboardController
        bindControllerHooks()
    }

    private fun bindControllerHooks() {
        val tl = timelineController
        val el = elementController
        val th = themeController
        tl.onUndoRequest = { undo() }
        tl.onRedoRequest = { redo() }
        tl.onBeforeBeginGesture = { commitOtherActiveGestures(except = tl) }
        el.onUndoRequest = { undo() }
        el.onRedoRequest = { redo() }
        el.onBeforeBeginGesture = { commitOtherActiveGestures(except = el) }
        th.onUndoRequest = { undo() }
        th.onRedoRequest = { redo() }
        th.onBeforeBeginGesture = { commitOtherActiveGestures(except = th) }
    }

    /**
     * Commits any dangling open gesture transaction on sibling controllers before starting a new
     * edit so slider/drag transactions never lock out cross-pane edits or fragment undo.
     */
    fun commitOtherActiveGestures(except: Any? = null) {
        if (except !== timelineController && timelineController.hasActiveGesture) {
            timelineController.commitActiveGesture()
        }
        if (except !== elementController && elementController.hasActiveTransaction) {
            elementController.commitGesture()
        }
        if (except !== themeController && themeController.hasActiveTransaction) {
            themeController.commitGesture()
        }
    }

    val isStudioOpen: Boolean
        get() = timelineWindow?.authoringActive == true

    /** Whether full timeline tracks are open (expanded docked tracks or detached F6 window). */
    val isTimelineTracksOpen: Boolean
        get() = (timelineWindow?.dockedInStoryWindow == true &&
            timelineWindow?.timelineExpanded == true &&
            !issuesDockOpen) ||
            timelineWindow?.detachedWindowVisible == true

    /** `true` when the App Bar owns the window chrome (not presenting, not in Overview). */
    val isAppBarActive: Boolean
        get() = mode != StudioMode.Present && !overviewOpen

    /** Stage clicks select nodes in Edit mode on either inspector tab (never while reviewing). */
    val isElementPickingEnabled: Boolean
        get() = isStudioOpen && mode == StudioMode.Edit

    val isDesignEditorVisible: Boolean
        get() = themeSheetOpen || designSidecarVisibleProvider()

    val isLinterVisible: Boolean
        get() = issuesDockOpen || lintSidecarVisibleProvider()

    val isAnyAuthoringToolOpen: Boolean
        get() = isStudioOpen || themeSheetOpen || issuesDockOpen ||
            designSidecarVisibleProvider() || lintSidecarVisibleProvider()

    /**
     * Expanded or floating authoring panels (excludes the collapsed docked Timeline strip so
     * Presenter View's pre-show warning doesn't fire when only the minimal Edit header is present).
     */
    val hasExpandedAuthoringTools: Boolean
        get() = isTimelineTracksOpen || isDesignEditorVisible || isLinterVisible

    /** Inspector width after the narrow-window clamp (`< 1200dp` → at most `320dp`). */
    val effectiveInspectorWidthDp: Float
        get() = effectiveInspectorWidthFor(inspectorWidthDp, shellWidthDp)

    /** Whether the right-hand inspector pane is currently shown (`auto-collapsed` below 900dp unless toggled open). */
    val isInspectorVisible: Boolean
        get() = !isInspectorCollapsedForWidth(shellWidthDp, inspectorCollapsedOverride)

    fun toggleInspector() {
        val nextVisible = !isInspectorVisible
        val autoVisible = shellWidthDp.isNaN() || shellWidthDp >= COLLAPSE_INSPECTOR_THRESHOLD_DP
        inspectorCollapsedOverride = if (nextVisible == autoVisible) null else !nextVisible
    }

    val saveState: StudioSaveState
        get() = studioSaveStateFor(store)

    fun selectTabInStudio(tab: StudioInspectorTab) {
        val tw = timelineWindow
        if (tw != null && !tw.authoringActive) {
            tw.dockedInStoryWindow = true
        }
        if (mode == StudioMode.Present) mode = StudioMode.Edit
        activeTab = tab
    }

    /**
     * Switches the App Bar mode. `Edit` docks the timeline, `Review` enables click-to-comment inspect
     * mode with the review panel, and `Present` enters Clean Show Mode.
     */
    fun switchMode(target: StudioMode) {
        when (target) {
            StudioMode.Edit -> {
                onCaptureReviewTargetNodeId?.invoke(null)
                setReviewInspectMode(false)
                timelineWindow?.let { tw ->
                    if (!tw.authoringActive) tw.dockedInStoryWindow = true
                }
            }

            StudioMode.Review -> {
                val capturedNodeId = elementController.selectedNodeId
                cancelAnyActiveGesture()
                elementController.cancelInlineTextEdit()
                elementController.selectNode(null)
                onCaptureReviewTargetNodeId?.invoke(capturedNodeId)
                themeSheetOpen = false
                // Reviewing is stage-first: fold the editing chrome so pins and comments get the room.
                timelineWindow?.dockedInStoryWindow = false
                setReviewInspectMode(true)
                onOpenReviewPanel?.invoke()
            }

            StudioMode.Present -> {
                onCaptureReviewTargetNodeId?.invoke(null)
                cancelAnyActiveGesture()
                elementController.cancelInlineTextEdit()
                elementController.selectNode(null)
                themeSheetOpen = false
                issuesDockOpen = false
                setReviewInspectMode(false)
                onEnterCleanShowMode?.invoke()
            }
        }
        mode = target
        onRestoreStageFocus?.invoke()
    }

    /** Records an externally entered Clean Show Mode (e.g. the Presenter readiness banner). */
    fun markPresentMode() {
        themeSheetOpen = false
        issuesDockOpen = false
        mode = StudioMode.Present
    }

    /** Leaves Present mode when an authoring tool reopens (e.g. `F5`). */
    fun syncModeWithOpenTools() {
        if (mode == StudioMode.Present && (isStudioOpen || themeSheetOpen || issuesDockOpen)) {
            mode = StudioMode.Edit
        }
    }

    fun toggleDesignSystemEditor() {
        if (mode != StudioMode.Present) {
            setDesignSidecarVisible(false)
            themeSheetOpen = !themeSheetOpen
        } else {
            setDesignSidecarVisible(!designSidecarVisibleProvider())
        }
    }

    fun toggleDeckLinter() {
        if (mode != StudioMode.Present) {
            setLintSidecarVisible(false)
            issuesDockOpen = !issuesDockOpen
        } else {
            setLintSidecarVisible(!lintSidecarVisibleProvider())
        }
    }

    fun toggleDockedTimeline() {
        val custom = onToggleDockedTimeline
        if (custom != null) {
            custom()
        } else {
            timelineWindow?.let { tw ->
                val next = !tw.dockedInStoryWindow
                tw.dockedInStoryWindow = next
                if (next) tw.detachedWindowVisible = false
            }
        }
        syncModeWithOpenTools()
    }

    /**
     * Toggles the Timeline tracks (`F5` / App Bar Timeline button):
     * - in `Edit` mode with a docked timeline, switches away from `Issues` (if open) or toggles
     *   [DocumentTimelineWindow.timelineExpanded] between the compact header band and full tracks;
     * - in `Edit` mode with an undocked/detached timeline, docks and expands it;
     * - in `Review` or `Present` mode, enters `Edit` mode with the docked timeline expanded.
     */
    fun toggleStudioTimeline() {
        val custom = onToggleDockedTimeline
        if (custom != null) {
            custom()
            syncModeWithOpenTools()
            return
        }
        val tw = timelineWindow
        if (mode != StudioMode.Edit) {
            switchMode(StudioMode.Edit)
            issuesDockOpen = false
            if (tw != null) {
                tw.detachedWindowVisible = false
                tw.dockedInStoryWindow = true
                tw.timelineExpanded = true
            }
            return
        }
        if (tw != null) {
            if (!tw.dockedInStoryWindow) {
                tw.detachedWindowVisible = false
                tw.dockedInStoryWindow = true
                issuesDockOpen = false
                tw.timelineExpanded = true
            } else if (issuesDockOpen) {
                issuesDockOpen = false
                tw.timelineExpanded = true
            } else {
                tw.toggleTimelineExpanded()
            }
        }
        syncModeWithOpenTools()
    }

    /**
     * Opens [nodeId] in the `Design` tab (jumping to its slide/beat). The Issues dock stays open so
     * the list and its context remain visible while the node is edited.
     */
    fun openNodeInElementInspector(
        nodeId: String?,
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
        if (mode == StudioMode.Review) {
            setReviewInspectMode(false)
            mode = StudioMode.Edit
        }
        selectTabInStudio(StudioInspectorTab.Design)
        elementController.selectNode(nodeId)
    }

    /** Cancels any active gesture transaction across Timeline, Element, Theme, or Store. */
    fun cancelAnyActiveGesture(): Boolean {
        var cancelled = false
        if (timelineController.hasActiveGesture) {
            cancelled = timelineController.cancelActiveGesture() || cancelled
        }
        if (elementController.hasActiveTransaction) {
            cancelled = elementController.cancelGesture() || cancelled
        }
        if (themeController.hasActiveTransaction) {
            cancelled = themeController.cancelGesture() || cancelled
        }
        if (store.hasOpenTransaction) {
            store.cancelActiveTransaction()
            cancelled = true
        }
        return cancelled
    }

    /** Unified undo on [DeckDocumentStore]. Mid-gesture undo cancels the active gesture first. */
    fun undo(): Boolean {
        if (cancelAnyActiveGesture()) {
            showShortcutToast("Canceled gesture")
            return true
        }
        if (!store.canUndo) return false
        store.undo()
        timelineController.refreshSnapshot()
        showShortcutToast("⌘Z Undo")
        return true
    }

    /** Unified redo on [DeckDocumentStore]. */
    fun redo(): Boolean {
        if (!store.canRedo) return false
        store.redo()
        timelineController.refreshSnapshot()
        showShortcutToast("⇧⌘Z Redo")
        return true
    }

    /**
     * Capture-phase shortcut router shared by the Story window and Studio panels:
     * - `F2` / `F5` / `F6` / `F7` / `F8`: Presenter View, docked/detached timeline, Theme sheet, Issues dock.
     * - `⌘⌥1..9`: switch active deck.
     * - `Esc` mid-gesture cancels the active gesture transaction and restores the exact prior document.
     * - Node nudging (`Arrow` / `Shift+Arrow`), delete (`⌘⌫`), and selection clear (`Esc`).
     * - Unified `⌘Z` / `⇧⌘Z` (`Ctrl+Z` / `Ctrl+Y`) routed to `store.undo()` / `store.redo()`,
     *   strictly yielding whenever a text input holds focus.
     */
    fun handleStudioPreviewKey(
        event: KeyEvent,
        allowStageAuthoringShortcuts: Boolean = true,
    ): Boolean {
        if (event.key == Key.Escape && event.type == KeyEventType.KeyUp) {
            if (swallowNextEscapeKeyUp) {
                swallowNextEscapeKeyUp = false
                return true
            }
            if (elementController.swallowNextEscapeKeyUp) {
                elementController.swallowNextEscapeKeyUp = false
                return true
            }
        }
        if ((event.key == Key.Enter || event.key == Key.NumPadEnter) &&
            event.type == KeyEventType.KeyUp &&
            elementController.swallowNextEnterKeyUp
        ) {
            elementController.swallowNextEnterKeyUp = false
            return true
        }
        if (isTextInputActiveProvider() ||
            elementController.textInputHasFocus ||
            elementController.inlineEditingNodeId != null
        ) {
            return false
        }

        // An entered "interactive": "keyboard" specimen keeps every key except Esc.
        val kb = keyboardController
        if (kb != null && kb.isCaptured) {
            if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                kb.exitInteractive()
                swallowNextEscapeKeyUp = true
                return true
            }
            return false
        }

        if (event.type == KeyEventType.KeyDown &&
            event.key == Key.Escape &&
            cancelAnyActiveGesture()
        ) {
            swallowNextEscapeKeyUp = true
            return true
        }

        if (event.type == KeyEventType.KeyDown && event.key == Key.Escape && themeSheetOpen) {
            themeSheetOpen = false
            swallowNextEscapeKeyUp = true
            return true
        }

        if (event.type == KeyEventType.KeyDown &&
            !event.isCtrlPressed &&
            !event.isMetaPressed &&
            !event.isAltPressed &&
            !event.isShiftPressed &&
            handleFunctionKey(event.key)
        ) {
            return true
        }

        if (handleDeckSwitchChord(event)) return true

        if (allowStageAuthoringShortcuts && isElementPickingEnabled) {
            if (event.type == KeyEventType.KeyDown &&
                event.key == Key.Escape &&
                elementController.selectedNodeIds.isNotEmpty()
            ) {
                elementController.selectNode(null)
                swallowNextEscapeKeyUp = true
                return true
            }
            if (!overviewOpen &&
                !themeSheetOpen &&
                !inlineEditBlockedProvider() &&
                !chromeHasFocus &&
                isStageFocusedProvider() &&
                event.type == KeyEventType.KeyDown &&
                !event.isCtrlPressed &&
                !event.isMetaPressed &&
                !event.isAltPressed &&
                !event.isShiftPressed &&
                (event.key == Key.Enter || event.key == Key.NumPadEnter)
            ) {
                if (elementController.swallowNextEnterKeyUp) return true
                if (elementController.startInlineEditForSelection()) {
                    elementController.swallowNextEnterKeyUp = true
                    return true
                }
            }
            if (activeTab == StudioInspectorTab.Design &&
                !elementController.layersTreeHasFocus &&
                elementController.handleKeyEvent(event)
            ) {
                return true
            }
        }

        if (allowStageAuthoringShortcuts &&
            mode == StudioMode.Review &&
            !isFullscreenPresenting &&
            !overviewOpen &&
            !themeSheetOpen &&
            !chromeHasFocus &&
            !isTextInputActiveProvider() &&
            event.type == KeyEventType.KeyDown
        ) {
            val unmodifiedC = event.key == Key.C &&
                !event.isCtrlPressed && !event.isMetaPressed && !event.isAltPressed && !event.isShiftPressed
            val primaryModM = event.key == Key.M &&
                (event.isMetaPressed || event.isCtrlPressed) && !event.isAltPressed && !event.isShiftPressed
            if ((unmodifiedC || primaryModM) && onPinReviewComment?.invoke() == true) {
                return true
            }
        }

        if (event.type == KeyEventType.KeyDown && !event.isAltPressed) {
            val primaryMod = event.isMetaPressed || event.isCtrlPressed
            if (primaryMod && event.key == Key.Z) {
                if (event.isShiftPressed) redo() else undo()
                return true
            }
            if (event.isCtrlPressed && !event.isMetaPressed && !event.isShiftPressed && event.key == Key.Y) {
                redo()
                return true
            }
        }

        return false
    }

    private fun handleFunctionKey(key: Key): Boolean {
        when (key) {
            Key.F2 -> {
                val toggle = onTogglePresenter ?: return false
                toggle()
                return true
            }

            Key.F3 -> {
                val toggle = onToggleReviewInspect ?: return false
                toggle()
                return true
            }

            Key.F5 -> {
                toggleStudioTimeline()
                return true
            }

            Key.F6 -> {
                val custom = onToggleDetachedTimeline
                if (custom != null) {
                    custom()
                } else {
                    timelineWindow?.let { tw ->
                        val next = !tw.detachedWindowVisible
                        if (next) tw.dockedInStoryWindow = false
                        tw.detachedWindowVisible = next
                    }
                }
                syncModeWithOpenTools()
                return true
            }

            Key.F7 -> {
                toggleDesignSystemEditor()
                return true
            }

            Key.F8 -> {
                toggleDeckLinter()
                return true
            }
        }
        return false
    }

    private fun handleDeckSwitchChord(event: KeyEvent): Boolean {
        val deckSwitcherMod = (event.isMetaPressed || event.isCtrlPressed) &&
            event.isAltPressed &&
            !event.isShiftPressed
        if (event.type != KeyEventType.KeyDown || !deckSwitcherMod) return false
        val deckIndex = when (event.key) {
            Key.One -> 0
            Key.Two -> 1
            Key.Three -> 2
            Key.Four -> 3
            Key.Five -> 4
            Key.Six -> 5
            Key.Seven -> 6
            Key.Eight -> 7
            Key.Nine -> 8
            else -> -1
        }
        val select = onSelectDeckByIndex
        if (deckIndex < 0 || select == null) return false
        select(deckIndex)
        return true
    }
}

/** Narrow-window clamp for the right inspector (pure for tests). */
fun effectiveInspectorWidthFor(requestedWidthDp: Float, shellWidthDp: Float): Float {
    val requested = requestedWidthDp.coerceIn(MIN_STUDIO_INSPECTOR_WIDTH_DP, MAX_STUDIO_INSPECTOR_WIDTH_DP)
    return if (!shellWidthDp.isNaN() && shellWidthDp < NARROW_WINDOW_THRESHOLD_DP) {
        minOf(requested, NARROW_WINDOW_INSPECTOR_WIDTH_DP)
    } else {
        requested
    }
}

/** Whether the right inspector auto-collapses for [shellWidthDp] (`< 900dp`) unless overridden. */
fun isInspectorCollapsedForWidth(shellWidthDp: Float, overrideCollapsed: Boolean?): Boolean {
    if (overrideCollapsed != null) return overrideCollapsed
    return !shellWidthDp.isNaN() && shellWidthDp < COLLAPSE_INSPECTOR_THRESHOLD_DP
}

/**
 * Synchronizes the active slide/beat into the element inspector, the specimen keyboard controller,
 * sidecar folding, Present-mode bookkeeping, and bidirectional stage ⇄ timeline selection.
 */
@Composable
fun StudioWorkspaceSyncEffects(
    workspace: StudioWorkspaceController,
    activeSlideId: String?,
    activeBeatIndex: Int,
    animatic: Animatic? = null,
) {
    val studioOpen = workspace.isStudioOpen

    LaunchedEffect(activeSlideId) {
        workspace.onActiveSlideChanged?.invoke(activeSlideId)
    }

    LaunchedEffect(activeSlideId, activeBeatIndex) {
        if (activeSlideId != null) {
            workspace.elementController.selectLocation(activeSlideId, activeBeatIndex)
        }
        workspace.keyboardController?.onActiveSlideChanged(activeSlideId)
    }

    LaunchedEffect(studioOpen) {
        workspace.keyboardController?.isEditMode = studioOpen
        if (studioOpen) {
            workspace.syncModeWithOpenTools()
            if (workspace.designSidecarVisibleProvider()) {
                workspace.setDesignSidecarVisible(false)
                workspace.themeSheetOpen = true
            }
            if (workspace.lintSidecarVisibleProvider()) {
                workspace.setLintSidecarVisible(false)
                workspace.issuesDockOpen = true
            }
        } else {
            workspace.elementController.cancelInlineTextEdit()
            workspace.elementController.cancelStageDragPreview()
        }
    }

    StudioSelectionSyncEffect(
        workspace = workspace,
        activeSlideId = activeSlideId,
        activeBeatIndex = activeBeatIndex,
        animatic = animatic,
    )
}
