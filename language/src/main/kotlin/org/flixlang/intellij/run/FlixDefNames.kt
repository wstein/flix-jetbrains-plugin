package org.flixlang.intellij.run

import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.PsiDocumentManager
import org.flixlang.intellij.lang.psi.FlixDeclaration
import org.flixlang.intellij.lang.psi.FlixDefDecl
import org.flixlang.intellij.lang.psi.FlixIdent
import org.flixlang.intellij.lang.psi.FlixModuleDecl
import org.flixlang.intellij.lang.FlixFile

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

/** The fully-qualified symbol accepted by `flix test --filter`, only for an actual `@Test` def. */
fun FlixDefDecl.testSymbolOrNull(): String? {
    val declaration = PsiTreeUtil.getParentOfType(this, FlixDeclaration::class.java, true) ?: return null
    val isTest = PsiTreeUtil.collectElements(declaration.annotationList) {
        it.firstChild == null && it.text == "@Test"
    }.isNotEmpty()
    return if (isTest) entryPointSymbolOrNull() else null
}

/** Every test declared in this file, encoded as one whole-name compiler regex. */
fun FlixFile.testPatternOrNull(): String? {
    return exactTestPattern(testSymbolsUnder(this))
}

/** Every test nested under this module, including tests in nested modules. */
fun FlixModuleDecl.testPatternOrNull(): String? = exactTestPattern(testSymbolsUnder(this))

private fun testSymbolsUnder(element: PsiElement): List<String> =
    PsiTreeUtil.findChildrenOfType(element, FlixDefDecl::class.java)
        .mapNotNull(FlixDefDecl::testSymbolOrNull)

/** One compiler regex whose alternatives each match one complete test symbol. */
fun exactTestPattern(symbols: Collection<String>): String? = symbols
    .filter(String::isNotBlank)
    .distinct()
    .sorted()
    .takeIf(List<String>::isNotEmpty)
    ?.joinToString(separator = "|", prefix = "(?:", postfix = ")") { Regex.escape(it) }

/** The same location URL emitted by the compiler test protocol for this definition. */
fun FlixDefDecl.testLocationUrlOrNull(): String? {
    if (testSymbolOrNull() == null) return null
    val file = containingFile ?: return null
    val virtualFile = file.virtualFile ?: return null
    val document = PsiDocumentManager.getInstance(project).getDocument(file) ?: return null
    val declaration = PsiTreeUtil.getParentOfType(this, FlixDeclaration::class.java, true) ?: return null
    val offset = declaration.textOffset.coerceAtLeast(0)
    val line = document.getLineNumber(offset)
    val column = offset - document.getLineStartOffset(line)
    return "file://${virtualFile.path}:${line + 1}:${column + 1}"
}
