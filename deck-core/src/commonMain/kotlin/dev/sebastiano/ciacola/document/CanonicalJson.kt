package dev.sebastiano.ciacola.document

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/**
 * Canonical JSON formatter for `<deckId>.deck.json`.
 *
 * Guarantees:
 * - Two-space indentation, LF (`\n`) line endings, UTF-8, trailing newline.
 * - Typed objects print keys in the order of their [SerialDescriptor].
 * - Objects inside `Build.Change.set` print keys in the order of the patched type ([NodeSpec]).
 * - Name-keyed maps (`palettes`, `themes`, `params`, `args`, `loopPeriodsMs`, `tracks`, `manual`,
 *   `colors`, `typography`, `tokens`, `axes`, `transition.text`) sort lexicographically by key.
 * - Primitive-only objects and arrays whose inline form fits within [MAX_INLINE_COLUMNS] (100)
 *   columns print on one line; everything else prints one child per line.
 * - `encodeDefaults = false`. `null` is printed only inside `set`.
 * - Numbers are never rounded: they print as the shortest decimal that round-trips, with no
 *   trailing `.0`.
 */
object CanonicalJson {

    const val INDENT_SPACES: Int = 2
    const val MAX_INLINE_COLUMNS: Int = 100

    private val SORTED_MAP_KEYS: Set<String> = setOf(
        "palettes",
        "themes",
        "params",
        "args",
        "loopPeriodsMs",
        "tracks",
        "manual",
        "colors",
        "typography",
        "tokens",
        "axes",
    )

    private val BUILD_DESCRIPTORS_BY_KIND: Map<String, SerialDescriptor> = mapOf(
        "enter" to Build.Enter.serializer().descriptor,
        "exit" to Build.Exit.serializer().descriptor,
        "emphasis" to Build.Emphasis.serializer().descriptor,
        "change" to Build.Change.serializer().descriptor,
        "playback" to Build.Playback.serializer().descriptor,
    )

    /** Encodes [document] to a canonical [JsonObject] (`encodeDefaults = false`, nulls only in `set`). */
    fun encodeToJsonObject(document: DeckDocument): JsonObject =
        JsonMergePatch.strictJson.encodeToJsonElement(DeckDocument.serializer(), document).jsonObject

    /** Formats [document] directly to canonical JSON text with trailing newline. */
    fun print(document: DeckDocument): String =
        print(encodeToJsonObject(document), DeckDocument.serializer().descriptor)

    /** Formats [element] to canonical JSON text with trailing newline. */
    fun print(
        element: JsonElement,
        rootDescriptor: SerialDescriptor? = DeckDocument.serializer().descriptor,
    ): String {
        val sb = StringBuilder(4096)
        writeElement(
            sb = sb,
            element = element,
            descriptor = rootDescriptor,
            depth = 0,
            currentColumn = 0,
            insideSet = false,
            forceSortedMap = false,
            parentKey = null,
        )
        sb.append('\n')
        return sb.toString()
    }

