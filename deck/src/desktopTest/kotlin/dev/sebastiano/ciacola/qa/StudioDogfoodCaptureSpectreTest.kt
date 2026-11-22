package dev.sebastiano.ciacola.qa

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPosition
import dev.bnorm.storyboard.Storyboard
import dev.sebastiano.ciacola.document.TrackKind
import dev.sebastiano.ciacola.studio.StudioInspectorTab
import dev.sebastiano.ciacola.theme.DeckThemePreset
import dev.sebastiano.ciacola.timeline.DocumentTimelineTrackRef
import dev.sebastiano.ciacola.timeline.TimelineSurface
import dev.sebastiano.spectre.testing.runSpectreTest
import java.awt.Point
import java.awt.event.KeyEvent
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import org.junit.Assume
import org.junit.Rule

/**
 * Deliverable 2 — Real human-physics 60 fps CFR Studio dogfooding video capture suite recorded via
 * native Spectre ScreenCaptureKit (`-Pciacola.sckRecording=true`, `window.isAutoRequestFocus = false`)
 * against isolated writable temporary copies of `decks/` so tracked `.deck.json` files stay untouched.
 *
 * Opt-in via `-Dciacola.dogfoodOutDir=<dir>` (or env `CIACOLA_DOGFOOD_OUT_DIR`) so normal
 * `:deck:spectreTest` invocations stay fast and zero-side-effect.
 */
class StudioDogfoodCaptureSpectreTest {

    @get:Rule
    val qa = QaStudioRule()

    private fun requireOutputDir(): Path {
        val raw = System.getProperty("ciacola.dogfoodOutDir")
            ?.takeIf { it.isNotBlank() }
            ?: System.getenv("CIACOLA_DOGFOOD_OUT_DIR")?.takeIf { it.isNotBlank() }
        Assume.assumeTrue(
            "Skipping Studio dogfood video capture (pass -Dciacola.dogfoodOutDir=<dir> to enable)",
            raw != null,
        )
        return Path.of(raw!!).also { Files.createDirectories(it) }
    }

    private fun cuesDir(outDir: Path): Path {
        val custom = System.getProperty("ciacola.dogfoodCuesDir")?.takeIf { it.isNotBlank() }
        return if (custom != null) Path.of(custom) else outDir.resolve(".cues")
    }

