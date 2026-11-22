package dev.sebastiano.ciacola.text.v2

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import dev.bnorm.storyboard.text.magic.MorphBy
import dev.sebastiano.ciacola.core.fonts.EffectiveFontFeatureConfig
import dev.sebastiano.ciacola.core.fonts.GsubParserLimits
import dev.sebastiano.ciacola.core.fonts.effectiveFeatureConfigForTextNode
import dev.sebastiano.ciacola.effects.v2.CurveV2

internal data class LayoutCacheKey(
    val text: AnnotatedString,
    val style: TextStyle,
    val maxWidthPx: Int,
    val minWidthPx: Int = 0,
    val softWrap: Boolean,
    val maxLines: Int,
    val overflow: TextOverflow,
    val density: Float,
    val fontScale: Float,
    val layoutDirection: LayoutDirection,
    val localeList: LocaleList?,
    val fontResolverFingerprint: String,
    val effectiveFeatureConfig: EffectiveFontFeatureConfig,
)

internal data class PartitionCacheKey(
    val layoutKey: LayoutCacheKey,
    val requestedGranularity: PartitionGranularity,
    val stageSize: IntSize,
    val condition1Limits: GsubParserLimits,
    val forceCondition2Failure: Boolean,
    val endpointHookIdentity: Int,
    val readbackHookIdentity: Int,
)

internal data class MorphPlanCacheKey(
    val fromKey: PartitionCacheKey,
    val toKey: PartitionCacheKey,
    val by: MorphBy,
    val pruneStopwords: Boolean,
    val schedule: PieceScheduleV2,
    val defaultMoveCurve: CurveV2,
)

internal data class BuildPlanCacheKey(
    val partitionKey: PartitionCacheKey,
    val schedule: PieceScheduleV2,
)

/**
 * Thread-safe bounded LRU caches for `MagicText v2` whole layouts, admitted partitions, morph
 * plans, and build plans.
 */
