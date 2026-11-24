/**
 * Lossless ANTLR4 lexer grammar for Dockerfile / Containerfile (including BuildKit parser
 * directives, dynamic escape characters, instructions, flags, JSON exec form, shell arguments,
 * variable expansions, and single/multi-heredoc blocks).
 */
lexer grammar DockerfileLexer;

options {
    superClass = DockerfileLexerBase;
}

tokens {
    HEREDOC_END,
    PARAM_EXP_CLOSE
}

WS
    : [ \t\uFEFF]+ { onWs(); }
    ;

BACKSLASH_LINE_CONT
    : '\\' { isEscapeBackslash() }? ( '\r'? '\n' | '\r' ) { onLineContinuation(); }
    ;

BACKTICK_LINE_CONT
    : '`' { isEscapeBacktick() }? ( '\r'? '\n' | '\r' ) { onLineContinuation(); }
    ;

NEWLINE
    : ( '\r'? '\n' | '\r' ) { onNewline(Modes.HEREDOC_LITERAL, Modes.HEREDOC_EXPAND); }
    ;

COMMENT
    : '#' { isCommentStart() }? ~[\r\n]* { onComment(text); }
    ;

DQUOTE_OPEN
    : '"' { onNonWsToken(); pushMode(Modes.DQUOTE); }
    ;

