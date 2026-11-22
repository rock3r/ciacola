package dev.sebastiano.ciacola.document.render

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.bnorm.storyboard.AdvanceDirection
import dev.bnorm.storyboard.ContentDecorator
import dev.bnorm.storyboard.Storyboard
import dev.bnorm.storyboard.easel.Animatic
import dev.sebastiano.ciacola.document.AutoAdvanceController
import dev.sebastiano.ciacola.document.DeckDocument
import dev.sebastiano.ciacola.document.DeckSpecimenRegistry
import dev.sebastiano.ciacola.document.PaddingSpec
import dev.sebastiano.ciacola.document.RenderSession
import dev.sebastiano.ciacola.presenter.DeckSpeakerNotes
import dev.sebastiano.ciacola.presenter.SceneSpeakerNotes
import kotlin.time.Duration
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Runtime rendering configuration for a `DeckDocument` mounted via [buildDocumentStoryboard] or
 * [rememberDocumentStoryboard].
 */
@Immutable
data class DocumentRenderConfig(
    val themeName: String? = null,
    val specimens: DeckSpecimenRegistry = DeckSpecimenRegistry.Empty,
    val reducedMotion: Boolean? = null,
    val assetsRootDir: String? = null,
    val fontsDir: String? = null,
    val defaultHoldMs: Long = 400L,
    val fontScale: Float = 1f,
    val baseDensity: Float = 2f,
    val isEditMode: Boolean = false,
    val keyboardController: DocumentKeyboardCaptureController? = null,
    val onNodeClick: ((slideId: String, nodeId: String) -> Unit)? = null,
    val onNodeDoubleClick: ((slideId: String, nodeId: String) -> Unit)? = null,
    val stageDecorator: ContentDecorator = ContentDecorator.None,
)

/** Active [DocumentRenderConfig] for the current document mount. */
val LocalDocumentRenderConfig = compositionLocalOf { DocumentRenderConfig() }

/** Active [DocumentKeyboardCaptureController] for the current document mount, if any. */
val LocalDocumentKeyboardController = compositionLocalOf<DocumentKeyboardCaptureController?> { null }

/** Converts a value in 1920x1080 document units into stage [Dp] under [density]. */
fun Double.toStageDp(density: Density): Dp = (this / density.density.toDouble()).dp

/** Converts a value in 1920x1080 document units into stage [Dp] under [density]. */
fun Float.toStageDp(density: Density): Dp = (this / density.density).dp

/** Converts a value in 1920x1080 document units into stage [TextUnit] (`sp`) under [density]. */
fun Double.toStageSp(density: Density): TextUnit = (this / density.density.toDouble()).sp

/** Converts a value in 1920x1080 document units into stage [TextUnit] (`sp`) under [density]. */
fun Float.toStageSp(density: Density): TextUnit = (this / density.density).sp

/** Converts a [PaddingSpec] in document units into stage [PaddingValues] under [density]. */
fun PaddingSpec.toPaddingValues(density: Density): PaddingValues = PaddingValues(
    start = left.toStageDp(density),
    top = top.toStageDp(density),
    end = right.toStageDp(density),
    bottom = bottom.toStageDp(density),
)

/** Combines two optional [PaddingSpec]s by summing each edge. */
fun combinePadding(a: PaddingSpec?, b: PaddingSpec?): PaddingSpec = PaddingSpec(
    left = (a?.left ?: 0.0) + (b?.left ?: 0.0),
    top = (a?.top ?: 0.0) + (b?.top ?: 0.0),
    right = (a?.right ?: 0.0) + (b?.right ?: 0.0),
    bottom = (a?.bottom ?: 0.0) + (b?.bottom ?: 0.0),
)

/**
 * Builds [DeckSpeakerNotes] from all non-skipped slides in [document], mapping each beat's `notes`
 * field to its 0-based beat index.
 */
fun buildDocumentSpeakerNotes(document: DeckDocument): DeckSpeakerNotes {
    val nonSkipped = document.slides.filterNot { it.skipped }
    if (nonSkipped.isEmpty()) return DeckSpeakerNotes.Empty
    val scenes = LinkedHashMap<Int, SceneSpeakerNotes>(nonSkipped.size)
    nonSkipped.forEachIndexed { sceneIdx, slide ->
        val beatNotes = LinkedHashMap<Int, String>()
        slide.beats.forEachIndexed { beatIdx, beat ->
            val text = beat.notes?.trim()
            if (!text.isNullOrEmpty()) {
                beatNotes[beatIdx] = text
            }
        }
        scenes[sceneIdx] = SceneSpeakerNotes(
            sceneIndex = sceneIdx,
            title = slide.title?.takeIf { it.isNotBlank() } ?: slide.id,
            slideNotes = beatNotes[0].orEmpty(),
            beatNotes = beatNotes,
        )
    }
    return DeckSpeakerNotes(scenes = scenes)
}

/**
 * Returns a `holdDurationForState` callback suitable for `StoryboardVideoExporter`.
 * Uses each beat's `autoAdvanceMs` when declared, falling back to [defaultHoldMs].
 */
fun holdDurationForState(
    session: RenderSession,
    defaultHoldMs: Long = 400L,
): (Storyboard.Index) -> Duration = { index ->
    session.holdDurationForState(index, defaultHoldMs = defaultHoldMs)
}

/**
 * Composable bridge connecting an [Animatic] to [AutoAdvanceController].
 *
 * Fires `autoAdvanceMs` strictly when arriving at a resting beat via forward navigation (`next > prev`
 * in storyboard order and immediately adjacent, never on initial mount, backward travel, jump, or
 * scrub) and cancels any pending timer immediately upon any other navigation or disposal.
 */
