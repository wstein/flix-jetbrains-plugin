package dev.wstein.flixplugin.debugger

import com.intellij.debugger.ui.tree.ValueDescriptor
import com.sun.jdi.ClassType
import com.sun.jdi.Field
import com.sun.jdi.InterfaceType
import com.sun.jdi.Type
import com.sun.jdi.IntegerValue
import com.sun.jdi.ObjectReference
import com.sun.jdi.ReferenceType
import com.sun.jdi.StringReference
import com.sun.jdi.Value
import org.junit.Assert.assertEquals
import org.junit.Test
import java.lang.reflect.Proxy
import java.util.concurrent.CompletableFuture
import java.util.function.Function as JFunction

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
    fun `a recorded name takes the ordinal out of the tree`() {
        // With a name, the ordinal is the discriminator the label already used, and showing it
        // beside `Some` adds nothing -- the same reasoning that drops it for a case with a class of
        // its own. Without a name, the shared representation keeps it, because there it is the only
        // thing distinguishing one value from another.
        val named = tagged("dev.flix.gen.Tag\$Obj", ordinal = 1, payload = listOf(int(7)), recordedTag = "Some")
        val unnamed = tagged("dev.flix.gen.Tag\$Obj", ordinal = 1, payload = listOf(int(7)))

        assertEquals(listOf("v0"), FlixTaggedRenderer().childNamesOf(named))
        assertEquals(listOf("v0", "ordinal"), FlixTaggedRenderer().childNamesOf(unnamed))
    }

    @Test
    fun `a recorded name labels a value the class cannot name`() {
        val value = tagged("dev.flix.gen.Tag\$Obj", ordinal = 1, payload = listOf(string("/home/x")), recordedTag = "Some")

        assertEquals("Some(\"/home/x\")", FlixTaggedRenderer().labelOf(value))
    }

    @Test
    fun `a nullary tag expands to nothing`() {
        val tagged = tagged("dev.flix.gen.BombKind\$Fast", ordinal = 0, payload = emptyList())

        assertEquals(emptyList<String>(), FlixTaggedRenderer().childNamesOf(tagged))
    }

    // --- applicability, through the checker the platform actually calls ------------------------

    @Test
    fun `a null value has a null type, and asking about it must not throw`() {
        // The platform asks every renderer about every value. A `null` field -- `l0`, `param_1` in a
        // CPS frame -- has no type, so the checker is handed `null`. Kotlin's SAM conversion takes
        // the Java parameter as non-null and inserted a check, so the lambda threw an NPE for each
        // one, and the platform reported that on the *node*: every null field in the variables view
        // read "Internal error. See logs for more details" instead of `null`.
        //
        // Asserted through `getIsApplicableChecker` rather than `FlixValues.isA`, because the check
        // Kotlin inserts lives in the SAM and calling the rule directly steps straight past it.
        assertEquals(false, FlixRecordRenderer().applicableTo(null))
        assertEquals(false, FlixTaggedRenderer().applicableTo(null))
    }

    @Test
    fun `a record and a tag are recognised through the same checker`() {
        val record = record("a" to int(1))
        val tag = tagged("dev.flix.gen.BombKind\$Fast", ordinal = 0, payload = emptyList())

        assertEquals(true, FlixRecordRenderer().applicableTo(record.referenceType()))
        assertEquals(false, FlixTaggedRenderer().applicableTo(record.referenceType()))
        assertEquals(true, FlixTaggedRenderer().applicableTo(tag.referenceType()))
        assertEquals(false, FlixRecordRenderer().applicableTo(tag.referenceType()))
    }

    // --- the compiled identity the platform prepends ---------------------------------------------

    @Test
    fun `a rendered value drops the compiled class and object id`() {
        // The platform composes "{" + idLabel + "}" + valueText, so without this a record read
        // `{RecordExtend$Obj@1127} { dir = … }`. `RecordExtend$Obj` is the compiled shape of a
        // record and not its Flix type; printing it beside a Flix value invites writing it in a
        // watch, where it means nothing.
        val cleared = mutableListOf<String?>()
        val label = (FlixRecordRenderer().valueLabelRenderer as FlixLabelRenderer)
            .calcLabel(descriptorFor(record("a" to int(1)), cleared), null, null)

        assertEquals("{ a = 1 }", label)
        assertEquals(listOf<String?>(null), cleared)
    }

    @Test
    fun `a value the renderer could not read keeps its identity`() {
        // An empty label means the renderer failed. Clearing the identity there would leave the
        // node showing nothing at all, which is worse than showing the compiled class.
        val cleared = mutableListOf<String?>()
        val label = (FlixRecordRenderer().valueLabelRenderer as FlixLabelRenderer)
            .calcLabel(descriptorFor(null, cleared), null, null)

        assertEquals("", label)
        assertEquals(emptyList<String?>(), cleared)
    }

    /** A descriptor that records what its id label was set to. */
    private fun descriptorFor(value: Value?, cleared: MutableList<String?>): ValueDescriptor =
        proxy(ValueDescriptor::class.java) { method, args ->
            when (method.name) {
                "getValue" -> value
                "setIdLabel" -> { cleared += args?.get(0) as String?; null }
                else -> null
            }
        }

    // --- stubs ----------------------------------------------------------------------------------

    /**
     * Asks the checker the way the platform does, `null` included.
     *
     * One helper per renderer because the property is protected on the shared base class, and the
     * cast is what lets `null` through: the Java parameter is non-null to Kotlin, which is the whole
     * reason the SAM inserted a check and threw.
     */
    private fun FlixRecordRenderer.applicableTo(type: Type?): Boolean = ask(isApplicableChecker, type)

    private fun FlixTaggedRenderer.applicableTo(type: Type?): Boolean = ask(isApplicableChecker, type)

    @Suppress("UNCHECKED_CAST")
    private fun ask(checker: JFunction<Type, CompletableFuture<Boolean>>, type: Type?): Boolean =
        (checker as JFunction<Type?, CompletableFuture<Boolean>>).apply(type).get()

    private fun FlixRecordRenderer.childNamesOf(value: Value): List<String> =
        (childrenRenderer as FlixChildrenRenderer).childrenOf(value).map { it.first }

    private fun FlixRecordRenderer.labelOf(value: Value): String =
        (valueLabelRenderer as FlixLabelRenderer).label(value)

    private fun FlixTaggedRenderer.childNamesOf(value: Value): List<String> =
        (childrenRenderer as FlixChildrenRenderer).childrenOf(value).map { it.first }

    private fun FlixTaggedRenderer.labelOf(value: Value): String =
        (valueLabelRenderer as FlixLabelRenderer).label(value)

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
            interfaces = listOf("dev.flix.gen.Record\$"),
        )

    private fun emptyRecord(): ObjectReference =
        objectRef("dev.flix.gen.RecordEmpty\$", emptyMap(), interfaces = listOf("dev.flix.gen.Record\$"))

    /** A chain whose tail points back at its head. */
    private fun cyclicRecord(): ObjectReference {
        val fields = mutableMapOf<String, Value?>("label" to string("a"), "value" to int(1))
        val head = objectRef("dev.flix.gen.RecordExtend\$Obj", fields, interfaces = listOf("dev.flix.gen.Record\$"))
        fields["rest"] = head
        return head
    }

    private fun tagged(
        className: String,
        ordinal: Int,
        payload: List<Value>,
        recordedTag: String? = null,
    ): ObjectReference {
        val fields = payload.withIndex().associate { (i, v) -> "v$i" to v } +
            ("ordinal" to int(ordinal)) +
            // Present only in a `--Xdebug` build, so absent by default here.
            listOfNotNull(recordedTag?.let { FlixValues.TAG_NAME_FIELD to string(it) })
        return objectRef(className, fields, superclass = "dev.flix.gen.Tagged\$")
    }

    /** Distinct per stubbed object, as a real `uniqueID` is: the cycle guard keys on it. */
    private var nextId = 0L

    private fun objectRef(
        className: String,
        fields: Map<String, Value?>,
        superclass: String? = null,
        interfaces: List<String> = emptyList(),
    ): ObjectReference {
        val id = nextId++
        val declared = fields.keys.map { field(it) }
        // A ClassType, not a bare ReferenceType: the renderers reach `Record$` through an interface
        // and `Tagged$` through a superclass, so a stub with no hierarchy would answer "not a Flix
        // value" for both and prove nothing.
        val type = proxy(ClassType::class.java) { method, args ->
            when (method.name) {
                "name" -> className
                "allFields", "fields" -> declared
                "fieldByName" -> declared.firstOrNull { it.name() == args?.get(0) }
                "superclass" -> superclass?.let { classType(it) }
                "interfaces" -> interfaces.map { interfaceType(it) }
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

    private fun classType(name: String): ClassType = proxy(ClassType::class.java) { method, _ ->
        when (method.name) {
            "name" -> name
            "superclass" -> null
            "interfaces" -> emptyList<InterfaceType>()
            else -> null
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
