package dev.sebastiano.ciacola.timeline

import dev.sebastiano.ciacola.document.BeatSpec
import dev.sebastiano.ciacola.document.Build
import dev.sebastiano.ciacola.document.CanonicalJson
import dev.sebastiano.ciacola.document.DeckDecodeResult
import dev.sebastiano.ciacola.document.DeckDocument
import dev.sebastiano.ciacola.document.DeckDocumentCodec
import dev.sebastiano.ciacola.document.DeckDocumentStore
import dev.sebastiano.ciacola.document.DeckSpecimenRegistry
import dev.sebastiano.ciacola.document.DeckThemeSpec
import dev.sebastiano.ciacola.document.Fill
import dev.sebastiano.ciacola.document.FrameSpec
import dev.sebastiano.ciacola.document.MorphBy
import dev.sebastiano.ciacola.document.NodeSpec
import dev.sebastiano.ciacola.document.Phases
import dev.sebastiano.ciacola.document.RichTextValue
import dev.sebastiano.ciacola.document.SlideSpec
import dev.sebastiano.ciacola.document.SpecimenPayload
import dev.sebastiano.ciacola.document.Stagger
import dev.sebastiano.ciacola.document.StaggerOrder
import dev.sebastiano.ciacola.document.TextMorphSpec
import dev.sebastiano.ciacola.document.TextPayload
import dev.sebastiano.ciacola.document.Timing
import dev.sebastiano.ciacola.document.TransitionSpec
import dev.sebastiano.ciacola.document.TransitionType
import dev.sebastiano.ciacola.document.deckSpecimens
import java.awt.GraphicsEnvironment
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Timeline scale benchmark on a heterogeneous 136-slide / ~700-track synthetic deck.
 *
 * Measures and asserts:
 * - Open deck to interactive Timeline: `<= 2,000 ms`
 * - Timeline scroll & zoom 95th percentile frame: `<= 20 ms`
 * - 1-property edit -> updated Timeline snapshot 95th percentile: `<= 50 ms`
 * - Strict incremental invalidation: ONLY slide `N` and transitions `N-1 -> N`, `N -> N+1` re-planned!
 * - Plan cache memory footprint: `<= 50 MB`
 *
 * Records exact Apple Silicon model, macOS version, RAM, and display configuration.
 */
class DocumentTimelineScaleBenchmarkTest {

    private fun buildSpecimens(): DeckSpecimenRegistry = deckSpecimens {
        specimen("bench.pulse", "Bench Pulse") {
            track(name = "wave", beat = 1, durationMs = 420)
            loop(name = "bench-ambient", label = "Bench Ambient", periodMs = 5_000L)
            content {}
        }
    }

