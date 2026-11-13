package dev.bnorm.storyboard.easel

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.MaterialTheme
import androidx.compose.material.darkColors
import androidx.compose.material.lightColors
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import dev.bnorm.storyboard.Storyboard
import dev.bnorm.storyboard.easel.internal.WindowStateSerializer
import dev.bnorm.storyboard.easel.overlay.EaselOverlay
import dev.bnorm.storyboard.easel.overlay.OverlayNavigation
import java.awt.Rectangle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

@OptIn(ExperimentalTestApi::class)
class WindowSizingAndOverlayTest {

    @Test
    fun minimizedWindowStateIsNeverPersistedAndNormalizedOnLoad() {
        val minimizedState = WindowState(
            placement = WindowPlacement.Floating,
            isMinimized = true,
            position = WindowPosition(100.dp, 80.dp),
            size = DpSize(1280.dp, 720.dp),
        )
        val encoded = Json.encodeToString(WindowStateSerializer, minimizedState)
        assertTrue(encoded.contains("\"isMinimized\":false"), "Expected isMinimized=false on save, got $encoded")

        val staleMinimizedJson =
            """{"placement":"Floating","isMinimized":true,"position":{"x":100.0,"y":80.0},"size":{"width":1280.0,"height":720.dp}}"""
                .replace(".dp", "")
        val decoded = Json.decodeFromString(WindowStateSerializer, staleMinimizedJson)
        assertFalse(decoded.isMinimized, "Deserializing isMinimized=true must normalize to false")
        assertEquals(DpSize(1280.dp, 720.dp), decoded.size)
    }

    @Test
    fun scaleMenuClampsAgainstMaximumWindowBoundsPreservingAspectRatio() {
        val stageSize = DpSize(1920.dp, 1080.dp)
        val insets = DpSize(0.dp, 28.dp)
        val laptopMaxBounds = Rectangle(0, 25, 1512, 920)

        val scaled200 = clampScaledWindowSize(stageSize, 2.0f, insets, laptopMaxBounds)
        val scaled150 = clampScaledWindowSize(stageSize, 1.5f, insets, laptopMaxBounds)
        val scaled50 = clampScaledWindowSize(stageSize, 0.5f, insets, laptopMaxBounds)

        assertTrue(scaled200.width.value <= laptopMaxBounds.width, "200% width (${scaled200.width}) must fit laptop bounds")
        assertTrue(scaled200.height.value <= laptopMaxBounds.height, "200% height (${scaled200.height}) must fit laptop bounds")
        assertEquals(scaled200, scaled150, "Both 150% and 200% clamp cleanly to the laptop display max")

        val stageAspect = 1920f / 1080f
        val actualStageAspect = scaled150.width.value / (scaled150.height.value - insets.height.value)
        assertEquals(stageAspect, actualStageAspect, absoluteTolerance = 0.02f)

        // 50% (960x568) fits naturally without clamping.
        assertEquals(DpSize(960.dp, 568.dp), scaled50)

        // Window position clamped back inside maximumWindowBounds.
        val pos = clampWindowPositionToBounds(WindowPosition(900.dp, 600.dp), scaled150, laptopMaxBounds) as WindowPosition.Absolute
        assertTrue(pos.x.value + scaled150.width.value <= laptopMaxBounds.x + laptopMaxBounds.width + 0.5f)
        assertTrue(pos.y.value + scaled150.height.value <= laptopMaxBounds.y + laptopMaxBounds.height + 0.5f)
    }

    @Test
    fun overlayNavigationRendersCrispBorderedPillInDarkAndLightThemes() {
        val sb = Storyboard.build(title = "NavTest") {
            scene(frames = listOf(0, 1)) {}
        }
        for (isLight in listOf(false, true)) {
            runComposeUiTest {
                setContent {
                    val colors = if (isLight) {
                        lightColors(surface = Color(0xFFF8F8F8), onSurface = Color(0xFF242124))
                    } else {
                        darkColors(surface = Color(0xFF171B24), onSurface = Color(0xFFF4F1EA))
                    }
                    MaterialTheme(colors = colors) {
                        val animatic = rememberAnimatic(storyboard = { sb })
                        Box(Modifier.size(640.dp, 360.dp)) {
                            EaselOverlay(
                                overlay = { OverlayNavigation(animatic) },
                                modifier = Modifier.fillMaxSize().testTag("easel-overlay-host"),
                            ) {
                                Easel(animatic)
                            }
                        }
                    }
                }
                waitForIdle()
                onNodeWithTag("easel-overlay-host").performMouseInput {
                    moveTo(Offset(100f, 100f))
                }
                waitForIdle()
                onNodeWithTag("easel-overlay-navigation", useUnmergedTree = true).assertIsDisplayed()
                onNodeWithTag("easel-overlay-nav-previous", useUnmergedTree = true).assertExists()
                onNodeWithTag("easel-overlay-nav-next", useUnmergedTree = true).assertExists()
            }
        }
    }
}
