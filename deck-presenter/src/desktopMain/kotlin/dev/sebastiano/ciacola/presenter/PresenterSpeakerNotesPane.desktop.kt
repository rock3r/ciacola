package dev.sebastiano.ciacola.presenter

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.InfoText
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextArea
import org.jetbrains.jewel.ui.component.Tooltip
import org.jetbrains.jewel.ui.component.VerticalScrollbar
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.ui.typography

/**
 * Speaker notes pane with inline editing: an empty slide shows "No notes for this slide" with an
 * `Add notes` action, and slides with notes get an `Edit` action. While editing, every key types
 * into the notes field (navigation and curtain shortcuts are suspended); `⌘↵` saves, `Esc` cancels.
 * Navigating away (either window), switching decks, or closing the Presenter window auto-commits a
 * changed draft to the build it was opened on.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun PresenterSpeakerNotesPane(
    controller: PresenterController,
    onEditorClosed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val globalColors = JewelTheme.globalColors
    val resolved = controller.resolvedCurrentNotes()
    val scrollState = rememberScrollState()

    LaunchedEffect(resolved.sceneIndex, resolved.beatIndex) {
        scrollState.scrollTo(0)
    }
    // Navigating away (Story window, deck switch) closes an editor bound to the previous build,
    // auto-committing a changed draft to that build.
    val document = controller.documentProvider?.invoke()
    LaunchedEffect(
        controller.currentSceneIndex,
        controller.currentBeatIndex,
        controller.isEditingNotes,
        document?.id,
        controller.currentNoteTarget(),
    ) {
        controller.commitOrCancelEditingNotesIfTargetMoved()
    }
    // However the editor closed (save, cancel, navigation auto-commit), hand focus back to the dashboard.
    var wasEditing by remember { mutableStateOf(controller.isEditingNotes) }
    LaunchedEffect(controller.isEditingNotes) {
        if (wasEditing && !controller.isEditingNotes) onEditorClosed()
        wasEditing = controller.isEditingNotes
    }

    Column(
        modifier = modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(28.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "Speaker Notes",
                style = JewelTheme.typography.h4TextStyle,
                color = globalColors.text.normal,
                maxLines = 1,
            )
            if (resolved.isVirtualHoldBeat) {
                Text(
                    text = "Hold beat",
                    style = JewelTheme.typography.small,
                    color = globalColors.text.info,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .border(1.dp, globalColors.borders.normal, RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 1.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            if (resolved.hasNotes && !controller.isEditingNotes && controller.canEditNotes) {
                PresenterToolbarButton(
                    label = presenterNotesEditLabel(resolved),
                    tooltip = "Edit the notes for build ${resolved.beatIndex + 1}",
                    testTag = "ciacola-presenter-notes-edit",
                    onClick = { controller.beginEditingNotes() },
                )
            }
            if (!controller.isEditingNotes) {
                PresenterIconButton(
                    icon = AllIconsKeys.General.Remove,
                    contentDescription = "Decrease speaker notes font size",
                    tooltip = "Decrease notes font size (⌘-)",
                    enabled = controller.notesFontSizeSp > PresenterController.MIN_NOTES_FONT_SP,
                    testTag = "ciacola-presenter-notes-font-dec",
                    onClick = { controller.decreaseNotesFontSize() },
                )
                Text(
                    text = "${controller.notesFontSizeSp.toInt()} pt",
                    style = JewelTheme.typography.small.copy(fontFeatureSettings = "tnum"),
                    color = globalColors.text.info,
                    modifier = Modifier.testTag("ciacola-presenter-notes-font-size"),
                )
                PresenterIconButton(
                    icon = AllIconsKeys.General.Add,
                    contentDescription = "Increase speaker notes font size",
                    tooltip = "Increase notes font size (⌘=)",
                    enabled = controller.notesFontSizeSp < PresenterController.MAX_NOTES_FONT_SP,
                    testTag = "ciacola-presenter-notes-font-inc",
                    onClick = { controller.increaseNotesFontSize() },
                )
            }
        }

        Divider(Orientation.Horizontal, Modifier.fillMaxWidth())

        Box(
            modifier = Modifier
                .fillMaxSize()
                .weight(1f)
                .clip(RoundedCornerShape(6.dp))
                .background(globalColors.panelBackground)
                .border(1.dp, globalColors.borders.normal, RoundedCornerShape(6.dp)),
        ) {
            when {
                controller.isEditingNotes -> SpeakerNotesEditor(
                    controller = controller,
                )

                !resolved.hasNotes -> EmptyNotesState(controller)

                else -> NotesContent(controller, resolved, scrollState)
            }
        }
    }
}

/**
 * `Edit` when the build has its own notes; `Add build N notes` when only the slide context (build 1
 * notes) is showing, so the button never suggests it edits the text the speaker is reading.
 */
fun presenterNotesEditLabel(resolved: ResolvedSpeakerNotes): String =
    if (resolved.beatIndex > 0 && resolved.beatNote.isNullOrBlank() && resolved.slideNotes.isNotBlank()) {
        "Add build ${resolved.beatIndex + 1} notes"
    } else {
        "Edit"
    }

@Composable
private fun EmptyNotesState(controller: PresenterController) {
    val globalColors = JewelTheme.globalColors
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp)
            .testTag("ciacola-presenter-notes-empty"),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "No notes for this slide",
            style = JewelTheme.typography.h4TextStyle,
            color = globalColors.text.normal,
        )
        Spacer(Modifier.height(10.dp))
        if (controller.canEditNotes) {
            PresenterToolbarButton(
                label = "Add notes",
                tooltip = "Write speaker notes for this slide",
                testTag = "ciacola-presenter-notes-add",
                onClick = { controller.beginEditingNotes() },
            )
        } else {
            if (controller.isNotesReadOnly) InfoText("This deck's notes are read-only.")
        }
    }
}

