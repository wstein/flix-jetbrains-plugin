package de.wstein.flixplugin.run

import com.intellij.execution.configurations.CommandLineState
import com.intellij.execution.configurations.ModuleRunProfile
import com.intellij.execution.configurations.RemoteConnectionCreator
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins the two silent gates imposed by GenericDebuggerRunner for an individual Flix test. */
class FlixTestDebuggerGatesTest {

    @Test
    fun `the test configuration is a module run profile`() {
        assertTrue(
            "GenericDebuggerRunner rejects a test configuration that is not a ModuleRunProfile",
            ModuleRunProfile::class.java.isAssignableFrom(FlixTestRunConfiguration::class.java),
        )
    }

    @Test
    fun `the test debug state creates a remote connection`() {
        val stateClass = FlixTestRunConfiguration::class.java.declaredClasses
            .singleOrNull { CommandLineState::class.java.isAssignableFrom(it) }
        assertTrue(
            "FlixTestRunConfiguration must declare one CommandLineState; found " +
                FlixTestRunConfiguration::class.java.declaredClasses.joinToString { it.simpleName },
            stateClass != null,
        )
        assertTrue(
            "${stateClass!!.simpleName} must implement RemoteConnectionCreator",
            RemoteConnectionCreator::class.java.isAssignableFrom(stateClass),
        )
    }
}
