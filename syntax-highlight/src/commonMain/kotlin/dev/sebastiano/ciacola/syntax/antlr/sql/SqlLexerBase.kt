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
        "LANGUAGE", "LAST", "LATERAL", "LEFT", "LEVEL", "LIKE", "LIMIT", "LOGIN", "LOOP",
        "MATCHED", "MATERIALIZED", "MERGE",
        "NATURAL", "NEW", "NEXT", "NO", "NOLOCK", "NOT", "NOTHING", "NULLIF", "NULLS",
        "OF", "OFFSET", "OLD", "ON", "ONLY", "OR", "ORDER", "ORDINALITY", "OTHERS", "OUTER",
        "OUTPUT", "OVER", "OWNED",
        "PARTITION", "PERCENT", "PERFORM", "PIVOT", "PLPGSQL", "PRAGMA", "PRECEDING",
        "PREPARE", "PRIMARY", "PRINT", "PRIVILEGES", "PROCEDURE", "PUBLIC",
        "RAISE", "RANGE", "READ", "RECURSIVE", "REFERENCES", "RENAME", "REPEATABLE",
        "REPLACE", "RESTRICT", "RETURN", "RETURNING", "REVOKE", "RIGHT", "ROLE", "ROLLBACK",
        "ROW", "ROWS",
        "SAVEPOINT", "SCHEMA", "SECURITY", "SELECT", "SEQUENCE", "SERIALIZABLE", "SERVER", "SET",
        "SHOW", "SIMILAR", "SOME", "SQL", "STABLE", "START", "STATEMENT", "STRICT",
        "TABLE", "TEMP", "TEMPORARY", "THEN", "THROW", "TIES", "TO", "TOP", "TRANSACTION",
        "TRIGGER", "TRUNCATE", "TRY", "TUPLE", "TYPE",
        "UNBOUNDED", "UNCOMMITTED", "UNION", "UNIQUE", "UNPIVOT", "UPDATE", "UPDLOCK",
        "USE", "USER", "USING",
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

    /**
     * Keywords that commonly precede a T-SQL `#temp` / `##global` table identifier across a newline.
     */
    val tempTableIntroducers: Set<String> = hashSetOf(
        "FROM",
        "INTO",
        "JOIN",
        "MERGE",
        "TABLE",
        "TRUNCATE",
        "UPDATE",
    )

    /**
     * SQL clause, operator, and statement keywords that validly follow a closed string literal
     * after whitespace. Used to distinguish standard SQL strings ending in a literal backslash
     * (e.g. `'C:\Backup\' FROM t`) from MySQL backslash-escaped quotes (e.g. `'users\' name'`).
     */
    val postStringKeywords: Set<String> = hashSetOf(
        "ALTER", "AND", "AS", "ASC", "BEGIN", "BETWEEN", "BY", "CASE", "CHECK", "COLLATE",
        "COMMIT", "CONFLICT", "CREATE", "CROSS", "DEFAULT", "DELETE", "DESC", "DO", "DROP",
        "ELSE", "ELSEIF", "ELSIF", "END", "ESCAPE", "EXCEPT", "FALSE", "FETCH", "FILTER",
        "FIRST", "FOR", "FROM", "FULL", "GROUP", "GROUPS", "HAVING", "ILIKE", "IN", "INNER",
        "INSERT", "INTERSECT", "INTO", "IS", "JOIN", "LAST", "LEFT", "LIKE", "LIMIT", "LOOP",
        "NATURAL", "NEXT", "NOT", "NULL", "NULLS", "OFFSET", "ON", "ONLY", "OR", "ORDER",
        "OUTER", "OVER", "PARTITION", "RANGE", "RETURNING", "RIGHT", "ROLLBACK", "ROW", "ROWS",
        "SELECT", "SET", "SIMILAR", "THEN", "TO", "TRUE", "UNION", "UPDATE", "USING", "VALUES",
        "WHEN", "WHERE", "WINDOW", "WITH", "XOR",
    )
}

/**
 * Pure Kotlin Multiplatform base lexer for [SqlLexer], providing O(N) nested block comment
 * scanning, PostgreSQL dollar-quoted string matching (`$$...$$` and `$tag$...$tag$`),
 * T-SQL `[Column Name]` vs array subscript `[1]` disambiguation, `#` comment/temp-table/XOR
 * disambiguation, and trailing-dot float guard.
 */
internal abstract class SqlLexerBase(input: CharStream) : Lexer(input) {
    private var allowBracketIdent: Boolean = true
    private var inValuePosition: Boolean = false
    private var allowTempTableAtLineStart: Boolean = false
    private var afterTypeOrArray: Boolean = false
    private var noClosingBracketUpToCp: Int = -1
    private var pendingBracketIdentEndCp: Int = -1

    override fun reset() {
        super.reset()
        allowBracketIdent = true
        inValuePosition = false
        allowTempTableAtLineStart = false
        afterTypeOrArray = false
        noClosingBracketUpToCp = -1
        pendingBracketIdentEndCp = -1
    }

