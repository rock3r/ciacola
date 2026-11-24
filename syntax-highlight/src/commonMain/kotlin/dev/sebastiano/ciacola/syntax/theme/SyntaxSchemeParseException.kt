package dev.sebastiano.ciacola.syntax.theme

/**
 * Thrown when a syntax color scheme file or imported theme definition cannot be parsed.
 */
public class SyntaxSchemeParseException(
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)
