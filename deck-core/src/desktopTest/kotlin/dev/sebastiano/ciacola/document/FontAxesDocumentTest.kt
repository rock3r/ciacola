package dev.sebastiano.ciacola.document

import dev.sebastiano.ciacola.core.fonts.BundledFontCatalog
import dev.sebastiano.ciacola.core.fonts.ClasspathFontResourceLoader
import dev.sebastiano.ciacola.core.fonts.DeckFontDeclaration
import dev.sebastiano.ciacola.core.fonts.DeckFontResolver
import dev.sebastiano.ciacola.core.fonts.SystemFontLookup
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Schema round-trip, tag validation, and font-aware lint for static `axes` on theme typography
 * roles, text nodes, and inline text runs.
 */
class FontAxesDocumentTest {

    private fun axesDocument(
        fonts: List<DeckFontDeclaration> = listOf(BundledFontCatalog.Fredoka) + BundledFontCatalog.GoogleSans,
        nodeAxes: Map<String, Double> = mapOf("wdth" to 85.0, "GRAD" to 0.0),
        runAxes: Map<String, Double> = mapOf("wdth" to 120.0),
    ): DeckDocument = DeckDocument(
        format = CURRENT_DECK_FORMAT_VERSION,
        id = "axes-fixture",
        defaultTheme = "dark",
        themes = mapOf(
            "dark" to DeckThemeSpec(
                preset = "stageDark",
                typography = mapOf(
                    "body" to TextStyleOverride(font = "Google Sans", axes = mapOf("opsz" to 18.0, "GRAD" to -25.0)),
                ),
            ),
            "light" to DeckThemeSpec(
                preset = "keynoteLight",
                typography = mapOf(
                    "body" to TextStyleOverride(font = "Google Sans", axes = mapOf("opsz" to 18.0, "GRAD" to 0.0)),
                ),
            ),
        ),
        fonts = fonts,
        slides = listOf(
            SlideSpec(
                id = "s0",
                nodes = listOf(
                    NodeSpec(
                        id = "headline",
                        frame = FrameSpec(x = 100.0, y = 100.0, w = 1200.0, h = 200.0),
                        text = TextPayload(
                            value = RichTextValue.Runs(
                                listOf(
                                    TextRun(text = "Narrow "),
                                    TextRun(text = "wide", axes = runAxes),
                                ),
                            ),
                            font = "Fredoka",
                            axes = nodeAxes,
                        ),
                    ),
                ),
            ),
        ),
    )

    private fun resolverFor(document: DeckDocument): DeckFontResolver {
        val fontsDir = listOf(
            File("deck/src/desktopMain/resources/fonts"),
            File("../deck/src/desktopMain/resources/fonts"),
        ).first { it.isDirectory }
        return DeckFontResolver(
            declarations = document.fonts,
            resourceLoader = ClasspathFontResourceLoader(fallbackDirectories = listOf(fontsDir)),
            systemFontLookup = SystemFontLookup { null },
        )
    }

    @Test
    fun `axes round-trip canonically with tags sorted on roles, nodes, and runs`() {
        val doc = axesDocument()
        val printed = CanonicalJson.print(doc)
        assertTrue(printed.contains("\"axes\": { \"GRAD\": -25, \"opsz\": 18 }"), printed)
        assertTrue(printed.contains("\"axes\": { \"GRAD\": 0, \"wdth\": 85 }"), printed)
        assertTrue(printed.contains("\"axes\": { \"wdth\": 120 }"), printed)

        val decoded = assertIs<DeckDecodeResult.Success>(DeckDocumentCodec.decode(printed))
        assertEquals(doc, decoded.document)
        assertEquals(printed, CanonicalJson.print(decoded.document))
        assertEquals(printed, CanonicalJson.print(decoded.rawJson))

        // Documents without axes keep printing without an `axes` key.
        val plain = doc.copy(
            themes = doc.themes.mapValues { (_, theme) ->
                theme.copy(typography = theme.typography.mapValues { it.value.copy(axes = emptyMap()) })
            },
            slides = listOf(
                doc.slides.single().copy(
                    nodes = listOf(
                        doc.slides.single().nodes.single().let { node ->
                            node.copy(text = node.text!!.copy(value = RichTextValue.Plain("Plain"), axes = emptyMap()))
                        },
                    ),
                ),
            ),
        )
        assertTrue("\"axes\"" !in CanonicalJson.print(plain))
    }

