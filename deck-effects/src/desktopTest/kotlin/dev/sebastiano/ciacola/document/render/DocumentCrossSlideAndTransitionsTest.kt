package dev.sebastiano.ciacola.document.render

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.bnorm.storyboard.AdvanceDirection
import dev.bnorm.storyboard.SceneFormat
import dev.bnorm.storyboard.Storyboard
import dev.bnorm.storyboard.easel.Animatic
import dev.bnorm.storyboard.easel.Easel
import dev.bnorm.storyboard.easel.SceneMode
import dev.bnorm.storyboard.easel.ScenePreview
import dev.bnorm.storyboard.easel.rememberAnimatic
import dev.sebastiano.ciacola.authoring.SlideLayers
import dev.sebastiano.ciacola.document.BeatSpec
import dev.sebastiano.ciacola.document.Build
import dev.sebastiano.ciacola.document.CodePayload
import dev.sebastiano.ciacola.document.ConnectorPayload
import dev.sebastiano.ciacola.document.ContainerPayload
import dev.sebastiano.ciacola.document.CrossSlideGeometry
import dev.sebastiano.ciacola.document.Curve
import dev.sebastiano.ciacola.document.DeckClock
import dev.sebastiano.ciacola.document.DeckDocument
import dev.sebastiano.ciacola.document.DocSegment
import dev.sebastiano.ciacola.document.Fill
import dev.sebastiano.ciacola.document.FrameSpec
import dev.sebastiano.ciacola.document.GradientSpec
import dev.sebastiano.ciacola.document.GradientStop
import dev.sebastiano.ciacola.document.GradientType
import dev.sebastiano.ciacola.document.ImagePayload
import dev.sebastiano.ciacola.document.LoupePayload
import dev.sebastiano.ciacola.document.MorphBy
import dev.sebastiano.ciacola.document.NodeSpec
import dev.sebastiano.ciacola.document.PaddingSpec
import dev.sebastiano.ciacola.document.Phases
import dev.sebastiano.ciacola.document.RenderSession
import dev.sebastiano.ciacola.document.RichTextValue
import dev.sebastiano.ciacola.document.ScaleValue
import dev.sebastiano.ciacola.document.SegmentPlanner
import dev.sebastiano.ciacola.document.ShapeGeometry
import dev.sebastiano.ciacola.document.ShapePayload
import dev.sebastiano.ciacola.document.SlideSpec
import dev.sebastiano.ciacola.document.SpecimenPayload
import dev.sebastiano.ciacola.document.StageSize
import dev.sebastiano.ciacola.document.StrokeSpec
import dev.sebastiano.ciacola.document.SurfaceSpec
import dev.sebastiano.ciacola.document.TextPayload
import dev.sebastiano.ciacola.document.TextMorphSpec
import dev.sebastiano.ciacola.document.Timing
import dev.sebastiano.ciacola.document.TransitionSpec
import dev.sebastiano.ciacola.document.TransitionType
import dev.sebastiano.ciacola.document.Vec2
import dev.sebastiano.ciacola.document.deckSpecimens
import dev.sebastiano.ciacola.theme.SlidePalette
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Acceptance Gates 12 & 23:
 * - Every whole-slide transition type (`none`, `dissolve`, `push`, `slide`, `reveal`, `wipe`,
 *   `iris` in/out, `fadeThroughColor` incl. translucent color forced opaque at p = 0.5) on both
 *   background-only and opaque full-stage slides, forward == fresh backward == cold seek, plus
 *   reduced-motion fallback, concurrent `Easel` + `ScenePreview` isolation, and legacy `SlideLayers`
 *   backward compatibility.
 * - Cross-slide `magicMove`: exact once-per-frame drawing (no double-drawn semi-transparent node),
 *   nested rotated/scaled padded-stack + non-default-origin affine interpolation, sheared &
 *   flow-layout cross-fade fallbacks, warm == cold seek == fresh session == draw-only frame,
 *   unmatched overlapping stacking fwd == bwd, unsized wrapping text & surfaced text hand-offs,
 *   ancestor `clip: true` unclipped travel & clipped rest, movable `specimen` `A -> B -> C -> B -> A`
 *   with `mounts == 1` & `retained` identity across live travel and cold seek, travelling container
 *   holding a specimen, missing-ref / `todo.video` placeholders, SVG & PNG assets, connectors, and loupe.
 */
@OptIn(ExperimentalComposeUiApi::class)
class DocumentCrossSlideAndTransitionsTest {

