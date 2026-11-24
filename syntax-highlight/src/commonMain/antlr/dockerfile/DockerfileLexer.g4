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
    : '\\' { isEscapeBackslash() }? [ \t]* ( '\r'? '\n' | '\r' ) { onLineContinuation(); }
    ;

BACKTICK_LINE_CONT
    : '`' { isEscapeBacktick() }? [ \t]* ( '\r'? '\n' | '\r' ) { onLineContinuation(); }
    ;

NEWLINE
    : ( '\r'? '\n' | '\r' ) { onNewline(Modes.HEREDOC_LITERAL, Modes.HEREDOC_EXPAND); }
    ;

COMMENT
    : '#' { isCommentStart() }? ~[\r\n]* { onComment(text); }
    ;

DQUOTE_OPEN
    : '"' { pushDQuote(Modes.DQUOTE); }
    ;

SINGLE_STRING
    : '\'' { consumeSingleString(); onDelimiterToken(keepsWordBoundary = false); }
    ;

HEREDOC_OPEN
    : '<<' '-'? { isHeredocAllowed() }? ( '\'' ~['\r\n]+ '\'' | '"' ~["\r\n]+ '"' | [a-zA-Z_][a-zA-Z0-9_-]* ) { enqueueHeredoc(text); }
    ;

PARAM_EXP_OPEN
    : '${' { pushParamExp(Modes.PARAM_EXP); }
    ;

SIMPLE_VAR
    : '$' [a-zA-Z_][a-zA-Z0-9_]* { onDelimiterToken(keepsWordBoundary = false); }
    ;

SPECIAL_VAR
    : '$' [0-9@#?$!*-] { onDelimiterToken(keepsWordBoundary = false); }
    ;

DOLLAR
    : '$' { onDelimiterToken(keepsWordBoundary = false); }
    ;

FLAG
    : '--' [a-zA-Z0-9][a-zA-Z0-9_-]* { onDelimiterToken(keepsWordBoundary = false); }
    ;

LBRACK : '[' { onDelimiterToken(keepsWordBoundary = true); } ;
RBRACK : ']' { onDelimiterToken(keepsWordBoundary = false); } ;
LPAREN : '(' { onDelimiterToken(keepsWordBoundary = true); } ;
RPAREN : ')' { onDelimiterToken(keepsWordBoundary = false); } ;
LBRACE : '{' { onDelimiterToken(keepsWordBoundary = true); } ;
RBRACE : '}' { onDelimiterToken(keepsWordBoundary = false); } ;
COMMA  : ',' { onDelimiterToken(keepsWordBoundary = true); } ;
SEMI   : ';' { onDelimiterToken(keepsWordBoundary = true); } ;

OP_MULTI
    : ( '&&' | '||' | '>>' | '<<' | '<<-' ) { onDelimiterToken(keepsWordBoundary = true); }
    ;

OP
    : ( '=' | '|' | '>' | '<' | '&' ) { onDelimiterToken(keepsWordBoundary = true); }
    ;

NUMBER
    : [0-9]+ { onDelimiterToken(keepsWordBoundary = false); }
    ;

BACKSLASH_ESC
    : '\\' { isEscapeBackslash() }? ~[\r\n] { onDelimiterToken(keepsWordBoundary = false); }
    ;

BACKTICK_ESC
    : '`' { isEscapeBacktick() }? ~[\r\n] { onDelimiterToken(keepsWordBoundary = false); }
    ;

BACKSLASH_LITERAL
    : '\\' { onDelimiterToken(keepsWordBoundary = false); }
    ;

BACKTICK_LITERAL
    : '`' { onDelimiterToken(keepsWordBoundary = false); }
    ;

WORD
    : ~[ \t\r\n\uFEFF"'`\\$[\](){},;=|><&0-9-] ~[ \t\r\n\uFEFF"'`\\$[\](){},;=|><&]* { onWord(text); }
    | '-' ~[ \t\r\n\uFEFF"'`\\$[\](){},;=|><&]* { onWord(text); }
    | [0-9]+ ~[ \t\r\n\uFEFF"'`\\$[\](){},;=|><&0-9] ~[ \t\r\n\uFEFF"'`\\$[\](){},;=|><&]* { onWord(text); }
    ;

ERROR_CHAR
    : . { onDelimiterToken(keepsWordBoundary = false); }
    ;

// ---------------- Double-Quoted String ----------------

mode DQUOTE;

DQ_CLOSE
    : '"' { onDQuoteClose(); }
    ;

DQ_AFTER_COMMENT_NEWLINE
    : ( '\r'? '\n' | '\r' ) { isDqAfterComment() }? { onDQuoteAfterCommentNewline(); }
    ;

DQ_CONT_BLANK_LINE
    : [ \t]* ( '\r'? '\n' | '\r' ) { isDqAtLineStart() }? { onDQuoteContinuationBlankLine(); }
    ;

DQ_NEWLINE
    : ( '\r'? '\n' | '\r' ) { onDQuoteNewline(Modes.HEREDOC_LITERAL, Modes.HEREDOC_EXPAND); }
    ;

DQ_COMMENT
    : [ \t]* '#' { isDqCommentStart() }? ~[\r\n]* { onDQuoteComment(); }
    ;

DQ_CONT_BS
    : '\\' { isEscapeBackslash() && !isInsideHeredocBody() }? [ \t]* ( '\r'? '\n' | '\r' ) { onDQuoteLineCont(); }
    ;

DQ_CONT_BT
    : '`' { isEscapeBacktick() && !isInsideHeredocBody() }? [ \t]* ( '\r'? '\n' | '\r' ) { onDQuoteLineCont(); }
    ;

DQ_ESC_BS
    : '\\' { isEscapeBackslash() }? ~[\r\n]? { onDQuoteNonComment(); }
    ;

DQ_ESC_BT
    : '`' { isEscapeBacktick() }? ~[\r\n]? { onDQuoteNonComment(); }
    ;

DQ_PARAM_EXP_OPEN
    : '${' { onDQuoteNonComment(); pushParamExp(Modes.PARAM_EXP); }
    ;

DQ_SIMPLE_VAR
    : '$' [a-zA-Z_][a-zA-Z0-9_]* { onDQuoteNonComment(); }
    ;

DQ_SPECIAL_VAR
    : '$' [0-9@#?$!*-] { onDQuoteNonComment(); }
    ;

DQ_DOLLAR
    : '$' { onDQuoteNonComment(); }
    ;

DQ_BS_LITERAL
    : '\\' { onDQuoteNonComment(); }
    ;

DQ_BT_LITERAL
    : '`' { onDQuoteNonComment(); }
    ;

DQ_HASH
    : '#' { onDQuoteNonComment(); }
    ;

DQ_TEXT
    : ~["\\`$\r\n#]+ { onDQuoteNonComment(); }
    ;

// ---------------- Parameter Expansion (${...}) ----------------

mode PARAM_EXP;

PE_WS
    : [ \t]+
    ;

PE_BACKSLASH_LINE_CONT
    : '\\' { isEscapeBackslash() && !isInsideHeredocBody() }? [ \t]* ( '\r'? '\n' | '\r' ) { onPeLineCont(); }
    ;

PE_BACKTICK_LINE_CONT
    : '`' { isEscapeBacktick() && !isInsideHeredocBody() }? [ \t]* ( '\r'? '\n' | '\r' ) { onPeLineCont(); }
    ;

PE_CONT_NEWLINE
    : [ \t]* ( '\r'? '\n' | '\r' ) { isPeContinuationLineStart() }? { onPeContNewline(); }
    ;

PE_NEWLINE
    : ( '\r'? '\n' | '\r' ) { onParamExpUnclosedNewline(Modes.HEREDOC_LITERAL, Modes.HEREDOC_EXPAND); }
    ;

PE_CONT_COMMENT
    : [ \t]* '#' { isPeCommentStart() }? ~[\r\n]* { onPeComment(); }
    ;

PE_LBRACE
    : '{' { onPeNonComment(); onParamExpLBrace(); }
    ;

PE_RBRACE
    : '}' { onPeNonComment(); onParamExpRBrace(Tokens.PARAM_EXP_CLOSE); }
    ;

PE_PARAM_EXP_OPEN
    : '${' { onPeNonComment(); pushParamExp(Modes.PARAM_EXP); }
    ;

PE_SIMPLE_VAR
    : '$' [a-zA-Z_][a-zA-Z0-9_]* { onPeNonComment(); }
    ;

PE_SPECIAL_VAR
    : '$' [0-9@#?$!*-] { onPeNonComment(); }
    ;

PE_DQUOTE_OPEN
    : '"' { onPeNonComment(); pushDQuote(Modes.DQUOTE); }
    ;

PE_SINGLE_STRING
    : '\'' { onPeNonComment(); consumeSingleString(); }
    ;

PE_ESC_BS
    : '\\' { isEscapeBackslash() }? ~[\r\n]? { onPeNonComment(); }
    ;

PE_ESC_BT
    : '`' { isEscapeBacktick() }? ~[\r\n]? { onPeNonComment(); }
    ;

PE_OP
    : ( ':-' | ':=' | ':?' | ':+' | '##' | '%%' | '//' | '^^' | ',,'
    | '-' | '=' | '?' | '+' | '#' | '%' | '/' | ':' | '^' | ',' | '@' | '!' | '~' | '*' ) { onPeNonComment(); }
    ;

PE_INT
    : [0-9]+ { onPeNonComment(); }
    ;

PE_IDENT
    : [a-zA-Z_][a-zA-Z0-9_]* { onPeNonComment(); }
    ;

PE_TEXT
    : ( ~[ \t\r\n{}'"\\$`\-=?+#%/:^,@!~*0-9a-zA-Z_]+
    | [\\`$] ) { onPeNonComment(); }
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
    : '\\' { isEscapeBackslash() }? ~[\r\n]? { markHeredocLineBody(); }
    ;

HE_ESC_BT
    : '`' { isEscapeBacktick() }? ~[\r\n]? { markHeredocLineBody(); }
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
