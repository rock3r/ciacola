package dev.sebastiano.ciacola.document.render

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LookaheadScope
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import dev.bnorm.storyboard.ContentDecorator
import dev.bnorm.storyboard.Frame
import dev.bnorm.storyboard.SceneFormat
import dev.bnorm.storyboard.Storyboard
import dev.bnorm.storyboard.easel.LocalStoryboardTransition
import dev.sebastiano.ciacola.document.CrossSlideGeometry
import dev.sebastiano.ciacola.document.CrossSlideOwner
import dev.sebastiano.ciacola.document.DeckClock
import dev.sebastiano.ciacola.document.DeckDocument
import dev.sebastiano.ciacola.document.DeckSpecimenRegistry
import dev.sebastiano.ciacola.document.DeckStructureKey
import dev.sebastiano.ciacola.document.DeckThemeSpec
import dev.sebastiano.ciacola.document.DocSegment
import dev.sebastiano.ciacola.document.JsonMergePatch
import dev.sebastiano.ciacola.document.LocalDeckClock
import dev.sebastiano.ciacola.document.LocalRenderSession
import dev.sebastiano.ciacola.document.MatchedPair
import dev.sebastiano.ciacola.document.NodeKind
import dev.sebastiano.ciacola.document.NodeSpec
import dev.sebastiano.ciacola.document.RenderSession
import dev.sebastiano.ciacola.document.SegmentPlan
import dev.sebastiano.ciacola.document.SegmentPlanner
import dev.sebastiano.ciacola.document.SlideSpec
import dev.sebastiano.ciacola.document.TrackKind
import dev.sebastiano.ciacola.document.TransitionSpec
import dev.sebastiano.ciacola.document.TransitionType
import dev.sebastiano.ciacola.document.rememberDocCrossCarrier
import dev.sebastiano.ciacola.document.rememberDocIntraCarrier
import dev.sebastiano.ciacola.document.rootSegment
import dev.sebastiano.ciacola.effects.LocalOverlayStageAnchorRegistry
import dev.sebastiano.ciacola.effects.LocalStageAnchorRegistry
import dev.sebastiano.ciacola.effects.StageAnchorRegistry
import dev.sebastiano.ciacola.effects.v2.SlideTransitionComposition
import dev.sebastiano.ciacola.effects.v2.SlideTransitionV2
import dev.sebastiano.ciacola.effects.v2.composeSlideTransition
import dev.sebastiano.ciacola.effects.v2.slideLayerTransform
import dev.sebastiano.ciacola.inspector.LocalEffectiveBackgroundColor
import dev.sebastiano.ciacola.inspector.LocalStageRootCoordinates
import dev.sebastiano.ciacola.inspector.StageRootCoordinatesHolder
import dev.sebastiano.ciacola.presenter.StoryboardSpeakerNotesRegistry
import dev.sebastiano.ciacola.review.LocalReviewStageBridge
import dev.sebastiano.ciacola.theme.LocalDeckTheme
import dev.sebastiano.ciacola.theme.LocalReducedMotion
import dev.sebastiano.ciacola.theme.LocalSlidePalette

/**
 * Mutable config holder read dynamically inside scenes and decorator so property/theme/motion/mode
 * edits recompose in place without rebuilding the [Storyboard] or remounting scenes.
 */
@Stable
internal class LiveDocumentRenderConfigHolder(initial: DocumentRenderConfig) {
    var config: DocumentRenderConfig by mutableStateOf(initial)
    @Volatile
    var effectiveReducedMotion: Boolean = initial.reducedMotion ?: false
}

/**
 * Active cross-slide context computed once inside the decorator and passed down via composition
 * local so `SceneA`, `SceneB`, and the Root Transition Overlay share the exact same [MatchedPair]s,
 * [SegmentPlan], and [SlideTransitionComposition] on every frame.
 */
@Immutable
internal data class ActiveCrossSlideContext(
    val fromSlide: SlideSpec,
    val toSlide: SlideSpec,
    val transitionSpec: TransitionSpec,
    val transitionV2: SlideTransitionV2,
    val composition: SlideTransitionComposition,
    val plan: SegmentPlan,
    val matchedPairs: List<MatchedPair>,
    val isMagicMoveActive: Boolean,
    val reducedMotion: Boolean,
)

private val LocalActiveCrossSlideContext =
    androidx.compose.runtime.compositionLocalOf<ActiveCrossSlideContext?> { null }

/**
 * Per-(document, reducedMotion, specimens, themeName) memoization cache shared across the host
 * decorator, duration carriers, and all slide scenes so animation frames never re-plan segments,
 * re-match cross-slide pairs, or re-run JSON merge patches.
 */
