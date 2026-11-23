package dev.sebastiano.ciacola.syntax.internal

import org.antlr.v4.kotlinruntime.IntStream
import org.antlr.v4.kotlinruntime.misc.Interval
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SafeCodePointCharStreamTest {

    @Test
    fun bmpOnlyInputUsesZeroAllocationFastPath() {
        val stream = SafeCodePointCharStream("abc")
        assertNull(stream.cpToUtf16)
        assertEquals(3, stream.size())
        assertEquals(0, stream.index())
        assertEquals(0, stream.LA(0))
        assertEquals('a'.code, stream.LA(1))
        assertEquals('b'.code, stream.LA(2))
        assertEquals('c'.code, stream.LA(3))
        assertEquals(IntStream.EOF, stream.LA(4))
        assertEquals(IntStream.EOF, stream.LA(-1))

        stream.consume()
        assertEquals(1, stream.index())
        assertEquals('a'.code, stream.LA(-1))
        assertEquals('b'.code, stream.LA(1))

        stream.seek(3)
        assertEquals(IntStream.EOF, stream.LA(1))
        assertEquals('c'.code, stream.LA(-1))
        assertFailsWith<IllegalStateException> { stream.consume() }

        assertEquals("ab", stream.getText(Interval(0, 1)))
        assertEquals("abc", stream.getText(Interval(-5, 99)))
        assertEquals("", stream.getText(Interval(2, 1)))
        assertEquals("", stream.getText(Interval(3, 3)))
        assertEquals("abc", stream.toString())
    }

    @Test
    fun trailingSupplementaryCodePointAndUnpairedSurrogatesWorkCorrectly() {
        val singleSupp = SafeCodePointCharStream("𠮷")
        assertNotNull(singleSupp.cpToUtf16)
        assertEquals(1, singleSupp.size())
        assertEquals(0x20BB7, singleSupp.LA(1))
        assertEquals(IntStream.EOF, singleSupp.LA(2))
        assertEquals("𠮷", singleSupp.getText(Interval(0, 0)))

        // Build unpaired surrogates dynamically so Kotlin/JS UTF-8 string-literal encoding does not
        // replace lone surrogate literals with '?'.
        val loneHigh = 0xD800.toChar()
        val mixedText = buildString {
            append('a')
            append("𠮷")
            append(loneHigh)
            append('b')
            append(loneHigh)
        }
        val mixed = SafeCodePointCharStream(mixedText)
        assertEquals(5, mixed.size())
        assertEquals('a'.code, mixed.LA(1))
        assertEquals(0x20BB7, mixed.LA(2))
        assertEquals(0xD800, mixed.LA(3))
        assertEquals('b'.code, mixed.LA(4))
        assertEquals(0xD800, mixed.LA(5))
        assertEquals(IntStream.EOF, mixed.LA(6))
        assertEquals("𠮷${loneHigh}b", mixed.getText(Interval(1, 3)))
        assertEquals(loneHigh.toString(), mixed.getText(Interval(4, 4)))

        mixed.seek(5)
        assertEquals(0xD800, mixed.LA(-1))
        assertEquals('b'.code, mixed.LA(-2))
        assertEquals(0xD800, mixed.LA(-3))
        assertEquals(0x20BB7, mixed.LA(-4))
        assertEquals('a'.code, mixed.LA(-5))
        assertEquals(IntStream.EOF, mixed.LA(-6))
    }

    @Test
    fun emptyInputReturnsEofAndRejectsConsume() {
        val empty = SafeCodePointCharStream("")
        assertEquals(0, empty.size())
        assertEquals(IntStream.EOF, empty.LA(1))
        assertEquals(IntStream.EOF, empty.LA(-1))
        assertEquals("", empty.getText(Interval(0, 0)))
        assertFailsWith<IllegalStateException> { empty.consume() }
    }
}
