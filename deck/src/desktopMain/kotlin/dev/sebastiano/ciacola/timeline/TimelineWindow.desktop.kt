package dev.sebastiano.ciacola.timeline

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.KeyShortcut
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.MenuScope
import dev.bnorm.storyboard.ContentDecorator
import dev.bnorm.storyboard.easel.EaselWindow
import dev.bnorm.storyboard.easel.LocalSceneMode
import dev.bnorm.storyboard.easel.SceneMode
import dev.sebastiano.ciacola.theme.DeckThemePreset
import dev.sebastiano.ciacola.ui.CiacolaJewelTheme
import kotlin.math.hypot
import kotlinx.coroutines.launch
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Divider

/**
 * Complete After Effects Timeline Studio panel (Transport + Synchronized Tracks + Inspector).
 */
@Composable
fun TimelineStudioPanel(
    controller: TimelineStudioController,
    isDark: Boolean,
    modifier: Modifier = Modifier,
) {
    CiacolaJewelTheme(isDark = isDark) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .background(JewelTheme.globalColors.panelBackground)
                .testTag("ciacola-timeline-studio"),
        ) {
            TimelineTransportBar(controller = controller)
            Divider(Orientation.Horizontal, Modifier.fillMaxWidth())
            Row(Modifier.fillMaxSize()) {
                TimelineMultiTrackArea(
                    controller = controller,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
                Divider(Orientation.Vertical, Modifier.fillMaxHeight())
                TimelineBlockInspector(controller = controller)
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
) : EaselWindow {
    override val name: String = "Timeline"
    override var visible: Boolean by mutableStateOf(false)

    /**
     * Stage-space decorator that provides [LocalDeckLoopClock] and [LocalMotionPathPreviewState],
     * and draws curved shared-element motion-path previews on the `1920x1080` slide canvas.
     */
    val stageDecorator: ContentDecorator = createTimelineStageDecorator(
        loopClock = controller.loopClock,
        motionPathPreview = controller.motionPathPreview,
        overridesStore = controller.overridesStore,
    )

    override val decorator: ContentDecorator = ContentDecorator { content ->
        val scope = rememberCoroutineScope()
        val isDark = currentPresetProvider() == DeckThemePreset.StageDark
        var totalHeightPx by remember { mutableStateOf(720f) }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { totalHeightPx = it.height.toFloat().coerceAtLeast(240f) }
                .onPreviewKeyEvent { ev ->
                    if (suppressShortcutsProvider()) {
                        false
                    } else if (ev.type == KeyEventType.KeyDown && ev.key == Key.F5) {
                        controller.dockedInStoryWindow = !controller.dockedInStoryWindow
                        true
                    } else if (ev.type == KeyEventType.KeyDown &&
                        ev.key == Key.Spacebar &&
                        !ev.isCtrlPressed &&
                        !ev.isMetaPressed &&
                        !ev.isAltPressed &&
                        controller.dockedInStoryWindow
                    ) {
                        controller.togglePlayback(scope)
                        true
                    } else {
                        false
                    }
                },
        ) {
            if (controller.dockedInStoryWindow) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(controller.splitFraction.coerceIn(0.28f, 0.78f)),
                ) {
                    content()
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
                    TimelineStudioPanel(controller = controller, isDark = isDark)
                }
            } else {
                Box(Modifier.fillMaxSize()) {
                    content()
                }
            }
        }
    }

    @Composable
    override fun MenuScope.Menu() {
        val scope = rememberCoroutineScope()
        CheckboxItem(
            text = "Dock Timeline Studio in Story Window",
            checked = controller.dockedInStoryWindow,
            shortcut = KeyShortcut(Key.F5),
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
    }

    @Composable
    override fun Content() {
        val isDark = currentPresetProvider() == DeckThemePreset.StageDark
        TimelineStudioPanel(controller = controller, isDark = isDark)
    }
}

@Composable
fun rememberTimelineStudioWindow(
    controller: TimelineStudioController,
    currentPresetProvider: () -> DeckThemePreset = { DeckThemePreset.StageDark },
    suppressShortcutsProvider: () -> Boolean = { false },
): TimelineStudioWindow = remember(controller) {
    TimelineStudioWindow(controller, currentPresetProvider, suppressShortcutsProvider)
}

/**
 * Creates a `1920x1080` slide-stage [ContentDecorator] providing [LocalDeckLoopClock] and
 * [LocalMotionPathPreviewState] plus live quadratic Bezier motion-arc rendering.
 */
fun createTimelineStageDecorator(
    loopClock: DeckLoopClock,
    motionPathPreview: MotionPathPreviewState,
    overridesStore: TimelineOverridesStore,
): ContentDecorator = ContentDecorator { content ->
    CompositionLocalProvider(
        LocalDeckLoopClock provides loopClock,
        LocalMotionPathPreviewState provides motionPathPreview,
    ) {
        Box(Modifier.fillMaxSize()) {
            content()
            if (LocalSceneMode.current == SceneMode.Story && loopClock.activeSubCompLoopId == null) {
                MotionArcStageOverlay(
                    motionPathPreview = motionPathPreview,
                    overridesStore = overridesStore,
                )
            }
        }
    }
}

@Composable
fun rememberTimelineStageDecorator(
    loopClock: DeckLoopClock,
    motionPathPreview: MotionPathPreviewState,
    overridesStore: TimelineOverridesStore,
): ContentDecorator = remember(loopClock, motionPathPreview, overridesStore) {
    createTimelineStageDecorator(loopClock, motionPathPreview, overridesStore)
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
            color = Color(0xFFD59BF6),
            style = Stroke(width = 3f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f), 0f)),
        )
        drawCircle(color = Color(0xFFD59BF6), radius = 7f, center = s)
        drawCircle(color = Color(0xFFD59BF6), radius = 7f, center = e)
    }
}
