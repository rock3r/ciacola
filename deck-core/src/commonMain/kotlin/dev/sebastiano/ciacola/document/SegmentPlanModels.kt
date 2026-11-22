package dev.sebastiano.ciacola.document

import androidx.compose.runtime.Immutable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Classification of a planned track inside a [SegmentPlan].
 */
enum class TrackKind {
    Enter,
    Exit,
    Emphasis,
    Change,
    SpecimenTrack,
    TransitionSlide,
    TransitionMove,
    TransitionExit,
    TransitionEnter,
}

enum class SlotPhase {
    Exit,
    Move,
    Enter,
}

/**
 * A single logical piece slot relative to its parent [TrackPlan]'s [TrackPlan.startMs].
 */
@Immutable
data class Slot(
    val address: String,
    val startMs: Int,
    val durationMs: Int,
    val curve: Curve,
    val phase: SlotPhase = SlotPhase.Move,
) {
    val endMs: Int get() = startMs + durationMs

    companion object {
        /** Reserved slot address present on every text morph schedule. */
        const val SIZE_ADDRESS: String = "@size"
    }
}

@Immutable
data class PieceSchedule(
    val slots: List<Slot>,
) {
    val spanMs: Int
        get() = slots.maxOfOrNull { it.endMs } ?: 0

    fun slot(address: String): Slot? = slots.firstOrNull { it.address == address }
}

@Immutable
data class TrackPlan(
    val nodeId: String,
    val buildId: String?,
    val startMs: Int,
    val endMs: Int,
    val curve: Curve,
    val pieces: PieceSchedule? = null,
    val kind: TrackKind = TrackKind.Change,
    val buildIndex: Int? = null,
    val warpSchedule: WarpSchedule? = null,
    /**
     * When `true`, neither the build, phase timing, nor effect declaration authored an explicit
     * curve override, so effect evaluators MUST keep their built-in Keynote curve(s).
     */
    val useEffectDefaultCurve: Boolean = false,
    /** `true` when this track was scheduled relative to a participating same-node or `after` dependency. */
    val isSequenced: Boolean = false,
    /** End time (ms) of the controlling dependency when [isSequenced] is `true`, or `0` otherwise. */
    val dependencyEndMs: Int = 0,
) {
    val durationMs: Int get() = (endMs - startMs).coerceAtLeast(0)

    /**
     * Explicit curve override to pass into build effect evaluators, or `null` when the effect
     * should use its own built-in Keynote curve(s).
     */
    val effectCurveOverride: Curve? get() = if (useEffectDefaultCurve) null else curve

    /** Evaluates this track's eased progress at canonical segment time [canonicalTimeMs]. */
    fun progressAt(canonicalTimeMs: Float): Float =
        CurveEvaluator.evaluateAtMs(curve, canonicalTimeMs - startMs.toFloat(), durationMs)
}

@Immutable
data class SegmentPlan(
    val tracks: List<TrackPlan>,
    val segmentMs: Int,
) {
    companion object {
        val Empty: SegmentPlan = SegmentPlan(emptyList(), 0)
    }
}

internal data class DocumentSortKey(
    val x: Double,
    val y: Double,
    val childPath: List<Int>,
    val documentOrder: Int,
)

/**
 * Per-slide plan and lint cache invalidation key calculators.
 */
internal object SlidePlanCacheKeys {

