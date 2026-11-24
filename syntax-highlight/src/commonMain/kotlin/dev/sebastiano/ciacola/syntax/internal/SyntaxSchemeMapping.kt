package dev.sebastiano.ciacola.syntax.internal

import dev.sebastiano.ciacola.syntax.SyntaxTokenKind
import dev.sebastiano.ciacola.syntax.theme.SyntaxColor
import dev.sebastiano.ciacola.syntax.theme.SyntaxColorScheme
import dev.sebastiano.ciacola.syntax.theme.SyntaxSchemeAppearance
import dev.sebastiano.ciacola.syntax.theme.SyntaxSchemeMetadata
import dev.sebastiano.ciacola.syntax.theme.SyntaxSchemeParseException
import dev.sebastiano.ciacola.syntax.theme.SyntaxTokenStyle

internal data class IjAttributeEntry(
    val baseAttributes: String?,
    val value: Map<String, String?>?,
)

internal data class IjSchemeData(
    val name: String,
    val parentScheme: String?,
    val colors: Map<String, String>,
    val attributes: Map<String, IjAttributeEntry>,
    val originalScheme: String? = null,
)

internal object IjEmbeddedBaseSchemes {
    val defaultScheme: IjSchemeData = IjSchemeData(
        name = "Default",
        parentScheme = null,
        colors = mapOf(
            "SELECTION_BACKGROUND" to "5974ab",
            "CARET_ROW_COLOR" to "fffae3",
            "LINE_NUMBERS_COLOR" to "999999",
            "ADDED_LINES_COLOR" to "c9dec1",
            "DELETED_LINES_COLOR" to "9f9f9f",
            "MODIFIED_LINES_COLOR" to "c3d6e8",
            "WHITESPACES" to "adadad",
        ),
        attributes = mapOf(
            "TEXT" to attr(mapOf("EFFECT_COLOR" to null)),
            "DEFAULT_IDENTIFIER" to attr(mapOf("FOREGROUND" to "0")),
            "DEFAULT_KEYWORD" to attr(mapOf("FOREGROUND" to "80", "FONT_TYPE" to "1")),
            "DEFAULT_STRING" to attr(mapOf("FOREGROUND" to "008000", "FONT_TYPE" to "1")),
            "DEFAULT_LINE_COMMENT" to attr(mapOf("FOREGROUND" to "808080", "FONT_TYPE" to "2")),
            "DEFAULT_BLOCK_COMMENT" to attr(mapOf("FOREGROUND" to "808080", "FONT_TYPE" to "2")),
            "DEFAULT_DOC_COMMENT" to attr(mapOf("FOREGROUND" to "808080", "FONT_TYPE" to "2")),
            "DEFAULT_NUMBER" to attr(mapOf("FOREGROUND" to "ff")),
            "DEFAULT_CONSTANT" to attr(mapOf("FOREGROUND" to "660e7a", "FONT_TYPE" to "3")),
            "DEFAULT_STATIC_FIELD" to attr(mapOf("FOREGROUND" to "660e7a", "FONT_TYPE" to "2")),
            "DEFAULT_STATIC_METHOD" to attr(mapOf("FONT_TYPE" to "2")),
            "TYPE_PARAMETER_NAME_ATTRIBUTES" to attr(
                mapOf("FOREGROUND" to "20999d", "BACKGROUND" to null, "EFFECT_COLOR" to null),
            ),
            "DEFAULT_INSTANCE_FIELD" to attr(mapOf("FOREGROUND" to "660e7a", "FONT_TYPE" to "1")),
            "ANNOTATION_NAME_ATTRIBUTES" to attr(
                mapOf(
                    "FOREGROUND" to "808000",
                    "BACKGROUND" to null,
                    "EFFECT_COLOR" to null,
                    "EFFECT_TYPE" to "1",
                    "FONT_TYPE" to null,
                ),
            ),
            "DEFAULT_ATTRIBUTE" to attr(mapOf("FOREGROUND" to "ff", "FONT_TYPE" to "1")),
            "DEFAULT_TAG" to attr(mapOf("BACKGROUND" to "efefef")),
            "DEFAULT_METADATA" to attr(mapOf("FOREGROUND" to "808000")),
            "DEFAULT_COMMA" to attr(emptyMap()),
            "DEFAULT_SEMICOLON" to attr(emptyMap()),
            "BAD_CHARACTER" to attr(
                mapOf("FOREGROUND" to null, "BACKGROUND" to "ffcccc", "EFFECT_COLOR" to null),
            ),
            "WRONG_REFERENCES_ATTRIBUTES" to attr(
                mapOf(
                    "FOREGROUND" to "ff0000",
                    "BACKGROUND" to null,
                    "EFFECT_COLOR" to null,
                    "EFFECT_TYPE" to null,
                    "FONT_TYPE" to null,
                ),
            ),
            "CONSOLE_ERROR_OUTPUT" to attr(mapOf("FOREGROUND" to "7f0000", "FONT_TYPE" to null)),
            "LOG_ERROR_OUTPUT" to attr(mapOf("FOREGROUND" to "cd0000", "FONT_TYPE" to null)),
            "ERRORS_ATTRIBUTES" to attr(
                mapOf(
                    "FOREGROUND" to null,
                    "BACKGROUND" to null,
                    "EFFECT_COLOR" to "ff0000",
                    "EFFECT_TYPE" to "2",
                    "FONT_TYPE" to null,
                ),
            ),
        ),
    )

