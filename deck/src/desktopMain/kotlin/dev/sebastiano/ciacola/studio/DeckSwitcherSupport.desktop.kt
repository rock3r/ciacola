package dev.sebastiano.ciacola.studio

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import dev.sebastiano.ciacola.document.DeckDocument
import dev.sebastiano.ciacola.document.StudioSessionStore
import dev.sebastiano.ciacola.document.render.DocumentKeyboardCaptureController
import dev.sebastiano.ciacola.sanitizeDeckId
import dev.sebastiano.ciacola.ui.accessibleFocusRing
import dev.sebastiano.ciacola.ui.rememberAccessibleFocusRingColor
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.PopupMenu
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.Tooltip
import org.jetbrains.jewel.ui.component.separator
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.ui.typography

const val ACTIVE_DECK_SYSTEM_PROPERTY = "ciacola.deck"
const val SESSION_FILE_NAME = "session.json"

/** Returns the [StudioSessionStore] persisted under `<rootStoryboardDir>/session.json`. */
fun studioSessionStore(rootStoryboardDir: Path): StudioSessionStore =
    StudioSessionStore(rootStoryboardDir.resolve(SESSION_FILE_NAME))

/**
 * Resolves the `.storyboard` storage directory for [deckId] (used strictly for slide review
 * comments; never stores authored deck content or overrides).
 */
fun storyboardDirForDeck(rootStoryboardDir: Path, deckId: String): Path {
    if (deckId == DeckCatalog.FOUNDATION_ID) {
        return rootStoryboardDir.normalize()
    }
    val safeId = sanitizeDeckId(deckId, fallback = "external-deck")
    val decksRoot = rootStoryboardDir.resolve("decks").normalize()
    val target = decksRoot.resolve(safeId).normalize()
    require(target.startsWith(decksRoot) && target != decksRoot) {
        "Invalid storyboard directory '$target' for deckId='$deckId'"
    }
    return target
}

/**
 * Resolves the initial active deck id: `-Dciacola.deck=<id>` takes precedence over
 * [StudioSessionStore]'s `activeDeckId`, falling back to the first entry in [entries].
 */
fun resolveInitialDeckId(
    rootStoryboardDir: Path,
    systemPropertyOverride: String? = System.getProperty(ACTIVE_DECK_SYSTEM_PROPERTY),
    entries: List<DeckEntry> = DeckCatalog.entries,
): String {
    val overrideEntry = systemPropertyOverride?.trim()?.takeIf { it.isNotEmpty() }
        ?.let { DeckCatalog.byIdOrNull(it, entries) }
    if (overrideEntry != null) return overrideEntry.id
    val persisted = studioSessionStore(rootStoryboardDir).load().activeDeckId
    return DeckCatalog.byId(persisted, entries).id
}

/** Persists the active deck id to `.storyboard/session.json` via [StudioSessionStore]. */
fun persistActiveDeckId(rootStoryboardDir: Path, deckId: String) {
    runCatching {
        studioSessionStore(rootStoryboardDir).update { it.copy(activeDeckId = deckId) }
    }
}

/** Loads the persisted Studio Inspector right-sidebar width from `.storyboard/session.json`. */
fun loadPersistedInspectorWidthDp(rootStoryboardDir: Path): Float = runCatching {
    val store = studioSessionStore(rootStoryboardDir)
    if (!java.nio.file.Files.isRegularFile(store.sessionFile)) {
        return@runCatching DEFAULT_STUDIO_INSPECTOR_WIDTH_DP
    }
    store.load().workspaceLayout.inspectorWidthDp
        .takeIf { it.isFinite() }
        ?.coerceIn(MIN_STUDIO_INSPECTOR_WIDTH_DP, MAX_STUDIO_INSPECTOR_WIDTH_DP)
        ?: DEFAULT_STUDIO_INSPECTOR_WIDTH_DP
}.getOrDefault(DEFAULT_STUDIO_INSPECTOR_WIDTH_DP)

