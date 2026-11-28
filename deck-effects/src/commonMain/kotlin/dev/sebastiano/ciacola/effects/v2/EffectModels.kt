package dev.sebastiano.ciacola.effects.v2

import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin

/**
 * Architectural tier of a Ciacola v2 build effect.
 */
enum class EffectTier {
    /** Evaluates purely to a [LayerState] (alpha, translation, scale, rotation, blur, colorMatrix, clip). */
    Layer,

    /** Evaluates to a [PathTrimState] applied by vector path geometry trimming. */
    Path,

    /** Evaluates on the node's rendered pixels via offscreen layer + SkSL `RuntimeShader`. */
    Content,

    /** Drives character-level text cadence/caret rendering inside `MagicText v2`. */
    Text,
}

/**
 * Direction/role of a build effect invocation.
 */
enum class EffectBuildKind {
    Enter,
    Exit,
    Emphasis,
}

/**
 * Node classes recognized by [BuildEffects.validate].
 */
enum class EffectNodeKind {
    Text,
    Shape,
    Path,
    Connector,
    Image,
    Video,
    Group,
    Code,
    Component,
}

/**
 * Eight-way cardinal and diagonal direction used by `fadeMove`, `moveIn`, `wipe`, `trace`,
 * `sparkle`, `shimmer`, and directional slide transitions.
 *
 * [unitVector] points in the direction of motion / reveal travel in screen coordinates
 * (`+x` right, `+y` down).
 */
enum class EffectDirection8(
    val canonicalName: String,
    val keynoteCode: Int,
    private val dx: Float,
    private val dy: Float,
) {
    LeftToRight("leftToRight", 11, 1f, 0f),
    RightToLeft("rightToLeft", 12, -1f, 0f),
    BottomToTop("bottomToTop", 13, 0f, -1f),
    TopToBottom("topToBottom", 14, 0f, 1f),
    BottomLeftToTopRight("bottomLeftToTopRight", 21, INV_SQRT2, -INV_SQRT2),
    TopLeftToBottomRight("topLeftToBottomRight", 22, INV_SQRT2, INV_SQRT2),
    BottomRightToTopLeft("bottomRightToTopLeft", 23, -INV_SQRT2, -INV_SQRT2),
    TopRightToBottomLeft("topRightToBottomLeft", 24, -INV_SQRT2, INV_SQRT2),
    ;

    fun unitVector(): Offset = Offset(dx, dy)

    /** Signs (`-1`, `0`, `+1`) along X and Y axes for full-stage-span translations. */
    fun axisSigns(): Offset = Offset(
        x = when {
            dx > 0f -> 1f
            dx < 0f -> -1f
            else -> 0f
        },
        y = when {
            dy > 0f -> 1f
            dy < 0f -> -1f
            else -> 0f
        },
    )

    companion object {
        fun fromKeynoteCode(code: Int): EffectDirection8? =
            entries.firstOrNull { it.keynoteCode == code }

        fun parse(raw: String): EffectDirection8? {
            val normalized = raw.trim().lowercase().replace("-", "").replace("_", "").replace(" ", "")
            return when (normalized) {
                "lefttoright", "right", "fromleft", "starttoend", "11" -> LeftToRight
                "righttoleft", "left", "fromright", "endtostart", "12" -> RightToLeft
                "bottomtotop", "up", "frombottom", "13" -> BottomToTop
                "toptobottom", "down", "fromtop", "14" -> TopToBottom
                "bottomlefttotopright", "frombottomleft", "upright", "21" -> BottomLeftToTopRight
                "toplefttobottomright", "fromtopleft", "downright", "22" -> TopLeftToBottomRight
                "bottomrighttotopleft", "frombottomright", "upleft", "23" -> BottomRightToTopLeft
                "toprighttobottomleft", "fromtopright", "downleft", "24" -> TopRightToBottomLeft
                else -> null
            }
        }
    }
}

private const val INV_SQRT2: Float = 0.70710677f

/**
 * Iris clip direction (`In`: grows/opens from center; `Out`: closes/contracts toward center).
 */
enum class IrisDirection {
    In,
    Out,
    ;

