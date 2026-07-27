package org.flixlang.intellij.lang.editor

import com.intellij.lang.BracePair
import com.intellij.lang.PairedBraceMatcher
import com.intellij.psi.PsiFile
import com.intellij.psi.tree.IElementType
import org.flixlang.intellij.lang.psi.FlixTypes

/**
 * Pairs every delimiter-shaped token in the lexer, not just `(){}` []`: the fixpoint/schema
 * forms `#( ... )` and `#{ ... }` (HASH_PAREN_L/HASH_CURLY_L, see Flix.bnf's
 * predicateParamList/fixpointConstraintSetExpr/schemaType/schemaRowType) close with an ordinary
 * `)`/`}`, and the extensible-row type `#| ... |#` (HASH_BAR/BAR_HASH) is its own pair. The
 * `Array#`/`List#`/`Map#`/`Set#`/`Vector#` collection-literal prefixes are plain keyword-like
 * tokens followed by an ordinary `{`, so they need no entry of their own.
 */
class FlixBraceMatcher : PairedBraceMatcher {
    private val pairs = arrayOf(
        BracePair(FlixTypes.CURLY_L, FlixTypes.CURLY_R, true),
        BracePair(FlixTypes.PAREN_L, FlixTypes.PAREN_R, false),
        BracePair(FlixTypes.BRACKET_L, FlixTypes.BRACKET_R, false),
        BracePair(FlixTypes.HASH_PAREN_L, FlixTypes.PAREN_R, false),
        BracePair(FlixTypes.HASH_CURLY_L, FlixTypes.CURLY_R, false),
        BracePair(FlixTypes.HASH_BAR, FlixTypes.BAR_HASH, false),
    )

    override fun getPairs(): Array<BracePair> = pairs

    override fun isPairedBracesAllowedBeforeType(lbraceType: IElementType, contextType: IElementType?): Boolean = true

    override fun getCodeConstructStart(file: PsiFile, openingBraceOffset: Int): Int = openingBraceOffset
}
