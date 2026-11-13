package dev.sebastiano.ciacola.presenter

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.window.WindowPlacement
import dev.bnorm.storyboard.AdvanceDirection
import dev.bnorm.storyboard.Storyboard
import dev.bnorm.storyboard.easel.Animatic
import java.awt.EventQueue
import java.awt.GraphicsDevice
import java.awt.GraphicsEnvironment
import java.awt.HeadlessException
import java.awt.Rectangle
import java.awt.Window as AwtWindow
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Full-stage blackout or whiteout curtain for live presenting pauses. */
enum class StageCurtain {
    None,
    Blackout,
    Whiteout,
}

/** Which window currently hosts the Quick Slide Jump dialog (`Cmd+G`). */
enum class JumpDialogHost {
    Story,
    Presenter,
}

/** Talk pacing status derived from elapsed talk time, target duration, and deck progress. */
enum class PaceStatus(val label: String) {
    OnPace("On pace"),
    Tight("Tight"),
    Over("Over"),
}

/** Run state of the presenter elapsed talk timer. */
enum class TimerRunState {
    Stopped,
    Running,
    Paused,
}

/** Snapshot of pre-show readiness hazards before entering a live talk. */
data class ReadinessStatus(
    val studioOpen: Boolean,
    val inspectOpen: Boolean,
    val hasUnbakedOverrides: Boolean,
) {
    val hasAnyHazard: Boolean
        get() = studioOpen || inspectOpen || hasUnbakedOverrides

    val hasClosableAuthoringTools: Boolean
        get() = studioOpen || inspectOpen
}

/** Connected physical/logical display metadata from AWT [GraphicsEnvironment]. */
data class DisplayScreenInfo(
    val id: String,
    val label: String,
    val bounds: Rectangle,
    val isPrimary: Boolean,
    val device: GraphicsDevice? = null,
)

/** Abstracts connected display discovery and window routing for testability. */
interface DisplayRoutingAdapter {
    fun screens(): List<DisplayScreenInfo>
    fun presentStoryOn(screen: DisplayScreenInfo): Boolean
    fun swapAudienceAndPresenter(): Boolean
}

