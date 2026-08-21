package dev.wstein.flixplugin.debugger

import com.intellij.debugger.DebuggerContext
import com.intellij.debugger.engine.evaluation.EvaluateException
import com.intellij.debugger.engine.evaluation.EvaluationContext
import com.intellij.debugger.engine.evaluation.EvaluationContextImpl
import com.intellij.debugger.ui.impl.watch.ValueDescriptorImpl
import com.intellij.debugger.ui.tree.DebuggerTreeNode
import com.intellij.debugger.ui.tree.NodeDescriptor
import com.intellij.debugger.ui.tree.ValueDescriptor
import com.intellij.debugger.ui.tree.render.ChildrenBuilder
import com.intellij.debugger.ui.tree.render.ChildrenRenderer
import com.intellij.debugger.ui.tree.render.CompoundRendererProvider
import com.intellij.debugger.ui.tree.render.DescriptorLabelListener
import com.intellij.debugger.ui.tree.render.Renderer
import com.intellij.debugger.ui.tree.render.ValueLabelRenderer
import com.sun.jdi.ClassNotPreparedException
import com.sun.jdi.ClassType
import com.sun.jdi.InterfaceType
import com.sun.jdi.CharValue
import com.sun.jdi.ObjectReference
import com.sun.jdi.ReferenceType
import com.sun.jdi.StringReference
import com.sun.jdi.Type
import com.sun.jdi.Value
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiExpression
import org.jdom.Element
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletableFuture.completedFuture
import java.util.function.Function

/**
 * A [ValueLabelRenderer] that only has to compute a label.
 *
 * The interface also carries `getUniqueId`, `clone` and JDOM serialization, none of which mean
 * anything for a renderer that holds no configurable state: there is nothing for a user to change,
 * so there is nothing to persist or copy. Gathering that boilerplate here keeps the two renderers
 * below to the part that differs.
 */
internal abstract class FlixLabelRenderer(private val id: String) : ValueLabelRenderer {

    abstract fun label(value: Value?): String

    final override fun calcLabel(
        descriptor: ValueDescriptor,
        context: EvaluationContext?,
        listener: DescriptorLabelListener?,
    ): String {
        val text = runCatching { label(descriptor.value) }.getOrDefault("")
        if (text.isNotEmpty()) {
            hideCompiledIdentity(descriptor)
        }
        return text
    }

    /**
     * Drops the `{RecordExtend$Obj@1127}` the platform puts in front of a rendered value.
     *
     * `ValueDescriptorImpl.getValueLabel` composes `"{" + idLabel + "}" + valueText`, and the id
     * label is set from the renderer just before this runs, so clearing it here is what removes it,
     * and only for values a Flix renderer claimed.
     *
     * Dropped rather than rewritten because it names a class the programmer never wrote:
     * `RecordExtend$Obj` is the compiled shape of a record, and `Tag$Obj$Obj` is one class serving
     * every two-field tag. Neither is the value's type in Flix, and printing a Java-looking type
     * beside a Flix-looking value invites the reader to write the Java one in a watch.
     *
     * Only when a label was produced. An empty one means the renderer failed, and there the identity
     * is the only thing left that says anything at all.
     */
    private fun hideCompiledIdentity(descriptor: ValueDescriptor) {
        runCatching { descriptor.setIdLabel(null) }
    }

    final override fun getUniqueId(): String = id

    /** Stateless, so a copy is the same object. */
    final override fun clone(): Renderer = this

    final override fun readExternal(element: Element) = Unit

    final override fun writeExternal(element: Element) = Unit
}

/**
 * A child node with a name this plugin chooses and a value it already holds.
 *
 * The compiled shape of a Flix value is not the shape the programmer wrote, so the names in the
 * tree have to be supplied rather than read off fields: a record's fields live in the `label` of
 * each link in a chain, not in the JVM field names, which are `label`, `value` and `rest` all the
 * way down.
 *
 * Deliberately not `UserExpressionData`, which is how the platform's own "Customize Data Views"
 * children work. That evaluates a Java expression in the debuggee per child, and reaching a record
 * field would mean a cast per link -- `((RecordExtend$Obj)((RecordExtend$Int32)this.rest).rest)` --
 * built from each link's runtime type, and a debuggee evaluation for a value the renderer is
 * already holding. Precomputed values cost nothing and cannot be wrong.
 *
 * `ValueDescriptorImpl` is not internal API; three of its methods are, and none is used here.
 */
