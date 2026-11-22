package dev.sebastiano.ciacola.document

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

internal expect fun identityHashCode(value: Any): Int

/**
 * RFC 7386 JSON Merge Patch implementation and memoized beat-state resolution helpers for
 * [NodeSpec] and [SlideSpec].
 *
 * Rules:
 * - Objects merge key by key; arrays and primitives replace wholesale.
 * - `null` removes the key from the target object (restoring format/code default, never falling
 *   back to base or an earlier `set`).
 * - Resolution runs on [JsonObject] and decodes the resulting node strictly as [NodeSpec], with
 *   bounded thread-safe LRU memoization so 60fps rendering never repeats JSON round-trips.
 */
object JsonMergePatch {

    private const val MAX_NODE_IDENTITY_ENTRIES: Int = 2_048
    private const val MAX_NODE_STRUCTURAL_ENTRIES: Int = 512
    private const val MAX_SLIDE_IDENTITY_ENTRIES: Int = 512
    private const val MAX_SLIDE_STRUCTURAL_ENTRIES: Int = 128
    private const val MAX_AUX_ENTRIES: Int = 1_024

    private class IdentityIntKey(val ref: Any, val index: Int) {
        private val hash: Int = 31 * identityHashCode(ref) + index

        override fun equals(other: Any?): Boolean =
            other is IdentityIntKey && ref === other.ref && index == other.index

        override fun hashCode(): Int = hash
    }

    private data class StructuralIntKey<T>(val value: T, val index: Int)

    private class BoundedLruCache<K : Any, V : Any>(private val maxEntries: Int) {
        private val lock = Any()
        private val entries = LinkedHashMap<K, V>(64)

        fun get(key: K): V? = withDocumentLock(lock) {
            val value = entries.remove(key) ?: return@withDocumentLock null
            entries[key] = value
            value
        }

        fun put(key: K, value: V) = withDocumentLock(lock) {
            entries.remove(key)
            entries[key] = value
            while (entries.size > maxEntries) {
                val eldest = entries.keys.iterator().next()
                entries.remove(eldest)
            }
        }

        fun clear() = withDocumentLock(lock) {
            entries.clear()
        }
    }

    private val statsLock = Any()
    private var cacheHitCount: Long = 0L
    private var cacheMissCount: Long = 0L
    private var jsonRoundTripCount: Long = 0L

    private val nodeAtBeatIdentityCache = BoundedLruCache<IdentityIntKey, NodeSpec>(MAX_NODE_IDENTITY_ENTRIES)
    private val nodeAtBeatStructuralCache = BoundedLruCache<StructuralIntKey<NodeSpec>, NodeSpec>(MAX_NODE_STRUCTURAL_ENTRIES)
    private val slideAtBeatIdentityCache = BoundedLruCache<IdentityIntKey, List<NodeSpec>>(MAX_SLIDE_IDENTITY_ENTRIES)
    private val slideAtBeatStructuralCache = BoundedLruCache<StructuralIntKey<SlideSpec>, List<NodeSpec>>(MAX_SLIDE_STRUCTURAL_ENTRIES)
    private val nodeJsonAtBeatCache = BoundedLruCache<IdentityIntKey, JsonObject>(MAX_AUX_ENTRIES)
    private val nodeBeforeBuildCache = BoundedLruCache<IdentityIntKey, NodeSpec>(MAX_AUX_ENTRIES)
    private val nodeAfterBuildCache = BoundedLruCache<IdentityIntKey, NodeSpec>(MAX_AUX_ENTRIES)

    internal data class ResolutionCacheStats(
        val hits: Long,
        val misses: Long,
        val jsonRoundTrips: Long,
    )

    internal fun cacheStatsForTesting(): ResolutionCacheStats = withDocumentLock(statsLock) {
        ResolutionCacheStats(
            hits = cacheHitCount,
            misses = cacheMissCount,
            jsonRoundTrips = jsonRoundTripCount,
        )
    }

    internal fun clearCachesForTesting() {
        nodeAtBeatIdentityCache.clear()
        nodeAtBeatStructuralCache.clear()
        slideAtBeatIdentityCache.clear()
        slideAtBeatStructuralCache.clear()
        nodeJsonAtBeatCache.clear()
        nodeBeforeBuildCache.clear()
        nodeAfterBuildCache.clear()
        withDocumentLock(statsLock) {
            cacheHitCount = 0L
            cacheMissCount = 0L
            jsonRoundTripCount = 0L
        }
    }

