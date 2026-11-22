package dev.sebastiano.ciacola.timeline

import dev.sebastiano.ciacola.document.BeatSpec
import dev.sebastiano.ciacola.document.Curve
import dev.sebastiano.ciacola.document.MorphBy
import dev.sebastiano.ciacola.document.Stagger
import dev.sebastiano.ciacola.document.TransitionSpec
import dev.sebastiano.ciacola.document.TransitionType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TimelineModelTest {

    @Test
    fun headerBandOrdersBeatsBeforeOutgoingZeroLengthTransitionOnRealDeck() {
        val controller = TimelineFixtures.controller()
        val snapshot = controller.snapshot
        val trans = snapshot.transitionHeader("s001", "s002")!!
        val s2Base = snapshot.beatHeader("s002", 0)!!
        assertEquals(0, trans.segmentMs, "Fixture transition must be zero-length")
        assertEquals(trans.startMs, s2Base.startMs, "Zero-length transition must tie with the next slide's base beat")

        val keys = buildHeaderBandEntries(snapshot).map { it.key }
        val transIdx = keys.indexOf("trans:s001:s002")
        assertTrue(transIdx > keys.indexOf("beat:s001:1"), "Transition must follow every beat of its slide: $keys")
        assertTrue(transIdx < keys.indexOf("slide:s002"), "Transition must precede the next slide segment: $keys")
        assertTrue(transIdx < keys.indexOf("beat:s002:0"), "s002 B0 must never appear before s001→s002: $keys")
    }

    @Test
    fun headerBandOrderingIsByConstructionForSyntheticTies() {
        fun beat(slide: String, scene: Int, idx: Int, start: Int) =
            DocumentBeatHeaderInfo(slide, scene, idx, start, 0, BeatSpec(), emptyList())
        val trans01 = DocumentTransitionHeaderInfo("a", "b", 0, 1, 1_000, 0, TransitionSpec(type = TransitionType.None), emptyList())
        val trans12 = DocumentTransitionHeaderInfo("b", "c", 1, 2, 2_000, 0, TransitionSpec(type = TransitionType.None), emptyList())
        // Feed headers in the worst order (sorted by start only, transitions after their target beats).
        val snap = DocumentTimelineSnapshot.Empty.copy(
            snapshot = DeckTimelineSnapshot(
                totalDurationMs = 3_000,
                slides = listOf(
                    SlideSectionBar(0, "A", 0, 1_000, 0, 0, 2),
                    SlideSectionBar(1, "B", 1_000, 2_000, 0, 0, 1),
                    SlideSectionBar(2, "C", 2_000, 3_000, 0, 0, 1),
                ),
                eventBeats = emptyList(),
                tracks = emptyList(),
            ),
            beatHeaders = listOf(beat("c", 2, 0, 2_000), beat("b", 1, 0, 1_000), beat("a", 0, 1, 500), beat("a", 0, 0, 0)),
            transitionHeaders = listOf(trans12, trans01),
        )
        val entries = buildHeaderBandEntries(snap)
        assertEquals(
            listOf(
                "slide:a", "beat:a:0", "beat:a:1", "trans:a:b",
                "slide:b", "beat:b:0", "trans:b:c",
                "slide:c", "beat:c:0",
            ),
            entries.map { it.key },
        )
        assertEquals(entries, entries.sortedWith(HEADER_BAND_ORDER))
        val labels = entries.filterIsInstance<HeaderBandEntry.Slide>().map { it.label }
        assertEquals(listOf("Slide 1", "Slide 2", "Slide 3"), labels, "Untitled slides fall back to plain names")
    }

    @Test
    fun slideScopeShowsOnlyLoopsReferencedByTheActiveSlide() {
        val controller = TimelineFixtures.controller()
        val snapshot = controller.snapshot
        val loops = snapshot.trackRefsByTrackId.values.filterIsInstance<DocumentTimelineTrackRef.LoopTrackBar>()
        val pulse = loops.single { it.specimenRef == "demo.pulse" }
        val wave = loops.single { it.specimenRef == "demo.wave" }
        assertEquals(setOf(0, 2), pulse.sceneIndices, "Shared pulse loop belongs to slides 1 and 3")
        assertEquals(setOf(1), wave.sceneIndices)

        fun loopIdsOn(scene: Int) = filterTracksForScope(snapshot, TimelineTrackFilterMode.CurrentSlide, scene, "")
            .mapNotNull { (snapshot.trackRefsByTrackId[it.trackId] as? DocumentTimelineTrackRef.LoopTrackBar)?.loopId }
            .toSet()

        assertEquals(setOf(pulse.loopId), loopIdsOn(0))
        assertEquals(setOf(wave.loopId), loopIdsOn(1), "Slide 2 must not leak the pulse loop from slides 1 and 3")
        assertEquals(setOf(pulse.loopId), loopIdsOn(2))

        // Every non-loop row in scope belongs to the active slide (zero leaks).
        for (scene in 0..2) {
            val rows = filterTracksForScope(snapshot, TimelineTrackFilterMode.CurrentSlide, scene, "")
            assertTrue(rows.all { it.sceneIndex == null || it.sceneIndex == scene })
        }
        val all = filterTracksForScope(snapshot, TimelineTrackFilterMode.AllSlides, 1, "")
        assertEquals(snapshot.snapshot.tracks.size, all.size)
        // Search matches plain labels and raw ids alike.
        assertTrue(filterTracksForScope(snapshot, TimelineTrackFilterMode.AllSlides, 0, "scene begins").isNotEmpty())
        assertTrue(filterTracksForScope(snapshot, TimelineTrackFilterMode.AllSlides, 0, "s1:prose").isNotEmpty())
    }

    @Test
    fun perScopeZoomMemoryRestoresEachScopesZoom() {
        val controller = TimelineFixtures.controller()
        val viewport = controller.viewport
        viewport.updateSurfaceMetrics(TimelineSurface.Docked, widthPx = 900f, surfaceDensity = 1f)
        controller.setTrackFilter(TimelineTrackFilterMode.AllSlides)
        viewport.setZoomDpPerMs(0.4f, anchorX = 0f, surface = TimelineSurface.Docked)
        viewport.scrollHorizontallyTo(120f, TimelineSurface.Docked)
        val allZoom = viewport.dpPerMs
        val allScroll = viewport.scrollX(TimelineSurface.Docked)

        controller.setTrackFilter(TimelineTrackFilterMode.CurrentSlide)
        viewport.setZoomDpPerMs(1.2f, anchorX = 0f, surface = TimelineSurface.Docked)
        val slideZoom = viewport.dpPerMs

        controller.setTrackFilter(TimelineTrackFilterMode.AllSlides)
        assertEquals(allZoom, viewport.dpPerMs, 1e-4f, "All scope must restore its zoom")
        assertEquals(allScroll, viewport.scrollX(TimelineSurface.Docked), 0.5f)
        assertTrue(controller.consumeScopeZoomRestored())
        assertFalse(controller.consumeScopeZoomRestored(), "Restored flag is consumed once")

        controller.setTrackFilter(TimelineTrackFilterMode.CurrentSlide)
        assertEquals(slideZoom, viewport.dpPerMs, 1e-4f, "Slide scope must restore its zoom on the same slide")

        // Moving to another slide makes the Slide-scope memento stale: auto-framing takes over.
        controller.setTrackFilter(TimelineTrackFilterMode.AllSlides)
        controller.updatePresentedLocation("s003", sceneIndex = 2, beatIndex = 0)
        controller.setTrackFilter(TimelineTrackFilterMode.CurrentSlide)
        assertFalse(controller.consumeScopeZoomRestored())
    }

    @Test
    fun unifiedRulerFormatterCoversMsSecondsAndMinutes() {
        assertEquals("0ms", formatTimelineRulerLabel(0, 250))
        assertEquals("500ms", formatTimelineRulerLabel(500, 500))
        assertEquals("1500ms", formatTimelineRulerLabel(1_500, 500))
        assertEquals("2.5s", formatTimelineRulerLabel(2_500, 500))
        assertEquals("12.0s", formatTimelineRulerLabel(12_000, 500))
        assertEquals("2.05s", formatTimelineRulerLabel(2_050, 50))
        assertEquals("1s", formatTimelineRulerLabel(1_000, 1_000))
        assertEquals("10s", formatTimelineRulerLabel(10_000, 10_000))
        assertEquals("1:00", formatTimelineRulerLabel(60_000, 15_000))
        assertEquals("1:30", formatTimelineRulerLabel(90_000, 30_000))
        assertEquals("1:00.5", formatTimelineRulerLabel(60_500, 500))
        // Master ruler and loop sub-comp both go through the single formatter.
        assertEquals(formatTimelineRulerLabel(90_000, 30_000), formatMasterTimelineTick(90_000, 30_000))
        // Consecutive ticks are always distinct at every master step.
        for (step in listOf(50, 100, 250, 500, 1_000, 2_000, 5_000, 30_000, 60_000)) {
            val labels = (0..30).map { formatTimelineRulerLabel(it * step, step) }
            assertEquals(labels.size, labels.toSet().size, "Duplicate ruler labels at step $step: $labels")
        }
    }

    @Test
    fun trackLabelsArePlainWithBeatNumbersAndNoRawIds() {
        val controller = TimelineFixtures.controller()
        val labels = controller.snapshot.snapshot.tracks.map { it.label }
        assertTrue("Build 1 · A scene begins with a…" in labels, "Got $labels")
        assertTrue("Build 1 · An aside" in labels, "Got $labels")
        assertTrue(labels.any { it.startsWith("→ Slide 3 · Dissolve") }, "Whole-slide transition reads plainly: $labels")
        assertTrue(labels.any { it == "Loop · Ambient pulse" })
        for (label in labels) {
            assertFalse(label.contains("@transition"), "Raw transition id leaked: $label")
            assertFalse(label.contains("s001") || label.contains("s1:"), "Raw slide/node id leaked: $label")
        }
        assertEquals(listOf("Intro", "Middle", "Slide 3"), controller.snapshot.snapshot.slides.map { it.label })
    }

    @Test
    fun curvePresetDisplayNamesNeverShowSerializedTuples() {
        assertEquals("Standard", TimelineDisplayNames.curveName(Curve.Bezier(0.2, 0.0, 0.0, 1.0)))
        assertEquals("Standard", TimelineDisplayNames.curveName(Curve.Preset("fastOutSlowIn")))
        assertEquals("Ease in-out", TimelineDisplayNames.curveName(Curve.EaseInOut))
        assertEquals("Ease out", TimelineDisplayNames.curveName(Curve.EaseOut))
        assertEquals("Ease in", TimelineDisplayNames.curveName(Curve.EaseIn))
        assertEquals("Linear", TimelineDisplayNames.curveName(Curve.Linear))
        assertEquals("Linear", TimelineDisplayNames.curveName(Curve.Bezier(0.0, 0.0, 1.0, 1.0)))
        assertEquals("Spring", TimelineDisplayNames.curveName(Curve.Spring(0.7, 380.0)))
        assertEquals("Custom", TimelineDisplayNames.curveName(Curve.Bezier(0.11, 0.7, 0.3, 0.93)))
        assertEquals("Keynote · Pop", TimelineDisplayNames.curveName(Curve.Preset("keynote.pop")))
        assertEquals("Custom", TimelineDisplayNames.curveName(Curve.Preset("not-a-preset")))
        assertEquals(curveShortLabel(Curve.Bezier(0.2, 0.0, 0.0, 1.0)), "Standard")
        for ((label, _) in TIMELINE_CURVE_CHOICES) assertFalse(label.contains("Bezier("))
        assertEquals(
            listOf("Standard", "Ease in-out", "Ease out", "Ease in", "Linear", "Spring"),
            TIMELINE_CURVE_CHOICES.take(6).map { it.first },
        )
    }

    @Test
    fun selectTracksForNodeHighlightsWithoutEchoAndTrackClicksNotifyNode() {
        val controller = TimelineFixtures.controller()
        val notified = mutableListOf<String?>()
        controller.onSelectedNodeChanged = { notified += it }
        controller.updatePresentedLocation("s001", sceneIndex = 0, beatIndex = 1)

        // Stage/Design -> Timeline: selects the node's primary track, never echoes back.
        controller.selectTracksForNode("s001", "s1:prose")
        val sel = assertIs<DocumentTimelineSelection.Track>(controller.selection)
        val ref = controller.snapshot.trackRefsByTrackId[sel.trackId] as DocumentTimelineTrackRef.SlideBuildTrack
        assertEquals("s1:prose", ref.nodeId)
        assertEquals("s1:prose", controller.highlightedNodeId)
        assertTrue(notified.isEmpty(), "selectTracksForNode must not fire onSelectedNodeChanged")
        val nonceAfterStage = controller.revealRequestNonce
        assertTrue(nonceAfterStage > 0, "Stage selection must request a reveal")

        // Specimen nodes reveal their ambient loop.
        controller.selectTracksForNode("s002", "wave")
        assertIs<DocumentTimelineSelection.Loop>(controller.selection)

        // Timeline -> Stage: clicking another node's track fires the hook with that node.
        val asideTrack = controller.snapshot.snapshot.tracks.first { row ->
            (controller.snapshot.trackRefsByTrackId[row.trackId] as? DocumentTimelineTrackRef.SlideBuildTrack)?.nodeId == "s1:aside"
        }
        controller.selectTrack(asideTrack.trackId)
        assertEquals(listOf<String?>("s1:aside"), notified)
        assertEquals("s1:aside", controller.highlightedNodeId)
        controller.selectTrack(asideTrack.trackId)
        assertEquals(1, notified.size, "Re-selecting the same track must not re-fire")

        // Deselecting on stage clears the track selection + highlight.
        controller.selectTracksForNode("s001", null)
        assertEquals(DocumentTimelineSelection.None, controller.selection)
        assertNull(controller.highlightedNodeId)

        // A node with no tracks keeps the highlight but leaves no stale track selected.
        controller.selectTrack(asideTrack.trackId)
        controller.selectTracksForNode("s001", "no-such-node")
        assertEquals(DocumentTimelineSelection.None, controller.selection)
    }

    @Test
    fun fragmentSummaryReadsAsCountAndCascade() {
        assertEquals("12 words · 45ms cascade", fragmentSummaryLabel(12, MorphBy.Word, Stagger(ms = 45)))
        assertEquals("1 character · together", fragmentSummaryLabel(1, MorphBy.Character, Stagger(ms = 0)))
        assertEquals("3 lines · 20ms cascade", fragmentSummaryLabel(3, MorphBy.Line, Stagger(ms = 20)))
    }

    @Test
    fun transportLayoutCollapsesByPriority() {
        val wide = transportBarLayoutFor(androidx.compose.ui.unit.Dp(1_200f))
        assertEquals(TransportZoomMode.Slider, wide.zoomMode)
        assertTrue(wide.inlineFilterField)
        val mid = transportBarLayoutFor(androidx.compose.ui.unit.Dp(800f))
        assertEquals(TransportZoomMode.Slider, mid.zoomMode)
        assertFalse(mid.inlineFilterField)
        val narrow = transportBarLayoutFor(androidx.compose.ui.unit.Dp(600f))
        assertEquals(TransportZoomMode.Buttons, narrow.zoomMode)
        val tiny = transportBarLayoutFor(androidx.compose.ui.unit.Dp(500f))
        assertEquals(TransportZoomMode.Menu, tiny.zoomMode)
        assertTrue(tiny.compactTimecode)
        assertTrue(transportBarLayoutFor(androidx.compose.ui.unit.Dp(400f)).scopeInMenu)
    }

    @Test
    fun containerNodeNamesUseConsistentPlainGroupRowColumnLabels() {
        val stackNode = dev.sebastiano.ciacola.document.NodeSpec(
            id = "foundation-shared-card",
            stack = dev.sebastiano.ciacola.document.ContainerPayload(),
        )
        val rowNode = dev.sebastiano.ciacola.document.NodeSpec(
            id = "hero-row",
            row = dev.sebastiano.ciacola.document.ContainerPayload(),
        )
        val colNode = dev.sebastiano.ciacola.document.NodeSpec(
            id = "hero-col",
            column = dev.sebastiano.ciacola.document.ContainerPayload(),
        )
        assertEquals("Group · Foundation shared card", TimelineDisplayNames.nodeName(stackNode, stackNode.id))
        assertEquals("Row · Hero row", TimelineDisplayNames.nodeName(rowNode, rowNode.id))
        assertEquals("Column · Hero col", TimelineDisplayNames.nodeName(colNode, colNode.id))
    }
}