    val darculaScheme: IjSchemeData = IjSchemeData(
        name = "Darcula",
        parentScheme = "Default",
        colors = mapOf(
            "SELECTION_BACKGROUND" to "214283",
            "CARET_ROW_COLOR" to "323232",
            "LINE_NUMBERS_COLOR" to "606366",
            "ADDED_LINES_COLOR" to "384C38",
            "DELETED_LINES_COLOR" to "656e76",
            "MODIFIED_LINES_COLOR" to "374752",
            "WHITESPACES" to "606060",
        ),
        attributes = mapOf(
            "TEXT" to attr(mapOf("FOREGROUND" to "a9b7c6", "BACKGROUND" to "2b2b2b", "EFFECT_TYPE" to "5")),
            "DEFAULT_IDENTIFIER" to attr(mapOf("FOREGROUND" to "a9b7c6")),
            "DEFAULT_KEYWORD" to attr(mapOf("FOREGROUND" to "cc7832")),
            "DEFAULT_STRING" to attr(mapOf("FOREGROUND" to "6a8759", "FONT_TYPE" to null)),
            "DEFAULT_LINE_COMMENT" to attr(mapOf("FOREGROUND" to "808080")),
            "DEFAULT_BLOCK_COMMENT" to attr(mapOf("FOREGROUND" to "808080")),
            "DEFAULT_DOC_COMMENT" to attr(mapOf("FOREGROUND" to "629755", "FONT_TYPE" to "2")),
            "DEFAULT_NUMBER" to attr(mapOf("FOREGROUND" to "6897bb")),
            "DEFAULT_CONSTANT" to attr(mapOf("FOREGROUND" to "9876aa", "FONT_TYPE" to "2")),
            "DEFAULT_STATIC_FIELD" to attr(mapOf("FOREGROUND" to "9876aa", "FONT_TYPE" to "2")),
            "DEFAULT_FUNCTION_DECLARATION" to attr(mapOf("FOREGROUND" to "ffc66d")),
            "DEFAULT_STATIC_METHOD" to attr(mapOf("FOREGROUND" to "ffc66d", "FONT_TYPE" to "2")),
            "DEFAULT_FUNCTION_CALL" to attr(emptyMap()),
            "TYPE_PARAMETER_NAME_ATTRIBUTES" to attr(mapOf("FOREGROUND" to "507874")),
            "DEFAULT_INSTANCE_FIELD" to attr(mapOf("FOREGROUND" to "9876aa")),
            "ANNOTATION_NAME_ATTRIBUTES" to attr(
                mapOf("FOREGROUND" to "bbb529", "FONT_TYPE" to null, "EFFECT_TYPE" to "1"),
            ),
            "HTML_ATTRIBUTE_NAME" to attr(mapOf("FOREGROUND" to "bababa")),
            "XML_ATTRIBUTE_NAME" to attr(mapOf("FOREGROUND" to "bababa")),
            "XML_TAG_NAME" to attr(mapOf("FOREGROUND" to "e8bf6a")),
            "HTML_TAG_NAME" to attr(mapOf("FOREGROUND" to "e8bf6a")),
            "DEFAULT_OPERATION_SIGN" to attr(emptyMap()),
            "DEFAULT_METADATA" to attr(mapOf("FOREGROUND" to "bbb529", "FONT_TYPE" to null)),
            "DEFAULT_BRACES" to attr(emptyMap()),
            "DEFAULT_COMMA" to attr(mapOf("FOREGROUND" to "cc7832")),
            "DEFAULT_SEMICOLON" to attr(mapOf("FOREGROUND" to "cc7832")),
            "DEFAULT_PARENTHS" to attr(emptyMap()),
            "BAD_CHARACTER" to attr(mapOf("EFFECT_COLOR" to "ff0000", "EFFECT_TYPE" to "2")),
            "WRONG_REFERENCES_ATTRIBUTES" to attr(
                mapOf(
                    "FOREGROUND" to "bc3f3c",
                    "EFFECT_COLOR" to null,
                    "EFFECT_TYPE" to null,
                    "FONT_TYPE" to null,
                ),
            ),
            "CONSOLE_ERROR_OUTPUT" to attr(mapOf("FOREGROUND" to "ff6b68", "FONT_TYPE" to null)),
            "LOG_ERROR_OUTPUT" to attr(mapOf("FOREGROUND" to "cc666e", "FONT_TYPE" to null)),
            "ERRORS_ATTRIBUTES" to attr(mapOf("EFFECT_COLOR" to "bc3f3c", "EFFECT_TYPE" to "2")),
        ),
    )

    fun toIjSchemeData(scheme: SyntaxColorScheme): IjSchemeData {
        val colors = LinkedHashMap<String, String>()
        scheme.selectionBackground?.let { colors["SELECTION_BACKGROUND"] = it.toIjHex() }
        scheme.lineHighlightBackground?.let { colors["CARET_ROW_COLOR"] = it.toIjHex() }
        scheme.lineNumberForeground?.let { colors["LINE_NUMBERS_COLOR"] = it.toIjHex() }
        scheme.diffInserted?.let { colors["ADDED_LINES_COLOR"] = it.toIjHex() }
        scheme.diffDeleted?.let { colors["DELETED_LINES_COLOR"] = it.toIjHex() }
        scheme.diffChanged?.let { colors["MODIFIED_LINES_COLOR"] = it.toIjHex() }
        scheme.styles[SyntaxTokenKind.Whitespace]?.foreground?.let { colors["WHITESPACES"] = it.toIjHex() }

        val attrs = LinkedHashMap<String, IjAttributeEntry>()
        attrs["TEXT"] = attr(
            mapOf(
                "FOREGROUND" to scheme.foreground.toIjHex(),
                "BACKGROUND" to scheme.background.toIjHex(),
            ),
        )
        scheme.styles[SyntaxTokenKind.Keyword]?.let { attrs["DEFAULT_KEYWORD"] = it.toIjAttr() }
        scheme.styles[SyntaxTokenKind.String]?.let { attrs["DEFAULT_STRING"] = it.toIjAttr() }
        scheme.styles[SyntaxTokenKind.Comment]?.let { attrs["DEFAULT_LINE_COMMENT"] = it.toIjAttr() }
        scheme.styles[SyntaxTokenKind.Number]?.let { attrs["DEFAULT_NUMBER"] = it.toIjAttr() }
        scheme.styles[SyntaxTokenKind.Constant]?.let { attrs["DEFAULT_CONSTANT"] = it.toIjAttr() }
        val fnDecl = scheme.functionDeclaration ?: scheme.styles[SyntaxTokenKind.Function]
        fnDecl?.let { attrs["DEFAULT_FUNCTION_DECLARATION"] = it.toIjAttr() }
        scheme.styles[SyntaxTokenKind.Type]?.let { attrs["TYPE_PARAMETER_NAME_ATTRIBUTES"] = it.toIjAttr() }
        scheme.styles[SyntaxTokenKind.Property]?.let { attrs["DEFAULT_INSTANCE_FIELD"] = it.toIjAttr() }
        scheme.styles[SyntaxTokenKind.Annotation]?.let { attrs["ANNOTATION_NAME_ATTRIBUTES"] = it.toIjAttr() }
        scheme.styles[SyntaxTokenKind.Attribute]?.let {
            val attrKey = if (scheme.appearance.isLight) "DEFAULT_ATTRIBUTE" else "HTML_ATTRIBUTE_NAME"
            attrs[attrKey] = it.toIjAttr()
        }
        scheme.styles[SyntaxTokenKind.Tag]?.let { attrs["XML_TAG_NAME"] = it.toIjAttr() }
        scheme.styles[SyntaxTokenKind.Operator]?.let { attrs["DEFAULT_OPERATION_SIGN"] = it.toIjAttr() }
        scheme.styles[SyntaxTokenKind.Preprocessor]?.let { attrs["PREPROCESSOR_DIRECTIVE"] = it.toIjAttr() }
        scheme.styles[SyntaxTokenKind.Punctuation]?.let { attrs["DEFAULT_BRACES"] = it.toIjAttr() }
        scheme.styles[SyntaxTokenKind.Invalid]?.let { attrs["WRONG_REFERENCES_ATTRIBUTES"] = it.toIjAttr() }

        return IjSchemeData(
            name = scheme.name,
            parentScheme = if (scheme.appearance.isLight) "Default" else "Darcula",
            colors = colors,
            attributes = attrs,
        )
    }

