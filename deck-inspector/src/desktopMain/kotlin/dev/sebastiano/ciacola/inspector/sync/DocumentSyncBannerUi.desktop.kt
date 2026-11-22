package dev.sebastiano.ciacola.inspector.sync

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sebastiano.ciacola.document.CloseFlushResult
import dev.sebastiano.ciacola.document.ConflictKind
import dev.sebastiano.ciacola.document.DeckDocumentStore
import dev.sebastiano.ciacola.document.DeckLoadState
import dev.sebastiano.ciacola.document.DocumentIssue
import dev.sebastiano.ciacola.document.SyncState
import dev.sebastiano.ciacola.document.WriteFailure
import dev.sebastiano.ciacola.inspector.element.copyTextToSystemClipboard
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text

private val RecoveryTimeFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())

object SyncUiTestTags {
    const val STICKY_BANNER = "sync-sticky-banner"
    const val BTN_KEEP_MINE = "sync-keep-mine"
    const val BTN_TAKE_THEIRS = "sync-take-theirs"
    const val BTN_OVERWRITE_INVALID = "sync-overwrite-invalid"
    const val BTN_RETRY_WRITE = "sync-retry-write"
    const val BTN_OPEN_RECOVERY = "sync-open-recovery"
    const val RECOVERY_PICKER = "sync-recovery-picker"
    const val CLOSE_GUARD_DIALOG = "sync-close-guard-dialog"
    const val BTN_SAVE_RECOVERY_AND_CLOSE = "sync-save-recovery-and-close"
    const val BTN_CANCEL_CLOSE = "sync-cancel-close"
    const val LOAD_INVALID_SCREEN = "sync-load-invalid-screen"
    const val LOAD_NEWER_FORMAT_SCREEN = "sync-load-newer-format-screen"
}

/**
 * Sticky, non-dismissing status bar shown whenever [SyncState] is `Conflict`, `ExternalInvalid`,
 * `WriteFailed`, or `ReadOnly`.
 */
