package dev.sebastiano.ciacola.core.fonts

import androidx.compose.runtime.Immutable

/**
 * Helpers for static OpenType variable font axis coordinates declared as `axes` on `.deck.json`
 * typography roles, text nodes, and inline text runs.
 *
 * Coordinates resolve per tag: theme typography role, then text node, then text run. A later
 * level replaces an earlier level's value for the same tag and keeps every other tag.
 */
object FontAxes {

    /**
     * `true` iff [tag] is a well-formed OpenType axis tag: exactly four printable ASCII characters
     * (`0x20..0x7E`), not starting with a space, with spaces only as trailing padding.
     */
    fun isValidTag(tag: String): Boolean {
        if (tag.length != 4) return false
        if (tag[0] == ' ') return false
        var seenSpace = false
        for (ch in tag) {
            if (ch.code !in 0x20..0x7E) return false
            if (ch == ' ') {
                seenSpace = true
            } else if (seenSpace) {
                return false
            }
        }
        return true
    }

    /** Merges [override] over [base] per tag. */
    fun merge(base: Map<String, Double>, override: Map<String, Double>): Map<String, Double> = when {
        override.isEmpty() -> base
        base.isEmpty() -> override
        else -> base + override
    }

    /**
     * Resolves [requested] coordinates against the `fvar` axes a face declares.
     *
     * Invalid tags and non-finite values are dropped, tags the face does not declare are reported
     * in [AxisResolution.unsupported], and values outside `[min, max]` are clamped and reported in
     * [AxisResolution.clamped]. The applied coordinates are sorted by tag so equal requests always
     * produce equal results.
     */
    fun resolve(requested: Map<String, Double>, available: List<VariationAxisRange>): AxisResolution {
        if (requested.isEmpty()) return AxisResolution.Empty
        val byTag = available.associateBy { it.tag }
        val applied = LinkedHashMap<String, Float>()
        val unsupported = LinkedHashSet<String>()
        val invalid = LinkedHashSet<String>()
        val clamped = mutableListOf<ClampedAxis>()
        for ((tag, value) in requested.entries.sortedBy { it.key }) {
            if (!isValidTag(tag) || !value.isFinite()) {
                invalid += tag
                continue
            }
            val axis = byTag[tag]
            if (axis == null) {
                unsupported += tag
                continue
            }
            val lo = minOf(axis.minValue, axis.maxValue).toDouble()
            val hi = maxOf(axis.minValue, axis.maxValue).toDouble()
            val coerced = value.coerceIn(lo, hi)
            if (coerced != value) {
                clamped += ClampedAxis(tag, value, coerced, lo, hi)
            }
            applied[tag] = coerced.toFloat()
        }
        return AxisResolution(
            applied = applied,
            unsupported = unsupported,
            invalid = invalid,
            clamped = clamped,
        )
    }

    /** Stable textual key for [coordinates], used in font identities and cache keys. */
    fun cacheKey(coordinates: Map<String, Float>): String =
        coordinates.entries.sortedBy { it.key }.joinToString(",") { (tag, value) -> "$tag=$value" }
}

/** A requested axis value that fell outside the face's `fvar` range and was clamped. */
@Immutable
data class ClampedAxis(
    val tag: String,
    val requested: Double,
    val applied: Double,
    val min: Double,
    val max: Double,
)

/** Result of resolving requested axis coordinates against a face's `fvar` axes. */
@Immutable
data class AxisResolution(
    /** Coordinates to apply, sorted by tag, already clamped to each axis range. */
    val applied: Map<String, Float>,
    /** Well-formed tags that the face does not declare in `fvar`. They are ignored. */
    val unsupported: Set<String>,
    /** Malformed tags or non-finite values. They are ignored. */
    val invalid: Set<String>,
    /** Values clamped into their axis range. */
    val clamped: List<ClampedAxis>,
) {
    companion object {
        val Empty: AxisResolution = AxisResolution(emptyMap(), emptySet(), emptySet(), emptyList())
    }
}
