package dev.sebastiano.ciacola.timeline

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.KeyShortcut
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.isAltPressed as isPointerAltPressed
import androidx.compose.ui.input.pointer.isCtrlPressed as isPointerCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed as isPointerMetaPressed
import androidx.compose.ui.input.pointer.isShiftPressed as isPointerShiftPressed
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.window.MenuScope
import dev.bnorm.storyboard.AdvanceDirection
import dev.bnorm.storyboard.ContentDecorator
import dev.bnorm.storyboard.easel.EaselWindow
import dev.bnorm.storyboard.easel.LocalSceneMode
import dev.bnorm.storyboard.easel.SceneMode
import dev.sebastiano.ciacola.theme.DeckThemePreset
import dev.sebastiano.ciacola.theme.LocalDeckTheme
import dev.sebastiano.ciacola.ui.CiacolaJewelTheme
import java.awt.KeyboardFocusManager
import java.awt.Window as AwtWindow
import java.awt.event.WindowEvent
import java.awt.event.WindowFocusListener
import java.beans.PropertyChangeListener
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.Tooltip
import org.jetbrains.jewel.ui.typography

/** Complete After Effects Timeline Studio panel (Transport + Synchronized Tracks + Inspector). */
@Composable
fun TimelineStudioPanel(
    controller: TimelineStudioController,
    isDark: Boolean,
    modifier: Modifier = Modifier,
    surface: TimelineSurface = TimelineSurface.Docked,
) {
    val focusState = LocalStudioA11yFocusState.current ?: remember { StudioA11yFocusState() }
    val panelFocusRequester = remember { FocusRequester() }
    var panelHadFocus by remember { mutableStateOf(false) }
    val activeSubLoopId = controller.loopClock.activeSubCompLoopId
    var wasInSubComp by remember { mutableStateOf(activeSubLoopId != null) }
    LaunchedEffect(activeSubLoopId) {
        val nowInSubComp = activeSubLoopId != null
        if (wasInSubComp && !nowInSubComp && panelHadFocus) {
            withFrameNanos {}
            runCatching { panelFocusRequester.requestFocus() }
        }
        wasInSubComp = nowInSubComp
    }
    CiacolaJewelTheme(isDark = isDark) {
        CompositionLocalProvider(LocalStudioA11yFocusState provides focusState) {
            Column(
                modifier = modifier
                    .fillMaxSize()
                    .background(JewelTheme.globalColors.panelBackground)
                    .focusRequester(panelFocusRequester)
                    .onFocusChanged {
                        if (it.hasFocus) panelHadFocus = true
                        focusState.timelinePanelHasFocus = it.hasFocus
                    }
                    .focusable()
                    .testTag("ciacola-timeline-studio"),
            ) {
                TimelineTransportBar(controller = controller, surface = surface)
            Divider(Orientation.Horizontal, Modifier.fillMaxWidth())
            controller.overridesStore.structuralRevision
            if (controller.isPlayheadOnInjectedVirtualBeat()) {
                val warnBg = if (isDark) Color(0xFF3A2D12) else Color(0xFFFFF4D6)
                val warnFg = if (isDark) Color(0xFFFFD87A) else Color(0xFF6B4C00)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(warnBg)
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                        .testTag("ciacola-virtual-hold-banner"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "◆ HOLD · Virtual Hold Beat — assign build tracks here, or Bake to Kotlin to add new text/code state",
                        style = JewelTheme.typography.small,
                        color = warnFg,
                    )
                }
                Divider(Orientation.Horizontal, Modifier.fillMaxWidth())
            }
            Row(Modifier.fillMaxSize()) {
                TimelineMultiTrackArea(
                    controller = controller,
                    surface = surface,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
                Divider(Orientation.Vertical, Modifier.fillMaxHeight())
                TimelineBlockInspector(controller = controller)
            }
        }
        }
    }
}

