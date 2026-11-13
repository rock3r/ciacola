package dev.bnorm.storyboard.easel.export

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDp
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material.Text
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.bnorm.storyboard.Frame
import dev.bnorm.storyboard.SceneFormat
import dev.bnorm.storyboard.Storyboard
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield

@OptIn(kotlin.io.path.ExperimentalPathApi::class)
class StoryboardVideoExporterTest {

    private val smallFormat = SceneFormat(IntSize(160, 90), Density(1f))

    private val animatedDeck = Storyboard.build(
        title = "AnimatedVideoDeck",
        format = smallFormat,
    ) {
        scene(frames = listOf(0, 1)) {
            val offsetX by transition.animateDp(
                transitionSpec = { tween(durationMillis = 250, easing = LinearEasing) },
                label = "boxOffset",
            ) { frame ->
                when (frame) {
                    is Frame.Value -> if (frame.value == 0) 8.dp else 100.dp
                    else -> 8.dp
                }
            }
            Box(Modifier.fillMaxSize().background(Color(0xFF101820))) {
                Box(
                    Modifier
                        .offset(x = offsetX, y = 20.dp)
                        .size(40.dp)
                        .background(Color(0xFFFF6B5E)),
                )
            }
        }
    }

    private class RecordingVideoSink : VideoFrameSink {
        val frameHashes = mutableListOf<Int>()
        val frameSizes = mutableListOf<Int>()
        var finished = false
        var aborted = false
        var onWriteFrame: (suspend (Int) -> Unit)? = null

        override suspend fun writeFrame(rgbaBytes: ByteArray) {
            onWriteFrame?.invoke(frameHashes.size)
            frameSizes += rgbaBytes.size
            frameHashes += rgbaBytes.contentHashCode()
        }

        override suspend fun finish() {
            finished = true
        }

        override fun abort() {
            aborted = true
        }
    }