    private fun recordHit() = withDocumentLock(statsLock) { cacheHitCount++ }
    private fun recordMiss(roundTrip: Boolean) = withDocumentLock(statsLock) {
        cacheMissCount++
        if (roundTrip) jsonRoundTripCount++
    }

    @OptIn(ExperimentalSerializationApi::class)
    internal val strictJson: Json = Json {
        ignoreUnknownKeys = false
        encodeDefaults = false
        explicitNulls = false
        isLenient = false
        allowSpecialFloatingPointValues = false
    }

    /**
     * Applies RFC 7386 Merge Patch [patch] to [target].
     *
     * When [patch] is not a [JsonObject], returns [patch] directly.
     * When [patch] is a [JsonObject], recursively merges keys and deletes any member mapped to
     * [JsonNull].
     */
    fun apply(target: JsonElement, patch: JsonElement): JsonElement {
        if (patch !is JsonObject) return patch
        val base = if (target is JsonObject) target else JsonObject(emptyMap())
        val result = LinkedHashMap<String, JsonElement>(base.size + patch.size)
        result.putAll(base)
        for ((key, value) in patch) {
            if (value is JsonNull) {
                result.remove(key)
            } else {
                val existing = result[key]
                result[key] = if (existing is JsonObject && value is JsonObject) {
                    apply(existing, value)
                } else if (value is JsonObject) {
                    // Strip nulls inside a newly introduced sub-object per RFC 7386.
                    apply(JsonObject(emptyMap()), value)
                } else {
                    value
                }
            }
        }
        return JsonObject(result)
    }

    /**
     * Merges [patch] onto an existing RFC 7386 `set` patch object while **preserving** explicit
     * [JsonNull] entries (since `null` inside `set` is the stored removal marker, distinct from
     * an absent key).
     */
    fun composeSetPatch(existingSet: JsonObject, deltaPatch: JsonObject): JsonObject {
        val result = LinkedHashMap<String, JsonElement>(existingSet.size + deltaPatch.size)
        result.putAll(existingSet)
        for ((key, value) in deltaPatch) {
            val prev = result[key]
            result[key] = if (prev is JsonObject && value is JsonObject) {
                composeSetPatch(prev, value)
            } else {
                value
            }
        }
        return JsonObject(result)
    }

    /**
     * Removes every leaf key present in [keysToStrip] from [setPatch], pruning any nested objects
     * that become empty as a result.
     */
    fun stripKeysFromSet(setPatch: JsonObject, keysToStrip: JsonObject): JsonObject {
        val result = LinkedHashMap<String, JsonElement>(setPatch.size)
        for ((key, existingValue) in setPatch) {
            val stripValue = keysToStrip[key]
            when {
                stripValue == null -> result[key] = existingValue
                stripValue is JsonObject && existingValue is JsonObject -> {
                    val pruned = stripKeysFromSet(existingValue, stripValue)
                    if (pruned.isNotEmpty()) {
                        result[key] = pruned
                    }
                }
                else -> Unit // Leaf overridden across all beats -> drop from this beat's set.
            }
        }
        return JsonObject(result)
    }

    /**
     * Resolves all top-level (and nested container) nodes of [slide] at [beat], memoized in a
     * bounded LRU cache keyed by `(slide identity/value, beat)`.
     */
    fun resolveSlideNodesAtBeat(slide: SlideSpec, beat: Int): List<NodeSpec> {
        if (slide.nodes.isEmpty()) return emptyList()
        val idKey = IdentityIntKey(slide, beat)
        slideAtBeatIdentityCache.get(idKey)?.let {
            recordHit()
            return it
        }
        val structKey = StructuralIntKey(slide, beat)
        slideAtBeatStructuralCache.get(structKey)?.let {
            slideAtBeatIdentityCache.put(idKey, it)
            recordHit()
            return it
        }
        recordMiss(roundTrip = false)
        var changed = false
        val resolved = ArrayList<NodeSpec>(slide.nodes.size)
        for (node in slide.nodes) {
            val r = resolveNodeAtBeat(node, beat)
            if (r !== node) changed = true
            resolved.add(r)
        }
        val result = if (changed) resolved else slide.nodes
        slideAtBeatIdentityCache.put(idKey, result)
        slideAtBeatStructuralCache.put(structKey, result)
        return result
    }

