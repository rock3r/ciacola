package dev.sebastiano.ciacola.timeline

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.bnorm.storyboard.easel.Easel
import dev.bnorm.storyboard.easel.overview.EaselOverview
import dev.bnorm.storyboard.easel.rememberAnimatic
import dev.bnorm.storyboard.plus
import dev.sebastiano.ciacola.createFoundationDeck
import dev.sebastiano.ciacola.rememberHotReloadStoryboard
import dev.sebastiano.ciacola.review.ReviewCommentStore
import dev.sebastiano.ciacola.review.ReviewStageCoordinator
import dev.sebastiano.ciacola.review.rememberReviewStageCoordinator
import dev.sebastiano.ciacola.review.rememberSlideReviewWindow
import dev.sebastiano.ciacola.theme.DeckThemePreset
import dev.sebastiano.ciacola.theme.DeckThemes
import dev.sebastiano.spectre.core.AutomatorNode
import dev.sebastiano.spectre.core.ComposeAutomator
import dev.sebastiano.spectre.core.RobotDriver
import dev.sebastiano.spectre.testing.ComposeAutomatorRule
import dev.sebastiano.spectre.testing.runSpectreTest
import java.awt.Component
import java.awt.Container
import java.awt.EventQueue
import java.awt.Point
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.imageio.ImageIO
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.skiko.SkiaLayer
import org.jetbrains.skiko.toBufferedImage
import org.junit.Rule

/**
 * Real-window end-to-end QA of Ciacola's Studio (Slide Stage + Ctrl-Click Review + After Effects
 * Timeline) driven by Spectre 0.9.0 on JetBrains Runtime 25. Every interaction goes through real
 * AWT input (Spectre's synthetic driver, plus raw AWT events where a modifier must be held during a
 * wheel or drag), and every assertion checks the visible outcome or the state that drives it.
 */
class CiacolaStudioSpectreTest {

    private val windowRef = AtomicReference<ComposeWindow?>(null)
    private val uiScopeRef = AtomicReference<CoroutineScope?>(null)
    private var appThread: Thread? = null

    @get:Rule
    val automatorRule = ComposeAutomatorRule(
        factory = { ComposeAutomator.inProcess(robotDriver = RobotDriver.synthetic()) },
    )

    private class Studio(
        val automator: ComposeAutomator,
        val controller: TimelineStudioController,
        val overridesStore: TimelineOverridesStore,
        val review: ReviewStageCoordinator,
        val captureDir: File?,
    )

    // region Harness

    private suspend fun <T> onUi(block: suspend () -> T): T {
        val scope = checkNotNull(uiScopeRef.get()) { "Compose UI scope not initialized" }
        val deferred = CompletableDeferred<T>()
        scope.launch { runCatching { block() }.fold(deferred::complete, deferred::completeExceptionally) }
        return deferred.await()
    }

    private fun findSkiaLayer(root: Component?): SkiaLayer? {
        if (root == null) return null
        if (root is SkiaLayer) return root
        if (root is Container) {
            for (child in root.components) findSkiaLayer(child)?.let { return it }
        }
        return null
    }

    /** Window content in Compose window pixels (same space as `AutomatorNode.boundsInWindow`). */
    private suspend fun screenshot(): BufferedImage {
        delay(60)
        // Copy into a heap ARGB image while the Skia bitmap is provably alive: toBufferedImage()
        // wraps the bitmap's native pixels, and blitting after the bitmap is collected crashes.
        return onUi {
            windowRef.get()?.repaint()
            val bitmap = checkNotNull(findSkiaLayer(windowRef.get())?.screenshot()) { "No SkiaLayer" }
            try {
                val raw = bitmap.toBufferedImage()
                BufferedImage(raw.width, raw.height, BufferedImage.TYPE_INT_ARGB).also {
                    val g = it.createGraphics()
                    g.drawImage(raw, 0, 0, null)
                    g.dispose()
                }
            } finally {
                java.lang.ref.Reference.reachabilityFence(bitmap)
                bitmap.close()
            }
        }
    }

    private suspend fun Studio.capture(name: String) {
        val dir = captureDir ?: return
        ImageIO.write(screenshot(), "png", File(dir, name))
    }

    private fun BufferedImage.crop(bottomPx: Int): BufferedImage =
        getSubimage(0, 0, width, bottomPx.coerceIn(1, height))

