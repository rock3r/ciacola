package dev.bnorm.storyboard.easel.export

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.window.MenuBarScope
import dev.bnorm.storyboard.Storyboard
import kotlinx.coroutines.launch

@Composable
internal fun MenuBarScope.ExportMenu(
    coordinator: ExportCoordinator,
    pdfExporter: StoryboardPdfExporter,
    videoExporter: StoryboardVideoExporter,
    storyboard: Storyboard,
) {
    val coroutineScope = rememberCoroutineScope()

    Menu("Export") {
        Item(
            text = "PDF\u2026",
            // Disabled for the whole export, file dialog included; the coordinator also ignores re-entry.
            enabled = !coordinator.isExporting,
            onClick = {
                coroutineScope.launch {
                    pdfExporter.export(storyboard)
                }
            },
        )
        Item(
            text = "Video (MP4)\u2026",
            enabled = !coordinator.isExporting,
            onClick = {
                coroutineScope.launch {
                    videoExporter.export(storyboard)
                }
            },
        )
    }
}
