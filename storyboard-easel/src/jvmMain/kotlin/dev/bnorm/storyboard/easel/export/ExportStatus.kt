package dev.bnorm.storyboard.easel.export

/** Progress of a PDF export, shown by [ExportProgressPopup]. */
sealed class ExportStatus(
    val progress: Float,
    val message: String,
) {
    /** Pages are being rendered or the document is being written. */
    class Exporting(progress: Float, message: String) : ExportStatus(progress, message)

    /** The export stopped because of an error; stays visible until dismissed or a new export starts. */
    class Failed(message: String) : ExportStatus(1f, message)
}
