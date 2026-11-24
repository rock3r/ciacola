package dev.sebastiano.ciacola.timeline

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.sebastiano.ciacola.document.BeatSpec
import dev.sebastiano.ciacola.document.Build
import dev.sebastiano.ciacola.document.Curve
import dev.sebastiano.ciacola.document.DeckDocument
import dev.sebastiano.ciacola.document.DeckDocumentStore
import dev.sebastiano.ciacola.document.DeckSpecimenRegistry
import dev.sebastiano.ciacola.document.DeckThemeSpec
import dev.sebastiano.ciacola.document.FrameSpec
import dev.sebastiano.ciacola.document.MorphBy
import dev.sebastiano.ciacola.document.NodeSpec
import dev.sebastiano.ciacola.document.Phases
import dev.sebastiano.ciacola.document.RichTextValue
import dev.sebastiano.ciacola.document.SlideSpec
import dev.sebastiano.ciacola.document.Slot
import dev.sebastiano.ciacola.document.SlotPhase
import dev.sebastiano.ciacola.document.SpecimenPayload
import dev.sebastiano.ciacola.document.Stagger
import dev.sebastiano.ciacola.document.StaggerOrder
import dev.sebastiano.ciacola.document.TextMorphSpec
import dev.sebastiano.ciacola.document.TextPayload
import dev.sebastiano.ciacola.document.Timing
import dev.sebastiano.ciacola.document.TransitionSpec
import dev.sebastiano.ciacola.document.TransitionType
import dev.sebastiano.ciacola.document.deckSpecimens
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.jetbrains.jewel.intui.standalone.theme.IntUiTheme

class DocumentTimelineAndStaggerTest {

    private fun testSpecimens(): DeckSpecimenRegistry = deckSpecimens {
        specimen("demo.shader", "Demo Shader") {
            track(name = "sweep", beat = 1, durationMs = 500, curve = Curve.EaseOut)
            loop(name = "ambient-pulse", label = "Ambient Pulse", periodMs = 4_000L)
            content {}
        }
    }

    private fun sampleDeck(): DeckDocument {
        val slide1 = SlideSpec(
            id = "s1",
            title = "Shaders Intro",
            beats = listOf(
                BeatSpec(notes = "Opening question."),
                BeatSpec(
                    notes = "Reveal and morph text.",
                    stagger = Stagger(ms = 60, order = StaggerOrder.TopToBottom),
                    phases = Phases(
                        exit = Timing(delayMs = 0, durationMs = 200),
                        move = Timing(delayMs = 80, durationMs = 400),
                        enter = Timing(delayMs = 150, durationMs = 350),
                    ),
                ),
            ),
            nodes = listOf(
                NodeSpec(
                    id = "headline",
                    frame = FrameSpec(x = 120.0, y = 100.0, w = 960.0, h = 120.0),
                    text = TextPayload(
                        value = RichTextValue.Plain("What is a shader?"),
                        style = "title",
                        morph = TextMorphSpec(by = MorphBy.Word),
                    ),
                    builds = listOf(
                        Build.Change(
                            beat = 1,
                            id = "headline-morph",
                            set = buildJsonObject {
                                putJsonObject("text") {
                                    put("value", "A shader is a function")
                                }
                            },
                            morph = TextMorphSpec(
                                by = MorphBy.Word,
                                stagger = Stagger(ms = 20, order = StaggerOrder.ReadingOrder),
                            ),
                        ),
                    ),
                ),
                NodeSpec(
                    id = "card-a",
                    frame = FrameSpec(x = 120.0, y = 300.0, w = 400.0, h = 200.0),
                    text = TextPayload(value = RichTextValue.Plain("Card A"), style = "body"),
                    builds = listOf(
                        Build.Enter(beat = 1, id = "enter-a", effect = "fadeMove", durationMs = 300),
                    ),
                ),
                NodeSpec(
                    id = "card-b",
                    frame = FrameSpec(x = 120.0, y = 560.0, w = 400.0, h = 200.0),
                    text = TextPayload(value = RichTextValue.Plain("Card B"), style = "body"),
                    builds = listOf(
                        Build.Enter(beat = 1, id = "enter-b", effect = "fadeMove", durationMs = 300),
                    ),
                ),
                NodeSpec(
                    id = "hero-shader",
                    frame = FrameSpec(x = 1000.0, y = 240.0, w = 720.0, h = 540.0),
                    specimen = SpecimenPayload(ref = "demo.shader"),
                ),
            ),
            transition = TransitionSpec(
                type = TransitionType.MagicMove,
                stagger = Stagger(ms = 40, order = StaggerOrder.LeftToRight),
                phases = Phases(
                    exit = Timing(delayMs = 0, durationMs = 220),
                    move = Timing(delayMs = 100, durationMs = 500),
                    enter = Timing(delayMs = 300, durationMs = 320),
                ),
            ),
        )

        val slide2 = SlideSpec(
            id = "s2",
            title = "Function Deep Dive",
            beats = listOf(BeatSpec(notes = "Follow-up slide.")),
            nodes = listOf(
                NodeSpec(
                    id = "headline",
                    frame = FrameSpec(x = 120.0, y = 60.0, w = 960.0, h = 120.0),
                    text = TextPayload(
                        value = RichTextValue.Plain("A shader is a pure function"),
                        style = "title",
                        morph = TextMorphSpec(by = MorphBy.Word),
                    ),
                ),
                NodeSpec(
                    id = "card-a",
                    frame = FrameSpec(x = 120.0, y = 260.0, w = 400.0, h = 200.0),
                    text = TextPayload(value = RichTextValue.Plain("Card A"), style = "body"),
                ),
                NodeSpec(
                    id = "badge-new",
                    frame = FrameSpec(x = 700.0, y = 300.0, w = 320.0, h = 120.0),
                    text = TextPayload(value = RichTextValue.Plain("Pure"), style = "callout"),
                ),
            ),
        )

        return DeckDocument(
            id = "test-stagger-deck",
            title = "Stagger Sculpting Test Deck",
            themes = mapOf("dark" to DeckThemeSpec(preset = "stageDark")),
            defaultTheme = "dark",
            slides = listOf(slide1, slide2),
        )
    }

