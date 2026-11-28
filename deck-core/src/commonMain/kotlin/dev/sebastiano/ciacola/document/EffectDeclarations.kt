package dev.sebastiano.ciacola.document

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlin.math.roundToInt

/**
 * Shared typed parameter specification used by both Specimen declarations and Build
 * Effect argument declarations.
 */
@Immutable
sealed interface ParamSpec<T> {
    val name: String
    val label: String
    val defaultValue: T
    val group: String?

    @Immutable
    data class FloatParam(
        override val name: String,
        override val label: String = name,
        override val defaultValue: Float,
        val range: ClosedFloatingPointRange<Float>,
        val step: Float? = null,
        val isUniform: Boolean = false,
    ) : ParamSpec<Float> {
        override val group: String? get() = if (isUniform) "Uniforms" else null
    }

    @Immutable
    data class IntParam(
        override val name: String,
        override val label: String = name,
        override val defaultValue: Int,
        val range: IntRange,
        val step: Int = 1,
    ) : ParamSpec<Int> {
        override val group: String? get() = null
    }

    @Immutable
    data class BooleanParam(
        override val name: String,
        override val label: String = name,
        override val defaultValue: Boolean,
    ) : ParamSpec<Boolean> {
        override val group: String? get() = null
    }

    @Immutable
    data class ColorParam(
        override val name: String,
        override val label: String = name,
        override val defaultValue: Color,
    ) : ParamSpec<Color> {
        override val group: String? get() = null
    }

    @Immutable
    data class ChoiceParam(
        override val name: String,
        override val label: String = name,
        override val defaultValue: String,
        val choices: List<String>,
    ) : ParamSpec<String> {
        override val group: String? get() = null
    }

    @Immutable
    data class StringParam(
        override val name: String,
        override val label: String = name,
        override val defaultValue: String,
    ) : ParamSpec<String> {
        override val group: String? get() = null
    }
}

enum class ParamDiagnosticKind {
    UndeclaredKey,
    WrongType,
    OutOfRange,
    InvalidChoice,
}

@Immutable
data class ParamDiagnostic(
    val key: String,
    val kind: ParamDiagnosticKind,
    val message: String,
) {
    val isWarningOnly: Boolean get() = kind == ParamDiagnosticKind.OutOfRange
}

@Immutable
data class ParamResolution<T>(
    val value: T,
    val diagnostic: ParamDiagnostic? = null,
)

/**
 * Validates, resolves, clamps, and interpolates typed parameters against [ParamSpec].
 */
object ParamResolver {

    @Suppress("UNCHECKED_CAST")
    fun <T> resolve(
        spec: ParamSpec<T>,
        raw: JsonElement?,
        colorResolver: (String) -> Color? = ::parseHexColorOrNull,
    ): ParamResolution<T> {
        if (raw == null) return ParamResolution(spec.defaultValue)
        return when (spec) {
            is ParamSpec.FloatParam -> resolveFloat(spec, raw) as ParamResolution<T>
            is ParamSpec.IntParam -> resolveInt(spec, raw) as ParamResolution<T>
            is ParamSpec.BooleanParam -> resolveBoolean(spec, raw) as ParamResolution<T>
            is ParamSpec.ColorParam -> resolveColor(spec, raw, colorResolver) as ParamResolution<T>
            is ParamSpec.ChoiceParam -> resolveChoice(spec, raw) as ParamResolution<T>
            is ParamSpec.StringParam -> resolveString(spec, raw) as ParamResolution<T>
        }
    }

    fun validateMap(
        specs: List<ParamSpec<*>>,
        values: Map<String, JsonElement>,
        colorResolver: (String) -> Color? = ::parseHexColorOrNull,
    ): List<ParamDiagnostic> {
        val byName = specs.associateBy { it.name }
        val diagnostics = mutableListOf<ParamDiagnostic>()
        for ((key, raw) in values) {
            val spec = byName[key]
            if (spec == null) {
                diagnostics += ParamDiagnostic(
                    key = key,
                    kind = ParamDiagnosticKind.UndeclaredKey,
                    message = "Parameter '$key' is not declared.",
                )
                continue
            }
            resolve(spec, raw, colorResolver).diagnostic?.let { diagnostics += it }
        }
        return diagnostics
    }

