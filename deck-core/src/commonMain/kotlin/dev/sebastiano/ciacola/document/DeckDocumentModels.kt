package dev.sebastiano.ciacola.document

import androidx.compose.runtime.Immutable
import dev.sebastiano.ciacola.core.fonts.DeckFontDeclaration
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Current supported `<deckId>.deck.json` schema format version. */
const val CURRENT_DECK_FORMAT_VERSION: Int = 1

@OptIn(ExperimentalSerializationApi::class)
@Immutable
@Serializable
data class DeckDocument(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val format: Int = CURRENT_DECK_FORMAT_VERSION,
    val id: String,
    val title: String = "",
    val stage: StageSize = StageSize(),
    val themes: Map<String, DeckThemeSpec> = emptyMap(),
    val defaultTheme: String? = null,
    val fonts: List<DeckFontDeclaration> = emptyList(),
    val palettes: Map<String, SlidePaletteSpec> = emptyMap(),
    val loopPeriodsMs: Map<String, Long> = emptyMap(),
    val slides: List<SlideSpec> = emptyList(),
)

@OptIn(ExperimentalSerializationApi::class)
@Immutable
@Serializable
data class StageSize(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val width: Int = 1920,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val height: Int = 1080,
)

@Immutable
@Serializable
data class DeckThemeSpec(
    val preset: String = "stageDark",
    val codeScheme: String? = null,
    val customCodeScheme: CodeSchemeSpec? = null,
    val colors: Map<String, String> = emptyMap(),
    val typography: Map<String, TextStyleOverride> = emptyMap(),
    val motion: ThemeMotionDefaults? = null,
)

@Immutable
@Serializable
data class CodeSchemeSpec(
    val id: String,
    val name: String,
    val appearance: String,
    val background: String,
    val foreground: String,
    val selectionBackground: String? = null,
    val lineHighlightBackground: String? = null,
    val lineNumberForeground: String? = null,
    val diffInserted: String? = null,
    val diffDeleted: String? = null,
    val diffChanged: String? = null,
    val tokens: Map<String, CodeTokenStyleSpec> = emptyMap(),
    val source: CodeSchemeSourceSpec? = null,
)

@Immutable
@Serializable
data class CodeTokenStyleSpec(
    val fg: String? = null,
    val bg: String? = null,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strikethrough: Boolean = false,
)

@Immutable
@Serializable
data class CodeSchemeSourceSpec(
    val family: String? = null,
    val upstream: String? = null,
    val license: String? = null,
    val revision: String? = null,
)

@Immutable
@Serializable
data class ThemeMotionDefaults(
    val durationMs: Int? = null,
    val curve: Curve? = null,
    val transition: TransitionSpec? = null,
)

@Immutable
@Serializable
data class TextStyleOverride(
    val font: String? = null,
    val size: Double? = null,
    val weight: Int? = null,
    val italic: Boolean? = null,
    val color: String? = null,
    val lineHeight: Double? = null,
    val tracking: Double? = null,
    val align: String? = null,
    /** Variable font axis coordinates keyed by 4-character OpenType tag (e.g. `wdth`, `GRAD`). */
    val axes: Map<String, Double> = emptyMap(),
)

@Immutable
@Serializable
data class SlidePaletteSpec(
    val canvas: String? = null,
    val ink: String? = null,
    val mutedInk: String? = null,
    val accent: String? = null,
    val surface: String? = null,
    val border: String? = null,
)

@Immutable
@Serializable
data class SlideSpec(
    val id: String,
    val title: String? = null,
    val skipped: Boolean = false,
    val palette: String? = null,
    val background: Fill? = null,
    val beats: List<BeatSpec> = listOf(BeatSpec()),
    val nodes: List<NodeSpec> = emptyList(),
    val transition: TransitionSpec? = null,
)

@Immutable
@Serializable
data class BeatSpec(
    val notes: String? = null,
    val phases: Phases? = null,
    val stagger: Stagger? = null,
    val autoAdvanceMs: Int? = null,
)

