package dev.sebastiano.ciacola.inspector.design

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import dev.sebastiano.ciacola.theme.contrastRatio
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Hue (`0..360`), saturation (`0..1`), value (`0..1`), and alpha (`0..1`) color representation.
 */
@Immutable
data class HsvColor(
    val hue: Float,
    val saturation: Float,
    val value: Float,
    val alpha: Float = 1f,
)

/**
 * Hue (`0..360`), saturation (`0..1`), lightness (`0..1`), and alpha (`0..1`) color representation.
 */
@Immutable
data class HslColor(
    val hue: Float,
    val saturation: Float,
    val lightness: Float,
    val alpha: Float = 1f,
)

/**
 * Converts a [Color] to a 32-bit unsigned ARGB [Long] (`0xAARRGGBBL`).
 */
fun Color.toArgbLong(): Long {
    val a = (alpha * 255f + 0.5f).toInt().coerceIn(0, 255)
    val r = (red * 255f + 0.5f).toInt().coerceIn(0, 255)
    val g = (green * 255f + 0.5f).toInt().coerceIn(0, 255)
    val b = (blue * 255f + 0.5f).toInt().coerceIn(0, 255)
    return ((a.toLong() shl 24) or (r.toLong() shl 16) or (g.toLong() shl 8) or b.toLong()) and 0xFFFFFFFFL
}

private fun Int.toHexByte(): String {
    val v = coerceIn(0, 255)
    val digits = "0123456789ABCDEF"
    return "${digits[v ushr 4]}${digits[v and 0xF]}"
}

/**
 * Formats a [Color] as `#RRGGBB` or `#AARRGGBB` (when [includeAlpha] is true or alpha < 1).
 */
fun Color.toHexString(includeAlpha: Boolean = false): String {
    val a = (alpha * 255f + 0.5f).toInt().coerceIn(0, 255)
    val r = (red * 255f + 0.5f).toInt().coerceIn(0, 255)
    val g = (green * 255f + 0.5f).toInt().coerceIn(0, 255)
    val b = (blue * 255f + 0.5f).toInt().coerceIn(0, 255)
    return if (includeAlpha || a < 255) {
        "#${a.toHexByte()}${r.toHexByte()}${g.toHexByte()}${b.toHexByte()}"
    } else {
        "#${r.toHexByte()}${g.toHexByte()}${b.toHexByte()}"
    }
}

/**
 * Formats a [Color] as a Kotlin `0xAARRGGBBL` literal string.
 */
fun Color.toKotlinHexLiteral(): String {
    val argb = toArgbLong()
    val a = ((argb ushr 24) and 0xFFL).toInt()
    val r = ((argb ushr 16) and 0xFFL).toInt()
    val g = ((argb ushr 8) and 0xFFL).toInt()
    val b = (argb and 0xFFL).toInt()
    return "0x${a.toHexByte()}${r.toHexByte()}${g.toHexByte()}${b.toHexByte()}L"
}

/**
 * Parses `#RRGGBB`, `RRGGBB`, `#AARRGGBB`, `AARRGGBB`, or `0xAARRGGBB` into a [Color], or `null` if invalid.
 */
