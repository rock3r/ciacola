package dev.sebastiano.ciacola.timeline

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import dev.sebastiano.ciacola.document.BeatSpec
import dev.sebastiano.ciacola.document.Build
import dev.sebastiano.ciacola.document.Curve
import dev.sebastiano.ciacola.document.DeckDocument
import dev.sebastiano.ciacola.document.DeckDocumentStore
import dev.sebastiano.ciacola.document.DeckLoadState
import dev.sebastiano.ciacola.document.FrameSpec
import dev.sebastiano.ciacola.document.MorphBy
import dev.sebastiano.ciacola.document.NodeSpec
import dev.sebastiano.ciacola.document.Phases
import dev.sebastiano.ciacola.document.RichTextValue
import dev.sebastiano.ciacola.document.SlideSpec
import dev.sebastiano.ciacola.document.Slot
import dev.sebastiano.ciacola.document.SlotPhase
import dev.sebastiano.ciacola.document.TextMorphSpec
import dev.sebastiano.ciacola.document.TextPayload
import dev.sebastiano.ciacola.document.Timing
import dev.sebastiano.ciacola.document.TransitionSpec
import dev.sebastiano.ciacola.document.TransitionType
import dev.sebastiano.ciacola.ui.CiacolaJewelTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

@OptIn(ExperimentalTestApi::class)
class TimelineUxPolishTest {

    private fun multiSlideDeckWithStaticAndMotionSlides(): DeckDocument {
        val slide1 = SlideSpec(
            id = "slide-motion",
            title = "Motion Slide",
            beats = listOf(
                BeatSpec(notes = "Intro"),
                BeatSpec(
                    notes = "Morph words",
                    phases = Phases(
                        exit = Timing(delayMs = 0, durationMs = 200, curve = Curve.Preset("easeIn")),
                        move = Timing(delayMs = 80, durationMs = 420, curve = Curve.Preset("easeOut")),
                        enter = Timing(delayMs = 150, durationMs = 350, curve = Curve.Preset("easeInOut")),
                    ),
                ),
            ),
            nodes = listOf(
                NodeSpec(
                    id = "headline",
                    frame = FrameSpec(x = 120.0, y = 100.0, w = 960.0, h = 120.0),
                    text = TextPayload(
                        value = RichTextValue.Plain("Story begins with text"),
                        style = "title",
                        morph = TextMorphSpec(by = MorphBy.Word),
                    ),
                    builds = listOf(
                        Build.Change(
                            beat = 1,
                            id = "headline-morph",
                            set = buildJsonObject {
                                putJsonObject("text") { put("value", "Story live with motion") }
                            },
                            morph = TextMorphSpec(by = MorphBy.Word),
                        ),
                    ),
                ),
            ),
            transition = TransitionSpec(type = TransitionType.Dissolve, durationMs = 500),
        )
        val slide2 = SlideSpec(
            id = "slide-static",
            title = "Static Slide",
            beats = listOf(BeatSpec(notes = "No motion here")),
            nodes = listOf(
                NodeSpec(
                    id = "static-label",
                    frame = FrameSpec(x = 120.0, y = 100.0, w = 600.0, h = 80.0),
                    text = TextPayload(value = RichTextValue.Plain("Pure static slide")),
                ),
            ),
            transition = TransitionSpec(type = TransitionType.None),
        )
        val slide3 = SlideSpec(
            id = "slide-late",
            title = "Late Motion Slide",
            beats = listOf(BeatSpec(), BeatSpec()),
            nodes = listOf(
                NodeSpec(
                    id = "late-card",
                    frame = FrameSpec(x = 160.0, y = 180.0, w = 500.0, h = 200.0),
                    text = TextPayload(value = RichTextValue.Plain("Late reveal")),
                    builds = listOf(Build.Enter(beat = 1, id = "late-in", effect = "fade")),
                ),
            ),
        )
        return DeckDocument(
            id = "timeline-ux-test",
            title = "Timeline UX Test",
            slides = listOf(slide1, slide2, slide3),
        )
    }

