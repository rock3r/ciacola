package dev.sebastiano.ciacola.document.render

import androidx.compose.animation.core.Transition
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import dev.bnorm.storyboard.easel.LocalStoryboardTransition
import dev.bnorm.storyboard.text.magic.MorphBy as MagicMorphBy
import dev.sebastiano.ciacola.EffectCall
import dev.sebastiano.ciacola.LocalDeckTextRenderer
import dev.sebastiano.ciacola.StyledText
import dev.sebastiano.ciacola.TextBuildKind
import dev.sebastiano.ciacola.TextContent
import dev.sebastiano.ciacola.core.fonts.DeckFontResolver
import dev.sebastiano.ciacola.core.fonts.FontAxes
import dev.sebastiano.ciacola.document.Curve
import dev.sebastiano.ciacola.document.DeckClock
import dev.sebastiano.ciacola.document.DeckDocument
import dev.sebastiano.ciacola.document.DeliveryBy
import dev.sebastiano.ciacola.document.DocSegment
import dev.sebastiano.ciacola.document.Fill
import dev.sebastiano.ciacola.document.MorphBy
import dev.sebastiano.ciacola.document.PieceSchedule
import dev.sebastiano.ciacola.document.RichTextValue
import dev.sebastiano.ciacola.document.SlideSpec
import dev.sebastiano.ciacola.document.Slot
import dev.sebastiano.ciacola.document.SlotPhase
import dev.sebastiano.ciacola.document.SurfaceSpec
import dev.sebastiano.ciacola.document.TextPayload
import dev.sebastiano.ciacola.document.TextStyleOverride
import dev.sebastiano.ciacola.document.rootSegment
import dev.sebastiano.ciacola.effects.v2.CurveV2
import dev.sebastiano.ciacola.effects.v2.EffectBuildKind
import dev.sebastiano.ciacola.text.v2.DeckTextRendererV2
import dev.sebastiano.ciacola.text.v2.EffectCallV2
import dev.sebastiano.ciacola.text.v2.LocalMagicTextV2Config
import dev.sebastiano.ciacola.text.v2.MagicTextV2Config
import dev.sebastiano.ciacola.text.v2.MagicTextV2Renderer
import dev.sebastiano.ciacola.text.v2.PartitionGranularity
import dev.sebastiano.ciacola.text.v2.PieceScheduleV2
import dev.sebastiano.ciacola.text.v2.PieceSlotV2
import dev.sebastiano.ciacola.text.v2.StyledTextV2
import dev.sebastiano.ciacola.text.v2.TextContentV2
import dev.sebastiano.ciacola.theme.DeckTheme
import kotlin.math.roundToInt