    private fun attr(opts: Map<String, String?>): IjAttributeEntry =
        IjAttributeEntry(baseAttributes = null, value = opts)

    private fun SyntaxColor.toIjHex(): String =
        if (alpha == 0xFF) toHexRgb().substring(1) else toHexRgba().substring(1)

    private fun SyntaxTokenStyle.toIjAttr(): IjAttributeEntry {
        val opts = LinkedHashMap<String, String?>()
        foreground?.let { opts["FOREGROUND"] = it.toIjHex() }
        background?.let { opts["BACKGROUND"] = it.toIjHex() }
        val fontType = (if (bold) 1 else 0) or (if (italic) 2 else 0)
        if (fontType != 0) opts["FONT_TYPE"] = fontType.toString()
        if (underline) {
            opts["EFFECT_COLOR"] = (foreground ?: SyntaxColor(0xFF000000.toInt())).toIjHex()
            opts["EFFECT_TYPE"] = "1"
        } else if (strikethrough) {
            opts["EFFECT_COLOR"] = (foreground ?: SyntaxColor(0xFF000000.toInt())).toIjHex()
            opts["EFFECT_TYPE"] = "3"
        }
        return IjAttributeEntry(baseAttributes = null, value = opts)
    }
}

internal object IntelliJSchemeMapper {
    fun parseXmlSchemes(xmlText: String): List<IjSchemeData> {
        val root = MiniXml.parse(xmlText)
        val elements = if (root.name == "scheme") {
            listOf(root)
        } else {
            collectSchemeElements(root)
        }
        if (elements.isEmpty()) {
            throw SyntaxSchemeParseException("No <scheme> element found in IntelliJ XML")
        }
        return elements.map { parseSchemeElement(it) }
    }

    private fun collectSchemeElements(element: XmlElement): List<XmlElement> {
        val out = ArrayList<XmlElement>()
        fun visit(el: XmlElement) {
            if (el.name == "scheme") {
                out += el
            } else {
                for (child in el.children) visit(child)
            }
        }
        visit(element)
        return out
    }

    private fun parseSchemeElement(el: XmlElement): IjSchemeData {
        val name = el.attributes["name"]?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw SyntaxSchemeParseException("IntelliJ <scheme> element is missing a non-empty 'name' attribute")
        val parentScheme = el.attributes["parent_scheme"]?.trim()?.takeIf { it.isNotEmpty() }
        val originalScheme = el.firstChildOrNull("metaInfo")
            ?.childrenNamed("property")
            ?.firstOrNull { it.attributes["name"] == "originalScheme" }
            ?.text
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

        val colors = LinkedHashMap<String, String>()
        el.firstChildOrNull("colors")?.childrenNamed("option")?.forEach { opt ->
            val cname = opt.attributes["name"]
            val cval = opt.attributes["value"]
            if (!cname.isNullOrEmpty() && !cval.isNullOrEmpty()) {
                colors[cname] = cval
            }
        }

        val attributes = LinkedHashMap<String, IjAttributeEntry>()
        el.firstChildOrNull("attributes")?.childrenNamed("option")?.forEach { opt ->
            val aname = opt.attributes["name"]
            if (!aname.isNullOrEmpty()) {
                val baseAttr = opt.attributes["baseAttributes"]?.takeIf { it.isNotEmpty() }
                val valueEl = opt.firstChildOrNull("value")
                val valueMap = valueEl?.let { vEl ->
                    val map = LinkedHashMap<String, String?>()
                    vEl.childrenNamed("option").forEach { vOpt ->
                        val vname = vOpt.attributes["name"]
                        if (!vname.isNullOrEmpty()) {
                            map[vname] = vOpt.attributes["value"]
                        }
                    }
                    map
                }
                attributes[aname] = IjAttributeEntry(baseAttributes = baseAttr, value = valueMap)
            }
        }

        return IjSchemeData(
            name = name,
            parentScheme = parentScheme,
            colors = colors,
            attributes = attributes,
            originalScheme = originalScheme,
        )
    }

