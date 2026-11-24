package dev.sebastiano.ciacola.document

import dev.sebastiano.ciacola.syntax.SyntaxTokenKind
import dev.sebastiano.ciacola.syntax.theme.SyntaxColor
import dev.sebastiano.ciacola.syntax.theme.SyntaxColorScheme
import dev.sebastiano.ciacola.syntax.theme.SyntaxColorSchemes
import dev.sebastiano.ciacola.syntax.theme.SyntaxSchemeAppearance
import dev.sebastiano.ciacola.syntax.theme.SyntaxSchemeMetadata
import dev.sebastiano.ciacola.syntax.theme.SyntaxTokenStyle

internal val CODE_SCHEME_TOKEN_KEYS: Map<String, SyntaxTokenKind> =
    SyntaxTokenKind.entries.associateBy { it.toSpecKey() }

/** Returns `true` if [preset] represents a light theme preset. */
fun isLightThemePreset(preset: String?): Boolean = when (preset?.lowercase()) {
    "keynotelight", "stagelight", "light" -> true
    else -> false
}

/**
 * Resolves the effective [SyntaxColorScheme] for [spec] (custom embedded scheme -> bundled id ->
 * preset default for [isLight]).
 */
fun resolveDeckCodeScheme(spec: DeckThemeSpec?, isLight: Boolean): SyntaxColorScheme {
    spec?.customCodeScheme?.toSyntaxColorSchemeOrNull()?.let { return it }
    spec?.codeScheme?.let(SyntaxColorSchemes::findById)?.let { return it }
    return SyntaxColorSchemes.defaultFor(isLight = isLight)
}

/** Converts a [SyntaxColorScheme] into a serializable [CodeSchemeSpec] for `.deck.json` embedding. */
fun SyntaxColorScheme.toCodeSchemeSpec(): CodeSchemeSpec {
    val tokenMap = LinkedHashMap<String, CodeTokenStyleSpec>()
    for (kind in SyntaxTokenKind.entries) {
        val style = styles[kind] ?: continue
        tokenMap[kind.toSpecKey()] = style.toSpec()
    }
    functionDeclaration?.let { tokenMap["functionDeclaration"] = it.toSpec() }

    val sourceSpec = if (
        metadata.family != null ||
        metadata.upstream != null ||
        metadata.license != null ||
        metadata.revision != null
    ) {
        CodeSchemeSourceSpec(
            family = metadata.family,
            upstream = metadata.upstream,
            license = metadata.license,
            revision = metadata.revision,
        )
    } else {
        null
    }

    return CodeSchemeSpec(
        id = id,
        name = name,
        appearance = appearance.toSpecString(),
        background = background.toCssHex(),
        foreground = foreground.toCssHex(),
        selectionBackground = selectionBackground?.toCssHex(),
        lineHighlightBackground = lineHighlightBackground?.toCssHex(),
        lineNumberForeground = lineNumberForeground?.toCssHex(),
        diffInserted = diffInserted?.toCssHex(),
        diffDeleted = diffDeleted?.toCssHex(),
        diffChanged = diffChanged?.toCssHex(),
        tokens = tokenMap,
        source = sourceSpec,
    )
}

