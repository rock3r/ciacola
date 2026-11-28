package dev.sebastiano.ciacola.document

import dev.sebastiano.ciacola.core.fonts.DeckFontDeclaration
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Tests for canonical JSON serialization, non-destructive open/close, minimal diffs, and schema/lint validation:
 * - Canonical JSON byte-identical round-trip, descriptor key order, sorted maps,
 *   <=100-col inline primitive formatting, shortest decimal formatting without `.0`, `null`
 *   only inside `set`.
 * - Open-without-edit leaves bytes and mtime untouched (canonical, non-canonical, and
 *   in-memory migrated files).
 * - Single-property edits produce minimal diffs confined strictly to the edited node.
 * - Pre-parse duplicate-key errors with line/column, malformed JSON, unknown keys,
 *   range checks, loupe strict-descendant checks, code changing w/h & single-value-change rules,
 *   spring+durationMs lint, SVG motion path delta check, format version guards (> supported
 *   read-only without decoding, < supported pure JsonObject migration), and RFC 7386 merge patch.
 */
@OptIn(ExperimentalPathApi::class)
class CanonicalJsonAndValidationTest {

    private val testSpecimens = deckSpecimens {
        specimen("pulse-meter", "Pulse Meter") {
            float("intensity", default = 0.5f, range = 0f..1f)
            int("bars", default = 4, range = 1..16)
            boolean("glow", default = true)
            choice("mode", default = "bars", choices = listOf("bars", "wave", "ring"))
            track("sweep", beat = 1, durationMs = 450)
            loop("ambient", periodMs = 2400L)
            content {}
        }
    }

    private fun richFixtureDocument(): DeckDocument = DeckDocument(
        format = CURRENT_DECK_FORMAT_VERSION,
        id = "ciacola-canonical",
        title = "Canonical Complete Fixture",
        stage = StageSize(width = 1600, height = 900),
        defaultTheme = "obsidian",
        themes = mapOf(
            "zeta" to DeckThemeSpec(
                colors = mapOf("fg" to "#F5F7FA", "bg" to "#101218"),
                typography = mapOf("body" to TextStyleOverride(font = "Inter", size = 32.0, weight = 400)),
            ),
            "obsidian" to DeckThemeSpec(
                colors = mapOf("ink" to "#FFFFFF", "accent" to "#FF6B5E", "canvas" to "#0B0E14"),
                typography = mapOf(
                    "title" to TextStyleOverride(font = "Inter", size = 72.0, weight = 700, lineHeight = 84.0),
                    "code" to TextStyleOverride(font = "JetBrains Mono", size = 24.0, weight = 500),
                ),
                motion = ThemeMotionDefaults(durationMs = 420, curve = Curve.Preset("standard")),
            ),
        ),
        fonts = listOf(
            DeckFontDeclaration(family = "JetBrains Mono", src = "fonts/JetBrainsMono-Regular.ttf", weight = 400),
            DeckFontDeclaration(family = "Inter", src = "fonts/Inter-Regular.ttf", weight = 400),
        ),
        palettes = mapOf(
            "warm" to SlidePaletteSpec(canvas = "#1A120E", ink = "#FFF4E8", accent = "#FF8C42"),
            "cool" to SlidePaletteSpec(canvas = "#0D1520", ink = "#E8F4FF", accent = "#42B4FF"),
        ),
        loopPeriodsMs = mapOf(
            "pulse-meter/ambient" to 3000L,
            "alpha/pulse" to 1500L,
        ),
        slides = listOf(
            SlideSpec(
                id = "s0-cover",
                title = "Cover",
                palette = "warm",
                background = Fill.Gradient(
                    GradientSpec(
                        angle = 135.0,
                        stops = listOf(GradientStop(0.0, "#1A120E"), GradientStop(1.0, "#2D1E17")),
                    ),
                ),
                beats = listOf(
                    BeatSpec(notes = "Opening beat"),
                    BeatSpec(notes = "Second beat", autoAdvanceMs = 1200),
                ),
                nodes = listOf(
                    NodeSpec(
                        id = "headline",
                        frame = FrameSpec(x = 120.0, y = 100.0, w = 1200.0, h = 160.0),
                        text = TextPayload(
                            value = RichTextValue.Plain("Hello Ciacola World"),
                            style = "title",
                            color = "@ink",
                            surface = SurfaceSpec(fill = Fill.Solid("#141923"), cornerRadius = 8.0),
                            morph = TextMorphSpec(
                                by = MorphBy.Word,
                                stagger = Stagger(ms = 35, manual = mapOf("World#0" to 180, "Hello#0" to 0)),
                            ),
                        ),
                        builds = listOf(
                            Build.Change(
                                id = "rewrite",
                                beat = 1,
                                durationMs = 500,
                                curve = Curve.Bezier(0.2, 0.0, 0.2, 1.0),
                                set = buildJsonObject {
                                    putJsonObject("text") {
                                        put("value", "Hello Motion World")
                                        put("surface", JsonNull)
                                    }
                                    put("rotation", 2.5)
                                    put("opacity", 0.95)
                                },
                            ),
                        ),
                    ),
                    NodeSpec(
                        id = "rich-sub",
                        frame = FrameSpec(x = 120.0, y = 280.0, w = 900.0, h = 80.0),
                        text = TextPayload(
                            value = RichTextValue.Runs(
                                listOf(
                                    TextRun("Fast ", weight = 700, color = "@accent"),
                                    TextRun("declarative slides", italic = true),
                                ),
                            ),
                        ),
                    ),
                    NodeSpec(
                        id = "snippet",
                        frame = FrameSpec(x = 120.0, y = 390.0, w = 820.0, h = 280.0),
                        code = CodePayload(
                            value = "val x = 1",
                            language = "kotlin",
                            surface = SurfaceSpec(
                                fill = Fill.Solid("#141923"),
                                cornerRadius = 16.0,
                                padding = PaddingSpec(20.0),
                                stroke = StrokeSpec(color = "#2C3546", width = 1.5),
                            ),
                        ),
                        builds = listOf(
                            Build.Change(
                                id = "edit-code",
                                beat = 1,
                                durationMs = 40,
                                set = buildJsonObject {
                                    putJsonObject("code") { put("value", "val x = 42") }
                                },
                            ),
                        ),
                    ),
                    NodeSpec(
                        id = "badge-shape",
                        frame = FrameSpec(x = 1040.0, y = 390.0, w = 180.0, h = 180.0),
                        scale = ScaleValue.Uniform(1.0),
                        shape = ShapePayload(
                            geometry = ShapeGeometry.Rect,
                            fill = Fill.Solid("@accent"),
                            stroke = StrokeSpec(color = "#FFFFFF", width = 2.0),
                            cornerRadius = 24.0,
                        ),
                        builds = listOf(
                            Build.Enter(
                                id = "pop-in",
                                beat = 1,
                                effect = "pop",
                                curve = Curve.Spring(damping = 0.72, stiffness = 360.0),
                                args = mapOf("z" to JsonPrimitive(2), "a" to JsonPrimitive(1)),
                            ),
                            Build.Emphasis(
                                id = "jiggle-it",
                                beat = 1,
                                after = "badge-shape#pop-in",
                                effect = "jiggle",
                                durationMs = 300,
                            ),
                        ),
                    ),
                    NodeSpec(
                        id = "photo",
                        frame = FrameSpec(x = 1260.0, y = 390.0, w = 320.0, h = 200.0),
                        image = ImagePayload(src = "images/hero.png", fit = "cover"),
                    ),
                    NodeSpec(
                        id = "arrow",
                        connector = ConnectorPayload(
                            from = "snippet",
                            to = "badge-shape",
                            stroke = StrokeSpec(color = "@accent", width = 3.0),
                            heads = LineHeads(end = HeadStyle.Filled),
                        ),
                    ),
                    NodeSpec(
                        id = "meter",
                        frame = FrameSpec(x = 1040.0, y = 610.0, w = 420.0, h = 220.0),
                        specimen = SpecimenPayload(
                            ref = "pulse-meter",
                            params = mapOf(
                                "mode" to JsonPrimitive("wave"),
                                "intensity" to JsonPrimitive(0.75),
                                "bars" to JsonPrimitive(8),
                            ),
                            tracks = mapOf(
                                "sweep" to SpecimenTrackOverride(beat = 1, delayMs = 50, durationMs = 400),
                            ),
                        ),
                    ),
                    NodeSpec(
                        id = "container-stack",
                        frame = FrameSpec(x = 120.0, y = 700.0, w = 720.0, h = 260.0),
                        stack = ContainerPayload(
                            padding = PaddingSpec(left = 12.0, top = 8.0, right = 12.0, bottom = 8.0),
                            children = listOf(
                                NodeSpec(
                                    id = "inner-row",
                                    row = ContainerPayload(
                                        gap = 16.0,
                                        children = listOf(
                                            NodeSpec(
                                                id = "inner-col",
                                                column = ContainerPayload(
                                                    gap = 8.0,
                                                    children = listOf(
                                                        NodeSpec(
                                                            id = "leaf-note",
                                                            text = TextPayload(value = RichTextValue.Plain("Nested leaf")),
                                                        ),
                                                    ),
                                                ),
                                            ),
                                        ),
                                    ),
                                ),
                            ),
                        ),
                    ),
                    NodeSpec(
                        id = "zoom-lens",
                        frame = FrameSpec(x = 1520.0, y = 640.0, w = 260.0, h = 260.0),
                        loupe = LoupePayload(
                            target = "lens-target",
                            zoom = 2.0,
                            children = listOf(
                                NodeSpec(
                                    id = "lens-target",
                                    frame = FrameSpec(x = 10.0, y = 10.0, w = 120.0, h = 80.0),
                                    shape = ShapePayload(geometry = ShapeGeometry.Path("M 0 0 L 100 0 L 50 80 Z")),
                                ),
                            ),
                        ),
                    ),
                ),
                transition = TransitionSpec(
                    type = TransitionType.MagicMove,
                    durationMs = 650,
                    curve = Curve.Preset("emphasized"),
                    text = mapOf(
                        "headline" to TextMorphSpec(by = MorphBy.Word),
                        "alpha" to TextMorphSpec(by = MorphBy.Character),
                    ),
                ),
            ),
            SlideSpec(
                id = "s1-end",
                title = "Fin",
                palette = "cool",
                background = Fill.Image(ImageFillSpec(src = "images/bg.png", fit = "cover")),
                beats = listOf(BeatSpec(notes = "Closing")),
                nodes = listOf(
                    NodeSpec(
                        id = "headline",
                        frame = FrameSpec(x = 160.0, y = 200.0, w = 1200.0, h = 160.0),
                        text = TextPayload(value = RichTextValue.Plain("Thanks for Watching")),
                        builds = listOf(
                            Build.Exit(beat = 0, effect = "fade", durationMs = 250),
                        ),
                    ),
                ),
            ),
        ),
    )

