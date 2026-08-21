package dev.wstein.flixplugin.run

import com.intellij.execution.ExecutionException
import com.intellij.execution.Executor
import com.intellij.execution.configurations.RunProfile
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.project.Project
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText

/**
 * That the Flix build runs before the debugger starts, and never on the EDT.
 *
 * The build used to run inside `startProcess`, on the assumption -- written into a comment there --
 * that the platform calls it off the EDT. It does not, and the platform said so:
 *
 * ```
 * java.lang.Throwable: Synchronous execution on EDT: … flix build --Xdebug --yes --entrypoint main
 *     at com.intellij.execution.process.OSProcessHandler.waitFor
 *     at dev.wstein.flixplugin.run.FlixRunConfiguration$FlixCommandLineState.buildPhase
 * ```
 *
 * Everything that reaches `startProcess` is on the EDT by construction -- `GenericDebuggerRunner`
 * begins with `FileDocumentManager.saveAllDocuments()` -- so the whole IDE froze for the length of a
 * cold Flix build. [FlixDebuggerRunner] moves that phase to a background task and hands the launch
 * back afterwards.
 *
 * Each test below pins one part of that arrangement that fails *silently* when broken: a launch that
 * skips phase one, a runner that never gets selected, or a build command that drifts from the one
 * the state would have run.
 */
class FlixDebugPhaseOrderTest {

    @Test
    fun `debug without a completed build phase is refused, not built late`() {
        // The invariant that keeps the freeze from coming back. If `startProcess` fell back to
        // building when the marker is missing, every launch that bypassed the runner would be the
        // original bug again -- and it would look like a working debug session, only frozen.
        val refused = assertThrows(ExecutionException::class.java) {
            FlixDebuggerRunner.buildOutputOf(ExecutionEnvironment())
        }
        assertTrue(
            "the refusal must name the runner that owns phase one, so the cause is findable: " +
                refused.message,
            refused.message!!.contains(FlixDebuggerRunner.RUNNER_ID),
        )
    }

    @Test
    fun `the runner answers for Flix debug launches and for nothing else`() {
        val runner = FlixDebuggerRunner()
        val flix = configuration()
        val foreign = object : RunProfile {
            override fun getState(executor: Executor, environment: ExecutionEnvironment) = null
            override fun getIcon() = null
            override fun getName() = "not Flix"
        }

        assertTrue("Debug on a Flix configuration is the case this exists for", runner.canRun("Debug", flix))
        assertFalse("a Run has no phases to sequence", runner.canRun("Run", flix))
        assertFalse("another language's configuration is not ours to build", runner.canRun("Debug", foreign))
    }

    @Test
    fun `the runner is registered ahead of the platform's debugger runner`() {
        // `GenericDebuggerRunner.canRun` accepts a FlixRunConfiguration as well -- it asks only for
        // ModuleRunProfile -- and `ProgramRunner.getRunner` takes the first extension that answers.
        // So without order="first" the platform's runner can win, and the build is back on the EDT
        // with nothing anywhere to say the plugin's runner was skipped.
        val descriptor = repositoryRoot()
            .resolve("debugger/src/main/resources/flix.jetbrains.plugin.debugger.xml")
        assertTrue("no debugger module descriptor at $descriptor", descriptor.exists())

        val registration = Regex("""<programRunner\b[^>]*>""").findAll(descriptor.readText())
            .map { it.value }
            .singleOrNull { it.contains(FlixDebuggerRunner::class.java.name) }
        assertTrue(
            "the debugger module must register exactly one programRunner, FlixDebuggerRunner",
            registration != null,
        )
        assertTrue(
            "the registration must carry order=\"first\", or the platform may pick " +
                "GenericDebuggerRunner instead: $registration",
            registration!!.contains("""order="first""""),
        )
    }

    @Test
    fun `the runner identifies itself distinctly from the platform's`() {
        // Runner settings are stored per id. Sharing "Debug" would make this runner read and write
        // GenericDebuggerRunner's settings, which is a different class's idea of what they mean.
        assertEquals("FlixDebug", FlixDebuggerRunner().runnerId)
    }

    private fun configuration() =
        FlixRunConfiguration(project(), FlixRunConfigurationType().configurationFactories[0], "phase order")

    /**
     * A [Project] that answers nothing.
     *
     * `canRun` tests the profile's type and never asks it anything, so a configuration is all that
     * is needed and a real project would only be a fixture around a type check.
     */
    private fun project(): Project =
        Proxy.newProxyInstance(Project::class.java.classLoader, arrayOf(Project::class.java)) { _, method, _ ->
            if (method.returnType == java.lang.Boolean.TYPE) false else null
        } as Project

    /** Tests run from the module directory under Gradle, and from the repository root elsewhere. */
    private fun repositoryRoot(): Path {
        val cwd = Path.of("").toAbsolutePath()
        return if (cwd.fileName?.toString() == "debugger") cwd.parent else cwd
    }
}
