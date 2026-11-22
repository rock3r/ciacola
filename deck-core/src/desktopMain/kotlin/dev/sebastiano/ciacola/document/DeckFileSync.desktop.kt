package dev.sebastiano.ciacola.document

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Crash-safe, conflict-preserving single-writer sync engine for `<deckId>.deck.json`.
 *
 * All disk I/O runs on [ioDispatcher] (a dedicated single-lane IO dispatcher by default) and is
 * serialized through [ioMutex], never overwrites the target without atomic exchange, preserves
 * displaced files in `.<name>.sync/quarantine/` under conservative dual-clock 10-minute retention,
 * and holds close on unresolved conflict, write failure, or unsaved edits.
 */
class DeckFileSync(
    val targetFile: Path,
    val recoveryRootDir: Path,
    val store: DeckDocumentStore,
    private val fileOps: AtomicFileOps = NativeAtomicFileOps,
    private val clock: SyncClock = SyncClock.System,
    private val debounceMs: Long = DEFAULT_DEBOUNCE_MS,
    private val pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS,
    private val sweepIntervalMs: Long = DEFAULT_SWEEP_INTERVAL_MS,
    private val retentionMs: Long = DEFAULT_RETENTION_MS,
    private val specimenRegistry: DeckSpecimenRegistry = DeckSpecimenRegistry.Empty,
    private val migrations: DeckMigrations = DeckMigrations.Default,
    private val stepHook: suspend (SyncStep) -> Unit = {},
    private val effectsRegistry: BuildEffectRegistry = BuildEffectRegistry.Standard,
    private val assetExists: ((String) -> Boolean)? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1),
) : AutoCloseable {

    val deckDir: Path = targetFile.toAbsolutePath().normalize().parent
        ?: error("Deck file must have a parent directory: $targetFile")
    val syncDir: Path = deckDir.resolve(".${targetFile.fileName}.sync")
    val quarantineDir: Path = syncDir.resolve("quarantine")
    val journalFile: Path = syncDir.resolve("journal")
    val lockFile: Path = syncDir.resolve("lock")
    private val dismissedFile: Path = syncDir.resolve("dismissed")
    private val resolvedConflictsFile: Path = syncDir.resolve("resolved-conflicts")

    private val ioMutex = Mutex()
    private val closed = AtomicBoolean(false)
    private val noticesLock = Any()
    private val dirLock = SyncDirectoryLock(deckDir, syncDir, quarantineDir, lockFile, fileOps)

    private var watcherJob: Job? = null

    private var lastObservedSize: Long = -1L
    private var lastObservedMtimeMs: Long = -1L
    private var inFlightTxId: String? = null
    private var inFlightRevision: Long? = null
    private val dismissedNames: MutableSet<String> = LinkedHashSet()
    private val resolvedConflictNames: MutableSet<String> = LinkedHashSet()
    private val firstObservedMonoMs: MutableMap<String, Long> = LinkedHashMap()
    private val notices: MutableList<RecoveryNotice> = mutableListOf()

    @Volatile
    var diskHash: String? = null
        private set

    @Volatile
    var externalEditLintWarnings: List<DocumentIssue> = emptyList()
        private set

    val savedRevision: Long
        get() = store.savedRevision

    val recoveryNotices: List<RecoveryNotice>
        get() = synchronized(noticesLock) { notices.toList() }

    val isDirty: Boolean
        get() = store.isDirty

    init {
        if (store.hasDefaultValidationRegistries()) {
            store.configureValidation(
                specimens = specimenRegistry,
                effects = effectsRegistry,
                assetExists = assetExists,
            )
        }
    }

    /**
     * Opens the deck file: checks format version, acquires `.<name>.sync/lock`, probes atomic
     * exchange support, recovers any interrupted transaction, runs an initial sweep, and loads the
     * document into [store] WITHOUT writing to [targetFile].
     */
    suspend fun open(): DeckLoadState = withContext(ioDispatcher) {
        ioMutex.withLock {
            store.markLoading()
            clearNotices()

            if (!Files.exists(targetFile)) {
                store.markLoadInvalid(
                    listOf(
                        DocumentIssue(
                            severity = IssueSeverity.LoadError,
                            code = "FILE_NOT_FOUND",
                            address = targetFile.toString(),
                            message = "Deck file does not exist: $targetFile",
                        ),
                    ),
                )
                return@withLock store.load
            }

            val initialBytes = try {
                Files.readAllBytes(targetFile)
            } catch (e: IOException) {
                store.markLoadInvalid(
                    listOf(
                        DocumentIssue(
                            severity = IssueSeverity.LoadError,
                            code = "IO_ERROR",
                            address = targetFile.toString(),
                            message = "Failed to read deck file: ${e.message}",
                        ),
                    ),
                )
                return@withLock store.load
            }

            recordStatSnapshot()
            val initialHash = SyncFilesHelper.sha256Hex(initialBytes)

            val decoded = DeckDocumentCodec.decode(
                rawText = initialBytes.toString(StandardCharsets.UTF_8),
                specimens = specimenRegistry,
                effects = effectsRegistry,
                migrations = migrations,
                assetExists = assetExists,
            )

            when (decoded) {
                is DeckDecodeResult.ReadOnlyNewerFormat -> {
                    diskHash = initialHash
                    store.markReadOnlyNewerFormat(
                        foundVersion = decoded.foundVersion,
                        supportedVersion = decoded.supportedVersion,
                        message = decoded.message,
                    )
                    store.load
                }

                is DeckDecodeResult.Failure -> {
                    diskHash = initialHash
                    store.markLoadInvalid(decoded.errors)
                    store.load
                }

                is DeckDecodeResult.Success -> initializeWritableOrReadOnlyLocked(decoded, initialHash)
            }
        }
    }

    /**
     * Starts the single writer/watcher coroutine on [ioDispatcher] that debounces edits, polls
     * external changes every [pollIntervalMs], and sweeps quarantine every [sweepIntervalMs].
     */
    fun startBackgroundSync(scope: CoroutineScope) {
        if (watcherJob?.isActive == true) return
        watcherJob = scope.launch(ioDispatcher) {
            var lastDirtyRevision = store.savedRevision
            var dirtySinceMonoMs = -1L
            var lastSweepMonoMs = clock.monotonicTimeMillis()

            while (isActive && !closed.get()) {
                delay(pollIntervalMs)
                if (closed.get()) break

                val nowMono = clock.monotonicTimeMillis()
                try {
                    ioMutex.withLock { pollExternalChangesInternal() }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Throwable) {
                    // Reflected in SyncState.
                }

                val curRev = store.revision
                if (!store.isReadOnly && curRev > store.savedRevision && !store.hasOpenTransaction && canStartAutoWrite()) {
                    if (curRev != lastDirtyRevision || dirtySinceMonoMs < 0L) {
                        lastDirtyRevision = curRev
                        dirtySinceMonoMs = nowMono
                        if (store.sync == SyncState.Saved) {
                            store.setSyncState(SyncState.Dirty)
                        }
                    }
                    if (nowMono - dirtySinceMonoMs >= debounceMs) {
                        dirtySinceMonoMs = -1L
                        try {
                            ioMutex.withLock {
                                if (!store.isReadOnly && !store.hasOpenTransaction && canStartAutoWrite() && store.isDirty) {
                                    writeIfDirtyInternal()
                                }
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Throwable) {
                            // Captured into SyncState.WriteFailed.
                        }
                    }
                } else {
                    dirtySinceMonoMs = -1L
                }

                if (nowMono - lastSweepMonoMs >= sweepIntervalMs) {
                    lastSweepMonoMs = nowMono
                    try {
                        ioMutex.withLock { if (!store.isReadOnly) sweepLocked() }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Throwable) {
                        // Best-effort periodic sweep.
                    }
                }
            }
        }
    }

    /** Explicitly flushes dirty committed edits now. Never writes when clean, read-only, or blocked. */
    suspend fun flushNow(): SyncState = withContext(ioDispatcher) {
        ioMutex.withLock {
            if (store.isReadOnly || store.load != DeckLoadState.Loaded || !store.isDirty || !canStartAutoWrite()) {
                return@withLock store.sync
            }
            writeIfDirtyInternal()
            store.sync
        }
    }

    /** Synchronous close flush; holds close if conflict, failure, or unsaved edits remain. */
    suspend fun flushOnClose(): CloseFlushResult = withContext(ioDispatcher) {
        ioMutex.withLock {
            if (!store.isReadOnly && store.load == DeckLoadState.Loaded && store.isDirty && canStartAutoWrite()) {
                writeIfDirtyInternal()
            }
            when (val st = store.sync) {
                is SyncState.Conflict -> CloseFlushResult.HeldConflict(st.info)
                is SyncState.WriteFailed -> CloseFlushResult.HeldWriteFailed(st.failure)
                is SyncState.ExternalInvalid -> CloseFlushResult.HeldExternalInvalid(st.errors)
                is SyncState.ReadOnly -> if (store.isDirty) {
                    CloseFlushResult.HeldWriteFailed(
                        WriteFailure.BeforeExchange("Cannot save unsaved edits while read-only: ${st.reason}"),
                    )
                } else {
                    CloseFlushResult.Clean
                }

                SyncState.Saved, SyncState.Dirty -> if (store.isDirty) {
                    CloseFlushResult.HeldWriteFailed(
                        WriteFailure.BeforeExchange("Unsaved edits remain in memory after close flush."),
                    )
                } else {
                    CloseFlushResult.Clean
                }
            }
        }
    }

    suspend fun pollExternalChangesNow(): SyncState = withContext(ioDispatcher) {
        ioMutex.withLock {
            pollExternalChangesInternal()
            store.sync
        }
    }

    suspend fun sweepNow() = withContext(ioDispatcher) {
        ioMutex.withLock {
            if (!store.isReadOnly) sweepLocked()
        }
    }

    suspend fun retryUnresolvedTransaction(): SyncState = withContext(ioDispatcher) {
        ioMutex.withLock {
            if (store.isReadOnly) return@withLock store.sync
            val targetHash = SyncFilesHelper.readTargetHashOrNull(targetFile) ?: return@withLock store.sync
            if (!recoverCatchingIo(targetHash, "Failed to recover unresolved transaction", isOnOpen = false)) {
                return@withLock store.sync
            }
            if (!Files.exists(journalFile)) {
                store.reconcileSyncState(clearWriteFailed = true)
            } else {
                store.reconcileSyncState()
            }
            store.sync
        }
    }

    suspend fun listQuarantineEntries(includeDismissed: Boolean = false): List<QuarantineEntry> = withContext(ioDispatcher) {
        ioMutex.withLock {
            SyncFilesHelper.scanQuarantineEntries(quarantineDir, dismissedNames)
                .filter { includeDismissed || !it.dismissed }
        }
    }

    /** Hides a `.theirs`/`.mine`/`.unknown` entry immediately; never deletes before retention completes. */
    suspend fun dismissQuarantineEntry(fileName: String): Boolean = withContext(ioDispatcher) {
        ioMutex.withLock {
            val target = SyncFilesHelper.scanQuarantineEntries(quarantineDir, dismissedNames)
                .firstOrNull { it.fileName == fileName } ?: return@withLock false
            if (target.kind == QuarantineKind.Base) return@withLock false
            dismissedNames.add(fileName)
            SyncFilesHelper.persistDismissedSet(dismissedFile, syncDir, dismissedNames, fileOps)
            try {
                sweepLocked()
            } catch (_: IOException) {
                // Best-effort sweep after dismissal; entry remains hidden immediately.
            }
            true
        }
    }

    /** Conflict action **Keep mine**. */
    suspend fun keepMine(): SyncState = withContext(ioDispatcher) {
        ioMutex.withLock {
            val st = store.sync as? SyncState.Conflict ?: return@withLock store.sync
            when (st.info.kind) {
                ConflictKind.Unwritten -> {
                    // Anchor Step 2 on the observed external hash that opened this banner so
                    // a newer unseen external replacement is caught as a fresh Unwritten conflict
                    // instead of being silently filed as .base.
                    diskHash = st.info.observedTheirsHash
                    store.setSyncState(SyncState.Dirty)
                    writeIfDirtyInternal(forceWrite = true)
                }

                ConflictKind.Overwrote -> {
                    val theirsName = Path.of(st.info.theirsPath).fileName?.toString()
                    if (!theirsName.isNullOrBlank() && resolvedConflictNames.add(theirsName)) {
                        SyncFilesHelper.persistDismissedSet(resolvedConflictsFile, syncDir, resolvedConflictNames, fileOps)
                    }
                    store.reconcileSyncState(clearConflict = true)
                }
            }
            store.sync
        }
    }

    /**
     * Conflict action **Take theirs**:
     * Writes recovery copy of current committed Studio document first; refuses action if that write
     * fails. For `Overwrote`, runs the normal write protocol (no rollback exchange).
     */
    suspend fun takeTheirs(): Result<Path> = withContext(ioDispatcher) {
        ioMutex.withLock {
            val st = store.sync as? SyncState.Conflict
                ?: return@withLock Result.failure(IllegalStateException("No active conflict to resolve."))

            val committedDoc = store.committedSnapshot().first
            val recoveryCopy = SyncFilesHelper.writeRecoveryCopy(
                recoveryRootDir = recoveryRootDir,
                targetFile = targetFile,
                bytes = CanonicalJson.print(committedDoc).toByteArray(StandardCharsets.UTF_8),
                label = "mine-before-take-theirs",
                clock = clock,
                fileOps = fileOps,
            ).getOrElse { return@withLock Result.failure(it) }

            when (st.info.kind) {
                ConflictKind.Unwritten -> {
                    val targetBytes = try {
                        Files.readAllBytes(targetFile)
                    } catch (e: IOException) {
                        return@withLock Result.failure(e)
                    }
                    val decoded = DeckDocumentCodec.decode(
                        rawText = targetBytes.toString(StandardCharsets.UTF_8),
                        specimens = specimenRegistry,
                        effects = effectsRegistry,
                        migrations = migrations,
                        assetExists = assetExists,
                    )
                    if (decoded !is DeckDecodeResult.Success) {
                        return@withLock Result.failure(IllegalStateException("Target file is not a valid deck."))
                    }
                    store.applyExternalChange(
                        newDocument = decoded.document,
                        label = "Take theirs",
                        markAsSaved = true,
                        issues = decoded.lintIssues,
                    )
                    externalEditLintWarnings = FragmentProvenance.lintExternalDocumentEdit(committedDoc, decoded.document)
                    diskHash = SyncFilesHelper.sha256Hex(targetBytes)
                    recordStatSnapshot()
                    store.setSyncState(SyncState.Saved)
                    Result.success(recoveryCopy)
                }

                ConflictKind.Overwrote -> {
                    val theirsPath = Path.of(st.info.theirsPath)
                    val theirsBytes = try {
                        Files.readAllBytes(theirsPath)
                    } catch (e: IOException) {
                        return@withLock Result.failure(e)
                    }
                    val decoded = DeckDocumentCodec.decode(
                        rawText = theirsBytes.toString(StandardCharsets.UTF_8),
                        specimens = specimenRegistry,
                        effects = effectsRegistry,
                        migrations = migrations,
                        assetExists = assetExists,
                    )
                    if (decoded !is DeckDecodeResult.Success) {
                        return@withLock Result.failure(IllegalStateException("Quarantined .theirs file is not a valid deck."))
                    }
                    store.applyExternalChange(
                        newDocument = decoded.document,
                        label = "Take theirs",
                        markAsSaved = false,
                        issues = decoded.lintIssues,
                    )
                    externalEditLintWarnings = FragmentProvenance.lintExternalDocumentEdit(committedDoc, decoded.document)
                    store.setSyncState(SyncState.Dirty)
                    // Normal write protocol: step 2 checks target against diskHash.
                    writeIfDirtyInternal(forceWrite = true)
                    if (store.sync == SyncState.Saved) {
                        val theirsName = theirsPath.fileName?.toString()
                        if (!theirsName.isNullOrBlank() && resolvedConflictNames.add(theirsName)) {
                            SyncFilesHelper.persistDismissedSet(resolvedConflictsFile, syncDir, resolvedConflictNames, fileOps)
                        }
                    }
                    Result.success(recoveryCopy)
                }
            }
        }
    }

    /** Banner action for [SyncState.ExternalInvalid]: copies invalid bytes to recovery first. */
    suspend fun overwriteExternalInvalid(): Result<Path> = withContext(ioDispatcher) {
        ioMutex.withLock {
            if (store.sync !is SyncState.ExternalInvalid) {
                return@withLock Result.failure(IllegalStateException("Not in ExternalInvalid state."))
            }
            val invalidBytes = try {
                Files.readAllBytes(targetFile)
            } catch (e: IOException) {
                return@withLock Result.failure(e)
            }
            val recoveryCopy = SyncFilesHelper.writeRecoveryCopy(
                recoveryRootDir = recoveryRootDir,
                targetFile = targetFile,
                bytes = invalidBytes,
                label = "external-invalid",
                clock = clock,
                fileOps = fileOps,
            ).getOrElse { return@withLock Result.failure(it) }
            diskHash = SyncFilesHelper.sha256Hex(invalidBytes)
            store.setSyncState(SyncState.Dirty)
            writeIfDirtyInternal(forceWrite = true)
            Result.success(recoveryCopy)
        }
    }

    suspend fun saveRecoveryCopy(label: String = "manual"): Result<Path> = withContext(ioDispatcher) {
        ioMutex.withLock {
            val committedDoc = store.committedSnapshot().first
            SyncFilesHelper.writeRecoveryCopy(
                recoveryRootDir = recoveryRootDir,
                targetFile = targetFile,
                bytes = CanonicalJson.print(committedDoc).toByteArray(StandardCharsets.UTF_8),
                label = label,
                clock = clock,
                fileOps = fileOps,
            )
        }
    }

    fun listRecoveryCopies(): List<Path> = SyncFilesHelper.listRecoveryCopies(recoveryRootDir, targetFile)

    override fun close() {
        closed.set(true)
        watcherJob?.cancel()
        watcherJob = null
        releaseLock()
    }

    private suspend fun initializeWritableOrReadOnlyLocked(
        decoded: DeckDecodeResult.Success,
        currentHash: String,
    ): DeckLoadState {
        val lockAcquired = try {
            ensureSyncDirectoriesAndLock()
        } catch (e: IOException) {
            diskHash = currentHash
            store.loadInitial(
                doc = decoded.document,
                readOnlyReason = "Cannot initialize sync directory: ${e.message}",
                issues = decoded.lintIssues,
            )
            return store.load
        }
        if (!lockAcquired) {
            diskHash = currentHash
            store.loadInitial(
                doc = decoded.document,
                readOnlyReason = "Another Studio instance holds lock on ${targetFile.fileName}.",
                issues = decoded.lintIssues,
            )
            return store.load
        }

        SyncFilesHelper.loadDismissedSet(dismissedFile, dismissedNames)
        SyncFilesHelper.loadDismissedSet(resolvedConflictsFile, resolvedConflictNames)

        when (val probe = fileOps.probeExchangeSupport(syncDir)) {
            is AtomicExchangeResult.Success -> Unit
            is AtomicExchangeResult.Unsupported -> {
                releaseLock()
                diskHash = currentHash
                store.loadInitial(decoded.document, readOnlyReason = probe.reason, issues = decoded.lintIssues)
                return store.load
            }

            is AtomicExchangeResult.Failed -> {
                releaseLock()
                diskHash = currentHash
                store.loadInitial(decoded.document, readOnlyReason = probe.message, issues = decoded.lintIssues)
                return store.load
            }
        }

        store.loadInitial(decoded.document, readOnlyReason = null, issues = decoded.lintIssues)
        diskHash = currentHash

        if (recoverCatchingIo(currentHash, "Failed to recover interrupted transaction on open", isOnOpen = true)) {
            try {
                sweepLocked()
            } catch (_: IOException) {
                // Best-effort initial sweep; quarantine stays safe until next sweep.
            }
        }
        return store.load
    }

    private suspend fun recoverCatchingIo(
        targetHash: String,
        context: String,
        isOnOpen: Boolean,
    ): Boolean = try {
        recoverLocked(targetHash, isOnOpen = isOnOpen)
        true
    } catch (e: IOException) {
        val txid = SyncFilesHelper.readJournalOrNull(journalFile)?.txid
        val displacedAt = txid?.let { syncDir.resolve("$it.tmp").toString() } ?: syncDir.toString()
        store.setSyncState(SyncState.WriteFailed(WriteFailure.AfterExchange(displacedAt, "$context: ${e.message}")))
        false
    }

    private fun canStartAutoWrite(): Boolean = when (store.sync) {
        SyncState.Saved, SyncState.Dirty -> true
        is SyncState.Conflict,
        is SyncState.ExternalInvalid,
        is SyncState.WriteFailed,
        is SyncState.ReadOnly,
        -> false
    }

    private suspend fun writeIfDirtyInternal(forceWrite: Boolean = false) {
        if (!forceWrite && !store.isDirty) return

        // Never start a second save while S/journal exists; recover first.
        if (Files.exists(journalFile)) {
            val targetHash = SyncFilesHelper.readTargetHashOrNull(targetFile) ?: return
            if (!recoverCatchingIo(targetHash, "Failed to finish recovery before write", isOnOpen = false)) return
            if (store.sync is SyncState.Conflict || store.sync is SyncState.WriteFailed) return
        }

        try {
            sweepLocked()
        } catch (e: IOException) {
            store.setSyncState(
                SyncState.WriteFailed(WriteFailure.BeforeExchange("Quarantine sweep failed before write: ${e.message}")),
            )
            return
        }
        if (store.sync is SyncState.Conflict) return

        val (capturedDoc, capturedRev) = store.committedSnapshot()
        val loadErrors = store.validateDocument(capturedDoc).filter { it.severity == IssueSeverity.LoadError }
        if (loadErrors.isNotEmpty()) {
            val detail = loadErrors.joinToString("; ") { "${it.address}: ${it.message}" }
            store.setSyncState(
                SyncState.WriteFailed(WriteFailure.BeforeExchange("Refused to persist document with load errors: $detail")),
            )
            return
        }

        val serializedBytes = CanonicalJson.print(capturedDoc).toByteArray(StandardCharsets.UTF_8)
        val newHash = SyncFilesHelper.sha256Hex(serializedBytes)
        val baseHash = diskHash ?: run {
            store.setSyncState(SyncState.WriteFailed(WriteFailure.BeforeExchange("Sync baseline hash is not initialized.")))
            return
        }

        stepHook(SyncStep.WriteBeforeCheck)

        // Step 2: Check target hash against diskHash.
        val actualTargetHash = try {
            SyncFilesHelper.sha256Hex(Files.readAllBytes(targetFile))
        } catch (e: IOException) {
            store.setSyncState(SyncState.WriteFailed(WriteFailure.BeforeExchange("Failed to read target: ${e.message}")))
            return
        }
        if (actualTargetHash != baseHash) {
            store.setSyncState(
                SyncState.Conflict(
                    ConflictInfo(
                        kind = ConflictKind.Unwritten,
                        targetPath = targetFile.toString(),
                        theirsPath = targetFile.toString(),
                        theirsHash = actualTargetHash,
                        reason = "Target file changed externally before save.",
                    ),
                ),
            )
            return
        }

        stepHook(SyncStep.WriteAfterCheck)

        val txid = UUID.randomUUID().toString()
        val tmpPath = syncDir.resolve("$txid.tmp")
        val metaPath = quarantineDir.resolve("$txid.meta")
        val metaBytes = SyncFilesHelper.syncJson.encodeToString(
            SyncMeta.serializer(),
            SyncMeta(txid = txid, baseHash = baseHash, newHash = newHash),
        ).toByteArray(StandardCharsets.UTF_8)
        val journalBytes = SyncFilesHelper.syncJson.encodeToString(
            SyncJournal.serializer(),
            SyncJournal(txid = txid),
        ).toByteArray(StandardCharsets.UTF_8)

        inFlightTxId = txid
        inFlightRevision = capturedRev

        // Step 3: Write & fsync tmp, meta, journal; sync Q then S.
        try {
            fileOps.writeFileAndFsync(tmpPath, serializedBytes)
            stepHook(SyncStep.WriteAfterTmp)

            fileOps.writeFileAndFsync(metaPath, metaBytes)
            stepHook(SyncStep.WriteAfterMeta)

            fileOps.writeFileAndFsync(journalFile, journalBytes)
            fileOps.fsyncDirectory(quarantineDir)
            fileOps.fsyncDirectory(syncDir)
            stepHook(SyncStep.WriteAfterJournal)
        } catch (e: IOException) {
            inFlightTxId = null
            inFlightRevision = null
            SyncFilesHelper.cleanupBeforeExchange(journalFile, tmpPath, metaPath, syncDir, quarantineDir, fileOps)
            store.setSyncState(SyncState.WriteFailed(WriteFailure.BeforeExchange("Failed preparing save files: ${e.message}")))
            return
        }

        // Step 4: Atomic exchange of S/<txid>.tmp and target; sync D and S.
        val exchangeResult = try {
            fileOps.exchange(tmpPath, targetFile)
        } catch (e: IOException) {
            inFlightTxId = null
            inFlightRevision = null
            SyncFilesHelper.cleanupBeforeExchange(journalFile, tmpPath, metaPath, syncDir, quarantineDir, fileOps)
            store.setSyncState(SyncState.WriteFailed(WriteFailure.BeforeExchange("Atomic exchange failed: ${e.message}")))
            return
        }
        when (exchangeResult) {
            is AtomicExchangeResult.Success -> {
                try {
                    fileOps.fsyncDirectory(deckDir)
                    fileOps.fsyncDirectory(syncDir)
                } catch (e: IOException) {
                    store.setSyncState(
                        SyncState.WriteFailed(
                            WriteFailure.AfterExchange(
                                displacedAt = tmpPath.toString(),
                                message = "Studio's version is installed on disk, but directory sync failed: ${e.message}",
                            ),
                        ),
                    )
                    return
                }
            }

            is AtomicExchangeResult.Unsupported -> {
                inFlightTxId = null
                inFlightRevision = null
                SyncFilesHelper.cleanupBeforeExchange(journalFile, tmpPath, metaPath, syncDir, quarantineDir, fileOps)
                store.setSyncState(SyncState.ReadOnly(exchangeResult.reason))
                return
            }

            is AtomicExchangeResult.Failed -> {
                inFlightTxId = null
                inFlightRevision = null
                SyncFilesHelper.cleanupBeforeExchange(journalFile, tmpPath, metaPath, syncDir, quarantineDir, fileOps)
                store.setSyncState(SyncState.WriteFailed(WriteFailure.BeforeExchange(exchangeResult.message)))
                return
            }
        }

        stepHook(SyncStep.WriteAfterExchange)

        // Step 5: Hash displaced file at S/<txid>.tmp.
        val displacedHash = try {
            SyncFilesHelper.sha256Hex(Files.readAllBytes(tmpPath))
        } catch (e: IOException) {
            store.setSyncState(
                SyncState.WriteFailed(
                    WriteFailure.AfterExchange(
                        displacedAt = tmpPath.toString(),
                        message = "Studio's version is installed, but hashing displaced file failed: ${e.message}",
                    ),
                ),
            )
            return
        }

        stepHook(SyncStep.WriteAfterHashDisplaced)

        // Step 6: Quarantine displaced file (.base or .theirs); sync S and Q.
        val isExpectedBase = displacedHash == baseHash
        val suffix = if (isExpectedBase) QuarantineKind.Base.suffix else QuarantineKind.Theirs.suffix
        val quarantinedName = "$txid-${clock.wallTimeMillis()}.$suffix"
        val quarantinedPath = quarantineDir.resolve(quarantinedName)

        try {
            fileOps.atomicMove(tmpPath, quarantinedPath)
            fileOps.fsyncDirectory(syncDir)
            fileOps.fsyncDirectory(quarantineDir)
            firstObservedMonoMs[quarantinedName] = clock.monotonicTimeMillis()
        } catch (e: IOException) {
            store.setSyncState(
                SyncState.WriteFailed(
                    WriteFailure.AfterExchange(
                        displacedAt = tmpPath.toString(),
                        message = "Studio's version is installed, but quarantining displaced file failed: ${e.message}",
                    ),
                ),
            )
            return
        }

        stepHook(SyncStep.WriteAfterQuarantine)

        // Step 7: Delete S/journal; sync S; update diskHash and savedRevision.
        try {
            fileOps.deleteIfExists(journalFile)
            fileOps.fsyncDirectory(syncDir)
        } catch (e: IOException) {
            store.setSyncState(
                SyncState.WriteFailed(
                    WriteFailure.AfterExchange(
                        displacedAt = quarantinedPath.toString(),
                        message = "Studio's version is installed and quarantined, but removing journal failed: ${e.message}",
                    ),
                ),
            )
            return
        }

        inFlightTxId = null
        inFlightRevision = null
        diskHash = newHash
        store.markSaved(capturedRev)
        recordStatSnapshot()

        stepHook(SyncStep.WriteAfterDeleteJournal)

        // Step 8: If displaced hash != baseHash -> Overwrote conflict.
        if (!isExpectedBase) {
            store.setSyncState(
                SyncState.Conflict(
                    ConflictInfo(
                        kind = ConflictKind.Overwrote,
                        targetPath = targetFile.toString(),
                        theirsPath = quarantinedPath.toString(),
                        theirsHash = displacedHash,
                        txId = txid,
                        reason = "Save displaced an external version not yet seen by Studio.",
                    ),
                ),
            )
        }
    }

    private suspend fun recoverLocked(targetHash: String, isOnOpen: Boolean) {
        val journalExists = Files.exists(journalFile)
        val journalTxid = if (journalExists) SyncFilesHelper.readJournalOrNull(journalFile)?.txid else null
        var recoveredMeta: SyncMeta? = null

        if (journalTxid != null) {
            val meta = SyncFilesHelper.readMetaOrNull(quarantineDir, journalTxid)
            recoveredMeta = meta
            val tmpPath = syncDir.resolve("$journalTxid.tmp")

            if (Files.exists(tmpPath)) {
                stepHook(SyncStep.RecoveryBeforeRenameTmp)
                val tmpHash = SyncFilesHelper.sha256Hex(Files.readAllBytes(tmpPath))
                val kind = when {
                    meta != null && tmpHash == meta.newHash -> QuarantineKind.Mine
                    meta != null && tmpHash == meta.baseHash -> QuarantineKind.Base
                    else -> QuarantineKind.Theirs
                }
                val qName = "$journalTxid-${clock.wallTimeMillis()}.${kind.suffix}"
                val qPath = quarantineDir.resolve(qName)
                // Always RENAME, never copy+delete, preserving inode for retained FDs (ABA safety).
                fileOps.atomicMove(tmpPath, qPath)
                fileOps.fsyncDirectory(syncDir)
                fileOps.fsyncDirectory(quarantineDir)
                firstObservedMonoMs[qName] = clock.monotonicTimeMillis()
                stepHook(SyncStep.RecoveryAfterRenameTmp)
            }

            fileOps.deleteIfExists(journalFile)
            fileOps.fsyncDirectory(syncDir)
            stepHook(SyncStep.RecoveryAfterDeleteJournal)

            if (!isOnOpen && meta != null && targetHash == meta.newHash &&
                inFlightTxId == journalTxid && inFlightRevision != null
            ) {
                store.markSaved(inFlightRevision!!)
            }
            if (inFlightTxId == journalTxid) {
                inFlightTxId = null
                inFlightRevision = null
            }

            rebuildStatusForTxid(journalTxid, meta, targetHash)
        }

        for (tmpPath in SyncFilesHelper.listStrayTmpFiles(syncDir)) {
            val txid = tmpPath.fileName.toString().removeSuffix(".tmp")
            val qName = "$txid-${clock.wallTimeMillis()}.${QuarantineKind.Unknown.suffix}"
            val qPath = quarantineDir.resolve(qName)
            fileOps.atomicMove(tmpPath, qPath)
            fileOps.fsyncDirectory(syncDir)
            fileOps.fsyncDirectory(quarantineDir)
            firstObservedMonoMs[qName] = clock.monotonicTimeMillis()
            addNotice(
                RecoveryNotice(
                    kind = "unknown",
                    message = "Recovered temp file without journal to quarantine: $qName",
                    quarantinePath = qPath,
                ),
            )
        }

        // Remove torn/unreadable journal after stray .tmp files are safely quarantined.
        if (journalExists && journalTxid == null) {
            fileOps.deleteIfExists(journalFile)
            fileOps.fsyncDirectory(syncDir)
            addNotice(
                RecoveryNotice(
                    kind = "torn_journal",
                    message = "Cleared unreadable sync journal after moving any temporary files to quarantine.",
                ),
            )
        }

        // Rebuild status from Q on open even when journal was already deleted (step 2->3 crash or restart).
        if (isOnOpen && journalTxid == null) {
            val entries = SyncFilesHelper.scanQuarantineEntries(quarantineDir, dismissedNames).filterNot { it.dismissed }
            for ((txid, txEntries) in entries.groupBy { it.txid }) {
                if (txEntries.any { it.kind == QuarantineKind.Theirs || it.kind == QuarantineKind.Mine }) {
                    rebuildStatusForTxid(txid, SyncFilesHelper.readMetaOrNull(quarantineDir, txid), targetHash)
                }
            }
        }

        if (isOnOpen) {
            diskHash = targetHash
            recordStatSnapshot()
            store.reconcileSyncState(clearWriteFailed = true)
        } else {
            val targetMatchesRecoveredNew = recoveredMeta != null && targetHash == recoveredMeta.newHash
            if (targetMatchesRecoveredNew) {
                diskHash = targetHash
                recordStatSnapshot()
                store.reconcileSyncState(clearWriteFailed = true)
            } else {
                // In-session recovery MUST NOT adopt an unseen external target as diskHash or
                // mark a stale in-memory document as Saved without reloading targetFile.
                if (targetHash != diskHash && store.sync !is SyncState.Conflict) {
                    val bytes = runCatching { Files.readAllBytes(targetFile) }.getOrNull()
                    if (bytes != null) {
                        applyDecodedExternalTargetLocked(bytes, SyncFilesHelper.sha256Hex(bytes))
                        recordStatSnapshot()
                    } else if (store.isDirty || store.hasOpenTransaction) {
                        store.setSyncState(
                            SyncState.Conflict(
                                ConflictInfo(
                                    kind = ConflictKind.Unwritten,
                                    targetPath = targetFile.toString(),
                                    theirsPath = targetFile.toString(),
                                    theirsHash = targetHash,
                                    reason = "Target file changed externally while recovering an interrupted save.",
                                ),
                            ),
                        )
                    }
                } else {
                    store.reconcileSyncState(clearWriteFailed = true)
                }
            }
        }
    }

    private fun rebuildStatusForTxid(txid: String, meta: SyncMeta?, targetHash: String) =
        SyncFilesHelper.rebuildStatusForTxid(
            txid = txid,
            meta = meta,
            targetHash = targetHash,
            targetFile = targetFile,
            quarantineDir = quarantineDir,
            dismissedNames = dismissedNames,
            resolvedConflictNames = resolvedConflictNames,
            store = store,
            onNotice = ::addNotice,
        )

    private suspend fun sweepLocked() = SyncFilesHelper.sweepQuarantine(
        targetFile = targetFile,
        syncDir = syncDir,
        quarantineDir = quarantineDir,
        journalFile = journalFile,
        dismissedFile = dismissedFile,
        dismissedNames = dismissedNames,
        resolvedConflictsFile = resolvedConflictsFile,
        resolvedConflictNames = resolvedConflictNames,
        firstObservedMonoMs = firstObservedMonoMs,
        retentionMs = retentionMs,
        clock = clock,
        fileOps = fileOps,
        store = store,
        stepHook = stepHook,
        onNotice = ::addNotice,
    )

    private suspend fun pollExternalChangesInternal() {
        // Never overwrite/clear an active Conflict or WriteFailed banner, or race an active journal.
        if (store.sync is SyncState.Conflict || store.sync is SyncState.WriteFailed || Files.exists(journalFile)) {
            return
        }
        if (store.load is DeckLoadState.ReadOnlyNewerFormat || store.load is DeckLoadState.Loading) {
            return
        }
        if (!Files.exists(targetFile)) return
        val size = try {
            Files.size(targetFile)
        } catch (_: IOException) {
            return
        }
        val mtime = try {
            Files.getLastModifiedTime(targetFile).toMillis()
        } catch (_: IOException) {
            return
        }

        val statChanged = size != lastObservedSize || mtime != lastObservedMtimeMs
        val shouldHash = statChanged || store.sync is SyncState.ExternalInvalid || store.load is DeckLoadState.Invalid
        if (!shouldHash) return

        val bytes = try {
            Files.readAllBytes(targetFile)
        } catch (_: IOException) {
            return
        }
        lastObservedSize = bytes.size.toLong()
        lastObservedMtimeMs = mtime
        val currentHash = SyncFilesHelper.sha256Hex(bytes)

        if (currentHash == diskHash && store.sync !is SyncState.ExternalInvalid && store.load !is DeckLoadState.Invalid) {
            return
        }
        applyDecodedExternalTargetLocked(bytes, currentHash)
    }

    private suspend fun applyDecodedExternalTargetLocked(bytes: ByteArray, currentHash: String) {
        val decoded = DeckDocumentCodec.decode(
            rawText = bytes.toString(StandardCharsets.UTF_8),
            specimens = specimenRegistry,
            effects = effectsRegistry,
            migrations = migrations,
            assetExists = assetExists,
        )

        if (store.load is DeckLoadState.Invalid) {
            when (decoded) {
                is DeckDecodeResult.Success -> initializeWritableOrReadOnlyLocked(decoded, currentHash)
                is DeckDecodeResult.ReadOnlyNewerFormat -> {
                    diskHash = currentHash
                    store.markReadOnlyNewerFormat(decoded.foundVersion, decoded.supportedVersion, decoded.message)
                }

                is DeckDecodeResult.Failure -> store.markLoadInvalid(decoded.errors)
            }
            return
        }

        when (decoded) {
            is DeckDecodeResult.Failure -> {
                if (store.sync !is SyncState.ReadOnly) {
                    store.setSyncState(SyncState.ExternalInvalid(decoded.errors, targetFile.toString()))
                }
            }

            is DeckDecodeResult.ReadOnlyNewerFormat -> {
                store.setSyncState(SyncState.ReadOnly(decoded.message))
            }

            is DeckDecodeResult.Success -> {
                if (currentHash == diskHash) {
                    store.reconcileSyncState(clearWriteFailed = true, clearExternalInvalid = true)
                    return
                }
                stepHook(SyncStep.PollBeforeApplyExternal)
                val previousDoc = store.applyExternalChangeIfClean(
                    newDocument = decoded.document,
                    conflictOnDirty = ConflictInfo(
                        kind = ConflictKind.Unwritten,
                        targetPath = targetFile.toString(),
                        theirsPath = targetFile.toString(),
                        theirsHash = currentHash,
                        reason = "Deck file changed externally while Studio has unsaved edits.",
                    ),
                    label = "External change",
                    issues = decoded.lintIssues,
                )
                if (previousDoc != null) {
                    externalEditLintWarnings = FragmentProvenance.lintExternalDocumentEdit(previousDoc, decoded.document)
                    diskHash = currentHash
                }
            }
        }
    }

    private fun ensureSyncDirectoriesAndLock(): Boolean = dirLock.ensureDirectoriesAndAcquire()

    private fun releaseLock() = dirLock.release()

    private fun addNotice(notice: RecoveryNotice) {
        synchronized(noticesLock) { notices += notice }
    }

    private fun clearNotices() {
        synchronized(noticesLock) { notices.clear() }
    }

    private fun recordStatSnapshot() {
        runCatching {
            lastObservedSize = Files.size(targetFile)
            lastObservedMtimeMs = Files.getLastModifiedTime(targetFile).toMillis()
        }
    }

    companion object {
        const val DEFAULT_DEBOUNCE_MS: Long = 350L
        const val DEFAULT_POLL_INTERVAL_MS: Long = 500L
        const val DEFAULT_SWEEP_INTERVAL_MS: Long = 60_000L
        const val DEFAULT_RETENTION_MS: Long = 10L * 60L * 1_000L

        fun sha256Hex(bytes: ByteArray): String = SyncFilesHelper.sha256Hex(bytes)
    }
}
