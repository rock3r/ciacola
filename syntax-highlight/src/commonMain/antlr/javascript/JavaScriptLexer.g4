/**
 * Lossless ANTLR4 lexer grammar for JavaScript/JSX and TypeScript/TSX.
 */
lexer grammar JavaScriptLexer;

options {
    superClass = JsTsLexerBase;
}

tokens {
    TEMPLATE_EXPR_END
}

@members {
    override fun isHiddenTokenType(type: Int): Boolean =
        type == Tokens.WS ||
            type == Tokens.LINE_COMMENT ||
            type == Tokens.BLOCK_COMMENT ||
            type == Tokens.UNTERMINATED_BLOCK_COMMENT ||
            type == Tokens.JSX_TAG_WS ||
            type == Tokens.JSX_TAG_LINE_COMMENT ||
            type == Tokens.JSX_TAG_BLOCK_COMMENT ||
            type == Tokens.JSX_TAG_UNTERMINATED_BLOCK_COMMENT ||
            type == Tokens.JSX_TYPE_WS ||
            type == Tokens.JSX_CLOSING_WS

    override fun isIdentTokenType(type: Int): Boolean = type == Tokens.IDENT

    override fun isLparenTokenType(type: Int): Boolean = type == Tokens.LPAREN

    override fun isRparenTokenType(type: Int): Boolean = type == Tokens.RPAREN

    override fun isConditionKeywordText(text: String?): Boolean =
        text == "if" || text == "while" || text == "for" || text == "with"

    override fun allowsRegexAfterToken(type: Int, text: String?, lastParenWasCond: Boolean): Boolean {
        if (type == Tokens.RPAREN) return lastParenWasCond
        return when (type) {
            Tokens.LPAREN,
            Tokens.LBRACK,
            Tokens.LBRACE,
            Tokens.RBRACE,
            Tokens.COMMA,
            Tokens.SEMI,
            Tokens.COLON,
            Tokens.QUESTION,
            Tokens.ARROW,
            Tokens.ELLIPSIS,
            Tokens.ASSIGN,
            Tokens.PLUS_ASSIGN,
            Tokens.MINUS_ASSIGN,
            Tokens.STAR_ASSIGN,
            Tokens.SLASH_ASSIGN,
            Tokens.PERCENT_ASSIGN,
            Tokens.STAR_STAR_ASSIGN,
            Tokens.LSHIFT_ASSIGN,
            Tokens.RSHIFT_ASSIGN,
            Tokens.URSHIFT_ASSIGN,
            Tokens.AND_ASSIGN,
            Tokens.OR_ASSIGN,
            Tokens.XOR_ASSIGN,
            Tokens.LOGICAL_AND_ASSIGN,
            Tokens.LOGICAL_OR_ASSIGN,
            Tokens.NULLISH_ASSIGN,
            Tokens.PLUS,
            Tokens.MINUS,
            Tokens.STAR,
            Tokens.SLASH,
            Tokens.PERCENT,
            Tokens.STAR_STAR,
            Tokens.BANG,
            Tokens.TILDE,
            Tokens.BIT_AND,
            Tokens.BIT_OR,
            Tokens.BIT_XOR,
            Tokens.LOGICAL_AND,
            Tokens.LOGICAL_OR,
            Tokens.NULLISH,
            Tokens.LT,
            Tokens.GT,
            Tokens.LE,
            Tokens.GE,
            Tokens.EQ,
            Tokens.NEQ,
            Tokens.STRICT_EQ,
            Tokens.STRICT_NEQ,
            Tokens.LSHIFT,
            Tokens.RSHIFT,
            Tokens.URSHIFT,
            Tokens.TEMPLATE_EXPR_START,
            Tokens.JSX_TAG_EXPR_START,
            Tokens.JSX_CHILDREN_EXPR_START,
            -> true
            Tokens.IDENT -> text == "return" ||
                text == "throw" ||
                text == "case" ||
                text == "default" ||
                text == "else" ||
                text == "do" ||
                text == "typeof" ||
                text == "void" ||
                text == "delete" ||
                text == "await" ||
                text == "yield" ||
                text == "in" ||
                text == "of" ||
                text == "instanceof" ||
                text == "new" ||
                text == "export"
            else -> false
        }
    }
}

SHEBANG
    : '#!' { isAtStartOfFile() }? ~[\r\n]*
    ;

WS
    : [ \t\r\n]+
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

REGEX_LITERAL
    : '/' { isRegexAllowed() }?
      ( ~[/\\*\r\n[] | '\\' ~[\r\n] | '[' ( ~[\]\\\r\n] | '\\' ~[\r\n] )* ']' )
      ( ~[/\\\r\n[] | '\\' ~[\r\n] | '[' ( ~[\]\\\r\n] | '\\' ~[\r\n] )* ']' )*
      '/' [a-zA-Z]*
    ;

