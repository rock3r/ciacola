package dev.sebastiano.ciacola.qa

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntSize
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
import dev.bnorm.storyboard.easel.overview.LocalOverviewChromeStyle
import dev.bnorm.storyboard.easel.overview.LocalOverviewSlideTitleProvider
import dev.bnorm.storyboard.easel.overview.LocalOverviewThumbnailFrameProvider
import dev.bnorm.storyboard.easel.overview.rememberOverviewVisibility
import dev.bnorm.storyboard.easel.rememberAnimatic
import dev.bnorm.storyboard.plus
import dev.sebastiano.ciacola.DeckCatalog
import dev.sebastiano.ciacola.DeckEntry
import dev.sebastiano.ciacola.DeckTextRendererProvider
import dev.sebastiano.ciacola.decks.DeckSource
import dev.sebastiano.ciacola.decks.loadClasspathDeckDocument
import dev.sebastiano.ciacola.decks.resolveDeckSource
import dev.sebastiano.ciacola.discoverEntries
import dev.sebastiano.ciacola.document.DeckDecodeResult
import dev.sebastiano.ciacola.document.DeckDocument
import dev.sebastiano.ciacola.document.DeckDocumentCodec
import dev.sebastiano.ciacola.document.DeckDocumentStore
import dev.sebastiano.ciacola.document.DeckFileSync
import dev.sebastiano.ciacola.document.DeckLoadState
import dev.sebastiano.ciacola.document.DeckMigrations
import dev.sebastiano.ciacola.document.DeckSpecimenRegistry
import dev.sebastiano.ciacola.document.RenderSession
import dev.sebastiano.ciacola.inspector.sync.DocumentSyncController
import dev.sebastiano.ciacola.document.render.DefaultMagicTextV2Renderer
import dev.sebastiano.ciacola.document.render.DocumentKeyboardCaptureController
import dev.sebastiano.ciacola.document.render.DocumentRenderConfig
import dev.sebastiano.ciacola.document.render.rememberDocumentStoryboard
import dev.sebastiano.ciacola.inspector.LocalStageDragPreview
import dev.sebastiano.ciacola.inspector.LocalStageInspectionRegistry
import dev.sebastiano.ciacola.inspector.LocalStageInteractiveRegistry
import dev.sebastiano.ciacola.inspector.StageDragPreview
import dev.sebastiano.ciacola.inspector.StageInspectionRegistry
import dev.sebastiano.ciacola.inspector.StageInteractiveRegistry
import dev.sebastiano.ciacola.inspector.design.DocumentThemeInspectorController
import dev.sebastiano.ciacola.inspector.element.DocumentElementInspectorController
import dev.sebastiano.ciacola.inspector.element.DocumentStageDirectEditOverlay
import dev.sebastiano.ciacola.inspector.lint.DocumentDeckLintController
import dev.sebastiano.ciacola.inspector.lint.DocumentDeckLintStageOverlay
import dev.sebastiano.ciacola.presenter.PresenterController
import dev.sebastiano.ciacola.presenter.PresenterWindow
import dev.sebastiano.ciacola.presenter.rememberPresenterWindow
import dev.sebastiano.ciacola.review.ReviewCommentStore
import dev.sebastiano.ciacola.review.ReviewStageCoordinator
import dev.sebastiano.ciacola.review.SlideReviewWindow
import dev.sebastiano.ciacola.review.rememberReviewStageCoordinator
import dev.sebastiano.ciacola.review.rememberSlideReviewWindow
import dev.sebastiano.ciacola.studio.DeckSwitcherDropdown
import dev.sebastiano.ciacola.studio.DocumentSpeakerNotesMemo
import dev.sebastiano.ciacola.studio.StageShortcutToastOverlay
import dev.sebastiano.ciacola.studio.StudioAppBarHost
import dev.sebastiano.ciacola.studio.StudioBottomDockTabStrip
import dev.sebastiano.ciacola.studio.StudioBottomIssuesDockBody
import dev.sebastiano.ciacola.studio.StudioBottomIssuesDockHeaderTrailing
import dev.sebastiano.ciacola.studio.StudioIssuesDock
import dev.sebastiano.ciacola.studio.StudioMode
import dev.sebastiano.ciacola.studio.StudioThemeSheet
import dev.sebastiano.ciacola.studio.StudioUnifiedInspectorPane
import dev.sebastiano.ciacola.studio.StudioWorkspaceController
import dev.sebastiano.ciacola.studio.StudioWorkspaceSyncEffects
import dev.sebastiano.ciacola.studio.buildPerThumbnailOverviewStoryboard
import dev.sebastiano.ciacola.studio.formatStudioBreadcrumb
import dev.sebastiano.ciacola.studio.loadPersistedInspectorWidthDp
import dev.sebastiano.ciacola.studio.persistActiveDeckId
import dev.sebastiano.ciacola.studio.persistInspectorWidthDp
import dev.sebastiano.ciacola.studio.rememberDeckMenuWindow
import dev.sebastiano.ciacola.studio.rememberOverviewSlideProviders
import dev.sebastiano.ciacola.studio.rememberStudioOverviewChromeStyle
import dev.sebastiano.ciacola.studio.rememberStudioShellWindow
import dev.sebastiano.ciacola.studio.resolveInitialDeckId
import dev.sebastiano.ciacola.studio.saveSpeakerNote
import dev.sebastiano.ciacola.studio.storyboardDirForDeck
import dev.sebastiano.ciacola.theme.DeckThemePreset
import dev.sebastiano.ciacola.theme.DeckThemes
import dev.sebastiano.ciacola.timeline.DocumentTimelineController
import dev.sebastiano.ciacola.timeline.DocumentTimelineStageOverlay
import dev.sebastiano.ciacola.timeline.DocumentTimelineWindow
import dev.sebastiano.ciacola.timeline.TimelineSurface
import dev.sebastiano.ciacola.timeline.rememberDocumentTimelineWindow
import dev.sebastiano.ciacola.timeline.scrubStepIsInPlace
import dev.sebastiano.ciacola.ui.AuthoringTextInputFocusTracker
import dev.sebastiano.ciacola.ui.CiacolaJewelTheme
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
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