@Stable
internal class DocumentRenderCache(
    val document: DeckDocument,
    val reducedMotion: Boolean,
    val specimens: DeckSpecimenRegistry,
    val themeName: String?,
) {
    private val effectiveThemeName: String? = themeName ?: document.defaultTheme
    val activeThemeSpec: DeckThemeSpec? = resolveActiveThemeSpec(document, themeName)
    val nonSkippedSlides: List<SlideSpec> = document.slides.filterNot { it.skipped }
    private val slideIndexById: Map<String, Int> = nonSkippedSlides.withIndex().associate { it.value.id to it.index }

    private val segmentPlans = HashMap<Pair<String, Int>, SegmentPlan>()
    private val transitionPlans = HashMap<Pair<String, String>, SegmentPlan>()
    private val matchedPairsCache = HashMap<Pair<String, String>, List<MatchedPair>>()
    private val resolvedAtBeatCache = HashMap<Triple<String, String, Int>, NodeSpec>()
    private val resolvedAfterBuildCache = HashMap<Triple<String, String, Int>, NodeSpec>()

    val movableSpecimenSlotKeys: Map<MovableSpecimenKey, String> by lazy {
        computeMovableSpecimenSlotMap(
            nonSkipped = nonSkippedSlides,
            activeThemeSpec = activeThemeSpec,
            matchPairs = ::matchedPairs,
        )
    }

    fun segmentPlan(slideId: String, beat: Int): SegmentPlan =
        segmentPlans.getOrPut(slideId to beat) {
            SegmentPlanner.planSegment(
                document = document,
                slideId = slideId,
                beat = beat,
                reducedMotion = reducedMotion,
                specimens = specimens,
                themeName = effectiveThemeName,
            )
        }

    fun transitionPlan(fromSlideId: String, toSlideId: String): SegmentPlan =
        transitionPlans.getOrPut(fromSlideId to toSlideId) {
            SegmentPlanner.planTransition(
                document = document,
                fromSlideId = fromSlideId,
                toSlideId = toSlideId,
                reducedMotion = reducedMotion,
                specimens = specimens,
                themeName = effectiveThemeName,
            )
        }

    fun matchedPairs(fromSlideId: String, toSlideId: String): List<MatchedPair> =
        matchedPairsCache.getOrPut(fromSlideId to toSlideId) {
            val slideA = nonSkippedSlides.firstOrNull { it.id == fromSlideId } ?: return@getOrPut emptyList()
            val slideB = nonSkippedSlides.firstOrNull { it.id == toSlideId } ?: return@getOrPut emptyList()
            CrossSlideGeometry.matchCrossSlidePairs(
                fromSlide = slideA,
                toSlide = slideB,
                stage = document.stage,
            ).pairs
        }

    fun incomingTransitionForSlide(slideId: String): TransitionSpec? {
        val idx = slideIndexById[slideId] ?: return null
        if (idx <= 0) return null
        return effectiveSlideTransition(nonSkippedSlides[idx - 1], activeThemeSpec)
    }

    fun resolveNodeAtBeat(slideId: String, rawNode: NodeSpec, beat: Int): NodeSpec =
        resolvedAtBeatCache.getOrPut(Triple(slideId, rawNode.id, beat)) {
            JsonMergePatch.resolveNodeAtBeat(rawNode, beat)
        }

    fun resolveNodeAfterBuild(slideId: String, rawNode: NodeSpec, buildIndex: Int): NodeSpec =
        resolvedAfterBuildCache.getOrPut(Triple(slideId, rawNode.id, buildIndex)) {
            JsonMergePatch.resolveNodeAfterBuild(rawNode, buildIndex)
        }
}

private val LocalDocumentRenderCache =
    androidx.compose.runtime.compositionLocalOf<DocumentRenderCache?> { null }

/**
 * Builds a Storyboard from a [RenderSession] and [DocumentRenderConfig].
 *
 * - Captures only `slideId` per scene and reads live [SlideSpec] from `session.document`, so live
 *   property, motion, and theme edits recompose in place without remounting scenes or specimens.
 * - Mounts the Root Transition Overlay inside `Storyboard.decorator` (inside `Easel`'s `FixedSize`
 *   and freeze boundary).
 * - Registers speaker notes in [StoryboardSpeakerNotesRegistry].
 * - Wires `sceneZIndex` to [documentSceneZIndex] honoring theme default transitions and reduced motion.
 */
fun buildDocumentStoryboard(
    session: RenderSession,
    config: DocumentRenderConfig = DocumentRenderConfig(),
    title: String? = null,
): Storyboard = buildDocumentStoryboardInternal(
    session = session,
    configHolder = LiveDocumentRenderConfigHolder(config),
    title = title,
)

/**
 * Remembers a [Storyboard] built from [session] and [config], rebuilding ONLY when the document's
 * [DeckStructureKey] (ordered `(slideId, beatCount)` for non-skipped slides) or stage format
 * changes.
 */