    private fun createController(): Pair<DeckDocumentStore, DocumentTimelineController> {
        val store = DeckDocumentStore()
        store.loadInitial(sampleDeck())
        val controller = DocumentTimelineController(
            store = store,
            specimens = testSpecimens(),
        )
        return store to controller
    }

    @Test
    fun analyticalTimelineComputesBeatsTransitionsSpecimenTracksAndLoopsWithoutRendering() {
        val (_, controller) = createController()
        val snap = controller.snapshot

        assertEquals(2, snap.snapshot.slides.size)
        assertTrue(snap.beatHeaders.any { it.slideId == "s1" && it.beatIndex == 1 && it.segmentMs > 0 })
        assertTrue(snap.transitionHeaders.any { it.fromSlideId == "s1" && it.toSlideId == "s2" && it.segmentMs > 0 })
        assertTrue(
            snap.trackRefsByTrackId.values.any {
                it is DocumentTimelineTrackRef.SpecimenTrackBar && it.trackName == "sweep"
            },
            "Specimen declared track 'sweep' must appear analytically",
        )
        assertTrue(
            snap.trackRefsByTrackId.values.any {
                it is DocumentTimelineTrackRef.LoopTrackBar && it.loopName == "ambient-pulse"
            },
            "Specimen declared loop 'ambient-pulse' must appear analytically",
        )
    }

    @Test
    fun beatAndTransitionMotionStaggerPhasesAndManualNodeDelaySculpting() {
        val (store, controller) = createController()

        // 1. Select beat header and edit stagger ms, order, reverse, and overlapping Exit/Move/Enter phases.
        controller.selectBeatHeader("s1", 1)
        assertTrue(controller.updateBeatStagger("s1", 1, ms = 95, order = StaggerOrder.ReadingOrder, reverse = true))
        val beat1After = store.document.slides[0].beats[1]
        assertEquals(95, beat1After.stagger?.ms)
        assertEquals(StaggerOrder.ReadingOrder, beat1After.stagger?.order)
        assertEquals(true, beat1After.stagger?.reverse)

        // Make Move overlap Exit (Exit: 0..260, Move: 120..520, Enter: 150..500).
        assertTrue(
            controller.updateBeatPhaseTiming(
                slideId = "s1",
                beatIndex = 1,
                phase = SlotPhase.Exit,
                delayMs = 0,
                durationMs = 260,
                curve = Curve.EaseIn,
            ),
        )
        assertTrue(
            controller.updateBeatPhaseTiming(
                slideId = "s1",
                beatIndex = 1,
                phase = SlotPhase.Move,
                delayMs = 120,
                durationMs = 400,
                curve = Curve.Spring(0.8, 420.0),
            ),
        )
        val phases = store.document.slides[0].beats[1].phases
        assertEquals(260, phases?.exit?.durationMs)
        assertEquals(120, phases?.move?.delayMs)
        assertEquals(Curve.Spring(0.8, 420.0), phases?.move?.curve)

        // 2. Drag participating node's bar start -> writes stagger.manual[nodeId] and updates SegmentPlan!
        val cardBRef = controller.snapshot.trackRefsByTrackId.values
            .filterIsInstance<DocumentTimelineTrackRef.SlideBuildTrack>()
            .first { it.slideId == "s1" && it.beatIndex == 1 && it.nodeId == "card-b" }
        assertNull(store.document.slides[0].beats[1].stagger?.manual?.get("card-b"))

        assertTrue(controller.beginGesture("Drag card-b start"))
        assertTrue(controller.dragTrackBarStart(cardBRef, newRelativeStartMs = 480, useActiveGesture = true))
        controller.commitActiveGesture()

        // Phase enter delay is 150ms, so manual stagger = 480 - 150 = 330ms.
        assertEquals(330, store.document.slides[0].beats[1].stagger?.manual?.get("card-b"))
        val updatedCardBRef = controller.snapshot.trackRefsByTrackId.values
            .filterIsInstance<DocumentTimelineTrackRef.SlideBuildTrack>()
            .first { it.slideId == "s1" && it.beatIndex == 1 && it.nodeId == "card-b" }
        assertEquals(480, updatedCardBRef.trackPlan.startMs)
        assertTrue(
            controller.snapshot.beatHeader("s1", 1)?.beatSpec?.stagger?.manual?.containsKey("card-b") == true,
            "Manual badge backing state must reflect manual override",
        )

        // Reset manual delay restores automatic stagger.
        assertTrue(controller.resetBeatNodeManualDelay("s1", 1, "card-b"))
        assertNull(store.document.slides[0].beats[1].stagger?.manual?.get("card-b"))

        // 3. Select transition header and edit transition stagger, phase overlap, and per-node manual delay.
        controller.selectTransitionHeader("s1", "s2")
        assertTrue(controller.updateTransitionStagger("s1", ms = 75, order = StaggerOrder.TopToBottom, reverse = true))
        assertTrue(controller.updateTransitionPhaseTiming("s1", SlotPhase.Move, delayMs = 60, durationMs = 440))
        val trCardARef = controller.snapshot.trackRefsByTrackId.values
            .filterIsInstance<DocumentTimelineTrackRef.TransitionTrackBar>()
            .first { it.fromSlideId == "s1" && it.nodeId == "card-a" }
        assertTrue(controller.dragTrackBarStart(trCardARef, newRelativeStartMs = 310))
        assertEquals(250, store.document.slides[0].transition?.stagger?.manual?.get("card-a"))
        assertTrue(controller.resetTransitionNodeManualDelay("s1", "card-a"))
        assertNull(store.document.slides[0].transition?.stagger?.manual?.get("card-a"))
    }