@Composable
private fun NotesContent(
    controller: PresenterController,
    resolved: ResolvedSpeakerNotes,
    scrollState: androidx.compose.foundation.ScrollState,
) {
    val globalColors = JewelTheme.globalColors
    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(start = 16.dp, top = 16.dp, bottom = 16.dp, end = 22.dp)
                .testTag("ciacola-presenter-notes-content"),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            val noteFontSize = controller.notesFontSizeSp.sp
            val noteLineHeight = (controller.notesFontSizeSp * 1.45f).sp
            val beatNote = resolved.beatNote
            if (!beatNote.isNullOrBlank()) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "Build ${resolved.beatIndex + 1}",
                        style = JewelTheme.typography.small.copy(fontWeight = FontWeight.SemiBold),
                        color = globalColors.text.info,
                    )
                    Text(
                        text = beatNote,
                        style = JewelTheme.typography.regular.copy(
                            fontSize = noteFontSize,
                            lineHeight = noteLineHeight,
                        ),
                        color = globalColors.text.normal,
                    )
                }
            }

            if (resolved.slideNotes.isNotBlank() && resolved.slideNotes != beatNote) {
                if (!beatNote.isNullOrBlank()) {
                    Divider(Orientation.Horizontal, Modifier.fillMaxWidth())
                }
                val contextSize = (controller.notesFontSizeSp * 0.85f).coerceAtLeast(14f)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "Slide context",
                        style = JewelTheme.typography.small.copy(fontWeight = FontWeight.SemiBold),
                        color = globalColors.text.info,
                    )
                    Text(
                        text = resolved.slideNotes,
                        style = JewelTheme.typography.regular.copy(
                            fontSize = contextSize.sp,
                            lineHeight = (contextSize * 1.42f).sp,
                        ),
                        color = if (beatNote.isNullOrBlank()) globalColors.text.normal else globalColors.text.info,
                    )
                }
            }
        }
        VerticalScrollbar(
            scrollState = scrollState,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .padding(vertical = 4.dp, horizontal = 2.dp),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SpeakerNotesEditor(
    controller: PresenterController,
) {
    val target = controller.editingNoteTarget
    val session = controller.editingSessionId
    // Keyed per editing session so reopening the same target always starts from the stored note.
    val initial = remember(target, session) { controller.storedNote(target) }
    val state = key(target, session) { rememberTextFieldState(initialText = initial) }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(target) {
        runCatching { focusRequester.requestFocus() }
    }
    DisposableEffect(target, session) {
        controller.activeDraftProvider = { state.text.toString() }
        onDispose {
            // Leaving composition while still editing (Presenter window closed) auto-commits.
            // Explicit Esc/Cancel clear isEditingNotes first, so they never reach this path.
            if (controller.isEditingNotes &&
                controller.editingNoteTarget == target &&
                controller.editingSessionId == session
            ) {
                controller.commitEditingNotesIfDirty()
            }
            controller.activeDraftProvider = null
        }
    }

    // The pane's `isEditingNotes` effect owns handing focus back once the editor closes.
    fun save() {
        controller.commitEditingNotes(state.text.toString())
    }

    fun cancel() {
        controller.cancelEditingNotes()
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(10.dp).testTag("ciacola-presenter-notes-editor"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TextArea(
            state = state,
            lineLimits = TextFieldLineLimits.MultiLine(),
            placeholder = { Text("What do you want to say on build ${(target?.beatIndex ?: 0) + 1}?") },
            textStyle = JewelTheme.typography.regular.copy(
                fontSize = controller.notesFontSizeSp.sp,
                lineHeight = (controller.notesFontSizeSp * 1.45f).sp,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .focusRequester(focusRequester)
                .onPreviewKeyEvent { ev ->
                    if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when {
                        ev.key == Key.Escape -> {
                            cancel()
                            true
                        }

                        (ev.key == Key.Enter || ev.key == Key.NumPadEnter) && (ev.isMetaPressed || ev.isCtrlPressed) -> {
                            save()
                            true
                        }

                        else -> false
                    }
                }
                .testTag("ciacola-presenter-notes-input"),
        )
        // Adding build notes on top of slide-wide context: keep that context visible while typing.
        val context = controller.resolvedCurrentNotes()
        if ((target?.beatIndex ?: 0) > 0 && context.slideNotes.isNotBlank() && context.slideNotes != initial) {
            Column(
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.testTag("ciacola-presenter-notes-editor-context"),
            ) {
                Text(
                    text = "Slide context",
                    style = JewelTheme.typography.small.copy(fontWeight = FontWeight.SemiBold),
                    color = JewelTheme.globalColors.text.info,
                )
                Text(
                    text = context.slideNotes,
                    style = JewelTheme.typography.small,
                    color = JewelTheme.globalColors.text.info,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            InfoText("⌘↵ to save · Esc to cancel")
            Spacer(Modifier.weight(1f))
            Tooltip(tooltip = { Text("Discard changes (Esc)") }) {
                OutlinedButton(
                    onClick = { cancel() },
                    modifier = Modifier.testTag("ciacola-presenter-notes-cancel"),
                ) { Text("Cancel") }
            }
            Tooltip(tooltip = { Text("Save notes to the deck file (⌘↵)") }) {
                DefaultButton(
                    onClick = { save() },
                    modifier = Modifier.testTag("ciacola-presenter-notes-save"),
                ) { Text("Save") }
            }
        }
    }
}
