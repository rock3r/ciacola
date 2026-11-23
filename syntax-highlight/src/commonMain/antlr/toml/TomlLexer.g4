/**
 * Lossless ANTLR4 lexer grammar for TOML 1.0 / 1.1.
 */
lexer grammar TomlLexer;

WS
    : [ \t]+
    ;

NEWLINE
    : '\r'? '\n'
    | '\r'
    ;

COMMENT
    : '#' ~[\r\n]*
    ;

// Strings (multiline before single-line; terminated before unterminated)
ML_BASIC_STRING
    : '"""'
      ( '\\' .
      | ~[\\"]
      | '"' ( '\\' . | ~[\\"] )
      | '""' ( '\\' . | ~[\\"] )
      )*
      '"""' '"'? '"'?
    ;

UNTERMINATED_ML_BASIC_STRING
    : '"""'
      ( '\\' .
      | ~[\\"]
      | '"' ( '\\' . | ~[\\"] )
      | '""' ( '\\' . | ~[\\"] )
      )*
      ( '""' '\\'? | '"' '\\'? | '\\' )?
    ;

ML_LITERAL_STRING
    : '\'\'\''
      ( ~[']
      | '\'' ~[']
      | '\'\'' ~[']
      )*
      '\'\'\'' '\''? '\''?
    ;

UNTERMINATED_ML_LITERAL_STRING
    : '\'\'\''
      ( ~[']
      | '\'' ~[']
      | '\'\'' ~[']
      )*
      ( '\'\'' | '\'' )?
    ;

BASIC_STRING
    : '"' ( '\\' ~[\r\n] | ~["\\\r\n] )* '"'
    ;

UNTERMINATED_BASIC_STRING
    : '"' ( '\\' ~[\r\n] | ~["\\\r\n] )* '\\'?
    ;

LITERAL_STRING
    : '\'' ~['\r\n]* '\''
    ;

UNTERMINATED_LITERAL_STRING
    : '\'' ~['\r\n]*
    ;

// RFC 3339 Dates and Times
DATE_TIME
    : [0-9][0-9][0-9][0-9] '-' [0-9][0-9] '-' [0-9][0-9]
      ( [Tt ] [0-9][0-9] ':' [0-9][0-9] ( ':' [0-9][0-9] ( '.' [0-9]+ )? )?
        ( [Zz] | [+-] [0-9][0-9] ':' [0-9][0-9] )?
      )?
    ;

TIME
    : [0-9][0-9] ':' [0-9][0-9] ( ':' [0-9][0-9] ( '.' [0-9]+ )? )?
    ;

// Booleans and special floats (must precede BARE_KEY)
BOOL
    : 'true'
    | 'false'
    ;

SPECIAL_FLOAT
    : [+-]? ( 'inf' | 'nan' )
    ;

// Numbers
HEX_INT
    : '0x' [0-9a-fA-F] ( '_'? [0-9a-fA-F] )*
    ;

OCT_INT
    : '0o' [0-7] ( '_'? [0-7] )*
    ;

BIN_INT
    : '0b' [01] ( '_'? [01] )*
    ;

FLOAT
    : [+-]? DEC_INT_PART
      ( '.' [0-9] ( '_'? [0-9] )* ( [eE] [+-]? [0-9] ( '_'? [0-9] )* )?
      | [eE] [+-]? [0-9] ( '_'? [0-9] )*
      )
    ;

DEC_INT
    : [+-]? DEC_INT_PART
    ;

fragment DEC_INT_PART
    : '0'
    | [1-9] ( '_'? [0-9] )*
    ;

// Punctuation
LBRACK : '[';
RBRACK : ']';
LBRACE : '{';
RBRACE : '}';
EQUALS : '=';
COMMA  : ',';
DOT    : '.';

// Bare keys
BARE_KEY
    : [A-Za-z0-9_-]+
    ;

ERROR_CHAR
    : .
    ;
