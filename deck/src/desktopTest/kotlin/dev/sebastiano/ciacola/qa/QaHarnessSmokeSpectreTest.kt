package dev.sebastiano.ciacola.qa

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import dev.sebastiano.ciacola.theme.DeckThemePreset
import dev.sebastiano.spectre.testing.runSpectreTest
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Rule

/**
 * Smoke suite exercising [QaStudio], window-only [screenshot] and [record] evidence, detached
 * timeline & light-theme launch options, and schema-compliant [finding] JSONL output.
 */
class QaHarnessSmokeSpectreTest {

    @get:Rule
    val qa = QaStudioRule()

    @Test
    fun launchesStudioRecordsScrubEvidenceAndWritesSampleFinding(): Unit =
        runSpectreTest(timeout = 120.seconds) {
            val worker = "harness-smoke"
            val defaultRoot = QaPaths.qaRoot()
            val qaRoot = if (defaultRoot.exists()) {
                defaultRoot
            } else {
                kotlin.io.path.createTempDirectory("ciacola-qa-smoke-root-").toFile()
            }
            val findingsFile = QaPaths.findingsFile(worker = worker, qaRoot = qaRoot)
            val tempRootToClean = qaRoot.takeIf { it != defaultRoot }

            try {
                findingsFile.delete()

                val studio = qa.launchStudio(
                    preset = DeckThemePreset.StageDark,
                    windowSize = DpSize(1280.dp, 840.dp),
                    worker = worker,
                    qaRoot = qaRoot,
                )
                with(studio) {
                    val canvas = openTimeline()
                    val snap = controller.snapshot
                    assertTrue(snap.totalDurationMs > 1_000, "Timeline indexed FoundationDeck")

                    // 1. Window-only SkiaLayer screenshot.
                    val stillFile = saveScreenshot("smoke-docked-studio.png")
                    assertTrue(stillFile.isFile && stillFile.length() > 1_000L, "Saved PNG screenshot")
                    val stillImage = ImageIO.read(stillFile)
                    assertNotNull(stillImage)
                    assertTrue(stillImage.width >= 1200 && stillImage.height >= 800)

                    // 2. Record a ~2 s scrub across slides into .mp4 (H.264 yuv420p) + .gif (<= 960 px).
                    val startX = rulerPoint(canvas, 80).x
                    val midX = rulerPoint(canvas, (snap.totalDurationMs * 0.65).toInt()).x
                    val endX = rulerPoint(canvas, (snap.totalDurationMs * 0.25).toInt()).x
                    val rulerY = rulerPoint(canvas, 0).y

                    val rec = record(name = "smoke-2s-scrub", fps = 20) {
                        automator.swipe(
                            startX = startX,
                            startY = rulerY,
                            endX = midX,
                            endY = rulerY,
                            steps = 28,
                            duration = 1_100.milliseconds,
                        )
                        automator.swipe(
                            startX = midX,
                            startY = rulerY,
                            endX = endX,
                            endY = rulerY,
                            steps = 22,
                            duration = 900.milliseconds,
                        )
                        settle()
                    }

                    assertTrue(rec.mp4.isFile && rec.mp4.length() > 1_000L, "Encoded MP4 exists")
                    assertTrue(rec.gif.isFile && rec.gif.length() > 1_000L, "Encoded GIF exists")
                    assertTrue(rec.frameCount >= 10, "Captured ${rec.frameCount} window frames")
                    val gifImg = ImageIO.read(rec.gif)
                    assertNotNull(gifImg, "GIF readable by ImageIO")
                    assertTrue(gifImg.width in 1..960, "GIF width <= 960 px (got ${gifImg.width})")

                    // 3. Write one sample finding to harness-smoke.jsonl and verify the schema.
                    val written = finding(
                        title = "Harness smoke check",
                        area = QaArea.TimelineScrub,
                        type = QaFindingType.Visual,
                        severity = QaSeverity.Polish,
                        steps = listOf(
                            "Open Timeline Studio (F5)",
                            "Drag playhead forward and back across ruler",
                        ),
                        expected = "Playhead and stage scrub smoothly",
                        actual = "Harness smoke verification pass",
                        evidence = listOf(evidencePath(stillFile), rec.gifEvidencePath, rec.mp4EvidencePath),
                        repro = QaRepro.Always,
                        confidence = QaConfidence.High,
                        notes = "QaHarnessSmokeSpectreTest verification",
                    )

                    assertEquals("F-harness-smoke-001", written.id)
                    assertTrue(findingsFile.isFile, "Findings JSONL written at $findingsFile")
                    val lines = findingsFile.readLines().filter { it.isNotBlank() }
                    assertEquals(1, lines.size)
                    val parsed = Json.parseToJsonElement(lines.single()).jsonObject
                    assertEquals("F-harness-smoke-001", parsed["id"]?.jsonPrimitive?.content)
                    assertEquals("timeline-scrub", parsed["area"]?.jsonPrimitive?.content)
                    assertEquals("harness-smoke", parsed["reporter"]?.jsonPrimitive?.content)
                    assertEquals(3, parsed["evidence"]?.jsonArray?.size)
                    assertTrue(
                        parsed["evidence"]!!.jsonArray.all {
                            it.jsonPrimitive.content.startsWith("evidence/harness-smoke/")
                        },
                        "Evidence paths are relative to \$QA",
                    )
                }
            } finally {
                // Always delete the throwaway sample finding so live triage never ingests it.
                findingsFile.delete()
                // Clean smoke media artifacts as well so evidence/ stays reserved for real findings.
                File(QaPaths.evidenceDir(worker = worker, qaRoot = qaRoot).path).deleteRecursively()
                tempRootToClean?.deleteRecursively()
            }
        }

    @Test
    fun supportsKeynoteLightThemeAndDetachedTimelineWindow(): Unit =
        runSpectreTest(timeout = 120.seconds) {
            val studio = qa.launchStudio(
                preset = DeckThemePreset.KeynoteLight,
                windowSize = DpSize(1120.dp, 720.dp),
                worker = "harness-smoke",
            )
            with(studio) {
                assertEquals(DeckThemePreset.KeynoteLight, preset)
                val detachedWin = openDetachedTimeline()
                assertTrue(controller.detachedWindowVisible)
                assertTrue(!controller.dockedInStoryWindow)

                val storyImg = screenshot(window)
                val detachedImg = screenshot(detachedWin)
                assertTrue(storyImg.width > 800 && detachedImg.width > 800)

                closeDetachedTimeline()
                assertTrue(!controller.detachedWindowVisible)
            }
        }
}