@Immutable
@Serializable
data class FrameSpec(
    val x: Double? = null,
    val y: Double? = null,
    val w: Double? = null,
    val h: Double? = null,
)

@Immutable
@Serializable
data class Vec2(
    val x: Double,
    val y: Double,
)

@Immutable
@Serializable(with = ScaleValueSerializer::class)
sealed interface ScaleValue {
    val scaleX: Double
    val scaleY: Double

    @Immutable
    data class Uniform(val value: Double) : ScaleValue {
        override val scaleX: Double get() = value
        override val scaleY: Double get() = value
    }

    @Immutable
    data class NonUniform(val x: Double, val y: Double) : ScaleValue {
        override val scaleX: Double get() = x
        override val scaleY: Double get() = y
    }

    companion object {
        val Identity: ScaleValue = Uniform(1.0)
    }
}

@Immutable
@Serializable(with = PaddingSpecSerializer::class)
data class PaddingSpec(
    val left: Double = 0.0,
    val top: Double = 0.0,
    val right: Double = 0.0,
    val bottom: Double = 0.0,
) {
    constructor(all: Double) : this(left = all, top = all, right = all, bottom = all)

    val isUniform: Boolean
        get() = left == top && top == right && right == bottom
}

@Immutable
@Serializable(with = FillSerializer::class)
sealed interface Fill {
    @Immutable
    data class Solid(val color: String) : Fill

    @Immutable
    data class Gradient(val gradient: GradientSpec) : Fill

    @Immutable
    data class Image(val image: ImageFillSpec) : Fill
}

@Serializable
enum class GradientType {
    @SerialName("linear") Linear,
    @SerialName("radial") Radial,
}

@Immutable
@Serializable
data class GradientStop(
    val at: Double,
    val color: String,
)

@Immutable
@Serializable
data class GradientSpec(
    val type: GradientType = GradientType.Linear,
    val angle: Double? = null,
    val center: Vec2? = null,
    val radius: Double? = null,
    val stops: List<GradientStop>,
)

@Immutable
@Serializable
data class ImageFillSpec(
    val src: String,
    val fit: String? = null,
    val tint: String? = null,
)

@Immutable
@Serializable
data class StrokeSpec(
    val color: String,
    val width: Double = 1.0,
    val dash: List<Double>? = null,
    val cap: String? = null,
    val join: String? = null,
)

@Immutable
@Serializable
data class SurfaceSpec(
    val fill: Fill? = null,
    val stroke: StrokeSpec? = null,
    val cornerRadius: Double? = null,
    val padding: PaddingSpec? = null,
)

@Immutable
@Serializable
data class TextRun(
    val text: String,
    val font: String? = null,
    val size: Double? = null,
    val weight: Int? = null,
    val italic: Boolean? = null,
    val color: String? = null,
    val tracking: Double? = null,
    val underline: Boolean? = null,
    val strike: Boolean? = null,
    val baselineShift: Double? = null,
    val background: String? = null,
    /** Per-tag axis coordinates merged over the enclosing text node's resolved axes. */
    val axes: Map<String, Double> = emptyMap(),
)

@Immutable
@Serializable(with = RichTextValueSerializer::class)
sealed interface RichTextValue {
    val plainText: String

    @Immutable
    data class Plain(val text: String) : RichTextValue {
        override val plainText: String get() = text
    }

    @Immutable
    data class Runs(val runs: List<TextRun>) : RichTextValue {
        override val plainText: String get() = runs.joinToString(separator = "") { it.text }
    }
}

@Serializable
enum class MorphBy {
    @SerialName("word") Word,
    @SerialName("character") Character,
    @SerialName("line") Line,
    @SerialName("paragraph") Paragraph,
}

@Immutable
@Serializable
data class TextMorphSpec(
    val by: MorphBy = MorphBy.Word,
    val pruneStopwords: Boolean = true,
    val phases: Phases? = null,
    val stagger: Stagger? = null,
)

