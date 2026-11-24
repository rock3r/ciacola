package dev.sebastiano.ciacola.inspector.design

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.sebastiano.ciacola.document.DeckDocument
import dev.sebastiano.ciacola.document.DeckDocumentStore
import dev.sebastiano.ciacola.document.DeckLoadState
import dev.sebastiano.ciacola.document.DeckThemeSpec
import dev.sebastiano.ciacola.document.SlideSpec
import dev.sebastiano.ciacola.document.SyncState
import dev.sebastiano.ciacola.inspector.document.DocumentThemeResolver
import dev.sebastiano.ciacola.syntax.SyntaxTokenKind
import dev.sebastiano.ciacola.syntax.theme.SyntaxColorSchemes
import dev.sebastiano.ciacola.ui.CiacolaJewelTheme
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image as SkiaImage
import org.jetbrains.skia.ImageInfo

@OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
class CodeSchemeInspectorTest {

    private fun testDoc(
        colors: Map<String, String> = mapOf(
            "accent" to "#FF6B5E",
            "codeBackground" to "#123456",
            "codeForeground" to "#ABCDEF",
        ),
    ) = DeckDocument(
        id = "code-scheme-inspector",
        title = "Code Scheme Inspector",
        defaultTheme = "dark",
        themes = mapOf(
            "dark" to DeckThemeSpec(preset = "stageDark", colors = colors),
            "light" to DeckThemeSpec(preset = "keynoteLight", colors = colors),
        ),
        slides = listOf(SlideSpec(id = "s1")),
    )

    @Test
    fun selectBundledSchemesUpdatesStoreStripsStaleOverridesAndSupportsUndoRedoReset() {
        val cleanController = DocumentThemeInspectorController(
            DeckDocumentStore(testDoc(colors = mapOf("accent" to "#FF6B5E")), initialLoad = DeckLoadState.Loaded),
        )
        assertFalse(cleanController.isCodeSchemeModified())

        val store = DeckDocumentStore(testDoc(), initialLoad = DeckLoadState.Loaded)
        val controller = DocumentThemeInspectorController(store)

        assertTrue(controller.isCodeSchemeModified())
        assertEquals(SyntaxColorSchemes.IntelliJIslandsDark.id, controller.activeCodeScheme().id)

        assertTrue(controller.selectBundledCodeScheme("github-dark"))
        assertTrue(controller.isCodeSchemeModified())
        assertEquals("github-dark", controller.activeCodeScheme().id)
        val darkSpec = store.document.themes["dark"]!!
        assertEquals("github-dark", darkSpec.codeScheme)
        assertNull(darkSpec.customCodeScheme)
        assertFalse("codeBackground" in darkSpec.colors)
        assertFalse("codeForeground" in darkSpec.colors)
        assertEquals("#FF6B5E", darkSpec.colors["accent"])

        assertTrue(store.canUndo)
        store.undo()
        assertTrue(controller.isCodeSchemeModified())
        assertEquals("#123456", store.document.themes["dark"]?.colors?.get("codeBackground"))

        store.redo()
        assertEquals("github-dark", controller.activeCodeScheme().id)

        assertTrue(controller.resetCodeScheme())
        assertFalse(controller.isCodeSchemeModified())
        assertNull(store.document.themes["dark"]?.codeScheme)
        assertEquals(SyntaxColorSchemes.IntelliJIslandsDark.id, controller.activeCodeScheme().id)
    }

