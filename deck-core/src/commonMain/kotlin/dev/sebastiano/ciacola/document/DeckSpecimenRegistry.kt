package dev.sebastiano.ciacola.document

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import dev.sebastiano.ciacola.theme.DeckTheme
import dev.sebastiano.ciacola.theme.SlidePalette

/**
 * Typed handle returned by specimen declaration builders (`float`, `uniformFloat`, `int`,
 * `boolean`, `color`, `choice`, `string`). Values are read only inside `content { ... }` through
 * [SpecimenScope.invoke].
 */
@Immutable
data class ParamHandle<T>(
    val spec: ParamSpec<T>,
) {
    val name: String get() = spec.name
}

/**
 * Static declaration and handle for a specimen-owned beat track.
 */
@Immutable
data class TrackHandle(
    val name: String,
    val beat: Int,
    val durationMs: Int,
    val delayMs: Int = 0,
    val curve: Curve = Curve.EaseInOut,
)

/**
 * Static declaration and handle for a specimen-owned loop clock.
 */
@Immutable
data class LoopHandle(
    val name: String,
    val label: String,
    val periodMs: Long,
    val perNode: Boolean = false,
) {
    /** Computes the canonical `loopPeriodsMs` key for this loop. */
    fun loopId(specimenRef: String, slideId: String, nodeId: String): String =
        if (perNode) "$slideId/$nodeId/$name" else "$specimenRef/$name"
}

/**
 * Purpose of a [RenderSession] mount. Explicitly supplied by the session creator; never
 * inferred from `SceneMode`.
 */
enum class RenderPurpose {
    Present,
    Still,
    Video,
}

/**
 * Scope passed to a specimen's `@Composable` content lambda.
 *
 * Intentionally exposes NO root-attached `Transition`: a specimen cannot stretch a root segment's
 * duration.
 */
@Stable
interface SpecimenScope {
    val slideId: String
    val nodeId: String
    val purpose: RenderPurpose

    // Deterministic channel — pure functions of the document clock & render session
    val beat: Int
    val beatsCount: Int
    val fromBeat: Int
    val toBeat: Int
    val segmentFraction: Float
    operator fun <T> ParamHandle<T>.invoke(): T
    operator fun TrackHandle.invoke(): Float
    operator fun LoopHandle.invoke(): Long
    fun LoopHandle.phaseRadians(): Float

    // Context
    val theme: DeckTheme
    val palette: SlidePalette?
    val effectiveBackground: Color?
    val fontScale: Float
    val reducedMotion: Boolean

    @Composable
    fun ProvideSurfaceBackground(color: Color, content: @Composable () -> Unit)

    fun Modifier.stageHitRegion(): Modifier

    @Composable
    fun <T : Any> retained(key: String, init: () -> T): T
}

/**
 * Static, composition-free declaration of a specimen registered via [deckSpecimens].
 */
@Immutable
data class SpecimenDeclaration(
    val id: String,
    val label: String,
    val params: List<ParamSpec<*>>,
    val tracks: List<TrackHandle>,
    val loops: List<LoopHandle>,
    val content: @Composable SpecimenScope.() -> Unit,
) {
    private val paramsByName: Map<String, ParamSpec<*>> = params.associateBy { it.name }
    private val tracksByName: Map<String, TrackHandle> = tracks.associateBy { it.name }
    private val loopsByName: Map<String, LoopHandle> = loops.associateBy { it.name }

    fun param(name: String): ParamSpec<*>? = paramsByName[name]
    fun track(name: String): TrackHandle? = tracksByName[name]
    fun loop(name: String): LoopHandle? = loopsByName[name]
}

/**
 * Immutable registry of statically declared specimens.
 */
@Immutable
class DeckSpecimenRegistry(
    declarations: List<SpecimenDeclaration> = emptyList(),
) {
    private val byId: LinkedHashMap<String, SpecimenDeclaration> = LinkedHashMap<String, SpecimenDeclaration>().apply {
        for (decl in declarations) {
            require(put(decl.id, decl) == null) {
                "Duplicate specimen id '${decl.id}' in DeckSpecimenRegistry."
            }
        }
    }

    val all: List<SpecimenDeclaration> get() = byId.values.toList()
    val ids: Set<String> get() = byId.keys

    fun find(id: String): SpecimenDeclaration? = byId[id]
    operator fun contains(id: String): Boolean = id in byId

    operator fun plus(other: DeckSpecimenRegistry): DeckSpecimenRegistry {
        val merged = LinkedHashMap<String, SpecimenDeclaration>(byId.size + other.byId.size)
        merged.putAll(byId)
        for (decl in other.all) {
            require(merged.put(decl.id, decl) == null) {
                "Duplicate specimen id '${decl.id}' when combining DeckSpecimenRegistry instances."
            }
        }
        return DeckSpecimenRegistry(merged.values.toList())
    }

    companion object {
        val Empty: DeckSpecimenRegistry = DeckSpecimenRegistry()
    }
}

@DslMarker
annotation class DeckSpecimenDsl

