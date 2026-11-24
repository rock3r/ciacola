package dev.sebastiano.ciacola.decks

import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/**
 * Cross-process serialization for the review queue, and the one place that touches its bytes.
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
internal object DeckReviewQueueFile {

    const val LOCK_FILE_NAME = "review-queue.lock"

    /** A queue file that exists but cannot be read or parsed. Distinct from "no queue yet". */
    class Unreadable(val file: Path, cause: Throwable?) : IOException(
        "Review queue at ${file.toAbsolutePath()} exists but could not be read: " +
            (cause?.message ?: "invalid JSON"),
        cause,
    )

    /**
     * Runs [block] while holding the queue lock for [dir].
     *
     * [FileChannel.lock] blocks until the holder releases, so a transaction waits rather than
     * failing: these transactions are a few milliseconds of JSON, and an author who has to wait
     * that long has not noticed.
     */
    fun <T> withLock(dir: Path, block: () -> T): T {
        Files.createDirectories(dir)
        val lockFile = dir.resolve(LOCK_FILE_NAME)
        FileChannel.open(
            lockFile,
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
        ).use { channel ->
            var lock: FileLock? = null
            try {
                lock = channel.lock()
                return block()
            } finally {
                runCatching { lock?.release() }
            }
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