    @Test
    fun `malformed axis tags are lint errors`() {
        val doc = axesDocument(nodeAxes = mapOf("width" to 85.0), runAxes = mapOf("wd" to 1.0))
        val issues = DeckDocumentCodec.validate(doc).filter { it.code == "INVALID_FONT_AXIS_TAG" }
        assertEquals(2, issues.size, "$issues")
        assertTrue(issues.all { it.severity == IssueSeverity.LintError && it.address == "s0/headline" })
        assertTrue(DeckDocumentCodec.validate(axesDocument()).none { it.code.startsWith("INVALID_FONT_AXIS") })
    }

    @Test
    fun `font-aware lint warns on unsupported tags, clamped values, and non-variable faces`() {
        val doc = axesDocument(
            nodeAxes = mapOf("wdth" to 200.0, "GRAD" to 0.0),
            runAxes = mapOf("wdth" to 120.0),
        )
        val issues = DeckFontAxisLint.validate(doc, resolverFor(doc))
        assertTrue(issues.all { it.severity == IssueSeverity.LintWarning }, "$issues")

        // The node declares GRAD and the run inherits it, so both report it against Fredoka.
        val unsupported = issues.filter { it.code == DeckFontAxisLint.CODE_UNSUPPORTED_AXIS }
        assertEquals(2, unsupported.size, "$issues")
        assertTrue(unsupported.all { it.message.contains("'GRAD'") && it.message.contains("'Fredoka'") }, "$unsupported")

        val clamped = issues.filter { it.code == DeckFontAxisLint.CODE_AXIS_OUT_OF_RANGE }
        assertEquals(1, clamped.size, "$issues")
        assertTrue(clamped.single().message.contains("200") && clamped.single().message.contains("clamped to 125"))

        // Theme roles target Google Sans, which declares both opsz and GRAD within range.
        assertTrue(issues.none { it.address.startsWith("<root>/themes") }, "$issues")

        // A static face ignores axes with a single clear warning.
        val static = axesDocument(fonts = listOf(BundledFontCatalog.NanumPenScript) + BundledFontCatalog.GoogleSans)
            .let { d ->
                d.copy(
                    slides = listOf(
                        d.slides.single().copy(
                            nodes = listOf(d.slides.single().nodes.single().let { it.copy(text = it.text!!.copy(font = "Nanum Pen Script")) }),
                        ),
                    ),
                )
            }
        val staticIssues = DeckFontAxisLint.validate(static, resolverFor(static))
        assertTrue(staticIssues.isNotEmpty() && staticIssues.all { it.code == DeckFontAxisLint.CODE_AXES_IGNORED }, "$staticIssues")

        // A run that switches to a static font is checked against its inherited coordinates.
        val inherited = axesDocument(
            fonts = listOf(BundledFontCatalog.Fredoka, BundledFontCatalog.NanumPenScript),
            nodeAxes = mapOf("wdth" to 80.0),
            runAxes = emptyMap(),
        ).let { d ->
            val node = d.slides.single().nodes.single()
            val runs = listOf(TextRun(text = "Fredoka "), TextRun(text = "Nanum", font = "Nanum Pen Script"))
            d.copy(
                themes = emptyMap(),
                slides = listOf(d.slides.single().copy(nodes = listOf(node.copy(text = node.text!!.copy(value = RichTextValue.Runs(runs)))))),
            )
        }
        val inheritedIssues = DeckFontAxisLint.validate(inherited, resolverFor(inherited))
        assertEquals(1, inheritedIssues.size, "$inheritedIssues")
        assertEquals(DeckFontAxisLint.CODE_AXES_IGNORED, inheritedIssues.single().code)
        assertTrue(inheritedIssues.single().message.contains("'Nanum Pen Script'"), "$inheritedIssues")

        // Without declared fonts every axis is ignored.
        val noFonts = axesDocument(fonts = emptyList())
        assertTrue(DeckFontAxisLint.validate(noFonts, null).all { it.code == DeckFontAxisLint.CODE_AXES_IGNORED })
        assertTrue(DeckFontAxisLint.declaresAxes(noFonts))
    }
}
