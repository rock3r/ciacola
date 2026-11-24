package dev.sebastiano.ciacola.syntax.lexer

import dev.sebastiano.ciacola.syntax.SyntaxLanguage
import dev.sebastiano.ciacola.syntax.SyntaxLexer
import dev.sebastiano.ciacola.syntax.SyntaxToken
import dev.sebastiano.ciacola.syntax.SyntaxTokenKind
import dev.sebastiano.ciacola.syntax.antlr.sql.SqlKeywords
import dev.sebastiano.ciacola.syntax.antlr.sql.SqlLexer
import dev.sebastiano.ciacola.syntax.antlr.sql.SqlLexer.Tokens as SqlTokens
import dev.sebastiano.ciacola.syntax.internal.antlrCharStream
import dev.sebastiano.ciacola.syntax.internal.driveAntlrLexer
import dev.sebastiano.ciacola.syntax.internal.emitDefaultGap

/**
 * Lossless ANTLR4 DFA-based lexical tokenizer for SQL (ANSI SQL, PostgreSQL, MySQL, SQLite,
 * T-SQL, and PL/SQL).
 */
internal object SqlSyntaxLexer : SyntaxLexer {
    override val language: SyntaxLanguage = SyntaxLanguage.Sql

    override fun lex(source: String): List<SyntaxToken> {
        if (source.isEmpty()) return emptyList()

        val lexer = SqlLexer(antlrCharStream(source))
        val rawTypes = ArrayList<Int>(source.length / 4 + 8)
        val rawStarts = ArrayList<Int>(source.length / 4 + 8)
        val rawEnds = ArrayList<Int>(source.length / 4 + 8)

        driveAntlrLexer(
            source = source,
            lexer = lexer,
            onGap = { start, end ->
                emitDefaultGap(source, start, end) { kind, s, e ->
                    rawTypes += if (kind == SyntaxTokenKind.Whitespace) SqlTokens.WS else SqlTokens.ERROR_CHAR
                    rawStarts += s
                    rawEnds += e
                }
            },
            onToken = { type, start, end ->
                rawTypes += type
                rawStarts += start
                rawEnds += end
            },
        )

        val count = rawTypes.size
        val out = ArrayList<SyntaxToken>(count)
        var prevNonHiddenWasDot = false
        var prevDdlIntroducer = false

        for (i in 0 until count) {
            val type = rawTypes[i]
            val start = rawStarts[i]
            val end = rawEnds[i]
            val kind = when (type) {
                SqlTokens.WS -> SyntaxTokenKind.Whitespace

                SqlTokens.LINE_COMMENT,
                SqlTokens.BLOCK_COMMENT,
                SqlTokens.HASH_LINE_COMMENT,
                SqlTokens.HASH_COMMENT,
                -> SyntaxTokenKind.Comment

                SqlTokens.DOLLAR_STRING,
                SqlTokens.PG_ESCAPE_STRING,
                SqlTokens.UNTERMINATED_PG_ESCAPE_STRING,
                SqlTokens.ORACLE_QUOTE_STRING,
                SqlTokens.NATIONAL_STRING,
                SqlTokens.BIT_STRING,
                SqlTokens.UNTERMINATED_BIT_STRING,
                SqlTokens.HEX_STRING,
                SqlTokens.UNTERMINATED_HEX_STRING,
                SqlTokens.STRING,
                -> SyntaxTokenKind.String

                SqlTokens.DOUBLE_QUOTED_IDENT,
                SqlTokens.BACKTICK_IDENT,
                SqlTokens.UNTERMINATED_BACKTICK_IDENT,
                -> if (prevNonHiddenWasDot && !isFollowedByOpenParen(rawTypes, i + 1)) {
                    SyntaxTokenKind.Property
                } else {
                    SyntaxTokenKind.String
                }

                SqlTokens.BRACKET_IDENT -> if (prevNonHiddenWasDot && !isFollowedByOpenParen(rawTypes, i + 1)) {
                    SyntaxTokenKind.Property
                } else {
                    SyntaxTokenKind.Plain
                }

                SqlTokens.HEX_NUMBER,
                SqlTokens.FLOAT_NUMBER,
                SqlTokens.INT_NUMBER,
                -> SyntaxTokenKind.Number

                SqlTokens.PG_HASH_PATH_TEXT,
                SqlTokens.PG_HASH_PATH,
                SqlTokens.PG_HASH_DELETE,
                SqlTokens.HASH_XOR,
                SqlTokens.DOUBLE_COLON,
                SqlTokens.COLON_EQ,
                SqlTokens.FAT_ARROW,
                SqlTokens.JSON_EXTRACT_TEXT,
                SqlTokens.JSON_EXTRACT,
                SqlTokens.PG_CONTAINS,
                SqlTokens.PG_CONTAINED_BY,
                SqlTokens.PG_JSON_EXISTS_ALL,
                SqlTokens.PG_JSON_EXISTS_ANY,
                SqlTokens.CONCAT_OP,
                SqlTokens.NEQ_ANGLE,
                SqlTokens.NEQ_BANG,
                SqlTokens.LEQ,
                SqlTokens.GEQ,
                SqlTokens.LSHIFT,
                SqlTokens.RSHIFT,
                SqlTokens.REGEX_NOT_ICASE,
                SqlTokens.REGEX_NOT_CASE,
                SqlTokens.REGEX_ICASE,
                SqlTokens.DOTDOT,
                SqlTokens.QUESTION_PARAM,
                SqlTokens.PLUS,
                SqlTokens.MINUS,
                SqlTokens.STAR,
                SqlTokens.SLASH,
                SqlTokens.PERCENT,
                SqlTokens.EQ,
                SqlTokens.LT,
                SqlTokens.GT,
                SqlTokens.AMP,
                SqlTokens.PIPE,
                SqlTokens.CARET,
                SqlTokens.TILDE,
                SqlTokens.BANG,
                -> SyntaxTokenKind.Operator

                SqlTokens.LPAREN,
                SqlTokens.RPAREN,
                SqlTokens.LBRACK,
                SqlTokens.RBRACK,
                SqlTokens.COMMA,
                SqlTokens.SEMI,
                SqlTokens.DOT,
                SqlTokens.COLON,
                -> SyntaxTokenKind.Punctuation

                SqlTokens.NAMED_PARAM,
                SqlTokens.AT_VAR,
                SqlTokens.POSITIONAL_PARAM,
                SqlTokens.DOLLAR_PARAM,
                SqlTokens.TEMP_TABLE_IDENT,
                -> SyntaxTokenKind.Plain

                SqlTokens.IDENT -> {
                    val upper = source.substring(start, end).uppercase()
                    val followedByParen = isFollowedByOpenParen(rawTypes, i + 1)
                    classifySqlIdentifier(
                        upper = upper,
                        followedByParen = followedByParen,
                        afterDot = prevNonHiddenWasDot,
                        afterDdlIntroducer = prevDdlIntroducer,
                    )
                }

                else -> SyntaxTokenKind.Invalid
            }

            out += SyntaxToken(kind = kind, startIndex = start, endIndex = end)

            if (!isHiddenSqlToken(type)) {
                val curUpper = if (type == SqlTokens.IDENT) source.substring(start, end).uppercase() else null
                val wasDot = type == SqlTokens.DOT
                val isDdlIntroducer = when {
                    curUpper != null && curUpper in SqlKeywords.ddlTargetIntroducers -> true
                    wasDot && prevDdlIntroducer -> true
                    curUpper in DDL_MODIFIER_WORDS && prevDdlIntroducer -> true
                    type == SqlTokens.IDENT && prevDdlIntroducer && isFollowedByDot(rawTypes, i + 1) -> true
                    else -> false
                }
                prevNonHiddenWasDot = wasDot
                prevDdlIntroducer = isDdlIntroducer
            }
        }
        return out
    }
}

