package dev.wstein.flixplugin.debugger

import com.intellij.debugger.engine.AsyncStackTraceProvider
import com.intellij.debugger.engine.JavaStackFrame
import com.intellij.debugger.engine.SuspendContextImpl
import com.intellij.debugger.memory.utils.StackFrameItem
import com.sun.jdi.Location
import com.sun.jdi.Method
import com.sun.jdi.ObjectReference
import com.sun.jdi.ReferenceType
import com.sun.jdi.ThreadReference

/**
 * Shows the Flix call chain that the JVM stack does not contain.
 *
 * Stopping inside an effectful Flix function shows one frame and a note that thirty-five are hidden,
 * and turning those on does not help: they are `Handler$.installHandler`,
 * `ResumptionCons$$Lambda.invoke` and the `invoke` wrappers of the effect trampoline. The calls the
 * programmer made -- `main -> readTuning -> path -> location` -- are on the heap, in the
 * continuation objects, because under CPS only one Flix frame is live at a time.
 * [FlixContinuations] is where they are read from.
 *
 * This is the same mechanism Kotlin uses to show a coroutine's logical stack instead of its
 * dispatcher: the platform appends whatever is returned under an *Async stack trace* separator, so
 * the reconstruction is presented as what it is -- a second, derived view -- rather than being mixed
 * into the real frames.
 *
 * ## What each entry points at
 *
 * A continuation's class identifies the definition it belongs to, and the *first* line its
 * `applyFrame` records is that definition's own.
 *
 * It is deliberately not the line the call will resume at, which would be the better answer and is
 * not derivable here: the resume point is the continuation's `pc` field, and turning a `pc` into a
 * line means reading the `tableswitch` at the top of `applyFrame` out of the method's bytecode.
 * Until that exists an entry says "this definition is on the chain" rather than claiming a position
 * inside it.
 *
 * Entries carry no variables. A continuation's fields are its captured state under compiled names
 * (`clo0`, `arg0`, `l0`), and the frame they belong to is not the one the evaluator is pointed at,
 * so presenting them as that frame's variables would show values that cannot be read back or
 * evaluated against. The variables view keeps showing the live frame, which is the frame those
 * names belong to.
 */
class FlixAsyncStackTraceProvider : AsyncStackTraceProvider {

    override fun getAsyncStackTrace(frame: JavaStackFrame, context: SuspendContextImpl): List<StackFrameItem>? {
        // Only for a frame that is Flix. The platform asks every provider about every frame of every
        // JVM language, and one that answered for all of them would staple a Flix chain onto a Java
        // stack.
        val location = runCatching { frame.descriptor.location }.getOrNull()
        if (!isFlixSource(location)) {
            return null
        }
        val thread = context.thread?.threadReference ?: return null
        return chainOf(thread)
    }

    /** The chain `thread` holds, or `null` if it holds none. */
    internal fun chainOf(thread: ThreadReference): List<StackFrameItem>? {
        val chain = runCatching { FlixContinuations.callChain(thread) }.getOrDefault(emptyList())
        val items = chain.mapNotNull { continuation ->
            definitionLocation(continuation)?.let { StackFrameItem(it, null) }
        }
        // Null rather than an empty list: an empty one still draws the separator, which would
        // promise a reconstruction and then show nothing under it.
        return items.ifEmpty { null }
    }

    /** Whether a paused location belongs to Flix, judged by the source it maps to. */
    internal fun isFlixSource(location: Location?): Boolean =
        runCatching { location?.sourceName()?.endsWith(".flix") }.getOrNull() == true

    /**
     * A location inside the definition `continuation` belongs to.
     *
     * `applyFrame` for a continuation, `staticApply` for a definition applied directly; the first
     * line either records is the definition's own. A class with no line information contributes
     * nothing, rather than an entry that navigates nowhere.
     */
    internal fun definitionLocation(continuation: ObjectReference): Location? {
        val type: ReferenceType = runCatching { continuation.referenceType() }.getOrNull() ?: return null
        val method: Method = FRAME_METHODS.firstNotNullOfOrNull { name ->
            runCatching { type.methodsByName(name).firstOrNull() }.getOrNull()
        } ?: return null
        return runCatching { method.allLineLocations().minByOrNull { it.lineNumber() } }.getOrNull()
    }

    private companion object {
        /** The method a Flix definition's body compiles to, continuation form first. */
        private val FRAME_METHODS = listOf("applyFrame", "staticApply")
    }
}
