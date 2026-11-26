package dev.sebastiano.ciacola.presenter

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import dev.bnorm.storyboard.AdvanceDirection
import dev.bnorm.storyboard.Storyboard
import dev.bnorm.storyboard.easel.Animatic
import dev.sebastiano.ciacola.document.DeckDocument
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Full-stage blackout or whiteout curtain for live presenting pauses. */
enum class StageCurtain {
    None,
    Blackout,
    Whiteout,
}

/** Which window currently hosts the Quick Slide Jump dialog (`Cmd+G`). */
enum class JumpDialogHost {
    Story,
    Presenter,
}

/** Talk pacing status derived from elapsed talk time, target duration, and deck progress. */
enum class PaceStatus(val label: String) {
    OnPace("On pace"),
    Tight("Tight"),
    Over("Over"),
}

/** Run state of the presenter elapsed talk timer. */
enum class TimerRunState {
    Stopped,
    Running,
    Paused,
}

/** Snapshot of pre-show readiness hazards before entering a live talk. */
data class ReadinessStatus(
    val studioOpen: Boolean,
    val inspectOpen: Boolean,
    val openAuthoringTools: List<String> = emptyList(),
) {
    val hasAnyHazard: Boolean
        get() = studioOpen || inspectOpen || openAuthoringTools.isNotEmpty()

    val hasClosableAuthoringTools: Boolean
        get() = studioOpen || inspectOpen || openAuthoringTools.isNotEmpty()
}

/** Document address of one beat's speaker notes (`slides[slideId].beats[beatIndex].notes`). */
data class SpeakerNoteTarget(
    val slideId: String,
    val beatIndex: Int,
    /**
     * `DeckDocument.id` the target belongs to. A non-empty id must match `doc.id` exactly before a
     * draft is written; an empty id (synthetic/legacy targets) skips the deck-id check.
     */
    val deckId: String = "",
)

/**
 * Elapsed talk timer and pace calculator decoupled from UI composition.
 */
@Stable
class PresenterTimerState(
    private val clockMillis: () -> Long = System::currentTimeMillis,
) {
    var runState: TimerRunState by mutableStateOf(TimerRunState.Stopped)
        private set

    private var accumulatedBeforeResumeMs by mutableLongStateOf(0L)
    private var startedAtMs by mutableLongStateOf(0L)

    var targetDurationMinutes: Int by mutableIntStateOf(DEFAULT_TARGET_MINUTES)
        private set

    fun elapsedSeconds(nowMs: Long = clockMillis()): Long = when (runState) {
        TimerRunState.Stopped -> 0L
        TimerRunState.Paused -> (accumulatedBeforeResumeMs / 1000L).coerceAtLeast(0L)
        TimerRunState.Running -> ((accumulatedBeforeResumeMs + (nowMs - startedAtMs).coerceAtLeast(0L)) / 1000L)
    }

    fun togglePlayPause() {
        val now = clockMillis()
        runState = when (runState) {
            TimerRunState.Stopped -> {
                accumulatedBeforeResumeMs = 0L
                startedAtMs = now
                TimerRunState.Running
            }

            TimerRunState.Running -> {
                accumulatedBeforeResumeMs += (now - startedAtMs).coerceAtLeast(0L)
                TimerRunState.Paused
            }

            TimerRunState.Paused -> {
                startedAtMs = now
                TimerRunState.Running
            }
        }
    }

    fun reset() {
        accumulatedBeforeResumeMs = 0L
        when (runState) {
            TimerRunState.Running -> startedAtMs = clockMillis()
            TimerRunState.Paused, TimerRunState.Stopped -> runState = TimerRunState.Stopped
        }
    }

    fun adjustTargetMinutes(deltaMinutes: Int) {
        targetDurationMinutes = (targetDurationMinutes + deltaMinutes)
            .coerceIn(MIN_TARGET_MINUTES, MAX_TARGET_MINUTES)
    }

    fun paceStatus(elapsedSeconds: Long, flatBeatIndex: Int, totalBeats: Int): PaceStatus {
        val targetSeconds = (targetDurationMinutes * 60L).coerceAtLeast(60L)
        val remainingSeconds = targetSeconds - elapsedSeconds
        if (remainingSeconds < 0L) return PaceStatus.Over
        if (elapsedSeconds == 0L) return PaceStatus.OnPace

        val elapsedFraction = elapsedSeconds.toFloat() / targetSeconds.toFloat()
        val deckFraction = if (totalBeats <= 1) 1f else flatBeatIndex.toFloat() / (totalBeats - 1).toFloat()
        val aheadOfScheduleTime = (elapsedFraction - deckFraction) > 0.15f
        val tightRemaining = remainingSeconds <= 180L &&
            flatBeatIndex < totalBeats - 1 &&
            (remainingSeconds.toFloat() / targetSeconds.toFloat()) < 0.20f
        return if (aheadOfScheduleTime || tightRemaining) PaceStatus.Tight else PaceStatus.OnPace
    }

    companion object {
        const val DEFAULT_TARGET_MINUTES = 35
        const val MIN_TARGET_MINUTES = 5
        const val MAX_TARGET_MINUTES = 180
        const val TARGET_STEP_MINUTES = 5

        fun formatMmSs(totalSeconds: Long): String {
            val safe = totalSeconds.coerceAtLeast(0L)
            val mins = safe / 60L
            val secs = safe % 60L
            return "${mins.toString().padStart(2, '0')}:${secs.toString().padStart(2, '0')}"
        }

        fun formatRemaining(elapsedSeconds: Long, targetMinutes: Int): String {
            val remaining = targetMinutes * 60L - elapsedSeconds
            return if (remaining >= 0L) {
                "${formatMmSs(remaining)} left"
            } else {
                "+${formatMmSs(-remaining)} over"
            }
        }
    }
}

