/**
 * Lossless ANTLR4 lexer grammar for YAML 1.2 (supporting directives, document markers,
 * indentation-aware block scalars, anchors/aliases/tags, flow/block collections, and scalars).
 */
lexer grammar YamlLexer;

options {
    superClass = YamlLexerBase;
}

WS
    : [ \t\uFEFF]+ { onWs(); }
    ;

NEWLINE
    : ( '\r'? '\n' | '\r' ) { onNewline(); }
    ;

DIRECTIVE_START
    : '%' { isAtLineStart() }? { onDirectiveStart(); } -> pushMode(DIRECTIVE_MODE)
    ;

DOC_START
    : '---' { isDocMarker() }? { onDocMarker(); }
    ;

DOC_END
    : '...' { isDocMarker() }? { onDocMarker(); }
    ;

COMMENT
    : '#' { isCommentAllowed() }? ~[\r\n]*
    ;

MERGE_KEY
    : '<<' { isFollowedByMappingColon() }? { markNonIndicatorContent(); markQuoted(false); }
    ;

SEQUENCE_ENTRY
    : '-' { isIndicatorBoundary() }? { onLeadingIndicator(); markQuoted(false); }
    ;

COMPLEX_KEY
    : '?' { isIndicatorBoundary() }? { onLeadingIndicator(); markQuoted(false); }
    ;

COLON
    : ':' { isMappingColon() }? { onMappingColon(); markQuoted(false); }
    ;

LBRACE
    : '{' { onOpenFlow(); }
    ;

RBRACE
    : '}' { onCloseFlow(); }
    ;

LBRACK
    : '[' { onOpenFlow(); }
    ;

RBRACK
    : ']' { onCloseFlow(); }
    ;

COMMA
    : ',' { markQuoted(false); }
    ;

ANCHOR_SIGIL
    : '&' { isFollowedByAnchorChar() }? { markNonIndicatorContent(); markQuoted(false); } -> pushMode(ANCHOR_MODE)
    ;

ALIAS_SIGIL
    : '*' { isFollowedByAnchorChar() }? { onNonPlainScalar(false); } -> pushMode(ALIAS_MODE)
    ;

VERBATIM_TAG
    : '!<' ~[ \t\r\n><[\]{}]+ '>' { markNonIndicatorContent(); markQuoted(false); }
    ;

TAG
    : '!' '!'? TagChar* { markNonIndicatorContent(); markQuoted(false); }
    ;

BLOCK_SCALAR_START
    : [|>] { isBlockScalarHeaderAhead() }? { onBlockScalarStart(); markQuoted(false); } -> pushMode(BLOCK_SCALAR_HEADER_MODE)
    ;

