package dev.sebastiano.ciacola.document

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

class ClipPlaybackTest {
    private val image = ImagePayload(src = "clip.webp")
    private val window = ClipWindow(startMs = 0.0, endMs = 9000.0, loop = false)

    @Test
    fun `clip without playback builds autoplays from the trim start once the slide shows`() {
        val trimmed = image.copy(startMs = 500, playbackRate = 2.0)
        val before = ClipPlayback.stateAtBeat(trimmed, emptyList(), ClipPlayback.BEFORE_FIRST_BEAT)
        assertFalse(before.playing)
        assertTrue(before.showPoster)
        val atZero = ClipPlayback.stateAtBeat(trimmed, emptyList(), 0)
        assertEquals(ClipBeatState(playing = true, rate = 2.0, anchorMs = 500, pinnedAtBeat = false, showPoster = false), atZero)
    }

    @Test
    fun `playback builds fold across beats`() {
        val builds = listOf(
            Build.Playback(beat = 1, action = PlaybackAction.Play),
            Build.Playback(beat = 2, action = PlaybackAction.Pause, atMs = 3000),
            Build.Playback(beat = 3, action = PlaybackAction.Rate, rate = 0.5),
            Build.Playback(beat = 4, action = PlaybackAction.Seek),
            Build.Playback(beat = 5, action = PlaybackAction.Play, rate = 2.0),
        )
        fun at(beat: Int) = ClipPlayback.stateAtBeat(image, builds, beat)
        assertEquals(ClipBeatState(false, 1.0, 0, pinnedAtBeat = false, showPoster = true), at(0))
        assertEquals(ClipBeatState(true, 1.0, 0, pinnedAtBeat = false, showPoster = false), at(1))
        assertEquals(ClipBeatState(false, 1.0, 3000, pinnedAtBeat = true, showPoster = false), at(2))
        assertEquals(ClipBeatState(false, 0.5, 3000, pinnedAtBeat = false, showPoster = false), at(3))
        assertEquals(ClipBeatState(false, 0.5, 0, pinnedAtBeat = true, showPoster = false), at(4))
        assertEquals(ClipBeatState(true, 2.0, 0, pinnedAtBeat = false, showPoster = false), at(5))
    }

    @Test
    fun `stepping back and forward replays the segment from the same entry position`() {
        val builds = listOf(
            Build.Playback(beat = 1, action = PlaybackAction.Play),
            Build.Playback(beat = 2, action = PlaybackAction.Pause),
            Build.Playback(beat = 3, action = PlaybackAction.Play),
        )
        val head = ClipPlayhead()
        fun enter(beat: Int) = head.enterBeat(beat, ClipPlayback.stateAtBeat(image, builds, beat), window)

        enter(0)
        enter(1)
        head.advance(1200.0, window)
        enter(2) // pause where beat 1 left it
        assertEquals(1200.0, head.positionMs)
        assertFalse(head.playing)
        enter(3)
        head.advance(800.0, window)
        assertEquals(2000.0, head.positionMs)

        enter(2) // step back: restore the paused frame
        assertEquals(1200.0, head.positionMs)
        enter(3) // forward again: same segment start
        assertEquals(1200.0, head.positionMs)
        assertTrue(head.playing)

        enter(1) // further back: beat 1 replays from its own entry
        assertEquals(0.0, head.positionMs)
    }

    @Test
    fun `pinned beats jump even on a forward step and scrubs carry into the next beat`() {
        val builds = listOf(
            Build.Playback(beat = 1, action = PlaybackAction.Play),
            Build.Playback(beat = 2, action = PlaybackAction.Play, atMs = 5000),
            Build.Playback(beat = 3, action = PlaybackAction.Pause),
        )
        val head = ClipPlayhead()
        fun enter(beat: Int) = head.enterBeat(beat, ClipPlayback.stateAtBeat(image, builds, beat), window)
        enter(0)
        enter(1)
        head.advance(300.0, window)
        enter(2)
        assertEquals(5000.0, head.positionMs)
        head.seek(7000.0, window)
        enter(3)
        assertEquals(7000.0, head.positionMs)
    }

    @Test
    fun `restart replays the current beat and poster waits for play`() {
        val builds = listOf(Build.Playback(beat = 1, action = PlaybackAction.Play))
        val head = ClipPlayhead()
        head.enterBeat(0, ClipPlayback.stateAtBeat(image, builds, 0), window)
        assertTrue(head.showPoster)
        head.advance(500.0, window)
        assertEquals(0.0, head.positionMs, "the poster holds the playhead")
        head.enterBeat(1, ClipPlayback.stateAtBeat(image, builds, 1), window)
        head.advance(2500.0, window)
        head.restart(window)
        assertEquals(0.0, head.positionMs)
        assertTrue(head.playing)
    }