/** Default AWT/Swing display router backed by [GraphicsEnvironment] and open [ComposeWindow]s. */
class AwtDisplayRouter(
    private val windowLister: () -> List<ComposeWindow> = {
        runCatching {
            AwtWindow.getWindows().filterIsInstance<ComposeWindow>().filter { it.isShowing }
        }.getOrDefault(emptyList())
    },
) : DisplayRoutingAdapter {
    override fun screens(): List<DisplayScreenInfo> {
        return try {
            val env = GraphicsEnvironment.getLocalGraphicsEnvironment()
            val primary = runCatching { env.defaultScreenDevice }.getOrNull()
            env.screenDevices.mapIndexed { index, device ->
                val bounds = device.defaultConfiguration.bounds
                val isPrimary = device === primary
                val suffix = if (isPrimary) " · Primary" else ""
                DisplayScreenInfo(
                    id = device.iDstring ?: "display-$index",
                    label = "Display ${index + 1} (${bounds.width}×${bounds.height}$suffix)",
                    bounds = bounds,
                    isPrimary = isPrimary,
                    device = device,
                )
            }
        } catch (_: HeadlessException) {
            emptyList()
        }
    }

    override fun presentStoryOn(screen: DisplayScreenInfo): Boolean {
        val storyWin = findStoryWindow() ?: return false
        runOnEdt {
            moveAndFullscreen(storyWin, screen.bounds)
        }
        return true
    }

    override fun swapAudienceAndPresenter(): Boolean {
        val allScreens = screens()
        if (allScreens.size < 2) return false
        val storyWin = findStoryWindow() ?: return false
        val presenterWin = findPresenterWindow() ?: return false

        val currentStoryScreen = screenForWindow(storyWin, allScreens) ?: allScreens.first()
        val currentPresenterScreen = screenForWindow(presenterWin, allScreens)
            ?.takeIf { it.id != currentStoryScreen.id }
            ?: allScreens.first { it.id != currentStoryScreen.id }

        val storyWasFullscreen = storyWin.placement == WindowPlacement.Fullscreen
        runOnEdt {
            moveWindowToBounds(presenterWin, currentStoryScreen.bounds, fullscreen = false)
            if (storyWasFullscreen) {
                moveAndFullscreen(storyWin, currentPresenterScreen.bounds)
            } else {
                moveWindowToBounds(storyWin, currentPresenterScreen.bounds, fullscreen = false)
            }
        }
        return true
    }

    private fun findStoryWindow(): ComposeWindow? =
        windowLister().firstOrNull { it.title.endsWith(" - Story") }

    private fun findPresenterWindow(): ComposeWindow? =
        windowLister().firstOrNull { it.title.endsWith(" - Presenter") }

    private fun screenForWindow(window: ComposeWindow, screens: List<DisplayScreenInfo>): DisplayScreenInfo? {
        val device = window.graphicsConfiguration?.device
        if (device != null) {
            screens.firstOrNull { it.device === device }?.let { return it }
        }
        val centerX = window.x + window.width / 2
        val centerY = window.y + window.height / 2
        return screens.firstOrNull { it.bounds.contains(centerX, centerY) }
    }

    private fun moveAndFullscreen(window: ComposeWindow, targetBounds: Rectangle) {
        if (window.placement == WindowPlacement.Fullscreen) {
            window.placement = WindowPlacement.Floating
            EventQueue.invokeLater {
                relocateCentered(window, targetBounds)
                EventQueue.invokeLater {
                    window.placement = WindowPlacement.Fullscreen
                }
            }
        } else {
            relocateCentered(window, targetBounds)
            EventQueue.invokeLater {
                window.placement = WindowPlacement.Fullscreen
            }
        }
    }

    private fun moveWindowToBounds(window: ComposeWindow, targetBounds: Rectangle, fullscreen: Boolean) {
        if (window.placement == WindowPlacement.Fullscreen) {
            window.placement = WindowPlacement.Floating
        }
        relocateCentered(window, targetBounds)
        if (fullscreen) {
            EventQueue.invokeLater { window.placement = WindowPlacement.Fullscreen }
        }
    }

    private fun relocateCentered(window: ComposeWindow, targetBounds: Rectangle) {
        val minSize = window.minimumSize
        val minW = (minSize?.width ?: 640).coerceAtLeast(320)
        val minH = (minSize?.height ?: 420).coerceAtLeast(240)
        val w = window.width.coerceIn(minW, targetBounds.width.coerceAtLeast(minW))
        val h = window.height.coerceIn(minH, targetBounds.height.coerceAtLeast(minH))
        val x = targetBounds.x + ((targetBounds.width - w) / 2).coerceAtLeast(0)
        val y = targetBounds.y + ((targetBounds.height - h) / 2).coerceAtLeast(0)
        window.setBounds(x, y, w, h)
    }

    private fun runOnEdt(block: () -> Unit) {
        if (EventQueue.isDispatchThread()) {
            block()
        } else {
            EventQueue.invokeLater(block)
        }
    }
}

/**
 * Elapsed talk timer and pace calculator decoupled from UI composition.
 */
