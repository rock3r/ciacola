/**
 * Lossless ANTLR4 lexer grammar for PHP 8.3/8.4, supporting HTML template switching across
 * `<?php` / `<?=` / `<?` / `?>` boundaries, PHP 8 attributes (`#[...]`), flexible heredoc and
 * nowdoc literals, double-quoted/backtick interpolation, and PHP Unicode identifiers.
 */
lexer grammar PhpLexer;

options {
    superClass = PhpLexerBase;
}

tokens {
    INTERP_OPEN,
    INTERP_CLOSE
}

// ---------------- HTML Outside PHP Tags (DEFAULT_MODE) ----------------

PHP_OPEN_ECHO
    : '<?=' -> pushMode(PHP_MODE)
    ;

PHP_OPEN_FULL
    : '<?' [pP][hH][pP] { isPhpOpenBoundaryAhead() }? -> pushMode(PHP_MODE)
    ;

PHP_OPEN_SHORT
    : '<?' { isShortOpenTagAhead() }? -> pushMode(PHP_MODE)
    ;

HTML_CHUNK
    : ~[<]+
    | '<'
    ;

// ---------------- PHP Script Mode ----------------
mode PHP_MODE;

PHP_CLOSE
    : '?>' { onPhpClose(); }
    ;

WS
    : [ \t\r\n\u000B\u000C\uFEFF]+
    ;

DOC_COMMENT
    : '/**' ( ~'*' | '*'+ ~[*/] )* '*'+ '/'
    ;

BLOCK_COMMENT
    : '/*' ( ~'*' | '*'+ ~[*/] )* '*'+ '/'
    ;

UNTERMINATED_BLOCK_COMMENT
    : '/*' ( ~'*' | '*'+ ~[*/] )* '*'*
    ;

LINE_COMMENT
    : '//' ( ~[\r\n?] | '?' { _input.LA(1) != '>'.code }? )*
    ;

ATTR_OPEN
    : '#['
    ;

