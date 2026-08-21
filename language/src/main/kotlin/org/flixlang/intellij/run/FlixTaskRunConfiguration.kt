package org.flixlang.intellij.run

import com.intellij.execution.ExecutionException
import com.intellij.execution.Executor
import com.intellij.execution.configurations.CommandLineState
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.LocatableConfigurationBase
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.configurations.RuntimeConfigurationError
import com.intellij.execution.process.KillableColoredProcessHandler
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.testframework.sm.SMTestRunnerConnectionUtil
import com.intellij.execution.ui.ConsoleView
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.util.execution.ParametersListUtil
import de.wstein.flixplugin.FlixJar
import de.wstein.flixplugin.FlixLaunchCommand
import de.wstein.flixplugin.FlixTask
import org.flixlang.intellij.settings.FlixSettings
import java.nio.file.Path

/**
 * Runs one Flix subcommand -- `build`, `test`, `doc` and the rest -- against the resolved compiler.
 *
 * ## Why a run configuration rather than a terminal command
 *
 * The same command in a terminal gives text. This gives the platform's own process lifecycle: a
 * Stop button that actually kills the process tree, an exit code the Run widget reports, re-run,
 * a named entry in the run history, and -- through [FlixCompilerOutputFilter] -- diagnostics whose
 * line numbers are links into the source.
 *
 * ## Why it is not the Flix run/debug configuration
 *
 * That one exists to attach a debugger, so it lives in the debugger module and needs the Java
 * plugin; `run` here is the plain CLI invocation, available wherever the plugin loads. The two
 * share [FlixLaunchCommand], so the invocation rules are stated once.
 *
 * ## Why the jar is checked before launching
 *
 * `java -jar <missing>` fails with a JVM error naming a path, which does not tell a user that the
 * plugin resolves `flix.jar` from the project root or `$FLIX_JAR`. [checkConfiguration] refuses in
 * the dialog instead, where the message can say what to do.
 */
class FlixTaskRunConfiguration(
    project: Project,
    factory: ConfigurationFactory,
    name: String,
) : LocatableConfigurationBase<FlixTaskRunConfigurationOptions>(project, factory, name) {

    public override fun getOptions(): FlixTaskRunConfigurationOptions =
        super.getOptions() as FlixTaskRunConfigurationOptions

    /**
     * The subcommand to run.
     *
     * Falls back to [FlixTask.BUILD] only for a configuration whose stored command no longer names
     * a task; [checkConfiguration] reports that separately, so the fallback is what keeps the
     * dialog openable rather than what decides the run.
     */
    var task: FlixTask
        get() = FlixTask.byCommand(options.task).orElse(FlixTask.BUILD)
        set(value) {
            options.task = value.command()
        }

    var arguments: String?
        get() = options.arguments
        set(value) {
            options.arguments = value
        }

    override fun getConfigurationEditor(): SettingsEditor<out LocatableConfigurationBase<FlixTaskRunConfigurationOptions>> =
        FlixTaskRunConfigurationEditor()

    override fun checkConfiguration() {
        if (FlixTask.byCommand(options.task).isEmpty) {
            throw RuntimeConfigurationError(
                "'${options.task}' is not a Flix subcommand. Pick one from the Task list.",
            )
        }
        try {
            resolveJar()
        } catch (e: IllegalStateException) {
            throw RuntimeConfigurationError(e.message)
        }
    }

    override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState {
        val jar = try {
            resolveJar()
        } catch (e: IllegalStateException) {
            throw ExecutionException(e.message, e)
        }
        return FlixTaskState(environment, commandFor(jar), workingDirectory(), this)
    }

    /**
     * The command this configuration runs.
     *
     * Separated from the launch so the part that can be wrong is testable without starting a
     * process: which half of the command line each argument lands in, and in what order.
     * Project-wide arguments come first and the configuration's own after them, so a flag set on
     * one task overrides the same flag set for every task rather than the other way round.
     */
    internal fun commandFor(jar: Path): List<String> {
        val settings = FlixSettings.getInstance(project)
        return FlixLaunchCommand.task(
            // The JDK a flixw wrapper pinned, else the one on PATH. A pinned compiler on an
            // unpinned runtime is the half-reproducible state the wrapper exists to prevent.
            FlixJar.javaExecutable(project.basePath),
            jar,
            task.command(),
            settings.jvmArguments,
            settings.flixArguments + ParametersListUtil.parse(arguments.orEmpty()) + eventsFlag(),
        )
    }

    /**
     * `--events-json` for the test task, nothing for any other.
     *
     * Added here rather than offered in the arguments field, because it is not a preference: it is
     * how the console and the process agree to talk. A user who cleared the field would get a run
     * whose output the test tree cannot read, and the symptom -- an empty tree beside a console
     * full of JSON -- says nothing about what was removed.
     *
     * Last in the list so that it survives whatever the settings or the configuration put before
     * it. The flag takes no value, so there is nothing for a stray argument to bind to.
     */
    private fun eventsFlag(): List<String> =
        if (task == FlixTask.TEST) listOf(EVENTS_JSON) else emptyList()

    private fun resolveJar(): Path = FlixJar.resolve(project.basePath, System.getenv(FlixJar.JAR_ENV))

    /** The project directory: a Flix subcommand acts on the project it is run in. */
    private fun workingDirectory(): Path = Path.of(project.basePath ?: ".")

    /** One task execution: the command line, the process, and the console attached to it. */
    private class FlixTaskState(
        environment: ExecutionEnvironment,
        private val command: List<String>,
        private val workingDirectory: Path,
        private val configuration: FlixTaskRunConfiguration,
    ) : CommandLineState(environment) {

        init {
            // The link from a diagnostic's line number back to the source. Registered on the
            // console builder rather than added afterwards, because the console is built by the
            // platform and there is no later moment that reliably has it.
            consoleBuilder.addFilter(FlixCompilerOutputFilter(environment.project, workingDirectory))
        }

        /**
         * A test tree for `test`, and the ordinary console for everything else.
         *
         * `build` and `doc` produce compiler output that [FlixCompilerOutputFilter] makes navigable
         * and a test tree would have nowhere to put; `test` produces events that the plain console
         * would show as raw JSON. The two are different renderings because the two commands emit
         * different things, not because one is nicer.
         */
        override fun createConsole(executor: Executor): ConsoleView? {
            if (configuration.task != FlixTask.TEST) {
                return super.createConsole(executor)
            }
            val properties = FlixTestConsoleProperties(configuration, executor, workingDirectory)
            // The same link the plain console has. A test's captured output is compiler output too
            // when the failure is a stack trace, and losing the link inside the tree would make the
            // tree worse than the console it replaces.
            properties.addStackTraceFilter(FlixCompilerOutputFilter(environment.project, workingDirectory))
            return SMTestRunnerConnectionUtil.createConsole(properties)
        }

        override fun startProcess(): ProcessHandler {
            val commandLine = GeneralCommandLine(command).withWorkingDirectory(workingDirectory)

            // Killable so Stop terminates the compiler rather than detaching from it; coloured so
            // the compiler's own formatting survives, which is most of what makes its errors
            // readable.
            val handler = KillableColoredProcessHandler(commandLine)
            ProcessTerminatedListener.attach(handler)
            return handler
        }
    }

    companion object {
        /**
         * Makes `flix test` report each test as a line of JSON instead of rendering a terminal.
         *
         * Named here rather than spelled inline so the flag the command carries and the flag the
         * tests assert are one string.
         */
        internal const val EVENTS_JSON: String = "--events-json"
    }
}
