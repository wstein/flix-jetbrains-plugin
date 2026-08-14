package org.flixlang.intellij.lang.highlighting

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.tree.IElementType
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
 * ## The default: bold italic, and every colour left alone
 *
 * One rule for all of it -- keywords and condition spans alike -- and it sets **only** the font
 * style. Every token underneath keeps the colour the syntax highlighter gave it, so a string, a
 * number, an operator and a name inside one condition each stay their own colour and merely gain
 * weight and slant. See [EMPHASIS] for the platform mechanism that makes this work, which is not
 * incidental: it is why the rule can be this simple.
 *
 * Style rather than colour, because weight and slant are orthogonal to the palette. A colour that
 * reads well in Darcula can vanish in the high-contrast scheme, and shipping per-theme attribute
 * files means shipping contrast nobody has looked at; one style rule reads correctly in all three.
 *
 * Applied here rather than through `additionalTextAttributes`, and that is not a style preference.
 * A scheme entry *replaces* a key's attributes instead of adding to them, so the fallback would
 * stop applying and a guard keyword would render bold italic in the colour of ordinary text --
 * losing the very thing this is careful to keep.
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
     * second gets the default.
     */
    private fun apply(range: TextRange, key: TextAttributesKey, holder: AnnotationHolder) {
        val scheme = EditorColorsManager.getInstance().globalScheme
        val builder = holder.newSilentAnnotation(HighlightSeverity.INFORMATION).range(range)

        if (scheme.getAttributes(key, false) != null) {
            builder.textAttributes(key).create()
            return
        }
        builder.enforcedTextAttributes(EMPHASIS).create()
    }

    internal companion object {

        /**
         * Bold italic, and **nothing else**.
         *
         * Every colour field is left null on purpose, and that is what lets one rule serve both a
         * single keyword and a span covering a whole condition. `TextAttributes.merge` composes
         * layered highlighters field by field:
         *
         * ```java
         * if (above.getForegroundColor() != null) attrs.setForegroundColor(...);
         * attrs.setFontType(above.getFontType() | under.getFontType());
         * ```
         *
         * Colours override only when set, and font types are **or-ed**. So a null foreground here
         * lets every token underneath keep the colour the syntax highlighter gave it -- the string,
         * the number, the operator and the name inside a condition each stay their own -- while the
         * weight and slant are added on top. Setting any colour, or cloning some inherited
         * attributes, would flatten the span to one colour.
         *
         * Style rather than colour for the same reason throughout: weight and slant are orthogonal
         * to the palette, so one rule reads correctly in the light, dark and high-contrast schemes,
         * where a chosen colour would have to be checked against each.
         */
        internal val EMPHASIS: TextAttributes =
            TextAttributes().apply { fontType = Font.BOLD or Font.ITALIC }
    }
}
