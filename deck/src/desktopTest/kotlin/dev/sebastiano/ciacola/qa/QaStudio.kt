package dev.sebastiano.ciacola.qa

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.bnorm.storyboard.ContentDecorator
import dev.bnorm.storyboard.Storyboard
import dev.bnorm.storyboard.easel.Animatic
import dev.bnorm.storyboard.easel.Easel
import dev.bnorm.storyboard.easel.LocalOverviewOpen
import dev.bnorm.storyboard.easel.overlay.EaselOverlay
import dev.bnorm.storyboard.easel.overlay.OverlayNavigation
import dev.bnorm.storyboard.easel.overview.EaselOverview
import dev.bnorm.storyboard.easel.overview.rememberOverviewVisibility
import dev.bnorm.storyboard.easel.rememberAnimatic
import dev.bnorm.storyboard.plus
import dev.sebastiano.ciacola.DeckCatalog
import dev.sebastiano.ciacola.DeckEntry
import dev.sebastiano.ciacola.authoring.DeckHostParams
import dev.sebastiano.ciacola.createFoundationDeck
import dev.sebastiano.ciacola.inspector.DeckElementOverridesFile
import dev.sebastiano.ciacola.inspector.LocalStageDragPreview
import dev.sebastiano.ciacola.inspector.LocalStageInteractiveRegistry
import dev.sebastiano.ciacola.inspector.StageDragPreview
import dev.sebastiano.ciacola.inspector.StageInspectionRegistry
import dev.sebastiano.ciacola.inspector.StageInteractiveRegistry
import dev.sebastiano.ciacola.inspector.design.DesignSystemStore
import dev.sebastiano.ciacola.inspector.element.ElementEditorController
import dev.sebastiano.ciacola.inspector.element.ElementOverridesStore
import dev.sebastiano.ciacola.inspector.element.rememberElementEditorController
import dev.sebastiano.ciacola.inspector.lint.DeckLintController
import dev.sebastiano.ciacola.inspector.lint.rememberDeckLintStageDecorator
import dev.sebastiano.ciacola.presenter.PresenterController
import dev.sebastiano.ciacola.presenter.PresenterWindow
import dev.sebastiano.ciacola.presenter.rememberPresenterWindow
import dev.sebastiano.ciacola.rememberHotReloadStoryboard
import dev.sebastiano.ciacola.review.ReviewCommentStore
import dev.sebastiano.ciacola.review.ReviewStageCoordinator
import dev.sebastiano.ciacola.review.SlideReviewWindow
import dev.sebastiano.ciacola.review.rememberReviewStageCoordinator
import dev.sebastiano.ciacola.review.rememberSlideReviewWindow
import dev.sebastiano.ciacola.studio.DeckSwitcherDropdown
import dev.sebastiano.ciacola.studio.StudioInspectorTab
import dev.sebastiano.ciacola.studio.StudioUnifiedInspectorPane
import dev.sebastiano.ciacola.studio.StudioWorkspaceController
import dev.sebastiano.ciacola.studio.StudioWorkspaceSyncEffects
import dev.sebastiano.ciacola.studio.loadPersistedInspectorWidthDp
import dev.sebastiano.ciacola.studio.persistActiveDeckId
import dev.sebastiano.ciacola.studio.persistInspectorWidthDp
import dev.sebastiano.ciacola.studio.rememberDeckMenuWindow
import dev.sebastiano.ciacola.studio.resolveInitialDeckId
import dev.sebastiano.ciacola.studio.storyboardDirForDeck
import dev.sebastiano.ciacola.theme.DeckTheme
import dev.sebastiano.ciacola.theme.DeckThemePreset
import dev.sebastiano.ciacola.theme.DeckThemes
import dev.sebastiano.ciacola.theme.DesignSystemOverrides
import dev.sebastiano.ciacola.theme.withOverrides
import dev.sebastiano.ciacola.timeline.DeckLoopClock
import dev.sebastiano.ciacola.timeline.DeckTimelineOverridesFile
import dev.sebastiano.ciacola.timeline.MotionPathPreviewState
import dev.sebastiano.ciacola.timeline.TimelineOverridesStore
import dev.sebastiano.ciacola.timeline.TimelineStageSelection
import dev.sebastiano.ciacola.timeline.TimelineStudioController
import dev.sebastiano.ciacola.timeline.TimelineStudioWindow
import dev.sebastiano.ciacola.timeline.rememberTimelineStageDecorator
import dev.sebastiano.ciacola.timeline.rememberTimelineStudioController
import dev.sebastiano.ciacola.timeline.rememberTimelineStudioWindow
import dev.sebastiano.ciacola.ui.AuthoringTextInputFocusTracker
import dev.sebastiano.ciacola.ui.LocalAuthoringTextInputTracker
import dev.sebastiano.spectre.core.AutomatorNode
import dev.sebastiano.spectre.core.ComposeAutomator
import dev.sebastiano.spectre.core.RobotDriver
import dev.sebastiano.spectre.testing.ComposeAutomatorRule
import java.awt.EventQueue
import java.awt.Point
import java.awt.Window as AwtWindow
import java.awt.event.InputEvent
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.imageio.ImageIO
import kotlin.concurrent.thread
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/** Builds a custom [Storyboard] inside [QaStudio] with full Studio decorator and override wiring. */
data class QaStoryboardRequest(
    val theme: DeckTheme,
    val bundledPreset: DeckThemePreset,
    val includeKeynoteEffectsLab: Boolean = true,
    val stageDecorator: ContentDecorator,
    val timelineOverrides: DeckTimelineOverridesFile,
    val elementOverrides: DeckElementOverridesFile,
    val designOverrides: DesignSystemOverrides,
    val timelineOverridesProvider: () -> DeckTimelineOverridesFile,
    val elementOverridesProvider: () -> DeckElementOverridesFile,
    val designOverridesProvider: () -> DesignSystemOverrides,
)

typealias QaDeckBuildParams = QaStoryboardRequest
typealias QaStoryboardFactory = (QaStoryboardRequest) -> Storyboard

/** Builds a focus-safe Spectre [ComposeAutomatorRule] backed by [RobotDriver.synthetic]. */
fun qaAutomatorRule(): ComposeAutomatorRule = ComposeAutomatorRule(
    factory = { ComposeAutomator.inProcess(robotDriver = RobotDriver.synthetic()) },
)

/**
 * JUnit 4 rule that manages both Spectre's synthetic [ComposeAutomator] and automatic teardown
 * of every [QaStudio] launched during the test.
 */
class QaStudioRule : TestRule {
    private val automatorRule = qaAutomatorRule()
    private val launchedStudios = CopyOnWriteArrayList<QaStudio>()

    val automator: ComposeAutomator
        get() = automatorRule.automator

