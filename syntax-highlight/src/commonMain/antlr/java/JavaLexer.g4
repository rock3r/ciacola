/**
 * Lossless ANTLR4 lexer grammar for Java 21+.
 */
lexer grammar JavaLexer;

import UnicodeIdent;

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

// Strings, text blocks, and character literals (terminated and cleanly unterminated)
TEXT_BLOCK
    : '"""'
      ( '\\' .
      | ~[\\"]
      | '"' ( '\\' . | ~[\\"] )
      | '""' ( '\\' . | ~[\\"] )
      )*
      '"""'
    ;

UNTERMINATED_TEXT_BLOCK
    : '"""'
      ( '\\' .
      | ~[\\"]
      | '"' ( '\\' . | ~[\\"] )
      | '""' ( '\\' . | ~[\\"] )
      )*
      ( '""' '\\'? | '"' '\\'? | '\\' )?
    ;

STRING_LITERAL
    : '"' ( '\\' ~[\r\n] | ~["\\\r\n] )* '"'
    ;

UNTERMINATED_STRING_LITERAL
    : '"' ( '\\' ~[\r\n] | ~["\\\r\n] )* '\\'?
    ;

CHAR_LITERAL
    : '\'' ( '\\' ~[\r\n] | ~['\\\r\n] )* '\''
    ;

UNTERMINATED_CHAR_LITERAL
    : '\'' ( '\\' ~[\r\n] | ~['\\\r\n] )* '\\'?
    ;

// Numeric literals
HEX_FLOAT_LITERAL
    : '0' [xX]
      ( HEX_DIGITS '.'?
      | HEX_DIGITS? '.' HEX_DIGITS
      )
      [pP] [+-]? DIGITS [fFdD]?
    ;

FLOAT_LITERAL
    : ( DIGITS '.' DIGITS? | '.' DIGITS ) ( [eE] [+-]? DIGITS )? [fFdD]?
    | DIGITS [eE] [+-]? DIGITS [fFdD]?
    | DIGITS [fFdD]
    ;

HEX_LITERAL
    : '0' [xX] HEX_DIGITS [lL]?
    ;

BIN_LITERAL
    : '0' [bB] BIN_DIGITS [lL]?
    ;

OCT_LITERAL
    : '0' '_'* [0-7] ( '_'* [0-7] )* [lL]?
    ;

DECIMAL_LITERAL
    : ( '0' | [1-9] ( '_'* [0-9] )* ) [lL]?
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
LBRACE     : '{';
RBRACE     : '}';
LBRACK     : '[';
RBRACK     : ']';
SEMI       : ';';
COMMA      : ',';
DOT        : '.';
AT         : '@';

// Multi-character operators
ARROW         : '->';
COLONCOLON    : '::';
URSHIFT_ASSIGN: '>>>=';
RSHIFT_ASSIGN : '>>=';
LSHIFT_ASSIGN : '<<=';
ADD_ASSIGN    : '+=';
SUB_ASSIGN    : '-=';
MUL_ASSIGN    : '*=';
DIV_ASSIGN    : '/=';
MOD_ASSIGN    : '%=';
AND_ASSIGN    : '&=';
OR_ASSIGN     : '|=';
XOR_ASSIGN    : '^=';
URSHIFT       : '>>>';
RSHIFT        : '>>';
LSHIFT        : '<<';
INC           : '++';
DEC           : '--';
AND           : '&&';
OR            : '||';
LE            : '<=';
GE            : '>=';
EQ            : '==';
NE            : '!=';

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

// Identifiers (including contextual keywords)
IDENTIFIER
    : ID_START ID_CONTINUE*
    ;

fragment ID_START
    : UNICODE_XID_START
    | '$'
    | '_'
    ;

fragment ID_CONTINUE
    : UNICODE_XID_CONTINUE
    | '$'
    | '_'
    ;

ERROR_CHAR
    : .
    ;
