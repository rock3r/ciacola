/**
 * Lossless ANTLR4 lexer grammar for CommonMark + GFM + Frontmatter (YAML/TOML),
 * with zero position-0 semantic predicates and strictly O(N) scanning.
 */
lexer grammar MarkdownLexer;

options {
    superClass = MarkdownLexerBase;
}

tokens {
    ATX_HEADING_OPEN,
    SETEXT_UNDERLINE,
    THEMATIC_BREAK,
    FRONTMATTER_DELIM,
    FRONTMATTER_YAML_BODY,
    FRONTMATTER_TOML_BODY,
    FENCE_OPEN,
    FENCE_BODY,
    FENCE_BODY_LINE,
    FENCE_CLOSE,
    FENCE_LANG,
    FENCE_INFO_REST,
    LIST_MARKER,
    TASK_CHECKBOX,
    INLINE_CODE,
    STAR_RUN,
    UNDERSCORE_RUN,
    TILDE_TILDE,
    LBRACK,
    HTML_COMMENT,
    AUTOLINK,
    HTML_TAG_OPEN,
    HTML_NAME,
    HTML_ATTR_NAME,
    HTML_ATTR_VALUE,
    BLOCKQUOTE_MARKER,
    TEXT
}

@members {
    override fun textTokenType(): Int = Tokens.TEXT
    override fun wsTokenType(): Int = Tokens.WS
    override fun newlineTokenType(): Int = Tokens.NEWLINE
    override fun atxHeadingOpenTokenType(): Int = Tokens.ATX_HEADING_OPEN
    override fun setextUnderlineTokenType(): Int = Tokens.SETEXT_UNDERLINE
    override fun thematicBreakTokenType(): Int = Tokens.THEMATIC_BREAK
    override fun frontmatterDelimTokenType(): Int = Tokens.FRONTMATTER_DELIM
    override fun frontmatterYamlBodyTokenType(): Int = Tokens.FRONTMATTER_YAML_BODY
    override fun frontmatterTomlBodyTokenType(): Int = Tokens.FRONTMATTER_TOML_BODY
    override fun fenceOpenTokenType(): Int = Tokens.FENCE_OPEN
    override fun fenceBodyTokenType(): Int = Tokens.FENCE_BODY
    override fun fenceBodyLineTokenType(): Int = Tokens.FENCE_BODY_LINE
    override fun fenceCloseTokenType(): Int = Tokens.FENCE_CLOSE
    override fun fenceLangTokenType(): Int = Tokens.FENCE_LANG
    override fun fenceInfoRestTokenType(): Int = Tokens.FENCE_INFO_REST
    override fun listMarkerTokenType(): Int = Tokens.LIST_MARKER
    override fun taskCheckboxTokenType(): Int = Tokens.TASK_CHECKBOX
    override fun inlineCodeTokenType(): Int = Tokens.INLINE_CODE
    override fun starRunTokenType(): Int = Tokens.STAR_RUN
    override fun underscoreRunTokenType(): Int = Tokens.UNDERSCORE_RUN
    override fun tildeTildeTokenType(): Int = Tokens.TILDE_TILDE
    override fun lbrackTokenType(): Int = Tokens.LBRACK
    override fun htmlCommentTokenType(): Int = Tokens.HTML_COMMENT
    override fun autolinkTokenType(): Int = Tokens.AUTOLINK
    override fun htmlTagOpenTokenType(): Int = Tokens.HTML_TAG_OPEN
    override fun htmlNameTokenType(): Int = Tokens.HTML_NAME
    override fun htmlAttrNameTokenType(): Int = Tokens.HTML_ATTR_NAME
    override fun htmlAttrValueTokenType(): Int = Tokens.HTML_ATTR_VALUE
    override fun blockquoteMarkerTokenType(): Int = Tokens.BLOCKQUOTE_MARKER

    override fun defaultModeId(): Int = DEFAULT_MODE
    override fun inlineModeId(): Int = Modes.INLINE_MODE
    override fun indentedCodeModeId(): Int = Modes.INDENTED_CODE_MODE
    override fun frontmatterHeaderEndModeId(): Int = Modes.FRONTMATTER_HEADER_END_MODE
    override fun frontmatterBodyModeId(): Int = Modes.FRONTMATTER_BODY_MODE
    override fun frontmatterCloseModeId(): Int = Modes.FRONTMATTER_CLOSE_MODE
    override fun fenceInfoModeId(): Int = Modes.FENCE_INFO_MODE
    override fun fenceBodyModeId(): Int = Modes.FENCE_BODY_MODE
    override fun fenceCloseModeId(): Int = Modes.FENCE_CLOSE_MODE
    override fun bqFenceInfoModeId(): Int = Modes.BQ_FENCE_INFO_MODE
    override fun bqFenceBodyModeId(): Int = Modes.BQ_FENCE_BODY_MODE
    override fun htmlTagModeId(): Int = Modes.HTML_TAG_MODE
}

