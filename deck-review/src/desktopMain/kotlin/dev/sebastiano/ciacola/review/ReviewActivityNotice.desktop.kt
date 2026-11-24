package dev.sebastiano.ciacola.review

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.sebastiano.ciacola.ui.ReviewStatusPalette
import dev.sebastiano.ciacola.ui.rememberReviewStatusPalette
import kotlinx.coroutines.delay
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.IconButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.Tooltip
import org.jetbrains.jewel.ui.icon.IconKey
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.ui.typography

/**
 * What just happened to one or more review comments, as the author needs to read it.
 *
 * The statuses on a pin say where a comment *is*; a notice says what the last click *did*, which
 * is the part Queue and Send otherwise leave indistinguishable.
 */
enum class ReviewNoticeKind {
    /** Saved to the queue, waiting for an agent to pick it up. */
    Queued,

    /** Being pushed to a configured agent conversation right now. */
    Sending,

    /** The push succeeded; the agent now has it. */
    Delivered,

    /** A push was asked for but there was nothing to push to; the comment stayed queued. */
    NothingToPushTo,

    /** The push was attempted and failed. */
    Failed,
}

/** A short-lived message shown over the stage after a review action. */
data class ReviewActivityNotice(
    val id: Long,
    val kind: ReviewNoticeKind,
    val title: String,
    val detail: String,
    /** How long before it fades on its own; `null` keeps it up until something replaces it. */
    val autoDismissMillis: Long?,
)

/** Long enough to read two lines without having to hunt for them. */
internal const val QUEUED_NOTICE_MILLIS = 6_000L

/** Something did not go the way the click asked for: give it time to be read. */
internal const val STICKY_NOTICE_MILLIS = 14_000L

/** Renders `#9`, `#9 and #10`, `#9, #10 and #11`. */
internal fun formatCommentNumbers(numbers: List<Int>): String = when (numbers.size) {
    0 -> "the comment"
    1 -> "#${numbers.single()}"
    else -> numbers.dropLast(1).joinToString(", ") { "#$it" } + " and #${numbers.last()}"
}

private fun plural(numbers: List<Int>, one: String, many: String) =
    if (numbers.size == 1) one else many

internal fun queuedNotice(numbers: List<Int>, agentConfigured: Boolean): Pair<String, String> {
    val subject = formatCommentNumbers(numbers)
    val title = "Queued ${plural(numbers, "comment", "comments")} $subject"
    val push = if (agentConfigured) " Or press Send in the top right to push now." else ""
    return title to
        "An agent waiting on :deck:reviewWait picks ${itThem(numbers)} up within seconds.$push"
}

internal fun sendingNotice(numbers: List<Int>): Pair<String, String> =
    "Sending ${formatCommentNumbers(numbers)} to the agent…" to
        "Pushing to the configured conversation."

private fun isAre(numbers: List<Int>) = if (numbers.size == 1) "is" else "are"

private fun itThem(numbers: List<Int>) = if (numbers.size == 1) "it" else "them"

internal fun alreadyWithAnAgentNotice(numbers: List<Int>): Pair<String, String> =
    "${formatCommentNumbers(numbers)} ${isAre(numbers)} already with an agent" to
        "An agent collected ${itThem(numbers)} from the queue and has not reported back yet. " +
        "Pushing again would put two of them on the same slide. Release the claim from the pin " +
        "if that agent is gone."

internal fun queueBusyNotice(file: java.nio.file.Path): Pair<String, String> =
    "The review queue is busy" to
        "Another process is holding ${file.toAbsolutePath()} and did not let go. Nothing was " +
        "changed; try again once it has finished."

internal fun deliveredNotice(numbers: List<Int>): Pair<String, String> =
    "The agent has ${formatCommentNumbers(numbers)}" to
        "The ${if (numbers.size == 1) "pin turns" else "pins turn"} green once the agent " +
        "reports ${itThem(numbers)} applied."

internal fun nothingToPushToNotice(numbers: List<Int>): Pair<String, String> =
    "${formatCommentNumbers(numbers)} ${isAre(numbers)} still queued" to
        "No push command is installed, so nothing was pushed. An agent waiting on " +
        ":deck:reviewWait picks ${itThem(numbers)} up from the queue instead."

internal fun deliveryFailedNotice(numbers: List<Int>, reason: String): Pair<String, String> =
    "Could not send ${formatCommentNumbers(numbers)}" to
        reason.ifBlank { "The agent command failed." }

private fun ReviewNoticeKind.color(palette: ReviewStatusPalette): Color = when (this) {
    ReviewNoticeKind.Queued, ReviewNoticeKind.NothingToPushTo -> palette.queued
    ReviewNoticeKind.Sending -> palette.applying
    ReviewNoticeKind.Delivered -> palette.applied
    ReviewNoticeKind.Failed -> palette.failed
}

private fun ReviewNoticeKind.iconKey(): IconKey = when (this) {
    ReviewNoticeKind.Queued, ReviewNoticeKind.NothingToPushTo -> AllIconsKeys.Actions.Upload
    ReviewNoticeKind.Sending -> AllIconsKeys.Actions.Refresh
    ReviewNoticeKind.Delivered -> AllIconsKeys.General.InspectionsOK
    ReviewNoticeKind.Failed -> AllIconsKeys.General.Error
}