class MagicTextV2Caches(
    private val maxLayouts: Int = 256,
    private val maxPartitions: Int = 128,
    private val maxMorphPlans: Int = 64,
    private val maxBuildPlans: Int = 64,
) {
    private val lock = Any()

    private val layoutCache = lruMap<LayoutCacheKey, TextLayoutResult>(maxLayouts)
    private val partitionCache = lruMap<PartitionCacheKey, AdmittedPartition>(maxPartitions)
    private val morphPlanCache = lruMap<MorphPlanCacheKey, MorphPlanV2>(maxMorphPlans)
    private val buildPlanCache = lruMap<BuildPlanCacheKey, BuildPlanV2>(maxBuildPlans)

    var layoutCacheHits: Long = 0L
        private set
    var layoutCacheMisses: Long = 0L
        private set
    var partitionCacheHits: Long = 0L
        private set
    var partitionCacheMisses: Long = 0L
        private set
    var morphPlanCacheHits: Long = 0L
        private set
    var morphPlanCacheMisses: Long = 0L
        private set
    var buildPlanCacheHits: Long = 0L
        private set
    var buildPlanCacheMisses: Long = 0L
        private set

    internal fun getOrMeasureWholeLayout(
        styled: StyledTextV2,
        characterDeliveryNode: Boolean,
        measurer: TextMeasurer,
        density: Density,
        layoutDirection: LayoutDirection,
        config: MagicTextV2Config,
    ): TextLayoutResult {
        val key = buildLayoutKey(styled, characterDeliveryNode, density, layoutDirection, config)
        synchronized(lock) {
            layoutCache[key]?.let {
                layoutCacheHits++
                return it
            }
            layoutCacheMisses++
        }
        val prepared = TwoConditionPartitioner.prepareStyledText(
            styled = styled,
            characterDeliveryNode = characterDeliveryNode,
            fontResolver = config.fontResolver,
        )
        val measured = TwoConditionPartitioner.measureWholeParagraph(prepared, measurer)
        synchronized(lock) {
            layoutCache[key] = measured
        }
        return measured
    }

    internal fun getOrAdmitPartition(
        styled: StyledTextV2,
        requestedGranularity: PartitionGranularity,
        characterDeliveryNode: Boolean,
        measurer: TextMeasurer,
        density: Density,
        layoutDirection: LayoutDirection,
        config: MagicTextV2Config,
    ): AdmittedPartition {
        val key = buildPartitionKey(
            styled = styled,
            requestedGranularity = requestedGranularity,
            characterDeliveryNode = characterDeliveryNode,
            density = density,
            layoutDirection = layoutDirection,
            config = config,
        )
        synchronized(lock) {
            partitionCache[key]?.let {
                partitionCacheHits++
                return it
            }
            partitionCacheMisses++
        }
        val admitted = TwoConditionPartitioner.partition(
            styled = styled,
            requestedGranularity = requestedGranularity,
            characterDeliveryNode = characterDeliveryNode,
            measurer = measurer,
            density = density,
            layoutDirection = layoutDirection,
            config = config,
        )
        synchronized(lock) {
            partitionCache[key] = admitted
            layoutCache[key.layoutKey] = admitted.wholeLayout
        }
        return admitted
    }

    internal fun getOrBuildMorphPlan(
        from: StyledTextV2,
        to: StyledTextV2,
        by: MorphBy,
        pruneStopwords: Boolean,
        schedule: PieceScheduleV2,
        defaultMoveCurve: CurveV2,
        characterDeliveryNode: Boolean,
        measurer: TextMeasurer,
        density: Density,
        layoutDirection: LayoutDirection,
        config: MagicTextV2Config,
    ): MorphPlanV2 {
        val granularity = PartitionGranularity.fromMorphBy(by)
        val isCharNode = characterDeliveryNode || by == MorphBy.Character
        val fromKey = buildPartitionKey(from, granularity, isCharNode, density, layoutDirection, config)
        val toKey = buildPartitionKey(to, granularity, isCharNode, density, layoutDirection, config)
        val morphKey = MorphPlanCacheKey(
            fromKey = fromKey,
            toKey = toKey,
            by = by,
            pruneStopwords = pruneStopwords,
            schedule = schedule,
            defaultMoveCurve = defaultMoveCurve,
        )
        synchronized(lock) {
            morphPlanCache[morphKey]?.let {
                morphPlanCacheHits++
                return it
            }
            morphPlanCacheMisses++
        }

        val fromPart = getOrAdmitPartition(from, granularity, isCharNode, measurer, density, layoutDirection, config)
        val toPart = getOrAdmitPartition(to, granularity, isCharNode, measurer, density, layoutDirection, config)
        val plan = JointGroupingPlanner.buildPlan(
            fromPartition = fromPart,
            toPartition = toPart,
            by = by,
            pruneStopwords = pruneStopwords,
            schedule = schedule,
            defaultMoveCurve = defaultMoveCurve,
            measurer = measurer,
            density = density,
            fontResolver = config.fontResolver,
        )
        synchronized(lock) {
            morphPlanCache[morphKey] = plan
        }
        return plan
    }

    internal fun getOrBuildBuildPlan(
        styled: StyledTextV2,
        granularity: PartitionGranularity,
        characterDeliveryNode: Boolean,
        schedule: PieceScheduleV2,
        measurer: TextMeasurer,
        density: Density,
        layoutDirection: LayoutDirection,
        config: MagicTextV2Config,
    ): BuildPlanV2 {
        val isCharNode = characterDeliveryNode || granularity == PartitionGranularity.Character
        val partKey = buildPartitionKey(styled, granularity, isCharNode, density, layoutDirection, config)
        val buildKey = BuildPlanCacheKey(partitionKey = partKey, schedule = schedule)
        synchronized(lock) {
            buildPlanCache[buildKey]?.let {
                buildPlanCacheHits++
                return it
            }
            buildPlanCacheMisses++
        }

        val partition = getOrAdmitPartition(styled, granularity, isCharNode, measurer, density, layoutDirection, config)
        val plan = JointGroupingPlanner.buildBuildPlan(partition = partition, schedule = schedule)
        synchronized(lock) {
            buildPlanCache[buildKey] = plan
        }
        return plan
    }

    fun clear() {
        synchronized(lock) {
            layoutCache.clear()
            partitionCache.clear()
            morphPlanCache.clear()
            buildPlanCache.clear()
            layoutCacheHits = 0L
            layoutCacheMisses = 0L
            partitionCacheHits = 0L
            partitionCacheMisses = 0L
            morphPlanCacheHits = 0L
            morphPlanCacheMisses = 0L
            buildPlanCacheHits = 0L
            buildPlanCacheMisses = 0L
        }
    }

    private fun buildLayoutKey(
        styled: StyledTextV2,
        characterDeliveryNode: Boolean,
        density: Density,
        layoutDirection: LayoutDirection,
        config: MagicTextV2Config,
    ): LayoutCacheKey = LayoutCacheKey(
        text = styled.text,
        style = styled.style,
        maxWidthPx = styled.maxWidthPx,
        minWidthPx = styled.minWidthPx,
        softWrap = styled.softWrap,
        maxLines = styled.maxLines,
        overflow = styled.overflow,
        density = density.density,
        fontScale = density.fontScale,
        layoutDirection = layoutDirection,
        localeList = styled.style.localeList,
        fontResolverFingerprint = config.fontResolver?.cacheFingerprint.orEmpty(),
        effectiveFeatureConfig = effectiveFeatureConfigForTextNode(characterDeliveryNode),
    )

    private fun buildPartitionKey(
        styled: StyledTextV2,
        requestedGranularity: PartitionGranularity,
        characterDeliveryNode: Boolean,
        density: Density,
        layoutDirection: LayoutDirection,
        config: MagicTextV2Config,
    ): PartitionCacheKey = PartitionCacheKey(
        layoutKey = buildLayoutKey(
            styled = styled,
            characterDeliveryNode = characterDeliveryNode || requestedGranularity == PartitionGranularity.Character,
            density = density,
            layoutDirection = layoutDirection,
            config = config,
        ),
        requestedGranularity = requestedGranularity,
        stageSize = config.stageSize,
        condition1Limits = config.condition1Limits,
        forceCondition2Failure = config.forceCondition2Failure,
        endpointHookIdentity = System.identityHashCode(config.forceCondition2FailureForEndpoint),
        readbackHookIdentity = System.identityHashCode(config.condition2ReadbackOverrideForTest),
    )

    private fun <K, V> lruMap(
        maxSize: Int,
        onEvict: ((V) -> Unit)? = null,
    ): LinkedHashMap<K, V> =
        object : LinkedHashMap<K, V>(maxSize.coerceAtLeast(8), 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>): Boolean {
                val shouldEvict = size > maxSize.coerceAtLeast(1)
                if (shouldEvict) {
                    onEvict?.invoke(eldest.value)
                }
                return shouldEvict
            }
        }
}
