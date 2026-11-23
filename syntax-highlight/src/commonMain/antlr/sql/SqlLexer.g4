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

PG_HASH_PATH_TEXT
    : '#>>' { setBracketIdentAllowed(true); }
    ;

PG_HASH_PATH
    : '#>' { setBracketIdentAllowed(true); }
    ;

PG_HASH_DELETE
    : '#-' { setBracketIdentAllowed(true); }
    ;

HASH_COMMENT
    : '#' ( ~[>\-\r\n] ~[\r\n]* )?
    ;

DOLLAR_STRING
    : '$' ( DollarTagStart DollarTagChar* )? '$' { consumeDollarString(); setBracketIdentAllowed(false); }
    ;

PG_ESCAPE_STRING
    : [eE] '\'' ( '\\' . | '\'\'' | ~['\\] )* '\'' { setBracketIdentAllowed(false); }
    ;

UNTERMINATED_PG_ESCAPE_STRING
    : [eE] '\'' ( '\\' . | '\'\'' | ~['\\] )* '\\'? { setBracketIdentAllowed(false); }
    ;

NATIONAL_STRING
    : [nN] '\'' ( '\'\'' | ~['] )* '\'' { setBracketIdentAllowed(false); }
    ;

UNTERMINATED_NATIONAL_STRING
    : [nN] '\'' ( '\'\'' | ~['] )* { setBracketIdentAllowed(false); }
    ;

BIT_STRING
    : [bB] '\'' ( '\'\'' | ~['] )* '\'' { setBracketIdentAllowed(false); }
    ;

UNTERMINATED_BIT_STRING
    : [bB] '\'' ( '\'\'' | ~['] )* { setBracketIdentAllowed(false); }
    ;

HEX_STRING
    : [xX] '\'' ( '\'\'' | ~['] )* '\'' { setBracketIdentAllowed(false); }
    ;

UNTERMINATED_HEX_STRING
    : [xX] '\'' ( '\'\'' | ~['] )* { setBracketIdentAllowed(false); }
    ;

STRING
    : '\'' ( '\'\'' | ~['] )* '\'' { setBracketIdentAllowed(false); }
    ;

UNTERMINATED_STRING
    : '\'' ( '\'\'' | ~['] )* { setBracketIdentAllowed(false); }
    ;

DOUBLE_QUOTED_IDENT
    : '"' ( '""' | ~["] )* '"' { setBracketIdentAllowed(false); }
    ;

UNTERMINATED_DOUBLE_QUOTED_IDENT
    : '"' ( '""' | ~["] )* { setBracketIdentAllowed(false); }
    ;

BACKTICK_IDENT
    : '`' ( '``' | ~[`] )* '`' { setBracketIdentAllowed(false); }
    ;

UNTERMINATED_BACKTICK_IDENT
    : '`' ( '``' | ~[`] )* { setBracketIdentAllowed(false); }
    ;

BRACKET_IDENT
    : '[' { isBracketIdentAllowed() }? ( ']]' | ~[\]\r\n] )* ']' { setBracketIdentAllowed(false); }
    ;

HEX_NUMBER
    : '0' [xX] [0-9a-fA-F] ( '_'? [0-9a-fA-F] )* { setBracketIdentAllowed(false); }
    ;

FLOAT_NUMBER
    : ( Digits '.' Digits? Exponent?
      | '.' Digits Exponent?
      | Digits Exponent
      ) { setBracketIdentAllowed(false); }
    | Digits '.' { isNotRangeDotFloat() }? { setBracketIdentAllowed(false); }
    ;

INT_NUMBER
    : Digits { setBracketIdentAllowed(false); }
    ;

DOUBLE_COLON
    : '::' { setBracketIdentAllowed(true); }
    ;

COLON_EQ
    : ':=' { setBracketIdentAllowed(true); }
    ;

FAT_ARROW
    : '=>' { setBracketIdentAllowed(true); }
    ;

JSON_EXTRACT_TEXT
    : '->>' { setBracketIdentAllowed(true); }
    ;

JSON_EXTRACT
    : '->' { setBracketIdentAllowed(true); }
    ;

PG_CONTAINS
    : '@>' { setBracketIdentAllowed(true); }
    ;

PG_CONTAINED_BY
    : '<@' { setBracketIdentAllowed(true); }
    ;

PG_JSON_EXISTS_ALL
    : '?&' { setBracketIdentAllowed(true); }
    ;

PG_JSON_EXISTS_ANY
    : '?|' { setBracketIdentAllowed(true); }
    ;

CONCAT_OP
    : '||' { setBracketIdentAllowed(true); }
    ;

NEQ_ANGLE
    : '<>' { setBracketIdentAllowed(true); }
    ;

NEQ_BANG
    : '!=' { setBracketIdentAllowed(true); }
    ;

LEQ
    : '<=' { setBracketIdentAllowed(true); }
    ;

GEQ
    : '>=' { setBracketIdentAllowed(true); }
    ;

LSHIFT
    : '<<' { setBracketIdentAllowed(true); }
    ;

RSHIFT
    : '>>' { setBracketIdentAllowed(true); }
    ;

REGEX_NOT_ICASE
    : '!~*' { setBracketIdentAllowed(true); }
    ;

REGEX_NOT_CASE
    : '!~' { setBracketIdentAllowed(true); }
    ;

REGEX_ICASE
    : '~*' { setBracketIdentAllowed(true); }
    ;

DOTDOT
    : '..' { setBracketIdentAllowed(true); }
    ;

NAMED_PARAM
    : ':' [A-Za-z_][A-Za-z0-9_]* { setBracketIdentAllowed(false); }
    ;

AT_VAR
    : '@' '@'? [A-Za-z_][A-Za-z0-9_]* { setBracketIdentAllowed(false); }
    ;

POSITIONAL_PARAM
    : '$' [0-9]+ { setBracketIdentAllowed(false); }
    ;

QUESTION_PARAM
    : '?' { setBracketIdentAllowed(false); }
    ;

PLUS    : '+' { setBracketIdentAllowed(true); } ;
MINUS   : '-' { setBracketIdentAllowed(true); } ;
STAR    : '*' { setBracketIdentAllowed(true); } ;
SLASH   : '/' { setBracketIdentAllowed(true); } ;
PERCENT : '%' { setBracketIdentAllowed(true); } ;
EQ      : '=' { setBracketIdentAllowed(true); } ;
LT      : '<' { setBracketIdentAllowed(true); } ;
GT      : '>' { setBracketIdentAllowed(true); } ;
AMP     : '&' { setBracketIdentAllowed(true); } ;
PIPE    : '|' { setBracketIdentAllowed(true); } ;
CARET   : '^' { setBracketIdentAllowed(true); } ;
TILDE   : '~' { setBracketIdentAllowed(true); } ;
BANG    : '!' { setBracketIdentAllowed(true); } ;

LPAREN  : '(' { setBracketIdentAllowed(true); } ;
RPAREN  : ')' { setBracketIdentAllowed(false); } ;
LBRACK  : '[' { setBracketIdentAllowed(true); } ;
RBRACK  : ']' { setBracketIdentAllowed(false); } ;
COMMA   : ',' { setBracketIdentAllowed(true); } ;
SEMI    : ';' { setBracketIdentAllowed(true); } ;
DOT     : '.' { setBracketIdentAllowed(true); } ;
COLON   : ':' { setBracketIdentAllowed(true); } ;

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
