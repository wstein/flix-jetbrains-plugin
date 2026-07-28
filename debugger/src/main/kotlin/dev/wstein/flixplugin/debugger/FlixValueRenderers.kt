package dev.wstein.flixplugin.debugger

import com.intellij.debugger.engine.evaluation.EvaluationContext
import com.intellij.debugger.ui.tree.ValueDescriptor
import com.intellij.debugger.ui.tree.render.CompoundRendererProvider
import com.intellij.debugger.ui.tree.render.DescriptorLabelListener
import com.intellij.debugger.ui.tree.render.Renderer
import com.intellij.debugger.ui.tree.render.ValueLabelRenderer
import com.sun.jdi.ObjectReference
import com.sun.jdi.StringReference
import com.sun.jdi.Value
import org.jdom.Element

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

    override fun getClassName(): String = FlixValues.RECORD_TYPE

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
            if (node.referenceType().name() == FlixValues.RECORD_EMPTY_TYPE) break
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

    override fun getClassName(): String = FlixValues.TAGGED_TYPE

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
