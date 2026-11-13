package dev.sebastiano.ciacola

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material.ProvideTextStyle
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.renderComposeScene
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.bnorm.storyboard.text.magic.MagicText
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalComposeUiApi::class)
class MagicTextWrapTest {

    @Test
    fun magicTextWrapsLongSingleLineWithinBoundedContainerWidth() {
        var measuredBounds = Rect.Zero
        val maxContainerWidthPx = 600 // 300.dp at density 2f

        renderComposeScene(width = maxContainerWidthPx, height = 400, density = Density(2f)) {
            Box(Modifier.width(300.dp), contentAlignment = Alignment.TopStart) {
                ProvideTextStyle(TextStyle(fontSize = 28.sp, lineHeight = 36.sp)) {
                    MagicText(
                        text = AnnotatedString(
                            "The same path is reversible without inventing a second navigation model.",
                        ),
                        modifier = Modifier.onGloballyPositioned { coords ->
                            measuredBounds = coords.boundsInRoot()
                        },
                    )
                }
            }
        }

        assertTrue(
            measuredBounds.width <= maxContainerWidthPx + 1f,
            "Expected MagicText width (${measuredBounds.width}) <= container width ($maxContainerWidthPx)",
        )
        assertTrue(
            measuredBounds.height > 100f,
            "Expected wrapped MagicText height (${measuredBounds.height}) to span multiple lines (> 100px)",
        )
    }
}
