package dev.sebastiano.ciacola.syntax.theme

/**
 * Polarity and contrast classification of a [SyntaxColorScheme].
 */
public enum class SyntaxSchemeAppearance {
    Light,
    Dark,
    HighContrastLight,
    HighContrastDark,
    ;

    /** Whether this scheme uses a light canvas background. */
    public val isLight: Boolean
        get() = this == Light || this == HighContrastLight

    /** Whether this scheme is a high-contrast variant. */
    public val isHighContrast: Boolean
        get() = this == HighContrastLight || this == HighContrastDark
}
