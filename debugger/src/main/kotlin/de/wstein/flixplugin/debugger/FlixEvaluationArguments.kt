package de.wstein.flixplugin.debugger

import com.sun.jdi.ArrayReference
import com.sun.jdi.ObjectReference
import com.sun.jdi.ObjectCollectedException
import com.sun.jdi.StackFrame
import com.sun.jdi.Value
import com.sun.jdi.VMDisconnectedException

/** Owns the remote argument array across managed invocations. */
internal object FlixEvaluationArguments {
    fun <T> use(
        names: List<String>,
        frame: StackFrame,
        retained: List<ObjectReference> = emptyList(),
        create: (Int) -> ArrayReference,
        box: (Value?) -> Value?,
        invoke: (ArrayReference) -> T,
    ): T {
        // No remote invocation until every frame value has been copied. Allocation and
        // boxing may resume the thread and invalidate this StackFrame permanently.
        val values = names.map { name ->
            val variable = frame.visibleVariableByName(name)
                ?: throw FlixRemoteEvalException("The selected frame does not hold `$name`.")
            frame.getValue(variable)
        }
        val pinned = mutableListOf<ObjectReference>()
        fun pin(value: ObjectReference) {
            value.disableCollection()
            pinned.add(value)
        }
        try {
            (retained + values.filterIsInstance<ObjectReference>()).forEach(::pin)
            val array = create(names.size)
            pin(array)
            values.forEachIndexed { index, value -> array.setValue(index, box(value)) }
            return invoke(array)
        } finally {
            // Release even on boxing/invocation failure; a disconnected or already collected
            // object no longer needs a collection pin.
            pinned.asReversed().forEach {
                try {
                    it.enableCollection()
                } catch (_: VMDisconnectedException) {
                } catch (_: ObjectCollectedException) {
                }
            }
        }
    }
}