    @Test
    fun captureStaggerAndEditDogfood60fps(): Unit = runSpectreTest(timeout = 300.seconds) {
        val outDir = requireOutputDir()
        val outputMp4 = outDir.resolve("studio-dogfood-stagger-and-edit-60fps.mp4")
        val cueTsv = cuesDir(outDir).resolve("studio-dogfood-stagger-and-edit.cues.tsv")

        val writableDecks = createWritableDecksTempCopy("ciacola-dogfood-stagger-decks-")
        val storyCursor = HumanCursorState()
        val cueLog = StudioCueLog()

        try {
            val studio = qa.launchStudio(
                preset = DeckThemePreset.StageDark,
                windowSize = DpSize(1440.dp, 810.dp),
                windowPosition = WindowPosition(40.dp, 40.dp),
                timelineDocked = true,
                deckId = "foundation",
                decksDir = writableDecks,
                useDeckDefaultTheme = true,
                storyWindowOverlay = { win -> HumanPhysicsCursorOverlay(storyCursor, win) },
                worker = "dogfood-stagger",
            )

            with(studio) {
                settle()
                assertFalse(store.isReadOnly, "Writable temp decks copy must open in read-write mode")
                val driver = HumanPhysicsDriver(
                    automator = automator,
                    window = window,
                    input = inputFor(window),
                    cursor = storyCursor,
                )

                recordWindowMp4Cfr(
                    window = window,
                    outputMp4 = outputMp4,
                    fps = 60,
                    targetWidth = 1920,
                    targetHeight = 1080,
                    cueLog = cueLog,
                ) {
                    cueLog.cue("Open Ciacola Studio on Foundation deck (writable temp decks copy)")
                    delay(650L)

                    // 1. Switch to Element tab & direct stage selection + drag to reposition `s1:prose`
                    cueLog.cue("Open Element tab, direct stage select & drag s1:prose to reposition")
                    if (studioWorkspace.activeTab != StudioInspectorTab.Element) {
                        driver.clickNode("ciacola-studio-tab-element", postDelayMs = 240L)
                        settle()
                    }
                    val proseStageNode = driver.waitForNode("ciacola-doc-stage-node-s1:prose")
                    val proseBounds = checkNotNull(proseStageNode.boundsOnScreen)
                    val dragFrom = Point(proseBounds.x + proseBounds.width / 3, proseBounds.y + proseBounds.height / 2)
                    val dragTo = Point(dragFrom.x + 56, dragFrom.y + 34)
                    driver.click(dragFrom, postDelayMs = 240L)
                    settle()
                    assertEquals("s1:prose", elementController.selectedNodeId)

                    driver.drag(
                        from = dragFrom,
                        to = dragTo,
                        durationMs = 540L,
                        midPauseMs = 140L,
                        curved = false,
                        postDelayMs = 260L,
                    )
                    settle()

                    // 1b. Drag 8-point SE stage resize handle on `s1:prose` with live W × H dimension pill
                    cueLog.cue("Drag 8-point SE resize handle on s1:prose (live W × H pill)")
                    val widthBeforeResize = store.document.slides.first().nodes.first { it.id == "s1:prose" }.frame?.w ?: 0.0
                    val seHandle = driver.waitForNode("ciacola-stage-resize-handle-s1:prose-SE", timeout = 5.seconds)
                    val sePt = seHandle.centerOnScreen
                    driver.drag(
                        from = sePt,
                        to = Point(sePt.x - 68, sePt.y + 32),
                        durationMs = 520L,
                        midPauseMs = 160L,
                        curved = false,
                        postDelayMs = 260L,
                    )
                    settle()
                    val widthAfterResize = store.document.slides.first().nodes.first { it.id == "s1:prose" }.frame?.w ?: 0.0
                    assertTrue(widthAfterResize != widthBeforeResize, "Dragging SE handle must resize s1:prose width")

                    // 2. Edit text Size property live in DocumentElementInspectorUi
                    cueLog.cue("Edit s1:prose text size in DocumentElementInspectorUi")
                    if (studioWorkspace.activeTab != StudioInspectorTab.Element) {
                        driver.clickNode("ciacola-studio-tab-element", postDelayMs = 220L)
                        settle()
                    }
                    val sizeSlider = driver.scrollIntoView(
                        scrollContainerTag = "ciacola-doc-element-inspector",
                        targetTag = "ciacola-doc-text-size-slider",
                    )
                    val sizeBounds = checkNotNull(sizeSlider.boundsOnScreen)
                    driver.drag(
                        from = driver.sliderPointForValue(sizeBounds, 60f, 10f, 200f),
                        to = driver.sliderPointForValue(sizeBounds, 92f, 10f, 200f),
                        durationMs = 520L,
                        midPauseMs = 150L,
                        thenTo = driver.sliderPointForValue(sizeBounds, 74f, 10f, 200f),
                        thenDurationMs = 380L,
                        curved = false,
                        postDelayMs = 240L,
                    )
                    settle()

                    // 3. Double-click text directly on stage to inline-edit `s1:eyebrow`
                    cueLog.cue("Double-click s1:eyebrow on stage to open inline text editor")
                    val eyebrowNode = driver.waitForNode("ciacola-doc-stage-node-s1:eyebrow")
                    val eyebrowBounds = checkNotNull(eyebrowNode.boundsOnScreen)
                    val eyebrowTarget = Point(eyebrowBounds.x + 120, eyebrowBounds.y + eyebrowBounds.height / 2)
                    driver.doubleClick(eyebrowTarget, postDelayMs = 280L)
                    driver.waitForNode("ciacola-doc-inline-text-popover", timeout = 5.seconds)
                    settle()

                    cueLog.cue("Type inline edit into s1:eyebrow and commit")
                    driver.typeText(" - LIVE", charDelayMs = 64L, postDelayMs = 240L)
                    driver.clickNode("ciacola-doc-inline-text-commit", postDelayMs = 280L)
                    settle()

                    // 4. Open Motion Stagger Inspector on the `beats -> shared-move` Magic Move transition
                    cueLog.cue("Switch to Motion tab & select beats -> shared-move transition header")
                    driver.clickNode("ciacola-studio-tab-motion", postDelayMs = 200L)
                    settle()
                    driver.clickNode("ciacola-doc-timeline-fit-all", postDelayMs = 200L)
                    settle()
                    val transChip = driver.scrollHorizontallyIntoView(
                        scrollContainerTag = "ciacola-doc-timeline-headers-strip",
                        targetTag = "ciacola-transition-header-beats-shared-move",
                    )
                    driver.click(transChip.centerOnScreen, postDelayMs = 260L)
                    driver.waitForNode("ciacola-motion-stagger-inspector", timeout = 5.seconds)
                    settle()

                    // Adjust Stagger (ms) slider
                    cueLog.cue("Adjust transition Stagger (ms) slider (0 -> 95ms)")
                    val staggerSlider = driver.scrollIntoView(
                        scrollContainerTag = "ciacola-doc-timeline-inspector",
                        targetTag = "ciacola-transition-stagger-slider",
                    )
                    val staggerBounds = checkNotNull(staggerSlider.boundsOnScreen)
                    driver.drag(
                        from = driver.sliderPointForValue(staggerBounds, 0f, 0f, 300f),
                        to = driver.sliderPointForValue(staggerBounds, 95f, 0f, 300f),
                        durationMs = 560L,
                        midPauseMs = 120L,
                        curved = false,
                        postDelayMs = 240L,
                    )
                    settle()

                    // Toggle Stagger Order: Top -> Bottom, then Reading Order
                    cueLog.cue("Toggle Stagger Order: Top -> Bottom then Reading Order")
                    driver.clickNode("ciacola-transition-order-TopToBottom", postDelayMs = 340L)
                    settle()
                    driver.clickNode("ciacola-transition-order-ReadingOrder", postDelayMs = 320L)
                    settle()

                    // Drag Exit / Move / Enter phase bars horizontally
                    cueLog.cue("Drag Exit, Move, and Enter phase bars in Motion Stagger Inspector")
                    for ((phaseName, dxPx) in listOf("exit" to 28, "move" to 34, "enter" to -26)) {
                        val bar = driver.scrollIntoView(
                            scrollContainerTag = "ciacola-doc-timeline-inspector",
                            targetTag = "ciacola-transition-phase-$phaseName-bar",
                        )
                        val b = checkNotNull(bar.boundsOnScreen)
                        // Grab near the left/middle (avoid the 8dp right resize handle)
                        val fromPt = Point(b.x + (b.width * 0.35).roundToInt().coerceAtLeast(6), b.y + b.height / 2)
                        val toPt = Point(fromPt.x + dxPx, fromPt.y)
                        driver.drag(
                            from = fromPt,
                            to = toPt,
                            durationMs = 420L,
                            curved = false,
                            postDelayMs = 220L,
                        )
                        settle()
                    }

                    // 5. Drag a node track bar on the timeline canvas to set Manual stagger override
                    cueLog.cue("Drag timeline node track bar to set Manual stagger badge")
                    val visibleTracksWithBars = controller.snapshot.snapshot.tracks.filter { it.blocks.isNotEmpty() }
                    val manualPair = visibleTracksWithBars
                        .mapNotNull { row ->
                            val ref = controller.snapshot.trackRefsByTrackId[row.trackId] ?: return@mapNotNull null
                            row to ref
                        }
                        .filter { (_, ref) ->
                            ref is DocumentTimelineTrackRef.TransitionTrackBar &&
                                ref.fromSlideId == "beats" &&
                                ref.trackPlan.kind != TrackKind.TransitionSlide
                        }
                        .maxByOrNull { (row, _) -> row.blocks.first().durationMs }
                        ?: visibleTracksWithBars
                            .mapNotNull { row ->
                                val ref = controller.snapshot.trackRefsByTrackId[row.trackId] ?: return@mapNotNull null
                                row to ref
                            }
                            .filter { (_, ref) ->
                                ref is DocumentTimelineTrackRef.SlideBuildTrack && ref.isUnsequenced
                            }
                            .maxByOrNull { (row, _) -> row.blocks.first().durationMs }
                    assertNotNull(manualPair, "Expected at least one manual-stagger-eligible track with blocks on Foundation")
                    val (manualRow, manualCandidateRef) = manualPair
                    val manualTrackId = manualRow.trackId
                    val manualBlock = manualRow.blocks.first()
                    val manualRowNode = driver.scrollIntoView(
                        scrollContainerTag = "ciacola-doc-timeline-track-list",
                        targetTag = "ciacola-doc-track-row-$manualTrackId",
                        maxSteps = 60,
                    )
                    val manualRowBounds = checkNotNull(manualRowNode.boundsOnScreen)
                    val rulerForBar = driver.waitForNode("ciacola-doc-timeline-ruler")
                    val rulerBounds = checkNotNull(rulerForBar.boundsOnScreen)
                    val scale = pixelScale(window).toFloat()
                    val pxPerMs = controller.viewport.dpPerMs * scale
                    val scrollPx = controller.viewport.scrollX
                    // DocumentTrackRowItem draws bars inside the canvas Box aligned directly with rulerBounds.x
                    // (without TIMELINE_LEFT_PAD_DP), and reserves the rightmost 7.dp for the resize handle.
                    val barLeftPx = (manualBlock.startMs * pxPerMs - scrollPx).roundToInt().coerceAtLeast(0)
                    val barWidthPx = (manualBlock.durationMs * pxPerMs).roundToInt().coerceAtLeast(18)
                    val barLeftDp = (barLeftPx / scale).roundToInt()
                    val barWidthDp = (barWidthPx / scale).roundToInt().coerceAtLeast(10)
                    val barLeftX = rulerBounds.x + barLeftDp
                    val grabOffsetDp = minOf((barWidthDp * 0.35f).roundToInt(), (barWidthDp - 10).coerceAtLeast(2))
                        .coerceAtLeast(2)
                    val grabX = (barLeftX + grabOffsetDp).coerceIn(
                        rulerBounds.x + 2,
                        (rulerBounds.x + rulerBounds.width - 70).coerceAtLeast(rulerBounds.x + 2),
                    )
                    val manualStart = Point(grabX, manualRowBounds.y + manualRowBounds.height / 2)
                    driver.drag(
                        from = manualStart,
                        to = Point(manualStart.x + 56, manualStart.y),
                        durationMs = 520L,
                        midPauseMs = 140L,
                        curved = false,
                        postDelayMs = 280L,
                    )
                    settle()
                    val manualSaved = when (manualCandidateRef) {
                        is DocumentTimelineTrackRef.TransitionTrackBar ->
                            manualCandidateRef.nodeId in store.document.slides
                                .firstOrNull { it.id == manualCandidateRef.fromSlideId }
                                ?.transition?.stagger?.manual.orEmpty()
                        is DocumentTimelineTrackRef.SlideBuildTrack ->
                            manualCandidateRef.nodeId in store.document.slides
                                .firstOrNull { it.id == manualCandidateRef.slideId }
                                ?.beats?.getOrNull(manualCandidateRef.beatIndex)
                                ?.stagger?.manual.orEmpty()
                        else -> false
                    }
                    assertTrue(
                        manualSaved,
                        "Dragging track bar $manualTrackId (startMs=${manualBlock.startMs}, durMs=${manualBlock.durationMs}, " +
                            "barLeftDp=$barLeftDp, barWidthDp=$barWidthDp, grabX=$grabX, rulerX=${rulerBounds.x}) " +
                            "should persist Manual stagger override",
                    )

                    // 6. Select text morph track bar to expand PieceSchedule fragment ticks and drag individual word ticks
                    cueLog.cue("Select text morph track bar to expand word PieceSchedule fragment ticks")
                    val wordMorphRef = controller.snapshot.trackRefsByTrackId.values.firstOrNull { ref ->
                        controller.fragmentSlotsFor(ref).size >= 2
                    }
                    assertNotNull(wordMorphRef, "Expected text morph track with >=2 PieceSchedule slots on Foundation")
                    val wordTrackRow = driver.scrollIntoView(
                        scrollContainerTag = "ciacola-doc-timeline-track-list",
                        targetTag = "ciacola-doc-track-row-${wordMorphRef.trackId}",
                    )
                    val wordRowBounds = checkNotNull(wordTrackRow.boundsOnScreen)
                    driver.click(Point(wordRowBounds.x + 88, wordRowBounds.y + wordRowBounds.height / 2), postDelayMs = 260L)
                    settle()

                    val slots = controller.fragmentSlotsFor(wordMorphRef)
                    assertTrue(slots.size >= 2, "Expected >=2 word fragment slots, got ${slots.size}")
                    driver.scrollIntoView(
                        scrollContainerTag = "ciacola-doc-timeline-inspector",
                        targetTag = "ciacola-intra-text-stagger-editor",
                    )
                    cueLog.cue("Drag individual word PieceSchedule fragment ticks (${slots[0].address}, ${slots[1].address})")
                    for ((slot, dragDx) in listOf(slots[0] to 46, slots[1] to 62)) {
                        val tick = driver.scrollIntoView(
                            scrollContainerTag = "ciacola-doc-timeline-inspector",
                            targetTag = "ciacola-fragment-tick-${slot.address}",
                        )
                        val tb = checkNotNull(tick.boundsOnScreen)
                        val tickFrom = Point(tb.x + tb.width / 2, tb.y + tb.height / 2)
                        driver.drag(
                            from = tickFrom,
                            to = Point(tickFrom.x + dragDx, tickFrom.y),
                            durationMs = 460L,
                            midPauseMs = 120L,
                            curved = false,
                            postDelayMs = 240L,
                        )
                        settle()
                    }

                    // 7. Scrub back and forth across the staggered Magic Move along the timeline ruler
                    cueLog.cue("Scrub back and forth across staggered Magic Move transition")
                    val ruler = driver.waitForNode("ciacola-doc-timeline-ruler")
                    val transHeader = controller.snapshot.transitionHeader("beats", "shared-move")
                    val transStartMs = (transHeader?.startMs ?: 1200) - 220
                    val transEndMs = ((transHeader?.startMs ?: 1200) + (transHeader?.segmentMs ?: 800)) + 220
                    val pStart = rulerPoint(ruler, transStartMs.coerceAtLeast(0), window, TimelineSurface.Docked)
                    val pEnd = rulerPoint(ruler, transEndMs, window, TimelineSurface.Docked)
                    val pMid = rulerPoint(ruler, (transStartMs + transEndMs) / 2, window, TimelineSurface.Docked)
                    driver.drag(
                        from = pStart,
                        to = pEnd,
                        durationMs = 950L,
                        midPauseMs = 140L,
                        thenTo = pStart,
                        thenDurationMs = 820L,
                        curved = false,
                        postDelayMs = 220L,
                    )
                    driver.drag(
                        from = pStart,
                        to = pMid,
                        durationMs = 520L,
                        curved = false,
                        postDelayMs = 260L,
                    )
                    settle()

                    // 8. Exercise unified ⌘Z Undo & ⇧⌘Z Redo stack
                    cueLog.cue("Unified Undo (⌘Z x3) and Redo (⇧⌘Z x2)")
                    val revBeforeUndo = store.revision
                    repeat(3) {
                        driver.pressShortcut("⌘Z Undo", KeyEvent.VK_Z, listOf(KeyEvent.VK_META), postDelayMs = 340L)
                        settle()
                    }
                    assertTrue(store.revision != revBeforeUndo || store.canRedo, "⌘Z must undo on unified stack")
                    repeat(2) {
                        driver.pressShortcut(
                            "⇧⌘Z Redo",
                            KeyEvent.VK_Z,
                            listOf(KeyEvent.VK_META, KeyEvent.VK_SHIFT),
                            postDelayMs = 340L,
                        )
                        settle()
                    }

                    // 9. Switch to Shaders Quickie 2025: character-level PieceSchedule ticks + live SkSL shader uniform editing!
                    cueLog.cue("Switch deck to Shaders Quickie 2025")
                    driver.clickNode("ciacola-deck-switcher", postDelayMs = 260L)
                    driver.clickNode("ciacola-deck-option-shaders-quickie-2025", postDelayMs = 520L)
                    automator.waitUntil(description = "shaders-quickie-2025 loaded", timeout = 10.seconds) {
                        activeDeck.id == "shaders-quickie-2025"
                    }
                    settle()

                    // Expand character-level PieceSchedule ticks on S01 `"awesome"` dissolve!
                    val charMorphRef = controller.snapshot.trackRefsByTrackId.values.firstOrNull { ref ->
                        ref is DocumentTimelineTrackRef.SlideBuildTrack &&
                            ref.slideId == "s01" &&
                            controller.fragmentSlotsFor(ref).size >= 3
                    }
                    if (charMorphRef != null) {
                        cueLog.cue("Select S01 'awesome' character-dissolve track & drag character PieceSchedule tick")
                        val charRow = driver.scrollIntoView(
                            scrollContainerTag = "ciacola-doc-timeline-track-list",
                            targetTag = "ciacola-doc-track-row-${charMorphRef.trackId}",
                        )
                        val crb = checkNotNull(charRow.boundsOnScreen)
                        driver.click(Point(crb.x + 92, crb.y + crb.height / 2), postDelayMs = 260L)
                        settle()
                        val charSlots = controller.fragmentSlotsFor(charMorphRef)
                        val pickSlot = charSlots[minOf(2, charSlots.lastIndex)]
                        val charTick = driver.scrollIntoView(
                            scrollContainerTag = "ciacola-doc-timeline-inspector",
                            targetTag = "ciacola-fragment-tick-${pickSlot.address}",
                        )
                        val ctb = checkNotNull(charTick.boundsOnScreen)
                        val ctFrom = Point(ctb.x + ctb.width / 2, ctb.y + ctb.height / 2)
                        driver.drag(
                            from = ctFrom,
                            to = Point(ctFrom.x + 54, ctFrom.y),
                            durationMs = 480L,
                            midPauseMs = 120L,
                            curved = false,
                            postDelayMs = 240L,
                        )
                        settle()
                    }

                    // Navigate to Slide 5 (`s05` Trippy Kaleidoscope live SkSL shader), select specimen & drag Folds uniform slider!
                    cueLog.cue("Jump to Slide 5 (Trippy Kaleidoscope SkSL shader) & select specimen")
                    restOnBeat(sceneIndex = 4, frameIndex = 0)
                    delay(300L)
                    driver.clickNode("ciacola-studio-tab-element", postDelayMs = 220L)
                    settle()

                    // Alt+Click directly on the interactive shader specimen on stage (fallback to node picker chip)
                    val shaderStageNode = automator.findOneByTestTag("ciacola-doc-stage-node-specimen-trippy-kaleidoscope")
                    if (shaderStageNode != null) {
                        driver.clickWithModifiers(
                            target = shaderStageNode.centerOnScreen,
                            modifiers = listOf(KeyEvent.VK_ALT),
                            badgeLabel = "⌥ Click Select Specimen",
                            postDelayMs = 260L,
                        )
                        settle()
                    }
                    if (elementController.selectedNodeId != "specimen-trippy-kaleidoscope") {
                        driver.clickNode("ciacola-doc-node-item-specimen-trippy-kaleidoscope", postDelayMs = 240L)
                        settle()
                    }
                    assertEquals("specimen-trippy-kaleidoscope", elementController.selectedNodeId)

                    cueLog.cue("Drag live SkSL 'Folds' uniform slider (6 -> 11 -> 4 -> 8)")
                    val foldsSlider = driver.scrollIntoView(
                        scrollContainerTag = "ciacola-doc-element-inspector",
                        targetTag = "ciacola-specimen-param-folds-slider",
                    )
                    val foldsBounds = checkNotNull(foldsSlider.boundsOnScreen)
                    driver.drag(
                        from = driver.sliderPointForValue(foldsBounds, 6f, 3f, 12f),
                        to = driver.sliderPointForValue(foldsBounds, 11f, 3f, 12f),
                        durationMs = 620L,
                        midPauseMs = 220L,
                        thenTo = driver.sliderPointForValue(foldsBounds, 4f, 3f, 12f),
                        thenDurationMs = 640L,
                        curved = false,
                        postDelayMs = 220L,
                    )
                    driver.drag(
                        from = driver.sliderPointForValue(foldsBounds, 4f, 3f, 12f),
                        to = driver.sliderPointForValue(foldsBounds, 8f, 3f, 12f),
                        durationMs = 460L,
                        curved = false,
                        postDelayMs = 280L,
                    )
                    settle()

                    cueLog.cue("Undo & Redo live SkSL uniform change (⌘Z / ⇧⌘Z)")
                    driver.pressShortcut("⌘Z Undo", KeyEvent.VK_Z, listOf(KeyEvent.VK_META), postDelayMs = 420L)
                    settle()
                    driver.pressShortcut(
                        "⇧⌘Z Redo",
                        KeyEvent.VK_Z,
                        listOf(KeyEvent.VK_META, KeyEvent.VK_SHIFT),
                        postDelayMs = 520L,
                    )
                    settle()
                    cueLog.cue("End of Stagger & Edit dogfooding reel")
                    delay(450L)
                }

                cueLog.writeTsv(cueTsv)
            }
        } finally {
            runCatching { writableDecks.toFile().deleteRecursively() }
        }
    }