/**
 * After Effects keyboard map for the studio (active only while authoring):
 * - `Space` tap: play/pause (yields to focused buttons, pills, steppers, and layer rows);
 *   `Space` + drag: pan (hand tool) in the timeline or the preview.
 * - `[` / `]`: previous / next event beat.
 * - `Up` / `Down`: navigate layer rows when the layer list has focus; otherwise step slides.
 * - `=` / `-`: zoom the timeline in/out around the playhead; `;`: toggle fit / frame level.
 * - `.` / `,`: zoom the preview in/out; `/`: fit the preview.
 * - `Esc` inside Timeline Studio: exits an active Loop Sub-Composition first, then closes docked Studio.
 * - `F5`: dock/undock the timeline (always available).
 */
internal fun TimelineStudioController.handleStudioKey(
    event: KeyEvent,
    scope: CoroutineScope,
    suppressed: Boolean,
    focusState: StudioA11yFocusState = StudioA11yFocusState(),
): Boolean {
    if (suppressed) return false
    if (event.key == Key.Escape && event.type == KeyEventType.KeyUp && focusState.swallowNextEscapeKeyUp) {
        focusState.swallowNextEscapeKeyUp = false
        return true
    }
    if (event.type == KeyEventType.KeyDown && event.key == Key.F5) {
        dockedInStoryWindow = !dockedInStoryWindow
        if (!dockedInStoryWindow) {
            spaceHeld = false
            spaceUsedForPan = false
        }
        return true
    }
    if (!authoringActive) return false

    if (event.key == Key.Escape) {
        if (event.type == KeyEventType.KeyDown) {
            focusState.swallowNextEscapeKeyUp = false
        }
        if (event.type == KeyEventType.KeyDown && !event.isCtrlPressed && !event.isMetaPressed && !event.isAltPressed) {
            if (loopClock.activeSubCompLoopId != null && (focusState.timelinePanelHasFocus || detachedWindowVisible)) {
                exitLoopSubComp()
                focusState.swallowNextEscapeKeyUp = true
                return true
            }
            if (dockedInStoryWindow && focusState.timelinePanelHasFocus) {
                dockedInStoryWindow = false
                spaceHeld = false
                spaceUsedForPan = false
                focusState.swallowNextEscapeKeyUp = true
                return true
            }
        }
        return false
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
    if (event.isCtrlPressed || event.isMetaPressed || event.isAltPressed) return false

    if (event.key == Key.Spacebar) {
        if (event.type == KeyEventType.KeyUp && spaceHeld) {
            val wasTap = spaceHeld && !spaceUsedForPan
            spaceHeld = false
            spaceUsedForPan = false
            if (wasTap && !focusState.activatableControlHasFocus) {
                togglePlayback(scope)
            }
            return true
        }
        if (focusState.activatableControlHasFocus) {
            return false
        }
        if (event.type == KeyEventType.KeyDown && !spaceHeld) {
            spaceHeld = true
            spaceUsedForPan = false
        }
        return true
    }
    if (event.type != KeyEventType.KeyDown) return false
    if (focusState.layerListHasFocus && (event.key == Key.DirectionUp || event.key == Key.DirectionDown)) {
        return false
    }
    // Presenting keys work from anywhere in the studio: a click on the transport, a layer row or a
    // slider moves keyboard focus off the stage, which would otherwise swallow ←/→.
    val direction = when (event.key) {
        Key.DirectionRight, Key.DirectionDown, Key.PageDown -> AdvanceDirection.Forward
        Key.DirectionLeft, Key.DirectionUp, Key.PageUp -> AdvanceDirection.Backward
        else -> null
    }
    if (direction != null) {
        advance(scope, direction)
        return true
    }
    val playheadX = viewport.xForTime(currentPlayheadTimeMs.toFloat()).coerceIn(0f, viewport.viewportWidthPx)
    val previewCenter = Offset(preview.containerSize.width / 2f, preview.containerSize.height / 2f)
    when (event.key) {
        Key.LeftBracket -> stepBeat(-1)
        Key.RightBracket -> stepBeat(+1)
        Key.Equals, Key.Plus, Key.NumPadAdd -> viewport.zoomBy(1.25f, playheadX)
        Key.Minus, Key.NumPadSubtract -> viewport.zoomBy(0.8f, playheadX)
        Key.Semicolon -> viewport.toggleFitAndFrames(currentPlayheadTimeMs.toFloat())
        Key.Period -> if (stageSelection.isStoryStageMounted) preview.zoomBy(1.25f, previewCenter) else return false
        Key.Comma -> if (stageSelection.isStoryStageMounted) preview.zoomBy(0.8f, previewCenter) else return false
        Key.Slash -> if (stageSelection.isStoryStageMounted) preview.reset() else return false
        else -> return false
    }
    return true
}

internal fun createSpaceResetWindowFocusListener(
    controller: TimelineStudioController,
): WindowFocusListener = object : WindowFocusListener {
    override fun windowGainedFocus(e: WindowEvent?) = Unit
    override fun windowLostFocus(e: WindowEvent?) {
        controller.spaceHeld = false
        controller.spaceUsedForPan = false
    }
}

@Composable
private fun InstallSpaceResetOnWindowFocusLoss(controller: TimelineStudioController) {
    DisposableEffect(controller) {
        val listener = createSpaceResetWindowFocusListener(controller)
        val attachedWindows = LinkedHashSet<AwtWindow>()
        fun attachTo(window: AwtWindow?) {
            if (window != null && attachedWindows.add(window)) {
                window.addWindowFocusListener(listener)
            }
        }
        AwtWindow.getWindows().forEach(::attachTo)
        val kfm = KeyboardFocusManager.getCurrentKeyboardFocusManager()
        val propListener = PropertyChangeListener { evt ->
            attachTo(evt.newValue as? AwtWindow)
        }
        kfm.addPropertyChangeListener("focusedWindow", propListener)
        onDispose {
            kfm.removePropertyChangeListener("focusedWindow", propListener)
            attachedWindows.forEach { it.removeWindowFocusListener(listener) }
            attachedWindows.clear()
        }
    }
}

private val LocalInsideStagePreviewViewport = staticCompositionLocalOf { false }

/**
 * Story-window preview with After Effects composition-viewer navigation: mouse wheel zooms at
 * the cursor, Shift+wheel pans horizontally, Cmd/Ctrl+wheel pans vertically, and Space+drag or
 * middle-drag pans. Only active while authoring; presenting is never affected.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun StagePreviewViewport(
    controller: TimelineStudioController,
    enabled: Boolean,
    isDark: Boolean,
    content: @Composable () -> Unit,
) {
    val preview = controller.preview
    val storyMounted = controller.stageSelection.isStoryStageMounted
    val previewActive = enabled && storyMounted
    LaunchedEffect(enabled) {
        if (!enabled) preview.reset()
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clipToBounds()
            .onSizeChanged { preview.containerSize = it.toSize() }
            .pointerInput(previewActive) {
                if (!previewActive) return@pointerInput
                awaitPointerEventScope {
                    var panning = false
                    var last = Offset.Zero
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull() ?: continue
                        when (event.type) {
                            PointerEventType.Scroll -> {
                                val mods = event.keyboardModifiers
                                val d = change.scrollDelta
                                when {
                                    mods.isPointerShiftPressed -> preview.panBy(Offset(-(if (d.x != 0f) d.x else d.y) * 40f, 0f))
                                    mods.isPointerCtrlPressed || mods.isPointerMetaPressed -> preview.panBy(Offset(0f, -d.y * 40f))
                                    d.x != 0f && d.y == 0f -> preview.panBy(Offset(-d.x * 40f, 0f))
                                    else -> preview.zoomBy(exp(-d.y * 0.12f), change.position)
                                }
                                change.consume()
                            }
                            PointerEventType.Press -> {
                                if (event.buttons.isTertiaryPressed || controller.spaceHeld) {
                                    panning = true
                                    controller.spaceUsedForPan = true
                                    last = change.position
                                    change.consume()
                                }
                            }
                            PointerEventType.Move -> if (panning) {
                                preview.panBy(change.position - last)
                                last = change.position
                                change.consume()
                            }
                            PointerEventType.Release -> if (panning) {
                                panning = false
                                change.consume()
                            }
                        }
                    }
                }
            }
            .then(if (previewActive && controller.spaceHeld) Modifier.pointerHoverIcon(PointerIcon.Hand, overrideDescendants = true) else Modifier)
            .testTag("ciacola-preview-viewport")
            .semantics {
                contentDescription = "Slide preview viewport"
                stateDescription = if (preview.isIdentity) {
                    "Fit to window"
                } else {
                    "Zoomed to ${(preview.scale * 100).roundToInt()} percent"
                }
            },
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    transformOrigin = TransformOrigin(0f, 0f)
                    scaleX = if (previewActive) preview.scale else 1f
                    scaleY = if (previewActive) preview.scale else 1f
                    translationX = if (previewActive) preview.offset.x else 0f
                    translationY = if (previewActive) preview.offset.y else 0f
                },
        ) {
            CompositionLocalProvider(LocalInsideStagePreviewViewport provides true) {
                content()
            }
        }
        if (previewActive && !preview.isIdentity) {
            CiacolaJewelTheme(isDark = isDark) {
                val focusRingColor = rememberAccessibleFocusRingColor()
                val pct = (preview.scale * 100).roundToInt()
                Tooltip(tooltip = { Text("Reset preview zoom to fit (/)") }) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(10.dp)
                            .background(JewelTheme.globalColors.panelBackground.copy(alpha = 0.92f), RoundedCornerShape(6.dp))
                            .studioActivatableFocus(focusRingColor, cornerRadius = 6.dp)
                            .clickable { preview.reset() }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                            .testTag("ciacola-preview-zoom-badge")
                            .semantics {
                                role = Role.Button
                                contentDescription = "Reset preview zoom from $pct percent to fit"
                            },
                    ) {
                        Text(
                            text = "$pct% · click or / to fit",
                            style = JewelTheme.typography.small,
                            color = JewelTheme.globalColors.text.info,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Storyboard [EaselWindow] integrating the After Effects Timeline Studio both as a dockable
 * bottom studio pane inside the Story window (`F5`) and as a detachable standalone window (`F6`).
 */
class TimelineStudioWindow(
    val controller: TimelineStudioController,
    val currentPresetProvider: () -> DeckThemePreset = { DeckThemePreset.StageDark },
    val suppressShortcutsProvider: () -> Boolean = { false },
    val onReturnFocusToStage: (() -> Unit)? = null,
) : EaselWindow {
    override val name: String = "Timeline"
    override val minimumSize: DpSize get() = DpSize(720.dp, 420.dp)
    override var visible: Boolean
        get() = controller.detachedWindowVisible
        set(value) {
            controller.detachedWindowVisible = value
        }

    /**
     * Stage-space decorator that provides [LocalDeckLoopClock] and [LocalMotionPathPreviewState],
     * and draws curved shared-element motion-path previews on the `1920x1080` slide canvas.
     */
    val stageDecorator: ContentDecorator = createTimelineStageDecorator(
        loopClock = controller.loopClock,
        motionPathPreview = controller.motionPathPreview,
        overridesStore = controller.overridesStore,
        stageSelection = controller.stageSelection,
    )

    override val decorator: ContentDecorator = ContentDecorator { content ->
        val scope = rememberCoroutineScope()
        val isDark = currentPresetProvider() == DeckThemePreset.StageDark
        var totalHeightPx by remember { mutableStateOf(720f) }
        val rootFocusRequester = remember { FocusRequester() }
        val focusManager = LocalFocusManager.current
        val studioFocusState = remember { StudioA11yFocusState() }
        var rootHadFocus by remember { mutableStateOf(false) }
        var rootHasFocus by remember { mutableStateOf(false) }
        InstallSpaceResetOnWindowFocusLoss(controller)
        LaunchedEffect(rootHasFocus, controller.overridesStore.canUndo, controller.overridesStore.canRedo) {
            if (rootHadFocus && !rootHasFocus && !suppressShortcutsProvider()) {
                runCatching { rootFocusRequester.requestFocus() }
            }
        }
        var wasDocked by remember { mutableStateOf(controller.dockedInStoryWindow) }
        LaunchedEffect(controller.dockedInStoryWindow) {
            val nowDocked = controller.dockedInStoryWindow
            if (wasDocked && !nowDocked) {
                controller.spaceHeld = false
                controller.spaceUsedForPan = false
                withFrameNanos {}
                if (onReturnFocusToStage != null) {
                    onReturnFocusToStage.invoke()
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
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .onSizeChanged { totalHeightPx = it.height.toFloat().coerceAtLeast(240f) }
                    .onFocusChanged { state ->
                        if (state.hasFocus) rootHadFocus = true
                        rootHasFocus = state.hasFocus
                    }
                    .focusRequester(rootFocusRequester)
                    .focusable()
                    .onPreviewKeyEvent { ev ->
                        controller.handleStudioKey(
                            event = ev,
                            scope = scope,
                            suppressed = suppressShortcutsProvider(),
                            focusState = studioFocusState,
                        )
                    },
            ) {
                if (controller.dockedInStoryWindow) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(controller.splitFraction.coerceIn(0.28f, 0.78f)),
                    ) {
                        StagePreviewViewport(controller, enabled = controller.authoringActive, isDark = isDark) {
                            content()
                        }
                    }
                    CiacolaJewelTheme(isDark = isDark) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(5.dp)
                                .background(JewelTheme.globalColors.borders.normal)
                                .pointerHoverIcon(PointerIcon.Crosshair)
                                .pointerInput(Unit) {
                                    detectVerticalDragGestures { change, dragAmount ->
                                        change.consume()
                                        val deltaFrac = dragAmount / totalHeightPx
                                        controller.splitFraction =
                                            (controller.splitFraction + deltaFrac).coerceIn(0.28f, 0.78f)
                                    }
                                },
                        )
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight((1f - controller.splitFraction).coerceIn(0.22f, 0.72f)),
                    ) {
                        TimelineStudioPanel(
                            controller = controller,
                            isDark = isDark,
                            surface = TimelineSurface.Docked,
                        )
                    }
                } else {
                    Box(Modifier.fillMaxSize()) {
                        StagePreviewViewport(controller, enabled = controller.authoringActive, isDark = isDark) {
                            content()
                        }
                    }
                }
            }
        }
    }

    @Composable
    override fun MenuScope.Menu() {
        val scope = rememberCoroutineScope()
        // No accelerator: F5 is handled by handleStudioKey on onPreviewKeyEvent, and a Swing menu
        // accelerator would fire in addition to it and double-toggle dockedInStoryWindow.
        CheckboxItem(
            text = "Dock Timeline Studio in Story Window (F5)",
            checked = controller.dockedInStoryWindow,
        ) {
            controller.dockedInStoryWindow = it
        }
        CheckboxItem(
            text = "Show Detached Timeline Window",
            checked = visible,
            shortcut = KeyShortcut(Key.F6),
        ) {
            visible = it
        }
        Separator()
        Item(
            text = if (controller.isPlaying) "Pause Timeline" else "Play Timeline",
            onClick = { controller.togglePlayback(scope) },
        )
        Item(
            text = "Insert Event Keyframe (+ Beat)",
            onClick = { controller.insertEventBeatAtPlayhead() },
        )
        Item(text = "Zoom Timeline to Fit", onClick = { controller.viewport.zoomToFit() })
        Item(text = "Fit Preview", onClick = { controller.preview.reset() })
    }

    @Composable
    override fun Content() {
        val scope = rememberCoroutineScope()
        val isDark = currentPresetProvider() == DeckThemePreset.StageDark
        val rootFocusRequester = remember { FocusRequester() }
        val studioFocusState = remember { StudioA11yFocusState() }
        var rootHasFocus by remember { mutableStateOf(false) }
        InstallSpaceResetOnWindowFocusLoss(controller)
        LaunchedEffect(rootHasFocus, controller.overridesStore.canUndo, controller.overridesStore.canRedo) {
            if (!rootHasFocus && !suppressShortcutsProvider()) {
                runCatching { rootFocusRequester.requestFocus() }
            }
        }
        CompositionLocalProvider(LocalStudioA11yFocusState provides studioFocusState) {
            Box(
                Modifier
                    .fillMaxSize()
                    .onFocusChanged { rootHasFocus = it.hasFocus }
                    .focusRequester(rootFocusRequester)
                    .focusable()
                    .onPreviewKeyEvent { ev ->
                        controller.handleStudioKey(
                            event = ev,
                            scope = scope,
                            suppressed = suppressShortcutsProvider(),
                            focusState = studioFocusState,
                        )
                    },
            ) {
                TimelineStudioPanel(
                    controller = controller,
                    isDark = isDark,
                    surface = TimelineSurface.Detached,
                )
            }
        }
    }
}