SINGLE_STRING
    : '\'' ~[']* '\''? { onNonWsToken(); }
    ;

HEREDOC_OPEN
    : '<<' '-'? ( '\'' ~['\r\n]+ '\'' | '"' ~["\r\n]+ '"' | [a-zA-Z_][a-zA-Z0-9_-]* ) { enqueueHeredoc(text); }
    ;

PARAM_EXP_OPEN
    : '${' { pushParamExp(Modes.PARAM_EXP); }
    ;

SIMPLE_VAR
    : '$' [a-zA-Z_][a-zA-Z0-9_]* { onNonWsToken(); }
    ;

SPECIAL_VAR
    : '$' [0-9@#?$!*-] { onNonWsToken(); }
    ;

DOLLAR
    : '$' { onNonWsToken(); }
    ;

FLAG
    : '--' [a-zA-Z0-9][a-zA-Z0-9_-]* { onNonWsToken(); }
    ;

LBRACK : '[' { onNonWsToken(); } ;
RBRACK : ']' { onNonWsToken(); } ;
LPAREN : '(' { onNonWsToken(); } ;
RPAREN : ')' { onNonWsToken(); } ;
LBRACE : '{' { onNonWsToken(); } ;
RBRACE : '}' { onNonWsToken(); } ;
COMMA  : ',' { onNonWsToken(); } ;
SEMI   : ';' { onNonWsToken(); } ;

OP_MULTI
    : ( '&&' | '||' | '>>' | '<<' | '<<-' ) { onNonWsToken(); }
    ;

OP
    : ( '=' | '|' | '>' | '<' | '&' ) { onNonWsToken(); }
    ;

NUMBER
    : [0-9]+ { onNonWsToken(); }
    ;

BACKSLASH_ESC
    : '\\' { isEscapeBackslash() }? ~[\r\n] { onNonWsToken(); }
    ;

BACKTICK_ESC
    : '`' { isEscapeBacktick() }? ~[\r\n] { onNonWsToken(); }
    ;

BACKSLASH_LITERAL
    : '\\' { onNonWsToken(); }
    ;

BACKTICK_LITERAL
    : '`' { onNonWsToken(); }
    ;

WORD
    : ~[ \t\r\n\uFEFF"'`\\$[\](){},;=|><&0-9-] ~[ \t\r\n\uFEFF"'`\\$[\](){},;=|><&]* { onNonWsToken(); }
    | '-' ~[ \t\r\n\uFEFF"'`\\$[\](){},;=|><&]* { onNonWsToken(); }
    | [0-9]+ ~[ \t\r\n\uFEFF"'`\\$[\](){},;=|><&0-9] ~[ \t\r\n\uFEFF"'`\\$[\](){},;=|><&]* { onNonWsToken(); }
    ;

ERROR_CHAR
    : . { onNonWsToken(); }
    ;

// ---------------- Double-Quoted String ----------------

mode DQUOTE;

DQ_CLOSE
    : '"' { popMode(); }
    ;

DQ_ESC_BS
    : '\\' { isEscapeBackslash() }? ( '\r'? '\n' | '\r' | . )?
    ;

DQ_ESC_BT
    : '`' { isEscapeBacktick() }? ( '\r'? '\n' | '\r' | . )?
    ;

DQ_PARAM_EXP_OPEN
    : '${' { pushParamExp(Modes.PARAM_EXP); }
    ;

DQ_SIMPLE_VAR
    : '$' [a-zA-Z_][a-zA-Z0-9_]*
    ;

DQ_SPECIAL_VAR
    : '$' [0-9@#?$!*-]
    ;

DQ_DOLLAR
    : '$'
    ;

DQ_BS_LITERAL
    : '\\'
    ;

DQ_BT_LITERAL
    : '`'
    ;

DQ_TEXT
    : ~["\\`$]+
    ;

// ---------------- Parameter Expansion (${...}) ----------------

mode PARAM_EXP;

PE_WS
    : [ \t\r\n]+
    ;

PE_LBRACE
    : '{' { onParamExpLBrace(); }
    ;

PE_RBRACE
    : '}' { onParamExpRBrace(Tokens.PARAM_EXP_CLOSE); }
    ;

PE_PARAM_EXP_OPEN
    : '${' { pushParamExp(Modes.PARAM_EXP); }
    ;

PE_SIMPLE_VAR
    : '$' [a-zA-Z_][a-zA-Z0-9_]*
    ;

PE_SPECIAL_VAR
    : '$' [0-9@#?$!*-]
    ;

PE_DQUOTE_OPEN
    : '"' { pushMode(Modes.DQUOTE); }
    ;

PE_SINGLE_STRING
    : '\'' ~[']* '\''?
    ;

PE_ESC_BS
    : '\\' { isEscapeBackslash() }? ( '\r'? '\n' | '\r' | . )?
    ;

PE_ESC_BT
    : '`' { isEscapeBacktick() }? ( '\r'? '\n' | '\r' | . )?
    ;

PE_OP
    : ':-' | ':=' | ':?' | ':+' | '##' | '%%' | '//' | '^^' | ',,'
    | '-' | '=' | '?' | '+' | '#' | '%' | '/' | ':' | '^' | ',' | '@' | '!' | '~' | '*'
    ;

PE_INT
    : [0-9]+
    ;

PE_IDENT
    : [a-zA-Z_][a-zA-Z0-9_]*
    ;

PE_TEXT
    : ~[ \t\r\n{}'"\\$`\-=?+#%/:^,@!~*0-9a-zA-Z_]+
    | [\\`$]
    ;

// ---------------- Heredoc Literal Body (Quoted Delimiter) ----------------

mode HEREDOC_LITERAL;

HL_NEWLINE
    : ( '\r'? '\n' | '\r' ) { onHeredocNewline(Modes.HEREDOC_LITERAL, Modes.HEREDOC_EXPAND); }
    ;

HL_TEXT
    : ~[\r\n] { consumeHeredocLiteralLine(Tokens.HEREDOC_END, Modes.HEREDOC_LITERAL, Modes.HEREDOC_EXPAND); }
    ;

// ---------------- Heredoc Expanding Body (Unquoted Delimiter) ----------------

mode HEREDOC_EXPAND;

HE_NEWLINE
    : ( '\r'? '\n' | '\r' ) { onHeredocNewline(Modes.HEREDOC_LITERAL, Modes.HEREDOC_EXPAND); }
    ;

HE_ESC_BS
    : '\\' { isEscapeBackslash() }? ( '\r'? '\n' | '\r' | ~[\r\n] )? { markHeredocLineBody(); }
    ;

HE_ESC_BT
    : '`' { isEscapeBacktick() }? ( '\r'? '\n' | '\r' | ~[\r\n] )? { markHeredocLineBody(); }
    ;

HE_BS_LITERAL
    : '\\' { markHeredocLineBody(); }
    ;

HE_BT_LITERAL
    : '`' { markHeredocLineBody(); }
    ;

HE_PARAM_EXP_OPEN
    : '${' { markHeredocLineBody(); pushParamExp(Modes.PARAM_EXP); }
    ;

HE_SIMPLE_VAR
    : '$' [a-zA-Z_][a-zA-Z0-9_]* { markHeredocLineBody(); }
    ;

HE_SPECIAL_VAR
    : '$' [0-9@#?$!*-] { markHeredocLineBody(); }
    ;

HE_DOLLAR
    : '$' { markHeredocLineBody(); }
    ;

HE_TEXT
    : ~[\r\n$\\`]+ { markHeredocTextBody(text); }
    ;
