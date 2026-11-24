package dev.sebastiano.ciacola.review

import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/**
 * Cross-process serialization for the review queue, and the one place that touches its bytes.
 *
 * Lives in deck-core because both writers need it: the running deck (deck-review) and the agent
 * CLI (deck). A lock only one of them takes is not a lock.
 *
 * Two independent writers share `review-comments.json`: the running deck, and an agent's
 * `--review-resolve`. Both do read-modify-write over the whole file. An atomic rename stops a
 * reader seeing half a queue, but it does nothing about the author queueing a comment in the
 * moment between an agent's read and its write — the agent's stale list then replaces the file,
 * the deck's 250 ms poll adopts it, and the comment is gone from disk and from the UI with no
 * error anywhere. So every read-modify-write runs under an advisory lock on a sibling file, held
 * by whoever is mid-transaction.
 *
 * The lock is advisory and local. It orders the deck against the agents on one machine, which is
 * the whole population of writers; it is not a distributed lock and does not pretend to be.
 */
object ReviewQueueFile {

    const val LOCK_FILE_NAME = "review-queue.lock"

    /** A queue file that exists but cannot be read or parsed. Distinct from "no queue yet". */
    class Unreadable(val file: Path, cause: Throwable?) : IOException(
        "Review queue at ${file.toAbsolutePath()} exists but could not be read: " +
            (cause?.message ?: "invalid JSON"),
        cause,
    )

    /**
     * Serializes this JVM's own threads. A [FileLock] does not: it belongs to the whole process,
     * so a second thread asking for one on the same file gets [OverlappingFileLockException]
     * rather than waiting its turn. Both halves are needed — this one between threads, the file
     * lock between processes — and leaving either out looks like it works until two writers
     * actually meet.
     */
    private val inProcessLocks = ConcurrentHashMap<String, ReentrantLock>()

    /** The queue is held by someone else and did not come free in time. */
    class Busy(val file: Path, waited: Duration) : IOException(
        "Review queue at ${file.toAbsolutePath()} was still locked after ${waited.toSeconds()}s",
    )

    /**
     * Runs [block] while holding the queue lock for [dir], against every other thread in this JVM
     * and every other process on this machine.
     *
     * [timeout] bounds the wait. The other holder is a separate process, so an unbounded wait
     * means a stuck agent can freeze whatever is waiting — including the deck's UI thread — for
     * as long as it likes; past the deadline this throws [Busy] instead. A null timeout waits for
     * ever, which is what a command-line tool with nothing else to do should do.
     *
     * The transactions themselves are a few milliseconds of JSON, so a wait that reaches the
     * deadline means something is wrong rather than busy.
     */
    fun <T> withLock(dir: Path, timeout: Duration? = null, block: () -> T): T {
        Files.createDirectories(dir)
        val lockFile = dir.resolve(LOCK_FILE_NAME)
        val key = lockFile.toAbsolutePath().normalize().toString()
        val inProcess = inProcessLocks.computeIfAbsent(key) { ReentrantLock() }
        // Already inside a transaction on this file: run the block and do not ask for a second
        // FileLock. A FileLock belongs to the process, so asking twice throws
        // OverlappingFileLockException even on the thread that already holds it — nesting would
        // fail at the inner call rather than waiting, which is the opposite of what a reentrant
        // lock should do.
        // One deadline for both halves of the wait. Timing them separately lets a caller that
        // asked for five seconds block for ten and still be told it waited five.
        val deadlineNanos = timeout?.let { System.nanoTime() + it.toNanos() }
        val alreadyHeld = inProcess.isHeldByCurrentThread
        if (!acquire(inProcess, deadlineNanos)) throw Busy(lockFile, timeout ?: Duration.ZERO)
        try {
            if (alreadyHeld) return block()
            FileChannel.open(
                lockFile,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
            ).use { channel ->
                var lock: FileLock? = null
                try {
                    lock = acquireFileLock(channel, deadlineNanos) ?: throw Busy(lockFile, timeout!!)
                    return block()
                } finally {
                    runCatching { lock?.release() }
                }
            }
        } finally {
            inProcess.unlock()
        }
    }