// =========================================================================
// DEFAULT_MODE: Line-start block dispatch
// =========================================================================

BOM
    : '\uFEFF' { onBom(); }
    ;

WS
    : [ \t]+ { onLineStartWs(); }
    ;

NEWLINE
    : ( '\r'? '\n' | '\r' ) { onDefaultNewline(); }
    ;

LINE_BLOCKQUOTE
    : '>' { onBlockquoteMarker(); } -> type(BLOCKQUOTE_MARKER)
    ;

LINE_HASH
    : '#'+ { handleLineStartHash(); }
    ;

LINE_BACKTICK
    : '`'+ { handleLineStartBackticks(); }
    ;

LINE_TILDE
    : '~'+ { handleLineStartTildes(); }
    ;

LINE_DASH
    : '-'+ { handleLineStartDash(); }
    ;

LINE_STAR
    : '*'+ { handleLineStartStar(); }
    ;

LINE_UNDERSCORE
    : '_'+ { handleLineStartUnderscore(); }
    ;

LINE_PLUS
    : '+'+ { handleLineStartPlus(); }
    ;

LINE_EQUALS
    : '='+ { handleLineStartEquals(); }
    ;

LINE_DIGITS
    : [0-9]+ { handleLineStartDigits(); }
    ;

LINE_LBRACK
    : '[' { handleLineStartLbrack(); }
    ;

LINE_HTML_COMMENT
    : '<!--' { consumeHtmlComment(); onNonParagraphLineStart(); } -> mode(INLINE_MODE)
    ;

LINE_LT
    : '<' { handleLineStartLt(); }
    ;

LINE_BANG_LBRACK
    : '![' { onInlineNonWs(); } -> type(BANG_LBRACK), mode(INLINE_MODE)
    ;

LINE_PIPE
    : '|' { onInlineNonWs(); } -> type(PIPE), mode(INLINE_MODE)
    ;

LINE_ESCAPE
    : '\\' ASCII_PUNCT { onInlineNonWs(); } -> type(ESCAPE), mode(INLINE_MODE)
    ;

LINE_HARD_BREAK_ESCAPE
    : '\\' { _input.LA(1) == '\r'.code || _input.LA(1) == '\n'.code }? { onInlineNonWs(); } -> type(ESCAPE), mode(INLINE_MODE)
    ;

LINE_ENTITY_REF
    : '&' ( [A-Za-z][A-Za-z0-9]* | '#' ( [0-9]+ | [xX][0-9A-Fa-f]+ ) ) ';' { onInlineNonWs(); } -> type(ENTITY_REF), mode(INLINE_MODE)
    ;

LINE_COLON
    : ':' { onInlineNonWs(); } -> type(COLON), mode(INLINE_MODE)
    ;

LINE_LPAREN
    : '(' { onInlineNonWs(); } -> type(LPAREN), mode(INLINE_MODE)
    ;

