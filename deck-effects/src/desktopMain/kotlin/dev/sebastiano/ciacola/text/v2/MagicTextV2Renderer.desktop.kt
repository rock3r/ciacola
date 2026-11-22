package dev.sebastiano.ciacola.text.v2

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.getTextLayoutResult
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import dev.bnorm.storyboard.text.magic.MorphBy as MagicMorphBy
import dev.sebastiano.ciacola.StyledText
import dev.sebastiano.ciacola.TextBuildKind
import dev.sebastiano.ciacola.TextContent
import dev.sebastiano.ciacola.document.DeliveryBy
import dev.sebastiano.ciacola.document.MorphBy
import dev.sebastiano.ciacola.document.PieceSchedule
import dev.sebastiano.ciacola.document.render.LocalLookaheadTextMetricsHolder
import dev.sebastiano.ciacola.document.render.toCurveV2
import dev.sebastiano.ciacola.effects.v2.BuildEffects
import dev.sebastiano.ciacola.effects.v2.CurveV2
import dev.sebastiano.ciacola.effects.v2.EffectBuildKind
import kotlin.math.roundToInt

/**
 * Single-`DrawScope` Compose Desktop implementation of `MagicText v2`.
 *
 * Guarantees:
 * - Zero child composables and zero child layout nodes per token; single merged semantics node
 *   exposing `text` and `getTextLayoutResult` for PDF export and slide linting.
 * - Deferred `() -> TextContent` / `() -> TextContentV2` reading in layout and draw phases so
 *   animated frames never recompose and allocate zero managed heap bytes on layer-free draws.
 * - `Rest`: single `drawText(wholeLayout)` (100% pixel-identical to `BasicText`).
 * - Two-Condition Admission + Region Coarsening via the ONE shared face-assignment
 *   and Condition 1 predicate (`DeckFontResolver.certifyParagraphCharacterSafety`), fail-closed
 *   Condition 2 pixel readback, and unbounded `saveLayer(null, paint)` fades.
 * - Strictly planner-driven slot timing joined by UTF-16 range intersection for both Morph and Build
 *   (zero renderer-invented timing or keyboard jitter).
 */
