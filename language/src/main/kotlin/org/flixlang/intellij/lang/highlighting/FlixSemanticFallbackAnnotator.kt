package org.flixlang.intellij.lang.highlighting

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.util.PsiTreeUtil
import org.flixlang.intellij.lang.psi.FlixDefDecl
import org.flixlang.intellij.lang.psi.FlixEffectDecl
import org.flixlang.intellij.lang.psi.FlixEffectOperation
import org.flixlang.intellij.lang.psi.FlixEnumCase
import org.flixlang.intellij.lang.FlixFile
import org.flixlang.intellij.lang.psi.FlixParameter
import org.flixlang.intellij.lang.psi.FlixUnaryLambdaExpr
import org.flixlang.intellij.lang.psi.FlixQualifiedName
import org.flixlang.intellij.lang.psi.FlixPostfixExpr
import org.flixlang.intellij.lang.psi.FlixParameterList
import org.flixlang.intellij.lang.psi.FlixArgumentList
import org.flixlang.intellij.lang.psi.FlixPlainEnumDecl
import org.flixlang.intellij.lang.psi.FlixRestrictableEnumDecl
import org.flixlang.intellij.lang.psi.FlixSignatureDecl
import org.flixlang.intellij.lang.psi.FlixStructDecl
import org.flixlang.intellij.lang.psi.FlixStructField
import org.flixlang.intellij.lang.psi.FlixTraitDecl
import org.flixlang.intellij.lang.psi.FlixTypeAliasDecl
import org.flixlang.intellij.lang.psi.FlixTypeParameter
import org.flixlang.intellij.lang.psi.FlixVariablePattern

/**
 * Colours the names a `.flix` file *declares*, before the language server has said anything.
 *
 * ## What it is for
 *
 * Two layers colour a Flix buffer: this module's lexical highlighter, and the server's semantic
 * tokens painted over it by LSP4IJ (`docs/syntax-highlighting.md`). The lexer can colour a keyword,
 * a literal and a comment, because those are token kinds. Everything a reader actually navigates by
 * -- which name is a function, which is a parameter, which is a type -- is a *role*, and a token
 * has no role. So until the server answers, every name in the file is the colour of plain text, and
 * in an IDE without LSP4IJ it stays that way.
 *
 * The parser already knows the roles this class assigns. It does not need resolution to know that
 * the name in `def greet(...)` is a function's, or that `who` in a parameter list is a parameter.
 *
 * ## Declarations only, deliberately
 *
 * Every rule here reads a name out of the production that *binds* it. Uses are left to the server:
 * `f(x)` is a function call, a constructor, an enum case or a local of function type depending on
 * what `f` resolves to, and a fallback that guessed would be confidently wrong in ordinary Flix
 * code -- which is worse than uncoloured, because a wrong colour is read as information.
 *
 * The one exception to "uses are the server's" is deliberate and small: nothing here is *replaced*
 * when the server answers, it is *repainted*, and the platform draws the last writer. So a
 * declaration this class colours keeps its colour only until the server confirms it.
 *
 * ## The colours are the server's own
 *
 * Measured, not chosen. `SemanticTokensProvider` was asked what it emits for a file containing one
 * of each declaration, and every key below carries the platform attribute LSP4IJ maps that answer
 * to (`SemanticTokensHighlightingColors`):
 *
 * | Declaration | Server token | Platform attribute |
 * | --- | --- | --- |
 * | `def` / signature / effect operation | `Function` | `FUNCTION_CALL` |
 * | parameter | `Parameter` | `PARAMETER` |
 * | `let` binding | `Variable` | `REASSIGNED_LOCAL_VARIABLE` |
 * | `enum` name | `Enum` | `CLASS_NAME` |
 * | enum case | `EnumMember` | `STATIC_FIELD` |
 * | `struct` name, `type alias` name | `Type` | `CLASS_NAME` |
 * | struct field | `Property` | `INSTANCE_FIELD` |
 * | `trait` name | `Interface` | `INTERFACE_NAME` |
 * | type parameter | `TypeParameter` | `PARAMETER` |
 *
 * That is the whole point of matching them: the handover from this layer to the server's is meant
 * to be invisible. A fallback that picked its own palette would make every file flicker through a
 * second colour scheme on open.
 *
 * One row cannot be invisible, and saying so is more useful than pretending otherwise. LSP4IJ ships
 * colour schemes of its own (`colorSchemes/SemanticTokens*.xml`), and `LSP_TYPE_PARAMETER` is the
 * single key in them given an explicit foreground -- teal in dark and high contrast, blue in light.
 * So a type parameter *does* change colour when the server answers, from the scheme's parameter
 * colour to that one. Matching it here would mean shipping a colour, which nothing else in this
 * plugin does; the alternative is to leave the row uncoloured, which is worse. Measured against
 * lsp4ij-0.20.1; every other key in those schemes is empty.
 *
 * `eff` is the one row with no platform attribute, because `Effect` is a semantic token type Flix
 * invented and LSP4IJ's default provider answers `null` for anything outside the standard set --
 * so an effect name is uncoloured in *both* layers today. It is coloured here as a type, which is
 * what it is, and [FlixSyntaxHighlighter.EFFECT_NAME] carries the same fallback so the two agree.
 *
 * ## Silent annotations
 *
 * `newSilentAnnotation(INFORMATION)` adds attributes and nothing else: no inspection, no tooltip,
 * no gutter, nothing in Problems. It is the platform's own mechanism for exactly this -- colour
 * that comes from the PSI rather than from the lexer -- and is what the Kotlin and Rust plugins use
 * for the same purpose.
 *
 * ## Incomplete code
 *
 * Every name is read from a child that may be absent mid-keystroke, and an absent child is skipped.
 * An annotator's normal input is half-written code.
 */
