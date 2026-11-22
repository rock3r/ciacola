package dev.sebastiano.ciacola.inspector.sync

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.sebastiano.ciacola.document.CloseFlushResult
import dev.sebastiano.ciacola.document.DeckDocumentStore
import dev.sebastiano.ciacola.document.DeckFileSync
import dev.sebastiano.ciacola.document.QuarantineEntry
import dev.sebastiano.ciacola.document.QuarantineKind
import dev.sebastiano.ciacola.document.RecoveryNotice
import dev.sebastiano.ciacola.document.SyncState
import java.nio.file.Files
import java.nio.file.Path

enum class RecoverySource(val label: String) {
    Quarantine("Quarantine"),
    AppDataRecovery("App-Data Recovery"),
}

@Immutable
data class RecoveryCopyEntry(
    val id: String,
    val label: String,
    val path: Path,
    val source: RecoverySource,
    val modifiedMillis: Long,
    val quarantineEntry: QuarantineEntry? = null,
) {
    val canDismiss: Boolean
        get() = quarantineEntry != null && quarantineEntry.kind != QuarantineKind.Base
}

@Immutable
sealed interface CloseGuardState {
    data object Idle : CloseGuardState

    @Immutable
    data class Confirming(
        val flushResult: CloseFlushResult,
        val errorMessage: String? = null,
    ) : CloseGuardState
}

/**
 * Stateful Studio sync/conflict/recovery/close-guard controller binding [DeckDocumentStore] and
 * [DeckFileSync] to the sticky status banner, recovery copy picker, and close confirmation dialog.
 */
