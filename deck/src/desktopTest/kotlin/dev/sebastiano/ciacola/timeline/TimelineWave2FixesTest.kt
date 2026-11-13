package dev.sebastiano.ciacola.timeline

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.core.VectorConverter
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.Text
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import dev.bnorm.storyboard.Storyboard
import dev.bnorm.storyboard.easel.Easel
import dev.bnorm.storyboard.easel.overview.EaselOverview
import dev.bnorm.storyboard.easel.rememberAnimatic
import dev.sebastiano.ciacola.createFoundationDeck
import dev.sebastiano.ciacola.review.ReviewCommentStore
import dev.sebastiano.ciacola.review.ReviewConfig
import dev.sebastiano.ciacola.theme.DeckThemes
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class TimelineWave2FixesTest {

    @Test
    fun crossSlideBoundaryEventBeatsRecordMeasuredTransitionDurationInsteadOfZero() {
        val deck = createFoundationDeck(includeKeynoteEffectsLab = true)
        val snapshot = indexDeckTimeline(deck)
        val boundaryBeats = snapshot.eventBeats.filter { it.isSlideBoundary }
        assertEquals(4, boundaryBeats.size, "Expected 4 cross-slide boundary event beats across 5 slides")
        for (beat in boundaryBeats) {
            assertTrue(
                beat.segmentDurationMs > 0,
                "Cross-slide EventBeatLine at S${beat.sceneIndex + 1}:B${beat.frameIndex + 1} must record measured cross-slide duration > 0ms, got ${beat.segmentDurationMs}ms",
            )
        }
        assertTrue(
            boundaryBeats[0].segmentDurationMs >= 500,
            "S1 -> S2 shared-element cross-slide beat duration should reflect ~550ms morph, got ${boundaryBeats[0].segmentDurationMs}ms",
        )
    }

    @Test
    fun externalNavigationDuringPlaybackStopsPlaybackInsteadOfSnappingBackToNextSpan() = runComposeUiTest {
        val tempDir = Files.createTempDirectory("ciacola-playback-preempt")
        val store = TimelineOverridesStore(tempDir)
        val deck = createFoundationDeck(includeKeynoteEffectsLab = true)
        lateinit var controller: TimelineStudioController
        lateinit var uiScope: CoroutineScope

        mainClock.autoAdvance = false
        setContent {
            uiScope = rememberCoroutineScope()
            val loopClock = remember { DeckLoopClock() }
            val stageSelection = remember { TimelineStageSelection() }
            val motionPath = remember { MotionPathPreviewState() }
            val stageDecorator = rememberTimelineStageDecorator(
                loopClock = loopClock,
                motionPathPreview = motionPath,
                overridesStore = store,
                stageSelection = stageSelection,
            )
            val decoratedDeck = remember {
                createFoundationDeck(
                    includeKeynoteEffectsLab = true,
                    stageDecorator = stageDecorator,
                )
            }
            val animatic = rememberAnimatic(storyboard = { decoratedDeck })
            controller = rememberTimelineStudioController(
                animatic = animatic,
                overridesStore = store,
                loopClock = loopClock,
                motionPathPreview = motionPath,
                stageSelection = stageSelection,
            )
            controller.dockedInStoryWindow = true
            Box(Modifier.size(1280.dp, 800.dp)) {
                Easel(animatic)
            }
        }
        mainClock.advanceTimeByFrame()
        mainClock.advanceTimeBy(50)
        runOnIdle { controller.refreshSnapshot() }
        mainClock.advanceTimeByFrame()

        // 1. Start playback at 0ms (enters S1:B1 hold, then S1:B1->B2 transition), then jump externally to Slide 4 (3, 0).
        runOnIdle { controller.togglePlayback(uiScope) }
        mainClock.advanceTimeBy(500) // past 400ms hold, inside S1:B1 -> B2 transition
        assertTrue(controller.isPlaying, "Playback should be active before external navigation")

        runOnIdle {
            uiScope.launch {
                controller.animatic.jumpTo(Storyboard.Index(3, 0))
            }
        }
        mainClock.advanceTimeBy(1_200)

        assertFalse(controller.isPlaying, "External jumpTo (e.g. Esc Overview card click) must stop timeline playback")
        assertEquals(
            Storyboard.Index(3, 0),
            controller.animatic.currentIndex,
            "Stage must stay on Slide 4 after external jumpTo instead of snapping back to S1/S2 playback span",
        )

        // 2. Also verify preemption during a 400ms click-hold span!
        runOnIdle {
            uiScope.launch { controller.animatic.jumpTo(Storyboard.Index(0, 0)) }
        }
        mainClock.advanceTimeBy(200)
        runOnIdle {
            controller.scrubToTimeMs(0)
        }
        mainClock.advanceTimeBy(100)
        runOnIdle { controller.togglePlayback(uiScope) }
        mainClock.advanceTimeBy(120) // still inside 400ms click hold at S1:B1
        assertTrue(controller.isPlaying, "Playback should be active inside click hold")

        runOnIdle {
            uiScope.launch {
                controller.animatic.jumpTo(Storyboard.Index(2, 0))
            }
        }
        mainClock.advanceTimeBy(800)

        assertFalse(controller.isPlaying, "External jumpTo during a click hold must also stop playback")
        assertEquals(
            Storyboard.Index(2, 0),
            controller.animatic.currentIndex,
            "Stage must stay on Slide 3 after external jumpTo during click hold",
        )
    }

    @Test
    fun stagePreviewViewportSkipsWheelZoomWhenOverviewReplacesStoryStage() = runComposeUiTest {
        val tempDir = Files.createTempDirectory("ciacola-overview-wheel")
        val store = TimelineOverridesStore(tempDir)
        lateinit var controller: TimelineStudioController

        setContent {
            val loopClock = remember { DeckLoopClock() }
            val stageSelection = remember { TimelineStageSelection() }
            val motionPath = remember { MotionPathPreviewState() }
            val stageDecorator = rememberTimelineStageDecorator(
                loopClock = loopClock,
                motionPathPreview = motionPath,
                overridesStore = store,
                stageSelection = stageSelection,
            )
            val storyboard = remember {
                Storyboard.build(title = "WheelGuardDeck", decorator = stageDecorator) {
                    scene(frames = listOf(0, 1)) {
                        Box(Modifier.fillMaxSize()) { Text("Slide One") }
                    }
                    scene(frames = listOf(0)) {
                        Box(Modifier.fillMaxSize()) { Text("Slide Two") }
                    }
                }
            }
            val animatic = rememberAnimatic(storyboard = { storyboard })
            controller = rememberTimelineStudioController(
                animatic = animatic,
                overridesStore = store,
                loopClock = loopClock,
                motionPathPreview = motionPath,
                stageSelection = stageSelection,
            )
            controller.dockedInStoryWindow = true
            val studioWindow = rememberTimelineStudioWindow(controller = controller)
            Box(Modifier.size(1280.dp, 800.dp)) {
                studioWindow.decorator.decorate {
                    EaselOverview(animatic) {
                        Easel(animatic)
                    }
                }
            }
        }
        waitForIdle()

        // While Story stage is mounted, wheel over preview zooms the slide preview.
        val previewNode = onNodeWithTag("ciacola-preview-viewport", useUnmergedTree = true)
        previewNode.performMouseInput {
            moveTo(center)
            scroll(-2f)
        }
        waitForIdle()
        assertTrue(controller.preview.scale > 1.05f, "Wheel over Story stage must zoom preview while authoring")
        runOnIdle { controller.preview.reset() }
        waitForIdle()
        assertEquals(1f, controller.preview.scale)

        // Press Escape to open EaselOverview (unmounting Story Easel).
        onRoot().performKeyInput {
            keyDown(Key.Escape)
            keyUp(Key.Escape)
        }
        waitForIdle()
        mainClock.advanceTimeBy(500)
        waitForIdle()

        // Scrolling mouse wheel inside Esc Overview must NOT zoom StagePreviewViewport!
        previewNode.performMouseInput {
            moveTo(center)
            scroll(-3f)
        }
        waitForIdle()
        assertEquals(
            1f,
            controller.preview.scale,
            "Mouse wheel inside Esc Overview must scroll cards naturally instead of zooming StagePreviewViewport",
        )
    }

    @OptIn(ExperimentalSharedTransitionApi::class)
    @Test
    fun springAnimationSpecHonoursDelayAndArcBoundsTransformSamplesSpringPhysicsWithOvershoot() {
        val underdamped = TimelineCurveSpec(
            preset = "Spring",
            isSpring = true,
            dampingRatio = 0.35f,
            stiffness = 600f,
        )

        // 1. TimelineCurveSpec.toAnimationSpec must hold initialValue across delayMillis before starting spring motion.
        val floatSpec = underdamped.toAnimationSpec<Float>(durationMillis = 400, delayMillis = 200)
            .vectorize(Float.VectorConverter)
        val initialVec = Float.VectorConverter.convertToVector(0f)
        val targetVec = Float.VectorConverter.convertToVector(1f)
        val zeroVel = Float.VectorConverter.convertToVector(0f)

        val duringDelay = Float.VectorConverter.convertFromVector(
            floatSpec.getValueFromNanos(120_000_000L, initialVec, targetVec, zeroVel)
        )
        assertEquals(0f, duringDelay, absoluteTolerance = 0.001f, message = "Spring spec must stay at 0f during 200ms delay")

        val totalNanos = floatSpec.getDurationNanos(initialVec, targetVec, zeroVel)
        assertTrue(totalNanos > 250_000_000L, "Delayed spring total duration must exceed delay (got ${totalNanos / 1_000_000L}ms)")

        var peakFloat = 0f
        var sampleMs = 205L
        while (sampleMs * 1_000_000L <= totalNanos) {
            val v = Float.VectorConverter.convertFromVector(
                floatSpec.getValueFromNanos(sampleMs * 1_000_000L, initialVec, targetVec, zeroVel)
            )
            if (v > peakFloat) peakFloat = v
            sampleMs += 10L
        }
        assertTrue(peakFloat > 1.05f, "Underdamped spring must overshoot past 1.0 after delay (peak=$peakFloat)")

        // 2. createArcBoundsTransform with isSpring must honour delayMillis and sample FloatSpringSpec (overshooting target!).
        val startRect = Rect(0f, 0f, 100f, 100f)   // center.x = 50
        val endRect = Rect(500f, 0f, 600f, 100f)   // center.x = 550
        val boundsTransform = createArcBoundsTransform(
            durationMillis = 400,
            delayMillis = 150,
            curve = underdamped,
            arcBend = 0.35f,
        )
        val rectSpec = boundsTransform.createAnimationSpec(startRect, endRect).vectorize(Rect.VectorConverter)
        val startRVec = Rect.VectorConverter.convertToVector(startRect)
        val endRVec = Rect.VectorConverter.convertToVector(endRect)
        val zeroRVel = Rect.VectorConverter.convertToVector(Rect.Zero)

        val rectDuringDelay = Rect.VectorConverter.convertFromVector(
            rectSpec.getValueFromNanos(80_000_000L, startRVec, endRVec, zeroRVel)
        )
        assertEquals(50f, rectDuringDelay.center.x, absoluteTolerance = 0.5f, message = "Arc spring must stay at start during delay")

        val rectDurationNanos = rectSpec.getDurationNanos(startRVec, endRVec, zeroRVel)
        var maxCenterX = 50f
        var tMs = 155L
        while (tMs * 1_000_000L <= rectDurationNanos) {
            val r = Rect.VectorConverter.convertFromVector(
                rectSpec.getValueFromNanos(tMs * 1_000_000L, startRVec, endRVec, zeroRVel)
            )
            if (r.center.x > maxCenterX) maxCenterX = r.center.x
            tMs += 10L
        }
        assertTrue(
            maxCenterX > 560f,
            "Arc bounds transform with underdamped spring must sample FloatSpringSpec and overshoot past target center 550f (maxCenterX=$maxCenterX)",
        )
    }

    @Test
    fun inspectorExposesSpringControls2dSpatialOffsetsAndHidesInapplicableControlsByCategory() = runComposeUiTest {
        val tempDir = Files.createTempDirectory("ciacola-honest-inspector")
        val store = TimelineOverridesStore(tempDir)
        val deck = createFoundationDeck(includeKeynoteEffectsLab = true)
        lateinit var controller: TimelineStudioController

        setContent {
            val loopClock = remember { DeckLoopClock() }
            val animatic = rememberAnimatic(storyboard = { deck })
            controller = rememberTimelineStudioController(
                animatic = animatic,
                overridesStore = store,
                loopClock = loopClock,
            )
            Box(Modifier.size(Dp(1240f), Dp(720f))) {
                TimelineStudioPanel(controller = controller, isDark = true)
            }
        }
        waitForIdle()

        // 1. Build track: Trigger Beat, 2D Spatial (Offset X & Offset Y centered at 0dp by default), and Spring Damping/Stiffness!
        runOnIdle { controller.selectTrack("lab-immutable-stamp") }
        waitForIdle()
        onNodeWithTag("ciacola-inspector-beat-inc", useUnmergedTree = true).assertExists()
        onNodeWithTag("ciacola-inspector-offset-x-slider", useUnmergedTree = true).assertExists()
        onNodeWithTag("ciacola-inspector-offset-y-slider", useUnmergedTree = true).assertExists()
        onNodeWithText("Offset X: 0dp", substring = true, useUnmergedTree = true).assertExists()
        onNodeWithText("Offset Y: 0dp", substring = true, useUnmergedTree = true).assertExists()

        onNodeWithText("Spring", useUnmergedTree = true).performClick()
        waitForIdle()
        onNodeWithTag("ciacola-inspector-damping-dec", useUnmergedTree = true).assertExists()
        onNodeWithTag("ciacola-inspector-stiffness-inc", useUnmergedTree = true).assertExists()
        onNodeWithTag("ciacola-inspector-damping-dec", useUnmergedTree = true).performClick()
        onNodeWithTag("ciacola-inspector-stiffness-inc", useUnmergedTree = true).performClick()
        waitForIdle()
        val springCurve = store.data().tracks["lab-immutable-stamp"]?.curve
        assertTrue(springCurve != null && springCurve.isSpring, "Spring preset must activate isSpring")
        assertTrue(springCurve.dampingRatio < 0.78f, "Damping '-' stepper must decrease dampingRatio (got ${springCurve.dampingRatio})")
        assertTrue(springCurve.stiffness > 520f, "Stiffness '+' stepper must increase stiffness (got ${springCurve.stiffness})")

        // 2. StaggerGroup and StaggerItem: both Offset X and Offset Y exist.
        runOnIdle { controller.selectTrack("lab-leader-stagger#item0") }
        waitForIdle()
        onNodeWithTag("ciacola-inspector-offset-x-slider", useUnmergedTree = true).assertExists()
        onNodeWithTag("ciacola-inspector-offset-y-slider", useUnmergedTree = true).assertExists()

        // 3. SharedElement: Arc bend shown; Trigger Beat and Offset X/Y hidden!
        runOnIdle { controller.selectTrack("shared:foundation-shared-card") }
        waitForIdle()
        onNodeWithTag("ciacola-inspector-beat-inc", useUnmergedTree = true).assertDoesNotExist()
        onNodeWithTag("ciacola-inspector-arc-slider", useUnmergedTree = true).assertExists()
        onNodeWithTag("ciacola-inspector-offset-x-slider", useUnmergedTree = true).assertDoesNotExist()
        onNodeWithTag("ciacola-inspector-offset-y-slider", useUnmergedTree = true).assertDoesNotExist()

        // 4. WarpCode: Trigger Beat and Offset X/Y hidden!
        val warpRow = controller.snapshot.tracks.first { it.category == TrackCategory.WarpCode }
        runOnIdle { controller.selectTrack(warpRow.trackId) }
        waitForIdle()
        onNodeWithTag("ciacola-inspector-beat-inc", useUnmergedTree = true).assertDoesNotExist()
        onNodeWithTag("ciacola-inspector-offset-x-slider", useUnmergedTree = true).assertDoesNotExist()
        onNodeWithTag("ciacola-inspector-offset-y-slider", useUnmergedTree = true).assertDoesNotExist()

        // 5. MagicText & LineDraw: Offset X/Y hidden; MagicText also hides Trigger Beat (its beat is
        // the text state change itself, not a free build trigger).
        val magicRow = controller.snapshot.tracks.first { it.category == TrackCategory.MagicText }
        runOnIdle { controller.selectTrack(magicRow.trackId) }
        waitForIdle()
        onNodeWithTag("ciacola-inspector-beat-inc", useUnmergedTree = true).assertDoesNotExist()
        onNodeWithTag("ciacola-inspector-offset-x-slider", useUnmergedTree = true).assertDoesNotExist()
        onNodeWithTag("ciacola-inspector-offset-y-slider", useUnmergedTree = true).assertDoesNotExist()

        runOnIdle { controller.selectTrack("lab-warp-callout") }
        waitForIdle()
        onNodeWithTag("ciacola-inspector-offset-x-slider", useUnmergedTree = true).assertDoesNotExist()
        onNodeWithTag("ciacola-inspector-offset-y-slider", useUnmergedTree = true).assertDoesNotExist()

        // 6. Outer LoopSubComp (outside sub-comp mode): Enter Sub-Comp ▸ + Cycle Period stepper, NO dead Build timing steppers!
        val loopRow = controller.snapshot.tracks.first { it.category == TrackCategory.LoopSubComp }
        runOnIdle { controller.selectTrack(loopRow.trackId) }
        waitForIdle()
        onNodeWithTag("ciacola-inspector-enter-subcomp", useUnmergedTree = true).assertExists()
        onNodeWithTag("ciacola-loop-period-inc", useUnmergedTree = true).assertExists()
        onNodeWithTag("ciacola-inspector-beat-inc", useUnmergedTree = true).assertDoesNotExist()
        onNodeWithTag("ciacola-inspector-dur-inc", useUnmergedTree = true).assertDoesNotExist()
        onNodeWithTag("ciacola-inspector-delay-inc", useUnmergedTree = true).assertDoesNotExist()
        onNodeWithTag("ciacola-inspector-offset-x-slider", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun loopPeriodOverridesPersistReloadFormatForBakeResetAndUpdateLoopRowLabel() {
        val tempDir = Files.createTempDirectory("ciacola-loop-persist")
        val store = TimelineOverridesStore(tempDir)
        assertFalse(store.data().hasOverrides)

        store.updateLoopPeriod("foundation-mesh-loop", 8_500L)
        assertTrue(store.data().hasOverrides)

        val reloaded = TimelineOverridesStore(tempDir)
        assertEquals(8_500L, reloaded.data().loopPeriodsMs["foundation-mesh-loop"])
        assertTrue(reloaded.data().hasOverrides)

        val deck = createFoundationDeck(includeKeynoteEffectsLab = true, timelineOverrides = reloaded.data())
        val snapshot = indexDeckTimeline(deck, reloaded.data())
        val loopRow = snapshot.tracks.first { it.category == TrackCategory.LoopSubComp }
        assertTrue(
            loopRow.label.contains("8500ms"),
            "Loop row label in indexDeckTimeline must display overridden period (got '${loopRow.label}')",
        )
        assertTrue(
            loopRow.blocks.first().label.contains("8500ms"),
            "Loop block label must display overridden period (got '${loopRow.blocks.first().label}')",
        )

        val bakeText = reloaded.formatOverridesForAgentBake(tempDir)
        assertTrue(
            bakeText.contains("foundation-mesh-loop") && bakeText.contains("8500"),
            "formatOverridesForAgentBake must include loop period overrides (got:\n$bakeText)",
        )

        reloaded.resetLoopPeriod("foundation-mesh-loop")
        assertFalse(reloaded.data().hasOverrides)

        store.updateLoopPeriod("foundation-mesh-loop", 7_000L)
        store.resetAll()
        assertTrue(store.data().loopPeriodsMs.isEmpty())
        assertFalse(store.data().hasOverrides)
    }

    @Test
    fun studioRendersOverrideBadgesDistinctResetButtonsAndVirtualHoldBeatBanner() = runComposeUiTest {
        val tempDir = Files.createTempDirectory("ciacola-badges-hold")
        val store = TimelineOverridesStore(tempDir)
        lateinit var controller: TimelineStudioController
        lateinit var uiScope: CoroutineScope

        setContent {
            uiScope = rememberCoroutineScope()
            val loopClock = remember { DeckLoopClock() }
            val structuralRev = store.structuralRevision
            val paramRev = store.parameterRevision
            val currentOverrides = remember(structuralRev, paramRev) { store.data() }
            val deck = remember(structuralRev) {
                createFoundationDeck(
                    includeKeynoteEffectsLab = true,
                    timelineOverrides = currentOverrides,
                )
            }
            val animatic = rememberAnimatic(storyboard = { deck })
            controller = rememberTimelineStudioController(
                animatic = animatic,
                overridesStore = store,
                loopClock = loopClock,
            )
            controller.dockedInStoryWindow = true
            Box(Modifier.size(Dp(1280f), Dp(720f))) {
                Easel(animatic)
                TimelineStudioPanel(controller = controller, isDark = true)
            }
        }
        waitForIdle()

        // 1. Override badge on track row + Inspector + distinct 'Reset Track' vs 'Reset All' labels.
        runOnIdle { controller.selectTrack("lab-immutable-stamp") }
        waitForIdle()
        onNodeWithTag("ciacola-override-badge-lab-immutable-stamp", useUnmergedTree = true).assertDoesNotExist()
        onNodeWithTag("ciacola-inspector-override-badge", useUnmergedTree = true).assertDoesNotExist()

        onNodeWithTag("ciacola-inspector-dur-inc", useUnmergedTree = true).performClick()
        waitForIdle()

        onNodeWithTag("ciacola-override-badge-lab-immutable-stamp", useUnmergedTree = true).assertExists()
        onNodeWithTag("ciacola-inspector-override-badge", useUnmergedTree = true).assertExists()
        onNodeWithText("Reset All", useUnmergedTree = true).assertExists()
        onNodeWithText("Reset Track", useUnmergedTree = true).assertExists()

        onNodeWithTag("ciacola-inspector-reset-track", useUnmergedTree = true).performClick()
        waitForIdle()
        onNodeWithTag("ciacola-override-badge-lab-immutable-stamp", useUnmergedTree = true).assertDoesNotExist()

        // 2. Loop Cycle Period stepper persists into TimelineOverridesStore and lights up override badge + Reset All.
        val loopRow = controller.snapshot.tracks.first { it.category == TrackCategory.LoopSubComp }
        runOnIdle { controller.selectTrack(loopRow.trackId) }
        waitForIdle()
        onNodeWithTag("ciacola-loop-period-inc", useUnmergedTree = true).performClick()
        waitForIdle()
        assertEquals(6_500L, store.data().loopPeriodsMs["foundation-mesh-loop"])
        onNodeWithTag("ciacola-override-badge-${loopRow.trackId}", useUnmergedTree = true).assertExists()
        onNodeWithText("Reset All", useUnmergedTree = true).assertExists()

        // 3. Virtual Hold Beat banner appears when playhead rests on an injected virtual beat!
        onNodeWithTag("ciacola-virtual-hold-banner", useUnmergedTree = true).assertDoesNotExist()
        onNodeWithTag("ciacola-timeline-add-beat", useUnmergedTree = true).performClick()
        waitForIdle()
        runOnIdle { controller.refreshSnapshot() }
        waitForIdle()
        runOnIdle {
            uiScope.launch { controller.animatic.jumpTo(Storyboard.Index(0, 1)) }
        }
        waitForIdle()
        onNodeWithTag("ciacola-virtual-hold-banner", useUnmergedTree = true).assertExists()
        onNodeWithText(
            "Virtual Hold Beat — assign build tracks here, or Bake to Kotlin to add new text/code state",
            substring = true,
            useUnmergedTree = true,
        ).assertExists()
    }

    @Test
    fun bakeOverridesDispatchesDirectlyViaAgentApiWithoutReviewPinsAndInstructsClearingOverrides() = runComposeUiTest {
        val tempRoot = Files.createTempDirectory("ciacola-bake-direct")
        val storyboardDir = Files.createDirectories(tempRoot.resolve(".storyboard"))
        val store = TimelineOverridesStore(storyboardDir)
        val reviewStore = ReviewCommentStore(storyboardDir = storyboardDir, workspaceRoot = tempRoot)

        val capturedArgsFile = tempRoot.resolve("captured-agentapi.txt")
        val fakeAgentApi = tempRoot.resolve("fake-agentapi.sh")
        Files.writeString(
            fakeAgentApi,
            """
            #!/bin/sh
            printf '%s\n' "$@" > "${capturedArgsFile.toAbsolutePath()}"
            exit 0
            """.trimIndent() + "\n",
        )
        Files.setPosixFilePermissions(fakeAgentApi, PosixFilePermissions.fromString("rwxr-xr-x"))

        store.updateTrack("lab-immutable-stamp") { it.copy(durationMs = 680, delayMs = 75) }
        store.updateLoopPeriod("foundation-mesh-loop", 9_000L)
        store.insertBeatAfter(sceneIndex = 0, beatIndex = 0)

        val deck = createFoundationDeck(includeKeynoteEffectsLab = true, timelineOverrides = store.data())
        lateinit var controller: TimelineStudioController

        setContent {
            val loopClock = remember { DeckLoopClock() }
            val animatic = rememberAnimatic(storyboard = { deck })
            controller = rememberTimelineStudioController(
                animatic = animatic,
                overridesStore = store,
                loopClock = loopClock,
                bakeDispatcher = reviewStore.asBakeDispatcher(),
                bakeWorkspaceRoot = reviewStore.workspaceRoot,
                bakeGradleTaskHint = reviewStore.config.gradleTaskHint,
            )
            Box(Modifier.size(Dp(1280f), Dp(600f))) {
                TimelineStudioPanel(controller = controller, isDark = true)
            }
        }
        waitForIdle()

        // 1. Blank Conversation ID must surface inline Studio Error status pill and NEVER add a fake review pin!
        runOnIdle { controller.conversationIdProvider = { "" } }
        onNodeWithTag("ciacola-timeline-bake", useUnmergedTree = true).performClick()
        waitForIdle()

        assertTrue(reviewStore.comments.isEmpty(), "Bake must NEVER create fake SlideReviewComment pins at (960, 540)")
        assertEquals(TimelineBakeStatus.Error, controller.bakeStatus)
        onNodeWithTag("ciacola-timeline-bake-status", useUnmergedTree = true).assertExists()

        // 2. Configured Conversation ID dispatches directly via agentapi send-message, marks Sent, and still adds 0 review pins!
        runOnIdle {
            controller.conversationIdProvider = null
            reviewStore.saveConfig(
                ReviewConfig(
                    conversationId = "conv-bake-42",
                    agentApiBin = fakeAgentApi.toAbsolutePath().toString(),
                )
            )
        }
        onNodeWithTag("ciacola-timeline-bake", useUnmergedTree = true).performClick()
        waitUntil(conditionDescription = "Bake finishes sending via fake agentapi", timeoutMillis = 5_000) {
            controller.bakeStatus == TimelineBakeStatus.Sent
        }
        waitForIdle()

        assertTrue(reviewStore.comments.isEmpty(), "Bake must add zero review comments even on successful send")
        onNodeWithTag("ciacola-timeline-bake-status", useUnmergedTree = true).assertExists()

        val dispatched = Files.readString(capturedArgsFile)
        assertTrue(dispatched.contains("send-message"), "Must invoke agentapi send-message")
        assertTrue(dispatched.contains("conv-bake-42"), "Must target configured conversationId")
        assertTrue(dispatched.contains("lab-immutable-stamp"), "Prompt must include track overrides")
        assertTrue(dispatched.contains("foundation-mesh-loop"), "Prompt must include loop period overrides")
        assertTrue(
            dispatched.contains("timeline-overrides.json"),
            "Prompt must explicitly instruct removing baked entries from .storyboard/timeline-overrides.json so they are never applied twice",
        )
        assertTrue(
            dispatched.contains(":deck:reload"),
            "Prompt must instruct running :deck:reload to hot-reload Kotlin source",
        )
    }

    @Test
    fun undoRedoStackCoalescesContinuousDragsAndRestoresTrackBeatAndLoopMutations() {
        val tempDir = Files.createTempDirectory("ciacola-undo-redo")
        var fakeClockMs = 10_000L
        val store = TimelineOverridesStore(storyboardDir = tempDir, clockMillis = { fakeClockMs })
        assertFalse(store.canUndo)
        assertFalse(store.canRedo)

        // Step 1: Discrete stepper click.
        store.updateTrack("lab-immutable-stamp") { it.copy(durationMs = 450) }
        assertTrue(store.canUndo)
        assertFalse(store.canRedo)

        // Step 2: Rapid continuous slider drag within 250ms window -> must coalesce into ONE undo step!
        fakeClockMs += 500L
        for (delay in listOf(50, 75, 100, 125, 150, 175)) {
            fakeClockMs += 35L
            store.updateTrack("lab-immutable-stamp", coalesce = true) { it.copy(delayMs = delay) }
        }
        assertEquals(175, store.data().tracks["lab-immutable-stamp"]?.delayMs)

        // Step 3: Explicit drag gesture (beginEditGesture..endEditGesture) even across > 250ms pauses -> ONE undo step!
        fakeClockMs += 600L
        store.beginEditGesture()
        for (bend in listOf(0.2f, 0.4f, 0.6f, 0.8f)) {
            fakeClockMs += 300L
            store.updateTrack("shared:foundation-shared-card", coalesce = true) {
                it.copy(spatial = TimelineSpatialSpec(offsetXDp = 0f, offsetYDp = 0f, arcBend = bend))
            }
        }
        store.endEditGesture()
        assertEquals(0.8f, store.data().tracks["shared:foundation-shared-card"]?.spatial?.arcBend)

        // Undo 1 -> undoes entire Step 3 gesture!
        assertTrue(store.undo())
        assertFalse("shared:foundation-shared-card" in store.data().tracks, "Entire drag gesture must undo in 1 step")
        assertEquals(175, store.data().tracks["lab-immutable-stamp"]?.delayMs)
        assertTrue(store.canRedo)

        // Undo 2 -> undoes all 6 coalesced slider updates in Step 2!
        assertTrue(store.undo())
        assertEquals(null, store.data().tracks["lab-immutable-stamp"]?.delayMs, "Coalesced slider burst must undo in 1 step")
        assertEquals(450, store.data().tracks["lab-immutable-stamp"]?.durationMs)

        // Undo 3 -> undoes Step 1 back to empty overrides!
        assertTrue(store.undo())
        assertFalse(store.data().hasOverrides)
        assertFalse(store.canUndo)
        assertTrue(store.canRedo)

        // Redo all 3 steps!
        assertTrue(store.redo())
        assertEquals(450, store.data().tracks["lab-immutable-stamp"]?.durationMs)
        assertTrue(store.redo())
        assertEquals(175, store.data().tracks["lab-immutable-stamp"]?.delayMs)
        assertTrue(store.redo())
        assertEquals(0.8f, store.data().tracks["shared:foundation-shared-card"]?.spatial?.arcBend)
        assertFalse(store.canRedo)
    }

    @Test
    fun studioUndoRedoShortcutsToolbarButtonsAndCopyKotlinClipboardFlashWorkEndToEnd() = runComposeUiTest {
        val tempDir = Files.createTempDirectory("ciacola-copy-kotlin")
        val store = TimelineOverridesStore(tempDir)
        val deck = createFoundationDeck(includeKeynoteEffectsLab = true)
        lateinit var controller: TimelineStudioController
        var copiedSnippet = ""

        setContent {
            val loopClock = remember { DeckLoopClock() }
            val animatic = rememberAnimatic(storyboard = { deck })
            controller = rememberTimelineStudioController(
                animatic = animatic,
                overridesStore = store,
                loopClock = loopClock,
            )
            controller.dockedInStoryWindow = true
            controller.clipboardWriter = { copiedSnippet = it }
            val studioWindow = rememberTimelineStudioWindow(controller = controller)
            Box(Modifier.size(Dp(1280f), Dp(720f))) {
                studioWindow.Content()
            }
        }
        waitForIdle()

        runOnIdle {
            store.updateTrack("lab-immutable-stamp") { it.copy(durationMs = 620, delayMs = 90) }
            controller.updateLoopPeriod("foundation-mesh-loop", 7_500L)
        }
        waitForIdle()

        // 1. Toolbar Undo button undoes loop period edit; Redo button restores it.
        onNodeWithTag("ciacola-timeline-undo", useUnmergedTree = true).performClick()
        waitForIdle()
        assertFalse("foundation-mesh-loop" in store.data().loopPeriodsMs)

        onNodeWithTag("ciacola-timeline-redo", useUnmergedTree = true).performClick()
        waitForIdle()
        assertEquals(7_500L, store.data().loopPeriodsMs["foundation-mesh-loop"])

        // 2. Keyboard Cmd+Z / Ctrl+Z undoes, Cmd+Shift+Z and Ctrl+Y redo!
        onRoot().performKeyInput {
            keyDown(Key.MetaLeft)
            keyDown(Key.Z)
            keyUp(Key.Z)
            keyUp(Key.MetaLeft)
        }
        waitForIdle()
        assertFalse("foundation-mesh-loop" in store.data().loopPeriodsMs, "Cmd+Z must undo")

        onRoot().performKeyInput {
            keyDown(Key.MetaLeft)
            keyDown(Key.ShiftLeft)
            keyDown(Key.Z)
            keyUp(Key.Z)
            keyUp(Key.ShiftLeft)
            keyUp(Key.MetaLeft)
        }
        waitForIdle()
        assertEquals(7_500L, store.data().loopPeriodsMs["foundation-mesh-loop"], "Cmd+Shift+Z must redo")

        onRoot().performKeyInput {
            keyDown(Key.CtrlLeft)
            keyDown(Key.Z)
            keyUp(Key.Z)
            keyUp(Key.CtrlLeft)
        }
        waitForIdle()
        assertFalse("foundation-mesh-loop" in store.data().loopPeriodsMs, "Ctrl+Z must undo")

        onRoot().performKeyInput {
            keyDown(Key.CtrlLeft)
            keyDown(Key.Y)
            keyUp(Key.Y)
            keyUp(Key.CtrlLeft)
        }
        waitForIdle()
        assertEquals(7_500L, store.data().loopPeriodsMs["foundation-mesh-loop"], "Ctrl+Y must redo")

        // 3. Copy Kotlin button formats Kotlin modifier/parameter snippet + JSON summary to clipboard and flashes "Copied!".
        onNodeWithTag("ciacola-timeline-copy-kotlin", useUnmergedTree = true).performClick()
        waitForIdle()
        assertTrue(copiedSnippet.contains("lab-immutable-stamp"), "Copied snippet must include track id")
        assertTrue(copiedSnippet.contains("620"), "Copied snippet must include durationMillis = 620")
        assertTrue(copiedSnippet.contains("7500") || copiedSnippet.contains("7_500"), "Copied snippet must include loop period")
        assertTrue(copiedSnippet.contains("\"tracks\""), "Copied snippet must include JSON summary")
        onNodeWithText("Copied!", useUnmergedTree = true).assertExists()
    }

    @Test
    fun studioContrastMeetsWcagTargetsInKeynoteLightAndStageDark() = runComposeUiTest {
        // 1. Keynote Light & Stage Dark bar label ink contrast >= 4.5:1 across EVERY TrackCategory!
        for (isDark in listOf(false, true)) {
            val palette = buildStudioCanvasPaletteForTheme(isDark = isDark)
            for (category in TrackCategory.entries) {
                val rawFill = palette.colorForCategory(category)
                val barAlpha = if (category == TrackCategory.StaggerItem) 0.74f else 0.90f
                val compositedFill = compositeColorOver(rawFill.copy(alpha = barAlpha), palette.bg)
                val ink = barLabelInkForFill(compositedFill)
                val ratio = wcagContrastRatio(ink, compositedFill)
                assertTrue(
                    ratio >= 4.5f,
                    "Bar label contrast for $category (isDark=$isDark) must be >= 4.5:1, got ${"%.2f".format(ratio)}:1",
                )
            }

            // 2. Selected-bar outer border contrast >= 4.5:1 against canvas background!
            val selRatio = wcagContrastRatio(palette.selectedBarOuterRing, palette.bg)
            assertTrue(
                selRatio >= 4.5f,
                "Selected bar outer border contrast (isDark=$isDark) must be >= 4.5:1 against bg, got ${"%.2f".format(selRatio)}:1",
            )

            // 3. Secondary layer labels & muted ruler captions contrast >= 4.5:1 against both panel bg and headerBg!
            val secOnPanel = wcagContrastRatio(palette.textSecondary, palette.bg)
            val secOnHeader = wcagContrastRatio(palette.textSecondary, palette.headerBg)
            assertTrue(
                secOnPanel >= 4.5f,
                "Secondary studio text on panel bg (isDark=$isDark) must be >= 4.5:1, got ${"%.2f".format(secOnPanel)}:1",
            )
            assertTrue(
                secOnHeader >= 4.5f,
                "Secondary studio text on ruler headerBg (isDark=$isDark) must be >= 4.5:1, got ${"%.2f".format(secOnHeader)}:1",
            )
        }

        // 4. MotionArcStageOverlay strokes with the theme's motionPathPreview token: >= 3.5:1 against
        // each preset's stage background.
        for (theme in listOf(DeckThemes.stageDark(), DeckThemes.keynoteLight())) {
            val arcRatio = wcagContrastRatio(theme.colors.motionPathPreview, theme.colors.background)
            assertTrue(
                arcRatio >= 3.5f,
                "MotionArcStageOverlay stroke must be >= 3.5:1 against ${theme.colors.background}, got ${"%.2f".format(arcRatio)}:1",
            )
        }

        // 5. Smoke-render TimelineStudioPanel in Keynote Light (isDark = false) with a selected track.
        val tempDir = Files.createTempDirectory("ciacola-wcag-light")
        val store = TimelineOverridesStore(tempDir)
        val deck = createFoundationDeck(includeKeynoteEffectsLab = true)
        lateinit var controller: TimelineStudioController
        setContent {
            val loopClock = remember { DeckLoopClock() }
            val animatic = rememberAnimatic(storyboard = { deck })
            controller = rememberTimelineStudioController(
                animatic = animatic,
                overridesStore = store,
                loopClock = loopClock,
            )
            Box(Modifier.size(Dp(1280f), Dp(600f))) {
                TimelineStudioPanel(controller = controller, isDark = false)
            }
        }
        waitForIdle()
        runOnIdle { controller.selectTrack("lab-warp-callout") }
        waitForIdle()
        onNodeWithTag("ciacola-timeline-canvas", useUnmergedTree = true).assertExists()
    }

    @Test
    fun playheadScrubTracksPointerWithoutSnappingToBeatsAndScrubsAnimationsProportionally() = runComposeUiTest {
        mainClock.autoAdvance = false
        val tempDir = Files.createTempDirectory("ciacola-scrub-sync")
        val store = TimelineOverridesStore(tempDir)
        val deck = createFoundationDeck(includeKeynoteEffectsLab = true)
        lateinit var controller: TimelineStudioController
        lateinit var animatic: dev.bnorm.storyboard.easel.Animatic

        setContent {
            val loopClock = remember { DeckLoopClock() }
            animatic = rememberAnimatic(storyboard = { deck })
            controller = rememberTimelineStudioController(
                animatic = animatic,
                overridesStore = store,
                loopClock = loopClock,
            )
            Box(Modifier.size(Dp(1280f), Dp(720f))) {
                dev.bnorm.storyboard.easel.Easel(animatic)
                TimelineStudioPanel(controller = controller, isDark = true)
            }
        }
        mainClock.advanceTimeBy(400)

        // Pick an animated segment on Slide 5 (e.g. IMMUTABLE stamp build)
        val segSpan = controller.snapshot.stateSpans.first { it.sceneIndex == 4 && !it.isHold && it.durationMs > 100 }
        val midTimeMs = segSpan.startMs + segSpan.durationMs / 2

        // Issue a scrub request and inspect currentPlayheadTimeMs on EVERY intermediate frame
        // while runScrubWorker() is still applying the seek: it must stay at midTimeMs, never
        // snapping backward to segSpan.startMs (the beat diamond)!
        runOnIdle { controller.scrubToTimeMs(midTimeMs) }
        assertEquals(
            midTimeMs,
            controller.currentPlayheadTimeMs,
            "Playhead must immediately reflect requested scrub time without snapping to beat start (${segSpan.startMs})",
        )
        repeat(12) { frame ->
            mainClock.advanceTimeByFrame()
            assertEquals(
                midTimeMs,
                controller.currentPlayheadTimeMs,
                "Frame $frame while seeking: playhead snapped away from $midTimeMs to ${controller.currentPlayheadTimeMs}",
            )
        }

        // Now scrub VERY slowly (25% -> 50% -> 75% with 200ms pauses between steps while holding drag):
        // animation fraction must stay frozen at 25%, 50%, 75% (never running at normal speed on its own!).
        for (targetFrac in listOf(0.25f, 0.50f, 0.75f)) {
            val tMs = segSpan.startMs + (segSpan.durationMs * targetFrac).toInt()
            runOnIdle { controller.scrubToTimeMs(tMs) }
            repeat(8) { mainClock.advanceTimeByFrame() }
            assertEquals(targetFrac, animatic.activeFraction, absoluteTolerance = 0.06f)
            // Hold pointer still for 300ms: animation MUST NOT advance on its own!
            mainClock.advanceTimeBy(300)
            assertEquals(targetFrac, animatic.activeFraction, absoluteTolerance = 0.06f)
            assertEquals(tMs, controller.currentPlayheadTimeMs)
        }
    }
}
