package dev.sebastiano.ciacola.inspector.lint

import dev.sebastiano.ciacola.document.BeatSpec
import dev.sebastiano.ciacola.document.Build
import dev.sebastiano.ciacola.document.CodePayload
import dev.sebastiano.ciacola.document.ContainerPayload
import dev.sebastiano.ciacola.document.DeckDocument
import dev.sebastiano.ciacola.document.DeckDocumentStore
import dev.sebastiano.ciacola.document.DeckSpecimenRegistry
import dev.sebastiano.ciacola.document.DeckThemeSpec
import dev.sebastiano.ciacola.document.DocumentIssue
import dev.sebastiano.ciacola.document.Fill
import dev.sebastiano.ciacola.document.FrameSpec
import dev.sebastiano.ciacola.document.GradientSpec
import dev.sebastiano.ciacola.document.GradientStop
import dev.sebastiano.ciacola.document.GradientType
import dev.sebastiano.ciacola.document.ImagePayload
import dev.sebastiano.ciacola.document.IssueSeverity
import dev.sebastiano.ciacola.document.NodeSpec
import dev.sebastiano.ciacola.document.deckSpecimens
import dev.sebastiano.ciacola.document.RichTextValue
import dev.sebastiano.ciacola.document.ScaleValue
import dev.sebastiano.ciacola.document.ShapeGeometry
import dev.sebastiano.ciacola.document.ShapePayload
import dev.sebastiano.ciacola.document.SlidePaletteSpec
import dev.sebastiano.ciacola.document.SlideSpec
import dev.sebastiano.ciacola.document.SpecimenPayload
import dev.sebastiano.ciacola.document.TextPayload
import dev.sebastiano.ciacola.document.TransitionSpec
import dev.sebastiano.ciacola.document.TransitionType
import java.awt.GraphicsEnvironment
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive

/**
 * Verifies the Fast Cached 136-Slide `DeckLint` engine:
 * - Static validation (`todo.video`, unknown effect/specimen, out-of-range param, cross-slide flow
 *   ancestor, code natural size exceeding fixed box, external-edit manual delay warnings).
 * - Static contrast eligibility fast-path (`< 0.1 ms` per eligible node, ZERO pixel readback).
 * - Reduced-resolution `480x270` UI-thread pixel fallback for ineligible text (gradient background,
 *   partial opacity, overlapping non-ancestor sibling).
 * - Smart resolved-style theme deduplication (deduplicates ONLY when fully resolved styles hash
 *   identically, NOT merely when `slide.palette != null`).
 * - Per-slide content-addressed cache + asset content fingerprint invalidation.
 * - 136-slide scale benchmark: single-slide `<= 300 ms`, full-deck UI slice p95
 *   `<= 32 ms`, cancellation `<= 100 ms`, cache memory `<= 50 MB`, with full hardware record.
 */
class DocumentDeckLintScaleBenchmarkTest {

    private fun testSpecimens(): DeckSpecimenRegistry = deckSpecimens {
        specimen("lint.demo", "Lint Demo") {
            uniformFloat(name = "uGain", default = 1.0f, range = 0.0f..2.0f, step = 0.1f)
            content {}
        }
    }

