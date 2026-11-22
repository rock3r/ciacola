package dev.sebastiano.ciacola.document.render

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import dev.bnorm.storyboard.AdvanceDirection
import dev.bnorm.storyboard.Storyboard
import dev.bnorm.storyboard.easel.Animatic
import dev.bnorm.storyboard.easel.Easel
import dev.bnorm.storyboard.easel.SceneMode
import dev.bnorm.storyboard.easel.rememberAnimatic
import dev.sebastiano.ciacola.document.AutoAdvanceController
import dev.sebastiano.ciacola.document.BeatSpec
import dev.sebastiano.ciacola.document.Build
import dev.sebastiano.ciacola.document.CodePayload
import dev.sebastiano.ciacola.document.ContainerPayload
import dev.sebastiano.ciacola.document.Curve
import dev.sebastiano.ciacola.document.DeckClock
import dev.sebastiano.ciacola.document.DeckDocument
import dev.sebastiano.ciacola.document.DeckDocumentCodec
import dev.sebastiano.ciacola.document.DeckDocumentStore
import dev.sebastiano.ciacola.document.DeckThemeSpec
import dev.sebastiano.ciacola.document.DeliveryBy
import dev.sebastiano.ciacola.document.DocSegment
import dev.sebastiano.ciacola.document.Fill
import dev.sebastiano.ciacola.document.FrameSpec
import dev.sebastiano.ciacola.document.NodeSpec
import dev.sebastiano.ciacola.document.PaddingSpec
import dev.sebastiano.ciacola.document.RenderSession
import dev.sebastiano.ciacola.document.RichTextValue
import dev.sebastiano.ciacola.document.SegmentPlanner
import dev.sebastiano.ciacola.document.ShapeGeometry
import dev.sebastiano.ciacola.document.ShapePayload
import dev.sebastiano.ciacola.document.SlideSpec
import dev.sebastiano.ciacola.document.SpecimenPayload
import dev.sebastiano.ciacola.document.StrokeSpec
import dev.sebastiano.ciacola.document.SurfaceSpec
import dev.sebastiano.ciacola.document.TextPayload
import dev.sebastiano.ciacola.document.ThemeMotionDefaults
import dev.sebastiano.ciacola.document.TransitionSpec
import dev.sebastiano.ciacola.document.TransitionType
import dev.sebastiano.ciacola.document.deckSpecimens
import dev.sebastiano.ciacola.document.mirroredWarpWindow
import dev.sebastiano.ciacola.document.warpSchedule
import dev.sebastiano.ciacola.effects.v2.EffectArgs
import dev.sebastiano.ciacola.effects.v2.EffectBuildKind
import dev.sebastiano.ciacola.effects.v2.EffectEvalContext
import dev.sebastiano.ciacola.effects.v2.LayerEffects
import dev.sebastiano.ciacola.inspector.LocalStageInspectionRegistry
import dev.sebastiano.ciacola.inspector.StageInspectionRegistry
import dev.sebastiano.ciacola.presenter.StoryboardSpeakerNotesRegistry
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Verifies document motion timing, path independence, live memoization, auto-advance, and Warp code rendering:
 * - Duration authority (`effectiveDurationNanos == plan.segmentMs * 1_000_000L`),
 *   path independence (forward play, scrub, backward scrub, mid-flight reversal, resting jump),
 *   structure-key memoization (live edit without remounting), auto-advance policy, speaker notes,
 *   and video hold durations.
 * - `WarpCodeAdapter` fixed reserved outer box (0 px sibling shift in Row & Column),
 *   exact duration parity across 10 ms / 60 ms / delayed / reduced-motion / unchanged beats,
 *   per-pair-family pixel equality, and zero coupling to legacy override locals.
 */
@OptIn(ExperimentalComposeUiApi::class)
class DocumentMotionAndWarpTest {