@Composable
fun DocumentSyncStatusBanner(
    controller: DocumentSyncController,
    modifier: Modifier = Modifier,
    onCopyText: (String) -> Boolean = ::copyTextToSystemClipboard,
) {
    val sync = controller.syncState
    val scope = rememberCoroutineScope()
    val statusMsg = controller.statusMessage

    if (!controller.shouldShowStickyBanner) {
        if (statusMsg != null) {
            Row(
                modifier = modifier
                    .fillMaxWidth()
                    .background(Color(0xFF17251E))
                    .border(1.dp, Color(0xFF2D6A4F))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = statusMsg,
                    color = Color(0xFFD8F3DC),
                    fontSize = 11.sp,
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(onClick = { controller.clearStatusMessage() }) {
                    Text("Dismiss", fontSize = 11.sp)
                }
            }
        }
        return
    }

    if (sync is SyncState.ExternalInvalid) {
        DocumentInvalidJsonBanner(
            filePath = controller.fileSync.targetFile.toString(),
            errors = sync.errors,
            isBusy = controller.isBusy,
            onRetry = { scope.launch { controller.pollNow() } },
            onOverwrite = { scope.launch { controller.overwriteExternalInvalid() } },
            onOpenRecovery = { scope.launch { controller.openRecoveryPicker() } },
            onCopyText = onCopyText,
            modifier = modifier.testTag(SyncUiTestTags.STICKY_BANNER),
            statusMessage = statusMsg,
        )
        return
    }

    val (bgColor, borderColor, title, detail) = when (sync) {
        is SyncState.Conflict -> when (sync.info.kind) {
            ConflictKind.Unwritten -> BannerStyle(
                bg = Color(0xFF33230C),
                border = Color(0xFFD97706),
                title = "External change conflict (unsaved Studio edits)",
                detail = "${sync.info.reason} Target on disk currently holds the external version.",
            )

            ConflictKind.Overwrote -> BannerStyle(
                bg = Color(0xFF3A1F0B),
                border = Color(0xFFEA580C),
                title = "External change conflict (Studio version saved on disk)",
                detail = "${sync.info.reason} Displaced external file quarantined at ${sync.info.theirsPath}.",
            )
        }

        is SyncState.ExternalInvalid -> {
            val first = sync.errors.firstOrNull()
            val loc = if (first?.line != null) "line ${first.line}:${first.column ?: 1} — " else ""
            val summary = first?.let { "$loc${it.code}: ${it.message}" } ?: "File could not be parsed."
            BannerStyle(
                bg = Color(0xFF3B1219),
                border = Color(0xFFDC2626),
                title = "External file on disk is invalid (saving suspended)",
                detail = "Showing last valid document (${sync.errors.size} error(s)): $summary",
            )
        }

        is SyncState.WriteFailed -> when (val f = sync.failure) {
            is WriteFailure.BeforeExchange -> BannerStyle(
                bg = Color(0xFF3B1219),
                border = Color(0xFFDC2626),
                title = "Save failed before replacing deck file",
                detail = f.message,
            )

            is WriteFailure.AfterExchange -> BannerStyle(
                bg = Color(0xFF3A1F0B),
                border = Color(0xFFEA580C),
                title = "Studio's version is on disk, but cleanup failed",
                detail = "${f.message} Displaced copy at ${f.displacedAt}.",
            )
        }

        is SyncState.ReadOnly -> BannerStyle(
            bg = Color(0xFF1A2233),
            border = Color(0xFF3B82F6),
            title = "Deck opened read-only",
            detail = sync.reason,
        )

        SyncState.Saved, SyncState.Dirty -> return
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag(SyncUiTestTags.STICKY_BANNER)
            .background(bgColor)
            .border(1.dp, borderColor)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = title,
                    color = Color(0xFFFDF2E9),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = detail,
                    color = Color(0xFFE5D4C0),
                    fontSize = 11.sp,
                )
                if (statusMsg != null) {
                    Text(
                        text = statusMsg,
                        color = Color(0xFFFDE047),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                when (sync) {
                    is SyncState.Conflict -> {
                        DefaultButton(
                            onClick = { scope.launch { controller.keepMine() } },
                            enabled = !controller.isBusy,
                            modifier = Modifier.testTag(SyncUiTestTags.BTN_KEEP_MINE),
                        ) {
                            Text("Keep mine", fontSize = 11.sp)
                        }
                        OutlinedButton(
                            onClick = { scope.launch { controller.takeTheirs() } },
                            enabled = !controller.isBusy,
                            modifier = Modifier.testTag(SyncUiTestTags.BTN_TAKE_THEIRS),
                        ) {
                            Text("Take theirs", fontSize = 11.sp)
                        }
                    }

                    is SyncState.ExternalInvalid -> {
                        DefaultButton(
                            onClick = { scope.launch { controller.overwriteExternalInvalid() } },
                            enabled = !controller.isBusy,
                            modifier = Modifier.testTag(SyncUiTestTags.BTN_OVERWRITE_INVALID),
                        ) {
                            Text("Overwrite with Studio's version", fontSize = 11.sp)
                        }
                    }

                    is SyncState.WriteFailed -> {
                        DefaultButton(
                            onClick = { scope.launch { controller.retryWrite() } },
                            enabled = !controller.isBusy,
                            modifier = Modifier.testTag(SyncUiTestTags.BTN_RETRY_WRITE),
                        ) {
                            Text("Retry", fontSize = 11.sp)
                        }
                    }

                    is SyncState.ReadOnly -> Unit
                    SyncState.Saved, SyncState.Dirty -> Unit
                }

                OutlinedButton(
                    onClick = { scope.launch { controller.openRecoveryPicker() } },
                    enabled = !controller.isBusy,
                    modifier = Modifier.testTag(SyncUiTestTags.BTN_OPEN_RECOVERY),
                ) {
                    Text("Open recovery copy…", fontSize = 11.sp)
                }
            }
        }
    }
}

/**
 * Recovery copy picker listing BOTH `quarantine/` beside the deck AND app-data recovery copies
 * (`…/ciacola/recovery/<deckId>/`).
 */