typealias QaDocumentFactory = () -> Pair<DeckDocument, DeckSpecimenRegistry>

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
        timelineDocked: Boolean = true,
        timelineDetached: Boolean = false,
        timelineExpanded: Boolean = false,
        initialMode: StudioMode? = null,
        detachedWindowSize: DpSize = DpSize(1180.dp, 460.dp),
        detachedWindowPosition: WindowPosition = WindowPosition(120.dp, 520.dp),
        presenterWindowSize: DpSize = DpSize(1120.dp, 680.dp),
        presenterWindowPosition: WindowPosition = WindowPosition(140.dp, 90.dp),
        includeOverlayNavigation: Boolean = false,
        deckId: String? = null,
        documentFactory: QaDocumentFactory? = null,
        decksDir: Path? = null,
        useDeckDefaultTheme: Boolean = false,
        storyWindowOverlay: (@Composable (ComposeWindow) -> Unit)? = null,
        presenterWindowOverlay: (@Composable (ComposeWindow) -> Unit)? = null,
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
        timelineExpanded = timelineExpanded,
        initialMode = initialMode,
        detachedWindowSize = detachedWindowSize,
        detachedWindowPosition = detachedWindowPosition,
        presenterWindowSize = presenterWindowSize,
        presenterWindowPosition = presenterWindowPosition,
        includeOverlayNavigation = includeOverlayNavigation,
        deckId = deckId,
        documentFactory = documentFactory,
        decksDir = decksDir,
        useDeckDefaultTheme = useDeckDefaultTheme,
        storyWindowOverlay = storyWindowOverlay,
        presenterWindowOverlay = presenterWindowOverlay,
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
 * Live handle to an isolated document-backed Ciacola Studio session running inside a real
 * [ComposeWindow] without stealing OS focus.
 */
