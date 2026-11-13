package dev.sebastiano.ciacola.qa

import androidx.compose.ui.awt.ComposeWindow
import java.awt.Component
import java.awt.Container
import java.awt.EventQueue
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import java.io.File
import java.io.FileOutputStream
import java.lang.ref.Reference
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.util.Locale
import javax.imageio.ImageIO
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import dev.sebastiano.spectre.recording.AutoRecorder
import dev.sebastiano.spectre.recording.RecordingOptions
import dev.sebastiano.spectre.recording.screencapturekit.asTitledWindow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.skiko.SkiaLayer
import org.jetbrains.skiko.toBufferedImage

/** Shared QA paths resolved from environment/system properties or `build/qa`. */
object QaPaths {
    val DEFAULT_QA_ROOT: String = File("build/qa").absolutePath

    fun qaRoot(): File {
        val raw = System.getenv("CIACOLA_QA_ROOT")?.takeIf { it.isNotBlank() }
            ?: System.getProperty("ciacola.qa.root")?.takeIf { it.isNotBlank() }
            ?: DEFAULT_QA_ROOT
        return File(raw)
    }

    fun resolveWorker(explicit: String? = null): String =
        explicit?.takeIf { it.isNotBlank() }
            ?: System.getenv("CIACOLA_QA_WORKER")?.takeIf { it.isNotBlank() }
            ?: System.getProperty("ciacola.qa.worker")?.takeIf { it.isNotBlank() }
            ?: "explorer"

    fun evidenceDir(worker: String = resolveWorker(), qaRoot: File = qaRoot()): File =
        File(File(qaRoot, "evidence"), worker)

    fun findingsDir(qaRoot: File = qaRoot()): File = File(qaRoot, "findings")

    fun findingsFile(worker: String = resolveWorker(), qaRoot: File = qaRoot()): File =
        File(findingsDir(qaRoot), "$worker.jsonl")

    /** Formats [file] as a `$QA`-relative path (`evidence/<worker>/...`) when under [qaRoot]. */
    fun toEvidenceRelativePath(
        file: File,
        worker: String = resolveWorker(),
        qaRoot: File = qaRoot(),
    ): String {
        val rootPath = qaRoot.canonicalFile.toPath()
        val filePath = file.canonicalFile.toPath()
        return if (filePath.startsWith(rootPath)) {
            rootPath.relativize(filePath).toString().replace(File.separatorChar, '/')
        } else {
            "evidence/$worker/${file.name}"
        }
    }

    /** Normalizes a raw evidence string (relative path, bare file name, or absolute `$QA` path). */
    fun normalizeEvidenceEntry(
        raw: String,
        worker: String = resolveWorker(),
        qaRoot: File = qaRoot(),
    ): String {
        val trimmed = raw.trim()
        require(trimmed.isNotEmpty()) { "Evidence entry must not be blank" }
        val asFile = File(trimmed)
        if (asFile.isAbsolute) {
            return toEvidenceRelativePath(asFile, worker, qaRoot)
        }
        val slashNormalized = trimmed.replace('\\', '/').removePrefix("./")
        return if ('/' in slashNormalized) slashNormalized else "evidence/$worker/$slashNormalized"
    }
}

enum class QaArea(val wireName: String) {
    TimelineScrub("timeline-scrub"),
    TimelineUi("timeline-ui"),
    Inspector("inspector"),
    Stage("stage"),
    Navigation("navigation"),
    Overview("overview"),
    Review("review"),
    Theme("theme"),
    HotReload("hot-reload"),
    Perf("perf"),
    A11y("a11y"),
    Visual("visual"),
    Competitive("competitive"),
}

enum class QaFindingType(val wireName: String) {
    Crash("crash"),
    Bug("bug"),
    Visual("visual"),
    Ux("ux"),
    Perf("perf"),
    Gap("gap"),
}

enum class QaSeverity(val wireName: String) {
    Blocker("blocker"),
    Critical("critical"),
    Major("major"),
    Minor("minor"),
    Polish("polish"),
}

enum class QaRepro(val wireName: String) {
    Always("always"),
    Often("often"),
    Sometimes("sometimes"),
    Once("once"),
}

