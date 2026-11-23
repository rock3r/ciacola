/**
 * Lossless ANTLR4 lexer grammar for Rust (2021/2024), supporting nested block comments via
 * mode stacks and arbitrary `#`-count raw strings via RustLexerBase.
 */
lexer grammar RustLexer;

options {
    superClass = RustLexerBase;
}

WS
    : [ \t\r\n]+
    ;

LINE_COMMENT
    : '//' ~[\r\n]*
    ;

BLOCK_COMMENT
    : '/*' -> pushMode(BlockComment)
    ;

RAW_STRING
    : 'r' '#'* '"' { consumeRustRawString(1); }
    | ('br' | 'cr') '#'* '"' { consumeRustRawString(2); }
    ;

STRING
    : ('b' | 'c')? '"' ( '\\' . | ~["\\] )* '"'
    ;

UNTERMINATED_STRING
    : ('b' | 'c')? '"' ( '\\' . | ~["\\] )* '\\'?
    ;

CHAR_LITERAL
    : 'b'? '\'' ( ~['\\\r\n] | '\\' ( 'x' [0-9a-fA-F] [0-9a-fA-F] | 'u{' [0-9a-fA-F_]+ '}' | . ) ) '\''
    ;

LIFETIME
    : '\'' ( '_' | [a-zA-Z] [a-zA-Z0-9_]* )
    ;

RAW_IDENT
    : 'r#' [a-zA-Z_] [a-zA-Z0-9_]*
    ;

HEX_LITERAL
    : '0x' [0-9a-fA-F_]+ IntSuffix?
    ;

OCT_LITERAL
    : '0o' [0-7_]+ IntSuffix?
    ;

BIN_LITERAL
    : '0b' [01_]+ IntSuffix?
    ;

FLOAT_LITERAL
    : [0-9][0-9_]* '.' [0-9][0-9_]* ( [eE] [+-]? [0-9_]+ )? FloatSuffix?
    | [0-9][0-9_]* '.' { isRustTrailingDotFloat() }? FloatSuffix?
    | [0-9][0-9_]* [eE] [+-]? [0-9_]+ FloatSuffix?
    ;

INT_LITERAL
    : [0-9][0-9_]* IntSuffix?
    ;

fragment IntSuffix
    : [iu] ( '8' | '16' | '32' | '64' | '128' | 'size' )
    ;

fragment FloatSuffix
    : 'f' ( '32' | '64' )
    ;

// Multi-char operators & punctuation
DOTDOTEQ   : '..=';
DOTDOT     : '..';
COLONCOLON : '::';
RARROW     : '->';
FATARROW   : '=>';
SHLEQ      : '<<=';
SHREQ      : '>>=';
SHL        : '<<';
SHR        : '>>';
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
HASH     : '#';
DOLLAR   : '$';
TILDE    : '~';
QUESTION : '?';
AT       : '@';
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

IDENT
    : [a-zA-Z_] [a-zA-Z0-9_]*
    ;

ERROR_CHAR
    : .
    ;

mode BlockComment;

BLOCK_COMMENT_OPEN  : '/*' -> pushMode(BlockComment), type(BLOCK_COMMENT);
BLOCK_COMMENT_CLOSE : '*/' -> popMode, type(BLOCK_COMMENT);
BLOCK_COMMENT_TEXT  : (~[/*]+ | '/' | '*') -> type(BLOCK_COMMENT);
