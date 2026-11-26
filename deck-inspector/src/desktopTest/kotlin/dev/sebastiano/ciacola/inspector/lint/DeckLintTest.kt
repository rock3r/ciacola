package dev.sebastiano.ciacola.inspector.lint

import dev.sebastiano.ciacola.document.BeatSpec
import dev.sebastiano.ciacola.document.Build
import dev.sebastiano.ciacola.document.DeckDocument
import dev.sebastiano.ciacola.document.DeckThemeSpec
import dev.sebastiano.ciacola.document.Fill
import dev.sebastiano.ciacola.document.FrameSpec
import dev.sebastiano.ciacola.document.NodeSpec
import dev.sebastiano.ciacola.document.RichTextValue
import dev.sebastiano.ciacola.document.SlideSpec
import dev.sebastiano.ciacola.document.TextPayload
import dev.sebastiano.ciacola.document.TextRun
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class DeckLintTest {

    @Test
    fun documentLintEngineDetectsContrastOverflowOutOfBoundsTinyTextAndOverlap() = runBlocking {
        val doc = DeckDocument(
            id = "synthetic-defects",
            title = "Synthetic Defects",
            defaultTheme = "light",
            themes = mapOf(
                "light" to DeckThemeSpec(
                    colors = mapOf(
                        "background" to "#EBEBEB",
                        "foreground" to "#242124",
                    ),
                ),
            ),
            slides = listOf(
                // Slide 0: Low contrast (#9A9FA8 on #EBEBEB)
                SlideSpec(
                    id = "s0-contrast",
                    background = Fill.Solid("#EBEBEB"),
                    nodes = listOf(
                        NodeSpec(
                            id = "s0:washed",
                            frame = FrameSpec(x = 80.0, y = 100.0, w = 600.0, h = 80.0),
                            text = TextPayload(
                                value = RichTextValue.Plain("Washed out copy"),
                                color = "#9A9FA8",
                                size = 18.0,
                            ),
                        ),
                    ),
                ),
                // Slide 1: Out-of-stage bleed + tiny text
                SlideSpec(
                    id = "s1-bounds-tiny",
                    background = Fill.Solid("#EBEBEB"),
                    nodes = listOf(
                        NodeSpec(
                            id = "s1:offstage",
                            frame = FrameSpec(x = 1800.0, y = 100.0, w = 320.0, h = 80.0),
                            text = TextPayload(
                                value = RichTextValue.Plain("Offstage headline"),
                                color = "#242124",
                                size = 28.0,
                            ),
                        ),
                        NodeSpec(
                            id = "s1:tiny",
                            frame = FrameSpec(x = 80.0, y = 300.0, w = 400.0, h = 40.0),
                            text = TextPayload(
                                value = RichTextValue.Plain("Tiny unreadable footnote"),
                                color = "#242124",
                                size = 10.0,
                            ),
                        ),
                    ),
                ),
                // Slide 2: Overlapping text nodes
                SlideSpec(
                    id = "s2-overlap",
                    background = Fill.Solid("#EBEBEB"),
                    nodes = listOf(
                        NodeSpec(
                            id = "s2:first",
                            frame = FrameSpec(x = 100.0, y = 120.0, w = 640.0, h = 70.0),
                            text = TextPayload(
                                value = RichTextValue.Plain("First overlapping headline"),
                                color = "#242124",
                                size = 36.0,
                            ),
                        ),
                        NodeSpec(
                            id = "s2:second",
                            frame = FrameSpec(x = 100.0, y = 128.0, w = 640.0, h = 70.0),
                            text = TextPayload(
                                value = RichTextValue.Plain("Second overlapping headline"),
                                color = "#242124",
                                size = 36.0,
                            ),
                        ),
                    ),
                ),
            ),
        )

        DocumentDeckLintEngine().use { engine ->
            val report = engine.lintFullDeck(doc)
            val codes = report.issues.map { it.code }.toSet()
            assertTrue(report.issues.any { it.slideIndex == 0 && it.code.contains("CONTRAST", ignoreCase = true) }, "Expected low contrast on slide 0, got: ${report.issues}")
            assertTrue(report.issues.any { it.slideIndex == 1 && it.code.contains("BOUNDS", ignoreCase = true) }, "Expected out-of-bounds on slide 1, got: ${report.issues}")
            assertTrue(report.issues.any { it.slideIndex == 1 && it.code.contains("TINY", ignoreCase = true) }, "Expected tiny text on slide 1, got: ${report.issues}")
            assertTrue(report.issues.any { it.slideIndex == 2 && it.code.contains("OVERLAP", ignoreCase = true) }, "Expected overlap on slide 2, got: $codes")
        }
    }

    @Test
    fun textOverlapIsScopedToCoVisibleBeatsAndIgnoresFadedOutNodes() = runBlocking {
        val doc = DeckDocument(
            id = "beat-scoped-overlap",
            title = "Beat Scoped Overlap",
            defaultTheme = "light",
            themes = mapOf(
                "light" to DeckThemeSpec(
                    colors = mapOf(
                        "background" to "#EBEBEB",
                        "foreground" to "#242124",
                    ),
                ),
            ),
            slides = listOf(
                SlideSpec(
                    id = "s0",
                    background = Fill.Solid("#EBEBEB"),
                    beats = listOf(BeatSpec(), BeatSpec()),
                    nodes = listOf(
                        NodeSpec(
                            id = "s0:fading-out",
                            frame = FrameSpec(x = 120.0, y = 120.0, w = 640.0, h = 70.0),
                            text = TextPayload(value = RichTextValue.Plain("Beat 0 copy"), color = "#242124", size = 36.0),
                            builds = listOf(
                                Build.Change(beat = 1, set = buildJsonObject { put("opacity", 0.0) }),
                            ),
                        ),
                        NodeSpec(
                            id = "s0:entering-beat-1",
                            frame = FrameSpec(x = 120.0, y = 120.0, w = 640.0, h = 70.0),
                            text = TextPayload(value = RichTextValue.Plain("Beat 1 replacement"), color = "#242124", size = 36.0),
                            builds = listOf(Build.Enter(beat = 1, effect = "appear")),
                        ),
                    ),
                ),
            ),
        )

        DocumentDeckLintEngine().use { engine ->
            val report = engine.lintFullDeck(doc)
            assertFalse(
                report.issues.any { it.code == "TEXT_OVERLAP" },
                "Sequential cross-beat text replacements must not trigger TEXT_OVERLAP, got: ${report.issues}",
            )
        }
    }

    @Test
    fun textOverlapRespectsPerRunOpaqueColorsEvenWhenBaseColorIsTransparent() = runBlocking {
        val doc = DeckDocument(
            id = "run-color-overlap",
            title = "Run Color Overlap",
            defaultTheme = "light",
            themes = mapOf(
                "light" to DeckThemeSpec(
                    colors = mapOf(
                        "background" to "#EBEBEB",
                        "foreground" to "#242124",
                    ),
                ),
            ),
            slides = listOf(
                SlideSpec(
                    id = "s0",
                    background = Fill.Solid("#EBEBEB"),
                    nodes = listOf(
                        NodeSpec(
                            id = "s0:transparent-base-visible-run",
                            frame = FrameSpec(x = 120.0, y = 120.0, w = 640.0, h = 70.0),
                            text = TextPayload(
                                value = RichTextValue.Runs(
                                    listOf(
                                        TextRun(text = "Hidden ", color = "#00000000"),
                                        TextRun(text = "Visible run", color = "#242124"),
                                    ),
                                ),
                                color = "#00000000",
                                size = 36.0,
                            ),
                        ),
                        NodeSpec(
                            id = "s0:plain-overlapping",
                            frame = FrameSpec(x = 120.0, y = 128.0, w = 640.0, h = 70.0),
                            text = TextPayload(
                                value = RichTextValue.Plain("Overlapping text"),
                                color = "#242124",
                                size = 36.0,
                            ),
                        ),
                    ),
                ),
            ),
        )

        DocumentDeckLintEngine().use { engine ->
            val report = engine.lintFullDeck(doc)
            assertTrue(
                report.issues.any { it.code == "TEXT_OVERLAP" },
                "Transparent base color with visible nonblank run must still report TEXT_OVERLAP, got: ${report.issues}",
            )
        }
    }
}
