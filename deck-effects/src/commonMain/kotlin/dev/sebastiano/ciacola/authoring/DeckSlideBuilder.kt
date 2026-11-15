package dev.sebastiano.ciacola.authoring

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.Transition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.createChildTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import dev.bnorm.storyboard.Frame
import dev.bnorm.storyboard.SceneEnterTransition
import dev.bnorm.storyboard.SceneExitTransition
import dev.bnorm.storyboard.SceneFormat
import dev.bnorm.storyboard.SceneScope
import dev.bnorm.storyboard.Storyboard
import dev.bnorm.storyboard.StoryboardBuilder
import dev.bnorm.storyboard.easel.LocalSceneMode
import dev.bnorm.storyboard.easel.SceneMode
import dev.bnorm.storyboard.easel.segmentProgressFraction
import dev.bnorm.storyboard.easel.stableSegment
import dev.bnorm.storyboard.toValue
import dev.sebastiano.ciacola.DeckDesign
import dev.sebastiano.ciacola.inspector.LocalActiveBeatIndex
import dev.sebastiano.ciacola.inspector.stageInteractive
import dev.sebastiano.ciacola.presenter.DeckSpeakerNotesBuilder
import dev.sebastiano.ciacola.presenter.SceneSpeakerNotesBuilder
import dev.sebastiano.ciacola.presenter.StoryboardSpeakerNotesRegistry
import dev.sebastiano.ciacola.review.LocalReviewStageBridge
import dev.sebastiano.ciacola.theme.DeckTheme
import dev.sebastiano.ciacola.theme.DesignSystemOverrides
import dev.sebastiano.ciacola.theme.LocalDeckTheme
import dev.sebastiano.ciacola.theme.LocalReducedMotion
import dev.sebastiano.ciacola.theme.REDUCED_MOTION_FADE_DURATION_MS
import dev.sebastiano.ciacola.theme.SlidePalette
import dev.sebastiano.ciacola.theme.withOverrides
import dev.sebastiano.ciacola.timeline.ActiveSceneTimelineContext
import dev.sebastiano.ciacola.timeline.DeckTimelineOverridesFile
import dev.sebastiano.ciacola.timeline.LocalActiveSceneTimeline
import dev.sebastiano.ciacola.timeline.LocalMotionPathPreviewState
import dev.sebastiano.ciacola.timeline.LocalTimelineHarvestCollector
import dev.sebastiano.ciacola.timeline.VirtualizedFrame
import dev.sebastiano.ciacola.timeline.buildVirtualizedFrames

/** Stable identifier of the enclosing [ciacolaDeck] provided to every slide subtree. */
val LocalDeckId = compositionLocalOf<String?> { null }

/** Stable key of the enclosing [DeckBuilder.slide] provided to its content subtree. */
val LocalSlideKey = compositionLocalOf<String?> { null }

/** Registers a choreographed scene with virtualized beats, notes, harvest, and review context. */
fun <T> StoryboardBuilder.deckChoreographedScene(
    sceneIndex: Int,
    sceneTitle: String,
    baseFrames: List<T>,
    theme: DeckTheme,
    overrides: DeckTimelineOverridesFile,
    notesBuilder: DeckSpeakerNotesBuilder? = null,
    reducedMotion: Boolean = false,
    designOverridesProvider: () -> DesignSystemOverrides = { DesignSystemOverrides() },
    enterTransition: SceneEnterTransition? = null,
    exitTransition: SceneExitTransition? = null,
    notes: (SceneSpeakerNotesBuilder.() -> Unit)? = null,
    content: @Composable context(AnimatedVisibilityScope, SharedTransitionScope) SceneScope<VirtualizedFrame<T>>.() -> Unit,
) {
    notesBuilder?.scene(sceneIndex, sceneTitle) { notes?.invoke(this) }
    val vFrames = buildVirtualizedFrames(baseFrames, overrides.sceneBeats[sceneIndex])
    val resolveDissolveMs = {
        val d = theme.withOverrides(designOverridesProvider()).motion.dissolveDurationMs
        if (reducedMotion) minOf(d, REDUCED_MOTION_FADE_DURATION_MS) else d
    }
    scene(
        frames = vFrames,
        enterTransition = enterTransition ?: SceneEnterTransition { fadeIn(tween(resolveDissolveMs())) },
        exitTransition = exitTransition ?: SceneExitTransition { fadeOut(tween(resolveDissolveMs())) },
    ) {
        val ctx = remember(sceneIndex, sceneTitle, vFrames) {
            ActiveSceneTimelineContext(
                sceneIndex = sceneIndex,
                sceneTitle = sceneTitle,
                totalBeats = vFrames.size,
                frameBeatIndexOf = { rawFrame ->
                    when (rawFrame) {
                        is Frame.Start -> 0
                        is Frame.End -> (vFrames.size - 1).coerceAtLeast(0)
                        is Frame.Value<*> -> (rawFrame.value as? VirtualizedFrame<*>)?.virtualBeatIndex ?: 0
                        is VirtualizedFrame<*> -> rawFrame.virtualBeatIndex
                        is Int -> rawFrame
                        else -> 0
                    }
                },
            )
        }
        val previewState = LocalMotionPathPreviewState.current
        if (LocalSceneMode.current == SceneMode.Story) {
            val reviewBridge = LocalReviewStageBridge.current
            SideEffect {
                previewState?.activeStageSceneIndex = sceneIndex
                reviewBridge?.updateSceneTitle(sceneTitle)
            }
        }
        LocalTimelineHarvestCollector.current?.recordSceneTitle(sceneIndex, sceneTitle)
        val activeBeatIndex = transition.targetState.toValue().virtualBeatIndex
        CompositionLocalProvider(
            LocalActiveSceneTimeline provides ctx,
            LocalActiveBeatIndex provides activeBeatIndex,
        ) {
            content()
        }
    }
}

