package dev.sebastiano.ciacola.inspector.design

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.sebastiano.ciacola.document.Curve
import dev.sebastiano.ciacola.document.DeckDecodeResult
import dev.sebastiano.ciacola.document.DeckDocument
import dev.sebastiano.ciacola.document.DeckDocumentCodec
import dev.sebastiano.ciacola.document.DeckDocumentStore
import dev.sebastiano.ciacola.document.DeckThemeSpec
import dev.sebastiano.ciacola.document.EditTransaction
import dev.sebastiano.ciacola.document.SlidePaletteSpec
import dev.sebastiano.ciacola.document.TextStyleOverride
import dev.sebastiano.ciacola.document.ThemeMotionDefaults
import dev.sebastiano.ciacola.document.TransitionSpec
import dev.sebastiano.ciacola.document.TransitionType
import dev.sebastiano.ciacola.document.isLightThemePreset
import dev.sebastiano.ciacola.document.toCodeSchemeSpec
import dev.sebastiano.ciacola.inspector.document.DocumentThemeResolver
import dev.sebastiano.ciacola.syntax.theme.SyntaxColorScheme
import dev.sebastiano.ciacola.syntax.theme.SyntaxColorSchemes
import dev.sebastiano.ciacola.syntax.theme.SyntaxSchemeImporters
import dev.sebastiano.ciacola.syntax.theme.SyntaxSchemeParseException
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path

/** Status feedback for code scheme selection/import operations in [DocumentThemeInspectorController]. */
@Immutable
data class CodeSchemeStatus(
    val message: String,
    val isError: Boolean,
)

/**
 * Document-backed Theme & Palette Inspector controller bound directly to [DeckDocumentStore].
 *
 * Every color, typography, motion-default, palette, and *Copy themes from deck…* mutation writes
 * directly into `document.themes` / `document.palettes` on the single unified undo stack.
 */