    @Test
    fun `holding a scrub freezes the frame and releasing resumes from it`() {
        val head = ClipPlayhead()
        head.enterBeat(0, ClipPlayback.stateAtBeat(image, emptyList(), 0), window)
        head.advance(1000.0, window)
        head.beginScrub()
        head.seek(4000.0, window)
        head.advance(500.0, window)
        assertEquals(4000.0, head.positionMs, "held while the button is down")
        head.endScrub()
        head.advance(500.0, window)
        assertEquals(4500.0, head.positionMs)
        assertTrue(head.playing)
    }

    @Test
    fun `toggling pause stops and resumes, and replays a clip held at its end`() {
        val head = ClipPlayhead()
        head.enterBeat(0, ClipPlayback.stateAtBeat(image, emptyList(), 0), window)
        head.advance(1000.0, window)
        head.togglePause(window)
        head.advance(500.0, window)
        assertEquals(1000.0, head.positionMs)
        head.togglePause(window)
        head.advance(500.0, window)
        assertEquals(1500.0, head.positionMs)
        head.advance(60_000.0, window)
        head.togglePause(window)
        assertEquals(0.0, head.positionMs, "one press at the end starts over")
        assertTrue(head.playing)
    }

    @Test
    fun `a zero or negative rate does not move the playhead`() {
        val head = ClipPlayhead()
        head.enterBeat(0, ClipPlayback.stateAtBeat(image.copy(playbackRate = -2.0), emptyList(), 0), window)
        head.advance(1000.0, window)
        assertEquals(0.0, head.positionMs)
    }

    @Test
    fun `leaving a slide backward holds the frame instead of showing the poster`() {
        val head = ClipPlayhead()
        val before = ClipPlayback.stateAtBeat(image, emptyList(), ClipPlayback.BEFORE_FIRST_BEAT)
        head.enterBeat(0, ClipPlayback.stateAtBeat(image, emptyList(), 0), window)
        head.advance(700.0, window)
        head.enterBeat(ClipPlayback.BEFORE_FIRST_BEAT, before, window)
        head.enterBeat(ClipPlayback.BEFORE_FIRST_BEAT, before, window)
        assertEquals(700.0, head.positionMs)
        assertFalse(head.playing)
        assertFalse(head.showPoster)
        head.advance(500.0, window)
        assertEquals(700.0, head.positionMs)
        head.togglePause(window)
        head.restart(window)
        assertFalse(head.playing, "P and R do nothing before the first beat")
        head.enterBeat(0, ClipPlayback.stateAtBeat(image, emptyList(), 0), window)
        assertEquals(0.0, head.positionMs, "coming back restores the entry position")
        assertTrue(head.playing)
    }

    @Test
    fun `after unmounting a slide left backward the next arrival shows the poster again`() {
        val head = ClipPlayhead()
        val before = ClipPlayback.stateAtBeat(image, emptyList(), ClipPlayback.BEFORE_FIRST_BEAT)
        head.enterBeat(0, ClipPlayback.stateAtBeat(image, emptyList(), 0), window)
        head.advance(700.0, window)
        head.enterBeat(ClipPlayback.BEFORE_FIRST_BEAT, before, window)
        head.detach()
        head.enterBeat(ClipPlayback.BEFORE_FIRST_BEAT, before, window)
        assertTrue(head.showPoster, "a fresh forward arrival shows the poster, not the stale frame")
        assertEquals(0.0, head.positionMs)
    }

    @Test
    fun `a held clip stops until its node is shown again`() {
        val head = ClipPlayhead()
        head.hold()
        head.advance(500.0, window)
        assertEquals(0.0, head.positionMs)
        assertFalse(head.playing)
        head.enterBeat(1, ClipPlayback.stateAtBeat(image, emptyList(), 1), window)
        assertTrue(head.playing)
        head.advance(500.0, window)
        head.beginScrub()
        assertTrue(head.scrubbing)
        head.hold()
        assertFalse(head.playing)
        assertFalse(head.scrubbing)
        assertEquals(500.0, head.positionMs)
    }