    private fun buildSynthetic136SlideDeck(): DeckDocument {
        val slides = (0 until 136).map { i ->
            val slideId = "slide-${"%03d".format(i + 1)}"
            val hasMorph = i % 3 == 0
            val hasSpecimen = i % 4 == 0
            val beatCount = if (i % 5 == 0) 3 else 2

            val beats = (0 until beatCount).map { b ->
                BeatSpec(
                    notes = "Notes for $slideId beat $b",
                    stagger = if (b == 1) Stagger(ms = 45, order = StaggerOrder.TopToBottom) else null,
                    phases = if (b == 1) {
                        Phases(
                            exit = Timing(delayMs = 0, durationMs = 180),
                            move = Timing(delayMs = 60, durationMs = 360),
                            enter = Timing(delayMs = 120, durationMs = 300),
                        )
                    } else {
                        null
                    },
                )
            }

            val nodes = buildList {
                val titleBuilds = buildList<Build> {
                    if (hasMorph) {
                        add(
                            Build.Change(
                                beat = 1,
                                set = buildJsonObject {
                                    putJsonObject("text") {
                                        put("value", "Slide ${i + 1} updated morph headline token")
                                    }
                                },
                                morph = TextMorphSpec(by = MorphBy.Word, stagger = Stagger(ms = 16)),
                            ),
                        )
                    } else {
                        add(Build.Enter(beat = 1, effect = "fadeMove", durationMs = 320))
                    }
                }
                add(
                    NodeSpec(
                        id = if (i % 2 == 0) "shared-title" else "title-$i",
                        frame = FrameSpec(x = 120.0, y = 80.0 + (i % 3) * 16.0, w = 1200.0, h = 110.0),
                        text = TextPayload(
                            value = RichTextValue.Plain("Slide ${i + 1} initial headline token"),
                            style = "title",
                            morph = TextMorphSpec(by = MorphBy.Word),
                        ),
                        builds = titleBuilds,
                    ),
                )

                for (c in 0 until 3) {
                    add(
                        NodeSpec(
                            id = "card-$i-$c",
                            frame = FrameSpec(x = 120.0 + c * 540.0, y = 300.0, w = 480.0, h = 240.0),
                            text = TextPayload(value = RichTextValue.Plain("Item $c on $slideId"), style = "body"),
                            builds = buildList {
                                add(Build.Enter(beat = 1, effect = "dissolve", durationMs = 260 + c * 30))
                                if (beatCount == 3 && c == 0) {
                                    add(Build.Emphasis(beat = 2, effect = "pulse", durationMs = 300))
                                }
                            },
                        ),
                    )
                }

                if (hasSpecimen) {
                    add(
                        NodeSpec(
                            id = "spec-$i",
                            frame = FrameSpec(x = 120.0, y = 620.0, w = 640.0, h = 340.0),
                            specimen = SpecimenPayload(ref = "bench.pulse"),
                        ),
                    )
                }
            }

            SlideSpec(
                id = slideId,
                title = "Slide ${i + 1}",
                background = Fill.Solid("#10131A"),
                beats = beats,
                nodes = nodes,
                transition = when (i % 3) {
                    0 -> TransitionSpec(type = TransitionType.MagicMove, durationMs = 450, stagger = Stagger(ms = 30))
                    1 -> TransitionSpec(type = TransitionType.Dissolve, durationMs = 300)
                    else -> TransitionSpec(type = TransitionType.Push, durationMs = 350)
                },
            )
        }

        return DeckDocument(
            id = "timeline-scale-136",
            title = "136-Slide Timeline Scale Benchmark Deck",
            themes = mapOf(
                "dark" to DeckThemeSpec(preset = "stageDark"),
                "light" to DeckThemeSpec(preset = "keynoteLight"),
            ),
            defaultTheme = "dark",
            slides = slides,
        )
    }

