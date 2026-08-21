package de.wstein.flixplugin.debugger

import com.intellij.debugger.SourcePosition
import com.intellij.debugger.engine.evaluation.EvaluateException
import com.intellij.debugger.engine.evaluation.EvaluationContext
import com.intellij.debugger.engine.evaluation.CodeFragmentFactory
import com.intellij.debugger.engine.evaluation.TextWithImports
import com.intellij.debugger.engine.evaluation.expression.EvaluatorBuilder
import com.intellij.debugger.engine.evaluation.expression.ExpressionEvaluator
import com.intellij.debugger.engine.evaluation.expression.Modifier
import com.intellij.extapi.psi.PsiFileBase
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.project.Project
import com.intellij.psi.FileViewProvider
import com.intellij.psi.PsiCodeFragment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.SingleRootFileViewProvider
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.testFramework.LightVirtualFile
import com.sun.jdi.ObjectReference
import com.sun.jdi.StringReference
import com.sun.jdi.Value
import org.flixlang.intellij.lang.FlixFileType
import org.flixlang.intellij.lang.FlixLanguage

/**
 * Lets "Evaluate expression" and watches be written in Flix rather than in Java.
 *
 * ## What this is, and what it is not
 *
 * It is the IntelliJ adapter. IntelliJ's Java debugger still owns the session and the JDWP
 * connection (ADR 0002); this only supplies the front end for a `.flix` context, so Java, Kotlin and
 * Scala frames keep their own evaluators untouched.
 *
 * It is **not** a translation of Flix into Java, and it is not a second Flix type checker. It
 * evaluates exactly the expressions that can be answered by *reading* the paused frame -- see
 * [FlixExpressions.LIMIT] -- and refuses everything else by name. Anything larger has to be
 * resolved, typed and lowered by the compiler and run in the debuggee, which is a different feature.
 *
 * Before this, a watch was a Java expression over the compiled form. `at#dir` failed with
 * `Invalid expression : #`, and `"${at#dir}${sep}"` "succeeded" -- it is a valid Java string literal,
 * so the evaluator returned it unchanged, which reads as an answer and is not one.
 *
 * ## Why the fragment is Flix even though the grammar is small
 *
 * The fragment carries the Flix language, so the Evaluate dialog lexes and colours what is typed as
 * Flix. The Flix parser parses *files*, so a bare expression leaves error elements in the tree;
 * they are harmless here because the evaluator reads the fragment's text rather than its PSI. Giving
 * the grammar an expression entry point is the change that would let the tree be used, and is the
 * natural first step when the compiler-backed evaluator arrives.
 */
class FlixCodeFragmentFactory : CodeFragmentFactory() {

    override fun isContextAccepted(context: PsiElement?): Boolean =
        context?.language == FlixLanguage

    override fun getFileType(): LanguageFileType = FlixFileType.INSTANCE

    override fun createPsiCodeFragment(item: TextWithImports, context: PsiElement?, project: Project): PsiCodeFragment {
        val file = LightVirtualFile("flix-fragment.flix", FlixFileType.INSTANCE, item.text)
        val provider = SingleRootFileViewProvider(PsiManager.getInstance(project), file, false)
        return FlixCodeFragment(provider)
    }

    override fun createPresentationPsiCodeFragment(item: TextWithImports, context: PsiElement?, project: Project): PsiCodeFragment =
        createPsiCodeFragment(item, context, project)

    override fun getEvaluatorBuilder(): EvaluatorBuilder = FlixEvaluatorBuilder
}

/** A Flix expression typed into the debugger, rather than a file on disk. */
internal class FlixCodeFragment(viewProvider: FileViewProvider) :
    PsiFileBase(viewProvider, FlixLanguage), PsiCodeFragment {

    private var forcedScope: GlobalSearchScope? = null

    override fun getFileType(): FileType = FlixFileType.INSTANCE

    override fun forceResolveScope(scope: GlobalSearchScope?) {
        forcedScope = scope
    }

    override fun getForcedResolveScope(): GlobalSearchScope? = forcedScope
}

internal object FlixEvaluatorBuilder : EvaluatorBuilder {

    override fun build(codeFragment: PsiElement?, position: SourcePosition?): ExpressionEvaluator {
        // Parsed once, here, rather than on every evaluation: a watch is re-evaluated on each step,
        // and a refusal does not become truer for being recomputed.
        val parsed = FlixExpressions.parse(codeFragment?.text.orEmpty())
        return FlixExpressionEvaluator(parsed)
    }
}

/**
 * Reads a frame variable, and projects record fields out of it.
 *
 * Nothing here runs code in the debuggee. That is what keeps it outside the question of effects: a
 * read cannot change the program, so there is no policy to apply, no confirmation to ask for and no
 * way for the answer to differ from what the program holds.
 */
internal class FlixExpressionEvaluator(private val expression: FlixNavigation) : ExpressionEvaluator {

    override fun getModifier(): Modifier? = null

    override fun evaluate(context: EvaluationContext?): Value? {
        val path = when (expression) {
            is FlixNavigation.Unsupported -> throw EvaluateException(expression.reason)
            is FlixNavigation.Path -> expression
        }
        val frame = context?.frameProxy?.stackFrame
            ?: throw EvaluateException("No frame is selected, so there is nothing to read.")

        val variable = frame.visibleVariableByName(path.name)
            ?: throw EvaluateException(FlixExpressions.unknownName(path.name))
        var value: Value? = frame.getValue(variable)

        // Rebuilt as we descend so a refusal names the part that failed -- `at#dir` rather than the
        // whole expression -- which is the difference between "this field is missing" and "this
        // expression is wrong".
        val soFar = StringBuilder(path.name)
        for (field in path.fields) {
            value = project(value, field, soFar.toString())
            soFar.append('#').append(field)
        }
        return value
    }

    private fun project(value: Value?, field: String, expressionSoFar: String): Value? {
        val record = value as? ObjectReference
            ?: throw EvaluateException(FlixExpressions.notARecord(expressionSoFar, describe(value)))
        if (!FlixValues.isA(supertypesOf(record.referenceType()), FlixValues.RECORD_TYPE)) {
            throw EvaluateException(FlixExpressions.notARecord(expressionSoFar, describe(value)))
        }
        val fields = recordFields(record, Int.MAX_VALUE).first
        val match = fields.firstOrNull { it.first == field }
            ?: throw EvaluateException(FlixExpressions.noSuchField(expressionSoFar, field, fields.map { it.first }))
        return match.second
    }

    /** How a value is described when it turns out not to be a record. */
    private fun describe(value: Value?): String = when (value) {
        null -> "null"
        is StringReference -> "a String"
        is ObjectReference -> "a ${FlixValues.simpleNameOf(value.referenceType().name())}"
        else -> "a ${value.type().name()}"
    }
}
