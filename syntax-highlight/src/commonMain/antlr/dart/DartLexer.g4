/**
 * Lossless ANTLR4 lexer grammar for Dart 3.x, supporting nested block comments,
 * single-line and multiline strings (single-quoted, double-quoted, and raw `r` strings),
 * nested `$ident` and `${expr}` interpolations, digit-separated numbers, and Dart 3
 * operators/patterns.
 */
lexer grammar DartLexer;

options {
    superClass = DartLexerBase;
}

tokens {
    INTERP_OPEN,
    INTERP_CLOSE,
    INTERP_IDENT,
    STRING_ESCAPE,
    STRING_TEXT,
    STRING_CLOSE
}

@members {
    override fun singleLineSqMode(): Int = Modes.DART_SQ_SINGLE
    override fun singleLineDqMode(): Int = Modes.DART_DQ_SINGLE
    override fun multiLineSqMode(): Int = Modes.DART_SQ_MULTI
    override fun multiLineDqMode(): Int = Modes.DART_DQ_MULTI
}

WS
    : [ \t\u000B\u000C\uFEFF]+
    ;

NEWLINE
    : ( '\r'? '\n'
      | '\r'
      ) { onDefaultNewline(); }
    ;

DOC_LINE_COMMENT
    : '///' ~[\r\n]*
    ;

LINE_COMMENT
    : '//' ~[\r\n]*
    ;

BLOCK_COMMENT
    : '/*' { consumeNestedBlockComment(); }
    ;

// Raw strings (no escapes, no interpolation)
RAW_TRIPLE_SQ_STRING
    : 'r\'\'\'' { consumeDartRawString(quoteChar = '\'', quoteCount = 3); }
    ;

RAW_TRIPLE_DQ_STRING
    : 'r"""' { consumeDartRawString(quoteChar = '"', quoteCount = 3); }
    ;

