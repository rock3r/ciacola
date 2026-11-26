package dev.sebastiano.ciacola.document

import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Non-authored Studio session preferences persisted under `.storyboard/session.json`.
 *
 * Strictly holds session selection and workspace layout only (active deck, last slide & beat per
 * deck, selected theme per deck, workspace panel layout). Never stores authored slide content,
 * styles, or motion overrides.
 */
@Serializable
data class StudioSessionState(
    val activeDeckId: String? = null,
    val lastSlideIdByDeck: Map<String, String> = emptyMap(),
    val lastBeatByDeck: Map<String, Int> = emptyMap(),
    val selectedThemeByDeck: Map<String, String> = emptyMap(),
    val workspaceLayout: WorkspaceLayoutState = WorkspaceLayoutState(),
)

@Serializable
data class WorkspaceLayoutState(
    val inspectorOpen: Boolean = false,
    val timelineOpen: Boolean = false,
    val inspectorWidthDp: Float = 360f,
    val timelineHeightDp: Float = 260f,
    val activeInspectorTab: String = "element",
    val presenterNotesFontSizeSp: Float = 20f,
)

/**
 * Persistence store for [StudioSessionState] under `.storyboard/session.json`.
 */
class StudioSessionStore(
    val sessionFile: Path,
) {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun load(): StudioSessionState = synchronized(lockFor(sessionFile)) {
        loadUnlocked()
    }

    fun save(state: StudioSessionState) = synchronized(lockFor(sessionFile)) {
        saveUnlocked(state)
    }

    fun update(transform: (StudioSessionState) -> StudioSessionState): StudioSessionState =
        synchronized(lockFor(sessionFile)) {
            val next = transform(loadUnlocked())
            saveUnlocked(next)
            next
        }

    private fun loadUnlocked(): StudioSessionState {
        if (!Files.exists(sessionFile)) return StudioSessionState()
        return runCatching {
            val raw = Files.readString(sessionFile, StandardCharsets.UTF_8)
            json.decodeFromString(StudioSessionState.serializer(), raw)
        }.getOrElse { StudioSessionState() }
    }

    private fun saveUnlocked(state: StudioSessionState) {
        val normalized = sessionFile.toAbsolutePath().normalize()
        val parent = normalized.parent ?: Path.of(".")
        Files.createDirectories(parent)
        val tmp = Files.createTempFile(parent, ".${normalized.fileName}.", ".tmp")
        val content = json.encodeToString(StudioSessionState.serializer(), state) + "\n"
        try {
            Files.writeString(tmp, content, StandardCharsets.UTF_8)
            try {
                Files.move(tmp, normalized, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tmp, normalized, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            runCatching { Files.deleteIfExists(tmp) }
        }
    }

    private companion object {
        private val fileLocks = ConcurrentHashMap<Path, Any>()

        private fun lockFor(sessionFile: Path): Any =
            fileLocks.computeIfAbsent(sessionFile.toAbsolutePath().normalize()) { Any() }
    }
}
