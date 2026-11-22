package dev.sebastiano.ciacola.document

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
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

    fun load(): StudioSessionState {
        if (!Files.exists(sessionFile)) return StudioSessionState()
        return runCatching {
            val raw = Files.readString(sessionFile, StandardCharsets.UTF_8)
            json.decodeFromString(StudioSessionState.serializer(), raw)
        }.getOrElse { StudioSessionState() }
    }

    fun save(state: StudioSessionState) {
        val parent = sessionFile.toAbsolutePath().normalize().parent
        if (parent != null) {
            Files.createDirectories(parent)
        }
        val tmp = sessionFile.resolveSibling(".${sessionFile.fileName}.tmp")
        val content = json.encodeToString(StudioSessionState.serializer(), state) + "\n"
        Files.writeString(tmp, content, StandardCharsets.UTF_8)
        Files.move(tmp, sessionFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    fun update(transform: (StudioSessionState) -> StudioSessionState): StudioSessionState {
        val next = transform(load())
        save(next)
        return next
    }
}