    @Test
    fun canonicalJsonByteIdenticalRoundTripAndFormattingInvariants() {
        val doc = richFixtureDocument()
        val printed1 = CanonicalJson.print(doc)

        // Trailing LF newline, no CRLF
        assertTrue(printed1.endsWith("\n"), "Canonical JSON must end with trailing LF")
        assertFalse(printed1.contains("\r"), "Canonical JSON must use LF line endings only")

        // Round-trip through decode AND raw parse -> byte-identical
        val decoded = DeckDocumentCodec.decode(printed1, specimens = testSpecimens)
        val success = assertIs<DeckDecodeResult.Success>(decoded)
        val printedFromDecoded = CanonicalJson.print(success.document)
        val printedFromParsed = CanonicalJson.print(success.rawJson)
        assertEquals(printed1, printedFromDecoded, "print(decode(s).document) must be byte-identical")
        assertEquals(printed1, printedFromParsed, "print(parse(s)) must be byte-identical")

        // Top-level descriptor key order: format -> id -> title -> stage -> themes -> defaultTheme -> fonts -> palettes -> loopPeriodsMs -> slides
        val topLevelKeys = listOf(
            "\"format\":",
            "\"id\":",
            "\"title\":",
            "\"stage\":",
            "\"themes\":",
            "\"defaultTheme\":",
            "\"fonts\":",
            "\"palettes\":",
            "\"loopPeriodsMs\":",
            "\"slides\":",
        )
        val topIndices = topLevelKeys.map { printed1.indexOf(it) }
        assertTrue(topIndices.all { it >= 0 }, "All top-level keys must appear")
        assertEquals(topIndices.sorted(), topIndices, "Top-level keys must appear in SerialDescriptor order")

        // Sorted name-keyed maps
        assertTrue(printed1.indexOf("\"obsidian\":") < printed1.indexOf("\"zeta\":"), "themes map must be sorted")
        assertTrue(printed1.indexOf("\"cool\":") < printed1.indexOf("\"warm\":"), "palettes map must be sorted")
        assertTrue(
            printed1.indexOf("\"alpha/pulse\":") < printed1.indexOf("\"pulse-meter/ambient\":"),
            "loopPeriodsMs map must be sorted",
        )
        assertTrue(
            printed1.indexOf("\"Hello#0\":") < printed1.indexOf("\"World#0\":"),
            "stagger.manual map must be sorted",
        )
        assertTrue(
            printed1.indexOf("\"bars\":") < printed1.indexOf("\"intensity\":") &&
                printed1.indexOf("\"intensity\":") < printed1.indexOf("\"mode\":"),
            "specimen.params map must be sorted",
        )
        assertTrue(
            printed1.indexOf("\"a\": 1, \"z\": 2") >= 0,
            "build.args map must be sorted and printed inline when primitive-only <= 100 cols",
        )

        // Build.Change.set preserves `null` and prints keys in NodeSpec descriptor order (opacity -> rotation -> text)
        val setIdx = printed1.indexOf("\"set\":")
        assertTrue(setIdx >= 0)
        val setSlice = printed1.substring(setIdx, printed1.indexOf("}", printed1.indexOf("\"text\":", setIdx)) + 1)
        assertTrue(setSlice.contains("\"surface\": null"), "`null` must be preserved inside `set`")
        assertTrue(
            setSlice.indexOf("\"opacity\":") < setSlice.indexOf("\"rotation\":") &&
                setSlice.indexOf("\"rotation\":") < setSlice.indexOf("\"text\":"),
            "`set` keys must follow patched NodeSpec descriptor order",
        )

        // Outside `set`, no `null` values appear
        val withoutSet = printed1.replace(setSlice, "")
        assertFalse(withoutSet.contains(": null"), "`null` must not be printed outside `set`")

        // Primitive-only objects <= 100 columns print on one line, without trailing `.0`
        assertTrue(printed1.contains("\"stage\": { \"width\": 1600, \"height\": 900 }"))
        // Default 1920x1080 stage is omitted under encodeDefaults = false
        val defaultStageJson = CanonicalJson.print(doc.copy(stage = StageSize()))
        assertFalse(defaultStageJson.contains("\"stage\":"), "Default 1920x1080 stage must be omitted")
        assertTrue(printed1.contains("\"frame\": { \"x\": 120, \"y\": 100, \"w\": 1200, \"h\": 160 }"))
        assertFalse(printed1.contains("120.0"), "Whole-number doubles must not have trailing .0")
        assertTrue(printed1.contains("0.95"), "Fractional doubles keep exact shortest decimal")

        // Shortest decimal edge cases (including scientific notation without trailing .0)
        assertEquals("0", CanonicalJson.formatShortestDecimal(-0.0))
        assertEquals("0", CanonicalJson.formatShortestDecimal(0.0))
        assertEquals("42", CanonicalJson.formatShortestDecimal(42.0))
        assertEquals("0.1", CanonicalJson.formatShortestDecimal(0.1))
        assertEquals("0.3333333333333333", CanonicalJson.formatShortestDecimal(1.0 / 3.0))
        assertEquals("5e-4", CanonicalJson.formatShortestDecimal(5e-4))
        assertEquals("1e-5", CanonicalJson.formatShortestDecimal(1e-5))
        assertEquals("-5e-4", CanonicalJson.formatShortestDecimal(-5e-4))
        assertEquals("1.25e-4", CanonicalJson.formatShortestDecimal(1.25e-4))

        // Long primitive-only object (> 100 cols) wraps to multiple lines
        val longManual = (1..12).associate { "longTokenName$it#0" to it * 100 }
        val longDoc = DeckDocument(
            id = "wrap",
            slides = listOf(
                SlideSpec(
                    id = "s0",
                    beats = listOf(BeatSpec()),
                    nodes = listOf(
                        NodeSpec(
                            id = "t",
                            text = TextPayload(
                                value = RichTextValue.Plain("a"),
                                morph = TextMorphSpec(stagger = Stagger(manual = longManual)),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val longPrinted = CanonicalJson.print(longDoc)
        val manualLine = longPrinted.lines().first { it.contains("\"manual\":") }
        assertTrue(manualLine.trimEnd().endsWith("{"), "Primitive object exceeding 100 columns must expand multi-line")
    }

    @Test
    fun openAndCloseWithoutEditLeavesCanonicalNonCanonicalAndMigratedFilesUntouched() = runBlocking {
        val tempDir = Files.createTempDirectory("open-no-rewrite")
        val recoveryDir = tempDir.resolve("recovery")
        try {
            val doc = DeckDocument(
                id = "untouched-deck",
                title = "Untouched",
                slides = listOf(
                    SlideSpec(
                        id = "s0",
                        beats = listOf(BeatSpec()),
                        nodes = listOf(
                            NodeSpec(id = "n0", text = TextPayload(value = RichTextValue.Plain("Stay still"))),
                        ),
                    ),
                ),
            )
            // Case A: Canonical file
            val canonicalPath = tempDir.resolve("canonical.deck.json")
            val canonicalText = CanonicalJson.print(doc)
            Files.writeString(canonicalPath, canonicalText)
            val canonicalTime = FileTime.fromMillis(1_700_000_000_000L)
            Files.setLastModifiedTime(canonicalPath, canonicalTime)

            val storeA = DeckDocumentStore()
            val syncA = DeckFileSync(targetFile = canonicalPath, recoveryRootDir = recoveryDir, store = storeA)
            syncA.open()
            assertEquals(CloseFlushResult.Clean, syncA.flushOnClose())
            syncA.close()
            assertEquals(canonicalText, Files.readString(canonicalPath))
            assertEquals(canonicalTime, Files.getLastModifiedTime(canonicalPath))

            // Case B: Deliberately non-canonical formatting (shuffled keys, extra spaces, trailing .0)
            val messyPath = tempDir.resolve("messy.deck.json")
            val messyText = """{"slides":[{"nodes":[{"text":{"value":"Stay still"},"id":"n0"}],"beats":[{}],"id":"s0"}],"title":"Untouched","id":"untouched-deck","format":1}"""
            Files.writeString(messyPath, messyText)
            val messyTime = FileTime.fromMillis(1_700_000_123_000L)
            Files.setLastModifiedTime(messyPath, messyTime)

            val storeB = DeckDocumentStore()
            val syncB = DeckFileSync(targetFile = messyPath, recoveryRootDir = recoveryDir, store = storeB)
            syncB.open()
            assertIs<DeckLoadState.Loaded>(storeB.load)
            assertEquals(CloseFlushResult.Clean, syncB.flushOnClose())
            syncB.close()
            assertEquals(messyText, Files.readString(messyPath), "Non-canonical file must not be rewritten on clean open/close")
            assertEquals(messyTime, Files.getLastModifiedTime(messyPath))

            // Case C: Older format version migrated purely in memory -> disk untouched until edit
            val oldPath = tempDir.resolve("v0.deck.json")
            val oldText = """{"format":0,"id":"legacy","legacyFlag":true,"slides":[{"id":"s0","beats":[{}],"nodes":[]}]}"""
            Files.writeString(oldPath, oldText)
            val oldTime = FileTime.fromMillis(1_700_000_456_000L)
            Files.setLastModifiedTime(oldPath, oldTime)

            val migrations = DeckMigrations(
                supportedFormatVersion = 1,
                steps = mapOf(
                    0 to DeckMigrationStep { raw ->
                        JsonObject(
                            raw.filterKeys { it != "legacyFlag" } + ("format" to JsonPrimitive(1)),
                        )
                    },
                ),
            )
            val storeC = DeckDocumentStore()
            val syncC = DeckFileSync(
                targetFile = oldPath,
                recoveryRootDir = recoveryDir,
                store = storeC,
                migrations = migrations,
            )
            syncC.open()
            assertIs<DeckLoadState.Loaded>(storeC.load)
            assertEquals(1, storeC.document.format)
            assertEquals(CloseFlushResult.Clean, syncC.flushOnClose())
            syncC.close()
            assertEquals(oldText, Files.readString(oldPath), "Migrated older-format file must not rewrite disk without an edit")
            assertEquals(oldTime, Files.getLastModifiedTime(oldPath))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun singlePropertyEditProducesMinimalLocalizedDiff() {
        val base = richFixtureDocument()
        val beforeLines = CanonicalJson.print(base).lines()

        // Edit ONLY `badge-shape`'s `frame.x` from 1040 to 1088
        val edited = base.copy(
            slides = base.slides.mapIndexed { idx, slide ->
                if (idx != 0) slide else slide.copy(
                    nodes = slide.nodes.map { node ->
                        if (node.id != "badge-shape") node else node.copy(
                            frame = node.frame!!.copy(x = 1088.0),
                        )
                    },
                )
            },
        )
        val afterLines = CanonicalJson.print(edited).lines()

        assertEquals(beforeLines.size, afterLines.size, "Line count must stay identical for scalar edit")
        val changedIndices = beforeLines.indices.filter { beforeLines[it] != afterLines[it] }
        assertEquals(1, changedIndices.size, "Exactly one line should change when editing frame.x; changed=$changedIndices")
        assertTrue(beforeLines[changedIndices.single()].contains("\"x\": 1040"))
        assertTrue(afterLines[changedIndices.single()].contains("\"x\": 1088"))
    }

    @Test
    fun preParseDuplicateKeysAndMalformedJsonReportLineAndColumn() {
        val dupTopLevel = """
            {
              "format": 1,
              "id": "dup",
              "id": "dup-2",
              "slides": []
            }
        """.trimIndent()
        val dupResult = assertIs<DeckDecodeResult.Failure>(DeckDocumentCodec.decode(dupTopLevel))
        val dupErr = dupResult.errors.first { it.code == "DUPLICATE_JSON_KEY" }
        assertEquals(IssueSeverity.LoadError, dupErr.severity)
        assertEquals(4, dupErr.line)
        assertNotNull(dupErr.column)
        assertTrue(dupErr.column!! >= 3)

        val dupNested = """
            {
              "format": 1,
              "id": "ok",
              "slides": [
                {
                  "id": "s0",
                  "beats": [{}],
                  "nodes": [
                    {
                      "id": "n0",
                      "frame": { "x": 10, "x": 20 },
                      "text": { "value": "hi" }
                    }
                  ]
                }
              ]
            }
        """.trimIndent()
        val nestedRes = assertIs<DeckDecodeResult.Failure>(DeckDocumentCodec.decode(dupNested))
        val nestedErr = nestedRes.errors.first { it.code == "DUPLICATE_JSON_KEY" }
        assertEquals(11, nestedErr.line)
        assertNotNull(nestedErr.column)

        val malformed = "{\n  \"format\": 1,\n  \"id\": }\n"
        val badRes = assertIs<DeckDecodeResult.Failure>(DeckDocumentCodec.decode(malformed))
        val badErr = badRes.errors.first { it.code == "MALFORMED_JSON" }
        assertEquals(IssueSeverity.LoadError, badErr.severity)
        assertNotNull(badErr.line)
    }

    @Test
    fun formatVersionGuardNewerReadOnlyAndOlderMigrated() = runBlocking {
        // Newer format with unknown future keys MUST NOT enter strict decoder
        val futureJson = """
            {
              "format": 99,
              "id": "future-deck",
              "futureOnlyFeature": { "hologram": true },
              "slides": []
            }
        """.trimIndent()
        val decoded = DeckDocumentCodec.decode(futureJson)
        val newer = assertIs<DeckDecodeResult.ReadOnlyNewerFormat>(decoded)
        assertEquals(99, newer.foundVersion)
        assertEquals(CURRENT_DECK_FORMAT_VERSION, newer.supportedVersion)

        // DeckFileSync opens newer format read-only and never touches disk
        val tempDir = Files.createTempDirectory("newer-format-readonly")
        val recoveryDir = tempDir.resolve("recovery")
        try {
            val file = tempDir.resolve("future.deck.json")
            Files.writeString(file, futureJson)
            val store = DeckDocumentStore()
            val sync = DeckFileSync(targetFile = file, recoveryRootDir = recoveryDir, store = store)
            sync.open()
            assertIs<DeckLoadState.ReadOnlyNewerFormat>(store.load)
            assertIs<SyncState.ReadOnly>(store.sync)
            assertFalse(store.edit { it.copy(title = "Nope") })
            sync.close()
            assertEquals(futureJson, Files.readString(file))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun unknownKeysRangesPayloadsAndForbiddenSetKeys() {
        // Unknown key on top level
        val unknownTop = """{"format":1,"id":"u","bogus":123,"slides":[{"id":"s0","beats":[{}],"nodes":[]}]}"""
        assertIs<DeckDecodeResult.Failure>(DeckDocumentCodec.decode(unknownTop))

        // Unknown key inside Build.Change.set and forbidden keys (id, children, builds)
        val badSet = """
            {
              "format": 1,
              "id": "badset",
              "slides": [
                {
                  "id": "s0",
                  "beats": [{}, {}],
                  "nodes": [
                    {
                      "id": "n0",
                      "text": { "value": "hello" },
                      "builds": [
                        {
                          "kind": "change",
                          "beat": 1,
                          "set": {
                            "id": "hack",
                            "builds": [],
                            "mysteryKey": 1,
                            "code": { "value": "swap-payload" },
                            "text": { "unknownField": true }
                          }
                        }
                      ]
                    }
                  ]
                }
              ]
            }
        """.trimIndent()
        val setFail = assertIs<DeckDecodeResult.Failure>(DeckDocumentCodec.decode(badSet))
        val codes = setFail.errors.mapTo(hashSetOf()) { it.code }
        assertTrue("SET_FORBIDDEN_KEY" in codes, "Expected SET_FORBIDDEN_KEY in $codes")
        assertTrue("SET_UNKNOWN_KEY" in codes, "Expected SET_UNKNOWN_KEY in $codes")
        assertTrue("SET_PAYLOAD_SWAP" in codes, "Expected SET_PAYLOAD_SWAP in $codes")

        // 0 payloads & 2 payloads on a node
        val payloadDoc = DeckDocument(
            id = "payloads",
            slides = listOf(
                SlideSpec(
                    id = "s0",
                    beats = listOf(BeatSpec()),
                    nodes = listOf(
                        NodeSpec(id = "empty"),
                        NodeSpec(
                            id = "both",
                            text = TextPayload(value = RichTextValue.Plain("a")),
                            shape = ShapePayload(geometry = ShapeGeometry.Rect),
                        ),
                    ),
                ),
            ),
        )
        val payloadIssues = DeckDocumentCodec.validate(payloadDoc)
        assertTrue(
            payloadIssues.any { it.code == "INVALID_PAYLOAD_COUNT" && it.address == "s0/empty" && it.severity == IssueSeverity.LoadError },
            "Expected INVALID_PAYLOAD_COUNT for empty node, got $payloadIssues",
        )
        assertTrue(
            payloadIssues.any { it.code == "INVALID_PAYLOAD_COUNT" && it.address == "s0/both" && it.severity == IssueSeverity.LoadError },
            "Expected INVALID_PAYLOAD_COUNT for dual-payload node, got $payloadIssues",
        )

        // Out-of-range opacity, scale, negative delayMs, bad bezier x, bad spring damping
        val rangeDoc = DeckDocument(
            id = "ranges",
            stage = StageSize(width = 0, height = -10),
            loopPeriodsMs = mapOf("bad/loop" to 0L),
            slides = listOf(
                SlideSpec(
                    id = "s0",
                    beats = listOf(BeatSpec(autoAdvanceMs = -10, stagger = Stagger(ms = -5))),
                    nodes = listOf(
                        NodeSpec(
                            id = "n0",
                            opacity = 1.5,
                            scale = ScaleValue.NonUniform(-1.0, 1.0), // Mirror flip is valid!
                            text = TextPayload(value = RichTextValue.Plain("hi")),
                            builds = listOf(
                                Build.Enter(
                                    beat = 0,
                                    effect = "fade",
                                    delayMs = -5,
                                    durationMs = -1,
                                    curve = Curve.Bezier(x1 = 1.5, y1 = 0.0, x2 = -0.1, y2 = 1.0),
                                ),
                                Build.Emphasis(
                                    beat = 0,
                                    effect = "pulse",
                                    durationMs = 300,
                                    curve = Curve.Spring(damping = 0.01, stiffness = 5.0),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val rangeIssues = DeckDocumentCodec.validate(rangeDoc)
        val rangeCodes = rangeIssues.mapTo(hashSetOf()) { it.code }
        assertTrue("INVALID_STAGE_SIZE" in rangeCodes)
        assertTrue("INVALID_LOOP_PERIOD" in rangeCodes)
        assertTrue("OPACITY_OUT_OF_RANGE" in rangeCodes)
        assertTrue("NEGATIVE_AUTO_ADVANCE" in rangeCodes)
        assertTrue("NEGATIVE_STAGGER_MS" in rangeCodes)
        assertTrue("NEGATIVE_DELAY_MS" in rangeCodes)
        assertTrue("NEGATIVE_DURATION_MS" in rangeCodes)
        assertTrue("BEZIER_X_OUT_OF_RANGE" in rangeCodes)
        assertTrue("SPRING_DAMPING_OUT_OF_RANGE" in rangeCodes)
        assertTrue("SPRING_STIFFNESS_OUT_OF_RANGE" in rangeCodes)
        // durationMs with spring must be LintError
        val springDur = rangeIssues.firstOrNull { it.code == "DURATION_WITH_SPRING" }
        assertNotNull(springDur)
        assertEquals(IssueSeverity.LintError, springDur.severity)
    }

    @Test
    fun loupeStrictDescendantCodeChangeRulesDanglingRefsAndMotionPathLint() {
        val doc = DeckDocument(
            id = "semantics",
            slides = listOf(
                SlideSpec(
                    id = "s0",
                    beats = listOf(BeatSpec(), BeatSpec()),
                    nodes = listOf(
                        // Changing code node WITHOUT explicit w/h + TWO value changes on beat 1
                        NodeSpec(
                            id = "bad-code",
                            frame = FrameSpec(x = 0.0, y = 0.0),
                            code = CodePayload(value = "a"),
                            builds = listOf(
                                Build.Change(id = "c1", beat = 1, set = buildJsonObject { putJsonObject("code") { put("value", "b") } }),
                                Build.Change(id = "c2", beat = 1, set = buildJsonObject { putJsonObject("code") { put("value", "c") } }),
                            ),
                        ),
                        // Static code node WITHOUT w/h is allowed
                        NodeSpec(
                            id = "static-code",
                            code = CodePayload(value = "static"),
                        ),
                        // Self-targeting loupe & sibling-targeting loupe
                        NodeSpec(
                            id = "sibling",
                            shape = ShapePayload(geometry = ShapeGeometry.Ellipse),
                        ),
                        NodeSpec(
                            id = "bad-loupe-self",
                            loupe = LoupePayload(target = "bad-loupe-self"),
                        ),
                        NodeSpec(
                            id = "bad-loupe-sibling",
                            loupe = LoupePayload(target = "sibling"),
                        ),
                        // Dangling connector & dangling after + text leftToRight + SVG path mismatch + beat-0 enter on slide 0
                        NodeSpec(
                            id = "conn",
                            connector = ConnectorPayload(from = "missing-a", to = "missing-b"),
                        ),
                        NodeSpec(
                            id = "mover",
                            frame = FrameSpec(x = 100.0, y = 200.0, w = 200.0, h = 60.0),
                            text = TextPayload(
                                value = RichTextValue.Plain("move me"),
                                morph = TextMorphSpec(stagger = Stagger(order = StaggerOrder.LeftToRight)),
                            ),
                            builds = listOf(
                                Build.Enter(id = "e0", beat = 0, effect = "fade", after = "ghost-node"),
                                Build.Change(
                                    id = "m1",
                                    beat = 1,
                                    path = "M 0 0 L 999 999",
                                    set = buildJsonObject { putJsonObject("frame") { put("x", 200); put("y", 250) } },
                                ),
                            ),
                        ),
                    ),
                    // Transition on last slide -> LintWarning
                    transition = TransitionSpec(type = TransitionType.Dissolve, durationMs = 300),
                ),
            ),
        )
        val issues = DeckDocumentCodec.validate(doc)
        val byCode = issues.groupBy { it.code }

        assertEquals(IssueSeverity.LoadError, byCode["CODE_CHANGING_REQUIRES_EXPLICIT_SIZE"]?.first()?.severity)
        assertEquals(IssueSeverity.LoadError, byCode["CODE_MULTIPLE_VALUE_CHANGES_ON_BEAT"]?.first()?.severity)
        assertTrue(issues.none { it.address.contains("static-code") }, "Static code without w/h must produce no error")
        assertTrue("LOUPE_TARGET_SELF" in byCode)
        assertTrue("LOUPE_TARGET_NOT_DESCENDANT" in byCode)
        assertTrue("DANGLING_CONNECTOR_FROM" in byCode)
        assertTrue("DANGLING_CONNECTOR_TO" in byCode)
        assertTrue("DANGLING_AFTER_TARGET" in byCode)
        assertEquals(IssueSeverity.LintError, byCode["TEXT_STAGGER_LEFT_TO_RIGHT"]?.first()?.severity)
        assertEquals(IssueSeverity.LintError, byCode["MOTION_PATH_ENDPOINT_MISMATCH"]?.first()?.severity)
        assertEquals(IssueSeverity.LintWarning, byCode["FIRST_SLIDE_BEAT0_ENTER"]?.first()?.severity)
        assertEquals(IssueSeverity.LintWarning, byCode["TRANSITION_ON_LAST_SLIDE"]?.first()?.severity)

        // Verify matching SVG path delta produces NO MOTION_PATH_ENDPOINT_MISMATCH
        val goodPathDoc = DeckDocument(
            id = "goodpath",
            slides = listOf(
                SlideSpec(
                    id = "s0",
                    beats = listOf(BeatSpec(), BeatSpec()),
                    nodes = listOf(
                        NodeSpec(
                            id = "mover",
                            frame = FrameSpec(x = 100.0, y = 200.0, w = 200.0, h = 60.0),
                            shape = ShapePayload(geometry = ShapeGeometry.Rect),
                            builds = listOf(
                                Build.Change(
                                    beat = 1,
                                    path = "M 0 0 Q 50 -40 100 50",
                                    set = buildJsonObject { putJsonObject("frame") { put("x", 200); put("y", 250) } },
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val goodPathIssues = DeckDocumentCodec.validate(goodPathDoc)
        assertTrue(goodPathIssues.none { it.code == "MOTION_PATH_ENDPOINT_MISMATCH" || it.code == "INVALID_MOTION_PATH" })
    }

    @Test
    fun rfc7386MergePatchSemanticsAndBeatEdits() {
        val baseNode = NodeSpec(
            id = "card",
            frame = FrameSpec(x = 100.0, y = 200.0, w = 400.0, h = 200.0),
            opacity = 1.0,
            text = TextPayload(
                value = RichTextValue.Plain("Beat 0"),
                color = "#FFFFFF",
                surface = SurfaceSpec(fill = Fill.Solid("#111111"), cornerRadius = 12.0),
            ),
        )

        // Applying an edit at beat 1 adds a Build.Change; beat 0 remains unchanged
        val editedAt1 = JsonMergePatch.applyEditAtBeat(
            node = baseNode,
            beat = 1,
            patch = buildJsonObject {
                put("opacity", 0.5)
                putJsonObject("text") { put("surface", JsonNull) } // removes entire enclosing object
            },
        )
        assertEquals(1.0, JsonMergePatch.resolveNodeAtBeat(editedAt1, 0).opacity)
        assertNotNull(JsonMergePatch.resolveNodeAtBeat(editedAt1, 0).text?.surface)

        val resolvedBeat1 = JsonMergePatch.resolveNodeAtBeat(editedAt1, 1)
        assertEquals(0.5, resolvedBeat1.opacity)
        assertNull(resolvedBeat1.text?.surface, "null in set removes enclosing object per RFC 7386")

        // resetAtBeat at beat 2 writes `null` to remove a field set on beat 0 / 1
        val resetLeaf = JsonMergePatch.resetAtBeat(editedAt1, beat = 2, keyPath = listOf("text", "color"))
        val beat2Node = JsonMergePatch.resolveNodeAtBeat(resetLeaf, 2)
        assertNull(beat2Node.text?.color, "resetAtBeat at beat > 0 must remove inherited leaf via null")
        val beat2Build = resetLeaf.builds.last() as Build.Change
        val textPatch = assertIs<JsonObject>(beat2Build.set["text"])
        assertEquals(JsonNull, textPatch["color"], "resetAtBeat at beat > 0 must store explicit JsonNull in set")

        // resetAtBeat at beat 0 removes key from base node cleanly
        val resetAt0 = JsonMergePatch.resetAtBeat(baseNode, beat = 0, keyPath = listOf("text", "color"))
        assertNull(resetAt0.text?.color)
        assertTrue(resetAt0.builds.isEmpty())

        // applyToAllBeats updates base and strips overridden keys from later change builds
        val uniform = JsonMergePatch.applyToAllBeats(
            node = editedAt1,
            patch = buildJsonObject { put("opacity", 0.8) },
        )
        assertEquals(0.8, JsonMergePatch.resolveNodeAtBeat(uniform, 0).opacity)
        assertEquals(0.8, JsonMergePatch.resolveNodeAtBeat(uniform, 1).opacity)
        val remainingChange = uniform.builds.filterIsInstance<Build.Change>().single()
        assertFalse("opacity" in remainingChange.set, "applyToAllBeats must strip patched key from beat > 0 builds")
        assertTrue("text" in remainingChange.set, "unrelated keys in beat > 0 build must survive")

        // Verify null removal vs absent member difference
        val target = buildJsonObject {
            put("a", 1)
            put("b", 2)
        }
        val mergedRemove = JsonMergePatch.apply(target, buildJsonObject { put("a", JsonNull) }) as JsonObject
        val mergedAbsent = JsonMergePatch.apply(target, buildJsonObject { put("b", 3) }) as JsonObject
        assertFalse("a" in mergedRemove)
        assertEquals(JsonPrimitive(2), mergedRemove["b"])
        assertEquals(JsonPrimitive(1), mergedAbsent["a"])
        assertEquals(JsonPrimitive(3), mergedAbsent["b"])
        assertNotEquals(mergedRemove, mergedAbsent)
    }

    @OptIn(ExperimentalSerializationApi::class)
    @Test
    fun goldenDescriptorKeyOrderAndCanonicalFixtureOutput() {
        val descriptors: List<Pair<String, SerialDescriptor>> = listOf(
            "DeckDocument" to DeckDocument.serializer().descriptor,
            "StageSize" to StageSize.serializer().descriptor,
            "DeckThemeSpec" to DeckThemeSpec.serializer().descriptor,
            "CodeSchemeSpec" to CodeSchemeSpec.serializer().descriptor,
            "CodeTokenStyleSpec" to CodeTokenStyleSpec.serializer().descriptor,
            "CodeSchemeSourceSpec" to CodeSchemeSourceSpec.serializer().descriptor,
            "ThemeMotionDefaults" to ThemeMotionDefaults.serializer().descriptor,
            "TextStyleOverride" to TextStyleOverride.serializer().descriptor,
            "SlidePaletteSpec" to SlidePaletteSpec.serializer().descriptor,
            "SlideSpec" to SlideSpec.serializer().descriptor,
            "BeatSpec" to BeatSpec.serializer().descriptor,
            "FrameSpec" to FrameSpec.serializer().descriptor,
            "Vec2" to Vec2.serializer().descriptor,
            "GradientStop" to GradientStop.serializer().descriptor,
            "GradientSpec" to GradientSpec.serializer().descriptor,
            "ImageFillSpec" to ImageFillSpec.serializer().descriptor,
            "StrokeSpec" to StrokeSpec.serializer().descriptor,
            "SurfaceSpec" to SurfaceSpec.serializer().descriptor,
            "TextRun" to TextRun.serializer().descriptor,
            "TextMorphSpec" to TextMorphSpec.serializer().descriptor,
            "TextPayload" to TextPayload.serializer().descriptor,
            "CodePayload" to CodePayload.serializer().descriptor,
            "LineHeads" to LineHeads.serializer().descriptor,
            "ShapePayload" to ShapePayload.serializer().descriptor,
            "ImagePayload" to ImagePayload.serializer().descriptor,
            "ConnectorPayload" to ConnectorPayload.serializer().descriptor,
            "SpecimenTrackOverride" to SpecimenTrackOverride.serializer().descriptor,
            "SpecimenPayload" to SpecimenPayload.serializer().descriptor,
            "ContainerPayload" to ContainerPayload.serializer().descriptor,
            "LoupePayload" to LoupePayload.serializer().descriptor,
            "NodeSpec" to NodeSpec.serializer().descriptor,
            "Timing" to Timing.serializer().descriptor,
            "Phases" to Phases.serializer().descriptor,
            "Stagger" to Stagger.serializer().descriptor,
            "SpringParams" to SpringParams.serializer().descriptor,
            "MotionChannelSpec" to MotionChannelSpec.serializer().descriptor,
            "MotionKeyframeSpec" to MotionKeyframeSpec.serializer().descriptor,
            "MotionWaveSpec" to MotionWaveSpec.serializer().descriptor,
            "Build.Enter" to Build.Enter.serializer().descriptor,
            "Build.Exit" to Build.Exit.serializer().descriptor,
            "Build.Emphasis" to Build.Emphasis.serializer().descriptor,
            "Build.Change" to Build.Change.serializer().descriptor,
            "TransitionSpec" to TransitionSpec.serializer().descriptor,
        )
        val actualOrder = buildString {
            for ((name, desc) in descriptors) {
                val keys = (0 until desc.elementsCount).joinToString(", ") { desc.getElementName(it) }
                append(name).append(": ").append(keys).append('\n')
            }
        }
        val goldenOrder = checkNotNull(
            this::class.java.classLoader.getResourceAsStream("golden/descriptor-key-order.txt"),
            { "Missing golden/descriptor-key-order.txt resource" },
        ).bufferedReader().use { it.readText() }
        assertEquals(goldenOrder, actualOrder, "SerialDescriptor key order drifted from golden fixture")

        val actualCanonical = CanonicalJson.print(richFixtureDocument())
        val goldenCanonical = checkNotNull(
            this::class.java.classLoader.getResourceAsStream("golden/canonical-document-fixture.deck.json"),
            { "Missing golden/canonical-document-fixture.deck.json resource" },
        ).bufferedReader().use { it.readText() }
        assertEquals(goldenCanonical, actualCanonical, "CanonicalJson output drifted from golden fixture")
    }

    @Test
    fun unknownKeysAndDecodeErrorsReportExactSemanticAddresses() {
        val multiUnknown = """
            {
              "format": 1,
              "id": "u",
              "rootBogus": 1,
              "slides": [
                {
                  "id": "s0",
                  "slideBogus": true,
                  "beats": [{ "beatBogus": 1 }],
                  "nodes": [
                    {
                      "id": "n0",
                      "nodeBogus": "x",
                      "text": { "value": "hi", "textBogus": 2 },
                      "builds": [
                        {
                          "kind": "enter",
                          "id": "b1",
                          "beat": 0,
                          "effect": "fade",
                          "buildBogus": 3,
                          "curve": { "spring": { "damping": 0.7, "stiffness": 300, "extraSpring": 1 } }
                        }
                      ]
                    },
                    {
                      "id": "box",
                      "stack": {
                        "children": [
                          {
                            "id": "leaf",
                            "childBogus": 9,
                            "text": { "value": "nested" }
                          }
                        ]
                      }
                    }
                  ]
                }
              ]
            }
        """.trimIndent()

        val fail = assertIs<DeckDecodeResult.Failure>(DeckDocumentCodec.decode(multiUnknown))
        assertTrue(fail.errors.all { it.severity == IssueSeverity.LoadError && it.code == "UNKNOWN_KEY" })
        val addresses = fail.errors.mapTo(hashSetOf()) { it.address }
        assertEquals(
            setOf(
                "<root>/rootBogus",
                "s0/slideBogus",
                "s0@beat0/beatBogus",
                "s0/n0/nodeBogus",
                "s0/n0/text/textBogus",
                "s0/n0#b1/buildBogus",
                "s0/n0#b1/curve/spring/extraSpring",
                "s0/leaf/childBogus",
            ),
            addresses,
            "Every unknown key must carry its exact semantic slide/node/build address",
        )

        // Malformed custom serializer payload (IllegalArgumentException without `at path:`)
        val badBezier = """
            {
              "format": 1,
              "id": "bad-bez",
              "slides": [
                {
                  "id": "s0",
                  "beats": [{}],
                  "nodes": [
                    {
                      "id": "n0",
                      "text": { "value": "hi" },
                      "builds": [
                        { "kind": "enter", "id": "b1", "beat": 0, "effect": "fade", "curve": { "bezier": 5 } }
                      ]
                    }
                  ]
                }
              ]
            }
        """.trimIndent()
        val bezFail = assertIs<DeckDecodeResult.Failure>(DeckDocumentCodec.decode(badBezier))
        assertEquals("SCHEMA_DECODE_ERROR", bezFail.errors.single().code)
        assertEquals("s0/n0#b1/curve", bezFail.errors.single().address)

        // Wrong scalar type inside node -> resolved semantic address from kotlinx.serialization path
        val badType = """
            {
              "format": 1,
              "id": "bad-type",
              "slides": [
                {
                  "id": "s0",
                  "beats": [{}],
                  "nodes": [
                    {
                      "id": "n0",
                      "opacity": "not-a-double",
                      "text": { "value": "hi" }
                    }
                  ]
                }
              ]
            }
        """.trimIndent()
        val typeFail = assertIs<DeckDecodeResult.Failure>(DeckDocumentCodec.decode(badType))
        assertEquals("SCHEMA_DECODE_ERROR", typeFail.errors.single().code)
        assertEquals("s0/n0/opacity", typeFail.errors.single().address)
    }

    @Test
    fun nestedAllBuildsAndSameBeatEditsPreserveSiblingNullResets() {
        val baseNode = NodeSpec(
            id = "box",
            frame = FrameSpec(x = 100.0, y = 50.0, w = 200.0, h = 80.0),
            text = TextPayload(
                value = RichTextValue.Plain("Hello"),
                surface = SurfaceSpec(
                    fill = Fill.Solid("#111111"),
                    cornerRadius = 8.0,
                    padding = PaddingSpec(left = 12.0, top = 8.0),
                ),
            ),
            builds = listOf(
                Build.Change(
                    beat = 1,
                    set = buildJsonObject {
                        put("frame", JsonNull)
                        put("text", buildJsonObject { put("surface", JsonNull) })
                    },
                ),
            ),
        )

        // 1. All-builds edit of frame.w only: beat 0 gets w=300; beat 1 gets w=300 while keeping x/y/h reset to null.
        val allWidth = JsonMergePatch.applyToAllBeats(
            node = baseNode,
            patch = JsonMergePatch.nestedPatch(listOf("frame", "w"), JsonPrimitive(300.0)),
        )
        assertEquals(FrameSpec(x = 100.0, y = 50.0, w = 300.0, h = 80.0), JsonMergePatch.resolveNodeAtBeat(allWidth, 0).frame)
        assertEquals(FrameSpec(x = null, y = null, w = 300.0, h = null), JsonMergePatch.resolveNodeAtBeat(allWidth, 1).frame)

        // 2. All-builds edit of nested text.surface.cornerRadius: beat 1 keeps fill and padding reset to null.
        val allRadius = JsonMergePatch.applyToAllBeats(
            node = baseNode,
            patch = JsonMergePatch.nestedPatch(listOf("text", "surface", "cornerRadius"), JsonPrimitive(16.0)),
        )
        val beat1Surface = JsonMergePatch.resolveNodeAtBeat(allRadius, 1).text?.surface
        assertEquals(16.0, beat1Surface?.cornerRadius)
        assertNull(beat1Surface?.fill, "Untouched sibling fill must remain reset on beat 1")
        assertNull(beat1Surface?.padding, "Untouched sibling padding must remain reset on beat 1")

        // 3. Same-beat edit of frame.w onto existing `"frame": null`: beat 1 keeps x/y/h reset instead of resurrecting base.
        val beat1Width = JsonMergePatch.applyEditAtBeat(
            node = baseNode,
            beat = 1,
            patch = JsonMergePatch.nestedPatch(listOf("frame", "w"), JsonPrimitive(320.0)),
        )
        assertEquals(FrameSpec(x = 100.0, y = 50.0, w = 200.0, h = 80.0), JsonMergePatch.resolveNodeAtBeat(beat1Width, 0).frame)
        assertEquals(FrameSpec(x = null, y = null, w = 320.0, h = null), JsonMergePatch.resolveNodeAtBeat(beat1Width, 1).frame)

        // 4. All-builds nested null reset (`frame.w = null`) keeps existing `"frame": null` whole-object reset intact.
        val allResetW = JsonMergePatch.applyToAllBeats(
            node = baseNode,
            patch = JsonMergePatch.nestedPatch(listOf("frame", "w"), JsonNull),
        )
        assertEquals(FrameSpec(x = 100.0, y = 50.0, w = null, h = 80.0), JsonMergePatch.resolveNodeAtBeat(allResetW, 0).frame)
        assertNull(JsonMergePatch.resolveNodeAtBeat(allResetW, 1).frame)

        // 5. Earlier beat adds frame.h before a beat-2 whole-frame reset; all-builds frame.w preserves reset of h too.
        val multiBeat = NodeSpec(
            id = "multi",
            frame = FrameSpec(x = 100.0, y = 50.0),
            text = TextPayload(value = RichTextValue.Plain("Multi")),
            builds = listOf(
                Build.Change(beat = 1, set = JsonMergePatch.nestedPatch(listOf("frame", "h"), JsonPrimitive(80.0))),
                Build.Change(beat = 2, set = buildJsonObject { put("frame", JsonNull) }),
            ),
        )
        val multiAllW = JsonMergePatch.applyToAllBeats(
            node = multiBeat,
            patch = JsonMergePatch.nestedPatch(listOf("frame", "w"), JsonPrimitive(300.0)),
        )
        assertEquals(FrameSpec(x = 100.0, y = 50.0, w = 300.0, h = 80.0), JsonMergePatch.resolveNodeAtBeat(multiAllW, 1).frame)
        assertEquals(FrameSpec(x = null, y = null, w = 300.0, h = null), JsonMergePatch.resolveNodeAtBeat(multiAllW, 2).frame)

        // 6. Required-field nested object (`StrokeSpec.color` required): partial all-builds stroke.width edit
        // keeps beat-1 `"stroke": null` instead of producing an undecodable partial StrokeSpec.
        val shapeWithStrokeReset = NodeSpec(
            id = "rect",
            frame = FrameSpec(x = 0.0, y = 0.0, w = 100.0, h = 100.0),
            shape = ShapePayload(
                geometry = ShapeGeometry.Rect,
                stroke = StrokeSpec(color = "#FFFFFF", width = 2.0),
            ),
            builds = listOf(
                Build.Change(beat = 1, set = JsonMergePatch.nestedPatch(listOf("shape", "stroke"), JsonNull)),
            ),
        )
        val allStrokeWidth = JsonMergePatch.applyToAllBeats(
            node = shapeWithStrokeReset,
            patch = JsonMergePatch.nestedPatch(listOf("shape", "stroke", "width"), JsonPrimitive(4.0)),
        )
        assertEquals(4.0, JsonMergePatch.resolveNodeAtBeat(allStrokeWidth, 0).shape?.stroke?.width)
        assertNull(JsonMergePatch.resolveNodeAtBeat(allStrokeWidth, 1).shape?.stroke)
    }

    @Test
    fun codeSchemeAndCustomCodeSchemeRoundTripAndValidateCleanly() {
        val githubDark = dev.sebastiano.ciacola.syntax.theme.SyntaxColorSchemes.GitHubDark
        val islandsDark = dev.sebastiano.ciacola.syntax.theme.SyntaxColorSchemes.IntelliJIslandsDark
        val doc = DeckDocument(
            id = "code-scheme-roundtrip",
            title = "Code Scheme Test",
            defaultTheme = "dark",
            themes = mapOf(
                "dark" to DeckThemeSpec(
                    preset = "stageDark",
                    codeScheme = "github-dark",
                ),
                "imported" to DeckThemeSpec(
                    preset = "stageDark",
                    codeScheme = islandsDark.id,
                    customCodeScheme = islandsDark.toCodeSchemeSpec(),
                ),
                "legacy" to DeckThemeSpec(preset = "keynoteLight"),
            ),
            slides = listOf(SlideSpec(id = "s1")),
        )

        val printed = CanonicalJson.print(doc)
        val decoded = assertIs<DeckDecodeResult.Success>(DeckDocumentCodec.decode(printed))
        assertTrue(decoded.lintIssues.isEmpty(), "Expected no lint issues, got ${decoded.lintIssues}")
        assertEquals("github-dark", decoded.document.themes["dark"]?.codeScheme)
        assertNull(decoded.document.themes["dark"]?.customCodeScheme)

        val roundTrippedCustom = assertNotNull(decoded.document.themes["imported"]?.customCodeScheme)
        assertEquals(islandsDark, roundTrippedCustom.toSyntaxColorScheme())
        assertEquals(githubDark, resolveDeckCodeScheme(decoded.document.themes["dark"], isLight = false))
        assertEquals(
            dev.sebastiano.ciacola.syntax.theme.SyntaxColorSchemes.IntelliJLight,
            resolveDeckCodeScheme(decoded.document.themes["legacy"], isLight = true),
        )

        // Unknown bundled codeScheme id -> lint error
        val unknownSchemeDoc = doc.copy(
            themes = mapOf("dark" to DeckThemeSpec(preset = "stageDark", codeScheme = "non-existent-scheme")),
        )
        val unknownIssues = DeckDocumentCodec.validate(unknownSchemeDoc)
        assertTrue(unknownIssues.any { it.code == "UNKNOWN_CODE_SCHEME" && it.severity == IssueSeverity.LintError })

        // Invalid customCodeScheme -> lint error
        val badCustomDoc = doc.copy(
            themes = mapOf(
                "dark" to DeckThemeSpec(
                    preset = "stageDark",
                    customCodeScheme = CodeSchemeSpec(
                        id = "",
                        name = "Broken",
                        appearance = "neon",
                        background = "not-a-color",
                        foreground = "#FFFFFF",
                        tokens = mapOf("keyword" to CodeTokenStyleSpec(fg = "#ZZZ")),
                    ),
                ),
            ),
        )
        val customIssues = DeckDocumentCodec.validate(badCustomDoc)
        assertTrue(customIssues.any { it.code == "INVALID_CUSTOM_CODE_SCHEME" && it.severity == IssueSeverity.LintError })
        assertNull(badCustomDoc.themes["dark"]?.customCodeScheme?.toSyntaxColorSchemeOrNull())
    }
}
