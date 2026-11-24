/**
 * Lossless ANTLR4 lexer grammar for Swift 5.9/6.0, supporting nested block comments,
 * single-line and multiline strings with arbitrary `#` raw delimiters and nested `\(...)`
 * / `\#(...)` interpolations, bare `/.../` and extended `#/.../#` regex literals,
 * attributes, compiler directives, and backtick identifiers.
 */
lexer grammar SwiftLexer;

options {
    superClass = SwiftLexerBase;
}

import UnicodeIdent;

tokens {
    INTERP_OPEN,
    INTERP_CLOSE,
    STRING_ESCAPE,
    STRING_TEXT,
    STRING_CLOSE
}

@members {
    override fun isHiddenTokenType(type: Int): Boolean =
        type == Tokens.WS ||
            type == Tokens.NEWLINE ||
            type == Tokens.SHEBANG ||
            type == Tokens.DOC_LINE_COMMENT ||
            type == Tokens.LINE_COMMENT ||
            type == Tokens.BLOCK_COMMENT

    override fun isNewlineTokenType(type: Int): Boolean = type == Tokens.NEWLINE

    override fun isIdentTokenType(type: Int): Boolean = type == Tokens.IDENT

    override fun isBangTokenType(type: Int): Boolean = type == Tokens.BANG

    override fun rbraceTokenType(): Int = Tokens.RBRACE

    override fun allowsBareRegexAfter(type: Int, text: String?, lastBangWasPrefix: Boolean): Boolean = when (type) {
        Tokens.LPAREN,
        Tokens.LBRACK,
        Tokens.LBRACE,
        Tokens.COMMA,
        Tokens.SEMI,
        Tokens.COLON,
        Tokens.ASSIGN,
        Tokens.ARROW,
        Tokens.QUESTION,
        Tokens.TILDE,
        Tokens.PLUS,
        Tokens.MINUS,
        Tokens.STAR,
        Tokens.SLASH,
        Tokens.PERCENT,
        Tokens.AMP,
        Tokens.PIPE,
        Tokens.CARET,
        Tokens.LT,
        Tokens.GT,
        Tokens.ELLIPSIS,
        Tokens.HALF_OPEN_RANGE,
        Tokens.NIL_COALESCE,
        Tokens.AND,
        Tokens.OR,
        Tokens.EQ,
        Tokens.NE,
        Tokens.TRIPLE_EQ,
        Tokens.TRIPLE_NE,
        Tokens.LE,
        Tokens.GE,
        Tokens.LSHIFT,
        Tokens.RSHIFT,
        Tokens.OVERFLOW_LSHIFT,
        Tokens.OVERFLOW_RSHIFT,
        Tokens.PLUS_ASSIGN,
        Tokens.MINUS_ASSIGN,
        Tokens.STAR_ASSIGN,
        Tokens.SLASH_ASSIGN,
        Tokens.PERCENT_ASSIGN,
        Tokens.AMP_ASSIGN,
        Tokens.PIPE_ASSIGN,
        Tokens.CARET_ASSIGN,
        Tokens.LSHIFT_ASSIGN,
        Tokens.RSHIFT_ASSIGN,
        Tokens.OVERFLOW_LSHIFT_ASSIGN,
        Tokens.OVERFLOW_RSHIFT_ASSIGN,
        Tokens.TILDE_EQ,
        Tokens.OVERFLOW_ADD,
        Tokens.OVERFLOW_SUB,
        Tokens.OVERFLOW_MUL,
        Tokens.PIPE_FORWARD,
        Tokens.PIPE_BACKWARD,
        Tokens.BIDIR_ARROW,
        Tokens.POWER,
        Tokens.INTERP_OPEN,
        Tokens.RETURN,
        Tokens.THROW,
        Tokens.TRY,
        Tokens.IN,
        Tokens.WHERE,
        Tokens.IF,
        Tokens.ELSE,
        Tokens.WHILE,
        Tokens.GUARD,
        Tokens.SWITCH,
        Tokens.CASE,
        Tokens.CATCH,
        -> true

        Tokens.BANG -> lastBangWasPrefix
        Tokens.IDENT -> text == "await" || text == "consume" || text == "copy" || text == "yield"
        else -> false
    }

    override fun singleLineStringMode(): Int = Modes.SWIFT_STRING_SINGLE
    override fun multiLineStringMode(): Int = Modes.SWIFT_STRING_MULTI
    override fun extSingleLineStringMode(): Int = Modes.SWIFT_EXT_STRING_SINGLE
    override fun extMultiLineStringMode(): Int = Modes.SWIFT_EXT_STRING_MULTI

    override fun stringTextTokenType(): Int = Tokens.STRING_TEXT
    override fun stringEscapeTokenType(): Int = Tokens.STRING_ESCAPE
    override fun stringCloseTokenType(): Int = Tokens.STRING_CLOSE
    override fun interpOpenTokenType(): Int = Tokens.INTERP_OPEN
}

