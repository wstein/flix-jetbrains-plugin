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
import org.flixlang.intellij.eval.FlixDebugEval
import org.flixlang.intellij.eval.FlixDebugEvalAnswer
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
 *
 * ## What the compiler is asked, and when
 *
 * Only when this cannot answer. An expression it *can* read is read: a watch is re-evaluated on
 * every step, and asking the language server to type `at` again on each one would spend a
 * compilation to confirm something already in hand.
 *
 * When the expression is outside what a read can do, the compiler is asked what it *is* -- see
 * [FlixDebugEval] -- and the refusal becomes an answer rather than a shrug:
 *
 * | The compiler says | The watch shows |
 * | --- | --- |
 * | the expression is ill-typed | its own diagnostics, in its own words |
 * | it is well-typed | the type and effect, and that running it is not implemented |
 * | it cannot say | the local limit, and why the compiler was no help |
 *
 * The middle row is the one worth the wiring. "`List.length(xs)` is `Int32 \ Pure`, and running it
 * needs an evaluator in the debuggee" tells a reader that their expression is right and the tool is
 * incomplete. Before this the same expression got a sentence about record projections, which reads
 * as though the expression were wrong.
 */
internal class FlixExpressionEvaluator(
    private val expression: FlixNavigation,
    private val compiler: (Project) -> FlixDebugEval? = { FlixDebugEval.getInstance(it) },
) : ExpressionEvaluator {

    override fun getModifier(): Modifier? = null

    override fun evaluate(context: EvaluationContext?): Value? {
        val path = when (expression) {
            is FlixNavigation.Unsupported -> return run(expression, context)
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

    /**
     * Compiles the expression, runs it in the debuggee, and returns what it produced.
     *
     * The order is the whole design. The compiler is asked what the expression *is*, because whether
     * it may be run is a question about its effect and that cannot be asked before it is typed; only
     * a pure expression is then run, because running anything else would perform the program's own
     * effects while it is stopped. Everything that is not run falls back to [explain], which says
     * what it is instead of what is missing.
     */
    private fun run(unsupported: FlixNavigation.Unsupported, context: EvaluationContext?): Value? {
        val answer = ask(context, unsupported.text, withArtifact = true)
            ?: throw EvaluateException(unsupported.reason)
        val typed = answer as? FlixDebugEvalAnswer.Typed
            ?: throw EvaluateException(explain(unsupported, answer))
        val artifact = typed.artifact

        if (!typed.isPure) {
            // Typed, and refused: running it would perform the program's own effects in a program
            // that is stopped. The message says what it is, which is more use than a refusal alone.
            throw EvaluateException(
                "`${unsupported.text}` is `${typed.type}` with effect `${typed.effect}`. Only an " +
                    "expression the compiler proves pure is run, because running this one would " +
                    "perform the program's effects while it is stopped.",
            )
        }
        if (artifact == null) {
            throw EvaluateException(
                "`${unsupported.text}` is `${typed.type}`, but the compiler produced nothing to run. " +
                    "The program has to be built with --Xdebug before an expression can be evaluated.",
            )
        }

        if (context == null) {
            throw EvaluateException("No frame is selected, so there is nothing to run the expression against.")
        }

        return try {
            FlixRemoteEval.evaluate(artifact, context)
        } catch (failed: FlixRemoteEvalException) {
            throw EvaluateException(failed.message, failed)
        }
    }

    /**
     * What to say about an expression this evaluator cannot read.
     *
     * Falls back to the local refusal whenever the compiler is not there to ask or has nothing to
     * add. A message about record projections is a poor answer for `List.length(xs)`, but it is a
     * better one than a message about a language server the user never asked about.
     */
    private fun explain(unsupported: FlixNavigation.Unsupported, answer: FlixDebugEvalAnswer): String {
        return when (answer) {
            // The compiler's own words about the expression. It has resolved names, checked types
            // and knows the scope; nothing here could say it better.
            is FlixDebugEvalAnswer.Invalid ->
                answer.diagnostics.joinToString("\n").ifBlank { unsupported.reason }

            // The expression is right and the tool is incomplete, which is a different thing to be
            // told and the reason this path exists.
            is FlixDebugEvalAnswer.Typed ->
                "`${unsupported.text}` is `${answer.type}` with effect `${answer.effect}`, and this session " +
                    "can only read values out of the paused frame. Running it needs an evaluator in " +
                    "the debuggee, which is not implemented yet."

            // Asked and got nothing: no debug build, no server, a frame it cannot place. Not a
            // verdict on the expression, so it must not replace one.
            is FlixDebugEvalAnswer.Unavailable ->
                unsupported.reason + " (the compiler could not help: " + answer.reason + ")"
        }
    }

    /**
     * The compiler's verdict on the whole expression, or `null` if there is nobody to ask.
     *
     * `null` in an IDE without LSP4IJ, in a frame with no location, and before a project has a
     * service -- every one of which is an ordinary state rather than a fault. Blocking, on the
     * debugger thread, and only on a path that has already failed.
     */
    private fun ask(
        context: EvaluationContext?,
        unsupportedText: String,
        withArtifact: Boolean = false,
    ): FlixDebugEvalAnswer? {
        val project = context?.project ?: return null
        val location = runCatching { context.frameProxy?.location() }.getOrNull() ?: return null
        val service = compiler(project) ?: return null
        return runCatching {
            service.compile(
                unsupportedText,
                location.declaringType().name(),
                location.method().name(),
                // Typing an expression runs nothing, so the widest policy is the right one here:
                // refusing to *type* an effectful expression would hide what it is, and what it is
                // is exactly what the message needs to say. Whether it may be *run* is decided
                // afterwards, from the effect this reports.
                FlixDebugEval.Policy.ALLOW_EFFECTS,
                withArtifact,
            )
        }.getOrNull()
    }

    /** How a value is described when it turns out not to be a record. */
    private fun describe(value: Value?): String = when (value) {
        null -> "null"
        is StringReference -> "a String"
        is ObjectReference -> "a ${FlixValues.simpleNameOf(value.referenceType().name())}"
        else -> "a ${value.type().name()}"
    }
}