@Immutable
@Serializable
data class TextPayload(
    val value: RichTextValue,
    val style: String? = null,
    val font: String? = null,
    val size: Double? = null,
    val weight: Int? = null,
    val italic: Boolean? = null,
    val color: String? = null,
    val lineHeight: Double? = null,
    val tracking: Double? = null,
    val align: String? = null,
    val surface: SurfaceSpec? = null,
    val morph: TextMorphSpec? = null,
    /** Per-tag axis coordinates merged over the theme typography role's axes. */
    val axes: Map<String, Double> = emptyMap(),
)

@Immutable
@Serializable
data class CodePayload(
    val value: String,
    val language: String? = null,
    val highlightLines: List<Int> = emptyList(),
    val fit: String? = null,
    val surface: SurfaceSpec? = null,
)

@Serializable
enum class HeadStyle {
    @SerialName("none") None,
    @SerialName("open") Open,
    @SerialName("filled") Filled,
}

@Immutable
@Serializable
data class LineHeads(
    val start: HeadStyle = HeadStyle.None,
    val end: HeadStyle = HeadStyle.None,
)

@Immutable
@Serializable(with = ShapeGeometrySerializer::class)
sealed interface ShapeGeometry {
    @Immutable
    data object Rect : ShapeGeometry

    @Immutable
    data object Ellipse : ShapeGeometry

    @Immutable
    data object Line : ShapeGeometry

    @Immutable
    data class Path(val path: String) : ShapeGeometry
}

@Immutable
@Serializable
data class ShapePayload(
    val geometry: ShapeGeometry,
    val fill: Fill? = null,
    val stroke: StrokeSpec? = null,
    val cornerRadius: Double? = null,
    val heads: LineHeads? = null,
)

@Immutable
@Serializable
data class ImagePayload(
    val src: String,
    val fit: String? = null,
    val tint: String? = null,
)

@Immutable
@Serializable
data class ConnectorPayload(
    val from: String,
    val to: String,
    val fromAnchor: String? = null,
    val toAnchor: String? = null,
    val style: String? = null,
    val stroke: StrokeSpec? = null,
    val heads: LineHeads? = null,
    val gap: Double? = null,
)

@Immutable
@Serializable
data class SpecimenTrackOverride(
    val beat: Int? = null,
    val delayMs: Int? = null,
    val durationMs: Int? = null,
    val curve: Curve? = null,
)

@Immutable
@Serializable
data class SpecimenPayload(
    val ref: String,
    val params: Map<String, JsonElement> = emptyMap(),
    val tracks: Map<String, SpecimenTrackOverride> = emptyMap(),
)

@Immutable
@Serializable
data class ContainerPayload(
    val children: List<NodeSpec> = emptyList(),
    val gap: Double? = null,
    val padding: PaddingSpec? = null,
    val alignItems: String? = null,
    val justify: String? = null,
    val surface: SurfaceSpec? = null,
    val clip: Boolean = false,
)

@Immutable
@Serializable
data class LoupePayload(
    val children: List<NodeSpec> = emptyList(),
    val target: String,
    val zoom: Double = 2.0,
    val diameter: Double? = null,
    val align: String? = null,
    val lensFill: Fill? = null,
)

enum class NodeKind(val payloadKey: String) {
    Text("text"),
    Code("code"),
    Shape("shape"),
    Image("image"),
    Connector("connector"),
    Specimen("specimen"),
    Stack("stack"),
    Row("row"),
    Column("column"),
    Loupe("loupe");

    companion object {
        val ALL_PAYLOAD_KEYS: Set<String> = entries.mapTo(linkedSetOf()) { it.payloadKey }
        fun fromPayloadKey(key: String): NodeKind? = entries.firstOrNull { it.payloadKey == key }
    }
}

