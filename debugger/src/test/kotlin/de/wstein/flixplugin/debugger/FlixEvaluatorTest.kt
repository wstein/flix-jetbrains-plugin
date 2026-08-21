package de.wstein.flixplugin.debugger

import com.intellij.debugger.engine.evaluation.EvaluateException
import com.intellij.debugger.engine.evaluation.EvaluationContext
import com.intellij.debugger.engine.jdi.StackFrameProxy
import com.sun.jdi.ClassType
import com.sun.jdi.Field
import com.sun.jdi.InterfaceType
import com.sun.jdi.LocalVariable
import com.sun.jdi.ObjectReference
import com.sun.jdi.StackFrame
import com.sun.jdi.StringReference
import com.sun.jdi.Value
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

/**
 * Evaluating a Flix expression against a paused frame.
 *
 * The frame is stubbed to the shape the compiler now produces: a continuation whose captures and
 * parameters are named in its `LocalVariableTable`, so `sep` and `at` resolve by the names in the
 * source rather than as `clo0` and `arg0`. Without that compiler change every one of these lookups
 * would fail, which is why the two landed together.
 */
class FlixEvaluatorTest {

    private val dir = string("/Users/werner/.config/flix-invaders")
    private val file = string("/Users/werner/.config/flix-invaders/scores.txt")
    private val sep = string("/")
    private val at = record("dir" to dir, "file" to file)

    /** The frame from a real session: `Option.map(at -> …)` inside an effectful `path()`. */
    private val frame = frame("sep" to sep, "at" to at)

    private fun evaluate(text: String): Value? =
        FlixExpressionEvaluator(FlixExpressions.parse(text)).evaluate(frame)

    private fun failure(text: String): String =
        runCatching { evaluate(text) }.exceptionOrNull().let {
            (it as? EvaluateException)?.message ?: error("expected `$text` to be refused, got $it")
        }

    @Test
    fun `a name resolves to the value the frame holds`() {
        assertSame(sep, evaluate("sep"))
        assertSame(at, evaluate("at"))
    }

    @Test
    fun `a record projection reads the field`() {
        // The expression from the screenshot that started this: `at#dir`.
        assertSame(dir, evaluate("at#dir"))
        assertSame(file, evaluate("at#file"))
    }

    @Test
    fun `projections chain through nested records`() {
        val inner = record("name" to string("WER"))
        val outer = record("player" to inner)
        val f = frame("s" to outer)

        assertSame(inner, FlixExpressionEvaluator(FlixExpressions.parse("s#player")).evaluate(f))
        assertEquals("WER", (FlixExpressionEvaluator(FlixExpressions.parse("s#player#name")).evaluate(f) as StringReference).value())
    }

    @Test
    fun `an unknown name is refused with what a frame records`() {
        val message = failure("nope")
        assertTrue(message, message.contains("`nope` is not visible in this frame"))
    }

    @Test
    fun `projecting out of a string says what it is`() {
        // `sep` is a String. Asking for `sep#dir` is a mistake worth naming precisely, because the
        // reader's model of which value is a record is exactly what went wrong.
        val message = failure("sep#dir")
        assertTrue(message, message.contains("`sep` is a String, not a record"))
    }

    @Test
    fun `a missing field lists the ones that exist`() {
        val message = failure("at#nope")
        assertTrue(message, message.contains("has no field `nope`"))
        assertTrue(message, message.contains("`dir`"))
        assertTrue(message, message.contains("`file`"))
    }

    @Test
    fun `a refusal names the part of the expression that failed, not the whole`() {
        // `at#dir#x` fails at `at#dir`, which is a String. Reporting the whole expression would
        // leave the reader guessing which projection was wrong.
        val message = failure("at#dir#x")
        assertTrue(message, message.contains("`at#dir` is a String"))
    }

    @Test
    fun `an unsupported expression is refused before the frame is touched`() {
        // No frame access at all: the parse already decided. A watch on `fileName()` must not cost
        // a JDI round trip on every step.
        val message = runCatching {
            FlixExpressionEvaluator(FlixExpressions.parse("fileName()")).evaluate(null)
        }.exceptionOrNull()?.message.orEmpty()
        assertTrue(message, message.contains(FlixExpressions.LIMIT))
    }

    @Test
    fun `with no frame selected it says so rather than throwing something opaque`() {
        val message = runCatching {
            FlixExpressionEvaluator(FlixExpressions.parse("at")).evaluate(null)
        }.exceptionOrNull()?.message.orEmpty()
        assertTrue(message, message.contains("No frame is selected"))
    }

    // --- stubs ----------------------------------------------------------------------------------

    private var nextId = 0L

    private fun frame(vararg locals: Pair<String, Value>): EvaluationContext {
        val vars = locals.associate { (name, _) -> name to localVariable(name) }
        val values = locals.associate { (name, v) -> name to v }
        val stackFrame = proxy(StackFrame::class.java) { method, args ->
            when (method.name) {
                "visibleVariableByName" -> vars[args?.get(0)]
                "getValue" -> values[(args?.get(0) as LocalVariable).name()]
                else -> null
            }
        }
        val frameProxy = proxy(StackFrameProxy::class.java) { method, _ ->
            when (method.name) {
                "getStackFrame" -> stackFrame
                else -> null
            }
        }
        return proxy(EvaluationContext::class.java) { method, _ ->
            when (method.name) {
                "getFrameProxy" -> frameProxy
                else -> null
            }
        }
    }

    private fun localVariable(name: String): LocalVariable = proxy(LocalVariable::class.java) { method, _ ->
        when (method.name) {
            "name" -> name
            else -> null
        }
    }

    private fun record(vararg fields: Pair<String, Value>): ObjectReference {
        var rest: ObjectReference = objectRef("dev.flix.gen.RecordEmpty\$", emptyMap())
        for ((label, value) in fields.reversed()) {
            rest = objectRef(
                "dev.flix.gen.RecordExtend\$Obj",
                mapOf("label" to string(label), "value" to value, "rest" to rest),
            )
        }
        return rest
    }

    private fun objectRef(className: String, fields: Map<String, Value?>): ObjectReference {
        val id = nextId++
        val declared = fields.keys.map { field(it) }
        val type = proxy(ClassType::class.java) { method, args ->
            when (method.name) {
                "name" -> className
                "allFields", "fields" -> declared
                "fieldByName" -> declared.firstOrNull { it.name() == args?.get(0) }
                "superclass" -> null
                "interfaces" -> listOf(interfaceType("dev.flix.gen.Record\$"))
                else -> null
            }
        }
        return proxy(ObjectReference::class.java) { method, args ->
            when (method.name) {
                "referenceType", "type" -> type
                "uniqueID" -> id
                "getValue" -> fields[(args?.get(0) as Field).name()]
                else -> null
            }
        }
    }

    private fun interfaceType(name: String): InterfaceType = proxy(InterfaceType::class.java) { method, _ ->
        when (method.name) {
            "name" -> name
            "superinterfaces" -> emptyList<InterfaceType>()
            else -> null
        }
    }

    private fun field(name: String): Field = proxy(Field::class.java) { method, _ ->
        when (method.name) {
            "name" -> name
            else -> null
        }
    }

    private fun string(text: String): StringReference = proxy(StringReference::class.java) { method, _ ->
        when (method.name) {
            "value" -> text
            "type", "referenceType" -> null
            else -> null
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> proxy(type: Class<T>, handler: (java.lang.reflect.Method, Array<Any?>?) -> Any?): T =
        Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, args ->
            handler(method, args)
        } as T
}
