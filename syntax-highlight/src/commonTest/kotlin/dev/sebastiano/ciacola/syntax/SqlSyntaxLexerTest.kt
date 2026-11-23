package dev.sebastiano.ciacola.syntax

import dev.sebastiano.ciacola.syntax.lexer.SqlSyntaxLexer
import dev.sebastiano.ciacola.syntax.oracle.assertMatchesOracleFixture
import dev.sebastiano.ciacola.syntax.oracle.sqlOracleFixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SqlSyntaxLexerTest {

    @Test
    fun classifiesCommentsStringsQuotedIdentsParamsKeywordsTypesFunctionsAndOperators() {
        val code = """
            -- Line comment
            # MySQL comment
            /* outer /* inner */ outer */
            with recursive cte as (
              Select distinct u.id, u.email,
                cast(u.total as numeric(10, 2)) as total,
                coalesce(u.name, 'it''s') as name
              From users u
              Where u.active = true and u.deleted_at is null and u.flag is not unknown
                and u.created_at >= current_timestamp and u.birth_date = current_date
                and u.login_time = current_time and u.owner = current_user
                and u.meta ->> 'k' = 'v'
                and u.meta #>> '{a,b}' != 'x'
                and u.meta #> '{a}' @> '{"a":1}'
                and u.tags <@ array['a']
                and u.meta ?& array['a']
                and u.meta ?| array['a', 'b']
                and u.code ~* '^[a-z]+' and u.code !~* '^z'
                and u.rate <= 1e-5 and u.frac >= .5 and u.mask = 0xFF_00
            )
            SELECT count(*), sum(total), avg(total), min(total), max(total),
                   row_number() over (partition by name order by total desc nulls last),
                   rank() over (), dense_rank() over (),
                   lag(total) over (), lead(total) over (),
                   first_value(total) over (), last_value(total) over (),
                   string_agg(name, ','), array_agg(id), json_build_object('id', id),
                   now(), lower(email), upper(email), substring(email, 1, 3),
                   trim(email), length(email), concat(name, '!'), round(total), abs(total),
                   left(name, 2), right(name, 2), my_fn(total::int),
                   E'a\nb\'', N'nat', B'1010', X'DEAD',
                   ${'$'}${'$'}body${'$'}${'$'}, ${'$'}tag${'$'}body with ${'$'}other${'$'} inside${'$'}tag${'$'},
                   "my ""col${"\"\""}", `backtick_tbl`, [Column Name], [Esc ]] Name],
                   arr[1], matrix[1][2], :param_name, @user_var, @@global_var, ${'$'}1, ?
            FROM cte
            LEFT JOIN other o USING (id);
        """.trimIndent()

        val tokens = assertValidSnippetHasNoInvalidTokens(SqlSyntaxLexer, code, "sql-features")

        fun firstKind(text: String, predicate: (SyntaxToken) -> Boolean = { true }): SyntaxTokenKind =
            tokens.firstOrNull { it.slice(code) == text && predicate(it) }?.kind
                ?: error("Missing token '$text' in: ${tokens.filter { !it.slice(code).isBlank() }.map { it.slice(code) }}")

        assertEquals(SyntaxTokenKind.Comment, firstKind("-- Line comment"))
        assertEquals(SyntaxTokenKind.Comment, firstKind("# MySQL comment"))
        assertEquals(SyntaxTokenKind.Comment, firstKind("/* outer /* inner */ outer */"))

        assertEquals(SyntaxTokenKind.Keyword, firstKind("with"))
        assertEquals(SyntaxTokenKind.Keyword, firstKind("recursive"))
        assertEquals(SyntaxTokenKind.Keyword, firstKind("Select"))
        assertEquals(SyntaxTokenKind.Keyword, firstKind("distinct"))
        assertEquals(SyntaxTokenKind.Keyword, firstKind("cast"))
        assertEquals(SyntaxTokenKind.Keyword, firstKind("coalesce"))
        assertEquals(SyntaxTokenKind.Keyword, firstKind("From"))
        assertEquals(SyntaxTokenKind.Keyword, firstKind("Where"))
        assertEquals(SyntaxTokenKind.Keyword, firstKind("LEFT"))
        assertEquals(SyntaxTokenKind.Keyword, firstKind("JOIN"))
        assertEquals(SyntaxTokenKind.Keyword, firstKind("USING"))

        assertEquals(SyntaxTokenKind.Property, firstKind("email"))
        assertEquals(SyntaxTokenKind.Property, firstKind("active"))
        assertEquals(SyntaxTokenKind.Type, firstKind("numeric"))
        assertEquals(SyntaxTokenKind.Type, firstKind("int"))
        assertEquals(SyntaxTokenKind.Type, firstKind("array"))

        for (c in listOf("true", "null", "unknown", "current_timestamp", "current_date", "current_time", "current_user")) {
            assertEquals(SyntaxTokenKind.Constant, firstKind(c), "Expected $c to be Constant")
        }

        for (fn in listOf(
            "count", "sum", "avg", "min", "max", "row_number", "rank", "dense_rank",
            "lag", "lead", "first_value", "last_value", "string_agg", "array_agg",
            "json_build_object", "now", "lower", "upper", "substring", "trim",
            "length", "concat", "round", "abs", "left", "right", "my_fn",
        )) {
            assertEquals(SyntaxTokenKind.Function, firstKind(fn), "Expected $fn to be Function")
        }

        for (op in listOf("->>", "#>>", "#>", "@>", "<@", "?&", "?|", "~*", "!~*", "::", "!=", "<=", ">=", "?")) {
            assertEquals(SyntaxTokenKind.Operator, firstKind(op), "Expected $op to be Operator")
        }

        assertEquals(SyntaxTokenKind.Number, firstKind("1e-5"))
        assertEquals(SyntaxTokenKind.Number, firstKind(".5"))
        assertEquals(SyntaxTokenKind.Number, firstKind("0xFF_00"))

        assertEquals(SyntaxTokenKind.String, firstKind("'it''s'"))
        assertEquals(SyntaxTokenKind.String, firstKind("E'a\\nb\\''"))
        assertEquals(SyntaxTokenKind.String, firstKind("N'nat'"))
        assertEquals(SyntaxTokenKind.String, firstKind("B'1010'"))
        assertEquals(SyntaxTokenKind.String, firstKind("X'DEAD'"))
        assertEquals(SyntaxTokenKind.String, firstKind("${'$'}${'$'}body${'$'}${'$'}"))
        assertEquals(SyntaxTokenKind.String, firstKind("${'$'}tag${'$'}body with ${'$'}other${'$'} inside${'$'}tag${'$'}"))

        assertEquals(SyntaxTokenKind.String, firstKind("\"my \"\"col\"\"\""))
        assertEquals(SyntaxTokenKind.String, firstKind("`backtick_tbl`"))
        assertEquals(SyntaxTokenKind.Plain, firstKind("[Column Name]"))
        assertEquals(SyntaxTokenKind.Plain, firstKind("[Esc ]] Name]"))

        // Array subscript [1] must be Punctuation + Number + Punctuation, not BRACKET_IDENT!
        val arrIdx = code.indexOf("arr[1]")
        assertEquals(SyntaxTokenKind.Punctuation, firstKind("[") { it.startIndex > arrIdx })
        assertEquals(SyntaxTokenKind.Number, firstKind("1") { it.startIndex > arrIdx })
        assertEquals(SyntaxTokenKind.Punctuation, firstKind("]") { it.startIndex > arrIdx })

        assertEquals(SyntaxTokenKind.Plain, firstKind(":param_name"))
        assertEquals(SyntaxTokenKind.Plain, firstKind("@user_var"))
        assertEquals(SyntaxTokenKind.Plain, firstKind("@@global_var"))
        assertEquals(SyntaxTokenKind.Plain, firstKind("${'$'}1"))
    }

    @Test
    fun distinguishesDdlTargetsBeforeParensAndAllBuiltInTypes() {
        val code = """
            CREATE TABLE public.users (
              id SERIAL PRIMARY KEY,
              big BIGSERIAL NOT NULL,
              small SMALLINT,
              tiny TINYINT,
              flag BOOLEAN DEFAULT FALSE,
              price DECIMAL(10, 2),
              flt REAL,
              dbl DOUBLE PRECISION,
              txt TEXT,
              ch CHAR(4),
              nch NVARCHAR(20),
              raw BYTEA,
              bin VARBINARY(16),
              cl CLOB,
              bl BLOB,
              d DATE,
              t TIME,
              ts TIMESTAMP,
              tstz TIMESTAMPTZ,
              iv INTERVAL,
              uid UUID,
              js JSON,
              doc XML,
              ip INET,
              net CIDR
            );
            INSERT INTO public.users (id) VALUES (1) ON CONFLICT DO NOTHING RETURNING id;
            MERGE INTO target t USING src s ON t.id = s.id WHEN MATCHED THEN DELETE;
            BEGIN TRANSACTION; SAVEPOINT sp1; ROLLBACK; COMMIT;
            GRANT SELECT ON users TO app; REVOKE SELECT ON users FROM app;
            EXPLAIN ANALYZE SELECT public.my_fn(1);
        """.trimIndent()

        val tokens = assertValidSnippetHasNoInvalidTokens(SqlSyntaxLexer, code, "sql-ddl-types")

        fun firstKind(text: String, predicate: (SyntaxToken) -> Boolean = { true }): SyntaxTokenKind =
            tokens.firstOrNull { it.slice(code) == text && predicate(it) }?.kind
                ?: error("Missing token '$text' in: ${tokens.filter { !it.slice(code).isBlank() }.map { it.slice(code) }}")

        assertEquals(SyntaxTokenKind.Property, firstKind("users") { it.startIndex < code.indexOf("(") })
        assertEquals(SyntaxTokenKind.Function, firstKind("my_fn"))
        for (t in listOf(
            "SERIAL", "BIGSERIAL", "SMALLINT", "TINYINT", "BOOLEAN", "DECIMAL", "REAL",
            "DOUBLE", "PRECISION", "TEXT", "CHAR", "NVARCHAR", "BYTEA", "VARBINARY",
            "CLOB", "BLOB", "DATE", "TIME", "TIMESTAMP", "TIMESTAMPTZ", "INTERVAL",
            "UUID", "JSON", "XML", "INET", "CIDR",
        )) {
            assertEquals(SyntaxTokenKind.Type, firstKind(t), "Expected $t to be Type")
        }
    }

    @Test
    fun handlesDialectParametersBracketAliasesTempTablesAndHashOperators() {
        val dialectParamsAndBrackets = """
            SELECT * FROM t WHERE id = ${'$'}user_id;
            MERGE t USING s ON 1=1 WHEN MATCHED THEN DELETE OUTPUT ${'$'}action;
            CREATE USER [domain\bob];
            CREATE LOGIN [app\svc] FROM WINDOWS;
            CREATE ROLE [readers];
            SELECT [u].[Id] FROM [dbo].[Users] [u] WHERE arr[1] = 2 AND matrix[1][2] = 3 AND ARRAY['a'][1] = 'a';
        """.trimIndent()
        fun assertFirstKind(
            tokens: List<SyntaxToken>,
            src: String,
            expected: SyntaxTokenKind,
            text: String,
            predicate: (SyntaxToken) -> Boolean = { true },
        ) {
            val tok = tokens.firstOrNull { it.slice(src) == text && predicate(it) }
                ?: error("Missing token '$text' in: ${tokens.filter { !it.slice(src).isBlank() }.map { "${it.kind}:${it.slice(src)}@${it.startIndex}" }}")
            assertEquals(expected, tok.kind, "Expected '$text' to be $expected")
        }

        val t1 = assertValidSnippetHasNoInvalidTokens(SqlSyntaxLexer, dialectParamsAndBrackets, "sql-params-brackets")
        assertFirstKind(t1, dialectParamsAndBrackets, SyntaxTokenKind.Plain, "${'$'}user_id")
        assertFirstKind(t1, dialectParamsAndBrackets, SyntaxTokenKind.Plain, "${'$'}action")
        assertFirstKind(t1, dialectParamsAndBrackets, SyntaxTokenKind.Keyword, "USER")
        assertFirstKind(t1, dialectParamsAndBrackets, SyntaxTokenKind.Keyword, "LOGIN")
        assertFirstKind(t1, dialectParamsAndBrackets, SyntaxTokenKind.Keyword, "ROLE")
        assertFirstKind(t1, dialectParamsAndBrackets, SyntaxTokenKind.Plain, "[domain\\bob]")
        assertFirstKind(t1, dialectParamsAndBrackets, SyntaxTokenKind.Plain, "[app\\svc]")
        assertFirstKind(t1, dialectParamsAndBrackets, SyntaxTokenKind.Plain, "[readers]")
        assertFirstKind(t1, dialectParamsAndBrackets, SyntaxTokenKind.Plain, "[u]")
        assertFirstKind(t1, dialectParamsAndBrackets, SyntaxTokenKind.Property, "[Id]")
        assertFirstKind(t1, dialectParamsAndBrackets, SyntaxTokenKind.Property, "[Users]")
        val aliasIdx = dialectParamsAndBrackets.indexOf("[Users] [u]") + "[Users] ".length
        assertFirstKind(t1, dialectParamsAndBrackets, SyntaxTokenKind.Plain, "[u]") { it.startIndex == aliasIdx }
        val arrIdx = dialectParamsAndBrackets.indexOf("arr[1]")
        assertFirstKind(t1, dialectParamsAndBrackets, SyntaxTokenKind.Punctuation, "[") { it.startIndex > arrIdx }

        val hashDisambiguation = """
            #-- separator comment
            CREATE TABLE #users (id INT);
            SELECT * FROM ##global_users WHERE id = 1;
            SELECT 5 # 3 AS x;
            SELECT data
              #> '{a,b}' FROM tbl;
            SELECT 1; #-- inline after semicolon
        """.trimIndent()
        val t2 = assertValidSnippetHasNoInvalidTokens(SqlSyntaxLexer, hashDisambiguation, "sql-hash-disambiguation")
        assertFirstKind(t2, hashDisambiguation, SyntaxTokenKind.Comment, "#-- separator comment")
        assertFirstKind(t2, hashDisambiguation, SyntaxTokenKind.Plain, "#users")
        assertFirstKind(t2, hashDisambiguation, SyntaxTokenKind.Plain, "##global_users")
        assertFirstKind(t2, hashDisambiguation, SyntaxTokenKind.Operator, "#")
        assertFirstKind(t2, hashDisambiguation, SyntaxTokenKind.Number, "3")
        assertFirstKind(t2, hashDisambiguation, SyntaxTokenKind.Operator, "#>")
        assertFirstKind(t2, hashDisambiguation, SyntaxTokenKind.Comment, "#-- inline after semicolon")
    }

    @Test
    fun handlesMysqlBackslashEscapedQuotesAndPlSqlRanges() {
        val escapedStatements = """
            SELECT 'it\'s'; SELECT 1;
            SELECT N'it\'s'; SELECT 2;
            SELECT "it\"s"; SELECT 3;
            SELECT 'a\'b''c\\'; SELECT 4;
            SELECT 'users\' name'; SELECT 5;
            SELECT 'foo\''; SELECT 6;
            SELECT 'a\''b'; SELECT 7;
            SELECT 'f(\'x\')' AS a, 1;
            SELECT 'say \'yes\', ok' AS msg, 2;
            SELECT '<a href=\'/x\'>' AS html, 3;
            SELECT 'it\'s\'' AS quoted, 4;
            SELECT 'users\' names and roles' AS prose1, 5;
            SELECT 'users\' names, roles' AS prose2, 6;
            INSERT INTO t VALUES ('the girls\' team), (x)');
            SELECT 'the teachers\' notes for today', 'players\' scores from last season.', 'the boys\' door left open.', 'users\' names where possible!', 'users\' and roles', 'Customers\' return policy?', 'kids\' at play.', 'users\' note:', 'customers\' orders from plan B', 'users\' notes for vitamin E', 'owners\' manual for Model X';
        """.trimIndent()
        val escapedTokens = assertValidSnippetHasNoInvalidTokens(SqlSyntaxLexer, escapedStatements, "sql-mysql-escapes")
        val nonWsEscaped = escapedTokens
            .filter { it.kind != SyntaxTokenKind.Whitespace }
            .map { it.kind to it.slice(escapedStatements) }
        assertEquals(
            listOf(
                SyntaxTokenKind.Keyword to "SELECT",
                SyntaxTokenKind.String to "'it\\'s'",
                SyntaxTokenKind.Punctuation to ";",
                SyntaxTokenKind.Keyword to "SELECT",
                SyntaxTokenKind.Number to "1",
                SyntaxTokenKind.Punctuation to ";",
                SyntaxTokenKind.Keyword to "SELECT",
                SyntaxTokenKind.String to "N'it\\'s'",
                SyntaxTokenKind.Punctuation to ";",
                SyntaxTokenKind.Keyword to "SELECT",
                SyntaxTokenKind.Number to "2",
                SyntaxTokenKind.Punctuation to ";",
                SyntaxTokenKind.Keyword to "SELECT",
                SyntaxTokenKind.String to "\"it\\\"s\"",
                SyntaxTokenKind.Punctuation to ";",
                SyntaxTokenKind.Keyword to "SELECT",
                SyntaxTokenKind.Number to "3",
                SyntaxTokenKind.Punctuation to ";",
                SyntaxTokenKind.Keyword to "SELECT",
                SyntaxTokenKind.String to "'a\\'b''c\\\\'",
                SyntaxTokenKind.Punctuation to ";",
                SyntaxTokenKind.Keyword to "SELECT",
                SyntaxTokenKind.Number to "4",
                SyntaxTokenKind.Punctuation to ";",
                SyntaxTokenKind.Keyword to "SELECT",
                SyntaxTokenKind.String to "'users\\' name'",
                SyntaxTokenKind.Punctuation to ";",
                SyntaxTokenKind.Keyword to "SELECT",
                SyntaxTokenKind.Number to "5",
                SyntaxTokenKind.Punctuation to ";",
                SyntaxTokenKind.Keyword to "SELECT",
                SyntaxTokenKind.String to "'foo\\''",
                SyntaxTokenKind.Punctuation to ";",
                SyntaxTokenKind.Keyword to "SELECT",
                SyntaxTokenKind.Number to "6",
                SyntaxTokenKind.Punctuation to ";",
                SyntaxTokenKind.Keyword to "SELECT",
                SyntaxTokenKind.String to "'a\\''b'",
                SyntaxTokenKind.Punctuation to ";",
                SyntaxTokenKind.Keyword to "SELECT",
                SyntaxTokenKind.Number to "7",
                SyntaxTokenKind.Punctuation to ";",
                SyntaxTokenKind.Keyword to "SELECT",
                SyntaxTokenKind.String to "'f(\\'x\\')'",
                SyntaxTokenKind.Keyword to "AS",
                SyntaxTokenKind.Plain to "a",
                SyntaxTokenKind.Punctuation to ",",
                SyntaxTokenKind.Number to "1",
                SyntaxTokenKind.Punctuation to ";",
                SyntaxTokenKind.Keyword to "SELECT",
                SyntaxTokenKind.String to "'say \\'yes\\', ok'",
                SyntaxTokenKind.Keyword to "AS",
                SyntaxTokenKind.Plain to "msg",
                SyntaxTokenKind.Punctuation to ",",
                SyntaxTokenKind.Number to "2",
                SyntaxTokenKind.Punctuation to ";",
                SyntaxTokenKind.Keyword to "SELECT",
                SyntaxTokenKind.String to "'<a href=\\'/x\\'>'",
                SyntaxTokenKind.Keyword to "AS",
                SyntaxTokenKind.Plain to "html",
                SyntaxTokenKind.Punctuation to ",",
                SyntaxTokenKind.Number to "3",
                SyntaxTokenKind.Punctuation to ";",
                SyntaxTokenKind.Keyword to "SELECT",
                SyntaxTokenKind.String to "'it\\'s\\''",
                SyntaxTokenKind.Keyword to "AS",
                SyntaxTokenKind.Plain to "quoted",
                SyntaxTokenKind.Punctuation to ",",
                SyntaxTokenKind.Number to "4",
                SyntaxTokenKind.Punctuation to ";",
                SyntaxTokenKind.Keyword to "SELECT",
                SyntaxTokenKind.String to "'users\\' names and roles'",
                SyntaxTokenKind.Keyword to "AS",
                SyntaxTokenKind.Plain to "prose1",
                SyntaxTokenKind.Punctuation to ",",
                SyntaxTokenKind.Number to "5",
                SyntaxTokenKind.Punctuation to ";",
                SyntaxTokenKind.Keyword to "SELECT",
                SyntaxTokenKind.String to "'users\\' names, roles'",
                SyntaxTokenKind.Keyword to "AS",
                SyntaxTokenKind.Plain to "prose2",
                SyntaxTokenKind.Punctuation to ",",
                SyntaxTokenKind.Number to "6",
                SyntaxTokenKind.Punctuation to ";",
                SyntaxTokenKind.Keyword to "INSERT",
                SyntaxTokenKind.Keyword to "INTO",
                SyntaxTokenKind.Plain to "t",
                SyntaxTokenKind.Keyword to "VALUES",
                SyntaxTokenKind.Punctuation to "(",
                SyntaxTokenKind.String to "'the girls\\' team), (x)'",
                SyntaxTokenKind.Punctuation to ")",
                SyntaxTokenKind.Punctuation to ";",
                SyntaxTokenKind.Keyword to "SELECT",
                SyntaxTokenKind.String to "'the teachers\\' notes for today'",
                SyntaxTokenKind.Punctuation to ",",
                SyntaxTokenKind.String to "'players\\' scores from last season.'",
                SyntaxTokenKind.Punctuation to ",",
                SyntaxTokenKind.String to "'the boys\\' door left open.'",
                SyntaxTokenKind.Punctuation to ",",
                SyntaxTokenKind.String to "'users\\' names where possible!'",
                SyntaxTokenKind.Punctuation to ",",
                SyntaxTokenKind.String to "'users\\' and roles'",
                SyntaxTokenKind.Punctuation to ",",
                SyntaxTokenKind.String to "'Customers\\' return policy?'",
                SyntaxTokenKind.Punctuation to ",",
                SyntaxTokenKind.String to "'kids\\' at play.'",
                SyntaxTokenKind.Punctuation to ",",
                SyntaxTokenKind.String to "'users\\' note:'",
                SyntaxTokenKind.Punctuation to ",",
                SyntaxTokenKind.String to "'customers\\' orders from plan B'",
                SyntaxTokenKind.Punctuation to ",",
                SyntaxTokenKind.String to "'users\\' notes for vitamin E'",
                SyntaxTokenKind.Punctuation to ",",
                SyntaxTokenKind.String to "'owners\\' manual for Model X'",
                SyntaxTokenKind.Punctuation to ";",
            ),
            nonWsEscaped,
        )

        val multiLineProseAndAlias = "SELECT 'users\\' data\nmore text' AS ml1, 'users\\' data\nfrom yesterday' AS ml2;\nSELECT 'C:\\' path\nFROM t;\nSELECT 'C:\\' AS dir, \$\$it's\nmore\$\$;\nSELECT 'C:\\' AS p /* the user's\n dir */;\n"
        val mlProseTokens = assertValidSnippetHasNoInvalidTokens(SqlSyntaxLexer, multiLineProseAndAlias, "sql-ml-prose-alias")
        assertEquals(
            listOf(
                SyntaxTokenKind.Keyword to "SELECT",
                SyntaxTokenKind.String to "'users\\' data\nmore text'",
                SyntaxTokenKind.Keyword to "AS",
                SyntaxTokenKind.Plain to "ml1",
                SyntaxTokenKind.Punctuation to ",",
                SyntaxTokenKind.String to "'users\\' data\nfrom yesterday'",
                SyntaxTokenKind.Keyword to "AS",
                SyntaxTokenKind.Plain to "ml2",
                SyntaxTokenKind.Punctuation to ";",
                SyntaxTokenKind.Keyword to "SELECT",
                SyntaxTokenKind.String to "'C:\\'",
                SyntaxTokenKind.Plain to "path",
                SyntaxTokenKind.Keyword to "FROM",
                SyntaxTokenKind.Plain to "t",
                SyntaxTokenKind.Punctuation to ";",
                SyntaxTokenKind.Keyword to "SELECT",
                SyntaxTokenKind.String to "'C:\\'",
                SyntaxTokenKind.Keyword to "AS",
                SyntaxTokenKind.Plain to "dir",
                SyntaxTokenKind.Punctuation to ",",
                SyntaxTokenKind.String to "\$\$it's\nmore\$\$",
                SyntaxTokenKind.Punctuation to ";",
                SyntaxTokenKind.Keyword to "SELECT",
                SyntaxTokenKind.String to "'C:\\'",
                SyntaxTokenKind.Keyword to "AS",
                SyntaxTokenKind.Plain to "p",
                SyntaxTokenKind.Comment to "/* the user's\n dir */",
                SyntaxTokenKind.Punctuation to ";",
            ),
            mlProseTokens.filter { it.kind != SyntaxTokenKind.Whitespace }.map { it.kind to it.slice(multiLineProseAndAlias) },
        )

        val multiLineMysqlEscape = "SELECT 'run \\'echo\\'\\nnext' AS s, 5;\nSELECT 'run \\'echo\\'\nnext' AS s2, 6;"
        val multiLineTokens = assertValidSnippetHasNoInvalidTokens(SqlSyntaxLexer, multiLineMysqlEscape, "sql-multiline-mysql")
        assertEquals(
            listOf(
                SyntaxTokenKind.Keyword to "SELECT",
                SyntaxTokenKind.String to "'run \\'echo\\'\\nnext'",
                SyntaxTokenKind.Keyword to "AS",
                SyntaxTokenKind.Plain to "s",
                SyntaxTokenKind.Punctuation to ",",
                SyntaxTokenKind.Number to "5",
                SyntaxTokenKind.Punctuation to ";",
                SyntaxTokenKind.Keyword to "SELECT",
                SyntaxTokenKind.String to "'run \\'echo\\'\nnext'",
                SyntaxTokenKind.Keyword to "AS",
                SyntaxTokenKind.Plain to "s2",
                SyntaxTokenKind.Punctuation to ",",
                SyntaxTokenKind.Number to "6",
                SyntaxTokenKind.Punctuation to ";",
            ),
            multiLineTokens.filter { it.kind != SyntaxTokenKind.Whitespace }.map { it.kind to it.slice(multiLineMysqlEscape) },
        )

        val standardSqlTrailingBackslashes = """
            SELECT REPLACE(path, '\', '/') FROM t WHERE x = 'y';
            SELECT 'C:\Backup\' FROM t WHERE x = 'y';
            SELECT 'C:\', 'D:\' || 'file.txt', 'C:\'::text;
            SET @path = 'C:\Backup\'+@db+'.bak';
            SET @npath = N'C:\'+@x;
            SELECT * FROM t ORDER BY '\' DESC;
            SELECT 'C:\' path; SELECT 1;
            SELECT 'C:\' dir FROM t WHERE x = 'y';
            SELECT 'C:\' p1, 'D:\' p2;
            SELECT 'C:\' AS dir, ';' AS sep, SPLIT_PART(x, ',', 1);
            SELECT 'C:\Backup\' AS backup_dir -- the user's folder
            SELECT 'C:\' AS [user's dir];
            SELECT 'C:\' AS p; -- don't
            SELECT 'C:\' AS p1 /* user's dir */, 'a--b' AS s, 'C:\' AS p2 -- don't
            SELECT 'C:\' AS dir, STRING_AGG(name, ', ') AS names FROM t;
            SELECT 'C:\' AS "user's dir";
            SELECT 'C:\' AS a, 'x' AS "it's", 'D:\' AS c -- don't
            SELECT 'C:\' AS a, 'it\'s' AS b, 'D:\' AS c -- don't
            SELECT 'C:\' AS dir, ${'$'}${'$'}it's ok${'$'}${'$'};
            SELECT "C:\" AS p, 'a"b';
            SELECT "col\", "other" FROM t;
        """.trimIndent()
        val trailingBsTokens = assertValidSnippetHasNoInvalidTokens(
            SqlSyntaxLexer,
            standardSqlTrailingBackslashes,
            "sql-standard-trailing-backslash",
        )
        assertEquals(
            listOf(
                SyntaxTokenKind.Keyword to "SELECT",
                SyntaxTokenKind.Function to "REPLACE",
                SyntaxTokenKind.Punctuation to "(",
                SyntaxTokenKind.Plain to "path",
                SyntaxTokenKind.Punctuation to ",",
                SyntaxTokenKind.String to "'\\'",
                SyntaxTokenKind.Punctuation to ",",
                SyntaxTokenKind.String to "'/'",
                SyntaxTokenKind.Punctuation to ")",
                SyntaxTokenKind.Keyword to "FROM",
                SyntaxTokenKind.Plain to "t",
                SyntaxTokenKind.Keyword to "WHERE",
                SyntaxTokenKind.Plain to "x",
                SyntaxTokenKind.Operator to "=",
                SyntaxTokenKind.String to "'y'",
                SyntaxTokenKind.Punctuation to ";",
                SyntaxTokenKind.Keyword to "SELECT",
                SyntaxTokenKind.String to "'C:\\Backup\\'",
                SyntaxTokenKind.Keyword to "FROM",
                SyntaxTokenKind.Plain to "t",
                SyntaxTokenKind.Keyword to "WHERE",
                SyntaxTokenKind.Plain to "x",
                SyntaxTokenKind.Operator to "=",
                SyntaxTokenKind.String to "'y'",
                SyntaxTokenKind.Punctuation to ";",
                SyntaxTokenKind.Keyword to "SELECT",
                SyntaxTokenKind.String to "'C:\\'",
                SyntaxTokenKind.Punctuation to ",",
                SyntaxTokenKind.String to "'D:\\'",
                SyntaxTokenKind.Operator to "||",
                SyntaxTokenKind.String to "'file.txt'",
                SyntaxTokenKind.Punctuation to ",",
                SyntaxTokenKind.String to "'C:\\'",
                SyntaxTokenKind.Operator to "::",
                SyntaxTokenKind.Type to "text",
                SyntaxTokenKind.Punctuation to ";",
                SyntaxTokenKind.Keyword to "SET",
                SyntaxTokenKind.Plain to "@path",
                SyntaxTokenKind.Operator to "=",
                SyntaxTokenKind.String to "'C:\\Backup\\'",
                SyntaxTokenKind.Operator to "+",
                SyntaxTokenKind.Plain to "@db",
                SyntaxTokenKind.Operator to "+",
                SyntaxTokenKind.String to "'.bak'",
                SyntaxTokenKind.Punctuation to ";",
                SyntaxTokenKind.Keyword to "SET",
                SyntaxTokenKind.Plain to "@npath",
                SyntaxTokenKind.Operator to "=",
                SyntaxTokenKind.String to "N'C:\\'",
                SyntaxTokenKind.Operator to "+",
                SyntaxTokenKind.Plain to "@x",
                SyntaxTokenKind.Punctuation to ";",
                SyntaxTokenKind.Keyword to "SELECT",
                SyntaxTokenKind.Operator to "*",
                SyntaxTokenKind.Keyword to "FROM",
                SyntaxTokenKind.Plain to "t",
                SyntaxTokenKind.Keyword to "ORDER",
                SyntaxTokenKind.Keyword to "BY",
                SyntaxTokenKind.String to "'\\'",
                SyntaxTokenKind.Keyword to "DESC",
                SyntaxTokenKind.Punctuation to ";",
                SyntaxTokenKind.Keyword to "SELECT",
                SyntaxTokenKind.String to "'C:\\'",
                SyntaxTokenKind.Plain to "path",
                SyntaxTokenKind.Punctuation to ";",
                SyntaxTokenKind.Keyword to "SELECT",
                SyntaxTokenKind.Number to "1",
                SyntaxTokenKind.Punctuation to ";",
                SyntaxTokenKind.Keyword to "SELECT",
                SyntaxTokenKind.String to "'C:\\'",
                SyntaxTokenKind.Plain to "dir",
                SyntaxTokenKind.Keyword to "FROM",
                SyntaxTokenKind.Plain to "t",
                SyntaxTokenKind.Keyword to "WHERE",
                SyntaxTokenKind.Plain to "x",
                SyntaxTokenKind.Operator to "=",
                SyntaxTokenKind.String to "'y'",
                SyntaxTokenKind.Punctuation to ";",
                SyntaxTokenKind.Keyword to "SELECT",
                SyntaxTokenKind.String to "'C:\\'",
                SyntaxTokenKind.Plain to "p1",
                SyntaxTokenKind.Punctuation to ",",
                SyntaxTokenKind.String to "'D:\\'",
                SyntaxTokenKind.Plain to "p2",
                SyntaxTokenKind.Punctuation to ";",
                SyntaxTokenKind.Keyword to "SELECT",
                SyntaxTokenKind.String to "'C:\\'",
                SyntaxTokenKind.Keyword to "AS",
                SyntaxTokenKind.Plain to "dir",
                SyntaxTokenKind.Punctuation to ",",
                SyntaxTokenKind.String to "';'",
                SyntaxTokenKind.Keyword to "AS",
                SyntaxTokenKind.Plain to "sep",
                SyntaxTokenKind.Punctuation to ",",
                SyntaxTokenKind.Function to "SPLIT_PART",
                SyntaxTokenKind.Punctuation to "(",
                SyntaxTokenKind.Plain to "x",
                SyntaxTokenKind.Punctuation to ",",
                SyntaxTokenKind.String to "','",
                SyntaxTokenKind.Punctuation to ",",
                SyntaxTokenKind.Number to "1",
                SyntaxTokenKind.Punctuation to ")",
                SyntaxTokenKind.Punctuation to ";",
                SyntaxTokenKind.Keyword to "SELECT",
                SyntaxTokenKind.String to "'C:\\Backup\\'",
                SyntaxTokenKind.Keyword to "AS",
                SyntaxTokenKind.Plain to "backup_dir",
                SyntaxTokenKind.Comment to "-- the user's folder",
                SyntaxTokenKind.Keyword to "SELECT",
                SyntaxTokenKind.String to "'C:\\'",
                SyntaxTokenKind.Keyword to "AS",
                SyntaxTokenKind.Plain to "[user's dir]",
                SyntaxTokenKind.Punctuation to ";",
                SyntaxTokenKind.Keyword to "SELECT",
                SyntaxTokenKind.String to "'C:\\'",
                SyntaxTokenKind.Keyword to "AS",
                SyntaxTokenKind.Plain to "p",
                SyntaxTokenKind.Punctuation to ";",
                SyntaxTokenKind.Comment to "-- don't",
                SyntaxTokenKind.Keyword to "SELECT",
                SyntaxTokenKind.String to "'C:\\'",
                SyntaxTokenKind.Keyword to "AS",
                SyntaxTokenKind.Plain to "p1",
                SyntaxTokenKind.Comment to "/* user's dir */",
                SyntaxTokenKind.Punctuation to ",",
                SyntaxTokenKind.String to "'a--b'",
                SyntaxTokenKind.Keyword to "AS",
                SyntaxTokenKind.Plain to "s",
                SyntaxTokenKind.Punctuation to ",",
                SyntaxTokenKind.String to "'C:\\'",
                SyntaxTokenKind.Keyword to "AS",
                SyntaxTokenKind.Plain to "p2",
                SyntaxTokenKind.Comment to "-- don't",
                SyntaxTokenKind.Keyword to "SELECT",
                SyntaxTokenKind.String to "'C:\\'",
                SyntaxTokenKind.Keyword to "AS",
                SyntaxTokenKind.Plain to "dir",
                SyntaxTokenKind.Punctuation to ",",
                SyntaxTokenKind.Function to "STRING_AGG",
                SyntaxTokenKind.Punctuation to "(",
                SyntaxTokenKind.Plain to "name",
                SyntaxTokenKind.Punctuation to ",",
                SyntaxTokenKind.String to "', '",
                SyntaxTokenKind.Punctuation to ")",
                SyntaxTokenKind.Keyword to "AS",
                SyntaxTokenKind.Plain to "names",
                SyntaxTokenKind.Keyword to "FROM",
                SyntaxTokenKind.Plain to "t",
                SyntaxTokenKind.Punctuation to ";",
                SyntaxTokenKind.Keyword to "SELECT",
                SyntaxTokenKind.String to "'C:\\'",
                SyntaxTokenKind.Keyword to "AS",
                SyntaxTokenKind.String to "\"user's dir\"",
                SyntaxTokenKind.Punctuation to ";",
                SyntaxTokenKind.Keyword to "SELECT",
                SyntaxTokenKind.String to "'C:\\'",
                SyntaxTokenKind.Keyword to "AS",
                SyntaxTokenKind.Plain to "a",
                SyntaxTokenKind.Punctuation to ",",
                SyntaxTokenKind.String to "'x'",
                SyntaxTokenKind.Keyword to "AS",
                SyntaxTokenKind.String to "\"it's\"",
                SyntaxTokenKind.Punctuation to ",",
                SyntaxTokenKind.String to "'D:\\'",
                SyntaxTokenKind.Keyword to "AS",
                SyntaxTokenKind.Plain to "c",
                SyntaxTokenKind.Comment to "-- don't",
                SyntaxTokenKind.Keyword to "SELECT",
                SyntaxTokenKind.String to "'C:\\'",
                SyntaxTokenKind.Keyword to "AS",
                SyntaxTokenKind.Plain to "a",
                SyntaxTokenKind.Punctuation to ",",
                SyntaxTokenKind.String to "'it\\'s'",
                SyntaxTokenKind.Keyword to "AS",
                SyntaxTokenKind.Plain to "b",
                SyntaxTokenKind.Punctuation to ",",
                SyntaxTokenKind.String to "'D:\\'",
                SyntaxTokenKind.Keyword to "AS",
                SyntaxTokenKind.Plain to "c",
                SyntaxTokenKind.Comment to "-- don't",
                SyntaxTokenKind.Keyword to "SELECT",
                SyntaxTokenKind.String to "'C:\\'",
                SyntaxTokenKind.Keyword to "AS",
                SyntaxTokenKind.Plain to "dir",
                SyntaxTokenKind.Punctuation to ",",
                SyntaxTokenKind.String to "${'$'}${'$'}it's ok${'$'}${'$'}",
                SyntaxTokenKind.Punctuation to ";",
                SyntaxTokenKind.Keyword to "SELECT",
                SyntaxTokenKind.String to "\"C:\\\"",
                SyntaxTokenKind.Keyword to "AS",
                SyntaxTokenKind.Plain to "p",
                SyntaxTokenKind.Punctuation to ",",
                SyntaxTokenKind.String to "'a\"b'",
                SyntaxTokenKind.Punctuation to ";",
                SyntaxTokenKind.Keyword to "SELECT",
                SyntaxTokenKind.String to "\"col\\\"",
                SyntaxTokenKind.Punctuation to ",",
                SyntaxTokenKind.String to "\"other\"",
                SyntaxTokenKind.Keyword to "FROM",
                SyntaxTokenKind.Plain to "t",
                SyntaxTokenKind.Punctuation to ";",
            ),
            trailingBsTokens
                .filter { it.kind != SyntaxTokenKind.Whitespace }
                .map { it.kind to it.slice(standardSqlTrailingBackslashes) },
        )

        for (unterminated in listOf("SELECT 'unterminated\\", "SELECT N'unterminated\\", "SELECT \"unterminated\\")) {
            val tokens = assertValidSnippetHasNoInvalidTokens(SqlSyntaxLexer, unterminated, "sql-unterminated-bs")
            assertEquals(SyntaxTokenKind.String, tokens.last().kind)
        }

        val rangesAndFloats = "FOR i IN 1..10 LOOP SELECT 1. + 1.e5 + 1.E-3 + 1.5 + .5 + 1.5..10 + 1..10e2 + 1.;"
        val rangeTokens = assertValidSnippetHasNoInvalidTokens(SqlSyntaxLexer, rangesAndFloats, "sql-plsql-ranges")
        val nonWsRanges = rangeTokens
            .filter { it.kind != SyntaxTokenKind.Whitespace }
            .map { it.kind to it.slice(rangesAndFloats) }
        assertEquals(
            listOf(
                SyntaxTokenKind.Keyword to "FOR",
                SyntaxTokenKind.Plain to "i",
                SyntaxTokenKind.Keyword to "IN",
                SyntaxTokenKind.Number to "1",
                SyntaxTokenKind.Operator to "..",
                SyntaxTokenKind.Number to "10",
                SyntaxTokenKind.Keyword to "LOOP",
                SyntaxTokenKind.Keyword to "SELECT",
                SyntaxTokenKind.Number to "1.",
                SyntaxTokenKind.Operator to "+",
                SyntaxTokenKind.Number to "1.e5",
                SyntaxTokenKind.Operator to "+",
                SyntaxTokenKind.Number to "1.E-3",
                SyntaxTokenKind.Operator to "+",
                SyntaxTokenKind.Number to "1.5",
                SyntaxTokenKind.Operator to "+",
                SyntaxTokenKind.Number to ".5",
                SyntaxTokenKind.Operator to "+",
                SyntaxTokenKind.Number to "1.5",
                SyntaxTokenKind.Operator to "..",
                SyntaxTokenKind.Number to "10",
                SyntaxTokenKind.Operator to "+",
                SyntaxTokenKind.Number to "1",
                SyntaxTokenKind.Operator to "..",
                SyntaxTokenKind.Number to "10e2",
                SyntaxTokenKind.Operator to "+",
                SyntaxTokenKind.Number to "1.",
                SyntaxTokenKind.Punctuation to ";",
            ),
            nonWsRanges,
        )

        val malformedFloat = "SELECT 1.e+;"
        val malformedTokens = assertValidSnippetHasNoInvalidTokens(SqlSyntaxLexer, malformedFloat, "sql-malformed-exp")
        assertEquals(
            listOf(
                SyntaxTokenKind.Keyword to "SELECT",
                SyntaxTokenKind.Number to "1.",
                SyntaxTokenKind.Plain to "e",
                SyntaxTokenKind.Operator to "+",
                SyntaxTokenKind.Punctuation to ";",
            ),
            malformedTokens.filter { it.kind != SyntaxTokenKind.Whitespace }.map { it.kind to it.slice(malformedFloat) },
        )
    }

    @Test
    fun handlesUnterminatedContainersBomAndSupplementaryUnicodeLosslessly() {
        val unterminatedDollar = "SELECT ${'$'}tag${'$'}unclosed body"
        val tokens1 = assertLosslessInvariants(SqlSyntaxLexer, unterminatedDollar, "sql-unclosed-dollar")
        assertEquals(SyntaxTokenKind.String, tokens1.last().kind)

        val unterminatedComment = "SELECT 1 /* outer /* inner"
        val tokens2 = assertLosslessInvariants(SqlSyntaxLexer, unterminatedComment, "sql-unclosed-comment")
        assertEquals(SyntaxTokenKind.Comment, tokens2.last().kind)

        val unicodeCode = "\uFEFF-- comment 🎉\r\nSELECT 𠮷 FROM \"tbl_🎉\";\r\n"
        val tokens3 = assertValidSnippetHasNoInvalidTokens(SqlSyntaxLexer, unicodeCode, "sql-unicode")
        assertEquals(SyntaxTokenKind.Whitespace, tokens3.first().kind)
        assertEquals(SyntaxTokenKind.Comment, tokens3.first { it.slice(unicodeCode) == "-- comment 🎉" }.kind)
        assertEquals(SyntaxTokenKind.Plain, tokens3.first { it.slice(unicodeCode) == "𠮷" }.kind)
    }

    @Test
    fun prefixTruncationMalformedAndFuzzAreLossless() {
        val sample = """
            /* outer /* inner */ */
            SELECT u.id, ${'$'}tag${'$'}hello${'$'}tag${'$'}, [Col Name], arr[1]
            FROM users u WHERE u.name = E'a\nb';
        """.trimIndent()
        assertPrefixTruncationSafety(SqlSyntaxLexer, sample)
        assertMalformedAndFuzzSafety(SqlSyntaxLexer, seed = 0x53514C31L, iterations = 60)
    }

    @Test
    fun linearPerformanceOnPathologicalInputs() {
        val manyStatements = buildString {
            repeat(2_500) { idx ->
                append("SELECT col_").append(idx).append(" FROM tbl_").append(idx).append(" WHERE id = ").append(idx).append(";\n")
            }
        }
        val hugeDollar = "SELECT ${'$'}longtag${'$'}" + "abc${'$'}other${'$'}def".repeat(4_000) + "${'$'}longtag${'$'};"
        val deepNestedComment = "/* ".repeat(500) + "body" + " */".repeat(500) + " SELECT 1;"
        val unbalancedBrackets = "SELECT " + "[".repeat(16_000)
        val nestedArrayBrackets = "SELECT " + "arr[".repeat(8_000) + "1]"
        val manyRanges = "SELECT " + "1..2,".repeat(8_000) + "3;"
        val manyBackslashQuotes = "SELECT '" + "\\'   ".repeat(8_000) + "';"
        val wideSpaceAfterBackslashQuote = "SELECT '\\'" + " ".repeat(16_000) + "x';"
        val manyImplicitAliasesSameLine = "SELECT " + (1..2_000).joinToString(", ") { "'C:\\' p$it" } + ";"
        val unclosedContainersInProse = "SELECT " + "'a\\' and [x', ".repeat(2_000) + "1;"
        val closedBracketsInDoubleQuoteEscape = "SELECT '" + "\\'' AND [".repeat(4_000) + "x] y';"
        val nestedBlockCommentsInDoubleQuoteEscape = "SELECT '" + "\\'' AND /*".repeat(2_000) + " */".repeat(2_000) + " y';"

        assertLinearPerformance(
            lexer = SqlSyntaxLexer,
            pathologicalInputs = listOf(
                "many-statements" to manyStatements,
                "huge-dollar-string" to hugeDollar,
                "deep-nested-comment" to deepNestedComment,
                "unbalanced-brackets" to unbalancedBrackets,
                "nested-array-brackets" to nestedArrayBrackets,
                "many-ranges" to manyRanges,
                "many-backslash-quotes" to manyBackslashQuotes,
                "wide-space-after-backslash-quote" to wideSpaceAfterBackslashQuote,
                "many-implicit-aliases-same-line" to manyImplicitAliasesSameLine,
                "unclosed-containers-in-prose" to unclosedContainersInProse,
                "closed-brackets-in-double-quote-escape" to closedBracketsInDoubleQuoteEscape,
                "nested-block-comments-in-double-quote-escape" to nestedBlockCommentsInDoubleQuoteEscape,
            ),
        )
    }

    @Test
    fun matchesDualOracleFixtures() {
        for (fixture in sqlOracleFixtures) {
            assertMatchesOracleFixture(fixture)
        }
    }
}
