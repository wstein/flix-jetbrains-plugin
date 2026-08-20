package dev.wstein.flixplugin.debugger

import com.sun.jdi.ObjectReference
import com.sun.jdi.StackFrame
import com.sun.jdi.ThreadReference
import com.sun.jdi.Value

/**
 * The Flix call chain, read out of the continuation objects on the heap.
 *
 * ## Why it is not on the JVM stack
 *
 * Under CPS the trampoline invokes every continuation from one loop, so exactly one Flix frame is
 * live at a time. `main -> readTuning -> path -> location` is nowhere on the JVM stack; between the
 * Flix frames sit `Handler$.installHandler`, `ResumptionCons$$Lambda.invoke` and the `invoke`
 * wrappers. Measured on a real stop, that was 36 JVM frames for a chain of four Flix calls, of which
 * the frames view showed one.
 *
 * The chain is on the heap instead. `Frames$` is a cons list -- `FramesCons$ { head: Frame$, tail:
 * Frames$ }` ending at `FramesNil$` -- and each `head` is the continuation object of one Flix call,
 * whose class identifies the definition it belongs to.
 *
 * ## Where a list is found
 *
 * Two places, both measured against a live session:
 *
 * - as an argument of the `Handler$.installHandler` frames on the JVM stack, and
 * - as the `frames` field of a `ResumptionCons$ { sym, handler, frames, tail }` node, which is what
 *   an effect's suspension captured. A `Suspension$ { effSym, effOp, prefix, resumption }` holds one
 *   directly in `prefix` and reaches the rest through `resumption`.
 *
 * The second matters when the program is stopped inside a *handler* rather than inside the
 * computation: there the chain that was suspended is no longer an argument of anything on the stack,
 * and only the resumption still holds it.
 *
 * The field names are not guessed from a heap dump. They are the ones the compiler emits, in
 * `BackendObjType.scala` -- `FramesCons.HeadField`/`TailField`, `ResumptionCons.FramesField`/
 * `TailField`, `Suspension.PrefixField`/`ResumptionField`.
 *
 * ## Why the longest one
 *
 * Each handler holds the frames captured at *its* level, and measurement shows those lists are
 * nested suffixes of one another:
 *
 * ```
 * installHandler #1   [readTuning, main]
 * installHandler #2   [path, readTuning, main]
 * installHandler #3   [location, path, readTuning, main]
 * ```
 *
 * So the longest is the complete chain and every other is a tail of it. Taking the longest is
 * therefore not a guess between rivals -- the shorter lists carry no call the longest lacks. The
 * lists are compared, never concatenated: concatenation would assert an ordering across handler
 * levels that has not been measured, and would invent calls where the suffix property already
 * accounts for them.
 *
 * This is a property observed rather than proved, which is why it is stated here and asserted in
 * `FlixContinuationsTest`.
 */
internal object FlixContinuations {

    /** `dev.flix.runtime.FramesCons$`, the cons cell of the frame list. */
    private const val FRAMES_CONS = "FramesCons\$"

    /** `dev.flix.runtime.ResumptionCons$`, one captured handler level. */
    private const val RESUMPTION_CONS = "ResumptionCons\$"

    /** `dev.flix.runtime.Suspension$`, an effect operation in flight. */
    private const val SUSPENSION = "Suspension\$"

    /** How far to walk before giving up. A corrupt or cyclic list must not hang the frames view. */
    private const val MAX_FRAMES = 512

    /** How many JVM frames to inspect when looking for a chain. */
    private const val MAX_JVM_FRAMES = 512

    /**
     * The Flix continuations above `thread`'s current position, innermost first.
     *
     * Empty when the thread holds no continuation list, which is the ordinary case for a program
     * with no effects: there is no trampoline, the JVM stack *is* the call chain, and the frames
     * view already shows it.
     */
    fun callChain(thread: ThreadReference): List<ObjectReference> =
        candidates(thread).maxByOrNull { it.size }.orEmpty()

    /** Every frame list this thread can reach, in no particular order. */
    private fun candidates(thread: ThreadReference): List<List<ObjectReference>> {
        val chains = mutableListOf<List<ObjectReference>>()
        for (frame in framesOf(thread)) {
            for (candidate in referencesIn(frame)) {
                when (simpleName(candidate)) {
                    FRAMES_CONS -> chains += walk(candidate)
                    RESUMPTION_CONS -> chains += framesOfResumption(candidate)
                    SUSPENSION -> {
                        (candidate.readField("prefix") as? ObjectReference)?.let { chains += walk(it) }
                        (candidate.readField("resumption") as? ObjectReference)
                            ?.let { chains += framesOfResumption(it) }
                    }
                }
            }
        }
        return chains
    }

    /** The `head` of every cell in the `Frames$` list starting at `cell`. */
    fun walk(cell: ObjectReference): List<ObjectReference> {
        val heads = mutableListOf<ObjectReference>()
        val seen = mutableSetOf<Long>()
        var node: ObjectReference? = cell
        while (node != null && heads.size < MAX_FRAMES) {
            if (simpleName(node) != FRAMES_CONS) break
            if (!seen.add(node.uniqueID())) break
            (node.readField("head") as? ObjectReference)?.let { heads += it }
            node = node.readField("tail") as? ObjectReference
        }
        return heads
    }

    /** The frame list held at each level of the resumption chain starting at `cell`. */
    fun framesOfResumption(cell: ObjectReference): List<List<ObjectReference>> {
        val chains = mutableListOf<List<ObjectReference>>()
        val seen = mutableSetOf<Long>()
        var node: ObjectReference? = cell
        while (node != null && chains.size < MAX_FRAMES) {
            if (simpleName(node) != RESUMPTION_CONS) break
            if (!seen.add(node.uniqueID())) break
            (node.readField("frames") as? ObjectReference)?.let { chains += walk(it) }
            node = node.readField("tail") as? ObjectReference
        }
        return chains
    }

    private fun simpleName(value: ObjectReference): String? =
        runCatching { FlixValues.simpleNameOf(value.referenceType().name()) }.getOrNull()

    /** `this` and the arguments of `frame`, as far as the debuggee will report them. */
    private fun referencesIn(frame: StackFrame): List<ObjectReference> {
        val values = mutableListOf<Value?>()
        // Both can fail on a native or static frame -- JDWP answers INVALID_SLOT -- and one frame
        // refusing must not lose the chain held by another.
        runCatching { values += frame.thisObject() }
        runCatching { values += frame.getArgumentValues() }
        return values.filterIsInstance<ObjectReference>()
    }

    private fun framesOf(thread: ThreadReference): List<StackFrame> =
        runCatching { thread.frames(0, minOf(thread.frameCount(), MAX_JVM_FRAMES)) }.getOrDefault(emptyList())
}