TEMPLATE_START
    : '`' -> pushMode(TEMPLATE)
    ;

STRING
    : '"' ( '\\' ( '\r'? '\n' | ~[\r\n] ) | ~["\\\r\n] )* '"'
    | '\'' ( '\\' ( '\r'? '\n' | ~[\r\n] ) | ~['\\\r\n] )* '\''
    ;

UNTERMINATED_STRING
    : '"' ( '\\' ( '\r'? '\n' | ~[\r\n] ) | ~["\\\r\n] )* '\\'?
    | '\'' ( '\\' ( '\r'? '\n' | ~[\r\n] ) | ~['\\\r\n] )* '\\'?
    ;

JSX_FRAG_OPEN
    : '<>' { isJsxFragmentStartAfterAngle() }? -> pushMode(JSX_CHILDREN)
    ;

JSX_TAG_OPEN
    : '<' { isJsxTagStartAfterAngle() }? -> pushMode(JSX_TAG)
    ;

BIGINT_LITERAL
    : ( '0' [xX] [0-9a-fA-F] ( '_'? [0-9a-fA-F] )*
      | '0' [bB] [01] ( '_'? [01] )*
      | '0' [oO] [0-7] ( '_'? [0-7] )*
      | ( '0' | [1-9] ( '_'? [0-9] )* )
      ) 'n'
    ;

HEX_LITERAL
    : '0' [xX] [0-9a-fA-F] ( '_'? [0-9a-fA-F] )*
    ;

BIN_LITERAL
    : '0' [bB] [01] ( '_'? [01] )*
    ;

OCT_LITERAL
    : '0' [oO] [0-7] ( '_'? [0-7] )*
    ;

DECIMAL_LITERAL
    : ( DECIMAL_DIGITS '.' DECIMAL_DIGITS? | '.' DECIMAL_DIGITS | DECIMAL_DIGITS ) ( [eE] [+-]? DECIMAL_DIGITS )?
    ;

LPAREN  : '(';
RPAREN  : ')';
LBRACK  : '[';
RBRACK  : ']';
LBRACE  : '{' -> pushMode(DEFAULT_MODE);
RBRACE
    : '}'
      {
          if (!_modeStack.isEmpty) {
              popMode()
              if (_mode == Modes.TEMPLATE) {
                  type = Tokens.TEMPLATE_EXPR_END
              }
          }
      }
    ;

ELLIPSIS : '...';
OPT_CHAIN : '?.';
DOT      : '.';
COMMA    : ',';
SEMI     : ';';
COLON    : ':';
AT       : '@';
ARROW    : '=>';

STRICT_EQ    : '===';
STRICT_NEQ   : '!==';
EQ           : '==';
NEQ          : '!=';
LE           : '<=';
GE           : '>=';

URSHIFT_ASSIGN : '>>>=';
RSHIFT_ASSIGN  : '>>=';
LSHIFT_ASSIGN  : '<<=';
URSHIFT        : '>>>';
RSHIFT         : '>>';
LSHIFT         : '<<';
LT             : '<';
GT             : '>';

STAR_STAR_ASSIGN : '**=';
STAR_STAR        : '**';
LOGICAL_AND_ASSIGN : '&&=';
LOGICAL_OR_ASSIGN  : '||=';
NULLISH_ASSIGN     : '??=';
LOGICAL_AND        : '&&';
LOGICAL_OR         : '||';
NULLISH            : '??';

PLUS_ASSIGN    : '+=';
MINUS_ASSIGN   : '-=';
STAR_ASSIGN    : '*=';
SLASH_ASSIGN   : '/=';
PERCENT_ASSIGN : '%=';
AND_ASSIGN     : '&=';
OR_ASSIGN      : '|=';
XOR_ASSIGN     : '^=';

INC      : '++';
DEC      : '--';
ASSIGN   : '=';
PLUS     : '+';
MINUS    : '-';
STAR     : '*';
SLASH    : '/';
PERCENT  : '%';
BANG     : '!';
TILDE    : '~';
BIT_AND  : '&';
BIT_OR   : '|';
BIT_XOR  : '^';
QUESTION : '?';

PRIVATE_IDENT
    : '#' IDENT_START IDENT_PART*
    ;

IDENT
    : IDENT_START IDENT_PART*
    ;

ERROR_CHAR
    : .
    ;

fragment DECIMAL_DIGITS
    : [0-9] ( '_'? [0-9] )*
    ;

fragment IDENT_START
    : [a-zA-Z_$]
    | [\u00C0-\u02AF\u0370-\u1FFF\u2C00-\uD7FF\uF900-\uFFEF\u{10000}-\u{1EFFF}\u{20000}-\u{2FA1F}]
    ;

fragment IDENT_PART
    : IDENT_START
    | [0-9]
    | [\u0300-\u036F\u200C\u200D]
    ;

// ---------------- Template literal mode ----------------
mode TEMPLATE;

TEMPLATE_CLOSE
    : '`' -> popMode
    ;

TEMPLATE_EXPR_START
    : '${' -> pushMode(DEFAULT_MODE)
    ;

TEMPLATE_ESCAPE
    : '\\' ( '\r'? '\n' | ~[\r\n] )
    ;

TEMPLATE_TEXT
    : ~[`\\$]+
    ;

TEMPLATE_LONE_DOLLAR
    : '$'
    ;

// ---------------- JSX tag header mode ----------------
mode JSX_TAG;

JSX_TAG_WS
    : [ \t\r\n]+
    ;

JSX_TAG_LINE_COMMENT
    : '//' ~[\r\n]*
    ;

JSX_TAG_BLOCK_COMMENT
    : '/*' ( ~'*' | '*'+ ~[*/] )* '*'+ '/'
    ;

JSX_TAG_UNTERMINATED_BLOCK_COMMENT
    : '/*' ( ~'*' | '*'+ ~[*/] )* '*'*
    ;

JSX_TAG_SELF_CLOSE
    : '/>' -> popMode
    ;

JSX_TAG_CLOSE
    : '>' -> mode(JSX_CHILDREN)
    ;

JSX_TAG_EXPR_START
    : '{' -> pushMode(DEFAULT_MODE)
    ;

JSX_TAG_TYPE_OPEN
    : '<' -> pushMode(JSX_TYPE_ARGS)
    ;

JSX_TAG_STRING
    : '"' ~["]* '"'
    | '\'' ~[']* '\''
    ;

JSX_TAG_UNTERMINATED_STRING
    : '"' ~["]*
    | '\'' ~[']*
    ;

JSX_TAG_DOT   : '.';
JSX_TAG_COLON : ':';
JSX_TAG_EQ    : '=';

JSX_TAG_NAME
    : IDENT_START ( IDENT_PART | '-' )*
    ;

JSX_TAG_ERROR
    : .
    ;

// ---------------- JSX children mode ----------------
mode JSX_CHILDREN;

JSX_FRAG_CLOSE
    : '</>' -> popMode
    ;

JSX_CLOSING_OPEN
    : '</' -> mode(JSX_CLOSING_TAG)
    ;

JSX_CHILD_FRAG_OPEN
    : '<>' -> pushMode(JSX_CHILDREN)
    ;

JSX_CHILD_TAG_OPEN
    : '<' { isChildTagStartAfterAngle() }? -> pushMode(JSX_TAG)
    ;

JSX_CHILDREN_EXPR_START
    : '{' -> pushMode(DEFAULT_MODE)
    ;

JSX_CHILDREN_WS
    : [ \t\r\n]+
    ;

JSX_TEXT
    : ~[<{} \t\r\n]+
    ;

JSX_CHILDREN_LT
    : '<'
    ;

JSX_CHILDREN_RBRACE
    : '}'
    ;

// ---------------- JSX closing tag mode ----------------
mode JSX_CLOSING_TAG;

JSX_CLOSING_WS
    : [ \t\r\n]+
    ;

JSX_CLOSING_END
    : '>' -> popMode
    ;

JSX_CLOSING_DOT
    : '.'
    ;

JSX_CLOSING_COLON
    : ':'
    ;

JSX_CLOSING_NAME
    : IDENT_START ( IDENT_PART | '-' )*
    ;

JSX_CLOSING_ERROR
    : .
    ;

// ---------------- TSX generic type argument mode inside JSX tag ----------------
mode JSX_TYPE_ARGS;

JSX_TYPE_WS
    : [ \t\r\n]+
    ;

JSX_TYPE_OPEN
    : '<' -> pushMode(JSX_TYPE_ARGS)
    ;

JSX_TYPE_CLOSE
    : '>' -> popMode
    ;

JSX_TYPE_STRING
    : '"' ( '\\' ~[\r\n] | ~["\\\r\n] )* '"'
    | '\'' ( '\\' ~[\r\n] | ~['\\\r\n] )* '\''
    ;

JSX_TYPE_NUMBER
    : DECIMAL_DIGITS ( '.' DECIMAL_DIGITS? )?
    ;

JSX_TYPE_ARROW
    : '=>'
    ;

JSX_TYPE_PUNCT
    : [(){}[\],.;]
    ;

JSX_TYPE_OP
    : [|&?:=]
    ;

JSX_TYPE_IDENT
    : IDENT_START IDENT_PART*
    ;

JSX_TYPE_ERROR
    : .
    ;