@Stable
class PresenterTimerState(
    private val clockMillis: () -> Long = System::currentTimeMillis,
) {
    var runState: TimerRunState by mutableStateOf(TimerRunState.Stopped)
        private set

    private var accumulatedBeforeResumeMs by mutableLongStateOf(0L)
    private var startedAtMs by mutableLongStateOf(0L)

    var targetDurationMinutes: Int by mutableIntStateOf(DEFAULT_TARGET_MINUTES)
        private set

    fun elapsedSeconds(nowMs: Long = clockMillis()): Long = when (runState) {
        TimerRunState.Stopped -> 0L
        TimerRunState.Paused -> (accumulatedBeforeResumeMs / 1000L).coerceAtLeast(0L)
        TimerRunState.Running -> ((accumulatedBeforeResumeMs + (nowMs - startedAtMs).coerceAtLeast(0L)) / 1000L)
    }

    fun togglePlayPause() {
        val now = clockMillis()
        runState = when (runState) {
            TimerRunState.Stopped -> {
                accumulatedBeforeResumeMs = 0L
                startedAtMs = now
                TimerRunState.Running
            }

            TimerRunState.Running -> {
                accumulatedBeforeResumeMs += (now - startedAtMs).coerceAtLeast(0L)
                TimerRunState.Paused
            }

            TimerRunState.Paused -> {
                startedAtMs = now
                TimerRunState.Running
            }
        }
    }

    fun reset() {
        accumulatedBeforeResumeMs = 0L
        when (runState) {
            TimerRunState.Running -> startedAtMs = clockMillis()
            TimerRunState.Paused, TimerRunState.Stopped -> runState = TimerRunState.Stopped
        }
    }

    fun adjustTargetMinutes(deltaMinutes: Int) {
        targetDurationMinutes = (targetDurationMinutes + deltaMinutes)
            .coerceIn(MIN_TARGET_MINUTES, MAX_TARGET_MINUTES)
    }

    fun paceStatus(elapsedSeconds: Long, flatBeatIndex: Int, totalBeats: Int): PaceStatus {
        val targetSeconds = (targetDurationMinutes * 60L).coerceAtLeast(60L)
        val remainingSeconds = targetSeconds - elapsedSeconds
        if (remainingSeconds < 0L) return PaceStatus.Over
        if (elapsedSeconds == 0L) return PaceStatus.OnPace

        val elapsedFraction = elapsedSeconds.toFloat() / targetSeconds.toFloat()
        val deckFraction = if (totalBeats <= 1) 1f else flatBeatIndex.toFloat() / (totalBeats - 1).toFloat()
        val aheadOfScheduleTime = (elapsedFraction - deckFraction) > 0.15f
        val tightRemaining = remainingSeconds <= 180L &&
            flatBeatIndex < totalBeats - 1 &&
            (remainingSeconds.toFloat() / targetSeconds.toFloat()) < 0.20f
        return if (aheadOfScheduleTime || tightRemaining) PaceStatus.Tight else PaceStatus.OnPace
    }

    companion object {
        const val DEFAULT_TARGET_MINUTES = 35
        const val MIN_TARGET_MINUTES = 5
        const val MAX_TARGET_MINUTES = 180
        const val TARGET_STEP_MINUTES = 5

        fun formatMmSs(totalSeconds: Long): String {
            val safe = totalSeconds.coerceAtLeast(0L)
            val mins = safe / 60L
            val secs = safe % 60L
            return "${mins.toString().padStart(2, '0')}:${secs.toString().padStart(2, '0')}"
        }

        fun formatRemaining(elapsedSeconds: Long, targetMinutes: Int): String {
            val remaining = targetMinutes * 60L - elapsedSeconds
            return if (remaining >= 0L) {
                "${formatMmSs(remaining)} left"
            } else {
                "+${formatMmSs(-remaining)} over"
            }
        }
    }
}

/**
 * Presenter/coordinator for Ciacola's `F2` Presenter View, live stage curtain (`B`/`W`),
 * quick slide jump (`Cmd+G`), display routing, and pre-show readiness checks.
 */