@Composable
actual fun DocumentTextPayloadView(
    renderState: () -> DocumentTextRenderState,
    surface: SurfaceSpec?,
    document: DeckDocument,
    theme: DeckTheme,
    colorResolver: DocumentColorResolver,
    fontResolverHandle: Any?,
    density: Density,
    reducedMotion: Boolean,
    deckId: String,
    assetsRootDir: String?,
    maxWidthPx: Int,
    modifier: Modifier,
) {
    val fontResolver = fontResolverHandle as? DeckFontResolver
    val activeThemeSpec = resolveActiveThemeSpec(document, colorResolver.themeSpec?.preset)
    val themeTypography = colorResolver.themeSpec?.typography ?: activeThemeSpec?.typography.orEmpty()

    val surfaceHorizontalPadPx = surface?.padding?.let { (it.left + it.right).roundToInt() } ?: 0
    LocalLookaheadTextMetricsHolder.current?.surfaceHorizontalPadPx = surfaceHorizontalPadPx
    val innerMaxWidthPx = if (maxWidthPx == Int.MAX_VALUE) {
        Int.MAX_VALUE
    } else {
        (maxWidthPx - surfaceHorizontalPadPx).coerceAtLeast(1)
    }

    val stageSize = Size(document.stage.width.toFloat(), document.stage.height.toFloat())
    val stageIntSize = IntSize(document.stage.width, document.stage.height)
    val v2Config = remember(fontResolver, reducedMotion, stageIntSize, stageSize) {
        MagicTextV2Config(
            fontResolver = fontResolver,
            stageSize = stageIntSize,
            reducedMotion = reducedMotion,
            stageSizeForEffects = stageSize,
        )
    }

    val storyboardTransition = LocalStoryboardTransition.current
    val holder = remember { DeferredTextPayloadHolder() }
    holder.bind(
        renderStateProvider = renderState,
        storyboardTransition = storyboardTransition,
        document = document,
        theme = theme,
        themeTypography = themeTypography,
        colorResolver = colorResolver,
        fontResolver = fontResolver,
        density = density,
        innerMaxWidthPx = innerMaxWidthPx,
        surfaceHorizontalPadPx = surfaceHorizontalPadPx,
        reducedMotion = reducedMotion,
    )

    val providedRenderer = LocalDeckTextRenderer.current

    @Composable
    fun InvokeTextRenderer(targetModifier: Modifier) {
        if (providedRenderer != null && providedRenderer !is DeckTextRendererV2) {
            CompositionLocalProvider(LocalMagicTextV2Config provides v2Config) {
                providedRenderer.Text(
                    content = holder.genericProvider,
                    modifier = targetModifier,
                )
            }
        } else {
            val v2Renderer = (providedRenderer as? DeckTextRendererV2) ?: DefaultMagicTextV2Renderer
            v2Renderer.TextNode(
                content = holder.v2Provider,
                config = v2Config,
                modifier = targetModifier,
            )
        }
    }

    if (surface != null) {
        val imageFill = surface.fill as? Fill.Image
        val padMod = surface.padding?.let { Modifier.padding(it.toPaddingValues(density)) } ?: Modifier
        val isMorphing = renderState() is DocumentTextRenderState.Morph
        Box(
            modifier = modifier.applyDocumentSurfaceChrome(
                surface = surface,
                colorResolver = colorResolver,
                density = density,
                clipToShape = (imageFill != null) || !isMorphing,
            ),
            propagateMinConstraints = true,
        ) {
            if (imageFill != null) {
                DocumentImageFillView(
                    deckId = deckId,
                    src = imageFill.image.src,
                    fit = imageFill.image.fit ?: "cover",
                    tint = imageFill.image.tint?.let { colorResolver.resolveOrNull(it) },
                    assetsRootDir = assetsRootDir,
                    modifier = Modifier.matchParentSize(),
                )
            }
            InvokeTextRenderer(padMod)
        }
    } else {
        InvokeTextRenderer(modifier)
    }
}

/**
 * Memoizes structural `StyledTextV2` and `PieceScheduleV2` conversions across frames and exposes
 * stable `() -> TextContentV2` and `() -> TextContent` lambdas so `TextNode` / `DeckTextRenderer.Text`
 * never recomposes on animated frame ticks (`recomps == 1`).
 */
private class DeferredTextPayloadHolder {
    private var plainGeneration: Int = 0
    private val bindGeneration = mutableIntStateOf(0)

    private var renderStateProvider: (() -> DocumentTextRenderState)? = null
    private var storyboardTransition: Transition<*>? = null
    private var lastDocument: DeckDocument? = null
    private var nonSkippedSlides: List<SlideSpec> = emptyList()
    private var theme: DeckTheme? = null
    private var themeTypography: Map<String, TextStyleOverride> = emptyMap()
    private var colorResolver: DocumentColorResolver? = null
    private var fontResolver: DeckFontResolver? = null
    private var density: Density = Density(1f)
    private var innerMaxWidthPx: Int = Int.MAX_VALUE
    private var surfaceHorizontalPadPx: Int = 0
    private var reducedMotion: Boolean = false

    // Structural cache for styled endpoints and schedules so per-frame `elapsedMs` ticks do not re-build styles.
    private data class StyledKey(
        val payload: TextPayload,
        val theme: DeckTheme,
        val themeTypography: Map<String, TextStyleOverride>,
        val colorResolver: DocumentColorResolver,
        val fontResolver: DeckFontResolver?,
        val densityValue: Float,
        val fontScale: Float,
        val maxWidthPx: Int,
        val minWidthPx: Int = 0,
        val disableLigatures: Boolean,
    )

    private var cachedStyledKeyA: StyledKey? = null
    private var cachedStyledValA: StyledTextV2? = null
    private var cachedStyledKeyB: StyledKey? = null
    private var cachedStyledValB: StyledTextV2? = null

