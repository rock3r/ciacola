package dev.sebastiano.ciacola.syntax.oracle

import dev.sebastiano.ciacola.syntax.SyntaxLanguage
import dev.sebastiano.ciacola.syntax.assertAllOracleFixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DualOracleSuiteTest {

    @Test
    fun allSeventeenLanguagesHaveDualOracleFixtures() {
        val allFixtures = buildList {
            add(KotlinOracleFixtures.fixture)
            add(XmlOracleFixtures.fixture)
            add(JsonOracleFixtures.fixture)
            addAll(HtmlOracleFixtures.all)
            addAll(CssOracleFixtures.all)
            addAll(JavaScriptOracleFixtures.all)
            addAll(TypeScriptOracleFixtures.all)
            addAll(rustOracleFixtures)
            addAll(goOracleFixtures)
            add(PythonOracleFixtures.fixture)
            add(TomlOracleFixtures.fixture)
            add(JavaOracleFixtures.fixture)
            addAll(cOracleFixtures)
            addAll(cppOracleFixtures)
            addAll(shaderOracleFixtures)
        }
        val coveredLanguages = allFixtures.map { it.language }.toSet()
        assertEquals(SyntaxLanguage.entries.toSet(), coveredLanguages)
        for (language in SyntaxLanguage.entries) {
            assertTrue(allFixtures.any { it.language == language }, "Missing oracle fixture for $language")
        }
    }

    @Test
    fun kotlinMatchesDualOracle() {
        assertMatchesOracleFixture(KotlinOracleFixtures.fixture)
    }

    @Test
    fun xmlMatchesDualOracle() {
        assertMatchesOracleFixture(XmlOracleFixtures.fixture)
    }

    @Test
    fun jsonMatchesDualOracle() {
        assertMatchesOracleFixture(JsonOracleFixtures.fixture)
    }

    @Test
    fun htmlMatchesDualOracle() {
        assertAllOracleFixtures(HtmlOracleFixtures.all)
    }

    @Test
    fun cssMatchesDualOracle() {
        assertAllOracleFixtures(CssOracleFixtures.all)
    }

    @Test
    fun javaScriptMatchesDualOracle() {
        assertAllOracleFixtures(JavaScriptOracleFixtures.all)
    }

    @Test
    fun typeScriptMatchesDualOracle() {
        assertAllOracleFixtures(TypeScriptOracleFixtures.all)
    }

    @Test
    fun rustMatchesDualOracle() {
        assertAllOracleFixtures(rustOracleFixtures)
    }

    @Test
    fun goMatchesDualOracle() {
        assertAllOracleFixtures(goOracleFixtures)
    }

    @Test
    fun pythonMatchesDualOracle() {
        assertMatchesOracleFixture(PythonOracleFixtures.fixture)
    }

    @Test
    fun tomlMatchesDualOracle() {
        assertMatchesOracleFixture(TomlOracleFixtures.fixture)
    }

    @Test
    fun javaMatchesDualOracle() {
        assertMatchesOracleFixture(JavaOracleFixtures.fixture)
    }

    @Test
    fun cMatchesDualOracle() {
        assertAllOracleFixtures(cOracleFixtures)
    }

    @Test
    fun cppMatchesDualOracle() {
        assertAllOracleFixtures(cppOracleFixtures)
    }

    @Test
    fun glslMatchesDualOracle() {
        val fixtures = shaderOracleFixtures.filter { it.language == SyntaxLanguage.Glsl }
        assertTrue(fixtures.isNotEmpty())
        assertAllOracleFixtures(fixtures)
    }

    @Test
    fun skslMatchesDualOracle() {
        val fixtures = shaderOracleFixtures.filter { it.language == SyntaxLanguage.Sksl }
        assertTrue(fixtures.isNotEmpty())
        assertAllOracleFixtures(fixtures)
    }

    @Test
    fun agslMatchesDualOracle() {
        val fixtures = shaderOracleFixtures.filter { it.language == SyntaxLanguage.Agsl }
        assertTrue(fixtures.isNotEmpty())
        assertAllOracleFixtures(fixtures)
    }
}