enum class QaConfidence(val wireName: String) {
    High("high"),
    Medium("medium"),
    Low("low"),
}

@Serializable
data class QaFinding(
    val id: String,
    val title: String,
    val area: String,
    val type: String,
    val severity: String,
    val steps: List<String>,
    val expected: String,
    val actual: String,
    val evidence: List<String>,
    val repro: String,
    val confidence: String,
    val notes: String,
    val reporter: String,
    val timestamp: String = Instant.now().toString(),
    @SerialName("see_also")
    val seeAlso: List<String>? = null,
    @SerialName("status_hint")
    val statusHint: String? = null,
)

data class QaRecording(
    val name: String,
    val mp4: File,
    val gif: File,
    val mp4EvidencePath: String,
    val gifEvidencePath: String,
    val frameCount: Int,
    val effectiveFps: Double,
)

private val findingJson = Json {
    encodeDefaults = true
    explicitNulls = false
}

private val findingFileLock = Any()

private val validAreas = QaArea.entries.map { it.wireName }.toSet()
private val validTypes = QaFindingType.entries.map { it.wireName }.toSet()
private val validSeverities = QaSeverity.entries.map { it.wireName }.toSet()
private val validRepros = QaRepro.entries.map { it.wireName }.toSet()
private val validConfidences = QaConfidence.entries.map { it.wireName }.toSet()

internal fun findSkiaLayer(root: Component?): SkiaLayer? {
    if (root == null) return null
    if (root is SkiaLayer) return root
    if (root is Container) {
        for (child in root.components) {
            findSkiaLayer(child)?.let { return it }
        }
    }
    return null
}

internal suspend fun <T> runOnEdt(block: () -> T): T {
    if (EventQueue.isDispatchThread()) return block()
    return suspendCancellableCoroutine { cont ->
        EventQueue.invokeLater {
            if (!cont.isActive) return@invokeLater
            runCatching(block).fold(cont::resume, cont::resumeWithException)
        }
    }
}

internal fun captureSkiaWindowFrameOnEdt(window: ComposeWindow): BufferedImage? {
    check(EventQueue.isDispatchThread()) { "SkiaLayer capture must execute on the AWT EDT" }
    if (!window.isDisplayable || !window.isShowing) return null
    val layer = findSkiaLayer(window) ?: return null
    val bitmap = layer.screenshot() ?: return null
    return try {
        // Skiko's Bitmap.toBufferedImage() copies native pixels into a fresh heap int[] raster.
        bitmap.toBufferedImage()
    } finally {
        Reference.reachabilityFence(bitmap)
        bitmap.close()
    }
}

/**
 * Window-only screenshot via [SkiaLayer.screenshot], never OS desktop capture.
 * Returns device-pixel ARGB content matching [ComposeWindow]'s own surface.
 */
suspend fun screenshot(
    window: ComposeWindow,
    settleDelayMs: Long = 60L,
): BufferedImage {
    if (settleDelayMs > 0L) delay(settleDelayMs)
    repeat(6) { attempt ->
        val captured = runOnEdt { captureSkiaWindowFrameOnEdt(window) }
        if (captured != null) return captured
        if (attempt < 5) delay(30L)
    }
    error("No SkiaLayer screenshot available for window '${window.title}'")
}

/** Saves a window-only PNG screenshot into [evidenceDir] and returns the written [File]. */
suspend fun saveScreenshot(
    window: ComposeWindow,
    name: String,
    evidenceDir: File = QaPaths.evidenceDir(),
    settleDelayMs: Long = 60L,
): File {
    val fileName = if (name.endsWith(".png", ignoreCase = true)) name else "$name.png"
    evidenceDir.mkdirs()
    val target = File(evidenceDir, fileName)
    val image = screenshot(window = window, settleDelayMs = settleDelayMs)
    withContext(Dispatchers.IO) {
        check(ImageIO.write(image, "png", target)) { "Failed to write PNG to $target" }
    }
    return target
}

