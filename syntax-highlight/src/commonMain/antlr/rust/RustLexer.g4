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
    : '\'' ( '_' | IDENT_START IDENT_CONTINUE* )
    ;

RAW_IDENT
    : 'r#' IDENT_START IDENT_CONTINUE*
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

mode BlockComment;

BLOCK_COMMENT_OPEN  : '/*' -> pushMode(BlockComment), type(BLOCK_COMMENT);
BLOCK_COMMENT_CLOSE : '*/' -> popMode, type(BLOCK_COMMENT);
BLOCK_COMMENT_TEXT  : (~[/*]+ | '/' | '*') -> type(BLOCK_COMMENT);
