package dev.wstein.flixplugin.debugger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which Flix expressions a watch answers, and what it says about the ones it does not.
 *
 * The refusals are asserted as carefully as the acceptances. A watch that fails is the normal case
 * while the grammar is this small, so the message *is* the feature: it has to say what works, not
 * merely that this did not.
 */
class FlixNavigationTest {

    private fun path(text: String): FlixNavigation.Path =
        FlixExpressions.parse(text) as? FlixNavigation.Path
            ?: error("expected `$text` to parse as a navigation path, got ${FlixExpressions.parse(text)}")

    private fun refusal(text: String): String =
        (FlixExpressions.parse(text) as? FlixNavigation.Unsupported)?.reason
            ?: error("expected `$text` to be refused, but it parsed")

    @Test
    fun `a bare name is a path with no projections`() {
        assertEquals(FlixNavigation.Path("at", emptyList()), path("at"))
    }

    @Test
    fun `a record projection is the expression this exists for`() {
        // `at#dir` at a breakpoint in `Option.map(at -> …)`: the case that used to fail with
        // "Invalid expression : #", because the evaluator was Java and `#` means nothing there.
        assertEquals(FlixNavigation.Path("at", listOf("dir")), path("at#dir"))
    }

    @Test
    fun `projections chain`() {
        assertEquals(FlixNavigation.Path("a", listOf("b", "c")), path("a#b#c"))
    }

    @Test
    fun `surrounding and interior whitespace is not significant`() {
        assertEquals(FlixNavigation.Path("at", listOf("dir")), path("  at # dir  "))
    }

    @Test
    fun `a name may carry the characters a frame can actually record`() {
        assertEquals(FlixNavigation.Path("_x'", emptyList()), path("_x'"))
    }

    // --- refusals -------------------------------------------------------------------------------

    @Test
    fun `every refusal names the limit rather than the parse failure`() {
        // The whole point of refusing by hand rather than failing to parse. A reader who typed
        // something outside the grammar learns the grammar.
        for (text in listOf("f()", "a + b", "\"\${at#dir}\"", "at#", "#dir", "1")) {
            val reason = refusal(text)
            assertTrue("`$text` was refused without naming the limit: $reason", reason.contains(FlixExpressions.LIMIT))
        }
    }

    @Test
    fun `a call is refused, and says compilation is what it would need`() {
        // Not "invalid expression". Calling `fileName()` is well-formed Flix; it is unsupported
        // because running it means compiling a thunk and executing it in the debuggee.
        val reason = refusal("fileName()")
        assertTrue(reason, reason.contains("compiled and run in the debuggee"))
    }

    @Test
    fun `an interpolated string is refused rather than answered with itself`() {
        // The trap the Java evaluator fell into: `"${at#dir}${sep}"` is a valid *Java* string
        // literal, so it evaluated to itself and looked like an answer. Refusing is strictly better
        // than returning something that is not the value of the expression.
        val reason = refusal("\"\${at#dir}\${sep}\${fileName()}\"")
        assertTrue(reason, reason.contains(FlixExpressions.LIMIT))
    }

    @Test
    fun `a dangling projection names the syntax, not the whole expression`() {
        assertTrue(refusal("at#").contains("both sides"))
        assertTrue(refusal("#dir").contains("both sides"))
    }

    @Test
    fun `nothing to evaluate is said plainly`() {
        assertEquals("Nothing to evaluate.", refusal("   "))
    }

    // --- the messages the evaluator raises against a live frame ---------------------------------

    @Test
    fun `an unknown name explains what a frame does and does not record`() {
        // Before the compiler recorded a continuation's captures and parameters, *every* name in an
        // effectful frame failed this way. It can still happen for a name from an enclosing scope
        // the closure did not capture, and that distinction is what the message carries.
        val message = FlixExpressions.unknownName("at")
        assertTrue(message, message.contains("`at` is not visible in this frame"))
        assertTrue(message, message.contains("captures, parameters and let-bindings"))
    }

    @Test
    fun `projecting out of a non-record says what it actually is`() {
        assertEquals(
            "`sep` is a String, not a record, so it has no `#` fields.",
            FlixExpressions.notARecord("sep", "a String"),
        )
    }

    @Test
    fun `a missing field lists the fields that are there`() {
        assertEquals(
            "`at` has no field `nope`; it has `dir`, `file`.",
            FlixExpressions.noSuchField("at", "nope", listOf("dir", "file")),
        )
    }

    @Test
    fun `a missing field on an empty record does not print an empty list`() {
        assertEquals(
            "`at` has no field `dir`; it has none.",
            FlixExpressions.noSuchField("at", "dir", emptyList()),
        )
    }
}