/**
 * Captures frames of [window]'s [SkiaLayer] in the background at [fps] while [block] runs,
 * then encodes both `.mp4` (H.264 `yuv420p`) and `.gif` (`<= maxGifWidthPx` wide) via `ffmpeg`
 * into [evidenceDir].
 */
suspend fun record(
    window: ComposeWindow,
    name: String,
    fps: Int = 20,
    evidenceDir: File = QaPaths.evidenceDir(),
    worker: String = QaPaths.resolveWorker(),
    qaRoot: File = QaPaths.qaRoot(),
    maxGifWidthPx: Int = 960,
    block: suspend () -> Unit,
): QaRecording {
    require(fps in 1..60) { "fps must be in 1..60 (got $fps)" }
    val stem = name
        .removeSuffix(".gif")
        .removeSuffix(".GIF")
        .removeSuffix(".mp4")
        .removeSuffix(".MP4")
    require(stem.isNotBlank()) { "Recording name must not be blank" }

    evidenceDir.mkdirs()
    val mp4File = File(evidenceDir, "$stem.mp4")
    val gifFile = File(evidenceDir, "$stem.gif")

    // Prefer Spectre's native ScreenCaptureKit AutoRecorder (zero AWT-EDT overhead, 60fps hardware
    // window capture with the macOS purple semaphore indicator) whenever UIElement accessory mode is off.
    if (!System.getProperty("apple.awt.UIElement").equals("true", ignoreCase = true)) {
        val sckRecording = runCatching {
            recordWithSpectreScreenCaptureKit(
                window = window,
                name = stem,
                fps = fps,
                mp4File = mp4File,
                gifFile = gifFile,
                worker = worker,
                qaRoot = qaRoot,
                maxGifWidthPx = maxGifWidthPx,
                block = block,
            )
        }.getOrNull()
        if (sckRecording != null) return sckRecording
    }

    val tempDir = withContext(Dispatchers.IO) { Files.createTempDirectory("ciacola-qa-rec-") }

    try {
        val initialFrame = screenshot(window = window, settleDelayMs = 16L)
        val width = initialFrame.width
        val height = initialFrame.height
        val rawFile = tempDir.resolve("frames.argb").toFile()

        val frameChannel = Channel<BufferedImage>(
            capacity = 8,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
        var writtenFrames = 0
        val bgScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        val writerJob = bgScope.launch {
            FileOutputStream(rawFile).channel.use { fileChannel ->
                val directBuffer = ByteBuffer.allocateDirect(width * height * 4)
                    .order(ByteOrder.BIG_ENDIAN)
                var scratchImage: BufferedImage? = null

                fun writeImg(image: BufferedImage) {
                    val argb = if (
                        image.width == width &&
                        image.height == height &&
                        image.type == BufferedImage.TYPE_INT_ARGB
                    ) {
                        image
                    } else {
                        val canvas = scratchImage
                            ?: BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB).also {
                                scratchImage = it
                            }
                        val g = canvas.createGraphics()
                        try {
                            g.clearRect(0, 0, width, height)
                            g.drawImage(image, 0, 0, width, height, null)
                        } finally {
                            g.dispose()
                        }
                        canvas
                    }
                    val pixels = (argb.raster.dataBuffer as DataBufferInt).data
                    directBuffer.clear()
                    directBuffer.asIntBuffer().put(pixels)
                    directBuffer.limit(pixels.size * 4)
                    while (directBuffer.hasRemaining()) {
                        fileChannel.write(directBuffer)
                    }
                    writtenFrames++
                }

                for (frame in frameChannel) {
                    writeImg(frame)
                }
                if (writtenFrames == 1) {
                    // Duplicate single frame so short blocks still encode a valid stream.
                    writeImg(initialFrame)
                }
            }
        }

        frameChannel.send(initialFrame)
        val startNanos = System.nanoTime()

        val samplerJob = CoroutineScope(Dispatchers.Default + SupervisorJob()).launch {
            val intervalNanos = 1_000_000_000L / fps
            var nextDeadline = System.nanoTime() + intervalNanos
            while (isActive) {
                val sleepMs = (nextDeadline - System.nanoTime()) / 1_000_000L
                if (sleepMs > 0L) delay(sleepMs)
                nextDeadline += intervalNanos
                val now = System.nanoTime()
                if (nextDeadline < now - intervalNanos) {
                    nextDeadline = now + intervalNanos
                }
                val captured = runCatching { runOnEdt { captureSkiaWindowFrameOnEdt(window) } }.getOrNull()
                if (captured != null) {
                    frameChannel.trySend(captured)
                }
            }
        }

        var blockFailure: Throwable? = null
        try {
            block()
        } catch (t: Throwable) {
            blockFailure = t
        } finally {
            samplerJob.cancel()
            samplerJob.join()
        }

        if (blockFailure != null) {
            frameChannel.close()
            writerJob.join()
            bgScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
            throw blockFailure
        }

        runCatching { runOnEdt { captureSkiaWindowFrameOnEdt(window) } }
            .getOrNull()
            ?.let { frameChannel.send(it) }
        frameChannel.close()
        writerJob.join()
        bgScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()

        val elapsedSeconds = ((System.nanoTime() - startNanos) / 1_000_000_000.0)
            .coerceAtLeast(1.0 / fps)
        val effectiveFps = if (writtenFrames >= 3) {
            ((writtenFrames - 1) / elapsedSeconds).coerceIn(1.0, fps.toDouble())
        } else {
            fps.toDouble()
        }
        val fpsArg = String.format(Locale.US, "%.2f", effectiveFps)

        withContext(Dispatchers.IO) {
            runFfmpeg(
                "-y",
                "-f", "rawvideo",
                "-pix_fmt", "argb",
                "-video_size", "${width}x${height}",
                "-framerate", fpsArg,
                "-i", rawFile.absolutePath,
                "-vf", "scale=trunc(iw/2)*2:trunc(ih/2)*2",
                "-c:v", "libx264",
                "-preset", "veryfast",
                "-crf", "20",
                "-pix_fmt", "yuv420p",
                "-movflags", "+faststart",
                mp4File.absolutePath,
            )
            runFfmpeg(
                "-y",
                "-f", "rawvideo",
                "-pix_fmt", "argb",
                "-video_size", "${width}x${height}",
                "-framerate", fpsArg,
                "-i", rawFile.absolutePath,
                "-vf", "scale='min($maxGifWidthPx,iw)':-1:flags=lanczos,split[s0][s1];[s0]palettegen=stats_mode=diff[p];[s1][p]paletteuse=dither=bayer:bayer_scale=5:diff_mode=rectangle",
                "-loop", "0",
                gifFile.absolutePath,
            )
        }

        check(mp4File.isFile && mp4File.length() > 0L) { "MP4 output missing or empty: $mp4File" }
        check(gifFile.isFile && gifFile.length() > 0L) { "GIF output missing or empty: $gifFile" }

        return QaRecording(
            name = stem,
            mp4 = mp4File,
            gif = gifFile,
            mp4EvidencePath = QaPaths.toEvidenceRelativePath(mp4File, worker = worker, qaRoot = qaRoot),
            gifEvidencePath = QaPaths.toEvidenceRelativePath(gifFile, worker = worker, qaRoot = qaRoot),
            frameCount = writtenFrames,
            effectiveFps = effectiveFps,
        )
    } finally {
        withContext(Dispatchers.IO) {
            runCatching { tempDir.toFile().deleteRecursively() }
        }
    }
}

