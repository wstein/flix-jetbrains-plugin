package org.flixlang.intellij.run

import com.intellij.lang.ASTNode
import com.intellij.psi.PsiElement
import com.intellij.psi.TokenType
import org.flixlang.intellij.lang.psi.FlixDefDecl
import org.flixlang.intellij.lang.psi.FlixTypes

/**
 * A defDecl's name comes from Flix.bnf's `private ident ::= NAME_LOWERCASE | ...` -- a private
 * rule Grammar-Kit inlines rather than wrapping in its own PSI node, so there is no generated
 * `getIdent()`/`getName()` accessor: the name is just a raw leaf token following `DEF_KW`, with a
 * whitespace node in between that must be skipped.
 */
fun FlixDefDecl.nameLeafOrNull(): ASTNode? {
    val defKw = node.findChildByType(FlixTypes.DEF_KW) ?: return null
    var sibling = defKw.treeNext
    while (sibling != null && sibling.elementType == TokenType.WHITE_SPACE) {
        sibling = sibling.treeNext
    }
    return sibling
}

fun FlixDefDecl.nameOrNull(): String? = nameLeafOrNull()?.text

fun FlixDefDecl.namePsiOrNull(): PsiElement? = nameLeafOrNull()?.psi
