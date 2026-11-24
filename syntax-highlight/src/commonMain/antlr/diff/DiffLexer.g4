/**
 * Lossless ANTLR4 lexer grammar for Diff / Patch formats (Git extended unified diffs,
 * combined merge diffs, standard unified diffs, context diffs, normal diffs, binary
 * patches, and email/SVN/Mercurial patch headers).
 */
lexer grammar DiffLexer;

options {
    superClass = DiffLexerBase;
}

BOM
    : '\uFEFF'
    ;

NEWLINE
    : '\r'? '\n'
    | '\r'
    ;

GIT_DIFF_HEADER
    : 'diff' [ \t]+ ( '--git' | '--cc' | '--combined' | '-' ~[\r\n \t]+ ) ( [ \t] ~[\r\n]* )?
    ;

GIT_INDEX_HEADER
    : 'index' [ \t]+ [0-9a-fA-F]+ ( ',' [0-9a-fA-F]+ )* '..' [0-9a-fA-F]+ ~[\r\n]*
    ;

GIT_MODE_HEADER
    : ( 'old mode' | 'new mode' | 'deleted file mode' | 'new file mode' ) [ \t]+ [0-7]+ [ \t]*
    ;

GIT_COPY_RENAME_HEADER
    : ( 'copy from' | 'copy to' | 'rename from' | 'rename to' ) [ \t]+ ~[\r\n]*
    ;

GIT_SIMILARITY_HEADER
    : ( 'similarity index' | 'dissimilarity index' ) [ \t]+ [0-9]+ '%' [ \t]*
    ;

GIT_BINARY_FILES
    : 'Binary files' [ \t]+ ~[\r\n]*
    | 'Files' [ \t]+ ~[\r\n]+ [ \t]+ 'differ' [ \t]*
    ;

GIT_BINARY_PATCH
    : 'GIT binary patch' [ \t]* -> mode(BINARY_PATCH_MODE)
    ;

SVN_INDEX_HEADER
    : [Ii] 'ndex:' ~[\r\n]*
    ;

HEADER_SEPARATOR
    : '====' '='* [ \t]*
    ;

PATCH_FROM_ENVELOPE
    : 'From' [ \t]+ [0-9a-fA-F][0-9a-fA-F][0-9a-fA-F][0-9a-fA-F]+ [ \t]+ ~[\r\n]*
    ;

EMAIL_HEADER
    : ( 'From'
      | 'Date'
      | 'Subject'
      | 'Message-Id'
      | 'Message-ID'
      | 'MIME-Version'
      | 'Content-Type'
      | 'Content-Transfer-Encoding'
      ) ':' ~[\r\n]*
    ;

CONTEXT_HUNK_STAR_HEADER
    : '***' [ \t]+ [0-9]+ ( ',' [0-9]+ )? [ \t]+ '****' [ \t]* -> mode(CONTEXT_HUNK_MODE)
    ;

CONTEXT_HUNK_DASH_HEADER
    : '---' [ \t]+ [0-9]+ ( ',' [0-9]+ )? [ \t]+ '----' [ \t]* -> mode(CONTEXT_HUNK_MODE)
    ;

CONTEXT_SEPARATOR
    : '***************' '*'* [ \t]*
    ;

CONTEXT_FILE_HEADER
    : '***' [ \t]+ ~[\r\n]+
    ;

UNIFIED_FROM_FILE
    : '---' [ \t]+ ~[\r\n]+
    ;

UNIFIED_TO_FILE
    : '+++' [ \t]+ ~[\r\n]+
    ;

SEPARATOR_TRIPLE_DASH
    : '---' [ \t]*
    ;

HUNK_HEADER_UNIFIED
    : '@@' [ \t]* '-' [0-9]+ ( ',' [0-9]+ )? [ \t]+ '+' [0-9]+ ( ',' [0-9]+ )? [ \t]* '@@' ~[\r\n]*
      { onUnifiedHunkHeader(Modes.DEFAULT_MODE, Modes.UNIFIED_HUNK_MODE); }
    ;

HUNK_HEADER_COMBINED
    : '@@@' '@'* [ \t]* ( '-' [0-9]+ ( ',' [0-9]+ )? [ \t]+ )+ '+' [0-9]+ ( ',' [0-9]+ )? [ \t]* '@@@' '@'* ~[\r\n]*
      { onCombinedHunkHeader(Modes.DEFAULT_MODE, Modes.COMBINED_HUNK_MODE); }
    ;

HUNK_HEADER_NORMAL
    : [0-9]+ ( ',' [0-9]+ )? [acd] [0-9]+ ( ',' [0-9]+ )? [ \t]* -> mode(NORMAL_HUNK_MODE)
    ;

