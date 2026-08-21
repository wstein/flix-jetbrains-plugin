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
    private data class Asked(val expression: String, val className: String, val methodName: String, val policy: FlixDebugEval.Policy)

    private val asked = mutableListOf<Asked>()

    @Test
    fun `a well-typed expression is reported as what it is, not as a mistake`() {
        // The case this wiring exists for. The expression is right; the tool cannot run it yet, and
        // saying so is a different message from "that is not a valid expression".
        val message = refusalFor("List.length(xs)", FlixDebugEvalAnswer.Typed("Int32", "Pure"))

        assertTrue("the type is missing: $message", message.contains("Int32"))
        assertTrue("the effect is missing: $message", message.contains("Pure"))
        assertTrue("the expression is missing: $message", message.contains("List.length(xs)"))
        assertTrue(
            "the message must say running it is what is missing: $message",
            message.contains("not implemented"),
        )
        assertFalse(
            "a well-typed expression must not be answered with the local grammar: $message",
            message.contains(FlixExpressions.LIMIT),
        )
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
                ): FlixDebugEvalAnswer {
                    asked += Asked(expression, className, methodName, policy)
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