private val DDL_MODIFIER_WORDS: Set<String> = hashSetOf(
    "IF",
    "NOT",
    "EXISTS",
)

private fun classifySqlIdentifier(
    upper: String,
    followedByParen: Boolean,
    afterDot: Boolean,
    afterDdlIntroducer: Boolean,
): SyntaxTokenKind {
    if (afterDot) {
        return when {
            followedByParen && !afterDdlIntroducer && upper !in SqlKeywords.types -> SyntaxTokenKind.Function
            else -> SyntaxTokenKind.Property
        }
    }
    if (upper in SqlKeywords.constants) return SyntaxTokenKind.Constant
    if (upper in SqlKeywords.types) return SyntaxTokenKind.Type
    if (followedByParen && upper in SqlKeywords.dualKeywordFunctions) return SyntaxTokenKind.Function
    if (upper in SqlKeywords.keywords) return SyntaxTokenKind.Keyword
    if (followedByParen) {
        return when {
            upper in SqlKeywords.builtinFunctions -> SyntaxTokenKind.Function
            afterDdlIntroducer -> SyntaxTokenKind.Plain
            else -> SyntaxTokenKind.Function
        }
    }
    return SyntaxTokenKind.Plain
}

private fun isHiddenSqlToken(type: Int): Boolean =
    type == SqlTokens.WS ||
        type == SqlTokens.LINE_COMMENT ||
        type == SqlTokens.BLOCK_COMMENT ||
        type == SqlTokens.HASH_LINE_COMMENT ||
        type == SqlTokens.HASH_COMMENT

private fun isFollowedByOpenParen(rawTypes: List<Int>, fromIndex: Int): Boolean =
    nextNonHiddenTokenType(rawTypes, fromIndex) == SqlTokens.LPAREN

private fun isFollowedByDot(rawTypes: List<Int>, fromIndex: Int): Boolean =
    nextNonHiddenTokenType(rawTypes, fromIndex) == SqlTokens.DOT

private fun nextNonHiddenTokenType(rawTypes: List<Int>, fromIndex: Int): Int {
    var j = fromIndex
    while (j < rawTypes.size) {
        val t = rawTypes[j]
        if (isHiddenSqlToken(t)) {
            j++
        } else {
            return t
        }
    }
    return -1
}