internal class FlixNamedValue(
    project: Project,
    private val fieldName: String,
    value: Value?,
) : ValueDescriptorImpl(project, value) {

    override fun getName(): String = fieldName

    override fun calcValue(context: EvaluationContextImpl?): Value? = value

    /**
     * Refused, rather than fabricated.
     *
     * This is what "Evaluate expression" and "Copy value" ask for: a Java expression that reproduces
     * the node. There is none -- the name is a Flix record label or a tag position, and neither
     * exists in the debuggee's Java namespace. Returning a plausible-looking expression would give
     * a value that is silently unrelated to the node it was read from.
     */
    override fun getDescriptorEvaluation(context: DebuggerContext?): PsiExpression =
        throw EvaluateException("A Flix ${'$'}fieldName has no Java expression to evaluate")
}

/**
 * A [ChildrenRenderer] over children this plugin computes, with nothing else to configure.
 *
 * Gathers the parts of the interface that mean nothing for a renderer holding no state, so the two
 * below are only the rule for turning a value into named children.
 */
internal abstract class FlixChildrenRenderer(private val id: String) : ChildrenRenderer {

    /** The children of `value`, as name/value pairs in display order. */
    abstract fun childrenOf(value: Value?): List<Pair<String, Value?>>

    final override fun buildChildren(value: Value?, builder: ChildrenBuilder, context: EvaluationContext) {
        val project = context.project ?: return builder.setChildren(emptyList())
        val nodes = runCatching { childrenOf(value) }.getOrDefault(emptyList())
            .map { (name, child) -> builder.nodeManager.createNode(FlixNamedValue(project, name, child), context) }
        builder.setChildren(nodes)
    }

    /**
     * `isExpandableAsync`, not `isExpandable`: the latter is deprecated and its default throws
     * `AbstractMethodError`, so the async form is the one to answer. Nothing here needs the
     * debuggee, so the answer is already known.
     */
    final override fun isExpandableAsync(
        value: Value?,
        context: EvaluationContext?,
        parent: NodeDescriptor?,
    ): CompletableFuture<Boolean> =
        completedFuture(runCatching { childrenOf(value).isNotEmpty() }.getOrDefault(false))

    /** See [FlixNamedValue.getDescriptorEvaluation]: a Flix child has no Java expression. */
    final override fun getChildValueExpression(node: DebuggerTreeNode?, context: DebuggerContext?): PsiExpression? = null

    final override fun getUniqueId(): String = id

    /** Stateless, so a copy is the same object. */
    final override fun clone(): Renderer = this

    final override fun readExternal(element: Element) = Unit

    final override fun writeExternal(element: Element) = Unit
}

/**
 * The fields of a Flix record, flattened out of the `label`/`value`/`rest` chain.
 *
 * Shared by the renderer, which shows them, and the evaluator, which projects one out of them. One
 * walk, so a field the tree displays is by construction the field `#` reaches.
 *
 * @param limit how many fields to take. The renderer's label is a summary and stops early; the tree
 *              and the evaluator take them all.
 * @return the fields in declaration order, and whether [limit] cut the walk short.
 */
internal fun recordFields(value: Value?, limit: Int): Pair<List<Pair<String, Value?>>, Boolean> {
    val fields = mutableListOf<Pair<String, Value?>>()
    var node = value as? ObjectReference
    val seen = mutableSetOf<Long>()

    while (node != null) {
        if (FlixValues.simpleNameOf(node.referenceType().name()) == FlixValues.RECORD_EMPTY_TYPE) break
        // A record is immutable and cannot be cyclic, but a debuggee mid-construction or simply
        // corrupt can be, and a walk that hangs takes the variables view with it.
        if (!seen.add(node.uniqueID())) break
        if (fields.size == limit) return fields to true
        // Read as text, not through `renderScalar`, which quotes a string: a field is named `name`,
        // not `"name"`, and the quotes would reach the label, the tree and any `#` lookup.
        val label = (node.readField("label") as? StringReference)?.value() ?: break
        fields += label to node.readField("value")
        node = node.readField("rest") as? ObjectReference
    }
    return fields to false
}

