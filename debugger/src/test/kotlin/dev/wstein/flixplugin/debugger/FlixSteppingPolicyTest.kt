package dev.wstein.flixplugin.debugger

import com.sun.jdi.AbsentInformationException
import com.sun.jdi.Location
import com.sun.jdi.ReferenceType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

/**
 * Where Flix stepping may stop.
 *
 * Java's Step Over means *run until the line changes in this frame, or the frame pops*, which
 * assumes methods hold several lines. Flix's CPS output does not: each continuation frame covers one
 * source line and the generated `invoke()` bridges carry no line table at all, so the step always
 * ends on frame pop, in bytecode with no source. `FlixSteppingFilter` carries it onward; this pins
 * the rule that decides when.
 *
 * The cases below are the shapes a live session actually produces, taken from `flix-lab` frames
 * (`Clo$main$400067`, `dev.flix.runtime.Frame$.applyFrameStatic`) rather than invented.
 */
class FlixSteppingPolicyTest {

    // --- stop here ---------------------------------------------------------------------------

    @Test
    fun `stops on a continuation frame that has a Flix line`() {
        // The 80% shape: applyFrame with a single line entry. This is a real position the user can
        // see, and the whole point of stepping is to reach it.
        val frame = flixLocation("Clo\$main\$400067", "Main.flix", line = 53)
        assertFalse(FlixSourceLocations.isMachineryWithoutFlixLine(frame))
    }

    // --- keep going --------------------------------------------------------------------------

    @Test
    fun `steps through a generated bridge with no line table`() {
        // Clo$main$*.invoke() -- 0 of 325 carry a LineNumberTable. The class is still recorded as
        // compiled from Main.flix, which is what identifies it as ours.
        val bridge = flixLocation("Clo\$main\$400067", "Main.flix", line = -1)
        assertTrue(FlixSourceLocations.isMachineryWithoutFlixLine(bridge))
    }

    @Test
    fun `steps through the runtime trampoline`() {
        // dev.flix.runtime.Frame$.applyFrameStatic -- reported as `applyFrameStatic:-1` in the
        // stack. It has no line information and no .flix source, so only the package identifies it.
        val trampoline = runtimeLocation("dev.flix.runtime.Frame\$")
        assertTrue(FlixSourceLocations.isMachineryWithoutFlixLine(trampoline))
    }

    // --- never touch another language --------------------------------------------------------

    @Test
    fun `leaves a Java frame alone`() {
        assertFalse(FlixSourceLocations.isMachineryWithoutFlixLine(foreign("Greeter", "Greeter.java", 22)))
    }

    @Test
    fun `leaves a Java frame with no line information alone`() {
        // The case that makes "has no line number" an unsafe rule on its own. A class compiled
        // without -g looks exactly like a Flix bridge from the line table alone, and suppressing a
        // stop there would silently break Java stepping in a way that only shows up on someone
        // else's build.
        assertFalse(FlixSourceLocations.isMachineryWithoutFlixLine(foreign("Vendor", "Vendor.java", -1)))
    }

    @Test
    fun `leaves Kotlin, Scala and Groovy frames alone`() {
        for (source in listOf("Service.kt", "Model.scala", "Script.groovy")) {
            assertFalse(
                "must not claim $source",
                FlixSourceLocations.isMachineryWithoutFlixLine(foreign("Foo", source, -1)),
            )
        }
    }

    @Test
    fun `a package that merely starts similarly is not the Flix runtime`() {
        // Prefix matching on "dev.flix.runtime" without the dot would claim a user package called
        // dev.flixruntime, and stepping there would silently stop working.
        assertFalse(FlixSourceLocations.isFlixRuntime(runtimeLocation("dev.flixruntime.Thing")))
        assertTrue(FlixSourceLocations.isFlixRuntime(runtimeLocation("dev.flix.runtime.Result\$")))
    }

    // --- the runaway guard -------------------------------------------------------------------

    @Test
    fun `a step-over scope spends a finite budget and then gives up`() {
        // Re-entry into the stepped definition is not guaranteed -- the stepped line may be the last
        // one that executes. Without a bound the step would single-step to process exit instead of
        // stopping, which presents as a frozen IDE rather than as a wrong stop.
        val scope = FlixSteppingCommands.StepOverScope("Main.flix#42")
        var granted = 0
        while (scope.consume()) {
            granted++
            if (granted > 100_000) break // fail loudly rather than hang the suite
        }
        assertTrue("the budget must be finite", granted in 1..100_000)
        assertFalse("an exhausted scope stays exhausted", scope.consume())
    }

    // --- JDI stubs ---------------------------------------------------------------------------

    /** A class compiled from a `.flix` file, as JDI reports it. */
    private fun flixLocation(className: String, sourceName: String, line: Int): Location =
        location(referenceType(className, listOf(sourceName)), sourceName, line)

    /** A Flix runtime class: no `.flix` source, no line information. */
    private fun runtimeLocation(className: String): Location =
        location(referenceType(className, sourceNames = null), sourceName = null, line = -1)

    /** A class belonging to another language. */
    private fun foreign(className: String, sourceName: String, line: Int): Location =
        location(referenceType(className, listOf(sourceName)), sourceName, line)

    private fun referenceType(className: String, sourceNames: List<String>?): ReferenceType =
        proxy(ReferenceType::class.java) { method, _ ->
            when (method.name) {
                "name" -> className
                "availableStrata" -> listOf("Java")
                "defaultStratum" -> "Java"
                "sourceNames", "sourcePaths" -> sourceNames ?: throw AbsentInformationException()
                else -> null
            }
        }

    private fun location(type: ReferenceType, sourceName: String?, line: Int): Location =
        proxy(Location::class.java) { method, _ ->
            when (method.name) {
                "declaringType" -> type
                "sourceName", "sourcePath" -> sourceName ?: throw AbsentInformationException()
                "lineNumber" -> line
                else -> null
            }
        }

    @Suppress("UNCHECKED_CAST")
    private fun <T> proxy(type: Class<T>, handler: (java.lang.reflect.Method, Array<Any?>?) -> Any?): T =
        Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, args ->
            handler(method, args)
        } as T
}