    @Test
    fun humanFragmentLabelsCleanRawAddressesAndDisambiguateDuplicates() {
        val slots = listOf(
            Slot(address = "-begins#0", startMs = 0, durationMs = 200, curve = Curve.EaseOut, phase = SlotPhase.Exit),
            Slot(address = "-with#0", startMs = 80, durationMs = 400, curve = Curve.EaseOut, phase = SlotPhase.Move),
            Slot(address = "live#0", startMs = 150, durationMs = 350, curve = Curve.EaseOut, phase = SlotPhase.Enter),
            Slot(address = "the#0", startMs = 100, durationMs = 300, curve = Curve.EaseOut, phase = SlotPhase.Move),
            Slot(address = "the#1", startMs = 160, durationMs = 300, curve = Curve.EaseOut, phase = SlotPhase.Move),
        )
        val counts = computeFragmentBaseTokenCounts(slots)

        fun fmt(slot: Slot) = formatHumanFragmentLabel(
            address = slot.address,
            phase = slot.phase,
            duplicateCountForBaseToken = counts[extractFragmentBaseToken(slot.address)] ?: 1,
        )

        val exitFmt = fmt(slots[0])
        assertEquals("\"begins\" (exit)", exitFmt.displayLabel)
        assertEquals("-begins#0", exitFmt.rawAddress)
        assertTrue(exitFmt.hasLeadingSpace)

        val moveFmt = fmt(slots[1])
        assertEquals("\"with\" (move)", moveFmt.displayLabel)
        assertTrue(moveFmt.hasLeadingSpace)

        val enterFmt = fmt(slots[2])
        assertEquals("\"live\" (enter)", enterFmt.displayLabel)

        val dup1 = fmt(slots[3])
        val dup2 = fmt(slots[4])
        assertEquals("\"the\" #1 (move)", dup1.displayLabel)
        assertEquals("\"the\" #2 (move)", dup2.displayLabel)
    }

    @Test
    fun syncSelectionOnSlideChangeUpdatesStaleCrossSlideSelectionWhilePreservingNoneAndSharedTransitions() {
        val store = DeckDocumentStore(multiSlideDeckWithStaticAndMotionSlides(), initialLoad = DeckLoadState.Loaded)
        val controller = DocumentTimelineController(store)

        // 1. Selecting a track on slide 1 then advancing to slide 3 syncs selection to slide 3's beat header.
        val firstTrackId = controller.snapshot.snapshot.tracks.first { it.sceneIndex == 0 }.trackId
        controller.selectTrack(firstTrackId)
        controller.updatePresentedLocation("slide-late", sceneIndex = 2, beatIndex = 0)
        val synced = assertIs<DocumentTimelineSelection.BeatHeader>(controller.selection)
        assertEquals("slide-late", synced.slideId)
        assertEquals(0, synced.beatIndex)

        // 2. Explicit Esc -> None stays None across slide changes.
        controller.clearSelection()
        controller.updatePresentedLocation("slide-static", sceneIndex = 1, beatIndex = 0)
        assertEquals(DocumentTimelineSelection.None, controller.selection)

        // 3. Selecting TransitionHeader(slide-motion -> slide-static) stays valid on both endpoints!
        val transSel = DocumentTimelineSelection.TransitionHeader("slide-motion", "slide-static")
        controller.selectTransitionHeader("slide-motion", "slide-static")
        controller.updatePresentedLocation("slide-static", sceneIndex = 1, beatIndex = 0)
        assertEquals(transSel, controller.selection)

        // Advancing further to slide-late replaces stale transition selection.
        controller.updatePresentedLocation("slide-late", sceneIndex = 2, beatIndex = 0)
        val replaced = assertIs<DocumentTimelineSelection.BeatHeader>(controller.selection)
        assertEquals("slide-late", replaced.slideId)
    }

