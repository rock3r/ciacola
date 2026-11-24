package dev.sebastiano.ciacola.syntax.theme

import dev.sebastiano.ciacola.syntax.internal.IjEmbeddedBaseSchemes
import dev.sebastiano.ciacola.syntax.internal.IjSchemeData
import dev.sebastiano.ciacola.syntax.internal.IntelliJSchemeMapper
import dev.sebastiano.ciacola.syntax.internal.JsonObject
import dev.sebastiano.ciacola.syntax.internal.JsonString
import dev.sebastiano.ciacola.syntax.internal.MiniJson
import dev.sebastiano.ciacola.syntax.internal.VsCodeThemeMapper

/**
 * Importers for external syntax color scheme formats into [SyntaxColorScheme].
 */
public object SyntaxSchemeImporters {

    /**
     * Imports an IntelliJ IDEA color scheme XML (`.xml` or `.icls`) into a [SyntaxColorScheme].
     *
     * Supports `parent_scheme` inheritance (including built-in `Default` and `Darcula` base tables
     * as well as bundled IntelliJ schemes), custom [parentSchemes] XML definitions, `baseAttributes`
     * chaining with cycle detection, 1..6-digit zero-padded hex colors, `FONT_TYPE` bitmasks, and
     * `EFFECT_TYPE` underline/strikethrough attributes.
     *
     * @throws SyntaxSchemeParseException if [xml] or any supplied parent XML is malformed.
     */
    public fun importIntelliJXml(
        xml: String,
        parentSchemes: Map<String, String> = emptyMap(),
        fallbackParent: SyntaxColorScheme? = null,
    ): SyntaxColorScheme {
        val schemeMap = LinkedHashMap<String, IjSchemeData>()
        schemeMap[IjEmbeddedBaseSchemes.defaultScheme.name] = IjEmbeddedBaseSchemes.defaultScheme
        schemeMap[IjEmbeddedBaseSchemes.darculaScheme.name] = IjEmbeddedBaseSchemes.darculaScheme

        for (bundled in SyntaxColorSchemes.intelliJ) {
            if (bundled.name !in schemeMap) {
                schemeMap[bundled.name] = IjEmbeddedBaseSchemes.toIjSchemeData(bundled)
            }
        }

        for ((key, parentXml) in parentSchemes) {
            val parsedParents = IntelliJSchemeMapper.parseXmlSchemes(parentXml)
            for (parent in parsedParents) {
                schemeMap[parent.name] = parent
            }
            if (key.isNotBlank() && key !in schemeMap && parsedParents.isNotEmpty()) {
                schemeMap[key] = parsedParents.first()
            }
        }

        val parsedPrimary = IntelliJSchemeMapper.parseXmlSchemes(xml)
        for (scheme in parsedPrimary) {
            schemeMap[scheme.name] = scheme
        }

        val primaryName = parsedPrimary.first().name
        val resolvedFallback = fallbackParent ?: run {
            val parentName = parsedPrimary.first().parentScheme
            if (parentName != null) SyntaxColorSchemes.findById(parentName) else null
        }
        return IntelliJSchemeMapper.convert(
            primaryName = primaryName,
            schemes = schemeMap,
            fallbackParent = resolvedFallback,
        )
    }

    /**
     * Imports a VS Code theme JSON or JSONC document into a [SyntaxColorScheme].
     *
     * Supports `//` and `/* */` comments, trailing commas, `include` chains via [includeResolver]
     * with cycle detection, `colors`, `tokenColors`, and `semanticTokenColors` fallback.
     *
     * @throws SyntaxSchemeParseException if [json] or any included document is malformed.
     */
    public fun importVsCodeJson(
        json: String,
        fallbackName: String? = null,
        includeResolver: ((String) -> String?)? = null,
    ): SyntaxColorScheme = VsCodeThemeMapper.import(
        json = json,
        fallbackName = fallbackName,
        includeResolver = includeResolver,
    )

    /**
     * Automatically detects Ciacola compact JSON (`"format": "ciacola-syntax-scheme"`), VS Code
     * theme JSON/JSONC, or IntelliJ XML/ICLS and imports the resulting [SyntaxColorScheme].
     *
     * @throws SyntaxSchemeParseException if the format cannot be detected or parsing fails.
     */
    public fun importAuto(
        content: String,
        fileName: String? = null,
    ): SyntaxColorScheme {
        val trimmed = content.trimStart { it == '\uFEFF' || it == ' ' || it == '\t' || it == '\r' || it == '\n' }
        if (trimmed.isEmpty()) {
            throw SyntaxSchemeParseException("Cannot import empty syntax color scheme")
        }
        if (trimmed.startsWith('<')) {
            return importIntelliJXml(content)
        }
        if (trimmed.startsWith('{') || trimmed.startsWith("//") || trimmed.startsWith("/*")) {
            val parsed = MiniJson.parse(content, allowComments = true, allowTrailingCommas = true)
            val obj = parsed as? JsonObject
                ?: throw SyntaxSchemeParseException("Expected top-level JSON object")
            val format = (obj.entries["format"] as? JsonString)?.value
            if (format == "ciacola-syntax-scheme") {
                return SyntaxColorScheme.fromCompactJson(content)
            }
            val fallbackName = deriveFallbackName(fileName)
            return importVsCodeJson(content, fallbackName = fallbackName)
        }
        throw SyntaxSchemeParseException("Unrecognized syntax color scheme format")
    }

    private fun deriveFallbackName(fileName: String?): String? {
        if (fileName.isNullOrBlank()) return null
        val leaf = fileName.substringAfterLast('/').substringAfterLast('\\')
        val stem = leaf.substringBeforeLast('.', leaf)
        return stem.takeIf { it.isNotBlank() }
    }
}