@Composable
fun rememberDocumentStoryboard(
    session: RenderSession,
    config: DocumentRenderConfig = DocumentRenderConfig(),
    title: String? = null,
): Storyboard {
    val configHolder = remember(session) { LiveDocumentRenderConfigHolder(config) }
    SideEffect {
        configHolder.config = config
    }

    val doc = session.document
    val structureKey = remember(doc) { DeckStructureKey.from(doc) }
    val stageW = doc.stage.width
    val stageH = doc.stage.height

    val storyboard = remember(session, structureKey, stageW, stageH, config.baseDensity, config.fontScale, title) {
        configHolder.config = config
        buildDocumentStoryboardInternal(
            session = session,
            configHolder = configHolder,
            title = title,
        )
    }

    // Keep speaker notes synced whenever beat notes change without rebuilding the Storyboard.
    val speakerNotes = remember(doc) { buildDocumentSpeakerNotes(doc) }
    SideEffect {
        StoryboardSpeakerNotesRegistry.register(storyboard, speakerNotes)
    }

    return storyboard
}

/**
 * Canonical `sceneZIndex` resolver for a document mount.
 *
 * Honors `slide.transition`, falls back to active theme `motion.transition`, and respects
 * `reducedMotion` (collapsing `Reveal` and `Iris(Out)` to `Dissolve` -> `BOverA`).
 */
fun documentSceneZIndex(
    session: RenderSession,
    config: DocumentRenderConfig,
    index: Storyboard.Index,
    reducedMotionOverride: Boolean? = null,
): Float {
    val doc = session.document
    val nonSkipped = doc.slides.filterNot { it.skipped }
    val slide = nonSkipped.getOrNull(index.sceneIndex) ?: return index.sceneIndex.toFloat()
    val clock = session.currentClock(
        fallbackSlide = slide,
        fallbackFrame = Frame.Value(index.frameIndex.coerceAtLeast(0)),
    )
    val themeSpec = resolveActiveThemeSpec(doc, config.themeName)
    val reduced = reducedMotionOverride ?: (config.reducedMotion ?: false)
    return CrossSlideGeometry.sceneZIndex(
        slideIndex = index.sceneIndex,
        slideId = slide.id,
        clock = clock,
    ) { slideId ->
        val s = nonSkipped.firstOrNull { it.id == slideId }
        val eff = s?.let { effectiveSlideTransition(it, themeSpec) }
        if (reduced && eff != null && eff.type != TransitionType.None && eff.type != TransitionType.FadeThroughColor) {
            TransitionSpec(type = TransitionType.Dissolve)
        } else {
            eff
        }
    }
}

private fun buildDocumentStoryboardInternal(
    session: RenderSession,
    configHolder: LiveDocumentRenderConfigHolder,
    title: String?,
): Storyboard {
    val initialDoc = session.document
    val nonSkipped = initialDoc.slides.filterNot { it.skipped }
    val effectiveTitle = title ?: initialDoc.title.ifBlank { initialDoc.id }
    val format = SceneFormat(
        size = IntSize(initialDoc.stage.width, initialDoc.stage.height),
        density = Density(
            density = configHolder.config.baseDensity,
            fontScale = configHolder.config.fontScale,
        ),
    )

    val hostDecorator = ContentDecorator { content ->
        DocumentStageHostDecorator(
            session = session,
            configHolder = configHolder,
            scenesContent = content,
        )
    }

    val storyboard = Storyboard.build(
        title = effectiveTitle,
        format = format,
        decorator = hostDecorator,
        sceneZIndex = { index ->
            documentSceneZIndex(
                session = session,
                config = configHolder.config,
                index = index,
                reducedMotionOverride = configHolder.config.reducedMotion ?: configHolder.effectiveReducedMotion,
            )
        },
    ) {
        if (nonSkipped.isEmpty()) {
            scene(
                frames = listOf(0),
                enterTransition = { EnterTransition.None },
                exitTransition = { ExitTransition.None },
            ) {
                Box(Modifier.fillMaxSize())
            }
        } else {
            for (slide in nonSkipped) {
                val capturedSlideId = slide.id
                val beatCount = slide.beats.size.coerceAtLeast(1)
                scene(
                    frames = List(beatCount) { it },
                    enterTransition = { EnterTransition.None },
                    exitTransition = { ExitTransition.None },
                ) {
                    val liveDoc = session.document
                    val liveConfig = configHolder.config
                    val liveReduced = liveConfig.reducedMotion ?: LocalReducedMotion.current
                    val renderCache = LocalDocumentRenderCache.current
                        ?: remember(liveDoc, liveReduced, liveConfig.specimens, liveConfig.themeName) {
                            DocumentRenderCache(
                                document = liveDoc,
                                reducedMotion = liveReduced,
                                specimens = liveConfig.specimens,
                                themeName = liveConfig.themeName,
                            )
                        }
                    val liveNonSkipped = renderCache.nonSkippedSlides
                    val liveSceneIdx = liveNonSkipped.indexOfFirst { it.id == capturedSlideId }
                    val liveSlide = liveNonSkipped.getOrNull(liveSceneIdx) ?: return@scene

                    // Mount intra-slide & cross-slide duration carriers on animated mounts.
                    if (LocalStoryboardTransition.current != null) {
                        transition.rememberDocIntraCarrier(
                            beatCount = liveSlide.beats.size.coerceAtLeast(1),
                            entranceDurationMs = {
                                renderCache.segmentPlan(liveSlide.id, beat = 0).segmentMs
                            },
                            beatSegmentDurationMs = { fromBeat ->
                                renderCache.segmentPlan(liveSlide.id, beat = fromBeat + 1).segmentMs
                            },
                        )
                        rememberDocCrossCarrier { earlierIdx, laterIdx ->
                            val sA = liveNonSkipped.getOrNull(earlierIdx)
                            val sB = liveNonSkipped.getOrNull(laterIdx)
                            if (sA != null && sB != null) {
                                renderCache.transitionPlan(sA.id, sB.id).segmentMs
                            } else {
                                0
                            }
                        }
                    }

                    val boundClock = LocalDeckClock.current
                    val isAnimatedMount = boundClock != null
                    val sceneClock = boundClock ?: DeckClock.fromStillFrame(
                        slide = liveSlide,
                        frame = transition.currentState,
                    )

                    val crossCtx = LocalActiveCrossSlideContext.current
                    DocumentSlideSceneBody(
                        slide = liveSlide,
                        sceneIndex = liveSceneIdx.coerceAtLeast(0),
                        clock = sceneClock,
                        frameTransition = if (isAnimatedMount) transition else null,
                        session = session,
                        config = liveConfig,
                        reducedMotion = liveReduced,
                        isAnimatedMount = isAnimatedMount,
                        crossCtx = crossCtx,
                        renderCache = renderCache,
                    )
                }
            }
        }
    }

    StoryboardSpeakerNotesRegistry.register(storyboard, buildDocumentSpeakerNotes(initialDoc))
    return storyboard
}