    fun convert(
        primaryName: String,
        schemes: Map<String, IjSchemeData>,
        fallbackParent: SyntaxColorScheme?,
    ): SyntaxColorScheme {
        val defaultToDefault = fallbackParent == null
        val textOpts = resolveAttr(schemes, primaryName, "TEXT", defaultToDefault = defaultToDefault)
        val bg = textOpts?.get("BACKGROUND")?.let { SyntaxColor.parseIntelliJHexOrNull(it) }
            ?: fallbackParent?.background
            ?: SyntaxColor(0xFFFFFFFF.toInt())
        val fg = textOpts?.get("FOREGROUND")?.let { SyntaxColor.parseIntelliJHexOrNull(it) }
            ?: fallbackParent?.foreground
            ?: SyntaxColor(0xFF000000.toInt())

        val isLight = bg.relativeLuminance() >= 0.5
        val isHc = fallbackParent?.appearance?.isHighContrast == true ||
            hasHighContrastInChain(primaryName, schemes, defaultToDefault)
        val appearance = when {
            isHc && isLight -> SyntaxSchemeAppearance.HighContrastLight
            isHc -> SyntaxSchemeAppearance.HighContrastDark
            isLight -> SyntaxSchemeAppearance.Light
            else -> SyntaxSchemeAppearance.Dark
        }

        val plainStyle = SyntaxTokenStyle(foreground = fg)
        val whitespaceFg = resolveColor(schemes, primaryName, "WHITESPACES", defaultToDefault = defaultToDefault)
            ?: fallbackParent?.styles?.get(SyntaxTokenKind.Whitespace)?.foreground
            ?: pickStyle(
                schemes,
                primaryName,
                bg,
                listOf("DEFAULT_LINE_COMMENT", "DEFAULT_BLOCK_COMMENT"),
                defaultToDefault = defaultToDefault,
            )?.foreground
            ?: fg

        val keywordStyle = pickStyle(schemes, primaryName, bg, listOf("DEFAULT_KEYWORD"), defaultToDefault = defaultToDefault)
            ?: fallbackParent?.styles?.get(SyntaxTokenKind.Keyword)
            ?: plainStyle
        val stringStyle = pickStyle(schemes, primaryName, bg, listOf("DEFAULT_STRING"), defaultToDefault = defaultToDefault)
            ?: fallbackParent?.styles?.get(SyntaxTokenKind.String)
            ?: plainStyle
        val commentStyle = pickStyle(
            schemes,
            primaryName,
            bg,
            listOf("DEFAULT_LINE_COMMENT", "DEFAULT_BLOCK_COMMENT", "DEFAULT_DOC_COMMENT"),
            defaultToDefault = defaultToDefault,
        ) ?: fallbackParent?.styles?.get(SyntaxTokenKind.Comment)
            ?: plainStyle
        val numberStyle = pickStyle(schemes, primaryName, bg, listOf("DEFAULT_NUMBER"), defaultToDefault = defaultToDefault)
            ?: fallbackParent?.styles?.get(SyntaxTokenKind.Number)
            ?: plainStyle
        val constantStyle = pickStyle(
            schemes,
            primaryName,
            bg,
            listOf("DEFAULT_CONSTANT", "DEFAULT_STATIC_FIELD"),
            defaultToDefault = defaultToDefault,
        ) ?: fallbackParent?.styles?.get(SyntaxTokenKind.Constant)
            ?: plainStyle
        val fnDeclStyle = pickStyle(schemes, primaryName, bg, listOf("DEFAULT_FUNCTION_DECLARATION"), defaultToDefault = defaultToDefault)
            ?: fallbackParent?.functionDeclaration
        val functionStyle = pickStyle(
            schemes,
            primaryName,
            bg,
            listOf(
                "DEFAULT_FUNCTION_DECLARATION",
                "DEFAULT_INSTANCE_METHOD",
                "DEFAULT_STATIC_METHOD",
                "DEFAULT_FUNCTION_CALL",
            ),
            defaultToDefault = defaultToDefault,
        ) ?: fallbackParent?.styles?.get(SyntaxTokenKind.Function)
            ?: plainStyle
        val typeStyle = pickStyle(
            schemes,
            primaryName,
            bg,
            listOf("DEFAULT_CLASS_NAME", "CLASS_NAME_ATTRIBUTES", "DEFAULT_INTERFACE_NAME"),
            diffFrom = fg,
            defaultToDefault = defaultToDefault,
        ) ?: pickStyle(
            schemes,
            primaryName,
            bg,
            listOf("TYPE_PARAMETER_NAME_ATTRIBUTES", "DEFAULT_CLASS_NAME"),
            defaultToDefault = defaultToDefault,
        ) ?: fallbackParent?.styles?.get(SyntaxTokenKind.Type)
            ?: pickStyle(schemes, primaryName, bg, listOf("TEXT"), defaultToDefault = defaultToDefault)
            ?: plainStyle
        val propertyStyle = pickStyle(
            schemes,
            primaryName,
            bg,
            listOf("DEFAULT_INSTANCE_FIELD", "DEFAULT_STATIC_FIELD", "KOTLIN_NAMED_ARGUMENT"),
            defaultToDefault = defaultToDefault,
        ) ?: fallbackParent?.styles?.get(SyntaxTokenKind.Property)
            ?: plainStyle
        val annotationStyle = pickStyle(
            schemes,
            primaryName,
            bg,
            listOf("ANNOTATION_NAME_ATTRIBUTES", "DEFAULT_METADATA"),
            defaultToDefault = defaultToDefault,
        ) ?: fallbackParent?.styles?.get(SyntaxTokenKind.Annotation)
            ?: plainStyle
        val attributeStyle = pickStyle(
            schemes,
            primaryName,
            bg,
            listOf("HTML_ATTRIBUTE_NAME", "XML_ATTRIBUTE_NAME", "DEFAULT_ATTRIBUTE"),
            minContrast = 2.5,
            defaultToDefault = defaultToDefault,
        ) ?: fallbackParent?.styles?.get(SyntaxTokenKind.Attribute)
            ?: plainStyle
        val tagStyle = pickStyle(
            schemes,
            primaryName,
            bg,
            listOf("XML_TAG_NAME", "HTML_TAG_NAME", "DEFAULT_TAG"),
            fgOnly = true,
            defaultToDefault = defaultToDefault,
        ) ?: fallbackParent?.styles?.get(SyntaxTokenKind.Tag)
            ?: pickStyle(schemes, primaryName, bg, listOf("DEFAULT_KEYWORD"), fgOnly = true, defaultToDefault = defaultToDefault)
            ?: keywordStyle
        val operatorStyle = pickStyle(
            schemes,
            primaryName,
            bg,
            listOf("DEFAULT_OPERATION_SIGN"),
            defaultToDefault = defaultToDefault,
        ) ?: fallbackParent?.styles?.get(SyntaxTokenKind.Operator)
            ?: pickStyle(schemes, primaryName, bg, listOf("TEXT"), defaultToDefault = defaultToDefault)
            ?: plainStyle
        val preprocessorStyle = pickStyle(
            schemes,
            primaryName,
            bg,
            listOf("OC.DIRECTIVE", "PREPROCESSOR_DIRECTIVE", "DEFAULT_METADATA"),
            defaultToDefault = defaultToDefault,
        ) ?: fallbackParent?.styles?.get(SyntaxTokenKind.Preprocessor)
            ?: pickStyle(schemes, primaryName, bg, listOf("DEFAULT_KEYWORD"), defaultToDefault = defaultToDefault)
            ?: keywordStyle
        val punctuationStyle = pickStyle(
            schemes,
            primaryName,
            bg,
            listOf("DEFAULT_BRACES", "DEFAULT_PARENTHS", "DEFAULT_OPERATION_SIGN"),
            defaultToDefault = defaultToDefault,
        ) ?: fallbackParent?.styles?.get(SyntaxTokenKind.Punctuation)
            ?: pickStyle(schemes, primaryName, bg, listOf("TEXT"), defaultToDefault = defaultToDefault)
            ?: plainStyle
        val invalidStyle = pickStyle(
            schemes,
            primaryName,
            bg,
            listOf(
                "BAD_CHARACTER",
                "WRONG_REFERENCES_ATTRIBUTES",
                "CONSOLE_ERROR_OUTPUT",
                "LOG_ERROR_OUTPUT",
            ),
            defaultToDefault = defaultToDefault,
        ) ?: pickStyle(
            schemes,
            primaryName,
            bg,
            listOf("ERRORS_ATTRIBUTES"),
            requireFg = false,
            defaultToDefault = defaultToDefault,
        ) ?: fallbackParent?.styles?.get(SyntaxTokenKind.Invalid)
            ?: plainStyle

        val styles = linkedMapOf(
            SyntaxTokenKind.Plain to plainStyle,
            SyntaxTokenKind.Whitespace to SyntaxTokenStyle(foreground = whitespaceFg),
            SyntaxTokenKind.Keyword to keywordStyle,
            SyntaxTokenKind.String to stringStyle,
            SyntaxTokenKind.Comment to commentStyle,
            SyntaxTokenKind.Number to numberStyle,
            SyntaxTokenKind.Constant to constantStyle,
            SyntaxTokenKind.Function to functionStyle,
            SyntaxTokenKind.Type to typeStyle,
            SyntaxTokenKind.Property to propertyStyle,
            SyntaxTokenKind.Annotation to annotationStyle,
            SyntaxTokenKind.Attribute to attributeStyle,
            SyntaxTokenKind.Tag to tagStyle,
            SyntaxTokenKind.Operator to operatorStyle,
            SyntaxTokenKind.Preprocessor to preprocessorStyle,
            SyntaxTokenKind.Punctuation to punctuationStyle,
            SyntaxTokenKind.Invalid to invalidStyle,
        )

        return SyntaxColorScheme(
            id = slugify(primaryName),
            name = primaryName,
            appearance = appearance,
            background = bg,
            foreground = fg,
            selectionBackground = resolveColor(schemes, primaryName, "SELECTION_BACKGROUND", defaultToDefault = defaultToDefault)
                ?: fallbackParent?.selectionBackground,
            lineHighlightBackground = resolveColor(schemes, primaryName, "CARET_ROW_COLOR", defaultToDefault = defaultToDefault)
                ?: fallbackParent?.lineHighlightBackground,
            lineNumberForeground = resolveColor(schemes, primaryName, "LINE_NUMBERS_COLOR", defaultToDefault = defaultToDefault)
                ?: fallbackParent?.lineNumberForeground,
            styles = styles,
            functionDeclaration = fnDeclStyle,
            diffInserted = resolveColor(schemes, primaryName, "ADDED_LINES_COLOR", defaultToDefault = defaultToDefault)
                ?: fallbackParent?.diffInserted,
            diffDeleted = resolveColor(schemes, primaryName, "DELETED_LINES_COLOR", defaultToDefault = defaultToDefault)
                ?: fallbackParent?.diffDeleted,
            diffChanged = resolveColor(schemes, primaryName, "MODIFIED_LINES_COLOR", defaultToDefault = defaultToDefault)
                ?: fallbackParent?.diffChanged,
            metadata = SyntaxSchemeMetadata(family = "IntelliJ"),
        )
    }

