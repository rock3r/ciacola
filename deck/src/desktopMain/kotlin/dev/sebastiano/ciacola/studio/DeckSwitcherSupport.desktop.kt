package dev.sebastiano.ciacola.studio

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.KeyShortcut
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.MenuScope
import androidx.compose.ui.window.PopupProperties
import dev.bnorm.storyboard.AdvanceDirection
import dev.bnorm.storyboard.ContentDecorator
import dev.bnorm.storyboard.Storyboard
import dev.bnorm.storyboard.easel.Animatic
import dev.bnorm.storyboard.easel.EaselWindow
import dev.bnorm.storyboard.easel.LocalOverviewOpen
import dev.sebastiano.ciacola.DeckCatalog
import dev.sebastiano.ciacola.DeckEntry
import dev.sebastiano.ciacola.ui.accessibleFocusRing
import dev.sebastiano.ciacola.ui.rememberAccessibleFocusRingColor
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.PopupMenu
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.Tooltip
import org.jetbrains.jewel.ui.typography

const val ACTIVE_DECK_SYSTEM_PROPERTY = "ciacola.deck"
const val ACTIVE_DECK_FILE_NAME = "active-deck"
private const val STUDIO_LAYOUT_FILE_NAME = "studio-layout.properties"
private const val INSPECTOR_WIDTH_KEY = "inspectorWidthDp"
private const val ANIMATIC_STATE_FILE_NAME = "animatic.json"

/**
 * Resolves the `.storyboard` storage directory for [deckId].
 * The Foundation deck preserves the root `.storyboard/` directory; additional decks live under
 * `.storyboard/decks/<deckId>/` so overrides and comments never collide across decks.
 */
fun storyboardDirForDeck(rootStoryboardDir: Path, deckId: String): Path =
    if (deckId == DeckCatalog.FOUNDATION_ID) {
        rootStoryboardDir
    } else {
        rootStoryboardDir.resolve("decks").resolve(deckId)
    }

/**
 * Resolves the initial active deck id: `-Dciacola.deck=<id>` takes precedence over
 * `.storyboard/active-deck`, falling back to the first entry in [entries] (or [DeckCatalog.defaultEntry]).
 */
fun resolveInitialDeckId(
    rootStoryboardDir: Path,
    systemPropertyOverride: String? = System.getProperty(ACTIVE_DECK_SYSTEM_PROPERTY),
    entries: List<DeckEntry> = DeckCatalog.entries,
): String {
    val overrideEntry = systemPropertyOverride?.trim()?.takeIf { it.isNotEmpty() }
        ?.let { DeckCatalog.byIdOrNull(it, entries) }
    if (overrideEntry != null) return overrideEntry.id
    val persisted = runCatching {
        val file = rootStoryboardDir.resolve(ACTIVE_DECK_FILE_NAME)
        if (Files.isRegularFile(file)) Files.readString(file).trim() else null
    }.getOrNull()
    return DeckCatalog.byId(persisted, entries).id
}

/** Persists the active deck id to `.storyboard/active-deck`. */
fun persistActiveDeckId(rootStoryboardDir: Path, deckId: String) {
    runCatching {
        Files.createDirectories(rootStoryboardDir)
        Files.writeString(rootStoryboardDir.resolve(ACTIVE_DECK_FILE_NAME), "$deckId\n")
    }
}

/** Loads the persisted Studio Inspector right-sidebar width from `.storyboard/studio-layout.properties`. */
fun loadPersistedInspectorWidthDp(rootStoryboardDir: Path): Float = runCatching {
    val file = rootStoryboardDir.resolve(STUDIO_LAYOUT_FILE_NAME)
    if (!Files.isRegularFile(file)) return@runCatching DEFAULT_STUDIO_INSPECTOR_WIDTH_DP
    val props = Properties()
    Files.newBufferedReader(file).use(props::load)
    props.getProperty(INSPECTOR_WIDTH_KEY)
        ?.toFloatOrNull()
        ?.takeIf { it.isFinite() }
        ?.coerceIn(MIN_STUDIO_INSPECTOR_WIDTH_DP, MAX_STUDIO_INSPECTOR_WIDTH_DP)
        ?: DEFAULT_STUDIO_INSPECTOR_WIDTH_DP
}.getOrDefault(DEFAULT_STUDIO_INSPECTOR_WIDTH_DP)

