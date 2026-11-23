package dev.sebastiano.ciacola.syntax.antlr.sql

import org.antlr.v4.kotlinruntime.CharStream
import org.antlr.v4.kotlinruntime.IntStream
import org.antlr.v4.kotlinruntime.Lexer
import org.antlr.v4.kotlinruntime.misc.Interval

/**
 * Shared case-insensitive keyword, built-in type, built-in function, and constant tables
 * for SQL lexing across ANSI SQL, PostgreSQL, MySQL, SQLite, T-SQL, and PL/SQL.
 */
internal object SqlKeywords {
    val keywords: Set<String> = hashSetOf(
        "ACTION", "ADD", "AFTER", "ALL", "ALTER", "ALWAYS", "ANALYZE", "AND", "ANY", "APPLY",
        "AS", "ASC", "ATTACH", "AUTHORIZATION", "AUTOINCREMENT", "AUTO_INCREMENT",
        "BEFORE", "BEGIN", "BETWEEN", "BY",
        "CASCADE", "CASE", "CAST", "CATCH", "CHECK", "CHECKPOINT", "COALESCE", "COLLATE",
        "COLUMN", "COMMENT", "COMMIT", "COMMITTED", "CONCURRENTLY", "CONFLICT", "CONSTRAINT",
        "CONTINUE", "CREATE", "CROSS", "CURRENT",
        "DATABASE", "DEALLOCATE", "DECLARE", "DEFAULT", "DEFERRABLE", "DEFERRED", "DEFINER",
        "DELETE", "DESC", "DESCRIBE", "DETACH", "DISABLE", "DISTINCT", "DO", "DOMAIN", "DROP",
        "EACH", "ELSE", "ELSEIF", "ELSIF", "ENABLE", "END", "ENUM", "ESCAPE", "EXCEPT",
        "EXCLUDE", "EXEC", "EXECUTE", "EXISTS", "EXIT", "EXPLAIN", "EXTENSION", "EXTRACT",
        "FETCH", "FILTER", "FIRST", "FOLLOWING", "FOR", "FOREIGN", "FROM", "FULL", "FUNCTION",
        "GENERATED", "GO", "GRANT", "GROUP", "GROUPS",
        "HAVING", "HOLDLOCK",
        "IDENTITY", "IF", "ILIKE", "IMMEDIATE", "IMMUTABLE", "IN", "INDEX", "INITIALLY",
        "INNER", "INSERT", "INSTEAD", "INTERSECT", "INTO", "INVOKER", "IS", "ISOLATION",
        "JOIN",
        "KEY",
        "LANGUAGE", "LAST", "LATERAL", "LEFT", "LEVEL", "LIKE", "LIMIT", "LOOP",
        "MATCHED", "MATERIALIZED", "MERGE",
        "NATURAL", "NEW", "NEXT", "NO", "NOLOCK", "NOT", "NOTHING", "NULLIF", "NULLS",
        "OF", "OFFSET", "OLD", "ON", "ONLY", "OR", "ORDER", "ORDINALITY", "OTHERS", "OUTER",
        "OUTPUT", "OVER", "OWNED",
        "PARTITION", "PERCENT", "PERFORM", "PIVOT", "PLPGSQL", "PRAGMA", "PRECEDING",
        "PREPARE", "PRIMARY", "PRINT", "PRIVILEGES", "PROCEDURE", "PUBLIC",
        "RAISE", "RANGE", "READ", "RECURSIVE", "REFERENCES", "RENAME", "REPEATABLE",
        "REPLACE", "RESTRICT", "RETURN", "RETURNING", "REVOKE", "RIGHT", "ROLLBACK",
        "ROW", "ROWS",
        "SAVEPOINT", "SCHEMA", "SECURITY", "SELECT", "SEQUENCE", "SERIALIZABLE", "SET",
        "SHOW", "SIMILAR", "SOME", "SQL", "STABLE", "START", "STATEMENT", "STRICT",
        "TABLE", "TEMP", "TEMPORARY", "THEN", "THROW", "TIES", "TO", "TOP", "TRANSACTION",
        "TRIGGER", "TRUNCATE", "TRY", "TUPLE", "TYPE",
        "UNBOUNDED", "UNCOMMITTED", "UNION", "UNIQUE", "UNPIVOT", "UPDATE", "UPDLOCK",
        "USE", "USING",
        "VACUUM", "VALUES", "VARYING", "VIEW", "VOLATILE",
        "WAITFOR", "WHEN", "WHERE", "WHILE", "WINDOW", "WITH", "WITHIN", "WITHOUT", "WORK",
        "WRITE",
        "XOR",
        "ZONE",
    )

