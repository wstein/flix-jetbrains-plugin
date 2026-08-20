package dev.wstein.flixplugin.debugger

import com.sun.jdi.Field
import com.sun.jdi.IntegerValue
import com.sun.jdi.ObjectReference
import com.sun.jdi.ReferenceType
import com.sun.jdi.StringReference
import com.sun.jdi.Value
import org.junit.Assert.assertEquals
import org.junit.Test
import java.lang.reflect.Proxy

/**
 * What a Flix value looks like once it is *expanded*, not merely labelled.
 *
 * The label was already readable; the tree under it was not. A record is compiled to a chain of
 * `label`/`value`/`rest` links, so reading the third field meant opening three nested nodes named
 * after the chain rather than after the record — the compiled form, not the value. These assertions
 * are the flattening, over JDI stubs built to the shapes a real project produces.
 *
 * The renderers themselves need a live `EvaluationContext` and a `Project`, so what is exercised
 * here is the rule that turns a value into named children. Everything above that is platform
 * plumbing this plugin does not own.
 */
class FlixValueTreeTest {

    // --- records --------------------------------------------------------------------------------

    @Test
    fun `a record expands to its fields, not to a chain`() {
        val record = record("name" to string("WER"), "score" to int(1), "rank" to int(7))

        assertEquals(
            listOf("name", "score", "rank"),
            FlixRecordRenderer().childNamesOf(record),
        )
    }

    @Test
    fun `a field name is the label's text, without the quotes a string renders with`() {
        // `renderScalar` quotes a string, which is right for a *value* and wrong for a name: the
        // field is `name`, not `"name"`. Reading the label through it put the quotes in both the
        // summary and the tree.
        val record = record("name" to string("WER"))

        assertEquals(listOf("name"), FlixRecordRenderer().childNamesOf(record))
        assertEquals("{ name = \"WER\" }", FlixRecordRenderer().labelOf(record))
    }

    @Test
    fun `the tree shows every field even where the label stops`() {
        // The label is a summary and stops at MAX_RECORD_FIELDS; the tree is the data. A record
        // truncated in both would hide fields with no way to reach them.
        val wide = record(*(1..FlixValues.MAX_RECORD_FIELDS + 4).map { "f$it" to int(it) }.toTypedArray())

        assertEquals(FlixValues.MAX_RECORD_FIELDS + 4, FlixRecordRenderer().childNamesOf(wide).size)
        assertEquals(true, FlixRecordRenderer().labelOf(wide).endsWith(", … }"))
    }

    @Test
    fun `an empty record expands to nothing`() {
        assertEquals(emptyList<String>(), FlixRecordRenderer().childNamesOf(emptyRecord()))
    }

    @Test
    fun `a cyclic chain terminates instead of hanging the variables view`() {
        // A record is immutable and cannot be cyclic, but a debuggee caught mid-construction or
        // simply corrupt can be. A renderer that looped here would take the whole view with it.
        val cyclic = cyclicRecord()

        assertEquals(listOf("a"), FlixRecordRenderer().childNamesOf(cyclic))
    }

    // --- tagged unions --------------------------------------------------------------------------

    @Test
    fun `a named tag expands to its payload without repeating the ordinal`() {
        // The label already said `InvaderBlast`; `ordinal = 0` beside it adds nothing.
        val tagged = tagged("dev.flix.gen.BlastKind\$InvaderBlast", ordinal = 0, payload = listOf(int(3), int(4)))

        assertEquals(listOf("v0", "v1"), FlixTaggedRenderer().childNamesOf(tagged))
    }

    @Test
    fun `a shared representation keeps the ordinal, which is all that distinguishes it`() {
        // `Tag$Obj$Obj` serves every two-field tag, so its label falls back to `#1`. There the
        // ordinal is the only thing telling one value from another, and dropping it would leave the
        // reader with no discriminator at all.
        val tagged = tagged("dev.flix.gen.Tag\$Obj\$Obj", ordinal = 1, payload = listOf(int(3), int(4)))

        assertEquals(listOf("v0", "v1", "ordinal"), FlixTaggedRenderer().childNamesOf(tagged))
    }

    @Test
    fun `a nullary tag expands to nothing`() {
        val tagged = tagged("dev.flix.gen.BombKind\$Fast", ordinal = 0, payload = emptyList())

        assertEquals(emptyList<String>(), FlixTaggedRenderer().childNamesOf(tagged))
    }

    // --- stubs ----------------------------------------------------------------------------------

    private fun FlixRecordRenderer.childNamesOf(value: Value): List<String> =
        (childrenRenderer as FlixChildrenRenderer).childrenOf(value).map { it.first }

    private fun FlixRecordRenderer.labelOf(value: Value): String =
        (valueLabelRenderer as FlixLabelRenderer).label(value)

    private fun FlixTaggedRenderer.childNamesOf(value: Value): List<String> =
        (childrenRenderer as FlixChildrenRenderer).childrenOf(value).map { it.first }

    /** A record, as `RecordExtend$…` links ending at `RecordEmpty$`. */
    private fun record(vararg fields: Pair<String, Value>): ObjectReference {
        var rest = emptyRecord()
        for ((label, value) in fields.reversed()) {
            rest = link(label, value, rest)
        }
        return rest
    }

    private fun link(label: String, value: Value, rest: ObjectReference): ObjectReference =
        objectRef(
            "dev.flix.gen.RecordExtend\$Obj",
            mapOf("label" to string(label), "value" to value, "rest" to rest),
        )

    private fun emptyRecord(): ObjectReference = objectRef("dev.flix.gen.RecordEmpty\$", emptyMap())

    /** A chain whose tail points back at its head. */
    private fun cyclicRecord(): ObjectReference {
        val fields = mutableMapOf<String, Value?>("label" to string("a"), "value" to int(1))
        val head = objectRef("dev.flix.gen.RecordExtend\$Obj", fields)
        fields["rest"] = head
        return head
    }

    private fun tagged(className: String, ordinal: Int, payload: List<Value>): ObjectReference {
        val fields = payload.withIndex().associate { (i, v) -> "v$i" to v } + ("ordinal" to int(ordinal))
        return objectRef(className, fields)
    }

    /** Distinct per stubbed object, as a real `uniqueID` is: the cycle guard keys on it. */
    private var nextId = 0L

    private fun objectRef(className: String, fields: Map<String, Value?>): ObjectReference {
        val id = nextId++
        val declared = fields.keys.map { field(it) }
        val type = proxy(ReferenceType::class.java) { method, args ->
            when (method.name) {
                "name" -> className
                "allFields", "fields" -> declared
                "fieldByName" -> declared.firstOrNull { it.name() == args?.get(0) }
                else -> null
            }
        }
        return proxy(ObjectReference::class.java) { method, args ->
            when (method.name) {
                "referenceType" -> type
                "uniqueID" -> id
                "getValue" -> fields[(args?.get(0) as Field).name()]
                else -> null
            }
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
            else -> null
        }
    }

    private fun int(n: Int): IntegerValue = proxy(IntegerValue::class.java) { method, _ ->
        when (method.name) {
            "value", "intValue" -> n
            "toString" -> n.toString()
            else -> null
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> proxy(type: Class<T>, handler: (java.lang.reflect.Method, Array<Any?>?) -> Any?): T =
        Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, args ->
            handler(method, args)
        } as T
}
