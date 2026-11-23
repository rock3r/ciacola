/**
 * Lossless ANTLR4 lexer grammar for ANSI SQL, PostgreSQL, MySQL, SQLite, T-SQL, and PL/SQL.
 * Supports nested block comments, dollar-quoted strings, dialect-specific quoted identifiers,
 * parameters/variables, and PostgreSQL/JSON operators in linear time.
 */
lexer grammar SqlLexer;

options {
    superClass = SqlLexerBase;
}

import UnicodeIdent;

WS
    : [ \t\r\n\u000B\u000C\uFEFF]+
    ;

LINE_COMMENT
    : '--' ~[\r\n]*
    ;

BLOCK_COMMENT
    : '/*' { consumeNestedBlockComment(); }
    ;

HASH_LINE_COMMENT
    : '#' { isLineStartHashComment() }? ~[\r\n]*
    ;

PG_HASH_PATH_TEXT
    : '#>>' { onOperatorToken(); }
    ;

PG_HASH_PATH
    : '#>' { onOperatorToken(); }
    ;

PG_HASH_DELETE
    : '#-' { isNotDoubleDashAfterHash() }? { onOperatorToken(); }
    ;

TEMP_TABLE_IDENT
    : '#' '#'? ( [A-Za-z_] | UNICODE_XID_START ) ( [A-Za-z0-9_$] | UNICODE_XID_CONTINUE )* { onValueToken(); }
    ;

HASH_XOR
    : '#' { isInValuePosition() }? { onOperatorToken(); }
    ;

HASH_COMMENT
    : '#' { isNonLineStartHashComment() }? ~[\r\n]*
    ;

DOLLAR_STRING
    : '$' ( DollarTagStart DollarTagChar* )? '$' { consumeDollarString(); onValueToken(); }
    ;

DOLLAR_PARAM
    : '$' DollarTagStart DollarTagChar* { onValueToken(); }
    ;