    /**
     * Resolves [node]'s raw JSON representation at [beat] by applying every `change` build with
     * `build.beat <= beat` in list order via RFC 7386 Merge Patch.
     */
    fun resolveNodeJsonAtBeat(node: NodeSpec, beat: Int): JsonObject {
        val idKey = IdentityIntKey(node, beat)
        nodeJsonAtBeatCache.get(idKey)?.let {
            recordHit()
            return it
        }
        recordMiss(roundTrip = true)
        val computed = computeResolvedNodeJsonAtBeat(node, beat)
        nodeJsonAtBeatCache.put(idKey, computed)
        return computed
    }

    /**
     * Resolves [node] at [beat] by applying every `change` build with `build.beat <= beat` in list
     * order and decoding the resulting [JsonObject] back to [NodeSpec].
     *
     * Fast-paths nodes with no applicable `Build.Change` without JSON serialization, and memoizes
     * patched results in a bounded LRU cache so per-frame animation renders never repeat JSON
     * encode/decode work.
     */
    fun resolveNodeAtBeat(node: NodeSpec, beat: Int): NodeSpec {
        if (node.builds.isEmpty() && !hasContainerPayload(node)) return node

        val idKey = IdentityIntKey(node, beat)
        nodeAtBeatIdentityCache.get(idKey)?.let {
            recordHit()
            return it
        }

        if (!hasApplicableChangeAtBeat(node, beat)) {
            val withChildren = resolveChildrenAtBeat(node, beat)
            if (withChildren === node) return node
            nodeAtBeatIdentityCache.put(idKey, withChildren)
            return withChildren
        }

        val structKey = StructuralIntKey(node, beat)
        nodeAtBeatStructuralCache.get(structKey)?.let {
            nodeAtBeatIdentityCache.put(idKey, it)
            recordHit()
            return it
        }

        recordMiss(roundTrip = true)
        val resolvedJson = computeResolvedNodeJsonAtBeat(node, beat)
        val decoded = strictJson.decodeFromJsonElement(NodeSpec.serializer(), resolvedJson)
        val result = resolveChildrenAtBeat(decoded, beat)
        nodeAtBeatIdentityCache.put(idKey, result)
        nodeAtBeatStructuralCache.put(structKey, result)
        return result
    }

    /**
     * Resolves [node] immediately before [buildIndex] (applying all earlier `change` builds with
     * `build.beat < targetBeat` OR same beat with index `< buildIndex`).
     */
    fun resolveNodeBeforeBuild(node: NodeSpec, buildIndex: Int): NodeSpec {
        val targetBuild = node.builds[buildIndex]
        if (!hasChangeBefore(node, targetBuild.beat, buildIndex, inclusive = false)) return node
        val idKey = IdentityIntKey(node, buildIndex)
        nodeBeforeBuildCache.get(idKey)?.let {
            recordHit()
            return it
        }
        recordMiss(roundTrip = true)
        var current = strictJson.encodeToJsonElement(NodeSpec.serializer(), node).jsonObject
        for ((idx, b) in chronologicalChangeBuilds(node.builds)) {
            if (b.beat < targetBuild.beat || (b.beat == targetBuild.beat && idx < buildIndex)) {
                current = apply(current, b.set).jsonObject
            }
        }
        val decoded = strictJson.decodeFromJsonElement(NodeSpec.serializer(), current)
        nodeBeforeBuildCache.put(idKey, decoded)
        return decoded
    }

    /**
     * Resolves [node] immediately after [buildIndex] (inclusive of [buildIndex] if it is a `change`).
     */
    fun resolveNodeAfterBuild(node: NodeSpec, buildIndex: Int): NodeSpec {
        val targetBuild = node.builds[buildIndex]
        if (!hasChangeBefore(node, targetBuild.beat, buildIndex, inclusive = true)) return node
        val idKey = IdentityIntKey(node, buildIndex)
        nodeAfterBuildCache.get(idKey)?.let {
            recordHit()
            return it
        }
        recordMiss(roundTrip = true)
        var current = strictJson.encodeToJsonElement(NodeSpec.serializer(), node).jsonObject
        for ((idx, b) in chronologicalChangeBuilds(node.builds)) {
            if (b.beat < targetBuild.beat || (b.beat == targetBuild.beat && idx <= buildIndex)) {
                current = apply(current, b.set).jsonObject
            }
        }
        val decoded = strictJson.decodeFromJsonElement(NodeSpec.serializer(), current)
        nodeAfterBuildCache.put(idKey, decoded)
        return decoded
    }

