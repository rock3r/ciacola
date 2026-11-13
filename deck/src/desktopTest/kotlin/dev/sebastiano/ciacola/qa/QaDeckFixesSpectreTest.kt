package dev.sebastiano.ciacola.qa

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPosition
import dev.bnorm.storyboard.Storyboard
import dev.sebastiano.ciacola.theme.DeckThemePreset
import dev.sebastiano.spectre.testing.runSpectreTest
import java.awt.Point
import java.awt.event.KeyEvent
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import org.junit.Rule

class QaDeckFixesSpectreTest {

    @get:Rule
    val qa = QaStudioRule()

    @Test
    fun slide1Beat3WrapsHeadlineInsideStageViewport(): Unit = runSpectreTest(timeout = 60.seconds) {
        val studio = qa.launchStudio(
            preset = DeckThemePreset.StageDark,
            windowSize = DpSize(1280.dp, 800.dp),
            timelineDocked = true,
            includeKeynoteEffectsLab = true,
        )
        with(studio) {
            settle()
            onUi { controller.animatic.jumpTo(Storyboard.Index(0, 2)) }
            settle()
            val viewport = checkNotNull(
                automator.findOneByTestTag("ciacola-preview-viewport")?.boundsOnScreen,
            ) { "Missing ciacola-preview-viewport" }

            val firstWord = stageText("The", exact = true).boundsOnScreen
            val navWord = stageText("navigation", exact = true).boundsOnScreen
            val modelWord = stageText("model", exact = true).boundsOnScreen

            assertTrue(
                navWord.x + navWord.width <= viewport.x + viewport.width,
                "Expected 'navigation' (${navWord.x + navWord.width}) inside stage right edge (${viewport.x + viewport.width})",
            )
            assertTrue(
                modelWord.x + modelWord.width <= viewport.x + viewport.width,
                "Expected 'model' (${modelWord.x + modelWord.width}) inside stage right edge (${viewport.x + viewport.width})",
            )
            assertTrue(
                modelWord.y > firstWord.y + firstWord.height / 2,
                "Expected 'model' (y=${modelWord.y}) to wrap onto a line below 'The' (y=${firstWord.y})",
            )
        }
    }

    @Test
    fun slide5Beat5LoupeDoesNotOccludeWarpCodeCard(): Unit = runSpectreTest(timeout = 60.seconds) {
        val studio = qa.launchStudio(
            preset = DeckThemePreset.StageDark,
            windowSize = DpSize(1280.dp, 800.dp),
            timelineDocked = true,
            includeKeynoteEffectsLab = true,
        )
        with(studio) {
            settle()
            onUi { controller.animatic.jumpTo(Storyboard.Index(4, 4)) }
            settle()

            val cardBounds = checkNotNull(
                automator.findOneByTestTag("ciacola-lab-warp-card")?.boundsOnScreen,
            ) { "Missing ciacola-lab-warp-card" }
            val loupeBounds = checkNotNull(
                automator.findOneByTestTag("ciacola-magnifier-loupe")?.boundsOnScreen,
            ) { "Missing ciacola-magnifier-loupe" }

            assertTrue(
                !cardBounds.intersects(loupeBounds),
                "Expected MagnifierLoupeStage ($loupeBounds) not to occlude DeckWarpCodeCard ($cardBounds)",
            )
        }
    }