    @Test
    fun `end of trim holds or loops`() {
        val head = ClipPlayhead()
        val trimmed = ClipWindow(startMs = 1000.0, endMs = 2000.0, loop = false)
        head.enterBeat(0, ClipPlayback.stateAtBeat(image.copy(startMs = 1000, endMs = 2000), emptyList(), 0), trimmed)
        head.advance(1500.0, trimmed)
        assertEquals(2000.0, head.positionMs)
        val looping = trimmed.copy(loop = true)
        head.seek(1800.0, looping)
        head.advance(500.0, looping)
        assertEquals(1300.0, head.positionMs)
    }

    @Test
    fun `window clamps trim to the clip duration`() {
        assertEquals(ClipWindow(0.0, 9000.0, false), ClipWindow.of(image, 9000))
        assertEquals(ClipWindow(1000.0, 4000.0, true), ClipWindow.of(image.copy(startMs = 1000, endMs = 4000, loop = true), 9000))
        assertEquals(ClipWindow(9000.0, 9000.0, false), ClipWindow.of(image.copy(startMs = 12000, endMs = 20000), 9000))
    }

    @Test
    fun `effective beat follows forward segments and arriving slides`() {
        assertEquals(2, ClipPlayback.effectiveBeat(DeckClock(DocSegment.Beat("s", 1), 10f, 100f), "s", 3))
        assertEquals(1, ClipPlayback.effectiveBeat(DeckClock(DocSegment.Rest("s", 1), 0f, 0f), "s", 3))
        assertEquals(-1, ClipPlayback.effectiveBeat(DeckClock(DocSegment.Cross("a", "s"), 10f, 100f), "s", 3))
        assertEquals(3, ClipPlayback.effectiveBeat(DeckClock(DocSegment.Cross("s", "b"), 10f, 100f), "s", 3))
        assertEquals(null, ClipPlayback.effectiveBeat(DeckClock(DocSegment.Rest("other", 0), 0f, 0f), "s", 3))
    }

    @Test
    fun `playback build and image fields round-trip through JSON`() {
        val json = Json { ignoreUnknownKeys = false }
        val build = json.decodeFromString(
            Build.serializer(),
            """{"kind":"playback","beat":2,"action":"pause","atMs":1500,"rate":0.5}""",
        )
        assertIs<Build.Playback>(build)
        assertEquals(Build.Playback(beat = 2, action = PlaybackAction.Pause, atMs = 1500, rate = 0.5), build)
        val payload = json.decodeFromString(
            ImagePayload.serializer(),
            """{"src":"a.webp","loop":true,"playbackRate":1.5,"startMs":100,"endMs":900,"posterMs":200,"maxPixels":50000000}""",
        )
        assertEquals(ImagePayload("a.webp", loop = true, playbackRate = 1.5, startMs = 100, endMs = 900, posterMs = 200, maxPixels = 50_000_000), payload)
    }

    @Test
    fun `validation flags bad playback fields and builds`() {
        val node = NodeSpec(
            id = "clip",
            image = ImagePayload("clip.webp", playbackRate = 0.0, startMs = 900, endMs = 500, posterMs = -1, maxPixels = 0),
            builds = listOf(
                Build.Playback(beat = 1, action = PlaybackAction.Rate),
                Build.Playback(beat = 1, action = PlaybackAction.Play, atMs = -5, rate = -1.0),
            ),
        )
        val textNode = NodeSpec(
            id = "label",
            text = TextPayload(value = RichTextValue.Plain("x")),
            builds = listOf(Build.Playback(beat = 1, action = PlaybackAction.Play)),
        )
        val doc = DeckDocument(
            id = "d",
            slides = listOf(SlideSpec(id = "s", beats = listOf(BeatSpec(), BeatSpec()), nodes = listOf(node, textNode))),
        )
        val codes = DeckDocumentCodec.validate(doc).map { it.code }.toSet()
        for (code in listOf(
            "INVALID_PLAYBACK_RATE", "NEGATIVE_CLIP_TIME", "EMPTY_CLIP_TRIM", "INVALID_MAX_PIXELS",
            "PLAYBACK_RATE_MISSING", "PLAYBACK_ON_NON_IMAGE",
        )) {
            assertTrue(code in codes, "expected $code in $codes")
        }
    }

