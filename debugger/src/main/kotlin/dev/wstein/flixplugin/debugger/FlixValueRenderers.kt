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
    ): String = runCatching { label(descriptor.value) }.getOrDefault("")

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
    override fun getIsApplicableChecker(): Function<Type, CompletableFuture<Boolean>> =
        Function { type -> completedFuture(FlixValues.isA(supertypesOf(type), FlixValues.RECORD_TYPE)) }

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
    private fun fieldsOf(value: Value?, limit: Int): Pair<List<Pair<String, Value?>>, Boolean> {
        val fields = mutableListOf<Pair<String, Value?>>()
        var node = value as? ObjectReference
        val seen = mutableSetOf<Long>()

        while (node != null) {
            if (FlixValues.simpleNameOf(node.referenceType().name()) == FlixValues.RECORD_EMPTY_TYPE) break
            // A record is immutable and cannot be cyclic, but a debuggee mid-construction or simply
            // corrupt can be, and a renderer that hangs takes the variables view with it.
            if (!seen.add(node.uniqueID())) break
            if (fields.size == limit) return fields to true
            // Read as text, not through `renderScalar`, which quotes a string: a field is named
            // `name`, not `"name"`, and the quotes would reach both the label and the tree.
            val label = (node.readField("label") as? StringReference)?.value() ?: break
            fields += label to node.readField("value")
            node = node.readField("rest") as? ObjectReference
        }
        return fields to false
    }

    private fun renderRecord(value: Value?): String {
        if (value !is ObjectReference) return ""
        val (fields, truncated) = fieldsOf(value, FlixValues.MAX_RECORD_FIELDS)
        return FlixValues.formatRecord(fields.map { (name, v) -> name to renderScalar(v) }, truncated)
    }

    public override fun getChildrenRenderer(): ChildrenRenderer =
        object : FlixChildrenRenderer("FlixRecordChildren") {
            override fun childrenOf(value: Value?): List<Pair<String, Value?>> =
                fieldsOf(value, Int.MAX_VALUE).first
        }
}

/**
 * Renders a Flix tagged-union value as `Some(42)` or `NoneLeft` rather than
 * `{Chain$dotViewLeft$405229$NoneLeft@5678}`.
 *
 * The tag is the last segment of the class name. Values compiled to a shared representation --
 * `Tag$Bool`, `Tag$Char$Obj` -- carry no tag name, so those fall back to the `ordinal`, which is
 * the only discriminator they have.
 */
class FlixTaggedRenderer : CompoundRendererProvider() {

    override fun getName(): String = "Flix tagged union"

    override fun getClassName(): String = FlixValues.GEN_PACKAGE + FlixValues.TAGGED_TYPE

    /** Matches on the simple name; see [FlixRecordRenderer.getIsApplicableChecker]. */
    override fun getIsApplicableChecker(): Function<Type, CompletableFuture<Boolean>> =
        Function { type -> completedFuture(FlixValues.isA(supertypesOf(type), FlixValues.TAGGED_TYPE)) }

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
        val ordinal = (tagged.readField("ordinal") as? com.sun.jdi.IntegerValue)?.value()
        return FlixValues.formatTagged(
            tagged.referenceType().name(),
            payloadOf(tagged).map { (_, v) -> renderScalar(v) },
            ordinal,
        )
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
                val payload = payloadOf(tagged)
                if (FlixValues.tagNameOf(tagged.referenceType().name()) != null) return payload
                val ordinal = tagged.referenceType().allFields().firstOrNull { it.name() == "ordinal" }
                return payload + listOfNotNull(ordinal?.let { "ordinal" to tagged.getValue(it) })
            }
        }

    private companion object {
        private val PAYLOAD_FIELD = Regex("""v\d+""")
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
    is ObjectReference -> {
        val typeName = value.referenceType().name()
        FlixValues.tagNameOf(typeName) ?: typeName.substringAfterLast('.').substringAfterLast('$')
    }
    else -> value.toString()
}