NO_NEWLINE
    : '\\' [ \t]* 'No newline at end of file' [ \t]*
    ;

DIFF_COMMENT
    : '#' ~[\r\n]*
    ;

NORMAL_DEL_PREFIX
    : ( '< ' | '<' ) -> pushMode(BODY_TEXT_MODE)
    ;

NORMAL_ADD_PREFIX
    : ( '> ' | '>' ) -> pushMode(BODY_TEXT_MODE)
    ;

UNIFIED_ADD_PREFIX
    : '+' -> pushMode(BODY_TEXT_MODE)
    ;

UNIFIED_DEL_PREFIX
    : '-' -> pushMode(BODY_TEXT_MODE)
    ;

CONTEXT_CHG_PREFIX
    : ( '! ' | '!' ) -> pushMode(BODY_TEXT_MODE)
    ;

DEFAULT_SPACE_PREFIX
    : ' ' -> pushMode(BODY_TEXT_MODE)
    ;

BACKSLASH_FALLBACK
    : '\\' ~[\r\n]*
    ;

PREAMBLE_LINE
    : ~[+\- !<>#\\\r\n\uFEFF] ~[\r\n]*
    ;

mode UNIFIED_HUNK_MODE;

UH_NEWLINE
    : ( '\r'? '\n' | '\r' ) { onUnifiedNewline(Modes.DEFAULT_MODE); }
    ;

UH_GIT_DIFF
    : 'diff' [ \t]+ ( '--git' | '--cc' | '--combined' | '-' ~[\r\n \t]+ ) ( [ \t] ~[\r\n]* )?
      { resetHunkCounts(); } -> mode(DEFAULT_MODE)
    ;

UH_SVN_INDEX
    : [Ii] 'ndex:' ~[\r\n]* { resetHunkCounts(); } -> mode(DEFAULT_MODE)
    ;

UH_HEADER_SEP
    : '====' '='* [ \t]* { resetHunkCounts(); } -> mode(DEFAULT_MODE)
    ;

UH_HUNK_UNIFIED
    : '@@' [ \t]* '-' [0-9]+ ( ',' [0-9]+ )? [ \t]+ '+' [0-9]+ ( ',' [0-9]+ )? [ \t]* '@@' ~[\r\n]*
      { onUnifiedHunkHeader(Modes.DEFAULT_MODE, Modes.UNIFIED_HUNK_MODE); }
    ;

UH_HUNK_COMBINED
    : '@@@' '@'* [ \t]* ( '-' [0-9]+ ( ',' [0-9]+ )? [ \t]+ )+ '+' [0-9]+ ( ',' [0-9]+ )? [ \t]* '@@@' '@'* ~[\r\n]*
      { onCombinedHunkHeader(Modes.DEFAULT_MODE, Modes.COMBINED_HUNK_MODE); }
    ;

UH_HUNK_NORMAL
    : [0-9]+ ( ',' [0-9]+ )? [acd] [0-9]+ ( ',' [0-9]+ )? [ \t]* { resetHunkCounts(); } -> mode(NORMAL_HUNK_MODE)
    ;

UH_ADD_PREFIX
    : '+' { onUnifiedAdd(); } -> pushMode(BODY_TEXT_MODE)
    ;

UH_DEL_PREFIX
    : '-' { onUnifiedDel(); } -> pushMode(BODY_TEXT_MODE)
    ;

UH_CTX_PREFIX
    : ' ' { onUnifiedCtx(); } -> pushMode(BODY_TEXT_MODE)
    ;

UH_NO_NEWLINE
    : '\\' [ \t]* 'No newline at end of file' [ \t]*
    ;

UH_BACKSLASH_LINE
    : '\\' ~[\r\n]* { resetHunkCounts(); } -> mode(DEFAULT_MODE)
    ;

UH_FALLBACK_LINE
    : ~[+\- \\\r\n] ~[\r\n]* { resetHunkCounts(); } -> mode(DEFAULT_MODE)
    ;

mode COMBINED_HUNK_MODE;

CH_NEWLINE
    : ( '\r'? '\n' | '\r' ) { onCombinedNewline(Modes.DEFAULT_MODE); }
    ;

CH_GIT_DIFF
    : 'diff' [ \t]+ ( '--git' | '--cc' | '--combined' | '-' ~[\r\n \t]+ ) ( [ \t] ~[\r\n]* )?
      { resetHunkCounts(); } -> mode(DEFAULT_MODE)
    ;

CH_SVN_INDEX
    : [Ii] 'ndex:' ~[\r\n]* { resetHunkCounts(); } -> mode(DEFAULT_MODE)
    ;

CH_HEADER_SEP
    : '====' '='* [ \t]* { resetHunkCounts(); } -> mode(DEFAULT_MODE)
    ;

CH_HUNK_UNIFIED
    : '@@' [ \t]* '-' [0-9]+ ( ',' [0-9]+ )? [ \t]+ '+' [0-9]+ ( ',' [0-9]+ )? [ \t]* '@@' ~[\r\n]*
      { onUnifiedHunkHeader(Modes.DEFAULT_MODE, Modes.UNIFIED_HUNK_MODE); }
    ;

CH_HUNK_COMBINED
    : '@@@' '@'* [ \t]* ( '-' [0-9]+ ( ',' [0-9]+ )? [ \t]+ )+ '+' [0-9]+ ( ',' [0-9]+ )? [ \t]* '@@@' '@'* ~[\r\n]*
      { onCombinedHunkHeader(Modes.DEFAULT_MODE, Modes.COMBINED_HUNK_MODE); }
    ;

CH_PREFIX
    : [+\- ] { consumeCombinedPrefix(); } -> pushMode(BODY_TEXT_MODE)
    ;

CH_NO_NEWLINE
    : '\\' [ \t]* 'No newline at end of file' [ \t]*
    ;

CH_BACKSLASH_LINE
    : '\\' ~[\r\n]* { resetHunkCounts(); } -> mode(DEFAULT_MODE)
    ;

CH_FALLBACK_LINE
    : ~[+\- \\\r\n] ~[\r\n]* { resetHunkCounts(); } -> mode(DEFAULT_MODE)
    ;

mode CONTEXT_HUNK_MODE;

CX_NEWLINE
    : '\r'? '\n'
    | '\r'
    ;

CX_GIT_DIFF
    : 'diff' [ \t]+ ( '--git' | '--cc' | '--combined' | '-' ~[\r\n \t]+ ) ( [ \t] ~[\r\n]* )? -> mode(DEFAULT_MODE)
    ;

CX_SVN_INDEX
    : [Ii] 'ndex:' ~[\r\n]* -> mode(DEFAULT_MODE)
    ;

CX_HEADER_SEP
    : '====' '='* [ \t]* -> mode(DEFAULT_MODE)
    ;

CX_HUNK_UNIFIED
    : '@@' [ \t]* '-' [0-9]+ ( ',' [0-9]+ )? [ \t]+ '+' [0-9]+ ( ',' [0-9]+ )? [ \t]* '@@' ~[\r\n]*
      { onUnifiedHunkHeader(Modes.DEFAULT_MODE, Modes.UNIFIED_HUNK_MODE); }
    ;

CX_HUNK_COMBINED
    : '@@@' '@'* [ \t]* ( '-' [0-9]+ ( ',' [0-9]+ )? [ \t]+ )+ '+' [0-9]+ ( ',' [0-9]+ )? [ \t]* '@@@' '@'* ~[\r\n]*
      { onCombinedHunkHeader(Modes.DEFAULT_MODE, Modes.COMBINED_HUNK_MODE); }
    ;

CX_HUNK_NORMAL
    : [0-9]+ ( ',' [0-9]+ )? [acd] [0-9]+ ( ',' [0-9]+ )? [ \t]* -> mode(NORMAL_HUNK_MODE)
    ;

CX_SEPARATOR
    : '***************' '*'* [ \t]*
    ;

CX_HUNK_STAR
    : '***' [ \t]+ [0-9]+ ( ',' [0-9]+ )? [ \t]+ '****' [ \t]*
    ;

CX_HUNK_DASH
    : '---' [ \t]+ [0-9]+ ( ',' [0-9]+ )? [ \t]+ '----' [ \t]*
    ;

CX_FROM_FILE
    : '***' [ \t]+ ~[\r\n]+ -> mode(DEFAULT_MODE)
    ;

CX_TO_FILE
    : '---' [ \t]+ ~[\r\n]+ -> mode(DEFAULT_MODE)
    ;

CX_CHG_PREFIX
    : ( '! ' | '!' ) -> pushMode(BODY_TEXT_MODE)
    ;

CX_ADD_PREFIX
    : ( '+ ' | '+' ) -> pushMode(BODY_TEXT_MODE)
    ;

CX_DEL_PREFIX
    : ( '- ' | '-' ) -> pushMode(BODY_TEXT_MODE)
    ;

CX_CTX_PREFIX
    : ( '  ' | ' ' ) -> pushMode(BODY_TEXT_MODE)
    ;

CX_NO_NEWLINE
    : '\\' [ \t]* 'No newline at end of file' [ \t]*
    ;

CX_BACKSLASH_LINE
    : '\\' ~[\r\n]* -> mode(DEFAULT_MODE)
    ;

CX_FALLBACK_LINE
    : ~[!+\- \\\r\n] ~[\r\n]* -> mode(DEFAULT_MODE)
    ;

mode NORMAL_HUNK_MODE;

NH_NEWLINE
    : '\r'? '\n'
    | '\r'
    ;

NH_GIT_DIFF
    : 'diff' [ \t]+ ( '--git' | '--cc' | '--combined' | '-' ~[\r\n \t]+ ) ( [ \t] ~[\r\n]* )? -> mode(DEFAULT_MODE)
    ;

NH_SVN_INDEX
    : [Ii] 'ndex:' ~[\r\n]* -> mode(DEFAULT_MODE)
    ;

NH_HEADER_SEP
    : '====' '='* [ \t]* -> mode(DEFAULT_MODE)
    ;

NH_HUNK_UNIFIED
    : '@@' [ \t]* '-' [0-9]+ ( ',' [0-9]+ )? [ \t]+ '+' [0-9]+ ( ',' [0-9]+ )? [ \t]* '@@' ~[\r\n]*
      { onUnifiedHunkHeader(Modes.DEFAULT_MODE, Modes.UNIFIED_HUNK_MODE); }
    ;

NH_HUNK_NORMAL
    : [0-9]+ ( ',' [0-9]+ )? [acd] [0-9]+ ( ',' [0-9]+ )? [ \t]*
    ;

NH_UNIFIED_FROM_FILE
    : '---' [ \t]+ ~[\r\n]+ -> mode(DEFAULT_MODE)
    ;

NH_UNIFIED_TO_FILE
    : '+++' [ \t]+ ~[\r\n]+ -> mode(DEFAULT_MODE)
    ;

NH_DEL_PREFIX
    : ( '< ' | '<' ) -> pushMode(BODY_TEXT_MODE)
    ;

NH_ADD_PREFIX
    : ( '> ' | '>' ) -> pushMode(BODY_TEXT_MODE)
    ;

NH_SEP
    : '---' [ \t]*
    ;

NH_NO_NEWLINE
    : '\\' [ \t]* 'No newline at end of file' [ \t]*
    ;

NH_BACKSLASH_LINE
    : '\\' ~[\r\n]* -> mode(DEFAULT_MODE)
    ;

NH_FALLBACK_LINE
    : ~[<>\\\r\n] ~[\r\n]* -> mode(DEFAULT_MODE)
    ;

mode BINARY_PATCH_MODE;

BP_NEWLINE
    : '\r'? '\n'
    | '\r'
    ;

BP_GIT_DIFF
    : 'diff' [ \t]+ ( '--git' | '--cc' | '--combined' | '-' ~[\r\n \t]+ ) ( [ \t] ~[\r\n]* )? -> mode(DEFAULT_MODE)
    ;

BP_SVN_INDEX
    : [Ii] 'ndex:' ~[\r\n]* -> mode(DEFAULT_MODE)
    ;

BP_HEADER_SEP
    : '====' '='* [ \t]* -> mode(DEFAULT_MODE)
    ;

BP_UNIFIED_FROM
    : '---' [ \t]+ ~[\r\n]+ -> mode(DEFAULT_MODE)
    ;

BP_CONTEXT_FILE
    : '***' [ \t]+ ~[\r\n]+ -> mode(DEFAULT_MODE)
    ;

BP_UNIFIED_TO
    : '+++' [ \t]+ ~[\r\n]+ -> mode(DEFAULT_MODE)
    ;

BP_HUNK_UNIFIED
    : '@@' [ \t]* '-' [0-9]+ ( ',' [0-9]+ )? [ \t]+ '+' [0-9]+ ( ',' [0-9]+ )? [ \t]* '@@' ~[\r\n]*
      { onUnifiedHunkHeader(Modes.DEFAULT_MODE, Modes.UNIFIED_HUNK_MODE); }
    ;

BP_HEADER
    : ( 'literal' | 'delta' ) [ \t]+ [0-9]+ [ \t]*
    ;

BP_PAYLOAD
    : [A-Za-z] [0-9A-Za-z!#$%&()*+\-;<=>?@^_`{|}~]*
    ;

BP_FALLBACK_LINE
    : ~[\r\n]+ -> mode(DEFAULT_MODE)
    ;

mode BODY_TEXT_MODE;

BODY_TEXT
    : ~[\r\n]+
    ;

BODY_NEWLINE
    : ( '\r'? '\n' | '\r' ) { onBodyNewline(Modes.DEFAULT_MODE, Modes.UNIFIED_HUNK_MODE, Modes.COMBINED_HUNK_MODE); }
    ;
