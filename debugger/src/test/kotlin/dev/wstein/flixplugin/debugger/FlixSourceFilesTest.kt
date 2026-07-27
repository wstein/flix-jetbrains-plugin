package dev.wstein.flixplugin.debugger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * How a JDI source attribute is turned into one project file.
 *
 * Both halves have bitten already: `FilenameIndex` keys on base names, so a path-valued
 * `SourceFile` finds nothing at all; and Flix projects routinely carry several `Main.flix`, so a
 * base-name match alone binds to whichever module the index happened to return first.
 */
class FlixSourceFilesTest {

    // --- base names ------------------------------------------------------------------------

    @Test
    fun `reduces path-valued source attributes to a base name`() {
        assertEquals("Main.flix", FlixSourceFiles.baseNameOf("Main.flix"))
        assertEquals("Main.flix", FlixSourceFiles.baseNameOf("src/Main.flix"))
        assertEquals("Main.flix", FlixSourceFiles.baseNameOf("/home/dev/proj/src/Main.flix"))
        assertEquals("Main.flix", FlixSourceFiles.baseNameOf("src\\Main.flix"))
    }

    // --- unambiguous -----------------------------------------------------------------------

    @Test
    fun `takes the only candidate without needing a path`() {
        assertEquals(
            "/proj/src/Main.flix",
            FlixSourceFiles.choose(listOf("/proj/src/Main.flix"), "Main.flix", null),
        )
    }

    @Test
    fun `finds nothing when there are no candidates`() {
        assertNull(FlixSourceFiles.choose(emptyList(), "Main.flix", "/proj/src/Main.flix"))
    }

    // --- duplicates ------------------------------------------------------------------------

    private val twoMains = listOf("/proj/moduleA/src/Main.flix", "/proj/moduleB/src/Main.flix")

    @Test
    fun `disambiguates duplicate base names by source path`() {
        assertEquals(
            "/proj/moduleB/src/Main.flix",
            FlixSourceFiles.choose(twoMains, "Main.flix", "moduleB/src/Main.flix"),
        )
    }

    @Test
    fun `disambiguates by a path-valued source name when no source path is available`() {
        // A class with no SMAP has no sourcePath to offer, but the name itself may carry
        // directories depending on what the compiler recorded.
        assertEquals(
            "/proj/moduleA/src/Main.flix",
            FlixSourceFiles.choose(twoMains, "moduleA/src/Main.flix", null),
        )
    }

    @Test
    fun `refuses to guess between duplicates with nothing to disambiguate on`() {
        // The no-SMAP worst case: several Main.flix and only a bare base name. Binding to either
        // would show unrelated code as the frame's source.
        assertNull(FlixSourceFiles.choose(twoMains, "Main.flix", null))
        assertNull(FlixSourceFiles.choose(twoMains, "Main.flix", "Main.flix"))
    }

    @Test
    fun `refuses when a qualifier still matches more than one candidate`() {
        // Two modules laid out identically below different roots: the recorded suffix matches both,
        // so it does not actually identify a file.
        val ambiguous = listOf("/proj/a/src/Main.flix", "/proj/b/src/Main.flix")
        assertNull(FlixSourceFiles.choose(ambiguous, "Main.flix", "src/Main.flix"))
    }

    @Test
    fun `does not let a suffix match cross a name boundary`() {
        val candidates = listOf("/proj/src/NotMain.flix", "/proj/other/Main.flix")
        assertEquals(
            "/proj/other/Main.flix",
            FlixSourceFiles.choose(candidates, "Main.flix", "other/Main.flix"),
        )
    }
}
