package dev.wstein.flixplugin.debugger

import com.intellij.debugger.engine.evaluation.EvaluationContext
import com.intellij.debugger.ui.tree.ValueDescriptor
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

    override fun getValueLabelRenderer(): ValueLabelRenderer =
        object : FlixLabelRenderer("FlixRecord") {
            override fun label(value: Value?): String = renderRecord(value)
        }

    private fun renderRecord(value: Value?): String {
        val start = value as? ObjectReference ?: return ""
        val fields = mutableListOf<Pair<String, String>>()
        var node: ObjectReference? = start
        var truncated = false

        while (node != null) {
            if (FlixValues.simpleNameOf(node.referenceType().name()) == FlixValues.RECORD_EMPTY_TYPE) break
            if (fields.size == FlixValues.MAX_RECORD_FIELDS) {
                truncated = true
                break
            }
            val label = node.readField("label")?.let(::renderScalar) ?: break
            val fieldValue = node.readField("value")?.let(::renderScalar) ?: "?"
            fields += label to fieldValue
            node = node.readField("rest") as? ObjectReference
        }
        return FlixValues.formatRecord(fields, truncated)
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

    override fun getValueLabelRenderer(): ValueLabelRenderer =
        object : FlixLabelRenderer("FlixTagged") {
            override fun label(value: Value?): String = renderTagged(value)
        }

    private fun renderTagged(value: Value?): String {
        val tagged = value as? ObjectReference ?: return ""
        val type = tagged.referenceType()

        // v0, v1, ... in declaration order. Reading the field list rather than probing names in a
        // loop keeps a tag with no payload from costing a failed lookup.
        val payload = type.allFields()
            .filter { it.name().matches(PAYLOAD_FIELD) }
            .sortedBy { it.name().drop(1).toIntOrNull() ?: 0 }
            .map { renderScalar(tagged.getValue(it)) }

        val ordinal = (tagged.readField("ordinal") as? com.sun.jdi.IntegerValue)?.value()
        return FlixValues.formatTagged(type.name(), payload, ordinal)
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
