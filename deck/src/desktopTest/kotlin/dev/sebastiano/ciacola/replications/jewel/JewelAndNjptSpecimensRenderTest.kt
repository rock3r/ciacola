package dev.sebastiano.ciacola.replications.jewel

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.sebastiano.ciacola.document.DeckDocument
import dev.sebastiano.ciacola.document.DeckDocumentStore
import dev.sebastiano.ciacola.document.LocalRenderSession
import dev.sebastiano.ciacola.document.RenderSession
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.intui.standalone.styling.default
import org.jetbrains.jewel.intui.standalone.theme.IntUiTheme
import org.jetbrains.jewel.intui.standalone.theme.darkThemeDefinition
import org.jetbrains.jewel.intui.standalone.theme.default
import org.jetbrains.jewel.intui.standalone.theme.lightThemeDefinition
import java.io.File
import org.jetbrains.jewel.ui.ComponentStyling
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.EncodedImageFormat

class JewelAndNjptSpecimensRenderTest {

    private data class RenderedFrame(
        val width: Int,
        val height: Int,
        val rgbaBytes: ByteArray,
    ) {
        fun distinctColorBuckets(stride: Int = 5): Int {
            val buckets = HashSet<Int>()
            var y = 0
            while (y < height) {
                var x = 0
                while (x < width) {
                    val idx = (y * width + x) * 4
                    val b0 = (rgbaBytes[idx].toInt() and 0xFF) shr 4
                    val b1 = (rgbaBytes[idx + 1].toInt() and 0xFF) shr 4
                    val b2 = (rgbaBytes[idx + 2].toInt() and 0xFF) shr 4
                    buckets.add((b0 shl 8) or (b1 shl 4) or b2)
                    x += stride
                }
                y += stride
            }
            return buckets.size
        }

        fun centerMeanLuma(): Double {
            val xStart = width / 4
            val xEnd = (width * 3) / 4
            val yStart = height / 4
            val yEnd = (height * 3) / 4
            var sum = 0.0
            var count = 0
            var y = yStart
            while (y < yEnd) {
                var x = xStart
                while (x < xEnd) {
                    val idx = (y * width + x) * 4
                    // Skia default N32 is BGRA or RGBA — channel average across RGB is invariant for greyscale luminance
                    val c0 = (rgbaBytes[idx].toInt() and 0xFF) / 255.0
                    val c1 = (rgbaBytes[idx + 1].toInt() and 0xFF) / 255.0
                    val c2 = (rgbaBytes[idx + 2].toInt() and 0xFF) / 255.0
                    sum += (c0 + c1 + c2) / 3.0
                    count++
                    x += 4
                }
                y += 4
            }
            return if (count == 0) 0.0 else sum / count
        }

        fun diffRatio(other: RenderedFrame): Double {
            if (rgbaBytes.size != other.rgbaBytes.size) return 1.0
            var diffPixels = 0
            val totalPixels = width * height
            var i = 0
            while (i < rgbaBytes.size) {
                if (rgbaBytes[i] != other.rgbaBytes[i] ||
                    rgbaBytes[i + 1] != other.rgbaBytes[i + 1] ||
                    rgbaBytes[i + 2] != other.rgbaBytes[i + 2] ||
                    rgbaBytes[i + 3] != other.rgbaBytes[i + 3]
                ) {
                    diffPixels++
                }
                i += 4
            }
            return diffPixels.toDouble() / totalPixels.toDouble()
        }
    }

