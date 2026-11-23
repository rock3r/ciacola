package dev.sebastiano.ciacola.syntax.antlr.html

import org.antlr.v4.kotlinruntime.CharStream
import org.antlr.v4.kotlinruntime.IntStream
import org.antlr.v4.kotlinruntime.Lexer

/**
 * Pure Kotlin Multiplatform base lexer for HTML5, tracking `<script>` and `<style>` opening tags
 * to switch into raw text modes until the matching closing tag.
 */
internal abstract class HtmlLexerBase(input: CharStream) : Lexer(input) {
    protected enum class RawTagKind {
        NONE,
        SCRIPT,
        STYLE,
    }

    private var atTagStart: Boolean = false
    private var pendingRawTag: RawTagKind = RawTagKind.NONE

    override fun reset() {
        super.reset()
        atTagStart = false
        pendingRawTag = RawTagKind.NONE
    }

    protected fun onOpenAngle() {
        atTagStart = true
        pendingRawTag = RawTagKind.NONE
    }

    protected fun onNonElementOpen() {
        atTagStart = false
        pendingRawTag = RawTagKind.NONE
    }

    protected fun onSlashInTag() {
        atTagStart = false
        pendingRawTag = RawTagKind.NONE
    }

    protected fun onTagName(name: String) {
        if (!atTagStart) return
        atTagStart = false
        pendingRawTag = when {
            name.equals("script", ignoreCase = true) -> RawTagKind.SCRIPT
            name.equals("style", ignoreCase = true) -> RawTagKind.STYLE
            else -> RawTagKind.NONE
        }
    }

    protected fun onSelfOrSpecialClose() {
        atTagStart = false
        pendingRawTag = RawTagKind.NONE
        popMode()
    }

    protected fun onTagClose(rawScriptMode: Int, rawStyleMode: Int) {
        atTagStart = false
        val rawTag = pendingRawTag
        pendingRawTag = RawTagKind.NONE
        popMode()
        when (rawTag) {
            RawTagKind.SCRIPT -> pushMode(rawScriptMode)
            RawTagKind.STYLE -> pushMode(rawStyleMode)
            RawTagKind.NONE -> Unit
        }
    }

    protected fun onAttrCloseTag(rawScriptMode: Int, rawStyleMode: Int) {
        popMode()
        onTagClose(rawScriptMode, rawStyleMode)
    }

    protected fun isDoctypeAheadAfterBang(): Boolean {
        val word = "doctype"
        for (i in word.indices) {
            val ch = _input.LA(1 + i)
            if (ch == IntStream.EOF) return false
            val lower = if (ch in 'A'.code..'Z'.code) ch + 32 else ch
            if (lower != word[i].code) return false
        }
        return true
    }

    protected fun isClosingTagAheadAfterAngle(tagName: String): Boolean {
        if (_input.LA(1) != '/'.code) return false
        for (i in tagName.indices) {
            val ch = _input.LA(2 + i)
            if (ch == IntStream.EOF) return false
            val expected = tagName[i].code
            val lower = if (ch in 'A'.code..'Z'.code) ch + 32 else ch
            if (lower != expected) return false
        }
        val after = _input.LA(2 + tagName.length)
        return after == ' '.code ||
            after == '\t'.code ||
            after == '\r'.code ||
            after == '\n'.code ||
            after == '>'.code ||
            after == '/'.code ||
            after == IntStream.EOF
    }
}