@Immutable
@Serializable
data class NodeSpec(
    val id: String,
    val frame: FrameSpec? = null,
    val align: String? = null,
    val weight: Double? = null,
    val offset: Vec2? = null,
    val opacity: Double = 1.0,
    val rotation: Double = 0.0,
    val scale: ScaleValue? = null,
    val origin: Vec2? = null,
    val interactive: String? = null,
    val text: TextPayload? = null,
    val code: CodePayload? = null,
    val shape: ShapePayload? = null,
    val image: ImagePayload? = null,
    val connector: ConnectorPayload? = null,
    val specimen: SpecimenPayload? = null,
    val stack: ContainerPayload? = null,
    val row: ContainerPayload? = null,
    val column: ContainerPayload? = null,
    val loupe: LoupePayload? = null,
    val builds: List<Build> = emptyList(),
) {
    /** All non-null payload kinds declared on this node (valid nodes always have size == 1). */
    val payloadKinds: List<NodeKind>
        get() = buildList(1) {
            if (text != null) add(NodeKind.Text)
            if (code != null) add(NodeKind.Code)
            if (shape != null) add(NodeKind.Shape)
            if (image != null) add(NodeKind.Image)
            if (connector != null) add(NodeKind.Connector)
            if (specimen != null) add(NodeKind.Specimen)
            if (stack != null) add(NodeKind.Stack)
            if (row != null) add(NodeKind.Row)
            if (column != null) add(NodeKind.Column)
            if (loupe != null) add(NodeKind.Loupe)
        }

    /** The single payload kind of this node, or `null` if invalid (0 or >1 payloads). */
    val kind: NodeKind?
        get() = payloadKinds.singleOrNull()

    /** Direct child nodes if this node is a container (`stack`, `row`, `column`, or `loupe`). */
    val children: List<NodeSpec>
        get() = stack?.children
            ?: row?.children
            ?: column?.children
            ?: loupe?.children
            ?: emptyList()
}

@Immutable
@Serializable
data class Timing(
    val delayMs: Int? = null,
    val durationMs: Int? = null,
    val curve: Curve? = null,
)

@Immutable
@Serializable
data class Phases(
    val exit: Timing? = null,
    val move: Timing? = null,
    val enter: Timing? = null,
)

@Serializable
enum class StaggerOrder {
    @SerialName("document") Document,
    @SerialName("topToBottom") TopToBottom,
    @SerialName("leftToRight") LeftToRight,
    @SerialName("readingOrder") ReadingOrder,
}

@Immutable
@Serializable
data class Stagger(
    val ms: Int = 0,
    val order: StaggerOrder = StaggerOrder.Document,
    val reverse: Boolean = false,
    val manual: Map<String, Int> = emptyMap(),
    /** How offsets spread across ranks; see [StaggerShaping]. */
    val distribution: StaggerDistribution = StaggerDistribution.Linear,
    /** Optional falloff curve over the normalized rank position. */
    val curve: Curve? = null,
    /** Spread of the last offset in ms; when set it replaces `ms * (count - 1)`. */
    val totalMs: Int? = null,
    /** Seed for [StaggerDistribution.Random]. */
    val seed: Int = 1,
)

@Serializable
enum class StaggerDistribution {
    @SerialName("linear") Linear,
    @SerialName("fromCenter") FromCenter,
    @SerialName("fromEdges") FromEdges,
    @SerialName("random") Random,
}

@Serializable
enum class DeliveryBy {
    @SerialName("whole") Whole,
    @SerialName("child") Child,
    @SerialName("paragraph") Paragraph,
    @SerialName("word") Word,
    @SerialName("character") Character,
}

@Immutable
@Serializable
data class SpringParams(
    val damping: Double,
    val stiffness: Double,
)

@Immutable
@Serializable(with = CurveSerializer::class)
sealed interface Curve {
    @Immutable
    data class Preset(val name: String) : Curve

    @Immutable
    data class Bezier(val x1: Double, val y1: Double, val x2: Double, val y2: Double) : Curve

    @Immutable
    data class Spring(val damping: Double, val stiffness: Double) : Curve

    companion object {
        val Linear: Curve = Preset("linear")
        val EaseInOut: Curve = Preset("easeInOut")
        val EaseOut: Curve = Preset("easeOut")
        val EaseIn: Curve = Preset("easeIn")
    }
}

@OptIn(ExperimentalSerializationApi::class)
@Immutable
@Serializable
@JsonClassDiscriminator("kind")
sealed interface Build {
    val beat: Int
    val id: String?
    val after: String?
    val delayMs: Int?
    val durationMs: Int?
    val curve: Curve?

