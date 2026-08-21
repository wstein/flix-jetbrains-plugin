package de.wstein.flixplugin.debugger

import com.sun.jdi.IntegerValue
import com.sun.jdi.Location
import com.sun.jdi.Method
import com.sun.jdi.ObjectReference
import com.sun.jdi.ReferenceType
import com.sun.jdi.StringReference

/**
 * Where a suspended Flix call will come back to.
 *
 * A continuation waiting on a call holds a `pc`, and that is all it holds about position: the `pc`
 * is a key of the `tableswitch` at the top of `applyFrame`, the switch turns it into a bytecode
 * offset, and the `LineNumberTable` turns the offset into a line. Every step of that is inside the
 * instruction stream, so the only reader that could follow it is a disassembler.
 *
 * A `--Xdebug` build records the result instead, as a `pcLines` constant on the continuation class
 * (`GenFunAndClosureClasses.resumeLines`) -- the line for `pc = 1` first. This reads it.
 *
 * ## Why the entry is looked up rather than constructed
 *
 * A line number is not a position a debugger can navigate to; a [Location] is. The recorded line is
 * the same number the `LineNumberTable` carries, so the location for it is already in the method and
 * is found by asking. Constructing a position from a bare number would also lose the stratum, and
 * with it the file — for a class carrying SMAP, the number alone names a line of a composite file
 * that no editor will open.
 */
internal object FlixResumePoints {

    /** The constant a `--Xdebug` build writes, listing the line of each `pc` in order. */
    private const val RESUME_LINES_FIELD = "pcLines"

    /** The field every continuation carries: which resume point it is waiting at. */
    private const val PC_FIELD = "pc"

    /**
     * The location `continuation` will resume at, or `null` if nothing says.
     *
     * Null covers the three ways this legitimately has no answer, none of which is an error: a
     * build without `--Xdebug` records no lines; a continuation that has not suspended has `pc = 0`
     * and no resume point to name; and a recorded line may name no location in this method, which
     * would mean the constant and the line table disagree. The caller falls back to the definition.
     */
    fun locationOf(continuation: ObjectReference, frameMethod: Method): Location? {
        val pc = (continuation.readField(PC_FIELD) as? IntegerValue)?.value() ?: return null
        // `pc` counts from 1; 0 is the entry the switch never dispatches on, and means this frame
        // is not waiting on anything.
        if (pc < 1) return null
        val line = recordedLines(continuation.referenceType()).getOrNull(pc - 1) ?: return null
        return runCatching { frameMethod.allLineLocations().firstOrNull { it.lineNumber() == line } }.getOrNull()
    }

    /**
     * The lines recorded on `type`, or empty if it records none.
     *
     * Not cached. It is one static-field read per frame of one stack, on the debugger thread, while
     * the UI is already waiting for the frames — and a cache keyed on a reference type would have to
     * be invalidated on redefinition, which is a class of bug this plugin has already paid for once
     * (see `FlixSourceCache`). If a stack ever gets deep enough for this to matter, cache it there,
     * with the same lifetime as the suspension.
     */
    private fun recordedLines(type: ReferenceType): List<Int> {
        val field = runCatching { type.fieldByName(RESUME_LINES_FIELD) }.getOrNull() ?: return emptyList()
        val recorded = runCatching { type.getValue(field) as? StringReference }.getOrNull() ?: return emptyList()
        return recorded.value().split(',').mapNotNull { it.trim().toIntOrNull() }
    }
}