    companion object {
        fun parse(raw: String): IrisDirection? = when (raw.trim().lowercase()) {
            "in", "open", "expand", "fromcenter" -> In
            "out", "close", "shrink", "tocenter" -> Out
            else -> null
        }
    }
}

/**
 * LineDraw path trimming direction.
 */
enum class LineDrawDirection {
    StartToEnd,
    EndToStart,
    FromCenter,
    Clockwise,
    CounterClockwise,
    ;

    companion object {
        fun parse(raw: String): LineDrawDirection? = when (
            raw.trim().lowercase().replace("-", "").replace("_", "").replace(" ", "")
        ) {
            "starttoend", "forward", "lefttoright" -> StartToEnd
            "endtostart", "reverse", "righttoleft" -> EndToStart
            "fromcenter", "center", "middleout" -> FromCenter
            "clockwise", "cw" -> Clockwise
            "counterclockwise", "ccw", "anticlockwise" -> CounterClockwise
            else -> null
        }
    }
}

/**
 * Arrowhead styling attached to trimmed [PathTrimState] tips.
 */
enum class ArrowHeadStyle {
    None,
    Open,
    Filled,
}

/**
 * Clip geometry carried by a [LayerState] or [SlideLayerComposition].
 */
@Immutable
sealed interface EffectClip {
    data object None : EffectClip

    /**
     * Linear 8-way half-plane clip.
     * - [revealFraction] `0f` = completely clipped away; `1f` = completely unclipped.
     * - When [isExit] is `false`, the visible region advances in [direction].
     * - When [isExit] is `true`, the hidden region advances in [direction] (wiping off forward).
     */
    @Immutable
    data class Wipe(
        val direction: EffectDirection8,
        val revealFraction: Float,
        val isExit: Boolean = false,
    ) : EffectClip

    /**
     * Circular iris clip centered at normalized [center] (`0..1`, default `(0.5, 0.5)`).
     * - [radiusFraction] scales the radius relative to the farthest corner of the bounds (`0..1`).
     * - [keepInside] `true` keeps the interior of the circle; `false` keeps the exterior.
     */
    @Immutable
    data class Iris(
        val center: Offset = Offset(0.5f, 0.5f),
        val radiusFraction: Float,
        val keepInside: Boolean = true,
    ) : EffectClip

    /**
     * Rectangular mask fixed at the layer's resting bounds (before the layer's own translation,
     * scale and rotation), grown by the given padding in px. Content that moves outside its rest
     * box disappears, as if emerging from behind the edge of its line.
     */
    @Immutable
    data class RestBounds(
        val padLeft: Float,
        val padTop: Float,
        val padRight: Float,
        val padBottom: Float,
    ) : EffectClip
}

/**
 * Complete GPU layer state produced by Layer-tier effects and slide transitions.
 *
 * [colorMatrix], when non-null, is a standard 20-element 4×5 RGBA color matrix (row-major).
 */
@Immutable
data class LayerState(
    val alpha: Float = 1f,
    val translationX: Float = 0f,
    val translationY: Float = 0f,
    val scaleX: Float = 1f,
    val scaleY: Float = 1f,
    val transformOrigin: TransformOrigin = TransformOrigin.Center,
    val rotationZ: Float = 0f,
    val blurRadiusPx: Float = 0f,
    val colorMatrix: FloatArray? = null,
    val clip: EffectClip = EffectClip.None,
) {
    val isIdentity: Boolean
        get() = alpha == 1f &&
            translationX == 0f &&
            translationY == 0f &&
            scaleX == 1f &&
            scaleY == 1f &&
            rotationZ == 0f &&
            blurRadiusPx == 0f &&
            colorMatrix == null &&
            clip == EffectClip.None

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is LayerState) return false
        return alpha == other.alpha &&
            translationX == other.translationX &&
            translationY == other.translationY &&
            scaleX == other.scaleX &&
            scaleY == other.scaleY &&
            transformOrigin == other.transformOrigin &&
            rotationZ == other.rotationZ &&
            blurRadiusPx == other.blurRadiusPx &&
            (colorMatrix contentEquals other.colorMatrix) &&
            clip == other.clip
    }

    override fun hashCode(): Int {
        var result = alpha.hashCode()
        result = 31 * result + translationX.hashCode()
        result = 31 * result + translationY.hashCode()
        result = 31 * result + scaleX.hashCode()
        result = 31 * result + scaleY.hashCode()
        result = 31 * result + transformOrigin.hashCode()
        result = 31 * result + rotationZ.hashCode()
        result = 31 * result + blurRadiusPx.hashCode()
        result = 31 * result + (colorMatrix?.contentHashCode() ?: 0)
        result = 31 * result + clip.hashCode()
        return result
    }

    companion object {
        val Identity: LayerState = LayerState()
        val Hidden: LayerState = LayerState(alpha = 0f)
    }
}