@Stable
class PresenterController(
    initialAnimatic: Animatic,
    private val speakerNotesProvider: (Storyboard) -> DeckSpeakerNotes? = {
        StoryboardSpeakerNotesRegistry.forStoryboard(it)
    },
    private val isStudioOpenProvider: () -> Boolean = { false },
    private val isInspectOpenProvider: () -> Boolean = { false },
    private val hasUnbakedOverridesProvider: () -> Boolean = { false },
    private val onEnterCleanShowModeAction: () -> Unit = {},
    private val isExternalTextInputActiveProvider: () -> Boolean = { false },
    private val onRestoreStageFocus: () -> Unit = {},
    private val displayRouter: DisplayRoutingAdapter = AwtDisplayRouter(),
    timerClockMillis: () -> Long = System::currentTimeMillis,
    private val wallClockProvider: () -> String = { LocalTime.now().format(WALL_CLOCK_FORMATTER) },
) {
    var animatic: Animatic by mutableStateOf(initialAnimatic)
        internal set

    val timer = PresenterTimerState(timerClockMillis)

    var curtain: StageCurtain by mutableStateOf(StageCurtain.None)
        internal set

    var notesFontSizeSp: Float by mutableFloatStateOf(DEFAULT_NOTES_FONT_SP)
        internal set

    var jumpDialogHost: JumpDialogHost? by mutableStateOf(null)
        internal set

    val jumpDialogOpen: Boolean
        get() = jumpDialogHost != null

    var isOverviewOpen: Boolean by mutableStateOf(false)
        internal set

    val isExternalTextInputActive: Boolean
        get() = isExternalTextInputActiveProvider()

    var availableDisplays: List<DisplayScreenInfo> by mutableStateOf(displayRouter.screens())
        private set

    internal var swallowNextEscapeKeyUp: Boolean by mutableStateOf(false)

    var unbakedNoticeDismissed: Boolean by mutableStateOf(false)

    private var advanceJob: Job? = null

    val currentIndex: Storyboard.Index
        get() = animatic.targetIndex

    val slideCount: Int
        get() = animatic.storyboard.scenes.size

    val currentSceneIndex: Int
        get() = currentIndex.sceneIndex.coerceIn(0, (slideCount - 1).coerceAtLeast(0))

    val currentBeatIndex: Int
        get() = currentIndex.frameIndex.coerceAtLeast(0)

    val currentBeatCount: Int
        get() = animatic.storyboard.scenes.getOrNull(currentSceneIndex)?.frames?.size?.coerceAtLeast(1) ?: 1

    val isTransitioning: Boolean
        get() = animatic.currentIndex != animatic.targetIndex ||
            (animatic.activeFraction > 0.001f && animatic.activeFromStateIndex != animatic.activeToStateIndex)

    val transitionProgress: Float
        get() = if (animatic.currentIndex != animatic.targetIndex || animatic.activeFromStateIndex != animatic.activeToStateIndex) {
            animatic.activeFraction.coerceIn(0f, 1f)
        } else {
            1f
        }

    val canStepBackward: Boolean
        get() = animatic.storyboard.indices.firstOrNull()?.let { currentIndex > it } == true

    val canStepForward: Boolean
        get() = animatic.storyboard.indices.lastOrNull()?.let { currentIndex < it } == true

    val nextIndex: Storyboard.Index?
        get() {
            val indices = animatic.storyboard.indices
            val pos = indices.binarySearch(currentIndex)
            return if (pos >= 0) indices.getOrNull(pos + 1) else null
        }

    val flatBeatIndex: Int
        get() = animatic.storyboard.indices.binarySearch(currentIndex).coerceAtLeast(0)

    val totalFlatBeats: Int
        get() = animatic.storyboard.indices.size.coerceAtLeast(1)

    fun currentSpeakerNotes(): DeckSpeakerNotes =
        speakerNotesProvider(animatic.storyboard) ?: DeckSpeakerNotes.Empty

    fun resolvedCurrentNotes(): ResolvedSpeakerNotes =
        currentSpeakerNotes().resolve(animatic.storyboard, currentIndex)

    fun nextStepLabel(): String {
        val next = nextIndex ?: return "End of Deck"
        return if (next.sceneIndex == currentSceneIndex) {
            "Next Beat (${next.frameIndex + 1} / $currentBeatCount)"
        } else {
            val title = currentSpeakerNotes().sceneTitle(next.sceneIndex)
            "Next Slide: $title"
        }
    }

    fun wallClockText(): String = wallClockProvider()

    fun readinessStatus(): ReadinessStatus = ReadinessStatus(
        studioOpen = isStudioOpenProvider(),
        inspectOpen = isInspectOpenProvider(),
        hasUnbakedOverrides = hasUnbakedOverridesProvider(),
    )

    fun enterCleanShowMode() {
        onEnterCleanShowModeAction()
    }

    fun isShowModeActive(): Boolean =
        !isStudioOpenProvider() &&
            !isInspectOpenProvider() &&
            !isOverviewOpen &&
            !isExternalTextInputActive &&
            !jumpDialogOpen

    fun refreshDisplays(): List<DisplayScreenInfo> {
        val screens = displayRouter.screens()
        availableDisplays = screens
        return screens
    }

    fun connectedDisplays(): List<DisplayScreenInfo> = refreshDisplays()

    fun presentOnDisplay(screen: DisplayScreenInfo): Boolean =
        displayRouter.presentStoryOn(screen)

    fun swapAudienceAndPresenterDisplays(): Boolean =
        displayRouter.swapAudienceAndPresenter()

    fun step(scope: CoroutineScope, direction: AdvanceDirection) {
        if (curtain != StageCurtain.None) {
            curtain = StageCurtain.None
        }
        advanceJob?.cancel()
        advanceJob = scope.launch {
            animatic.advance(direction)
            advanceJob = null
        }
    }

    fun jumpToSlide(scope: CoroutineScope, oneBasedSlide: Int): Boolean {
        val targetScene = oneBasedSlide - 1
        if (targetScene !in 0 until slideCount) return false
        val targetIdx = Storyboard.Index(targetScene, 0)
        if (targetIdx !in animatic.storyboard.indices) return false
        val host = jumpDialogHost
        curtain = StageCurtain.None
        jumpDialogHost = null
        advanceJob?.cancel()
        advanceJob = scope.launch {
            animatic.jumpTo(targetIdx)
            advanceJob = null
        }
        if (host == JumpDialogHost.Story) {
            onRestoreStageFocus()
        }
        return true
    }

    fun toggleCurtain(target: StageCurtain) {
        curtain = if (curtain == target) StageCurtain.None else target
    }

    fun clearCurtain() {
        curtain = StageCurtain.None
    }

    fun openJumpDialog(host: JumpDialogHost = JumpDialogHost.Story) {
        jumpDialogHost = host
    }

    fun closeJumpDialog(fromEscapeKey: Boolean = false) {
        val host = jumpDialogHost
        if (fromEscapeKey && host == JumpDialogHost.Story) {
            swallowNextEscapeKeyUp = true
        }
        jumpDialogHost = null
        if (host == JumpDialogHost.Story) {
            onRestoreStageFocus()
        }
    }

    fun increaseNotesFontSize() {
        notesFontSizeSp = (notesFontSizeSp + NOTES_FONT_STEP_SP).coerceIn(MIN_NOTES_FONT_SP, MAX_NOTES_FONT_SP)
    }

    fun decreaseNotesFontSize() {
        notesFontSizeSp = (notesFontSizeSp - NOTES_FONT_STEP_SP).coerceIn(MIN_NOTES_FONT_SP, MAX_NOTES_FONT_SP)
    }

    /**
     * Preview key handler installed at the Story window root: manages `B` / `W` blackout/whiteout,
     * `Esc` curtain/dialog dismissal without leaking KeyUp to `EaselOverview`, and `Cmd+G` jump.
     */
    fun handleStoryKeyEvent(event: KeyEvent): Boolean {
        if (event.key == Key.Escape) {
            if (swallowNextEscapeKeyUp) {
                if (event.type == KeyEventType.KeyUp) {
                    swallowNextEscapeKeyUp = false
                }
                return true
            }
            if (jumpDialogOpen) {
                if (event.type == KeyEventType.KeyDown) {
                    closeJumpDialog(fromEscapeKey = true)
                }
                return true
            }
            if (curtain != StageCurtain.None) {
                if (event.type == KeyEventType.KeyDown) {
                    curtain = StageCurtain.None
                    swallowNextEscapeKeyUp = true
                }
                return true
            }
            return false
        }

        if (event.type != KeyEventType.KeyDown) return false

        val primaryMod = event.isMetaPressed || event.isCtrlPressed
        if (primaryMod && !event.isAltPressed && !event.isShiftPressed && event.key == Key.G) {
            if (!isExternalTextInputActive && !isOverviewOpen) {
                openJumpDialog(JumpDialogHost.Story)
                return true
            }
            return false
        }

        if (jumpDialogOpen || !isShowModeActive()) return false

        val noModifiers = !event.isCtrlPressed && !event.isMetaPressed && !event.isAltPressed && !event.isShiftPressed
        if (!noModifiers) return false

        when (event.key) {
            Key.B -> {
                toggleCurtain(StageCurtain.Blackout)
                return true
            }

            Key.W -> {
                toggleCurtain(StageCurtain.Whiteout)
                return true
            }

            Key.DirectionRight,
            Key.DirectionDown,
            Key.PageDown,
            Key.Enter,
            Key.NumPadEnter,
            Key.Spacebar,
            Key.DirectionLeft,
            Key.DirectionUp,
            Key.PageUp,
            Key.Backspace,
            -> {
                if (curtain != StageCurtain.None) {
                    curtain = StageCurtain.None
                }
                return false
            }
        }
        return false
    }

    /**
     * Capture-phase key handler on the Presenter window root: handles `Escape` dismissal and
     * modifier chords (`Cmd+G`, `Cmd+-`, `Cmd+=`) without intercepting `Space` or `Enter` before
     * focused child buttons receive them.
     */
    fun handlePresenterPreviewKeyEvent(event: KeyEvent): Boolean {
        if (event.key == Key.Escape) {
            if (jumpDialogOpen) {
                if (event.type == KeyEventType.KeyDown) {
                    closeJumpDialog(fromEscapeKey = false)
                }
                return true
            }
            if (curtain != StageCurtain.None) {
                if (event.type == KeyEventType.KeyDown) {
                    curtain = StageCurtain.None
                }
                return true
            }
            return false
        }

        if (event.type != KeyEventType.KeyDown) return false

        val primaryMod = event.isMetaPressed || event.isCtrlPressed
        if (primaryMod && !event.isAltPressed) {
            when (event.key) {
                Key.G -> if (!event.isShiftPressed) {
                    openJumpDialog(JumpDialogHost.Presenter)
                    return true
                }

                Key.Minus, Key.NumPadSubtract -> {
                    decreaseNotesFontSize()
                    return true
                }

                Key.Equals, Key.Plus, Key.NumPadAdd -> {
                    increaseNotesFontSize()
                    return true
                }
            }
        }
        return false
    }

    /**
     * Bubble-phase key handler on the Presenter window root: runs after focused child buttons had
     * first crack at `Space` / `Enter`, handling timer (`T`/`R`), curtains (`B`/`W`), and smooth
     * deck navigation.
     */
    fun handlePresenterBubbleKeyEvent(event: KeyEvent, scope: CoroutineScope): Boolean {
        if (event.type != KeyEventType.KeyDown || jumpDialogOpen) return false

        val noModifiers = !event.isCtrlPressed && !event.isMetaPressed && !event.isAltPressed && !event.isShiftPressed
        if (!noModifiers) return false

        return when (event.key) {
            Key.T -> {
                timer.togglePlayPause()
                true
            }

            Key.R -> {
                timer.reset()
                true
            }

            Key.B -> {
                if (isShowModeActive()) {
                    toggleCurtain(StageCurtain.Blackout)
                    true
                } else {
                    false
                }
            }

            Key.W -> {
                if (isShowModeActive()) {
                    toggleCurtain(StageCurtain.Whiteout)
                    true
                } else {
                    false
                }
            }

            Key.DirectionRight,
            Key.DirectionDown,
            Key.PageDown,
            Key.Enter,
            Key.NumPadEnter,
            Key.Spacebar,
            -> {
                step(scope, AdvanceDirection.Forward)
                true
            }

            Key.DirectionLeft,
            Key.DirectionUp,
            Key.PageUp,
            Key.Backspace,
            -> {
                step(scope, AdvanceDirection.Backward)
                true
            }

            else -> false
        }
    }

    companion object {
        const val DEFAULT_NOTES_FONT_SP = 20f
        const val MIN_NOTES_FONT_SP = 14f
        const val MAX_NOTES_FONT_SP = 36f
        const val NOTES_FONT_STEP_SP = 2f

        private val WALL_CLOCK_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    }
}
