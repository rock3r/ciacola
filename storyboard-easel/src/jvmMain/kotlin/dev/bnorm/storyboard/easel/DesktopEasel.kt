package dev.bnorm.storyboard.easel

import androidx.compose.runtime.*
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.window.*
import dev.bnorm.storyboard.ContentDecorator
import dev.bnorm.storyboard.Storyboard
import dev.bnorm.storyboard.easel.assist.rememberAssistantWindow
import dev.bnorm.storyboard.easel.export.ExportCoordinator
import dev.bnorm.storyboard.easel.export.ExportMenu
import dev.bnorm.storyboard.easel.export.ExportProgressPopup
import dev.bnorm.storyboard.easel.export.StoryboardPdfExporter
import dev.bnorm.storyboard.easel.export.StoryboardVideoExporter
import dev.bnorm.storyboard.easel.overlay.EaselOverlay
import dev.bnorm.storyboard.easel.overlay.EaselOverlayScope
import dev.bnorm.storyboard.easel.overlay.OverlayNavigation
import dev.bnorm.storyboard.easel.overview.EaselOverview
import dev.bnorm.storyboard.easel.overview.rememberOverviewVisibility
import dev.bnorm.storyboard.plus
import java.awt.Dimension
import kotlin.math.roundToInt

private val StoryWindowMinimumSize = Dimension(640, 420)

@Composable
fun ApplicationScope.DesktopEasel(
    animaticFileName: String? = "animatic",
    storyboard: () -> Storyboard,
) {
    val animatic = when (animaticFileName) {
        null -> rememberAnimatic(storyboard = storyboard)
        else -> rememberAnimatic(animaticFileName, storyboard = storyboard)
    }
    DesktopEasel(animatic)
}

@Composable
fun ApplicationScope.DesktopEasel(
    animatic: Animatic,
    overlay: @Composable EaselOverlayScope.() -> Unit = {
        OverlayNavigation(animatic)
    },
    windows: List<EaselWindow> = listOf(
        rememberAssistantWindow(animatic)
    ),
) {
    // One coordinator/exporter pair for the app's lifetime: the storyboard is rebuilt on every
    // override or theme change, and a new coordinator would drop a running export's status and
    // re-enable the menu.
    val exportCoordinator = remember { ExportCoordinator() }
    val pdfExporter = remember(exportCoordinator) { StoryboardPdfExporter(exportCoordinator) }
    val videoExporter = remember(exportCoordinator) { StoryboardVideoExporter(exportCoordinator) }

    val storyWindowState = rememberWindowState(fileName = "Story", defaultSize = DefaultStoryWindowSize)
    var storyWindow by remember { mutableStateOf<ComposeWindow?>(null) }
    val overviewVisibility = rememberOverviewVisibility()

    @Composable
    fun FrameWindowScope.Toolbar() {
        MenuBar {
            DesktopMenu(animatic.storyboard, storyWindowState, storyWindow, overviewVisibility)
            ExportMenu(exportCoordinator, pdfExporter, videoExporter, animatic.storyboard)
            for (window in windows) {
                key(window.name) {
                    Menu(window.name) {
                        with(window) { Menu() }
                    }
                }
            }
        }
    }

    if (storyWindowState == null) {
        // Need a window to keep the application from closing.
        // TODO splash screen?
        Window(onCloseRequest = ::exitApplication, visible = false) {}
        return
    }

    Window(
        onCloseRequest = ::exitApplication,
        state = storyWindowState,
        title = "${animatic.storyboard.title} - Story",
    ) {
        storyWindow = window
        SideEffect {
            if (window.minimumSize != StoryWindowMinimumSize) {
                window.minimumSize = StoryWindowMinimumSize
            }
        }

        Toolbar()

        val decorator = remember(windows) {
            windows.fold(ContentDecorator.None) { acc, sidecar -> acc + sidecar.decorator }
        }

        CompositionLocalProvider(
            LocalPresentingFullscreen provides (storyWindowState.placement == WindowPlacement.Fullscreen),
            LocalOverviewOpen provides overviewVisibility.isVisible,
        ) {
            decorator.decorate {
                EaselOverview(animatic, visibility = overviewVisibility) {
                    EaselOverlay(
                        overlay = {
                            // Only display overlay navigation when *not* fullscreen.
                            if (storyWindowState.placement != WindowPlacement.Fullscreen) {
                                overlay()
                            }
                        }
                    ) {
                        Easel(animatic)
                    }
                }
            }
        }

        exportCoordinator.status?.let {
            ExportProgressPopup(
                status = it,
                onDismiss = exportCoordinator::dismissFailure,
                onCancel = exportCoordinator::cancel,
            )
        }
    }

    for (easelWindow in windows) {
        key(easelWindow.name) {
            // Never-opened windows do not write `.storyboard/<name>.json`; they persist once shown.
            val state = rememberWindowState(
                fileName = easelWindow.name,
                defaultSize = easelWindow.initialSize,
                persist = easelWindow.visible,
            )
            if (state != null && easelWindow.visible) {
                Window(
                    title = "${animatic.storyboard.title} - ${easelWindow.name}",
                    state = state,
                    onCloseRequest = { easelWindow.visible = false },
                ) {
                    SideEffect {
                        easelWindow.minimumSize?.let { min ->
                            val dim = Dimension(min.width.value.roundToInt(), min.height.value.roundToInt())
                            if (window.minimumSize != dim) {
                                window.minimumSize = dim
                            }
                        }
                    }
                    Toolbar()
                    easelWindow.Content()
                }
            }
        }
    }
}