/** Persists the Studio Inspector right-sidebar width to `.storyboard/session.json`. */
fun persistInspectorWidthDp(rootStoryboardDir: Path, widthDp: Float) {
    runCatching {
        val clamped = widthDp.coerceIn(MIN_STUDIO_INSPECTOR_WIDTH_DP, MAX_STUDIO_INSPECTOR_WIDTH_DP)
        studioSessionStore(rootStoryboardDir).update { state ->
            state.copy(workspaceLayout = state.workspaceLayout.copy(inspectorWidthDp = clamped))
        }
    }
}

/**
 * Resolves the active theme name for [deckId] from `CIACOLA_THEME`, [StudioSessionStore], or
 * [document]'s `defaultTheme`.
 */
fun resolveSelectedThemeForDeck(
    rootStoryboardDir: Path,
    deckId: String,
    document: DeckDocument,
    envTheme: String? = System.getenv("CIACOLA_THEME"),
): String {
    val available = document.themes.keys
    val normalizedEnv = envTheme?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
    if (normalizedEnv != null) {
        val envMatch = available.firstOrNull { it.lowercase() == normalizedEnv }
        if (envMatch != null) return envMatch
    }
    val persisted = studioSessionStore(rootStoryboardDir).load().selectedThemeByDeck[deckId]
    if (persisted != null && (available.isEmpty() || persisted in available)) {
        return persisted
    }
    return document.defaultTheme.takeIf { available.isEmpty() || it in available }
        ?: available.firstOrNull()
        ?: "dark"
}

/** Persists the selected theme name for [deckId] in [StudioSessionStore]. */
fun persistSelectedThemeForDeck(rootStoryboardDir: Path, deckId: String, themeName: String) {
    runCatching {
        studioSessionStore(rootStoryboardDir).update { state ->
            state.copy(selectedThemeByDeck = state.selectedThemeByDeck + (deckId to themeName))
        }
    }
}

/**
 * Persists per-deck [Animatic] slide & beat position to `.storyboard/session.json` via
 * [StudioSessionStore]. Only restores on initial launch (`restoreOnEnter = true`);
 * interactive deck switches land on `(0, 0)`.
 */
@Composable
fun DeckSessionAnimaticPersistenceEffect(
    animatic: Animatic,
    document: DeckDocument,
    rootStoryboardDir: Path,
    deckId: String,
    restoreOnEnter: Boolean,
) {
    val hasSlides = document.slides.isNotEmpty()
    LaunchedEffect(animatic, rootStoryboardDir, deckId, restoreOnEnter, hasSlides) {
        if (!hasSlides) return@LaunchedEffect
        if (restoreOnEnter) {
            val session = withContext(Dispatchers.IO) { studioSessionStore(rootStoryboardDir).load() }
            val savedSlideId = session.lastSlideIdByDeck[deckId]
            val savedBeat = session.lastBeatByDeck[deckId] ?: 0
            if (savedSlideId != null) {
                val nonSkipped = document.slides.filterNot { it.skipped }
                val sceneIdx = nonSkipped.indexOfFirst { it.id == savedSlideId }
                if (sceneIdx >= 0) {
                    val maxBeat = nonSkipped[sceneIdx].beats.lastIndex.coerceAtLeast(0)
                    val target = Storyboard.Index(
                        sceneIndex = sceneIdx,
                        frameIndex = savedBeat.coerceIn(0, maxBeat),
                    )
                    if (target in animatic.storyboard.indices && target != animatic.currentIndex) {
                        animatic.jumpTo(target)
                    }
                }
            }
        }
    }
    val settledIndex = animatic.currentIndex
    LaunchedEffect(rootStoryboardDir, deckId, settledIndex, document, hasSlides) {
        if (!hasSlides) return@LaunchedEffect
        val nonSkipped = document.slides.filterNot { it.skipped }
        val slideId = nonSkipped.getOrNull(settledIndex.sceneIndex)?.id ?: return@LaunchedEffect
        val beatIdx = settledIndex.frameIndex.coerceAtLeast(0)
        withContext(Dispatchers.IO) {
            runCatching {
                studioSessionStore(rootStoryboardDir).update { state ->
                    state.copy(
                        lastSlideIdByDeck = state.lastSlideIdByDeck + (deckId to slideId),
                        lastBeatByDeck = state.lastBeatByDeck + (deckId to beatIdx),
                    )
                }
            }
        }
    }
}

