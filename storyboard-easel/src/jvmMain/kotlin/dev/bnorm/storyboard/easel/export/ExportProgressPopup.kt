package dev.bnorm.storyboard.easel.export

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.LinearProgressIndicator
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import kotlinx.coroutines.delay

private const val FAILURE_AUTO_DISMISS_MILLIS = 8_000L

@Composable
fun ExportProgressPopup(status: ExportStatus, onDismiss: () -> Unit = {}) {
    if (status is ExportStatus.Failed) {
        LaunchedEffect(status) {
            delay(FAILURE_AUTO_DISMISS_MILLIS)
            onDismiss()
        }
    }
    Popup(alignment = Alignment.Center, onDismissRequest = onDismiss.takeIf { status is ExportStatus.Failed }) {
        Surface(
            elevation = 8.dp,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.border(2.dp, borderColor(status), RoundedCornerShape(8.dp))
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(status.message)
                Spacer(Modifier.padding(4.dp))
                when (status) {
                    is ExportStatus.Exporting -> LinearProgressIndicator(status.progress)
                    is ExportStatus.Failed -> TextButton(onClick = onDismiss) { Text("Dismiss") }
                }
            }
        }
    }
}

@Composable
private fun borderColor(status: ExportStatus) = when (status) {
    is ExportStatus.Exporting -> MaterialTheme.colors.primary
    is ExportStatus.Failed -> MaterialTheme.colors.error
}
