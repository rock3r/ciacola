package dev.sebastiano.ciacola.document

import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal actual fun <T> withDocumentLock(lock: Any, block: () -> T): T = synchronized(lock) { block() }

internal actual fun identityHashCode(value: Any): Int = System.identityHashCode(value)

/**
 * Injectable dual clock (wall + monotonic) for deterministic quarantine retention testing.
 */
interface SyncClock {
    fun wallTimeMillis(): Long
    fun monotonicTimeMillis(): Long

    companion object {
        val System: SyncClock = object : SyncClock {
            override fun wallTimeMillis(): Long = java.lang.System.currentTimeMillis()
            override fun monotonicTimeMillis(): Long = java.lang.System.nanoTime() / 1_000_000L
        }
    }
}

/**
 * Named checkpoints in [DeckFileSync]'s write, recovery, and sweep protocols where a test
 * harness can pause, perform concurrent external operations, or throw a simulated crash.
 */
enum class SyncStep {
    WriteBeforeCheck,
    WriteAfterCheck,
    WriteAfterTmp,
    WriteAfterMeta,
    WriteAfterJournal,
    WriteAfterExchange,
    WriteAfterHashDisplaced,
    WriteAfterQuarantine,
    WriteAfterDeleteJournal,
    RecoveryBeforeRenameTmp,
    RecoveryAfterRenameTmp,
    RecoveryAfterDeleteJournal,
    SweepBeforeHash,
    SweepAfterHashBeforeDelete,
    SweepAfterDeleteEntryBeforeMeta,
    PollBeforeApplyExternal,
}

/** Quarantine entry classification matching the sync quarantine filename suffixes. */
enum class QuarantineKind(val suffix: String) {
    Base("base"),
    Theirs("theirs"),
    Mine("mine"),
    Unknown("unknown"),
}

data class QuarantineEntry(
    val fileName: String,
    val path: Path,
    val txid: String,
    val quarantinedAtMillis: Long,
    val kind: QuarantineKind,
    val dismissed: Boolean,
    val hasReadableMeta: Boolean,
)

data class RecoveryNotice(
    val kind: String,
    val message: String,
    val quarantinePath: Path? = null,
)

sealed interface CloseFlushResult {
    data object Clean : CloseFlushResult
    data class HeldConflict(val conflict: ConflictInfo) : CloseFlushResult
    data class HeldWriteFailed(val failure: WriteFailure) : CloseFlushResult
    data class HeldExternalInvalid(val errors: List<DocumentIssue>) : CloseFlushResult
}

@Serializable
internal data class SyncJournal(val txid: String)

@Serializable
internal data class SyncMeta(
    val txid: String,
    val baseHash: String,
    val newHash: String,
)

internal object SyncFilesHelper {
    val syncJson = Json {
        ignoreUnknownKeys = false
        encodeDefaults = true
    }

    fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            val i = b.toInt() and 0xFF
            sb.append("0123456789abcdef"[i ushr 4])
            sb.append("0123456789abcdef"[i and 0x0F])
        }
        return sb.toString()
    }

    fun readJournalOrNull(journalFile: Path): SyncJournal? {
        if (!Files.exists(journalFile)) return null
        return runCatching {
            val raw = Files.readString(journalFile, StandardCharsets.UTF_8)
            syncJson.decodeFromString(SyncJournal.serializer(), raw)
        }.getOrNull()
    }

    fun readMetaOrNull(quarantineDir: Path, txid: String): SyncMeta? {
        val metaPath = quarantineDir.resolve("$txid.meta")
        if (!Files.exists(metaPath)) return null
        return runCatching {
            val raw = Files.readString(metaPath, StandardCharsets.UTF_8)
            val parsed = syncJson.decodeFromString(SyncMeta.serializer(), raw)
            if (parsed.txid == txid && parsed.baseHash.isNotEmpty() && parsed.newHash.isNotEmpty()) parsed else null
        }.getOrNull()
    }

    fun listStrayTmpFiles(syncDir: Path): List<Path> {
        if (!Files.isDirectory(syncDir)) return emptyList()
        return Files.list(syncDir).use { stream ->
            stream.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".tmp") }
                .sorted()
                .toList()
        }
    }

    fun scanQuarantineEntries(
        quarantineDir: Path,
        dismissedNames: Set<String>,
    ): List<QuarantineEntry> {
        if (!Files.isDirectory(quarantineDir)) return emptyList()
        val result = mutableListOf<QuarantineEntry>()
        Files.list(quarantineDir).use { stream ->
            for (path in stream.sorted().toList()) {
                if (!Files.isRegularFile(path)) continue
                val name = path.fileName.toString()
                val parsed = parseQuarantineFileName(name) ?: continue
                result += QuarantineEntry(
                    fileName = name,
                    path = path,
                    txid = parsed.first,
                    quarantinedAtMillis = parsed.second,
                    kind = parsed.third,
                    dismissed = name in dismissedNames,
                    hasReadableMeta = readMetaOrNull(quarantineDir, parsed.first) != null,
                )
            }
        }
        return result
    }

    fun parseQuarantineFileName(name: String): Triple<String, Long, QuarantineKind>? {
        val dot = name.lastIndexOf('.')
        if (dot <= 0) return null
        val ext = name.substring(dot + 1)
        val kind = QuarantineKind.entries.firstOrNull { it.suffix == ext } ?: return null
        val stem = name.substring(0, dot)
        val dash = stem.lastIndexOf('-')
        if (dash <= 0) return null
        val txid = stem.substring(0, dash)
        val millis = stem.substring(dash + 1).toLongOrNull() ?: return null
        return Triple(txid, millis, kind)
    }

    fun readTargetHashOrNull(targetFile: Path): String? = runCatching {
        sha256Hex(Files.readAllBytes(targetFile))
    }.getOrNull()

    fun loadDismissedSet(dismissedFile: Path, target: MutableSet<String>) {
        target.clear()
        if (!Files.exists(dismissedFile)) return
        runCatching {
            Files.readAllLines(dismissedFile, StandardCharsets.UTF_8)
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .forEach { target.add(it) }
        }
    }

    fun persistDismissedSet(
        dismissedFile: Path,
        syncDir: Path,
        dismissedNames: Set<String>,
        fileOps: AtomicFileOps,
    ) {
        runCatching {
            val content = if (dismissedNames.isEmpty()) "" else dismissedNames.joinToString("\n") + "\n"
            fileOps.writeFileAndFsync(dismissedFile, content.toByteArray(StandardCharsets.UTF_8))
            fileOps.fsyncDirectory(syncDir)
        }
    }

    fun deckRecoveryDir(recoveryRootDir: Path, targetFile: Path): Path {
        val deckId = targetFile.fileName.toString().removeSuffix(".deck.json").ifBlank { "deck" }
        return recoveryRootDir.resolve(deckId)
    }

    fun writeRecoveryCopy(
        recoveryRootDir: Path,
        targetFile: Path,
        bytes: ByteArray,
        label: String,
        clock: SyncClock,
        fileOps: AtomicFileOps,
    ): Result<Path> = try {
        val dir = deckRecoveryDir(recoveryRootDir, targetFile)
        Files.createDirectories(dir)
        val file = dir.resolve("${clock.wallTimeMillis()}-$label-${UUID.randomUUID().toString().take(8)}.deck.json")
        fileOps.writeFileAndFsync(file, bytes)
        fileOps.fsyncDirectory(dir)
        Result.success(file)
    } catch (e: Exception) {
        Result.failure(e)
    }

    fun listRecoveryCopies(recoveryRootDir: Path, targetFile: Path): List<Path> {
        val dir = deckRecoveryDir(recoveryRootDir, targetFile)
        if (!Files.isDirectory(dir)) return emptyList()
        return Files.list(dir).use { stream -> stream.filter { Files.isRegularFile(it) }.sorted().toList() }
    }

    fun cleanupBeforeExchange(
        journalFile: Path,
        tmpPath: Path,
        metaPath: Path,
        syncDir: Path,
        quarantineDir: Path,
        fileOps: AtomicFileOps,
    ) {
        runCatching { fileOps.deleteIfExists(journalFile) }
        runCatching { fileOps.deleteIfExists(tmpPath) }
        runCatching { fileOps.deleteIfExists(metaPath) }
        runCatching { fileOps.fsyncDirectory(syncDir) }
        runCatching { fileOps.fsyncDirectory(quarantineDir) }
    }

    fun deleteMetaIfNoEntriesRemain(
        txid: String,
        syncDir: Path,
        quarantineDir: Path,
        journalFile: Path,
        dismissedNames: Set<String>,
        fileOps: AtomicFileOps,
    ) {
        val remaining = scanQuarantineEntries(quarantineDir, dismissedNames).any { it.txid == txid }
        val tmpExists = Files.exists(syncDir.resolve("$txid.tmp"))
        val journalMatches = readJournalOrNull(journalFile)?.txid == txid
        if (!remaining && !tmpExists && !journalMatches) {
            fileOps.deleteIfExists(quarantineDir.resolve("$txid.meta"))
            fileOps.fsyncDirectory(quarantineDir)
        }
    }

    fun cleanupOrphanMetaFiles(
        syncDir: Path,
        quarantineDir: Path,
        journalFile: Path,
        dismissedNames: Set<String>,
        fileOps: AtomicFileOps,
    ) {
        if (!Files.isDirectory(quarantineDir)) return
        val activeTxids = scanQuarantineEntries(quarantineDir, dismissedNames).mapTo(HashSet()) { it.txid }
        val journalTxid = readJournalOrNull(journalFile)?.txid
        Files.list(quarantineDir).use { stream ->
            for (p in stream.toList()) {
                val name = p.fileName.toString()
                if (!name.endsWith(".meta")) continue
                val txid = name.removeSuffix(".meta")
                if (txid in activeTxids || txid == journalTxid) continue
                if (Files.exists(syncDir.resolve("$txid.tmp"))) continue
                if (readMetaOrNull(quarantineDir, txid) != null) {
                    fileOps.deleteIfExists(p)
                    fileOps.fsyncDirectory(quarantineDir)
                }
            }
        }
    }

    fun rebuildStatusForTxid(
        txid: String,
        meta: SyncMeta?,
        targetHash: String,
        targetFile: Path,
        quarantineDir: Path,
        dismissedNames: Set<String>,
        resolvedConflictNames: Set<String> = emptySet(),
        store: DeckDocumentStore,
        onNotice: (RecoveryNotice) -> Unit,
    ) {
        val entries = scanQuarantineEntries(quarantineDir, dismissedNames)
            .filter { it.txid == txid && !it.dismissed }
        val theirsEntry = entries.firstOrNull { it.kind == QuarantineKind.Theirs && it.fileName !in resolvedConflictNames }
        val mineEntry = entries.firstOrNull { it.kind == QuarantineKind.Mine }
        val targetEqualsNew = meta != null && targetHash == meta.newHash

        when {
            theirsEntry != null && targetEqualsNew -> {
                val theirsHash = runCatching { sha256Hex(Files.readAllBytes(theirsEntry.path)) }.getOrDefault("")
                store.setSyncState(
                    SyncState.Conflict(
                        ConflictInfo(
                            kind = ConflictKind.Overwrote,
                            targetPath = targetFile.toString(),
                            theirsPath = theirsEntry.path.toString(),
                            theirsHash = theirsHash,
                            txId = txid,
                            reason = "Recovered save displaced an external version; see quarantine.",
                        ),
                    ),
                )
            }

            theirsEntry != null -> {
                onNotice(
                    RecoveryNotice(
                        kind = "theirs",
                        message = "Recovered external version in quarantine (${theirsEntry.fileName}); target loaded as current.",
                        quarantinePath = theirsEntry.path,
                    ),
                )
            }

            mineEntry != null && targetEqualsNew -> {
                if (store.sync !is SyncState.Conflict) {
                    store.setSyncState(if (store.isDirty) SyncState.Dirty else SyncState.Saved)
                }
            }

            mineEntry != null -> {
                onNotice(
                    RecoveryNotice(
                        kind = "unsaved_at_crash",
                        message = "Studio's last edit was unsaved at crash; bytes preserved in quarantine (${mineEntry.fileName}).",
                        quarantinePath = mineEntry.path,
                    ),
                )
            }

            targetEqualsNew -> {
                if (store.sync !is SyncState.Conflict) {
                    store.setSyncState(if (store.isDirty) SyncState.Dirty else SyncState.Saved)
                }
            }

            else -> {
                if (meta != null && targetHash != meta.baseHash) {
                    onNotice(
                        RecoveryNotice(
                            kind = "external_change",
                            message = "Target changed externally while Studio was closed; loaded current disk content.",
                        ),
                    )
                }
            }
        }
    }

    suspend fun sweepQuarantine(
        targetFile: Path,
        syncDir: Path,
        quarantineDir: Path,
        journalFile: Path,
        dismissedFile: Path,
        dismissedNames: MutableSet<String>,
        resolvedConflictsFile: Path,
        resolvedConflictNames: MutableSet<String>,
        firstObservedMonoMs: MutableMap<String, Long>,
        retentionMs: Long,
        clock: SyncClock,
        fileOps: AtomicFileOps,
        store: DeckDocumentStore,
        stepHook: suspend (SyncStep) -> Unit,
        onNotice: (RecoveryNotice) -> Unit,
    ) {
        val wallNow = clock.wallTimeMillis()
        val monoNow = clock.monotonicTimeMillis()
        val entries = scanQuarantineEntries(quarantineDir, dismissedNames)

        val presentNames = HashSet<String>(entries.size)
        for (entry in entries) {
            presentNames.add(entry.fileName)
            firstObservedMonoMs.putIfAbsent(entry.fileName, monoNow)
        }
        firstObservedMonoMs.keys.retainAll(presentNames)

        val metaCache = HashMap<String, SyncMeta?>()
        for (entry in entries) {
            val meta = metaCache.getOrPut(entry.txid) { readMetaOrNull(quarantineDir, entry.txid) }
            // Missing or unreadable meta -> NEVER delete and NEVER reclassify.
            if (meta == null) continue

            val wallAge = (wallNow - entry.quarantinedAtMillis).coerceAtLeast(0L)
            val monoAge = (monoNow - (firstObservedMonoMs[entry.fileName] ?: monoNow)).coerceAtLeast(0L)
            if (wallAge < retentionMs || monoAge < retentionMs) continue

            when (entry.kind) {
                QuarantineKind.Base -> {
                    stepHook(SyncStep.SweepBeforeHash)
                    val currentHash = try {
                        sha256Hex(Files.readAllBytes(entry.path))
                    } catch (_: IOException) {
                        continue
                    }
                    if (currentHash == meta.baseHash) {
                        stepHook(SyncStep.SweepAfterHashBeforeDelete)
                        fileOps.deleteIfExists(entry.path)
                        fileOps.fsyncDirectory(quarantineDir)
                        firstObservedMonoMs.remove(entry.fileName)
                        stepHook(SyncStep.SweepAfterDeleteEntryBeforeMeta)
                        deleteMetaIfNoEntriesRemain(
                            txid = entry.txid,
                            syncDir = syncDir,
                            quarantineDir = quarantineDir,
                            journalFile = journalFile,
                            dismissedNames = dismissedNames,
                            fileOps = fileOps,
                        )
                    } else {
                        val newName = "${entry.txid}-${entry.quarantinedAtMillis}.${QuarantineKind.Theirs.suffix}"
                        val newPath = quarantineDir.resolve(newName)
                        fileOps.atomicMove(entry.path, newPath)
                        fileOps.fsyncDirectory(quarantineDir)
                        firstObservedMonoMs.remove(entry.fileName)
                        firstObservedMonoMs[newName] = monoNow
                        if (store.sync is SyncState.Saved || store.sync is SyncState.Dirty) {
                            store.setSyncState(
                                SyncState.Conflict(
                                    ConflictInfo(
                                        kind = ConflictKind.Overwrote,
                                        targetPath = targetFile.toString(),
                                        theirsPath = newPath.toString(),
                                        theirsHash = currentHash,
                                        txId = entry.txid,
                                        reason = "A tool with an open file handle modified a displaced file after save.",
                                    ),
                                ),
                            )
                        } else {
                            onNotice(
                                RecoveryNotice(
                                    kind = "theirs_from_base",
                                    message = "Displaced base file was modified after save and promoted to quarantine: $newName",
                                    quarantinePath = newPath,
                                ),
                            )
                        }
                    }
                }

                QuarantineKind.Theirs, QuarantineKind.Mine, QuarantineKind.Unknown -> {
                    if (entry.dismissed) {
                        stepHook(SyncStep.SweepAfterHashBeforeDelete)
                        fileOps.deleteIfExists(entry.path)
                        fileOps.fsyncDirectory(quarantineDir)
                        firstObservedMonoMs.remove(entry.fileName)
                        dismissedNames.remove(entry.fileName)
                        persistDismissedSet(dismissedFile, syncDir, dismissedNames, fileOps)
                        if (resolvedConflictNames.remove(entry.fileName)) {
                            persistDismissedSet(resolvedConflictsFile, syncDir, resolvedConflictNames, fileOps)
                        }
                        stepHook(SyncStep.SweepAfterDeleteEntryBeforeMeta)
                        deleteMetaIfNoEntriesRemain(
                            txid = entry.txid,
                            syncDir = syncDir,
                            quarantineDir = quarantineDir,
                            journalFile = journalFile,
                            dismissedNames = dismissedNames,
                            fileOps = fileOps,
                        )
                    }
                }
            }
        }

        cleanupOrphanMetaFiles(syncDir, quarantineDir, journalFile, dismissedNames, fileOps)
    }
}

