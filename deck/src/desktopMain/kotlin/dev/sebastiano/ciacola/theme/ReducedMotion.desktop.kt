package dev.sebastiano.ciacola.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Queries macOS Reduce Motion preference safely once off-EDT with a bounded timeout so the UI
 * thread never blocks on subprocess startup or OS IPC stalls.
 */
internal object SystemReducedMotionDetector {
    private val cached = AtomicReference<Boolean?>(null)
    private val mutex = Mutex()

    fun cachedOrNull(): Boolean? = cached.get()

    suspend fun detectOnce(query: () -> Boolean = ::queryOsReduceMotion): Boolean {
        cached.get()?.let { return it }
        return mutex.withLock {
            cached.get()?.let { return@withLock it }
            val detected = withContext(Dispatchers.IO) {
                runCatching(query).getOrDefault(false)
            }
            cached.compareAndSet(null, detected)
            cached.get() ?: detected
        }
    }

    internal fun resetForTesting() {
        cached.set(null)
    }
}

internal fun queryOsReduceMotion(
    osName: String = System.getProperty("os.name").orEmpty(),
    defaultsBinary: String = "/usr/bin/defaults",
    timeoutMillis: Long = 500L,
): Boolean {
    if (!osName.contains("mac", ignoreCase = true) && !osName.contains("darwin", ignoreCase = true)) {
        return false
    }
    return runCatching {
        val process = ProcessBuilder(defaultsBinary, "read", "com.apple.universalaccess", "reduceMotion")
            .redirectErrorStream(true)
            .start()
        val finished = process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)
        if (!finished) {
            process.destroyForcibly()
            return@runCatching false
        }
        if (process.exitValue() != 0) {
            return@runCatching false
        }
        val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
        output == "1" || output.equals("true", ignoreCase = true)
    }.getOrDefault(false)
}

/**
 * Returns the host OS Reduce Motion preference without blocking the EDT on initial composition.
 */
@Composable
fun rememberSystemReducedMotion(): Boolean {
    var reduced by remember { mutableStateOf(SystemReducedMotionDetector.cachedOrNull() ?: false) }
    LaunchedEffect(Unit) {
        reduced = SystemReducedMotionDetector.detectOnce()
    }
    return reduced
}