/**
 * `type` and every supertype above it, by JDI name.
 *
 * Breadth-first over both superclasses and interfaces, because a Flix record reaches `Record$`
 * through an interface while a tagged value reaches `Tagged$` through its superclass. Names are
 * deduplicated: the interface graph is a DAG, and a diamond would otherwise be walked twice.
 *
 * A class that is not prepared yet contributes what is known and stops. Its supertypes cannot be
 * read, and a renderer that threw here would break the whole variables view rather than one node.
 */
internal fun supertypesOf(type: Type?): Sequence<String> = sequence {
    val queue = ArrayDeque<ReferenceType>()
    (type as? ReferenceType)?.let { queue += it }
    val seen = mutableSetOf<String>()
    while (queue.isNotEmpty()) {
        val current = queue.removeFirst()
        val name = current.name()
        if (!seen.add(name)) continue
        yield(name)
        val parents = try {
            when (current) {
                is ClassType -> listOfNotNull(current.superclass()) + current.interfaces()
                is InterfaceType -> current.superinterfaces()
                else -> emptyList()
            }
        } catch (_: ClassNotPreparedException) {
            emptyList()
        }
        queue += parents
    }
}

/**
 * Renders a Flix record as `{ name = "Ada", age = 36 }` rather than `{RecordExtend$Obj@1234}`.
 *
 * A record compiles to a linked list: each node carries `label`, `value` and `rest`, ending at
 * `RecordEmpty$`. Every field is therefore reachable in the tree already -- what is missing is the
 * summary, which is the line the user actually reads.
 *
 * Registered against the `Record$` interface, so it covers every `RecordExtend$…` specialisation
 * without naming them.
 */
class FlixRecordRenderer : CompoundRendererProvider() {

    override fun getName(): String = "Flix record"

    override fun getClassName(): String = FlixValues.GEN_PACKAGE + FlixValues.RECORD_TYPE

    /**
     * Matches on the simple name, anywhere in the hierarchy.
     *
     * [getClassName] alone would not: the platform compares JDI's fully qualified name verbatim
     * (`DebuggerUtils.typeEquals`), so a renderer named for a package the compiler later moves stops
     * applying and says nothing. That is exactly what happened when generated classes moved into
     * `dev.flix.gen`. The name is still declared above because the settings UI shows it.
     */
    public override fun getIsApplicableChecker(): Function<Type, CompletableFuture<Boolean>> =
        // `type: Type?` is load-bearing. The platform asks every renderer about every value,
        // including one whose value is `null` and whose type is therefore `null` too. Kotlin's SAM
        // conversion takes the Java parameter as non-null and inserts a check, so the lambda threw
        // an NPE for each of those -- and the platform reports a renderer that throws as
        // "Internal error. See logs for more details" on the node, not on the renderer.
        Function { type: Type? -> completedFuture(FlixValues.isA(supertypesOf(type), FlixValues.RECORD_TYPE)) }

    override fun isEnabled(): Boolean = true

    public override fun getValueLabelRenderer(): ValueLabelRenderer =
        object : FlixLabelRenderer("FlixRecord") {
            override fun label(value: Value?): String = renderRecord(value)
        }

    /**
     * The fields of a record, flattened out of the `label`/`value`/`rest` chain.
     *
     * Expanding a record used to walk that chain a link at a time -- `label`, `value`, `rest`, then
     * `rest` again -- so reading the third field of a record meant opening three nested nodes whose
     * names said nothing about the record. The chain is the compiled form, not the value.
     *
     * @param limit how many fields to take. The label is a summary and stops at
     *              [FlixValues.MAX_RECORD_FIELDS]; the tree is the data and takes them all.
     */
    private fun renderRecord(value: Value?): String {
        if (value !is ObjectReference) return ""
        val (fields, truncated) = recordFields(value, FlixValues.MAX_RECORD_FIELDS)
        return FlixValues.formatRecord(fields.map { (name, v) -> name to renderScalar(v) }, truncated)
    }

    public override fun getChildrenRenderer(): ChildrenRenderer =
        object : FlixChildrenRenderer("FlixRecordChildren") {
            override fun childrenOf(value: Value?): List<Pair<String, Value?>> =
                recordFields(value, Int.MAX_VALUE).first
        }
}