@DeckSpecimenDsl
class SpecimenBuilder internal constructor(
    private val id: String,
    private val label: String,
) {
    private val declaredNames = HashSet<String>()
    private val params = mutableListOf<ParamSpec<*>>()
    private val tracks = mutableListOf<TrackHandle>()
    private val loops = mutableListOf<LoopHandle>()
    private var contentBlock: (@Composable SpecimenScope.() -> Unit)? = null

    private fun claimName(name: String, kind: String) {
        require(name.isNotBlank()) { "Specimen '$id' $kind name must not be blank." }
        require(declaredNames.add(name)) {
            "Specimen '$id' declares duplicate member name '$name'."
        }
    }

    fun float(
        name: String,
        default: Float,
        range: ClosedFloatingPointRange<Float>,
        step: Float? = null,
        label: String = name,
    ): ParamHandle<Float> {
        claimName(name, "parameter")
        require(range.start <= range.endInclusive) { "Invalid float range $range for '$name'." }
        require(default in range) { "Default $default for '$name' must lie inside $range." }
        val spec = ParamSpec.FloatParam(name, label, default, range, step, isUniform = false)
        params += spec
        return ParamHandle(spec)
    }

    fun uniformFloat(
        name: String,
        default: Float,
        range: ClosedFloatingPointRange<Float>,
        step: Float? = null,
        label: String = name,
    ): ParamHandle<Float> {
        claimName(name, "uniform parameter")
        require(range.start <= range.endInclusive) { "Invalid uniformFloat range $range for '$name'." }
        require(default in range) { "Default $default for '$name' must lie inside $range." }
        val spec = ParamSpec.FloatParam(name, label, default, range, step, isUniform = true)
        params += spec
        return ParamHandle(spec)
    }

    fun int(
        name: String,
        default: Int,
        range: IntRange,
        step: Int = 1,
        label: String = name,
    ): ParamHandle<Int> {
        claimName(name, "parameter")
        require(range.first <= range.last) { "Invalid int range $range for '$name'." }
        require(default in range) { "Default $default for '$name' must lie inside $range." }
        val spec = ParamSpec.IntParam(name, label, default, range, step)
        params += spec
        return ParamHandle(spec)
    }

    fun boolean(
        name: String,
        default: Boolean,
        label: String = name,
    ): ParamHandle<Boolean> {
        claimName(name, "parameter")
        val spec = ParamSpec.BooleanParam(name, label, default)
        params += spec
        return ParamHandle(spec)
    }

    fun color(
        name: String,
        default: Color,
        label: String = name,
    ): ParamHandle<Color> {
        claimName(name, "parameter")
        val spec = ParamSpec.ColorParam(name, label, default)
        params += spec
        return ParamHandle(spec)
    }

    fun choice(
        name: String,
        default: String,
        choices: List<String>,
        label: String = name,
    ): ParamHandle<String> {
        claimName(name, "parameter")
        require(choices.isNotEmpty()) { "Choice parameter '$name' must offer at least one choice." }
        require(default in choices) { "Default '$default' for '$name' must be one of $choices." }
        val spec = ParamSpec.ChoiceParam(name, label, default, choices)
        params += spec
        return ParamHandle(spec)
    }

    fun string(
        name: String,
        default: String,
        label: String = name,
    ): ParamHandle<String> {
        claimName(name, "parameter")
        val spec = ParamSpec.StringParam(name, label, default)
        params += spec
        return ParamHandle(spec)
    }

    fun track(
        name: String,
        beat: Int,
        durationMs: Int,
        delayMs: Int = 0,
        curve: Curve = Curve.EaseInOut,
    ): TrackHandle {
        claimName(name, "track")
        require(beat >= 0) { "Track '$name' beat must be >= 0, got $beat." }
        require(durationMs >= 0) { "Track '$name' durationMs must be >= 0, got $durationMs." }
        require(delayMs >= 0) { "Track '$name' delayMs must be >= 0, got $delayMs." }
        val handle = TrackHandle(name, beat, durationMs, delayMs, curve)
        tracks += handle
        return handle
    }

    fun loop(
        name: String,
        label: String = name,
        periodMs: Long,
        perNode: Boolean = false,
    ): LoopHandle {
        claimName(name, "loop")
        require(periodMs > 0L) { "Loop '$name' periodMs must be > 0, got $periodMs." }
        val handle = LoopHandle(name, label, periodMs, perNode)
        loops += handle
        return handle
    }

    fun content(block: @Composable SpecimenScope.() -> Unit) {
        require(contentBlock == null) { "Specimen '$id' may declare content {} only once." }
        contentBlock = block
    }

    internal fun build(): SpecimenDeclaration {
        val block = requireNotNull(contentBlock) { "Specimen '$id' must declare a content {} block." }
        return SpecimenDeclaration(
            id = id,
            label = label,
            params = params.toList(),
            tracks = tracks.toList(),
            loops = loops.toList(),
            content = block,
        )
    }
}

@DeckSpecimenDsl
class DeckSpecimensBuilder internal constructor() {
    private val declarations = mutableListOf<SpecimenDeclaration>()

    fun specimen(
        id: String,
        label: String = id,
        block: SpecimenBuilder.() -> Unit,
    ) {
        require(id.isNotBlank()) { "Specimen id must not be blank." }
        val builder = SpecimenBuilder(id, label)
        builder.block()
        declarations += builder.build()
    }

    internal fun build(): DeckSpecimenRegistry = DeckSpecimenRegistry(declarations)
}

/**
 * Builds a static [DeckSpecimenRegistry] outside composition.
 */
fun deckSpecimens(block: DeckSpecimensBuilder.() -> Unit): DeckSpecimenRegistry =
    DeckSpecimensBuilder().apply(block).build()