    private fun resolveAttr(
        schemes: Map<String, IjSchemeData>,
        schemeName: String,
        attrName: String,
        visited: MutableSet<Pair<String, String>> = HashSet(),
        defaultToDefault: Boolean = true,
    ): Map<String, String?>? {
        val key = schemeName to attrName
        if (!visited.add(key)) return null

        var curName: String? = lookupSchemeKey(schemeName, schemes) ?: schemeName
        val chainSeen = HashSet<String>()
        while (curName != null && chainSeen.add(curName)) {
            val scheme = schemes[curName] ?: break
            val entry = scheme.attributes[attrName]
            if (entry != null) {
                if (entry.value != null) {
                    return entry.value
                }
                if (entry.baseAttributes != null) {
                    val resolved = resolveAttr(schemes, schemeName, entry.baseAttributes, visited, defaultToDefault)
                    if (resolved != null) return resolved
                }
            }
            curName = effectiveParentKey(scheme, schemes, defaultToDefault)
        }
        return null
    }

    private fun resolveColor(
        schemes: Map<String, IjSchemeData>,
        schemeName: String,
        colorName: String,
        defaultToDefault: Boolean = true,
    ): SyntaxColor? {
        var curName: String? = lookupSchemeKey(schemeName, schemes) ?: schemeName
        val seen = HashSet<String>()
        while (curName != null && seen.add(curName)) {
            val scheme = schemes[curName] ?: break
            val raw = scheme.colors[colorName]
            if (!raw.isNullOrEmpty()) {
                val parsed = SyntaxColor.parseIntelliJHexOrNull(raw)
                if (parsed != null) return parsed
            }
            curName = effectiveParentKey(scheme, schemes, defaultToDefault)
        }
        return null
    }

    private fun hasHighContrastInChain(
        schemeName: String,
        schemes: Map<String, IjSchemeData>,
        defaultToDefault: Boolean,
    ): Boolean {
        var curName: String? = lookupSchemeKey(schemeName, schemes) ?: schemeName
        val seen = HashSet<String>()
        while (curName != null && seen.add(curName)) {
            if (curName.contains("high contrast", ignoreCase = true)) return true
            val scheme = schemes[curName] ?: break
            if (scheme.originalScheme?.contains("high contrast", ignoreCase = true) == true) return true
            if (scheme.parentScheme?.contains("high contrast", ignoreCase = true) == true) return true
            curName = effectiveParentKey(scheme, schemes, defaultToDefault)
        }
        return false
    }

    fun resolveEffectiveParentKey(scheme: IjSchemeData, schemes: Map<String, IjSchemeData>): String? =
        effectiveParentKey(scheme, schemes, defaultToDefault = true)

    private fun effectiveParentKey(
        scheme: IjSchemeData,
        schemes: Map<String, IjSchemeData>,
        defaultToDefault: Boolean,
    ): String? {
        val orig = scheme.originalScheme
        if (orig != null &&
            (scheme.name.startsWith("_@user_") || scheme.parentScheme == null ||
                scheme.parentScheme == "Default" || scheme.parentScheme == "Darcula")
        ) {
            val origKey = lookupSchemeKey(orig, schemes)
            if (origKey != null && origKey != scheme.name) {
                return origKey
            }
        }
        val parent = scheme.parentScheme
        if (parent != null) {
            return lookupSchemeKey(parent, schemes)
        }
        if (defaultToDefault && !scheme.name.equals("Default", ignoreCase = true)) {
            return lookupSchemeKey("Default", schemes)
        }
        return null
    }

    private fun lookupSchemeKey(rawName: String, schemes: Map<String, IjSchemeData>): String? {
        if (rawName in schemes) return rawName
        val stripped = rawName.removePrefix("_@user_")
        if (stripped != rawName && stripped in schemes) return stripped
        for (key in schemes.keys) {
            if (key.equals(rawName, ignoreCase = true) || key.equals(stripped, ignoreCase = true)) {
                return key
            }
        }
        return null
    }

