package dev.sebastiano.ciacola.timeline

import dev.sebastiano.ciacola.qa.QaStudioRule
import dev.sebastiano.ciacola.qa.differingFraction
import dev.sebastiano.spectre.testing.runSpectreTest
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.image.BufferedImage
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.junit.Rule

/**
 * Real-window end-to-end QA of document-backed Ciacola Studio (Slide Stage + Ctrl-Click Review +
 * Analytical Document Timeline) driven by Spectre on JetBrains Runtime 25.
 */
class CiacolaStudioSpectreTest {

    @get:Rule
    val qa = QaStudioRule()

    private fun BufferedImage.crop(bottomPx: Int): BufferedImage =
        getSubimage(0, 0, width, bottomPx.coerceIn(1, height))

    @Test
    fun scrubbingWithTheMouseForwardAcrossABeatAndBackShowsTheRightTransition(): Unit =
        runSpectreTest(timeout = 120.seconds) {
            val studio = qa.launchStudio(timelineDocked = false)
            with(studio) {
                val ruler = openTimeline()
                val snapshot = controller.snapshot.snapshot
                val seg0 = snapshot.stateSpans.first { !it.isHold && it.durationMs > 0 }
                val seg1 = snapshot.stateSpans.filter { !it.isHold && it.durationMs > 0 }[1]
                val y = rulerPoint(ruler, 0).y

                // Drag the playhead from beat 0 well into the second transition, then back into the
                // middle of the first one.
                automator.swipe(
                    startX = rulerPoint(ruler, 80).x,
                    startY = y,
                    endX = rulerPoint(ruler, seg1.startMs + seg1.durationMs / 2).x,
                    endY = y,
                    steps = 24,
                    duration = 480.milliseconds,
                )
                automator.swipe(
                    startX = rulerPoint(ruler, seg1.startMs + seg1.durationMs / 2).x,
                    startY = y,
                    endX = rulerPoint(ruler, seg0.startMs + seg0.durationMs / 2).x,
                    endY = y,
                    steps = 24,
                    duration = 480.milliseconds,
                )
                settle()
                val landed = controller.playheadMs
                assertTrue(
                    landed in seg0.startMs..(seg0.startMs + seg0.durationMs),
                    "Playhead in segment 0 (got ${landed}ms, expected ${seg0.startMs}..${seg0.startMs + seg0.durationMs})",
                )
                assertEquals(seg0.stateIndex, animatic.activeFromStateIndex, "Stage shows the first transition")
                assertEquals(seg0.stateIndex + 1, animatic.activeToStateIndex)
                assertTrue(
                    automator.findByText("reversible", false).isEmpty(),
                    "Third beat's words must not be on stage while scrubbing the first transition",
                )
                assertTrue(automator.findByText("advances", false).isNotEmpty(), "Second beat's words morph in")
                capture("01-scrub-back-into-first-transition.png")

                // The frame must equal a scrub that reaches the same time from the other end.
                automator.moveTo(rulerPoint(ruler, 0).x, (y - 160).coerceAtLeast(16))
                settle()
                val studioTop = checkNotNull(automator.findOneByTestTag("ciacola-timeline-studio")).boundsInWindow.top.toInt()
                val viaDrag = screenshot().crop(studioTop)
                onUi { controller.seekToMs(snapshot.totalDurationMs) }
                settle()
                onUi { controller.seekToMs(landed) }
                settle()
                val viaJump = screenshot().crop(studioTop)
                val diff = differingFraction(viaDrag, viaJump)
                assertTrue(diff < 0.005, "Stage after drag differs from a direct scrub to ${landed}ms: ${"%.4f".format(diff)}")
            }
        }