    @Test
    fun timeline136SlideScaleBenchmarkMeetsAllBudgets() {
        val specimens = buildSpecimens()
        val syntheticDoc = buildSynthetic136SlideDeck()
        val jsonText = CanonicalJson.print(syntheticDoc)

        // 1. Open deck to interactive Timeline (decode + load store + initial analytical plan).
        val openStartNs = System.nanoTime()
        val decoded = DeckDocumentCodec.decode(jsonText, specimens = specimens)
        assertTrue(decoded is DeckDecodeResult.Success)
        val store = DeckDocumentStore()
        store.loadInitial(decoded.document, issues = decoded.lintIssues)
        val controller = DocumentTimelineController(store = store, specimens = specimens)
        val initialSnap = controller.snapshot
        val openMs = (System.nanoTime() - openStartNs) / 1_000_000.0

        val deckTracks = initialSnap.snapshot.tracks
        val totalTracks = deckTracks.size
        assertEquals(136, initialSnap.snapshot.slides.size)
        assertTrue(totalTracks >= 650, "Expected ~700 tracks in synthetic 136-slide deck, got $totalTracks")

        // 2. Timeline scroll & zoom 100-frame benchmark (windowed visible row range + viewport projection).
        val viewport = TimelineViewportState()
        val scrollZoomDurationsMs = mutableListOf<Double>()
        repeat(100) { frame ->
            val t0 = System.nanoTime()
            viewport.updateSurfaceMetrics(
                surface = TimelineSurface.Docked,
                widthPx = 1_440f,
                surfaceDensity = 2f,
            )
            viewport.contentDurationMs = initialSnap.snapshot.totalDurationMs.coerceAtLeast(1)
            val factor = if (frame % 2 == 0) 1.06f else 0.95f
            viewport.zoomBy(factor = factor, anchorX = 360f + (frame % 10) * 40f)
            val scrollY = (frame * 37f) % (totalTracks * 34f).coerceAtLeast(1f)
            val visibleRows = computeVisibleTrackRowRange(
                totalRows = totalTracks,
                scrollYPx = scrollY,
                viewportHeightPx = 420f,
                rowHeightPx = 34f,
            )
            var visibleBlocks = 0
            for (rowIdx in visibleRows) {
                val track = deckTracks[rowIdx]
                for (block in track.blocks) {
                    val x = block.startMs * viewport.pxPerMs - viewport.scrollX
                    if (x >= -200f && x <= 1_640f) visibleBlocks++
                }
            }
            assertTrue(visibleBlocks >= 0)
            scrollZoomDurationsMs += (System.nanoTime() - t0) / 1_000_000.0
        }
        val scrollZoomP95Ms = percentile(scrollZoomDurationsMs, 0.95)

        // 3. 1-property edit -> updated Timeline snapshot (50 iterations on slide 68) with strict incremental invalidation.
        val targetIdx = 68
        val targetSlideId = store.document.slides[targetIdx].id
        val prevSlideId = store.document.slides[targetIdx - 1].id
        val nextSlideId = store.document.slides[targetIdx + 1].id
        // Warm up JIT (C1/C2 tier-up) and caches before measuring steady-state 1-property edit latency.
        repeat(50) { iter ->
            controller.updateBeatStagger(
                slideId = targetSlideId,
                beatIndex = 1,
                ms = 35 + (iter % 15) * 5,
            )
        }

        val editDurationsMs = mutableListOf<Double>()

        repeat(50) { iter ->
            val t0 = System.nanoTime()
            controller.updateBeatStagger(
                slideId = targetSlideId,
                beatIndex = 1,
                ms = 40 + (iter % 20) * 5,
            )
            val diag = controller.snapshot.diagnostics
            editDurationsMs += (System.nanoTime() - t0) / 1_000_000.0

            // Incremental planner invariant: re-plan ONLY slide N and transitions N-1->N, N->N+1.
            assertEquals(
                setOf(targetSlideId),
                diag.replannedSlideIds,
                "1-property edit on $targetSlideId must re-plan ONLY that slide, got ${diag.replannedSlideIds}",
            )
            assertEquals(136, diag.cachedSlideCount, "All 136 slides must remain cached (135 cache hits + 1 replanned)")
            assertTrue(
                diag.replannedTransitions.all { it == (prevSlideId to targetSlideId) || it == (targetSlideId to nextSlideId) },
                "Only adjacent transitions may re-plan, got ${diag.replannedTransitions}",
            )
            assertTrue(diag.replannedTransitions.size <= 2)
        }
        val editP95Ms = percentile(editDurationsMs, 0.95)

        // 4. Plan cache memory estimate (<= 50 MB).
        val cacheBytes = controller.plannerCache.estimateCacheMemoryBytes()
        val cacheMb = cacheBytes / (1024.0 * 1024.0)

        val envReport = collectMachineRecord()
        println(
            """
            |=== TIMELINE SCALE BENCHMARK (136 slides, $totalTracks tracks) ===
            |Machine: $envReport
            |Open-to-interactive: ${String.format(java.util.Locale.US, "%.2f", openMs)} ms (budget <= 2000 ms)
            |Scroll/zoom p95:     ${String.format(java.util.Locale.US, "%.3f", scrollZoomP95Ms)} ms (budget <= 20 ms)
            |1-prop edit p95:     ${String.format(java.util.Locale.US, "%.3f", editP95Ms)} ms (budget <= 50 ms; replanned=1/136 slides, <=2 transitions)
            |Plan cache memory:   ${String.format(java.util.Locale.US, "%.3f", cacheMb)} MB ($cacheBytes bytes; budget <= 50 MB)
            |=========================================================================
            """.trimMargin(),
        )

        assertTrue(openMs <= 2_000.0, "Open-to-interactive ${openMs}ms exceeded 2000ms budget")
        assertTrue(scrollZoomP95Ms <= 20.0, "Timeline scroll/zoom p95 ${scrollZoomP95Ms}ms exceeded 20ms budget")
        assertTrue(editP95Ms <= 50.0, "1-property edit p95 ${editP95Ms}ms exceeded 50ms budget")
        assertTrue(cacheBytes <= 50L * 1024L * 1024L, "Plan cache $cacheBytes bytes exceeded 50 MB budget")
    }

    private fun percentile(values: List<Double>, p: Double): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        val idx = ((sorted.size - 1) * p).roundToInt().coerceIn(0, sorted.lastIndex)
        return sorted[idx]
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
