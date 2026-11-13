package dev.bnorm.storyboard.easel.export

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.LinearProgressIndicator
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

private const val FAILURE_AUTO_DISMISS_MILLIS = 8_000L

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ExportProgressPopup(
    status: ExportStatus,
    onDismiss: () -> Unit = {},
    onCancel: () -> Unit = {},
) {
    if (status is ExportStatus.Failed) {
        LaunchedEffect(status) {
            delay(FAILURE_AUTO_DISMISS_MILLIS)
            onDismiss()
        }
    }

    val onEscapeOrClose: () -> Unit = when (status) {
        is ExportStatus.Exporting -> onCancel
        is ExportStatus.Failed -> onDismiss
    }

    Popup(
        alignment = Alignment.Center,
        onDismissRequest = onEscapeOrClose,
        properties = PopupProperties(
            focusable = true,
            dismissOnBackPress = true,
            dismissOnClickOutside = status is ExportStatus.Failed,
        ),
    ) {
        val focusRequester = remember { FocusRequester() }
        LaunchedEffect(status::class) {
            runCatching { focusRequester.requestFocus() }
        }

        val surfaceColor = MaterialTheme.colors.surface
        val onSurfaceColor = MaterialTheme.colors.onSurface
        val accentColor = borderColor(status)
        val clampedProgress = status.progress.coerceIn(0f, 1f)
        val percent = (clampedProgress * 100f).roundToInt()

        Surface(
            color = surfaceColor,
            contentColor = onSurfaceColor,
            elevation = 8.dp,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier
                .widthIn(min = 320.dp, max = 440.dp)
                .border(1.5.dp, accentColor, RoundedCornerShape(8.dp))
                .focusRequester(focusRequester)
                .focusable()
                .onPreviewKeyEvent { event ->
                    if (event.key == Key.Escape && event.type == KeyEventType.KeyUp) {
                        onEscapeOrClose()
                        true
                    } else {
                        false
                    }
                }
                .testTag("export-progress-popup")
                .semantics {
                    liveRegion = LiveRegionMode.Polite
                    paneTitle = if (status is ExportStatus.Failed) "Export error" else "Export progress"
                },
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = status.message,
                        color = onSurfaceColor,
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("export-progress-message"),
                    )
                    if (status is ExportStatus.Exporting) {
                        Text(
                            text = "$percent%",
                            color = onSurfaceColor.copy(alpha = 0.78f),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }

                when (status) {
                    is ExportStatus.Exporting -> {
                        LinearProgressIndicator(
                            progress = clampedProgress,
                            color = MaterialTheme.colors.primary,
                            backgroundColor = onSurfaceColor.copy(alpha = 0.16f),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("export-progress-bar")
                                .semantics {
                                    progressBarRangeInfo = ProgressBarRangeInfo(clampedProgress, 0f..1f)
                                    stateDescription = "$percent percent"
                                },
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            TooltipArea(
                                tooltip = { ExportTooltip("Cancel export (Esc)") },
                            ) {
                                TextButton(
                                    onClick = onCancel,
                                    colors = ButtonDefaults.textButtonColors(
                                        contentColor = MaterialTheme.colors.primary,
                                    ),
                                    modifier = Modifier
                                        .testTag("export-cancel-button")
                                        .semantics { contentDescription = "Cancel export" },
                                ) {
                                    Text("Cancel", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                }
                            }
                        }
                    }

                    is ExportStatus.Failed -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            TooltipArea(
                                tooltip = { ExportTooltip("Dismiss error (Esc)") },
                            ) {
                                TextButton(
                                    onClick = onDismiss,
                                    colors = ButtonDefaults.textButtonColors(
                                        contentColor = MaterialTheme.colors.error,
                                    ),
                                    modifier = Modifier
                                        .testTag("export-dismiss-button")
                                        .semantics { contentDescription = "Dismiss export error" },
                                ) {
                                    Text("Dismiss", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ExportTooltip(text: String) {
    Surface(
        color = MaterialTheme.colors.surface,
        contentColor = MaterialTheme.colors.onSurface,
        elevation = 4.dp,
        shape = RoundedCornerShape(6.dp),
        modifier = Modifier.border(
            width = 1.dp,
            color = MaterialTheme.colors.onSurface.copy(alpha = 0.24f),
            shape = RoundedCornerShape(6.dp),
        ),
    ) {
        Text(
            text = text,
            color = MaterialTheme.colors.onSurface,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun borderColor(status: ExportStatus) = when (status) {
    is ExportStatus.Exporting -> MaterialTheme.colors.primary
    is ExportStatus.Failed -> MaterialTheme.colors.error
}
