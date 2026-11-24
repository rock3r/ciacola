package dev.sebastiano.ciacola.syntax.theme

import dev.sebastiano.ciacola.syntax.SyntaxTokenKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.DurationUnit
import kotlin.time.TimeSource

class SyntaxSchemeImportersTest {

    @Test
    fun intelliJXmlResolvesInheritanceWholeValueReplacementAndBaseAttributes() {
        val customParent = """
            <scheme name="Custom Parent" version="142" parent_scheme="Darcula">
              <colors>
                <option name="ADDED_LINES_COLOR" value="112233" />
              </colors>
              <attributes>
                <option name="DEFAULT_KEYWORD">
                  <value>
                    <option name="FOREGROUND" value="ff00aa" />
                    <option name="BACKGROUND" value="222222" />
                    <option name="FONT_TYPE" value="3" />
                  </value>
                </option>
                <option name="CUSTOM_ACCENT">
                  <value>
                    <option name="FOREGROUND" value="00ccff" />
                    <option name="FONT_TYPE" value="2" />
                  </value>
                </option>
              </attributes>
            </scheme>
        """.trimIndent()

        val childXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE scheme>
            <!-- Comment before root -->
            <scheme name="My Child" version="142" parent_scheme="Custom Parent">
              <colors>
                <option name="CARET_ROW_COLOR" value="1234" />
                <option name="LINE_NUMBERS_COLOR" value="FFFFFF80" />
              </colors>
              <attributes>
                <option name="TEXT">
                  <value>
                    <option name="FOREGROUND" value="e0e0e0" />
                    <option name="BACKGROUND" value="101010" />
                  </value>
                </option>
                <!-- Child value replaces parent value completely (no inherited bg/fontType) -->
                <option name="DEFAULT_KEYWORD">
                  <value>
                    <option name="FOREGROUND" value="80" />
                  </value>
                </option>
                <!-- baseAttributes resolves within active scheme first -->
                <option name="DEFAULT_NUMBER" baseAttributes="CUSTOM_ACCENT" />
                <!-- Cycle in baseAttributes falls back cleanly without hanging -->
                <option name="CYCLE_A" baseAttributes="CYCLE_B" />
                <option name="CYCLE_B" baseAttributes="CYCLE_A" />
                <option name="DEFAULT_STRING">
                  <value>
                    <option name="FOREGROUND" value="6aab73" />
                    <option name="FONT_TYPE" value="1" />
                    <option name="EFFECT_COLOR" value="6aab73" />
                    <option name="EFFECT_TYPE" value="1" />
                  </value>
                </option>
                <option name="WRONG_REFERENCES_ATTRIBUTES">
                  <value>
                    <option name="FOREGROUND" value="f75464" />
                    <option name="EFFECT_COLOR" value="f75464" />
                    <option name="EFFECT_TYPE" value="3" />
                  </value>
                </option>
              </attributes>
            </scheme>
        """.trimIndent()

        val scheme = SyntaxSchemeImporters.importIntelliJXml(
            xml = childXml,
            parentSchemes = mapOf("Custom Parent" to customParent),
        )
        assertEquals("my-child", scheme.id)
        assertEquals("My Child", scheme.name)
        assertEquals(SyntaxSchemeAppearance.Dark, scheme.appearance)
        assertEquals("#101010", scheme.background.toHexRgb())
        assertEquals("#E0E0E0", scheme.foreground.toHexRgb())
        assertEquals("#001234", scheme.lineHighlightBackground?.toHexRgb())
        assertEquals("#FFFFFF80", scheme.lineNumberForeground?.toHexRgba())
        assertEquals("#112233", scheme.diffInserted?.toHexRgb())

        // Whole-value replacement: DEFAULT_KEYWORD has fg=#000080, NO bg, NO bold/italic
        val kwStyle = scheme.styleFor(SyntaxTokenKind.Keyword)
        assertEquals("#000080", kwStyle.foreground?.toHexRgb())
        assertNull(kwStyle.background)
        assertFalse(kwStyle.bold)
        assertFalse(kwStyle.italic)

        // baseAttributes resolved CUSTOM_ACCENT
        val numStyle = scheme.styleFor(SyntaxTokenKind.Number)
        assertEquals("#00CCFF", numStyle.foreground?.toHexRgb())
        assertTrue(numStyle.italic)

        // FONT_TYPE + EFFECT_TYPE underline and strikethrough
        val strStyle = scheme.styleFor(SyntaxTokenKind.String)
        assertTrue(strStyle.bold)
        assertTrue(strStyle.underline)
        assertFalse(strStyle.strikethrough)

        val errStyle = scheme.styleFor(SyntaxTokenKind.Invalid)
        assertTrue(errStyle.strikethrough)
        assertFalse(errStyle.underline)

        // Inherited Darcula attribute + contrast guard against Default #0000FF
        assertEquals("#BABABA", scheme.foregroundFor(SyntaxTokenKind.Attribute).toHexRgb())
    }

    @Test
    fun intelliJXmlSupportsBundledParentAndLightDefaultAttribute() {
        val childOfIslands = """
            <scheme name="Islands Custom" parent_scheme="Islands Dark">
              <attributes>
                <option name="DEFAULT_KEYWORD">
                  <value>
                    <option name="FOREGROUND" value="ff8800" />
                  </value>
                </option>
              </attributes>
            </scheme>
        """.trimIndent()
        val imported = SyntaxSchemeImporters.importIntelliJXml(childOfIslands)
        assertEquals("#191A1C", imported.background.toHexRgb())
        assertEquals("#FF8800", imported.foregroundFor(SyntaxTokenKind.Keyword).toHexRgb())
        assertEquals("#6AAB73", imported.foregroundFor(SyntaxTokenKind.String).toHexRgb())

        val lightXml = """
            <scheme name="Minimal Light" parent_scheme="Default">
              <attributes>
                <option name="TEXT">
                  <value>
                    <option name="FOREGROUND" value="80808" />
                    <option name="BACKGROUND" value="ffffff" />
                  </value>
                </option>
                <option name="DEFAULT_ATTRIBUTE">
                  <value>
                    <option name="FOREGROUND" value="174ad4" />
                  </value>
                </option>
              </attributes>
            </scheme>
        """.trimIndent()
        val lightImported = SyntaxSchemeImporters.importIntelliJXml(lightXml)
        assertEquals(SyntaxSchemeAppearance.Light, lightImported.appearance)
        assertEquals("#174AD4", lightImported.foregroundFor(SyntaxTokenKind.Attribute).toHexRgb())
    }

    @Test
    fun intelliJXmlRejectsMalformedAndUnsafeEntityInputs() {
        assertFailsWith<SyntaxSchemeParseException> {
            SyntaxSchemeImporters.importIntelliJXml("")
        }
        assertFailsWith<SyntaxSchemeParseException> {
            SyntaxSchemeImporters.importIntelliJXml("<scheme name=\"Unclosed\">")
        }
        assertFailsWith<SyntaxSchemeParseException> {
            SyntaxSchemeImporters.importIntelliJXml("<scheme name=\"Bad\"></other>")
        }
        assertFailsWith<SyntaxSchemeParseException> {
            SyntaxSchemeImporters.importIntelliJXml(
                """<!DOCTYPE scheme [<!ENTITY xxe SYSTEM "file:///etc/passwd">]><scheme name="XXE"/>""",
            )
        }
        assertFailsWith<SyntaxSchemeParseException> {
            SyntaxSchemeImporters.importIntelliJXml(
                """<scheme name="&customEntity;"/>""",
            )
        }

        val tooDeep = "<a>".repeat(140) + "</a>".repeat(140)
        assertFailsWith<SyntaxSchemeParseException> {
            SyntaxSchemeImporters.importIntelliJXml(tooDeep)
        }
    }

    @Test
    fun vsCodeJsoncHandlesCommentsTrailingCommasIncludesSpecificityAndSemanticFallback() {
        val baseTheme = """
            {
              // Base palette
              "name": "Base Dark",
              "type": "dark",
              "colors": {
                "editor.background": "#121316",
                "editor.foreground": "#D0D4DC",
                "editor.lineHighlightBackground": "#ffffff10",
              },
              "tokenColors": [
                {
                  "scope": "keyword, storage",
                  "settings": { "foreground": "#cc7832" }
                },
                {
                  "scope": "constant",
                  "settings": { "foreground": "#6897bb" }
                }
              ],
              "semanticTokenColors": {
                "decorator": { "foreground": "#bbb529", "bold": true }
              }
            }
        """.trimIndent()

        val childTheme = """
            /* Multi-line JSONC header */
            {
              "name": "Child Theme \u002B",
              "include": "./base.json",
              "colors": {
                "editorWhitespace.foreground": "#445566",
                "editorGutter.addedBackground": "#4ac26b66",
              },
              "tokenColors": [
                {
                  "scope": ["keyword.control", "string"],
                  "settings": { "foreground": "#ff7b72", "fontStyle": "bold italic" }
                },
                {
                  // Descendant selector must not hijack bare 'variable'
                  "scope": "string variable",
                  "settings": { "foreground": "#00ff00" }
                },
                {
                  // Deeper scope wins over earlier 'constant'
                  "scope": "constant.numeric",
                  "settings": { "foreground": "#33ccff" }
                },
                {
                  "scope": "invalid.illegal",
                  "settings": { "foreground": "#f75464", "fontStyle": "underline strikethrough" }
                },
              ],
            }
        """.trimIndent()

        val imported = SyntaxSchemeImporters.importVsCodeJson(
            json = childTheme,
            includeResolver = { path ->
                when (path) {
                    "./base.json" -> baseTheme
                    else -> null
                }
            },
        )

        assertEquals("Child Theme +", imported.name)
        assertEquals(SyntaxSchemeAppearance.Dark, imported.appearance)
        assertEquals("#121316", imported.background.toHexRgb())
        assertEquals("#D0D4DC", imported.foreground.toHexRgb())
        assertEquals("#FFFFFF10", imported.lineHighlightBackground?.toHexRgba())
        assertEquals("#445566", imported.foregroundFor(SyntaxTokenKind.Whitespace).toHexRgb())
        assertEquals("#4AC26B66", imported.diffInserted?.toHexRgba())

        val kw = imported.styleFor(SyntaxTokenKind.Keyword)
        assertEquals("#FF7B72", kw.foreground?.toHexRgb())
        assertTrue(kw.bold)
        assertTrue(kw.italic)

        // Specificity: constant.numeric (#33CCFF) beats constant (#6897BB)
        assertEquals("#33CCFF", imported.foregroundFor(SyntaxTokenKind.Number).toHexRgb())
        assertEquals("#6897BB", imported.foregroundFor(SyntaxTokenKind.Constant).toHexRgb())

        // Semantic token fallback for Annotation -> decorator (#BBB529, bold)
        val ann = imported.styleFor(SyntaxTokenKind.Annotation)
        assertEquals("#BBB529", ann.foreground?.toHexRgb())
        assertTrue(ann.bold)

        val invalid = imported.styleFor(SyntaxTokenKind.Invalid)
        assertTrue(invalid.underline)
        assertTrue(invalid.strikethrough)

        // Include cycle detection stops cleanly without StackOverflowError
        val cycleA = """{"name":"CycleA","include":"b.json","colors":{"editor.background":"#000000","editor.foreground":"#FFFFFF"}}"""
        val cycleB = """{"name":"CycleB","include":"a.json","tokenColors":[{"scope":"keyword","settings":{"foreground":"#FF0000"}}]}"""
        val cyclic = SyntaxSchemeImporters.importVsCodeJson(
            json = cycleA,
            includeResolver = { if (it == "b.json") cycleB else cycleA },
        )
        assertEquals("#FF0000", cyclic.foregroundFor(SyntaxTokenKind.Keyword).toHexRgb())
    }

    @Test
    fun vsCodeJsonRejectsMalformedAndDeepInputs() {
        assertFailsWith<SyntaxSchemeParseException> {
            SyntaxSchemeImporters.importVsCodeJson("")
        }
        assertFailsWith<SyntaxSchemeParseException> {
            SyntaxSchemeImporters.importVsCodeJson("""{"name": "x" /* unterminated""")
        }
        assertFailsWith<SyntaxSchemeParseException> {
            SyntaxSchemeImporters.importVsCodeJson("""{"include": "missing.json"}""", includeResolver = { null })
        }
        val tooDeep = "[".repeat(140) + "]".repeat(140)
        assertFailsWith<SyntaxSchemeParseException> {
            SyntaxSchemeImporters.importVsCodeJson("""{"a": $tooDeep}""")
        }
    }

    @Test
    fun importAutoDetectsAllSupportedFormats() {
        val compact = SyntaxColorSchemes.GitHubDark.toCompactJson()
        assertEquals(SyntaxColorSchemes.GitHubDark, SyntaxSchemeImporters.importAuto(compact))

        val xmlWithBom = "\uFEFF<scheme name=\"Auto XML\" parent_scheme=\"Darcula\"/>"
        val fromXml = SyntaxSchemeImporters.importAuto(xmlWithBom, fileName = "auto.icls")
        assertEquals("Auto XML", fromXml.name)
        assertEquals("#CC7832", fromXml.foregroundFor(SyntaxTokenKind.Keyword).toHexRgb())

        val jsonc = """
            // Leading comment
            {
              "type": "light",
              "colors": { "editor.background": "#FFFFFF", "editor.foreground": "#111111" },
              "tokenColors": [{ "scope": "keyword", "settings": { "foreground": "#0033B3" } }]
            }
        """.trimIndent()
        val fromJsonc = SyntaxSchemeImporters.importAuto(jsonc, fileName = "my-light-theme.jsonc")
        assertEquals("my-light-theme", fromJsonc.name)
        assertEquals("#0033B3", fromJsonc.foregroundFor(SyntaxTokenKind.Keyword).toHexRgb())

        assertFailsWith<SyntaxSchemeParseException> {
            SyntaxSchemeImporters.importAuto("just plain text")
        }
    }

    @Test
    fun realWorldParityBetweenBundledSchemesAndDirectImport() {
        // Representative Islands Dark XML snippet matching JetBrains IslandSchemeDark.xml values
        val islandsSnippet = """
            <scheme name="Islands Dark" version="142" parent_scheme="Darcula">
              <colors>
                <option name="CARET_ROW_COLOR" value="1F2024" />
                <option name="LINE_NUMBERS_COLOR" value="4B5059" />
                <option name="ADDED_LINES_COLOR" value="549159" />
                <option name="DELETED_LINES_COLOR" value="868A91" />
                <option name="MODIFIED_LINES_COLOR" value="375FAD" />
                <option name="WHITESPACES" value="6F737A" />
              </colors>
              <attributes>
                <option name="ANNOTATION_NAME_ATTRIBUTES" baseAttributes="DEFAULT_METADATA" />
                <option name="BAD_CHARACTER">
                  <value>
                    <option name="FOREGROUND" value="f75464" />
                    <option name="EFFECT_TYPE" value="2" />
                  </value>
                </option>
                <option name="DEFAULT_BRACES"><value><option name="FOREGROUND" value="bcbec4" /></value></option>
                <option name="DEFAULT_CONSTANT"><value><option name="FOREGROUND" value="c77dbb" /><option name="FONT_TYPE" value="2" /></value></option>
                <option name="DEFAULT_FUNCTION_DECLARATION"><value><option name="FOREGROUND" value="56a8f5" /></value></option>
                <option name="DEFAULT_INSTANCE_FIELD"><value><option name="FOREGROUND" value="c77dbb" /></value></option>
                <option name="DEFAULT_KEYWORD"><value><option name="FOREGROUND" value="cf8e6d" /></value></option>
                <option name="DEFAULT_LINE_COMMENT"><value><option name="FOREGROUND" value="7a7e85" /></value></option>
                <option name="DEFAULT_METADATA"><value><option name="FOREGROUND" value="b3ae60" /></value></option>
                <option name="DEFAULT_NUMBER"><value><option name="FOREGROUND" value="2aacb8" /></value></option>
                <option name="DEFAULT_OPERATION_SIGN"><value><option name="FOREGROUND" value="bcbec4" /></value></option>
                <option name="DEFAULT_STRING"><value><option name="FOREGROUND" value="6aab73" /></value></option>
                <option name="TEXT"><value><option name="FOREGROUND" value="bcbec4" /><option name="BACKGROUND" value="191A1C" /></value></option>
                <option name="TYPE_PARAMETER_NAME_ATTRIBUTES"><value><option name="FOREGROUND" value="16baac" /></value></option>
                <option name="XML_ATTRIBUTE_NAME"><value><option name="FOREGROUND" value="bcbec4" /></value></option>
                <option name="XML_TAG_NAME"><value><option name="FOREGROUND" value="d5b778" /></value></option>
              </attributes>
            </scheme>
        """.trimIndent()

        val importedIslands = SyntaxSchemeImporters.importIntelliJXml(islandsSnippet)
        val bundledIslands = SyntaxColorSchemes.IntelliJIslandsDark
        assertEquals(bundledIslands.background, importedIslands.background)
        assertEquals(bundledIslands.foreground, importedIslands.foreground)
        assertEquals(bundledIslands.selectionBackground, importedIslands.selectionBackground)
        assertEquals(bundledIslands.lineHighlightBackground, importedIslands.lineHighlightBackground)
        assertEquals(bundledIslands.lineNumberForeground, importedIslands.lineNumberForeground)
        assertEquals(bundledIslands.diffInserted, importedIslands.diffInserted)
        assertEquals(bundledIslands.diffDeleted, importedIslands.diffDeleted)
        assertEquals(bundledIslands.diffChanged, importedIslands.diffChanged)
        assertEquals(bundledIslands.functionDeclaration, importedIslands.functionDeclaration)
        assertEquals(bundledIslands.styles, importedIslands.styles)

        // Representative GitHub Light JSON matching github-light-default.json
        val ghLightSnippet = """
            {
              "name": "GitHub Light",
              "type": "light",
              "colors": {
                "editor.background": "#ffffff",
                "editor.foreground": "#1f2328",
                "editor.lineHighlightBackground": "#eaeef280",
                "editorLineNumber.foreground": "#8c959f",
                "editorWhitespace.foreground": "#afb8c1",
                "editorGutter.addedBackground": "#4ac26b66",
                "editorGutter.deletedBackground": "#ff818266",
                "editorGutter.modifiedBackground": "#d4a72c66"
              },
              "tokenColors": [
                { "scope": ["comment", "punctuation.definition.comment"], "settings": { "foreground": "#6e7781" } },
                { "scope": ["constant", "entity"], "settings": { "foreground": "#0550ae" } },
                { "scope": ["entity.name"], "settings": { "foreground": "#953800" } },
                { "scope": "entity.name.function", "settings": { "foreground": "#8250df" } },
                { "scope": ["entity.name.tag"], "settings": { "foreground": "#116329" } },
                { "scope": "keyword", "settings": { "foreground": "#cf222e" } },
                { "scope": "string", "settings": { "foreground": "#0a3069" } },
                { "scope": "meta.property-name", "settings": { "foreground": "#0550ae" } },
                { "scope": "invalid.illegal", "settings": { "foreground": "#82071e", "fontStyle": "italic" } },
                { "scope": ["brackethighlighter.round"], "settings": { "foreground": "#57606a" } }
              ]
            }
        """.trimIndent()

        val importedGhLight = SyntaxSchemeImporters.importVsCodeJson(ghLightSnippet)
        val bundledGhLight = SyntaxColorSchemes.GitHubLight
        assertEquals(bundledGhLight.background, importedGhLight.background)
        assertEquals(bundledGhLight.foreground, importedGhLight.foreground)
        assertEquals(bundledGhLight.lineHighlightBackground, importedGhLight.lineHighlightBackground)
        assertEquals(bundledGhLight.lineNumberForeground, importedGhLight.lineNumberForeground)
        assertEquals(bundledGhLight.diffInserted, importedGhLight.diffInserted)
        assertEquals(bundledGhLight.diffDeleted, importedGhLight.diffDeleted)
        assertEquals(bundledGhLight.diffChanged, importedGhLight.diffChanged)
        assertEquals(bundledGhLight.styles, importedGhLight.styles)
    }

    @Test
    fun parsersScaleLinearlyOnLargeInputs() {
        val largeXml = buildString {
            append("<scheme name=\"Large\" parent_scheme=\"Darcula\"><attributes>")
            repeat(1500) { i ->
                append("<option name=\"OPT_$i\"><value><option name=\"FOREGROUND\" value=\"abcdef\"/></value></option>")
            }
            append("</attributes></scheme>")
        }
        val xmlStart = TimeSource.Monotonic.markNow()
        val xmlScheme = SyntaxSchemeImporters.importIntelliJXml(largeXml)
        val xmlElapsedMs = xmlStart.elapsedNow().toDouble(DurationUnit.MILLISECONDS)
        assertNotNull(xmlScheme)
        assertTrue(xmlElapsedMs < 500.0, "XML import took ${xmlElapsedMs}ms")

        val largeJsonc = buildString {
            append("{\n\"name\":\"LargeVS\",\n\"type\":\"dark\",\n\"colors\":{\"editor.background\":\"#000000\",\"editor.foreground\":\"#FFFFFF\"},\n\"tokenColors\":[\n")
            repeat(1500) { i ->
                append("/* comment $i */ {\"scope\":\"meta.custom.$i\",\"settings\":{\"foreground\":\"#abcdef\"}},\n")
            }
            append("{\"scope\":\"keyword\",\"settings\":{\"foreground\":\"#ff7b72\"}}\n]}")
        }
        val jsonStart = TimeSource.Monotonic.markNow()
        val jsonScheme = SyntaxSchemeImporters.importVsCodeJson(largeJsonc)
        val jsonElapsedMs = jsonStart.elapsedNow().toDouble(DurationUnit.MILLISECONDS)
        assertEquals("#FF7B72", jsonScheme.foregroundFor(SyntaxTokenKind.Keyword).toHexRgb())
        assertTrue(jsonElapsedMs < 500.0, "JSONC import took ${jsonElapsedMs}ms")
    }
}