WS
    : [ \t\u000B\u000C\uFEFF]+
    ;

NEWLINE
    : ( '\r'? '\n'
      | '\r'
      ) { onDefaultNewline(); }
    ;

SHEBANG
    : '#!' { _tokenStartCharIndex == 0 }? ~[\r\n]*
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

EXTENDED_REGEX_LITERAL
    : '#'+ '/' { consumeExtendedRegex(); }
    ;

REGEX_LITERAL
    : '/' { isBareRegexAllowed() }? { consumeValidatedBareRegex(); }
    ;

EXT_MULTILINE_STRING_START
    : '#'+ '"""' { enterSwiftString(isMultiline = true, isExtended = true); }
    ;

EXT_STRING_START
    : '#'+ '"' { enterSwiftString(isMultiline = false, isExtended = true); }
    ;

MULTILINE_STRING_START
    : '"""' { enterSwiftString(isMultiline = true, isExtended = false); }
    ;

STRING_START
    : '"' { enterSwiftString(isMultiline = false, isExtended = false); }
    ;

POUND_IDENT
    : '#' IDENT_START IDENT_CONTINUE*
    ;

AT_IDENT
    : '@' IDENT_START IDENT_CONTINUE*
    ;

BACKTICK_IDENT
    : '`' ~[`\r\n]+ '`'
    ;

DOLLAR_ARG
    : '$' [0-9]+
    ;

DOLLAR_IDENT
    : '$' IDENT_START IDENT_CONTINUE*
    ;

// Numeric literals
HEX_FLOAT_LITERAL
    : '0' [xX] HEX_DIGITS ( '.' HEX_DIGITS )? [pP] [+-]? DIGITS
    ;

HEX_LITERAL
    : '0' [xX] HEX_DIGITS
    ;

OCT_LITERAL
    : '0' [oO] OCT_DIGITS
    ;

BIN_LITERAL
    : '0' [bB] BIN_DIGITS
    ;

FLOAT_LITERAL
    : DIGITS '.' DIGITS ( [eE] [+-]? DIGITS )?
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

fragment OCT_DIGITS
    : [0-7] ( '_'* [0-7] )*
    ;

fragment BIN_DIGITS
    : [01] ( '_'* [01] )*
    ;

// Hard keywords
ASSOCIATEDTYPE  : 'associatedtype';
CLASS           : 'class';
DEINIT          : 'deinit';
ENUM            : 'enum';
EXTENSION       : 'extension';
FILEPRIVATE     : 'fileprivate';
FUNC            : 'func';
IMPORT          : 'import';
INIT            : 'init';
INOUT           : 'inout';
INTERNAL        : 'internal';
LET             : 'let';
OPEN            : 'open';
OPERATOR        : 'operator';
PRIVATE         : 'private';
PRECEDENCEGROUP : 'precedencegroup';
PROTOCOL        : 'protocol';
PUBLIC          : 'public';
RETHROWS        : 'rethrows';
STATIC          : 'static';
STRUCT          : 'struct';
SUBSCRIPT       : 'subscript';
TYPEALIAS       : 'typealias';
VAR             : 'var';

BREAK           : 'break';
CASE            : 'case';
CATCH           : 'catch';
CONTINUE        : 'continue';
DEFAULT         : 'default';
DEFER           : 'defer';
DO              : 'do';
ELSE            : 'else';
FALLTHROUGH     : 'fallthrough';
FOR             : 'for';
GUARD           : 'guard';
IF              : 'if';
IN              : 'in';
REPEAT          : 'repeat';
RETURN          : 'return';
THROW           : 'throw';
SWITCH          : 'switch';
WHERE           : 'where';
WHILE           : 'while';

AS              : 'as';
ANY_UPPER       : 'Any';
FALSE           : 'false';
IS              : 'is';
NIL             : 'nil';
SELF_LOWER      : 'self';
SELF_UPPER      : 'Self';
SUPER           : 'super';
THROWS          : 'throws';
TRUE            : 'true';
TRY             : 'try';

// Multi-character operators and punctuation
ELLIPSIS        : '...';
HALF_OPEN_RANGE : '..<';
ARROW           : '->';
OVERFLOW_LSHIFT_ASSIGN : '&<<=';
OVERFLOW_RSHIFT_ASSIGN : '&>>=';
LSHIFT_ASSIGN   : '<<=';
RSHIFT_ASSIGN   : '>>=';
OVERFLOW_LSHIFT : '&<<';
OVERFLOW_RSHIFT : '&>>';
TRIPLE_EQ       : '===';
TRIPLE_NE       : '!==';
LSHIFT          : '<<';
RSHIFT          : '>>';
NIL_COALESCE    : '??';
AND             : '&&';
OR              : '||';
EQ              : '==';
NE              : '!=';
LE              : '<=';
GE              : '>=';
PLUS_ASSIGN     : '+=';
MINUS_ASSIGN    : '-=';
STAR_ASSIGN     : '*=';
SLASH_ASSIGN    : '/=';
PERCENT_ASSIGN  : '%=';
AMP_ASSIGN      : '&=';
PIPE_ASSIGN     : '|=';
CARET_ASSIGN    : '^=';
TILDE_EQ        : '~=';
OVERFLOW_ADD    : '&+';
OVERFLOW_SUB    : '&-';
OVERFLOW_MUL    : '&*';
PIPE_FORWARD    : '|>';
PIPE_BACKWARD   : '<|';
BIDIR_ARROW     : '<->';
POWER           : '**';

// Single-character operators and punctuation
LPAREN    : '(' { onOpenParen(); };
RPAREN    : ')' { onCloseParen(Tokens.INTERP_CLOSE); };
LBRACE    : '{';
RBRACE    : '}';
LBRACK    : '[';
RBRACK    : ']';
SEMI      : ';';
COMMA     : ',';
DOT       : '.';
COLON     : ':';
ASSIGN    : '=';
LT        : '<';
GT        : '>';
BANG      : '!';
QUESTION  : '?';
PLUS      : '+';
MINUS     : '-';
STAR      : '*';
SLASH     : '/';
PERCENT   : '%';
AMP       : '&';
PIPE      : '|';
CARET     : '^';
TILDE     : '~';
BACKSLASH : '\\';
HASH      : '#';
AT        : '@';

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

// ---------------- Standard Single-Line String ("...") ----------------
mode SWIFT_STRING_SINGLE;

SS_INTERP_OPEN
    : '\\(' { enterInterpExpr(Modes.DEFAULT_MODE); } -> type(INTERP_OPEN)
    ;

SS_ESCAPE
    : '\\' ~[\r\n] -> type(STRING_ESCAPE)
    ;

SS_BACKSLASH
    : '\\' -> type(STRING_TEXT)
    ;

SS_CLOSE
    : '"' { exitSwiftString(); } -> type(STRING_CLOSE)
    ;

SS_UNTERM_NL
    : ( '\r'? '\n' | '\r' ) { exitSwiftString(); } -> type(NEWLINE)
    ;

SS_TEXT
    : ~["\\\r\n]+ -> type(STRING_TEXT)
    ;

// ---------------- Standard Multiline String ("""...""") ----------------
mode SWIFT_STRING_MULTI;

SM_INTERP_OPEN
    : '\\(' { enterInterpExpr(Modes.DEFAULT_MODE); } -> type(INTERP_OPEN)
    ;

SM_ESCAPE
    : '\\' ( '\r'? '\n' | ~[\r\n] ) -> type(STRING_ESCAPE)
    ;

SM_BACKSLASH
    : '\\' -> type(STRING_TEXT)
    ;

SM_CLOSE
    : '"""' { exitSwiftString(); } -> type(STRING_CLOSE)
    ;

SM_QUOTE
    : ( '""' | '"' ) -> type(STRING_TEXT)
    ;

SM_TEXT
    : ~["\\]+ -> type(STRING_TEXT)
    ;

// ---------------- Extended Single-Line String (#"..."#) ----------------
mode SWIFT_EXT_STRING_SINGLE;

SES_CLOSE
    : '"' '#'+ { onExtQuotes(quoteCount = 1); }
    ;

SES_QUOTE
    : '"' -> type(STRING_TEXT)
    ;

SES_ESCAPE_OR_INTERP
    : '\\' '#'+ ( '(' | ~[\r\n] )? { onExtBackslash(isSingleLine = true); }
    ;

SES_BACKSLASH
    : '\\' -> type(STRING_TEXT)
    ;

SES_UNTERM_NL
    : ( '\r'? '\n' | '\r' ) { exitSwiftString(); } -> type(NEWLINE)
    ;

SES_TEXT
    : ~["\\\r\n]+ -> type(STRING_TEXT)
    ;

// ---------------- Extended Multiline String (#"""..."""#) ----------------
mode SWIFT_EXT_STRING_MULTI;

SEM_CLOSE
    : '"""' '#'+ { onExtQuotes(quoteCount = 3); }
    ;

SEM_QUOTE
    : ( '""' | '"' ) -> type(STRING_TEXT)
    ;

SEM_ESCAPE_OR_INTERP
    : '\\' '#'+ ( '(' | '\r'? '\n' | ~[\r\n] )? { onExtBackslash(isSingleLine = false); }
    ;

SEM_BACKSLASH
    : '\\' -> type(STRING_TEXT)
    ;

SEM_TEXT
    : ~["\\]+ -> type(STRING_TEXT)
    ;