    @Test
    fun `validation covers an empty trim from zero and change builds on clip fields`() {
        fun codes(node: NodeSpec) = DeckDocumentCodec.validate(
            DeckDocument(id = "d", slides = listOf(SlideSpec(id = "s", beats = listOf(BeatSpec(), BeatSpec()), nodes = listOf(node)))),
        ).map { it.code }.toSet()
        assertTrue("EMPTY_CLIP_TRIM" in codes(NodeSpec(id = "clip", image = ImagePayload("clip.webp", endMs = 0))))
        val patch = kotlinx.serialization.json.buildJsonObject {
            put("image", kotlinx.serialization.json.buildJsonObject {
                put("playbackRate", kotlinx.serialization.json.JsonPrimitive(-2.0))
                put("startMs", kotlinx.serialization.json.JsonPrimitive(900))
                put("endMs", kotlinx.serialization.json.JsonPrimitive(500))
                put("maxPixels", kotlinx.serialization.json.JsonPrimitive(0))
            })
        }
        val changed = codes(NodeSpec(id = "clip", image = ImagePayload("clip.webp"), builds = listOf(Build.Change(beat = 1, set = patch))))
        for (code in listOf("INVALID_PLAYBACK_RATE", "EMPTY_CLIP_TRIM", "INVALID_MAX_PIXELS")) {
            assertTrue(code in changed, "expected $code in $changed")
        }
    }

    @Test
    fun `after references never bind to playback builds`() {
        val clip = NodeSpec(
            id = "clip",
            image = ImagePayload("clip.webp"),
            builds = listOf(
                Build.Enter(beat = 1, effect = "fade", durationMs = 500),
                Build.Playback(beat = 1, id = "play1", action = PlaybackAction.Play),
                Build.Playback(beat = 2, action = PlaybackAction.Pause),
            ),
        )
        val label = NodeSpec(
            id = "label",
            text = TextPayload(value = RichTextValue.Plain("x")),
            builds = listOf(Build.Enter(beat = 1, effect = "fade", after = "clip")),
        )
        val explicit = label.copy(id = "label2", builds = listOf(Build.Enter(beat = 1, effect = "fade", after = "clip#play1")))
        val doc = DeckDocument(
            id = "d",
            slides = listOf(SlideSpec(id = "s", beats = listOf(BeatSpec(), BeatSpec(), BeatSpec()), nodes = listOf(clip, label, explicit))),
        )
        val issues = DeckDocumentCodec.validate(doc).filter { it.address.contains("label2") }.map { it.code }
        assertTrue("AFTER_PLAYBACK_BUILD" in issues, "$issues")
        val edited = DeckStructureEdits.deleteBeat(doc, "s", 2)
        val after = edited.slides.single().nodes.first { it.id == "label" }.builds.single().after
        assertTrue(after == "clip" || after?.startsWith("clip#") == true && after != "clip#play1", "after rewritten to $after")
    }

    @Test
    fun `deleting a beat never rebinds a beat-0 enter to a playback build`() {
        val clip = NodeSpec(
            id = "clip",
            image = image,
            builds = listOf(
                Build.Emphasis(beat = 0, effect = "pulse"),
                Build.Playback(beat = 0, id = "play0", action = PlaybackAction.Play),
                Build.Emphasis(beat = 1, effect = "pulse"),
            ),
        )
        val label = NodeSpec(
            id = "label",
            text = TextPayload(value = RichTextValue.Plain("x")),
            builds = listOf(Build.Enter(beat = 0, effect = "fade", after = "clip")),
        )
        val doc = DeckDocument(
            id = "d",
            slides = listOf(SlideSpec(id = "s", beats = listOf(BeatSpec(), BeatSpec()), nodes = listOf(clip, label))),
        )
        val edited = DeckStructureEdits.deleteBeat(doc, "s", 1)
        val after = edited.slides.single().nodes.first { it.id == "label" }.builds.single().after
        assertTrue(after != "clip#play0", "after rewritten to $after")
        assertTrue(DeckDocumentCodec.validate(edited).none { it.code == "AFTER_PLAYBACK_BUILD" })
    }

    @Test
    fun `deck JSON with playback decodes and keeps static images unchanged`() {
        val text = """
            {"format":1,"id":"d","slides":[{"id":"s","beats":[{},{}],"nodes":[
              {"id":"still","image":{"src":"a.png"}},
              {"id":"clip","image":{"src":"c.webp","loop":true,"posterMs":250},
               "builds":[{"kind":"playback","beat":1,"action":"play","rate":2.0}]}
            ]}]}
        """.trimIndent()
        val result = assertIs<DeckDecodeResult.Success>(DeckDocumentCodec.decode(text))
        val nodes = result.document.slides.single().nodes
        assertEquals(ImagePayload("a.png"), nodes[0].image)
        assertEquals(listOf<Build>(Build.Playback(beat = 1, action = PlaybackAction.Play, rate = 2.0)), nodes[1].builds)
        val printed = CanonicalJson.encodeToJsonObject(result.document).toString()
        assertFalse("\"loop\":false" in printed)
        assertTrue("\"kind\":\"playback\"" in printed, printed)
    }
}