fun parseHexColorOrNull(
    raw: String,
    defaultAlpha: Float = 1f,
): Color? {
    val cleaned = raw.trim()
        .removePrefix("#")
        .removePrefix("0x")
        .removePrefix("0X")
        .removeSuffix("L")
        .removeSuffix("l")
    if (cleaned.length != 6 && cleaned.length != 8) return null
    if (!cleaned.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null

    return if (cleaned.length == 6) {
        val r = cleaned.substring(0, 2).toInt(16)
        val g = cleaned.substring(2, 4).toInt(16)
        val b = cleaned.substring(4, 6).toInt(16)
        val a = (defaultAlpha.coerceIn(0f, 1f) * 255f).roundToInt().coerceIn(0, 255)
        Color(red = r, green = g, blue = b, alpha = a)
    } else {
        val a = cleaned.substring(0, 2).toInt(16)
        val r = cleaned.substring(2, 4).toInt(16)
        val g = cleaned.substring(4, 6).toInt(16)
        val b = cleaned.substring(6, 8).toInt(16)
        Color(red = r, green = g, blue = b, alpha = a)
    }
}

/**
 * Converts an sRGB [Color] to [HsvColor].
 */
fun Color.toHsv(): HsvColor {
    val r = red.coerceIn(0f, 1f)
    val g = green.coerceIn(0f, 1f)
    val b = blue.coerceIn(0f, 1f)
    val maxC = max(r, max(g, b))
    val minC = min(r, min(g, b))
    val delta = maxC - minC

    val hue = when {
        delta < 1e-6f -> 0f
        maxC == r -> 60f * (((g - b) / delta) % 6f)
        maxC == g -> 60f * (((b - r) / delta) + 2f)
        else -> 60f * (((r - g) / delta) + 4f)
    }.let { if (it < 0f) it + 360f else it }

    val saturation = if (maxC < 1e-6f) 0f else delta / maxC
    return HsvColor(
        hue = hue.coerceIn(0f, 360f),
        saturation = saturation.coerceIn(0f, 1f),
        value = maxC.coerceIn(0f, 1f),
        alpha = alpha.coerceIn(0f, 1f),
    )
}

/**
 * Converts [HsvColor] components into an sRGB [Color].
 */
fun hsvToColor(
    hue: Float,
    saturation: Float,
    value: Float,
    alpha: Float = 1f,
): Color {
    val h = ((hue % 360f) + 360f) % 360f
    val s = saturation.coerceIn(0f, 1f)
    val v = value.coerceIn(0f, 1f)
    val a = alpha.coerceIn(0f, 1f)

    val c = v * s
    val x = c * (1f - abs((h / 60f) % 2f - 1f))
    val m = v - c

    val (r1, g1, b1) = when {
        h < 60f -> Triple(c, x, 0f)
        h < 120f -> Triple(x, c, 0f)
        h < 180f -> Triple(0f, c, x)
        h < 240f -> Triple(0f, x, c)
        h < 300f -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    return Color(
        red = (r1 + m).coerceIn(0f, 1f),
        green = (g1 + m).coerceIn(0f, 1f),
        blue = (b1 + m).coerceIn(0f, 1f),
        alpha = a,
    )
}

/**
 * Converts an sRGB [Color] to [HslColor].
 */
fun Color.toHsl(): HslColor {
    val r = red.coerceIn(0f, 1f)
    val g = green.coerceIn(0f, 1f)
    val b = blue.coerceIn(0f, 1f)
    val maxC = max(r, max(g, b))
    val minC = min(r, min(g, b))
    val delta = maxC - minC
    val l = (maxC + minC) * 0.5f

    val s = if (delta < 1e-6f) {
        0f
    } else {
        delta / (1f - abs(2f * l - 1f)).coerceAtLeast(1e-6f)
    }

    val h = when {
        delta < 1e-6f -> 0f
        maxC == r -> 60f * (((g - b) / delta) % 6f)
        maxC == g -> 60f * (((b - r) / delta) + 2f)
        else -> 60f * (((r - g) / delta) + 4f)
    }.let { if (it < 0f) it + 360f else it }

    return HslColor(
        hue = h.coerceIn(0f, 360f),
        saturation = s.coerceIn(0f, 1f),
        lightness = l.coerceIn(0f, 1f),
        alpha = alpha.coerceIn(0f, 1f),
    )
}

/**
 * Converts [HslColor] components into an sRGB [Color].
 */
fun hslToColor(
    hue: Float,
    saturation: Float,
    lightness: Float,
    alpha: Float = 1f,
): Color {
    val h = ((hue % 360f) + 360f) % 360f
    val s = saturation.coerceIn(0f, 1f)
    val l = lightness.coerceIn(0f, 1f)
    val a = alpha.coerceIn(0f, 1f)

    val c = (1f - abs(2f * l - 1f)) * s
    val x = c * (1f - abs((h / 60f) % 2f - 1f))
    val m = l - c * 0.5f

    val (r1, g1, b1) = when {
        h < 60f -> Triple(c, x, 0f)
        h < 120f -> Triple(x, c, 0f)
        h < 180f -> Triple(0f, c, x)
        h < 240f -> Triple(0f, x, c)
        h < 300f -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    return Color(
        red = (r1 + m).coerceIn(0f, 1f),
        green = (g1 + m).coerceIn(0f, 1f),
        blue = (b1 + m).coerceIn(0f, 1f),
        alpha = a,
    )
}

/**
 * Promotes nearly-transparent (`< 0.05f`) alpha to opaque (`1f`) when picking a hue/SV color so
 * picking onto a cleared or default-transparent fill immediately produces a visible color.
 */
fun resolvePickedAlpha(currentAlpha: Float): Float =
    if (currentAlpha < 0.05f) 1f else currentAlpha.coerceIn(0f, 1f)

/**
 * Alpha-composites [foreground] over opaque [background] for accurate WCAG contrast evaluation.
 */
fun compositeOver(foreground: Color, background: Color): Color {
    val a = foreground.alpha.coerceIn(0f, 1f)
    if (a >= 0.999f) return foreground.copy(alpha = 1f)
    val bgR = background.red.coerceIn(0f, 1f)
    val bgG = background.green.coerceIn(0f, 1f)
    val bgB = background.blue.coerceIn(0f, 1f)
    return Color(
        red = (foreground.red * a + bgR * (1f - a)).coerceIn(0f, 1f),
        green = (foreground.green * a + bgG * (1f - a)).coerceIn(0f, 1f),
        blue = (foreground.blue * a + bgB * (1f - a)).coerceIn(0f, 1f),
        alpha = 1f,
    )
}

/**
 * Computes WCAG 2.1 contrast ratio (`1f..21f`) between [foreground] (composited if translucent) and
 * [background].
 */
fun wcagContrastRatio(foreground: Color, background: Color): Float =
    contrastRatio(compositeOver(foreground, background), background.copy(alpha = 1f))

/**
 * Computes the worst (minimum) WCAG 2.1 contrast ratio of [foreground] against [primaryBackground]
 * and an optional [secondaryBackground].
 */
fun worstWcagContrastRatio(
    foreground: Color,
    primaryBackground: Color,
    secondaryBackground: Color? = null,
): Float {
    val primary = wcagContrastRatio(foreground, primaryBackground)
    return if (secondaryBackground != null) {
        min(primary, wcagContrastRatio(foreground, secondaryBackground))
    } else {
        primary
    }
}

/**
 * Nudges [foreground]'s HSL lightness (preserving hue and saturation) until its WCAG 2.1 contrast
 * ratio meets [targetRatio] (default `4.55f` for safe AA compliance) against [primaryBackground]
 * and [secondaryBackground]. If both lighter and darker adjustments can satisfy the target, picks
 * the minimal lightness delta.
 */
fun fixColorForWcagAa(
    foreground: Color,
    primaryBackground: Color,
    secondaryBackground: Color? = null,
    targetRatio: Float = 4.55f,
): Color {
    fun score(candidate: Color, includeSecondary: Boolean): Float =
        worstWcagContrastRatio(
            foreground = candidate,
            primaryBackground = primaryBackground,
            secondaryBackground = if (includeSecondary) secondaryBackground else null,
        )

    if (score(foreground, includeSecondary = true) >= targetRatio) return foreground

    val hsl = foreground.toHsl()

    fun searchDirection(includeSecondary: Boolean, alphaOverride: Float): Color? {
        val lighterExtreme = hslToColor(hsl.hue, hsl.saturation, 1f, alphaOverride)
        val darkerExtreme = hslToColor(hsl.hue, hsl.saturation, 0f, alphaOverride)

        val lighterCandidate: Pair<Float, Color>? = if (score(lighterExtreme, includeSecondary) >= targetRatio) {
            var lo = hsl.lightness
            var hi = 1f
            repeat(28) {
                val mid = (lo + hi) * 0.5f
                val probe = hslToColor(hsl.hue, hsl.saturation, mid, alphaOverride)
                if (score(probe, includeSecondary) >= targetRatio) hi = mid else lo = mid
            }
            (hi - hsl.lightness) to hslToColor(hsl.hue, hsl.saturation, hi, alphaOverride)
        } else {
            null
        }

        val darkerCandidate: Pair<Float, Color>? = if (score(darkerExtreme, includeSecondary) >= targetRatio) {
            var lo = 0f
            var hi = hsl.lightness
            repeat(28) {
                val mid = (lo + hi) * 0.5f
                val probe = hslToColor(hsl.hue, hsl.saturation, mid, alphaOverride)
                if (score(probe, includeSecondary) >= targetRatio) lo = mid else hi = mid
            }
            (hsl.lightness - lo) to hslToColor(hsl.hue, hsl.saturation, lo, alphaOverride)
        } else {
            null
        }

        return when {
            lighterCandidate != null && darkerCandidate != null ->
                if (lighterCandidate.first <= darkerCandidate.first) lighterCandidate.second else darkerCandidate.second
            lighterCandidate != null -> lighterCandidate.second
            darkerCandidate != null -> darkerCandidate.second
            else -> null
        }
    }

    searchDirection(includeSecondary = true, alphaOverride = hsl.alpha)?.let { return it }
    if (hsl.alpha < 1f) {
        searchDirection(includeSecondary = true, alphaOverride = 1f)?.let { return it }
    }
    if (secondaryBackground != null) {
        searchDirection(includeSecondary = false, alphaOverride = hsl.alpha)?.let { return it }
    }

    val whiteCandidate = hslToColor(hsl.hue, hsl.saturation, 1f, 1f)
    val blackCandidate = hslToColor(hsl.hue, hsl.saturation, 0f, 1f)
    return if (score(whiteCandidate, includeSecondary = false) >= score(blackCandidate, includeSecondary = false)) {
        whiteCandidate
    } else {
        blackCandidate
    }
}
