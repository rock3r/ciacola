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
import dev.bnorm.storyboard.easel.LocalExportEmittedFrameIndex
import dev.bnorm.storyboard.easel.LocalExportFps
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
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
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

    @Test
    fun cancellationDuringSinkSpawnStillAbortsSpawnedSink() = runTest {
        val dir = Files.createTempDirectory("video-export-cancel-during-spawn")
        try {
            val sink = RecordingVideoSink()
            lateinit var exporter: StoryboardVideoExporter
            exporter = StoryboardVideoExporter(
                ffmpegChecker = { true },
                sinkFactory = { _, _, _, _ ->
                    exporter.cancel()
                    sink
                },
            )

            val job = launch {
                assertFailsWith<CancellationException> {
                    exporter.exportAsMp4(animatedDeck, dir.resolve("cancelled-during-spawn.mp4"))
                }
            }
            job.join()

            assertTrue(sink.aborted, "Sink spawned concurrently with cancellation must still be aborted")
            assertFalse(sink.finished)
            assertNull(exporter.status)
            assertFalse(exporter.isExporting)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun emittedFrameCounterAdvancesDeterministicallyAcrossSettleFramesAndIsNullDuringMeasurement() = runTest {
        val dir = Files.createTempDirectory("video-export-emitted-counter")
        try {
            val fps = 60
            val measurementObservedIndices = mutableListOf<Long?>()
            val measurementObservedFps = mutableListOf<Int?>()
            var inMeasurement = true

            val counterDeck = Storyboard.build(
                title = "EmittedFrameCounterDeck",
                format = SceneFormat(IntSize(16, 16), Density(1f)),
            ) {
                scene(frames = listOf(0, 1)) {
                    val emittedIndex = LocalExportEmittedFrameIndex.current
                    val exportFps = LocalExportFps.current
                    if (inMeasurement) {
                        measurementObservedIndices += emittedIndex
                        measurementObservedFps += exportFps
                    }
                    val offsetX by transition.animateDp(
                        transitionSpec = { tween(durationMillis = 100, easing = LinearEasing) },
                        label = "counterOffset",
                    ) { frame ->
                        when (frame) {
                            is Frame.Value -> if (frame.value == 0) 0.dp else 8.dp
                            else -> 0.dp
                        }
                    }
                    val encodedIdx = (emittedIndex ?: 0L).toInt()
                    val encodedFps = exportFps ?: 0
                    val encodedColor = Color(
                        red = (encodedIdx and 0xFF) / 255f,
                        green = ((encodedIdx ushr 8) and 0xFF) / 255f,
                        blue = (encodedFps and 0xFF) / 255f,
                        alpha = 1f,
                    )
                    Box(Modifier.fillMaxSize().background(encodedColor)) {
                        Box(Modifier.offset(x = offsetX).size(2.dp))
                    }
                }
                scene(frames = listOf(0)) {
                    val emittedIndex = LocalExportEmittedFrameIndex.current
                    val exportFps = LocalExportFps.current
                    if (inMeasurement) {
                        measurementObservedIndices += emittedIndex
                        measurementObservedFps += exportFps
                    }
                    val encodedIdx = (emittedIndex ?: 0L).toInt()
                    val encodedFps = exportFps ?: 0
                    val encodedColor = Color(
                        red = (encodedIdx and 0xFF) / 255f,
                        green = ((encodedIdx ushr 8) and 0xFF) / 255f,
                        blue = (encodedFps and 0xFF) / 255f,
                        alpha = 1f,
                    )
                    Box(Modifier.fillMaxSize().background(encodedColor))
                }
            }

            val decodedIndices = mutableListOf<Long>()
            val decodedFps = mutableListOf<Int>()
            val pixelSink = object : VideoFrameSink {
                var finished = false
                override suspend fun writeFrame(rgbaBytes: ByteArray) {
                    val r = rgbaBytes[0].toInt() and 0xFF
                    val g = rgbaBytes[1].toInt() and 0xFF
                    val b = rgbaBytes[2].toInt() and 0xFF
                    decodedIndices += ((g shl 8) or r).toLong()
                    decodedFps += b
                }
                override suspend fun finish() { finished = true }
                override fun abort() {}
            }

            val exporter = StoryboardVideoExporter(
                fps = fps,
                beatHoldMillis = 100L, // 6 frames per beat
                ffmpegChecker = { true },
                sinkFactory = { _, _, _, _ -> pixelSink },
                holdDurationForState = { index ->
                    if (index == Storyboard.Index(0, 1)) Duration.ZERO else 50.milliseconds // 3, 1, 3 hold frames
                },
            )

            // 1) Measurement pass must leave LocalExportEmittedFrameIndex & LocalExportFps null.
            val estimated = exporter.estimateTotalFrames(counterDeck)
            assertTrue(measurementObservedIndices.isNotEmpty(), "Expected measurement renders")
            assertTrue(
                measurementObservedIndices.all { it == null },
                "Measurement pass must not set LocalExportEmittedFrameIndex, saw $measurementObservedIndices",
            )
            assertTrue(
                measurementObservedFps.all { it == null },
                "Measurement pass must not set LocalExportFps, saw $measurementObservedFps",
            )

            // 2) Full export: every emitted frame must observe strictly sequential 0, 1, ..., N-1
            // despite initial settle and scrubSettling discarded frames between beats and scenes.
            inMeasurement = false
            exporter.exportAsMp4(counterDeck, dir.resolve("counter.mp4"))

            assertTrue(pixelSink.finished)
            assertEquals(estimated, decodedIndices.size, "Frame estimate must match emitted frame count")
            assertEquals(
                (0L until decodedIndices.size.toLong()).toList(),
                decodedIndices,
                "Emitted frame counter must increment strictly 0..N-1 with zero gaps from settle frames",
            )
            assertTrue(decodedFps.all { it == fps }, "Every emitted frame must observe LocalExportFps == $fps")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun zeroDurationHoldCallbackEmitsExactlyOneFramePerBeat() = runTest {
        val dir = Files.createTempDirectory("video-export-zero-hold")
        try {
            val staticDeck = Storyboard.build(
                title = "StaticAutoAdvanceDeck",
                format = SceneFormat(IntSize(16, 16), Density(1f)),
            ) {
                scene(frames = listOf(0, 1)) {
                    Box(Modifier.fillMaxSize().background(Color.Red))
                }
                scene(frames = listOf(0)) {
                    Box(Modifier.fillMaxSize().background(Color.Blue))
                }
            }

            // All beats Duration.ZERO (autoAdvanceMs = 0) -> exactly 1 frame per beat.
            val allZeroSink = RecordingVideoSink()
            val allZeroExporter = StoryboardVideoExporter(
                fps = 60,
                beatHoldMillis = 400L,
                ffmpegChecker = { true },
                sinkFactory = { _, _, _, _ -> allZeroSink },
                holdDurationForState = { Duration.ZERO },
            )
            val allZeroEstimate = allZeroExporter.estimateTotalFrames(staticDeck)
            allZeroExporter.exportAsMp4(staticDeck, dir.resolve("zero.mp4"))

            assertEquals(3, allZeroEstimate, "3 beats at Duration.ZERO must estimate 3 frames")
            assertEquals(3, allZeroSink.frameHashes.size, "3 beats at Duration.ZERO must emit exactly 3 frames")

            // Mixed per-beat holds: beat 0 = 0ms (1f), beat 1 = 100ms (6f @ 60fps), beat 2 = 0ms (1f).
            val mixedSink = RecordingVideoSink()
            val mixedExporter = StoryboardVideoExporter(
                fps = 60,
                beatHoldMillis = 400L,
                ffmpegChecker = { true },
                sinkFactory = { _, _, _, _ -> mixedSink },
                holdDurationForState = { index ->
                    when (index) {
                        Storyboard.Index(0, 0) -> Duration.ZERO
                        Storyboard.Index(0, 1) -> 100.milliseconds
                        else -> Duration.ZERO
                    }
                },
            )
            val mixedEstimate = mixedExporter.estimateTotalFrames(staticDeck)
            mixedExporter.exportAsMp4(staticDeck, dir.resolve("mixed.mp4"))

            assertEquals(8, mixedEstimate, "Mixed holds (1 + 6 + 1) must estimate 8 frames")
            assertEquals(8, mixedSink.frameHashes.size, "Mixed holds (1 + 6 + 1) must emit 8 frames")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun offGridHoldRoundsWithCallbackAndTruncatesWithoutCallback() = runTest {
        val dir = Files.createTempDirectory("video-export-off-grid-hold")
        try {
            val twoBeatStaticDeck = Storyboard.build(
                title = "OffGridHoldDeck",
                format = SceneFormat(IntSize(16, 16), Density(1f)),
            ) {
                scene(frames = listOf(0, 1)) {
                    Box(Modifier.fillMaxSize().background(Color.DarkGray))
                }
            }

            // 45ms at 60fps = 2.7 frames:
            // - legacy (callback absent): truncates to 2 frames/beat -> 4 total frames
            // - callback supplied: rounds to 3 frames/beat -> 6 total frames
            val legacy45Sink = RecordingVideoSink()
            val legacy45Exporter = StoryboardVideoExporter(
                fps = 60,
                beatHoldMillis = 45L,
                ffmpegChecker = { true },
                sinkFactory = { _, _, _, _ -> legacy45Sink },
            )
            assertEquals(4, legacy45Exporter.estimateTotalFrames(twoBeatStaticDeck))
            legacy45Exporter.exportAsMp4(twoBeatStaticDeck, dir.resolve("legacy45.mp4"))
            assertEquals(4, legacy45Sink.frameHashes.size, "Legacy absent callback must truncate 45ms@60fps to 2 frames/beat")

            val rounded45Sink = RecordingVideoSink()
            val rounded45Exporter = StoryboardVideoExporter(
                fps = 60,
                beatHoldMillis = 45L,
                ffmpegChecker = { true },
                sinkFactory = { _, _, _, _ -> rounded45Sink },
                holdDurationForState = { 45.milliseconds },
            )
            assertEquals(6, rounded45Exporter.estimateTotalFrames(twoBeatStaticDeck))
            rounded45Exporter.exportAsMp4(twoBeatStaticDeck, dir.resolve("rounded45.mp4"))
            assertEquals(6, rounded45Sink.frameHashes.size, "Callback path must round 45ms@60fps to 3 frames/beat")

            // 25ms at 60fps = 1.5 frames:
            // - legacy (callback absent): truncates to 1 frame/beat -> 2 total frames
            // - callback supplied: rounds half-up to 2 frames/beat -> 4 total frames
            val legacy25Sink = RecordingVideoSink()
            val legacy25Exporter = StoryboardVideoExporter(
                fps = 60,
                beatHoldMillis = 25L,
                ffmpegChecker = { true },
                sinkFactory = { _, _, _, _ -> legacy25Sink },
            )
            assertEquals(2, legacy25Exporter.estimateTotalFrames(twoBeatStaticDeck))
            legacy25Exporter.exportAsMp4(twoBeatStaticDeck, dir.resolve("legacy25.mp4"))
            assertEquals(2, legacy25Sink.frameHashes.size, "Legacy absent callback must truncate 25ms@60fps to 1 frame/beat")

            val rounded25Sink = RecordingVideoSink()
            val rounded25Exporter = StoryboardVideoExporter(
                fps = 60,
                beatHoldMillis = 25L,
                ffmpegChecker = { true },
                sinkFactory = { _, _, _, _ -> rounded25Sink },
                holdDurationForState = { 25.milliseconds },
            )
            assertEquals(4, rounded25Exporter.estimateTotalFrames(twoBeatStaticDeck))
            rounded25Exporter.exportAsMp4(twoBeatStaticDeck, dir.resolve("rounded25.mp4"))
            assertEquals(4, rounded25Sink.frameHashes.size, "Callback path must round 25ms@60fps to 2 frames/beat")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun frameEstimateMatchesEmittedCountForAnimatedDeckWithConstantAndPerBeatHolds() = runTest {
        val dir = Files.createTempDirectory("video-export-estimate-match")
        try {
            // 250ms transition at 60fps = exact 15 transition frames.
            // Constant 200ms hold = 12 frames/beat -> 12 + 15 + 12 = 39 frames.
            val legacySink = RecordingVideoSink()
            val legacyExporter = StoryboardVideoExporter(
                fps = 60,
                beatHoldMillis = 200L,
                ffmpegChecker = { true },
                sinkFactory = { _, _, _, _ -> legacySink },
            )
            val legacyEstimate = legacyExporter.estimateTotalFrames(animatedDeck)
            legacyExporter.exportAsMp4(animatedDeck, dir.resolve("animated-legacy.mp4"))
            assertEquals(39, legacyEstimate)
            assertEquals(legacyEstimate, legacySink.frameHashes.size)

            // Per-beat callback: Beat 0 = Duration.ZERO (1 frame), Beat 1 = 45ms (rounded 3 frames) -> 1 + 15 + 3 = 19 frames.
            val callbackSink = RecordingVideoSink()
            val callbackExporter = StoryboardVideoExporter(
                fps = 60,
                beatHoldMillis = 200L,
                ffmpegChecker = { true },
                sinkFactory = { _, _, _, _ -> callbackSink },
                holdDurationForState = { index ->
                    if (index.frameIndex == 0) Duration.ZERO else 45.milliseconds
                },
            )
            val callbackEstimate = callbackExporter.estimateTotalFrames(animatedDeck)
            callbackExporter.exportAsMp4(animatedDeck, dir.resolve("animated-callback.mp4"))
            assertEquals(19, callbackEstimate)
            assertEquals(callbackEstimate, callbackSink.frameHashes.size)
        } finally {
            dir.deleteRecursively()
        }
    }
}
