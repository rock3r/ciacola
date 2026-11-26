package dev.sebastiano.ciacola.studio

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.MenuScope
import dev.bnorm.storyboard.ContentDecorator
import dev.bnorm.storyboard.easel.EaselWindow
import dev.sebastiano.ciacola.DeckEntry
import dev.sebastiano.ciacola.decks.DECKS_DIR_SYSTEM_PROPERTY
import dev.sebastiano.ciacola.decks.DeckSource
import dev.sebastiano.ciacola.decks.openReadOnlyFileDeckStore
import dev.sebastiano.ciacola.decks.openReadOnlyResourceDeckStore
import dev.sebastiano.ciacola.decks.resolveDeckSource
import dev.sebastiano.ciacola.document.DeckDecodeResult
import dev.sebastiano.ciacola.document.DeckDocumentCodec
import dev.sebastiano.ciacola.document.DeckDocumentStore
import dev.sebastiano.ciacola.document.DeckFileSync
import dev.sebastiano.ciacola.document.DeckMigrations
import dev.sebastiano.ciacola.document.SyncState
import dev.sebastiano.ciacola.inspector.sync.DocumentLoadStateGate
import dev.sebastiano.ciacola.inspector.sync.DocumentSyncController
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.typography

/**
 * Atomic bundle representing a fully decoded, lock-acquired deck session.
 *
 * Pre-opening [ActiveDeckSession] off the active composition before swapping [Main]'s state
 * guarantees zero frames of mixed deck title / blank stage / loading flash when switching decks.
 */
@Stable
data class ActiveDeckSession(
    val entry: DeckEntry,
    val source: DeckSource,
    val store: DeckDocumentStore,
    val fileSync: DeckFileSync?,
    val loadToken: Int,
)

/**
 * Decodes and opens [entry] completely before returning so callers can swap UI state in a single
 * atomic Compose snapshot update. Releases the file lock if cancelled mid-open.
 */
suspend fun preOpenDeckSession(
    entry: DeckEntry,
    recoveryRootDir: Path,
    loadToken: Int,
    decksDirProperty: String? = System.getProperty(DECKS_DIR_SYSTEM_PROPERTY),
): ActiveDeckSession = withContext(Dispatchers.IO) {
    currentCoroutineContext().ensureActive()
    val source = resolveDeckSource(entry, decksDirProperty)
    when (source) {
        is DeckSource.File -> {
            val isWritable = Files.isRegularFile(source.file) &&
                Files.isWritable(source.file) &&
                (source.file.parent?.let(Files::isWritable) != false)
            if (!isWritable) {
                val store = openReadOnlyFileDeckStore(entry, source.file, source.assetsDir)
                currentCoroutineContext().ensureActive()
                return@withContext ActiveDeckSession(
                    entry = entry,
                    source = source,
                    store = store,
                    fileSync = null,
                    loadToken = loadToken,
                )
            }
            val store = DeckDocumentStore(
                validator = { DeckDocumentCodec.validate(it, specimens = entry.specimens) },
            )
            val sync = DeckFileSync(
                targetFile = source.file,
                recoveryRootDir = recoveryRootDir,
                store = store,
                specimenRegistry = entry.specimens,
            )
            try {
                sync.open()
                currentCoroutineContext().ensureActive()
            } catch (c: CancellationException) {
                runCatching { sync.close() }
                throw c
            } catch (t: Throwable) {
                runCatching { sync.close() }
                throw t
            }
            ActiveDeckSession(
                entry = entry,
                source = source,
                store = store,
                fileSync = sync,
                loadToken = loadToken,
            )
        }
        is DeckSource.Resource -> {
            val store = openReadOnlyResourceDeckStore(entry)
            currentCoroutineContext().ensureActive()
            ActiveDeckSession(
                entry = entry,
                source = source,
                store = store,
                fileSync = null,
                loadToken = loadToken,
            )
        }
    }
}