HASH_COMMENT
    : '#' ( ~[\r\n?[] COMMENT_TAIL | '?' { _input.LA(1) != '>'.code }? COMMENT_TAIL )?
    ;

fragment COMMENT_TAIL
    : ( ~[\r\n?] | '?' { _input.LA(1) != '>'.code }? )*
    ;

// Heredocs, nowdocs, and strings
NOWDOC
    : '<<<' [ \t]* '\'' IDENT_START IDENT_CONTINUE* '\'' ( '\r'? '\n' | '\r' ) { consumeNowdoc(); }
    ;

HEREDOC_START
    : '<<<' [ \t]* ( IDENT_START IDENT_CONTINUE* | '"' IDENT_START IDENT_CONTINUE* '"' ) ( '\r'? '\n' | '\r' )
      { enterHeredoc(Modes.PHP_HEREDOC); }
    ;

SINGLE_STRING
    : [bB]? '\'' ( '\\' . | ~['\\] )* '\''
    ;

UNTERMINATED_SINGLE_STRING
    : [bB]? '\'' ( '\\' . | ~['\\] )* '\\'?
    ;

DSTR_OPEN
    : [bB]? '"' -> pushMode(PHP_DSTRING)
    ;

BACKTICK_OPEN
    : '`' -> pushMode(PHP_BACKTICK)
    ;

// Numeric literals
HEX_LITERAL
    : '0' [xX] [0-9a-fA-F] ( '_'? [0-9a-fA-F] )*
    ;

BIN_LITERAL
    : '0' [bB] [01] ( '_'? [01] )*
    ;

OCT_LITERAL
    : '0' [oO] [0-7] ( '_'? [0-7] )*
    | '0' ( '_'? [0-7] )+
    ;

FLOAT_LITERAL
    : ( DIGITS '.' DIGITS? | '.' DIGITS ) ( [eE] [+-]? DIGITS )?
    | DIGITS [eE] [+-]? DIGITS
    ;

DECIMAL_LITERAL
    : '0'
    | [1-9] ( '_'? [0-9] )*
    ;

fragment DIGITS
    : [0-9] ( '_'? [0-9] )*
    ;

// Multi-character operators and punctuation
SPACESHIP            : '<=>';
ELLIPSIS             : '...';
NULLSAFE_ARROW       : '?->';
NULL_COALESCE_ASSIGN : '??=';
NULL_COALESCE        : '??';
IDENTICAL            : '===';
NOT_IDENTICAL        : '!==';
POW_ASSIGN           : '**=';
POW                  : '**';
RSHIFT_ASSIGN        : '>>=';
LSHIFT_ASSIGN        : '<<=';
RSHIFT               : '>>';
LSHIFT               : '<<';
ARROW                : '->';
DOUBLE_ARROW         : '=>';
COLONCOLON           : '::';
DIAMOND_NE           : '<>';
INC                  : '++';
DEC                  : '--';
AND_AND              : '&&';
OR_OR                : '||';
EQ                   : '==';
NE                   : '!=';
LE                   : '<=';
GE                   : '>=';
CONCAT_ASSIGN        : '.=';
ADD_ASSIGN           : '+=';
SUB_ASSIGN           : '-=';
MUL_ASSIGN           : '*=';
DIV_ASSIGN           : '/=';
MOD_ASSIGN           : '%=';
AND_ASSIGN           : '&=';
OR_ASSIGN            : '|=';
XOR_ASSIGN           : '^=';

// Single-character operators and punctuation
LPAREN    : '(';
RPAREN    : ')';
LBRACE    : '{' { onLBraceInPhp(); };
RBRACE    : '}' { onRBraceInPhp(Tokens.INTERP_CLOSE); };
LBRACK    : '[';
RBRACK    : ']';
SEMI      : ';';
COMMA     : ',';
DOT       : '.';
COLON     : ':';
BACKSLASH : '\\';
ASSIGN    : '=';
GT        : '>';
LT        : '<';
BANG      : '!';
TILDE     : '~';
QUESTION  : '?';
PLUS      : '+';
MINUS     : '-';
STAR      : '*';
SLASH     : '/';
BITAND    : '&';
BITOR     : '|';
CARET     : '^';
MOD       : '%';
AT        : '@';
DOLLAR    : '$';

// Variables and identifiers (case-insensitive PHP keywords are classified in PhpSyntaxLexer)
VAR_IDENT
    : '$' IDENT_START IDENT_CONTINUE*
    ;

IDENT
    : IDENT_START IDENT_CONTINUE*
    ;

fragment IDENT_START
    : [a-zA-Z_\u0080-\u{10FFFF}]
    ;

fragment IDENT_CONTINUE
    : [a-zA-Z0-9_\u0080-\u{10FFFF}]
    ;

ERROR_CHAR
    : .
    ;

// ---------------- Double-Quoted String Mode ----------------
mode PHP_DSTRING;

DSTR_CLOSE
    : '"' { afterSimpleVar = false; } -> popMode
    ;

DSTR_ESCAPE
    : '\\' . { afterSimpleVar = false; }
    ;

DSTR_CURLY_OPEN
    : '{$' { onCurlyDollarOpen(Modes.PHP_MODE, Tokens.INTERP_OPEN); }
    ;

DSTR_DOLLAR_CURLY_OPEN
    : '${' { enterBraceInterp(Modes.PHP_MODE); }
    ;

DSTR_VAR
    : '$' IDENT_START IDENT_CONTINUE* { afterSimpleVar = true; }
    ;

DSTR_ARROW_PROP
    : ( '->' | '?->' ) IDENT_START IDENT_CONTINUE* { onSimpleVarMember(Tokens.DSTR_ARROW_PROP, Tokens.DSTR_TEXT); }
    ;

DSTR_INDEX_OPEN
    : '[' { onSimpleVarIndex(Modes.PHP_SIMPLE_INDEX, Tokens.DSTR_INDEX_OPEN, Tokens.DSTR_TEXT); }
    ;

DSTR_BACKSLASH
    : '\\' { afterSimpleVar = false; }
    ;

DSTR_TEXT
    : ~["\\${[?-]+ { afterSimpleVar = false; }
    ;

DSTR_FALLBACK
    : ( '$' | '{' | '[' | '-' | '?' ) { afterSimpleVar = false; } -> type(DSTR_TEXT)
    ;

// ---------------- Backtick Shell String Mode ----------------
mode PHP_BACKTICK;

BT_CLOSE
    : '`' { afterSimpleVar = false; } -> popMode
    ;

BT_ESCAPE
    : '\\' . { afterSimpleVar = false; }
    ;

BT_CURLY_OPEN
    : '{$' { onCurlyDollarOpen(Modes.PHP_MODE, Tokens.INTERP_OPEN); }
    ;

BT_DOLLAR_CURLY_OPEN
    : '${' { enterBraceInterp(Modes.PHP_MODE); }
    ;

BT_VAR
    : '$' IDENT_START IDENT_CONTINUE* { afterSimpleVar = true; }
    ;

BT_ARROW_PROP
    : ( '->' | '?->' ) IDENT_START IDENT_CONTINUE* { onSimpleVarMember(Tokens.BT_ARROW_PROP, Tokens.BT_TEXT); }
    ;

BT_INDEX_OPEN
    : '[' { onSimpleVarIndex(Modes.PHP_SIMPLE_INDEX, Tokens.BT_INDEX_OPEN, Tokens.BT_TEXT); }
    ;

BT_BACKSLASH
    : '\\' { afterSimpleVar = false; }
    ;

BT_TEXT
    : ~[`\\${[?-]+ { afterSimpleVar = false; }
    ;

BT_FALLBACK
    : ( '$' | '{' | '[' | '-' | '?' ) { afterSimpleVar = false; } -> type(BT_TEXT)
    ;

// ---------------- Heredoc Body Mode ----------------
mode PHP_HEREDOC;

HEREDOC_NL
    : ( '\r'? '\n' | '\r' ) { onHeredocNewline(); }
    ;

HEREDOC_ESCAPE
    : '\\' ~[\r\n] { afterSimpleVar = false; }
    ;

HEREDOC_CURLY_OPEN
    : '{$' { onCurlyDollarOpen(Modes.PHP_MODE, Tokens.INTERP_OPEN); }
    ;

HEREDOC_DOLLAR_CURLY_OPEN
    : '${' { enterBraceInterp(Modes.PHP_MODE); }
    ;

HEREDOC_VAR
    : '$' IDENT_START IDENT_CONTINUE* { afterSimpleVar = true; }
    ;

HEREDOC_ARROW_PROP
    : ( '->' | '?->' ) IDENT_START IDENT_CONTINUE* { onSimpleVarMember(Tokens.HEREDOC_ARROW_PROP, Tokens.HEREDOC_TEXT); }
    ;

HEREDOC_INDEX_OPEN
    : '[' { onSimpleVarIndex(Modes.PHP_SIMPLE_INDEX, Tokens.HEREDOC_INDEX_OPEN, Tokens.HEREDOC_TEXT); }
    ;

HEREDOC_BACKSLASH
    : '\\' { afterSimpleVar = false; }
    ;

HEREDOC_TEXT
    : ~[\r\n\\${[?-]+ { afterSimpleVar = false; }
    ;

HEREDOC_FALLBACK
    : ( '$' | '{' | '[' | '-' | '?' ) { afterSimpleVar = false; } -> type(HEREDOC_TEXT)
    ;

// ---------------- Simple String Variable Index Mode ($arr[key]) ----------------
mode PHP_SIMPLE_INDEX;

SIDX_CLOSE
    : ']' -> popMode
    ;

SIDX_VAR
    : '$' IDENT_START IDENT_CONTINUE*
    ;

SIDX_NUM
    : [0-9]+
    ;

SIDX_IDENT
    : IDENT_START IDENT_CONTINUE*
    ;

SIDX_MINUS
    : '-'
    ;

SIDX_OTHER
    : . -> popMode
    ;
