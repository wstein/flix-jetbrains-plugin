package de.wstein.flixplugin.debugger

import com.intellij.debugger.engine.evaluation.EvaluateException
import com.intellij.debugger.engine.evaluation.EvaluationContext
import com.intellij.debugger.engine.jdi.StackFrameProxy
import com.intellij.openapi.project.Project
import com.sun.jdi.Location
import com.sun.jdi.Method
import com.sun.jdi.ReferenceType
import com.sun.jdi.StackFrame
import org.flixlang.intellij.eval.FlixDebugEval
import org.flixlang.intellij.eval.FlixDebugEvalAnswer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

/**
 * What a watch says about an expression this session cannot read.
 *
 * The evaluator reads values out of the paused frame and nothing else. Everything larger used to get
 * one sentence about record projections, whatever was wrong with it — so `List.length(xs)`, which is
 * perfectly good Flix, read as though the user had made a mistake.
 *
 * It now asks the compiler what the expression *is*, and the answer decides what the watch shows.
 * The three cases are not interchangeable, and the tests below exist because collapsing any two of
 * them sends a reader to the wrong place: to their own expression when the tool is incomplete, or to
 * the tool when their expression is wrong.
 */
class FlixEvaluatorAnswerTest {

    /** What was asked of the compiler, so a test can assert the question as well as the answer. */
    private data class Asked(
        val expression: String,
        val className: String,
        val methodName: String,
        val policy: FlixDebugEval.Policy,
        val withArtifact: Boolean,
    )

    private val asked = mutableListOf<Asked>()

    @Test
    fun `an effectful expression is described rather than run`() {
        // Typed, and refused on purpose: running it would perform the program's own effects in a
        // program that is stopped. What it *is* is still worth saying -- that is the difference
        // between "not allowed" and "that is not a valid expression".
        val message = refusalFor("println(xs)", FlixDebugEvalAnswer.Typed("Unit", "IO"))

        assertTrue("the type is missing: $message", message.contains("Unit"))
        assertTrue("the effect is missing: $message", message.contains("IO"))
        assertTrue("the expression is missing: $message", message.contains("println(xs)"))
        assertTrue("the reason is missing: $message", message.contains("pure"))
        assertFalse(
            "a well-typed expression must not be answered with the local grammar: $message",
            message.contains(FlixExpressions.LIMIT),
        )
    }

    @Test
    fun `a pure expression with nothing to run says what is missing`() {
        // The compiler typed it and produced no classes, which happens when the program was not
        // built with --Xdebug. That is a statement about the build, and telling a user their
        // expression is at fault would be false.
        val message = refusalFor("List.length(xs)", FlixDebugEvalAnswer.Typed("Int32", "Pure"))

        assertTrue("the type is missing: $message", message.contains("Int32"))
        assertTrue("what to do about it is missing: $message", message.contains("--Xdebug"))
    }

    @Test
    fun `an ill-typed expression is answered in the compiler's own words`() {
        // It has resolved the names, checked the types and knows the scope. Nothing in the debugger
        // could say it better, and paraphrasing would only lose the detail.
        val message = refusalFor("at + 1", FlixDebugEvalAnswer.Invalid(listOf("Undefined name 'at'.", "in this scope")))

        assertEquals("Undefined name 'at'.\nin this scope", message)
    }

    @Test
    fun `when the compiler cannot help, the local limit is still explained`() {
        // No debug build, no server, a frame it cannot place. None of those is a verdict on the
        // expression, so none may replace one -- but the reason is worth appending, because "there
        // is no debug build" is actionable and silence is not.
        val message = refusalFor("List.length(xs)", FlixDebugEvalAnswer.Unavailable("no --Xdebug build"))

        assertTrue("the local limit is missing: $message", message.contains(FlixExpressions.LIMIT))
        assertTrue("the compiler's reason is missing: $message", message.contains("no --Xdebug build"))
    }

    @Test
    fun `with no compiler at all the message is the one from before`() {
        // An IDE without LSP4IJ. The evaluator must not start talking about a language server the
        // user has not installed and did not ask about.
        val message = refusalFor("List.length(xs)", answer = null)

        assertTrue("the local limit is missing: $message", message.contains(FlixExpressions.LIMIT))
        assertFalse("it mentions a compiler that is not there: $message", message.contains("could not help"))
    }

    @Test
    fun `the frame is asked about by the class and method the debugger is in`() {
        // Not by a source position: one `.flix` definition becomes several classes, and a
        // continuation's parameters live in a method the programmer never wrote. The class and
        // method are what JDI has and what the build recorded.
        refusalFor("List.length(xs)", FlixDebugEvalAnswer.Typed("Int32", "Pure"))

        assertEquals(1, asked.size)
        assertEquals("List.length(xs)", asked.single().expression)
        assertEquals("dev.flix.gen.Def\$describe", asked.single().className)
        assertEquals("staticApply", asked.single().methodName)
    }

    @Test
    fun `a boolean condition is passed through as the debuggee produced it`() {
        // A breakpoint condition is an expression of type Bool, and it reaches this evaluator by the
        // same route a watch does -- the platform picks a code-fragment factory by the context, and
        // a `.flix` context is ours. What the platform then does with the answer is unbox it and
        // test it, so nothing here may convert, coerce or second-guess it.
        //
        // The value itself comes from the debuggee, so what is pinned here is that a pure Bool with
        // an artifact takes the running path rather than one of the refusals.
        val message = runCatching {
            evaluatorFor(
                "n > 0",
                FlixDebugEvalAnswer.Typed("Bool", "Pure", artifact("dev.flix.gen.Def\u0024flixDebugEvalWrapper")),
            )
        }.exceptionOrNull()?.message.orEmpty()

        assertFalse(
            "a pure boolean expression must not be refused for want of something to run: $message",
            message.contains("--Xdebug"),
        )
        assertFalse(
            "a pure boolean expression must not be refused as effectful: $message",
            message.contains("perform the program"),
        )
    }

