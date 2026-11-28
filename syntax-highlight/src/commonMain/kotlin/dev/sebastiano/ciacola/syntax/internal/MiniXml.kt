package dev.sebastiano.ciacola.syntax.internal

import dev.sebastiano.ciacola.syntax.theme.SyntaxSchemeParseException

internal data class XmlElement(
    val name: String,
    val attributes: Map<String, String>,
    val children: List<XmlElement>,
    val text: String = "",
) {
    fun firstChildOrNull(childName: String): XmlElement? {
        for (child in children) {
            if (child.name == childName) return child
        }
        return null
    }

    fun childrenNamed(childName: String): List<XmlElement> =
        children.filter { it.name == childName }
}

internal object MiniXml {
    private const val MAX_DEPTH = 128

    fun parse(input: String): XmlElement {
        val parser = Parser(input)
        return parser.parseDocument()
    }

    private class MutableElement(
        val name: String,
        val attributes: Map<String, String>,
        var children: MutableList<XmlElement>? = null,
        var textBuilder: StringBuilder? = null,
    ) {
        fun addChild(child: XmlElement) {
            val list = children ?: ArrayList<XmlElement>(4).also { children = it }
            list.add(child)
        }

        fun appendChar(ch: Char) {
            val sb = textBuilder ?: StringBuilder().also { textBuilder = it }
            sb.append(ch)
        }

        fun appendStr(s: String) {
            if (s.isEmpty()) return
            val sb = textBuilder ?: StringBuilder().also { textBuilder = it }
            sb.append(s)
        }

        fun freeze(): XmlElement = XmlElement(
            name = name,
            attributes = attributes,
            children = children ?: emptyList(),
            text = textBuilder?.toString() ?: "",
        )
    }

    private class Parser(private val input: String) {
        private var pos: Int = 0
        private val len: Int = input.length

        fun parseDocument(): XmlElement {
            if (pos < len && input[pos] == '\uFEFF') {
                pos++
            }
            val stack = ArrayList<MutableElement>()
            var root: XmlElement? = null

            while (pos < len) {
                if (input[pos] != '<') {
                    val ch = input[pos]
                    if (stack.isEmpty()) {
                        if (!isWhitespace(ch)) {
                            fail("Unexpected character data outside root XML element")
                        }
                        pos++
                    } else if (ch == '&') {
                        stack.last().appendStr(readEntity())
                    } else {
                        if (!isWhitespace(ch) || stack.last().textBuilder != null) {
                            stack.last().appendChar(ch)
                        }
                        pos++
                    }
                    continue
                }

                // '<'
                if (pos + 1 >= len) fail("Unexpected '<' at end of XML")
                val next = input[pos + 1]
                when {
                    next == '?' -> skipProcessingInstruction()
                    next == '!' -> {
                        if (input.startsWith("<!--", pos)) {
                            skipComment()
                        } else if (input.startsWith("<![CDATA[", pos)) {
                            if (stack.isEmpty()) fail("CDATA section outside root XML element")
                            skipCdata(stack.last())
                        } else if (
                            input.startsWith("<!DOCTYPE", pos, ignoreCase = true) &&
                            pos + 9 < len &&
                            isWhitespace(input[pos + 9])
                        ) {
                            if (stack.isNotEmpty()) fail("DOCTYPE inside XML element")
                            skipDoctype()
                        } else {
                            fail("Unsupported XML declaration")
                        }
                    }
                    next == '/' -> {
                        if (stack.isEmpty()) fail("Unexpected closing tag")
                        pos += 2
                        val closeName = readName()
                        skipWhitespace()
                        if (pos >= len || input[pos] != '>') {
                            fail("Expected '>' in closing tag </$closeName>")
                        }
                        pos++
                        val open = stack.removeAt(stack.lastIndex)
                        if (open.name != closeName) {
                            fail("Mismatched closing tag </$closeName> for <${open.name}>")
                        }
                        val frozen = open.freeze()
                        if (stack.isEmpty()) {
                            root = frozen
                        } else {
                            stack.last().addChild(frozen)
                        }
                    }
                    else -> {
                        if (stack.isEmpty() && root != null) {
                            fail("Multiple root XML elements are not allowed")
                        }
                        if (stack.size >= MAX_DEPTH) {
                            fail("Maximum XML nesting depth ($MAX_DEPTH) exceeded")
                        }
                        pos++ // '<'
                        val tagName = readName()
                        val attrs = readAttributes()
                        skipWhitespace()
                        if (pos >= len) fail("Unterminated opening tag <$tagName>")
                        val selfClosing = if (input[pos] == '/') {
                            pos++
                            if (pos >= len || input[pos] != '>') {
                                fail("Expected '>' after '/' in <$tagName/>")
                            }
                            pos++
                            true
                        } else if (input[pos] == '>') {
                            pos++
                            false
                        } else {
                            fail("Unexpected character '${input[pos]}' in tag <$tagName>")
                        }
                        val elem = MutableElement(tagName, attrs)
                        if (selfClosing) {
                            val frozen = elem.freeze()
                            if (stack.isEmpty()) {
                                root = frozen
                            } else {
                                stack.last().addChild(frozen)
                            }
                        } else {
                            stack.add(elem)
                        }
                    }
                }
            }

            if (stack.isNotEmpty()) {
                fail("Unclosed XML element <${stack.last().name}>")
            }
            return root ?: fail("Empty XML document")
        }

