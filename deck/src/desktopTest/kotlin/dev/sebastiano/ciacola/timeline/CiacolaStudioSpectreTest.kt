package dev.sebastiano.ciacola.timeline

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.bnorm.storyboard.Storyboard
import dev.bnorm.storyboard.easel.Easel
import dev.bnorm.storyboard.easel.overview.EaselOverview
import dev.bnorm.storyboard.easel.rememberAnimatic
import dev.bnorm.storyboard.plus
import dev.sebastiano.ciacola.createFoundationDeck
import dev.sebastiano.ciacola.rememberHotReloadStoryboard
import dev.sebastiano.ciacola.review.ReviewCommentStore
import dev.sebastiano.ciacola.review.rememberReviewStageCoordinator
import dev.sebastiano.ciacola.review.rememberSlideReviewWindow
import dev.sebastiano.ciacola.theme.DeckThemePreset
import dev.sebastiano.ciacola.theme.DeckThemes
import dev.sebastiano.spectre.core.ComposeAutomator
import dev.sebastiano.spectre.core.RobotDriver
import dev.sebastiano.spectre.testing.ComposeAutomatorRule
import dev.sebastiano.spectre.testing.runSpectreTest
import java.awt.Component
import java.awt.Container
import java.awt.EventQueue
import java.awt.Rectangle
import java.awt.Robot
import java.awt.event.KeyEvent
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.imageio.ImageIO
import kotlin.concurrent.thread
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
 * Real-window end-to-end verification of Ciacola's Studio (Slide Stage + Jewel Ctrl-Click Review +
 * After Effects Timeline + Sub-Compositions + 0-ms Event Keyframes + Live Inspector) driven by
 * Spectre 0.9.0 on JetBrains Runtime 25.
 */
class CiacolaStudioSpectreTest {

    private val windowRef = AtomicReference<ComposeWindow?>(null)
    private val rootLayerRef = AtomicReference<GraphicsLayer?>(null)
    private val uiScopeRef = AtomicReference<CoroutineScope?>(null)
    private var appThread: Thread? = null

    @get:Rule
    val automatorRule = ComposeAutomatorRule(
        factory = {
            ComposeAutomator.inProcess(
                robotDriver = RobotDriver.synthetic(),
            )
        }
    )

    private suspend fun <T> runOnComposeUi(block: suspend () -> T): T {
        val scope = checkNotNull(uiScopeRef.get()) { "Compose UI scope not initialized" }
        val deferred = CompletableDeferred<T>()
        scope.launch {
            runCatching { block() }.fold(deferred::complete, deferred::completeExceptionally)
        }
        return deferred.await()
    }

    private fun findSkiaLayer(root: Component?): SkiaLayer? {
        if (root == null) return null
        if (root is SkiaLayer) return root
        if (root is Container) {
            for (child in root.components) {
                val found = findSkiaLayer(child)
                if (found != null) return found
            }
        }
        return null
    }

    private suspend fun captureStudioPng(outDir: File?, fileName: String) {
        if (outDir == null) return
        val outFile = File(outDir, fileName)
        delay(80)
        val rawImage: BufferedImage = runOnComposeUi {
            val win = windowRef.get()
            win?.repaint()
            val skiaImg = runCatching { findSkiaLayer(win)?.screenshot()?.toBufferedImage() }.getOrNull()
            skiaImg ?: rootLayerRef.get()?.toImageBitmap()?.toAwtImage()
                ?: error("Neither SkiaLayer nor GraphicsLayer available")
        }
        val argbImage = BufferedImage(rawImage.width, rawImage.height, BufferedImage.TYPE_INT_ARGB)
        val g = argbImage.createGraphics()
        try {
            g.drawImage(rawImage, 0, 0, null)
        } finally {
            g.dispose()
        }
        ImageIO.write(argbImage, "png", outFile)
    }

    @AfterTest
    fun tearDownWindow() {
        val win = windowRef.getAndSet(null)
        rootLayerRef.set(null)
        uiScopeRef.set(null)
        if (win != null) {
            EventQueue.invokeLater {
                runCatching { win.dispose() }
            }
        }
        appThread?.join(2_000)
    }