    fun slidePlanCacheKey(
        document: DeckDocument,
        slideIndex: Int,
        themeName: String? = document.defaultTheme,
        specimens: DeckSpecimenRegistry = DeckSpecimenRegistry.Empty,
    ): String {
        val slide = document.slides.getOrNull(slideIndex) ?: return ""
        val activeTheme = themeName?.let { document.themes[it] } ?: document.themes.values.firstOrNull()
        val referencedPalette = slide.palette?.let { document.palettes[it] }

        val prevSlide = (slideIndex - 1 downTo 0)
            .map { document.slides[it] }
            .firstOrNull { !it.skipped }
        val nextSlide = ((slideIndex + 1) until document.slides.size)
            .map { document.slides[it] }
            .firstOrNull { !it.skipped }

        val referencedSpecimens = linkedSetOf<String>()
        fun collectSpecs(nodes: List<NodeSpec>) {
            for (n in nodes) {
                n.specimen?.let { referencedSpecimens += it.ref }
                collectSpecs(n.children)
            }
        }
        collectSpecs(slide.nodes)
        prevSlide?.let { collectSpecs(it.nodes) }
        nextSlide?.let { collectSpecs(it.nodes) }

        val payload = buildJsonObject {
            put("stage", JsonMergePatch.strictJson.encodeToJsonElement(StageSize.serializer(), document.stage))
            put("slide", JsonMergePatch.strictJson.encodeToJsonElement(SlideSpec.serializer(), slide))
            if (activeTheme != null) {
                put("theme", JsonMergePatch.strictJson.encodeToJsonElement(DeckThemeSpec.serializer(), activeTheme))
            }
            if (referencedPalette != null) {
                put("palette", JsonMergePatch.strictJson.encodeToJsonElement(SlidePaletteSpec.serializer(), referencedPalette))
            }
            put(
                "fonts",
                JsonMergePatch.strictJson.encodeToJsonElement(
                    kotlinx.serialization.builtins.ListSerializer(dev.sebastiano.ciacola.core.fonts.DeckFontDeclaration.serializer()),
                    document.fonts,
                ),
            )
            put(
                "loopPeriodsMs",
                buildJsonObject {
                    for ((k, v) in document.loopPeriodsMs.toSortedMap()) {
                        put(k, JsonPrimitive(v))
                    }
                },
            )
            put(
                "specimenDecls",
                buildJsonObject {
                    for (ref in referencedSpecimens.sorted()) {
                        val decl = specimens.find(ref)
                        put(ref, JsonPrimitive(decl?.let { specimenDeclarationFingerprint(it) } ?: "<missing>"))
                    }
                },
            )
            if (prevSlide != null) {
                val prevLastBeat = prevSlide.beats.lastIndex.coerceAtLeast(0)
                val resolvedPrev = prevSlide.nodes.map { JsonMergePatch.resolveNodeJsonAtBeat(it, prevLastBeat) }
                put("prevLastBeatNodes", JsonArray(resolvedPrev))
            }
            if (nextSlide != null) {
                val resolvedNext = nextSlide.nodes.map { JsonMergePatch.resolveNodeJsonAtBeat(it, 0) }
                put("nextBeat0Nodes", JsonArray(resolvedNext))
            }
        }
        return fnv1a128Hex(CanonicalJson.print(payload, rootDescriptor = null))
    }

    fun slideLintCacheKey(
        document: DeckDocument,
        slideIndex: Int,
        themeName: String? = document.defaultTheme,
        specimens: DeckSpecimenRegistry = DeckSpecimenRegistry.Empty,
        assetFingerprints: Map<String, String> = emptyMap(),
        fontResolverTag: String = "default",
    ): String {
        val baseKey = slidePlanCacheKey(document, slideIndex, themeName, specimens)
        val slide = document.slides.getOrNull(slideIndex) ?: return baseKey
        val referencedAssets = linkedSetOf<String>()
        (slide.background as? Fill.Image)?.let { referencedAssets += it.image.src }
        fun collectAssets(nodes: List<NodeSpec>) {
            for (n in nodes) {
                n.image?.let { referencedAssets += it.src }
                (n.shape?.fill as? Fill.Image)?.let { referencedAssets += it.image.src }
                collectAssets(n.children)
            }
        }
        collectAssets(slide.nodes)
        val extra = buildString {
            append(baseKey).append('|').append(fontResolverTag)
            for (asset in referencedAssets.sorted()) {
                append('|').append(asset).append('=').append(assetFingerprints[asset].orEmpty())
            }
        }
        return fnv1a128Hex(extra)
    }

    private fun specimenDeclarationFingerprint(decl: SpecimenDeclaration): String = buildString {
        append(decl.id).append('|').append(decl.label)
        for (p in decl.params) {
            append("|p:").append(p.name).append('=').append(p.defaultValue)
        }
        for (t in decl.tracks) {
            append("|t:").append(t.name).append('@').append(t.beat).append(':').append(t.delayMs).append('+').append(t.durationMs)
        }
        for (l in decl.loops) {
            append("|l:").append(l.name).append(':').append(l.periodMs).append(':').append(l.perNode)
        }
    }

    /** Deterministic 128-bit FNV-1a hex hash (pure Kotlin commonMain). */
    fun fnv1a128Hex(input: String): String {
        var h1 = -0x340d631b7bdddcdbL // 0xcbf29ce484222325
        var h2 = 0x100000001b3L
        val prime1 = 0x100000001b3L
        val prime2 = -0x542c8f512929c3b5L
        for (ch in input) {
            val v = ch.code.toLong()
            h1 = (h1 xor v) * prime1
            h2 = (h2 xor (v + 31L)) * prime2
        }
        return h1.toULong().toString(16).padStart(16, '0') +
            h2.toULong().toString(16).padStart(16, '0')
    }
}
