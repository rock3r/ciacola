/**
 * Lossless ANTLR4 lexer grammar for CSS.
 */
lexer grammar CssLexer;

options {
    superClass = CssLexerBase;
}

@members {
    override fun isIdentType(type: Int): Boolean = type == Tokens.IDENT
    override fun isWhitespaceType(type: Int): Boolean = type == Tokens.WS
}

WS
    : [ \t\r\n\f]+
    ;

BLOCK_COMMENT
    : '/*' ( ~'*' | '*'+ ~[*/] )* '*'+ '/'
    ;

UNTERMINATED_BLOCK_COMMENT
    : '/*' ( ~'*' | '*'+ ~[*/] )* '*'*
    ;

STRING
    : '"' ( '\\' ( '\r'? '\n' | ~[\r\n] ) | ~["\\\r\n] )* '"'
    | '\'' ( '\\' ( '\r'? '\n' | ~[\r\n] ) | ~['\\\r\n] )* '\''
    ;

UNTERMINATED_STRING
    : '"' ( '\\' ( '\r'? '\n' | ~[\r\n] ) | ~["\\\r\n] )* '\\'?
    | '\'' ( '\\' ( '\r'? '\n' | ~[\r\n] ) | ~['\\\r\n] )* '\\'?
    ;

IMPORTANT
    : '!' [ \t\r\n]* [iI][mM][pP][oO][rR][tT][aA][nN][tT]
    ;

AT_KEYWORD
    : '@' ( '--' | '-'? IDENT_START ) IDENT_CHAR*
    ;

HASH_TOKEN
    : '#' IDENT_CHAR+
    ;

UNICODE_RANGE
    : [uU] '+' [0-9a-fA-F?]+ ( '-' [0-9a-fA-F]+ )?
    ;

NUMBER
    : ( [0-9]+ ( '.' [0-9]+ )? | '.' [0-9]+ ) ( [eE] [+-]? [0-9]+ )? ( '%' | ( '--' | '-'? IDENT_START ) IDENT_CHAR* )?
    ;

IDENT
    : ( '--' | '-'? IDENT_START ) IDENT_CHAR*
    ;

DOUBLE_COLON : '::';
COLON        : ':';
SEMICOLON    : ';';
COMMA        : ',';
LBRACE       : '{';
RBRACE       : '}';
LPAREN       : '(' { onLparen(Modes.URL_BODY) };
RPAREN       : ')';
LBRACK       : '[';
RBRACK       : ']';

INCLUDES     : '~=';
DASHMATCH    : '|=';
PREFIXMATCH  : '^=';
SUFFIXMATCH  : '$=';
SUBSTRINGMATCH : '*=';

DOT          : '.';
GT           : '>';
PLUS         : '+';
MINUS        : '-';
TILDE        : '~';
STAR         : '*';
SLASH        : '/';
EQ           : '=';
BANG         : '!';
AMP          : '&';
HASH_SINGLE  : '#';
AT_SINGLE    : '@';

ERROR_CHAR
    : .
    ;

fragment ESCAPE
    : '\\' ( [0-9a-fA-F]+ [ \t\r\n]? | ~[0-9a-fA-F\r\n] )
    ;

fragment IDENT_START
    : [a-zA-Z_]
    | [\u0080-\uD7FF\uE000-\u{10FFFF}]
    | ESCAPE
    ;

fragment IDENT_CHAR
    : [a-zA-Z0-9_-]
    | [\u0080-\uD7FF\uE000-\u{10FFFF}]
    | ESCAPE
    ;

mode URL_BODY;

URL_WS
    : [ \t\r\n\f]+
    ;

URL_RPAREN
    : ')' -> popMode
    ;

URL_STRING
    : '"' ( '\\' ( '\r'? '\n' | ~[\r\n] ) | ~["\\\r\n] )* '"'
    | '\'' ( '\\' ( '\r'? '\n' | ~[\r\n] ) | ~['\\\r\n] )* '\''
    ;

URL_UNTERMINATED_STRING
    : '"' ( '\\' ( '\r'? '\n' | ~[\r\n] ) | ~["\\\r\n] )* '\\'?
    | '\'' ( '\\' ( '\r'? '\n' | ~[\r\n] ) | ~['\\\r\n] )* '\\'?
    ;

URL_UNQUOTED
    : ~[ \t\r\n\f"'()]+
    ;

URL_LPAREN
    : '('
    ;
