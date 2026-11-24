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

        // Canonical IntelliJ parent_scheme names: "Light", "Dark", "High contrast", and _@user_*
        val childOfLight = """
            <scheme name="Custom Light" parent_scheme="Light">
              <attributes>
                <option name="DEFAULT_ATTRIBUTE">
                  <value><option name="FOREGROUND" value="aa1122" /></value>
                </option>
              </attributes>
            </scheme>
        """.trimIndent()
        val importedLightChild = SyntaxSchemeImporters.importIntelliJXml(childOfLight)
        assertEquals(SyntaxSchemeAppearance.Light, importedLightChild.appearance)
        assertEquals("#0033B3", importedLightChild.foregroundFor(SyntaxTokenKind.Keyword).toHexRgb())
        assertEquals("#067D17", importedLightChild.foregroundFor(SyntaxTokenKind.String).toHexRgb())
        assertEquals("#00627A", importedLightChild.foregroundFor(SyntaxTokenKind.Function).toHexRgb())
        assertEquals("#AA1122", importedLightChild.foregroundFor(SyntaxTokenKind.Attribute).toHexRgb())

        val childOfDark = """
            <scheme name="Custom Dark" parent_scheme="Dark">
              <attributes>
                <option name="DEFAULT_STRING">
                  <value><option name="FOREGROUND" value="55cc88" /></value>
                </option>
              </attributes>
            </scheme>
        """.trimIndent()
        val importedDarkChild = SyntaxSchemeImporters.importIntelliJXml(childOfDark)
        assertEquals("#1E1F22", importedDarkChild.background.toHexRgb())
        assertEquals("#CF8E6D", importedDarkChild.foregroundFor(SyntaxTokenKind.Keyword).toHexRgb())
        assertEquals("#55CC88", importedDarkChild.foregroundFor(SyntaxTokenKind.String).toHexRgb())

        val childOfHc = """
            <scheme name="Custom HC" parent_scheme="High contrast">
              <attributes>
                <option name="DEFAULT_NUMBER">
                  <value><option name="FOREGROUND" value="00ffcc" /></value>
                </option>
              </attributes>
            </scheme>
        """.trimIndent()
        val importedHcChild = SyntaxSchemeImporters.importIntelliJXml(childOfHc)
        assertEquals(SyntaxSchemeAppearance.HighContrastDark, importedHcChild.appearance)
        assertEquals("#000000", importedHcChild.background.toHexRgb())
        assertEquals("#ED864A", importedHcChild.foregroundFor(SyntaxTokenKind.Keyword).toHexRgb())
        assertEquals("#00FFCC", importedHcChild.foregroundFor(SyntaxTokenKind.Number).toHexRgb())

        // Bare <scheme> without parent_scheme defaults to IntelliJ Default scheme
        val bareScheme = """<scheme name="Bare"><attributes/></scheme>"""
        val importedBare = SyntaxSchemeImporters.importIntelliJXml(bareScheme)
        val bareKeyword = importedBare.styleFor(SyntaxTokenKind.Keyword)
        assertEquals("#000080", bareKeyword.foreground?.toHexRgb())
        assertTrue(bareKeyword.bold)

        // Signed FONT_TYPE values ("+1", "-1") are ignored like non-digit strings
        val signedFontType = """
            <scheme name="Signed Font">
              <attributes>
                <option name="DEFAULT_KEYWORD">
                  <value><option name="FOREGROUND" value="123456" /><option name="FONT_TYPE" value="+1" /></value>
                </option>
                <option name="DEFAULT_STRING">
                  <value><option name="FOREGROUND" value="123456" /><option name="FONT_TYPE" value="-1" /></value>
                </option>
              </attributes>
            </scheme>
        """.trimIndent()
        val importedSigned = SyntaxSchemeImporters.importIntelliJXml(signedFontType)
        assertFalse(importedSigned.styleFor(SyntaxTokenKind.Keyword).bold)
        assertFalse(importedSigned.styleFor(SyntaxTokenKind.String).bold)
        assertFalse(importedSigned.styleFor(SyntaxTokenKind.String).italic)

        // _@user_* user-modified IntelliJ export carrying metaInfo/originalScheme
        val userModifiedLight = """
            <scheme name="_@user_Light" parent_scheme="Default">
              <metaInfo>
                <property name="originalScheme">Light</property>
              </metaInfo>
              <attributes>
                <option name="DEFAULT_KEYWORD">
                  <value><option name="FOREGROUND" value="990044" /></value>
                </option>
              </attributes>
            </scheme>
        """.trimIndent()
        val importedUserLight = SyntaxSchemeImporters.importIntelliJXml(userModifiedLight)
        assertEquals("#990044", importedUserLight.foregroundFor(SyntaxTokenKind.Keyword).toHexRgb())
        assertEquals("#067D17", importedUserLight.foregroundFor(SyntaxTokenKind.String).toHexRgb())
        assertEquals("#174AD4", importedUserLight.foregroundFor(SyntaxTokenKind.Attribute).toHexRgb())

        // Explicit fallbackParent fills unoverridden tokens
        val soloXml = """
            <scheme name="Solo Override">
              <attributes>
                <option name="DEFAULT_KEYWORD">
                  <value><option name="FOREGROUND" value="ff5500" /></value>
                </option>
              </attributes>
            </scheme>
        """.trimIndent()
        val withFallback = SyntaxSchemeImporters.importIntelliJXml(
            xml = soloXml,
            fallbackParent = SyntaxColorSchemes.GitHubDark,
        )
        assertEquals("#0D1117", withFallback.background.toHexRgb())
        assertEquals("#FF5500", withFallback.foregroundFor(SyntaxTokenKind.Keyword).toHexRgb())
        assertEquals("#A5D6FF", withFallback.foregroundFor(SyntaxTokenKind.String).toHexRgb())
        assertEquals("#D2A8FF", withFallback.foregroundFor(SyntaxTokenKind.Function).toHexRgb())
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

        // DOCTYPE comments containing quotes/apostrophes must not confuse quote tracking,
        // while <!DOCTYPEfoo> without delimiter whitespace and <!ENTITY inside DOCTYPE must fail.
        val doctypeCommentWithQuotes = """
            <!DOCTYPE scheme [ <!-- author's note with "quotes" --> ]>
            <scheme name="Safe Doctype" parent_scheme="Darcula"/>
        """.trimIndent()
        assertEquals("#CC7832", SyntaxSchemeImporters.importIntelliJXml(doctypeCommentWithQuotes).foregroundFor(SyntaxTokenKind.Keyword).toHexRgb())

        assertFailsWith<SyntaxSchemeParseException> {
            SyntaxSchemeImporters.importIntelliJXml(
                """<!DOCTYPE scheme [ <!-- " --><!ENTITY xxe "bad"><!-- " --> ]><scheme name="Masked"/>""",
            )
        }
        assertFailsWith<SyntaxSchemeParseException> {
            SyntaxSchemeImporters.importIntelliJXml("""<!DOCTYPEfoo><scheme name="Bad"/>""")
        }
        for (badEntity in listOf("&#+65;", "&#-65;", "&#x+41;", "&#x;")) {
            val ex = assertFailsWith<SyntaxSchemeParseException> {
                SyntaxSchemeImporters.importIntelliJXml("""<scheme name="$badEntity"/>""")
            }
            assertFalse(ex.message.orEmpty().contains("&##"), "Double '#' in error message: ${ex.message}")
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

        // Nested subdirectory includes resolve relative to the including file, not the root file
        val rootDecoyColors = """{"colors":{"editor.background":"#990000","editor.foreground":"#FFFFFF"}}"""
        val nestedColors = """{"colors":{"editor.background":"#102030","editor.foreground":"#E0E8F0"},"tokenColors":[{"scope":"comment","settings":{"foreground":"#607080"}}]}"""
        val sharedTokens = """{"tokenColors":[{"scope":"constant.numeric","settings":{"foreground":"#44DDAA"}}]}"""
        val subBase = """{"include":"./colors.json","tokenColors":[{"scope":"keyword","settings":{"foreground":"#FFAA33"}}]}"""
        val subMid = """{"include":"../shared/tokens.json","tokenColors":[{"scope":"string","settings":{"foreground":"#88EE66"}}]}"""
        val nestedRoot = """{"name":"Nested Theme","type":"dark","include":"base/base.json"}"""
        val requestedPaths = mutableListOf<String>()
        val nested = SyntaxSchemeImporters.importVsCodeJson(
            json = nestedRoot,
            includeResolver = { path ->
                requestedPaths += path
                when (path) {
                    "base/base.json" -> subBase
                    "base/colors.json" -> nestedColors
                    "colors.json", "./colors.json" -> rootDecoyColors
                    else -> null
                }
            },
        )
        assertEquals(listOf("base/base.json", "base/colors.json"), requestedPaths)
        assertEquals("#102030", nested.background.toHexRgb())
        assertEquals("#607080", nested.foregroundFor(SyntaxTokenKind.Comment).toHexRgb())
        assertEquals("#FFAA33", nested.foregroundFor(SyntaxTokenKind.Keyword).toHexRgb())

        // Parent-relative include across sibling subdirectories (`base/sub/mid.json` -> `../shared/tokens.json` -> `base/shared/tokens.json`)
        val siblingRoot = """{"name":"Sibling Theme","type":"dark","include":"base/sub/mid.json"}"""
        val siblingRequests = mutableListOf<String>()
        val sibling = SyntaxSchemeImporters.importVsCodeJson(
            json = siblingRoot,
            includeResolver = { path ->
                siblingRequests += path
                when (path) {
                    "base/sub/mid.json" -> subMid
                    "base/shared/tokens.json" -> sharedTokens
                    else -> null
                }
            },
        )
        assertEquals(listOf("base/sub/mid.json", "base/shared/tokens.json"), siblingRequests)
        assertEquals("#44DDAA", sibling.foregroundFor(SyntaxTokenKind.Number).toHexRgb())
        assertEquals("#88EE66", sibling.foregroundFor(SyntaxTokenKind.String).toHexRgb())

        // Missing nested `./colors.json` must fail instead of falling back to root `colors.json`
        assertFailsWith<SyntaxSchemeParseException> {
            SyntaxSchemeImporters.importVsCodeJson(
                json = nestedRoot,
                includeResolver = { path ->
                    when (path) {
                        "base/base.json" -> subBase
                        "colors.json", "./colors.json" -> rootDecoyColors
                        else -> null
                    }
                },
            )
        }

        // Nested escape (`base/base.json` -> `../../escape.json`) resolves to `../escape.json` so resolvers can reject it
        val escapeRequests = mutableListOf<String>()
        assertFailsWith<SyntaxSchemeParseException> {
            SyntaxSchemeImporters.importVsCodeJson(
                json = """{"name":"Escape","include":"base/base.json"}""",
                includeResolver = { path ->
                    escapeRequests += path
                    when (path) {
                        "base/base.json" -> """{"include":"../../escape.json"}"""
                        else -> null
                    }
                },
            )
        }
        assertEquals(listOf("base/base.json", "../escape.json"), escapeRequests)

        // Normalized cycle across subdirectory (`base/a.json` -> `../base/a.json`) stops cleanly
        val subCycleRequests = mutableListOf<String>()
        val subCyclic = SyntaxSchemeImporters.importVsCodeJson(
            json = """{"name":"SubCycle","include":"base/a.json"}""",
            includeResolver = { path ->
                subCycleRequests += path
                """{"include":"../base/a.json","colors":{"editor.background":"#222222","editor.foreground":"#EEEEEE"}}"""
            },
        )
        assertEquals(listOf("base/a.json"), subCycleRequests)
        assertEquals("#222222", subCyclic.background.toHexRgb())
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
        assertEquals(bundledGhLight.functionDeclaration, importedGhLight.functionDeclaration)
        assertEquals(bundledGhLight.styles, importedGhLight.styles)
    }

    @Test
    fun vsCodeTextMateCascadesFontStyleAndHonorsExactFunctionDeclaration() {
        val theme = """
            {
              "name": "Cascade Check",
              "type": "dark",
              "colors": {
                "editor.background": "#101214",
                "editor.foreground": "#E0E4EA"
              },
              "tokenColors": [
                {
                  "scope": "comment",
                  "settings": { "foreground": "#7A8490", "fontStyle": "bold" }
                },
                {
                  "scope": "comment.line",
                  "settings": { "fontStyle": "italic" }
                },
                {
                  "scope": "keyword",
                  "settings": { "foreground": "#FF7B72", "fontStyle": "bold italic" }
                },
                {
                  "scope": "keyword.control",
                  "settings": { "fontStyle": "" }
                },
                {
                  "scope": "string",
                  "settings": { "foreground": "#A5D6FF", "fontStyle": "\n  bold\t italic \r" }
                },
                {
                  "scope": "entity.name.function",
                  "settings": { "foreground": "#D2A8FF" }
                }
              ],
              "semanticTokenColors": {
                "function.declaration": { "foreground": "#79C0FF", "bold": true }
              }
            }
        """.trimIndent()

        val imported = SyntaxSchemeImporters.importVsCodeJson(theme)
        // comment.line fontStyle-only rule inherits #7A8490 from comment and replaces bold with italic
        val comment = imported.styleFor(SyntaxTokenKind.Comment)
        assertEquals("#7A8490", comment.foreground?.toHexRgb())
        assertTrue(comment.italic)
        assertFalse(comment.bold)

        // keyword.control with "fontStyle": "" inherits #FF7B72 and clears bold/italic
        val kw = imported.styleFor(SyntaxTokenKind.Keyword)
        assertEquals("#FF7B72", kw.foreground?.toHexRgb())
        assertFalse(kw.bold)
        assertFalse(kw.italic)

        // Multiline / whitespace fontStyle tokens parse identically to single-space tokens
        val strStyle = imported.styleFor(SyntaxTokenKind.String)
        assertEquals("#A5D6FF", strStyle.foreground?.toHexRgb())
        assertTrue(strStyle.bold)
        assertTrue(strStyle.italic)

        // Generic entity.name.function must not shadow semanticTokenColors["function.declaration"]
        assertEquals("#D2A8FF", imported.foregroundFor(SyntaxTokenKind.Function).toHexRgb())
        val fnDecl = imported.functionDeclaration
        assertNotNull(fnDecl)
        assertEquals("#79C0FF", fnDecl.foreground?.toHexRgb())
        assertTrue(fnDecl.bold)
    }

    @Test
    fun importAutoPassesIncludeResolverThrough() {
        val base = """
            {
              "type": "dark",
              "colors": { "editor.background": "#111111", "editor.foreground": "#EEEEEE" },
              "tokenColors": [{ "scope": "keyword", "settings": { "foreground": "#CC7832" } }]
            }
        """.trimIndent()
        val child = """
            {
              "name": "Auto Included",
              "include": "base.json",
              "tokenColors": [{ "scope": "string", "settings": { "foreground": "#6AAB73" } }]
            }
        """.trimIndent()
        val imported = SyntaxSchemeImporters.importAuto(
            content = child,
            fileName = "child.jsonc",
            includeResolver = { if (it == "base.json") base else null },
        )
        assertEquals("#CC7832", imported.foregroundFor(SyntaxTokenKind.Keyword).toHexRgb())
        assertEquals("#6AAB73", imported.foregroundFor(SyntaxTokenKind.String).toHexRgb())
    }

    @Test
    fun parsersScaleLinearlyOnLargeInputs() {
        // Warm up bundled scheme lazy initialization before measuring parser scaling
        assertTrue(SyntaxColorSchemes.intelliJ.isNotEmpty())
        assertTrue(SyntaxColorSchemes.gitHub.isNotEmpty())

        fun buildXml(count: Int): String = buildString {
            append("<scheme name=\"Large\" parent_scheme=\"Darcula\"><attributes>")
            repeat(count) { i ->
                append("<option name=\"OPT_$i\"><value><option name=\"FOREGROUND\" value=\"abcdef\"/></value></option>")
            }
            append("</attributes></scheme>")
        }
        val xmlN = buildXml(500)
        val xml4N = buildXml(2000)
        val xmlNStart = TimeSource.Monotonic.markNow()
        assertNotNull(SyntaxSchemeImporters.importIntelliJXml(xmlN))
        val tXmlN = xmlNStart.elapsedNow().toLong(DurationUnit.MILLISECONDS)

        val xml4NStart = TimeSource.Monotonic.markNow()
        assertNotNull(SyntaxSchemeImporters.importIntelliJXml(xml4N))
        val tXml4N = xml4NStart.elapsedNow().toLong(DurationUnit.MILLISECONDS)
        assertTrue(tXml4N < 500L, "4N XML import took ${tXml4N}ms")
        assertTrue(tXml4N <= maxOf(tXmlN * 10L, 150L), "XML scaling super-linear: N=${tXmlN}ms, 4N=${tXml4N}ms")

        fun buildJsonc(count: Int): String = buildString {
            append("{\n\"name\":\"LargeVS\",\n\"type\":\"dark\",\n\"colors\":{\"editor.background\":\"#000000\",\"editor.foreground\":\"#FFFFFF\"},\n\"tokenColors\":[\n")
            repeat(count) { i ->
                append("/* comment $i */ {\"scope\":\"meta.custom.$i\",\"settings\":{\"foreground\":\"#abcdef\"}},\n")
            }
            append("{\"scope\":\"keyword\",\"settings\":{\"foreground\":\"#ff7b72\"}}\n]}")
        }
        val jsonN = buildJsonc(500)
        val json4N = buildJsonc(2000)
        val jsonNStart = TimeSource.Monotonic.markNow()
        assertEquals("#FF7B72", SyntaxSchemeImporters.importVsCodeJson(jsonN).foregroundFor(SyntaxTokenKind.Keyword).toHexRgb())
        val tJsonN = jsonNStart.elapsedNow().toLong(DurationUnit.MILLISECONDS)

        val json4NStart = TimeSource.Monotonic.markNow()
        assertEquals("#FF7B72", SyntaxSchemeImporters.importVsCodeJson(json4N).foregroundFor(SyntaxTokenKind.Keyword).toHexRgb())
        val tJson4N = json4NStart.elapsedNow().toLong(DurationUnit.MILLISECONDS)
        assertTrue(tJson4N < 500L, "4N JSONC import took ${tJson4N}ms")
        assertTrue(tJson4N <= maxOf(tJsonN * 10L, 150L), "JSONC scaling super-linear: N=${tJsonN}ms, 4N=${tJson4N}ms")

        // Pathological stress inputs: 20,000 XML entities and 2,000 DOCTYPE comments
        val manyEntities = "<scheme name=\"" + "&amp;".repeat(20_000) + "\" parent_scheme=\"Darcula\"/>"
        val entStart = TimeSource.Monotonic.markNow()
        assertTrue(SyntaxSchemeImporters.importIntelliJXml(manyEntities).name.length == 20_000)
        val entMs = entStart.elapsedNow().toLong(DurationUnit.MILLISECONDS)
        assertTrue(entMs < 500L, "20k XML entities took ${entMs}ms")

        val manyDoctypeComments = buildString {
            append("<!DOCTYPE scheme [")
            repeat(2_000) { i -> append("<!-- comment $i -->") }
            append("]><scheme name=\"ManyComments\" parent_scheme=\"Darcula\"/>")
        }
        val docStart = TimeSource.Monotonic.markNow()
        assertEquals("#CC7832", SyntaxSchemeImporters.importIntelliJXml(manyDoctypeComments).foregroundFor(SyntaxTokenKind.Keyword).toHexRgb())
        val docMs = docStart.elapsedNow().toLong(DurationUnit.MILLISECONDS)
        assertTrue(docMs < 500L, "2k DOCTYPE comments took ${docMs}ms")
    }
}
