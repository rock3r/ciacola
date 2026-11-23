/**
 * Lossless ANTLR4 lexer grammar for C# 12/13, supporting preprocessor directives, verbatim and
 * raw string literals (including UTF-8 `u8` suffixes), and arbitrarily nested interpolated
 * strings (`$"..."`, `$@"..."`, `@$"..."`, `$$"""..."""`).
 */
lexer grammar CSharpLexer;

options {
    superClass = CSharpLexerBase;
}

import UnicodeIdent;

tokens {
    INTERP_OPEN,
    INTERP_CLOSE,
    INTERP_FORMAT_COLON,
    IRAW_CLOSE
}

WS
    : [ \t\u000B\u000C\uFEFF]+
    ;

NEWLINE
    : ( '\r'? '\n'
      | '\r'
      ) { onDefaultNewline(); }
    ;

DOC_COMMENT
    : '///' ~[\r\n]*
    ;

LINE_COMMENT
    : '//' ~[\r\n]*
    ;

BLOCK_COMMENT
    : '/*' ( ~'*' | '*'+ ~[*/] )* '*'+ '/'
    ;

UNTERMINATED_BLOCK_COMMENT
    : '/*' ( ~'*' | '*'+ ~[*/] )* '*'*
    ;

PP_HASH
    : '#' { isPreprocessorStart() }? -> pushMode(PP_MODE)
    ;

// String and character literals (interpolated, raw, verbatim, regular, terminated and unterminated)
RAW_INTERP_START
    : '$'+ '"""' '"'* { enterRawInterp(Modes.INTERP_RAW); }
    ;

VERBATIM_INTERP_START
    : ( '$@"' | '@$"' ) { enterVerbatimInterp(Modes.INTERP_VERBATIM); }
    ;

REGULAR_INTERP_START
    : '$"' { enterRegularInterp(Modes.INTERP_REGULAR); }
    ;

RAW_STRING_LITERAL
    : '"""' '"'* { consumeCSharpRawString(); }
    ;

