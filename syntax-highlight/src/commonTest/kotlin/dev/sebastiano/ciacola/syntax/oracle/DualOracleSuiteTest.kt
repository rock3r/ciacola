package dev.sebastiano.ciacola.syntax.oracle

import kotlin.test.Test

class DualOracleSuiteTest {

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
}