/**
 * Window-level capture-phase navigation handler for Show Mode & Studio:
 * Routes clicker & keyboard slide navigation keys (`→ ← ↑ ↓ PageDown PageUp Space Enter Backspace`)
 * through [keyboardController] (when supplied) even when Studio panels are open so an entered
 * `"interactive": "keyboard"` specimen cleanly yields to `Esc` (without opening Overview) and
 * `PageUp`/`PageDown` (advancing the deck), while keeping typing and `⌘Z` local to the specimen.
 */
fun Modifier.showModeNavigationCapture(
    animaticProvider: () -> Animatic,
    isTextInputActive: () -> Boolean,
    isStudioOpen: () -> Boolean,
    keyboardController: DocumentKeyboardCaptureController? = null,
    onBeforeNavigate: () -> Unit = {},
): Modifier = composed {
    val scope = rememberCoroutineScope()
    val overviewOpen = LocalOverviewOpen.current
    var navJob by remember { mutableStateOf<Job?>(null) }
    var swallowNextEscapeKeyUp by remember { mutableStateOf(false) }

    fun dispatchAdvance(direction: AdvanceDirection) {
        onBeforeNavigate()
        val targetAnimatic = animaticProvider()
        navJob?.cancel()
        navJob = scope.launch {
            targetAnimatic.advance(direction)
            navJob = null
        }
    }

    onPreviewKeyEvent { ev ->
        if (ev.key == Key.Escape && ev.type == KeyEventType.KeyUp && swallowNextEscapeKeyUp) {
            swallowNextEscapeKeyUp = false
            return@onPreviewKeyEvent true
        }
        if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        if (overviewOpen) return@onPreviewKeyEvent false

        val kb = keyboardController
        if (kb != null && kb.isCaptured) {
            // While inside an entered "interactive": "keyboard" specimen (Show OR Studio mode):
            // - PageUp / PageDown ALWAYS advance the deck and exit interactive capture.
            // - Esc exits interactive capture and swallows its matching KeyUp so Overview never opens.
            // - All other keys (including typing, arrows, Space, Enter, Backspace, ⌘Z) flow to the specimen.
            return@onPreviewKeyEvent when (ev.key) {
                Key.PageDown -> {
                    if (ev.isCtrlPressed || ev.isMetaPressed || ev.isAltPressed) return@onPreviewKeyEvent false
                    kb.exitInteractive()
                    dispatchAdvance(AdvanceDirection.Forward)
                    true
                }
                Key.PageUp -> {
                    if (ev.isCtrlPressed || ev.isMetaPressed || ev.isAltPressed) return@onPreviewKeyEvent false
                    kb.exitInteractive()
                    dispatchAdvance(AdvanceDirection.Backward)
                    true
                }
                Key.Escape -> {
                    kb.exitInteractive()
                    swallowNextEscapeKeyUp = true
                    true
                }
                else -> false
            }
        }

        if (ev.isCtrlPressed || ev.isMetaPressed || ev.isAltPressed) return@onPreviewKeyEvent false
        if (isTextInputActive()) return@onPreviewKeyEvent false

        if (isStudioOpen()) {
            // When Studio panels are open and no specimen is captured:
            // - Enter on a focused "interactive": "keyboard" node enters capture.
            // - PageUp / PageDown navigate slides.
            // - Other keys stay available for Studio controls (timeline arrows, element nudging).
            if (kb != null &&
                (ev.key == Key.Enter || ev.key == Key.NumPadEnter) &&
                kb.focusedKeyboardSlideId != null &&
                kb.focusedKeyboardNodeId != null
            ) {
                kb.enterInteractive(kb.focusedKeyboardSlideId!!, kb.focusedKeyboardNodeId!!)
                return@onPreviewKeyEvent true
            }
            return@onPreviewKeyEvent when (ev.key) {
                Key.PageDown -> {
                    dispatchAdvance(AdvanceDirection.Forward)
                    true
                }
                Key.PageUp -> {
                    dispatchAdvance(AdvanceDirection.Backward)
                    true
                }
                else -> false
            }
        }

        if (kb != null) {
            val wasCaptured = kb.isCaptured
            val consumed = kb.handlePreviewKeyEvent(
                event = ev,
                onAdvance = ::dispatchAdvance,
                isExternalTextInputFocused = false,
            )
            if (wasCaptured && !kb.isCaptured && ev.key == Key.Escape) {
                swallowNextEscapeKeyUp = true
            }
            return@onPreviewKeyEvent consumed
        }

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

        dispatchAdvance(direction)
        true
    }
}

