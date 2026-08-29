package dev.bnorm.storyboard.text.magic

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class MagicTextCharacterizationTest {
    @Test
    fun defaultTokenizationPreservesWordsWhitespacePunctuationAndAnnotations() {
        val input = buildAnnotatedString {
            append("val café = 👩🏽‍💻\n")
            addStyle(SpanStyle(color = Color.Magenta), 4, 8)
        }

        val tokens = input.toWords()

        assertEquals(input.text, tokens.joinToString("") { it.text })
        assertEquals(listOf("val", " ", "café", " ", "=", " ", "👩🏽‍💻", "\n"), tokens.map { it.text })
        assertEquals(Color.Magenta, tokens.single { it.text == "café" }.spanStyles.single().item.color)
    }

    @Test
    fun callerSuppliedTokenizationIsUsedWithoutRetokenizing() {
        val supplied = listOf(AnnotatedString("call()"), AnnotatedString(" "), AnnotatedString("as one token"))

        val planned = plan(supplied, supplied)

        assertEquals(supplied, planned.map { it.value })
        assertEquals(listOf("before:0", "before:1", "before:2"), planned.map { it.key })
    }

    @Test
    fun insertionDeletionAndMovementRetainStableKeys() {
        val before = tokens("alpha", " ", "beta", " ", "gamma")

        val inserted = plan(before, tokens("zero", " ", "alpha", " ", "gamma", " ", "beta"))

        assertEquals("before:0", inserted.single { it.value.text == "alpha" }.key)
        assertEquals("before:2", inserted.single { it.value.text == "beta" }.key)
        assertEquals("before:4", inserted.single { it.value.text == "gamma" }.key)
        assertTrue(inserted.single { it.value.text == "zero" }.key!!.startsWith("stable:"))
        assertFalse(inserted.any { it.value.text == "deleted" })
    }

    @Test
    fun styleOnlyChangeRetainsKeyAndRequestsCrossFade() {
        val plain = AnnotatedString("same")
        val styled = buildAnnotatedString {
            append("same")
            addStyle(SpanStyle(color = Color.Cyan), 0, length)
        }

        val result = plan(listOf(plain), listOf(styled)).single()

        assertEquals("before:0", result.key)
        assertEquals(styled, result.value)
        assertTrue(result.crossFade)
    }

    @Test
    fun repeatedTokensUseSurroundingContextDeterministically() {
        val before = tokens("item", " ", "left", " ", "item", " ", "right")

        val result = plan(before, tokens("item", " ", "right", " ", "item", " ", "left"))

        assertEquals("before:6", result.single { it.value.text == "right" }.key)
        assertEquals("before:2", result.single { it.value.text == "left" }.key)
        assertEquals("before:4", result[0].key)
        assertEquals("before:0", result[4].key)
    }

    @Test
    fun matchesCanSpanExplicitLineBreaksWithoutLosingTheBreak() {
        val before = tokens("before", "\n", "shared", " ", "tail")

        val result = plan(before, tokens("shared", " ", "tail", "\n", "after"))

        assertEquals(listOf("shared", " ", "tail", "\n", "after"), result.map { it.value.text })
        assertEquals("before:2", result[0].key)
        assertEquals("before:4", result[2].key)
        assertNotEquals("before:1", result[3].key)
    }

    @Test
    fun emptyAndCompletelyReplacedTextProduceValidPlans() {
        assertTrue(plan(emptyList(), emptyList()).isEmpty())
        assertTrue(plan(tokens("old"), emptyList()).isEmpty())

        val replaced = plan(tokens("old"), tokens("entirely", " ", "new"))
        assertTrue(replaced.all { it.key!!.startsWith("stable:") })
    }

    @Test
    fun largeMostlyUnchangedInputKeepsEveryStableKey() {
        val before = (0 until 2_000).map { AnnotatedString("token-$it") }
        val after = before.toMutableList().apply { this[1_000] = AnnotatedString("replacement") }

        val result = plan(before, after)

        assertEquals(2_000, result.size)
        assertEquals("before:999", result[999].key)
        assertTrue(result[1_000].key!!.startsWith("stable:"))
        assertEquals("before:1001", result[1_001].key)
    }

    @Test
    fun representativeUnicodeRemainsIntactWhenCallerSuppliesSemanticTokens() {
        val unicode = listOf(
            "e\u0301",
            "👨‍👩‍👧‍👦",
            "שלום العربية English",
            "हिन्दी",
            "日本語",
            "한글",
        ).map(::AnnotatedString)

        val result = plan(unicode, listOf(unicode.last()) + unicode.dropLast(1))

        assertEquals(listOf("한글", "e\u0301", "👨‍👩‍👧‍👦", "שלום العربية English", "हिन्दी", "日本語"), result.map { it.value.text })
        assertEquals(listOf("before:5", "before:0", "before:1", "before:2", "before:3", "before:4"), result.map { it.key })
    }

    @OptIn(ExperimentalAtomicApi::class)
    private fun plan(before: List<AnnotatedString>, after: List<AnnotatedString>): List<SharedText> {
        val previous = before.mapIndexed { index, value -> SharedText(value, "before:$index") }
        return findShared(AtomicLong(0), previous, after)
    }

    private fun tokens(vararg values: String): List<AnnotatedString> = values.map(::AnnotatedString)
}
