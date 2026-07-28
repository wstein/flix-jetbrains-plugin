package dev.wstein.flixplugin.run

import com.intellij.execution.DefaultExecutionResult
import com.intellij.execution.ExecutionException
import com.intellij.execution.ExecutionResult
import com.intellij.execution.Executor
import com.intellij.execution.configurations.CommandLineState
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.LocatableConfigurationBase
import com.intellij.execution.configurations.RemoteConnection
import com.intellij.execution.configurations.RemoteConnectionCreator
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.configurations.RuntimeConfigurationError
import com.intellij.execution.process.KillableColoredProcessHandler
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ProgramRunner
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import dev.wstein.flixplugin.FlixJar
import dev.wstein.flixplugin.FlixLaunchCommand
import java.io.IOException
import java.nio.file.Path

/**
 * Runs or debugs a Flix program with the resolved Flix compiler.
 *
 * ## How Debug works
 *
 * The debuggee is an ordinary process that happens to load a JDWP agent, and IntelliJ's own Java
 * debugger attaches to it. That is the whole of ADR 0002 in one class: this plugin never speaks a
 * debug protocol, it only decides which port to listen on and hands that to the platform.
 *
 * The mechanism is [RemoteConnectionCreator]. `GenericDebuggerRunner` checks whether the profile
 * implements it and, if so, attaches to the connection it returns instead of building its own
 * command line. So the sequence for one Debug press is:
 *
 *  1. the runner calls [createRemoteConnection] -- a free port is chosen and recorded on the
 *     [ExecutionEnvironment];
 *  2. the runner calls [getState] -- the same port is read back and put on the command line;
 *  3. the process starts suspended, the debugger attaches, execution begins.
 *
 * The port travels on the environment rather than in a field because a field is shared by every
 * execution of the configuration: two debug sessions started together would overwrite each other's
 * port and one would attach to the wrong process. The environment is per-execution, which is
 * exactly the lifetime the port has.
 *
 * `--Xdebug` is not optional for a debug run and is not merely a JDWP switch: without it the
 * compiler emits no line numbers for `let`, calls, `if` or statement sequences, and breakpoints on
 * those lines can never bind. [FlixLaunchCommand] owns that rule.
 */
class FlixRunConfiguration(
    project: Project,
    factory: ConfigurationFactory,
    name: String,
) : LocatableConfigurationBase<FlixRunConfigurationOptions>(project, factory, name), RemoteConnectionCreator {

    public override fun getOptions(): FlixRunConfigurationOptions =
        super.getOptions() as FlixRunConfigurationOptions

    var entryPoint: String?
        get() = options.entryPoint
        set(value) {
            options.entryPoint = value
        }

    override fun getConfigurationEditor(): SettingsEditor<out LocatableConfigurationBase<FlixRunConfigurationOptions>> =
        FlixRunConfigurationEditor()

    /**
     * Fails early, in the configuration dialog, rather than at launch.
     *
     * Resolving the jar here means a missing compiler is reported where the user can act on it,
     * with the message [FlixJar] writes, instead of as a process that exits immediately.
     */
    override fun checkConfiguration() {
        try {
            resolveJar()
        } catch (e: IllegalStateException) {
            throw RuntimeConfigurationError(e.message)
        }
    }

    override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState {
        val jar = resolveJar()
        val debugPort = environment.getUserData(DEBUG_PORT)
        val command = if (debugPort == null) {
            FlixLaunchCommand.run(jar, entryPoint)
        } else {
            // suspend=y: the program must not run past its own entry point before the debugger has
            // attached, or a breakpoint on the first line never gets the chance to bind.
            FlixLaunchCommand.debug(jar, entryPoint, debugPort, true)
        }
        return FlixCommandLineState(environment, command)
    }

    // --- debugging -------------------------------------------------------------------------

    override fun createRemoteConnection(environment: ExecutionEnvironment): RemoteConnection {
        val port = try {
            FlixLaunchCommand.findFreePort()
        } catch (e: IOException) {
            throw ExecutionException("Could not allocate a port for the debugger", e)
        }
        environment.putUserData(DEBUG_PORT, port)

        // The debuggee listens and the IDE attaches -- `server` here describes the *IDE's* role, so
        // false is correct. Reversing it makes the IDE listen and nothing ever connects.
        return RemoteConnection(true, LOCALHOST, port.toString(), false)
    }

    /**
     * The debuggee is still starting when the IDE first tries to connect.
     *
     * The JVM has to load, open the JDWP transport and bind the port, all after the process exists.
     * Without polling the first connection attempt loses that race and the session fails with a
     * connection error that looks like a configuration fault.
     */
    override fun isPollConnection(): Boolean = true

    private fun resolveJar(): Path =
        FlixJar.resolve(project.basePath, System.getenv(FlixJar.PINNED_JAR_ENV))

    /**
     * Starts the compiler and wires its output to the console.
     *
     * [KillableColoredProcessHandler] rather than a plain one so Stop actually terminates a
     * compiler that is mid-build, and so the compiler's own coloured diagnostics survive.
     */
    private class FlixCommandLineState(
        environment: ExecutionEnvironment,
        private val command: List<String>,
    ) : CommandLineState(environment) {

        override fun startProcess(): ProcessHandler {
            val commandLine = GeneralCommandLine(command)
                .withWorkDirectory(environment.project.basePath)
                .withCharset(Charsets.UTF_8)
            val handler = KillableColoredProcessHandler(commandLine)
            // Prints the exit code in the console, which is the difference between "it stopped" and
            // "it failed" for a compiler that reports errors by exiting non-zero.
            ProcessTerminatedListener.attach(handler, environment.project)
            return handler
        }

        override fun execute(executor: Executor, runner: ProgramRunner<*>): ExecutionResult {
            val console = createConsole(executor)
            val handler = startProcess()
            console?.attachToProcess(handler)
            return DefaultExecutionResult(console, handler, *createActions(console, handler, executor))
        }
    }

    private companion object {
        private const val LOCALHOST = "localhost"

        /**
         * The port chosen for one debug execution.
         *
         * Per-[ExecutionEnvironment] rather than per-configuration: the runner asks for the
         * connection and the state separately, and both must mean the same port, but two concurrent
         * debug sessions of the same configuration must not.
         */
        private val DEBUG_PORT = Key.create<Int>("flix.debugPort")
    }
}