    @Test
    fun realWindowTimelineScrubbingZeroLatencyBeatsInspectorTweaksSubCompsAndReviewPopupWorkEndToEnd(): Unit =
        runSpectreTest(timeout = 90.seconds) {
            val tempRoot = Files.createTempDirectory("ciacola-spectre-e2e")
            val storyboardDir = tempRoot.resolve(".storyboard").also { Files.createDirectories(it) }
            val captureOutDir = System.getenv("CIACOLA_SPECTRE_CAPTURE_DIR")
                ?.takeIf { it.isNotBlank() }
                ?.let(::File)
                ?.apply { mkdirs() }

            val readyLatch = CountDownLatch(1)
            val timelineControllerRef = AtomicReference<TimelineStudioController?>(null)
            val overridesStore = TimelineOverridesStore(storyboardDir = storyboardDir)
            val reviewStore = ReviewCommentStore(storyboardDir = storyboardDir, workspaceRoot = tempRoot)

            appThread = thread(name = "ciacola-spectre-host", isDaemon = true) {
                application(exitProcessOnExit = false) {
                    val preset = DeckThemePreset.StageDark
                    val hostTheme = remember { DeckThemes.forPreset(preset) }
                    val loopClock = remember { DeckLoopClock() }
                    val motionPathPreview = remember { MotionPathPreviewState() }
                    val reviewStageCoordinator = rememberReviewStageCoordinator()
                    val structuralRev = overridesStore.structuralRevision
                    val paramRev = overridesStore.parameterRevision
                    val currentOverrides = remember(structuralRev, paramRev) { overridesStore.data() }
                    val timelineStageDecorator = rememberTimelineStageDecorator(
                        loopClock = loopClock,
                        motionPathPreview = motionPathPreview,
                        overridesStore = overridesStore,
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
                    )
                    timelineControllerRef.set(timelineController)

                    val timelineWindow = rememberTimelineStudioWindow(
                        controller = timelineController,
                        currentPresetProvider = { preset },
                        suppressShortcutsProvider = {
                            reviewStageCoordinator.pendingSelection != null ||
                                reviewStageCoordinator.selectedPinCommentId != null
                        },
                    )
                    val reviewWindow = rememberSlideReviewWindow(
                        animatic = animatic,
                        store = reviewStore,
                        stageCoordinator = reviewStageCoordinator,
                        currentPresetProvider = { preset },
                        timelineDockedProvider = { timelineController.dockedInStoryWindow },
                        onToggleTimeline = {
                            timelineController.dockedInStoryWindow = !timelineController.dockedInStoryWindow
                        },
                    )
                    val fullDecorator = timelineWindow.decorator + reviewWindow.decorator

                    MaterialTheme(colors = hostTheme.toMaterialColors()) {
                        Window(
                            onCloseRequest = ::exitApplication,
                            title = "Ciacola - Story",
                            state = rememberWindowState(
                                position = WindowPosition(80.dp, 80.dp),
                                size = DpSize(1360.dp, 860.dp),
                            ),
                        ) {
                            val uiScope = rememberCoroutineScope()
                            uiScopeRef.set(uiScope)
                            val rootGraphicsLayer = rememberGraphicsLayer()
                            rootLayerRef.set(rootGraphicsLayer)
                            if (windowRef.compareAndSet(null, window)) {
                                readyLatch.countDown()
                            }
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .drawWithContent {
                                        rootGraphicsLayer.record {
                                            this@drawWithContent.drawContent()
                                        }
                                        drawLayer(rootGraphicsLayer)
                                    },
                            ) {
                                fullDecorator.decorate {
                                    EaselOverview(animatic) {
                                        Easel(animatic)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            assertTrue(readyLatch.await(15, TimeUnit.SECONDS), "Timed out waiting for ComposeWindow")
            val automator = automatorRule.automator

            // 1. Wait for Story stage + HUD button, then toggle docked After Effects Timeline Studio open!
            val toggleBtn = automator.waitForNode(tag = "ciacola-hud-toggle-timeline", timeout = 10.seconds)
            automator.waitForVisualIdle()
            automator.click(toggleBtn)

            automator.waitForNode(tag = "ciacola-timeline-canvas", timeout = 5.seconds)
            automator.waitForVisualIdle()
            captureStudioPng(captureOutDir, "01-master-timeline.png")

            // 2. Test 0-ms "+ Beat" & "- Beat" event keyframe editing without agent wait!
            // Timeline MUST remain open and preserve state across Storyboard structural rebuild!
            val addBeatBtn = automator.waitForNode(tag = "ciacola-timeline-add-beat", timeout = 5.seconds)
            automator.click(addBeatBtn)
            automator.waitUntil(description = "Scene 1 beat count /4", timeout = 5.seconds) {
                automator.findOneByTestTag("ciacola-timeline-timecode")?.text?.contains("/4") == true
            }
            assertTrue(
                overridesStore.data().sceneBeats[0]?.injectedAfterBeats?.isNotEmpty() == true,
                "Expected 0-ms injected beat in overrides store",
            )
            captureStudioPng(captureOutDir, "02-injected-event-beat.png")

            val removeBeatBtn = automator.waitForNode(tag = "ciacola-timeline-remove-beat", timeout = 5.seconds)
            automator.click(removeBeatBtn)
            automator.waitUntil(description = "Scene 1 beat count /3", timeout = 5.seconds) {
                automator.findOneByTestTag("ciacola-timeline-timecode")?.text?.contains("/3") == true
            }
            assertTrue(
                overridesStore.data().sceneBeats[0]?.injectedAfterBeats.isNullOrEmpty(),
                "Expected injected beat removed cleanly",
            )

            // 3. Select the cross-slide Shared Element track, tweak duration & curved arc bend, and scrub!
            val sharedTrackRow = automator.waitForNode(
                tag = "ciacola-track-row-shared:foundation-shared-card",
                timeout = 5.seconds,
            )
            automator.click(sharedTrackRow)
            automator.waitForNode(tag = "ciacola-inspector-dur-inc", timeout = 5.seconds)
            val durIncBtn = automator.findOneByTestTag("ciacola-inspector-dur-inc")!!
            automator.click(durIncBtn)
            automator.waitForVisualIdle()
            assertNotNull(
                overridesStore.data().tracks["shared:foundation-shared-card"]?.durationMs,
                "Expected live duration override written by Inspector",
            )

            // Apply a noticeable upward spatial arc bend (+0.55f) and scrub mid-flight on Slide 2!
            overridesStore.updateTrack("shared:foundation-shared-card") {
                it.copy(spatial = TimelineSpatialSpec(arcBend = 0.55f))
            }
            val controller = timelineControllerRef.get()!!
            runOnComposeUi {
                val s1State = controller.animatic.stateDescriptors.first {
                    it.sceneIndex == 1 && it.frameIndex == 0 && !it.isBoundaryStart && !it.isBoundaryEnd
                }
                controller.animatic.seekToSegment(s1State.stateIndex, 0.45f)
            }
            automator.waitForVisualIdle()
            val sharedStageNode = automator.waitForNode(text = "shared", timeout = 5.seconds)
            assertNotNull(sharedStageNode)
            captureStudioPng(captureOutDir, "03-inspector-bezier-and-arc-stage.png")

            // 4. Jump to Slide 5 (Keynote Effects Lab) mid-LineDraw/Warp morph to inspect tracks + stage!
            runOnComposeUi {
                val s4Beat2State = controller.animatic.stateDescriptors.first {
                    it.sceneIndex == 4 && it.frameIndex == 2 && !it.isBoundaryStart && !it.isBoundaryEnd
                }
                controller.animatic.seekToSegment(s4Beat2State.stateIndex, 0.65f)
            }
            automator.waitForVisualIdle()
            val staggerRow = automator.findOneByTestTag("ciacola-track-row-lab-leader-stagger")
            if (staggerRow != null) {
                automator.click(staggerRow)
                automator.waitForVisualIdle()
            }
            captureStudioPng(captureOutDir, "04-keynote-lab-stagger-tracks.png")

            // 5. Enter the Ambient Vertex Mesh Loop Sub-Composition ("go in" the loop)!
            val openLoopBtn = automator.waitForNode(tag = "ciacola-timeline-open-loop", timeout = 5.seconds)
            automator.click(openLoopBtn)
            val exitSubCompBtn = automator.waitForNode(tag = "ciacola-timeline-exit-subcomp", timeout = 5.seconds)
            automator.waitForNode(tag = "ciacola-subcomp-canvas", timeout = 5.seconds)
            automator.waitForNode(text = "CONTINUOUS COMPOSE / PROVISIONAL", timeout = 5.seconds)
            val periodIncBtn = automator.waitForNode(tag = "ciacola-loop-period-inc", timeout = 5.seconds)
            automator.click(periodIncBtn)
            runOnComposeUi {
                controller.loopClock.scrubbedMillis = 2_150L
            }
            automator.waitForVisualIdle()
            assertEquals(6_500L, controller.loopClock.effectivePeriodMillis("foundation-mesh-loop", 6_000L))
            captureStudioPng(captureOutDir, "05-loop-subcomposition.png")

            // Exit back to Master Deck (Slide 3's ambient loop resumes 60fps animation on exit!)
            automator.click(exitSubCompBtn)
            val canvasAfterExit = automator.waitForNode(tag = "ciacola-timeline-canvas", timeout = 5.seconds)
            automator.waitForIdle()

            // Verify real mouse pointer scrubbing on the After Effects timeline ruler!
            val rulerBounds = canvasAfterExit.boundsOnScreen
            if (rulerBounds != null) {
                val rulerY = rulerBounds.y + 18
                automator.swipe(
                    startX = rulerBounds.x + 30,
                    startY = rulerY,
                    endX = rulerBounds.x + 260,
                    endY = rulerY,
                    steps = 8,
                    duration = 160.milliseconds,
                )
                automator.waitForIdle()
                assertTrue(
                    controller.currentPlayheadTimeMs > 200,
                    "Expected mouse drag across timeline ruler to scrub playhead, got ${controller.currentPlayheadTimeMs}ms",
                )
            }

            // Scrub back to Slide 1 Beat 1 so the stage is settled for Inspect Mode popup testing
            runOnComposeUi {
                controller.animatic.seekToSegment(0, 0f)
            }
            automator.waitForNode(text = "FOUNDATION / PROVISIONAL", timeout = 5.seconds)
            automator.waitForVisualIdle()

            // 6. Verify Inspect Mode toggle -> stage click -> real Spectre typing WITH SPACES inside Jewel PopupContainer -> Esc!
            val inspectHudToggle = automator.waitForNode(tag = "ciacola-hud-toggle-inspect", timeout = 5.seconds)
            automator.click(inspectHudToggle)
            automator.waitForNode(text = "Inspecting (F3)", timeout = 5.seconds)
            val stageNode = automator.waitForNode(tag = "ciacola-review-stage", timeout = 5.seconds)
            automator.click(stageNode)

            val noteInput = automator.waitForNode(tag = "ciacola-review-note-input", timeout = 5.seconds)
            automator.click(noteInput)
            automator.typeText("Spectre live typing check")
            automator.waitForVisualIdle()

            val typedNode = automator.findOneByTestTag("ciacola-review-note-input")
            val observedTyped = typedNode?.editableText ?: typedNode?.text ?: ""
            assertTrue(
                observedTyped.contains("Spectre live typing check"),
                "Expected full phrase with spaces inside Jewel TextArea, got '$observedTyped'",
            )
            // Capture full window including Jewel PopupContainer via AWT composite / GraphicsLayer
            captureStudioPng(captureOutDir, "06-review-composer-over-timeline.png")

            // Press Escape: popup must close AND Story stage + Timeline must remain active (NOT overview grid)!
            automator.pressKey(KeyEvent.VK_ESCAPE)
            automator.waitForVisualIdle()
            assertNull(automator.findOneByTestTag("ciacola-review-note-input"))
            assertNotNull(automator.findOneByTestTag("ciacola-review-stage"))
            assertNotNull(automator.findOneByTestTag("ciacola-timeline-canvas"))
        }
}