    private var cachedMorphRawSched: PieceSchedule? = null
    private var cachedMorphTotalMs: Int = -1
    private var cachedMorphCurve: CurveV2? = null
    private var cachedMorphSchedV2: PieceScheduleV2? = null

    private var cachedBuildRawSched: PieceSchedule? = null
    private var cachedBuildTotalMs: Int = -1
    private var cachedBuildCurve: CurveV2? = null
    private var cachedBuildSchedV2: PieceScheduleV2? = null

    val v2Provider: () -> TextContentV2 = { resolveV2Content() }
    val genericProvider: () -> TextContent = { resolveGenericContent() }

    fun bind(
        renderStateProvider: () -> DocumentTextRenderState,
        storyboardTransition: Transition<*>?,
        document: DeckDocument,
        theme: DeckTheme,
        themeTypography: Map<String, TextStyleOverride>,
        colorResolver: DocumentColorResolver,
        fontResolver: DeckFontResolver?,
        density: Density,
        innerMaxWidthPx: Int,
        surfaceHorizontalPadPx: Int,
        reducedMotion: Boolean,
    ) {
        if (this.lastDocument !== document) {
            this.lastDocument = document
            this.nonSkippedSlides = document.slides.filterNot { it.skipped }
        }
        val changed = this.renderStateProvider !== renderStateProvider ||
            this.storyboardTransition !== storyboardTransition ||
            this.theme != theme ||
            this.themeTypography != themeTypography ||
            this.colorResolver != colorResolver ||
            this.fontResolver !== fontResolver ||
            this.density != density ||
            this.innerMaxWidthPx != innerMaxWidthPx ||
            this.surfaceHorizontalPadPx != surfaceHorizontalPadPx ||
            this.reducedMotion != reducedMotion
        this.renderStateProvider = renderStateProvider
        this.storyboardTransition = storyboardTransition
        this.theme = theme
        this.themeTypography = themeTypography
        this.colorResolver = colorResolver
        this.fontResolver = fontResolver
        this.density = density
        this.innerMaxWidthPx = innerMaxWidthPx
        this.surfaceHorizontalPadPx = surfaceHorizontalPadPx
        this.reducedMotion = reducedMotion
        // Notify layout/draw observers without ever reading bindGeneration during composition.
        if (changed) {
            plainGeneration += 1
            bindGeneration.intValue = plainGeneration
        }
    }

    private fun getOrBuildStyledA(
        payload: TextPayload,
        maxWidthPx: Int,
        disableLigatures: Boolean,
        minWidthPx: Int = 0,
    ): StyledTextV2 {
        val t = checkNotNull(theme)
        val cr = checkNotNull(colorResolver)
        val key = StyledKey(
            payload = payload,
            theme = t,
            themeTypography = themeTypography,
            colorResolver = cr,
            fontResolver = fontResolver,
            densityValue = density.density,
            fontScale = density.fontScale,
            maxWidthPx = maxWidthPx,
            minWidthPx = minWidthPx,
            disableLigatures = disableLigatures,
        )
        val existing = cachedStyledValA
        if (existing != null && cachedStyledKeyA == key) return existing
        val built = buildStyledTextV2(
            payload = payload,
            theme = t,
            themeTypography = themeTypography,
            colorResolver = cr,
            fontResolver = fontResolver,
            density = density,
            maxWidthPx = maxWidthPx,
            disableLigatures = disableLigatures,
            minWidthPx = minWidthPx,
        )
        cachedStyledKeyA = key
        cachedStyledValA = built
        return built
    }