class MagicTextV2Renderer(
    val caches: MagicTextV2Caches = MagicTextV2Caches(),
) : DeckTextRendererV2 {

    /**
     * Optional zero-allocation test instrumentation hooks bracketing the draw-phase body inside
     * `drawBehind` during composable-path allocation profiling. Zero cost when `null`.
     */
    internal var onBeforeDrawPhaseForTest: (() -> Unit)? = null
    internal var onAfterDrawPhaseForTest: (() -> Unit)? = null

    private class NodeHotCache {
        var lastKind: Int = 0 // 1=Rest, 2=Morph, 3=Build
        var lastMinConstraintWidth: Int = 0
        var lastConstraintWidth: Int = Int.MAX_VALUE
        var lastDensity: Float = Float.NaN
        var lastFontScale: Float = Float.NaN
        var lastLayoutDirection: LayoutDirection? = null
        var lastConfig: MagicTextV2Config? = null

        // Structural Rest fields
        var lastRestStyled: StyledTextV2? = null
        var lastRestCharNode: Boolean = false
        var cachedRestLayout: TextLayoutResult? = null

        // Structural Morph fields
        var lastMorphFrom: StyledTextV2? = null
        var lastMorphTo: StyledTextV2? = null
        var lastMorphBy: MagicMorphBy? = null
        var lastMorphPrune: Boolean = false
        var lastMorphSchedule: PieceScheduleV2? = null
        var lastMorphMoveCurve: CurveV2? = null
        var lastMorphCharNode: Boolean = false
        var cachedMorphPlan: MorphPlanV2? = null

        // Structural Build fields
        var lastBuildStyled: StyledTextV2? = null
        var lastBuildGranularity: PartitionGranularity? = null
        var lastBuildCharNode: Boolean = false
        var lastBuildSchedule: PieceScheduleV2? = null
        var cachedBuildPlan: BuildPlanV2? = null

        // Cached TextContent -> V2 conversions to keep DeckTextRenderer.Text zero-alloc on hot frames.
        var lastRawStyledA: StyledText? = null
        var cachedStyledV2A: StyledTextV2? = null
        var lastRawStyledB: StyledText? = null
        var cachedStyledV2B: StyledTextV2? = null
        var lastRawSchedule: PieceSchedule? = null
        var cachedScheduleV2: PieceScheduleV2? = null
        var lastRawEffectId: String? = null
        var lastRawEffectArgs: dev.sebastiano.ciacola.effects.v2.EffectArgs? = null
        var lastRawEffectKind: EffectBuildKind? = null
        var cachedBuildEffectV2: EffectCallV2? = null

        var targetText: AnnotatedString = AnnotatedString("")
        var targetWholeLayout: TextLayoutResult? = null
        val semanticsVersion = mutableIntStateOf(0)

        fun updateSemanticsIfChanged(newText: AnnotatedString, newLayout: TextLayoutResult) {
            if (targetWholeLayout !== newLayout || targetText != newText) {
                targetText = newText
                targetWholeLayout = newLayout
                semanticsVersion.intValue = semanticsVersion.intValue + 1
            }
        }

        fun convertStyledA(raw: StyledText): StyledTextV2 {
            if (lastRawStyledA === raw && cachedStyledV2A != null) return cachedStyledV2A!!
            val converted = raw.toStyledTextV2()
            lastRawStyledA = raw
            cachedStyledV2A = converted
            return converted
        }

        fun convertStyledB(raw: StyledText): StyledTextV2 {
            if (lastRawStyledB === raw && cachedStyledV2B != null) return cachedStyledV2B!!
            val converted = raw.toStyledTextV2()
            lastRawStyledB = raw
            cachedStyledV2B = converted
            return converted
        }

        fun convertSchedule(raw: PieceSchedule): PieceScheduleV2 {
            if (lastRawSchedule === raw && cachedScheduleV2 != null) return cachedScheduleV2!!
            val converted = convertPieceSchedule(raw)
            lastRawSchedule = raw
            cachedScheduleV2 = converted
            return converted
        }

        fun convertBuildEffect(
            effectId: String,
            kind: EffectBuildKind,
            args: dev.sebastiano.ciacola.effects.v2.EffectArgs,
        ): EffectCallV2 {
            val cached = cachedBuildEffectV2
            if (cached != null && lastRawEffectId == effectId && lastRawEffectKind == kind && lastRawEffectArgs == args) {
                return cached
            }
            val created = EffectCallV2(effectId = effectId, kind = kind, args = args)
            lastRawEffectId = effectId
            lastRawEffectKind = kind
            lastRawEffectArgs = args
            cachedBuildEffectV2 = created
            return created
        }

        fun resolveRest(
            caches: MagicTextV2Caches,
            styled: StyledTextV2,
            characterDeliveryNode: Boolean,
            maxConstraintWidthPx: Int,
            measurer: TextMeasurer,
            density: Density,
            layoutDirection: LayoutDirection,
            config: MagicTextV2Config,
            minConstraintWidthPx: Int = 0,
        ): TextLayoutResult {
            val cached = cachedRestLayout
            if (lastKind == 1 && cached != null &&
                (lastRestStyled === styled || lastRestStyled == styled) &&
                lastRestCharNode == characterDeliveryNode &&
                lastMinConstraintWidth == minConstraintWidthPx &&
                lastConstraintWidth == maxConstraintWidthPx &&
                lastDensity == density.density &&
                lastFontScale == density.fontScale &&
                lastLayoutDirection == layoutDirection &&
                (lastConfig === config || lastConfig == config)
            ) {
                return cached
            }
            val bounded = styled.withBoundedWidth(minConstraintWidthPx, maxConstraintWidthPx)
            val layout = caches.getOrMeasureWholeLayout(
                styled = bounded,
                characterDeliveryNode = characterDeliveryNode,
                measurer = measurer,
                density = density,
                layoutDirection = layoutDirection,
                config = config,
            )
            lastKind = 1
            lastRestStyled = styled
            lastRestCharNode = characterDeliveryNode
            lastMinConstraintWidth = minConstraintWidthPx
            lastConstraintWidth = maxConstraintWidthPx
            lastDensity = density.density
            lastFontScale = density.fontScale
            lastLayoutDirection = layoutDirection
            lastConfig = config
            cachedRestLayout = layout
            updateSemanticsIfChanged(layout.layoutInput.text, layout)
            return layout
        }

        fun resolveMorph(
            caches: MagicTextV2Caches,
            from: StyledTextV2,
            to: StyledTextV2,
            by: MagicMorphBy,
            pruneStopwords: Boolean,
            schedule: PieceScheduleV2,
            moveCurve: CurveV2,
            characterDeliveryNode: Boolean,
            maxConstraintWidthPx: Int,
            measurer: TextMeasurer,
            density: Density,
            layoutDirection: LayoutDirection,
            config: MagicTextV2Config,
            preserveEndpointMaxWidths: Boolean = false,
            minConstraintWidthPx: Int = 0,
        ): MorphPlanV2 {
            val effectiveMinConstraintWidth = if (preserveEndpointMaxWidths) 0 else minConstraintWidthPx
            val effectiveConstraintWidth = if (preserveEndpointMaxWidths) Int.MAX_VALUE else maxConstraintWidthPx
            val cached = cachedMorphPlan
            if (lastKind == 2 && cached != null &&
                (lastMorphFrom === from || lastMorphFrom == from) &&
                (lastMorphTo === to || lastMorphTo == to) &&
                lastMorphBy == by &&
                lastMorphPrune == pruneStopwords &&
                (lastMorphSchedule === schedule || lastMorphSchedule == schedule) &&
                (lastMorphMoveCurve === moveCurve || lastMorphMoveCurve == moveCurve) &&
                lastMorphCharNode == characterDeliveryNode &&
                lastMinConstraintWidth == effectiveMinConstraintWidth &&
                lastConstraintWidth == effectiveConstraintWidth &&
                lastDensity == density.density &&
                lastFontScale == density.fontScale &&
                lastLayoutDirection == layoutDirection &&
                (lastConfig === config || lastConfig == config)
            ) {
                return cached
            }
            val boundedFrom = if (preserveEndpointMaxWidths) from else from.withBoundedWidth(effectiveMinConstraintWidth, effectiveConstraintWidth)
            val boundedTo = if (preserveEndpointMaxWidths) to else to.withBoundedWidth(effectiveMinConstraintWidth, effectiveConstraintWidth)
            val plan = caches.getOrBuildMorphPlan(
                from = boundedFrom,
                to = boundedTo,
                by = by,
                pruneStopwords = pruneStopwords,
                schedule = schedule,
                defaultMoveCurve = moveCurve,
                characterDeliveryNode = characterDeliveryNode,
                measurer = measurer,
                density = density,
                layoutDirection = layoutDirection,
                config = config,
            )
            lastKind = 2
            lastMorphFrom = from
            lastMorphTo = to
            lastMorphBy = by
            lastMorphPrune = pruneStopwords
            lastMorphSchedule = schedule
            lastMorphMoveCurve = moveCurve
            lastMorphCharNode = characterDeliveryNode
            lastMinConstraintWidth = effectiveMinConstraintWidth
            lastConstraintWidth = effectiveConstraintWidth
            lastDensity = density.density
            lastFontScale = density.fontScale
            lastLayoutDirection = layoutDirection
            lastConfig = config
            cachedMorphPlan = plan
            updateSemanticsIfChanged(plan.toPartition.wholeText, plan.toPartition.wholeLayout)
            return plan
        }

        fun resolveBuild(
            caches: MagicTextV2Caches,
            styled: StyledTextV2,
            granularity: PartitionGranularity,
            characterDeliveryNode: Boolean,
            schedule: PieceScheduleV2,
            maxConstraintWidthPx: Int,
            measurer: TextMeasurer,
            density: Density,
            layoutDirection: LayoutDirection,
            config: MagicTextV2Config,
            minConstraintWidthPx: Int = 0,
        ): BuildPlanV2 {
            val cached = cachedBuildPlan
            if (lastKind == 3 && cached != null &&
                (lastBuildStyled === styled || lastBuildStyled == styled) &&
                lastBuildGranularity == granularity &&
                lastBuildCharNode == characterDeliveryNode &&
                (lastBuildSchedule === schedule || lastBuildSchedule == schedule) &&
                lastMinConstraintWidth == minConstraintWidthPx &&
                lastConstraintWidth == maxConstraintWidthPx &&
                lastDensity == density.density &&
                lastFontScale == density.fontScale &&
                lastLayoutDirection == layoutDirection &&
                (lastConfig === config || lastConfig == config)
            ) {
                return cached
            }
            val bounded = styled.withBoundedWidth(minConstraintWidthPx, maxConstraintWidthPx)
            val plan = caches.getOrBuildBuildPlan(
                styled = bounded,
                granularity = granularity,
                characterDeliveryNode = characterDeliveryNode,
                schedule = schedule,
                measurer = measurer,
                density = density,
                layoutDirection = layoutDirection,
                config = config,
            )
            lastKind = 3
            lastBuildStyled = styled
            lastBuildGranularity = granularity
            lastBuildCharNode = characterDeliveryNode
            lastBuildSchedule = schedule
            lastMinConstraintWidth = minConstraintWidthPx
            lastConstraintWidth = maxConstraintWidthPx
            lastDensity = density.density
            lastFontScale = density.fontScale
            lastLayoutDirection = layoutDirection
            lastConfig = config
            cachedBuildPlan = plan
            updateSemanticsIfChanged(plan.partition.wholeText, plan.partition.wholeLayout)
            return plan
        }
    }

    @Composable
    override fun Text(
        content: () -> TextContent,
        modifier: Modifier,
    ) {
        val baseConfig = LocalMagicTextV2Config.current
        val measurer = rememberTextMeasurer(cacheSize = baseConfig.maxLayoutCacheEntries)
        val density = LocalDensity.current
        val layoutDirection = LocalLayoutDirection.current
        val hotCache = remember { NodeHotCache() }
        val lookaheadHolder = LocalLookaheadTextMetricsHolder.current

        Layout(
            modifier = modifier
                .semantics(mergeDescendants = true) {
                    @Suppress("UNUSED_VARIABLE")
                    val ver = hotCache.semanticsVersion.intValue
                    if (hotCache.targetWholeLayout == null) {
                        resolveTextContentForSemantics(content(), hotCache, measurer, density, layoutDirection, baseConfig)
                    }
                    this.text = hotCache.targetText
                    getTextLayoutResult { out ->
                        hotCache.targetWholeLayout?.let {
                            out.add(it)
                            true
                        } ?: false
                    }
                }
                .drawBehind {
                    onBeforeDrawPhaseForTest?.invoke()
                    try {
                        drawDeferredTextContent(content(), hotCache, measurer, density, layoutDirection, baseConfig)
                    } finally {
                        onAfterDrawPhaseForTest?.invoke()
                    }
                },
        ) { _, constraints ->
            val minW = constraints.minWidth
            val maxW = if (constraints.hasBoundedWidth) constraints.maxWidth else Int.MAX_VALUE
            val active = content()
            val desired = measureDeferredTextContent(
                active = active,
                minW = minW,
                maxW = maxW,
                skipMorphPlanInLookahead = isLookingAhead && lookaheadHolder == null,
                hotCache = hotCache,
                measurer = measurer,
                density = density,
                layoutDirection = layoutDirection,
                baseConfig = baseConfig,
            )
            if (isLookingAhead && lookaheadHolder != null) {
                val styledMaxW = when (active) {
                    is TextContent.Rest -> active.text.maxWidth
                    is TextContent.Build -> active.text.maxWidth
                    is TextContent.Morph -> Float.POSITIVE_INFINITY
                }
                val effectiveMaxW = if (styledMaxW.isFinite() && styledMaxW > 0f) styledMaxW.roundToInt() else maxW
                lookaheadHolder.constraintMinWidthPx = minW
                lookaheadHolder.constraintMaxWidthPx = effectiveMaxW
                lookaheadHolder.layoutWidthPx = desired.width
            }
            val w = desired.width.coerceIn(constraints.minWidth, constraints.maxWidth)
            val h = desired.height.coerceIn(constraints.minHeight, constraints.maxHeight)
            layout(w, h) {}
        }
    }

    @Composable
    override fun TextNode(
        content: () -> TextContentV2,
        modifier: Modifier,
        config: MagicTextV2Config,
    ) {
        val measurer = rememberTextMeasurer(cacheSize = config.maxLayoutCacheEntries)
        val density = LocalDensity.current
        val layoutDirection = LocalLayoutDirection.current
        val hotCache = remember { NodeHotCache() }
        val lookaheadHolder = LocalLookaheadTextMetricsHolder.current

        Layout(
            modifier = modifier
                .semantics(mergeDescendants = true) {
                    @Suppress("UNUSED_VARIABLE")
                    val ver = hotCache.semanticsVersion.intValue
                    if (hotCache.targetWholeLayout == null) {
                        resolveV2ForSemantics(content(), hotCache, measurer, density, layoutDirection, config)
                    }
                    this.text = hotCache.targetText
                    getTextLayoutResult { out ->
                        hotCache.targetWholeLayout?.let {
                            out.add(it)
                            true
                        } ?: false
                    }
                }
                .drawBehind {
                    onBeforeDrawPhaseForTest?.invoke()
                    try {
                        drawDeferredV2(content(), hotCache, measurer, density, layoutDirection, config)
                    } finally {
                        onAfterDrawPhaseForTest?.invoke()
                    }
                },
        ) { _, constraints ->
            val minW = constraints.minWidth
            val maxW = if (constraints.hasBoundedWidth) constraints.maxWidth else Int.MAX_VALUE
            val active = content()
            val desired = measureDeferredV2(
                active = active,
                minW = minW,
                maxW = maxW,
                skipMorphPlanInLookahead = isLookingAhead && lookaheadHolder == null,
                hotCache = hotCache,
                measurer = measurer,
                density = density,
                layoutDirection = layoutDirection,
                config = config,
            )
            if (isLookingAhead && lookaheadHolder != null) {
                val effectiveMaxW = when (active) {
                    is TextContentV2.Rest ->
                        if (active.content.maxWidthPx != Int.MAX_VALUE) active.content.maxWidthPx else maxW
                    is TextContentV2.Build ->
                        if (active.content.maxWidthPx != Int.MAX_VALUE) active.content.maxWidthPx else maxW
                    is TextContentV2.Morph -> maxW
                }
                lookaheadHolder.constraintMinWidthPx = minW
                lookaheadHolder.constraintMaxWidthPx = effectiveMaxW
                lookaheadHolder.layoutWidthPx = desired.width
            }
            val w = desired.width.coerceIn(constraints.minWidth, constraints.maxWidth)
            val h = desired.height.coerceIn(constraints.minHeight, constraints.maxHeight)
            layout(w, h) {}
        }
    }

    private fun resolveTextContentForSemantics(
        active: TextContent,
        hotCache: NodeHotCache,
        measurer: TextMeasurer,
        density: Density,
        layoutDirection: LayoutDirection,
        baseConfig: MagicTextV2Config,
    ) {
        measureDeferredTextContent(active, 0, Int.MAX_VALUE, false, hotCache, measurer, density, layoutDirection, baseConfig)
    }

    private fun measureDeferredTextContent(
        active: TextContent,
        minW: Int,
        maxW: Int,
        skipMorphPlanInLookahead: Boolean,
        hotCache: NodeHotCache,
        measurer: TextMeasurer,
        density: Density,
        layoutDirection: LayoutDirection,
        baseConfig: MagicTextV2Config,
    ): IntSize = when (active) {
        is TextContent.Rest -> {
            val styled = hotCache.convertStyledA(active.text)
            hotCache.resolveRest(
                caches, styled, active.characterDeliveryNode, maxW, measurer, density, layoutDirection, baseConfig, minW,
            ).size
        }
        is TextContent.Morph -> {
            val effConfig = if (active.reducedMotion == baseConfig.reducedMotion) baseConfig else baseConfig.copy(reducedMotion = active.reducedMotion)
            val fromV2 = hotCache.convertStyledA(active.from)
            val toV2 = hotCache.convertStyledB(active.to)
            val schedV2 = hotCache.convertSchedule(active.schedule)
            if (skipMorphPlanInLookahead) {
                measureMorphLookaheadSize(fromV2, toV2, active.characterDeliveryNode, minW, maxW, false, schedV2, active.localMs, active.interpolateLayoutSize, measurer, density, layoutDirection, effConfig)
            } else {
                val plan = hotCache.resolveMorph(
                    caches = caches,
                    from = fromV2,
                    to = toV2,
                    by = active.by.toMagicMorphBy(),
                    pruneStopwords = active.pruneStopwords,
                    schedule = schedV2,
                    moveCurve = schedV2.defaultCurve,
                    characterDeliveryNode = active.characterDeliveryNode,
                    maxConstraintWidthPx = maxW,
                    measurer = measurer,
                    density = density,
                    layoutDirection = layoutDirection,
                    config = effConfig,
                    minConstraintWidthPx = minW,
                )
                computeMorphMeasuredSize(plan, schedV2, active.localMs, active.interpolateLayoutSize)
            }
        }
        is TextContent.Build -> {
            val effConfig = if (active.reducedMotion == baseConfig.reducedMotion) baseConfig else baseConfig.copy(reducedMotion = active.reducedMotion)
            val styledV2 = hotCache.convertStyledA(active.text)
            val schedV2 = hotCache.convertSchedule(active.schedule)
            val plan = hotCache.resolveBuild(
                caches = caches,
                styled = styledV2,
                granularity = active.by.toPartitionGranularity(),
                characterDeliveryNode = active.characterDeliveryNode,
                schedule = schedV2,
                maxConstraintWidthPx = maxW,
                measurer = measurer,
                density = density,
                layoutDirection = layoutDirection,
                config = effConfig,
                minConstraintWidthPx = minW,
            )
            plan.partition.wholeLayout.size
        }
    }

    private fun DrawScope.drawDeferredTextContent(
        active: TextContent,
        hotCache: NodeHotCache,
        measurer: TextMeasurer,
        density: Density,
        layoutDirection: LayoutDirection,
        baseConfig: MagicTextV2Config,
    ) {
        val minW = hotCache.lastMinConstraintWidth
        val maxW = hotCache.lastConstraintWidth
        when (active) {
            is TextContent.Rest -> {
                val styled = hotCache.convertStyledA(active.text)
                val layout = hotCache.resolveRest(
                    caches, styled, active.characterDeliveryNode, maxW, measurer, density, layoutDirection, baseConfig, minW,
                )
                MagicTextV2Drawing.run { drawRestParagraph(layout) }
            }
            is TextContent.Morph -> {
                val effConfig = if (active.reducedMotion == baseConfig.reducedMotion) baseConfig else baseConfig.copy(reducedMotion = active.reducedMotion)
                val fromV2 = hotCache.convertStyledA(active.from)
                val toV2 = hotCache.convertStyledB(active.to)
                val schedV2 = hotCache.convertSchedule(active.schedule)
                val plan = hotCache.resolveMorph(
                    caches = caches,
                    from = fromV2,
                    to = toV2,
                    by = active.by.toMagicMorphBy(),
                    pruneStopwords = active.pruneStopwords,
                    schedule = schedV2,
                    moveCurve = schedV2.defaultCurve,
                    characterDeliveryNode = active.characterDeliveryNode,
                    maxConstraintWidthPx = maxW,
                    measurer = measurer,
                    density = density,
                    layoutDirection = layoutDirection,
                    config = effConfig,
                    minConstraintWidthPx = minW,
                )
                MagicTextV2Drawing.run {
                    drawMorphFrame(
                        plan = plan,
                        elapsedMs = active.localMs,
                        totalDurationMs = schedV2.totalDurationMs,
                        exitEffect = DEFAULT_MORPH_EXIT_EFFECT,
                        enterEffect = DEFAULT_MORPH_ENTER_EFFECT,
                        moveCurve = schedV2.defaultCurve,
                        config = effConfig,
                    )
                }
            }
            is TextContent.Build -> {
                val effConfig = if (active.reducedMotion == baseConfig.reducedMotion) baseConfig else baseConfig.copy(reducedMotion = active.reducedMotion)
                val styledV2 = hotCache.convertStyledA(active.text)
                val schedV2 = hotCache.convertSchedule(active.schedule)
                val plan = hotCache.resolveBuild(
                    caches = caches,
                    styled = styledV2,
                    granularity = active.by.toPartitionGranularity(),
                    characterDeliveryNode = active.characterDeliveryNode,
                    schedule = schedV2,
                    maxConstraintWidthPx = maxW,
                    measurer = measurer,
                    density = density,
                    layoutDirection = layoutDirection,
                    config = effConfig,
                    minConstraintWidthPx = minW,
                )
                val eff = hotCache.convertBuildEffect(
                    effectId = active.effect.effectId,
                    kind = active.kind.toEffectBuildKind(),
                    args = active.effect.args,
                )
                MagicTextV2Drawing.run {
                    drawBuildFrame(
                        plan = plan,
                        elapsedMs = active.localMs,
                        totalDurationMs = schedV2.totalDurationMs,
                        defaultCurve = schedV2.defaultCurve,
                        effect = eff,
                        caretColor = Color.Unspecified,
                        config = effConfig,
                    )
                }
            }
        }
    }

    private fun resolveV2ForSemantics(
        active: TextContentV2,
        hotCache: NodeHotCache,
        measurer: TextMeasurer,
        density: Density,
        layoutDirection: LayoutDirection,
        config: MagicTextV2Config,
    ) {
        measureDeferredV2(active, 0, Int.MAX_VALUE, false, hotCache, measurer, density, layoutDirection, config)
    }

    private fun measureDeferredV2(
        active: TextContentV2,
        minW: Int,
        maxW: Int,
        skipMorphPlanInLookahead: Boolean,
        hotCache: NodeHotCache,
        measurer: TextMeasurer,
        density: Density,
        layoutDirection: LayoutDirection,
        config: MagicTextV2Config,
    ): IntSize = when (active) {
        is TextContentV2.Rest -> hotCache.resolveRest(
            caches, active.content, active.characterDeliveryNode, maxW, measurer, density, layoutDirection, config, minW,
        ).size
        is TextContentV2.Morph -> {
            if (skipMorphPlanInLookahead) {
                measureMorphLookaheadSize(
                    from = active.from,
                    to = active.to,
                    characterDeliveryNode = active.characterDeliveryNode,
                    minW = minW,
                    maxW = maxW,
                    preserveEndpointMaxWidths = active.preserveEndpointMaxWidths,
                    schedule = active.schedule,
                    elapsedMs = active.elapsedMs,
                    interpolateLayoutSize = active.interpolateLayoutSize,
                    measurer = measurer,
                    density = density,
                    layoutDirection = layoutDirection,
                    config = config,
                )
            } else {
                val plan = hotCache.resolveMorph(
                    caches = caches,
                    from = active.from,
                    to = active.to,
                    by = active.by,
                    pruneStopwords = active.pruneStopwords,
                    schedule = active.schedule,
                    moveCurve = active.moveCurve,
                    characterDeliveryNode = active.characterDeliveryNode,
                    maxConstraintWidthPx = maxW,
                    measurer = measurer,
                    density = density,
                    layoutDirection = layoutDirection,
                    config = config,
                    preserveEndpointMaxWidths = active.preserveEndpointMaxWidths,
                    minConstraintWidthPx = minW,
                )
                computeMorphMeasuredSize(plan, active.schedule, active.elapsedMs, active.interpolateLayoutSize)
            }
        }
        is TextContentV2.Build -> hotCache.resolveBuild(
            caches = caches,
            styled = active.content,
            granularity = active.granularity,
            characterDeliveryNode = active.characterDeliveryNode,
            schedule = active.schedule,
            maxConstraintWidthPx = maxW,
            measurer = measurer,
            density = density,
            layoutDirection = layoutDirection,
            config = config,
            minConstraintWidthPx = minW,
        ).partition.wholeLayout.size
    }

    private fun DrawScope.drawDeferredV2(
        active: TextContentV2,
        hotCache: NodeHotCache,
        measurer: TextMeasurer,
        density: Density,
        layoutDirection: LayoutDirection,
        config: MagicTextV2Config,
    ) {
        val minW = hotCache.lastMinConstraintWidth
        val maxW = hotCache.lastConstraintWidth
        when (active) {
            is TextContentV2.Rest -> {
                val layout = hotCache.resolveRest(
                    caches, active.content, active.characterDeliveryNode, maxW, measurer, density, layoutDirection, config, minW,
                )
                MagicTextV2Drawing.run { drawRestParagraph(layout) }
            }
            is TextContentV2.Morph -> {
                val plan = hotCache.resolveMorph(
                    caches = caches,
                    from = active.from,
                    to = active.to,
                    by = active.by,
                    pruneStopwords = active.pruneStopwords,
                    schedule = active.schedule,
                    moveCurve = active.moveCurve,
                    characterDeliveryNode = active.characterDeliveryNode,
                    maxConstraintWidthPx = maxW,
                    measurer = measurer,
                    density = density,
                    layoutDirection = layoutDirection,
                    config = config,
                    preserveEndpointMaxWidths = active.preserveEndpointMaxWidths,
                    minConstraintWidthPx = minW,
                )
                MagicTextV2Drawing.run { drawMorphFrame(plan, active, config) }
            }
            is TextContentV2.Build -> {
                val plan = hotCache.resolveBuild(
                    caches = caches,
                    styled = active.content,
                    granularity = active.granularity,
                    characterDeliveryNode = active.characterDeliveryNode,
                    schedule = active.schedule,
                    maxConstraintWidthPx = maxW,
                    measurer = measurer,
                    density = density,
                    layoutDirection = layoutDirection,
                    config = config,
                    minConstraintWidthPx = minW,
                )
                MagicTextV2Drawing.run { drawBuildFrame(plan, active, config) }
            }
        }
    }

    private fun measureMorphLookaheadSize(
        from: StyledTextV2,
        to: StyledTextV2,
        characterDeliveryNode: Boolean,
        minW: Int,
        maxW: Int,
        preserveEndpointMaxWidths: Boolean,
        schedule: PieceScheduleV2,
        elapsedMs: Float,
        interpolateLayoutSize: Boolean,
        measurer: TextMeasurer,
        density: Density,
        layoutDirection: LayoutDirection,
        config: MagicTextV2Config,
    ): IntSize {
        val boundedFrom = if (preserveEndpointMaxWidths) from else from.withBoundedWidth(minW, maxW)
        val boundedTo = if (preserveEndpointMaxWidths) to else to.withBoundedWidth(minW, maxW)
        val fromSize = caches.getOrMeasureWholeLayout(boundedFrom, characterDeliveryNode, measurer, density, layoutDirection, config).size
        val toSize = caches.getOrMeasureWholeLayout(boundedTo, characterDeliveryNode, measurer, density, layoutDirection, config).size
        return interpolateIntSizes(fromSize, toSize, schedule, elapsedMs, interpolateLayoutSize)
    }

    private fun computeMorphMeasuredSize(
        plan: MorphPlanV2,
        schedule: PieceScheduleV2,
        elapsedMs: Float,
        interpolateLayoutSize: Boolean,
    ): IntSize = interpolateIntSizes(
        fromSize = plan.fromPartition.wholeLayout.size,
        toSize = plan.toPartition.wholeLayout.size,
        schedule = schedule,
        elapsedMs = elapsedMs,
        interpolateLayoutSize = interpolateLayoutSize,
    )

    private fun interpolateIntSizes(
        fromSize: IntSize,
        toSize: IntSize,
        schedule: PieceScheduleV2,
        elapsedMs: Float,
        interpolateLayoutSize: Boolean,
    ): IntSize = if (interpolateLayoutSize) {
        val sizeP = schedule.sizeProgressAt(elapsedMs)
        IntSize(
            width = (fromSize.width + (toSize.width - fromSize.width) * sizeP).roundToInt().coerceAtLeast(0),
            height = (fromSize.height + (toSize.height - fromSize.height) * sizeP).roundToInt().coerceAtLeast(0),
        )
    } else {
        IntSize(
            width = maxOf(fromSize.width, toSize.width),
            height = maxOf(fromSize.height, toSize.height),
        )
    }

    /** Direct diagnostic / headless admission helper inspecting the exact [AdmittedPartition]. */
    fun admitPartition(
        styled: StyledTextV2,
        requestedGranularity: PartitionGranularity,
        characterDeliveryNode: Boolean = requestedGranularity == PartitionGranularity.Character,
        measurer: TextMeasurer,
        density: Density,
        layoutDirection: LayoutDirection = LayoutDirection.Ltr,
        config: MagicTextV2Config = MagicTextV2Config(),
    ): AdmittedPartition = caches.getOrAdmitPartition(
        styled = styled,
        requestedGranularity = requestedGranularity,
        characterDeliveryNode = characterDeliveryNode,
        measurer = measurer,
        density = density,
        layoutDirection = layoutDirection,
        config = config,
    )

    /** Direct diagnostic / headless planning helper building or fetching a cached [MorphPlanV2]. */
    fun planMorph(
        morph: TextContentV2.Morph,
        measurer: TextMeasurer,
        density: Density,
        layoutDirection: LayoutDirection = LayoutDirection.Ltr,
        config: MagicTextV2Config = MagicTextV2Config(),
    ): MorphPlanV2 = caches.getOrBuildMorphPlan(
        from = morph.from,
        to = morph.to,
        by = morph.by,
        pruneStopwords = morph.pruneStopwords,
        schedule = morph.schedule,
        defaultMoveCurve = morph.moveCurve,
        characterDeliveryNode = morph.characterDeliveryNode,
        measurer = measurer,
        density = density,
        layoutDirection = layoutDirection,
        config = config,
    )

    /** Direct diagnostic / headless planning helper building or fetching a cached [BuildPlanV2]. */
    fun planBuild(
        build: TextContentV2.Build,
        measurer: TextMeasurer,
        density: Density,
        layoutDirection: LayoutDirection = LayoutDirection.Ltr,
        config: MagicTextV2Config = MagicTextV2Config(),
    ): BuildPlanV2 = caches.getOrBuildBuildPlan(
        styled = build.content,
        granularity = build.granularity,
        characterDeliveryNode = build.characterDeliveryNode,
        schedule = build.schedule,
        measurer = measurer,
        density = density,
        layoutDirection = layoutDirection,
        config = config,
    )

    companion object {
        private val DEFAULT_MORPH_EXIT_EFFECT = EffectCallV2(BuildEffects.DISSOLVE, EffectBuildKind.Exit)
        private val DEFAULT_MORPH_ENTER_EFFECT = EffectCallV2(BuildEffects.DISSOLVE, EffectBuildKind.Enter)

        /** Converts a planner [PieceSchedule] to [PieceScheduleV2] preserving every slot address verbatim. */
        fun convertPieceSchedule(
            schedule: PieceSchedule,
            minTotalDurationMs: Int = 0,
            defaultCurve: CurveV2 = CurveV2.EaseInOut,
        ): PieceScheduleV2 {
            if (schedule.slots.isEmpty()) {
                return PieceScheduleV2(
                    totalDurationMs = minTotalDurationMs.coerceAtLeast(1),
                    slots = emptyMap(),
                    defaultCurve = defaultCurve,
                )
            }
            val map = LinkedHashMap<String, PieceSlotV2>(schedule.slots.size)
            for (slot in schedule.slots) {
                map[slot.address] = PieceSlotV2(
                    address = slot.address,
                    startMs = slot.startMs,
                    endMs = slot.endMs,
                    curve = slot.curve.toCurveV2(),
                )
            }
            val span = maxOf(minTotalDurationMs, schedule.spanMs).coerceAtLeast(1)
            return PieceScheduleV2(totalDurationMs = span, slots = map, defaultCurve = defaultCurve)
        }

        fun DrawScope.drawRestParagraph(wholeLayout: TextLayoutResult) =
            MagicTextV2Drawing.run { drawRestParagraph(wholeLayout) }

        fun DrawScope.drawMorphFrame(
            plan: MorphPlanV2,
            content: TextContentV2.Morph,
            config: MagicTextV2Config = MagicTextV2Config(),
        ) = MagicTextV2Drawing.run { drawMorphFrame(plan, content, config) }

        fun DrawScope.drawBuildFrame(
            plan: BuildPlanV2,
            content: TextContentV2.Build,
            config: MagicTextV2Config = MagicTextV2Config(),
        ) = MagicTextV2Drawing.run { drawBuildFrame(plan, content, config) }
    }
}