/** Persists the Studio Inspector right-sidebar width to `.storyboard/studio-layout.properties`. */
fun persistInspectorWidthDp(rootStoryboardDir: Path, widthDp: Float) {
    runCatching {
        val clamped = widthDp.coerceIn(MIN_STUDIO_INSPECTOR_WIDTH_DP, MAX_STUDIO_INSPECTOR_WIDTH_DP)
        Files.createDirectories(rootStoryboardDir)
        val file = rootStoryboardDir.resolve(STUDIO_LAYOUT_FILE_NAME)
        val props = Properties()
        if (Files.isRegularFile(file)) {
            runCatching { Files.newBufferedReader(file).use(props::load) }
        }
        props.setProperty(INSPECTOR_WIDTH_KEY, "%.1f".format(java.util.Locale.US, clamped))
        Files.newBufferedWriter(file).use { writer ->
            props.store(writer, "Ciacola Studio layout preferences")
        }
    }
}

private val SCENE_INDEX_REGEX = Regex("\"sceneIndex\"\\s*:\\s*(\\d+)")
private val FRAME_INDEX_REGEX = Regex("\"frameIndex\"\\s*:\\s*(\\d+)")

private fun readPersistedAnimaticIndex(storyboardDir: Path): Storyboard.Index? = runCatching {
    val file = storyboardDir.resolve(ANIMATIC_STATE_FILE_NAME)
    if (!Files.isRegularFile(file)) return@runCatching null
    val raw = Files.readString(file)
    val scene = SCENE_INDEX_REGEX.find(raw)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: return@runCatching null
    val frame = FRAME_INDEX_REGEX.find(raw)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: return@runCatching null
    Storyboard.Index(sceneIndex = scene, frameIndex = frame)
}.getOrNull()

private fun writePersistedAnimaticIndex(storyboardDir: Path, index: Storyboard.Index) {
    runCatching {
        Files.createDirectories(storyboardDir)
        val json = """{"sceneIndex":${index.sceneIndex},"frameIndex":${index.frameIndex}}"""
        Files.writeString(storyboardDir.resolve(ANIMATIC_STATE_FILE_NAME), json)
    }
}

/**
 * Persists per-deck [Animatic] slide/beat position to `<storyboardDir>/animatic.json`.
 * Only restores the saved position on initial launch (`restoreOnEnter = true`); interactive deck
 * switches land on `(0, 0)`.
 */
@Composable
fun DeckAnimaticPersistenceEffect(
    animatic: Animatic,
    storyboardDir: Path,
    restoreOnEnter: Boolean,
) {
    LaunchedEffect(animatic, storyboardDir, restoreOnEnter) {
        if (restoreOnEnter) {
            val saved = withContext(Dispatchers.IO) { readPersistedAnimaticIndex(storyboardDir) }
            if (saved != null && saved in animatic.storyboard.indices && saved != animatic.currentIndex) {
                animatic.jumpTo(saved)
            }
        }
    }
    val settledIndex = animatic.currentIndex
    LaunchedEffect(storyboardDir, settledIndex) {
        withContext(Dispatchers.IO) {
            writePersistedAnimaticIndex(storyboardDir, settledIndex)
        }
    }
}

/**
 * Window-level capture-phase navigation handler for Show Mode: routes clicker & keyboard slide
 * navigation keys (`→ ← ↑ ↓ PageDown PageUp Space Enter Backspace`) to [animatic] even when an
 * interactive stage specimen button holds keyboard focus, while yielding whenever text input,
 * Studio authoring, or the Overview grid is active.
 */