    private fun getOrBuildStyledB(
        payload: TextPayload,
        maxWidthPx: Int,
        disableLigatures: Boolean,
        minWidthPx: Int = 0,
    ): StyledTextV2 {
        val t = checkNotNull(theme)
        val cr = checkNotNull(colorResolver)
        val key = StyledKey(
            payload = payload,
            theme = t,
            themeTypography = themeTypography,
            colorResolver = cr,
            fontResolver = fontResolver,
            densityValue = density.density,
            fontScale = density.fontScale,
            maxWidthPx = maxWidthPx,
            minWidthPx = minWidthPx,
            disableLigatures = disableLigatures,
        )
        val existing = cachedStyledValB
        if (existing != null && cachedStyledKeyB == key) return existing
        val built = buildStyledTextV2(
            payload = payload,
            theme = t,
            themeTypography = themeTypography,
            colorResolver = cr,
            fontResolver = fontResolver,
            density = density,
            maxWidthPx = maxWidthPx,
            disableLigatures = disableLigatures,
            minWidthPx = minWidthPx,
        )
        cachedStyledKeyB = key
        cachedStyledValB = built
        return built
    }

    private fun resolveLiveElapsedMs(
        expectedSegment: DocSegment?,
        trackStartMs: Int,
        totalDurationMs: Int,
        fallbackElapsedMs: Float,
    ): Float {
        val rootSeg = storyboardTransition?.rootSegment() ?: return fallbackElapsedMs
        if (expectedSegment == null || nonSkippedSlides.isEmpty()) return fallbackElapsedMs
        val liveClock = DeckClock.fromRootSegment(rootSeg, nonSkippedSlides)
        if (liveClock.segment != expectedSegment) return fallbackElapsedMs
        return (liveClock.t - trackStartMs.toFloat()).coerceIn(0f, totalDurationMs.coerceAtLeast(1).toFloat())
    }

    fun resolveV2Content(): TextContentV2 {
        @Suppress("UNUSED_VARIABLE")
        val gen = bindGeneration.intValue
        // Subscribe layout/draw phases directly to the root Animatic Transition's frame clock so
        // stationary text morphs & builds redraw on the exact same frame as live Transition ticks!
        val state = checkNotNull(renderStateProvider).invoke()
        return when (state) {
            is DocumentTextRenderState.Rest -> {
                val isCharNode = state.characterDeliveryNode || state.payload.morph?.by == MorphBy.Character
                val styled = getOrBuildStyledA(state.payload, innerMaxWidthPx, isCharNode)
                TextContentV2.Rest(content = styled, characterDeliveryNode = isCharNode)
            }

            is DocumentTextRenderState.Morph -> {
                val isCharNode = state.characterDeliveryNode ||
                    state.by == MorphBy.Character ||
                    state.from.morph?.by == MorphBy.Character ||
                    state.to.morph?.by == MorphBy.Character
                val fromMaxW = when {
                    state.fromMaxWidthIsInner -> state.fromMaxWidthPx
                    state.fromMaxWidthPx != Int.MAX_VALUE -> (state.fromMaxWidthPx - surfaceHorizontalPadPx).coerceAtLeast(1)
                    else -> innerMaxWidthPx
                }
                val toMaxW = when {
                    state.toMaxWidthIsInner -> state.toMaxWidthPx
                    state.toMaxWidthPx != Int.MAX_VALUE -> (state.toMaxWidthPx - surfaceHorizontalPadPx).coerceAtLeast(1)
                    else -> innerMaxWidthPx
                }
                val fromStyled = getOrBuildStyledA(state.from, fromMaxW, isCharNode, minWidthPx = state.fromMinWidthPx)
                val toStyled = getOrBuildStyledB(state.to, toMaxW, isCharNode, minWidthPx = state.toMinWidthPx)
                val schedV2 = cachedMorphSchedV2?.takeIf {
                    cachedMorphRawSched === state.schedule &&
                        cachedMorphTotalMs == state.totalDurationMs &&
                        cachedMorphCurve == state.moveCurve
                } ?: convertMorphPieceSchedule(
                    schedule = state.schedule,
                    totalDurationMs = state.totalDurationMs,
                    defaultCurve = state.moveCurve,
                ).also {
                    cachedMorphRawSched = state.schedule
                    cachedMorphTotalMs = state.totalDurationMs
                    cachedMorphCurve = state.moveCurve
                    cachedMorphSchedV2 = it
                }
                val liveElapsed = resolveLiveElapsedMs(
                    expectedSegment = state.clockSegment,
                    trackStartMs = state.trackStartMs,
                    totalDurationMs = state.totalDurationMs,
                    fallbackElapsedMs = state.elapsedMs,
                )
                TextContentV2.Morph(
                    from = fromStyled,
                    to = toStyled,
                    by = state.by.toMagicMorphBy(),
                    pruneStopwords = state.pruneStopwords,
                    schedule = schedV2,
                    elapsedMs = liveElapsed,
                    moveCurve = state.moveCurve,
                    interpolateLayoutSize = state.interpolateLayoutSize,
                    preserveEndpointMaxWidths = state.fromMaxWidthIsInner || state.toMaxWidthIsInner,
                    characterDeliveryNode = isCharNode,
                )
            }

            is DocumentTextRenderState.Build -> {
                val isCharNode = state.characterDeliveryNode ||
                    state.by == DeliveryBy.Character ||
                    state.payload.morph?.by == MorphBy.Character
                val styled = getOrBuildStyledA(state.payload, innerMaxWidthPx, isCharNode)
                val granularity = when (state.by) {
                    DeliveryBy.Character -> PartitionGranularity.Character
                    DeliveryBy.Word -> PartitionGranularity.Word
                    DeliveryBy.Paragraph -> PartitionGranularity.Line
                    DeliveryBy.Whole, DeliveryBy.Child -> PartitionGranularity.Word
                }
                val defaultCurve = state.curve ?: CurveV2.EaseInOut
                val schedV2 = cachedBuildSchedV2?.takeIf {
                    cachedBuildRawSched === state.schedule &&
                        cachedBuildTotalMs == state.totalDurationMs &&
                        cachedBuildCurve == defaultCurve
                } ?: convertBuildPieceSchedule(
                    schedule = state.schedule,
                    totalDurationMs = state.totalDurationMs,
                    defaultCurve = defaultCurve,
                ).also {
                    cachedBuildRawSched = state.schedule
                    cachedBuildTotalMs = state.totalDurationMs
                    cachedBuildCurve = defaultCurve
                    cachedBuildSchedV2 = it
                }
                val liveElapsed = resolveLiveElapsedMs(
                    expectedSegment = state.clockSegment,
                    trackStartMs = state.trackStartMs,
                    totalDurationMs = state.totalDurationMs,
                    fallbackElapsedMs = state.elapsedMs,
                )
                val caretColor = state.args.color("caretColor", Color.Unspecified)
                TextContentV2.Build(
                    content = styled,
                    granularity = granularity,
                    effect = EffectCallV2(
                        effectId = state.effectId,
                        kind = state.kind,
                        args = state.args,
                        curve = state.curve,
                    ),
                    schedule = schedV2,
                    elapsedMs = liveElapsed,
                    caretColor = caretColor,
                    characterDeliveryNode = isCharNode,
                )
            }
        }
    }