/**
 * Presenter/coordinator for Ciacola's `F2` Presenter View, live stage curtain (`B`/`W`),
 * quick slide jump (`Cmd+G`), display routing, and pre-show readiness checks.
 */
@Stable
class PresenterController(
    initialAnimatic: Animatic,
    internal var speakerNotesProvider: (Storyboard) -> DeckSpeakerNotes? = {
        StoryboardSpeakerNotesRegistry.forStoryboard(it)
    },
    internal var isStudioOpenProvider: () -> Boolean = { false },
    internal var isStageAuthoringChromeOpenProvider: (() -> Boolean)? = null,
    internal var isInspectOpenProvider: () -> Boolean = { false },
    internal var openAuthoringToolsProvider: () -> List<String> = { emptyList() },
    internal var onEnterCleanShowModeAction: () -> Unit = {},
    internal var isExternalTextInputActiveProvider: () -> Boolean = { false },
    internal var onRestoreStageFocus: () -> Unit = {},
    internal var previewStoryboardProvider: (() -> Storyboard)? = null,
    internal var nextPreviewStoryboardProvider: (() -> Storyboard)? = null,
    internal var documentProvider: (() -> DeckDocument?)? = null,
    /**
     * Persists an inline speaker-notes edit into `beats[beatIndex].notes` of slide `slideId`
     * (`null` clears the note). When `null`, notes are read-only in Presenter View.
     */
    internal var onSaveSpeakerNote: ((slideId: String, beatIndex: Int, note: String?) -> Unit)? = null,
    /** `true` while notes editing is temporarily unavailable (e.g. a deck switch/quit is in flight). */
    internal var notesEditingBlockedProvider: () -> Boolean = { false },
    private val displayRouter: DisplayRoutingAdapter = AwtDisplayRouter(),
    timerClockMillis: () -> Long = System::currentTimeMillis,
    private val wallClockProvider: () -> String = { LocalTime.now().format(WALL_CLOCK_FORMATTER) },
) {
    var animatic: Animatic by mutableStateOf(initialAnimatic)
        internal set

    val timer = PresenterTimerState(timerClockMillis)

    var curtain: StageCurtain by mutableStateOf(StageCurtain.None)
        internal set

    var notesFontSizeSp: Float by mutableFloatStateOf(DEFAULT_NOTES_FONT_SP)
        internal set

    var jumpDialogHost: JumpDialogHost? by mutableStateOf(null)
        internal set

    val jumpDialogOpen: Boolean
        get() = jumpDialogHost != null

    var isOverviewOpen: Boolean by mutableStateOf(false)
        internal set

    /** `true` while the speaker is typing notes inline (navigation shortcuts are suspended). */
    var isEditingNotes: Boolean by mutableStateOf(false)
        private set

    /** `(slideId, beatIndex)` captured when inline notes editing started. */
    var editingNoteTarget: SpeakerNoteTarget? by mutableStateOf(null)
        private set

    /** Incremented per [beginEditingNotes] so reopening the same target starts a fresh editor session. */
    var editingSessionId: Long by mutableLongStateOf(0L)
        private set

    /** Normalized stored note captured by [beginEditingNotes]; drafts equal to it are not written. */
    private var editingNoteInitial: String? = null

    /** Fallback draft for non-UI callers; the live editor registers [activeDraftProvider] instead. */
    private var editingNoteDraft: String = ""

    /**
     * Reads the live editor text synchronously (registered by the notes editor while composed), so
     * auto-commit on navigation never lags a frame behind the latest keystroke.
     */
    internal var activeDraftProvider: (() -> String)? = null

    val isExternalTextInputActive: Boolean
        get() = isExternalTextInputActiveProvider()

    var availableDisplays: List<DisplayScreenInfo> by mutableStateOf(displayRouter.screens())
        private set

    internal var swallowNextEscapeKeyUp: Boolean by mutableStateOf(false)

    private var advanceJob: Job? = null

    val currentIndex: Storyboard.Index
        get() = animatic.targetIndex

    val slideCount: Int
        get() = animatic.storyboard.scenes.size

    val currentSceneIndex: Int
        get() = currentIndex.sceneIndex.coerceIn(0, (slideCount - 1).coerceAtLeast(0))

    val currentBeatIndex: Int
        get() = currentIndex.frameIndex.coerceAtLeast(0)

    val currentBeatCount: Int
        get() = animatic.storyboard.scenes.getOrNull(currentSceneIndex)?.frames?.size?.coerceAtLeast(1) ?: 1

    val isTransitioning: Boolean
        get() {
            val inFlightFraction = animatic.activeFraction in 0.001f..0.999f &&
                animatic.activeFromStateIndex != animatic.activeToStateIndex
            return animatic.currentIndex != animatic.targetIndex || inFlightFraction
        }

    val transitionProgress: Float
        get() = if (isTransitioning) {
            animatic.activeFraction.coerceIn(0f, 1f)
        } else {
            1f
        }

    val canStepBackward: Boolean
        get() = animatic.storyboard.indices.firstOrNull()?.let { currentIndex > it } == true

    val canStepForward: Boolean
        get() = animatic.storyboard.indices.lastOrNull()?.let { currentIndex < it } == true

    val nextIndex: Storyboard.Index?
        get() {
            val indices = animatic.storyboard.indices
            val pos = indices.binarySearch(currentIndex)
            return if (pos >= 0) indices.getOrNull(pos + 1) else null
        }

    /**
     * Effective next step for the Presenter "Next" preview card: skips transient
     * `autoAdvanceMs == 0` bridge beats (and duplicate landing beat 0 immediately after a
     * `0 ms` bridge beat) so the Next card always shows the next visually distinct speaker step.
     */
    val nextPreviewIndex: Storyboard.Index?
        get() {
            val indices = animatic.storyboard.indices
            val pos = indices.binarySearch(currentIndex)
            if (pos < 0 || pos + 1 >= indices.size) return null

            val nonSkipped = documentProvider?.invoke()?.slides?.filterNot { it.skipped }
                ?: return indices[pos + 1]

            fun beatAutoAdvanceMs(idx: Storyboard.Index): Int? =
                nonSkipped.getOrNull(idx.sceneIndex)?.beats?.getOrNull(idx.frameIndex)?.autoAdvanceMs

            var candidatePos = pos + 1
            // If currently parked directly on a 0ms bridge beat, the immediate next slide's beat 0
            // visually mirrors the bridge; advance past it if another beat/slide follows.
            if (beatAutoAdvanceMs(currentIndex) == 0 &&
                candidatePos + 1 < indices.size &&
                indices[candidatePos].sceneIndex != currentIndex.sceneIndex &&
                indices[candidatePos].frameIndex == 0
            ) {
                candidatePos += 1
            }

            // Skip over any 0ms auto-advance bridge beats along the forward path to show the true
            // resting beat/slide the presenter will land on after advancing.
            while (candidatePos + 1 < indices.size && beatAutoAdvanceMs(indices[candidatePos]) == 0) {
                candidatePos += 1
            }
            return indices[candidatePos]
        }

    val flatBeatIndex: Int
        get() = animatic.storyboard.indices.binarySearch(currentIndex).coerceAtLeast(0)

    val totalFlatBeats: Int
        get() = animatic.storyboard.indices.size.coerceAtLeast(1)

    fun currentSpeakerNotes(): DeckSpeakerNotes =
        speakerNotesProvider(animatic.storyboard) ?: DeckSpeakerNotes.Empty

    fun resolvedCurrentNotes(): ResolvedSpeakerNotes =
        currentSpeakerNotes().resolve(animatic.storyboard, currentIndex)

    fun currentSlideTitle(): String =
        humanizePresenterSlideTitle(resolvedCurrentNotes().sceneTitle, currentSceneIndex)

    fun nextStepLabel(): String {
        val next = nextPreviewIndex ?: return "End of Deck"
        return if (next.sceneIndex == currentSceneIndex) {
            "Next build (${next.frameIndex + 1} of $currentBeatCount)"
        } else {
            val rawTitle = currentSpeakerNotes().sceneTitle(next.sceneIndex)
            val cleanTitle = humanizePresenterSlideTitle(rawTitle, next.sceneIndex)
            val num = next.sceneIndex + 1
            if (cleanTitle.equals("Slide $num", ignoreCase = true) || cleanTitle.isBlank()) {
                "Next Slide (#$num)"
            } else {
                "Next Slide (#$num) · $cleanTitle"
            }
        }
    }

    /** Returns `(oneBasedNumber, title)` entries for every non-skipped slide in the deck. */
    fun slideJumpEntries(): List<Pair<Int, String>> {
        val notes = currentSpeakerNotes()
        val nonSkipped = documentProvider?.invoke()?.slides?.filterNot { it.skipped }
        return (0 until slideCount).map { sceneIndex ->
            val docTitle = nonSkipped?.getOrNull(sceneIndex)?.title.orEmpty()
            val rawTitle = notes.sceneTitle(sceneIndex).ifBlank { docTitle }
            (sceneIndex + 1) to humanizePresenterSlideTitle(rawTitle, sceneIndex)
        }
    }

    fun wallClockText(): String = wallClockProvider()

    /** The `(slideId, beatIndex)` the current slide/beat's notes live at in the document. */
    fun currentNoteTarget(): SpeakerNoteTarget? {
        val doc = documentProvider?.invoke() ?: return null
        val slide = doc.slides.filterNot { it.skipped }.getOrNull(currentSceneIndex) ?: return null
        if (slide.beats.isEmpty()) return null
        return SpeakerNoteTarget(
            slideId = slide.id,
            beatIndex = currentBeatIndex.coerceIn(0, slide.beats.lastIndex),
            deckId = doc.id,
        )
    }

    /** `true` when inline notes editing is available for the current slide. */
    val canEditNotes: Boolean
        get() = onSaveSpeakerNote != null && !notesEditingBlockedProvider() && currentNoteTarget() != null

    /** `true` when this deck's notes can never be edited here (as opposed to temporarily blocked). */
    val isNotesReadOnly: Boolean
        get() = onSaveSpeakerNote == null

    /** The raw note stored at [target] (empty when unset). */
    fun storedNote(target: SpeakerNoteTarget?): String {
        val t = target ?: return ""
        val doc = documentProvider?.invoke() ?: return ""
        return doc.slides.firstOrNull { it.id == t.slideId }?.beats?.getOrNull(t.beatIndex)?.notes.orEmpty()
    }

    /** Starts inline editing of the current slide/beat's notes. Returns `false` when unavailable. */
    fun beginEditingNotes(): Boolean {
        if (onSaveSpeakerNote == null || notesEditingBlockedProvider()) return false
        val target = currentNoteTarget() ?: return false
        val stored = storedNote(target)
        editingNoteInitial = stored.normalizedNote()
        editingNoteDraft = stored
        editingNoteTarget = target
        editingSessionId++
        isEditingNotes = true
        return true
    }

    /** Updates the draft for callers without a live editor (the editor's text wins when composed). */
    fun updateEditingNoteDraft(text: String) {
        editingNoteDraft = text
    }

    private fun currentEditingDraft(): String = activeDraftProvider?.invoke() ?: editingNoteDraft

    private fun clearEditingState() {
        isEditingNotes = false
        editingNoteTarget = null
        activeDraftProvider = null
        editingNoteInitial = null
        editingNoteDraft = ""
    }

    /** Discards the inline notes draft (explicit `Esc` / `Cancel`). */
    fun cancelEditingNotes() {
        clearEditingState()
    }

    /**
     * Closes an open inline editor, writing the draft back to the note it was opened on when it
     * changed and that note still exists in the same deck. Unchanged drafts add no history entry,
     * and a draft is never written into a different deck or a slide/build that disappeared.
     * Returns `true` when an open editor was closed.
     */
    fun commitEditingNotesIfDirty(): Boolean {
        if (!isEditingNotes) return false
        val target = editingNoteTarget
        val draft = currentEditingDraft()
        val initial = editingNoteInitial
        val save = onSaveSpeakerNote
        clearEditingState()
        if (target == null || save == null) return true
        val doc = documentProvider?.invoke() ?: return true
        if (target.deckId.isNotEmpty() && doc.id != target.deckId) return true
        val beatExists = doc.slides.firstOrNull { it.id == target.slideId }?.beats?.getOrNull(target.beatIndex) != null
        if (!beatExists) return true
        val normalized = draft.normalizedNote()
        if (normalized != initial) save(target.slideId, target.beatIndex, normalized)
        return true
    }

    /**
     * Closes the inline editor when the presented slide/build moved away from the note being edited
     * (Story-window navigation, deck switch), auto-committing a changed draft to the note it was
     * opened on (see [commitEditingNotesIfDirty]). Returns `true` when the editor was closed.
     */
    fun commitOrCancelEditingNotesIfTargetMoved(): Boolean {
        if (!isEditingNotes) return false
        if (currentNoteTarget() == editingNoteTarget) return false
        return commitEditingNotesIfDirty()
    }

    /** Saves [text] (blank clears the note) to the target captured by [beginEditingNotes]. */
    fun commitEditingNotes(text: String): Boolean {
        val target = editingNoteTarget
        val save = onSaveSpeakerNote
        clearEditingState()
        if (target == null || save == null) return false
        save(target.slideId, target.beatIndex, text.normalizedNote())
        return true
    }

    fun readinessStatus(): ReadinessStatus = ReadinessStatus(
        studioOpen = isStudioOpenProvider(),
        inspectOpen = isInspectOpenProvider(),
        openAuthoringTools = openAuthoringToolsProvider(),
    )

    fun enterCleanShowMode() {
        onEnterCleanShowModeAction()
    }

    fun isShowModeActive(): Boolean =
        !(isStageAuthoringChromeOpenProvider?.invoke() ?: isStudioOpenProvider()) &&
            !isInspectOpenProvider() &&
            openAuthoringToolsProvider().isEmpty() &&
            !isOverviewOpen &&
            !isExternalTextInputActive &&
            !jumpDialogOpen

    fun refreshDisplays(): List<DisplayScreenInfo> {
        val screens = displayRouter.screens()
        availableDisplays = screens
        return screens
    }

    fun presentOnDisplay(screen: DisplayScreenInfo): Boolean =
        displayRouter.presentStoryOn(screen)

    fun swapAudienceAndPresenterDisplays(): Boolean =
        displayRouter.swapAudienceAndPresenter()

    fun step(scope: CoroutineScope, direction: AdvanceDirection) {
        if (isEditingNotes) commitEditingNotesIfDirty()
        if (curtain != StageCurtain.None) {
            curtain = StageCurtain.None
        }
        advanceJob?.cancel()
        advanceJob = scope.launch {
            animatic.advance(direction)
            advanceJob = null
        }
    }

    fun jumpToSlide(scope: CoroutineScope, oneBasedSlide: Int): Boolean {
        val targetScene = oneBasedSlide - 1
        if (targetScene !in 0 until slideCount) return false
        val targetIdx = Storyboard.Index(targetScene, 0)
        if (targetIdx !in animatic.storyboard.indices) return false
        if (isEditingNotes) commitEditingNotesIfDirty()
        val host = jumpDialogHost
        curtain = StageCurtain.None
        jumpDialogHost = null
        advanceJob?.cancel()
        advanceJob = scope.launch {
            animatic.jumpTo(targetIdx)
            advanceJob = null
        }
        if (host == JumpDialogHost.Story) {
            onRestoreStageFocus()
        }
        return true
    }

    fun toggleCurtain(target: StageCurtain) {
        curtain = if (curtain == target) StageCurtain.None else target
    }

    fun clearCurtain() {
        curtain = StageCurtain.None
    }

    fun openJumpDialog(host: JumpDialogHost = JumpDialogHost.Story) {
        jumpDialogHost = host
    }

    fun closeJumpDialog(fromEscapeKey: Boolean = false) {
        val host = jumpDialogHost
        if (fromEscapeKey && host == JumpDialogHost.Story) {
            swallowNextEscapeKeyUp = true
        }
        jumpDialogHost = null
        if (host == JumpDialogHost.Story) {
            onRestoreStageFocus()
        }
    }

    fun increaseNotesFontSize() {
        notesFontSizeSp = (notesFontSizeSp + NOTES_FONT_STEP_SP).coerceIn(MIN_NOTES_FONT_SP, MAX_NOTES_FONT_SP)
    }

    fun decreaseNotesFontSize() {
        notesFontSizeSp = (notesFontSizeSp - NOTES_FONT_STEP_SP).coerceIn(MIN_NOTES_FONT_SP, MAX_NOTES_FONT_SP)
    }

    /**
     * Preview key handler installed at the Story window root: manages `B` / `W` blackout/whiteout,
     * `Esc` curtain/dialog dismissal without leaking KeyUp to `EaselOverview`, and `Cmd+G` jump.
     */
    fun handleStoryKeyEvent(event: KeyEvent): Boolean {
        if (event.key == Key.Escape) {
            if (swallowNextEscapeKeyUp) {
                if (event.type == KeyEventType.KeyUp) {
                    swallowNextEscapeKeyUp = false
                }
                return true
            }
            if (jumpDialogOpen) {
                if (event.type == KeyEventType.KeyDown) {
                    closeJumpDialog(fromEscapeKey = true)
                }
                return true
            }
            if (curtain != StageCurtain.None) {
                if (event.type == KeyEventType.KeyDown) {
                    curtain = StageCurtain.None
                    swallowNextEscapeKeyUp = true
                }
                return true
            }
            return false
        }

        if (event.type != KeyEventType.KeyDown) return false

        val primaryMod = event.isMetaPressed || event.isCtrlPressed
        if (primaryMod && !event.isAltPressed && !event.isShiftPressed && (event.key == Key.G || event.key == Key.K)) {
            if (!isExternalTextInputActive && !isOverviewOpen) {
                openJumpDialog(JumpDialogHost.Story)
                return true
            }
            return false
        }

        if (jumpDialogOpen || !isShowModeActive()) return false

        val noModifiers = !event.isCtrlPressed && !event.isMetaPressed && !event.isAltPressed && !event.isShiftPressed
        if (!noModifiers) return false

        when (event.key) {
            Key.B -> {
                toggleCurtain(StageCurtain.Blackout)
                return true
            }

            Key.W -> {
                toggleCurtain(StageCurtain.Whiteout)
                return true
            }

            Key.DirectionRight,
            Key.DirectionDown,
            Key.PageDown,
            Key.Enter,
            Key.NumPadEnter,
            Key.Spacebar,
            Key.DirectionLeft,
            Key.DirectionUp,
            Key.PageUp,
            Key.Backspace,
            -> {
                if (curtain != StageCurtain.None) {
                    curtain = StageCurtain.None
                }
                return false
            }
        }
        return false
    }

    /**
     * Capture-phase key handler on the Presenter window root: handles `Escape` dismissal and
     * modifier chords (`Cmd+G`, `Cmd+-`, `Cmd+=`) without intercepting `Space` or `Enter` before
     * focused child buttons receive them.
     */
    fun handlePresenterPreviewKeyEvent(event: KeyEvent): Boolean {
        // While typing notes, every key (Space, arrows, B, W, Esc, ⌘G…) belongs to the notes field.
        if (isEditingNotes) return false
        if (event.key == Key.Escape) {
            if (jumpDialogOpen) {
                if (event.type == KeyEventType.KeyDown) {
                    closeJumpDialog(fromEscapeKey = false)
                }
                return true
            }
            if (curtain != StageCurtain.None) {
                if (event.type == KeyEventType.KeyDown) {
                    curtain = StageCurtain.None
                }
                return true
            }
            return false
        }

        if (event.type != KeyEventType.KeyDown) return false

        val primaryMod = event.isMetaPressed || event.isCtrlPressed
        if (primaryMod && !event.isAltPressed) {
            when (event.key) {
                Key.G, Key.K -> if (!event.isShiftPressed) {
                    openJumpDialog(JumpDialogHost.Presenter)
                    return true
                }

                Key.Minus, Key.NumPadSubtract -> {
                    decreaseNotesFontSize()
                    return true
                }

                Key.Equals, Key.Plus, Key.NumPadAdd -> {
                    increaseNotesFontSize()
                    return true
                }
            }
        }
        return false
    }

    /**
     * Bubble-phase key handler on the Presenter window root: runs after focused child buttons had
     * first crack at `Space` / `Enter`, handling timer (`T`/`R`), curtains (`B`/`W`), and smooth
     * deck navigation.
     */
    fun handlePresenterBubbleKeyEvent(event: KeyEvent, scope: CoroutineScope): Boolean {
        if (event.type != KeyEventType.KeyDown || jumpDialogOpen || isEditingNotes) return false

        val noModifiers = !event.isCtrlPressed && !event.isMetaPressed && !event.isAltPressed && !event.isShiftPressed
        if (!noModifiers) return false

        return when (event.key) {
            Key.T -> {
                timer.togglePlayPause()
                true
            }

            Key.R -> {
                timer.reset()
                true
            }

            Key.B -> {
                if (isShowModeActive()) {
                    toggleCurtain(StageCurtain.Blackout)
                    true
                } else {
                    false
                }
            }

            Key.W -> {
                if (isShowModeActive()) {
                    toggleCurtain(StageCurtain.Whiteout)
                    true
                } else {
                    false
                }
            }

            Key.DirectionRight,
            Key.DirectionDown,
            Key.PageDown,
            Key.Enter,
            Key.NumPadEnter,
            Key.Spacebar,
            -> {
                step(scope, AdvanceDirection.Forward)
                true
            }

            Key.DirectionLeft,
            Key.DirectionUp,
            Key.PageUp,
            Key.Backspace,
            -> {
                step(scope, AdvanceDirection.Backward)
                true
            }

            else -> false
        }
    }

    companion object {
        const val DEFAULT_NOTES_FONT_SP = 20f
        const val MIN_NOTES_FONT_SP = 14f
        const val MAX_NOTES_FONT_SP = 36f
        const val NOTES_FONT_STEP_SP = 2f

        private val WALL_CLOCK_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
        private val RAW_SPECIMEN_KEY_REGEX = Regex("^[a-z][a-z0-9_-]*\\.([A-Za-z][A-Za-z0-9_]*)$")

        /** Strips redundant `Slide N · ` prefixes and humanizes raw `prefix.camelCase` specimen keys. */
        fun humanizePresenterSlideTitle(rawTitle: String, sceneIndex: Int): String {
            val trimmed = rawTitle.trim()
            val oneBased = sceneIndex + 1
            if (trimmed.isEmpty()) return "Slide $oneBased"
            val prefixRegex = Regex("^Slide\\s+0?$oneBased\\s*(?:[·:\\-–—]+\\s*)?", RegexOption.IGNORE_CASE)
            val stripped = trimmed.replace(prefixRegex, "").trim()
            if (stripped.isEmpty()) return "Slide $oneBased"
            val match = RAW_SPECIMEN_KEY_REGEX.matchEntire(stripped)
            if (match != null) {
                val spaced = match.groupValues[1]
                    .replace(Regex("([a-z])([A-Z])"), "$1 $2")
                    .replace(Regex("([A-Za-z])([0-9])"), "$1 $2")
                    .replace('_', ' ')
                    .trim()
                return spaced.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
            }
            return stripped
        }
    }
}

private fun String.normalizedNote(): String? = trimEnd().takeIf { it.isNotBlank() }