/**
 * Renders a Flix struct as `Counter { count = 3, label = "hits" }` rather than
 * `{Struct$Int32$Obj@3596}` over `field0`, `field1`.
 *
 * A struct's class is shared by every struct of the same erased shape and names its fields by
 * position, so the class says how many fields there are and nothing else -- which is why a channel
 * read as `MpmcAdmin(1, ReentrantLock, false, 1, …)` and the standard library's B+ tree could not be
 * walked at all. A `--Xdebug` build records the struct's name and its field names in the value
 * itself (`BackendObjType.Struct.NameField`), in `field` order, and this pairs them up.
 *
 * Without that record there is nothing to pair: the fields keep their positional names, which is
 * what they had before.
 */
class FlixStructRenderer : CompoundRendererProvider() {

    override fun getName(): String = "Flix struct"

    override fun getClassName(): String = FlixValues.GEN_PACKAGE + FlixValues.STRUCT_PREFIX

    public override fun getIsApplicableChecker(): Function<Type, CompletableFuture<Boolean>> =
        Function { type: Type? -> completedFuture(type != null && FlixValues.isStruct(type.name())) }

    override fun isEnabled(): Boolean = true

    public override fun getValueLabelRenderer(): ValueLabelRenderer =
        object : FlixLabelRenderer("FlixStruct") {
            override fun label(value: Value?): String = renderStruct(value)
        }

    public override fun getChildrenRenderer(): ChildrenRenderer =
        object : FlixChildrenRenderer("FlixStructChildren") {
            override fun childrenOf(value: Value?): List<Pair<String, Value?>> =
                structFields(value as? ObjectReference ?: return emptyList())
        }

    private fun renderStruct(value: Value?): String {
        val struct = value as? ObjectReference ?: return ""
        val fields = structFields(struct)
        val name = FlixValues.structNameOf(recordedStructOf(struct))?.first
            ?: FlixValues.simpleNameOf(struct.referenceType().name())
        val shown = fields.take(FlixValues.MAX_RECORD_FIELDS)
        return FlixValues.formatStruct(
            name,
            shown.map { (field, v) -> field to renderScalar(v) },
            truncated = fields.size > shown.size,
        )
    }

    /**
     * The fields, named where a name was recorded and by position where none was.
     *
     * Paired by position rather than by looking a name up: the class names its fields `field0`,
     * `field1` in the order the struct declares them, and the recorded names are in that same
     * order. A recorded list of the wrong length is ignored rather than partially applied -- half a
     * struct named and half not is worse than none of it named.
     */
    private fun structFields(struct: ObjectReference): List<Pair<String, Value?>> {
        val values = struct.referenceType().allFields()
            .mapNotNull { field ->
                FlixValues.TUPLE_FIELD.matchEntire(field.name())?.groupValues?.get(1)?.toIntOrNull()
                    ?.let { index -> index to field }
            }
            .sortedBy { it.first }
        val names = FlixValues.structNameOf(recordedStructOf(struct))?.second
            ?.takeIf { it.size == values.size }
        return values.mapIndexed { position, (index, field) ->
            (names?.getOrNull(position) ?: "field$index") to struct.getValue(field)
        }
    }
}

/**
 * Renders a Flix tuple as `(1, 2)` rather than `{Tuple$Int32$Int32@3405}`.
 *
 * A tuple is the one Flix value whose compiled form says everything about it: `BackendObjType.Tuple`
 * gives each arity and component typing its own class, `dev.flix.gen.Tuple$Int32$Int32`, with fields
 * `field0`, `field1` and so on. There is no shared representation to disambiguate and no tag to
 * read -- the class *is* the type, which is why this renderer needs nothing from `--Xdebug`.
 *
 * A tuple has no field names in the source either, so its components are shown by position, the way
 * a list's elements are.
 */
class FlixTupleRenderer : CompoundRendererProvider() {

    override fun getName(): String = "Flix tuple"

    override fun getClassName(): String = FlixValues.GEN_PACKAGE + "Tuple\$"

    /** Matches on the class name, which is where a tuple's identity lives; see [FlixRecordRenderer]. */
    public override fun getIsApplicableChecker(): Function<Type, CompletableFuture<Boolean>> =
        Function { type: Type? -> completedFuture(type != null && FlixValues.isTuple(type.name())) }

    override fun isEnabled(): Boolean = true

