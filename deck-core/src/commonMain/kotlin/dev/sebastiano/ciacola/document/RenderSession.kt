package dev.sebastiano.ciacola.document

import androidx.compose.animation.core.Transition
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import dev.bnorm.storyboard.Frame
import dev.bnorm.storyboard.Storyboard
import dev.bnorm.storyboard.easel.Animatic
import dev.bnorm.storyboard.easel.LocalExportEmittedFrameIndex
import dev.bnorm.storyboard.easel.LocalExportFps
import dev.bnorm.storyboard.easel.LocalStoryboardTransition
import dev.sebastiano.ciacola.inspector.stageInteractive
import dev.sebastiano.ciacola.theme.DeckTheme
import dev.sebastiano.ciacola.theme.SlidePalette
import kotlin.math.PI
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.serialization.json.JsonElement

/**
 * Per-mount render session owning:
 * - explicit [purpose] (`Present`, `Still`, or `Video` — never inferred from `SceneMode`);
 * - the document source (live [DeckDocumentStore.document] for `Present`; an immutable frozen
 *   snapshot taken at session creation for `Still` and `Video`);
 * - an isolated [retained] store empty at creation that survives cold seeks (`scrubEpoch++`) within
 *   this mount and never leaks across sessions;
 * - an isolated `movableContentOf` slot cache for cross-slide specimen handoffs;
 * - the single loop-time producer; and
 * - mount-local root transition binding so `Storyboard.sceneZIndex`, scene renderers, and the
 *   decorator all read the exact same [DeckClock] before sibling z-index ordering runs.
 */
