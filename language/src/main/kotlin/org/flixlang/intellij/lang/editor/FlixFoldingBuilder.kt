package org.flixlang.intellij.lang.editor

import com.intellij.lang.ASTNode
import com.intellij.lang.folding.FoldingBuilderEx
import com.intellij.lang.folding.FoldingDescriptor
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiWhiteSpace
import org.flixlang.intellij.lang.psi.FlixTypes

/**
 * Folds two independent things:
 *  1. The `{ ... }` body of any node shaped like `<keyword/expr> { ... }` -- module/trait/
 *     instance/enum/struct/effect declarations, a generic `block` (covers `def` bodies and any
 *     other brace-delimited expression), and match/ematch expressions. All of these have exactly
 *     one CURLY_L/CURLY_R pair as *direct* children in the grammar (Flix.bnf), so a plain
 *     `findChildByType` lookup on each is unambiguous -- no need to special-case each
 *     declaration kind's PSI shape individually.
 *  2. Runs of comments: a multi-line block comment folds on its own, and consecutive line/doc
 *     comments on adjacent lines (no blank-line gap) fold together as one region, matching the
 *     usual "leading comment block" folding convention.
 */
class FlixFoldingBuilder : FoldingBuilderEx(), DumbAware {

    private val braceBodyElementTypes = setOf(
        FlixTypes.MODULE_DECL,
        FlixTypes.TRAIT_DECL,
        FlixTypes.INSTANCE_DECL,
        FlixTypes.RESTRICTABLE_ENUM_DECL,
        FlixTypes.PLAIN_ENUM_DECL,
        FlixTypes.STRUCT_DECL,
        FlixTypes.EFFECT_DECL,
        FlixTypes.BLOCK,
        FlixTypes.MATCH_OR_MATCH_LAMBDA_EXPR,
        FlixTypes.EXT_MATCH_OR_LAMBDA_EXPR,
    )

    override fun buildFoldRegions(root: PsiElement, document: Document, quick: Boolean): Array<FoldingDescriptor> {
        val descriptors = mutableListOf<FoldingDescriptor>()
        collectBraceFolds(root.node, document, descriptors)
        collectCommentFolds(root, descriptors)
        return descriptors.toTypedArray()
    }

    override fun isCollapsedByDefault(node: ASTNode): Boolean = false

    // Every FoldingDescriptor built above already carries its own placeholder text, so this
    // per-node fallback (used only if a descriptor's placeholder is left unset) is never
    // actually consulted -- still required because FoldingBuilder declares it abstract.
    override fun getPlaceholderText(node: ASTNode): String? =
        if (node.elementType in braceBodyElementTypes) "{...}" else null

    private fun collectBraceFolds(node: ASTNode, document: Document, out: MutableList<FoldingDescriptor>) {
        if (node.elementType in braceBodyElementTypes) {
            val open = node.findChildByType(FlixTypes.CURLY_L)
            val close = node.findChildByType(FlixTypes.CURLY_R)
            if (open != null && close != null) {
                val range = TextRange(open.startOffset, close.textRange.endOffset)
                if (!range.isEmpty && spansMultipleLines(range, document)) {
                    out.add(FoldingDescriptor(node, range, null, "{...}"))
                }
            }
        }
        var child = node.firstChildNode
        while (child != null) {
            collectBraceFolds(child, document, out)
            child = child.treeNext
        }
    }

    private fun spansMultipleLines(range: TextRange, document: Document): Boolean {
        if (range.endOffset > document.textLength || range.startOffset >= range.endOffset) return false
        return document.getLineNumber(range.endOffset - 1) > document.getLineNumber(range.startOffset)
    }

    private fun collectCommentFolds(root: PsiElement, out: MutableList<FoldingDescriptor>) {
        val comments = mutableListOf<PsiElement>()
        collectComments(root, comments)

        var i = 0
        while (i < comments.size) {
            val start = comments[i]
            if (start.node.elementType == FlixTypes.COMMENT_BLOCK) {
                if (start.text.contains('\n')) {
                    out.add(FoldingDescriptor(start.node, start.textRange, null, "/*...*/"))
                }
                i++
                continue
            }

            var j = i
            while (j + 1 < comments.size &&
                comments[j + 1].node.elementType != FlixTypes.COMMENT_BLOCK &&
                onAdjacentLines(comments[j], comments[j + 1])
            ) {
                j++
            }
            if (j > i) {
                val range = TextRange(start.textRange.startOffset, comments[j].textRange.endOffset)
                out.add(FoldingDescriptor(start.node, range, null, "//..."))
            }
            i = j + 1
        }
    }

    private fun collectComments(element: PsiElement, out: MutableList<PsiElement>) {
        if (element is PsiComment) {
            out.add(element)
            return
        }
        var child = element.firstChild
        while (child != null) {
            collectComments(child, out)
            child = child.nextSibling
        }
    }

    /** True if [a] and [b] are separated by nothing but a single-newline whitespace run, i.e.
     *  there's no blank line between them that would signal two separate comment groups. */
    private fun onAdjacentLines(a: PsiElement, b: PsiElement): Boolean {
        val between = a.nextSibling ?: return false
        if (between === b) return true
        if (between !is PsiWhiteSpace) return false
        if (between.nextSibling !== b) return false
        return between.text.count { it == '\n' } <= 1
    }
}
