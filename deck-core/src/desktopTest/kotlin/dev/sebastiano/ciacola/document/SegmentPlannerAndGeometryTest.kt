package dev.sebastiano.ciacola.document

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Tests for [SegmentPlanner] stagger/after/warp scheduling, cross-slide geometry/stacking/ownership,
 * and per-slide cache keys.
 */
class SegmentPlannerAndGeometryTest {

    @Test
    fun staggerOrdersTieGroupsAfterChainsSpringsZeroDurationAndTwoFragmentManualDelay() {
        // 1. Tie groups: y = {0, 7} share rank 0 (within 8 units), y = {14} is rank 1!
        val tieDoc = DeckDocument(
            id = "ties",
            slides = listOf(
                SlideSpec(
                    id = "s0",
                    beats = listOf(
                        BeatSpec(),
                        BeatSpec(stagger = Stagger(ms = 100, order = StaggerOrder.TopToBottom)),
                    ),
                    nodes = listOf(
                        NodeSpec(
                            id = "a",
                            frame = FrameSpec(x = 200.0, y = 0.0, w = 100.0, h = 40.0),
                            shape = ShapePayload(geometry = ShapeGeometry.Rect),
                            builds = listOf(Build.Enter(beat = 1, effect = "fade", durationMs = 200)),
                        ),
                        NodeSpec(
                            id = "b",
                            frame = FrameSpec(x = 100.0, y = 7.0, w = 100.0, h = 40.0),
                            shape = ShapePayload(geometry = ShapeGeometry.Rect),
                            builds = listOf(Build.Enter(beat = 1, effect = "fade", durationMs = 200)),
                        ),
                        NodeSpec(
                            id = "c",
                            frame = FrameSpec(x = 50.0, y = 14.0, w = 100.0, h = 40.0),
                            shape = ShapePayload(geometry = ShapeGeometry.Rect),
                            builds = listOf(Build.Enter(beat = 1, effect = "fade", durationMs = 200)),
                        ),
                    ),
                ),
            ),
        )
        val topDown = SegmentPlanner.planSegment(tieDoc, "s0", beat = 1)
        val startsTD = topDown.tracks.associate { it.nodeId to it.startMs }
        assertEquals(0, startsTD["a"], "y=0 must be rank 0")
        assertEquals(0, startsTD["b"], "y=7 is within 8 units of y=0 so it MUST share rank 0")
        assertEquals(100, startsTD["c"], "y=14 exceeds 8 units from group start 0 -> rank 1")

        // LeftToRight (x = 50, 100, 200) and reverse=true
        val ltrDoc = tieDoc.copy(
            slides = listOf(
                tieDoc.slides[0].copy(
                    beats = listOf(
                        BeatSpec(),
                        BeatSpec(stagger = Stagger(ms = 50, order = StaggerOrder.LeftToRight, reverse = true)),
                    ),
                ),
            ),
        )
        val ltrRev = SegmentPlanner.planSegment(ltrDoc, "s0", beat = 1)
        val startsLtrRev = ltrRev.tracks.associate { it.nodeId to it.startMs }
        assertEquals(0, startsLtrRev["a"], "x=200 is first when LeftToRight reversed")
        assertEquals(50, startsLtrRev["b"])
        assertEquals(100, startsLtrRev["c"])

        // ReadingOrder (y tie-group {0, 7} sorted by x: b(x=100) before a(x=200), then c(y=14))
        val readDoc = tieDoc.copy(
            slides = listOf(
                tieDoc.slides[0].copy(
                    beats = listOf(
                        BeatSpec(),
                        BeatSpec(stagger = Stagger(ms = 80, order = StaggerOrder.ReadingOrder)),
                    ),
                ),
            ),
        )
        val readPlan = SegmentPlanner.planSegment(readDoc, "s0", beat = 1)
        val startsRead = readPlan.tracks.associate { it.nodeId to it.startMs }
        assertEquals(0, startsRead["b"], "Within y tie-group {0,7}, b (x=100) precedes a (x=200)")
        assertEquals(80, startsRead["a"])
        assertEquals(160, startsRead["c"])

        // 2. Same-node build chain + `after` (`nodeId` and `nodeId#buildId`) + max(listOrder, after) + zero-duration step
        val chainDoc = DeckDocument(
            id = "chains",
            slides = listOf(
                SlideSpec(
                    id = "s0",
                    beats = listOf(BeatSpec(), BeatSpec()),
                    nodes = listOf(
                        NodeSpec(
                            id = "lead",
                            shape = ShapePayload(geometry = ShapeGeometry.Rect),
                            builds = listOf(
                                Build.Enter(id = "in", beat = 1, effect = "fade", delayMs = 50, durationMs = 200), // [50, 250]
                                Build.Emphasis(id = "pulse", beat = 1, effect = "pulse", durationMs = 150), // [250, 400]
                            ),
                        ),
                        NodeSpec(
                            id = "mid",
                            shape = ShapePayload(geometry = ShapeGeometry.Rect),
                            builds = listOf(
                                Build.Enter(id = "m1", beat = 1, effect = "fade", after = "lead#in", durationMs = 100), // [250, 350]
                                Build.Change(
                                    id = "m2",
                                    beat = 1,
                                    after = "lead", // waits for lead's last build on beat 1 (end 400) > m1 (350)
                                    durationMs = 0, // zero-duration step!
                                    set = buildJsonObject { put("opacity", 0.5) },
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val chainPlan = SegmentPlanner.planSegment(chainDoc, "s0", beat = 1)
        val leadIn = chainPlan.tracks.single { it.nodeId == "lead" && it.buildId == "in" }
        val leadPulse = chainPlan.tracks.single { it.nodeId == "lead" && it.buildId == "pulse" }
        val mid1 = chainPlan.tracks.single { it.nodeId == "mid" && it.buildId == "m1" }
        val mid2 = chainPlan.tracks.single { it.nodeId == "mid" && it.buildId == "m2" }

        assertEquals(50, leadIn.startMs)
        assertEquals(250, leadIn.endMs)
        assertEquals(250, leadPulse.startMs)
        assertEquals(400, leadPulse.endMs)
        assertEquals(250, mid1.startMs)
        assertEquals(350, mid1.endMs)
        assertEquals(400, mid2.startMs, "max(prev same-node end 350, after lead end 400) == 400")
        assertEquals(400, mid2.endMs)
        // Zero-duration step: 0f before start, 1f at and after start
        assertEquals(0f, mid2.progressAt(399.9f))
        assertEquals(1f, mid2.progressAt(400f))
        assertEquals(1f, mid2.progressAt(450f))

        // 3. Underdamped spring: overshoot > 1 preserved, analytical settle duration satisfies |x-1| < 0.001 & |v| < 0.001
        val spring = Curve.Spring(damping = 0.5, stiffness = 350.0)
        val settleMs = CurveEvaluator.springSettleDurationMs(spring.damping, spring.stiffness)
        assertTrue(settleMs in 200..4000, "Reasonable settle duration expected, got $settleMs")
        val samples = (0..settleMs step 10).map { CurveEvaluator.evaluateAtMs(spring, it.toFloat(), settleMs) }
        assertTrue(samples.any { it > 1.05f }, "Underdamped spring must preserve overshoot > 1")
        assertEquals(0f, CurveEvaluator.evaluateAtMs(spring, 0f, settleMs))
        assertEquals(1f, CurveEvaluator.evaluateAtMs(spring, settleMs.toFloat(), settleMs))
        // Verify velocity & displacement at settle time directly
        val settleSec = settleMs / 1000.0
        val xSettle = CurveEvaluator.evaluateSpring(spring.damping, spring.stiffness, settleSec)
        val vSettle = CurveEvaluator.evaluateSpringVelocity(spring.damping, spring.stiffness, settleSec)
        assertTrue(abs(xSettle - 1.0) < 0.001, "|x-1| at settleMs must be < 0.001, got ${abs(xSettle - 1.0)}")
        assertTrue(abs(vSettle) < 0.001, "|v| at settleMs must be < 0.001, got ${abs(vSettle)}")

        // 4. Two-fragment track with 1000ms manual delay and 100ms duration ends at 1100ms,
        //    and a dependent `after` track starts at 1100ms!
        val manualDoc = DeckDocument(
            id = "manual-span",
            slides = listOf(
                SlideSpec(
                    id = "s0",
                    beats = listOf(BeatSpec(), BeatSpec()),
                    nodes = listOf(
                        NodeSpec(
                            id = "words",
                            text = TextPayload(value = RichTextValue.Plain("one two")),
                            builds = listOf(
                                Build.Enter(
                                    id = "in",
                                    beat = 1,
                                    effect = "fade",
                                    by = DeliveryBy.Word,
                                    durationMs = 100,
                                    stagger = Stagger(ms = 0, manual = mapOf("two#0" to 1000)),
                                ),
                            ),
                        ),
                        NodeSpec(
                            id = "follower",
                            shape = ShapePayload(geometry = ShapeGeometry.Ellipse),
                            builds = listOf(
                                Build.Enter(id = "after-words", beat = 1, effect = "fade", after = "words#in", durationMs = 200),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val manualPlan = SegmentPlanner.planSegment(manualDoc, "s0", beat = 1)
        val wordsTrack = manualPlan.tracks.single { it.nodeId == "words" }
        val followerTrack = manualPlan.tracks.single { it.nodeId == "follower" }
        assertEquals(0, wordsTrack.startMs)
        assertEquals(1100, wordsTrack.endMs, "Two-fragment track with 1000ms manual delay and 100ms duration MUST end at 1100ms")
        assertEquals(1100, followerTrack.startMs, "Dependent `after` track must start at 1100ms")
        assertEquals(1300, manualPlan.segmentMs)

        // 5. Resizing predecessor in 1 store edit re-flows dependent track, and undo restores original plan!
        val store = DeckDocumentStore()
        store.loadInitial(manualDoc)
        assertTrue(store.edit("Resize words duration") { doc ->
            doc.copy(
                slides = doc.slides.map { s ->
                    s.copy(
                        nodes = s.nodes.map { n ->
                            if (n.id != "words") n else n.copy(
                                builds = n.builds.map { if (it is Build.Enter) it.copy(durationMs = 250) else it },
                            )
                        },
                    )
                },
            )
        })
        val reflowed = SegmentPlanner.planSegment(store.document, "s0", beat = 1)
        assertEquals(1250, reflowed.tracks.single { it.nodeId == "follower" }.startMs)
        store.undo()
        assertEquals(manualPlan, SegmentPlanner.planSegment(store.document, "s0", beat = 1))
    }

    @Test
    fun zeroKeptFragmentTextMorphsCarryAtSizeSlotMatchingMovePhaseAndDistinguishMoveTiming() {
        fun morphDoc(from: String, to: String, moveDelayMs: Int, moveDurationMs: Int): DeckDocument = DeckDocument(
            id = "size-slot",
            slides = listOf(
                SlideSpec(
                    id = "s0",
                    beats = listOf(BeatSpec(), BeatSpec()),
                    nodes = listOf(
                        NodeSpec(
                            id = "headline",
                            text = TextPayload(value = RichTextValue.Plain(from)),
                            builds = listOf(
                                Build.Change(
                                    id = "m",
                                    beat = 1,
                                    morph = TextMorphSpec(
                                        by = MorphBy.Word,
                                        phases = Phases(
                                            exit = Timing(delayMs = 0, durationMs = 150),
                                            move = Timing(delayMs = moveDelayMs, durationMs = moveDurationMs),
                                            enter = Timing(delayMs = 100, durationMs = 150),
                                        ),
                                    ),
                                    set = buildJsonObject { putJsonObject("text") { put("value", to) } },
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val cases = listOf(
            "alpha beta" to "gamma delta", // all replaced (0 kept)
            "" to "hello world",           // empty -> non-empty (0 kept)
            "hello world" to "",           // non-empty -> empty (0 kept)
        )
        for ((from, to) in cases) {
            val planA = SegmentPlanner.planSegment(morphDoc(from, to, moveDelayMs = 50, moveDurationMs = 700), "s0", beat = 1)
            val planB = SegmentPlanner.planSegment(morphDoc(from, to, moveDelayMs = 120, moveDurationMs = 900), "s0", beat = 1)

            val trackA = planA.tracks.single { it.nodeId == "headline" }
            val sizeSlotA = assertNotNull(trackA.pieces?.slot(Slot.SIZE_ADDRESS), "Every text morph must carry @size slot for '$from' -> '$to'")
            assertEquals(50, sizeSlotA.startMs)
            assertEquals(700, sizeSlotA.durationMs)
            assertEquals(750, trackA.endMs, "Move phase longer than exit & enter must determine track end via @size slot")
            assertEquals(750, planA.segmentMs)

            val trackB = planB.tracks.single { it.nodeId == "headline" }
            val sizeSlotB = assertNotNull(trackB.pieces?.slot(Slot.SIZE_ADDRESS))
            assertEquals(120, sizeSlotB.startMs)
            assertEquals(900, sizeSlotB.durationMs)
            assertEquals(1020, trackB.endMs)

            assertNotEquals(planA, planB, "Two 0-kept morphs differing only in Move timing must produce distinct SegmentPlans")
        }
    }

    @Test
    fun warpScheduleExactBoundsAndCodeChangePlannerIntegration() {
        // warpSchedule(10, false): leaving [0, 40], moving [2, 62], arriving [26, 76], totalSpanMs == 76
        val ws10 = warpSchedule(tokenDurationMs = 10, reducedMotion = false)
        assertEquals(0, ws10.leaving.startMs)
        assertEquals(40, ws10.leaving.endMs)
        assertEquals(2, ws10.moving.startMs)
        assertEquals(62, ws10.moving.endMs)
        assertEquals(26, ws10.arriving.startMs)
        assertEquals(76, ws10.arriving.endMs)
        assertEquals(76, ws10.totalSpanMs)

        // warpSchedule(60, false): leaving [0, 40], moving [9, 69], arriving [33, 83], totalSpanMs == 83
        val ws60 = warpSchedule(tokenDurationMs = 60, reducedMotion = false)
        assertEquals(0, ws60.leaving.startMs)
        assertEquals(40, ws60.leaving.endMs)
        assertEquals(9, ws60.moving.startMs)
        assertEquals(69, ws60.moving.endMs)
        assertEquals(33, ws60.arriving.startMs)
        assertEquals(83, ws60.arriving.endMs)
        assertEquals(83, ws60.totalSpanMs)

        // reducedMotion = true -> all 3 parts [0, 150], totalSpanMs == 150
        for (dur in listOf(10, 60)) {
            val wsRed = warpSchedule(tokenDurationMs = dur, reducedMotion = true)
            assertEquals(0, wsRed.leaving.startMs)
            assertEquals(150, wsRed.leaving.endMs)
            assertEquals(0, wsRed.moving.startMs)
            assertEquals(150, wsRed.moving.endMs)
            assertEquals(0, wsRed.arriving.startMs)
            assertEquals(150, wsRed.arriving.endMs)
            assertEquals(150, wsRed.totalSpanMs)
        }

        // Planner integration: startMs applied ONCE, durationMs == ws.totalSpanMs, mirroredWarpWindow exact
        val codeDoc = DeckDocument(
            id = "code-warp",
            slides = listOf(
                SlideSpec(
                    id = "s0",
                    beats = listOf(BeatSpec(), BeatSpec()),
                    nodes = listOf(
                        NodeSpec(
                            id = "codeBlock",
                            frame = FrameSpec(x = 80.0, y = 80.0, w = 800.0, h = 400.0),
                            code = CodePayload(value = "val a = 1"),
                            builds = listOf(
                                Build.Change(
                                    id = "step1",
                                    beat = 1,
                                    delayMs = 120,
                                    durationMs = 60,
                                    set = buildJsonObject { putJsonObject("code") { put("value", "val a = 2") } },
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val plan = SegmentPlanner.planSegment(codeDoc, "s0", beat = 1, reducedMotion = false)
        val track = plan.tracks.single { it.nodeId == "codeBlock" }
        assertEquals(ws60, track.warpSchedule)
        assertEquals(120, track.startMs)
        assertEquals(120 + 83, track.endMs)
        assertEquals(83, track.durationMs)
        assertEquals(203, plan.segmentMs)

        // Mirrored backward window: [segmentMs - trackStart - totalSpanMs, segmentMs - trackStart]
        assertEquals(0..83, mirroredWarpWindow(segmentMs = 203, trackStartMs = 120, totalSpanMs = 83))
        assertEquals(100..183, mirroredWarpWindow(segmentMs = 303, trackStartMs = 120, totalSpanMs = 83))

        // Reduced motion plan for same code node
        val planRed = SegmentPlanner.planSegment(codeDoc, "s0", beat = 1, reducedMotion = true)
        val trackRed = planRed.tracks.single { it.nodeId == "codeBlock" }
        assertEquals(0, trackRed.startMs)
        assertEquals(150, trackRed.durationMs)
        assertEquals(150, trackRed.warpSchedule?.totalSpanMs)
    }

    @Test
    fun section5_crossSlideGeometryPaddingShearFlowSpecimenRulesOwnerAndStacking() {
        // 1. Padded stack (stack.padding + surface.padding) + nested rotation & uniform scale reproduces exact affine
        val fromSlide = SlideSpec(
            id = "sA",
            beats = listOf(BeatSpec()),
            nodes = listOf(
                NodeSpec(
                    id = "outer-stack",
                    frame = FrameSpec(x = 100.0, y = 50.0, w = 800.0, h = 500.0),
                    stack = ContainerPayload(
                        padding = PaddingSpec(left = 20.0, top = 15.0, right = 20.0, bottom = 15.0),
                        surface = SurfaceSpec(padding = PaddingSpec(10.0)),
                        alignItems = "center", // explicit x/y on child must override align!
                        children = listOf(
                            NodeSpec(
                                id = "traveller",
                                frame = FrameSpec(x = 40.0, y = 30.0, w = 200.0, h = 100.0),
                                rotation = 15.0,
                                scale = ScaleValue.Uniform(1.5),
                                shape = ShapePayload(geometry = ShapeGeometry.Rect),
                            ),
                        ),
                    ),
                ),
                // Non-uniformly scaled parent with rotated child -> SHEARED!
                NodeSpec(
                    id = "sheared-parent",
                    frame = FrameSpec(x = 0.0, y = 0.0, w = 400.0, h = 400.0),
                    scale = ScaleValue.NonUniform(2.0, 1.0),
                    stack = ContainerPayload(
                        children = listOf(
                            NodeSpec(
                                id = "sheared-child",
                                frame = FrameSpec(x = 10.0, y = 10.0, w = 100.0, h = 100.0),
                                rotation = 30.0,
                                shape = ShapePayload(geometry = ShapeGeometry.Rect),
                            ),
                        ),
                    ),
                ),
                // Flow-layout ancestor (row) -> excluded with CROSS_SLIDE_FLOW_ANCESTOR lint
                NodeSpec(
                    id = "row-box",
                    frame = FrameSpec(x = 0.0, y = 600.0, w = 600.0, h = 100.0),
                    row = ContainerPayload(
                        children = listOf(
                            NodeSpec(
                                id = "in-row",
                                frame = FrameSpec(x = 10.0, y = 10.0, w = 100.0, h = 60.0),
                                shape = ShapePayload(geometry = ShapeGeometry.Rect),
                            ),
                        ),
                    ),
                ),
                // Specimen without w/h -> excluded; specimen WITH w/h -> travels!
                NodeSpec(
                    id = "spec-nosize",
                    frame = FrameSpec(x = 10.0, y = 10.0),
                    specimen = SpecimenPayload(ref = "meter"),
                ),
                NodeSpec(
                    id = "spec-sized",
                    frame = FrameSpec(x = 10.0, y = 10.0, w = 300.0, h = 200.0),
                    specimen = SpecimenPayload(ref = "meter"),
                ),
                // Outermost matched container owns its matched child
                NodeSpec(
                    id = "group-box",
                    frame = FrameSpec(x = 50.0, y = 750.0, w = 400.0, h = 120.0),
                    stack = ContainerPayload(
                        children = listOf(
                            NodeSpec(
                                id = "group-child",
                                frame = FrameSpec(x = 10.0, y = 10.0, w = 100.0, h = 50.0),
                                shape = ShapePayload(geometry = ShapeGeometry.Ellipse),
                            ),
                        ),
                    ),
                ),
            ),
            transition = TransitionSpec(type = TransitionType.MagicMove, durationMs = 600),
        )

        val toSlide = SlideSpec(
            id = "sB",
            beats = listOf(BeatSpec()),
            nodes = listOf(
                NodeSpec(
                    id = "traveller",
                    frame = FrameSpec(x = 500.0, y = 300.0, w = 200.0, h = 100.0),
                    rotation = 45.0,
                    scale = ScaleValue.Uniform(2.0),
                    shape = ShapePayload(geometry = ShapeGeometry.Rect),
                ),
                NodeSpec(
                    id = "sheared-child",
                    frame = FrameSpec(x = 200.0, y = 200.0, w = 100.0, h = 100.0),
                    shape = ShapePayload(geometry = ShapeGeometry.Rect),
                ),
                NodeSpec(
                    id = "in-row",
                    frame = FrameSpec(x = 200.0, y = 600.0, w = 100.0, h = 60.0),
                    shape = ShapePayload(geometry = ShapeGeometry.Rect),
                ),
                NodeSpec(
                    id = "spec-nosize",
                    frame = FrameSpec(x = 400.0, y = 10.0),
                    specimen = SpecimenPayload(ref = "meter"),
                ),
                NodeSpec(
                    id = "spec-sized",
                    frame = FrameSpec(x = 400.0, y = 10.0, w = 320.0, h = 200.0),
                    specimen = SpecimenPayload(ref = "meter"),
                ),
                NodeSpec(
                    id = "group-box",
                    frame = FrameSpec(x = 600.0, y = 750.0, w = 400.0, h = 120.0),
                    stack = ContainerPayload(
                        children = listOf(
                            NodeSpec(
                                id = "group-child",
                                frame = FrameSpec(x = 10.0, y = 10.0, w = 100.0, h = 50.0),
                                shape = ShapePayload(geometry = ShapeGeometry.Ellipse),
                            ),
                        ),
                    ),
                ),
            ),
        )

        // Check padded stack inset: outer(100,50) + surface.padding(10,10) + stack.padding(20,15) + child(40,30)
        // Child center at local (100, 50) -> stage (100+10+20+40+100, 50+10+15+30+50) = (270, 155)
        val geomFrom = assertIs<DocumentTransformResult.Determined>(
            CrossSlideGeometry.computeDocumentTransform(fromSlide, "traveller"),
        )
        val centerStage = geomFrom.affine.mapPoint(100.0, 50.0)
        assertTrue(abs(centerStage.x - 270.0) < 1e-6, "Expected x=270, got ${centerStage.x}")
        assertTrue(abs(centerStage.y - 155.0) < 1e-6, "Expected y=155, got ${centerStage.y}")
        val decomp = assertNotNull(geomFrom.decomposed)
        val rebuilt = decomp.toAffine()
        assertTrue(abs(rebuilt.a - geomFrom.affine.a) < 1e-6)
        assertTrue(abs(rebuilt.b - geomFrom.affine.b) < 1e-6)
        assertTrue(abs(rebuilt.c - geomFrom.affine.c) < 1e-6)
        assertTrue(abs(rebuilt.d - geomFrom.affine.d) < 1e-6)
        assertTrue(abs(rebuilt.tx - geomFrom.affine.tx) < 1e-6)
        assertTrue(abs(rebuilt.ty - geomFrom.affine.ty) < 1e-6)

        // Sheared child decomposes to null
        val shearedGeom = assertIs<DocumentTransformResult.Determined>(
            CrossSlideGeometry.computeDocumentTransform(fromSlide, "sheared-child"),
        )
        assertNull(shearedGeom.decomposed, "Rotated child of non-uniformly scaled parent must fail shear check")

        // Match cross-slide pairs: flow-layout child ("in-row") now matches with isDocumentDetermined = false;
        // sheared and unconstrained-specimen remain excluded.
        val match = CrossSlideGeometry.matchCrossSlidePairs(fromSlide, toSlide)
        val matchedIds = match.pairs.map { it.nodeId }.toSet()
        assertEquals(
            setOf("traveller", "in-row", "spec-sized", "group-box"),
            matchedIds,
            "Outermost owner excludes group-child; sheared/unsized-specimen excluded; flow child matched; got $matchedIds",
        )
        assertTrue(match.pairs.single { it.nodeId == "traveller" }.isDocumentDetermined)
        assertFalse(match.pairs.single { it.nodeId == "in-row" }.isDocumentDetermined)
        assertTrue(match.lintIssues.any { it.code == "CROSS_SLIDE_SHEARED_TRANSFORM" && it.address.contains("sheared-child") })
        assertFalse(match.lintIssues.any { it.code == "CROSS_SLIDE_FLOW_ANCESTOR" })
        assertTrue(match.lintIssues.any { it.code == "CROSS_SLIDE_SPECIMEN_MISSING_SIZE" && it.address.contains("spec-nosize") })

        // Verify DecomposedTransform.lerp at p=0 and p=1 reproduces endpoint affines
        val travPair = match.pairs.single { it.nodeId == "traveller" }
        val at0 = DecomposedTransform.lerp(travPair.fromTransform, travPair.toTransform, 0.0).toAffine()
        val at1 = DecomposedTransform.lerp(travPair.fromTransform, travPair.toTransform, 1.0).toAffine()
        assertTrue(abs(at0.tx - travPair.fromAffine.tx) < 1e-6 && abs(at0.ty - travPair.fromAffine.ty) < 1e-6)
        assertTrue(abs(at1.tx - travPair.toAffine.tx) < 1e-6 && abs(at1.ty - travPair.toAffine.ty) < 1e-6)

        // Owner selection across every DocSegment
        assertEquals(CrossSlideOwner.SceneA, CrossSlideGeometry.owner("sA", "sB", DeckClock(DocSegment.Rest("sA", 0), 0f, 0f)))
        assertEquals(CrossSlideOwner.SceneB, CrossSlideGeometry.owner("sA", "sB", DeckClock(DocSegment.Rest("sB", 0), 0f, 0f)))
        assertEquals(CrossSlideOwner.SceneB, CrossSlideGeometry.owner("sA", "sB", DeckClock(DocSegment.AtStart("sB"), 0f, 0f)))
        assertEquals(CrossSlideOwner.SceneB, CrossSlideGeometry.owner("sA", "sB", DeckClock(DocSegment.Entrance("sB"), 0.5f, 100f)))
        assertEquals(CrossSlideOwner.Overlay, CrossSlideGeometry.owner("sA", "sB", DeckClock(DocSegment.Cross("sA", "sB"), 0.5f, 300f)))

        // Stacking order: Reveal & Iris(out) = AOverB, others = BOverA; direction-independent!
        assertEquals(SlideLayerOrder.AOverB, CrossSlideGeometry.transitionLayerOrder(TransitionSpec(TransitionType.Reveal)))
        assertEquals(SlideLayerOrder.AOverB, CrossSlideGeometry.transitionLayerOrder(TransitionSpec(TransitionType.Iris, direction = "out")))
        assertEquals(SlideLayerOrder.BOverA, CrossSlideGeometry.transitionLayerOrder(TransitionSpec(TransitionType.Iris, direction = "in")))
        assertEquals(SlideLayerOrder.BOverA, CrossSlideGeometry.transitionLayerOrder(TransitionSpec(TransitionType.Push)))
        assertEquals(SlideLayerOrder.BOverA, CrossSlideGeometry.transitionLayerOrder(TransitionSpec(TransitionType.MagicMove)))

        val crossClock = DeckClock(DocSegment.Cross("sA", "sB"), 0.4f, 240f)
        val zRevealA = CrossSlideGeometry.sceneZIndex(0, "sA", crossClock) { TransitionSpec(TransitionType.Reveal) }
        val zRevealB = CrossSlideGeometry.sceneZIndex(1, "sB", crossClock) { TransitionSpec(TransitionType.Reveal) }
        assertTrue(zRevealA > zRevealB, "Reveal must stack A over B")
        val zPushA = CrossSlideGeometry.sceneZIndex(0, "sA", crossClock) { TransitionSpec(TransitionType.Push) }
        val zPushB = CrossSlideGeometry.sceneZIndex(1, "sB", crossClock) { TransitionSpec(TransitionType.Push) }
        assertTrue(zPushB > zPushA, "Push must stack B over A")
    }

    @Test
    fun ac1_flowAlignAutoSizedAndWeightedSpecimensMatchAndShearedFlowNodeRejects() {
        val fromSlide = SlideSpec(
            id = "sA",
            transition = TransitionSpec(type = TransitionType.MagicMove, durationMs = 400),
            nodes = listOf(
                NodeSpec(
                    id = "align-stack",
                    align = "center",
                    stack = ContainerPayload(
                        children = listOf(
                            NodeSpec(
                                id = "auto-rotated",
                                align = "center",
                                rotation = 12.0,
                                scale = ScaleValue.Uniform(1.2),
                                text = TextPayload(value = RichTextValue.Plain("Auto")),
                            ),
                        ),
                    ),
                ),
                NodeSpec(
                    id = "row-a",
                    frame = FrameSpec(w = 600.0, h = 200.0),
                    row = ContainerPayload(
                        gap = 16.0,
                        children = listOf(
                            NodeSpec(
                                id = "spec-row-weighted",
                                weight = 1.0,
                                frame = FrameSpec(h = 120.0),
                                specimen = SpecimenPayload(ref = "meter"),
                            ),
                            NodeSpec(
                                id = "spec-col-weighted",
                                weight = 1.0,
                                frame = FrameSpec(h = 120.0),
                                specimen = SpecimenPayload(ref = "meter"),
                            ),
                        ),
                    ),
                ),
                NodeSpec(
                    id = "sheared-flow-parent",
                    scale = ScaleValue.NonUniform(2.0, 1.0),
                    row = ContainerPayload(
                        children = listOf(
                            NodeSpec(
                                id = "sheared-flow-child",
                                rotation = 25.0,
                                shape = ShapePayload(geometry = ShapeGeometry.Rect),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val toSlide = SlideSpec(
            id = "sB",
            nodes = listOf(
                NodeSpec(
                    id = "col-b",
                    frame = FrameSpec(w = 360.0, h = 500.0),
                    column = ContainerPayload(
                        gap = 16.0,
                        children = listOf(
                            NodeSpec(
                                id = "auto-rotated",
                                rotation = 0.0,
                                text = TextPayload(value = RichTextValue.Plain("Auto")),
                            ),
                            NodeSpec(
                                id = "spec-row-weighted",
                                frame = FrameSpec(w = 240.0, h = 140.0),
                                specimen = SpecimenPayload(ref = "meter"),
                            ),
                            NodeSpec(
                                id = "spec-col-weighted",
                                weight = 1.0,
                                frame = FrameSpec(w = 300.0),
                                specimen = SpecimenPayload(ref = "meter"),
                            ),
                        ),
                    ),
                ),
                NodeSpec(
                    id = "sheared-flow-child",
                    frame = FrameSpec(x = 100.0, y = 100.0, w = 80.0, h = 40.0),
                    shape = ShapePayload(geometry = ShapeGeometry.Rect),
                ),
            ),
        )

        val res = CrossSlideGeometry.matchCrossSlidePairs(fromSlide, toSlide)
        val matched = res.pairs.associateBy { it.nodeId }
        assertEquals(setOf("auto-rotated", "spec-row-weighted", "spec-col-weighted"), matched.keys)
        assertFalse(matched.getValue("auto-rotated").isDocumentDetermined)
        assertFalse(matched.getValue("spec-row-weighted").isDocumentDetermined)
        assertFalse(matched.getValue("spec-col-weighted").isDocumentDetermined)
        assertTrue(res.lintIssues.any { it.code == "CROSS_SLIDE_SHEARED_TRANSFORM" && it.address.contains("sheared-flow-child") })
    }

    @Test
    fun ac2_flowGrid2x3StaggerOrdersTopToBottomLeftToRightAndReadingOrder() {
        fun gridDoc(order: StaggerOrder): DeckDocument {
            fun cell(id: String) = NodeSpec(
                id = id,
                shape = ShapePayload(geometry = ShapeGeometry.Rect),
                builds = listOf(Build.Enter(beat = 0, effect = "dissolve", durationMs = 200)),
            )
            return DeckDocument(
                id = "grid-stagger-$order",
                slides = listOf(
                    SlideSpec(
                        id = "s0",
                        beats = listOf(BeatSpec(stagger = Stagger(ms = 100, order = order))),
                        nodes = listOf(
                            NodeSpec(
                                id = "grid",
                                frame = FrameSpec(x = 100.0, y = 80.0, w = 600.0, h = 300.0),
                                column = ContainerPayload(
                                    gap = 20.0,
                                    children = listOf(
                                        NodeSpec(
                                            id = "row0",
                                            row = ContainerPayload(
                                                gap = 16.0,
                                                children = listOf(cell("c00"), cell("c01"), cell("c02")),
                                            ),
                                        ),
                                        NodeSpec(
                                            id = "row1",
                                            row = ContainerPayload(
                                                gap = 16.0,
                                                children = listOf(cell("c10"), cell("c11"), cell("c12")),
                                            ),
                                        ),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            )
        }

        val tdStarts = SegmentPlanner.planSegment(gridDoc(StaggerOrder.TopToBottom), "s0", beat = 0)
            .tracks.associate { it.nodeId to it.startMs }
        assertEquals(mapOf("c00" to 0, "c01" to 0, "c02" to 0, "c10" to 100, "c11" to 100, "c12" to 100), tdStarts)

        val ltrStarts = SegmentPlanner.planSegment(gridDoc(StaggerOrder.LeftToRight), "s0", beat = 0)
            .tracks.associate { it.nodeId to it.startMs }
        assertEquals(mapOf("c00" to 0, "c10" to 0, "c01" to 100, "c11" to 100, "c02" to 200, "c12" to 200), ltrStarts)

        val readStarts = SegmentPlanner.planSegment(gridDoc(StaggerOrder.ReadingOrder), "s0", beat = 0)
            .tracks.associate { it.nodeId to it.startMs }
        assertEquals(mapOf("c00" to 0, "c01" to 100, "c02" to 200, "c10" to 300, "c11" to 400, "c12" to 500), readStarts)
    }

    @Test
    fun p1_nestedFramedFlowGridCrossSlideStaggerAndReflowTextMorphPiecesAndSpecimenBeat1Skip() {
        fun nestedGridSlide(id: String, yOffset: Double, staggerOrder: StaggerOrder?): SlideSpec {
            fun cell(cellId: String, label: String) = NodeSpec(
                id = "card-$id-$cellId",
                stack = ContainerPayload(
                    children = listOf(
                        NodeSpec(
                            id = cellId,
                            frame = FrameSpec(x = 16.0, y = 12.0),
                            text = TextPayload(value = RichTextValue.Plain(label)),
                        ),
                    ),
                ),
            )
            return SlideSpec(
                id = id,
                transition = staggerOrder?.let {
                    TransitionSpec(
                        type = TransitionType.MagicMove,
                        durationMs = 200,
                        stagger = Stagger(ms = 100, order = it),
                    )
                },
                nodes = listOf(
                    NodeSpec(
                        id = "grid-$id",
                        frame = FrameSpec(x = 100.0, y = yOffset, w = 600.0, h = 300.0),
                        column = ContainerPayload(
                            gap = 20.0,
                            children = listOf(
                                NodeSpec(
                                    id = "r0-$id",
                                    row = ContainerPayload(
                                        gap = 16.0,
                                        children = listOf(cell("c00", "A"), cell("c01", "B"), cell("c02", "C")),
                                    ),
                                ),
                                NodeSpec(
                                    id = "r1-$id",
                                    row = ContainerPayload(
                                        gap = 16.0,
                                        children = listOf(cell("c10", "D"), cell("c11", "E"), cell("c12", "F")),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            )
        }

        for ((order, expected) in listOf(
            StaggerOrder.TopToBottom to mapOf("c00" to 0, "c01" to 0, "c02" to 0, "c10" to 100, "c11" to 100, "c12" to 100),
            StaggerOrder.LeftToRight to mapOf("c00" to 0, "c10" to 0, "c01" to 100, "c11" to 100, "c02" to 200, "c12" to 200),
            StaggerOrder.ReadingOrder to mapOf("c00" to 0, "c01" to 100, "c02" to 200, "c10" to 300, "c11" to 400, "c12" to 500),
        )) {
            val doc = DeckDocument(
                id = "cross-grid-$order",
                slides = listOf(
                    nestedGridSlide("sA", 60.0, order),
                    nestedGridSlide("sB", 180.0, null),
                ),
            )
            val plan = SegmentPlanner.planTransition(doc, "sA", "sB")
            val moveTracks = plan.tracks.filter { it.kind == TrackKind.TransitionMove }
            assertEquals(expected, moveTracks.associate { it.nodeId to it.startMs }, "Failed $order cross-slide stagger")
            assertTrue(moveTracks.all { it.pieces == null && it.durationMs == 200 }, "Unchanged flow text must match move duration cleanly")
        }

        // Unchanged flow text + sibling shape under non-zero `phases.move.delayMs` stay in exact lockstep,
        // and changed text does NOT double-count `phases.move.delayMs`!
        val delayedFlowDoc = DeckDocument(
            id = "delayed-flow",
            slides = listOf(
                SlideSpec(
                    id = "sA",
                    transition = TransitionSpec(
                        type = TransitionType.MagicMove,
                        durationMs = 300,
                        phases = Phases(
                            exit = Timing(delayMs = 0, durationMs = 100),
                            move = Timing(delayMs = 80, durationMs = 300),
                            enter = Timing(delayMs = 80, durationMs = 100),
                        ),
                    ),
                    nodes = listOf(
                        NodeSpec(
                            id = "col",
                            frame = FrameSpec(x = 40.0, y = 40.0, w = 400.0, h = 240.0),
                            column = ContainerPayload(
                                children = listOf(
                                    NodeSpec("same-txt", text = TextPayload(RichTextValue.Plain("Unchanged"), size = 18.0)),
                                    NodeSpec("changed-txt", text = TextPayload(RichTextValue.Plain("Before"), size = 18.0)),
                                    NodeSpec("sib-shape", frame = FrameSpec(w = 40.0, h = 40.0), shape = ShapePayload(ShapeGeometry.Rect)),
                                ),
                            ),
                        ),
                    ),
                ),
                SlideSpec(
                    id = "sB",
                    nodes = listOf(
                        NodeSpec(
                            id = "row",
                            frame = FrameSpec(x = 80.0, y = 80.0, w = 500.0, h = 120.0),
                            row = ContainerPayload(
                                children = listOf(
                                    NodeSpec("same-txt", text = TextPayload(RichTextValue.Plain("Unchanged"), size = 18.0)),
                                    NodeSpec("changed-txt", text = TextPayload(RichTextValue.Plain("After"), size = 18.0)),
                                    NodeSpec("sib-shape", frame = FrameSpec(w = 60.0, h = 60.0), shape = ShapePayload(ShapeGeometry.Rect)),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val delayedPlan = SegmentPlanner.planTransition(delayedFlowDoc, "sA", "sB")
        val sameTxtTrack = delayedPlan.tracks.single { it.nodeId == "same-txt" }
        val changedTxtTrack = delayedPlan.tracks.single { it.nodeId == "changed-txt" }
        val sibShapeTrack = delayedPlan.tracks.single { it.nodeId == "sib-shape" }
        assertEquals(80, sameTxtTrack.startMs)
        assertEquals(380, sameTxtTrack.endMs)
        assertNull(sameTxtTrack.pieces)
        assertEquals(sameTxtTrack.startMs, sibShapeTrack.startMs)
        assertEquals(sameTxtTrack.endMs, sibShapeTrack.endMs)
        assertEquals(80, changedTxtTrack.startMs)
        assertEquals(380, changedTxtTrack.endMs)
        assertEquals(0, changedTxtTrack.pieces?.slot(Slot.SIZE_ADDRESS)?.startMs)

        // Descendants inside an unmatched Loupe and Connector nodes must stay on scene cross-fade path
        val loupeConnDoc = DeckDocument(
            id = "loupe-conn-boundary",
            slides = listOf(
                SlideSpec(
                    id = "a",
                    nodes = listOf(
                        NodeSpec(
                            id = "loupe-a",
                            frame = FrameSpec(x = 0.0, y = 0.0, w = 200.0, h = 200.0),
                            loupe = LoupePayload(
                                target = "trapped",
                                children = listOf(
                                    NodeSpec("trapped", frame = FrameSpec(x = 20.0, y = 20.0, w = 60.0, h = 30.0), shape = ShapePayload(ShapeGeometry.Rect)),
                                ),
                            ),
                        ),
                        NodeSpec("free", frame = FrameSpec(x = 260.0, y = 20.0, w = 60.0, h = 30.0), shape = ShapePayload(ShapeGeometry.Rect)),
                        NodeSpec("conn", connector = ConnectorPayload(from = "free", to = "trapped")),
                    ),
                ),
                SlideSpec(
                    id = "b",
                    nodes = listOf(
                        NodeSpec("trapped", frame = FrameSpec(x = 400.0, y = 20.0, w = 60.0, h = 30.0), shape = ShapePayload(ShapeGeometry.Rect)),
                        NodeSpec("free", frame = FrameSpec(x = 460.0, y = 20.0, w = 60.0, h = 30.0), shape = ShapePayload(ShapeGeometry.Rect)),
                        NodeSpec("conn", connector = ConnectorPayload(from = "free", to = "trapped")),
                    ),
                ),
            ),
        )
        val lcMatch = CrossSlideGeometry.matchCrossSlidePairs(loupeConnDoc.slides[0], loupeConnDoc.slides[1])
        assertEquals(listOf("free"), lcMatch.pairs.map { it.nodeId })

        // Unpresent Beat-1 unsized specimen inside a matched container must NOT reject the container on Beat 0,
        // while a weighted specimen inside an unbounded (shrink-wrapped) parent Row IS rejected.
        val specDoc = DeckDocument(
            id = "spec-beat1-and-unbounded",
            slides = listOf(
                SlideSpec(
                    id = "a",
                    transition = TransitionSpec(type = TransitionType.MagicMove),
                    beats = listOf(BeatSpec(), BeatSpec()),
                    nodes = listOf(
                        NodeSpec(
                            id = "container-with-beat1-spec",
                            frame = FrameSpec(x = 20.0, y = 20.0, w = 200.0, h = 100.0),
                            stack = ContainerPayload(
                                children = listOf(
                                    NodeSpec(
                                        id = "late-spec",
                                        specimen = SpecimenPayload(ref = "meter"),
                                        builds = listOf(Build.Exit(beat = 1, effect = "dissolve")),
                                    ),
                                ),
                            ),
                        ),
                        NodeSpec(
                            id = "unbounded-row",
                            frame = FrameSpec(x = 20.0, y = 160.0), // NO w!
                            row = ContainerPayload(
                                children = listOf(
                                    NodeSpec(
                                        id = "weighted-unbounded",
                                        weight = 1.0,
                                        frame = FrameSpec(h = 80.0),
                                        specimen = SpecimenPayload(ref = "meter"),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
                SlideSpec(
                    id = "b",
                    beats = listOf(BeatSpec(), BeatSpec()),
                    nodes = listOf(
                        NodeSpec(
                            id = "container-with-beat1-spec",
                            frame = FrameSpec(x = 220.0, y = 20.0, w = 200.0, h = 100.0),
                            stack = ContainerPayload(
                                children = listOf(
                                    NodeSpec(
                                        id = "late-spec",
                                        specimen = SpecimenPayload(ref = "meter"),
                                        builds = listOf(Build.Enter(beat = 1, effect = "dissolve")),
                                    ),
                                ),
                            ),
                        ),
                        NodeSpec(
                            id = "weighted-unbounded",
                            frame = FrameSpec(x = 220.0, y = 160.0, w = 120.0, h = 80.0),
                            specimen = SpecimenPayload(ref = "meter"),
                        ),
                    ),
                ),
            ),
        )
        val specMatch = CrossSlideGeometry.matchCrossSlidePairs(specDoc.slides[0], specDoc.slides[1])
        assertTrue("container-with-beat1-spec" in specMatch.pairs.map { it.nodeId })
        assertFalse("weighted-unbounded" in specMatch.pairs.map { it.nodeId })
        assertTrue(specMatch.lintIssues.any { it.code == "CROSS_SLIDE_SPECIMEN_MISSING_SIZE" && it.address.contains("weighted-unbounded") })
    }

    @Test
    fun section91_slidePlanAndLintCacheKeysInvalidateOnlyAffectedSlidesAndSharedDependencies() {
        val doc = DeckDocument(
            id = "cache-keys",
            defaultTheme = "main",
            themes = mapOf("main" to DeckThemeSpec(colors = mapOf("accent" to "#FF0000"))),
            palettes = mapOf("p0" to SlidePaletteSpec(canvas = "#111111"), "p3" to SlidePaletteSpec(canvas = "#222222")),
            slides = (0..4).map { i ->
                SlideSpec(
                    id = "s$i",
                    palette = if (i == 0) "p0" else if (i == 3) "p3" else null,
                    beats = listOf(BeatSpec()),
                    nodes = listOfNotNull(
                        NodeSpec(id = "n$i", text = TextPayload(value = RichTextValue.Plain("Slide $i"))),
                        if (i == 0) NodeSpec(id = "img0", image = ImagePayload(src = "img.png")) else null,
                    ),
                )
            },
        )
        val keysBefore = (0..4).map { SegmentPlanner.slidePlanCacheKey(doc, it) }

        // Editing slide 2 invalidates slides 1, 2, 3 ONLY — slides 0 and 4 MUST stay unchanged!
        val editedSlide2 = doc.copy(
            slides = doc.slides.mapIndexed { idx, s ->
                if (idx != 2) s else s.copy(
                    nodes = listOf(NodeSpec(id = "n2", text = TextPayload(value = RichTextValue.Plain("Edited Slide 2")))),
                )
            },
        )
        val keysAfterSlide2 = (0..4).map { SegmentPlanner.slidePlanCacheKey(editedSlide2, it) }
        assertEquals(keysBefore[0], keysAfterSlide2[0], "Slide 0 must NOT invalidate when editing Slide 2")
        assertNotEquals(keysBefore[1], keysAfterSlide2[1], "Slide 1 (neighbor) must invalidate")
        assertNotEquals(keysBefore[2], keysAfterSlide2[2], "Slide 2 itself must invalidate")
        assertNotEquals(keysBefore[3], keysAfterSlide2[3], "Slide 3 (neighbor) must invalidate")
        assertEquals(keysBefore[4], keysAfterSlide2[4], "Slide 4 must NOT invalidate when editing Slide 2")

        // Editing palette `p3` invalidates ONLY slide 3 (not slide 0)!
        val editedPalette = doc.copy(palettes = doc.palettes + ("p3" to SlidePaletteSpec(canvas = "#333333")))
        assertEquals(keysBefore[0], SegmentPlanner.slidePlanCacheKey(editedPalette, 0))
        assertNotEquals(keysBefore[3], SegmentPlanner.slidePlanCacheKey(editedPalette, 3))

        // Editing active theme invalidates every slide
        val editedTheme = doc.copy(themes = mapOf("main" to DeckThemeSpec(colors = mapOf("accent" to "#00FF00"))))
        assertNotEquals(keysBefore[0], SegmentPlanner.slidePlanCacheKey(editedTheme, 0))
        assertNotEquals(keysBefore[4], SegmentPlanner.slidePlanCacheKey(editedTheme, 4))

        // Referenced asset fingerprint change invalidates slide 0's lint cache key, NOT unreferenced slide 4!
        val lintA0 = SegmentPlanner.slideLintCacheKey(doc, 0, assetFingerprints = mapOf("img.png" to "h1"))
        val lintB0 = SegmentPlanner.slideLintCacheKey(doc, 0, assetFingerprints = mapOf("img.png" to "h2"))
        assertNotEquals(lintA0, lintB0)
        val lintA4 = SegmentPlanner.slideLintCacheKey(doc, 4, assetFingerprints = mapOf("img.png" to "h1"))
        val lintB4 = SegmentPlanner.slideLintCacheKey(doc, 4, assetFingerprints = mapOf("img.png" to "h2"))
        assertEquals(lintA4, lintB4, "Unreferenced asset change must NOT invalidate slide 4 lint key")
    }

    @Test
    fun effectDefaultCurvePreservationKeynotePresetsContainerDescendantsAndCodeHighlightWarp() {
        // 1. Exact Apple Keynote Bezier presets
        assertEquals(Curve.Bezier(0.4063, 0.4998, 0.6016, 1.0), CurveEvaluator.PRESET_BEZIERS["keynote.moveIn"])
        assertEquals(Curve.Bezier(0.4971, 0.0, 0.4981, 0.996), CurveEvaluator.PRESET_BEZIERS["keynote.push"])
        assertEquals(Curve.Bezier(0.5532, 0.8018, 0.6019, 1.0), CurveEvaluator.PRESET_BEZIERS["keynote.slide"])
        assertEquals(Curve.Bezier(0.3747, 0.0, 0.4752, 0.0877), CurveEvaluator.PRESET_BEZIERS["keynote.reveal"])
        assertEquals(Curve.Bezier(0.311, 0.208, 0.2013, 1.0), CurveEvaluator.PRESET_BEZIERS["keynote.fadeMovePos"])
        assertEquals(Curve.Bezier(0.7028, 0.0, 0.3007, 0.9992), CurveEvaluator.PRESET_BEZIERS["keynote.fadeMoveAlpha"])
        assertEquals(Curve.Bezier(0.7003, 0.0, 0.5595, 0.7566), CurveEvaluator.PRESET_BEZIERS["keynote.fadeMoveExit"])
        assertEquals(Curve.Bezier(0.742, 0.0, 0.642, 1.0), CurveEvaluator.PRESET_BEZIERS["keynote.zoomBigExit"])

        val moveInAtQuarter = CurveEvaluator.evaluateFraction(Curve.Preset("keynote.moveIn"), 0.25f)
        val expectedMoveIn = CurveEvaluator.evaluateFraction(Curve.Bezier(0.4063, 0.4998, 0.6016, 1.0), 0.25f)
        assertTrue(abs(moveInAtQuarter - expectedMoveIn) < 1e-6f, "keynote.moveIn must evaluate its Apple Bezier")

        // 2. Omitted build/phase/effect curve preserves useEffectDefaultCurve=true & effectCurveOverride=null,
        //    while explicit build.curve sets useEffectDefaultCurve=false & effectCurveOverride=authored!
        val curveDoc = DeckDocument(
            id = "curve-precedence",
            themes = mapOf(
                "t" to DeckThemeSpec(
                    motion = ThemeMotionDefaults(curve = Curve.Linear, durationMs = 300),
                ),
            ),
            defaultTheme = "t",
            slides = listOf(
                SlideSpec(
                    id = "s0",
                    beats = listOf(BeatSpec()),
                    nodes = listOf(
                        NodeSpec(
                            id = "default-fademove",
                            shape = ShapePayload(geometry = ShapeGeometry.Rect),
                            builds = listOf(Build.Enter(beat = 0, effect = "fadeMove", durationMs = 400)),
                        ),
                        NodeSpec(
                            id = "explicit-fademove",
                            shape = ShapePayload(geometry = ShapeGeometry.Rect),
                            builds = listOf(Build.Enter(beat = 0, effect = "fadeMove", durationMs = 400, curve = Curve.Linear)),
                        ),
                        NodeSpec(
                            id = "default-movein",
                            shape = ShapePayload(geometry = ShapeGeometry.Rect),
                            builds = listOf(Build.Enter(beat = 0, effect = "moveIn", durationMs = 400)),
                        ),
                    ),
                ),
            ),
        )
        val curvePlan = SegmentPlanner.planSegment(curveDoc, "s0", beat = 0)
        val defaultFadeTrack = curvePlan.tracks.single { it.nodeId == "default-fademove" }
        val explicitFadeTrack = curvePlan.tracks.single { it.nodeId == "explicit-fademove" }
        val defaultMoveInTrack = curvePlan.tracks.single { it.nodeId == "default-movein" }
        assertTrue(defaultFadeTrack.useEffectDefaultCurve, "Omitted curve on fadeMove must preserve effect-default curve")
        assertNull(defaultFadeTrack.effectCurveOverride)
        assertEquals(Curve.Preset("keynote.fadeMovePos"), defaultFadeTrack.curve)
        assertFalse(explicitFadeTrack.useEffectDefaultCurve, "Explicit build.curve must override effect-default curve")
        assertEquals(Curve.Linear, explicitFadeTrack.effectCurveOverride)
        assertTrue(defaultMoveInTrack.useEffectDefaultCurve)
        assertEquals(Curve.Preset("keynote.moveIn"), defaultMoveInTrack.curve)

        // 3. Magic Move planTransition must NOT emit unmatched Exit/Enter tracks
        //    for descendants inside a matched travelling container!
        val magicContainerDoc = DeckDocument(
            id = "magic-container",
            slides = listOf(
                SlideSpec(
                    id = "sA",
                    transition = TransitionSpec(
                        type = TransitionType.MagicMove,
                        durationMs = 500,
                        phases = Phases(
                            exit = Timing(durationMs = 200),
                            move = Timing(durationMs = 500),
                            enter = Timing(durationMs = 200),
                        ),
                    ),
                    nodes = listOf(
                        NodeSpec(
                            id = "card",
                            frame = FrameSpec(x = 40.0, y = 40.0, w = 240.0, h = 160.0),
                            stack = ContainerPayload(
                                children = listOf(
                                    NodeSpec(
                                        id = "matched-child",
                                        frame = FrameSpec(x = 12.0, y = 12.0, w = 100.0, h = 40.0),
                                        shape = ShapePayload(geometry = ShapeGeometry.Rect),
                                    ),
                                    NodeSpec(
                                        id = "only-in-a",
                                        frame = FrameSpec(x = 12.0, y = 70.0, w = 80.0, h = 30.0),
                                        shape = ShapePayload(geometry = ShapeGeometry.Rect),
                                    ),
                                ),
                            ),
                        ),
                        NodeSpec(
                            id = "outside-exit",
                            frame = FrameSpec(x = 400.0, y = 40.0, w = 80.0, h = 40.0),
                            shape = ShapePayload(geometry = ShapeGeometry.Rect),
                        ),
                    ),
                ),
                SlideSpec(
                    id = "sB",
                    nodes = listOf(
                        NodeSpec(
                            id = "card",
                            frame = FrameSpec(x = 120.0, y = 80.0, w = 300.0, h = 200.0),
                            stack = ContainerPayload(
                                children = listOf(
                                    NodeSpec(
                                        id = "matched-child",
                                        frame = FrameSpec(x = 24.0, y = 24.0, w = 140.0, h = 50.0),
                                        shape = ShapePayload(geometry = ShapeGeometry.Rect),
                                    ),
                                    NodeSpec(
                                        id = "only-in-b",
                                        frame = FrameSpec(x = 24.0, y = 90.0, w = 90.0, h = 30.0),
                                        shape = ShapePayload(geometry = ShapeGeometry.Rect),
                                    ),
                                ),
                            ),
                        ),
                        NodeSpec(
                            id = "outside-enter",
                            frame = FrameSpec(x = 420.0, y = 120.0, w = 80.0, h = 40.0),
                            shape = ShapePayload(geometry = ShapeGeometry.Rect),
                        ),
                    ),
                ),
            ),
        )
        val mmPlan = SegmentPlanner.planTransition(magicContainerDoc, "sA", "sB")
        val plannedNodeIds = mmPlan.tracks.map { it.nodeId }.toSet()
        assertTrue("card" in plannedNodeIds, "Outermost matched container must have TransitionMove track")
        assertTrue("outside-exit" in plannedNodeIds, "Unmatched top-level node on A must have TransitionExit track")
        assertTrue("outside-enter" in plannedNodeIds, "Unmatched top-level node on B must have TransitionEnter track")
        assertFalse("matched-child" in plannedNodeIds, "Descendant of matched container MUST NOT get separate Exit/Enter tracks")
        assertFalse("only-in-a" in plannedNodeIds, "Descendant of matched container MUST NOT get separate Exit track")
        assertFalse("only-in-b" in plannedNodeIds, "Descendant of matched container MUST NOT get separate Enter track")

        // 4. Code change modifying ONLY highlightLines (not value) MUST emit warpSchedule
        //    in BOTH full motion and reduced motion!
        val hlCodeDoc = DeckDocument(
            id = "hl-code-warp",
            slides = listOf(
                SlideSpec(
                    id = "s0",
                    beats = listOf(BeatSpec(), BeatSpec()),
                    nodes = listOf(
                        NodeSpec(
                            id = "code1",
                            frame = FrameSpec(x = 20.0, y = 20.0, w = 320.0, h = 120.0),
                            code = CodePayload(
                                language = "kotlin",
                                value = "val a = 1\nval b = 2",
                                highlightLines = listOf(1),
                            ),
                            builds = listOf(
                                Build.Change(
                                    beat = 1,
                                    durationMs = 80,
                                    set = buildJsonObject {
                                        putJsonObject("code") {
                                            put("highlightLines", kotlinx.serialization.json.JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive(2))))
                                        }
                                    },
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val hlFull = SegmentPlanner.planSegment(hlCodeDoc, "s0", beat = 1, reducedMotion = false)
        val hlTrackFull = hlFull.tracks.single { it.nodeId == "code1" }
        assertNotNull(hlTrackFull.warpSchedule, "highlightLines-only code change must emit warpSchedule in full motion")
        assertEquals(warpSchedule(80, reducedMotion = false).totalSpanMs, hlFull.segmentMs)

        val hlReduced = SegmentPlanner.planSegment(hlCodeDoc, "s0", beat = 1, reducedMotion = true)
        val hlTrackReduced = hlReduced.tracks.single { it.nodeId == "code1" }
        assertNotNull(hlTrackReduced.warpSchedule, "highlightLines-only code change must emit warpSchedule in reduced motion")
        assertEquals(warpSchedule(80, reducedMotion = true).totalSpanMs, hlReduced.segmentMs)

        // 5. Case-insensitive Iris(out) layer order & whole-slide Keynote default transition curves
        assertEquals(SlideLayerOrder.AOverB, CrossSlideGeometry.transitionLayerOrder(TransitionSpec(TransitionType.Iris, direction = "Out")))
        assertEquals(SlideLayerOrder.AOverB, CrossSlideGeometry.transitionLayerOrder(TransitionSpec(TransitionType.Iris, direction = "OUT")))
        assertEquals(SlideLayerOrder.AOverB, CrossSlideGeometry.transitionLayerOrder(TransitionSpec(TransitionType.Iris, direction = "close")))
        assertEquals(SlideLayerOrder.AOverB, CrossSlideGeometry.transitionLayerOrder(TransitionSpec(TransitionType.Iris, direction = "shrink")))

        val pushDoc = DeckDocument(
            id = "push-curve",
            slides = listOf(
                SlideSpec(id = "a", transition = TransitionSpec(type = TransitionType.Push, durationMs = 400)),
                SlideSpec(id = "b"),
            ),
        )
        val pushPlan = SegmentPlanner.planTransition(pushDoc, "a", "b")
        assertEquals(Curve.Preset("keynote.push"), pushPlan.tracks.single().curve)
    }
}
