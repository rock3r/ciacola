package dev.sebastiano.ciacola.decks

import androidx.compose.runtime.Immutable
import dev.sebastiano.ciacola.DeckEntry
import dev.sebastiano.ciacola.document.DeckDecodeResult
import dev.sebastiano.ciacola.document.DeckDocument
import dev.sebastiano.ciacola.document.DeckDocumentCodec
import dev.sebastiano.ciacola.document.DeckDocumentStore
import dev.sebastiano.ciacola.document.DeckLoadState
import dev.sebastiano.ciacola.document.DeckMigrations
import dev.sebastiano.ciacola.document.SyncState
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

const val DECKS_DIR_SYSTEM_PROPERTY: String = "ciacola.decks.dir"
const val RECOVERY_DIR_SYSTEM_PROPERTY: String = "ciacola.recovery.dir"

/**
 * Location from which a `<deckId>.deck.json` document is opened:
 * - [File]: writable tracked `<deckId>.deck.json` under `-Dciacola.decks.dir` (authoring enabled via `DeckFileSync`).
 * - [Resource]: packaged classpath `decks/<deckId>.deck.json` (strictly read-only; edits rejected).
 */
@Immutable
sealed interface DeckSource {
    @Immutable
    data class File(
        val file: Path,
        val assetsDir: Path?,
    ) : DeckSource

    @Immutable
    data class Resource(
        val resourcePath: String,
        val assetsResourceDir: String,
    ) : DeckSource
}

/**
 * Resolves the [DeckSource] for [entry]:
 * - When [DeckEntry.externalFilePath] is set (for arbitrary external `.deck.json` files or newly
 *   discovered non-built-in decks), opens [DeckSource.File] on that path alongside its sibling
 *   `<stem>.assets` (or `<id>.assets`) directory.
 * - Otherwise, when `-Dciacola.decks.dir` is set and `<dir>/<deckId>.deck.json` exists and is
 *   writable, opens [DeckSource.File] on that file; else opens [DeckSource.Resource] read-only.
 */
fun resolveDeckSource(
    entry: DeckEntry,
    decksDirProperty: String? = System.getProperty(DECKS_DIR_SYSTEM_PROPERTY),
): DeckSource {
    val externalPathRaw = entry.externalFilePath?.trim()?.takeIf { it.isNotEmpty() }
    if (externalPathRaw != null) {
        val normalized = Path.of(externalPathRaw).toAbsolutePath().normalize()
        val fileName = normalized.fileName?.toString().orEmpty()
        val stem = if (fileName.endsWith(".deck.json")) {
            fileName.removeSuffix(".deck.json")
        } else {
            fileName.substringBeforeLast('.', fileName)
        }
        val siblingAssets = normalized.resolveSibling("$stem.assets")
            .takeIf { Files.isDirectory(it) }
            ?: normalized.resolveSibling("${entry.id}.assets")
                .takeIf { Files.isDirectory(it) }
        return DeckSource.File(file = normalized, assetsDir = siblingAssets)
    }
    val rawDir = decksDirProperty?.trim()?.takeIf { it.isNotEmpty() }
    if (rawDir != null) {
        val candidate = runCatching { Path.of(rawDir).resolve("${entry.id}.deck.json") }.getOrNull()
        if (candidate != null && Files.isRegularFile(candidate) && Files.isWritable(candidate)) {
            val normalized = candidate.toAbsolutePath().normalize()
            val siblingAssets = normalized.resolveSibling("${entry.id}.assets")
                .takeIf { Files.isDirectory(it) }
            return DeckSource.File(file = normalized, assetsDir = siblingAssets)
        }
    }
    return DeckSource.Resource(
        resourcePath = entry.resourcePath,
        assetsResourceDir = entry.assetsResourceDir,
    )
}

/**
 * Resolves the configured decks directory from `-Dciacola.decks.dir`, or the repository's default
 * `deck/src/desktopMain/resources/decks` directory when running from the workspace.
 */
fun resolveDefaultDecksDirectory(
    decksDirProperty: String? = System.getProperty(DECKS_DIR_SYSTEM_PROPERTY),
): Path? {
    val explicit = decksDirProperty?.trim()?.takeIf { it.isNotEmpty() }
    if (explicit != null) {
        val p = Path.of(explicit).toAbsolutePath().normalize()
        if (Files.isDirectory(p)) return p
    }
    val repoCandidate = Path.of("deck/src/desktopMain/resources/decks")
    if (Files.isDirectory(repoCandidate)) return repoCandidate.toAbsolutePath().normalize()
    val moduleCandidate = Path.of("src/desktopMain/resources/decks")
    if (Files.isDirectory(moduleCandidate)) return moduleCandidate.toAbsolutePath().normalize()
    return null
}

/**
 * Resolves the OS app-data recovery root directory (`…/ciacola/recovery`) outside `.storyboard/`.
 */
fun resolveAppDataRecoveryRoot(
    overrideProperty: String? = System.getProperty(RECOVERY_DIR_SYSTEM_PROPERTY),
): Path {
    val explicit = overrideProperty?.trim()?.takeIf { it.isNotEmpty() }
    if (explicit != null) {
        return Path.of(explicit).toAbsolutePath().normalize()
    }
    val os = System.getProperty("os.name").orEmpty().lowercase()
    val home = Path.of(System.getProperty("user.home", ".")).toAbsolutePath().normalize()
    return when {
        "mac" in os || "darwin" in os ->
            home.resolve("Library").resolve("Application Support").resolve("ciacola").resolve("recovery")
        "win" in os -> {
            val localAppData = System.getenv("LOCALAPPDATA")?.takeIf { it.isNotBlank() }?.let(Path::of)
                ?: home.resolve("AppData").resolve("Local")
            localAppData.resolve("ciacola").resolve("recovery")
        }
        else -> {
            val xdgData = System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() }?.let(Path::of)
                ?: home.resolve(".local").resolve("share")
            xdgData.resolve("ciacola").resolve("recovery")
        }
    }
}

