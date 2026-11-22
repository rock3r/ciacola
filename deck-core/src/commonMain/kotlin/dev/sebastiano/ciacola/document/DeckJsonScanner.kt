package dev.sebastiano.ciacola.document

/**
 * Pre-parse JSON duplicate-key scanner, SVG path endpoint parser, and schema key tables extracted
 * from [DeckDocumentCodec] to keep each file focused and maintainable.
 */
internal object DeckJsonScanner {

    private val SVG_TOKEN_REGEX: Regex =
        Regex("[AaCcHhLlMmQqSsTtVvZz]|[-+]?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][-+]?\\d+)?")
    private val OFFSET_REGEX: Regex = Regex("at offset (\\d+)")
    private val VALID_COLOR_REGEX: Regex = Regex("^#(?:[0-9A-Fa-f]{6}|[0-9A-Fa-f]{8})$")

    val FORBIDDEN_SET_KEYS: Set<String> = setOf("id", "children", "builds")
    val ALLOWED_INTERACTIVE_VALUES: Set<String> = setOf("pointer", "keyboard")
    val FRAME_KEYS: Set<String> = setOf("x", "y", "w", "h")
    val VEC2_KEYS: Set<String> = setOf("x", "y")
    val ALLOWED_NODE_SET_KEYS: Set<String> = setOf(
        "frame", "align", "weight", "offset", "opacity", "rotation", "scale", "origin", "interactive",
    ) + NodeKind.ALL_PAYLOAD_KEYS

    val PAYLOAD_KEYS_BY_KIND: Map<NodeKind, Set<String>> = mapOf(
        NodeKind.Text to setOf(
            "value", "style", "font", "size", "weight", "italic", "color",
            "lineHeight", "tracking", "align", "surface", "morph",
        ),
        NodeKind.Code to setOf("value", "language", "highlightLines", "fit", "surface"),
        NodeKind.Shape to setOf("geometry", "fill", "stroke", "cornerRadius", "heads"),
        NodeKind.Image to setOf("src", "fit", "tint"),
        NodeKind.Connector to setOf("from", "to", "fromAnchor", "toAnchor", "style", "stroke", "heads", "gap"),
        NodeKind.Specimen to setOf("ref", "params", "tracks"),
        NodeKind.Stack to setOf("gap", "padding", "alignItems", "justify", "surface", "clip"),
        NodeKind.Row to setOf("gap", "padding", "alignItems", "justify", "surface", "clip"),
        NodeKind.Column to setOf("gap", "padding", "alignItems", "justify", "surface", "clip"),
        NodeKind.Loupe to setOf("target", "zoom", "diameter", "align", "lensFill"),
    )

    fun isValidHexColor(value: String): Boolean = VALID_COLOR_REGEX.matches(value)