    /** Fraction of pixels whose channels differ by more than ~6%. */
    private fun differingFraction(a: BufferedImage, b: BufferedImage): Double {
        require(a.width == b.width && a.height == b.height)
        var differing = 0
        for (y in 0 until a.height) for (x in 0 until a.width) {
            val p = a.getRGB(x, y)
            val q = b.getRGB(x, y)
            val d = maxOf(
                abs((p shr 16 and 0xFF) - (q shr 16 and 0xFF)),
                abs((p shr 8 and 0xFF) - (q shr 8 and 0xFF)),
                abs((p and 0xFF) - (q and 0xFF)),
            )
            if (d > 16) differing++
        }
        return differing.toDouble() / (a.width * a.height)
    }

    /** Device pixels per AWT point (Compose window px / screen points). */
    private fun pixelScale(): Double = windowRef.get()?.graphicsConfiguration?.defaultTransform?.scaleX ?: 1.0

    /**
     * Spectre's synthetic input adapter, used directly where a gesture needs a held modifier key or
     * a non-primary button (the high-level automator API only exposes primary clicks/drags and
     * plain wheel notches). It tracks held keys, so `Alt` + wheel is delivered as one gesture.
     */
    private val robot: SyntheticInput by lazy { SyntheticInput(checkNotNull(windowRef.get())) }

    /** Reflective handle on Spectre's internal synthetic adapter (same input path Spectre uses). */
    private class SyntheticInput(window: java.awt.Window) {
        private val adapter: Any = Class.forName("dev.sebastiano.spectre.core.SyntheticRobotAdapter")
            .getConstructor(java.awt.Window::class.java)
            .newInstance(window)

        private fun call(name: String, vararg args: Int) {
            val types = Array(args.size) { Int::class.javaPrimitiveType!! }
            adapter.javaClass.getMethod(name, *types).invoke(adapter, *args.toTypedArray())
        }

        fun mouseMove(x: Int, y: Int) = call("mouseMove", x, y)
        fun mousePress(buttons: Int) = call("mousePress", buttons)
        fun mouseRelease(buttons: Int) = call("mouseRelease", buttons)
        fun mouseWheel(notches: Int) = call("mouseWheel", notches)
        fun keyPress(keyCode: Int) = call("keyPress", keyCode)
        fun keyRelease(keyCode: Int) = call("keyRelease", keyCode)
    }

    /** A wheel notch with a modifier key held. */
    private fun awtWheel(screen: Point, modifierKey: Int, rotation: Int) {
        robot.mouseMove(screen.x, screen.y)
        robot.keyPress(modifierKey)
        robot.mouseWheel(rotation)
        robot.keyRelease(modifierKey)
    }

    private suspend fun awtDrag(from: Point, to: Point, buttonMask: Int, steps: Int = 10) {
        robot.mouseMove(from.x, from.y)
        delay(20)
        robot.mousePress(buttonMask)
        delay(20)
        for (i in 1..steps) {
            robot.mouseMove(from.x + (to.x - from.x) * i / steps, from.y + (to.y - from.y) * i / steps)
            delay(16)
        }
        robot.mouseRelease(buttonMask)
        delay(30)
    }

    private fun awtKey(pressed: Boolean, keyCode: Int) {
        if (pressed) robot.keyPress(keyCode) else robot.keyRelease(keyCode)
    }

    private fun AutomatorNode.center(): Point = checkNotNull(centerOnScreen) { "No screen bounds for $this" }

    /** Screen point on the timeline ruler at [timeMs]. */
    private fun Studio.rulerPoint(canvas: AutomatorNode, timeMs: Int): Point {
        val bounds = checkNotNull(canvas.boundsOnScreen)
        val x = bounds.x + (controller.viewport.xForTime(timeMs.toFloat()) / pixelScale()).roundToInt()
        return Point(x, bounds.y + 39)
    }

    private suspend fun Studio.settle() {
        automator.waitForIdle()
        delay(120)
        automator.waitForIdle()
    }