@Composable
fun AutoAdvanceEffect(
    animatic: Animatic,
    session: RenderSession,
    enabled: Boolean = true,
    isScrubbing: Boolean = false,
    controller: AutoAdvanceController? = null,
) {
    val scope = rememberCoroutineScope()
    val effectiveController = controller ?: remember(scope, animatic) {
        AutoAdvanceController(
            scope = scope,
            onAdvanceForward = {
                animatic.advance(AdvanceDirection.Forward)
            },
        )
    }

    val latestScrubbing by rememberUpdatedState(isScrubbing)

    SideEffect {
        effectiveController.isScrubbing = isScrubbing || !enabled
    }

    DisposableEffect(effectiveController) {
        onDispose {
            effectiveController.cancelPending()
        }
    }

    var previousRest by remember(animatic) { mutableStateOf<Storyboard.Index?>(null) }
    var lastScrubEpoch by remember(animatic) { mutableStateOf(animatic.scrubEpoch) }
    var sawForwardPlayback by remember(animatic) { mutableStateOf(false) }
    var sawNonForwardOrJump by remember(animatic) { mutableStateOf(false) }
    var prevLiveFraction by remember(animatic) { mutableStateOf(-1f) }

    LaunchedEffect(animatic, session, effectiveController, enabled) {
        if (!enabled) {
            effectiveController.cancelPending()
            return@LaunchedEffect
        }
        snapshotFlow {
            AutoAdvanceObservation(
                cur = animatic.currentIndex,
                tgt = animatic.targetIndex,
                fromIdx = animatic.activeFromStateIndex,
                toIdx = animatic.activeToStateIndex,
                settling = animatic.scrubSettling,
                scrubEpoch = animatic.scrubEpoch,
                activeFraction = animatic.activeFraction,
                scrubbing = latestScrubbing,
            )
        }
            .distinctUntilChanged()
            .collect { obs ->
                if (obs.scrubbing || obs.scrubEpoch != lastScrubEpoch) {
                    lastScrubEpoch = obs.scrubEpoch
                    sawForwardPlayback = false
                    sawNonForwardOrJump = true
                    prevLiveFraction = -1f
                    effectiveController.onOtherNavigation()
                }
                if (obs.cur != obs.tgt) {
                    effectiveController.cancelPending()
                    if (obs.tgt < obs.cur) {
                        sawForwardPlayback = false
                        sawNonForwardOrJump = true
                        prevLiveFraction = -1f
                    } else if (obs.fromIdx == obs.toIdx || (obs.settling && obs.activeFraction > 0.001f)) {
                        // `jumpTo(...)` snaps directly (`fromIdx == toIdx`) or freezes at a non-zero
                        // fraction; `seekInPlace(seg, f)` yields while `settling` with `activeFraction == f`.
                        sawForwardPlayback = false
                        sawNonForwardOrJump = true
                        prevLiveFraction = -1f
                    } else if (obs.toIdx > obs.fromIdx && !sawNonForwardOrJump) {
                        if (!obs.settling && prevLiveFraction >= 0f && obs.activeFraction < prevLiveFraction - 0.001f) {
                            // Mid-flight backward drag or reversal within the active segment.
                            sawForwardPlayback = false
                            sawNonForwardOrJump = true
                        } else {
                            sawForwardPlayback = true
                            if (!obs.settling) {
                                prevLiveFraction = obs.activeFraction
                            }
                        }
                    }
                } else {
                    val prev = previousRest
                    previousRest = obs.cur
                    val arrivedForward = prev != null &&
                        obs.cur > prev &&
                        sawForwardPlayback &&
                        !sawNonForwardOrJump &&
                        !obs.scrubbing &&
                        isImmediateForwardStep(session.document, prev, obs.cur)
                    sawForwardPlayback = false
                    sawNonForwardOrJump = false
                    prevLiveFraction = -1f
                    if (arrivedForward) {
                        val nonSkipped = session.document.slides.filterNot { it.skipped }
                        val slide = nonSkipped.getOrNull(obs.cur.sceneIndex)
                        val autoMs = slide?.beats?.getOrNull(obs.cur.frameIndex)?.autoAdvanceMs
                        if (autoMs != null) {
                            effectiveController.onForwardAdvanceArrived(autoMs)
                        } else {
                            effectiveController.cancelPending()
                        }
                    } else {
                        effectiveController.cancelPending()
                    }
                }
            }
    }
}

private data class AutoAdvanceObservation(
    val cur: Storyboard.Index,
    val tgt: Storyboard.Index,
    val fromIdx: Int,
    val toIdx: Int,
    val settling: Boolean,
    val scrubEpoch: Int,
    val activeFraction: Float,
    val scrubbing: Boolean,
)

private fun isImmediateForwardStep(
    document: DeckDocument,
    prev: Storyboard.Index,
    cur: Storyboard.Index,
): Boolean {
    if (cur.sceneIndex == prev.sceneIndex) {
        return cur.frameIndex == prev.frameIndex + 1
    }
    if (cur.sceneIndex == prev.sceneIndex + 1 && cur.frameIndex == 0) {
        val nonSkipped = document.slides.filterNot { it.skipped }
        val prevSlide = nonSkipped.getOrNull(prev.sceneIndex) ?: return false
        val prevLastBeat = prevSlide.beats.lastIndex.coerceAtLeast(0)
        return prev.frameIndex >= prevLastBeat
    }
    return false
}