/** Builds a Ciacola [Storyboard] using [DeckBuilder], wiring [deckRootDecorator] and speaker notes. */
fun ciacolaDeck(
    id: String,
    title: String,
    host: DeckHostParams = DeckHostParams(),
    format: SceneFormat = DeckDesign.format,
    build: DeckBuilder.() -> Unit,
): Storyboard = ciacolaDeck(id = id, title = title, description = "", host = host, format = format, build = build)

/** Builds a Ciacola [Storyboard] with an explicit [description] using [DeckBuilder]. */
fun ciacolaDeck(
    id: String,
    title: String,
    description: String,
    host: DeckHostParams = DeckHostParams(),
    format: SceneFormat = DeckDesign.format,
    build: DeckBuilder.() -> Unit,
): Storyboard {
    val notesBuilder = DeckSpeakerNotesBuilder()
    val effectiveOverrides = if (host.timelineOverrides.hasOverrides) {
        host.timelineOverrides
    } else {
        host.timelineOverridesProvider()
    }
    val storyboard = Storyboard.build(
        title = title,
        description = description,
        format = format,
        decorator = deckRootDecorator(host),
    ) {
        DeckBuilder(id, host, effectiveOverrides, notesBuilder, this).build()
    }
    StoryboardSpeakerNotesRegistry.register(storyboard, notesBuilder.build())
    return storyboard
}

/** Marks Ciacola's declarative deck-authoring DSL receivers. */
@DslMarker
annotation class DeckAuthoringDsl

/** Receiver for [ciacolaDeck] registering ordered slides, palettes, and speaker notes. */
@DeckAuthoringDsl
class DeckBuilder internal constructor(
    val deckId: String,
    val host: DeckHostParams,
    private val timelineOverrides: DeckTimelineOverridesFile,
    private val notesBuilder: DeckSpeakerNotesBuilder,
    private val storyboardBuilder: StoryboardBuilder,
) {
    private var nextSceneIndex: Int = 0
    /** Base [DeckTheme] configured on [host]. */
    val theme: DeckTheme get() = host.theme
    /** Whether reduced motion is active for this deck build. */
    val reducedMotion: Boolean get() = host.reducedMotion
    /** Zero-based index that will be assigned to the next registered slide. */
    val currentSceneIndex: Int get() = nextSceneIndex

    /** Convenience helper returning a speaker-notes builder block (`notes { beat(0, "...") }`). */
    fun notes(block: SceneSpeakerNotesBuilder.() -> Unit): SceneSpeakerNotesBuilder.() -> Unit = block

    /** Registers a beat-indexed slide with `0 until beats` frames and optional [palette] & [notes]. */
    fun slide(
        key: String,
        title: String = key,
        beats: Int = 1,
        palette: SlidePalette? = null,
        notes: (SceneSpeakerNotesBuilder.() -> Unit)? = null,
        content: @Composable context(AnimatedVisibilityScope, SharedTransitionScope) SlideScope<Int>.() -> Unit,
    ) {
        require(beats >= 1) { "Slide '$key' must declare at least 1 beat (got $beats)" }
        slide(key = key, title = title, frames = (0 until beats).toList(), palette = palette, notes = notes, content = content)
    }

    /** Registers a typed-frame slide with explicit [frames], [SlideLayers] [palette], and [notes]. */
    fun <T> slide(
        key: String,
        title: String = key,
        frames: List<T>,
        palette: SlidePalette? = null,
        notes: (SceneSpeakerNotesBuilder.() -> Unit)? = null,
        content: @Composable context(AnimatedVisibilityScope, SharedTransitionScope) SlideScope<T>.() -> Unit,
    ) {
        require(frames.isNotEmpty()) { "Slide '$key' frames must not be empty" }
        val sceneIndex = nextSceneIndex++
        val parentDeckId = deckId
        storyboardBuilder.deckChoreographedScene(
            sceneIndex = sceneIndex,
            sceneTitle = title,
            baseFrames = frames,
            theme = host.theme,
            overrides = timelineOverrides,
            notesBuilder = notesBuilder,
            reducedMotion = host.reducedMotion,
            designOverridesProvider = host.designOverridesProvider,
            enterTransition = slideEnterTransition(),
            exitTransition = slideExitTransition(),
            notes = notes,
        ) {
            val sceneScope = this
            val slideScope = remember(sceneScope, key, sceneIndex, frames.size) {
                SlideScope(sceneScope, key, sceneIndex, frames.size)
            }
            CompositionLocalProvider(LocalDeckId provides parentDeckId, LocalSlideKey provides key) {
                SlideLayers(palette = palette) { slideScope.content() }
            }
        }
    }
}