@Composable
fun rememberTimelineStudioWindow(
    controller: TimelineStudioController,
    currentPresetProvider: () -> DeckThemePreset = { DeckThemePreset.StageDark },
    suppressShortcutsProvider: () -> Boolean = { false },
    onReturnFocusToStage: (() -> Unit)? = null,
): TimelineStudioWindow = remember(controller) {
    TimelineStudioWindow(controller, currentPresetProvider, suppressShortcutsProvider, onReturnFocusToStage)
}

/**
 * Creates the `1920x1080` slide-stage [ContentDecorator]: provides the loop clock, motion-path
 * preview, and timeline/stage selection bridge, draws the selection highlight and motion arcs, and
 * lets a plain click on the stage select the track(s) animating the element under the pointer.
 */
fun createTimelineStageDecorator(
    loopClock: DeckLoopClock,
    motionPathPreview: MotionPathPreviewState,
    overridesStore: TimelineOverridesStore,
    stageSelection: TimelineStageSelection = TimelineStageSelection(),
): ContentDecorator = ContentDecorator { content ->
    if (LocalTimelineOffscreen.current) {
        // Offscreen indexing/measuring scenes: keep their own frozen clock, no live selection.
        content()
        return@ContentDecorator
    }
    val sceneMode = LocalSceneMode.current
    val isStory = sceneMode == SceneMode.Story
    val insidePreviewViewport = LocalInsideStagePreviewViewport.current
    if (insidePreviewViewport) {
        DisposableEffect(stageSelection, sceneMode) {
            when (sceneMode) {
                SceneMode.Story -> stageSelection.storyStageMountCount++
                SceneMode.Preview -> stageSelection.overviewPreviewMountCount++
                null -> Unit
            }
            onDispose {
                when (sceneMode) {
                    SceneMode.Story -> stageSelection.storyStageMountCount = (stageSelection.storyStageMountCount - 1).coerceAtLeast(0)
                    SceneMode.Preview -> stageSelection.overviewPreviewMountCount = (stageSelection.overviewPreviewMountCount - 1).coerceAtLeast(0)
                    null -> Unit
                }
            }
        }
    }
    if (!isStory) {
        // Preview / offscreen export scenes (Overview thumbnails, Presenter View previews, PDF/MP4
        // exporters) use an isolated loop clock inheriting period overrides so they animate
        // deterministically without mutating live Story loop phase or selection state.
        val isolatedClock = remember { DeckLoopClock() }.also { isolated ->
            val staleKeys = isolated.periodOverridesMillis.keys.filter { it !in loopClock.periodOverridesMillis }
            staleKeys.forEach { isolated.periodOverridesMillis.remove(it) }
            for ((k, v) in loopClock.periodOverridesMillis) {
                if (isolated.periodOverridesMillis[k] != v) isolated.periodOverridesMillis[k] = v
            }
        }
        CompositionLocalProvider(
            LocalDeckLoopClock provides isolatedClock,
            LocalMotionPathPreviewState provides null,
            LocalTimelineStageSelection provides null,
        ) {
            content()
        }
        return@ContentDecorator
    }
    CompositionLocalProvider(
        LocalDeckLoopClock provides loopClock,
        LocalMotionPathPreviewState provides motionPathPreview,
        LocalTimelineStageSelection provides stageSelection,
    ) {
        val isPresentingStoryStage = stageSelection.overviewPreviewMountCount == 0
        Box(Modifier.fillMaxSize().then(if (isPresentingStoryStage) Modifier.stagePicking(stageSelection) else Modifier)) {
            content()
            if (isPresentingStoryStage) {
                TimelineSelectionStageOverlay(stageSelection)
                if (loopClock.activeSubCompLoopId == null && stageSelection.pickingEnabled) {
                    MotionArcStageOverlay(
                        motionPathPreview = motionPathPreview,
                        overridesStore = overridesStore,
                    )
                }
            }
        }
    }
}

