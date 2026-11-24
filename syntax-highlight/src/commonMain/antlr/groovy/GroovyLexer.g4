/**
 * Lossless ANTLR4 lexer grammar for Groovy 4 (including Gradle Groovy DSL),
 * supporting shebangs, Groovydoc/block/line comments, single/triple-single/
 * double/triple-double/slashy/dollar-slashy strings, nested GString
 * interpolations (`$foo.bar` and `${expr}`), slashy-vs-division disambiguation,
 * BigInteger/BigDecimal/hex/binary/octal literals, and Groovy 4 operators.
 */
lexer grammar GroovyLexer;

import UnicodeIdent;

options {
    superClass = GroovyLexerBase;
}

tokens {
    GSTRING_INTERP_CLOSE
}

// Shebang (only at offset 0; literal '#!' precedes semantic predicate)
SHEBANG
    : '#!' { isAtStartOfInput() }? ~[\r\n]*
    ;

// Whitespace and comments
WS
    : [ \t\r\n\f\uFEFF]+
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

// Strings and GStrings
TRIPLE_SINGLE_STRING
    : '\'\'\''
      ( '\\' .
      | ~[\\']
      | '\'' ( '\\' . | ~[\\'] )
      | '\'\'' ( '\\' . | ~[\\'] )
      )*
      '\'\'\''
    ;

UNTERMINATED_TRIPLE_SINGLE_STRING
    : '\'\'\''
      ( '\\' .
      | ~[\\']
      | '\'' ( '\\' . | ~[\\'] )
      | '\'\'' ( '\\' . | ~[\\'] )
      )*
      ( '\'\'' '\\'? | '\'' '\\'? | '\\' )?
    ;

SINGLE_STRING
    : '\'' ( '\\' ~[\r\n] | ~['\\\r\n] )* '\''
    ;

UNTERMINATED_SINGLE_STRING
    : '\'' ( '\\' ~[\r\n] | ~['\\\r\n] )* '\\'?
    ;

TDQUOTE_START
    : '"""' -> pushMode(GSTRING_TDQ)
    ;

DQUOTE_START
    : '"' -> pushMode(GSTRING_DQ)
    ;

DOLLAR_SLASHY_START
    : '$/' -> pushMode(DOLLAR_SLASHY_STRING)
    ;

// Numeric literals (hex float, float, hex, binary, octal, decimal)
HEX_FLOAT_LITERAL
    : '0' [xX]
      ( HEX_DIGITS '.'?
      | HEX_DIGITS? '.' HEX_DIGITS
      )
      [pP] [+-]? DIGITS [gGfFdD]?
    ;

FLOAT_LITERAL
    : DIGITS '.' DIGITS ( [eE] [+-]? DIGITS )? [gGfFdD]?
    | DIGITS [eE] [+-]? DIGITS [gGfFdD]?
    | DIGITS [fFdD]
    ;

HEX_LITERAL
    : '0' [xX] HEX_DIGITS [gGlLiI]?
    ;

BIN_LITERAL
    : '0' [bB] BIN_DIGITS [gGlLiI]?
    ;

OCT_LITERAL
    : '0' '_'* [0-7] ( '_'* [0-7] )* [gGlLiI]?
    ;

DECIMAL_LITERAL
    : ( '0' | [1-9] ( '_'* [0-9] )* ) [gGlLiI]?
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

// Hard keywords
ABSTRACT     : 'abstract';
AS           : 'as';
ASSERT       : 'assert';
BOOLEAN      : 'boolean';
BREAK        : 'break';
BYTE         : 'byte';
CASE         : 'case';
CATCH        : 'catch';
CHAR         : 'char';
CLASS        : 'class';
CONST        : 'const';
CONTINUE     : 'continue';
DEF          : 'def';
DEFAULT      : 'default';
DO           : 'do';
DOUBLE       : 'double';
ELSE         : 'else';
ENUM         : 'enum';
EXTENDS      : 'extends';
FINAL        : 'final';
FINALLY      : 'finally';
FLOAT        : 'float';
FOR          : 'for';
GOTO         : 'goto';
IF           : 'if';
IMPLEMENTS   : 'implements';
IMPORT       : 'import';
IN           : 'in';
INSTANCEOF   : 'instanceof';
INT          : 'int';
INTERFACE    : 'interface';
LONG         : 'long';
NATIVE       : 'native';
NEW          : 'new';
PACKAGE      : 'package';
PRIVATE      : 'private';
PROTECTED    : 'protected';
PUBLIC       : 'public';
RETURN       : 'return';
SHORT        : 'short';
STATIC       : 'static';
STRICTFP     : 'strictfp';
SUPER        : 'super';
SWITCH       : 'switch';
SYNCHRONIZED : 'synchronized';
THIS         : 'this';
THROW        : 'throw';
THROWS       : 'throws';
TRANSIENT    : 'transient';
TRY          : 'try';
VOID         : 'void';
VOLATILE     : 'volatile';
WHILE        : 'while';

// Constants
TRUE  : 'true';
FALSE : 'false';
NULL  : 'null';

// Separators and punctuation
ELLIPSIS   : '...';
LPAREN     : '(';
RPAREN     : ')';
LBRACE     : '{' { onDefaultLBrace(); };
RBRACE     : '}' { onDefaultRBrace(Tokens.GSTRING_INTERP_CLOSE); };
LBRACK     : '[';
RBRACK     : ']';
SEMI       : ';';
COMMA      : ',';
METHOD_PTR : '.&';
FIELD_ACC  : '.@';
DOT        : '.';
AT         : '@';

// Multi-character operators
ARROW            : '->';
COLONCOLON       : '::';
ELVIS_ASSIGN     : '?=';
ELVIS            : '?:';
SAFE_DOT         : '?.';
SPREAD_DOT       : '*.';
STAR_STAR_ASSIGN : '**=';
STAR_STAR        : '**';
SPACESHIP        : '<=>';
REGEX_MATCH      : '==~';
REGEX_FIND       : '=~';
IDENTICAL        : '===';
NOT_IDENTICAL    : '!==';
HALF_RANGE_BOTH  : '<..<';
HALF_RANGE_LEFT  : '<..';
HALF_RANGE_RIGHT : '..<';
RANGE            : '..';
URSHIFT_ASSIGN   : '>>>=';
RSHIFT_ASSIGN    : '>>=';
LSHIFT_ASSIGN    : '<<=';
ADD_ASSIGN       : '+=';
SUB_ASSIGN       : '-=';
MUL_ASSIGN       : '*=';
DIV_ASSIGN       : '/=' { !isExplicitSlashyPrefix() }?;
MOD_ASSIGN       : '%=';
AND_ASSIGN       : '&=';
OR_ASSIGN        : '|=';
XOR_ASSIGN       : '^=';
URSHIFT          : '>>>';
RSHIFT           : '>>';
LSHIFT           : '<<';
INC              : '++';
DEC              : '--';
AND              : '&&';
OR               : '||';
LE               : '<=';
GE               : '>=';
EQ               : '==';
NE               : '!=';

// Slashy string opener (literal '/' precedes predicate) vs division operator
SLASHY_START
    : '/' { isSlashyStart() }? -> pushMode(SLASHY_STRING)
    ;

// Single-character operators
ASSIGN   : '=';
GT       : '>';
LT       : '<';
BANG     : '!';
TILDE    : '~';
QUESTION : '?';
COLON    : ':';
PLUS     : '+';
MINUS    : '-';
STAR     : '*';
SLASH    : '/';
BITAND   : '&';
BITOR    : '|';
CARET    : '^';
MOD      : '%';

// Identifiers (contextual keywords such as trait/var/record/sealed/non-sealed/permits/yield are classified in GroovySyntaxLexer)
IDENTIFIER
    : ID_START ID_CONTINUE*
    ;

fragment ID_START
    : UNICODE_XID_START | '_' | '$'
    ;

fragment ID_CONTINUE
    : UNICODE_XID_CONTINUE | '_' | '$'
    ;

fragment GSTRING_ID_START
    : UNICODE_XID_START | '_'
    ;

fragment GSTRING_ID_CONTINUE
    : UNICODE_XID_CONTINUE | '_'
    ;

ERROR_CHAR
    : .
    ;

// ---------------- Double-Quoted GString ("...") ----------------

mode GSTRING_DQ;

GDQ_CLOSE        : '"' -> popMode;
GDQ_LINE_CONT    : '\\' ( '\r'? '\n' | '\r' );
GDQ_UNTERM_NL    : ( '\r'? '\n' | '\r' ) -> popMode;
GDQ_ESCAPE       : '\\' ~[\r\n];
GDQ_BACKSLASH    : '\\';
GDQ_INTERP_START : '${' { enterInterpExpr(); };
GDQ_INTERP_VAR   : '$' GSTRING_ID_START GSTRING_ID_CONTINUE* ( '.' GSTRING_ID_START GSTRING_ID_CONTINUE* )*;
GDQ_DOLLAR       : '$';
GDQ_TEXT         : ~["\\$\r\n]+;

// ---------------- Triple-Double-Quoted GString ("""...""") ----------------

mode GSTRING_TDQ;

GTDQ_CLOSE        : '"""' -> popMode;
GTDQ_QUOTE        : '"';
GTDQ_ESCAPE       : '\\' .;
GTDQ_BACKSLASH    : '\\';
GTDQ_INTERP_START : '${' { enterInterpExpr(); };
GTDQ_INTERP_VAR   : '$' GSTRING_ID_START GSTRING_ID_CONTINUE* ( '.' GSTRING_ID_START GSTRING_ID_CONTINUE* )*;
GTDQ_DOLLAR       : '$';
GTDQ_TEXT         : ~["\\$]+;

// ---------------- Slashy String (/.../) ----------------

mode SLASHY_STRING;

SL_CLOSE        : '/' -> popMode;
SL_ESCAPE       : '\\/';
SL_BACKSLASH    : '\\';
SL_INTERP_START : '${' { enterInterpExpr(); };
SL_INTERP_VAR   : '$' GSTRING_ID_START GSTRING_ID_CONTINUE* ( '.' GSTRING_ID_START GSTRING_ID_CONTINUE* )*;
SL_DOLLAR       : '$';
SL_TEXT         : ~[/\\$]+;

// ---------------- Dollar-Slashy String ($/.../$) ----------------

mode DOLLAR_SLASHY_STRING;

DSL_CLOSE        : '/$' -> popMode;
DSL_ESCAPE       : '$$' | '$/';
DSL_INTERP_START : '${' { enterInterpExpr(); };
DSL_INTERP_VAR   : '$' GSTRING_ID_START GSTRING_ID_CONTINUE* ( '.' GSTRING_ID_START GSTRING_ID_CONTINUE* )*;
DSL_DOLLAR       : '$';
DSL_SLASH        : '/';
DSL_TEXT         : ~[$/]+;
