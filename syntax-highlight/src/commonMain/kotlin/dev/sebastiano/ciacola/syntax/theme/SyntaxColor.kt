package dev.sebastiano.ciacola.syntax.theme

import kotlin.jvm.JvmInline
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Packed 32-bit ARGB color (`0xAARRGGBB`) used by syntax color schemes.
 */
@JvmInline
public value class SyntaxColor(public val argb: Int) {

    /** Alpha channel in `0..255`. */
    public val alpha: Int
        get() = (argb ushr 24) and 0xFF

    /** Red channel in `0..255`. */
    public val red: Int
        get() = (argb ushr 16) and 0xFF

    /** Green channel in `0..255`. */
    public val green: Int
        get() = (argb ushr 8) and 0xFF

    /** Blue channel in `0..255`. */
    public val blue: Int
        get() = argb and 0xFF

    /** Formats this color as uppercase `#RRGGBB` (omitting alpha). */
    public fun toHexRgb(): String = buildString(7) {
        append('#')
        appendHexByte(red)
        appendHexByte(green)
        appendHexByte(blue)
    }

    /** Formats this color as uppercase CSS `#RRGGBBAA`. */
    public fun toHexRgba(): String = buildString(9) {
        append('#')
        appendHexByte(red)
        appendHexByte(green)
        appendHexByte(blue)
        appendHexByte(alpha)
    }

    /** Formats this color as uppercase `#AARRGGBB`. */
    public fun toHexArgb(): String = buildString(9) {
        append('#')
        appendHexByte(alpha)
        appendHexByte(red)
        appendHexByte(green)
        appendHexByte(blue)
    }

    /**
     * Returns the WCAG 2.x relative luminance of the RGB channels in `0.0..1.0`.
     */
    public fun relativeLuminance(): Double =
        0.2126 * srgbToLinear(red) +
            0.7152 * srgbToLinear(green) +
            0.0722 * srgbToLinear(blue)

    /**
     * Returns the WCAG 2.x contrast ratio between `this` and [other] in `1.0..21.0`.
     */
    public fun contrastRatio(other: SyntaxColor): Double {
        val l1 = relativeLuminance()
        val l2 = other.relativeLuminance()
        val hi = max(l1, l2)
        val lo = min(l1, l2)
        return (hi + 0.05) / (lo + 0.05)
    }

    internal fun toCompactHex(): String = if (alpha == 0xFF) toHexRgb() else toHexRgba()

    public companion object {

        /** Creates a [SyntaxColor] from 8-bit `0..255` channel values. */
        public fun fromRgb(red: Int, green: Int, blue: Int, alpha: Int = 0xFF): SyntaxColor {
            require(red in 0..255) { "red must be in 0..255, was $red" }
            require(green in 0..255) { "green must be in 0..255, was $green" }
            require(blue in 0..255) { "blue must be in 0..255, was $blue" }
            require(alpha in 0..255) { "alpha must be in 0..255, was $alpha" }
            val packed = ((alpha and 0xFF) shl 24) or
                ((red and 0xFF) shl 16) or
                ((green and 0xFF) shl 8) or
                (blue and 0xFF)
            return SyntaxColor(packed)
        }

        /**
         * Parses a CSS hex color (`#RGB`, `#RGBA`, `#RRGGBB`, or `#RRGGBBAA`), or returns `null` if invalid.
         */
        public fun parseCssHexOrNull(hex: String): SyntaxColor? {
            val s = hex.trim()
            if (!s.startsWith('#')) return null
            val body = s.substring(1)
            if (!isAllHex(body)) return null
            return when (body.length) {
                3 -> {
                    val r = hexDigit(body[0]) * 17
                    val g = hexDigit(body[1]) * 17
                    val b = hexDigit(body[2]) * 17
                    fromRgb(r, g, b, 0xFF)
                }
                4 -> {
                    val r = hexDigit(body[0]) * 17
                    val g = hexDigit(body[1]) * 17
                    val b = hexDigit(body[2]) * 17
                    val a = hexDigit(body[3]) * 17
                    fromRgb(r, g, b, a)
                }
                6 -> {
                    val r = hexByte(body, 0)
                    val g = hexByte(body, 2)
                    val b = hexByte(body, 4)
                    fromRgb(r, g, b, 0xFF)
                }
                8 -> {
                    val r = hexByte(body, 0)
                    val g = hexByte(body, 2)
                    val b = hexByte(body, 4)
                    val a = hexByte(body, 6)
                    fromRgb(r, g, b, a)
                }
                else -> null
            }
        }

        /**
         * Parses an ARGB hex color (`#RGB`, `#ARGB`, `#RRGGBB`, or `#AARRGGBB`), or returns `null` if invalid.
         */
        public fun parseArgbHexOrNull(hex: String): SyntaxColor? {
            val s = hex.trim()
            if (!s.startsWith('#')) return null
            val body = s.substring(1)
            if (!isAllHex(body)) return null
            return when (body.length) {
                3 -> {
                    val r = hexDigit(body[0]) * 17
                    val g = hexDigit(body[1]) * 17
                    val b = hexDigit(body[2]) * 17
                    fromRgb(r, g, b, 0xFF)
                }
                4 -> {
                    val a = hexDigit(body[0]) * 17
                    val r = hexDigit(body[1]) * 17
                    val g = hexDigit(body[2]) * 17
                    val b = hexDigit(body[3]) * 17
                    fromRgb(r, g, b, a)
                }
                6 -> {
                    val r = hexByte(body, 0)
                    val g = hexByte(body, 2)
                    val b = hexByte(body, 4)
                    fromRgb(r, g, b, 0xFF)
                }
                8 -> {
                    val a = hexByte(body, 0)
                    val r = hexByte(body, 2)
                    val g = hexByte(body, 4)
                    val b = hexByte(body, 6)
                    fromRgb(r, g, b, a)
                }
                else -> null
            }
        }

        /**
         * Parses an IntelliJ color scheme hex string: 1..6 zero-left-padded RGB hex digits or
         * 8-digit `RRGGBBAA`, tolerating an optional leading `#`, or returns `null` if invalid.
         */
        public fun parseIntelliJHexOrNull(hex: String): SyntaxColor? {
            var body = hex.trim()
            if (body.startsWith('#')) {
                body = body.substring(1)
            }
            if (body.isEmpty() || body.length == 7 || body.length > 8 || !isAllHex(body)) {
                return null
            }
            if (body.length <= 6) {
                val padded = body.padStart(6, '0')
                val r = hexByte(padded, 0)
                val g = hexByte(padded, 2)
                val b = hexByte(padded, 4)
                return fromRgb(r, g, b, 0xFF)
            }
            val r = hexByte(body, 0)
            val g = hexByte(body, 2)
            val b = hexByte(body, 4)
            val a = hexByte(body, 6)
            return fromRgb(r, g, b, a)
        }
    }
}

private fun srgbToLinear(channel: Int): Double {
    val c = channel / 255.0
    return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
}

private fun StringBuilder.appendHexByte(value: Int) {
    val v = value and 0xFF
    append(HEX_CHARS[v ushr 4])
    append(HEX_CHARS[v and 0x0F])
}

private fun isAllHex(s: String): Boolean {
    for (ch in s) {
        if (hexDigitOrMinusOne(ch) < 0) return false
    }
    return true
}

private fun hexDigit(ch: Char): Int = hexDigitOrMinusOne(ch)

private fun hexByte(s: String, offset: Int): Int =
    (hexDigitOrMinusOne(s[offset]) shl 4) or hexDigitOrMinusOne(s[offset + 1])

private fun hexDigitOrMinusOne(ch: Char): Int = when (ch) {
    in '0'..'9' -> ch - '0'
    in 'a'..'f' -> ch - 'a' + 10
    in 'A'..'F' -> ch - 'A' + 10
    else -> -1
}

private const val HEX_CHARS = "0123456789ABCDEF"
