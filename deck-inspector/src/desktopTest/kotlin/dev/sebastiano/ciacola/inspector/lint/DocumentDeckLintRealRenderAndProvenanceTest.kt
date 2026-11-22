package dev.sebastiano.ciacola.inspector.lint

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import dev.sebastiano.ciacola.document.DeckDocument
import dev.sebastiano.ciacola.document.DeckDocumentStore
import dev.sebastiano.ciacola.document.DeckThemeSpec
import dev.sebastiano.ciacola.document.Fill
import dev.sebastiano.ciacola.document.FrameSpec
import dev.sebastiano.ciacola.document.ImageFillSpec
import dev.sebastiano.ciacola.document.MorphBy
import dev.sebastiano.ciacola.document.NodeSpec
import dev.sebastiano.ciacola.document.RichTextValue
import dev.sebastiano.ciacola.document.SlideSpec
import dev.sebastiano.ciacola.document.SpecimenPayload
import dev.sebastiano.ciacola.document.Stagger
import dev.sebastiano.ciacola.document.TextMorphSpec
import dev.sebastiano.ciacola.document.TextPayload
import dev.sebastiano.ciacola.document.TextRangeEdit
import dev.sebastiano.ciacola.document.deckSpecimens
import dev.sebastiano.ciacola.inspector.element.DocumentElementInspectorController
import dev.sebastiano.ciacola.inspector.element.coalesceTextRangeEdits
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Verifies real `480x270` slide rendering through `DocumentStoryboardHost` in
 * `DocumentDeckLintEngine` (without invented schematic colors) and per-keystroke UTF-16 range
 * provenance coalescing in inline text editing.
 */
class DocumentDeckLintRealRenderAndProvenanceTest {

    private fun writeSolidPng(path: Path, argb: Int) {
        val img = BufferedImage(64, 36, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until img.height) {
            for (x in 0 until img.width) {
                img.setRGB(x, y, argb)
            }
        }
        ImageIO.write(img, "png", path.toFile())
    }

