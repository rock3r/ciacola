package dev.sebastiano.ciacola.syntax.internal

import dev.sebastiano.ciacola.syntax.theme.SyntaxSchemeParseException

internal sealed interface JsonValue

internal data class JsonObject(val entries: Map<String, JsonValue>) : JsonValue

internal data class JsonArray(val items: List<JsonValue>) : JsonValue

internal data class JsonString(val value: String) : JsonValue

internal data class JsonNumber(val raw: String) : JsonValue {
    fun toIntOrNull(): Int? = raw.toIntOrNull()
}

internal data class JsonBoolean(val value: Boolean) : JsonValue

internal data object JsonNull : JsonValue

internal object MiniJson {
    private const val MAX_DEPTH = 128

    fun parse(
        input: String,
        allowComments: Boolean = false,
        allowTrailingCommas: Boolean = false,
    ): JsonValue {
        val parser = Parser(input, allowComments, allowTrailingCommas)
        parser.skipBomAndTrivia()
        val value = parser.parseValue(depth = 0)
        parser.skipTrivia()
        if (!parser.isAtEnd()) {
            parser.fail("Unexpected trailing content")
        }
        return value
    }

    fun serialize(value: JsonValue, pretty: Boolean = false): String {
        val sb = StringBuilder()
        writeValue(sb, value, pretty, indent = 0)
        return sb.toString()
    }

    private fun writeValue(sb: StringBuilder, value: JsonValue, pretty: Boolean, indent: Int) {
        when (value) {
            is JsonNull -> sb.append("null")
            is JsonBoolean -> sb.append(if (value.value) "true" else "false")
            is JsonNumber -> sb.append(value.raw)
            is JsonString -> writeEscapedString(sb, value.value)
            is JsonArray -> {
                if (value.items.isEmpty()) {
                    sb.append("[]")
                    return
                }
                sb.append('[')
                value.items.forEachIndexed { idx, item ->
                    if (idx > 0) sb.append(',')
                    if (pretty) {
                        sb.append('\n')
                        appendIndent(sb, indent + 1)
                    }
                    writeValue(sb, item, pretty, indent + 1)
                }
                if (pretty) {
                    sb.append('\n')
                    appendIndent(sb, indent)
                }
                sb.append(']')
            }
            is JsonObject -> {
                if (value.entries.isEmpty()) {
                    sb.append("{}")
                    return
                }
                sb.append('{')
                var first = true
                for ((k, v) in value.entries) {
                    if (!first) sb.append(',')
                    first = false
                    if (pretty) {
                        sb.append('\n')
                        appendIndent(sb, indent + 1)
                    }
                    writeEscapedString(sb, k)
                    sb.append(':')
                    if (pretty) sb.append(' ')
                    writeValue(sb, v, pretty, indent + 1)
                }
                if (pretty) {
                    sb.append('\n')
                    appendIndent(sb, indent)
                }
                sb.append('}')
            }
        }
    }

    private fun appendIndent(sb: StringBuilder, depth: Int) {
        repeat(depth * 2) { sb.append(' ') }
    }