    private fun launchStudio(): Studio {
        val tempRoot = Files.createTempDirectory("ciacola-spectre-e2e")
        val storyboardDir = tempRoot.resolve(".storyboard").also { Files.createDirectories(it) }
        val captureDir = System.getenv("CIACOLA_SPECTRE_CAPTURE_DIR")?.takeIf { it.isNotBlank() }?.let(::File)?.apply { mkdirs() }
        val readyLatch = CountDownLatch(1)
        val controllerRef = AtomicReference<TimelineStudioController?>(null)
        val reviewRef = AtomicReference<ReviewStageCoordinator?>(null)
        val overridesStore = TimelineOverridesStore(storyboardDir = storyboardDir)
        val reviewStore = ReviewCommentStore(storyboardDir = storyboardDir, workspaceRoot = tempRoot)

        appThread = thread(name = "ciacola-spectre-host", isDaemon = true) {
            application(exitProcessOnExit = false) {
                val preset = DeckThemePreset.StageDark
                val hostTheme = remember { DeckThemes.forPreset(preset) }
                val loopClock = remember { DeckLoopClock() }
                val motionPathPreview = remember { MotionPathPreviewState() }
                val stageSelection = remember { TimelineStageSelection() }
                val reviewStageCoordinator = rememberReviewStageCoordinator()
                reviewRef.set(reviewStageCoordinator)
                stageSelection.pickingSuppressed = {
                    reviewStageCoordinator.inspectModeEnabled || reviewStageCoordinator.pendingSelection != null
                }
                val structuralRev = overridesStore.structuralRevision
                val paramRev = overridesStore.parameterRevision
                val currentOverrides = remember(structuralRev, paramRev) { overridesStore.data() }
                val timelineStageDecorator = rememberTimelineStageDecorator(
                    loopClock = loopClock,
                    motionPathPreview = motionPathPreview,
                    overridesStore = overridesStore,
                    stageSelection = stageSelection,
                )
                val storyboard = rememberHotReloadStoryboard(structuralRev, paramRev) {
                    createFoundationDeck(
                        theme = hostTheme,
                        bundledPreset = preset,
                        includeKeynoteEffectsLab = true,
                        stageDecorator = timelineStageDecorator + reviewStageCoordinator.stageDecorator,
                        timelineOverrides = currentOverrides,
                    )
                }
                val animatic = rememberAnimatic(storyboard = { storyboard })
                val timelineController = rememberTimelineStudioController(
                    animatic = animatic,
                    overridesStore = overridesStore,
                    loopClock = loopClock,
                    reviewStore = reviewStore,
                    motionPathPreview = motionPathPreview,
                    stageSelection = stageSelection,
                )
                controllerRef.set(timelineController)
                val timelineWindow = rememberTimelineStudioWindow(
                    controller = timelineController,
                    currentPresetProvider = { preset },
                    suppressShortcutsProvider = {
                        reviewStageCoordinator.pendingSelection != null || reviewStageCoordinator.selectedPinCommentId != null
                    },
                )
                val reviewWindow = rememberSlideReviewWindow(
                    animatic = animatic,
                    store = reviewStore,
                    stageCoordinator = reviewStageCoordinator,
                    currentPresetProvider = { preset },
                    timelineDockedProvider = { timelineController.dockedInStoryWindow },
                    onToggleTimeline = { timelineController.dockedInStoryWindow = !timelineController.dockedInStoryWindow },
                )
                // Same decorator order as DesktopEasel in Main.kt: review outermost, timeline inside.
                val fullDecorator = reviewWindow.decorator + timelineWindow.decorator
                MaterialTheme(colors = hostTheme.toMaterialColors()) {
                    Window(
                        onCloseRequest = ::exitApplication,
                        title = "Ciacola - Story",
                        state = rememberWindowState(position = WindowPosition(80.dp, 60.dp), size = DpSize(1360.dp, 900.dp)),
                    ) {
                        uiScopeRef.set(rememberCoroutineScope())
                        if (windowRef.compareAndSet(null, window)) readyLatch.countDown()
                        Box(Modifier.fillMaxSize()) {
                            fullDecorator.decorate {
                                EaselOverview(animatic) { Easel(animatic) }
                            }
                        }
                    }
                }
            }
        }
        assertTrue(readyLatch.await(15, TimeUnit.SECONDS), "Timed out waiting for ComposeWindow")
        return Studio(
            automator = automatorRule.automator,
            controller = checkNotNull(controllerRef.get()),
            overridesStore = overridesStore,
            review = checkNotNull(reviewRef.get()),
            captureDir = captureDir,
        )
    }

    private suspend fun Studio.openTimeline(): AutomatorNode {
        val toggle = automator.waitForNode(tag = "ciacola-hud-toggle-timeline", timeout = 10.seconds)
        automator.waitForVisualIdle()
        automator.click(toggle)
        val canvas = automator.waitForNode(tag = "ciacola-timeline-canvas", timeout = 5.seconds)
        settle()
        return canvas
    }

    private suspend fun Studio.restOnBeat(sceneIndex: Int, frameIndex: Int) {
        val beat = controller.snapshot.eventBeats.first { it.sceneIndex == sceneIndex && it.frameIndex == frameIndex }
        onUi { controller.scrubToTimeMs(beat.timeMs) }
        automator.waitUntil(description = "rest on S${sceneIndex + 1}:B${frameIndex + 1}", timeout = 5.seconds) {
            controller.animatic.activeFromStateIndex == beat.stateIndex && controller.animatic.activeFraction == 0f &&
                controller.currentPlayheadTimeMs == beat.timeMs
        }
        settle()
    }