class FlixSemanticFallbackAnnotator : Annotator {

    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        val role = roleOf(element) ?: return
        holder.newSilentAnnotation(HighlightSeverity.INFORMATION)
            .range(role.range)
            .textAttributes(role.key)
            .create()
    }

    /** One range and the key it should be drawn with. */
    internal data class Role(val range: TextRange, val key: TextAttributesKey)

    /**
     * The role [element] declares, or `null` if it declares none.
     *
     * Separated from [annotate] so the decision can be tested against real parsed PSI without an
     * [AnnotationHolder], as [FlixControlFlowAnnotator] does.
     */
    internal fun roleOf(element: PsiElement): Role? = when (element) {
        // A definition's name. `Function` rather than `FunctionDeclaration`: the server emits
        // `Function` for the declaration too, and the fallback follows the server rather than the
        // convention of other languages.
        is FlixDefDecl -> named(element.ident, FlixSyntaxHighlighter.FUNCTION_NAME)
        is FlixSignatureDecl -> named(element.ident, FlixSyntaxHighlighter.FUNCTION_NAME)
        is FlixEffectOperation -> named(element.ident, FlixSyntaxHighlighter.FUNCTION_NAME)

        is FlixParameter -> named(element.ident, FlixSyntaxHighlighter.PARAMETER)
        is FlixTypeParameter -> named(element.ident, FlixSyntaxHighlighter.TYPE_PARAMETER)

        // Every binding occurrence of a name: what `let` binds, and what a `match` arm binds. Both
        // reach here as a variable pattern, which is why there is no rule for `let` itself -- and
        // why a destructuring pattern colours each of its names without any rule walking into it.
        is FlixVariablePattern -> Role(element.textRange, FlixSyntaxHighlighter.LOCAL_VARIABLE)

        is FlixPlainEnumDecl -> named(element.ident, FlixSyntaxHighlighter.TYPE_NAME)
        is FlixRestrictableEnumDecl -> named(element.ident, FlixSyntaxHighlighter.TYPE_NAME)
        is FlixEnumCase -> named(element.ident, FlixSyntaxHighlighter.ENUM_CASE)

        is FlixStructDecl -> named(element.ident, FlixSyntaxHighlighter.TYPE_NAME)
        is FlixStructField -> named(element.ident, FlixSyntaxHighlighter.FIELD_NAME)
        is FlixTypeAliasDecl -> named(element.ident, FlixSyntaxHighlighter.TYPE_NAME)
        is FlixTraitDecl -> named(element.ident, FlixSyntaxHighlighter.TRAIT_NAME)
        is FlixEffectDecl -> named(element.ident, FlixSyntaxHighlighter.EFFECT_NAME)

        // The one *use* this colours, and the only one the parser can settle on its own.
        is FlixPostfixExpr -> callee(element)

        else -> null
    }

    /**
     * The name being called in [expr], when it is one this layer can be sure of.
     *
     * ## What makes a call
     *
     * Not adjacency of tokens: the grammar says a call outright. `postfixExpr` is a head followed by
     * suffixes, and one of those suffixes is an argument list -- so a call is a head whose *next*
     * sibling is a [FlixArgumentList]. The next-sibling test is what tells `f(x)` from `e.f(x)`,
     * where the argument list belongs to `f` and the head is `e`.
     *
     * ## What it refuses, and why each one is measured
     *
     * `SemanticTokensProvider` was asked what the server says for every shape of call, and this
     * agrees with it or says nothing:
     *
     * | Call | Server | Here |
     * | --- | --- | --- |
     * | `helper(n)`, `List.length(xs)` | `Function` | the same |
     * | `g(n)`, where `g` is a local | `Function` | the same |
     * | `f(n)`, where `f` is a parameter | `Parameter` | nothing |
     * | `Some(4)`, `Colour.Shade(3)` | `EnumMember` | nothing |
     *
     * An enum case is refused by its capital: Flix names cases `Some` and definitions `length`, so
     * the first letter separates them without resolving anything. A parameter is refused by looking
     * for the name in the enclosing parameter lists, which the PSI has -- and which covers a lambda's
     * binders as well, since `lambdaExpr` is a parameter list too.
     *
     * Both refusals leave the name uncoloured rather than colouring it differently. A wrong colour is
     * read as information; an absent one is read as "not yet", which is what it is.
     */
    private fun callee(expr: FlixPostfixExpr): Role? {
        val head = expr.exprDelimited ?: return null
        if (head.nextSibling.skipWhitespace() !is FlixArgumentList) return null

        // The head must *be* a name, not merely contain one. Searching inside it colours the first
        // name in a parenthesised head -- `(h -> h(1))(x)` reported `h`, which is a lambda's binder
        // and not what is being called.
        val name = head.qualifiedName?.identList?.lastOrNull() ?: return null
        val text = name.text
        if (text.isEmpty() || !text.first().isLowerCase()) return null
        if (isParameter(expr, text)) return null

        return Role(name.textRange, FlixSyntaxHighlighter.FUNCTION_NAME)
    }

    /** Whether [name] is bound by a parameter list enclosing [element] -- a definition's or a lambda's. */
    private fun isParameter(element: PsiElement, name: String): Boolean =
        generateSequence(element.parent) { it.parent }
            .takeWhile { it !is FlixFile }
            .flatMap { PsiTreeUtil.getChildrenOfTypeAsList(it, FlixParameterList::class.java).asSequence() }
            .flatMap { it.parameterList.asSequence() }
            .any { it.ident?.text == name } ||
            generateSequence(element.parent) { it.parent }
                .takeWhile { it !is FlixFile }
                .filterIsInstance<FlixUnaryLambdaExpr>()
                .any { it.ident.text == name }

    /** The next element that is not whitespace, which is what "immediately followed by" means here. */
    private fun PsiElement?.skipWhitespace(): PsiElement? =
        generateSequence(this) { it.nextSibling }.firstOrNull { it !is PsiWhiteSpace }

    /** [ident]'s range with [key], or nothing if the declaration has no name yet. */
    private fun named(ident: PsiElement?, key: TextAttributesKey): Role? =
        ident?.let { Role(it.textRange, key) }
}