    @Immutable
    @Serializable
    @SerialName("enter")
    data class Enter(
        override val beat: Int,
        override val id: String? = null,
        val effect: String,
        val direction: String? = null,
        val args: Map<String, JsonElement> = emptyMap(),
        /** Per-channel overrides layered over the effect's own channels (motion presets). */
        val channels: Map<String, MotionChannelSpec> = emptyMap(),
        val by: DeliveryBy = DeliveryBy.Whole,
        val stagger: Stagger? = null,
        override val after: String? = null,
        override val delayMs: Int? = null,
        override val durationMs: Int? = null,
        override val curve: Curve? = null,
    ) : Build

    @Immutable
    @Serializable
    @SerialName("exit")
    data class Exit(
        override val beat: Int,
        override val id: String? = null,
        val effect: String,
        val direction: String? = null,
        val args: Map<String, JsonElement> = emptyMap(),
        /** Per-channel overrides layered over the effect's own channels (motion presets). */
        val channels: Map<String, MotionChannelSpec> = emptyMap(),
        val by: DeliveryBy = DeliveryBy.Whole,
        val stagger: Stagger? = null,
        override val after: String? = null,
        override val delayMs: Int? = null,
        override val durationMs: Int? = null,
        override val curve: Curve? = null,
    ) : Build

    @Immutable
    @Serializable
    @SerialName("emphasis")
    data class Emphasis(
        override val beat: Int,
        override val id: String? = null,
        val effect: String,
        val direction: String? = null,
        val args: Map<String, JsonElement> = emptyMap(),
        /** Per-channel overrides layered over the effect's own channels (motion presets). */
        val channels: Map<String, MotionChannelSpec> = emptyMap(),
        val by: DeliveryBy = DeliveryBy.Whole,
        val stagger: Stagger? = null,
        override val after: String? = null,
        override val delayMs: Int? = null,
        override val durationMs: Int? = null,
        override val curve: Curve? = null,
    ) : Build

    @Immutable
    @Serializable
    @SerialName("change")
    data class Change(
        override val beat: Int,
        override val id: String? = null,
        val set: JsonObject,
        val path: String? = null,
        val morph: TextMorphSpec? = null,
        override val after: String? = null,
        override val delayMs: Int? = null,
        override val durationMs: Int? = null,
        override val curve: Curve? = null,
    ) : Build
}

@Serializable
enum class TransitionType {
    @SerialName("none") None,
    @SerialName("dissolve") Dissolve,
    @SerialName("magicMove") MagicMove,
    @SerialName("push") Push,
    @SerialName("slide") Slide,
    @SerialName("reveal") Reveal,
    @SerialName("wipe") Wipe,
    @SerialName("iris") Iris,
    @SerialName("fadeThroughColor") FadeThroughColor,
}

@Immutable
@Serializable
data class TransitionSpec(
    val type: TransitionType = TransitionType.Dissolve,
    val direction: String? = null,
    val color: String? = null,
    val center: Vec2? = null,
    val delayMs: Int? = null,
    val durationMs: Int? = null,
    val curve: Curve? = null,
    val phases: Phases? = null,
    val stagger: Stagger? = null,
    val text: Map<String, TextMorphSpec> = emptyMap(),
)

// ── Custom Serializers ───────────────────────────────────────────────────────────────────────────