DOUBLE_QUOTED_STRING
    : '"' ( '\\' ( '\r'? '\n' | . ) | ~["\\] )* '"' { onQuotedScalarDone(); }
    ;

UNTERMINATED_DOUBLE_QUOTED_STRING
    : '"' ( '\\' ( '\r'? '\n' | . ) | ~["\\] )* '\\'? { onQuotedScalarDone(); }
    ;

SINGLE_QUOTED_STRING
    : '\'' ( '\'\'' | ~['] )* '\'' { onQuotedScalarDone(); }
    ;

UNTERMINATED_SINGLE_QUOTED_STRING
    : '\'' ( '\'\'' | ~['] )* { onQuotedScalarDone(); }
    ;

BOOL
    : ( 'true' | 'True' | 'TRUE' | 'false' | 'False' | 'FALSE' ) { isScalarBoundary() }? { onNonPlainScalar(false); }
    ;

NULL
    : ( 'null' | 'Null' | 'NULL' | '~' ) { isScalarBoundary() }? { onNonPlainScalar(false); }
    ;

SPECIAL_FLOAT
    : [+-]? ( '.inf' | '.Inf' | '.INF' | '.nan' | '.NaN' | '.NAN' ) { isScalarBoundary() }? { onNonPlainScalar(false); }
    ;

TIMESTAMP
    : [0-9][0-9][0-9][0-9] '-' [0-9][0-9] '-' [0-9][0-9]
      ( ( [Tt] | [ \t]+ ) [0-9][0-9]? ':' [0-9][0-9] ':' [0-9][0-9] ( '.' [0-9]* )?
        ( [ \t]* ( [Zz] | [+-] [0-9][0-9]? ( ':' [0-9][0-9] )? ) )?
      )?
      { isScalarBoundary() }? { onNonPlainScalar(false); }
    ;

HEX_INT
    : '0x' [0-9a-fA-F]+ { isScalarBoundary() }? { onNonPlainScalar(false); }
    ;

OCT_INT
    : '0o' [0-7]+ { isScalarBoundary() }? { onNonPlainScalar(false); }
    ;

FLOAT
    : [+-]?
      ( [0-9]+ '.' [0-9]* ( [eE] [+-]? [0-9]+ )?
      | '.' [0-9]+ ( [eE] [+-]? [0-9]+ )?
      | [0-9]+ [eE] [+-]? [0-9]+
      )
      { isScalarBoundary() }? { onNonPlainScalar(false); }
    ;

INT
    : [+-]? [0-9]+ { isScalarBoundary() }? { onNonPlainScalar(false); }
    ;

PLAIN_SCALAR
    : PlainStartChar { consumePlainScalar(); onPlainScalarDone(Modes.PLAIN_CONT_MODE); }
    ;

ERROR_CHAR
    : . { onNonPlainScalar(false); }
    ;

fragment TagChar
    : ~[ \t\r\n,[\]{}!<]
    ;

fragment PlainStartChar
    : ~[ \t\r\n\uFEFF,[\]{}#&*!|>'"%@`]
    ;

mode DIRECTIVE_MODE;

DIR_WS
    : [ \t]+
    ;

DIR_NEWLINE
    : ( '\r'? '\n' | '\r' ) { onNewline(); } -> popMode
    ;

DIR_COMMENT
    : '#' { isCommentAllowed() }? ~[\r\n]*
    ;

DIR_VERSION
    : [0-9]+ '.' [0-9]+
    ;

DIR_TAG_HANDLE
    : '!' ( [A-Za-z0-9_-]+ '!' | '!' )?
    ;

DIR_NAME
    : [A-Za-z][A-Za-z0-9_-]*
    ;

DIR_ARG
    : ~[ \t\r\n#]+
    ;

mode ANCHOR_MODE;

ANCHOR_NAME
    : ~[ \t\r\n\uFEFF,[\]{}]+ -> popMode
    ;

ANCHOR_FALLBACK
    : . -> popMode
    ;

mode ALIAS_MODE;

ALIAS_NAME
    : ~[ \t\r\n\uFEFF,[\]{}]+ -> popMode
    ;

ALIAS_FALLBACK
    : . -> popMode
    ;

mode BLOCK_SCALAR_HEADER_MODE;

BS_WS
    : [ \t]+
    ;

BS_INDENT
    : [1-9] { onBlockScalarIndentDigit(text[0].code); }
    ;

BS_CHOMP
    : [+-]
    ;

BS_COMMENT
    : '#' { isCommentAllowed() }? ~[\r\n]*
    ;

BS_NEWLINE
    : ( '\r'? '\n' | '\r' ) { onNewline(); onBlockScalarHeaderNewline(Modes.BLOCK_SCALAR_BODY_MODE); }
    ;

BS_ERROR
    : .
    ;

mode BLOCK_SCALAR_BODY_MODE;

BLOCK_SCALAR_BODY
    : . { consumeBlockScalarBody(); onBlockScalarBodyDone(); } -> popMode
    ;

mode PLAIN_CONT_MODE;

PC_WS
    : [ \t]+
    ;

PC_NEWLINE
    : ( '\r'? '\n' | '\r' ) { onNewline(); }
    ;

PC_PLAIN_SCALAR
    : ~[ \t\r\n] { consumePlainScalarContinuation(); }
    ;