        private fun skipProcessingInstruction() {
            pos += 2 // '<?'
            val end = input.indexOf("?>", startIndex = pos)
            if (end < 0) fail("Unterminated XML processing instruction")
            pos = end + 2
        }

        private fun skipComment() {
            pos += 4 // '<!--'
            val end = input.indexOf("-->", startIndex = pos)
            if (end < 0) fail("Unterminated XML comment")
            pos = end + 3
        }

        private fun skipCdata(owner: MutableElement) {
            pos += 9 // '<![CDATA['
            val end = input.indexOf("]]>", startIndex = pos)
            if (end < 0) fail("Unterminated CDATA section")
            owner.appendStr(input.substring(pos, end))
            pos = end + 3
        }

        private fun skipDoctype() {
            pos += 9 // '<!DOCTYPE'
            var bracketDepth = 0
            var quote: Char? = null
            while (pos < len) {
                val ch = input[pos]
                if (quote != null) {
                    if (ch == quote) quote = null
                    pos++
                    continue
                }
                if (ch == '<' && input.startsWith("<!--", pos)) {
                    skipComment()
                    continue
                }
                if (ch == '"' || ch == '\'') {
                    quote = ch
                    pos++
                    continue
                }
                if (ch == '<' && input.startsWith("<!ENTITY", pos, ignoreCase = true)) {
                    fail("XML ENTITY declarations are not permitted")
                }
                if (ch == '[') {
                    bracketDepth++
                    pos++
                    continue
                }
                if (ch == ']') {
                    if (bracketDepth > 0) bracketDepth--
                    pos++
                    continue
                }
                if (ch == '>' && bracketDepth == 0) {
                    pos++
                    return
                }
                pos++
            }
            fail("Unterminated DOCTYPE declaration")
        }

        private fun readAttributes(): Map<String, String> {
            var attrs: LinkedHashMap<String, String>? = null
            while (pos < len) {
                val hadSpace = skipWhitespaceCount() > 0
                if (pos >= len) break
                val ch = input[pos]
                if (ch == '>' || ch == '/') break
                if (!hadSpace) {
                    fail("Expected whitespace before attribute")
                }
                val attrName = readName()
                skipWhitespace()
                if (pos >= len || input[pos] != '=') {
                    fail("Expected '=' after attribute '$attrName'")
                }
                pos++
                skipWhitespace()
                val value = readAttributeValue()
                val map = attrs ?: LinkedHashMap<String, String>(4).also { attrs = it }
                map[attrName] = value
            }
            return attrs ?: emptyMap()
        }

