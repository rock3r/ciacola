package dev.sebastiano.ciacola.studio

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.sebastiano.ciacola.ui.accessibleFocusRing
import dev.sebastiano.ciacola.ui.rememberAccessibleFocusRingColor
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.Tooltip
import org.jetbrains.jewel.ui.icon.IconKey
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.ui.theme.colorPalette
import org.jetbrains.jewel.ui.typography

/** Height of the single full-width Studio application bar. */
val StudioAppBarHeight: Dp = 36.dp

/** Below this bar width the right-hand buttons collapse to icon-only. */
private val CompactAppBarWidth: Dp = 1200.dp

/** Immutable snapshot of everything the App Bar displays. */
@Immutable
data class StudioAppBarModel(
    val breadcrumb: String,
    val mode: StudioMode,
    val canUndo: Boolean,
    val canRedo: Boolean,
    val saveState: StudioSaveState,
    val themeOpen: Boolean,
    val issuesOpen: Boolean,
    val timelineOpen: Boolean,
    val presenterOpen: Boolean,
    val errorCount: Int,
    val warningCount: Int,
)

/** Callbacks invoked by the App Bar controls. */
class StudioAppBarActions(
    val onSelectMode: (StudioMode) -> Unit,
    val onUndo: () -> Unit,
    val onRedo: () -> Unit,
    val onToggleTheme: () -> Unit,
    val onToggleIssues: () -> Unit,
    val onToggleTimeline: () -> Unit,
    val onTogglePresenter: () -> Unit,
    /**
     * Returns keyboard focus to the stage. Invoked after a pointer click on a right-hand App Bar
     * button so Space/Enter/arrows keep driving the deck instead of re-firing the button; keyboard
     * activation (Tab → Space/Enter) keeps focus on the button.
     */
    val onRestoreStageFocus: () -> Unit = {},
)

/**
 * Plain-language location breadcrumb: `Slide 3 of 136 · "Title" · Build 1 of 2`.
 *
 * Blank titles fall back to omitting the title segment (the slide number already reads
 * `Slide N`), and single-build slides omit the build segment.
 */
fun formatStudioBreadcrumb(
    sceneIndex: Int,
    sceneCount: Int,
    title: String?,
    beatIndex: Int,
    beatCount: Int,
): String {
    val count = sceneCount.coerceAtLeast(1)
    val slide = "Slide ${(sceneIndex + 1).coerceIn(1, count)} of $count"
    val cleanTitle = title?.trim()?.trim(*BreadcrumbQuoteChars)?.trim()?.takeIf { it.isNotEmpty() }
    val builds = beatCount.coerceAtLeast(1)
    return buildString {
        append(slide)
        if (cleanTitle != null) append(" · \u201C").append(cleanTitle).append('\u201D')
        if (builds > 1) append(" · Build ${(beatIndex + 1).coerceIn(1, builds)} of $builds")
    }
}

/** Quote characters stripped from slide titles before the breadcrumb wraps them in its own quotes. */
private val BreadcrumbQuoteChars = charArrayOf('"', '\u201C', '\u201D', '\u201E')

/** Background painted behind the App Bar (and the Studio shell): opaque, theme-aware panel colour. */
@Composable
fun studioChromeBackground(): Color = JewelTheme.globalColors.panelBackground

/** `52 · 270` issues badge copy (errors · warnings). */
fun formatIssuesBadge(errorCount: Int, warningCount: Int): String = "$errorCount · $warningCount"

/**
 * Single 36dp full-width Studio application bar:
 * - **Left:** deck switcher + plain-language breadcrumb.
 * - **Center:** `Edit | Review | Present` segmented mode control (truly centered).
 * - **Right:** Undo / Redo, save state, Theme (`F7`), Issues badge (`F8`), Timeline (`F5`),
 *   Presenter View (`F2`).
 */
