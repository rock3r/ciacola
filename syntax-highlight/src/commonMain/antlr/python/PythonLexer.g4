/**
 * Lossless ANTLR4 lexer grammar for Python 3.12+ including PEP 701 f-string interpolation,
 * format specs, raw/bytes/unicode string combinations, and contextual soft keywords.
 */
lexer grammar PythonLexer;

options {
    superClass = PythonLexerBase;
}

tokens {
    FSTR_INTERP_CLOSE,
    FSTR_FORMAT_COLON
}

// Whitespace, comments, and line continuations
WS
    : [ \t\f]+
    ;

NEWLINE
    : ( '\r'? '\n'
      | '\r'
      ) { onDefaultNewline(); }
    ;

LINE_CONTINUATION
    : '\\' [ \t]* ('\r'? '\n' | '\r')
    ;

COMMENT
    : '#' ~[\r\n]*
    ;

// F-string starts (must appear before normal string & NAME rules)
F_TRIPLE_DOUBLE_RAW_START
    : ( [fF] [rR] | [rR] [fF] ) '"""'
      { enterFString(singleLine = false, isDoubleQuote = true, targetMode = Modes.FSTRING_RAW_TRIPLE_DOUBLE) }
    ;

F_TRIPLE_SINGLE_RAW_START
    : ( [fF] [rR] | [rR] [fF] ) '\'\'\''
      { enterFString(singleLine = false, isDoubleQuote = false, targetMode = Modes.FSTRING_RAW_TRIPLE_SINGLE) }
    ;

F_TRIPLE_DOUBLE_START
    : [fF] '"""'
      { enterFString(singleLine = false, isDoubleQuote = true, targetMode = Modes.FSTRING_TRIPLE_DOUBLE) }
    ;

F_TRIPLE_SINGLE_START
    : [fF] '\'\'\''
      { enterFString(singleLine = false, isDoubleQuote = false, targetMode = Modes.FSTRING_TRIPLE_SINGLE) }
    ;

F_DOUBLE_RAW_START
    : ( [fF] [rR] | [rR] [fF] ) '"'
      { enterFString(singleLine = true, isDoubleQuote = true, targetMode = Modes.FSTRING_RAW_DOUBLE) }
    ;

F_SINGLE_RAW_START
    : ( [fF] [rR] | [rR] [fF] ) '\''
      { enterFString(singleLine = true, isDoubleQuote = false, targetMode = Modes.FSTRING_RAW_SINGLE) }
    ;

F_DOUBLE_START
    : [fF] '"'
      { enterFString(singleLine = true, isDoubleQuote = true, targetMode = Modes.FSTRING_DOUBLE) }
    ;

F_SINGLE_START
    : [fF] '\''
      { enterFString(singleLine = true, isDoubleQuote = false, targetMode = Modes.FSTRING_SINGLE) }
    ;

