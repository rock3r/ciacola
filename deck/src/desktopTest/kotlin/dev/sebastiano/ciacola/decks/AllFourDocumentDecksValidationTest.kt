package dev.sebastiano.ciacola.decks

import dev.sebastiano.ciacola.document.BuildEffectRegistry
import dev.sebastiano.ciacola.document.CanonicalJson
import dev.sebastiano.ciacola.document.CrossSlideGeometry
import dev.sebastiano.ciacola.document.DeckDecodeResult
import dev.sebastiano.ciacola.document.DeckDocument
import dev.sebastiano.ciacola.document.DeckDocumentCodec
import dev.sebastiano.ciacola.document.IssueSeverity
import dev.sebastiano.ciacola.document.NodeSpec
import dev.sebastiano.ciacola.document.ParamDiagnosticKind
import dev.sebastiano.ciacola.document.ParamResolver
import dev.sebastiano.ciacola.document.SegmentPlanner
import dev.sebastiano.ciacola.document.SlideSpec
import dev.sebastiano.ciacola.replications.shaders.ShadersSpecimenIds
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

class AllFourDocumentDecksValidationTest {

    private data class DeckExpectation(
        val registration: DocumentDeckRegistration,
        val expectedSlideCount: Int,
        val expectedSpecimenCountInRegistry: Int,
        val expectedUsedSpecimenCount: Int,
    )

    private val expectations: List<DeckExpectation> = listOf(
        DeckExpectation(
            registration = AllDocumentDecks.foundation,
            expectedSlideCount = 5,
            expectedSpecimenCountInRegistry = 2,
            expectedUsedSpecimenCount = 2,
        ),
        DeckExpectation(
            registration = AllDocumentDecks.shadersQuickie2025,
            expectedSlideCount = 48,
            expectedSpecimenCountInRegistry = 14,
            expectedUsedSpecimenCount = 14,
        ),
        DeckExpectation(
            registration = AllDocumentDecks.jewelTalk2024,
            expectedSlideCount = 75,
            expectedSpecimenCountInRegistry = 20,
            expectedUsedSpecimenCount = 20,
        ),
        DeckExpectation(
            registration = AllDocumentDecks.notJustPlainText2024,
            expectedSlideCount = 136,
            expectedSpecimenCountInRegistry = 2,
            expectedUsedSpecimenCount = 2,
        ),
    )

