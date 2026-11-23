/*
 [The "BSD licence"]
 Copyright (c) 2013 Terence Parr
 All rights reserved.

 Redistribution and use in source and binary forms, with or without
 modification, are permitted provided that the following conditions
 are met:
 1. Redistributions of source code must retain the above copyright
    notice, this list of conditions and the following disclaimer.
 2. Redistributions in binary form must reproduce the above copyright
    notice, this list of conditions and the following disclaimer in the
    documentation and/or other materials provided with the distribution.
 3. The name of the author may not be used to endorse or promote products
    derived from this software without specific prior written permission.

 THIS SOFTWARE IS PROVIDED BY THE AUTHOR ``AS IS'' AND ANY EXPRESS OR
 IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES
 OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
 IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY DIRECT, INDIRECT,
 INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT
 NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF
 THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
*/

/** XML lexer derived from ANTLR v4 ref guide book example */

// $antlr-format alignTrailingComments true, columnLimit 150, maxEmptyLinesToKeep 1, reflowComments false, useTab false
// $antlr-format allowShortRulesOnASingleLine true, allowShortBlocksOnASingleLine true, minEmptyLines 0, alignSemicolons ownLine
// $antlr-format alignColons trailing, singleLineOverrulesHangingColon true, alignLexerCommands true, alignLabels true, alignTrailers true

lexer grammar XMLLexer;

// Default "mode": Everything OUTSIDE of a tag
COMMENT
    : '<!--' ( ~'-' | '-' ~'-' | '-' '-'+ ~[->] )* '-'* '-->'
    ;

UNTERMINATED_COMMENT
    : '<!--' ( ~'-' | '-' ~'-' | '-' '-'+ ~[->] )* '-'*
    ;

CDATA
    : '<![CDATA[' ( ~']' | ']' ~']' | ']'+ ~[\]>] )* ']'* ']]>'
    ;

UNTERMINATED_CDATA
    : '<![CDATA[' ( ~']' | ']' ~']' | ']'+ ~[\]>] )* ']'*
    ;

/** DTD declarations and internal subsets such as <!DOCTYPE ... [...]> */
DTD_OPEN
    : '<!' [A-Za-z]+ -> pushMode(DTD_MODE)
    ;

EntityRef : '&' Name ';';
CharRef   : '&#' DIGIT+ ';' | '&#x' HEXDIGIT+ ';';
SEA_WS    : (' ' | '\t' | '\r'? '\n' | '\uFEFF')+;

OPEN        : '<'       -> pushMode(INSIDE);
XMLDeclOpen : '<?xml' S -> pushMode(INSIDE);

PI
    : '<?' ( ( 'x' | 'xm' | 'xml' )? '?'* '?>' | PI_BODY_START ( ~'?' | '?'+ ~[?>] )* '?'* '?>' )
    ;

UNTERMINATED_PI
    : '<?' ( PI_BODY_START ( ~'?' | '?'+ ~[?>] )* '?'* | ( 'x' | 'xm' | 'xml' )? '?'* )
    ;

fragment PI_BODY_START
    : ~[x?]
    | 'x' ~[m?]
    | 'xm' ~[l?]
    | 'xml' ~[ \t\r\n?]
    | ( 'x' | 'xm' | 'xml' )? '?'+ ~[?>]
    ;

TEXT: ~[<&]+; // match any 16 bit char other than < and &

// ----------------- Everything INSIDE of a tag ---------------------
mode INSIDE;

CLOSE         : '>'  -> popMode;
SPECIAL_CLOSE : '?>' -> popMode; // close <?xml...?>
SLASH_CLOSE   : '/>' -> popMode;
SLASH         : '/';
EQUALS        : '=';
STRING              : '"' ~[<"]* '"' | '\'' ~[<']* '\'';
UNTERMINATED_STRING : '"' ~[<"]* | '\'' ~[<']*;
Name          : NameStartChar NameChar*;
S             : [ \t\r\n]+;

mode DTD_MODE;

DTD_SUBSET_OPEN   : '[' -> pushMode(DTD_SUBSET);
DTD_CLOSE         : '>' -> popMode;
DTD_STRING        : '"' ~["]* '"' | '\'' ~[']* '\'';
DTD_UNTERM_STRING : '"' ~["]* | '\'' ~[']*;
DTD_WS            : [ \t\r\n\uFEFF]+;
DTD_CONTENT       : ~[[\]>"'\t\r\n \uFEFF]+;
DTD_FALLBACK      : ']';

mode DTD_SUBSET;

DTD_SUBSET_COMMENT        : '<!--' ( ~'-' | '-' ~'-' | '-' '-'+ ~[->] )* '-'* '-->';
DTD_SUBSET_UNTERM_COMMENT : '<!--' ( ~'-' | '-' ~'-' | '-' '-'+ ~[->] )* '-'*;
DTD_SUBSET_PI             : '<?' ( ~'?' | '?'+ ~[?>] )* '?'* '?>';
DTD_SUBSET_UNTERM_PI      : '<?' ( ~'?' | '?'+ ~[?>] )* '?'*;
DTD_SUBSET_DECL_OPEN      : '<!' [A-Za-z]+ -> pushMode(DTD_DECL);
DTD_SUBSET_CLOSE          : ']' -> popMode;
DTD_SUBSET_WS             : [ \t\r\n\uFEFF]+;
DTD_SUBSET_TEXT           : ~[<[\]\t\r\n \uFEFF]+;
DTD_SUBSET_FALLBACK       : '<' | '[';

mode DTD_DECL;

DTD_DECL_CLOSE         : '>' -> popMode;
DTD_DECL_STRING        : '"' ~["]* '"' | '\'' ~[']* '\'';
DTD_DECL_UNTERM_STRING : '"' ~["]* | '\'' ~[']*;
DTD_DECL_WS            : [ \t\r\n\uFEFF]+;
DTD_DECL_CONTENT       : ~[>"'\t\r\n \uFEFF]+;

fragment HEXDIGIT: [a-fA-F0-9];

fragment DIGIT: [0-9];

fragment NameChar:
    NameStartChar
    | '-'
    | '.'
    | DIGIT
    | '\u00B7'
    | '\u0300' ..'\u036F'
    | '\u203F' ..'\u2040'
;

fragment NameStartChar:
    [_:a-zA-Z]
    | '\u00C0' ..'\u00D6'
    | '\u00D8' ..'\u00F6'
    | '\u00F8' ..'\u02FF'
    | '\u0370' ..'\u037D'
    | '\u037F' ..'\u1FFF'
    | '\u200C' ..'\u200D'
    | '\u2070' ..'\u218F'
    | '\u2C00' ..'\u2FEF'
    | '\u3001' ..'\uD7FF'
    | '\uF900' ..'\uFDCF'
    | '\uFDF0' ..'\uFFFD'
    | '\u{10000}' ..'\u{EFFFF}'
;
