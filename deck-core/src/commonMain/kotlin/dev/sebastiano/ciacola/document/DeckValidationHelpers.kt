package dev.sebastiano.ciacola.document

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Shared validation helpers extracted from [DeckDocumentCodec] to keep codec files concise
 * and modular while preserving identical diagnostic codes, severities, and addresses.
 */
internal object DeckValidationHelpers {

    private val JSON_PATH_REGEX: Regex = Regex("""at (?:path|element): (\$(?:\.[^,\s\n]+|\[[^\]]+\])*)""")
    private val PATH_SEGMENT_REGEX: Regex = Regex("""\.([^.\[]+)|\[(\d+)\]""")

    private val BUILD_DESCRIPTORS_BY_KIND: Map<String, SerialDescriptor> = mapOf(
        "enter" to Build.Enter.serializer().descriptor,
        "exit" to Build.Exit.serializer().descriptor,
        "emphasis" to Build.Emphasis.serializer().descriptor,
        "change" to Build.Change.serializer().descriptor,
        "playback" to Build.Playback.serializer().descriptor,
    )

    private val SCALE_KEYS: Set<String> = setOf("x", "y")
    private val PADDING_KEYS: Set<String> = setOf("left", "top", "right", "bottom", "horizontal", "vertical")
    private val FILL_KEYS: Set<String> = setOf("gradient", "image")
    private val CURVE_KEYS: Set<String> = setOf("bezier", "spring")
    private val SHAPE_GEOMETRY_KEYS: Set<String> = setOf("path")
    private val CONTAINER_PAYLOAD_KEYS: Set<String> = setOf("stack", "row", "column", "loupe")