    @Test
    fun `all four decks decode cleanly with zero errors, zero placeholders, and all assets present`() {
        val allFailures = mutableListOf<String>()
        val summary = StringBuilder()
        for (exp in expectations) {
            val reg = exp.registration
            val rawText = loadResourceText(reg.resourcePath)
            val result = DeckDocumentCodec.decode(
                rawText = rawText,
                specimens = reg.specimens,
                effects = BuildEffectRegistry.Standard,
                assetExists = { src -> deckAssetExists(reg, src) },
            )

            val success = when (result) {
                is DeckDecodeResult.Success -> result
                is DeckDecodeResult.ReadOnlyNewerFormat -> {
                    allFailures += "${reg.id}: unexpected ReadOnlyNewerFormat: ${result.message}"
                    continue
                }
                is DeckDecodeResult.Failure -> {
                    allFailures += "${reg.id}: decode failed with ${result.errors.size} errors:\n" +
                        result.errors.joinToString("\n") { "  [${it.severity}] ${it.code} @ ${it.address}: ${it.message}" }
                    continue
                }
            }

            val doc = success.document
            val lintErrors = success.lintIssues.filter { it.severity == IssueSeverity.LintError }
            val lintWarnings = success.lintIssues.filter { it.severity == IssueSeverity.LintWarning }
            if (lintErrors.isNotEmpty()) {
                allFailures += "${reg.id}: ${lintErrors.size} lint error(s):\n" +
                    lintErrors.joinToString("\n") { "  [${it.severity}] ${it.code} @ ${it.address}: ${it.message}" }
            }
            if (lintWarnings.isNotEmpty()) {
                allFailures += "${reg.id}: ${lintWarnings.size} lint warning(s):\n" +
                    lintWarnings.joinToString("\n") { "  [${it.severity}] ${it.code} @ ${it.address}: ${it.message}" }
            }

            // Exact slide count
            if (doc.slides.size != exp.expectedSlideCount) {
                allFailures += "${reg.id}: expected ${exp.expectedSlideCount} slides, got ${doc.slides.size}"
            }

            // Verify every bundled font resource exists on classpath
            val missingFonts = doc.fonts
                .filter { it.isBundled }
                .mapNotNull { it.src }
                .filterNot { classpathResourceExists(it) }
            if (missingFonts.isNotEmpty()) {
                allFailures += "${reg.id}: missing bundled font resource(s): $missingFonts"
            }

            // Collect all specimen nodes recursively across all slides
            val allNodes = doc.slides.flatMap { flattenNodes(it.nodes) }
            val specimenRefsUsed = allNodes.mapNotNull { it.specimen?.ref }.toSet()

            if ("todo.video" in specimenRefsUsed) {
                allFailures += "${reg.id}: contains unresolved todo.video placeholder specimen"
            }
            if (specimenRefsUsed.size != exp.expectedUsedSpecimenCount) {
                allFailures += "${reg.id}: expected ${exp.expectedUsedSpecimenCount} distinct specimen refs, got ${specimenRefsUsed.size} ($specimenRefsUsed)"
            }

            // Verify loopPeriodsMs matches every animated specimen's declared loop("time") period bidirectionally
            val expectedLoopPeriods = linkedMapOf<String, Long>()
            for (ref in specimenRefsUsed) {
                val decl = reg.specimens.find(ref)
                if (decl == null) {
                    allFailures += "${reg.id}: specimen '$ref' missing from registry"
                    continue
                }
                val timeLoop = decl.loop("time")
                if (timeLoop != null) {
                    expectedLoopPeriods["$ref/time"] = timeLoop.periodMs
                }
            }
            if (doc.loopPeriodsMs != expectedLoopPeriods) {
                allFailures += "${reg.id}: loopPeriodsMs mismatch: actual=${doc.loopPeriodsMs}, expected=$expectedLoopPeriods"
            }

            val totalBeats = doc.slides.sumOf { it.beats.size }
            summary.appendLine(
                "${reg.id}: slides=${doc.slides.size}, beats=$totalBeats, " +
                    "specimens=${specimenRefsUsed.size}/${reg.specimens.all.size}, " +
                    "fonts=${doc.fonts.size}, warnings=${lintWarnings.size}" +
                    if (lintWarnings.isNotEmpty()) " (${lintWarnings.groupBy { it.code }.mapValues { it.value.size }})" else "",
            )
        }
        println(summary.toString())
        if (allFailures.isNotEmpty()) {
            fail(allFailures.joinToString("\n\n"))
        }
    }

    @Test
    fun `all four checked-in deck json files are byte-identical after CanonicalJson round-trip`() {
        for (exp in expectations) {
            val reg = exp.registration
            val rawText = loadResourceText(reg.resourcePath)
            val result = DeckDocumentCodec.decode(
                rawText = rawText,
                specimens = reg.specimens,
                effects = BuildEffectRegistry.Standard,
                assetExists = { src -> deckAssetExists(reg, src) },
            )
            val doc = (result as? DeckDecodeResult.Success)?.document
                ?: fail("${reg.id}: failed to decode before canonical check: $result")

            val reprinted = CanonicalJson.print(doc)
            if (reprinted != rawText) {
                val diffPreview = firstDiffPreview(rawText, reprinted)
                fail("${reg.id}: not byte-canonical (raw=${rawText.length} chars, canonical=${reprinted.length} chars).\n$diffPreview")
            }
        }
    }

