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
import org.flixlang.intellij.lang.psi.FlixTypes

/**
 * Buckets tokens by the name Grammar-Kit gave the constant — `DEF_KW`, `COMMENT_LINE` — rather than
 * by an exhaustive enumeration of [FlixTypes] members, since the token set mirrors flix/flix's
 * ~190-entry `TokenKind` enum and is expected to grow as the compiler's lexer does.
 *
 * ## Why the names are read reflectively rather than from `toString()`
 *
 * Because `toString()` is not the constant's name, and reading it as though it were left this class
 * colouring **nothing at all**.
 *
 * Grammar-Kit builds each token from the *display* text in the grammar, not from the rule name:
 * `Flix.bnf` declares `DEF_KW='def'`, and the generated line is
 * `IElementType DEF_KW = new FlixTokenType("def")`. `FlixTokenType.toString()` then prefixes its
 * class, so the string this used to test was `"FlixTokenType.def"` — which does not end in `_KW`,
 * does not start with `COMMENT_`, and does not start with `LITERAL_`. Every token fell through to
 * the empty array.
 *
 * That was invisible for as long as it lasted, and the reason is worth recording: LSP4IJ's semantic
 * tokens paint over the same file, so a `.flix` buffer looked correctly coloured in any IDE with
 * the `backend` module loaded. The case this module exists for — an IDE without LSP4IJ, or a file
 * open before the server has answered — is exactly the case that had no colour.
 *
 * The field names *are* `DEF_KW`, `COMMENT_LINE`, `LITERAL_STRING`. Reading them off the interface
 * once keeps the prefix rules the original design chose, and keeps them true.
 */
class FlixSyntaxHighlighter : SyntaxHighlighterBase() {
    override fun getHighlightingLexer(): Lexer = FlexAdapter(_FlixLexer(null))

    override fun getTokenHighlights(tokenType: IElementType): Array<TextAttributesKey> {
        if (tokenType == TokenType.BAD_CHARACTER) {
            return arrayOf(BAD_CHARACTER)
        }
        val name = TOKEN_NAMES[tokenType] ?: return EMPTY_KEYS
        val key = when {
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

        /**
         * Every token constant [FlixTypes] declares, keyed by the name of the field holding it.
         *
         * Built once. The map is the fix described in the class docs: it is the only place the
         * constant's *name* survives, since the [IElementType] itself carries only its display
         * text.
         */
        private val TOKEN_NAMES: Map<IElementType, String> =
            FlixTypes::class.java.fields
                .filter { IElementType::class.java.isAssignableFrom(it.type) }
                .associate { (it.get(null) as IElementType) to it.name }

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