    @Test
    fun durationAgreementAndPathIndependenceAcrossTextShapePathAndSpecimen() {
        val registry = deckSpecimens {
            specimen("demo.meter") {
                val fill = float("fill", default = 0.2f, range = 0f..1f)
                val sweep = track("sweep", beat = 1, durationMs = 240, delayMs = 40, curve = Curve.Linear)
                content {
                    val f = fill()
                    val t = sweep()
                    val mix = ((f + t) * 0.5f).coerceIn(0f, 1f)
                    val color = lerp(Color(0xFF1E3A8A), Color(0xFF10B981), mix)
                    Box(Modifier.fillMaxSize().background(color))
                }
            }
        }

        val doc = DeckDocument(
            id = "motion-multi-node",
            stage = compactStage(420, 260),
            slides = listOf(
                SlideSpec(
                    id = "s0",
                    background = Fill.Solid("#0B0F17"),
                    beats = listOf(BeatSpec(notes = "Intro slide")),
                    transition = TransitionSpec(type = TransitionType.Dissolve, durationMs = 240, curve = Curve.Linear),
                ),
                SlideSpec(
                    id = "s1",
                    background = Fill.Solid("#10141C"),
                    beats = listOf(
                        BeatSpec(notes = "Beat zero entrance"),
                        BeatSpec(notes = "Beat one changes", autoAdvanceMs = 250),
                    ),
                    nodes = listOf(
                        NodeSpec(
                            id = "headline",
                            frame = FrameSpec(x = 24.0, y = 20.0, w = 360.0),
                            text = TextPayload(
                                value = RichTextValue.Plain("Hello World"),
                                size = 28.0,
                                color = "#F8FAFC",
                            ),
                            builds = listOf(
                                Build.Enter(
                                    beat = 0,
                                    effect = "fadeMove",
                                    direction = "up",
                                    durationMs = 200,
                                    curve = Curve.Linear,
                                ),
                                Build.Change(
                                    beat = 1,
                                    durationMs = 300,
                                    curve = Curve.Linear,
                                    set = buildJsonObject {
                                        putJsonObject("text") {
                                            put("value", "Hello Ciacola")
                                        }
                                    },
                                ),
                            ),
                        ),
                        NodeSpec(
                            id = "mover",
                            frame = FrameSpec(x = 24.0, y = 100.0, w = 64.0, h = 48.0),
                            shape = ShapePayload(
                                geometry = ShapeGeometry.Rect,
                                fill = Fill.Solid("#EF4444"),
                                stroke = StrokeSpec(color = "#FFFFFF", width = 3.0),
                                cornerRadius = 8.0,
                            ),
                            builds = listOf(
                                Build.Enter(beat = 0, effect = "lineDraw", durationMs = 200, curve = Curve.Linear),
                                Build.Change(
                                    beat = 1,
                                    durationMs = 300,
                                    curve = Curve.Linear,
                                    path = "M 0 0 L 140 0 L 140 40",
                                    // Lint-valid motion path WITH set.frame matching endpoint!
                                    set = buildJsonObject {
                                        putJsonObject("frame") {
                                            put("x", 164.0)
                                            put("y", 140.0)
                                        }
                                        putJsonObject("shape") {
                                            put("fill", "#3B82F6")
                                        }
                                    },
                                ),
                            ),
                        ),
                        NodeSpec(
                            id = "meter",
                            frame = FrameSpec(x = 260.0, y = 100.0, w = 120.0, h = 90.0),
                            specimen = SpecimenPayload(
                                ref = "demo.meter",
                                params = mapOf("fill" to JsonPrimitive(0.2)),
                            ),
                            builds = listOf(
                                Build.Change(
                                    beat = 1,
                                    durationMs = 300,
                                    curve = Curve.Linear,
                                    set = buildJsonObject {
                                        putJsonObject("specimen") {
                                            putJsonObject("params") {
                                                put("fill", 0.9)
                                            }
                                        }
                                    },
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )

        // Motion-path fixture must be 100% lint-valid (endpoint matches set.frame)!
        val lintIssues = DeckDocumentCodec.validate(document = doc, specimens = registry)
        assertTrue(
            lintIssues.none { it.code == "MOTION_PATH_ENDPOINT_MISMATCH" },
            "Motion-path fixture must have zero MOTION_PATH_ENDPOINT_MISMATCH issues, got $lintIssues",
        )

        val expectedCrossMs = SegmentPlanner.planTransition(doc, "s0", "s1", specimens = registry).segmentMs
        val expectedEntranceMs = SegmentPlanner.planSegment(doc, "s1", beat = 0, specimens = registry).segmentMs
        val expectedBeat1Ms = SegmentPlanner.planSegment(doc, "s1", beat = 1, specimens = registry).segmentMs
        assertEquals(240, expectedCrossMs)
        assertEquals(200, expectedEntranceMs)
        assertEquals(300, expectedBeat1Ms)

        // Verify speaker notes & hold duration helpers
        val sessionForMeta = RenderSession.forPresent(documentProvider = { doc })
        val sbForMeta = buildDocumentStoryboard(sessionForMeta, DocumentRenderConfig(baseDensity = 1f, specimens = registry))
        val notes = StoryboardSpeakerNotesRegistry.forStoryboard(sbForMeta)
        assertEquals("Beat zero entrance", notes?.resolve(sbForMeta, Storyboard.Index(1, 0))?.beatNote)
        assertEquals("Beat one changes", notes?.resolve(sbForMeta, Storyboard.Index(1, 1))?.beatNote)
        val holdProvider = holdDurationForState(sessionForMeta, defaultHoldMs = 450L)
        assertEquals(450.milliseconds, holdProvider(Storyboard.Index(1, 0)))
        assertEquals(250.milliseconds, holdProvider(Storyboard.Index(1, 1)))

        val cfg = DocumentRenderConfig(baseDensity = 1f, specimens = registry, fontsDir = bundledFontsDirPath())
        DocumentStageHarness(doc, initialConfig = cfg, initialIndex = Storyboard.Index(1, 0)).use { h ->
            val crossIdx = h.crossFromStateIndex(0)
            val beat0Idx = h.valueStateIndex(1, 0)
            val beat1Idx = h.valueStateIndex(1, 1)
            val entranceStartIdx = h.entranceFromStateIndex(1)

            // 0. Cross-slide duration authority (forward & backward)
            h.jumpAndSettle(Storyboard.Index(0, 0))
            val (_, crossFwdEff) = h.advanceAndCaptureAt(
                direction = AdvanceDirection.Forward,
                targetCanonicalFraction = 0.5f,
                stepMs = 10L,
                matchSegment = { f, t -> f == crossIdx && t == crossIdx + 1 },
            )
            assertEquals(expectedCrossMs * 1_000_000L, crossFwdEff, "Cross(s0, s1) forward duration mismatch")
            val (_, crossBwdEff) = h.advanceAndCaptureAt(
                direction = AdvanceDirection.Backward,
                targetCanonicalFraction = 0.5f,
                stepMs = 10L,
                matchSegment = { f, t -> t == crossIdx && f == crossIdx + 1 },
            )
            assertEquals(expectedCrossMs * 1_000_000L, crossBwdEff, "Cross(s0, s1) backward duration mismatch")

            // 1. Check Entrance duration (Start -> Value(0)) via seek
            h.seekSegment(entranceStartIdx, 0.5f)
            assertEquals(expectedEntranceMs * 1_000_000L, h.animatic.effectiveDurationNanos(), "Entrance effectiveDurationNanos mismatch")

            // 2. Check beat 0 -> beat 1 duration & capture forward play midpoint (canonical 0.5)
            h.jumpAndSettle(Storyboard.Index(1, 0))
            val restBeat0 = h.renderCurrent()
            val (fwdMid, fwdEffNanos) = h.advanceAndCaptureAt(
                direction = AdvanceDirection.Forward,
                targetCanonicalFraction = 0.5f,
                stepMs = 10L,
            )
            assertEquals(expectedBeat1Ms * 1_000_000L, fwdEffNanos, "Beat 0->1 forward effectiveDurationNanos mismatch")
            val restBeat1ByPlay = h.renderCurrent()

            // Verify motion path applies SINGLE Δ (no double 2Δ translation!).
            // Path "M 0 0 L 140 0 L 140 40" has total length 180; at p = 0.5 arc length = 90 -> (dx=90, dy=0).
            // Single-Δ box sits at x = 114..178, y = 100..148 (center 146, 124).
            // Double-Δ buggy box would sit at x = 184..248, y = 120..168 (center 216, 144).
            assertColorNear(
                fwdMid.pixelColor(146, 124),
                149,
                99,
                157,
                tolerance = 18,
                label = "Mover at p=0.5 must sit at single-Δ motion-path position (146, 124)",
            )
            assertColorNear(
                fwdMid.pixelColor(216, 144),
                0x10,
                0x14,
                0x1C,
                tolerance = 6,
                label = "Double-Δ overshoot position (216, 144) must stay slide background",
            )
            assertColorNear(
                restBeat1ByPlay.pixelColor(196, 164),
                0x3B,
                0x82,
                0xF6,
                tolerance = 10,
                label = "Mover at rest beat 1 must land cleanly at (164..228, 140..188)",
            )

            // 3. Scrub (cold seek) AND backward in-place scrub to exact canonical 0.5 must match forward play!
            val scrubMid = h.seekSegment(beat0Idx, 0.5f, rebuildScene = true)
            assertFramesEqual(fwdMid, scrubMid, "forward play != cold seek at canonical p=0.5")
            h.seekSegment(beat0Idx, 0.8f, rebuildScene = false)
            val bwdScrubMid = h.seekSegment(beat0Idx, 0.5f, rebuildScene = false)
            assertFramesEqual(fwdMid, bwdScrubMid, "forward play != backward in-place scrub at canonical p=0.5")

            // 4. Backward play (beat 1 -> beat 0) at canonical 0.5 must match forward play AND duration!
            h.jumpAndSettle(Storyboard.Index(1, 1))
            val (bwdMid, bwdEffNanos) = h.advanceAndCaptureAt(
                direction = AdvanceDirection.Backward,
                targetCanonicalFraction = 0.5f,
                stepMs = 10L,
            )
            assertEquals(expectedBeat1Ms * 1_000_000L, bwdEffNanos, "Beat 1->0 backward effectiveDurationNanos mismatch")
            assertFramesEqual(fwdMid, bwdMid, "forward play != backward play at canonical p=0.5")

            // 5. Mid-flight reversal (Forward -> reverse at 0.7 -> sample canonical 0.5 on way back)
            h.jumpAndSettle(Storyboard.Index(1, 0))
            h.onUiThread {
                val revJob1 = h.scope.launch { h.animatic.advance(AdvanceDirection.Forward) }
                var ticks = 0
                while (!revJob1.isCompleted && ticks++ < 150) {
                    h.renderStep(10_000_000L)
                    if (!h.animatic.scrubSettling && h.animatic.activeFraction >= 0.72f) break
                }
            }
            val (revMid, _) = h.advanceAndCaptureAt(
                direction = AdvanceDirection.Backward,
                targetCanonicalFraction = 0.5f,
                stepMs = 10L,
            )
            assertFramesEqual(fwdMid, revMid, "mid-flight reversal != forward play at canonical p=0.5")

            // 5b. Backward ← → ← reversal starting from resting beat 1
            h.jumpAndSettle(Storyboard.Index(1, 1))
            h.onUiThread {
                val bLeg1 = h.scope.launch { h.animatic.advance(AdvanceDirection.Backward) }
                var ticks = 0
                while (!bLeg1.isCompleted && ticks++ < 150) {
                    h.renderStep(10_000_000L)
                    if (!h.animatic.scrubSettling && h.animatic.activeFraction >= 0.35f) break
                }
                val bLeg2 = h.scope.launch { h.animatic.advance(AdvanceDirection.Forward) }
                ticks = 0
                while (!bLeg2.isCompleted && ticks++ < 150) {
                    h.renderStep(10_000_000L)
                    if (!h.animatic.scrubSettling && h.animatic.activeFraction <= 0.15f) break
                }
            }
            val (bwdRevMid, bwdRevEffNanos) = h.advanceAndCaptureAt(
                direction = AdvanceDirection.Backward,
                targetCanonicalFraction = 0.5f,
                stepMs = 10L,
            )
            assertEquals(expectedBeat1Ms * 1_000_000L, bwdRevEffNanos, "← → ← reversal duration mismatch")
            assertFramesEqual(fwdMid, bwdRevMid, "← → ← reversal != forward play at canonical p=0.5")

            // 6. Resting beat reached by jumpTo equals resting beat reached by play!
            val restBeat0ByJump = h.jumpAndSettle(Storyboard.Index(1, 0))
            val restBeat1ByJump = h.jumpAndSettle(Storyboard.Index(1, 1))
            assertFramesEqual(restBeat0, restBeat0ByJump, "resting beat 0 jumpTo mismatch")
            assertFramesEqual(restBeat1ByPlay, restBeat1ByJump, "resting beat 1 jumpTo mismatch")

            // 7. Reduced motion mode: duration equals reduced-motion plan & forward == seek == backward == reversal!
            h.config = cfg.copy(reducedMotion = true)
            h.settle(3)
            val reducedBeat1Ms = SegmentPlanner.planSegment(doc, "s1", beat = 1, reducedMotion = true, specimens = registry).segmentMs
            h.jumpAndSettle(Storyboard.Index(1, 0))
            val (redFwdMid, redEffNanos) = h.advanceAndCaptureAt(
                direction = AdvanceDirection.Forward,
                targetCanonicalFraction = 0.5f,
                stepMs = 5L,
            )
            assertEquals(reducedBeat1Ms * 1_000_000L, redEffNanos, "reduced-motion effectiveDurationNanos mismatch")
            val redSeekMid = h.seekSegment(beat0Idx, 0.5f, rebuildScene = true)
            assertFramesEqual(redFwdMid, redSeekMid, "reduced-motion forward != seek")
            h.jumpAndSettle(Storyboard.Index(1, 1))
            val (redBwdMid, redBwdEff) = h.advanceAndCaptureAt(
                direction = AdvanceDirection.Backward,
                targetCanonicalFraction = 0.5f,
                stepMs = 5L,
            )
            assertEquals(reducedBeat1Ms * 1_000_000L, redBwdEff, "reduced-motion backward duration mismatch")
            assertFramesEqual(redFwdMid, redBwdMid, "reduced-motion forward != backward")
            assertTrue(beat1Idx > beat0Idx)
        }
    }

    @Test
    fun multiChangeSequentialBuildsKeynoteDefaultCurvesAndEffectBridging() {
        // 1. Two sequential `change` builds on the SAME node on the SAME beat MUST
        //    evaluate step-by-step against (stateBeforeBuild_k, stateAfterBuild_k), NOT jump to final beat state!
        val seqDoc = DeckDocument(
            id = "multi-change",
            stage = compactStage(240, 180),
            slides = listOf(
                SlideSpec(
                    id = "s0",
                    background = Fill.Solid("#0F172A"),
                    beats = listOf(BeatSpec(), BeatSpec()),
                    nodes = listOf(
                        NodeSpec(
                            id = "stepper",
                            frame = FrameSpec(x = 20.0, y = 20.0, w = 40.0, h = 40.0),
                            shape = ShapePayload(geometry = ShapeGeometry.Rect, fill = Fill.Solid("#EF4444")),
                            builds = listOf(
                                Build.Change(
                                    beat = 1,
                                    delayMs = 0,
                                    durationMs = 100,
                                    curve = Curve.Linear,
                                    set = buildJsonObject {
                                        putJsonObject("frame") { put("x", 120.0) }
                                    },
                                ),
                                Build.Change(
                                    beat = 1,
                                    delayMs = 0,
                                    durationMs = 100,
                                    curve = Curve.Linear,
                                    set = buildJsonObject {
                                        putJsonObject("frame") { put("y", 100.0) }
                                        putJsonObject("shape") { put("fill", "#22C55E") }
                                    },
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val seqPlan = SegmentPlanner.planSegment(seqDoc, "s0", beat = 1)
        assertEquals(200, seqPlan.segmentMs)

        DocumentStageHarness(seqDoc).use { h ->
            val beat0Idx = h.valueStateIndex(0, 0)
            // At p = 0.25 (t = 50 ms): midpoint of b0 [0..100], BEFORE b1 [100..200] starts!
            // Box MUST be at x = 70..110, y = 20..60 and pure red #EF4444 (NOT jumped to x=120 or green!).
            val frame25 = h.seekSegment(beat0Idx, 0.25f, rebuildScene = true)
            assertColorNear(frame25.pixelColor(90, 40), 0xEF, 0x44, 0x44, tolerance = 8, label = "t=50ms b0 midpoint x=70..110 red")
            assertColorNear(frame25.pixelColor(140, 40), 0x0F, 0x17, 0x2A, tolerance = 6, label = "t=50ms must NOT have jumped to b0 end x=120")
            assertColorNear(frame25.pixelColor(140, 120), 0x0F, 0x17, 0x2A, tolerance = 6, label = "t=50ms must NOT be at b1 end")

            // At p = 0.75 (t = 150 ms): AFTER b0 ended (x = 120), midpoint of b1 (y = 60, fill = mid red/green in Oklab)!
            val expectedMidColor = androidx.compose.ui.graphics.lerp(Color(0xFFEF4444), Color(0xFF22C55E), 0.5f)
            val frame75 = h.seekSegment(beat0Idx, 0.75f, rebuildScene = true)
            assertColorNear(
                frame75.pixelColor(140, 80),
                (expectedMidColor.red * 255f).toInt(),
                (expectedMidColor.green * 255f).toInt(),
                (expectedMidColor.blue * 255f).toInt(),
                tolerance = 10,
                label = "t=150ms b1 midpoint x=120, y=60..100 mid-color",
            )
            assertColorNear(frame75.pixelColor(90, 40), 0x0F, 0x17, 0x2A, tolerance = 6, label = "t=150ms old b0 position is clear")

            // At rest beat 1: box sits at (120..160, 100..140) pure green #22C55E!
            val rest1 = h.jumpAndSettle(Storyboard.Index(0, 1))
            assertColorNear(rest1.pixelColor(140, 120), 0x22, 0xC5, 0x5E, tolerance = 8, label = "rest beat 1 green at (120, 100)")
        }

        // 2. Keynote built-in curve preservation, jiggle amplitude, iris center,
        //    and EffectTier.Path (lineDraw) fallback to dissolve on unsupported NodeKind.Text!
        val effectsDoc = DeckDocument(
            id = "effects-curve-bridging",
            stage = compactStage(400, 240),
            themes = mapOf("t" to DeckThemeSpec(motion = ThemeMotionDefaults(curve = Curve.Linear, durationMs = 400))),
            defaultTheme = "t",
            slides = listOf(
                SlideSpec(
                    id = "s0",
                    beats = listOf(BeatSpec(), BeatSpec()),
                    nodes = listOf(
                        NodeSpec(
                            id = "fm-default",
                            frame = FrameSpec(x = 20.0, y = 20.0, w = 80.0, h = 40.0),
                            shape = ShapePayload(geometry = ShapeGeometry.Rect, fill = Fill.Solid("#FFFFFF")),
                            builds = listOf(Build.Enter(beat = 0, effect = "fadeMove", direction = "up", durationMs = 400)),
                        ),
                        NodeSpec(
                            id = "fm-linear",
                            frame = FrameSpec(x = 120.0, y = 20.0, w = 80.0, h = 40.0),
                            shape = ShapePayload(geometry = ShapeGeometry.Rect, fill = Fill.Solid("#FFFFFF")),
                            builds = listOf(Build.Enter(beat = 0, effect = "fadeMove", direction = "up", durationMs = 400, curve = Curve.Linear)),
                        ),
                        NodeSpec(
                            id = "jiggler",
                            frame = FrameSpec(x = 220.0, y = 20.0, w = 80.0, h = 40.0),
                            shape = ShapePayload(geometry = ShapeGeometry.Rect, fill = Fill.Solid("#FFFFFF")),
                            builds = listOf(
                                Build.Emphasis(
                                    beat = 1,
                                    effect = "jiggle",
                                    durationMs = 400,
                                    args = mapOf("amplitude" to JsonPrimitive(20.0)),
                                ),
                            ),
                        ),
                        NodeSpec(
                            id = "linedraw-text",
                            frame = FrameSpec(x = 20.0, y = 100.0, w = 160.0, h = 40.0),
                            text = TextPayload(value = RichTextValue.Plain("Fallback"), size = 20.0, color = "#FFFFFF"),
                            builds = listOf(Build.Enter(beat = 0, effect = "lineDraw", durationMs = 400, curve = Curve.Linear)),
                        ),
                    ),
                ),
            ),
        )
        val efPlan = SegmentPlanner.planSegment(effectsDoc, "s0", beat = 0)
        val clockQuarter = DeckClock(DocSegment.Entrance("s0"), t = 100f, effMs = 400f)
        val clockHalf = DeckClock(DocSegment.Entrance("s0"), t = 200f, effMs = 400f)
        val stage = Size(400f, 240f)
        val slide = effectsDoc.slides[0]
        val theme = resolveDeckTheme(effectsDoc, "t")
        val colorResolver = DocumentColorResolver(themeSpec = effectsDoc.themes["t"], baseTheme = theme)

        val evalDefault = DocumentMotionEvaluator.evaluateNode(
            rawNode = slide.nodes[0],
            slide = slide,
            clock = clockQuarter,
            segmentPlan = efPlan,
            stageSize = stage,
            densityValue = 1f,
            colorResolver = colorResolver,
            reducedMotion = false,
        )
        val evalLinear = DocumentMotionEvaluator.evaluateNode(
            rawNode = slide.nodes[1],
            slide = slide,
            clock = clockQuarter,
            segmentPlan = efPlan,
            stageSize = stage,
            densityValue = 1f,
            colorResolver = colorResolver,
            reducedMotion = false,
        )
        val expectedKeynoteFadeMove = LayerEffects.evaluateFadeMove(
            kind = EffectBuildKind.Enter,
            progress = 0.25f,
            context = EffectEvalContext(
                nodeBounds = androidx.compose.ui.geometry.Rect(0f, 0f, 80f, 40f),
                stageSize = stage,
                density = 1f,
                reducedMotion = false,
            ),
            args = buildEffectArgs(emptyMap(), direction = "up", colorResolver = colorResolver),
            curve = null,
        )
        assertTrue(
            abs(evalDefault.layerState.alpha - expectedKeynoteFadeMove.alpha) < 1e-3f &&
                abs(evalDefault.layerState.translationY - expectedKeynoteFadeMove.translationY) < 1e-2f,
            "omitted curve on fadeMove must preserve Apple Keynote dual curves " +
                "(expected alpha=${expectedKeynoteFadeMove.alpha}/dy=${expectedKeynoteFadeMove.translationY}, " +
                "got alpha=${evalDefault.layerState.alpha}/dy=${evalDefault.layerState.translationY})",
        )
        assertTrue(
            abs(evalDefault.layerState.translationY - evalLinear.layerState.translationY) > 2f,
            "omitted curve fadeMove (${evalDefault.layerState.translationY}) must differ from explicit Linear (${evalLinear.layerState.translationY})",
        )

        // Evaluate jiggle on beat 1 at p = 0.3125 (t = 125ms of 400ms) where sin(2*pi*4*p) = +1.0 (peak!)
        val efPlanBeat1 = SegmentPlanner.planSegment(effectsDoc, "s0", beat = 1)
        val clockJigglePeak = DeckClock(DocSegment.Beat("s0", 0), t = 125f, effMs = 400f)
        val evalJiggle = DocumentMotionEvaluator.evaluateNode(
            rawNode = slide.nodes[2],
            slide = slide,
            clock = clockJigglePeak,
            segmentPlan = efPlanBeat1,
            stageSize = stage,
            densityValue = 1f,
            colorResolver = colorResolver,
            reducedMotion = false,
        )
        val expectedDefaultJiggle = LayerEffects.evaluateJiggle(
            kind = EffectBuildKind.Emphasis,
            progress = 0.3125f,
            context = EffectEvalContext(
                nodeBounds = androidx.compose.ui.geometry.Rect(0f, 0f, 80f, 40f),
                stageSize = stage,
                density = 1f,
                reducedMotion = false,
            ),
            args = EffectArgs.Empty,
        )
        assertTrue(
            abs(evalJiggle.layerState.rotationZ) > abs(expectedDefaultJiggle.rotationZ) * 3f,
            "jiggle amplitude=20 must scale rotationZ above default amplitude=4.5 " +
                "(got ${evalJiggle.layerState.rotationZ} vs default ${expectedDefaultJiggle.rotationZ})",
        )

        val irisArgs = buildEffectArgs(
            rawArgs = mapOf("centerX" to JsonPrimitive(0.2), "centerY" to JsonPrimitive(0.8)),
            direction = "in",
            colorResolver = colorResolver,
        )
        assertEquals(
            Offset(0.2f, 0.8f),
            irisArgs.offset("center", Offset.Zero),
            "iris centerX/centerY must map to EffectArgs.center",
        )

        val evalLineDrawText = DocumentMotionEvaluator.evaluateNode(
            rawNode = slide.nodes[3],
            slide = slide,
            clock = clockHalf,
            segmentPlan = efPlan,
            stageSize = stage,
            densityValue = 1f,
            colorResolver = colorResolver,
            reducedMotion = false,
        )
        assertTrue(
            evalLineDrawText.layerState.alpha in 0.45f..0.55f,
            "EffectTier.Path (lineDraw) on NodeKind.Text must fall back to dissolve (~0.5 alpha at p=0.5), got ${evalLineDrawText.layerState.alpha}",
        )
    }

    @Test
    fun structureKeyMemoizationRecomposesInPlaceWithoutRemountingSpecimen() {
        val mountCount = AtomicInteger(0)
        val registry = deckSpecimens {
            specimen("stateful.box") {
                val hue = float("hue", default = 0f, range = 0f..1f)
                content {
                    DisposableEffect(Unit) {
                        mountCount.incrementAndGet()
                        onDispose { }
                    }
                    val c = lerp(Color(0xFFDC2626), Color(0xFF16A34A), hue())
                    Box(Modifier.fillMaxSize().background(c))
                }
            }
        }

        val initialDoc = DeckDocument(
            id = "structure-key-memo",
            stage = compactStage(200, 140),
            themes = mapOf(
                "dark" to DeckThemeSpec(preset = "stageDark", colors = mapOf("accent" to "#FF0000")),
                "light" to DeckThemeSpec(preset = "stageLight", colors = mapOf("accent" to "#00FF00")),
            ),
            defaultTheme = "dark",
            slides = listOf(
                SlideSpec(
                    id = "s1",
                    beats = listOf(BeatSpec()),
                    nodes = listOf(
                        NodeSpec(
                            id = "spec1",
                            frame = FrameSpec(x = 20.0, y = 20.0, w = 100.0, h = 80.0),
                            specimen = SpecimenPayload(
                                ref = "stateful.box",
                                params = mapOf("hue" to JsonPrimitive(0.0)),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val cfg = DocumentRenderConfig(baseDensity = 1f, specimens = registry, fontsDir = bundledFontsDirPath())
        DocumentStageHarness(initialDoc, initialConfig = cfg).use { h ->
            val sbInitial = h.storyboard
            assertEquals(1, mountCount.get(), "Initial mount should mount specimen once")
            val frameBefore = h.renderCurrent()
            assertColorNear(frameBefore.pixelColor(60, 60), 0xDC, 0x26, 0x26, label = "before param edit")

            // Property edit (same slideId & beatCount): MUST keep exact same Storyboard & NOT remount specimen!
            h.onUiThread {
                h.store.edit("Update specimen hue") { d ->
                    d.copy(
                        slides = listOf(
                            d.slides[0].copy(
                                nodes = listOf(
                                    d.slides[0].nodes[0].copy(
                                        specimen = SpecimenPayload(
                                            ref = "stateful.box",
                                            params = mapOf("hue" to JsonPrimitive(1.0)),
                                        ),
                                    ),
                                ),
                            ),
                        ),
                    )
                }
            }
            h.settle(3)
            assertSame(sbInitial, h.storyboard, "Property edit must NOT rebuild Storyboard")
            assertEquals(1, mountCount.get(), "Property edit must NOT remount specimen")
            val frameAfter = h.renderCurrent()
            assertColorNear(frameAfter.pixelColor(60, 60), 0x16, 0xA3, 0x4A, label = "after live param edit")

            // Theme edit: still same Storyboard & no remount!
            h.config = cfg.copy(themeName = "light")
            h.settle(3)
            assertSame(sbInitial, h.storyboard, "Theme switch must NOT rebuild Storyboard")
            assertEquals(1, mountCount.get(), "Theme switch must NOT remount specimen")

            // Structural edit (add beat to slide): MUST rebuild Storyboard!
            h.onUiThread {
                h.store.edit("Add beat") { d ->
                    d.copy(
                        slides = listOf(
                            d.slides[0].copy(beats = listOf(BeatSpec(), BeatSpec())),
                        ),
                    )
                }
            }
            h.settle(3)
            assertNotSame(sbInitial, h.storyboard, "Beat count change MUST rebuild Storyboard")
        }
    }

    @Test
    fun autoAdvanceFiresOnceOnForwardArrivalOnly() {
        val doc = DeckDocument(
            id = "auto-advance-forward-only",
            stage = compactStage(200, 120),
            slides = listOf(
                SlideSpec(
                    id = "s1",
                    beats = listOf(
                        BeatSpec(autoAdvanceMs = 0), // Beat 0: must NOT auto-fire on initial mount!
                        BeatSpec(autoAdvanceMs = 0), // Beat 1: MUST auto-advance once when reached forward!
                        BeatSpec(),                  // Beat 2: rests here
                    ),
                    nodes = listOf(
                        NodeSpec(
                            id = "dot",
                            frame = FrameSpec(x = 20.0, y = 20.0, w = 40.0, h = 40.0),
                            shape = ShapePayload(geometry = ShapeGeometry.Rect, fill = Fill.Solid("#22C55E")),
                            builds = listOf(
                                Build.Change(beat = 1, durationMs = 60, curve = Curve.Linear, set = buildJsonObject { putJsonObject("frame") { put("x", 80.0) } }),
                                Build.Change(beat = 2, durationMs = 60, curve = Curve.Linear, set = buildJsonObject { putJsonObject("frame") { put("x", 140.0) } }),
                            ),
                        ),
                    ),
                ),
            ),
        )

        var controllerRef: AutoAdvanceController? = null
        DocumentStageHarness(
            initialDocument = doc,
            initialConfig = DocumentRenderConfig(baseDensity = 1f, fontsDir = bundledFontsDirPath()),
            extraOverlayContent = { _, animatic ->
                val scope = androidx.compose.runtime.rememberCoroutineScope()
                val ctrl = remember(scope, animatic) {
                    AutoAdvanceController(
                        scope = scope,
                        onAdvanceForward = { animatic.advance(AdvanceDirection.Forward) },
                    )
                }
                controllerRef = ctrl
                AutoAdvanceEffect(
                    animatic = animatic,
                    session = dev.sebastiano.ciacola.document.LocalRenderSession.current
                        ?: RenderSession.forPresent(documentProvider = { doc }),
                    enabled = true,
                    isScrubbing = false,
                    controller = ctrl,
                )
            },
        ).use { h ->
            // 1. Initial mount at beat 0 (even with autoAdvanceMs = 0) must NEVER auto-advance.
            h.settle(8, stepNanos = 20_000_000L)
            assertEquals(Storyboard.Index(0, 0), h.animatic.currentIndex, "Must not auto-advance on initial mount")

            // 2. Forward step beat 0 -> beat 1 (autoAdvanceMs = 0) must auto-advance ONCE onto beat 2!
            h.advanceToRest(AdvanceDirection.Forward, stepMs = 10L)
            // Pump frames so the scheduled auto-advance coroutine runs and completes beat 1 -> beat 2.
            h.settle(20, stepNanos = 15_000_000L)
            assertEquals(Storyboard.Index(0, 2), h.animatic.currentIndex, "Forward arrival on autoAdvanceMs=0 beat must chain to beat 2")

            // 3. Backward step beat 2 -> beat 1 must NOT auto-advance!
            h.advanceToRest(AdvanceDirection.Backward, stepMs = 10L)
            h.settle(12, stepNanos = 15_000_000L)
            assertEquals(Storyboard.Index(0, 1), h.animatic.currentIndex, "Backward arrival must NEVER trigger auto-advance")

            // 4. Jump directly to beat 1 must NOT auto-advance!
            h.jumpAndSettle(Storyboard.Index(0, 0))
            h.jumpAndSettle(Storyboard.Index(0, 1), settleFrames = 12)
            assertEquals(Storyboard.Index(0, 1), h.animatic.currentIndex, "jumpTo must NEVER trigger auto-advance")

            // 5. Cold seek AND in-place scrub (rebuildScene = false) landing on beat 1 must NOT auto-advance!
            val beat0Idx = h.valueStateIndex(0, 0)
            val beat1Idx = h.valueStateIndex(0, 1)
            h.jumpAndSettle(Storyboard.Index(0, 0))
            h.seekSegment(beat0Idx, 0.5f, rebuildScene = false)
            h.seekSegment(beat1Idx, 0f, rebuildScene = false)
            h.settle(12, stepNanos = 15_000_000L)
            assertEquals(Storyboard.Index(0, 1), h.animatic.currentIndex, "In-place scrub onto beat 1 must NEVER trigger auto-advance")

            h.jumpAndSettle(Storyboard.Index(0, 0))
            h.seekSegment(beat0Idx, 0.5f, rebuildScene = true)
            h.seekSegment(beat1Idx, 0f, rebuildScene = true)
            h.settle(12, stepNanos = 15_000_000L)
            assertEquals(Storyboard.Index(0, 1), h.animatic.currentIndex, "Cold seek onto beat 1 must NEVER trigger auto-advance")

            // 6. Mid-flight reversal back onto beat 1 (beat 1 -> Forward toward beat 2 -> Backward back to beat 1) must NOT auto-advance!
            h.onUiThread {
                val revStart = h.scope.launch { h.animatic.advance(AdvanceDirection.Forward) }
                var ticks = 0
                while (!revStart.isCompleted && ticks++ < 40) {
                    h.renderStep(10_000_000L)
                    if (!h.animatic.scrubSettling && h.animatic.activeFraction >= 0.4f) break
                }
            }
            h.advanceToRest(AdvanceDirection.Backward, stepMs = 10L)
            h.settle(12, stepNanos = 15_000_000L)
            assertEquals(Storyboard.Index(0, 1), h.animatic.currentIndex, "Mid-flight reversal back onto beat 1 must NEVER trigger auto-advance")
            assertTrue(controllerRef != null)
        }
    }

    @Test
    fun warpCodeFixedBoxNoSiblingReflowDurationAuthorityAndPerPairEquality() {
        val codeBefore = "val x = 1\nval y = 2"
        val codeAfter = """
            fun computeShaderColor(uvX: Float, uvY: Float, gain: Float): Float {
                val a = uvX * gain + 0.25f
                val b = uvY * gain + 0.75f
                val mix = (a + b) * 0.5f
                return mix.coerceIn(0f, 1f)
            }
        """.trimIndent()

        fun buildCodeDoc(
            tokenMs: Int,
            delayMs: Int = 0,
            badgeBeat1Ms: Int = 40, // Strictly <= shortest warpSpan (76ms) so code card is UNMASKED!
            highlightOnly: Boolean = false,
        ): DeckDocument = DeckDocument(
            id = "warp-$tokenMs-$delayMs-$highlightOnly",
            stage = compactStage(640, 360),
            // Decoy theme motion duration (999 ms) proving WarpCodeAdapter NEVER falls back to theme.motion!
            themes = mapOf("decoy" to DeckThemeSpec(motion = ThemeMotionDefaults(durationMs = 999))),
            defaultTheme = "decoy",
            slides = listOf(
                SlideSpec(
                    id = "s1",
                    background = Fill.Solid("#0F172A"),
                    beats = listOf(
                        BeatSpec(notes = "short code"),
                        BeatSpec(notes = "wide tall code"),
                        BeatSpec(notes = "unchanged code beat"),
                    ),
                    nodes = listOf(
                        // Row containing code box (x=20..380, y=20..160) + right sibling (x=396..516, y=20..100)
                        NodeSpec(
                            id = "row-container",
                            frame = FrameSpec(x = 20.0, y = 20.0, w = 600.0, h = 160.0),
                            row = ContainerPayload(
                                gap = 16.0,
                                children = listOf(
                                    NodeSpec(
                                        id = "code-in-row",
                                        frame = FrameSpec(w = 360.0, h = 140.0),
                                        code = CodePayload(
                                            language = "kotlin",
                                            value = codeBefore,
                                            highlightLines = if (highlightOnly) listOf(1) else emptyList(),
                                            surface = SurfaceSpec(
                                                fill = Fill.Solid("#1E293B"),
                                                cornerRadius = 8.0,
                                                padding = PaddingSpec(12.0, 12.0, 12.0, 12.0),
                                            ),
                                        ),
                                        builds = listOf(
                                            Build.Change(
                                                beat = 1,
                                                delayMs = delayMs.takeIf { it > 0 },
                                                durationMs = tokenMs,
                                                set = buildJsonObject {
                                                    putJsonObject("code") {
                                                        if (highlightOnly) {
                                                            put("highlightLines", JsonArray(listOf(JsonPrimitive(2))))
                                                        } else {
                                                            put("value", codeAfter)
                                                        }
                                                    }
                                                },
                                            ),
                                        ),
                                    ),
                                    NodeSpec(
                                        id = "row-sibling",
                                        frame = FrameSpec(w = 120.0, h = 80.0),
                                        shape = ShapePayload(geometry = ShapeGeometry.Rect, fill = Fill.Solid("#F59E0B")),
                                    ),
                                ),
                            ),
                        ),
                        // Column containing code box (x=20..380, y=190..280) + bottom sibling (x=20..160, y=290..330)
                        NodeSpec(
                            id = "col-container",
                            frame = FrameSpec(x = 20.0, y = 190.0, w = 400.0, h = 160.0),
                            column = ContainerPayload(
                                gap = 10.0,
                                children = listOf(
                                    NodeSpec(
                                        id = "code-in-col",
                                        frame = FrameSpec(w = 360.0, h = 90.0),
                                        code = CodePayload(
                                            language = "kotlin",
                                            value = codeBefore,
                                            highlightLines = if (highlightOnly) listOf(1) else emptyList(),
                                            surface = SurfaceSpec(fill = Fill.Solid("#1E293B"), cornerRadius = 6.0),
                                        ),
                                        builds = listOf(
                                            Build.Change(
                                                beat = 1,
                                                delayMs = delayMs.takeIf { it > 0 },
                                                durationMs = tokenMs,
                                                set = buildJsonObject {
                                                    putJsonObject("code") {
                                                        if (highlightOnly) {
                                                            put("highlightLines", JsonArray(listOf(JsonPrimitive(2))))
                                                        } else {
                                                            put("value", codeAfter)
                                                        }
                                                    }
                                                },
                                            ),
                                        ),
                                    ),
                                    NodeSpec(
                                        id = "col-sibling",
                                        frame = FrameSpec(w = 140.0, h = 40.0),
                                        shape = ShapePayload(geometry = ShapeGeometry.Rect, fill = Fill.Solid("#EC4899")),
                                    ),
                                ),
                            ),
                        ),
                        // Non-code animated node on same slide for cross-family pixel verification
                        NodeSpec(
                            id = "non-code-badge",
                            frame = FrameSpec(x = 460.0, y = 210.0, w = 60.0, h = 60.0),
                            shape = ShapePayload(geometry = ShapeGeometry.Rect, fill = Fill.Solid("#22C55E")),
                            builds = listOf(
                                Build.Change(
                                    beat = 1,
                                    durationMs = badgeBeat1Ms,
                                    curve = Curve.Linear,
                                    set = buildJsonObject {
                                        putJsonObject("frame") { put("x", 540.0) }
                                    },
                                ),
                                Build.Change(
                                    beat = 2,
                                    durationMs = 120,
                                    curve = Curve.Linear,
                                    set = buildJsonObject {
                                        putJsonObject("shape") { put("fill", "#A855F7") }
                                    },
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )

        // 1. UNMASKED duration parity across 10 ms (76 ms), 60 ms (83 ms), delayed (150+83=233 ms),
        //    highlightLines-only change, reducedMotion = true, & unchanged beat 2!
        data class Case(val tokenMs: Int, val delayMs: Int, val reduced: Boolean, val highlightOnly: Boolean = false)
        val cases = listOf(
            Case(10, 0, false),
            Case(60, 0, false),
            Case(60, 150, false),
            Case(60, 150, true),
            Case(60, 0, false, highlightOnly = true),
        )
        for (c in cases) {
            val doc = buildCodeDoc(c.tokenMs, c.delayMs, badgeBeat1Ms = 40, highlightOnly = c.highlightOnly)
            val planBeat1 = SegmentPlanner.planSegment(doc, "s1", beat = 1, reducedMotion = c.reduced)
            val planBeat2 = SegmentPlanner.planSegment(doc, "s1", beat = 2, reducedMotion = c.reduced)
            val expectedSpan = warpSchedule(c.tokenMs, c.reduced).totalSpanMs
            val codeTrack = planBeat1.tracks.firstOrNull { it.nodeId == "code-in-row" }
            assertNotNull(codeTrack?.warpSchedule, "code track must carry warpSchedule for $c")
            if (!c.reduced) {
                // UNMASKED: planBeat1.segmentMs MUST equal exact delayMs + expectedSpan (no 200ms badge hiding it!)
                assertEquals(
                    c.delayMs + expectedSpan,
                    planBeat1.segmentMs,
                    "UNMASKED: planBeat1.segmentMs must equal exact delay(${c.delayMs}) + warpSpan($expectedSpan) for $c",
                )
            }
            val cfg = DocumentRenderConfig(
                baseDensity = 1f,
                reducedMotion = c.reduced,
                fontsDir = bundledFontsDirPath(),
            )
            DocumentStageHarness(doc, initialConfig = cfg).use { h ->
                val (_, beat1FwdEff) = h.advanceAndCaptureAt(AdvanceDirection.Forward, 0.5f, stepMs = 5L)
                assertEquals(
                    planBeat1.segmentMs * 1_000_000L,
                    beat1FwdEff,
                    "forward effectiveDurationNanos mismatch for $c",
                )
                // Unchanged code on beat 2 must contribute 0 ns (exact planBeat2.segmentMs from non-code badge only!)
                val (_, beat2FwdEff) = h.advanceAndCaptureAt(AdvanceDirection.Forward, 0.5f, stepMs = 5L)
                assertEquals(
                    planBeat2.segmentMs * 1_000_000L,
                    beat2FwdEff,
                    "unchanged-code beat effectiveDurationNanos mismatch for $c",
                )
                // Backward beat 1 -> beat 0 duration parity
                h.jumpAndSettle(Storyboard.Index(0, 1))
                val (_, beat1BwdEff) = h.advanceAndCaptureAt(AdvanceDirection.Backward, 0.5f, stepMs = 5L)
                assertEquals(
                    planBeat1.segmentMs * 1_000_000L,
                    beat1BwdEff,
                    "backward effectiveDurationNanos mismatch for $c",
                )
            }
        }

        // 2. Sibling non-reflow in Row & Column (fwd, bwd, ← → ←, & scrub) + mounted timing window
        //    & bounded constraints assertions + per-pair-family pixel equality + narrow two-code-box mask!
        val doc = buildCodeDoc(tokenMs = 60, delayMs = 40, badgeBeat1Ms = 200)
        val plan2Beat1 = SegmentPlanner.planSegment(doc, "s1", beat = 1, reducedMotion = false)
        val ws60 = warpSchedule(60, reducedMotion = false)
        val expectedBwdStart = mirroredWarpWindow(plan2Beat1.segmentMs, trackStartMs = 40, totalSpanMs = ws60.totalSpanMs).first

        val inspectionRegistry = StageInspectionRegistry()
        val cfg = DocumentRenderConfig(baseDensity = 1f, reducedMotion = false, fontsDir = bundledFontsDirPath())

        val rowShifts = mutableListOf<Rect>()
        val colShifts = mutableListOf<Rect>()
        val observedTimingSnapshots = mutableListOf<WarpMountedTimingSnapshot>()
        val observedConstraints = mutableListOf<Pair<String, Constraints>>()

        val session = RenderSession.forPresent(documentProvider = { doc })
        var animaticRef: Animatic? = null
        var scopeRef: kotlinx.coroutines.CoroutineScope? = null

        val scene = ImageComposeScene(
            width = 640,
            height = 360,
            density = Density(1f),
            coroutineContext = Dispatchers.Unconfined,
        ) {
            scopeRef = androidx.compose.runtime.rememberCoroutineScope()
            CompositionLocalProvider(
                LocalStageInspectionRegistry provides inspectionRegistry,
                LocalWarpTimingObserver provides { observedTimingSnapshots += it },
                LocalWarpChildConstraintsObserver provides { id, c -> observedConstraints += (id to c) },
            ) {
                val sb = rememberDocumentStoryboard(session = session, config = cfg)
                val a = rememberAnimatic(storyboard = { sb })
                animaticRef = a
                Easel(a, mode = SceneMode.Preview)
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
            val beat0Idx = a.stateDescriptors.first { it.sceneIndex == 0 && it.frameIndex == 0 && !it.isBoundaryStart }.stateIndex

            val siblingRowRectRest0 = checkNotNull(inspectionRegistry.nodes["row-sibling"]?.stageBounds)
            val siblingColRectRest0 = checkNotNull(inspectionRegistry.nodes["col-sibling"]?.stageBounds)
            step(1L)

            fun recordSiblingBounds() {
                inspectionRegistry.nodes["row-sibling"]?.stageBounds?.let { rowShifts += it }
                inspectionRegistry.nodes["col-sibling"]?.stageBounds?.let { colShifts += it }
            }

            // Forward play beat 0 -> beat 1: sample sibling bounds on EVERY frame + capture canonical 0.5
            var fwdMidFrame: RenderedFrame? = null
            var bestFwdErr = Float.MAX_VALUE
            val fwdJob = sc.launch { a.advance(AdvanceDirection.Forward) }
            var guard = 0
            while (!fwdJob.isCompleted && guard++ < 400) {
                val frame = step(10L)
                recordSiblingBounds()
                if (!a.scrubSettling && a.currentIndex != a.targetIndex && a.effectiveDurationNanos() > 0L) {
                    val err = kotlin.math.abs(a.activeFraction - 0.5f)
                    if (err < bestFwdErr) {
                        bestFwdErr = err
                        fwdMidFrame = frame
                    }
                }
            }
            assertTrue(fwdJob.isCompleted)
            repeat(3) { step() }
            val rest1FrameByPlay = step(1L)

            // Forward pair family equality: forward play == cold seek == in-place scrub == → ← → reversal at canonical 0.5!
            val seekJob = sc.launch { a.seekToSegment(beat0Idx, 0.5f, rebuildScene = true) }
            while (!seekJob.isCompleted) {
                step(5L)
                recordSiblingBounds()
            }
            val seekMidFrame = step(1L)
            recordSiblingBounds()
            assertFramesEqual(
                checkNotNull(fwdMidFrame),
                seekMidFrame,
                "forward play != cold seek inside forward pair family",
            )

            // In-place scrub handover (rebuildScene = false) also keeps siblings stationary!
            val inPlaceScrub = sc.launch { a.seekToSegment(beat0Idx, 0.5f, rebuildScene = false) }
            while (!inPlaceScrub.isCompleted) {
                step(5L)
                recordSiblingBounds()
            }
            recordSiblingBounds()

            // Forward → ← → reversal starting from resting beat 0
            val jump0 = sc.launch { a.jumpTo(Storyboard.Index(0, 0)) }
            while (!jump0.isCompleted) step(5L)
            repeat(3) { step() }
            val fLeg1 = sc.launch { a.advance(AdvanceDirection.Forward) }
            var ft = 0
            while (!fLeg1.isCompleted && ft++ < 100) {
                step(10L)
                recordSiblingBounds()
                if (!a.scrubSettling && a.activeFraction >= 0.35f) break
            }
            val fLeg2 = sc.launch { a.advance(AdvanceDirection.Backward) }
            ft = 0
            while (!fLeg2.isCompleted && ft++ < 100) {
                step(10L)
                recordSiblingBounds()
                if (!a.scrubSettling && a.activeFraction <= 0.15f) break
            }
            var fwdRevMidFrame: RenderedFrame? = null
            var bestFwdRevErr = Float.MAX_VALUE
            val fLeg3 = sc.launch { a.advance(AdvanceDirection.Forward) }
            guard = 0
            while (!fLeg3.isCompleted && guard++ < 400) {
                val frame = step(10L)
                recordSiblingBounds()
                if (!a.scrubSettling && a.currentIndex != a.targetIndex && a.effectiveDurationNanos() > 0L) {
                    val err = kotlin.math.abs(a.activeFraction - 0.5f)
                    if (err < bestFwdRevErr) {
                        bestFwdRevErr = err
                        fwdRevMidFrame = frame
                    }
                }
            }
            assertTrue(fLeg3.isCompleted)
            assertFramesEqual(
                checkNotNull(fwdMidFrame),
                checkNotNull(fwdRevMidFrame),
                "forward play != → ← → reversal within forward pair family",
            )

            // Backward pair family equality: backward play == ← → ← reversal from rest!
            val jump1 = sc.launch { a.jumpTo(Storyboard.Index(0, 1)) }
            while (!jump1.isCompleted) step(5L)
            repeat(3) { step() }
            val rest1FrameByJump = step(1L)
            assertFramesEqual(rest1FrameByPlay, rest1FrameByJump, "resting beat 1 jump != play")

            var bwdMidFrame: RenderedFrame? = null
            var bestBwdErr = Float.MAX_VALUE
            var bwdEff = -1L
            val bwdJob = sc.launch { a.advance(AdvanceDirection.Backward) }
            guard = 0
            while (!bwdJob.isCompleted && guard++ < 400) {
                val frame = step(10L)
                recordSiblingBounds()
                if (!a.scrubSettling && a.currentIndex != a.targetIndex && a.effectiveDurationNanos() > 0L) {
                    val canonical = 1f - a.activeFraction
                    val err = kotlin.math.abs(canonical - 0.5f)
                    if (err < bestBwdErr) {
                        bestBwdErr = err
                        bwdMidFrame = frame
                        bwdEff = a.effectiveDurationNanos()
                    }
                }
            }
            assertTrue(bwdJob.isCompleted)
            repeat(3) { step() }

            // ← → ← reversal starting from resting beat 1
            val jump1Again = sc.launch { a.jumpTo(Storyboard.Index(0, 1)) }
            while (!jump1Again.isCompleted) step(5L)
            repeat(3) { step() }
            val leg1 = sc.launch { a.advance(AdvanceDirection.Backward) }
            var t1 = 0
            while (!leg1.isCompleted && t1++ < 100) {
                step(10L)
                recordSiblingBounds()
                if (!a.scrubSettling && a.activeFraction >= 0.35f) break
            }
            val leg2 = sc.launch { a.advance(AdvanceDirection.Forward) }
            var t2 = 0
            while (!leg2.isCompleted && t2++ < 100) {
                step(10L)
                recordSiblingBounds()
                if (!a.scrubSettling && a.activeFraction <= 0.15f) break
            }
            var bwdRevMidFrame: RenderedFrame? = null
            var bestRevErr = Float.MAX_VALUE
            var bwdRevEff = -1L
            val leg3 = sc.launch { a.advance(AdvanceDirection.Backward) }
            guard = 0
            while (!leg3.isCompleted && guard++ < 400) {
                val frame = step(10L)
                recordSiblingBounds()
                if (!a.scrubSettling && a.currentIndex != a.targetIndex && a.effectiveDurationNanos() > 0L) {
                    val canonical = 1f - a.activeFraction
                    val err = kotlin.math.abs(canonical - 0.5f)
                    if (err < bestRevErr) {
                        bestRevErr = err
                        bwdRevMidFrame = frame
                        bwdRevEff = a.effectiveDurationNanos()
                    }
                }
            }
            assertTrue(leg3.isCompleted)
            assertEquals(bwdEff, bwdRevEff, "backward play and ← → ← reversal must have identical effectiveDurationNanos")
            assertFramesEqual(
                checkNotNull(bwdMidFrame),
                checkNotNull(bwdRevMidFrame),
                "backward play != ← → ← reversal within backward pair family",
            )

            // Assert 0 px sibling shift across EVERY frame (forward, backward, reversals, and scrub) in Row & Column!
            assertTrue(rowShifts.size > 20 && colShifts.size > 20)
            for (r in rowShifts) {
                assertEquals(siblingRowRectRest0, r, "Row sibling shifted during code morph!")
            }
            for (r in colShifts) {
                assertEquals(siblingColRectRest0, r, "Column sibling shifted during code morph!")
            }

            // Assert mounted Warp timing windows (applied ONCE, exact forward/mirrored start, bounded inside span)
            val fwdSnapshots = observedTimingSnapshots.filter { !it.isBackwardRoot }
            val bwdSnapshots = observedTimingSnapshots.filter { it.isBackwardRoot }
            assertTrue(fwdSnapshots.isNotEmpty(), "Expected observed forward Warp timing snapshots")
            assertTrue(bwdSnapshots.isNotEmpty(), "Expected observed backward Warp timing snapshots")
            for (s in fwdSnapshots) {
                assertEquals(40, s.effectiveStartMs, "Forward Warp track startMs must be applied ONCE (=40), got $s")
                assertEquals(ws60.totalSpanMs, s.totalSpanMs)
                assertTrue(s.arriveDelayMs + s.arriveDurationMs <= 40 + ws60.totalSpanMs, "Forward arrive window exceeded span: $s")
            }
            for (s in bwdSnapshots) {
                assertEquals(expectedBwdStart, s.effectiveStartMs, "Backward Warp track startMs must match mirroredWarpWindow (=$expectedBwdStart), got $s")
                assertEquals(ws60.totalSpanMs, s.totalSpanMs)
                assertTrue(s.arriveDelayMs + s.arriveDurationMs <= expectedBwdStart + ws60.totalSpanMs, "Backward arrive window exceeded span: $s")
            }

            // Assert bounded measurement constraints in DocWarpStateHost (never Constraints.Infinity!)
            assertTrue(observedConstraints.isNotEmpty(), "Expected observed DocWarpStateHost child constraints")
            for ((nodeId, c) in observedConstraints) {
                assertTrue(c.hasBoundedWidth, "DocWarpStateHost for $nodeId must pass bounded maxWidth (got $c)")
            }

            // Non-code nodes match across BOTH families (forward vs backward)!
            // Exclude ONLY the exact two code card boxes so row-sibling, col-sibling, AND non-code-badge are ALL verified!
            val exactCodeBoxes = listOf(
                intArrayOf(20, 20, 380, 160),   // code-in-row exact frame
                intArrayOf(20, 190, 380, 280),  // code-in-col exact frame
            )
            val nonCodeDiff = diffFrames(
                checkNotNull(fwdMidFrame),
                checkNotNull(bwdMidFrame),
                tolerance = 2,
                excludeBoxes = exactCodeBoxes,
            )
            assertEquals(
                0,
                nonCodeDiff.differingPixelsOutsideBox,
                "row-sibling, col-sibling, and non-code-badge must match pixel-for-pixel across forward & backward families",
            )
        } finally {
            scene.close()
        }
    }
}