/**
 * Mounted via `Storyboard.decorator` inside `Easel`'s `FixedSize(1920x1080)` and freeze boundary.
 * Owns:
 * 1. Mount-local root transition binding (`session.bindRootTransition` ONLY on animated mounts so
 *    concurrent `ScenePreview` mounts never clobber a live `Easel` mount's `sceneZIndex` binding!).
 * 2. Host-separated cross-fading backgrounds ONLY for active `magicMove` (`!reducedMotion`).
 * 3. `scenesContent()`.
 * 4. Root Transition Overlay drawing matched travelling `magicMove` nodes (Z-ordered by `B`'s
 *    document order, unclipped!) and `fadeThroughColor` full-stage opaque veil.
 */
@Composable
private fun DocumentStageHostDecorator(
    session: RenderSession,
    configHolder: LiveDocumentRenderConfigHolder,
    scenesContent: @Composable () -> Unit,
) {
    val config = configHolder.config
    val doc = session.document
    val reducedMotion = config.reducedMotion ?: LocalReducedMotion.current
    SideEffect {
        configHolder.effectiveReducedMotion = reducedMotion
    }
    val renderCache = remember(doc, reducedMotion, config.specimens, config.themeName) {
        DocumentRenderCache(
            document = doc,
            reducedMotion = reducedMotion,
            specimens = config.specimens,
            themeName = config.themeName,
        )
    }
    val nonSkipped = renderCache.nonSkippedSlides
    val theme = remember(doc, config.themeName) { resolveDeckTheme(doc, config.themeName) }
    val activeThemeSpec = renderCache.activeThemeSpec
    val fontResolverHandle = rememberDocumentFontResolverHandle(doc, config.fontsDir)
    val stageSize = Size(doc.stage.width.toFloat(), doc.stage.height.toFloat())
    val stageRootHolder = remember { StageRootCoordinatesHolder() }
    val movableSlotPool = remember { MovableSpecimenSlotPool() }
    val rootTransition = LocalStoryboardTransition.current
    val isAnimatedMount = rootTransition != null
    val lookaheadStore = remember(isAnimatedMount) {
        if (isAnimatedMount) CrossSlideLookaheadGeometryStore() else null
    }
    val overlayAnchorRegistry = remember(isAnimatedMount) {
        if (isAnimatedMount) StageAnchorRegistry() else null
    }

    // Bind mount-local root transition ONLY on animated mounts and unbind cleanly on dispose.
    session.BindMountTransition()
    val boundClock: DeckClock? = rootTransition?.rootSegment()?.let { rootSeg ->
        DeckClock.fromRootSegment(rootSeg, nonSkipped)
    }

    // Notify keyboard capture controller whenever active slide changes (auto-exit on navigation).
    val activeSlideId: String? = when (val seg = boundClock?.segment) {
        is DocSegment.Rest -> seg.slideId
        is DocSegment.AtStart -> seg.slideId
        is DocSegment.Entrance -> seg.slideId
        is DocSegment.Beat -> seg.slideId
        is DocSegment.Cross -> if (boundClock.progress() >= 0.5f) seg.toSlideId else seg.fromSlideId
        null -> null
    }
    val kbController = config.keyboardController
    if (kbController != null) {
        SideEffect {
            kbController.isEditMode = config.isEditMode
        }
        LaunchedEffect(activeSlideId) {
            kbController.onActiveSlideChanged(activeSlideId)
        }
    }

    // Compute active Cross(A, B) context strictly on animated mounts using memoized plans & pairs.
    val crossCtx: ActiveCrossSlideContext? = if (isAnimatedMount && boundClock != null) {
        val seg = boundClock.segment
        if (seg is DocSegment.Cross) {
            val slideA = nonSkipped.firstOrNull { it.id == seg.fromSlideId }
            val slideB = nonSkipped.firstOrNull { it.id == seg.toSlideId }
            if (slideA != null && slideB != null) {
                val tSpec = effectiveSlideTransition(slideA, activeThemeSpec)
                val colorResolverA = remember(activeThemeSpec, theme, slideA.palette, doc.palettes) {
                    DocumentColorResolver(
                        themeSpec = activeThemeSpec,
                        baseTheme = theme,
                        slidePaletteSpec = slideA.palette?.let { doc.palettes[it] },
                    )
                }
                val tV2 = remember(tSpec, colorResolverA, activeThemeSpec?.motion) {
                    tSpec.toSlideTransitionV2(colorResolverA, activeThemeSpec?.motion)
                }
                val plan = renderCache.transitionPlan(slideA.id, slideB.id)
                val slideTrack = plan.tracks.firstOrNull { it.kind == TrackKind.TransitionSlide }
                val p = when {
                    slideTrack != null -> {
                        val dur = slideTrack.durationMs
                        if (dur <= 0) {
                            if (boundClock.t >= slideTrack.startMs) 1f else 0f
                        } else {
                            ((boundClock.t - slideTrack.startMs.toFloat()) / dur.toFloat()).coerceIn(0f, 1f)
                        }
                    }
                    tSpec.type == TransitionType.MagicMove -> boundClock.progress()
                    tSpec.type == TransitionType.None -> if (boundClock.progress() >= 0.5f) 1f else 0f
                    else -> 0f
                }
                val composition = composeSlideTransition(
                    transition = tV2,
                    canonicalProgress = p,
                    stageSize = stageSize,
                    reducedMotion = reducedMotion,
                )
                val isMagicActive = tSpec.type == TransitionType.MagicMove && !reducedMotion
                val pairs = if (isMagicActive) {
                    renderCache.matchedPairs(slideA.id, slideB.id)
                } else {
                    emptyList()
                }
                ActiveCrossSlideContext(
                    fromSlide = slideA,
                    toSlide = slideB,
                    transitionSpec = tSpec,
                    transitionV2 = tV2,
                    composition = composition,
                    plan = plan,
                    matchedPairs = pairs,
                    isMagicMoveActive = isMagicActive,
                    reducedMotion = reducedMotion,
                )
            } else {
                null
            }
        } else {
            null
        }
    } else {
        null
    }

    CompositionLocalProvider(
        LocalRenderSession provides session,
        LocalDeckClock provides boundClock,
        LocalDeckTheme provides theme,
        LocalReducedMotion provides reducedMotion,
        LocalDocumentRenderConfig provides config,
        LocalMovableSpecimenSlotPool provides movableSlotPool,
        LocalDocumentKeyboardController provides config.keyboardController,
        LocalActiveCrossSlideContext provides crossCtx,
        LocalDocumentRenderCache provides renderCache,
        LocalStageRootCoordinates provides stageRootHolder,
        LocalCrossSlideLookaheadGeometryStore provides lookaheadStore,
        LocalOverlayStageAnchorRegistry provides (if (crossCtx?.isMagicMoveActive == true) overlayAnchorRegistry else null),
    ) {
        config.stageDecorator.decorate {
            val reviewBridge = LocalReviewStageBridge.current
            if (reviewBridge != null && activeSlideId != null) {
                val activeSlide = nonSkipped.firstOrNull { it.id == activeSlideId }
                val activeTitle = activeSlide?.title?.takeIf { it.isNotBlank() } ?: activeSlide?.id.orEmpty()
                SideEffect {
                    reviewBridge.updateSceneTitle(activeTitle)
                    reviewBridge.updateSceneHeader(activeTitle)
                }
            }
            @Composable
            fun StageContainer() {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clipToBounds()
                        .onPlaced { stageRootHolder.coordinates = it },
                ) {
                    // Layer 1 (underneath scenes): host-separated backgrounds ONLY for active magicMove!
                    if (crossCtx != null && crossCtx.isMagicMoveActive) {
                        val mm = crossCtx.composition.magicMove
                        val bgAAlpha = mm?.backgroundAAlpha ?: (1f - boundClock!!.progress())
                        val bgBAlpha = mm?.backgroundBAlpha ?: boundClock!!.progress()

                        val resolverA = remember(activeThemeSpec, theme, crossCtx.fromSlide.palette, doc.palettes) {
                            DocumentColorResolver(
                                themeSpec = activeThemeSpec,
                                baseTheme = theme,
                                slidePaletteSpec = crossCtx.fromSlide.palette?.let { doc.palettes[it] },
                            )
                        }
                        val resolverB = remember(activeThemeSpec, theme, crossCtx.toSlide.palette, doc.palettes) {
                            DocumentColorResolver(
                                themeSpec = activeThemeSpec,
                                baseTheme = theme,
                                slidePaletteSpec = crossCtx.toSlide.palette?.let { doc.palettes[it] },
                            )
                        }

                        if (bgAAlpha > 0.0005f) {
                            DocumentFillBackgroundBox(
                                fill = crossCtx.fromSlide.background,
                                fallbackColor = resolverA.resolve("canvas", theme.colors.background),
                                colorResolver = resolverA,
                                deckId = doc.id,
                                assetsRootDir = config.assetsRootDir,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .testTag("doc-magicmove-bg-a")
                                    .graphicsLayer { alpha = bgAAlpha.coerceIn(0f, 1f) },
                            )
                        }
                        if (bgBAlpha > 0.0005f) {
                            DocumentFillBackgroundBox(
                                fill = crossCtx.toSlide.background,
                                fallbackColor = resolverB.resolve("canvas", theme.colors.background),
                                colorResolver = resolverB,
                                deckId = doc.id,
                                assetsRootDir = config.assetsRootDir,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .testTag("doc-magicmove-bg-b")
                                    .graphicsLayer { alpha = bgBAlpha.coerceIn(0f, 1f) },
                            )
                        }
                    }

                    // Layer 2: Storyboard scenes (`DeterministicStateHost` with `sceneZIndex`)
                    scenesContent()

                    // Layer 3 (Root Transition Overlay): anchor-interleaved travelling + unmatched
                    // Magic Move nodes (unclipped) and FadeThroughColor opaque veil.
                    // Strictly no-op on ScenePreview / still mounts!
                    if (isAnimatedMount && boundClock != null && crossCtx != null) {
                        if (crossCtx.isMagicMoveActive) {
                            DocumentMagicMoveOverlayLayer(
                                doc = doc,
                                nonSkipped = nonSkipped,
                                fromSlide = crossCtx.fromSlide,
                                toSlide = crossCtx.toSlide,
                                transitionSpec = crossCtx.transitionSpec,
                                composition = crossCtx.composition,
                                plan = crossCtx.plan,
                                matchedPairs = crossCtx.matchedPairs,
                                boundClock = boundClock,
                                session = session,
                                config = config,
                                theme = theme,
                                activeThemeSpec = activeThemeSpec,
                                fontResolverHandle = fontResolverHandle,
                                reducedMotion = reducedMotion,
                                stageSize = stageSize,
                                renderCache = renderCache,
                                lookaheadStore = lookaheadStore,
                                overlayAnchorRegistry = overlayAnchorRegistry,
                            )
                        }

                        val veil = crossCtx.composition.veil
                        if (veil != null && veil.alpha > 0.0005f) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .testTag("doc-transition-veil")
                                    .graphicsLayer { alpha = veil.alpha.coerceIn(0f, 1f) }
                                    .background(veil.color.copy(alpha = 1f)),
                            )
                        }
                    }
                }
            }
            if (isAnimatedMount) {
                LookaheadScope {
                    StageContainer()
                }
            } else {
                StageContainer()
            }
        }
    }
}

