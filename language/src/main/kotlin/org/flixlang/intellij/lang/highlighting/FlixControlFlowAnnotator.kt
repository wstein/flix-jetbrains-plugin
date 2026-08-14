package org.flixlang.intellij.lang.highlighting

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.tree.IElementType
import com.intellij.ui.ColorUtil
import java.awt.Font
import org.flixlang.intellij.lang.psi.FlixGuard
import org.flixlang.intellij.lang.psi.FlixGuardFragment
import org.flixlang.intellij.lang.psi.FlixIfExpr
import org.flixlang.intellij.lang.psi.FlixMatchRule
import org.flixlang.intellij.lang.psi.FlixTypes

/**
 * Tells the four `if`s apart.
 *
 * ## Why this cannot be done in the highlighter
 *
 * `IF_KW` is one token playing four grammatical roles, and only one of them is a conditional:
 *
 * | Production | Shape | Meaning |
 * | --- | --- | --- |
 * | `ifExpr` (`Flix.bnf:790`) | `if ( expr ) expr [else expr]` | choose a branch |
 * | `matchRule` (`:825`) | `case pat if expr =>` | filter a match arm |
 * | `guardFragment` (`:845`) | `if expr` | filter a comprehension |
 * | `guard` (`:1024`) | `if ( expr )` | constrain a Datalog rule |
 *
 * The last is the one that matters. A Datalog constraint is spelled exactly like a conditional --
 * keyword, parentheses, boolean expression -- and is not one: it filters the solutions of a rule
 * rather than choosing what to evaluate next. [FlixSyntaxHighlighter] sees a token and cannot tell
 * these apart; the parser already has.
 *
 * ## What it does and does not colour
 *
 * The keyword, and the **whole condition** as one span: parentheses included where the grammar has
 * them, and the guard expression alone where it does not, since a match guard and a comprehension
 * guard take none.
 *
 * `Main.flix:88` is the case that prompted this:
 * `if (m2 > m1) (h2 - h1, m2 - m1) else ((h2 - h1) - 1, (60 + m2) - m1)` -- three parenthesised
 * groups on one line, of which exactly one is a condition. Banding that one says which.
 *
 * Not the branches. A branch is an arbitrary expression that can be pages long and can nest, so
 * banding one turns into a wash across most of a file rather than a cue -- and a nested `if` would
 * sit inside its own parent's band.
 *
 * ## Two different defaults, for two different shapes
 *
 * A **keyword** is one token, so it is emphasised in bold. Bold is the one emphasis that survives
 * every theme: a colour that reads well in Darcula can vanish in the high-contrast scheme, and
 * shipping per-theme attribute files means shipping contrast nobody has looked at.
 *
 * A **span** covers many tokens that are already coloured, so it gets a background band and
 * nothing else. Bolding a whole condition would be heavy, and any foreground would flatten the
 * string, number and operator inside it to one colour. The band's colour is mixed from the active
 * scheme's own default background and foreground, so it is subtle in a light scheme and subtle in
 * a dark one with no file per theme -- and it still works in a scheme nobody has written yet.
 *
 * Both are applied here rather than through `additionalTextAttributes`, and that is not a style
 * preference. A scheme entry *replaces* a key's attributes instead of adding to them, so the
 * fallback stops applying and a guard keyword would lose its inherited colour -- bold, in the
 * colour of ordinary text.
 *
 * A user who gives one of these keys attributes of their own gets exactly those: an explicit choice
 * in the colour scheme outranks the default, which is what makes it a default rather than a decree.
 * The one cost is that the Color Settings preview cannot show a default nothing has stored -- see
 * `docs/syntax-highlighting.md`.
 *
 * ## Incomplete code
 *
 * Every range is read from a child node that may be absent while someone is typing, and an absent
 * child is skipped rather than guessed at. That is the whole of the error handling and it is
 * enough: an annotator runs on every keystroke, so half-written code is its normal input, not an
 * edge case.
 */
