package dev.sebastiano.ciacola.review

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.getLastModifiedTime
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
    var defaultDeckId: String? = null,
    var defaultDeckFilePath: String? = null,
    private val clipboardWriter: (String) -> Unit = ::writeTextToSystemClipboard,
) {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    val commentsFile: Path = storyboardDir.resolve("review-comments.json")
    val claimsFile: Path = storyboardDir.resolve(ReviewClaims.FILE_NAME)
    val configFile: Path = storyboardDir.resolve("review-config.json")
    val capturesDir: Path = storyboardDir.resolve("review-captures")

    val comments = SnapshotStateList<SlideReviewComment>()
    val trashedComments = SnapshotStateList<SlideReviewComment>()
    var config by mutableStateOf(ReviewConfig())
        private set

    /**
     * Comment ids whose push to the agent is in flight right now. A comment is `Applying` both
     * while it is being pushed and for as long as the agent holds it afterwards, and those two
     * mean very different things to whoever just clicked Send, so the in-flight half is tracked
     * here rather than persisted.
     */
    val deliveringIds = SnapshotStateList<String>()

    /**
     * Comment ids an agent has claimed, refreshed by the same poll that reloads the queue.
     *
     * A claimed comment is still `Queued` — the agent has not reported back — so without this the
     * UI would call it "waiting for an agent" while an agent is in fact working on it, and offer
     * to send it to a second one.
     */
    val claimedIds = SnapshotStateList<String>()

    private var lastClaimsModifiedMillis: Long = -1L
    private var lastClaimsFileSize: Long = -1L

    /** Whether a push command exists; see [agentPushAvailable]. */
    private var pushBinAvailable: Boolean by mutableStateOf(false)

    /** What the last review action did, for [ReviewActivityNoticeBar]. */
    var activityNotice: ReviewActivityNotice? by mutableStateOf(null)
        private set

    private var noticeSeq: Long = 0L

    private val noticeLock = Any()

    private val deliveryLock = Any()

    private data class FlushOutcome(
        val dispatched: List<SlideReviewComment>,
        val allClaimed: List<SlideReviewComment>,
    )

    private data class RetryOutcome(val comment: SlideReviewComment?, val claimedAlready: Boolean)

    private fun postNotice(
        kind: ReviewNoticeKind,
        text: Pair<String, String>,
        autoDismissMillis: Long?,
    ) {
        // Notices are posted from Dispatchers.IO as well as the UI thread. Allocating the id and
        // publishing it have to be one step, or two notices share an id and the auto-dismiss
        // timer for the first one takes the second one down with it.
        synchronized(noticeLock) {
            noticeSeq += 1
            activityNotice = ReviewActivityNotice(
                id = noticeSeq,
                kind = kind,
                title = text.first,
                detail = text.second,
                autoDismissMillis = autoDismissMillis,
            )
        }
    }

    /** Clears the notice only while it is still the one [noticeId] named. */
    fun dismissNotice(noticeId: Long) {
        synchronized(noticeLock) {
            if (activityNotice?.id == noticeId) activityNotice = null
        }
    }

    private fun displayNumbersOf(items: List<SlideReviewComment>): List<Int> {
        // One immutable snapshot: this runs on Dispatchers.IO, and walking the live
        // SnapshotStateList while the author queues or a reload swaps it can throw mid-dispatch,
        // after the comment has already been persisted as Applying.
        val snapshot = comments.toList()
        return items.map { reviewDisplayNumber(snapshot, it.id) }.sorted()
    }

    private var lastObservedModifiedMillis: Long = -1L
    private var lastObservedFileSize: Long = -1L
    private var lastConfigModifiedMillis: Long = -1L
    private var lastConfigFileSize: Long = -1L

    init {
        config = readConfigInternal()
        refreshPushAvailability()
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

    /**
     * Resolves the `agentapi` binary to execute.
     *
     * Security hardening: ignores arbitrary binary paths from on-disk `review-config.json` (only
     * the literal `"agentapi"` command name is accepted from workspace config); custom binary paths
     * are accepted only from trusted JVM system properties or environment variables.
     */
    fun effectiveAgentApiBin(): String {
        val fromProp = sequenceOf(
            systemPropertyProvider("ciacola.agentapi.bin"),
            systemPropertyProvider("agentapi.bin"),
        ).mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }
            .firstOrNull()
        if (fromProp != null) return fromProp

        val fromEnv = sequenceOf(
            envProvider("CIACOLA_AGENTAPI_BIN"),
            envProvider("AGENTAPI_BIN"),
        ).mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }
            .firstOrNull()
        if (fromEnv != null) return fromEnv

        val fromConfig = config.agentApiBin.trim()
        if (fromConfig == "agentapi") return fromConfig

        return "agentapi"
    }

    /**
     * Whether there is a command on this machine to push a comment to at all.
     *
     * Without one, Send can only ever end where Queue already put the comment, so offering it is
     * a dead end dressed up as an action. The queue is the real path: an agent on
     * `:deck:reviewWait` collects from it.
     *
     * The answer is snapshot state so that [refreshPushAvailability] reaches the open UI, and it
     * is only ever *read* here: this is called from composition, and resolving lazily would mean
     * writing state while composing, which costs a wasted frame on every store and invites the
     * loop where the write invalidates its own reader.
     */
    fun agentPushAvailable(): Boolean = pushBinAvailable

    private fun resolvePushAvailability(bin: String): Boolean =
        isExecutableAvailable(
            bin = bin,
            path = envProvider("PATH"),
            baseDir = workspaceRoot,
            pathExt = envProvider("PATHEXT"),
        )

    /**
     * Re-checks the push command, for the moments where the answer plausibly just changed: the
     * review panel opening, and a launch that failed.
     */
    fun refreshPushAvailability() {
        pushBinAvailable = resolvePushAvailability(effectiveAgentApiBin())
    }

    /**
     * Re-reads the claims sidecar when it has changed, on the same 250 ms tick as the queue.
     *
     * Guarded by mtime and size like the queue itself: this runs four times a second and an
     * unchanged file is the normal case.
     */
    @Synchronized
    fun reloadClaims() {
        val modified = runCatching { claimsFile.getLastModifiedTime().toMillis() }.getOrDefault(-1L)
        val size = runCatching { claimsFile.fileSize() }.getOrDefault(-1L)
        if (modified == lastClaimsModifiedMillis && size == lastClaimsFileSize) return
        lastClaimsModifiedMillis = modified
        lastClaimsFileSize = size
        val ids = ReviewClaims.readIds(claimsFile) {}
        if (claimedIds.toSet() == ids) return
        claimedIds.clear()
        claimedIds.addAll(ids.sorted())
    }

    /**
     * Hands a claim back, so the comment is offered to an agent again.
     *
     * The author's escape hatch when the agent that took a note is gone: a claim has no expiry,
     * because guessing one would hand a comment to a second agent while the first is still
     * thinking.
     */
    fun releaseClaim(commentId: String) {
        inQueueTransactionOrNotify {
            val remaining = ReviewClaims.releasing(ReviewClaims.read(claimsFile) {}, listOf(commentId))
            ReviewClaims.write(claimsFile, remaining)
            lastClaimsModifiedMillis = -1L
            lastClaimsFileSize = -1L
        }
        reloadClaims()
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

    /**
     * Commits [updated] to disk, merged against whatever is there now.
     *
     * The deck is not the only writer: an agent's `--review-resolve` edits the same file. Writing
     * this list wholesale would erase a verdict that landed since the last 250 ms poll, exactly as
     * the agent writing wholesale would erase a comment queued since its own read. So the write is
     * a transaction under the shared lock, and it carries a *delta* rather than a whole file:
     * what this store added or changed since it last agreed with disk, and what it deleted.
     *
     * Everything on disk that this store never knew about is left alone, which is what makes the
     * two writers safe together — and why a plain union would be wrong, since it would resurrect
     * every comment the author had just deleted.
     */
    /**
     * Runs [block] holding the queue's file lock and then this store's monitor, in that order.
     *
     * Every mutation goes through here so the two are always taken the same way round. A path
     * that took the monitor first would deadlock against one already holding the file lock and
     * waiting for the monitor, which is exactly what reading claims before marking a comment
     * `Applying` used to do.
     *
     * The wait is bounded. The other holder is a separate process, and a stuck one must not be
     * able to freeze the deck for ever; [ReviewQueueFile.Busy] surfaces as a notice instead.
     */
    private fun <T> inQueueTransaction(block: () -> T): T =
        ReviewQueueFile.withLock(storyboardDir, QUEUE_LOCK_WAIT) { synchronized(this) { block() } }

    /** As [inQueueTransaction], but reports a busy queue to the author rather than throwing. */
    private fun <T> inQueueTransactionOrNotify(block: () -> T): T? =
        try {
            inQueueTransaction(block)
        } catch (e: ReviewQueueFile.Busy) {
            postNotice(
                kind = ReviewNoticeKind.Failed,
                text = queueBusyNotice(e.file),
                autoDismissMillis = STICKY_NOTICE_MILLIS,
            )
            null
        }

    /**
     * Commits [updated] to disk, merged against whatever is there now.
     *
     * Must be called inside [inQueueTransaction]: it does not take the locks itself, so that the
     * caller can decide what else belongs in the same transaction — reading claims, say, which
     * has to be atomic with marking a comment `Applying` or two agents get the same note.
     */
    private fun persistCommentsInternal(updated: List<SlideReviewComment>) {
        val believed = comments.toList()
        var committed = updated
        runCatching {
            Files.createDirectories(storyboardDir)
            val raw = ReviewQueueFile.readTextOrNull(commentsFile)?.takeIf { it.isNotBlank() }
            val onDisk = if (raw == null) {
                updated
            } else {
                runCatching { json.decodeFromString<List<SlideReviewComment>>(raw) }.getOrElse {
                    // Keep a copy before replacing something we could not read, the way
                    // reloadFromDisk does. Overwriting an unreadable queue in silence is how a
                    // recoverable mess becomes an unrecoverable one.
                    runCatching {
                        Files.copy(
                            commentsFile,
                            commentsFile.resolveSibling("review-comments.corrupt.json"),
                            StandardCopyOption.REPLACE_EXISTING,
                        )
                    }
                    updated
                }
            }
            val merged = mergeOntoDisk(onDisk = onDisk, believed = believed, updated = updated)
            ReviewQueueFile.writeAtomically(commentsFile, json.encodeToString(merged) + "\n")
            committed = merged
            // Inside the lock: taken afterwards, a verdict committed in between would be recorded
            // as already seen, and the 250 ms poll would never come back for it.
            lastObservedModifiedMillis = commentsFile.getLastModifiedTime().toMillis()
            lastObservedFileSize = commentsFile.fileSize()
        }
        // Publish what was committed, not what was asked for. The merge may have kept another
        // writer's verdict, and the file's mtime is now recorded as seen, so a poll will not
        // come back for it: believing `updated` here would leave the UI showing a comment as
        // queued that disk says is applied, and the next write would push that stale state back.
        comments.clear()
        comments.addAll(committed)
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
            deckId = comment.deckId ?: defaultDeckId,
            deckFilePath = comment.deckFilePath ?: defaultDeckFilePath,
            nodeId = comment.nodeId ?: comment.target.nodeId,
        )
        // On IO, not the caller's dispatcher: this takes a cross-process lock, and the caller is
        // a Compose click handler.
        val snapshot = withContext(Dispatchers.IO) {
            inQueueTransaction {
                val next = comments.toList() + prepared
                persistCommentsInternal(next)
                prepared
            }
        }

        if (!dispatchImmediate) {
            postNotice(
                kind = ReviewNoticeKind.Queued,
                text = queuedNotice(
                    numbers = displayNumbersOf(listOf(snapshot)),
                    // Not "is a conversation set": whether a Send button exists to be pressed.
                    agentConfigured = effectiveConversationId().isNotBlank() && agentPushAvailable(),
                ),
                autoDismissMillis = QUEUED_NOTICE_MILLIS,
            )
            return snapshot
        }
        return dispatchItemsToAgent(listOf(snapshot)).single()
    }

    /**
     * Pushes everything still waiting, except what an agent has already taken.
     *
     * Status alone is not enough to decide. A comment collected by `:deck:reviewWait` stays
     * `Queued` until the agent resolves it, so selecting on status would hand a note somebody is
     * already acting on to a second agent, and two of them would edit the same slide.
     */
    suspend fun flushQueued(): Int {
        // Reading the claims and writing `Applying` have to be one transaction: a reviewWait
        // landing in the gap claims the same comments before the status says they are taken, and
        // two agents edit the same slide.
        val outcome = withContext(Dispatchers.IO) {
            inQueueTransactionOrNotify {
                val claimed = ReviewClaims.readIds(claimsFile) {}
                val waiting = comments.filter {
                    it.status == ReviewCommentStatus.Queued ||
                        it.status == ReviewCommentStatus.Failed
                }
                val pending = waiting.filterNot { it.id in claimed }
                if (pending.isEmpty()) {
                    FlushOutcome(dispatched = emptyList(), allClaimed = waiting)
                } else {
                    val pendingIds = pending.map { it.id }.toSet()
                    val updated = comments.map { c ->
                        if (c.id in pendingIds) {
                            c.copy(status = ReviewCommentStatus.Applying, deliveryError = null)
                        } else {
                            c
                        }
                    }
                    persistCommentsInternal(updated)
                    FlushOutcome(
                        dispatched = updated.filter { it.id in pendingIds },
                        allClaimed = emptyList(),
                    )
                }
            }
        } ?: return 0
        if (outcome.dispatched.isEmpty()) {
            if (outcome.allClaimed.isNotEmpty()) {
                postNotice(
                    kind = ReviewNoticeKind.Delivered,
                    text = alreadyWithAnAgentNotice(displayNumbersOf(outcome.allClaimed)),
                    autoDismissMillis = QUEUED_NOTICE_MILLIS,
                )
            }
            return 0
        }
        dispatchItemsToAgent(outcome.dispatched)
        return outcome.dispatched.size
    }

    fun deleteComment(commentId: String) {
        inQueueTransactionOrNotify {
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

    fun restoreComment(commentId: String): Boolean = inQueueTransactionOrNotify {
        val idx = trashedComments.indexOfFirst { it.id == commentId }
        if (idx < 0) {
            false
        } else {
            val item = trashedComments.removeAt(idx)
            if (comments.none { it.id == item.id }) {
                persistCommentsInternal(comments.toList() + item)
            }
            true
        }
    } ?: false

    fun restoreLastDeleted(): SlideReviewComment? = inQueueTransactionOrNotify {
        val last = trashedComments.lastOrNull()
        if (last == null) {
            null
        } else {
            trashedComments.removeAt(trashedComments.lastIndex)
            if (comments.none { it.id == last.id }) {
                persistCommentsInternal(comments.toList() + last)
            }
            last
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
        inQueueTransactionOrNotify {
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
        inQueueTransactionOrNotify {
            persistCommentsInternal(comments.filterNot { it.status == ReviewCommentStatus.Applied })
        }
    }

    suspend fun retryComment(commentId: String) {
        val outcome = withContext(Dispatchers.IO) {
            inQueueTransactionOrNotify {
                val target = comments.firstOrNull { it.id == commentId }
                when {
                    target == null -> RetryOutcome(null, claimedAlready = false)
                    // An agent is already on it; pushing would be the same double delivery
                    // flushQueued avoids, one comment at a time.
                    commentId in ReviewClaims.readIds(claimsFile) {} ->
                        RetryOutcome(target, claimedAlready = true)
                    else -> {
                        val retrying =
                            target.copy(status = ReviewCommentStatus.Applying, deliveryError = null)
                        persistCommentsInternal(
                            comments.map { if (it.id == commentId) retrying else it },
                        )
                        RetryOutcome(retrying, claimedAlready = false)
                    }
                }
            }
        } ?: return
        val item = outcome.comment ?: return
        if (outcome.claimedAlready) {
            postNotice(
                kind = ReviewNoticeKind.Delivered,
                text = alreadyWithAnAgentNotice(displayNumbersOf(listOf(item))),
                autoDismissMillis = QUEUED_NOTICE_MILLIS,
            )
            return
        }
        dispatchItemsToAgent(listOf(item))
    }

    private suspend fun dispatchItemsToAgent(
        items: List<SlideReviewComment>,
    ): List<SlideReviewComment> = withContext(Dispatchers.IO) {
        val convId = effectiveConversationId()
        if (convId.isBlank()) {
            postNotice(
                kind = ReviewNoticeKind.NothingToPushTo,
                text = queuedNotice(numbers = displayNumbersOf(items), agentConfigured = false),
                autoDismissMillis = STICKY_NOTICE_MILLIS,
            )
            return@withContext markDeliveryFailure(
                items = items,
                fallbackStatus = ReviewCommentStatus.Queued,
                reason = "No Conversation ID configured. Open Review -> Panel (F4) to paste your Conversation ID, then click Send Queued.",
            )
        }

        val bin = effectiveAgentApiBin()
        // Retry Send stays live while a comment is Applying, so a second push can start on top of
        // the first. Reserving has to be one step, or two dispatches both see the id unreserved;
        // and the message has to be built from what this dispatch actually owns, not from what it
        // was asked for, or the push describes comments another one is already sending.
        val mine = synchronized(deliveryLock) {
            val free = items.filterNot { it.id in deliveringIds }
            deliveringIds.addAll(free.map { it.id })
            free
        }
        if (mine.isEmpty()) return@withContext items
        // Every notice from here on describes what this dispatch actually owns: another one may
        // already be sending the rest, and naming those would report its progress as ours.
        val numbers = displayNumbersOf(mine)
        val message = formatAgentMessage(mine)
        postNotice(
            kind = ReviewNoticeKind.Sending,
            text = sendingNotice(numbers),
            autoDismissMillis = null,
        )
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

        synchronized(deliveryLock) { deliveringIds.removeAll(mine.map { it.id }.toSet()) }

        if (result.isFailure) {
            val cause = result.exceptionOrNull()
            val err = cause?.message ?: "Unknown agentapi error"
            // Having no agent binary on PATH is not a delivery failure. There is simply nothing
            // listening to push to, and the comment is perfectly well served by staying queued for
            // an agent that polls (`:deck:reviewWait`). Reporting it in red trains the author to
            // ignore the one signal that should mean something went wrong.
            // Ask the filesystem rather than the exception text: ProcessBuilder says "Cannot run
            // program" for a command that is present but not executable, or whose interpreter is
            // missing, and reporting those as "nothing installed" hides a fault the author can fix.
            // Absent is the only case that means "nothing to push to"; present-but-unrunnable
            // keeps its diagnostic, because that one the author can act on.
            refreshPushAvailability()
            val noAgentInstalled = isAgentBinaryMissing(cause) &&
                !commandExistsAnywhere(effectiveAgentApiBin(), envProvider("PATH"), workspaceRoot)
            postNotice(
                kind = if (noAgentInstalled) ReviewNoticeKind.NothingToPushTo else ReviewNoticeKind.Failed,
                text = if (noAgentInstalled) {
                    nothingToPushToNotice(numbers)
                } else {
                    deliveryFailedNotice(numbers, err.take(300))
                },
                autoDismissMillis = STICKY_NOTICE_MILLIS,
            )
            markDeliveryFailure(
                // Only what this dispatch reserved: another dispatch may own the rest, and
                // marking its comments failed would offer them to a waiter mid-flight.
                items = mine,
                fallbackStatus = if (noAgentInstalled) ReviewCommentStatus.Queued else ReviewCommentStatus.Failed,
                reason = if (noAgentInstalled) "" else err.take(300),
            )
        } else {
            postNotice(
                kind = ReviewNoticeKind.Delivered,
                text = deliveredNotice(numbers),
                autoDismissMillis = QUEUED_NOTICE_MILLIS,
            )
            items
        }
    }

    private fun markDeliveryFailure(
        items: List<SlideReviewComment>,
        fallbackStatus: ReviewCommentStatus,
        reason: String,
    ): List<SlideReviewComment> = inQueueTransaction {
        val ids = items.map { it.id }.toSet()
        val updated = comments.map { existing ->
            // Only a comment still being pushed. A push can time out after 15 seconds, by which
            // time the agent may already have written Applied through :deck:reviewResolve, and
            // reporting the delivery failure over the top of that verdict loses the work.
            if (existing.id in ids && existing.status == ReviewCommentStatus.Applying) {
                existing.copy(status = fallbackStatus, deliveryError = reason)
            } else {
                existing
            }
        }
        persistCommentsInternal(updated)
        updated.filter { it.id in ids }
    }

    fun copyPromptToClipboard(items: List<SlideReviewComment>): String {
        val message = formatAgentMessage(items)
        clipboardWriter(message)
        return message
    }

    fun formatAgentMessage(items: List<SlideReviewComment>): String {
        val absWorkspace = workspaceRoot.toAbsolutePath()
        val absComments = commentsFile.toAbsolutePath()
        val isolatedGradleHome = absWorkspace.resolve(".gradle-agent-home")
        fun isExistingFile(pathText: String): Boolean {
            val candidate = runCatching { Path.of(pathText) }.getOrNull() ?: return false
            val resolved = if (candidate.isAbsolute) candidate else absWorkspace.resolve(candidate)
            return Files.isRegularFile(resolved.normalize())
        }
        val resolvedDeckIds = items.mapNotNull { it.deckId?.trim()?.takeIf(String::isNotEmpty) }.distinct()
        val primaryDeckId = resolvedDeckIds.firstOrNull()
            ?: defaultDeckId?.trim()?.takeIf(String::isNotEmpty)
            ?: "<deckId>"
        val primaryDeckFile = (
            items.firstNotNullOfOrNull { it.deckFilePath?.trim()?.takeIf(String::isNotEmpty) }
                ?: defaultDeckFilePath?.trim()?.takeIf(String::isNotEmpty)
            )?.takeIf(::isExistingFile)
        val distinctTargets = items.map { c ->
            val itemDeckId = c.deckId?.trim()?.takeIf(String::isNotEmpty)
                ?: defaultDeckId?.trim()?.takeIf(String::isNotEmpty)
                ?: "<deckId>"
            val itemDeckFile = (
                c.deckFilePath?.trim()?.takeIf(String::isNotEmpty)
                    ?: defaultDeckFilePath?.trim()?.takeIf(String::isNotEmpty)
                )?.takeIf(::isExistingFile)
            val itemEditPath = itemDeckFile ?: "deck/src/desktopMain/resources/decks/$itemDeckId.deck.json"
            val itemLintArg = itemDeckFile ?: itemDeckId
            itemEditPath to itemLintArg
        }.distinct()
        val editDeckPaths = if (distinctTargets.isNotEmpty()) {
            distinctTargets.map { it.first }.distinct()
        } else {
            listOf(primaryDeckFile ?: "deck/src/desktopMain/resources/decks/$primaryDeckId.deck.json")
        }
        val lintDeckArgs = if (distinctTargets.isNotEmpty()) {
            distinctTargets.map { it.second }.distinct()
        } else {
            listOf(primaryDeckFile ?: primaryDeckId)
        }
        val gradlePrefix = if (isolatedGradleHome.exists()) {
            "./gradlew --gradle-user-home \"$isolatedGradleHome\" " +
                "\"-Dkotlin.daemon.options=runFilesPath=$isolatedGradleHome/kotlin-daemon\""
        } else {
            "./gradlew"
        }
        val lintCommand = lintDeckArgs.joinToString(" && ") { arg ->
            "$gradlePrefix :deck:lintDeck -Pciacola.deck=${shellQuote(arg)}"
        }

        return buildString {
            appendLine(
                "The user submitted ${items.size} interactive slide review comment" +
                    "${if (items.size == 1) "" else "s"} from the live Ciacola viewer ($absWorkspace):"
            )
            appendLine()
            for (c in items) {
                val itemDeckId = c.deckId?.trim()?.takeIf(String::isNotEmpty)
                    ?: defaultDeckId?.trim()?.takeIf(String::isNotEmpty)
                val itemDeckFile = (
                    c.deckFilePath?.trim()?.takeIf(String::isNotEmpty)
                        ?: defaultDeckFilePath?.trim()?.takeIf(String::isNotEmpty)
                    )?.takeIf(::isExistingFile)
                val itemNodeId = c.nodeId?.trim()?.takeIf(String::isNotEmpty)
                    ?: c.target.nodeId?.trim()?.takeIf(String::isNotEmpty)

                appendLine("- Comment ID: `${c.id}`")
                if (itemDeckId != null) {
                    appendLine("  Deck ID: `$itemDeckId`")
                }
                if (itemDeckFile != null) {
                    appendLine("  Deck file: `$itemDeckFile`")
                }
                appendLine(
                    buildString {
                        append("  Slide: Slide ${c.sceneIndex + 1}/${c.sceneCount}")
                        if (!c.slideId.isNullOrBlank()) {
                            append(" (slideId=${c.slideId})")
                        }
                        if (c.sceneTitle.isNotBlank()) {
                            append(" \"${c.sceneTitle}\"")
                        }
                        append(", Beat ${c.frameIndex + 1}/${c.frameCount} (beatIndex=${c.frameIndex})")
                        if (c.sceneHeader.isNotBlank()) {
                            append(" · Header: \"${c.sceneHeader}\"")
                        }
                    }
                )
                appendLine("  Theme preset: `${c.themePreset}`")
                appendLine(
                    "  Stage position (1920x1080): (${c.stageX.toInt()}, ${c.stageY.toInt()})" +
                        (c.selectionBounds?.let { b ->
                            " · Bounds: [${b.left.toInt()}, ${b.top.toInt()} -> ${b.right.toInt()}, ${b.bottom.toInt()}]"
                        } ?: "")
                )
                appendLine(
                    "  Target element: [${c.target.kind}] ${c.target.label}" +
                        if (itemNodeId != null) " (nodeId=$itemNodeId)" else ""
                )
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
            if (editDeckPaths.size <= 1) {
                appendLine(
                    "1. Edit `${editDeckPaths.single()}` directly in `$absWorkspace` " +
                        "(Ciacola's `DeckFileSync` auto-reloads changes live)."
                )
            } else {
                appendLine(
                    "1. Edit each target deck file (${editDeckPaths.joinToString(", ") { "`$it`" }}) directly in `$absWorkspace` " +
                        "(Ciacola's `DeckFileSync` auto-reloads changes live)."
                )
            }
            val deckNoun = if (lintDeckArgs.size <= 1) "deck" else "decks"
            appendLine("2. Validate the updated $deckNoun via `$lintCommand`.")
            // Never tell an agent to edit the queue by hand. That write would miss
            // review-queue.lock, and the author queueing a comment at the same moment would lose
            // it — the race this whole file's locking exists to close.
            appendLine(
                "3. Close each comment with `./gradlew :deck:reviewResolve " +
                    "-Pciacola.deck=$primaryDeckId -Pciacola.comment=<commentId> " +
                    "-Pciacola.resolution=\"<what you changed>\"`, which takes the queue lock and " +
                    "turns the live viewer's pin green. Do not edit `$absComments` by hand: that " +
                    "bypasses the lock and can erase a comment queued at the same moment."
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

    private fun shellQuote(value: String): String {
        if (value.isNotEmpty() && SAFE_SHELL_ARG_REGEX.matches(value)) {
            return value
        }
        return "'" + value.replace("'", "'\"'\"'") + "'"
    }

    private companion object {
        /**
         * How long a queue transaction waits for another process before giving up.
         *
         * Long enough that an agent mid-write is never interrupted, short enough that a stuck one
         * cannot hold the author's deck hostage.
         */
        private val QUEUE_LOCK_WAIT: java.time.Duration = java.time.Duration.ofSeconds(5)

        private val SAFE_SHELL_ARG_REGEX = Regex("^[A-Za-z0-9_./:@%+=,-]+$")
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

internal fun writeTextToSystemClipboard(text: String) {
    runCatching {
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
    }
}

/**
 * Whether a dispatch failed only because the configured agent command is not installed.
 *
 * That is the ordinary state for an author whose agent polls the queue instead of being pushed to,
 * so it should leave the comment queued rather than marked as a delivery failure.
 */
internal fun isAgentBinaryMissing(cause: Throwable?): Boolean =
    cause is IOException && (cause.message ?: "").contains("Cannot run program")