    protected fun isInValuePosition(): Boolean = inValuePosition

    protected fun onOperatorToken() {
        allowBracketIdent = true
        inValuePosition = false
        allowTempTableAtLineStart = false
        afterTypeOrArray = false
    }

    protected fun onValueToken() {
        allowBracketIdent = false
        inValuePosition = true
        allowTempTableAtLineStart = false
        afterTypeOrArray = false
    }

    protected fun onIdentMatched() {
        val text = _input.getText(Interval(_tokenStartCharIndex, _input.index() - 1))
        val upper = text.uppercase()
        val isType = upper in SqlKeywords.types
        val isConst = upper in SqlKeywords.constants
        val isKw = upper in SqlKeywords.keywords
        allowBracketIdent = isKw && !isType
        inValuePosition = isConst || (!isKw && !isType)
        allowTempTableAtLineStart = upper in SqlKeywords.tempTableIntroducers
        afterTypeOrArray = isType
    }

    protected fun isBracketIdentWithClosingAhead(): Boolean {
        val startCp = _tokenStartCharIndex
        if (startCp < noClosingBracketUpToCp) return false
        if (!allowBracketIdent && afterTypeOrArray) return false

        val precededByWs = isPrecededByHorizontalWs()
        var offset = 1
        var bodyLen = 0
        var hasSpaceOrBackslash = false
        var hasDisallowedAliasChar = false
        var allDigits = true

        while (true) {
            val ch = _input.LA(offset)
            if (ch == IntStream.EOF || ch == '\r'.code || ch == '\n'.code) {
                noClosingBracketUpToCp = _input.index() + offset - 1
                pendingBracketIdentEndCp = -1
                return false
            }
            if (ch == ']'.code) {
                if (_input.LA(offset + 1) == ']'.code) {
                    allDigits = false
                    bodyLen += 2
                    offset += 2
                    continue
                }
                val endCp = _input.index() + offset
                val allowed = bodyLen > 0 && (
                    allowBracketIdent || (
                        (precededByWs || hasSpaceOrBackslash) &&
                            !hasDisallowedAliasChar &&
                            !allDigits
                        )
                    )
                pendingBracketIdentEndCp = if (allowed) endCp else -1
                return allowed
            }
            if (ch == ' '.code || ch == '\t'.code || ch == '\\'.code) {
                hasSpaceOrBackslash = true
            }
            if (ch == ','.code || ch == '\''.code || ch == '['.code) {
                if (!allowBracketIdent) {
                    pendingBracketIdentEndCp = -1
                    return false
                }
                hasDisallowedAliasChar = true
            }
            if (ch !in '0'.code..'9'.code) {
                allDigits = false
            }
            bodyLen++
            offset++
        }
    }

    protected fun consumeBracketIdent() {
        val target = pendingBracketIdentEndCp
        pendingBracketIdentEndCp = -1
        while (_input.index() < target && _input.LA(1) != IntStream.EOF) {
            _input.consume()
        }
    }

    protected fun isNotDoubleDashAfterHash(): Boolean = _input.LA(1) != '-'.code

    protected fun isLineStartHashComment(): Boolean {
        if (!isAtLineStartAfterPrefix()) return false
        if (inValuePosition && isPgJsonOpAfterHash()) return false
        if (allowTempTableAtLineStart && isTempTableIdentAhead()) return false
        return true
    }

    protected fun isNonLineStartHashComment(): Boolean = !inValuePosition && !isTempTableIdentAhead()

    protected fun isNotRangeDotFloat(): Boolean = _input.LA(1) != '.'.code

    /**
     * Scans the body and closing delimiter of a single-quoted (`'...'`, `N'...'`) or double-quoted
     * (`"..."`) SQL literal in strictly O(N) time.
     *
     * Supports both ANSI doubled-quote escaping (`''` / `""`) and MySQL backslash-escaped quotes
     * (`\'` / `\"`), while closing on a trailing literal backslash when the quote sits at a clear
     * SQL token boundary (e.g. `REPLACE(path, '\', '/')`, `'C:\';`, `'C:\Backup\' FROM t`).
     */
    protected fun consumeSqlQuoted(quote: Int) {
        var sawBackslashQuoteEscape = false
        while (true) {
            val ch = _input.LA(1)
            if (ch == IntStream.EOF) return
            if (ch == quote) {
                _input.consume()
                if (_input.LA(1) == quote) {
                    _input.consume()
                    continue
                }
                return
            }
            if (ch == '\\'.code) {
                val next = _input.LA(2)
                if (next == IntStream.EOF) {
                    _input.consume()
                    return
                }
                if (next == '\\'.code) {
                    _input.consume()
                    _input.consume()
                    continue
                }
                if (next == quote) {
                    if (_input.LA(3) == quote) {
                        if (sawBackslashQuoteEscape || isSqlStringBoundaryAfterQuote(4)) {
                            // MySQL escaped quote right before the closing quote: 'foo\'';
                            sawBackslashQuoteEscape = true
                            _input.consume()
                            _input.consume()
                        } else {
                            // Standard SQL literal backslash before a doubled quote: 'a\''b'
                            _input.consume()
                        }
                        continue
                    }
                    if (!sawBackslashQuoteEscape && isSqlStringBoundaryAfterQuote(3)) {
                        // Standard SQL string ending with a literal backslash: '\', 'C:\';
                        _input.consume()
                        _input.consume()
                        return
                    }
                    // MySQL backslash-escaped quote: lock this literal into MySQL-escape mode.
                    sawBackslashQuoteEscape = true
                    _input.consume()
                    _input.consume()
                    continue
                }
                _input.consume()
                _input.consume()
                continue
            }
            _input.consume()
        }
    }