@Composable
fun rememberTimelineStageDecorator(
    loopClock: DeckLoopClock,
    motionPathPreview: MotionPathPreviewState,
    overridesStore: TimelineOverridesStore,
    stageSelection: TimelineStageSelection = remember { TimelineStageSelection() },
): ContentDecorator = remember(loopClock, motionPathPreview, overridesStore, stageSelection) {
    createTimelineStageDecorator(loopClock, motionPathPreview, overridesStore, stageSelection)
}

/** Whether two coordinates live in the same layout hierarchy (same window / composition root). */
private fun LayoutCoordinates.sharesRootWith(other: LayoutCoordinates): Boolean =
    findRootCoordinates() === other.findRootCoordinates()

/** Plain primary click (no modifiers, no drag) on the stage selects the topmost tagged element. */
private fun Modifier.stagePicking(selection: TimelineStageSelection): Modifier = composed {
    var ownCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    onGloballyPositioned { ownCoordinates = it }
        .pointerInput(selection) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                if (down.isConsumed || !selection.pickingEnabled || selection.pickingSuppressed()) return@awaitEachGesture
                val event = currentEvent
                val mods = event.keyboardModifiers
                if (!event.buttons.isPrimaryPressed || mods.isPointerCtrlPressed || mods.isPointerMetaPressed || mods.isPointerAltPressed || mods.isPointerShiftPressed) {
                    return@awaitEachGesture
                }
                val up = waitForUpOrCancellation(PointerEventPass.Initial) ?: return@awaitEachGesture
                if ((up.position - down.position).getDistance() > 6f) return@awaitEachGesture
                val own = ownCoordinates ?: return@awaitEachGesture
                if (!own.isAttached) return@awaitEachGesture
                val hit = selection.targets.values
                    .filter { it.coordinates.isAttached && it.coordinates.sharesRootWith(own) }
                    .map { it to own.localBoundingBoxOf(it.coordinates, clipBounds = false) }
                    .filter { (_, bounds) -> bounds.contains(up.position) }
                    .minByOrNull { (_, bounds) -> bounds.width * bounds.height }
                selection.onPick(hit?.let { selection.mostSpecificTrackId(it.first) })
            }
        }
}

