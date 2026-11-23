/**
 * Lossless ANTLR4 lexer grammar for Shell (POSIX sh, Bash, and Zsh), supporting
 * shebangs, word-boundary comments, single/double/ANSI-C/locale quotes, nested command
 * and process substitutions, parameter expansions, arithmetic scopes, and queued heredocs.
 */
lexer grammar ShellLexer;

options {
    superClass = ShellLexerBase;
}

tokens {
    HEREDOC_DELIM_END
}

// Shebang and comments (consume literal '#' prefix before semantic predicate)
SHEBANG
    : '#!' { isAtStartOfInput() }? ~[\r\n]* { onWordBoundary(); }
    ;

COMMENT
    : '#' { isCommentStart() }? ~[\r\n]* { onWordBoundary(); }
    ;

// Whitespace, line continuations, and newlines
WS
    : [ \t\f\uFEFF]+ { onWordBoundary(); }
    ;

LINE_CONTINUATION
    : '\\' ( '\r'? '\n' | '\r' )
    ;

NEWLINE
    : ( '\r'? '\n' | '\r' ) { onDefaultNewline(Modes.HEREDOC_LITERAL, Modes.HEREDOC_EXPAND); }
    ;

// Quotes and strings
ANSI_C_STRING
    : '$' '\'' ( '\\' ( '\r'? '\n' | '\r' | ~[\r\n] ) | ~['\\] )* ( '\'' | '\\' )? { onNonCommandArgToken(); }
    ;

LOCALE_DQUOTE_START
    : '$"' { pushDQuote(Modes.DQUOTE); }
    ;

DQUOTE_START
    : '"' { pushDQuote(Modes.DQUOTE); }
    ;

SINGLE_STRING
    : '\'' ~[']* '\''? { onNonCommandArgToken(); }
    ;

// Expansions, substitutions, and arithmetic
ARITH_EXP_START
    : '$((' { hasBalancedDoubleParenClose() }? { pushArith(Modes.ARITH); }
    ;

CMD_SUB_START
    : '$(' { pushCmdSub(); }
    ;

PARAM_EXP_START
    : '${' { pushParamExp(Modes.PARAM_EXP); }
    ;

PROC_SUB_IN
    : '<(' { pushCmdSub(); }
    ;

PROC_SUB_OUT
    : '>(' { pushCmdSub(); }
    ;

ARITH_CMD_START
    : '((' { hasBalancedDoubleParenClose() }? { pushArith(Modes.ARITH, isCommand = true); }
    ;

BACKTICK
    : '`' { onBacktick(); }
    ;

SIMPLE_VAR
    : '$' [a-zA-Z_][a-zA-Z0-9_]* { onNonCommandArgToken(); }
    ;

SPECIAL_VAR
    : '$' [0-9@#?$!*-] { onNonCommandArgToken(); }
    ;

DOLLAR
    : '$' { onNonCommandArgToken(); }
    ;

// Here-strings, heredocs, and redirections
HERE_STRING
    : ( '{' [a-zA-Z_][a-zA-Z0-9_]* '}' | [0-9]+ )? '<<<' { onWordBoundary(); }
    ;

HEREDOC_STRIP_OP
    : ( '{' [a-zA-Z_][a-zA-Z0-9_]* '}' | [0-9]+ )? '<<-' { onHeredocOp(stripTabs = true, Modes.HEREDOC_DELIM); }
    ;

HEREDOC_OP
    : ( '{' [a-zA-Z_][a-zA-Z0-9_]* '}' | [0-9]+ )? '<<' { onHeredocOp(stripTabs = false, Modes.HEREDOC_DELIM); }
    ;

FD_REDIR
    : '{' [a-zA-Z_][a-zA-Z0-9_]* '}' ( '>&' | '<&' | '<>' | '>>' | '>|' | '>' | '<' ) { onWordBoundary(); }
    ;

NUM_REDIR
    : [0-9]+ ( '>&' | '<&' | '<>' | '>>' | '>|' | '>' | '<' ) { onWordBoundary(); }
    ;

AMP_GT_APPEND : '&>>' { onWordBoundary(); };
AMP_GT        : '&>' { onWordBoundary(); };
PIPE_AMP      : '|&' { onPipeOrListOp(); };
GT_APPEND     : '>>' { onWordBoundary(); };
GT_CLOBBER    : '>|' { onWordBoundary(); };
LT_GT         : '<>' { onWordBoundary(); };
GT_AMP        : '>&' { onWordBoundary(); };
LT_AMP        : '<&' { onWordBoundary(); };

// Pipelines, lists, case terminators, and test operators
AND_AND       : '&&' { onPipeOrListOp(); };
OR_OR         : '||' { onPipeOrListOp(); };
SEMI_SEMI_AMP : ';;&' { onCaseArmTerminator(); };
SEMI_SEMI     : ';;' { onCaseArmTerminator(); };
SEMI_AMP      : ';&' { onCaseArmTerminator(); };
EQ_TILDE      : '=~' { onWordPart(); };
EQ_EQ         : '==' { onWordPart(); };
BANG_EQ       : '!=' { onWordPart(); };
PLUS_EQ       : '+=' { onWordPart(); };
LBRACK_LBRACK : '[[' { onWordPart(); };
RBRACK_RBRACK : ']]' { onWordPart(); };

LPAREN : '(' { onLParen(); };
RPAREN : ')' { onRParen(); };
LBRACE : '{' { onLBrace(); };
RBRACE : '}' { onWordPart(); };
LBRACK : '[' { onWordPart(); };
RBRACK : ']' { onWordPart(); };
SEMI   : ';' { onCommandSeparator(); };
PIPE   : '|' { onPipeOrListOp(); };
AMP    : '&' { onCommandSeparator(); };
LT     : '<' { onWordBoundary(); };
GT     : '>' { onWordBoundary(); };
EQ     : '=' { onAssignmentEq(); };
PLUS   : '+' { onWordPart(); };
BANG   : '!' { onWordPart(); };
COMMA  : ',' { onWordPart(); };
HASH   : '#' { onWordPart(); };

// Numeric literals (placed before WORD so pure numbers win over WORD)
HEX_INT
    : '0' [xX] [0-9a-fA-F]+ { onNonCommandArgToken(); }
    ;

BASE_INT
    : [0-9]+ '#' [0-9a-zA-Z_@]+ { onNonCommandArgToken(); }
    ;

FLOAT
    : [0-9]+ '.' [0-9]+ { onNonCommandArgToken(); }
    ;

INT
    : [0-9]+ { onNonCommandArgToken(); }
    ;

// Flags / options and single-dash / dot tokens
FLAG
    : '-' '-'? [a-zA-Z0-9][a-zA-Z0-9_-]* { onNonCommandArgToken(); }
    ;

MINUS
    : '-' { onNonCommandArgToken(); }
    ;

DOT
    : '.' { onDefaultWord(text); }
    ;

// Escaped characters outside quotes
ESCAPED_BACKTICK
    : '\\`' { onEscapedBacktick(); }
    ;

ESCAPED_CHAR
    : '\\' ~[\r\n] { onNonCommandArgToken(); }
    ;

BACKSLASH
    : '\\' { onNonCommandArgToken(); }
    ;

// Unquoted shell words (including internal `#` and `-`, plus Unicode letters/emoji)
WORD
    : WORD_START WORD_CONT* { onDefaultWord(text); }
    ;

fragment WORD_START
    : ~[ \t\f\r\n\uFEFF'"`\\$&|;()<>{}[\]=,+!#-]
    ;

fragment WORD_CONT
    : ~[ \t\f\r\n\uFEFF'"`\\$&|;()<>{}[\]=,+!]
    ;

ERROR_CHAR
    : . { onWordPart(); }
    ;

// ---------------- Double-Quoted Strings ("..." and $"...") ----------------

mode DQUOTE;

DQ_CLOSE            : '"' { popMode(); } ;
DQ_ARITH_START      : '$((' { hasBalancedDoubleParenClose() }? { pushArith(Modes.ARITH); } ;
DQ_CMD_SUB_START    : '$(' { pushCmdSub(); } ;
DQ_PARAM_EXP_START  : '${' { pushParamExp(Modes.PARAM_EXP); } ;
DQ_SIMPLE_VAR       : '$' [a-zA-Z_][a-zA-Z0-9_]* ;
DQ_SPECIAL_VAR      : '$' [0-9@#?$!*-] ;
DQ_BACKTICK         : '`' { pushBacktick(); } ;
DQ_LINE_CONT        : '\\' ( '\r'? '\n' | '\r' ) ;
DQ_ESCAPE           : '\\' ~[\r\n] ;
DQ_BACKSLASH        : '\\' ;
DQ_DOLLAR           : '$' ;
DQ_TEXT             : ~["\\$`]+ ;

// ---------------- Parameter Expansion (${ ... }) ----------------

mode PARAM_EXP;

PE_WS                  : [ \t\r\n\f]+ ;
PE_ARITH_START         : '$((' { hasBalancedDoubleParenClose() }? { pushArith(Modes.ARITH); } ;
PE_CMD_SUB_START       : '$(' { pushCmdSub(); } ;
PE_PARAM_EXP_START     : '${' { pushParamExp(Modes.PARAM_EXP); } ;
PE_SIMPLE_VAR          : '$' [a-zA-Z_][a-zA-Z0-9_]* ;
PE_SPECIAL_VAR         : '$' [0-9@#?$!*-] ;
PE_ANSI_C_STRING       : '$' '\'' ( '\\' ( '\r'? '\n' | '\r' | ~[\r\n] ) | ~['\\] )* ( '\'' | '\\' )? ;
PE_LOCALE_DQUOTE_START : '$"' { pushDQuote(Modes.DQUOTE); } ;
PE_DQUOTE_START        : '"' { pushDQuote(Modes.DQUOTE); } ;
PE_SINGLE_STRING       : '\'' ~[']* '\''? ;
PE_BACKTICK            : '`' { pushBacktick(); } ;
PE_LBRACE              : '{' { onParamExpLBrace(); } ;
PE_RBRACE              : '}' { onParamExpRBrace(); } ;
PE_LPAREN              : '(' ;
PE_RPAREN              : ')' ;
PE_LBRACK              : '[' ;
PE_RBRACK              : ']' ;
PE_OP                  : '##' | '%%' | '//' | ':-' | ':=' | ':?' | ':+' | '^^' | ',,' | '/#' | '/%'
                       | '#' | '%' | '-' | '=' | '?' | '+' | ':' | '/' | '^' | ',' | '@' | '!' | '~' | '*' ;
PE_ESCAPE              : '\\' ~[\r\n] ;
PE_BACKSLASH           : '\\' ;
PE_DOLLAR              : '$' ;
PE_INT                 : [0-9]+ ;
PE_IDENT               : [a-zA-Z_][a-zA-Z0-9_]* ;
PE_TEXT                : ~[ \t\r\n\f"'`\\${}()[\]#%\-=?+:/^,@!~*0-9a-zA-Z_]+ ;

// ---------------- Arithmetic Context ($(( ... )) and (( ... ))) ----------------

mode ARITH;

AR_WS               : [ \t\r\n\f]+ ;
AR_LINE_CONT        : '\\' ( '\r'? '\n' | '\r' ) ;
AR_END              : '))' { canCloseArith() }? { popArith(); } ;
AR_ARITH_EXP_START  : '$((' { hasBalancedDoubleParenClose() }? { pushArith(Modes.ARITH); } ;
AR_CMD_SUB_START    : '$(' { pushCmdSub(); } ;
AR_PARAM_EXP_START  : '${' { pushParamExp(Modes.PARAM_EXP); } ;
AR_SIMPLE_VAR       : '$' [a-zA-Z_][a-zA-Z0-9_]* ;
AR_SPECIAL_VAR      : '$' [0-9@#?$!*-] ;
AR_ANSI_C_STRING    : '$' '\'' ( '\\' ( '\r'? '\n' | '\r' | ~[\r\n] ) | ~['\\] )* ( '\'' | '\\' )? ;
AR_DQUOTE_START     : '"' { pushDQuote(Modes.DQUOTE); } ;
AR_SINGLE_STRING    : '\'' ~[']* '\''? ;
AR_BACKTICK         : '`' { pushBacktick(); } ;
AR_HEX              : '0' [xX] [0-9a-fA-F]+ ;
AR_BASE_INT         : [0-9]+ '#' [0-9a-zA-Z_@]+ ;
AR_FLOAT            : [0-9]+ '.' [0-9]* ( [eE] [+-]? [0-9]+ )?
                    | '.' [0-9]+ ( [eE] [+-]? [0-9]+ )?
                    | [0-9]+ [eE] [+-]? [0-9]+
                    ;
AR_INT              : [0-9]+ ;
AR_LPAREN           : '(' { onArithLParen(); } ;
AR_RPAREN           : ')' { onArithRParen(); } ;
AR_LBRACK           : '[' ;
AR_RBRACK           : ']' ;
AR_OP3              : '<<=' | '>>=' ;
AR_OP2              : '++' | '--' | '**' | '<<' | '>>' | '<=' | '>=' | '==' | '!=' | '&&' | '||'
                    | '+=' | '-=' | '*=' | '/=' | '%=' | '&=' | '|=' | '^=' ;
AR_OP1              : '+' | '-' | '*' | '/' | '%' | '<' | '>' | '&' | '|' | '^' | '~' | '!' | '=' | '?' | ':' ;
AR_COMMA            : ',' ;
AR_DOLLAR           : '$' ;
AR_IDENT            : [a-zA-Z_][a-zA-Z0-9_]* ;
AR_ERROR            : . ;

// ---------------- Heredoc Delimiter ----------------

mode HEREDOC_DELIM;

HD_WS
    : [ \t]+
    ;

HD_DELIM
    : ~[ \t\r\n;&|()<>#] { consumeHeredocDelimiter(); popMode(); }
    ;

HD_FALLBACK
    : . { popMode(); }
    ;

// ---------------- Heredoc Literal Body (Quoted Delimiter) ----------------

mode HEREDOC_LITERAL;

HL_NEWLINE
    : ( '\r'? '\n' | '\r' ) { onHeredocNewline(Modes.HEREDOC_LITERAL, Modes.HEREDOC_EXPAND); }
    ;

HL_TEXT
    : ~[\r\n] { consumeHeredocLiteralLine(Tokens.HEREDOC_DELIM_END, Modes.HEREDOC_LITERAL, Modes.HEREDOC_EXPAND); }
    ;

// ---------------- Heredoc Expanding Body (Unquoted Delimiter) ----------------

mode HEREDOC_EXPAND;

HE_NEWLINE
    : ( '\r'? '\n' | '\r' ) { onHeredocNewline(Modes.HEREDOC_LITERAL, Modes.HEREDOC_EXPAND); }
    ;

HE_LINE_CONT
    : '\\' ( '\r'? '\n' | '\r' ) { onHeredocLineContinuation(Modes.HEREDOC_LITERAL); }
    ;

HE_ESCAPE
    : '\\' ~[\r\n] { markHeredocLineBody(); }
    ;

HE_BACKSLASH
    : '\\' { markHeredocLineBody(); }
    ;

HE_ARITH_START
    : '$((' { hasBalancedDoubleParenClose() }? { markHeredocLineBody(); pushArith(Modes.ARITH); }
    ;

HE_CMD_SUB_START
    : '$(' { markHeredocLineBody(); pushCmdSub(); }
    ;

HE_PARAM_EXP_START
    : '${' { markHeredocLineBody(); pushParamExp(Modes.PARAM_EXP); }
    ;

HE_SIMPLE_VAR
    : '$' [a-zA-Z_][a-zA-Z0-9_]* { markHeredocLineBody(); }
    ;

HE_SPECIAL_VAR
    : '$' [0-9@#?$!*-] { markHeredocLineBody(); }
    ;

HE_BACKTICK
    : '`' { markHeredocLineBody(); pushBacktick(); }
    ;

HE_DOLLAR
    : '$' { markHeredocLineBody(); }
    ;

HE_TEXT
    : ~[\r\n$\\`]+ { markHeredocTextBody(text); }
    ;
