package dev.sebastiano.ciacola.timeline

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.bnorm.storyboard.easel.LocalOverviewOpen
import dev.bnorm.storyboard.easel.LocalPresentingFullscreen
import dev.sebastiano.ciacola.document.Curve
import dev.sebastiano.ciacola.document.Timing
import dev.sebastiano.ciacola.ui.CiacolaJewelTheme
import org.jetbrains.jewel.ui.component.Text
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class TimelineUiTest {

    private val transportTags = listOf(
        "ciacola-timeline-dock-header-leading",
        "ciacola-timeline-prev-beat",
        "ciacola-timeline-play",
        "ciacola-timeline-next-beat",
        "ciacola-doc-timeline-playhead-readout",
        "ciacola-doc-timeline-search",
        "ciacola-doc-timeline-search-toggle",
        "ciacola-timeline-filter-current",
        "ciacola-timeline-filter-all",
        "ciacola-doc-timeline-zoom-out",
        "ciacola-timeline-zoom-slider",
        "ciacola-doc-timeline-zoom-in",
        "ciacola-doc-timeline-fit-all",
        "ciacola-doc-timeline-fit-slide",
        "ciacola-doc-timeline-zoom-menu",
        "ciacola-timeline-expand-toggle",
    )

    private fun ComposeUiTest.boundsOf(tag: String): List<Rect> =
        onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().map { it.boundsInRoot }

    private fun ComposeUiTest.assertTransportFits(width: Dp) {
        val bar = boundsOf("ciacola-doc-timeline-toolbar").single()
        val controls = transportTags.flatMap { tag -> boundsOf(tag).map { tag to it } }
        assertTrue(controls.size >= 8, "Expected the priority-1/2 controls to be present at $width, got ${controls.map { it.first }}")
        for ((tag, r) in controls) {
            assertTrue(r.width > 0f && r.left >= bar.left - 0.5f && r.right <= bar.right + 0.5f, "$tag clipped at $width: $r vs bar $bar")
        }
        // Readout text sits inside its bordered box; compare only sibling controls.
        val siblings = controls.filter { it.first != "ciacola-doc-timeline-playhead-readout" }
        for (i in siblings.indices) for (j in i + 1 until siblings.size) {
            val (ta, a) = siblings[i]
            val (tb, b) = siblings[j]
            val overlap = minOf(a.right, b.right) - maxOf(a.left, b.left)
            assertTrue(overlap <= 0.5f, "$ta overlaps $tb at $width ($a vs $b)")
        }
        for (tag in listOf("ciacola-timeline-prev-beat", "ciacola-timeline-play", "ciacola-timeline-next-beat", "ciacola-doc-timeline-playhead-readout")) {
            assertTrue(boundsOf(tag).isNotEmpty(), "Priority-1 control $tag must never hide at $width")
        }
        assertTrue(boundsOf("ciacola-doc-timeline-undo").isEmpty(), "Undo/Redo live in the App Bar, not the transport bar")
    }

    @Test
    fun transportBarNeverClipsAt1100720And480Widths() = runDesktopComposeUiTest(width = 1_400, height = 400) {
        val controller = TimelineFixtures.controller()
        var width by mutableStateOf(1_100.dp)
        setContent {
            CiacolaJewelTheme(isDark = true) {
                Box(Modifier.width(width).height(64.dp)) {
                    DocumentTimelineCollapsedBar(
                        controller = controller,
                        headerLeadingContent = { Text("Timeline  ·  Issues", modifier = Modifier.width(132.dp)) },
                        onToggleExpanded = {},
                    )
                }
            }
        }
        for (w in listOf(1_100.dp, 880.dp, 720.dp, 600.dp, 480.dp)) {
            width = w
            waitForIdle()
            assertTransportFits(w)
        }
        width = 1_100.dp
        waitForIdle()
        val present = transportTags.filter { boundsOf(it).isNotEmpty() }
        val bar = boundsOf("ciacola-doc-timeline-toolbar").single()
        assertTrue(boundsOf("ciacola-doc-timeline-search").isNotEmpty(), "Inline filter at 1100dp; bar=$bar present=$present lead=${boundsOf("ciacola-timeline-dock-header-leading")}")
        onNodeWithTag("ciacola-timeline-zoom-slider", useUnmergedTree = true).assertIsDisplayed()
        width = 720.dp
        waitForIdle()
        assertTrue(boundsOf("ciacola-doc-timeline-search").isEmpty(), "Filter collapses to an icon at 720dp")
        onNodeWithTag("ciacola-doc-timeline-search-toggle", useUnmergedTree = true).performClick()
        waitForIdle()
        onNodeWithTag("ciacola-doc-timeline-search", useUnmergedTree = true).assertIsDisplayed()
        assertTransportFits(720.dp)
    }

    @Test
    fun windowDefaultsToCollapsedBarAndExpandsTo66PercentSplit() = runDesktopComposeUiTest(width = 1_100, height = 720) {
        val controller = TimelineFixtures.controller()
        val window = DocumentTimelineWindow(controller = controller, initialDocked = true)
        assertFalse(window.timelineExpanded, "Timeline must default to collapsed")
        assertEquals(0.66f, window.splitFraction)
        val splits = mutableListOf<Float>()
        window.onSplitFractionChanged = { splits += it }

        setContent {
            Box(Modifier.size(1_100.dp, 720.dp)) {
                window.decorator.decorate { Box(Modifier.fillMaxSize().testTag("stage-content")) }
            }
        }
        waitForIdle()
        val bar = boundsOf("ciacola-timeline-collapsed-bar").single()
        val density = density.density
        val barDp = bar.height / density
        assertTrue(barDp in 54f..64f, "Collapsed bar must be ~58-64dp, was ${barDp}dp")
        assertTrue(boundsOf("ciacola-doc-timeline-panel").isEmpty())
        onNodeWithTag("ciacola-timeline-header-band", useUnmergedTree = true).assertIsDisplayed()
        val stageCollapsed = boundsOf("stage-content").single()
        assertTrue(stageCollapsed.height / density > 640f, "Stage gets the room when collapsed: ${stageCollapsed.height / density}dp")

        onNodeWithTag("ciacola-timeline-expand-toggle", useUnmergedTree = true).performClick()
        waitForIdle()
        assertTrue(window.timelineExpanded)
        onNodeWithTag("ciacola-doc-timeline-panel", useUnmergedTree = true).assertIsDisplayed()
        val stageExpanded = boundsOf("stage-content").single()
        val share = stageExpanded.height / (720f * density)
        assertTrue(share in 0.62f..0.68f, "Expanded stage share should be ~66%, was $share")

        window.splitFraction = 0.5f
        waitForIdle()
        onNodeWithTag("ciacola-studio-horizontal-splitter", useUnmergedTree = true).performMouseInput { doubleClick() }
        waitForIdle()
        assertEquals(0.66f, window.splitFraction)
        assertEquals(listOf(0.66f), splits)

        window.toggleTimelineExpanded()
        waitForIdle()
        assertTrue(boundsOf("ciacola-timeline-collapsed-bar").isNotEmpty())
    }

    @Test
    fun overviewFullscreenAndChromelessModesRenderContentFullWindow() = runDesktopComposeUiTest(width = 1_100, height = 720) {
        val controller = TimelineFixtures.controller()
        val window = DocumentTimelineWindow(
            controller = controller,
            initialDocked = true,
            initialExpanded = true,
            topBarContent = { Box(Modifier.fillMaxWidth().height(36.dp).testTag("app-bar")) },
            trailingPane = { Box(Modifier.width(320.dp).fillMaxHeight().testTag("inspector")) },
        )
        var overview by mutableStateOf(false)
        var fullscreen by mutableStateOf(false)
        setContent {
            CompositionLocalProvider(LocalOverviewOpen provides overview, LocalPresentingFullscreen provides fullscreen) {
                Box(Modifier.size(1_100.dp, 720.dp)) {
                    window.decorator.decorate { Box(Modifier.fillMaxSize().testTag("stage-content")) }
                }
            }
        }
        waitForIdle()
        val root = onRoot().fetchSemanticsNode().boundsInRoot
        onNodeWithTag("app-bar", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithTag("inspector", useUnmergedTree = true).assertIsDisplayed()
        assertTrue(boundsOf("stage-content").single().width < root.width)

        fun assertFullWindow(mode: String) {
            waitForIdle()
            val stage = boundsOf("stage-content").single()
            assertEquals(root.width, stage.width, 0.5f, "$mode: stage must span the full window width")
            assertEquals(root.height, stage.height, 0.5f, "$mode: stage must span the full window height")
            assertTrue(boundsOf("app-bar").isEmpty(), "$mode: no App Bar")
            assertTrue(boundsOf("inspector").isEmpty(), "$mode: no inspector")
            assertTrue(boundsOf("ciacola-doc-timeline-panel").isEmpty() && boundsOf("ciacola-timeline-collapsed-bar").isEmpty(), "$mode: no timeline")
        }
        overview = true
        assertFullWindow("Overview")
        overview = false
        fullscreen = true
        assertFullWindow("Fullscreen present")
        fullscreen = false
        window.showStudioChrome = false
        assertFullWindow("Chrome-less")
        window.showStudioChrome = true
        waitForIdle()
        onNodeWithTag("app-bar", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun alternateBottomContentAndOverlaySlotsKeepStageAndInspectorVisible() = runComposeUiTest {
        val controller = TimelineFixtures.controller()
        val window = DocumentTimelineWindow(
            controller = controller,
            initialDocked = true,
            trailingPane = { Box(Modifier.width(300.dp).fillMaxHeight().testTag("inspector")) },
            bottomDockHeaderLeading = { Text("Timeline | Issues", modifier = Modifier.testTag("dock-tabs")) },
        )
        setContent {
            Box(Modifier.size(1_100.dp, 720.dp)) {
                window.decorator.decorate { Box(Modifier.fillMaxSize().testTag("stage-content")) }
            }
        }
        waitForIdle()
        onNodeWithTag("dock-tabs", useUnmergedTree = true).assertIsDisplayed()
        window.alternateBottomContent = { Box(Modifier.fillMaxSize().testTag("issues-list")) }
        waitForIdle()
        onNodeWithTag("issues-list", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithTag("dock-tabs", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithTag("stage-content", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithTag("inspector", useUnmergedTree = true).assertIsDisplayed()
        assertTrue(boundsOf("ciacola-timeline-collapsed-bar").isEmpty())

        window.fullWindowOverlayContent = { Box(Modifier.fillMaxSize().testTag("theme-sheet")) }
        waitForIdle()
        val root = onRoot().fetchSemanticsNode().boundsInRoot
        assertEquals(root.width, boundsOf("theme-sheet").single().width, 0.5f)
    }

    @Test
    fun headerBandRendersSegmentsTicksAndAddBeatButton() = runComposeUiTest {
        val controller = TimelineFixtures.controller()
        controller.updatePresentedLocation("s002", sceneIndex = 1, beatIndex = 0)
        setContent {
            CiacolaJewelTheme(isDark = false) {
                Box(Modifier.size(1_200.dp, 520.dp)) {
                    DocumentTimelineStudioPanel(controller = controller, showInlineInspector = false)
                }
            }
        }
        waitForIdle()
        assertTrue(boundsOf("ciacola-doc-timeline-headers-strip").isEmpty(), "Old beat pill strip is gone")
        assertTrue(boundsOf("ciacola-doc-timeline-minimap").isEmpty(), "Old minimap stripe is gone")
        val band = boundsOf("ciacola-timeline-header-band").single()
        assertEquals(22f, band.height / density.density, 0.5f)
        val ruler = boundsOf("ciacola-doc-timeline-ruler").single()
        assertTrue(band.bottom <= ruler.top + 0.5f, "Header band sits directly above the ruler")

        onNodeWithTag("ciacola-header-beat-s002-1", useUnmergedTree = true).performClick()
        waitForIdle()
        assertEquals(DocumentTimelineSelection.BeatHeader("s002", 1), controller.selection)

        val beatsBefore = controller.store.document.slides[1].beats.size
        onNodeWithTag("ciacola-doc-timeline-insert-beat", useUnmergedTree = true).performClick()
        waitForIdle()
        assertEquals(beatsBefore + 1, controller.store.document.slides[1].beats.size)
        assertEquals(2, controller.store.document.slides[0].beats.size, "Only the active slide gains a beat")
    }

    @Test
    fun selectingANodeOnStageScrollsItsRowAndTimeBarIntoView() = runComposeUiTest {
        val controller = TimelineFixtures.controller(TimelineFixtures.largeDeck())
        controller.setTrackFilter(TimelineTrackFilterMode.AllSlides)
        setContent {
            CiacolaJewelTheme(isDark = true) {
                Box(Modifier.size(1_000.dp, 360.dp)) {
                    DocumentTimelineStudioPanel(controller = controller, showInlineInspector = false)
                }
            }
        }
        waitForIdle()
        controller.viewport.setZoomDpPerMs(0.5f, anchorX = 0f, surface = TimelineSurface.Docked)
        controller.viewport.scrollHorizontallyTo(0f, TimelineSurface.Docked)
        waitForIdle()

        controller.selectTracksForNode("big-27", "big-27:b")
        waitForIdle()
        val trackId = (controller.selection as DocumentTimelineSelection.Track).trackId
        onNodeWithTag("ciacola-doc-track-row-$trackId", useUnmergedTree = true).assertIsDisplayed()
        val row = controller.snapshot.snapshot.tracks.first { it.trackId == trackId }
        val (start, end) = row.timeRange()!!
        val x0 = controller.viewport.xForTime(start.toFloat(), TimelineSurface.Docked)
        val x1 = controller.viewport.xForTime(end.toFloat(), TimelineSurface.Docked)
        val widthPx = controller.viewport.viewportWidthPx(TimelineSurface.Docked)
        assertTrue(x0 >= 0f && x1 <= widthPx, "Selected bar must be horizontally visible ($x0..$x1 within 0..$widthPx)")
    }

    @Test
    fun animateTabUsesScrubFieldsPresetNamesAndOverflowDelete() = runComposeUiTest {
        val controller = TimelineFixtures.controller()
        controller.updatePresentedLocation("s001", sceneIndex = 0, beatIndex = 1)
        controller.selectTracksForNode("s001", "s1:prose")
        setContent {
            CiacolaJewelTheme(isDark = true) {
                Box(Modifier.size(340.dp, 900.dp)) {
                    DocumentTimelineMotionInspector(controller = controller)
                }
            }
        }
        waitForIdle()
        onNodeWithTag("ciacola-scrub-field-track-duration", useUnmergedTree = true).assertIsDisplayed()
        assertTrue(boundsOf("ciacola-doc-track-delete-build").isEmpty(), "Delete build is not next to the curve fields")

        // Typed entry commits on Enter; arrow keys step.
        onNodeWithTag("ciacola-scrub-input-track-duration", useUnmergedTree = true).performTextReplacement("640")
        onNodeWithTag("ciacola-scrub-input-track-duration", useUnmergedTree = true).performKeyInput { pressKey(Key.Enter) }
        waitForIdle()
        fun duration() = (controller.selectedTrackRef() as DocumentTimelineTrackRef.SlideBuildTrack).trackPlan.durationMs
        assertEquals(640, duration())
        onNodeWithTag("ciacola-scrub-input-track-duration", useUnmergedTree = true).performKeyInput { pressKey(Key.DirectionUp) }
        waitForIdle()
        assertEquals(650, duration())

        // Destructive action lives in the section overflow menu.
        onNodeWithTag("ciacola-animate-overflow-track", useUnmergedTree = true).performClick()
        waitForIdle()
        onNodeWithTag("ciacola-doc-track-delete-build", useUnmergedTree = true).performClick()
        waitForIdle()
        val prose = controller.store.document.slides[0].nodes.first { it.id == "s1:prose" }
        assertTrue(prose.builds.isEmpty(), "Delete build from the overflow menu removes the build")
    }

    @Test
    fun phaseCurveDropdownsShowPresetNamesNotBezierTuples() = runComposeUiTest {
        setContent {
            CiacolaJewelTheme(isDark = true) {
                Box(Modifier.size(340.dp, 400.dp)) {
                    InteractivePhaseBarsEditor(
                        resolvedPhases = ResolvedPhaseTimings(
                            exit = Timing(delayMs = 0, durationMs = 200, curve = Curve.Bezier(0.2, 0.0, 0.0, 1.0)),
                            move = Timing(delayMs = 0, durationMs = 400, curve = Curve.EaseInOut),
                            enter = Timing(delayMs = 100, durationMs = 300, curve = Curve.Bezier(0.13, 0.8, 0.3, 0.9)),
                        ),
                        tagPrefix = "test",
                        onBeginGesture = {},
                        onCommitGesture = {},
                        onCancelGesture = {},
                        onPhaseUpdated = { _, _, _, _, _ -> },
                    )
                }
            }
        }
        waitForIdle()
        onNodeWithText("Standard", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithText("Ease in-out", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithText("Custom", useUnmergedTree = true).assertIsDisplayed()
        assertTrue(
            onAllNodesWithTag("ciacola-test-phase-exit-curve", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty(),
        )
        onNodeWithText("Bezier(", substring = true, useUnmergedTree = true).assertDoesNotExist()
    }
}
