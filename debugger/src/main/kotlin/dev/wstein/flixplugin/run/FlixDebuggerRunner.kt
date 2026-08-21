package dev.wstein.flixplugin.run

import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.RunProfile
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ExecutionUtil
import com.intellij.debugger.impl.GenericDebuggerRunner
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.util.Key

/**
 * Runs phase one of a debug launch off the EDT, and only then starts the debugger.
 *
 * ## The failure this exists for
 *
 * Debugging is two phases -- `flix build --Xdebug`, then the program JVM the agent is on -- and the
 * build used to run inside `startProcess`. That reads as the natural place for it: the state is
 * about to launch a program, and the program cannot be described until the build has written the
 * manifest that describes it. It is the wrong place, and the platform says so:
 *
 * ```
 * java.lang.Throwable: Synchronous execution on EDT: … flix build --Xdebug --yes --entrypoint main,
 *   see com.intellij.execution.process.OSProcessHandler#checkEdtAndReadAction() Javadoc
 *     at com.intellij.execution.process.OSProcessHandler.waitFor
 *     at dev.wstein.flixplugin.run.FlixRunConfiguration$FlixCommandLineState.buildPhase
 *     at com.intellij.debugger.DefaultDebugEnvironment.createExecutionResult
 *     at com.intellij.debugger.impl.GenericDebuggerRunner.createContentDescriptor
 * ```
 *
 * `startProcess` is not called off the EDT. Decompiled from IU-2026.1.3, the path that reaches it is
 * `ExecutionManagerImpl.doStartRunProfile` → `doRun` → `ActionsKt.runInEdt(...)` → the runner's
 * `doExecute` → `createContentDescriptor` → `DefaultDebugEnvironment.createExecutionResult` →
 * `CommandLineState.execute` → `startProcess`. Every one of those is on the EDT by construction:
 * `doExecute` begins with `FileDocumentManager.saveAllDocuments()`, which requires it.
 *
 * So the whole IDE froze for the length of a Flix build -- minutes, on a cold project -- and newer
 * platforms turn that into a logged error. Swapping the blocking call for another blocking call
 * would move the stack trace and not the freeze.
 *
 * ## Where the phase boundary belongs
 *
 * `ExecutionManagerImpl` already has a background stage before the EDT one, which is where Java's
 * *Build* step runs, but that is a `BeforeRunTask`: it appears in the configuration's *Before
 * launch* list, and a user who removes it would get a debug session over whatever the previous
 * build left behind. The build is not a step of this launch, it *is* the launch's first half; ADR
 * 0002 rests on the program JVM being the one the manifest describes.
 *
 * A runner of our own keeps that guarantee and still gets a background thread. `ProgramRunner.execute`
 * is the outermost point in a launch that belongs to the plugin, so this builds there, and re-enters
 * [GenericDebuggerRunner.execute] on success -- from `onSuccess`, which the platform calls on the
 * EDT, so the second half begins exactly where a launch normally does. Everything after it is
 * unchanged: the same two gates, the same connection, the same 30-second poll.
 *
 * Registered `order="first"` because `GenericDebuggerRunner.canRun` accepts a [FlixRunConfiguration]
 * too -- it asks only for `ModuleRunProfile` -- and the platform takes the first runner that answers.
 * Losing that race is silent: the build would be back on the EDT.
 *
 * ## What it does not do
 *
 * A plain Run is untouched. It is one process and one command (`flix run`), starts no debugger, and
 * has nothing to sequence -- so it keeps the platform's own runner, and this one refuses it.
 */
internal class FlixDebuggerRunner : GenericDebuggerRunner() {

    override fun getRunnerId(): String = RUNNER_ID

    /**
     * Only Flix, and only Debug.
     *
     * Narrower than [GenericDebuggerRunner.canRun] on purpose: this runner exists to sequence a Flix
     * build against a Flix debuggee, and it would have nothing to offer any other profile.
     */
    override fun canRun(executorId: String, profile: RunProfile): Boolean =
        executorId == DefaultDebugExecutor.EXECUTOR_ID && profile is FlixRunConfiguration