    @Test
    fun fitCurrentScopeFramesActiveSlideInCurrentSlideModeAndFullDeckInAllSlidesMode() {
        val store = DeckDocumentStore(multiSlideDeckWithStaticAndMotionSlides(), initialLoad = DeckLoadState.Loaded)
        val controller = DocumentTimelineController(store)
        controller.viewport.updateSurfaceMetrics(TimelineSurface.Docked, widthPx = 1000f, surfaceDensity = 1f)
        controller.updatePresentedLocation("slide-motion", sceneIndex = 0, beatIndex = 1)

        controller.setTrackFilter(TimelineTrackFilterMode.AllSlides)
        controller.fitCurrentScope(TimelineSurface.Docked)
        val allSlidesDpPerMs = controller.viewport.dpPerMs

        controller.setTrackFilter(TimelineTrackFilterMode.CurrentSlide)
        controller.fitCurrentScope(TimelineSurface.Docked)
        val currentSlideDpPerMs = controller.viewport.dpPerMs

        assertTrue(
            currentSlideDpPerMs > allSlidesDpPerMs * 1.15f,
            "Fit in CurrentSlide mode ($currentSlideDpPerMs dp/ms) must zoom closer than AllSlides ($allSlidesDpPerMs dp/ms)",
        )
        assertTrue(controller.activeSlideTimeRange() != null)
    }

    @Test
    fun singleAxisPhaseUpdatePreservesResolvedCurveWithoutFlickeringToDefault() {
        val store = DeckDocumentStore(multiSlideDeckWithStaticAndMotionSlides(), initialLoad = DeckLoadState.Loaded)
        val controller = DocumentTimelineController(store)
        val before = controller.resolvedBeatPhases("slide-motion", 1)
        assertEquals(Curve.Preset("easeOut"), before.move.curve)

        // Simulate dragging only the Move phase delay while preserving existing duration & curve.
        val ok = controller.updateBeatPhaseTiming(
            slideId = "slide-motion",
            beatIndex = 1,
            phase = SlotPhase.Move,
            delayMs = 140,
            durationMs = before.move.durationMs,
            curve = before.move.curve,
        )
        assertTrue(ok)
        val after = controller.resolvedBeatPhases("slide-motion", 1)
        assertEquals(140, after.move.delayMs)
        assertEquals(before.move.durationMs, after.move.durationMs)
        assertEquals(Curve.Preset("easeOut"), after.move.curve)
    }

    @Test
    fun staticSlideEmptyStateShowsActionableShowAllTracksButton() = runComposeUiTest {
        val store = DeckDocumentStore(multiSlideDeckWithStaticAndMotionSlides(), initialLoad = DeckLoadState.Loaded)
        val controller = DocumentTimelineController(store)
        controller.setTrackFilter(TimelineTrackFilterMode.CurrentSlide)
        controller.updatePresentedLocation("slide-static", sceneIndex = 1, beatIndex = 0)

        setContent {
            CiacolaJewelTheme(isDark = true) {
                Box(Modifier.size(1_100.dp, 420.dp)) {
                    DocumentTimelineStudioPanel(
                        controller = controller,
                        showInlineInspector = false,
                    )
                }
            }
        }
        waitForIdle()

        // Empty state shows slide-specific message + 1-click Show All Tracks button.
        onNodeWithText("No motion tracks on slide 2.", substring = true, useUnmergedTree = true)
            .assertIsDisplayed()
        onNodeWithTag("ciacola-timeline-empty-show-all", useUnmergedTree = true)
            .assertIsDisplayed()
            .performClick()
        waitForIdle()

        assertEquals(TimelineTrackFilterMode.AllSlides, controller.trackFilterMode)
        assertTrue(controller.snapshot.snapshot.tracks.isNotEmpty(), "Switching to AllSlides must reveal deck tracks")
    }

