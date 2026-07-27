package dev.wstein.flixplugin.debugger

import com.sun.jdi.AbsentInformationException
import com.sun.jdi.ReferenceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

/**
 * Breakpoint → class resolution: given a `.flix` file the user set a breakpoint in, which loaded
 * classes were compiled from it.
 *
 * This direction had an asymmetry worth a regression test. Reverse navigation (location → file)
 * refuses ambiguous duplicate base names, but forward binding compared source paths textually, and
 * `sameSourcePath("Main.flix", "/project/moduleA/Main.flix")` succeeds through the suffix rule --
 * so a class reporting only `Main.flix`, which is every class without SMAP, bound to a breakpoint
 * in *every* module's `Main.flix`. One breakpoint would stop in code the user never marked, and the
 * debugger would then decline to navigate to the frame it stopped in.
 *
 * The pieces exercised here are the ones that decide it. Driving `getAllClasses` end to end would
 * need a live `DebugProcess` and a `Project`; the resolution rule does not.
 */
class FlixForwardResolutionTest {

    // --- what JDI reports ------------------------------------------------------------------

    @Test
    fun `pairs each source name with its own path`() {
        // JDI hands back two parallel lists. Zipping them wrongly would attach one file's path to
        // another file's name, which is exactly the kind of mix-up that binds a breakpoint to the
        // wrong class in an inlined build.
        val type = referenceType(
            names = listOf("Main.flix", "List.flix"),
            paths = listOf("moduleA/Main.flix", "lib/List.flix"),
        )
        assertEquals(
            listOf("Main.flix" to "moduleA/Main.flix", "List.flix" to "lib/List.flix"),
            FlixSourceLocations.flixSourcesOf(type, "Flix"),
        )
    }

    @Test
    fun `tolerates a class that reports names but no paths`() {
        // The no-SMAP shape: a source name, no path. It must still be offered for resolution, with
        // a null path, rather than dropped.
        val type = referenceType(names = listOf("Main.flix"), paths = null)
        assertEquals(listOf("Main.flix" to null), FlixSourceLocations.flixSourcesOf(type, "Java"))
    }

    @Test
    fun `returns source names exactly as JDI reported them`() {
        // Load-bearing for locationsOfLine, which must query with the name JDI gave rather than a
        // base name derived from it. A class without SMAP can report an absolute SourceFile, and
        // `locationsOfLine(stratum, "Main.flix", n)` then yields nothing even though the line table
        // holds line n -- so the breakpoint verifies against the class and never binds to a
        // location. Normalizing here would reintroduce exactly that.
        val type = referenceType(
            names = listOf("/project/src/Main.flix"),
            paths = listOf("/project/src/Main.flix"),
        )
        assertEquals(
            listOf("/project/src/Main.flix" to "/project/src/Main.flix"),
            FlixSourceLocations.flixSourcesOf(type, "Java"),
        )
        // ...while still passing the base-name filter, so such a class is not skipped outright.
        assertTrue(FlixSourceLocations.couldReferToBaseName("/project/src/Main.flix", "Main.flix"))
    }

    @Test
    fun `ignores non-Flix sources of a mixed class`() {
        val type = referenceType(
            names = listOf("Main.flix", "Helper.java"),
            paths = listOf("Main.flix", "Helper.java"),
        )
        assertEquals(listOf("Main.flix" to "Main.flix"), FlixSourceLocations.flixSourcesOf(type, "Java"))
    }

    @Test
    fun `reports no sources rather than throwing when information is absent`() {
        val type = referenceType(names = null, paths = null)
        assertTrue(FlixSourceLocations.flixSourcesOf(type, "Java").isEmpty())
    }

    // --- the cheap pre-filter --------------------------------------------------------------

    @Test
    fun `base-name filter accepts a bare name and a path ending in it`() {
        assertTrue(FlixSourceLocations.couldReferToBaseName("Main.flix", "Main.flix"))
        assertTrue(FlixSourceLocations.couldReferToBaseName("moduleA/src/Main.flix", "Main.flix"))
        assertTrue(FlixSourceLocations.couldReferToBaseName("moduleA\\src\\Main.flix", "Main.flix"))
    }

    @Test
    fun `base-name filter rejects a different file`() {
        assertFalse(FlixSourceLocations.couldReferToBaseName("Other.flix", "Main.flix"))
        // Must not match on a bare suffix, or NotMain.flix would pass for Main.flix and the
        // expensive check would be asked a question it should never see.
        assertFalse(FlixSourceLocations.couldReferToBaseName("NotMain.flix", "Main.flix"))
    }

    // --- the property the bug violated -------------------------------------------------------

    @Test
    fun `the pre-filter alone cannot distinguish duplicate base names`() {
        // Both modules' classes pass the cheap filter, which is precisely why it is only a filter.
        // The authoritative step is FlixSourceFiles.choose, which refuses this case -- asserted
        // here together so the two halves cannot drift apart.
        assertTrue(FlixSourceLocations.couldReferToBaseName("Main.flix", "Main.flix"))

        val bothModules = listOf("/proj/moduleA/Main.flix", "/proj/moduleB/Main.flix")
        assertEquals(
            "A class reporting only `Main.flix` must not resolve to either module's file",
            null,
            FlixSourceFiles.choose(bothModules, "Main.flix", null),
        )
    }

    @Test
    fun `a recorded path does distinguish duplicate base names`() {
        val bothModules = listOf("/proj/moduleA/Main.flix", "/proj/moduleB/Main.flix")
        assertEquals(
            "/proj/moduleA/Main.flix",
            FlixSourceFiles.choose(bothModules, "Main.flix", "moduleA/Main.flix"),
        )
    }

    private fun referenceType(names: List<String>?, paths: List<String>?): ReferenceType =
        Proxy.newProxyInstance(
            ReferenceType::class.java.classLoader,
            arrayOf(ReferenceType::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "sourceNames" -> names ?: throw AbsentInformationException()
                "sourcePaths" -> paths ?: throw AbsentInformationException()
                "availableStrata" -> listOf("Java")
                "defaultStratum" -> "Java"
                else -> null
            }
        } as ReferenceType
}
