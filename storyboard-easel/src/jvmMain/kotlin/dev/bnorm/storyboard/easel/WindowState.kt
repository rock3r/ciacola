package dev.bnorm.storyboard.easel

import androidx.compose.runtime.*
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowState
import dev.bnorm.storyboard.easel.internal.WindowStateSerializer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.serialization.StringFormat
import kotlinx.serialization.json.Json
import java.io.IOException
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption.*
import kotlin.io.path.createParentDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/** First-run size of the Story window: 16:9, matching the stage. */
val DefaultStoryWindowSize: DpSize = DpSize(1280.dp, 720.dp)

/** First-run size of windows that have no better default (Compose's own `WindowState()` size). */
val DefaultWindowSize: DpSize = DpSize(800.dp, 600.dp)

@Composable
fun rememberWindowState(
    fileName: String,
    format: StringFormat = Json,
    defaultSize: DpSize = DefaultWindowSize,
    persist: Boolean = true,
): WindowState? {
    val explicitStoryboardDir = LocalStoryboardDir.current
    val path = remember(fileName, explicitStoryboardDir) {
        (explicitStoryboardDir ?: resolveDefaultStoryboardDir()).resolve("${fileName.lowercase()}.json")
    }
    return rememberWindowState(path, format, defaultSize, persist)
}

/**
 * Loads the window state persisted at [path], or a fresh state of [defaultSize] on first run.
 *
 * Changes are written back while [persist] is `true`, or once the file exists (it was loaded or
 * written before). Pass `persist = false` for a window that has never been shown, so merely
 * declaring a window does not create its state file.
 */
@Composable
fun rememberWindowState(
    path: Path,
    format: StringFormat = Json,
    defaultSize: DpSize = DefaultWindowSize,
    persist: Boolean = true,
): WindowState? {
    var state by remember { mutableStateOf<WindowState?>(null) }
    // Whether a state file exists for this window (loaded on start or written since).
    var hasStateFile by remember(path) { mutableStateOf(false) }

    LaunchedEffect(path, format) {
        state = try {
            runInterruptible(Dispatchers.IO) {
                if (path.exists()) {
                    hasStateFile = true
                    val text = path.readText()
                    runCatching { format.decodeFromString(WindowStateSerializer, text) }
                        .getOrElse { WindowState(size = defaultSize) }
                } else {
                    path.createParentDirectories()
                    WindowState(size = defaultSize)
                }
            }
        } catch (e: IOException) {
            System.err.println("Storyboard: unable to initialize window state at $path: ${e.message}")
            WindowState(size = defaultSize)
        } catch (e: SecurityException) {
            System.err.println("Storyboard: permission denied reading window state at $path: ${e.message}")
            WindowState(size = defaultSize)
        }
    }

    // Launch writer in a separate composable so *this* composable does not rerun each time.
    LaunchedStateWriter(state.takeIf { persist || hasStateFile }, path, format) { hasStateFile = true }

    return state
}

@Composable
private fun LaunchedStateWriter(state: WindowState?, path: Path, format: StringFormat, onWritten: () -> Unit) {
    state ?: return
    LaunchedEffect(state.hash(), path, format) {
        try {
            runInterruptible(Dispatchers.IO) {
                path.createParentDirectories()
                val text = format.encodeToString(WindowStateSerializer, state)
                path.writeText(text, options = arrayOf(WRITE, CREATE, TRUNCATE_EXISTING))
            }
            onWritten()
        } catch (e: IOException) {
            System.err.println("Storyboard: unable to persist window state to $path: ${e.message}")
        } catch (e: SecurityException) {
            System.err.println("Storyboard: permission denied persisting window state to $path: ${e.message}")
        }
    }
}

private fun WindowState.hash(): Int {
    var result = placement.hashCode()
    result = 31 * result + isMinimized.hashCode()
    result = 31 * result + position.hashCode()
    result = 31 * result + size.hashCode()
    return result
}