    @Test
    fun `all beat segments and cross-slide transitions plan cleanly across all 264 slides`() {
        var totalSlides = 0
        var totalBeatSegments = 0
        var totalTransitions = 0

        for (exp in expectations) {
            val reg = exp.registration
            val doc = decodeOrFail(reg)

            for (slide in doc.slides) {
                totalSlides++
                for (beatIdx in slide.beats.indices) {
                    for (reducedMotion in listOf(false, true)) {
                        val plan = SegmentPlanner.planSegment(
                            document = doc,
                            slideId = slide.id,
                            beat = beatIdx,
                            reducedMotion = reducedMotion,
                            specimens = reg.specimens,
                            effects = BuildEffectRegistry.Standard,
                        )
                        assertTrue(
                            plan.segmentMs >= 0,
                            "${reg.id}/${slide.id}@beat$beatIdx (rm=$reducedMotion): negative segmentMs=${plan.segmentMs}",
                        )
                        for (track in plan.tracks) {
                            assertTrue(
                                track.startMs >= 0 && track.endMs >= track.startMs && track.endMs <= plan.segmentMs,
                                "${reg.id}/${slide.id}@beat$beatIdx: invalid track bounds ${track.startMs}..${track.endMs} (segmentMs=${plan.segmentMs}) on ${track.nodeId}",
                            )
                        }
                    }
                    totalBeatSegments++
                }
            }

            val activeSlides = doc.slides.filterNot { it.skipped }
            for (idx in 0 until activeSlides.lastIndex) {
                val from = activeSlides[idx]
                val to = activeSlides[idx + 1]
                for (reducedMotion in listOf(false, true)) {
                    val plan = SegmentPlanner.planTransition(
                        document = doc,
                        fromSlideId = from.id,
                        toSlideId = to.id,
                        reducedMotion = reducedMotion,
                        specimens = reg.specimens,
                        effects = BuildEffectRegistry.Standard,
                    )
                    assertTrue(
                        plan.segmentMs >= 0,
                        "${reg.id}/${from.id}->${to.id} (rm=$reducedMotion): negative transition segmentMs=${plan.segmentMs}",
                    )
                    for (track in plan.tracks) {
                        assertTrue(
                            track.startMs >= 0 && track.endMs >= track.startMs && track.endMs <= plan.segmentMs,
                            "${reg.id}/${from.id}->${to.id}: invalid transition track bounds ${track.startMs}..${track.endMs} (segmentMs=${plan.segmentMs})",
                        )
                    }
                }
                totalTransitions++
            }
        }

        assertEquals(264, totalSlides)
        assertTrue(totalBeatSegments >= 264)
        assertTrue(totalTransitions >= 260)
    }

    @Test
    fun `foundation deck preserves exact slides, beats, speaker notes, shared card magic move, and zero warnings`() {
        val reg = AllDocumentDecks.foundation
        val rawText = loadResourceText(reg.resourcePath)
        val result = DeckDocumentCodec.decode(
            rawText = rawText,
            specimens = reg.specimens,
            effects = BuildEffectRegistry.Standard,
            assetExists = { src -> deckAssetExists(reg, src) },
        ) as DeckDecodeResult.Success
        val doc = result.document

        assertTrue(
            result.lintIssues.isEmpty(),
            "foundation.deck.json should have zero lint errors and zero lint warnings, got: ${result.lintIssues}",
        )
        assertEquals(
            listOf("beats", "shared-move", "ambient-loop", "highlighted-code", "keynote-effects-lab"),
            doc.slides.map { it.id },
        )
        assertEquals(listOf(3, 2, 1, 3, 5), doc.slides.map { it.beats.size })
        for (slide in doc.slides) {
            for ((idx, beat) in slide.beats.withIndex()) {
                assertTrue(
                    !beat.notes.isNullOrBlank(),
                    "foundation/${slide.id}@beat$idx missing speaker notes",
                )
            }
        }

        // Verify cross-slide Magic Move pairing for foundation-shared-card (Slide 1 -> Slide 2)
        val match = CrossSlideGeometry.matchCrossSlidePairs(doc.slides[0], doc.slides[1], doc.stage)
        assertTrue(match.lintIssues.isEmpty(), "Slide 1 -> 2 match lint issues: ${match.lintIssues}")
        assertEquals(listOf("foundation-shared-card"), match.pairs.map { it.nodeId })
    }