@Composable
fun StudioAppBar(
    model: StudioAppBarModel,
    actions: StudioAppBarActions,
    deckSwitcher: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val globalColors = JewelTheme.globalColors
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(StudioAppBarHeight)
            // `toolwindowBackground` is Unspecified in standalone Jewel, which left the bar transparent
            // (light window paint showing through under dark-theme text). Always paint the panel colour.
            .background(studioChromeBackground())
            .testTag("ciacola-studio-app-bar"),
    ) {
        val compact = maxWidth < CompactAppBarWidth
        Row(
            modifier = Modifier.fillMaxWidth().fillMaxHeight().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier.weight(1f).testTag("ciacola-app-bar-left"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                deckSwitcher()
                Divider(Orientation.Vertical, Modifier.height(16.dp))
                Text(
                    text = model.breadcrumb,
                    style = JewelTheme.typography.regular,
                    color = globalColors.text.info,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false).testTag("ciacola-app-bar-breadcrumb"),
                )
            }

            StudioModeSegmentedControl(
                mode = model.mode,
                onSelectMode = actions.onSelectMode,
                modifier = Modifier.padding(horizontal = 12.dp),
            )

            Row(
                modifier = Modifier.weight(1f).testTag("ciacola-app-bar-right"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
            ) {
                // Save state sits left of Undo/Redo so its width changes never shift the buttons.
                SaveStateIndicator(model.saveState)
                AppBarButton(
                    icon = AllIconsKeys.Actions.Undo,
                    label = null,
                    tooltip = "Undo (⌘Z)",
                    enabled = model.canUndo,
                    testTag = "ciacola-app-bar-undo",
                    onRestoreStageFocus = actions.onRestoreStageFocus,
                    onClick = actions.onUndo,
                )
                AppBarButton(
                    icon = AllIconsKeys.Actions.Redo,
                    label = null,
                    tooltip = "Redo (⇧⌘Z)",
                    enabled = model.canRedo,
                    testTag = "ciacola-app-bar-redo",
                    onRestoreStageFocus = actions.onRestoreStageFocus,
                    onClick = actions.onRedo,
                )
                AppBarDivider()
                AppBarButton(
                    icon = AllIconsKeys.Actions.Colors,
                    label = if (compact) null else "Theme",
                    tooltip = "Deck theme (F7)",
                    toggled = model.themeOpen,
                    testTag = "ciacola-app-bar-theme",
                    onRestoreStageFocus = actions.onRestoreStageFocus,
                    onClick = actions.onToggleTheme,
                )
                IssuesBadgeButton(
                    errorCount = model.errorCount,
                    warningCount = model.warningCount,
                    toggled = model.issuesOpen,
                    onRestoreStageFocus = actions.onRestoreStageFocus,
                    onClick = actions.onToggleIssues,
                )
                AppBarButton(
                    icon = AllIconsKeys.General.Layout,
                    label = if (compact) null else "Timeline",
                    tooltip = "Timeline (F5)",
                    toggled = model.timelineOpen,
                    testTag = "ciacola-app-bar-timeline",
                    onRestoreStageFocus = actions.onRestoreStageFocus,
                    onClick = actions.onToggleTimeline,
                )
                AppBarButton(
                    icon = AllIconsKeys.Actions.Preview,
                    label = if (compact) null else "Presenter",
                    tooltip = "Presenter View (F2)",
                    toggled = model.presenterOpen,
                    testTag = "ciacola-app-bar-presenter",
                    onRestoreStageFocus = actions.onRestoreStageFocus,
                    // Opening: leave the button before the Presenter OS window takes over.
                    restoreFocusBeforeClick = !model.presenterOpen,
                    onClick = actions.onTogglePresenter,
                )
            }
        }
        Divider(
            Orientation.Horizontal,
            Modifier.fillMaxWidth().align(Alignment.BottomCenter),
        )
    }
}

