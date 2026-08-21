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
            // Before the general comment rule, which would otherwise claim it: `///` is Flix's doc
            // comment and the lexer already tells it apart from `//`.
            name == "COMMENT_DOC" -> DOC_COMMENT
            name.startsWith("COMMENT_") -> COMMENT
            name == "LITERAL_STRING" || name == "LITERAL_CHAR" || name == "LITERAL_REGEX" ||
                name.startsWith("LITERAL_STRING_INTERPOLATION") -> STRING
            name.startsWith("LITERAL_") -> NUMBER
            // Order-free, unlike the rules around it: no operator's constant name ends in `_KW` or
            // begins with `COMMENT_`/`LITERAL_`, so nothing can shadow it and it shadows nothing.
            name in OPERATORS -> OPERATOR
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

        /**
         * The symbolic operators, by the names the grammar's own "Operators" section gives them,
         * plus the catch-all every user-defined one lexes as.
         *
         * A spelling, not a role -- which is precisely the division of labour between this layer
         * and the server's. `+` is a call to `Add.add` and `|>` is a call to `pub def |>`, and the
         * server says so; what the lexer can see is that both are written as symbols rather than as
         * words, and that is worth a colour of its own because nothing else in the file supplies
         * one until the server answers.
         *
         * `GENERIC_OPERATOR` is the important entry: every operator Flix does not reserve a token
         * for -- `|>`, `>>`, `<*>`, `|+|`, and anything a user defines -- arrives under it
         * (`_Flix.flex:112, :453`). Without it the operators a Flix program actually reads by are
         * the only ones left uncoloured.
         *
         * `NAME_MATH` is deliberately absent. A math identifier (`⊗`) is a *name*, so it is bound
         * and used like one -- `let ⊗ = …` reaches [FlixSemanticFallbackAnnotator] as a variable
         * pattern and is coloured as the local it is. Colouring it here would overrule that with a
         * claim about how it is spelled.
         */
        private val OPERATORS = setOf(
            "BANG", "BANG_EQUAL", "AMPERSAND", "STAR", "PLUS", "MINUS",
            "COLON", "COLON_MINUS", "COLON_COLON", "COLON_COLON_COLON",
            "ANGLE_L", "ANGLE_R", "ANGLE_L_EQUAL", "ANGLE_R_EQUAL",
            "ANGLED_PLUS", "ANGLED_EQUAL", "EQUAL", "EQUAL_EQUAL",
            "ARROW_THIN_L", "ARROW_THIN_R", "ARROW_THIN_R_TIGHT", "ARROW_THICK_R",
            "CARET", "BAR", "SLASH", "TILDE", "BACKSLASH",
            "GENERIC_OPERATOR",
        )

        /**
         * An operator, by its spelling.
         *
         * The layer that paints this one is the lexer, so what it can answer is "this is written as
         * a symbol". The server answers a different question -- what the symbol *resolves to* -- and
         * repaints the ones that are calls: `+` and `<=` come back as `Method`, `|>` as an operator
         * or a function depending on the compiler. Where the server has no opinion at all (`::`,
         * `->`, `=>`, the `~`/`&`/`+` of an effect set) this is the only colour there is, which is
         * the gap it was added to close.
         */
        val OPERATOR: TextAttributesKey =
            TextAttributesKey.createTextAttributesKey(
                "FLIX_OPERATOR",
                DefaultLanguageHighlighterColors.OPERATION_SIGN,
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
         * [CONTROL_FLOW_KEYWORD], so it keeps the control-flow colour until someone separates
         * them; the annotator adds bold italic on top without touching that colour.
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
         * The whole condition, as one span.
         *
         * Parentheses included where the grammar has them — `ifExpr` and the Datalog `guard` — and
         * the guard expression alone where it does not, since a match guard and a comprehension
         * guard take none (`Flix.bnf:825`, `:845`). The span is what separates a condition from
         * everything else on a line like `if (m2 > m1) (h2 - h1, m2 - m1) else (…)`, where three
         * parenthesised groups compete and only one is a condition.
         *
         * **No fallback, deliberately.** A condition is many tokens, each already coloured by this
         * highlighter, so this key must not impose a colour of its own on any of them.
         * [FlixControlFlowAnnotator] draws the span in bold italic and leaves every colour
         * untouched, which needs no fallback to inherit from. A value set here in the colour scheme
         * replaces that default outright.
         */
        val CONDITION: TextAttributesKey =
            TextAttributesKey.createTextAttributesKey("FLIX_CONDITION")

        /**
         * The roles the parser can name, assigned by [FlixSemanticFallbackAnnotator].
         *
         * Each carries the platform attribute LSP4IJ maps the server's own answer to, so the moment
         * the semantic tokens arrive nothing changes colour. Measured against
         * `SemanticTokensProvider` rather than chosen; the table is in the annotator's docs.
         *
         * They are keys of this plugin's rather than LSP4IJ's `LSP_*` because they must work in an
         * IDE with no LSP4IJ at all, and because a Flix reader who wants parameters to stand out
         * should find that setting under Flix.
         */
        val FUNCTION_NAME: TextAttributesKey =
            TextAttributesKey.createTextAttributesKey("FLIX_FUNCTION_NAME", DefaultLanguageHighlighterColors.FUNCTION_CALL)
        val PARAMETER: TextAttributesKey =
            TextAttributesKey.createTextAttributesKey("FLIX_PARAMETER", DefaultLanguageHighlighterColors.PARAMETER)
        val TYPE_PARAMETER: TextAttributesKey =
            TextAttributesKey.createTextAttributesKey("FLIX_TYPE_PARAMETER", DefaultLanguageHighlighterColors.PARAMETER)
        val LOCAL_VARIABLE: TextAttributesKey =
            TextAttributesKey.createTextAttributesKey(
                "FLIX_LOCAL_VARIABLE",
                DefaultLanguageHighlighterColors.REASSIGNED_LOCAL_VARIABLE,
            )
        val TYPE_NAME: TextAttributesKey =
            TextAttributesKey.createTextAttributesKey("FLIX_TYPE_NAME", DefaultLanguageHighlighterColors.CLASS_NAME)
        val ENUM_CASE: TextAttributesKey =
            TextAttributesKey.createTextAttributesKey("FLIX_ENUM_CASE", DefaultLanguageHighlighterColors.STATIC_FIELD)
        val FIELD_NAME: TextAttributesKey =
            TextAttributesKey.createTextAttributesKey("FLIX_FIELD_NAME", DefaultLanguageHighlighterColors.INSTANCE_FIELD)
        val TRAIT_NAME: TextAttributesKey =
            TextAttributesKey.createTextAttributesKey("FLIX_TRAIT_NAME", DefaultLanguageHighlighterColors.INTERFACE_NAME)

        /**
         * An effect's name.
         *
         * The one role with no counterpart in the semantic-token standard: `Effect` is a token type
         * Flix invented, and LSP4IJ's default colours provider answers `null` for anything outside
         * the standard set -- so an effect name is uncoloured in the server's layer as well as this
         * one. Given the colour of a type here, which is what an effect is.
         */
        val EFFECT_NAME: TextAttributesKey =
            TextAttributesKey.createTextAttributesKey("FLIX_EFFECT_NAME", DefaultLanguageHighlighterColors.CLASS_NAME)

        val STRING: TextAttributesKey =
            TextAttributesKey.createTextAttributesKey("FLIX_STRING", DefaultLanguageHighlighterColors.STRING)
        val NUMBER: TextAttributesKey =
            TextAttributesKey.createTextAttributesKey("FLIX_NUMBER", DefaultLanguageHighlighterColors.NUMBER)
        val COMMENT: TextAttributesKey =
            TextAttributesKey.createTextAttributesKey("FLIX_COMMENT", DefaultLanguageHighlighterColors.LINE_COMMENT)

        /**
         * A `///` comment, which documents the declaration below it rather than annotating a line.
         *
         * Falls back to the scheme's own doc-comment colour, so it reads like Javadoc or KDoc does
         * in the same theme -- the difference a reader is looking for is documentation against
         * remark, and every language in the IDE draws that difference the same way.
         */
        val DOC_COMMENT: TextAttributesKey =
            TextAttributesKey.createTextAttributesKey("FLIX_DOC_COMMENT", DefaultLanguageHighlighterColors.DOC_COMMENT)
        val ANNOTATION: TextAttributesKey =
            TextAttributesKey.createTextAttributesKey("FLIX_ANNOTATION", DefaultLanguageHighlighterColors.METADATA)
        val BAD_CHARACTER: TextAttributesKey =
            TextAttributesKey.createTextAttributesKey("FLIX_BAD_CHARACTER", HighlighterColors.BAD_CHARACTER)

        private val EMPTY_KEYS = emptyArray<TextAttributesKey>()
    }
}
