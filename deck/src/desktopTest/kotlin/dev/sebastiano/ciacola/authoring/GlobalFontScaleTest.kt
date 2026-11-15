package dev.sebastiano.ciacola.authoring

import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.sp
import dev.bnorm.storyboard.Kotlin
import dev.sebastiano.ciacola.DeckMagicText
import dev.sebastiano.ciacola.effects.DeckEditableText
import dev.sebastiano.ciacola.effects.DeckWarpCodeCard
import dev.sebastiano.ciacola.theme.DeckThemes
import dev.sebastiano.ciacola.theme.DesignSystemOverrides
import dev.sebastiano.ciacola.theme.LocalDeckTheme
import dev.sebastiano.ciacola.theme.TypographyTokenOverrides
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class GlobalFontScaleTest {

    @Test
    fun globalFontScaleViaLocalDensityScalesAllTextPrimitivesUniformly() = runComposeUiTest {
        var scale by mutableFloatStateOf(1.0f)

        val host = DeckHostParams(
            theme = DeckThemes.stageDark(),
            designOverridesProvider = {
                DesignSystemOverrides(
                    typography = TypographyTokenOverrides(globalFontScale = scale),
                )
            },
        )
        val decorator = deckRootDecorator(host)

        setContent {
            Column(modifier = Modifier.size(960.dp, 540.dp)) {
                decorator.decorate {
                    val theme = LocalDeckTheme.current
                    val magicTransition = updateTransition(AnnotatedString("Magic Heading"), label = "magic")
                    val warpTransition = updateTransition(0, label = "warp")
                    Column(
                        modifier = Modifier.fillMaxSize().padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        DeckEditableText(
                            elementId = "gfs:editable",
                            text = "Editable Headline",
                            style = theme.typography.heading,
                            modifier = Modifier.testTag("gfs-editable"),
                        )
                        DeckMagicText(
                            transition = magicTransition,
                            elementId = "gfs:magic",
                            style = theme.typography.heading,
                            modifier = Modifier.testTag("gfs-magic"),
                        )
                        DeckWarpCodeCard(
                            codeStates = listOf("val answer = 42\nprintln(answer)"),
                            stateTransition = warpTransition,
                            language = Kotlin,
                            textStyle = theme.typography.codeCompact,
                            elementId = "gfs:warp",
                            modifier = Modifier.testTag("gfs-warp"),
                        )
                        Text(
                            text = "Inline Override Copy",
                            style = theme.typography.body.copy(fontSize = 36.sp, lineHeight = 44.sp),
                            modifier = Modifier.testTag("gfs-inline"),
                        )
                    }
                }
            }
        }
        waitForIdle()

        val tags = listOf("gfs-editable", "gfs-magic", "gfs-inline")
        val baselineHeights = tags.associateWith { tag ->
            onNodeWithTag(tag).getUnclippedBoundsInRoot().height.value
        }
        val warpBaseHeight = onNodeWithTag("gfs-warp").getUnclippedBoundsInRoot().height.value

        runOnIdle { scale = 1.18f }
        waitForIdle()

        for (tag in tags) {
            val baseH = baselineHeights.getValue(tag)
            val scaledH = onNodeWithTag(tag).getUnclippedBoundsInRoot().height.value
            val expected = baseH * 1.18f
            assertTrue(
                abs(scaledH - expected) <= 2.0f,
                "Expected '$tag' height to scale from ${baseH}dp to ~${expected}dp at 1.18x (actual=${scaledH}dp)",
            )
        }

        // DeckWarpCodeCard includes fixed dp card padding (36dp * 2 = 72dp), while inner text lines scale 1.18x.
        val warpScaledHeight = onNodeWithTag("gfs-warp").getUnclippedBoundsInRoot().height.value
        val warpPaddingDp = 72f
        val warpInnerBase = (warpBaseHeight - warpPaddingDp).coerceAtLeast(1f)
        val warpInnerScaled = (warpScaledHeight - warpPaddingDp).coerceAtLeast(1f)
        val warpExpectedInner = warpInnerBase * 1.18f
        assertTrue(
            abs(warpInnerScaled - warpExpectedInner) <= 3.0f,
            "Expected DeckWarpCodeCard inner text height to scale from ${warpInnerBase}dp to ~${warpExpectedInner}dp (actual=${warpInnerScaled}dp)",
        )
    }
}