    @Test
    fun real480x270RenderSamplesTruePngImageAndLiveSpecimenPixelsWithoutInventingColors() = runBlocking {
        val tempDir = Files.createTempDirectory("ciacola-real-lint-render")
        val assetsDir = Files.createDirectories(tempDir.resolve("real-render-deck.assets"))
        writeSolidPng(assetsDir.resolve("bright-photo.png"), 0xFFF8F8F8.toInt())
        writeSolidPng(assetsDir.resolve("dark-photo.png"), 0xFF0A0D14.toInt())

        val specimens = deckSpecimens {
            specimen("lint.brightCanvas", "Bright Canvas Specimen") {
                content {
                    Box(Modifier.fillMaxSize().background(Color(0xFFF6F6F6)))
                }
            }
            specimen("lint.darkCanvas", "Dark Canvas Specimen") {
                content {
                    Box(Modifier.fillMaxSize().background(Color(0xFF0B0E16)))
                }
            }
        }

        val doc = DeckDocument(
            id = "real-render-deck",
            themes = mapOf("dark" to DeckThemeSpec(preset = "stageDark")),
            defaultTheme = "dark",
            slides = listOf(
                // Slide 0: white text over REAL bright PNG photo -> MUST fail LOW_CONTRAST!
                SlideSpec(
                    id = "s-bright-img",
                    background = Fill.Image(ImageFillSpec(src = "bright-photo.png", fit = "cover")),
                    nodes = listOf(
                        NodeSpec(
                            id = "white-on-bright-img",
                            frame = FrameSpec(180.0, 180.0, 640.0, 120.0),
                            text = TextPayload(
                                value = RichTextValue.Plain("White on bright photo"),
                                color = "#FFFFFF",
                                size = 44.0,
                            ),
                        ),
                    ),
                ),
                // Slide 1: white text over REAL dark PNG photo -> MUST pass contrast (0 LOW_CONTRAST)!
                SlideSpec(
                    id = "s-dark-img",
                    background = Fill.Image(ImageFillSpec(src = "dark-photo.png", fit = "cover")),
                    nodes = listOf(
                        NodeSpec(
                            id = "white-on-dark-img",
                            frame = FrameSpec(180.0, 180.0, 640.0, 120.0),
                            text = TextPayload(
                                value = RichTextValue.Plain("White on dark photo"),
                                color = "#FFFFFF",
                                size = 44.0,
                            ),
                        ),
                    ),
                ),
                // Slide 2: white text over REAL bright live specimen -> MUST fail LOW_CONTRAST!
                SlideSpec(
                    id = "s-bright-specimen",
                    background = Fill.Solid("#0B0E16"),
                    nodes = listOf(
                        NodeSpec(
                            id = "spec-bg-bright",
                            frame = FrameSpec(140.0, 140.0, 760.0, 260.0),
                            specimen = SpecimenPayload(ref = "lint.brightCanvas"),
                        ),
                        NodeSpec(
                            id = "white-on-bright-spec",
                            frame = FrameSpec(200.0, 200.0, 600.0, 100.0),
                            text = TextPayload(
                                value = RichTextValue.Plain("White on bright specimen"),
                                color = "#FFFFFF",
                                size = 44.0,
                            ),
                        ),
                    ),
                ),
                // Slide 3: white text over REAL dark live specimen -> MUST pass contrast!
                SlideSpec(
                    id = "s-dark-specimen",
                    background = Fill.Solid("#F8FAFC"),
                    nodes = listOf(
                        NodeSpec(
                            id = "spec-bg-dark",
                            frame = FrameSpec(140.0, 140.0, 760.0, 260.0),
                            specimen = SpecimenPayload(ref = "lint.darkCanvas"),
                        ),
                        NodeSpec(
                            id = "white-on-dark-spec",
                            frame = FrameSpec(200.0, 200.0, 600.0, 100.0),
                            text = TextPayload(
                                value = RichTextValue.Plain("White on dark specimen"),
                                color = "#FFFFFF",
                                size = 44.0,
                            ),
                        ),
                    ),
                ),
                // Slide 4: missing image asset behind text -> MUST emit CONTRAST_UNKNOWN (never invent flat color)!
                SlideSpec(
                    id = "s-missing-img",
                    background = Fill.Image(ImageFillSpec(src = "does-not-exist.png", fit = "cover")),
                    nodes = listOf(
                        NodeSpec(
                            id = "text-on-missing-img",
                            frame = FrameSpec(180.0, 180.0, 640.0, 120.0),
                            text = TextPayload(
                                value = RichTextValue.Plain("Missing backdrop"),
                                color = "#FFFFFF",
                                size = 44.0,
                            ),
                        ),
                    ),
                ),
            ),
        )

        try {
            DocumentDeckLintEngine(specimens = specimens).use { engine ->
                val report = engine.lintFullDeck(
                    document = doc,
                    themeNames = listOf("dark"),
                    deckDir = tempDir,
                    assetsDir = assetsDir,
                )

                assertEquals(5, report.stats.pixelContrastRenders)
                assertTrue(
                    report.issues.any {
                        it.slideId == "s-bright-img" &&
                            it.nodeId == "white-on-bright-img" &&
                            it.code == "LOW_CONTRAST" &&
                            it.source == DocumentLintSource.PixelContrast480x270
                    },
                    "Real 480x270 render MUST catch white text over bright PNG photo: ${report.issues}",
                )
                assertFalse(
                    report.issues.any { it.slideId == "s-dark-img" && it.code == "LOW_CONTRAST" },
                    "White text over dark PNG photo MUST pass contrast: ${report.issues}",
                )
                assertTrue(
                    report.issues.any {
                        it.slideId == "s-bright-specimen" &&
                            it.nodeId == "white-on-bright-spec" &&
                            it.code == "LOW_CONTRAST" &&
                            it.source == DocumentLintSource.PixelContrast480x270
                    },
                    "Real 480x270 render MUST catch white text over bright live specimen: ${report.issues}",
                )
                assertFalse(
                    report.issues.any { it.slideId == "s-dark-specimen" && it.code == "LOW_CONTRAST" },
                    "White text over dark live specimen MUST pass contrast: ${report.issues}",
                )
                assertTrue(
                    report.issues.any {
                        it.slideId == "s-missing-img" &&
                            it.nodeId == "text-on-missing-img" &&
                            it.code == "CONTRAST_UNKNOWN"
                    },
                    "Unrenderable/missing image asset MUST report CONTRAST_UNKNOWN, never invent flat tint: ${report.issues}",
                )
                // Existing assets inside assetsDir must NOT raise false MISSING_ASSET errors.
                assertFalse(
                    report.issues.any {
                        it.code == "MISSING_ASSET" &&
                            (it.detail.contains("bright-photo.png") || it.detail.contains("dark-photo.png"))
                    },
                    "Resolved assets in assetsDir must never trigger false MISSING_ASSET: ${report.issues}",
                )
            }
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun inlineTextRangeProvenanceTypingPrefixBeforeIdenticalTokenRemapsOccurrenceZeroToOne() {
        val doc = DeckDocument(
            id = "provenance-prefix-test",
            themes = mapOf("dark" to DeckThemeSpec(preset = "stageDark")),
            defaultTheme = "dark",
            slides = listOf(
                SlideSpec(
                    id = "s1",
                    nodes = listOf(
                        NodeSpec(
                            id = "label",
                            frame = FrameSpec(120.0, 120.0, 600.0, 80.0),
                            text = TextPayload(
                                value = RichTextValue.Plain("item"),
                                morph = TextMorphSpec(
                                    by = MorphBy.Word,
                                    stagger = Stagger(manual = mapOf("item#0" to 150)),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val store = DeckDocumentStore()
        store.loadInitial(doc)
        val controller = DocumentElementInspectorController(store = store, initialSlideId = "s1", initialBeat = 0)
        assertTrue(controller.startInlineTextEdit("label"))

        // Simulate keystroke-by-keystroke InputTransformation captures when typing "item " at offset 0 before "item".
        val rawKeystrokes = listOf(
            TextRangeEdit(start = 0, removedLength = 0, insertedText = "i"),
            TextRangeEdit(start = 1, removedLength = 0, insertedText = "t"),
            TextRangeEdit(start = 2, removedLength = 0, insertedText = "e"),
            TextRangeEdit(start = 3, removedLength = 0, insertedText = "m"),
            TextRangeEdit(start = 4, removedLength = 0, insertedText = " "),
        )
        val coalesced = coalesceTextRangeEdits(rawKeystrokes)
        assertEquals(listOf(TextRangeEdit(start = 0, removedLength = 0, insertedText = "item ")), coalesced)

        assertTrue(controller.commitInlineTextEdits(rawKeystrokes, finalText = "item item"))

        val updatedManual = store.document.slides[0].nodes[0].text?.morph?.stagger?.manual
        assertEquals(
            150,
            updatedManual?.get("item#1"),
            "Typing 'item ' before 'item' MUST remap original item#0 -> item#1, got $updatedManual",
        )
        assertNull(updatedManual?.get("item#0"), "Newly inserted prefix 'item' must NOT steal item#0 delay")

        // Single undo restores exact original document!
        store.undo()
        assertEquals(doc, store.document)
        assertFalse(store.canUndo)
    }

    @Test
    fun unframedCodeNodeInFlowOrPrecedingSiblingForcesPixelRenderInsteadOfStaticContrast() {
        val doc = DeckDocument(
            id = "code-flow-render-test",
            themes = mapOf("dark" to DeckThemeSpec(preset = "stageDark")),
            defaultTheme = "dark",
            slides = listOf(
                SlideSpec(
                    id = "s1",
                    background = dev.sebastiano.ciacola.document.Fill.Solid("#0E1117"),
                    nodes = listOf(
                        NodeSpec(
                            id = "flow-col",
                            frame = FrameSpec(80.0, 80.0, 1200.0, 800.0),
                            column = dev.sebastiano.ciacola.document.ContainerPayload(
                                gap = 24.0,
                                children = listOf(
                                    // Unframed code node whose height uses estimateCodeNaturalSize in flow layout
                                    NodeSpec(
                                        id = "unframed-code",
                                        code = dev.sebastiano.ciacola.document.CodePayload(
                                            value = "fun hello() {\n    println(\"world\")\n}",
                                        ),
                                    ),
                                    NodeSpec(
                                        id = "following-text",
                                        text = TextPayload(
                                            value = RichTextValue.Plain("Caption below code"),
                                            color = "#FFFFFF",
                                        ),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val slide = doc.slides.single()
        val themeSpec = doc.themes.getValue("dark")
        val laidOut = DocumentDeckLintLayout.layoutSlideAtBeat(slide, beat = 0, document = doc, themeSpec = themeSpec)
        val textEntry = laidOut.first { it.node.id == "following-text" }
        assertTrue(textEntry.hasApproximateFlowLayout, "Text sharing flow container with unframed code must be marked approximate")
        val decision = DocumentDeckLintLayout.evaluateStaticContrast(textEntry, laidOut, slide, doc, themeSpec)
        assertTrue(
            decision is StaticContrastDecision.RequiresPixelRender,
            "Text positioned after an unframed code node MUST require real pixel render instead of Eligible static contrast; got $decision",
        )
    }
}