    @Test
    fun draggingThePlayheadAcrossSlidesUpdatesTheStageDuringTheDrag(): Unit =
        runSpectreTest(timeout = 120.seconds) {
            val studio = qa.launchStudio(timelineDocked = false)
            with(studio) {
                val ruler = openTimeline()
                val spans = controller.snapshot.snapshot.stateSpans
                // From slide 2 resting on the expanded shared card, across slide 3, into slide 4.
                val startMs = spans.last { it.sceneIndex == 1 && it.isHold }.startMs + 40
                val slide4 = spans.first { it.sceneIndex == 3 && it.isHold }
                val slide4Ms = slide4.startMs..(slide4.startMs + slide4.durationMs)
                val endMs = slide4.startMs + slide4.durationMs / 2
                val y = rulerPoint(ruler, 0).y
                val from = rulerPoint(ruler, startMs)
                val to = rulerPoint(ruler, endMs)
                val studioTop = checkNotNull(automator.findOneByTestTag("ciacola-timeline-studio")).boundsInWindow.top.toInt()
                val storyIn = inputFor(window)

                storyIn.mouseMove(from.x, y)
                delay(30)
                storyIn.mousePress(InputEvent.BUTTON1_DOWN_MASK)
                delay(600)
                val slide2 = screenshot().crop(studioTop)

                val dragging = AtomicBoolean(true)
                val mover = thread(name = "spectre-playhead-drag", isDaemon = true) {
                    val steps = 40
                    for (i in 1..steps) {
                        storyIn.mouseMove(from.x + (to.x - from.x) * i / steps, y)
                        Thread.sleep(16)
                    }
                    var i = 0
                    while (dragging.get()) {
                        storyIn.mouseMove(to.x + if (i++ % 2 == 0) 6 else -6, y)
                        Thread.sleep(16)
                    }
                }
                try {
                    automator.waitUntil(description = "playhead in slide 4 mid-drag", timeout = 5.seconds) {
                        controller.playheadMs in slide4Ms
                    }
                    delay(400)
                    val midDrag = screenshot().crop(studioTop)
                    assertTrue(controller.playheadMs in slide4Ms, "Still dragging inside slide 4")
                    captureDir?.let { ImageIO.write(midDrag, "png", File(it, "11-mid-drag-slide-4.png")) }
                    val diff = differingFraction(midDrag, slide2)
                    assertTrue(diff > 0.05, "Stage still shows the slide-2 frame mid-drag (only ${"%.4f".format(diff)} of pixels changed)")
                } finally {
                    dragging.set(false)
                    mover.join(2_000)
                    storyIn.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
                }
                delay(300)
            }
        }

    @Test
    fun longBackAndForthScrubAcrossEverySlideWithScreenshotsStaysAliveAndLandsOnTheRightFrame(): Unit =
        runSpectreTest(timeout = 180.seconds) {
            val studio = qa.launchStudio(timelineDocked = false)
            with(studio) {
                val ruler = openTimeline()
                val total = controller.snapshot.snapshot.totalDurationMs
                val y = rulerPoint(ruler, 0).y
                val left = rulerPoint(ruler, 0).x
                val right = rulerPoint(ruler, total).x
                val storyIn = inputFor(window)
                val shots = AtomicInteger()
                val dragging = AtomicBoolean(true)
                val shooter = launch {
                    while (dragging.get()) {
                        screenshot()
                        shots.incrementAndGet()
                    }
                }
                storyIn.mouseMove(left, y)
                delay(30)
                storyIn.mousePress(InputEvent.BUTTON1_DOWN_MASK)
                try {
                    val deadline = System.currentTimeMillis() + 18_000
                    var i = 0
                    while (System.currentTimeMillis() < deadline) {
                        val phase = (i % 120).let { if (it < 60) it else 120 - it }
                        val jitter = if (i % 7 == 0) -9 else 0
                        storyIn.mouseMove(left + (right - left) * phase / 60 + jitter, y)
                        delay(16)
                        i++
                    }
                } finally {
                    storyIn.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
                    dragging.set(false)
                    shooter.join()
                }
                automator.moveTo(left, (y - 160).coerceAtLeast(16))
                settle()
                delay(300)
                assertTrue(shots.get() > 25, "Screenshots kept running during the drag (${shots.get()})")
                val studioTop = checkNotNull(automator.findOneByTestTag("ciacola-timeline-studio")).boundsInWindow.top.toInt()
                val landed = controller.playheadMs
                val viaDrag = screenshot().crop(studioTop)
                onUi { controller.seekToMs(if (landed > total / 2) 0 else total) }
                settle()
                onUi { controller.seekToMs(landed) }
                settle()
                delay(300)
                val viaJump = screenshot().crop(studioTop)
                val diff = differingFraction(viaDrag, viaJump)
                assertTrue(diff < 0.005, "Stage after a long scrub differs from a direct scrub to ${landed}ms: ${"%.4f".format(diff)}")
            }
        }

