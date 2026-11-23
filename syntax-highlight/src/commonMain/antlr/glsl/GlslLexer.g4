/**
 * Lossless ANTLR4 lexer grammar shared by GLSL, SkSL, and AGSL shader dialects.
 */
lexer grammar GlslLexer;

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

PP_HASH
    : '#' -> pushMode(PP_MODE)
    ;

HEX_INT
    : '0' [xX] [0-9a-fA-F]+ [uU]?
    ;

OCT_INT
    : '0' [0-7]+ [uU]?
    ;

FLOAT
    : ( [0-9]+ '.' [0-9]* Exponent? | '.' [0-9]+ Exponent? | [0-9]+ Exponent ) FloatSuffix?
    ;

DEC_INT
    : [0-9]+ [uU]?
    ;

fragment Exponent
    : [eE] [+-]? [0-9]+
    ;

fragment FloatSuffix
    : 'lf' | 'LF' | [fFhH]
    ;

// Multi-char operators
SHLEQ      : '<<=';
SHREQ      : '>>=';
SHL        : '<<';
SHR        : '>>';
PLUSPLUS   : '++';
MINUSMINUS : '--';
ANDAND     : '&&';
OROR       : '||';
XORXOR     : '^^';
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
QUESTION : '?';
COLON    : ':';
PLUS     : '+';
MINUS    : '-';
STAR     : '*';
SLASH    : '/';
PERCENT  : '%';
CARET    : '^';
AMP      : '&';
PIPE     : '|';
TILDE    : '~';
BANG     : '!';
EQ       : '=';
LT       : '<';
GT       : '>';

IDENT
    : [a-zA-Z_] [a-zA-Z0-9_]*
    ;

ERROR_CHAR
    : .
    ;

mode PP_MODE;

PP_WS
    : [ \t]+
    ;

PP_LINE_CONT
    : '\\' '\r'? '\n'
    ;

PP_LINE_COMMENT
    : '//' ~[\r\n]*
    ;

PP_BLOCK_COMMENT
    : '/*' ( ~'*' | '*'+ ~[*/] )* ( '*'+ '/' | '*'* )
    ;

PP_NL
    : '\r'? '\n' -> popMode
    ;

PP_TEXT
    : ~[\r\n \t/\\]+
    | '/'
    | '\\'
    ;