private fun StyledTextV2.withBoundedWidth(
    minConstraintWidthPx: Int,
    maxConstraintWidthPx: Int,
): StyledTextV2 {
    val boundedMax = if (maxWidthPx == Int.MAX_VALUE && maxConstraintWidthPx != Constraints.Infinity && maxConstraintWidthPx != Int.MAX_VALUE) {
        maxConstraintWidthPx
    } else {
        maxWidthPx
    }
    val boundedMin = if (minWidthPx == 0 && minConstraintWidthPx > 0) {
        minConstraintWidthPx.coerceAtMost(boundedMax.coerceAtLeast(1))
    } else {
        minWidthPx
    }
    return if (boundedMax != maxWidthPx || boundedMin != minWidthPx) {
        copy(minWidthPx = boundedMin, maxWidthPx = boundedMax)
    } else {
        this
    }
}

private fun StyledText.toStyledTextV2(): StyledTextV2 = StyledTextV2(
    text = text,
    style = style,
    maxWidthPx = if (maxWidth.isFinite() && maxWidth > 0f) maxWidth.roundToInt() else Int.MAX_VALUE,
)

private fun MorphBy.toMagicMorphBy(): MagicMorphBy = when (this) {
    MorphBy.Word -> MagicMorphBy.Word
    MorphBy.Character -> MagicMorphBy.Character
    MorphBy.Line, MorphBy.Paragraph -> MagicMorphBy.Line
}

private fun DeliveryBy.toPartitionGranularity(): PartitionGranularity = when (this) {
    DeliveryBy.Character -> PartitionGranularity.Character
    DeliveryBy.Word -> PartitionGranularity.Word
    DeliveryBy.Paragraph -> PartitionGranularity.Line
    DeliveryBy.Whole, DeliveryBy.Child -> PartitionGranularity.Paragraph
}