    private fun pickStyle(
        schemes: Map<String, IjSchemeData>,
        schemeName: String,
        canvasBg: SyntaxColor,
        candidates: List<String>,
        minContrast: Double? = null,
        diffFrom: SyntaxColor? = null,
        requireFg: Boolean = true,
        fgOnly: Boolean = false,
        defaultToDefault: Boolean = true,
    ): SyntaxTokenStyle? {
        for (attrName in candidates) {
            val opts = resolveAttr(schemes, schemeName, attrName, defaultToDefault = defaultToDefault) ?: continue
            val fg = opts["FOREGROUND"]?.let { SyntaxColor.parseIntelliJHexOrNull(it) }
            if (requireFg && fg == null) continue
            if (diffFrom != null && fg == diffFrom) continue
            if (minContrast != null && fg != null) {
                val effectiveFg = if (fg.alpha < 0xFF) fg.compositeOver(canvasBg) else fg
                if (effectiveFg.contrastRatio(canvasBg) < minContrast) continue
            }

            val rawBg = if (fgOnly || attrName == "TEXT") {
                null
            } else {
                opts["BACKGROUND"]?.let { SyntaxColor.parseIntelliJHexOrNull(it) }
            }
            val bg = if (rawBg == canvasBg) null else rawBg
            val fontType = parseUnsignedIntOrZero(opts["FONT_TYPE"])
            val effectType = parseUnsignedIntOrZero(opts["EFFECT_TYPE"])
            val effectColor = opts["EFFECT_COLOR"]?.let { SyntaxColor.parseIntelliJHexOrNull(it) }
            val hasEffect = effectColor != null
            return SyntaxTokenStyle(
                foreground = fg,
                background = bg,
                bold = (fontType and 1) != 0,
                italic = (fontType and 2) != 0,
                underline = hasEffect && effectType in intArrayOf(1, 2, 4, 5),
                strikethrough = hasEffect && effectType == 3,
            )
        }
        return null
    }

    private fun parseUnsignedIntOrZero(raw: String?): Int {
        if (raw.isNullOrEmpty() || !raw.all { it in '0'..'9' }) return 0
        return raw.toIntOrNull() ?: 0
    }
}

internal object VsCodeThemeMapper {
    private data class ScopeSpec(
        val tmCandidates: List<String>,
        val semanticCandidates: List<String>,
        val tmFallbackCandidates: List<String> = emptyList(),
    )

    private val SCOPE_MAP: Map<SyntaxTokenKind, ScopeSpec> = mapOf(
        SyntaxTokenKind.Keyword to ScopeSpec(
            listOf("keyword.control", "keyword", "storage.type", "storage"),
            listOf("keyword"),
        ),
        SyntaxTokenKind.String to ScopeSpec(
            listOf("string.quoted", "string"),
            listOf("string"),
        ),
        SyntaxTokenKind.Comment to ScopeSpec(
            listOf("comment.line", "comment.block", "comment", "punctuation.definition.comment"),
            listOf("comment"),
        ),
        SyntaxTokenKind.Number to ScopeSpec(
            listOf("constant.numeric", "constant"),
            listOf("number"),
        ),
        SyntaxTokenKind.Constant to ScopeSpec(
            listOf("constant.language", "variable.other.constant", "constant"),
            listOf("variable.readonly", "enumMember"),
        ),
        SyntaxTokenKind.Function to ScopeSpec(
            listOf("entity.name.function", "support.function"),
            listOf("function", "method"),
        ),
        SyntaxTokenKind.Type to ScopeSpec(
            listOf(
                "entity.name.type",
                "entity.name.class",
                "entity.name.struct",
                "entity.name.interface",
                "support.type",
                "support.class",
                "entity.name",
            ),
            listOf("type", "class", "interface", "struct", "enum", "typeParameter"),
        ),
        SyntaxTokenKind.Property to ScopeSpec(
            listOf(
                "meta.property-name",
                "support.type.property-name",
                "variable.other.property",
                "variable.other.member",
                "entity.name.variable.field",
            ),
            listOf("property"),
        ),
        SyntaxTokenKind.Annotation to ScopeSpec(
            listOf(
                "meta.decorator",
                "entity.name.decorator",
                "punctuation.decorator",
                "entity.name.function.decorator",
                "meta.annotation",
            ),
            listOf("decorator"),
            listOf("storage.type.annotation", "keyword"),
        ),
        SyntaxTokenKind.Attribute to ScopeSpec(
            listOf("entity.other.attribute-name", "meta.tag.attributes"),
            emptyList(),
        ),
        SyntaxTokenKind.Tag to ScopeSpec(
            listOf("entity.name.tag", "support.class.component"),
            emptyList(),
        ),
        SyntaxTokenKind.Operator to ScopeSpec(
            listOf("keyword.operator", "punctuation.accessor"),
            listOf("operator"),
        ),
        SyntaxTokenKind.Preprocessor to ScopeSpec(
            listOf(
                "meta.preprocessor",
                "keyword.control.directive",
                "keyword.other.directive",
                "entity.name.function.preprocessor",
            ),
            listOf("macro"),
            listOf("keyword"),
        ),
        SyntaxTokenKind.Punctuation to ScopeSpec(
            listOf(
                "punctuation.separator",
                "punctuation.terminator",
                "punctuation.section",
                "punctuation",
                "meta.brace",
                "brackethighlighter.round",
                "brackethighlighter.curly",
            ),
            emptyList(),
        ),
        SyntaxTokenKind.Invalid to ScopeSpec(
            listOf(
                "invalid.illegal",
                "invalid.broken",
                "invalid.deprecated",
                "invalid.unimplemented",
                "invalid",
                "message.error",
            ),
            emptyList(),
        ),
    )

    private data class MergedVsCodeTheme(
        val name: String?,
        val type: String?,
        val colors: LinkedHashMap<String, SyntaxColor>,
        val tokenColors: MutableList<JsonObject>,
        val semanticTokenColors: LinkedHashMap<String, JsonValue>,
    )

    fun import(
        json: String,
        fallbackName: String?,
        includeResolver: ((String) -> String?)?,
    ): SyntaxColorScheme {
        val root = MiniJson.parse(json, allowComments = true, allowTrailingCommas = true) as? JsonObject
            ?: throw SyntaxSchemeParseException("Expected VS Code theme top-level JSON object")
        return importParsed(root, fallbackName, includeResolver)
    }