    private fun renderScene(
        width: Int = 1000,
        height: Int = 700,
        backdrop: Color = Color(0xFF29223A),
        dumpName: String? = null,
        content: @Composable () -> Unit,
    ): RenderedFrame {
        val scene = ImageComposeScene(
            width = width,
            height = height,
            density = Density(1f),
            coroutineContext = Dispatchers.Unconfined,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(backdrop)
                    .padding(16.dp),
            ) {
                content()
            }
        }
        try {
            val image = scene.render(0L)
            try {
                val dumpDir = (System.getenv("CIACOLA_DUMP_SPECIMENS_DIR") ?: System.getProperty("ciacola.dumpSpecimensDir"))
                    ?.takeIf { it.isNotBlank() }
                if (dumpDir != null && dumpName != null) {
                    val outDir = File(dumpDir).apply { mkdirs() }
                    val safeName = dumpName.replace(Regex("[^A-Za-z0-9._-]"), "_")
                    image.encodeToData(EncodedImageFormat.PNG)?.bytes?.let { png ->
                        File(outDir, "$safeName.png").writeBytes(png)
                    }
                }
                val bitmap = Bitmap.makeFromImage(image)
                try {
                    val bytes = requireNotNull(bitmap.readPixels()) { "Skia bitmap readPixels() returned null" }
                    return RenderedFrame(width = width, height = height, rgbaBytes = bytes)
                } finally {
                    bitmap.close()
                }
            } finally {
                image.close()
            }
        } finally {
            scene.close()
        }
    }

    @Test
    fun catalogCoversAll22JewelAndNjptSpecimensWithUniqueRefs() {
        assertEquals(22, ALL_JEWEL_AND_NJPT_SPECIMENS.size, "Expected exactly 22 specimens (20 jewel + 2 njpt)")
        val refs = ALL_JEWEL_AND_NJPT_SPECIMENS.map { it.ref }
        assertEquals(refs.size, refs.toSet().size, "Specimen refs must be unique")
        assertEquals(20, refs.count { it.startsWith("jewel.") }, "Expected 20 jewel.* specimens")
        assertEquals(2, refs.count { it.startsWith("njpt.") }, "Expected 2 njpt.* specimens")
    }

    @Test
    fun all22SpecimensAndAllVariantsRenderNonBlankAndDeterministicallyAt1000x700() {
        data class RenderCase(
            val label: String,
            val ref: String,
            val loopMillis: Long,
            val params: Map<String, Any?> = emptyMap(),
        )

        val cases = buildList {
            ALL_JEWEL_AND_NJPT_SPECIMENS.forEach { desc ->
                val sampleMillis = if (desc.periodMs > 0L) desc.periodMs / 4L else 0L
                when (desc.ref) {
                    "jewel.confettiPartyPopper" -> {
                        add(RenderCase("${desc.ref}[mirrored=false]", desc.ref, sampleMillis, mapOf("mirrored" to false)))
                        add(RenderCase("${desc.ref}[mirrored=true]", desc.ref, sampleMillis, mapOf("mirrored" to true)))
                    }
                    "jewel.cohesionIdeShowcase" -> {
                        COHESION_PANEL_MODES.forEach { mode ->
                            add(RenderCase("${desc.ref}[panel=$mode]", desc.ref, 0L, mapOf("panel" to mode)))
                        }
                    }
                    else -> {
                        add(RenderCase(desc.ref, desc.ref, sampleMillis))
                    }
                }
            }
        }

        // 20 single-variant + 2 confetti mirror modes + 5 cohesion panel modes = 27 total configurations
        assertEquals(27, cases.size)

        cases.forEachIndexed { idx, tc ->
            val dumpPrefix = (idx + 1).toString().padStart(2, '0')
            val frameA = renderScene(dumpName = "${dumpPrefix}_${tc.label}") {
                RenderJewelOrNjptSpecimen(
                    ref = tc.ref,
                    loopMillis = tc.loopMillis,
                    params = tc.params,
                    isInteractive = true,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            val frameB = renderScene {
                RenderJewelOrNjptSpecimen(
                    ref = tc.ref,
                    loopMillis = tc.loopMillis,
                    params = tc.params,
                    isInteractive = true,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            val buckets = frameA.distinctColorBuckets()
            assertTrue(
                buckets >= 8,
                "Specimen ${tc.label} rendered too few diverse color buckets ($buckets) — possibly blank or clipped",
            )
            assertContentEquals(
                frameA.rgbaBytes,
                frameB.rgbaBytes,
                "Specimen ${tc.label} must render bit-for-bit deterministically at loopMillis=${tc.loopMillis}",
            )
        }
    }

    @Test
    fun allAnimatedSpecimensProgressVisiblyAcrossLoopMillis() {
        val animated = ALL_JEWEL_AND_NJPT_SPECIMENS.filter { it.periodMs > 0L }
        assertEquals(18, animated.size, "Expected 18 animated loop specimens")

        animated.forEach { desc ->
            val t1 = (desc.periodMs * 18L) / 100L
            val t2 = (desc.periodMs * 62L) / 100L

            val frame1 = renderScene {
                RenderJewelOrNjptSpecimen(
                    ref = desc.ref,
                    loopMillis = t1,
                    isInteractive = false,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            val frame2 = renderScene {
                RenderJewelOrNjptSpecimen(
                    ref = desc.ref,
                    loopMillis = t2,
                    isInteractive = false,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            val diff = frame1.diffRatio(frame2)
            assertTrue(
                diff > 0.001,
                "Animated specimen ${desc.ref} did not visually progress between t1=${t1}ms and t2=${t2}ms (diff=$diff)",
            )
        }
    }

    @Test
    fun confettiMirrorAndCohesionPanelsAndAdaptiveWidthProduceDistinctVisuals() {
        // Confetti mirrored=false vs mirrored=true
        val confettiLeft = renderScene {
            RenderJewelOrNjptSpecimen("jewel.confettiPartyPopper", loopMillis = 350L, params = mapOf("mirrored" to false))
        }
        val confettiRight = renderScene {
            RenderJewelOrNjptSpecimen("jewel.confettiPartyPopper", loopMillis = 350L, params = mapOf("mirrored" to true))
        }
        assertTrue(
            confettiLeft.diffRatio(confettiRight) > 0.05,
            "Mirrored confetti popper must render distinctly from non-mirrored popper",
        )

        // All 5 cohesion panel modes pairwise distinct
        val panelFrames = COHESION_PANEL_MODES.associateWith { mode ->
            renderScene {
                RenderJewelOrNjptSpecimen("jewel.cohesionIdeShowcase", loopMillis = 0L, params = mapOf("panel" to mode))
            }
        }
        for (i in COHESION_PANEL_MODES.indices) {
            for (j in i + 1 until COHESION_PANEL_MODES.size) {
                val a = COHESION_PANEL_MODES[i]
                val b = COHESION_PANEL_MODES[j]
                val diff = panelFrames.getValue(a).diffRatio(panelFrames.getValue(b))
                assertTrue(
                    diff > 0.04,
                    "Cohesion panels '$a' and '$b' should be visually distinct (diff=$diff)",
                )
            }
        }

        // Adaptive wireframe widthFraction parameter changes rendered breakpoints
        val wireCompact = renderScene {
            RenderJewelOrNjptSpecimen("jewel.adaptiveWireframePanes", loopMillis = 800L, params = mapOf("widthFraction" to 0.45f))
        }
        val wireWide = renderScene {
            RenderJewelOrNjptSpecimen("jewel.adaptiveWireframePanes", loopMillis = 800L, params = mapOf("widthFraction" to 0.90f))
        }
        assertTrue(
            wireCompact.diffRatio(wireWide) > 0.04,
            "Adaptive wireframe panes must respond visibly to widthFraction param",
        )
    }

    @Test
    fun jewelSubThemesIsolateLightSpecimensOnDarkChromeAndDarkSpecimensOnLightChrome() {
        // Light specimens mounted inside an outer Dark IntUiTheme on Jewel Talk's #29223A eggplant canvas
        listOf(
            "jewel.taskBasedProfilers" to 0L,
            "jewel.addDeviceDialog" to 3_000L,
            "jewel.geminiAuthCard" to 1_000L,
            "njpt.jewelMarkdownSplitDemo" to 3_500L,
        ).forEach { (ref, ms) ->
            val frame = renderScene(backdrop = Color(0xFF29223A)) {
                IntUiTheme(
                    theme = JewelTheme.darkThemeDefinition(),
                    styling = ComponentStyling.default(),
                ) {
                    RenderJewelOrNjptSpecimen(
                        ref = ref,
                        loopMillis = ms,
                        isInteractive = false,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            val luma = frame.centerMeanLuma()
            assertTrue(
                luma > 0.72,
                "Light specimen $ref leaked outer dark theme (centerMeanLuma=$luma, expected > 0.72)",
            )
        }

        // Dark specimen mounted inside an outer Light IntUiTheme on a pure white backdrop
        val darkOnLight = renderScene(backdrop = Color.White) {
            IntUiTheme(
                theme = JewelTheme.lightThemeDefinition(),
                styling = ComponentStyling.default(),
            ) {
                RenderJewelOrNjptSpecimen(
                    ref = "njpt.geminiDarkMarkdownChat",
                    loopMillis = 3_000L,
                    isInteractive = false,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        val darkLuma = darkOnLight.centerMeanLuma()
        assertTrue(
            darkLuma < 0.35,
            "Dark specimen njpt.geminiDarkMarkdownChat leaked outer light theme (centerMeanLuma=$darkLuma, expected < 0.35)",
        )
    }

    @Test
    fun liveMarkdownParserHandlesCommonMarkAndGfmAstBlocks() {
        val sample = """
            # Title **Bold**

            Paragraph with _italic_, ~~strike~~, `inline()`, and [Jewel](https://github.com/JetBrains/jewel).

            > [!WARNING]
            > Keep HTML sanitized.

            | Col A | Col B |
            | --- | --- |
            | One | `Two` |

            - [x] Done task
            - [ ] Pending task

            ```kotlin
            fun greet() = "hello"
            ```

            ---
        """.trimIndent()

        val blocks = parseMarkdownDocument(sample)
        assertTrue(blocks.any { it is MarkdownBlock.Heading && it.level == 1 })
        assertTrue(blocks.any { it is MarkdownBlock.Paragraph })
        assertTrue(blocks.any { it is MarkdownBlock.Callout && it.kind == MarkdownCalloutKind.Warning })
        assertTrue(blocks.any { it is MarkdownBlock.Table && it.headers.size == 2 && it.rows.size == 1 })
        assertTrue(blocks.any { it is MarkdownBlock.TaskList && it.items.size == 2 && it.items[0].checked && !it.items[1].checked })
        assertTrue(blocks.any { it is MarkdownBlock.CodeBlock && it.language == "kotlin" && it.code.contains("fun greet") })
        assertTrue(blocks.any { it is MarkdownBlock.ThematicBreak })

        val para = blocks.filterIsInstance<MarkdownBlock.Paragraph>().first()
        assertTrue(para.inlines.any { it is MarkdownInline.Italic })
        assertTrue(para.inlines.any { it is MarkdownInline.Strike })
        assertTrue(para.inlines.any { it is MarkdownInline.Code && it.text == "inline()" })
        assertTrue(para.inlines.any { it is MarkdownInline.Link && it.label == "Jewel" })
        assertNotEquals(0, blocks.size)
    }

    @Test
    fun liveMarkdownParserHandlesNestedListsMultiBacktickCodeReferenceLinksFlankingAndAlignedTables() {
        val doc = """
            Setext Title
            ============

            - Parent item
              - Nested bullet with ``code with `tick` inside``
                1. Deep ordered item
            - Second parent

            > Outer quote
            > > Inner nested quote

            Intraword foo_bar_baz stays plain, \*escaped\* stays plain, ***bold italic*** works,
            and see [**Kotlin Docs**](https://en.wikipedia.org/wiki/Kotlin_(programming_language) "Wiki"),
            [full ref][JETBRAINS], [Collapsed][], and [shortcut].

            | Left | Center | Right |
            | :--- | :----: | ----: |
            | `a \| b` | foo \| bar | 42 |

                val indented = 1 + 2

            [jetbrains]: https://www.jetbrains.com "JetBrains"
            [collapsed]: https://commonmark.org
            [shortcut]: https://github.com/JetBrains/jewel
        """.trimIndent()

        val blocks = parseMarkdownDocument(doc)

        // Setext heading
        val setext = blocks.filterIsInstance<MarkdownBlock.Heading>().firstOrNull()
        assertTrue(setext != null && setext.level == 1, "Expected Setext H1 heading")

        // Nested bullet -> nested bullet -> nested ordered list
        val outerList = blocks.filterIsInstance<MarkdownBlock.BulletList>().firstOrNull()
        assertTrue(outerList != null && outerList.items.size == 2, "Expected 2 outer bullet items")
        val nestedBullet = outerList.items[0].children.filterIsInstance<MarkdownBlock.BulletList>().firstOrNull()
        assertTrue(nestedBullet != null && nestedBullet.items.size == 1, "Expected nested bullet list")
        val multiBacktickCode = nestedBullet.items[0].inlines.filterIsInstance<MarkdownInline.Code>().firstOrNull()
        assertEquals("code with `tick` inside", multiBacktickCode?.text, "Expected multi-backtick code span")
        val deepOrdered = nestedBullet.items[0].children.filterIsInstance<MarkdownBlock.OrderedList>().firstOrNull()
        assertTrue(deepOrdered != null && deepOrdered.start == 1 && deepOrdered.items.size == 1)

        // Nested blockquote
        val outerQuote = blocks.filterIsInstance<MarkdownBlock.BlockQuote>().firstOrNull()
        assertTrue(
            outerQuote != null && outerQuote.blocks.any { it is MarkdownBlock.BlockQuote },
            "Expected nested BlockQuote inside outer BlockQuote",
        )

        // Flanking rules, escapes, bold+italic, inline link with parens, and reference links
        val para = blocks.filterIsInstance<MarkdownBlock.Paragraph>().first()
        val italics = para.inlines.filterIsInstance<MarkdownInline.Italic>()
        assertTrue(italics.isEmpty(), "Intraword foo_bar_baz and \\*escaped\\* must NOT become top-level italic: $para")
        val boldItalic = para.inlines.filterIsInstance<MarkdownInline.Bold>().firstOrNull()
        assertTrue(
            boldItalic != null && boldItalic.children.any { it is MarkdownInline.Italic },
            "Expected ***bold italic*** to parse as Bold(Italic(...))",
        )
        val links = para.inlines.filterIsInstance<MarkdownInline.Link>()
        assertEquals(4, links.size, "Expected 1 inline + 3 reference links, got $links")
        assertEquals("https://en.wikipedia.org/wiki/Kotlin_(programming_language)", links[0].url)
        assertTrue(links[0].children.any { it is MarkdownInline.Bold }, "Link label should parse nested Bold")
        assertEquals("https://www.jetbrains.com", links[1].url)
        assertEquals("https://commonmark.org", links[2].url)
        assertEquals("https://github.com/JetBrains/jewel", links[3].url)

        // Reference link definitions must NOT appear as stray paragraphs
        assertFalse(
            blocks.filterIsInstance<MarkdownBlock.Paragraph>().any { p ->
                p.inlines.filterIsInstance<MarkdownInline.Plain>().any { it.text.contains("[jetbrains]:") }
            },
            "Link reference definitions must be stripped from rendered paragraphs",
        )

        // GFM Table alignments + escaped pipes + code-span pipe
        val table = blocks.filterIsInstance<MarkdownBlock.Table>().first()
        assertEquals(
            listOf(MarkdownTableAlignment.Left, MarkdownTableAlignment.Center, MarkdownTableAlignment.Right),
            table.alignments,
        )
        assertEquals(3, table.rows[0].size)
        val cell0Code = table.rows[0][0].filterIsInstance<MarkdownInline.Code>().firstOrNull()
        assertEquals("a | b", cell0Code?.text)

        // 4-space indented code block
        val indentedCode = blocks.filterIsInstance<MarkdownBlock.CodeBlock>().firstOrNull()
        assertEquals("val indented = 1 + 2", indentedCode?.code)
    }

    @Test
    fun swingComposeInteropRendersRealOffscreenSwingJTreeAndJTextArea() {
        val imgA = renderRealSwingIdePaneBitmap(widthPx = 640, heightPx = 260, selectedTreeRow = 5)
        val imgB = renderRealSwingIdePaneBitmap(widthPx = 640, heightPx = 260, selectedTreeRow = 5)
        assertEquals(640, imgA.width)
        assertEquals(260, imgA.height)

        var leftDistinct = 0
        var rightDistinct = 0
        var foundSelectionBlue = false
        val leftRef = imgA.getRGB(4, 40)
        val rightRef = imgA.getRGB(300, 40)
        for (y in 0 until imgA.height) {
            for (x in 0 until imgA.width) {
                val argb = imgA.getRGB(x, y)
                assertEquals(argb, imgB.getRGB(x, y), "Offscreen Swing render must be deterministic")
                if (x < 170) {
                    if (argb != leftRef) leftDistinct++
                    val r = (argb ushr 16) and 0xFF
                    val g = (argb ushr 8) and 0xFF
                    val b = argb and 0xFF
                    if (r in 205..220 && g in 220..235 && b >= 245) {
                        foundSelectionBlue = true
                    }
                } else if (x > 210) {
                    if (argb != rightRef) rightDistinct++
                }
            }
        }
        assertTrue(leftDistinct > 200, "Left javax.swing.JTree pane must paint visible tree rows (got $leftDistinct px)")
        assertTrue(rightDistinct > 400, "Right javax.swing.JTextArea pane must paint visible JSON code (got $rightDistinct px)")
        assertTrue(foundSelectionBlue, "Selected JTree row should paint IntelliJ light selection highlight #D4E2FF")
    }

    @Test
    fun cohesionIdeShowcaseSuppressesDebugPanelPillInPresentSession() {
        val presentSession = RenderSession.forPresent(store = DeckDocumentStore(DeckDocument(id = "cohesion-check")))
        val presentFrame = renderScene(width = 1000, height = 700) {
            CompositionLocalProvider(LocalRenderSession provides presentSession) {
                RenderJewelOrNjptSpecimen(
                    ref = "jewel.cohesionIdeShowcase",
                    loopMillis = 0L,
                    params = mapOf("panel" to "ideFull"),
                    isInteractive = true,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        val nonInteractiveFrame = renderScene(width = 1000, height = 700) {
            RenderJewelOrNjptSpecimen(
                ref = "jewel.cohesionIdeShowcase",
                loopMillis = 0L,
                params = mapOf("panel" to "ideFull"),
                isInteractive = false,
                modifier = Modifier.fillMaxSize(),
            )
        }
        val standaloneInteractiveFrame = renderScene(width = 1000, height = 700) {
            RenderJewelOrNjptSpecimen(
                ref = "jewel.cohesionIdeShowcase",
                loopMillis = 0L,
                params = mapOf("panel" to "ideFull"),
                isInteractive = true,
                modifier = Modifier.fillMaxSize(),
            )
        }
        // In Present mode, the bottom-left debug panel pill is hidden so it matches non-interactive pixels cleanly!
        assertContentEquals(
            nonInteractiveFrame.rgbaBytes,
            presentFrame.rgbaBytes,
            "jewel.cohesionIdeShowcase must hide the debug panel pill when purpose == RenderPurpose.Present",
        )
        assertTrue(
            standaloneInteractiveFrame.diffRatio(presentFrame) > 0.001,
            "Standalone non-session interactive preview still distinguishes itself from suppressed Present frame",
        )
    }

    @Test
    fun compactSlideFramesAndVectorIconsRenderCleanlyAndProgressVisibly() {
        // S25/S30 GeminiAuthCard at exact compact slide dimensions (504x302)
        val authStart = renderScene(width = 504, height = 302) {
            RenderJewelOrNjptSpecimen("jewel.geminiAuthCard", loopMillis = 0L, isInteractive = false, modifier = Modifier.fillMaxSize())
        }
        val authDone = renderScene(width = 504, height = 302) {
            RenderJewelOrNjptSpecimen("jewel.geminiAuthCard", loopMillis = 3_200L, isInteractive = false, modifier = Modifier.fillMaxSize())
        }
        assertTrue(authStart.distinctColorBuckets() >= 12)
        assertTrue(authStart.diffRatio(authDone) > 0.005, "Compact GeminiAuthCard should progress from button to authorized state")

        // S29 ReferencesBouncyExpand at exact slide dimensions (466x371)
        val refCollapsed = renderScene(width = 466, height = 371) {
            RenderJewelOrNjptSpecimen("jewel.referencesBouncyExpand", loopMillis = 0L, isInteractive = false, modifier = Modifier.fillMaxSize())
        }
        val refExpanded = renderScene(width = 466, height = 371) {
            RenderJewelOrNjptSpecimen("jewel.referencesBouncyExpand", loopMillis = 1_400L, isInteractive = false, modifier = Modifier.fillMaxSize())
        }
        assertTrue(
            refCollapsed.diffRatio(refExpanded) > 0.03,
            "Compact ReferencesBouncyExpand at 466x371 must show References accordion expanding above the fold",
        )

        // S49-S50 MicrointeractionScrollFab natural scroll + top fade + FAB reveal
        val scrollTop = renderScene(width = 960, height = 600) {
            RenderJewelOrNjptSpecimen("jewel.microinteractionScrollFab", loopMillis = 400L, isInteractive = false, modifier = Modifier.fillMaxSize())
        }
        val scrollUp = renderScene(width = 960, height = 600) {
            RenderJewelOrNjptSpecimen("jewel.microinteractionScrollFab", loopMillis = 3_600L, isInteractive = false, modifier = Modifier.fillMaxSize())
        }
        assertTrue(
            scrollTop.diffRatio(scrollUp) > 0.05,
            "MicrointeractionScrollFab must visibly scroll content and animate the Jump-to-bottom FAB",
        )

        // S55 M3ExpressiveShowcase vector icons (zero tofu)
        val m3Mid = renderScene(width = 1200, height = 760) {
            RenderJewelOrNjptSpecimen("jewel.m3ExpressiveShowcase", loopMillis = 4_000L, isInteractive = false, modifier = Modifier.fillMaxSize())
        }
        assertTrue(m3Mid.distinctColorBuckets() >= 20)

        // S16 TaskBasedProfilers + S125/S128 NJPT Markdown windows at slide aspect ratios
        val profiler = renderScene(width = 960, height = 600) {
            RenderJewelOrNjptSpecimen("jewel.taskBasedProfilers", loopMillis = 0L, isInteractive = false, modifier = Modifier.fillMaxSize())
        }
        val njptSplit = renderScene(width = 1100, height = 680) {
            RenderJewelOrNjptSpecimen("njpt.jewelMarkdownSplitDemo", loopMillis = 1_500L, isInteractive = false, modifier = Modifier.fillMaxSize())
        }
        val njptDark = renderScene(width = 820, height = 620) {
            RenderJewelOrNjptSpecimen("njpt.geminiDarkMarkdownChat", loopMillis = 3_000L, isInteractive = false, modifier = Modifier.fillMaxSize())
        }
        assertTrue(profiler.distinctColorBuckets() >= 12)
        assertTrue(njptSplit.distinctColorBuckets() >= 15)
        assertTrue(njptDark.distinctColorBuckets() >= 15)
    }
}