    @Test
    fun missingFfmpegFailsImmediatelyWithActionableMessage() = runTest {
        val dir = Files.createTempDirectory("video-export-missing-ffmpeg")
        try {
            var sinkCreated = false
            val exporter = StoryboardVideoExporter(
                ffmpegChecker = { false },
                sinkFactory = { _, _, _, _ ->
                    sinkCreated = true
                    RecordingVideoSink()
                },
            )
            exporter.exportAsMp4(animatedDeck, dir.resolve("out.mp4"))

            assertFalse(sinkCreated, "Sink must not be created when ffmpeg pre-flight fails")
            val status = assertIs<ExportStatus.Failed>(exporter.status)
            assertEquals(FFMPEG_MISSING_MESSAGE, status.message)
            assertFalse(exporter.isExporting)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun deterministicFrameCountAndNonIdenticalTransitionFrames() = runTest {
        val dir = Files.createTempDirectory("video-export-deterministic")
        try {
            val sink = RecordingVideoSink()
            val fps = 60
            val holdMs = 200L // 12 frames per beat
            val expectedHoldFramesPerBeat = 12
            val exporter = StoryboardVideoExporter(
                fps = fps,
                beatHoldMillis = holdMs,
                ffmpegChecker = { true },
                sinkFactory = { _, _, _, _ -> sink },
            )

            exporter.exportAsMp4(animatedDeck, dir.resolve("out.mp4"))

            assertTrue(sink.finished)
            assertFalse(sink.aborted)
            assertNull(exporter.status)
            assertFalse(exporter.isExporting)

            val expectedByteCount = smallFormat.size.width * smallFormat.size.height * 4
            assertTrue(sink.frameSizes.all { it == expectedByteCount }, "Every frame must have RGBA buffer size $expectedByteCount")

            // 2 beats * 12 hold frames + ~15-16 frames of 250ms transition at 60fps.
            assertTrue(
                sink.frameHashes.size > expectedHoldFramesPerBeat * 2 + 10,
                "Expected hold + transition frames, got ${sink.frameHashes.size}",
            )

            val beat0HoldHashes = sink.frameHashes.take(expectedHoldFramesPerBeat).toSet()
            assertEquals(1, beat0HoldHashes.size, "Beat 0 hold frames should be identical")

            val beat1HoldHashes = sink.frameHashes.takeLast(expectedHoldFramesPerBeat).toSet()
            assertEquals(1, beat1HoldHashes.size, "Beat 1 hold frames should be identical")

            val beat0Hash = beat0HoldHashes.single()
            val beat1Hash = beat1HoldHashes.single()
            assertTrue(beat0Hash != beat1Hash, "Beat 0 and Beat 1 settled frames must differ")

            val transitionHashes = sink.frameHashes.subList(
                expectedHoldFramesPerBeat,
                sink.frameHashes.size - expectedHoldFramesPerBeat,
            )
            val distinctMidFrames = transitionHashes.count { it != beat0Hash && it != beat1Hash }
            assertTrue(
                distinctMidFrames >= 5,
                "Expected distinct intermediate transition frames, got $distinctMidFrames in $transitionHashes",
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun cancellationMidExportAbortsSinkAndClearsStatus() = runTest {
        val dir = Files.createTempDirectory("video-export-cancel")
        try {
            val sink = RecordingVideoSink()
            val exporter = StoryboardVideoExporter(
                fps = 60,
                beatHoldMillis = 200L,
                ffmpegChecker = { true },
                sinkFactory = { _, _, _, _ -> sink },
            )
            sink.onWriteFrame = { index ->
                if (index == 4) {
                    exporter.cancel()
                }
            }

            val job = launch {
                assertFailsWith<CancellationException> {
                    exporter.exportAsMp4(animatedDeck, dir.resolve("cancelled.mp4"))
                }
            }
            job.join()

            assertTrue(sink.aborted, "Cancelled export must abort the sink")
            assertFalse(sink.finished, "Cancelled export must not finish the sink")
            assertNull(exporter.status)
            assertFalse(exporter.isExporting)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun sinkIoErrorAbortsAndSurfacesFailedStatus() = runTest {
        val dir = Files.createTempDirectory("video-export-error")
        try {
            val sink = RecordingVideoSink().apply {
                onWriteFrame = { index ->
                    if (index == 2) throw IOException("simulated pipe failure")
                }
            }
            val exporter = StoryboardVideoExporter(
                fps = 60,
                beatHoldMillis = 200L,
                ffmpegChecker = { true },
                sinkFactory = { _, _, _, _ -> sink },
            )

            exporter.exportAsMp4(animatedDeck, dir.resolve("failed.mp4"))

            assertTrue(sink.aborted)
            assertFalse(sink.finished)
            val status = assertIs<ExportStatus.Failed>(exporter.status)
            assertTrue(status.message.contains("simulated pipe failure"))
            assertFalse(exporter.isExporting)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun sharedCoordinatorBlocksConcurrentPdfAndVideoExports() = runTest {
        val dir = Files.createTempDirectory("export-mutual-exclusion")
        try {
            val coordinator = ExportCoordinator()
            val sink = RecordingVideoSink()
            val videoExporter = StoryboardVideoExporter(
                coordinator = coordinator,
                fps = 30,
                beatHoldMillis = 200L,
                ffmpegChecker = { true },
                sinkFactory = { _, _, _, _ -> sink },
            )
            val pdfExporter = StoryboardPdfExporter(coordinator = coordinator)
            val pdfPath = dir.resolve("blocked.pdf")

            val runningVideo = launch {
                videoExporter.exportAsMp4(animatedDeck, dir.resolve("video.mp4"))
            }
            yield()
            assertTrue(coordinator.isExporting)
            pdfExporter.exportAsPdf(animatedDeck, pdfPath)
            runningVideo.join()

            assertFalse(pdfPath.exists(), "PDF export must be ignored while MP4 export is running")
            assertTrue(sink.finished)
            assertFalse(coordinator.isExporting)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun smokeTestRealFfmpegProducesValidMp4WhenInstalled() = runTest {
        if (!defaultCheckFfmpegAvailable()) return@runTest

        val twoSceneDeck = Storyboard.build(
            title = "SmokeMp4",
            format = smallFormat,
        ) {
            scene(frames = listOf(0)) {
                Box(Modifier.fillMaxSize().background(Color(0xFF171B24))) {
                    Text("Slide One", color = Color.White)
                }
            }
            scene(frames = listOf(0)) {
                Box(Modifier.fillMaxSize().background(Color(0xFF242124))) {
                    Text("Slide Two", color = Color.White)
                }
            }
        }

        val dir = Files.createTempDirectory("video-export-ffmpeg-smoke")
        try {
            val mp4Path = dir.resolve("smoke.mp4")
            val exporter = StoryboardVideoExporter(fps = 30, beatHoldMillis = 200L)
            exporter.exportAsMp4(twoSceneDeck, mp4Path)

            assertNull(exporter.status, "Expected no failure status, got ${exporter.status}")
            assertTrue(mp4Path.exists() && mp4Path.fileSize() > 0L, "Expected non-empty MP4 output")

            val verifyProcess = ProcessBuilder(
                "ffmpeg", "-v", "error", "-i", mp4Path.toString(), "-f", "null", "-"
            )
                .redirectErrorStream(true)
                .start()
            val verifyOutput = verifyProcess.inputStream.bufferedReader().readText()
            assertTrue(verifyProcess.waitFor(15, TimeUnit.SECONDS), "ffmpeg decode verification timed out")
            assertEquals(0, verifyProcess.exitValue(), "ffmpeg decode check failed: $verifyOutput")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun sinkFactoryFailureSurfacesFailedStatusAndReleasesCoordinator() = runTest {
        val dir = Files.createTempDirectory("video-export-sink-factory-fail")
        try {
            val exporter = StoryboardVideoExporter(
                ffmpegChecker = { true },
                sinkFactory = { _, _, _, _ -> throw IOException("simulated sink spawn failure") },
            )

            exporter.exportAsMp4(animatedDeck, dir.resolve("never-created.mp4"))

            val status = assertIs<ExportStatus.Failed>(exporter.status)
            assertTrue(status.message.contains("simulated sink spawn failure"))
            assertFalse(exporter.isExporting)
        } finally {
            dir.deleteRecursively()
        }
    }
}