    fun importParsed(
        root: JsonObject,
        fallbackName: String?,
        includeResolver: ((String) -> String?)?,
    ): SyntaxColorScheme {
        val merged = MergedVsCodeTheme(
            name = null,
            type = null,
            colors = LinkedHashMap(),
            tokenColors = ArrayList(),
            semanticTokenColors = LinkedHashMap(),
        )
        val result = mergeThemeObject(
            root = root,
            acc = merged,
            includeResolver = includeResolver,
            currentBaseDir = "",
            visitedIncludes = HashSet(),
            depth = 0,
        )

        val displayName = result.name?.takeIf { it.isNotBlank() }
            ?: fallbackName?.takeIf { it.isNotBlank() }
            ?: "Imported Theme"
        val bg = result.colors["editor.background"] ?: SyntaxColor(0xFF1E1E1E.toInt())
        val fg = result.colors["editor.foreground"] ?: SyntaxColor(0xFFD4D4D4.toInt())

        val rawType = result.type?.lowercase().orEmpty()
        val lowerName = displayName.lowercase()
        val isHc = rawType in setOf("hclight", "hcdark", "hc", "hc-black", "hc-light") ||
            lowerName.contains("high contrast") ||
            lowerName.contains("high-contrast")
        val isLight = when (rawType) {
            "light", "hclight", "hc-light" -> true
            "dark", "hcdark", "hc", "hc-black" -> false
            else -> bg.relativeLuminance() >= 0.5
        }
        val appearance = when {
            isHc && isLight -> SyntaxSchemeAppearance.HighContrastLight
            isHc -> SyntaxSchemeAppearance.HighContrastDark
            isLight -> SyntaxSchemeAppearance.Light
            else -> SyntaxSchemeAppearance.Dark
        }

        val plainStyle = SyntaxTokenStyle(foreground = fg)
        val wsFg = result.colors["editorWhitespace.foreground"]
            ?: matchTextMate(listOf("comment", "punctuation.definition.comment"), result.tokenColors)?.foreground
            ?: fg

        val styles = LinkedHashMap<SyntaxTokenKind, SyntaxTokenStyle>()
        styles[SyntaxTokenKind.Plain] = plainStyle
        styles[SyntaxTokenKind.Whitespace] = SyntaxTokenStyle(foreground = wsFg)
        for (kind in SyntaxTokenKind.entries) {
            if (kind == SyntaxTokenKind.Plain || kind == SyntaxTokenKind.Whitespace) continue
            val spec = SCOPE_MAP[kind] ?: continue
            val style = matchTextMate(spec.tmCandidates, result.tokenColors)
                ?: matchSemantic(spec.semanticCandidates, result.semanticTokenColors)
                ?: matchTextMate(spec.tmFallbackCandidates, result.tokenColors)
                ?: plainStyle
            styles[kind] = style
        }

        val tmFnDecl = matchTextMate(
            listOf("entity.name.function.declaration"),
            result.tokenColors,
            exactOnly = true,
        )
        val semFnDecl = matchSemantic(listOf("function.declaration"), result.semanticTokenColors)
        val fnBaseStyle = styles[SyntaxTokenKind.Function]
        val fnDeclStyle = (tmFnDecl ?: semFnDecl)?.let { matched ->
            if (matched.foreground == null) {
                val inheritedFg = semFnDecl?.foreground ?: fnBaseStyle?.foreground
                if (inheritedFg != null) matched.copy(foreground = inheritedFg) else matched
            } else {
                matched
            }
        }

        return SyntaxColorScheme(
            id = slugify(displayName),
            name = displayName,
            appearance = appearance,
            background = bg,
            foreground = fg,
            selectionBackground = result.colors["editor.selectionBackground"],
            lineHighlightBackground = result.colors["editor.lineHighlightBackground"],
            lineNumberForeground = result.colors["editorLineNumber.foreground"],
            styles = styles,
            functionDeclaration = fnDeclStyle,
            diffInserted = result.colors["editorGutter.addedBackground"]
                ?: result.colors["diffEditor.insertedTextBackground"],
            diffDeleted = result.colors["editorGutter.deletedBackground"]
                ?: result.colors["diffEditor.removedTextBackground"],
            diffChanged = result.colors["editorGutter.modifiedBackground"],
        )
    }

    private fun mergeTheme(
        json: String,
        acc: MergedVsCodeTheme,
        includeResolver: ((String) -> String?)?,
        currentBaseDir: String,
        visitedIncludes: MutableSet<String>,
        depth: Int,
    ): MergedVsCodeTheme {
        if (depth > 32) {
            throw SyntaxSchemeParseException("Maximum VS Code theme include depth exceeded")
        }
        val root = MiniJson.parse(json, allowComments = true, allowTrailingCommas = true) as? JsonObject
            ?: throw SyntaxSchemeParseException("Expected VS Code theme top-level JSON object")
        return mergeThemeObject(root, acc, includeResolver, currentBaseDir, visitedIncludes, depth)
    }

    private fun mergeThemeObject(
        root: JsonObject,
        acc: MergedVsCodeTheme,
        includeResolver: ((String) -> String?)?,
        currentBaseDir: String,
        visitedIncludes: MutableSet<String>,
        depth: Int,
    ): MergedVsCodeTheme {
        if (depth > 32) {
            throw SyntaxSchemeParseException("Maximum VS Code theme include depth exceeded")
        }

        var current = acc
        val rawInclude = (root.entries["include"] as? JsonString)?.value?.trim()?.takeIf { it.isNotEmpty() }
        if (rawInclude != null && includeResolver != null) {
            val resolvedPath = resolveRelativeIncludePath(currentBaseDir, rawInclude)
            if (resolvedPath.isEmpty()) {
                throw SyntaxSchemeParseException("Could not resolve included VS Code theme '$rawInclude'")
            }
            if (visitedIncludes.add(resolvedPath)) {
                val includedJson = (
                    includeResolver(resolvedPath)
                        ?: if (currentBaseDir.isEmpty() && resolvedPath != rawInclude) includeResolver(rawInclude) else null
                    ) ?: throw SyntaxSchemeParseException("Could not resolve included VS Code theme '$resolvedPath'")
                val nextBaseDir = parentIncludeDir(resolvedPath)
                current = mergeTheme(includedJson, current, includeResolver, nextBaseDir, visitedIncludes, depth + 1)
            }
        }

        val themeName = (root.entries["name"] as? JsonString)?.value?.takeIf { it.isNotBlank() } ?: current.name
        val themeType = (root.entries["type"] as? JsonString)?.value?.takeIf { it.isNotBlank() } ?: current.type

        (root.entries["colors"] as? JsonObject)?.entries?.forEach { (k, v) ->
            if (v is JsonString) {
                SyntaxColor.parseCssHexOrNull(v.value)?.let { current.colors[k] = it }
            }
        }
        (root.entries["tokenColors"] as? JsonArray)?.items?.forEach { item ->
            if (item is JsonObject) {
                current.tokenColors.add(item)
            }
        }
        (root.entries["semanticTokenColors"] as? JsonObject)?.entries?.forEach { (k, v) ->
            current.semanticTokenColors[k] = v
        }

        return current.copy(name = themeName, type = themeType)
    }

    private fun isAbsoluteIncludePath(raw: String): Boolean {
        if (raw.startsWith('/') || raw.startsWith('\\')) return true
        if (raw.length >= 2 && raw[0].isLetter() && raw[1] == ':') return true
        return false
    }

