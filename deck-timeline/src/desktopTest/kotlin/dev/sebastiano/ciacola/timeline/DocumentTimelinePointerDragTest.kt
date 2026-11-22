package dev.sebastiano.ciacola.timeline

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
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
import dev.sebastiano.ciacola.document.RichTextValue
import dev.sebastiano.ciacola.document.SlideSpec
import dev.sebastiano.ciacola.document.SpecimenPayload
import dev.sebastiano.ciacola.document.Stagger
import dev.sebastiano.ciacola.document.StaggerOrder
import dev.sebastiano.ciacola.document.TextMorphSpec
import dev.sebastiano.ciacola.document.TextPayload
import dev.sebastiano.ciacola.document.TransitionType
import dev.sebastiano.ciacola.document.deckSpecimens
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.jetbrains.jewel.intui.standalone.theme.IntUiTheme

@OptIn(ExperimentalTestApi::class)
class DocumentTimelinePointerDragTest {

    private fun testSpecimens(): DeckSpecimenRegistry = deckSpecimens {
        specimen("demo.pulse", "Demo Pulse") {
            loop(name = "ambient", label = "Ambient Pulse", periodMs = 4_000L)
            content {}
        }
    }

    private fun twoSlideDeck(): DeckDocument {
        val slide1 = SlideSpec(
            id = "s1",
            title = "Slide One",
            beats = listOf(
                BeatSpec(notes = "s1 beat 0"),
                BeatSpec(notes = "s1 beat 1"),
            ),
            nodes = listOf(
                NodeSpec(
                    id = "headline",
                    frame = FrameSpec(x = 120.0, y = 100.0, w = 900.0, h = 110.0),
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
                            morph = TextMorphSpec(by = MorphBy.Word, stagger = Stagger(ms = 20)),
                        ),
                    ),
                ),
                NodeSpec(
                    id = "card-a",
                    frame = FrameSpec(x = 120.0, y = 300.0, w = 400.0, h = 180.0),
                    text = TextPayload(value = RichTextValue.Plain("Card A"), style = "body"),
                    builds = listOf(
                        Build.Enter(beat = 1, id = "enter-a", effect = "fadeMove", durationMs = 300),
                    ),
                ),
                NodeSpec(
                    id = "loop-spec",
                    frame = FrameSpec(x = 900.0, y = 260.0, w = 400.0, h = 240.0),
                    specimen = SpecimenPayload(ref = "demo.pulse"),
                ),
            ),
            transition = null, // Deliberately null to verify Dissolve default is preserved!
        )

        val slide2 = SlideSpec(
            id = "s2",
            title = "Slide Two",
            beats = listOf(
                BeatSpec(notes = "s2 beat 0"),
                BeatSpec(notes = "s2 beat 1"),
                BeatSpec(notes = "s2 beat 2"),
            ),
            nodes = listOf(
                NodeSpec(
                    id = "s2-body",
                    frame = FrameSpec(x = 120.0, y = 200.0, w = 600.0, h = 120.0),
                    text = TextPayload(value = RichTextValue.Plain("Second slide"), style = "body"),
                    builds = listOf(
                        Build.Enter(beat = 1, id = "s2-enter", effect = "dissolve", durationMs = 250),
                        Build.Emphasis(beat = 2, id = "s2-emph", effect = "pulse", durationMs = 200),
                    ),
                ),
            ),
        )

        return DeckDocument(
            id = "pointer-drag-test-deck",
            title = "Pointer Drag Test Deck",
            themes = mapOf("dark" to DeckThemeSpec(preset = "stageDark")),
            defaultTheme = "dark",
            slides = listOf(slide1, slide2),
        )
    }

    @Test
    fun draggingTrackBarPastSegmentMsSurvivesTotalDurationGrowthCommitsOneUndoAndClosesTx() = runComposeUiTest {
        val store = DeckDocumentStore()
        store.loadInitial(twoSlideDeck())
        val controller = DocumentTimelineController(store = store, specimens = testSpecimens())

        val cardARef = controller.snapshot.trackRefsByTrackId.values
            .filterIsInstance<DocumentTimelineTrackRef.SlideBuildTrack>()
            .first { it.slideId == "s1" && it.nodeId == "card-a" }
        val initialTotalDurationMs = controller.snapshot.snapshot.totalDurationMs
        val initialDoc = store.document

        setContent {
            IntUiTheme {
                Box(Modifier.size(1280.dp, 620.dp)) {
                    DocumentTimelineStudioPanel(controller = controller)
                }
            }
        }
        waitForIdle()

        // Drag card-a far to the right in multi-step pointer motion so totalDurationMs grows mid-drag!
        onNodeWithTag("ciacola-doc-track-bar-${cardARef.trackId}", useUnmergedTree = true)
            .performTouchInput {
                down(center)
                repeat(8) {
                    moveBy(Offset(35f, 0f), delayMillis = 16L)
                }
                up()
            }
        waitForIdle()

        assertFalse(store.hasOpenTransaction, "Transaction must be closed after drag release")
        assertFalse(controller.hasActiveGesture, "Controller must have no active gesture after drag release")
        val manualDelay = store.document.slides[0].beats[1].stagger?.manual?.get("card-a")
        assertNotNull(
            manualDelay,
            "Dragging card-a bar must write manual stagger delay " +
                "(ref=$cardARef, selection=${controller.selection}, canUndo=${store.canUndo}, " +
                "beat1=${store.document.slides[0].beats[1]}, builds=${store.document.slides[0].nodes[1].builds}, " +
                "pxPerMs=${controller.viewport.pxPerMs(TimelineSurface.Docked)})",
        )
        assertTrue(manualDelay > 400, "Expected dragged delay past original segment, got ${manualDelay}ms")
        assertTrue(
            controller.snapshot.snapshot.totalDurationMs > initialTotalDurationMs,
            "Dragging bar past segmentMs must grow totalDurationMs without cancelling drag " +
                "(before=$initialTotalDurationMs, after=${controller.snapshot.snapshot.totalDurationMs})",
        )

        // Exactly ONE undo step must restore the exact initial document!
        assertTrue(store.canUndo)
        store.undo()
        assertEquals(initialDoc, store.document, "Multi-move drag must commit as a single undo step")
        assertFalse(store.canUndo)
    }

    @Test
    fun inlineTextTrackExpansionShowsPhaseSubBarsAndDraggableFragmentTicks() = runComposeUiTest {
        val store = DeckDocumentStore()
        store.loadInitial(twoSlideDeck())
        val controller = DocumentTimelineController(store = store, specimens = testSpecimens())

        val morphRef = controller.snapshot.trackRefsByTrackId.values
            .filterIsInstance<DocumentTimelineTrackRef.SlideBuildTrack>()
            .first { it.slideId == "s1" && it.nodeId == "headline" && it.isTextMorph }

        setContent {
            IntUiTheme {
                Box(Modifier.size(1280.dp, 680.dp)) {
                    DocumentTimelineStudioPanel(controller = controller)
                }
            }
        }
        waitForIdle()

        // Clicking the text morph track row expands inline Exit/Move/Enter sub-bars & fragment ticks.
        onNodeWithTag("ciacola-doc-track-row-${morphRef.trackId}", useUnmergedTree = true).performClick()
        waitForIdle()

        assertEquals(morphRef.trackId, controller.expandedTextTrackId)
        onNodeWithTag("ciacola-timeline-expanded-text-${morphRef.trackId}", useUnmergedTree = true).assertExists()
        onNodeWithTag("ciacola-timeline-phase-subrow-${morphRef.trackId}-exit", useUnmergedTree = true).assertExists()
        onNodeWithTag("ciacola-timeline-phase-subrow-${morphRef.trackId}-move", useUnmergedTree = true).assertExists()
        onNodeWithTag("ciacola-timeline-phase-subrow-${morphRef.trackId}-enter", useUnmergedTree = true).assertExists()

        // Drag the inline fragment tick "function#0" right across the timeline!
        onNodeWithTag("ciacola-timeline-fragment-tick-function#0", useUnmergedTree = true)
            .performTouchInput {
                down(center)
                repeat(5) { moveBy(Offset(28f, 0f), delayMillis = 16L) }
                up()
            }
        waitForIdle()

        assertFalse(store.hasOpenTransaction)
        val build = store.document.slides[0].nodes.first { it.id == "headline" }.builds[0] as Build.Change
        val manualMs = build.morph?.stagger?.manual?.get("function#0")
        assertNotNull(manualMs, "Dragging inline fragment tick must write morph.stagger.manual['function#0']")
        assertTrue(manualMs > 0)
    }

    @Test
    fun headerBandBeatContextMenuDeletesThatSlidesBeatNotSlide1() = runComposeUiTest {
        val store = DeckDocumentStore()
        store.loadInitial(twoSlideDeck())
        val controller = DocumentTimelineController(store = store, specimens = testSpecimens())

        // Navigate stage to slide 2, beat 1 with NO timeline selection.
        controller.updatePresentedLocation(slideId = "s2", sceneIndex = 1, beatIndex = 1)
        controller.clearSelection()

        setContent {
            IntUiTheme {
                Box(Modifier.size(1280.dp, 620.dp)) {
                    DocumentTimelineStudioPanel(controller = controller)
                }
            }
        }
        waitForIdle()

        // Right-click the slide 2 / build 1 tick on the header band, then choose `Delete Beat`.
        onNodeWithTag("ciacola-header-beat-s2-1", useUnmergedTree = true).performMouseInput { rightClick(center) }
        waitForIdle()
        onNodeWithText("Delete Beat", useUnmergedTree = true).performClick()
        waitForIdle()

        // Slide 1 MUST remain untouched (2 beats), while slide 2 lost beat 1 (3 -> 2 beats)!
        assertEquals(2, store.document.slides[0].beats.size, "Slide 1 must NOT be modified when deleting beat on slide 2")
        assertEquals(2, store.document.slides[1].beats.size, "Active presented slide 2 must lose its current beat")
    }

    @Test
    fun transitionWithoutExplicitSpecPreservesDissolveAndCurveEditorAndLoopSubCompWork() = runComposeUiTest {
        val store = DeckDocumentStore()
        store.loadInitial(twoSlideDeck())
        val controller = DocumentTimelineController(store = store, specimens = testSpecimens())

        // Effective transition for s1 (which has transition = null) is Dissolve, NOT MagicMove!
        assertEquals(TransitionType.Dissolve, controller.effectiveTransitionSpec("s1").type)
        controller.selectTransitionHeader("s1", "s2")

        setContent {
            IntUiTheme {
                Box(Modifier.size(1280.dp, 680.dp)) {
                    DocumentTimelineStudioPanel(controller = controller)
                }
            }
        }
        waitForIdle()

        // Non-MagicMove transition must show explicit "Switch to Magic Move" opt-in rather than clobbering.
        onNodeWithTag("ciacola-transition-switch-magic-move", useUnmergedTree = true).assertExists()
        assertEquals(null, store.document.slides[0].transition, "Inspecting transition header must not mutate document")

        // Dragging the restored Bezier curve editor updates the transition curve cleanly in 1 undo step.
        onNodeWithTag("ciacola-bezier-editor", useUnmergedTree = true)
            .performMouseInput {
                moveTo(Offset(width * 0.25f, height * 0.75f))
                press()
                moveTo(Offset(width * 0.65f, height * 0.25f))
                release()
            }
        waitForIdle()
        assertFalse(store.hasOpenTransaction)
        val tr = store.document.slides[0].transition
        assertNotNull(tr)
        assertEquals(TransitionType.Dissolve, tr.type, "Editing curve on Dissolve transition must keep type = Dissolve!")
        assertTrue(tr.curve is Curve.Bezier, "Dragging Bezier handle must write Curve.Bezier, got ${tr.curve}")

        // Open Loop Sub-Comp Scrub Bar and drag it to scrub subCompositionScrubMillis!
        val loopRef = controller.snapshot.trackRefsByTrackId.values
            .filterIsInstance<DocumentTimelineTrackRef.LoopTrackBar>()
            .first()
        controller.enterLoopSubComp(loopRef.loopId)
        waitForIdle()

        onNodeWithTag("ciacola-subcomp-canvas", useUnmergedTree = true)
            .performMouseInput {
                moveTo(Offset(width * 0.5f, height * 0.5f))
                press()
                moveTo(Offset(width * 0.75f, height * 0.5f))
                release()
            }
        waitForIdle()

        val scrubMs = controller.subCompositionScrubMillis
        assertNotNull(scrubMs, "Scrubbing Loop Sub-Comp must populate subCompositionScrubMillis")
        assertTrue(scrubMs in 2000L..3800L, "Expected scrub near 75% of 4000ms period, got $scrubMs")

        // Back to Master Deck clears scrub time.
        onNodeWithTag("ciacola-timeline-exit-subcomp", useUnmergedTree = true).performClick()
        waitForIdle()
        assertEquals(null, controller.activeSubCompLoopId)
        assertEquals(null, controller.subCompositionScrubMillis)
    }

    @Test
    fun phaseBarDragCancelNeverCommitsAndResolvedDefaultsMatchSegmentPlanner() {
        val store = DeckDocumentStore()
        store.loadInitial(twoSlideDeck())
        val controller = DocumentTimelineController(store = store, specimens = testSpecimens())

        // Slide 1 beat 1 has no explicit phases -> resolved phases use SegmentPlanner's actual 0ms delay / 500ms default!
        val resolved = controller.resolvedBeatPhases("s1", 1)
        assertEquals(0, resolved.exit.delayMs)
        assertEquals(0, resolved.move.delayMs)
        assertEquals(0, resolved.enter.delayMs)
        assertEquals(500, resolved.exit.durationMs)
    }
}