/** Highlights every stage element animated by the selected track (After Effects layer bounds). */
@Composable
private fun TimelineSelectionStageOverlay(selection: TimelineStageSelection) {
    // Authoring-only: while presenting (studio closed) nothing is highlighted and no frame loop runs;
    // the selection itself is kept for when the studio reopens.
    val selected = if (selection.pickingEnabled) selection.selectedTrackId else null
    var overlayCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var frameTick by remember { mutableLongStateOf(0L) }
    val textMeasurer = rememberTextMeasurer()
    if (selected != null) {
        // Targets move while animating: redraw every frame while something is selected.
        LaunchedEffect(selected) {
            while (true) withFrameNanos { frameTick = it }
        }
    }
    val entries = if (selected != null) {
        selection.entriesFor(selected).filter { entry ->
            val own = overlayCoordinates
            own != null && own.isAttached && entry.coordinates.isAttached && entry.coordinates.sharesRootWith(own)
        }
    } else {
        emptyList()
    }
    val accent = Color(0xFF3D9EFF)
    Canvas(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned { overlayCoordinates = it }
            .testTag("ciacola-stage-selection-overlay")
            .semantics { contentDescription = "selected=${selected ?: "none"} highlighted=${entries.size}" },
    ) {
        frameTick
        val own = overlayCoordinates ?: return@Canvas
        if (selected == null || !own.isAttached) return@Canvas
        val pad = 6.dp.toPx()
        val stroke = 3.dp.toPx()
        val dash = PathEffect.dashPathEffect(floatArrayOf(10.dp.toPx(), 6.dp.toPx()), 0f)
        var labelAnchor: Rect? = null
        for (entry in entries) {
            if (!entry.coordinates.isAttached) continue
            val r = own.localBoundingBoxOf(entry.coordinates, clipBounds = false)
            if (r.width <= 0f || r.height <= 0f) continue
            val inflated = Rect(r.left - pad, r.top - pad, r.right + pad, r.bottom + pad)
            drawRoundRect(accent.copy(alpha = 0.10f), inflated.topLeft, inflated.size, CornerRadius(8.dp.toPx()))
            drawRoundRect(
                color = accent,
                topLeft = inflated.topLeft,
                size = inflated.size,
                cornerRadius = CornerRadius(8.dp.toPx()),
                style = Stroke(width = stroke, pathEffect = dash),
            )
            if (labelAnchor == null || inflated.top < labelAnchor.top) labelAnchor = inflated
        }
        val anchor = labelAnchor ?: return@Canvas
        val label = selection.selectedLabel ?: selected
        val layout = textMeasurer.measure(
            text = label,
            style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color.White),
            maxLines = 1,
        )
        val chipPadH = 8.dp.toPx()
        val chipPadV = 4.dp.toPx()
        val chipW = layout.size.width + chipPadH * 2
        val chipH = layout.size.height + chipPadV * 2
        val chipTop = (anchor.top - chipH - 4.dp.toPx()).coerceAtLeast(0f)
        val chipLeft = anchor.left.coerceIn(0f, (size.width - chipW).coerceAtLeast(0f))
        drawRoundRect(accent, Offset(chipLeft, chipTop), Size(chipW, chipH), CornerRadius(6.dp.toPx()))
        drawText(layout, topLeft = Offset(chipLeft + chipPadH, chipTop + chipPadV))
    }
}

