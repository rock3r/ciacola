package dev.sebastiano.ciacola.qa

import androidx.compose.ui.awt.ComposeWindow
import java.awt.Component
import java.awt.Container
import java.awt.EventQueue

internal fun detachSwingMouseListeners(root: Component) {
    root.mouseListeners.forEach { root.removeMouseListener(it) }
    root.mouseMotionListeners.forEach { root.removeMouseMotionListener(it) }
    root.mouseWheelListeners.forEach { root.removeMouseWheelListener(it) }
    if (root is Container) {
        root.components.forEach { child -> detachSwingMouseListeners(child) }
    }
}

internal fun detachWindowSwingListenersOnEdt(win: ComposeWindow?) {
    if (win == null) return
    runCatching {
        if (EventQueue.isDispatchThread()) {
            detachSwingMouseListeners(win)
        } else {
            EventQueue.invokeAndWait { detachSwingMouseListeners(win) }
        }
    }
}

internal fun closeQaWindowsSafely(
    windows: List<ComposeWindow>,
    exitApp: (() -> Unit)?,
    appThread: Thread,
) {
    val prepareTeardown = {
        windows.forEach { win ->
            runCatching { detachSwingMouseListeners(win) }
            runCatching { win.isVisible = false }
        }
        runCatching { exitApp?.invoke() }
    }
    if (EventQueue.isDispatchThread()) {
        prepareTeardown()
    } else {
        runCatching { EventQueue.invokeAndWait { prepareTeardown() } }
    }
    appThread.join(1_500)
    if (appThread.isAlive) {
        val disposeRemaining = {
            windows.forEach { win ->
                if (win.isDisplayable) {
                    runCatching { win.dispose() }
                }
            }
        }
        if (EventQueue.isDispatchThread()) {
            disposeRemaining()
        } else {
            runCatching { EventQueue.invokeAndWait { disposeRemaining() } }
        }
        appThread.join(1_500)
    }
}
