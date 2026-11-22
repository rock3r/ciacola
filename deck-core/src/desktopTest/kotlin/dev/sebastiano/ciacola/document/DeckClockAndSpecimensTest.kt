package dev.sebastiano.ciacola.document

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.bnorm.storyboard.AdvanceDirection
import dev.bnorm.storyboard.ContentDecorator
import dev.bnorm.storyboard.Frame
import dev.bnorm.storyboard.Scene
import dev.bnorm.storyboard.SceneFormat
import dev.bnorm.storyboard.Storyboard
import dev.bnorm.storyboard.easel.Animatic
import dev.bnorm.storyboard.easel.Easel
import dev.bnorm.storyboard.easel.LocalExportEmittedFrameIndex
import dev.bnorm.storyboard.easel.LocalExportFps
import dev.bnorm.storyboard.easel.SceneMode
import dev.bnorm.storyboard.easel.ScenePreview
import dev.bnorm.storyboard.easel.export.StoryboardVideoExporter
import dev.bnorm.storyboard.easel.rememberAnimatic
import dev.sebastiano.ciacola.theme.DeckTheme
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo

/**
 * Tests for document clock, specimen registry, and render session isolation:
 * - Canonical [DeckClock] mapping, real Compose UI tests with [Storyboard] + [Easel] +
 *   [rememberDocIntraCarrier] + [rememberDocCrossCarrier] (symmetric backward durations),
 *   [ScenePreview], concurrent-mount isolation, and [AutoAdvanceController].
 * - Static [deckSpecimens] declarations, parameter validation, overshoot-clamped interpolation,
 *   and beat-reset semantics.
 * - [RenderSession] Present/Still/Video isolation, retained isolation, single loop-time
 *   producer, and end-to-end [StoryboardVideoExporter] deterministic export.
 */
@OptIn(ExperimentalTestApi::class)
class DeckClockAndSpecimensTest {

    private fun threeSlideDoc(): DeckDocument = DeckDocument(
        id = "clock-deck",
        loopPeriodsMs = mapOf("shader/ambient" to 2000L),
        slides = listOf(
            SlideSpec(
                id = "s0",
                beats = listOf(BeatSpec(autoAdvanceMs = 0), BeatSpec(autoAdvanceMs = 200)),
                nodes = listOf(
                    NodeSpec(
                        id = "n0",
                        text = TextPayload(value = RichTextValue.Plain("Slide 0")),
                        builds = listOf(
                            Build.Enter(beat = 0, effect = "fade", durationMs = 250),
                            Build.Change(beat = 1, durationMs = 400, set = buildJsonObject { put("opacity", 0.5) }),
                        ),
                    ),
                ),
                transition = TransitionSpec(type = TransitionType.Reveal, durationMs = 700),
            ),
            SlideSpec(
                id = "s1",
                beats = listOf(BeatSpec(autoAdvanceMs = 100), BeatSpec()),
                nodes = listOf(
                    NodeSpec(
                        id = "n1",
                        text = TextPayload(value = RichTextValue.Plain("Slide 1")),
                        builds = listOf(
                            Build.Enter(beat = 0, effect = "fade", durationMs = 200),
                            Build.Change(beat = 1, durationMs = 350, set = buildJsonObject { put("opacity", 0.7) }),
                        ),
                    ),
                ),
                transition = TransitionSpec(type = TransitionType.Push, durationMs = 300),
            ),
            SlideSpec(
                id = "s2",
                beats = listOf(BeatSpec()),
                nodes = listOf(
                    NodeSpec(id = "n2", text = TextPayload(value = RichTextValue.Plain("Slide 2"))),
                ),
            ),
        ),
    )