/** Scope receiver inside [DeckBuilder.slide] exposing beat-indexed helpers and [Specimen] slots. */
@Stable
@DeckAuthoringDsl
class SlideScope<T>(
    private val sceneScope: SceneScope<VirtualizedFrame<T>>,
    val slideKey: String,
    val sceneIndex: Int,
    private val baseBeatsCount: Int,
) : SceneScope<VirtualizedFrame<T>> by sceneScope {
    private val presentedVirtualFrameState: State<VirtualizedFrame<T>> = derivedStateOf {
        val (from, to) = transition.stableSegment()
        if (from == to) {
            to.toValue()
        } else {
            (if (transition.segmentProgressFraction < 0.5f) from else to).toValue()
        }
    }

    private val presentedVirtualFrame: VirtualizedFrame<T>
        get() = presentedVirtualFrameState.value

    /** Total base beats declared for this slide. */
    val beats: Int get() = baseBeatsCount

    /**
     * Visibly presented zero-based base beat index, clamped to `0 until beats`.
     *
     * Follows `transition.stableSegment()` while resting and switches from `from` to `to` at the
     * active segment's midpoint (`segmentProgressFraction >= 0.5f`) so discrete values never jump
     * to the target beat at the start of a transition.
     */
    val beat: Int get() = presentedVirtualFrame.baseBeatIndex.coerceIn(0, (beats - 1).coerceAtLeast(0))

    /**
     * Typed frame value associated with the visibly presented beat (switching at the segment
     * midpoint `segmentProgressFraction >= 0.5f`, matching [beat]).
     */
    val value: T get() = presentedVirtualFrame.value

    /**
     * Selects the element corresponding to the visibly presented [beat] (switching at the segment
     * midpoint `segmentProgressFraction >= 0.5f`); requires exactly [beats] arguments.
     */
    fun <V> byBeat(vararg values: V): V {
        require(values.size == beats) { "byBeat expected $beats values for slide '$slideKey', got ${values.size}" }
        return values[beat]
    }

    /** Creates a child [Transition] tracking the clamped base beat index for beat-driven animations. */
    @Composable
    fun rememberBeatTransition(label: String = "$slideKey-beat"): Transition<Int> =
        transition.createChildTransition(label) { it.toValue().baseBeatIndex.coerceIn(0, (beats - 1).coerceAtLeast(0)) }

    /** Animates a [Float] across slide beats using the active deck motion spec (or reduced motion). */
    @Composable
    fun animateByBeat(
        label: String = "$slideKey-float",
        durationMillis: Int? = null,
        targetValueByBeat: (beat: Int) -> Float,
    ): State<Float> {
        val activeTheme = LocalDeckTheme.current
        val reducedMotion = LocalReducedMotion.current
        val targetMs = durationMillis ?: activeTheme.motion.fadeMoveDurationMs
        return transition.animateFloat(
            transitionSpec = {
                if (reducedMotion) {
                    tween(durationMillis = REDUCED_MOTION_FADE_DURATION_MS)
                } else {
                    tween(targetMs, easing = activeTheme.motion.standardEasing)
                }
            },
            label = label,
        ) { frame ->
            targetValueByBeat(frame.toValue().baseBeatIndex.coerceIn(0, (beats - 1).coerceAtLeast(0)))
        }
    }
}

/** Registers sequential per-beat speaker notes starting from beat `0`. */
fun SceneSpeakerNotesBuilder.beats(vararg notes: String) {
    notes.forEachIndexed { index, note -> beat(index, note) }
}

/** Hosts an arbitrary live `@Composable` inside a slide and marks its bounds with [stageInteractive]. */
@Composable
fun SlideScope<*>.Specimen(
    id: String,
    modifier: Modifier = Modifier,
    label: String = id,
    content: @Composable () -> Unit,
) {
    Box(modifier = modifier.stageInteractive(id = id, label = label)) { content() }
}
