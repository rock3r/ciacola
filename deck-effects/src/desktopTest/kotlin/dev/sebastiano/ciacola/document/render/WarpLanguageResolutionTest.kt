package dev.sebastiano.ciacola.document.render

import dev.bnorm.storyboard.Agsl
import dev.bnorm.storyboard.BasicCode
import dev.bnorm.storyboard.C
import dev.bnorm.storyboard.CSharp
import dev.bnorm.storyboard.Cpp
import dev.bnorm.storyboard.Css
import dev.bnorm.storyboard.Dart
import dev.bnorm.storyboard.Diff
import dev.bnorm.storyboard.Dockerfile
import dev.bnorm.storyboard.Glsl
import dev.bnorm.storyboard.Go
import dev.bnorm.storyboard.Groovy
import dev.bnorm.storyboard.Hcl
import dev.bnorm.storyboard.Html
import dev.bnorm.storyboard.Java
import dev.bnorm.storyboard.JavaScript
import dev.bnorm.storyboard.Json
import dev.bnorm.storyboard.Kotlin
import dev.bnorm.storyboard.Language
import dev.bnorm.storyboard.Markdown
import dev.bnorm.storyboard.Php
import dev.bnorm.storyboard.PowerShell
import dev.bnorm.storyboard.Protobuf
import dev.bnorm.storyboard.Python
import dev.bnorm.storyboard.Ruby
import dev.bnorm.storyboard.Rust
import dev.bnorm.storyboard.Shell
import dev.bnorm.storyboard.Sksl
import dev.bnorm.storyboard.Sql
import dev.bnorm.storyboard.Swift
import dev.bnorm.storyboard.Toml
import dev.bnorm.storyboard.TypeScript
import dev.bnorm.storyboard.Xml
import dev.bnorm.storyboard.Yaml
import dev.bnorm.storyboard.buildDiffList
import dev.bnorm.storyboard.parse
import dev.sebastiano.ciacola.syntax.SyntaxLanguage
import dev.sebastiano.ciacola.theme.DeckThemes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class WarpLanguageResolutionTest {

    @Test
    fun resolvesAllLanguagesAndAliasesAndDefaultsUnknownToKotlin() {
        val expectedBySyntaxLanguage: Map<SyntaxLanguage, Language> = mapOf(
            SyntaxLanguage.Kotlin to Kotlin,
            SyntaxLanguage.Xml to Xml,
            SyntaxLanguage.Json to Json,
            SyntaxLanguage.Html to Html,
            SyntaxLanguage.Css to Css,
            SyntaxLanguage.JavaScript to JavaScript,
            SyntaxLanguage.TypeScript to TypeScript,
            SyntaxLanguage.Rust to Rust,
            SyntaxLanguage.Go to Go,
            SyntaxLanguage.Python to Python,
            SyntaxLanguage.Toml to Toml,
            SyntaxLanguage.Java to Java,
            SyntaxLanguage.C to C,
            SyntaxLanguage.Cpp to Cpp,
            SyntaxLanguage.Glsl to Glsl,
            SyntaxLanguage.Sksl to Sksl,
            SyntaxLanguage.Agsl to Agsl,
            SyntaxLanguage.Yaml to Yaml,
            SyntaxLanguage.Sql to Sql,
            SyntaxLanguage.Shell to Shell,
            SyntaxLanguage.CSharp to CSharp,
            SyntaxLanguage.Php to Php,
            SyntaxLanguage.Swift to Swift,
            SyntaxLanguage.Ruby to Ruby,
            SyntaxLanguage.Dart to Dart,
            SyntaxLanguage.Groovy to Groovy,
            SyntaxLanguage.PowerShell to PowerShell,
            SyntaxLanguage.Dockerfile to Dockerfile,
            SyntaxLanguage.Diff to Diff,
            SyntaxLanguage.Markdown to Markdown,
            SyntaxLanguage.Protobuf to Protobuf,
            SyntaxLanguage.Hcl to Hcl,
        )
        assertEquals(32, expectedBySyntaxLanguage.size)
        assertEquals(SyntaxLanguage.entries.toSet(), expectedBySyntaxLanguage.keys)

        for ((syntaxLang, expectedWarpLang) in expectedBySyntaxLanguage) {
            assertSame(expectedWarpLang, resolveWarpLanguage(syntaxLang.id))
            assertSame(expectedWarpLang, resolveWarpLanguage("  ${syntaxLang.id.uppercase()}  "))
            for (alias in syntaxLang.aliases) {
                assertSame(
                    expectedWarpLang,
                    resolveWarpLanguage(alias),
                    "Alias '$alias' should resolve to $expectedWarpLang",
                )
                assertSame(
                    expectedWarpLang,
                    resolveWarpLanguage(alias.uppercase()),
                    "Uppercase alias '${alias.uppercase()}' should resolve to $expectedWarpLang",
                )
            }
        }

        // Null, blank, and unknown identifiers safely fall back to Kotlin as before.
        assertSame(Kotlin, resolveWarpLanguage(null))
        assertSame(Kotlin, resolveWarpLanguage(""))
        assertSame(Kotlin, resolveWarpLanguage("   "))
        assertSame(Kotlin, resolveWarpLanguage("unknown-language"))
    }

    @Test
    fun deckCodePaletteMapsEmittedScopesAcrossAllWarpLanguages() {
        val darkPalette = DeckThemes.StageDarkColors.code
        val lightPalette = DeckThemes.KeynoteLightColors.code

        val snippetsById = mapOf(
            "kotlin" to "@Composable fun Demo(x: Int = 42) { /* note */ val s = \"ok\" }",
            "xml" to "<?xml version=\"1.0\"?><Box attr=\"1\"><!-- comment --></Box>",
            "json" to "{ // jsonc\n  \"key\": 123, \"flag\": true }",
            "html" to "<!DOCTYPE html><section data-id=\"1\"><style>.a { color: red; }</style></section>",
            "css" to "@layer ui { .btn:hover { --gap: 8px; content: \"hi\"; } }",
            "js" to "export const f = (a) => /re/g.test(`${'$'}{a}`);",
            "tsx" to "type Props = { label: string }; export const View = (p: Props) => <span>{p.label}</span>;",
            "rs" to "#[derive(Debug)] pub fn run<'a>(s: &'a str) -> usize { s.len() }",
            "golang" to "package main\nfunc sum(xs []int) int { return len(xs) }",
            "py" to "@property\ndef total(self) -> int:\n    return f\"{1 + 2}\"",
            "toml" to "[server]\nhost = \"127.0.0.1\"\nport = 8080 # port",
            "java" to "@Override public String toString() { return \"item-\" + 1; }",
            "c" to "#include <stdio.h>\nint main(void) { return 0; }",
            "c++" to "#include <vector>\ntemplate <typename T> auto id(T v) { return v; }",
            "glsl" to "#version 450\nuniform vec4 uColor;\nvoid main() {}",
            "sksl" to "uniform shader child;\nhalf4 main(float2 p) { return child.eval(p); }",
            "agsl" to "uniform float2 iResolution;\nhalf4 main(float2 p) { return half4(0.5); }",
            "yml" to "server:\n  host: \"127.0.0.1\"\n  port: 8080 # port",
            "postgresql" to "SELECT u.id, COUNT(*) AS total FROM users u WHERE u.active = TRUE;",
            "zsh" to "if [[ -n \"${'$'}USER\" ]]; then echo \"hi ${'$'}USER\"; fi",
            "c#" to "[Serializable] public record User(string Name) { public int Id => 1; }",
            "phtml" to "<?php #[Route('/api')] function ok(int ${'$'}x): string { return \"v=${'$'}x\"; } ?>",
            "swift" to "@MainActor struct Counter { var count = 0; let re = /\\d+/ }",
            "gemspec" to "class Greeter\n  def greet(name) = \"Hello, #{name}!\"\nend",
            "dart" to "sealed class Result { final int code = 200; }",
            "gradle" to "@CompileStatic def greet(String s) { return \"hi ${'$'}s\" }",
            "pwsh" to "function Get-Status { param([string]${'$'}Env = 'prod') return ${'$'}Env }",
            "docker" to "FROM alpine:3.19 AS base\nRUN echo \"hi\"",
            "patch" to "--- a/Main.kt\n+++ b/Main.kt\n@@ -1 +1 @@\n-val x = 1\n+val x = 2",
            "md" to "# Heading\n\nParagraph with *em* and `code`.",
            "proto3" to "syntax = \"proto3\";\nmessage Ping { int32 id = 1; }",
            "tf" to "resource \"aws_s3_bucket\" \"app\" { count = 1 }",
        )
        assertEquals(32, snippetsById.size)

        for ((langId, code) in snippetsById) {
            val warpLang = resolveWarpLanguage(langId)
            val tokens = parse(code, warpLang)
            assertTrue(tokens.isNotEmpty(), "Expected tokens for $langId")
            val diffs = buildDiffList(listOf(BasicCode(code), BasicCode("$code\n")), warpLang)
            assertEquals(2, diffs.size)

            // Verify at least one syntax-colored (non-foreground) token is produced for every language in both palettes.
            val darkDistinct = tokens.map { darkPalette.colorForWarpScope(it.scope) }.toSet()
            val lightDistinct = tokens.map { lightPalette.colorForWarpScope(it.scope) }.toSet()
            assertTrue(darkDistinct.size >= 2, "Expected highlighted token colors in dark palette for $langId")
            assertTrue(lightDistinct.size >= 2, "Expected highlighted token colors in light palette for $langId")
        }

        // AGSL and SkSL shader types should render with the same palette.type color (not keyword).
        val agslTokens = parse("uniform float2 iResolution; half4 main(float2 p) { return half4(0.5); }", Agsl)
        val skslTokens = parse("uniform float2 iResolution; half4 main(float2 p) { return half4(0.5); }", Sksl)
        for (palette in listOf(darkPalette, lightPalette)) {
            assertEquals(
                palette.type,
                palette.colorForWarpScope(agslTokens.first { it.content == "float2" }.scope),
            )
            assertEquals(
                palette.type,
                palette.colorForWarpScope(agslTokens.first { it.content == "half4" }.scope),
            )
            assertEquals(
                palette.colorForWarpScope(skslTokens.first { it.content == "float2" }.scope),
                palette.colorForWarpScope(agslTokens.first { it.content == "float2" }.scope),
            )
            assertEquals(
                palette.keyword,
                palette.colorForWarpScope(agslTokens.first { it.content == "uniform" }.scope),
            )
        }
    }
}