    @Test
    fun pureDeckClockMappingStructureKeyAndNavigationRestore() {
        val doc = threeSlideDoc()
        val slides = doc.slides

        // Inactive resting states: Value(i), Start, End
        assertEquals(
            DeckClock(DocSegment.Rest("s0", 1), 0f, 0f),
            DeckClock.fromRootSegment(RootSegment(Storyboard.Index(0, 1), Storyboard.Index(0, 1), false, 0f, 0f), slides),
        )
        assertEquals(
            DeckClock(DocSegment.AtStart("s1"), 0f, 0f),
            DeckClock.fromRootSegment(RootSegment(Storyboard.Index(1, -1), Storyboard.Index(1, -1), false, 0f, 0f), slides),
        )
        assertEquals(
            DeckClock(DocSegment.Rest("s0", 1), 0f, 0f),
            DeckClock.fromRootSegment(RootSegment(Storyboard.Index(0, 2), Storyboard.Index(0, 2), false, 0f, 0f), slides),
        )

        // Forward & backward Entrance (Start <-> Value(0))
        val entFwd = DeckClock.fromRootSegment(
            RootSegment(Storyboard.Index(0, -1), Storyboard.Index(0, 0), true, playMs = 80f, effMs = 250f),
            slides,
        )
        assertEquals(DeckClock(DocSegment.Entrance("s0"), 80f, 250f), entFwd)

        val entBack = DeckClock.fromRootSegment(
            RootSegment(Storyboard.Index(0, 0), Storyboard.Index(0, -1), true, playMs = 80f, effMs = 250f),
            slides,
        )
        assertEquals(DeckClock(DocSegment.Entrance("s0"), 170f, 250f), entBack, "Backward entrance mirrors t = effMs - playMs")

        // Forward & backward Beat
        val beatFwd = DeckClock.fromRootSegment(
            RootSegment(Storyboard.Index(0, 0), Storyboard.Index(0, 1), true, playMs = 150f, effMs = 400f),
            slides,
        )
        assertEquals(DeckClock(DocSegment.Beat("s0", 0), 150f, 400f), beatFwd)
        val beatBack = DeckClock.fromRootSegment(
            RootSegment(Storyboard.Index(0, 1), Storyboard.Index(0, 0), true, playMs = 150f, effMs = 400f),
            slides,
        )
        assertEquals(DeckClock(DocSegment.Beat("s0", 0), 250f, 400f), beatBack)

        // Value(last) <-> End bridge -> Rest on last beat
        val bridge = DeckClock.fromRootSegment(
            RootSegment(Storyboard.Index(0, 1), Storyboard.Index(0, 2), true, playMs = 5f, effMs = 10f),
            slides,
        )
        assertEquals(DeckClock(DocSegment.Rest("s0", 1), 0f, 0f), bridge)

        // Forward & backward Cross(s0, s1)
        val crossFwd = DeckClock.fromRootSegment(
            RootSegment(Storyboard.Index(0, 2), Storyboard.Index(1, -1), true, playMs = 200f, effMs = 700f),
            slides,
        )
        assertEquals(DeckClock(DocSegment.Cross("s0", "s1"), 200f, 700f), crossFwd)

        val crossBack = DeckClock.fromRootSegment(
            RootSegment(Storyboard.Index(1, -1), Storyboard.Index(0, 2), true, playMs = 200f, effMs = 700f),
            slides,
        )
        assertEquals(DeckClock(DocSegment.Cross("s0", "s1"), 500f, 700f), crossBack)

        // Transient non-adjacent jump -> Rest on target
        val jump = DeckClock.fromRootSegment(
            RootSegment(Storyboard.Index(0, 0), Storyboard.Index(2, 0), true, playMs = 10f, effMs = 100f),
            slides,
        )
        assertEquals(DeckClock(DocSegment.Rest("s2", 0), 0f, 0f), jump)

        // 0ms cut (TransitionType.None) -> t=0, effMs=0, progress()=1f
        val cut = DeckClock.fromRootSegment(
            RootSegment(Storyboard.Index(0, 2), Storyboard.Index(1, -1), true, playMs = 0f, effMs = 0f),
            slides,
        )
        assertEquals(DocSegment.Cross("s0", "s1"), cut.segment)
        assertEquals(1f, cut.progress())

        // Still frame helper
        assertEquals(DeckClock(DocSegment.AtStart("s0"), 0f, 0f), DeckClock.fromStillFrame(slides[0], Frame.Start))
        assertEquals(DeckClock(DocSegment.Rest("s0", 0), 0f, 0f), DeckClock.fromStillFrame(slides[0], Frame.Value(0)))
        assertEquals(DeckClock(DocSegment.Rest("s0", 1), 0f, 0f), DeckClock.fromStillFrame(slides[0], Frame.End))

        // DeckStructureKey ignores skipped slides and changes only on structure changes
        val key1 = DeckStructureKey.from(doc)
        val nonStructuralEdit = doc.copy(title = "Renamed")
        assertEquals(key1, DeckStructureKey.from(nonStructuralEdit))
        val skippedDoc = doc.copy(slides = doc.slides.map { if (it.id == "s1") it.copy(skipped = true) else it })
        assertNotEquals(key1, DeckStructureKey.from(skippedDoc))

        // restoreNavigationIndex
        assertEquals(
            Storyboard.Index(1, 0),
            restoreNavigationIndex(slides, "s1", 1, skippedDoc.slides.filterNot { it.skipped }),
            "Deleting/skipping s1 must restore to following surviving slide s2 (now index 1) at beat 0",
        )
        assertEquals(
            Storyboard.Index(0, 0),
            restoreNavigationIndex(slides, "s2", 0, listOf(slides[0])),
            "When no following slide survives, fall back to nearest preceding surviving slide",
        )
    }