    public override fun getValueLabelRenderer(): ValueLabelRenderer =
        object : FlixLabelRenderer("FlixTuple") {
            override fun label(value: Value?): String =
                FlixValues.formatTuple(componentsOf(value).map { (_, v) -> renderScalar(v) })
        }

    public override fun getChildrenRenderer(): ChildrenRenderer =
        object : FlixChildrenRenderer("FlixTupleChildren") {
            override fun childrenOf(value: Value?): List<Pair<String, Value?>> = componentsOf(value)
        }

    /**
     * The components, in source order.
     *
     * Sorted by the number in the field name rather than by the order JDI lists fields in: that
     * order is the class file's, which is the order the compiler wrote them and not something to
     * depend on for a value the user reads positionally.
     */
    private fun componentsOf(value: Value?): List<Pair<String, Value?>> {
        val tuple = value as? ObjectReference ?: return emptyList()
        return tuple.referenceType().allFields()
            .mapNotNull { field ->
                FlixValues.TUPLE_FIELD.matchEntire(field.name())?.groupValues?.get(1)?.toIntOrNull()
                    ?.let { index -> index to field }
            }
            .sortedBy { it.first }
            .map { (index, field) -> "[$index]" to tuple.getValue(field) }
    }
}

/**
 * Renders a Flix tagged-union value as `Some(42)` or `NoneLeft` rather than
 * `{Chain$dotViewLeft$405229$NoneLeft@5678}`.
 *
 * A case *without* terms has a class of its own, so its name is the last segment of the class name.
 * A case *with* terms does not: it is compiled to a representation shared by every case of the same
 * erased shape -- `Tag$Bool`, `Tag$Char$Obj` -- where the class says nothing and only an ordinal
 * separates one case from another.
 *
 * For those, a `--Xdebug` build records the name in the value itself
 * ([FlixValues.TAG_NAME_FIELD]), which is why `Some("/home/…")` reads as itself rather than as
 * `#1("/home/…")`. An optimized build has no such field and falls back to the ordinal, which is
 * then genuinely all the value knows about itself.
 */
class FlixTaggedRenderer : CompoundRendererProvider() {

    override fun getName(): String = "Flix tagged union"

    override fun getClassName(): String = FlixValues.GEN_PACKAGE + FlixValues.TAGGED_TYPE

    /** Matches on the simple name; see [FlixRecordRenderer.getIsApplicableChecker]. */
    public override fun getIsApplicableChecker(): Function<Type, CompletableFuture<Boolean>> =
        // `type: Type?` is load-bearing. The platform asks every renderer about every value,
        // including one whose value is `null` and whose type is therefore `null` too. Kotlin's SAM
        // conversion takes the Java parameter as non-null and inserts a check, so the lambda threw
        // an NPE for each of those -- and the platform reports a renderer that throws as
        // "Internal error. See logs for more details" on the node, not on the renderer.
        Function { type: Type? -> completedFuture(FlixValues.isA(supertypesOf(type), FlixValues.TAGGED_TYPE)) }

    override fun isEnabled(): Boolean = true

    public override fun getValueLabelRenderer(): ValueLabelRenderer =
        object : FlixLabelRenderer("FlixTagged") {
            override fun label(value: Value?): String = renderTagged(value)
        }

    /**
     * The tag's payload, in declaration order.
     *
     * Reading the field list rather than probing `v0`, `v1`, … in a loop keeps a tag with no
     * payload from costing a failed lookup.
     */
    private fun payloadOf(tagged: ObjectReference): List<Pair<String, Value?>> =
        tagged.referenceType().allFields()
            .filter { it.name().matches(PAYLOAD_FIELD) }
            .sortedBy { it.name().drop(1).toIntOrNull() ?: 0 }
            .map { it.name() to tagged.getValue(it) }

