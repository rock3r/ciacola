package dev.sebastiano.ciacola.decks

import dev.sebastiano.ciacola.document.BeatSpec
import dev.sebastiano.ciacola.document.DeckDocument
import dev.sebastiano.ciacola.document.DeckThemeSpec
import dev.sebastiano.ciacola.document.Fill
import dev.sebastiano.ciacola.document.FrameSpec
import dev.sebastiano.ciacola.document.NodeSpec
import dev.sebastiano.ciacola.document.RichTextValue
import dev.sebastiano.ciacola.document.SlideSpec
import dev.sebastiano.ciacola.document.TextPayload
import dev.sebastiano.ciacola.document.TransitionSpec
import dev.sebastiano.ciacola.document.TransitionType
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class DeckAgentCliIntegrationTest {

    @Test
    fun `cli lints foundation deck cleanly with machine readable json report`() {
        val (exitCode, stdout) = captureStdout {
            DeckAgentCli.runCli(arrayOf("--lint", "--deck=foundation", "--json"))
        }
        assertEquals(0, exitCode, "Expected --lint on foundation to pass:\n$stdout")
        val obj = Json.parseToJsonElement(stdout).jsonObject
        assertEquals("foundation", obj["deckId"]?.jsonPrimitive?.content)
        assertTrue(obj["passed"]?.jsonPrimitive?.boolean == true)
        assertEquals(0, obj["errorCount"]?.jsonPrimitive?.int)
        assertEquals(5, obj["slidesAudited"]?.jsonPrimitive?.int)
    }

    @Test
    fun `cli lint reports schema and contrast failures cleanly and exits non zero`() {
        val tmpDir = Files.createTempDirectory("ciacola-cli-lint-fail-")
        try {
            val badJsonFile = tmpDir.resolve("broken.deck.json")
            Files.writeString(badJsonFile, """{"format":1,"id":"broken", invalid""", StandardCharsets.UTF_8)

            val (badExit, badOut) = captureStdout {
                DeckAgentCli.runCli(arrayOf("--lint", "--deck=${badJsonFile.toAbsolutePath()}", "--json"))
            }
            assertEquals(1, badExit)
            val badObj = Json.parseToJsonElement(badOut).jsonObject
            assertFalse(badObj["passed"]?.jsonPrimitive?.boolean == true)
            assertTrue((badObj["errorCount"]?.jsonPrimitive?.int ?: 0) >= 1)

            val lowContrastFile = tmpDir.resolve("low-contrast.deck.json")
            Files.writeString(
                lowContrastFile,
                """
                {
                  "format": 1,
                  "id": "low-contrast",
                  "title": "Low Contrast",
                  "defaultTheme": "dark",
                  "themes": {
                    "dark": {
                      "preset": "stageDark",
                      "colors": { "background": "#101218", "foreground": "#181B22" }
                    }
                  },
                  "slides": [
                    {
                      "id": "s1",
                      "title": "Hard to read",
                      "nodes": [
                        {
                          "id": "n1",
                          "frame": { "x": 120, "y": 120, "w": 600, "h": 120 },
                          "text": { "value": "Invisible text", "color": "#181B22", "size": 48 }
                        }
                      ]
                    }
                  ]
                }
                """.trimIndent(),
                StandardCharsets.UTF_8,
            )

            val (contrastExit, contrastOut) = captureStdout {
                DeckAgentCli.runCli(arrayOf("--lint", "--deck=${lowContrastFile.toAbsolutePath()}", "--json"))
            }
            assertEquals(1, contrastExit, "Expected low contrast deck to fail lint:\n$contrastOut")
            val contrastObj = Json.parseToJsonElement(contrastOut).jsonObject
            assertFalse(contrastObj["passed"]?.jsonPrimitive?.boolean == true)
            assertTrue(
                contrastObj["issues"]?.jsonArray?.any {
                    it.jsonObject["code"]?.jsonPrimitive?.content?.contains("CONTRAST") == true
                } == true,
                "Expected contrast issue in report:\n$contrastOut",
            )
        } finally {
            tmpDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `cli renders foundation slide 1 stills into out dir with valid png bytes and manifest`() {
        val outDir = Files.createTempDirectory("ciacola-cli-render-slides-")
        try {
            val (exitCode, stdout) = captureStdout {
                DeckAgentCli.runCli(
                    arrayOf(
                        "--render-slides",
                        "--deck=foundation",
                        "--slides=1",
                        "--size=1280x720",
                        "--out=${outDir.toAbsolutePath()}",
                    ),
                )
            }
            assertEquals(0, exitCode, "Expected --render-slides to succeed:\n$stdout")

            val manifestFile = outDir.resolve("manifest.json")
            assertTrue(Files.isRegularFile(manifestFile), "Expected manifest.json at $manifestFile")

            val manifest = Json.parseToJsonElement(Files.readString(manifestFile, StandardCharsets.UTF_8)).jsonObject
            assertEquals("foundation", manifest["deckId"]?.jsonPrimitive?.content)
            assertEquals(1280, manifest["width"]?.jsonPrimitive?.int)
            assertEquals(720, manifest["height"]?.jsonPrimitive?.int)

            val slides = manifest["slides"]?.jsonArray
            assertEquals(1, slides?.size)
            val beats = slides!!.first().jsonObject["beats"]?.jsonArray
            assertEquals(3, beats?.size, "Foundation slide 1 has 3 beats")

            val pngMagic = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
            for (beatObj in beats!!) {
                val pngPath = java.nio.file.Path.of(beatObj.jsonObject["path"]!!.jsonPrimitive.content)
                assertTrue(Files.isRegularFile(pngPath), "Missing rendered PNG: $pngPath")
                val bytes = Files.readAllBytes(pngPath)
                assertTrue(bytes.size > 1_000, "Rendered PNG unexpectedly small (${bytes.size} bytes): $pngPath")
                assertEquals(
                    pngMagic.toList(),
                    bytes.take(8),
                    "File does not begin with PNG magic bytes: $pngPath",
                )
            }
        } finally {
            outDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `cli helper parsers handle slide ranges and resolutions`() {
        assertEquals(listOf(1..1, 3..5, 8..9), DeckAgentCli.parseSlideRanges("1, 3-5, 8..9"))
        val sz = DeckAgentCli.parseSize("1920x1080")
        assertEquals(1920, sz.width)
        assertEquals(1080, sz.height)
    }

    @Test
    fun `assetExistsOnDiskOrClasspath rejects path traversal outside deck and assets dirs`() {
        val parentDir = Files.createTempDirectory("ciacola-cli-sec-parent-")
        try {
            val outsideFile = parentDir.resolve("secret.png")
            Files.write(outsideFile, byteArrayOf(1, 2, 3, 4))

            val deckDir = Files.createDirectories(parentDir.resolve("deck-dir"))
            val assetsDir = Files.createDirectories(deckDir.resolve("demo.assets"))
            Files.write(assetsDir.resolve("valid.png"), byteArrayOf(5, 6, 7, 8))

            assertTrue(DeckAgentCli.assetExistsOnDiskOrClasspath("demo", "valid.png", deckDir, assetsDir))
            assertFalse(DeckAgentCli.assetExistsOnDiskOrClasspath("demo", "../secret.png", deckDir, assetsDir))
            assertFalse(DeckAgentCli.assetExistsOnDiskOrClasspath("demo", "../../secret.png", deckDir, assetsDir))
            assertFalse(DeckAgentCli.assetExistsOnDiskOrClasspath("demo", "../../../../etc/passwd", deckDir, assetsDir))
        } finally {
            parentDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `cli lint reports 1-based non-skipped slideNumber when earlier slide is skipped`() {
        val tmpDir = Files.createTempDirectory("ciacola-cli-skipped-lint-")
        try {
            val deckFile = tmpDir.resolve("skipped-lint.deck.json")
            Files.writeString(
                deckFile,
                """
                {
                  "format": 1,
                  "id": "skipped-lint",
                  "title": "Skipped Lint",
                  "defaultTheme": "dark",
                  "themes": {
                    "dark": {
                      "preset": "stageDark",
                      "colors": { "background": "#101218", "foreground": "#F8FAFC" }
                    }
                  },
                  "slides": [
                    {
                      "id": "s-skip",
                      "title": "Skipped Intro",
                      "skipped": true,
                      "nodes": []
                    },
                    {
                      "id": "s-first-visible",
                      "title": "First Visible",
                      "nodes": [
                        {
                          "id": "n1",
                          "frame": { "x": 120, "y": 120, "w": 600, "h": 120 },
                          "text": { "value": "Low contrast", "color": "#181B22", "size": 48 }
                        }
                      ]
                    }
                  ]
                }
                """.trimIndent(),
                StandardCharsets.UTF_8,
            )

            val (jsonExit, jsonOut) = captureStdout {
                DeckAgentCli.runCli(arrayOf("--lint", "--deck=${deckFile.toAbsolutePath()}", "--slides=1", "--json"))
            }
            assertEquals(1, jsonExit, "Expected low contrast on S1 to fail:\n$jsonOut")
            val jsonObj = Json.parseToJsonElement(jsonOut).jsonObject
            val issues = jsonObj["issues"]!!.jsonArray
            assertTrue(issues.isNotEmpty())
            val firstIssue = issues.first().jsonObject
            assertEquals(1, firstIssue["slideNumber"]?.jsonPrimitive?.int, "Non-skipped slideNumber must be 1:\n$jsonOut")
            assertEquals("s-first-visible", firstIssue["slideId"]?.jsonPrimitive?.content)

            val (textExit, textOut) = captureStdout {
                DeckAgentCli.runCli(arrayOf("--lint", "--deck=${deckFile.toAbsolutePath()}", "--slides=1"))
            }
            assertEquals(1, textExit)
            assertTrue(textOut.contains("S1:B0"), "Expected S1:B0 in text lint report:\n$textOut")
        } finally {
            tmpDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `prepareExportDocument handles contiguous vs non-contiguous ranges and strips zero autoAdvanceMs`() {
        val doc = DeckDocument(
            id = "export-prep",
            title = "Export Prep",
            defaultTheme = "dark",
            themes = mapOf("dark" to DeckThemeSpec(preset = "stageDark")),
            slides = listOf(
                SlideSpec(id = "s0-skip", title = "Skipped", skipped = true),
                SlideSpec(
                    id = "s1",
                    title = "One",
                    beats = listOf(BeatSpec(autoAdvanceMs = 0)),
                    transition = TransitionSpec(type = TransitionType.Dissolve, durationMs = 250),
                ),
                SlideSpec(
                    id = "s2",
                    title = "Two",
                    beats = listOf(BeatSpec(autoAdvanceMs = 500)),
                    transition = TransitionSpec(type = TransitionType.Push, durationMs = 400),
                ),
                SlideSpec(
                    id = "s3",
                    title = "Three",
                    beats = listOf(BeatSpec(autoAdvanceMs = 0)),
                ),
            ),
        )

        val contiguous = DeckAgentCli.prepareExportDocument(doc, listOf(1..2))
        assertEquals(listOf("s1", "s2"), contiguous.document.slides.map { it.id })
        assertEquals(listOf(1, 2), contiguous.slides.map { it.originalSlideNumber1Based })
        assertEquals(0, contiguous.document.slides[0].beats.single().autoAdvanceMs)
        assertEquals(TransitionType.Dissolve, contiguous.document.slides[0].transition?.type)

        val nonContiguous = DeckAgentCli.prepareExportDocument(doc, listOf(1..1, 3..3))
        assertEquals(listOf("s1", "s3"), nonContiguous.document.slides.map { it.id })
        assertEquals(listOf(1, 3), nonContiguous.slides.map { it.originalSlideNumber1Based })
        assertNull(
            nonContiguous.document.slides[0].beats.single().autoAdvanceMs,
            "Non-contiguous cut must strip autoAdvanceMs == 0 on last beat",
        )
        assertEquals(TransitionType.FadeThroughColor, nonContiguous.document.slides[0].transition?.type)
        assertEquals("#000000", nonContiguous.document.slides[0].transition?.color)
        assertEquals(300, nonContiguous.document.slides[0].transition?.durationMs)
    }

    @Test
    fun `cli exports fast 1-slide video to mp4 and spans tsv`() {
        val tmpDir = Files.createTempDirectory("ciacola-cli-export-video-")
        try {
            val deckFile = tmpDir.resolve("mini-video.deck.json")
            Files.writeString(
                deckFile,
                """
                {
                  "format": 1,
                  "id": "mini-video",
                  "title": "Mini Video",
                  "defaultTheme": "dark",
                  "themes": {
                    "dark": {
                      "preset": "stageDark",
                      "colors": { "background": "#101218", "foreground": "#F8FAFC" }
                    }
                  },
                  "slides": [
                    {
                      "id": "s1",
                      "title": "Title",
                      "beats": [ { "autoAdvanceMs": 200 } ],
                      "nodes": [
                        {
                          "id": "n1",
                          "frame": { "x": 120, "y": 120, "w": 600, "h": 120 },
                          "text": { "value": "Video frame", "color": "#F8FAFC", "size": 48 }
                        }
                      ]
                    }
                  ]
                }
                """.trimIndent(),
                StandardCharsets.UTF_8,
            )
            val mp4Out = tmpDir.resolve("out/mini.mp4")
            val spansOut = tmpDir.resolve("out/mini.spans.tsv")

            val (exitCode, stdout) = captureStdout {
                DeckAgentCli.runCli(
                    arrayOf(
                        "--export-video",
                        "--deck=${deckFile.toAbsolutePath()}",
                        "--slides=1",
                        "--fps=10",
                        "--size=320x180",
                        "--out=${mp4Out.toAbsolutePath()}",
                    ),
                )
            }
            assertEquals(0, exitCode, "Expected --export-video to succeed:\n$stdout")
            assertTrue(Files.isRegularFile(mp4Out), "Expected MP4 file at $mp4Out")
            assertTrue(Files.size(mp4Out) > 100L, "Expected non-empty MP4 file, size=${Files.size(mp4Out)}")
            assertTrue(Files.isRegularFile(spansOut), "Expected spans TSV at $spansOut")
            val tsvLines = Files.readAllLines(spansOut, StandardCharsets.UTF_8)
            assertTrue(tsvLines.size >= 2, "Expected header + at least 1 span row, got:\n${tsvLines.joinToString("\n")}")
            assertTrue(tsvLines.first().startsWith("beatOrdinal\toriginalSlideNumber\tslideId\t"))
            assertTrue(tsvLines[1].contains("\ts1\tTitle\t0\t"))
        } finally {
            tmpDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `cli validates theme names rejects empty beat filter and matches filename stem`() {
        val tmpDir = Files.createTempDirectory("ciacola-cli-checks-")
        try {
            val deckFile = tmpDir.resolve("speaker-notes-talk.deck.json")
            Files.writeString(
                deckFile,
                """
                {
                  "format": 1,
                  "id": "custom-internal-id",
                  "title": "Speaker Talk",
                  "defaultTheme": "dark",
                  "themes": {
                    "dark": { "preset": "stageDark", "colors": { "background": "#101218", "foreground": "#F8FAFC" } },
                    "light": { "preset": "keynoteLight", "colors": { "background": "#FFFFFF", "foreground": "#111827" } }
                  },
                  "slides": [
                    {
                      "id": "s1",
                      "title": "Intro",
                      "nodes": [
                        {
                          "id": "n1",
                          "frame": { "x": 120, "y": 120, "w": 600, "h": 120 },
                          "text": { "value": "Hello", "color": "foreground", "size": 48 }
                        }
                      ]
                    }
                  ]
                }
                """.trimIndent(),
                StandardCharsets.UTF_8,
            )

            // 1. Lookup by filename stem when internal id differs.
            val (stemExit, stemOut) = captureStdout {
                DeckAgentCli.runCli(
                    arrayOf(
                        "--lint",
                        "--deck=speaker-notes-talk",
                        "--dir=${tmpDir.toAbsolutePath()}",
                        "--json",
                    ),
                )
            }
            assertEquals(0, stemExit, "Expected filename-stem lookup to succeed:\n$stemOut")
            val stemObj = Json.parseToJsonElement(stemOut).jsonObject
            assertEquals("custom-internal-id", stemObj["deckId"]?.jsonPrimitive?.content)

            // 2. Unknown --theme fails cleanly and lists valid themes across modes.
            val (lintThemeExit, lintThemeOut) = captureStdout {
                DeckAgentCli.runCli(arrayOf("--lint", "--deck=${deckFile.toAbsolutePath()}", "--theme=darck", "--json"))
            }
            assertEquals(1, lintThemeExit)
            val lintThemeObj = Json.parseToJsonElement(lintThemeOut).jsonObject
            val lintErrDetail = lintThemeObj["issues"]!!.jsonArray.first().jsonObject["detail"]!!.jsonPrimitive.content
            assertTrue(lintErrDetail.contains("dark") && lintErrDetail.contains("light"), lintThemeOut)

            val (renderThemeExit, renderThemeErr) = captureStderr {
                DeckAgentCli.runCli(
                    arrayOf(
                        "--render-slides",
                        "--deck=${deckFile.toAbsolutePath()}",
                        "--theme=bogus",
                        "--out=${tmpDir.resolve("out-theme").toAbsolutePath()}",
                    ),
                )
            }
            assertEquals(1, renderThemeExit)
            assertTrue(renderThemeErr.contains("Unknown theme 'bogus'"), renderThemeErr)
            assertTrue(renderThemeErr.contains("dark") && renderThemeErr.contains("light"), renderThemeErr)

            // 3. Empty --beats guard returns 1 with clear error.
            val (emptyBeatsExit, emptyBeatsErr) = captureStderr {
                DeckAgentCli.runCli(
                    arrayOf(
                        "--render-slides",
                        "--deck=${deckFile.toAbsolutePath()}",
                        "--beats=99",
                        "--out=${tmpDir.resolve("out-beats").toAbsolutePath()}",
                    ),
                )
            }
            assertEquals(1, emptyBeatsExit)
            assertTrue(emptyBeatsErr.contains("no beats matched --beats=99"), emptyBeatsErr)

            // 4. Out-of-range --slides in --lint --json returns exit 1 with valid JSON error.
            val (badSlidesExit, badSlidesOut) = captureStdout {
                DeckAgentCli.runCli(arrayOf("--lint", "--deck=foundation", "--slides=999", "--json"))
            }
            assertEquals(1, badSlidesExit)
            val badSlidesObj = Json.parseToJsonElement(badSlidesOut).jsonObject
            assertFalse(badSlidesObj["passed"]?.jsonPrimitive?.boolean == true)
            assertEquals(
                "INVALID_SLIDES",
                badSlidesObj["issues"]!!.jsonArray.first().jsonObject["code"]?.jsonPrimitive?.content,
            )

            // 5. Case-insensitive .MP4 suffix stripping produces .spans.tsv without .MP4 in stem.
            val upperMp4 = tmpDir.resolve("out-case/Foo.MP4")
            val upperSpans = tmpDir.resolve("out-case/Foo.spans.tsv")
            val (upperExit, upperOut) = captureStdout {
                DeckAgentCli.runCli(
                    arrayOf(
                        "--export-video",
                        "--deck=${deckFile.toAbsolutePath()}",
                        "--slides=1",
                        "--fps=10",
                        "--size=320x180",
                        "--out=${upperMp4.toAbsolutePath()}",
                    ),
                )
            }
            assertEquals(0, upperExit, upperOut)
            assertTrue(Files.isRegularFile(upperMp4))
            assertTrue(Files.isRegularFile(upperSpans), "Expected Foo.spans.tsv alongside Foo.MP4")
            assertFalse(Files.exists(tmpDir.resolve("out-case/Foo.MP4.spans.tsv")))

            // 6. Default output paths sanitize doc.id so traversal ids cannot escape build/.
            val renderDir = DeckAgentCli.defaultRenderOutDir("../../../evil")
            assertTrue(renderDir.endsWith(java.nio.file.Path.of("build/ciacola-slides/evil")), "Got $renderDir")
            val (videoMp4, videoSpans) = DeckAgentCli.defaultVideoOutPaths("../../../evil", null)
            assertTrue(videoMp4.endsWith(java.nio.file.Path.of("build/ciacola-video/evil/evil.mp4")), "Got $videoMp4")
            assertTrue(videoSpans.endsWith(java.nio.file.Path.of("build/ciacola-video/evil/evil.spans.tsv")), "Got $videoSpans")
        } finally {
            tmpDir.toFile().deleteRecursively()
        }
    }

    private fun captureStderr(block: () -> Int): Pair<Int, String> {
        val original = System.err
        val buf = ByteArrayOutputStream()
        val tee = PrintStream(buf, true, StandardCharsets.UTF_8)
        System.setErr(tee)
        return try {
            val code = block()
            tee.flush()
            code to buf.toString(StandardCharsets.UTF_8)
        } finally {
            System.setErr(original)
        }
    }

    private fun captureStdout(block: () -> Int): Pair<Int, String> {
        val original = System.out
        val buf = ByteArrayOutputStream()
        val tee = PrintStream(buf, true, StandardCharsets.UTF_8)
        System.setOut(tee)
        return try {
            val code = block()
            tee.flush()
            code to buf.toString(StandardCharsets.UTF_8)
        } finally {
            System.setOut(original)
        }
    }
}