/**
 * Loads and decodes a classpath [DeckDocument] for [entry].
 */
fun loadClasspathDeckDecodeResult(
    entry: DeckEntry,
    classLoader: ClassLoader = AllDocumentDecks::class.java.classLoader,
): DeckDecodeResult {
    val stream = classLoader.getResourceAsStream(entry.resourcePath)
        ?: return DeckDecodeResult.Failure(
            listOf(
                dev.sebastiano.ciacola.document.DocumentIssue(
                    severity = dev.sebastiano.ciacola.document.IssueSeverity.LoadError,
                    code = "RESOURCE_NOT_FOUND",
                    address = entry.resourcePath,
                    message = "Classpath deck resource not found: ${entry.resourcePath}",
                ),
            ),
        )
    val rawText = stream.use { it.readBytes().toString(StandardCharsets.UTF_8) }
    return DeckDocumentCodec.decode(
        rawText = rawText,
        specimens = entry.specimens,
        migrations = DeckMigrations.Default,
    )
}

/**
 * Loads a [DeckDocument] from the classpath, throwing if the bundled resource is missing or invalid.
 */
fun loadClasspathDeckDocument(entry: DeckEntry): DeckDocument =
    when (val decoded = loadClasspathDeckDecodeResult(entry)) {
        is DeckDecodeResult.Success -> decoded.document
        is DeckDecodeResult.Failure -> error(
            "Bundled deck resource '${entry.resourcePath}' failed validation: ${decoded.errors}",
        )
        is DeckDecodeResult.ReadOnlyNewerFormat -> error(
            "Bundled deck resource '${entry.resourcePath}' has unsupported format v${decoded.foundVersion}",
        )
    }

/**
 * Creates a read-only [DeckDocumentStore] backed by [DeckSource.Resource].
 */
fun openReadOnlyResourceDeckStore(entry: DeckEntry): DeckDocumentStore {
    val store = DeckDocumentStore()
    when (val decoded = loadClasspathDeckDecodeResult(entry)) {
        is DeckDecodeResult.Success -> {
            store.loadInitial(
                doc = decoded.document,
                readOnlyReason = "Opened from packaged classpath resource (${entry.resourcePath}); set -Dciacola.decks.dir to edit.",
                issues = decoded.lintIssues,
            )
        }
        is DeckDecodeResult.ReadOnlyNewerFormat -> {
            store.markReadOnlyNewerFormat(
                foundVersion = decoded.foundVersion,
                supportedVersion = decoded.supportedVersion,
                message = decoded.message,
            )
        }
        is DeckDecodeResult.Failure -> {
            store.markLoadInvalid(decoded.errors)
        }
    }
    return store
}

/**
 * Creates a read-only [DeckDocumentStore] backed by a non-writable [DeckSource.File].
 */
fun openReadOnlyFileDeckStore(
    entry: DeckEntry,
    file: Path,
    assetsDir: Path? = null,
): DeckDocumentStore {
    val store = DeckDocumentStore(
        validator = { DeckDocumentCodec.validate(it, specimens = entry.specimens) },
    )
    val rawText = runCatching { Files.readString(file, StandardCharsets.UTF_8) }.getOrElse { err ->
        store.markLoadInvalid(
            listOf(
                dev.sebastiano.ciacola.document.DocumentIssue(
                    severity = dev.sebastiano.ciacola.document.IssueSeverity.LoadError,
                    code = "FILE_READ_ERROR",
                    address = file.toString(),
                    message = err.message ?: "Unable to read ${file.fileName}",
                ),
            ),
        )
        return store
    }
    val deckDir = file.parent
    val decoded = DeckDocumentCodec.decode(
        rawText = rawText,
        specimens = entry.specimens,
        migrations = DeckMigrations.Default,
        assetExists = { src ->
            val norm = src.trim().removePrefix("/")
            if (norm.isEmpty()) {
                false
            } else {
                fun existsInside(baseDir: Path?): Boolean {
                    if (baseDir == null) return false
                    val base = baseDir.toAbsolutePath().normalize()
                    val resolved = runCatching { base.resolve(norm).normalize() }.getOrNull() ?: return false
                    return resolved.startsWith(base) && Files.isRegularFile(resolved)
                }
                existsInside(assetsDir) ||
                    existsInside(deckDir?.resolve("${entry.id}.assets")) ||
                    existsInside(deckDir)
            }
        },
    )
    when (decoded) {
        is DeckDecodeResult.Success -> {
            store.loadInitial(
                doc = decoded.document,
                readOnlyReason = "Read-only file: $file",
                issues = decoded.lintIssues,
            )
        }
        is DeckDecodeResult.ReadOnlyNewerFormat -> {
            store.markReadOnlyNewerFormat(
                foundVersion = decoded.foundVersion,
                supportedVersion = decoded.supportedVersion,
                message = decoded.message,
            )
        }
        is DeckDecodeResult.Failure -> {
            store.markLoadInvalid(decoded.errors)
        }
    }
    return store
}
