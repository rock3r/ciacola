package dev.sebastiano.ciacola.document

import dev.sebastiano.ciacola.core.fonts.DeckFontDeclaration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Verifies that [IncrementalDeckValidator] (used by [DeckDocumentStore]) stays 100% identical to
 * full [DeckDocumentCodec.validate] across every deck-wide, positional, registry, and dynamic asset
 * invalidation edge case while re-validating only changed slides on steady-state edits.
 */
class IncrementalDeckValidatorTest {

    private fun testSpecimens(): DeckSpecimenRegistry = deckSpecimens {
        specimen("demo.pulse", "Demo Pulse") {
            track(name = "wave", beat = 1, durationMs = 300)
            content {}
        }
    }

    private fun makeMultiSlideDoc(slideCount: Int = 10): DeckDocument {
        val slides = (0 until slideCount).map { i ->
            SlideSpec(
                id = "s$i",
                title = "Slide $i",
                beats = listOf(BeatSpec(), BeatSpec(stagger = Stagger(ms = 30))),
                nodes = listOf(
                    NodeSpec(
                        id = "headline-$i",
                        frame = FrameSpec(100.0, 100.0, 800.0, 100.0),
                        text = TextPayload(value = RichTextValue.Plain("Initial $i")),
                        builds = listOf(
                            Build.Change(
                                beat = 1,
                                set = buildJsonObject {
                                    putJsonObject("text") { put("value", "Updated $i") }
                                },
                            ),
                        ),
                    ),
                ),
            )
        }
        return DeckDocument(
            id = "inc-val-test",
            title = "Incremental Validation Test",
            themes = mapOf("dark" to DeckThemeSpec(preset = "stageDark")),
            defaultTheme = "dark",
            slides = slides,
        )
    }

    @Test
    fun singleSlideEditRevalidatesOnlyEditedSlideAndMatchesFullValidation() {
        val store = DeckDocumentStore()
        val initial = makeMultiSlideDoc(slideCount = 20)
        store.loadInitial(initial)
        assertEquals(20, store.lastValidationStatsForTesting().revalidatedSlideCount)
        assertEquals(0, store.lastValidationStatsForTesting().cachedSlideHits)

        assertTrue(
            store.edit("Update s10 stagger") { doc ->
                val updatedSlides = doc.slides.toMutableList()
                val s = updatedSlides[10]
                val updatedBeats = s.beats.toMutableList()
                updatedBeats[1] = updatedBeats[1].copy(stagger = Stagger(ms = 75))
                updatedSlides[10] = s.copy(beats = updatedBeats)
                doc.copy(slides = updatedSlides)
            },
        )
        val afterEditStats = store.lastValidationStatsForTesting()
        assertEquals(1, afterEditStats.revalidatedSlideCount, "Only edited slide s10 should re-validate")
        assertEquals(19, afterEditStats.cachedSlideHits, "19 untouched slides should hit validation cache")
        assertEquals(DeckDocumentCodec.validate(store.document), store.lintIssues)

        // Undo & redo must also hit cache for untouched slides and keep lintIssues exact.
        store.undo()
        assertEquals(1, store.lastValidationStatsForTesting().revalidatedSlideCount)
        assertEquals(19, store.lastValidationStatsForTesting().cachedSlideHits)
        assertEquals(DeckDocumentCodec.validate(store.document), store.lintIssues)

        store.redo()
        assertEquals(1, store.lastValidationStatsForTesting().revalidatedSlideCount)
        assertEquals(19, store.lastValidationStatsForTesting().cachedSlideHits)
        assertEquals(DeckDocumentCodec.validate(store.document), store.lintIssues)
    }