    @Suppress("UNCHECKED_CAST")
    fun <T> interpolate(
        spec: ParamSpec<T>,
        from: T,
        to: T,
        progress: Float,
    ): T = when (spec) {
        is ParamSpec.FloatParam -> {
            val a = from as Float
            val b = to as Float
            val v = a + (b - a) * progress
            v.coerceIn(spec.range.start, spec.range.endInclusive) as T
        }
        is ParamSpec.IntParam -> {
            val a = from as Int
            val b = to as Int
            val v = (a + (b - a) * progress).roundToInt()
            v.coerceIn(spec.range.first, spec.range.last) as T
        }
        is ParamSpec.ColorParam -> {
            val a = from as Color
            val b = to as Color
            androidx.compose.ui.graphics.lerp(a, b, progress.coerceIn(0f, 1f)) as T
        }
        is ParamSpec.BooleanParam,
        is ParamSpec.ChoiceParam,
        is ParamSpec.StringParam,
        -> if (progress >= 0.5f) to else from
    }

    private fun resolveFloat(spec: ParamSpec.FloatParam, raw: JsonElement): ParamResolution<Float> {
        val prim = raw as? JsonPrimitive
        val num = prim?.takeIf { !it.isString }?.doubleOrNull
        if (num == null || !num.isFinite()) {
            return ParamResolution(
                value = spec.defaultValue,
                diagnostic = ParamDiagnostic(spec.name, ParamDiagnosticKind.WrongType, "Expected number for '${spec.name}', got $raw."),
            )
        }
        val f = num.toFloat()
        if (f < spec.range.start || f > spec.range.endInclusive) {
            return ParamResolution(
                value = f.coerceIn(spec.range.start, spec.range.endInclusive),
                diagnostic = ParamDiagnostic(
                    spec.name,
                    ParamDiagnosticKind.OutOfRange,
                    "Value $f for '${spec.name}' is outside ${spec.range.start}..${spec.range.endInclusive}; clamped.",
                ),
            )
        }
        return ParamResolution(f)
    }

    private fun resolveInt(spec: ParamSpec.IntParam, raw: JsonElement): ParamResolution<Int> {
        val prim = raw as? JsonPrimitive
        val num = prim?.takeIf { !it.isString }?.intOrNull
        if (num == null) {
            return ParamResolution(
                value = spec.defaultValue,
                diagnostic = ParamDiagnostic(spec.name, ParamDiagnosticKind.WrongType, "Expected integer for '${spec.name}', got $raw."),
            )
        }
        if (num !in spec.range) {
            return ParamResolution(
                value = num.coerceIn(spec.range.first, spec.range.last),
                diagnostic = ParamDiagnostic(
                    spec.name,
                    ParamDiagnosticKind.OutOfRange,
                    "Value $num for '${spec.name}' is outside ${spec.range.first}..${spec.range.last}; clamped.",
                ),
            )
        }
        return ParamResolution(num)
    }

    private fun resolveBoolean(spec: ParamSpec.BooleanParam, raw: JsonElement): ParamResolution<Boolean> {
        val prim = raw as? JsonPrimitive
        val bool = prim?.takeIf { !it.isString }?.booleanOrNull
        if (bool == null) {
            return ParamResolution(
                value = spec.defaultValue,
                diagnostic = ParamDiagnostic(spec.name, ParamDiagnosticKind.WrongType, "Expected boolean for '${spec.name}', got $raw."),
            )
        }
        return ParamResolution(bool)
    }

