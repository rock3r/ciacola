package dev.sebastiano.ciacola.syntax.theme

/**
 * Visual styling attributes for a token kind in a [SyntaxColorScheme].
 */
public data class SyntaxTokenStyle(
    public val foreground: SyntaxColor? = null,
    public val background: SyntaxColor? = null,
    public val bold: Boolean = false,
    public val italic: Boolean = false,
    public val underline: Boolean = false,
    public val strikethrough: Boolean = false,
) {
    public companion object {
        /** Empty style with no explicit overrides. */
        public val Default: SyntaxTokenStyle = SyntaxTokenStyle()
    }
}