    @Test
    fun intraTextFragmentStaggerExpansionAndDraggingFunction0TickUpdatesDocumentAndPlan() {
        val (store, controller) = createController()

        // 1. Select beat-1 text morph track on "headline" ("What is a shader?" -> "A shader is a function").
        val morphRef = controller.snapshot.trackRefsByTrackId.values
            .filterIsInstance<DocumentTimelineTrackRef.SlideBuildTrack>()
            .first { it.slideId == "s1" && it.nodeId == "headline" && it.isTextMorph }
        controller.selectTrack(morphRef.trackId)
        assertEquals(morphRef.trackId, controller.expandedTextTrackId)

        val slotsBefore = controller.fragmentSlotsFor(morphRef)
        assertTrue(slotsBefore.none { it.address == Slot.SIZE_ADDRESS }, "@size is reserved and excluded from draggable fragment ticks")
        val functionSlotBefore = slotsBefore.firstOrNull { it.address == "function#0" }
        assertNotNull(functionSlotBefore, "Expected 'function#0' fragment slot in PieceSchedule: ${slotsBefore.map { it.address }}")

        // 2. Drag "function#0" fragment tick horizontally to 640ms inside one transaction.
        assertTrue(controller.beginGesture("Drag fragment function#0"))
        assertTrue(controller.setFragmentManualDelay(morphRef, "function#0", 420, useActiveGesture = true))
        assertTrue(controller.setFragmentManualDelay(morphRef, "function#0", 640, useActiveGesture = true))
        controller.commitActiveGesture()

        // Verify exact DeckDocument write: builds[0].morph.stagger.manual["function#0"] == 640.
        val changeBuild = store.document.slides[0].nodes.first { it.id == "headline" }.builds[0] as Build.Change
        assertEquals(640, changeBuild.morph?.stagger?.manual?.get("function#0"))

        // Verify SegmentPlan & PieceSchedule updated so the fragment tick & track end moved!
        val updatedRef = controller.snapshot.trackRefsByTrackId[morphRef.trackId] as DocumentTimelineTrackRef.SlideBuildTrack
        val functionSlotAfter = controller.fragmentSlotsFor(updatedRef).first { it.address == "function#0" }
        assertTrue(
            functionSlotAfter.startMs > functionSlotBefore.startMs,
            "Dragging 'function#0' to 640ms must push its planned slot start forward (before=${functionSlotBefore.startMs}, after=${functionSlotAfter.startMs})",
        )
        assertTrue(updatedRef.trackPlan.endMs >= updatedRef.trackPlan.startMs + functionSlotAfter.endMs)

        // Reset removes manual entry.
        assertTrue(controller.resetFragmentManualDelay(updatedRef, "function#0"))
        val resetBuild = store.document.slides[0].nodes.first { it.id == "headline" }.builds[0] as Build.Change
        assertNull(resetBuild.morph?.stagger?.manual?.get("function#0"))

        // 3. Cross-slide text morph on "headline" (s1 -> s2) writes transition.text["headline"].stagger.manual["pure#0"].
        val crossMorphRef = controller.snapshot.trackRefsByTrackId.values
            .filterIsInstance<DocumentTimelineTrackRef.TransitionTrackBar>()
            .first { it.fromSlideId == "s1" && it.nodeId == "headline" && it.isTextMorph }
        controller.selectTrack(crossMorphRef.trackId)
        assertEquals(crossMorphRef.trackId, controller.expandedTextTrackId)
        val crossSlots = controller.fragmentSlotsFor(crossMorphRef)
        assertTrue(crossSlots.any { it.address == "pure#0" }, "Expected 'pure#0' in cross-slide morph: ${crossSlots.map { it.address }}")
        assertTrue(controller.setFragmentManualDelay(crossMorphRef, "pure#0", 510))
        assertEquals(510, store.document.slides[0].transition?.text?.get("headline")?.stagger?.manual?.get("pure#0"))
    }

