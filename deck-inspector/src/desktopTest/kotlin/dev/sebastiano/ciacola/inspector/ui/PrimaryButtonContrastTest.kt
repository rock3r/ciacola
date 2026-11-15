package dev.sebastiano.ciacola.inspector.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.sebastiano.ciacola.theme.contrastRatio
import dev.sebastiano.ciacola.ui.CiacolaJewelTheme
import dev.sebastiano.ciacola.ui.CiacolaPrimaryButton
import dev.sebastiano.ciacola.ui.ResolvedPrimaryButtonStyle
import dev.sebastiano.ciacola.ui.rememberCiacolaPrimaryButtonStyle
import dev.sebastiano.ciacola.ui.resolveCiacolaPrimaryButtonStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.styling.ButtonColors
import org.jetbrains.jewel.ui.component.styling.ButtonStyle
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.ui.theme.colorPalette
import org.jetbrains.jewel.ui.theme.defaultButtonStyle

@OptIn(ExperimentalTestApi::class)
class PrimaryButtonContrastTest {

    @Test
    fun primaryButtonGuaranteesWcagAaContrastInBothDarkAndLightJewelThemes() = runComposeUiTest {
        var darkResolved: ResolvedPrimaryButtonStyle? = null
        var lightResolved: ResolvedPrimaryButtonStyle? = null
        var lowContrastFallback: ResolvedPrimaryButtonStyle? = null
        var clicks = 0

        setContent {
            Box(Modifier.padding(12.dp)) {
                CiacolaJewelTheme(isDark = true) {
                    darkResolved = rememberCiacolaPrimaryButtonStyle()
                    val base = JewelTheme.defaultButtonStyle
                    val palette = JewelTheme.colorPalette
                    val washOut = ButtonStyle(
                        colors = ButtonColors(
                            background = SolidColor(Color(0xFF93C5FD)), // light washed blue (< 2:1 vs white)
                            backgroundDisabled = base.colors.backgroundDisabled,
                            backgroundFocused = SolidColor(Color(0xFF93C5FD)),
                            backgroundPressed = SolidColor(Color(0xFF93C5FD)),
                            backgroundHovered = SolidColor(Color(0xFF93C5FD)),
                            content = Color(0xFF222222),
                            contentDisabled = base.colors.contentDisabled,
                            contentFocused = Color(0xFF222222),
                            contentPressed = Color(0xFF222222),
                            contentHovered = Color(0xFF222222),
                            border = base.colors.border,
                            borderDisabled = base.colors.borderDisabled,
                            borderFocused = base.colors.borderFocused,
                            borderPressed = base.colors.borderPressed,
                            borderHovered = base.colors.borderHovered,
                        ),
                        metrics = base.metrics,
                        focusOutlineAlignment = base.focusOutlineAlignment,
                    )
                    lowContrastFallback = resolveCiacolaPrimaryButtonStyle(washOut, palette, isDark = true)
                    CiacolaPrimaryButton(
                        text = "Audit Deck",
                        leadingIcon = AllIconsKeys.Actions.Refresh,
                        onClick = { clicks++ },
                        modifier = Modifier.testTag("primary-btn-dark"),
                    )
                }
            }
        }
        waitForIdle()
        onNodeWithTag("primary-btn-dark").assertIsDisplayed().performClick()
        waitForIdle()
        assertEquals(1, clicks)

        setContent {
            Box(Modifier.padding(12.dp)) {
                CiacolaJewelTheme(isDark = false) {
                    lightResolved = rememberCiacolaPrimaryButtonStyle()
                    CiacolaPrimaryButton(
                        text = "Apply",
                        onClick = { clicks++ },
                        modifier = Modifier.testTag("primary-btn-light"),
                    )
                }
            }
        }
        waitForIdle()
        onNodeWithTag("primary-btn-light").assertIsDisplayed().performClick()
        waitForIdle()
        assertEquals(2, clicks)

        val dark = assertNotNull(darkResolved)
        val light = assertNotNull(lightResolved)
        val fallback = assertNotNull(lowContrastFallback)

        assertEquals(Color.White, dark.contentColor)
        assertTrue(
            dark.contrastRatio >= 4.5f && contrastRatio(dark.contentColor, dark.backgroundColor) >= 4.5f,
            "Expected >= 4.5:1 contrast in dark theme, got ${dark.contrastRatio}",
        )

        assertEquals(Color.White, light.contentColor)
        assertTrue(
            light.contrastRatio >= 4.5f && contrastRatio(light.contentColor, light.backgroundColor) >= 4.5f,
            "Expected >= 4.5:1 contrast in light theme, got ${light.contrastRatio}",
        )

        assertEquals(Color.White, fallback.contentColor)
        assertTrue(
            fallback.contrastRatio >= 4.5f,
            "Expected washed-out base style to resolve to >= 4.5:1 contrast, got ${fallback.contrastRatio}",
        )
    }
}