    @Test
    fun composeUiCarriersAsymmetricCrossSlideReversalsScenePreviewAndConcurrentMountIsolation() = runComposeUiTest {
        mainClock.autoAdvance = false
        val doc = threeSlideDoc()
        val format = SceneFormat(IntSize(320, 180), Density(1f))

        val observedByScene = mutableMapOf<String, MutableList<DeckClock>>()
        val observedCrossEffMsForward = mutableListOf<Float>()
        val observedCrossEffMsBackward = mutableListOf<Float>()
        val observedEntranceActive = mutableListOf<DeckClock>()
        var sawNonAdjacentPair = false

        val sessionA = RenderSession.forPresent(documentProvider = { doc })
        val sessionB = RenderSession.forPresent(documentProvider = { doc })

        fun buildDocStoryboard(
            session: RenderSession,
            recordEvents: Boolean,
        ): Storyboard = Storyboard.build(
            title = "DeckClockCarriersDeck",
            format = format,
            sceneZIndex = { idx -> session.sceneZIndexFor(idx) },
            decorator = ContentDecorator { content ->
                session.BindMountTransition()
                CompositionLocalProvider(LocalRenderSession provides session) {
                    content()
                }
            },
        ) {
            val slides = doc.slides
            for ((sIdx, slide) in slides.withIndex()) {
                scene(frames = slide.beats.indices.toList()) {
                    val intra by transition.rememberDocIntraCarrier(
                        beatCount = slide.beats.size,
                        entranceDurationMs = { SegmentPlanner.planSegment(doc, slide.id, 0).segmentMs },
                        beatSegmentDurationMs = { from -> SegmentPlanner.planSegment(doc, slide.id, from + 1).segmentMs },
                    )
                    val cross by rememberDocCrossCarrier { earlierIdx, laterIdx ->
                        val fromId = slides[earlierIdx].id
                        val toId = slides[laterIdx].id
                        SegmentPlanner.planTransition(doc, fromId, toId).segmentMs
                    }
                    // Consume carrier states so Compose tracks them
                    val unusedCarrierRead = intra + cross
                    val root = transition.rootSegment()
                    val clock = if (root != null) {
                        if (root.active && abs(root.from.sceneIndex - root.to.sceneIndex) > 1) {
                            sawNonAdjacentPair = true
                        }
                        DeckClock.fromRootSegment(root, slides)
                    } else {
                        DeckClock.fromStillFrame(slide, transition.currentState)
                    }
                    if (recordEvents) {
                        observedByScene.getOrPut(slide.id) { mutableListOf() }.add(clock)
                        if (clock.segment is DocSegment.Entrance) {
                            observedEntranceActive += clock
                        }
                    }
                    Box(Modifier.fillMaxSize().background(if (unusedCarrierRead >= -10f) Color.Black else Color.Red))
                }
            }
        }

        mainClock.autoAdvance = false
        lateinit var animaticA: Animatic
        lateinit var animaticB: Animatic
        lateinit var scope: CoroutineScope

        val boardA = buildDocStoryboard(sessionA, recordEvents = true)
        val boardB = buildDocStoryboard(sessionB, recordEvents = false)

        setContent {
            scope = rememberCoroutineScope()
            animaticA = rememberAnimatic { boardA }
            animaticB = rememberAnimatic { boardB }
            Box(Modifier.size(320.dp, 180.dp)) {
                Easel(animaticA)
                Easel(animaticB)
            }
        }
        mainClock.advanceTimeBy(200L)

        // 1. Forward beat 0 -> 1 on s0 (400ms)
        lateinit var beatJob: Job
        runOnIdle { beatJob = scope.launch { animaticA.advance(AdvanceDirection.Forward) } }
        var sampledBeat: DeckClock? = null
        var safety = 0
        while (!beatJob.isCompleted && safety++ < 200) {
            mainClock.advanceTimeByFrame()
            val c = runOnIdle { sessionA.currentClock() }
            if (sampledBeat == null && c.segment == DocSegment.Beat("s0", 0) && c.t > 0f && c.t < c.effMs) {
                sampledBeat = c
            }
        }
        assertTrue(
            beatJob.isCompleted,
            "Beat 0->1 advance must complete (cur=${animaticA.currentIndex}, tgt=${animaticA.targetIndex}, " +
                "totalNs=${animaticA.transition.totalDurationNanos}, effNs=${animaticA.effectiveDurationNanos()}, " +
                "settling=${animaticA.scrubSettling}, clock=${sessionA.currentClock()}, sampled=$sampledBeat)",
        )
        val midBeat = assertNotNull(sampledBeat, "Expected active Beat(s0, 0) mid-segment clock")
        assertTrue(midBeat.effMs in 380f..420f, "Expected ~400ms beat segment, got ${midBeat.effMs}")
        assertEquals(DocSegment.Rest("s0", 1), runOnIdle { sessionA.currentClock().segment })

        // 2. Forward cross-slide s0 -> s1 (700ms Reveal: AOverB!) + Entrance on s1 (200ms)
        //    Meanwhile sessionB stays at Rest("s0", 0) -> concurrent mount isolation.
        lateinit var fwdCrossJob: Job
        runOnIdle { fwdCrossJob = scope.launch { animaticA.advance(AdvanceDirection.Forward) } }
        var sampledFwdCross: DeckClock? = null
        var sawZOrderIsolation = false
        safety = 0
        while (!fwdCrossJob.isCompleted && safety++ < 300) {
            mainClock.advanceTimeByFrame()
            runOnIdle {
                val c = sessionA.currentClock()
                if (!animaticA.scrubSettling && c.segment == DocSegment.Cross("s0", "s1") && c.effMs > 0f) {
                    if (sampledFwdCross == null) sampledFwdCross = c
                    observedCrossEffMsForward += c.effMs
                    val zA0 = sessionA.sceneZIndexFor(Storyboard.Index(0, 0))
                    val zA1 = sessionA.sceneZIndexFor(Storyboard.Index(1, 0))
                    val zB0 = sessionB.sceneZIndexFor(Storyboard.Index(0, 0))
                    val zB1 = sessionB.sceneZIndexFor(Storyboard.Index(1, 0))
                    if (zA0 > zA1 && zB1 > zB0 && sessionB.currentClock().segment == DocSegment.Rest("s0", 0)) {
                        sawZOrderIsolation = true
                    }
                }
            }
        }
        assertTrue(fwdCrossJob.isCompleted, "Cross-slide s0->s1 forward must complete")
        val fwdCross = assertNotNull(sampledFwdCross, "Expected Cross(s0, s1) forward")
        assertTrue(fwdCross.effMs in 680f..720f, "S0->S1 forward must use S0's 700ms transition, got ${fwdCross.effMs}")
        assertTrue(sawZOrderIsolation, "SessionA Reveal must stack s0>s1 while isolated SessionB keeps s1>s0 at Rest(s0,0)")
        assertEquals(DocSegment.Rest("s1", 0), runOnIdle { sessionA.currentClock().segment })
        assertTrue(
            observedEntranceActive.any { it.segment == DocSegment.Entrance("s1") && it.effMs in 180f..220f },
            "Expected Entrance(s1) with ~200ms effMs, got $observedEntranceActive",
        )

        // 3. Backward cross-slide s1 -> s0 MUST use S0->S1's 700ms (NOT S1->S2's 300ms).
        lateinit var backCrossJob: Job
        runOnIdle { backCrossJob = scope.launch { animaticA.advance(AdvanceDirection.Backward) } }
        var sampledBackCross: DeckClock? = null
        safety = 0
        while (!backCrossJob.isCompleted && safety++ < 300) {
            mainClock.advanceTimeByFrame()
            val (settling, c) = runOnIdle { animaticA.scrubSettling to sessionA.currentClock() }
            if (!settling && c.segment == DocSegment.Cross("s0", "s1") && c.effMs > 0f) {
                if (sampledBackCross == null) sampledBackCross = c
                observedCrossEffMsBackward += c.effMs
            }
        }
        assertTrue(backCrossJob.isCompleted, "Cross-slide s1->s0 backward must complete")
        val backCross = assertNotNull(sampledBackCross, "Expected Cross(s0, s1) backward (observed=$observedCrossEffMsBackward)")
        assertTrue(
            backCross.effMs in 680f..720f,
            "Exiting S1 -> S0 backward MUST use S0->S1's 700ms duration, NOT S1->S2's 300ms! Got ${backCross.effMs} (all=$observedCrossEffMsBackward)",
        )
        assertEquals(DocSegment.Rest("s0", 1), runOnIdle { sessionA.currentClock().segment })

        // 3b. Mid-flight reversal inside Cross(s0, s1) (→ then ← mid-segment): stays canonical 700ms and settles cleanly on s0@1
        lateinit var revFwdJob: Job
        runOnIdle { revFwdJob = scope.launch { animaticA.advance(AdvanceDirection.Forward) } }
        var reachedMidCross = false
        safety = 0
        while (!reachedMidCross && !revFwdJob.isCompleted && safety++ < 120) {
            mainClock.advanceTimeByFrame()
            val (settling, c) = runOnIdle { animaticA.scrubSettling to sessionA.currentClock() }
            if (!settling && c.segment == DocSegment.Cross("s0", "s1") && c.t > 120f && c.t < 500f) {
                reachedMidCross = true
            }
        }
        assertTrue(reachedMidCross, "Should reach mid-flight Cross(s0, s1) before reversing")
        lateinit var revBackJob: Job
        runOnIdle { revBackJob = scope.launch { animaticA.advance(AdvanceDirection.Backward) } }
        var sawReversedCross = false
        safety = 0
        while (!revBackJob.isCompleted && safety++ < 200) {
            mainClock.advanceTimeByFrame()
            val (settling, c) = runOnIdle { animaticA.scrubSettling to sessionA.currentClock() }
            if (!settling && c.segment == DocSegment.Cross("s0", "s1") && c.effMs in 680f..720f) {
                sawReversedCross = true
            }
        }
        assertTrue(revBackJob.isCompleted, "Mid-flight reversed Cross(s0, s1) must complete cleanly")
        assertTrue(sawReversedCross, "Reversed Cross(s0, s1) must keep canonical 700ms segment")
        assertEquals(DocSegment.Rest("s0", 1), runOnIdle { sessionA.currentClock().segment })
        assertFalse(sawNonAdjacentPair, "No transient non-adjacent scene pair should occur during adjacent navigation")

        // 4. Cold seek to Frame.Start on s1 -> AtStart
        val startIdx = animaticA.stateDescriptors.first {
            it.sceneIndex == 1 && it.isBoundaryStart
        }.stateIndex
        assertTrue(startIdx >= 0)
        lateinit var seekJob: Job
        runOnIdle { seekJob = scope.launch { animaticA.seekToSegment(startIdx, 0f, rebuildScene = true) } }
        safety = 0
        while (!seekJob.isCompleted && safety++ < 60) {
            mainClock.advanceTimeByFrame()
        }
        assertTrue(seekJob.isCompleted, "Cold seek to Frame.Start must complete")
        assertEquals(DocSegment.AtStart("s1"), runOnIdle { sessionA.currentClock().segment })
    }