    fun launchStudio(
        preset: DeckThemePreset = DeckThemePreset.StageDark,
        windowSize: DpSize = DpSize(1360.dp, 900.dp),
        windowPosition: WindowPosition = WindowPosition(80.dp, 60.dp),
        timelineDocked: Boolean = false,
        timelineDetached: Boolean = false,
        detachedWindowSize: DpSize = DpSize(1180.dp, 460.dp),
        detachedWindowPosition: WindowPosition = WindowPosition(120.dp, 520.dp),
        includeKeynoteEffectsLab: Boolean = true,
        includeOverlayNavigation: Boolean = false,
        deckId: String? = null,
        storyboardFactory: QaStoryboardFactory? = null,
        worker: String? = null,
        qaRoot: File = QaPaths.qaRoot(),
        evidenceDir: File? = null,
        findingsFile: File? = null,
        captureDir: File? = defaultSpectreCaptureDir(),
    ): QaStudio = launchStudio(
        automator = automator,
        preset = preset,
        windowSize = windowSize,
        windowPosition = windowPosition,
        timelineDocked = timelineDocked,
        timelineDetached = timelineDetached,
        detachedWindowSize = detachedWindowSize,
        detachedWindowPosition = detachedWindowPosition,
        includeKeynoteEffectsLab = includeKeynoteEffectsLab,
        includeOverlayNavigation = includeOverlayNavigation,
        deckId = deckId,
        storyboardFactory = storyboardFactory,
        worker = worker,
        qaRoot = qaRoot,
        evidenceDir = evidenceDir,
        findingsFile = findingsFile,
        captureDir = captureDir,
    ).also { launchedStudios += it }

    override fun apply(base: Statement, description: Description): Statement {
        val inner = object : Statement() {
            override fun evaluate() {
                try {
                    base.evaluate()
                } finally {
                    val toClose = launchedStudios.toList()
                    launchedStudios.clear()
                    toClose.forEach { runCatching { it.close() } }
                }
            }
        }
        return automatorRule.apply(inner, description)
    }
}

/**
 * Reflective handle on Spectre's internal `SyntheticRobotAdapter` so tests can perform gestures
 * that hold modifier keys across wheel notches, drag with middle/secondary mouse buttons, or hold
 * `Space` / `Ctrl` during drags without touching real OS HID input or stealing focus.
 *
 * When [rootWindow] is `null` (default), events dispatch to whichever showing AWT window in this
 * JVM contains the pointer (Story window, detached Timeline window, Review window, or popups).
 */
class SyntheticInput(rootWindow: AwtWindow? = null) {
    private val adapter: Any = run {
        val cls = Class.forName("dev.sebastiano.spectre.core.SyntheticRobotAdapter")
        if (rootWindow != null) {
            cls.getConstructor(AwtWindow::class.java).newInstance(rootWindow)
        } else {
            cls.getConstructor().newInstance()
        }
    }

    private fun call(name: String, vararg args: Int) {
        val types = Array(args.size) { Int::class.javaPrimitiveType!! }
        adapter.javaClass.getMethod(name, *types).invoke(adapter, *args.toTypedArray())
    }

    fun mouseMove(x: Int, y: Int) = call("mouseMove", x, y)
    fun mousePress(buttons: Int = InputEvent.BUTTON1_DOWN_MASK) = call("mousePress", buttons)
    fun mouseRelease(buttons: Int = InputEvent.BUTTON1_DOWN_MASK) = call("mouseRelease", buttons)
    fun mouseWheel(notches: Int) = call("mouseWheel", notches)
    fun keyPress(keyCode: Int) = call("keyPress", keyCode)
    fun keyRelease(keyCode: Int) = call("keyRelease", keyCode)
}

/**
 * Live handle to an isolated Ciacola Studio session running inside a real [ComposeWindow] without
 * stealing OS focus.
 */
