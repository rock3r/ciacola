package dev.sebastiano.ciacola.syntax.theme

import dev.sebastiano.ciacola.syntax.SyntaxTokenKind
import dev.sebastiano.ciacola.syntax.internal.JsonBoolean
import dev.sebastiano.ciacola.syntax.internal.JsonNull
import dev.sebastiano.ciacola.syntax.internal.JsonNumber
import dev.sebastiano.ciacola.syntax.internal.JsonObject
import dev.sebastiano.ciacola.syntax.internal.JsonString
import dev.sebastiano.ciacola.syntax.internal.JsonValue
import dev.sebastiano.ciacola.syntax.internal.MiniJson

/**
 * Complete syntax highlighting and editor chrome color scheme.
 */
public data class SyntaxColorScheme(
    public val id: String,
    public val name: String,
    public val appearance: SyntaxSchemeAppearance,
    public val background: SyntaxColor,
    public val foreground: SyntaxColor,
    public val selectionBackground: SyntaxColor? = null,
    public val lineHighlightBackground: SyntaxColor? = null,
    public val lineNumberForeground: SyntaxColor? = null,
    public val styles: Map<SyntaxTokenKind, SyntaxTokenStyle>,
    public val functionDeclaration: SyntaxTokenStyle? = null,
    public val diffInserted: SyntaxColor? = null,
    public val diffDeleted: SyntaxColor? = null,
    public val diffChanged: SyntaxColor? = null,
    public val metadata: SyntaxSchemeMetadata = SyntaxSchemeMetadata(),
) {
    init {
        require(id.isNotBlank()) { "Scheme id must not be blank" }
        require(name.isNotBlank()) { "Scheme name must not be blank" }
    }

    /**
     * Returns the configured [SyntaxTokenStyle] for [kind], falling back to a plain foreground style.
     */
    public fun styleFor(kind: SyntaxTokenKind): SyntaxTokenStyle =
        styles[kind] ?: styles[SyntaxTokenKind.Plain] ?: SyntaxTokenStyle(foreground = foreground)

    /**
     * Returns the effective foreground color for [kind], falling back to plain or scheme [foreground].
     */
    public fun foregroundFor(kind: SyntaxTokenKind): SyntaxColor =
        styles[kind]?.foreground ?: styles[SyntaxTokenKind.Plain]?.foreground ?: foreground

    /**
     * Returns [functionDeclaration] when specified, or falls back to [styleFor] for [SyntaxTokenKind.Function].
     */
    public fun effectiveFunctionDeclarationStyle(): SyntaxTokenStyle =
        functionDeclaration ?: styleFor(SyntaxTokenKind.Function)

    /**
     * Serializes this scheme into the Ciacola Syntax Color Scheme v1 JSON format.
     */
    public fun toCompactJson(pretty: Boolean = false): String {
        val root = LinkedHashMap<String, JsonValue>()
        root["format"] = JsonString(FORMAT_ID)
        root["version"] = JsonNumber(FORMAT_VERSION.toString())
        root["id"] = JsonString(id)
        root["name"] = JsonString(name)
        root["appearance"] = JsonString(appearance.toJsonValue())
        root["background"] = JsonString(background.toCompactHex())
        root["foreground"] = JsonString(foreground.toCompactHex())
        selectionBackground?.let { root["selectionBackground"] = JsonString(it.toCompactHex()) }
        lineHighlightBackground?.let { root["lineHighlightBackground"] = JsonString(it.toCompactHex()) }
        lineNumberForeground?.let { root["lineNumberForeground"] = JsonString(it.toCompactHex()) }
        diffInserted?.let { root["diffInserted"] = JsonString(it.toCompactHex()) }
        diffDeleted?.let { root["diffDeleted"] = JsonString(it.toCompactHex()) }
        diffChanged?.let { root["diffChanged"] = JsonString(it.toCompactHex()) }

        val tokensMap = LinkedHashMap<String, JsonValue>()
        for (kind in SyntaxTokenKind.entries) {
            val style = styles[kind] ?: continue
            tokensMap[kind.toCompactKey()] = style.toJsonValue()
        }
        functionDeclaration?.let { tokensMap["functionDeclaration"] = it.toJsonValue() }
        root["tokens"] = JsonObject(tokensMap)

        val sourceMap = LinkedHashMap<String, JsonValue>()
        metadata.family?.let { sourceMap["family"] = JsonString(it) }
        metadata.upstream?.let { sourceMap["upstream"] = JsonString(it) }
        metadata.license?.let { sourceMap["license"] = JsonString(it) }
        metadata.revision?.let { sourceMap["revision"] = JsonString(it) }
        if (sourceMap.isNotEmpty()) {
            root["source"] = JsonObject(sourceMap)
        }

        return MiniJson.serialize(JsonObject(root), pretty = pretty)
    }

    public companion object {
        private const val FORMAT_ID = "ciacola-syntax-scheme"
        private const val FORMAT_VERSION = 1

        /**
         * Parses a [SyntaxColorScheme] from Ciacola Syntax Color Scheme v1 JSON.
         *
         * @throws SyntaxSchemeParseException if the input is malformed or invalid.
         */
        public fun fromCompactJson(json: String): SyntaxColorScheme {
            val root = MiniJson.parse(json, allowComments = false, allowTrailingCommas = false) as? JsonObject
                ?: throw SyntaxSchemeParseException("Expected top-level JSON object")
            return fromCompactJsonObject(root)
        }

        internal fun fromCompactJsonObject(root: JsonObject): SyntaxColorScheme {

            val format = root.requireString("format")
            if (format != FORMAT_ID) {
                throw SyntaxSchemeParseException("Unsupported scheme format '$format' (expected '$FORMAT_ID')")
            }
            val version = root.requireInt("version")
            if (version != FORMAT_VERSION) {
                throw SyntaxSchemeParseException("Unsupported scheme version $version (expected $FORMAT_VERSION)")
            }

            val id = root.requireString("id").also {
                if (it.isBlank()) throw SyntaxSchemeParseException("Field 'id' must not be blank")
            }
            val name = root.requireString("name").also {
                if (it.isBlank()) throw SyntaxSchemeParseException("Field 'name' must not be blank")
            }
            val appearance = parseAppearance(root.requireString("appearance"))
            val background = root.requireColor("background")
            val foreground = root.requireColor("foreground")

            val selectionBackground = root.optionalColor("selectionBackground")
            val lineHighlightBackground = root.optionalColor("lineHighlightBackground")
            val lineNumberForeground = root.optionalColor("lineNumberForeground")
            val diffInserted = root.optionalColor("diffInserted")
            val diffDeleted = root.optionalColor("diffDeleted")
            val diffChanged = root.optionalColor("diffChanged")

            val tokensObj = root.entries["tokens"] as? JsonObject
                ?: throw SyntaxSchemeParseException("Missing or invalid 'tokens' object")

            val styles = LinkedHashMap<SyntaxTokenKind, SyntaxTokenStyle>()
            var functionDeclaration: SyntaxTokenStyle? = null
            for ((key, rawStyle) in tokensObj.entries) {
                if (key == "functionDeclaration") {
                    functionDeclaration = parseTokenStyle(key, rawStyle)
                    continue
                }
                val kind = TOKEN_KEY_TO_KIND[key] ?: continue
                styles[kind] = parseTokenStyle(key, rawStyle)
            }

            val metadata = when (val rawSource = root.entries["source"]) {
                null, JsonNull -> SyntaxSchemeMetadata()
                is JsonObject -> SyntaxSchemeMetadata(
                    family = rawSource.optionalString("family"),
                    upstream = rawSource.optionalString("upstream"),
                    license = rawSource.optionalString("license"),
                    revision = rawSource.optionalString("revision"),
                )
                else -> throw SyntaxSchemeParseException("Field 'source' must be a JSON object")
            }

            return SyntaxColorScheme(
                id = id,
                name = name,
                appearance = appearance,
                background = background,
                foreground = foreground,
                selectionBackground = selectionBackground,
                lineHighlightBackground = lineHighlightBackground,
                lineNumberForeground = lineNumberForeground,
                styles = styles,
                functionDeclaration = functionDeclaration,
                diffInserted = diffInserted,
                diffDeleted = diffDeleted,
                diffChanged = diffChanged,
                metadata = metadata,
            )
        }
    }
}

