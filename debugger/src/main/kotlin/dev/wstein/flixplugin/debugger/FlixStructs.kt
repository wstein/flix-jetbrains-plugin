package dev.wstein.flixplugin.debugger

import com.sun.jdi.ObjectReference
import com.sun.jdi.Value

/**
 * A struct's fields, by the names it carries.
 *
 * A struct is compiled to a class shared by every struct of the same erased shape, whose fields are
 * `field0`, `field1` by position -- so the names exist only where a `--Xdebug` build recorded them,
 * in the value itself. Reading a struct by name rather than by index is what keeps a reader of the
 * standard library's own structures honest: the B+ tree behind a solved relation is walked through
 * `keys`, `values` and `size`, and a field that moves changes the answer to `null` rather than
 * quietly transposing the data.
 */
internal object FlixStructs {

    /** The value of `name` in `struct`, or `null` if it carries no such name. */
    fun field(struct: ObjectReference, name: String): Value? {
        val names = FlixValues.structNameOf(recordedStructOf(struct))?.second ?: return null
        val index = names.indexOf(name)
        if (index < 0) return null
        val field = runCatching { struct.referenceType().fieldByName("field$index") }.getOrNull() ?: return null
        return runCatching { struct.getValue(field) }.getOrNull()
    }
}
