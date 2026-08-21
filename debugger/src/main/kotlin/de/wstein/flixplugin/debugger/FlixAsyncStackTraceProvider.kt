package de.wstein.flixplugin.debugger

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
 * The line the call it is waiting on was made from -- `readTuning(), Main.flix:86` is the frame that
 * called `path()` from line 86, not the frame that starts at line 84.
 *
 * That position is the continuation's `pc`, which is a tableswitch key inside `applyFrame`: the
 * switch turns it into a bytecode offset and the `LineNumberTable` turns that into a line, so
 * nothing but a disassembler could read it. A `--Xdebug` build therefore records the answer on the
 * class, as `pcLines` (`GenFunAndClosureClasses.resumeLines`), and [[FlixResumePoints]] reads it.
 *
 * Without that constant -- an older build, or one without `--Xdebug` -- an entry falls back to the
 * definition's own first line, which is what every entry used to show. That reads as a stack of
 * function entries, and for a chain of calls in flight it is wrong for every frame but the
 * innermost.
 *
 * Entries carry no variables. A continuation's fields are its captured state under compiled names
 * (`clo0`, `arg0`, `l0`), and the frame they belong to is not the one the evaluator is pointed at,
 * so presenting them as that frame's variables would show values that cannot be read back or
 * evaluated against. Selecting one says "Variables are not available in async stacks", which is
 * true; the variables view keeps showing the live frame, which is where those names belong.
 *
 * ## Why only the topmost frame is answered for
 *
 * Answering is not additive. `JavaExecutionStack.AppendFrameCommand` schedules the returned list
 * with a **null** frame iterator and returns, so the real JVM frames below the one answered for are
 * replaced rather than followed by the reconstruction -- the same substitution the Kotlin plugin's
 * coroutine stacks rely on. Under the trampoline that is exactly right: what it replaces is the
 * machinery.
 *
 * It is also why nothing below the top frame is answered for. The platform asks about each frame in
 * turn, so a provider that answered for a lower one would truncate every real frame beneath it to
 * re-state a chain it had already shown.
 *
 * The cost, stated because it is real: a Java frame sitting directly *below* a Flix top frame is
 * hidden along with the machinery. That needs a Flix closure invoked from Java, which the interop
 * does not currently produce -- Java called *from* Flix puts the Java frame on top, where this
 * declines and the stack is left alone.
 */
class FlixAsyncStackTraceProvider : AsyncStackTraceProvider {

    override fun getAsyncStackTrace(frame: JavaStackFrame, context: SuspendContextImpl): List<StackFrameItem>? {
        // Only the topmost frame, and only when it is Flix: the platform asks every provider about
        // every frame of every JVM language, and what is returned replaces the frames below it.
        if (runCatching { frame.stackFrameProxy.frameIndex }.getOrNull() != 0) {
            return null
        }
        val location = runCatching { frame.descriptor.location }.getOrNull()
        if (!isFlixSource(location)) {
            return null
        }
        val thread = context.thread?.threadReference ?: return null
        return chainOf(thread, runCatching { location?.declaringType() }.getOrNull())
    }

    /** The chain `thread` holds above a frame of class `current`, or `null` if it holds none. */
    internal fun chainOf(thread: ThreadReference, current: ReferenceType?): List<StackFrameItem>? {
        val chain = runCatching { FlixContinuations.callChain(thread, current) }.getOrDefault(emptyList())
        val items = chain.mapNotNull { continuation ->
            val location = definitionLocation(continuation) ?: return@mapNotNull null
            // Labelled in Flix like a live frame, rather than as `applyFrame:177, Tuning$Def$path`.
            // An entry that exists only because this reconstruction put it there has no excuse for
            // naming the compiled form.
            // Falling back to the platform's own item rather than dropping the entry: a compiled
            // label still names a call that happened, and a chain with a hole in it does not.
            val label = runCatching { FlixFrames.labelOf(location) }.getOrNull()
            if (label != null) FlixStackFrameItem(location, label) else StackFrameItem(location, null)
        }
        // Null rather than an empty list: an empty one still draws the separator, which would
        // promise a reconstruction and then show nothing under it.
        return items.ifEmpty { null }
    }

    /** Whether a paused location belongs to Flix, judged by the source it maps to. */
    internal fun isFlixSource(location: Location?): Boolean =
        runCatching { location?.sourceName()?.endsWith(".flix") }.getOrNull() == true

    /**
     * Where `continuation` is: the call it suspended at, or failing that the definition it is in.
     *
     * The resume point is the honest answer and needs the `pcLines` constant a `--Xdebug` build
     * writes. The definition's first line is the fallback, and it is a real fallback rather than a
     * second-best default: a frame that has not suspended has no resume point, and a build without
     * the constant has no way to name one.
     */
    internal fun definitionLocation(continuation: ObjectReference): Location? {
        val method = frameMethod(continuation) ?: return null
        return FlixResumePoints.locationOf(continuation, method)
            ?: runCatching { method.allLineLocations().minByOrNull { it.lineNumber() } }.getOrNull()
    }

    /**
     * The method a Flix definition's body compiles to.
     *
     * `applyFrame` for a continuation, `staticApply` for a definition applied directly. A class with
     * neither contributes nothing, rather than an entry that navigates nowhere.
     */
    private fun frameMethod(continuation: ObjectReference): Method? {
        val type: ReferenceType = runCatching { continuation.referenceType() }.getOrNull() ?: return null
        return FRAME_METHODS.firstNotNullOfOrNull { name ->
            runCatching { type.methodsByName(name).firstOrNull() }.getOrNull()
        }
    }

    private companion object {
        /** The method a Flix definition's body compiles to, continuation form first. */
        private val FRAME_METHODS = listOf("applyFrame", "staticApply")
    }
}