PG_ESCAPE_STRING
    : [eE] '\'' ( '\\' . | '\'\'' | ~['\\] )* '\'' { onValueToken(); }
    ;

UNTERMINATED_PG_ESCAPE_STRING
    : [eE] '\'' ( '\\' . | '\'\'' | ~['\\] )* '\\'? { onValueToken(); }
    ;

NATIONAL_STRING
    : [nN] '\'' { consumeSqlQuoted('\''.code); onValueToken(); }
    ;

BIT_STRING
    : [bB] '\'' ( '\'\'' | ~['] )* '\'' { onValueToken(); }
    ;

UNTERMINATED_BIT_STRING
    : [bB] '\'' ( '\'\'' | ~['] )* { onValueToken(); }
    ;

HEX_STRING
    : [xX] '\'' ( '\'\'' | ~['] )* '\'' { onValueToken(); }
    ;

UNTERMINATED_HEX_STRING
    : [xX] '\'' ( '\'\'' | ~['] )* { onValueToken(); }
    ;

STRING
    : '\'' { consumeSqlQuoted('\''.code); onValueToken(); }
    ;

DOUBLE_QUOTED_IDENT
    : '"' { consumeSqlQuoted('"'.code); onValueToken(); }
    ;

BACKTICK_IDENT
    : '`' ( '``' | ~[`] )* '`' { onValueToken(); }
    ;

UNTERMINATED_BACKTICK_IDENT
    : '`' ( '``' | ~[`] )* { onValueToken(); }
    ;

BRACKET_IDENT
    : '[' { isBracketIdentWithClosingAhead() }? { consumeBracketIdent(); onValueToken(); }
    ;

HEX_NUMBER
    : '0' [xX] [0-9a-fA-F] ( '_'? [0-9a-fA-F] )* { onValueToken(); }
    ;

FLOAT_NUMBER
    : ( Digits '.' Digits Exponent?
      | '.' Digits Exponent?
      | Digits Exponent
      ) { onValueToken(); }
    | Digits '.' { isNotRangeDotFloat() }? Exponent? { onValueToken(); }
    ;

INT_NUMBER
    : Digits { onValueToken(); }
    ;

DOUBLE_COLON
    : '::' { onOperatorToken(); }
    ;

COLON_EQ
    : ':=' { onOperatorToken(); }
    ;

FAT_ARROW
    : '=>' { onOperatorToken(); }
    ;

JSON_EXTRACT_TEXT
    : '->>' { onOperatorToken(); }
    ;

JSON_EXTRACT
    : '->' { onOperatorToken(); }
    ;

PG_CONTAINS
    : '@>' { onOperatorToken(); }
    ;

PG_CONTAINED_BY
    : '<@' { onOperatorToken(); }
    ;

PG_JSON_EXISTS_ALL
    : '?&' { onOperatorToken(); }
    ;

PG_JSON_EXISTS_ANY
    : '?|' { onOperatorToken(); }
    ;

CONCAT_OP
    : '||' { onOperatorToken(); }
    ;

NEQ_ANGLE
    : '<>' { onOperatorToken(); }
    ;

NEQ_BANG
    : '!=' { onOperatorToken(); }
    ;

LEQ
    : '<=' { onOperatorToken(); }
    ;

GEQ
    : '>=' { onOperatorToken(); }
    ;

LSHIFT
    : '<<' { onOperatorToken(); }
    ;

RSHIFT
    : '>>' { onOperatorToken(); }
    ;

REGEX_NOT_ICASE
    : '!~*' { onOperatorToken(); }
    ;

REGEX_NOT_CASE
    : '!~' { onOperatorToken(); }
    ;

REGEX_ICASE
    : '~*' { onOperatorToken(); }
    ;

DOTDOT
    : '..' { onOperatorToken(); }
    ;

NAMED_PARAM
    : ':' [A-Za-z_][A-Za-z0-9_]* { onValueToken(); }
    ;

AT_VAR
    : '@' '@'? [A-Za-z_][A-Za-z0-9_]* { onValueToken(); }
    ;

POSITIONAL_PARAM
    : '$' [0-9]+ { onValueToken(); }
    ;

QUESTION_PARAM
    : '?' { onValueToken(); }
    ;

PLUS    : '+' { onOperatorToken(); } ;
MINUS   : '-' { onOperatorToken(); } ;
STAR    : '*' { onOperatorToken(); } ;
SLASH   : '/' { onOperatorToken(); } ;
PERCENT : '%' { onOperatorToken(); } ;
EQ      : '=' { onOperatorToken(); } ;
LT      : '<' { onOperatorToken(); } ;
GT      : '>' { onOperatorToken(); } ;
AMP     : '&' { onOperatorToken(); } ;
PIPE    : '|' { onOperatorToken(); } ;
CARET   : '^' { onOperatorToken(); } ;
TILDE   : '~' { onOperatorToken(); } ;
BANG    : '!' { onOperatorToken(); } ;

LPAREN  : '(' { onOperatorToken(); } ;
RPAREN  : ')' { onValueToken(); } ;
LBRACK  : '[' { onOperatorToken(); } ;
RBRACK  : ']' { onValueToken(); } ;
COMMA   : ',' { onOperatorToken(); } ;
SEMI    : ';' { onOperatorToken(); } ;
DOT     : '.' { onOperatorToken(); } ;
COLON   : ':' { onOperatorToken(); } ;

IDENT
    : ( [A-Za-z_] | UNICODE_XID_START ) ( [A-Za-z0-9_$] | UNICODE_XID_CONTINUE )* { onIdentMatched(); }
    ;

ERROR_CHAR
    : .
    ;

fragment Digits
    : [0-9] ( '_'? [0-9] )*
    ;

fragment Exponent
    : [eE] [+-]? Digits
    ;

fragment DollarTagStart
    : [A-Za-z_]
    | UNICODE_XID_START
    ;

fragment DollarTagChar
    : [A-Za-z0-9_]
    | UNICODE_XID_CONTINUE
    ;