class QaStudio internal constructor(
    val automator: ComposeAutomator,
    val window: ComposeWindow,
    private val storeRef: AtomicReference<DeckDocumentStore?>,
    private val controllerRef: AtomicReference<DocumentTimelineController?>,
    private val stageInspectionRegistryRef: AtomicReference<StageInspectionRegistry?>,
    private val elementControllerRef: AtomicReference<DocumentElementInspectorController?>,
    private val themeControllerRef: AtomicReference<DocumentThemeInspectorController?>,
    private val lintControllerRef: AtomicReference<DocumentDeckLintController?>,
    val studioWorkspace: StudioWorkspaceController,
    val presenterWindow: PresenterWindow,
    val presenter: PresenterController,
    val review: ReviewStageCoordinator,
    private val reviewStoreRef: AtomicReference<ReviewCommentStore?>,
    val timelineWindow: DocumentTimelineWindow,
    private val reviewWindowRef: AtomicReference<SlideReviewWindow?>,
    private val animaticRef: AtomicReference<Animatic?>,
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

    val store: DeckDocumentStore
        get() = checkNotNull(storeRef.get()) { "DeckDocumentStore is not active" }

    val controller: DocumentTimelineController
        get() = checkNotNull(controllerRef.get()) { "DocumentTimelineController is not active" }

    val stageInspectionRegistry: StageInspectionRegistry
        get() = checkNotNull(stageInspectionRegistryRef.get()) { "StageInspectionRegistry is not active" }

    val elementController: DocumentElementInspectorController
        get() = checkNotNull(elementControllerRef.get()) { "DocumentElementInspectorController is not active" }

    val themeController: DocumentThemeInspectorController
        get() = checkNotNull(themeControllerRef.get()) { "DocumentThemeInspectorController is not active" }

    val lintController: DocumentDeckLintController
        get() = checkNotNull(lintControllerRef.get()) { "DocumentDeckLintController is not active" }

    val reviewStore: ReviewCommentStore
        get() = checkNotNull(reviewStoreRef.get()) { "ReviewCommentStore is not active" }

    val reviewWindow: SlideReviewWindow
        get() = checkNotNull(reviewWindowRef.get()) { "SlideReviewWindow is not active" }

    val activeDeck: DeckEntry
        get() = activeDeckReader()

    val activeDeckStoryboardDir: Path
        get() = storyboardDirForDeck(storyboardDir, activeDeck.id)

    val animatic: Animatic
        get() = checkNotNull(animaticRef.get()) { "Animatic is not active" }

    val input: SyntheticInput by lazy { SyntheticInput() }

    val detachedTimelineWindow: ComposeWindow?
        get() = detachedTimelineWindowRef.get()

    val reviewPanelWindow: ComposeWindow?
        get() = reviewPanelWindowRef.get()

    val presenterComposeWindow: ComposeWindow?
        get() = presenterComposeWindowRef.get()

    val presenterPanelWindow: ComposeWindow?
        get() = presenterComposeWindowRef.get()

    val preset: DeckThemePreset
        get() = presetReader()

    fun inputFor(targetWindow: AwtWindow): SyntheticInput = SyntheticInput(targetWindow)

    suspend fun <T> onUi(block: suspend () -> T): T {
        val scope = checkNotNull(uiScopeRef.get()) { "Compose UI scope is not active" }
        val deferred = CompletableDeferred<T>()
        scope.launch {
            runCatching { block() }.fold(deferred::complete, deferred::completeExceptionally)
        }
        return deferred.await()
    }

    suspend fun setThemePreset(newPreset: DeckThemePreset) {
        onUi { presetUpdater(newPreset) }
        settle()
    }

    suspend fun selectDeck(targetDeckId: String) {
        val entry = DeckCatalog.byId(targetDeckId)
        onUi { deckSwitcher(entry) }
        settle()
    }

    suspend fun settle() {
        automator.waitForIdle()
        delay(120)
        automator.waitForIdle()
    }

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

    fun pixelScale(targetWindow: AwtWindow = window): Double =
        targetWindow.graphicsConfiguration?.defaultTransform?.scaleX ?: 1.0

    /** Screen point on the timeline ruler (`ciacola-doc-timeline-ruler`) at [timeMs]. */
    fun rulerPoint(
        rulerNode: AutomatorNode,
        timeMs: Int,
        targetWindow: AwtWindow = detachedTimelineWindow ?: window,
        surface: TimelineSurface = if (detachedTimelineWindow != null) TimelineSurface.Detached else TimelineSurface.Docked,
    ): Point {
        val bounds = checkNotNull(rulerNode.boundsOnScreen) { "Timeline ruler has no screen bounds" }
        val px = controller.viewport.xForTime(timeMs.toFloat(), surface)
        val x = (bounds.x + (px / pixelScale(targetWindow)).roundToInt())
            .coerceIn(bounds.x + 4, bounds.x + (bounds.width - 4).coerceAtLeast(4))
        return Point(x, bounds.y + bounds.height / 2)
    }

    fun AutomatorNode.center(): Point = centerOnScreen

    /** Opens the docked & expanded Timeline Studio inside the Story window and returns its ruler node. */
    suspend fun openTimeline(): AutomatorNode {
        if (!timelineWindow.dockedInStoryWindow || !timelineWindow.timelineExpanded || studioWorkspace.issuesDockOpen) {
            onUi {
                if (studioWorkspace.mode != StudioMode.Edit) {
                    studioWorkspace.switchMode(StudioMode.Edit)
                }
                studioWorkspace.issuesDockOpen = false
                timelineWindow.detachedWindowVisible = false
                timelineWindow.dockedInStoryWindow = true
                timelineWindow.timelineExpanded = true
            }
            settle()
        }
        val ruler = automator.waitForNode(tag = "ciacola-doc-timeline-ruler", timeout = 5.seconds)
        settle()
        return ruler
    }

    /** Opens the standalone detached Timeline Studio window (`F6` mode) and returns its [ComposeWindow]. */
    suspend fun openDetachedTimeline(): ComposeWindow {
        onUi {
            timelineWindow.dockedInStoryWindow = false
            timelineWindow.detachedWindowVisible = true
        }
        automator.waitUntil(description = "detached timeline window showing", timeout = 10.seconds) {
            val w = detachedTimelineWindowRef.get()
            w != null && w.isShowing && findSkiaLayer(w) != null
        }
        automator.waitForNode(tag = "ciacola-doc-timeline-ruler", timeout = 5.seconds)
        settle()
        return checkNotNull(detachedTimelineWindowRef.get())
    }

    suspend fun closeDetachedTimeline() {
        onUi { timelineWindow.detachedWindowVisible = false }
        automator.waitUntil(description = "detached timeline window closed", timeout = 5.seconds) {
            detachedTimelineWindowRef.get() == null
        }
        settle()
    }

    suspend fun openReviewPanel(): ComposeWindow {
        onUi { reviewWindow.visible = true }
        automator.waitUntil(description = "review panel window showing", timeout = 10.seconds) {
            val w = reviewPanelWindowRef.get()
            w != null && w.isShowing && findSkiaLayer(w) != null
        }
        settle()
        return checkNotNull(reviewPanelWindowRef.get())
    }

    suspend fun openPresenterWindow(): ComposeWindow {
        onUi { presenterWindow.visible = true }
        automator.waitUntil(description = "presenter window showing", timeout = 10.seconds) {
            val w = presenterComposeWindowRef.get()
            w != null && w.isShowing && findSkiaLayer(w) != null
        }
        settle()
        return checkNotNull(presenterComposeWindowRef.get())
    }

    suspend fun closePresenterWindow() {
        detachWindowSwingListenersOnEdt(presenterComposeWindowRef.get())
        onUi { presenterWindow.visible = false }
        automator.waitUntil(description = "presenter window closed", timeout = 5.seconds) {
            presenterComposeWindowRef.get() == null
        }
        settle()
    }

    /** Scrubs to the settled rest beat `(sceneIndex, frameIndex)` and waits for the stage to match. */
    suspend fun restOnBeat(sceneIndex: Int, frameIndex: Int) {
        val beat = controller.snapshot.snapshot.eventBeats.first {
            it.sceneIndex == sceneIndex && it.frameIndex == frameIndex
        }
        onUi {
            controller.seekToMs(beat.timeMs)
            animatic.jumpTo(Storyboard.Index(sceneIndex, frameIndex))
        }
        automator.waitUntil(
            description = "rest on S${sceneIndex + 1}:B${frameIndex + 1}",
            timeout = 5.seconds,
        ) {
            animatic.currentIndex == Storyboard.Index(sceneIndex, frameIndex)
        }
        settle()
    }

    suspend fun awaitComposerFocus() {
        automator.waitForNode(tag = "ciacola-review-note-input", timeout = 5.seconds)
        automator.waitUntil(description = "composer focused", timeout = 5.seconds) {
            automator.findOneByTestTag("ciacola-review-note-input")?.isFocused == true
        }
    }

    /** Finds a text node rendered inside the slide stage viewport (never a timeline/inspector label). */
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

    fun awtWheel(screen: Point, modifierKey: Int, rotation: Int) {
        input.mouseMove(screen.x, screen.y)
        input.keyPress(modifierKey)
        try {
            input.mouseWheel(rotation)
        } finally {
            input.keyRelease(modifierKey)
        }
    }

    fun awtKey(pressed: Boolean, keyCode: Int) {
        if (pressed) input.keyPress(keyCode) else input.keyRelease(keyCode)
    }

    suspend fun <T> withHeldKey(keyCode: Int, block: suspend () -> T): T {
        input.keyPress(keyCode)
        return try {
            delay(30)
            block()
        } finally {
            input.keyRelease(keyCode)
        }
    }

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

    suspend fun screenshot(
        targetWindow: ComposeWindow = window,
        settleDelayMs: Long = 60L,
    ): BufferedImage = dev.sebastiano.ciacola.qa.screenshot(
        window = targetWindow,
        settleDelayMs = settleDelayMs,
    )

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

    fun evidencePath(file: File): String =
        QaPaths.toEvidenceRelativePath(file = file, worker = worker, qaRoot = qaRoot)

    suspend fun capture(name: String, targetWindow: ComposeWindow = window) {
        val dir = captureDir ?: return
        dir.mkdirs()
        val img = screenshot(targetWindow = targetWindow)
        ImageIO.write(img, "png", File(dir, name))
    }

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

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { lintControllerRef.get()?.close() }
        uiScopeRef.set(null)
        val exitApp = exitAppRef.getAndSet(null)
        val detached = detachedTimelineWindowRef.getAndSet(null)
        val reviewWin = reviewPanelWindowRef.getAndSet(null)
        val presenterWin = presenterComposeWindowRef.getAndSet(null)
        val story = storyWindowRef.getAndSet(null)
        val windows = listOfNotNull(detached, reviewWin, presenterWin, story)
        closeQaWindowsSafely(windows = windows, exitApp = exitApp, appThread = appThread)
        runCatching { workspaceRoot.toFile().deleteRecursively() }
    }
}

internal fun defaultSpectreCaptureDir(): File? =
    System.getenv("CIACOLA_SPECTRE_CAPTURE_DIR")
        ?.takeIf { it.isNotBlank() }
        ?.let(::File)
        ?.apply { mkdirs() }

/**
 * Launches a real-window document-backed Ciacola Studio session configured not to request OS focus.
 */