// Standard, raw, bytes, and unicode strings (terminated or cleanly unterminated)
TRIPLE_DOUBLE_STRING
    : NON_F_PREFIX? '"""'
      ( '\\' .
      | ~[\\"]
      | '"' ( '\\' . | ~[\\"] )
      | '""' ( '\\' . | ~[\\"] )
      )*
      ( '"""' | '""' '\\'? | '"' '\\'? | '\\' )?
    ;

TRIPLE_SINGLE_STRING
    : NON_F_PREFIX? '\'\'\''
      ( '\\' .
      | ~[\\']
      | '\'' ( '\\' . | ~[\\'] )
      | '\'\'' ( '\\' . | ~[\\'] )
      )*
      ( '\'\'\'' | '\'\'' '\\'? | '\'' '\\'? | '\\' )?
    ;

DOUBLE_STRING
    : NON_F_PREFIX? '"' ( '\\' ~[\r\n] | ~["\\\r\n] )* ( '"' | '\\' )?
    ;

SINGLE_STRING
    : NON_F_PREFIX? '\'' ( '\\' ~[\r\n] | ~['\\\r\n] )* ( '\'' | '\\' )?
    ;

fragment NON_F_PREFIX
    : [rRbBuU]
    | [rR] [bB]
    | [bB] [rR]
    ;

// Hard keywords
AND      : 'and' { onNonHeaderToken(); };
AS       : 'as' { onNonHeaderToken(); };
ASSERT   : 'assert' { onNonHeaderToken(); };
ASYNC    : 'async';
AWAIT    : 'await' { onNonHeaderToken(); };
BREAK    : 'break' { onNonHeaderToken(); };
CLASS    : 'class' { onStatementHeaderKeyword(); };
CONTINUE : 'continue' { onNonHeaderToken(); };
DEF      : 'def' { onStatementHeaderKeyword(); };
DEL      : 'del' { onNonHeaderToken(); };
ELIF     : 'elif' { onStatementHeaderKeyword(); };
ELSE     : 'else' { onStatementHeaderKeyword(); };
EXCEPT   : 'except' { onStatementHeaderKeyword(); };
FINALLY  : 'finally' { onStatementHeaderKeyword(); };
FOR      : 'for' { onStatementHeaderKeyword(); };
FROM     : 'from' { onNonHeaderToken(); };
GLOBAL   : 'global' { onNonHeaderToken(); };
IF       : 'if' { onStatementHeaderKeyword(); };
IMPORT   : 'import' { onNonHeaderToken(); };
IN       : 'in' { onNonHeaderToken(); };
IS       : 'is' { onNonHeaderToken(); };
LAMBDA   : 'lambda' { onLambda(); };
NONLOCAL : 'nonlocal' { onNonHeaderToken(); };
NOT      : 'not' { onNonHeaderToken(); };
OR       : 'or' { onNonHeaderToken(); };
PASS     : 'pass' { onNonHeaderToken(); };
RAISE    : 'raise' { onNonHeaderToken(); };
RETURN   : 'return' { onNonHeaderToken(); };
TRY      : 'try' { onStatementHeaderKeyword(); };
WHILE    : 'while' { onStatementHeaderKeyword(); };
WITH     : 'with' { onStatementHeaderKeyword(); };
YIELD    : 'yield' { onNonHeaderToken(); };

// Constants
TRUE  : 'True' { onNonHeaderToken(); };
FALSE : 'False' { onNonHeaderToken(); };
NONE  : 'None' { onNonHeaderToken(); };

// Numeric literals
IMAG_NUMBER
    : ( FLOAT_NUMBER | DIGIT_PART ) [jJ] { onNonHeaderToken(); }
    ;

FLOAT_NUMBER
    : ( POINT_FLOAT
      | EXPONENT_FLOAT
      ) { onNonHeaderToken(); }
    ;

HEX_INTEGER
    : '0' [xX] ( '_'? [0-9a-fA-F] )+ { onNonHeaderToken(); }
    ;

BIN_INTEGER
    : '0' [bB] ( '_'? [01] )+ { onNonHeaderToken(); }
    ;

OCT_INTEGER
    : '0' [oO] ( '_'? [0-7] )+ { onNonHeaderToken(); }
    ;

DEC_INTEGER
    : ( [1-9] ( '_'? [0-9] )*
      | '0'+ ( '_'? '0' )*
      ) { onNonHeaderToken(); }
    ;

fragment DIGIT_PART
    : [0-9] ( '_'? [0-9] )*
    ;

fragment POINT_FLOAT
    : DIGIT_PART? '.' DIGIT_PART
    | DIGIT_PART '.'
    ;

fragment EXPONENT_FLOAT
    : ( DIGIT_PART | POINT_FLOAT ) [eE] [+-]? DIGIT_PART
    ;

// Multi-character operators and delimiters
ELLIPSIS        : '...';
WALRUS          : ':=';
ARROW           : '->';
POWER_ASSIGN    : '**=';
FLOORDIV_ASSIGN : '//=';
LSHIFT_ASSIGN   : '<<=';
RSHIFT_ASSIGN   : '>>=';
POWER           : '**';
FLOORDIV        : '//';
LSHIFT          : '<<';
RSHIFT          : '>>';
LE              : '<=';
GE              : '>=';
EQ              : '==';
NE              : '!=';
ADD_ASSIGN      : '+=';
SUB_ASSIGN      : '-=';
MUL_ASSIGN      : '*=';
DIV_ASSIGN      : '/=';
MOD_ASSIGN      : '%=';
AT_ASSIGN       : '@=';
AND_ASSIGN      : '&=';
OR_ASSIGN       : '|=';
XOR_ASSIGN      : '^=';

// Single-character delimiters & operators
LPAREN : '(' { onOpenParenOrBracket(); };
RPAREN : ')' { onCloseParenOrBracket(); };
LBRACK : '[' { onOpenParenOrBracket(); };
RBRACK : ']' { onCloseParenOrBracket(); };
LBRACE : '{' { onLBrace(); };
RBRACE : '}' { onRBrace(Tokens.FSTR_INTERP_CLOSE); };
COLON  : ':' { onColon(Modes.FSTRING_FORMAT_SPEC, Tokens.FSTR_FORMAT_COLON); };
COMMA  : ',';
SEMI   : ';' { onSemicolon(); };
DOT    : '.';
AT     : '@';
ASSIGN : '=';
PLUS   : '+';
MINUS  : '-';
STAR   : '*';
SLASH  : '/';
PERCENT: '%';
AMP    : '&';
PIPE   : '|';
CARET  : '^';
TILDE  : '~';
LT     : '<';
GT     : '>';
BANG   : '!';

// Identifiers (including soft keywords match, case, type and Unicode BMP + SMP letters/marks)
NAME
    : ID_START ID_CONTINUE* { onName(text); }
    ;

fragment ID_START
    : [a-zA-Z_]
    | [\u00C0-\u02AF\u0370-\u1FFF\u2100-\u2FFF\u3040-\uD7FF\uF900-\uFFEF\u{10000}-\u{1EFFF}\u{20000}-\u{2FA1F}]
    ;

fragment ID_CONTINUE
    : ID_START
    | [0-9]
    | [\u0300-\u036F\u1AB0-\u1AFF\u1DC0-\u1DFF\u20D0-\u20FF\uFE20-\uFE2F]
    ;

ERROR_CHAR
    : .
    ;

// ---------------- F-String Modes ----------------

mode FSTRING_DOUBLE;
FD_ESCAPED_BRACE : '{{' | '}}' ;
FD_INTERP_OPEN   : '{' { enterInterp(Modes.DEFAULT_MODE); } ;
FD_NAMED_ESCAPE  : '\\N{' ~[}\r\n]* '}' ;
FD_ESCAPE        : '\\' ( '\r'? '\n' | '\r' | ~[{}\r\n] ) ;
FD_BACKSLASH     : '\\' ;
FD_CLOSE         : '"' { exitFString(); } ;
FD_UNTERM_NL     : ( '\r'? '\n' | '\r' ) { exitFString(); } ;
FD_TEXT          : ~["{}\\\r\n]+ ;

mode FSTRING_SINGLE;
FS_ESCAPED_BRACE : '{{' | '}}' ;
FS_INTERP_OPEN   : '{' { enterInterp(Modes.DEFAULT_MODE); } ;
FS_NAMED_ESCAPE  : '\\N{' ~[}\r\n]* '}' ;
FS_ESCAPE        : '\\' ( '\r'? '\n' | '\r' | ~[{}\r\n] ) ;
FS_BACKSLASH     : '\\' ;
FS_CLOSE         : '\'' { exitFString(); } ;
FS_UNTERM_NL     : ( '\r'? '\n' | '\r' ) { exitFString(); } ;
FS_TEXT          : ~['{}\\\r\n]+ ;

mode FSTRING_RAW_DOUBLE;
FRD_ESCAPED_BRACE : '{{' | '}}' ;
FRD_INTERP_OPEN   : '{' { enterInterp(Modes.DEFAULT_MODE); } ;
FRD_ESCAPE        : '\\' ["'\\] ;
FRD_BACKSLASH     : '\\' ;
FRD_CLOSE         : '"' { exitFString(); } ;
FRD_UNTERM_NL     : ( '\r'? '\n' | '\r' ) { exitFString(); } ;
FRD_TEXT          : ~["{}\\\r\n]+ ;

mode FSTRING_RAW_SINGLE;
FRS_ESCAPED_BRACE : '{{' | '}}' ;
FRS_INTERP_OPEN   : '{' { enterInterp(Modes.DEFAULT_MODE); } ;
FRS_ESCAPE        : '\\' ["'\\] ;
FRS_BACKSLASH     : '\\' ;
FRS_CLOSE         : '\'' { exitFString(); } ;
FRS_UNTERM_NL     : ( '\r'? '\n' | '\r' ) { exitFString(); } ;
FRS_TEXT          : ~['{}\\\r\n]+ ;

mode FSTRING_TRIPLE_DOUBLE;
FTD_ESCAPED_BRACE : '{{' | '}}' ;
FTD_INTERP_OPEN   : '{' { enterInterp(Modes.DEFAULT_MODE); } ;
FTD_NAMED_ESCAPE  : '\\N{' ~[}\r\n]* '}' ;
FTD_ESCAPE        : '\\' ( '\r'? '\n' | '\r' | ~[{}] ) ;
FTD_BACKSLASH     : '\\' ;
FTD_CLOSE         : '"""' { exitFString(); } ;
FTD_QUOTES        : '""' | '"' ;
FTD_TEXT          : ~["{}\\]+ ;

mode FSTRING_TRIPLE_SINGLE;
FTS_ESCAPED_BRACE : '{{' | '}}' ;
FTS_INTERP_OPEN   : '{' { enterInterp(Modes.DEFAULT_MODE); } ;
FTS_NAMED_ESCAPE  : '\\N{' ~[}\r\n]* '}' ;
FTS_ESCAPE        : '\\' ( '\r'? '\n' | '\r' | ~[{}] ) ;
FTS_BACKSLASH     : '\\' ;
FTS_CLOSE         : '\'\'\'' { exitFString(); } ;
FTS_QUOTES        : '\'\'' | '\'' ;
FTS_TEXT          : ~['{}\\]+ ;

mode FSTRING_RAW_TRIPLE_DOUBLE;
FRTD_ESCAPED_BRACE : '{{' | '}}' ;
FRTD_INTERP_OPEN   : '{' { enterInterp(Modes.DEFAULT_MODE); } ;
FRTD_ESCAPE        : '\\' ["'\\] ;
FRTD_BACKSLASH     : '\\' ;
FRTD_CLOSE         : '"""' { exitFString(); } ;
FRTD_QUOTES        : '""' | '"' ;
FRTD_TEXT          : ~["{}\\]+ ;

mode FSTRING_RAW_TRIPLE_SINGLE;
FRTS_ESCAPED_BRACE : '{{' | '}}' ;
FRTS_INTERP_OPEN   : '{' { enterInterp(Modes.DEFAULT_MODE); } ;
FRTS_ESCAPE        : '\\' ["'\\] ;
FRTS_BACKSLASH     : '\\' ;
FRTS_CLOSE         : '\'\'\'' { exitFString(); } ;
FRTS_QUOTES        : '\'\'' | '\'' ;
FRTS_TEXT          : ~['{}\\]+ ;

mode FSTRING_FORMAT_SPEC;
FFS_INTERP_OPEN  : '{' { enterInterp(Modes.DEFAULT_MODE); } ;
FFS_INTERP_CLOSE : '}' { onFormatSpecRBrace(Tokens.FSTR_INTERP_CLOSE); } ;
FFS_NEWLINE      : ( '\r'? '\n' | '\r' ) { onFormatSpecNewline(); } ;
FFS_TEXT         : ~[{}\r\n]+ ;