fun Modifier.showModeNavigationCapture(
    animaticProvider: () -> Animatic,
    isTextInputActive: () -> Boolean,
    isStudioOpen: () -> Boolean,
    onBeforeNavigate: () -> Unit = {},
): Modifier = composed {
    val scope = rememberCoroutineScope()
    val overviewOpen = LocalOverviewOpen.current
    var navJob by remember { mutableStateOf<Job?>(null) }

    onPreviewKeyEvent { ev ->
        if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        if (ev.isCtrlPressed || ev.isMetaPressed || ev.isAltPressed) return@onPreviewKeyEvent false
        if (isTextInputActive() || isStudioOpen() || overviewOpen) return@onPreviewKeyEvent false

        val direction = when (ev.key) {
            Key.DirectionRight,
            Key.DirectionDown,
            Key.PageDown,
            Key.Spacebar,
            Key.Enter,
            Key.NumPadEnter,
                -> AdvanceDirection.Forward

            Key.DirectionLeft,
            Key.DirectionUp,
            Key.PageUp,
            Key.Backspace,
                -> AdvanceDirection.Backward

            else -> null
        } ?: return@onPreviewKeyEvent false

        onBeforeNavigate()
        val targetAnimatic = animaticProvider()
        navJob?.cancel()
        navJob = scope.launch {
            targetAnimatic.advance(direction)
            navJob = null
        }
        true
    }
}

/**
 * Leftmost Studio toolbar Jewel dropdown (`Deck: <title> ▾`) allowing instant switching across
 * [entries] (defaulting to [DeckCatalog.entries]).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DeckSwitcherDropdown(
    activeDeck: DeckEntry,
    onSelectDeck: (DeckEntry) -> Unit,
    entries: List<DeckEntry> = DeckCatalog.entries,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val globalColors = JewelTheme.globalColors
    val focusRingColor = rememberAccessibleFocusRingColor()
    var buttonFocused by remember { mutableStateOf(false) }
    val shortcutHint = remember(entries) {
        entries.indices.take(9).joinToString(" / ") { "⌘⌥${it + 1}" }
    }

    Box(modifier = modifier) {
        Tooltip(
            tooltip = {
                Text(if (shortcutHint.isNotEmpty()) "Switch active deck ($shortcutHint)" else "Switch active deck")
            },
        ) {
            OutlinedButton(
                onClick = { expanded = !expanded },
                modifier = Modifier
                    .onFocusChanged { buttonFocused = it.isFocused }
                    .accessibleFocusRing(buttonFocused, focusRingColor, cornerRadius = 4.dp)
                    .testTag("ciacola-deck-switcher")
                    .semantics {
                        contentDescription = "Deck switcher: ${activeDeck.title}"
                    },
            ) {
                Text(
                    text = "Deck: ${activeDeck.title} ▾",
                    style = JewelTheme.typography.small.copy(fontWeight = FontWeight.SemiBold),
                    color = globalColors.text.normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 196.dp),
                )
            }
        }

        if (expanded) {
            PopupMenu(
                onDismissRequest = {
                    expanded = false
                    true
                },
                horizontalAlignment = Alignment.Start,
                popupProperties = PopupProperties(focusable = true),
                modifier = Modifier
                    .widthIn(min = 240.dp, max = 340.dp)
                    .testTag("ciacola-deck-switcher-popup"),
            ) {
                entries.forEachIndexed { index, entry ->
                    val isSelected = entry.id == activeDeck.id
                    selectableItem(
                        selected = isSelected,
                        keybinding = if (index < 9) setOf("⌘", "⌥", "${index + 1}") else null,
                        onClick = {
                            expanded = false
                            onSelectDeck(entry)
                        },
                    ) {
                        Text(
                            text = entry.title,
                            style = JewelTheme.typography.small.copy(
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .semantics(mergeDescendants = true) {}
                                .testTag("ciacola-deck-option-${entry.id}"),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Menu-only + decorator [EaselWindow] that installs:
 * 1. The macOS top-level `Deck` menu (`⌘⌥1..9`) globally across all Ciacola windows.
 * 2. Window-level Show-mode focus-proof navigation capture on the Story window.
 */