VERBATIM_STRING_LITERAL
    : '@"' ( '""' | ~["] )* '"' ( [uU] '8' )?
    ;

UNTERMINATED_VERBATIM_STRING_LITERAL
    : '@"' ( '""' | ~["] )*
    ;

STRING_LITERAL
    : '"' ( '\\' ~[\r\n] | ~["\\\r\n] )* '"' ( [uU] '8' )?
    ;

UNTERMINATED_STRING_LITERAL
    : '"' ( '\\' ~[\r\n] | ~["\\\r\n] )* '\\'?
    ;

CHAR_LITERAL
    : '\'' ( '\\' ~[\r\n] | ~['\\\r\n] )+ '\''
    ;

UNTERMINATED_CHAR_LITERAL
    : '\'' ( '\\' ~[\r\n] | ~['\\\r\n] )* '\\'?
    ;

// Numeric literals
HEX_LITERAL
    : '0' [xX] '_'* HEX_DIGITS INT_SUFFIX?
    ;

BIN_LITERAL
    : '0' [bB] '_'* BIN_DIGITS INT_SUFFIX?
    ;

FLOAT_LITERAL
    : ( DIGITS '.' DIGITS | '.' DIGITS ) ( [eE] [+-]? DIGITS )? [fFdDmM]?
    | DIGITS [eE] [+-]? DIGITS [fFdDmM]?
    | DIGITS [fFdDmM]
    ;

DECIMAL_LITERAL
    : DIGITS INT_SUFFIX?
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

fragment INT_SUFFIX
    : [uU] [lL]?
    | [lL] [uU]?
    ;

// Reserved keywords
ABSTRACT   : 'abstract';
AS         : 'as';
BASE       : 'base';
BOOL       : 'bool';
BREAK      : 'break';
BYTE       : 'byte';
CASE       : 'case';
CATCH      : 'catch';
CHAR       : 'char';
CHECKED    : 'checked';
CLASS      : 'class';
CONST      : 'const';
CONTINUE   : 'continue';
DECIMAL    : 'decimal';
DEFAULT    : 'default';
DELEGATE   : 'delegate';
DO         : 'do';
DOUBLE     : 'double';
ELSE       : 'else';
ENUM       : 'enum';
EVENT      : 'event';
EXPLICIT   : 'explicit';
EXTERN     : 'extern';
FALSE      : 'false';
FINALLY    : 'finally';
FIXED      : 'fixed';
FLOAT      : 'float';
FOR        : 'for';
FOREACH    : 'foreach';
GOTO       : 'goto';
IF         : 'if';
IMPLICIT   : 'implicit';
IN         : 'in';
INT        : 'int';
INTERFACE  : 'interface';
INTERNAL   : 'internal';
IS         : 'is';
LOCK       : 'lock';
LONG       : 'long';
NAMESPACE  : 'namespace';
NEW        : 'new';
NULL       : 'null';
OBJECT     : 'object';
OPERATOR   : 'operator';
OUT        : 'out';
OVERRIDE   : 'override';
PARAMS     : 'params';
PRIVATE    : 'private';
PROTECTED  : 'protected';
PUBLIC     : 'public';
READONLY   : 'readonly';
REF        : 'ref';
RETURN     : 'return';
SBYTE      : 'sbyte';
SEALED     : 'sealed';
SHORT      : 'short';
SIZEOF     : 'sizeof';
STACKALLOC : 'stackalloc';
STATIC     : 'static';
STRING     : 'string';
STRUCT     : 'struct';
SWITCH     : 'switch';
THIS       : 'this';
THROW      : 'throw';
TRUE       : 'true';
TRY        : 'try';
TYPEOF     : 'typeof';
UINT       : 'uint';
ULONG      : 'ulong';
UNCHECKED  : 'unchecked';
UNSAFE     : 'unsafe';
USHORT     : 'ushort';
USING      : 'using';
VIRTUAL    : 'virtual';
VOID       : 'void';
VOLATILE   : 'volatile';
WHILE      : 'while';

// Multi-character operators and punctuation
URSHIFT_ASSIGN : '>>>=';
RSHIFT_ASSIGN  : '>>=';
LSHIFT_ASSIGN  : '<<=';
NULL_COALESCE_ASSIGN : '??=';
LAMBDA_ARROW   : '=>';
NULL_COALESCE  : '??';
DOTDOT         : '..';
COLONCOLON     : '::';
ARROW          : '->';
URSHIFT        : '>>>';
RSHIFT         : '>>';
LSHIFT         : '<<';
INC            : '++';
DEC            : '--';
AND            : '&&';
OR             : '||';
EQ             : '==';
NE             : '!=';
LE             : '<=';
GE             : '>=';
ADD_ASSIGN     : '+=';
SUB_ASSIGN     : '-=';
MUL_ASSIGN     : '*=';
DIV_ASSIGN     : '/=';
MOD_ASSIGN     : '%=';
AND_ASSIGN     : '&=';
OR_ASSIGN      : '|=';
XOR_ASSIGN     : '^=';

// Single-character operators and punctuation
LPAREN   : '(' { onOpenParen(); };
RPAREN   : ')' { onCloseParen(); };
LBRACE   : '{' { onLBrace(); };
RBRACE   : '}' { onRBrace(Tokens.INTERP_CLOSE); };
LBRACK   : '[' { onOpenBracket(); };
RBRACK   : ']' { onCloseBracket(); };
SEMI     : ';';
COMMA    : ',';
DOT      : '.';
COLON    : ':' { onColon(Modes.INTERP_FORMAT_SPEC, Tokens.INTERP_FORMAT_COLON); };
ASSIGN   : '=';
GT       : '>';
LT       : '<';
BANG     : '!';
TILDE    : '~';
QUESTION : '?' { onQuestionMark(); };
PLUS     : '+';
MINUS    : '-';
STAR     : '*';
SLASH    : '/';
BITAND   : '&';
BITOR    : '|';
CARET    : '^';
MOD      : '%';

// Verbatim and regular identifiers (including contextual keywords)
VERBATIM_IDENT
    : '@' IDENT_START IDENT_CONTINUE*
    ;

IDENT
    : IDENT_START IDENT_CONTINUE*
    ;

fragment IDENT_START
    : UNICODE_XID_START
    | '_'
    ;

fragment IDENT_CONTINUE
    : UNICODE_XID_CONTINUE
    ;

ERROR_CHAR
    : .
    ;

// ---------------- Preprocessor Directive Mode ----------------
mode PP_MODE;

PP_WS
    : [ \t\u000B\u000C\uFEFF]+
    ;

PP_NL
    : ( '\r'? '\n' | '\r' ) -> popMode
    ;

PP_LINE_COMMENT
    : '//' ~[\r\n]*
    ;

PP_BLOCK_COMMENT
    : '/*' ( ~[*\r\n] | '*'+ ~[*/\r\n] )* '*'+ '/'
    ;

PP_STRING
    : '"' ( '\\' ~[\r\n] | ~["\\\r\n] )* '"'
    ;

PP_DIRECTIVE
    : [a-zA-Z_][a-zA-Z0-9_]*
    ;

PP_TEXT
    : ~[\r\n \t\u000B\u000C\uFEFF"/]+
    | '/'
    | '"'
    ;

// ---------------- Regular Interpolated String ($"...") ----------------
mode INTERP_REGULAR;

IR_ESCAPED_BRACE
    : '{{' | '}}'
    ;

IR_INTERP_OPEN
    : '{' { enterExpr(Modes.DEFAULT_MODE); } -> type(INTERP_OPEN)
    ;

IR_ESCAPE
    : '\\' ~[\r\n]
    ;

IR_BACKSLASH
    : '\\'
    ;

IR_CLOSE
    : '"' ( [uU] '8' )? { exitInterpString(); }
    ;

IR_UNTERM_NL
    : ( '\r'? '\n' | '\r' ) { exitInterpString(); }
    ;

IR_TEXT
    : ~["{}\\\r\n]+
    ;

// ---------------- Verbatim Interpolated String ($@"..." / @$"...") ----------------
mode INTERP_VERBATIM;

IV_ESCAPED_BRACE
    : '{{' | '}}'
    ;

IV_INTERP_OPEN
    : '{' { enterExpr(Modes.DEFAULT_MODE); } -> type(INTERP_OPEN)
    ;

IV_ESCAPED_QUOTE
    : '""'
    ;

IV_CLOSE
    : '"' ( [uU] '8' )? { exitInterpString(); }
    ;

IV_TEXT
    : ~["{}]+
    ;

// ---------------- Raw Interpolated String ($$"""...""") ----------------
mode INTERP_RAW;

IRAW_LBRACE
    : '{'+ { onRawInterpLBrace(Modes.DEFAULT_MODE, Tokens.INTERP_OPEN, Tokens.IRAW_TEXT); }
    ;

IRAW_RBRACE
    : '}'+
    ;

IRAW_QUOTES
    : '"'+ { onRawInterpQuotes(Tokens.IRAW_CLOSE, Tokens.IRAW_TEXT); }
    ;

IRAW_TEXT
    : ~["{}]+
    ;

// ---------------- Interpolation Format Specifier (:N2) ----------------
mode INTERP_FORMAT_SPEC;

FMT_RBRACE
    : '}'+ { onFormatSpecRBrace(Tokens.INTERP_CLOSE, Tokens.FMT_TEXT); }
    ;

FMT_ESCAPE
    : '\\' ~[\r\n] { onFormatSpecEscape(Tokens.FMT_TEXT); }
    ;

FMT_BACKSLASH
    : '\\' -> type(FMT_TEXT)
    ;

FMT_VERBATIM_QUOTE
    : '""' { onFormatSpecVerbatimQuote(Tokens.FMT_TEXT); }
    ;

FMT_QUOTE
    : '"' { onFormatSpecQuoteOrNewline(isNewline = false, fallbackTextType = Tokens.FMT_TEXT); }
    ;

FMT_NEWLINE
    : ( '\r'? '\n' | '\r' ) { onFormatSpecQuoteOrNewline(isNewline = true, fallbackTextType = Tokens.FMT_TEXT); }
    ;

FMT_TEXT
    : ~[}"\\\r\n]+
    ;