    @Test
    fun animateFragmentsShowRuleAndCountWithoutDuplicatingPerFragmentRows() = runComposeUiTest {
        val store = DeckDocumentStore(multiSlideDeckWithStaticAndMotionSlides(), initialLoad = DeckLoadState.Loaded)
        val controller = DocumentTimelineController(store)
        controller.updatePresentedLocation("slide-motion", sceneIndex = 0, beatIndex = 1)
        val morphRef = controller.snapshot.trackRefsByTrackId.values
            .filterIsInstance<DocumentTimelineTrackRef.SlideBuildTrack>()
            .first { it.nodeId == "headline" }
        controller.selectTrack(morphRef.trackId)

        setContent {
            CiacolaJewelTheme(isDark = true) {
                Box(Modifier.size(420.dp, 620.dp)) {
                    IntraTextFragmentStaggerEditor(controller = controller, trackRef = morphRef)
                }
            }
        }
        waitForIdle()

        val slots = controller.fragmentSlotsFor(morphRef)
        assertTrue(slots.isNotEmpty())
        // The summary states the count; per-fragment bars live only in the timeline sub-rows.
        onNodeWithTag("ciacola-fragment-summary", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithText("${slots.size} words", substring = true, useUnmergedTree = true).assertIsDisplayed()
        for (slot in slots) {
            onNodeWithTag("ciacola-fragment-row-${slot.address}", useUnmergedTree = true).assertDoesNotExist()
            onNodeWithTag("ciacola-fragment-tick-${slot.address}", useUnmergedTree = true).assertDoesNotExist()
        }
    }

    @Test
    fun animateInspectorHasBottomScrollFadeAndScrollClearance() = runComposeUiTest {
        val store = DeckDocumentStore(multiSlideDeckWithStaticAndMotionSlides(), initialLoad = DeckLoadState.Loaded)
        val controller = DocumentTimelineController(store)
        controller.updatePresentedLocation("slide-motion", sceneIndex = 0, beatIndex = 1)
        val morphRef = controller.snapshot.trackRefsByTrackId.values
            .filterIsInstance<DocumentTimelineTrackRef.SlideBuildTrack>()
            .first { it.nodeId == "headline" }
        controller.selectTrack(morphRef.trackId)

        setContent {
            CiacolaJewelTheme(isDark = true) {
                Box(Modifier.size(320.dp, 260.dp)) {
                    DocumentTimelineMotionInspector(controller = controller)
                }
            }
        }
        waitForIdle()

        onNodeWithTag("ciacola-doc-animate-bottom-fade", useUnmergedTree = true).assertIsDisplayed()
        onAllNodesWithTag("ciacola-doc-animate-scroll-fade", useUnmergedTree = true).let {
            assertEquals(0, it.fetchSemanticsNodes().size, "Top scroll fade must stay hidden at scroll top")
        }

        // Outer box has opaque panel background and keeps the full 260dp height.
        val rootBounds = onNodeWithTag("ciacola-doc-animate-panel-root", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        assertTrue(rootBounds.height >= 259f, "Expected outer animate box to fill 260dp height; got $rootBounds")
        val panelBgPixels = onNodeWithTag("ciacola-doc-animate-panel-root", useUnmergedTree = true)
            .captureToImage().toPixelMap()
        assertEquals(1f, panelBgPixels[0, 0].alpha, "Expected opaque panel background on Animate inspector")

        // Scroll to the very bottom: top fade appears, bottom fade hides, and 32dp bottom clearance is observable.
        onNodeWithTag("ciacola-doc-timeline-inspector", useUnmergedTree = true).performMouseInput {
            repeat(25) { scroll(10f) }
        }
        waitForIdle()
        onNodeWithTag("ciacola-doc-animate-scroll-fade", useUnmergedTree = true).assertIsDisplayed()
        assertEquals(
            0,
            onAllNodesWithTag("ciacola-doc-animate-bottom-fade", useUnmergedTree = true).fetchSemanticsNodes().size,
            "Bottom scroll fade should hide once scrolled to bottom",
        )
        val toggleBounds = onNodeWithTag("ciacola-fragment-toggle-subrows", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        assertTrue(
            rootBounds.bottom - toggleBounds.bottom >= 28f,
            "Expected genuine 32dp bottom clearance below last item at max scroll; root=$rootBounds toggle=$toggleBounds",
        )
        assertTrue(
            rootBounds.right - toggleBounds.right >= 12f,
            "Expected 14dp scrollbar gutter on right edge; root=$rootBounds toggle=$toggleBounds",
        )
    }
}