    private fun resolveRelativeIncludePath(currentBaseDir: String, rawInclude: String): String {
        val trimmed = rawInclude.trim()
        if (trimmed.isEmpty()) return ""
        if (isAbsoluteIncludePath(trimmed)) return trimmed
        val normalizedRaw = trimmed.replace('\\', '/')
        val combined = if (currentBaseDir.isEmpty()) normalizedRaw else "$currentBaseDir/$normalizedRaw"
        val segments = ArrayList<String>()
        for (part in combined.split('/')) {
            when (part) {
                "", "." -> Unit
                ".." -> {
                    if (segments.isNotEmpty() && segments.last() != "..") {
                        segments.removeAt(segments.lastIndex)
                    } else {
                        segments.add("..")
                    }
                }
                else -> segments.add(part)
            }
        }
        return segments.joinToString("/")
    }

    private fun parentIncludeDir(normalizedPath: String): String {
        if (normalizedPath.isEmpty() || isAbsoluteIncludePath(normalizedPath)) return ""
        val slash = normalizedPath.lastIndexOf('/')
        return if (slash <= 0) "" else normalizedPath.substring(0, slash)
    }

    private data class TextMateMatch(
        val depth: Int,
        val index: Int,
        val fg: SyntaxColor?,
        val bg: SyntaxColor?,
        val hasFontStyle: Boolean,
        val fontStyle: String,
    )

    private fun matchTextMate(
        candidates: List<String>,
        tokenColors: List<JsonObject>,
        exactOnly: Boolean = false,
    ): SyntaxTokenStyle? {
        for (target in candidates) {
            val matches = ArrayList<TextMateMatch>()
            tokenColors.forEachIndexed { idx, rule ->
                val rawScope = rule.entries["scope"] ?: return@forEachIndexed
                val scopes = when (rawScope) {
                    is JsonString -> rawScope.value.split(',').map { it.trim() }
                    is JsonArray -> rawScope.items.mapNotNull { (it as? JsonString)?.value?.trim() }
                    else -> return@forEachIndexed
                }
                val settings = rule.entries["settings"] as? JsonObject ?: return@forEachIndexed
                val fg = (settings.entries["foreground"] as? JsonString)?.value?.let {
                    SyntaxColor.parseCssHexOrNull(it)
                }
                val bg = (settings.entries["background"] as? JsonString)?.value?.let {
                    SyntaxColor.parseCssHexOrNull(it)
                }
                val fontStyleValue = settings.entries["fontStyle"] as? JsonString
                val hasFontStyle = fontStyleValue != null
                if (fg == null && bg == null && !hasFontStyle) return@forEachIndexed

                var maxMatchedDepth = -1
                for (scope in scopes) {
                    if (scope.isEmpty() || scope.contains(' ')) continue
                    val matched = if (exactOnly) target == scope else (target == scope || target.startsWith("$scope."))
                    if (matched) {
                        val depth = scope.count { it == '.' } + 1
                        if (depth > maxMatchedDepth) {
                            maxMatchedDepth = depth
                        }
                    }
                }
                if (maxMatchedDepth >= 0) {
                    matches += TextMateMatch(
                        depth = maxMatchedDepth,
                        index = idx,
                        fg = fg,
                        bg = bg,
                        hasFontStyle = hasFontStyle,
                        fontStyle = fontStyleValue?.value ?: "",
                    )
                }
            }
            if (matches.isEmpty()) continue
            matches.sortWith(compareBy<TextMateMatch>({ it.depth }, { it.index }))

            var fg: SyntaxColor? = null
            var bg: SyntaxColor? = null
            var bold = false
            var italic = false
            var underline = false
            var strikethrough = false
            for (m in matches) {
                if (m.fg != null) fg = m.fg
                if (m.bg != null) bg = m.bg
                if (m.hasFontStyle) {
                    val fontTokens = splitWhitespaceTokens(m.fontStyle)
                    bold = "bold" in fontTokens
                    italic = "italic" in fontTokens
                    underline = "underline" in fontTokens
                    strikethrough = "strikethrough" in fontTokens
                }
            }
            return SyntaxTokenStyle(
                foreground = fg,
                background = bg,
                bold = bold,
                italic = italic,
                underline = underline,
                strikethrough = strikethrough,
            )
        }
        return null
    }

    private fun matchSemantic(
        candidates: List<String>,
        semanticColors: Map<String, JsonValue>,
    ): SyntaxTokenStyle? {
        for (key in candidates) {
            val raw = semanticColors[key] ?: continue
            when (raw) {
                is JsonString -> {
                    val fg = SyntaxColor.parseCssHexOrNull(raw.value) ?: continue
                    return SyntaxTokenStyle(foreground = fg)
                }
                is JsonObject -> {
                    val fg = (raw.entries["foreground"] as? JsonString)?.value?.let {
                        SyntaxColor.parseCssHexOrNull(it)
                    }
                    val fontStyleRaw = (raw.entries["fontStyle"] as? JsonString)?.value
                    val boldProp = (raw.entries["bold"] as? JsonBoolean)?.value
                    val italicProp = (raw.entries["italic"] as? JsonBoolean)?.value
                    val underlineProp = (raw.entries["underline"] as? JsonBoolean)?.value
                    val strikethroughProp = (raw.entries["strikethrough"] as? JsonBoolean)?.value
                    val hasStyleProp = fontStyleRaw != null ||
                        boldProp != null ||
                        italicProp != null ||
                        underlineProp != null ||
                        strikethroughProp != null
                    if (fg == null && !hasStyleProp) continue
                    val fontTokens = splitWhitespaceTokens(fontStyleRaw ?: "")
                    val bold = boldProp ?: ("bold" in fontTokens)
                    val italic = italicProp ?: ("italic" in fontTokens)
                    val underline = underlineProp ?: ("underline" in fontTokens)
                    val strikethrough = strikethroughProp ?: ("strikethrough" in fontTokens)
                    return SyntaxTokenStyle(
                        foreground = fg,
                        bold = bold,
                        italic = italic,
                        underline = underline,
                        strikethrough = strikethrough,
                    )
                }
                else -> Unit
            }
        }
        return null
    }

    private fun splitWhitespaceTokens(raw: String): Set<String> {
        val tokens = HashSet<String>()
        var start = -1
        for (i in raw.indices) {
            if (raw[i].isWhitespace()) {
                if (start >= 0) {
                    tokens += raw.substring(start, i).lowercase()
                    start = -1
                }
            } else if (start < 0) {
                start = i
            }
        }
        if (start >= 0) {
            tokens += raw.substring(start).lowercase()
        }
        return tokens
    }
}

internal fun slugify(raw: String): String {
    val sb = StringBuilder()
    var lastDash = true
    for (ch in raw.lowercase()) {
        when {
            ch in 'a'..'z' || ch in '0'..'9' -> {
                sb.append(ch)
                lastDash = false
            }
            !lastDash -> {
                sb.append('-')
                lastDash = true
            }
        }
    }
    while (sb.isNotEmpty() && sb[sb.length - 1] == '-') {
        sb.setLength(sb.length - 1)
    }
    return if (sb.isEmpty()) "imported-scheme" else sb.toString()
}