    /** The review composer focuses its note field a frame after opening; keys go there from then on. */
    private suspend fun Studio.awaitComposerFocus() {
        automator.waitForNode(tag = "ciacola-review-note-input", timeout = 5.seconds)
        automator.waitUntil(description = "composer focused", timeout = 5.seconds) {
            automator.findOneByTestTag("ciacola-review-note-input")?.isFocused == true
        }
    }

    /** A text node rendered on the slide (inside the preview), never a timeline label. */
    private suspend fun Studio.stageText(text: String): AutomatorNode {
        var found: AutomatorNode? = null
        automator.waitUntil(description = "stage text '$text'", timeout = 5.seconds) {
            val stage = automator.findOneByTestTag("ciacola-preview-viewport")?.boundsOnScreen
            found = automator.findByText(text, true).firstOrNull { node ->
                val c = node.centerOnScreen
                stage != null && c != null && stage.contains(c)
            }
            found != null
        }
        return checkNotNull(found)
    }

    @AfterTest
    fun tearDownWindow() {
        val win = windowRef.getAndSet(null)
        uiScopeRef.set(null)
        if (win != null) EventQueue.invokeLater { runCatching { win.dispose() } }
        appThread?.join(3_000)
    }

    // endregion

    @Test
    fun scrubbingWithTheMouseForwardAcrossABeatAndBackShowsTheRightTransition(): Unit =
        runSpectreTest(timeout = 120.seconds) {
            val studio = launchStudio()
            with(studio) {
                val canvas = openTimeline()
                val snapshot = controller.snapshot
                val seg0 = snapshot.stateSpans.first { !it.isHold && it.durationMs > 0 }
                val seg1 = snapshot.stateSpans.filter { !it.isHold && it.durationMs > 0 }[1]
                val y = rulerPoint(canvas, 0).y

                // Drag the playhead from beat 1 well into the second transition, then back into the
                // middle of the first one (the reported bug showed the third transition here).
                automator.swipe(
                    startX = rulerPoint(canvas, 100).x, startY = y,
                    endX = rulerPoint(canvas, seg1.startMs + seg1.durationMs / 2).x, endY = y,
                    steps = 24, duration = 480.milliseconds,
                )
                automator.swipe(
                    startX = rulerPoint(canvas, seg1.startMs + seg1.durationMs / 2).x, startY = y,
                    endX = rulerPoint(canvas, seg0.startMs + seg0.durationMs / 2).x, endY = y,
                    steps = 24, duration = 480.milliseconds,
                )
                settle()
                val landed = controller.currentPlayheadTimeMs
                assertTrue(landed in seg0.startMs..(seg0.startMs + seg0.durationMs), "Playhead in segment 1 (got ${landed}ms)")
                assertEquals(seg0.stateIndex, controller.animatic.activeFromStateIndex, "Stage shows the first transition")
                assertEquals(seg0.stateIndex + 1, controller.animatic.activeToStateIndex)
                assertTrue(
                    automator.findByText("reversible", false).isEmpty(),
                    "Third beat's words must not be on stage while scrubbing the first transition",
                )
                assertTrue(automator.findByText("advances", false).isNotEmpty(), "Second beat's words morph in")
                capture("01-scrub-back-into-first-transition.png")

                // The frame must equal a scrub that reaches the same time from the other end.
                automator.moveTo(rulerPoint(canvas, 0).x, y - 200)
                settle()
                val studioTop = checkNotNull(automator.findOneByTestTag("ciacola-timeline-studio")).boundsInWindow.top.toInt()
                val viaDrag = screenshot().crop(studioTop)
                onUi { controller.scrubToTimeMs(snapshot.totalDurationMs) }
                settle()
                onUi { controller.scrubToTimeMs(landed) }
                settle()
                val viaJump = screenshot().crop(studioTop)
                val diff = differingFraction(viaDrag, viaJump)
                assertTrue(diff < 0.002, "Stage after drag differs from a direct scrub to ${landed}ms: ${"%.4f".format(diff)}")

                // Previous / next beat icons and transport.
                automator.click(checkNotNull(automator.findOneByTestTag("ciacola-timeline-next-beat")))
                settle()
                assertEquals(snapshot.eventBeats[1].timeMs, controller.currentPlayheadTimeMs, "Next beat jumps to beat 2's line")
                automator.click(checkNotNull(automator.findOneByTestTag("ciacola-timeline-prev-beat")))
                settle()
                assertEquals(snapshot.eventBeats[0].timeMs, controller.currentPlayheadTimeMs, "Previous beat jumps back")

                val play = checkNotNull(automator.findOneByTestTag("ciacola-timeline-play"))
                automator.click(play)
                automator.waitUntil(description = "playback advances", timeout = 5.seconds) {
                    controller.currentPlayheadTimeMs > snapshot.eventBeats[0].timeMs + 300
                }
                assertTrue(controller.isPlaying, "Play icon starts playback")
                automator.click(play)
                settle()
                assertTrue(!controller.isPlaying, "Pause stops playback")
                val paused = controller.currentPlayheadTimeMs
                delay(400)
                assertEquals(paused, controller.currentPlayheadTimeMs, "Paused playhead stays put")
            }
        }