RAW_SQ_STRING
    : 'r\'' ~['\r\n]* '\''?
    ;

RAW_DQ_STRING
    : 'r"' ~["\r\n]* '"'?
    ;

// Interpolated / escaped strings
TRIPLE_SQ_STRING_START
    : '\'\'\'' { enterDartString(quoteChar = '\'', isMultiline = true); }
    ;

TRIPLE_DQ_STRING_START
    : '"""' { enterDartString(quoteChar = '"', isMultiline = true); }
    ;

SQ_STRING_START
    : '\'' { enterDartString(quoteChar = '\'', isMultiline = false); }
    ;

DQ_STRING_START
    : '"' { enterDartString(quoteChar = '"', isMultiline = false); }
    ;

// Numeric literals (including Dart 3.6 `_` digit separators)
HEX_LITERAL
    : '0' [xX] HEX_DIGITS
    ;

FLOAT_LITERAL
    : DIGITS '.' DIGITS ( [eE] [+-]? DIGITS )?
    | '.' DIGITS ( [eE] [+-]? DIGITS )?
    | DIGITS [eE] [+-]? DIGITS
    ;

INT_LITERAL
    : DIGITS
    ;

fragment DIGITS
    : [0-9] ( '_'* [0-9] )*
    ;

fragment HEX_DIGITS
    : [0-9a-fA-F] ( '_'* [0-9a-fA-F] )*
    ;

// Reserved words
ASSERT    : 'assert';
BREAK     : 'break';
CASE      : 'case';
CATCH     : 'catch';
CLASS     : 'class';
CONST     : 'const';
CONTINUE  : 'continue';
DEFAULT   : 'default';
DO        : 'do';
ELSE      : 'else';
ENUM      : 'enum';
EXTENDS   : 'extends';
FALSE     : 'false';
FINAL     : 'final';
FINALLY   : 'finally';
FOR       : 'for';
IF        : 'if';
IN        : 'in';
IS        : 'is';
NEW       : 'new';
NULL      : 'null';
RETHROW   : 'rethrow';
RETURN    : 'return';
SUPER     : 'super';
SWITCH    : 'switch';
THIS      : 'this';
THROW     : 'throw';
TRUE      : 'true';
TRY       : 'try';
VAR       : 'var';
VOID      : 'void';
WHILE     : 'while';
WITH      : 'with';

// Multi-character operators and punctuation
ELLIPSIS_QUESTION  : '...?';
ELLIPSIS           : '...';
NULL_CASCADE       : '?..';
CASCADE            : '..';
NULL_DOT           : '?.';
NULL_COALESCE_ASSIGN : '??=';
NULL_COALESCE      : '??';
INT_DIV_ASSIGN     : '~/=';
INT_DIV            : '~/';
URSHIFT_ASSIGN     : '>>>=';
RSHIFT_ASSIGN      : '>>=';
LSHIFT_ASSIGN      : '<<=';
URSHIFT            : '>>>';
RSHIFT             : '>>';
LSHIFT             : '<<';
FAT_ARROW          : '=>';
INC                : '++';
DEC                : '--';
AND                : '&&';
OR                 : '||';
EQ                 : '==';
NE                 : '!=';
LE                 : '<=';
GE                 : '>=';
PLUS_ASSIGN        : '+=';
MINUS_ASSIGN       : '-=';
STAR_ASSIGN        : '*=';
SLASH_ASSIGN       : '/=';
PERCENT_ASSIGN     : '%=';
AMP_ASSIGN         : '&=';
PIPE_ASSIGN        : '|=';
CARET_ASSIGN       : '^=';

// Single-character operators and punctuation
LPAREN   : '(';
RPAREN   : ')';
LBRACE   : '{' { onLBrace(); };
RBRACE   : '}' { onRBrace(Tokens.INTERP_CLOSE); };
LBRACK   : '[';
RBRACK   : ']';
SEMI     : ';';
COMMA    : ',';
DOT      : '.';
COLON    : ':';
ASSIGN   : '=';
LT       : '<';
GT       : '>';
BANG     : '!';
TILDE    : '~';
QUESTION : '?';
PLUS     : '+';
MINUS    : '-';
STAR     : '*';
SLASH    : '/';
PERCENT  : '%';
AMP      : '&';
PIPE     : '|';
CARET    : '^';
HASH     : '#';
AT       : '@';

AT_IDENT
    : '@' [a-zA-Z_$][a-zA-Z0-9_$]*
    ;

IDENT
    : [a-zA-Z_$][a-zA-Z0-9_$]*
    ;

ERROR_CHAR
    : .
    ;

// ---------------- Single-Quoted Single-Line String ('...') ----------------
mode DART_SQ_SINGLE;

DSQ_CLOSE
    : '\'' { exitDartString(); } -> type(STRING_CLOSE)
    ;

DSQ_INTERP_OPEN
    : '${' { enterInterpExpr(Modes.DEFAULT_MODE); } -> type(INTERP_OPEN)
    ;

DSQ_INTERP_IDENT
    : '$' [a-zA-Z_][a-zA-Z0-9_]* -> type(INTERP_IDENT)
    ;

DSQ_ESCAPE
    : '\\' ( 'u{' [0-9a-fA-F]+ '}' | 'u' [0-9a-fA-F][0-9a-fA-F][0-9a-fA-F][0-9a-fA-F] | 'x' [0-9a-fA-F][0-9a-fA-F] | ~[\r\n] ) -> type(STRING_ESCAPE)
    ;

DSQ_BACKSLASH
    : '\\' -> type(STRING_TEXT)
    ;

DSQ_DOLLAR
    : '$' -> type(STRING_TEXT)
    ;

DSQ_UNTERM_NL
    : ( '\r'? '\n' | '\r' ) { exitDartString(); } -> type(NEWLINE)
    ;

DSQ_TEXT
    : ~['\\$\r\n]+ -> type(STRING_TEXT)
    ;

// ---------------- Double-Quoted Single-Line String ("...") ----------------
mode DART_DQ_SINGLE;

DDQ_CLOSE
    : '"' { exitDartString(); } -> type(STRING_CLOSE)
    ;

DDQ_INTERP_OPEN
    : '${' { enterInterpExpr(Modes.DEFAULT_MODE); } -> type(INTERP_OPEN)
    ;

DDQ_INTERP_IDENT
    : '$' [a-zA-Z_][a-zA-Z0-9_]* -> type(INTERP_IDENT)
    ;

DDQ_ESCAPE
    : '\\' ( 'u{' [0-9a-fA-F]+ '}' | 'u' [0-9a-fA-F][0-9a-fA-F][0-9a-fA-F][0-9a-fA-F] | 'x' [0-9a-fA-F][0-9a-fA-F] | ~[\r\n] ) -> type(STRING_ESCAPE)
    ;

DDQ_BACKSLASH
    : '\\' -> type(STRING_TEXT)
    ;

DDQ_DOLLAR
    : '$' -> type(STRING_TEXT)
    ;

DDQ_UNTERM_NL
    : ( '\r'? '\n' | '\r' ) { exitDartString(); } -> type(NEWLINE)
    ;

DDQ_TEXT
    : ~["\\$\r\n]+ -> type(STRING_TEXT)
    ;

// ---------------- Single-Quoted Multiline String ('''...''') ----------------
mode DART_SQ_MULTI;

DMQ_SQ_CLOSE
    : '\'\'\'' { exitDartString(); } -> type(STRING_CLOSE)
    ;

DMQ_SQ_QUOTE
    : ( '\'\'' | '\'' ) -> type(STRING_TEXT)
    ;

DMQ_SQ_INTERP_OPEN
    : '${' { enterInterpExpr(Modes.DEFAULT_MODE); } -> type(INTERP_OPEN)
    ;

DMQ_SQ_INTERP_IDENT
    : '$' [a-zA-Z_][a-zA-Z0-9_]* -> type(INTERP_IDENT)
    ;

DMQ_SQ_ESCAPE
    : '\\' ( 'u{' [0-9a-fA-F]+ '}' | 'u' [0-9a-fA-F][0-9a-fA-F][0-9a-fA-F][0-9a-fA-F] | 'x' [0-9a-fA-F][0-9a-fA-F] | '\r'? '\n' | ~[\r\n] ) -> type(STRING_ESCAPE)
    ;

DMQ_SQ_BACKSLASH
    : '\\' -> type(STRING_TEXT)
    ;

DMQ_SQ_DOLLAR
    : '$' -> type(STRING_TEXT)
    ;

DMQ_SQ_TEXT
    : ~['\\$]+ -> type(STRING_TEXT)
    ;

// ---------------- Double-Quoted Multiline String ("""...""") ----------------
mode DART_DQ_MULTI;

DMQ_DQ_CLOSE
    : '"""' { exitDartString(); } -> type(STRING_CLOSE)
    ;

DMQ_DQ_QUOTE
    : ( '""' | '"' ) -> type(STRING_TEXT)
    ;

DMQ_DQ_INTERP_OPEN
    : '${' { enterInterpExpr(Modes.DEFAULT_MODE); } -> type(INTERP_OPEN)
    ;

DMQ_DQ_INTERP_IDENT
    : '$' [a-zA-Z_][a-zA-Z0-9_]* -> type(INTERP_IDENT)
    ;

DMQ_DQ_ESCAPE
    : '\\' ( 'u{' [0-9a-fA-F]+ '}' | 'u' [0-9a-fA-F][0-9a-fA-F][0-9a-fA-F][0-9a-fA-F] | 'x' [0-9a-fA-F][0-9a-fA-F] | '\r'? '\n' | ~[\r\n] ) -> type(STRING_ESCAPE)
    ;

DMQ_DQ_BACKSLASH
    : '\\' -> type(STRING_TEXT)
    ;

DMQ_DQ_DOLLAR
    : '$' -> type(STRING_TEXT)
    ;

DMQ_DQ_TEXT
    : ~["\\$]+ -> type(STRING_TEXT)
    ;