private val TOKEN_KEY_TO_KIND: Map<String, SyntaxTokenKind> =
    SyntaxTokenKind.entries.associateBy { it.toCompactKey() }

private fun SyntaxTokenKind.toCompactKey(): String = when (this) {
    SyntaxTokenKind.Plain -> "plain"
    SyntaxTokenKind.Whitespace -> "whitespace"
    SyntaxTokenKind.Keyword -> "keyword"
    SyntaxTokenKind.String -> "string"
    SyntaxTokenKind.Comment -> "comment"
    SyntaxTokenKind.Number -> "number"
    SyntaxTokenKind.Constant -> "constant"
    SyntaxTokenKind.Function -> "function"
    SyntaxTokenKind.Type -> "type"
    SyntaxTokenKind.Property -> "property"
    SyntaxTokenKind.Annotation -> "annotation"
    SyntaxTokenKind.Attribute -> "attribute"
    SyntaxTokenKind.Tag -> "tag"
    SyntaxTokenKind.Operator -> "operator"
    SyntaxTokenKind.Preprocessor -> "preprocessor"
    SyntaxTokenKind.Punctuation -> "punctuation"
    SyntaxTokenKind.Invalid -> "invalid"
}

private fun SyntaxSchemeAppearance.toJsonValue(): String = when (this) {
    SyntaxSchemeAppearance.Light -> "light"
    SyntaxSchemeAppearance.Dark -> "dark"
    SyntaxSchemeAppearance.HighContrastLight -> "high-contrast-light"
    SyntaxSchemeAppearance.HighContrastDark -> "high-contrast-dark"
}