object ScaleValueSerializer : KSerializer<ScaleValue> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("ScaleValue")

    override fun serialize(encoder: Encoder, value: ScaleValue) {
        val jsonEncoder = encoder as? JsonEncoder
            ?: throw SerializationException("ScaleValue only supports JSON serialization.")
        val element: JsonElement = when (value) {
            is ScaleValue.Uniform -> JsonPrimitive(value.value)
            is ScaleValue.NonUniform -> buildJsonObject {
                put("x", JsonPrimitive(value.x))
                put("y", JsonPrimitive(value.y))
            }
        }
        jsonEncoder.encodeJsonElement(element)
    }

    override fun deserialize(decoder: Decoder): ScaleValue {
        val jsonDecoder = decoder as? JsonDecoder
            ?: throw SerializationException("ScaleValue only supports JSON deserialization.")
        return when (val el = jsonDecoder.decodeJsonElement()) {
            is JsonPrimitive -> {
                if (el.isString) throw SerializationException("Scale must be a number or {x, y}, got string '${el.content}'.")
                val v = el.doubleOrNull ?: throw SerializationException("Invalid numeric scale: $el")
                ScaleValue.Uniform(v)
            }
            is JsonObject -> {
                val allowed = setOf("x", "y")
                val unknown = el.keys - allowed
                if (unknown.isNotEmpty()) {
                    throw SerializationException("Unknown keys in scale object: ${unknown.joinToString(", ")}")
                }
                val x = el["x"]?.jsonPrimitive?.takeIf { !it.isString }?.doubleOrNull
                    ?: throw SerializationException("Non-uniform scale requires numeric 'x'.")
                val y = el["y"]?.jsonPrimitive?.takeIf { !it.isString }?.doubleOrNull
                    ?: throw SerializationException("Non-uniform scale requires numeric 'y'.")
                ScaleValue.NonUniform(x, y)
            }
            else -> throw SerializationException("Scale must be a number or {x, y}, got $el")
        }
    }
}

object PaddingSpecSerializer : KSerializer<PaddingSpec> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("PaddingSpec")

    override fun serialize(encoder: Encoder, value: PaddingSpec) {
        val jsonEncoder = encoder as? JsonEncoder
            ?: throw SerializationException("PaddingSpec only supports JSON serialization.")
        val element: JsonElement = if (value.isUniform) {
            JsonPrimitive(value.left)
        } else {
            buildJsonObject {
                put("left", JsonPrimitive(value.left))
                put("top", JsonPrimitive(value.top))
                put("right", JsonPrimitive(value.right))
                put("bottom", JsonPrimitive(value.bottom))
            }
        }
        jsonEncoder.encodeJsonElement(element)
    }

    override fun deserialize(decoder: Decoder): PaddingSpec {
        val jsonDecoder = decoder as? JsonDecoder
            ?: throw SerializationException("PaddingSpec only supports JSON deserialization.")
        return when (val el = jsonDecoder.decodeJsonElement()) {
            is JsonPrimitive -> {
                if (el.isString) throw SerializationException("Padding must be a number or object, got string.")
                val v = el.doubleOrNull ?: throw SerializationException("Invalid padding number: $el")
                PaddingSpec(v)
            }
            is JsonObject -> {
                val allowed = setOf("left", "top", "right", "bottom", "horizontal", "vertical")
                val unknown = el.keys - allowed
                if (unknown.isNotEmpty()) {
                    throw SerializationException("Unknown keys in padding: ${unknown.joinToString(", ")}")
                }
                fun num(key: String): Double? {
                    val v = el[key] ?: return null
                    val prim = v as? JsonPrimitive ?: throw SerializationException("Padding '$key' must be numeric.")
                    if (prim.isString) throw SerializationException("Padding '$key' must be numeric.")
                    return prim.doubleOrNull ?: throw SerializationException("Padding '$key' must be numeric.")
                }
                val h = num("horizontal") ?: 0.0
                val v = num("vertical") ?: 0.0
                PaddingSpec(
                    left = num("left") ?: h,
                    top = num("top") ?: v,
                    right = num("right") ?: h,
                    bottom = num("bottom") ?: v,
                )
            }
            else -> throw SerializationException("Padding must be a number or object, got $el")
        }
    }
}

