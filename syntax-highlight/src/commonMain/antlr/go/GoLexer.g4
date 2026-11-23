/**
 * Lossless ANTLR4 lexer grammar for Go.
 */
lexer grammar GoLexer;

import UnicodeIdent;

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

RAW_STRING
    : '`' ~[`]* '`'
    ;

UNTERMINATED_RAW_STRING
    : '`' ~[`]*
    ;

STRING
    : '"' ( '\\' ~[\r\n] | ~["\\\r\n] )* '"'
    ;

UNTERMINATED_STRING
    : '"' ( '\\' ~[\r\n] | ~["\\\r\n] )* '\\'?
    ;

RUNE
    : '\'' ( '\\' ~[\r\n] | ~['\\\r\n] )+ '\''
    ;

UNTERMINATED_RUNE
    : '\'' ( '\\' ~[\r\n] | ~['\\\r\n] )* '\\'?
    ;

HEX_FLOAT
    : '0' [xX] ( HexDigits ( '.' HexDigits? )? | '.' HexDigits ) [pP] [+-]? Digits 'i'?
    ;

HEX_INT
    : '0' [xX] '_'? HexDigits 'i'?
    ;

BIN_INT
    : '0' [bB] '_'? BinDigits 'i'?
    ;

OCT_INT
    : '0' [oO] '_'? OctDigits 'i'?
    | '0' '_'? OctDigits 'i'?
    ;

FLOAT
    : ( Digits '.' Digits? Exponent? | '.' Digits Exponent? | Digits Exponent ) 'i'?
    ;

DEC_INT
    : Digits 'i'?
    ;

fragment Digits
    : [0-9] ( '_'? [0-9] )*
    ;

fragment HexDigits
    : [0-9a-fA-F] ( '_'? [0-9a-fA-F] )*
    ;

fragment OctDigits
    : [0-7] ( '_'? [0-7] )*
    ;

fragment BinDigits
    : [01] ( '_'? [01] )*
    ;

fragment Exponent
    : [eE] [+-]? Digits
    ;

// Multi-char operators & punctuation
ELLIPSIS   : '...';
COLONEQ    : ':=';
LARROW     : '<-';
AMPCASEQ   : '&^=';
AMPCARET   : '&^';
SHLEQ      : '<<=';
SHREQ      : '>>=';
SHL        : '<<';
SHR        : '>>';
PLUSPLUS   : '++';
MINUSMINUS : '--';
ANDAND     : '&&';
OROR       : '||';
EQEQ       : '==';
NE         : '!=';
LE         : '<=';
GE         : '>=';
PLUSEQ     : '+=';
MINUSEQ    : '-=';
STAREQ     : '*=';
SLASHEQ    : '/=';
PERCENTEQ  : '%=';
AMPEQ      : '&=';
PIPEEQ     : '|=';
CARETEQ    : '^=';

// Single-char operators & punctuation
LPAREN   : '(';
RPAREN   : ')';
LBRACE   : '{';
RBRACE   : '}';
LBRACK   : '[';
RBRACK   : ']';
SEMI     : ';';
COMMA    : ',';
DOT      : '.';
COLON    : ':';
PLUS     : '+';
MINUS    : '-';
STAR     : '*';
SLASH    : '/';
PERCENT  : '%';
CARET    : '^';
AMP      : '&';
PIPE     : '|';
BANG     : '!';
EQ       : '=';
LT       : '<';
GT       : '>';
TILDE    : '~';

IDENT
    : IDENT_START IDENT_CONTINUE*
    ;

fragment IDENT_START
    : UNICODE_XID_START
    | '_'
    ;

fragment IDENT_CONTINUE
    : UNICODE_XID_CONTINUE
    ;

ERROR_CHAR
    : .
    ;