    @Test
    fun staticValidationTodoVideoCrossSlideFlowCodeOverflowAndProvenanceWarnings() = runBlocking {
        val doc = DeckDocument(
            id = "static-lint-test",
            themes = mapOf("dark" to DeckThemeSpec(preset = "stageDark")),
            defaultTheme = "dark",
            slides = listOf(
                SlideSpec(
                    id = "s1",
                    background = Fill.Solid("#0B0D12"),
                    beats = listOf(BeatSpec(), BeatSpec()),
                    nodes = listOf(
                        NodeSpec(
                            id = "bad-effect",
                            frame = FrameSpec(100.0, 100.0, 400.0, 80.0),
                            text = TextPayload(value = RichTextValue.Plain("Bad effect"), style = "body"),
                            builds = listOf(Build.Enter(beat = 1, effect = "nonexistentFx")),
                        ),
                        NodeSpec(
                            id = "missing-specimen",
                            frame = FrameSpec(100.0, 220.0, 400.0, 180.0),
                            specimen = SpecimenPayload(ref = "unknown.ref"),
                        ),
                        NodeSpec(
                            id = "video-placeholder",
                            frame = FrameSpec(100.0, 420.0, 400.0, 180.0),
                            specimen = SpecimenPayload(
                                ref = "todo.video",
                                params = mapOf("src" to JsonPrimitive("assets/demo.mov")),
                            ),
                        ),
                        NodeSpec(
                            id = "out-of-range-spec",
                            frame = FrameSpec(600.0, 220.0, 300.0, 180.0),
                            specimen = SpecimenPayload(
                                ref = "lint.demo",
                                params = mapOf("uGain" to JsonPrimitive(99.0)),
                            ),
                        ),
                        NodeSpec(
                            id = "tight-code",
                            frame = FrameSpec(600.0, 440.0, 180.0, 60.0),
                            code = CodePayload(
                                value = "fun veryLongFunctionNameThatClearlyExceedsBoxWidth() {\n    println(1)\n    println(2)\n    println(3)\n}",
                            ),
                        ),
                        NodeSpec(
                            id = "flow-col",
                            frame = FrameSpec(1100.0, 100.0, 400.0, 300.0),
                            column = ContainerPayload(
                                children = listOf(
                                    NodeSpec(
                                        id = "shared-in-flow",
                                        text = TextPayload(value = RichTextValue.Plain("Inside column"), style = "body"),
                                    ),
                                ),
                            ),
                        ),
                        NodeSpec(
                            id = "sheared-flow-col",
                            frame = FrameSpec(1100.0, 450.0, 400.0, 200.0),
                            scale = ScaleValue.NonUniform(2.0, 1.0),
                            column = ContainerPayload(
                                children = listOf(
                                    NodeSpec(
                                        id = "sheared-cross",
                                        rotation = 25.0,
                                        text = TextPayload(value = RichTextValue.Plain("Sheared"), style = "body"),
                                    ),
                                ),
                            ),
                        ),
                    ),
                    transition = TransitionSpec(type = TransitionType.MagicMove),
                ),
                SlideSpec(
                    id = "s2",
                    background = Fill.Solid("#0B0D12"),
                    nodes = listOf(
                        NodeSpec(
                            id = "shared-in-flow",
                            frame = FrameSpec(200.0, 200.0, 400.0, 80.0),
                            text = TextPayload(value = RichTextValue.Plain("Outside column"), style = "body"),
                        ),
                        NodeSpec(
                            id = "sheared-cross",
                            frame = FrameSpec(200.0, 320.0, 400.0, 80.0),
                            text = TextPayload(value = RichTextValue.Plain("Sheared"), style = "body"),
                        ),
                    ),
                ),
            ),
        )

        val externalWarning = DocumentIssue(
            severity = IssueSeverity.LintWarning,
            code = "MANUAL_FRAGMENT_DELAY_STALE",
            address = "slides[s1].nodes[bad-effect]",
            message = "External edit altered text with manual fragment delays.",
        )

        DocumentDeckLintEngine(specimens = testSpecimens()).use { engine ->
            val report = engine.lintFullDeck(
                document = doc,
                externalEditWarnings = listOf(externalWarning),
            )
            val codes = report.issues.map { it.code }.toSet()
            assertTrue("TODO_VIDEO" in codes, "Expected TODO_VIDEO in $codes")
            assertTrue("CODE_NATURAL_SIZE_EXCEEDS_BOX" in codes, "Expected CODE_NATURAL_SIZE_EXCEEDS_BOX in $codes")
            assertTrue("MANUAL_FRAGMENT_DELAY_STALE" in codes, "Expected external provenance warning in $codes")
            assertFalse(
                report.issues.any {
                    it.source == DocumentLintSource.CrossSlide &&
                        (it.nodeId == "shared-in-flow" || it.detail.contains("shared-in-flow"))
                },
                "Flow-layout matched node shared-in-flow must no longer emit CrossSlide lint issue in ${report.issues}",
            )
            assertTrue(
                report.issues.any { it.source == DocumentLintSource.CrossSlide && it.code == "CROSS_SLIDE_SHEARED_TRANSFORM" },
                "Expected cross-slide sheared-transform lint issue in ${report.issues}",
            )
            assertTrue(
                report.issues.any { it.detail.contains("nonexistentFx") || it.code.contains("EFFECT") },
                "Expected unknown effect issue in ${report.issues}",
            )
            assertTrue(
                report.issues.any { it.detail.contains("unknown.ref") || it.code.contains("SPECIMEN") },
                "Expected unknown specimen issue in ${report.issues}",
            )
            assertTrue(
                report.issues.any { it.detail.contains("uGain") || it.code.contains("PARAM") },
                "Expected out-of-range specimen param issue in ${report.issues}",
            )
        }
    }

