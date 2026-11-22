package dev.sebastiano.ciacola.timeline

import dev.sebastiano.ciacola.document.BeatSpec
import dev.sebastiano.ciacola.document.Build
import dev.sebastiano.ciacola.document.Curve
import dev.sebastiano.ciacola.document.DeckDocument
import dev.sebastiano.ciacola.document.DeckDocumentStore
import dev.sebastiano.ciacola.document.DeckLoadState
import dev.sebastiano.ciacola.document.DeckSpecimenRegistry
import dev.sebastiano.ciacola.document.DeckThemeSpec
import dev.sebastiano.ciacola.document.FrameSpec
import dev.sebastiano.ciacola.document.NodeSpec
import dev.sebastiano.ciacola.document.RichTextValue
import dev.sebastiano.ciacola.document.SlideSpec
import dev.sebastiano.ciacola.document.SpecimenPayload
import dev.sebastiano.ciacola.document.TextPayload
import dev.sebastiano.ciacola.document.TransitionSpec
import dev.sebastiano.ciacola.document.TransitionType
import dev.sebastiano.ciacola.document.deckSpecimens

/** Shared three-slide fixture: zero-length transition, shared + slide-local ambient loops. */
internal object TimelineFixtures {
    fun specimens(): DeckSpecimenRegistry = deckSpecimens {
        specimen("demo.pulse", "Pulse field") {
            loop(name = "ambient", label = "Ambient pulse", periodMs = 4_000L)
            content {}
        }
        specimen("demo.wave", "Wave field") {
            loop(name = "drift", label = "Wave drift", periodMs = 6_000L)
            content {}
        }
    }

    private fun text(id: String, value: String, y: Double, vararg builds: Build) = NodeSpec(
        id = id,
        frame = FrameSpec(x = 120.0, y = y, w = 900.0, h = 100.0),
        text = TextPayload(value = RichTextValue.Plain(value), style = "body"),
        builds = builds.toList(),
    )

    private fun specimen(id: String, ref: String) = NodeSpec(
        id = id,
        frame = FrameSpec(x = 1100.0, y = 200.0, w = 400.0, h = 300.0),
        specimen = SpecimenPayload(ref = ref),
    )

    fun deck(): DeckDocument = DeckDocument(
        id = "timeline-fixture",
        title = "Timeline Fixture",
        themes = mapOf("dark" to DeckThemeSpec(preset = "stageDark")),
        defaultTheme = "dark",
        slides = listOf(
            SlideSpec(
                id = "s001",
                title = "Intro",
                beats = listOf(BeatSpec(), BeatSpec()),
                nodes = listOf(
                    text("s1:prose", "A scene begins with a single line of prose", 100.0, Build.Enter(beat = 1, effect = "fadeMove", durationMs = 300)),
                    text("s1:aside", "An aside", 300.0, Build.Enter(beat = 1, effect = "dissolve", durationMs = 200)),
                    specimen("pulse-a", "demo.pulse"),
                ),
                // Zero-length transition: shares its timestamp with s002's base beat.
                transition = TransitionSpec(type = TransitionType.None),
            ),
            SlideSpec(
                id = "s002",
                title = "Middle",
                beats = listOf(BeatSpec(), BeatSpec()),
                nodes = listOf(
                    text("s2:body", "Second slide body", 200.0, Build.Enter(beat = 1, effect = "dissolve", durationMs = 250)),
                    specimen("wave", "demo.wave"),
                ),
                transition = TransitionSpec(type = TransitionType.Dissolve, durationMs = 400),
            ),
            SlideSpec(
                id = "s003",
                beats = listOf(BeatSpec(), BeatSpec()),
                nodes = listOf(
                    text("s3:tail", "Closing words", 200.0, Build.Enter(beat = 1, effect = "dissolve", durationMs = 250)),
                    specimen("pulse-b", "demo.pulse"),
                ),
            ),
        ),
    )

    fun controller(doc: DeckDocument = deck()): DocumentTimelineController =
        DocumentTimelineController(
            store = DeckDocumentStore(doc, initialLoad = DeckLoadState.Loaded),
            specimens = specimens(),
        )

    /** Many-slide deck so selection reveal has to scroll both axes. */
    fun largeDeck(slideCount: Int = 30): DeckDocument = DeckDocument(
        id = "timeline-large",
        title = "Large",
        themes = mapOf("dark" to DeckThemeSpec(preset = "stageDark")),
        defaultTheme = "dark",
        slides = List(slideCount) { i ->
            SlideSpec(
                id = "big-$i",
                title = "Slide $i",
                beats = listOf(BeatSpec(), BeatSpec()),
                nodes = listOf(
                    text("big-$i:a", "Alpha $i", 100.0, Build.Enter(beat = 1, effect = "dissolve", durationMs = 300)),
                    text("big-$i:b", "Beta $i", 300.0, Build.Enter(beat = 1, effect = "dissolve", durationMs = 300)),
                ),
                transition = TransitionSpec(type = TransitionType.Dissolve, durationMs = 300, curve = Curve.Linear),
            )
        },
    )
}