    private fun writeElement(
        sb: StringBuilder,
        element: JsonElement,
        descriptor: SerialDescriptor?,
        depth: Int,
        currentColumn: Int,
        insideSet: Boolean,
        forceSortedMap: Boolean,
        parentKey: String?,
    ) {
        when (element) {
            is JsonNull -> sb.append("null")
            is JsonPrimitive -> sb.append(formatPrimitive(element))
            is JsonObject -> writeObject(
                sb = sb,
                obj = element,
                descriptor = descriptor,
                depth = depth,
                currentColumn = currentColumn,
                insideSet = insideSet,
                forceSortedMap = forceSortedMap,
                parentKey = parentKey,
            )
            is JsonArray -> writeArray(
                sb = sb,
                arr = element,
                descriptor = descriptor,
                depth = depth,
                currentColumn = currentColumn,
                insideSet = insideSet,
                parentKey = parentKey,
            )
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun writeObject(
        sb: StringBuilder,
        obj: JsonObject,
        descriptor: SerialDescriptor?,
        depth: Int,
        currentColumn: Int,
        insideSet: Boolean,
        forceSortedMap: Boolean,
        parentKey: String?,
    ) {
        val resolvedDescriptor = resolveEffectiveObjectDescriptor(obj, descriptor, parentKey)
        val isMap = forceSortedMap ||
            resolvedDescriptor?.kind == StructureKind.MAP ||
            (parentKey != null && parentKey in SORTED_MAP_KEYS) ||
            (parentKey == "text" && descriptor?.serialName?.endsWith("TransitionSpec") == true)

        val entries = orderedObjectEntries(obj, resolvedDescriptor, insideSet, isMap)
        if (entries.isEmpty()) {
            sb.append("{}")
            return
        }

        if (entries.all { it.value is JsonPrimitive }) {
            val inline = buildInlineObject(entries)
            if (currentColumn + inline.length <= MAX_INLINE_COLUMNS) {
                sb.append(inline)
                return
            }
        }

        sb.append("{\n")
        val childDepth = depth + 1
        val childIndent = indent(childDepth)
        for ((index, entry) in entries.withIndex()) {
            val key = entry.key
            val value = entry.value
            val childInsideSet = insideSet || key == "set"
            val childDescriptor = childDescriptorForKey(resolvedDescriptor, key, value, isMap, childInsideSet)
            val childForceSortedMap = (key in SORTED_MAP_KEYS) ||
                (key == "text" && resolvedDescriptor?.serialName?.endsWith("TransitionSpec") == true)

            sb.append(childIndent)
            val quotedKey = quoteString(key)
            sb.append(quotedKey)
            sb.append(": ")
            val valueColumn = childIndent.length + quotedKey.length + 2
            writeElement(
                sb = sb,
                element = value,
                descriptor = childDescriptor,
                depth = childDepth,
                currentColumn = valueColumn,
                insideSet = childInsideSet,
                forceSortedMap = childForceSortedMap,
                parentKey = key,
            )
            if (index < entries.lastIndex) {
                sb.append(',')
            }
            sb.append('\n')
        }
        sb.append(indent(depth))
        sb.append('}')
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun writeArray(
        sb: StringBuilder,
        arr: JsonArray,
        descriptor: SerialDescriptor?,
        depth: Int,
        currentColumn: Int,
        insideSet: Boolean,
        parentKey: String?,
    ) {
        if (arr.isEmpty()) {
            sb.append("[]")
            return
        }

        if (arr.all { it is JsonPrimitive }) {
            val inline = buildInlineArray(arr)
            if (currentColumn + inline.length <= MAX_INLINE_COLUMNS) {
                sb.append(inline)
                return
            }
        }

        val itemDescriptor = when {
            descriptor == null -> null
            descriptor.kind == StructureKind.LIST && descriptor.elementsCount > 0 -> descriptor.getElementDescriptor(0)
            descriptor.serialName == "RichTextValue" -> TextRun.serializer().descriptor
            else -> null
        }

        sb.append("[\n")
        val childDepth = depth + 1
        val childIndent = indent(childDepth)
        for ((index, item) in arr.withIndex()) {
            sb.append(childIndent)
            writeElement(
                sb = sb,
                element = item,
                descriptor = itemDescriptor,
                depth = childDepth,
                currentColumn = childIndent.length,
                insideSet = insideSet,
                forceSortedMap = false,
                parentKey = parentKey,
            )
            if (index < arr.lastIndex) {
                sb.append(',')
            }
            sb.append('\n')
        }
        sb.append(indent(depth))
        sb.append(']')
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun resolveEffectiveObjectDescriptor(
        obj: JsonObject,
        descriptor: SerialDescriptor?,
        parentKey: String?,
    ): SerialDescriptor? {
        if (parentKey == "set") return NodeSpec.serializer().descriptor
        if (descriptor == null) return null

        if (descriptor.kind is PolymorphicKind || descriptor.serialName.endsWith(".Build")) {
            val kind = (obj["kind"] as? JsonPrimitive)?.content
            if (kind != null) return BUILD_DESCRIPTORS_BY_KIND[kind]
        }

        return when (descriptor.serialName) {
            "ScaleValue" -> Vec2.serializer().descriptor
            "PaddingSpec" -> PADDING_OBJECT_DESCRIPTOR
            "Fill" -> when {
                "gradient" in obj -> FILL_GRADIENT_WRAPPER
                "image" in obj -> FILL_IMAGE_WRAPPER
                else -> null
            }
            "Curve" -> when {
                "bezier" in obj -> CURVE_BEZIER_WRAPPER
                "spring" in obj -> CURVE_SPRING_WRAPPER
                else -> null
            }
            "ShapeGeometry" -> SHAPE_PATH_WRAPPER
            else -> descriptor
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun childDescriptorForKey(
        parentDescriptor: SerialDescriptor?,
        key: String,
        value: JsonElement,
        parentIsMap: Boolean,
        childInsideSet: Boolean,
    ): SerialDescriptor? {
        if (key == "set") return NodeSpec.serializer().descriptor
        if (parentDescriptor == null) return null

        if (parentIsMap) {
            return if (parentDescriptor.kind == StructureKind.MAP && parentDescriptor.elementsCount >= 2) {
                parentDescriptor.getElementDescriptor(1)
            } else {
                null
            }
        }

        if (parentDescriptor == FILL_GRADIENT_WRAPPER && key == "gradient") return GradientSpec.serializer().descriptor
        if (parentDescriptor == FILL_IMAGE_WRAPPER && key == "image") return ImageFillSpec.serializer().descriptor
        if (parentDescriptor == CURVE_SPRING_WRAPPER && key == "spring") return SpringParams.serializer().descriptor

        val idx = parentDescriptor.getElementIndex(key)
        if (idx >= 0) {
            val child = parentDescriptor.getElementDescriptor(idx)
            if (childInsideSet && value is JsonObject && child.kind == StructureKind.MAP && child.elementsCount >= 2) {
                return child
            }
            return child
        }
        return null
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun orderedObjectEntries(
        obj: JsonObject,
        descriptor: SerialDescriptor?,
        insideSet: Boolean,
        isMap: Boolean,
    ): List<Map.Entry<String, JsonElement>> {
        val filtered = if (insideSet) {
            obj.entries.toList()
        } else {
            obj.entries.filter { it.value !is JsonNull }
        }
        if (filtered.size <= 1) return filtered

        if (isMap || descriptor == null || descriptor.kind == StructureKind.MAP) {
            return filtered.sortedBy { it.key }
        }

        val order = HashMap<String, Int>(descriptor.elementsCount + 1)
        var slot = 0
        if ("kind" in obj && descriptor in BUILD_DESCRIPTORS_BY_KIND.values) {
            order["kind"] = slot++
        }
        for (i in 0 until descriptor.elementsCount) {
            val name = descriptor.getElementName(i)
            if (name !in order) {
                order[name] = slot++
            }
        }
        return filtered.sortedWith(
            compareBy<Map.Entry<String, JsonElement>> { order[it.key] ?: Int.MAX_VALUE }
                .thenBy { it.key },
        )
    }

    private fun buildInlineObject(entries: List<Map.Entry<String, JsonElement>>): String {
        val sb = StringBuilder(64)
        sb.append("{ ")
        for ((idx, entry) in entries.withIndex()) {
            if (idx > 0) sb.append(", ")
            sb.append(quoteString(entry.key))
            sb.append(": ")
            sb.append(formatPrimitive(entry.value as JsonPrimitive))
        }
        sb.append(" }")
        return sb.toString()
    }

    private fun buildInlineArray(arr: JsonArray): String {
        val sb = StringBuilder(64)
        sb.append('[')
        for ((idx, item) in arr.withIndex()) {
            if (idx > 0) sb.append(", ")
            sb.append(formatPrimitive(item as JsonPrimitive))
        }
        sb.append(']')
        return sb.toString()
    }

    internal fun formatPrimitive(primitive: JsonPrimitive): String {
        if (primitive is JsonNull) return "null"
        if (primitive.isString) return quoteString(primitive.content)
        primitive.booleanOrNull?.let { return if (it) "true" else "false" }
        primitive.longOrNull?.let { return it.toString() }
        val dbl = primitive.doubleOrNull
        if (dbl != null && dbl.isFinite()) {
            return formatShortestDecimal(dbl)
        }
        return primitive.content
    }

    /**
     * Formats [value] as the shortest decimal string that round-trips to the exact same IEEE-754
     * double, stripping any trailing `.0` and normalizing `-0.0` to `0`.
     */
    internal fun formatShortestDecimal(value: Double): String {
        if (value == 0.0) return "0"
        val raw = value.toString()
        if (!raw.contains('E') && !raw.contains('e')) {
            return stripTrailingDotZero(raw)
        }
        val longCandidate = value.toLong()
        if (longCandidate.toDouble() == value && value > -1e15 && value < 1e15) {
            return longCandidate.toString()
        }
        val lowered = raw.lowercase()
        val eIdx = lowered.indexOf('e')
        return if (eIdx > 0) {
            stripTrailingDotZero(lowered.substring(0, eIdx)) + lowered.substring(eIdx)
        } else {
            lowered
        }
    }

    private fun stripTrailingDotZero(decimal: String): String {
        if (!decimal.contains('.')) return decimal
        var end = decimal.length
        while (end > 0 && decimal[end - 1] == '0') {
            end--
        }
        if (end > 0 && decimal[end - 1] == '.') {
            end--
        }
        return if (end == 0 || (end == 1 && decimal[0] == '-')) "0" else decimal.substring(0, end)
    }

    private fun quoteString(value: String): String {
        val sb = StringBuilder(value.length + 2)
        sb.append('"')
        for (ch in value) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (ch.code < 0x20) {
                    sb.append("\\u")
                    sb.append(ch.code.toString(16).padStart(4, '0'))
                } else {
                    sb.append(ch)
                }
            }
        }
        sb.append('"')
        return sb.toString()
    }

    private fun indent(depth: Int): String = " ".repeat(depth * INDENT_SPACES)

    @kotlinx.serialization.Serializable
    private data class PaddingObj(val left: Double, val top: Double, val right: Double, val bottom: Double)

    @kotlinx.serialization.Serializable
    private data class FillGradWrapper(val gradient: GradientSpec)

    @kotlinx.serialization.Serializable
    private data class FillImgWrapper(val image: ImageFillSpec)

    @kotlinx.serialization.Serializable
    private data class CurveBezWrapper(val bezier: List<Double>)

    @kotlinx.serialization.Serializable
    private data class CurveSprWrapper(val spring: SpringParams)

    @kotlinx.serialization.Serializable
    private data class ShapePathWrapper(val path: String)

    private val PADDING_OBJECT_DESCRIPTOR: SerialDescriptor = PaddingObj.serializer().descriptor
    private val FILL_GRADIENT_WRAPPER: SerialDescriptor = FillGradWrapper.serializer().descriptor
    private val FILL_IMAGE_WRAPPER: SerialDescriptor = FillImgWrapper.serializer().descriptor
    private val CURVE_BEZIER_WRAPPER: SerialDescriptor = CurveBezWrapper.serializer().descriptor
    private val CURVE_SPRING_WRAPPER: SerialDescriptor = CurveSprWrapper.serializer().descriptor
    private val SHAPE_PATH_WRAPPER: SerialDescriptor = ShapePathWrapper.serializer().descriptor
}
