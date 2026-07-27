package org.flixlang.intellij.lang.highlighting

import com.intellij.lexer.FlexAdapter
import com.intellij.lexer.Lexer
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.HighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import org.flixlang.intellij.lang._FlixLexer

/**
 * Buckets tokens by their generated debug name (e.g. "DEF_KW", "COMMENT_LINE") rather than
 * an exhaustive enumeration of FlixTypes constants, since the token set mirrors flix/flix's
 * ~190-entry TokenKind enum and is expected to grow as the compiler's lexer does.
 */
class FlixSyntaxHighlighter : SyntaxHighlighterBase() {
    override fun getHighlightingLexer(): Lexer = FlexAdapter(_FlixLexer(null))

    override fun getTokenHighlights(tokenType: IElementType): Array<TextAttributesKey> {
        val name = tokenType.toString()
        val key = when {
            tokenType == TokenType.BAD_CHARACTER -> BAD_CHARACTER
            name.startsWith("COMMENT_") -> COMMENT
            name == "LITERAL_STRING" || name == "LITERAL_CHAR" || name == "LITERAL_REGEX" ||
                name.startsWith("LITERAL_STRING_INTERPOLATION") -> STRING
            name.startsWith("LITERAL_") -> NUMBER
            name.endsWith("_KW") -> KEYWORD
            name == "ANNOTATION" -> ANNOTATION
            else -> return EMPTY_KEYS
        }
        return arrayOf(key)
    }

    companion object {
        val KEYWORD: TextAttributesKey =
            TextAttributesKey.createTextAttributesKey("FLIX_KEYWORD", DefaultLanguageHighlighterColors.KEYWORD)
        val STRING: TextAttributesKey =
            TextAttributesKey.createTextAttributesKey("FLIX_STRING", DefaultLanguageHighlighterColors.STRING)
        val NUMBER: TextAttributesKey =
            TextAttributesKey.createTextAttributesKey("FLIX_NUMBER", DefaultLanguageHighlighterColors.NUMBER)
        val COMMENT: TextAttributesKey =
            TextAttributesKey.createTextAttributesKey("FLIX_COMMENT", DefaultLanguageHighlighterColors.LINE_COMMENT)
        val ANNOTATION: TextAttributesKey =
            TextAttributesKey.createTextAttributesKey("FLIX_ANNOTATION", DefaultLanguageHighlighterColors.METADATA)
        val BAD_CHARACTER: TextAttributesKey =
            TextAttributesKey.createTextAttributesKey("FLIX_BAD_CHARACTER", HighlighterColors.BAD_CHARACTER)

        private val EMPTY_KEYS = emptyArray<TextAttributesKey>()
    }
}
