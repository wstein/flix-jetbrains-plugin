package dev.wstein.flixplugin.run

import com.intellij.execution.ExecutionException
import com.intellij.execution.Executor
import com.intellij.execution.CommonProgramRunConfigurationParameters
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.configurations.CommandLineState
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.LocatableConfigurationBase
import com.intellij.execution.configurations.ModuleRunProfile
import com.intellij.execution.configurations.RemoteConnection
import com.intellij.execution.configurations.RemoteConnectionCreator
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.configurations.RuntimeConfigurationError
import com.intellij.execution.process.KillableColoredProcessHandler
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
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
 * `GenericDebuggerRunner` gets there through two gates, and **both** have to be satisfied. Verified
 * by decompiling it from IU-2026.1.3 rather than inferred:
 *
 * ```java
 * public boolean canRun(String executorId, RunProfile profile) {
 *    return executorId.equals("Debug")
 *        && profile instanceof ModuleRunProfile          // gate 1: on the CONFIGURATION
 *        && !(profile instanceof RunConfigurationWithSuppressedDefaultDebugAction);
 * }
 *
 * protected RunContentDescriptor createContentDescriptor(RunProfileState state, ExecutionEnvironment env) {
 *    if (state instanceof RemoteConnectionCreator c) {   // gate 2: on the STATE
 *       ...attachVirtualMachine(state, env, c.createRemoteConnection(env), c.isPollConnection());
 *    }
 *    ...
 *    return null;                                        // silently, if nothing matches
 * }
 * ```
 *
 * [ModuleRunProfile] is a marker the configuration must carry, and `LocatableConfigurationBase` does
 * not carry it: without it the runner is never even selected. [RemoteConnectionCreator] must be on
 * the **state**, not here: `createContentDescriptor` never looks at the configuration. Failing
 * either produces the same silent nothing, which is why both are stated above.
 *
 * The order also runs opposite to the obvious reading: `getState` is called *first*, always, and
 * `createRemoteConnection` afterwards on the object it returned. So the port is chosen when the
 * state is constructed and both callers read the same field — see [FlixCommandLineState].
 *
 * `--Xdebug` is not optional for a debug run and is not merely a JDWP switch: without it the
 * compiler emits no line numbers for `let`, calls, `if` or statement sequences, and breakpoints on
 * those lines can never bind. [FlixLaunchCommand] owns that rule.
 */
class FlixRunConfiguration(
    project: Project,
    factory: ConfigurationFactory,
    name: String,
) : LocatableConfigurationBase<FlixRunConfigurationOptions>(project, factory, name),
    ModuleRunProfile,
    CommonProgramRunConfigurationParameters {

    public override fun getOptions(): FlixRunConfigurationOptions =
        super.getOptions() as FlixRunConfigurationOptions

    var entryPoint: String?
        get() = options.entryPoint
        set(value) {
            options.entryPoint = value
        }

    override fun getProgramParameters(): String? = options.programParameters

    override fun setProgramParameters(value: String?) {
        options.programParameters = value
    }

    override fun getWorkingDirectory(): String? = options.workingDirectory

    override fun setWorkingDirectory(value: String?) {
        options.workingDirectory = value
    }

    override fun getEnvs(): Map<String, String> = options.envs

    override fun setEnvs(value: Map<String, String>) {
        options.envs = value.toMutableMap()
    }

    override fun isPassParentEnvs(): Boolean = options.passParentEnvs

    override fun setPassParentEnvs(value: Boolean) {
        options.passParentEnvs = value
    }

    var vmOptions: String?
        get() = options.vmOptions
        set(value) {
            options.vmOptions = value
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

    /**
     * The project's modules, which is what gives the debug session its search scope.
     *
     * Empty would also satisfy [ModuleRunProfile], but `LineBreakpoint.isInScopeOf` consults
     * `debugProcess.getSearchScope()` for breakpoints in files under a Java source root -- so an
     * empty scope would leave Flix breakpoints working and quietly refuse the `.java` ones in the
     * same session, which is the interop this whole path exists for.
     */
    override fun getModules(): Array<Module> = ModuleManager.getInstance(project).modules

    override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState =
        FlixCommandLineState(
            environment,
            FlixLaunch.of(
                FlixJar.javaExecutable(project.basePath),
                resolveJar(),
                entryPoint,
                vmOptions,
                programParameters,
                debug = executor.id == DefaultDebugExecutor.EXECUTOR_ID,
                inheritedJavaToolOptions = envs[FlixLaunch.JAVA_TOOL_OPTIONS]
                    ?: System.getenv(FlixLaunch.JAVA_TOOL_OPTIONS).takeIf { isPassParentEnvs },
            ),
            workingDirectory,
            envs.toMap(),
            isPassParentEnvs,
        )

    private fun resolveJar(): Path =
        FlixJar.resolve(project.basePath, System.getenv(FlixJar.JAR_ENV))

    /**
     * Starts the compiler, and -- for a debug run -- tells the platform where to attach.
     *
     * [RemoteConnectionCreator] lives here rather than on the configuration because
     * `GenericDebuggerRunner.createContentDescriptor` inspects the state and nothing else.
     *
     * The [FlixLaunch] is per-state, and a state is per-execution, so two concurrent debug sessions
     * cannot share a port. It also holds the agreement between the port on the command line and the
     * port in the connection, which is the part that fails quietly when it breaks.
     *
     * [KillableColoredProcessHandler] so Stop actually terminates a compiler mid-build, and so the
     * compiler's own coloured diagnostics survive.
     */
    internal class FlixCommandLineState(
        environment: ExecutionEnvironment,
        private val launch: FlixLaunch,
        private val workingDirectory: String?,
        private val envs: Map<String, String>,
        private val passParentEnvs: Boolean,
    ) : CommandLineState(environment), RemoteConnectionCreator {

        override fun createRemoteConnection(environment: ExecutionEnvironment): RemoteConnection? =
            launch.remoteConnection

        /**
         * The debuggee is still starting when the IDE first tries to connect: the JVM has to load,
         * open the JDWP transport and bind the port, all after the process exists. Without polling
         * the first attempt loses that race and the session fails with what looks like a
         * configuration error.
         */
        override fun isPollConnection(): Boolean = true

        override fun startProcess(): ProcessHandler {
            val commandLine = GeneralCommandLine(launch.command)
                .withWorkDirectory(workingDirectory?.takeIf { it.isNotBlank() } ?: environment.project.basePath)
                .withEnvironment(envs)
                .withParentEnvironmentType(
                    if (passParentEnvs) GeneralCommandLine.ParentEnvironmentType.CONSOLE
                    else GeneralCommandLine.ParentEnvironmentType.NONE,
                )
                .withCharset(Charsets.UTF_8)
            val handler = KillableColoredProcessHandler(commandLine)
            // Prints the exit code, which is the difference between "it stopped" and "it failed"
            // for a compiler that reports errors by exiting non-zero.
            ProcessTerminatedListener.attach(handler, environment.project)
            return handler
        }
    }

}
