package dev.wstein.flixplugin.debugger

import com.sun.jdi.AbsentInformationException
import com.sun.jdi.Location
import com.sun.jdi.ReferenceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

/**
 * The rules that decide whether a JVM frame is Flix, and which JDI overload to ask for its source.
 *
 * These are exercised against JDI stubs rather than a live VM because the interesting cases are the
 * ones a live session rarely produces on demand: a class with no SMAP, a class whose SMAP exceeded
 * the line-number ceiling, absent line tables, and same-named files in different directories. Each
 * of those is a real Flix compilation outcome, and each one silently sends the debugger to the
 * wrong file or no file at all if the mapping is wrong.
 */
class FlixSourceLocationsTest {

    // --- ownership -------------------------------------------------------------------------

    @Test
    fun `claims a single-source Flix class that has no SMAP at all`() {
        // The common case, and the one a stratum-based ownership test would wrongly decline:
        // Smap.build() emits nothing until inlining pulls in a second file, yet the class still
        // reports a .flix source name and real .flix line numbers.
        val type = referenceType(strata = listOf("Java"), defaultStratum = "Java", sourceNames = listOf("Main.flix"))
        assertEquals("Java", FlixSourceLocations.stratumFor(type))
        assertTrue(FlixSourceLocations.isFlixLocation(location(type, sourceName = "Main.flix", line = 7)))
    }

    @Test
    fun `prefers the Flix stratum when inlining produced SMAP`() {
        val type = referenceType(
            strata = listOf("Java", "Flix"),
            defaultStratum = "Java",
            sourceNames = listOf("Main.flix", "List.flix"),
        )
        assertEquals("Flix", FlixSourceLocations.stratumFor(type))
    }

    @Test
    fun `declines Java, Kotlin, Scala and Groovy classes`() {
        // Non-interference is the guarantee this plugin owes the other JVM language plugins: their
        // position managers must keep their own frames.
        listOf("Main.java", "Main.kt", "Main.scala", "Main.groovy").forEach { name ->
            val type = referenceType(listOf("Java"), "Java", listOf(name))
            assertNull("Should not claim $name", FlixSourceLocations.stratumFor(type))
            assertFalse(FlixSourceLocations.isFlixLocation(location(type, name, 1)))
        }
    }

    @Test
    fun `declines a class whose source information is absent entirely`() {
        val type = referenceType(listOf("Java"), "Java", sourceNames = null)
        assertNull(FlixSourceLocations.stratumFor(type))
    }

    // --- line numbers ----------------------------------------------------------------------

    @Test
    fun `reports the line from the selected stratum`() {
        val type = referenceType(listOf("Java", "Flix"), "Java", listOf("Main.flix"))
        assertEquals(42, FlixSourceLocations.lineNumberOf(location(type, "Main.flix", line = 42)))
    }

    @Test
    fun `treats an unknown line as missing rather than as line one`() {
        // JDI signals "no line information" with 0 or -1. Passing either through would land the
        // debugger on line 1 after the usual one-based to zero-based conversion, which looks like a
        // plausible position and is not one.
        val type = referenceType(listOf("Java"), "Java", listOf("Main.flix"))
        assertNull(FlixSourceLocations.lineNumberOf(location(type, "Main.flix", line = 0)))
        assertNull(FlixSourceLocations.lineNumberOf(location(type, "Main.flix", line = -1)))
    }

    @Test
    fun `survives a class that throws AbsentInformationException`() {
        val type = referenceType(listOf("Java"), "Java", sourceNames = null, throwOnSourceNames = true)
        assertFalse(FlixSourceLocations.declaresFlixSourceIn(type, "Java"))
        assertNull(FlixSourceLocations.stratumFor(type))
    }

    // --- path matching ---------------------------------------------------------------------

    @Test
    fun `matches identical and separator-normalised paths`() {
        assertTrue(FlixSourceLocations.sameSourcePath("src/Main.flix", "src/Main.flix"))
        assertTrue(FlixSourceLocations.sameSourcePath("src\\Main.flix", "src/Main.flix"))
        assertTrue(FlixSourceLocations.sameSourcePath("/src/Main.flix", "src/Main.flix"))
    }

    @Test
    fun `matches a recorded relative path against an absolute one`() {
        assertTrue(
            FlixSourceLocations.sameSourcePath("/home/dev/project/src/Main.flix", "src/Main.flix"),
        )
    }

    @Test
    fun `does not match different files that share a suffix`() {
        // The reason matching is on a path boundary rather than plain endsWith: `NotMain.flix`
        // ends with `Main.flix`, and resolving a breakpoint into it would show unrelated code.
        assertFalse(FlixSourceLocations.sameSourcePath("src/NotMain.flix", "Main.flix"))
        assertFalse(FlixSourceLocations.sameSourcePath("a/Main.flix", "b/Main.flix"))
    }

    @Test
    fun `distinguishes same-named files in different modules`() {
        // Flix projects routinely have several Main.flix; matching on base name alone would attach
        // a breakpoint to whichever the debugger saw first.
        assertFalse(
            FlixSourceLocations.sameSourcePath("moduleA/src/Main.flix", "moduleB/src/Main.flix"),
        )
    }

    // --- JDI stubs -------------------------------------------------------------------------

    private fun referenceType(
        strata: List<String>,
        defaultStratum: String,
        sourceNames: List<String>?,
        throwOnSourceNames: Boolean = false,
    ): ReferenceType = proxy(ReferenceType::class.java) { method, _ ->
        when (method.name) {
            "availableStrata" -> strata
            "defaultStratum" -> defaultStratum
            "sourceNames" ->
                when {
                    throwOnSourceNames -> throw AbsentInformationException()
                    sourceNames == null -> throw AbsentInformationException()
                    else -> sourceNames
                }
            "sourcePaths" -> sourceNames ?: throw AbsentInformationException()
            else -> null
        }
    }

    private fun location(type: ReferenceType, sourceName: String, line: Int): Location =
        proxy(Location::class.java) { method, _ ->
            when (method.name) {
                "declaringType" -> type
                "sourceName" -> sourceName
                "sourcePath" -> sourceName
                "lineNumber" -> line
                else -> null
            }
        }

    /**
     * JDI's interfaces have dozens of methods each and no test doubles ship with the platform, so
     * the handful this code actually calls are stubbed reflectively rather than by writing out
     * several hundred lines of unused overrides.
     */
    @Suppress("UNCHECKED_CAST")
    private fun <T> proxy(type: Class<T>, handler: (java.lang.reflect.Method, Array<Any?>?) -> Any?): T =
        Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, args ->
            handler(method, args)
        } as T
}
