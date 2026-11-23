/**
 * Lossless ANTLR4 mode-switched lexer grammar for HTML5 with raw `<script>` and `<style>` body modes.
 */
lexer grammar HtmlLexer;

options {
    superClass = HtmlLexerBase;
}

HTML_COMMENT
    : '<!--' ( ~'-' | '-' ~'-' | '-' '-'+ ~[->] )* '-'* '-->'
    ;

UNTERMINATED_HTML_COMMENT
    : '<!--' ( ~'-' | '-' ~'-' | '-' '-'+ ~[->] )* '-'*
    ;

CDATA
    : '<![CDATA[' ( ~']' | ']' ~']' | ']'+ ~[\]>] )* ']'* ']]>'
    ;

UNTERMINATED_CDATA
    : '<![CDATA[' ( ~']' | ']' ~']' | ']'+ ~[\]>] )* ']'*
    ;

DOCTYPE_OPEN
    : '<!' [dD][oO][cC][tT][yY][pP][eE] { onNonElementOpen() } -> pushMode(INSIDE)
    ;

DTD
    : '<!' { !isDoctypeAheadAfterBang() }? ~[>\r\n]* '>'?
    ;

XML_DECL_OPEN
    : '<?xml' [ \t\r\n]+ { onNonElementOpen() } -> pushMode(INSIDE)
    ;

PI
    : '<?' ( ~'?' | '?'+ ~[?>] )* '?'* '?>'
    ;

UNTERMINATED_PI
    : '<?' ( ~'?' | '?'+ ~[?>] )* '?'*
    ;

ENTITY_REF
    : '&' [a-zA-Z][a-zA-Z0-9]* ';'
    ;

CHAR_REF
    : '&#' [0-9]+ ';'
    | '&#' [xX] [0-9a-fA-F]+ ';'
    ;

SEA_WS
    : [ \t\r\n]+
    ;

OPEN
    : '<' { onOpenAngle() } -> pushMode(INSIDE)
    ;

TEXT
    : ~[<& \t\r\n]+
    ;

LONE_AMP
    : '&'
    ;

// ---------------- Tag header mode ----------------
mode INSIDE;

TAG_WS
    : [ \t\r\n]+
    ;

SPECIAL_CLOSE
    : '?>' { onSelfOrSpecialClose() }
    ;

SLASH_CLOSE
    : '/>' { onSelfOrSpecialClose() }
    ;

CLOSE
    : '>' { onTagClose(Modes.RAW_SCRIPT, Modes.RAW_STYLE) }
    ;

SLASH
    : '/' { onSlashInTag() }
    ;

EQUALS
    : '=' -> pushMode(ATTR_VALUE)
    ;

TAG_NAME
    : [a-zA-Z_:][a-zA-Z0-9_.:-]* { onTagName(text) }
    ;

ATTR_SPECIAL_NAME
    : ( '@' | '#' | '?' | '*' ) [a-zA-Z0-9_.:-]+
    | '[' ~[\] \t\r\n=<>]+ ']'
    | '(' ~[) \t\r\n=<>]+ ')'
    ;

INSIDE_STRING
    : '"' ~["]* '"'
    | '\'' ~[']* '\''
    ;

INSIDE_UNTERMINATED_STRING
    : '"' ~["]*
    | '\'' ~[']*
    ;

INSIDE_ERROR
    : .
    ;

// ---------------- Attribute value mode ----------------
mode ATTR_VALUE;

ATTR_WS
    : [ \t\r\n]+
    ;

ATTR_CLOSE_TAG
    : '>' { onAttrCloseTag(Modes.RAW_SCRIPT, Modes.RAW_STYLE) }
    ;

ATTR_STRING
    : ( '"' ~["]* '"' | '\'' ~[']* '\'' ) -> popMode
    ;

ATTR_UNTERMINATED_STRING
    : ( '"' ~["]* | '\'' ~[']* ) -> popMode
    ;

ATTR_UNQUOTED
    : ~[ \t\r\n"'=<>`]+ -> popMode
    ;

ATTR_ERROR
    : . -> popMode
    ;

// ---------------- Raw <script> body mode ----------------
mode RAW_SCRIPT;

SCRIPT_END_OPEN
    : '<' { isClosingTagAheadAfterAngle("script") }? { onOpenAngle() } -> mode(INSIDE)
    ;

SCRIPT_TEXT
    : ~[<]+
    ;

SCRIPT_LT
    : '<'
    ;

// ---------------- Raw <style> body mode ----------------
mode RAW_STYLE;

STYLE_END_OPEN
    : '<' { isClosingTagAheadAfterAngle("style") }? { onOpenAngle() } -> mode(INSIDE)
    ;

STYLE_TEXT
    : ~[<]+
    ;

STYLE_LT
    : '<'
    ;