private suspend fun recordWithSpectreScreenCaptureKit(
    window: ComposeWindow,
    name: String,
    fps: Int,
    mp4File: File,
    gifFile: File,
    worker: String,
    qaRoot: File,
    maxGifWidthPx: Int,
    block: suspend () -> Unit,
): QaRecording {
    val recorder = AutoRecorder()
    val options = RecordingOptions(frameRate = fps.coerceIn(1, 60))
    val startNanos = System.nanoTime()
    val handle = withContext(Dispatchers.IO) {
        recorder.startWindow(
            window = window.asTitledWindow(),
            output = mp4File.toPath(),
            options = options,
        )
    }
    var blockFailure: Throwable? = null
    try {
        block()
    } catch (t: Throwable) {
        blockFailure = t
    } finally {
        withContext(Dispatchers.IO) {
            runCatching { handle.stop() }
        }
    }
    if (blockFailure != null) throw blockFailure

    check(mp4File.isFile && mp4File.length() > 0L) {
        "Spectre ScreenCaptureKit MP4 missing or empty: $mp4File"
    }
    withContext(Dispatchers.IO) {
        runFfmpeg(
            "-y",
            "-i", mp4File.absolutePath,
            "-vf", "fps=${fps.coerceAtMost(24)},scale='min($maxGifWidthPx,iw)':-1:flags=lanczos,split[s0][s1];[s0]palettegen=stats_mode=diff[p];[s1][p]paletteuse=dither=bayer:bayer_scale=5:diff_mode=rectangle",
            "-loop", "0",
            gifFile.absolutePath,
        )
    }
    check(gifFile.isFile && gifFile.length() > 0L) {
        "GIF output missing or empty: $gifFile"
    }
    val elapsedSeconds = ((System.nanoTime() - startNanos) / 1_000_000_000.0).coerceAtLeast(0.1)
    val approxFrames = (elapsedSeconds * fps).toInt().coerceAtLeast(1)
    return QaRecording(
        name = name,
        mp4 = mp4File,
        gif = gifFile,
        mp4EvidencePath = QaPaths.toEvidenceRelativePath(mp4File, worker = worker, qaRoot = qaRoot),
        gifEvidencePath = QaPaths.toEvidenceRelativePath(gifFile, worker = worker, qaRoot = qaRoot),
        frameCount = approxFrames,
        effectiveFps = fps.toDouble(),
    )
}