    @Test
    fun everyWholeSlideTransitionMatchesCompositorTableAndPathIndependence() {
        data class TransitionCase(
            val label: String,
            val spec: TransitionSpec,
            val verifyMidPixel: (RenderedFrame) -> Unit,
        )

        // Stage 200x120; Slide A = Red (#FF0000), Slide B = Blue (#0000FF).
        val cases = listOf(
            TransitionCase(
                label = "dissolve",
                spec = TransitionSpec(type = TransitionType.Dissolve, durationMs = 200, curve = Curve.Linear),
                verifyMidPixel = { f ->
                    // At p = 0.5: Blue at alpha 0.5 over opaque Red -> ~(127, 0, 127)
                    assertColorNear(f.pixelColor(100, 60), 128, 0, 128, tolerance = 14, label = "dissolve mid")
                },
            ),
            TransitionCase(
                label = "push",
                spec = TransitionSpec(type = TransitionType.Push, direction = "rightToLeft", durationMs = 200, curve = Curve.Linear),
                verifyMidPixel = { f ->
                    // A shifts left by -100 (occupies x = 0..100), B enters from right (occupies x = 100..200)
                    assertColorNear(f.pixelColor(40, 60), 255, 0, 0, label = "push mid left (A)")
                    assertColorNear(f.pixelColor(160, 60), 0, 0, 255, label = "push mid right (B)")
                },
            ),
            TransitionCase(
                label = "slide",
                spec = TransitionSpec(type = TransitionType.Slide, direction = "rightToLeft", durationMs = 200, curve = Curve.Linear),
                verifyMidPixel = { f ->
                    // A stays behind on left, B slides over right half
                    assertColorNear(f.pixelColor(40, 60), 255, 0, 0, label = "slide mid left (A)")
                    assertColorNear(f.pixelColor(160, 60), 0, 0, 255, label = "slide mid right (B)")
                },
            ),
            TransitionCase(
                label = "reveal",
                spec = TransitionSpec(type = TransitionType.Reveal, direction = "rightToLeft", durationMs = 200, curve = Curve.Linear),
                verifyMidPixel = { f ->
                    // AOverB stacking: A slides off left (still covers x = 0..100), uncovering stationary B on x = 100..200!
                    assertColorNear(f.pixelColor(40, 60), 255, 0, 0, label = "reveal mid left (A on top)")
                    assertColorNear(f.pixelColor(160, 60), 0, 0, 255, label = "reveal mid right (B uncovered)")
                },
            ),
            TransitionCase(
                label = "wipe",
                spec = TransitionSpec(type = TransitionType.Wipe, direction = "leftToRight", durationMs = 200, curve = Curve.Linear),
                verifyMidPixel = { f ->
                    // B clipped to left half (0..100) over A on right half (100..200)
                    assertColorNear(f.pixelColor(40, 60), 0, 0, 255, label = "wipe mid left (B)")
                    assertColorNear(f.pixelColor(160, 60), 255, 0, 0, label = "wipe mid right (A)")
                },
            ),
            TransitionCase(
                label = "iris-in",
                spec = TransitionSpec(
                    type = TransitionType.Iris,
                    direction = "in",
                    center = Vec2(0.5, 0.5),
                    durationMs = 200,
                    curve = Curve.Linear,
                ),
                verifyMidPixel = { f ->
                    // Center inside growing circle = B (Blue); far corner = A (Red)
                    assertColorNear(f.pixelColor(100, 60), 0, 0, 255, label = "iris-in center (B)")
                    assertColorNear(f.pixelColor(4, 4), 255, 0, 0, label = "iris-in corner (A)")
                },
            ),
            TransitionCase(
                label = "iris-out",
                spec = TransitionSpec(
                    type = TransitionType.Iris,
                    direction = "out",
                    center = Vec2(0.5, 0.5),
                    durationMs = 200,
                    curve = Curve.Linear,
                ),
                verifyMidPixel = { f ->
                    // AOverB stacking: shrinking A circle covers center (Red); far corner reveals B (Blue)!
                    assertColorNear(f.pixelColor(100, 60), 255, 0, 0, label = "iris-out center (A on top)")
                    assertColorNear(f.pixelColor(4, 4), 0, 0, 255, label = "iris-out corner (B underneath)")
                },
            ),
            TransitionCase(
                label = "fadeThroughColor-translucent-forced-opaque",
                spec = TransitionSpec(
                    type = TransitionType.FadeThroughColor,
                    color = "#8000FF00", // Translucent green input -> MUST become opaque #FF00FF00 at p = 0.5!
                    durationMs = 200,
                    curve = Curve.Linear,
                ),
                verifyMidPixel = { f ->
                    assertColorNear(f.pixelColor(100, 60), 0, 255, 0, tolerance = 8, label = "fadeThroughColor opaque green veil at p=0.5")
                },
            ),
        )

        val pendingEnterB = NodeSpec(
            id = "pending-enter-b",
            frame = FrameSpec(x = 150.0, y = 10.0, w = 40.0, h = 30.0),
            shape = ShapePayload(geometry = ShapeGeometry.Rect, fill = Fill.Solid("#FFFF00")),
            builds = listOf(
                Build.Enter(
                    beat = 0,
                    effect = "dissolve",
                    durationMs = 120,
                    curve = Curve.Linear,
                ),
            ),
        )
        val checkFractions = listOf(0.05f, 0.3f, 0.5f, 0.7f, 0.95f)
        val reverseFractions = listOf(0.3f, 0.5f)

        for (case in cases) {
            for (opaqueContent in listOf(false, true)) {
                val nodesA = if (opaqueContent) {
                    listOf(
                        NodeSpec(
                            id = "fillA",
                            frame = FrameSpec(x = 0.0, y = 0.0, w = 200.0, h = 120.0),
                            shape = ShapePayload(geometry = ShapeGeometry.Rect, fill = Fill.Solid("#FF0000")),
                        ),
                    )
                } else {
                    emptyList()
                }
                val nodesB = buildList {
                    if (opaqueContent) {
                        add(
                            NodeSpec(
                                id = "fillB",
                                frame = FrameSpec(x = 0.0, y = 0.0, w = 200.0, h = 120.0),
                                shape = ShapePayload(geometry = ShapeGeometry.Rect, fill = Fill.Solid("#0000FF")),
                            ),
                        )
                    }
                    add(pendingEnterB)
                }
                val doc = DeckDocument(
                    id = "transition-${case.label}-$opaqueContent",
                    stage = compactStage(200, 120),
                    slides = listOf(
                        SlideSpec(
                            id = "sA",
                            background = Fill.Solid(if (opaqueContent) "#111111" else "#FF0000"),
                            beats = listOf(BeatSpec()),
                            nodes = nodesA,
                            transition = case.spec,
                        ),
                        SlideSpec(
                            id = "sB",
                            background = Fill.Solid(if (opaqueContent) "#222222" else "#0000FF"),
                            beats = listOf(BeatSpec()),
                            nodes = nodesB,
                        ),
                    ),
                )
                val expectedMs = SegmentPlanner.planTransition(doc, "sA", "sB").segmentMs
                assertEquals(200, expectedMs, "Expected 200ms plan for ${case.label}")

                DocumentStageHarness(doc).use { h ->
                    val crossIdx = h.crossFromStateIndex(0)
                    val entranceIdx = h.entranceFromStateIndex(1)

                    // Helper: (170, 25) is inside pending-enter-b; (170, 95) has identical x=170 and
                    // identical radial distance from (100, 60) (dy = ±35), so under ALL transitions
                    // pending-enter-b being truly hidden (present = false) means pixel(170,25) == pixel(170,95)!
                    fun assertPendingEnterHidden(frame: RenderedFrame, where: String) {
                        val inside = frame.pixelRgba(170, 25)
                        val symmetric = frame.pixelRgba(170, 95)
                        assertTrue(
                            abs(inside[0] - symmetric[0]) <= 4 &&
                                abs(inside[1] - symmetric[1]) <= 4 &&
                                abs(inside[2] - symmetric[2]) <= 4,
                            "Pending enter (${case.label}, opaque=$opaqueContent) at $where: beat-0 enter node on B " +
                                "must stay hidden, got inside=(${inside[0]},${inside[1]},${inside[2]}) vs " +
                                "symmetric=(${symmetric[0]},${symmetric[1]},${symmetric[2]})",
                        )
                    }

                    // Endpoints + near-endpoints + interior cold seek frames
                    val seekP0 = h.seekSegment(crossIdx, 0f, rebuildScene = true)
                    assertPendingEnterHidden(seekP0, "Cross p=0")
                    val seekP1 = h.seekSegment(crossIdx, 1f, rebuildScene = true)
                    assertPendingEnterHidden(seekP1, "Cross p=1")

                    val seekFrames = checkFractions.associateWith { frac ->
                        h.seekSegment(crossIdx, frac, rebuildScene = true).also { frame ->
                            assertPendingEnterHidden(frame, "Cross cold-seek p=$frac")
                        }
                    }
                    case.verifyMidPixel(seekFrames.getValue(0.5f))

                    // AtStart(B) MUST still hide pending-enter-b; Entrance(B) p=0.5 blends yellow over blue;
                    // Rest(B, 0) renders pure yellow #FFFF00!
                    val atStartB = h.seekSegment(entranceIdx, 0f, rebuildScene = true)
                    assertPendingEnterHidden(atStartB, "AtStart(B)")
                    assertColorNear(atStartB.pixelColor(170, 25), 0, 0, 255, label = "${case.label} AtStart(B) is pure blue")

                    val midEntranceB = h.seekSegment(entranceIdx, 0.5f, rebuildScene = true)
                    assertColorNear(
                        midEntranceB.pixelColor(170, 25),
                        128,
                        128,
                        128,
                        tolerance = 18,
                        label = "${case.label} Entrance(B) p=0.5 dissolves yellow over blue",
                    )
                    assertColorNear(midEntranceB.pixelColor(170, 95), 0, 0, 255, label = "${case.label} Entrance(B) outside pending stays blue")

                    val restB = h.jumpAndSettle(Storyboard.Index(1, 0))
                    assertColorNear(restB.pixelColor(170, 25), 255, 255, 0, label = "${case.label} Rest(B, 0) pending-enter-b is pure yellow")

                    // Forward play A -> B across all 5 canonical fractions (stepMs=10 -> exact 0.05/0.3/0.5/0.7/0.95)
                    h.jumpAndSettle(Storyboard.Index(0, 0))
                    val (fwdFrames, fwdEff) = h.advanceAndCaptureMultiple(
                        direction = AdvanceDirection.Forward,
                        targetCanonicalFractions = checkFractions,
                        stepMs = 10L,
                        matchSegment = { f, t -> f == crossIdx && t == crossIdx + 1 },
                    )
                    assertEquals(expectedMs * 1_000_000L, fwdEff, "${case.label} forward duration mismatch")
                    for (frac in checkFractions) {
                        assertPendingEnterHidden(fwdFrames.getValue(frac), "Cross forward p=$frac")
                        assertFramesEqual(
                            seekFrames.getValue(frac),
                            fwdFrames.getValue(frac),
                            "${case.label} (opaque=$opaqueContent) p=$frac: forward != seek",
                        )
                    }

                    // Fresh backward play B -> A across all 5 canonical fractions
                    h.jumpAndSettle(Storyboard.Index(1, 0))
                    val (bwdFrames, bwdEff) = h.advanceAndCaptureMultiple(
                        direction = AdvanceDirection.Backward,
                        targetCanonicalFractions = checkFractions,
                        stepMs = 10L,
                        matchSegment = { f, t -> t == crossIdx && f == crossIdx + 1 },
                    )
                    assertEquals(expectedMs * 1_000_000L, bwdEff, "${case.label} backward duration mismatch")
                    for (frac in checkFractions) {
                        assertPendingEnterHidden(bwdFrames.getValue(frac), "Cross backward p=$frac")
                        assertFramesEqual(
                            fwdFrames.getValue(frac),
                            bwdFrames.getValue(frac),
                            "${case.label} (opaque=$opaqueContent) p=$frac: forward != backward",
                        )
                    }

                    // Mid-flight reversal: Forward to p=0.7 then reverse Backward, capturing p=0.5 and p=0.3!
                    h.jumpAndSettle(Storyboard.Index(0, 0))
                    val revFrames = h.advanceForwardThenReverseAndCapture(
                        reverseAtCanonicalFraction = 0.7f,
                        captureBackwardFractions = reverseFractions,
                        stepMs = 10L,
                        matchForwardSegment = { f, t -> f == crossIdx && t == crossIdx + 1 },
                    )
                    for (frac in reverseFractions) {
                        assertPendingEnterHidden(revFrames.getValue(frac), "Cross reversal p=$frac")
                        assertFramesEqual(
                            fwdFrames.getValue(frac),
                            revFrames.getValue(frac),
                            "${case.label} (opaque=$opaqueContent) p=$frac: mid-flight reversal != forward",
                        )
                    }
                }
            }
        }

        // Verify TransitionType.None is a 0-ms cut AND also respects beat-0 enter on B.
        val noneDoc = DeckDocument(
            id = "transition-none",
            stage = compactStage(200, 120),
            slides = listOf(
                SlideSpec(id = "sA", background = Fill.Solid("#FF0000"), transition = TransitionSpec(type = TransitionType.None)),
                SlideSpec(id = "sB", background = Fill.Solid("#0000FF")),
            ),
        )
        assertEquals(0, SegmentPlanner.planTransition(noneDoc, "sA", "sB").segmentMs)
        DocumentStageHarness(noneDoc).use { h ->
            val sawNonZeroDuration = h.onUiThread {
                val job = h.scope.launch { h.animatic.advance(AdvanceDirection.Forward) }
                var nonZero = false
                var steps = 0
                while (!job.isCompleted && steps++ < 400) {
                    h.renderStep(10_000_000L)
                    if (!h.animatic.scrubSettling && h.animatic.currentIndex != h.animatic.targetIndex) {
                        if (h.animatic.effectiveDurationNanos() > 0L) nonZero = true
                    }
                }
                nonZero
            }
            assertTrue(!sawNonZeroDuration, "TransitionType.None must have 0-ms cut")
            assertColorNear(h.renderCurrent().pixelColor(100, 60), 0, 0, 255, label = "none cut landed on B")
        }

        val noneWithPendingDoc = DeckDocument(
            id = "transition-none-pending-b",
            stage = compactStage(200, 120),
            slides = listOf(
                SlideSpec(id = "sA", background = Fill.Solid("#FF0000"), transition = TransitionSpec(type = TransitionType.None)),
                SlideSpec(id = "sB", background = Fill.Solid("#0000FF"), nodes = listOf(pendingEnterB)),
            ),
        )
        assertEquals(0, SegmentPlanner.planTransition(noneWithPendingDoc, "sA", "sB").segmentMs)
        DocumentStageHarness(noneWithPendingDoc).use { h ->
            val entranceIdx = h.entranceFromStateIndex(1)
            val atStartB = h.seekSegment(entranceIdx, 0f, rebuildScene = true)
            assertColorNear(atStartB.pixelColor(170, 25), 0, 0, 255, label = "TransitionType.None AtStart(B) hides beat-0 enter node")
            val midEntranceB = h.seekSegment(entranceIdx, 0.5f, rebuildScene = true)
            assertColorNear(midEntranceB.pixelColor(170, 25), 128, 128, 128, tolerance = 18, label = "TransitionType.None Entrance(B) p=0.5 dissolves beat-0 enter node")
            h.jumpAndSettle(Storyboard.Index(0, 0))
            h.advanceToRest(AdvanceDirection.Forward, stepMs = 10L)
            assertColorNear(h.renderCurrent().pixelColor(170, 25), 255, 255, 0, label = "TransitionType.None Rest(B, 0) renders beat-0 enter node")
        }

        val revealReducedDoc = DeckDocument(
            id = "transition-reveal-reduced",
            stage = compactStage(200, 120),
            slides = listOf(
                SlideSpec(id = "sA", background = Fill.Solid("#FF0000"), transition = TransitionSpec(type = TransitionType.Reveal, durationMs = 400)),
                SlideSpec(id = "sB", background = Fill.Solid("#0000FF")),
            ),
        )
        DocumentStageHarness(
            revealReducedDoc,
            initialConfig = DocumentRenderConfig(baseDensity = 1f, reducedMotion = true, fontsDir = bundledFontsDirPath()),
        ).use { h ->
            val crossIdx = h.crossFromStateIndex(0)
            val mid = h.seekSegment(crossIdx, 0.5f, rebuildScene = true)
            // Reduced motion collapses Reveal -> Dissolve (uniform blend ~(128, 0, 128) at both left & right!)
            assertColorNear(mid.pixelColor(40, 60), 128, 0, 128, tolerance = 16, label = "reduced reveal dissolves left")
            assertColorNear(mid.pixelColor(160, 60), 128, 0, 128, tolerance = 16, label = "reduced reveal dissolves right")
            assertEquals(150_000_000L, h.animatic.effectiveDurationNanos(), "Reduced motion cold seek transition must run 150 ms")
            // Also verify warm forward play runs exact 150 ms (never stretched to 171 ms interruption spring!)
            h.jumpAndSettle(Storyboard.Index(0, 0))
            val (warmFwdMid, warmFwdEff) = h.advanceAndCaptureAt(
                direction = AdvanceDirection.Forward,
                targetCanonicalFraction = 0.5f,
                stepMs = 5L,
                matchSegment = { f, t -> f == crossIdx && t == crossIdx + 1 },
            )
            assertEquals(150_000_000L, warmFwdEff, "Reduced motion warm forward play transition must run exact 150 ms")
            assertFramesEqual(mid, warmFwdMid, "Reduced motion warm forward play must match cold seek")
        }
    }

