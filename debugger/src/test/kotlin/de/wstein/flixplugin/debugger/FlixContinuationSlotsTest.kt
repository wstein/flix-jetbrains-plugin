package de.wstein.flixplugin.debugger

import com.sun.jdi.Field
import com.sun.jdi.IntegerValue
import com.sun.jdi.ObjectReference
import com.sun.jdi.ReferenceType
import com.sun.jdi.StringReference
import com.sun.jdi.Value
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

class FlixContinuationSlotsTest {

    @Test
    fun `parses only the slots belonging to the current pc`() {
        val json = """{"formatVersion":1,"pcs":{"1":[{"field":"arg0","name":"prefix","type":"Int32","kind":"parameter"}],"2":[{"field":"l0","name":"answer","type":"Option[String]","kind":"local"}]}}"""

        assertEquals(
            listOf(FlixContinuationSlots.Slot("l0", "answer", "Option[String]", "local")),
            FlixContinuationSlots.parse(json, pc = 2),
        )
    }

    @Test
    fun `malformed unknown and out of range metadata fail closed`() {
        assertTrue(FlixContinuationSlots.parse("not json", 1).isEmpty())
        assertTrue(FlixContinuationSlots.parse("""{"formatVersion":2,"pcs":{}}""", 1).isEmpty())
        assertTrue(FlixContinuationSlots.parse("""{"formatVersion":1,"pcs":{}}""", 0).isEmpty())
        assertTrue(FlixContinuationSlots.parse("""{"formatVersion":1,"pcs":{}}""", 99).isEmpty())
    }

    @Test
    fun `reads exact generated fields from a suspended continuation`() {
        val pc = field("pc")
        val metadata = field("frameSlots")
        val arg0 = field("arg0")
        val ignored = field("l0")
        val value = value("saved-prefix")
        val metadataValue = string("""{"formatVersion":1,"pcs":{"1":[{"field":"arg0","name":"prefix","type":"Int32","kind":"parameter"}]}}""")
        val type = referenceType(
            mapOf("pc" to pc, "frameSlots" to metadata, "arg0" to arg0, "l0" to ignored),
            mapOf(metadata to metadataValue),
        )
        val continuation = objectReference(
            type,
            mapOf(
                pc to integer(1),
                arg0 to value,
                ignored to value("not live"),
            ),
        )

        val slots = FlixContinuationSlots.valuesOf(continuation)
        assertEquals(listOf("prefix"), slots.map { it.slot.name })
        assertTrue(slots.single().value === value)
    }

    private fun field(name: String): Field = proxy(Field::class.java) { method, _ ->
        if (method.name == "name") name else null
    }

    private fun referenceType(fields: Map<String, Field>, values: Map<Field, Value?>): ReferenceType = proxy(ReferenceType::class.java) { method, args ->
        when (method.name) {
            "fieldByName" -> fields[args!![0]]
            "getValue" -> values[args!![0] as Field]
            else -> null
        }
    }

    private fun objectReference(type: ReferenceType, values: Map<Field, Value?>): ObjectReference =
        proxy(ObjectReference::class.java) { method, args ->
            when (method.name) {
                "referenceType" -> type
                "getValue" -> values[args!![0] as Field]
                else -> null
            }
        }

    private fun integer(value: Int): IntegerValue = proxy(IntegerValue::class.java) { method, _ ->
        if (method.name == "value") value else null
    }

    private fun string(value: String): StringReference = proxy(StringReference::class.java) { method, _ ->
        if (method.name == "value") value else null
    }

    private fun value(label: String): Value = proxy(Value::class.java) { method, _ ->
        if (method.name == "toString") label else null
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> proxy(type: Class<T>, handler: (java.lang.reflect.Method, Array<Any?>?) -> Any?): T =
        Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { instance, method, args ->
            when (method.name) {
                "hashCode" -> System.identityHashCode(instance)
                "equals" -> instance === args!![0]
                "toString" -> type.simpleName
                else -> handler(method, args)
            }
        } as T
}
