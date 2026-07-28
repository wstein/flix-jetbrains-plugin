package dev.wstein.flixplugin.run

import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.RemoteConnection
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
 * Deliberately free of IntelliJ execution machinery beyond [RemoteConnection], so the agreement can
 * be tested directly rather than by driving a launch.
 *
 * @param debugPort the JDWP port, or `null` for a plain Run
 */
internal class FlixLaunch(
    private val jar: Path,
    private val entryPoint: String?,
    val debugPort: Int?,
) {

    /**
     * The full command, including the JDWP agent when debugging.
     *
     * `suspend=y`: the program must not run past its own entry point before the debugger has
     * attached, or a breakpoint on the first line never gets the chance to bind.
     */
    val command: List<String> = when (debugPort) {
        null -> FlixLaunchCommand.run(jar, entryPoint)
        else -> FlixLaunchCommand.debug(jar, entryPoint, debugPort, true)
    }

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
        fun of(jar: Path, entryPoint: String?, debug: Boolean): FlixLaunch {
            val port = if (!debug) {
                null
            } else {
                // Before allocating anything: the debuggee inherits this process's environment, so
                // an agent already in JAVA_TOOL_OPTIONS would be loaded alongside the one this
                // launch adds. Two agents cannot share a debuggee (ADR 0002), and the JVM reports
                // that as a transport error at startup rather than as a configuration problem.
                try {
                    FlixLaunchCommand.requireNoInheritedJdwpAgent(System.getenv(JAVA_TOOL_OPTIONS))
                } catch (e: FlixLaunchCommand.JdwpAlreadyConfiguredException) {
                    throw ExecutionException(e.message, e)
                }
                try {
                    FlixLaunchCommand.findFreePort()
                } catch (e: IOException) {
                    throw ExecutionException("Could not allocate a port for the debugger", e)
                }
            }
            return FlixLaunch(jar, entryPoint, port)
        }
    }
}