LINE_RPAREN
    : ')' { onInlineNonWs(); } -> type(RPAREN), mode(INLINE_MODE)
    ;

LINE_RBRACK
    : ']' { onInlineNonWs(); } -> type(RBRACK), mode(INLINE_MODE)
    ;

LINE_TEXT
    : ~[ \t\r\n\uFEFF>#`~*_+=0-9[\]<!\\&:()|-]+ { onInlineNonWs(); } -> type(TEXT), mode(INLINE_MODE)
    ;

LINE_FALLBACK
    : . { onInlineNonWs(); } -> type(TEXT), mode(INLINE_MODE)
    ;

fragment ASCII_PUNCT
    : [\u0021-\u002F\u003A-\u0040\u005B-\u0060\u007B-\u007E]
    ;

// =========================================================================
// INLINE_MODE: Mid-line inline constructs
// =========================================================================
mode INLINE_MODE;

INLINE_WS
    : [ \t]+ -> type(WS)
    ;

INLINE_NEWLINE
    : ( '\r'? '\n' | '\r' ) { onInlineNewline(); } -> type(NEWLINE), mode(DEFAULT_MODE)
    ;

INLINE_BACKTICK
    : '`'+ { handleInlineBackticksToken(); }
    ;

INLINE_STAR
    : '*'+ -> type(STAR_RUN)
    ;

INLINE_UNDERSCORE
    : '_'+ -> type(UNDERSCORE_RUN)
    ;

INLINE_TILDE_TILDE
    : '~~' -> type(TILDE_TILDE)
    ;

BANG_LBRACK
    : '!['
    ;

INLINE_LBRACK
    : '[' -> type(LBRACK)
    ;

RBRACK
    : ']'
    ;

LPAREN
    : '('
    ;

RPAREN
    : ')'
    ;

PIPE
    : '|'
    ;

COLON
    : ':'
    ;

ESCAPE
    : '\\' ASCII_PUNCT
    ;

HARD_BREAK_ESCAPE
    : '\\' { _input.LA(1) == '\r'.code || _input.LA(1) == '\n'.code }? -> type(ESCAPE)
    ;

INLINE_HTML_COMMENT
    : '<!--' { consumeHtmlComment(); }
    ;

INLINE_LT
    : '<' { handleInlineLt(); }
    ;

ENTITY_REF
    : '&' ( [A-Za-z][A-Za-z0-9]* | '#' ( [0-9]+ | [xX][0-9A-Fa-f]+ ) ) ';'
    ;

INLINE_TEXT
    : ~[ \t\r\n`*_~![\]()|:\\<&]+ -> type(TEXT)
    ;

INLINE_FALLBACK
    : . -> type(TEXT)
    ;

// =========================================================================
// INDENTED_CODE_MODE: Lines indented >= 4 columns after blank/doc-start
// =========================================================================
mode INDENTED_CODE_MODE;

INDENTED_CODE_LINE
    : ~[\r\n]+
    ;

IC_NEWLINE
    : ( '\r'? '\n' | '\r' ) { onIndentedCodeNewline(); } -> type(NEWLINE), mode(DEFAULT_MODE)
    ;

// =========================================================================
// FRONTMATTER modes (YAML --- and TOML +++)
// =========================================================================
mode FRONTMATTER_HEADER_END_MODE;

FMH_WS
    : [ \t]+ -> type(WS)
    ;

FMH_NEWLINE
    : ( '\r'? '\n' | '\r' ) { onFrontmatterHeaderNewline(); } -> type(NEWLINE)
    ;

mode FRONTMATTER_BODY_MODE;

FM_BODY
    : ( ~[\r\n] | '\r' | '\n' ) { consumeFrontmatterBody(); } -> mode(FRONTMATTER_CLOSE_MODE)
    ;

mode FRONTMATTER_CLOSE_MODE;

FMC_DELIM
    : ( '---' | '...' | '+++' ) -> type(FRONTMATTER_DELIM)
    ;

FMC_WS
    : [ \t]+ -> type(WS)
    ;

FMC_NEWLINE
    : ( '\r'? '\n' | '\r' ) { onFrontmatterCloseNewline(); } -> type(NEWLINE), mode(DEFAULT_MODE)
    ;

// =========================================================================
// FENCE modes (Top-level fenced code blocks)
// =========================================================================
mode FENCE_INFO_MODE;

FENCE_INFO_WS
    : [ \t]+
    ;

FI_WORD
    : ~[ \t\r\n{,;]+ { onFenceInfoWord(); }
    ;

FI_SEP
    : [{,;]+ -> type(FENCE_INFO_REST)
    ;

FI_NEWLINE
    : ( '\r'? '\n' | '\r' ) { onFenceInfoNewline(); } -> type(NEWLINE)
    ;

mode FENCE_BODY_MODE;

FB_BODY
    : ( ~[\r\n] | '\r' | '\n' ) { consumeFenceBody(); } -> mode(FENCE_CLOSE_MODE)
    ;

mode FENCE_CLOSE_MODE;

FC_WS
    : [ \t]+ -> type(WS)
    ;

FC_CLOSE
    : ( '`'+ | '~'+ ) -> type(FENCE_CLOSE)
    ;

FC_NEWLINE
    : ( '\r'? '\n' | '\r' ) { onDefaultNewline(); } -> type(NEWLINE), mode(DEFAULT_MODE)
    ;

FC_FALLBACK
    : ~[ \t\r\n`~]+ -> type(TEXT), mode(DEFAULT_MODE)
    ;

// =========================================================================
// BQ_FENCE modes (Fenced code blocks nested inside blockquotes)
// =========================================================================
mode BQ_FENCE_INFO_MODE;

BFI_WS
    : [ \t]+ -> type(FENCE_INFO_WS)
    ;

BFI_WORD
    : ~[ \t\r\n{,;]+ { onFenceInfoWord(); }
    ;

BFI_SEP
    : [{,;]+ -> type(FENCE_INFO_REST)
    ;

BFI_NEWLINE
    : ( '\r'? '\n' | '\r' ) { onBqFenceInfoNewline(); } -> type(NEWLINE)
    ;

mode BQ_FENCE_BODY_MODE;

BFB_WS
    : [ \t]+ { onBqFenceWs(); } -> type(WS)
    ;

BFB_BQ
    : '>' { handleBqFenceMarker(); }
    ;

BFB_CLOSE_BACKTICK
    : '`'+ { handleBqFencePotentialClose(); }
    ;

BFB_CLOSE_TILDE
    : '~'+ { handleBqFencePotentialClose(); }
    ;

BFB_LINE
    : ~[ \t\r\n>`~]+ { consumeBqFenceRestOfLine(); }
    ;

BFB_NEWLINE
    : ( '\r'? '\n' | '\r' ) { onBqFenceBodyNewline(); } -> type(NEWLINE)
    ;

BFB_FALLBACK
    : . { consumeBqFenceRestOfLine(); }
    ;

// =========================================================================
// HTML_TAG_MODE (Inline HTML tags with validated closing '>')
// =========================================================================
mode HTML_TAG_MODE;

HTML_TAG_WS
    : [ \t\r\n]+ -> type(WS)
    ;

HTML_TAG_SLASH
    : '/'
    ;

HTML_TAG_EQ
    : '='
    ;

HTML_TAG_CLOSE
    : '>' -> popMode
    ;

HTML_TAG_STRING
    : ( '"' ~["]* '"' | '\'' ~[']* '\'' ) -> type(HTML_ATTR_VALUE)
    ;

HTML_TAG_NAME_OR_ATTR
    : [A-Za-z:_][A-Za-z0-9:._-]* { onHtmlName(); }
    ;

HTML_TAG_UNQUOTED
    : ~[ \t\r\n/>='"<&]+ -> type(HTML_ATTR_VALUE)
    ;

HTML_TAG_ERROR
    : .
    ;