    private fun renderTagged(value: Value?): String {
        val tagged = value as? ObjectReference ?: return ""
        listElements(tagged, FlixValues.MAX_LIST_ELEMENTS)?.let { (elements, end) ->
            return FlixValues.formatList(elements.map { renderScalar(it) }, end)
        }
        // A Datalog program and its constraints, written back out as the program they are. Before
        // the tag rendering, since both are ordinary tagged values and would otherwise read as
        // `Datalog([], [])` and `Constraint(HeadAtom, [])`.
        if (FlixDatalog.isProgram(tagged)) {
            return FlixDatalog.renderProgram(tagged, FlixValues.MAX_LIST_ELEMENTS)
        }
        if (FlixDatalog.isModel(tagged)) {
            return FlixDatalog.renderModel(tagged, FlixValues.MAX_LIST_ELEMENTS)
        }
        FlixDatalog.renderConstraint(tagged)?.let { return it }
        FlixDatalog.renderRelSym(tagged)?.let { return it }
        if (FlixCollections.isMap(tagged)) {
            val (entries, truncated) = FlixCollections.entries(tagged, FlixValues.MAX_LIST_ELEMENTS)
            return FlixValues.formatMap(entries.map { (k, v) -> renderScalar(k) to renderScalar(v) }, truncated)
        }
        if (FlixCollections.isSet(tagged)) {
            val (entries, truncated) = FlixCollections.entries(tagged, FlixValues.MAX_LIST_ELEMENTS)
            // A set stores a unit beside each element; the element is the key.
            return FlixValues.formatSet(entries.map { (element, _) -> renderScalar(element) }, truncated)
        }
        val ordinal = (tagged.readField("ordinal") as? com.sun.jdi.IntegerValue)?.value()
        return FlixValues.formatTagged(
            tagged.referenceType().name(),
            payloadOf(tagged).map { (_, v) -> renderScalar(v) },
            ordinal,
            recordedTagOf(tagged),
        )
    }

    /**
     * The elements of a Flix list, and whether the walk reached `Nil`.
     *
     * `null` when this is not a list, which is the interesting part of the rule: a list is a chain
     * of `List.Cons` cells, and *only* the case name the compiler recorded says so. The compiled
     * form cannot: a two-term case is built into a class shared with every other two-term case, so
     * a structural guess -- "a tag with two terms whose second is another such tag" -- would catch
     * any user-defined pair that happened to nest. Without `--Xdebug` there is no recorded name and
     * a list renders as the `Cons` chain it is, which is what it did before.
     */
    private fun listElements(value: ObjectReference, limit: Int): Pair<List<Value?>, FlixListEnd>? {
        if (FlixValues.tagOf(value.referenceType().name(), recordedTagOf(value)) !in LIST_CASES) return null
        val elements = mutableListOf<Value?>()
        val seen = mutableSetOf<Long>()
        var node: ObjectReference? = value
        while (node != null && elements.size < limit) {
            val case = FlixValues.tagOf(node.referenceType().name(), recordedTagOf(node))
            if (case == FlixValues.LIST_NIL) return elements to FlixListEnd.NIL
            if (case != FlixValues.LIST_CONS) return elements to FlixListEnd.BROKEN
            // A list is immutable and cannot be circular, but a debuggee caught mid-construction or
            // simply corrupt can be, and this runs on the debugger thread while the UI waits.
            if (!seen.add(node.uniqueID())) return elements to FlixListEnd.BROKEN
            val terms = payloadOf(node)
            elements += terms.getOrNull(0)?.second
            node = terms.getOrNull(1)?.second as? ObjectReference
        }
        return elements to FlixListEnd.TRUNCATED
    }

    /**
     * The payload, and nothing else.
     *
     * `ordinal` is dropped: it is the discriminator the label already used, and a reader who has
     * been shown `InvaderBlast` gains nothing from being shown `0` beside it. It stays visible for
     * the shared representations, where the label *is* the ordinal, because there it is the only
     * thing distinguishing one value from another.
     */
    public override fun getChildrenRenderer(): ChildrenRenderer =
        object : FlixChildrenRenderer("FlixTaggedChildren") {
            override fun childrenOf(value: Value?): List<Pair<String, Value?>> {
                val tagged = value as? ObjectReference ?: return emptyList()
                // A list expands to its elements. Expanding the `Cons` chain instead meant one
                // nested node per element, each named `v1` after the field holding the rest --
                // reading the fourth element took four clicks and named none of them.
                listElements(tagged, Int.MAX_VALUE)?.let { (elements, _) ->
                    return elements.mapIndexed { index, element -> "[$index]" to element }
                }
                // A program expands to its constraints, each of which renders as itself.
                if (FlixDatalog.isProgram(tagged)) {
                    return FlixDatalog.constraints(tagged, Int.MAX_VALUE).first
                        .mapIndexed { index, constraint -> "[$index]" to constraint }
                }
                // A model expands to one node per relation, holding that relation's own value --
                // which renders as its facts, so the tree is never shown as a tree.
                if (FlixDatalog.isModel(tagged)) {
                    return FlixDatalog.relations(tagged, Int.MAX_VALUE).first
                }
                // A map expands to its entries, named by key: the key is what a reader is looking
                // for, and a positional name would send them counting.
                if (FlixCollections.isMap(tagged)) {
                    return FlixCollections.entries(tagged, Int.MAX_VALUE).first
                        .map { (key, value) -> renderScalar(key) to value }
                }
                if (FlixCollections.isSet(tagged)) {
                    return FlixCollections.entries(tagged, Int.MAX_VALUE).first
                        .mapIndexed { index, (element, _) -> "[$index]" to element }
                }
                val payload = payloadOf(tagged)
                if (FlixValues.tagOf(tagged.referenceType().name(), recordedTagOf(tagged)) != null) return payload
                val ordinal = tagged.referenceType().allFields().firstOrNull { it.name() == "ordinal" }
                return payload + listOfNotNull(ordinal?.let { "ordinal" to tagged.getValue(it) })
            }
        }

