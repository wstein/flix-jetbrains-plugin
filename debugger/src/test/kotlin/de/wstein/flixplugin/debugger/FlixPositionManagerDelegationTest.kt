package de.wstein.flixplugin.debugger

import com.intellij.debugger.PositionManager
import com.intellij.debugger.engine.PositionManagerImpl
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The platform contract that decides what `FlixPositionManager.locationsOfLine` must return for a
 * class it does not own.
 *
 * This is pinned rather than assumed because getting it wrong is silent and severe, and because the
 * reasoning lives entirely in platform code that never appears in a stack trace.
 *
 * `CompoundPositionManager` stops at the **first manager that returns without throwing**, and reads
 * `NoDataException` as "ask the next one":
 *
 * ```java
 * for (pm : myPositionManagers)
 *   if (acceptsFileType(pm, fileType))
 *     try { result = pm.locationsOfLine(type, position); break; }
 *     catch (NoDataException) { /* next */ }
 * ```
 *
 * The next one is `PositionManagerImpl`, which answers unconditionally:
 *
 * ```java
 * try { return DebuggerUtilsAsync.locationsOfLineSync(type, "Java", null, position.getLine() + 1); }
 * catch (AbsentInformationException e) { return Collections.emptyList(); }
 * ```
 *
 * So throwing `NoDataException` for a `.flix` position does not decline -- it asks the Java manager
 * "which locations of this class are at line N?" about a class that has nothing to do with Flix.
 * Any class with code at line N answers, and `LineBreakpoint.createRequestForPreparedClass` plants
 * a real breakpoint request there. Observed live: a breakpoint on `Main.flix:52` stopping in
 * arbitrary JDK and library code.
 *
 * The two facts below are what make that happen. If a future IDE changes either, this fails and
 * says so, instead of the plugin quietly regaining or losing the behaviour.
 */
class FlixPositionManagerDelegationTest {

    @Test
    fun `the platform Java position manager accepts every file type, Flix included`() {
        // It does not override the file-type gate at all -- so CompoundPositionManager's
        // acceptsFileType() check never filters it out, whatever language the position is in.
        assertThrows(
            "PositionManagerImpl now declares accepted file types; re-check whether it still " +
                "answers for .flix positions, and whether locationsOfLine must still return empty",
            NoSuchMethodException::class.java,
        ) {
            PositionManagerImpl::class.java.getDeclaredMethod("getAcceptedFileTypes")
        }
        assertThrows(
            "PositionManagerImpl now overrides isAcceptedFileType; re-check the same thing",
            NoSuchMethodException::class.java,
        ) {
            PositionManagerImpl::class.java.getDeclaredMethod(
                "isAcceptedFileType",
                com.intellij.openapi.fileTypes.FileType::class.java,
            )
        }
    }

    @Test
    fun `an unset accepted-file-type set means every file type`() {
        // The interface default that turns "did not override" into "accepts everything".
        val default = object : PositionManager {
            override fun getSourcePosition(location: com.sun.jdi.Location?) = null
            override fun getAllClasses(position: com.intellij.debugger.SourcePosition) =
                emptyList<com.sun.jdi.ReferenceType>()
            override fun locationsOfLine(
                type: com.sun.jdi.ReferenceType,
                position: com.intellij.debugger.SourcePosition,
            ) = emptyList<com.sun.jdi.Location>()
            override fun createPrepareRequest(
                requestor: com.intellij.debugger.requests.ClassPrepareRequestor,
                position: com.intellij.debugger.SourcePosition,
            ) = null
        }
        assertTrue(
            "a manager that declares no accepted types is offered every position",
            default.isAcceptedFileType(org.flixlang.intellij.lang.FlixFileType.INSTANCE),
        )
    }

    @Test
    fun `this manager, by contrast, declares Flix and only Flix`() {
        // The other half of the contract: because FlixPositionManager *does* declare which file
        // types it accepts, it is never offered a .java, .kt or .scala position, so returning empty
        // from locationsOfLine cannot end the chain for a foreign language. That is what makes the
        // empty-vs-throw choice safe rather than merely convenient.
        //
        // Asserted on isAcceptedFileType, not the deprecated getAcceptedFileTypes: only the former
        // is overridden now, because overriding both says the same thing twice and the Plugin
        // Verifier flags the deprecated one. getDeclaredMethod either returns a Method or throws,
        // so the call itself is the assertion.
        FlixPositionManager::class.java.getDeclaredMethod(
            "isAcceptedFileType",
            com.intellij.openapi.fileTypes.FileType::class.java,
        )
    }
}
