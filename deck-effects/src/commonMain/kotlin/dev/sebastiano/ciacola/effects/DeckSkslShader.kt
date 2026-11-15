package dev.sebastiano.ciacola.effects

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import dev.sebastiano.ciacola.inspector.DeckElementOverridesFile
import dev.sebastiano.ciacola.inspector.StageShaderUniformSpec
import dev.sebastiano.ciacola.timeline.LocalDeckLoopClock
import dev.sebastiano.ciacola.timeline.rememberDeckLoopMillis
import kotlin.math.PI

/**
 * Typed uniform value bound into a [DeckSkslShader] on each draw pass.
 */
sealed interface DeckShaderUniformValue {
    /** Single-component SkSL `float` uniform value. */
    data class FloatValue(val value: Float) : DeckShaderUniformValue

    /** Two-component SkSL `float2` uniform value. */
    data class Float2Value(val x: Float, val y: Float) : DeckShaderUniformValue

    /** Three-component SkSL `float3` uniform value. */
    data class Float3Value(val x: Float, val y: Float, val z: Float) : DeckShaderUniformValue

    /** Fixed-length SkSL `float[N]` (or packed vector array) uniform values. */
    data class FloatArrayValue(val values: List<Float>) : DeckShaderUniformValue
}

/**
 * Declarative receiver passed to [DeckSkslShader]'s `uniforms` lambda for binding deterministic
 * loop time, fixed uniforms, and inspector-tunable float parameters.
 */
@Stable
class DeckShaderUniformScope internal constructor(
    private val elementId: String,
    internal var overrides: DeckElementOverridesFile,
) {
    private val liveValues = mutableStateMapOf<String, DeckShaderUniformValue>()
    private val specsByName = LinkedHashMap<String, StageShaderUniformSpec>()

    internal val boundValues: Map<String, DeckShaderUniformValue>
        get() = liveValues

    internal val declaredSpecs: List<StageShaderUniformSpec>
        get() = specsByName.values.toList()

    private fun putUniform(name: String, value: DeckShaderUniformValue) {
        if (liveValues[name] != value) {
            liveValues[name] = value
        }
    }

    /** Binds a non-tunable SkSL `float` uniform named [name]. */
    fun float(name: String, value: Float) {
        putUniform(name, DeckShaderUniformValue.FloatValue(value))
    }

    /** Binds a SkSL `float2` uniform named [name]. */
    fun float2(name: String, x: Float, y: Float) {
        putUniform(name, DeckShaderUniformValue.Float2Value(x, y))
    }

    /** Binds a SkSL `float3` uniform named [name]. */
    fun float3(name: String, x: Float, y: Float, z: Float) {
        putUniform(name, DeckShaderUniformValue.Float3Value(x, y, z))
    }

    /** Binds a SkSL `float[N]` array uniform named [name]. */
    fun floatArray(name: String, vararg values: Float) {
        putUniform(name, DeckShaderUniformValue.FloatArrayValue(values.toList()))
    }

    /**
     * Declares an inspector-tunable SkSL `float` uniform named [name] clamped to [range].
     *
     * Reads any live override from `LocalElementOverrides` (`ElementPatch.uniforms[name]`),
     * records a [StageShaderUniformSpec] on the shader's `StageNodeSnapshot`, binds the resolved
     * value, and returns it so slide telemetry can stay in sync.
     */
    fun uniformFloat(
        name: String,
        default: Float,
        range: ClosedFloatingPointRange<Float> = 0f..1f,
    ): Float {
        val raw = overrides.patchFor(elementId)?.uniforms?.get(name) ?: default
        val resolved = raw.coerceIn(range.start, range.endInclusive)
        specsByName[name] = StageShaderUniformSpec(
            name = name,
            default = default,
            min = range.start,
            max = range.endInclusive,
        )
        putUniform(name, DeckShaderUniformValue.FloatValue(resolved))
        return resolved
    }

    /**
     * Binds deterministic, scrubbable loop time driven by [rememberDeckLoopMillis] to [name].
     *
     * When [scaleRadians] is `true`, emits `[0, 2π)` over the effective loop period so periodic
     * shaders loop seamlessly; otherwise emits elapsed seconds (`loopMs / 1000f`).
     */
    @Composable
    fun time(
        name: String = "iTime",
        loopId: String = "$elementId:loop",
        label: String = loopId,
        periodMillis: Long = 10_000L,
        harmonicChannels: List<String> = listOf(
            "Primary Phase (0..2π)",
            "Drift sin(ωt)",
            "Counter-Drift cos(ωt)",
        ),
        scaleRadians: Boolean = true,
    ): Float {
        val safePeriod = periodMillis.coerceAtLeast(1L)
        val clock = LocalDeckLoopClock.current
        val effectivePeriod = clock.effectivePeriodMillis(loopId, safePeriod).coerceAtLeast(1L)
        val loopMs = rememberDeckLoopMillis(
            loopId = loopId,
            label = label,
            periodMillis = safePeriod,
            harmonicChannels = harmonicChannels,
        )
        val value = if (scaleRadians) {
            ((loopMs.toDouble() / effectivePeriod.toDouble()) * (2.0 * PI)).toFloat()
        } else {
            loopMs.toFloat() / 1_000f
        }
        float(name, value)
        return value
    }
}

/**
 * First-class SkSL fragment shader primitive (`half4 main(float2 fragCoord)`) with cached
 * compilation, deterministic loop-clock time, inspector-tunable float uniforms, drag-preview
 * offset support, and an inline red diagnostic overlay on invalid SkSL.
 */
@Composable
expect fun DeckSkslShader(
    id: String,
    sksl: String,
    modifier: Modifier = Modifier,
    label: String = id,
    fallbackColor: Color = Color(0xFF1E1A1D),
    overlay: @Composable BoxScope.() -> Unit = {},
    uniforms: @Composable DeckShaderUniformScope.() -> Unit = {},
)

/** Optional test observer notified of bound uniform maps for each composed [DeckSkslShader]. */
val LocalShaderUniformObserver =
    compositionLocalOf<((shaderId: String, uniforms: Map<String, DeckShaderUniformValue>) -> Unit)?> { null }

/**
 * Compiles [sksl] against the platform shader runtime and returns `null` on success, or the
 * compiler diagnostic message on failure.
 */
expect fun validateSkslShader(sksl: String): String?
