package de.wstein.flixplugin.debugger

/**
 * The Flix expressions a debug session can answer today, and the reason for every one it cannot.
 *
 * ## Why this grammar is this small
 *
 * Evaluating a Flix expression properly means asking the compiler: resolve it in the paused scope,
 * type it, lower it, and run the result in the debuggee. That is the destination, and nothing here
 * approximates it — an approximation of a type system is the one thing worse than not having one,
 * because it answers confidently and differently from the language.
 *
 * What *is* answerable without any of that is navigation. A name that the frame already holds, and
 * projections out of the record it points at, are reads: no code runs in the debuggee, so there is
 * no effect to sanction, no thunk to compile, and no way for the answer to disagree with the
 * program. `at#dir` is the expression people actually type at a breakpoint, and it costs a field
 * read.
 *
 * Names are resolvable at all only because the compiler now records a continuation frame's captures
 * and parameters in its `LocalVariableTable`; before that, `at` was `arg0` and nothing could map it
 * back. See `docs/native-debugger-gate.md`.
 *
 * ## Why refusals are specific
 *
 * Everything outside the grammar is refused by naming the limit, not by failing to parse. A watch
 * that says "Invalid expression : #" teaches nothing; one that says which expressions work tells
 * the reader where the boundary is and that it is deliberate.
 */
internal sealed interface FlixNavigation {

    /** A frame variable, and the record fields to project out of it in order. */
    data class Path(val name: String, val fields: List<String>) : FlixNavigation

    /** Outside the grammar. [reason] is shown to the user verbatim. */
    data class Unsupported(val reason: String) : FlixNavigation
}

internal object FlixExpressions {

    /**
     * What the grammar covers, in the words the refusal uses.
     *
     * One sentence, because it is read in a one-line watch cell and in a tooltip, and a reader who
     * has just been refused wants the rule rather than a description of the machinery.
     */
    const val LIMIT: String =
        "a variable, optionally with record projections -- `at`, `at#dir`, `at#dir#name`"

    /**
     * A Flix identifier, as the debugger needs to recognise one.
     *
     * Deliberately narrower than the language's: `?`, `!` and unicode names exist in Flix and none
     * of them can appear in a frame's `LocalVariableTable`, so accepting them here would only move
     * the failure from the parse to the lookup, where the message is worse.
     */
    private val IDENTIFIER = Regex("""[A-Za-z_][A-Za-z0-9_']*""")

    /** Parses [text] as a navigation path, or says why it is not one. */
    fun parse(text: String): FlixNavigation {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            return FlixNavigation.Unsupported("Nothing to evaluate.")
        }
        // Split before validating, so that `at#dir` with a bad field names the field rather than
        // reporting the whole expression as unrecognisable.
        val parts = trimmed.split('#')
        if (parts.any { it.isBlank() }) {
            return unsupported(trimmed, "a `#` must have a field name on both sides")
        }
        val bad = parts.firstOrNull { !IDENTIFIER.matches(it.trim()) }
        if (bad != null) {
            return unsupported(trimmed, "`${bad.trim()}` is not a plain name")
        }
        val names = parts.map { it.trim() }
        return FlixNavigation.Path(names.first(), names.drop(1))
    }

    private fun unsupported(text: String, because: String): FlixNavigation.Unsupported =
        FlixNavigation.Unsupported(
            "Cannot evaluate `$text`: $because. A Flix debug session evaluates $LIMIT. " +
                "Anything else has to be compiled and run in the debuggee, which is not implemented yet.",
        )

    /** The message for a name the paused frame does not hold. */
    fun unknownName(name: String): String =
        "`$name` is not visible in this frame. Only names the compiler recorded for it can be read, " +
            "and a frame records its captures, parameters and let-bindings -- not names from an " +
            "enclosing scope it did not capture."

    /** The message for projecting a field out of something that is not a record. */
    fun notARecord(expression: String, actual: String): String =
        "`$expression` is $actual, not a record, so it has no `#` fields."

    /** The message for a field a record does not have. */
    fun noSuchField(expression: String, field: String, available: List<String>): String {
        val has = if (available.isEmpty()) "it has none" else "it has ${available.joinToString(", ") { "`$it`" }}"
        return "`$expression` has no field `$field`; $has."
    }
}