    private fun writeEscapedString(sb: StringBuilder, s: String) {
        sb.append('"')
        for (ch in s) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> {
                    if (ch.code < 0x20) {
                        sb.append("\\u00")
                        sb.append(HEX[(ch.code ushr 4) and 0x0F])
                        sb.append(HEX[ch.code and 0x0F])
                    } else {
                        sb.append(ch)
                    }
                }
            }
        }
        sb.append('"')
    }

    private const val HEX = "0123456789abcdef"

    private class Parser(
        private val input: String,
        private val allowComments: Boolean,
        private val allowTrailingCommas: Boolean,
    ) {
        private var pos: Int = 0
        private val len: Int = input.length

        fun isAtEnd(): Boolean = pos >= len

        fun skipBomAndTrivia() {
            if (pos < len && input[pos] == '\uFEFF') {
                pos++
            }
            skipTrivia()
        }

        fun skipTrivia() {
            while (pos < len) {
                val ch = input[pos]
                if (ch == ' ' || ch == '\t' || ch == '\r' || ch == '\n') {
                    pos++
                    continue
                }
                if (allowComments && ch == '/' && pos + 1 < len) {
                    val next = input[pos + 1]
                    if (next == '/') {
                        pos += 2
                        while (pos < len && input[pos] != '\n' && input[pos] != '\r') {
                            pos++
                        }
                        continue
                    }
                    if (next == '*') {
                        pos += 2
                        var closed = false
                        while (pos + 1 < len) {
                            if (input[pos] == '*' && input[pos + 1] == '/') {
                                pos += 2
                                closed = true
                                break
                            }
                            pos++
                        }
                        if (!closed) {
                            fail("Unterminated block comment")
                        }
                        continue
                    }
                }
                break
            }
        }

        fun parseValue(depth: Int): JsonValue {
            if (depth > MAX_DEPTH) {
                fail("Maximum JSON nesting depth ($MAX_DEPTH) exceeded")
            }
            skipTrivia()
            if (pos >= len) {
                fail("Unexpected end of JSON input")
            }
            return when (val ch = input[pos]) {
                '{' -> parseObject(depth + 1)
                '[' -> parseArray(depth + 1)
                '"' -> JsonString(parseString())
                't' -> parseLiteral("true", JsonBoolean(true))
                'f' -> parseLiteral("false", JsonBoolean(false))
                'n' -> parseLiteral("null", JsonNull)
                '-', in '0'..'9' -> parseNumber()
                else -> fail("Unexpected character '$ch'")
            }
        }

        private fun parseObject(depth: Int): JsonObject {
            if (depth > MAX_DEPTH) {
                fail("Maximum JSON nesting depth ($MAX_DEPTH) exceeded")
            }
            pos++ // '{'
            skipTrivia()
            if (pos < len && input[pos] == '}') {
                pos++
                return JsonObject(emptyMap())
            }
            val map = LinkedHashMap<String, JsonValue>(4)
            while (true) {
                skipTrivia()
                if (pos >= len) fail("Unterminated JSON object")
                if (allowTrailingCommas && input[pos] == '}') {
                    pos++
                    return JsonObject(map)
                }
                if (input[pos] != '"') {
                    fail("Expected object key string")
                }
                val key = parseString()
                skipTrivia()
                if (pos >= len || input[pos] != ':') {
                    fail("Expected ':' after object key")
                }
                pos++
                val value = parseValue(depth)
                map[key] = value
                skipTrivia()
                if (pos >= len) fail("Unterminated JSON object")
                when (input[pos]) {
                    ',' -> pos++
                    '}' -> {
                        pos++
                        return JsonObject(map)
                    }
                    else -> fail("Expected ',' or '}' in JSON object")
                }
            }
        }

        private fun parseArray(depth: Int): JsonArray {
            if (depth > MAX_DEPTH) {
                fail("Maximum JSON nesting depth ($MAX_DEPTH) exceeded")
            }
            pos++ // '['
            skipTrivia()
            if (pos < len && input[pos] == ']') {
                pos++
                return JsonArray(emptyList())
            }
            val items = ArrayList<JsonValue>(4)
            while (true) {
                skipTrivia()
                if (pos >= len) fail("Unterminated JSON array")
                if (allowTrailingCommas && input[pos] == ']') {
                    pos++
                    return JsonArray(items)
                }
                items += parseValue(depth)
                skipTrivia()
                if (pos >= len) fail("Unterminated JSON array")
                when (input[pos]) {
                    ',' -> pos++
                    ']' -> {
                        pos++
                        return JsonArray(items)
                    }
                    else -> fail("Expected ',' or ']' in JSON array")
                }
            }
        }

        private fun parseString(): String {
            pos++ // opening '"'
            val start = pos
            var sb: StringBuilder? = null
            while (pos < len) {
                val ch = input[pos]
                if (ch == '"') {
                    val result = if (sb == null) {
                        input.substring(start, pos)
                    } else {
                        sb.append(input, start, pos)
                        sb.toString()
                    }
                    pos++
                    return result
                }
                if (ch.code < 0x20) {
                    fail("Unescaped control character in JSON string")
                }
                if (ch == '\\') {
                    if (sb == null) {
                        sb = StringBuilder()
                        sb.append(input, start, pos)
                    } else {
                        sb.append(input, start, pos)
                    }
                    pos++
                    if (pos >= len) fail("Unterminated escape sequence in JSON string")
                    val esc = input[pos++]
                    when (esc) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            if (pos + 4 > len) fail("Incomplete \\u escape in JSON string")
                            var code = 0
                            for (i in 0 until 4) {
                                val digit = hexValue(input[pos + i])
                                if (digit < 0) fail("Invalid hex digit in \\u escape")
                                code = (code shl 4) or digit
                            }
                            pos += 4
                            sb.append(code.toChar())
                        }
                        else -> fail("Invalid escape '\\$esc' in JSON string")
                    }
                    // Continue copying fast-path from new pos
                    return continueStringWithBuilder(sb)
                }
                pos++
            }
            fail("Unterminated JSON string")
        }

        private fun continueStringWithBuilder(sb: StringBuilder): String {
            var chunkStart = pos
            while (pos < len) {
                val ch = input[pos]
                if (ch == '"') {
                    sb.append(input, chunkStart, pos)
                    pos++
                    return sb.toString()
                }
                if (ch.code < 0x20) {
                    fail("Unescaped control character in JSON string")
                }
                if (ch == '\\') {
                    sb.append(input, chunkStart, pos)
                    pos++
                    if (pos >= len) fail("Unterminated escape sequence in JSON string")
                    val esc = input[pos++]
                    when (esc) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            if (pos + 4 > len) fail("Incomplete \\u escape in JSON string")
                            var code = 0
                            for (i in 0 until 4) {
                                val digit = hexValue(input[pos + i])
                                if (digit < 0) fail("Invalid hex digit in \\u escape")
                                code = (code shl 4) or digit
                            }
                            pos += 4
                            sb.append(code.toChar())
                        }
                        else -> fail("Invalid escape '\\$esc' in JSON string")
                    }
                    chunkStart = pos
                    continue
                }
                pos++
            }
            fail("Unterminated JSON string")
        }

        private fun parseLiteral(literal: String, value: JsonValue): JsonValue {
            if (pos + literal.length > len || !input.startsWith(literal, pos)) {
                fail("Unexpected token")
            }
            pos += literal.length
            return value
        }

        private fun parseNumber(): JsonNumber {
            val start = pos
            if (input[pos] == '-') pos++
            if (pos >= len) fail("Invalid number")
            if (input[pos] == '0') {
                pos++
            } else if (input[pos] in '1'..'9') {
                pos++
                while (pos < len && input[pos] in '0'..'9') pos++
            } else {
                fail("Invalid number")
            }
            if (pos < len && input[pos] == '.') {
                pos++
                if (pos >= len || input[pos] !in '0'..'9') fail("Invalid fraction in number")
                while (pos < len && input[pos] in '0'..'9') pos++
            }
            if (pos < len && (input[pos] == 'e' || input[pos] == 'E')) {
                pos++
                if (pos < len && (input[pos] == '+' || input[pos] == '-')) pos++
                if (pos >= len || input[pos] !in '0'..'9') fail("Invalid exponent in number")
                while (pos < len && input[pos] in '0'..'9') pos++
            }
            return JsonNumber(input.substring(start, pos))
        }

        fun fail(message: String): Nothing {
            throw SyntaxSchemeParseException("$message at index $pos")
        }

        private fun hexValue(ch: Char): Int = when (ch) {
            in '0'..'9' -> ch - '0'
            in 'a'..'f' -> ch - 'a' + 10
            in 'A'..'F' -> ch - 'A' + 10
            else -> -1
        }
    }
}