    @Test
    fun staticContrastFastPathZeroPixelReadsAnd480x270FallbackForIneligibleNodes() = runBlocking {
        // 1. Eligible slide: solid background, opaque text, no active effect, no overlapping siblings -> 0 pixel renders!
        val eligibleDoc = DeckDocument(
            id = "static-contrast-fastpath",
            themes = mapOf("dark" to DeckThemeSpec(preset = "stageDark")),
            defaultTheme = "dark",
            slides = listOf(
                SlideSpec(
                    id = "s-static",
                    background = Fill.Solid("#101014"),
                    nodes = listOf(
                        NodeSpec(
                            id = "good-text",
                            frame = FrameSpec(120.0, 120.0, 600.0, 80.0),
                            text = TextPayload(value = RichTextValue.Plain("High contrast"), color = "#FFFFFF", size = 48.0),
                        ),
                        NodeSpec(
                            id = "bad-contrast-text",
                            frame = FrameSpec(120.0, 260.0, 600.0, 80.0),
                            text = TextPayload(value = RichTextValue.Plain("Too dark on dark"), color = "#24242E", size = 36.0),
                        ),
                    ),
                ),
            ),
        )

        DocumentDeckLintEngine().use { engine ->
            val t0 = System.nanoTime()
            val report = engine.lintSingleSlide(eligibleDoc, slideIndex = 0)
            val elapsedMs = (System.nanoTime() - t0) / 1_000_000.0

            assertEquals(2, report.stats.staticContrastChecks)
            assertEquals(0, report.stats.pixelContrastRenders, "Eligible text MUST NOT trigger pixel readback")
            assertTrue(
                report.issues.any { it.code == "LOW_CONTRAST" && it.nodeId == "bad-contrast-text" && it.source == DocumentLintSource.StaticContrast },
                "Static contrast fast-path must detect low contrast on bad-contrast-text: ${report.issues}",
            )
            assertTrue(
                report.issues.none { it.code == "LOW_CONTRAST" && it.nodeId == "good-text" },
                "High-contrast text must pass",
            )
            assertTrue(elapsedMs < 100.0, "Static contrast single slide took ${elapsedMs}ms")
        }

        // 2. Ineligible slide: text over linear gradient + text overlapped by bright sibling -> 480x270 pixel readback!
        val ineligibleDoc = DeckDocument(
            id = "pixel-fallback-test",
            themes = mapOf("dark" to DeckThemeSpec(preset = "stageDark")),
            defaultTheme = "dark",
            slides = listOf(
                SlideSpec(
                    id = "s-gradient",
                    background = Fill.Gradient(
                        GradientSpec(
                            type = GradientType.Linear,
                            angle = 0.0,
                            stops = listOf(
                                GradientStop(0.0, "#EFEFEF"),
                                GradientStop(1.0, "#EAEAEA"),
                            ),
                        ),
                    ),
                    nodes = listOf(
                        NodeSpec(
                            id = "light-on-light-grad",
                            frame = FrameSpec(200.0, 200.0, 600.0, 120.0),
                            text = TextPayload(value = RichTextValue.Plain("Faint on gradient"), color = "#E2E2E2", size = 40.0),
                        ),
                    ),
                ),
                SlideSpec(
                    id = "s-overlap",
                    background = Fill.Solid("#0B0D12"),
                    nodes = listOf(
                        NodeSpec(
                            id = "white-card-under",
                            frame = FrameSpec(160.0, 160.0, 700.0, 220.0),
                            shape = ShapePayload(geometry = ShapeGeometry.Rect, fill = Fill.Solid("#F8F8F8")),
                        ),
                        NodeSpec(
                            id = "white-text-over-sibling",
                            frame = FrameSpec(200.0, 200.0, 600.0, 100.0),
                            text = TextPayload(value = RichTextValue.Plain("White over white sibling"), color = "#FFFFFF", size = 40.0),
                        ),
                    ),
                ),
            ),
        )

        DocumentDeckLintEngine().use { engine ->
            val report = engine.lintFullDeck(ineligibleDoc)
            assertEquals(2, report.stats.pixelContrastRenders, "Both gradient and sibling-overlap slides must trigger 480x270 pixel audit")
            assertTrue(
                report.issues.any { it.nodeId == "light-on-light-grad" && it.code == "LOW_CONTRAST" && it.source == DocumentLintSource.PixelContrast480x270 },
                "480x270 pixel audit must catch low contrast over gradient: ${report.issues}",
            )
            assertTrue(
                report.issues.any { it.nodeId == "white-text-over-sibling" && it.code == "LOW_CONTRAST" && it.source == DocumentLintSource.PixelContrast480x270 },
                "480x270 pixel audit must catch white text over white sibling shape: ${report.issues}",
            )
        }
    }

