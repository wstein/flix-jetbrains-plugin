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
 * The keyword, and -- for the two roles that have them -- the condition's own parentheses. Not the
 * branches. A branch is an arbitrary expression that can be pages long and can nest, so tinting one
 * turns into a wash across most of a file rather than a cue.
 *
 * The parentheses are worth their own key because in `ifExpr` they are *required by the grammar*
 * rather than chosen by the author, which makes them the one part of the construct that carries no
 * information. `Main.flix:88` is the case that prompted this:
 * `if (m2 > m1) (h2 - h1, m2 - m1) else ((h2 - h1) - 1, (60 + m2) - m1)` -- three parenthesised
 * groups on one line, of which exactly one is a condition. Giving that one its own key lets a
 * reader dim it and let the condition itself stand out; leaving it alone costs nothing.
 *
 * ## Why the default emphasis is bold rather than a colour
 *
 * Because bold is the one emphasis that survives every theme. A colour that reads well in Darcula
 * can vanish in the high-contrast scheme, and shipping per-theme attribute files means shipping
 * contrast nobody has looked at; weight is orthogonal to the palette, so one rule works in light,
 * dark and high contrast alike.
 *
 * It is applied here rather than through `additionalTextAttributes`, and that is not a style
 * preference. A scheme entry *replaces* a key's attributes instead of adding to them, so the
 * fallback stops applying and the guard would lose its inherited keyword colour — bold, and the
 * colour of ordinary text. Reading the resolved attributes and setting one bit keeps the colour
 * whatever the active scheme says it should be.
 *
 * A user who gives one of these keys attributes of their own gets exactly those, bold or not: an
 * explicit choice in the colour scheme outranks this default, which is what makes the emphasis a
 * default rather than a decree.
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
        // A conditional expression: the keyword, `else`, and the mandatory parentheses.
        is FlixIfExpr -> listOfNotNull(
            role(element, FlixTypes.IF_KW, FlixSyntaxHighlighter.CONTROL_FLOW_KEYWORD),
            role(element, FlixTypes.ELSE_KW, FlixSyntaxHighlighter.CONTROL_FLOW_KEYWORD),
            role(element, FlixTypes.PAREN_L, FlixSyntaxHighlighter.CONDITION_PARENTHESES),
            role(element, FlixTypes.PAREN_R, FlixSyntaxHighlighter.CONDITION_PARENTHESES),
        )

        // `case pat if expr =>`. No parentheses in this one -- the grammar does not take them.
        is FlixMatchRule ->
            listOfNotNull(role(element, FlixTypes.IF_KW, FlixSyntaxHighlighter.GUARD_KEYWORD))

        // A `forA`/`forM` fragment. No parentheses here either.
        is FlixGuardFragment ->
            listOfNotNull(role(element, FlixTypes.IF_KW, FlixSyntaxHighlighter.GUARD_KEYWORD))

        // A Datalog constraint. Parenthesised like a conditional, and not one.
        is FlixGuard -> listOfNotNull(
            role(element, FlixTypes.IF_KW, FlixSyntaxHighlighter.DATALOG_GUARD_KEYWORD),
            role(element, FlixTypes.PAREN_L, FlixSyntaxHighlighter.CONDITION_PARENTHESES),
            role(element, FlixTypes.PAREN_R, FlixSyntaxHighlighter.CONDITION_PARENTHESES),
        )

        else -> emptyList()
    }

    /**
     * [element]'s own [token] child paired with [key], or `null` if it has no such child.
     *
     * `findChildByType` looks only at direct children, which is what makes the parentheses safe to
     * ask for: a nested `(` inside the condition belongs to the condition's node, not to this one.
     * It is also the whole of the incomplete-code handling -- a `(` the user has not typed yet is
     * simply absent.
     */
    private fun role(element: PsiElement, token: IElementType, key: TextAttributesKey): Role? =
        element.node.findChildByType(token)?.let { Role(it.textRange, key) }

    /**
     * Draws [range] with [key], emphasising it unless the scheme has an opinion of its own.
     *
     * `getAttributes(key, false)` asks what the scheme states *for this key*, without walking the
     * fallback chain, so it distinguishes "the user set this" from "this inherits". Only the second
     * gets the default emphasis.
     */
    private fun apply(range: TextRange, key: TextAttributesKey, holder: AnnotationHolder) {
        val scheme = EditorColorsManager.getInstance().globalScheme
        val builder = holder.newSilentAnnotation(HighlightSeverity.INFORMATION).range(range)

        if (scheme.getAttributes(key, false) != null) {
            builder.textAttributes(key).create()
            return
        }
        builder.enforcedTextAttributes(emphasised(scheme.getAttributes(key))).create()
    }

    internal companion object {

        /**
         * [base] in bold, keeping every colour it already has.
         *
         * Bold rather than a colour because weight is orthogonal to the palette: it reads the same
         * in the light, dark and high-contrast schemes, where a chosen colour would have to be
         * checked against each.
         */
        internal fun emphasised(base: TextAttributes?): TextAttributes =
            (base?.clone() ?: TextAttributes()).apply { fontType = fontType or Font.BOLD }
    }
}
