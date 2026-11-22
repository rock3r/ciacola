package dev.sebastiano.ciacola.text.v2

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import dev.bnorm.storyboard.text.magic.MorphBy
import dev.sebastiano.ciacola.DeckTextRenderer
import dev.sebastiano.ciacola.core.fonts.CHARACTER_DELIVERY_DISABLED_FEATURES
import dev.sebastiano.ciacola.core.fonts.DeckFontResolver
import dev.sebastiano.ciacola.core.fonts.GsubParserLimits
import dev.sebastiano.ciacola.effects.v2.BuildEffects
import dev.sebastiano.ciacola.effects.v2.CurveV2
import dev.sebastiano.ciacola.effects.v2.EffectArgs
import dev.sebastiano.ciacola.effects.v2.EffectBuildKind

/**
 * Reserved slot address in [PieceScheduleV2] governing node measured size interpolation during an
 * in-slide beat morph.
 */
const val SIZE_SLOT_ADDRESS: String = "@size"

/**
 * Unquoted OpenType feature string disabling optional ligatures and contextual alternates for
 * character-delivery text runs.
 *
 * Must remain **unquoted** because Skiko `0.156.1` `FontFeature.parseW3` silently returns `[]`
 * when CSS quotes are present.
 */
const val CHARACTER_MODE_DISABLE_LIGATURES: String = CHARACTER_DELIVERY_DISABLED_FEATURES

/**
 * Target partition granularity requested by a text morph or build.
 */
enum class PartitionGranularity {
    Paragraph,
    Line,
    Word,
    Character,
    ;

    companion object {
        fun fromMorphBy(by: MorphBy): PartitionGranularity = when (by) {
            MorphBy.Line -> Line
            MorphBy.Word -> Word
            MorphBy.Character -> Character
        }
    }
}

/**
 * Text content paired with its resolved [TextStyle] and wrapping constraints.
 */
@Immutable
data class StyledTextV2(
    val text: AnnotatedString,
    val style: TextStyle,
    val maxWidthPx: Int = Int.MAX_VALUE,
    val minWidthPx: Int = 0,
    val softWrap: Boolean = true,
    val maxLines: Int = Int.MAX_VALUE,
    val overflow: TextOverflow = TextOverflow.Visible,
)

/**
 * Per-fragment or `@size` timing window inside a [PieceScheduleV2].
 */
@Immutable
data class PieceSlotV2(
    val address: String,
    val startMs: Int,
    val endMs: Int,
    val curve: CurveV2 = CurveV2.EaseInOut,
) {
    val durationMs: Int
        get() = (endMs - startMs).coerceAtLeast(0)

    /** Evaluates raw normalized fraction in `[0, 1]` at [elapsedMs]. */
    fun rawProgressAt(elapsedMs: Float): Float {
        if (durationMs <= 0) return if (elapsedMs >= startMs) 1f else 0f
        if (elapsedMs <= startMs) return 0f
        if (elapsedMs >= endMs) return 1f
        return ((elapsedMs - startMs) / durationMs.toFloat()).coerceIn(0f, 1f)
    }

    /** Evaluates eased progress at [elapsedMs]. */
    fun easedProgressAt(elapsedMs: Float): Float {
        val raw = rawProgressAt(elapsedMs)
        if (raw <= 0f) return 0f
        if (raw >= 1f) return 1f
        return curve.sample(raw, durationMs.toFloat())
    }
}

/**
 * Stagger order computable purely from text structure without layout.
 */
enum class TextStaggerOrder {
    ReadingOrder,
    TopToBottom,
}

/**
 * Immutable schedule of piece timing slots (and `@size` slot on morphs) resolved by the planner.
 * The renderer consumes these slots directly and never invents timing of its own.
 */
@Immutable
data class PieceScheduleV2(
    val totalDurationMs: Int,
    val slots: Map<String, PieceSlotV2>,
    val defaultCurve: CurveV2 = CurveV2.EaseInOut,
) {
    fun slotFor(address: String): PieceSlotV2? = slots[address]

    /**
     * Evaluates size interpolation strictly from the planner's `@size` slot.
     * Never invents an easing curve when `@size` is absent.
     */
    fun sizeProgressAt(elapsedMs: Float): Float {
        val slot = slots[SIZE_SLOT_ADDRESS]
        if (slot != null) return slot.easedProgressAt(elapsedMs).coerceIn(0f, 1f)
        return if (totalDurationMs <= 0 || elapsedMs >= totalDurationMs) 1f else 0f
    }
}

/**
 * Effect invocation applied to entering, exiting, or built text pieces.
 */
@Immutable
data class EffectCallV2(
    val effectId: String = BuildEffects.DISSOLVE,
    val kind: EffectBuildKind = EffectBuildKind.Enter,
    val args: EffectArgs = EffectArgs.Empty,
    val curve: CurveV2? = null,
)

/**
 * Clean renderer input seam for `MagicText v2`.
 */