    @Test
    fun timelineAndPreviewFollowAfterEffectsZoomPanAndScrollGestures(): Unit =
        runSpectreTest(timeout = 120.seconds) {
            val studio = launchStudio()
            with(studio) {
                var canvas = openTimeline()
                val vp = controller.viewport
                onUi { controller.splitFraction = 0.66f }
                settle()
                canvas = automator.waitForNode(tag = "ciacola-timeline-canvas", timeout = 5.seconds)
                assertTrue(vp.verticalScroll.maxValue > 0, "Rows overflow so vertical scrolling is testable")

                // Plain wheel scrolls rows vertically (and the layer headers stay aligned).
                automator.scrollWheel(canvas, 3)
                settle()
                assertTrue(vp.verticalScroll.value > 0, "Plain wheel scrolls rows (scrollY=${vp.verticalScroll.value})")
                automator.scrollWheel(canvas, -10)
                settle()
                assertEquals(0, vp.verticalScroll.value)

                // Alt+wheel zooms time around the cursor.
                val cursor = canvas.center()
                val cursorX = ((cursor.x - checkNotNull(canvas.boundsOnScreen).x) * pixelScale()).toFloat()
                val timeUnderCursor = vp.timeForX(cursorX)
                val zoomBefore = vp.pxPerMs
                repeat(4) { awtWheel(cursor, KeyEvent.VK_ALT, -1) }
                settle()
                assertTrue(vp.pxPerMs > zoomBefore * 1.5f, "Alt+wheel zooms in (${vp.pxPerMs} vs $zoomBefore)")
                assertEquals(timeUnderCursor, vp.timeForX(cursorX), absoluteTolerance = 4f, message = "Time under the cursor stays put")

                // Shift+wheel scrolls time horizontally.
                val scrollBefore = vp.scrollX
                repeat(3) { awtWheel(cursor, KeyEvent.VK_SHIFT, 1) }
                settle()
                assertTrue(vp.scrollX > scrollBefore, "Shift+wheel scrolls later in time (${vp.scrollX} vs $scrollBefore)")

                // Middle-drag and Space+drag pan; a Space drag must not toggle playback.
                val panStart = vp.scrollX
                awtDrag(cursor, Point(cursor.x + 120, cursor.y), InputEvent.BUTTON2_DOWN_MASK)
                settle()
                assertTrue(vp.scrollX < panStart - 50, "Middle-drag pans (${vp.scrollX} vs $panStart)")
                val panStart2 = vp.scrollX
                awtKey(pressed = true, KeyEvent.VK_SPACE)
                delay(50)
                awtDrag(cursor, Point(cursor.x - 120, cursor.y), InputEvent.BUTTON1_DOWN_MASK)
                awtKey(pressed = false, KeyEvent.VK_SPACE)
                settle()
                assertTrue(vp.scrollX > panStart2 + 50, "Space+drag pans (${vp.scrollX} vs $panStart2)")
                assertTrue(!controller.isPlaying, "Space used for panning does not toggle playback")

                // Space tap toggles playback.
                awtKey(pressed = true, KeyEvent.VK_SPACE)
                awtKey(pressed = false, KeyEvent.VK_SPACE)
                automator.waitUntil(description = "Space starts playback", timeout = 3.seconds) { controller.isPlaying }
                awtKey(pressed = true, KeyEvent.VK_SPACE)
                awtKey(pressed = false, KeyEvent.VK_SPACE)
                automator.waitUntil(description = "Space pauses playback", timeout = 3.seconds) { !controller.isPlaying }

                // Keyboard zoom: = / - around the playhead, ; toggles fit and frame level.
                val z0 = vp.pxPerMs
                automator.pressKey(KeyEvent.VK_EQUALS)
                settle()
                assertTrue(vp.pxPerMs > z0, "= zooms in")
                automator.pressKey(KeyEvent.VK_MINUS)
                automator.pressKey(KeyEvent.VK_MINUS)
                settle()
                assertTrue(vp.pxPerMs < z0, "- zooms out")
                onUi { vp.zoomToFit() }
                automator.pressKey(KeyEvent.VK_SEMICOLON)
                settle()
                assertEquals(FRAME_LEVEL_PX_PER_MS, vp.pxPerMs, "; goes to frame level")
                val playheadX = vp.xForTime(controller.currentPlayheadTimeMs.toFloat())
                assertTrue(playheadX in 0f..vp.viewportWidthPx, "Playhead stays visible at frame level")
                automator.pressKey(KeyEvent.VK_SEMICOLON)
                settle()
                assertTrue(vp.isNearFit, "; returns to fit")

                // Compact icon buttons for zoom.
                val zoomIn = checkNotNull(automator.findOneByTestTag("ciacola-timeline-zoom-in"))
                assertTrue(checkNotNull(zoomIn.boundsOnScreen).height <= 28, "Zoom buttons are compact icon buttons")
                val z1 = vp.pxPerMs
                automator.click(zoomIn)
                settle()
                assertTrue(vp.pxPerMs > z1, "Zoom-in icon zooms")
                assertNotNull(automator.findOneByTestTag("ciacola-timeline-hscrollbar"))
                assertNotNull(automator.findOneByTestTag("ciacola-timeline-vscrollbar"))
                capture("02-timeline-zoomed.png")

                // Preview: wheel zooms at the cursor, Space/middle drag pans, . , / keys.
                val preview = automator.waitForNode(tag = "ciacola-preview-viewport", timeout = 5.seconds)
                val p = controller.preview
                val anchor = preview.center()
                automator.scrollWheel(preview, -3)
                settle()
                assertTrue(p.scale > 1.1f, "Wheel over the preview zooms in (scale=${p.scale})")
                assertNotNull(automator.findOneByTestTag("ciacola-preview-zoom-badge"), "Zoom badge shows while zoomed")
                val offsetBefore = p.offset
                awtDrag(anchor, Point(anchor.x + 80, anchor.y + 40), InputEvent.BUTTON2_DOWN_MASK)
                settle()
                assertTrue((p.offset - offsetBefore).getDistance() > 60f, "Middle-drag pans the preview")
                capture("03-preview-zoomed.png")
                automator.pressKey(KeyEvent.VK_SLASH)
                settle()
                assertTrue(p.isIdentity, "/ fits the preview")
                assertNull(automator.findOneByTestTag("ciacola-preview-zoom-badge"))
                automator.pressKey(KeyEvent.VK_PERIOD)
                settle()
                assertTrue(p.scale > 1f, ". zooms the preview in")
                automator.pressKey(KeyEvent.VK_COMMA)
                automator.pressKey(KeyEvent.VK_COMMA)
                settle()
                assertTrue(p.scale < 1f, ", zooms the preview out")
                automator.pressKey(KeyEvent.VK_SLASH)
                settle()

                // Ctrl+Click review still maps to the right slide element while the preview is zoomed.
                restOnBeat(sceneIndex = 4, frameIndex = 2)
                val stamp = stageText("IMMUTABLE")
                automator.scrollWheel(stamp, -3)
                automator.waitUntil(description = "preview zoomed at the stamp (scale=${p.scale})", timeout = 5.seconds) { p.scale > 1.1f }
                settle()
                val inspect = automator.waitForNode(tag = "ciacola-hud-toggle-inspect", timeout = 5.seconds)
                automator.click(inspect)
                automator.waitForNode(text = "Inspecting (F3)", timeout = 5.seconds)
                val zoomedStamp = stageText("IMMUTABLE")
                automator.click(zoomedStamp)
                automator.waitUntil(description = "review selection", timeout = 5.seconds) { review.pendingSelection != null }
                assertEquals("IMMUTABLE", review.pendingSelection?.target?.label, "Review hit-test honours the preview zoom")
                capture("04-review-on-zoomed-preview.png")
                awaitComposerFocus()
                automator.pressKey(KeyEvent.VK_ESCAPE)
                automator.waitUntil(description = "composer closed", timeout = 5.seconds) { review.pendingSelection == null }
                if (review.inspectModeEnabled) {
                    automator.click(checkNotNull(automator.findOneByTestTag("ciacola-hud-toggle-inspect")))
                }
                automator.waitUntil(description = "inspect mode off", timeout = 5.seconds) { !review.inspectModeEnabled }
            }
        }