@Stable
class DocumentSyncController(
    val store: DeckDocumentStore,
    val fileSync: DeckFileSync,
    private val onOpenRecoveryFile: (Path) -> Unit = {},
) {
    var recoveryPickerOpen: Boolean by mutableStateOf(false)
        private set

    var recoveryEntries: List<RecoveryCopyEntry> by mutableStateOf(emptyList())
        private set

    var closeGuard: CloseGuardState by mutableStateOf(CloseGuardState.Idle)
        private set

    var statusMessage: String? by mutableStateOf(null)
        private set

    var isBusy: Boolean by mutableStateOf(false)
        private set

    val syncState: SyncState
        get() = store.sync

    val recoveryNotices: List<RecoveryNotice>
        get() = fileSync.recoveryNotices

    val shouldShowStickyBanner: Boolean
        get() = when (store.sync) {
            SyncState.Saved, SyncState.Dirty -> false
            is SyncState.Conflict,
            is SyncState.ExternalInvalid,
            is SyncState.WriteFailed,
            is SyncState.ReadOnly,
            -> true
        }

    fun clearStatusMessage() {
        statusMessage = null
    }

    /**
     * Resolves an active conflict with **Keep mine**.
     */
    suspend fun keepMine(): SyncState {
        isBusy = true
        statusMessage = null
        return try {
            val result = fileSync.keepMine()
            refreshRecoveryEntries()
            result
        } catch (t: Throwable) {
            statusMessage = "Keep mine failed: ${t.message ?: t::class.simpleName}"
            store.sync
        } finally {
            isBusy = false
        }
    }

    /**
     * Resolves an active conflict with **Take theirs**.
     *
     * [DeckFileSync.takeTheirs] writes a recovery copy of Studio's current document first and
     * refuses the action (keeping the conflict open) if writing that recovery copy fails.
     */
    suspend fun takeTheirs(): Result<Path> {
        isBusy = true
        statusMessage = null
        return try {
            val result = fileSync.takeTheirs()
            result.onSuccess { recoveryPath ->
                statusMessage = "Loaded external version. Previous Studio copy saved to ${recoveryPath.fileName}."
            }.onFailure { err ->
                statusMessage = "Take theirs refused: ${err.message ?: err::class.simpleName}"
            }
            refreshRecoveryEntries()
            result
        } finally {
            isBusy = false
        }
    }

    /**
     * Resolves [SyncState.ExternalInvalid] by saving the invalid on-disk bytes to recovery first
     * and writing Studio's in-memory valid document.
     */
    suspend fun overwriteExternalInvalid(): Result<Path> {
        isBusy = true
        statusMessage = null
        return try {
            val result = fileSync.overwriteExternalInvalid()
            result.onSuccess { recoveryPath ->
                statusMessage = "Overwrote invalid disk file. Invalid bytes saved to ${recoveryPath.fileName}."
            }.onFailure { err ->
                statusMessage = "Overwrite refused: ${err.message ?: err::class.simpleName}"
            }
            refreshRecoveryEntries()
            result
        } finally {
            isBusy = false
        }
    }

    /**
     * Retries an unresolved write transaction or failed flush.
     */
    suspend fun retryWrite(): SyncState {
        isBusy = true
        statusMessage = null
        return try {
            fileSync.retryUnresolvedTransaction()
            val after = if (store.sync == SyncState.Dirty && store.isDirty) {
                fileSync.flushNow()
            } else {
                store.sync
            }
            refreshRecoveryEntries()
            after
        } catch (t: Throwable) {
            statusMessage = "Retry failed: ${t.message ?: t::class.simpleName}"
            store.sync
        } finally {
            isBusy = false
        }
    }

    /**
     * Polls disk immediately (useful after the user fixes an externally invalid file).
     */
    suspend fun pollNow(): SyncState {
        isBusy = true
        return try {
            fileSync.pollExternalChangesNow()
        } finally {
            isBusy = false
        }
    }

    /**
     * Lists recoverable copies from BOTH `quarantine/` beside the deck AND the app-data recovery
     * directory (`…/ciacola/recovery/<deckId>/`), newest first.
     */
    suspend fun refreshRecoveryEntries(includeDismissedQuarantine: Boolean = false): List<RecoveryCopyEntry> {
        val quarantined = fileSync.listQuarantineEntries(includeDismissed = includeDismissedQuarantine)
            .map { q ->
                val modified = runCatching { Files.getLastModifiedTime(q.path).toMillis() }
                    .getOrDefault(q.quarantinedAtMillis)
                RecoveryCopyEntry(
                    id = "quarantine:${q.fileName}",
                    label = "${q.kind.name} (${q.fileName})",
                    path = q.path,
                    source = RecoverySource.Quarantine,
                    modifiedMillis = modified,
                    quarantineEntry = q,
                )
            }
        val appData = fileSync.listRecoveryCopies().map { path ->
            val modified = runCatching { Files.getLastModifiedTime(path).toMillis() }.getOrDefault(0L)
            RecoveryCopyEntry(
                id = "recovery:${path.fileName}",
                label = path.fileName.toString(),
                path = path,
                source = RecoverySource.AppDataRecovery,
                modifiedMillis = modified,
                quarantineEntry = null,
            )
        }
        val combined = (quarantined + appData).sortedByDescending { it.modifiedMillis }
        recoveryEntries = combined
        return combined
    }

    suspend fun openRecoveryPicker() {
        refreshRecoveryEntries()
        recoveryPickerOpen = true
    }

    fun closeRecoveryPicker() {
        recoveryPickerOpen = false
    }

    fun openRecoveryEntry(entry: RecoveryCopyEntry) {
        onOpenRecoveryFile(entry.path)
    }

    suspend fun dismissQuarantineEntry(entry: RecoveryCopyEntry): Boolean {
        val q = entry.quarantineEntry ?: return false
        val ok = fileSync.dismissQuarantineEntry(q.fileName)
        refreshRecoveryEntries()
        return ok
    }

    /**
     * Synchronous close/switch flush.
     *
     * Invokes [onAllowClose] ONLY when the flush ends in [CloseFlushResult.Clean]. If an
     * unresolved conflict, write failure, or external invalid file remains, holds close and opens
     * [CloseGuardState.Confirming] (*Save a recovery copy and close* / *Cancel*).
     */
    suspend fun requestClose(onAllowClose: () -> Unit): CloseFlushResult {
        isBusy = true
        return try {
            val result = fileSync.flushOnClose()
            when (result) {
                CloseFlushResult.Clean -> {
                    closeGuard = CloseGuardState.Idle
                    onAllowClose()
                }

                is CloseFlushResult.HeldConflict,
                is CloseFlushResult.HeldWriteFailed,
                is CloseFlushResult.HeldExternalInvalid,
                -> {
                    closeGuard = CloseGuardState.Confirming(flushResult = result)
                }
            }
            result
        } finally {
            isBusy = false
        }
    }

    /**
     * Action **Save a recovery copy and close** from the close-hold dialog.
     * Refuses to close if writing the recovery copy fails.
     */
    suspend fun saveRecoveryCopyAndClose(onAllowClose: () -> Unit): Result<Path> {
        val current = closeGuard as? CloseGuardState.Confirming
            ?: return Result.failure(IllegalStateException("Close guard is not active."))
        isBusy = true
        return try {
            val saved = fileSync.saveRecoveryCopy(label = "close-recovery")
            saved.onSuccess {
                refreshRecoveryEntries()
                closeGuard = CloseGuardState.Idle
                onAllowClose()
            }.onFailure { err ->
                closeGuard = current.copy(
                    errorMessage = "Could not save recovery copy: ${err.message ?: err::class.simpleName}",
                )
            }
            saved
        } finally {
            isBusy = false
        }
    }

    /** Action **Cancel** from the close-hold dialog. Keeps window open. */
    fun cancelCloseGuard() {
        closeGuard = CloseGuardState.Idle
    }
}
