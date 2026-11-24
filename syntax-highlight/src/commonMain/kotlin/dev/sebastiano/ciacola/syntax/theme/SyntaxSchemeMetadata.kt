package dev.sebastiano.ciacola.syntax.theme

/**
 * Optional provenance and licensing metadata for a [SyntaxColorScheme].
 */
public data class SyntaxSchemeMetadata(
    public val family: String? = null,
    public val upstream: String? = null,
    public val license: String? = null,
    public val revision: String? = null,
)