    /**
     * Reads [file] as text.
     *
     * Returns `null` only when there is no file at all, which is the ordinary state before the
     * author has queued anything. A file that exists and cannot be read or parsed throws
     * [Unreadable]: reporting that as an empty queue turns a corrupt file into a wait that never
     * ends and an export that cheerfully prints `[]`.
     */
    fun readTextOrNull(file: Path): String? {
        if (!Files.exists(file)) return null
        if (!Files.isRegularFile(file)) throw Unreadable(file, null)
        val text = try {
            Files.readString(file)
        } catch (e: IOException) {
            throw Unreadable(file, e)
        }
        return text
    }

    private fun acquire(lock: ReentrantLock, deadlineNanos: Long?): Boolean {
        if (deadlineNanos == null) {
            lock.lock()
            return true
        }
        return lock.tryLock((deadlineNanos - System.nanoTime()).coerceAtLeast(0L), TimeUnit.NANOSECONDS)
    }

    /**
     * [FileChannel.lock] has no timeout, so a bounded wait polls [FileChannel.tryLock] instead.
     * The poll is coarse on purpose: these transactions are milliseconds, so anything that has to
     * wait at all is already an anomaly worth a few extra of them.
     */
    private fun acquireFileLock(channel: FileChannel, deadlineNanos: Long?): FileLock? {
        if (deadlineNanos == null) return channel.lock()
        while (true) {
            val attempt = runCatching { channel.tryLock() }.getOrNull()
            if (attempt != null) return attempt
            val remainingNanos = deadlineNanos - System.nanoTime()
            if (remainingNanos <= 0L) return null
            Thread.sleep(minOf(POLL_MILLIS, remainingNanos / 1_000_000L + 1L))
        }
    }

    private const val POLL_MILLIS = 25L

    /** Replaces [file] through a temporary file in the same directory and an atomic move. */
    fun writeAtomically(file: Path, content: String) {
        val dir = file.toAbsolutePath().parent
        Files.createDirectories(dir)
        val tmp = Files.createTempFile(dir, file.fileName.toString(), ".tmp")
        try {
            Files.writeString(tmp, content)
            runCatching { Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE) }
                .recoverCatching { Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING) }
                .getOrThrow()
        } finally {
            runCatching { Files.deleteIfExists(tmp) }
        }
    }
}

/**
 * Applies one writer's change to a queue another writer may have moved on.
 *
 * [believed] is what the writer last agreed the file held, [updated] is what it wants, and
 * [onDisk] is what is actually there. The difference between the first two is the writer's own
 * change; everything else on disk belongs to somebody else and is kept.
 *
 * Deletions have to come from that difference rather than from absence, or a comment the author
 * deleted would reappear every time the two writers disagree.
 */
fun mergeOntoDisk(
    onDisk: List<SlideReviewComment>,
    believed: List<SlideReviewComment>,
    updated: List<SlideReviewComment>,
): List<SlideReviewComment> {
    val believedById = believed.associateBy { it.id }
    val updatedById = updated.associateBy { it.id }
    val deleted = believedById.keys - updatedById.keys
    val changed = updated.filter { believedById[it.id] != it }.associateBy { it.id }

    val result = mutableListOf<SlideReviewComment>()
    val seen = mutableSetOf<String>()
    for (existing in onDisk) {
        if (existing.id in deleted) continue
        result.add(changed[existing.id] ?: existing)
        seen.add(existing.id)
    }
    // Anything this writer added that disk has not seen yet keeps the order it was added in.
    for (item in updated) {
        if (item.id !in seen && item.id !in deleted) result.add(item)
    }
    return result
}
