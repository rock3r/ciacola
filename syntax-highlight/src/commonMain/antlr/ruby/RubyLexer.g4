/**
 * Lossless ANTLR4 lexer grammar for Ruby 3.3+, supporting embedded documentation (`=begin`/`=end`),
 * `__END__` data sections, single/double/backtick strings with `#{...}` and `#@var`/`#$var`
 * interpolation, paired and arbitrary-delimiter `%` literals, space-sensitive `/regex/` vs `/`
 * division, FIFO-queued heredocs (`<<`, `<<-`, `<<~`), character literals (`?a`), symbols, and
 * hash shorthand keys.
 */
lexer grammar RubyLexer;

options {
    superClass = RubyLexerBase;
}

tokens {
    INTERP_CLOSE,
    HEREDOC_END,
    PERCENT_SYM_OPEN,
    PERCENT_SYM_TEXT,
    PERCENT_SYM_CLOSE,
    PERCENT_CLOSE,
    DQUOTE_SYM_CLOSE,
    DQUOTE_SYM_TEXT,
    DQUOTE_SYM_ESCAPE,
    SHORTHAND_VAR
}

// ---------------- Comments, Embedded Docs & __END__ ----------------

SHEBANG
    : '#!' { isAtStartOfInput() }? ~[\r\n]* { onWhitespace(); }
    ;

COMMENT
    : '#' ~[\r\n]* { onWhitespace(); }
    ;

EMBEDDED_DOC
    : '=begin' { isEmbeddedDocStart() }? { consumeEmbeddedDoc(); }
    ;

END_DATA
    : '__END__' { isEndDataMarker() }? { onEndToken(); } -> pushMode(DATA_SECTION)
    ;

// ---------------- Whitespace & Line Breaks ----------------

WS
    : [ \t\u000B\u000C\uFEFF]+ { onWhitespace(); }
    ;

LINE_CONTINUATION
    : '\\' ( '\r'? '\n' | '\r' ) { onWhitespace(); }
    ;

NEWLINE
    : ( '\r'? '\n' | '\r' ) { onNewline(Modes.HEREDOC_LITERAL, Modes.HEREDOC_EXPAND); }
    ;

// ---------------- Strings, Backticks, Percent Literals, Regex & Heredocs ----------------

