package dev.wstein.flixplugin.debugger

import com.intellij.debugger.PositionManager
import com.intellij.debugger.engine.DebugProcess
import com.intellij.debugger.engine.SuspendContext
import com.intellij.openapi.util.Key
import com.sun.jdi.AbsentInformationException
import com.sun.jdi.Location
import com.sun.jdi.ReferenceType
import com.sun.jdi.request.StepRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

/**
 * [FlixSteppingFilter]'s own decision, rather than the rules it consults.
 *
 * `FlixSteppingPolicyTest` covers `isMachineryWithoutFlixLine` and the budget; `FlixDefinitionScopeTest`
 * covers which definition a position belongs to. Both were well covered while the class that wires
 * them together had no test at all — so a fault in the wiring itself (consulting the wrong scope,
 * forgetting to resolve the position, inverting a branch) would have been caught by nothing.
 *
 * Driven through the real [FlixSteppingFilter.isApplicable] with JDI and platform interfaces stubbed
 * reflectively, in the style already used across this module. The one case not covered here is a
 * position that resolves to a *matching* Flix definition, which needs real PSI and a project; the
 * comparison itself is covered by `FlixDefinitionScopeTest`.
 */
class FlixSteppingFilterTest {

    private val filter = FlixSteppingFilter()

    // --- Step Into: no scope recorded, so the first Flix line wins ---------------------------

    @Test
    fun `steps through machinery even when no step-over is in progress`() {
        // A generated bridge or the trampoline: nothing to show, so carry on regardless of which
        // action is running. This is what stops Step Into landing in decompiled bytecode.
        val context = suspendContext(location = runtimeLocation(), scope = null)
        assertTrue(filter.isApplicable(context))
    }

    @Test
    fun `stops at a Flix line when no step-over is in progress`() {
        // Step Into records no scope, so it must stop at the first Flix line it reaches. Were the
        // filter to consult a stale or absent scope as "keep going", Step Into would run away.
        val context = suspendContext(location = flixLocation(line = 53), scope = null)
        assertFalse(filter.isApplicable(context))
    }

    // --- Step Over: confined to the definition it began in ------------------------------------

    @Test
    fun `passes through a frame that is not the stepped definition`() {
        // getSourcePosition returns null here, which is what a Java or Kotlin frame produces: no
        // Flix definition. That must read as "still travelling", not as "arrived" -- reading it as
        // arrived is what once halted a Step Over inside Greeter.java.
        val context = suspendContext(location = flixLocation(line = 53), scope = "Main.flix#42")
        assertTrue(filter.isApplicable(context))
    }

    @Test
    fun `gives up and stops once the budget is spent`() {
        // Re-entry into the stepped definition is not guaranteed -- the stepped line may be the last
        // one that executes. Without this the step would single-step to process exit, which presents
        // as a frozen IDE rather than a wrong stop.
        val scope = FlixSteppingCommands.StepOverScope("Main.flix#42")
        @Suppress("ControlFlowWithEmptyBody")
        while (scope.consume()) {
        }

        val context = suspendContext(location = flixLocation(line = 53), scope = scope)
        assertFalse("an exhausted budget must stop, not keep stepping", filter.isApplicable(context))
        assertNull(
            "and it must clear the scope, or every later step inherits the exhausted one",
            FlixSteppingCommands.scopeOf(context.debugProcess),
        )
    }

    // --- the step it asks for ------------------------------------------------------------------

    @Test
    fun `resumes with STEP_INTO, never a shallower step`() {
        // The trampoline driving one continuation into the next is a loop inside a single frame, so
        // stepping out of it leaves the loop and abandons every continuation still to run.
        assertEquals(StepRequest.STEP_INTO, filter.getStepRequestDepth(null))
    }

    @Test
    fun `declines when there is no location to judge`() {
        assertFalse(filter.isApplicable(null))
        assertFalse(filter.isApplicable(suspendContext(location = null, scope = null)))
    }

    // --- stubs ---------------------------------------------------------------------------------

    private fun suspendContext(location: Location?, scope: Any?): SuspendContext {
        val userData = mutableMapOf<Key<*>, Any?>()
        scope?.let {
            userData[FlixSteppingCommands.STEP_OVER_SCOPE] =
                it as? FlixSteppingCommands.StepOverScope
                    ?: FlixSteppingCommands.StepOverScope(it as String)
        }

        // Returns null for every location, which is what a frame outside Flix produces. A position
        // that resolves to a real definition needs PSI, and is covered by FlixDefinitionScopeTest.
        val positionManager = proxy(PositionManager::class.java) { method, _ ->
            if (method.name == "getSourcePosition") null else null
        }
        val process = proxy(DebugProcess::class.java) { method, args ->
            when (method.name) {
                "getPositionManager" -> positionManager
                "getUserData" -> userData[args!![0] as Key<*>]
                "putUserData" -> userData[args!![0] as Key<*>] = args[1]
                else -> null
            }
        }
        val frameProxy = proxy(com.intellij.debugger.engine.jdi.StackFrameProxy::class.java) { method, _ ->
            if (method.name == "location") location else null
        }
        return proxy(SuspendContext::class.java) { method, _ ->
            when (method.name) {
                "getFrameProxy" -> frameProxy
                "getDebugProcess" -> process
                else -> null
            }
        }
    }

    /** A class compiled from a `.flix` file, with a real line. */
    private fun flixLocation(line: Int): Location =
        location(referenceType("Clo\$main\$400067", listOf("Main.flix")), "Main.flix", line)

    /** Flix's runtime trampoline: no `.flix` source, no line information. */
    private fun runtimeLocation(): Location =
        location(referenceType("dev.flix.runtime.Frame\$", null), null, -1)

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
                "method" -> null
                else -> null
            }
        }

    @Suppress("UNCHECKED_CAST")
    private fun <T> proxy(type: Class<T>, handler: (java.lang.reflect.Method, Array<Any?>?) -> Any?): T =
        Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, args ->
            handler(method, args)
        } as T
}

/** Convenience for the assertion above; `SuspendContext.getDebugProcess` is nullable in Java. */
private val SuspendContext.debugProcess: DebugProcess?
    get() = (this as com.intellij.debugger.engine.StackFrameContext).debugProcess

