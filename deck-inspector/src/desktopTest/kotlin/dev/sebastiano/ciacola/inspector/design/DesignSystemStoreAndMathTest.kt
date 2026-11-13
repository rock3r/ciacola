package dev.sebastiano.ciacola.inspector.design

import androidx.compose.ui.graphics.Color
import dev.sebastiano.ciacola.theme.DeckThemePreset
import dev.sebastiano.ciacola.theme.DeckThemes
import dev.sebastiano.ciacola.theme.TextStyleTokenOverride
import java.nio.file.Files
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesignSystemStoreAndMathTest {
    @Test
    fun colorConversionsAndHexParsingRoundtripAccurately() {
        val sample = Color(0xFFFF6B5EL)
        assertEquals(0xFFFF6B5EL, sample.toArgbLong())
        assertEquals("#FF6B5E", sample.toHexString())
        assertEquals("#FFFF6B5E", sample.toHexString(includeAlpha = true))
        assertEquals("0xFFFF6B5EL", sample.toKotlinHexLiteral())

        assertEquals(sample, parseHexColorOrNull("#ff6b5e"))
        assertEquals(sample, parseHexColorOrNull("FF6B5E"))
        assertEquals(Color(0x80FF6B5EL), parseHexColorOrNull("#80FF6B5E"))
        assertNull(parseHexColorOrNull("#12345"))
        assertNull(parseHexColorOrNull("not-hex"))

        val hsv = sample.toHsv()
        val fromHsv = hsvToColor(hsv.hue, hsv.saturation, hsv.value, hsv.alpha)
        assertEquals(sample.red, fromHsv.red, 0.01f)
        assertEquals(sample.green, fromHsv.green, 0.01f)
        assertEquals(sample.blue, fromHsv.blue, 0.01f)

        val hsl = sample.toHsl()
        val fromHsl = hslToColor(hsl.hue, hsl.saturation, hsl.lightness, hsl.alpha)
        assertEquals(sample.red, fromHsl.red, 0.01f)
        assertEquals(sample.green, fromHsl.green, 0.01f)
        assertEquals(sample.blue, fromHsl.blue, 0.01f)
    }

    @Test
    fun fixColorForWcagAaNudgesLightnessToAtLeast455ForDarkAndLightThemes() {
        val darkBg = DeckThemes.StageDarkColors.background
        val darkSurface = DeckThemes.StageDarkColors.surface
        val lowContrastDark = Color(0xFF3E4451)
        assertTrue(worstWcagContrastRatio(lowContrastDark, darkBg, darkSurface) < 4.5f)

        val fixedDark = fixColorForWcagAa(lowContrastDark, darkBg, darkSurface)
        assertTrue(
            worstWcagContrastRatio(fixedDark, darkBg, darkSurface) >= 4.55f,
            "Expected fixed dark token to reach >= 4.55:1, got ${worstWcagContrastRatio(fixedDark, darkBg, darkSurface)}",
        )

        val lightBg = DeckThemes.KeynoteLightColors.background
        val lightSurface = DeckThemes.KeynoteLightColors.surface
        val lowContrastLight = Color(0xFFD99B16)
        assertTrue(worstWcagContrastRatio(lowContrastLight, lightBg, lightSurface) < 4.5f)

        val fixedLight = fixColorForWcagAa(lowContrastLight, lightBg, lightSurface)
        assertTrue(
            worstWcagContrastRatio(fixedLight, lightBg, lightSurface) >= 4.55f,
            "Expected fixed light token to reach >= 4.55:1, got ${worstWcagContrastRatio(fixedLight, lightBg, lightSurface)}",
        )

        // Already compliant colors are returned untouched.
        val compliant = DeckThemes.StageDarkColors.foreground
        assertEquals(compliant, fixColorForWcagAa(compliant, darkBg, darkSurface))
    }

    @Test
    fun perPresetPersistenceAndReloadIsolatesDarkAndLightModes() {
        val tempDir = Files.createTempDirectory("ciacola-design-store-presets")
        val store = DesignSystemStore(tempDir)

        store.updateColors(DeckThemePreset.StageDark) { it.copy(accentArgb = 0xFF00D4FFL) }
        store.updateStyles(DeckThemePreset.KeynoteLight) { it.copy(slidePaddingDp = 88f) }

        assertEquals(Color(0xFF00D4FFL), store.resolveTheme(DeckThemePreset.StageDark).colors.accent)
        assertEquals(DeckThemes.KeynoteLightColors.accent, store.resolveTheme(DeckThemePreset.KeynoteLight).colors.accent)
        assertEquals(88f, store.resolveTheme(DeckThemePreset.KeynoteLight).styles.slidePadding.value)
        assertEquals(64f, store.resolveTheme(DeckThemePreset.StageDark).styles.slidePadding.value)

        // Fresh store loads persisted file cleanly.
        val reloaded = DesignSystemStore(tempDir)
        assertEquals(0xFF00D4FFL, reloaded.overridesFor(DeckThemePreset.StageDark).colors.accentArgb)
        assertEquals(88f, reloaded.overridesFor(DeckThemePreset.KeynoteLight).styles.slidePaddingDp)

        // External disk edit (even same byte length) is picked up by reloadFromDiskIfChanged().
        val updatedText = reloaded.overridesFile.readText().replace("88.0", "92.0")
        reloaded.overridesFile.writeText(updatedText)
        assertTrue(reloaded.reloadFromDiskIfChanged())
        assertEquals(92f, reloaded.overridesFor(DeckThemePreset.KeynoteLight).styles.slidePaddingDp)
    }

    @Test
    fun corruptJsonCreatesBackupAndPreservesSafeDefaults() {
        val tempDir = Files.createTempDirectory("ciacola-design-corrupt")
        val target = tempDir.resolve("design-system.json")
        target.writeText("{ invalid json !!!")

        val store = DesignSystemStore(tempDir)
        assertTrue(store.corruptBackupFile.exists(), "Expected design-system.corrupt.json backup")
        assertFalse(store.data().hasOverrides)
    }

    @Test
    fun gestureCoalescedUndoRedoAnd64StepCapWorkDeterministically() {
        val tempDir = Files.createTempDirectory("ciacola-design-undo")
        var now = 1_000L
        val store = DesignSystemStore(tempDir, clockMillis = { now })

        // Continuous drag of 80 steps inside one edit gesture produces ONE undo entry.
        store.beginEditGesture()
        for (i in 1..80) {
            now += 16L
            val argb = (0xFF000000L or ((i * 2).toLong() shl 16) or 0x004488L)
            store.updateColors(DeckThemePreset.StageDark, coalesceKey = "color:accent", coalesce = true) {
                it.copy(accentArgb = argb)
            }
        }
        store.endEditGesture()

        assertTrue(store.canUndo)
        assertFalse(store.canRedo)
        val afterDrag = store.overridesFor(DeckThemePreset.StageDark).colors.accentArgb
        assertNotNull(afterDrag)

        assertTrue(store.undo())
        assertNull(store.overridesFor(DeckThemePreset.StageDark).colors.accentArgb)
        assertFalse(store.canUndo)
        assertTrue(store.canRedo)

        assertTrue(store.redo())
        assertEquals(afterDrag, store.overridesFor(DeckThemePreset.StageDark).colors.accentArgb)

        // Exceeding 64-step history drops the oldest snapshot cleanly.
        for (step in 1..70) {
            now += 1_000L
            store.updateMotion(DeckThemePreset.StageDark) { it.copy(fadeMoveDurationMs = 200 + step) }
        }
        var undoCount = 0
        while (store.undo()) {
            undoCount++
        }
        assertEquals(DesignSystemStore.MAX_UNDO_HISTORY, undoCount)
    }

    @Test
    fun granularResetsAndDeterministicKotlinSnippetExport() {
        val tempDir = Files.createTempDirectory("ciacola-design-export")
        val store = DesignSystemStore(tempDir)

        store.updateColors(DeckThemePreset.StageDark) { it.copy(accentArgb = 0xFF22CC88L, spotlightDimAlpha = 0.35f) }
        store.updateTypography(DeckThemePreset.StageDark) {
            it.copy(globalFontScale = 1.15f, title = TextStyleTokenOverride(fontSizeSp = 80f, fontWeight = 700))
        }
        store.updateStyles(DeckThemePreset.StageDark) { it.copy(slidePaddingDp = 72f) }
        store.updateMotion(DeckThemePreset.StageDark) { it.copy(fadeMoveDurationMs = 400, popDampingRatio = 0.72f) }

        val snippet1 = store.exportKotlinSnippet(DeckThemePreset.StageDark)
        val snippet2 = store.exportKotlinSnippet(DeckThemePreset.StageDark)
        assertEquals(snippet1, snippet2)
        assertTrue(snippet1.contains("DesignSystemOverrides("))
        assertTrue(snippet1.contains("accentArgb = 0xFF22CC88L"))
        assertTrue(snippet1.contains("globalFontScale = 1.15f"))
        assertTrue(snippet1.contains("slidePaddingDp = 72.0f"))
        assertTrue(snippet1.contains("fadeMoveDurationMs = 400"))
        assertTrue(snippet1.contains("DeckThemes.stageDark().withOverrides(stageDarkOverrides)"))

        // Granular resets preserve sibling sections.
        store.resetColors(DeckThemePreset.StageDark)
        assertNull(store.overridesFor(DeckThemePreset.StageDark).colors.accentArgb)
        assertNotNull(store.overridesFor(DeckThemePreset.StageDark).typography.globalFontScale)
        assertNotNull(store.overridesFor(DeckThemePreset.StageDark).styles.slidePaddingDp)
        assertNotNull(store.overridesFor(DeckThemePreset.StageDark).motion.fadeMoveDurationMs)

        store.resetTypography(DeckThemePreset.StageDark)
        store.resetStyles(DeckThemePreset.StageDark)
        store.resetMotion(DeckThemePreset.StageDark)
        assertFalse(store.overridesFor(DeckThemePreset.StageDark).hasOverrides)

        val emptySnippet = store.exportKotlinSnippet(DeckThemePreset.KeynoteLight)
        assertTrue(emptySnippet.contains("DeckThemes.keynoteLight()"))
    }
}