    /**
     * Returns whether [node] is present at rest on [beat]:
     * - Absent before its earliest `enter` build's beat (a node with no `enter` is present from beat 0).
     * - Absent from its earliest `exit` build's beat onwards (unless re-entered later, evaluated in beat order).
     */
    fun isNodePresentAtBeat(node: NodeSpec, beat: Int): Boolean {
        val hasEnter = node.builds.any { it is Build.Enter }
        var present = !hasEnter
        val ordered = node.builds
            .withIndex()
            .sortedWith(compareBy<IndexedValue<Build>> { it.value.beat }.thenBy { it.index })
        for ((_, build) in ordered) {
            if (build.beat > beat) break
            when (build) {
                is Build.Enter -> present = true
                is Build.Exit -> present = false
                is Build.Emphasis, is Build.Change -> Unit
            }
        }
        return present
    }

    /**
     * Returns whether [node] is visible during `AtStart` (before beat-0 `enter` builds have played):
     * nodes with an `enter` on beat 0 are hidden at `AtStart`; nodes without any `enter` are present.
     */
    fun isNodePresentAtStart(node: NodeSpec): Boolean =
        node.builds.none { it is Build.Enter }

    /**
     * Applies an edit [patch] to [node] while parked on [beat]:
     * - Beat 0: applies [patch] to the base node via RFC 7386 Merge Patch and reconciles any
     *   existing beat-0 `change` builds by stripping the patched keys so [resolveNodeAtBeat]
     *   reflects the edit immediately (pruning empty anonymous beat-0 changes).
     * - Beat `b > 0`: merges [patch] into the **last** `change` build of [node] on beat `b`, or
     *   inserts a new `Build.Change(beat = b, set = patch)` before the first `change` build on a
     *   later beat so chronological override order is preserved.
     */
    fun applyEditAtBeat(node: NodeSpec, beat: Int, patch: JsonObject): NodeSpec {
        require(beat >= 0) { "Beat index must be >= 0, got $beat" }
        if (beat == 0) {
            val baseJson = strictJson.encodeToJsonElement(NodeSpec.serializer(), node).jsonObject
            val merged = apply(baseJson, patch).jsonObject
            val patchedBase = strictJson.decodeFromJsonElement(NodeSpec.serializer(), merged)
            val reconciledBuilds = patchedBase.builds.mapNotNull { build ->
                if (build !is Build.Change || build.beat != 0) {
                    build
                } else {
                    val stripped = stripKeysFromSet(build.set, patch)
                    if (stripped.isEmpty() && build.id == null && build.path == null && build.morph == null) {
                        null
                    } else {
                        build.copy(set = stripped)
                    }
                }
            }
            return patchedBase.copy(builds = reconciledBuilds)
        }
        val lastChangeIdx = node.builds.indexOfLast { it is Build.Change && it.beat == beat }
        val updatedBuilds = node.builds.toMutableList()
        if (lastChangeIdx >= 0) {
            val existing = updatedBuilds[lastChangeIdx] as Build.Change
            updatedBuilds[lastChangeIdx] = existing.copy(set = composeSetPatch(existing.set, patch))
        } else {
            val newChange = Build.Change(beat = beat, set = patch)
            val insertIdx = updatedBuilds.indexOfFirst { it is Build.Change && it.beat > beat }
            if (insertIdx >= 0) {
                updatedBuilds.add(insertIdx, newChange)
            } else {
                updatedBuilds.add(newChange)
            }
        }
        return node.copy(builds = updatedBuilds)
    }

    /**
     * Applies [patch] across all beats (*Apply to all beats*):
     * mutates the base node AND removes all patched keys from every `change` build's `set` so the
     * value holds on every beat. Empty `change` builds with no `id`/`path`/`morph` are pruned.
     */
    fun applyToAllBeats(node: NodeSpec, patch: JsonObject): NodeSpec {
        val baseJson = strictJson.encodeToJsonElement(NodeSpec.serializer(), node).jsonObject
        val mergedBase = apply(baseJson, patch).jsonObject
        val patchedBase = strictJson.decodeFromJsonElement(NodeSpec.serializer(), mergedBase)
        val cleanedBuilds = patchedBase.builds.mapNotNull { build ->
            if (build !is Build.Change) {
                build
            } else {
                val stripped = stripKeysFromSet(build.set, patch)
                if (stripped.isEmpty() && build.id == null && build.path == null && build.morph == null) {
                    null
                } else {
                    build.copy(set = stripped)
                }
            }
        }
        return patchedBase.copy(builds = cleanedBuilds)
    }

