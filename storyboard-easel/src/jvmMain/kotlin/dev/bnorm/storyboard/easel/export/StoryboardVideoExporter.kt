package dev.bnorm.storyboard.easel.export

import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import dev.bnorm.storyboard.AdvanceDirection
import dev.bnorm.storyboard.Storyboard
import dev.bnorm.storyboard.easel.Animatic
import dev.bnorm.storyboard.easel.Easel
import dev.bnorm.storyboard.easel.SceneMode
import dev.bnorm.storyboard.easel.rememberAnimatic
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.dialogs.openFileSaver
import java.io.BufferedOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.readLines
import kotlin.math.ceil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo

internal const val FFMPEG_MISSING_MESSAGE =
    "MP4 export requires ffmpeg on PATH (e.g. 'brew install ffmpeg')."

private const val DEFAULT_FPS = 60
private const val DEFAULT_BEAT_HOLD_MILLIS = 400L
private const val MAX_LAYOUT_SETTLE_PASSES = 3
private const val MAX_MEASUREMENT_FRAMES = 50_000
private const val MAX_SEGMENT_FRAMES = 3_600

/**
 * Consumer of deterministic RGBA video frames produced by [StoryboardVideoExporter].
 * Implementations handle encoding/writing off the UI thread and cleanup on cancellation.
 */
interface VideoFrameSink : AutoCloseable {
    suspend fun writeFrame(rgbaBytes: ByteArray)
    suspend fun finish()
    fun abort()
    override fun close() = abort()
}

private class MissingFfmpegException : IOException(FFMPEG_MISSING_MESSAGE)

