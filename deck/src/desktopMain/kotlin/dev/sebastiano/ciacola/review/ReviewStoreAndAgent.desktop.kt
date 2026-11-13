package dev.sebastiano.ciacola.review

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.isExecutable
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Color4f
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.Paint
import org.jetbrains.skia.PaintMode
import org.jetbrains.skia.Rect as SkiaRect
import org.jetbrains.skia.Surface

const val ReviewCommentHitForBake = "TIMELINE OVERRIDES BAKE"

enum class ConversationIdSource {
    None,
    Config,
    Environment,
}

data class ResolvedConversationId(
    val id: String,
    val source: ConversationIdSource,
    val sourceName: String = "",
) {
    val isConfigured: Boolean get() = id.isNotBlank()
    val isAutoDetected: Boolean get() = source == ConversationIdSource.Environment && id.isNotBlank()
}

internal val CONVERSATION_ID_ENV_VARS = listOf(
    "CIACOLA_CONVERSATION_ID",
    "AGENT_CONVERSATION_ID",
    "ANTIGRAVITY_CONVERSATION_ID",
    "CONVERSATION_ID",
)

class ReviewCommentStore(
    val storyboardDir: Path = Path.of(".storyboard"),
    val workspaceRoot: Path = Path.of("").toAbsolutePath(),
    private val envProvider: (String) -> String? = System::getenv,
    private val systemPropertyProvider: (String) -> String? = System::getProperty,
) {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    val commentsFile: Path = storyboardDir.resolve("review-comments.json")
    val configFile: Path = storyboardDir.resolve("review-config.json")
    val capturesDir: Path = storyboardDir.resolve("review-captures")

    val comments = SnapshotStateList<SlideReviewComment>()
    val trashedComments = SnapshotStateList<SlideReviewComment>()
    var config by mutableStateOf(ReviewConfig())
        private set

    private var lastObservedModifiedMillis: Long = -1L
    private var lastObservedFileSize: Long = -1L
    private var lastConfigModifiedMillis: Long = -1L
    private var lastConfigFileSize: Long = -1L

    init {
        config = readConfigInternal()
        reloadFromDisk()
    }

    fun loadConfig(): ReviewConfig {
        config = readConfigInternal(force = true)
        return config
    }

    fun saveConfig(newConfig: ReviewConfig) {
        runCatching {
            Files.createDirectories(storyboardDir)
            writeAtomically(configFile, json.encodeToString(newConfig) + "\n")
            lastConfigModifiedMillis = configFile.getLastModifiedTime().toMillis()
            lastConfigFileSize = configFile.fileSize()
        }
        config = newConfig
    }

    fun resolveConversationId(): ResolvedConversationId {
        val fromConfig = config.conversationId.trim()
        if (fromConfig.isNotEmpty()) {
            return ResolvedConversationId(
                id = fromConfig,
                source = ConversationIdSource.Config,
                sourceName = "review-config.json",
            )
        }
        for (envKey in CONVERSATION_ID_ENV_VARS) {
            val candidate = envProvider(envKey)?.trim()?.takeIf(String::isNotEmpty)
            if (candidate != null) {
                return ResolvedConversationId(
                    id = candidate,
                    source = ConversationIdSource.Environment,
                    sourceName = envKey,
                )
            }
        }
        val sysProp = systemPropertyProvider("ciacola.conversationId")?.trim()?.takeIf(String::isNotEmpty)
        if (sysProp != null) {
            return ResolvedConversationId(
                id = sysProp,
                source = ConversationIdSource.Environment,
                sourceName = "ciacola.conversationId",
            )
        }
        return ResolvedConversationId(id = "", source = ConversationIdSource.None)
    }

    fun effectiveConversationId(): String = resolveConversationId().id

    fun effectiveAgentApiBin(): String {
        val fromConfig = config.agentApiBin.trim()
        if (fromConfig.isNotEmpty()) return fromConfig
        val fromEnv = sequenceOf(
            envProvider("CIACOLA_AGENTAPI_BIN"),
            envProvider("AGENTAPI_BIN"),
        ).mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }
            .firstOrNull()
        if (fromEnv != null) return fromEnv

        val defaultJetskiBin = Path.of(
            System.getProperty("user.home", ""),
            ".gemini",
            "jetski",
            "bin",
            "agentapi",
        )
        return if (defaultJetskiBin.exists() && defaultJetskiBin.isExecutable()) {
            defaultJetskiBin.toAbsolutePath().toString()
        } else {
            "agentapi"
        }
    }

    @Synchronized
    fun reloadFromDisk(): Boolean {
        config = readConfigInternal()
        if (!commentsFile.exists()) {
            lastObservedModifiedMillis = -1L
            lastObservedFileSize = -1L
            return false
        }
        val modified = runCatching { commentsFile.getLastModifiedTime().toMillis() }.getOrDefault(-1L)
        val size = runCatching { commentsFile.fileSize() }.getOrDefault(-1L)
        val hasInFlightApplying = comments.any { it.status == ReviewCommentStatus.Applying }
        if (!hasInFlightApplying && modified == lastObservedModifiedMillis && size == lastObservedFileSize) {
            return false
        }
        lastObservedModifiedMillis = modified
        lastObservedFileSize = size
        val text = runCatching { commentsFile.readText() }.getOrNull() ?: return false
        if (text.isBlank()) return false
        val parsed = runCatching {
            json.decodeFromString<List<SlideReviewComment>>(text)
        }.getOrNull()
        if (parsed == null) {
            if (size > 0L) {
                runCatching {
                    val backup = commentsFile.resolveSibling("review-comments.corrupt.json")
                    Files.copy(commentsFile, backup, StandardCopyOption.REPLACE_EXISTING)
                }
            }
            return false
        }
        if (comments.toList() == parsed) return false

        comments.clear()
        comments.addAll(parsed)
        return true
    }

    @Synchronized
    private fun persistCommentsInternal(updated: List<SlideReviewComment>) {
        runCatching {
            Files.createDirectories(storyboardDir)
            writeAtomically(commentsFile, json.encodeToString(updated) + "\n")
            lastObservedModifiedMillis = commentsFile.getLastModifiedTime().toMillis()
            lastObservedFileSize = commentsFile.fileSize()
        }
        comments.clear()
        comments.addAll(updated)
    }

    suspend fun submitComment(
        comment: SlideReviewComment,
        dispatchImmediate: Boolean,
        pngBytes: ByteArray? = null,
    ): SlideReviewComment {
        val savedScreenshotPath = if (pngBytes != null && pngBytes.isNotEmpty()) {
            withContext(Dispatchers.IO) {
                Files.createDirectories(capturesDir)
                val targetFile = capturesDir.resolve("${comment.id}.png").toAbsolutePath()
                targetFile.writeBytes(pngBytes)
                targetFile.toString()
            }
        } else {
            comment.screenshotPath
        }

        val initialStatus = if (dispatchImmediate) {
            ReviewCommentStatus.Applying
        } else {
            ReviewCommentStatus.Queued
        }
        val prepared = comment.copy(
            status = initialStatus,
            screenshotPath = savedScreenshotPath,
            deliveryError = null,
        )
        val snapshot = synchronized(this) {
            val next = comments.toList() + prepared
            persistCommentsInternal(next)
            prepared
        }

        if (!dispatchImmediate) return snapshot
        return dispatchItemsToAgent(listOf(snapshot)).single()
    }

    suspend fun flushQueued(): Int {
        val queued = synchronized(this) {
            val pending = comments.filter {
                it.status == ReviewCommentStatus.Queued || it.status == ReviewCommentStatus.Failed
            }
            if (pending.isEmpty()) return 0
            val pendingIds = pending.map { it.id }.toSet()
            val updated = comments.map { c ->
                if (c.id in pendingIds) c.copy(status = ReviewCommentStatus.Applying, deliveryError = null) else c
            }
            persistCommentsInternal(updated)
            updated.filter { it.id in pendingIds }
        }
        dispatchItemsToAgent(queued)
        return queued.size
    }

    fun deleteComment(commentId: String) {
        synchronized(this) {
            val target = comments.firstOrNull { it.id == commentId }
            if (target != null && trashedComments.none { it.id == target.id }) {
                trashedComments.add(target)
                while (trashedComments.size > 20) {
                    trashedComments.removeAt(0)
                }
            }
            persistCommentsInternal(comments.filterNot { it.id == commentId })
        }
    }

    fun restoreComment(commentId: String): Boolean {
        synchronized(this) {
            val idx = trashedComments.indexOfFirst { it.id == commentId }
            if (idx < 0) return false
            val item = trashedComments.removeAt(idx)
            if (comments.none { it.id == item.id }) {
                persistCommentsInternal(comments.toList() + item)
            }
            return true
        }
    }

    fun restoreLastDeleted(): SlideReviewComment? {
        synchronized(this) {
            val last = trashedComments.lastOrNull() ?: return null
            trashedComments.removeAt(trashedComments.lastIndex)
            if (comments.none { it.id == last.id }) {
                persistCommentsInternal(comments.toList() + last)
            }
            return last
        }
    }

    fun dismissTrashItem(commentId: String) {
        synchronized(this) {
            trashedComments.removeAll { it.id == commentId }
        }
    }

    fun markCommentApplied(
        commentId: String,
        resolutionNote: String = "Marked applied manually",
    ) {
        synchronized(this) {
            var changed = false
            val updated = comments.map { existing ->
                if (existing.id == commentId) {
                    changed = true
                    existing.copy(
                        status = ReviewCommentStatus.Applied,
                        deliveryError = null,
                        resolution = existing.resolution.ifBlank { resolutionNote },
                    )
                } else {
                    existing
                }
            }
            if (changed) {
                persistCommentsInternal(updated)
            }
        }
    }

    fun clearAppliedComments() {
        synchronized(this) {
            persistCommentsInternal(comments.filterNot { it.status == ReviewCommentStatus.Applied })
        }
    }

    suspend fun retryComment(commentId: String) {
        val item = synchronized(this) {
            val target = comments.firstOrNull { it.id == commentId } ?: return
            val retrying = target.copy(status = ReviewCommentStatus.Applying, deliveryError = null)
            persistCommentsInternal(comments.map { if (it.id == commentId) retrying else it })
            retrying
        }
        dispatchItemsToAgent(listOf(item))
    }

    private suspend fun dispatchItemsToAgent(
        items: List<SlideReviewComment>,
    ): List<SlideReviewComment> = withContext(Dispatchers.IO) {
        val convId = effectiveConversationId()
        if (convId.isBlank()) {
            return@withContext markDeliveryFailure(
                items = items,
                fallbackStatus = ReviewCommentStatus.Queued,
                reason = "No Conversation ID configured. Open Review -> Panel (F4) to paste your Conversation ID, then click Send Queued.",
            )
        }

        val bin = effectiveAgentApiBin()
        val message = formatAgentMessage(items)
        val result = runCatching {
            val pb = ProcessBuilder(
                bin,
                "send-message",
                "--title=Ciacola Slide Review",
                convId,
                message,
            )
            pb.environment().remove("ANTIGRAVITY_SOURCE_METADATA")
            pb.directory(workspaceRoot.toFile())
            val proc = pb.start()
            val finished = proc.waitFor(15, TimeUnit.SECONDS)
            if (!finished) {
                proc.destroyForcibly()
                error("Timed out invoking agentapi after 15s")
            }
            val stderr = proc.errorStream.bufferedReader().readText().trim()
            val stdout = proc.inputStream.bufferedReader().readText().trim()
            if (proc.exitValue() != 0) {
                error("agentapi exited with ${proc.exitValue()}: ${stderr.ifBlank { stdout }}")
            }
        }

        if (result.isFailure) {
            val err = result.exceptionOrNull()?.message ?: "Unknown agentapi error"
            markDeliveryFailure(
                items = items,
                fallbackStatus = ReviewCommentStatus.Failed,
                reason = err.take(300),
            )
        } else {
            items
        }
    }

    private fun markDeliveryFailure(
        items: List<SlideReviewComment>,
        fallbackStatus: ReviewCommentStatus,
        reason: String,
    ): List<SlideReviewComment> = synchronized(this) {
        val ids = items.map { it.id }.toSet()
        val updated = comments.map { existing ->
            if (existing.id in ids) {
                existing.copy(status = fallbackStatus, deliveryError = reason)
            } else {
                existing
            }
        }
        persistCommentsInternal(updated)
        updated.filter { it.id in ids }
    }

    internal fun formatAgentMessage(items: List<SlideReviewComment>): String {
        val absWorkspace = workspaceRoot.toAbsolutePath()
        val absComments = commentsFile.toAbsolutePath()
        val isolatedGradleHome = absWorkspace.resolve(".gradle-agent-home")
        val reloadCommand = if (isolatedGradleHome.exists()) {
            "./gradlew --gradle-user-home \"$isolatedGradleHome\" " +
                "\"-Dkotlin.daemon.options=runFilesPath=$isolatedGradleHome/kotlin-daemon\" " +
                config.gradleTaskHint
        } else {
            "./gradlew ${config.gradleTaskHint}"
        }

        return buildString {
            appendLine(
                "The user submitted ${items.size} interactive slide review comment" +
                    "${if (items.size == 1) "" else "s"} from the live Ciacola viewer ($absWorkspace):"
            )
            appendLine()
            for (c in items) {
                appendLine("- Comment ID: `${c.id}`")
                appendLine(
                    "  Slide: Scene ${c.sceneIndex + 1}/${c.sceneCount} (sceneIndex=${c.sceneIndex})" +
                        (if (c.sceneTitle.isNotBlank()) " \"${c.sceneTitle}\"" else "") +
                        ", Frame ${c.frameIndex + 1}/${c.frameCount} (frameIndex=${c.frameIndex})" +
                        if (c.sceneHeader.isNotBlank()) " · Header: \"${c.sceneHeader}\"" else ""
                )
                appendLine("  Theme preset: `${c.themePreset}`")
                appendLine(
                    "  Stage position (1920x1080): (${c.stageX.toInt()}, ${c.stageY.toInt()})" +
                        (c.selectionBounds?.let { b ->
                            " · Bounds: [${b.left.toInt()}, ${b.top.toInt()} -> ${b.right.toInt()}, ${b.bottom.toInt()}]"
                        } ?: "")
                )
                appendLine("  Target element: [${c.target.kind}] ${c.target.label}")
                if (c.target.clickedToken.isNotBlank()) {
                    appendLine("  Clicked word/token: \"${c.target.clickedToken}\"")
                }
                if (c.target.textSnippet.isNotBlank()) {
                    appendLine("  Element text: \"${c.target.textSnippet}\"")
                }
                if (!c.screenshotPath.isNullOrBlank()) {
                    appendLine("  Annotated slide PNG: `${c.screenshotPath}`")
                }
                appendLine("  Instruction / feedback: ${c.note}")
                appendLine()
            }
            appendLine("Please:")
            appendLine("1. Apply the requested Kotlin changes in `$absWorkspace`.")
            appendLine("2. Trigger Compose Hot Reload on the live viewer via `$reloadCommand`.")
            appendLine(
                "3. Update those comment IDs in `$absComments` to `\"status\": \"applied\"` " +
                    "with a concise `\"resolution\"` note so the live viewer pins turn green automatically."
            )
        }
    }

    private fun readConfigInternal(force: Boolean = false): ReviewConfig {
        if (!configFile.exists()) {
            lastConfigModifiedMillis = -1L
            lastConfigFileSize = -1L
            return config
        }
        val modified = runCatching { configFile.getLastModifiedTime().toMillis() }.getOrDefault(-1L)
        val size = runCatching { configFile.fileSize() }.getOrDefault(-1L)
        if (!force && modified == lastConfigModifiedMillis && size == lastConfigFileSize) {
            return config
        }
        lastConfigModifiedMillis = modified
        lastConfigFileSize = size
        return runCatching {
            json.decodeFromString<ReviewConfig>(configFile.readText())
        }.getOrDefault(config)
    }

    private fun writeAtomically(target: Path, content: String) {
        val tmp = target.resolveSibling("${target.fileName}.tmp")
        tmp.writeText(content)
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
    }
}