    @Test
    fun `static specimen registries expose all declarations and enforce parameter bounds without composition`() {
        for (exp in expectations) {
            assertEquals(
                exp.expectedSpecimenCountInRegistry,
                exp.registration.specimens.all.size,
                "${exp.registration.id}: unexpected specimen registry size",
            )
        }
        // Four deck registries plus the shared typography specimens.
        assertEquals(39, AllDocumentDecks.allSpecimens.all.size)
        assertTrue(TypographySpecimenIds.AXIS_BREATH in AllDocumentDecks.allSpecimens)
        assertEquals(ShadersSpecimenIds.allIds.toSet(), shadersQuickieSpecimens.ids)

        // Verify live Markdown editor specimen nodes declare interactive = "keyboard"
        val jewelDoc = decodeOrFail(AllDocumentDecks.jewelTalk2024)
        val jewelMdNode = flattenNodes(jewelDoc.slides.flatMap { it.nodes })
            .firstOrNull { it.id == "specimen-markdown-standalone" }
        assertEquals(
            "keyboard",
            jewelMdNode?.interactive,
            "jewel-talk-2024 s36 specimen-markdown-standalone must declare interactive=\"keyboard\"",
        )
        val njptDoc = decodeOrFail(AllDocumentDecks.notJustPlainText2024)
        val njptMdNode = flattenNodes(njptDoc.slides.flatMap { it.nodes })
            .firstOrNull { it.id == "specimen-njpt-markdown-split" }
        assertEquals(
            "keyboard",
            njptMdNode?.interactive,
            "not-just-plain-text-2024 s125 specimen-njpt-markdown-split must declare interactive=\"keyboard\"",
        )

        // Check typed parameter validation (valid, out-of-range, undeclared, invalid choice)
        val colorDecl = assertNotNull(shadersQuickieSpecimens.find(ShadersSpecimenIds.COLOR_EVOLUTION))
        assertTrue(
            ParamResolver.validateMap(
                colorDecl.params,
                mapOf("r" to JsonPrimitive(0.5), "g" to JsonPrimitive(0.25), "b" to JsonPrimitive(1.0), "a" to JsonPrimitive(1.0)),
            ).isEmpty(),
        )
        val outOfRange = ParamResolver.validateMap(colorDecl.params, mapOf("r" to JsonPrimitive(2.5)))
        assertEquals(listOf(ParamDiagnosticKind.OutOfRange), outOfRange.map { it.kind })

        val undeclared = ParamResolver.validateMap(colorDecl.params, mapOf("bogus" to JsonPrimitive(1)))
        assertEquals(listOf(ParamDiagnosticKind.UndeclaredKey), undeclared.map { it.kind })

        val cohesionDecl = assertNotNull(jewelTalkSpecimens.find("jewel.cohesionIdeShowcase"))
        val badChoice = ParamResolver.validateMap(cohesionDecl.params, mapOf("panel" to JsonPrimitive("nonexistent")))
        assertEquals(listOf(ParamDiagnosticKind.InvalidChoice), badChoice.map { it.kind })

        val wireframeDecl = assertNotNull(jewelTalkSpecimens.find("jewel.adaptiveWireframePanes"))
        val badWidth = ParamResolver.validateMap(wireframeDecl.params, mapOf("widthFraction" to JsonPrimitive(1.5)))
        assertEquals(listOf(ParamDiagnosticKind.OutOfRange), badWidth.map { it.kind })

        val meshDecl = assertNotNull(foundationSpecimens.find(FOUNDATION_AMBIENT_MESH_REF))
        assertEquals(FOUNDATION_AMBIENT_MESH_PERIOD_MS, meshDecl.loop("time")?.periodMs)
        assertNotNull(meshDecl.param("driftScale"))
    }

