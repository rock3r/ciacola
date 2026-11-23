/**
 * Lossless ANTLR4 lexer grammar shared by C (C11/C23) and C++ (C++20/C++23).
 * Preprocessor directives, `#include` headers, line continuations, and C++ raw strings
 * (`R"delim(...)delim"`) are handled in linear time.
 */
lexer grammar CppLexer;

options {
    superClass = CppLexerBase;
}

WS
    : [ \t\r\n\u000B\u000C]+
    ;

LINE_COMMENT
    : '//' ( '\\' '\r'? '\n' | ~[\r\n] )*
    ;

BLOCK_COMMENT
    : '/*' ( ~'*' | '*'+ ~[*/] )* '*'+ '/'
    ;

UNTERMINATED_BLOCK_COMMENT
    : '/*' ( ~'*' | '*'+ ~[*/] )* '*'*
    ;

PP_HASH
    : '#' -> pushMode(PP_HEADER_MODE)
    ;

RAW_STRING
    : ('u8' | 'u' | 'U' | 'L')? 'R"' { enableCppFeatures }? RawDelim '(' { consumeCppRawString(); }
    ;

fragment DChar
    : ~[()\\ \t\r\n\u000B\u000C]
    ;

fragment RawDelim
    : (DChar (DChar (DChar (DChar (DChar (DChar (DChar (DChar
      (DChar (DChar (DChar (DChar (DChar (DChar (DChar DChar?)?)?)?)?)?)?)?)?)?)?)?)?)?)?)?
    ;

STRING
    : ('u8' | 'u' | 'U' | 'L')? '"' ( '\\' '\r'? '\n' | '\\' . | ~["\\\r\n] )* '"'
    ;

UNTERMINATED_STRING
    : ('u8' | 'u' | 'U' | 'L')? '"' ( '\\' '\r'? '\n' | '\\' . | ~["\\\r\n] )* '\\'?
    ;

CHAR_LITERAL
    : ('u8' | 'u' | 'U' | 'L')? '\'' ( '\\' '\r'? '\n' | '\\' . | ~['\\\r\n] )+ '\''
    ;

UNTERMINATED_CHAR
    : ('u8' | 'u' | 'U' | 'L')? '\'' ( '\\' '\r'? '\n' | '\\' . | ~['\\\r\n] )* '\\'?
    ;

HEX_FLOAT
    : '0' [xX] ( HexDigits ( '.' HexDigits? )? | '.' HexDigits ) [pP] [+-]? Digits FloatSuffix?
    ;

HEX_INT
    : '0' [xX] HexDigits IntSuffix?
    ;

BIN_INT
    : '0' [bB] BinDigits IntSuffix?
    ;

OCT_INT
    : '0' ( '\''? [0-7] )+ IntSuffix?
    ;

FLOAT
    : ( Digits '.' Digits? Exponent? | '.' Digits Exponent? | Digits Exponent ) FloatSuffix?
    ;

DEC_INT
    : Digits IntSuffix?
    ;

fragment Digits
    : [0-9] ( '\''? [0-9] )*
    ;

fragment HexDigits
    : [0-9a-fA-F] ( '\''? [0-9a-fA-F] )*
    ;

fragment BinDigits
    : [01] ( '\''? [01] )*
    ;

fragment Exponent
    : [eE] [+-]? Digits
    ;

fragment IntSuffix
    : [uU] ( 'll' | 'LL' | [lLzZ] | 'wb' | 'WB' )?
    | ( 'll' | 'LL' | [lLzZ] | 'wb' | 'WB' ) [uU]?
    ;

fragment FloatSuffix
    : [fFlL]
    | [fF] ( '16' | '32' | '64' | '128' )
    | ( 'bf16' | 'BF16' )
    ;

// Multi-char operators & punctuation
SPACESHIP  : '<=>';
ELLIPSIS   : '...';
ARROWSTAR  : '->*';
DOTSTAR    : '.*';
COLONCOLON : '::';
ARROW      : '->';
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
QUESTION : '?';
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

mode PP_HEADER_MODE;

PP_HDR_WS
    : [ \t]+
    ;

PP_HDR_LINE_CONT
    : '\\' '\r'? '\n'
    ;

PP_HDR_LINE_COMMENT
    : '//' ~[\r\n]*
    ;

PP_HDR_BLOCK_COMMENT
    : '/*' ( ~'*' | '*'+ ~[*/] )* ( '*'+ '/' | '*'* )
    ;

PP_HDR_INCLUDE
    : ( 'include_next' | 'include' | 'import' ) -> mode(PP_INCLUDE_MODE)
    ;

PP_HDR_DIRECTIVE
    : [a-zA-Z_] [a-zA-Z0-9_]* -> mode(PP_BODY_MODE)
    ;

PP_HDR_NL
    : '\r'? '\n' -> popMode
    ;

PP_HDR_OTHER
    : ~[\r\n] -> mode(PP_BODY_MODE)
    ;

mode PP_INCLUDE_MODE;

PP_INC_WS
    : [ \t]+
    ;

PP_INC_LINE_CONT
    : '\\' '\r'? '\n'
    ;

PP_INC_LINE_COMMENT
    : '//' ~[\r\n]*
    ;

PP_INC_BLOCK_COMMENT
    : '/*' ( ~'*' | '*'+ ~[*/] )* ( '*'+ '/' | '*'* )
    ;

PP_INC_SYS_HEADER
    : '<' ~[>\r\n]+ '>'
    ;

PP_INC_STR_HEADER
    : '"' ( '\\' ~[\r\n] | ~["\\\r\n] )* '"'
    ;

PP_INC_NL
    : '\r'? '\n' -> popMode
    ;

PP_INC_OTHER
    : ~[\r\n \t/\\"<]+
    | '/'
    | '\\'
    | '"'
    | '<'
    ;

mode PP_BODY_MODE;

PP_BODY_WS
    : [ \t]+
    ;

PP_BODY_LINE_CONT
    : '\\' '\r'? '\n'
    ;

PP_BODY_LINE_COMMENT
    : '//' ~[\r\n]*
    ;

PP_BODY_BLOCK_COMMENT
    : '/*' ( ~'*' | '*'+ ~[*/] )* ( '*'+ '/' | '*'* )
    ;

PP_BODY_STRING
    : '"' ( '\\' ~[\r\n] | ~["\\\r\n] )* '"'
    ;

PP_BODY_CHAR
    : '\'' ( '\\' ~[\r\n] | ~['\\\r\n] )* '\''
    ;

PP_BODY_NL
    : '\r'? '\n' -> popMode
    ;

PP_BODY_TEXT
    : ~[\r\n \t"'/\\]+
    | '/'
    | '\\'
    | '"'
    | '\''
    ;
