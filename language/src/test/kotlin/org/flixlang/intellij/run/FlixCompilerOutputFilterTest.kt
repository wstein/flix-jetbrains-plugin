package org.flixlang.intellij.run

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Reading a source location out of Flix compiler output.
 *
 * This is the whole difference between a task console and a terminal: the compiler already knows
 * where the problem is, and either the IDE turns that into a link or the user retypes it. Flix does
 * not print `file:line:col`, so the location is assembled from two lines — which is exactly the
 * part worth testing, and needs no console to test.
 */
class FlixCompilerOutputFilterTest {

    @Test
    fun `a diagnostic header names the file`() {
        assertEquals(
            "Bad.flix",
            FlixCompilerOutputFilter.sourceIn(
                "-- Parse Error [E6629] ------------------------------------------------ Bad.flix",
            ),
        )
    }

    @Test
    fun `a header keeps the path the compiler printed, not just the file name`() {
        // The compiler echoes the path as it was given, and the task runs in the project directory,
        // so a relative path resolves only if it survives intact.
        assertEquals(
            "OpenRewrite/Hello.flix",
            FlixCompilerOutputFilter.sourceIn(
                "-- Resolution Error [E1803] ----------------------------- OpenRewrite/Hello.flix",
            ),
        )
    }

    @Test
    fun `ordinary output is not mistaken for a header`() {
        assertNull(FlixCompilerOutputFilter.sourceIn(">> Expected <expression> before '}'."))
        assertNull(FlixCompilerOutputFilter.sourceIn("Compilation failed with 5 error(s)."))
        assertNull(FlixCompilerOutputFilter.sourceIn("-- just some dashes -- not a diagnostic"))
    }

    @Test
    fun `an excerpt line yields its number and where the number sits`() {
        // The offsets are what becomes clickable, so they have to be the number itself rather than
        // the whole line -- a link over the source text would swallow the code.
        assertEquals(Triple(3, 0, 1), FlixCompilerOutputFilter.lineNumberIn("3 | }"))
        assertEquals(Triple(42, 2, 4), FlixCompilerOutputFilter.lineNumberIn("  42 |     let x = 1;"))
    }

    @Test
    fun `the caret and message lines of an excerpt are not locations`() {
        assertNull(FlixCompilerOutputFilter.lineNumberIn("    ^"))
        assertNull(FlixCompilerOutputFilter.lineNumberIn("    Here"))
        assertNull(FlixCompilerOutputFilter.lineNumberIn(""))
    }

    @Test
    fun `colour escapes do not hide a location`() {
        // Whether the console has already decoded ANSI depends on the process handler it was built
        // with. Stripping here makes the answer the same either way.
        val coloured = "[38;2;140;140;140m2 | [0m    pub def f(): Int32 = 1"
        assertEquals(Triple(2, 0, 1), FlixCompilerOutputFilter.lineNumberIn(FlixCompilerOutputFilter.strip(coloured)))
    }

    @Test
    fun `a coloured header still names the file`() {
        val coloured = "-- [38;2;68;147;200mParse Error[0m " +
            "[38;2;68;147;200m[E6518][0m ------ [38;2;68;147;200mBad.flix[0m"
        assertEquals("Bad.flix", FlixCompilerOutputFilter.sourceIn(FlixCompilerOutputFilter.strip(coloured)))
    }
}