    @Test
    fun scenePreviewReturnsNullRootSegmentAndStillClockOnStartValueEnd() = runComposeUiTest {
        val doc = threeSlideDoc()
        val slide = doc.slides[0]
        val format = SceneFormat(IntSize(160, 90), Density(1f))
        val recorded = mutableListOf<Pair<RootSegment?, DeckClock>>()

        val storyboard = Storyboard.build(title = "PreviewDeck", format = format) {
            scene(frames = slide.beats.indices.toList()) {
                val root = transition.rootSegment()
                val clock = DeckClock.fromStillFrame(slide, transition.currentState)
                recorded += root to clock
                Box(Modifier.fillMaxSize())
            }
        }

        @Suppress("UNCHECKED_CAST")
        val scene0 = storyboard.scenes[0] as Scene<Int>
        setContent {
            ScenePreview(scene0, frame = Frame.Start, format = format)
            ScenePreview(scene0, frame = Frame.Value(0), format = format)
            ScenePreview(scene0, frame = Frame.End, format = format)
        }
        waitForIdle()

        assertTrue(recorded.isNotEmpty())
        assertTrue(recorded.all { it.first == null }, "ScenePreview has no Animatic root transition -> rootSegment() MUST be null")
        val segments = recorded.map { it.second.segment }.toSet()
        assertTrue(DocSegment.AtStart("s0") in segments)
        assertTrue(DocSegment.Rest("s0", 0) in segments)
        assertTrue(DocSegment.Rest("s0", 1) in segments)
    }

    @Test
    fun autoAdvanceControllerFiresOnlyOnForwardArrivalCancelsOnNavAndRespectsScrub() = runTest {
        var advances = 0
        val controller = AutoAdvanceController(scope = this) { advances++ }

        // Fires once after 500ms on forward arrival
        controller.onForwardAdvanceArrived(autoAdvanceMs = 500)
        assertTrue(controller.hasPendingAdvance)
        advanceTimeBy(499L)
        assertEquals(0, advances)
        advanceTimeBy(2L)
        assertEquals(1, advances)
        assertFalse(controller.hasPendingAdvance)

        // Any other navigation cancels pending auto-advance
        controller.onForwardAdvanceArrived(autoAdvanceMs = 500)
        advanceTimeBy(200L)
        controller.onOtherNavigation()
        assertFalse(controller.hasPendingAdvance)
        advanceTimeBy(1000L)
        assertEquals(1, advances, "Cancelled auto-advance must not fire")

        // Scrubbing blocks scheduling and cancels active pending job
        controller.onForwardAdvanceArrived(autoAdvanceMs = 500)
        controller.isScrubbing = true
        assertFalse(controller.hasPendingAdvance)
        controller.onForwardAdvanceArrived(autoAdvanceMs = 100)
        advanceTimeBy(1000L)
        assertEquals(1, advances, "Scrubbing must suppress auto-advance")

        // autoAdvanceMs = 0 yields and fires once
        controller.isScrubbing = false
        controller.onForwardAdvanceArrived(autoAdvanceMs = 0)
        runCurrent()
        assertEquals(2, advances)

        // At most one pending job even on back-to-back calls
        controller.onForwardAdvanceArrived(autoAdvanceMs = 300)
        controller.onForwardAdvanceArrived(autoAdvanceMs = 300)
        advanceTimeBy(350L)
        assertEquals(3, advances, "Second arrival replaces first; only one advance may fire")
    }

