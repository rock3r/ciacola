package dev.bnorm.storyboard.easel.export

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope

/**
 * Coordinates mutual exclusion, progress state, and cancellation across storyboard exporters
 * (PDF, MP4 video) so only one export runs at a time.
 */
class ExportCoordinator {
    var status by mutableStateOf<ExportStatus?>(null)
        internal set

    private var running by mutableStateOf(false)
    private var activeJob: Job? = null

    /** `true` from the moment an export is requested (file dialog included) until it ends. */
    val isExporting: Boolean get() = running

    /** Clears an [ExportStatus.Failed] status; a running export's progress is left alone. */
    fun dismissFailure() {
        if (status is ExportStatus.Failed) status = null
    }

    /** Surfaces an immediate failure when no export is currently running. */
    internal fun reportFailure(message: String) {
        if (!running) {
            status = ExportStatus.Failed(message)
        }
    }

    /** Requests cancellation of the currently running export, if any. */
    fun cancel() {
        activeJob?.cancel()
    }

    /**
     * Runs [block] unless an export is already running. Callers live on the UI thread, so the
     * check-and-set cannot race. Cancellation clears [status] and rethrows [CancellationException];
     * I/O and runtime errors are surfaced via [ExportStatus.Failed].
     */
    internal suspend fun runExclusive(
        failureMessage: (Exception) -> String,
        block: suspend (updateStatus: (ExportStatus?) -> Unit) -> Unit,
    ) {
        if (running) return
        running = true
        status = null
        try {
            coroutineScope {
                val job = coroutineContext[Job]
                activeJob = job
                try {
                    block { next -> status = next }
                } finally {
                    if (activeJob === job) activeJob = null
                }
            }
        } catch (e: CancellationException) {
            status = null
            throw e
        } catch (e: IOException) {
            status = ExportStatus.Failed(failureMessage(e))
        } catch (e: RuntimeException) {
            status = ExportStatus.Failed(failureMessage(e))
        } finally {
            running = false
        }
    }
}
