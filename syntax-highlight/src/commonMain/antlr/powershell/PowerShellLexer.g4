/**
 * Lossless ANTLR4 lexer grammar for PowerShell 5.1 / 7.x, supporting
 * line/block comments (including comment-based help), #requires directives,
 * single/double-quoted strings with backtick and doubled-quote escapes,
 * expandable and literal here-strings (@"..."@ and @'...'@ with column-0 close),
 * nested $(...) subexpressions, scoped/braced/special/splatted variables,
 * cmdlet names, dash operators, type accelerators, attributes, and PS 7 operators.
 */
lexer grammar PowerShellLexer;

import UnicodeIdent;

options {
    superClass = PowerShellLexerBase;
}

tokens {
    SUBEXPR_CLOSE
}

// Whitespace, newlines, and backtick line continuations
WS
    : [ \t\f\uFEFF]+ { onWhitespace(); }
    ;

NEWLINE
    : ( '\r'? '\n' | '\r' ) { onWhitespace(); }
    ;

LINE_CONTINUATION
    : '`' ( '\r'? '\n' | '\r' ) { onWhitespace(); }
    ;

// Comments (block comments do not nest; line comments require word boundary)
BLOCK_COMMENT
    : '<#' ( ~'#' | '#'+ ~[#>] )* '#'+ '>' { onWhitespace(); }
    ;

UNTERMINATED_BLOCK_COMMENT
    : '<#' ( ~'#' | '#'+ ~[#>] )* '#'* { onWhitespace(); }
    ;

LINE_COMMENT
    : '#' { isLineCommentStart() }? ~[\r\n]* { onWhitespace(); }
    ;

// Here-strings (@"...\n"@ and @'...\n'@)
HEREDOC_EXPAND_START
    : '@"' [ \t]* ( '\r'? '\n' | '\r' ) { onNonBoundaryToken(); } -> pushMode(PS_HEREDOC_EXPAND)
    ;

HEREDOC_LITERAL_START
    : '@\'' [ \t]* ( '\r'? '\n' | '\r' ) { onNonBoundaryToken(); } -> pushMode(PS_HEREDOC_LITERAL)
    ;

// Strings
DQUOTE_START
    : '"' { onNonBoundaryToken(); } -> pushMode(PS_DQUOTE)
    ;

SINGLE_STRING
    : '\'' ( '\'\'' | ~['] )* '\''? { onBoundaryToken(); }
    ;

// Subexpressions, arrays, hashtables, and variables
SUBEXPR_START
    : '$(' { enterSubExpr(); }
    ;

ARRAY_SUBEXPR_START
    : '@(' { onDefaultLParen(); }
    ;

HASH_LITERAL_START
    : '@{' { onBoundaryToken(); }
    ;

BRACED_VAR
    : '${' ( '`' . | ~[}\r\n] )* '}'? { onNonBoundaryToken(); }
    ;

SCOPED_VAR
    : '$' PS_VAR_NAME ':' PS_VAR_NAME { onNonBoundaryToken(); }
    ;

SPECIAL_VAR
    : '$' ( '?' | '$' | '^' ) { onNonBoundaryToken(); }
    ;

SIMPLE_VAR
    : '$' PS_VAR_NAME { onNonBoundaryToken(); }
    ;

SPLAT_VAR
    : '@' PS_VAR_NAME { onNonBoundaryToken(); }
    ;

// Numeric literals (hex, binary, float, decimal with PS multipliers and type suffixes)
HEX_LITERAL
    : '0' [xX] HEX_DIGITS PS_NUM_SUFFIX? { onNonBoundaryToken(); }
    ;

BIN_LITERAL
    : '0' [bB] BIN_DIGITS PS_NUM_SUFFIX? { onNonBoundaryToken(); }
    ;

FLOAT_LITERAL
    : ( DIGITS '.' DIGITS | '.' DIGITS ) ( [eE] [+-]? DIGITS )? PS_FLOAT_SUFFIX? { onNonBoundaryToken(); }
    | DIGITS [eE] [+-]? DIGITS PS_FLOAT_SUFFIX? { onNonBoundaryToken(); }
    | DIGITS [dD] PS_MULTIPLIER? { onNonBoundaryToken(); }
    ;

DECIMAL_LITERAL
    : DIGITS PS_NUM_SUFFIX? { onNonBoundaryToken(); }
    ;

fragment DIGITS
    : [0-9] ( '_'* [0-9] )*
    ;

fragment HEX_DIGITS
    : [0-9a-fA-F] ( '_'* [0-9a-fA-F] )*
    ;

fragment BIN_DIGITS
    : [01] ( '_'* [01] )*
    ;

fragment PS_MULTIPLIER
    : [kKmMgGtTpP][bB]
    ;

fragment PS_INT_SUFFIX
    : [uU][lLyYsS]
    | [lL][uU]
    | [lLuUnNyYsS]
    ;

fragment PS_NUM_SUFFIX
    : PS_INT_SUFFIX PS_MULTIPLIER?
    | PS_MULTIPLIER
    ;

fragment PS_FLOAT_SUFFIX
    : [dD] PS_MULTIPLIER?
    | PS_MULTIPLIER
    ;

// Redirections and multi-character operators
STREAM_REDIR
    : [1-6*] ( '>&' [1-6] | '>>' | '>' ) { onBoundaryToken(); }
    ;

STOP_PARSING
    : '--%' { onBoundaryToken(); }
    ;

NULL_COALESCE_ASSIGN : '??=' { onBoundaryToken(); };
NULL_COALESCE        : '??' { onBoundaryToken(); };
NULL_COND_DOT        : '?.' { onBoundaryToken(); };
AND_AND              : '&&' { onBoundaryToken(); };
OR_OR                : '||' { onBoundaryToken(); };
COLONCOLON           : '::' { onBoundaryToken(); };
RANGE                : '..' { onBoundaryToken(); };
GT_APPEND            : '>>' { onBoundaryToken(); };
INC                  : '++' { onNonBoundaryToken(); };
DEC                  : '--' { onNonBoundaryToken(); };
ADD_ASSIGN           : '+=' { onBoundaryToken(); };
SUB_ASSIGN           : '-=' { onBoundaryToken(); };
MUL_ASSIGN           : '*=' { onBoundaryToken(); };
DIV_ASSIGN           : '/=' { onBoundaryToken(); };
MOD_ASSIGN           : '%=' { onBoundaryToken(); };

// Dash operators (-eq, -ceq, -and, ...) and command parameters (-Path, -Force, --flag)
CLI_LONG_FLAG
    : '--' [a-zA-Z_][a-zA-Z0-9_-]* { onNonBoundaryToken(); }
    ;

DASH_WORD
    : '-' [a-zA-Z_][a-zA-Z0-9_]* { onNonBoundaryToken(); }
    ;

// Punctuation and single-character operators
LPAREN    : '(' { onDefaultLParen(); };
RPAREN    : ')' { onDefaultRParen(Tokens.SUBEXPR_CLOSE); };
LBRACE    : '{' { onBoundaryToken(); };
RBRACE    : '}' { onBoundaryToken(); };
LBRACK    : '[' { onBoundaryToken(); };
RBRACK    : ']' { onBoundaryToken(); };
SEMI      : ';' { onBoundaryToken(); };
COMMA     : ',' { onBoundaryToken(); };
DOT       : '.' { onBoundaryToken(); };
COLON     : ':' { onBoundaryToken(); };
BACKSLASH : '\\' { onBoundaryToken(); };

ASSIGN   : '=' { onBoundaryToken(); };
PIPE     : '|' { onBoundaryToken(); };
AMP      : '&' { onBoundaryToken(); };
GT       : '>' { onBoundaryToken(); };
LT       : '<' { onBoundaryToken(); };
BANG     : '!' { onBoundaryToken(); };
PLUS     : '+' { onBoundaryToken(); };
MINUS    : '-' { onBoundaryToken(); };
STAR     : '*' { onBoundaryToken(); };
SLASH    : '/' { onBoundaryToken(); };
MOD      : '%' { onBoundaryToken(); };
QUESTION : '?' { onBoundaryToken(); };
AT       : '@' { onNonBoundaryToken(); };
DOLLAR   : '$' { onNonBoundaryToken(); };
HASH     : '#' { onNonBoundaryToken(); };

// Backtick escapes outside quotes
BACKTICK_ESCAPE
    : '`' ~[\r\n] { onNonBoundaryToken(); }
    ;

BACKTICK
    : '`' { onNonBoundaryToken(); }
    ;

// Cmdlet names (Verb-Noun) and identifiers (case-insensitive keywords classified in PowerShellSyntaxLexer)
CMDLET_NAME
    : IDENT_START IDENT_CONTINUE* ( '-' IDENT_CONTINUE+ )+ { onNonBoundaryToken(); }
    ;

IDENT
    : IDENT_START IDENT_CONTINUE* { onNonBoundaryToken(); }
    ;

fragment IDENT_START
    : UNICODE_XID_START | '_'
    ;

fragment IDENT_CONTINUE
    : UNICODE_XID_CONTINUE | '_'
    ;

fragment PS_VAR_NAME
    : ( UNICODE_XID_CONTINUE | '_' )+
    ;

ERROR_CHAR
    : . { onNonBoundaryToken(); }
    ;

// ---------------- Double-Quoted Expandable String ("...") ----------------

mode PS_DQUOTE;

DQ_ESCAPED_QUOTE  : '""' ;
DQ_CLOSE          : '"' { onBoundaryToken(); } -> popMode ;
DQ_UNICODE_ESCAPE : '`u{' [0-9a-fA-F]+ '}' ;
DQ_ESCAPE         : '`' . ;
DQ_BACKTICK       : '`' ;
DQ_SUBEXPR_START  : '$(' { enterSubExpr(); } ;
DQ_BRACED_VAR     : '${' ( '`' . | ~[}\r\n] )* '}'? ;
DQ_SCOPED_VAR     : '$' PS_VAR_NAME ':' PS_VAR_NAME ;
DQ_SPECIAL_VAR    : '$' ( '?' | '$' | '^' ) ;
DQ_SIMPLE_VAR     : '$' PS_VAR_NAME ;
DQ_DOLLAR         : '$' ;
DQ_TEXT           : ~["`$]+ ;

// ---------------- Expandable Here-String (@"..."@) ----------------

mode PS_HEREDOC_EXPAND;

HE_CLOSE          : '"@' { isAtLineStart() }? { onBoundaryToken(); } -> popMode ;
HE_UNICODE_ESCAPE : '`u{' [0-9a-fA-F]+ '}' ;
HE_ESCAPE         : '`' . ;
HE_BACKTICK       : '`' ;
HE_SUBEXPR_START  : '$(' { enterSubExpr(); } ;
HE_BRACED_VAR     : '${' ( '`' . | ~[}\r\n] )* '}'? ;
HE_SCOPED_VAR     : '$' PS_VAR_NAME ':' PS_VAR_NAME ;
HE_SPECIAL_VAR    : '$' ( '?' | '$' | '^' ) ;
HE_SIMPLE_VAR     : '$' PS_VAR_NAME ;
HE_DOLLAR         : '$' ;
HE_NEWLINE        : '\r'? '\n' | '\r' ;
HE_DQUOTE_AT      : '"@' ;
HE_QUOTE          : '"' ;
HE_TEXT           : ~["`$\r\n]+ ;

// ---------------- Literal Here-String (@'...'@) ----------------

mode PS_HEREDOC_LITERAL;

HL_CLOSE     : '\'@' { isAtLineStart() }? { onBoundaryToken(); } -> popMode ;
HL_NEWLINE   : '\r'? '\n' | '\r' ;
HL_SQUOTE_AT : '\'@' ;
HL_SQUOTE    : '\'' ;
HL_TEXT      : ~['\r\n]+ ;
