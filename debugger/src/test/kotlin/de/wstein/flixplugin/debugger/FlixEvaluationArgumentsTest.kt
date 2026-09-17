package de.wstein.flixplugin.debugger

import com.sun.jdi.ArrayReference
import com.sun.jdi.IntegerValue
import com.sun.jdi.LocalVariable
import com.sun.jdi.ObjectReference
import com.sun.jdi.StackFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.lang.reflect.Proxy

class FlixEvaluationArgumentsTest {
    @Test
    fun `all values are read before any remote operation can resume the thread`() {
        var resumed = false
        var reads = 0
        var pinned = 0
        val variable = proxy(LocalVariable::class.java) { _, _ -> null }
        val value = proxy(IntegerValue::class.java) { _, _ -> null }
        val frame = proxy(StackFrame::class.java) { method, _ ->
            check(!resumed) { "Reading an invalidated frame" }
            when (method) {
                "visibleVariableByName" -> variable
                "getValue" -> { reads++; value }
                else -> null
            }
        }
        val array = proxy(ArrayReference::class.java) { method, _ ->
            when (method) {
                "disableCollection" -> pinned++
                "enableCollection" -> pinned--
            }
            null
        }
        val answer = FlixEvaluationArguments.use(listOf("a", "b"), frame,
            retained = emptyList(),
            create = { resumed = true; array },
            box = { resumed = true; null },
            invoke = { assertEquals(2, reads); assertEquals(1, pinned); 42 })
        assertEquals(42, answer)
        assertEquals(0, pinned)
    }

    @Test
    fun `snapshot objects and the argument array are unpinned when boxing fails`() {
        var pinned = 0
        fun pin(method: String) {
            when (method) {
                "disableCollection" -> pinned++
                "enableCollection" -> pinned--
            }
        }
        val value = proxy(ObjectReference::class.java) { method, _ -> pin(method); null }
        val array = proxy(ArrayReference::class.java) { method, _ -> pin(method); null }
        val variable = proxy(LocalVariable::class.java) { _, _ -> null }
        val frame = proxy(StackFrame::class.java) { method, _ ->
            when (method) {
                "visibleVariableByName" -> variable
                "getValue" -> value
                else -> null
            }
        }
        assertThrows(IllegalStateException::class.java) {
            FlixEvaluationArguments.use(listOf("value"), frame,
                retained = emptyList(),
                create = { assertEquals(1, pinned); array },
                box = { assertEquals(2, pinned); error("boxing failed") },
                invoke = { error("must not invoke") })
        }
        assertEquals(0, pinned)
    }

    private fun <T> proxy(type: Class<T>, action: (String, Array<out Any?>?) -> Any?): T =
        type.cast(Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, args ->
            action(method.name, args)
        })
}
