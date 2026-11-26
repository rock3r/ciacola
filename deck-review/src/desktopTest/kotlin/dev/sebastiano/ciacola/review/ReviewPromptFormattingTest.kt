package dev.sebastiano.ciacola.review

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** Review panel hint reflects the modifier-free Review mode UI. */
class ReviewPromptFormattingTest {

    @Test
    fun commentHintMatchesTheModifierFreeReviewMode() {
        assertEquals("Click any element or press C / ⌘M to comment.", reviewCommentHint(reviewModeActive = true))
        val outside = reviewCommentHint(reviewModeActive = false)
        assertFalse(outside.contains("Ctrl"), "No legacy modifier instructions: $outside")
        assertFalse(outside.contains("Story window"), "No legacy window names: $outside")
    }
}