object FillSerializer : KSerializer<Fill> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("Fill")

    override fun serialize(encoder: Encoder, value: Fill) {
        val jsonEncoder = encoder as? JsonEncoder
            ?: throw SerializationException("Fill only supports JSON serialization.")
        val json = jsonEncoder.json
        val element: JsonElement = when (value) {
            is Fill.Solid -> JsonPrimitive(value.color)
            is Fill.Gradient -> buildJsonObject {
                put("gradient", json.encodeToJsonElement(GradientSpec.serializer(), value.gradient))
            }
            is Fill.Image -> buildJsonObject {
                put("image", json.encodeToJsonElement(ImageFillSpec.serializer(), value.image))
            }
        }
        jsonEncoder.encodeJsonElement(element)
    }

    override fun deserialize(decoder: Decoder): Fill {
        val jsonDecoder = decoder as? JsonDecoder
            ?: throw SerializationException("Fill only supports JSON deserialization.")
        val json = jsonDecoder.json
        return when (val el = jsonDecoder.decodeJsonElement()) {
            is JsonPrimitive -> {
                if (!el.isString) throw SerializationException("Solid fill must be a colour string, got $el")
                Fill.Solid(el.content)
            }
            is JsonObject -> {
                if (el.size != 1) {
                    throw SerializationException("Fill object must have exactly one key ('gradient' or 'image'), got ${el.keys}")
                }
                when {
                    "gradient" in el -> Fill.Gradient(
                        json.decodeFromJsonElement(GradientSpec.serializer(), el.getValue("gradient")),
                    )
                    "image" in el -> Fill.Image(
                        json.decodeFromJsonElement(ImageFillSpec.serializer(), el.getValue("image")),
                    )
                    else -> throw SerializationException("Unknown fill key: ${el.keys.first()}")
                }
            }
            else -> throw SerializationException("Fill must be a colour string or {gradient|image}, got $el")
        }
    }
}

object RichTextValueSerializer : KSerializer<RichTextValue> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("RichTextValue")

    override fun serialize(encoder: Encoder, value: RichTextValue) {
        val jsonEncoder = encoder as? JsonEncoder
            ?: throw SerializationException("RichTextValue only supports JSON serialization.")
        val json = jsonEncoder.json
        val element: JsonElement = when (value) {
            is RichTextValue.Plain -> JsonPrimitive(value.text)
            is RichTextValue.Runs -> JsonArray(
                value.runs.map { run -> json.encodeToJsonElement(TextRun.serializer(), run) },
            )
        }
        jsonEncoder.encodeJsonElement(element)
    }

    override fun deserialize(decoder: Decoder): RichTextValue {
        val jsonDecoder = decoder as? JsonDecoder
            ?: throw SerializationException("RichTextValue only supports JSON deserialization.")
        val json = jsonDecoder.json
        return when (val el = jsonDecoder.decodeJsonElement()) {
            is JsonPrimitive -> {
                if (!el.isString) throw SerializationException("Text value must be a string or array of runs, got $el")
                RichTextValue.Plain(el.content)
            }
            is JsonArray -> RichTextValue.Runs(
                el.map { item -> json.decodeFromJsonElement(TextRun.serializer(), item) },
            )
            else -> throw SerializationException("Text value must be a string or array of runs, got $el")
        }
    }
}

object ShapeGeometrySerializer : KSerializer<ShapeGeometry> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("ShapeGeometry")

    override fun serialize(encoder: Encoder, value: ShapeGeometry) {
        val jsonEncoder = encoder as? JsonEncoder
            ?: throw SerializationException("ShapeGeometry only supports JSON serialization.")
        val element: JsonElement = when (value) {
            ShapeGeometry.Rect -> JsonPrimitive("rect")
            ShapeGeometry.Ellipse -> JsonPrimitive("ellipse")
            ShapeGeometry.Line -> JsonPrimitive("line")
            is ShapeGeometry.Path -> buildJsonObject { put("path", JsonPrimitive(value.path)) }
        }
        jsonEncoder.encodeJsonElement(element)
    }

    override fun deserialize(decoder: Decoder): ShapeGeometry {
        val jsonDecoder = decoder as? JsonDecoder
            ?: throw SerializationException("ShapeGeometry only supports JSON deserialization.")
        return when (val el = jsonDecoder.decodeJsonElement()) {
            is JsonPrimitive -> {
                if (!el.isString) throw SerializationException("Shape geometry must be string or {path}, got $el")
                when (el.content) {
                    "rect" -> ShapeGeometry.Rect
                    "ellipse" -> ShapeGeometry.Ellipse
                    "line" -> ShapeGeometry.Line
                    else -> throw SerializationException("Unknown shape geometry '${el.content}'.")
                }
            }
            is JsonObject -> {
                if (el.keys != setOf("path")) {
                    throw SerializationException("Path geometry must contain only 'path', got ${el.keys}")
                }
                val prim = el.getValue("path") as? JsonPrimitive
                    ?: throw SerializationException("Shape geometry 'path' must be a string.")
                if (!prim.isString) throw SerializationException("Shape geometry 'path' must be a string.")
                ShapeGeometry.Path(prim.content)
            }
            else -> throw SerializationException("Invalid shape geometry: $el")
        }
    }
}