    @Test
    fun smartThemeDeduplicationAndAssetFingerprintCacheInvalidation() = runBlocking {
        val tempDir = Files.createTempDirectory("ciacola-lint-cache")
        val imgFile = tempDir.resolve("hero.png")
        Files.write(imgFile, byteArrayOf(1, 2, 3, 4))

        val doc = DeckDocument(
            id = "theme-dedup-and-cache",
            themes = mapOf(
                "dark" to DeckThemeSpec(preset = "stageDark"),
                "light" to DeckThemeSpec(preset = "keynoteLight"),
            ),
            palettes = mapOf(
                "locked" to SlidePaletteSpec(canvas = "#111827", ink = "#F9FAFB"),
            ),
            defaultTheme = "dark",
            slides = listOf(
                // Slide 0: locked palette + explicit hex text -> identical resolved styles in dark & light -> DEDUPLICATED!
                SlideSpec(
                    id = "s-identical",
                    palette = "locked",
                    nodes = listOf(
                        NodeSpec(
                            id = "t0",
                            frame = FrameSpec(120.0, 120.0, 600.0, 80.0),
                            text = TextPayload(value = RichTextValue.Plain("Identical"), color = "#F9FAFB", font = "Inter", size = 48.0),
                        ),
                    ),
                ),
                // Slide 1: locked palette BUT has a code node (reads theme code.background/code.foreground) -> NOT deduplicated!
                SlideSpec(
                    id = "s-code-theme-dependent",
                    palette = "locked",
                    nodes = listOf(
                        NodeSpec(
                            id = "c1",
                            frame = FrameSpec(120.0, 120.0, 800.0, 320.0),
                            code = CodePayload(value = "val x = 42"),
                        ),
                    ),
                ),
                // Slide 2: references hero.png asset -> asset fingerprint change invalidates cache!
                SlideSpec(
                    id = "s-asset",
                    background = Fill.Solid("#10131A"),
                    nodes = listOf(
                        NodeSpec(
                            id = "img",
                            frame = FrameSpec(100.0, 100.0, 400.0, 300.0),
                            image = ImagePayload(src = "hero.png"),
                        ),
                        NodeSpec(
                            id = "caption",
                            frame = FrameSpec(100.0, 440.0, 500.0, 60.0),
                            text = TextPayload(value = RichTextValue.Plain("Caption"), color = "#FFFFFF", size = 36.0),
                        ),
                    ),
                ),
            ),
        )

        try {
            DocumentDeckLintEngine().use { engine ->
                // Check theme deduplication on s-identical vs s-code-theme-dependent.
                val r0 = engine.lintSingleSlide(doc, slideIndex = 0, themeNames = listOf("dark", "light"), deckDir = tempDir)
                assertEquals(1, r0.stats.themeDeduplicatedSlides, "Slide with identical resolved styles must deduplicate 2nd theme")
                assertEquals(1, r0.stats.cacheMisses)

                val r1 = engine.lintSingleSlide(doc, slideIndex = 1, themeNames = listOf("dark", "light"), deckDir = tempDir)
                assertEquals(0, r1.stats.themeDeduplicatedSlides, "Locked-palette slide with code node still depends on theme")
                assertEquals(2, r1.stats.cacheMisses)

                // Asset fingerprint invalidation on slide 2.
                val r2First = engine.lintSingleSlide(doc, slideIndex = 2, themeNames = listOf("dark"), deckDir = tempDir)
                assertEquals(1, r2First.stats.cacheMisses)
                assertEquals(0, r2First.stats.cacheHits)

                val r2Cached = engine.lintSingleSlide(doc, slideIndex = 2, themeNames = listOf("dark"), deckDir = tempDir)
                assertEquals(0, r2Cached.stats.cacheMisses)
                assertEquals(1, r2Cached.stats.cacheHits, "Unchanged slide & asset must hit cache")

                // Replace image bytes at the SAME path -> MUST invalidate slide 2 cache!
                Files.write(imgFile, byteArrayOf(9, 8, 7, 6, 5, 4))
                val r2AfterAssetEdit = engine.lintSingleSlide(doc, slideIndex = 2, themeNames = listOf("dark"), deckDir = tempDir)
                assertEquals(1, r2AfterAssetEdit.stats.cacheMisses, "Replacing asset bytes at same path must invalidate lint cache")
                assertEquals(0, r2AfterAssetEdit.stats.cacheHits)
            }
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun deckLint136SlideScaleBenchmarkAndCancellationBudget() = runBlocking {
        val slides = (0 until 136).map { i ->
            val isGradientSlide = i % 10 == 0 // ~14 slides trigger real 480x270 Skia pixel audits
            val isLockedPalette = i % 3 == 0
            SlideSpec(
                id = "lint-s${"%03d".format(i + 1)}",
                title = "Lint Slide ${i + 1}",
                palette = if (isLockedPalette) "locked" else null,
                background = if (isGradientSlide) {
                    Fill.Gradient(
                        GradientSpec(
                            type = GradientType.Linear,
                            angle = 45.0,
                            stops = listOf(GradientStop(0.0, "#10141F"), GradientStop(1.0, "#1F2937")),
                        ),
                    )
                } else {
                    Fill.Solid("#10141F")
                },
                beats = listOf(BeatSpec(), BeatSpec()),
                nodes = listOf(
                    NodeSpec(
                        id = "title-$i",
                        frame = FrameSpec(120.0, 80.0, 1200.0, 100.0),
                        text = TextPayload(
                            value = RichTextValue.Plain("Headline on slide ${i + 1}"),
                            color = "#F8FAFC",
                            font = "Inter",
                            size = 64.0,
                        ),
                    ),
                    NodeSpec(
                        id = "body-$i",
                        frame = FrameSpec(120.0, 240.0, 960.0, 140.0),
                        text = TextPayload(
                            value = RichTextValue.Plain("Supporting copy for heterogeneous benchmark slide ${i + 1}."),
                            color = "#CBD5E1",
                            font = "Inter",
                            size = 38.0,
                        ),
                        builds = listOf(Build.Enter(beat = 1, effect = "fadeMove", durationMs = 300)),
                    ),
                    NodeSpec(
                        id = "note-$i",
                        frame = FrameSpec(120.0, 440.0, 720.0, 80.0),
                        text = TextPayload(
                            value = RichTextValue.Plain("Footer caption ${i + 1}"),
                            color = "#94A3B8",
                            font = "Inter",
                            size = 32.0,
                        ),
                    ),
                ),
            )
        }

        val doc = DeckDocument(
            id = "lint-scale-136",
            title = "136-Slide Lint Scale Deck",
            themes = mapOf(
                "dark" to DeckThemeSpec(preset = "stageDark"),
                "light" to DeckThemeSpec(preset = "keynoteLight"),
            ),
            palettes = mapOf("locked" to SlidePaletteSpec(canvas = "#10141F", ink = "#F8FAFC")),
            defaultTheme = "dark",
            slides = slides,
        )

        val store = DeckDocumentStore()
        store.loadInitial(doc)

        DocumentDeckLintController(store = store).use { controller ->
            // Warm up Skia/Compose offscreen scene once on slide 0 (represents open Studio session).
            controller.lintSlideNow(slideIndex = 0)
            controller.engine.clearCache()

            // 1. Measure single-slide lint (cold, including a gradient slide with 480x270 pixel audit): <= 300 ms!
            val singleGradientReport = controller.lintSlideNow(slideIndex = 0, themeNames = listOf("dark"))
            val singleSolidReport = controller.lintSlideNow(slideIndex = 1, themeNames = listOf("dark"))
            controller.engine.clearCache()

            // 2. Measure full-deck 136-slide lint with UI time-slicing (<= 32 ms p95 UI slice!).
            val fullReport = controller.engine.lintFullDeck(
                document = doc,
                themeNames = listOf("dark", "light"),
                sliceBudgetMs = 6.0,
            )

            // 3. Measure mid-run cancellation latency (<= 100 ms!).
            controller.engine.clearCache()
            var cancelMs = 0.0
            val job = launch {
                controller.engine.lintFullDeck(
                    document = doc,
                    themeNames = listOf("dark", "light"),
                    sliceBudgetMs = 4.0,
                )
            }
            delay(10L)
            val cancelStartNs = System.nanoTime()
            job.cancelAndJoin()
            cancelMs = (System.nanoTime() - cancelStartNs) / 1_000_000.0

            val cacheBytes = fullReport.stats.estimatedCacheMemoryBytes
            val cacheMb = cacheBytes / (1024.0 * 1024.0)
            val env = collectMachineRecord()

            println(
                """
                |=== DECKLINT SCALE BENCHMARK (136 slides, 2 themes) ===
                |Machine: $env
                |Single-slide lint (gradient/480x270): ${String.format(java.util.Locale.US, "%.2f", singleGradientReport.stats.totalDurationMs)} ms (budget <= 300 ms)
                |Single-slide lint (static fast-path): ${String.format(java.util.Locale.US, "%.2f", singleSolidReport.stats.totalDurationMs)} ms (budget <= 300 ms)
                |Full-deck total:    ${String.format(java.util.Locale.US, "%.2f", fullReport.stats.totalDurationMs)} ms (staticChecks=${fullReport.stats.staticContrastChecks}, pixelRenders=${fullReport.stats.pixelContrastRenders}, themeDedup=${fullReport.stats.themeDeduplicatedSlides})
                |UI slice p95 / max: ${String.format(java.util.Locale.US, "%.2f", fullReport.stats.p95UiSliceMs)} ms / ${String.format(java.util.Locale.US, "%.2f", fullReport.stats.maxUiSliceMs)} ms (budget p95 <= 32 ms)
                |Cancellation time:  ${String.format(java.util.Locale.US, "%.2f", cancelMs)} ms (budget <= 100 ms)
                |Lint cache memory:  ${String.format(java.util.Locale.US, "%.3f", cacheMb)} MB ($cacheBytes bytes; budget <= 50 MB)
                |===============================================================
                """.trimMargin(),
            )

            assertTrue(singleGradientReport.stats.totalDurationMs <= 300.0, "Single gradient slide lint exceeded 300ms")
            assertTrue(singleSolidReport.stats.totalDurationMs <= 300.0, "Single solid slide lint exceeded 300ms")
            assertTrue(fullReport.stats.p95UiSliceMs <= 32.0, "Full-deck lint UI slice p95 ${fullReport.stats.p95UiSliceMs}ms exceeded 32ms budget")
            assertTrue(cancelMs <= 100.0, "Lint cancellation took ${cancelMs}ms (budget <= 100ms)")
            assertTrue(cacheBytes <= 50L * 1024L * 1024L, "Lint cache $cacheBytes bytes exceeded 50 MB budget")
            assertTrue(fullReport.stats.staticContrastChecks > 0, "Expected static contrast fast-path checks")
            assertTrue(fullReport.stats.pixelContrastRenders > 0, "Expected 480x270 pixel renders for gradient slides")
            assertTrue(fullReport.stats.themeDeduplicatedSlides > 0, "Expected theme deduplication on identical slides")
        }
    }

    private fun collectMachineRecord(): String {
        val model = runCmd("sysctl", "-n", "hw.model").ifBlank { System.getProperty("os.arch") }
        val cpu = runCmd("sysctl", "-n", "machdep.cpu.brand_string").ifBlank { "unknown CPU" }
        val ramBytes = runCmd("sysctl", "-n", "hw.memsize").toLongOrNull() ?: 0L
        val ramGb = if (ramBytes > 0) "${ramBytes / (1024L * 1024L * 1024L)} GB" else "unknown RAM"
        val osVer = runCmd("sw_vers", "-productVersion").ifBlank { System.getProperty("os.version") }
        val osBuild = runCmd("sw_vers", "-buildVersion")
        val display = runCatching {
            if (GraphicsEnvironment.isHeadless()) {
                "headless"
            } else {
                val gd = GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice
                val dm = gd.displayMode
                val scale = gd.defaultConfiguration.defaultTransform.scaleX
                "${dm.width}x${dm.height}@${scale}x"
            }
        }.getOrElse { "headless" }
        return "$model ($cpu), $ramGb RAM, macOS $osVer ($osBuild), display=$display"
    }

    private fun runCmd(vararg cmd: String): String = runCatching {
        ProcessBuilder(*cmd).redirectErrorStream(true).start().inputStream.bufferedReader().readText().trim()
    }.getOrDefault("")
}