    @Test
    fun specimenRegistryStaticInspectionValidationOvershootClampedInterpolationAndReset() {
        lateinit var pFloat: ParamHandle<Float>
        lateinit var pUniform: ParamHandle<Float>
        lateinit var pInt: ParamHandle<Int>
        lateinit var pBool: ParamHandle<Boolean>
        lateinit var pColor: ParamHandle<Color>
        lateinit var pChoice: ParamHandle<String>
        lateinit var pString: ParamHandle<String>
        lateinit var tSweep: TrackHandle
        lateinit var lShared: LoopHandle
        lateinit var lPerNode: LoopHandle

        val registry = deckSpecimens {
            specimen("shader-demo", "Shader Demo") {
                pFloat = float("gain", default = 0.5f, range = 0f..1f)
                pUniform = uniformFloat("blur", default = 4f, range = 0f..20f)
                pInt = int("steps", default = 4, range = 1..10)
                pBool = boolean("wireframe", default = false)
                pColor = color("tint", default = Color(0xFF336699))
                pChoice = choice("mode", default = "solid", choices = listOf("solid", "mesh", "dots"))
                pString = string("caption", default = "hello")
                tSweep = track("sweep", beat = 1, durationMs = 400, delayMs = 25)
                lShared = loop("ambient", periodMs = 2000L, perNode = false)
                lPerNode = loop("sparkle", periodMs = 800L, perNode = true)
                content {}
            }
        }

        // Static inspection without composition
        val decl = assertNotNull(registry.find("shader-demo"))
        assertEquals(7, decl.params.size)
        assertTrue((decl.param("blur") as ParamSpec.FloatParam).isUniform)
        assertEquals(1, decl.tracks.size)
        assertEquals(2, decl.loops.size)
        assertEquals("shader-demo/ambient", lShared.loopId("shader-demo", "s0", "nodeA"))
        assertEquals("s0/nodeA/sparkle", lPerNode.loopId("shader-demo", "s0", "nodeA"))

        // Duplicate specimen ID and duplicate member name fail eagerly
        assertFailsWith<IllegalArgumentException> {
            deckSpecimens {
                specimen("dup") { content {} }
                specimen("dup") { content {} }
            }
        }
        assertFailsWith<IllegalArgumentException> {
            deckSpecimens {
                specimen("dup-member") {
                    float("x", 0.5f, 0f..1f)
                    int("x", 1, 0..5)
                    content {}
                }
            }
        }

        // Parameter validation: undeclared, wrong type, invalid choice, out-of-range (clamped), absent (default)
        val diags = ParamResolver.validateMap(
            specs = decl.params,
            values = mapOf(
                "undeclaredKey" to JsonPrimitive(42),
                "gain" to JsonPrimitive(2.5),         // out of range -> Warning, clamped to 1.0f
                "steps" to JsonPrimitive("not-int"),  // wrong type -> Error, falls back to default 4
                "mode" to JsonPrimitive("invalid"),   // invalid choice -> Error, falls back to default "solid"
            ),
        )
        assertEquals(ParamDiagnosticKind.UndeclaredKey, diags.single { it.key == "undeclaredKey" }.kind)
        assertFalse(diags.single { it.key == "undeclaredKey" }.isWarningOnly)
        assertEquals(ParamDiagnosticKind.OutOfRange, diags.single { it.key == "gain" }.kind)
        assertTrue(diags.single { it.key == "gain" }.isWarningOnly)
        assertEquals(ParamDiagnosticKind.WrongType, diags.single { it.key == "steps" }.kind)
        assertEquals(ParamDiagnosticKind.InvalidChoice, diags.single { it.key == "mode" }.kind)

        assertEquals(1f, ParamResolver.resolve(pFloat.spec, JsonPrimitive(2.5)).value)
        assertEquals(4, ParamResolver.resolve(pInt.spec, JsonPrimitive("not-int")).value)
        assertEquals("solid", ParamResolver.resolve(pChoice.spec, JsonPrimitive("invalid")).value)
        assertEquals("hello", ParamResolver.resolve(pString.spec, null).value)

        // Interpolation including spring overshoot clamping (p = 1.25f and p = -0.5f)
        assertEquals(1f, ParamResolver.interpolate(pFloat.spec, 0.2f, 0.9f, 1.25f), "Overshoot must clamp to max 1f")
        assertEquals(0f, ParamResolver.interpolate(pFloat.spec, 0.2f, 0.9f, -0.5f), "Undershoot must clamp to min 0f")
        assertEquals(10, ParamResolver.interpolate(pInt.spec, 2, 10, 1.3f), "Int overshoot must clamp to declared range")
        assertEquals(6, ParamResolver.interpolate(pInt.spec, 2, 10, 0.5f))
        assertEquals(false, ParamResolver.interpolate(pBool.spec, false, true, 0.49f))
        assertEquals(true, ParamResolver.interpolate(pBool.spec, false, true, 0.5f))
        assertEquals("solid", ParamResolver.interpolate(pChoice.spec, "solid", "dots", 0.49f))
        assertEquals("dots", ParamResolver.interpolate(pChoice.spec, "solid", "dots", 0.5f))

        // Color token resolution & Oklab perceptual interpolation (with overshoot clamping)
        val colorFrom = ParamResolver.resolve(pColor.spec, JsonPrimitive("@accent")) { tok ->
            if (tok == "@accent") Color(1f, 0f, 0f, 1f) else null
        }.value
        val colorTo = ParamResolver.resolve(pColor.spec, JsonPrimitive("#0000FF")).value
        val colorMid = ParamResolver.interpolate(pColor.spec, colorFrom, colorTo, 0.5f)
        assertEquals(androidx.compose.ui.graphics.lerp(colorFrom, colorTo, 0.5f), colorMid)
        assertNotEquals(colorFrom, colorMid)
        assertNotEquals(colorTo, colorMid)
        assertEquals(colorTo, ParamResolver.interpolate(pColor.spec, colorFrom, colorTo, 1.3f))
        assertEquals(colorFrom, ParamResolver.interpolate(pColor.spec, colorFrom, colorTo, -0.3f))

        // Reset at beat > 0 writes null in set; reset at beat 0 removes key
        val specNode = NodeSpec(
            id = "snode",
            specimen = SpecimenPayload(ref = "shader-demo", params = mapOf("gain" to JsonPrimitive(0.8))),
        )
        val resetBeat1 = JsonMergePatch.resetAtBeat(specNode, beat = 1, keyPath = listOf("specimen", "params", "gain"))
        val beat1Set = (resetBeat1.builds.single() as Build.Change).set
        val paramsPatch = beat1Set["specimen"] as kotlinx.serialization.json.JsonObject
        assertEquals(JsonNull, (paramsPatch["params"] as kotlinx.serialization.json.JsonObject)["gain"])

        val resetBeat0 = JsonMergePatch.resetAtBeat(specNode, beat = 0, keyPath = listOf("specimen", "params", "gain"))
        assertFalse("gain" in resetBeat0.specimen!!.params)
    }