fun generateReviewCommentId(): String {
    val ts = java.lang.Long.toString(Instant.now().toEpochMilli(), 36)
    val rand = java.lang.Integer.toString((Math.random() * 1_679_616).toInt(), 36).padStart(4, '0')
    return "rc-$ts-$rand"
}

fun encodeAnnotatedStagePng(
    stageBitmap: ImageBitmap?,
    stageWidth: Float,
    stageHeight: Float,
    stageX: Float,
    stageY: Float,
    highlightRect: StageRectDto?,
): ByteArray {
    val width = (stageBitmap?.width ?: 960).coerceAtLeast(64)
    val height = (stageBitmap?.height ?: 540).coerceAtLeast(64)
    val surface = Surface.makeRasterN32Premul(width, height)
    val canvas: Canvas = surface.canvas
    canvas.clear(0xFF0B0D12.toInt())

    if (stageBitmap != null && stageBitmap.width > 0 && stageBitmap.height > 0) {
        runCatching {
            val skiaImage = Image.makeFromBitmap(stageBitmap.asSkiaBitmap())
            canvas.drawImage(skiaImage, 0f, 0f)
        }
    }

    val scaleX = width / stageWidth.coerceAtLeast(1f)
    val scaleY = height / stageHeight.coerceAtLeast(1f)

    if (highlightRect != null) {
        val r = SkiaRect.makeLTRB(
            highlightRect.left * scaleX,
            highlightRect.top * scaleY,
            highlightRect.right * scaleX,
            highlightRect.bottom * scaleY,
        )
        val fillPaint = Paint().apply {
            color4f = Color4f(1f, 0.42f, 0.37f, 0.14f)
            mode = PaintMode.FILL
            isAntiAlias = true
        }
        val strokePaint = Paint().apply {
            color4f = Color4f(1f, 0.42f, 0.37f, 0.95f)
            mode = PaintMode.STROKE
            strokeWidth = (3f * scaleX).coerceAtLeast(2f)
            isAntiAlias = true
        }
        canvas.drawRRect(r.inflate(4f * scaleX).withRadii(8f * scaleX), fillPaint)
        canvas.drawRRect(r.inflate(4f * scaleX).withRadii(8f * scaleX), strokePaint)
    }

    val px = stageX * scaleX
    val py = stageY * scaleY
    val pinOuter = Paint().apply {
        color4f = Color4f(1f, 0.42f, 0.37f, 1f)
        mode = PaintMode.FILL
        isAntiAlias = true
    }
    val pinInner = Paint().apply {
        color4f = Color4f(1f, 1f, 1f, 1f)
        mode = PaintMode.FILL
        isAntiAlias = true
    }
    val radius = (12f * scaleX).coerceAtLeast(8f)
    canvas.drawCircle(px, py, radius, pinOuter)
    canvas.drawCircle(px, py, radius * 0.42f, pinInner)

    return surface.makeImageSnapshot().encodeToData(EncodedImageFormat.PNG)?.bytes ?: ByteArray(0)
}

private fun SkiaRect.withRadii(radius: Float): org.jetbrains.skia.RRect =
    org.jetbrains.skia.RRect.makeLTRB(left, top, right, bottom, radius)