class DeckMenuWindow(
    var activeDeckProvider: () -> DeckEntry,
    var onSelectDeck: (DeckEntry) -> Unit,
    var animaticProvider: () -> Animatic,
    var isTextInputActiveProvider: () -> Boolean,
    var isStudioOpenProvider: () -> Boolean,
    var onBeforeNavigate: () -> Unit = {},
    var extraPreviewKeyHandler: ((KeyEvent) -> Boolean)? = null,
    var entries: List<DeckEntry> = DeckCatalog.entries,
) : EaselWindow {
    override val name: String = "Deck"
    override var visible: Boolean = false
    internal var deckIdState by mutableStateOf(activeDeckProvider().id)

    override val decorator: ContentDecorator = ContentDecorator { content ->
        val fallbackFocusRequester = remember { FocusRequester() }
        var subtreeHasFocus by remember { mutableStateOf(false) }
        var fallbackFocused by remember { mutableStateOf(false) }
        var requestingFallbackFocus by remember { mutableStateOf(false) }
        val currentDeckId = deckIdState

        LaunchedEffect(subtreeHasFocus, currentDeckId) {
            if (!subtreeHasFocus && !isTextInputActiveProvider()) {
                requestingFallbackFocus = true
                withFrameNanos {}
                if (!subtreeHasFocus && !isTextInputActiveProvider()) {
                    runCatching { fallbackFocusRequester.requestFocus() }
                }
                requestingFallbackFocus = false
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .onFocusChanged { state ->
                    subtreeHasFocus = state.hasFocus
                    fallbackFocused = state.isFocused
                }
                .focusRequester(fallbackFocusRequester)
                .focusProperties { canFocus = requestingFallbackFocus || fallbackFocused }
                .focusable()
                .onPreviewKeyEvent { ev ->
                    extraPreviewKeyHandler?.invoke(ev) == true
                }
                .showModeNavigationCapture(
                    animaticProvider = animaticProvider,
                    isTextInputActive = isTextInputActiveProvider,
                    isStudioOpen = isStudioOpenProvider,
                    onBeforeNavigate = onBeforeNavigate,
                ),
        ) {
            content()
        }
    }

    @Composable
    override fun MenuScope.Menu() {
        val current = activeDeckProvider()
        entries.forEachIndexed { index, entry ->
            val digitKey = when (index) {
                0 -> Key.One
                1 -> Key.Two
                2 -> Key.Three
                3 -> Key.Four
                4 -> Key.Five
                5 -> Key.Six
                6 -> Key.Seven
                7 -> Key.Eight
                8 -> Key.Nine
                else -> null
            }
            RadioButtonItem(
                text = entry.title,
                selected = current.id == entry.id,
                shortcut = digitKey?.let { KeyShortcut(it, meta = true, alt = true) },
                onClick = { onSelectDeck(entry) },
            )
        }
    }

    @Composable
    override fun Content() = Unit
}

@Composable
fun rememberDeckMenuWindow(
    activeDeckProvider: () -> DeckEntry,
    onSelectDeck: (DeckEntry) -> Unit,
    animaticProvider: () -> Animatic,
    isTextInputActiveProvider: () -> Boolean,
    isStudioOpenProvider: () -> Boolean,
    onBeforeNavigate: () -> Unit = {},
    extraPreviewKeyHandler: ((KeyEvent) -> Boolean)? = null,
    entries: List<DeckEntry> = DeckCatalog.entries,
): DeckMenuWindow = remember {
    DeckMenuWindow(
        activeDeckProvider = activeDeckProvider,
        onSelectDeck = onSelectDeck,
        animaticProvider = animaticProvider,
        isTextInputActiveProvider = isTextInputActiveProvider,
        isStudioOpenProvider = isStudioOpenProvider,
        onBeforeNavigate = onBeforeNavigate,
        extraPreviewKeyHandler = extraPreviewKeyHandler,
        entries = entries,
    )
}.also {
    it.activeDeckProvider = activeDeckProvider
    it.deckIdState = activeDeckProvider().id
    it.onSelectDeck = onSelectDeck
    it.animaticProvider = animaticProvider
    it.isTextInputActiveProvider = isTextInputActiveProvider
    it.isStudioOpenProvider = isStudioOpenProvider
    it.onBeforeNavigate = onBeforeNavigate
    it.extraPreviewKeyHandler = extraPreviewKeyHandler
    it.entries = entries
}