    fun resolveGenericContent(): TextContent {
        val v2 = resolveV2Content()
        val state = checkNotNull(renderStateProvider).invoke()
        return when (v2) {
            is TextContentV2.Rest -> TextContent.Rest(
                text = v2.content.toPublicStyledText(),
                characterDeliveryNode = v2.characterDeliveryNode,
            )
            is TextContentV2.Morph -> {
                val morphState = state as DocumentTextRenderState.Morph
                val sched = morphState.schedule ?: PieceSchedule(
                    listOf(
                        Slot(
                            address = Slot.SIZE_ADDRESS,
                            startMs = 0,
                            durationMs = morphState.totalDurationMs.coerceAtLeast(1),
                            curve = Curve.EaseInOut,
                            phase = SlotPhase.Move,
                        ),
                    ),
                )
                TextContent.Morph(
                    from = v2.from.toPublicStyledText(),
                    to = v2.to.toPublicStyledText(),
                    by = morphState.by,
                    schedule = sched,
                    localMs = v2.elapsedMs,
                    pruneStopwords = v2.pruneStopwords,
                    interpolateLayoutSize = v2.interpolateLayoutSize,
                    characterDeliveryNode = v2.characterDeliveryNode,
                    reducedMotion = reducedMotion,
                )
            }
            is TextContentV2.Build -> {
                val buildState = state as DocumentTextRenderState.Build
                val kind = when (buildState.kind) {
                    EffectBuildKind.Enter -> TextBuildKind.In
                    EffectBuildKind.Exit -> TextBuildKind.Out
                    EffectBuildKind.Emphasis -> TextBuildKind.Action
                }
                TextContent.Build(
                    text = v2.content.toPublicStyledText(),
                    kind = kind,
                    effect = EffectCall(effectId = buildState.effectId, args = buildState.args),
                    schedule = buildState.schedule ?: PieceSchedule(emptyList()),
                    localMs = v2.elapsedMs,
                    by = buildState.by,
                    characterDeliveryNode = v2.characterDeliveryNode,
                    reducedMotion = reducedMotion,
                )
            }
        }
    }