internal class SyncDirectoryLock(
    private val deckDir: Path,
    private val syncDir: Path,
    private val quarantineDir: Path,
    private val lockFile: Path,
    private val fileOps: AtomicFileOps,
) {
    private val guard = Any()
    private var lockChannel: FileChannel? = null
    private var fileLock: FileLock? = null

    fun ensureDirectoriesAndAcquire(): Boolean {
        if (!Files.isDirectory(syncDir)) {
            Files.createDirectories(syncDir)
            fileOps.fsyncDirectory(deckDir)
        }
        if (!Files.isDirectory(quarantineDir)) {
            Files.createDirectories(quarantineDir)
            fileOps.fsyncDirectory(syncDir)
        }
        return synchronized(guard) {
            if (fileLock?.isValid == true) return@synchronized true
            try {
                val channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
                val lock = try {
                    channel.tryLock()
                } catch (_: OverlappingFileLockException) {
                    null
                }
                if (lock == null) {
                    runCatching { channel.close() }
                    false
                } else {
                    lockChannel = channel
                    fileLock = lock
                    true
                }
            } catch (_: IOException) {
                false
            }
        }
    }

    fun release() {
        synchronized(guard) {
            runCatching { fileLock?.release() }
            fileLock = null
            runCatching { lockChannel?.close() }
            lockChannel = null
        }
    }
}