class QaStudio internal constructor(
    val automator: ComposeAutomator,
    val window: ComposeWindow,
    val controller: TimelineStudioController,
    private val overridesStoreRef: AtomicReference<TimelineOverridesStore?>,
    val designStore: DesignSystemStore,
    private val elementStoreRef: AtomicReference<ElementOverridesStore?>,
    private val stageInspectionRegistryRef: AtomicReference<StageInspectionRegistry?>,
    private val elementControllerRef: AtomicReference<ElementEditorController?>,
    private val lintControllerRef: AtomicReference<DeckLintController?>,
    val studioWorkspace: StudioWorkspaceController,
    val presenterWindow: PresenterWindow,
    val presenter: PresenterController,
    val review: ReviewStageCoordinator,
    private val reviewStoreRef: AtomicReference<ReviewCommentStore?>,
    val timelineWindow: TimelineStudioWindow,
    private val reviewWindowRef: AtomicReference<SlideReviewWindow?>,
    private val animaticRef: AtomicReference<Animatic?>,
    private val loopClockRef: AtomicReference<DeckLoopClock?>,
    val motionPathPreview: MotionPathPreviewState,
    val stageSelection: TimelineStageSelection,
    val textInputTracker: AuthoringTextInputFocusTracker,
    val workspaceRoot: Path,
    val storyboardDir: Path,
    val worker: String,
    val qaRoot: File,
    val evidenceDir: File,
    val findingsFile: File,
    val captureDir: File?,
    private val uiScopeRef: AtomicReference<CoroutineScope?>,
    private val storyWindowRef: AtomicReference<ComposeWindow?>,
    private val detachedTimelineWindowRef: AtomicReference<ComposeWindow?>,
    private val reviewPanelWindowRef: AtomicReference<ComposeWindow?>,
    private val presenterComposeWindowRef: AtomicReference<ComposeWindow?>,
    private val presetReader: () -> DeckThemePreset,
    private val presetUpdater: (DeckThemePreset) -> Unit,
    private val activeDeckReader: () -> DeckEntry,
    private val deckSwitcher: (DeckEntry) -> Unit,
    private val exitAppRef: AtomicReference<(() -> Unit)?>,
    private val appThread: Thread,
    val deckId: String? = null,
) : AutoCloseable {

    private val closed = AtomicBoolean(false)

    val overridesStore: TimelineOverridesStore
        get() = checkNotNull(overridesStoreRef.get()) { "TimelineOverridesStore is not active" }

    val elementStore: ElementOverridesStore
        get() = checkNotNull(elementStoreRef.get()) { "ElementOverridesStore is not active" }

    val stageInspectionRegistry: StageInspectionRegistry
        get() = checkNotNull(stageInspectionRegistryRef.get()) { "StageInspectionRegistry is not active" }

    val elementController: ElementEditorController
        get() = checkNotNull(elementControllerRef.get()) { "ElementEditorController is not active" }

    val lintController: DeckLintController
        get() = checkNotNull(lintControllerRef.get()) { "DeckLintController is not active" }

    val reviewStore: ReviewCommentStore
        get() = checkNotNull(reviewStoreRef.get()) { "ReviewCommentStore is not active" }

    val reviewWindow: SlideReviewWindow
        get() = checkNotNull(reviewWindowRef.get()) { "SlideReviewWindow is not active" }

    val loopClock: DeckLoopClock
        get() = checkNotNull(loopClockRef.get()) { "DeckLoopClock is not active" }

    /** Currently active catalog deck entry. */
    val activeDeck: DeckEntry
        get() = activeDeckReader()

    /** Active deck's per-deck `.storyboard` directory. */
    val activeDeckStoryboardDir: Path
        get() = storyboardDirForDeck(storyboardDir, activeDeck.id)

    /** Live [Animatic] instance (tracks structural rebuilds and deck switches). */
    val animatic: Animatic
        get() = checkNotNull(animaticRef.get()) { "Animatic is not active" }

    /** Synthetic AWT input dispatcher across all Ciacola windows in this test JVM. */
    val input: SyntheticInput by lazy { SyntheticInput() }

    /** Current detached Timeline [ComposeWindow], or `null` when hidden. */
    val detachedTimelineWindow: ComposeWindow?
        get() = detachedTimelineWindowRef.get()

    /** Current Review sidecar [ComposeWindow], or `null` when hidden. */
    val reviewPanelWindow: ComposeWindow?
        get() = reviewPanelWindowRef.get()

    /** Current Presenter View [ComposeWindow], or `null` when hidden. */
    val presenterComposeWindow: ComposeWindow?
        get() = presenterComposeWindowRef.get()

    /** Alias for [presenterComposeWindow] used by QA suites. */
    val presenterPanelWindow: ComposeWindow?
        get() = presenterComposeWindowRef.get()

    /** Active theme preset (`StageDark` or `KeynoteLight`). */
    val preset: DeckThemePreset
        get() = presetReader()

    /** Creates a [SyntheticInput] pinned to a specific [targetWindow]. */
    fun inputFor(targetWindow: AwtWindow): SyntheticInput = SyntheticInput(targetWindow)

    /** Runs [block] on the Compose UI coroutine scope (AWT EDT) and awaits its result. */
    suspend fun <T> onUi(block: suspend () -> T): T {
        val scope = checkNotNull(uiScopeRef.get()) { "Compose UI scope is not active" }
        val deferred = CompletableDeferred<T>()
        scope.launch {
            runCatching { block() }.fold(deferred::complete, deferred::completeExceptionally)
        }
        return deferred.await()
    }

    /** Switches between [DeckThemePreset.StageDark] and [DeckThemePreset.KeynoteLight] live. */
    suspend fun setThemePreset(newPreset: DeckThemePreset) {
        onUi { presetUpdater(newPreset) }
        settle()
    }

    /** Switches the active catalog deck live and waits for Compose and timeline indexing to settle. */
    suspend fun selectDeck(targetDeckId: String) {
        val entry = DeckCatalog.byId(targetDeckId)
        onUi { deckSwitcher(entry) }
        settle()
    }

    /** Waits for Compose semantics and pending animations/frame callbacks to settle. */
    suspend fun settle() {
        automator.waitForIdle()
        delay(120)
        automator.waitForIdle()
    }

    /**
     * Waits until [targetWindow]'s [SkiaLayer] pixels remain identical across [stableFrames]
     * consecutive samples (UIElement-safe replacement for `automator.waitForVisualIdle()`).
     */
    suspend fun waitForWindowVisualIdle(
        targetWindow: ComposeWindow = window,
        stableFrames: Int = 3,
        sampleIntervalMs: Long = 40L,
        timeout: Duration = 5.seconds,
    ) {
        automator.waitForIdle()
        var previous: BufferedImage? = null
        var streak = 0
        val deadline = System.nanoTime() + timeout.inWholeNanoseconds
        while (System.nanoTime() < deadline) {
            val current = runCatching { runOnEdt { captureSkiaWindowFrameOnEdt(targetWindow) } }.getOrNull()
            if (current != null) {
                val prev = previous
                if (
                    prev != null &&
                    prev.width == current.width &&
                    prev.height == current.height &&
                    differingFraction(prev, current, channelTolerance = 2) == 0.0
                ) {
                    streak++
                    if (streak >= stableFrames - 1) return
                } else {
                    streak = 0
                }
                previous = current
            }
            delay(sampleIntervalMs)
        }
    }

    /** Device pixels per AWT screen point for [targetWindow]. */
    fun pixelScale(targetWindow: AwtWindow = window): Double =
        targetWindow.graphicsConfiguration?.defaultTransform?.scaleX ?: 1.0

    /** Screen point on the timeline ruler at [timeMs] inside [canvas]. */
    fun rulerPoint(
        canvas: AutomatorNode,
        timeMs: Int,
        targetWindow: AwtWindow = detachedTimelineWindow ?: window,
    ): Point {
        val bounds = checkNotNull(canvas.boundsOnScreen) { "Timeline canvas has no screen bounds" }
        val x = bounds.x + (controller.viewport.xForTime(timeMs.toFloat()) / pixelScale(targetWindow)).roundToInt()
        return Point(x, bounds.y + 39)
    }

    /** Center of an [AutomatorNode] in screen coordinates. */
    fun AutomatorNode.center(): Point = centerOnScreen

    /** Opens the docked Timeline Studio inside the Story window if not already docked. */
    suspend fun openTimeline(): AutomatorNode {
        if (!controller.dockedInStoryWindow) {
            val toggle = automator.waitForNode(tag = "ciacola-hud-toggle-timeline", timeout = 10.seconds)
            settle()
            automator.click(toggle)
        }
        val canvas = automator.waitForNode(tag = "ciacola-timeline-canvas", timeout = 5.seconds)
        settle()
        return canvas
    }

    /** Opens the standalone detached Timeline Studio window (`F6` mode) and returns its [ComposeWindow]. */
    suspend fun openDetachedTimeline(): ComposeWindow {
        onUi {
            controller.dockedInStoryWindow = false
            controller.detachedWindowVisible = true
        }
        automator.waitUntil(description = "detached timeline window showing", timeout = 10.seconds) {
            val w = detachedTimelineWindowRef.get()
            w != null && w.isShowing && findSkiaLayer(w) != null
        }
        automator.waitForNode(tag = "ciacola-timeline-canvas", timeout = 5.seconds)
        settle()
        return checkNotNull(detachedTimelineWindowRef.get())
    }

    /** Closes the detached Timeline Studio window if open. */
    suspend fun closeDetachedTimeline() {
        onUi { controller.detachedWindowVisible = false }
        automator.waitUntil(description = "detached timeline window closed", timeout = 5.seconds) {
            detachedTimelineWindowRef.get() == null
        }
        settle()
    }

    /** Opens the standalone Review sidecar window (`F4` mode) and returns its [ComposeWindow]. */
    suspend fun openReviewPanel(): ComposeWindow {
        onUi { reviewWindow.visible = true }
        automator.waitUntil(description = "review panel window showing", timeout = 10.seconds) {
            val w = reviewPanelWindowRef.get()
            w != null && w.isShowing && findSkiaLayer(w) != null
        }
        settle()
        return checkNotNull(reviewPanelWindowRef.get())
    }

    /** Opens the standalone Presenter View window (`F2` mode) and returns its [ComposeWindow]. */
    suspend fun openPresenterWindow(): ComposeWindow {
        onUi { presenterWindow.visible = true }
        automator.waitUntil(description = "presenter window showing", timeout = 10.seconds) {
            val w = presenterComposeWindowRef.get()
            w != null && w.isShowing && findSkiaLayer(w) != null
        }
        settle()
        return checkNotNull(presenterComposeWindowRef.get())
    }

    /** Closes the standalone Presenter View window if open. */
    suspend fun closePresenterWindow() {
        onUi { presenterWindow.visible = false }
        automator.waitUntil(description = "presenter window closed", timeout = 5.seconds) {
            presenterComposeWindowRef.get() == null
        }
        settle()
    }

    /** Scrubs to the settled rest beat `(sceneIndex, frameIndex)` and waits for the stage to match. */
    suspend fun restOnBeat(sceneIndex: Int, frameIndex: Int) {
        val beat = controller.snapshot.eventBeats.first {
            it.sceneIndex == sceneIndex && it.frameIndex == frameIndex
        }
        onUi { controller.scrubToTimeMs(beat.timeMs) }
        automator.waitUntil(
            description = "rest on S${sceneIndex + 1}:B${frameIndex + 1}",
            timeout = 5.seconds,
        ) {
            controller.animatic.activeFromStateIndex == beat.stateIndex &&
                controller.animatic.activeFraction == 0f &&
                controller.currentPlayheadTimeMs == beat.timeMs
        }
        settle()
    }

    /** Waits for the anchored review comment composer input to mount and receive focus. */
    suspend fun awaitComposerFocus() {
        automator.waitForNode(tag = "ciacola-review-note-input", timeout = 5.seconds)
        automator.waitUntil(description = "composer focused", timeout = 5.seconds) {
            automator.findOneByTestTag("ciacola-review-note-input")?.isFocused == true
        }
    }

    /** Finds a text node rendered inside the slide stage viewport (never a timeline label). */
    suspend fun stageText(
        text: String,
        exact: Boolean = true,
        timeout: Duration = 5.seconds,
    ): AutomatorNode {
        var found: AutomatorNode? = null
        automator.waitUntil(description = "stage text '$text'", timeout = timeout) {
            val stage = automator.findOneByTestTag("ciacola-preview-viewport")?.boundsOnScreen
            found = automator.findByText(text, exact).firstOrNull { node ->
                stage != null && stage.contains(node.centerOnScreen)
            }
            found != null
        }
        return checkNotNull(found)
    }

    // region Synthetic AWT input helpers

    /** Scrolls the mouse wheel at [screen] while holding [modifierKey] (e.g. `VK_ALT`, `VK_SHIFT`). */
    fun awtWheel(screen: Point, modifierKey: Int, rotation: Int) {
        input.mouseMove(screen.x, screen.y)
        input.keyPress(modifierKey)
        try {
            input.mouseWheel(rotation)
        } finally {
            input.keyRelease(modifierKey)
        }
    }

    /** Presses or releases [keyCode] directly via the synthetic adapter. */
    fun awtKey(pressed: Boolean, keyCode: Int) {
        if (pressed) input.keyPress(keyCode) else input.keyRelease(keyCode)
    }

    /** Holds [keyCode] while [block] runs and guarantees release afterwards. */
    suspend fun <T> withHeldKey(keyCode: Int, block: suspend () -> T): T {
        input.keyPress(keyCode)
        return try {
            delay(30)
            block()
        } finally {
            input.keyRelease(keyCode)
        }
    }

    /** Synthetic click at [at] with optional held [modifierKeys] (e.g. `KeyEvent.VK_CONTROL`). */
    suspend fun awtClick(
        at: Point,
        buttonMask: Int = InputEvent.BUTTON1_DOWN_MASK,
        modifierKeys: List<Int> = emptyList(),
    ) {
        input.mouseMove(at.x, at.y)
        delay(16)
        modifierKeys.forEach(input::keyPress)
        try {
            input.mousePress(buttonMask)
            delay(16)
            input.mouseRelease(buttonMask)
        } finally {
            modifierKeys.asReversed().forEach(input::keyRelease)
        }
        delay(24)
    }

    /** Synthetic drag from [from] to [to] with [buttonMask] and optional held [modifierKeys]. */
    suspend fun awtDrag(
        from: Point,
        to: Point,
        buttonMask: Int = InputEvent.BUTTON1_DOWN_MASK,
        steps: Int = 10,
        stepDelayMs: Long = 16L,
        modifierKeys: List<Int> = emptyList(),
    ) {
        input.mouseMove(from.x, from.y)
        delay(20)
        modifierKeys.forEach(input::keyPress)
        try {
            input.mousePress(buttonMask)
            delay(20)
            for (i in 1..steps) {
                input.mouseMove(
                    from.x + (to.x - from.x) * i / steps,
                    from.y + (to.y - from.y) * i / steps,
                )
                delay(stepDelayMs)
            }
            input.mouseRelease(buttonMask)
        } finally {
            modifierKeys.asReversed().forEach(input::keyRelease)
        }
        delay(30)
    }

    // endregion

    // region Window-only QA evidence & findings

    /** Captures a crash-safe, window-only [SkiaLayer] screenshot of [targetWindow]. */
    suspend fun screenshot(
        targetWindow: ComposeWindow = window,
        settleDelayMs: Long = 60L,
    ): BufferedImage = dev.sebastiano.ciacola.qa.screenshot(
        window = targetWindow,
        settleDelayMs = settleDelayMs,
    )

    /** Saves a window-only PNG screenshot to [dir] (default `$QA/evidence/<worker>/`). */
    suspend fun saveScreenshot(
        name: String,
        targetWindow: ComposeWindow = window,
        dir: File = evidenceDir,
        settleDelayMs: Long = 60L,
    ): File = dev.sebastiano.ciacola.qa.saveScreenshot(
        window = targetWindow,
        name = name,
        evidenceDir = dir,
        settleDelayMs = settleDelayMs,
    )

    /** Returns the `$QA`-relative evidence string (`evidence/<worker>/...`) for [file]. */
    fun evidencePath(file: File): String =
        QaPaths.toEvidenceRelativePath(file = file, worker = worker, qaRoot = qaRoot)

    /** Writes a legacy Spectre capture PNG when `CIACOLA_SPECTRE_CAPTURE_DIR` is configured. */
    suspend fun capture(name: String, targetWindow: ComposeWindow = window) {
        val dir = captureDir ?: return
        dir.mkdirs()
        val img = screenshot(targetWindow = targetWindow)
        ImageIO.write(img, "png", File(dir, name))
    }

    /**
     * Records [targetWindow]'s [SkiaLayer] in the background while [block] runs and encodes
     * `.mp4` (H.264 `yuv420p`) + `.gif` (`<= maxGifWidthPx` wide) into [dir].
     */
    suspend fun record(
        name: String,
        fps: Int = 20,
        targetWindow: ComposeWindow = window,
        dir: File = evidenceDir,
        maxGifWidthPx: Int = 960,
        block: suspend () -> Unit,
    ): QaRecording = dev.sebastiano.ciacola.qa.record(
        window = targetWindow,
        name = name,
        fps = fps,
        evidenceDir = dir,
        worker = worker,
        qaRoot = qaRoot,
        maxGifWidthPx = maxGifWidthPx,
        block = block,
    )

    /** Appends a schema-validated JSON finding line to [findingsFile]. */
    fun finding(
        title: String,
        area: String,
        type: String,
        severity: String,
        steps: List<String>,
        expected: String,
        actual: String,
        evidence: List<String> = emptyList(),
        repro: String = QaRepro.Always.wireName,
        confidence: String = QaConfidence.High.wireName,
        notes: String = "",
        id: String? = null,
        seeAlso: List<String>? = null,
    ): QaFinding = dev.sebastiano.ciacola.qa.finding(
        title = title,
        area = area,
        type = type,
        severity = severity,
        steps = steps,
        expected = expected,
        actual = actual,
        evidence = evidence,
        repro = repro,
        confidence = confidence,
        notes = notes,
        id = id,
        seeAlso = seeAlso,
        worker = worker,
        qaRoot = qaRoot,
        findingsFile = findingsFile,
    )

    /** Type-safe enum overload of [finding]. */
    fun finding(
        title: String,
        area: QaArea,
        type: QaFindingType,
        severity: QaSeverity,
        steps: List<String>,
        expected: String,
        actual: String,
        evidence: List<String> = emptyList(),
        repro: QaRepro = QaRepro.Always,
        confidence: QaConfidence = QaConfidence.High,
        notes: String = "",
        id: String? = null,
        seeAlso: List<String>? = null,
    ): QaFinding = dev.sebastiano.ciacola.qa.finding(
        title = title,
        area = area,
        type = type,
        severity = severity,
        steps = steps,
        expected = expected,
        actual = actual,
        evidence = evidence,
        repro = repro,
        confidence = confidence,
        notes = notes,
        id = id,
        seeAlso = seeAlso,
        worker = worker,
        qaRoot = qaRoot,
        findingsFile = findingsFile,
    )

    // endregion

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { controller.stopPlayback() }
        runCatching { designStore.close() }
        runCatching { elementStore.close() }
        uiScopeRef.set(null)
        val exitApp = exitAppRef.getAndSet(null)
        val detached = detachedTimelineWindowRef.getAndSet(null)
        val reviewWin = reviewPanelWindowRef.getAndSet(null)
        val presenterWin = presenterComposeWindowRef.getAndSet(null)
        val story = storyWindowRef.getAndSet(null)
        val disposeAll = {
            runCatching { exitApp?.invoke() }
            runCatching { detached?.isVisible = false; detached?.dispose() }
            runCatching { reviewWin?.isVisible = false; reviewWin?.dispose() }
            runCatching { presenterWin?.isVisible = false; presenterWin?.dispose() }
            runCatching { story?.isVisible = false; story?.dispose() }
        }
        if (EventQueue.isDispatchThread()) {
            disposeAll()
        } else {
            runCatching { EventQueue.invokeAndWait { disposeAll() } }
        }
        appThread.join(3_000)
        runCatching { workspaceRoot.toFile().deleteRecursively() }
    }
}