@Stable
class DocumentThemeInspectorController(
    val store: DeckDocumentStore,
    initialThemeName: String? = null,
) {
    var activeThemeName: String by mutableStateOf(
        DocumentThemeResolver.resolveActiveThemeName(store.document, initialThemeName),
    )

    var selectedTypographyRole: String by mutableStateOf("body")

    var selectedPaletteName: String? by mutableStateOf(store.document.palettes.keys.firstOrNull())

    var lastCopyStatus: String? by mutableStateOf(null)
        private set

    var codeSchemeStatus: CodeSchemeStatus? by mutableStateOf(null)
        private set

    val lastCodeSchemeStatus: String?
        get() = codeSchemeStatus?.message

    /** Optional unified workspace Undo/Redo & cross-controller gesture handoff hooks. */
    var onUndoRequest: (() -> Unit)? = null
    var onRedoRequest: (() -> Unit)? = null
    var onBeforeBeginGesture: (() -> Unit)? = null

    private var activeTx: EditTransaction? = null

    val hasActiveTransaction: Boolean
        get() = activeTx?.isActive == true

    fun performUndo() {
        val hook = onUndoRequest
        if (hook != null) {
            hook()
        } else {
            if (!cancelGesture()) store.undo()
        }
        codeSchemeStatus = null
    }

    fun performRedo() {
        val hook = onRedoRequest
        if (hook != null) {
            hook()
        } else {
            store.redo()
        }
        codeSchemeStatus = null
    }

    fun currentThemeSpec(): DeckThemeSpec =
        DocumentThemeResolver.resolveThemeSpec(store.document, activeThemeName)

    fun availableThemeNames(): List<String> {
        val keys = store.document.themes.keys.toList()
        return if (keys.isEmpty()) listOf(activeThemeName) else keys
    }

    fun selectTheme(themeName: String) {
        activeThemeName = themeName
        codeSchemeStatus = null
    }

    fun setDefaultTheme(themeName: String): Boolean =
        applyMutation("Set default theme '$themeName'", useActiveGesture = false) { doc ->
            doc.copy(defaultTheme = themeName)
        }

    fun beginGesture(label: String): Boolean {
        if (activeTx?.isActive == true) return true
        onBeforeBeginGesture?.invoke()
        val tx = store.begin(label) ?: return false
        activeTx = tx
        return true
    }

    fun commitGesture() {
        val tx = activeTx ?: return
        activeTx = null
        tx.commit()
    }

    fun cancelGesture(): Boolean {
        val tx = activeTx
        if (tx != null && tx.isActive) {
            activeTx = null
            tx.cancel()
            return true
        }
        if (store.hasOpenTransaction) {
            store.cancelActiveTransaction()
            return true
        }
        return false
    }

    // ── Theme Preset & Colors ────────────────────────────────────────────────────────────────────

    fun setThemePreset(preset: String): Boolean {
        val ok = mutateActiveTheme("Change theme preset") { it.copy(preset = preset) }
        if (ok) {
            codeSchemeStatus = null
        }
        return ok
    }

    fun setThemeColor(
        tokenKey: String,
        hexColor: String,
        useActiveGesture: Boolean = false,
    ): Boolean = mutateActiveTheme("Edit theme color $tokenKey", useActiveGesture) { theme ->
        val nextColors = LinkedHashMap(theme.colors)
        nextColors[tokenKey] = hexColor
        theme.copy(colors = nextColors)
    }

    fun resetThemeColor(tokenKey: String): Boolean =
        mutateActiveTheme("Reset theme color $tokenKey") { theme ->
            if (tokenKey !in theme.colors) return@mutateActiveTheme theme
            val nextColors = LinkedHashMap(theme.colors).apply { remove(tokenKey) }
            theme.copy(colors = nextColors)
        }

    // ── Code Highlighting Schemes ────────────────────────────────────────────────────────────────

    fun activeCodeScheme(): SyntaxColorScheme =
        DocumentThemeResolver.resolveCodeScheme(currentThemeSpec())

    fun isCodeSchemeModified(): Boolean {
        val spec = currentThemeSpec()
        return spec.codeScheme != null ||
            spec.customCodeScheme != null ||
            hasCodeColorOverrides(spec.colors)
    }

    fun selectBundledCodeScheme(schemeId: String): Boolean {
        val scheme = SyntaxColorSchemes.findById(schemeId) ?: run {
            codeSchemeStatus = CodeSchemeStatus("Unknown code scheme '$schemeId'.", isError = true)
            return false
        }
        val ok = mutateActiveTheme("Select code scheme '${scheme.name}'") { theme ->
            val presetDefaultId = SyntaxColorSchemes.defaultFor(isLightThemePreset(theme.preset)).id
            theme.copy(
                codeScheme = if (scheme.id == presetDefaultId) null else scheme.id,
                customCodeScheme = null,
                colors = stripStaleCodeColorOverrides(theme.colors),
            )
        }
        if (ok) {
            codeSchemeStatus = null
        }
        return ok
    }

    fun importCodeSchemeFromText(
        content: String,
        fileName: String? = null,
        includeResolver: ((String) -> String?)? = null,
    ): Boolean {
        val imported = try {
            SyntaxSchemeImporters.importAuto(content, fileName, includeResolver = includeResolver)
        } catch (e: SyntaxSchemeParseException) {
            codeSchemeStatus = CodeSchemeStatus(e.message ?: "Failed to parse code scheme.", isError = true)
            return false
        } catch (e: Exception) {
            codeSchemeStatus = CodeSchemeStatus(
                "Failed to import code scheme: ${e.message ?: "unknown error"}",
                isError = true,
            )
            return false
        }
        val ok = mutateActiveTheme("Import code scheme '${imported.name}'") { theme ->
            theme.copy(
                codeScheme = null,
                customCodeScheme = imported.toCodeSchemeSpec(),
                colors = stripStaleCodeColorOverrides(theme.colors),
            )
        }
        if (ok) {
            codeSchemeStatus = CodeSchemeStatus("Imported '${imported.name}'", isError = false)
        }
        return ok
    }

    fun importCodeSchemeFromFile(path: Path): Boolean {
        val displayName = path.fileName?.toString()?.takeIf { it.isNotBlank() } ?: "selected file"
        val content = readBoundedUtf8SchemeFile(path, displayName).getOrElse { err ->
            codeSchemeStatus = CodeSchemeStatus(err.message ?: "Failed to read $displayName.", isError = true)
            return false
        }
        val normalizedBaseDir = path.toAbsolutePath().normalize().parent
        val realBaseDir = normalizedBaseDir?.let { runCatching { it.toRealPath() }.getOrNull() ?: it }
        val includeResolver: ((String) -> String?)? = if (normalizedBaseDir != null && realBaseDir != null) {
            { includePath ->
                resolveConfinedInclude(
                    normalizedBaseDir = normalizedBaseDir,
                    realBaseDir = realBaseDir,
                    includePath = includePath,
                )
            }
        } else {
            null
        }
        return importCodeSchemeFromText(
            content = content,
            fileName = displayName,
            includeResolver = includeResolver,
        )
    }

    fun resetCodeScheme(): Boolean {
        if (!isCodeSchemeModified()) {
            codeSchemeStatus = null
            return false
        }
        val ok = mutateActiveTheme("Reset code scheme to preset default") { theme ->
            theme.copy(
                codeScheme = null,
                customCodeScheme = null,
                colors = stripStaleCodeColorOverrides(theme.colors),
            )
        }
        if (ok) {
            codeSchemeStatus = null
        }
        return ok
    }

    private fun resolveConfinedInclude(
        normalizedBaseDir: Path,
        realBaseDir: Path,
        includePath: String,
    ): String? {
        if (includePath.isBlank()) return null
        if (includePath.startsWith('/') || includePath.startsWith('\\')) return null
        if (includePath.length >= 2 && includePath[0].isLetter() && includePath[1] == ':') return null
        val rel = runCatching { Path.of(includePath) }.getOrNull() ?: return null
        if (rel.isAbsolute) return null
        val resolved = normalizedBaseDir.resolve(rel).normalize()
        if (!resolved.startsWith(normalizedBaseDir)) return null
        if (!Files.isRegularFile(resolved)) return null
        val realResolved = runCatching { resolved.toRealPath() }.getOrNull() ?: return null
        if (!realResolved.startsWith(realBaseDir)) return null
        val includeDisplay = resolved.fileName?.toString()?.takeIf { it.isNotBlank() } ?: "included file"
        return readBoundedUtf8SchemeFile(realResolved, includeDisplay).getOrElse { err ->
            throw SyntaxSchemeParseException(err.message ?: "Failed to read $includeDisplay")
        }
    }

    private fun readBoundedUtf8SchemeFile(path: Path, displayName: String): Result<String> {
        if (!Files.exists(path)) {
            return Result.failure(IOException("Failed to read $displayName: file not found."))
        }
        if (!Files.isRegularFile(path)) {
            return Result.failure(IOException("Failed to read $displayName: not a regular file."))
        }
        val bytes = try {
            Files.newInputStream(path).use { input ->
                input.readNBytes(MAX_CODE_SCHEME_IMPORT_BYTES + 1)
            }
        } catch (_: NoSuchFileException) {
            return Result.failure(IOException("Failed to read $displayName: file not found."))
        } catch (_: AccessDeniedException) {
            return Result.failure(IOException("Failed to read $displayName: permission denied."))
        } catch (_: SecurityException) {
            return Result.failure(IOException("Failed to read $displayName: permission denied."))
        } catch (_: IOException) {
            return Result.failure(IOException("Failed to read $displayName: I/O error."))
        }
        if (bytes.size > MAX_CODE_SCHEME_IMPORT_BYTES) {
            return Result.failure(IOException("Failed to read $displayName: exceeds 4 MB limit."))
        }
        return try {
            val decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            Result.success(decoder.decode(ByteBuffer.wrap(bytes)).toString())
        } catch (_: CharacterCodingException) {
            Result.failure(IOException("Failed to read $displayName: invalid UTF-8."))
        }
    }

    private fun isCodeColorOverrideKey(key: String): Boolean =
        key == "codeBackground" || key == "codeForeground" || key.startsWith("code.")

    private fun hasCodeColorOverrides(colors: Map<String, String>): Boolean =
        colors.keys.any(::isCodeColorOverrideKey)

    private fun stripStaleCodeColorOverrides(colors: Map<String, String>): Map<String, String> {
        if (!hasCodeColorOverrides(colors)) return colors
        return LinkedHashMap(colors).apply {
            keys.removeAll(::isCodeColorOverrideKey)
        }
    }

    private companion object {
        const val MAX_CODE_SCHEME_IMPORT_BYTES: Int = 4 * 1024 * 1024
    }

    // ── Typography Roles ─────────────────────────────────────────────────────────────────────────

    fun updateTypographyRole(
        role: String,
        useActiveGesture: Boolean = false,
        transform: (TextStyleOverride) -> TextStyleOverride,
    ): Boolean = mutateActiveTheme("Edit typography role $role", useActiveGesture) { theme ->
        val existing = theme.typography[role] ?: TextStyleOverride()
        val next = LinkedHashMap(theme.typography)
        next[role] = transform(existing)
        theme.copy(typography = next)
    }

    fun resetTypographyRole(role: String): Boolean =
        mutateActiveTheme("Reset typography role $role") { theme ->
            if (role !in theme.typography) return@mutateActiveTheme theme
            val next = LinkedHashMap(theme.typography).apply { remove(role) }
            theme.copy(typography = next)
        }

    // ── Motion Defaults ──────────────────────────────────────────────────────────────────────────

    fun setMotionDefaultDurationMs(
        durationMs: Int,
        useActiveGesture: Boolean = false,
    ): Boolean = mutateActiveTheme("Edit theme motion duration", useActiveGesture) { theme ->
        val motion = theme.motion ?: ThemeMotionDefaults()
        theme.copy(motion = motion.copy(durationMs = durationMs.coerceAtLeast(0)))
    }

    fun setMotionDefaultCurve(curve: Curve): Boolean =
        mutateActiveTheme("Edit theme motion curve") { theme ->
            val motion = theme.motion ?: ThemeMotionDefaults()
            theme.copy(motion = motion.copy(curve = curve))
        }

    fun setMotionDefaultTransition(
        type: TransitionType,
        durationMs: Int? = null,
        curve: Curve? = null,
    ): Boolean = mutateActiveTheme("Edit theme default transition") { theme ->
        val motion = theme.motion ?: ThemeMotionDefaults()
        val prevTrans = motion.transition ?: TransitionSpec()
        theme.copy(
            motion = motion.copy(
                transition = prevTrans.copy(
                    type = type,
                    durationMs = durationMs ?: prevTrans.durationMs,
                    curve = curve ?: prevTrans.curve,
                ),
            ),
        )
    }

    // ── Slide Palettes (`document.palettes`) ─────────────────────────────────────────────────────

    fun updatePalette(
        paletteName: String,
        useActiveGesture: Boolean = false,
        transform: (SlidePaletteSpec) -> SlidePaletteSpec,
    ): Boolean = applyMutation("Edit palette $paletteName", useActiveGesture) { doc ->
        val existing = doc.palettes[paletteName] ?: SlidePaletteSpec()
        val next = LinkedHashMap(doc.palettes)
        next[paletteName] = transform(existing)
        doc.copy(palettes = next)
    }

    fun addPalette(paletteName: String, spec: SlidePaletteSpec = SlidePaletteSpec()): Boolean {
        val trimmed = paletteName.trim()
        if (trimmed.isEmpty()) return false
        val ok = applyMutation("Add palette $trimmed", useActiveGesture = false) { doc ->
            val next = LinkedHashMap(doc.palettes)
            next[trimmed] = spec
            doc.copy(palettes = next)
        }
        if (ok) selectedPaletteName = trimmed
        return ok
    }

    fun removePalette(paletteName: String): Boolean {
        val ok = applyMutation("Remove palette $paletteName", useActiveGesture = false) { doc ->
            if (paletteName !in doc.palettes) return@applyMutation doc
            val next = LinkedHashMap(doc.palettes).apply { remove(paletteName) }
            doc.copy(palettes = next)
        }
        if (ok && selectedPaletteName == paletteName) {
            selectedPaletteName = store.document.palettes.keys.firstOrNull()
        }
        return ok
    }

    // ── Copy Themes From Another Deck ────────────────────────────────────────────────────────────

    /**
     * Copies `themes` (and optionally `palettes`) from [sourceDocument] into this deck as ONE
     * undoable edit on [DeckDocumentStore].
     */
    fun copyThemesFromDocument(
        sourceDocument: DeckDocument,
        includePalettes: Boolean = false,
        replaceExisting: Boolean = false,
    ): Boolean {
        if (sourceDocument.themes.isEmpty() && (!includePalettes || sourceDocument.palettes.isEmpty())) {
            lastCopyStatus = "Source deck '${sourceDocument.id}' contains no themes to copy."
            return false
        }
        val ok = applyMutation("Copy themes from ${sourceDocument.id}", useActiveGesture = false) { doc ->
            val nextThemes = if (replaceExisting) {
                LinkedHashMap(sourceDocument.themes)
            } else {
                LinkedHashMap(doc.themes).apply { putAll(sourceDocument.themes) }
            }
            val nextPalettes = if (!includePalettes) {
                doc.palettes
            } else if (replaceExisting) {
                LinkedHashMap(sourceDocument.palettes)
            } else {
                LinkedHashMap(doc.palettes).apply { putAll(sourceDocument.palettes) }
            }
            val nextDefault = doc.defaultTheme?.takeIf { it in nextThemes }
                ?: sourceDocument.defaultTheme?.takeIf { it in nextThemes }
                ?: nextThemes.keys.firstOrNull()
            doc.copy(
                themes = nextThemes,
                defaultTheme = nextDefault,
                palettes = nextPalettes,
            )
        }
        if (ok) {
            if (activeThemeName !in store.document.themes) {
                activeThemeName = DocumentThemeResolver.resolveActiveThemeName(store.document)
            }
            lastCopyStatus = "Copied ${sourceDocument.themes.size} theme(s) from '${sourceDocument.id}'."
        }
        return ok
    }

    /**
     * Reads and validates an external `<other>.deck.json` file at [deckPath], copying its themes
     * into the active deck in a single undoable transaction.
     */
    fun copyThemesFromDeckFile(
        deckPath: Path,
        includePalettes: Boolean = false,
        replaceExisting: Boolean = false,
    ): Result<Int> {
        val raw = runCatching { Files.readString(deckPath, StandardCharsets.UTF_8) }
            .getOrElse { err ->
                lastCopyStatus = "Failed to read ${deckPath.fileName}: ${err.message}"
                return Result.failure(err)
            }
        return when (val decoded = DeckDocumentCodec.decode(raw)) {
            is DeckDecodeResult.Success -> {
                val copied = copyThemesFromDocument(
                    sourceDocument = decoded.document,
                    includePalettes = includePalettes,
                    replaceExisting = replaceExisting,
                )
                if (copied) Result.success(decoded.document.themes.size)
                else Result.failure(IllegalStateException(lastCopyStatus ?: "No themes copied."))
            }
            is DeckDecodeResult.Failure -> {
                val msg = decoded.errors.firstOrNull()?.message ?: "Invalid deck JSON."
                lastCopyStatus = "Cannot copy themes from ${deckPath.fileName}: $msg"
                Result.failure(IllegalArgumentException(lastCopyStatus))
            }
            is DeckDecodeResult.ReadOnlyNewerFormat -> {
                lastCopyStatus = "Cannot copy themes from newer format v${decoded.foundVersion}."
                Result.failure(IllegalStateException(lastCopyStatus))
            }
        }
    }

    private fun mutateActiveTheme(
        label: String,
        useActiveGesture: Boolean = false,
        transform: (DeckThemeSpec) -> DeckThemeSpec,
    ): Boolean {
        val key = activeThemeName
        return applyMutation(label, useActiveGesture) { doc ->
            val current = doc.themes[key] ?: DocumentThemeResolver.resolveThemeSpec(doc, key)
            val nextThemes = LinkedHashMap(doc.themes)
            nextThemes[key] = transform(current)
            doc.copy(
                themes = nextThemes,
                defaultTheme = doc.defaultTheme ?: key,
            )
        }
    }

    private fun applyMutation(
        label: String,
        useActiveGesture: Boolean,
        transform: (DeckDocument) -> DeckDocument,
    ): Boolean {
        if (useActiveGesture) {
            if (activeTx?.isActive != true) {
                if (!beginGesture(label)) return false
            }
            val tx = activeTx ?: return false
            tx.update(transform)
            return true
        }
        if (activeTx?.isActive == true) {
            commitGesture()
        }
        onBeforeBeginGesture?.invoke()
        return store.edit(label, transform)
    }
}