@Stable
class RenderSession private constructor(
    val purpose: RenderPurpose,
    private val documentProvider: () -> DeckDocument,
    private val freeClockMillis: () -> Long,
    private val masterPlayheadMillis: () -> Long?,
    private val subCompositionScrubMillis: () -> Long?,
) {
    /** Current document rendered by this session (live for [RenderPurpose.Present], frozen otherwise). */
    val document: DeckDocument
        get() = documentProvider()

    private val retainedStore = mutableMapOf<String, Any>()
    private val movableSlots = mutableMapOf<String, @Composable (@Composable () -> Unit) -> Unit>()

    @Volatile
    private var boundRootTransition: Transition<out Animatic.State<*>>? = null

    /**
     * Binds this session to the current mount's root [Animatic] transition inside
     * `Storyboard.decorator` BEFORE `scenesContent()` invokes `DeterministicStateHost` and
     * `sceneZIndex`. Passing `null` (e.g. inside `ScenePreview`) clears the binding.
     */
    fun bindRootTransition(transition: Transition<out Animatic.State<*>>?) {
        boundRootTransition = transition
    }

    /**
     * Unbinds [expected] when a mount leaves composition, leaving any newer mount's binding
     * untouched.
     */
    fun unbindRootTransition(expected: Transition<out Animatic.State<*>>) {
        if (boundRootTransition === expected) {
            boundRootTransition = null
        }
    }

    /**
     * Composable helper called at the top of a document `Storyboard.decorator` to bind this
     * session to [LocalStoryboardTransition] before `scenesContent()` runs and cleanly unbind on
     * disposal.
     */
    @Composable
    fun BindMountTransition() {
        val rootTransition = LocalStoryboardTransition.current ?: return
        remember(this, rootTransition) {
            bindRootTransition(rootTransition)
            rootTransition
        }
        DisposableEffect(this, rootTransition) {
            bindRootTransition(rootTransition)
            onDispose {
                unbindRootTransition(rootTransition)
            }
        }
    }

    /**
     * Computes this mount's current [DeckClock] from the bound root transition (or falls back to
     * [fallbackSlide]/[fallbackFrame] on a still mount).
     */
    fun currentClock(
        fallbackSlide: SlideSpec? = null,
        fallbackFrame: Frame<Int> = Frame.Value(0),
    ): DeckClock {
        val nonSkipped = document.slides.filterNot { it.skipped }
        val root = boundRootTransition?.rootSegment()
        if (root != null) {
            return DeckClock.fromRootSegment(root, nonSkipped)
        }
        val slide = fallbackSlide ?: nonSkipped.firstOrNull()
        return if (slide != null) {
            DeckClock.fromStillFrame(slide, fallbackFrame)
        } else {
            DeckClock(DocSegment.Rest("", 0), 0f, 0f)
        }
    }

    /**
     * Non-composable `sceneZIndex` callback suitable for `Storyboard.build(sceneZIndex = ...)` or
     * `Easel(..., sceneZIndex = ...)`. Reads this session's mount-local bound root transition on
     * demand and honors theme default transitions plus [reducedMotion] collapse so sibling
     * stacking matches the host decorator's compositor rules.
     */
    fun sceneZIndexFor(
        index: Storyboard.Index,
        themeName: String? = null,
        reducedMotion: Boolean = false,
    ): Float {
        val doc = document
        val nonSkipped = doc.slides.filterNot { it.skipped }
        val slide = nonSkipped.getOrNull(index.sceneIndex) ?: return index.sceneIndex.toFloat()
        val clock = currentClock(fallbackSlide = slide, fallbackFrame = Frame.Value(index.frameIndex.coerceAtLeast(0)))
        val effectiveThemeName = themeName ?: doc.defaultTheme
        val themeTransition = (effectiveThemeName?.let { doc.themes[it] } ?: doc.themes.values.firstOrNull())
            ?.motion?.transition
        return CrossSlideGeometry.sceneZIndex(
            slideIndex = index.sceneIndex,
            slideId = slide.id,
            clock = clock,
        ) { slideId ->
            val s = nonSkipped.firstOrNull { it.id == slideId }
            val eff = s?.transition ?: themeTransition
            if (reducedMotion && eff != null && eff.type != TransitionType.None && eff.type != TransitionType.FadeThroughColor) {
                TransitionSpec(type = TransitionType.Dissolve)
            } else {
                eff
            }
        }
    }

    /**
     * Returns the per-beat hold duration callback for `StoryboardVideoExporter`.
     * Uses the beat's `autoAdvanceMs` when present, else [defaultHoldMs].
     */
    fun holdDurationForState(
        index: Storyboard.Index,
        defaultHoldMs: Long = 400L,
    ): Duration {
        val nonSkipped = document.slides.filterNot { it.skipped }
        val slide = nonSkipped.getOrNull(index.sceneIndex)
        val beatMs = slide?.beats?.getOrNull(index.frameIndex)?.autoAdvanceMs?.toLong()
        return (beatMs ?: defaultHoldMs).coerceAtLeast(0L).milliseconds
    }

    /**
     * Returns or lazily initializes a session-scoped retained value keyed by [key].
     */
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> getOrPutRetained(key: String, init: () -> T): T {
        val existing = retainedStore[key]
        if (existing != null) return existing as T
        val created = init()
        retainedStore[key] = created
        return created
    }

    /** Returns `true` if [key] is currently populated in this session's isolated `retained` store. */
    fun hasRetained(key: String): Boolean = key in retainedStore

    /**
     * Returns a stable `movableContentOf` wrapper keyed by [slotKey] (typically
     * `"$nodeId:$specimenRef"`) for exclusive single-owner cross-slide handoffs.
     */
    fun movableSlot(slotKey: String): @Composable (@Composable () -> Unit) -> Unit =
        movableSlots.getOrPut(slotKey) {
            movableContentOf { body: @Composable () -> Unit -> body() }
        }

    /** Effective loop period in ms, honouring `document.loopPeriodsMs[loopId]` overrides. */
    fun effectiveLoopPeriodMs(loopId: String, declaredPeriodMs: Long): Long {
        val override = document.loopPeriodsMs[loopId]
        return (override ?: declaredPeriodMs).coerceAtLeast(1L)
    }

    /**
     * Single loop-time producer. Pure calculation: reading never launches a frame loop or
     * mutates shared state.
     *
     * - [RenderPurpose.Present]: `subCompositionScrubMillis ?: masterPlayheadMillis ?: freeClockMillis`,
     *   modulo effective period.
     * - [RenderPurpose.Still]: fixed at `effectivePeriod / 4` (25 % of period).
     * - [RenderPurpose.Video]: `emittedFrameIndex * 1000 / exportFps` modulo effective period
     *   (returns `0L` during measurement passes where `emittedFrameIndex == null`).
     */
    fun loopMillis(
        loopId: String,
        declaredPeriodMs: Long,
        emittedFrameIndex: Long? = null,
        exportFps: Int? = null,
    ): Long {
        val period = effectiveLoopPeriodMs(loopId, declaredPeriodMs)
        return when (purpose) {
            RenderPurpose.Still -> period / 4L
            RenderPurpose.Video -> {
                if (emittedFrameIndex == null || exportFps == null || exportFps <= 0) {
                    0L
                } else {
                    val rawMs = (emittedFrameIndex * 1000L) / exportFps.toLong()
                    positiveMod(rawMs, period)
                }
            }

            RenderPurpose.Present -> {
                val rawMs = subCompositionScrubMillis()
                    ?: masterPlayheadMillis()
                    ?: freeClockMillis()
                positiveMod(rawMs, period)
            }
        }
    }

    /** Loop phase in radians (`[0f, 2π)`), derived directly from [loopMillis]. */
    fun loopPhaseRadians(
        loopId: String,
        declaredPeriodMs: Long,
        emittedFrameIndex: Long? = null,
        exportFps: Int? = null,
    ): Float {
        val period = effectiveLoopPeriodMs(loopId, declaredPeriodMs)
        val ms = loopMillis(loopId, declaredPeriodMs, emittedFrameIndex, exportFps)
        return ((ms.toDouble() / period.toDouble()) * 2.0 * PI).toFloat()
    }

    /**
     * Composable variant of [loopMillis] that reads [LocalExportEmittedFrameIndex] and
     * [LocalExportFps] automatically for [RenderPurpose.Video] mounts.
     */
    @Composable
    fun currentLoopMillis(loopId: String, declaredPeriodMs: Long): Long {
        val emitted = if (purpose == RenderPurpose.Video) LocalExportEmittedFrameIndex.current else null
        val fps = if (purpose == RenderPurpose.Video) LocalExportFps.current else null
        return loopMillis(loopId, declaredPeriodMs, emitted, fps)
    }

    companion object {
        /** Creates a live presentation session backed by [store]. */
        fun forPresent(
            store: DeckDocumentStore,
            freeClockMillis: () -> Long = { 0L },
            masterPlayheadMillis: () -> Long? = { null },
            subCompositionScrubMillis: () -> Long? = { null },
        ): RenderSession = RenderSession(
            purpose = RenderPurpose.Present,
            documentProvider = { store.document },
            freeClockMillis = freeClockMillis,
            masterPlayheadMillis = masterPlayheadMillis,
            subCompositionScrubMillis = subCompositionScrubMillis,
        )

        /** Creates a live presentation session backed by an arbitrary [documentProvider]. */
        fun forPresent(
            documentProvider: () -> DeckDocument,
            freeClockMillis: () -> Long = { 0L },
            masterPlayheadMillis: () -> Long? = { null },
            subCompositionScrubMillis: () -> Long? = { null },
        ): RenderSession = RenderSession(
            purpose = RenderPurpose.Present,
            documentProvider = documentProvider,
            freeClockMillis = freeClockMillis,
            masterPlayheadMillis = masterPlayheadMillis,
            subCompositionScrubMillis = subCompositionScrubMillis,
        )

        /** Creates an isolated still-frame session (Overview, lint, PDF) with a frozen document snapshot. */
        fun forStill(documentSnapshot: DeckDocument): RenderSession = RenderSession(
            purpose = RenderPurpose.Still,
            documentProvider = { documentSnapshot },
            freeClockMillis = { 0L },
            masterPlayheadMillis = { null },
            subCompositionScrubMillis = { null },
        )

        /** Creates an isolated video-export session with a frozen document snapshot. */
        fun forVideo(documentSnapshot: DeckDocument): RenderSession = RenderSession(
            purpose = RenderPurpose.Video,
            documentProvider = { documentSnapshot },
            freeClockMillis = { 0L },
            masterPlayheadMillis = { null },
            subCompositionScrubMillis = { null },
        )
    }
}