    private fun resolveColor(
        spec: ParamSpec.ColorParam,
        raw: JsonElement,
        colorResolver: (String) -> Color?,
    ): ParamResolution<Color> {
        val str = (raw as? JsonPrimitive)?.stringOrNullStrict()
        if (str == null) {
            return ParamResolution(
                value = spec.defaultValue,
                diagnostic = ParamDiagnostic(spec.name, ParamDiagnosticKind.WrongType, "Expected colour string for '${spec.name}', got $raw."),
            )
        }
        val parsed = colorResolver(str)
        if (parsed == null) {
            return ParamResolution(
                value = spec.defaultValue,
                diagnostic = ParamDiagnostic(spec.name, ParamDiagnosticKind.WrongType, "Invalid colour '$str' for '${spec.name}'."),
            )
        }
        return ParamResolution(parsed)
    }

    private fun resolveChoice(spec: ParamSpec.ChoiceParam, raw: JsonElement): ParamResolution<String> {
        val str = (raw as? JsonPrimitive)?.stringOrNullStrict()
        if (str == null) {
            return ParamResolution(
                value = spec.defaultValue,
                diagnostic = ParamDiagnostic(spec.name, ParamDiagnosticKind.WrongType, "Expected choice string for '${spec.name}', got $raw."),
            )
        }
        if (str !in spec.choices) {
            return ParamResolution(
                value = spec.defaultValue,
                diagnostic = ParamDiagnostic(
                    spec.name,
                    ParamDiagnosticKind.InvalidChoice,
                    "Choice '$str' is not offered for '${spec.name}' (allowed: ${spec.choices.joinToString(", ")}).",
                ),
            )
        }
        return ParamResolution(str)
    }

    private fun resolveString(spec: ParamSpec.StringParam, raw: JsonElement): ParamResolution<String> {
        val str = (raw as? JsonPrimitive)?.stringOrNullStrict()
        if (str == null) {
            return ParamResolution(
                value = spec.defaultValue,
                diagnostic = ParamDiagnostic(spec.name, ParamDiagnosticKind.WrongType, "Expected string for '${spec.name}', got $raw."),
            )
        }
        return ParamResolution(str)
    }

    /** Parses `#RRGGBB` or `#AARRGGBB` sRGB hex colour strings. */
    fun parseHexColorOrNull(raw: String): Color? {
        if (!raw.startsWith('#')) return null
        val hex = raw.substring(1)
        val argb: Long = when (hex.length) {
            6 -> (0xFF000000L or (hex.toLongOrNull(16) ?: return null))
            8 -> hex.toLongOrNull(16) ?: return null
            else -> return null
        }
        return Color(argb)
    }
}

/**
 * Effect execution tier.
 */
enum class EffectTier {
    Layer,
    Path,
    Content,
    Text,
}

/**
 * Static metadata declaration for a registered build or emphasis effect.
 *
 * Used by validation and the pure planner without invoking Compose or rendering.
 */
@Immutable
data class BuildEffectDeclaration(
    val id: String,
    val tier: EffectTier,
    val supportedNodeKinds: Set<NodeKind> = NodeKind.entries.toSet(),
    val supportsPieceDelivery: Boolean = false,
    val supportedDirections: Set<String> = emptySet(),
    val args: List<ParamSpec<*>> = emptyList(),
    val defaultDurationMs: Int = 500,
    /**
     * Explicit default curve for this effect, or `null` when the effect owns its own built-in
     * Keynote curve(s) (e.g. `fadeMove`'s dual position/alpha curves, `moveIn`, `pop`, `zoomBig`)
     * that must not be overridden unless a curve is authored on the build or phase.
     */
    val defaultCurve: Curve? = null,
    val isEmphasis: Boolean = false,
    val pieceStartMs: ((index: Int, count: Int, stagger: Stagger?, args: Map<String, JsonElement>) -> Int)? = null,
)

/**
 * Registry of build and emphasis effect metadata declarations.
 */
