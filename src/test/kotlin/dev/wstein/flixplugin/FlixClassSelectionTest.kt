package dev.wstein.flixplugin

import com.intellij.debugger.NoDataException
import com.intellij.debugger.SourcePosition
import com.intellij.debugger.engine.DebugProcess
import com.intellij.debugger.engine.jdi.VirtualMachineProxy
import com.intellij.psi.PsiFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.sun.jdi.Location
import com.sun.jdi.ReferenceType
import dev.wstein.flixplugin.debugger.FlixPositionManager
import java.lang.reflect.Proxy

/**
 * Which loaded classes a Flix breakpoint is offered.
 *
 * This lives in the root module rather than in `:debugger`, and that placement is the point. The
 * decision starts from a [SourcePosition] and ends in the file index, so the fixture needs a file
 * that is *both* typed as Flix **and** indexed. No module-local fixture provides that:
 * `ParsingTestCase` gives the right type but a `LightVirtualFile` the index cannot see, and a
 * `BasePlatformTestCase` inside `:debugger` gives an indexed file typed as plain text, because a
 * module-local sandbox never loads the language module's `fileType` registration. Here the
 * assembled plugin is loaded, so `.flix` resolves for real.
 *
 * What it protects is a defect that was order-dependent, and therefore intermittent. One `.flix`
 * source compiles to dozens of classes each covering part of it. A class that merely shared the
 * *file* used to be offered, whereupon `LineBreakpoint` reported "no executable code" and
 * `RequestManagerImpl.setInvalid` made that stick if it arrived before a class that did hold the
 * line — so the same breakpoint worked or did not between runs.
 *
 * The class-prepare filter applies the same rule to a single prepared class. Driving it needs a
 * `RequestManager`, which is a class rather than an interface and cannot be stubbed here; the
 * shared decision is what is covered.
 */
class FlixClassSelectionTest : BasePlatformTestCase() {

    private lateinit var file: PsiFile

    override fun setUp() {
        super.setUp()
        file = myFixture.configureByText(
            "Main.flix",
            """
            def main(): Unit \ IO =
                println("one");
                println("two")
            """.trimIndent(),
        )
    }

    fun testOffersOnlyClassesThatHoldTheLine() {
        val holdsIt = flixClass("Clo\$main\$1", lines = setOf(2))
        val sameFileOtherLine = flixClass("Clo\$main\$2", lines = setOf(3))
        val foreign = foreignClass("Greeter")

        val offered = manager(holdsIt, sameFileOtherLine, foreign).getAllClasses(positionAtLine(1))

        assertEquals(
            "only the class holding the line may be offered — one that merely shares the file " +
                "reports \"no executable code\" and can mark the breakpoint invalid before the " +
                "real one arrives",
            listOf(holdsIt),
            offered,
        )
    }

    fun testOffersNothingBeforeTheClassLoads() {
        // Normal, not a failure: the class-prepare path exists for exactly this. It must be empty
        // rather than "every class from the file".
        val offered = manager(flixClass("Clo\$main\$1", lines = setOf(3))).getAllClasses(positionAtLine(1))
        assertTrue("expected nothing, got $offered", offered.isEmpty())
    }

    fun testIgnoresClassesFromOtherSources() {
        val offered = manager(foreignClass("Greeter")).getAllClasses(positionAtLine(1))
        assertTrue("a Java class must never be offered for a Flix position", offered.isEmpty())
    }

    fun testDeclinesAPositionInAnotherLanguage() {
        // The guarantee owed to every other JVM language: a foreign position is not ours to answer,
        // and declining by exception is what hands it to the manager that owns it.
        val other = myFixture.configureByText("Notes.txt", "not Flix")
        try {
            manager(flixClass("Clo\$main\$1", lines = setOf(2)))
                .getAllClasses(SourcePosition.createFromLine(other, 0))
            fail("expected NoDataException for a non-Flix position")
        } catch (expected: NoDataException) {
            // correct: another position manager owns it
        }
    }

    // --- fixture --------------------------------------------------------------------------------

    private fun positionAtLine(zeroBased: Int): SourcePosition =
        SourcePosition.createFromLine(file, zeroBased)

    private fun manager(vararg loaded: ReferenceType): FlixPositionManager {
        val vm = proxy(VirtualMachineProxy::class.java) { method, _ ->
            if (method.name == "allClasses") loaded.toList() else null
        }
        val process = proxy(DebugProcess::class.java) { method, _ ->
            when (method.name) {
                "getProject" -> project
                "getVirtualMachineProxy" -> vm
                else -> null // addDebugProcessListener and friends: nothing to record here
            }
        }
        return FlixPositionManager(process)
    }

    /** A class JDI reports as compiled from the fixture's `.flix` file. */
    private fun flixClass(name: String, lines: Set<Int>): ReferenceType =
        referenceType(name, listOf(file.virtualFile.path), lines)

    private fun foreignClass(name: String): ReferenceType =
        referenceType(name, listOf("Greeter.java"), setOf(1, 2, 3))

    private fun referenceType(name: String, sourceNames: List<String>, lines: Set<Int>): ReferenceType {
        lateinit var self: ReferenceType
        self = proxy(ReferenceType::class.java) { method, args ->
            when (method.name) {
                "name" -> name
                "availableStrata" -> listOf("Java")
                "defaultStratum" -> "Java"
                "sourceNames", "sourcePaths" -> sourceNames
                // The manager calls the (stratum, sourceName, line) overload.
                "locationsOfLine" -> (args?.lastOrNull() as? Int)
                    ?.takeIf { it in lines }
                    ?.let { listOf(location(self, it)) }
                    ?: emptyList<Location>()
                else -> null
            }
        }
        return self
    }

    private fun location(type: ReferenceType, line: Int): Location =
        proxy(Location::class.java) { method, _ ->
            when (method.name) {
                "declaringType" -> type
                "sourceName", "sourcePath" -> file.virtualFile.path
                "lineNumber" -> line
                else -> null
            }
        }

    /**
     * A reflective stub.
     *
     * `Object`'s methods are answered by identity rather than passed to the handler. They must be:
     * the position manager keys a `ConcurrentHashMap` on these, and a `hashCode` returning `null`
     * cannot be unboxed to `int` — which surfaces as a `NullPointerException` inside the cache,
     * pointing at production code that is behaving correctly.
     */
    @Suppress("UNCHECKED_CAST")
    private fun <T> proxy(type: Class<T>, handler: (java.lang.reflect.Method, Array<Any?>?) -> Any?): T =
        Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { self, method, args ->
            when (method.name) {
                "hashCode" -> System.identityHashCode(self)
                "equals" -> self === args?.getOrNull(0)
                "toString" -> "${type.simpleName}@${System.identityHashCode(self)}"
                else -> handler(method, args)
            }
        } as T
}