    private fun StyledTextV2.toPublicStyledText(): StyledText = StyledText(
        text = text,
        style = style,
        maxWidth = if (maxWidthPx == Int.MAX_VALUE) Float.POSITIVE_INFINITY else maxWidthPx.toFloat(),
    )
}

/**
 * Resolves effective `lineHeight` honoring both unitless `< 6.0` `em` multipliers and
 * resetting stale inherited theme `lineHeight` when an explicit `size` is set without `lineHeight` (#269).
 */
internal fun resolveEffectiveLineHeight(
    explicitLineHeight: Double?,
    explicitFontSize: Double?,
    effectiveFontSizeSp: TextUnit,
    inheritedLineHeight: TextUnit,
    density: Density,
): TextUnit = when {
    explicitLineHeight != null -> {
        if (explicitLineHeight < 6.0) {
            (explicitLineHeight * effectiveFontSizeSp.value.toDouble()).sp
        } else {
            explicitLineHeight.toStageSp(density)
        }
    }
    explicitFontSize != null -> TextUnit.Unspecified
    else -> inheritedLineHeight
}

internal fun buildStyledTextV2(
    payload: TextPayload,
    theme: DeckTheme,
    themeTypography: Map<String, TextStyleOverride>,
    colorResolver: DocumentColorResolver,
    fontResolver: DeckFontResolver?,
    density: Density,
    maxWidthPx: Int,
    disableLigatures: Boolean,
    minWidthPx: Int = 0,
): StyledTextV2 {
    val styleName = payload.style
    var textStyle = baseStyleFromTheme(styleName, theme)
    val themeOverride = styleName?.let { themeTypography[it] }
    if (themeOverride != null) {
        textStyle = applyTextStyleOverride(textStyle, themeOverride, colorResolver, density)
    }

    val resolvedFontName = payload.font ?: themeOverride?.font
    val resolvedColor = payload.color?.let { colorResolver.resolve(it, textStyle.color) }
        ?: if (textStyle.color == Color.Unspecified) colorResolver.resolve("ink", theme.colors.foreground) else textStyle.color
    val effectiveFontSizeSp = payload.size?.toStageSp(density) ?: textStyle.fontSize
    val effectiveLineHeight = resolveEffectiveLineHeight(
        explicitLineHeight = payload.lineHeight,
        explicitFontSize = payload.size,
        effectiveFontSizeSp = effectiveFontSizeSp,
        inheritedLineHeight = textStyle.lineHeight,
        density = density,
    )

    textStyle = textStyle.copy(
        color = resolvedColor,
        fontSize = effectiveFontSizeSp,
        fontWeight = payload.weight?.let { FontWeight(it.coerceIn(1, 1000)) } ?: textStyle.fontWeight,
        fontStyle = payload.italic?.let { if (it) FontStyle.Italic else FontStyle.Normal } ?: textStyle.fontStyle,
        lineHeight = effectiveLineHeight,
        letterSpacing = payload.tracking?.toStageSp(density) ?: textStyle.letterSpacing,
        textAlign = payload.align?.let { parseTextAlign(it) } ?: textStyle.textAlign,
    )

    val paragraphAxes = FontAxes.merge(themeOverride?.axes.orEmpty(), payload.axes)
    if (fontResolver != null) {
        textStyle = if (!resolvedFontName.isNullOrBlank()) {
            fontResolver.resolveTextStyle(
                familyName = resolvedFontName,
                style = textStyle,
                disableOptionalLigatures = disableLigatures,
                axes = paragraphAxes,
            )
        } else {
            fontResolver.applyAxes(fontResolver.clampStyleWeights(textStyle), paragraphAxes)
        }
    }

    val annotated = buildAnnotatedText(
        value = payload.value,
        baseStyle = textStyle,
        primaryFontName = resolvedFontName,
        paragraphAxes = paragraphAxes,
        colorResolver = colorResolver,
        fontResolver = fontResolver,
        density = density,
        disableLigatures = disableLigatures,
    )

    return StyledTextV2(
        text = annotated,
        style = textStyle,
        maxWidthPx = maxWidthPx,
        minWidthPx = minWidthPx.coerceIn(0, maxWidthPx.coerceAtLeast(1)),
        softWrap = true,
        maxLines = Int.MAX_VALUE,
    )
}

