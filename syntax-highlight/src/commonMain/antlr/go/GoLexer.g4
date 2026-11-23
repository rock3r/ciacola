/**
 * Lossless ANTLR4 lexer grammar for Go.
 */
lexer grammar GoLexer;

WS
    : [ \t\r\n]+
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
    : [a-zA-Z_]
    | [\u00C0-\u00D6\u00D8-\u00F6\u00F8-\u02AF\u0370-\u1FFF\u2100-\u218F\u2C00-\uD7FF\uF900-\uFFEF]
    | [\u{10000}-\u{1EFFF}\u{20000}-\u{323AF}]
    ;

fragment IDENT_CONTINUE
    : IDENT_START
    | [0-9\u0300-\u036F]
    ;

ERROR_CHAR
    : .
    ;