fun launchStudio(
    automator: ComposeAutomator,
    preset: DeckThemePreset = DeckThemePreset.StageDark,
    windowSize: DpSize = DpSize(1360.dp, 900.dp),
    windowPosition: WindowPosition = WindowPosition(80.dp, 60.dp),
    timelineDocked: Boolean = true,
    timelineDetached: Boolean = false,
    timelineExpanded: Boolean = false,
    initialMode: StudioMode? = null,
    detachedWindowSize: DpSize = DpSize(1180.dp, 460.dp),
    detachedWindowPosition: WindowPosition = WindowPosition(120.dp, 520.dp),
    presenterWindowSize: DpSize = DpSize(1120.dp, 680.dp),
    presenterWindowPosition: WindowPosition = WindowPosition(140.dp, 90.dp),
    includeOverlayNavigation: Boolean = false,
    deckId: String? = null,
    documentFactory: QaDocumentFactory? = null,
    decksDir: Path? = null,
    useDeckDefaultTheme: Boolean = false,
    storyWindowOverlay: (@Composable (ComposeWindow) -> Unit)? = null,
    presenterWindowOverlay: (@Composable (ComposeWindow) -> Unit)? = null,
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

    val storeRef = AtomicReference<DeckDocumentStore?>(null)
    val controllerRef = AtomicReference<DocumentTimelineController?>(null)
    val reviewRef = AtomicReference<ReviewStageCoordinator?>(null)
    val timelineWindowRef = AtomicReference<DocumentTimelineWindow?>(null)
    val reviewWindowRef = AtomicReference<SlideReviewWindow?>(null)
    val animaticRef = AtomicReference<Animatic?>(null)
    val inspectionRegistryRef = AtomicReference<StageInspectionRegistry?>(null)
    val elementControllerRef = AtomicReference<DocumentElementInspectorController?>(null)
    val themeControllerRef = AtomicReference<DocumentThemeInspectorController?>(null)
    val lintControllerRef = AtomicReference<DocumentDeckLintController?>(null)
    val workspaceRef = AtomicReference<StudioWorkspaceController?>(null)
    val presenterWindowRef = AtomicReference<PresenterWindow?>(null)
    val textInputTrackerRef = AtomicReference<AuthoringTextInputFocusTracker?>(null)
    val reviewStoreRef = AtomicReference<ReviewCommentStore?>(null)
    val presetHolder = AtomicReference(preset)
    val presetSetterRef = AtomicReference<((DeckThemePreset) -> Unit)?>(null)
    val initialDeckId = resolveInitialDeckId(
        rootStoryboardDir = storyboardDir,
        systemPropertyOverride = deckId ?: System.getProperty("ciacola.deck"),
    )
    val activeDeckHolder = AtomicReference(DeckCatalog.byId(initialDeckId))
    val deckSwitcherRef = AtomicReference<((DeckEntry) -> Unit)?>(null)

    val hostThread = thread(name = "ciacola-qa-studio-host", isDaemon = true) {
        application(exitProcessOnExit = false) {
            exitAppRef.set(::exitApplication)
            val appScope = rememberCoroutineScope()
            var activePreset by remember { mutableStateOf(preset) }
            presetHolder.set(activePreset)

            var activeDeckId by remember { mutableStateOf(initialDeckId) }
            val activeDeck = remember(activeDeckId) { DeckCatalog.byId(activeDeckId) }
            activeDeckHolder.set(activeDeck)
            val deckStoryboardDir = remember(storyboardDir, activeDeck.id) {
                storyboardDirForDeck(storyboardDir, activeDeck.id)
            }

            val deckSource = remember(activeDeck.id, decksDir) {
                if (decksDir != null) {
                    resolveDeckSource(activeDeck, decksDirProperty = decksDir.toString())
                } else {
                    DeckSource.Resource(
                        resourcePath = activeDeck.resourcePath,
                        assetsResourceDir = activeDeck.assetsResourceDir,
                    )
                }
            }
            val resolvedAssetsRootDir = remember(deckSource, activeDeck.id) {
                when (deckSource) {
                    is DeckSource.File -> (deckSource.assetsDir ?: deckSource.file.toAbsolutePath().normalize().parent)?.toString()
                        ?: activeDeck.assetsResourceDir
                    is DeckSource.Resource -> deckSource.assetsResourceDir
                }
            }

            val (initialDoc, activeSpecimens) = remember(activeDeck.id, documentFactory, deckSource) {
                if (documentFactory != null) {
                    documentFactory()
                } else if (deckSource is DeckSource.File) {
                    val raw = Files.readString(deckSource.file, java.nio.charset.StandardCharsets.UTF_8)
                    val decoded = DeckDocumentCodec.decode(
                        rawText = raw,
                        specimens = activeDeck.specimens,
                        migrations = DeckMigrations.Default,
                    )
                    val doc = (decoded as? DeckDecodeResult.Success)?.document
                        ?: loadClasspathDeckDocument(activeDeck)
                    doc to activeDeck.specimens
                } else {
                    loadClasspathDeckDocument(activeDeck) to activeDeck.specimens
                }
            }

            val store = remember(activeDeck.id, deckSource) {
                DeckDocumentStore(
                    initialDocument = initialDoc,
                    initialLoad = DeckLoadState.Loaded,
                )
            }.also(storeRef::set)

            val fileSync = remember(activeDeck.id, deckSource, store) {
                if (deckSource is DeckSource.File) {
                    val recoveryDir = tempRoot.resolve("recovery").also { Files.createDirectories(it) }
                    DeckFileSync(
                        targetFile = deckSource.file,
                        recoveryRootDir = recoveryDir,
                        store = store,
                        specimenRegistry = activeDeck.specimens,
                    )
                } else {
                    null
                }
            }
            LaunchedEffect(fileSync) {
                if (fileSync != null) {
                    fileSync.open()
                    fileSync.startBackgroundSync(this)
                }
            }
            DisposableEffect(fileSync) {
                onDispose { fileSync?.close() }
            }
            val syncController = remember(store, fileSync, activeDeck.id) {
                fileSync?.let { sync ->
                    DocumentSyncController(
                        store = store,
                        fileSync = sync,
                    )
                }
            }

            val reviewStore = remember(deckStoryboardDir, tempRoot) {
                ReviewCommentStore(storyboardDir = deckStoryboardDir, workspaceRoot = tempRoot)
            }.also(reviewStoreRef::set)

            val textInputTracker = remember { AuthoringTextInputFocusTracker() }.also(textInputTrackerRef::set)
            val stageInspectionRegistry = remember(activeDeck.id) { StageInspectionRegistry() }.also(inspectionRegistryRef::set)
            val stageInteractiveRegistry = remember(activeDeck.id) { StageInteractiveRegistry() }
            val stageDragPreview = remember(activeDeck.id) { StageDragPreview() }
            val keyboardController = remember(activeDeck.id) { DocumentKeyboardCaptureController() }
            val reviewStageCoordinator = rememberReviewStageCoordinator().also(reviewRef::set)

            val initialThemeName = if (useDeckDefaultTheme) {
                store.document.defaultTheme
            } else if (activePreset == DeckThemePreset.KeynoteLight) {
                if ("light" in store.document.themes) "light" else store.document.defaultTheme
            } else {
                if ("dark" in store.document.themes) "dark" else store.document.defaultTheme
            }

            val themeController = remember(store) {
                DocumentThemeInspectorController(
                    store = store,
                    initialThemeName = initialThemeName,
                )
            }.also(themeControllerRef::set)

            presetSetterRef.set { next ->
                presetHolder.set(next)
                activePreset = next
                val targetName = if (next == DeckThemePreset.KeynoteLight) "light" else "dark"
                if (targetName in store.document.themes) {
                    themeController.selectTheme(targetName)
                }
            }

            val elementController = remember(store, activeSpecimens) {
                DocumentElementInspectorController(
                    store = store,
                    specimens = activeSpecimens,
                )
            }.also(elementControllerRef::set)

            val lintController = remember(store, activeSpecimens, deckSource) {
                DocumentDeckLintController(
                    store = store,
                    specimens = activeSpecimens,
                    deckDir = (deckSource as? DeckSource.File)?.file?.parent,
                )
            }.also(lintControllerRef::set)
            DisposableEffect(lintController) {
                onDispose { lintController.close() }
            }

            val timelineController = remember(store, activeSpecimens) {
                DocumentTimelineController(
                    store = store,
                    specimens = activeSpecimens,
                    themeNameProvider = { themeController.activeThemeName },
                )
            }.also(controllerRef::set)

            val studioWorkspace = remember {
                StudioWorkspaceController(
                    store = store,
                    timelineController = timelineController,
                    elementController = elementController,
                    themeController = themeController,
                    lintController = lintController,
                    initialInspectorWidthDp = loadPersistedInspectorWidthDp(storyboardDir),
                ).apply {
                    val resolvedInitialMode = initialMode
                        ?: if (timelineDocked || timelineDetached) StudioMode.Edit else StudioMode.Present
                    if (resolvedInitialMode == StudioMode.Present) {
                        markPresentMode()
                    }
                }
            }.also { ws ->
                ws.updateBindings(
                    newStore = store,
                    newTimelineController = timelineController,
                    newElementController = elementController,
                    newThemeController = themeController,
                    newLintController = lintController,
                    newSyncController = syncController,
                )
                ws.onInspectorWidthPersisted = { widthDp ->
                    persistInspectorWidthDp(storyboardDir, widthDp)
                }
                workspaceRef.set(ws)
            }

            var freeClockMs by remember(activeDeck.id) { mutableLongStateOf(0L) }
            val animateFreeLoops = !studioWorkspace.isStudioOpen
            LaunchedEffect(activeDeck.id, animateFreeLoops) {
                if (!animateFreeLoops) return@LaunchedEffect
                var prevNanos = -1L
                while (true) {
                    withFrameNanos { now ->
                        if (prevNanos >= 0L) {
                            freeClockMs += ((now - prevNanos) / 1_000_000L).coerceAtLeast(0L)
                        }
                        prevNanos = now
                    }
                }
            }

            val presentSession = remember(store) {
                RenderSession.forPresent(
                    store = store,
                    freeClockMillis = { freeClockMs },
                    masterPlayheadMillis = {
                        if (studioWorkspace.isStudioOpen) timelineController.playheadMs.toLong() else null
                    },
                )
            }

            val directEditStageDecorator = remember(
                elementController,
                timelineController,
                lintController,
                studioWorkspace,
                reviewStageCoordinator,
            ) {
                ContentDecorator { inner ->
                    val overviewOpen = LocalOverviewOpen.current
                    val reviewOwnsClick = reviewStageCoordinator.inspectModeEnabled ||
                        reviewStageCoordinator.pendingSelection != null ||
                        reviewStageCoordinator.selectedPinCommentId != null
                    val stageSpec = store.document.stage
                    DocumentStageDirectEditOverlay(
                        controller = elementController,
                        enabled = studioWorkspace.isElementPickingEnabled && !overviewOpen && !reviewOwnsClick,
                        inlineEditBlockedProvider = { studioWorkspace.inlineEditBlockedProvider() },
                        onRestoreStageFocus = { studioWorkspace.onRestoreStageFocus?.invoke() },
                    ) {
                        Box(Modifier.fillMaxSize()) {
                            inner()
                            DocumentTimelineStageOverlay(
                                controller = timelineController,
                                enabled = studioWorkspace.isStudioOpen,
                                stageSelectedNodeIds = elementController.selectedNodeIds,
                            )
                            DocumentDeckLintStageOverlay(
                                controller = lintController,
                                activeSlideId = timelineController.currentPresentedSlideId ?: elementController.activeSlideId,
                                activeBeatIndex = timelineController.currentPresentedBeatIndex,
                                isInspectionVisible = studioWorkspace.isLinterVisible,
                                stageFormatSize = IntSize(stageSpec.width, stageSpec.height),
                            )
                            StageShortcutToastOverlay(
                                toast = studioWorkspace.lastShortcutToast,
                                enabled = studioWorkspace.mode != StudioMode.Present,
                                onDismissed = studioWorkspace::clearShortcutToast,
                                modifier = Modifier.align(androidx.compose.ui.Alignment.TopCenter),
                            )
                        }
                    }
                }
            }

            val combinedStageDecorator = remember(
                stageInspectionRegistry,
                reviewStageCoordinator,
                directEditStageDecorator,
            ) {
                ContentDecorator { inner ->
                    CompositionLocalProvider(
                        LocalStageInspectionRegistry provides stageInspectionRegistry,
                    ) {
                        (reviewStageCoordinator.stageDecorator + directEditStageDecorator).decorate(inner)
                    }
                }
            }

            val storyboard = rememberDocumentStoryboard(
                session = presentSession,
                config = DocumentRenderConfig(
                    themeName = themeController.activeThemeName,
                    specimens = activeSpecimens,
                    assetsRootDir = resolvedAssetsRootDir,
                    isEditMode = studioWorkspace.isStudioOpen,
                    keyboardController = keyboardController,
                    onNodeClick = { _, nodeId ->
                        if (studioWorkspace.isElementPickingEnabled) {
                            elementController.selectNode(nodeId)
                        }
                    },
                    onNodeDoubleClick = { _, nodeId ->
                        if (studioWorkspace.isElementPickingEnabled) {
                            elementController.startInlineTextEdit(nodeId)
                        }
                    },
                    stageDecorator = combinedStageDecorator,
                ),
            )

            val stillConfig = DocumentRenderConfig(
                themeName = themeController.activeThemeName,
                specimens = activeSpecimens,
                assetsRootDir = resolvedAssetsRootDir,
                isEditMode = false,
                keyboardController = null,
                stageDecorator = ContentDecorator.None,
            )
            var committedOverviewDoc by remember(activeDeck.id) { mutableStateOf(store.document) }
            LaunchedEffect(store.document, store.hasOpenTransaction) {
                if (!store.hasOpenTransaction) {
                    committedOverviewDoc = store.document
                }
            }
            val presenterCurrentSession = remember(store, activeDeck.id) {
                RenderSession.forPresent(
                    store = store,
                    freeClockMillis = { freeClockMs },
                    masterPlayheadMillis = {
                        if (studioWorkspace.isStudioOpen || timelineController.hasActiveGesture) {
                            timelineController.playheadMs.toLong()
                        } else {
                            null
                        }
                    },
                    subCompositionScrubMillis = { timelineController.subCompositionScrubMillis },
                )
            }
            val presenterNextSession = remember(store, activeDeck.id) {
                RenderSession.forPresent(
                    store = store,
                    freeClockMillis = { freeClockMs },
                    masterPlayheadMillis = {
                        if (studioWorkspace.isStudioOpen || timelineController.hasActiveGesture) {
                            timelineController.playheadMs.toLong()
                        } else {
                            null
                        }
                    },
                    subCompositionScrubMillis = { timelineController.subCompositionScrubMillis },
                )
            }
            val overviewStoryboard = remember(committedOverviewDoc, stillConfig) {
                buildPerThumbnailOverviewStoryboard(
                    frozenDoc = committedOverviewDoc,
                    config = stillConfig,
                )
            }
            val presenterCurrentStoryboard = rememberDocumentStoryboard(
                session = presenterCurrentSession,
                config = stillConfig,
            )
            val presenterNextStoryboard = rememberDocumentStoryboard(
                session = presenterNextSession,
                config = stillConfig,
            )

            val animatic = key(activeDeck.id) {
                rememberAnimatic(
                    initialIndex = Storyboard.Index(0, 0),
                    storyboard = { storyboard },
                )
            }.also(animaticRef::set)

            // Bidirectional Timeline <-> Animatic conflated scrub worker matching Main.kt.
            val pendingScrubRequest = remember(animatic) { kotlinx.coroutines.flow.MutableStateFlow<Pair<Long, Int>?>(null) }
            var scrubSeq by remember(animatic) { mutableStateOf(0L) }
            var isApplyingScrub by remember(animatic) { mutableStateOf(false) }
            SideEffect {
                timelineController.onPlayheadChanged = { timeMs ->
                    scrubSeq += 1L
                    pendingScrubRequest.value = scrubSeq to timeMs
                }
            }
            LaunchedEffect(animatic, timelineController) {
                pendingScrubRequest.collect { req ->
                    if (req == null) return@collect
                    pendingScrubRequest.compareAndSet(req, null)
                    val snap = timelineController.snapshot.snapshot
                    if (snap.stateSpans.isEmpty()) return@collect
                    val (stateIdx, frac) = snap.stateFractionForTimeMs(req.second)
                    val inPlace = scrubStepIsInPlace(
                        activeFrom = animatic.activeFromStateIndex,
                        activeTo = animatic.activeToStateIndex,
                        stateIndex = stateIdx,
                        fraction = frac,
                    )
                    isApplyingScrub = true
                    try {
                        animatic.seekToSegment(stateIdx, frac, rebuildScene = !inPlace)
                    } finally {
                        isApplyingScrub = false
                    }
                }
            }

            val settledIdx = animatic.currentIndex
            LaunchedEffect(settledIdx, timelineController.snapshot) {
                if (!isApplyingScrub && pendingScrubRequest.value == null) {
                    val beatLine = timelineController.snapshot.snapshot.eventBeats.firstOrNull {
                        it.sceneIndex == settledIdx.sceneIndex && it.frameIndex == settledIdx.frameIndex
                    }
                    if (beatLine != null) {
                        timelineController.syncPlayheadFromStage(beatLine.timeMs)
                    }
                }
            }

            var studioWindowHolder by remember { mutableStateOf<DocumentTimelineWindow?>(null) }
            var presenterWinHolder by remember { mutableStateOf<PresenterWindow?>(null) }

            fun switchToDeck(target: DeckEntry) {
                if (target.id == activeDeckId) return
                elementController.cancelInlineTextEdit()
                elementController.selectNode(null)
                timelineController.clearSelection()
                reviewStageCoordinator.dismissActivePopups()
                persistActiveDeckId(storyboardDir, target.id)
                activeDeckHolder.set(target)
                activeDeckId = target.id
            }
            deckSwitcherRef.set(::switchToDeck)

            val isAnyTextInputActive = {
                textInputTracker.hasFocusedTextInput ||
                    elementController.textInputHasFocus ||
                    elementController.inlineEditingNodeId != null ||
                    reviewStageCoordinator.pendingSelection != null ||
                    reviewStageCoordinator.selectedPinCommentId != null ||
                    presenterWinHolder?.controller?.jumpDialogOpen == true ||
                    presenterWinHolder?.controller?.isEditingNotes == true
            }
            studioWorkspace.isTextInputActiveProvider = isAnyTextInputActive
            studioWorkspace.onTogglePresenter = {
                presenterWinHolder?.let { it.visible = !it.visible }
            }
            studioWorkspace.onToggleDetachedTimeline = {
                studioWindowHolder?.let { tw ->
                    val next = !tw.detachedWindowVisible
                    if (next) tw.dockedInStoryWindow = false
                    tw.detachedWindowVisible = next
                }
            }
            studioWorkspace.onSelectDeckByIndex = { idx ->
                DeckCatalog.discoverEntries(decksDir).getOrNull(idx)?.let(::switchToDeck)
            }
            studioWorkspace.isStageFocusedProvider = { reviewStageCoordinator.stageHasFocus }
            studioWorkspace.inlineEditBlockedProvider = {
                studioWorkspace.themeSheetOpen || reviewStageCoordinator.inspectModeEnabled ||
                    reviewStageCoordinator.pendingSelection != null || reviewStageCoordinator.selectedPinCommentId != null
            }

            val activeNonSkipped = remember(store.document) {
                store.document.slides.filterNot { it.skipped }
            }
            val activeSceneIndex = animatic.presentedIndex.sceneIndex.coerceAtLeast(0)
            val activeBeatIndex = animatic.presentedIndex.frameIndex.coerceAtLeast(0)
            val activeSlideIdForSync = activeNonSkipped.getOrNull(activeSceneIndex)?.id
            LaunchedEffect(timelineController, activeSlideIdForSync, activeSceneIndex, activeBeatIndex) {
                timelineController.updatePresentedLocation(
                    slideId = activeSlideIdForSync,
                    sceneIndex = activeSceneIndex,
                    beatIndex = activeBeatIndex,
                )
            }
            StudioWorkspaceSyncEffects(
                workspace = studioWorkspace,
                activeSlideId = activeSlideIdForSync,
                activeBeatIndex = activeBeatIndex,
                animatic = animatic,
            )
            reviewStageCoordinator.fallbackNodeIdResolver = { stagePoint, dragRect ->
                stageInspectionRegistry.nodes.values
                    .filter { snap ->
                        snap.effectiveAlpha > 0.01f &&
                            snap.stageBounds.width > 0f &&
                            snap.stageBounds.height > 0f &&
                            if (dragRect != null) snap.stageBounds.overlaps(dragRect) else snap.stageBounds.contains(stagePoint)
                    }
                    .minByOrNull { it.stageBounds.width * it.stageBounds.height }
                    ?.elementId
            }
            val resolvedDeckFilePath = remember(deckSource) {
                when (deckSource) {
                    is DeckSource.File -> deckSource.file.toAbsolutePath().normalize().toString()
                    is DeckSource.Resource -> deckSource.resourcePath
                }
            }
            reviewStore.defaultDeckId = activeDeck.id
            reviewStore.defaultDeckFilePath = resolvedDeckFilePath

            val hostTheme = remember(activePreset) { DeckThemes.forPreset(activePreset) }
            val materialColors = remember(hostTheme) { hostTheme.toMaterialColors() }
            val materialTypography = remember(hostTheme) { hostTheme.toMaterialTypography() }

            fun closeAuthoringChrome() {
                studioWindowHolder?.let {
                    it.dockedInStoryWindow = false
                    it.detachedWindowVisible = false
                }
                elementController.cancelInlineTextEdit()
                elementController.selectNode(null)
                reviewStageCoordinator.inspectModeEnabled = false
                reviewStageCoordinator.dismissActivePopups()
            }

            val speakerNotesMemo = remember(store) { DocumentSpeakerNotesMemo() }
            val presenterWindow = rememberPresenterWindow(
                animatic = animatic,
                speakerNotesProvider = { speakerNotesMemo.notesFor(store.document) },
                documentProvider = { store.document },
                onSaveSpeakerNote = if (store.isReadOnly) {
                    null
                } else {
                    { slideId, beatIndex, note -> saveSpeakerNote(store, slideId, beatIndex, note) }
                },
                previewStoryboardProvider = { presenterCurrentStoryboard },
                nextPreviewStoryboardProvider = { presenterNextStoryboard },
                currentPresetProvider = { activePreset },
                isStudioOpenProvider = { studioWorkspace.hasExpandedAuthoringTools },
                isStageAuthoringChromeOpenProvider = { studioWorkspace.isAnyAuthoringToolOpen },
                isInspectOpenProvider = { reviewStageCoordinator.inspectModeEnabled },
                openAuthoringToolsProvider = {
                    buildList {
                        if (studioWorkspace.isTimelineTracksOpen) add("Timeline (F5)")
                        if (studioWorkspace.isDesignEditorVisible) add("Deck Theme (F7)")
                        if (studioWorkspace.isLinterVisible) add("Issues (F8)")
                        if (reviewStageCoordinator.inspectModeEnabled) add("Review Inspect (F3)")
                    }
                },
                onEnterCleanShowMode = {
                    closeAuthoringChrome()
                    studioWorkspace.markPresentMode()
                },
                isExternalTextInputActiveProvider = {
                    textInputTracker.hasFocusedTextInput ||
                        elementController.textInputHasFocus ||
                        elementController.inlineEditingNodeId != null ||
                        reviewStageCoordinator.pendingSelection != null ||
                        reviewStageCoordinator.selectedPinCommentId != null
                },
                onRestoreStageFocus = {
                    runCatching { reviewStageCoordinator.stageFocusRequester.requestFocus() }.isSuccess
                },
                extraPreviewKeyHandler = { ev, _ ->
                    studioWorkspace.handleStudioPreviewKey(ev, allowStageAuthoringShortcuts = false)
                },
            ).also {
                presenterWinHolder = it
                presenterWindowRef.set(it)
            }

            val latestActivePreset by androidx.compose.runtime.rememberUpdatedState(activePreset)
            val hoistedTrailingPane: @Composable () -> Unit = remember(studioWorkspace, animatic, appScope) {
                {
                    CiacolaJewelTheme(isDark = latestActivePreset == DeckThemePreset.StageDark) {
                        StudioUnifiedInspectorPane(
                            workspace = studioWorkspace,
                            animatic = animatic,
                            scope = appScope,
                        )
                    }
                }
            }
            val showBottomDockTabs = studioWorkspace.issuesDockOpen || studioWindowHolder?.timelineExpanded == true
            val bottomDockTabs: (@Composable () -> Unit)? = if (showBottomDockTabs) {
                {
                    StudioBottomDockTabStrip(
                        issuesActive = studioWorkspace.issuesDockOpen,
                        errorCount = lintController.errorCount,
                        warningCount = lintController.warningCount,
                        onSelectTimeline = {
                            studioWorkspace.issuesDockOpen = false
                            studioWindowHolder?.timelineExpanded = true
                        },
                        onSelectIssues = {
                            studioWorkspace.issuesDockOpen = true
                        },
                    )
                }
            } else {
                null
            }

            val studioWindow = rememberDocumentTimelineWindow(
                controller = timelineController,
                currentPresetProvider = { activePreset },
                suppressShortcutsProvider = isAnyTextInputActive,
                onReturnFocusToStage = {
                    runCatching { reviewStageCoordinator.stageFocusRequester.requestFocus() }
                },
                trailingPane = hoistedTrailingPane,
                leadingToolbarContent = null,
                extraPreviewKeyHandler = { ev, _ ->
                    studioWorkspace.handleStudioPreviewKey(ev, allowStageAuthoringShortcuts = false)
                },
                initialDocked = timelineDocked,
                initialExpanded = timelineExpanded,
                bottomDockHeaderLeading = bottomDockTabs,
                bottomDockHeaderTrailing = if (studioWorkspace.issuesDockOpen) {
                    { StudioBottomIssuesDockHeaderTrailing(studioWorkspace) }
                } else {
                    null
                },
                alternateBottomContent = if (studioWorkspace.issuesDockOpen) {
                    { StudioBottomIssuesDockBody(workspace = studioWorkspace, animatic = animatic, scope = appScope) }
                } else {
                    null
                },
                showStudioChrome = studioWorkspace.isAppBarActive,
            ).also { tw ->
                if (studioWindowHolder == null) {
                    tw.dockedInStoryWindow = timelineDocked
                    tw.detachedWindowVisible = timelineDetached
                    tw.timelineExpanded = timelineExpanded
                }
                studioWindowHolder = tw
                studioWorkspace.timelineWindow = tw
                timelineWindowRef.set(tw)
            }

            var reviewWinHolder by remember { mutableStateOf<SlideReviewWindow?>(null) }
            val prevReviewVisible = reviewWinHolder?.visible == true
            val slideReviewWindow = rememberSlideReviewWindow(
                animatic = animatic,
                store = reviewStore,
                stageCoordinator = reviewStageCoordinator,
                currentPresetProvider = { activePreset },
                timelineDockedProvider = { studioWindow.dockedInStoryWindow },
                onToggleTimeline = { studioWorkspace.toggleStudioTimeline() },
                deckIdProvider = { activeDeck.id },
                deckFilePathProvider = { resolvedDeckFilePath },
                slideIdProvider = { idx -> activeNonSkipped.getOrNull(idx)?.id },
            ).also {
                if (reviewWinHolder != null && reviewWinHolder !== it) {
                    it.visible = prevReviewVisible
                }
                reviewWinHolder = it
                reviewWindowRef.set(it)
            }

            val restoreStageFocus: () -> Unit = {
                runCatching { reviewStageCoordinator.stageFocusRequester.requestFocus() }
            }
            studioWorkspace.setReviewInspectMode = { enabled ->
                reviewStageCoordinator.inspectModeEnabled = enabled
                if (enabled) reviewStageCoordinator.showStageBadges = true else reviewStageCoordinator.dismissActivePopups()
            }
            studioWorkspace.onToggleReviewInspect = {
                val next = !reviewStageCoordinator.inspectModeEnabled
                reviewStageCoordinator.inspectModeEnabled = next
                if (next) reviewStageCoordinator.showStageBadges = true
            }
            studioWorkspace.onOpenReviewPanel = { reviewWinHolder?.visible = true }
            studioWorkspace.onEnterCleanShowMode = ::closeAuthoringChrome
            studioWorkspace.onRestoreStageFocus = restoreStageFocus

            val activeSlide = activeNonSkipped.getOrNull(activeSceneIndex)
            val breadcrumb = formatStudioBreadcrumb(
                sceneIndex = activeSceneIndex,
                sceneCount = activeNonSkipped.size,
                title = activeSlide?.title,
                beatIndex = activeBeatIndex,
                beatCount = activeSlide?.beats?.size ?: 1,
            )
            val shellWindow = rememberStudioShellWindow(
                workspace = studioWorkspace,
                appBar = {
                    StudioAppBarHost(
                        workspace = studioWorkspace,
                        breadcrumb = breadcrumb,
                        presenterOpen = presenterWindow.visible,
                        onTogglePresenter = { presenterWindow.visible = !presenterWindow.visible },
                        deckSwitcher = {
                            DeckSwitcherDropdown(
                                activeDeck = activeDeck,
                                onSelectDeck = ::switchToDeck,
                                entriesProvider = { DeckCatalog.discoverEntries(decksDir) },
                                onPopupClosed = restoreStageFocus,
                            )
                        },
                    )
                },
                themeSheet = {
                    StudioThemeSheet(
                        workspace = studioWorkspace,
                        onPickDeckFileToCopyThemes = null,
                    )
                },
                issuesDock = if (studioWindow.dockedInStoryWindow) {
                    null
                } else {
                    { StudioIssuesDock(workspace = studioWorkspace, animatic = animatic, scope = appScope) }
                },
            )

            val deckMenuWindow = rememberDeckMenuWindow(
                activeDeckProvider = { activeDeck },
                onSelectDeck = ::switchToDeck,
                animaticProvider = { animatic },
                isTextInputActiveProvider = isAnyTextInputActive,
                isStudioOpenProvider = { studioWorkspace.isStudioOpen },
                keyboardController = keyboardController,
                onBeforeNavigate = { presenterWinHolder?.controller?.clearCurtain() },
                extraPreviewKeyHandler = { ev -> studioWorkspace.handleStudioPreviewKey(ev) },
                entries = DeckCatalog.discoverEntries(decksDir),
            )

            val fullDecorator = remember(deckMenuWindow, presenterWindow, shellWindow, studioWindow, slideReviewWindow) {
                deckMenuWindow.decorator +
                    presenterWindow.decorator +
                    shellWindow.decorator +
                    studioWindow.decorator +
                    slideReviewWindow.decorator
            }

            DeckTextRendererProvider(DefaultMagicTextV2Renderer) {
                CompositionLocalProvider(
                    LocalAuthoringTextInputTracker provides textInputTracker,
                    LocalStageInteractiveRegistry provides stageInteractiveRegistry,
                    LocalStageDragPreview provides stageDragPreview,
                ) {
                    CiacolaJewelTheme(isDark = activePreset == DeckThemePreset.StageDark) {
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
                                val committedOverviewSlides = remember(committedOverviewDoc) {
                                    committedOverviewDoc.slides.filterNot { it.skipped }
                                }
                                val overviewProviders = rememberOverviewSlideProviders(committedOverviewSlides)
                                CompositionLocalProvider(
                                    LocalOverviewOpen provides overviewVisibility.isVisible,
                                    LocalOverviewChromeStyle provides rememberStudioOverviewChromeStyle(),
                                    LocalOverviewSlideTitleProvider provides overviewProviders.titleProvider,
                                    LocalOverviewThumbnailFrameProvider provides overviewProviders.thumbnailFrameProvider,
                                ) {
                                    Box(Modifier.fillMaxSize()) {
                                        fullDecorator.decorate {
                                            EaselOverview(
                                                animatic = animatic,
                                                visibility = overviewVisibility,
                                                slideTitle = overviewProviders.titleProvider,
                                                previewStoryboard = overviewStoryboard,
                                            ) {
                                                if (includeOverlayNavigation) {
                                                    EaselOverlay(overlay = { OverlayNavigation(animatic) }) {
                                                        Easel(animatic)
                                                    }
                                                } else {
                                                    Easel(animatic)
                                                }
                                            }
                                        }
                                        storyWindowOverlay?.invoke(window)
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
                                    position = presenterWindowPosition,
                                    size = presenterWindowSize,
                                ),
                            ) {
                                window.isAutoRequestFocus = false
                                DisposableEffect(window) {
                                    presenterComposeWindowRef.set(window)
                                    onDispose { presenterComposeWindowRef.compareAndSet(window, null) }
                                }
                                Box(Modifier.fillMaxSize()) {
                                    presenterWindow.Content()
                                    presenterWindowOverlay?.invoke(window)
                                }
                            }
                        }
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
        storeRef = storeRef,
        controllerRef = controllerRef,
        stageInspectionRegistryRef = inspectionRegistryRef,
        elementControllerRef = elementControllerRef,
        themeControllerRef = themeControllerRef,
        lintControllerRef = lintControllerRef,
        studioWorkspace = checkNotNull(workspaceRef.get()),
        presenterWindow = checkNotNull(presenterWindowRef.get()),
        presenter = checkNotNull(presenterWindowRef.get()).controller,
        review = checkNotNull(reviewRef.get()),
        reviewStoreRef = reviewStoreRef,
        timelineWindow = checkNotNull(timelineWindowRef.get()),
        reviewWindowRef = reviewWindowRef,
        animaticRef = animaticRef,
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
