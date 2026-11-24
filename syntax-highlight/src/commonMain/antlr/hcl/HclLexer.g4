/**
 * Lossless ANTLR4 lexer grammar for HCL2 / Terraform, supporting hash, slash, and block
 * comments, HCL2 identifiers with embedded hyphens, double-quoted template strings and heredocs
 * with arbitrary nested interpolations and directives.
 */
lexer grammar HclLexer;

options {
    superClass = HclLexerBase;
}

import UnicodeIdent;

tokens {
    TEMPLATE_CLOSE,
    HEREDOC_CLOSE
}

WS
    : [ \t\uFEFF]+
    ;

NEWLINE
    : ( '\r'? '\n' | '\r' ) { onDefaultNewline(Modes.HEREDOC); }
    ;

HASH_COMMENT
    : '#' ~[\r\n]*
    ;

LINE_COMMENT
    : '//' ~[\r\n]*
    ;

BLOCK_COMMENT
    : '/*' .*? ( '*/' | EOF )
    ;

HEREDOC_OPEN
    : '<<' '-'? IdentStart IdentPart* { onHeredocOpen(text); }
    ;

DQUOTE_OPEN
    : '"' { pushMode(Modes.DQUOTE); }
    ;

TEMPLATE_STRIP_CLOSE
    : '~}' { canCloseTemplate() }? { onTemplateStripClose(Tokens.TEMPLATE_CLOSE); }
    ;

LBRACE : '{' { onLBrace(); } ;
RBRACE : '}' { onRBrace(Tokens.TEMPLATE_CLOSE); } ;
LBRACK : '[' ;
RBRACK : ']' ;
LPAREN : '(' ;
RPAREN : ')' ;
COMMA  : ',' ;
DOUBLE_COLON : '::' ;
ELLIPSIS : '...' ;
DOT    : '.' ;

FAT_ARROW : '=>' ;
EQ_EQ  : '==' ;
NEQ    : '!=' ;
LE     : '<=' ;
GE     : '>=' ;
AND_AND : '&&' ;
OR_OR  : '||' ;

EQ     : '=' ;
COLON  : ':' ;
QUESTION : '?' ;
PLUS   : '+' ;
MINUS  : '-' ;
STAR   : '*' ;
SLASH  : '/' ;
PERCENT : '%' ;
BANG   : '!' ;
LT     : '<' ;
GT     : '>' ;

FLOAT
    : [0-9]+ '.' [0-9]+ ( [eE] [+-]? [0-9]+ )?
    | [0-9]+ [eE] [+-]? [0-9]+
    ;

INT
    : [0-9]+
    ;

IDENT
    : IdentStart IdentPart*
    ;

fragment IdentStart
    : UNICODE_XID_START
    | '_'
    ;

fragment IdentPart
    : UNICODE_XID_CONTINUE
    | '-'
    ;

ERROR_CHAR
    : .
    ;

// ---------------- Double-Quoted Template String ----------------

mode DQUOTE;

DQ_CLOSE
    : '"' { popMode(); }
    ;

DQ_NEWLINE
    : ( '\r'? '\n' | '\r' ) { popMode(); }
    ;

DQ_ESC_DOLLAR
    : '$$'
    ;

DQ_ESC_PERCENT
    : '%%'
    ;

DQ_ESCAPE
    : '\\' ( 'u' [0-9a-fA-F] [0-9a-fA-F] [0-9a-fA-F] [0-9a-fA-F]
           | 'U' [0-9a-fA-F] [0-9a-fA-F] [0-9a-fA-F] [0-9a-fA-F] [0-9a-fA-F] [0-9a-fA-F] [0-9a-fA-F] [0-9a-fA-F]
           | ~[\r\n]
           )
    ;

DQ_BACKSLASH
    : '\\'
    ;

DQ_INTERP_OPEN
    : ( '${~' | '${' ) { enterTemplateExpr(); }
    ;

DQ_DIR_OPEN
    : ( '%{~' | '%{' ) { enterTemplateExpr(); }
    ;

DQ_DOLLAR
    : '$'
    ;

DQ_PERCENT
    : '%'
    ;

DQ_TEXT
    : ~["\\$%\r\n]+
    ;

// ---------------- Heredoc Template Body (<<EOF / <<-EOF) ----------------

mode HEREDOC;

HD_NEWLINE
    : ( '\r'? '\n' | '\r' ) { onHeredocNewline(); }
    ;

HD_ESC_DOLLAR
    : '$$' { markHeredocBody(); }
    ;

HD_ESC_PERCENT
    : '%%' { markHeredocBody(); }
    ;

HD_INTERP_OPEN
    : ( '${~' | '${' ) { markHeredocBody(); enterTemplateExpr(); }
    ;

HD_DIR_OPEN
    : ( '%{~' | '%{' ) { markHeredocBody(); enterTemplateExpr(); }
    ;

HD_DOLLAR
    : '$' { markHeredocBody(); }
    ;

HD_PERCENT
    : '%' { markHeredocBody(); }
    ;

HD_TEXT
    : ~[\r\n$%] { consumeHeredocText(Tokens.HEREDOC_CLOSE); }
    ;