    @Test
    fun positionalFirstAndLastNonSkippedWarningsInvalidateWhenSlidesSkipOrReorder() {
        val doc = DeckDocument(
            id = "positional-test",
            slides = listOf(
                SlideSpec(
                    id = "s0",
                    beats = listOf(BeatSpec()),
                    nodes = listOf(
                        NodeSpec(
                            id = "n0",
                            text = TextPayload(value = RichTextValue.Plain("First")),
                            builds = listOf(Build.Enter(beat = 0, effect = "dissolve")),
                        ),
                    ),
                    transition = TransitionSpec(type = TransitionType.Dissolve),
                ),
                SlideSpec(
                    id = "s1",
                    beats = listOf(BeatSpec()),
                    nodes = listOf(
                        NodeSpec(
                            id = "n1",
                            text = TextPayload(value = RichTextValue.Plain("Middle")),
                            builds = listOf(Build.Enter(beat = 0, effect = "dissolve")),
                        ),
                    ),
                    transition = TransitionSpec(type = TransitionType.Dissolve),
                ),
                SlideSpec(
                    id = "s2",
                    beats = listOf(BeatSpec()),
                    nodes = listOf(
                        NodeSpec(
                            id = "n2",
                            text = TextPayload(value = RichTextValue.Plain("Last")),
                        ),
                    ),
                    transition = TransitionSpec(type = TransitionType.Dissolve),
                ),
            ),
        )

        val store = DeckDocumentStore()
        store.loadInitial(doc)
        assertEquals(DeckDocumentCodec.validate(store.document), store.lintIssues)
        assertTrue(store.lintIssues.any { it.code == "FIRST_SLIDE_BEAT0_ENTER" && it.address.startsWith("s0/") })
        assertTrue(store.lintIssues.any { it.code == "TRANSITION_ON_LAST_SLIDE" && it.address == "s2" })

        // Skip s0 and s2 -> s1 becomes BOTH first AND last non-skipped slide without s1's SlideSpec changing!
        assertTrue(
            store.edit("Skip s0 and s2") { d ->
                d.copy(
                    slides = listOf(
                        d.slides[0].copy(skipped = true),
                        d.slides[1],
                        d.slides[2].copy(skipped = true),
                    ),
                )
            },
        )
        assertEquals(DeckDocumentCodec.validate(store.document), store.lintIssues)
        assertTrue(store.lintIssues.any { it.code == "FIRST_SLIDE_BEAT0_ENTER" && it.address.startsWith("s1/") })
        assertTrue(store.lintIssues.any { it.code == "TRANSITION_ON_LAST_SLIDE" && it.address == "s1" })
        assertTrue(store.lintIssues.none { it.address.startsWith("s0/") || it.address == "s2" })
    }

    @Test
    fun palettesFontsRegistriesBlankAndDuplicateSlideIdsStayExact() {
        val doc = DeckDocument(
            id = "ctx-invalidation",
            fonts = listOf(DeckFontDeclaration(family = "Inter", src = "fonts/Inter.ttf")),
            palettes = mapOf("warm" to SlidePaletteSpec(canvas = "#111111")),
            slides = listOf(
                SlideSpec(
                    id = "s0",
                    palette = "warm",
                    nodes = listOf(
                        NodeSpec(
                            id = "t0",
                            text = TextPayload(value = RichTextValue.Plain("Hello"), font = "Inter"),
                        ),
                        NodeSpec(
                            id = "sp0",
                            specimen = SpecimenPayload(ref = "demo.pulse"),
                        ),
                    ),
                ),
                SlideSpec(
                    id = "s1",
                    nodes = listOf(
                        NodeSpec(id = "t1", text = TextPayload(value = RichTextValue.Plain("Second"))),
                    ),
                ),
            ),
        )

        val store = DeckDocumentStore(specimens = testSpecimens())
        store.loadInitial(doc)
        assertEquals(
            DeckDocumentCodec.validate(store.document, specimens = testSpecimens()),
            store.lintIssues,
        )
        assertTrue(store.lintIssues.isEmpty())

        // Removing the palette invalidates s0 even though s0's SlideSpec reference is untouched.
        assertTrue(store.edit("Remove palette") { d -> d.copy(palettes = emptyMap()) })
        assertEquals(
            DeckDocumentCodec.validate(store.document, specimens = testSpecimens()),
            store.lintIssues,
        )
        assertTrue(store.lintIssues.any { it.code == "UNKNOWN_PALETTE" && it.address == "s0" })

        // Changing declared fonts invalidates s0's UNKNOWN_FONT check.
        assertTrue(
            store.edit("Swap font") { d ->
                d.copy(fonts = listOf(DeckFontDeclaration(family = "JetBrains Mono", src = "fonts/JB.ttf")))
            },
        )
        assertEquals(
            DeckDocumentCodec.validate(store.document, specimens = testSpecimens()),
            store.lintIssues,
        )
        assertTrue(store.lintIssues.any { it.code == "UNKNOWN_FONT" && it.address == "s0/t0" })

        // Reconfiguring specimen registry invalidates s0's specimen check.
        store.configureValidation(specimens = DeckSpecimenRegistry.Empty)
        assertTrue(store.edit("Retitle") { d -> d.copy(title = "Changed") })
        assertEquals(
            DeckDocumentCodec.validate(store.document, specimens = DeckSpecimenRegistry.Empty),
            store.lintIssues,
        )
        assertTrue(store.lintIssues.any { it.code == "UNKNOWN_SPECIMEN_REF" && it.address == "s0/sp0" })

        // Duplicate slide ID and blank slide ID rejected edits must report exact LoadErrors and keep store intact.
        assertFalse(store.edit("Duplicate id") { d -> d.copy(slides = d.slides + d.slides[0]) })
        assertTrue(store.lastRejectedEditErrors.any { it.code == "DUPLICATE_SLIDE_ID" && it.address == "s0" })

        assertFalse(store.edit("Blank id") { d -> d.copy(slides = listOf(d.slides[1], d.slides[0].copy(id = ""))) })
        assertTrue(store.lastRejectedEditErrors.any { it.code == "BLANK_SLIDE_ID" && it.address == "slide[1]" })

        // Valid edit after rejected edits still succeeds cleanly and matches full validation.
        assertTrue(store.edit("Retitle again") { d -> d.copy(title = "Final") })
        assertEquals(
            DeckDocumentCodec.validate(store.document, specimens = DeckSpecimenRegistry.Empty),
            store.lintIssues,
        )
    }