@Composable
fun DocumentRecoveryPickerPanel(
    controller: DocumentSyncController,
    modifier: Modifier = Modifier,
) {
    if (!controller.recoveryPickerOpen) return
    val scope = rememberCoroutineScope()
    val entries = controller.recoveryEntries

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xAA000000))
            .testTag(SyncUiTestTags.RECOVERY_PICKER),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(min = 460.dp, max = 720.dp)
                .background(Color(0xFF171A21), RoundedCornerShape(10.dp))
                .border(1.dp, Color(0xFF3B4354), RoundedCornerShape(10.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text(
                        text = "Open recovery copy",
                        color = Color(0xFFF3F4F6),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "Includes displaced files in quarantine/ and Studio app-data recovery copies.",
                        color = Color(0xFF9CA3AF),
                        fontSize = 11.sp,
                    )
                }
                OutlinedButton(onClick = { controller.closeRecoveryPicker() }) {
                    Text("Close", fontSize = 11.sp)
                }
            }

            if (entries.isEmpty()) {
                Text(
                    text = "No recovery or quarantine copies found for this deck.",
                    color = Color(0xFF9CA3AF),
                    fontSize = 12.sp,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    for (entry in entries) {
                        val badgeColor = when (entry.source) {
                            RecoverySource.Quarantine -> Color(0xFF92400E)
                            RecoverySource.AppDataRecovery -> Color(0xFF1E3A5F)
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFF1F2430), RoundedCornerShape(6.dp))
                                .border(1.dp, Color(0xFF2D3545), RoundedCornerShape(6.dp))
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Text(
                                        text = entry.source.label,
                                        color = Color(0xFFF3F4F6),
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier
                                            .background(badgeColor, RoundedCornerShape(4.dp))
                                            .padding(horizontal = 6.dp, vertical = 2.dp),
                                    )
                                    Text(
                                        text = entry.label,
                                        color = Color(0xFFE5E7EB),
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace,
                                    )
                                }
                                Text(
                                    text = "${formatMillis(entry.modifiedMillis)}  •  ${entry.path}",
                                    color = Color(0xFF9CA3AF),
                                    fontSize = 10.sp,
                                )
                            }
                            DefaultButton(
                                onClick = {
                                    controller.openRecoveryEntry(entry)
                                    controller.closeRecoveryPicker()
                                },
                            ) {
                                Text("Open", fontSize = 11.sp)
                            }
                            if (entry.canDismiss) {
                                OutlinedButton(
                                    onClick = { scope.launch { controller.dismissQuarantineEntry(entry) } },
                                ) {
                                    Text("Dismiss", fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Close-hold confirmation dialog shown when closing or switching decks while an unresolved
 * conflict, write failure, or external invalid file remains.
 */
@Composable
fun DocumentCloseGuardDialog(
    controller: DocumentSyncController,
    onAllowClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val guard = controller.closeGuard as? CloseGuardState.Confirming ?: return
    val scope = rememberCoroutineScope()

    val reasonText = when (val r = guard.flushResult) {
        is CloseFlushResult.HeldConflict ->
            "An unresolved ${r.conflict.kind.name} conflict remains (${r.conflict.reason})."

        is CloseFlushResult.HeldWriteFailed ->
            "Saving to disk failed: ${r.failure.message}"

        is CloseFlushResult.HeldExternalInvalid ->
            "The file on disk is currently invalid (${r.errors.size} error(s)), so your latest edits were not saved to it."

        CloseFlushResult.Clean -> return
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xB8000000))
            .testTag(SyncUiTestTags.CLOSE_GUARD_DIALOG),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(min = 420.dp, max = 560.dp)
                .background(Color(0xFF1A1D24), RoundedCornerShape(10.dp))
                .border(1.dp, Color(0xFFDC2626), RoundedCornerShape(10.dp))
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "Unsaved changes cannot be flushed cleanly",
                color = Color(0xFFFEE2E2),
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = reasonText,
                color = Color(0xFFE5E7EB),
                fontSize = 12.sp,
            )
            if (guard.errorMessage != null) {
                Text(
                    text = guard.errorMessage,
                    color = Color(0xFFFCA5A5),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = { controller.cancelCloseGuard() },
                    enabled = !controller.isBusy,
                    modifier = Modifier.testTag(SyncUiTestTags.BTN_CANCEL_CLOSE),
                ) {
                    Text("Cancel", fontSize = 11.sp)
                }
                Spacer(Modifier.padding(horizontal = 4.dp))
                DefaultButton(
                    onClick = {
                        scope.launch { controller.saveRecoveryCopyAndClose(onAllowClose) }
                    },
                    enabled = !controller.isBusy,
                    modifier = Modifier.testTag(SyncUiTestTags.BTN_SAVE_RECOVERY_AND_CLOSE),
                ) {
                    Text("Save a recovery copy and close", fontSize = 11.sp)
                }
            }
        }
    }
}