internal fun defaultSpectreCaptureDir(): File? =
    System.getenv("CIACOLA_SPECTRE_CAPTURE_DIR")
        ?.takeIf { it.isNotBlank() }
        ?.let(::File)
        ?.apply { mkdirs() }

/**
 * Launches a real-window Ciacola Studio session configured not to request OS focus or pop in front
 * of the active macOS application.
 */
fun launchStudio(
    automator: ComposeAutomator,
    preset: DeckThemePreset = DeckThemePreset.StageDark,
    windowSize: DpSize = DpSize(1360.dp, 900.dp),
    windowPosition: WindowPosition = WindowPosition(80.dp, 60.dp),
    timelineDocked: Boolean = false,
    timelineDetached: Boolean = false,
    detachedWindowSize: DpSize = DpSize(1180.dp, 460.dp),
    detachedWindowPosition: WindowPosition = WindowPosition(120.dp, 520.dp),
    includeKeynoteEffectsLab: Boolean = true,
    includeOverlayNavigation: Boolean = false,
    deckId: String? = null,
    storyboardFactory: QaStoryboardFactory? = null,
    worker: String? = null,
    qaRoot: File = QaPaths.qaRoot(),
    evidenceDir: File? = null,
    findingsFile: File? = null,
    captureDir: File? = defaultSpectreCaptureDir(),
): QaStudio {
    val resolvedWorker = QaPaths.resolveWorker(worker)
    val resolvedEvidenceDir = evidenceDir ?: QaPaths.evidenceDir(worker = resolvedWorker, qaRoot = qaRoot)
    val resolvedFindingsFile = findingsFile ?: QaPaths.findingsFile(worker = resolvedWorker, qaRoot = qaRoot)

    val tempRoot = Files.createTempDirectory("ciacola-qa-studio-")
    val storyboardDir = tempRoot.resolve(".storyboard").also { Files.createDirectories(it) }

    val readyLatch = CountDownLatch(1)
    val detachedReadyLatch = CountDownLatch(if (timelineDetached) 1 else 0)

    val uiScopeRef = AtomicReference<CoroutineScope?>(null)
    val storyWindowRef = AtomicReference<ComposeWindow?>(null)
    val detachedTimelineWindowRef = AtomicReference<ComposeWindow?>(null)
    val reviewPanelWindowRef = AtomicReference<ComposeWindow?>(null)
    val presenterComposeWindowRef = AtomicReference<ComposeWindow?>(null)
    val exitAppRef = AtomicReference<(() -> Unit)?>(null)

    val controllerRef = AtomicReference<TimelineStudioController?>(null)
    val reviewRef = AtomicReference<ReviewStageCoordinator?>(null)
    val timelineWindowRef = AtomicReference<TimelineStudioWindow?>(null)
    val reviewWindowRef = AtomicReference<SlideReviewWindow?>(null)
    val animaticRef = AtomicReference<Animatic?>(null)
    val loopClockRef = AtomicReference<DeckLoopClock?>(null)
    val motionPathRef = AtomicReference<MotionPathPreviewState?>(null)
    val stageSelectionRef = AtomicReference<TimelineStageSelection?>(null)
    val inspectionRegistryRef = AtomicReference<StageInspectionRegistry?>(null)
    val elementControllerRef = AtomicReference<ElementEditorController?>(null)
    val lintControllerRef = AtomicReference<DeckLintController?>(null)
    val workspaceRef = AtomicReference<StudioWorkspaceController?>(null)
    val presenterWindowRef = AtomicReference<PresenterWindow?>(null)
    val textInputTrackerRef = AtomicReference<AuthoringTextInputFocusTracker?>(null)
    val overridesStoreRef = AtomicReference<TimelineOverridesStore?>(null)
    val elementStoreRef = AtomicReference<ElementOverridesStore?>(null)
    val reviewStoreRef = AtomicReference<ReviewCommentStore?>(null)
    val presetHolder = AtomicReference(preset)
    val presetSetterRef = AtomicReference<((DeckThemePreset) -> Unit)?>(null)
    val initialDeckId = resolveInitialDeckId(
        rootStoryboardDir = storyboardDir,
        systemPropertyOverride = deckId ?: System.getProperty("ciacola.deck"),
    )
    val activeDeckHolder = AtomicReference(DeckCatalog.byId(initialDeckId))
    val deckSwitcherRef = AtomicReference<((DeckEntry) -> Unit)?>(null)

    val designStore = DesignSystemStore(storyboardDir = storyboardDir)

    val hostThread = thread(name = "ciacola-qa-studio-host", isDaemon = true) {
        application(exitProcessOnExit = false) {
            exitAppRef.set(::exitApplication)
            val appScope = rememberCoroutineScope()
            var activePreset by remember { mutableStateOf(preset) }
            presetHolder.set(activePreset)
            presetSetterRef.set { next ->
                presetHolder.set(next)
                activePreset = next
            }
            var activeDeckId by remember { mutableStateOf(initialDeckId) }
            val activeDeck = remember(activeDeckId) { DeckCatalog.byId(activeDeckId) }
            activeDeckHolder.set(activeDeck)
            val deckStoryboardDir = remember(storyboardDir, activeDeck.id) {
                storyboardDirForDeck(storyboardDir, activeDeck.id)
            }

            val overridesStore = remember(deckStoryboardDir) {
                TimelineOverridesStore(storyboardDir = deckStoryboardDir)
            }.also(overridesStoreRef::set)
            val elementStore = remember(deckStoryboardDir) {
                ElementOverridesStore(storyboardDir = deckStoryboardDir)
            }.also(elementStoreRef::set)
            val reviewStore = remember(deckStoryboardDir, tempRoot) {
                ReviewCommentStore(storyboardDir = deckStoryboardDir, workspaceRoot = tempRoot)
            }.also(reviewStoreRef::set)
            DisposableEffect(elementStore) {
                onDispose { elementStore.close() }
            }

            val textInputTracker = remember { AuthoringTextInputFocusTracker() }.also(textInputTrackerRef::set)
            val stageInspectionRegistry = remember(activeDeck.id) { StageInspectionRegistry() }.also(inspectionRegistryRef::set)
            val stageInteractiveRegistry = remember(activeDeck.id) { StageInteractiveRegistry() }
            val elementController = rememberElementEditorController(
                overridesStore = elementStore,
                inspectionRegistry = stageInspectionRegistry,
                isStageSettlingProvider = { animaticRef.get()?.scrubSettling == true },
            ).also(elementControllerRef::set)
            val lintController = remember(activeDeck.id) { DeckLintController() }.also(lintControllerRef::set)

            val designRev = designStore.revision
            val designOverrides = remember(activePreset, designRev) { designStore.overridesFor(activePreset) }
            val baseHostTheme = remember(activePreset) { DeckThemes.forPreset(activePreset) }
            val hostTheme = remember(baseHostTheme, designOverrides) {
                baseHostTheme.withOverrides(designOverrides)
            }
            val materialColors = remember(hostTheme) { hostTheme.toMaterialColors() }
            val materialTypography = remember(hostTheme) { hostTheme.toMaterialTypography() }
            val loopClock = remember(activeDeck.id) { DeckLoopClock() }.also(loopClockRef::set)
            val motionPathPreview = remember { MotionPathPreviewState() }.also(motionPathRef::set)
            val stageSelection = remember { TimelineStageSelection() }.also(stageSelectionRef::set)
            val reviewStageCoordinator = rememberReviewStageCoordinator().also(reviewRef::set)

            // Match Main.kt root revision reads so QaStudio exercises identical root recompositions.
            val elementRev = elementStore.revision
            val structuralRev = overridesStore.structuralRevision
            val paramRev = overridesStore.parameterRevision
            check(elementRev >= 0 && paramRev >= 0)

            var workspaceHolder by remember { mutableStateOf<StudioWorkspaceController?>(null) }

            val presetRegistry = remember { dev.sebastiano.ciacola.StoryboardPresetRegistry() }
            val timelineStageDecorator = rememberTimelineStageDecorator(
                loopClock = loopClock,
                motionPathPreview = motionPathPreview,
                overridesStore = overridesStore,
                stageSelection = stageSelection,
            )
            val lintStageDecorator = rememberDeckLintStageDecorator(
                controller = lintController,
                isInspectionVisible = { workspaceHolder?.isLinterVisible == true },
            )
            val storyboard = rememberHotReloadStoryboard(
                activeDeck.id,
                activePreset,
                reviewStageCoordinator,
                structuralRev,
            ) {
                val combinedStageDecorator = timelineStageDecorator +
                    reviewStageCoordinator.stageDecorator +
                    elementController.stageDecorator +
                    lintStageDecorator
                val timelineProvider = {
                    overridesStore.parameterRevision
                    overridesStore.structuralRevision
                    overridesStore.data()
                }
                val elementProvider = {
                    elementStore.revision
                    elementStore.data()
                }
                val designProvider = {
                    designStore.revision
                    designStore.overridesFor(activePreset)
                }
                val built = if (storyboardFactory != null) {
                    storyboardFactory(
                        QaStoryboardRequest(
                            theme = baseHostTheme,
                            bundledPreset = activePreset,
                            includeKeynoteEffectsLab = includeKeynoteEffectsLab,
                            stageDecorator = combinedStageDecorator,
                            timelineOverrides = overridesStore.data(),
                            elementOverrides = elementStore.data(),
                            designOverrides = designOverrides,
                            timelineOverridesProvider = timelineProvider,
                            elementOverridesProvider = elementProvider,
                            designOverridesProvider = designProvider,
                        ),
                    )
                } else if (activeDeck.id == DeckCatalog.FOUNDATION_ID) {
                    createFoundationDeck(
                        theme = baseHostTheme,
                        bundledPreset = activePreset,
                        includeKeynoteEffectsLab = includeKeynoteEffectsLab,
                        stageDecorator = combinedStageDecorator,
                        timelineOverrides = overridesStore.data(),
                        timelineOverridesProvider = timelineProvider,
                        elementOverridesProvider = elementProvider,
                        designOverridesProvider = designProvider,
                    )
                } else {
                    activeDeck.create(
                        DeckHostParams(
                            theme = baseHostTheme,
                            bundledPreset = activePreset,
                            stageDecorator = combinedStageDecorator,
                            timelineOverrides = overridesStore.data(),
                            timelineOverridesProvider = timelineProvider,
                            elementOverridesProvider = elementProvider,
                            designOverridesProvider = designProvider,
                        ),
                    )
                }
                built.also { presetRegistry.record(it, activePreset) }
            }
            val buildAuditStoryboardForPreset: (DeckThemePreset) -> Storyboard = remember(
                activeDeck,
                overridesStore,
                elementStore,
                designStore,
                storyboardFactory,
            ) {
                { targetPreset ->
                    val targetTheme = DeckThemes.forPreset(targetPreset)
                    val targetDesignOverrides = designStore.overridesFor(targetPreset)
                    if (storyboardFactory != null) {
                        val tl = overridesStore.data()
                        val el = elementStore.data()
                        storyboardFactory(
                            QaStoryboardRequest(
                                theme = targetTheme,
                                bundledPreset = targetPreset,
                                includeKeynoteEffectsLab = includeKeynoteEffectsLab,
                                stageDecorator = ContentDecorator.None,
                                timelineOverrides = tl,
                                elementOverrides = el,
                                designOverrides = targetDesignOverrides,
                                timelineOverridesProvider = { tl },
                                elementOverridesProvider = { el },
                                designOverridesProvider = { targetDesignOverrides },
                            ),
                        )
                    } else if (activeDeck.id == DeckCatalog.FOUNDATION_ID) {
                        createFoundationDeck(
                            theme = targetTheme,
                            bundledPreset = targetPreset,
                            includeKeynoteEffectsLab = includeKeynoteEffectsLab,
                            timelineOverrides = overridesStore.data(),
                            elementOverrides = elementStore.data(),
                            designOverrides = targetDesignOverrides,
                        )
                    } else {
                        activeDeck.create(
                            DeckHostParams(
                                theme = targetTheme,
                                bundledPreset = targetPreset,
                                timelineOverrides = overridesStore.data(),
                                elementOverrides = elementStore.data(),
                                designOverrides = targetDesignOverrides,
                            ),
                        )
                    }
                }
            }
            val animatic = key(activeDeck.id) {
                rememberAnimatic(
                    initialIndex = Storyboard.Index(0, 0),
                    storyboard = { storyboard },
                )
            }.also(animaticRef::set)
            val timelineController = rememberTimelineStudioController(
                animatic = animatic,
                overridesStore = overridesStore,
                loopClock = loopClock,
                bakeDispatcher = reviewStore.asBakeDispatcher(),
                bakeWorkspaceRoot = reviewStore.workspaceRoot,
                bakeGradleTaskHint = reviewStore.config.gradleTaskHint,
                motionPathPreview = motionPathPreview,
                stageSelection = stageSelection,
                externalTimingKey = designOverrides.motion,
                externalInEditGesture = designStore.isInEditGesture,
            ).also { ctrl ->
                if (controllerRef.compareAndSet(null, ctrl)) {
                    ctrl.dockedInStoryWindow = timelineDocked
                    ctrl.detachedWindowVisible = timelineDetached
                }
            }
            val studioWorkspace = remember(timelineController, designStore) {
                StudioWorkspaceController(
                    timelineController = timelineController,
                    timelineOverridesStore = overridesStore,
                    designSystemStore = designStore,
                    elementOverridesStore = elementStore,
                    elementController = elementController,
                    lintController = lintController,
                    stageInspectionRegistry = stageInspectionRegistry,
                    initialInspectorWidthDp = loadPersistedInspectorWidthDp(storyboardDir),
                )
            }.also {
                it.updateForDeckSwitch(
                    newTimelineOverridesStore = overridesStore,
                    newElementOverridesStore = elementStore,
                    newElementController = elementController,
                    newLintController = lintController,
                    newStageInspectionRegistry = stageInspectionRegistry,
                )
                it.onInspectorWidthPersisted = { widthDp ->
                    persistInspectorWidthDp(storyboardDir, widthDp)
                }
                workspaceHolder = it
                workspaceRef.set(it)
            }

            fun switchToDeck(target: DeckEntry) {
                if (target.id == activeDeckId) return
                elementController.commitInlineEdit(restoreStageFocus = false)
                elementController.selectElement(null)
                timelineController.exitLoopSubComp()
                timelineController.stopPlayback()
                timelineController.selectTrack(null)
                reviewStageCoordinator.dismissActivePopups()
                persistActiveDeckId(storyboardDir, target.id)
                activeDeckHolder.set(target)
                activeDeckId = target.id
            }
            deckSwitcherRef.set(::switchToDeck)

            var presenterWinHolder by remember { mutableStateOf<PresenterWindow?>(null) }

            val isAnyTextInputActive = {
                textInputTracker.hasFocusedTextInput ||
                    elementController.inlineEditingElementId != null ||
                    reviewStageCoordinator.pendingSelection != null ||
                    reviewStageCoordinator.selectedPinCommentId != null ||
                    presenterWinHolder?.controller?.jumpDialogOpen == true
            }
            studioWorkspace.isTextInputActiveProvider = isAnyTextInputActive
            studioWorkspace.onTogglePresenter = {
                presenterWinHolder?.let { it.visible = !it.visible }
            }
            studioWorkspace.onToggleDockedTimeline = {
                timelineController.dockedInStoryWindow = !timelineController.dockedInStoryWindow
            }
            studioWorkspace.onToggleDetachedTimeline = {
                val next = !timelineController.detachedWindowVisible
                if (next) timelineController.dockedInStoryWindow = false
                timelineController.detachedWindowVisible = next
            }
            studioWorkspace.onSelectDeckByIndex = { idx ->
                DeckCatalog.entries.getOrNull(idx)?.let(::switchToDeck)
            }

            val reviewOwnsStageClick = {
                reviewStageCoordinator.inspectModeEnabled ||
                    reviewStageCoordinator.pendingSelection != null ||
                    reviewStageCoordinator.selectedPinCommentId != null
            }
            stageSelection.pickingSuppressed = {
                reviewOwnsStageClick() ||
                    timelineController.spaceHeld ||
                    elementController.inlineEditingElementId != null ||
                    (studioWorkspace.isStudioOpen && studioWorkspace.activeTab == StudioInspectorTab.Element)
            }

            remember(elementController, reviewStageCoordinator) {
                elementController.onReturnFocusToStage = {
                    runCatching { reviewStageCoordinator.stageFocusRequester.requestFocus() }
                }
            }

            SideEffect {
                elementController.suppressPicking = {
                    reviewOwnsStageClick() || timelineController.spaceHeld
                }
                elementController.isStagePointOverHud = reviewStageCoordinator::isStagePointOverHud
                elementController.isExternalTextInputActive = isAnyTextInputActive
                elementController.currentSceneIndexProvider = { animatic.presentedIndex.sceneIndex }
                elementController.currentBeatIndexProvider = { animatic.presentedIndex.frameIndex }
                elementController.isStageSettlingProvider = { animatic.scrubSettling }
                elementController.isDarkThemeProvider = { activePreset == DeckThemePreset.StageDark }
            }
            StudioWorkspaceSyncEffects(studioWorkspace)

            val presenterWindow = rememberPresenterWindow(
                animatic = animatic,
                currentPresetProvider = {
                    presetRegistry.presetFor(presenterWinHolder?.controller?.animatic?.storyboard, fallback = activePreset)
                },
                isStudioOpenProvider = { studioWorkspace.isAnyAuthoringToolOpen },
                isInspectOpenProvider = { reviewStageCoordinator.inspectModeEnabled },
                hasUnbakedOverridesProvider = {
                    overridesStore.structuralRevision
                    overridesStore.parameterRevision
                    elementStore.revision
                    designStore.revision
                    overridesStore.data().hasOverrides ||
                        elementStore.data().hasOverrides ||
                        designStore.data().hasOverrides
                },
                onEnterCleanShowMode = {
                    timelineController.dockedInStoryWindow = false
                    timelineController.detachedWindowVisible = false
                    elementController.cancelInlineEdit(restoreStageFocus = false)
                    elementController.selectElement(null)
                    reviewStageCoordinator.inspectModeEnabled = false
                    reviewStageCoordinator.dismissActivePopups()
                },
                isExternalTextInputActiveProvider = isAnyTextInputActive,
                onRestoreStageFocus = {
                    runCatching { reviewStageCoordinator.stageFocusRequester.requestFocus() }.isSuccess
                },
                extraPreviewKeyHandler = { ev, _ ->
                    studioWorkspace.handleStudioPreviewKey(ev)
                },
            ).also {
                presenterWinHolder = it
                presenterWindowRef.set(it)
            }

            val studioWindow = rememberTimelineStudioWindow(
                controller = timelineController,
                currentPresetProvider = {
                    presetRegistry.presetFor(timelineController.animatic.storyboard, fallback = activePreset)
                },
                suppressShortcutsProvider = isAnyTextInputActive,
                onReturnFocusToStage = {
                    runCatching { reviewStageCoordinator.stageFocusRequester.requestFocus() }
                },
                trailingPane = {
                    StudioUnifiedInspectorPane(
                        workspace = studioWorkspace,
                        animatic = animatic,
                        currentPreset = activePreset,
                        onSelectPreset = { next ->
                            presetHolder.set(next)
                            activePreset = next
                        },
                        baseThemeProvider = { DeckThemes.forPreset(it) },
                        storyboardForPreset = buildAuditStoryboardForPreset,
                        scope = appScope,
                    )
                },
                leadingToolbarContent = {
                    DeckSwitcherDropdown(
                        activeDeck = activeDeck,
                        onSelectDeck = ::switchToDeck,
                    )
                },
                extraPreviewKeyHandler = { ev, _ ->
                    studioWorkspace.handleStudioPreviewKey(ev)
                },
            ).also(timelineWindowRef::set)
            var reviewWinHolder by remember { mutableStateOf<SlideReviewWindow?>(null) }
            val prevReviewVisible = reviewWinHolder?.visible == true
            val slideReviewWindow = rememberSlideReviewWindow(
                animatic = animatic,
                store = reviewStore,
                stageCoordinator = reviewStageCoordinator,
                currentPresetProvider = {
                    presetRegistry.presetFor(reviewWinHolder?.animatic?.storyboard, fallback = activePreset)
                },
                timelineDockedProvider = { timelineController.dockedInStoryWindow },
                onToggleTimeline = {
                    timelineController.dockedInStoryWindow = !timelineController.dockedInStoryWindow
                },
            ).also {
                if (reviewWinHolder != null && reviewWinHolder !== it) {
                    it.visible = prevReviewVisible
                }
                reviewWinHolder = it
                reviewWindowRef.set(it)
            }
            val deckMenuWindow = rememberDeckMenuWindow(
                activeDeckProvider = { activeDeck },
                onSelectDeck = ::switchToDeck,
                animaticProvider = { animatic },
                isTextInputActiveProvider = isAnyTextInputActive,
                isStudioOpenProvider = { studioWorkspace.isStudioOpen },
                onBeforeNavigate = { presenterWinHolder?.controller?.clearCurtain() },
                extraPreviewKeyHandler = { ev -> workspaceHolder?.handleStudioPreviewKey(ev) == true },
            )

            // DeckMenuWindow sits outermost as window-level key capture + focus guard; Studio wraps
            // Review so the Stage Review HUD sits inside the Stage pane to the left of the Inspector.
            val fullDecorator = deckMenuWindow.decorator +
                presenterWindow.decorator +
                studioWindow.decorator +
                slideReviewWindow.decorator

            CompositionLocalProvider(
                LocalAuthoringTextInputTracker provides textInputTracker,
                LocalStageInteractiveRegistry provides stageInteractiveRegistry,
            ) {
                MaterialTheme(colors = materialColors, typography = materialTypography) {
                    Window(
                        onCloseRequest = ::exitApplication,
                        title = "${animatic.storyboard.title} - Story",
                        state = rememberWindowState(position = windowPosition, size = windowSize),
                    ) {
                        window.isAutoRequestFocus = false
                        uiScopeRef.set(rememberCoroutineScope())
                        if (storyWindowRef.compareAndSet(null, window)) {
                            readyLatch.countDown()
                        }
                        val overviewVisibility = rememberOverviewVisibility()
                        CompositionLocalProvider(
                            LocalOverviewOpen provides overviewVisibility.isVisible,
                        ) {
                            Box(Modifier.fillMaxSize()) {
                                fullDecorator.decorate {
                                    EaselOverview(animatic, visibility = overviewVisibility) {
                                        if (includeOverlayNavigation) {
                                            EaselOverlay(overlay = { OverlayNavigation(animatic) }) {
                                                Easel(animatic)
                                            }
                                        } else {
                                            Easel(animatic)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (studioWindow.visible) {
                        Window(
                            onCloseRequest = { studioWindow.visible = false },
                            title = "Ciacola - Timeline",
                            state = rememberWindowState(
                                position = detachedWindowPosition,
                                size = detachedWindowSize,
                            ),
                        ) {
                            window.isAutoRequestFocus = false
                            DisposableEffect(window) {
                                detachedTimelineWindowRef.set(window)
                                detachedReadyLatch.countDown()
                                onDispose { detachedTimelineWindowRef.compareAndSet(window, null) }
                            }
                            studioWindow.Content()
                        }
                    }

                    if (slideReviewWindow.visible) {
                        Window(
                            onCloseRequest = { slideReviewWindow.visible = false },
                            title = "Ciacola - Review",
                            state = rememberWindowState(
                                position = WindowPosition(940.dp, 80.dp),
                                size = DpSize(460.dp, 680.dp),
                            ),
                        ) {
                            window.isAutoRequestFocus = false
                            DisposableEffect(window) {
                                reviewPanelWindowRef.set(window)
                                onDispose { reviewPanelWindowRef.compareAndSet(window, null) }
                            }
                            slideReviewWindow.Content()
                        }
                    }

                    if (presenterWindow.visible) {
                        Window(
                            onCloseRequest = { presenterWindow.visible = false },
                            title = "Ciacola - Presenter",
                            state = rememberWindowState(
                                position = WindowPosition(140.dp, 90.dp),
                                size = DpSize(1120.dp, 680.dp),
                            ),
                        ) {
                            window.isAutoRequestFocus = false
                            DisposableEffect(window) {
                                presenterComposeWindowRef.set(window)
                                onDispose { presenterComposeWindowRef.compareAndSet(window, null) }
                            }
                            presenterWindow.Content()
                        }
                    }
                }
            }
        }
    }

    check(readyLatch.await(15, TimeUnit.SECONDS)) { "Timed out waiting for Ciacola Story ComposeWindow" }
    if (timelineDetached) {
        check(detachedReadyLatch.await(10, TimeUnit.SECONDS)) {
            "Timed out waiting for detached Timeline ComposeWindow"
        }
    }

    return QaStudio(
        automator = automator,
        window = checkNotNull(storyWindowRef.get()),
        controller = checkNotNull(controllerRef.get()),
        overridesStoreRef = overridesStoreRef,
        designStore = designStore,
        elementStoreRef = elementStoreRef,
        stageInspectionRegistryRef = inspectionRegistryRef,
        elementControllerRef = elementControllerRef,
        lintControllerRef = lintControllerRef,
        studioWorkspace = checkNotNull(workspaceRef.get()),
        presenterWindow = checkNotNull(presenterWindowRef.get()),
        presenter = checkNotNull(presenterWindowRef.get()).controller,
        review = checkNotNull(reviewRef.get()),
        reviewStoreRef = reviewStoreRef,
        timelineWindow = checkNotNull(timelineWindowRef.get()),
        reviewWindowRef = reviewWindowRef,
        animaticRef = animaticRef,
        loopClockRef = loopClockRef,
        motionPathPreview = checkNotNull(motionPathRef.get()),
        stageSelection = checkNotNull(stageSelectionRef.get()),
        textInputTracker = checkNotNull(textInputTrackerRef.get()),
        workspaceRoot = tempRoot,
        storyboardDir = storyboardDir,
        worker = resolvedWorker,
        qaRoot = qaRoot,
        evidenceDir = resolvedEvidenceDir,
        findingsFile = resolvedFindingsFile,
        captureDir = captureDir,
        uiScopeRef = uiScopeRef,
        storyWindowRef = storyWindowRef,
        detachedTimelineWindowRef = detachedTimelineWindowRef,
        reviewPanelWindowRef = reviewPanelWindowRef,
        presenterComposeWindowRef = presenterComposeWindowRef,
        presetReader = presetHolder::get,
        presetUpdater = { next -> presetSetterRef.get()?.invoke(next) },
        activeDeckReader = activeDeckHolder::get,
        deckSwitcher = { entry -> deckSwitcherRef.get()?.invoke(entry) },
        exitAppRef = exitAppRef,
        appThread = hostThread,
        deckId = deckId,
    )
}