/**
 * Trim parameters produced by Path-tier `lineDraw` effects.
 */
@Immutable
data class PathTrimState(
    val startFraction: Float,
    val endFraction: Float,
    val direction: LineDrawDirection = LineDrawDirection.StartToEnd,
    val includeEndpoints: Boolean = true,
    val alpha: Float = 1f,
    val headScale: Float = 1f,
) {
    val visibleFraction: Float
        get() = (endFraction - startFraction).coerceIn(0f, 1f)

    companion object {
        val Full: PathTrimState = PathTrimState(startFraction = 0f, endFraction = 1f)
        val Empty: PathTrimState = PathTrimState(startFraction = 0f, endFraction = 0f, alpha = 0f, headScale = 0f)
    }
}

/**
 * Spatial and runtime context supplied when evaluating a build effect.
 *
 * When [stageOffset] is non-null, [nodeBounds] is translated by [stageOffset] to place it in
 * stage coordinates (`0..stageSize.width`, `0..stageSize.height`). When [stageOffset] is null,
 * stage-clearing effects conservatively guarantee full [stageSize] clearance.
 */
@Immutable
data class EffectEvalContext(
    val nodeBounds: Rect = Rect(0f, 0f, 320f, 120f),
    val stageSize: Size = Size(1920f, 1080f),
    val density: Float = 1f,
    val reducedMotion: Boolean = false,
    val stageOffset: Offset? = null,
    /** Piece-level placement for per-piece effects (motion presets); defaults describe a whole node. */
    val piece: PieceContext = PieceContext.Whole,
)

/**
 * Where a piece sits inside its text, for effects that vary per piece (tracking, travelling waves,
 * focus sweeps).
 *
 * [emPx] is the piece's font size in px (0 when unknown; evaluators then fall back to
 * `16 * density`). [textBounds] is the whole text's box in the same coordinate space as
 * `EffectEvalContext.nodeBounds`.
 */
@Immutable
data class PieceContext(
    val index: Int = 0,
    val count: Int = 1,
    val line: Int = 0,
    val indexInLine: Int = 0,
    val lineLength: Int = 1,
    val emPx: Float = 0f,
    val textBounds: Rect? = null,
) {
    companion object {
        val Whole: PieceContext = PieceContext()
    }
}

/**
 * Typed argument bag passed to build effects from declarations or compiled timelines.
 */
