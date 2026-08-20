package dev.wstein.flixplugin.run

import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.RemoteConnection
import com.intellij.util.execution.ParametersListUtil
import dev.wstein.flixplugin.FlixBuildSpec
import dev.wstein.flixplugin.FlixLaunchCommand
import java.io.IOException
import java.nio.file.Path

/**
 * One execution of a Flix program: what to run, and — when debugging — where the IDE should attach.
 *
 * Exists so a single object owns the invariant that would otherwise be split across two methods the
 * platform calls at different times: **the port the debugger is told to attach to is the port on
 * the debuggee's command line.** Nothing enforces that agreement, and a session where the two
 * disagree does not fail loudly — the IDE simply waits on a port nothing is listening on, which
 * reads as "the debugger is broken" rather than as a wiring mistake.
 *
 * ## A Run is one process and a Debug is two
 *
 * A Run is `flix run`, unchanged: the compiler builds and starts the program, and nothing here needs
 * to know how.
 *
 * A Debug cannot be, because `flix run` starts the program in a JVM **of its own**. An agent on that
 * command line is an agent on the *compiler*; the program then runs one process further down with no
 * agent at all, and every breakpoint silently fails to bind. So a debug session does the two halves
 * itself — [buildCommand] compiles and records, [programCommand] starts what it recorded — and the
 * agent goes on the second, which is the JVM the user's classes actually load in.
 *
 * Deliberately free of IntelliJ execution machinery beyond [RemoteConnection], so both halves can be
 * asserted on directly rather than by driving a launch.
 *
 * @param debugPort the JDWP port, or `null` for a plain Run
 */
internal class FlixLaunch(
    private val javaExecutable: String,
    private val jar: Path,
    private val entryPoint: String?,
    private val vmOptions: String? = null,
    private val programParameters: String? = null,
    val debugPort: Int?,
    private val projectRoot: Path? = null,
) {

    /**
     * The command for a plain Run: `flix run`, with the compiler doing the starting.
     *
     * Empty for a debug launch, which is [buildCommand] then [programCommand] instead. Reading this
     * on a debug launch is a mistake rather than a fallback: what it would produce is the command
     * that put the agent on the wrong process.
     */
    val command: List<String> = when (debugPort) {
        null -> FlixLaunchCommand.run(javaExecutable, jar, entryPoint).toMutableList().apply {
            // JVM options must precede `-jar`; the JVM treats everything after it as Flix CLI input.
            addAll(1, ParametersListUtil.parse(vmOptions.orEmpty()))
            // Arguments for the Flix `run` command belong after the command itself.
            addAll(ParametersListUtil.parse(programParameters.orEmpty()))
        }
        else -> emptyList()
    }

    /**
     * Phase one of a debug session: build, and leave the manifest behind for phase two.
     *
     * Carries no agent. This JVM is the compiler and nothing in it is being debugged.
     */
    val buildCommand: List<String>
        get() = FlixLaunchCommand.buildForDebug(javaExecutable, jar, entryPoint)

    /**
     * Phase two: the program, under the agent, started from what the build recorded.
     *
     * `suspend=y`: the program must not run past its own entry point before the debugger has
     * attached, or a breakpoint on the first line never gets the chance to bind.
     *
     * The `java` is [spec]'s and not [javaExecutable]. They answer different questions — which JVM
     * runs the *compiler*, and which JVM the compiler's output was built for — and substituting the
     * first for the second is a class-version error that names neither.
     */
    fun programCommand(spec: FlixBuildSpec): List<String> {
        val port = requireNotNull(debugPort) { "programCommand is for a debug launch; a Run uses `command`" }
        val mainClass = spec.mainClass()
            ?: throw ExecutionException(
                "The project has no entry point, so there is nothing to debug. " +
                    "Add a `main`, or name one in the run configuration.",
            )
        return FlixLaunchCommand.debugProgram(
            spec.java(),
            spec.classpath(),
            mainClass,
            ParametersListUtil.parse(vmOptions.orEmpty()),
            ParametersListUtil.parse(programParameters.orEmpty()),
            port,
            true,
        )
    }

    /** Where the build manifest of this launch's project is, or `null` when it has no root. */
    fun buildSpec(): FlixBuildSpec = FlixBuildSpec.read(
        projectRoot ?: throw ExecutionException("The project has no directory to build in."),
    )

    /**
     * Where the IDE should attach, or `null` for a plain Run.
     *
     * `server = false` describes the **IDE's** role: the debuggee listens and the IDE connects.
     * Reversing it makes the IDE listen and nothing ever arrives.
     */
    val remoteConnection: RemoteConnection? =
        debugPort?.let { RemoteConnection(true, LOCALHOST, it.toString(), false) }

    companion object {
        const val LOCALHOST: String = "localhost"

        /** Inherited by the debuggee, so an agent here is loaded alongside ours. */
        const val JAVA_TOOL_OPTIONS: String = "JAVA_TOOL_OPTIONS"

        /**
         * A launch for [jar], allocating a debug port only when [debug].
         *
         * The port is allocated once, here, rather than on each of the two reads. The platform calls
         * `getState` first and `createRemoteConnection` afterwards, so anything that re-derived it
         * would hand out two different ports.
         */
        fun of(
            javaExecutable: String,
            jar: Path,
            entryPoint: String?,
            vmOptions: String? = null,
            programParameters: String? = null,
            debug: Boolean,
            inheritedJavaToolOptions: String? = System.getenv(JAVA_TOOL_OPTIONS),
            projectRoot: Path? = null,
        ): FlixLaunch {
            val port = if (!debug) {
                null
            } else {
                // Before allocating anything: the debuggee inherits this process's environment, so
                // an agent already in JAVA_TOOL_OPTIONS would be loaded alongside the one this
                // launch adds. Two agents cannot share a debuggee (ADR 0002), and the JVM reports
                // that as a transport error at startup rather than as a configuration problem.
                try {
                    FlixLaunchCommand.requireNoInheritedJdwpAgent(inheritedJavaToolOptions)
                } catch (e: FlixLaunchCommand.JdwpAlreadyConfiguredException) {
                    throw ExecutionException(e.message, e)
                }
                try {
                    FlixLaunchCommand.findFreePort()
                } catch (e: IOException) {
                    throw ExecutionException("Could not allocate a port for the debugger", e)
                }
            }
            return FlixLaunch(javaExecutable, jar, entryPoint, vmOptions, programParameters, port, projectRoot)
        }
    }
}