    @Test
    fun `an artifact is asked for, since the expression may have to be run`() {
        // The request that makes a value possible at all. Asked once, with the type and the classes
        // together: a second call to fetch the artifact would compile the project again.
        refusalFor("println(xs)", FlixDebugEvalAnswer.Typed("Unit", "IO"))

        assertTrue("no artifact was asked for", asked.single().withArtifact)
    }

    @Test
    fun `typing an expression asks for the widest policy, because typing runs nothing`() {
        // Refusing to *type* an effectful expression would hide what it is, and what it is is
        // exactly what the message needs to say. The policy that matters is the one asked when
        // something is actually run, which nothing here does.
        refusalFor("println(xs)", FlixDebugEvalAnswer.Typed("Unit", "IO"))

        assertEquals(FlixDebugEval.Policy.ALLOW_EFFECTS, asked.single().policy)
    }

    @Test
    fun `an expression the frame can answer is never sent to the compiler`() {
        // A watch is re-evaluated on every step. Asking the language server to type `at` again on
        // each one would spend a compilation to confirm something already in hand.
        // Against a service that is present and would answer: a stub that offered no service at all
        // would record nothing whatever the evaluator did, and the test would pass by construction.
        val evaluator = FlixExpressionEvaluator(
            FlixExpressions.parse("at"),
            compilerThatAnswers(FlixDebugEvalAnswer.Unavailable("should never be asked")),
        )
        runCatching { evaluator.evaluate(contextIn("dev.flix.gen.Def\$describe", "staticApply")) }

        assertEquals("the compiler was consulted about a readable expression", emptyList<Asked>(), asked)
    }

    /** An artifact with nothing in it, for a test that only cares which path is taken. */
    private fun artifact(entryClass: String) = org.flixlang.intellij.eval.FlixDebugEvalArtifact(
        classes = "",
        entryClass = entryClass,
        entryMethod = "staticApply",
        valueField = "b",
        parameters = listOf("n"),
    )

    /** Evaluates [text] against a frame that holds nothing, so only the decision is exercised. */
    private fun evaluatorFor(text: String, answer: FlixDebugEvalAnswer) =
        FlixExpressionEvaluator(FlixExpressions.parse(text), compilerThatAnswers(answer))
            .evaluate(contextIn("dev.flix.gen.Def\u0024describe", "staticApply"))

    /** The message a watch would show for [text], given what the compiler says about it. */
    private fun refusalFor(text: String, answer: FlixDebugEvalAnswer?): String {
        val evaluator = FlixExpressionEvaluator(FlixExpressions.parse(text), compilerThatAnswers(answer))
        val thrown = runCatching {
            evaluator.evaluate(contextIn("dev.flix.gen.Def\$describe", "staticApply"))
        }.exceptionOrNull()

        assertTrue("the expression was not refused at all", thrown is EvaluateException)
        return thrown!!.message.orEmpty()
    }

    /** A service that always answers [answer], or no service at all when it is `null`. */
    private fun compilerThatAnswers(answer: FlixDebugEvalAnswer?): (Project) -> FlixDebugEval? = {
        answer?.let {
            object : FlixDebugEval {
                override fun compile(
                    expression: String,
                    className: String,
                    methodName: String,
                    policy: FlixDebugEval.Policy,
                    withArtifact: Boolean,
                ): FlixDebugEvalAnswer {
                    asked += Asked(expression, className, methodName, policy, withArtifact)
                    return it
                }
            }
        }
    }

    /**
     * A context paused in [className]`.`[methodName], holding no variables.
     *
     * No variables on purpose: every expression under test here is one the frame cannot answer, and
     * a frame that could answer it would take a different path.
     */
    private fun contextIn(className: String, methodName: String): EvaluationContext {
        val type = proxy(ReferenceType::class.java) { m, _ -> if (m.name == "name") className else null }
        val method = proxy(Method::class.java) { m, _ -> if (m.name == "name") methodName else null }
        val location = proxy(Location::class.java) { m, _ ->
            when (m.name) {
                "declaringType" -> type
                "method" -> method
                else -> null
            }
        }
        val stackFrame = proxy(StackFrame::class.java) { _, _ -> null }
        val frameProxy = proxy(StackFrameProxy::class.java) { m, _ ->
            when (m.name) {
                "location" -> location
                "getStackFrame" -> stackFrame
                else -> null
            }
        }
        val project = proxy(Project::class.java) { m, _ -> if (m.returnType == java.lang.Boolean.TYPE) false else null }
        return proxy(EvaluationContext::class.java) { m, _ ->
            when (m.name) {
                "getFrameProxy" -> frameProxy
                "getProject" -> project
                else -> null
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> proxy(type: Class<T>, handler: (java.lang.reflect.Method, Array<out Any?>?) -> Any?): T =
        Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, args ->
            handler(method, args) ?: defaultFor(method.returnType)
        } as T

    private fun defaultFor(type: Class<*>): Any? = when (type) {
        java.lang.Boolean.TYPE -> false
        Integer.TYPE -> 0
        java.lang.Long.TYPE -> 0L
        else -> null
    }
}