    /**
     * Converts a strict-decoder [SerializationException] or [IllegalArgumentException] into exact
     * [DocumentIssue] entries with semantic `<root>` / `slideId` / `slideId/nodeId` / `#buildId`
     * addresses.
     */
    fun diagnoseDecodeFailure(
        effectiveJson: JsonObject,
        ex: Exception,
    ): List<DocumentIssue> {
        val unknownKeyIssues = mutableListOf<DocumentIssue>()
        collectUnknownKeys(
            element = effectiveJson,
            descriptor = DeckDocument.serializer().descriptor,
            address = "<root>",
            slideAddr = null,
            issues = unknownKeyIssues,
        )
        if (unknownKeyIssues.isNotEmpty()) {
            return unknownKeyIssues
        }

        val resolvedAddress = resolveAddressFromException(effectiveJson, ex)
        return listOf(
            loadError(
                code = "SCHEMA_DECODE_ERROR",
                address = resolvedAddress,
                message = ex.message ?: "Schema decoding failed.",
            ),
        )
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun collectUnknownKeys(
        element: JsonElement,
        descriptor: SerialDescriptor,
        address: String,
        slideAddr: String?,
        issues: MutableList<DocumentIssue>,
    ) {
        val serialName = descriptor.serialName.removeSuffix("?")
        if (serialName.startsWith("kotlinx.serialization.json.Json")) return

        when (serialName) {
            "ScaleValue" -> {
                if (element is JsonObject) {
                    for (k in element.keys - SCALE_KEYS) {
                        issues += loadError("UNKNOWN_KEY", "$address/$k", "Unknown key '$k' in scale at $address.")
                    }
                }
                return
            }

            "PaddingSpec" -> {
                if (element is JsonObject) {
                    for (k in element.keys - PADDING_KEYS) {
                        issues += loadError("UNKNOWN_KEY", "$address/$k", "Unknown key '$k' in padding at $address.")
                    }
                }
                return
            }

            "Fill" -> {
                if (element is JsonObject) {
                    for (k in element.keys - FILL_KEYS) {
                        issues += loadError("UNKNOWN_KEY", "$address/$k", "Unknown key '$k' in fill at $address.")
                    }
                    element["gradient"]?.let {
                        collectUnknownKeys(it, GradientSpec.serializer().descriptor, "$address/gradient", slideAddr, issues)
                    }
                    element["image"]?.let {
                        collectUnknownKeys(it, ImageFillSpec.serializer().descriptor, "$address/image", slideAddr, issues)
                    }
                }
                return
            }

            "Curve" -> {
                if (element is JsonObject) {
                    for (k in element.keys - CURVE_KEYS) {
                        issues += loadError("UNKNOWN_KEY", "$address/$k", "Unknown key '$k' in curve at $address.")
                    }
                    element["spring"]?.let {
                        collectUnknownKeys(it, SpringParams.serializer().descriptor, "$address/spring", slideAddr, issues)
                    }
                }
                return
            }

            "ShapeGeometry" -> {
                if (element is JsonObject) {
                    for (k in element.keys - SHAPE_GEOMETRY_KEYS) {
                        issues += loadError("UNKNOWN_KEY", "$address/$k", "Unknown key '$k' in geometry at $address.")
                    }
                }
                return
            }

            "RichTextValue" -> {
                if (element is JsonArray) {
                    val runDesc = TextRun.serializer().descriptor
                    for ((idx, item) in element.withIndex()) {
                        collectUnknownKeys(item, runDesc, "$address[$idx]", slideAddr, issues)
                    }
                }
                return
            }
        }

        if (descriptor.kind is PolymorphicKind || serialName.endsWith(".Build")) {
            val obj = element as? JsonObject ?: return
            val kind = (obj["kind"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return
            val subDesc = BUILD_DESCRIPTORS_BY_KIND[kind] ?: return
            val allowed = buildSet(subDesc.elementsCount + 1) {
                add("kind")
                for (i in 0 until subDesc.elementsCount) add(subDesc.getElementName(i))
            }
            for ((k, v) in obj) {
                if (k !in allowed) {
                    issues += loadError("UNKNOWN_KEY", "$address/$k", "Unknown key '$k' on build at $address.")
                } else if (k != "kind" && k != "set") {
                    val idx = subDesc.getElementIndex(k)
                    if (idx >= 0) {
                        collectUnknownKeys(v, subDesc.getElementDescriptor(idx), "$address/$k", slideAddr, issues)
                    }
                }
            }
            return
        }

        when (descriptor.kind) {
            StructureKind.MAP -> {
                val obj = element as? JsonObject ?: return
                if (descriptor.elementsCount < 2) return
                val valueDesc = descriptor.getElementDescriptor(1)
                for ((k, v) in obj) {
                    collectUnknownKeys(v, valueDesc, "$address/$k", slideAddr, issues)
                }
            }

            StructureKind.LIST -> {
                val arr = element as? JsonArray ?: return
                if (descriptor.elementsCount < 1) return
                val elemDesc = descriptor.getElementDescriptor(0)
                val elemName = elemDesc.serialName.removeSuffix("?")
                for ((idx, item) in arr.withIndex()) {
                    when {
                        elemName.endsWith(".SlideSpec") -> {
                            val sId = (item as? JsonObject)?.stringField("id")?.ifBlank { null } ?: "slide[$idx]"
                            collectUnknownKeys(item, elemDesc, sId, slideAddr = sId, issues = issues)
                        }

                        elemName.endsWith(".NodeSpec") -> {
                            val nId = (item as? JsonObject)?.stringField("id")?.ifBlank { null } ?: "node[$idx]"
                            val nodeAddr = if (slideAddr != null) "$slideAddr/$nId" else "$address/$nId"
                            collectUnknownKeys(item, elemDesc, nodeAddr, slideAddr = slideAddr, issues = issues)
                        }

                        elemName.endsWith(".Build") -> {
                            val bId = (item as? JsonObject)?.stringField("id")?.ifBlank { null }
                            val buildAddr = if (bId != null) "$address#$bId" else "$address#builds[$idx]"
                            collectUnknownKeys(item, elemDesc, buildAddr, slideAddr = slideAddr, issues = issues)
                        }

                        elemName.endsWith(".BeatSpec") -> {
                            val beatAddr = if (slideAddr != null) "$slideAddr@beat$idx" else "$address[$idx]"
                            collectUnknownKeys(item, elemDesc, beatAddr, slideAddr = slideAddr, issues = issues)
                        }

                        else -> collectUnknownKeys(item, elemDesc, "$address[$idx]", slideAddr = slideAddr, issues = issues)
                    }
                }
            }

            StructureKind.CLASS, StructureKind.OBJECT -> {
                val obj = element as? JsonObject ?: return
                val allowed = HashSet<String>(descriptor.elementsCount)
                for (i in 0 until descriptor.elementsCount) {
                    allowed.add(descriptor.getElementName(i))
                }
                for ((k, v) in obj) {
                    if (k !in allowed) {
                        issues += loadError("UNKNOWN_KEY", "$address/$k", "Unknown key '$k' at $address.")
                    } else {
                        val idx = descriptor.getElementIndex(k)
                        if (idx >= 0) {
                            val childDesc = descriptor.getElementDescriptor(idx)
                            val childAddr = if (k == "builds" || (k == "nodes" && slideAddr != null) ||
                                (k == "children" && slideAddr != null) || (k == "beats" && slideAddr != null)
                            ) {
                                address
                            } else {
                                "$address/$k"
                            }
                            collectUnknownKeys(v, childDesc, childAddr, slideAddr = slideAddr, issues = issues)
                        }
                    }
                }
            }

            else -> Unit
        }
    }

    private fun resolveAddressFromException(effectiveJson: JsonObject, ex: Exception): String {
        // Streaming decode preserves full root-to-leaf JSONPath (e.g. `$.slides[0].nodes[0].opacity`),
        // whereas JsonTreeDecoder only reports the local key inside the innermost object.
        val streamingMessage = try {
            JsonMergePatch.strictJson.decodeFromString(DeckDocument.serializer(), effectiveJson.toString())
            null
        } catch (strEx: SerializationException) {
            strEx.message
        } catch (_: IllegalArgumentException) {
            null
        }
        val rawPath = streamingMessage?.let { JSON_PATH_REGEX.find(it)?.groupValues?.getOrNull(1) }
            ?: ex.message?.let { JSON_PATH_REGEX.find(it)?.groupValues?.getOrNull(1) }
        if (rawPath != null) {
            return resolveSemanticAddress(effectiveJson, rawPath)
        }
        // Custom serializers throwing IllegalArgumentException (e.g. .jsonArray / .jsonObject /
        // .jsonPrimitive on a wrong JSON element kind) omit `at path:`; locate the offending slot.
        return findCustomSerializerShapeErrorAddress(effectiveJson, DeckDocument.serializer().descriptor, "<root>", null)
            ?: "<root>"
    }

    private fun resolveSemanticAddress(root: JsonObject, jsonPath: String): String {
        if (jsonPath == "$") return "<root>"
        var cursor: JsonElement? = root
        var slideAddr: String? = null
        var nodeAddr: String? = null
        var buildAddr: String? = null
        val trailing = mutableListOf<String>()

        val matches = PATH_SEGMENT_REGEX.findAll(jsonPath).toList()
        var i = 0
        while (i < matches.size) {
            val seg = matches[i]
            val prop = seg.groupValues[1].ifEmpty { null }
            val index = seg.groupValues[2].toIntOrNull()

            if (prop != null) {
                val nextIndex = matches.getOrNull(i + 1)?.groupValues?.get(2)?.toIntOrNull()
                if (prop == "slides" && nextIndex != null) {
                    val arr = (cursor as? JsonObject)?.get("slides") as? JsonArray
                    val item = arr?.getOrNull(nextIndex)
                    val sId = (item as? JsonObject)?.stringField("id")?.ifBlank { null } ?: "slide[$nextIndex]"
                    slideAddr = sId
                    nodeAddr = null
                    buildAddr = null
                    trailing.clear()
                    cursor = item
                    i += 2
                    continue
                }
                if ((prop == "nodes" || prop == "children") && nextIndex != null && slideAddr != null) {
                    val arr = (cursor as? JsonObject)?.get(prop) as? JsonArray
                    val item = arr?.getOrNull(nextIndex)
                    val nId = (item as? JsonObject)?.stringField("id")?.ifBlank { null } ?: "node[$nextIndex]"
                    nodeAddr = "$slideAddr/$nId"
                    buildAddr = null
                    trailing.clear()
                    cursor = item
                    i += 2
                    continue
                }
                if (prop == "builds" && nextIndex != null && nodeAddr != null) {
                    val arr = (cursor as? JsonObject)?.get("builds") as? JsonArray
                    val item = arr?.getOrNull(nextIndex)
                    val bId = (item as? JsonObject)?.stringField("id")?.ifBlank { null }
                    buildAddr = if (bId != null) "$nodeAddr#$bId" else "$nodeAddr#builds[$nextIndex]"
                    trailing.clear()
                    cursor = item
                    i += 2
                    continue
                }
                if (prop == "beats" && nextIndex != null && slideAddr != null && nodeAddr == null) {
                    val arr = (cursor as? JsonObject)?.get("beats") as? JsonArray
                    cursor = arr?.getOrNull(nextIndex)
                    slideAddr = "$slideAddr@beat$nextIndex"
                    trailing.clear()
                    i += 2
                    continue
                }
                // Container payload step before `.children[k]` is folded into child nodeId if followed by children[k].
                val nextProp = matches.getOrNull(i + 1)?.groupValues?.get(1)?.ifEmpty { null }
                if (prop in CONTAINER_PAYLOAD_KEYS && nextProp == "children" && slideAddr != null) {
                    cursor = (cursor as? JsonObject)?.get(prop)
                    i++
                    continue
                }
                trailing += prop
                cursor = (cursor as? JsonObject)?.get(prop)
                i++
            } else if (index != null) {
                if (trailing.isNotEmpty()) {
                    trailing[trailing.lastIndex] = "${trailing.last()}[$index]"
                } else {
                    trailing += "[$index]"
                }
                cursor = (cursor as? JsonArray)?.getOrNull(index)
                i++
            } else {
                i++
            }
        }

        val base = buildAddr ?: nodeAddr ?: slideAddr ?: "<root>"
        return if (trailing.isEmpty()) base else "$base/${trailing.joinToString("/")}"
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun findCustomSerializerShapeErrorAddress(
        element: JsonElement,
        descriptor: SerialDescriptor,
        address: String,
        slideAddr: String?,
    ): String? {
        val serialName = descriptor.serialName.removeSuffix("?")
        if (serialName.startsWith("kotlinx.serialization.json.Json")) return null

        when (serialName) {
            "Curve" -> {
                if (element is JsonObject) {
                    val bez = element["bezier"]
                    if (bez != null && bez !is JsonArray) return address
                    val spr = element["spring"]
                    if (spr != null && spr !is JsonObject) return address
                }
                return null
            }

            "ScaleValue" -> {
                if (element is JsonObject) {
                    val x = element["x"]
                    val y = element["y"]
                    if ((x != null && x !is JsonPrimitive) || (y != null && y !is JsonPrimitive)) return address
                }
                return null
            }
        }

        if (descriptor.kind is PolymorphicKind || serialName.endsWith(".Build")) {
            val obj = element as? JsonObject ?: return null
            val kind = obj.stringField("kind") ?: return null
            val subDesc = BUILD_DESCRIPTORS_BY_KIND[kind] ?: return null
            for ((k, v) in obj) {
                if (k == "kind" || k == "set") continue
                val idx = subDesc.getElementIndex(k)
                if (idx >= 0) {
                    findCustomSerializerShapeErrorAddress(v, subDesc.getElementDescriptor(idx), "$address/$k", slideAddr)
                        ?.let { return it }
                }
            }
            return null
        }

        return when (descriptor.kind) {
            StructureKind.MAP -> {
                val obj = element as? JsonObject ?: return null
                if (descriptor.elementsCount < 2) return null
                val valDesc = descriptor.getElementDescriptor(1)
                for ((k, v) in obj) {
                    findCustomSerializerShapeErrorAddress(v, valDesc, "$address/$k", slideAddr)?.let { return it }
                }
                null
            }

            StructureKind.LIST -> {
                val arr = element as? JsonArray ?: return null
                if (descriptor.elementsCount < 1) return null
                val elemDesc = descriptor.getElementDescriptor(0)
                val elemName = elemDesc.serialName.removeSuffix("?")
                for ((idx, item) in arr.withIndex()) {
                    val nextSlide = if (elemName.endsWith(".SlideSpec")) {
                        (item as? JsonObject)?.stringField("id")?.ifBlank { null } ?: "slide[$idx]"
                    } else {
                        slideAddr
                    }
                    val itemAddr = when {
                        elemName.endsWith(".SlideSpec") -> nextSlide!!
                        elemName.endsWith(".NodeSpec") -> {
                            val nId = (item as? JsonObject)?.stringField("id")?.ifBlank { null } ?: "node[$idx]"
                            if (slideAddr != null) "$slideAddr/$nId" else "$address/$nId"
                        }
                        elemName.endsWith(".Build") -> {
                            val bId = (item as? JsonObject)?.stringField("id")?.ifBlank { null }
                            if (bId != null) "$address#$bId" else "$address#builds[$idx]"
                        }
                        elemName.endsWith(".BeatSpec") -> if (slideAddr != null) "$slideAddr@beat$idx" else "$address[$idx]"
                        else -> "$address[$idx]"
                    }
                    findCustomSerializerShapeErrorAddress(item, elemDesc, itemAddr, nextSlide)?.let { return it }
                }
                null
            }

            StructureKind.CLASS, StructureKind.OBJECT -> {
                val obj = element as? JsonObject ?: return null
                for ((k, v) in obj) {
                    val idx = descriptor.getElementIndex(k)
                    if (idx >= 0) {
                        val childDesc = descriptor.getElementDescriptor(idx)
                        val childAddr = if (k == "builds" || (k == "nodes" && slideAddr != null) ||
                            (k == "children" && slideAddr != null) || (k == "beats" && slideAddr != null)
                        ) {
                            address
                        } else {
                            "$address/$k"
                        }
                        findCustomSerializerShapeErrorAddress(v, childDesc, childAddr, slideAddr)?.let { return it }
                    }
                }
                null
            }

            else -> null
        }
    }

    private fun JsonObject.stringField(name: String): String? =
        (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

    fun validateSetSchemaKeys(
        setObj: JsonObject,
        kind: NodeKind?,
        buildAddr: String,
        issues: MutableList<DocumentIssue>,
    ) {
        (setObj["frame"] as? JsonObject)?.keys?.let { keys ->
            for (k in keys - DeckJsonScanner.FRAME_KEYS) {
                issues += loadError("SET_UNKNOWN_KEY", "$buildAddr/set/frame/$k", "Unknown key '$k' in set.frame.")
            }
        }
        (setObj["offset"] as? JsonObject)?.keys?.let { keys ->
            for (k in keys - DeckJsonScanner.VEC2_KEYS) {
                issues += loadError("SET_UNKNOWN_KEY", "$buildAddr/set/offset/$k", "Unknown key '$k' in set.offset.")
            }
        }
        (setObj["origin"] as? JsonObject)?.keys?.let { keys ->
            for (k in keys - DeckJsonScanner.VEC2_KEYS) {
                issues += loadError("SET_UNKNOWN_KEY", "$buildAddr/set/origin/$k", "Unknown key '$k' in set.origin.")
            }
        }
        if (kind != null) {
            val payloadObj = setObj[kind.payloadKey] as? JsonObject ?: return
            val allowed = DeckJsonScanner.PAYLOAD_KEYS_BY_KIND[kind] ?: return
            for (k in payloadObj.keys - allowed) {
                if (k != "children") { // handled specifically as SET_FORBIDDEN_CHILDREN
                    issues += loadError(
                        "SET_UNKNOWN_KEY",
                        "$buildAddr/set/${kind.payloadKey}/$k",
                        "Unknown key '$k' in set.${kind.payloadKey}.",
                    )
                }
            }
        }
    }

    fun validateSlideReferences(
        slide: SlideSpec,
        allNodesById: Map<String, NodeSpec>,
        issues: MutableList<DocumentIssue>,
    ) {
        // 1. Validate connectors
        for ((nodeId, node) in allNodesById) {
            val addr = "${slide.id}/$nodeId"
            node.connector?.let { conn ->
                if (conn.from !in allNodesById) {
                    issues += lintError("DANGLING_CONNECTOR_FROM", addr, "Connector 'from' references missing node '${conn.from}'.")
                }
                if (conn.to !in allNodesById) {
                    issues += lintError("DANGLING_CONNECTOR_TO", addr, "Connector 'to' references missing node '${conn.to}'.")
                }
            }
        }

        // 2. Validate `after` references per beat + cycles
        val buildsByIdPerNode = allNodesById.mapValues { (_, n) ->
            n.builds.withIndex().mapNotNull { (idx, b) -> b.id?.let { it to idx } }.toMap()
        }

        data class BuildRef(val nodeId: String, val buildIndex: Int)

        for (beat in slide.beats.indices) {
            val beatBuilds = mutableListOf<BuildRef>()
            val edges = HashMap<BuildRef, MutableList<BuildRef>>()

            for ((nodeId, node) in allNodesById) {
                var prevOnBeat: BuildRef? = null
                for ((idx, b) in node.builds.withIndex()) {
                    if (b.beat != beat) continue
                    val ref = BuildRef(nodeId, idx)
                    beatBuilds += ref
                    val deps = edges.getOrPut(ref) { mutableListOf() }
                    if (prevOnBeat != null) {
                        deps += prevOnBeat
                    }
                    prevOnBeat = ref

                    val afterRaw = b.after ?: continue
                    val buildAddr = b.id?.let { "${slide.id}/$nodeId#$it" } ?: "${slide.id}/$nodeId#builds[$idx]"
                    val hashIdx = afterRaw.indexOf('#')
                    val targetNodeId = if (hashIdx >= 0) afterRaw.substring(0, hashIdx) else afterRaw
                    val targetBuildId = if (hashIdx >= 0) afterRaw.substring(hashIdx + 1) else null

                    val targetNode = allNodesById[targetNodeId]
                    if (targetNode == null) {
                        issues += lintError("DANGLING_AFTER_TARGET", buildAddr, "'after' references missing node '$targetNodeId'.")
                        continue
                    }

                    val targetIdx = if (targetBuildId != null) {
                        val found = buildsByIdPerNode[targetNodeId]?.get(targetBuildId)
                        if (found == null) {
                            issues += lintError("DANGLING_AFTER_BUILD", buildAddr, "'after' references missing build '$afterRaw'.")
                            continue
                        }
                        if (targetNode.builds[found].beat != beat) {
                            issues += lintError(
                                "AFTER_CROSS_BEAT",
                                buildAddr,
                                "'after' target '$afterRaw' is on beat ${targetNode.builds[found].beat}, not beat $beat.",
                            )
                            continue
                        }
                        found
                    } else {
                        val lastIdx = targetNode.builds.indexOfLast { it.beat == beat }
                        if (lastIdx < 0) {
                            issues += lintError(
                                "AFTER_TARGET_NO_BUILD_ON_BEAT",
                                buildAddr,
                                "'after' target node '$targetNodeId' has no build on beat $beat.",
                            )
                            continue
                        }
                        lastIdx
                    }

                    if (targetNodeId == nodeId && targetIdx >= idx) {
                        issues += lintError(
                            "AFTER_SAME_NODE_LATER_OR_SELF",
                            buildAddr,
                            "'after' cannot target the same or a later build on node '$nodeId'.",
                        )
                        continue
                    }
                    deps += BuildRef(targetNodeId, targetIdx)
                }
            }

            // Detect cycles among builds on this beat
            val state = HashMap<BuildRef, Int>() // 1 = visiting, 2 = done
            fun dfs(u: BuildRef): Boolean {
                state[u] = 1
                for (dep in edges[u].orEmpty()) {
                    when (state[dep]) {
                        1 -> return true
                        null -> if (dfs(dep)) return true
                    }
                }
                state[u] = 2
                return false
            }
            for (ref in beatBuilds) {
                if (state[ref] == null && dfs(ref)) {
                    val b = allNodesById.getValue(ref.nodeId).builds[ref.buildIndex]
                    val buildAddr = b.id?.let { "${slide.id}/${ref.nodeId}#$it" }
                        ?: "${slide.id}/${ref.nodeId}#builds[${ref.buildIndex}]"
                    issues += lintError("AFTER_CYCLE", buildAddr, "Cyclic 'after' dependency detected on beat $beat.")
                }
            }
        }
    }

    fun validateTransition(
        transition: TransitionSpec,
        slideAddr: String,
        issues: MutableList<DocumentIssue>,
    ) {
        val addr = "$slideAddr/transition"
        validateTimingFields(transition.delayMs, transition.durationMs, transition.curve, addr, issues)
        validatePhases(transition.phases, "$addr/phases", issues)
        validateStagger(transition.stagger, "$addr/stagger", isTextContext = false, issues = issues)
        for ((nodeId, morph) in transition.text) {
            validatePhases(morph.phases, "$addr/text/$nodeId/phases", issues)
            validateStagger(morph.stagger, "$addr/text/$nodeId/stagger", isTextContext = true, issues = issues)
        }
    }

    fun validatePhases(phases: Phases?, address: String, issues: MutableList<DocumentIssue>) {
        if (phases == null) return
        phases.exit?.let { validateTimingFields(it.delayMs, it.durationMs, it.curve, "$address/exit", issues) }
        phases.move?.let { validateTimingFields(it.delayMs, it.durationMs, it.curve, "$address/move", issues) }
        phases.enter?.let { validateTimingFields(it.delayMs, it.durationMs, it.curve, "$address/enter", issues) }
    }

    fun validateStagger(
        stagger: Stagger?,
        address: String,
        isTextContext: Boolean,
        issues: MutableList<DocumentIssue>,
    ) {
        if (stagger == null) return
        if (stagger.ms < 0) {
            issues += loadError("NEGATIVE_STAGGER_MS", address, "stagger.ms must be >= 0, got ${stagger.ms}.")
        }
        if (isTextContext && stagger.order == StaggerOrder.LeftToRight) {
            issues += lintError(
                "TEXT_STAGGER_LEFT_TO_RIGHT",
                address,
                "'leftToRight' stagger order requires layout positions and is not valid inside text.",
            )
        }
        for ((k, delay) in stagger.manual) {
            if (delay < 0) {
                issues += loadError(
                    "NEGATIVE_MANUAL_DELAY",
                    "$address/manual/$k",
                    "Manual delay for '$k' must be >= 0, got $delay.",
                )
            }
        }
    }

    fun validateTimingFields(
        delayMs: Int?,
        durationMs: Int?,
        curve: Curve?,
        address: String,
        issues: MutableList<DocumentIssue>,
    ) {
        if (delayMs != null && delayMs < 0) {
            issues += loadError("NEGATIVE_DELAY_MS", address, "delayMs must be >= 0, got $delayMs.")
        }
        if (durationMs != null && durationMs < 0) {
            issues += loadError("NEGATIVE_DURATION_MS", address, "durationMs must be >= 0, got $durationMs.")
        }
        when (curve) {
            is Curve.Spring -> {
                if (curve.damping !in CurveEvaluator.SPRING_MIN_DAMPING..CurveEvaluator.SPRING_MAX_DAMPING) {
                    issues += loadError(
                        "SPRING_DAMPING_OUT_OF_RANGE",
                        address,
                        "Spring damping ${curve.damping} must be in ${CurveEvaluator.SPRING_MIN_DAMPING}..${CurveEvaluator.SPRING_MAX_DAMPING}.",
                    )
                }
                if (curve.stiffness !in CurveEvaluator.SPRING_MIN_STIFFNESS..CurveEvaluator.SPRING_MAX_STIFFNESS) {
                    issues += loadError(
                        "SPRING_STIFFNESS_OUT_OF_RANGE",
                        address,
                        "Spring stiffness ${curve.stiffness} must be in ${CurveEvaluator.SPRING_MIN_STIFFNESS}..${CurveEvaluator.SPRING_MAX_STIFFNESS}.",
                    )
                }
                if (durationMs != null) {
                    issues += lintError(
                        "DURATION_WITH_SPRING",
                        address,
                        "Authoring durationMs ($durationMs) together with a spring curve is a lint error; spring duration is its settle time.",
                    )
                }
            }
            is Curve.Preset -> {
                if (!CurveEvaluator.isKnownPreset(curve.name)) {
                    issues += lintError("UNKNOWN_CURVE_PRESET", address, "Unknown curve preset '${curve.name}'.")
                }
            }
            is Curve.Bezier -> {
                if (curve.x1 !in 0.0..1.0 || curve.x2 !in 0.0..1.0) {
                    issues += loadError(
                        "BEZIER_X_OUT_OF_RANGE",
                        address,
                        "Bezier x1 (${curve.x1}) and x2 (${curve.x2}) must be in 0..1.",
                    )
                }
            }
            null -> Unit
        }
    }

    private fun loadError(code: String, address: String, message: String) =
        DocumentIssue(IssueSeverity.LoadError, code, address, message)

    private fun lintError(code: String, address: String, message: String) =
        DocumentIssue(IssueSeverity.LintError, code, address, message)
}