    @Test
    fun renderSessionIsolationLoopProducerAndDeterministicVideoExport() = runBlocking {
        val doc = threeSlideDoc()
        val store = DeckDocumentStore()
        store.loadInitial(doc)

        var freeMs = 300L
        var masterMs: Long? = null
        var scrubMs: Long? = null

        val present = RenderSession.forPresent(
            store = store,
            freeClockMillis = { freeMs },
            masterPlayheadMillis = { masterMs },
            subCompositionScrubMillis = { scrubMs },
        )
        val still = RenderSession.forStill(store.document)
        val videoA = RenderSession.forVideo(store.document)
        val videoB = RenderSession.forVideo(store.document)

        // 1. Live edit updates Present ONLY; Still and Video keep frozen snapshots!
        store.edit("Live title edit") { it.copy(title = "Edited Live") }
        assertEquals("Edited Live", present.document.title)
        assertEquals("", still.document.title)
        assertEquals("", videoA.document.title)

        // 2. Retained store is empty at creation and strictly isolated per session
        assertFalse(present.hasRetained("k"))
        assertFalse(videoA.hasRetained("k"))
        val pState = present.getOrPutRetained("k") { "presenter-only" }
        assertEquals("presenter-only", pState)
        assertFalse(still.hasRetained("k"), "Presenter retained state must NEVER leak into Still session")
        assertFalse(videoA.hasRetained("k"), "Presenter retained state must NEVER leak into Video session")
        assertEquals("video-a", videoA.getOrPutRetained("k") { "video-a" })
        assertFalse(videoB.hasRetained("k"))

        // 3. Single loop-time producer: Present precedence, Still fixed at period/4, Video = k*1000/fps, loopPeriodsMs override
        // Loop "shader/ambient" has loopPeriodsMs override = 2000L (declared 5000L)
        assertEquals(2000L, present.effectiveLoopPeriodMs("shader/ambient", 5000L))
        assertEquals(300L, present.loopMillis("shader/ambient", 5000L)) // freeClock
        masterMs = 750L
        assertEquals(750L, present.loopMillis("shader/ambient", 5000L)) // master > free
        scrubMs = 1250L
        assertEquals(1250L, present.loopMillis("shader/ambient", 5000L)) // scrub > master > free

        assertEquals(500L, still.loopMillis("shader/ambient", 5000L), "Still must be fixed at effectivePeriod / 4 = 2000 / 4")
        assertEquals(0L, videoA.loopMillis("shader/ambient", 5000L, emittedFrameIndex = null, exportFps = 60), "Measurement pass -> 0L")
        assertEquals(500L, videoA.loopMillis("shader/ambient", 5000L, emittedFrameIndex = 30L, exportFps = 60))
        assertEquals(
            videoA.loopPhaseRadians("shader/ambient", 5000L, emittedFrameIndex = 30L, exportFps = 60),
            videoB.loopPhaseRadians("shader/ambient", 5000L, emittedFrameIndex = 30L, exportFps = 60),
        )
        assertTrue(abs(still.loopPhaseRadians("shader/ambient", 5000L) - (PI / 2.0).toFloat()) < 1e-4f)

        // 4. holdDurationForState uses autoAdvanceMs (incl. 0ms -> 0ms so exporter emits min 1 frame) or fallback defaultHoldMs
        assertEquals(0.milliseconds, videoA.holdDurationForState(Storyboard.Index(0, 0), defaultHoldMs = 400L))
        assertEquals(200.milliseconds, videoA.holdDurationForState(Storyboard.Index(0, 1), defaultHoldMs = 400L))
        assertEquals(100.milliseconds, videoA.holdDurationForState(Storyboard.Index(1, 0), defaultHoldMs = 400L))
        assertEquals(400.milliseconds, videoA.holdDurationForState(Storyboard.Index(1, 1), defaultHoldMs = 400L))

        // 5. Deterministic offscreen Video render sessions wired via LocalExportEmittedFrameIndex,
        //    LocalExportFps, and holdDurationForState (plus verifying StoryboardVideoExporter
        //    constructor accepts holdDurationForState cleanly):
        //    Two independent video sessions emit byte-identical RGBA frames and identical loopMillis sequences!
        val exportDoc = DeckDocument(
            id = "export-det",
            slides = listOf(
                SlideSpec(
                    id = "e0",
                    beats = listOf(BeatSpec(autoAdvanceMs = 0), BeatSpec(autoAdvanceMs = 100)),
                    nodes = emptyList(),
                ),
            ),
        )
        val width = 64
        val height = 36
        val fps = 20
        val smallFormat = SceneFormat(IntSize(width, height), Density(1f))

        // Verify StoryboardVideoExporter constructor accepts RenderSession.holdDurationForState directly
        val probeExporter = StoryboardVideoExporter(
            fps = fps,
            beatHoldMillis = 200L,
            holdDurationForState = { idx -> videoA.holdDurationForState(idx, defaultHoldMs = 200L) },
            ffmpegChecker = { true },
        )
        assertFalse(probeExporter.isExporting)

        @OptIn(ExperimentalComposeUiApi::class)
        fun runVideoSession(): Pair<List<ByteArray>, List<Long>> {
            val session = RenderSession.forVideo(exportDoc)
            val loopTrace = mutableListOf<Long>()
            val frames = mutableListOf<ByteArray>()
            var emittedFrameIndex by mutableLongStateOf(0L)
            val board = Storyboard.build(title = "ExportTest", format = smallFormat) {
                scene(frames = listOf(0, 1)) {
                    val emitted = LocalExportEmittedFrameIndex.current
                    val activeFps = LocalExportFps.current
                    val loopMs = session.currentLoopMillis("export/spin", declaredPeriodMs = 1000L)
                    if (emitted != null && activeFps != null) {
                        loopTrace += loopMs
                    }
                    val shade = (loopMs % 1000L) / 1000f
                    Box(Modifier.fillMaxSize().background(Color(shade, 0.2f, 0.5f, 1f)))
                }
            }

            val rgbaInfo = ImageInfo(width, height, ColorType.RGBA_8888, ColorAlphaType.PREMUL)
            val bitmap = Bitmap().apply { allocPixels(rgbaInfo) }
            val scene = ImageComposeScene(
                width = width,
                height = height,
                density = smallFormat.density,
                coroutineContext = Dispatchers.Unconfined,
            ) {
                val animatic = rememberAnimatic(storyboard = { board })
                CompositionLocalProvider(
                    LocalExportEmittedFrameIndex provides emittedFrameIndex,
                    LocalExportFps provides fps,
                ) {
                    Easel(animatic, mode = SceneMode.Preview)
                }
            }
            try {
                var frameTick = 0L
                val holdFramesPerBeat = board.indices.map { idx ->
                    val holdMs = session.holdDurationForState(idx, defaultHoldMs = 200L).inWholeMilliseconds
                    ceil(holdMs * fps / 1000.0).toInt().coerceAtLeast(1)
                }
                val totalToEmit = holdFramesPerBeat.sum()
                repeat(totalToEmit) {
                    val image = scene.render((frameTick++) * 1_000_000_000L / fps)
                    try {
                        assertTrue(image.readPixels(bitmap))
                        val bytes = bitmap.readPixels(dstInfo = rgbaInfo, dstRowBytes = width * 4, srcX = 0, srcY = 0)
                        assertNotNull(bytes)
                        frames += bytes
                    } finally {
                        image.close()
                    }
                    emittedFrameIndex = frames.size.toLong()
                    Snapshot.sendApplyNotifications()
                }
            } finally {
                bitmap.close()
                scene.close()
            }
            return frames to loopTrace
        }

        val (frames1, trace1) = runVideoSession()
        val (frames2, trace2) = runVideoSession()
        // At 20fps: beat 0 autoAdvanceMs=0 -> min 1 frame; beat 1 autoAdvanceMs=100 -> 2 frames!
        assertEquals(3, frames1.size, "Expected 1 frame (autoAdvanceMs=0) + 2 frames (autoAdvanceMs=100 @ 20fps) = 3 frames")
        assertEquals(frames1.size, frames2.size)
        for (i in frames1.indices) {
            assertTrue(frames1[i].contentEquals(frames2[i]), "Frame $i must be byte-identical across two Video sessions")
        }
        assertFalse(frames1[0].contentEquals(frames1[1]), "Distinct loop timestamps must produce distinct rendered pixels")
        assertEquals(trace1, trace2, "Two Video sessions must observe identical deterministic loop times")
        assertEquals(listOf(0L, 50L, 100L), trace1.distinct().take(3))
    }