    private fun isSqlStringBoundaryAfterQuote(startOffset: Int): Boolean {
        val c = _input.LA(startOffset)
        if (isSqlImmediateDelimiterOrOperator(startOffset, c)) return true
        if (c != ' '.code && c != '\t'.code) return false

        var offset = startOffset + 1
        while (true) {
            val ch = _input.LA(offset)
            if (ch == ' '.code || ch == '\t'.code) {
                offset++
            } else {
                break
            }
        }
        val afterWs = _input.LA(offset)
        if (isSqlImmediateDelimiterOrOperator(offset, afterWs)) return true
        if (afterWs == '-'.code || afterWs == '/'.code || afterWs == '*'.code || afterWs == '%'.code ||
            afterWs == '&'.code || afterWs == '^'.code || afterWs == '~'.code || afterWs == '?'.code
        ) {
            return true
        }
        if (afterWs == '#'.code) {
            val next = _input.LA(offset + 1)
            if (next == ' '.code || next == '\t'.code) return true
        }

        if (afterWs !in 'A'.code..'Z'.code && afterWs !in 'a'.code..'z'.code) return false
        val sb = StringBuilder(16)
        var k = 0
        while (k < 16) {
            val ch = _input.LA(offset + k)
            if (ch in 'A'.code..'Z'.code || ch in 'a'.code..'z'.code || ch == '_'.code) {
                sb.append(ch.toChar().uppercaseChar())
                k++
            } else {
                break
            }
        }
        val trailing = _input.LA(offset + k)
        if (trailing in 'A'.code..'Z'.code ||
            trailing in 'a'.code..'z'.code ||
            trailing in '0'.code..'9'.code ||
            trailing == '_'.code ||
            trailing == '$'.code ||
            trailing >= 0x80
        ) {
            return false
        }
        return sb.toString() in SqlKeywords.postStringKeywords
    }

    private fun isSqlImmediateDelimiterOrOperator(offset: Int, c: Int): Boolean {
        if (c == IntStream.EOF ||
            c == '\r'.code || c == '\n'.code ||
            c == ','.code || c == ')'.code || c == ';'.code || c == ']'.code
        ) {
            return true
        }
        if (c == '='.code || c == '+'.code || c == '<'.code || c == '>'.code ||
            c == '!'.code || c == '|'.code
        ) {
            return true
        }
        if (c == ':'.code && _input.LA(offset + 1) == ':'.code) return true
        if (c == '-'.code && (_input.LA(offset + 1) == '-'.code || _input.LA(offset + 1) == '>'.code)) return true
        if (c == '/'.code && _input.LA(offset + 1) == '*'.code) return true
        if (c == '#'.code && (_input.LA(offset + 1) == '>'.code || _input.LA(offset + 1) == '-'.code)) return true
        return false
    }

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

    private fun isAtLineStartAfterPrefix(): Boolean {
        val startCp = _tokenStartCharIndex
        val currentIndex = _input.index()
        var back = 1
        while (startCp - back >= 0) {
            val ch = _input.LA(-(currentIndex - startCp + back))
            if (ch == '\n'.code || ch == '\r'.code || ch == IntStream.EOF) return true
            if (ch == ' '.code || ch == '\t'.code || (startCp - back == 0 && ch == BOM_CODE_POINT)) {
                back++
            } else {
                return false
            }
        }
        return true
    }

    private fun isPgJsonOpAfterHash(): Boolean {
        val c1 = _input.LA(1)
        if (c1 == '>'.code) return true
        return c1 == '-'.code && _input.LA(2) != '-'.code
    }

    private fun isTempTableIdentAhead(): Boolean {
        val first = if (_input.LA(1) == '#'.code) _input.LA(2) else _input.LA(1)
        return first == '_'.code ||
            first in 'A'.code..'Z'.code ||
            first in 'a'.code..'z'.code ||
            (first >= 0x80 && first != IntStream.EOF)
    }

    private fun isPrecededByHorizontalWs(): Boolean {
        if (_tokenStartCharIndex == 0) return true
        val dist = _input.index() - _tokenStartCharIndex + 1
        val prev = _input.LA(-dist)
        return prev == ' '.code || prev == '\t'.code || prev == '\r'.code || prev == '\n'.code || prev == BOM_CODE_POINT
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

    private companion object {
        const val BOM_CODE_POINT = 0xFEFF
    }
}