@Composable
private fun MotionArcStageOverlay(
    motionPathPreview: MotionPathPreviewState,
    overridesStore: TimelineOverridesStore,
) {
    val selId = motionPathPreview.selectedTrackId ?: return
    if (!selId.startsWith("shared:")) return
    val arc = motionPathPreview.latestArc ?: return
    val activeScene = motionPathPreview.activeStageSceneIndex
    if (activeScene != null && arc.sceneIndex != null && activeScene != arc.sceneIndex) return
    val overrideBend = overridesStore.data().tracks[selId]?.spatial?.arcBend ?: arc.arcBend
    val stageBg = LocalDeckTheme.current.colors.background
    // The theme's motion-path token is contrast-checked against each preset's stage background.
    val arcStrokeColor = LocalDeckTheme.current.colors.motionPathPreview
    val isLightStage = wcagRelativeLuminance(stageBg) > 0.35f

    Canvas(Modifier.fillMaxSize()) {
        val s = arc.startBounds.center
        val e = arc.endBounds.center
        val dx = e.x - s.x
        val dy = e.y - s.y
        val dist = hypot(dx, dy).coerceAtLeast(64f)
        val nx = -dy / dist
        val ny = dx / dist
        val peak = -overrideBend * dist * 0.42f
        val ctrl = Offset((s.x + e.x) * 0.5f + nx * peak, (s.y + e.y) * 0.5f + ny * peak)

        val trajectory = Path().apply {
            moveTo(s.x, s.y)
            quadraticTo(ctrl.x, ctrl.y, e.x, e.y)
        }
        drawPath(
            path = trajectory,
            color = arcStrokeColor,
            style = Stroke(width = 3f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f), 0f)),
        )
        drawCircle(color = if (isLightStage) Color.White else Color(0xFF0B0D12), radius = 8.5f, center = s)
        drawCircle(color = arcStrokeColor, radius = 7f, center = s)
        drawCircle(color = if (isLightStage) Color.White else Color(0xFF0B0D12), radius = 8.5f, center = e)
        drawCircle(color = arcStrokeColor, radius = 7f, center = e)
    }
}