@Composable
private fun DocumentSlideSceneBody(
    slide: SlideSpec,
    sceneIndex: Int,
    clock: DeckClock,
    frameTransition: androidx.compose.animation.core.Transition<out Frame<Int>>?,
    session: RenderSession,
    config: DocumentRenderConfig,
    reducedMotion: Boolean,
    isAnimatedMount: Boolean,
    crossCtx: ActiveCrossSlideContext?,
    renderCache: DocumentRenderCache,
) {
    val doc = session.document
    val theme = LocalDeckTheme.current
    val activeThemeSpec = renderCache.activeThemeSpec
    val colorResolver = remember(activeThemeSpec, theme, slide.palette, doc.palettes) {
        DocumentColorResolver(
            themeSpec = activeThemeSpec,
            baseTheme = theme,
            slidePaletteSpec = slide.palette?.let { doc.palettes[it] },
        )
    }
    val slidePalette = remember(slide.palette, doc.palettes, colorResolver) {
        resolveSlidePalette(slide, doc, colorResolver)
    }
    val effectiveSolidBg = remember(slide.background, colorResolver) {
        resolveEffectiveSolidBackground(slide, colorResolver)
    }
    val fontResolverHandle = rememberDocumentFontResolverHandle(doc, config.fontsDir)
    val anchorRegistry = remember(slide.id) { StageAnchorRegistry() }
    val stageSize = Size(doc.stage.width.toFloat(), doc.stage.height.toFloat())

    // Look up memoized intra-slide segment plan when animating an entrance or beat segment on this slide.
    val segmentPlan: SegmentPlan = when (val seg = clock.segment) {
        is DocSegment.Entrance -> if (seg.slideId == slide.id) {
            renderCache.segmentPlan(slide.id, beat = 0)
        } else {
            SegmentPlan.Empty
        }
        is DocSegment.Beat -> if (seg.slideId == slide.id) {
            renderCache.segmentPlan(slide.id, beat = seg.fromBeat + 1)
        } else {
            SegmentPlan.Empty
        }
        else -> SegmentPlan.Empty
    }

    val currentBeatIndex = when (val seg = clock.segment) {
        is DocSegment.Rest -> if (seg.slideId == slide.id) seg.beat else 0
        is DocSegment.Beat -> if (seg.slideId == slide.id) seg.fromBeat + 1 else 0
        is DocSegment.Cross -> if (seg.fromSlideId == slide.id) slide.beats.lastIndex.coerceAtLeast(0) else 0
        else -> 0
    }

    // Determine whole-slide layer transform & Magic Move suppression/unmatched fade maps.
    val isOutgoingA = crossCtx != null && crossCtx.fromSlide.id == slide.id
    val isIncomingB = crossCtx != null && crossCtx.toSlide.id == slide.id
    val isActiveMagicMove = crossCtx != null && crossCtx.isMagicMoveActive && (isOutgoingA || isIncomingB)

    val slideLayerMod: Modifier = when {
        crossCtx == null -> Modifier
        isActiveMagicMove -> Modifier // Per-node Exit/Enter tracks handle unmatched fade; matched nodes move in overlay.
        isOutgoingA -> Modifier.slideLayerTransform(crossCtx.composition.slideA.layer)
        isIncomingB -> Modifier.slideLayerTransform(crossCtx.composition.slideB.layer)
        else -> Modifier
    }

    val suppressedIds: Set<String>
    val unmatchedAlphas: Map<String, Float>
    if (isActiveMagicMove) {
        val activeCross = checkNotNull(crossCtx)
        val owner = CrossSlideGeometry.owner(activeCross.fromSlide.id, activeCross.toSlide.id, clock)
        val thisSlideOwns = (isOutgoingA && owner == CrossSlideOwner.SceneA) ||
            (isIncomingB && owner == CrossSlideOwner.SceneB)
        suppressedIds = if (thisSlideOwns) {
            emptySet()
        } else if (owner == CrossSlideOwner.Overlay) {
            // Overlay renders ALL root nodes (matched + unmatched) in anchor-interleaved Z-order;
            // SceneA and SceneB keep Lookahead ghosts mounted with zero draw / duplicate hooks.
            slide.nodes.mapTo(HashSet(slide.nodes.size)) { it.id }
        } else {
            activeCross.matchedPairs.mapTo(HashSet(activeCross.matchedPairs.size)) { it.nodeId }
        }

        val alphas = HashMap<String, Float>()
        val targetKind = if (isOutgoingA) TrackKind.TransitionExit else TrackKind.TransitionEnter
        for (track in activeCross.plan.tracks) {
            if (track.kind == targetKind) {
                val p = track.progressAt(clock.t).coerceIn(0f, 1f)
                alphas[track.nodeId] = if (isOutgoingA) (1f - p) else p
            }
        }
        unmatchedAlphas = alphas
    } else {
        suppressedIds = emptySet()
        unmatchedAlphas = emptyMap()
    }

    val renderCtx = DocumentNodeRenderContext(
        document = doc,
        slide = slide,
        sceneIndex = sceneIndex,
        beatIndex = currentBeatIndex,
        clock = clock,
        segmentPlan = segmentPlan,
        frameTransition = frameTransition,
        session = session,
        config = config,
        theme = theme,
        slidePalette = slidePalette,
        effectiveSolidBg = effectiveSolidBg,
        colorResolver = colorResolver,
        fontResolverHandle = fontResolverHandle,
        reducedMotion = reducedMotion,
        stageSize = stageSize,
        isAnimatedMount = isAnimatedMount,
        suppressedNodeIds = suppressedIds,
        unmatchedAlphaByNodeId = unmatchedAlphas,
        movableSpecimenSlotKeys = renderCache.movableSpecimenSlotKeys,
        incomingTransition = renderCache.incomingTransitionForSlide(slide.id),
        resolveNodeAtBeat = { rawNode, beat ->
            renderCache.resolveNodeAtBeat(slide.id, rawNode, beat)
        },
        resolveNodeAfterBuild = { rawNode, bIdx ->
            renderCache.resolveNodeAfterBuild(slide.id, rawNode, bIdx)
        },
        lookaheadStore = LocalCrossSlideLookaheadGeometryStore.current,
    )

    CompositionLocalProvider(
        LocalStageAnchorRegistry provides anchorRegistry,
        LocalSlidePalette provides slidePalette,
        LocalEffectiveBackgroundColor provides (effectiveSolidBg ?: theme.colors.background),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .testTag("doc-slide-${slide.id}")
                .then(slideLayerMod),
        ) {
            // Paint slide background inside the slide's own transformed/clipped layer EXCEPT during
            // active magicMove (where the host decorator paints cross-fading backgrounds underneath).
            if (!isActiveMagicMove) {
                DocumentFillBackgroundBox(
                    fill = slide.background,
                    fallbackColor = colorResolver.resolve("canvas", theme.colors.background),
                    colorResolver = colorResolver,
                    deckId = doc.id,
                    assetsRootDir = config.assetsRootDir,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            DocumentSlideRootNodes(
                nodes = slide.nodes,
                ctx = renderCtx,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * Partitions `magicMove`-matched specimens (direct or inside matched travelling containers) into
 * contiguous `magicMove` slide chains (`"chain:<firstSlide>..<lastSlide>:<nodeId>:<ref>"`).
 *
 * Keying `movableContentOf` slots by contiguous chain identity guarantees:
 * 1. Across every `magicMove` edge in a chain, `SceneA`, `Overlay`, and `SceneB` share ONE slot
 *    (strictly single-instance via `CrossSlideGeometry.owner`, zero remounts).
 * 2. Across any non-`magicMove` edge (`dissolve`, `push`, etc.), the two slides NEVER share a slot
 *    key (zero dual-invocation) AND the incoming slide keeps its own stable slot identity from
 *    `Cross` into `Rest` (zero post-transition remount).
 */
private fun computeMovableSpecimenSlotMap(
    nonSkipped: List<SlideSpec>,
    activeThemeSpec: DeckThemeSpec?,
    matchPairs: (fromSlideId: String, toSlideId: String) -> List<MatchedPair>,
): Map<MovableSpecimenKey, String> {
    if (nonSkipped.size < 2) return emptyMap()
    val edgesBySpecimen = HashMap<Pair<String, String>, HashSet<Int>>()

    fun recordMatchedSpecimens(fromNode: NodeSpec, toNode: NodeSpec, fromLastBeat: Int, edgeIdx: Int) {
        if (fromNode.kind == NodeKind.Specimen && toNode.kind == NodeKind.Specimen) {
            val fromRef = fromNode.specimen?.ref
            val toRef = toNode.specimen?.ref
            if (fromRef != null && fromRef == toRef) {
                edgesBySpecimen.getOrPut(fromNode.id to fromRef) { HashSet() }.add(edgeIdx)
            }
            return
        }
        if (fromNode.children.isEmpty() || toNode.children.isEmpty()) return
        val presentFrom = fromNode.children
            .filter { JsonMergePatch.isNodePresentAtBeat(it, fromLastBeat) }
            .associateBy { it.id }
        for (toChild in toNode.children) {
            if (!JsonMergePatch.isNodePresentAtStart(toChild)) continue
            val fromChild = presentFrom[toChild.id] ?: continue
            if (fromChild.kind != null && fromChild.kind == toChild.kind) {
                recordMatchedSpecimens(fromChild, toChild, fromLastBeat, edgeIdx)
            }
        }
    }

    val magicMoveSlideIndices = HashSet<Int>()
    for (i in 0 until nonSkipped.lastIndex) {
        val slideA = nonSkipped[i]
        val slideB = nonSkipped[i + 1]
        val tSpec = effectiveSlideTransition(slideA, activeThemeSpec)
        if (tSpec.type != TransitionType.MagicMove) continue
        magicMoveSlideIndices += i
        magicMoveSlideIndices += (i + 1)
        val fromLastBeat = slideA.beats.lastIndex.coerceAtLeast(0)
        val pairs = matchPairs(slideA.id, slideB.id)
        for (pair in pairs) {
            recordMatchedSpecimens(pair.fromNode, pair.toNode, fromLastBeat, i)
        }
    }

    val result = HashMap<MovableSpecimenKey, String>()
    for ((specPair, edges) in edgesBySpecimen) {
        val (nodeId, ref) = specPair
        val sortedEdges = edges.sorted()
        var idx = 0
        while (idx < sortedEdges.size) {
            val runStart = sortedEdges[idx]
            var runEnd = runStart + 1
            while (idx + 1 < sortedEdges.size && sortedEdges[idx + 1] == runEnd) {
                idx++
                runEnd++
            }
            val chainKey = "chain:${nonSkipped[runStart].id}..${nonSkipped[runEnd].id}:$nodeId:$ref"
            for (slideIdx in runStart..runEnd) {
                result[MovableSpecimenKey(nonSkipped[slideIdx].id, nodeId, ref)] = chainKey
            }
            idx++
        }
    }
    // Unmatched specimens on slides participating in any magicMove edge also migrate across
    // Scene <-> DocumentMagicMoveOverlayLayer <-> Scene and need a per-slide movable slot.
    fun recordUnmatchedSpecimens(slide: SlideSpec) {
        fun walk(node: NodeSpec) {
            if (node.kind == NodeKind.Specimen) {
                val ref = node.specimen?.ref
                if (ref != null) {
                    result.putIfAbsent(
                        MovableSpecimenKey(slide.id, node.id, ref),
                        "single:${slide.id}:${node.id}:$ref",
                    )
                }
            }
            for (child in node.children) walk(child)
        }
        for (node in slide.nodes) walk(node)
    }
    for (slideIdx in magicMoveSlideIndices) {
        recordUnmatchedSpecimens(nonSkipped[slideIdx])
    }
    return result
}
