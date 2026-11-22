package dev.bnorm.storyboard

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DiffListsBlankLineTest {

    private val blankSkippingLanguage = Custom(
        parser = object : Parser {
            override fun parse(code: String): List<Token> = buildList {
                code.lines().forEachIndexed { lineNumber, line ->
                    if (line.isBlank()) return@forEachIndexed
                    var idx = 0
                    line.split(" ").forEach { word ->
                        if (word.isNotEmpty()) {
                            add(
                                Token(
                                    content = word,
                                    scope = "source.test",
                                    depth = 1,
                                    language = Kotlin,
                                    allScopes = listOf("source.test"),
                                    lineNumber = lineNumber,
                                    startIndex = idx,
                                    endIndex = idx + word.length,
                                ),
                            )
                        }
                        idx += word.length + 1
                    }
                }
            }
        },
    )

    private fun State.content(): String = when (this) {
        is State.Match -> current.content
        is State.Insert -> token.content
        is State.Delete -> token.content
        State.Empty -> ""
    }

    @Test
    fun buildDiffListPreservesInteriorBlankLinesWithoutOutOfBoundsForSparseParser() {
        val step0 = """
            val alpha = 1

            fun greet() {
                println(alpha)
            }
        """.trimIndent()
        val step1 = """
            val alpha = 1


            fun greet(name: String) {
                println(name)
            }
        """.trimIndent()

        val diffs = buildDiffList(
            codeBlocks = listOf(BasicCode(step0), BasicCode(step1)),
            language = blankSkippingLanguage,
        )

        assertEquals(2, diffs.size)
        assertEquals(5, diffs[0].rows.size, "Step 0 has 5 lines including 1 interior blank line")
        assertTrue(diffs[0].rows[1].isEmpty(), "Step 0 line 1 should remain an empty row")
        assertTrue(diffs[0].rows[0].isNotEmpty() && diffs[0].rows[2].isNotEmpty())

        assertEquals(6, diffs[1].rows.size, "Step 1 has 6 lines including 2 interior blank lines")
        assertTrue(diffs[1].rows[1].isEmpty(), "Step 1 line 1 should remain an empty row")
        assertTrue(diffs[1].rows[2].isEmpty(), "Step 1 line 2 should remain an empty row")
        assertTrue(diffs[1].rows[0].isNotEmpty() && diffs[1].rows[3].isNotEmpty())
    }

    @Test
    fun buildDiffListPreservesLeadingAndTrailingBlankLinesAcrossMultipleSteps() {
        val step0 = "\nval x = 1\n\nval y = 2\n"
        val step1 = "\n\nval x = 1\n\nval z = 3\n\n"
        val step2 = "val x = 1\nval z = 3"

        val sparseDiffs = buildDiffList(
            codeBlocks = listOf(BasicCode(step0), BasicCode(step1), BasicCode(step2)),
            language = blankSkippingLanguage,
        )
        assertEquals(3, sparseDiffs.size)
        assertEquals(5, sparseDiffs[0].rows.size)
        assertTrue(sparseDiffs[0].rows[0].isEmpty())
        assertTrue(sparseDiffs[0].rows[2].isEmpty())
        assertTrue(sparseDiffs[0].rows[4].isEmpty())

        assertEquals(7, sparseDiffs[1].rows.size)
        assertTrue(sparseDiffs[1].rows[0].isEmpty())
        assertTrue(sparseDiffs[1].rows[1].isEmpty())
        assertTrue(sparseDiffs[1].rows[3].isEmpty())
        assertTrue(sparseDiffs[1].rows[5].isEmpty())
        assertTrue(sparseDiffs[1].rows[6].isEmpty())

        assertEquals(2, sparseDiffs[2].rows.size)
        assertTrue(sparseDiffs[2].rows[0].isNotEmpty())
        assertTrue(sparseDiffs[2].rows[1].isNotEmpty())

        // Built-in ANTLR Kotlin parser also preserves exact row counts with blank lines.
        val kotlinDiffs = buildDiffList(
            codeBlocks = listOf(BasicCode(step0), BasicCode(step1), BasicCode(step2)),
            language = Kotlin,
        )
        assertEquals(3, kotlinDiffs.size)
        assertEquals(5, kotlinDiffs[0].rows.size)
        assertTrue(kotlinDiffs[0].rows[0].all { it.content().isBlank() })
        assertTrue(kotlinDiffs[0].rows[2].all { it.content().isBlank() })
        assertTrue(kotlinDiffs[0].rows[4].all { it.content().isBlank() })
        assertEquals(7, kotlinDiffs[1].rows.size)
        assertTrue(kotlinDiffs[1].rows[1].all { it.content().isBlank() })
        assertTrue(kotlinDiffs[1].rows[5].all { it.content().isBlank() })
        assertEquals(2, kotlinDiffs[2].rows.size)
    }
}