    @Test
    fun selectingInTheTimelineHighlightsTheSlideAndClickingTheSlideSelectsTheTrack(): Unit =
        runSpectreTest(timeout = 120.seconds) {
            val studio = launchStudio()
            with(studio) {
                openTimeline()
                restOnBeat(sceneIndex = 4, frameIndex = 3)
                val overlayState = { automator.findOneByTestTag("ciacola-stage-selection-overlay")?.contentDescription.orEmpty() }

                // Timeline -> stage: clicking a layer highlights what it animates.
                val stampRow = automator.waitForNode(tag = "ciacola-track-row-lab-immutable-stamp", timeout = 5.seconds)
                automator.click(stampRow)
                automator.waitUntil(description = "stamp highlighted", timeout = 5.seconds) {
                    overlayState() == "selected=lab-immutable-stamp highlighted=1"
                }
                capture("05-timeline-selects-stamp.png")

                // Selecting a stagger group highlights each item; an item row highlights only itself.
                automator.click(automator.waitForNode(tag = "ciacola-track-row-lab-leader-stagger", timeout = 5.seconds))
                automator.waitUntil(description = "group highlighted", timeout = 5.seconds) {
                    overlayState().startsWith("selected=lab-leader-stagger ") &&
                        (overlayState().substringAfter("highlighted=").toIntOrNull() ?: 0) >= 3
                }

                // Compact Inspector steppers.
                automator.click(automator.waitForNode(tag = "ciacola-track-row-lab-immutable-stamp", timeout = 5.seconds))
                automator.waitUntil(description = "stamp selected", timeout = 5.seconds) {
                    controller.selectedExactTrackId == "lab-immutable-stamp"
                }
                settle()
                val durInc = automator.waitForNode(tag = "ciacola-inspector-dur-inc", timeout = 5.seconds)
                assertTrue(checkNotNull(durInc.boundsOnScreen).height <= 26, "Steppers are compact icon buttons")
                val before = overridesStore.data().tracks["lab-immutable-stamp"]?.durationMs
                automator.click(durInc)
                settle()
                val after = overridesStore.data().tracks["lab-immutable-stamp"]?.durationMs
                assertNotNull(after)
                assertTrue(before == null || after > before, "Duration stepper increments ($before -> $after)")
                capture("06-inspector-compact-steppers.png")

                // Stage -> timeline: a plain click picks the element under the pointer.
                val claim = stageText("Fix with SpanStyle(alpha)!")
                automator.click(claim)
                automator.waitUntil(description = "claim selected and highlighted", timeout = 5.seconds) {
                    controller.selectedExactTrackId == "lab-strikeout-loupe" &&
                        overlayState() == "selected=lab-strikeout-loupe highlighted=1"
                }
                val headers = checkNotNull(automator.findOneByTestTag("ciacola-timeline-layer-headers")?.boundsOnScreen)
                automator.waitUntil(description = "row revealed", timeout = 5.seconds) {
                    val r = automator.findOneByTestTag("ciacola-track-row-lab-strikeout-loupe")?.boundsOnScreen
                    r != null && r.y >= headers.y - 2 && r.y + r.height <= headers.y + headers.height + 2
                }

                automator.click(stageText("Compose"))
                automator.waitUntil(description = "stagger item selected", timeout = 5.seconds) {
                    controller.selectedExactTrackId == "lab-leader-stagger#item1"
                }
                capture("07-stage-selects-stagger-item.png")

                // Clicking empty stage (right of the eyebrow, above the cards) clears the selection.
                val eyebrowBounds = checkNotNull(stageText("KEYNOTE EFFECTS LAB / PROVISIONAL").boundsOnScreen)
                val emptyPoint = Point(eyebrowBounds.x + eyebrowBounds.width + 200, eyebrowBounds.y + eyebrowBounds.height / 2)
                assertTrue(checkNotNull(automator.findOneByTestTag("ciacola-preview-viewport")?.boundsOnScreen).contains(emptyPoint))
                awtDrag(emptyPoint, emptyPoint, InputEvent.BUTTON1_DOWN_MASK, steps = 1)
                automator.waitUntil(description = "selection cleared (overlay: ${overlayState()})", timeout = 5.seconds) {
                    controller.selectedExactTrackId == null && overlayState() == "selected=none highlighted=0"
                }

                // Inspect mode owns stage clicks: no timeline picking while reviewing.
                automator.click(automator.waitForNode(tag = "ciacola-hud-toggle-inspect", timeout = 5.seconds))
                automator.waitForNode(text = "Inspecting (F3)", timeout = 5.seconds)
                automator.click(stageText("Fix with SpanStyle(alpha)!"))
                automator.waitUntil(description = "review selection", timeout = 5.seconds) { review.pendingSelection != null }
                assertNull(controller.selectedExactTrackId, "Picking is suppressed while inspecting")
                awaitComposerFocus()
                automator.pressKey(KeyEvent.VK_ESCAPE)
                automator.waitUntil(description = "composer closed", timeout = 5.seconds) { review.pendingSelection == null }
            }
        }