    @Test
    fun dynamicAssetExistsIsNeverStaleAndClearingCallbackNeverLeaksMissingAsset() {
        val availableAssets = mutableSetOf<String>()
        val assetCheck: (String) -> Boolean = { it in availableAssets }

        val doc = DeckDocument(
            id = "asset-invalidation",
            slides = listOf(
                SlideSpec(
                    id = "s-plain",
                    nodes = listOf(NodeSpec(id = "t", text = TextPayload(value = RichTextValue.Plain("No images")))),
                ),
                SlideSpec(
                    id = "s-bg",
                    background = Fill.Image(ImageFillSpec(src = "bg.png")),
                    nodes = listOf(NodeSpec(id = "t", text = TextPayload(value = RichTextValue.Plain("Has bg")))),
                ),
                SlideSpec(
                    id = "s-nested-img",
                    nodes = listOf(
                        NodeSpec(
                            id = "group",
                            stack = ContainerPayload(
                                children = listOf(
                                    NodeSpec(id = "pic", image = ImagePayload(src = "nested.png")),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val store = DeckDocumentStore(assetExists = assetCheck)
        store.loadInitial(doc)
        assertEquals(DeckDocumentCodec.validate(store.document, assetExists = assetCheck), store.lintIssues)
        assertEquals(2, store.lintIssues.count { it.code == "MISSING_ASSET" })

        // Assets appear on disk; untouched image slides MUST re-check assetExists, while plain slide hits cache!
        availableAssets += "bg.png"
        availableAssets += "nested.png"
        assertTrue(store.edit("Touch title 1") { d -> d.copy(title = "T1") })
        assertEquals(DeckDocumentCodec.validate(store.document, assetExists = assetCheck), store.lintIssues)
        assertTrue(store.lintIssues.none { it.code == "MISSING_ASSET" })
        assertEquals(1, store.lastValidationStatsForTesting().cachedSlideHits, "s-plain should still hit cache")
        assertEquals(2, store.lastValidationStatsForTesting().revalidatedSlideCount, "Image slides must re-check assetExists")

        // Assets disappear again -> MISSING_ASSET reappears immediately.
        availableAssets.clear()
        assertTrue(store.edit("Touch title 2") { d -> d.copy(title = "T2") })
        assertEquals(DeckDocumentCodec.validate(store.document, assetExists = assetCheck), store.lintIssues)
        assertEquals(2, store.lintIssues.count { it.code == "MISSING_ASSET" })

        // Clearing assetExists callback to null must NOT leak prior MISSING_ASSET issues from cache.
        store.configureValidation(assetExists = null)
        assertTrue(store.edit("Touch title 3") { d -> d.copy(title = "T3") })
        assertEquals(DeckDocumentCodec.validate(store.document, assetExists = null), store.lintIssues)
        assertTrue(store.lintIssues.none { it.code == "MISSING_ASSET" })

        // Reusing store via loadInitial clears cache cleanly.
        val otherDoc = makeMultiSlideDoc(slideCount = 3)
        store.loadInitial(otherDoc)
        assertEquals(3, store.lastValidationStatsForTesting().revalidatedSlideCount)
        assertEquals(0, store.lastValidationStatsForTesting().cachedSlideHits)
    }
}