/** Converts this [CodeSchemeSpec] into a [SyntaxColorScheme], throwing [IllegalArgumentException] if invalid. */
fun CodeSchemeSpec.toSyntaxColorScheme(): SyntaxColorScheme {
    require(id.isNotBlank()) { "customCodeScheme 'id' must not be blank" }
    require(name.isNotBlank()) { "customCodeScheme 'name' must not be blank" }
    val parsedAppearance = parseSpecAppearance(appearance)
    val bg = parseCssHexOrThrow(background, "background")
    val fg = parseCssHexOrThrow(foreground, "foreground")

    val parsedStyles = LinkedHashMap<SyntaxTokenKind, SyntaxTokenStyle>()
    var fnDecl: SyntaxTokenStyle? = null
    for ((key, tokenSpec) in tokens) {
        if (key == "functionDeclaration") {
            fnDecl = tokenSpec.toSyntaxTokenStyle(key)
            continue
        }
        val kind = CODE_SCHEME_TOKEN_KEYS[key] ?: continue
        parsedStyles[kind] = tokenSpec.toSyntaxTokenStyle(key)
    }

    return SyntaxColorScheme(
        id = id,
        name = name,
        appearance = parsedAppearance,
        background = bg,
        foreground = fg,
        selectionBackground = selectionBackground?.let { parseCssHexOrThrow(it, "selectionBackground") },
        lineHighlightBackground = lineHighlightBackground?.let { parseCssHexOrThrow(it, "lineHighlightBackground") },
        lineNumberForeground = lineNumberForeground?.let { parseCssHexOrThrow(it, "lineNumberForeground") },
        styles = parsedStyles,
        functionDeclaration = fnDecl,
        diffInserted = diffInserted?.let { parseCssHexOrThrow(it, "diffInserted") },
        diffDeleted = diffDeleted?.let { parseCssHexOrThrow(it, "diffDeleted") },
        diffChanged = diffChanged?.let { parseCssHexOrThrow(it, "diffChanged") },
        metadata = SyntaxSchemeMetadata(
            family = source?.family,
            upstream = source?.upstream,
            license = source?.license,
            revision = source?.revision,
        ),
    )
}

/** Converts this [CodeSchemeSpec] into a [SyntaxColorScheme], returning `null` if invalid. */
fun CodeSchemeSpec.toSyntaxColorSchemeOrNull(): SyntaxColorScheme? =
    runCatching { toSyntaxColorScheme() }.getOrNull()

private fun SyntaxColor.toCssHex(): String = if (alpha == 0xFF) toHexRgb() else toHexRgba()

private fun SyntaxTokenStyle.toSpec(): CodeTokenStyleSpec = CodeTokenStyleSpec(
    fg = foreground?.toCssHex(),
    bg = background?.toCssHex(),
    bold = bold,
    italic = italic,
    underline = underline,
    strikethrough = strikethrough,
)

private fun CodeTokenStyleSpec.toSyntaxTokenStyle(tokenKey: String): SyntaxTokenStyle = SyntaxTokenStyle(
    foreground = fg?.let { parseCssHexOrThrow(it, "tokens.$tokenKey.fg") },
    background = bg?.let { parseCssHexOrThrow(it, "tokens.$tokenKey.bg") },
    bold = bold,
    italic = italic,
    underline = underline,
    strikethrough = strikethrough,
)

private fun parseCssHexOrThrow(raw: String, field: String): SyntaxColor =
    SyntaxColor.parseCssHexOrNull(raw)
        ?: throw IllegalArgumentException("Invalid CSS hex color '$raw' in customCodeScheme.$field")

private fun SyntaxSchemeAppearance.toSpecString(): String = when (this) {
    SyntaxSchemeAppearance.Light -> "light"
    SyntaxSchemeAppearance.Dark -> "dark"
    SyntaxSchemeAppearance.HighContrastLight -> "high-contrast-light"
    SyntaxSchemeAppearance.HighContrastDark -> "high-contrast-dark"
}

internal fun parseSpecAppearanceOrNull(raw: String): SyntaxSchemeAppearance? = when (raw) {
    "light" -> SyntaxSchemeAppearance.Light
    "dark" -> SyntaxSchemeAppearance.Dark
    "high-contrast-light" -> SyntaxSchemeAppearance.HighContrastLight
    "high-contrast-dark" -> SyntaxSchemeAppearance.HighContrastDark
    else -> null
}

private fun parseSpecAppearance(raw: String): SyntaxSchemeAppearance =
    parseSpecAppearanceOrNull(raw)
        ?: throw IllegalArgumentException("Unknown customCodeScheme appearance '$raw'")

private fun SyntaxTokenKind.toSpecKey(): String = when (this) {
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