    @Test
    fun fileImportEnforcesConfinementBoundedReadsStrictUtf8AndSafeErrors() {
        val store = DeckDocumentStore(testDoc(), initialLoad = DeckLoadState.Loaded)
        val controller = DocumentThemeInspectorController(store)
        val tempDir = Files.createTempDirectory("ciacola-scheme-security")
        try {
            val themesDir = tempDir.resolve("themes").also { Files.createDirectory(it) }
            val outsideSecret = tempDir.resolve("secret.json").apply {
                Files.writeString(
                    this,
                    """{"name":"Secret","type":"dark","colors":{"editor.background":"#111111","editor.foreground":"#EEEEEE"}}""",
                )
            }

            // 1. Relative VS Code "include" resolution + .jsonc import
            val baseFile = themesDir.resolve("base-palette.jsonc")
            Files.writeString(
                baseFile,
                """
                {
                  // base JSONC theme
                  "colors": { "editor.background": "#1A1B26", "editor.foreground": "#C0CAF5", },
                  "tokenColors": [
                    { "scope": "keyword", "settings": { "foreground": "#BB9AF7" } },
                  ],
                }
                """.trimIndent(),
            )
            val childFile = themesDir.resolve("tokyo-child.jsonc")
            Files.writeString(
                childFile,
                """
                {
                  "name": "Tokyo Child",
                  "type": "dark",
                  "include": "./base-palette.jsonc",
                  "tokenColors": [
                    { "scope": "string", "settings": { "foreground": "#9ECE6A" } }
                  ]
                }
                """.trimIndent(),
            )
            assertTrue(controller.importCodeSchemeFromFile(childFile))
            assertEquals("Imported 'Tokyo Child'", controller.lastCodeSchemeStatus)
            val importedScheme = controller.activeCodeScheme()
            assertEquals("#1A1B26", importedScheme.background.toHexRgb())
            assertEquals("#BB9AF7", importedScheme.foregroundFor(SyntaxTokenKind.Keyword).toHexRgb())
            assertEquals("#9ECE6A", importedScheme.foregroundFor(SyntaxTokenKind.String).toHexRgb())

            // 2. Path-traversal include "../secret.json" rejected without leaking temp path
            val revBefore = store.revision
            val traversalFile = themesDir.resolve("traversal.json").apply {
                Files.writeString(this, """{"name":"Bad","include":"../secret.json"}""")
            }
            assertFalse(controller.importCodeSchemeFromFile(traversalFile))
            assertEquals(revBefore, store.revision)
            val traversalStatus = assertNotNull(controller.lastCodeSchemeStatus)
            assertFalse(traversalStatus.contains(tempDir.toString()), "Status must not leak path: $traversalStatus")

            // 3. Symlink escape inside themesDir pointing outside rejected
            val linkPath = themesDir.resolve("link-escape.json")
            val createdSymlink = runCatching { Files.createSymbolicLink(linkPath, outsideSecret) }.isSuccess
            if (createdSymlink) {
                val symlinkIncludeFile = themesDir.resolve("via-symlink.json").apply {
                    Files.writeString(this, """{"name":"Via Symlink","include":"./link-escape.json"}""")
                }
                assertFalse(controller.importCodeSchemeFromFile(symlinkIncludeFile))
                assertEquals(revBefore, store.revision)
            }

            // 4. Directory and missing-file safe errors (no full path disclosure)
            assertFalse(controller.importCodeSchemeFromFile(themesDir))
            val dirStatus = assertNotNull(controller.lastCodeSchemeStatus)
            assertTrue(dirStatus.contains("not a regular file"), dirStatus)
            assertFalse(dirStatus.contains(tempDir.toString()), dirStatus)

            val missingFile = themesDir.resolve("missing-theme.icls")
            assertFalse(controller.importCodeSchemeFromFile(missingFile))
            val missingStatus = assertNotNull(controller.lastCodeSchemeStatus)
            assertTrue(missingStatus.contains("file not found"), missingStatus)
            assertFalse(missingStatus.contains(tempDir.toString()), missingStatus)

            // 5. Invalid UTF-8 bytes rejected cleanly
            val badUtf8 = themesDir.resolve("bad-utf8.json")
            Files.write(badUtf8, byteArrayOf(0x7B, 0xFF.toByte(), 0xFE.toByte(), 0x7D))
            assertFalse(controller.importCodeSchemeFromFile(badUtf8))
            val utf8Status = assertNotNull(controller.lastCodeSchemeStatus)
            assertTrue(utf8Status.contains("invalid UTF-8"), utf8Status)
            assertFalse(utf8Status.contains(tempDir.toString()), utf8Status)

            // 6. Oversized > 4 MB file rejected cleanly
            val hugeFile = themesDir.resolve("huge.json")
            Files.write(hugeFile, ByteArray(4 * 1024 * 1024 + 8) { ' '.code.toByte() })
            assertFalse(controller.importCodeSchemeFromFile(hugeFile))
            val hugeStatus = assertNotNull(controller.lastCodeSchemeStatus)
            assertTrue(hugeStatus.contains("exceeds 4 MB limit"), hugeStatus)
            assertFalse(hugeStatus.contains(tempDir.toString()), hugeStatus)
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun statusClearsAcrossNavigationCodeOverridesStripAndDefaultSchemeNormalizes() {
        val store = DeckDocumentStore(
            testDoc(
                colors = mapOf(
                    "accent" to "#FF6B5E",
                    "codeBackground" to "#112233",
                    "code.keyword" to "#445566",
                ),
            ),
            initialLoad = DeckLoadState.Loaded,
        )
        val controller = DocumentThemeInspectorController(store)

        // Pre-existing code.* override marks code scheme as modified
        assertTrue(controller.isCodeSchemeModified())

        // Reset strips both codeBackground/codeForeground and code.* overrides
        assertTrue(controller.resetCodeScheme())
        assertFalse(controller.isCodeSchemeModified())
        assertFalse("code.keyword" in store.document.themes["dark"]!!.colors)
        assertFalse("codeBackground" in store.document.themes["dark"]!!.colors)
        assertEquals("#FF6B5E", store.document.themes["dark"]!!.colors["accent"])

        // Selecting a non-default scheme then picking the preset's own default normalizes codeScheme = null
        assertTrue(controller.selectBundledCodeScheme("github-dark"))
        assertTrue(controller.isCodeSchemeModified())
        assertEquals("github-dark", store.document.themes["dark"]?.codeScheme)

        assertTrue(controller.selectBundledCodeScheme("intellij-islands-dark"))
        assertNull(store.document.themes["dark"]?.codeScheme)
        assertFalse(controller.isCodeSchemeModified())

        // Import status clears on selectTheme, setThemePreset, performUndo, performRedo
        assertTrue(
            controller.importCodeSchemeFromText(
                """{"name":"Temp","type":"dark","colors":{"editor.background":"#101010","editor.foreground":"#EEEEEE"}}""",
                fileName = "temp.json",
            ),
        )
        assertNotNull(controller.lastCodeSchemeStatus)
        controller.selectTheme("light")
        assertNull(controller.lastCodeSchemeStatus)

        controller.selectTheme("dark")
        assertTrue(
            controller.importCodeSchemeFromText(
                """{"name":"Temp2","type":"dark","colors":{"editor.background":"#101010","editor.foreground":"#EEEEEE"}}""",
                fileName = "temp2.json",
            ),
        )
        assertNotNull(controller.lastCodeSchemeStatus)
        assertTrue(controller.setThemePreset("keynoteLight"))
        assertNull(controller.lastCodeSchemeStatus)

        assertTrue(
            controller.importCodeSchemeFromText(
                """{"name":"Temp3","type":"dark","colors":{"editor.background":"#101010","editor.foreground":"#EEEEEE"}}""",
                fileName = "temp3.json",
            ),
        )
        assertNotNull(controller.lastCodeSchemeStatus)
        controller.performUndo()
        assertNull(controller.lastCodeSchemeStatus)

        assertTrue(
            controller.importCodeSchemeFromText(
                """{"name":"Temp4","type":"dark","colors":{"editor.background":"#101010","editor.foreground":"#EEEEEE"}}""",
                fileName = "temp4.json",
            ),
        )
        controller.performUndo()
        controller.performRedo()
        assertNull(controller.lastCodeSchemeStatus)

        // Verify DocumentThemeResolver bidirectional code.function <-> code.functionCall alias sync
        val specFn = DeckThemeSpec(preset = "stageDark", colors = mapOf("code.function" to "#11AAFF"))
        val mapFn = DocumentThemeResolver.resolveThemeColorMap(specFn)
        assertEquals(Color(0xFF11AAFF), mapFn["code.function"])
        assertEquals(Color(0xFF11AAFF), mapFn["code.functionCall"])

        val specFnCall = DeckThemeSpec(preset = "stageDark", colors = mapOf("code.functionCall" to "#FFAA11"))
        val mapFnCall = DocumentThemeResolver.resolveThemeColorMap(specFnCall)
        assertEquals(Color(0xFFFFAA11), mapFnCall["code.functionCall"])
        assertEquals(Color(0xFFFFAA11), mapFnCall["code.function"])
    }

    @Test
    fun readOnlyStoreDisablesSchemeSelectorImportAndResetInUi() = runComposeUiTest {
        val doc = testDoc(colors = mapOf("accent" to "#FF6B5E")).let { base ->
            base.copy(
                themes = base.themes + ("dark" to DeckThemeSpec(preset = "stageDark", codeScheme = "github-dark")),
            )
        }
        val store = DeckDocumentStore(
            doc,
            initialLoad = DeckLoadState.Loaded,
            initialSync = SyncState.ReadOnly("Read-only fixture"),
        )
        val controller = DocumentThemeInspectorController(store)

        setContent {
            CiacolaJewelTheme(isDark = true) {
                Box(Modifier.size(380.dp, 820.dp)) {
                    DocumentThemeInspectorUi(
                        controller = controller,
                        onPickCodeSchemeFile = { null },
                    )
                }
            }
        }
        waitForIdle()

        onNodeWithTag("ciacola-doc-theme-code-scheme-selector", useUnmergedTree = true).assertIsNotEnabled()
        onNodeWithTag("ciacola-doc-theme-code-scheme-import", useUnmergedTree = true).assertIsNotEnabled()
        onNodeWithTag("ciacola-doc-theme-code-scheme-reset", useUnmergedTree = true).performClick()
        waitForIdle()
        assertEquals("github-dark", store.document.themes["dark"]?.codeScheme)
    }

    @Test
    fun importIntelliJXmlAndVsCodeJsonEmbedCustomSchemeAndRejectInvalidInput() {
        val store = DeckDocumentStore(testDoc(), initialLoad = DeckLoadState.Loaded)
        val controller = DocumentThemeInspectorController(store)

        val iclsXml = """
            <scheme name="My Studio Dark" version="142" parent_scheme="Darcula">
              <colors>
                <option name="CARET_ROW_COLOR" value="22252A"/>
              </colors>
              <attributes>
                <option name="TEXT">
                  <value>
                    <option name="FOREGROUND" value="E5E9F0"/>
                    <option name="BACKGROUND" value="1B1E24"/>
                  </value>
                </option>
                <option name="DEFAULT_KEYWORD">
                  <value>
                    <option name="FOREGROUND" value="81A1C1"/>
                    <option name="FONT_TYPE" value="1"/>
                  </value>
                </option>
              </attributes>
            </scheme>
        """.trimIndent()

        val tempDir = Files.createTempDirectory("ciacola-scheme-import-test")
        try {
            val iclsFile = tempDir.resolve("my-studio-dark.icls")
            Files.writeString(iclsFile, iclsXml)

            assertTrue(controller.importCodeSchemeFromFile(iclsFile))
            assertEquals("Imported 'My Studio Dark'", controller.lastCodeSchemeStatus)
            val customSpec = assertNotNull(store.document.themes["dark"]?.customCodeScheme)
            assertEquals("My Studio Dark", customSpec.name)
            assertEquals("#1B1E24", customSpec.background)
            assertTrue(controller.activeCodeScheme().styleFor(SyntaxTokenKind.Keyword).bold)
            assertFalse("codeBackground" in store.document.themes["dark"]!!.colors)

            val vsCodeJson = """
                {
                  // comment allowed in JSONC
                  "name": "Nord Breeze",
                  "type": "dark",
                  "colors": {
                    "editor.background": "#2E3440",
                    "editor.foreground": "#D8DEE9"
                  },
                  "tokenColors": [
                    {
                      "scope": ["keyword", "storage.type"],
                      "settings": { "foreground": "#81A1C1" }
                    },
                    {
                      "scope": "comment",
                      "settings": { "foreground": "#616E88", "fontStyle": "italic" }
                    }
                  ]
                }
            """.trimIndent()
            val vsCodeFile = tempDir.resolve("nord-breeze.json")
            Files.writeString(vsCodeFile, vsCodeJson)

            assertTrue(controller.importCodeSchemeFromFile(vsCodeFile))
            assertEquals("Imported 'Nord Breeze'", controller.lastCodeSchemeStatus)
            assertEquals("#2E3440", store.document.themes["dark"]?.customCodeScheme?.background)
            assertTrue(controller.activeCodeScheme().styleFor(SyntaxTokenKind.Comment).italic)

            // Invalid scheme input sets clear diagnostic without crashing or mutating store
            val revBefore = store.revision
            val badFile = tempDir.resolve("broken.xml")
            Files.writeString(badFile, "<not-a-scheme>")
            assertFalse(controller.importCodeSchemeFromFile(badFile))
            assertEquals(revBefore, store.revision)
            val errorStatus = assertNotNull(controller.lastCodeSchemeStatus)
            assertFalse(errorStatus.startsWith("Imported "))
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun themeInspectorUiShowsCodeHighlightingSectionPickerImportPreviewAndReset() = runComposeUiTest {
        val store = DeckDocumentStore(testDoc(colors = mapOf("accent" to "#FF6B5E")), initialLoad = DeckLoadState.Loaded)
        val controller = DocumentThemeInspectorController(store)
        val tempDir = Files.createTempDirectory("ciacola-ui-scheme-import")
        val importedFile = tempDir.resolve("custom-ocean.json").apply {
            Files.writeString(
                this,
                """
                {
                  "name": "Oceanic Preview",
                  "type": "dark",
                  "colors": { "editor.background": "#1B2B34", "editor.foreground": "#CDD3DE" },
                  "tokenColors": [
                    { "scope": "keyword", "settings": { "foreground": "#C594C5" } }
                  ]
                }
                """.trimIndent(),
            )
        }
        try {
            setContent {
                CiacolaJewelTheme(isDark = true) {
                    Box(Modifier.size(380.dp, 820.dp)) {
                        DocumentThemeInspectorUi(
                            controller = controller,
                            onPickCodeSchemeFile = { importedFile },
                        )
                    }
                }
            }
            waitForIdle()

            onNodeWithTag("ciacola-doc-theme-code-scheme-section", useUnmergedTree = true).assertExists()
            onNodeWithTag("ciacola-doc-theme-code-scheme-selector", useUnmergedTree = true).assertExists()
            onNodeWithText("Islands Dark", useUnmergedTree = true).assertExists()
            onNodeWithTag("ciacola-doc-theme-code-scheme-preview", useUnmergedTree = true).assertExists()
            onNodeWithTag("ciacola-doc-theme-code-scheme-import", useUnmergedTree = true).performClick()
            waitForIdle()

            onNodeWithText("Oceanic Preview (Imported)", useUnmergedTree = true).assertExists()
            onNodeWithTag("ciacola-doc-theme-code-scheme-status", useUnmergedTree = true)
                .assertTextContains("Imported 'Oceanic Preview'", substring = true)

            // Open selector dropdown and pick GitHub Dark
            onNodeWithTag("ciacola-doc-theme-code-scheme-selector", useUnmergedTree = true).performClick()
            waitForIdle()
            onNodeWithText("GitHub Dark", useUnmergedTree = true).performClick()
            waitForIdle()
            assertEquals("github-dark", store.document.themes["dark"]?.codeScheme)
            assertNull(store.document.themes["dark"]?.customCodeScheme)

            // Click reset dot to restore preset default
            onNodeWithTag("ciacola-doc-theme-code-scheme-reset", useUnmergedTree = true).performClick()
            waitForIdle()
            assertNull(store.document.themes["dark"]?.codeScheme)
            assertEquals("intellij-islands-dark", controller.activeCodeScheme().id)
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun renderThemeSheetDarkAndLightScreenshotsWhenRequested() {
        val rawDir = System.getProperty("ciacola.screenshots.dir")
            ?: System.getenv("CIACOLA_SCREENSHOTS_DIR")
            ?: return
        val outDir = File(rawDir)
        outDir.mkdirs()

        val darkDoc = testDoc(
            colors = mapOf(
                "accent" to "#FF6B5E",
                "background" to "#0B0D12",
                "surface" to "#171B24",
                "elevatedSurface" to "#202634",
                "foreground" to "#F4F1EA",
            ),
        )
        val lightDoc = DeckDocument(
            id = "code-scheme-inspector-light",
            title = "Code Scheme Inspector",
            defaultTheme = "light",
            themes = mapOf(
                "dark" to DeckThemeSpec(preset = "stageDark"),
                "light" to DeckThemeSpec(preset = "keynoteLight"),
            ),
            slides = listOf(SlideSpec(id = "s1")),
        )
        renderThemeSheetPng(
            doc = darkDoc,
            themeName = "dark",
            isDarkJewel = true,
            outFile = File(outDir, "theme-sheet-dark.png"),
        )
        renderThemeSheetPng(
            doc = lightDoc,
            themeName = "light",
            isDarkJewel = false,
            outFile = File(outDir, "theme-sheet-light.png"),
        )
    }

    private fun renderThemeSheetPng(
        doc: DeckDocument,
        themeName: String,
        isDarkJewel: Boolean,
        outFile: File,
    ) {
        val store = DeckDocumentStore(doc, initialLoad = DeckLoadState.Loaded)
        val controller = DocumentThemeInspectorController(store, initialThemeName = themeName)
        val scene = ImageComposeScene(width = 380, height = 820, density = Density(1f)) {
            CiacolaJewelTheme(isDark = isDarkJewel) {
                Box(Modifier.size(380.dp, 820.dp)) {
                    DocumentThemeInspectorUi(
                        controller = controller,
                        onPickCodeSchemeFile = { null },
                    )
                }
            }
        }
        try {
            scene.render(0L)
            val skiaImg = scene.render(16_000_000L)
            val bmp = Bitmap()
            bmp.allocPixels(ImageInfo(380, 820, ColorType.RGBA_8888, ColorAlphaType.UNPREMUL))
            assertTrue(skiaImg.readPixels(bmp, 0, 0))
            val rgba = bmp.readPixels(bmp.imageInfo, 380 * 4, 0, 0)!!
            val info = ImageInfo(380, 820, ColorType.RGBA_8888, ColorAlphaType.UNPREMUL)
            val png = SkiaImage.makeRaster(info, rgba, 380 * 4).encodeToData(EncodedImageFormat.PNG)!!
            outFile.writeBytes(png.bytes)
        } finally {
            scene.close()
        }
    }
}