        private fun readAttributeValue(): String {
            if (pos >= len) fail("Expected attribute value")
            val quote = input[pos]
            if (quote != '"' && quote != '\'') {
                fail("Expected quoted attribute value")
            }
            pos++
            val start = pos
            var sb: StringBuilder? = null
            var chunkStart = pos
            while (pos < len) {
                val ch = input[pos]
                if (ch == quote) {
                    val result = if (sb == null) {
                        input.substring(start, pos)
                    } else {
                        sb.append(input, chunkStart, pos)
                        sb.toString()
                    }
                    pos++
                    return result
                }
                if (ch == '<') {
                    fail("Unescaped '<' in attribute value")
                }
                if (ch == '&') {
                    val builder = sb ?: StringBuilder().also { sb = it }
                    builder.append(input, chunkStart, pos)
                    builder.append(readEntity())
                    chunkStart = pos
                    continue
                }
                pos++
            }
            fail("Unterminated XML attribute value")
        }

        private fun readEntity(): String {
            pos++ // '&'
            val semi = input.indexOf(';', startIndex = pos)
            if (semi < 0 || semi - pos > 16) {
                fail("Unterminated or invalid XML entity reference")
            }
            val start = pos
            val entityLen = semi - start
            pos = semi + 1
            return when {
                entityLen == 3 && input.regionMatches(start, "amp", 0, 3) -> "&"
                entityLen == 2 && input.regionMatches(start, "lt", 0, 2) -> "<"
                entityLen == 2 && input.regionMatches(start, "gt", 0, 2) -> ">"
                entityLen == 4 && input.regionMatches(start, "quot", 0, 4) -> "\""
                entityLen == 4 && input.regionMatches(start, "apos", 0, 4) -> "'"
                else -> {
                    val entity = input.substring(start, semi)
                    when {
                        entity.startsWith("#x") || entity.startsWith("#X") -> {
                            val hexDigits = entity.substring(2)
                            if (hexDigits.isEmpty() || !hexDigits.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
                                fail("Invalid hexadecimal character reference '&$entity;'")
                            }
                            val code = hexDigits.toIntOrNull(16)
                                ?: fail("Invalid hexadecimal character reference '&$entity;'")
                            codePointToString(code)
                        }
                        entity.startsWith('#') -> {
                            val decDigits = entity.substring(1)
                            if (decDigits.isEmpty() || !decDigits.all { it in '0'..'9' }) {
                                fail("Invalid numeric character reference '&$entity;'")
                            }
                            val code = decDigits.toIntOrNull(10)
                                ?: fail("Invalid numeric character reference '&$entity;'")
                            codePointToString(code)
                        }
                        else -> fail("Unsupported XML entity '&$entity;'")
                    }
                }
            }
        }

        private fun codePointToString(cp: Int): String {
            if (cp <= 0 || cp > 0x10FFFF || cp in 0xD800..0xDFFF) {
                fail("Invalid Unicode code point in XML character reference: $cp")
            }
            return if (cp <= 0xFFFF) {
                cp.toChar().toString()
            } else {
                val high = (((cp - 0x10000) ushr 10) + 0xD800).toChar()
                val low = (((cp - 0x10000) and 0x3FF) + 0xDC00).toChar()
                charArrayOf(high, low).concatToString()
            }
        }

        private fun readName(): String {
            val start = pos
            if (pos >= len || !isNameStartChar(input[pos])) {
                fail("Expected XML identifier")
            }
            pos++
            while (pos < len && isNameChar(input[pos])) {
                pos++
            }
            return input.substring(start, pos)
        }

        private fun skipWhitespace() {
            while (pos < len && isWhitespace(input[pos])) {
                pos++
            }
        }

        private fun skipWhitespaceCount(): Int {
            val start = pos
            skipWhitespace()
            return pos - start
        }

        private fun isWhitespace(ch: Char): Boolean =
            ch == ' ' || ch == '\t' || ch == '\r' || ch == '\n'

        private fun isNameStartChar(ch: Char): Boolean =
            ch in 'a'..'z' || ch in 'A'..'Z' || ch == '_' || ch == ':' || ch.code >= 0x80

        private fun isNameChar(ch: Char): Boolean =
            isNameStartChar(ch) || ch in '0'..'9' || ch == '-' || ch == '.'

        private fun fail(message: String): Nothing {
            throw SyntaxSchemeParseException("$message at index $pos")
        }
    }
}