    /**
     * Scans raw JSON text before `kotlinx.serialization` parsing to detect any duplicate member
     * keys within the same JSON object, reporting exact 1-based line and column numbers.
     */
    fun scanDuplicateJsonKeys(text: String): List<DocumentIssue> {
        val issues = mutableListOf<DocumentIssue>()
        var i = 0
        var line = 1
        var col = 1

        fun advance(): Char {
            val ch = text[i++]
            if (ch == '\n') {
                line++
                col = 1
            } else {
                col++
            }
            return ch
        }

        fun skipWs() {
            while (i < text.length) {
                when (text[i]) {
                    ' ', '\t', '\r', '\n' -> advance()
                    else -> return
                }
            }
        }

        fun readString(): String? {
            if (i >= text.length || text[i] != '"') return null
            advance() // opening "
            val sb = StringBuilder()
            while (i < text.length) {
                val ch = advance()
                when (ch) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (i >= text.length) return null
                        when (val esc = advance()) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (i + 4 > text.length) return null
                                val hex = text.substring(i, i + 4)
                                repeat(4) { advance() }
                                sb.append((hex.toIntOrNull(16) ?: return null).toChar())
                            }
                            else -> sb.append(esc)
                        }
                    }
                    else -> sb.append(ch)
                }
            }
            return null
        }

        fun scanValue(path: String) {
            skipWs()
            if (i >= text.length) return
            when (text[i]) {
                '{' -> {
                    advance()
                    skipWs()
                    val seen = hashSetOf<String>()
                    if (i < text.length && text[i] == '}') {
                        advance()
                        return
                    }
                    while (i < text.length) {
                        skipWs()
                        val keyLine = line
                        val keyCol = col
                        val key = readString() ?: return
                        val childPath = if (path.isEmpty()) key else "$path/$key"
                        if (!seen.add(key)) {
                            issues += DocumentIssue(
                                severity = IssueSeverity.LoadError,
                                code = "DUPLICATE_JSON_KEY",
                                address = childPath,
                                message = "Duplicate JSON key '$key' at line $keyLine, column $keyCol.",
                                line = keyLine,
                                column = keyCol,
                            )
                        }
                        skipWs()
                        if (i >= text.length || text[i] != ':') return
                        advance()
                        scanValue(childPath)
                        skipWs()
                        if (i >= text.length) return
                        when (text[i]) {
                            ',' -> advance()
                            '}' -> {
                                advance()
                                return
                            }
                            else -> return
                        }
                    }
                }
                '[' -> {
                    advance()
                    skipWs()
                    if (i < text.length && text[i] == ']') {
                        advance()
                        return
                    }
                    var idx = 0
                    while (i < text.length) {
                        scanValue("$path[$idx]")
                        idx++
                        skipWs()
                        if (i >= text.length) return
                        when (text[i]) {
                            ',' -> advance()
                            ']' -> {
                                advance()
                                return
                            }
                            else -> return
                        }
                    }
                }
                '"' -> readString()
                else -> {
                    while (i < text.length && text[i] !in ",}] \t\r\n") {
                        advance()
                    }
                }
            }
        }

        scanValue("")
        return issues
    }

    /**
     * Parses an SVG path data string (`M/m/L/l/H/h/V/v/C/c/S/s/Q/q/T/t/A/a/Z/z`) and returns its
     * final `(x, y)` point, or `null` if malformed/empty.
     */
    fun parseSvgPathEndpoint(pathData: String): Pair<Double, Double>? {
        val tokens = SVG_TOKEN_REGEX.findAll(pathData).map { it.value }.toList()
        if (tokens.isEmpty()) return null

        var idx = 0
        var cmd = '\u0000'
        var cx = 0.0
        var cy = 0.0
        var sx = 0.0
        var sy = 0.0
        var moved = false

        fun nextNum(): Double? = tokens.getOrNull(idx++)?.toDoubleOrNull()
        fun hasNum(): Boolean = idx < tokens.size && tokens[idx].first().let { it.isDigit() || it == '-' || it == '+' || it == '.' }

        while (idx < tokens.size) {
            val tok = tokens[idx]
            if (tok.length == 1 && tok[0].isLetter()) {
                cmd = tok[0]
                idx++
            } else if (cmd == '\u0000') {
                return null
            }

            when (cmd) {
                'M', 'm' -> {
                    val x = nextNum() ?: return null
                    val y = nextNum() ?: return null
                    if (cmd == 'm' && moved) {
                        cx += x
                        cy += y
                    } else {
                        cx = x
                        cy = y
                    }
                    sx = cx
                    sy = cy
                    moved = true
                    cmd = if (cmd == 'M') 'L' else 'l'
                }
                'L', 'l' -> {
                    val x = nextNum() ?: return null
                    val y = nextNum() ?: return null
                    if (cmd == 'l') {
                        cx += x
                        cy += y
                    } else {
                        cx = x
                        cy = y
                    }
                }
                'H', 'h' -> {
                    val x = nextNum() ?: return null
                    cx = if (cmd == 'h') cx + x else x
                }
                'V', 'v' -> {
                    val y = nextNum() ?: return null
                    cy = if (cmd == 'v') cy + y else y
                }
                'C', 'c' -> {
                    repeat(4) { nextNum() ?: return null }
                    val x = nextNum() ?: return null
                    val y = nextNum() ?: return null
                    if (cmd == 'c') {
                        cx += x
                        cy += y
                    } else {
                        cx = x
                        cy = y
                    }
                }
                'S', 's', 'Q', 'q' -> {
                    repeat(2) { nextNum() ?: return null }
                    val x = nextNum() ?: return null
                    val y = nextNum() ?: return null
                    if (cmd.isLowerCase()) {
                        cx += x
                        cy += y
                    } else {
                        cx = x
                        cy = y
                    }
                }
                'T', 't' -> {
                    val x = nextNum() ?: return null
                    val y = nextNum() ?: return null
                    if (cmd == 't') {
                        cx += x
                        cy += y
                    } else {
                        cx = x
                        cy = y
                    }
                }
                'A', 'a' -> {
                    repeat(5) { nextNum() ?: return null }
                    val x = nextNum() ?: return null
                    val y = nextNum() ?: return null
                    if (cmd == 'a') {
                        cx += x
                        cy += y
                    } else {
                        cx = x
                        cy = y
                    }
                }
                'Z', 'z' -> {
                    cx = sx
                    cy = sy
                    cmd = '\u0000'
                }
                else -> return null
            }
            if (cmd != '\u0000' && !hasNum()) {
                cmd = '\u0000'
            }
        }
        return if (moved) cx to cy else null
    }

    fun extractLineColumn(message: String?, text: String): Pair<Int?, Int?> {
        if (message == null) return null to null
        val match = OFFSET_REGEX.find(message) ?: return null to null
        val offset = match.groupValues[1].toIntOrNull()?.coerceIn(0, text.length) ?: return null to null
        var line = 1
        var col = 1
        for (idx in 0 until offset) {
            if (text[idx] == '\n') {
                line++
                col = 1
            } else {
                col++
            }
        }
        return line to col
    }
}