    val types: Set<String> = hashSetOf(
        "ANYARRAY", "ANYELEMENT", "ARRAY",
        "BIGDECIMAL", "BIGINT", "BIGSERIAL", "BINARY", "BIT", "BLOB", "BOOL", "BOOLEAN", "BYTEA",
        "CHAR", "CHARACTER", "CIDR", "CLOB",
        "DATE", "DATETIME", "DATETIME2", "DATETIMEOFFSET", "DEC", "DECIMAL", "DOUBLE",
        "FLOAT", "FLOAT4", "FLOAT8",
        "GEOGRAPHY", "GEOMETRY",
        "INET", "INT", "INT2", "INT4", "INT8", "INTEGER", "INTERVAL",
        "JSON", "JSONB",
        "MACADDR", "MACADDR8", "MEDIUMINT", "MONEY",
        "NCHAR", "NCLOB", "NUMERIC", "NVARCHAR",
        "OID",
        "PRECISION",
        "REAL", "RECORD", "REGCLASS",
        "SERIAL", "SETOF", "SMALLDATETIME", "SMALLINT", "SMALLSERIAL",
        "TEXT", "TIME", "TIMESTAMP", "TIMESTAMPTZ", "TIMETZ", "TINYINT", "TSQUERY", "TSVECTOR",
        "UNIQUEIDENTIFIER", "UUID",
        "VARBINARY", "VARCHAR", "VARCHAR2", "VOID",
        "XML",
    )

    val constants: Set<String> = hashSetOf(
        "CURRENT_DATE",
        "CURRENT_TIME",
        "CURRENT_TIMESTAMP",
        "CURRENT_USER",
        "FALSE",
        "LOCALTIME",
        "LOCALTIMESTAMP",
        "NULL",
        "SESSION_USER",
        "SYSTEM_USER",
        "TRUE",
        "UNKNOWN",
    )

    /**
     * Keywords that also double as scalar functions when immediately followed by `(`.
     */
    val dualKeywordFunctions: Set<String> = hashSetOf(
        "LEFT",
        "REPLACE",
        "RIGHT",
    )

    val builtinFunctions: Set<String> = hashSetOf(
        "ABS", "ARRAY_AGG", "AVG",
        "CEIL", "CEILING", "CHAR_LENGTH", "CONCAT", "CONCAT_WS", "COUNT", "CUME_DIST",
        "DATE_PART", "DATE_TRUNC", "DENSE_RANK",
        "FIRST_VALUE", "FLOOR", "FORMAT",
        "GENERATE_SERIES", "GEN_RANDOM_UUID", "GREATEST",
        "IFNULL", "IIF",
        "JSONB_AGG", "JSONB_BUILD_OBJECT", "JSON_AGG", "JSON_ARRAY", "JSON_BUILD_OBJECT",
        "JSON_EXTRACT", "JSON_OBJECT", "JSON_VALUE",
        "LAG", "LAST_VALUE", "LEAD", "LEAST", "LEFT", "LENGTH", "LOWER", "LTRIM",
        "MAX", "MD5", "MIN", "MOD",
        "NOW", "NTH_VALUE", "NTILE", "NVL",
        "OCTET_LENGTH",
        "PERCENT_RANK", "POSITION", "POWER",
        "RANK", "REGEXP_MATCHES", "REGEXP_REPLACE", "REPLACE", "REVERSE", "RIGHT", "ROUND",
        "ROW_NUMBER", "RTRIM",
        "SHA256", "SPLIT_PART", "SQRT", "STRING_AGG", "STRPOS", "SUBSTR", "SUBSTRING", "SUM",
        "TO_CHAR", "TO_DATE", "TO_NUMBER", "TO_TIMESTAMP", "TRIM",
        "UNNEST", "UPPER",
    )