/**
 * App Bar deck switcher (`<title> ▾`) allowing instant switching across [entries] (defaulting to
 * [DeckCatalog.entries]) plus an `Open .deck.json…` item that loads an external deck file.
 *
 * Focus is released as soon as the popup closes, so no focus ring lingers on the trigger and
 * keyboard input returns to the stage via [onPopupClosed].
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DeckSwitcherDropdown(
    activeDeck: DeckEntry,
    onSelectDeck: (DeckEntry) -> Unit,
    entries: List<DeckEntry> = DeckCatalog.entries,
    modifier: Modifier = Modifier,
    entriesProvider: (() -> List<DeckEntry>)? = null,
    onOpenDeckFile: (() -> Unit)? = null,
    onPopupClosed: (() -> Unit)? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    var liveEntries by remember(entries) { mutableStateOf(entries) }
    val globalColors = JewelTheme.globalColors
    val focusRingColor = rememberAccessibleFocusRingColor()
    val focusManager = LocalFocusManager.current
    val interaction = remember { MutableInteractionSource() }
    val buttonFocused by interaction.collectIsFocusedAsState()
    val hovered by interaction.collectIsHoveredAsState()
    val shortcutHint = remember(liveEntries) {
        liveEntries.indices.take(9).joinToString(" / ") { "⌘⌥${it + 1}" }
    }
    val listedEntries = remember(liveEntries, activeDeck) {
        if (liveEntries.any { it.id == activeDeck.id && it.externalFilePath == activeDeck.externalFilePath }) {
            liveEntries
        } else if (liveEntries.any { it.id == activeDeck.id }) {
            liveEntries.map { if (it.id == activeDeck.id) activeDeck else it }
        } else {
            liveEntries + activeDeck
        }
    }

    fun closePopup() {
        expanded = false
        focusManager.clearFocus(force = true)
        onPopupClosed?.invoke()
    }

    Box(modifier = modifier) {
        Tooltip(
            tooltip = {
                Text(if (shortcutHint.isNotEmpty()) "Switch deck ($shortcutHint)" else "Switch deck")
            },
        ) {
            Row(
                modifier = Modifier
                    .height(26.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(
                        when {
                            expanded -> globalColors.outlines.focused.copy(alpha = 0.16f)
                            hovered -> globalColors.borders.normal.copy(alpha = 0.35f)
                            else -> Color.Transparent
                        },
                    )
                    .accessibleFocusRing(buttonFocused && !expanded, focusRingColor, cornerRadius = 4.dp)
                    .pointerHoverIcon(PointerIcon.Hand)
                    .clickable(
                        interactionSource = interaction,
                        indication = null,
                        role = Role.DropdownList,
                        onClick = {
                            if (expanded) {
                                closePopup()
                            } else {
                                if (entriesProvider != null) {
                                    liveEntries = entriesProvider()
                                }
                                expanded = true
                            }
                        },
                    )
                    .padding(horizontal = 8.dp)
                    .testTag("ciacola-deck-switcher")
                    .semantics {
                        contentDescription = "Deck switcher: ${activeDeck.title}"
                    },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = activeDeck.title,
                    style = JewelTheme.typography.regular.copy(fontWeight = FontWeight.SemiBold),
                    color = globalColors.text.normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 220.dp),
                )
                Icon(
                    key = AllIconsKeys.General.ChevronDown,
                    contentDescription = null,
                    modifier = Modifier.size(12.dp),
                )
            }
        }

        if (expanded) {
            PopupMenu(
                onDismissRequest = {
                    closePopup()
                    true
                },
                horizontalAlignment = Alignment.Start,
                popupProperties = PopupProperties(focusable = true),
                modifier = Modifier
                    .widthIn(min = 260.dp, max = 360.dp)
                    .testTag("ciacola-deck-switcher-popup"),
            ) {
                listedEntries.forEach { entry ->
                    val isSelected = entry.id == activeDeck.id
                    val boundIndex = liveEntries.indexOfFirst {
                        it.id == entry.id && it.externalFilePath == entry.externalFilePath
                    }.takeIf { it in 0 until 9 }
                        ?: liveEntries.indexOfFirst { it.id == entry.id }.takeIf { it in 0 until 9 }
                    selectableItem(
                        selected = isSelected,
                        keybinding = boundIndex?.let { setOf("⌘", "⌥", "${it + 1}") },
                        onClick = {
                            closePopup()
                            onSelectDeck(entry)
                        },
                    ) {
                        Text(
                            text = entry.title,
                            style = JewelTheme.typography.regular.copy(
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
                if (onOpenDeckFile != null) {
                    separator()
                    selectableItem(
                        selected = false,
                        onClick = {
                            closePopup()
                            onOpenDeckFile()
                        },
                    ) {
                        Text(
                            text = "Open .deck.json…",
                            style = JewelTheme.typography.regular,
                            maxLines = 1,
                            modifier = Modifier
                                .semantics(mergeDescendants = true) {}
                                .testTag("ciacola-deck-open-file"),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Menu-only + decorator [EaselWindow] that installs:
 * 1. The macOS top-level `Deck` menu (`⌘⌥1..4`) globally across all Ciacola windows.
 * 2. Window-level Show-mode focus-proof navigation capture on the Story window.
 */
