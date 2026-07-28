package org.flixlang.intellij.run

import com.intellij.lang.ASTNode
import com.intellij.psi.PsiElement
import com.intellij.psi.TokenType
import org.flixlang.intellij.lang.psi.FlixDefDecl
import org.flixlang.intellij.lang.psi.FlixModuleDecl
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

/**
 * The symbol to pass to the compiler's `--entrypoint`, or `null` if this declaration has no name.
 *
 * `--entrypoint` parses with `Symbol.mkDefnSym`, which splits at the **last** dot: everything before
 * it is the namespace, the rest is the name. So a nested module has to be emitted whole --
 * `Foo.Bar.demo`, not `Bar.demo` -- and a declaration outside any module is just its own name.
 *
 * Modules nest, and the enclosing chain is walked outwards and then reversed, because a
 * `mod A { mod B { def f } }` must produce `A.B.f` rather than `B.A.f`. A module's own name comes
 * from its qualified-name node, which is already dotted for the `mod A.B { … }` spelling, so both
 * spellings converge on the same symbol.
 */
fun FlixDefDecl.entryPointSymbolOrNull(): String? {
    val name = nameOrNull() ?: return null
    val namespace = generateSequence(parent) { it.parent }
        .filterIsInstance<FlixModuleDecl>()
        .mapNotNull { module -> module.qualifiedName?.text?.trim()?.takeIf(String::isNotEmpty) }
        .toList()
        .asReversed()
    return (namespace + name).joinToString(".")
}
