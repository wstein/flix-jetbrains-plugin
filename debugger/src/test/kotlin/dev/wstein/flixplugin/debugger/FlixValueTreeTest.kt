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
import org.junit.Assert.assertNull
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

    // --- lists ------------------------------------------------------------------------------------

    @Test
    fun `a list reads as a list, not as a chain of cons cells`() {
        // What the variables view showed: `Cons(-96.0, Obj)`, and one nested node per element below
        // it, each named `v1` after the field holding the rest.
        val list = list(float(-96.0f), float(-64.0f), float(-36.0f))

        assertEquals("-96.0 :: -64.0 :: -36.0 :: Nil", FlixTaggedRenderer().labelOf(list))
    }

    @Test
    fun `a list expands to its elements, named by position`() {
        val list = list(float(-96.0f), float(-64.0f), float(-36.0f))

        assertEquals(listOf("[0]", "[1]", "[2]"), FlixTaggedRenderer().childNamesOf(list))
    }

    @Test
    fun `the empty list is Nil`() {
        assertEquals("Nil", FlixTaggedRenderer().labelOf(nil()))
        assertEquals(emptyList<String>(), FlixTaggedRenderer().childNamesOf(nil()))
    }

    @Test
    fun `a label stops at the limit and says so, while the tree holds everything`() {
        // An ellipsis rather than `Nil`, because a `Nil` there would claim the list ends where the
        // renderer stopped looking.
        val long = list(*(1..FlixValues.MAX_LIST_ELEMENTS + 3).map { int(it) as Value }.toTypedArray())

        assertEquals(true, FlixTaggedRenderer().labelOf(long).endsWith(":: … :: Nil"))
        assertEquals(FlixValues.MAX_LIST_ELEMENTS + 3, FlixTaggedRenderer().childNamesOf(long).size)
    }

    @Test(timeout = 10_000)
    fun `a cyclic list terminates instead of hanging the variables view`() {
        // The tree is where this matters: the label stops at its own limit either way, while the
        // tree walks the whole list and would follow a cycle forever -- on the debugger thread,
        // with the UI waiting on it. Hence the timeout: without the guard this hangs rather than
        // failing, and a hung suite says nothing.
        val fields = mutableMapOf<String, Value?>("v0" to int(1), "ordinal" to int(1), "tag" to string("List\$Vv4NSpVAmjE.Cons"))
        val cell = objectRef("dev.flix.gen.Tag\$Obj\$Obj", fields, superclass = "dev.flix.gen.Tagged\$")
        fields["v1"] = cell

        assertEquals("1 :: …", FlixTaggedRenderer().labelOf(cell))
        assertEquals(listOf("[0]"), FlixTaggedRenderer().childNamesOf(cell))
    }

    @Test
    fun `a two-term case that is not a list is still a tag`() {
        // The reason a list is recognised by its recorded case name and not by its shape: `Pair` is
        // built into the same class as `Cons`, and a structural rule would walk it as a list.
        val pair = tagged("dev.flix.gen.Tag\$Obj\$Obj", ordinal = 0, payload = listOf(int(1), int(2)), recordedTag = "Shape.Pair")

        assertEquals("Pair(1, 2)", FlixTaggedRenderer().labelOf(pair))
    }

    @Test
    fun `without a recorded case name a list is the cons chain it is`() {
        // A build without `--Xdebug` records nothing, and nothing can be inferred: the class is
        // shared with every other two-term case. The old rendering is the honest one.
        val inner = tagged("dev.flix.gen.List\$Nil", ordinal = 0, payload = emptyList())
        val outer = tagged("dev.flix.gen.Tag\$Obj\$Obj", ordinal = 1, payload = listOf(int(1), inner))

        assertEquals("#1(1, Nil)", FlixTaggedRenderer().labelOf(outer))
    }

    // --- tuples -----------------------------------------------------------------------------------

    @Test
    fun `a tuple reads the way the language writes one`() {
        // `(exp1, …, expn)` is the syntax, so that is the rendering. Unrendered this was
        // `{Tuple$Int32$Int32@3405}`, which names the shape and not the value.
        assertEquals("(1, 2)", FlixTupleRenderer().labelOf(tuple(int(1), int(2))))
        assertEquals("(1, \"x\", true)", FlixTupleRenderer().labelOf(tuple(int(1), string("x"), bool(true))))
    }

    @Test
    fun `a tuple expands by position, because that is how one is written`() {
        assertEquals(listOf("[0]", "[1]"), FlixTupleRenderer().childNamesOf(tuple(int(1), int(2))))
    }

    @Test
    fun `components are ordered by their index and not by the class file`() {
        // JDI lists fields in the order the compiler wrote them, which is not something a value read
        // positionally should depend on.
        val reversed = objectRef(
            "dev.flix.gen.Tuple\$Int32\$Int32",
            linkedMapOf("field1" to int(2), "field0" to int(1)),
        )

        assertEquals("(1, 2)", FlixTupleRenderer().labelOf(reversed))
    }

    @Test
    fun `a tuple is recognised by its class, with no tag and no --Xdebug`() {
        // The one Flix value whose compiled form is unambiguous on its own: `BackendObjType.Tuple`
        // gives every arity and component typing a class of its own.
        assertEquals(true, FlixTupleRenderer().applicableTo(tuple(int(1)).referenceType()))
        assertEquals(false, FlixTupleRenderer().applicableTo(record("a" to int(1)).referenceType()))
        assertEquals(false, FlixTupleRenderer().applicableTo(null))
    }

    // --- structs ----------------------------------------------------------------------------------

    @Test
    fun `a struct reads with the names its fields were given`() {
        val counter = struct("Counter{count,label}", int(3), string("hits"))

        assertEquals("""Counter { count = 3, label = "hits" }""", FlixStructRenderer().labelOf(counter))
        assertEquals(listOf("count", "label"), FlixStructRenderer().childNamesOf(counter))
    }

    @Test
    fun `without a record a struct keeps the positional names it has`() {
        // A build without `--Xdebug` records nothing, and the class names its fields by position.
        // That is what a reader had before, and it is still true.
        val counter = struct(recorded = null, int(3), string("hits"))

        assertEquals(listOf("field0", "field1"), FlixStructRenderer().childNamesOf(counter))
        assertEquals("""Struct${'$'}Int32${'$'}Obj { field0 = 3, field1 = "hits" }""", FlixStructRenderer().labelOf(counter))
    }

    @Test
    fun `a record of the wrong length names nothing rather than half of it`() {
        // The names are paired with the fields by position, so a list of the wrong length pairs them
        // wrongly from the first mismatch on. Half a struct named and half not is worse than none.
        val counter = struct("Counter{count}", int(3), string("hits"))

        assertEquals(listOf("field0", "field1"), FlixStructRenderer().childNamesOf(counter))
    }

    @Test
    fun `a struct is recognised by its class, and a tuple is not one`() {
        assertEquals(true, FlixStructRenderer().applicableTo(struct("C{a}", int(1)).referenceType()))
        assertEquals(false, FlixStructRenderer().applicableTo(tuple(int(1)).referenceType()))
        assertEquals(false, FlixStructRenderer().applicableTo(null))
    }

    @Test
    fun `a struct field is found by its name, not by where it happens to sit`() {
        // The rule the B+ tree walk rests on. In the tree the library builds, `keys` happens to be
        // `field1` -- so a walk that read position 1 and called it `keys` would agree with a walk
        // that read the name, and no live test could tell them apart. This one can.
        val node = struct("Node{size,keys,values}", int(2), string("K"), string("V"))

        assertEquals(2, (FlixStructs.field(node, "size") as com.sun.jdi.IntegerValue).value())
        assertEquals("K", (FlixStructs.field(node, "keys") as com.sun.jdi.StringReference).value())
        assertEquals("V", (FlixStructs.field(node, "values") as com.sun.jdi.StringReference).value())
    }

    @Test
    fun `a field nobody recorded is not found by guessing`() {
        // Without `--Xdebug` there are no names, and a reader that fell back to a position would be
        // reading whatever happened to be there.
        assertNull(FlixStructs.field(struct(recorded = null, int(2)), "size"))
        assertNull(FlixStructs.field(struct("Node{size}", int(2)), "keys"))
    }

    // --- function values -----------------------------------------------------------------------

    @Test
    fun `a capture is named where the compiler recorded a name`() {
        val closure = closure("Clo\$curriedMultiply\$Xb8Kaq73gD3", "x", int(6))

        assertEquals("fn curriedMultiply(x = 6)", FlixClosureRenderer().labelOf(closure))
        assertEquals(listOf("x"), FlixClosureRenderer().childNamesOf(closure))
    }

    @Test
    fun `a capture nobody named is shown by value alone`() {
        // `_` is what the compiler records for a capture the source never named, so that the list
        // still lines up with `clo0`, `clo1`. It is a position, not a name, and reads as one.
        val closure = closure("Clo\$main\$aBfCTPKjx6U", "_", int(6))

        assertEquals("fn main(6)", FlixClosureRenderer().labelOf(closure))
        assertEquals(listOf("[0]"), FlixClosureRenderer().childNamesOf(closure))
    }

    @Test
    fun `names that do not line up with the captures are not used at all`() {
        // The names are paired by position, so a list of the wrong length pairs them wrongly from
        // the first mismatch on -- the same rule as a struct's fields.
        val closure = closure("Clo\$both\$QhFZgVt8ipa", "a", int(1), int(2))

        assertEquals(listOf("[0]", "[1]"), FlixClosureRenderer().childNamesOf(closure))
    }

    @Test
    fun `without a record the captures keep their positions`() {
        val closure = closure("Clo\$curriedMultiply\$Xb8Kaq73gD3", recorded = null, int(6))

        assertEquals("fn curriedMultiply(6)", FlixClosureRenderer().labelOf(closure))
    }

    // --- anonymous objects --------------------------------------------------------------------

    @Test
    fun `an anonymous object reads as the type it implements`() {
        val cmp = anonymous(
            implements = "java.util.Comparator",
            methods = listOf("<init>", "compare", "lambda\$bridge"),
            closures = listOf(closure("Clo\$main\$dJc2zUjuyaG", "bias", int(7))),
        )

        assertEquals("new Comparator { compare }", FlixAnonymousRenderer().labelOf(cmp))
        assertEquals(listOf("compare"), FlixAnonymousRenderer().childNamesOf(cmp))
    }

    @Test
    fun `an anonymous object extending a class names the class`() {
        // An interface is implemented and a class is extended, so where there is no interface the
        // superclass is the answer.
        val list = anonymous(
            implements = null,
            extends = "java.util.AbstractList",
            methods = listOf("<init>", "get"),
            closures = listOf(closure("Clo\$main\$x", "_", int(1))),
        )

        assertEquals("new AbstractList { get }", FlixAnonymousRenderer().labelOf(list))
    }

    @Test
    fun `an object that implements nothing is not called an Object`() {
        // `java.lang.Object` is what a class implementing an interface extends, not a type anyone
        // wrote. With nothing else to go on the generated name is the honest answer.
        val opaque = anonymous(
            implements = null,
            extends = "java.lang.Object",
            methods = listOf("<init>", "run"),
            closures = listOf(closure("Clo\$main\$x", "_", int(1))),
        )

        assertEquals(true, FlixAnonymousRenderer().labelOf(opaque).startsWith("new Anon\$"))
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

    /** An anonymous object: one closure field per method it implements, plus the type it implements. */
    private fun anonymous(
        implements: String?,
        extends: String = "java.lang.Object",
        methods: List<String>,
        closures: List<Value>,
    ): ObjectReference = objectRef(
        "dev.flix.gen.Anon\$220078",
        closures.withIndex().associate { (i, v) -> "clo$i" to v },
        superclass = extends,
        interfaces = listOfNotNull(implements),
        methods = methods,
    )

    private fun FlixAnonymousRenderer.labelOf(value: Value): String =
        (valueLabelRenderer as FlixLabelRenderer).label(value)

    private fun FlixAnonymousRenderer.childNamesOf(value: Value): List<String> =
        (childrenRenderer as FlixChildrenRenderer).childrenOf(value).map { it.first }

    /**
     * A lifted lambda, with its captures in `clo0`, `clo1` and their names on the class.
     *
     * The names are a *static* field, which is what the renderer reads: a closure class belongs to
     * one lambda, so unlike a tag or a struct the names are the same for every instance.
     */
    private fun closure(className: String, recorded: String?, vararg captures: Value): ObjectReference {
        val fields = captures.withIndex().associate { (i, v) -> "clo$i" to v } + ("pc" to int(0))
        return objectRef(
            "dev.flix.gen.$className",
            fields,
            statics = listOfNotNull(recorded?.let { FlixValues.CAPTURE_NAMES_FIELD to string(it) }).toMap(),
        )
    }

    private fun FlixClosureRenderer.labelOf(value: Value): String =
        (valueLabelRenderer as FlixLabelRenderer).label(value)

    private fun FlixClosureRenderer.childNamesOf(value: Value): List<String> =
        (childrenRenderer as FlixChildrenRenderer).childrenOf(value).map { it.first }

    /** A struct, whose class carries only types; the names live in the value under `--Xdebug`. */
    private fun struct(recorded: String?, vararg fields: Value): ObjectReference {
        val types = fields.joinToString("$") { if (it is com.sun.jdi.IntegerValue) "Int32" else "Obj" }
        val values = fields.withIndex().associate { (i, v) -> "field$i" to v } +
            listOfNotNull(recorded?.let { FlixValues.STRUCT_NAME_FIELD to string(it) })
        return objectRef("dev.flix.gen.Struct\$$types", values)
    }

    private fun FlixStructRenderer.labelOf(value: Value): String =
        (valueLabelRenderer as FlixLabelRenderer).label(value)

    private fun FlixStructRenderer.childNamesOf(value: Value): List<String> =
        (childrenRenderer as FlixChildrenRenderer).childrenOf(value).map { it.first }

    private fun FlixStructRenderer.applicableTo(type: Type?): Boolean = ask(isApplicableChecker, type)

    /** A tuple, as `BackendObjType.Tuple` compiles one: a class per arity, fields `field0`, `field1`. */
    private fun tuple(vararg components: Value): ObjectReference {
        val types = components.joinToString("\$") { "Obj" }
        val fields = components.withIndex().associate { (i, v) -> "field$i" to v }
        return objectRef("dev.flix.gen.Tuple\$$types", fields)
    }

    private fun bool(value: Boolean): com.sun.jdi.BooleanValue = proxy(com.sun.jdi.BooleanValue::class.java) { method, _ ->
        when (method.name) {
            "value", "booleanValue" -> value
            "toString" -> value.toString()
            else -> null
        }
    }

    private fun FlixTupleRenderer.labelOf(value: Value): String =
        (valueLabelRenderer as FlixLabelRenderer).label(value)

    private fun FlixTupleRenderer.childNamesOf(value: Value): List<String> =
        (childrenRenderer as FlixChildrenRenderer).childrenOf(value).map { it.first }

    private fun FlixTupleRenderer.applicableTo(type: Type?): Boolean = ask(isApplicableChecker, type)

    /** A Flix list, as the `Cons` cells and the `Nil` a `--Xdebug` build produces. */
    private fun list(vararg elements: Value): ObjectReference {
        var rest = nil()
        for (element in elements.reversed()) {
            rest = tagged(
                "dev.flix.gen.Tag\$Obj\$Obj",
                ordinal = 1,
                payload = listOf(element, rest),
                // As the compiler writes it: monomorphised, hash and all.
                recordedTag = "List\$Vv4NSpVAmjE.Cons",
            )
        }
        return rest
    }

    private fun nil(): ObjectReference =
        tagged("dev.flix.gen.List\$Nil", ordinal = 0, payload = emptyList(), recordedTag = "List\$Vv4NSpVAmjE.Nil")

    private fun float(value: Float): com.sun.jdi.FloatValue = proxy(com.sun.jdi.FloatValue::class.java) { method, _ ->
        when (method.name) {
            "value", "floatValue" -> value
            "toString" -> value.toString()
            else -> null
        }
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
        statics: Map<String, Value?> = emptyMap(),
        methods: List<String> = emptyList(),
    ): ObjectReference {
        val id = nextId++
        // Read from the map on every call rather than captured once. A cyclic fixture is built by
        // adding the back-reference *after* the object exists, and a snapshot taken here would omit
        // it -- so the walk would stop for want of a field rather than because a guard stopped it,
        // and the cycle tests would pass while proving nothing.
        // A ClassType, not a bare ReferenceType: the renderers reach `Record$` through an interface
        // and `Tagged$` through a superclass, so a stub with no hierarchy would answer "not a Flix
        // value" for both and prove nothing.
        val type = proxy(ClassType::class.java) { method, args ->
            when (method.name) {
                "name" -> className
                "allFields", "fields" -> fields.keys.map { field(it) }
                "fieldByName" -> (fields.keys + statics.keys).firstOrNull { it == args?.get(0) }?.let { field(it) }
                // A static field is read off the *type*, which is where the platform looks for one.
                "getValue" -> statics[(args?.get(0) as? Field)?.name()]
                "superclass" -> superclass?.let { classType(it) }
                "interfaces" -> interfaces.map { interfaceType(it) }
                "methods" -> methods.map { declaredMethod(it) }
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

    /** A declared method, as JDI reports one: a name, and the flags that say what kind it is. */
    private fun declaredMethod(name: String): com.sun.jdi.Method = proxy(com.sun.jdi.Method::class.java) { method, _ ->
        when (method.name) {
            "name" -> name
            "isConstructor" -> name == "<init>"
            "isStaticInitializer" -> name == "<clinit>"
            "isBridge", "isSynthetic" -> name.endsWith("\$bridge")
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