SINGLE_STRING
    : '\'' ( '\\' . | ~['\\] )* ( '\'' | '\\' )? { onEndToken(); }
    ;

DQUOTE_OPEN
    : '"' { enterDQuote(Modes.DQUOTE, isSymbol = false); }
    ;

BACKTICK_OPEN
    : '`' -> pushMode(BACKTICK)
    ;

HEREDOC_OPEN
    : '<<' [-~]? { isHeredocStart() }?
      ( [a-zA-Z_][a-zA-Z0-9_]*
      | '\'' ~['\r\n]* '\''
      | '"' ~["\r\n]* '"'
      | '`' ~[`\r\n]* '`'
      )
      { enqueueHeredoc(); }
    ;

PERCENT_LITERAL_OPEN
    : '%' [qQwWiIrsx] ~[\r\n] { isTypedPercentStart() }?
      { enterPercentLiteral(typed = true, Modes.PERCENT_LITERAL, Modes.PERCENT_INTERP, Tokens.PERCENT_SYM_OPEN); }
    | '%' ~[ \t\r\n\u000B\u000C] { isUntypedPercentStart() }?
      { enterPercentLiteral(typed = false, Modes.PERCENT_LITERAL, Modes.PERCENT_INTERP, Tokens.PERCENT_SYM_OPEN); }
    ;

REGEX_OPEN
    : '/' { isRegexStart() }? { enterRegexMode(Modes.REGEX); }
    ;

CHAR_LITERAL
    : '?' { isCharLiteralStart() }?
      ( '\\' CHAR_ESCAPE
      | ~[ \t\r\n\u000B\u000C]
      )
      { onEndToken(); }
    ;

fragment CHAR_ESCAPE
    : ( [MC] '-' '\\'? | 'c' )+ ( '\\' ~[\r\n] | ~[\r\n] )
    | 'u' '{' [0-9a-fA-F]+ '}'
    | 'u' [0-9a-fA-F] [0-9a-fA-F] [0-9a-fA-F] [0-9a-fA-F]
    | 'x' [0-9a-fA-F] [0-9a-fA-F]?
    | [0-7] [0-7]? [0-7]?
    | ~[\r\n]
    ;

// ---------------- Symbols & Hash Keys ----------------

SYM_DQUOTE_OPEN
    : ':"' { isSymbolStart() }? { enterDQuote(Modes.DQUOTE, isSymbol = true); }
    ;

SYM_SINGLE
    : ':\'' { isSymbolStart() }? ( '\\' . | ~['\\] )* ( '\'' | '\\' )? { onEndToken(); }
    ;

SYM_OP
    : ':'
      ( '<=>' | '===' | '==' | '=~' | '!~' | '!=' | '!@' | '~@' | '+@' | '-@'
      | '**' | '<<' | '>>' | '<=' | '>=' | '[]=' | '[]'
      | '+' | '-' | '*' | '/' | '%' | '&' | '|' | '^' | '!' | '~' | '<' | '>' | '`'
      )
      { isSymbolStart() }? { onEndToken(); }
    ;

SYM_VAR
    : ':'
      ( '@' '@'? IDENT_START IDENT_CONT*
      | '$' ( IDENT_START IDENT_CONT* | '-' [a-zA-Z0-9_] | [0-9]+ | [!@&`'+~=/\\,;.<>*$?:"] )
      )
      { isSymbolStart() }? { onEndToken(); }
    ;

SYM_IDENT
    : ':' IDENT_START IDENT_CONT*
      ( [?!] { isSymbolStart() && _input.LA(1) != '='.code }?
      | '=' { isSymbolStart() && _input.LA(1) != '='.code && _input.LA(1) != '~'.code && _input.LA(1) != '>'.code }?
      | { isSymbolStart() }?
      )
      { onEndToken(); }
    ;

HASH_KEY
    : IDENT_START IDENT_CONT* [?!]? ':' { _input.LA(1) != ':'.code }? { onBegToken(); }
    ;

// ---------------- Variables ----------------

CVAR
    : '@@' IDENT_START IDENT_CONT* { onEndToken(); }
    ;

IVAR
    : '@' IDENT_START IDENT_CONT* { onEndToken(); }
    ;

GVAR
    : '$'
      ( IDENT_START IDENT_CONT*
      | '-' [a-zA-Z0-9_]
      | [0-9]+
      | [!@&`'+~=/\\,;.<>*$?:"]
      )
      { onEndToken(); }
    ;

// ---------------- Numeric Literals ----------------

HEX_LITERAL
    : '0' [xX] [0-9a-fA-F] ( '_'? [0-9a-fA-F] )* ( 'ri' | [ri] )? { onEndToken(); }
    ;

BIN_LITERAL
    : '0' [bB] [01] ( '_'? [01] )* ( 'ri' | [ri] )? { onEndToken(); }
    ;

OCT_LITERAL
    : ( '0' [oO] [0-7] ( '_'? [0-7] )*
      | '0' ( '_'? [0-7] )+
      ) ( 'ri' | [ri] )? { onEndToken(); }
    ;

DEC_PREFIX_LITERAL
    : '0' [dD] [0-9] ( '_'? [0-9] )* ( 'ri' | [ri] )? { onEndToken(); }
    ;

FLOAT_LITERAL
    : ( DEC_DIGITS '.' DEC_DIGITS ( [eE] [+-]? DEC_DIGITS )?
      | DEC_DIGITS [eE] [+-]? DEC_DIGITS
      ) ( 'ri' | [ri] )? { onEndToken(); }
    ;

INT_LITERAL
    : ( '0'
      | [1-9] ( '_'? [0-9] )*
      ) ( 'ri' | [ri] )? { onEndToken(); }
    ;

fragment DEC_DIGITS
    : [0-9] ( '_'? [0-9] )*
    ;

// ---------------- Operators & Punctuation ----------------

SPACESHIP      : '<=>' { onBegToken(); };
TRIPLE_EQ      : '===' { onBegToken(); };
ELLIPSIS       : '...' { onBegToken(); };
POW_ASSIGN     : '**=' { onBegToken(); };
LSHIFT_ASSIGN  : '<<=' { onBegToken(); };
RSHIFT_ASSIGN  : '>>=' { onBegToken(); };
AND_AND_ASSIGN : '&&=' { onBegToken(); };
OR_OR_ASSIGN   : '||=' { onBegToken(); };

UNARY_OP_DEF
    : ( '+@' | '-@' | '~@' | '!@' ) { isDefContext() }? { onEndToken(); }
    ;

RANGE_INC      : '..' { onBegToken(); };
SAFE_NAV       : '&.' { onDotToken(); };
COLON_COLON    : '::' { onDotToken(); };
HASH_ROCKET    : '=>' { onBegToken(); };
STABBY_LAMBDA  : '->' { onBegToken(); };
POW            : '**' { onBegToken(); };
LSHIFT         : '<<' { onBegToken(); };
RSHIFT         : '>>' { onBegToken(); };
EQ_EQ          : '==' { onBegToken(); };
NOT_EQ         : '!=' { onBegToken(); };
MATCH          : '=~' { onBegToken(); };
NOT_MATCH      : '!~' { onBegToken(); };
LE             : '<=' { onBegToken(); };
GE             : '>=' { onBegToken(); };
AND_AND        : '&&' { onBegToken(); };
OR_OR          : '||' { onBegToken(); };
PLUS_ASSIGN    : '+=' { onBegToken(); };
MINUS_ASSIGN   : '-=' { onBegToken(); };
STAR_ASSIGN    : '*=' { onBegToken(); };
SLASH_ASSIGN   : '/=' { !isRegexStart() }? { onBegToken(); };
PERCENT_ASSIGN : '%=' { onBegToken(); };
AMP_ASSIGN     : '&=' { onBegToken(); };
PIPE_ASSIGN    : '|=' { onBegToken(); };
CARET_ASSIGN   : '^=' { onBegToken(); };

LPAREN   : '(' { onBegToken(); };
RPAREN   : ')' { onEndToken(); };
LBRACK   : '[' { onBegToken(); };
RBRACK   : ']' { onEndToken(); };
LBRACE   : '{' { onLBrace(); };
RBRACE   : '}' { onRBrace(Tokens.INTERP_CLOSE); };
COMMA    : ',' { onBegToken(); };
SEMI     : ';' { onBegToken(); };
DOT      : '.' { onDotToken(); };
COLON    : ':' { onBegToken(); };
QUESTION : '?' { onBegToken(); };
ASSIGN   : '=' { onBegToken(); };
PLUS     : '+' { onBegToken(); };
MINUS    : '-' { onBegToken(); };
STAR     : '*' { onBegToken(); };
SLASH    : '/' { onBegToken(); };
PERCENT  : '%' { onBegToken(); };
LT       : '<' { onBegToken(); };
GT       : '>' { onBegToken(); };
BANG     : '!' { onBegToken(); };
TILDE    : '~' { onBegToken(); };
AMP      : '&' { onBegToken(); };
PIPE     : '|' { onBegToken(); };
CARET    : '^' { onBegToken(); };
AT       : '@' { onEndToken(); };
DOLLAR   : '$' { onEndToken(); };
BACKSLASH : '\\' { onBegToken(); };

// ---------------- Identifiers (Keywords classified contextually in RubySyntaxLexer) ----------------

IDENT
    : IDENT_START IDENT_CONT* [?!] { _input.LA(1) != '='.code }? { onIdentifierOrKeyword(text); }
    | IDENT_START IDENT_CONT* { onIdentifierOrKeyword(text); }
    ;

fragment IDENT_START
    : [a-zA-Z_\u0080-\uD7FF\uE000-\uFEFE\uFF00-\u{10FFFF}]
    ;

fragment IDENT_CONT
    : [a-zA-Z0-9_\u0080-\uD7FF\uE000-\uFEFE\uFF00-\u{10FFFF}]
    ;

ERROR_CHAR
    : . { onEndToken(); }
    ;

// ---------------- Double-Quoted String / Symbol Mode ----------------

mode DQUOTE;

DQ_CLOSE
    : '"' { exitDQuote(Tokens.DQUOTE_SYM_CLOSE); }
    ;

DQ_INTERP_OPEN
    : '#{' { enterInterp(Modes.DEFAULT_MODE); }
    ;

DQ_SHORTHAND_VAR
    : '#'
      ( '@' '@'? IDENT_START IDENT_CONT*
      | '$' ( IDENT_START IDENT_CONT* | '-' [a-zA-Z0-9_] | [0-9]+ | [!@&`'+~=/\\,;.<>*$?:"] )
      )
      -> type(SHORTHAND_VAR)
    ;

DQ_ESCAPE
    : '\\' . { onDQuoteEscape(Tokens.DQUOTE_SYM_ESCAPE); }
    ;

DQ_BACKSLASH
    : '\\' { onDQuoteEscape(Tokens.DQUOTE_SYM_ESCAPE); }
    ;

DQ_TEXT
    : ~["\\#]+ { onDQuoteText(Tokens.DQUOTE_SYM_TEXT); }
    ;

DQ_HASH
    : '#' { onDQuoteText(Tokens.DQUOTE_SYM_TEXT); }
    ;

// ---------------- Backtick Command String Mode ----------------

mode BACKTICK;

BT_CLOSE
    : '`' { exitBacktick(); }
    ;

BT_INTERP_OPEN
    : '#{' { enterInterp(Modes.DEFAULT_MODE); }
    ;

BT_SHORTHAND_VAR
    : '#'
      ( '@' '@'? IDENT_START IDENT_CONT*
      | '$' ( IDENT_START IDENT_CONT* | '-' [a-zA-Z0-9_] | [0-9]+ | [!@&`'+~=/\\,;.<>*$?:"] )
      )
      -> type(SHORTHAND_VAR)
    ;

BT_ESCAPE
    : '\\' .
    ;

BT_BACKSLASH
    : '\\'
    ;

BT_TEXT
    : ~[`\\#]+
    ;

BT_HASH
    : '#' -> type(BT_TEXT)
    ;

// ---------------- Regex Literal Mode (/.../[imxouesn]*) ----------------

mode REGEX;

RE_INTERP_OPEN
    : '#{' { enterInterp(Modes.DEFAULT_MODE); }
    ;

RE_SHORTHAND_VAR
    : '#'
      ( '@' '@'? IDENT_START IDENT_CONT*
      | '$' ( IDENT_START IDENT_CONT* | '-' [a-zA-Z0-9_] | [0-9]+ | [!@&`'+~=/\\,;.<>*$?:"] )
      )
      -> type(SHORTHAND_VAR)
    ;

RE_ESCAPE
    : '\\' .
    ;

RE_BACKSLASH
    : '\\'
    ;

RE_LBRACK
    : '[' { onRegexLBracket(); }
    ;

RE_RBRACK
    : ']' { onRegexRBracket(); }
    ;

RE_SLASH
    : '/' { onRegexSlash(Tokens.RE_TEXT, Tokens.RE_SLASH); }
    ;

RE_TEXT
    : ~[/\\#[\]]+
    ;

RE_HASH
    : '#' -> type(RE_TEXT)
    ;

// ---------------- Percent Literal Mode (Non-Interpolating) ----------------

mode PERCENT_LITERAL;

PL_ESCAPE
    : '\\' .
    ;

PL_BACKSLASH
    : '\\'
    ;

PL_TEXT
    : ~[\\]
      { consumePercentBody(isInterp = false, Tokens.PL_TEXT, Tokens.PERCENT_CLOSE, Tokens.PERCENT_SYM_TEXT, Tokens.PERCENT_SYM_CLOSE); }
    ;

// ---------------- Percent Literal Mode (Interpolating) ----------------

mode PERCENT_INTERP;

PI_INTERP_OPEN
    : '#{' { !isPercentHashCloser() }? { enterInterp(Modes.DEFAULT_MODE); }
    ;

PI_SHORTHAND_VAR
    : '#'
      ( '@' '@'? IDENT_START IDENT_CONT*
      | '$' ( IDENT_START IDENT_CONT* | '-' [a-zA-Z0-9_] | [0-9]+ | [!@&`'+~=/\\,;.<>*$?:"] )
      )
      { !isPercentHashCloser() }?
      -> type(SHORTHAND_VAR)
    ;

PI_ESCAPE
    : '\\' .
    ;

PI_BACKSLASH
    : '\\'
    ;

PI_TEXT
    : ~[\\#]
      { consumePercentBody(isInterp = true, Tokens.PI_TEXT, Tokens.PERCENT_CLOSE, Tokens.PERCENT_SYM_TEXT, Tokens.PERCENT_SYM_CLOSE); }
    ;

PI_HASH
    : '#'
      { consumePercentBody(isInterp = true, Tokens.PI_TEXT, Tokens.PERCENT_CLOSE, Tokens.PERCENT_SYM_TEXT, Tokens.PERCENT_SYM_CLOSE); }
    ;

// ---------------- Heredoc Literal Body (Single-Quoted Delimiter) ----------------

mode HEREDOC_LITERAL;

HL_NEWLINE
    : ( '\r'? '\n' | '\r' ) { onHeredocNewline(Modes.HEREDOC_LITERAL, Modes.HEREDOC_EXPAND); }
    ;

HL_TEXT
    : ~[\r\n] { consumeHeredocLiteralLine(Tokens.HEREDOC_END, Modes.HEREDOC_LITERAL, Modes.HEREDOC_EXPAND); }
    ;

// ---------------- Heredoc Expanding Body (Bare / Double-Quoted / Backtick) ----------------

mode HEREDOC_EXPAND;

HE_NEWLINE
    : ( '\r'? '\n' | '\r' ) { onHeredocNewline(Modes.HEREDOC_LITERAL, Modes.HEREDOC_EXPAND); }
    ;

HE_INTERP_OPEN
    : '#{' { markHeredocBody(); enterInterp(Modes.DEFAULT_MODE); }
    ;

HE_SHORTHAND_VAR
    : '#'
      ( '@' '@'? IDENT_START IDENT_CONT*
      | '$' ( IDENT_START IDENT_CONT* | '-' [a-zA-Z0-9_] | [0-9]+ | [!@&`'+~=/\\,;.<>*$?:"] )
      )
      { markHeredocBody(); }
      -> type(SHORTHAND_VAR)
    ;

HE_ESCAPE
    : '\\' ~[\r\n] { markHeredocBody(); }
    ;

HE_BACKSLASH
    : '\\' { markHeredocBody(); }
    ;

HE_TEXT
    : ~[\r\n\\#]+ { markHeredocText(text); }
    ;

HE_HASH
    : '#' { markHeredocBody(); } -> type(HE_TEXT)
    ;

// ---------------- __END__ Data Section ----------------

mode DATA_SECTION;

DATA_BODY
    : . { consumeToEof(); }
    ;