    @Test
    fun beatsSubCompositionsAndReviewComposerWorkWithTheTimelineOpen(): Unit =
        runSpectreTest(timeout = 120.seconds) {
            val studio = launchStudio()
            with(studio) {
                openTimeline()
                capture("08-master-timeline.png")

                // 0-ms "+ Beat" / "- Beat" event keyframe editing without waiting for an agent.
                automator.click(automator.waitForNode(tag = "ciacola-timeline-add-beat", timeout = 5.seconds))
                automator.waitUntil(description = "Scene 1 beat count /4", timeout = 5.seconds) {
                    automator.findOneByTestTag("ciacola-timeline-timecode")?.text?.contains("/4") == true
                }
                assertTrue(overridesStore.data().sceneBeats[0]?.injectedAfterBeats?.isNotEmpty() == true)
                automator.click(automator.waitForNode(tag = "ciacola-timeline-remove-beat", timeout = 5.seconds))
                automator.waitUntil(description = "Scene 1 beat count /3", timeout = 5.seconds) {
                    automator.findOneByTestTag("ciacola-timeline-timecode")?.text?.contains("/3") == true
                }
                assertTrue(overridesStore.data().sceneBeats[0]?.injectedAfterBeats.isNullOrEmpty())

                // Enter the ambient loop sub-composition, change its period, scrub it, and leave.
                automator.click(automator.waitForNode(tag = "ciacola-timeline-open-loop", timeout = 5.seconds))
                val exit = automator.waitForNode(tag = "ciacola-timeline-exit-subcomp", timeout = 5.seconds)
                automator.waitForNode(tag = "ciacola-subcomp-canvas", timeout = 5.seconds)
                automator.waitForNode(text = "CONTINUOUS COMPOSE / PROVISIONAL", timeout = 5.seconds)
                automator.click(automator.waitForNode(tag = "ciacola-loop-period-inc", timeout = 5.seconds))
                onUi { controller.loopClock.scrubbedMillis = 2_150L }
                settle()
                assertEquals(6_500L, controller.loopClock.effectivePeriodMillis("foundation-mesh-loop", 6_000L))
                capture("09-loop-subcomposition.png")
                automator.click(exit)
                automator.waitForNode(tag = "ciacola-timeline-canvas", timeout = 5.seconds)
                assertNull(controller.loopClock.activeSubCompLoopId)

                // Review composer: typing (with spaces) is not eaten by timeline shortcuts; Escape closes.
                restOnBeat(sceneIndex = 0, frameIndex = 0)
                automator.click(automator.waitForNode(tag = "ciacola-hud-toggle-inspect", timeout = 5.seconds))
                automator.waitForNode(text = "Inspecting (F3)", timeout = 5.seconds)
                automator.click(automator.waitForNode(tag = "ciacola-review-stage", timeout = 5.seconds))
                val noteInput = automator.waitForNode(tag = "ciacola-review-note-input", timeout = 5.seconds)
                automator.click(noteInput)
                val zoomBefore = controller.viewport.pxPerMs
                automator.typeText("Spectre live typing check = ; .")
                settle()
                val typed = automator.findOneByTestTag("ciacola-review-note-input")?.let { it.editableText ?: it.text }.orEmpty()
                assertTrue(typed.contains("Spectre live typing check = ; ."), "Composer receives every key, got '$typed'")
                assertEquals(zoomBefore, controller.viewport.pxPerMs, "Typing does not trigger timeline zoom")
                assertTrue(!controller.isPlaying, "Typing a space does not start playback")
                capture("10-review-composer-over-timeline.png")
                automator.pressKey(KeyEvent.VK_ESCAPE)
                settle()
                assertNull(automator.findOneByTestTag("ciacola-review-note-input"))
                assertNotNull(automator.findOneByTestTag("ciacola-review-stage"))
                assertNotNull(automator.findOneByTestTag("ciacola-timeline-canvas"))
            }
        }
}