@Immutable
data class EffectArgs(
    val values: Map<String, Any?> = emptyMap(),
) {
    fun float(name: String, default: Float): Float = when (val v = values[name]) {
        is Float -> v
        is Double -> v.toFloat()
        is Int -> v.toFloat()
        is Long -> v.toFloat()
        is Number -> v.toFloat()
        is String -> v.toFloatOrNull() ?: default
        else -> default
    }

    fun int(name: String, default: Int): Int = when (val v = values[name]) {
        is Int -> v
        is Long -> v.toInt()
        is Float -> v.toInt()
        is Double -> v.toInt()
        is Number -> v.toInt()
        is String -> v.toIntOrNull() ?: default
        else -> default
    }

    fun long(name: String, default: Long): Long = when (val v = values[name]) {
        is Long -> v
        is Int -> v.toLong()
        is Number -> v.toLong()
        is String -> v.toLongOrNull() ?: default
        else -> default
    }

    fun boolean(name: String, default: Boolean): Boolean = when (val v = values[name]) {
        is Boolean -> v
        is String -> v.toBooleanStrictOrNull() ?: default
        else -> default
    }

    fun string(name: String, default: String): String = when (val v = values[name]) {
        is String -> v
        null -> default
        else -> v.toString()
    }

    fun color(name: String, default: Color): Color = when (val v = values[name]) {
        is Color -> v
        is Int -> Color(v)
        is Long -> Color(v.toULong())
        else -> default
    }

    fun direction8(name: String, default: EffectDirection8): EffectDirection8 = when (val v = values[name]) {
        is EffectDirection8 -> v
        is Int -> EffectDirection8.fromKeynoteCode(v) ?: default
        is String -> EffectDirection8.parse(v) ?: default
        else -> default
    }

    fun irisDirection(name: String, default: IrisDirection): IrisDirection = when (val v = values[name]) {
        is IrisDirection -> v
        is String -> IrisDirection.parse(v) ?: default
        else -> default
    }

    fun lineDrawDirection(name: String, default: LineDrawDirection): LineDrawDirection = when (val v = values[name]) {
        is LineDrawDirection -> v
        is String -> LineDrawDirection.parse(v) ?: default
        else -> default
    }

    fun offset(name: String, default: Offset): Offset = when (val v = values[name]) {
        is Offset -> v
        else -> default
    }

    fun curve(name: String): CurveV2? = when (val v = values[name]) {
        is CurveV2 -> v
        is String -> KeynoteCurves.byName(v)?.let { CurveV2.Preset(v) }
        else -> null
    }

    companion object {
        val Empty: EffectArgs = EffectArgs()

        fun of(vararg pairs: Pair<String, Any?>): EffectArgs =
            if (pairs.isEmpty()) Empty else EffectArgs(linkedMapOf(*pairs))
    }
}

enum class EffectParamType {
    Float,
    Int,
    Long,
    Boolean,
    String,
    Color,
    Direction8,
    IrisDirection,
    LineDrawDirection,
    Offset,
    Curve,

    /** Motion preset channel overrides (`Map<String, MotionChannelSpec>`). */
    Channels,
}

@Immutable
data class EffectParamSpec(
    val name: String,
    val type: EffectParamType,
    val minValue: Float? = null,
    val maxValue: Float? = null,
)

@Immutable
data class EffectDeclaration(
    val id: String,
    val tier: EffectTier,
    val supportedKinds: Set<EffectBuildKind>,
    val supportedNodeKinds: Set<EffectNodeKind> = EffectNodeKind.entries.toSet(),
    val params: List<EffectParamSpec> = emptyList(),
    val unsupportedNodeFallbackId: String? = null,
)

enum class EffectValidationSeverity {
    Warning,
    Error,
}

@Immutable
data class EffectValidationIssue(
    val severity: EffectValidationSeverity,
    val code: String,
    val message: String,
    val fallbackEffectId: String? = null,
)

/**
 * Target category in Ciacola for a Keynote `(keynoteType, keynoteName)` record.
 */
enum class KeynoteTargetCategory {
    /** Mapped to a registered build effect in [BuildEffects]. */
    BuildEffect,

    /** Mapped to an explicit track property (`x`/`y`/`motionPath`, `opacity`, `rotation`, `scale`). */
    ActionTrackProperty,

    /** Mapped to a [SlideTransitionV2] slide transition. */
    SlideTransition,

    /** Mapped to video media playback control (`movie-start`, `renderMovie`). */
    MediaPlayback,
}

/**
 * Delivery granularity implied by a Keynote build effect name (e.g. `apple:fade and move character`).
 */
enum class KeynoteDeliveryMode {
    Object,
    Character,
    Slide,
    Media,
}

/**
 * Exact 1-to-1 mapping entry for each of the 52 Keynote `(type, name)` effect combinations.
 */
@Immutable
data class KeynoteEffectMapping(
    val keynoteType: String,
    val keynoteName: String,
    val category: KeynoteTargetCategory,
    val targetId: String,
    val tier: EffectTier?,
    val buildKind: EffectBuildKind?,
    val deliveryMode: KeynoteDeliveryMode,
    val defaultCurvePreset: String? = null,
    val defaultArgs: EffectArgs = EffectArgs.Empty,
)