    @Test
    fun slide3AmbientMeshAdaptsToKeynoteLightAndAlignsEyebrow(): Unit = runSpectreTest(timeout = 60.seconds) {
        val studio = qa.launchStudio(
            preset = DeckThemePreset.KeynoteLight,
            windowSize = DpSize(1280.dp, 800.dp),
            timelineDocked = false,
            includeKeynoteEffectsLab = true,
        )
        with(studio) {
            settle()
            val s1Eyebrow = stageText("FOUNDATION / PROVISIONAL", exact = true).boundsOnScreen

            onUi { controller.animatic.jumpTo(Storyboard.Index(2, 0)) }
            settle()
            val s3Eyebrow = stageText("CONTINUOUS COMPOSE / PROVISIONAL", exact = true).boundsOnScreen

            assertTrue(
                abs(s3Eyebrow.x - s1Eyebrow.x) <= 3 && abs(s3Eyebrow.y - s1Eyebrow.y) <= 3,
                "Expected Slide 3 eyebrow ($s3Eyebrow) to align with Slide 1 eyebrow ($s1Eyebrow)",
            )

            val img = screenshot(settleDelayMs = 40L)
            // Sample upper-left/mid-left mesh region inside the window (convert AWT logical bounds via window size ratio)
            val scaleX = img.width.toDouble() / window.width.coerceAtLeast(1)
            val scaleY = img.height.toDouble() / window.height.coerceAtLeast(1)
            val sampleX = ((s3Eyebrow.x - window.locationOnScreen.x + 40) * scaleX).toInt().coerceIn(0, img.width - 1)
            val sampleY = (img.height * 0.50).toInt().coerceIn(0, img.height - 1)
            val argb = img.getRGB(sampleX, sampleY)
            val r = (argb ushr 16) and 0xFF
            val g = (argb ushr 8) and 0xFF
            val b = argb and 0xFF
            val luma = (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255.0
            assertTrue(
                luma > 0.55,
                "Expected Slide 3 mesh in Keynote Light to be light-toned (luma > 0.55), got luma=$luma rgb=($r,$g,$b)",
            )
        }
    }

    @Test
    fun slide2SharedCardMountsSingleTargetWithoutGhostDuplicatesMidMorph(): Unit = runSpectreTest(timeout = 60.seconds) {
        val studio = qa.launchStudio(
            preset = DeckThemePreset.StageDark,
            windowSize = DpSize(1280.dp, 800.dp),
            timelineDocked = true,
            includeKeynoteEffectsLab = true,
        )
        with(studio) {
            settle()
            // Scrub to midpoint of S2:B1 -> S2:B2
            val s2b1Event = controller.snapshot.eventBeats.first { it.sceneIndex == 1 && it.frameIndex == 0 }
            val midTimeMs = s2b1Event.timeMs + (s2b1Event.segmentDurationMs / 2)
            onUi {
                controller.selectTrack("shared:foundation-shared-card")
                controller.scrubToTimeMs(midTimeMs)
            }
            settle()

            val viewport = checkNotNull(
                automator.findOneByTestTag("ciacola-preview-viewport")?.boundsOnScreen,
            ) { "Missing ciacola-preview-viewport" }
            val sharedLabelsOnStage = automator.findByText("shared", exact = true).filter { node ->
                viewport.contains(node.centerOnScreen)
            }

            assertEquals(
                1,
                sharedLabelsOnStage.size,
                "Expected exactly 1 'shared' label mid-morph on Slide 2 (no double-mounted ghosting), got ${sharedLabelsOnStage.size}",
            )
            assertEquals(
                1,
                stageSelection.entriesFor("shared:foundation-shared-card").size,
                "Expected exactly 1 selection target box for Shared Card on Slide 2",
            )
        }
    }

    @Test
    fun overviewFormatsSlideBadgeAndEliminatesGhostDuplicateCards(): Unit = runSpectreTest(timeout = 60.seconds) {
        val studio = qa.launchStudio(
            preset = DeckThemePreset.StageDark,
            windowSize = DpSize(1280.dp, 800.dp),
            timelineDocked = false,
            includeKeynoteEffectsLab = true,
        )
        with(studio) {
            settle()
            val loc = window.locationOnScreen
            awtClick(Point(loc.x + 32, loc.y + 48))
            settle()
            val winIn = inputFor(window)
            winIn.keyPress(KeyEvent.VK_ESCAPE)
            winIn.keyRelease(KeyEvent.VK_ESCAPE)
            settle()

            automator.waitUntil(description = "formatted overview badge", timeout = 5.seconds) {
                automator.findByText("Slide 1 / 5 · Beat 1 / 3", exact = true).isNotEmpty()
            }
            assertEquals(
                0,
                automator.findByText("0,0", exact = true).size,
                "Raw '0,0' debug index badge must not appear in Overview",
            )
            // Slide 3 ('one stable scene state') has 1 beat and must render once, not 3x with Start/End ghosts.
            assertEquals(
                1,
                automator.findByText("one stable scene state", exact = true).size,
                "Single-beat Slide 3 must render 1 card in Overview without Start/End duplicates",
            )
        }
    }

    @Test
    fun reviewInspectHitTestIgnoresDebugOverlaySemanticsAndHighlightsTightHeadlineBounds(): Unit = runSpectreTest(timeout = 60.seconds) {
        val studio = qa.launchStudio(
            preset = DeckThemePreset.StageDark,
            windowSize = DpSize(1280.dp, 800.dp),
            timelineDocked = true,
            includeKeynoteEffectsLab = true,
        )
        with(studio) {
            settle()
            onUi {
                controller.animatic.jumpTo(Storyboard.Index(4, 3))
                review.inspectModeEnabled = true
            }
            settle()

            val headlineNode = stageText("Fix with SpanStyle(alpha)!", exact = true)
            awtClick(headlineNode.centerOnScreen)
            settle()

            val pending = assertNotNull(review.pendingSelection, "Expected pending review selection after Inspect click")
            assertFalse(
                pending.target.textSnippet.contains("selected=") || pending.target.textSnippet.contains("previewScale="),
                "Review target must never capture internal debug overlay descriptions, got: ${pending.target}",
            )
            assertTrue(
                pending.target.textSnippet.contains("Fix with SpanStyle(alpha)!"),
                "Expected headline text snippet, got: ${pending.target}",
            )
            val selBounds = assertNotNull(pending.selectionBounds, "Expected selection bounds on headline")
            assertTrue(
                selBounds.width < 1300f && selBounds.height < 400f,
                "Expected tight headline bounds instead of full-bleed 1920x1080 frame tint, got: $selBounds",
            )

            // Clicking empty space on Slide 2 must resolve to Stage, not '[Text] selected=none highlighted=0'.
            onUi {
                review.dismissActivePopups()
                controller.animatic.jumpTo(Storyboard.Index(1, 0))
            }
            settle()
            val stageRect = checkNotNull(automator.findOneByTestTag("ciacola-review-stage")?.boundsOnScreen)
            val emptyRight = Point(stageRect.x + (stageRect.width * 0.75).toInt(), stageRect.y + (stageRect.height * 0.75).toInt())
            awtClick(emptyRight)
            settle()

            val emptyPending = assertNotNull(review.pendingSelection, "Expected pending selection on empty stage click")
            assertEquals("Stage", emptyPending.target.kind, "Empty stage click must resolve to Stage, got ${emptyPending.target}")
            assertFalse(emptyPending.target.textSnippet.contains("selected="))
        }
    }

    @Test
    fun windowLaunchesCleanlyAtNegativeXCoordinatePreservingSize(): Unit = runSpectreTest(timeout = 60.seconds) {
        val studio = qa.launchStudio(
            preset = DeckThemePreset.StageDark,
            windowSize = DpSize(1440.dp, 920.dp),
            windowPosition = WindowPosition((-180).dp, 64.dp),
            timelineDocked = false,
            includeKeynoteEffectsLab = false,
        )
        with(studio) {
            settle()
            assertTrue(
                window.width >= 1400 && window.height >= 880,
                "Expected window launched with negative X to retain ~1440x920 size instead of 800x600, got ${window.width}x${window.height}",
            )
        }
    }
}