/** `Edit | Review | Present` segmented control. Selection is a tint plus an accent border. */
@Composable
fun StudioModeSegmentedControl(
    mode: StudioMode,
    onSelectMode: (StudioMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val globalColors = JewelTheme.globalColors
    val shape = RoundedCornerShape(6.dp)
    Row(
        modifier = modifier
            .height(26.dp)
            .clip(shape)
            .border(1.dp, globalColors.borders.normal, shape)
            .padding(2.dp)
            .selectableGroup()
            .testTag("ciacola-app-bar-mode"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        for (entry in StudioMode.entries) {
            ModeSegment(
                mode = entry,
                selected = entry == mode,
                onClick = { onSelectMode(entry) },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ModeSegment(
    mode: StudioMode,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val globalColors = JewelTheme.globalColors
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val hovered by interaction.collectIsHoveredAsState()
    val focusRingColor = rememberAccessibleFocusRingColor()
    val shape = RoundedCornerShape(4.dp)
    val bg = when {
        selected -> globalColors.outlines.focused.copy(alpha = 0.18f)
        hovered -> globalColors.borders.normal.copy(alpha = 0.35f)
        else -> Color.Transparent
    }
    Tooltip(tooltip = { Text(mode.tooltip) }) {
        Box(
            modifier = Modifier
                .width(64.dp)
                .fillMaxHeight()
                .clip(shape)
                .background(bg, shape)
                .border(
                    1.dp,
                    if (selected) globalColors.outlines.focused.copy(alpha = 0.45f) else Color.Transparent,
                    shape,
                )
                .accessibleFocusRing(focused, focusRingColor, cornerRadius = 4.dp)
                .pointerHoverIcon(PointerIcon.Hand)
                .clickable(interactionSource = interaction, indication = null, role = Role.Tab, onClick = onClick)
                .semantics { this.selected = selected }
                .testTag("ciacola-app-bar-mode-${mode.name.lowercase()}"),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = mode.label,
                style = JewelTheme.typography.regular.copy(
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                ),
                color = if (selected) globalColors.text.normal else globalColors.text.info,
                maxLines = 1,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun AppBarButton(
    icon: IconKey?,
    label: String?,
    tooltip: String,
    testTag: String,
    enabled: Boolean = true,
    toggled: Boolean = false,
    onRestoreStageFocus: () -> Unit = {},
    restoreFocusBeforeClick: Boolean = false,
    onClick: () -> Unit,
) {
    val globalColors = JewelTheme.globalColors
    val activation = rememberPointerActivationTracker(onRestoreStageFocus)
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val hovered by interaction.collectIsHoveredAsState()
    val focusRingColor = rememberAccessibleFocusRingColor()
    val shape = RoundedCornerShape(4.dp)
    val bg = when {
        toggled -> globalColors.outlines.focused.copy(alpha = 0.16f)
        hovered && enabled -> globalColors.borders.normal.copy(alpha = 0.35f)
        else -> Color.Transparent
    }
    Tooltip(tooltip = { Text(tooltip) }) {
        Row(
            modifier = Modifier
                .height(26.dp)
                .clip(shape)
                .background(bg, shape)
                .accessibleFocusRing(focused, focusRingColor, cornerRadius = 4.dp)
                .pointerHoverIcon(if (enabled) PointerIcon.Hand else PointerIcon.Default)
                .trackPointerActivation(activation)
                .clickable(
                    interactionSource = interaction,
                    indication = null,
                    enabled = enabled,
                    role = Role.Button,
                    onClick = { activation.run(onClick, onRestoreStageFocus, restoreFocusBeforeClick) },
                )
                .semantics {
                    contentDescription = tooltip
                    if (toggled) stateDescription = "On"
                }
                .padding(horizontal = if (label == null) 5.dp else 7.dp)
                .testTag(testTag),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            if (icon != null) {
                Icon(
                    key = icon,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = if (enabled) Color.Unspecified else globalColors.text.disabled,
                )
            }
            if (label != null) {
                Text(
                    text = label,
                    style = JewelTheme.typography.regular,
                    color = if (enabled) globalColors.text.normal else globalColors.text.disabled,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun AppBarDivider() {
    Divider(Orientation.Vertical, Modifier.padding(horizontal = 4.dp).height(16.dp))
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SaveStateIndicator(state: StudioSaveState) {
    val globalColors = JewelTheme.globalColors
    val palette = JewelTheme.colorPalette
    val isDark = JewelTheme.isDark
    val tint = when (state) {
        StudioSaveState.Saved -> globalColors.text.info
        StudioSaveState.Saving -> globalColors.text.info
        StudioSaveState.ReadOnly -> globalColors.text.info
        StudioSaveState.Conflict, StudioSaveState.Failed, StudioSaveState.Invalid ->
            palette.redOrNull(if (isDark) 7 else 4) ?: globalColors.text.error
    }
    val tooltip = when (state) {
        StudioSaveState.Saved -> "All edits are saved to the .deck.json file"
        StudioSaveState.Saving -> "Edits are being written to the .deck.json file"
        StudioSaveState.ReadOnly -> "This deck is bundled read-only; edits are disabled"
        StudioSaveState.Conflict -> "The file changed on disk; resolve the conflict in the sync banner"
        StudioSaveState.Failed -> "The last save failed; see the sync banner for details"
        StudioSaveState.Invalid -> "The file on disk is not valid JSON; see the sync banner"
    }
    Tooltip(tooltip = { Text(tooltip) }) {
        Text(
            text = state.label,
            style = JewelTheme.typography.regular,
            color = tint,
            maxLines = 1,
            textAlign = TextAlign.End,
            modifier = Modifier
                .widthIn(min = 64.dp)
                .padding(horizontal = 4.dp)
                .semantics { contentDescription = "Save state: ${state.label}" }
                .testTag("ciacola-app-bar-save-state"),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun IssuesBadgeButton(
    errorCount: Int,
    warningCount: Int,
    toggled: Boolean,
    onRestoreStageFocus: () -> Unit,
    onClick: () -> Unit,
) {
    val globalColors = JewelTheme.globalColors
    val activation = rememberPointerActivationTracker(onRestoreStageFocus)
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val hovered by interaction.collectIsHoveredAsState()
    val focusRingColor = rememberAccessibleFocusRingColor()
    val shape = RoundedCornerShape(4.dp)
    val bg = when {
        toggled -> globalColors.outlines.focused.copy(alpha = 0.16f)
        hovered -> globalColors.borders.normal.copy(alpha = 0.35f)
        else -> Color.Transparent
    }
    val description = "$errorCount errors, $warningCount warnings (F8)"
    Tooltip(tooltip = { Text("Issues: $description") }) {
        Row(
            modifier = Modifier
                .height(26.dp)
                .clip(shape)
                .background(bg, shape)
                .accessibleFocusRing(focused, focusRingColor, cornerRadius = 4.dp)
                .pointerHoverIcon(PointerIcon.Hand)
                .trackPointerActivation(activation)
                .clickable(
                    interactionSource = interaction,
                    indication = null,
                    role = Role.Button,
                    onClick = { activation.run(onClick, onRestoreStageFocus, restoreBefore = false) },
                )
                .semantics {
                    contentDescription = "Issues: $description"
                    if (toggled) stateDescription = "Open"
                }
                .padding(horizontal = 7.dp)
                .testTag("ciacola-app-bar-issues"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(key = AllIconsKeys.General.Error, contentDescription = null, modifier = Modifier.size(14.dp))
            Text(
                text = errorCount.toString(),
                style = JewelTheme.typography.regular.copy(fontFeatureSettings = "tnum"),
                color = globalColors.text.normal,
                modifier = Modifier.testTag("ciacola-app-bar-issues-errors"),
            )
            Text(text = "·", style = JewelTheme.typography.regular, color = globalColors.text.info)
            Icon(key = AllIconsKeys.General.Warning, contentDescription = null, modifier = Modifier.size(14.dp))
            Text(
                text = warningCount.toString(),
                style = JewelTheme.typography.regular.copy(fontFeatureSettings = "tnum"),
                color = globalColors.text.normal,
                modifier = Modifier.testTag("ciacola-app-bar-issues-warnings"),
            )
        }
    }
}

/**
 * Remembers whether the next App Bar button activation came from a pointer (mouse/trackpad) or the
 * keyboard, so pointer clicks hand focus back to the stage while keyboard activation keeps it.
 */
private class PointerActivationTracker {
    var fromPointer: Boolean = false

    /** Bumped per pointer activation so the stage focus is re-asserted once the click's UI settles. */
    var settledRestoreRequests by mutableIntStateOf(0)

    fun run(onClick: () -> Unit, onRestoreStageFocus: () -> Unit, restoreBefore: Boolean) {
        val pointer = fromPointer
        fromPointer = false
        if (pointer && restoreBefore) onRestoreStageFocus()
        onClick()
        if (pointer && !restoreBefore) onRestoreStageFocus()
        // Never re-touch focus after an action that opened another OS window (Presenter).
        if (pointer && !restoreBefore) settledRestoreRequests++
    }
}

/**
 * The action may reshape the window (e.g. expanding the docked Timeline re-lays out the stage),
 * which can drop the focus restored synchronously; re-assert it after the next frame.
 */
@Composable
private fun rememberPointerActivationTracker(onRestoreStageFocus: () -> Unit): PointerActivationTracker {
    val tracker = remember { PointerActivationTracker() }
    val restore by rememberUpdatedState(onRestoreStageFocus)
    LaunchedEffect(tracker.settledRestoreRequests) {
        if (tracker.settledRestoreRequests == 0) return@LaunchedEffect
        withFrameNanos {}
        restore()
    }
    return tracker
}

/**
 * Flags pointer presses (observed on the initial pass, so it works even when the click itself
 * disables the button) and clears the flag on Space/Enter key-down or when focus leaves.
 * Must be placed before `clickable` so it wraps the button's focus target.
 */
@OptIn(ExperimentalComposeUiApi::class)
private fun Modifier.trackPointerActivation(tracker: PointerActivationTracker): Modifier =
    this
        .onFocusChanged { if (!it.isFocused) tracker.fromPointer = false }
        .onPreviewKeyEvent { event ->
            if (event.type == KeyEventType.KeyDown &&
                (event.key == Key.Spacebar || event.key == Key.Enter || event.key == Key.NumPadEnter)
            ) {
                tracker.fromPointer = false
            }
            false
        }
        .onPointerEvent(PointerEventType.Press, PointerEventPass.Initial) { tracker.fromPointer = true }
