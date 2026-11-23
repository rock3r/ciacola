/**
 * Lossless ANTLR4 lexer grammar for RFC 8259 JSON with JSONC line and block comments.
 */
lexer grammar JsonLexer;

WS
    : [ \t\r\n\uFEFF]+
    ;

LINE_COMMENT
    : '//' ~[\r\n]*
    ;

BLOCK_COMMENT
    : '/*' ( ~'*' | '*'+ ~[*/] )* '*'+ '/'
    ;

UNTERMINATED_BLOCK_COMMENT
    : '/*' ( ~'*' | '*'+ ~[*/] )* '*'*
    ;

STRING
    : '"' ( '\\' ~[\r\n] | ~["\\\r\n] )* '"'
    ;

UNTERMINATED_STRING
    : '"' ( '\\' ~[\r\n] | ~["\\\r\n] )* '\\'?
    ;

LBRACE : '{';
RBRACE : '}';
LBRACK : '[';
RBRACK : ']';
COLON  : ':';
COMMA  : ',';

NUMBER
    : '-'? ( '0' | [1-9] [0-9]* ) ( '.' [0-9]+ )? ( [eE] [+-]? [0-9]+ )?
    ;

TRUE  : 'true';
FALSE : 'false';
NULL  : 'null';

IDENTIFIER
    : [a-zA-Z_][a-zA-Z0-9_]*
    ;

ERROR_CHAR
    : .
    ;
