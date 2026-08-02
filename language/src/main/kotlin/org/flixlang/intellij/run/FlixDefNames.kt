package org.flixlang.intellij.run

import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import org.flixlang.intellij.lang.psi.FlixDefDecl
import org.flixlang.intellij.lang.psi.FlixIdent
import org.flixlang.intellij.lang.psi.FlixModuleDecl

/**
 * `ident` is a real (non-`private`) rule in Flix.bnf, so Grammar-Kit generates a `getIdent()`
 * accessor -- used to be a raw leaf token hunt here before `ident` gained its own PSI node.
 *
 * Read via [PsiTreeUtil.getChildOfType] rather than the generated `getIdent()` directly:
 * Grammar-Kit marks it `@NotNull` because the grammar requires an `ident` in this position, but
 * the generated implementation (`findNotNullChildByClass`) asserts that at runtime rather than
 * guaranteeing it structurally, and a `def` with no name yet -- mid-typing, or genuinely malformed
 * input -- has no such child at all. [PsiTreeUtil.getChildOfType] returns `null` instead of
 * asserting, matching how every caller here already expects a name to be optionally absent.
 */
fun FlixDefDecl.namePsiOrNull(): PsiElement? = PsiTreeUtil.getChildOfType(this, FlixIdent::class.java)

fun FlixDefDecl.nameOrNull(): String? = namePsiOrNull()?.text

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