class DeckMenuWindow(
    var activeDeckProvider: () -> DeckEntry,
    var onSelectDeck: (DeckEntry) -> Unit,
    var animaticProvider: () -> Animatic,
    var isTextInputActiveProvider: () -> Boolean,
    var isStudioOpenProvider: () -> Boolean,
    var keyboardController: DocumentKeyboardCaptureController? = null,
    var onBeforeNavigate: () -> Unit = {},
    var extraPreviewKeyHandler: ((KeyEvent) -> Boolean)? = null,
    var entries: List<DeckEntry> = DeckCatalog.entries,
    var entriesProvider: (() -> List<DeckEntry>)? = null,
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
            if (!subtreeHasFocus && !isTextInputActiveProvider() && keyboardController?.isCaptured != true) {
                requestingFallbackFocus = true
                withFrameNanos {}
                if (!subtreeHasFocus && !isTextInputActiveProvider() && keyboardController?.isCaptured != true) {
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
                    keyboardController = keyboardController,
                    onBeforeNavigate = onBeforeNavigate,
                ),
        ) {
            content()
        }
    }

    @Composable
    override fun MenuScope.Menu() {
        val current = activeDeckProvider()
        val menuEntries = entriesProvider?.invoke() ?: entries
        menuEntries.forEachIndexed { index, entry ->
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
    keyboardController: DocumentKeyboardCaptureController? = null,
    onBeforeNavigate: () -> Unit = {},
    extraPreviewKeyHandler: ((KeyEvent) -> Boolean)? = null,
    entries: List<DeckEntry> = DeckCatalog.entries,
    entriesProvider: (() -> List<DeckEntry>)? = null,
): DeckMenuWindow = remember {
    DeckMenuWindow(
        activeDeckProvider = activeDeckProvider,
        onSelectDeck = onSelectDeck,
        animaticProvider = animaticProvider,
        isTextInputActiveProvider = isTextInputActiveProvider,
        isStudioOpenProvider = isStudioOpenProvider,
        keyboardController = keyboardController,
        onBeforeNavigate = onBeforeNavigate,
        extraPreviewKeyHandler = extraPreviewKeyHandler,
        entries = entries,
        entriesProvider = entriesProvider,
    )
}.also {
    it.activeDeckProvider = activeDeckProvider
    it.deckIdState = activeDeckProvider().id
    it.onSelectDeck = onSelectDeck
    it.animaticProvider = animaticProvider
    it.isTextInputActiveProvider = isTextInputActiveProvider
    it.isStudioOpenProvider = isStudioOpenProvider
    it.keyboardController = keyboardController
    it.onBeforeNavigate = onBeforeNavigate
    it.extraPreviewKeyHandler = extraPreviewKeyHandler
    it.entries = entries
    it.entriesProvider = entriesProvider
}
