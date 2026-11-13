package dev.bnorm.storyboard.easel.export

/** Progress of an active or failed storyboard export, shown by [ExportProgressPopup]. */
sealed class ExportStatus(
    val progress: Float,
    val message: String,
) {
    /** Frames or pages are being rendered, encoded, or written to disk. */
    class Exporting(progress: Float, message: String) : ExportStatus(progress, message)

    /** The export stopped because of an error; stays visible until dismissed or a new export starts. */
    class Failed(message: String) : ExportStatus(1f, message)
}