    @Test
    fun beatsLoopsAndReviewComposerWorkWithTheTimelineOpen(): Unit =
        runSpectreTest(timeout = 120.seconds) {
            val studio = qa.launchStudio(timelineDocked = false)
            with(studio) {
                openTimeline()
                capture("08-master-timeline.png")

                // 0-ms "+" insert beat & right-click "Delete Beat" structural beat editing on DeckDocumentStore.
                assertEquals(3, store.document.slides[0].beats.size)
                automator.click(automator.waitForNode(tag = "ciacola-doc-timeline-insert-beat", timeout = 5.seconds))
                automator.waitUntil(description = "Slide 1 beat count 4", timeout = 5.seconds) {
                    store.document.slides[0].beats.size == 4
                }
                val addedBeatTick = automator.waitForNode(tag = "ciacola-header-beat-beats-3", timeout = 5.seconds)
                awtClick(addedBeatTick.centerOnScreen, buttonMask = InputEvent.BUTTON3_DOWN_MASK)
                automator.click(automator.waitForNode(text = "Delete Beat", timeout = 5.seconds))
                automator.waitUntil(description = "Slide 1 beat count 3", timeout = 5.seconds) {
                    store.document.slides[0].beats.size == 3
                }

                // Select the ambient mesh loop track, verify its inspector mounts, and adjust its period.
                onUi { controller.selectLoop("foundation.ambientMesh/time") }
                settle()
                onUi { studioWorkspace.activeTab = dev.sebastiano.ciacola.studio.StudioInspectorTab.Animate }
                settle()
                automator.waitForNode(tag = "ciacola-loop-period-slider-foundation.ambientMesh/time", timeout = 5.seconds)
                onUi { controller.setLoopPeriodMs("foundation.ambientMesh/time", 6_500L) }
                settle()
                assertEquals(6_500L, store.document.loopPeriodsMs["foundation.ambientMesh/time"])
                capture("09-loop-period-inspector.png")

                // Zoom buttons on the document timeline toolbar work cleanly.
                val zoomBeforeClick = controller.viewport.pxPerMs
                automator.click(automator.waitForNode(tag = "ciacola-doc-timeline-zoom-in", timeout = 5.seconds))
                settle()
                assertTrue(controller.viewport.pxPerMs > zoomBeforeClick, "Zoom-in button zooms timeline")
                automator.click(automator.waitForNode(tag = "ciacola-doc-timeline-fit-all", timeout = 5.seconds))
                settle()

                // Review composer: typing (with spaces and shortcut keys) is not eaten by timeline shortcuts; Escape closes.
                restOnBeat(sceneIndex = 0, frameIndex = 0)
                automator.pressKey(KeyEvent.VK_F3)
                automator.waitUntil(description = "F3 enables inspect mode", timeout = 5.seconds) { review.inspectModeEnabled }
                automator.click(automator.waitForNode(tag = "ciacola-review-stage", timeout = 5.seconds))
                val noteInput = automator.waitForNode(tag = "ciacola-review-note-input", timeout = 5.seconds)
                automator.click(noteInput)
                val zoomBefore = controller.viewport.pxPerMs
                automator.typeText("Spectre live typing check = ; .")
                settle()
                val typed = automator.findOneByTestTag("ciacola-review-note-input")?.let { it.editableText ?: it.text }.orEmpty()
                assertTrue(typed.contains("Spectre live typing check = ; ."), "Composer receives every key, got '$typed'")
                assertEquals(zoomBefore, controller.viewport.pxPerMs, "Typing does not trigger timeline zoom")
                assertFalse(controller.isPlaying, "Typing a space does not start playback")
                capture("10-review-composer-over-timeline.png")
                automator.pressKey(KeyEvent.VK_ESCAPE)
                settle()
                assertNull(automator.findOneByTestTag("ciacola-review-note-input"))
                assertNotNull(automator.findOneByTestTag("ciacola-review-stage"))
                assertNotNull(automator.findOneByTestTag("ciacola-doc-timeline-panel"))
            }
        }
}