    private companion object {
        private val PAYLOAD_FIELD = Regex("""v\d+""")

        /** The two cases a list is made of; anything else is rendered as the tag it is. */
        private val LIST_CASES = setOf(FlixValues.LIST_CONS, FlixValues.LIST_NIL)
    }
}

/**
 * A field's value, or `null` if this object has no such field.
 *
 * Returns `null` rather than throwing: a renderer runs on the debugger thread against whatever the
 * VM happens to hold, and a value of an unexpected shape should degrade to a plainer label rather
 * than fail the variables view.
 */
internal fun ObjectReference.readField(name: String): Value? =
    runCatching { referenceType().fieldByName(name)?.let(::getValue) }.getOrNull()

/**
 * A short rendering of a nested value.
 *
 * Deliberately shallow. A label summarises; expanding the node shows the real structure, and
 * recursing here would turn one line into an unbounded walk of the object graph on the debugger
 * thread.
 */
internal fun renderScalar(value: Value?): String = when (value) {
    null -> "null"
    is StringReference -> "\"${value.value()}\""
    // Quoted, because a bare `c` reads as a name where a string is already unambiguous.
    is CharValue -> "'${value.value()}'"
    // Numbers are printed as they are, without the `f32`/`i64` suffix that would say which width
    // they are. The width is not what a reader is looking at, and a value is easier to compare with
    // the source when it is spelled the way the source spells it.
    is ObjectReference -> {
        val typeName = value.referenceType().name()
        when {
            // Shallow, like every other nested value here: a tuple of tuples shows the inner ones
            // by class, and expanding the node is what shows the rest.
            FlixValues.isTuple(typeName) -> FlixValues.formatTuple(tupleComponents(value).map(::renderComponent))
            else -> FlixValues.displayTagOf(typeName, recordedTagOf(value))
                ?: typeName.substringAfterLast('.').substringAfterLast('$')
        }
    }
    else -> value.toString()
}

/** A tuple's components in source order, for a caller that only needs the values. */
private fun tupleComponents(tuple: ObjectReference): List<Value?> =
    tuple.referenceType().allFields()
        .mapNotNull { field ->
            FlixValues.TUPLE_FIELD.matchEntire(field.name())?.groupValues?.get(1)?.toIntOrNull()
                ?.let { it to field }
        }
        .sortedBy { it.first }
        .map { (_, field) -> tuple.getValue(field) }

/** One component of a nested tuple: rendered, but not recursed into any further. */
private fun renderComponent(value: Value?): String = when (value) {
    is ObjectReference -> value.referenceType().name().substringAfterLast('.').substringAfterLast('$')
    else -> renderScalar(value)
}

/** The struct identity a `--Xdebug` build wrote into `value`, or `null` if it carries none. */
internal fun recordedStructOf(value: ObjectReference): String? =
    (value.readField(FlixValues.STRUCT_NAME_FIELD) as? StringReference)?.value()

/** The case name a `--Xdebug` build wrote into `value`, or `null` if it carries none. */
internal fun recordedTagOf(value: ObjectReference): String? =
    (value.readField(FlixValues.TAG_NAME_FIELD) as? StringReference)?.value()