    @Test
    fun intraCarrierDragToZeroThenAdvancePreservesExactDurationAndUnbindsCleanly() = runComposeUiTest {
        mainClock.autoAdvance = false
        val doc = DeckDocument(
            id = "drag-to-zero-deck",
            themes = mapOf(
                "revealTheme" to DeckThemeSpec(
                    motion = ThemeMotionDefaults(
                        transition = TransitionSpec(type = TransitionType.Reveal, durationMs = 500),
                    ),
                ),
            ),
            defaultTheme = "revealTheme",
            slides = listOf(
                SlideSpec(
                    id = "s0",
                    beats = listOf(BeatSpec(), BeatSpec()),
                    nodes = listOf(
                        NodeSpec(
                            id = "n0",
                            frame = FrameSpec(x = 10.0, y = 10.0, w = 80.0, h = 40.0),
                            shape = ShapePayload(geometry = ShapeGeometry.Rect),
                            builds = listOf(
                                Build.Change(
                                    beat = 1,
                                    durationMs = 400,
                                    curve = Curve.Linear,
                                    set = buildJsonObject {
                                        put("opacity", 0.5)
                                    },
                                ),
                            ),
                        ),
                    ),
                ),
                SlideSpec(
                    id = "s1",
                    beats = listOf(BeatSpec()),
                    nodes = emptyList(),
                ),
            ),
        )
        val format = SceneFormat(IntSize(320, 180), Density(1f))
        val session = RenderSession.forPresent(documentProvider = { doc })

        val board = Storyboard.build(
            title = "DragToZero",
            format = format,
            sceneZIndex = { idx -> session.sceneZIndexFor(idx) },
            decorator = ContentDecorator { content ->
                session.BindMountTransition()
                CompositionLocalProvider(LocalRenderSession provides session) {
                    content()
                }
            },
        ) {
            for (slide in doc.slides) {
                scene(frames = slide.beats.indices.toList()) {
                    val intra by transition.rememberDocIntraCarrier(
                        beatCount = slide.beats.size,
                        entranceDurationMs = { SegmentPlanner.planSegment(doc, slide.id, 0).segmentMs },
                        beatSegmentDurationMs = { from -> SegmentPlanner.planSegment(doc, slide.id, from + 1).segmentMs },
                    )
                    val cross by rememberDocCrossCarrier { earlierIdx, laterIdx ->
                        SegmentPlanner.planTransition(
                            doc,
                            doc.slides[earlierIdx].id,
                            doc.slides[laterIdx].id,
                        ).segmentMs
                    }
                    Box(Modifier.fillMaxSize().background(if (intra + cross >= -10f) Color.Black else Color.Red))
                }
            }
        }

        lateinit var animatic: Animatic
        lateinit var scope: CoroutineScope
        var showPreview by androidx.compose.runtime.mutableStateOf(true)
        var showEasel by androidx.compose.runtime.mutableStateOf(true)

        setContent {
            scope = rememberCoroutineScope()
            animatic = rememberAnimatic { board }
            Box(Modifier.size(320.dp, 180.dp)) {
                if (showEasel) {
                    Easel(animatic)
                }
                if (showPreview) {
                    // Concurrent ScenePreview has LocalStoryboardTransition == null and MUST NOT
                    // clobber the live Easel mount's bound root transition!
                    ScenePreview(
                        storyboard = board,
                        index = Storyboard.Index(0, 0),
                        modifier = Modifier.size(64.dp, 36.dp),
                    )
                }
            }
        }
        mainClock.advanceTimeBy(100L)

        // 1. Concurrent ScenePreview must not clobber live Easel binding; also verify theme-default
        //    Reveal transition & reducedMotion collapse in sceneZIndexFor.
        val beat0Seg = animatic.stateDescriptors.first { it.sceneIndex == 0 && it.frameIndex == 0 && !it.isBoundaryStart }.stateIndex
        val crossSeg = animatic.stateDescriptors.first { it.sceneIndex == 0 && it.isBoundaryEnd }.stateIndex
        lateinit var crossSeekJob: Job
        runOnIdle { crossSeekJob = scope.launch { animatic.seekToSegment(crossSeg, 0.5f, rebuildScene = false) } }
        var guard = 0
        while (!crossSeekJob.isCompleted && guard++ < 100) {
            mainClock.advanceTimeByFrame()
        }
        assertTrue(crossSeekJob.isCompleted)
        runOnIdle {
            assertEquals(DocSegment.Cross("s0", "s1"), session.currentClock().segment)
            val z0Reveal = session.sceneZIndexFor(Storyboard.Index(0, 0), reducedMotion = false)
            val z1Reveal = session.sceneZIndexFor(Storyboard.Index(1, 0), reducedMotion = false)
            assertTrue(z0Reveal > z1Reveal, "Theme-default Reveal must stack s0 > s1 during Cross")
            val z0Reduced = session.sceneZIndexFor(Storyboard.Index(0, 0), reducedMotion = true)
            val z1Reduced = session.sceneZIndexFor(Storyboard.Index(1, 0), reducedMotion = true)
            assertTrue(z1Reduced > z0Reduced, "Reduced motion must collapse Reveal to Dissolve (s1 > s0)")
        }

        // 2. Return to beat 0 and exercise in-place drag to 0.5 -> drag back to 0.0 -> advance(Forward).
        //    Carrier MUST bump its epoch via rootSegment() even if child states stay inverted,
        //    so the subsequent advance runs at the exact planned 400 ms (never 171 ms default spring / 0 ms).
        lateinit var jumpJob: Job
        runOnIdle { jumpJob = scope.launch { animatic.jumpTo(Storyboard.Index(0, 0)) } }
        guard = 0
        while (!jumpJob.isCompleted && guard++ < 100) {
            mainClock.advanceTimeByFrame()
        }
        repeat(4) { mainClock.advanceTimeByFrame() }

        lateinit var dragHalfJob: Job
        runOnIdle { dragHalfJob = scope.launch { animatic.seekToSegment(beat0Seg, 0.5f, rebuildScene = false) } }
        guard = 0
        while (!dragHalfJob.isCompleted && guard++ < 100) {
            mainClock.advanceTimeByFrame()
        }
        assertTrue(dragHalfJob.isCompleted)

        lateinit var dragZeroJob: Job
        runOnIdle { dragZeroJob = scope.launch { animatic.seekToSegment(beat0Seg, 0f, rebuildScene = false) } }
        guard = 0
        while (!dragZeroJob.isCompleted && guard++ < 100) {
            mainClock.advanceTimeByFrame()
        }
        assertTrue(dragZeroJob.isCompleted)
        repeat(4) { mainClock.advanceTimeByFrame() }

        lateinit var advJob: Job
        runOnIdle { advJob = scope.launch { animatic.advance(AdvanceDirection.Forward) } }
        var observedEffNanos = 0L
        guard = 0
        while (!advJob.isCompleted && guard++ < 200) {
            mainClock.advanceTimeByFrame()
            runOnIdle {
                if (!animatic.scrubSettling && animatic.currentIndex != animatic.targetIndex) {
                    val eff = animatic.effectiveDurationNanos()
                    if (eff > 0L) observedEffNanos = maxOf(observedEffNanos, eff)
                }
            }
        }
        assertTrue(advJob.isCompleted, "advance(Forward) after drag-to-zero must complete")
        assertEquals(
            400_000_000L,
            observedEffNanos,
            "advance(Forward) after drag-to-zero must use exact 400 ms plan duration, not stale spring",
        )

        // 3. Disposing the live Easel cleanly unbinds rootTransition so fallback clock is used.
        runOnIdle {
            showPreview = false
            showEasel = false
        }
        repeat(3) { mainClock.advanceTimeByFrame() }
        runOnIdle {
            val fallbackClock = session.currentClock(fallbackSlide = doc.slides[0], fallbackFrame = Frame.Value(0))
            assertEquals(DocSegment.Rest("s0", 0), fallbackClock.segment)
        }
    }
}