private fun parseAppearance(raw: String): SyntaxSchemeAppearance = when (raw) {
    "light" -> SyntaxSchemeAppearance.Light
    "dark" -> SyntaxSchemeAppearance.Dark
    "high-contrast-light" -> SyntaxSchemeAppearance.HighContrastLight
    "high-contrast-dark" -> SyntaxSchemeAppearance.HighContrastDark
    else -> throw SyntaxSchemeParseException("Unknown appearance '$raw'")
}

private fun SyntaxTokenStyle.toJsonValue(): JsonValue {
    if (foreground != null && background == null && !bold && !italic && !underline && !strikethrough) {
        return JsonString(foreground.toCompactHex())
    }
    val map = LinkedHashMap<String, JsonValue>()
    foreground?.let { map["fg"] = JsonString(it.toCompactHex()) }
    background?.let { map["bg"] = JsonString(it.toCompactHex()) }
    if (bold) map["bold"] = JsonBoolean(true)
    if (italic) map["italic"] = JsonBoolean(true)
    if (underline) map["underline"] = JsonBoolean(true)
    if (strikethrough) map["strikethrough"] = JsonBoolean(true)
    return JsonObject(map)
}

private fun parseTokenStyle(key: String, value: JsonValue): SyntaxTokenStyle = when (value) {
    is JsonString -> {
        val color = SyntaxColor.parseCssHexOrNull(value.value)
            ?: throw SyntaxSchemeParseException("Invalid hex color '${value.value}' for token '$key'")
        SyntaxTokenStyle(foreground = color)
    }
    is JsonObject -> SyntaxTokenStyle(
        foreground = value.optionalColor("fg"),
        background = value.optionalColor("bg"),
        bold = value.optionalBoolean("bold"),
        italic = value.optionalBoolean("italic"),
        underline = value.optionalBoolean("underline"),
        strikethrough = value.optionalBoolean("strikethrough"),
    )
    else -> throw SyntaxSchemeParseException("Token style for '$key' must be a hex string or style object")
}

private fun JsonObject.requireString(key: String): String {
    val v = entries[key] as? JsonString
        ?: throw SyntaxSchemeParseException("Missing or non-string field '$key'")
    return v.value
}

private fun JsonObject.optionalString(key: String): String? = when (val v = entries[key]) {
    null, JsonNull -> null
    is JsonString -> v.value
    else -> throw SyntaxSchemeParseException("Field '$key' must be a string")
}

private fun JsonObject.requireInt(key: String): Int {
    val num = entries[key] as? JsonNumber
        ?: throw SyntaxSchemeParseException("Missing or non-numeric field '$key'")
    return num.toIntOrNull()
        ?: throw SyntaxSchemeParseException("Field '$key' must be an integer, was '${num.raw}'")
}

private fun JsonObject.requireColor(key: String): SyntaxColor {
    val raw = requireString(key)
    return SyntaxColor.parseCssHexOrNull(raw)
        ?: throw SyntaxSchemeParseException("Invalid color '$raw' in field '$key'")
}

private fun JsonObject.optionalColor(key: String): SyntaxColor? = when (val v = entries[key]) {
    null, JsonNull -> null
    is JsonString -> SyntaxColor.parseCssHexOrNull(v.value)
        ?: throw SyntaxSchemeParseException("Invalid color '${v.value}' in field '$key'")
    else -> throw SyntaxSchemeParseException("Field '$key' must be a color string")
}

private fun JsonObject.optionalBoolean(key: String): Boolean = when (val v = entries[key]) {
    null, JsonNull -> false
    is JsonBoolean -> v.value
    else -> throw SyntaxSchemeParseException("Field '$key' must be a boolean")
}