/**
 * Bottom-centre notice over the stage telling the author what their last Queue or Send did, and
 * what happens to the comment next.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun BoxScope.ReviewActivityNoticeBar(
    notice: ReviewActivityNotice,
    onDismiss: () -> Unit,
    onOpenReviewPanel: () -> Unit,
) {
    val palette = rememberReviewStatusPalette()
    val globalColors = JewelTheme.globalColors
    val accent = notice.kind.color(palette)
    val shape = RoundedCornerShape(8.dp)

    LaunchedEffect(notice.id) {
        val millis = notice.autoDismissMillis ?: return@LaunchedEffect
        delay(millis)
        onDismiss()
    }

    Row(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(bottom = 24.dp)
            .widthIn(max = 520.dp)
            .background(globalColors.panelBackground.copy(alpha = 0.96f), shape)
            .border(1.dp, accent, shape)
            .padding(horizontal = 12.dp, vertical = 9.dp)
            .testTag("ciacola-review-notice"),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Icon(
            key = notice.kind.iconKey(),
            contentDescription = null,
            modifier = Modifier.size(14.dp).padding(top = 2.dp),
        )
        // Weighted, so a long delivery error cannot consume the row's width before the two
        // buttons are measured and leave them clipped to nothing.
        Column(
            modifier = Modifier.weight(1f, fill = false),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = notice.title,
                style = JewelTheme.typography.medium,
                color = accent,
                modifier = Modifier.testTag("ciacola-review-notice-title"),
            )
            Text(
                text = notice.detail,
                style = JewelTheme.typography.small,
                color = globalColors.text.info,
            )
        }
        Tooltip(tooltip = { Text("Open the Review panel (F4)") }) {
            IconButton(onClick = onOpenReviewPanel, modifier = Modifier.size(20.dp)) {
                Icon(
                    key = AllIconsKeys.Actions.Preview,
                    contentDescription = "Open the Review panel",
                    modifier = Modifier.size(12.dp),
                )
            }
        }
        Tooltip(tooltip = { Text("Dismiss") }) {
            IconButton(onClick = onDismiss, modifier = Modifier.size(20.dp)) {
                Icon(
                    key = AllIconsKeys.General.Close,
                    contentDescription = "Dismiss this notice",
                    modifier = Modifier.size(12.dp),
                )
            }
        }
    }
}

/**
 * Whether [bin] names something executable: a path that is executable, or a bare name on [path].
 *
 * A relative command resolves against [baseDir], not the process's working directory, because
 * that is what the push itself does — it sets `ProcessBuilder.directory(workspaceRoot)`. Checking
 * the two against different roots hides Send for a `./tools/agentapi` that would have run, or
 * offers it for one that would not.
 *
 * Windows is not an afterthought here: the deck ships an MSI, and this runs while the store is
 * being constructed, so a `PATH` this cannot parse would stop the deck opening. Hence both
 * separators, quoted entries, `PATHEXT`, and no path parse that can escape as an exception.
 */
internal fun isExecutableAvailable(
    bin: String,
    path: String?,
    baseDir: java.nio.file.Path? = null,
    pathExt: String? = System.getenv("PATHEXT"),
    // Injectable so Windows behaviour can be tested from a POSIX host, where this is ":" and
    // every quoted "C:\..." entry would otherwise split down the middle.
    pathSeparator: Char = java.io.File.pathSeparatorChar,
    isExecutable: (java.nio.file.Path) -> Boolean = {
        java.nio.file.Files.isExecutable(it) && !java.nio.file.Files.isDirectory(it)
    },
): Boolean {
    val trimmed = bin.trim().removeSurrounding("\"")
    if (trimmed.isEmpty()) return false

    // On Windows a bare name resolves through PATHEXT, so "agentapi" has to find agentapi.exe.
    // An empty extension first keeps the POSIX case unchanged.
    val suffixes = listOf("") + pathExt.orEmpty()
        .split(pathSeparator)
        .map { it.trim() }
        .filter { it.isNotEmpty() }

    fun candidate(parent: String?, name: String): java.nio.file.Path? = runCatching {
        val raw = if (parent == null) {
            java.nio.file.Path.of(name)
        } else {
            java.nio.file.Path.of(parent.trim().removeSurrounding("\""), name)
        }
        if (raw.isAbsolute || baseDir == null) raw.normalize() else baseDir.resolve(raw).normalize()
    }.getOrNull()

    if (trimmed.contains('/') || trimmed.contains('\\')) {
        return suffixes.any { suffix ->
            candidate(null, trimmed + suffix)?.let(isExecutable) == true
        }
    }
    return path.orEmpty()
        .split(pathSeparator)
        .filter { it.isNotBlank() }
        .any { entry ->
            suffixes.any { suffix -> candidate(entry, trimmed + suffix)?.let(isExecutable) == true }
        }
}

/**
 * Whether [bin] names a file that exists at all, executable or not.
 *
 * Separate from [isExecutableAvailable] on purpose: a command that is there but has lost its
 * execute bit fails to launch exactly like a missing one, and the author needs to be told which
 * of the two it was.
 */
internal fun commandExistsAnywhere(
    bin: String,
    path: String?,
    baseDir: java.nio.file.Path? = null,
): Boolean = isExecutableAvailable(bin, path, baseDir, isExecutable = { java.nio.file.Files.exists(it) })
