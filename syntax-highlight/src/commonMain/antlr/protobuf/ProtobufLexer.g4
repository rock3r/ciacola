/**
 * Lossless ANTLR4 lexer grammar for Protocol Buffers (proto2, proto3, and Editions).
 *
 * Contextual keywords and field/message/enum/service/option disambiguation are performed
 * cleanly in O(N) by ProtobufSyntaxLexer so that keywords used as field or method names
 * (e.g. `string message = 1;`, `rpc stream(stream Req) returns (stream Res);`) classify accurately.
 */
lexer grammar ProtobufLexer;

WS
    : [ \t\r\n\uFEFF]+
    ;

LINE_COMMENT
    : '//' ~[\r\n]*
    ;

BLOCK_COMMENT
    : '/*' .*? ( '*/' | EOF )
    ;

STRING
    : '"' ( '\\' ~[\r\n] | ~["\\\r\n] )* '"'?
    ;

SINGLE_STRING
    : '\'' ( '\\' ~[\r\n] | ~['\\\r\n] )* '\''?
    ;

HEX_INT
    : '0' [xX] [0-9a-fA-F]+
    ;

FLOAT
    : [0-9]+ '.' [0-9]* ( [eE] [+-]? [0-9]+ )?
    | '.' [0-9]+ ( [eE] [+-]? [0-9]+ )?
    | [0-9]+ [eE] [+-]? [0-9]+
    ;

OCT_INT
    : '0' [0-7]+
    ;

INT
    : [0-9]+
    ;

IDENT
    : [a-zA-Z_][a-zA-Z0-9_]*
    ;

LBRACE : '{' ;
RBRACE : '}' ;
LPAREN : '(' ;
RPAREN : ')' ;
LBRACK : '[' ;
RBRACK : ']' ;
LANGLE : '<' ;
RANGLE : '>' ;
COMMA  : ',' ;
SEMI   : ';' ;
DOT    : '.' ;

EQ     : '=' ;
COLON  : ':' ;
MINUS  : '-' ;
PLUS   : '+' ;
SLASH  : '/' ;
STAR   : '*' ;

ERROR_CHAR
    : .
    ;