private fun baseStyleFromTheme(styleName: String?, theme: DeckTheme): TextStyle {
    val t = theme.typography
    return when (styleName?.lowercase()) {
        "hero" -> t.hero
        "title" -> t.title
        "heading", "headline", "subtitle" -> t.heading
        "subheading" -> t.subheading
        "bodyemphasis" -> t.bodyEmphasis
        "callout" -> t.callout
        "eyebrow" -> t.eyebrow
        "code" -> t.code
        "codeemphasis" -> t.codeEmphasis
        "codecompact" -> t.codeCompact
        "stamp" -> t.stamp
        "cardtitle" -> t.cardTitle
        "cardbody" -> t.cardBody
        "caption", "label" -> t.caption
        else -> t.body
    }
}

private fun applyTextStyleOverride(
    base: TextStyle,
    override: TextStyleOverride,
    colorResolver: DocumentColorResolver,
    density: Density,
): TextStyle {
    val effectiveFontSizeSp = override.size?.toStageSp(density) ?: base.fontSize
    val effectiveLineHeight = resolveEffectiveLineHeight(
        explicitLineHeight = override.lineHeight,
        explicitFontSize = override.size,
        effectiveFontSizeSp = effectiveFontSizeSp,
        inheritedLineHeight = base.lineHeight,
        density = density,
    )
    return base.copy(
        fontSize = effectiveFontSizeSp,
        fontWeight = override.weight?.let { FontWeight(it.coerceIn(1, 1000)) } ?: base.fontWeight,
        fontStyle = override.italic?.let { if (it) FontStyle.Italic else FontStyle.Normal } ?: base.fontStyle,
        color = override.color?.let { colorResolver.resolve(it, base.color) } ?: base.color,
        lineHeight = effectiveLineHeight,
        letterSpacing = override.tracking?.toStageSp(density) ?: base.letterSpacing,
        textAlign = override.align?.let { parseTextAlign(it) } ?: base.textAlign,
    )
}

private fun buildAnnotatedText(
    value: RichTextValue,
    baseStyle: TextStyle,
    primaryFontName: String?,
    paragraphAxes: Map<String, Double>,
    colorResolver: DocumentColorResolver,
    fontResolver: DeckFontResolver?,
    density: Density,
    disableLigatures: Boolean,
): AnnotatedString = when (value) {
    is RichTextValue.Plain -> AnnotatedString(value.text)
    is RichTextValue.Runs -> buildAnnotatedString {
        for (run in value.runs) {
            val decorations = buildList {
                if (run.underline == true) add(TextDecoration.Underline)
                if (run.strike == true) add(TextDecoration.LineThrough)
            }
            val textDecoration = if (decorations.isEmpty()) null else TextDecoration.combine(decorations)
            var span = SpanStyle(
                color = run.color?.let { colorResolver.resolve(it, Color.Unspecified) } ?: Color.Unspecified,
                fontSize = run.size?.toStageSp(density) ?: TextUnit.Unspecified,
                fontWeight = run.weight?.let { FontWeight(it.coerceIn(1, 1000)) },
                fontStyle = run.italic?.let { if (it) FontStyle.Italic else FontStyle.Normal },
                letterSpacing = run.tracking?.toStageSp(density) ?: TextUnit.Unspecified,
                textDecoration = textDecoration,
                baselineShift = run.baselineShift?.let { BaselineShift(it.toFloat()) },
                background = run.background?.let { colorResolver.resolve(it, Color.Unspecified) } ?: Color.Unspecified,
            )
            val runFontName = run.font ?: primaryFontName
            val runAxes = FontAxes.merge(paragraphAxes, run.axes)
            if (fontResolver != null && !runFontName.isNullOrBlank()) {
                span = fontResolver.resolveSpanStyle(
                    familyName = runFontName,
                    style = span,
                    fallbackFontStyle = baseStyle.fontStyle ?: FontStyle.Normal,
                    disableOptionalLigatures = disableLigatures,
                    axes = runAxes,
                )
            } else if (fontResolver != null && run.axes.isNotEmpty()) {
                // No explicit family: rebind the paragraph's resolver-managed family with the
                // run's merged coordinates.
                val withFamily = span.copy(fontFamily = baseStyle.fontFamily)
                val rebound = fontResolver.applyAxes(withFamily, runAxes)
                if (rebound !== withFamily) span = rebound
            }
            val startIdx = length
            append(run.text)
            if (span != SpanStyle()) {
                addStyle(span, startIdx, length)
            }
        }
    }
}