/**
 * Opens the initial deck synchronously at application startup so frame 0 renders the decoded deck
 * immediately without a `Loading` flash.
 */
fun openInitialDeckSession(
    entry: DeckEntry,
    recoveryRootDir: Path,
    decksDirProperty: String? = System.getProperty(DECKS_DIR_SYSTEM_PROPERTY),
): ActiveDeckSession = runBlocking {
    preOpenDeckSession(
        entry = entry,
        recoveryRootDir = recoveryRootDir,
        loadToken = 0,
        decksDirProperty = decksDirProperty,
    )
}

/**
 * Non-interactive top-center stage pill confirming `⌘Z Undo` / `⇧⌘Z Redo` / gesture cancellation
 * without ever overlapping bottom-right Inspector controls.
 */
@Composable
fun StageShortcutToastOverlay(
    toast: Pair<Long, String>?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    displayDurationMs: Long = 1_200L,
    onDismissed: (Long) -> Unit = {},
) {
    var visible by remember { mutableStateOf(false) }
    var lastText by remember { mutableStateOf("") }

    LaunchedEffect(toast?.first, enabled) {
        if (!enabled) {
            visible = false
            toast?.first?.let(onDismissed)
            return@LaunchedEffect
        }
        val current = toast ?: run {
            visible = false
            return@LaunchedEffect
        }
        lastText = current.second
        visible = true
        delay(displayDurationMs)
        visible = false
        onDismissed(current.first)
    }

    AnimatedVisibility(
        visible = enabled && visible && lastText.isNotBlank(),
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier.padding(top = 12.dp),
    ) {
        val shape = RoundedCornerShape(999.dp)
        Box(
            modifier = Modifier
                .clip(shape)
                .background(Color(0xE0141821), shape)
                .border(1.dp, Color(0x6694A3B8), shape)
                .padding(horizontal = 12.dp, vertical = 5.dp)
                .testTag("ciacola-stage-shortcut-toast"),
        ) {
            Text(
                text = lastText,
                style = JewelTheme.typography.small.copy(fontWeight = FontWeight.SemiBold),
                color = Color(0xFFF8FAFC),
                maxLines = 1,
            )
        }
    }
}

/** Applies a chosen recovery `.deck.json` file into [store] as an external change. */
fun openRecoveryCopyIntoStore(
    recoveryPath: Path,
    entry: DeckEntry,
    store: DeckDocumentStore,
) {
    val raw = runCatching { Files.readString(recoveryPath, StandardCharsets.UTF_8) }.getOrNull() ?: return
    val decoded = DeckDocumentCodec.decode(
        rawText = raw,
        specimens = entry.specimens,
        migrations = DeckMigrations.Default,
    )
    if (decoded is DeckDecodeResult.Success) {
        store.applyExternalChange(
            newDocument = decoded.document,
            label = "Open recovery copy ${recoveryPath.fileName}",
            markAsSaved = false,
            issues = decoded.lintIssues,
        )
        if (!store.isReadOnly) {
            store.setSyncState(SyncState.Dirty)
        }
    }
}

/** Sync banner + recovery picker + guarded-close decorator mounted inside the Story window. */
@Composable
fun rememberDocumentSyncGateWindow(
    syncController: DocumentSyncController?,
    store: DeckDocumentStore,
    onAllowClose: () -> Unit,
    isAudiencePresentingProvider: () -> Boolean = { false },
): EaselWindow = remember(syncController, store) {
    object : EaselWindow {
        override val name: String = "SyncGate"
        override var visible: Boolean = false
        override val decorator: ContentDecorator = ContentDecorator { content ->
            val sync = syncController
            if (sync != null) {
                DocumentLoadStateGate(
                    store = store,
                    controller = sync,
                    onAllowClose = onAllowClose,
                    isAudiencePresentingProvider = isAudiencePresentingProvider,
                ) {
                    content()
                }
            } else {
                content()
            }
        }

        @Composable
        override fun MenuScope.Menu() = Unit

        @Composable
        override fun Content() = Unit
    }
}
