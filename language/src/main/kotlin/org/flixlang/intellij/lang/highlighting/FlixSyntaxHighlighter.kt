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
            name in CONTROL_FLOW_KEYWORDS -> CONTROL_FLOW_KEYWORD
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

        /**
         * The keywords that direct where execution goes next.
         *
         * Checked before the general `_KW` rule, so these get their own key while everything else
         * stays a plain keyword.
         *
         * Two deliberate omissions, both because a lexer cannot tell the cases apart and colouring
         * them here would be confidently wrong:
         *
         * - `CASE_KW` introduces a `match` arm (`Flix.bnf:825`), a `catch` arm (`:882`) and a
         *   `select` arm (`:933`) — but also an `enum` case (`:449`), which is a declaration and
         *   not control flow at all. One token, and only the PSI knows which.
         * - `SELECT_KW` is the channel select, and also the projection in `query … select`, which
         *   is a query clause rather than a branch.
         *
         * `IF_KW` is in the set even though it has four grammatical roles of its own, because all
         * four are conditional: a conditional expression (`:790`), a match guard (`:825`), a
         * comprehension guard (`:845`) and a Datalog constraint (`:1024`). Telling *those* apart is
         * a different job from telling control flow from declarations, and it needs the PSI too.
         */
        private val CONTROL_FLOW_KEYWORDS = setOf(
            "IF_KW", "ELSE_KW",
            "MATCH_KW", "EMATCH_KW", "CHOOSE_KW",
            "TRY_KW", "CATCH_KW", "THROW_KW",
            "FOREACH_KW", "YIELD_KW",
        )

        val KEYWORD: TextAttributesKey =
            TextAttributesKey.createTextAttributesKey("FLIX_KEYWORD", DefaultLanguageHighlighterColors.KEYWORD)

        /**
         * Control flow, falling back to [KEYWORD].
         *
         * The fallback is the feature's off switch, and it is off by default on purpose. With no
         * attributes of its own the key renders exactly as a keyword — identical to what a user
         * with a correctly working highlighter would already see — while still appearing in the
         * colour scheme for anyone who wants `if` to read differently from `def`. Shipping a
         * distinct colour by default would be a change of appearance nobody asked for; shipping the
         * key costs nothing and makes the choice available.
         */
        val CONTROL_FLOW_KEYWORD: TextAttributesKey =
            TextAttributesKey.createTextAttributesKey("FLIX_CONTROL_FLOW_KEYWORD", KEYWORD)

        /**
         * A guard: `case p if e =>`, or the `if e` of a comprehension.
         *
         * Assigned by [FlixControlFlowAnnotator], not here, because the token is the same `if` a
         * conditional uses and only the parser knows which is which. Falls back to
         * [CONTROL_FLOW_KEYWORD], so it reads as control flow until someone separates them.
         */
        val GUARD_KEYWORD: TextAttributesKey =
            TextAttributesKey.createTextAttributesKey("FLIX_GUARD_KEYWORD", CONTROL_FLOW_KEYWORD)

        /**
         * The `if` of a Datalog constraint, which is not a conditional at all.
         *
         * It filters the solutions of a rule rather than choosing a branch, and it is spelled
         * identically to one — keyword, parentheses, boolean expression. This is the distinction
         * the annotator exists for; see [FlixControlFlowAnnotator].
         */
        val DATALOG_GUARD_KEYWORD: TextAttributesKey =
            TextAttributesKey.createTextAttributesKey("FLIX_DATALOG_GUARD_KEYWORD", CONTROL_FLOW_KEYWORD)

        /**
         * The parentheses around a condition, which the grammar requires rather than the author
         * choosing them.
         *
         * These are what separate the condition from everything else on a line like
         * `if (m2 > m1) (h2 - h1, m2 - m1) else (…)`, where three parenthesised groups compete for
         * attention and only one is a condition. [FlixControlFlowAnnotator] emphasises them in
         * bold by default so that one stands out.
         *
         * Falls back to the ordinary parenthesis colour, so the emphasis is weight alone. Setting
         * this key to a dimmer foreground is the opposite treatment of the same idea — push the
         * required syntax back instead of pulling it forward — and works just as well; an explicit
         * value here replaces the default emphasis entirely.
         */
        val CONDITION_PARENTHESES: TextAttributesKey =
            TextAttributesKey.createTextAttributesKey(
                "FLIX_CONDITION_PARENTHESES",
                DefaultLanguageHighlighterColors.PARENTHESES,
            )

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
