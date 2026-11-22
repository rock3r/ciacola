package dev.sebastiano.ciacola.inspector.sync

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sebastiano.ciacola.document.DocumentIssue
import kotlinx.coroutines.delay
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icons.AllIconsKeys

object InvalidJsonBannerTestTags {
    const val BANNER = "sync-invalid-json-banner"
    const val LOCATION = "sync-invalid-json-location"
    const val MESSAGE = "sync-invalid-json-message"
    const val BTN_COPY_FOR_AGENT = "sync-copy-error-for-agent"
    const val COPIED = "sync-copy-error-copied"
    const val BTN_RETRY = "sync-retry-reload"
}

/** `Line 12, column 5`, or the JSON address when no line is known. */
fun invalidJsonLocation(issue: DocumentIssue?): String = when {
    issue == null -> "Unknown location"
    issue.line != null -> "Line ${issue.line}, column ${issue.column ?: 1}"
    issue.address.isNotBlank() -> issue.address
    else -> "Unknown location"
}

/**
 * Formats invalid-JSON errors as a self-contained fix request an author can paste to an agent:
 * the file path, every error with its exact location, and the reload contract.
 */
fun formatInvalidJsonAgentReport(filePath: String, errors: List<DocumentIssue>): String = buildString {
    appendLine("The Ciacola deck file `$filePath` is not valid, so the Studio is still showing the last valid version and has paused saving.")
    appendLine()
    appendLine("Fix ${if (errors.size == 1) "this error" else "these ${errors.size} errors"} in that file and save it; the Studio reloads it automatically:")
    if (errors.isEmpty()) {
        appendLine("- The file could not be parsed.")
    }
    for (err in errors) {
        val address = err.address.takeIf { it.isNotBlank() && err.line != null }?.let { " (at $it)" }.orEmpty()
        appendLine("- ${invalidJsonLocation(err)} — ${err.code}: ${err.message}$address")
    }
    appendLine()
    append("Keep the `.deck.json` v1 structure intact and change only what is needed to fix the errors.")
}

/**
 * Actionable banner shown when an agent (or any editor) writes invalid JSON to the deck file:
 * the exact error location and message, `Copy error for agent`, `Retry`, and the existing
 * overwrite / recovery actions.
 */
@Composable
fun DocumentInvalidJsonBanner(
    filePath: String,
    errors: List<DocumentIssue>,
    isBusy: Boolean,
    onRetry: () -> Unit,
    onOverwrite: () -> Unit,
    onOpenRecovery: () -> Unit,
    onCopyText: (String) -> Boolean,
    modifier: Modifier = Modifier,
    statusMessage: String? = null,
) {
    val colors = JewelTheme.globalColors
    val first = errors.firstOrNull()
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(2_000)
            copied = false
        }
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .background(colors.panelBackground)
            .border(1.dp, colors.borders.normal)
            .testTag(InvalidJsonBannerTestTags.BANNER),
    ) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(colors.outlines.error))
        Column(
            modifier = Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(AllIconsKeys.General.Error, contentDescription = "Error", modifier = Modifier.size(14.dp))
                Text(
                    text = "The deck file has invalid JSON. Showing the last valid version; saving is paused.",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.text.normal,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = invalidJsonLocation(first),
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    color = colors.text.normal,
                    modifier = Modifier.testTag(InvalidJsonBannerTestTags.LOCATION),
                )
                Text(
                    text = first?.message ?: "The file could not be parsed.",
                    fontSize = 12.sp,
                    color = colors.text.normal,
                    modifier = Modifier.weight(1f).testTag(InvalidJsonBannerTestTags.MESSAGE),
                )
            }
            val extra = errors.size - 1
            Text(
                text = if (extra > 0) "$filePath · $extra more ${if (extra == 1) "error" else "errors"}" else filePath,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = colors.text.info,
                maxLines = 1,
            )
            statusMessage?.let { Text(it, fontSize = 11.sp, color = colors.text.info) }
        }
        Row(
            modifier = Modifier.align(Alignment.CenterVertically).padding(end = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (copied) {
                Text("Copied", fontSize = 12.sp, color = colors.text.info, modifier = Modifier.testTag(InvalidJsonBannerTestTags.COPIED))
            }
            OutlinedButton(
                onClick = {
                    // Only confirm when the clipboard write actually succeeded.
                    copied = onCopyText(formatInvalidJsonAgentReport(filePath, errors))
                },
                modifier = Modifier.testTag(InvalidJsonBannerTestTags.BTN_COPY_FOR_AGENT),
            ) { Text("Copy error for agent", fontSize = 12.sp) }
            OutlinedButton(
                onClick = onRetry,
                enabled = !isBusy,
                modifier = Modifier.testTag(InvalidJsonBannerTestTags.BTN_RETRY),
            ) { Text("Retry", fontSize = 12.sp) }
            OutlinedButton(
                onClick = onOverwrite,
                enabled = !isBusy,
                modifier = Modifier.testTag(SyncUiTestTags.BTN_OVERWRITE_INVALID),
            ) { Text("Overwrite with Studio\u2019s version", fontSize = 12.sp) }
            OutlinedButton(
                onClick = onOpenRecovery,
                enabled = !isBusy,
                modifier = Modifier.testTag(SyncUiTestTags.BTN_OPEN_RECOVERY),
            ) { Text("Recovery…", fontSize = 12.sp) }
        }
    }
}