/**
 * Full-window load-state gate covering `Loading`, `Invalid(errors)` (no stage),
 * `ReadOnlyNewerFormat`, and `Loaded` with sticky sync banner + recovery picker + close guard.
 */
@Composable
fun DocumentLoadStateGate(
    store: DeckDocumentStore,
    controller: DocumentSyncController,
    onAllowClose: () -> Unit,
    onRetryLoad: () -> Unit = {},
    modifier: Modifier = Modifier,
    loadedContent: @Composable () -> Unit,
) {
    val scope = rememberCoroutineScope()

    Box(modifier = modifier.fillMaxSize()) {
        when (val load = store.load) {
            DeckLoadState.Loading -> {
                Box(
                    modifier = Modifier.fillMaxSize().background(Color(0xFF111318)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "Loading deck ${controller.fileSync.targetFile.fileName}…",
                        color = Color(0xFF9CA3AF),
                        fontSize = 13.sp,
                    )
                }
            }

            is DeckLoadState.Invalid -> {
                DocumentInvalidLoadScreen(
                    targetFilePath = controller.fileSync.targetFile.toString(),
                    errors = load.errors,
                    onRetry = {
                        scope.launch { controller.pollNow() }
                        onRetryLoad()
                    },
                    onOpenRecovery = { scope.launch { controller.openRecoveryPicker() } },
                )
            }

            is DeckLoadState.ReadOnlyNewerFormat -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag(SyncUiTestTags.LOAD_NEWER_FORMAT_SCREEN)
                        .background(Color(0xFF111318))
                        .padding(32.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = "Newer deck format (v${load.foundVersion} > supported v${load.supportedVersion})",
                        color = Color(0xFF93C5FD),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = load.message,
                        color = Color(0xFFE5E7EB),
                        fontSize = 12.sp,
                    )
                    OutlinedButton(
                        onClick = { scope.launch { controller.openRecoveryPicker() } },
                    ) {
                        Text("Open recovery copy…", fontSize = 12.sp)
                    }
                }
            }

            DeckLoadState.Loaded -> {
                Column(modifier = Modifier.fillMaxSize()) {
                    DocumentSyncStatusBanner(controller = controller)
                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        loadedContent()
                    }
                }
            }
        }

        DocumentRecoveryPickerPanel(controller = controller)
        DocumentCloseGuardDialog(controller = controller, onAllowClose = onAllowClose)
    }
}

@Composable
private fun DocumentInvalidLoadScreen(
    targetFilePath: String,
    errors: List<DocumentIssue>,
    onRetry: () -> Unit,
    onOpenRecovery: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .testTag(SyncUiTestTags.LOAD_INVALID_SCREEN)
            .background(Color(0xFF111318))
            .padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "Deck file could not be loaded",
            color = Color(0xFFFCA5A5),
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = targetFilePath,
            color = Color(0xFF9CA3AF),
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
        )
        Text(
            text = "Fix the errors below and save the file — Studio watches the file and reloads automatically.",
            color = Color(0xFFD1D5DB),
            fontSize = 12.sp,
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color(0xFF181B22), RoundedCornerShape(8.dp))
                .border(1.dp, Color(0xFF7F1D1D), RoundedCornerShape(8.dp))
                .padding(12.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (err in errors) {
                val loc = if (err.line != null) "Line ${err.line}, Col ${err.column ?: 1}" else err.address
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "[$loc] ${err.code}",
                        color = Color(0xFFFCA5A5),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Monospace,
                    )
                    Text(
                        text = err.message,
                        color = Color(0xFFE5E7EB),
                        fontSize = 11.sp,
                    )
                }
            }
        }
        Spacer(Modifier.height(2.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DefaultButton(onClick = onRetry) {
                Text("Re-read file", fontSize = 12.sp)
            }
            OutlinedButton(onClick = onOpenRecovery) {
                Text("Open recovery copy…", fontSize = 12.sp)
            }
        }
    }
}

private data class BannerStyle(
    val bg: Color,
    val border: Color,
    val title: String,
    val detail: String,
)

private fun formatMillis(epochMs: Long): String =
    if (epochMs > 0L) RecoveryTimeFormatter.format(Instant.ofEpochMilli(epochMs)) else "unknown time"