    @Test
    fun captureScale4DecksDogfood60fps(): Unit = runSpectreTest(timeout = 360.seconds) {
        val outDir = requireOutputDir()
        val outputMp4 = outDir.resolve("studio-dogfood-scale-4decks-60fps.mp4")
        val partAMp4 = outDir.resolve(".scale-part-a-story.mp4")
        val partBMp4 = outDir.resolve(".scale-part-b-presenter.mp4")
        val cueTsv = cuesDir(outDir).resolve("studio-dogfood-scale-4decks.cues.tsv")

        val writableDecks = createWritableDecksTempCopy("ciacola-dogfood-scale-decks-")
        val storyCursor = HumanCursorState()
        val presenterCursor = HumanCursorState()
        val cueLog = StudioCueLog()

        try {
            val studio = qa.launchStudio(
                preset = DeckThemePreset.StageDark,
                windowSize = DpSize(1440.dp, 810.dp),
                windowPosition = WindowPosition(40.dp, 40.dp),
                presenterWindowSize = DpSize(1440.dp, 810.dp),
                presenterWindowPosition = WindowPosition(40.dp, 40.dp),
                timelineDocked = true,
                deckId = "foundation",
                decksDir = writableDecks,
                useDeckDefaultTheme = true,
                storyWindowOverlay = { win -> HumanPhysicsCursorOverlay(storyCursor, win) },
                presenterWindowOverlay = { win -> HumanPhysicsCursorOverlay(presenterCursor, win) },
                worker = "dogfood-scale",
            )

            with(studio) {
                settle()
                val storyDriver = HumanPhysicsDriver(
                    automator = automator,
                    window = window,
                    input = inputFor(window),
                    cursor = storyCursor,
                )

                // PART A: Story Studio window across all 4 decks -> Fit -> Minimap -> track scroll -> Esc Overview -> 2D Grid -> ⌘K 125 -> F8 Linter -> F2 Presenter
                val partADurationSec = recordWindowMp4Cfr(
                    window = window,
                    outputMp4 = partAMp4,
                    fps = 60,
                    targetWidth = 1920,
                    targetHeight = 1080,
                    cueLog = cueLog,
                ) {
                    cueLog.cue("Deck 1/4: Foundation (5 slides)")
                    delay(750L)

                    // Switch to Shaders Quickie 2025
                    cueLog.cue("Switch to Deck 2/4: Shaders Quickie 2025 (48 slides)")
                    storyDriver.clickNode("ciacola-deck-switcher", postDelayMs = 240L)
                    storyDriver.clickNode("ciacola-deck-option-shaders-quickie-2025", postDelayMs = 450L)
                    automator.waitUntil(description = "shaders-quickie-2025 loaded", timeout = 10.seconds) {
                        activeDeck.id == "shaders-quickie-2025"
                    }
                    settle()
                    delay(650L)

                    // Switch to Jewel Talk 2024 (75 slides)
                    cueLog.cue("Switch to Deck 3/4: Jewel Talk 2024 (75 slides)")
                    storyDriver.clickNode("ciacola-deck-switcher", postDelayMs = 240L)
                    storyDriver.clickNode("ciacola-deck-option-jewel-talk-2024", postDelayMs = 480L)
                    automator.waitUntil(description = "jewel-talk-2024 loaded", timeout = 12.seconds) {
                        activeDeck.id == "jewel-talk-2024"
                    }
                    settle()

                    cueLog.cue("Jewel Talk 2024: Switch scope to 'All Slides' & click 'Fit'")
                    storyDriver.clickNode("ciacola-timeline-filter-all", postDelayMs = 240L)
                    settle()
                    storyDriver.clickNode("ciacola-doc-timeline-fit-all", postDelayMs = 340L)
                    settle()

                    cueLog.cue("Jewel Talk 2024: Drag & click across 75-slide Slide Minimap strip")
                    val jewelMinimap = storyDriver.waitForNode("ciacola-doc-timeline-minimap", timeout = 6.seconds)
                    val jmb = checkNotNull(jewelMinimap.boundsOnScreen)
                    storyDriver.drag(
                        from = Point(jmb.x + (jmb.width * 0.08).roundToInt(), jmb.y + jmb.height / 2),
                        to = Point(jmb.x + (jmb.width * 0.56).roundToInt(), jmb.y + jmb.height / 2),
                        durationMs = 920L,
                        midPauseMs = 180L,
                        thenTo = Point(jmb.x + (jmb.width * 0.34).roundToInt(), jmb.y + jmb.height / 2),
                        thenDurationMs = 560L,
                        curved = false,
                        postDelayMs = 280L,
                    )
                    settle()

                    // Switch to Not Just Plain Text 2024 (136 slides)
                    cueLog.cue("Switch to Deck 4/4: Not Just Plain Text 2024 (136 slides)")
                    storyDriver.clickNode("ciacola-deck-switcher", postDelayMs = 240L)
                    storyDriver.clickNode("ciacola-deck-option-not-just-plain-text-2024", postDelayMs = 600L)
                    automator.waitUntil(description = "not-just-plain-text-2024 loaded", timeout = 15.seconds) {
                        activeDeck.id == "not-just-plain-text-2024"
                    }
                    settle()

                    val totalTracks = controller.snapshot.snapshot.tracks.size
                    assertTrue(totalTracks >= 300, "Expected hundreds of tracks on 136-slide NJPT deck, got $totalTracks")

                    cueLog.cue("Not Just Plain Text 2024: Click 'All Slides' + 'Fit' ($totalTracks tracks across 136 slides)")
                    if (controller.trackFilterMode.name != "AllSlides") {
                        storyDriver.clickNode("ciacola-timeline-filter-all", postDelayMs = 240L)
                        settle()
                    }
                    storyDriver.clickNode("ciacola-doc-timeline-fit-all", postDelayMs = 360L)
                    settle()

                    cueLog.cue("Not Just Plain Text 2024: Scrub & click across 136-slide Slide Minimap strip")
                    val njptMinimap = storyDriver.waitForNode("ciacola-doc-timeline-minimap", timeout = 6.seconds)
                    val nmb = checkNotNull(njptMinimap.boundsOnScreen)
                    storyDriver.drag(
                        from = Point(nmb.x + (nmb.width * 0.06).roundToInt(), nmb.y + nmb.height / 2),
                        to = Point(nmb.x + (nmb.width * 0.62).roundToInt(), nmb.y + nmb.height / 2),
                        durationMs = 1080L,
                        midPauseMs = 180L,
                        curved = false,
                        postDelayMs = 220L,
                    )
                    storyDriver.click(
                        Point(nmb.x + (nmb.width * 0.36).roundToInt(), nmb.y + nmb.height / 2),
                        postDelayMs = 260L,
                    )
                    settle()

                    cueLog.cue("Scroll smoothly down virtualized $totalTracks-track All Slides header list")
                    val trackList = storyDriver.waitForNode("ciacola-doc-timeline-track-list")
                    val tlb = checkNotNull(trackList.boundsOnScreen)
                    val trackHeaderScrollPt = Point(tlb.x + 115, tlb.y + tlb.height / 2)
                    storyDriver.wheelScroll(
                        at = trackHeaderScrollPt,
                        notchesPerStep = 4,
                        steps = 36,
                        stepDelayMs = 28L,
                        postDelayMs = 240L,
                    )
                    storyDriver.wheelScroll(
                        at = trackHeaderScrollPt,
                        notchesPerStep = -4,
                        steps = 16,
                        stepDelayMs = 28L,
                        postDelayMs = 240L,
                    )
                    settle()

                    // Open Overview via Esc -> Toggle 2D Compact Grid -> ⌘K Quick Jump to Slide 125 (`njpt.jewelMarkdownSplitDemo`)
                    cueLog.cue("Focus stage viewport & press Esc for 136-slide Overview")
                    if (studioWorkspace.activeTab != StudioInspectorTab.Motion) {
                        storyDriver.clickNode("ciacola-studio-tab-motion", postDelayMs = 180L)
                    }
                    val stageViewport = storyDriver.waitForNode("ciacola-preview-viewport")
                    val svb = checkNotNull(stageViewport.boundsOnScreen)
                    storyDriver.click(Point(svb.x + svb.width / 2, svb.y + svb.height / 2), postDelayMs = 200L)
                    settle()
                    storyDriver.pressShortcut("Esc Overview", KeyEvent.VK_ESCAPE, postDelayMs = 480L)
                    storyDriver.waitForNode("easel-overview-grid", timeout = 6.seconds)
                    settle()

                    cueLog.cue("Exercise [ ▤ Strip | ⊞ Grid ] segmented toggle in 136-slide Overview")
                    storyDriver.clickNode("easel-overview-strip-toggle", postDelayMs = 520L)
                    settle()
                    storyDriver.clickNode("easel-overview-grid-toggle", postDelayMs = 620L)
                    storyDriver.waitForNode("easel-overview-grid", timeout = 6.seconds)
                    settle()

                    cueLog.cue("Open ⌘K Quick Jump, type '125' & Enter to leap to njpt.jewelMarkdownSplitDemo")
                    storyDriver.pressShortcut(
                        "⌘K Quick Jump",
                        KeyEvent.VK_K,
                        listOf(KeyEvent.VK_META),
                        postDelayMs = 280L,
                    )
                    val qjInput = automator.findOneByTestTag("easel-overview-quick-jump-input")
                        ?: run {
                            storyDriver.clickNode("easel-overview-quick-jump-trigger", postDelayMs = 240L)
                            storyDriver.waitForNode("easel-overview-quick-jump-input", timeout = 5.seconds)
                        }
                    storyDriver.click(qjInput.centerOnScreen, postDelayMs = 160L)
                    storyDriver.typeText("125", charDelayMs = 95L, postDelayMs = 420L)
                    storyDriver.pressShortcut("↵ Jump to S125", KeyEvent.VK_ENTER, postDelayMs = 520L)

                    automator.waitUntil(description = "jumped to slide 125 (index 124)", timeout = 8.seconds) {
                        animatic.currentIndex.sceneIndex == 124
                    }
                    settle()
                    delay(450L)

                    // Open Linter via F8 and run full-deck 136-slide audit
                    cueLog.cue("Press F8 to open Deck Linter & run full 136-slide Audit All")
                    storyDriver.pressShortcut("F8 Deck Linter", KeyEvent.VK_F8, postDelayMs = 340L)
                    storyDriver.waitForNode("ciacola-doc-lint-panel", timeout = 6.seconds)
                    settle()
                    // Before the first deck check the empty state owns the single `Check deck` button.
                    val auditTag = if (automator.findOneByTestTag("ciacola-doc-lint-audit-all") != null) {
                        "ciacola-doc-lint-audit-all"
                    } else {
                        "ciacola-doc-lint-check-deck"
                    }
                    storyDriver.clickNode(auditTag, postDelayMs = 450L)
                    automator.waitUntil(description = "full-deck lint audit completed", timeout = 15.seconds) {
                        !lintController.isAuditing
                    }
                    settle()
                    delay(500L)

                    // Smoothly scroll the lint results list if populated
                    automator.findOneByTestTag("ciacola-doc-lint-list")?.let { lintList ->
                        cueLog.cue("Scroll full-deck lint audit results")
                        storyDriver.wheelScroll(
                            at = lintList.centerOnScreen,
                            notchesPerStep = 3,
                            steps = 12,
                            stepDelayMs = 32L,
                            postDelayMs = 280L,
                        )
                    }

                    // Open Presenter View via F2
                    cueLog.cue("Press F2 to launch Presenter View")
                    storyDriver.pressShortcut("F2 Presenter View", KeyEvent.VK_F2, postDelayMs = 480L)
                    automator.waitUntil(description = "presenter window showing", timeout = 10.seconds) {
                        val pw = presenterComposeWindow
                        pw != null && pw.isShowing && findSkiaLayer(pw) != null
                    }
                    settle()
                    delay(350L)
                }

                // PART B: Capture Presenter View window (`Ciacola - Presenter`) at identical 1920x1080 60fps CFR
                val presenterWin = checkNotNull(presenterComposeWindow) { "Presenter window did not open on F2" }
                val presenterDriver = HumanPhysicsDriver(
                    automator = automator,
                    window = presenterWin,
                    input = inputFor(presenterWin),
                    cursor = presenterCursor,
                )

                val partBCueLog = StudioCueLog()
                recordWindowMp4Cfr(
                    window = presenterWin,
                    outputMp4 = partBMp4,
                    fps = 60,
                    targetWidth = 1920,
                    targetHeight = 1080,
                    cueLog = partBCueLog,
                ) {
                    partBCueLog.cue("Presenter View active on Slide 125 (njpt.jewelMarkdownSplitDemo)")
                    val currentPreview = presenterDriver.waitForNode("ciacola-presenter-current-preview", timeout = 6.seconds)
                    presenterDriver.moveTo(currentPreview.centerOnScreen, durationMs = 420L, curved = true)
                    delay(650L)

                    val nextPreview = presenterDriver.waitForNode("ciacola-presenter-next-preview", timeout = 6.seconds)
                    presenterDriver.moveTo(nextPreview.centerOnScreen, durationMs = 480L, curved = true)
                    delay(350L)

                    // Advance smoothly from Slide 125 through to Slide 128 (`njpt.geminiDarkMarkdownChat`)
                    // by clicking the interactive Next Slide preview card in Presenter View!
                    partBCueLog.cue("Click Next Slide card in Presenter View to advance to Slide 128 (njpt.geminiDarkMarkdownChat)")
                    var steps = 0
                    while (animatic.currentIndex.sceneIndex < 127 && steps < 10) {
                        val card = presenterDriver.waitForNode("ciacola-presenter-next-preview", timeout = 5.seconds)
                        presenterDriver.click(card.centerOnScreen, postDelayMs = 760L)
                        settle()
                        steps++
                    }
                    assertEquals(
                        127,
                        animatic.currentIndex.sceneIndex,
                        "Presenter View should advance to Slide 128 (index 127, njpt.geminiDarkMarkdownChat)",
                    )
                    partBCueLog.cue("Presenter View active on Slide 128 (njpt.geminiDarkMarkdownChat)")
                    presenterDriver.moveTo(currentPreview.centerOnScreen, durationMs = 440L, curved = true)
                    delay(950L)
                }
                cueLog.appendShifted(partBCueLog, offsetSec = partADurationSec)

                // Stitch Story + Presenter into one seamless 1920x1080 60fps CFR MP4
                concatMp4SegmentsCfr(
                    segments = listOf(partAMp4, partBMp4),
                    outputMp4 = outputMp4,
                    fps = 60,
                )
                runCatching { Files.deleteIfExists(partAMp4) }
                runCatching { Files.deleteIfExists(partBMp4) }
                cueLog.writeTsv(cueTsv)
            }
        } finally {
            runCatching { Files.deleteIfExists(partAMp4) }
            runCatching { Files.deleteIfExists(partBMp4) }
            runCatching { writableDecks.toFile().deleteRecursively() }
        }
    }
}
