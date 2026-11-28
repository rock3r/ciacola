package dev.sebastiano.ciacola.document

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Channel evaluation, stagger shaping, schema round-trip and validation for motion presets.
 */
class MotionChannelsTest {

    private val eps = 1e-6

    // ── Stagger shaping ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `plain linear stagger is exactly rank times ms`() {
        val stagger = Stagger(ms = 45)
        for (rank in 0 until 12) {
            assertEquals(rank * 45, StaggerShaping.delayMs(stagger, rank, rankCount = 12))
        }
    }

    @Test
    fun `fromCenter and fromEdges mirror around the middle rank`() {
        val center = Stagger(ms = 100, distribution = StaggerDistribution.FromCenter)
        // Five ranks span 400 ms; the middle rank starts first and the ends last.
        assertEquals(listOf(400, 200, 0, 200, 400), (0 until 5).map { StaggerShaping.delayMs(center, it, 5) })

        val edges = center.copy(distribution = StaggerDistribution.FromEdges)
        assertEquals(listOf(0, 200, 400, 200, 0), (0 until 5).map { StaggerShaping.delayMs(edges, it, 5) })
    }

    @Test
    fun `totalMs and curve reshape the spread`() {
        val total = Stagger(ms = 999, totalMs = 300)
        assertEquals(listOf(0, 100, 200, 300), (0 until 4).map { StaggerShaping.delayMs(total, it, 4) })

        val eased = Stagger(totalMs = 1000, curve = Curve.EaseIn)
        val delays = (0 until 5).map { StaggerShaping.delayMs(eased, it, 5) }
        assertEquals(0, delays.first())
        assertEquals(1000, delays.last())
        // Ease-in bunches the early ranks together.
        assertTrue(delays[1] - delays[0] < delays[4] - delays[3], "$delays")
    }

    @Test
    fun `random distribution is deterministic per seed and stays inside the spread`() {
        val a = Stagger(totalMs = 500, distribution = StaggerDistribution.Random, seed = 7)
        val first = (0 until 20).map { StaggerShaping.delayMs(a, it, 20) }
        assertEquals(first, (0 until 20).map { StaggerShaping.delayMs(a, it, 20) })
        assertTrue(first.all { it in 0..500 }, "$first")
        assertNotEquals(first, (0 until 20).map { StaggerShaping.delayMs(a.copy(seed = 8), it, 20) })
        assertEquals(0, StaggerShaping.delayMs(a, 0, rankCount = 1))
    }

    @Test
    fun `planner applies the stagger distribution to text pieces`() {
        val doc = DeckDocument(
            format = CURRENT_DECK_FORMAT_VERSION,
            id = "stagger-shape",
            slides = listOf(
                SlideSpec(
                    id = "s0",
                    beats = listOf(BeatSpec(), BeatSpec()),
                    nodes = listOf(
                        NodeSpec(
                            id = "t",
                            frame = FrameSpec(0.0, 0.0, 800.0, 200.0),
                            text = TextPayload(value = RichTextValue.Plain("abcde")),
                            builds = listOf(
                                Build.Enter(
                                    beat = 1,
                                    effect = "rise",
                                    by = DeliveryBy.Character,
                                    stagger = Stagger(ms = 50, distribution = StaggerDistribution.FromCenter),
                                    durationMs = 400,
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val track = SegmentPlanner.planSegment(doc, "s0", beat = 1).tracks.single { it.nodeId == "t" }
        val starts = track.pieces!!.slots.map { it.startMs }
        // Five characters at 50 ms per rank span 200 ms, spreading outwards from the middle one.
        assertEquals(listOf(200, 100, 0, 100, 200), starts)
        assertEquals(600, track.endMs - track.startMs)
    }

    // ── Channel math ────────────────────────────────────────────────────────────────────────────

    @Test
    fun `tween honours its window and curve`() {
        val spec = MotionChannelSpec(from = 0.0, to = 10.0, window = listOf(0.5, 1.0))
        assertEquals(0.0, MotionChannelMath.evaluate(spec, 0.25f, 0, restValue = 0.0), eps)
        assertEquals(5.0, MotionChannelMath.evaluate(spec, 0.75f, 0, restValue = 0.0), eps)
        assertEquals(10.0, MotionChannelMath.evaluate(spec, 1f, 0, restValue = 0.0), eps)

        val eased = spec.copy(window = null, curve = Curve.EaseIn)
        assertTrue(MotionChannelMath.evaluate(eased, 0.5f, 0, restValue = 0.0) < 5.0)
    }

    @Test
    fun `missing tween ends fall back to the rest value`() {
        val onlyFrom = MotionChannelSpec(from = 0.0)
        assertEquals(0.5, MotionChannelMath.evaluate(onlyFrom, 0.5f, 0, restValue = 1.0), eps)
        assertEquals(1.0, MotionChannelMath.evaluate(MotionChannelSpec(), 0.3f, 0, restValue = 1.0), eps)
    }

    @Test
    fun `keyframes interpolate segment by segment with per-stop curves`() {
        val spec = MotionChannelSpec(
            keyframes = listOf(
                MotionKeyframeSpec(at = 0.0, value = 0.0),
                MotionKeyframeSpec(at = 0.6, value = 1.2),
                MotionKeyframeSpec(at = 1.0, value = 1.0, curve = Curve.EaseOut),
            ),
        )
        assertEquals(0.0, MotionChannelMath.evaluate(spec, 0f, 0, 0.0), eps)
        assertEquals(0.6, MotionChannelMath.evaluate(spec, 0.3f, 0, 0.0), 1e-5)
        assertEquals(1.2, MotionChannelMath.evaluate(spec, 0.6f, 0, 0.0), 1e-5)
        assertEquals(1.0, MotionChannelMath.evaluate(spec, 1f, 0, 0.0), eps)
    }

    @Test
    fun `waves travel with phasePerPiece and fade to rest at both ends`() {
        val wave = MotionWaveSpec(amplitude = 1.0, cycles = 1.0, phasePerPiece = 0.25)
        val spec = MotionChannelSpec(wave = wave)
        assertEquals(1.0, MotionChannelMath.evaluate(spec, 0.25f, pieceIndex = 0, restValue = 0.0), 1e-5)
        // One quarter cycle later in phase, the next piece is at zero when the first peaks.
        assertEquals(0.0, MotionChannelMath.evaluate(spec, 0.25f, pieceIndex = 1, restValue = 0.0), 1e-5)

        val faded = MotionChannelSpec(wave = wave.copy(phasePerPiece = 0.0, phase = 0.25, fade = 0.2))
        assertEquals(0.0, MotionChannelMath.evaluate(faded, 0f, 0, 0.0), eps)
        assertEquals(0.0, MotionChannelMath.evaluate(faded, 1f, 0, 0.0), eps)

        val perLine = MotionChannelSpec(wave = MotionWaveSpec(amplitude = 1.0, phasePerLine = 0.5))
        assertEquals(
            -MotionChannelMath.evaluate(perLine, 0.25f, 0, 0.0, lineIndex = 0),
            MotionChannelMath.evaluate(perLine, 0.25f, 0, 0.0, lineIndex = 1),
            1e-5,
        )
    }

    @Test
    fun `wave shapes stay within unit range and noise is deterministic`() {
        for (shape in WaveShape.entries) {
            for (i in 0..40) {
                val v = MotionChannelMath.shapeAt(shape, i / 13.0, seed = 3)
                assertTrue(v in -1.0..1.0, "$shape at $i = $v")
            }
        }
        assertEquals(MotionChannelMath.shapeAt(WaveShape.Noise, 2.3, 9), MotionChannelMath.shapeAt(WaveShape.Noise, 2.3, 9))
        assertEquals(1.0, MotionChannelMath.shapeAt(WaveShape.Triangle, 0.25), eps)
        assertEquals(-1.0, MotionChannelMath.shapeAt(WaveShape.Triangle, 0.75), eps)
    }

    @Test
    fun `overrides merge field by field over preset defaults`() {
        val base = MotionChannelSpec(from = 0.9, to = 0.0, curve = Curve.EaseOut, wave = MotionWaveSpec(amplitude = 1.0, cycles = 2.0))
        val merged = MotionChannelSpec(curve = Curve.Linear, wave = MotionWaveSpec(cycles = 4.0)).mergedOver(base)
        assertEquals(0.9, merged.from)
        assertEquals(0.0, merged.to)
        assertEquals(Curve.Linear, merged.curve)
        assertEquals(MotionWaveSpec(amplitude = 1.0, cycles = 4.0), merged.wave)
    }

    // ── Schema ──────────────────────────────────────────────────────────────────────────────────

    private fun presetDocument(
        channels: Map<String, MotionChannelSpec>,
        stagger: Stagger? = Stagger(ms = 40, distribution = StaggerDistribution.FromCenter, curve = Curve.EaseOut),
    ) = DeckDocument(
        format = CURRENT_DECK_FORMAT_VERSION,
        id = "motion-presets",
        slides = listOf(
            SlideSpec(
                id = "s0",
                beats = listOf(BeatSpec(), BeatSpec()),
                nodes = listOf(
                    NodeSpec(
                        id = "title",
                        frame = FrameSpec(100.0, 100.0, 1200.0, 200.0),
                        text = TextPayload(value = RichTextValue.Plain("Ship it")),
                        builds = listOf(
                            Build.Enter(
                                beat = 1,
                                effect = "rise",
                                args = mapOf("distance" to kotlinx.serialization.json.JsonPrimitive(1.2)),
                                channels = channels,
                                by = DeliveryBy.Character,
                                stagger = stagger,
                            ),
                        ),
                    ),
                ),
            ),
        ),
    )

    @Test
    fun `channels and stagger shape round-trip canonically`() {
        val doc = presetDocument(
            mapOf(
                "offsetY" to MotionChannelSpec(curve = Curve.Bezier(0.2, 0.9, 0.1, 1.0)),
                "rotation" to MotionChannelSpec(
                    keyframes = listOf(MotionKeyframeSpec(0.0, -8.0), MotionKeyframeSpec(1.0, 0.0, Curve.EaseOut)),
                ),
                "blur" to MotionChannelSpec(wave = MotionWaveSpec(amplitude = 0.1, shape = WaveShape.Noise, seed = 4)),
            ),
        )
        val printed = CanonicalJson.print(doc)
        assertTrue(printed.contains("\"distribution\": \"fromCenter\""), printed)
        // Channel names print sorted like other name-keyed maps.
        assertTrue(printed.indexOf("\"blur\"") < printed.indexOf("\"offsetY\""), printed)
        assertTrue(printed.indexOf("\"offsetY\"") < printed.indexOf("\"rotation\""), printed)

        val decoded = assertIs<DeckDecodeResult.Success>(DeckDocumentCodec.decode(printed))
        assertEquals(doc, decoded.document)
        assertEquals(printed, CanonicalJson.print(decoded.document))
        assertTrue(DeckDocumentCodec.validate(doc).none { it.code.contains("MOTION") }, "${DeckDocumentCodec.validate(doc)}")

        val plain = presetDocument(emptyMap(), stagger = null)
        val plainPrinted = CanonicalJson.print(plain)
        assertTrue("\"channels\"" !in plainPrinted && "\"distribution\"" !in plainPrinted, plainPrinted)
    }

    @Test
    fun `invalid channels are reported with precise addresses`() {
        val doc = presetDocument(
            mapOf(
                "wobble" to MotionChannelSpec(from = 1.0),
                "opacity" to MotionChannelSpec(window = listOf(0.8, 0.2)),
                "offsetY" to MotionChannelSpec(
                    keyframes = listOf(MotionKeyframeSpec(0.7, 1.0), MotionKeyframeSpec(0.2, 0.0)),
                ),
                "scale" to MotionChannelSpec(wave = MotionWaveSpec(fade = 0.9)),
            ),
            stagger = Stagger(totalMs = -5),
        )
        val issues = DeckDocumentCodec.validate(doc)
        val byCode = issues.groupBy { it.code }
        assertEquals(IssueSeverity.LintWarning, byCode.getValue("UNKNOWN_MOTION_CHANNEL").single().severity)
        assertTrue(byCode.getValue("INVALID_MOTION_WINDOW").single().address.endsWith("/channels/opacity/window"), "$issues")
        assertTrue(byCode.getValue("MOTION_KEYFRAMES_UNSORTED").single().address.endsWith("/channels/offsetY/keyframes/1"), "$issues")
        assertTrue(byCode.getValue("INVALID_MOTION_WAVE").single().address.endsWith("/channels/scale/wave/fade"), "$issues")
        assertTrue("NEGATIVE_STAGGER_TOTAL_MS" in byCode, "$issues")
    }

    @Test
    fun `example motion presets deck decodes without validation errors`() {
        val file = listOf(
            java.io.File("docs/decks/motion-presets.deck.json"),
            java.io.File("../docs/decks/motion-presets.deck.json"),
        ).first { it.isFile }
        val decoded = assertIs<DeckDecodeResult.Success>(DeckDocumentCodec.decode(file.readText()))
        val problems = DeckDocumentCodec.validate(decoded.document).filter {
            it.severity == IssueSeverity.LoadError || it.severity == IssueSeverity.LintError
        }
        assertTrue(problems.isEmpty(), "$problems")
    }

    @Test
    fun `presets are registered with declared arguments`() {
        for (id in MotionPresetDeclarations.ids) {
            val decl = BuildEffectRegistry.Standard.find(id)
            assertTrue(decl != null && decl.supportsPieceDelivery && decl.args.isNotEmpty(), id)
        }
        val unknownArg = presetDocument(emptyMap()).let { d ->
            val node = d.slides.single().nodes.single()
            val build = node.builds.single() as Build.Enter
            d.copy(
                slides = listOf(
                    d.slides.single().copy(
                        nodes = listOf(
                            node.copy(builds = listOf(build.copy(args = build.args + ("bogus" to kotlinx.serialization.json.JsonPrimitive(1))))),
                        ),
                    ),
                ),
            )
        }
        assertTrue(DeckDocumentCodec.validate(unknownArg).any { it.code == "EFFECT_ARG_UNDECLAREDKEY" })
    }
}