class StoryboardVideoExporter(
    private val coordinator: ExportCoordinator = ExportCoordinator(),
    private val fps: Int = DEFAULT_FPS,
    private val beatHoldMillis: Long = DEFAULT_BEAT_HOLD_MILLIS,
    private val ffmpegChecker: suspend () -> Boolean = ::defaultCheckFfmpegAvailable,
    private val sinkFactory: (path: Path, width: Int, height: Int, fps: Int) -> VideoFrameSink =
        ::FfmpegVideoFrameSink,
) {
    init {
        require(fps > 0) { "fps must be positive, got $fps" }
        require(beatHoldMillis >= 0L) { "beatHoldMillis must be non-negative, got $beatHoldMillis" }
    }

    val status: ExportStatus? get() = coordinator.status

    /** `true` from the moment an export is requested (file dialog included) until it ends. */
    val isExporting: Boolean get() = coordinator.isExporting

    /** Clears an [ExportStatus.Failed] status; a running export's progress is left alone. */
    fun dismissFailure() = coordinator.dismissFailure()

    /** Cancels an active MP4 export, if any. */
    fun cancel() = coordinator.cancel()

    /** Checks `ffmpeg` availability, asks for a save path, and exports [storyboard] to MP4. */
    suspend fun export(storyboard: Storyboard) {
        coordinator.runExclusive(failureMessage = ::failureMessage) { updateStatus ->
            if (!ffmpegChecker()) {
                throw MissingFfmpegException()
            }
            val file = FileKit.openFileSaver(
                suggestedName = storyboard.title,
                defaultExtension = "mp4",
                allowedExtensions = setOf("mp4"),
            )
            if (file != null) {
                exportAsMp4Locked(
                    storyboard = storyboard,
                    path = file.file.toPath(),
                    updateStatus = updateStatus,
                )
            }
        }
    }

    internal suspend fun exportAsMp4(
        storyboard: Storyboard,
        path: Path,
    ) {
        coordinator.runExclusive(failureMessage = ::failureMessage) { updateStatus ->
            if (!ffmpegChecker()) {
                throw MissingFfmpegException()
            }
            exportAsMp4Locked(storyboard, path, updateStatus)
        }
    }

    private fun failureMessage(e: Exception): String = when (e) {
        is MissingFfmpegException -> FFMPEG_MISSING_MESSAGE
        else -> "MP4 export failed: ${e.message ?: e::class.simpleName}"
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private suspend fun exportAsMp4Locked(
        storyboard: Storyboard,
        path: Path,
        updateStatus: (ExportStatus?) -> Unit,
    ) {
        val indices = storyboard.indices
        if (indices.isEmpty()) {
            throw IOException("Storyboard has no frames to export")
        }

        val width = storyboard.format.size.width
        val height = storyboard.format.size.height
        val holdFrames = ((beatHoldMillis * fps) / 1000L).toInt().coerceAtLeast(1)

        updateStatus(ExportStatus.Exporting(0f, "Preparing MP4 export\u2026"))
        yield()

        val estimatedTotalFrames = estimateTotalFrames(storyboard, fps, holdFrames).coerceAtLeast(holdFrames)

        var animatic: Animatic? = null
        var sceneScope: CoroutineScope? = null
        // Offscreen composition stays on the caller (UI) thread to avoid SnapshotStateObserver
        // deadlocks while a live Compose window may be active.
        val scene = ImageComposeScene(
            width = width,
            height = height,
            density = storyboard.format.density,
            coroutineContext = Dispatchers.Unconfined,
        ) {
            sceneScope = rememberCoroutineScope()
            val a = rememberAnimatic(storyboard = { storyboard })
            animatic = a
            Easel(a, mode = SceneMode.Preview)
        }

        val rgbaInfo = ImageInfo(width, height, ColorType.RGBA_8888, ColorAlphaType.PREMUL)
        val bitmap = Bitmap().apply { allocPixels(rgbaInfo) }
        var sink: VideoFrameSink? = null
        var finishedCleanly = false

        try {
            val activeSink = runInterruptible(Dispatchers.IO) { sinkFactory(path, width, height, fps) }
            sink = activeSink
            var frameTick = 0L
            fun nextTimeNanos(): Long = (frameTick++) * 1_000_000_000L / fps

            var renderedFrames = 0
            suspend fun writeRenderedImage(image: Image) {
                val bytes = try {
                    if (!image.readPixels(bitmap)) {
                        throw IOException("Failed to read pixels from rendered frame")
                    }
                    bitmap.readPixels(dstInfo = rgbaInfo, dstRowBytes = width * 4, srcX = 0, srcY = 0)
                        ?: throw IOException("Failed to extract RGBA buffer from rendered frame")
                } finally {
                    image.close()
                }
                activeSink.writeFrame(bytes)
                renderedFrames++
                val totalDenom = maxOf(estimatedTotalFrames, renderedFrames)
                updateStatus(
                    ExportStatus.Exporting(
                        progress = (renderedFrames.toFloat() / totalDenom).coerceIn(0f, 0.99f),
                        message = "Rendering MP4 ($renderedFrames / $totalDenom frames)\u2026",
                    )
                )
            }

            // Initial render and settle on Beat 0.
            var initialImage = scene.render(nextTimeNanos())
            var settlePass = 0
            while (scene.hasInvalidations() && settlePass < MAX_LAYOUT_SETTLE_PASSES) {
                initialImage.close()
                initialImage = scene.render(nextTimeNanos())
                settlePass++
            }
            val activeAnimatic = animatic ?: throw IOException("Failed to initialize offscreen Animatic")
            val activeScope = sceneScope ?: throw IOException("Failed to initialize offscreen CoroutineScope")

            // Hold on Beat 0.
            writeRenderedImage(initialImage)
            yield()
            repeat(holdFrames - 1) {
                val holdImage = scene.render(nextTimeNanos())
                writeRenderedImage(holdImage)
                yield()
            }

            // Advance deterministically beat by beat until the final resting state.
            for (beatIdx in 1 until indices.size) {
                val advanceJob = activeScope.launch {
                    activeAnimatic.advance(AdvanceDirection.Forward)
                }
                var emittedCompletionFrame = false
                try {
                    var safetyFrames = 0
                    while (!advanceJob.isCompleted && safetyFrames++ < MAX_SEGMENT_FRAMES) {
                        val image = scene.render(nextTimeNanos())
                        if (activeAnimatic.scrubSettling) {
                            // Play-time-0 settle frames are not presentable video frames.
                            image.close()
                        } else {
                            writeRenderedImage(image)
                            if (advanceJob.isCompleted) {
                                emittedCompletionFrame = true
                            }
                        }
                        yield()
                    }
                    if (!advanceJob.isCompleted) {
                        throw IOException("Animation segment before beat $beatIdx did not settle within $MAX_SEGMENT_FRAMES frames")
                    }
                } finally {
                    advanceJob.cancel()
                }

                val remainingHoldFrames = if (emittedCompletionFrame) holdFrames - 1 else holdFrames
                repeat(remainingHoldFrames.coerceAtLeast(0)) {
                    val holdImage = scene.render(nextTimeNanos())
                    writeRenderedImage(holdImage)
                    yield()
                }
            }

            updateStatus(ExportStatus.Exporting(1.0f, "Encoding MP4\u2026"))
            activeSink.finish()
            finishedCleanly = true
            updateStatus(null)
        } finally {
            val toAbort = sink
            if (!finishedCleanly && toAbort != null) {
                withContext(NonCancellable + Dispatchers.IO) {
                    runInterruptible { toAbort.abort() }
                }
            }
            bitmap.close()
            scene.close()
        }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private suspend fun estimateTotalFrames(
        storyboard: Storyboard,
        fps: Int,
        holdFrames: Int,
    ): Int {
        val beatCount = storyboard.indices.size
        val holdTotal = beatCount * holdFrames
        if (beatCount <= 1) return holdTotal

        var animatic: Animatic? = null
        var sceneScope: CoroutineScope? = null
        val scene = ImageComposeScene(
            width = 64,
            height = 36,
            density = storyboard.format.density,
            coroutineContext = Dispatchers.Unconfined,
        ) {
            sceneScope = rememberCoroutineScope()
            val a = rememberAnimatic(storyboard = { storyboard })
            animatic = a
            Easel(a, mode = SceneMode.Preview)
        }
        var transitionFrames = 0
        try {
            scene.render(0L).close()
            val activeAnimatic = animatic ?: return holdTotal
            val activeScope = sceneScope ?: return holdTotal
            val job = activeScope.launch {
                val durations = activeAnimatic.measurePlaybackDurationsNanos()
                val frameNanos = 1_000_000_000.0 / fps.toDouble()
                for (duration in durations) {
                    if (duration > 0L) {
                        transitionFrames += ceil(duration.toDouble() / frameNanos).toInt()
                    }
                }
            }
            try {
                var frames = 0
                while (!job.isCompleted && frames < MAX_MEASUREMENT_FRAMES) {
                    val timeNanos = (++frames).toLong() * 1_000_000_000L / fps
                    scene.render(timeNanos).close()
                    if (frames % 16 == 0) yield()
                }
            } finally {
                job.cancel()
            }
        } finally {
            scene.close()
        }
        return holdTotal + transitionFrames
    }
}

internal suspend fun defaultCheckFfmpegAvailable(): Boolean =
    runInterruptible(Dispatchers.IO) {
        try {
            val process = ProcessBuilder("ffmpeg", "-version")
                .redirectErrorStream(true)
                .start()
            process.inputStream.use { it.readBytes() }
            val exited = process.waitFor(5, TimeUnit.SECONDS)
            if (!exited) {
                process.destroyForcibly()
                false
            } else {
                process.exitValue() == 0
            }
        } catch (_: IOException) {
            false
        }
    }

internal class FfmpegVideoFrameSink(
    private val outputPath: Path,
    width: Int,
    height: Int,
    fps: Int,
) : VideoFrameSink {
    private val tempOutput: Path
    private val logFile: Path
    private val process: Process
    private val stdin: BufferedOutputStream

    @Volatile
    private var finished = false

    @Volatile
    private var aborted = false

    init {
        val absoluteOutput = outputPath.toAbsolutePath()
        val parent = absoluteOutput.parent
            ?: throw IOException("Invalid output path: $outputPath")
        if (!Files.isDirectory(parent)) {
            throw IOException("Output directory does not exist: $parent")
        }

        tempOutput = Files.createTempFile(parent, ".storyboard-export-", ".mp4")
        logFile = Files.createTempFile(parent, ".storyboard-ffmpeg-", ".log")

        process = try {
            ProcessBuilder(
                "ffmpeg",
                "-y",
                "-f", "rawvideo",
                "-pix_fmt", "rgba",
                "-s", "${width}x${height}",
                "-r", fps.toString(),
                "-i", "-",
                "-vf", "pad=ceil(iw/2)*2:ceil(ih/2)*2",
                "-c:v", "libx264",
                "-preset", "fast",
                "-crf", "18",
                "-pix_fmt", "yuv420p",
                "-movflags", "+faststart",
                tempOutput.toString(),
            )
                .redirectErrorStream(true)
                .redirectOutput(logFile.toFile())
                .start()
        } catch (e: IOException) {
            runCatching { Files.deleteIfExists(tempOutput) }
            runCatching { Files.deleteIfExists(logFile) }
            throw e
        }

        stdin = BufferedOutputStream(process.outputStream, 1024 * 1024)
    }

    override suspend fun writeFrame(rgbaBytes: ByteArray) {
        runInterruptible(Dispatchers.IO) {
            stdin.write(rgbaBytes)
        }
    }

    override suspend fun finish() {
        runInterruptible(Dispatchers.IO) {
            try {
                stdin.flush()
                stdin.close()
                val exitCode = process.waitFor()
                if (exitCode != 0) {
                    val logTail = readLogTail()
                    val detail = if (logTail.isNotBlank()) ": $logTail" else ""
                    throw IOException("ffmpeg exited with code $exitCode$detail")
                }
                if (!tempOutput.exists() || tempOutput.fileSize() <= 0L) {
                    throw IOException("ffmpeg produced an empty output file")
                }
                Files.move(tempOutput, outputPath, StandardCopyOption.REPLACE_EXISTING)
                finished = true
            } finally {
                runCatching { Files.deleteIfExists(logFile) }
            }
        }
    }

    override fun abort() {
        if (finished || aborted) return
        aborted = true
        runCatching { stdin.close() }
        if (process.isAlive) {
            process.destroy()
            val exited = runCatching { process.waitFor(500, TimeUnit.MILLISECONDS) }.getOrDefault(false)
            if (!exited) {
                process.destroyForcibly()
            }
        }
        runCatching { Files.deleteIfExists(tempOutput) }
        runCatching { Files.deleteIfExists(logFile) }
    }

    private fun readLogTail(): String =
        runCatching {
            if (logFile.exists()) {
                logFile.readLines().takeLast(6).joinToString(" | ")
            } else {
                ""
            }
        }.getOrDefault("")
}