    /**
     * Resets the property at [keyPath] on [beat] (*Reset*):
     * - Beat 0: removes [keyPath] from the base node.
     * - Beat `b > 0`: writes explicit `null` at [keyPath] in that beat's last `change` build's
     *   `set` (creating one if needed).
     */
    fun resetAtBeat(node: NodeSpec, beat: Int, keyPath: List<String>): NodeSpec {
        require(keyPath.isNotEmpty()) { "keyPath must not be empty." }
        val nullPatch = nestedPatch(keyPath, JsonNull)
        return applyEditAtBeat(node, beat, nullPatch)
    }

    /** Builds a nested [JsonObject] setting [keyPath] to [leafValue]. */
    fun nestedPatch(keyPath: List<String>, leafValue: JsonElement): JsonObject {
        require(keyPath.isNotEmpty()) { "keyPath must not be empty." }
        var current: JsonElement = leafValue
        for (i in keyPath.lastIndex downTo 0) {
            current = JsonObject(mapOf(keyPath[i] to current))
        }
        return current.jsonObject
    }

    private fun computeResolvedNodeJsonAtBeat(node: NodeSpec, beat: Int): JsonObject {
        var current = strictJson.encodeToJsonElement(NodeSpec.serializer(), node).jsonObject
        for ((_, build) in chronologicalChangeBuilds(node.builds)) {
            if (build.beat <= beat) {
                current = apply(current, build.set).jsonObject
            }
        }
        return current
    }

    /**
     * Returns all [Build.Change] entries paired with their original index, ordered chronologically
     * by `(beat, originalIndex)` so earlier-beat changes never override later beats while same-beat
     * ordering stays intact.
     */
    private fun chronologicalChangeBuilds(builds: List<Build>): List<IndexedValue<Build.Change>> {
        val changes = ArrayList<IndexedValue<Build.Change>>(builds.size)
        var prevBeat = Int.MIN_VALUE
        var outOfOrder = false
        for ((idx, b) in builds.withIndex()) {
            if (b is Build.Change) {
                if (b.beat < prevBeat) outOfOrder = true
                prevBeat = b.beat
                changes.add(IndexedValue(idx, b))
            }
        }
        if (outOfOrder) {
            changes.sortWith(compareBy<IndexedValue<Build.Change>> { it.value.beat }.thenBy { it.index })
        }
        return changes
    }

    private fun hasContainerPayload(node: NodeSpec): Boolean =
        node.stack != null || node.row != null || node.column != null || node.loupe != null

    private fun hasApplicableChangeAtBeat(node: NodeSpec, beat: Int): Boolean {
        for (b in node.builds) {
            if (b is Build.Change && b.beat <= beat) return true
        }
        return false
    }

    private fun hasChangeBefore(node: NodeSpec, targetBeat: Int, buildIndex: Int, inclusive: Boolean): Boolean {
        for ((idx, b) in node.builds.withIndex()) {
            if (b is Build.Change) {
                if (b.beat < targetBeat) return true
                if (b.beat == targetBeat && (if (inclusive) idx <= buildIndex else idx < buildIndex)) return true
            }
        }
        return false
    }

    private fun resolveChildrenList(children: List<NodeSpec>, beat: Int): List<NodeSpec>? {
        if (children.isEmpty()) return null
        var changed = false
        val out = ArrayList<NodeSpec>(children.size)
        for (child in children) {
            val resolved = resolveNodeAtBeat(child, beat)
            if (resolved !== child) changed = true
            out.add(resolved)
        }
        return if (changed) out else null
    }

    private fun resolveChildrenAtBeat(node: NodeSpec, beat: Int): NodeSpec = when {
        node.stack != null -> resolveChildrenList(node.stack.children, beat)?.let {
            node.copy(stack = node.stack.copy(children = it))
        } ?: node

        node.row != null -> resolveChildrenList(node.row.children, beat)?.let {
            node.copy(row = node.row.copy(children = it))
        } ?: node

        node.column != null -> resolveChildrenList(node.column.children, beat)?.let {
            node.copy(column = node.column.copy(children = it))
        } ?: node

        node.loupe != null -> resolveChildrenList(node.loupe.children, beat)?.let {
            node.copy(loupe = node.loupe.copy(children = it))
        } ?: node

        else -> node
    }
}
