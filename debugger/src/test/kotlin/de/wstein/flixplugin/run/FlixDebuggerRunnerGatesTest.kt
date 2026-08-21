package de.wstein.flixplugin.run

import com.intellij.execution.configurations.CommandLineState
import com.intellij.execution.configurations.ModuleRunProfile
import com.intellij.execution.configurations.RemoteConnectionCreator
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two conditions `GenericDebuggerRunner` imposes before it will debug anything.
 *
 * Both are pinned because failing either produces the *same silent nothing*: Debug appears to do
 * work and no session ever starts. Nothing throws, nothing is logged, and the natural conclusion is
 * that the position manager is at fault — which is where the first investigation went.
 *
 * Decompiled from IU-2026.1.3:
 *
 * ```java
 * public boolean canRun(String executorId, RunProfile profile) {
 *    return executorId.equals("Debug")
 *        && profile instanceof ModuleRunProfile                                  // gate 1
 *        && !(profile instanceof RunConfigurationWithSuppressedDefaultDebugAction);
 * }
 *
 * protected RunContentDescriptor createContentDescriptor(RunProfileState state, ExecutionEnvironment env) {
 *    if (state instanceof RemoteConnectionCreator c) { ... }                     // gate 2
 *    if (state instanceof JavaCommandLine) { ... }
 *    else if (state instanceof PatchedRunnableState) { ... }
 *    else if (state instanceof RemoteState) { ... }
 *    else return null;                                                           // silently
 * }
 * ```
 *
 * The two markers sit on **different objects**, which is the trap: gate 1 tests the configuration,
 * gate 2 tests the state it produces. Putting both on the configuration compiles, reads correctly,
 * and never debugs.
 *
 * Type relationships are asserted rather than a session driven: a real one needs a project, a JDWP
 * agent and a live VM, while what actually broke here is exactly this — which interface sits on
 * which class.
 */
class FlixDebuggerRunnerGatesTest {

    @Test
    fun `gate 1 - the configuration is a ModuleRunProfile`() {
        // LocatableConfigurationBase does NOT supply this. Without it canRun returns false and
        // GenericDebuggerRunner is never even selected for the Debug executor.
        assertTrue(
            "FlixRunConfiguration must implement ModuleRunProfile, or GenericDebuggerRunner.canRun " +
                "rejects it and Debug silently does nothing",
            ModuleRunProfile::class.java.isAssignableFrom(FlixRunConfiguration::class.java),
        )
    }

    @Test
    fun `gate 2 - the run profile state is a RemoteConnectionCreator`() {
        // createContentDescriptor inspects the state and never the configuration. The state class is
        // private, so it is located through the configuration's declared nested classes -- naming it
        // by string would pass while pointing at nothing after a rename.
        val stateClass = FlixRunConfiguration::class.java.declaredClasses
            .singleOrNull { CommandLineState::class.java.isAssignableFrom(it) }
        assertTrue(
            "FlixRunConfiguration must declare exactly one CommandLineState; found " +
                FlixRunConfiguration::class.java.declaredClasses.joinToString { it.simpleName },
            stateClass != null,
        )
        assertTrue(
            "${stateClass!!.simpleName} must implement RemoteConnectionCreator: " +
                "createContentDescriptor tests the state, not the configuration, and returns null " +
                "for a state matching none of its four branches",
            RemoteConnectionCreator::class.java.isAssignableFrom(stateClass),
        )
    }

    @Test
    fun `the state is not one of the branches that would build its own command line`() {
        // JavaCommandLine and RemoteState are checked *after* RemoteConnectionCreator, so matching
        // one of them as well would be harmless today and load-bearing if the order ever changed.
        // Asserting the state matches exactly one branch keeps the reason it works unambiguous.
        val stateClass = FlixRunConfiguration::class.java.declaredClasses
            .single { CommandLineState::class.java.isAssignableFrom(it) }
        val alsoMatches = listOf(
            "com.intellij.execution.configurations.JavaCommandLine",
            "com.intellij.execution.configurations.RemoteState",
        ).filter { name ->
            runCatching { Class.forName(name).isAssignableFrom(stateClass) }.getOrDefault(false)
        }
        assertTrue(
            "the state should match only the RemoteConnectionCreator branch, but also matches $alsoMatches",
            alsoMatches.isEmpty(),
        )
    }
}