    @Test
    fun timelineDragTransactionsUndoEscCancelAndStructuralEdits() {
        val (store, controller) = createController()
        val initialDoc = store.document

        val cardARef = controller.snapshot.trackRefsByTrackId.values
            .filterIsInstance<DocumentTimelineTrackRef.SlideBuildTrack>()
            .first { it.slideId == "s1" && it.nodeId == "card-a" }

        // 1. Esc mid-drag restores EXACT document with zero history entries.
        assertTrue(controller.beginGesture("Resize card-a"))
        assertTrue(controller.resizeTrackBarDuration(cardARef, 720, useActiveGesture = true))
        assertEquals(720, (store.document.slides[0].nodes.first { it.id == "card-a" }.builds[0] as Build.Enter).durationMs)
        assertTrue(controller.handleShortcut(Key.Escape))
        assertEquals(initialDoc, store.document)
        assertFalse(store.canUndo)

        // 2. Single drag with multiple updates = 1 undo step.
        assertTrue(controller.beginGesture("Resize card-a"))
        assertTrue(controller.resizeTrackBarDuration(cardARef, 500, useActiveGesture = true))
        assertTrue(controller.resizeTrackBarDuration(cardARef, 650, useActiveGesture = true))
        controller.commitActiveGesture()
        assertEquals(650, (store.document.slides[0].nodes.first { it.id == "card-a" }.builds[0] as Build.Enter).durationMs)
        assertTrue(store.canUndo)

        // 3. Second quick drag = 2nd undo step.
        assertTrue(controller.beginGesture("Resize card-a again"))
        assertTrue(controller.resizeTrackBarDuration(cardARef, 800, useActiveGesture = true))
        controller.commitActiveGesture()
        assertEquals(800, (store.document.slides[0].nodes.first { it.id == "card-a" }.builds[0] as Build.Enter).durationMs)

        store.undo()
        assertEquals(650, (store.document.slides[0].nodes.first { it.id == "card-a" }.builds[0] as Build.Enter).durationMs)
        store.undo()
        assertEquals(initialDoc, store.document)

        // 4. Loop period edit uses transaction & records on document.loopPeriodsMs.
        val loopRef = controller.snapshot.trackRefsByTrackId.values
            .filterIsInstance<DocumentTimelineTrackRef.LoopTrackBar>()
            .first()
        assertTrue(controller.beginGesture("Edit loop period"))
        assertTrue(controller.setLoopPeriodMs(loopRef.loopId, 6_500L, useActiveGesture = true))
        controller.commitActiveGesture()
        assertEquals(6_500L, store.document.loopPeriodsMs[loopRef.loopId])

        // 5. Structural beat insert & delete are each 1 undo step.
        val beforeInsert = store.document
        assertTrue(controller.insertBeat("s1", 1))
        assertEquals(3, store.document.slides[0].beats.size)
        assertEquals(2, store.document.slides[0].nodes.first { it.id == "card-a" }.builds[0].beat)
        store.undo()
        assertEquals(beforeInsert, store.document)

        assertTrue(controller.deleteBeat("s1", 1))
        assertEquals(1, store.document.slides[0].beats.size)
        assertTrue(store.document.slides[0].beats[0].notes.orEmpty().contains("Reveal and morph text."))
        assertEquals(0, store.document.slides[0].nodes.first { it.id == "card-a" }.builds[0].beat)
        store.undo()
        assertEquals(beforeInsert, store.document)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun timelineStudioPanelRendersBeatInspectorAndIntraTextFragmentExpansion() = runComposeUiTest {
        val (_, controller) = createController()
        controller.selectBeatHeader("s1", 1)

        setContent {
            IntUiTheme {
                Box(Modifier.size(1280.dp, 720.dp)) {
                    DocumentTimelineStudioPanel(controller = controller)
                }
            }
        }
        waitForIdle()
        val textsBeat = collectAllSemanticTexts(onRoot(useUnmergedTree = true).fetchSemanticsNode())
        assertTrue(
            textsBeat.any { it.contains("Motion Stagger", ignoreCase = true) || it.contains("Phases", ignoreCase = true) },
            "Expected Motion Stagger / Phases UI when beat header is selected; found: ${textsBeat.take(25)}",
        )

        // Switch selection to the text morph track and verify Intra-Text Fragment expansion renders.
        val morphRef = controller.snapshot.trackRefsByTrackId.values
            .filterIsInstance<DocumentTimelineTrackRef.SlideBuildTrack>()
            .first { it.slideId == "s1" && it.nodeId == "headline" && it.isTextMorph }
        runOnIdle { controller.selectTrack(morphRef.trackId) }
        waitForIdle()
        val textsMorph = collectAllSemanticTexts(onRoot(useUnmergedTree = true).fetchSemanticsNode())
        assertTrue(
            textsMorph.any { it.contains("function#0") },
            "Expected fragment tick 'function#0' when text morph track is expanded; found: ${textsMorph.take(30)}",
        )
    }

    @Test
    fun draggingSequencedBuildWithHashAfterReferenceComputesDelayRelativeToDependencyEnd() {
        val doc = DeckDocument(
            id = "hash-after-drag",
            title = "Hash After Drag",
            defaultTheme = "dark",
            themes = mapOf("dark" to DeckThemeSpec(preset = "stageDark")),
            slides = listOf(
                SlideSpec(
                    id = "s1",
                    beats = listOf(BeatSpec(), BeatSpec()),
                    nodes = listOf(
                        NodeSpec(
                            id = "card-a",
                            frame = FrameSpec(x = 100.0, y = 100.0, w = 200.0, h = 100.0),
                            text = TextPayload(RichTextValue.Plain("First")),
                            builds = listOf(
                                Build.Enter(id = "intro", beat = 1, effect = "fade", durationMs = 400),
                            ),
                        ),
                        NodeSpec(
                            id = "card-b",
                            frame = FrameSpec(x = 400.0, y = 100.0, w = 200.0, h = 100.0),
                            text = TextPayload(RichTextValue.Plain("Second")),
                            builds = listOf(
                                Build.Enter(id = "follow", beat = 1, effect = "fade", after = "card-a#intro", durationMs = 300),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val store = DeckDocumentStore(doc, initialLoad = dev.sebastiano.ciacola.document.DeckLoadState.Loaded)
        val controller = DocumentTimelineController(store = store)
        val followRef = controller.snapshot.trackRefsByTrackId.values
            .filterIsInstance<DocumentTimelineTrackRef.SlideBuildTrack>()
            .single { it.nodeId == "card-b" && it.beatIndex == 1 }
        assertEquals(400, followRef.trackPlan.startMs, "Initial start follows card-a#intro end (400ms)")

        // Drag card-b's bar to 550ms -> delayMs must be 550 - 400 = 150ms so planned start lands at 550ms (not 950ms).
        assertTrue(controller.beginGesture("Move follow"))
        assertTrue(controller.dragTrackBarStart(followRef, 550, useActiveGesture = true))
        controller.commitActiveGesture()

        val updatedBuild = store.document.slides.single().nodes.first { it.id == "card-b" }.builds.single()
        assertEquals(150, updatedBuild.delayMs, "delayMs must subtract card-a#intro's 400ms end time")
        val updatedRef = controller.snapshot.trackRefsByTrackId.values
            .filterIsInstance<DocumentTimelineTrackRef.SlideBuildTrack>()
            .single { it.nodeId == "card-b" && it.beatIndex == 1 }
        assertEquals(550, updatedRef.trackPlan.startMs, "Re-planned start must match the dragged 550ms position")
    }

    @Test
    fun draggingBeatZeroEnterPrecededByInactiveBeatZeroChangePreservesPhaseDelaySubtraction() {
        val doc = DeckDocument(
            id = "beat0-inactive-change",
            title = "Beat 0 Inactive Change",
            defaultTheme = "dark",
            themes = mapOf("dark" to DeckThemeSpec(preset = "stageDark")),
            slides = listOf(
                SlideSpec(id = "s0", beats = listOf(BeatSpec())),
                SlideSpec(
                    id = "s1",
                    beats = listOf(
                        BeatSpec(
                            phases = Phases(enter = Timing(delayMs = 100, durationMs = 300)),
                        ),
                    ),
                    nodes = listOf(
                        NodeSpec(
                            id = "card-a",
                            frame = FrameSpec(x = 100.0, y = 100.0, w = 200.0, h = 100.0),
                            text = TextPayload(RichTextValue.Plain("Intro")),
                            builds = listOf(
                                Build.Change(
                                    id = "prep",
                                    beat = 0,
                                    durationMs = 200,
                                    set = buildJsonObject { put("opacity", 0.9) },
                                ),
                                Build.Enter(id = "in1", beat = 0, effect = "fade", durationMs = 300),
                                Build.Enter(id = "in2", beat = 0, effect = "fade", durationMs = 200),
                            ),
                        ),
                        NodeSpec(
                            id = "card-b",
                            frame = FrameSpec(x = 400.0, y = 100.0, w = 200.0, h = 100.0),
                            text = TextPayload(RichTextValue.Plain("Other")),
                            builds = listOf(
                                Build.Enter(id = "in-b", beat = 0, effect = "fade", after = "card-a#prep", durationMs = 250),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val store = DeckDocumentStore(doc, initialLoad = dev.sebastiano.ciacola.document.DeckLoadState.Loaded)
        val controller = DocumentTimelineController(store = store)

        val tracks = controller.snapshot.trackRefsByTrackId.values
            .filterIsInstance<DocumentTimelineTrackRef.SlideBuildTrack>()
            .filter { it.slideId == "s1" && it.beatIndex == 0 }
        val in1Ref = tracks.single { it.nodeId == "card-a" && it.buildId == "in1" }
        val in2Ref = tracks.single { it.nodeId == "card-a" && it.buildId == "in2" }
        val inBRef = tracks.single { it.nodeId == "card-b" && it.buildId == "in-b" }

        assertTrue(in1Ref.isUnsequenced, "Inactive beat-0 Change must not classify first beat-0 Enter as sequenced")
        assertFalse(in2Ref.isUnsequenced, "Second beat-0 Enter must still be sequenced after in1")
        assertTrue(inBRef.isUnsequenced, "Beat-0 Enter referencing inactive beat-0 Change is scheduled unsequenced")
        assertEquals(100, in1Ref.trackPlan.startMs, "Initial start includes 100ms enter phase delay")

        // Drag card-a#in1 to 400ms -> writes beats[0].stagger.manual["card-a"] = 300ms (leaving build.delayMs null)
        // so planned start lands at exactly 400ms instead of 500ms.
        assertTrue(controller.beginGesture("Move beat-0 enter"))
        assertTrue(controller.dragTrackBarStart(in1Ref, 400, useActiveGesture = true))
        controller.commitActiveGesture()

        assertEquals(300, store.document.slides[1].beats[0].stagger?.manual?.get("card-a"))
        assertNull(store.document.slides[1].nodes.first { it.id == "card-a" }.builds[1].delayMs)
        val updatedIn1 = controller.snapshot.trackRefsByTrackId.values
            .filterIsInstance<DocumentTimelineTrackRef.SlideBuildTrack>()
            .single { it.slideId == "s1" && it.nodeId == "card-a" && it.buildId == "in1" }
        assertEquals(400, updatedIn1.trackPlan.startMs, "Re-planned start must match the dragged 400ms position")

        // Second Enter on beat 0 remains sequenced after in1 (endMs = 700ms); dragging it to 850ms sets delayMs = 150ms.
        val updatedIn2 = controller.snapshot.trackRefsByTrackId.values
            .filterIsInstance<DocumentTimelineTrackRef.SlideBuildTrack>()
            .single { it.slideId == "s1" && it.nodeId == "card-a" && it.buildId == "in2" }
        assertEquals(700, updatedIn2.trackPlan.startMs)
        assertTrue(controller.dragTrackBarStart(updatedIn2, 850, useActiveGesture = false))
        assertEquals(150, store.document.slides[1].nodes.first { it.id == "card-a" }.builds[2].delayMs)
    }

    @Test
    fun resizingStaggeredDeliveryTextMorphAndCodeWarpBarsMatchesRequestedTotalSpan() {
        val doc = DeckDocument(
            id = "resize-span-conversion",
            title = "Resize Span Conversion",
            defaultTheme = "dark",
            themes = mapOf("dark" to DeckThemeSpec(preset = "stageDark")),
            slides = listOf(
                SlideSpec(
                    id = "s1",
                    beats = listOf(BeatSpec(), BeatSpec()),
                    nodes = listOf(
                        NodeSpec(
                            id = "words",
                            frame = FrameSpec(x = 100.0, y = 100.0, w = 500.0, h = 80.0),
                            text = TextPayload(RichTextValue.Plain("One Two Three")),
                            builds = listOf(
                                Build.Enter(
                                    id = "in",
                                    beat = 1,
                                    effect = "fade",
                                    by = dev.sebastiano.ciacola.document.DeliveryBy.Word,
                                    durationMs = 500,
                                    stagger = Stagger(ms = 100),
                                ),
                            ),
                        ),
                        NodeSpec(
                            id = "follow",
                            frame = FrameSpec(x = 100.0, y = 220.0, w = 300.0, h = 60.0),
                            text = TextPayload(RichTextValue.Plain("After")),
                            builds = listOf(
                                Build.Enter(id = "after-words", beat = 1, effect = "fade", after = "words", durationMs = 200),
                            ),
                        ),
                        NodeSpec(
                            id = "morph-node",
                            frame = FrameSpec(x = 100.0, y = 320.0, w = 500.0, h = 80.0),
                            text = TextPayload(RichTextValue.Plain("Alpha Beta")),
                            builds = listOf(
                                Build.Change(
                                    id = "morph",
                                    beat = 1,
                                    durationMs = 400,
                                    set = buildJsonObject {
                                        put("text", buildJsonObject { put("value", "One Two Three Four") })
                                    },
                                    morph = TextMorphSpec(by = MorphBy.Word, stagger = Stagger(ms = 50)),
                                ),
                            ),
                        ),
                        NodeSpec(
                            id = "code-node",
                            frame = FrameSpec(x = 100.0, y = 440.0, w = 600.0, h = 160.0),
                            code = dev.sebastiano.ciacola.document.CodePayload(value = "val x = 1", language = "kotlin"),
                            builds = listOf(
                                Build.Change(
                                    id = "warp",
                                    beat = 1,
                                    durationMs = 1000,
                                    set = buildJsonObject {
                                        put("code", buildJsonObject { put("value", "val x = 42") })
                                    },
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val store = DeckDocumentStore(doc, initialLoad = dev.sebastiano.ciacola.document.DeckLoadState.Loaded)
        val controller = DocumentTimelineController(store = store)

        fun buildRef(nodeId: String) = controller.snapshot.trackRefsByTrackId.values
            .filterIsInstance<DocumentTimelineTrackRef.SlideBuildTrack>()
            .single { it.nodeId == nodeId && it.beatIndex == 1 }

        // 1. 3-word staggered Enter: 200ms max stagger + 500ms duration = 700ms displayed span.
        // Extending right edge to 800ms must persist durationMs = 600ms so re-planned span is 800ms (and follower starts at 800ms).
        val wordsRef = buildRef("words")
        assertEquals(700, wordsRef.trackPlan.durationMs)
        assertTrue(controller.beginGesture("Resize words"))
        assertTrue(controller.resizeTrackBarDuration(wordsRef, 800, useActiveGesture = true))
        controller.commitActiveGesture()
        assertEquals(600, store.document.slides.single().nodes.first { it.id == "words" }.builds.single().durationMs)
        assertEquals(800, buildRef("words").trackPlan.durationMs)
        assertEquals(800, buildRef("follow").trackPlan.startMs, "Dependent build must start at 800ms, not 1000ms")

        // Shrinking below stagger lead-in (200ms) clamps build.durationMs to 0 safely.
        assertTrue(controller.resizeTrackBarDuration(buildRef("words"), 100, useActiveGesture = false))
        assertEquals(0, store.document.slides.single().nodes.first { it.id == "words" }.builds.single().durationMs)

        // 2. Staggered text morph: 150ms max slot start + 400ms duration = 550ms -> resize to 700ms persists 550ms and plans 700ms.
        val morphRef = buildRef("morph-node")
        assertEquals(550, morphRef.trackPlan.durationMs)
        assertTrue(controller.resizeTrackBarDuration(morphRef, 700, useActiveGesture = false))
        assertEquals(550, store.document.slides.single().nodes.first { it.id == "morph-node" }.builds.single().durationMs)
        assertEquals(700, buildRef("morph-node").trackPlan.durationMs)

        // 3. Code warp: resize to 1000ms inverts warpSchedule to the nearest achievable integer span (within 1ms, not 880ms).
        val codeRef = buildRef("code-node")
        assertEquals(880, codeRef.trackPlan.durationMs)
        assertTrue(controller.resizeTrackBarDuration(codeRef, 1000, useActiveGesture = false))
        assertTrue(
            kotlin.math.abs(buildRef("code-node").trackPlan.durationMs - 1000) <= 1,
            "Code warp span should match 1000ms within 1ms rounding, got ${buildRef("code-node").trackPlan.durationMs}",
        )
    }

    @Test
    fun resizingTextMorphWithExplicitPhaseDurationsUpdatesControllingPhases() {
        // 1. Slide build with explicit durations on all morph phases (exit=500, move=500, enter=500).
        val explicitPhasesDoc = DeckDocument(
            id = "explicit-morph-phases",
            title = "Explicit Morph Phases",
            defaultTheme = "dark",
            themes = mapOf("dark" to DeckThemeSpec(preset = "stageDark")),
            slides = listOf(
                SlideSpec(
                    id = "s1",
                    beats = listOf(BeatSpec(), BeatSpec()),
                    nodes = listOf(
                        NodeSpec(
                            id = "explicit-morph",
                            frame = FrameSpec(x = 100.0, y = 100.0, w = 600.0, h = 80.0),
                            text = TextPayload(RichTextValue.Plain("Old shared text")),
                            builds = listOf(
                                Build.Change(
                                    id = "morph",
                                    beat = 1,
                                    set = buildJsonObject {
                                        put("text", buildJsonObject { put("value", "New shared copy") })
                                    },
                                    morph = TextMorphSpec(
                                        by = MorphBy.Word,
                                        phases = Phases(
                                            exit = Timing(durationMs = 500),
                                            move = Timing(durationMs = 500),
                                            enter = Timing(durationMs = 500),
                                        ),
                                    ),
                                ),
                            ),
                        ),
                        NodeSpec(
                            id = "spring-morph",
                            frame = FrameSpec(x = 100.0, y = 220.0, w = 600.0, h = 80.0),
                            text = TextPayload(RichTextValue.Plain("Before")),
                            builds = listOf(
                                Build.Change(
                                    id = "spring",
                                    beat = 1,
                                    set = buildJsonObject {
                                        put("text", buildJsonObject { put("value", "After") })
                                    },
                                    morph = TextMorphSpec(
                                        by = MorphBy.Word,
                                        phases = Phases(
                                            exit = Timing(durationMs = 400, curve = Curve.Spring(0.8, 300.0)),
                                            move = Timing(durationMs = 400, curve = Curve.Spring(0.8, 300.0)),
                                            enter = Timing(durationMs = 400, curve = Curve.Spring(0.8, 300.0)),
                                        ),
                                    ),
                                ),
                            ),
                        ),
                    ),
                    transition = TransitionSpec(
                        type = TransitionType.MagicMove,
                        phases = Phases(move = Timing(durationMs = 450)),
                        text = mapOf(
                            "explicit-morph" to TextMorphSpec(
                                by = MorphBy.Word,
                                phases = Phases(
                                    exit = Timing(durationMs = 500),
                                    move = Timing(durationMs = 500),
                                    enter = Timing(durationMs = 500),
                                ),
                            ),
                        ),
                    ),
                ),
                SlideSpec(
                    id = "s2",
                    beats = listOf(BeatSpec()),
                    nodes = listOf(
                        NodeSpec(
                            id = "explicit-morph",
                            frame = FrameSpec(x = 100.0, y = 100.0, w = 600.0, h = 80.0),
                            text = TextPayload(RichTextValue.Plain("Final shared line")),
                        ),
                    ),
                ),
            ),
        )
        val store = DeckDocumentStore(explicitPhasesDoc, initialLoad = dev.sebastiano.ciacola.document.DeckLoadState.Loaded)
        val controller = DocumentTimelineController(store = store)

        fun slideMorphRef(nodeId: String) = controller.snapshot.trackRefsByTrackId.values
            .filterIsInstance<DocumentTimelineTrackRef.SlideBuildTrack>()
            .single { it.nodeId == nodeId && it.beatIndex == 1 }

        // Extending from 500ms to 800ms updates controlling explicit phases to 800ms (not unused build.durationMs).
        assertEquals(500, slideMorphRef("explicit-morph").trackPlan.durationMs)
        assertTrue(controller.resizeTrackBarDuration(slideMorphRef("explicit-morph"), 800, useActiveGesture = false))
        val extendedBuild = store.document.slides[0].nodes.first { it.id == "explicit-morph" }.builds.single() as Build.Change
        assertNull(extendedBuild.durationMs, "Unused build.durationMs fallback should remain untouched")
        assertEquals(800, extendedBuild.morph?.phases?.exit?.durationMs)
        assertEquals(800, extendedBuild.morph?.phases?.move?.durationMs)
        assertEquals(800, extendedBuild.morph?.phases?.enter?.durationMs)
        assertEquals(800, slideMorphRef("explicit-morph").trackPlan.durationMs)

        // Shrinking from 800ms to 300ms shrinks all exceeding phases cleanly.
        assertTrue(controller.resizeTrackBarDuration(slideMorphRef("explicit-morph"), 300, useActiveGesture = false))
        assertEquals(300, slideMorphRef("explicit-morph").trackPlan.durationMs)

        // All-spring morph cannot be resized by durationMs and leaves the document untouched.
        val beforeSpring = store.document
        assertFalse(controller.resizeTrackBarDuration(slideMorphRef("spring-morph"), 1200, useActiveGesture = false))
        assertEquals(beforeSpring, store.document)

        // Cross-slide transition text morph with explicit transition.text phases updates those phases directly.
        fun trMorphRef() = controller.snapshot.trackRefsByTrackId.values
            .filterIsInstance<DocumentTimelineTrackRef.TransitionTrackBar>()
            .single { it.fromSlideId == "s1" && it.nodeId == "explicit-morph" && it.isTextMorph }

        assertEquals(500, trMorphRef().trackPlan.durationMs)
        assertTrue(controller.resizeTrackBarDuration(trMorphRef(), 800, useActiveGesture = false))
        assertEquals(450, store.document.slides[0].transition?.phases?.move?.durationMs, "Shared transition move duration stays unchanged")
        assertEquals(800, trMorphRef().trackPlan.durationMs)
        assertTrue(controller.resizeTrackBarDuration(trMorphRef(), 300, useActiveGesture = false))
        assertEquals(300, trMorphRef().trackPlan.durationMs)

        // 2. Beat-inherited & transition-inherited explicit phases (sampleDeck):
        // Resizing headline materializes per-morph phases without mutating shared beat/transition phases.
        val (sampleStore, sampleController) = createController()
        val initialBeatPhases = sampleStore.document.slides[0].beats[1].phases
        val initialTransitionPhases = sampleStore.document.slides[0].transition?.phases
        fun sampleHeadlineBuild() = sampleController.snapshot.trackRefsByTrackId.values
            .filterIsInstance<DocumentTimelineTrackRef.SlideBuildTrack>()
            .single { it.slideId == "s1" && it.nodeId == "headline" && it.isTextMorph }

        assertTrue(sampleController.resizeTrackBarDuration(sampleHeadlineBuild(), 800, useActiveGesture = false))
        assertEquals(800, sampleHeadlineBuild().trackPlan.durationMs)
        assertEquals(initialBeatPhases, sampleStore.document.slides[0].beats[1].phases, "Shared beat.phases must stay untouched")

        assertTrue(sampleController.resizeTrackBarDuration(sampleHeadlineBuild(), 420, useActiveGesture = false))
        assertEquals(420, sampleHeadlineBuild().trackPlan.durationMs)
        val shrunkBuild = sampleStore.document.slides[0].nodes.first { it.id == "headline" }.builds.single() as Build.Change
        assertEquals(200, shrunkBuild.morph?.phases?.exit?.durationMs, "Non-exceeding exit phase stays intact")

        // Shrinking headline's cross-slide morph below enter phase's 520ms end materializes per-node transition.text phases.
        fun sampleHeadlineTr() = sampleController.snapshot.trackRefsByTrackId.values
            .filterIsInstance<DocumentTimelineTrackRef.TransitionTrackBar>()
            .single { it.fromSlideId == "s1" && it.nodeId == "headline" && it.isTextMorph }

        assertEquals(520, sampleHeadlineTr().trackPlan.durationMs)
        assertTrue(sampleController.resizeTrackBarDuration(sampleHeadlineTr(), 400, useActiveGesture = false))
        assertEquals(400, sampleHeadlineTr().trackPlan.durationMs)
        assertEquals(
            initialTransitionPhases,
            sampleStore.document.slides[0].transition?.phases,
            "Shared transition.phases must stay untouched when shrinking a text morph past explicit enter phase",
        )
    }

    @Test
    fun cachedTransitionTemplatesUpdateUntitledSlideIndexAndBlockRefViewMatchesTrackRefs() {
        val doc = DeckDocument(
            id = "cached-template-test",
            themes = mapOf("dark" to DeckThemeSpec(preset = "stageDark")),
            defaultTheme = "dark",
            slides = listOf(
                SlideSpec(
                    id = "s1",
                    title = null,
                    beats = listOf(BeatSpec(), BeatSpec()),
                    nodes = listOf(
                        NodeSpec(
                            id = "h1",
                            frame = FrameSpec(100.0, 100.0, 600.0, 100.0),
                            text = TextPayload(value = RichTextValue.Plain("**Bold** `code` ~~old~~")),
                            builds = listOf(Build.Enter(beat = 1, effect = "fade", durationMs = 300)),
                        ),
                    ),
                    transition = TransitionSpec(type = TransitionType.Dissolve, durationMs = 350),
                ),
                SlideSpec(
                    id = "s2",
                    title = null,
                    beats = listOf(BeatSpec()),
                    nodes = listOf(
                        NodeSpec(
                            id = "h2",
                            frame = FrameSpec(100.0, 100.0, 600.0, 100.0),
                            text = TextPayload(value = RichTextValue.Plain("Plain title")),
                        ),
                    ),
                ),
            ),
        )

        val store = DeckDocumentStore()
        store.loadInitial(doc)
        val controller = DocumentTimelineController(store = store)

        // Initial snapshot: s1 -> s2 targets untitled scene index 1 ("Slide 2").
        val snapBefore = controller.snapshot
        val transRowBefore = snapBefore.snapshot.tracks.single { it.trackId.startsWith("doc:trans:s1->s2:") }
        assertEquals("→ Slide 2 · Dissolve", transRowBefore.label)
        assertEquals("→ Slide 2 · Dissolve", transRowBefore.blocks.single().label)

        // Verify markdown stripping matches both markdown and plain strings.
        val buildRow = snapBefore.snapshot.tracks.single { it.trackId.startsWith("doc:s1:b1:h1:") }
        assertTrue(buildRow.label.contains("Bold code old"), "Expected stripped markdown label, got '${buildRow.label}'")
        assertEquals("Plain title", TimelineDisplayNames.stripMarkdownPunctuation("  Plain title  "))
        assertEquals("Heading bold code", TimelineDisplayNames.stripMarkdownPunctuation("## Heading **bold** `code`"))

        // Verify BlockRefMapView exposes exact 1:1 `"$trackId#0"` entries and rejects non-block keys.
        assertEquals(snapBefore.trackRefsByTrackId.size, snapBefore.blockRefsByBlockId.size)
        for ((trackId, ref) in snapBefore.trackRefsByTrackId) {
            val blockId = "$trackId#0"
            assertTrue(blockId in snapBefore.blockRefsByBlockId)
            assertEquals(ref, snapBefore.blockRefsByBlockId[blockId])
        }
        assertFalse("missing#0" in snapBefore.blockRefsByBlockId)
        assertNull(snapBefore.blockRefsByBlockId["doc:s1:b1:h1:0"])
        assertEquals(snapBefore.trackRefsByTrackId.size, snapBefore.blockRefsByBlockId.entries.size)

        // Prepend a new slide s0 BEFORE s1->s2: s1->s2 remains cached (NOT replanned!), yet its untitled label updates to Slide 3!
        assertTrue(
            store.edit("Prepend s0") { d ->
                val s0 = SlideSpec(
                    id = "s0",
                    title = "Intro",
                    beats = listOf(BeatSpec()),
                    nodes = listOf(NodeSpec(id = "n0", text = TextPayload(value = RichTextValue.Plain("Intro")))),
                )
                d.copy(slides = listOf(s0) + d.slides)
            },
        )
        val snapAfter = controller.refreshSnapshot()
        assertFalse(
            ("s1" to "s2") in snapAfter.diagnostics.replannedTransitions,
            "s1->s2 transition should stay cached when s0 is prepended",
        )
        val transRowAfter = snapAfter.snapshot.tracks.single { it.trackId.startsWith("doc:trans:s1->s2:") }
        assertEquals("→ Slide 3 · Dissolve", transRowAfter.label)
        assertEquals("→ Slide 3 · Dissolve", transRowAfter.blocks.single().label)
    }

    private fun collectAllSemanticTexts(root: SemanticsNode): List<String> {
        val out = mutableListOf<String>()
        fun walk(node: SemanticsNode) {
            node.config.getOrNull(SemanticsProperties.Text)?.forEach { out += it.text }
            node.config.getOrNull(SemanticsProperties.ContentDescription)?.forEach { out += it }
            node.children.forEach(::walk)
        }
        walk(root)
        return out
    }
}