class BuildEffectRegistry(
    declarations: List<BuildEffectDeclaration>,
) {
    private val byId: Map<String, BuildEffectDeclaration> = declarations.associateBy { it.id }

    fun find(id: String): BuildEffectDeclaration? = byId[id]
    val all: List<BuildEffectDeclaration> get() = byId.values.toList()

    operator fun plus(other: BuildEffectRegistry): BuildEffectRegistry =
        BuildEffectRegistry((byId + other.byId).values.toList())

    companion object {
        private val DIRECTIONS_8WAY: Set<String> = setOf(
            "left", "right", "up", "down",
            "upLeft", "upRight", "downLeft", "downRight",
        )
        private val DIRECTIONS_IRIS: Set<String> = setOf("in", "out")
        private val DIRECTIONS_LINE_DRAW: Set<String> = setOf(
            "startToEnd", "endToStart", "fromCenter", "clockwise", "counterClockwise",
        )

        /** Seeded deterministic keyboard per-keystroke offset. */
        internal fun keyboardPieceStartMs(
            index: Int,
            count: Int,
            stagger: Stagger?,
            args: Map<String, JsonElement>,
        ): Int {
            if (index <= 0 || count <= 1) return 0
            val charsPerSec = (args["charsPerSec"] as? JsonPrimitive)
                ?.takeIf { !it.isString }?.doubleOrNull?.coerceIn(1.0, 120.0) ?: 22.0
            val seed = (args["seed"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull ?: 1
            val baseStepMs = stagger?.ms?.takeIf { it > 0 }?.toDouble() ?: (1000.0 / charsPerSec)
            var elapsed = 0.0
            for (k in 1..index) {
                // Deterministic splitmix-style hash in [-0.25, +0.25] of baseStepMs.
                var x = (seed.toLong() * 0x9E3779B97F4A7C15UL.toLong()) xor (k.toLong() * 0xBF58476D1CE4E5B9UL.toLong())
                x = (x xor (x ushr 30)) * 0xBF58476D1CE4E5B9UL.toLong()
                x = (x xor (x ushr 27)) * 0x94D049BB133111EBUL.toLong()
                x = x xor (x ushr 31)
                val unit = ((x ushr 40).toDouble() / (1L shl 24).toDouble()) // [0, 1)
                val jitter = (unit - 0.5) * 0.5 // [-0.25, +0.25)
                elapsed += (baseStepMs * (1.0 + jitter)).coerceAtLeast(1.0)
            }
            return elapsed.roundToInt()
        }

        /** Complete Keynote-style build and emphasis effect declaration catalog. */
        val Standard: BuildEffectRegistry = BuildEffectRegistry(
            listOf(
                BuildEffectDeclaration(
                    id = "appear",
                    tier = EffectTier.Layer,
                    supportsPieceDelivery = true,
                    defaultDurationMs = 0,
                ),
                BuildEffectDeclaration(
                    id = "dissolve",
                    tier = EffectTier.Layer,
                    supportsPieceDelivery = true,
                    defaultDurationMs = 500,
                ),
                BuildEffectDeclaration(
                    id = "fadeMove",
                    tier = EffectTier.Layer,
                    supportsPieceDelivery = true,
                    supportedDirections = DIRECTIONS_8WAY,
                    args = listOf(
                        ParamSpec.FloatParam("distance", defaultValue = 48f, range = 0f..1920f),
                    ),
                    defaultDurationMs = 600,
                ),
                BuildEffectDeclaration(
                    id = "moveIn",
                    tier = EffectTier.Layer,
                    supportsPieceDelivery = true,
                    supportedDirections = DIRECTIONS_8WAY,
                    args = listOf(
                        ParamSpec.BooleanParam("bounce", defaultValue = false),
                    ),
                    defaultDurationMs = 650,
                ),
                BuildEffectDeclaration(
                    id = "wipe",
                    tier = EffectTier.Layer,
                    supportedDirections = DIRECTIONS_8WAY,
                    defaultDurationMs = 600,
                ),
                BuildEffectDeclaration(
                    id = "iris",
                    tier = EffectTier.Layer,
                    supportedDirections = DIRECTIONS_IRIS,
                    args = listOf(
                        ParamSpec.FloatParam("centerX", defaultValue = 0.5f, range = 0f..1f),
                        ParamSpec.FloatParam("centerY", defaultValue = 0.5f, range = 0f..1f),
                    ),
                    defaultDurationMs = 600,
                ),
                BuildEffectDeclaration(
                    id = "zoom",
                    tier = EffectTier.Layer,
                    supportsPieceDelivery = true,
                    args = listOf(
                        ParamSpec.FloatParam("fromScale", defaultValue = 0.8f, range = 0.01f..20f),
                        ParamSpec.FloatParam("toScale", defaultValue = 1f, range = 0.01f..20f),
                        ParamSpec.FloatParam("scaleDelay", defaultValue = 0f, range = 0f..0.85f, step = 0.01f),
                    ),
                    defaultDurationMs = 500,
                ),
                BuildEffectDeclaration(
                    id = "pop",
                    tier = EffectTier.Layer,
                    supportsPieceDelivery = true,
                    defaultDurationMs = 450,
                ),
                BuildEffectDeclaration(
                    id = "blur",
                    tier = EffectTier.Layer,
                    args = listOf(
                        ParamSpec.FloatParam("radius", defaultValue = 24f, range = 0f..200f),
                    ),
                    defaultDurationMs = 500,
                ),
                BuildEffectDeclaration(
                    id = "fromDarkness",
                    tier = EffectTier.Layer,
                    defaultDurationMs = 700,
                ),
                BuildEffectDeclaration(
                    id = "keyboard",
                    tier = EffectTier.Text,
                    supportedNodeKinds = setOf(NodeKind.Text),
                    supportsPieceDelivery = true,
                    args = listOf(
                        ParamSpec.IntParam("seed", defaultValue = 1, range = Int.MIN_VALUE..Int.MAX_VALUE),
                        ParamSpec.BooleanParam("caret", defaultValue = true),
                        ParamSpec.FloatParam("charsPerSec", defaultValue = 22f, range = 1f..120f),
                    ),
                    defaultDurationMs = 50,
                    pieceStartMs = ::keyboardPieceStartMs,
                ),
                BuildEffectDeclaration(
                    id = "lineDraw",
                    tier = EffectTier.Path,
                    supportedNodeKinds = setOf(NodeKind.Shape, NodeKind.Connector),
                    supportedDirections = DIRECTIONS_LINE_DRAW,
                    args = listOf(
                        ParamSpec.BooleanParam("includeEndpoints", defaultValue = true),
                    ),
                    defaultDurationMs = 800,
                ),
                BuildEffectDeclaration(
                    id = "trace",
                    tier = EffectTier.Content,
                    supportedDirections = DIRECTIONS_8WAY,
                    defaultDurationMs = 1000,
                ),
                BuildEffectDeclaration(
                    id = "sparkle",
                    tier = EffectTier.Content,
                    supportedDirections = DIRECTIONS_8WAY,
                    defaultDurationMs = 900,
                ),
                BuildEffectDeclaration(
                    id = "shimmer",
                    tier = EffectTier.Content,
                    defaultDurationMs = 900,
                ),
                BuildEffectDeclaration(
                    id = "jiggle",
                    tier = EffectTier.Layer,
                    supportsPieceDelivery = true,
                    isEmphasis = true,
                    args = listOf(
                        ParamSpec.FloatParam("amplitude", defaultValue = 6f, range = 0f..90f),
                        ParamSpec.IntParam("cycles", defaultValue = 3, range = 1..20),
                    ),
                    defaultDurationMs = 500,
                ),
            ) + MotionPresetDeclarations.all,
        )
    }
}
