package dev.sebastiano.ciacola.syntax

/**
 * Supported syntax highlighting languages, along with their TextMate-style [scopeName]
 * and recognized file/fence [aliases].
 */
public enum class SyntaxLanguage(
    public val id: String,
    public val scopeName: String,
    public val aliases: Set<String>,
) {
    Kotlin("kotlin", "source.kotlin", setOf("kt", "kts")),
    Xml("xml", "text.xml", setOf("svg", "xsd", "plist")),
    Json("json", "source.json", setOf("jsonc")),
    Html("html", "text.html.basic", setOf("htm", "xhtml")),
    Css("css", "source.css", emptySet()),
    JavaScript("javascript", "source.js", setOf("js", "mjs", "cjs", "jsx")),
    TypeScript("typescript", "source.ts", setOf("ts", "mts", "cts", "tsx")),
    Rust("rust", "source.rust", setOf("rs")),
    Go("go", "source.go", setOf("golang")),
    Python("python", "source.python", setOf("py", "pyi")),
    Toml("toml", "source.toml", setOf("tml")),
    Java("java", "source.java", emptySet()),
    C("c", "source.c", setOf("h")),
    Cpp("cpp", "source.cpp", setOf("c++", "cc", "cxx", "hpp", "hh", "hxx")),
    Glsl("glsl", "source.glsl", setOf("vert", "frag", "comp", "geom", "tesc", "tese")),
    Sksl("sksl", "source.sksl", emptySet()),
    Agsl("agsl", "source.agsl", emptySet()),
    ;

    public companion object {
        private val byNameOrAlias: Map<String, SyntaxLanguage> = buildMap {
            for (language in SyntaxLanguage.entries) {
                put(language.id.lowercase(), language)
                for (alias in language.aliases) {
                    put(alias.lowercase(), language)
                }
            }
        }

        /**
         * Resolves a [SyntaxLanguage] by canonical [id] or any entry in [aliases],
         * ignoring surrounding whitespace and letter case.
         */
        public fun fromNameOrAlias(name: String): SyntaxLanguage? {
            val normalized = name.trim().lowercase()
            if (normalized.isEmpty()) return null
            return byNameOrAlias[normalized]
        }
    }
}
