package dev.sebastiano.ciacola.document.render

import androidx.compose.ui.geometry.Size
import dev.sebastiano.ciacola.document.BeatSpec
import dev.sebastiano.ciacola.document.Build
import dev.sebastiano.ciacola.document.Curve
import dev.sebastiano.ciacola.document.DeckClock
import dev.sebastiano.ciacola.document.DeckDocument
import dev.sebastiano.ciacola.document.DeckThemeSpec
import dev.sebastiano.ciacola.document.DocSegment
import dev.sebastiano.ciacola.document.FrameSpec
import dev.sebastiano.ciacola.document.NodeSpec
import dev.sebastiano.ciacola.document.RichTextValue
import dev.sebastiano.ciacola.document.SegmentPlanner
import dev.sebastiano.ciacola.document.SlideSpec
import dev.sebastiano.ciacola.document.TextPayload
import dev.sebastiano.ciacola.document.TransitionSpec
import dev.sebastiano.ciacola.document.TransitionType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** `change` builds that set `text.axes` interpolate the variable font coordinates. */
class DocumentAxisChangeTest {

    @Test
    fun `axes set on both ends interpolate and unpaired tags switch at the midpoint`() {
        val from = mapOf("wght" to 300.0, "wdth" to 100.0)
        val to = mapOf("wght" to 900.0, "opsz" to 48.0)
        assertEquals(mapOf("wght" to 450.0, "wdth" to 100.0), interpolateAxes(from, to, 0.25))
        assertEquals(mapOf("wght" to 750.0, "opsz" to 48.0), interpolateAxes(from, to, 0.75))
        // Progress snaps to 1/128 and coordinates round, so a change derives a bounded set of fonts.
        assertEquals(mapOf("wght" to 300.34), interpolateAxes(mapOf("wght" to 300.0), mapOf("wght" to 301.0), 1.0 / 3.0))
        assertEquals(interpolateAxes(from, to, 0.5), interpolateAxes(from, to, 0.502))
        assertEquals(to, interpolateAxes(to, to, 0.4))
    }

    @Test
    fun `a change build animates text axes deterministically along its curve`() {
        val doc = DeckDocument(
            id = "axis-change",
            stage = compactStage(400, 240),
            themes = mapOf("t" to DeckThemeSpec()),
            defaultTheme = "t",
            slides = listOf(
                SlideSpec(
                    id = "s0",
                    beats = listOf(BeatSpec(), BeatSpec()),
                    nodes = listOf(
                        NodeSpec(
                            id = "word",
                            frame = FrameSpec(x = 20.0, y = 20.0, w = 300.0, h = 80.0),
                            text = TextPayload(
                                value = RichTextValue.Plain("Variable"),
                                size = 48.0,
                                axes = mapOf("wght" to 200.0, "wdth" to 100.0),
                            ),
                            builds = listOf(
                                Build.Change(
                                    beat = 1,
                                    durationMs = 400,
                                    curve = Curve.Linear,
                                    set = buildJsonObject {
                                        putJsonObject("text") {
                                            putJsonObject("axes") { put("wght", 800.0) }
                                        }
                                    },
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val slide = doc.slides[0]
        val plan = SegmentPlanner.planSegment(doc, "s0", beat = 1)
        val colorResolver = DocumentColorResolver(themeSpec = doc.themes["t"], baseTheme = resolveDeckTheme(doc, "t"))

        fun frameAt(segment: DocSegment, t: Float) = DocumentMotionEvaluator.evaluateNode(
            rawNode = slide.nodes[0],
            slide = slide,
            clock = DeckClock(segment, t = t, effMs = 400f),
            segmentPlan = plan,
            stageSize = Size(400f, 240f),
            densityValue = 1f,
            colorResolver = colorResolver,
            reducedMotion = false,
        )

        fun axesAt(segment: DocSegment, t: Float): Map<String, Double> = frameAt(segment, t).resolvedNode.text!!.axes

        val beat = DocSegment.Beat("s0", 0)
        assertEquals(mapOf("wght" to 200.0, "wdth" to 100.0), axesAt(DocSegment.Rest("s0", 0), 0f))
        assertEquals(mapOf("wght" to 350.0, "wdth" to 100.0), axesAt(beat, 100f))
        assertEquals(mapOf("wght" to 500.0, "wdth" to 100.0), axesAt(beat, 200f))
        assertEquals(mapOf("wght" to 800.0, "wdth" to 100.0), axesAt(DocSegment.Rest("s0", 1), 0f))
        // Random access: scrubbing back to the same time gives the same coordinates.
        assertEquals(axesAt(beat, 100f), axesAt(beat, 100f))
        assertEquals(axesAt(beat, 300f), axesAt(beat, 300f))
        // The same glyphs re-lay out with the interpolated axes instead of morphing between endpoints.
        val midState = frameAt(beat, 200f).textState
        assertIs<DocumentTextRenderState.Rest>(midState)
        assertEquals(mapOf("wght" to 500.0, "wdth" to 100.0), midState.payload.axes)
    }

    @Test
    fun `a magic move between axes-only differences renders at rest with interpolated axes`() {
        fun node(wght: Double) = NodeSpec(
            id = "word",
            frame = FrameSpec(x = 20.0, y = 20.0, w = 300.0, h = 80.0),
            text = TextPayload(value = RichTextValue.Plain("Variable"), size = 48.0, axes = mapOf("wght" to wght)),
        )
        val state = DocumentMotionEvaluator.resolveTravellingTextRenderState(
            fromNode = node(200.0),
            toNode = node(800.0),
            nodeId = "word",
            fromEndpointMinWidthPx = null,
            toEndpointMinWidthPx = null,
            fromEndpointMaxWidthPx = null,
            toEndpointMaxWidthPx = null,
            fromEndpointSurfacePadPx = null,
            toEndpointSurfacePadPx = null,
            fallbackFromAvailableWidth = 400.0,
            fallbackToAvailableWidth = 400.0,
            moveTrack = null,
            clock = DeckClock(DocSegment.Beat("s0", 0), t = 200f, effMs = 400f),
            transitionSpec = TransitionSpec(type = TransitionType.MagicMove, curve = Curve.Linear),
        )
        assertIs<DocumentTextRenderState.Rest>(state)
        assertEquals(mapOf("wght" to 500.0), state.payload.axes)
    }
}