    /**
     * Keywords that introduce an identifier followed by `(` that is a table/view/CTE/index name
     * rather than a function call.
     */
    val ddlTargetIntroducers: Set<String> = hashSetOf(
        "INTO",
        "ON",
        "RECURSIVE",
        "REFERENCES",
        "TABLE",
        "VIEW",
        "WITH",
    )
}

/**
 * Pure Kotlin Multiplatform base lexer for [SqlLexer], providing O(N) nested block comment
 * scanning, PostgreSQL dollar-quoted string matching (`$$...$$` and `$tag$...$tag$`),
 * T-SQL `[Column Name]` vs array subscript `[1]` disambiguation, and trailing-dot float guard.
 */
internal abstract class SqlLexerBase(input: CharStream) : Lexer(input) {
    private var allowBracketIdent: Boolean = true

    override fun reset() {
        super.reset()
        allowBracketIdent = true
    }

    protected fun isBracketIdentAllowed(): Boolean = allowBracketIdent

    protected fun setBracketIdentAllowed(allowed: Boolean) {
        allowBracketIdent = allowed
    }

    protected fun onIdentMatched() {
        val text = _input.getText(Interval(_tokenStartCharIndex, _input.index() - 1))
        val upper = text.uppercase()
        allowBracketIdent = upper in SqlKeywords.keywords && upper !in SqlKeywords.types
    }

    protected fun isNotRangeDotFloat(): Boolean = _input.LA(1) != '.'.code

    protected fun consumeNestedBlockComment() {
        var depth = 1
        while (depth > 0 && _input.LA(1) != IntStream.EOF) {
            val c1 = _input.LA(1)
            val c2 = _input.LA(2)
            if (c1 == '/'.code && c2 == '*'.code) {
                _input.consume()
                _input.consume()
                depth++
            } else if (c1 == '*'.code && c2 == '/'.code) {
                _input.consume()
                _input.consume()
                depth--
            } else {
                _input.consume()
            }
        }
    }

    protected fun consumeDollarString() {
        val tag = _input.getText(Interval(_tokenStartCharIndex, _input.index() - 1))
        val tagCodePoints = toCodePoints(tag)
        val tagLen = tagCodePoints.size

        while (_input.LA(1) != IntStream.EOF) {
            if (_input.LA(1) == '$'.code) {
                var matched = true
                for (j in 1 until tagLen) {
                    if (_input.LA(j + 1) != tagCodePoints[j]) {
                        matched = false
                        break
                    }
                }
                if (matched) {
                    repeat(tagLen) { _input.consume() }
                    return
                }
            }
            _input.consume()
        }
    }

    private fun toCodePoints(str: String): IntArray {
        val out = IntArray(str.length)
        var count = 0
        var i = 0
        while (i < str.length) {
            val hi = str[i]
            if (i + 1 < str.length && hi.isHighSurrogate() && str[i + 1].isLowSurrogate()) {
                val lo = str[i + 1]
                out[count++] = 0x10000 + ((hi.code - 0xD800) shl 10) + (lo.code - 0xDC00)
                i += 2
            } else {
                out[count++] = hi.code
                i++
            }
        }
        return if (count == out.size) out else out.copyOf(count)
    }
}