private fun resolveFfmpegBinary(): String {
    System.getenv("FFMPEG_BIN")?.takeIf { it.isNotBlank() && File(it).canExecute() }?.let { return it }
    val candidates = buildList {
        System.getenv("PATH")
            ?.split(File.pathSeparator)
            ?.filter { it.isNotBlank() }
            ?.forEach { dir -> add(File(dir, "ffmpeg").absolutePath) }
        add("/opt/homebrew/bin/ffmpeg")
        add("/usr/local/bin/ffmpeg")
        add("/usr/bin/ffmpeg")
    }
    return candidates.firstOrNull { File(it).canExecute() } ?: "ffmpeg"
}

private fun runFfmpeg(vararg args: String) {
    val command = buildList {
        add(resolveFfmpegBinary())
        add("-hide_banner")
        add("-loglevel")
        add("error")
        addAll(args)
    }
    val proc = ProcessBuilder(command)
        .redirectErrorStream(true)
        .start()
    val output = proc.inputStream.bufferedReader(StandardCharsets.UTF_8).readText()
    val exit = proc.waitFor()
    check(exit == 0) {
        "ffmpeg exited with code $exit for command ${command.joinToString(" ")}:\n$output"
    }
}

/**
 * Appends a single JSON line to `$QA/findings/<worker>.jsonl` conforming to the shared QA schema.
 * If [id] is omitted, assigns the next sequential `F-<worker>-NNN` ID in [findingsFile].
 */