object CurveSerializer : KSerializer<Curve> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("Curve")

    override fun serialize(encoder: Encoder, value: Curve) {
        val jsonEncoder = encoder as? JsonEncoder
            ?: throw SerializationException("Curve only supports JSON serialization.")
        val element: JsonElement = when (value) {
            is Curve.Preset -> JsonPrimitive(value.name)
            is Curve.Bezier -> buildJsonObject {
                put(
                    "bezier",
                    buildJsonArray {
                        add(JsonPrimitive(value.x1))
                        add(JsonPrimitive(value.y1))
                        add(JsonPrimitive(value.x2))
                        add(JsonPrimitive(value.y2))
                    },
                )
            }
            is Curve.Spring -> buildJsonObject {
                put(
                    "spring",
                    buildJsonObject {
                        put("damping", JsonPrimitive(value.damping))
                        put("stiffness", JsonPrimitive(value.stiffness))
                    },
                )
            }
        }
        jsonEncoder.encodeJsonElement(element)
    }

    override fun deserialize(decoder: Decoder): Curve {
        val jsonDecoder = decoder as? JsonDecoder
            ?: throw SerializationException("Curve only supports JSON deserialization.")
        return when (val el = jsonDecoder.decodeJsonElement()) {
            is JsonPrimitive -> {
                if (!el.isString) throw SerializationException("Curve preset must be a string, got $el")
                Curve.Preset(el.content)
            }
            is JsonObject -> {
                if (el.size != 1) {
                    throw SerializationException("Curve object must have exactly one key ('bezier' or 'spring'), got ${el.keys}")
                }
                when {
                    "bezier" in el -> {
                        val arr = el.getValue("bezier").jsonArray
                        if (arr.size != 4) {
                            throw SerializationException("Bezier curve requires 4 control numbers, got ${arr.size}")
                        }
                        val nums = arr.map { item ->
                            val prim = item as? JsonPrimitive
                                ?: throw SerializationException("Bezier entry must be numeric.")
                            if (prim.isString) throw SerializationException("Bezier entry must be numeric.")
                            prim.doubleOrNull ?: throw SerializationException("Invalid bezier number: $item")
                        }
                        Curve.Bezier(nums[0], nums[1], nums[2], nums[3])
                    }
                    "spring" in el -> {
                        val obj = el.getValue("spring").jsonObject
                        val unknown = obj.keys - setOf("damping", "stiffness")
                        if (unknown.isNotEmpty()) {
                            throw SerializationException("Unknown keys in spring curve: ${unknown.joinToString(", ")}")
                        }
                        val damping = obj["damping"]?.let { it as? JsonPrimitive }
                            ?.takeIf { !it.isString }?.doubleOrNull
                            ?: throw SerializationException("Spring curve requires numeric 'damping'.")
                        val stiffness = obj["stiffness"]?.let { it as? JsonPrimitive }
                            ?.takeIf { !it.isString }?.doubleOrNull
                            ?: throw SerializationException("Spring curve requires numeric 'stiffness'.")
                        Curve.Spring(damping = damping, stiffness = stiffness)
                    }
                    else -> throw SerializationException("Unknown curve key: ${el.keys.first()}")
                }
            }
            else -> throw SerializationException("Invalid curve JSON: $el")
        }
    }
}

internal fun JsonElement.isExplicitNull(): Boolean = this is JsonNull
internal fun JsonPrimitive.stringOrNullStrict(): String? = if (isString) contentOrNull else null