    override fun execute(environment: ExecutionEnvironment) {
        if (environment.getUserData(BUILD_OUTPUT) != null) {
            // The second pass, from `onSuccess` below. The build is done and its output is on the
            // environment for the console to replay; from here it is an ordinary debug launch.
            super.execute(environment)
            return
        }

        val state = environment.state as? FlixRunConfiguration.FlixCommandLineState
        if (state == null) {
            // Nothing to sequence, and nothing to be gained by refusing: a configuration that
            // produced no Flix state is the platform's own path, unchanged.
            super.execute(environment)
            return
        }

        object : Task.Backgroundable(environment.project, BUILD_TITLE, true) {
            private var output: String = ""

            override fun run(indicator: ProgressIndicator) {
                output = build(state.buildCommandLine(), indicator)
            }

            override fun onSuccess() {
                environment.putUserData(BUILD_OUTPUT, output)
                super@FlixDebuggerRunner.execute(environment)
            }

            override fun onThrowable(error: Throwable) {
                // The launch is over, and the reason is the compiler's own output. Reported through
                // the platform so it lands in the Debug tool window's balloon, where the user is
                // already looking, rather than in the log.
                ExecutionUtil.handleExecutionError(
                    environment,
                    error as? ExecutionException ?: ExecutionException(error),
                )
            }

            // No `onCancel`: cancelling is an answer, and nothing has started yet. The execution
            // manager is not told a run is in progress until `super.execute` runs, so there is no
            // pending state to unwind.
        }.queue()
    }

    /**
     * Builds, and returns what the compiler printed.
     *
     * A failed build fails the *launch*: the alternative is starting whatever the previous build
     * left behind, which is a debug session over code that is not the code on screen -- and the only
     * symptom is breakpoints landing on the wrong lines.
     *
     * No timeout, but cancellable. A cold project resolves dependencies and compiles the whole
     * program, which takes minutes on the first run; a bound short enough to catch a hung compiler
     * would fail every honest first build. The progress indicator is what makes that acceptable --
     * `runProcessWithProgressIndicator` kills the compiler when the user cancels, which a bare
     * `runProcess` cannot.
     */
    private fun build(commandLine: GeneralCommandLine, indicator: ProgressIndicator): String {
        val output = CapturingProcessHandler(commandLine).runProcessWithProgressIndicator(indicator)
        if (output.isCancelled) {
            throw ProcessCanceledException()
        }
        if (output.exitCode != 0) {
            throw ExecutionException(
                "The Flix build failed, so there is nothing to debug " +
                    "(exit code ${output.exitCode}).\n\n" +
                    (output.stderr.takeIf { it.isNotBlank() } ?: output.stdout).takeLast(4000),
            )
        }
        return output.stdout + output.stderr
    }

    companion object {
        /** Stable, and unique among runners: the platform keys per-runner settings on it. */
        const val RUNNER_ID: String = "FlixDebug"

        /** What the progress bar says while phase one runs. */
        const val BUILD_TITLE: String = "Building Flix program for debugging"

        /**
         * What the build printed, carried from phase one to the console phase two creates.
         *
         * On the environment because that is the one object both halves share, and because it is
         * per-execution: two debug sessions started at once each have their own.
         *
         * Its presence is also how the second pass of [execute] is told from the first. A launch
         * that reaches [FlixRunConfiguration.FlixCommandLineState.startProcess] without it did not
         * come through this runner, and that is an error rather than a build to run late -- by then
         * the EDT is the only thread there is.
         */
        val BUILD_OUTPUT: Key<String> = Key.create("flix.debug.build.output")

        /**
         * What phase one printed, or a refusal to start phase two without it.
         *
         * The refusal is the half of this that matters. Building here instead would be the original
         * defect restored -- by the time a launch is asking, it is on the EDT, and a Flix build
         * there freezes the IDE for minutes. Starting the program *without* building would be
         * worse: a debug session over whatever the last build left behind, whose only symptom is
         * breakpoints landing on the wrong lines.
         */
        fun buildOutputOf(environment: ExecutionEnvironment): String =
            environment.getUserData(BUILD_OUTPUT)
                ?: throw ExecutionException(
                    "This debug launch did not run the Flix build phase. Debug must go through " +
                        "$RUNNER_ID, which builds with --Xdebug on a background thread before the " +
                        "debugger attaches.",
                )
    }
}