val LocalRenderSession = compositionLocalOf<RenderSession?> { null }

/**
 * Surface background tracked through [SpecimenScope.ProvideSurfaceBackground].
 * `null` means the background is not provably a solid color (e.g. gradient, image, shader).
 */
val LocalSpecimenSurfaceBackground = compositionLocalOf<Color?> { null }

/**
 * Concrete [SpecimenScope] implementation wired to a single [RenderSession], [DeckClock], and
 * [SegmentPlan].
 *
 * Exposes NO root-attached `Transition`, guaranteeing specimens cannot stretch a root segment.
 */
@Stable
class DocumentSpecimenScope(
    override val slideId: String,
    override val nodeId: String,
    private val specimenRef: String,
    private val session: RenderSession,
    private val clock: DeckClock,
    override val beatsCount: Int,
    private val fromParams: Map<String, JsonElement>,
    private val toParams: Map<String, JsonElement>?,
    private val paramProgress: Float,
    private val trackProgressProvider: (TrackHandle) -> Float,
    private val emittedFrameIndex: Long? = null,
    private val exportFps: Int? = null,
    override val theme: DeckTheme,
    override val palette: SlidePalette? = null,
    override val effectiveBackground: Color? = null,
    override val fontScale: Float = 1f,
    override val reducedMotion: Boolean = false,
    private val resolveColorToken: (String) -> Color? = { null },
    private val retainedNamespace: String = "specimen:$slideId/$nodeId/$specimenRef",
) : SpecimenScope {

    override val purpose: RenderPurpose
        get() = session.purpose

    override val fromBeat: Int
        get() = when (val seg = clock.segment) {
            is DocSegment.Rest -> seg.beat
            is DocSegment.AtStart -> 0
            is DocSegment.Entrance -> 0
            is DocSegment.Beat -> seg.fromBeat
            is DocSegment.Cross -> if (seg.fromSlideId == slideId) (beatsCount - 1).coerceAtLeast(0) else 0
        }

    override val toBeat: Int
        get() = when (val seg = clock.segment) {
            is DocSegment.Rest -> seg.beat
            is DocSegment.AtStart -> 0
            is DocSegment.Entrance -> 0
            is DocSegment.Beat -> (seg.fromBeat + 1).coerceAtMost((beatsCount - 1).coerceAtLeast(0))
            is DocSegment.Cross -> if (seg.fromSlideId == slideId) (beatsCount - 1).coerceAtLeast(0) else 0
        }

    override val segmentFraction: Float
        get() = clock.progress()

    /** Presented beat: switches at the segment midpoint (`segmentFraction >= 0.5f`). */
    override val beat: Int
        get() = if (clock.isResting || segmentFraction >= 0.5f) toBeat else fromBeat

    override operator fun <T> ParamHandle<T>.invoke(): T {
        val fromVal = ParamResolver.resolve(spec, fromParams[spec.name], resolveColorToken).value
        if (toParams == null || clock.isResting) {
            return fromVal
        }
        val toVal = ParamResolver.resolve(spec, toParams[spec.name], resolveColorToken).value
        return ParamResolver.interpolate(spec, fromVal, toVal, paramProgress)
    }

    override operator fun TrackHandle.invoke(): Float = trackProgressProvider(this)

    override operator fun LoopHandle.invoke(): Long {
        val id = loopId(specimenRef = specimenRef, slideId = slideId, nodeId = nodeId)
        return session.loopMillis(
            loopId = id,
            declaredPeriodMs = periodMs,
            emittedFrameIndex = emittedFrameIndex,
            exportFps = exportFps,
        )
    }

    override fun LoopHandle.phaseRadians(): Float {
        val id = loopId(specimenRef = specimenRef, slideId = slideId, nodeId = nodeId)
        return session.loopPhaseRadians(
            loopId = id,
            declaredPeriodMs = periodMs,
            emittedFrameIndex = emittedFrameIndex,
            exportFps = exportFps,
        )
    }

    @Composable
    override fun ProvideSurfaceBackground(color: Color, content: @Composable () -> Unit) {
        CompositionLocalProvider(LocalSpecimenSurfaceBackground provides color, content = content)
    }

    override fun Modifier.stageHitRegion(): Modifier =
        stageInteractive(id = "$slideId/$nodeId", label = "$specimenRef ($nodeId)")

    @Composable
    override fun <T : Any> retained(key: String, init: () -> T): T =
        session.getOrPutRetained("$retainedNamespace/$key", init)
}

private fun positiveMod(value: Long, modulus: Long): Long {
    val rem = value % modulus
    return if (rem < 0L) rem + modulus else rem
}