private fun convertMorphPieceSchedule(
    schedule: PieceSchedule?,
    totalDurationMs: Int,
    defaultCurve: CurveV2,
): PieceScheduleV2 {
    if (schedule == null || schedule.slots.isEmpty()) {
        val dur = totalDurationMs.coerceAtLeast(1)
        return PieceScheduleV2(
            totalDurationMs = dur,
            slots = mapOf(
                Slot.SIZE_ADDRESS to PieceSlotV2(
                    address = Slot.SIZE_ADDRESS,
                    startMs = 0,
                    endMs = dur,
                    curve = defaultCurve,
                ),
            ),
            defaultCurve = defaultCurve,
        )
    }
    return MagicTextV2Renderer.convertPieceSchedule(
        schedule = schedule,
        minTotalDurationMs = totalDurationMs,
        defaultCurve = defaultCurve,
    )
}

private fun convertBuildPieceSchedule(
    schedule: PieceSchedule?,
    totalDurationMs: Int,
    defaultCurve: CurveV2,
): PieceScheduleV2 {
    if (schedule == null || schedule.slots.isEmpty()) {
        return PieceScheduleV2(
            totalDurationMs = totalDurationMs.coerceAtLeast(1),
            slots = emptyMap(),
            defaultCurve = defaultCurve,
        )
    }
    return MagicTextV2Renderer.convertPieceSchedule(
        schedule = schedule,
        minTotalDurationMs = totalDurationMs,
        defaultCurve = defaultCurve,
    )
}

private fun MorphBy.toMagicMorphBy(): MagicMorphBy = when (this) {
    MorphBy.Word -> MagicMorphBy.Word
    MorphBy.Character -> MagicMorphBy.Character
    MorphBy.Line, MorphBy.Paragraph -> MagicMorphBy.Line
}

private fun parseTextAlign(align: String): TextAlign = when (align.lowercase()) {
    "center" -> TextAlign.Center
    "end", "right" -> TextAlign.End
    "justify" -> TextAlign.Justify
    else -> TextAlign.Start
}

/**
 * Measures a document [TextPayload] through the exact runtime `buildStyledTextV2` +
 * `TwoConditionPartitioner.measureWholeParagraph` pipeline for deterministic wrap auditing.
 */
fun measureDocumentTextLayoutForAudit(
    payload: TextPayload,
    document: DeckDocument,
    theme: DeckTheme,
    colorResolver: DocumentColorResolver,
    fontResolver: DeckFontResolver?,
    density: Density,
    measurer: androidx.compose.ui.text.TextMeasurer,
    maxWidthPx: Int,
    disableLigatures: Boolean = false,
): androidx.compose.ui.text.TextLayoutResult {
    val activeThemeSpec = resolveActiveThemeSpec(document, colorResolver.themeSpec?.preset)
    val themeTypography = colorResolver.themeSpec?.typography ?: activeThemeSpec?.typography.orEmpty()
    val styled = buildStyledTextV2(
        payload = payload,
        theme = theme,
        themeTypography = themeTypography,
        colorResolver = colorResolver,
        fontResolver = fontResolver,
        density = density,
        maxWidthPx = maxWidthPx,
        disableLigatures = disableLigatures,
    )
    return dev.sebastiano.ciacola.text.v2.TwoConditionPartitioner.measureWholeParagraph(styled, measurer)
}