fun finding(
    title: String,
    area: String,
    type: String,
    severity: String,
    steps: List<String>,
    expected: String,
    actual: String,
    evidence: List<String> = emptyList(),
    repro: String = QaRepro.Always.wireName,
    confidence: String = QaConfidence.High.wireName,
    notes: String = "",
    id: String? = null,
    seeAlso: List<String>? = null,
    worker: String = QaPaths.resolveWorker(),
    qaRoot: File = QaPaths.qaRoot(),
    findingsFile: File = QaPaths.findingsFile(worker = worker, qaRoot = qaRoot),
): QaFinding {
    require(title.isNotBlank()) { "Finding title must not be blank" }
    require(area in validAreas) { "Invalid area '$area'; expected one of $validAreas" }
    require(type in validTypes) { "Invalid type '$type'; expected one of $validTypes" }
    require(severity in validSeverities) { "Invalid severity '$severity'; expected one of $validSeverities" }
    require(repro in validRepros) { "Invalid repro '$repro'; expected one of $validRepros" }
    require(confidence in validConfidences) { "Invalid confidence '$confidence'; expected one of $validConfidences" }

    val normalizedEvidence = evidence.map {
        QaPaths.normalizeEvidenceEntry(it, worker = worker, qaRoot = qaRoot)
    }

    return synchronized(findingFileLock) {
        findingsFile.parentFile?.mkdirs()
        val resolvedId = id?.takeIf { it.isNotBlank() } ?: nextFindingId(worker, findingsFile)
        val entry = QaFinding(
            id = resolvedId,
            title = title,
            area = area,
            type = type,
            severity = severity,
            steps = steps,
            expected = expected,
            actual = actual,
            evidence = normalizedEvidence,
            repro = repro,
            confidence = confidence,
            notes = notes,
            reporter = worker,
            timestamp = Instant.now().toString(),
            seeAlso = seeAlso?.takeIf { it.isNotEmpty() },
        )
        val line = findingJson.encodeToString(QaFinding.serializer(), entry) + "\n"
        Files.writeString(
            findingsFile.toPath(),
            line,
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND,
        )
        entry
    }
}

/** Type-safe overload of [finding] using QA schema enums. */
fun finding(
    title: String,
    area: QaArea,
    type: QaFindingType,
    severity: QaSeverity,
    steps: List<String>,
    expected: String,
    actual: String,
    evidence: List<String> = emptyList(),
    repro: QaRepro = QaRepro.Always,
    confidence: QaConfidence = QaConfidence.High,
    notes: String = "",
    id: String? = null,
    seeAlso: List<String>? = null,
    worker: String = QaPaths.resolveWorker(),
    qaRoot: File = QaPaths.qaRoot(),
    findingsFile: File = QaPaths.findingsFile(worker = worker, qaRoot = qaRoot),
): QaFinding = finding(
    title = title,
    area = area.wireName,
    type = type.wireName,
    severity = severity.wireName,
    steps = steps,
    expected = expected,
    actual = actual,
    evidence = evidence,
    repro = repro.wireName,
    confidence = confidence.wireName,
    notes = notes,
    id = id,
    seeAlso = seeAlso,
    worker = worker,
    qaRoot = qaRoot,
    findingsFile = findingsFile,
)

private fun nextFindingId(worker: String, findingsFile: File): String {
    if (!findingsFile.isFile) return "F-$worker-001"
    val prefix = "F-$worker-"
    val maxExisting = findingsFile.useLines { lines ->
        lines.mapNotNull { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty()) return@mapNotNull null
            runCatching { findingJson.decodeFromString(QaFinding.serializer(), trimmed) }
                .getOrNull()
                ?.id
                ?.takeIf { it.startsWith(prefix) }
                ?.removePrefix(prefix)
                ?.takeWhile { it.isDigit() }
                ?.toIntOrNull()
        }.maxOrNull() ?: 0
    }
    return "F-$worker-${(maxExisting + 1).toString().padStart(3, '0')}"
}

fun BufferedImage.cropTop(bottomPx: Int): BufferedImage =
    getSubimage(0, 0, width, bottomPx.coerceIn(1, height))

/** Fraction of pixels whose RGB channels differ by more than [channelTolerance] (default ~6%). */
fun differingFraction(
    a: BufferedImage,
    b: BufferedImage,
    channelTolerance: Int = 16,
): Double {
    require(a.width == b.width && a.height == b.height) {
        "Image sizes differ: ${a.width}x${a.height} vs ${b.width}x${b.height}"
    }
    var differing = 0
    for (y in 0 until a.height) {
        for (x in 0 until a.width) {
            val p = a.getRGB(x, y)
            val q = b.getRGB(x, y)
            val d = maxOf(
                abs((p shr 16 and 0xFF) - (q shr 16 and 0xFF)),
                abs((p shr 8 and 0xFF) - (q shr 8 and 0xFF)),
                abs((p and 0xFF) - (q and 0xFF)),
            )
            if (d > channelTolerance) differing++
        }
    }
    return differing.toDouble() / (a.width * a.height)
}