@Immutable
sealed interface TextContentV2 {
    /** Resting paragraph: drawn with a single `drawText(wholeLayout)` (100% pixel-identical to `BasicText`). */
    @Immutable
    data class Rest(
        val content: StyledTextV2,
        /**
         * Set `true` when this resting text node has a character-delivery build/morph on any beat
         * so optional ligatures (`liga 0, clig 0, dlig 0, calt 0`) stay consistently disabled at
         * rest and in motion.
         */
        val characterDeliveryNode: Boolean = false,
    ) : TextContentV2

    /** Active morph between [from] and [to] at canonical [elapsedMs]. */
    @Immutable
    data class Morph(
        val from: StyledTextV2,
        val to: StyledTextV2,
        val by: MorphBy = MorphBy.Word,
        val pruneStopwords: Boolean = true,
        val schedule: PieceScheduleV2,
        val elapsedMs: Float,
        val exitEffect: EffectCallV2 = EffectCallV2(BuildEffects.DISSOLVE, EffectBuildKind.Exit),
        val enterEffect: EffectCallV2 = EffectCallV2(BuildEffects.DISSOLVE, EffectBuildKind.Enter),
        val moveCurve: CurveV2 = CurveV2.EaseInOut,
        /** True inside a beat segment (`@size` interpolates); false on cross-slide moves. */
        val interpolateLayoutSize: Boolean = true,
        /**
         * True when `from.maxWidthPx` and `to.maxWidthPx` already hold exact endpoint constraint
         * widths (e.g. Lookahead-captured `Row`/`Column` widths) and must not be clamped by the
         * overlay's interpolated outer constraint width.
         */
        val preserveEndpointMaxWidths: Boolean = false,
        /**
         * True when the text node requests character delivery anywhere in the deck so even a word
         * or line morph on that node shares the single forced-off feature configuration.
         */
        val characterDeliveryNode: Boolean = false,
    ) : TextContentV2

    /** Piece-level build (enter or exit) with unchanged text (e.g. `dissolve`, `fadeMove`, `keyboard`). */
    @Immutable
    data class Build(
        val content: StyledTextV2,
        val granularity: PartitionGranularity = PartitionGranularity.Character,
        val effect: EffectCallV2,
        val schedule: PieceScheduleV2,
        val elapsedMs: Float,
        val caretColor: Color = Color.Unspecified,
        /**
         * True when the text node requests character delivery anywhere in the deck so even a word
         * or paragraph build on that node shares the single forced-off feature configuration.
         */
        val characterDeliveryNode: Boolean = false,
    ) : TextContentV2
}

/**
 * Configuration and test-verification hooks for [MagicTextV2Renderer].
 *
 * Condition 1 character-safety certification ALWAYS runs through the single production engine
 * (`DeckFontResolver.certifyParagraphCharacterSafety` / `CharacterSafetyEngine.certifyParagraph`);
 * no alternate or test-only predicate can be injected.
 */
@Immutable
data class MagicTextV2Config(
    val fontResolver: DeckFontResolver? = null,
    val stageSize: IntSize = IntSize(1920, 1080),
    val reducedMotion: Boolean = false,
    /** Test-only hook to force Condition 2 failure and exercise whole-paragraph native fallback. */
    val forceCondition2Failure: Boolean = false,
    /**
     * Test-only hook to deterministically fail Condition 2 for a specific endpoint/granularity
     * (e.g. `ab -> ba` asymmetric coarsening where only the source character partition fails).
     */
    val forceCondition2FailureForEndpoint: ((StyledTextV2, PartitionGranularity) -> Boolean)? = null,
    /** Test-only hook returning a forced Condition 2 pixel readback result (`null` simulates readback failure). */
    val condition2ReadbackOverrideForTest: (() -> ByteArray?)? = null,
    /** Parser and Tier B fixed-point limits passed to the single production Condition 1 engine. */
    val condition1Limits: GsubParserLimits = GsubParserLimits.Default,
    val maxLayoutCacheEntries: Int = 256,
    val maxPartitionCacheEntries: Int = 128,
    val maxMorphPlanCacheEntries: Int = 64,
    val stageSizeForEffects: Size = Size(stageSize.width.toFloat(), stageSize.height.toFloat()),
)

/** Ambient configuration read by [DeckTextRenderer.Text] on desktop. */
val LocalMagicTextV2Config = staticCompositionLocalOf { MagicTextV2Config() }

/**
 * Swappable text rendering boundary for MagicText v2, extending the application-level
 * [DeckTextRenderer] seam.
 */
interface DeckTextRendererV2 : DeckTextRenderer {
    /**
     * Deferred-content composable entry point reading [content] inside layout/draw phases so
     * animated frames do not rebuild composition or allocate managed heap objects on layer-free draws.
     */
    @Composable
    fun TextNode(
        content: () -> TextContentV2,
        modifier: Modifier = Modifier,
        config: MagicTextV2Config = MagicTextV2Config(),
    )

    @Composable
    fun TextNode(
        content: TextContentV2,
        modifier: Modifier = Modifier,
        config: MagicTextV2Config = MagicTextV2Config(),
    ) {
        TextNode(content = { content }, modifier = modifier, config = config)
    }
}
