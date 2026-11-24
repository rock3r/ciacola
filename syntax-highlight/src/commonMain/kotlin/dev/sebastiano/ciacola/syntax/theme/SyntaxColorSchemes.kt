package dev.sebastiano.ciacola.syntax.theme

import dev.sebastiano.ciacola.syntax.internal.BundledSyntaxSchemePayloads

/**
 * Registry of bundled IntelliJ Platform and GitHub Primer [SyntaxColorScheme] definitions.
 */
public object SyntaxColorSchemes {

    /** IntelliJ Islands Dark (default dark scheme). */
    public val IntelliJIslandsDark: SyntaxColorScheme by lazy {
        SyntaxColorScheme.fromCompactJson(BundledSyntaxSchemePayloads.INTELLIJ_ISLANDS_DARK)
    }

    /** IntelliJ Dark (New UI dark scheme). */
    public val IntelliJDark: SyntaxColorScheme by lazy {
        SyntaxColorScheme.fromCompactJson(BundledSyntaxSchemePayloads.INTELLIJ_DARK)
    }

    /** IntelliJ Light (New UI light scheme). */
    public val IntelliJLight: SyntaxColorScheme by lazy {
        SyntaxColorScheme.fromCompactJson(BundledSyntaxSchemePayloads.INTELLIJ_LIGHT)
    }

    /** Classic IntelliJ Darcula scheme. */
    public val IntelliJDarcula: SyntaxColorScheme by lazy {
        SyntaxColorScheme.fromCompactJson(BundledSyntaxSchemePayloads.INTELLIJ_DARCULA)
    }

    /** IntelliJ Darcula Contrast scheme. */
    public val IntelliJDarculaContrast: SyntaxColorScheme by lazy {
        SyntaxColorScheme.fromCompactJson(BundledSyntaxSchemePayloads.INTELLIJ_DARCULA_CONTRAST)
    }

    /** IntelliJ High Contrast dark scheme. */
    public val IntelliJHighContrast: SyntaxColorScheme by lazy {
        SyntaxColorScheme.fromCompactJson(BundledSyntaxSchemePayloads.INTELLIJ_HIGH_CONTRAST)
    }

    /** GitHub Light default Primer scheme. */
    public val GitHubLight: SyntaxColorScheme by lazy {
        SyntaxColorScheme.fromCompactJson(BundledSyntaxSchemePayloads.GITHUB_LIGHT)
    }

    /** GitHub Dark default Primer scheme. */
    public val GitHubDark: SyntaxColorScheme by lazy {
        SyntaxColorScheme.fromCompactJson(BundledSyntaxSchemePayloads.GITHUB_DARK)
    }

    /** GitHub Dark Dimmed Primer scheme. */
    public val GitHubDarkDimmed: SyntaxColorScheme by lazy {
        SyntaxColorScheme.fromCompactJson(BundledSyntaxSchemePayloads.GITHUB_DARK_DIMMED)
    }

    /** GitHub Light High Contrast Primer scheme. */
    public val GitHubLightHighContrast: SyntaxColorScheme by lazy {
        SyntaxColorScheme.fromCompactJson(BundledSyntaxSchemePayloads.GITHUB_LIGHT_HIGH_CONTRAST)
    }

    /** GitHub Dark High Contrast Primer scheme. */
    public val GitHubDarkHighContrast: SyntaxColorScheme by lazy {
        SyntaxColorScheme.fromCompactJson(BundledSyntaxSchemePayloads.GITHUB_DARK_HIGH_CONTRAST)
    }

    /** All 6 bundled IntelliJ Platform schemes. */
    public val intelliJ: List<SyntaxColorScheme> by lazy {
        listOf(
            IntelliJIslandsDark,
            IntelliJDark,
            IntelliJLight,
            IntelliJDarcula,
            IntelliJDarculaContrast,
            IntelliJHighContrast,
        )
    }

    /** All 5 bundled GitHub Primer schemes. */
    public val gitHub: List<SyntaxColorScheme> by lazy {
        listOf(
            GitHubLight,
            GitHubDark,
            GitHubDarkDimmed,
            GitHubLightHighContrast,
            GitHubDarkHighContrast,
        )
    }

    /** All 11 bundled schemes across IntelliJ Platform and GitHub Primer families. */
    public val all: List<SyntaxColorScheme> by lazy { intelliJ + gitHub }

    private val lookupMap: Map<String, SyntaxColorScheme> by lazy {
        buildMap {
            for (scheme in all) {
                put(normalizeLookupKey(scheme.id), scheme)
                put(normalizeLookupKey(scheme.name), scheme)
            }
            put("islands-dark", IntelliJIslandsDark)
            put("dark", IntelliJDark)
            put("light", IntelliJLight)
            put("darcula", IntelliJDarcula)
            put("darcula-contrast", IntelliJDarculaContrast)
            put("high-contrast", IntelliJHighContrast)
            put("github-light-default", GitHubLight)
            put("github-dark-default", GitHubDark)
        }
    }

    /**
     * Finds a bundled scheme by exact slug, display name, or common alias (case-insensitive),
     * or returns `null` if no match exists.
     */
    public fun findById(id: String): SyntaxColorScheme? {
        if (id.isBlank()) return null
        return lookupMap[normalizeLookupKey(id)]
    }

    /**
     * Returns the default bundled scheme for the given polarity ([IntelliJLight] or [IntelliJIslandsDark]).
     */
    public fun defaultFor(isLight: Boolean): SyntaxColorScheme =
        if (isLight) IntelliJLight else IntelliJIslandsDark

    private fun normalizeLookupKey(raw: String): String = buildString {
        var lastDash = true
        for (ch in raw.trim().lowercase()) {
            when {
                ch in 'a'..'z' || ch in '0'..'9' -> {
                    append(ch)
                    lastDash = false
                }
                !lastDash -> {
                    append('-')
                    lastDash = true
                }
            }
        }
        while (isNotEmpty() && last() == '-') {
            setLength(length - 1)
        }
    }
}