class FlixControlFlowAnnotator : Annotator {

    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        rolesOf(element).forEach { (range, key) -> apply(range, key, holder) }
    }

    /** One range and the key it should be drawn with. */
    internal data class Role(val range: TextRange, val key: TextAttributesKey)

    /**
     * What [element] contributes, or nothing if it is not one of the four productions.
     *
     * Separated from [annotate] so the decision -- which is the whole of this class -- can be
     * tested against real parsed PSI without standing up an [AnnotationHolder]. The rendering half
     * has its own test.
     */
    internal fun rolesOf(element: PsiElement): List<Role> = when (element) {
        // A conditional expression: the keyword, `else`, and the parenthesised condition.
        is FlixIfExpr -> listOfNotNull(
            role(element, FlixTypes.IF_KW, FlixSyntaxHighlighter.CONTROL_FLOW_KEYWORD),
            role(element, FlixTypes.ELSE_KW, FlixSyntaxHighlighter.CONTROL_FLOW_KEYWORD),
            parenthesisedCondition(element),
        )

        // `case pat if expr =>`. The grammar takes no parentheses here, so the condition is the
        // guard expression itself. `getExpr` is null when the arm has no guard at all.
        is FlixMatchRule -> listOfNotNull(
            role(element, FlixTypes.IF_KW, FlixSyntaxHighlighter.GUARD_KEYWORD),
            element.expr?.let { Role(it.textRange, FlixSyntaxHighlighter.CONDITION) },
        )

        // A `forA`/`forM` fragment. No parentheses here either.
        is FlixGuardFragment -> listOfNotNull(
            role(element, FlixTypes.IF_KW, FlixSyntaxHighlighter.GUARD_KEYWORD),
            Role(element.expr.textRange, FlixSyntaxHighlighter.CONDITION),
        )

        // A Datalog constraint. Parenthesised like a conditional, and not one.
        is FlixGuard -> listOfNotNull(
            role(element, FlixTypes.IF_KW, FlixSyntaxHighlighter.DATALOG_GUARD_KEYWORD),
            parenthesisedCondition(element),
        )

        else -> emptyList()
    }

    /**
     * The span from [element]'s own `(` through its own `)`, or `null` if either is missing.
     *
     * `findChildByType` looks only at direct children, and that is what makes this safe rather
     * than merely convenient: in `if (m2 > m1) (h2 - h1, m2 - m1) else (...)` the branches are
     * parenthesised too, but their parentheses belong to the branch expressions' own nodes. A
     * subtree search would take the first `(` and the last `)` and band the entire line.
     *
     * A missing `)` means the user is still typing, and the whole of the incomplete-code handling
     * is that the span is then not drawn at all rather than run to the end of the file.
     */
    private fun parenthesisedCondition(element: PsiElement): Role? {
        val open = element.node.findChildByType(FlixTypes.PAREN_L) ?: return null
        val close = element.node.findChildByType(FlixTypes.PAREN_R) ?: return null
        return Role(
            TextRange(open.textRange.startOffset, close.textRange.endOffset),
            FlixSyntaxHighlighter.CONDITION,
        )
    }

    /**
     * [element]'s own [token] child paired with [key], or `null` if it has no such child.
     */
    private fun role(element: PsiElement, token: IElementType, key: TextAttributesKey): Role? =
        element.node.findChildByType(token)?.let { Role(it.textRange, key) }

    /**
     * Draws [range] with [key], falling back to this class's own default styling.
     *
     * `getAttributes(key, false)` asks what the scheme states *for this key*, without walking the
     * fallback chain, so it distinguishes "the user set this" from "this inherits". Only the
     * second gets a default.
     */
    private fun apply(range: TextRange, key: TextAttributesKey, holder: AnnotationHolder) {
        val scheme = EditorColorsManager.getInstance().globalScheme
        val builder = holder.newSilentAnnotation(HighlightSeverity.INFORMATION).range(range)

        if (scheme.getAttributes(key, false) != null) {
            builder.textAttributes(key).create()
            return
        }
        val default = if (key == FlixSyntaxHighlighter.CONDITION) {
            band(scheme)
        } else {
            emphasised(scheme.getAttributes(key))
        }
        builder.enforcedTextAttributes(default).create()
    }

    internal companion object {

        /** How far the band is mixed from the editor's background towards its foreground. */
        private const val BAND_STRENGTH = 0.07

        /**
         * A subtle background for the condition span, and **nothing else**.
         *
         * Every other field is left null on purpose. A span covers many tokens, each already
         * coloured by [FlixSyntaxHighlighter], and the editor composes highlighter layers by
         * field: a null foreground here lets each token keep its own. Setting a foreground -- or
         * cloning some inherited attributes the way [emphasised] does -- would flatten a string,
         * a number and an operator inside the condition to one colour.
         *
         * The colour is mixed from the scheme's own defaults rather than shipped per theme, so it
         * is subtle in a light scheme and subtle in a dark one without a file per theme, and it
         * still works in a scheme nobody has written yet.
         */
        internal fun band(scheme: EditorColorsScheme): TextAttributes =
            TextAttributes().apply {
                backgroundColor = ColorUtil.mix(
                    scheme.defaultBackground,
                    scheme.defaultForeground,
                    BAND_STRENGTH,
                )
            }

        /**
         * [base] in bold, keeping every colour it already has.
         *
         * Bold rather than a colour because weight is orthogonal to the palette: it reads the same
         * in the light, dark and high-contrast schemes, where a chosen colour would have to be
         * checked against each. Used for the keywords, which are single tokens -- see [band] for
         * why a span cannot be styled this way.
         */
        internal fun emphasised(base: TextAttributes?): TextAttributes =
            (base?.clone() ?: TextAttributes()).apply { fontType = fontType or Font.BOLD }
    }
}
