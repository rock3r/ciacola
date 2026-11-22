package dev.bnorm.storyboard.easel.export

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.IntSize
import dev.bnorm.storyboard.AdvanceDirection
import dev.bnorm.storyboard.Storyboard
import dev.bnorm.storyboard.easel.Animatic
import dev.bnorm.storyboard.easel.Easel
import dev.bnorm.storyboard.easel.LocalExportEmittedFrameIndex
import dev.bnorm.storyboard.easel.LocalExportFps
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
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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

/**
 * Frame-accurate span emitted for each beat during [StoryboardVideoExporter.exportAsMp4].
 */
data class BeatExportSpan(
    val beatOrdinal: Int,
    val index: Storyboard.Index,
    val transitionStartFrame: Int,
    val holdStartFrame: Int,
    val endFrameExclusive: Int,
    val fps: Int,
) {
    val transitionStartSeconds: Double get() = transitionStartFrame.toDouble() / fps
    val holdStartSeconds: Double get() = holdStartFrame.toDouble() / fps
    val endSeconds: Double get() = endFrameExclusive.toDouble() / fps
}

private class MissingFfmpegException : IOException(FFMPEG_MISSING_MESSAGE)

class StoryboardVideoExporter(
    private val coordinator: ExportCoordinator = ExportCoordinator(),
    private val fps: Int = DEFAULT_FPS,
    private val beatHoldMillis: Long = DEFAULT_BEAT_HOLD_MILLIS,
    private val ffmpegChecker: suspend () -> Boolean = ::defaultCheckFfmpegAvailable,
    private val sinkFactory: (path: Path, width: Int, height: Int, fps: Int) -> VideoFrameSink =
        ::FfmpegVideoFrameSink,
    private val holdDurationForState: ((Storyboard.Index) -> Duration)? = null,
    private val outputSize: IntSize? = null,
    private val onBeatSpan: ((BeatExportSpan) -> Unit)? = null,
) {
    init {
        require(fps > 0) { "fps must be positive, got $fps" }
        require(beatHoldMillis >= 0L) { "beatHoldMillis must be non-negative, got $beatHoldMillis" }
        if (outputSize != null) {
            require(outputSize.width > 0 && outputSize.height > 0) {
                "outputSize dimensions must be positive, got ${outputSize.width}x${outputSize.height}"
            }
        }
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

    /** Headlessly exports [storyboard] directly to [path] without opening a save dialog. */
    suspend fun exportAsMp4(
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

        val targetSize = outputSize ?: storyboard.format.size
        val width = targetSize.width
        val height = targetSize.height
        val holdFramesPerBeat = IntArray(indices.size) { beatIdx ->
            computeHoldFrames(
                index = indices[beatIdx],
                fps = fps,
                beatHoldMillis = beatHoldMillis,
                holdDurationForState = holdDurationForState,
            )
        }
        val holdTotal = holdFramesPerBeat.sum()

        updateStatus(ExportStatus.Exporting(0f, "Preparing MP4 export\u2026"))
        yield()

        val estimatedTotalFrames = estimateTotalFrames(storyboard, fps, holdFramesPerBeat)
            .coerceAtLeast(holdTotal)

        var emittedFrameIndex by mutableLongStateOf(0L)
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
            CompositionLocalProvider(
                LocalExportEmittedFrameIndex provides emittedFrameIndex,
                LocalExportFps provides fps,
            ) {
                Easel(a, mode = SceneMode.Preview)
            }
        }

        val rgbaInfo = ImageInfo(width, height, ColorType.RGBA_8888, ColorAlphaType.PREMUL)
        val bitmap = Bitmap().apply { allocPixels(rgbaInfo) }
        var sink: VideoFrameSink? = null
        var finishedCleanly = false

        try {
            currentCoroutineContext().ensureActive()
            val activeSink = withContext(NonCancellable + Dispatchers.IO) {
                sinkFactory(path, width, height, fps).also { sink = it }
            }
            currentCoroutineContext().ensureActive()
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
                // Advance the observable emitted-frame counter only after frame `k` is emitted so
                // every render attempt for frame `k+1` (including settle retries) observes `k+1`.
                emittedFrameIndex = renderedFrames.toLong()
                Snapshot.sendApplyNotifications()
                val totalDenom = maxOf(estimatedTotalFrames, renderedFrames)
                updateStatus(
                    ExportStatus.Exporting(
                        progress = (renderedFrames.toFloat() / totalDenom).coerceIn(0f, 0.99f),
                        message = "Rendering MP4 ($renderedFrames / $totalDenom frames)\u2026",
                    )
                )
            }

            // Initial render and settle on Beat 0 (emittedFrameIndex == 0L throughout settle).
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
            val beat0StartFrame = renderedFrames
            writeRenderedImage(initialImage)
            yield()
            repeat(holdFramesPerBeat[0] - 1) {
                val holdImage = scene.render(nextTimeNanos())
                writeRenderedImage(holdImage)
                yield()
            }
            onBeatSpan?.invoke(
                BeatExportSpan(
                    beatOrdinal = 0,
                    index = indices[0],
                    transitionStartFrame = beat0StartFrame,
                    holdStartFrame = beat0StartFrame,
                    endFrameExclusive = renderedFrames,
                    fps = fps,
                ),
            )

            // Advance deterministically beat by beat until the final resting state.
            for (beatIdx in 1 until indices.size) {
                val transitionStartFrame = renderedFrames
                val advanceJob = activeScope.launch {
                    activeAnimatic.advance(AdvanceDirection.Forward)
                }
                var emittedCompletionFrame = false
                try {
                    var safetyFrames = 0
                    while (!advanceJob.isCompleted && safetyFrames++ < MAX_SEGMENT_FRAMES) {
                        val image = scene.render(nextTimeNanos())
                        if (activeAnimatic.scrubSettling) {
                            // Play-time-0 settle frames are not presentable video frames; do not
                            // emit or advance emittedFrameIndex.
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

                val holdStartFrame = if (emittedCompletionFrame) {
                    (renderedFrames - 1).coerceAtLeast(transitionStartFrame)
                } else {
                    renderedFrames
                }
                val beatHoldFrames = holdFramesPerBeat[beatIdx]
                val remainingHoldFrames = if (emittedCompletionFrame) beatHoldFrames - 1 else beatHoldFrames
                repeat(remainingHoldFrames.coerceAtLeast(0)) {
                    val holdImage = scene.render(nextTimeNanos())
                    writeRenderedImage(holdImage)
                    yield()
                }
                onBeatSpan?.invoke(
                    BeatExportSpan(
                        beatOrdinal = beatIdx,
                        index = indices[beatIdx],
                        transitionStartFrame = transitionStartFrame,
                        holdStartFrame = holdStartFrame,
                        endFrameExclusive = renderedFrames,
                        fps = fps,
                    ),
                )
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

    internal suspend fun estimateTotalFrames(storyboard: Storyboard): Int {
        val indices = storyboard.indices
        if (indices.isEmpty()) return 0
        val holdFramesPerBeat = IntArray(indices.size) { beatIdx ->
            computeHoldFrames(
                index = indices[beatIdx],
                fps = fps,
                beatHoldMillis = beatHoldMillis,
                holdDurationForState = holdDurationForState,
            )
        }
        return estimateTotalFrames(storyboard, fps, holdFramesPerBeat)
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private suspend fun estimateTotalFrames(
        storyboard: Storyboard,
        fps: Int,
        holdFramesPerBeat: IntArray,
    ): Int {
        val beatCount = storyboard.indices.size
        val holdTotal = holdFramesPerBeat.sum()
        if (beatCount <= 1) return holdTotal

        var animatic: Animatic? = null
        var sceneScope: CoroutineScope? = null
        // Measurement pass intentionally leaves LocalExportEmittedFrameIndex / LocalExportFps null
        // so non-emitted measurement renders never advance emitted-frame time.
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
                for (duration in durations) {
                    transitionFrames += transitionFramesForDurationNanos(duration, fps)
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

/**
 * Computes the hold frame count for [index].
 *
 * - When [holdDurationForState] is `null`, preserves legacy truncating arithmetic
 *   `((beatHoldMillis * fps) / 1000L).toInt().coerceAtLeast(1)`.
 * - When [holdDurationForState] is supplied, rounds `(duration * fps)` to the nearest integer frame
 *   count (half-up) with a minimum of `1` frame (so `Duration.ZERO` / `autoAdvanceMs = 0` yields `1`).
 */
internal fun computeHoldFrames(
    index: Storyboard.Index,
    fps: Int,
    beatHoldMillis: Long,
    holdDurationForState: ((Storyboard.Index) -> Duration)?,
): Int {
    if (holdDurationForState == null) {
        return ((beatHoldMillis * fps) / 1000L).toInt().coerceAtLeast(1)
    }
    val duration = holdDurationForState(index)
    require(!duration.isNegative()) {
        "holdDurationForState($index) must be non-negative, got $duration"
    }
    if (duration == Duration.ZERO) return 1
    val wholeSecs = duration.inWholeSeconds
    val remNanos = (duration - wholeSecs.seconds).inWholeNanoseconds
    val rounded = wholeSecs * fps.toLong() + (remNanos * fps.toLong() + 500_000_000L) / 1_000_000_000L
    return rounded.coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
}

/**
 * Exact integer ceiling of `(durationNanos * fps / 1_000_000_000)` to avoid IEEE-754 rounding drift
 * on frame-aligned durations (e.g. `250ms` at `60fps` == exact `15` frames).
 */
internal fun transitionFramesForDurationNanos(durationNanos: Long, fps: Int): Int {
    if (durationNanos <= 0L) return 0
    val wholeSecs = durationNanos / 1_000_000_000L
    val remNanos = durationNanos % 1_000_000_000L
    val total = wholeSecs * fps.toLong() + (remNanos * fps.toLong() + 999_999_999L) / 1_000_000_000L
    return total.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
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

        var spawned: Process? = null
        try {
            val started = ProcessBuilder(
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
            spawned = started
            if (Thread.currentThread().isInterrupted) {
                throw IOException("Interrupted while spawning ffmpeg")
            }
            process = started
            stdin = BufferedOutputStream(started.outputStream, 1024 * 1024)
        } catch (t: Throwable) {
            spawned?.destroyForcibly()
            runCatching { Files.deleteIfExists(tempOutput) }
            runCatching { Files.deleteIfExists(logFile) }
            throw t
        }
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