    @Test
    fun concurrentScenePreviewNeverClobbersLiveEaselSceneZIndexAndLegacySlideLayersUnchanged() {
        // 1. Concurrent Easel (mid-Reveal AOverB) + ScenePreview sharing the SAME Storyboard!
        val doc = DeckDocument(
            id = "concurrent-preview",
            stage = compactStage(200, 120),
            slides = listOf(
                SlideSpec(
                    id = "sA",
                    background = Fill.Solid("#FF0000"),
                    transition = TransitionSpec(type = TransitionType.Reveal, direction = "rightToLeft", durationMs = 200, curve = Curve.Linear),
                ),
                SlideSpec(id = "sB", background = Fill.Solid("#0000FF")),
            ),
        )
        val session = RenderSession.forPresent(documentProvider = { doc })
        var animaticRef: Animatic? = null
        var scopeRef: kotlinx.coroutines.CoroutineScope? = null
        val scene = ImageComposeScene(
            width = 400,
            height = 120,
            density = Density(1f),
            coroutineContext = Dispatchers.Unconfined,
        ) {
            scopeRef = androidx.compose.runtime.rememberCoroutineScope()
            val sb = rememberDocumentStoryboard(session, DocumentRenderConfig(baseDensity = 1f))
            val a = rememberAnimatic(storyboard = { sb })
            animaticRef = a
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.size(200.dp, 120.dp)) {
                    Easel(a, mode = SceneMode.Preview)
                }
                Box(Modifier.size(200.dp, 120.dp)) {
                    ScenePreview(storyboard = sb, index = Storyboard.Index(1, 0))
                }
            }
        }
        try {
            var nanos = 0L
            fun step(ms: Long = 10L): RenderedFrame {
                nanos += ms * 1_000_000L
                val img = scene.render(nanos)
                return try { readSkiaImageFrame(img) } finally { img.close() }
            }
            repeat(4) { step() }
            val a = checkNotNull(animaticRef)
            val sc = checkNotNull(scopeRef)
            val crossIdx = a.stateDescriptors.first { it.sceneIndex == 0 && it.isBoundaryEnd }.stateIndex
            val job = sc.launch { a.seekToSegment(crossIdx, 0.5f, rebuildScene = true) }
            while (!job.isCompleted) step(5L)
            val frame = step(1L)

            // Left half (Easel): Reveal mid-point MUST keep A (Red) on top at x=40 and B (Blue) revealed at x=160!
            assertColorNear(frame.pixelColor(40, 60), 255, 0, 0, label = "live Easel Reveal left (A on top)")
            assertColorNear(frame.pixelColor(160, 60), 0, 0, 255, label = "live Easel Reveal right (B)")
            // Right half (ScenePreview of slide B): still blue without overlay interference!
            assertColorNear(frame.pixelColor(300, 60), 0, 0, 255, label = "concurrent ScenePreview B")
        } finally {
            scene.close()
        }

        // 2. Legacy SlideLayers deck without sceneZIndex played backward renders identically to existing host behavior.
        val legacyFormat = SceneFormat(IntSize(120, 80), Density(1f))
        val legacyStoryboard = Storyboard.build(title = "LegacySlideLayers", format = legacyFormat) {
            scene(frames = listOf(0)) {
                SlideLayers(palette = SlidePalette(canvas = Color(0xFFAA2222))) {
                    Box(Modifier.fillMaxSize())
                }
            }
            scene(frames = listOf(0)) {
                SlideLayers(palette = SlidePalette(canvas = Color(0xFF2222AA))) {
                    Box(Modifier.fillMaxSize())
                }
            }
        }
        var legacyAnim: Animatic? = null
        var legacyScope: kotlinx.coroutines.CoroutineScope? = null
        val legacyScene = ImageComposeScene(120, 80, Density(1f), coroutineContext = Dispatchers.Unconfined) {
            legacyScope = androidx.compose.runtime.rememberCoroutineScope()
            val a = rememberAnimatic(initialIndex = Storyboard.Index(1, 0), storyboard = { legacyStoryboard })
            legacyAnim = a
            Easel(a, mode = SceneMode.Preview)
        }
        try {
            var t = 0L
            fun render(): RenderedFrame {
                t += 16_000_000L
                val img = legacyScene.render(t)
                return try { readSkiaImageFrame(img) } finally { img.close() }
            }
            repeat(3) { render() }
            assertColorNear(render().pixelColor(60, 40), 0x22, 0x22, 0xAA, label = "legacy start on slide 1")
            val j = checkNotNull(legacyScope).launch { checkNotNull(legacyAnim).advance(AdvanceDirection.Backward) }
            var g = 0
            while (!j.isCompleted && g++ < 200) render()
            assertTrue(j.isCompleted)
            repeat(3) { render() }
            assertColorNear(render().pixelColor(60, 40), 0xAA, 0x22, 0x22, label = "legacy backward landed on slide 0")
        } finally {
            legacyScene.close()
        }
    }

    @Test
    fun magicMoveOwnershipAffineFallbacksClippingHandOffsAndColdSessionParity() {
        // Test document combining:
        // 1. Semi-transparent travelling shape inside nested rotated padded stack with non-default origin
        //    -> verifies EXACT once-per-frame drawing (no alpha doubling) + exact affine endpoint match!
        // 2. Clipped ancestor stack (clip = true) whose child overflows parent box -> clipped at rest, unclipped in Overlay!
        // 3. Unsized wrapping text & surfaced text travelling smoothly across slides!
        // 4. Sheared node (rotated child of non-uniformly scaled parent) + flow-layout child -> cross-fade fallback!
        // 5. Overlapping unmatched shapes & code card -> fwd == bwd stacking!
        val magicTransition = TransitionSpec(
            type = TransitionType.MagicMove,
            durationMs = 200,
            curve = Curve.Linear,
            phases = Phases(
                exit = Timing(durationMs = 100, curve = Curve.Linear),
                move = Timing(durationMs = 200, curve = Curve.Linear),
                enter = Timing(delayMs = 100, durationMs = 100, curve = Curve.Linear),
            ),
        )

        val doc = DeckDocument(
            id = "magicmove-ownership",
            stage = compactStage(480, 300),
            slides = listOf(
                SlideSpec(
                    id = "sA",
                    background = Fill.Solid("#101018"),
                    transition = magicTransition,
                    nodes = listOf(
                        // Padded rotated parent stack holding semi-transparent travelling badge
                        NodeSpec(
                            id = "parentA",
                            frame = FrameSpec(x = 20.0, y = 20.0, w = 160.0, h = 110.0),
                            rotation = 15.0,
                            origin = Vec2(0.0, 0.0),
                            stack = ContainerPayload(
                                padding = PaddingSpec(10.0, 12.0, 10.0, 12.0),
                                surface = SurfaceSpec(padding = PaddingSpec(4.0, 4.0, 4.0, 4.0)),
                                children = listOf(
                                    NodeSpec(
                                        id = "traveller",
                                        frame = FrameSpec(x = 12.0, y = 8.0, w = 60.0, h = 40.0),
                                        opacity = 0.5, // If drawn twice, alpha would be 0.75 instead of 0.5!
                                        origin = Vec2(0.25, 0.75),
                                        rotation = -15.0,
                                        shape = ShapePayload(geometry = ShapeGeometry.Rect, fill = Fill.Solid("#FF0000")),
                                    ),
                                ),
                            ),
                        ),
                        // Clipped parent stack with overflowing travelling child
                        NodeSpec(
                            id = "clipParentA",
                            frame = FrameSpec(x = 20.0, y = 160.0, w = 40.0, h = 40.0),
                            stack = ContainerPayload(
                                clip = true,
                                children = listOf(
                                    NodeSpec(
                                        id = "clippedChild",
                                        frame = FrameSpec(x = 0.0, y = 0.0, w = 90.0, h = 40.0),
                                        shape = ShapePayload(geometry = ShapeGeometry.Rect, fill = Fill.Solid("#00FF00")),
                                    ),
                                ),
                            ),
                        ),
                        // Unsized wrapping text + surfaced text
                        NodeSpec(
                            id = "unsized-wrap",
                            frame = FrameSpec(x = 220.0, y = 20.0),
                            text = TextPayload(
                                value = RichTextValue.Plain("Wrapping headline across two slides cleanly"),
                                size = 18.0,
                                color = "#FFFFFF",
                            ),
                        ),
                        NodeSpec(
                            id = "surfaced-pill",
                            frame = FrameSpec(x = 220.0, y = 110.0, w = 140.0),
                            text = TextPayload(
                                value = RichTextValue.Plain("Alpha"),
                                size = 18.0,
                                color = "#FFFFFF",
                                surface = SurfaceSpec(
                                    fill = Fill.Solid("#2563EB"),
                                    stroke = StrokeSpec(color = "#93C5FD", width = 2.0),
                                    cornerRadius = 8.0,
                                    padding = PaddingSpec(6.0, 10.0, 6.0, 10.0),
                                ),
                            ),
                        ),
                        // Sheared parent (non-uniform scale + rotated child) -> MUST fall back to cross-fade
                        NodeSpec(
                            id = "shearParentA",
                            frame = FrameSpec(x = 20.0, y = 220.0, w = 80.0, h = 50.0),
                            scale = ScaleValue.NonUniform(x = 1.5, y = 0.7),
                            stack = ContainerPayload(
                                children = listOf(
                                    NodeSpec(
                                        id = "shearedNode",
                                        frame = FrameSpec(x = 5.0, y = 5.0, w = 30.0, h = 20.0),
                                        rotation = 30.0,
                                        shape = ShapePayload(geometry = ShapeGeometry.Rect, fill = Fill.Solid("#FACC15")),
                                    ),
                                ),
                            ),
                        ),
                        // Flow-layout child -> MUST NOT travel, cross-fades & reports lint
                        NodeSpec(
                            id = "flowRowA",
                            frame = FrameSpec(x = 140.0, y = 220.0, w = 120.0, h = 50.0),
                            row = ContainerPayload(
                                gap = 6.0,
                                children = listOf(
                                    NodeSpec(
                                        id = "flowChild",
                                        frame = FrameSpec(w = 40.0, h = 30.0),
                                        shape = ShapePayload(geometry = ShapeGeometry.Rect, fill = Fill.Solid("#F97316")),
                                    ),
                                ),
                            ),
                        ),
                        // Overlapping unmatched shape + code card
                        NodeSpec(
                            id = "unmatchedA",
                            frame = FrameSpec(x = 340.0, y = 180.0, w = 100.0, h = 70.0),
                            shape = ShapePayload(geometry = ShapeGeometry.Rect, fill = Fill.Solid("#EC4899")),
                        ),
                        NodeSpec(
                            id = "codeCard",
                            frame = FrameSpec(x = 350.0, y = 200.0, w = 100.0, h = 60.0),
                            code = CodePayload(language = "kotlin", value = "val a = 1"),
                        ),
                    ),
                ),
                SlideSpec(
                    id = "sB",
                    background = Fill.Solid("#101018"),
                    nodes = listOf(
                        NodeSpec(
                            id = "traveller",
                            frame = FrameSpec(x = 120.0, y = 60.0, w = 60.0, h = 40.0),
                            opacity = 0.5,
                            origin = Vec2(0.25, 0.75),
                            rotation = 0.0,
                            shape = ShapePayload(geometry = ShapeGeometry.Rect, fill = Fill.Solid("#FF0000")),
                        ),
                        NodeSpec(
                            id = "clipParentB",
                            frame = FrameSpec(x = 120.0, y = 160.0, w = 40.0, h = 40.0),
                            stack = ContainerPayload(
                                clip = true,
                                children = listOf(
                                    NodeSpec(
                                        id = "clippedChild",
                                        frame = FrameSpec(x = 0.0, y = 0.0, w = 90.0, h = 40.0),
                                        shape = ShapePayload(geometry = ShapeGeometry.Rect, fill = Fill.Solid("#00FF00")),
                                    ),
                                ),
                            ),
                        ),
                        NodeSpec(
                            id = "unsized-wrap",
                            frame = FrameSpec(x = 260.0, y = 40.0),
                            text = TextPayload(
                                value = RichTextValue.Plain("Wrapping headline across two slides cleanly"),
                                size = 18.0,
                                color = "#FFFFFF",
                            ),
                        ),
                        NodeSpec(
                            id = "surfaced-pill",
                            frame = FrameSpec(x = 280.0, y = 130.0, w = 160.0),
                            text = TextPayload(
                                value = RichTextValue.Plain("Alpha Beta"),
                                size = 18.0,
                                color = "#FFFFFF",
                                surface = SurfaceSpec(
                                    fill = Fill.Solid("#16A34A"),
                                    stroke = StrokeSpec(color = "#86EFAC", width = 2.0),
                                    cornerRadius = 12.0,
                                    padding = PaddingSpec(6.0, 10.0, 6.0, 10.0),
                                ),
                            ),
                        ),
                        NodeSpec(
                            id = "shearedNode",
                            frame = FrameSpec(x = 40.0, y = 230.0, w = 30.0, h = 20.0),
                            shape = ShapePayload(geometry = ShapeGeometry.Rect, fill = Fill.Solid("#FACC15")),
                        ),
                        NodeSpec(
                            id = "flowChild",
                            frame = FrameSpec(x = 160.0, y = 230.0, w = 40.0, h = 30.0),
                            shape = ShapePayload(geometry = ShapeGeometry.Rect, fill = Fill.Solid("#F97316")),
                        ),
                        NodeSpec(
                            id = "unmatchedB",
                            frame = FrameSpec(x = 340.0, y = 180.0, w = 100.0, h = 70.0),
                            shape = ShapePayload(geometry = ShapeGeometry.Rect, fill = Fill.Solid("#06B6D4")),
                        ),
                        NodeSpec(
                            id = "codeCard",
                            frame = FrameSpec(x = 350.0, y = 200.0, w = 100.0, h = 60.0),
                            code = CodePayload(language = "kotlin", value = "val b = 2"),
                        ),
                    ),
                ),
            ),
        )

        // Verify pure match result: sheared and codeCard are NOT matched; traveller, clippedChild,
        // unsized-wrap, surfaced-pill, and flowChild ARE matched (flowChild via Lookahead).
        val matchResult = CrossSlideGeometry.matchCrossSlidePairs(doc.slides[0], doc.slides[1], doc.stage)
        val matchedIds = matchResult.pairs.map { it.nodeId }.toSet()
        assertTrue("traveller" in matchedIds)
        assertTrue("clippedChild" in matchedIds)
        assertTrue("unsized-wrap" in matchedIds)
        assertTrue("surfaced-pill" in matchedIds)
        assertTrue("flowChild" in matchedIds, "Flow-layout child must now match via Lookahead Magic Move")
        assertFalse(matchResult.pairs.first { it.nodeId == "flowChild" }.isDocumentDetermined)
        assertTrue("shearedNode" !in matchedIds, "Sheared node must fall back to cross-fade")
        assertTrue("codeCard" !in matchedIds, "Code node must cross-fade across slides")
        assertFalse(matchResult.lintIssues.any { it.code == "CROSS_SLIDE_FLOW_ANCESTOR" })
        assertTrue(matchResult.lintIssues.any { it.code == "CROSS_SLIDE_SHEARED_TRANSFORM" })

        val travellerPair = matchResult.pairs.first { it.nodeId == "traveller" }

        val warmMidFrame = DocumentStageHarness(doc).use { h ->
            val crossIdx = h.crossFromStateIndex(0)

            // Resting Slide A & Slide B frames
            val restA = h.jumpAndSettle(Storyboard.Index(0, 0))
            // At rest on A: clippedChild is clipped at x = 60 (parent x=20, w=40), so pixel (80, 180) is background #101018!
            assertColorNear(restA.pixelColor(80, 180), 0x10, 0x10, 0x18, label = "clippedChild clipped at rest A")

            val restB = h.jumpAndSettle(Storyboard.Index(1, 0))
            assertColorNear(restB.pixelColor(180, 180), 0x10, 0x10, 0x18, label = "clippedChild clipped at rest B")

            // At p = 0 of Cross(A, B): matches resting Slide A pixel-for-pixel (and traveller is NOT double-drawn!).
            val crossP0 = h.seekSegment(crossIdx, 0f, rebuildScene = true)
            // Verify traveller center pixel at p = 0 has single 0.5-alpha red over #101018 (~135, 8, 12), NOT doubled (~195)!
            val fromCenter = travellerPair.fromTransform.toAffine().mapPoint(30.0, 20.0)
            val restTravPixel = restA.pixelColor(fromCenter.x.toInt(), fromCenter.y.toInt())
            val p0TravPixel = crossP0.pixelColor(fromCenter.x.toInt(), fromCenter.y.toInt())
            assertColorNear(
                p0TravPixel,
                (restTravPixel.red * 255f).toInt(),
                (restTravPixel.green * 255f).toInt(),
                (restTravPixel.blue * 255f).toInt(),
                tolerance = 4,
                label = "traveller p=0 exact once-per-frame match",
            )

            // Cold seek across near-endpoints & interior fractions (0.05, 0.3, 0.5, 0.7, 0.95)
            val checkFractions = listOf(0.05f, 0.3f, 0.5f, 0.7f, 0.95f)
            val seekFrames = checkFractions.associateWith { frac ->
                h.seekSegment(crossIdx, frac, rebuildScene = true)
            }
            val seekMid = seekFrames.getValue(0.5f)
            // Verify surfaced-pill reaches green #16A34A near p = 0.95 on its right surface area (420, 145), clear of text glyphs
            val nearEndPill = seekFrames.getValue(0.95f).pixelColor(420, 145)
            val restBPill = restB.pixelColor(420, 145)
            assertColorNear(
                nearEndPill,
                (restBPill.red * 255f).toInt(),
                (restBPill.green * 255f).toInt(),
                (restBPill.blue * 255f).toInt(),
                tolerance = 24,
                label = "surfaced-pill landing handoff at p=0.95 matches restB",
            )

            // At p = 0.5: clippedChild travels UNCLIPPED in Overlay! Its box sits at x = 70..160, y = 160..200,
            // so pixel (140, 180) (beyond the 40px parent clip width!) MUST be bright green #00FF00!
            assertColorNear(seekMid.pixelColor(140, 180), 0, 255, 0, label = "clippedChild unclipped while travelling in Overlay")

            // Check traveller at p = 0.5 has single 0.5-alpha red (R ~ 135, NOT ~195 from double drawing!)
            val midCenter = dev.sebastiano.ciacola.document.DecomposedTransform
                .lerp(travellerPair.fromTransform, travellerPair.toTransform, 0.5)
                .toAffine()
                .mapPoint(30.0, 20.0)
            assertColorNear(seekMid.pixelColor(midCenter.x.toInt(), midCenter.y.toInt()), 135, 8, 12, tolerance = 14, label = "traveller p=0.5 drawn exactly once")

            // Draw-only frame (re-render with no layout change) equals seekMid pixel-for-pixel!
            h.seekSegment(crossIdx, 0.5f, rebuildScene = true)
            val redrawMid = h.renderCurrent()
            assertFramesEqual(seekMid, redrawMid, "draw-only redraw frame mismatch")

            // Warm forward play across all 5 fractions equals cold seek AND fresh backward play!
            h.jumpAndSettle(Storyboard.Index(0, 0))
            val (fwdFrames, _) = h.advanceAndCaptureMultiple(
                direction = AdvanceDirection.Forward,
                targetCanonicalFractions = checkFractions,
                stepMs = 10L,
                matchSegment = { f, t -> f == crossIdx && t == crossIdx + 1 },
            )
            for (frac in checkFractions) {
                assertFramesEqual(seekFrames.getValue(frac), fwdFrames.getValue(frac), "magicMove p=$frac warm forward != cold seek")
            }

            h.jumpAndSettle(Storyboard.Index(1, 0))
            val (bwdFrames, _) = h.advanceAndCaptureMultiple(
                direction = AdvanceDirection.Backward,
                targetCanonicalFractions = checkFractions,
                stepMs = 10L,
                matchSegment = { f, t -> t == crossIdx && f == crossIdx + 1 },
            )
            for (frac in checkFractions) {
                assertFramesEqual(
                    fwdFrames.getValue(frac),
                    bwdFrames.getValue(frac),
                    "magicMove p=$frac forward != fresh backward (unmatched stacking & travelling)",
                )
            }

            // Mid-flight reversal (Forward to 0.7 -> Backward) equals forward at p = 0.5 & 0.3!
            h.jumpAndSettle(Storyboard.Index(0, 0))
            val revFrames = h.advanceForwardThenReverseAndCapture(
                reverseAtCanonicalFraction = 0.7f,
                captureBackwardFractions = listOf(0.3f, 0.5f),
                stepMs = 10L,
                matchForwardSegment = { f, t -> f == crossIdx && t == crossIdx + 1 },
            )
            for (frac in listOf(0.3f, 0.5f)) {
                assertFramesEqual(
                    fwdFrames.getValue(frac),
                    revFrames.getValue(frac),
                    "magicMove p=$frac mid-flight reversal != forward",
                )
            }

            fwdFrames.getValue(0.5f)
        }

        // Fresh RenderSession cold seek to p = 0.5 equals warm play pixel-for-pixel!
        DocumentStageHarness(doc).use { fresh ->
            val freshCross = fresh.crossFromStateIndex(0)
            val freshMid = fresh.seekSegment(freshCross, 0.5f, rebuildScene = true)
            assertFramesEqual(warmMidFrame, freshMid, "fresh RenderSession cold seek != warm play")
        }
    }

    @Test
    fun magicMoveBeat0EnterPendingMatchedContainerDescendantsLoupeAndTextSeamRules() {
        val mm = TransitionSpec(
            type = TransitionType.MagicMove,
            durationMs = 200,
            curve = Curve.Linear,
            phases = Phases(
                exit = Timing(durationMs = 100, curve = Curve.Linear),
                move = Timing(durationMs = 200, curve = Curve.Linear),
                enter = Timing(delayMs = 100, durationMs = 100, curve = Curve.Linear),
            ),
        )
        val doc = DeckDocument(
            id = "magicmove-container-loupe-text",
            stage = compactStage(440, 300),
            slides = listOf(
                SlideSpec(
                    id = "sA",
                    background = Fill.Solid("#101018"),
                    transition = mm,
                    nodes = listOf(
                        // Matched travelling stack container with matched child + unmatched A child
                        NodeSpec(
                            id = "card",
                            frame = FrameSpec(x = 20.0, y = 20.0, w = 140.0, h = 120.0),
                            stack = ContainerPayload(
                                surface = SurfaceSpec(fill = Fill.Solid("#1E293B")),
                                children = listOf(
                                    NodeSpec(
                                        id = "inner-badge",
                                        frame = FrameSpec(x = 10.0, y = 10.0, w = 50.0, h = 40.0),
                                        shape = ShapePayload(geometry = ShapeGeometry.Rect, fill = Fill.Solid("#00FF00")),
                                    ),
                                    NodeSpec(
                                        id = "only-a",
                                        frame = FrameSpec(x = 80.0, y = 10.0, w = 40.0, h = 40.0),
                                        shape = ShapePayload(geometry = ShapeGeometry.Rect, fill = Fill.Solid("#EF4444")),
                                    ),
                                ),
                            ),
                        ),
                        // Matched travelling loupe container holding its target child
                        NodeSpec(
                            id = "mag-lens",
                            frame = FrameSpec(x = 20.0, y = 170.0, w = 160.0, h = 100.0),
                            loupe = LoupePayload(
                                target = "loupe-target",
                                zoom = 1.5,
                                diameter = 70.0,
                                align = "centerEnd",
                                lensFill = Fill.Solid("#06B6D4"),
                                children = listOf(
                                    NodeSpec(
                                        id = "loupe-target",
                                        frame = FrameSpec(x = 10.0, y = 25.0, w = 50.0, h = 50.0),
                                        shape = ShapePayload(geometry = ShapeGeometry.Rect, fill = Fill.Solid("#EC4899")),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
                SlideSpec(
                    id = "sB",
                    background = Fill.Solid("#101018"),
                    nodes = listOf(
                        NodeSpec(
                            id = "card",
                            frame = FrameSpec(x = 140.0, y = 80.0, w = 140.0, h = 120.0),
                            stack = ContainerPayload(
                                surface = SurfaceSpec(fill = Fill.Solid("#1E293B")),
                                children = listOf(
                                    NodeSpec(
                                        id = "inner-badge",
                                        frame = FrameSpec(x = 70.0, y = 50.0, w = 50.0, h = 40.0),
                                        shape = ShapePayload(geometry = ShapeGeometry.Rect, fill = Fill.Solid("#00FF00")),
                                    ),
                                    NodeSpec(
                                        id = "only-b",
                                        frame = FrameSpec(x = 10.0, y = 60.0, w = 40.0, h = 40.0),
                                        shape = ShapePayload(geometry = ShapeGeometry.Rect, fill = Fill.Solid("#3B82F6")),
                                    ),
                                    NodeSpec(
                                        id = "inner-pending-b",
                                        frame = FrameSpec(x = 80.0, y = 10.0, w = 40.0, h = 40.0),
                                        shape = ShapePayload(geometry = ShapeGeometry.Rect, fill = Fill.Solid("#FFFF00")),
                                        builds = listOf(
                                            Build.Enter(beat = 0, effect = "dissolve", durationMs = 120, curve = Curve.Linear),
                                        ),
                                    ),
                                ),
                            ),
                        ),
                        NodeSpec(
                            id = "mag-lens",
                            frame = FrameSpec(x = 180.0, y = 170.0, w = 160.0, h = 100.0),
                            loupe = LoupePayload(
                                target = "loupe-target",
                                zoom = 1.5,
                                diameter = 70.0,
                                align = "centerEnd",
                                lensFill = Fill.Solid("#06B6D4"),
                                children = listOf(
                                    NodeSpec(
                                        id = "loupe-target",
                                        frame = FrameSpec(x = 10.0, y = 25.0, w = 50.0, h = 50.0),
                                        shape = ShapePayload(geometry = ShapeGeometry.Rect, fill = Fill.Solid("#EC4899")),
                                    ),
                                ),
                            ),
                        ),
                        // Top-level beat-0 enter node on B
                        NodeSpec(
                            id = "pending-b",
                            frame = FrameSpec(x = 340.0, y = 20.0, w = 60.0, h = 40.0),
                            shape = ShapePayload(geometry = ShapeGeometry.Rect, fill = Fill.Solid("#FFFF00")),
                            builds = listOf(
                                Build.Enter(beat = 0, effect = "dissolve", durationMs = 120, curve = Curve.Linear),
                            ),
                        ),
                    ),
                ),
            ),
        )

        // Verify SegmentPlanner emits NO separate TransitionExit/TransitionEnter tracks for descendants of `card`!
        val plan = SegmentPlanner.planTransition(doc, "sA", "sB")
        val descendantIds = setOf("inner-badge", "only-a", "only-b", "inner-pending-b")
        assertTrue(
            plan.tracks.none { it.nodeId in descendantIds },
            "Descendants inside matched container `card` must not receive standalone Exit/Enter tracks, got ${plan.tracks.map { it.nodeId to it.kind }}",
        )

        DocumentStageHarness(doc).use { h ->
            val crossIdx = h.crossFromStateIndex(0)
            val entranceIdx = h.entranceFromStateIndex(1)
            val checkFractions = listOf(0.05f, 0.5f, 0.95f)

            val seekFrames = checkFractions.associateWith { frac ->
                h.seekSegment(crossIdx, frac, rebuildScene = true)
            }
            // `pending-b` stays hidden throughout Cross(A, B) AND AtStart(B)!
            for (frac in checkFractions) {
                assertColorNear(
                    seekFrames.getValue(frac).pixelColor(370, 40),
                    0x10,
                    0x10,
                    0x18,
                    label = "magicMove Cross p=$frac hides beat-0 enter pending-b",
                )
            }
            val atStartB = h.seekSegment(entranceIdx, 0f, rebuildScene = true)
            assertColorNear(atStartB.pixelColor(370, 40), 0x10, 0x10, 0x18, label = "magicMove AtStart(B) hides beat-0 enter pending-b")
            // Also verify `inner-pending-b` inside `card` is hidden at AtStart(B) (card at 140,80 + child 80,10 -> 240,110)
            assertColorNear(atStartB.pixelColor(240, 110), 0x1E, 0x29, 0x3B, label = "magicMove AtStart(B) hides inner-pending-b inside card")

            val midEntranceB = h.seekSegment(entranceIdx, 0.5f, rebuildScene = true)
            val midPending = midEntranceB.pixelColor(370, 40)
            assertTrue(
                midPending.red > 0.4f && midPending.green > 0.4f,
                "magicMove Entrance(B) p=0.5 must blend yellow pending-b, got $midPending",
            )
            val restB = h.jumpAndSettle(Storyboard.Index(1, 0))
            assertColorNear(restB.pixelColor(370, 40), 255, 255, 0, label = "magicMove Rest(B,0) pending-b pure yellow")
            assertColorNear(restB.pixelColor(240, 110), 255, 255, 0, label = "magicMove Rest(B,0) inner-pending-b pure yellow")

            // At p = 0.5, `card` is at (80, 50) and matched child `inner-badge` is at local (40, 30)
            // -> stage (120..170, 80..120), staying PURE OPAQUE GREEN #00FF00 (NOT faded out/in!).
            val seekMid = seekFrames.getValue(0.5f)
            assertColorNear(seekMid.pixelColor(145, 100), 0, 255, 0, tolerance = 6, label = "Matched inner-badge stays opaque green at p=0.5")

            // Matched `mag-lens` loupe travels from x=20 to x=180 -> at p=0.5 its box is (100..260, 170..270).
            // Its target child sits at (110..160, 195..245) (#EC4899) and its magnified lens sits on the right (~200, 220)!
            assertColorNear(seekMid.pixelColor(135, 220), 0xEC, 0x48, 0x99, tolerance = 12, label = "Matched loupe child renders visibly in Overlay at p=0.5")
            val loupeLensPx = seekMid.pixelRgba(200, 220)
            assertTrue(
                abs(loupeLensPx[0] - 0x10) > 15 || abs(loupeLensPx[1] - 0x10) > 15 || abs(loupeLensPx[2] - 0x18) > 15,
                "Matched loupe lens must render visibly in Overlay at p=0.5, got RGB(${loupeLensPx[0]},${loupeLensPx[1]},${loupeLensPx[2]})",
            )

            // Path independence: forward == fresh backward == reversal == cold seek
            h.jumpAndSettle(Storyboard.Index(0, 0))
            val (fwdFrames, _) = h.advanceAndCaptureMultiple(
                direction = AdvanceDirection.Forward,
                targetCanonicalFractions = checkFractions,
                stepMs = 10L,
                matchSegment = { f, t -> f == crossIdx && t == crossIdx + 1 },
            )
            h.jumpAndSettle(Storyboard.Index(1, 0))
            val (bwdFrames, _) = h.advanceAndCaptureMultiple(
                direction = AdvanceDirection.Backward,
                targetCanonicalFractions = checkFractions,
                stepMs = 10L,
                matchSegment = { f, t -> t == crossIdx && f == crossIdx + 1 },
            )
            for (frac in checkFractions) {
                assertFramesEqual(seekFrames.getValue(frac), fwdFrames.getValue(frac), "magicMove p=$frac fwd != seek")
                assertFramesEqual(fwdFrames.getValue(frac), bwdFrames.getValue(frac), "magicMove p=$frac fwd != bwd")
            }
            h.jumpAndSettle(Storyboard.Index(0, 0))
            val revFrames = h.advanceForwardThenReverseAndCapture(
                reverseAtCanonicalFraction = 0.7f,
                captureBackwardFractions = listOf(0.5f),
                stepMs = 10L,
                matchForwardSegment = { f, t -> f == crossIdx && t == crossIdx + 1 },
            )
            assertFramesEqual(fwdFrames.getValue(0.5f), revFrames.getValue(0.5f), "magicMove p=0.5 reversal != fwd")
        }

        // `isCharacterDeliveryNode` includes incoming `transition.text` char morph!
        val plainTextNode = NodeSpec(
            id = "headline",
            frame = FrameSpec(x = 20.0, y = 20.0, w = 200.0, h = 40.0),
            text = TextPayload(value = RichTextValue.Plain("Hello"), size = 18.0, color = "#FFFFFF"),
        )
        val charIncoming = TransitionSpec(
            type = TransitionType.MagicMove,
            text = mapOf("headline" to TextMorphSpec(by = MorphBy.Character)),
        )
        assertTrue(
            DocumentMotionEvaluator.isCharacterDeliveryNode(
                rawNode = plainTextNode,
                slideTransition = null,
                incomingTransition = charIncoming,
            ),
            "isCharacterDeliveryNode must return true when incoming transition uses MorphBy.Character",
        )
        assertFalse(
            DocumentMotionEvaluator.isCharacterDeliveryNode(
                rawNode = plainTextNode,
                slideTransition = null,
                incomingTransition = TransitionSpec(type = TransitionType.MagicMove),
            ),
        )

        // `evaluateTravellingNode` MUST set `interpolateLayoutSize = false` during Cross(A, B)!
        val textSlideA = SlideSpec(
            id = "ta",
            transition = mm,
            nodes = listOf(
                NodeSpec(
                    id = "title",
                    frame = FrameSpec(x = 20.0, y = 20.0, w = 200.0, h = 40.0),
                    text = TextPayload(value = RichTextValue.Plain("One"), size = 18.0, color = "#FFFFFF"),
                ),
            ),
        )
        val textSlideB = SlideSpec(
            id = "tb",
            nodes = listOf(
                NodeSpec(
                    id = "title",
                    frame = FrameSpec(x = 120.0, y = 60.0, w = 260.0, h = 60.0),
                    text = TextPayload(value = RichTextValue.Plain("Two"), size = 36.0, color = "#FFFFFF"),
                ),
            ),
        )
        val textDoc = DeckDocument(id = "text-seam-doc", stage = compactStage(400, 200), slides = listOf(textSlideA, textSlideB))
        val textPair = CrossSlideGeometry.matchCrossSlidePairs(textSlideA, textSlideB, textDoc.stage).pairs.single()
        val textPlan = SegmentPlanner.planTransition(textDoc, "ta", "tb")
        val moveTrack = textPlan.tracks.first { it.nodeId == "title" }
        val colorResolver = DocumentColorResolver(
            themeSpec = null,
            baseTheme = resolveDeckTheme(textDoc),
            slidePaletteSpec = null,
        )
        val evalTrav = DocumentMotionEvaluator.evaluateTravellingNode(
            pair = textPair,
            moveTrack = moveTrack,
            clock = DeckClock(t = 100f, effMs = 200f, segment = DocSegment.Cross("ta", "tb")),
            transitionSpec = mm,
            colorResolver = colorResolver,
        )
        val morphState = evalTrav.textState as? DocumentTextRenderState.Morph
        assertTrue(morphState != null, "Expected DocumentTextRenderState.Morph for changing travelling text")
        assertFalse(morphState.interpolateLayoutSize, "Cross(A, B) travelling text MUST NOT interpolate layout @size")
    }

    @Test
    fun movableSpecimenOwnershipThroughABCBAndRetainedSurvivalAcrossColdSeek() {
        val mounts = AtomicInteger(0)
        val retainedAllocations = AtomicInteger(0)
        val observedRetainedObjects = mutableListOf<Any>()
        val observedRememberTokens = mutableListOf<Any>()

        class RetainedToken(val id: Int)
        class RememberToken

        val registry = deckSpecimens {
            specimen("live.counter") {
                content {
                    DisposableEffect(Unit) {
                        mounts.incrementAndGet()
                        onDispose { }
                    }
                    val token = retained("tok") {
                        RetainedToken(retainedAllocations.incrementAndGet())
                    }
                    val rememberToken = remember { RememberToken() }
                    observedRetainedObjects += token
                    observedRememberTokens += rememberToken
                    Box(Modifier.fillMaxSize().background(Color(0xFF14B8A6)))
                }
            }
        }

        val mm = TransitionSpec(type = TransitionType.MagicMove, durationMs = 120, curve = Curve.Linear)
        val doc = DeckDocument(
            id = "specimen-ownership-abc",
            stage = compactStage(320, 200),
            slides = listOf(
                SlideSpec(
                    id = "sA",
                    transition = mm,
                    nodes = listOf(
                        NodeSpec(
                            id = "hero",
                            frame = FrameSpec(x = 20.0, y = 20.0, w = 100.0, h = 80.0),
                            specimen = SpecimenPayload(ref = "live.counter"),
                        ),
                    ),
                ),
                SlideSpec(
                    id = "sB",
                    transition = mm,
                    nodes = listOf(
                        NodeSpec(
                            id = "hero",
                            frame = FrameSpec(x = 110.0, y = 50.0, w = 100.0, h = 80.0),
                            specimen = SpecimenPayload(ref = "live.counter"),
                        ),
                    ),
                ),
                SlideSpec(
                    id = "sC",
                    nodes = listOf(
                        NodeSpec(
                            id = "hero",
                            frame = FrameSpec(x = 200.0, y = 80.0, w = 100.0, h = 80.0),
                            specimen = SpecimenPayload(ref = "live.counter"),
                        ),
                    ),
                ),
            ),
        )

        val sharedSession = RenderSession.forPresent(documentProvider = { doc })
        val cfg = DocumentRenderConfig(baseDensity = 1f, specimens = registry, fontsDir = bundledFontsDirPath())

        // Live travel A -> B -> C -> B -> A: single mount (mounts == 1), identical rememberToken & retained!
        DocumentStageHarness(doc, initialConfig = cfg, existingSession = sharedSession).use { h ->
            assertEquals(1, mounts.get(), "Initial mount on A")
            val firstRetained = observedRetainedObjects.first()
            val firstRemember = observedRememberTokens.first()

            h.advanceToRest(AdvanceDirection.Forward, stepMs = 10L) // A -> B
            assertEquals(Storyboard.Index(1, 0), h.animatic.currentIndex)
            assertEquals(1, mounts.get(), "specimen must stay single instance across A -> B")

            h.advanceToRest(AdvanceDirection.Forward, stepMs = 10L) // B -> C
            assertEquals(Storyboard.Index(2, 0), h.animatic.currentIndex)
            assertEquals(1, mounts.get(), "specimen must stay single instance across A -> B -> C")

            h.advanceToRest(AdvanceDirection.Backward, stepMs = 10L) // C -> B
            assertEquals(Storyboard.Index(1, 0), h.animatic.currentIndex)
            assertEquals(1, mounts.get(), "specimen must stay single instance across C -> B")

            h.advanceToRest(AdvanceDirection.Backward, stepMs = 10L) // B -> A
            assertEquals(Storyboard.Index(0, 0), h.animatic.currentIndex)
            assertEquals(1, mounts.get(), "specimen must stay single instance across A -> B -> C -> B -> A")

            assertEquals(1, retainedAllocations.get(), "retained allocated exactly once")
            assertTrue(observedRetainedObjects.all { it === firstRetained }, "retained must be identical object throughout")
            assertTrue(observedRememberTokens.all { it === firstRemember }, "remember must stay identical object throughout live A->B->C->B->A")

            // In-mount cold seek (`rebuildScene = true`): remember MUST reset, while `retained` survives!
            observedRetainedObjects.clear()
            observedRememberTokens.clear()
            val crossIdx = h.crossFromStateIndex(0)
            h.seekSegment(crossIdx, 0.5f, rebuildScene = true)
            assertEquals(1, retainedAllocations.get(), "retained must survive in-mount cold seek")
            assertSame(firstRetained, observedRetainedObjects.last(), "in-mount cold seek receives same retained object")
            assertNotSame(firstRemember, observedRememberTokens.last(), "in-mount cold seek must reset remember state")
            val mountsAfterColdSeek = mounts.get()
            assertTrue(mountsAfterColdSeek >= 2, "In-mount cold seek must create a fresh composition")

            // Cold remount on SAME RenderSession: retained still survives, remember resets again!
            observedRetainedObjects.clear()
            observedRememberTokens.clear()
            DocumentStageHarness(
                doc,
                initialConfig = cfg,
                initialIndex = Storyboard.Index(2, 0),
                existingSession = sharedSession,
            ).use { remount ->
                remount.settle(3)
                assertEquals(1, retainedAllocations.get(), "retained must survive cold remount in same RenderSession")
                assertSame(firstRetained, observedRetainedObjects.first(), "cold remount in same session gets same retained object")
                assertNotSame(firstRemember, observedRememberTokens.first(), "cold remount must reset remember state")
                assertEquals(mountsAfterColdSeek + 1, mounts.get(), "Cold remount creates a new composition")
            }

            // Fresh RenderSession allocates a fresh retained object!
            observedRetainedObjects.clear()
            DocumentStageHarness(doc, initialConfig = cfg).use { fresh ->
                fresh.settle(3)
                assertEquals(2, retainedAllocations.get(), "Fresh RenderSession must start with empty retained")
                assertNotSame(firstRetained, observedRetainedObjects.first())
            }
        }
    }

    @Test
    fun specimenAcrossMagicMoveDissolveMagicMoveNeverRemountsOrDualInvokes() {
        // 4 slides: sA --magicMove--> sB --dissolve--> sC --magicMove--> sD, all with `hero` specimen.
        // - sA -> sB (magicMove): single mount (mounts == 1), maxConcurrent == 1.
        // - sB -> sC (dissolve): sC mounts ONCE (mounts == 2) and NEVER remounts upon settling on sC!
        // - sC -> sD (magicMove): reuses sC's live slot (mounts STAYS 2), maxConcurrent during sC->sD == 1.
        // - Under reducedMotion = true: sA -> sB never dual-invokes a shared movable slot.
        val mounts = AtomicInteger(0)
        val activeCount = AtomicInteger(0)
        val maxConcurrentDuringMove = AtomicInteger(0)
        val retainedAllocations = AtomicInteger(0)
        val observedRememberBySlide = mutableListOf<Any>()
        val observedRetained = mutableListOf<Any>()

        class RememberTag
        class RetainedTag(val id: Int)

        val registry = deckSpecimens {
            specimen("chain.spec") {
                content {
                    DisposableEffect(Unit) {
                        mounts.incrementAndGet()
                        val cur = activeCount.incrementAndGet()
                        maxConcurrentDuringMove.updateAndGet { maxOf(it, cur) }
                        onDispose { activeCount.decrementAndGet() }
                    }
                    val tok = remember { RememberTag() }
                    val ret = retained("r") { RetainedTag(retainedAllocations.incrementAndGet()) }
                    observedRememberBySlide += tok
                    observedRetained += ret
                    Box(Modifier.fillMaxSize().background(Color(0xFF0EA5E9)))
                }
            }
        }

        val mm = TransitionSpec(type = TransitionType.MagicMove, durationMs = 120, curve = Curve.Linear)
        val dis = TransitionSpec(type = TransitionType.Dissolve, durationMs = 120, curve = Curve.Linear)
        fun heroSlide(id: String, x: Double, transition: TransitionSpec? = null) = SlideSpec(
            id = id,
            transition = transition,
            nodes = listOf(
                NodeSpec(
                    id = "hero",
                    frame = FrameSpec(x = x, y = 30.0, w = 90.0, h = 70.0),
                    specimen = SpecimenPayload(ref = "chain.spec"),
                ),
            ),
        )
        val doc = DeckDocument(
            id = "specimen-mm-dissolve-mm",
            stage = compactStage(360, 180),
            slides = listOf(
                heroSlide("sA", 20.0, mm),
                heroSlide("sB", 100.0, dis),
                heroSlide("sC", 180.0, mm),
                heroSlide("sD", 250.0, null),
            ),
        )

        val cfg = DocumentRenderConfig(baseDensity = 1f, specimens = registry, fontsDir = bundledFontsDirPath())
        DocumentStageHarness(doc, initialConfig = cfg).use { h ->
            assertEquals(1, mounts.get(), "Initial mount on sA")
            val chain1Remember = observedRememberBySlide.last()
            val chain1Retained = observedRetained.last()

            // sA -> sB (magicMove): stays 1 mount, maxConcurrent == 1
            h.advanceToRest(AdvanceDirection.Forward, stepMs = 10L)
            assertEquals(Storyboard.Index(1, 0), h.animatic.currentIndex)
            assertEquals(1, mounts.get(), "sA -> sB magicMove must stay single mount")
            assertEquals(1, maxConcurrentDuringMove.get(), "sA -> sB must never dual-invoke")
            assertSame(chain1Remember, observedRememberBySlide.last(), "sA -> sB preserves remember")
            assertSame(chain1Retained, observedRetained.last(), "sA -> sB shares chain-1 retained")

            // sB -> sC (dissolve): sC mounts ONCE (mounts == 2) and MUST NOT remount when settling on sC!
            h.advanceToRest(AdvanceDirection.Forward, stepMs = 10L)
            h.settle(4)
            assertEquals(Storyboard.Index(2, 0), h.animatic.currentIndex)
            assertEquals(2, mounts.get(), "sB -> sC dissolve must mount sC ONCE and never remount at rest")
            val chain2Remember = observedRememberBySlide.last()
            val chain2Retained = observedRetained.last()
            assertNotSame(chain1Remember, chain2Remember, "sC starts a fresh chain after dissolve")
            assertNotSame(chain1Retained, chain2Retained, "sC..sD chain has its own retained scope distinct from sA..sB")

            // sC -> sD (magicMove): MUST reuse sC's live slot (mounts stays 2, maxConcurrent stays 1!)
            maxConcurrentDuringMove.set(activeCount.get())
            h.advanceToRest(AdvanceDirection.Forward, stepMs = 10L)
            h.settle(4)
            assertEquals(Storyboard.Index(3, 0), h.animatic.currentIndex)
            assertEquals(2, mounts.get(), "sC -> sD magicMove must reuse sC's live slot without remounting")
            assertEquals(1, maxConcurrentDuringMove.get(), "sC -> sD must never dual-invoke")
            assertSame(chain2Remember, observedRememberBySlide.last(), "sC -> sD preserves remember")
            assertSame(chain2Retained, observedRetained.last(), "sC -> sD shares chain-2 retained")
        }

        // Reduced motion = true: sA -> sB collapses magicMove to Dissolve; scenes MUST use standard
        // per-scene slots instead of dual-invoking the same movableContentOf slot!
        mounts.set(0)
        activeCount.set(0)
        val reducedCfg = DocumentRenderConfig(
            baseDensity = 1f,
            reducedMotion = true,
            specimens = registry,
            fontsDir = bundledFontsDirPath(),
        )
        DocumentStageHarness(doc, initialConfig = reducedCfg).use { h ->
            assertEquals(1, mounts.get(), "Reduced-motion initial mount on sA")
            h.advanceToRest(AdvanceDirection.Forward, stepMs = 10L)
            h.settle(4)
            assertEquals(Storyboard.Index(1, 0), h.animatic.currentIndex)
            assertEquals(2, mounts.get(), "Reduced-motion dissolve sA -> sB mounts sB once without remount or dual-invoke crash")
            assertEquals(1, activeCount.get(), "Exactly one active specimen remains at rest on sB")
        }
    }

    @Test
    fun travellingContainerWithSpecimenNeverDuplicatesAndAssetsPlaceholdersConnectorsLoupeRender() {
        // Create temp SVG + PNG assets under <tempDir>/assets-deck.assets/
        val tempDir = Files.createTempDirectory("ciacola-doc-assets-test").toFile()
        val assetsDir = File(tempDir, "assets-deck.assets").apply { mkdirs() }
        val svgFile = File(assetsDir, "badge.svg")
        svgFile.writeText(
            """<svg xmlns="http://www.w3.org/2000/svg" width="60" height="60" viewBox="0 0 60 60"><rect width="60" height="60" fill="#22C55E"/></svg>""",
        )

        val specimenActiveCount = AtomicInteger(0)
        val maxConcurrentSpecimens = AtomicInteger(0)
        val registry = deckSpecimens {
            specimen("nested.spec") {
                content {
                    DisposableEffect(Unit) {
                        val cur = specimenActiveCount.incrementAndGet()
                        maxConcurrentSpecimens.updateAndGet { maxOf(it, cur) }
                        onDispose { specimenActiveCount.decrementAndGet() }
                    }
                    Box(Modifier.fillMaxSize().background(Color(0xFF0EA5E9)))
                }
            }
        }

        val doc = DeckDocument(
            id = "assets-deck",
            stage = StageSize(1920, 1080),
            slides = listOf(
                SlideSpec(
                    id = "s1",
                    background = Fill.Gradient(
                        GradientSpec(
                            type = GradientType.Linear,
                            angle = 45.0,
                            stops = listOf(
                                GradientStop(at = 0.0, color = "#0B1020"),
                                GradientStop(at = 1.0, color = "#1E293B"),
                            ),
                        ),
                    ),
                    transition = TransitionSpec(type = TransitionType.MagicMove, durationMs = 120, curve = Curve.Linear),
                    nodes = listOf(
                        // Travelling container holding a sized specimen
                        NodeSpec(
                            id = "card",
                            frame = FrameSpec(x = 80.0, y = 80.0, w = 320.0, h = 220.0),
                            stack = ContainerPayload(
                                surface = SurfaceSpec(fill = Fill.Solid("#1F2937"), cornerRadius = 16.0),
                                children = listOf(
                                    NodeSpec(
                                        id = "nestedSpec",
                                        frame = FrameSpec(x = 20.0, y = 20.0, w = 200.0, h = 140.0),
                                        specimen = SpecimenPayload(ref = "nested.spec"),
                                    ),
                                ),
                            ),
                        ),
                        // SVG image asset
                        NodeSpec(
                            id = "svg-img",
                            frame = FrameSpec(x = 460.0, y = 80.0, w = 120.0, h = 120.0),
                            image = ImagePayload(src = "badge.svg", fit = "contain"),
                        ),
                        // Missing specimen ref + todo.video placeholder (must render visibly, never crash!)
                        NodeSpec(
                            id = "missing-spec",
                            frame = FrameSpec(x = 640.0, y = 80.0, w = 240.0, h = 120.0),
                            specimen = SpecimenPayload(ref = "nonexistent.ref"),
                        ),
                        NodeSpec(
                            id = "todo-vid",
                            frame = FrameSpec(x = 920.0, y = 80.0, w = 240.0, h = 120.0),
                            specimen = SpecimenPayload(ref = "todo.video"),
                        ),
                        // Connector between card and svg-img
                        NodeSpec(
                            id = "arrow",
                            connector = ConnectorPayload(
                                from = "card",
                                to = "svg-img",
                                style = "curvedArrow",
                                stroke = StrokeSpec(color = "#38BDF8", width = 6.0),
                            ),
                        ),
                        // Loupe targeting svg-img
                        NodeSpec(
                            id = "mag",
                            frame = FrameSpec(x = 460.0, y = 320.0, w = 260.0, h = 180.0),
                            loupe = LoupePayload(target = "svg-img", zoom = 2.0),
                        ),
                    ),
                ),
                SlideSpec(
                    id = "s2",
                    background = Fill.Gradient(
                        GradientSpec(
                            type = GradientType.Radial,
                            center = Vec2(0.5, 0.5),
                            radius = 0.7,
                            stops = listOf(
                                GradientStop(at = 0.0, color = "#1E293B"),
                                GradientStop(at = 1.0, color = "#090D16"),
                            ),
                        ),
                    ),
                    nodes = listOf(
                        NodeSpec(
                            id = "card",
                            frame = FrameSpec(x = 420.0, y = 160.0, w = 320.0, h = 220.0),
                            stack = ContainerPayload(
                                surface = SurfaceSpec(fill = Fill.Solid("#1F2937"), cornerRadius = 16.0),
                                children = listOf(
                                    NodeSpec(
                                        id = "nestedSpec",
                                        frame = FrameSpec(x = 20.0, y = 20.0, w = 200.0, h = 140.0),
                                        specimen = SpecimenPayload(ref = "nested.spec"),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )

        try {
            val cfg = DocumentRenderConfig(
                baseDensity = 2f,
                specimens = registry,
                assetsRootDir = tempDir.absolutePath,
                fontsDir = bundledFontsDirPath(),
            )
            DocumentStageHarness(doc, initialConfig = cfg).use { h ->
                val rest1 = h.renderCurrent()
                // SVG green square rendered at (460..580, 80..200) on 1920x1080 stage at Density(2f)!
                assertColorNear(rest1.pixelColor(520, 140), 0x22, 0xC5, 0x5E, tolerance = 12, label = "SVG asset rendered on 1920x1080 Density(2f) stage")
                // Missing-ref placeholder drew visible surface (different from dark gradient background)
                assertTrue(rest1.countNonMatchingPixels(0x0B, 0x10, 0x20, tolerance = 8) > 1000)

                // Advance s1 -> s2 via Magic Move: travelling container holding nested specimen must NEVER duplicate it!
                h.advanceToRest(AdvanceDirection.Forward, stepMs = 15L)
                assertEquals(Storyboard.Index(1, 0), h.animatic.currentIndex)
                assertEquals(
                    1,
                    maxConcurrentSpecimens.get(),
                    "travelling container holding a specimen must never produce a second live copy",
                )
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