    @Test
    fun `ported sample decks declare theme code schemes and use live syntax-highlighted code nodes`() {
        val portedRegs = listOf(
            AllDocumentDecks.shadersQuickie2025,
            AllDocumentDecks.jewelTalk2024,
            AllDocumentDecks.notJustPlainText2024,
        )
        for (reg in portedRegs) {
            val doc = decodeOrFail(reg)
            assertEquals("intellij-islands-dark", doc.themes["dark"]?.codeScheme, "${reg.id} dark codeScheme")
            assertEquals("intellij-light", doc.themes["light"]?.codeScheme, "${reg.id} light codeScheme")
        }

        val shadersDoc = decodeOrFail(AllDocumentDecks.shadersQuickie2025)
        val shadersCodeNodes = flattenNodes(shadersDoc.slides.flatMap { it.nodes }).filter { it.code != null }
        assertTrue(shadersCodeNodes.size >= 31, "Expected >= 31 live code nodes in shaders-quickie-2025, got ${shadersCodeNodes.size}")
        assertEquals(setOf("agsl", "kotlin"), shadersCodeNodes.mapNotNull { it.code?.language }.toSet())
        val shadersWarpMorphs = shadersCodeNodes.count { node ->
            node.builds.any { it is dev.sebastiano.ciacola.document.Build.Change && "code" in it.set }
        }
        assertTrue(shadersWarpMorphs >= 4, "Expected >= 4 live Warp code morph builds in shaders-quickie-2025, got $shadersWarpMorphs")

        val njptDoc = decodeOrFail(AllDocumentDecks.notJustPlainText2024)
        val njptCodeNodes = flattenNodes(njptDoc.slides.flatMap { it.nodes }).filter { it.code != null }
        assertTrue(njptCodeNodes.size >= 23, "Expected >= 23 live code nodes in not-just-plain-text-2024, got ${njptCodeNodes.size}")
        assertEquals(setOf("css", "html", "markdown"), njptCodeNodes.mapNotNull { it.code?.language }.toSet())
    }

    private fun decodeOrFail(reg: DocumentDeckRegistration): DeckDocument {
        val rawText = loadResourceText(reg.resourcePath)
        val result = DeckDocumentCodec.decode(
            rawText = rawText,
            specimens = reg.specimens,
            effects = BuildEffectRegistry.Standard,
            assetExists = { src -> deckAssetExists(reg, src) },
        )
        return (result as? DeckDecodeResult.Success)?.document
            ?: fail("${reg.id}: decode failed: $result")
    }

    private fun loadResourceText(path: String): String {
        val stream = javaClass.classLoader.getResourceAsStream(path)
            ?: fail("Classpath resource not found: $path")
        return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    private fun classpathResourceExists(path: String): Boolean =
        javaClass.classLoader.getResource(path) != null

    private fun deckAssetExists(reg: DocumentDeckRegistration, src: String): Boolean {
        val normalized = src.removePrefix("/")
        return classpathResourceExists("${reg.assetsResourceDir}/$normalized") ||
            classpathResourceExists("decks/$normalized") ||
            classpathResourceExists(normalized)
    }

    private fun flattenNodes(nodes: List<NodeSpec>): List<NodeSpec> = buildList {
        for (node in nodes) {
            add(node)
            if (node.children.isNotEmpty()) {
                addAll(flattenNodes(node.children))
            }
        }
    }

    private fun firstDiffPreview(actual: String, expected: String): String {
        val actualLines = actual.lines()
        val expectedLines = expected.lines()
        val max = maxOf(actualLines.size, expectedLines.size)
        for (i in 0 until max) {
            val a = actualLines.getOrNull(i)
            val e = expectedLines.getOrNull(i)
            if (a != e) {
                return "First difference at line ${i + 1}:\n  actual  : $a\n  canonical: $e"
            }
        }
        return "Trailing whitespace/newline mismatch"
    }
}
